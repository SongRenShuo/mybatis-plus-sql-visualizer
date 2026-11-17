package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-LabelInset：左栏标签列宽预算必须把**行内边距**算进去。
 *
 * 真机形态（2026-11-12，`deleteByExample` 面板截图）：三条集合标签里最宽的两条被截成
 * `criteria.crit...` / `criterion.va...`。取证两步：
 *  1. 截图 8× 放大 → 是「保留头部 + 尾省略号」，不是我方 [ellipsize]（保留尾部）；
 *  2. JDK8 headless 探针 `SwingUtilities.layoutCompoundLabel(avail=70)` → `criteria.crit...`
 *     —— 逐字复现：**Swing 的 JLabel 自己**在「组件宽 − 内边距」放不下时截尾加 `...`。
 *
 * ⇒ 成因 = `applyLabelColumn` 把 `preferredSize.width` 钉成「文本宽度」，而 foreach / iBatis
 * iterate / `$` 直替三类行随后给标签加了 8px 左内边距，最宽的标签必然差这一截。
 * 本用例钉的是**契约**：每一行的可绘宽度都要放得下它自己的文本。
 */
class LabelColumnInsetTest {

    @Test
    fun `最宽标签的可绘宽度必须放得下它自己`() {
        val textWidths = listOf(77, 131, 104)
        val insets = listOf(0, 8, 8)
        val columnWidth = paramLabelColumnWidth(textWidths, 680, insets)
        textWidths.indices.forEach { i ->
            val avail = paramLabelTextAvailPx(columnWidth, insets[i], 0)
            assertTrue(
                "第 $i 行：可绘宽度 $avail 放不下文本 ${textWidths[i]}（列宽 $columnWidth，内边距 ${insets[i]}）",
                avail >= textWidths[i],
            )
        }
    }

    @Test
    fun `内边距计入需求宽度_文本等宽时列宽随内边距增长`() {
        assertEquals(120, paramLabelColumnWidth(listOf(120), 680))
        assertEquals("最宽行的内边距必须加进列宽", 128, paramLabelColumnWidth(listOf(120), 680, listOf(8)))
    }

    @Test
    fun `取的是文本加内边距的最大值_而不是文本最大值加最大内边距`() {
        // 120+0=120 / 100+8=108 → 需求 120（若错写成 max(text)+max(inset) 会得 128 而虚宽）
        assertEquals(120, paramLabelColumnWidth(listOf(120, 100), 680, listOf(0, 8)))
    }

    @Test
    fun `无内边距时与旧口径一致`() {
        assertEquals(120, paramLabelColumnWidth(listOf(120), 680, emptyList()))
        assertEquals(120, paramLabelColumnWidth(listOf(120), 680, listOf(0)))
    }

    @Test
    fun `内边距不得突破视口上限`() {
        // 视口 200 → 预算 min(200×0.55, 480) = 110 → 内边距加不进去时按上限截（宁可窄，不能超屏）
        assertEquals(110, paramLabelColumnWidth(listOf(120), 200, listOf(8)))
    }

    @Test
    fun `可绘宽度不为负`() {
        assertEquals(0, paramLabelTextAvailPx(4, 8, 8))
        assertEquals(72, paramLabelTextAvailPx(80, 8, 0))
    }
}
