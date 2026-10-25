// 自 master ui/ExplainPlanDialog.kt 移植（T-PreviewSideQuery P2：EXPLAIN 结果非模态承载；
// T-ExplainConsoleFirst W1：直送结果只读表格对话框退役——EXPLAIN 默认与回退全部走内嵌原生控制台）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * T-PreviewSideQuery P2：非模态承载 EXPLAIN 结果，不替换预览编辑器。
 *
 * <p>结构化视图（{@link ExplainPlanResultView}）经本对话框展示；关闭后预览 SQL/参数仍在原处
 * 可继续编辑。同文件的 {@code SqlPreviewPanel} 扩展函数（showExplainDialog/showExplainErrorDialog）
 * 落为本类的静态承载（{@code fun SqlPreviewPanel.foo} → {@code static R foo(SqlPreviewPanel, ...)}）。</p>
 */
public class ExplainPlanDialog extends DialogWrapper {

    private final JComponent content;

    public ExplainPlanDialog(@NotNull Project project, @NotNull JComponent content) {
        super(project, false);
        this.content = content;
        setTitle(MpSqlVisualizerBundle.message("panel.explain"));
        setModal(false);
        init();
        // 插件语言独立于 IDE 语言：平台 cancelAction 文案跟随 IDE（英文 IDE 下显示 Cancel），
        // 必须按插件 bundle 覆盖（同 SqlPreviewDialog 的既有做法）。
        setCancelButtonText(MpSqlVisualizerBundle.message("panel.explain.close"));
    }

    @Override
    protected JComponent createCenterPanel() {
        JBScrollPane pane = new JBScrollPane(content);
        pane.setPreferredSize(new Dimension(900, 560));
        pane.setBorder(JBUI.Borders.empty());
        return pane;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getCancelAction()};
    }

    /** 测试访问器：承载内容。 */
    @NotNull
    public JComponent contentForTest() {
        return content;
    }

    /** 测试访问器：取消按钮文案（断言插件语言绑定，不受 IDE 语言影响）。 */
    @NotNull
    public String cancelButtonTextForTest() {
        javax.swing.JButton button = getButton(getCancelAction());
        return button != null && button.getText() != null ? button.getText() : "";
    }

    /** 测试缝：headless 不显示真实窗口；生产为 null 走 dialog.show()。 */
    public static volatile java.util.function.Consumer<ExplainPlanDialog> explainDialogSinkForTest = null;

    /** 关闭上一个结果对话框后展示新的；注册到面板 Disposer 树，面板关闭时一并回收。 */
    public static void showExplainDialog(@NotNull SqlPreviewPanel p, @NotNull ExplainPlanDialog dialog) {
        if (p.explainResultDialog != null) {
            p.explainResultDialog.close(0);
        }
        p.explainResultDialog = dialog;
        java.util.function.Consumer<ExplainPlanDialog> sink = explainDialogSinkForTest;
        if (sink != null) {
            sink.accept(dialog);
            return;
        }
        // DialogWrapper 不实现 Disposable（243）；用 Disposable 包装关闭动作挂到面板树。
        Disposer.register(p, new com.intellij.openapi.Disposable() {
            @Override
            public void dispose() {
                dialog.close(0);
            }
        });
        dialog.show();
    }

    /** Kotlin 默认参数等价重载：以视图直接建对话框展示（project 为 null 时静默返回）。 */
    public static void showExplainDialog(@NotNull SqlPreviewPanel p, @NotNull JComponent view) {
        Project proj = p.project;
        if (proj == null) return;
        showExplainDialog(p, new ExplainPlanDialog(proj, view));
    }

    /**
     * T-ExplainConsoleFirst W1（2026-10-24）：原「直送结果只读表格对话框」已随形态 B 一起退役
     * ——EXPLAIN 默认与回退全部走内嵌原生控制台（{@code submitExplainDirectConsole}），计划由平台
     * 控制台原生呈现。结构化形态（{@code explainStructuredRender}）用 {@link ExplainPlanResultView}
     * 渲染，从不经过该表格分支。留此注释只为记录去向，避免后来者以为漏了某个表格分支。
     */

    /** Kotlin 默认参数等价重载：{@code onOpenConsole={}}、{@code openDatabase=false}。 */
    public static void showExplainErrorDialog(@NotNull SqlPreviewPanel p, @NotNull String message) {
        showExplainErrorDialog(p, message, () -> {
        }, false);
    }

    /** Kotlin 默认参数等价重载：{@code openDatabase=false}。 */
    public static void showExplainErrorDialog(@NotNull SqlPreviewPanel p, @NotNull String message,
                                              @NotNull Runnable onOpenConsole) {
        showExplainErrorDialog(p, message, onOpenConsole, false);
    }

    /**
     * 无源/失败引导：给出原因，并保留显式出口（在控制台打开 / 去 Database 工具窗口）。
     *
     * @param openDatabase true=无绑定数据源场景：按钮改为打开 Database 工具窗口（按提示去选源），
     *                     而非空跑执行。
     */
    public static void showExplainErrorDialog(@NotNull SqlPreviewPanel p, @NotNull String message,
                                              @NotNull Runnable onOpenConsole, boolean openDatabase) {
        Project proj = p.project;
        if (proj == null) return;
        final ExplainPlanDialog[] dialogRef = {null};
        JBPanel<JBPanel<?>> panel = new JBPanel<JBPanel<?>>(new BorderLayout());
        JBLabel messageLabel = new JBLabel("<html>" + escapeHtml(message) + "</html>");
        messageLabel.setBorder(JBUI.Borders.empty(8));
        messageLabel.setForeground(com.intellij.ui.JBColor.RED);
        panel.add(messageLabel, BorderLayout.CENTER);
        javax.swing.JButton actionButton = new javax.swing.JButton(MpSqlVisualizerBundle.message(
                openDatabase ? "panel.explain.openDatabase" : "panel.explain.openConsole"));
        actionButton.addActionListener(e -> {
            if (openDatabase) {
                cn.song.mybatisplus.visualizer.execute.DataSourceSelector.openDatabaseToolWindow(proj);
            } else {
                onOpenConsole.run();
            }
            // 动作后关闭错误对话框：否则数据库窗口/控制台被它挡在后面，用户看着像「没反应」。
            try {
                if (dialogRef[0] != null) dialogRef[0].close(0);
            } catch (Throwable ignored) {
            }
        });
        panel.add(actionButton, BorderLayout.SOUTH);
        ExplainPlanDialog dialog = new ExplainPlanDialog(proj, panel);
        dialogRef[0] = dialog;
        showExplainDialog(p, dialog);
    }

    private static String escapeHtml(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
