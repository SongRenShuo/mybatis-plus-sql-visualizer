package cn.song.mybatisplus.visualizer.log;

import javax.swing.JPanel;
import java.awt.Graphics;

/**
 * 类型色条（T-DdlTypeColor）。
 *
 * <p>两种形态：</p>
 * <ul>
 *  <li><b>实心</b>（DML：SELECT / INSERT / UPDATE / DELETE）——沿用改造前的纯背景填充；</li>
 *  <li><b>虚线</b>（DDL：CREATE / DROP）——按竖向「实/空」交替绘制，表达「结构性操作、非普通数据读写」。</li>
 * </ul>
 *
 * <p>为什么 DDL 用虚线而非加粗/双色：虚线不额外占宽度（侧栏空间敏感），且语义上「不连续」
 * 恰好对应「不连续的数据操作」；加粗会与相邻行挤在一起，双色在 3px 里塞两段几乎看不出差别。</p>
 *
 * <p>性能约束（T-LogListFreeze 同族）：{@code paintComponent} 是渲染热路径，
 * <b>不得新建 Color/BasicStroke 等对象</b>——颜色来自 {@link SqlListTypeColor} 的实例缓存
 * （{@code getBackground()}），虚线段长是编译期常量，绘制只用 {@code fillRect}
 * （无抗锯齿路径、无 stroke 对象）。</p>
 */
public class TypeColorBar extends JPanel {
    private boolean dashed = false;

    private int repaintRequests = 0;

    /**
     * 是否画虚线。由 {@code applyCrudColorMark} 按语句类型设置。
     *
     * <p>只在值变化时触发 repaint（与 {@code setBackgroundIfChanged} 同族守卫）——
     * 渲染器逐格调用，无脑赋值会让每格多一次重绘调度。</p>
     */
    public void setDashed(boolean value) {
        if (this.dashed == value) return;
        this.dashed = value;
        repaintRequests++;
        repaint();
    }

    public boolean isDashed() {
        return dashed;
    }

    /** 实际触发的重绘次数（测试缝：验证「同值不重复重绘」这条热路径守卫）。 */
    public int getRepaintRequests() {
        return repaintRequests;
    }

    {
        setOpaque(true);
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!dashed) {
            // 实心：走父类的纯背景填充（与改造前逐字节一致）
            super.paintComponent(g);
            return;
        }
        // 虚线：不铺底色 —— 空白段必须真留空，让父容器背景透出。
        // 早期版本「先填 background 再按段填 background」等于全实心（前景与底色同色），
        // 由 DashProbe 实测的像素序列 `########` 抓出。
        java.awt.Color saved = g.getColor();
        g.setColor(getBackground());
        int y = 0;
        while (y < getHeight()) {
            g.fillRect(0, y, getWidth(), Math.min(DASH_ON, getHeight() - y));
            y += DASH_ON + DASH_OFF;
        }
        g.setColor(saved);
    }

    /** 实/空段长（px）。行高 20~80px 下分别有 3~13 个循环，视觉上稳定可辨。 */
    public kotlin.Pair<Integer, Integer> dashPatternForTest() {
        return new kotlin.Pair<>(DASH_ON, DASH_OFF);
    }

    /** 实心段长（px）。 */
    public static final int DASH_ON = 3;

    /** 空白段长（px）。 */
    public static final int DASH_OFF = 3;

    /** 总周期（测试断言用）。 */
    public static final int DASH_PERIOD = DASH_ON + DASH_OFF;
}
