package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent

/**
 * T-PreviewSideQuery P2：非模态承载 EXPLAIN 结果，不替换预览编辑器。
 *
 * 结构化视图（[ExplainPlanResultView]）与直送结果表格都通过本对话框展示；关闭后
 * 预览 SQL/参数仍在原处可继续编辑。
 */
internal class ExplainPlanDialog(
    project: Project,
    private val content: JComponent,
) : DialogWrapper(project, false) {

    init {
        title = MpSqlVisualizerBundle.message("panel.explain")
        isModal = false
        init()
        // 插件语言独立于 IDE 语言：平台 cancelAction 文案跟随 IDE（英文 IDE 下显示 Cancel），
        // 必须按插件 bundle 覆盖（同 SqlPreviewDialog 的既有做法）。
        setCancelButtonText(MpSqlVisualizerBundle.message("panel.explain.close"))
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(content).apply {
        preferredSize = Dimension(900, 560)
        border = JBUI.Borders.empty()
    }

    override fun createActions(): Array<javax.swing.Action> = arrayOf(cancelAction)

    internal fun contentForTest(): JComponent = content

    /** 测试访问器：取消按钮文案（断言插件语言绑定，不受 IDE 语言影响）。 */
    internal fun cancelButtonTextForTest(): String = getButtonMap()[cancelAction]?.text ?: ""
}

/** 测试缝：headless 不显示真实窗口；生产为 null 走 dialog.show()。 */
@Volatile
internal var explainDialogSinkForTest: ((ExplainPlanDialog) -> Unit)? = null

/** 关闭上一个结果对话框后展示新的；注册到面板 Disposer 树，面板关闭时一并回收。 */
internal fun SqlPreviewPanel.showExplainDialog(dialog: ExplainPlanDialog) {
    explainResultDialog?.close(0)
    explainResultDialog = dialog
    val sink = explainDialogSinkForTest
    if (sink != null) {
        sink(dialog)
        return
    }
    // DialogWrapper 不实现 Disposable（243）；用 Disposable 包装关闭动作挂到面板树。
    Disposer.register(this, com.intellij.openapi.Disposable { dialog.close(0) })
    dialog.show()
}

internal fun SqlPreviewPanel.showExplainDialog(view: JComponent) {
    val proj = project ?: return
    showExplainDialog(ExplainPlanDialog(proj, view))
}

/**
 * T-ExplainConsoleFirst W1（2026-10-24）：原「直送结果只读表格对话框」已随形态 B 一起退役
 * ——EXPLAIN 默认与回退全部走内嵌原生控制台（`submitExplainDirectConsole`），计划由平台
 * 控制台原生呈现。结构化形态（`explainStructuredRender`）用 `ExplainPlanResultView` 渲染，
 * 从不经过本函数。留此注释只为记录去向，避免后来者以为漏了某个表格分支。
 */

/** 无源/失败引导：给出原因，并保留显式出口（在控制台打开 / 去 Database 工具窗口）。 */
internal fun SqlPreviewPanel.showExplainErrorDialog(
    message: String,
    onOpenConsole: () -> Unit = {},
    /** true=无绑定数据源场景：按钮改为打开 Database 工具窗口（按提示去选源），而非空跑执行。 */
    openDatabase: Boolean = false,
) {
    val proj = project ?: return
    var dialog: ExplainPlanDialog? = null
    val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(
            JBLabel("<html>" + escapeHtml(message) + "</html>").apply {
                border = JBUI.Borders.empty(8)
                foreground = com.intellij.ui.JBColor.RED
            },
            BorderLayout.CENTER,
        )
        add(
            JButton(
                MpSqlVisualizerBundle.message(
                    if (openDatabase) "panel.explain.openDatabase" else "panel.explain.openConsole",
                ),
            ).apply {
                addActionListener {
                    if (openDatabase) {
                        cn.song.mybatisplus.visualizer.execute.DataSourceSelector
                            .openDatabaseToolWindow(proj)
                    } else {
                        onOpenConsole()
                    }
                    // 动作后关闭错误对话框：否则数据库窗口/控制台被它挡在后面，用户看着像「没反应」。
                    runCatching { dialog?.close(0) }
                }
            },
            BorderLayout.SOUTH,
        )
    }
    dialog = ExplainPlanDialog(proj, panel)
    showExplainDialog(dialog!!)
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
