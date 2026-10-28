// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/DegradationBanner.kt 移植
// T-SyncK2JMaster184 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/DegradationBanner.kt 移植（T-PrewarmLateRecover F2：DEGRADED 可挂「重试」动作）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import java.awt.BorderLayout;

/**
 * 面板内嵌诚实降级横幅（T-PreviewUx-Pack #2，吸收 DBX「诚实降级」口径）。
 *
 * 面板内嵌的**非模态单行提示**，不算弹窗（docs/规范/设计规范-弹窗克制红线.md 口径：面板内嵌
 * 提示不属于弹窗形态，弹窗克制零新增）。触发即显示、恢复即隐藏（不占高度）。
 * 文案三要素由各 i18n 键完整承载：哪段没还原 / 为什么 / 用户能做什么，绝不静默失败。
 *
 * 视觉照既有同类实现（LogToSqlPanel statusLabel / SqlPreviewPanel meta 警示同款）：
 * `ErrorForeground` 命名色 + JBUI 内边距，明暗主题自适应；文案自带 `⚠ ` 前缀（同 meta 行
 * 警示风格），不引入新图标形态。
 *
 * 消息类别与优先级（{@link Kind}，高优先不被低优先覆盖/清除）：
 * <pre>
 *  DEGRADED（降级链，最高）&gt; RENDER_WARNING（渲染警示）&gt; DOLLAR_INJECT（`${}` 直替
 *  信息提示，T-DollarInjectMark——措辞中性的注入面提示，**可关闭**：仅该类别带 ✕ 按钮，
 *  点击回调 {@link #onDismiss} 由调用方做会话级记忆）。
 * </pre>
 *
 * T-PrewarmLateRecover F2：DEGRADED 可挂一个<b>动作</b>（{@link #onRetry}，文案 {@code panel.banner.retry}）——
 * 预热硬失败时没有产物可等，只能由用户点一次重新臂预热。动作标签照抄同文件 ✕ 按钮的形态
 * （JBLabel + HAND_CURSOR + MouseAdapter），不新造控件类型；两者共用 EAST 位（横排容器）。
 */
public class DegradationBanner extends JBPanel<DegradationBanner> {

    /** 消息类别（同类别「恢复即清除」；高优先类别不被低优先类别清除）。 */
    public enum Kind { RENDER_WARNING, DEGRADED, DOLLAR_INJECT }

    /** 类别优先级（数值大者优先——show 低优先不覆盖高优先）。 */
    private static int priority(Kind kind) {
        switch (kind) {
            case DEGRADED:
                return 2;
            case RENDER_WARNING:
                return 1;
            case DOLLAR_INJECT:
                return 0;
        }
        throw new IllegalArgumentException("unknown kind: " + kind);
    }

    /** T-DollarInjectMark：DOLLAR_INJECT 的 ✕ 关闭回调（调用方负责会话级记忆与 clear）。 */
    @Nullable
    public Runnable onDismiss = null;

    /**
     * T-PrewarmLateRecover F2：DEGRADED 的「重试」回调（null = 不显示动作）。
     * 调用方在挂降级横幅时接上（SqlPreviewPanel.showDegradedBanner），主路径装配成功时摘除。
     */
    @Nullable
    public Runnable onRetry = null;

    private final JBLabel label;

    /** 仅 DOLLAR_INJECT 可见的信息级关闭按钮（错误/降级类保持常驻不可关）。 */
    private final JBLabel dismissButton;

    /**
     * T-PrewarmLateRecover F2：「重试」动作标签（形态同 {@link #dismissButton}——HAND_CURSOR + 点击回调）。
     * 文案在 {@link #show} 时按当前语言解析（语言设置可在运行期切换，构造期定死会跟着一起旧）。
     */
    private final JBLabel retryLabel;

    /** EAST 位横排容器：动作标签 + ✕ 关闭按钮共用（BorderLayout 的 EAST 只能放一个组件）。 */
    private final JBPanel<JBPanel<?>> actions;

    /** 当前挂起的消息类别（null = 无提示）。 */
    @Nullable
    private Kind currentKind = null;

    public DegradationBanner() {
        super(new BorderLayout());

        label = new JBLabel();
        label.setForeground(JBColor.namedColor("ErrorForeground", JBColor.RED));
        label.setBorder(JBUI.Borders.empty(4, 8, 4, 8));
        // 详情可能含 SQL 片段（`<` 开头），禁用 HTML 解析纯文本渲染（SqlSelectionDialog 同款约定）
        label.putClientProperty("html.disable", true);

        dismissButton = new JBLabel("✕");
        dismissButton.setToolTipText(null);
        dismissButton.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        dismissButton.setBorder(JBUI.Borders.empty(4, 8, 4, 8));
        dismissButton.setForeground(JBColor.namedColor("ErrorForeground", JBColor.RED));
        dismissButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (onDismiss != null) onDismiss.run();
            }
        });
        dismissButton.setVisible(false);

        retryLabel = new JBLabel();
        retryLabel.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        retryLabel.setBorder(JBUI.Borders.empty(4, 8, 4, 8));
        retryLabel.setForeground(JBColor.namedColor("Link.activeForeground", JBColor.BLUE));
        retryLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (onRetry != null) onRetry.run();
            }
        });
        retryLabel.setVisible(false);

        actions = new JBPanel<>(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(retryLabel);
        actions.add(dismissButton);

        setOpaque(false);
        add(label, BorderLayout.CENTER);
        add(actions, BorderLayout.EAST);
        setVisible(false);
    }

    /** 显示提示（文案由调用方按 i18n 键组装三要素）。 */
    public void show(Kind kind, String message) {
        // 高优先类别挂起期间，低优先提示不覆盖（DEGRADED 常驻语义的泛化——
        // RENDER_WARNING 挂起时 DOLLAR_INJECT 信息提示同样让位，警示清除后下次更新再回）
        Kind current = currentKind;
        if (current != null && priority(current) > priority(kind)) return;
        currentKind = kind;
        label.setText(message);
        dismissButton.setVisible(kind == Kind.DOLLAR_INJECT);
        dismissButton.setToolTipText(message);
        // T-PrewarmLateRecover F2：动作只在「有回调的降级提示」上出现
        boolean retryVisible = kind == Kind.DEGRADED && onRetry != null;
        retryLabel.setVisible(retryVisible);
        if (retryVisible) retryLabel.setText(MpSqlVisualizerBundle.message("panel.banner.retry"));
        actions.setVisible(retryVisible || dismissButton.isVisible());
        setVisible(true);
    }

    /**
     * 清除指定类别：当前挂起的是该类别才隐藏——渲染警示（低优先）清除不影响挂起的降级提示。
     */
    public void clear(Kind kind) {
        if (currentKind != kind) return;
        currentKind = null;
        label.setText("");
        dismissButton.setVisible(false);
        retryLabel.setVisible(false);
        actions.setVisible(false);
        setVisible(false);
    }

    /** 测试访问器：当前文案（显隐断言用 isVisible）。 */
    public String textForTest() {
        return label.getText();
    }

    /** 测试访问器：当前挂起类别。 */
    @Nullable
    public Kind kindForTest() {
        return currentKind;
    }

    /** 测试访问器：✕ 关闭按钮可见性（仅 DOLLAR_INJECT）。 */
    public boolean dismissVisibleForTest() {
        return dismissButton.isVisible();
    }

    /** 测试触发器：模拟点击 ✕。 */
    public void clickDismissForTest() {
        if (dismissButton.isVisible() && onDismiss != null) onDismiss.run();
    }

    /** T-PrewarmLateRecover F2：测试访问器——「重试」动作可见性（仅挂了回调的 DEGRADED）。 */
    public boolean retryVisibleForTest() {
        return retryLabel.isVisible();
    }

    /** T-PrewarmLateRecover F2：测试访问器——动作文案（i18n 键解析结果）。 */
    public String retryTextForTest() {
        return retryLabel.getText();
    }

    /** T-PrewarmLateRecover F2：测试触发器——模拟点击动作（不可见时不触发，同 {@link #clickDismissForTest}）。 */
    public void clickRetryForTest() {
        if (retryLabel.isVisible() && onRetry != null) onRetry.run();
    }
}
