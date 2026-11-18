package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlInputEditor
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.showResult
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * 独立 SQL 预览工作台（T-SqlPreviewStandalone D-2）：不依赖语句入口，用户粘贴一段
 * SQL 或 mapper XML 片段，走 [StandaloneSqlSourceResolver] 识别后喂内嵌 [SqlPreviewPanel]
 * 展示——格式化、参数填值、复制可执行 SQL、动态条件勾选（XML 路径）与执行全部复用
 * 既有面板能力，本类只做装配。
 *
 * 布局（Splitter 上下分栏，对齐 SqlToJavaPanel 先例）：
 *  - 上：SQL 输入区（[SqlInputEditor]：SQL 高亮 + 打字自动弹补全）；工具栏：解析预览 / 格式化 / 清空 / 在弹框中打开；
 *  - 下：预览区（内嵌 [SqlPreviewPanel]，懒创建——首次解析成功才建实例，避免常驻双 Editor 内存代价）；
 *  - 底部：状态行（识别结果如实呈现：XML 语句块 / 纯 SQL / 未识别 / 过长）。
 *
 * 线程纪律：解析是纯文本函数（project=null 链零 PSI 读），派后台线程；结果经代次守卫
 * 回 EDT 喂面板（对齐 SqlToJavaPanel 的 ConversionRequest 代次模式）。
 */
class SqlStandalonePreviewPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val inputEditor = SqlInputEditor(project, "")

    private val statusLabel = JBLabel(" ").apply {
        border = JBUI.Borders.empty(2, 6, 2, 6)
    }

    /** 输入区空文档灰字提示（用户交互引导；有内容自动隐藏）。 */
    private val inputHintLabel = JBLabel(MpSqlVisualizerBundle.message("standalone.hint.input")).apply {
        foreground = UIUtil.getInactiveTextColor()
        border = JBUI.Borders.empty(2, 6, 2, 6)
    }

    /** 预览区空态灰字提示（首次解析成功前显示）。 */
    private val previewHintLabel = JBLabel(MpSqlVisualizerBundle.message("standalone.hint.preview")).apply {
        foreground = UIUtil.getInactiveTextColor()
        border = JBUI.Borders.empty(2, 6, 2, 6)
    }

    /** 预览区宿主：懒创建前显示灰字提示，解析成功后替换为 [SqlPreviewPanel]。 */
    private val previewHolder = JPanel(BorderLayout()).apply {
        add(previewHintLabel, BorderLayout.NORTH)
    }

    /**
     * T-IncludePlaceholder A 券：用户补齐的 `<sql id>` 片段（id → 片段内容）。
     *
     * 与输入区分开存：用户输入永远保持原样（贴什么显示什么），补齐内容只在解析前拼到前面——
     * 这样「补齐」不污染用户手里那段文本，也不会因为再次编辑输入而丢失。
     */
    private val fragmentOverrides = linkedMapOf<String, String>()

    /** 缺失片段提示行（有缺失才可见；点击进入补齐输入）。 */
    private val missingFragmentLabel = JBLabel(" ").apply {
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(2, 6, 2, 6)
        isVisible = false
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                promptMissingFragment()
            }
        })
    }

    /** 内嵌预览面板（懒创建；Disposer 挂本面板，防双 Editor 常驻）。 */
    private var previewPanel: SqlPreviewPanel? = null

    /** Disposer 防重注册守卫（对齐 SqlLogPanel.historyPanelRegistered 先例）。 */
    private var previewPanelRegistered = false

    /** 代次守卫：晚到的后台解析结果触碰已过期/已清空的 UI（对齐 SqlToJavaPanel invalidateRequests）。 */
    private var resolveGeneration = 0L

    private var disposed = false

    /** 解析预览动作实例（字段化：构造期就要注册组件级快捷键，不能每次建工具栏都新建一个）。 */
    private val resolveAction = ResolveAction()

    init {
        Disposer.register(this, inputEditor)

        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(buildInputToolbar(), BorderLayout.NORTH)
        inputPanel.add(inputHintLabel, BorderLayout.SOUTH)
        inputPanel.add(inputEditor.component, BorderLayout.CENTER)

        val splitter = Splitter(true, 0.4f).apply {
            firstComponent = inputPanel
            secondComponent = previewHolder
            isShowDividerControls = false
        }
        add(splitter, BorderLayout.CENTER)
        // T-IncludePlaceholder：底部两行——缺失片段提示（有缺失才显示）+ 识别状态行
        add(
            JPanel(BorderLayout()).apply {
                add(missingFragmentLabel, BorderLayout.NORTH)
                add(statusLabel, BorderLayout.SOUTH)
            },
            BorderLayout.SOUTH,
        )

        // T-StandaloneHintAndWarning：Alt+Shift+Q 在工作台里接「解析预览」。
        // 为什么必须接管：该键全局绑 ConvertToSqlAction，而它只在 Java/XML/kt 文件上 enabled
        //（`actions/ConvertToSqlAction.kt` 的 update）——工作台输入是 `input.sql`，动作恒 disabled，
        // 按键被丢弃、界面零反馈，而预览区空态提示恰恰承诺了「按 Alt+Shift+Q」。组件级 custom
        // shortcut 命中即消费按键（同 SqlPreviewPanel 格式化键 T-M26.6 的既有手法）。
        resolveAction.registerCustomShortcutSet(
            CustomShortcutSet(*reparseShortcuts()),
            this,
        )

        // 空态提示显隐跟随文档内容
        inputEditor.editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(e: DocumentEvent) {
                inputHintLabel.isVisible = inputEditor.text.isBlank()
            }
        }, this)
    }

    // ------------------------------------------------------------------
    // 工具栏
    // ------------------------------------------------------------------

    private fun buildInputToolbar(): JPanel {
        val group = DefaultActionGroup(
            resolveAction,
            FormatAction(),
            ClearAction(),
            OpenInDialogAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID, group, true)
            .apply { targetComponent = this@SqlStandalonePreviewPanel }
        return JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.WEST) }
    }

    /** 解析预览：后台跑双源解析器，代次守卫回 EDT 喂内嵌面板。 */
    private inner class ResolveAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Convert
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.resolve")
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            resolveFromInput()
        }
    }

    /** 格式化输入区 SQL（SqlFormatter 幂等；程序性写入不触发补全，对齐 SqlToJavaPanel.FormatAction）。 */
    private inner class FormatAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Format
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.format")
        }

        override fun actionPerformed(e: AnActionEvent) {
            val text = inputEditor.text
            if (text.isBlank()) return
            inputEditor.setSql(SqlFormatter.format(text))
        }
    }

    /** 清空：作废在途解析、清输入、预览区回到空态提示（释放内嵌面板，下轮解析重建）。 */
    private inner class ClearAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Clear
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.clear")
        }

        override fun actionPerformed(e: AnActionEvent) {
            clear()
        }
    }

    /**
     * 在弹框中打开：同一解析链产出 → 既有 modeless 弹窗（与三条语句入口同构）。
     *
     * **`internal` 而非 `private`**：回归用例必须走这条真实动作路径——2026-11-18 缺陷就出在
     * 「动作先置标志位、再调无参方法被默认值抹掉」，只测面板方法会漏掉。
     */
    internal inner class OpenInDialogAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.DockedPreview
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.openDialog")
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            resolveFromInput(openDialog = true)
        }
    }

    // ------------------------------------------------------------------
    // 解析与展示
    // ------------------------------------------------------------------

    /**
     * 解析当前输入并展示（内部入口）。流程：EDT 取文本快照 → 后台纯文本解析 →
     * 代次守卫回 EDT 装配。
     *
     * **`openDialog` 只存在于本条调用链**（捕获进后台闭包、随代次守卫回传），不再用字段传递——
     * 2026-11-18 缺陷：原实现用 `openDialogPending` 字段，动作先置真再调无参 `resolveFromInput()`，
     * 被形参默认值 `false` 就地抹掉，导致「在弹框中打开」永不弹窗。字段形态跨调用、语义靠调用
     * 顺序维持，禁止回归。
     *
     * 空输入与未识别文本同样可以开弹窗（用户诉求：这个按钮永远能开）——空输入给空编辑器，
     * 未识别给原文 + 信息级说明，用户可直接在弹窗内粘贴/编辑。
     */
    fun resolveFromInput(openDialog: Boolean = false) {
        val rawInput = inputEditor.text
        if (rawInput.isBlank()) {
            if (openDialog) {
                setStatus(MpSqlVisualizerBundle.message("standalone.status.emptyOpenedDialog"))
                openDialogWith(StandaloneSqlSourceResolver.emptyPreviewResult(), autoReparse = true)
            } else {
                setStatus(MpSqlVisualizerBundle.message("standalone.status.empty"))
            }
            return
        }
        if (rawInput.length > StandaloneSqlSourceResolver.MAX_TEXT_LENGTH) {
            setStatus(MpSqlVisualizerBundle.message("standalone.status.tooLong"))
            return
        }
        // T-IncludePlaceholder A 券：补齐的片段只在解析前拼入，用户输入保持原样
        val text = MissingSqlFragmentSupport.withFragments(rawInput, fragmentOverrides)
        val missing = MissingSqlFragmentSupport.missingRefids(rawInput)
        val generation = ++resolveGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = runCatching { StandaloneSqlSourceResolver.resolve(text) }.getOrNull()
            ApplicationManager.getApplication().invokeLater(
                {
                    if (disposed || generation != resolveGeneration || project.isDisposed) return@invokeLater
                    applyResolved(resolved, openDialog, rawInput, missing)
                },
                ModalityState.nonModal(),
            )
        }
    }

    private fun applyResolved(
        resolved: StandaloneSqlSourceResolver.Resolved?,
        openDialog: Boolean,
        rawText: String,
        missingRefids: List<String> = emptyList(),
    ) {
        updateMissingFragmentHint(missingRefids)
        if (resolved == null) {
            setStatus(MpSqlVisualizerBundle.message("standalone.status.unrecognized"))
            if (openDialog) openDialogWith(rawTextResult(rawText), autoReparse = true)
            return
        }
        // 两条解析链都没还原：warning 换成结构化四段（标题｜来源｜语句｜原因｜建议），
        // 面板横幅与「复制错误信息」都读这一份——用户粘贴出去的才是可定位的完整信息。
        val result = resolved.failure?.let { failure ->
            resolved.result.copy(
                warning = buildFailureWarning(failure),
                informationalWarning = false,
            )
        } ?: resolved.result
        ensurePreviewPanel().showResult(result)
        previewHintLabel.isVisible = false
        setStatus(statusTextOf(resolved))
        if (openDialog) openDialogWith(result)
    }

    /** T-IncludePlaceholder A 券：更新「缺失片段」提示行（无缺失即隐藏）。 */
    private fun updateMissingFragmentHint(missing: List<String>) {
        if (missing.isEmpty()) {
            missingFragmentLabel.isVisible = false
            return
        }
        missingFragmentLabel.text = MpSqlVisualizerBundle.message(
            "standalone.missingFragment.label",
            missing.joinToString("、"),
        )
        missingFragmentLabel.isVisible = true
    }

    /**
     * T-IncludePlaceholder A 券：点提示行 → 让用户补齐首个缺失片段，补齐后立即重解析。
     *
     * 为什么逐个补：一个片段可能引用另一个缺失片段，一次只问一个才不会让用户面对一个
     * 不知道从哪粘起的空框；补完重解析会重新算缺失集合，链路自然收敛。
     */
    private fun promptMissingFragment() {
        val missing = MissingSqlFragmentSupport.missingRefids(inputEditor.text).firstOrNull() ?: return
        val dialog = MissingFragmentDialog(project, missing)
        if (!dialog.showAndGet()) return
        val content = dialog.content().takeIf { it.isNotBlank() } ?: return
        fragmentOverrides[missing] = content
        resolveFromInput()
    }

    /**
     * 走既有 modeless 弹窗（`SqlPreviewService` 单例关旧开新）。
     *
     * @param autoReparse 「先开弹窗、后贴内容」场景置 true：弹窗挂防抖监听，用户往空编辑器里
     *   贴入 SQL / mapper 片段后自动重新识别一次（命中即交还手动，不干扰后续编辑）。
     */
    private fun openDialogWith(result: PreviewResult, autoReparse: Boolean = false) {
        if (project.isDisposed) return
        SqlPreviewService.showResult(project, result, autoParseOnPaste = autoReparse)
    }

    /**
     * 未识别文本的弹窗载荷：原文照搬 + 信息级说明（UI-001 的 `informationalWarning`，
     * 面板用 ℹ 前缀，不冒充渲染失败）。
     */
    private fun rawTextResult(text: String): PreviewResult = PreviewResult(
        sql = text,
        compactSql = text,
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
        warning = StandaloneWarningText.rawTextNotice(),
        informationalWarning = true,
    )

    /** 结构化失败文案（单行、`｜` 分段）——与弹窗同源，见 [StandaloneWarningText]。 */
    private fun buildFailureWarning(failure: StandaloneSqlSourceResolver.Failure): String =
        StandaloneWarningText.failure(failure)

    /** 识别结果如实呈现（状态条教会用户「插件认成了什么」，含 MyBatis / iBatis 哪条链）。 */
    private fun statusTextOf(resolved: StandaloneSqlSourceResolver.Resolved): String = when (resolved.kind) {
        StandaloneSqlSourceResolver.Kind.XML_STATEMENT -> {
            val id = resolved.statementId ?: "?"
            val iBatis = resolved.flavor == XmlFlavorDetector.Flavor.IBATIS
            when {
                resolved.statementCount > 1 -> MpSqlVisualizerBundle.message(
                    if (iBatis) "standalone.status.ibatis.multi" else "standalone.status.xml.multi",
                    id,
                    resolved.statementCount,
                )
                iBatis -> MpSqlVisualizerBundle.message("standalone.status.ibatis", id)
                else -> MpSqlVisualizerBundle.message("standalone.status.xml", id)
            }
        }
        StandaloneSqlSourceResolver.Kind.PLAIN_SQL ->
            MpSqlVisualizerBundle.message("standalone.status.plain", resolved.paramCount)
    }

    /** 懒创建内嵌面板（防重注册守卫 + 预览区替换空态提示）。 */
    private fun ensurePreviewPanel(): SqlPreviewPanel {
        previewPanel?.let {
            previewHolder.add(it, BorderLayout.CENTER)
            previewHolder.revalidate()
            previewHolder.repaint()
            return it
        }
        val panel = SqlPreviewPanel(
            project,
            // T-StandaloneHintAndWarning：工作台专用空态提示——原文案的「选中代码」与 Alt+Shift+Q
            // 是编辑器流的语义，搬过来是假承诺（本类已接管该键，但「选中代码」在工作台不成立）。
            emptyHint = MpSqlVisualizerBundle.message("standalone.preview.emptyHint"),
        )
        if (!previewPanelRegistered) {
            Disposer.register(this, panel)
            previewPanelRegistered = true
        }
        previewHolder.removeAll()
        previewHolder.add(panel, BorderLayout.CENTER)
        previewHolder.revalidate()
        previewHolder.repaint()
        previewPanel = panel
        return panel
    }

    private fun clear() {
        resolveGeneration++
        fragmentOverrides.clear()
        missingFragmentLabel.isVisible = false
        inputEditor.setSql("")
        previewPanel?.let {
            previewHolder.removeAll()
            previewHolder.add(previewHintLabel, BorderLayout.NORTH)
            previewHintLabel.isVisible = true
            previewHolder.revalidate()
            previewHolder.repaint()
            Disposer.dispose(it)
        }
        previewPanel = null
        previewPanelRegistered = false
        setStatus(" ")
    }

    private fun setStatus(text: String) {
        statusLabel.text = text
    }

    // ------------------------------------------------------------------
    // 面板外通道（SqlLogPanel / action 桥接）
    // ------------------------------------------------------------------

    /** 工具窗页签选中后聚焦输入区；prefillText 非 null 时先程序性预填再解析（D-4 剪贴板候选）。 */
    fun focusAndMaybeResolve(prefillText: String?) {
        prefillText?.let { inputEditor.setSql(it) }
        inputEditor.editor.contentComponent.requestFocusInWindow()
        if (!prefillText.isNullOrBlank()) resolveFromInput()
    }

    /** 测试缝：程序性写入输入区（Light 用例驱动识别/弹窗链，不依赖真实键入）。 */
    internal fun setInputTextForTest(text: String) {
        inputEditor.setSql(text)
    }

    /** 测试缝：内嵌预览面板当前生效的空态提示（证工作台专用文案真的接上了）。 */
    internal fun embeddedPreviewHintForTest(): String = ensurePreviewPanel().previewEmptyHintForTest()

    override fun dispose() {
        if (disposed) return
        disposed = true
        resolveGeneration++
        // 显式释放子件（对齐 SqlToJavaPanel.dispose 的既有口径；guard 幂等防双释放）
        inputEditor.dispose()
        previewPanel?.let { Disposer.dispose(it) }
        previewPanel = null
        previewPanelRegistered = false
    }

    companion object {
        private const val TOOLBAR_ID = "SqlStandalonePreviewPanelToolbar"

        /**
         * 重新解析快捷键：与 plugin.xml 里 ConvertToSql 的双 keymap 登记同源（Win/Linux `alt shift Q`，
         * macOS `meta shift Q`）——用户是在编辑器里学会这个键的，工作台沿用同一手感。
         */
        internal fun reparseShortcuts(): Array<Shortcut> = if (SystemInfo.isMac) {
            arrayOf(KeyboardShortcut(KeyStroke.getKeyStroke("meta shift Q"), null))
        } else {
            arrayOf(KeyboardShortcut(KeyStroke.getKeyStroke("alt shift Q"), null))
        }
    }
}
