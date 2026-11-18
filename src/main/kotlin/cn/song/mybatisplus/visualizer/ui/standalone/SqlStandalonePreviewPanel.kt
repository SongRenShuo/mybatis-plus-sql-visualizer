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
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JPanel

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

    /** 内嵌预览面板（懒创建；Disposer 挂本面板，防双 Editor 常驻）。 */
    private var previewPanel: SqlPreviewPanel? = null

    /** Disposer 防重注册守卫（对齐 SqlLogPanel.historyPanelRegistered 先例）。 */
    private var previewPanelRegistered = false

    /** 代次守卫：晚到的后台解析结果触碰已过期/已清空的 UI（对齐 SqlToJavaPanel invalidateRequests）。 */
    private var resolveGeneration = 0L

    private var disposed = false

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
        add(statusLabel, BorderLayout.SOUTH)

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
            ResolveAction(),
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
        val text = inputEditor.text
        if (text.isBlank()) {
            if (openDialog) {
                setStatus(MpSqlVisualizerBundle.message("standalone.status.emptyOpenedDialog"))
                openDialogWith(StandaloneSqlSourceResolver.emptyPreviewResult())
            } else {
                setStatus(MpSqlVisualizerBundle.message("standalone.status.empty"))
            }
            return
        }
        if (text.length > StandaloneSqlSourceResolver.MAX_TEXT_LENGTH) {
            setStatus(MpSqlVisualizerBundle.message("standalone.status.tooLong"))
            return
        }
        val generation = ++resolveGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = runCatching { StandaloneSqlSourceResolver.resolve(text) }.getOrNull()
            ApplicationManager.getApplication().invokeLater(
                {
                    if (disposed || generation != resolveGeneration || project.isDisposed) return@invokeLater
                    applyResolved(resolved, openDialog, text)
                },
                ModalityState.nonModal(),
            )
        }
    }

    private fun applyResolved(
        resolved: StandaloneSqlSourceResolver.Resolved?,
        openDialog: Boolean,
        rawText: String,
    ) {
        if (resolved == null) {
            setStatus(MpSqlVisualizerBundle.message("standalone.status.unrecognized"))
            if (openDialog) openDialogWith(rawTextResult(rawText))
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

    /** 走既有 modeless 弹窗（`SqlPreviewService` 单例关旧开新）。 */
    private fun openDialogWith(result: PreviewResult) {
        if (project.isDisposed) return
        SqlPreviewService.showResult(project, result)
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
        warning = MpSqlVisualizerBundle.message("standalone.status.unrecognizedInDialog"),
        informationalWarning = true,
    )

    /**
     * 结构化失败文案（单行、`｜` 分段）。
     *
     * 为什么不用换行：面板 meta 行是 `JBLabel`（不渲染 `\n`），横幅同理——多行文本会挤成一行
     * 且丢掉分隔，反而更难读。单行分段在 meta/横幅/剪贴板三处形态一致。
     */
    private fun buildFailureWarning(failure: StandaloneSqlSourceResolver.Failure): String {
        val parts = mutableListOf(MpSqlVisualizerBundle.message("standalone.error.title"))
        parts += MpSqlVisualizerBundle.message("standalone.error.source")
        val statement = buildString {
            append(failure.statementId ?: "?")
            failure.statementTagName?.let { append(" (<").append(it).append(">)") }
        }
        parts += MpSqlVisualizerBundle.message("standalone.error.statement", statement)
        failure.reason?.let { parts += MpSqlVisualizerBundle.message("standalone.error.reason", it) }
        parts += MpSqlVisualizerBundle.message(
            if (failure.flavor == XmlFlavorDetector.Flavor.IBATIS) {
                "standalone.error.advice.ibatis"
            } else {
                "standalone.error.advice.mybatis"
            },
        )
        if (failure.otherChainFailed) {
            parts += MpSqlVisualizerBundle.message("standalone.error.otherChain")
        }
        return parts.joinToString("｜")
    }

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
        val panel = SqlPreviewPanel(project)
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
    }
}
