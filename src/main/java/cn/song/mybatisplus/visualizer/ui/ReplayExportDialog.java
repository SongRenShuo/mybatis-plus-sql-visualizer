// 自 master ui/ReplayExportDialog.kt 移植（R36：回放脚本导出选项弹框）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;

/**
 * R36：回放脚本导出选项弹框（单项——是否用事务包裹）。
 *
 * <p>形态照 {@code cn.song.mybatisplus.visualizer.log.InjectionModulePickerDialog}
 * （DialogWrapper + JBCheckBox）；仅承载选择态，脚本组装全在
 * {@code cn.song.mybatisplus.visualizer.log.ReplayScriptExport}。</p>
 *
 * <p><b>方言无显式事务开始语句</b>（{@code transactionBegin} == null，Oracle/DM）时勾选框<b>禁用</b>
 * 并显示原因——诚实降级：不产出 {@code .sql} 里必然语法错误的伪包裹（见
 * {@code cn.song.mybatisplus.visualizer.sql.Dialect.transactionBegin} 的三档证据）。</p>
 */
public class ReplayExportDialog extends DialogWrapper {

    /** 当前方言名（禁用态说明文案用）。 */
    private final String dialectName;

    /** 本方言显式事务开始语句；null = 无（勾选框禁用）。 */
    private final String transactionBegin;

    public final JBCheckBox wrapCheck;

    public ReplayExportDialog(@Nullable Project project, @NotNull String dialectName,
                              @Nullable String transactionBegin) {
        super(project, true);
        this.dialectName = dialectName;
        this.transactionBegin = transactionBegin;
        this.wrapCheck = new JBCheckBox(
                MpSqlVisualizerBundle.message("replay.export.wrap"),
                transactionBegin != null);
        wrapCheck.setEnabled(transactionBegin != null);
        setTitle(MpSqlVisualizerBundle.message("replay.export.title"));
        init();
    }

    /** 程序化关闭（同 {@code SqlPreviewDialog.doDispose} / {@code RollbackSqlDialog.doDispose} 惯例；测试 tearDown 用）。 */
    public void doDispose() {
        dispose();
    }

    /** 生效的事务包裹意愿（禁用态恒 false，双保险）。 */
    public boolean isWrapTransaction() {
        return transactionBegin != null && wrapCheck.isSelected();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(8, 12));
        panel.setPreferredSize(new Dimension(440, 96));
        panel.add(wrapCheck);
        String hint = transactionBegin != null
                ? MpSqlVisualizerBundle.message("replay.export.wrap.hint", transactionBegin)
                : MpSqlVisualizerBundle.message("replay.export.wrap.unsupported", dialectName);
        JBLabel label = new JBLabel(hint);
        label.setBorder(JBUI.Borders.emptyTop(6));
        label.setForeground(UIUtil.getInactiveTextColor());
        panel.add(label);
        return panel;
    }
}
