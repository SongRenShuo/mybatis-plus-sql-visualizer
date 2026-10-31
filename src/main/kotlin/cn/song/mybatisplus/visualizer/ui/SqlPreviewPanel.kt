package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.history.SqlHistoryEntry
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.execute.ConsoleEmbedMode
import cn.song.mybatisplus.visualizer.execute.DocWriteContext
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.execute.SqlExecutor
// T-M53-II：`installOutputHistoryFillLayout` 已搬为 execute 包内的同包扩展函数（原 DataSourceSelector 成员），
// 跨包调用点需显式导入。
import cn.song.mybatisplus.visualizer.execute.installOutputHistoryFillLayout
import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.FillClause
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.model.wrapperFlagOutcome
import cn.song.mybatisplus.visualizer.model.wrapperFlagOutcomeText
import cn.song.mybatisplus.visualizer.model.wrapperFlagOutcomeTooltip
import cn.song.mybatisplus.visualizer.sql.ExplainGate
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.PlaceholderCardinality
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.sql.interceptor.PaginationSimulator
import cn.song.mybatisplus.visualizer.share.ui.ShareDialog
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanel
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramSupport
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter
import cn.song.mybatisplus.visualizer.ui.inlay.PreviewInlayScope
import cn.song.mybatisplus.visualizer.util.CaseFormatUtil
import cn.song.mybatisplus.visualizer.util.EditorCommentShortcuts
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import cn.song.mybatisplus.visualizer.xml.ExprReason
import cn.song.mybatisplus.visualizer.xml.ExprSupport
import cn.song.mybatisplus.visualizer.xml.ConditionOutcome
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql
import cn.song.mybatisplus.visualizer.xml.XmlConditionView
import cn.song.mybatisplus.visualizer.xml.XmlForEachView
import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlPreviewState
import cn.song.mybatisplus.visualizer.xml.PagesColumn
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.Disposable
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.HideableTitledPanel
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Insets
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * SQL 预览面板（M9-01/02/03 可编辑版，F-07/F-08）。
 *
 * 弹窗 [SqlPreviewDialog] 与工具窗口共用本面板，展示：
 *  - 顶部单行：元信息（Wrapper 类型 / 表名 / 风险警示，T-PreviewTopOverlap F3 按宽度预算截断）
 *    + 工具栏（压缩切换 / 格式化 / 关键字换行 / 竖向排列 / 执行计划 / 刷新 + 「更多」折叠低频动作；
 *    复制错误仅警示时可见）
 *  - 中部左右分栏：左侧**参数值输入框**（`#param` → 值，输入实时回填 SQL），右侧**可编辑 SQL 编辑器**
 *  - SQL 编辑器：`EditorFactory.createEditor`（可写）+ 原生 SQL 语法高亮（平台自动关联）；
 *    文档由原生 SQL FileType PSI 文件支撑，挂 [SqlCompletionContributor]（language="SQL" + IS_PREVIEW_EDITOR 守卫）提供补全（Ctrl+Space）
 *  - 补全上下文（表名/列名）双载体传递（[SqlCompletionContext]）：**Editor userData 优先**（M16.2，
 *    嵌入式编辑器编辑后 PSI 可能被 commit 重建丢失 userData）+ **PSI userData 兜底**
 *
 * 说明：编辑器生命周期在 [removeNotify] 时释放，避免泄漏（EditorFactory.releaseEditor）。
 */
// T-M53-II：构造期属性放宽为 internal——同包扩展函数（拆出的协作文件）需要读取它；
// 可见性只在模块内变化，调用点语义不变。
class SqlPreviewPanel(internal val project: Project? = null) : JBPanel<SqlPreviewPanel>(BorderLayout()), Disposable {

    internal val metaLabel = JBLabel()

    // T-PreviewTopOverlap F1/F3：顶部行与工具栏提升为字段——布局断言（topRowForTest/toolbarForTest）
    // 与宽度预算计算（applyMetaTruncation 需工具栏理想宽）使用
    internal lateinit var topRow: JBPanel<JBPanel<*>>
    internal lateinit var toolbarPanel: JBPanel<JBPanel<*>>

    // T-PreviewTopOverlap F3：元信息原文缓存——metaLineText 第一行（meta+警示，纯文本）、
    // metaPerfSegments 第二行分段（可见文本+颜色）；tooltip 恒全文，label 文本按预算重拼
    internal var metaLineText: String = ""

    /**
     * F3 分段缓存（P5 复核修正）：meta 行**主体**（meta + 警示行）——宽度预算下恒优先保留。
     * 与 [metaLineDigest] 分段缓存的原因见 [metaLineWithinBudget]（摘要不得挤掉主体）。
     */
    internal var metaLineHead: String = ""

    /** F3 分段缓存（P5 复核修正）：meta 行尾部的调用栈内联摘要——预算不足时先截它。 */
    internal var metaLineDigest: String = ""
    internal var metaPerfSegments: List<MetaPerfSegment> = emptyList()
    internal var lastMetaBudget: Int = -1

    /**
     * T-DialectStatusLiveRefresh：当前方言决策（初值来自 [PreviewResult.dialectDecision]）。
     * 执行/绑定后可重算升级；meta 行只重组方言段，不重跑 [updateMeta] 的警示/横幅全链。
     */
    internal var currentDialectDecision: cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision? = null

    /** 方言段之外的 meta 主体（类型/表/警示行），方言段轻量重组时的固定前缀。 */
    internal var metaCoreText: String = ""

    /** tooltip 的方言详情之前部分（meta/警示/perf），拆分保证详情追加后顺序不变。 */
    internal var metaTooltipCore: String = ""

    /** tooltip 的方言详情之后部分（方言能力矩阵），拆分同上。 */
    internal var metaTooltipSuffix: String = ""

    /**
     * T-SourceJumpFunnel P6 修复：meta 行 tooltip 的**基线文本**（[updateMeta] 每次预览重建）。
     *
     * 行内提示态（参数导入空剪贴板）需要临时改写可见行与 tooltip，但 tooltip 是「悬停见完整调用链」
     * 的唯一载体（§3.3 后半句），不能被提示覆写丢掉——故提示态按「提示 + 换行 + 基线」拼接，
     * 本字段是拼接的基线来源（也保证提示重复触发时幂等，不会叠加自身）。
     *
     * T-StackHoverUi 起栈文本移出 tooltip（见 [stackHover]）：本基线只含 meta/警示/perf，
     * 「完整调用链」改由悬停弹层承接。
     */
    internal var metaTooltipBase: String = ""

    /**
     * T-StackHoverUi：调用栈悬停弹层控制器（调试器 Frames 风格，D84 拍板 A 案）。
     *
     * 栈文本在悬停面的唯一载体——旧实现把截断摘要+全文重复拼进 tooltip、超长单行折成文本墙；
     * 声明序在 init 块之前（构造期 NPE 固定检查项：init 内 attach/register 均读本字段）。
     */
    internal val stackHover = StackFrameHover(project)

    /** F3：perf 建议分段（可见文本 + HTML 颜色值），截断时按预算丢尾段。 */
    internal data class MetaPerfSegment(val text: String, val color: String)

    // T-M26.6：格式化快捷键动作（跟随 IDE keymap 的 Reformat Code，兜底 Ctrl+Alt+L）——
    // 注册到面板整体，焦点在 SQL 编辑器或参数输入框时均可触发；每次触发取当前 editor 格式化
    internal val reformatAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            formatSql()
        }
    }

    // 左：动态条件复选框 + 参数值输入区
    internal val paramPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    // 顶部对齐容器：BoxLayout Y_AXIS 在视口高于内容时默认垂直居中；
    // 用 BorderLayout.NOWRAP 包裹后固定参数行在顶部（用户要求"向上靠"）
    private val paramTopAlign = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(paramPanel, BorderLayout.NORTH)
        isOpaque = false
    }
    // T-M26.3：滚动区提升为字段——截断上限（标签列/复选框文本）按视口宽度计算，
    // 保证行宽不超出视口（标题行右侧按钮不被推出）
    internal val paramScroll = JBScrollPane(paramTopAlign).apply {
        preferredSize = Dimension(240, 200)
        // T-PreviewBgFit（问题一）：本面板自身给出面板底色——左侧参数区整条链是透明的
        //（paramTopAlign/viewport 均非不透明，M36.5 起用于与父容器融合），底色一直靠祖先容器提供；
        // T-UiNative P0a 把自绘 ThemedSplitPane（当时显式设了 UIUtil.getPanelBackground()）换成平台
        // OnePixelSplitter 时丢了那层背景，深色主题下参数行下方直接透出 splitter 的近黑背景。
        // 恢复祖先背景之外再让本滚动区成不透明面板灰，今后再换容器也不会复发。
        isOpaque = true
        background = com.intellij.util.ui.UIUtil.getPanelBackground()
        viewport.isOpaque = false
        // M36.5：去 LAF 默认 viewport 描边（左侧白色内边框根因）
        border = JBUI.Borders.empty()
    }
    internal val paramFields = mutableListOf<JBTextField>()
    // T-PT-02：普通参数行行尾校验警示标（与 paramFields 同序，仅 buildParamRow 产出）
    internal val paramWarnLabels = mutableListOf<JBLabel>()
    internal val conditionCheckBoxes = mutableListOf<JCheckBox>()
    // R30 条件求值探镜：XML 条件行尾的三态结果标注（条件 id → 标签），renderSql 每轮刷新（零监听器）
    internal val xmlConditionOutcomeLabels = mutableMapOf<String, JBLabel>()
    internal var autoPopupDisposable: Disposable? = null
    internal val conditionValueFields = mutableListOf<JBTextField>()
    // R49：Wrapper 布尔条件探镜——条件行尾的求值结果标注（按条件行下标与 conditionCheckBoxes 对齐，
    // 仅 buildConditionRow 产出；该行不可标注时为 null 占位，下标不跳位）
    internal val conditionOutcomeLabels = mutableListOf<JBLabel?>()
    // T-M23-04：批量列级勾选复选框（INSERT/UPDATE/UPSERT 参数列，与动态条件复选框互斥——批量预览无 conditionViews）
    internal val batchCheckBoxes = mutableListOf<JCheckBox>()
    // T-M26.3：全选/全不选按钮回挂分区标题行右侧（IDEA 组操作惯例，T-M23.2 原生形态；
    // T-M26.2 曾固定面板右上角，长文本截断后按钮不再被推出视口）。
    // T-SelToggle：全选/全不选合并为单个状态切换按钮——全勾时文案「全不选」点击清空，
    // 其余态文案「全选」点击填满；文案随勾选态翻转（renderSql 末尾单点同步）。
    internal var toggleSelectButton: JButton? = null
    internal var clearButton: JButton? = null        // 动态条件分区清除按钮
    internal var batchClearButton: JButton? = null    // 批量参数列分区清除按钮
    // T-M23.2：批量「参数列」区全选/全不选切换按钮（T-SelToggle 合并单按钮）
    internal var batchToggleSelectButton: JButton? = null

    // 右：可编辑 SQL 编辑器（PSI 支撑，补全用）
    internal var editor: Editor? = null

    /**
     * T-M27.2：自建 autopopup 的调度防重入标志（平台 autopopup 对非文件编辑器不生效）。
     * T-RA-02：监听器本体不再用字段记账——T-M52 起移除职责全在 [autoPopupDisposable]
     * （`addDocumentListener(listener, disposable)`，dispose 即摘），原 `autoPopupListener`
     * 字段自此只写不读，已删；细节见 [bindAutoPopup]。
     */
    internal var autoPopupScheduled = false

    /** T-M27.4：渲染 setText 期间抑制自建 autopopup（格式化替换整文档不触发补全）。 */
    internal var suppressAutoPopup = false
    internal var psiFile: PsiFile? = null
    /** M34 回归修复：预览 PSI 的临时 VirtualFile（PsiDocumentManager 追踪需真实 VFS 文件），releaseEditor 时删除。 */
    @Volatile
    internal var previewTempFile: com.intellij.openapi.vfs.VirtualFile? = null
    /**
     * T-FixN2：本面板**已创建**的预览临时文件登记表（[projectPreviewFile] 建文件即登记，
     * [releaseEditor] 统一回收）。与 [previewTempFile] 的分工：后者是「当前在用/最近一次预热」
     * 的那一个（唯一名字后仍可能被竞态里晚到的预热覆盖），本表是「本面板创建过的全部」——
     * 覆盖「预热已发布但从未被装配消费」与「两次预热竞态中晚到的那一个」两类无主文件。
     *
     * 只登记本面板自己创建的文件（含跨会话同名残留的兜底复用分支），故 [releaseEditor] 的删除
     * **不会**误删其它面板/其它 IDE 会话正在使用的预览文件（同项目多开进程各有自己的 pid 段）。
     */
    internal val previewTempFilesCreated = java.util.concurrent.ConcurrentHashMap.newKeySet<java.io.File>()

    /**
     * T-PerfSweep6 T-PS6-04：目录级兜底清扫的「本面板已扫过」闸门（首次解析预热目录时扫一次）。
     * 原子置位：`projectPreviewFile` 可能被预热线程与 EDT 同时触达。
     */
    internal val previewDirSwept = java.util.concurrent.atomic.AtomicBoolean(false)
    /** T-M36.6-03：后台预热产物（vf+PSI 就绪对），createEditor 优先消费——EDT 零慢操作。 */
    @Volatile
    internal var prewarmedTempFile: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? = null

    /**
     * T-PerfTail #2：预热产物异步载体（消除 EDT sleep-poll）——prewarmPreviewTempFile 每次
     * 预热新建（clear 重预热随之更新），createEditor 热态同步消费、冷态注册回调回 EDT 装配。
     */
    @Volatile
    internal var prewarmAwait: PrewarmAwait<Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>>? = null
    /** T-GifRerecord：预热失败标志——await 快速降级用，避免 EDT 空等 2s 后再走降级路径。 */
    @Volatile
    internal var prewarmFailed = false

    /**
     * T-PrewarmLateRecover F1/F2：预热轮次令牌——每次发起预热（构造期 / [clear] 重预热 / 横幅重试）
     * 自增，预热线程发布产物时比对。不等即「本轮已被后来的一轮取代」：产物不入字段（否则旧轮的
     * 迟到产物会覆盖新轮刚交付的那份），文件仍由 [releaseEditor] 按登记表回收。
     */
    @Volatile
    internal var prewarmRound = 0

    /**
     * T-PrewarmLateRecover F1：当前编辑器文档是**降级产物**时的身份标记（主路径装配成功置 null）。
     * 迟到产物换装前用它确认「此刻显示的确实是那份降级文档」——不比对身份就可能把用户已经
     * 在用的主路径文档误换掉。
     */
    internal var degradedPreviewDocument: com.intellij.openapi.editor.Document? = null

    /**
     * T-PrewarmLateRecover F1：插件最后一次写进编辑器文档的 SQL 文本——换装判据里的「用户没动过」。
     * 三条写入支链（原生控制台 / 既有编辑器 / 新建编辑器）都要更新，漏一条即把插件的重渲染
     * 误判成用户编辑（换装被永久挡掉）。
     */
    internal var lastRenderedSql: String? = null

    /**
     * T-DegradedPreviewUx P1：瞬时降级的宽限定时器。
     *
     * 预热超时（产物仍在途）时不立刻挂横幅——[DEGRADED_GRACE_MS] 内产物到达会由主路径装配
     * 取消本定时器，横幅一次都不出现；到点仍未恢复才显示超时型横幅。硬失败不走这条路。
     */
    internal var degradedGraceTimer: javax.swing.Timer? = null

    /** 宽限定时器到点后要披露的原因（[cancelDegradedGraceTimer] 一并清空）。 */
    internal var degradedGraceReason: PreviewDegradationReason? = null

    // R92: preview-only overlay; never mixed into paramValues or real handler state.
    internal var fieldFillEnabled = false
    internal var fieldFillSyncing = false
    internal var fieldFillPreview: PreviewResult? = null
    internal val fieldFillValues = linkedMapOf<String, String>()
    internal val fieldFillFields = linkedMapOf<String, JBTextField>()
    /** 模拟值覆盖时的普通参数输入框（source → 普通输入框）；用于置灰/恢复。 */
    internal val fieldFillNormalFields = linkedMapOf<String, JBTextField>()
    /** 模拟值行（标签+输入框）；仅在「FieldFill 安全模拟」勾选时可见。 */
    internal val fieldFillSimulationComponents = mutableListOf<JComponent>()
    internal var fieldFillCheckBox: JCheckBox? = null
    internal var fieldFillStatus: JBLabel? = null
    /** T-PreviewSideQuery P1：预估影响行数的内联结果行（不进入控制台/结果区）。 */
    internal var impactResultLabel: JBLabel? = null
    /** T-PreviewSideQuery P2：EXPLAIN 非模态结果对话框（不替换预览编辑器）。 */
    internal var explainResultDialog: ExplainPlanDialog? = null

    /**
     * T-PrewarmLateRecover F1：预热相关回调的 EDT 派发通道（写安全模态）。
     *
     * 与 [prewarmPreviewTempFile] 里 PrewarmAwait 的 dispatcher 是同一份实现：迟到产物换装也要
     * 写文档（`WriteCommandAction { d.setText }`），故必须同款 `defaultModalityState()`——
     * `any()` 恒 write-unsafe（T-FixN1 的 KDoc 有平台侧逐帧依据）。
     */
    internal val prewarmDispatcher: (Runnable) -> Unit = { runnable ->
        ApplicationManager.getApplication().invokeLater(
            runnable,
            com.intellij.openapi.application.ModalityState.defaultModalityState(),
        )
    }
    /** T-M36.6-03：面板释放标志——预热线程晚于 dispose 完成时丢弃产物并删除文件。
     *  T-StabilityTail 起语义扩为「放弃预热」：面板 dispose 与**项目开始销毁**（见
     *  [prewarmLifecycleGate]）都置它，预热线程在下一个检查点放弃（不再取服务）。 */
    @Volatile
    internal var previewDisposed = false

    /**
     * T-StabilityTail（窗口内异步泄漏治理）：预热任务的「项目开始销毁」取消门。
     *
     * 注册进 `ProjectEx.getEarlyDisposable()` 的 Disposable 树（见 [bindPrewarmToProjectLifecycle]），
     * 项目开始销毁时由平台回收并置 [previewDisposed]，使构造期/`clear()` 派发的池线程在窗口内
     * 不再取服务。面板 dispose 时解除登记（不留指向本面板的项目树引用）；null = 未挂或已解除。
     */
    internal var prewarmLifecycleGate: Disposable? = null
    /** T-PreviewExecDs-01c：已 setMapping 方言的预览 vf（同对象幂等短路，避免每次预览重设方言触发 push all dumb）。
     *  T-FixN2 起该 vf 是**预览目录**（不再是单个文件）——见 [mapPreviewDialectBeforePsi]。 */
    @Volatile
    internal var previewDialectMappedVf: com.intellij.openapi.vfs.VirtualFile? = null
    /** 本次预览 SQL 的实际生成方言；预热线程与结果到达后的重映射共用。null=兼容 MySQL。 */
    @Volatile
    internal var previewDesiredDialectName: String? = null
    /** [previewDialectMappedVf] 当前已映射的平台 language id，防同目录误把 MySQL 当成 Oracle/PG 幂等命中。 */
    @Volatile
    internal var previewDialectMappedLanguageId: String? = null
    /** O-145 W1/W6：面板只保留不可变模块键，不持有宿主 PSI 或 JDBC URL。 */
    internal var sourceModuleKey: String? = null
    /** F-46：当前代码位置（文件名:行号），来源列默认值；null 表示未知（日志/历史等无锚点场景）。 */
    private var sourceLocation: String? = null
    internal val editorHolder = JBPanel<JBPanel<*>>(java.awt.GridLayout(1, 1)).apply {
        // M36.5.25e：GridLayout(1,1) 强制唯一子组件（内嵌控制台）fill——BorderLayout CENTER
        // 受子组件 preferredSize 影响导致 editor 不 fill（实际高度≈preferred ~375，15 行 SQL
        // 被截断）；GridLayout 按可用空间均分强制填满，editor 高度自适应窗口大小，无需固定
        // minimumSize 兜底。首次塌缩兜底由 editorComponentRaw 的 minimumSize 承担。
        isOpaque = false
    }

    // M36.5：SQL 执行状态面板（主面板 SOUTH——仅在有状态/错误内容时可见，避免空白占位）
    internal val resultPanel = SqlResultPanel().apply {
        isVisible = false
    }

    /**
     * T-UxOnboard T-UX-02：预览编辑器空态引导（原生 placeholder，显隐由编辑器随空文档管理）。
     * 常态下预览内容即编辑器全文、placeholder 不可见；用户清空文档后原生提示接管。
     */
    internal val previewEmptyHint: String = MpSqlVisualizerBundle.message("panel.preview.emptyHint")

    /** M36：已内嵌的原生控制台 UI 组件（清空/释放时级联 dispose）。 */
    internal var embeddedConsole: JComponent? = null

    /** T-PreviewSideQuery P3a：执行结果视图头部（含「返回预览」）；null=当前不是执行视图。 */
    internal var executeViewHeader: JComponent? = null

    /**
     * R33：结构化 EXPLAIN 回调代际计数——每次发起新的 EXPLAIN 结构化提交/新预览/清空都递增，
     * 在途 pooled 回调（JDBC 可能慢于用户操作）按提交时捕获的代际判过期，过期即丢弃：
     * 慢计划绝不覆盖用户已经看到的新预览/新提交（与 DataSourceSelector 的 swapGeneration 同思路）。
     */
    internal var explainStructuredGeneration = 0

    /**
     * R61（V113②）：会话级上一次结构化 EXPLAIN 结果（跨次对比基线）。
     * 生命周期＝会话级（面板实例存活期）——不持久化、不跨会话；同 SQL 多次执行时滚动更新，
     * 不同 SQL 各自独立基线（按 SQL 指纹键控）。
     */
    internal var lastStructuredExplain: Pair<String, McpExplainFormatter.RawResult>? = null
    /** R90：同目标会话基线与持久化历史协调；未知 UUID 仅按目标对象身份比较。 */
    internal val explainPlanHistorySession = cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession()
    /** R91: confirmed EXPLAIN target for this preview only; never infer from last-used project source. */
    internal var aiContextTarget: AiContextTarget? = null
    /** R91: only the last acquired plan in this panel, invalidated by history clear/load generation. */
    internal var aiContextPlan: Pair<Long, cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot>? = null
    internal data class AiContextTarget(
        val preview: PreviewResult?,
        val key: cn.song.mybatisplus.visualizer.history.ExplainPlanKey,
        val stillCurrent: () -> Boolean,
    )
    /**
     * R51（O-105 余半）测试缝：recall 回落源——ParamHistoryCache 未命中时按缓存键取历史
     * 参数快照（null = 单测态默认禁用真服务；生产走 [SqlHistoryManager.newestParamSnapshot]，
     * 接线由源码扫描守卫钉住）。循 [bindStateNameProvider] 同款 provider 惯例。
     */
    internal var historySnapshotFallbackSource: ((String) -> Map<String, String>?)? = null

    /** R51 测试缝：参数快照回写 sink——flush 链同步进历史条目的落点观察（null = 单测态禁用真服务）。 */
    internal var historySnapshotSink: ((String, Map<String, String>) -> Unit)? = null
    /**
     * R51（O-105 余半·主体）：参数快照回落——ParamHistoryCache 未命中（LRU 淘汰 / 清缓存 /
     * 命名方案桶不含本键）时，按同一缓存键从 [SqlHistoryManager] 最新历史条目的
     * [SqlHistoryEntry.paramSnapshot] 回落。缓存 50 条 LRU 对历史 200 条，「历史条目→面板
     * 参数态」的桥（十五轮 R51 定义）；快照格式与缓存同源（[buildHistorySnapshot]），
     * 下游应用逻辑（普通值 + `__chk__`/`__fnull__`/`__fev__`）零分叉。
     *
     * 单测态默认禁用真服务（SqlHistoryManager 为 APP 级服务，跨用例残留会串扰既有
     * recall 断言）——测试经 [historySnapshotFallbackSource] 注入替代源；生产链走真服务
     * （接线由 ExplainPanelWiringGuard 同款源码扫描守卫钉住，防测试缝默认化成死代码）。
     */
    internal fun SqlPreviewPanel.historySnapshotFallback(key: String): Map<String, String>? {
        historySnapshotFallbackSource?.let { return it(key) }
        if (ApplicationManager.getApplication().isUnitTestMode) return null
        return runCatching {
            SqlHistoryManager.getInstance().newestParamSnapshot { entry -> this@historySnapshotFallback.matchesSnapshotKey(entry, key) }
        }.getOrNull()
    }

    /** R51：历史条目 → 缓存键（与 [historyCacheKey] 同口径——tableName 空串与 null 同走
     *  `sql:` 指纹路径，recordHistory 落表 `result.tableName ?: ""`，故空串回 null 复算一致）。
     *  T-PresetMethodScope：条目自带作用域时按同一口径追加，否则回落旧键（存量条目）。 */
    internal fun SqlPreviewPanel.entrySnapshotKey(entry: SqlHistoryEntry): String? =
        paramHistoryCache.computeKey(
            entry.tableName.ifEmpty { null },
            entry.sql,
            entry.originScopeKey.ifEmpty { null },
        )

    /**
     * T-PresetMethodScope：带 scope 键也认「同一条 SQL 的旧条目」——升级前落的历史条目没有作用域，
     * 若只做精确匹配，面板参数快照的回写与回落都会与它们失联（旧条目永远不再更新、也回落不到）。
     */
    internal fun SqlPreviewPanel.matchesSnapshotKey(entry: SqlHistoryEntry, key: String): Boolean {
        val entryKey = entrySnapshotKey(entry) ?: return false
        if (entryKey == key) return true
        val legacy = paramHistoryCache.legacyKeyOf(key)
        return legacy != key && entryKey == legacy
    }

    /** R51：参数快照回写——面板参数编辑防抖落库时同步进最新匹配历史条目（会话内闭环：
     *  编辑→flush→历史条目快照；单测态走 [historySnapshotSink] seam，真服务同上禁用）。 */
    internal fun SqlPreviewPanel.updateHistoryEntrySnapshot(key: String, snapshot: Map<String, String>) {
        historySnapshotSink?.invoke(key, snapshot)
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            SqlHistoryManager.getInstance().updateNewestParamSnapshot(
                { entry -> matchesSnapshotKey(entry, key) },
                snapshot,
            )
        }
    }

    /**
     * M36.5.39-A2：持久包装容器——console 组件统一嵌入此容器、容器整体挂入 editorHolder。
     * DBN results refreshed 期间会临时拿走 console 组件，旧的直挂方式（editorHolder.add(console)）
     * 会把 editorHolder 的显示链一并波及（holderShowing=false → 右侧空白）；隔离后 DBN 只动
     * consoleHost 子层，editorHolder/ThemedSplitPane 显示链稳定。
     */
    internal val consoleHost = JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply { isOpaque = false }

    /** M36.5.39-A1：最近一次嵌入 console 的身份 id（identityHashCode）——sameAsPrev 探针判定用。 */
    internal var lastConsoleEmbeddedId: Int? = null

    /** M36.5.44：上次 re-host 时刻（去抖用）——DBN 结果刷新期高频 reparent，需最小间隔抑制死循环。 */
    internal var lastRehostAt: Long = 0L

    /** T-RefreshDbSteal：当前挂接的 rehost HierarchyListener 引用——detach/dispose 时显式移除。
     *  旧实现「监听随组件废弃，无需显式解绑」的假设在共享控制台场景不成立：组件被新面板复用后
     *  旧面板的监听仍存活，parent==null 时会把共享控制台抢回已废弃面板（真机日志 6 连抢实锤）。 */
    internal var rehostListener: java.awt.event.HierarchyListener? = null

    /** T-RefreshDbSteal：去抖窗口内被跳过的释放事件 → 已调度延迟复查（单次标志，防重复调度）。 */
    internal var rehostRetryScheduled = false

    // M36.4：原生编辑器文档引用（参数回填/格式化时使用）
    /** 原生编辑器的文档引用（内嵌后参数变更同步用）。 */
    internal var nativeEditorDoc: com.intellij.openapi.editor.Document? = null

    /**
     * T-AutoFold F5：原生控制台编辑器引用（onNativeEditorReady 时登记）——
     * showSql nativeDoc 支链的程序性折叠应用目标；detachConsole/clear/dispose 时清空，
     * 防止 EditorFactory 泄漏告警。recoverConsole 失联路径经 embedConsole 换新组件后
     * 由下一次 onNativeEditorReady 重新登记。
     */
    internal var nativeConsoleEditor: com.intellij.openapi.editor.Editor? = null

    internal var current: PreviewResult? = null

    /** O-145 W7：结果到达时冻结的渲染模式；提交时不得重读设置冒充当初的渲染事实。 */
    internal var executionDialectRenderMode =
        cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.RenderMode.UNKNOWN
    internal var compactMode = false

    /**
     * T-PreviewDisplayMode：编辑器显示模式。
     *
     * 编辑器文本是「模型派生视图」——参数输入/条件勾选/foreach 次数/压缩切换等任何变更都会
     * [renderSql] 全量重建并整篇覆盖文档，因此**一次性文档变换必被下一次重建抹掉**（早期三个
     * 格式按钮直接 setText 的写法：竖向排列/关键字换行无条件丢失，格式化仅在设置开启且非压缩
     * 时因 [showSql] 里那句 format() 而"看似保留"）。模式改存状态、由 [applyDisplayMode] 在
     * 渲染管线中统一套用，重建后形态保持。
     */
    internal var displayMode = PreviewDisplayMode.NORMAL

    /** T-PreviewDisplayMode：预览编辑器显示模式（见 [displayMode] 的 KDoc）。 */
    enum class PreviewDisplayMode { NORMAL, FORMAT, KEYWORD_WRAP, VERTICAL }

    // O-4：上一次预览的 SQL（会话缓存，供 Compare 并排对比用）
    internal var previousSql: String? = null

    // F-1：JOIN 关系图（仅 SELECT 含 JOIN 时显示标签页）
    internal val diagramPanel = DiagramPanel(project)
    // T-Dgm-04：标签页宿主——原生图框架组件（joinDiagramNative=true 且 Diagrams 插件可用）与
    // 自绘 DiagramPanel 共用此容器按需切换；切换时旧原生编辑器即时 dispose 防 FileEditor 泄漏
    internal val diagramTabHost = javax.swing.JPanel(java.awt.BorderLayout())
    /**
     * T-F8：JOIN 图标签页的加载面——包住整个 [diagramTabHost]，转圈盖在标签页内容之上。
     *
     * 为什么加载面必须在宿主层而不是图面板内部：唯一真正的耗时段是 [updateDiagramTab] 的
     * **后台 schema 富化**（冷缓存按 schema 规模线性），而原生图路径（默认设置
     * `joinDiagramNative = true`，Ultimate 真机默认）下 [showJoinDiagram] 的
     * `diagramTabHost.removeAll()` 会把 [diagramPanel] 摘出层级——面板内部自摆的转圈在默认配置下
     * 根本不可见（审计项 F8：T-UN-14 接的转圈从未出现过，showLoading/hideLoading 全仓零调用点）。
     * 宿主层对原生/自绘两条渲染路径都可见，是唯一能覆盖真机默认路径的加载面。
     *
     * 转圈层由平台 [JBLoadingPanel] 提供（独立 loadingLayer 盖在内容层之上，不替换内容，
     * 2 参构造 startDelayMs=-1 使 startLoading/stopLoading 同步改可见性）。
     * Disposable 挂本面板（[JBLoadingPanel] 的 2 参构造要求父 Disposable）。
     */
    internal val diagramLoadingHost = JBLoadingPanel(BorderLayout(), this)
    internal var currentNativeDiagram: JComponent? = null
    /**
     * T-DbSchemaCompletion：待补刷富化的图（首次富化一点数据源信息都没拿到时记下）。
     *
     * 仅存"上一次富化空手而归"的那个 [PreviewResult]——数据源建连（原生控制台就绪）后据此补刷一次，
     * 刷完置空；新预览替换 current 时旧值自然失配，无需额外清理。
     */
    internal var diagramEnrichRetryTarget: PreviewResult? = null
    // F-45：maintainPage 列信息（仅 <pages> 预览非 null 时显示标签页）
    private var columnInfoPanel: JComponent? = null
    /** 内容区卡片：null=无内容 / "sql"=SQL编辑 / "sql+diagram"=SQL编辑 + JOIN图标签页。 */
    private var contentPane: JBPanel<JBPanel<*>>? = null
    internal var contentTabPane: JTabbedPane? = null

    // M12：动态条件启用集合（勾选状态，元素为 ConditionToggle.id）+ 参数值（按 source 绑定，取代位置绑定）
    internal val enabledIds = mutableSetOf<String>()
    internal val paramValues = mutableMapOf<String, String>()
    // T-M24-04：XML 预览渲染状态（条件开关复用 enabledIds，id = test 表达式原文；
    // T-M26.4：bind 覆盖状态已移除——bind 值由「参数」区输入统一承担）
    internal val xmlForEachCounts = mutableMapOf<String, Int>()
    // O-78 T-ForeachItemRender：foreach 集合值权威状态（collectionExpr → 逗号分隔解析后的值序）——
    // 播种收进 renderSql 帧内按新鲜映射清单进行，值不再依赖「渲染后同步读键清单」
    internal val xmlForEachValues = mutableMapOf<String, List<String>>()
    internal val xmlForEachNulls = mutableSetOf<String>()
    // R22 T-02/T-03：勾选态与 foreach 行的组件登记（快照枚举 + 召回视觉同步；随 rebuild 清场）
    internal val xmlForEachNullBoxes = mutableListOf<JCheckBox>()
    internal val xmlForEachValueFields = mutableListOf<Pair<String, JBTextField>>()
    internal var xmlErrors = listOf<String>()
    /** T-M25.4：最近一次 XML 渲染的参数映射（集合值输入按当前渲染键回填）。 */
    internal var xmlMappings = emptyList<cn.song.mybatisplus.visualizer.xml.ParameterMapping>()
    // T-M25.3：错误信息复制按钮（warning 存在时可见，一键复制完整错误文本）
    internal var errorCopyButton: JButton? = null
    internal var currentWarning: String? = null

    /**
     * T-PSG-04（N-33）：最近一次参数回填的「`?` 位点数 vs 参数条数」对账标注——不等时非 null。
     *
     * 只在 [backfillParamsBySource] 里按串感知位点数与 sources 条数计算（每次回填都重算，不留
     * 陈旧值）；执行前由 [warnOnPlaceholderMismatch] 读走并如实登记日志。**不阻断**任何流程
     * （与我方既有 `${}` 闸门「确认/标注」档一致，不引入拒绝档）。
     */
    internal var placeholderReconciliation: String? = null

    // T-PreviewUx-Pack #2：诚实降级横幅——渲染警示（RENDER_WARNING）与 T-PreviewExecDs
    // 降级链层级提示（DEGRADED）统一收口；降级提示优先级高于渲染警示（见 DegradationBanner.Kind）
    internal val degradationBanner = DegradationBanner().apply {
        // T-DollarInjectMark：✕ 关闭 → 会话级记忆（本 IDE 会话不再弹；测试经 resetDollarMarkForTest 复位）
        onDismiss = {
            dollarMarkDismissedSession = true
            clear(DegradationBanner.Kind.DOLLAR_INJECT)
        }
    }

    /**
     * T-DmlImpactProminence（用户真机反馈：左下角灰字不够明显）：影响行数试算结果独立横幅行——
     * 与降级/渲染警示**并存**（各自占一行，不抢同一显示位），蓝色加粗；失败红色。
     */
    internal val dmlImpactBanner = JBPanel<JBPanel<*>>(java.awt.BorderLayout()).apply {
        isOpaque = false
        isVisible = false
    }
    internal val dmlImpactBannerLabel = JBLabel().apply {
        putClientProperty("html.disable", true)
        border = JBUI.Borders.empty(4, 8, 4, 8)
        foreground = com.intellij.ui.JBColor.namedColor("Link.activeForeground", com.intellij.ui.JBColor.BLUE)
    }

    /** T-DollarInjectMark：最近一次 XML 渲染的 `${}` 直替计数（非 XML 路径恒 0）。 */
    internal var xmlDollarSubstitutions = 0

    /**
     * T-InjectionGate T-IG-02：本面板实例内已确认过 `${}` raw 段执行（会话级记忆）。
     *
     * 同一面板确认一次后不再重复弹（常用 `${orderBy}` 场景的确认疲劳缓解）；记忆随面板实例
     * 存活——面板 dispose 即失效（工具窗换预览内容不重置，与 T-DollarInjectMark 的 IDE 会话级
     * 关闭记忆同向）。@Volatile：执行入口可能在非 EDT 触发（弹窗按钮回调），读写可见性保守处理。
     */
    @Volatile
    internal var dollarRawConfirmedInSession = false

    /**
     * T-PC2-04（D1=B 定案）：本面板实例内已确认过含 UNKNOWN 参数执行（会话级记忆）。
     *
     * 语义与 [dollarRawConfirmedInSession] 同款（同面板确认一次后不再重复弹；执行与 EXPLAIN
     * 共用同一记忆，两条入口是同一道门）；记忆随面板实例存活，面板 dispose 即失效。
     * @Volatile：执行入口可能在非 EDT 触发（弹窗按钮回调），读写可见性保守处理。
     */
    @Volatile
    internal var unknownParamsConfirmedInSession = false

    // P-05：参数历史缓存 + debounce 写回
    internal val paramHistoryCache = ParamHistoryCache()
    /** P-05：debounce 写回调度器（Swing Timer，500ms 延迟，restart 实现防抖）。 */
    internal val historyAlarm = javax.swing.Timer(500) { flushHistoryToCache() }.apply { isRepeats = false }

    /**
     * UX 换源重渲染：last-used 变更后的头部刷新防抖（Swing Timer，120ms，restart 防抖）。
     * 一次执行链可能连写多键（模块级 + 项目级）并触发多次广播，合并为一拍只重组一次头部。
     */
    internal val headerSourceRefreshTimer = javax.swing.Timer(HEADER_SOURCE_REFRESH_DEBOUNCE_MS) {
        refreshHeaderSourceNow()
    }.apply { isRepeats = false }

    // R22 T-04：命名方案下拉（rebuildLeftPanel 随参数区重建；syncSchemeCombo 填充/刷新）
    internal var schemeCombo: JComboBox<String>? = null
    // O-95 S3：绑定数据源指示行标签（rebuildLeftPanel 重建；renderSql 尾部单点刷新）
    internal var bindStateLabel: JBLabel? = null
    /** 程序化填 combo 模型时置位（抑制 addActionListener 的用户切换语义）。 */
    internal var schemeSyncingCombo = false
    /** 上次已同步到下拉的激活方案名——对话框回调时比对判定「激活方案被对话框切走」。 */
    internal var lastShownActiveScheme: String = ParamHistoryCache.DEFAULT_SCHEME
    /** 管理方案对话框（MODELESS，单实例防堆叠；关闭后由可见性判断重建）。 */
    internal var schemeManagerDialog: ParamSchemeManagerDialog? = null

    // O-88：预设行折叠件（平台标准 HideableTitledPanel，Run Config「Before launch」区同款）。
    // 折叠态=companion 静态字段（声明见伴生对象 [Companion.presetSectionCollapsed]）：
    // 跨预览重建且跨面板实例保留——gutter 换语句走「关旧弹窗、开新面板实例」（真机走查实证：
    // 实例字段在新实例归零、折叠态丢失），静态化后新实例初始态继承；不跨重启持久化、无设置开关。
    /**
     * 当前预设折叠件实例——[HideableTitledPanel] 公共面无状态变更监听器（装饰器私有），
     * 用户点标题折叠的变化以「重建时读 [HideableTitledPanel.isExpanded] 回写」的懒惰捕获跟进；
     * 折叠件随 paramPanel.removeAll 摘除后实例仍持有最后视觉态，跨 clear/rebuild 均可恢复。
     */
    internal var presetFold: HideableTitledPanel? = null

    // T-Perf-04：参数击键路径 renderSql 防抖（Swing Timer，150ms，模式同 historyAlarm）
    /** 待执行的渲染任务（最后一次调度者生效——防抖语义）。 */
    private var pendingRender: (() -> Unit)? = null
    private val renderDebounceTimer = javax.swing.Timer(RENDER_DEBOUNCE_MS) { flushDebouncedRender() }.apply { isRepeats = false }

    /**
     * 调度防抖渲染： keystroke 路径用——150ms 停顿后只渲染一次，最终结果与逐键全量渲染一致。
     *
     * 单元测试模式（isUnitTestMode，headless 无 EDT 泵）同步执行：既有测试依赖「改参数值 →
     * 文档即时更新」的同步语义，且挂起的 Timer 在后续测试泵 EDT 时迟到触发会触碰已销毁面板
     * 并触发 TransactionGuard "Write-unsafe context" 日志断言（交叉污染）。生产行为不变。
     */
    internal fun scheduleRender(task: () -> Unit) {
        pendingRender = task
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app != null && app.isUnitTestMode) {
            flushDebouncedRender()
            return
        }
        renderDebounceTimer.restart()
    }

    /** Timer 动作：执行最后调度的渲染任务（EDT）。 */
    private fun flushDebouncedRender() {
        val task = pendingRender
        pendingRender = null
        task?.invoke()
    }

    init {
        // 顶部元信息行 + 工具栏
        // T-PreviewTopOverlap F1：metaLabel 从 WEST 迁 CENTER——BorderLayout 的 WEST/EAST 各按
        // 理想宽整块贴边绝不收缩，两偏好宽之和超容器宽时直接互相重叠（用户真机截图实锤：元信息
        // 文字压进按钮行）；CENTER 只拿剩余宽度，与 EAST 结构性零重叠。宽度不足由 F3 截断兜底。
        // （合并曾取 dev T-GifHeader 两行布局为基底，用户定案「更多」折叠菜单模式——单行方案转正。）
        metaLabel.border = JBUI.Borders.empty(0, 8, 4, 8)
        topRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(metaLabel, BorderLayout.CENTER)
            toolbarPanel = toolbar()
            add(toolbarPanel, BorderLayout.EAST)
            // M36.5：收边距与原生工具行对齐（上 6→4，右 8→8 保留）
            border = JBUI.Borders.empty(4, 8, 2, 8)
        }
        // T-PreviewUx-Pack #2：诚实降级横幅（面板内嵌非模态提示，不算弹窗）——渲染警示 /
        // T-PreviewExecDs 降级链层级提示统一收口（触发即显示、恢复即隐藏，不占高度）
        // T-DevAcceptFix ①（20261006 验收）：绑定指示行迁出参数区——挂「更多」按钮（工具栏 EAST
        // 末位）正下方右对齐常驻薄行，与右侧内嵌控制台头部数据源显示相邻；init 一次构建常驻，
        // 不随参数区重建（常驻语义不再依赖参数区是否建行），refreshBindStateRow 单点刷新链不变。
        val northHost = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(topRow, BorderLayout.NORTH)
            add(buildBindStateRow(), BorderLayout.CENTER)
            // T-DmlImpactProminence：降级横幅与影响行数结果横幅上下各占一行（并存不抢位）
            dmlImpactBanner.add(dmlImpactBannerLabel, BorderLayout.CENTER)
            add(
                JBPanel<JBPanel<*>>(BorderLayout()).apply {
                    isOpaque = false
                    add(degradationBanner, BorderLayout.NORTH)
                    add(dmlImpactBanner, BorderLayout.CENTER)
                },
                BorderLayout.SOUTH,
            )
        }
        add(northHost, BorderLayout.NORTH)
        // T-PreviewTopOverlap F3：宽度预算截断跟随尺寸变化（首显 + 拉伸/缩窄；宽度未变短路）
        topRow.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = applyMetaTruncation()
            override fun componentShown(e: java.awt.event.ComponentEvent) = applyMetaTruncation()
        })

        // T-StackHoverUi：调用栈悬停弹层（调试器 Frames 风格）——meta 行悬停承接完整调用链，
        // 弹层生命周期随面板 Disposer 树级联释放（弹窗关闭即清）
        Disposer.register(this, stackHover)
        stackHover.attach(metaLabel)

        // 中部左右分栏：参数输入 | SQL 编辑器/原生控制台
        // T-M26.3：按钮回挂分区标题行右侧（conditionSectionHeader/batchSectionHeader），
        // 左面板即参数滚动区（T-M26.2 的固定操作行已拆除——长文本按视口截断后按钮不再被推出视口）
        val split = OnePixelSplitter(false, "mpvs.preview.splitter", 0.3f).apply {
            firstComponent = paramScroll
            secondComponent = editorHolder
            // T-PreviewBgFit（问题一）：恢复 T-UiNative P0a 换组件时丢掉的容器背景——
            // 原 ThemedSplitPane 显式设过 `background = UIUtil.getPanelBackground()`，OnePixelSplitter
            // 版本漏了它，左侧透明参数区于是透出分栏自身背景（深色主题近黑）。
            // T-DarkTheme：命名色按主题解析，浅/深主题各自正确（主题切换由 LafManagerListener 触发重绘）。
            background = com.intellij.util.ui.UIUtil.getPanelBackground()
        }
        // M36.5：删除 verticalSplit 下半区——控制台已内嵌右侧，底部仅保留状态行
        //（resultPanel SOUTH，无内容时隐藏不占高度，错误/执行状态正常显示）
        add(split, BorderLayout.CENTER)
        add(resultPanel, BorderLayout.SOUTH)

        // T-M26.6：注册格式化快捷键——组件级 custom shortcut 命中即消费按键，
        // 不与 IDE 全局 Reformat Code 双触发（预览语言无 formatter，全局动作本不可用）
        reformatAction.registerCustomShortcutSet(
            CustomShortcutSet(*reformatShortcuts()),
            this,
        )

        // T-DarkTheme：主题切换时重绘（工具窗口模式下实时响应；命名色自动更新，仅需触发重绘）
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { lookAndFeelChanged() })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            PluginSettings.SETTINGS_CHANGE_TOPIC,
            cn.song.mybatisplus.visualizer.config.SettingsChangeListener {
                val app = ApplicationManager.getApplication()
                // 设置应用通常在 EDT；后台广播同步收口，避免开脱敏后仍能复制旧原文。
                val update = Runnable { if (!previewDisposed) refreshShardingRouteDisplay() }
                if (app.isDispatchThread) update.run() else app.invokeAndWait(update)
            },
        )
        // UX 换源重渲染：last-used 数据源变更（执行/换源写入记忆）→ 存活的预览面板只刷新头部
        // 绑定行与方言徽章，不重渲染 SQL、不新增常驻轮询。连接绑定本面板 Disposable，
        // dispose 时自动注销（与上方设置 Topic 同款）。
        project?.messageBus?.connect(this)?.subscribe(
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.LAST_USED_CHANGE_TOPIC,
            cn.song.mybatisplus.visualizer.execute.LastUsedChangeEvents.Listener {
                scheduleHeaderSourceRefresh()
            },
        )

        // F-1：注册图集面板到 Disposer 树（级联释放后台渲染任务）
        Disposer.register(this, diagramPanel)
        // T-Dgm-04：标签页实际展示 diagramTabHost（内嵌原生组件或 diagramPanel）
        diagramTabHost.isOpaque = false
        diagramTabHost.add(diagramPanel, java.awt.BorderLayout.CENTER)
        // T-F8：加载面在宿主层（标签页内容 = 加载面板 → diagramTabHost），并把它交给图面板出图
        //（面板自身的 contentLayer 在原生路径下不可见，见 diagramLoadingHost KDoc）
        diagramLoadingHost.add(diagramTabHost, BorderLayout.CENTER)
        diagramPanel.bindHostLoadingPanel(diagramLoadingHost)

        // T-M36.6-03：预热线程提前创建临时 SQL 文件 + VFS refresh + PSI 解析（后台线程慢操作合法），
        // createEditor（EDT）优先消费预热产物——243 平台 EDT/EDT-ReadAction 均禁止慢操作
        //（SlowOperations ERROR_EDT/ERROR_RA），面板打开期的 refreshAndFindFileByIoFile SEVERE 由此消除
        if (project != null) {
            prewarmPreviewTempFile()
        }
    }

    /** T-M36.6-03 / T-PreviewFlash：后台预热预览临时文件（占位空内容，createEditor 就绪后按实际 SQL 同步）。 */
    internal lateinit var modeButton: JButton

    /**
     * T-M53-II：`modeButton` 的就绪态读取口。
     *
     * 组合式重构把显示模式链搬进同包扩展文件后，`::modeButton.isInitialized` 这类**后备字段**引用
     * 在声明类外不可用（编译器红：backing field not accessible）。把判定留在类内，扩展侧按
     * `modeButtonOrNull()?.text = …` 调用——语义与原先逐字相同（同一 isInitialized 判定 + 同一赋值）。
     */
    internal fun modeButtonOrNull(): JButton? = if (::modeButton.isInitialized) modeButton else null

    /**
     * 顶部工具栏：复制错误（有警示时可见，永不折叠——错误反馈链路一环）/ 压缩多行切换 / 格式化 /
     * 关键字换行 / 竖向排列 / 执行计划 / 刷新 / 更多（T-PreviewTopOverlap F2：导入参数/差异对比/分享
     * 三个低频动作折叠进「更多」JBPopup 菜单；执行 SQL / 复制 SQL 在弹窗底部按钮区，M17.4。
     * T-UiCoord F1：回滚 SQL 亦折叠进「更多」，直显按钮 8 → 7 枚）。
     */
    internal var diagramRenderForTest: ((DiagramModel) -> Unit)? = null

    /**
     * 释放并移除当前原生图组件（内部 FileEditor 经 clientProperty 定位后 dispose）。
     *
     * T-EmbedFkOrder F2：本方法**不**清理 JOIN 虚拟外键注册表——它可能在建新图之前被调用（此时
     * 新图尚未登记，清了也无害），也可能在换图/卸载时被调用（此时若清会把刚登记的抹掉）。
     * 注册表的清理职责在明确语义点（三点口径）：登记前自带 clear、[unloadJoinDiagram]（无 JOIN/
     * 面板清空）与面板 [dispose]（T-PS4-14④）。
     */
    internal var formatCacheKey: String? = null
    internal var formatCacheValue: String? = null

    var onClosePanelRequested: (() -> Unit)? = null

    /** 清空展示区。 */
    internal var bindStateNameProvider: (() -> String?)? = null


    internal val checkBoxFont: Font by lazy { JCheckBox().font }


    /** T-M26.3：复选框标签截断——test 表达式等长文本超上限时中段省略显示（tooltip 原文由调用方处理）。 */
    /** T-M26.3：复选框标签截断——test 表达式等长文本超上限时中段省略显示（tooltip 原文由调用方处理）。 */
    override fun removeNotify() {
        super.removeNotify()
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] panel removeNotify: embedded=${System.identityHashCode(embeddedConsole)}")
        releaseEditor()
    }

    /**
     * T-DarkTheme：释放主题监听（messageBus.connect(this) 随 Disposable 销毁自动取消订阅）。
     * 工具窗口模式下面板可能长期存活，由 [SqlPreviewDialog] 或工具窗口工厂调用释放。
     */
    override fun dispose() {
        // O-88 复验修复：dispose 链捕获折叠态——懒惰回写（buildSchemeRow 开头）只在同实例触发参数区
        // 重建时执行，真机「收起→取消→换语句」走 DialogWrapper.dispose→Disposer 级联释放面板，
        // 全程无 buildSchemeRow 调用，静态字段从未写 true。面板自身是 Disposable 且经
        // Disposer.register(宿主 disposable, this) 挂树（弹窗见 SqlPreviewDialog，工具窗同惯例），
        // 宿主任何关闭路径（取消/Alt+F4/父级释放）级联触发本方法——在此读当前折叠件视觉态回写
        // 静态字段（真值语义=取反，与懒惰捕获行一致）。懒惰捕获行保留，覆盖刷新等重建路径。
        presetFold?.let { presetSectionCollapsed = !it.isExpanded() }
        // T-RefreshDbSteal：面板废弃——移除 rehost 监听（共享控制台可能已被新面板接管，
        // 本面板的监听不得残活抢组件）；embedConsole 对 null 安全
        runCatching { detachConsole() }
        // T-AutoFold F5：面板废弃——控制台编辑器引用一并清空（防 EditorFactory 泄漏告警）
        // T-PreviewLineCommentDash O-134：面板销毁减计数（detachConsole 已覆盖常规路径，此处兜底直清分支）
        nativeConsoleEditor?.let(EditorCommentShortcuts::uninstall)
        // UI025：摘除编辑器级错列标注通道（监听 + RangeHighlighter）
        nativeConsoleEditor?.let(cn.song.mybatisplus.visualizer.ui.annotate.PreviewSchemaHighlighter::uninstall)
        nativeConsoleEditor = null
        // T-M36.6-03：面板销毁——晚到的预热线程产物作废（预热线程自查后删除临时文件）
        previewDisposed = true
        // T-PerfSweep6 T-PS6-04③（性能报告发现 4）：面板 dispose 补一次同款临时文件回收。
        // 此前回收实际只由 removeNotify → releaseEditor 触发，而三种宿主（弹窗 / 工具窗 / 嵌入控制台）
        // 下 dispose 与 removeNotify 的先后未穷尽——dispose 先到时残留文件无人回收。本方法幂等，
        // 两条路径都走到也无副作用（集合已清空即零动作）。
        recoverPreviewTempFiles()
        // T-StabilityTail：解除预热取消门在项目 earlyDisposable 树上的登记——面板先于项目销毁时
        // 不留指向本面板的引用（项目先销毁时该 gate 已被平台回收，Disposer 对已销毁件幂等）
        prewarmLifecycleGate?.let { runCatching { Disposer.dispose(it) } }
        prewarmLifecycleGate = null
        // T-Perf-04：防抖/历史 Timer 随面板停止（防晚到 flush 触碰已销毁面板状态）
        renderDebounceTimer.stop()
        pendingRender = null
        // UX 换源重渲染：头部刷新防抖同样随面板停止（晚到的记忆变更不再触碰已销毁面板）
        headerSourceRefreshTimer.stop()
        // T-DegradedPreviewUx P1：降级宽限定时器同样随面板停止（到点不得再弹横幅）
        cancelDegradedGraceTimer()
        historyAlarm.stop()
        // T-Perf-16：释放 DataSourceSelector 单例滞留引用（弹窗关闭此前不走 clear 按钮路径——
        // prepared/shared/pendingOnConsole 强引用已关闭面板与原生控制台，堆转储滞留整棵面板树）
        SqlExecutor.releaseForPanel()
        // T-PS4-14④：面板 dispose 是 JOIN 虚拟外键注册表的最后语义回收点（T-EmbedFkOrder
        // 「显式语义点回收」三点口径：登记前 registerJoinForeignKeys 自带 clear + 无 JOIN 路径
        // unloadJoinDiagram + 面板 dispose）——最后一张 JOIN 图建图的登记此前驻留到下次建图，
        // 残留期 JoinVirtualFkProvider EP 会给用户自开的 Database 图补出旧连线。runCatching
        // 照邻居清理件风格（释放失败不阻断 dispose 链）
        runCatching {
            cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport.clearRegisteredFk()
        }
        // messageBus 连接经 connect(this) 绑定本 Disposable，Disposer.dispose 时自动取消订阅
        Disposer.dispose(this)
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用）
    // ------------------------------------------------------------------

    internal fun editorForTest(): Editor? = editor

    /** T-PSG-04 测试缝：最近一次回填的「位点数 vs 参数条数」对账标注（相等为 null）。 */
    internal fun placeholderReconciliationForTest(): String? = placeholderReconciliation

    /** T-PreviewConsoleFmt 测试缝：模拟控制台文档已就绪（nativeDoc 指向该文档）。 */
    internal fun markNativeDocForTest(doc: com.intellij.openapi.editor.Document) {
        nativeEditorDoc = doc
    }

    /** T-PreviewConsoleFmt 测试缝：挂载后回填渲染的等价入口。 */
    internal fun renderSqlForTest() = renderSql()
    /** T-AutoFold F5：登记的原生控制台编辑器引用（支链①接入断言用）。 */
    internal fun nativeConsoleEditorForTest(): Editor? = nativeConsoleEditor
    internal fun psiFileForTest(): PsiFile? = psiFile

    /**
     * T-PreviewDocLock2 F3 回归守护：预热载体（异步完成态）——测试据此有界等待预热出结果
     * （headless 无 EDT 泵，预热跑在池线程），再驱动真实消费链断言分支走向。
     */
    internal fun prewarmAwaitForTest(): PrewarmAwait<Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>>? =
        prewarmAwait

    /**
     * T-PreviewDocLock2 F3 回归守护：预热产物字段（[publishPrewarmProduct] 写入、消费即置 null）。
     * 断言点：①预热完成后字段与 await 交付值**同实例**（发布侧不变式）；②消费后字段被清空（一次性语义）。
     */
    internal fun prewarmedTempFileForTest(): Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? =
        prewarmedTempFile

    /** T-PreviewDocLock2 F3 回归守护：释放编辑器（**不**触发 [clear] 的重预热）——「产物已被消费」降级分支断言用。 */
    internal fun releaseEditorForTest() = releaseEditor()

    /**
     * T-PerfSweep6 T-PS6-04 测试缝：对给定目录执行一次目录级兜底清扫（生产入口在
     * [projectPreviewFile] 首次解析预热目录时）。测试用临时目录即可直测「按年龄/条数回收」
     * 与「非本插件命名零删除」两条判据。
     */
    internal fun sweepStalePreviewFilesForTest(dir: java.io.File) = sweepStalePreviewFiles(dir)
    /** T-PrewarmLateRecover F1：当前是否处于「降级文档」态（换装判据里的身份字段）。 */
    internal fun degradedDocumentForTest(): com.intellij.openapi.editor.Document? = degradedPreviewDocument

    /**
     * T-ErrorLocateUsability T-ELU-01（O-49）：错误定位动作的**显隐判据**缝——
     * 锚点可解析 → 非 null（按钮出现）；不可解析 → null（按钮不出现）。
     * 这是本任务修复点的直接可断言面（改前恒非 null）。
     */
    internal fun errorLocateActionForTest(): (() -> Unit)? = errorLocateAction()

    /** T-ELU-01：富错误回调缝（端到端断言「锚点不可解析 → 定位按钮不出现」）。 */
    internal fun showErrorRichWithLocateForTest(info: cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo) =
        showErrorRichWithLocate(info)

    /** T-PrewarmLateRecover F1：插件最后一次写入编辑器文档的文本（换装判据里的「用户没动过」）。 */
    internal fun lastRenderedSqlForTest(): String? = lastRenderedSql

    /**
     * T-PrewarmLateRecover F1：驱动迟到产物通道——与「预热线程发布时发现消费侧已放弃
     * （`PrewarmAwait.complete` 返回 false）」走同一条代码路径（[tryUpgradeToLateProduct]）。
     */
    internal fun onLatePrewarmProductForTest(product: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>) =
        tryUpgradeToLateProduct(product)

    /** T-PrewarmLateRecover F2：横幅「重试」动作入口（等价于点击横幅上的动作）。 */
    internal fun retryPrewarmForTest() = retryPrewarm()

    /** T-DegradedPreviewUx P1：宽限定时器是否在途（瞬时降级的「尚未披露」态）。 */
    internal fun degradedGracePendingForTest(): Boolean = degradedGraceTimer?.isRunning == true

    /** T-DegradedPreviewUx P1：模拟宽限期到点（headless 无真实 3s 等待）。 */
    internal fun fireDegradedGraceForTest() = flushDegradedGrace()

    /** T-DegradedPreviewUx P1/P2：按原因披露横幅的入口（等价于装配降级链的调用）。 */
    internal fun showDegradedBannerForTest(reason: PreviewDegradationReason) = showDegradedBanner(reason)

    internal fun currentForTest(): PreviewResult? = current
    internal fun compactForTest(): Boolean = compactMode
    /** T-PreviewDisplayMode：当前显示模式（参数变更后保持性断言用）。 */
    internal fun displayModeForTest(): PreviewDisplayMode = displayMode
    /** M36.5.2：内嵌控制台组件（同实例防抖/替换断言用）。 */
    internal fun embedConsoleForTest(component: JComponent) = embedConsole(component)
    internal fun embeddedConsoleForTest(): JComponent? = embeddedConsole
    /** M36.5.39-A2：持久包装容器 + 显示链宿主（ConsoleHostTest 断言用）。 */
    internal fun consoleHostForTest(): JComponent = consoleHost
    internal fun editorHolderForTest(): JComponent = editorHolder
    /** T-UxOnboard T-UX-02：预览编辑器空态提示文案（placeholder 接线断言用）。 */
    internal fun previewEmptyHintForTest(): String = previewEmptyHint
    /** M36.5.39-A1：最近一次嵌入 console 的身份 id（sameAsPrev 探针断言用）。 */
    internal fun lastConsoleEmbeddedIdForTest(): Int? = lastConsoleEmbeddedId
    /** T-RefreshDbSteal F3：摘除控制台 + 移除 rehost 监听（生命周期断言用）。 */
    internal fun detachConsoleForTest() = detachConsole()
    /** T-RefreshDbSteal F4：所有权判定（卫队断言用，见 RefreshDbStealTest）。 */
    internal fun consoleAttachedToMeForTest(): Boolean = consoleAttachedToMe()
    /** T-RollbackSql：执行状态面板（executeAdhocSql 结果反馈断言用）。 */
    internal fun resultPanelForTest(): SqlResultPanel = resultPanel

    /**
     * T-F8：JOIN 图加载态接线探针（测试用）——转发到 [DiagramPanel.loadingProbeForTest]。
     *
     * 接线用例据此断「进入加载态 → 渲染 → 退出加载态」的时序（时序为何必须如此见
     * [updateDiagramTab] 与 [showJoinDiagram] 的 KDoc）。
     */
    internal fun diagramLoadingProbeForTest(probe: (Boolean) -> Unit) {
        diagramPanel.loadingProbeForTest = probe
    }

    /** T-F8：JOIN 图面板（「停转圈时图已渲染」的断言入口）。 */
    internal fun diagramPanelForTest(): DiagramPanel = diagramPanel

    /** T-F8：当前是否走原生图路径（「停转圈时图已换入」的另一分支——原生路径不经过 [diagramPanel]）。 */
    internal fun hasNativeDiagramForTest(): Boolean = currentNativeDiagram != null

    /**
     * T-TS-05：JOIN 图标签页的加载面（测试断言用）——标签页内容必须**是它**。
     *
     * 与 [diagramPanelForTest] 的 `isLoadingForTest` 互补：后者只断「加载态为真」，把标签页内容改回
     * [diagramTabHost]（加载面不在层级里）也照样为真；真机默认原生图路径下 [diagramPanel] 被
     * `removeAll()` 摘出层级，转圈根本画不出来。故需按对象身份断结构。
     */
    internal fun diagramLoadingHostForTest(): JBLoadingPanel = diagramLoadingHost

    /** T-TS-05：内容区标签页容器（测试断言用；未切到标签页模式时为 null）。 */
    internal fun contentTabPaneForTest(): javax.swing.JTabbedPane? = contentTabPane

    /** T-RefreshDbSteal F3：当前挂接的 rehost 监听是否仍在组件上（解绑断言用）。 */
    internal fun rehostListenerAttachedForTest(): Boolean {
        val console = embeddedConsole ?: return false
        val listener = rehostListener ?: return false
        return console.hierarchyListeners.contains(listener)
    }

    // ------------------------------------------------------------------
    // F-46：来源位置（来源列默认值）
    // ------------------------------------------------------------------

    fun setSourceContext(context: cn.song.mybatisplus.visualizer.context.PreviewSourceContext?) {
        sourceModuleKey = context?.canonicalModuleKey
    }

    /** 设置当前代码位置（文件名:行号），来源列默认显示此值。 */
    fun setSourceLocation(loc: String?) { sourceLocation = loc }

    /** 当前代码位置（文件名:行号），null 表示未知。 */
    fun currentSourceLocation(): String? = sourceLocation

    // ------------------------------------------------------------------
    // O-4：预览弹窗 UX 增强（外部组件访问面板状态）
    // ------------------------------------------------------------------

    /** O-4：当前编辑器 SQL 文本（含手动编辑与参数回填）；编辑器未创建时返回 null。 */
    fun editorDocumentText(): String? = editor?.document?.text

    /** O-4：当前预览的未解析参数来源列表（与 SQL 中 `?` 按出现顺序对应）。 */
    fun unresolvedParams(): List<String> = current?.unresolvedParams ?: emptyList()

    /**
     * T-JpaParamOrder F3：复制可执行 SQL 的回填 sources——JPA 占位符名序非空时优先
     * （按名回填口径），否则回落 [unresolvedParams]，与面板渲染（renderSql else 分支）同口径。
     */
    fun backfillSources(): List<String> {
        val cur = current ?: return emptyList()
        return cur.placeholderParamNames.ifEmpty { cur.unresolvedParams }
    }

    /** O-4：当前参数值快照（source → 用户输入值）。 */
    fun paramValuesSnapshot(): Map<String, String> = paramValues.toMap()

    /**
     * T-InjectionGate T-IG-01：当前预览语句是否含 `${}` 原文拼接段（[PreviewResult.hasDollarRawSegments]
     * 面板侧读口——复制可执行 SQL 的产物标记注释据此追加）。
     */
    fun hasDollarRawSegments(): Boolean = current?.hasDollarRawSegments == true

    /** F-8：当前预览的表名（可 null）。 */
    fun currentTableName(): String? = current?.tableName

    /** F-8：当前预览的方言名称（可 null）。 */
    fun currentDialect(): String? = current?.effectiveDialect?.name

    /**
     * NEW-21：当前预览的**语句模型**（Wrapper/XML/JPA 预览链的解析产物；可 null）。
     *
     * 供信创兼容性体检逐方言**真渲染**——只有文本时体检只能按文本证据判，分页/引用形态判不出差异。
     * P-5 结论：多方言渲染本身不需要 PSI/工程上下文，此处只是把已解析好的模型取出来。
     */
    fun currentStatement(): cn.song.mybatisplus.visualizer.model.SqlStatement? =
        current?.previewModel?.statement ?: current?.diagramStatement

    internal fun paramFieldForTest(index: Int): JBTextField? = paramFields.getOrNull(index)
    /** T-M24-04：元信息标签文本（警告断言用）。 */
    internal fun metaLabelForTest(): String = metaLabel.text

    /**
     * T-SourceJumpFunnel §3.3：元信息标签 tooltip（「悬停见完整链」断言用）。
     *
     * headless 下无法产生真实鼠标悬停，但 Swing 悬停展示的正是本属性文本，故断言它即锁定
     * 悬停内容契约（真实悬停动作本身不在自动化范围）。
     */
    internal fun metaTooltipForTest(): String? = metaLabel.toolTipText

    /** T-StackHoverUi：当前喂给悬停弹层的栈快照（接线断言用——tooltip 不再承载栈文本）。 */
    internal fun stackDigestForTest(): String? = stackHover.currentDigestForTest()

    /** T-StackBizFrame S3：当前喂给弹层标题的线程名（接线断言用）。 */
    internal fun stackThreadNameForTest(): String? = stackHover.currentThreadNameForTest()
    /** T-M25.3：错误复制按钮（可见性断言用）。 */
    internal fun errorCopyButtonForTest(): JButton? = errorCopyButton

    // ------------------------------------------------------------------
    // T-PreviewTopOverlap F4：顶部行布局 / 折叠菜单测试访问
    // ------------------------------------------------------------------

    /** 顶部行（metaLabel + 工具栏宿主，布局断言用）。 */
    internal fun topRowForTest(): JBPanel<JBPanel<*>> = topRow

    /** 工具栏（直显按钮集合断言用）。 */
    internal fun toolbarForTest(): JBPanel<JBPanel<*>> = toolbarPanel

    /** 「更多」折叠菜单项文案（顺序与弹层一致，断言用；show 依赖真实屏幕故只暴露文案与 step）。 */
    internal fun moreActionsForTest(): List<String> = moreActions().map { it.first }

    /** 「更多」折叠菜单 step（onChosen 委托断言用，无需真实弹层）。 */
    internal fun moreActionsStepForTest(): com.intellij.openapi.ui.popup.util.BaseListPopupStep<String> =
        moreActionsStep(moreActions())

    /**
     * N4：构造 step 的注入口——真实三项动作都会弹模态（单测禁弹），故用无副作用的替身项断言
     * 「命中真实项时**不同步**执行动作」这条新契约。
     */
    internal fun moreActionsStepWithForTest(items: List<Pair<String, () -> Unit>>): com.intellij.openapi.ui.popup.util.BaseListPopupStep<String> =
        moreActionsStep(items)

    /** F3：宽度预算截断手动触发（headless 无 componentResized，测试直调）。 */
    internal fun applyMetaTruncationForTest() = applyMetaTruncation()

    /**
     * T-M19.4-01：第 index 个普通参数行的标签（JBLabel）——布局断言用
     * （宽度自适应/tooltip 全名）。按 paramPanel 中「含 JBLabel 的 JBPanel 行」定位，
     * 与 paramFields 的顺序一致（普通参数行按 source 顺序追加）。
     * R22 T-04：方案行同为「含 JBLabel 的 JBPanel」且插在头部，按 [SCHEME_ROW_NAME]
     * 排除——访问器契约是「普通参数行」，不含方案行（否则序号错位、断言取到方案行标签）。
     * O-95 S3：绑定指示行同形状（含 JBLabel 的 JBPanel），按 BIND_STATE_ROW_NAME 同口径排除。
     */
    internal fun paramRowLabelForTest(index: Int): JBLabel? = paramPanel.components
        .filterIsInstance<JBPanel<*>>()
        .filter {
            row -> row.name != SCHEME_ROW_NAME && row.name != BIND_STATE_ROW_NAME && row.components.any { it is JBLabel }
        }
        .getOrNull(index)
        ?.components?.filterIsInstance<JBLabel>()?.firstOrNull()

    /** T-HibDualCapture：第 index 个普通参数框的 tooltip（`#source = 值`），供长值兜底断言。 */
    internal fun paramFieldTooltipForTest(index: Int): String? = paramFields.getOrNull(index)?.toolTipText
    /** T-HibDualCapture：第 index 个普通参数框的光标位置（应钉 0，视口停在值头部）。 */
    internal fun paramFieldCaretForTest(index: Int): Int = paramFields.getOrNull(index)?.caretPosition ?: -1
    /** T-PT-02：第 index 个普通参数框的占位提示（emptyText，按类型类别）。 */
    internal fun paramEmptyTextForTest(index: Int): String? = paramFields.getOrNull(index)?.emptyText?.text
    /** T-PT-02：第 index 个普通参数行的校验警示文案（无警示返回 null；不阻塞渲染）。 */
    internal fun paramWarningForTest(index: Int): String? =
        paramWarnLabels.getOrNull(index)?.takeIf { it.isVisible }?.toolTipText
    internal fun conditionCheckboxForTest(index: Int): JCheckBox? = conditionCheckBoxes.getOrNull(index)
    internal fun conditionValueFieldForTest(index: Int): JBTextField? = conditionValueFields.getOrNull(index)
    /**
     * R49 测试缝：第 index 个 Wrapper 条件行尾的求值结果标注（按**条件行下标**与
     * [conditionCheckboxForTest] 对齐；该行不可标注时为 null，下标不跳位）。
     */
    internal fun conditionOutcomeLabelForTest(index: Int): JBLabel? = conditionOutcomeLabels.getOrNull(index)
    /** T-M23-04：第 index 个批量列级勾选复选框（toggle 列按 BatchModel 顺序）。 */
    internal fun batchCheckboxForTest(index: Int): JCheckBox? = batchCheckBoxes.getOrNull(index)

    /** T-M23.2 / T-SelToggle：批量「参数列」区全选/全不选切换按钮。 */
    internal fun batchToggleSelectButtonForTest(): JButton? = batchToggleSelectButton
    internal fun enabledIdsForTest(): Set<String> = enabledIds.toSet()
    internal fun toggleSelectButtonForTest(): JButton? = toggleSelectButton
    internal fun clearButtonForTest(): JButton? = clearButton
    internal fun batchClearButtonForTest(): JButton? = batchClearButton

    /** T-PreviewBgFit 测试缝：左侧参数滚动区（背景/不透明断言用）。 */
    internal fun paramScrollForTest(): JBScrollPane = paramScroll

    /** T-M26.1→T-UiNative：左右分栏（平台 OnePixelSplitter，原生分隔条主题融合 + 比例记忆）。 */
    internal fun splitPaneForTest(): OnePixelSplitter? {
        fun find(c: java.awt.Container): OnePixelSplitter? {
            for (child in c.components) {
                if (child is OnePixelSplitter && !child.orientation) return child
                if (child is java.awt.Container) find(child)?.let { return it }
            }
            return null
        }
        return find(this)
    }
    /** T-M26.6：格式化快捷键 action（注册断言 + 触发格式化测试用）。 */
    internal fun reformatActionForTest(): AnAction = reformatAction
    /** P-05：参数导入入口（测试调用用，绕过剪贴板）。 */
    internal fun importParamsForTest(params: Map<String, String>) = importParams(params)
    /** P-05：参数历史缓存（大小断言用）。 */
    internal fun paramHistoryCacheForTest(): ParamHistoryCache = paramHistoryCache

    /** T-PreviewUx-Pack #2：诚实降级横幅（显隐/文案/类别断言用）。 */
    internal fun degradationBannerForTest(): DegradationBanner = degradationBanner
    /** T-DmlImpactProminence：影响行数结果横幅（显隐/文案断言用）。 */
    internal fun dmlImpactBannerForTest(): JBPanel<JBPanel<*>> = dmlImpactBanner
    internal fun dmlImpactBannerTextForTest(): String = dmlImpactBannerLabel.text
    /** P-05：当前 paramValues 快照（断言用）。 */
    internal fun paramValuesForTest(): Map<String, String> = paramValues.toMap()
    /** P-05：强制同步刷写历史缓存（测试用，绕过 debounce Timer）。 */
    internal fun flushHistoryForTest() = flushHistoryToCache()
    /** P-05.1：当前 paramValues 序列化为 JSON（测试调用用）。 */
    internal fun serializeParamsToJsonForTest(): String = serializeParamsToJson()

    /** R22 T-02/T-03 测试缝：历史快照构建（含勾选态/foreach 权威态特殊键）。 */
    internal fun buildHistorySnapshotForTest(): Map<String, String> = buildHistorySnapshot()
    /** R22 T-02/T-03 测试缝：召回（含勾选态/foreach 权威态还原）。 */
    internal fun recallFromHistoryForTest() = recallFromHistory()
    /** R22 T-02 测试缝：可见勾选框选中态（按 client property id/expr 查）。 */
    internal fun toggleCheckSelectedForTest(id: String): Boolean? =
        (conditionCheckBoxes + batchCheckBoxes).firstOrNull { it.getClientProperty(PROP_TOGGLE_ID) == id }?.isSelected
    /** R22 T-02 测试缝：foreach null 勾选框选中态。 */
    internal fun forEachNullSelectedForTest(expr: String): Boolean? =
        xmlForEachNullBoxes.firstOrNull { it.getClientProperty(PROP_FOREACH_NULL) == expr }?.isSelected
    /** R22 T-03 测试缝：foreach 值字段文本。 */
    internal fun forEachValueFieldTextForTest(expr: String): String? =
        xmlForEachValueFields.firstOrNull { it.first == expr }?.second?.text
    /** R22 T-02/T-03 测试缝：权威状态集（召回断言用）。 */
    internal fun forEachNullsForTest(): Set<String> = xmlForEachNulls.toSet()
    internal fun forEachValuesForTest(): Map<String, List<String>> = xmlForEachValues.toMap()
    /** R22 T-02 测试缝：程序化切换条件勾选（同步可见勾选框，等价用户点击的状态面）。 */
    internal fun toggleCheckForTest(id: String, selected: Boolean) {
        if (selected) enabledIds.add(id) else enabledIds.remove(id)
        (conditionCheckBoxes + batchCheckBoxes).firstOrNull { it.getClientProperty(PROP_TOGGLE_ID) == id }?.isSelected = selected
    }
    /** R22 T-02 测试缝：程序化切换 foreach null 勾选。 */
    internal fun toggleForEachNullForTest(expr: String, selected: Boolean) {
        if (selected) xmlForEachNulls.add(expr) else xmlForEachNulls.remove(expr)
        xmlForEachNullBoxes.firstOrNull { it.getClientProperty(PROP_FOREACH_NULL) == expr }?.isSelected = selected
    }
    /** R22 T-03 测试缝：程序化设置 foreach 权威值（同步可见字段文本）。 */
    internal fun setForEachValuesForTest(expr: String, values: List<String>) {
        xmlForEachValues[expr] = values
        xmlForEachValueFields.firstOrNull { it.first == expr }?.second?.text = values.joinToString(",")
    }
    /** R22 T-03 测试缝：直驱 applyXmlForEachValues（等价字段 listener 链路的状态面）。 */
    internal fun applyXmlForEachValuesForTest(expr: String, text: String) {
        applyXmlForEachValues(XmlForEachView(expr), text)
    }
    /** R22 T-02 测试缝：清空权威状态（模拟新会话；组件在场便于断言视觉同步）。 */
    internal fun clearStateForTest() {
        enabledIds.clear()
        paramValues.clear()
        xmlForEachValues.clear()
        xmlForEachNulls.clear()
    }
    /** R22 T-02 测试缝：快照灌入当前键的历史缓存（模拟既有方案）。 */
    internal fun loadSnapshotForTest(snapshot: Map<String, String>) {
        val key = historyCacheKey() ?: return
        paramHistoryCache.save(key, snapshot)
    }
    /** R22 测试缝：当前预览 SQL。 */
    internal fun historyCacheKeyForTest(): String? = historyCacheKey()
    /** R22 T-04 测试缝：方案下拉（在场/选中态断言）。 */
    internal fun schemeComboForTest(): JComboBox<String>? = schemeCombo

    /** O-88：当前预设折叠件实例（null=本轮参数区未建预设行——无参数早退/清空后）。 */
    internal fun presetFoldForTest(): HideableTitledPanel? = presetFold

    /** O-88：按 SCHEME_ROW_NAME 锚点取折叠内容里的方案行（折叠态下 visible=false 仍可取到）。 */
    internal fun schemeRowForTest(): JComponent? = presetFold?.let { findSchemeRowDeep(it) }

    /** O-95 S3 测试缝：绑定指示行标签（null=本轮参数区未建指示行）。 */
    internal fun bindStateLabelForTest(): JBLabel? = bindStateLabel

    /** O-95 S3 测试缝：参数区组件序列（指示行在预设行之下的次序断言用，BoxLayout Y_AXIS 视觉序=组件序）。 */
    internal fun paramPanelComponentsForTest(): List<java.awt.Component> = paramPanel.components.toList()

    private fun findSchemeRowDeep(root: java.awt.Container): JComponent? {
        for (child in root.components) {
            if (child.name == SCHEME_ROW_NAME) return child as? JComponent
            if (child is java.awt.Container) findSchemeRowDeep(child)?.let { return it }
        }
        return null
    }
    /**
     * 按 source 锚点取可见输入框（T-DevAcceptFix ⑤：召回回写用，禁插入序对位——见 [syncParamFieldText]）。
     */
    internal fun paramFieldForSourceForTest(source: String): JBTextField? =
        paramFields.firstOrNull { it.getClientProperty(PROP_FIELD_SOURCE) == source }

    /** R22 T-04 测试缝：程序化切换方案（等价下拉用户切换：清态→switchScheme→召回→渲染）。 */
    internal fun switchSchemeForTest(name: String) = switchToScheme(name)
    /** R22 T-04 测试缝：对话框回调链（同步下拉 + 激活变更整面重召回）。 */
    internal fun onSchemesChangedForTest() = onSchemesChangedFromDialog()

    companion object {
        /** O-4：通知分组 id（plugin.xml 注册，diff/copy 共用）。 */
        internal const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

        /**
         * O-88：预设分区是否处于折叠态（缺省折叠=true——20261006 T-DevAcceptFix 验收反馈定案，
         * 预设是低频操作不应默认展开占竖向空间；重建时由当前折叠件实例懒惰回写，见 [buildSchemeRow]）。
         * 静态而非实例：gutter 换语句=关旧弹窗、开新面板实例，实例字段在新实例归零丢折叠态（真机走查实证）；
         * 跨面板实例保留、不持久化（无 PropertiesComponent/磁盘写入）。@Volatile：读写入口可能非 EDT，可见性保守处理。
         */
        @Volatile
        internal var presetSectionCollapsed = true

        /** O-88 测试缝：折叠态静态字段读（跨实例保留用例断言用）。 */
        internal fun presetCollapsedForTest(): Boolean = presetSectionCollapsed

        /** O-88 测试缝：折叠态静态字段复位（setUp 调用，防静态态跨用例/跨测试类串扰）。 */
        internal fun resetPresetCollapsedForTest() {
            presetSectionCollapsed = true
        }

        /** R22 T-04：方案行 panel.name 锚点——方案行也是「含 JBLabel 的 JBPanel」且插在 paramPanel
         *  头部，[paramRowLabelForTest] 按契约只取普通参数行，须按此排除（否则布局断言序号错位）。 */
        internal const val SCHEME_ROW_NAME = "mpsv.schemeRow"

        /** O-95 S3：绑定数据源指示行/标签 panel.name 锚点（测试定位 + paramRowLabelForTest 排除同口径）。 */
        internal const val BIND_STATE_ROW_NAME = "mpsv.bindStateRow"
        internal const val BIND_STATE_LABEL_NAME = "mpsv.bindStateLabel"

        /** R22 T-02/T-03：参数历史快照特殊键前缀（勾选态/foreach null/foreach 权威值；普通参数键
         *  不以 `__` 开头——键空间约定，ParamHistoryCache 对值透明）。 */
        internal const val SNAPSHOT_CHK = "__chk__:"
        internal const val SNAPSHOT_FNULL = "__fnull__:"
        internal const val SNAPSHOT_FEV = "__fev__:"

        /** R22 T-02：勾选框 client property 锚点（快照枚举 + 召回视觉同步）。 */
        internal const val PROP_TOGGLE_ID = "mpsv.toggleId"

        /** R49：条件复选框标签截断预算下限（px）——行尾探镜标注按宽度扣减后仍保底可读（同 [checkBoxMaxWidth] 下限）。 */
        internal const val MIN_CHECKBOX_TEXT_WIDTH = 100

        /**
         * T-DevAcceptFix ⑤（20261006 验收）：参数输入框的 source 锚点——召回回写按锚点显式映射
         * （[syncParamFieldText]），禁以 paramValues 插入序对位 paramFields 行序（方案切换后
         * 快照键序与面板构建序可漂移 → 回写错位）。
         */
        internal const val PROP_FIELD_SOURCE = "mpsv.paramSource"
        internal const val PROP_FOREACH_NULL = "mpsv.forEachNull"

        /** F-45：列信息标签页标题。 */
        internal const val COLUMN_INFO_TAB_TITLE = "列信息"

        /** T-PreviewTopOverlap F3：元信息宽度预算下限（px）——极窄时保底可读，超出仍由 ellipsize 收。 */
        internal const val MIN_META_BUDGET_PX = 80

        /** M36.5.44：re-host 最小重挂间隔（ms）——DBN 结果刷新期高频 reparent，低于此间隔视为"还在被 DBN 处理"，跳过不抢，防死循环刷新闪烁。 */
        internal const val REHOST_MIN_INTERVAL_MS = 800L

        /** T-RefreshDbSteal：去抖窗口内被跳过的释放 → 延迟复查间隔（ms）。取与去抖门槛同值：
         *  复查时刻距最近一次重挂必然已越过门槛，`since` 复查不会误拦。 */
        internal const val REHOST_RETRY_DELAY_MS = 800L

        internal val NUMBER_REGEX = Regex("-?\\d+(\\.\\d+)?")
        /** T-M24-04：XML 压缩视图的空白归一化（多行文本节点 / 段间空格）。 */
        internal val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * T-PT-02：TEMPORAL 参数的可解析日期形态——`yyyy` / `yyyy-M-d` / 追加 ` HH:mm[:ss[.SSS]]`
         * （`T` 分隔兼容 ISO 形态；只做提示，不阻塞渲染）。
         */
        internal val DATE_REGEX = Regex(
            "\\d{4}(-\\d{1,2}(-\\d{1,2}([ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?)?)?)?",
        )

        /** T-PT-02：行尾校验警示标（淡色，深浅主题各一档）。 */
        internal const val PARAM_WARN_MARK = "⚠"
        internal val PARAM_WARN_FOREGROUND = JBColor(0xB8860B, 0xD9A343)

        /**
         * T-PT-02：集合命名启发式的单数词排除表——以 `s` 结尾但语义为单数（状态/地址等），
         * 不做数组提示（宁缺勿错：提示只是占位文案）。
         */
        internal val COLLECTION_NAME_EXCEPTIONS = setOf(
            "status", "address", "class", "business", "progress", "process", "access", "alias",
            "analysis", "radius", "series", "species", "news", "pass", "glass", "mass", "press",
            "stress", "success", "express", "compass", "canvas", "atlas", "bias", "lens",
            "physics", "politics",
        )

        /** T-Perf-04：参数击键 renderSql 防抖窗口（毫秒）。 */
        private const val RENDER_DEBOUNCE_MS = 150

        /** UX 换源重渲染：last-used 变更后的头部刷新防抖窗口（毫秒）——执行链连写多键只重组一次。 */
        internal const val HEADER_SOURCE_REFRESH_DEBOUNCE_MS = 120

        /**
         * T-DollarInjectMark：`${}` 直替提示的会话级关闭记忆（✕ 后本 IDE 会话所有预览面板
         * 不再弹——常用 `${orderBy}` 场景的横幅疲劳缓解；面板关闭/切换文件不重置）。
         */
        internal var dollarMarkDismissedSession = false

        /** T-DollarInjectMark：横幅文案里的 `${}` token（技术记号非语言文案，双语言共用，作 {0} 参数传入）。 */
        internal const val DOLLAR_TOKEN = "\${}"

        /** 测试复位：清除 `${}` 提示的会话级关闭记忆。 */
        internal fun resetDollarMarkForTest() {
            dollarMarkDismissedSession = false
        }

        /** T-PreviewFlash：EDT 等待预热产物的轮询间隔与超时（毫秒）。 */
        const val PREWARM_POLL_MS = 20L
        /**
         * T-PrewarmBudget（2026-09-20 据实测重定）：预热产物的等待预算——消费侧 [PrewarmAwait]
         * `completeOnTimeout` 用它决定「等多久放弃、改走降级装配」。
         *
         * **取值 500ms 的依据（真机 16 次打开 / 3 会话，见
         * `docs/验收文档/20260920-预热耗时采样与预算建议.md`）**：预热耗时（打点
         * `[M36] prewarm done in Xms`）冷启动首开 233/239/360ms（n=3），同会话复开
         * 116/133/184ms（n=13）——**旧值 60ms 一次都覆盖不到（16/16 全降级）**。500ms 覆盖观测到的
         * 冷启最大 360ms（约 40% 余量）与全部复开（2.7 倍余量）。
         *
         * **旧值 60ms 的理由已失效**：其原注释称「EDT 干等到 2000ms 会把延迟烧进 UI 线程（真机日志
         * 双击→createEditor 达 3.07s）」——那是**旧的同步 sleep 轮询实现**（`awaitPrewarmedTempFile`）；
         * 自 T-PerfTail #2 改成异步后，冷态 `scheduleOnce` 注册回调即返回，**EDT 根本不等待**，
         * 该预算只决定装配质量与等待时长，不再有「省 EDT 时间」的作用。
         *
         * **已知不覆盖**：重负载（CPU 72%~99%）下冷启曾观测到约 1s——那种情况仍会降级，且正确的解法
         * 是让预热更快（`t-fix-n2` 的父目录映射已把每次打开都要付的 `setMapping`/push-all dumb 整条去掉，
         * 本轮实测 `Push all [SqlDialectPusher]` 计数 0），而不是无限抬高预算。
         * 抬高预算的代价是等待期变长，已由 [showPreviewPreparingPlaceholder] 的占位覆盖观感。
         */
        const val PREWARM_WAIT_MS = 500L

        /**
         * T-DegradedPreviewUx P1：预热超时后的降级横幅宽限期（毫秒）。
         *
         * 超时只代表「预热还没回来」，T-PrewarmLateRecover F1 的迟到产物通常数秒内到达并自动换装。
         * 取值 3000 的依据：既有实测样本里冷启首开 233~360ms、重负载约 1s、最坏一次 3016ms
         * （`docs/验收文档/20260920-预热耗时采样与预算建议.md`），3000ms 覆盖到观测尾部；
         * 到点仍未恢复才披露，避免把「慢」呈现成「故障」。硬失败不走本宽限期（立即显示）。
         */
        internal const val DEGRADED_GRACE_MS = 3000L

        /**
         * T-PerfSweep6 T-PS6-04（性能报告发现 4）：预热目录残留清扫的**年龄**上限（7 天）。
         *
         * 取值口径：残留来自 kill / 崩溃 / 未走 removeNotify 的会话，跨会话（IDE 重启）后本插件
         * 不会再复用它们；7 天足以覆盖「用户当周内重开项目仍可能被兜底复用」的窗口
         * （`projectPreviewFile` 的 existing 分支），又不会让残留无限驻留。
         */
        internal const val STALE_PREVIEW_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

        /**
         * T-PerfSweep6 T-PS6-04：预热目录内本插件命名的文件**条数**上限（超出按 mtime 删最旧）。
         *
         * 200 = 常规使用下永远撞不到（一次预览最多留 1~2 个文件），只兜「异常路径反复泄漏」的底。
         */
        internal const val PREVIEW_DIR_MAX_FILES = 200

        /**
         * T-FixN2 × T-InsertColumnInlay O-44b：生成下一次预热的唯一临时文件名。
         *
         * **形态（`mpsql-preview-<pid>-<seq>.sql`，例 `mpsql-preview-12480-3.sql`）的唯一来源是
         * [PreviewInlayScope]**：名字由该对象按「形态常量 + 形态判据」同处一地给出，本面板只供运行期
         * 变量（pid / 序号）。**为什么必须同源**：inlay 作用域锚点靠文件名判定「这个编辑器是不是本
         * 面板预览编辑器」，而一级预览文件名每轮预热都不同（见下），故锚点按**形态**匹配——两侧各自
         * 硬编码字面量会在改名时静默打穿锚点（锚点不报错，只是 inlay 永不出现）。
         *
         * **为什么每次预热必须换名**：固定名（原 `mpsql-preview.sql`）下预热重写同一路径，而该路径
         * 的 document 里存着上一轮装配写入的真 SQL（未落盘）→ 平台 `MemoryDiskConflictResolver`
         * 判定「内存与磁盘均变」→ 模态「File Cache Conflict」抢焦点；选「Load File System Changes」
         * 还会把真 SQL 换成磁盘上的占位空内容。换名后每次预热落在全新路径（无任何 document），
         * 冲突链从根上消失（详见 [projectPreviewFile]）。
         *
         * **为什么带 pid**：同项目可被多个 IDE 进程同时打开（各自一份本目录），进程内自增序号在
         * 两个进程里会重号——重号会让「按面板生命周期删除」误删另一会话正在用的文件。
         * pid 在**存活进程间唯一**，故存活会话之间不可能撞名；与已退出会话的同名残留相遇时
         * 只会走 [projectPreviewFile] 的兜底复用分支（无 document → 仍无冲突）。
         * 序号 + pid 都是可读、可定位的：目录里一眼看出「哪个 IDE 进程的第几次预览」。
         */

        /** T-FixN2：本 IDE 进程号（文件名的会话段；取不到退化为 `0`，仍是合法文件名）。 */
        private val PREVIEW_TEMP_FILE_OWNER: String =
            runCatching { ProcessHandle.current().pid().toString() }.getOrDefault("0")

        /** T-FixN2：本 JVM 内预览序号（跨面板共享，保证同进程内每个预热文件名唯一）。 */
        private val PREVIEW_TEMP_FILE_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

        /** T-FixN2：生成下一次预热的唯一临时文件名（形态与判据同源，见 [PreviewInlayScope]）。 */
        internal fun nextPreviewTempFileName(): String = PreviewInlayScope.previewTempFileName(
            PREVIEW_TEMP_FILE_OWNER,
            PREVIEW_TEMP_FILE_SEQ.incrementAndGet(),
        )

        /**
         * T-FixN2：预览文件的方言映射对象——**父目录**（取不到父目录时退回文件本身，语义同改动前）。
         *
         * 唯一文件名后按文件映射会让每个新名字都判「未映射」：每轮预热白付一次 setMapping
         * （`SqlDialectPusher` push all dumb）+ workspace.xml 的 `SqlDialectMappings` 状态按文件路径
         * 逐个累积。平台 `PerFileMappingsBase.getMappingInner → getMappingForHierarchy` 自下而上
         * 逐级查表（`for (cur = file; cur != null; cur = cur.getParent())`），故目录映射被其下所有
         * 文件继承（平台自身也按目录记账：`installDeleteUndo` 对 `file.isDirectory()` 显式收集其下
         * 全部子条目），一次映射覆盖该目录下**全部**预览文件（含每轮的新名字）。
         *
         * 抽成 internal 纯函数仅为让「映射对象是目录、且目录 vf 实例跨文件名稳定」可直接单测断言
         * （真机/单测环境无 Database 插件时 `SqlDialectMappings` 类不可加载，映射本身不可断言）。
         */
        internal fun previewDialectMappingTarget(vf: com.intellij.openapi.vfs.VirtualFile): com.intellij.openapi.vfs.VirtualFile =
            vf.parent ?: vf
    }
}

// ------------------------------------------------------------------
// T-SourceJumpFunnel P2-2（§3.3 验收项单测抽取）：调用栈快照文案组装
// ------------------------------------------------------------------

/** 调用栈摘要字符上限：超过即截断到该长度并追加 [STACK_DIGEST_ELLIPSIS]。 */
internal const val STACK_DIGEST_MAX_CHARS = 120

/** 调用栈摘要截断标记。 */
internal const val STACK_DIGEST_ELLIPSIS = "…"






