package cn.song.mybatisplus.visualizer.ui.highlight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SchemaPrewarmDedupe：预热去重窗口的纯判定。
 *
 * 真机背景（2026-11-12 15:53）：一次预览打开里 `schema 预热` 跑两遍，每遍把 15 个数据源全遍历且
 * 全部 bail（`resolved=0`），下一次打开原样重来——纯白付。窗口只作用于预热这条尽力链，
 * 不碰 `bailAfterTraversal` 的「查不了不记账」语义（那条是自愈护栏，4 处既有断言钉住）。
 */
class SchemaPrewarmWindowTest {

    private val window = DatabaseSchemaResolver.PREWARM_WINDOW_MS

    @Test
    fun `窗口内命中_跳过重复预热`() {
        val last = 1_000_000L
        assertTrue(DatabaseSchemaResolver.prewarmWindowHitAt(last, last + 1, window))
        assertTrue(DatabaseSchemaResolver.prewarmWindowHitAt(last, last + window - 1, window))
    }

    @Test
    fun `窗口外放行_保留数据源晚连接后的自愈`() {
        val last = 1_000_000L
        assertFalse(DatabaseSchemaResolver.prewarmWindowHitAt(last, last + window, window))
        assertFalse(DatabaseSchemaResolver.prewarmWindowHitAt(last, last + window * 3, window))
    }

    @Test
    fun `窗口为零等价关闭_且无历史时永不命中`() {
        assertFalse(DatabaseSchemaResolver.prewarmWindowHitAt(1_000_000L, 1_000_001L, 0L))
        assertFalse(DatabaseSchemaResolver.prewarmWindowHitAt(1_000_000L, 1_000_001L, -1L))
        assertFalse(DatabaseSchemaResolver.prewarmWindowHitAt(null, 1_000_001L, window))
    }
}
