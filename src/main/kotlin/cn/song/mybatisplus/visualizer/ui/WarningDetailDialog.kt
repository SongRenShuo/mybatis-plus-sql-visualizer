package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

/**
 * 完整提示信息查看对话框（T-StandaloneHintAndWarning E）。
 *
 * ## 为什么需要它
 *
 * 预览面板的警告同时出现在两处：meta 行（**中段省略**，保留头尾）与警告横幅（单行 `JBLabel`，
 * 超宽由 Swing 尾部裁切）。用户 2026-11-18 报障「错误信息无法查看完整的」——横幅此前连 tooltip
 * 都没接（`updateMeta` 调 `show()` 漏传第三参），而 tooltip 本身也只能瞄一眼。
 *
 * 本对话框给出确定入口：整段文本、可全选复制、可滚动（超长警告不截断）。
 * 「复制错误信息」按钮仍是快捷路径（复制同一份 `currentWarning`），两者内容一致。
 */
class WarningDetailDialog(
    project: Project?,
    private val text: String,
) : DialogWrapper(project, false) {

    init {
        title = MpSqlVisualizerBundle.message("panel.warningDetail.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val area = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6)
            // 文本可能含 SQL 片段（`<` 开头）：纯文本控件，不做 HTML 解析
            caretPosition = 0
        }
        return JBScrollPane(area).apply {
            preferredSize = Dimension(720, 200)
            border = JBUI.Borders.emptyTop(6)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : DialogWrapperAction(MpSqlVisualizerBundle.message("panel.warningDetail.copy")) {
            override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
                UiTextClipboard.copy(text)
            }
        },
        cancelAction,
    )

    override fun getDimensionServiceKey(): String = DIMENSION_KEY

    companion object {
        private const val DIMENSION_KEY = "cn.song.mybatisplus.visualizer.WarningDetailDialog"
    }
}

/** 打开完整提示对话框（无 warning 时无操作）。 */
internal fun SqlPreviewPanel.showWarningDetails() {
    val warning = currentWarning ?: return
    WarningDetailDialog(project, warning).show()
}
