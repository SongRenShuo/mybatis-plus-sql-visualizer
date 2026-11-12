package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.xml.MockObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-PreviewOpenPerfM2 M1：`MbgExampleCallResolver` 的缓存外壳（纯逻辑）。
 *
 * 被优化点是 `ReferencesSearch` 全项目索引检索（实测占装配 78%）。缓存外壳与 PSI 解析解耦
 * （[MbgExampleCallResolver.resolveCached] + 可注入的 `compute`），因此这里不需要平台环境；
 * 「依赖文件改动后失效」走 `VirtualFile.modificationStamp` 比较，测试环境构造真实 VirtualFile
 * 成本高，改由真机 `[open-perf]` 读数验收（计划 §四 已登记）。
 */
class MbgExampleCallCacheTest {

    @After
    fun cleanup() {
        MbgExampleCallResolver.resetCacheForTest()
    }

    private fun entry(tag: String) = MbgExampleCallResolver.CachedResolution(
        elements = mapOf("criteria.criteria" to listOf(MockObject(mapOf("condition" to tag)))),
        stamps = emptyMap(),
    )

    @Test
    fun `第二次命中缓存_不再执行检索`() {
        MbgExampleCallResolver.resetCacheForTest()
        var runs = 0
        val compute = { runs++; entry("v1") }

        val first = MbgExampleCallResolver.resolveCached("ns#s1", compute)
        val second = MbgExampleCallResolver.resolveCached("ns#s1", compute)

        assertEquals(first, second)
        assertEquals("compute（即 ReferencesSearch）只应执行一次", 1, runs)
        assertEquals(1, MbgExampleCallResolver.searchRuns)
        assertEquals(1, MbgExampleCallResolver.cacheHits)
    }

    @Test
    fun `不同key互不命中`() {
        MbgExampleCallResolver.resetCacheForTest()
        var runs = 0
        val compute = { runs++; entry("v") }

        MbgExampleCallResolver.resolveCached("ns#s1", compute)
        MbgExampleCallResolver.resolveCached("ns#s2", compute)

        assertEquals(2, runs)
        assertEquals(0, MbgExampleCallResolver.cacheHits)
    }

    @Test
    fun `解析结果为null时不写缓存`() {
        MbgExampleCallResolver.resetCacheForTest()
        var runs = 0
        val compute: () -> MbgExampleCallResolver.CachedResolution? = { runs++; null }

        assertNull(MbgExampleCallResolver.resolveCached("ns#s1", compute))
        assertNull(MbgExampleCallResolver.resolveCached("ns#s1", compute))

        // 「当时索引没就绪 / 找不到调用点」不得被固化——下次必须再试
        assertEquals(2, runs)
        assertEquals(0, MbgExampleCallResolver.cacheHits)
    }
}
