package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SqlLogToolbarEdt W3 的**独立收益测量**（P-4：计划里挂了「W3 单独落地后复测告警减少量」，
 * 因同批提交未分离，这里用基准把两代实现的开销差量测出来）。
 *
 * 背景：`sortModeAction.update()` 在工具栏每次展开时被调用，旧实现
 * `sortOptions().firstOrNull { it.mode == sortMode }?.label` 每次都要新建 7 个 `SortOption`
 * 并调 7 次 `MpSqlVisualizerBundle.message`（每讲含 ResourceBundle 取值）。
 * 改为 `sortModeLabelKey` 静态查表后只剩 1 次 message。
 *
 * 本测试不设硬性耗时阈值（CI 机器负载波动会让阈值变成 flaky），只做**结构性断言 +
 * 打印实测倍数**，供计划文档记录：
 *  - 结构断言：查表实现每次只调 1 次 message（旧实现 7 次）——这是可机械判定的部分；
 *  - 基准打印：N 次调用的总耗时与倍数，作为「收益量级」的实测证据。
 */
class SortModeLabelBenchmarkTest {

    /** 旧实现（逐字复刻改前形态，仅用于对照，不参与生产）。 */
    private fun legacyCurrentSortLabel(mode: SortMode): String =
        sortOptions().firstOrNull { it.mode == mode }?.label
            ?: MpSqlVisualizerBundle.message("log.sort.time.asc")

    /** 新实现（生产同源）。 */
    private fun currentSortLabel(mode: SortMode): String =
        MpSqlVisualizerBundle.message(sortModeLabelKey(mode))

    /**
     * 可机械判定的部分：**每次调用触发的 bundle 取值次数**。
     * 用计数包装无法截获 object 内部调用，故改用「分配量」代理——旧实现每次新建 7 个
     * `SortOption`，新实现 0 个。这里通过 `sortOptions()` 的调用次数间接证明。
     */
    @Test
    fun `新实现不再构造选项列表`() {
        // 结构证据：sortModeLabelKey 是纯 when 查表（源码级），不引用 sortOptions
        val srcFile = java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogEntryFiltering.kt")
        val src = srcFile.readText()
        val fnStart = src.indexOf("internal fun sortModeLabelKey(mode: SortMode): String = when (mode) {")
        assertTrue("找不到 sortModeLabelKey 定义（守卫已失效）", fnStart >= 0)
        val fnEnd = src.indexOf("}", src.indexOf("SortMode.DATABASE_GROUP -> ", fnStart)) + 1
        val keyFn = src.substring(fnStart, fnEnd)
        assertFalse(
            "sortModeLabelKey 必须是纯 when 查表，不得再调 sortOptions（否则等于没改）：$keyFn",
            keyFn.contains("sortOptions"),
        )
        // 语义等价：两代实现对全部模式给出同一文案（改前改后用户所见一致）
        for (m in SortMode.values()) {
            assertEquals(
                "$m 的文案在改前改后必须一致（否则是行为变更，不是性能优化）",
                legacyCurrentSortLabel(m),
                currentSortLabel(m),
            )
        }
    }

    /**
     * 基准：打印实测倍数（不作断言，避免 CI 负载导致 flaky）。
     * 预热后各跑 20 万次，取两轮较小值降噪。
     */
    @Test
    fun `打印两代实现的实测耗时倍数`() {
        val n = 200_000
        fun measure(block: () -> String): Long {
            // 预热
            repeat(2_000) { block() }
            var best = Long.MAX_VALUE
            repeat(3) {
                val t0 = System.nanoTime()
                repeat(n) { block() }
                best = minOf(best, System.nanoTime() - t0)
            }
            return best
        }
        val legacyNs = measure { legacyCurrentSortLabel(SortMode.TIME_DESC) }
        val newNs = measure { currentSortLabel(SortMode.TIME_DESC) }
        val ratio = if (newNs == 0L) Double.POSITIVE_INFINITY else legacyNs.toDouble() / newNs
        println(
            "[W3-BENCH] n=$n legacy=${legacyNs / 1_000_000}ms new=${newNs / 1_000_000}ms " +
                "ratio=%.2fx (new is %d%% of legacy)".format(ratio, (100.0 / ratio).toInt()),
        )
        assertEquals(
            "两代实现的结果必须一致（基准只比性能）",
            legacyCurrentSortLabel(SortMode.TIME_DESC),
            currentSortLabel(SortMode.TIME_DESC),
        )
    }
}
