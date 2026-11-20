package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.standalone.MissingFragmentFillFlow
import cn.song.mybatisplus.visualizer.ui.standalone.MissingSqlFragmentSupport
import cn.song.mybatisplus.visualizer.ui.standalone.StandaloneSqlSourceResolver
import cn.song.mybatisplus.visualizer.ui.standalone.StandaloneWarningText
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import java.awt.Dimension
import java.awt.Toolkit
import javax.swing.Action
import javax.swing.JComponent

/**
 * SQL 预览弹窗（M3-02，F-07 弹窗展示方式）。
 *
 * 由 [cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction] 触发，
 * 复用 [SqlPreviewPanel] 展示 SQL 与参数对照，底部提供"复制 / 关闭"。
 *
 * M14：默认尺寸按屏幕比例自适应（宽 62% / 高 65%，下限 900×600、上限 85%），
 * 并记忆用户拉伸后的尺寸（[rememberLastWindowSize]）。
 */
class SqlPreviewDialog(
    private val project: Project?,
    private val result: PreviewResult,
    private val sourceLocation: String? = null,
    sourceContext: cn.song.mybatisplus.visualizer.context.PreviewSourceContext? = null,
    /**
     * T-DialogPasteReparse：「先开弹窗、后贴内容」——挂防抖监听，用户往编辑器里贴入内容后
     * 自动重新识别一次（独立工作台的**空/原文态**开窗时置 true）。
     */
    private val autoParseOnPaste: Boolean = false,
    /**
     * T-PreviewActionRow D-7：产出本次预览的**原始文本**（只有工作台开窗这条链传）。
     *
     * 为什么弹窗需要它：面板只拿得到渲染后的 SQL（include 已展开成 TODO 占位），缺片段要补齐
     * 就必须用原始 XML 重解析。其余入口（gutter / 日志 / 历史 / 语句预览）没有原文可重解析，
     * 传 null ⇒ 弹窗不挂补齐入口，避免出现点了没反应的链接。
     */
    private val sourceText: String? = null,
) : DialogWrapper(project, false, DialogWrapper.IdeModalityType.MODELESS) {

    private val panel = SqlPreviewPanel(project)

    /**
     * T-DmlImpactProminence：底部蓝色主行动——复制 SQL 并关闭（复制成功提示随之关闭面板）。
     *
     * 必须声明在 [init] 之前：Kotlin 字段按声明序初始化，而 `init()` 会在构造期同步调用
     * `createActions()`，声明在后则该字段此刻仍为 null，动作数组里出现空位、按钮不渲染
     * （UI-042：用户侧表现为「复制 SQL 并关闭」按钮整个不见）。
     */
    private val copyAndCloseAction = object : DialogWrapperAction(
        MpSqlVisualizerBundle.message("panel.copySqlAndClose"),
    ) {
        override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
            // UI-044：弹窗随即关闭，「关闭 SQL 面板」动作已无目标，通知里不再挂它
            panel.copySql(offerClosePanelAction = false)
            doDispose()
        }
    }

    /**
     * 构造期首次 [createActions] 的产出（UI-042 回归缝）。
     *
     * 必须声明在 [init] 之前：`init()` 同步触发 `createActions()`，若本字段声明在后，
     * 其初始化表达式会在捕获之后执行、把捕获结果抹掉，回归缝自身失效。
     */
    internal var actionsAtConstructionForTest: Array<Action>? = null

    /**
     * T-PreviewActionRow D-6/D-7：本弹窗内补齐的片段（refid → 内容）。
     *
     * 存弹窗自己不存工作台：补完只影响**这个弹窗**的展示，不回写工作台输入区——用户手里那段
     * 原文保持原样（与工作台同一语义：补齐不污染原文）。
     */
    private val fragmentOverrides = linkedMapOf<String, String>()

    /** 补齐内容来源（默认弹 `MissingFragmentDialog`；测试注入，避免 headless 弹模态框）。 */
    internal var missingFragmentPrompt: (String) -> String? = { refid ->
        MissingFragmentFillFlow.promptViaDialog(project, refid)
    }

    /**
     * 最近一次「贴入即识别」用的原文（T-PreviewActionRow D-7）。
     *
     * 为什么单独存：识别成功后面板里是**渲染后的 SQL**，缺片段补齐要的却是贴进来那段原文；
     * `draftTextProvider` 那时已经给出渲染结果，不能再用。贴入路径没有 `sourceText` 参数，
     * 只能在这里记一手。
     */
    private var lastSourceText: String? = null

    init {
        title = MpSqlVisualizerBundle.message("dialog.preview.title")
        panel.setSourceLocation(sourceLocation)
        panel.setSourceContext(sourceContext)
        panel.showResult(result)
        // T-PreviewActionRow D-7：注入补齐入口——有原文（工作台开窗 / 贴入即识别）才有得补；
        // 其余入口（gutter / 日志 / 历史 / 语句预览）拿不到原文，不注入 ⇒ 面板整行不显示。
        if (sourceText != null || autoParseOnPaste) {
            panel.missingFragmentFillHandler = { refid -> fillMissingFragment(refid) }
        }
        // 注册面板到弹窗 Disposer 树：弹窗 dispose 时级联释放面板及其 messageBus 连接
        Disposer.register(disposable, panel)
        // T-M23.6：复制/执行 SQL 成功提示的「关闭 SQL 面板」按钮 → 关闭预览弹窗
        panel.onClosePanelRequested = { dispose() }
        // 最小尺寸兜底（防止拉伸时缩没；DialogWrapper 无 setMinimumSize，对中心面板设置）
        panel.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
        init()
        // 关闭按钮文案走插件 bundle（平台 cancelAction 随 IDE 语言包，插件语言设置独立于 IDE，避免中文环境显示英文 Cancel）
        setCancelButtonText(MpSqlVisualizerBundle.message("dialog.preview.cancel"))
        // 弹窗默认尺寸（按屏幕比例自适应；环境异常兜底）
        // 字体/尺寸兜底后进入「贴入即识别」待命（必须在 showResult 之后：面板编辑器此时才就位）
        if (autoParseOnPaste) armPasteReparse()
    }

    override fun createCenterPanel(): JComponent = panel

    // ------------------------------------------------------------------
    // T-DialogPasteReparse：「先开弹窗、后贴内容」
    // ------------------------------------------------------------------

    /** 是否仍待命（命中一次即 false——之后交还手动，避免与用户后续编辑互相打架）。 */
    private var pasteReparseArmed = false

    /** 轮询 Timer（仅在待命期存在；命中 / dispose 即停）。 */
    private var pasteReparseTimer: javax.swing.Timer? = null

    /** 上一次轮询看到的草稿文本（判「还在变 / 已停手」）。 */
    private var lastDraftText: String? = null

    /** 文本连续未变化的轮询次数（达到 [PASTE_REPARSE_STABLE_TICKS] 即视为停手）。 */
    private var stableTicks = 0

    /**
     * 草稿文本来源（测试缝：headless 下弹窗编辑器可能还没建出来，
     * 用例注入固定文本即可驱动同一重识别链）。
     */
    internal var draftTextProvider: () -> String? = { panel.currentDraftText() }

    /**
     * 进入待命：轮询草稿文本，停手后自动重新识别一次。
     *
     * ## 为什么是轮询而不是挂 DocumentListener
     *
     * 面板的编辑器是**懒建**的（空结果构造期 `draftDocument()` 为 null，真机要等首次布局/挂载
     * 才有编辑器）——挂监听会有「此刻没文档就永远挂不上」的窗口。轮询只读
     * [SqlPreviewPanel.currentDraftText]，文档何时出现都能接上；代价是待命期每
     * [PASTE_REPARSE_POLL_MS] 一次取文本（命中即停，常态为零开销）。
     *
     * ## 为什么只在「空/原文态」开窗时进入待命
     *
     * 正常语句预览的编辑器内容已是渲染后的 SQL，用户在那儿编辑是**改产物**（回填参数/手调 SQL），
     * 自动解析会把他的编辑冲掉。
     */
    private fun armPasteReparse() {
        pasteReparseArmed = true
        // 注意：本方法在构造期 init 里被调用，而下方字段声明在 init 之后——**不得在这里读取
        // draftTextProvider 等字段**（此刻仍是 JVM 默认 null，本仓在册坑）。首次轮询再取。
        lastDraftText = null
        stableTicks = 0
        pasteReparseTimer?.stop()
        pasteReparseTimer = javax.swing.Timer(PASTE_REPARSE_POLL_MS) { pollDraftForReparse() }.apply {
            isRepeats = true
            start()
        }
    }

    private fun pollDraftForReparse() {
        if (!pasteReparseArmed) {
            stopPasteReparse()
            return
        }
        val text = draftTextProvider()?.takeIf { it.isNotBlank() } ?: return
        if (text != lastDraftText) {
            lastDraftText = text
            stableTicks = 0
            return
        }
        stableTicks++
        if (stableTicks >= PASTE_REPARSE_STABLE_TICKS) {
            reparseDraftFromEditor()
        }
    }

    private fun stopPasteReparse() {
        pasteReparseArmed = false
        pasteReparseTimer?.stop()
        pasteReparseTimer = null
    }

    /**
     * 对当前草稿做一次独立预览识别（贴入停手后触发）。
     *
     * 识别走与工作台页签**同一条链**（[StandaloneSqlSourceResolver]：风味判定 + 双链择优），
     * 失败文案同源（[StandaloneWarningText]）。命中后立即交还手动，不再自动解析。
     *
     * T-PerfSweep33 券01：识别挪池线程（见 [resolveOffEdt]）；本入口只在 EDT 读草稿文本。
     */
    internal fun reparseDraftFromEditor() {
        if (!pasteReparseArmed) return
        val text = draftTextProvider()?.takeIf { it.isNotBlank() } ?: return
        // T-PreviewActionRow D-7：记一手原文——识别成功后草稿文本变成渲染结果，
        // 缺片段补齐要的还是「贴进来那段」，只能在这里留底
        lastSourceText = text
        resolveOffEdt({ text }, ::applyResolvedAsFresh)
    }

    /** 测试访问器：是否仍待命。 */
    internal fun pasteReparseArmedForTest(): Boolean = pasteReparseArmed

    /**
     * T-PreviewActionRow D-6/D-7：补齐一个缺失片段 → 用原文重解析 → 就地回填面板。
     *
     * 不重开弹窗：用户是在结果区看到 TODO 占位后点进来补的，补完应该当场看到展开后的 SQL。
     * 先停待命：计时器若还在跑，会拿旧草稿再覆盖一次刚补好的结果。
     *
     * 解析同样走 [resolveOffEdt]（T-PerfSweep33 券01 的唯一出口）：补进来的是整段 mapper，
     * DOM 解析在 100k 上限下最坏数百毫秒，不能停在 EDT。
     */
    private fun fillMissingFragment(refid: String) {
        val source = sourceText ?: lastSourceText ?: return
        val content = missingFragmentPrompt(refid)
        if (!MissingFragmentFillFlow.record(refid, content, fragmentOverrides)) return
        stopPasteReparse()
        resolveOffEdt(
            textSource = { MissingSqlFragmentSupport.withFragments(source, fragmentOverrides) },
            applyOnEdt = ::applyResolvedAsFresh,
        )
    }

    /** 测试缝：触发一次补齐（等价点击面板提示行；内容由 [missingFragmentPrompt] 提供）。 */
    internal fun fillMissingFragmentForTest(refid: String) {
        fillMissingFragment(refid)
    }

    /** 测试缝：提示行组件（派发真实点击覆盖监听接线）。 */
    internal fun missingFragmentLabelForTest(): javax.swing.JLabel? = panel.missingFragmentLabelForTest()

    // ------------------------------------------------------------------
    // T-PerfSweep33 券01：识别链挪池线程（性能分析第十期 §4.1）
    // ------------------------------------------------------------------

    /** 解析代次守卫：只应用最后一次请求的产物（贴入停手与手动操作竞态时保最后一次）。 */
    private var resolveGeneration = 0

    /**
     * 测试缝：替换「池线程计算 + invokeLater 回 EDT」的派发（默认 null = 生产形态）。
     * 契约：计算完回调 [onDone] 恰一次（空文本/识别失败传 null，不回调则无回填）。用例注入
     * 同步形态即可确定性驱动同一条链——先例＝[cn.song.mybatisplus.visualizer.util.EdtDispatch.invokeLaterOverride]。
     */
    internal var resolveOffEdtDispatcher: (
        (textSource: () -> String?, onDone: (StandaloneSqlSourceResolver.Resolved?) -> Unit) -> Unit
    )? = null

    /**
     * 识别链后台化。`StandaloneSqlSourceResolver.resolve` 是纯文本链（零 PSI/索引；工作台页签同链
     * 早已池线程化——SqlStandalonePreviewPanel），但最坏 8 次 DOM 解析、输入上限 100k 字符，
     * EDT 直跑可停数百毫秒。代次守卫只应用最后一次请求；弹窗已释放时丢弃（迟到产物面）。
     */
    private fun resolveOffEdt(textSource: () -> String?, applyOnEdt: (StandaloneSqlSourceResolver.Resolved) -> Unit) {
        val generation = ++resolveGeneration
        val seam = resolveOffEdtDispatcher
        if (seam != null) {
            seam(textSource) { deliverResolved(generation, it, applyOnEdt) }
            return
        }
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val text = runCatching(textSource).getOrNull()
            val resolved = text?.let { runCatching { StandaloneSqlSourceResolver.resolve(it) }.getOrNull() }
            app.invokeLater({ deliverResolved(generation, resolved, applyOnEdt) }, ModalityState.nonModal())
        }
    }

    /** 迟到产物闸：只应用最后一次请求；弹窗已释放时丢弃。 */
    private fun deliverResolved(
        generation: Int,
        resolved: StandaloneSqlSourceResolver.Resolved?,
        applyOnEdt: (StandaloneSqlSourceResolver.Resolved) -> Unit,
    ) {
        if (isDisposed || generation != resolveGeneration) return
        resolved?.let(applyOnEdt)
    }

    /**
     * 识别产物回填（失败文案同源 [StandaloneWarningText]）；命中后停轮询、交还手动——
     * 不再自动解析，与用户后续编辑互不打架。
     */
    private fun applyResolvedAsFresh(resolved: StandaloneSqlSourceResolver.Resolved) {
        val fresh = resolved.failure?.let { failure ->
            resolved.result.copy(
                warning = StandaloneWarningText.failure(failure),
                informationalWarning = false,
            )
        } ?: resolved.result
        stopPasteReparse()
        panel.showResult(fresh)
    }

    override fun dispose() {
        stopPasteReparse()
        super.dispose()
    }

    /**
     * T-RefreshDbSteal F2：程序化关闭（供 [SqlPreviewService] 弹窗单例化关旧开新）。
     * DialogWrapper.dispose 在平台侧 protected，同类内提升为 public 包装——语义与用户
     * 点关闭一致（级联 Disposer 释放面板，rehost 监听随之移除）。
     */
    fun doDispose() {
        dispose()
    }

    /** T-M23.6：internal 测试访问器（面板「关闭 SQL 面板」回调链路断言，仿 SqlPreviewPanel.editorForTest）。 */
    internal fun panelForTest(): SqlPreviewPanel? = panel

    override fun createActions(): Array<Action> = arrayOf(
        // 执行 SQL（M17：选择数据源 → 临时文件 → 可执行；提示由面板/支持层统一处理）
        object : DialogWrapperAction(MpSqlVisualizerBundle.message("panel.executeSql")) {
            override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
                panel.executeSql()
            }
        },
        // 复制 SQL（F-08，提示由面板统一处理）
        object : DialogWrapperAction(MpSqlVisualizerBundle.message("panel.copySql")) {
            override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
                panel.copySql()
            }
        },
        copyAndCloseAction,
        // 关闭
        cancelAction,
    ).also { actionsAtConstructionForTest = actionsAtConstructionForTest ?: it }

    /** 复制并关闭按钮按主行动样式渲染（蓝色实底）；其余按钮保持平台默认。 */
    override fun createJButtonForAction(action: Action): javax.swing.JButton {
        val button = super.createJButtonForAction(action)
        if (action === copyAndCloseAction) applyPrimaryButtonStyle(button)
        return button
    }

    /** UI-043 测试访问器（createJButtonForAction 在 DialogWrapper 内为 protected）。 */
    internal fun createJButtonForActionForTest(action: Action): javax.swing.JButton = createJButtonForAction(action)

    /** T-DmlImpactProminence：测试访问器（createActions 在 DialogWrapper 内为 protected）。 */
    internal fun actionsForTest(): Array<Action> = createActions()

    /** 尺寸记忆 key（非 null → DialogWrapper 经 DimensionService 记忆用户拉伸尺寸，下次打开保持）。 */
    override fun getDimensionServiceKey(): String = DIMENSION_KEY

    companion object {
        /** 尺寸记忆 key。 */
        const val DIMENSION_KEY = "cn.song.mybatisplus.visualizer.SqlPreviewDialog"

        /**
         * T-DialogPasteReparse：草稿轮询间隔（ms）——文档出现后才接得上（编辑器懒建）。
         */
        internal const val PASTE_REPARSE_POLL_MS = 400

        /**
         * T-DialogPasteReparse：文本连续未变化的轮询次数阈值（400ms × 2 ≈ 800ms 静默即认为停手）。
         */
        internal const val PASTE_REPARSE_STABLE_TICKS = 2

        /** 最小弹窗尺寸。 */
        const val MIN_WIDTH = 640
        const val MIN_HEIGHT = 420

        /** 屏幕占比（宽/高）。 */
        const val SCREEN_RATIO_WIDTH = 0.62
        const val SCREEN_RATIO_HEIGHT = 0.65

        /** 屏幕占比上限（防止小屏超屏）。 */
        const val SCREEN_RATIO_CAP = 0.85

        /** 最小默认尺寸（小屏/异常环境兜底）。 */
        const val DEFAULT_WIDTH = 900
        const val DEFAULT_HEIGHT = 600

        /**
         * 计算弹窗默认尺寸（M14）：按屏幕比例（宽 62% / 高 65%），
         * 夹在「下限 900×600」与「屏幕 85% 上限」之间；小屏时**上限优先（不超屏）**；
         * headless/无屏幕信息时兜底 900×600。
         * 独立静态方法便于单测（mock 屏幕）。
         */
        internal fun initialSize(screenWidth: Int = screenWidthOf(), screenHeight: Int = screenHeightOf()): Dimension {
            if (screenWidth <= 0 || screenHeight <= 0) return Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT)
            val w = fitWithin((screenWidth * SCREEN_RATIO_WIDTH).toInt(), screenWidth, DEFAULT_WIDTH)
            val h = fitWithin((screenHeight * SCREEN_RATIO_HEIGHT).toInt(), screenHeight, DEFAULT_HEIGHT)
            return Dimension(w, h)
        }

        /**
         * 尺寸夹取：正常屏 → 比例值夹在 [minDefault, 屏幕 85%]；
         * 小屏（上限 < 下限）→ 取上限（不超屏优先于保下限）。
         */
        private fun fitWithin(ratioValue: Int, screenSize: Int, minDefault: Int): Int {
            val cap = (screenSize * SCREEN_RATIO_CAP).toInt()
            return if (minDefault <= cap) ratioValue.coerceIn(minDefault, cap) else cap
        }

        private fun screenWidthOf(): Int = runCatching { Toolkit.getDefaultToolkit().screenSize.width }.getOrDefault(0)

        private fun screenHeightOf(): Int = runCatching { Toolkit.getDefaultToolkit().screenSize.height }.getOrDefault(0)
    }
}
