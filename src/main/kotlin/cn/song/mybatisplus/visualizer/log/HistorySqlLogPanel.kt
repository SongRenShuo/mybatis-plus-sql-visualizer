package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.ui.diff.SqlDiffer
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.SearchTextField
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.border.Border
import java.io.File
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 历史 SQL 面板（F-44-3 两级导航）：读取自动保存目录中的历史文件展示
 * （F-44-4：目录随插件更名，旧名 mybatis-plus-sql-visualizer 不再读取）。
 *
 * <p>目录解析与写端同源（[SqlLogAutoSaver.resolveHistoryDir]）——设置页「保存目录」填了自定义路径时，
 * 历史页读的就是同一个目录（T-HistoryTabAlways 前读端只认默认 .idea 路径，自定义目录下写的文件看不到）。
 *
 * <p>展示层级（用户要求）：
 * <ol>
 *   <li><b>一级（默认）</b>：文件列表——每行 = 文件名 | 文件大小 | SQL 条数；双击进入二级；</li>
 *   <li><b>二级</b>：单个文件的 SQL 列表——每行 = 耗时 + 执行时间 + 源码位置 + SQL 摘要（高亮）；
 *       双击 SQL → 预览弹窗；右键 → 复制 SQL / 跳转到源码；顶部「返回」回一级。</li>
 * </ol>
 *
 * <p>搜索框一级按文件名过滤、二级按 SQL 内容/源文件过滤（emptyText 动态切换）。
 */
class HistorySqlLogPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    // ---------- 一级：文件列表 ----------
    private val fileListModel = DefaultListModel<HistoryFileEntry>()
    private val fileList = JBList<HistoryFileEntry>(fileListModel).also {
        // T-UiNative P0：一级文件列表打字即搜（平台 ListSpeedSearch，按文件名匹配）
        ListSpeedSearch.installOn(it) { entry -> entry.displayName }
    }
    private var allFiles: List<HistoryFileEntry> = emptyList()

    // ---------- 二级：SQL 列表（复用 SqlEntryList——Ctrl+C 复制纯 SQL 链路） ----------
    private val sqlListModel = DefaultListModel<LogSqlEntry>()
    private val sqlList = ViewportWidthSqlEntryList(sqlListModel)

    /**
     * 二级列表渲染器（面板直持引用）。**勿经 [sqlList.cellRenderer] 取回**：平台
     * ExpandedItemListCellRendererWrapper 会包装列表渲染器（speed search 展开态），getter 拿到的是
     * wrapper——20261008 验收缺陷 #5 即「as? HistoryCellRenderer」静默失败致同模板徽章计数永不更新
     * （面板级计数正确、右键聚焦可用，唯独徽章不渲染）。回填一律走本字段。
     */
    private val sqlCellRenderer = HistoryCellRenderer()
    /** 二级速度搜索是否已安装。构造于 CardLayout 隐藏卡时安装不生效（真机 key 流不达、焦点不在），
     *  故延后到 [showFileContent]/[loadFileContentSync] 切卡可见后安装（T-UiNative T-UN-05）。 */
    private var sqlSpeedSearchInstalled = false
    private lateinit var previewHost: SqlListPreviewHost
    private val dockedPreview = SqlDockedPreview(project, sqlList) { previewHost.toggle() }
    private var currentFile: HistoryFileEntry? = null
    private var currentEntries: List<LogSqlEntry> = emptyList()

    /** T-SqlLogPerf：二级列表滚动宿主（构造期装配 adjustment 监听驱动滚动追加）。 */
    private lateinit var sqlScrollPane: JBScrollPane

    // ---------- O-90：同结构（结构相同，疑似 N+1）计数徽章与聚焦 ----------
    // 注：内部标识沿用 template* 命名；**面向用户**的文案自 T-DevAcceptFix2 ⑪ 起为「同结构 N 条 /
    // 只看同结构」（原「×N 同模板」与插件既有功能「SQL 快捷模板」撞名且 ×N 易被读成倍数）。

    /** O-90：当前文件全量条目的同模板计数（徽章渲染与右键聚焦动作共用；applySqlSearch 重建）。 */
    private var templateCounts: Map<String, Int> = emptyMap()

    /** F88 测试缝：非空时替代 [structureKeyCounts] 聚合（测试注入计数 lambda 断言聚合次数）。 */
    internal var structureKeyCountsHookForTest: ((List<LogSqlEntry>) -> Map<String, Int>)? = null

    /** F88：计数已对齐的条目快照（身份判据）——聚合只随快照换代重算，不随 apply 次数重算。 */
    private var templateCountsSnapshotFor: List<LogSqlEntry>? = null

    /** O-90：同模板聚焦键（非 null 时二级列表只显该模板条目；右键动作与 chip 点击经 [applyTemplateFocus]）。 */
    private var templateFocusKey: String? = null

    /** O-90 测试断言：当前同模板计数与聚焦键。 */
    internal val templateCountsForTest: Map<String, Int> get() = templateCounts

    /** O-90 测试断言：当前聚焦键（null = 未聚焦）。 */
    internal val templateFocusKeyForTest: String? get() = templateFocusKey

    /** O-90 同模板聚焦提示 chip（聚焦时显示该模板计数，点击清除；构建于 [buildUI] 工具栏）。 */
    private val templateFilterChip = javax.swing.JLabel().apply {
        isVisible = false
        font = font.deriveFont(java.awt.Font.BOLD)
        foreground = MpvsUiColors.TEMPLATE_N1
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = applyTemplateFocus(null)
        })
    }

    // ---------- T-Perf-05：后台任务代际 + 搜索防抖 ----------

    /** 后台刷新/加载代际：新任务启动即作废旧任务的 EDT apply。 */
    private var bgGeneration = 0

    private var initialScanRequested = false

    /** T-Perf-05：搜索防抖（每击键 O(N) clear+add → 停顿 200ms 应用一次）。 */
    private val searchDebounceTimer =
        javax.swing.Timer(SEARCH_DEBOUNCE_MS) { applySearch() }.apply { isRepeats = false }

    // ---------- T-SearchToggleNativeLook（O-137）：搜索框+开关迁移到平台原生形态 ----------
    //
    // SearchTextArea（Find in Files/Ctrl+F 同款）内嵌原生 ToggleMatchCase/ToggleRegex；
    // 两旗改从 NativeSearchTextField.findModel 读取（FindModel.isRegularExpressions/isCaseSensitive），
    // 过滤语义/memo 键/regex: 前缀完全不变。原生 Toggle 无独立 click 监听——测试缝直接置 FindModel 旗，
    // 生产路径由原生钮自己改旗。

    private val nativeSearch = NativeSearchTextField(
        placeholderText = null,
        showRegex = true,
        project = project,
    )

    private val searchField = nativeSearch

    /** 大小写敏感开关（原生钮状态落点；默认关=忽略大小写，三链语义统一）。 */
    private val matchCaseEnabled: Boolean get() = nativeSearch.isMatchCase()

    /** 正则开关（原生钮状态落点；默认关=子串语义；regex: 前缀独立生效）。 */
    private val regexEnabled: Boolean get() = nativeSearch.isRegex()

    /** 正则编译 memo（键 = 源串 + matchCase 旗，见 [HistoryRegexMemo]）。 */
    private val regexMemo = HistoryRegexMemo()

    /** 搜索框宿主：原生 SearchTextArea 自带内嵌开关+右侧读数，直接占满一行。 */
    private val searchBar = nativeSearch.row

    /** 测试断言/驱动：正则旗（等价用户点击 = 置 FindModel.isRegularExpressions 后应用过滤）。 */
    internal var regexToggleForTest: Boolean
        get() = regexEnabled
        set(value) {
            nativeSearch.findModel.isRegularExpressions = value
            applySearch()
        }

    /** 测试断言/驱动：大小写旗。 */
    internal var matchCaseToggleForTest: Boolean
        get() = matchCaseEnabled
        set(value) {
            nativeSearch.findModel.isCaseSensitive = value
            applySearch()
        }
    // ---------- T-UiNative T-UN-10：顶部导航动作（原手摆 28×28「仿平台按钮」JButton 平移） ----------

    /** 导航工具栏引用（层级切换后即时刷新 Back 可见性；T-UN-10）。 */
    private var _navToolbarRef: ActionToolbar? = null
    internal val previewToggleAction: ToggleAction = object : ToggleAction(
        MpSqlVisualizerBundle.message("log.dockedPreview.toggle"),
        MpSqlVisualizerBundle.message("log.dockedPreview.toggle.tip"),
        MpvsIcons.DockedPreview,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = dockedPreview.isOpen

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (dockedPreview.isOpen != state) previewHost.toggle()
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isVisible = !isShowingFiles
        }
    }

    /** 返回一级（原 backButton）：图标 Back、tooltip 沿用 log.history.back.tip；可见性 = 二级列表（update 输出）。 */
    private inner class BackAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Back
            e.presentation.description = MpSqlVisualizerBundle.message("log.history.back.tip")
            e.presentation.isVisible = !isShowingFiles
        }

        override fun actionPerformed(e: AnActionEvent) {
            showFileList()
        }
    }

    /** 刷新（原 refreshButton）：图标 Refresh、tooltip 沿用 log.history.refresh.tip。 */
    private inner class RefreshAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Refresh
            e.presentation.description = MpSqlVisualizerBundle.message("log.history.refresh.tip")
        }

        override fun actionPerformed(e: AnActionEvent) {
            refresh()
        }
    }

    /** 与日志页同款直接动作；历史只跳转，不引入实时尾部跟随。 */
    internal val scrollToEndAction: AnAction = object : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.ScrollToEnd
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.scrollBottom")
            // 日志页的 tip 描述实时跟随，历史使用同一中性动作文案，避免误导。
            e.presentation.description = MpSqlVisualizerBundle.message("log.panel.scrollBottom")
            e.presentation.isVisible = !isShowingFiles
            e.presentation.isEnabled = !isShowingFiles && !sqlListModel.isEmpty && !project.isDisposed
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (isShowingFiles || project.isDisposed || sqlListModel.isEmpty) return
            val generation = bgGeneration
            if (pendingAppendGen == generation && pendingAppendIndex < pendingEntries.size) {
                val remaining = pendingEntries.subList(pendingAppendIndex, pendingEntries.size)
                // 一次批量模型事件；先清待写状态，防滚动/布局监听重入重复追加。
                pendingEntries = emptyList()
                pendingAppendIndex = 0
                sqlListModel.addAll(remaining)
            }
            // 同步完成，不留下会滚动到新文件/新过滤结果的 invokeLater 请求。
            if (generation != bgGeneration || isShowingFiles || sqlListModel.isEmpty) return
            sqlList.ensureIndexIsVisible(sqlListModel.size() - 1)
            sqlScrollPane.verticalScrollBar.let { it.value = it.maximum }
        }
    }

    /**
     * 测试缝：导航工具栏动作组。
     *
     * `ActionGroup.getChildren` 是 override-only API，不能在插件字节码里出现（Marketplace 校验
     * 报 override-only usage）——生产侧只暴露组对象，取子项由测试代码自行调用。
     */
    internal val navigationToolbarGroupForTest: ActionGroup?
        get() = _navToolbarRef?.actionGroup

    /** 层级切换后即时刷新导航工具栏（Back 可见性走 update；组件不可见时平台跳过，无副作用）。 */
    private fun refreshNavToolbar() {
        _navToolbarRef?.updateActionsImmediately()
    }

    /** 二级列表当前行高（测试断言行高随模式重设用）。 */
    internal val sqlListFixedCellHeightForTest: Int get() = sqlList.fixedCellHeight

    /** 二级列表实例（测试断言 getCellBounds 行高与渲染 preferred 一致——布局缓存失效防回归用）。 */
    internal val sqlListForTest: javax.swing.JList<*> get() = sqlList

    /** 测试缝：面板直持的二级渲染器（勿走 sqlList.cellRenderer——平台 wrapper 包装，见字段 KDoc）。 */
    internal val sqlCellRendererForTest: HistoryCellRenderer get() = sqlCellRenderer

    /** 显示模式变更处理（topic 监听与测试共用）：行高随模式重设 + 二级可见时全量重建。
     *  多行片段缓存按条目内模式键自动失效（无需显式清）。日志面板同款处理经 topic 对齐。
     *  T-MergeFollowup-03（复查二轮）：BasicListUI 的 cellHeights 布局缓存不归 Swing layout 管
     *  （revalidate 无效），多行模式间切换 -1→-1 也不触发 setFixedCellHeight 的 propertyChange——
     *  updateUI() 重装列表 UI 强制布局状态失效（与日志页同款），applySqlSearch 全量重建作为双保险。
     *  T-DisplayModeToSettings：入口迁设置页「列表显示」，此处仅响应设置广播。 */
    internal fun onDisplayModeChanged() {
        applySqlListCellHeight()
        sqlList.updateUI()
        if (currentFile != null) applySqlSearch()
    }

    /**
     * 设置变更处理（topic 监听入口，T-HistoryTabAlways）：解析出的历史目录与上次扫描所用不同时
     * 直接重扫（用户在设置页改了「保存目录」，不重扫就还得手动点刷新），未变时只重算显示模式并重绘
     * （原 [onDisplayModeChanged] 行为）。
     */
    internal fun onSettingsChanged() {
        if (historyDirChangedSinceLastScan()) refresh() else onDisplayModeChanged()
    }

    /** T-HistoryTabAlways：解析出的历史目录是否与上次扫描所用不同（设置页改了「保存目录」）。
     *  测试直接断言本判据——[refresh] 是后台任务，headless 下到点不可控。 */
    internal fun historyDirChangedSinceLastScan(): Boolean = getHistoryDir() != lastScannedDir

    /**
     * 行高随显示模式分流（T-ListMode × T-LogListFreeze-04 权衡，与日志 tab [SqlLogPanel.applyListCellHeight] 同款）：
     * SINGLE_LINE 固定行高走 O(1) 布局快路径；KEYWORD_WRAP/FORMATTED 可变行高（条目行数不等），
     * 多行片段有条目级缓存，逐条测量只剩轻量组件布局。
     */
    internal fun applySqlListCellHeight() {
        sqlList.fixedCellHeight =
            if (PluginSettings.getInstance().sqlListDisplayMode() == SqlListDisplayMode.SINGLE_LINE) {
                sqlList.cellRenderer
                    .getListCellRendererComponent(sqlList, LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList()), 0, false, false)
                    .preferredSize.height
            } else {
                -1
            }
    }

    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)

    // ---------- 测试访问器（F-44-3 两级导航真实场景测试） ----------

    /** 一级文件列表模型条目（测试断言：文件名/大小/条数）。 */
    internal val fileListEntries: List<HistoryFileEntry>
        get() = (0 until fileListModel.size()).map { fileListModel.getElementAt(it) }

    /** 二级 SQL 列表模型条目（测试断言）。 */
    internal val sqlListEntries: List<LogSqlEntry>
        get() = (0 until sqlListModel.size()).map { sqlListModel.getElementAt(it) }

    /** 当前是否处于一级文件列表视图（测试断言）。 */
    internal val isShowingFiles: Boolean get() = currentFile == null

    /** 打开指定文件进入二级（测试驱动；等价双击文件）。 */
    internal fun openFileForTest(entry: HistoryFileEntry) = loadFileContentSync(entry)

    /** 返回一级（测试驱动；等价点击返回按钮）。 */
    internal fun backToFilesForTest() = showFileList()

    /** 设置搜索词（测试驱动；等价在搜索框输入，立即应用过滤——生产路径 DocumentListener 走防抖）。 */
    internal fun setSearchForTest(query: String) {
        searchField.text = query
        applySearch()
    }

    /** 读取搜索框当前文本（测试断言：进条目卡应被清空）。 */
    internal fun searchTextForTest(): String = searchField.text

    /**
     * 进条目卡前搜索框复位（生产异步路径与测试同步路径共用单源，防两处漂移）：
     * 用户定（20261010）文件级搜索词不带入条目级——清空触发防抖重过滤（空查询=全量条目）；
     * 历史记录在文件级应用时已入账，不受清空影响。
     */
    private fun clearSearchForEntryLevel() {
        searchField.text = ""
    }

    internal val previewHostForTest: SqlListPreviewHost get() = previewHost
    internal val dockedPreviewForTest: SqlDockedPreview get() = dockedPreview

    init {
        buildUI()
        addHierarchyListener { event ->
            if (event.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                scheduleInitialScanIfShowing()
            }
        }
    }

    internal fun scheduleInitialScanIfShowing(showing: () -> Boolean = { isShowing }) {
        if (!showing()) return
        javax.swing.SwingUtilities.invokeLater {
            if (showing() && !project.isDisposed) ensureInitialScan()
        }
    }

    internal fun ensureInitialScan(): Boolean {
        if (initialScanRequested) return false
        refresh()
        return true
    }

    private fun buildUI() {
        // 顶部工具栏：返回（仅二级可见）+ 刷新 + 搜索
        // T-UiNative T-UN-10：手摆按钮 → 平台 ActionToolbar（Back 可见性由 BackAction.update 按当前层级输出）
        val navToolbar = ActionManager.getInstance()
            .createActionToolbar("mpvs.history.nav", DefaultActionGroup(BackAction(), RefreshAction(), scrollToEndAction, previewToggleAction), true)
            .apply { targetComponent = this@HistorySqlLogPanel }
        _navToolbarRef = navToolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        toolbar.add(navToolbar.component)
        // T-DisplayModeToSettings：显示模式入口迁设置页「列表显示」（工具栏按钮移除）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.files")
        searchField.textEditor.minimumSize = Dimension(200, searchField.textEditor.minimumSize.height)
        searchField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = scheduleSearch()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = scheduleSearch()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = scheduleSearch()
        })
        // 原生按钮只修改 FindModel，不触发 DocumentListener；切旗必须同步重验并清除过期错误。
        nativeSearch.findModel.addObserver { applySearch() }
        // T-HistorySearchToggles：搜索框外包宿主容器（CENTER 搜索框 + EAST 正则/大小写两枚开关）
        toolbar.add(searchBar)
        // O-90：同模板聚焦提示 chip（聚焦时显示；点击清除）
        toolbar.add(templateFilterChip)
        add(toolbar, BorderLayout.NORTH)

        // T-ListMode：设置变更（含模式切换广播）→ 清二级列表渲染缓存并重绘；
        // connect(this) 随面板 Disposer 树自动注销。invokeLater 显式 ModalityState.any()
        // （设置页红线：模态弹窗存续期默认 modality 会挂起回调）
        project.messageBus.connect(this).subscribe(
            PluginSettings.SETTINGS_CHANGE_TOPIC,
            cn.song.mybatisplus.visualizer.config.SettingsChangeListener {
                // write-safe: UI-only（纯 Swing/只读，无文档写）
                ApplicationManager.getApplication().invokeLater(
                    { onSettingsChanged() },
                    com.intellij.openapi.application.ModalityState.any(),
                )
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener {
                ApplicationManager.getApplication().invokeLater(
                    { sqlList.repaint() },
                    ModalityState.any(),
                )
            },
        )

        // 一级：文件列表
        updateFilesEmptyText()
        fileList.cellRenderer = HistoryFileCellRenderer()
        // T-LogListFreeze-04：固定行高（单行恒定高）——JList 布局走 O(1) 快路径，不再逐条调渲染器测量
        fileList.fixedCellHeight = fileList.cellRenderer
            .getListCellRendererComponent(fileList, HistoryFileEntry(File("sample.sql"), 1), 0, false, false)
            .preferredSize.height
        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) {
                    fileList.selectedValue?.let { showFileContent(it) }
                }
            }
        })
        cards.add(JBScrollPane(fileList), CARD_FILES)

        // 二级：SQL 列表
        sqlList.emptyText.text = MpSqlVisualizerBundle.message("log.history.entries.empty")
        sqlList.cellRenderer = sqlCellRenderer
        applySqlListCellHeight()
        sqlList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                previewHost.onRowClick(e)
                if (javax.swing.SwingUtilities.isLeftMouseButton(e) && e.clickCount >= 2) {
                    previewHost.hideHint()
                    sqlList.selectedValue?.let { openPreview(it) }
                }
            }
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showSqlContextMenu(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showSqlContextMenu(e)
            }
        })
        // T-SqlLogPerf：二级列表滚动追加——滚动条逼近底部时续写下一批（fillSqlList 只写首屏）
        sqlScrollPane = JBScrollPane(sqlList).apply {
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        sqlScrollPane.verticalScrollBar.addAdjustmentListener { maybeAppendOnScroll() }
        previewHost = SqlListPreviewHost(sqlList, sqlScrollPane, dockedPreview, "mpvs.history.preview.split", showIconHint = true) {
            Toggleable.setSelected(previewToggleAction.templatePresentation, it)
        }
        cards.add(previewHost.component, CARD_SQL)
        add(cards, BorderLayout.CENTER)
    }

    // ---------- 视图切换 ----------

    private fun showFileList() {
        currentFile = null
        currentFileStamp = null
        currentEntries = emptyList()
        pendingEntries = emptyList() // F85（T-PerfSweep24）：返回一级清挂起批次（有 showing 守卫不误消费，清掉免滞留）
        if (dockedPreview.isOpen) previewHost.toggle()
        dockedPreview.resetContext()
        previewHost.hideHint()
        refreshNavToolbar() // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = false）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.files")
        cardLayout.show(cards, CARD_FILES)
        applyFileSearch()
    }

    private fun showFileContent(fileEntry: HistoryFileEntry) {
        currentFile = fileEntry
        templateFocusKey = null // O-90：换文件即清除同模板聚焦（旧文件的模板键对新文件无意义）
        // T-UiNative T-UN-05：二级 SQL 列表切卡可见后重挂速度搜索（构造于隐藏卡时 installOn
        // 仍不生效的兜底；标记防重复装——平台 installOn 重复挂载同一列表不幂等）
        if (!sqlSpeedSearchInstalled) {
            sqlSpeedSearchInstalled = true
            ListSpeedSearch.installOn(sqlList) {
                entry -> withTerminator(entry.restoredSql)
            }
        }
        refreshNavToolbar() // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = true）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.sql")
        cardLayout.show(cards, CARD_SQL)
        // T-Perf-05：立即清空旧文件内容（大文件后台读取期间不残留旧文件条目，防误操作）
        currentEntries = emptyList()
        clearSearchForEntryLevel()
        applySqlSearch()
        val gen = ++bgGeneration
        object : Task.Backgroundable(project, MpSqlVisualizerBundle.message("log.history.load.progress"), true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val entries = SqlLogFileReader.readFromFile(fileEntry.file)
                ApplicationManager.getApplication().invokeLater {
                    if (gen != bgGeneration || project.isDisposed) return@invokeLater
                    currentEntries = entries
                    // T-SqlLogPerf：记录加载完成时的文件快照——refresh() 据此免掉未变文件的全量重读
                    currentFileStamp = fileEntry.file.lastModified() to fileEntry.file.length()
                    applySqlSearch()
                    LOG.info("HistorySqlLogPanel.showFileContent: ${fileEntry.file.name} 共 ${entries.size} 条 SQL")
                }
            }
        }.queue()
    }

    /** 测试缝：同步加载文件内容（生产路径 showFileContent 已后台化，headless 测试需要确定性）。 */
    private fun loadFileContentSync(fileEntry: HistoryFileEntry) {
        currentFile = fileEntry
        templateFocusKey = null // O-90：换文件即清除同模板聚焦（与生产路径同语义）
        // T-UiNative T-UN-05：二级 SQL 列表可见后确保速度搜索安装（生产 showFileContent 已装，
        // 此同步路径 headless 下 CardLayout 不见真机 showing，marker 判定可能不触发，故统一补装）
        if (!sqlSpeedSearchInstalled) {
            sqlSpeedSearchInstalled = true
            ListSpeedSearch.installOn(sqlList) {
                entry -> withTerminator(entry.restoredSql)
            }
        }
        refreshNavToolbar() // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = true）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.sql")
        cardLayout.show(cards, CARD_SQL)
        clearSearchForEntryLevel()
        currentEntries = SqlLogFileReader.readFromFile(fileEntry.file)
        currentFileStamp = fileEntry.file.lastModified() to fileEntry.file.length()
        applySqlSearch()
    }

    // ---------- 刷新 ----------

    /**
     * T-CrudColor：仅重绘二级 SQL 列表（不重扫文件、不重载列表）。
     *
     * 设置页改了配色/标记样式后由 [SqlLogPanel] 的设置变更监听调用——渲染器直读设置，
     * 重绘即生效；走 repaint 而非 [refresh] 是为了不打断用户当前浏览位置、不触发磁盘扫描。
     */
    internal fun repaintSqlList() {
        sqlList.repaint()
    }

    /**
     * 扫描 .idea 目录：生成文件摘要列表；若正在浏览某文件且文件仍存在则保持二级并重载，否则回一级。
     *
     * T-Perf-05：目录扫描 + countInFile / readFromFile 全部移入 [Task.Backgroundable]
     * （100MB 自动保存文件的逐行扫描在 EDT 直跑必然长冻结）；EDT 只做结果应用。
     * 代际守卫：新一轮刷新/加载启动后，旧后台任务的 apply 自动作废。
     *
     * T-SqlLogPerf（20261006-1020 计划 P1）：浏览态刷新不再**无条件**全量重读当前文件——
     * 仅当 (lastModified, length) 与加载完成时快照 [currentFileStamp] 不一致（自动保存确有追加）
     * 才重读；未变则后台只做目录扫描 + countInFile（有缓存）。日常 IDE 实测 19MB/8708 条文件
     * 一次浏览窗口内被 refresh + showFileContent 全量解析约 3 遍（15~16.6s），此处消除 refresh 侧的重复成本。
     */
    /** F87 测试缝：refresh 实际调用计数（判别「构造即扫」与「懒首扫」——构造路径须为 0）。 */
    internal var refreshInvocationCountForTest: Int = 0
        private set

    fun refresh() {
        initialScanRequested = true
        refreshInvocationCountForTest++
        // T-HistoryTabAlways：记录本次扫描所用目录（EDT 侧纯字段写），供设置变更时判定要不要重扫
        lastScannedDir = getHistoryDir()
        val gen = ++bgGeneration
        val keepFile = currentFile?.file
        object : Task.Backgroundable(project, MpSqlVisualizerBundle.message("log.history.scan.progress"), true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val scanned = scanHistoryFiles(getHistoryDir())
                val keepReload = keepFile?.takeIf { it.exists() }?.let { f ->
                    val stamp = f.lastModified() to f.length()
                    // currentFileStamp 为 EDT 写的 Swing 字段，此处读只做「是否变化」启发判定：
                    // 竞态最坏结果是多读一次文件，无正确性风险（读到 null 视为变化 → 重读，行为同旧版）
                    if (stamp == currentFileStamp) null else SqlReload(f, SqlLogFileReader.readFromFile(f), stamp)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (gen != bgGeneration || project.isDisposed) return@invokeLater
                    applyRefresh(scanned, keepFile, keepReload)
                }
            }
        }.queue()
    }

    /** 测试缝：同步刷新（生产路径 refresh() 已后台化；headless 测试需要确定性）。 */
    internal fun refreshForTest() {
        initialScanRequested = true
        ++bgGeneration // 作废在途后台 apply（构造期 refresh() 的后台扫描可能尚未完成）
        val dir = getHistoryDir()
        lastScannedDir = dir
        val keepFile = currentFile?.file
        val keepReload = keepFile?.takeIf { it.exists() }?.let {
            SqlReload(it, SqlLogFileReader.readFromFile(it), it.lastModified() to it.length())
        }
        applyRefresh(scanHistoryFiles(dir), keepFile, keepReload)
    }

    /** 目录扫描 + 条目计数（纯 IO，后台执行）。 */
    private fun scanHistoryFiles(dir: String): List<HistoryFileEntry> {
        val directory = File(dir)
        val files = if (directory.isDirectory) {
            directory.listFiles { f -> f.extension == "sql" }?.sortedBy { it.name } ?: emptyList()
        } else {
            emptyList()
        }
        return files.map { file -> HistoryFileEntry(file, SqlLogFileReader.countInFile(file)) }
    }

    /**
     * 一级列表空态渲染（T-SettingsFocusFromLink）：文案按行 appendLine（StatusText 不解析换行转义），
     * 「未开启自动保存 且 无历史文件」时追加「自动保存历史 SQL」蓝字链接（点击打开设置并定位高亮
     * 「自动保存历史 SQL」设置行）；其余态仅纯文案。刷新点=init 与 [applyRefresh]（扫描落地）。
     */
    private fun updateFilesEmptyText() {
        val status = fileList.emptyText
        status.clear()
        if (allFiles.isNotEmpty()) {
            status.text = MpSqlVisualizerBundle.message("log.history.search.noMatches")
            return
        }
        MpSqlVisualizerBundle.message("log.history.files.empty").split("\n").forEach { line ->
            status.appendLine(line)
        }
        val autoSaveEnabled = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.logAutoSaveEnabled
        if (shouldShowAutoSaveHistoryLink(autoSaveEnabled, allFiles.size)) {
            appendLinkRow(
                status,
                listOf(
                    MpSqlVisualizerBundle.message("log.history.empty.link.autoSave") to
                        java.awt.event.ActionListener {
                            cn.song.mybatisplus.visualizer.config.PluginSettingsFolderConfigurable.openSqlLogSettingsFocused(
                                project,
                                MpSqlVisualizerBundle.message("settings.log.autoSave"),
                            )
                        },
                ),
            )
        }
    }

    /** T-SqlLogPerf：浏览文件重载结果包（条目 + 读取完成时的 (lastModified, length) 快照）。 */
    private data class SqlReload(val file: File, val entries: List<LogSqlEntry>, val stamp: Pair<Long, Long>)

    /** 应用扫描结果（EDT；仅刷新/加载代际最新者执行）。 */
    private fun applyRefresh(scanned: List<HistoryFileEntry>, keepFile: File?, keepReload: SqlReload?) {
        allFiles = scanned
        LOG.info("HistorySqlLogPanel.refresh: 扫描到 ${allFiles.size} 个历史文件")
        // T-SettingsFocusFromLink：空态条件链接随扫描结果刷新（未开自动保存且无文件时露出）
        updateFilesEmptyText()

        // 保持二级视图：当前文件仍存在则保留二级
        if (keepFile != null && keepFile.exists()) {
            // 仅文件真有变化（快照不一致）才替换解析结果；未变时 currentEntries/滚动位置原样保留
            if (keepReload != null) {
                currentEntries = keepReload.entries
                currentFileStamp = keepReload.stamp
                applySqlSearch()
            }
            return
        }
        // 否则回一级（默认展示文件列表）
        if (dockedPreview.isOpen) previewHost.toggle()
        dockedPreview.resetContext()
        previewHost.hideHint()
        currentFile = null
        currentFileStamp = null
        currentEntries = emptyList()
        templateFocusKey = null // O-90：回一级清除同模板聚焦（chip 同步隐）
        refreshNavToolbar() // T-UN-10：Back 可见性随层级即时刷新（原 backButton.isVisible = false）
        searchField.textEditor.emptyText.text = MpSqlVisualizerBundle.message("log.history.search.files")
        cardLayout.show(cards, CARD_FILES)
        applyFileSearch()
    }

    // ---------- 搜索过滤 ----------

    /** T-Perf-05：搜索输入防抖调度（DocumentListener 入口）。 */
    private fun scheduleSearch() {
        searchDebounceTimer.restart()
    }

    private fun applySearch() {
        // T-LogToSqlHint 批：过滤真正应用（防抖后单点）喂本组件搜索历史（平台历史钮已接管为自有实现）
        nativeSearch.recordSearchQuery(searchField.text)
        if (currentFile != null) applySqlSearch() else applyFileSearch()
    }

    /**
     * 一级：按文件名过滤。T-HistorySearchToggles：matchCase 开关生效（默认关=忽略大小写）；
     * regex 开关**不参与**（文件名正则价值低，tooltip 已写明两级语义差）。
     */
    private fun applyFileSearch() {
        val query = searchField.text.trim()
        fileListModel.clear()
        val filtered = if (query.isEmpty()) {
            allFiles
        } else {
            allFiles.filter { it.displayName.contains(query, ignoreCase = !matchCaseEnabled) }
        }
        filtered.forEach { fileListModel.addElement(it) }
        // 一级仍为文件名子串搜索；从二级返回时清掉与此层无关的正则错误。
        nativeSearch.setSearchError(null)
        updateFilesEmptyText()
        // T-SearchToggleNativeLook：原生状态区读数（对齐 IDEA 搜索条「N results」位）
        nativeSearch.setStatusText(
            if (query.isEmpty()) "" else MpSqlVisualizerBundle.message("log.history.search.results", filtered.size),
        )
    }

    private fun applySqlSearch() {
        // O-90：同模板计数随文件内容重建（**全量条目**聚合——徽章计数不随搜索/聚焦波动）；
        // 渲染器与右键聚焦动作共用同一快照。
        // F88（T-PerfSweep25）：聚合与查询无关、只随条目快照换代——同快照反复 apply（空查询
        // 防抖反复触发、查询进出）不再重复 O(N) 聚合（报告「空查询聚合不短路」的收敛形态）；
        // 快照换代（换文件/重载/清空）仍首算，徽章口径与首屏计数不丢（朴素「空查询即跳过」
        // 会使首次空态展示丢徽章——applySqlSearch 是 templateCounts 唯一赋值点，已否）。
        if (templateCountsSnapshotFor !== currentEntries) {
            templateCounts = (structureKeyCountsHookForTest ?: ::structureKeyCounts)(currentEntries)
            templateCountsSnapshotFor = currentEntries
        }
        sqlCellRenderer.templateCounts = templateCounts
        // T-HistorySearchToggles：查询解析与过滤抽为顶层纯函数 [filterHistoryEntries]（可独立单测，
        // 形态照日志页 filterBySearch 先例）；memo 实例面板持有，防抖 200ms 反复 apply 复用编译结果。
        // 正则生效条件 = 开关或 `regex:` 前缀（前缀是已发行能力，验收 3.11 不回归）；编译旗随
        // matchCase；非法正则 fail-closed 回退子串保持；行号 `#N` 精确匹配不受开关影响。
        val filteredEntries =
            filterHistoryEntries(
                currentEntries,
                searchField.text.trim(),
                regexEnabled = regexEnabled,
                matchCase = matchCaseEnabled,
                memo = regexMemo,
            )
        // T-LogListRail：进度条归一基准取**当前可见条目**的最大耗时（与日志页同口径；单遍无分配）
        sqlCellRenderer.maxDurationMs = maxDurationOf(filteredEntries)
        val query = searchField.text.trim()
        val invalidRegex = query.isNotEmpty() &&
            (regexEnabled || query.startsWith(HISTORY_REGEX_PREFIX)) && regexMemo.compiled == null
        val error = if (invalidRegex) MpSqlVisualizerBundle.message("log.history.search.invalidRegex") else null
        // 过滤仍保留既有非法正则子串回退，但不能让「0 条」掩盖输入错误。
        nativeSearch.setSearchError(error)
        nativeSearch.setStatusText(
            error ?: if (query.isEmpty()) "" else MpSqlVisualizerBundle.message("log.history.search.results", filteredEntries.size),
        )
        sqlList.emptyText.text = error ?: MpSqlVisualizerBundle.message(
            if (currentEntries.isEmpty()) "log.history.entries.empty" else "log.history.search.noMatches",
        )
        showSqlEntries(filteredEntries)
    }

    /**
     * O-90：二级列表统一出口——搜索过滤结果再过同模板聚焦层后填充，并同步聚焦 chip。
     * （聚焦层插在搜索层之后：模板聚焦是对当前可见行集的再收敛，与日志页
     * filterByFocusTabs 插在 filterByFocus 之后同序。）
     */
    private fun showSqlEntries(entries: List<LogSqlEntry>) {
        val key = templateFocusKey
        fillSqlList(if (key == null) entries else entries.filter { it.structureKey == key })
        syncTemplateChip()
    }

    /**
     * O-90：同模板聚焦统一入口（右键动作 / chip 点击 / 测试驱动同缝）：key 非空 → 二级列表
     * 只显该模板条目；null → 清除。非二级视图只记状态不刷列表（切入文件时按现状重算）。
     */
    internal fun applyTemplateFocus(key: String?) {
        templateFocusKey = key
        if (currentFile != null) applySqlSearch() else syncTemplateChip()
    }

    /** O-90：同步聚焦提示 chip（聚焦时显示该模板计数 + ✕，点击清除；未聚焦隐藏）。 */
    private fun syncTemplateChip() {
        val key = templateFocusKey
        if (key == null) {
            templateFilterChip.isVisible = false
            templateFilterChip.toolTipText = null
            return
        }
        templateFilterChip.text = MpSqlVisualizerBundle.message("log.panel.templateBadge", templateCounts[key] ?: 0) + " ✕"
        templateFilterChip.toolTipText = MpSqlVisualizerBundle.message("log.history.templateFilter.clearTip")
        templateFilterChip.isVisible = true
    }

    /**
     * T-SqlHistoryFull → T-SqlLogPerf 演进（20261006-1020 计划 P3/P4）：二级列表**滚动追加**填充。
     *
     * 旧版：首屏 30 条立即写入 + 剩余每 30 条 invokeLater 链式追加——8708 条文件排 290 个 EDT 脉冲，
     * 无论用户看多少都全量进模型。改为：首屏 30 条立即写入，其余挂 [pendingEntries]，用户滚动
     * 逼近列表底部（[maybeAppendOnScroll]）时才续写下一批（每批 [REFRESH_CHUNK_SIZE] 条，
     * 滚动条 max 变更会再触发 adjustment 事件自然节流）——大文件只付出「用户真正浏览到」的成本。
     * gen 代际守卫保留：新一轮 fillSqlList（重新加载/搜索/刷新）启动即作废旧待写队列。
     */
    private fun fillSqlList(entries: List<LogSqlEntry>) {
        val gen = ++bgGeneration
        pendingEntries = emptyList()
        pendingAppendIndex = 0
        pendingAppendGen = gen
        LogPerfProbe.measure("History.fillSqlList", { "${entries.size} 条" }) {
            sqlList.ignoreRepaint = true
            sqlListModel.clear()
            if (entries.isNotEmpty()) {
                sqlListModel.addAll(entries.take(REFRESH_FIRST_BATCH_SIZE))
            }
            sqlList.ignoreRepaint = false
            sqlList.revalidate()
            sqlList.repaint()
        }
        if (entries.size > REFRESH_FIRST_BATCH_SIZE) {
            pendingEntries = entries
            pendingAppendIndex = REFRESH_FIRST_BATCH_SIZE
        }
        maybeAppendOnScroll()
    }

    /** 滚动追加触发窗口：视口底沿距列表底沿小于该像素即续写下一批（约半屏，提前装载避免跟随感断裂）。 */
    private val APPEND_TRIGGER_PIXELS = 480

    /** 滚动逼近底部时续写待写条目（adjustment 事件 + fillSqlList 尾部各调一次；一次最多一批，靠滚动条 max 变更再触发）。 */
    private fun maybeAppendOnScroll() {
        if (!sqlList.isShowing) return // 未显示（headless/隐藏卡）不自动续写——测试经 [appendPendingForTest] 驱动
        appendPendingIfNearBottom()
    }

    /** 测试缝：headless 下无真实滚动事件，直接驱动滚动追加（与 adjustment 监听同一实现路径）。 */
    internal fun appendPendingForTest() {
        appendPendingIfNearBottom()
    }

    private fun appendPendingIfNearBottom() {
        if (pendingAppendIndex >= pendingEntries.size) return
        val bar = sqlScrollPane?.verticalScrollBar
        if (bar != null) {
            val distance = bar.maximum - (bar.value + bar.visibleAmount)
            if (distance > APPEND_TRIGGER_PIXELS) return
        }
        if (pendingAppendGen != bgGeneration || project.isDisposed) {
            pendingEntries = emptyList()
            return
        }
        val end = minOf(pendingEntries.size, pendingAppendIndex + REFRESH_CHUNK_SIZE)
        sqlListModel.addAll(pendingEntries.subList(pendingAppendIndex, end))
        pendingAppendIndex = end
        if (pendingAppendIndex >= pendingEntries.size) pendingEntries = emptyList()
    }

    /** 滚动追加待写队列（fillSqlList 写完首屏后的剩余条目；追加完毕置空）。 */
    private var pendingEntries: List<LogSqlEntry> = emptyList()

    /** 待写队列下一位游标（避免每批 drop() 拷贝余量）。 */
    private var pendingAppendIndex: Int = 0

    /** 待写队列所属代际（滚动回调校验，旧代际队列作废）。 */
    private var pendingAppendGen: Int = 0

    /** T-SqlLogPerf：当前浏览文件加载完成时的 (lastModified, length) 快照——refresh() 据此判定文件是否变化。 */
    private var currentFileStamp: Pair<Long, Long>? = null

    /** T-HistoryTabAlways：上次扫描所用目录——设置变更时据此判定是否需要重扫（保存目录被改过）。 */
    private var lastScannedDir: String = ""

    /** 历史 SQL 目录：与写端同源解析（自定义保存目录优先；无项目根回落 user.home）。 */
    private fun getHistoryDir(): String =
        SqlLogAutoSaver.resolveHistoryDir(PluginSettings.getInstance().state.logAutoSavePath, project.basePath)

    private fun openPreview(entry: LogSqlEntry) {
        SqlPreviewService.showResult(project, buildPreviewResult(entry))
    }

    /**
     * 双击预览的 PreviewResult 构建（T-HistParam F3，internal 供 UI 测试不弹窗断言识别结果）：
     * 与日志 tab 共用 [toPreviewResult] 统一识别通道——v2 历史条目（rawSql/params 齐全）参数行
     * 与日志页一致，字面量条目走 F4 恢复；T-ListPreviewCopy：formatSql 改跟随设置
     * 「预览时格式化还原 SQL」（logFormatSql，默认开=默认格式化），消除与日志 tab 双击预览的不一致。
     */
    internal fun buildPreviewResult(entry: LogSqlEntry): PreviewResult =
        entry.toPreviewResult(
            formatSql = PluginSettings.getInstance().state.logFormatSql,
            literalRecovery = PluginSettings.getInstance().state.literalParamRecovery,
            // T-DialectInferFix：历史条目同样携带本次执行方言（旧条目由此键或 `db:` 角标回填）
            capturedDialect = entry.dialectName,
        )

    // ---------- 二级右键菜单 ----------

    private fun showSqlContextMenu(e: MouseEvent) {
        val idx = sqlList.locationToIndex(e.point)
        if (idx < 0) return
        // T-ListPreviewCopy：与日志 tab 同款选中收敛——命中行已在多选内保留（复制项=批量复制多条），
        // 否则收敛为该行；跳转等其余菜单项恒作用于被右键的行
        sqlList.selectedIndices = resolveContextMenuSelection(sqlList.selectedIndices, idx)
        val entry = sqlListModel.getElementAt(idx)
        val selectedEntries = sqlList.selectedIndices.map { sqlListModel.getElementAt(it) }

        // T-UiNative P1a：JPopupMenu+JMenuItem → 平台 DefaultActionGroup+AnAction（ActionPopupMenu 承载）
        val popup = ActionManager.getInstance()
            .createActionPopupMenu("mpvs.history.sql", buildSqlContextMenu(entry, selectedEntries))
        // 243 的 ActionPopupMenu 只有 setter 无 getter，Kotlin 不合成 targetComponent 属性——显式调 setter
        popup.setTargetComponent(sqlList)
        popup.component.show(sqlList, e.x, e.y)
    }

    /**
     * 二级右键菜单动作组（R31 抽取 internal：测试断言「对比选中两条」接线与灰置规则）。
     * 动作集与抽取前逐项一致，末位追加 R31 对比动作。
     */
    internal fun buildSqlContextMenu(entry: LogSqlEntry, selectedEntries: List<LogSqlEntry>): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(CopySqlAction(selectedEntries))
        // T-LogSearchRowTime：复制时间戳（与日志页同款——纯时间文本无方括号；显示关闭时置灰，
        // 置灰逻辑在 CopyTimestampAction.update）
        val timestampClip = batchCopyTimestamps(selectedEntries)
        group.add(CopyTimestampAction(selectedEntries, timestampClip))
        group.add(JumpToSourceAction(entry))
        // T-M55-01：拆「跳转到 XML」双目标（与日志页同套）
        group.add(JumpToMapperXmlAction(entry))
        // O-90：按模板聚焦（「×N 同模板」徽章达阈值才可用；过滤出该模板全部条目，chip 点击清除）
        group.add(FocusTemplateAction(entry))
        // R4（W3 历史工作流包）：导出选中/全部条目（CSV/TSV/TXT，后台写盘）
        val allEntries = (0 until sqlList.model.size).map { sqlList.model.getElementAt(it) }
        group.add(ExportSqlAction(selectedEntries.ifEmpty { allEntries }))
        // FR-0045 第 6 项（T-TraceDocExport S2）：导出优化留痕 Markdown（六段式模板）
        group.add(ExportTraceDocAction(entry))
        // R31：历史/版本 SQL 对比（恰两条选中可用）
        group.add(CompareSelectedAction(selectedEntries))
        return group
    }

    /**
     * R31：历史/版本 SQL 对比——多选恰两条时可用；对比 = [SqlDiffer] 逐行 diff + 结构指纹
     * （[LogSqlEntry.structureKey]，同指纹提示「结构等价，仅参数不同」）。
     * 呈现复用预览面板 showSqlDiff 的 IDEA 原生 Diff 形态（非模态、SQL 语法高亮、同步滚动）。
     */
    internal inner class CompareSelectedAction(private val entries: List<LogSqlEntry>) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Diff
            e.presentation.text = MpSqlVisualizerBundle.message("log.history.compare")
            e.presentation.description = MpSqlVisualizerBundle.message("log.history.compare.tip")
            e.presentation.isEnabled = entries.size == 2
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (entries.size != 2) return
            showHistoryDiff(entries[0], entries[1])
        }
    }

    /**
     * R31：打开对比窗口——IDEA 原生 Diff（形态照抄 SqlPreviewPanel.showSqlDiff：
     * DiffContentFactory.createEditable + SimpleDiffRequest → DiffManager.showDiff），
     * 标题带结构指纹结论（等价提示「仅参数不同」，否则带差异行数）。
     */
    private fun showHistoryDiff(first: LogSqlEntry, second: LogSqlEntry) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val verdict = historyDiffVerdict(first, second)
        val diffContentFactory = com.intellij.diff.DiffContentFactory.getInstance()
        val sqlFileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL")
        val leftContent: com.intellij.diff.contents.DocumentContent =
            diffContentFactory.createEditable(project, verdict.oldSql, sqlFileType)
        val rightContent: com.intellij.diff.contents.DocumentContent =
            diffContentFactory.createEditable(project, verdict.newSql, sqlFileType)
        val request = com.intellij.diff.requests.SimpleDiffRequest(
            historyDiffDialogTitle(verdict),
            leftContent,
            rightContent,
            MpSqlVisualizerBundle.message("log.history.compare.old"),
            MpSqlVisualizerBundle.message("log.history.compare.new"),
        )
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request)
    }

    // ---------- R4（W3 历史工作流包）：二级 SQL 导出（CSV/TSV/TXT/SQL，后台写盘） ----------

    private inner class ExportSqlAction(private val entries: List<LogSqlEntry>) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Export
            e.presentation.text = MpSqlVisualizerBundle.message("log.history.export")
        }

        override fun actionPerformed(e: AnActionEvent) {
            exportEntries(entries)
        }
    }

    // ---------- FR-0045 第 6 项（T-TraceDocExport S2）：导出优化留痕 Markdown ----------

    /** 右键「导出优化留痕 Markdown」——作用于被右键的那一条（与跳转类动作同口径）。 */
    private inner class ExportTraceDocAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Export
            e.presentation.text = MpSqlVisualizerBundle.message("log.history.traceDoc")
            e.presentation.description = MpSqlVisualizerBundle.message("log.history.traceDoc.tip")
        }

        override fun actionPerformed(e: AnActionEvent) {
            exportTraceDocFor(entry)
        }
    }

    /**
     * 导出单条历史 SQL 的留痕文档。
     *
     * 计划取数：日志历史文件本身不存执行计划（计划只挂在预览历史
     * [cn.song.mybatisplus.visualizer.history.SqlHistoryEntry.explainPlans] 上），故按
     * 「**同项目** + SQL 文本精确相等」从历史域取最近两份快照——跨项目不借用（R91 目标隔离口径）；
     * 匹配不到就按「无计划快照」降级导出，绝不伪造计划。
     */
    internal fun exportTraceDocFor(entry: LogSqlEntry) {
        TraceDocExport.export(project, TraceDocExport.fromLogEntry(entry, matchingHistoryPlans(entry)))
    }

    private fun matchingHistoryPlans(entry: LogSqlEntry): List<cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot> {
        val identity = cn.song.mybatisplus.visualizer.history.ExplainPlanKey.projectIdentity(project.basePath)
        if (identity.isBlank()) return emptyList()
        val candidates = setOf(entry.restoredSql, entry.rawSql).filter { it.isNotBlank() }
        if (candidates.isEmpty()) return emptyList()
        return cn.song.mybatisplus.visualizer.history.SqlHistoryManager.getInstance().entries.asSequence()
            .filter { it.projectIdentity == identity && it.sql in candidates }
            .flatMap { it.explainPlans.asSequence() }
            .sortedByDescending { it.capturedAt }
            .take(2)
            .toList()
    }

    /** R4：导出选中/全部条目——格式四选（CSV/TSV/TXT/SQL，默认 SQL）→ 文件保存对话框 → 后台写盘 → 通知。
     *  照 ShareDialog 的 FileSaverDialog 惯例（仓内既有形态）。 */
    internal fun exportEntries(entries: List<LogSqlEntry>) {
        if (entries.isEmpty()) return
        val format = chooseExportFormat() ?: return
        val rows = entries.map {
            val source = buildString {
                if (!it.sourceFile.isNullOrBlank()) {
                    append(it.sourceFile)
                    if (it.sourceLine > 0) append(":").append(it.sourceLine)
                }
            }
            SqlExportRow(
                time = SqlExportService.formatTime(it.timestamp),
                sql = it.restoredSql,
                source = source,
                durationMs = it.executionTimeMs,
            )
        }
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
            MpSqlVisualizerBundle.message("log.history.export"),
            "SQL (${format.ext.uppercase(Locale.ROOT)})",
            format.ext,
        )
        val dialog = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
        // T-ExportSqlFormat（用户拍板 20261009）：SQL 格式默认名 sql-export-<yyyyMMdd-HHmmss>.sql
        // （时间戳防同日覆盖、不占 sql-history 名族）；CSV/TSV/TXT 保持原名随 ext。
        val defaultName = if (format == SqlExportFormat.SQL) {
            SqlExportService.defaultSqlExportFileName()
        } else {
            "sql-history.${format.ext}"
        }
        val saved = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, defaultName)
            ?: return
        val target = saved.file
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                SqlExportService.exportToFile(
                    target,
                    // T-MagpieGems W2：导出是离开 IDE 面——mode != OFF 一律按 MASK 打码（从严）。
                    // UI-001 修复：白名单豁免贯穿导出链。
                    SqlExportService.format(
                        rows,
                        format,
                        PluginSettings.getInstance().displayMaskExportOn(),
                        PluginSettings.getInstance().displayMaskAllowList(),
                    ),
                )
            }.onSuccess {
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("log.history.export.done", rows.size, target.absolutePath),
                        com.intellij.notification.NotificationType.INFORMATION,
                    ).notify(project)
            }.onFailure {
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("log.history.export.failed", it.message ?: ""),
                        com.intellij.notification.NotificationType.ERROR,
                    ).notify(project)
            }
        }
    }

    /** R4：导出格式四选（取消返回 null）。选项文本用格式名（CSV/TSV/TXT/SQL 无需翻译）；
     *  默认选中 SQL（T-ExportSqlFormat 用户拍板 20261009；P3：showChooseDialog 返回所选下标、取消 -1）。 */
    private fun chooseExportFormat(): SqlExportFormat? {
        val names = arrayOf("CSV", "TSV", "TXT", "SQL")
        val idx = com.intellij.openapi.ui.Messages.showChooseDialog(
            project,
            MpSqlVisualizerBundle.message("log.history.export.choose"),
            MpSqlVisualizerBundle.message("log.history.export"),
            MpvsIcons.Export,
            names,
            names[3],
        )
        if (idx < 0) return null
        return when (idx) {
            0 -> SqlExportFormat.CSV
            1 -> SqlExportFormat.TSV
            2 -> SqlExportFormat.TXT
            3 -> SqlExportFormat.SQL
            else -> null
        }
    }

    // ---------- T-UiNative P1a：二级右键菜单动作（原 JMenuItem 语义平移，update 设图标/文案/灰置） ----------

    private inner class CopySqlAction(private val entries: List<LogSqlEntry>) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Copy
            e.presentation.text = copyMenuText("log.history.copy", "log.history.copySelected", entries.size)
        }

        override fun actionPerformed(e: AnActionEvent) {
            UiTextClipboard.copy(batchCopySql(entries))
        }
    }

    private inner class CopyTimestampAction(private val entries: List<LogSqlEntry>, private val clip: String) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Copy
            e.presentation.text = copyMenuText("log.history.copyTimestamp", "log.history.copyTimestampSelected", entries.size)
            e.presentation.isEnabled = clip.isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (clip.isNotEmpty()) {
                UiTextClipboard.copy(clip)
            }
        }
    }

    /** 跳转到 Java 调用方（T-M55-01：与「跳转到 XML」拆分为双目标，与日志页同套）。 */
    private inner class JumpToSourceAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.EditSource
            e.presentation.text = MpSqlVisualizerBundle.message("log.history.jumpToJava")
        }

        override fun actionPerformed(e: AnActionEvent) {
            jumpToSource(entry)
        }
    }

    /**
     * O-90：按模板聚焦——右键带「×N 同模板」徽章的条目聚焦该模板（structureKey 精确匹配，
     * 与日志页 FocusTemplateAction 同形态：条目驱动的聚焦过滤器）；历史页无页签栏，
     * 聚焦态由工具栏 chip 承载（计数 + ✕ 点击清除，[applyTemplateFocus]）。
     * 准入 = 计数达 [TEMPLATE_BADGE_MIN_COUNT]（徽章在场才可点）。
     */
    private inner class FocusTemplateAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.focusTemplate")
            // T-DevAcceptFix2 ⑪（20261006 第三轮走查）：菜单项带判定口径说明（与日志页同源文案）
            e.presentation.description = MpSqlVisualizerBundle.message("log.panel.focusTemplate.desc")
            e.presentation.isEnabled = (templateCounts[entry.structureKey] ?: 0) >= TEMPLATE_BADGE_MIN_COUNT
        }

        override fun actionPerformed(e: AnActionEvent) {
            val n = templateCounts[entry.structureKey] ?: return
            if (n >= TEMPLATE_BADGE_MIN_COUNT) applyTemplateFocus(entry.structureKey)
        }
    }

    /**
     * T-M55-01：跳转到 mapper XML 语句节点。mapperStatement 非空且定位成功才显示
     * （update 内解析一次并存引用）；定位失败不显示，「跳转到 Java」承担回退，不劣化。
     */
    internal inner class JumpToMapperXmlAction(private val entry: LogSqlEntry) : AnAction() {
        private var resolved: HistoryJumpTarget.MapperXml? = null

        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.MapperXml
            e.presentation.text = MpSqlVisualizerBundle.message("log.history.jumpToXml")
            resolved = resolveXmlJumpTarget(entry)
            e.presentation.isEnabledAndVisible = resolved != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val target = resolved ?: resolveXmlJumpTarget(entry) ?: return
            OpenFileDescriptor(project, target.virtualFile, target.line, 0).navigate(true)
        }
    }

    /**
     * 跳转到产生该 SQL 的源码位置的解析结果（internal 供 UI 测试确定性断言跳转分支，
     * 规避 headless 下 Messages 弹窗阻塞）：Navigate=文件存在可跳转；NoSource=缺源码位置；
     * NotFound=源码路径无效/文件不存在。
     *
     * 抽取原因（F-44-5 全插件 UI 测试）：用户反馈「历史缺代码位置导致无法跳转」——
     * 跳转分支（可跳 / 缺位置 / 文件丢失）必须用确定性断言锁定，而非依赖弹窗副作用。
     */
    internal sealed class HistoryJumpTarget {
        data class Navigate(val virtualFile: com.intellij.openapi.vfs.VirtualFile, val line: Int) : HistoryJumpTarget()
        data object NoSource : HistoryJumpTarget()
        data class NotFound(val sourceFile: String) : HistoryJumpTarget()
        /** T-M55-01：mapper XML 语句节点（mapperStatement 定位成功才有意义，失败回退 Java 不劣化）。 */
        data class MapperXml(val virtualFile: com.intellij.openapi.vfs.VirtualFile, val line: Int) : HistoryJumpTarget()
    }

    /**
     * 解析跳转目标（与 [jumpToSource] 共用；测试驱动直接断言分支，不触发弹窗）。
     * T-SourceJumpFunnel P0-5：判定收敛到 [EntryJumpResolver]（与实时面板同一实现）——
     * 主位置不在工程内（自研框架汇聚门面帧）时按候选栈序回退，本面板此前无此能力。
     * T-RecentExecMenu O-119：转接原语收口 [LogEntryJumpOps]（三处共用）；**本页策略**=
     * `MissingSource` 并入 `NoSource`（走引导弹窗，与实时页的轻通知不同体，各自保留）。
     */
    internal fun resolveJumpTarget(entry: LogSqlEntry): HistoryJumpTarget =
        when (val target = LogEntryJumpOps.resolveSource(project, entry)) {
            is LogEntryJumpOps.SourceTarget.Precise ->
                HistoryJumpTarget.Navigate(target.virtualFile, target.line)
            LogEntryJumpOps.SourceTarget.NoSource -> HistoryJumpTarget.NoSource
            LogEntryJumpOps.SourceTarget.MissingSource -> HistoryJumpTarget.NoSource
            is LogEntryJumpOps.SourceTarget.NotFound -> HistoryJumpTarget.NotFound(target.sourceFile)
        }

    /**
     * T-M55-01：XML 目标解析——与日志页 [SqlLogPanel.resolveEntryXmlJump] 同语义。
     * mapperStatement 非空且 [MapperXmlLocator] 定位成功才返回；否则 null（回退 Java，不劣化）。
     * T-RecentExecMenu O-119：解析原语收口 [LogEntryJumpOps.resolveXml]。
     */
    internal fun resolveXmlJumpTarget(entry: LogSqlEntry): HistoryJumpTarget.MapperXml? =
        LogEntryJumpOps.resolveXml(project, entry)
            ?.let { HistoryJumpTarget.MapperXml(it.virtualFile, it.line) }

    /**
     * 跳转到产生该 SQL 的源码位置（测试驱动；等价右键「跳转到源码」）。
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）。
     */
    internal fun jumpToSourceForTest(entry: LogSqlEntry) = jumpToSource(entry)

    /**
     * 跳转到产生该 SQL 的源码位置。
     * 通过 sourceFile + sourceLine 直接定位（F-44 新格式）；
     * T-JumpCapture：无捕获位置时弹框引导开启源码捕获拦截器。
     * T-RecentExecMenu O-119：解析/导航/通知原语走 [LogEntryJumpOps]（本页策略保持原样：
     * NoSource/MissingSource 引导弹窗、NotFound 轻通知，标题键为本页口径 `log.history.*`）。
     */
    private fun jumpToSource(entry: LogSqlEntry) {
        when (val target = LogEntryJumpOps.resolveSource(project, entry)) {
            // 缺源码位置（旧版 F-43 条目 / 未启用拦截器捕获）：引导开启源码捕获（T-JumpCapture）
            LogEntryJumpOps.SourceTarget.NoSource,
            LogEntryJumpOps.SourceTarget.MissingSource,
            -> LogEntryJumpOps.showMissingSourceGuide(project)

            is LogEntryJumpOps.SourceTarget.NotFound -> {
                // 文件不存在时提示（项目结构可能已变化）；T-UiNative T-UN-02：改轻通知（原模态弹窗）
                LogEntryJumpOps.showToast(
                    project,
                    "log.history.jumpToSource",
                    MpSqlVisualizerBundle.message("log.history.jumpToSource.notFound", target.sourceFile),
                )
            }

            is LogEntryJumpOps.SourceTarget.Precise -> {
                LogEntryJumpOps.navigate(project, target.virtualFile, target.line)
            }
        }
    }

    override fun dispose() {
        // T-Perf-05：面板销毁停止防抖 Timer（防晚到 apply 触碰已销毁面板）
        searchDebounceTimer.stop()
        previewHost.hideHint()
        dockedPreview.dispose()
    }

    companion object {
        private val LOG = Logger.getInstance(HistorySqlLogPanel::class.java)
        private const val CARD_FILES = "files"
        private const val CARD_SQL = "sql"

        // T-RecentExecMenu O-119：跳转通知改经 [LogEntryJumpOps] 共享组；本常量保留给
        // R4 历史/日志导出 done/failed 两条通知（dev 批次 9 交付，master 未见故曾随退役删除）。
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

        /** T-Perf-05：搜索输入防抖窗口（毫秒）。 */
        private const val SEARCH_DEBOUNCE_MS = 200

        /** T-SqlHistoryFull：二级列表分批填充——首屏立即写入条数（与实时面板同值，快速首屏）。 */
        private const val REFRESH_FIRST_BATCH_SIZE = 30

        /** T-SqlHistoryFull：二级列表分批填充——每块 invokeLater 追加条数（防 EDT 长冻结）。 */
        private const val REFRESH_CHUNK_SIZE = 30
    }
}

// ---------- T-HistorySearchToggles：历史检索「查询 → 过滤」纯逻辑（applySqlSearch 抽缝，可独立单测） ----------

/** `regex:` 前缀（R16 已发行能力）：正则开关关闭时同样触发正则匹配（或语义，前缀在场先剥）。 */
internal const val HISTORY_REGEX_PREFIX = "regex:"

/**
 * 正则编译 memo（F41 双字段 memo 的 T-HistorySearchToggles 扩展）。
 *
 * **键 = 源串 + matchCase 旗二元组**——只按源串判等会在切开关后复用错误编译结果
 * （例：`user` 以 IGNORE_CASE 编译后切到敏感态仍复用 → 大写目标命中不了，S3 最易埋点）。
 * 非法源串同样整键 memo（[compiled] 配 null），同坏串同旗反复 apply 不再重复抛编译异常，
 * 换串或换旗即重试，匹配行为与逐次重编完全一致（Regex 的 containsMatchIn 无状态，复用安全）。
 * 面板单实例且调用全部发生在 EDT（Swing Timer 回调），可变字段无需并发容器。
 */
internal class HistoryRegexMemo {
    var source: String? = null

    /** 与 [source] 配对的编译大小写旗（ memo 键的另一半）。 */
    var matchCase: Boolean = false

    /** 编译结果（非法源串配 null，调用方走子串回退路径）。 */
    var compiled: Regex? = null
}

/** 按 [memo] 取编译结果：同源串**同旗**复用，否则（重）编译；非法源串 memo 失败供下次复用。 */
internal fun resolveHistoryRegex(source: String, matchCase: Boolean, memo: HistoryRegexMemo): Regex? {
    if (source == memo.source && matchCase == memo.matchCase) return memo.compiled
    val compiled = runCatching {
        if (matchCase) Regex(source) else Regex(source, RegexOption.IGNORE_CASE)
    }.getOrNull()
    memo.source = source
    memo.matchCase = matchCase
    memo.compiled = compiled
    return compiled
}

/**
 * 历史二级检索过滤（纯逻辑，形态照日志页 [filterBySearch] 先例）：
 *
 * - **正则**：生效条件 = 开启正则开关**或** `regex:` 前缀（前缀在场先剥、不参与匹配）；
 *   命中三维 restoredSql/summary/sourceFile（R16 现状，时间不参与）；编译旗随 matchCase
 *   （行为变更声明：正则路径从恒敏感改为随开关，默认关=忽略大小写，见计划 §三）；
 * - **非法正则**：fail-closed 回退普通子串匹配（回退查询为原始 query 全文含前缀，与现状逐字一致）；
 * - **子串**：匹配 restoredSql/summary/时间显示片段/sourceFile，大小写统一由 matchCase 控制
 *   （默认关=忽略大小写）；行号 `#N`/`N` 精确 equals [LogSqlEntry.sequenceNumber]，不受开关影响；
 * - 空查询透传全部。[timestampTextOf] 可注入（测试绕开设置读取），默认取当前设置下的真实显示片段。
 */
internal fun filterHistoryEntries(
    entries: List<LogSqlEntry>,
    query: String,
    regexEnabled: Boolean = false,
    matchCase: Boolean = false,
    memo: HistoryRegexMemo = HistoryRegexMemo(),
    timestampTextOf: (LogSqlEntry) -> String? = ::timestampDisplayText,
): List<LogSqlEntry> {
    val q = query.trim()
    if (q.isEmpty()) return entries
    if (regexEnabled || q.startsWith(HISTORY_REGEX_PREFIX)) {
        val pattern = resolveHistoryRegex(q.removePrefix(HISTORY_REGEX_PREFIX), matchCase, memo)
        if (pattern != null) {
            return entries.filter { entry ->
                pattern.containsMatchIn(entry.restoredSql) ||
                    pattern.containsMatchIn(entry.summary) ||
                    entry.sourceFile?.let { pattern.containsMatchIn(it) } == true
            }
        }
        // 非法正则：回退普通子串匹配（继续走下方通用路径）
    }
    val rowNumber = q.removePrefix("#").toLongOrNull()
    return entries.filter { entry ->
        entry.restoredSql.contains(q, ignoreCase = !matchCase) ||
            entry.summary.contains(q, ignoreCase = !matchCase) ||
            (rowNumber != null && entry.sequenceNumber == rowNumber) ||
            timestampTextOf(entry)?.contains(q, ignoreCase = !matchCase) == true ||
            entry.sourceFile?.contains(q, ignoreCase = !matchCase) == true
    }
}

/**
 * 历史 SQL 文件摘要（一级列表数据源，F-44-3）。
 */
data class HistoryFileEntry(
    /** 历史文件（.sql）。 */
    val file: File,
    /** 文件内 SQL 条目数（[SqlLogFileReader.countInFile] 轻量统计）。 */
    val sqlCount: Int,
) {
    val displayName: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

/** 历史面板元信息灰（与原 HTML 通道 #9E9E9E 一致）。T-UN-13（T-UiNative）：改 namedColor，亮值不变。 */
private val HISTORY_META_GRAY = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.HISTORY_META_GRAY)

/**
 * 一级文件列表单元格渲染器（F-44-3；T-LogListFreeze-04 弃 HTML 通道）：
 * 文件名（加粗）+ 文件大小 | SQL 条数（灰），SimpleColoredComponent 片段渲染。
 * internal 供 UI 测试断言文件名/大小/条数渲染与文件大小格式化边界。
 */
internal class HistoryFileCellRenderer : JPanel(BorderLayout()), javax.swing.ListCellRenderer<Any?> {
    /** 渲染行（internal 供 UI 测试遍历片段断言文本）。 */
    internal val line = SimpleColoredComponent()

    init {
        layout = BorderLayout()
        isOpaque = true
        line.isOpaque = false
        add(line, BorderLayout.CENTER)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8),
        )
    }

    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        background = if (isSelected) list?.selectionBackground else list?.background
        line.foreground = if (isSelected) (list?.selectionForeground ?: JBColor.foreground())
        else (list?.foreground ?: JBColor.foreground())
        line.clear()
        val fe = value as? HistoryFileEntry
        if (fe != null) {
            val size = formatFileSize(fe.sizeBytes)
            val sqlCount = MpSqlVisualizerBundle.message("log.history.sqlCount", fe.sqlCount.toString())
            line.icon = AllIcons.FileTypes.Text
            line.append(fe.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            line.append("  $size | $sqlCount ", HISTORY_META_GRAY)
            // 文件条目少、低频，tooltip（完整路径）保留；条目级 tooltip 卡顿根因在二级 SQL 列表（T-ClickPreview）
            toolTipText = fe.file.absolutePath
        }
        return this
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ENGLISH, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * 二级 SQL 列表单元格渲染器（F-44-2；T-LogListFreeze-04 弃 HTML 通道）。
 *
 * **T-LogListRail（2026-10-26）改版**：与日志页 [LogEntryListCellRenderer] 同步改为
 * 「左侧固定宽度信息轨 + 右侧 SQL 区」。改版动机与轨道语义见 [LogEntryRail] 类 KDoc。
 *
 * 本类曾与日志页渲染器逐行同构（三组方法近乎复制），改版时把共用部分收进 [RailCellRendererBase]，
 * 本类只保留历史页差异：耗时开关此前硬编码 `true`，现已与日志页同走设置项。
 *
 * internal 供 UI 测试断言各种条目状态（空耗时/空源码/特殊字符/超长 SQL）下的渲染健壮性。
 */
internal class HistoryCellRenderer : RailCellRendererBase() {
    /** O-90：同模板计数快照（「×N 同结构」徽章渲染数据源；HistorySqlLogPanel.applySqlSearch 更新）。 */
    var templateCounts: Map<String, Int> = emptyMap()

    /** 当前列表可见条目的最大耗时（进度条归一基准）。 */
    var maxDurationMs: Long? = null

    override fun templateCountsSnapshot(): Map<String, Int> = templateCounts

    override fun maxDurationMsSnapshot(): Long? = maxDurationMs

    /**
     * 历史页条目来自历史文件读回，`sequenceNumber` 恒为 0（见 [LogSqlEntry.sequenceNumber] 契约），
     * 故轨道首行通常只有时刻。耗时显示与日志页同走设置项（此前此处硬编码 true，设置页开关对历史页无效）。
     */
    override fun showDuration(): Boolean =
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.showDurationInLogList
}

/**
 * R31 历史/版本 SQL 对比结论：[SqlDiffer] 逐行 diff 统计 + 结构指纹等价判定（测试断言载体）。
 */
internal data class HistoryDiffVerdict(
    /** 结构指纹等价（[LogSqlEntry.structureKey] 同键）——UI 据此提示「结构等价，仅参数不同」。 */
    val structurallyEquivalent: Boolean,
    /** 行级差异条数（非 EQUAL 条目数；一行改写 = DELETE + INSERT 成对）。 */
    val diffCount: Int,
    val deleteCount: Int,
    val insertCount: Int,
    /** 时间序旧 SQL（Diff 左侧）。 */
    val oldSql: String,
    /** 时间序新 SQL（Diff 右侧）。 */
    val newSql: String,
)

/**
 * R31：两条目对比结论——按时间戳升序定「旧/新」（旧在左、新在右，入参顺序无关），
 * [SqlDiffer.computeDiff] 逐行 diff 统计，结构指纹（字面量/数字参数化，[LogSqlEntry.structureKey]）
 * 判等价。死代码复活：[SqlDiffer]（T-Perf-24 标注「无任何调用方」）自此获得唯一真实调用方。
 */
internal fun historyDiffVerdict(first: LogSqlEntry, second: LogSqlEntry): HistoryDiffVerdict {
    val (older, newer) = listOf(first, second).sortedBy { it.timestamp }
    val entries = SqlDiffer.computeDiff(older.restoredSql, newer.restoredSql)
    return HistoryDiffVerdict(
        structurallyEquivalent = older.structureKey == newer.structureKey,
        diffCount = entries.size,
        deleteCount = entries.count { it.type == SqlDiffer.DiffType.DELETE },
        insertCount = entries.count { it.type == SqlDiffer.DiffType.INSERT },
        oldSql = older.restoredSql,
        newSql = newer.restoredSql,
    )
}

/** R31 对比窗口标题：结构等价提示「仅参数不同」，否则带差异行数。 */
internal fun historyDiffDialogTitle(verdict: HistoryDiffVerdict): String {
    val base = MpSqlVisualizerBundle.message("log.history.compare.title")
    return if (verdict.structurallyEquivalent) {
        "$base — ${MpSqlVisualizerBundle.message("log.history.compare.equivalent")}"
    } else {
        "$base — ${MpSqlVisualizerBundle.message("log.history.compare.diffLines", verdict.diffCount)}"
    }
}

/**
 * 历史页空态「自动保存历史 SQL」链接的显示条件（T-SettingsFocusFromLink，纯函数便于单测）：
 * 仅当用户**未开启自动保存**且**没有任何历史文件**时露出（用户诉求语义）。
 */
internal fun shouldShowAutoSaveHistoryLink(autoSaveEnabled: Boolean, fileCount: Int): Boolean =
    !autoSaveEnabled && fileCount == 0
