package cn.song.mybatisplus.visualizer.ui.highlight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-StartupReadLockFreeze §14.10：模型冷窗口的纯判定。
 *
 * 真机背景（2026-11-13 13:47 沙盒）：一次预览打开期间出现 **19824 条**
 * `scan: table=…, bail=DasModel 遍历 0 张表（内省未完成）`——830 张表 × 15 个数据源，
 * 全部是「模型没加载所以问不出来」，耗时约 45s，把 `request→dialog` 拖到 **19.8s**。
 *
 * 语义：一次「所有源都 bail」即模型冷的充分证据；窗口内直接按 scanBailed 短路（不再逐源遍历），
 * 窗口外自动重试——与 `bailAfterTraversal` 的「查不了不记账、下次重试」自愈语义同向，不冲突。
 */
class DatabaseSchemaResolverModelsColdTest {

    @Test
    fun `窗口内视为模型冷_直接短路`() {
        val last = 1_000_000L
        assertTrue(DatabaseSchemaResolver.modelsColdNowAt(last, last + 1, 20_000))
        assertTrue(DatabaseSchemaResolver.modelsColdNowAt(last, last + 19_999, 20_000))
    }

    @Test
    fun `窗口外放行_内省完成后自愈`() {
        val last = 1_000_000L
        assertFalse(DatabaseSchemaResolver.modelsColdNowAt(last, last + 20_000, 20_000))
        assertFalse(DatabaseSchemaResolver.modelsColdNowAt(last, last + 60_000, 20_000))
    }

    @Test
    fun `未观测到模型冷或窗口关闭时不短路`() {
        assertFalse(DatabaseSchemaResolver.modelsColdNowAt(null, 1_000_000L, 20_000))
        assertFalse(DatabaseSchemaResolver.modelsColdNowAt(1_000_000L, 1_000_001L, 0))
        assertFalse(DatabaseSchemaResolver.modelsColdNowAt(1_000_000L, 1_000_001L, -1))
    }

    /** 时钟回拨不算命中（避免把 last 记在未来导致永久短路）。 */
    @Test
    fun `时钟回拨不命中`() {
        val last = 1_000_000L
        assertFalse(DatabaseSchemaResolver.modelsColdNowAt(last, last - 5_000, 20_000))
    }

    /**
     * 接线钉死：判定必须真的在**扫描入口**被调用，否则参数再对也不生效。
     * （第一版只在 prefetchTables 里查，而真机那场扫描来自单表入口 `tryCompute`——白查。）
     */
    @Test
    fun `扫描入口必须在遍历前自查模型冷`() {
        val src = resolverSource()
        val tryComputeBody = src.substringAfter("private fun tryCompute").substringBefore("private fun ")
        assertTrue(
            "tryCompute 必须在 dataSources 遍历前 return scanBailed（模型冷短路）",
            "modelsColdNow(project)" in tryComputeBody,
        )
        assertTrue(
            "观测到全源 bail 后必须 stamp 模型冷",
            "stampModelsCold(project)" in tryComputeBody,
        )
    }

    private fun resolverSource(): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = java.io.File(
                dir,
                "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/DatabaseSchemaResolver.kt",
            )
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        error("未找到 DatabaseSchemaResolver.kt")
    }
}
