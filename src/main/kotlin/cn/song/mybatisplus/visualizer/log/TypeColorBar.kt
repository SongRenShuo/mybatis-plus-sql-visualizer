package cn.song.mybatisplus.visualizer.log

import java.awt.Color
import java.awt.Graphics
import javax.swing.JPanel

/**
 * 类型色条（T-DdlTypeColor）。
 *
 * 两种形态：
 *  - **实心**（DML：SELECT / INSERT / UPDATE / DELETE）——沿用改造前的纯背景填充；
 *  - **虚线**（DDL：CREATE / DROP）——按竖向「实/空」交替绘制，表达「结构性操作、非普通数据读写」。
 *
 * 为什么 DDL 用虚线而非加粗/双色：虚线不额外占宽度（侧栏空间敏感），且语义上「不连续」
 * 恰好对应「不连续的数据操作」；加粗会与相邻行挤在一起，双色在 3px 里塞两段几乎看不出差别。
 *
 * 性能约束（T-LogListFreeze 同族）：`paintComponent` 是渲染热路径，
 * **不得新建 Color/BasicStroke 等对象**——颜色来自 [SqlListTypeColor] 的实例缓存（`background`），
 * 虚线段长是编译期常量，绘制只用 `fillRect`（无抗锯齿路径、无 stroke 对象）。
 */
internal class TypeColorBar : JPanel() {
    /**
     * 是否画虚线。由 [applyCrudColorMark] 按语句类型设置。
     *
     * 只在值变化时触发 repaint（与 `setBackgroundIfChanged` 同族守卫）——
     * 渲染器逐格调用，无脑赋值会让每格多一次重绘调度。
     */
    var dashed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            repaintRequests++
            repaint()
        }

    /** 实际触发的重绘次数（测试缝：验证「同值不重复重绘」这条热路径守卫）。 */
    internal var repaintRequests: Int = 0
        private set

    init {
        isOpaque = true
    }

    override fun paintComponent(g: Graphics) {
        if (!dashed) {
            // 实心：走父类的纯背景填充（与改造前逐字节一致）
            super.paintComponent(g)
            return
        }
        // 虚线：不铺底色 —— 空白段必须真留空，让父容器背景透出。
        // 早期版本「先填 background 再按段填 background」等于全实心（前景与底色同色），
        // 由 DashProbe 实测的像素序列 `########` 抓出。
        val saved = g.color
        g.color = background
        var y = 0
        while (y < height) {
            g.fillRect(0, y, width, minOf(DASH_ON, height - y))
            y += DASH_ON + DASH_OFF
        }
        g.color = saved
    }

    /** 实/空段长（px）。行高 20~80px 下分别有 3~13 个循环，视觉上稳定可辨。 */
    internal fun dashPatternForTest(): Pair<Int, Int> = DASH_ON to DASH_OFF

    internal companion object {
        /** 实心段长（px）。 */
        const val DASH_ON = 3

        /** 空白段长（px）。 */
        const val DASH_OFF = 3

        /** 总周期（测试断言用）。 */
        const val DASH_PERIOD = DASH_ON + DASH_OFF
    }
}
