package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettings.Companion.LOG_TIMESTAMP_SOURCE_LOG
import cn.song.mybatisplus.visualizer.config.SqlLogConfigurable
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener
import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
// T-UiCoord F2：下拉按钮文本截尾省略复用 meta 行的同款纯函数（避免第二份省略实现）
import cn.song.mybatisplus.visualizer.ui.clipTailToWidth
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import cn.song.mybatisplus.visualizer.util.DisplayMasker
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import cn.song.mybatisplus.visualizer.util.TextCopyList
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.Toggleable
import javax.swing.Action
import javax.swing.JComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.awt.RelativePoint
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JRadioButtonMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.JToggleButton
import javax.swing.ListModel
import javax.swing.SwingConstants
import javax.swing.border.Border
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 工具窗口面板（标签页 + 工具栏；headless 测试不布局，交互断言走条目模型层）。
 *
 * 筛选项精简改造（2026-08-27）：
 * - 类型筛选：工具栏行「SQL类型」多选按钮（弹窗勾选，替代原两行按钮组）
 * - 高频折叠：整体移除（识别重复 SQL 的价值由去重 ⨉N 徽章承接）
 * - 去重徽章：开启「移除重复的sql」后被丢弃的 SQL 在条目旁累加显示 ⨉N
 * - Ctrl+F/⌘F 搜索：默认隐藏的搜索条，快捷键唤起实时过滤（Esc 清空后再按收起）
 */
class SqlLogPanel(
    internal val project: Project,
    internal val manager: SqlLogManager,
) : JPanel(BorderLayout()), Disposable {

    internal val listModel = DefaultListModel<LogSqlEntry>()
    internal val list = ViewportWidthSqlEntryList(listModel)
    /** 列表条目单元格渲染器（internal 供 UI 测试直接渲染、断言 HTML 输出）。 */
    internal lateinit var entryCellRenderer: LogEntryListCellRenderer
    private lateinit var previewHost: SqlListPreviewHost
    private val dockedPreview = SqlDockedPreview(project, list) { previewHost.toggle() }
    /**
     * 页签容器（T-SqlLogTabsSingleRow / O-149，用户 2026-10-14 两轮校正后定稿）。
     *
     * 折行根因：`JTabbedPane` 默认 `WRAP_TAB_LAYOUT`——页签总宽超过容器宽就按行折，窄侧栏下变两行。
     * 用户要求「参考 IDEA 打开多个类时的交互」，故**复用平台自己的页签实现**而不是自造策略：
     * `JBTabsFactory.createTabs` 产出的 `JBTabsImpl` 实现 `MorePopupAware`（`canShowMorePopup()` /
     * `showMorePopup()` / `getMoreToolbar()`），配合 `setSingleRow(true)`（恒单行、不折行）与
     * `setSupportsCompression(true)`（页签可压缩）即得到编辑器页签那套交互：**一行 + 放不下时压缩 +
     * 再放不下就出「显示全部页签」下拉**。243 与 262 两个发行版 jar 均已 `javap` 实证这些 API 存在
     * （`JBTabsPresentation.setSingleRow/setSupportsCompression`、`JBTabsImpl.canShowMorePopup`）。
     */
    private val tabs: com.intellij.ui.tabs.JBTabs = com.intellij.ui.tabs.JBTabsFactory.createTabs(project, this).apply {
        presentation.setSingleRow(true)
        presentation.setSupportsCompression(true)
        presentation.setTabDraggingEnabled(false)
    }

    /** 各页签句柄（JBTabs 以 TabInfo 为身份，替代原 JTabbedPane 的索引语义）。 */
    private var logsTab: com.intellij.ui.tabs.TabInfo? = null
    private var logToSqlTab: com.intellij.ui.tabs.TabInfo? = null
    private var sqlToJavaTab: com.intellij.ui.tabs.TabInfo? = null
    private var historyTab: com.intellij.ui.tabs.TabInfo? = null
    /** T-M38：日志转 SQL 转换 tab（粘贴日志 → 批量还原可执行 SQL；原「SQL 预览」被动展示改造）。 */
    private val logToSqlPanel = LogToSqlPanel(project)
    /** T-M36.4：SQL → Java 转换 tab（粘贴 SQL → 格式化/补全 → 转换 → 可编辑 Java 高亮结果区）。 */
    private val sqlToJavaPanel = SqlToJavaPanel(project)
    /** F-44：历史 SQL tab（读取自动保存目录中的历史文件，支持源码定位）。
     *  T-HistoryTabAlways：**常驻**——不再由 logAutoSaveEnabled 或「.idea 里已存在历史文件」决定
     *  （未开启自动保存的新用户此前看不到入口）；无数据时面板显示空态并指路设置页。 */
    private val historyPanel: HistorySqlLogPanel by lazy { HistorySqlLogPanel(project) }
    /** F-44 / O-149：历史 tab 句柄（TabInfo 为 JBTabs 的身份；替代原 JTabbedPane 索引语义，常驻后恒为末位）。 */
    private val dedupeCheckBox = JCheckBox(MpSqlVisualizerBundle.message("log.panel.dedupe")).apply {
        isSelected = manager.entries.dedupeEnabled
        addActionListener {
            // 当前 UI 选中态（同步给 buffer）
            val nowChecked = isSelected
            val wasEnabled = manager.entries.dedupeEnabled
            if (nowChecked == wasEnabled) return@addActionListener
            if (nowChecked) {
                manager.entries.dedupeEnabled = true
                manager.entries.resetDedupeCounts()
                // F-44-2 用户实测缺：勾选「移除重复的sql」后立即对已有条目去重
                // —— 此前仅清计数、不裁剪缓冲，重复 SQL 继续展示
                val removed = manager.entries.dedupeExisting()
                if (removed > 0) {
                    LOG.info("dedupeCheckBox: 开启去重后裁掉了 $removed 条已存在重复 SQL")
                }
            } else {
                // 关闭去重：清空累计计数（语义：关闭 = 本次累计清零）
                manager.entries.disableDedupe()
            }
            refreshList()
        }
    }

    // ---------- 过滤/排序状态 ----------

    /** 当前激活的类型过滤集合（默认全选 = 「全部」，含 OTHER 透传）。 */
    internal val activeFilter = FILTERABLE_TYPES.toMutableSet()

    /**
     * 「只看慢查询」筛选（用户 2026-10-14 补）：true 时只保留耗时 ≥ 当前慢查询阈值的条目。
     * 判定走 [durationOf]（结构化字段优先、缺失回退日志文本解析），与「慢查询徽章」同口径。
     * 性能：一次 O(n) 遍历，位于刷新管线（非渲染热路径）。
     */
    internal var slowOnly = false

    /** 「只看慢查询」勾选框（弹窗首次构建后非 null；仅用于状态回填，测试另有 [setSlowOnlyForTest]）。 */
    internal var slowOnlyCheckBox: JCheckBox? = null

    /** 排序模式（T-LogSortModes：默认时间升序 = 捕获顺序）。 */
    internal var sortMode = SortMode.TIME_ASC

    /** 当前搜索词（搜索条实时同步；空串 = 不过滤）。 */
    internal var searchText = ""

    /**
     * M56 T-M56-00：动态聚焦过滤状态（基础设施；null = 不过滤）。
     * T-M56-02/06/07 起 agent 通道接管后由页签/请求聚合驱动写入；桥通道恒 null 与现状逐字一致。
     */
    @Volatile
    internal var activeFocus: FocusFilter? = null

    /** 去重丢弃计数快照（refreshList 后更新；cellRenderer 渲染 ⨉N 徽章用）。 */
    internal var dedupeCounts: Map<String, Int> = emptyMap()

    /** O-90：同模板计数快照（refreshList 后更新；cellRenderer 徽章与右键聚焦动作共用）。 */
    internal var templateCounts: Map<String, Int> = emptyMap()

    // ---------- T-Perf-03（自 dev 移植）：增量刷新状态 ----------

    /** 刷新代际：新 refreshList 启动时 +1，旧分批追加链检测到代际过期即终止。 */
    internal var refreshGeneration = 0L

    /** 上一轮渲染的可见列表（同一性差分基线；由 applyIncremental/applyFullRebuild 与 listModel 保持同步）。 */
    internal var lastRendered: List<LogSqlEntry> = emptyList()

    // T-Perf-15：manager 监听器命名引用（uiRefreshListener/capturingUiListener）声明于 init 前统一区块。
    //
    // T-RecentExecMenu O-119：送页签通道回调的**稳定引用**（init 注册 / dispose 同一性注销用）——
    // 必须声明在 init 之前：init 块内会读它完成注册，声明在后会读到未初始化值（本仓构造期序坑第三次）。
    private val openEntryInTabCallback: (LogSqlEntry, LogEntryTab) -> Unit =
        { entry, tab -> openEntryInTab(entry, tab) }

    // ---------- O-1 日志窗口差异化：慢查询计数器 + 耗时着色 ----------

    /** 慢查询计数器（O-1 Phase C）。 */
    internal val slowQueryCounter = SlowQueryCounter(
        PluginSettings.getInstance().state.slowQueryThresholdMs,
    )

    /** 工具栏慢查询计数标签（动态更新）。 */
    private var slowQueryLabel: JLabel? = null

    // ---------- UI 组件（工具栏单行：动作图标组靠左 + 列表控制组靠右） ----------

    /**
     * SQL类型多选按钮（点击弹可勾选弹窗；文案只显示选中状态：全部 / 无 / 「查询, 更新」组合）。
     *
     * T-UiCoord F2（2026-09-21 用户定案「工具栏不要两行」）：原「SQL类型：」标签控件退役，
     * 标签键转作 tooltip 前缀（tooltip = `SQL类型：` + 当前状态**全文**）；按钮显示文本按
     * [DROPDOWN_TEXT_BUDGET_PX] 截尾省略——文本定宽封顶是多选后行宽不漂移、单行恒成立的前提。
     */
    internal val typeFilterButton = JButton().apply {
        isFocusable = false
        icon = MpvsIcons.TypeFilter
        horizontalTextPosition = SwingConstants.LEFT // 图标在文字右侧（下拉按钮惯例）
        addActionListener { showTypeFilterPopup() }
    }

    /** 当前打开的类型弹窗内勾选框（全选/全不选即时同步用；弹窗每次打开重建）。 */
    internal var typePopupCheckBoxes: List<Pair<StatementType, JCheckBox>> = emptyList()

    /** T-SelToggle：弹窗底部全选/全不选切换按钮（T-SelToggle 双按钮合一；弹窗每次打开重建，与 [typePopupCheckBoxes] 同生命周期约定）。 */
    internal var typePopupToggleButton: JButton? = null

    /**
     * 排序方式动作（T-LogSortModes / 用户 2026-10-14 校正）：点击弹单选菜单，当前方式进
     * 动作 tooltip 与菜单选中态。
     *
     * 形态改为 **动作图标组内的 AnAction**（原为固定宽度 JButton）：用户指出「排序按钮没被左侧
     * 按钮集归入、过窄时应当自动折叠」——JButton 不参与 ActionToolbar 的溢出机制，宽度不足时会
     * 压住相邻控件；改为 AnAction 后与捕捉/清空/搜索/滚底同簇，窄宽一起折进平台溢出菜单。
     * 代价：工具窗工具栏按惯例只渲染图标，当前排序方式不再常显于按钮文字，改由 tooltip
     * （`排序：<当前方式>`）与菜单选中态承载。
     */
    internal val sortModeAction: AnAction = object : AnAction() {
        init {
            templatePresentation.icon = MpvsIcons.Sort
        }

        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT（见 toolbarGroup KDoc 的字节码依据）
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            val label = currentSortLabel()
            e.presentation.icon = MpvsIcons.Sort
            e.presentation.text = label
            e.presentation.description = MpSqlVisualizerBundle.message("log.sort.label") + label
        }

        override fun actionPerformed(e: AnActionEvent) {
            showSortPopup(e.inputEvent?.component)
        }
    }

    /** 当前排序方式显示名（默认回退时间升序）。 */
    /**
     * 当前排序方式显示名（静态查表，零分配——T-SqlLogToolbarEdt W3）。
     *
     * 旧实现 `sortOptions().firstOrNull { it.mode == sortMode }?.label` 每次都要新建 7 个
     * `SortOption` 并调 7 次 bundle 取值，而这里处在工具栏每次展开的 update 路径上。
     */
    private fun currentSortLabel(): String =
        MpSqlVisualizerBundle.message(sortModeLabelKey(sortMode))

    /**
     * T-SqlLogPerf（20261006-1020 计划 + 用户点名）：「滚动到底部」动作 + 尾部跟随。
     *
     * 本动作一键跳列表底并恢复跟随；跟随态下新条目到达自动贴底。
     * 手动滚轮、滚动条或键盘导航立即暂停，只有再次点击本动作才恢复跟随。
     * 形态走左侧动作图标组（AnAction）而非右侧控制组 JButton——控制组最坏宽有预算锁
     * （SqlLogPanelLayoutTest 防「又往控制组加控件」），图标组窄了由平台溢出承接。
     */
    internal val scrollToEndAction: AnAction = ScrollToEndAction()

    internal val tailFollow = TailFollowState()
    private var listScrollPane: JBScrollPane? = null
    internal val tailFollowModeForTest: TailFollowMode get() = tailFollow.mode

    /**
     * 程序性滚动窗口（T-LogListAnchor 券03）。
     *
     * 滚动条的调整事件**无法区分用户手势与程序动作**——贴底、锚点复原、模型增删引起的 clamp
     * 都会触发同一个 `AdjustmentListener`。旧实现把任何「value 到底」都当成「用户滚回底部」，
     * 于是停在贴底一行内时，头部淘汰的 clamp 会把暂停态静默改回跟随（探针实测：remove(0) 与
     * addElement 各触发一次调整事件）。本计数器非零期间，调整事件不参与跟随判定。
     *
     * 用计数器而不是布尔：复原可能嵌套（贴底后再复原锚点）。
     */
    private var programmaticScrollDepth = 0

    /** 上一次观察到的滚动条位置（券03 单调性判据基线）。 */
    private var lastScrollBarValue = -1

    internal inline fun <T> withProgrammaticScroll(block: () -> T): T {
        programmaticScrollDepth++
        try {
            return block()
        } finally {
            programmaticScrollDepth--
        }
    }

    internal fun scrollToBottom(resume: Boolean) {
        if (resume) tailFollow.onScrollToEnd()
        if (listModel.isEmpty) return
        withProgrammaticScroll {
            list.ensureIndexIsVisible(listModel.size() - 1)
            // maximum 是内容总高语义，能滚到的最大值是 maximum - visibleAmount
            // （旧写法靠 Swing 的 clamp 兜底，变行高下会差一两像素）。
            listScrollPane?.verticalScrollBar?.let { bar ->
                bar.value = (bar.maximum - bar.visibleAmount).coerceAtLeast(0)
            }
        }
    }

    /**
     * 暂停态视口锚点（T-LogListAnchor 券02）：条目身份 + 它在视口内的行内像素偏移。
     *
     * 为什么需要它：JList **没有滚动锚定**。视口按像素定位，模型头部删一条，后面所有行的 y
     * 就整体上移一行高，而 `viewPosition` 不变——用户正在读的内容被「顶上去」。
     * 缓冲满载（默认 200）时每条新 SQL 都触发一次 `HeadEvictAppend`，于是暂停态下持续跳动。
     */
    internal class ScrollAnchor(val entry: LogSqlEntry, val offsetInRow: Int)

    /** 全量重建在途时的待复原锚点（条目可能落在后续批次里，逐拍重试）。 */
    internal var pendingScrollAnchor: ScrollAnchor? = null

    /** 取当前视口顶部条目与其行内偏移；无滚动容器/空列表/布局未就绪时返回 null。 */
    internal fun captureTopAnchor(): ScrollAnchor? {
        val viewport = listScrollPane?.viewport ?: return null
        val size = listModel.size()
        if (size == 0) return null
        val pos = viewport.viewPosition
        val index = list.locationToIndex(java.awt.Point(pos.x, pos.y + 1))
        if (index !in 0 until size) return null
        val bounds = list.getCellBounds(index, index) ?: return null
        return ScrollAnchor(listModel.getElementAt(index), bounds.y - pos.y)
    }

    /** 按条目身份复原视口；条目还不在模型里返回 false（调用方决定是否重试）。 */
    internal fun restoreAnchor(anchor: ScrollAnchor): Boolean {
        val viewport = listScrollPane?.viewport ?: return true
        val size = listModel.size()
        var index = -1
        for (i in 0 until size) {
            if (listModel.getElementAt(i) === anchor.entry) {
                index = i
                break
            }
        }
        if (index < 0) return false
        val bounds = list.getCellBounds(index, index) ?: return false
        withProgrammaticScroll {
            viewport.viewPosition = java.awt.Point(
                viewport.viewPosition.x,
                (bounds.y - anchor.offsetInRow).coerceAtLeast(0),
            )
        }
        return true
    }

    /** 头部淘汰后把视口按被删总高上移（等价于内容原地不动）。 */
    internal fun shiftViewportUpBy(removedHeight: Int) {
        if (removedHeight <= 0) return
        val viewport = listScrollPane?.viewport ?: return
        val pos = viewport.viewPosition
        val target = (pos.y - removedHeight).coerceAtLeast(0)
        if (target == pos.y) return
        withProgrammaticScroll { viewport.viewPosition = java.awt.Point(pos.x, target) }
    }

    // ---------- T-ListMode：SQL 显示模式（T-DisplayModeToSettings：入口迁设置页「列表显示」，两列表共享一键） ----------

    /** 搜索条（默认隐藏，Ctrl+F/⌘F 唤起；实时过滤 restoredSql 与摘要）。 */
    // ---------- T-SearchToggleNativeLook（O-137）：搜索条迁平台原生 SearchTextArea ----------
    //
    // MatchCase/Regex 双开关走原生钮（内嵌搜索框右侧，与 Ctrl+F 同形态）。20261010 用户令日志页
    // 补正则钮（D133「日志页无正则」拍板翻案）——正则语义与历史页同链：开关或 `regex:` 前缀、
    // 非法正则 fail-closed 回退子串。旗改从 NativeSearchTextField.findModel 读，过滤语义零变化。
    // 显隐契约保持在宿主 searchBar 上（isVisible 由 showSearch/hideSearch 控制）。

    private val nativeSearch = NativeSearchTextField(
        placeholderText = MpSqlVisualizerBundle.message("log.search.emptyText"),
        showRegex = true,
        project = project,
    )

    private val searchField = nativeSearch

    init {
        // 搜索框文本变更 → 防抖应用。T-SearchToggleNativeLook 迁平台原生 SearchTextArea 时漏接线：
        // 本仓 dev 侧接线原在 buildSearchField 的 DocumentListener 上，master 原生结构只剩
        // onSearchTextChanged / searchDebounceTimer 两个孤儿与 hideSearch 里「非空时触发
        // DocumentListener 清过滤并刷新」的注释（测试钩子 applySearchTextForTest 直调内部入口，
        // 覆盖不到真实键入分派，故未暴露）。本处补回接线，恢复「键入即过滤」「隐藏搜索条即清过滤」。
        nativeSearch.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSearchTextChanged()
            override fun removeUpdate(e: DocumentEvent) = onSearchTextChanged()
            override fun changedUpdate(e: DocumentEvent) = onSearchTextChanged()
        })
    }

    /** 当前大小写敏感态（原生钮状态落点）。 */
    internal val logMatchCase: Boolean get() = nativeSearch.isMatchCase()

    /** 当前正则态（原生钮状态落点；`regex:` 前缀独立生效）。 */
    internal val logRegex: Boolean get() = nativeSearch.isRegex()

    /** 正则编译 memo（防抖 200ms 反复 apply 复用编译结果，键=源串+matchCase 旗）。 */
    internal val regexMemo = HistoryRegexMemo()

    /** 搜索条宿主（原生 SearchTextArea 自带内嵌开关；显隐由 showSearch/hideSearch 控制）。 */
    private val searchBar = JPanel(BorderLayout()).apply {
        isOpaque = false
        isVisible = false
        add(nativeSearch.row, BorderLayout.CENTER)
    }

    /** 测试断言/驱动：MatchCase 旗（等价用户点击 = 置 FindModel.isCaseSensitive 后自动刷新列表）。 */
    internal var matchCaseToggleForTest: Boolean
        get() = logMatchCase
        set(value) {
            nativeSearch.findModel.isCaseSensitive = value
            refreshList()
        }

    /** 测试断言/驱动：Regex 旗（等价用户点击 = 置 FindModel.isRegularExpressions 后自动刷新列表）。 */
    internal var regexToggleForTest: Boolean
        get() = logRegex
        set(value) {
            nativeSearch.findModel.isRegularExpressions = value
            refreshList()
        }

    // ---------- 测试访问器（headless 测试断言用） ----------

    /** 当前列表模型条目数（测试断言）。 */
    internal val visibleEntryCount: Int get() = listModel.size()

    /** 当前列表模型条目 restoredSql 列表（测试断言）。 */
    internal val visibleEntries: List<String>
        get() = (0 until listModel.size()).map { listModel.getElementAt(it).restoredSql }

    /** 当前激活的类型过滤集合（测试断言）。 */
    internal val currentFilter: Set<StatementType> get() = activeFilter.toSet()

    /** 当前排序模式（测试断言）。 */
    internal val currentSortMode: SortMode get() = sortMode

    /** 排序方式选择（测试驱动：等价用户在菜单里点选某项）。 */
    internal fun selectSortModeForTest(mode: SortMode) = applySortMode(mode)

    /** 排序按钮（测试断言文案/可见性）。 */
    internal val sortActionForTest: AnAction get() = sortModeAction

    /** 排序菜单项文案列表（测试断言选项齐备；构建菜单但不 show）。 */
    internal fun sortMenuLabelsForTest(): List<String> = buildSortMenuItems().map { it.text }

    /** 当前排序方式显示名（测试断言；与动作 presentation.text 同源）。 */
    internal val sortButtonTextForTest: String get() = currentSortLabel()

    /** 「只看慢查询」筛选态（测试驱动：等价用户勾选弹窗内该勾选框）。 */
    internal var slowOnlyForTest: Boolean
        get() = slowOnly
        set(value) {
            slowOnly = value
            slowOnlyCheckBox?.isSelected = value
            refreshList()
        }

    /** 「只看慢查询」勾选框（弹窗构建后非 null；测试断言文案/勾选态）。 */
    internal val slowOnlyCheckBoxForTest: JCheckBox? get() = slowOnlyCheckBox
    internal val listScrollPaneForTest: JBScrollPane? get() = listScrollPane
    internal fun resumeTailFollowForTest() = scrollToBottom(resume = true)

    /** 去重勾选框（F-44-2 测试驱动：模拟用户勾选「移除重复的sql」验证既有条目立即去重）。 */
    internal val dedupeCheckBoxForTest: JCheckBox get() = dedupeCheckBox

    /** SQL类型多选按钮（测试断言文案）。 */
    internal val typeFilterButtonForTest: JButton get() = typeFilterButton

    /** T-SelToggle：类型弹窗底部全选/全不选切换按钮（未打开弹窗时为 null）。 */
    internal val typePopupToggleButtonForTest: JButton? get() = typePopupToggleButton

    /**
     * T-SelToggle：headless 测试直接构建弹窗内容（不经 `popup.show`——无显示设备时
     * lightweight popup 不可靠），返回后 [typePopupToggleButtonForTest] 即非 null 可驱动。
     */
    internal fun buildTypeFilterContentForTest(): JPanel = buildTypeFilterContent()

    /**
     * T-UiCoord F2：工具栏单行宿主（布局契约断言用——单行 / 不越界 / 控件共轴）。
     * 返回的是生产实例（`buildToolbar()` 的产物），不是测试另搭的仿制品。
     */
    internal val toolbarRowForTest: JPanel? get() = toolbarRow

    /** T-UiCoord F2：工具栏右侧控制组（类型 / 排序 / 去重勾选 / 慢查询徽章）——单行契约的主断言面。 */
    internal val toolbarControlsForTest: JPanel? get() = toolbarControls

    /** T-UiCoord F2：工具栏动作图标组组件（结构断言：它必须挂在行宿主 CENTER；排序已并入该动作组）。 */
    internal val toolbarComponentForTest: JComponent? get() = toolbarComponent

    /** T-UiCoord F2：下拉按钮显示文本的像素预算（测试按同一预算算期望的省略文本）。 */
    internal val dropdownTextBudgetForTest: Int get() = JBUI.scale(DROPDOWN_TEXT_BUDGET_PX)

    /** 应用类型过滤组合（测试驱动：设置选中集 + 刷新按钮文案与列表）。 */
    internal fun applyTypeFilterForTest(types: Set<StatementType>) {
        activeFilter.clear()
        activeFilter.addAll(types)
        updateTypeFilterButton()
        refreshList()
    }

    /**
     * M56 T-M56-00：设置动态聚焦过滤（页签/请求聚合驱动；null 或全空维度 = 取消聚焦）。
     * 桥通道不写此状态，行为与现状逐字一致。过滤值变化经 refreshList 全量差分呈现
     * （离散增删走 FullRebuild，无需特殊增量分支）。
     */
    internal fun setFocusFilter(focus: FocusFilter?) {
        val next = focus?.takeUnless { it.isPassthrough }
        if (next == activeFocus) return
        activeFocus = next
        refreshList()
    }

    /** 搜索条（测试断言显隐/文本）。 */
    internal val searchFieldForTest: cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField get() = searchField

    /** 布局测试契约：搜索条可见性（旧断言落在 searchField.isVisible 上，迁到宿主 searchBar）。 */
    internal var searchFieldVisibleForTest: Boolean
        get() = searchBar.isVisible
        set(value) {
            searchBar.isVisible = value
        }

    internal val previewHostForTest: SqlListPreviewHost get() = previewHost
    internal val dockedPreviewForTest: SqlDockedPreview get() = dockedPreview

    /** 工具栏慢查询计数标签（测试断言显隐与文案）。 */
    internal val slowQueryLabelForTest: JLabel? get() = slowQueryLabel

    /**
     * 捕捉开关动作（T-CaptureBack）：回迁面板工具栏首位（T-UN-11 曾迁工具窗标题栏，
     * 新 UI 下标题栏动作悬停才可见致入口不可发现，回归面板）。图标/文案在 [CaptureAction.update]
     * 按 manager.capturing 态输出；测试断言直接读 presentation，不依赖按钮刷新。
     */
    val captureToggleAction: AnAction = CaptureAction()

    internal val previewToggleAction: ToggleAction = object : ToggleAction(
        MpSqlVisualizerBundle.message("log.dockedPreview.toggle"),
        MpSqlVisualizerBundle.message("log.dockedPreview.toggle.tip"),
        MpvsIcons.DockedPreview,
    ) {
        // T-SqlLogToolbarEdt W1：ToggleAction 的 update 由平台模板实现，但 isSelected 被覆写后
        // 平台仍按 AnAction 默认的更新线程判定 → 显式声明 BGT（isSelected 只读 dockedPreview 状态）。
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean = dockedPreview.isOpen

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (dockedPreview.isOpen != state) previewHost.toggle()
        }
    }

    /**
     * R53：增强捕获暂停/恢复动作（工具窗入口；图标/文案按 userPaused 态输出）。
     * 语义 = 暂停只停 agent 通道（事件在入队点丢弃）、控制台通道照常；暂停≠关闭——
     * 连接/心跳/CONFIG 链不动。增强未开启时隐藏（update 置 isVisible=false，平台不渲染）。
     */
    val agentPauseAction: AnAction = AgentPauseAction()

    /**
     * 工具栏动作组（T-M39：internal 供测试断言帮助入口存在性；
     * update 由工具栏生命周期驱动，headless 测试手动触发；须在 init 前声明）。
     *
     * 全部动作直接入组、按用户 2026-10-14 选定的「方案 A」排列：
     * 操作在前、工具在后、**帮助收尾**（`?` 是纯信息性动作，平台惯例放末尾；溢出从右端折叠，
     * 帮助最先让位）。**不设自建「更多」弹层**——窄宽折叠交给平台 ActionToolbar 自带的溢出（`»`），
     * 悬停/点击即列出被折叠的动作。
     *
     * T-SqlLogToolbarEdt W1（2026-10-24，用户令「可修的性能问题现在立项」）：**组与组内动作显式
     * 声明 `ActionUpdateThread.BGT`**。
     *
     * 为什么必须显式声明（243 字节码实证，非猜测）：`AnAction.getActionUpdateThread()` 的默认
     * 实现是「实现 `UpdateInBackground` 且返回 true → BGT；`ActionClassMetaData.isDefaultUpdate`
     * 为 true → BGT；**否则 EDT**」，而 `isDefaultUpdate` 判定的是「声明 `update()` 的类是否就是
     * `AnAction` 本身」——**组内这批动作全都覆写了 `update()`**，于是默认全部落 EDT。
     *
     * 实测代价（沙盒 `idea.log` 20:34:28 / 20:35:23）：
     * `ActionUpdater - 3981 ms / 3174 ms total to grab EDT 10 times to expand
     *  DefaultActionGroup#expandGroup@cn.song.mybatisplus.visualizer.log.toolbar ...
     *  Use ActionUpdateThread.BGT`——累计抢 EDT 3.1~4.0 秒。
     *
     * 约束（W2 已逐个核过）：`update()` 内只读状态 + 写 `e.presentation`，**不触碰 Swing 组件/
     * 文档/EDT-only API**，故 BGT 合规。
     */
    internal val toolbarGroup = object : DefaultActionGroup() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }.apply {
        addAll(
            listOf(
                captureToggleAction,
                agentPauseAction,
                ClearAction(),
                SettingsAction(),
                ManageSuppressAction(),
                ShowSearchAction(),
                previewToggleAction,
                scrollToEndAction,
                sortModeAction,
                CommunityAction(),
                HelpAction(),
            ),
        )
    }

    // ---------- T-Perf-15：manager 监听器命名引用（init 注册 / dispose 注销） ----------

    /** 缓冲变更 → 列表刷新（manager.uiNotifyListener 回调链；EDT 执行）。 */
    private val uiRefreshListener: () -> Unit = { refreshList() }

    /** capturing 状态变化 → 刷新捕捉动作 presentation（T-UiNative T-UN-11：动作挂工具窗标题栏，
     *  图标/文案由 [CaptureAction.update] 按 capturing 态输出）。 */
    private val capturingUiListener: () -> Unit = {
        LOG.info("SqlLogPanel.capturingListener: 触发")
        ApplicationManager.getApplication().invokeLater {
            // T-UiNative T-UN-11：反射 hack（updateCaptureButtonIcon 遍历组件树强改 ActionButton 图标）
            // 退役——回归平台更新机制。真实 IDE 中工具窗可见，isShowing 满足，updateActionsImmediately
            // 正常执行；沙盒/单测中组件不可见时平台会跳过更新——测试断言直接读
            // captureToggleAction.presentation，不依赖按钮刷新。
            _toolbarRef?.updateActionsImmediately()
            // T-CaptureTruth T-CT-03：捕捉态变化 → 诊断行立即跟上（起停捕捉是用户最先看的时刻）
            syncDiagnostics()
        }
    }

    // ---------- T-M55-03/04：动态聚焦过滤页签栏字段（须在 init 块之前——Kotlin 按文本序初始化，
    // init 里 buildTopPanel 会读这些字段） ----------

    /** 当前开启的聚焦页签（EDT 读写——右键菜单/页签关闭按钮均在 EDT）。 */
    internal val focusTabs = mutableListOf<FocusTab>()

    /** 聚焦页签栏（工具栏行与搜索条之间；无页签时隐藏不占高度）。 */
    private val focusTabBar = JPanel(WrapLayout()).apply { isOpaque = false; isVisible = false }

    /** 聚焦隐藏计数提示（聚焦视图下不匹配任何页签的条目数；无页签时隐藏）。 */
    internal val focusHiddenLabel = JLabel().apply {
        foreground = MpvsUiColors.META_GRAY
        isVisible = false
    }

    // ---------- T-UiCoord F2：工具栏单行宿主字段（同样须在 init 之前——init 里 buildToolbar 会赋值；
    // 声明晚于 init 会让赋值被随后的 `= null` 初始化器覆盖回 null） ----------

    /** 工具栏单行宿主（`buildToolbar()` 产物）。 */
    private var toolbarRow: JPanel? = null

    /** 工具栏右侧控制组（EAST 宿主内那层 FlowLayout 面板）。 */
    private var toolbarControls: JPanel? = null

    /** 工具栏动作图标组组件（挂行宿主 CENTER）——结构断言用（曾因还原反向验证探针时误删挂载而漏）。 */
    internal var toolbarComponent: JComponent? = null

    // ---------- R53：双通道捕获状态栏（字段须在 init 之前——init 里 buildStatusBar 会赋值） ----------

    /** 状态栏：控制台通道段（已挂载 N 个 · 已扫描 M 行）。 */
    internal var statusConsoleLabel: JLabel? = null

    /** 状态栏：增强 agent 通道段（未开启 / 暂停中 / 已挂载 pid · 已收事件 N / 等待…）。 */
    internal var statusAgentLabel: JLabel? = null

    /**
     * 状态栏宿主（非捕捉态整体隐藏——只清两段文案会留下分隔符与一条空白高度；
     * BorderLayout 跳过 isVisible=false 的子件，故置位后需 revalidate）。
     */
    internal var statusBarPanel: JPanel? = null

    init {
        // 注册子面板到 Disposer 树：父面板 dispose 时级联释放子面板（及其 DocumentListener），
        // 避免 IDE 退出时 ObjectTree 报 Memory leak detected
        Disposer.register(this, logToSqlPanel)
        Disposer.register(this, sqlToJavaPanel)

        // T-RecentExecMenu O-119：面板外「送条目到页签」通道注册（弹框右键用；dispose 注销）
        manager.onOpenEntryInTab = openEntryInTabCallback

        // 标签 1：SQL 日志（工具栏行 + 搜索条 + 条目列表 + 底部双通道状态栏）
        val logPanel = JPanel(BorderLayout())
        logPanel.add(buildTopPanel(), BorderLayout.NORTH)
        previewHost = SqlListPreviewHost(
            list, buildListPane(), dockedPreview, "mpvs.logs.preview.split", showIconHint = true,
        ) {
            Toggleable.setSelected(previewToggleAction.templatePresentation, it)
        }
        logPanel.add(previewHost.component, BorderLayout.CENTER)
        // R53：双通道捕获状态栏（捕捉期间常驻；非捕捉态清空隐藏——见 syncCaptureStatusBar）
        logPanel.add(buildStatusBar(), BorderLayout.SOUTH)

        // 标签 2：日志转 SQL（粘贴日志 → 批量还原可执行 SQL）
        logsTab = tabs.addTab(com.intellij.ui.tabs.TabInfo(logPanel).setText(MpSqlVisualizerBundle.message("log.tab.logs")))
        logToSqlTab = tabs.addTab(com.intellij.ui.tabs.TabInfo(logToSqlPanel).setText(MpSqlVisualizerBundle.message("log.tab.logToSql")))
        sqlToJavaTab = tabs.addTab(com.intellij.ui.tabs.TabInfo(sqlToJavaPanel).setText(MpSqlVisualizerBundle.message("log.tab.sqlToJava")))
        // R19（T-PerfSweep 批次 2）+ T-HistoryTabAlways：历史 tab 常驻——原 F-44 条件挂载
        // （「开启自动保存 或 .idea 中已有历史文件」）让未开启自动保存的干净项目没有入口，
        // 关掉设置页开关还会把已挂载的 tab 摘掉；文件列表空态已带「开启自动保存」引导文案，
        // tab 常驻即引导闭环，F-44 的移除分支随之废除、设置变更不再参与显隐。
        addHistoryTab()
        add(tabs.component, BorderLayout.CENTER)

        // T-M36.6：tab 重选时刷新形态下拉（设置页修改后切回即同步；不触发重生成）
        tabs.addListener(
            object : com.intellij.ui.tabs.TabsListener {
                override fun selectionChanged(
                    previous: com.intellij.ui.tabs.TabInfo?,
                    current: com.intellij.ui.tabs.TabInfo?,
                ) {
                    if (current === sqlToJavaTab) {
                        sqlToJavaPanel.refreshFormatFromSettings()
                    }
                    // UI-004：TabsListener 参数顺序为旧页签、新页签；必须在进入历史页时刷新。
                    // 设置变更可能已在隐藏期间扫描空目录，不能依赖 showing 的一次性首扫兜底。
                    if (current === historyTab) {
                        historyPanel.refresh()
                    }
                }
            },
            this,
        )

        // 设置变更后列表重排重绘（T-HistoryTabAlways 起历史 tab 无显隐逻辑，恒在）
        val settingsListener = SettingsChangeListener {
            ApplicationManager.getApplication().invokeLater {
                // T-LogListFreeze-04：片段渲染直读设置态（时间戳/耗时显示/慢查询阈值）——设置变更后
                // 走 refreshList 重排重绘即生效（SINGLE_LINE 直渲染；多行模式片段缓存按模式键自动失效）
                // T-ListMode：显示模式入口已迁设置页（T-DisplayModeToSettings）——设置页 apply 广播后
                // 行高随模式重设。T-MergeFollowup-03（复查二轮）：BasicListUI 的 cellHeights 布局缓存
                // **不归 Swing layout 管**——revalidate 无效；且多行模式间切换是 -1→-1（setFixedCellHeight
                // 值不变不触发 propertyChange 失效），refreshList 增量路径在内容无变化时也不产生 model
                // 结构事件——updateUI() 重装列表 UI 强制布局状态失效，是最直接可靠的失效手段。
                applyListCellHeight()
                list.updateUI()
                refreshList()
                // T-CrudColor：历史二级列表同样直读设置，重绘即生效（不重扫文件、不打断浏览位置）。
                // 仅当历史 tab 已挂载时才取面板——避免仅为重绘而强制实例化（historyPanel 是 lazy）
                if (historyTab != null) {
                    historyPanel.repaintSqlList()
                }
            }
        }
        project.messageBus.connect(this).subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, settingsListener)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener {
                ApplicationManager.getApplication().invokeLater(
                    { list.repaint() },
                    ModalityState.any(),
                )
            },
        )

        // T-Perf-15（自 dev 移植）：监听器改命名引用——dispose 时精确注销（此前 addUiListener/addCapturingListener
        // 只加不减，面板随 tool window content 销毁重建逐次累积）
        manager.addUiListener(uiRefreshListener)
        refreshList()

        // capturing 变化 → 刷新捕捉动作（T-UiNative T-UN-11：动作挂工具窗标题栏，presentation
        // 由 CaptureAction.update 按 capturing 态输出；监听经平台更新机制刷新，沙盒测试断言直读
        // captureToggleAction.presentation）
        manager.addCapturingListener(capturingUiListener)
    }

    // ---------- T-M55-03/04：动态聚焦过滤页签栏（页签=过滤器，多开并集，可关闭；不碰差分） ----------
    // 字段（focusTabs/focusTabBar/focusHiddenLabel）声明在类头部 init 块之前（文本序初始化）

    /** 当前开启的聚焦页签（测试断言）。 */
    internal fun focusTabsForTest(): List<FocusTab> = focusTabs.toList()

    /** 聚焦页签栏子组件数（测试断言：页签数 = chips + 前缀标签 + 计数标签）。 */
    internal val focusTabBarComponentCountForTest: Int get() = focusTabBar.componentCount

    /** 聚焦隐藏计数提示（测试断言显隐与文案）。 */
    internal val focusHiddenLabelForTest: JLabel get() = focusHiddenLabel

    /** 测试驱动：等价右键「聚焦调用方方法/按请求聚焦」添加页签。 */
    internal fun addFocusTabForTest(tab: FocusTab) = addFocusTab(tab)

    /** 测试驱动：等价页签 ✕ 关闭。 */
    internal fun removeFocusTabForTest(tab: FocusTab) = removeFocusTab(tab)

    private fun addFocusTab(tab: FocusTab) {
        val next = when (tab) {
            is FocusTab.Request -> addRequestTab(focusTabs, tab.requestTag)
            is FocusTab.Method -> if (focusTabs.contains(tab)) focusTabs.toList() else focusTabs + tab
            is FocusTab.Template -> addTemplateTab(focusTabs, tab.structureKey, tab.count)
        }
        focusTabs.clear()
        focusTabs.addAll(next)
        rebuildFocusTabBar()
        refreshList()
    }

    private fun removeFocusTab(tab: FocusTab) {
        if (focusTabs.remove(tab)) {
            rebuildFocusTabBar()
            refreshList()
        }
    }

    /** 重建页签栏 chips（页签数 = 前缀标签 + N×chip + 计数标签）。 */
    private fun rebuildFocusTabBar() {
        focusTabBar.removeAll()
        focusTabBar.add(JLabel(MpSqlVisualizerBundle.message("log.focus.label")))
        focusTabs.forEach { focusTabBar.add(buildFocusChip(it)) }
        focusTabBar.add(focusHiddenLabel)
        focusTabBar.isVisible = focusTabs.isNotEmpty()
        focusTabBar.parent?.revalidate()
        focusTabBar.parent?.repaint()
    }

    /** 单个聚焦页签 chip：名称 + ✕ 关闭（关闭即摘除过滤器并刷新）。 */
    private fun buildFocusChip(tab: FocusTab): JComponent {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { isOpaque = false }
        chip.add(JLabel(tab.label).apply { toolTipText = tab.label })
        chip.add(JButton(AllIcons.Actions.Close).apply {
            isFocusable = false
            toolTipText = MpSqlVisualizerBundle.message("log.focus.close")
            addActionListener { removeFocusTab(tab) }
        })
        return chip
    }

    /** 右键入口的聚焦页签解析（sourceFile+sourceLine → 方法行号区间页签）；解析失败返回 null。 */
    internal fun resolveCallerMethodFocus(entry: LogSqlEntry): FocusTab.Method? {
        val range = CallerMethodRangeResolver.resolve(project, entry.sourceFile, entry.sourceLine) ?: return null
        return FocusTab.Method(range.sourceFile, range.startLine, range.endLine, range.className, range.methodName)
    }

    // ---------- 顶部区域（工具栏行 + 聚焦页签栏 + 搜索条） ----------

    /** 顶部竖向容器：工具栏行（NORTH）+ 聚焦页签栏（CENTER，无页签隐藏不占高度）+ 搜索条（SOUTH）。 */
    private fun buildTopPanel(): JPanel {
        val top = JPanel(BorderLayout())
        top.add(buildToolbar(), BorderLayout.NORTH)
        top.add(focusTabBar, BorderLayout.CENTER)
        // T-HistorySearchToggles：挂搜索条宿主（搜索框 + MatchCase 开关），显隐契约不变
        top.add(searchBar, BorderLayout.SOUTH)
        return top
    }

    private fun buildToolbar(): JPanel {
        // T-M31.16：暂停按钮移除（捕捉开关已覆盖——用户方案 A）
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID, toolbarGroup, true)
            .apply { targetComponent = this@SqlLogPanel }
        _toolbarRef = toolbar // 保存引用供 capturing 监听刷新

        updateTypeFilterButton()
        updateSortButtonText()
        // T-UiCoord F2（2026-09-21 用户定案「不要两行」）：工具栏**恒为单行**——
        // 左侧动作图标组（CENTER）+ 右侧列表控制组（EAST：慢查询徽章 / SQL类型 / 去重勾选，勾选在最右）。
        // T-SqlListSortRight（2026-10-14 用户两轮校正）：排序**并入左侧动作组**（组内最后一个动作），
        // 与捕捉/清空/搜索/滚底一起由 ActionToolbar 折叠；不再用固定宽 JButton（它不参与溢出，
        // 窄宽会压住 SQL类型按钮，用户也反馈「没被左侧按钮集归入」）。
        // BorderLayout 结构上不存在折行可能，故任意宽度下都只有一行；原 WrapLayout 流式行退役
        // ——它按「谁最后谁折」处理放不下，2026-09-16 加排序单元、09-18 加第 5 枚图标后，
        // 460px 侧边栏下「排序」单元孤行到第二行（右留 300px 空档、两行左缘差 10px）。
        val row = JPanel(BorderLayout())
        toolbarComponent = toolbar.component
        row.add(toolbar.component, BorderLayout.CENTER)

        // O-1：慢查询计数徽章。T-UiCoord F2：由「慢查询: N」全文改短徽章（复用存量键
        // log.panel.slowQueryBadge）让出一行余量，全文进 tooltip（见 updateSlowQueryLabel）。
        slowQueryLabel = JLabel().apply {
            // T-UN-13（T-UiNative）：写死红 → namedColor（亮值不变，主题可覆写）
            foreground = MpvsUiColors.SLOW_RED
            font = font.deriveFont(Font.BOLD)
            isVisible = false // 默认隐藏，有慢查询时显示
        }
        // 控制组：Flow(RIGHT) 单行右对齐；外包 Y 轴 BoxLayout + 上下 glue 使整组垂直居中
        // （FlowLayout 自身按顶部摆放，直接挂 EAST 会与图标组差几像素不共轴）
        val controls = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(slowQueryLabel)
            add(typeFilterButton)
            add(dedupeCheckBox)
        }
        toolbarControls = controls
        val right = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(Box.createVerticalGlue())
            add(controls)
            add(Box.createVerticalGlue())
        }
        row.add(right, BorderLayout.EAST)
        toolbarRow = row
        return row
    }

    /**
     * R53：双通道捕获状态栏（SQL 日志页签底部一行，捕捉期间常驻；非捕捉态整条隐藏）。
     * 两个 JLabel（控制台通道 / 增强通道）+ 语义分隔符；文案由 [captureStatusLineText] 纯函数产出，
     * [syncCaptureStatusBar] 在 2s 诊断定时器上无锁轮询刷新（V80①：取数全为只读内存态）。
     * 淡色（[UIUtil.getContextHelpForeground]）与空态诊断行标注同视觉惯例，不抢正文。
     */
    private fun buildStatusBar(): JPanel {
        statusConsoleLabel = JLabel().apply { foreground = UIUtil.getContextHelpForeground() }
        statusAgentLabel = JLabel().apply { foreground = UIUtil.getContextHelpForeground() }
        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(statusConsoleLabel)
            add(JLabel(SEP_LABEL).apply { foreground = UIUtil.getContextHelpForeground() })
            add(statusAgentLabel)
            border = JBUI.Borders.empty(0, 6, 3, 6)
            // 初始隐藏：面板构建时捕捉多半未起，先显示会留一条无信息的空带
            isVisible = false
            statusBarPanel = this
        }
    }

    /** Ctrl+F/⌘F 唤起搜索条（快捷键经 CommonShortcuts.getFind() 平台 keymap 自适应 mac/win）。 */
    internal inner class ShowSearchAction : AnAction() {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        init {
            registerCustomShortcutSet(CommonShortcuts.getFind(), list)
        }

        override fun update(e: AnActionEvent) {
            // 图标必须在 update 里写（工具栏 ActionButton 按 presentation 渲染；
            // 不设则渲染为占位圆圈——用户实测缺陷）
            e.presentation.icon = MpvsIcons.Search
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.search")
        }

        override fun actionPerformed(e: AnActionEvent) {
            showSearch()
        }
    }

    private fun showSearch() {
        if (!searchBar.isVisible) {
            searchBar.isVisible = true
            searchBar.parent?.revalidate()
            searchBar.parent?.repaint()
        }
        nativeSearch.component.requestFocusInWindow()
    }

    private fun hideSearch() {
        searchField.text = "" // 非空时触发 DocumentListener 清过滤并刷新
        searchBar.isVisible = false
        searchBar.parent?.revalidate()
        searchBar.parent?.repaint()
        list.requestFocusInWindow()
    }

    // T-Perf-24（自 dev 移植）：搜索输入防抖——每击键一次 O(N) 刷新 → 停顿 200ms 应用一次
    private val searchDebounceTimer =
        javax.swing.Timer(SEARCH_DEBOUNCE_MS) { applySearchText() }.apply { isRepeats = false }

    private fun onSearchTextChanged() {
        searchDebounceTimer.restart()
    }

    private fun applySearchText() {
        searchText = searchField.text
        // T-LogToSqlHint 批：喂本组件搜索历史（平台历史钮已接管为自有实现）
        nativeSearch.recordSearchQuery(searchText)
        refreshList()
    }

    /**
     * S1-4④：非法正则显式提示（与历史页 [HistorySqlLogPanel.applySqlSearch] 同链、同文案）。
     *
     * 历史页此前已显式报错，日志页却在 [filterBySearch] 里静默回退子串——同一个输入换一页就
     * 「没反应」。本方法在 [refreshList] 过滤后调用（memo 已写入本次编译结果），把该错误送到
     * 原生搜索条的状态区（`setSearchError`：背景变色 + 可见右侧标签 + tooltip），不改回退语义。
     */
    internal fun updateSearchErrorFeedback() {
        val q = searchText.trim()
        val error = if (invalidRegexQuery(q, logRegex, regexMemo)) {
            MpSqlVisualizerBundle.message("log.history.search.invalidRegex")
        } else {
            null
        }
        nativeSearch.setSearchError(error)
        nativeSearch.setStatusText(
            error ?: if (q.isEmpty()) "" else MpSqlVisualizerBundle.message("log.history.search.results", lastRendered.size),
        )
    }

    /** 测试驱动：立即应用搜索框文本（headless 下防抖 Swing Timer 到点不可控），等价生产防抖回调。 */
    internal fun applySearchTextForTest() = applySearchText()

    // ---------- 工具栏动作 ----------

    /** T-Perf-17：historyPanel 当前是否已注册到 Disposer 树（防重复注册守卫标志）。 */
    private var historyPanelRegistered = false

    /**
     * 添加历史 tab（T-HistoryTabAlways 后为构造期唯一调用点）。
     *
     * T-Perf-17：Disposer 注册与防重入守卫保留——历史面板必须登记进本面板的 Disposer 树
     * （否则工具窗关闭时面板连同渲染器/防抖 Timer 不释放）；守卫在将来出现重复挂载路径时仍生效
     * （重复注册在 2026.x 平台报 "Object must not be registered twice" 错误弹窗）。
     */
    private fun addHistoryTab() {
        if (!historyPanelRegistered) {
            Disposer.register(this, historyPanel)
            historyPanelRegistered = true
        }
        historyTab = tabs.addTab(
            com.intellij.ui.tabs.TabInfo(historyPanel).setText(MpSqlVisualizerBundle.message("log.tab.history")),
        )
    }

    /** 测试访问器：主 tab 容器（历史 tab 常驻断言用；T-HistoryTabAlways 起 tab 恒在，无显隐路径）。 */
    internal val tabsForTest: com.intellij.ui.tabs.JBTabs get() = tabs

    /** 工具栏引用（capturing 变化时经平台更新机制刷新动作 presentation，T-UN-11）。 */
    internal var _toolbarRef: ActionToolbar? = null

    /**
     * 捕捉开关（T-UiNative T-UN-11：挂工具窗标题栏，经 [captureToggleAction] 暴露）。
     * 图标/文案在 update 按 manager.capturing 态输出（平台更新机制驱动，不再反射强改按钮）。
     */
    internal inner class CaptureAction : AnAction() {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            if (manager.capturing) {
                e.presentation.icon = MpvsIcons.CaptureStop
                e.presentation.text = MpSqlVisualizerBundle.message("log.panel.stop")
                e.presentation.description = if (manager.isWaitingForConsole)
                    MpSqlVisualizerBundle.message("log.panel.capture.waiting.desc") else null
            } else {
                e.presentation.icon = MpvsIcons.CaptureStart
                e.presentation.text = MpSqlVisualizerBundle.message("log.panel.capture")
                e.presentation.description = null
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (manager.capturing) {
                manager.stopCapture()
            } else {
                manager.startCapture(manual = true)  // 手动点击：设置 userArmed 持久标志
            }
        }
    }

    /**
     * R53：增强捕获暂停/恢复（工具窗入口）。
     *
     * T-AgentPauseVisibility（2026-10-19，用户拍板方案 B）：**临时全隐藏**。
     * 增强捕获已默认开启且无提示，该按钮在应用未运行时没有可暂停对象，并与捕捉按钮形成
     * 「播放/暂停」误读；先整体隐藏，动作对象仍保留在工具栏组内（不删能力代码）。
     * 代价：暂停 agent 通道的 UI 入口暂时不可达（关闭总开关仍会回落控制台）。
     * 恢复条件：方案 A（agent 已挂载或暂停态才显示）落地，或新增状态栏/右键菜单入口。
     *
     * 历史语义（保留在 [actionPerformed]）：暂停只停 agent 通道（事件在入队点丢弃）、
     * 控制台通道照常；暂停≠关闭——连接/心跳/CONFIG 链不动。
     */
    private inner class AgentPauseAction : AnAction() {
        override fun update(e: AnActionEvent) {
            // 方案 B：无条件隐藏。不要改回 isEnhancedEnabled()——恢复时必须走方案 A 的条件可见。
            e.presentation.isVisible = false
            e.presentation.isEnabled = false
        }

        override fun actionPerformed(e: AnActionEvent) {
            val am = AgentManager.getInstance(project)
            am.setUserPaused(!am.userPaused)
        }
    }

    internal inner class ClearAction : AnAction(MpvsIcons.Clear) {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Clear
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.clear")
        }

        override fun actionPerformed(e: AnActionEvent) {
            tailFollow.onClear()
            manager.entries.clear()
        }
    }

    /**
     * T-SqlLogPerf：「滚动到底部」动作（跳列表底 + 恢复尾部跟随）。
     * 图标 = [MpvsIcons.ScrollToEnd]（列表 + 向下箭头；原先复用 [AllIcons.General.ArrowDown]）。
     */
    internal inner class ScrollToEndAction : AnAction() {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.ScrollToEnd
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.scrollBottom")
            e.presentation.description = MpSqlVisualizerBundle.message(
                if (tailFollow.shouldFollow) "log.panel.scrollBottom.tip" else "log.panel.scrollBottom.paused.tip",
            )
        }

        override fun actionPerformed(e: AnActionEvent) {
            scrollToBottom(resume = true)
        }
    }

    internal inner class SettingsAction : AnAction() {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Settings
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.settings")
        }

        override fun actionPerformed(e: AnActionEvent) {
            // T-SettingsFocusFromLink：统一走主设置页入口（hint=null 只切到 SQL 日志页不聚焦行）
            cn.song.mybatisplus.visualizer.config.PluginSettingsFolderConfigurable
                .openSqlLogSettingsFocused(project, null)
        }
    }

    /**
     * T-SqlSuppress（O-69）：屏蔽管理入口（D1 定案主入口，插设置旁；图标 = [MpvsIcons.Suppress]
     * 自绘「眼睛 + 红斜线」，原先复用 [AllIcons.General.Filter] 漏斗——漏斗让给 SQL 类型过滤）。
     * 打开与右键「管理屏蔽规则…」同一非模态对话框。
     */
    internal inner class ManageSuppressAction : AnAction() {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Suppress
            e.presentation.text = MpSqlVisualizerBundle.message("log.suppress.manage")
        }

        override fun actionPerformed(e: AnActionEvent) {
            SqlSuppressManagerDialog(project).show()
        }
    }

    internal inner class CommunityAction : AnAction(
        MpSqlVisualizerBundle.message("log.community.action"),
        MpSqlVisualizerBundle.message("log.community.action.tip"),
        MpvsIcons.Community,
    ) {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            CommunityDialog(project).apply { startLoading() }.show()
        }
    }

    /** T-M39：帮助入口（? 按钮，位于设置按钮旁）→ 内嵌浏览器打开本地 HTML 帮助文档。
     *  图标 = [MpvsIcons.Help]（AllIcons.Actions.Help，纯灰问号，与设置/清空按钮同色系；
     *  ContextHelp 为灰蓝圆圈 #9AA7B0，与旁边按钮色相不一致——用户反馈）。
     *
     *  打开逻辑（2026-08-31 定稿：本地 HTML 为主路径，零网络依赖）：
     *  1. JCEF 可用 → HTMLEditorProvider.openEditor(project, title, html) 打开本地
     *     marketplace-custom-page.html；3 参重载内部走 JCEFHtmlPanel.loadHTML 渲染，
     *     内联 CSS 完整保留（data: URL 走 loadURL 会丢样式——已弃用）；
     *  2. JCEF 不可用 / 资源缺失 / 异常 → BrowserUtil.browse 系统浏览器打开在线文档。
     *
     *  历史演进（详见 docs/开发计划/20260827-1700-...-开发计划.md）：
     *  - data: URL（loadURL）→ CSS 丢失 ✗；
     *  - 在线 URL + 网络探测（直连/IDE 代理）→ 国内网络 JCEF 加载超时，且检测栈复杂 ✗；
     *  - 本地 HTML（loadHTML）→ 样式完整、秒开、零依赖 ✓（定稿）。
     *  样式保证：marketplace-custom-page.html 为纯内联 <style>，无 link/script/img/url()，
     *  不发起任何外部请求，不存在资源加载失败导致缺样式。 */
    internal inner class HelpAction : AnAction() {
        // T-SqlLogToolbarEdt W1：覆写了 update 的动作默认落 EDT → 显式 BGT
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Help
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.help")
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (!com.intellij.ui.jcef.JBCefApp.isSupported()) {
                LOG.info("[HelpAction] JCEF not supported, falling back to system browser")
                browseOnlineDoc()
                return
            }
            openLocalHtml()
        }

        /** 降级：系统浏览器打开在线文档。
         *  单元测试模式必须短路——BrowserUtil.browse 不走沙盒隔离，直接 ShellExecute
         *  拉起宿主机默认浏览器（HelpUiTest 真实触发 actionPerformed，定时回归任务期间
         *  周期性弹出用户 Edge，2026-09-11 真机实证）。 */
        private fun browseOnlineDoc() {
            if (com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) {
                LOG.info("[HelpAction] Unit test mode, skipping system browser fallback")
                return
            }
            com.intellij.ide.BrowserUtil.browse(HELP_DOCUMENTATION_URL)
        }

        /** 内嵌浏览器打开本地 HTML 帮助页。
         *
         * 用 3 参 openEditor（内部走 JCEFHtmlPanel.loadHTML 渲染，内联 CSS 正常；
         * 此前 4 参 data: URL 走 loadURL，JCEF 对 data: URL 的 CSS 渲染会丢失——已弃用）。
         */
        private fun openLocalHtml() {
            var htmlContent = loadText("html", "marketplace-custom-page.html")
            if (htmlContent.isBlank()) {
                LOG.warn("[HelpAction] Help HTML resource is empty, falling back to online doc")
                browseOnlineDoc()
                return
            }
            try {
                // 暗色检测：IDE 主题为暗色时注入 class（JCEF 不读取 IDE 的 prefers-color-scheme）
                if (isDarkTheme()) {
                    htmlContent = htmlContent.replace(
                        "<body>",
                        "<body class=\"dark\">",
                        ignoreCase = true
                    )
                    LOG.info("[HelpAction] Dark theme detected, injected .dark class")
                }
                LOG.info("[HelpAction] Opening local help HTML via loadHTML")
                com.intellij.openapi.fileEditor.impl.HTMLEditorProvider.openEditor(
                    project, HELP_TAB_TITLE, htmlContent,
                )
            } catch (ex: Throwable) {
                LOG.warn("[HelpAction] Failed to open local help HTML, falling back to online doc", ex)
                browseOnlineDoc()
            }
        }

        /** 检测当前 IDE 是否为暗色主题（JBColor.isBright 反映用户设置，不依赖 OS）。 */
        private fun isDarkTheme(): Boolean {
            return !com.intellij.ui.JBColor.isBright()
        }

        /** 从 resources 加载文本文件。 */
        private fun loadText(basePath: String, fileName: String): String {
            return try {
                val stream = HelpAction::class.java.classLoader.getResourceAsStream("$basePath/$fileName")
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Throwable) {
                ""
            }
        }
    }

    // ---------- T-AgentDiagWrap T-04：空态文案的「文本 → 显示」 ----------

    /**
     * 最近一次写入空态的纯文本（`\n` 分隔语义行）。宽度变化时按新宽度重排同一份文本，
     * 所以必须留住它——否则 resize 只能重排「上一次的视觉行」，再窄一档就还原不了。
     */
    internal var lastEmptyText: String? = null

    /** 当前空态是否带「增强捕获」链接（诊断行不带——常规空态专属，见 [applyEmptyTextWithEnhancedLink]）。 */
    internal var emptyTextLinkEnabled: Boolean = false

    // ---------- T-CaptureTruth T-CT-03：零产出诊断 ----------

    /**
     * 诊断刷新定时器（仅「捕捉中且零条目」时运行）。
     *
     * 为什么需要定时器：零产出意味着缓冲不发任何通知，而控制台行数/增强挂载态都在变——不主动拉取
     * 的话诊断行会永远停在「捕捉开始那一刻」的快照上，恰好在用户真正想知道「为什么还是没有」时失语。
     * 2s 与桥轮询同频（[SqlLogConsoleBridge] POLL_INTERVAL_MS）；命中条目或停止捕捉即停表，常态零开销。
     */
    internal var diagnosticsTimer: javax.swing.Timer? = null

    // ---------- 条目列表 ----------

    private fun buildListPane(): JBScrollPane {
        applyEmptyTextWithEnhancedLink()
        // T-AgentDiagWrap T-04：列表宽度变化即重排空态文案——否则用户拉窄/拉宽工具窗后，
        // 文案还按旧宽度折行（窄窗下会重新长出被裁掉的尾巴，等于修复只在首帧生效）。
        list.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                renderEmptyText()
            }
        })
        list.cellRenderer = LogEntryListCellRenderer().also { entryCellRenderer = it }
        applyListCellHeight()
        // T-UiNative T-UN-05（本次修复）：主日志列表构造即可见，速度搜索在此挂载（不移进
        // SqlEntryList.init——历史二级列表复用它却构造于 CardLayout 隐藏卡，构造期挂载不生效）
        ListSpeedSearch.installOn(list) { element -> withTerminator(element.restoredSql) }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                previewHost.onRowClick(e)
                if (javax.swing.SwingUtilities.isLeftMouseButton(e) && e.clickCount >= 2) {
                    previewHost.hideHint()
                    list.selectedValue?.let { openPreview(it) }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })
        val scrollPane = JBScrollPane(list).apply {
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        listScrollPane = scrollPane
        scrollPane.addMouseWheelListener { event ->
            if (event.wheelRotation != 0) tailFollow.onUserScroll()
        }
        scrollPane.verticalScrollBar.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                tailFollow.onUserScroll()
            }
        })
        // T-ListMetaRevert：用户手动滚回底部时恢复自动跟随。
        // 改造前只有「滚动到底部」按钮会调 onScrollToEnd()，用户滚回去后必须再点一下按钮
        // 才能恢复——违反「滚到底就继续跟随」的直觉。
        // T-LogListAnchor 券03：程序动作与内容缩短都不算用户手势，两条判据各挡一类：
        //  - programmaticScrollDepth：贴底/锚点复原这类我们自己发起的滚动；
        //  - 位置单调性：头部淘汰会让内容变矮、滚动条被 clamp 到底（value **变小**），
        //    而用户往底部滚动一定是 value 变大——变小的一律不恢复跟随。
        scrollPane.verticalScrollBar.addAdjustmentListener { e ->
            if (e.valueIsAdjusting) return@addAdjustmentListener
            val bar = e.adjustable as? javax.swing.JScrollBar ?: return@addAdjustmentListener
            val previous = lastScrollBarValue
            lastScrollBarValue = bar.value
            val bottom = isUserScrollToBottom(
                previousValue = previous,
                currentValue = bar.value,
                maxScrollable = bar.maximum - bar.visibleAmount,
                programmatic = programmaticScrollDepth > 0,
            )
            if (bottom) tailFollow.onScrollToEnd()
        }
        val navigationKeys = setOf(KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_PAGE_UP,
            KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_HOME, KeyEvent.VK_END)
        val navigationListener = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode in navigationKeys) tailFollow.onUserScroll()
            }
        }
        list.addKeyListener(navigationListener)
        scrollPane.verticalScrollBar.addKeyListener(navigationListener)
        return scrollPane
    }

    /**
     * 行高随显示模式分流（T-ListMode × T-LogListFreeze-04 权衡）：
     * SINGLE_LINE 固定行高——WideSelectionListUI 短路逐条测量走 O(1) 快路径
     * （原可变行高 × HTML 排版 = 16s EDT Freeze；片段通道后固定行高仍是流式灌入的最优路径）；
     * KEYWORD_WRAP/FORMATTED 可变行高——条目行数不等，固定高会裁行；多行片段有条目级缓存，
     * 逐条测量只剩轻量组件布局（无 HTML 解析，冻结量级不复现；真机大日志表现见计划文档）。
     * 全文查看由单击预览/双击弹窗承接。
     */
    internal fun applyListCellHeight() {
        list.fixedCellHeight = if (PluginSettings.getInstance().sqlListDisplayMode() == SqlListDisplayMode.SINGLE_LINE) {
            measureFixedCellHeight()
        } else {
            -1
        }
    }

    /** T-LogListFreeze-04：固定行高实测——样例条目渲染一次取首选高（两行文本 + 5,8 空边框 + 1px 分隔线）。 */
    private fun measureFixedCellHeight(): Int {
        val sample = LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList())
        val c = entryCellRenderer.getListCellRendererComponent(list, sample, 0, false, false)
        val measured = c.preferredSize.height
        return if (measured > 0) measured else list.getFontMetrics(list.font).height * 2 + 13
    }

    /** T-SqlLogPerf P5：在途全量重建链的剩余游标（null = 无在途链）。 */
    internal var rebuildRemaining: Iterator<LogSqlEntry>? = null

    /** F80：链后补跑挂起标志（超大余量让路本拍刷新，链排空即补跑一次）。 */
    internal var pendingRefreshAfterChain = false

    // ---------- 渲染辅助（F-44-2：片段通道恢复 SQL 高亮 + 耗时） ----------
    // 注：formatTimestampFragment / formatDurationFragment / formatSourceFragment 为文件级 internal 函数（见本文件末尾），
    // 供 LogEntryListCellRenderer 与 SqlLogPanel 共用。

    /**
     * F-44-6 / T-JumpCapture：实时日志跳转的统一解析结果。
     * 跳转完全依赖 [SqlSourceInterceptor] 捕获的源码位置：
     * 有位置 → 精确 file:line（[Precise]）或文件已失效（[NotFound]）；
     * MP 分页 COUNT 标记 → [NoSource]（无用户调用点，纯提示）；
     * 无位置 → [MissingSource]（引导开启源码捕获，[SourceCaptureGuide]）。
     *
     * 原「按表名搜索 Wrapper」回退已整体移除：同表名 ≠ 同调用点，命中即误导。
     */
    internal sealed class LogJumpTarget {
        data class Precise(val virtualFile: VirtualFile, val line: Int) : LogJumpTarget()
        data object NoSource : LogJumpTarget()
        data class NotFound(val sourceFile: String) : LogJumpTarget()
        data object MissingSource : LogJumpTarget()
        /** T-M55-01：mapper XML 语句节点（mapperStatement 定位成功才有意义，失败回退 Java 不劣化）。 */
        data class MapperXml(val virtualFile: VirtualFile, val line: Int) : LogJumpTarget()
    }

    /**
     * 统一解析跳转目标：仅依赖捕获位置，不做任何启发式定位。
     * T-SourceJumpFunnel P0-5：判定逻辑收敛到 [EntryJumpResolver]（实时/历史两面板共用同一实现，
     * 历史面板由此获得候选回退能力）；T-RecentExecMenu O-119：转接原语收口到 [LogEntryJumpOps]
     * （实时/历史/弹框三方共用），本函数只做类型转接（类型保持不动——既有测试按其断言分支）。
     */
    internal fun resolveEntryJump(entry: LogSqlEntry): LogJumpTarget =
        when (val target = LogEntryJumpOps.resolveSource(project, entry)) {
            is LogEntryJumpOps.SourceTarget.Precise -> LogJumpTarget.Precise(target.virtualFile, target.line)
            LogEntryJumpOps.SourceTarget.NoSource -> LogJumpTarget.NoSource
            is LogEntryJumpOps.SourceTarget.NotFound -> LogJumpTarget.NotFound(target.sourceFile)
            LogEntryJumpOps.SourceTarget.MissingSource -> LogJumpTarget.MissingSource
        }

    /**
     * T-M55-01：XML 目标解析——mapperStatement 非空且 [MapperXmlLocator] 定位成功时返回
     * [LogJumpTarget.MapperXml]；statementId 缺失 / MP 内置语句 XML 不存在 / 多模块定位失败
     * 一律返回 null（调用方回退 Java 跳转，不劣化）。定位自带 ReadAction，EDT 直调安全。
     * T-RecentExecMenu O-119：解析原语收口 [LogEntryJumpOps.resolveXml]。
     */
    internal fun resolveEntryXmlJump(entry: LogSqlEntry): LogJumpTarget.MapperXml? =
        LogEntryJumpOps.resolveXml(project, entry)
            ?.let { LogJumpTarget.MapperXml(it.virtualFile, it.line) }

    /** 等价右键「跳转到源码」（供 UI 测试断言精确跳转分支，避 headless 弹窗）。 */
    internal fun jumpToSourceForTest(entry: LogSqlEntry) = jumpToSource(entry)

    /**
     * F-44-6：日志 ↔ 源码关联跳转。
     * 仅依赖捕获位置精确跳转；无位置时弹框引导开启源码捕获（T-JumpCapture）。
     * T-RecentExecMenu O-119：**整体策略**收口 [LogEntryJumpOps.jumpToSourceRealtime]
     * （与弹框右键共用同一实现与文案，避免第四份策略复制）。
     */
    private fun jumpToSource(entry: LogSqlEntry) {
        LogEntryJumpOps.jumpToSourceRealtime(project, entry)
    }

    /**
     * T-UiNative T-UN-02：跳转无源码提示改轻通知（原模态弹窗——纯告知无需打断）。
     * T-RecentExecMenu O-119：实现收口 [LogEntryJumpOps.showToast]（标题键留本页口径）；
     * 见 [FocusCallerMethodAction] 同用本薄封装。
     */
    private fun showJumpToast(content: String) {
        LogEntryJumpOps.showToast(project, "log.panel.jumpToSource", content)
    }

    /** 更新工具栏慢查询计数徽章（O-1 Phase C）。 */
    internal fun updateSlowQueryLabel() {
        val count = slowQueryCounter.slowCount
        slowQueryLabel?.let {
            if (count > 0) {
                // T-UiCoord F2：全文「慢查询: N」（约 65px）在一行定构下吃掉余量 → 改短徽章
                // 「慢 N」（复用存量键 log.panel.slowQueryBadge），全文进 tooltip 不丢信息
                it.text = MpSqlVisualizerBundle.message("log.panel.slowQueryBadge") + " " + count
                it.toolTipText = MpSqlVisualizerBundle.message("log.toolbar.slowQueryCount", count.toString())
                it.isVisible = true
            } else {
                it.isVisible = false
            }
        }
    }

    private fun openPreview(entry: LogSqlEntry) {
        // T-M31.14：设置开启时预览展示格式化版（多行缩进），否则原样
        val state = PluginSettings.getInstance().state
        // T-HistParam F3：识别通道统一——与历史 tab 共用 toPreviewResult
        // （占位符识别 / 字面量参数恢复 F4 / 回退警告分流，行为与原 openPreview 双分支逐字段一致）
        SqlPreviewService.showResult(
            project,
            entry.toPreviewResult(
                formatSql = state.logFormatSql,
                literalRecovery = state.literalParamRecovery,
                // T-DialectInferFix：把「这条 SQL 当时连的库」交给预览，避免回落 MySQL 造成假红
                capturedDialect = entry.dialectName,
            ),
        )
    }

    /** R4（W3 历史工作流包）：导出当前可见行集（lastRendered=面板过滤/搜索/排序后的实际可见集）。 */
    internal inner class ExportVisibleAction : AnAction(
        MpSqlVisualizerBundle.message("log.export.visible"),
        null,
        MpvsIcons.Export,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            exportVisibleEntries()
        }
    }

    /** R4 导出：格式四选（CSV/TSV/TXT/SQL，默认 SQL）→ 文件保存 → 后台写盘 → 通知（行集=lastRendered 快照）。 */
    internal inner class CopySqlAction(private val entries: List<LogSqlEntry>) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = copyMenuText("log.panel.copy", "log.panel.copySelected", entries.size)
        }

        override fun actionPerformed(e: AnActionEvent) {
            UiTextClipboard.copy(batchCopySql(entries))
        }
    }

    /** 复制时间戳：显示开关关闭时无片段可复制 → update 置灰。 */
    internal inner class CopyTimestampAction(private val entries: List<LogSqlEntry>, private val clip: String) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = copyMenuText("log.panel.copyTimestamp", "log.panel.copyTimestampSelected", entries.size)
            e.presentation.isEnabled = clip.isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (clip.isNotEmpty()) {
                UiTextClipboard.copy(clip)
            }
        }
    }

    /** 删除被右键的条目。 */
    internal inner class DeleteSqlAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.delete")
        }

        override fun actionPerformed(e: AnActionEvent) {
            manager.entries.removeEntry(entry)
        }
    }

    /**
     * T-SqlSuppress（O-69）：屏蔽选中的 SQL。多选支持——去重后的**不同指纹**各成一条规则
     * （空白变体同指纹只建一条），已存在跳过（幂等）；达上限拒新增并提示（D6）。
     * 动作链：规则入库（监听器同步更新缓冲键集）→ refreshList（存量心跳行立即消失，D4 最强反馈）
     * → balloon 反馈附「管理屏蔽规则…」直达对话框（D4 中层）。
     */
    internal inner class SuppressSqlAction(private val entries: List<LogSqlEntry>) : AnAction() {
        override fun update(e: AnActionEvent) {
            val count = entries.distinctBy { it.dedupeKey }.size
            e.presentation.text = if (count > 1) {
                MpSqlVisualizerBundle.message("log.suppress.actionSelected", count.toString())
            } else {
                MpSqlVisualizerBundle.message("log.suppress.action")
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val ruleManager = SqlSuppressRuleManager.getInstance()
            val (added, limitReached) = ruleManager.addRules(entries.distinctBy { it.dedupeKey }.map { it.restoredSql }) // F21：单次 republish
            refreshList() // addRule 的监听器已同步更新缓冲键集——存量行立即消失
            if (added > 0) showSuppressBalloon(added)
            if (limitReached) showSuppressLimitBalloon()
        }
    }

    /**
     * T-SqlSuppress：屏蔽成功 balloon（D5 定案独立通知组 logsuppress）。
     * 附「管理屏蔽规则…」动作直达对话框；测试模式跳过（对齐 [showJumpToast] 约定）。
     */
    internal inner class SendToSqlToJavaAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.sendToReverse")
        }

        override fun actionPerformed(e: AnActionEvent) {
            openEntryInTab(entry, LogEntryTab.SQL_TO_JAVA)
        }
    }

    /** 发送到日志转 SQL（第二 tab）。T-RecentExecMenu O-119：落点收口 [openEntryInTab]（弹框右键同用）。 */
    internal inner class SendToLogToSqlAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.sendTo")
        }

        override fun actionPerformed(e: AnActionEvent) {
            openEntryInTab(entry, LogEntryTab.LOG_TO_SQL)
        }
    }

    /** 跳转到 Java 调用方（T-M55-01：与「跳转到 XML」拆分为双目标；无位置→引导捕获弹窗，无提示/文件丢失→toast）。 */
    internal inner class JumpToSourceAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.jumpToJava")
        }

        override fun actionPerformed(e: AnActionEvent) {
            jumpToSource(entry)
        }
    }

    /**
     * T-M55-01：跳转到 mapper XML 语句节点。mapperStatement 非空且定位成功才显示
     * （update 内解析一次并存引用，actionPerformed 复用，单次右键只做一次索引查询）；
     * 定位失败（XML 不存在/多模块未命中）不显示，原有「跳转到 Java」承担回退，不劣化。
     */
    internal inner class JumpToMapperXmlAction(private val entry: LogSqlEntry) : AnAction() {
        private var resolved: LogJumpTarget.MapperXml? = null

        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.jumpToXml")
            resolved = resolveEntryXmlJump(entry)
            e.presentation.isEnabledAndVisible = resolved != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            // update 已解析成功才可见可点；防御再解析一次（menu 会话内基本命中缓存外的同结果）
            val target = resolved ?: resolveEntryXmlJump(entry) ?: return
            OpenFileDescriptor(project, target.virtualFile, target.line, 0).navigate(true)
        }
    }

    /**
     * T-M55-03：聚焦调用方方法（静态行号区间近似版）——sourceFile 非空时可用；
     * 点击时解析方法行号区间并加入聚焦页签栏（解析失败 toast，不静默）。
     * Ultra 的编辑器行槽 ⚡ 入口首批不做（记录为可选增强）。
     */
    internal inner class FocusCallerMethodAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.focusCallerMethod")
            // MP 分页 COUNT 标记无用户调用点；其余 sourceFile 非空即可用（解析成败在 action 时点判定）
            e.presentation.isEnabled = entry.sourceFile != null &&
                !SourceMarkers.isMpPaginationCount(entry.sourceFile)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val tab = resolveCallerMethodFocus(entry)
            if (tab == null) {
                showJumpToast(MpSqlVisualizerBundle.message("log.panel.focusCallerMethod.failed"))
                return
            }
            addFocusTab(tab)
        }
    }

    /**
     * T-M55-04：按请求聚焦——requestTag 非空的条目右键即产生请求页签（同一次请求的多条 SQL
     * 共享同值，页签内呈现扁平时间序）。页签 K 有界（[addRequestTab] 超限挤掉最早请求页签，
     * 防长压测会话 requestTag 高基数爆页签栏）；无请求标签条目在请求聚焦下隐藏 + 计数提示
     * （与 03 方法聚焦同策略）。「最近 K 个请求标签下拉」的独立入口不另做——右键任意
     * 同标签条目等价可达，计划内「或」二选一取轻。
     */
    internal inner class FocusRequestAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.focusRequest")
            e.presentation.isEnabled = !entry.requestTag.isNullOrBlank()
        }

        override fun actionPerformed(e: AnActionEvent) {
            entry.requestTag?.takeIf { it.isNotBlank() }?.let { addFocusTab(FocusTab.Request(it)) }
        }
    }

    /**
     * O-90：按结构聚焦——右键带「同结构 N 条」徽章的条目产生结构页签（structureKey 精确匹配，
     * 过滤出该结构全部条目；与 04 请求聚焦同一交互形态：条目驱动的聚焦过滤器，页签可关闭恢复）。
     * 准入 = 计数达 [TEMPLATE_BADGE_MIN_COUNT]（徽章在场才可点）；同结构重复创建幂等
     * （[addTemplateTab] 按 structureKey 判重）。徽章本体为展示性（与去重徽章先例一致——
     * 列表片段非交互组件），聚焦入口落在本动作；T-DevAcceptFix2 ⑪ 起菜单项带 description
     * 说明判定口径（「字面量不同也算」——用户实测里 `IN ('a')` 与 `IN ('b')` 计同一结构）。
     */
    internal inner class FocusTemplateAction(private val entry: LogSqlEntry) : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.text = MpSqlVisualizerBundle.message("log.panel.focusTemplate")
            e.presentation.description = MpSqlVisualizerBundle.message("log.panel.focusTemplate.desc")
            e.presentation.isEnabled = (templateCounts[entry.structureKey] ?: 0) >= TEMPLATE_BADGE_MIN_COUNT
        }

        override fun actionPerformed(e: AnActionEvent) {
            val n = templateCounts[entry.structureKey] ?: return
            if (n >= TEMPLATE_BADGE_MIN_COUNT) addFocusTab(FocusTab.Template(entry.structureKey, n))
        }
    }

    /** 面板销毁：释放单击预览弹窗与日志转 SQL / SQL→Java 面板编辑器（manager 生命周期由项目 @Service 管理）。 */
    override fun dispose() {
        // T-Perf-24：停止搜索防抖 Timer（防晚到 apply 触碰已销毁面板）
        searchDebounceTimer.stop()
        // F80：撤链后补跑挂起标志（防 dispose 后晚到 invokeLater 触碰已销毁面板）
        pendingRefreshAfterChain = false
        // T-CaptureTruth T-CT-03：停止诊断定时器（同上，防晚到 syncDiagnostics 触碰已销毁列表）
        diagnosticsTimer?.stop()
        diagnosticsTimer = null
        // T-Perf-15：注销 manager 监听器（防面板销毁后仍被回调——每条新 SQL invokeLater 调已销毁面板）
        manager.removeUiListener(uiRefreshListener)
        manager.removeCapturingListener(capturingUiListener)
        // T-RecentExecMenu O-119：注销送页签通道（同一性判定——防误清他人注册）
        if (manager.onOpenEntryInTab === openEntryInTabCallback) manager.onOpenEntryInTab = null
        previewHost.hideHint()
        dockedPreview.dispose()
        logToSqlPanel.dispose()
        sqlToJavaPanel.dispose()
    }

    /**
     * T-RecentExecMenu O-119：面板外请求（弹框右键「发送到…」）把条目送进对应页签并选中——
     * 与右键 [SendToSqlToJavaAction] / [SendToLogToSqlAction] 同体（三处共用本方法，单一实现）。
     */
    internal fun openEntryInTab(entry: LogSqlEntry, tab: LogEntryTab) {
        when (tab) {
            LogEntryTab.LOG_TO_SQL -> {
                logToSqlPanel.loadFromEntry(entry)
                logToSqlTab?.let { tabs.select(it, true) }
            }
            LogEntryTab.SQL_TO_JAVA -> {
                sqlToJavaPanel.setInputSql(entry.restoredSql)
                sqlToJavaTab?.let { tabs.select(it, true) }
            }
        }
    }

    // ---------- WrapLayout（工具栏行自动换行） ----------

    /**
     * WrapLayout：FlowLayout 的换行变体——空间不足时组件自动折到下一行，且 preferred/minimum
     * 尺寸按多行计算（原生 FlowLayout.preferredLayoutSize 只按单行算，折行后第二行会被裁掉，
     * 表现为「控件消失」——2026-08-27 用户实测 SQL类型按钮选中两个类型后触发的缺陷）。
     */
    internal class WrapLayout : FlowLayout(LEFT, 4, 2) {

        override fun preferredLayoutSize(target: Container): Dimension {
            return computeSize(target, preferred = true)
        }

        override fun minimumLayoutSize(target: Container): Dimension {
            return computeSize(target, preferred = false)
        }

        private fun computeSize(target: Container, preferred: Boolean): Dimension {
            synchronized(target.treeLock) {
                val insets = target.getInsets()
                val availWidth = (target.parent?.width ?: Int.MAX_VALUE) - insets.left - insets.right
                var x = 0; var y = insets.top + vgap; var rowHeight = 0; var maxRowWidth = 0
                for (i in 0 until target.componentCount) {
                    val m = target.getComponent(i)
                    if (!m.isVisible) continue
                    val d = if (preferred) m.preferredSize else m.minimumSize
                    if (x + d.width > availWidth && x > 0) {
                        maxRowWidth = maxOf(maxRowWidth, x - hgap)
                        x = 0; y += rowHeight + vgap; rowHeight = 0
                    }
                    x += d.width + hgap; rowHeight = maxOf(rowHeight, d.height)
                }
                maxRowWidth = maxOf(maxRowWidth, x - hgap)
                return Dimension(maxRowWidth + insets.left + insets.right, y + rowHeight + insets.bottom)
            }
        }

        override fun layoutContainer(target: Container) {
            synchronized(target.treeLock) {
                val insets: Insets = target.getInsets()
                val availWidth: Int = target.width - insets.left - insets.right
                var x: Int = insets.left; var y: Int = insets.top; var rowHeight = 0
                for (i in 0 until target.componentCount) {
                    val m = target.getComponent(i)
                    if (!m.isVisible) continue
                    val d: Dimension = m.preferredSize
                    val rightEdge: Int = x + d.width
                    val maxRight: Int = insets.left + availWidth
                    if (rightEdge > maxRight && x > insets.left) {
                        x = insets.left; y += rowHeight + vgap; rowHeight = 0
                    }
                    m.setBounds(x, y, d.width, d.height)
                    x += d.width + hgap; rowHeight = maxOf(rowHeight, d.height)
                }
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SqlLogPanel::class.java)
        private const val TOOLBAR_ID = "cn.song.mybatisplus.visualizer.log.toolbar"
        /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
        internal const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"
        /** T-SqlSuppress（O-69/D5）：屏蔽规则 balloon 通知组（plugin.xml 独立注册）。 */
        internal const val SUPPRESS_NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.logsuppress"
        /** T-Perf-24（自 dev 移植）：搜索输入防抖窗口（毫秒）。 */
        private const val SEARCH_DEBOUNCE_MS = 200

        /**
         * T-UiCoord F2：两个下拉按钮（SQL类型 / 排序）显示文本的像素上限（@100% 缩放，用 JBUI.scale 取用）。
         *
         * 定宽封顶是「工具栏恒为单行」的前提之一：`SQL类型` 多选后文案会到「查询, 更新, 删除, 插入」，
         * 不封顶时行宽随选择漂移（旧流式行正是这么折到第二行的）。封顶后按钮宽度只由预算决定，
         * 超限截尾省略、tooltip 恒全文（见 [clipToDropdownBudget]）。
         *
         * **取值依据（2026-09-21 headless 实测，非估算）**：每个下拉按钮的固定开销 = 图标 16 + LaF 内边距 38
         * ≈ 54px（与文本无关），勾选固定 102px。用户侧边栏 460px、图标组真机量测约 133px 下：
         * 默认态（「全部」）控制组 ≈300 → 合计 433（余 27px）；多选态封顶后控制组 ≈333 → 合计 466。
         * 预算取 64（不是初版计划的 78）：78 时多选态控制组 346，超 `460 − 图标组保守下限 120 = 340`
         * 的预算锁。多选态余下约 6px 的超出量（相对图标组量测中值）由平台图标溢出兜底，
         * 属登记边界而非缺陷——见计划 §七 待验证前提 2。
         */
        internal const val DROPDOWN_TEXT_BUDGET_PX = 64
        /** T-CaptureTruth T-CT-03：零产出诊断刷新间隔（与桥轮询同频 2s）。 */
        internal const val DIAGNOSTICS_TICK_MS = 2000
        /** R53：双通道状态栏两段文案之间的语义分隔符。 */
        private const val SEP_LABEL = "｜"
        // 「已滚到底」容差常量随判据一起搬到 TailFollowState.kt（T-LogListAnchor 券03：
        // 判据抽成纯函数 isUserScrollToBottom，容差与判据同处才不会被两处各写一份）。
        internal const val DEFAULT_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
        /** T-M39：帮助文档 URL（降级系统浏览器打开）。
         *  2026-08-31 改为自建站点 jvtools.cn（可自主接入国内 CDN；
         *  当前实测：直连不通，需走系统/IDE 代理才可达，与 plugins.jetbrains.com 同）。 */
        // 2026-10-12：改用 canonical 域名 + 无后缀路径。旧写法
        // `mybatis-plus-sql-visualizer.jvtools.cn/features/wrapper-preview.html` 实测要走
        // 两次跳转（旧域名 301 → 新域名，`.html` 308 → 无后缀），canonical 地址直接 200。
        private const val HELP_DOCUMENTATION_URL =
            "https://sql-visualizer.jvtools.cn/features/wrapper-preview"
        /** T-M39：帮助标签页标题。 */
        private const val HELP_TAB_TITLE = "SQL Visualizer - Help"
    }
}

// =================== 文件级：列表渲染辅助（internal 渲染器与 SqlLogPanel 共用） ===================

