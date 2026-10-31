package cn.song.mybatisplus.visualizer.share.ui

import cn.song.mybatisplus.visualizer.share.SqlShareManager
import cn.song.mybatisplus.visualizer.share.format.DiagnosticReportFormatter
import cn.song.mybatisplus.visualizer.share.format.HtmlReportFormatter
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileSaverDialog
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import java.io.File
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JRadioButton

/**
 * 分享选项对话框（F-8）。
 *
 * 提供七种分享方式：
 *  - 复制为 Markdown 代码块
 *  - 复制为 Markdown 表格
 *  - 复制为可执行 SQL
 *  - 导出 HTML 报告文件
 *  - R50：复制诊断报告（Markdown，五段聚合：SQL+参数/perf/EXPLAIN 建议/无界写/回滚预案）
 *  - R50：导出诊断报告（HTML）
 *  - R94：投影诊断草稿，逐段审阅后显式同意反馈
 *
 * 用户可填写来源位置（文件名:行号），附到分享内容中。
 */
class ShareDialog(
    private val project: Project?,
    private val panel: SqlPreviewPanel,
) : DialogWrapper(project, true) {

    private val sourceField = JBTextField(30)
    private val markdownRadio = JRadioButton(MpSqlVisualizerBundle.message("share.dialog.markdown"))
    private val tableRadio = JRadioButton(MpSqlVisualizerBundle.message("share.dialog.markdownTable"))
    private val executableRadio = JRadioButton(MpSqlVisualizerBundle.message("share.dialog.executableSql"))
    private val htmlRadio = JRadioButton(MpSqlVisualizerBundle.message("share.dialog.html"))
    // R50：诊断报告两档（Markdown 走剪贴板 / HTML 走文件——与既有分享两通道同形）
    private val diagnosticRadio = JRadioButton(MpSqlVisualizerBundle.message("share.dialog.diagnostic"))
    private val diagnosticHtmlRadio = JRadioButton(MpSqlVisualizerBundle.message("share.dialog.diagnosticHtml"))
    private val diagnosticFeedbackRadio = JRadioButton(MpSqlVisualizerBundle.message("share.dialog.diagnosticFeedback"))

    private val shareManager = SqlShareManager(project)

    init {
        title = MpSqlVisualizerBundle.message("share.dialog.title")
        // F-46：预填充当前代码位置（文件名:行号），用户可编辑覆盖
        val loc = panel.currentSourceLocation() ?: computeCurrentLocation()
        if (!loc.isNullOrEmpty()) {
            sourceField.text = loc
        }
        init()
    }

    /** 从当前编辑器实时计算代码位置（文件名:行号），兜底 panel 未设置 sourceLocation 的场景。 */
    private fun computeCurrentLocation(): String? {
        val proj = project ?: return null
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(proj).selectedTextEditor ?: return null
        val virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val line = editor.document.getLineNumber(editor.caretModel.offset) + 1
        return "${virtualFile.name}:$line"
    }

    override fun createCenterPanel(): JComponent {
        val root = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)
        }

        // 来源输入行
        val sourceRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel(MpSqlVisualizerBundle.message("share.dialog.source") + ":"), BorderLayout.WEST)
            add(sourceField, BorderLayout.CENTER)
            border = JBUI.Borders.emptyBottom(10)
        }
        root.add(sourceRow)

        // 选项单选
        val group = ButtonGroup().apply {
            add(markdownRadio)
            add(tableRadio)
            add(executableRadio)
            add(htmlRadio)
            add(diagnosticRadio)
            add(diagnosticHtmlRadio)
            add(diagnosticFeedbackRadio)
        }
        markdownRadio.isSelected = true

        val optionsPanel = JBPanel<JBPanel<*>>(GridLayout(7, 1, 0, 4)).apply {
            add(markdownRadio)
            add(tableRadio)
            add(executableRadio)
            add(htmlRadio)
            add(diagnosticRadio)
            add(diagnosticHtmlRadio)
            add(diagnosticFeedbackRadio)
            border = JBUI.Borders.emptyBottom(10)
        }
        root.add(optionsPanel)

        return root
    }

    override fun getPreferredFocusedComponent(): JComponent = sourceField

    /** 测试缝：返回中心面板（与 [createCenterPanel] 同构），供测试遍历断言。DialogWrapper 的
     *  rootPane/contentPane 仅在 show() 时构建，headless 下构造即可用本缝取面板。 */
    internal fun centerPanelForTest(): JComponent = createCenterPanel()

    override fun createActions(): Array<Action> = arrayOf(
        okAction.apply { putValue(Action.NAME, MpSqlVisualizerBundle.message("dialog.ok")) },
        cancelAction.apply { putValue(Action.NAME, MpSqlVisualizerBundle.message("share.dialog.cancel")) },
    )

    override fun doOKAction() {
        val source = sourceField.text.trim().takeIf { it.isNotEmpty() }
        when {
            markdownRadio.isSelected -> shareManager.copyAsMarkdown(panel, source)
            tableRadio.isSelected -> shareManager.copyAsMarkdownTable(panel, source)
            executableRadio.isSelected -> shareManager.copyExecutableSql(panel, source)
            htmlRadio.isSelected -> exportHtmlReport()
            diagnosticRadio.isSelected -> copyDiagnosticReport(source)
            diagnosticHtmlRadio.isSelected -> exportDiagnosticHtml(source)
            diagnosticFeedbackRadio.isSelected -> {
                val sql = panel.editorDocumentText()?.takeIf { it.isNotBlank() } ?: return
                cn.song.mybatisplus.visualizer.feedback.FeedbackReporting.openDiagnosticDialog(
                    project, feedbackDraft(sql),
                )
            }
        }
        super.doOKAction()
    }

    internal fun feedbackDraft(sql: String): cn.song.mybatisplus.visualizer.share.DiagnosticShareDraft {
        val projected = cn.song.mybatisplus.visualizer.share.ShareContentProjection.sql(sql)
        return cn.song.mybatisplus.visualizer.share.DiagnosticShareDraft(listOf(
            cn.song.mybatisplus.visualizer.share.DiagnosticShareDraft.Section(
                "summary", MpSqlVisualizerBundle.message("share.review.summary"),
                MpSqlVisualizerBundle.message("share.review.summaryText"),
            ),
            cn.song.mybatisplus.visualizer.share.DiagnosticShareDraft.Section(
                "sql", MpSqlVisualizerBundle.message("share.review.sql"),
                projected.sql ?: MpSqlVisualizerBundle.message("share.review.omitted"),
            ),
        ))
    }

    /** R50：诊断报告输入（各诊断段由纯分析器聚合，IDE 侧只取 SQL/参数/方言/来源）。 */
    private fun diagnosticInput(source: String?): DiagnosticReportFormatter.Input? {
        val sql = panel.editorDocumentText()?.takeIf { it.isNotBlank() } ?: return null
        return DiagnosticReportFormatter.buildInput(
            sql = sql,
            dialectName = panel.currentDialect(),
            sources = panel.unresolvedParams(),
            paramValues = panel.paramValuesSnapshot(),
            tableName = panel.currentTableName(),
            source = source,
            // NEW-21：语句模型在场时体检段逐方言真渲染（不在场则按文本证据判，如实降级）
            statement = panel.currentStatement(),
        )
    }

    /** R50：复制诊断报告（Markdown）到剪贴板。 */
    private fun copyDiagnosticReport(source: String?) {
        val input = diagnosticInput(source) ?: return
        cn.song.mybatisplus.visualizer.util.UiTextClipboard.copy(DiagnosticReportFormatter.format(input))
    }

    /** R50：导出诊断报告（HTML 文件，复用既有 HTML 落盘链）。 */
    private fun exportDiagnosticHtml(source: String?) {
        val input = diagnosticInput(source) ?: return
        val html = DiagnosticReportFormatter.formatHtml(input)
        val descriptor = FileSaverDescriptor(
            MpSqlVisualizerBundle.message("share.dialog.diagnosticHtml"),
            "Save diagnostic report",
            "html",
        )
        val dialog: FileSaverDialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val saved = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, "sql-diagnostic-report.html")
        if (saved != null) {
            val file = saved.file
            shareManager.saveHtmlReport(html, file.absolutePath)
            BrowserUtil.open(file.toURI().toString())
        }
    }

    /** 导出 HTML 报告（复用 [HtmlReportFormatter]）。 */
    private fun exportHtmlReport() {
        val sql = panel.editorDocumentText()?.takeIf { it.isNotBlank() } ?: return
        val html = HtmlReportFormatter.format(
            sql = sql,
            sources = panel.unresolvedParams(),
            paramValues = panel.paramValuesSnapshot(),
            tableName = panel.currentTableName(),
            dialect = panel.currentDialect(),
        )
        val descriptor = FileSaverDescriptor(
            MpSqlVisualizerBundle.message("share.dialog.html"),
            "Save HTML report",
            "html",
        )
        val dialog: FileSaverDialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val saved = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, "sql-report.html")
        if (saved != null) {
            val file = saved.file
            shareManager.saveHtmlReport(html, file.absolutePath)
            BrowserUtil.open(file.toURI().toString())
        }
    }
}
