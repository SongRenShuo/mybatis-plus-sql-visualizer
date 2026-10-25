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

    private val listModel = DefaultListModel<LogSqlEntry>()
    internal val list = ViewportWidthSqlEntryList(listModel)
    /** 列表条目单元格渲染器（internal 供 UI 测试直接渲染、断言 HTML 输出）。 */
    private lateinit var entryCellRenderer: LogEntryListCellRenderer
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
    private val activeFilter = FILTERABLE_TYPES.toMutableSet()

    /**
     * 「只看慢查询」筛选（用户 2026-10-14 补）：true 时只保留耗时 ≥ 当前慢查询阈值的条目。
     * 判定走 [durationOf]（结构化字段优先、缺失回退日志文本解析），与「慢查询徽章」同口径。
     * 性能：一次 O(n) 遍历，位于刷新管线（非渲染热路径）。
     */
    private var slowOnly = false

    /** 「只看慢查询」勾选框（弹窗首次构建后非 null；仅用于状态回填，测试另有 [setSlowOnlyForTest]）。 */
    private var slowOnlyCheckBox: JCheckBox? = null

    /** 排序模式（T-LogSortModes：默认时间升序 = 捕获顺序）。 */
    private var sortMode = SortMode.TIME_ASC

    /** 当前搜索词（搜索条实时同步；空串 = 不过滤）。 */
    private var searchText = ""

    /**
     * M56 T-M56-00：动态聚焦过滤状态（基础设施；null = 不过滤）。
     * T-M56-02/06/07 起 agent 通道接管后由页签/请求聚合驱动写入；桥通道恒 null 与现状逐字一致。
     */
    @Volatile
    private var activeFocus: FocusFilter? = null

    /** 去重丢弃计数快照（refreshList 后更新；cellRenderer 渲染 ⨉N 徽章用）。 */
    private var dedupeCounts: Map<String, Int> = emptyMap()

    /** O-90：同模板计数快照（refreshList 后更新；cellRenderer 徽章与右键聚焦动作共用）。 */
    private var templateCounts: Map<String, Int> = emptyMap()

    // ---------- T-Perf-03（自 dev 移植）：增量刷新状态 ----------

    /** 刷新代际：新 refreshList 启动时 +1，旧分批追加链检测到代际过期即终止。 */
    private var refreshGeneration = 0L

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
    private val slowQueryCounter = SlowQueryCounter(
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
    private val typeFilterButton = JButton().apply {
        isFocusable = false
        icon = MpvsIcons.TypeFilter
        horizontalTextPosition = SwingConstants.LEFT // 图标在文字右侧（下拉按钮惯例）
        addActionListener { showTypeFilterPopup() }
    }

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

    private val tailFollow = TailFollowState()
    private var listScrollPane: JBScrollPane? = null
    internal val tailFollowModeForTest: TailFollowMode get() = tailFollow.mode

    private fun scrollToBottom(resume: Boolean) {
        if (resume) tailFollow.onScrollToEnd()
        if (listModel.isEmpty) return
        list.ensureIndexIsVisible(listModel.size() - 1)
        listScrollPane?.verticalScrollBar?.let { it.value = it.maximum }
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
    private val logMatchCase: Boolean get() = nativeSearch.isMatchCase()

    /** 当前正则态（原生钮状态落点；`regex:` 前缀独立生效）。 */
    private val logRegex: Boolean get() = nativeSearch.isRegex()

    /** 正则编译 memo（防抖 200ms 反复 apply 复用编译结果，键=源串+matchCase 旗）。 */
    private val regexMemo = HistoryRegexMemo()

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
    private val focusTabs = mutableListOf<FocusTab>()

    /** 聚焦页签栏（工具栏行与搜索条之间；无页签时隐藏不占高度）。 */
    private val focusTabBar = JPanel(WrapLayout()).apply { isOpaque = false; isVisible = false }

    /** 聚焦隐藏计数提示（聚焦视图下不匹配任何页签的条目数；无页签时隐藏）。 */
    private val focusHiddenLabel = JLabel().apply {
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
    private var toolbarComponent: JComponent? = null

    // ---------- R53：双通道捕获状态栏（字段须在 init 之前——init 里 buildStatusBar 会赋值） ----------

    /** 状态栏：控制台通道段（已挂载 N 个 · 已扫描 M 行）。 */
    private var statusConsoleLabel: JLabel? = null

    /** 状态栏：增强 agent 通道段（未开启 / 暂停中 / 已挂载 pid · 已收事件 N / 等待…）。 */
    private var statusAgentLabel: JLabel? = null

    /**
     * 状态栏宿主（非捕捉态整体隐藏——只清两段文案会留下分隔符与一条空白高度；
     * BorderLayout 跳过 isVisible=false 的子件，故置位后需 revalidate）。
     */
    private var statusBarPanel: JPanel? = null

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

    /** 测试驱动：立即应用搜索框文本（headless 下防抖 Swing Timer 到点不可控），等价生产防抖回调。 */
    internal fun applySearchTextForTest() = applySearchText()

    // ---------- SQL类型多选弹窗 ----------

    /**
     * 更新 SQL类型按钮文案（全部 / 无 / 类型组合）。
     *
     * T-UiCoord F2：标签控件退役后语义由 tooltip 承载——显示文本按预算截尾省略（多选组合可能很长），
     * tooltip 恒为「SQL类型：」+ 完整状态。
     */
    private fun updateTypeFilterButton() {
        val state = when {
            activeFilter.containsAll(FILTERABLE_TYPES) -> MpSqlVisualizerBundle.message("log.filter.all")
            activeFilter.isEmpty() -> MpSqlVisualizerBundle.message("log.filter.type.none")
            else -> FILTERABLE_TYPES.filter { it in activeFilter }.joinToString(", ") { labelForType(it) }
        }
        typeFilterButton.text = clipToDropdownBudget(state, typeFilterButton)
        typeFilterButton.toolTipText = MpSqlVisualizerBundle.message("log.filter.type.label") + state
    }

    /**
     * T-UiCoord F2：下拉按钮显示文本按预算截尾省略（复用 meta 行的 [clipTailToWidth] 纯函数）。
     * 预算用 JBUI 缩放表达——文本宽度随缩放变化，写死像素会在高 DPI 下失效。
     * tooltip 恒全文，省略只发生在按钮显示层。
     */
    private fun clipToDropdownBudget(text: String, target: JComponent): String {
        val metrics = target.getFontMetrics(target.font)
        return clipTailToWidth(text, { metrics.stringWidth(it) }, JBUI.scale(DROPDOWN_TEXT_BUDGET_PX))
    }

    /** 当前打开的类型弹窗内勾选框（全选/全不选即时同步用；弹窗每次打开重建）。 */
    private var typePopupCheckBoxes: List<Pair<StatementType, JCheckBox>> = emptyList()

    /** T-SelToggle：弹窗底部全选/全不选切换按钮（T-SelToggle 双按钮合一；弹窗每次打开重建，与 [typePopupCheckBoxes] 同生命周期约定）。 */
    private var typePopupToggleButton: JButton? = null

    /**
     * T-SelToggle：弹窗切换按钮文案——activeFilter 全勾 → 「全不选」（点击清空），
     * 其余态（含空集）→ 「全选」（点击填满）。判定与外层「全部」文案（updateTypeFilterButton）同口径。
     */
    private fun typePopupToggleLabel(): String = MpSqlVisualizerBundle.message(
        if (activeFilter.containsAll(FILTERABLE_TYPES)) "log.filter.selectNone" else "log.filter.selectAll",
    )

    /**
     * T-SelToggle：弹窗切换按钮文案随勾选态刷新。弹窗无公共渲染下游可收口
     * （updateTypeFilterButton/refreshList 还被搜索等非弹窗链路调用），三个勾选态变更入口
     * （弹窗构建末尾、applyAllTypes 末尾、单类型复选框 listener）显式调用本函数。
     */
    private fun syncTypePopupToggleLabel() {
        typePopupToggleButton?.let {
            val label = typePopupToggleLabel()
            if (it.text != label) it.text = label
        }
    }

    /**
     * 弹出类型多选弹窗：勾选即时生效且弹窗不关闭（JCheckBox 非 menu item，
     * 点击不触发 popup 收起，支持连续勾选组合）；底部单个全选/全不选切换按钮（T-SelToggle）
     * 一键设置——同步刷新弹窗内勾选框选中态 + 按钮文案 + 列表（弹窗保持打开，立即可见）。
     */
    private fun showTypeFilterPopup() {
        // T-UiNative P1a：JPopupMenu 外壳 → 平台 JBPopup（createComponentPopupBuilder 承载 JCheckBox 面板；
        // 触发源是工具栏按钮 ActionListener 无 MouseEvent，锚点沿用原 show 坐标——按钮正下方 2px）
        val contentPanel = buildTypeFilterContent()
        JBPopupFactory.getInstance().createComponentPopupBuilder(contentPanel, contentPanel).apply {
            setRequestFocus(false)
            setCancelOnClickOutside(true)
        }.createPopup().show(RelativePoint(typeFilterButton, Point(0, typeFilterButton.height + 2)))
    }

    /**
     * T-SelToggle：构建弹窗内容（勾选框区 + 分隔线 + 底部切换按钮），赋值 [typePopupCheckBoxes]
     * 与 [typePopupToggleButton]。与 [showTypeFilterPopup] 拆分是纯提取（行为零变更），
     * 使测试可脱离 `popup.show` 直接驱动勾选框/切换按钮（headless 下弹层 show 不可靠）。
     */
    private fun buildTypeFilterContent(): JPanel {
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        content.border = EmptyBorder(8, 12, 8, 12)
        val checkBoxes = mutableListOf<Pair<StatementType, JCheckBox>>()
        FILTERABLE_TYPES.forEach { type ->
            val cb = JCheckBox(labelForType(type), type in activeFilter).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                isFocusable = false
                addActionListener {
                    if (isSelected) activeFilter.add(type) else activeFilter.remove(type)
                    updateTypeFilterButton()
                    refreshList()
                    // T-SelToggle：单类型勾选即时同步切换按钮文案
                    syncTypePopupToggleLabel()
                }
            }
            checkBoxes += type to cb
            // T-CrudColor：复选框前置色块图例——否则用户得去设置页才能把颜色和类型对上号。
            // 样式为 off（或该类型无标记色）时不加色块，避免出现「有图例却无标记」的错位。
            // 包一层行面板保持 checkBoxes 结构与 applyAllTypes 语义不变。
            val tint = SqlListTypeColor.colorOf(type)
            content.add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, cb.preferredSize.height + 2)
                if (tint != null) add(JLabel(colorSwatchIcon(tint)))
                add(cb)
            })
        }
        typePopupCheckBoxes = checkBoxes
        content.add(Box.createVerticalStrut(6))
        content.add(JSeparator().apply { alignmentX = Component.LEFT_ALIGNMENT })
        content.add(Box.createVerticalStrut(6))
        // 用户 2026-10-14 补：与「慢查询徽章」呼应的快捷筛选——只看慢查询（按当前慢查询阈值判定）。
        // 放在类型弹窗内、与类型勾选同一入口，避免往右侧控制组加控件（控制组有最坏宽预算锁）。
        val slowOnlyBox = JCheckBox(MpSqlVisualizerBundle.message("log.filter.slowOnly"), slowOnly).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            isFocusable = false
            toolTipText = MpSqlVisualizerBundle.message("log.filter.slowOnly.tip")
            addActionListener {
                slowOnly = isSelected
                refreshList()
            }
        }
        slowOnlyCheckBox = slowOnlyBox
        content.add(slowOnlyBox)
        content.add(Box.createVerticalStrut(6))
        val toggleButton = JButton(typePopupToggleLabel()).apply {
            isFocusable = false
            addActionListener { applyAllTypes(!activeFilter.containsAll(FILTERABLE_TYPES)) }
        }
        typePopupToggleButton = toggleButton
        val row = JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(toggleButton)
        }
        content.add(row)
        return content
    }

    /**
     * T-CrudColor：类型色块图例图标（12×12 实心方块）。
     * 用于类型过滤弹窗——纯色块、无边框，与列表中的标记同色，起对照作用。
     */
    private fun colorSwatchIcon(color: Color): Icon = object : Icon {
        override fun getIconWidth(): Int = 12
        override fun getIconHeight(): Int = 12
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            g.color = color
            g.fillRect(x, y, iconWidth, iconHeight)
        }
    }

    /** 全选/全不选（全选 = 「全部」语义，列表透传含 OTHER）：同步弹窗内勾选框 + 按钮文案 + 列表。 */
    private fun applyAllTypes(selected: Boolean) {
        activeFilter.clear()
        if (selected) activeFilter.addAll(FILTERABLE_TYPES)
        typePopupCheckBoxes.forEach { (type, cb) -> cb.isSelected = type in activeFilter }
        updateTypeFilterButton()
        refreshList()
        // T-SelToggle：切换按钮点击后文案翻转（此时 activeFilter 已是新态）
        syncTypePopupToggleLabel()
    }

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
    private var _toolbarRef: ActionToolbar? = null

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
    private var lastEmptyText: String? = null

    /** 当前空态是否带「增强捕获」链接（诊断行不带——常规空态专属，见 [applyEmptyTextWithEnhancedLink]）。 */
    private var emptyTextLinkEnabled: Boolean = false

    /** 写入空态文案（纯文本入口，渲染层在此做折行）。 */
    private fun applyEmptyText(plain: String) {
        lastEmptyText = plain
        emptyTextLinkEnabled = false
        renderEmptyText()
    }

    /**
     * 写入空态文案 + 尾部可点「增强捕获」链接（20261008 验收缺陷 #3：第三径原为纯文本不可点，
     * 与验收文档 3.22「点该引导走到增强捕获的开启流程」不符）。仅常规空态用；诊断行不加链接。
     */
    private fun applyEmptyTextWithEnhancedLink() {
        lastEmptyText = MpSqlVisualizerBundle.message("log.panel.empty")
        emptyTextLinkEnabled = true
        renderEmptyText()
    }

    /** 按当前列表可用宽度重排并逐视觉行写入（EDT）。 */
    private fun renderEmptyText() {
        val plain = lastEmptyText ?: return
        val status = list.emptyText
        status.clear()
        applyEmptyTextLines(status, plain, emptyTextWidthPx()) { s -> list.getFontMetrics(list.font).stringWidth(s) }
        if (emptyTextLinkEnabled) {
            // T-SettingsFocusFromLink：引导链接（**单一整体入口**——用户定「一个整体，点击都跳到目标位置」：
            // 不再拆页签/设置页/增强捕获三个去向的分链），点击打开主设置页并定位高亮「增强捕获」设置行
            // （空态文案里「参数实参与精确耗时…在设置页开启 →」所指）。经 appendLinkRow 单点——
            // 平台 appendSecondaryText 固定写第二行的坑见该函数 KDoc。
            appendLinkRow(
                status,
                listOf(
                    MpSqlVisualizerBundle.message("log.panel.empty.link") to
                        java.awt.event.ActionListener {
                            cn.song.mybatisplus.visualizer.config.PluginSettingsFolderConfigurable.openSqlLogSettingsFocused(
                                project,
                                MpSqlVisualizerBundle.message("settings.enhancedCapture"),
                            )
                        },
                ),
            )
        }
    }

    /** 空态文案可用宽度：列表宽度扣掉自身内边距与左右余量；尚未布局（首帧 / 隐藏卡）时给兜底值。 */
    private fun emptyTextWidthPx(): Int {
        val insets = list.insets
        val w = list.width - insets.left - insets.right - EMPTY_TEXT_EDGE_PADDING_PX * 2
        return if (w <= 0) EMPTY_TEXT_FALLBACK_WIDTH_PX else w
    }

    // ---------- T-CaptureTruth T-CT-03：零产出诊断 ----------

    /**
     * 诊断刷新定时器（仅「捕捉中且零条目」时运行）。
     *
     * 为什么需要定时器：零产出意味着缓冲不发任何通知，而控制台行数/增强挂载态都在变——不主动拉取
     * 的话诊断行会永远停在「捕捉开始那一刻」的快照上，恰好在用户真正想知道「为什么还是没有」时失语。
     * 2s 与桥轮询同频（[SqlLogConsoleBridge] POLL_INTERVAL_MS）；命中条目或停止捕捉即停表，常态零开销。
     */
    private var diagnosticsTimer: javax.swing.Timer? = null

    /**
     * R53 测试缝：双通道状态栏两段标签（第一段=控制台通道，第二段=增强通道）。
     */
    internal fun statusBarLabelsForTest(): Pair<JLabel?, JLabel?> = statusConsoleLabel to statusAgentLabel

    /**
     * R53 测试缝：同步驱动状态栏刷新（等价 2s 诊断定时器的一拍）。
     */
    internal fun syncStatusBarForTest() = syncCaptureStatusBar()

    /** R53 测试缝：状态栏宿主面板（非捕捉态应整条隐藏——不留分隔符/空白高度）。 */
    internal fun statusBarForTest(): JPanel? = statusBarPanel

    /** 诊断行同步（EDT）：更新列表空态文案 + 双通道状态栏 + 管理定时器生命周期。
     * 取数全部为只读内存态（buffer 快照 / 桥计数 / AgentManager 状态），不触发平台调用。
     *
     * R53：定时器生命周期由「空态才跑」放宽为「捕捉期间常跑」——状态栏需要持续刷新；
     * 空态判定改 [SqlLogEntryBuffer.size]（O(1)）而非 snapshot().isEmpty()（O(N) 拷贝，
     * 长列表下 2s 常跑会白付整表拷贝）。
     */
    private fun syncDiagnostics() {
        syncCaptureStatusBar()
        val active = manager.capturing && manager.entries.size() == 0
        val text = if (active) {
            captureDiagnosticsText(
                CaptureDiagnostics(
                    capturing = true,
                    waitingForConsole = manager.isWaitingForConsole,
                    entryCount = 0,
                    consolesAttached = manager.consoleBridge.attachedConsoles.size,
                    linesSeen = manager.consoleBridge.consoleLinesSeen.get(),
                    enhancedStatus = runCatching {
                        AgentManager.getInstance(project).statusSummary()
                    }.getOrNull(),
                    // T-RA-03：两个「行去哪了」计数——守卫误丢行 / EP 短路后条目未落到本面板时，
                    // 它们是面板唯一可见的证据（此前生产侧零读取，故障被伪装成「控制台没输出」）。
                    replayDropped = manager.consoleBridge.replayDropCount,
                    epShortCircuited = manager.consoleBridge.epActiveShortCircuitCount.get(),
                    // T-DevAcceptFix2 ⑤：增强通道已收事件数（恒显示）——「事件到没到」的唯一判别量
                    agentEventsReceived = cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter.receivedEvents(),
                    // T-DevAcceptFix2 ⑩：屏蔽规则命中次数（应用级累计，与规则面板同源计数）——
                    // 「事件到了却 0 条」时最容易被忽略的一条吃行通道（用户自建规则，设计内但必须可见）
                    suppressed = runCatching {
                        val mgr = SqlSuppressRuleManager.getInstance()
                        mgr.rules.sumOf { it.id.let { id -> mgr.hitCount(id) } }.toLong()
                    }.getOrDefault(0L),
                ),
            )
        } else {
            null
        }
        if (text != null) applyEmptyText(text) else applyEmptyTextWithEnhancedLink()
        // R53：定时器捕捉期间常跑（状态栏持续刷新）；非捕捉态停表清栏
        if (manager.capturing) {
            if (diagnosticsTimer == null) {
                diagnosticsTimer = javax.swing.Timer(DIAGNOSTICS_TICK_MS) { syncDiagnostics() }
                    .apply { isRepeats = true; start() }
            }
        } else {
            diagnosticsTimer?.stop()
            diagnosticsTimer = null
        }
    }

    /**
     * R53：双通道捕获状态栏刷新（EDT，挂在诊断定时器上 2s 无锁轮询）。
     * 文案由纯函数 [captureStatusLineText] 产出（V80①：取数全为只读内存态）；
     * 非捕捉态整条面板隐藏（只清文案会留下分隔符与空白高度）。
     */
    private fun syncCaptureStatusBar() {
        val label = statusConsoleLabel ?: return
        val texts = if (manager.capturing) {
            runCatching {
                val am = AgentManager.getInstance(project)
                captureStatusLineText(
                    CaptureStatusLine(
                        capturing = true,
                        consolesAttached = manager.consoleBridge.attachedConsoles.size,
                        linesSeen = manager.consoleBridge.consoleLinesSeen.get(),
                        agentStatus = am.statusSummary(),
                        agentLivePids = am.healthyPids(),
                        agentPaused = am.userPaused,
                        agentEventsReceived = cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter.receivedEvents(),
                    ),
                )
            }.getOrNull()
        } else {
            null
        }
        if (texts == null) {
            label.text = ""
            statusAgentLabel?.text = ""
            setStatusBarVisible(false)
        } else {
            label.text = texts.first
            statusAgentLabel?.text = texts.second
            setStatusBarVisible(true)
        }
    }

    /** R53：状态栏显隐（BorderLayout 跳过不可见子件，须 revalidate 才重排高度）。 */
    private fun setStatusBarVisible(visible: Boolean) {
        val bar = statusBarPanel ?: return
        if (bar.isVisible == visible) return
        bar.isVisible = visible
        bar.parent?.revalidate()
    }

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

    // ---------- 刷新管线（类型过滤 → 搜索过滤 → 排序 → 渲染） ----------

    /**
     * 刷新列表（过滤 → 搜索 → 排序 → 差分渲染）。
     *
     * 执行顺序：
     * 1. [filterBySuppress]：按屏蔽规则指纹集滤存量（T-SqlSuppress O-69；管线最前——空集零成本）
     * 2. [filterByType]：按 [activeFilter] 筛选（全选 = 全部，含 OTHER）
     * 3. [filterByFocus]：按 [activeFocus] 聚焦（M56 T-M56-00 基础设施；null = 透传）
     * 4. [filterBySearch]：按 [searchText] 搜索（默认忽略大小写；T-HistorySearchToggles 起随 MatchCase 开关）
     * 5. [applySort]：按 [sortMode] 排序（降序耗时）
     * 6. 差分渲染：T-Perf-03 增量刷新——流式追加免全量重建；无法增量时回退分批全量重建
     *
     * T-LogListFreeze-04：HTML 缓存随片段通道退役——徽章计数在渲染时直读 [dedupeCounts]，
     * 原「清缓存/选择性失效」机制一并移除。
     */
    private fun refreshList() {
        val gen = ++refreshGeneration
        // T-SqlLogPerf P5（20261006-1020 计划）：上一轮全量重建链未走完就来了新刷新时，增量差分会被
        // 「model.size != lastRendered.size」拒绝 → 每次刷新都重启重建链，风暴期列表永远停在首屏 30 条
        // （用户体感「监听加载有延迟」的实态）。进差分前同步补完剩余批次，让差分基线对齐。
        // F80（T-PerfSweep24）：超大余量时补完让路（false＝本拍刷新挂起，链尾补跑）——单拍同步写
        // 量不再随 logMaxCount 线性放大。
        if (!flushRebuildChainSync()) return
        val snapshot = LogPerfProbe.measure("LogPanel.refreshList.snapshot", { "size=${manager.entries.size()}" }) {
            manager.entries.snapshot()
        }
        // T-CaptureTruth T-CT-03：条目变化同步诊断行（有条目即还原默认空态文案并停表）
        syncDiagnostics()

        // 去重丢弃计数快照（徽章渲染数据源）：徽章在元信息行渲染时直读，无缓存失效链
        dedupeCounts = manager.entries.dedupeCountsSnapshot()
        entryCellRenderer.dedupeCounts = dedupeCounts

        // O-90：同模板计数快照（**全量条目**聚合——徽章计数不随当前过滤/搜索波动；
        // snapshot 本就为过滤链物化，structureKey 惰性缓存后单遍 O(N)）
        templateCounts = structureKeyCounts(snapshot)
        entryCellRenderer.templateCounts = templateCounts

        // T-SqlSuppress：屏蔽存量过滤插管线最前（与类型过滤同级；键集经 SqlLogManager 订阅同步）
        val filtered = filterByFocus(
            filterByType(
                applySlowOnlyFilter(filterBySuppress(snapshot, manager.entries.suppressKeys)),                activeFilter,
            ),
            activeFocus,
        )
        // T-M55-03/04：动态聚焦页签层（页签=过滤器，多开并集；离散增删走 FullRebuild，差分零改动）
        val focused = filterByFocusTabs(filtered, focusTabs)
        // T-HistorySearchToggles：搜索旗随 MatchCase 开关（默认关=忽略大小写，与现状一致）
        // T-SearchToggleNativeLook：正则旗随 Regex 钮（20261010 用户令日志页补正则，语义与历史页同链）
        val searched = filterBySearch(focused, searchText, matchCase = logMatchCase, regexEnabled = logRegex, memo = regexMemo)
        val newVisible = applySort(searched)
        val evicted = manager.entries.drainEvictedCount()

        val modelSizeBefore = listModel.size()
        val visibleTailSequenceBefore = lastRendered.lastOrNull()?.sequenceNumber ?: 0L
        if (!applyIncremental(newVisible, evicted)) {
            applyFullRebuild(newVisible, gen)
        }
        val tailAdvancedAtCapacity = evicted > 0 &&
            (newVisible.lastOrNull()?.sequenceNumber ?: 0L) > visibleTailSequenceBefore
        // 缓冲区满时头部淘汰与尾部追加相抵，模型长度不变；只随确有新可见条目时贴底。
        if (tailFollow.shouldFollow && (listModel.size() > modelSizeBefore || tailAdvancedAtCapacity)) {
            scrollToBottom(resume = false)
        }

        // 聚焦隐藏计数提示（聚焦层单独度量：输入 filtered → 输出 focused）
        if (focusTabs.isEmpty()) {
            focusHiddenLabel.isVisible = false
        } else {
            val hidden = filtered.size - focused.size
            focusHiddenLabel.text = MpSqlVisualizerBundle.message("log.focus.hidden", hidden.toString())
            focusHiddenLabel.isVisible = hidden > 0
        }

        // O-1：同步慢查询阈值 + 更新计数（Phase C）
        slowQueryCounter.thresholdMs = PluginSettings.getInstance().state.slowQueryThresholdMs
        slowQueryCounter.reset()
        snapshot.forEach { slowQueryCounter.report(it.executionTimeMs) }
        updateSlowQueryLabel()
    }

    /**
     * T-Perf-03（自 dev 移植）：增量应用差分（同步完成，无异步追加链）。模型与 [lastRendered] 尺寸不一致
     * （旧异步追加链被新刷新中断/外部改动）时拒绝增量——全量重建兜底。
     */
    private fun applyIncremental(newVisible: List<LogSqlEntry>, evicted: Int): Boolean {
        if (listModel.size() != lastRendered.size) return false
        return when (val diff = diffVisible(lastRendered, newVisible, evicted)) {
            is VisibleDiff.Same -> { lastRendered = newVisible; true }
            is VisibleDiff.HeadEvictAppend -> {
                repeat(diff.evicted) { listModel.remove(0) }
                diff.appended.forEach { listModel.addElement(it) }
                lastRendered = newVisible
                true
            }
            is VisibleDiff.Insert -> {
                diff.entries.forEachIndexed { i, e -> listModel.add(diff.index + i, e) }
                lastRendered = newVisible
                true
            }
            is VisibleDiff.TailRemove -> {
                for (i in lastRendered.size - 1 downTo diff.from) listModel.remove(i)
                lastRendered = newVisible
                true
            }
            is VisibleDiff.FullRebuild -> false
        }
    }

    /**
     * 全量重建（分批 EDT 写入 + generation 守卫）。
     *
     * T-Perf-03 追加链 generation 守卫（自 dev 移植）：新 refreshList 启动后旧链在新 chunk 写入前检测代际
     * 并终止——杜绝旧快照剩余条目继续追加造成的幽灵/重复条目。
     * T-SqlLogPerf P5：剩余批次游标提为字段 [rebuildRemaining]——refreshList 进差分前经
     * [flushRebuildChainSync] 同步补完在途链（旧实现链被新刷新抢占后只能整体重启，见 flushRebuildChainSync 注释）。
     */
    private fun applyFullRebuild(sorted: List<LogSqlEntry>, gen: Long) {
        lastRendered = sorted
        rebuildRemaining = null
        // T-LogListFreeze-01：批量抑制布局 + T-LogListFreeze-03：分批 EDT 写入
        list.ignoreRepaint = true
        listModel.clear()
        if (sorted.isNotEmpty()) {
            // 首屏批次（立即写入，保证快速首屏）
            val firstBatch = sorted.take(REFRESH_FIRST_BATCH_SIZE)
            listModel.addAll(firstBatch)
        }
        list.ignoreRepaint = false
        list.revalidate()
        list.repaint()

        // T-LogListFreeze-03：剩余批次通过 invokeLater 异步追加（每批一次 EDT 脉冲，避免长冻结）
        rebuildRemaining = if (sorted.size > REFRESH_FIRST_BATCH_SIZE) {
            sorted.drop(REFRESH_FIRST_BATCH_SIZE).iterator()
        } else {
            null
        }
        if (rebuildRemaining != null) {
            ApplicationManager.getApplication().invokeLater { appendRebuildChunk() }
        }
    }

    /** T-SqlLogPerf P5：在途全量重建链的剩余游标（null = 无在途链）。 */
    private var rebuildRemaining: Iterator<LogSqlEntry>? = null

    /** F80：链后补跑挂起标志（超大余量让路本拍刷新，链排空即补跑一次）。 */
    private var pendingRefreshAfterChain = false

    /** 追加一批（REFRESH_CHUNK_SIZE 条）；写完仍有余量则排下一拍。 */
    private fun appendRebuildChunk() {
        val remaining = rebuildRemaining ?: return
        val chunk = mutableListOf<LogSqlEntry>()
        repeat(REFRESH_CHUNK_SIZE) { if (remaining.hasNext()) chunk.add(remaining.next()) }
        listModel.addAll(chunk)
        if (chunk.isNotEmpty() && tailFollow.shouldFollow) scrollToBottom(resume = false)
        rebuildRemaining = if (remaining.hasNext()) remaining else null
        if (rebuildRemaining != null) {
            ApplicationManager.getApplication().invokeLater { appendRebuildChunk() }
        } else if (pendingRefreshAfterChain) {
            // F80：链排空——补跑挂起的刷新（风暴期多次挂起合并为一次补跑）
            pendingRefreshAfterChain = false
            ApplicationManager.getApplication().invokeLater { refreshList() }
        }
    }

    /**
     * T-SqlLogPerf P5（20261006-1020 计划）：同步补完在途全量重建链。
     *
     * 旧形态：重建链在途时 `listModel.size != lastRendered.size`，增量差分必被拒绝 → 风暴期
     * 每 200ms 的新刷新都重启重建链，链永远走不完，列表停在首屏 30 条且反复 clear/addAll 白耗 EDT。
     * 补完后 model 与 lastRendered 对齐，后续刷新走增量差分。
     *
     * F80（T-PerfSweep24）补完保留分批：余量 ≤ [FLUSH_SYNC_MAX]（默认设置 ≤170）照旧单拍同步
     * 补完——默认行为逐字不变；余量超大（用户 logMaxCount 数千）不再单拍同步写数千条
     * （EDT 冻结随 logMaxCount 线性放大），本拍刷新让路（返回 false 挂起），在途链沿用
     * appendRebuildChunk 逐拍语义推进（每拍 30 条 EDT 脉冲），链排空补跑挂起刷新——
     * 风暴期新刷新不再反复 clear+重启链，列表从首屏 30 条起逐拍长满。
     */
    private fun flushRebuildChainSync(): Boolean {
        val remaining = rebuildRemaining ?: return true
        val rest = remaining.asSequence().toList()
        if (rest.size > FLUSH_SYNC_MAX) {
            // 超大余量：让路——链保持推进（rebuildRemaining 不动），本拍刷新挂起
            pendingRefreshAfterChain = true
            return false
        }
        rebuildRemaining = null
        list.ignoreRepaint = true
        if (rest.isNotEmpty()) listModel.addAll(rest)
        list.ignoreRepaint = false
        list.revalidate()
        list.repaint()
        return true
    }

    /** 按当前排序模式排序条目。 */
    // ---------- 排序方式（T-LogSortModes） ----------

    /** 弹出排序方式单选菜单（与 SQL类型弹窗同款交互；当前项打勾）。锚点＝触发动作的按钮。 */
    private fun showSortPopup(anchor: java.awt.Component?) {
        val popup = JPopupMenu()
        buildSortMenuItems().forEach { popup.add(it) }
        val target = anchor ?: toolbarComponent
        if (target != null) popup.show(target, 0, target.height + 2) else popup.show(toolbarComponent, 0, 0)
    }

    /**
     * 构建排序菜单项（拆出供测试断言选项齐备——headless 下 JPopupMenu.show 不可靠）。
     * 单选语义：点选即生效并刷新列表与按钮文案。
     */
    private fun buildSortMenuItems(): List<JRadioButtonMenuItem> =
        sortOptions().map { option ->
            JRadioButtonMenuItem(option.label, option.mode == sortMode).apply {
                isFocusable = false
                addActionListener { applySortMode(option.mode) }
            }
        }

    /** 应用排序方式：更新模式 → 刷新列表 → 刷新动作 presentation（当前方式进 tooltip 与菜单选中态）。 */
    private fun applySortMode(mode: SortMode) {
        if (sortMode == mode) {
            refreshSortAction()
            return
        }
        sortMode = mode
        refreshList()
        refreshSortAction()
    }

    /** 刷新排序动作的 presentation（工具窗工具栏只渲染图标，当前方式由 tooltip 承载）。 */
    private fun refreshSortAction() {
        _toolbarRef?.updateActionsImmediately()
    }

    /**
     * 排序动作文案 = 当前方式标签（见 [sortModeAction] 的 update：进 presentation.text 与 tooltip）。
     * 工具窗工具栏按惯例只渲染图标，故当前方式实际由 tooltip「排序：<方式>」与菜单选中态承载。
     */
    private fun updateSortButtonText() {
        refreshSortAction()
    }

    private fun applySort(entries: List<LogSqlEntry>): List<LogSqlEntry> = when (sortMode) {
        // 时间升序（默认）：与捕获顺序一致（sortedBy 稳定，等值保持入列序）
        SortMode.TIME_ASC -> entries.sortedBy { displayTimeOf(it) }
        SortMode.TIME_DESC -> entries.sortedByDescending { displayTimeOf(it) }
        // 耗时：结构化字段优先（agent 通道直采），缺失时回退日志文本解析；无耗时沉底
        SortMode.DURATION_ASC -> entries.sortedBy { durationOf(it) ?: Long.MAX_VALUE }
        SortMode.DURATION_DESC -> entries.sortedByDescending { durationOf(it) ?: Long.MIN_VALUE }
        // 行号：无行号（历史读回/round-trip）沉底
        SortMode.SEQUENCE -> entries.sortedBy { if (it.sequenceNumber > 0) it.sequenceNumber else Long.MAX_VALUE }
        // 同结构聚类（用户 2026-10-14 补）：按结构指纹分组、组内保持时间升序——排查 N+1 时同形状条目相邻。
        // structureKey 为条目级 lazy（每条只算一次指纹），故比较器是纯字段读取。
        SortMode.STRUCTURE_CLUSTER -> entries.sortedWith(
            compareBy({ it.structureKey }, { displayTimeOf(it) }),
        )
        // 按目标库分组（用户 2026-10-14 补）：库名升序、未知（null）沉底，组内保持时间升序。
        SortMode.DATABASE_GROUP -> entries.sortedWith(
            compareBy({ it.databaseName ?: DATABASE_UNKNOWN_SORT_KEY }, { displayTimeOf(it) }),
        )
    }

    /**
     * 「只看慢查询」过滤接线（阈值取当前设置；判定走 [durationOf]，与慢查询徽章同口径）。
     * 纯逻辑在 [cn.song.mybatisplus.visualizer.log.filterBySlowOnly]，便于独立单测。
     */
    private fun applySlowOnlyFilter(entries: List<LogSqlEntry>): List<LogSqlEntry> =
        cn.song.mybatisplus.visualizer.log.filterBySlowOnly(
            entries,
            slowOnly,
            PluginSettings.getInstance().state.slowQueryThresholdMs,
            ::durationOf,
        )

    /** 条目耗时（毫秒）：结构化 executionTimeMs 优先，缺失时回退日志文本解析。 */    private fun durationOf(entry: LogSqlEntry): Long? =
        entry.executionTimeMs ?: LogDurationParser.parse(entry)

    /**
     * 条目显示时间（排序口径）：与列表现行时间列一致——「日志时间」源开启且条目有原始日志时间时
     * 用之，否则用捕获时间（[formatTimestampFragment] 同规则），避免「看到的时间与排序依据不一致」。
     */
    private fun displayTimeOf(entry: LogSqlEntry): Long {
        val state = PluginSettings.getInstance().state
        return if (state.logTimestampSource == LOG_TIMESTAMP_SOURCE_LOG) {
            entry.logTimestamp ?: entry.timestamp
        } else {
            entry.timestamp
        }
    }

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
    private fun updateSlowQueryLabel() {
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
        SqlPreviewService.showResult(project, entry.toPreviewResult(formatSql = state.logFormatSql, literalRecovery = state.literalParamRecovery))
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
        private const val DROPDOWN_TEXT_BUDGET_PX = 64
        /** T-CaptureTruth T-CT-03：零产出诊断刷新间隔（与桥轮询同频 2s）。 */
        private const val DIAGNOSTICS_TICK_MS = 2000
        /** R53：双通道状态栏两段文案之间的语义分隔符。 */
        private const val SEP_LABEL = "｜"
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

        /** 类型 → 文案。 */
        private fun labelForType(type: StatementType): String = when (type) {
            StatementType.SELECT -> MpSqlVisualizerBundle.message("log.filter.select")
            StatementType.INSERT -> MpSqlVisualizerBundle.message("log.filter.insert")
            StatementType.UPDATE -> MpSqlVisualizerBundle.message("log.filter.update")
            StatementType.DELETE -> MpSqlVisualizerBundle.message("log.filter.delete")
            else -> "OTHER"
        }
    }
}

// =================== 文件级：列表渲染辅助（internal 渲染器与 SqlLogPanel 共用） ===================


