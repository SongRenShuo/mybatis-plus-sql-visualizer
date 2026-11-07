package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * R36：回放脚本导出选项弹框（单项——是否用事务包裹）。
 *
 * 形态照 [cn.song.mybatisplus.visualizer.log.InjectionModulePickerDialog]（DialogWrapper + JBCheckBox）；
 * 仅承载选择态，脚本组装全在 [cn.song.mybatisplus.visualizer.log.ReplayScriptExport]。
 *
 * **方言无显式事务开始语句**（[transactionBegin] == null，Oracle/DM）时勾选框**禁用**并显示原因——
 * 诚实降级：不产出 `.sql` 里必然语法错误的伪包裹（见
 * [cn.song.mybatisplus.visualizer.sql.Dialect.transactionBegin] 的三档证据）。
 */
class ReplayExportDialog(
    project: Project?,
    /** 当前方言名（禁用态说明文案用）。 */
    private val dialectName: String,
    /** 本方言显式事务开始语句；null = 无（勾选框禁用）。 */
    private val transactionBegin: String?,
) : DialogWrapper(project, true) {

    internal val wrapCheck: JBCheckBox = JBCheckBox(
        MpSqlVisualizerBundle.message("replay.export.wrap"),
        transactionBegin != null,
    ).apply { isEnabled = transactionBegin != null }

    init {
        title = MpSqlVisualizerBundle.message("replay.export.title")
        init()
    }

    /** 程序化关闭（同 [SqlPreviewDialog.doDispose] / [RollbackSqlDialog.doDispose] 惯例；测试 tearDown 用）。 */
    fun doDispose() {
        dispose()
    }

    /** 生效的事务包裹意愿（禁用态恒 false，双保险）。 */
    internal val wrapTransaction: Boolean
        get() = transactionBegin != null && wrapCheck.isSelected

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12)
            preferredSize = Dimension(440, 96)
        }
        panel.add(wrapCheck)
        val hint = if (transactionBegin != null) {
            MpSqlVisualizerBundle.message("replay.export.wrap.hint", transactionBegin)
        } else {
            MpSqlVisualizerBundle.message("replay.export.wrap.unsupported", dialectName)
        }
        panel.add(
            JBLabel(hint).apply {
                border = JBUI.Borders.emptyTop(6)
                foreground = UIUtil.getInactiveTextColor()
            },
        )
        return panel
    }
}
