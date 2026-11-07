package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SqlListCatchup 券 D：LogPerfProbe 全量分布采样守卫（运行期，非源码扫描）。
 *
 * 背景：阈值触发 INFO 只留 ≥100ms 的拍，refreshList 单拍常态远低于阈值——光靠阈值日志
 * 拿不到中位数（计划 §五.1 的「refreshList 单拍中位数」对比读数取不到）。采样把每拍入环，
 * [LogPerfProbe.sampleStats] 给出 count/mean/p50/p90/max，压测复测前 [LogPerfProbe.resetSamples]
 * 划窗口。
 *
 * 判据只断结构不变量（count 精确、mean>0、p50≤p90≤max），不断具体毫秒值——
 * 真实耗时随机器波动，断言墙钟读数是 L18 同族的机器敏感假红。
 */
class LogPerfProbeSampleTest {

    private val probe = LogPerfProbe

    /** 独占采样名（resetSamples 全局清空，用独有名避免并行类污染读数）。 */
    private val name = "TestProbe.${System.nanoTime()}"

    @Test
    fun testmeasure透传块返回值且样本入环() {
        probe.resetSamples()
        val result = probe.measure(name) { "block-result" }
        assertEquals("measure 必须透传块返回值", "block-result", result)
        val stats = probe.sampleStats(name)
        assertNotNull("采样后应有读数", stats)
        assertEquals("count 应为 1", 1L, stats!!.count)
        assertTrue("单次耗时均值应 >0（nanoTime 单调）", stats.meanMs > 0.0)
        assertEquals("p50 应等于 max（单样本）", stats.maxMs, stats.p50Ms, 1e-9)
    }

    @Test
    fun test分位与计数不变量() {
        probe.resetSamples()
        repeat(7) { probe.measure(name) { it + 1 } } // 占位块（引用外层 repeat 计数），无 IO
        val stats = probe.sampleStats(name)!!
        assertEquals("7 次 measure → count=7", 7L, stats.count)
        assertTrue("p50 ≤ p90", stats.p50Ms <= stats.p90Ms + 1e-9)
        assertTrue("p90 ≤ max", stats.p90Ms <= stats.maxMs + 1e-9)
        assertTrue("mean ≤ max", stats.meanMs <= stats.maxMs + 1e-9)
        assertTrue("耗时读数应为正毫秒", stats.maxMs > 0.0)
    }

    @Test
    fun test无名采样返回null_reset划窗口生效() {
        probe.resetSamples()
        assertNull("未采样名不应有读数", probe.sampleStats("TestProbe.never-sampled"))
        probe.measure(name) { }
        assertNotNull(probe.sampleStats(name))
        probe.resetSamples()
        assertNull("resetSamples 后读数应清空（压测窗口起点）", probe.sampleStats(name))
    }

    @Test
    fun test空转基准_measure开销可忽略() {
        // 计划 §五 前提验证：measure 自身开销（计时 + 入环 + 阈值判断）相对被测段可忽略。
        // 空转基准：百万次 measure 空块的总耗时，折算单次开销。断言只设宽上界（1µs/次）——
        // 超过它才说明采样路径有明显成本；实测读数进券 D 报告。
        probe.resetSamples()
        val n = 1_000_000
        val start = System.nanoTime()
        repeat(n) { probe.measure(name) { } }
        val perCallNanos = (System.nanoTime() - start) / n
        assertTrue(
            "measure 单次开销应 <1000ns（实测 ${perCallNanos}ns）",
            perCallNanos < 1_000,
        )
        assertEquals("百万次空转后 count 应精确", n.toLong(), probe.sampleStats(name)!!.count)
    }
}
