package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.Dimension;

/**
 * 完整提示信息查看对话框（T-StandaloneHintAndWarning E）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>预览面板的警告同时出现在两处：meta 行（<b>中段省略</b>，保留头尾）与警告横幅（单行 {@code JBLabel}，
 * 超宽由 Swing 尾部裁切）。用户 2026-11-18 报障「错误信息无法查看完整的」——横幅此前连 tooltip
 * 都没接（{@code updateMeta} 调 {@code show()} 漏传第三参），而 tooltip 本身也只能瞄一眼。
 *
 * <p>本对话框给出确定入口：整段文本、可全选复制、可滚动（超长警告不截断）。
 * 「复制错误信息」按钮仍是快捷路径（复制同一份 {@code currentWarning}），两者内容一致。
 */
public class WarningDetailDialog extends DialogWrapper {

    private static final String DIMENSION_KEY = "cn.song.mybatisplus.visualizer.WarningDetailDialog";

    private final String text;

    public WarningDetailDialog(@Nullable Project project, String text) {
        super(project, false);
        this.text = text;
        setTitle(MpSqlVisualizerBundle.message("panel.warningDetail.title"));
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBTextArea area = new JBTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(JBUI.Borders.empty(6));
        // 文本可能含 SQL 片段（`<` 开头）：纯文本控件，不做 HTML 解析
        area.setCaretPosition(0);
        JBScrollPane scroll = new JBScrollPane(area);
        scroll.setPreferredSize(new Dimension(720, 200));
        scroll.setBorder(JBUI.Borders.emptyTop(6));
        return scroll;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{
            new DialogWrapperAction(MpSqlVisualizerBundle.message("panel.warningDetail.copy")) {
                @Override
                protected void doAction(java.awt.event.ActionEvent actionEvent) {
                    UiTextClipboard.copy(text);
                }
            },
            getCancelAction(),
        };
    }

    @Override
    protected String getDimensionServiceKey() {
        return DIMENSION_KEY;
    }

    /**
     * 打开完整提示对话框（无 warning 时无操作）。
     *
     * <p>master 侧为 {@code SqlPreviewPanel} 扩展函数，Java 侧落本类静态方法（调用点
     * {@code SqlPreviewPanelResultView} 的横幅「看全文」回调）。
     */
    public static void showWarningDetails(SqlPreviewPanel panel) {
        String warning = panel.currentWarning;
        if (warning == null) {
            return;
        }
        new WarningDetailDialog(panel.project, warning).show();
    }
}
