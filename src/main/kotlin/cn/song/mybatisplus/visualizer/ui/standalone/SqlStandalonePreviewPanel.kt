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

    /** 「在弹框中打开」挂起标志：解析成功后除内嵌展示外再走 [SqlPreviewService.showResult]。 */
    private var openDialogPending = false

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

    /** 在弹框中打开：同一解析链产出 → 既有 modeless 弹窗（与三条语句入口同构）。 */
    private inner class OpenInDialogAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.DockedPreview
            e.presentation.text = MpSqlVisualizerBundle.message("standalone.action.openDialog")
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            openDialogPending = true
            resolveFromInput()
        }
    }

    // ------------------------------------------------------------------
    // 解析与展示
    // ------------------------------------------------------------------

    /**
     * 解析当前输入并展示（内部入口）。流程：EDT 取文本快照 → 后台纯文本解析 →
     * 代次守卫回 EDT 装配。openDialog=true 时解析成功后额外走弹窗单例（D-3）。
     */
    fun resolveFromInput(openDialog: Boolean = false) {
        val text = inputEditor.text
        if (text.isBlank()) {
            setStatus(MpSqlVisualizerBundle.message("standalone.status.empty"))
            return
        }
        if (text.length > StandaloneSqlSourceResolver.MAX_TEXT_LENGTH) {
            setStatus(MpSqlVisualizerBundle.message("standalone.status.tooLong"))
            return
        }
        val generation = ++resolveGeneration
        openDialogPending = openDialog
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = runCatching { StandaloneSqlSourceResolver.resolve(text) }.getOrNull()
            ApplicationManager.getApplication().invokeLater(
                {
                    if (disposed || generation != resolveGeneration || project.isDisposed) return@invokeLater
                    applyResolved(resolved)
                },
                ModalityState.nonModal(),
            )
        }
    }

    private fun applyResolved(resolved: StandaloneSqlSourceResolver.Resolved?) {
        if (resolved == null) {
            setStatus(MpSqlVisualizerBundle.message("standalone.status.unrecognized"))
            return
        }
        val dialogRequested = openDialogPending
        openDialogPending = false
        val panel = ensurePreviewPanel()
        panel.showResult(resolved.result)
        previewHintLabel.isVisible = false
        setStatus(statusTextOf(resolved))
        if (dialogRequested) {
            SqlPreviewService.showResult(project, resolved.result)
        }
    }

    /** 识别结果如实呈现（状态条教会用户「插件认成了什么」）。 */
    private fun statusTextOf(resolved: StandaloneSqlSourceResolver.Resolved): String = when (resolved.kind) {
        StandaloneSqlSourceResolver.Kind.XML_STATEMENT -> {
            val id = resolved.statementId ?: "?"
            if (resolved.statementCount > 1) {
                MpSqlVisualizerBundle.message(
                    "standalone.status.xml.multi", id, resolved.statementCount,
                )
            } else {
                MpSqlVisualizerBundle.message("standalone.status.xml", id)
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
        openDialogPending = false
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
