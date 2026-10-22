package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Toolkit;

/**
 * SQL 预览弹窗（M3-02，F-07 弹窗展示方式）。
 *
 * 复用 SqlPreviewPanel 展示 SQL 与参数对照，底部提供"复制 / 关闭"。
 */
public class SqlPreviewDialog extends DialogWrapper {

    private final SqlPreviewPanel panel;

    /**
     * T-DmlImpactProminence：底部蓝色主行动——复制 SQL 并关闭（复制成功提示随之关闭面板）。
     *
     * <p>字段初始化先于构造器体（init() 会在构造期同步调用 createActions()，本字段若在
     * init() 之后才赋值，动作数组里出现空位、按钮不渲染——UI-042：用户侧表现为
     * 「复制 SQL 并关闭」按钮整个不见）。</p>
     */
    private final Action copyAndCloseAction = new DialogWrapperAction(
            MpSqlVisualizerBundle.message("panel.copySqlAndClose")) {
        @Override
        protected void doAction(java.awt.event.ActionEvent actionEvent) {
            // UI-044：弹窗随即关闭，「关闭 SQL 面板」动作已无目标，通知里不再挂它
            SqlPreviewPanelSqlViewKt.copySql(panel, false);
            doDispose();
        }
    };

    /**
     * 构造期首次 createActions 的产出（UI-042 回归缝）。
     *
     * <p>init() 同步触发 createActions()；此捕获只记首次（后续测试再调 createActions 不覆盖）。</p>
     */
    public Action[] actionsAtConstructionForTest = null;

    public SqlPreviewDialog(@Nullable Project project, @NotNull PreviewResult result) {
        this(project, result, null);
    }

    public SqlPreviewDialog(@Nullable Project project, @NotNull PreviewResult result, @Nullable String sourceLocation) {
        super(project, false, DialogWrapper.IdeModalityType.MODELESS);
        this.panel = new SqlPreviewPanel(project);
        setTitle(MpSqlVisualizerBundle.message("dialog.preview.title"));
        panel.setSourceLocation(sourceLocation);
        SqlPreviewPanelResultViewKt.showResult(panel, result);
        Disposer.register(getDisposable(), panel);
        panel.onClosePanelRequested = this::dispose;
        panel.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
        init();
        setCancelButtonText(MpSqlVisualizerBundle.message("dialog.preview.cancel"));
        Dimension size = initialSize();
        setSize(size.width, size.height);
        // T-K2JWalk1 KW-1：弹窗自挂 project Disposer 树——工程关闭（含 Open This Window 切换）
        // 时平台遍历树执行本回调收口存活弹窗，根除「预览弹窗跨工程残留」（首个修法把收口挂在
        // SqlPreviewService 构造器，但 gutter 的 XML 链在 getService 之前就 return，服务从未
        // 实例化、收口从未挂上——真机复测残留依旧后改挂此处，弹窗创建即注册必在树上）。
        // 幂等：先被关旧开新/用户关闭 dispose 过的弹窗此处 isDisposed 直接跳过。
        if (project != null) {
            Disposer.register(project, () -> {
                if (!isDisposed()) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewDialog.class)
                            .info("[KW-1] project closing: dispose leftover preview dialog");
                    doDispose();
                }
            });
        }
    }

    @NotNull
    @Override
    protected JComponent createCenterPanel() {
        return panel;
    }

    /**
     * T-RefreshDbSteal F2：程序化关闭（供 SqlPreviewService 弹窗单例化关旧开新）。
     * DialogWrapper.dispose 在平台侧 protected，同类内提升为 public 包装——语义与用户
     * 点关闭一致（级联 Disposer 释放面板，rehost 监听随之移除）。
     */
    public void doDispose() {
        dispose();
    }

    /** internal 测试访问器（面板「关闭 SQL 面板」回调链路断言）。 */
    public SqlPreviewPanel panelForTest() {
        return panel;
    }

    @NotNull
    @Override
    protected Action[] createActions() {
        Action[] actions = new Action[]{
                // 执行 SQL（M17：选择数据源 → 临时文件 → 可执行；提示由面板/支持层统一处理）
                new DialogWrapperAction(MpSqlVisualizerBundle.message("panel.executeSql")) {
                    @Override
                    protected void doAction(java.awt.event.ActionEvent actionEvent) {
                        SqlPreviewPanelExecutionKt.executeSql(panel);
                    }
                },
                new DialogWrapperAction(MpSqlVisualizerBundle.message("panel.copySql")) {
                    @Override
                    protected void doAction(java.awt.event.ActionEvent actionEvent) {
                        SqlPreviewPanelSqlViewKt.copySql(panel);
                    }
                },
                copyAndCloseAction,
                // 关闭
                getCancelAction()
        };
        if (actionsAtConstructionForTest == null) actionsAtConstructionForTest = actions;
        return actions;
    }

    /** 复制并关闭按钮按主行动样式渲染（蓝色实底）；其余按钮保持平台默认。 */
    @Override
    protected javax.swing.JButton createJButtonForAction(Action action) {
        javax.swing.JButton button = super.createJButtonForAction(action);
        if (action == copyAndCloseAction) SqlPreviewPanelExecutionKt.applyPrimaryButtonStyle(button);
        return button;
    }

    /** UI-043 测试访问器（createJButtonForAction 在 DialogWrapper 内为 protected）。 */
    public javax.swing.JButton createJButtonForActionForTest(Action action) {
        return createJButtonForAction(action);
    }

    /** T-DmlImpactProminence：测试访问器（createActions 在 DialogWrapper 内为 protected）。 */
    public Action[] actionsForTest() {
        return createActions();
    }

    @Override
    public String getDimensionServiceKey() {
        return DIMENSION_KEY;
    }

    public static final String DIMENSION_KEY = "cn.song.mybatisplus.visualizer.SqlPreviewDialog";
    public static final int MIN_WIDTH = 640;
    public static final int MIN_HEIGHT = 420;
    public static final double SCREEN_RATIO_WIDTH = 0.62;
    public static final double SCREEN_RATIO_HEIGHT = 0.65;
    public static final double SCREEN_RATIO_CAP = 0.85;
    public static final int DEFAULT_WIDTH = 900;
    public static final int DEFAULT_HEIGHT = 600;

    /**
     * 计算弹窗默认尺寸（M14）：按屏幕比例（宽 62% / 高 65%），
     * 夹在「下限 900×600」与「屏幕 85% 上限」之间；小屏时上限优先（不超屏）。
     */
    public static Dimension initialSize() {
        return initialSize(screenWidthOf(), screenHeightOf());
    }

    public static Dimension initialSize(int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) return new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        int w = fitWithin((int) (screenWidth * SCREEN_RATIO_WIDTH), screenWidth, DEFAULT_WIDTH);
        int h = fitWithin((int) (screenHeight * SCREEN_RATIO_HEIGHT), screenHeight, DEFAULT_HEIGHT);
        return new Dimension(w, h);
    }

    private static int fitWithin(int ratioValue, int screenSize, int minDefault) {
        int cap = (int) (screenSize * SCREEN_RATIO_CAP);
        return minDefault <= cap ? Math.min(Math.max(ratioValue, minDefault), cap) : cap;
    }

    private static int screenWidthOf() {
        try {
            return Toolkit.getDefaultToolkit().getScreenSize().width;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int screenHeightOf() {
        try {
            return Toolkit.getDefaultToolkit().getScreenSize().height;
        } catch (Throwable t) {
            return 0;
        }
    }
}
