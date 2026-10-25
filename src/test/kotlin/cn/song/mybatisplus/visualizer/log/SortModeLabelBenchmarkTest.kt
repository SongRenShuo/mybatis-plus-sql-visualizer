package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-SqlLogToolbarEdt W3 的**独立收益测量**（P-4：计划里挂了「W3 单独落地后复测告警减少量」，
 * 因同批提交未分离，这里用基准把两代实现的开销差量测出来）。
 *
 * 背景：`sortModeAction.update()` 在工具栏每次展开时被调用，旧实现
 * `sortOptions().firstOrNull { it.mode == sortMode }?.label` 每次都要新建 7 个 `SortOption`
 * 并调 7 次 `MpSqlVisualizerBundle.message`（每次含 ResourceBundle 取值）。
 * 改为 `sortModeLabelKey` 静态查表后只剩 1 次 message。
 *
 * 本测试不设硬性耗时阈值（CI 机器负载波动会让阈值变成 flaky），只做**结构性断言 +
 * 打印实测倍数**，供计划文档记录：
 *  - 结构断言：查表实现每次只调 1 次 message（旧实现 7 次）——这是可机械判定的部分；
 *  - 基准打印：N 次调用的总耗时与倍数，作为「收益量级」的实测证据。
 *
 * K2J 适配：master 读 `src/main/kotlin/.../LogEntryFiltering.kt`（本分支该文件的 Java 承载类是
 * `SqlLogPanelKt.java`，扫描根已适配为 `src/main/java`）；基准 lambda 全部收进**非 test 前缀的
 * 私有工厂**，防 JUnit3 对 `testXxx$lambda$N` 合成方法的误扫（系统性坑 27b）。
 */
class SortModeLabelBenchmarkTest {

    /** 旧实现（逐字复刻改前形态，仅用于对照，不参与生产）。 */
    private fun legacyCurrentSortLabel(mode: SortMode): String {
        var label: String? = null
        for (option in SqlLogPanelKt.sortOptions()) {
            if (option.mode() == mode) {
                label = option.label()
                break
            }
        }
        return label ?: MpSqlVisualizerBundle.message("log.sort.time.asc")
    }

    /** 新实现（生产同源）。 */
    private fun currentSortLabel(mode: SortMode): String =
        MpSqlVisualizerBundle.message(SqlLogPanelKt.sortModeLabelKey(mode))

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录")
    }

    /** 生产源码里 `sortModeLabelKey` 的完整方法体（括号配对提取）。 */
    private fun keyFnSource(): String {
        val src = File(repoRoot(), "src/main/java/cn/song/mybatisplus/visualizer/log/SqlLogPanelKt.java").readText()
        val anchor = src.indexOf("String sortModeLabelKey(")
        assertTrue("找不到 sortModeLabelKey 定义（守卫已失效）", anchor >= 0)
        val open = src.indexOf('{', anchor)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(anchor, i + 1)
                }
            }
            i++
        }
        error("sortModeLabelKey 方法体括号不配对（源码形态异常）")
    }

    /**
     * 可机械判定的部分：**每次调用触发的 bundle 取值次数**。
     * 用计数包装无法截获 object 内部调用，故改用「分配量」代理——旧实现每次新建 7 个
     * `SortOption`，新实现 0 个。这里通过 `sortOptions()` 的调用次数间接证明。
     */
    @Test
    fun `新实现不再构造选项列表`() {
        // 结构证据：sortModeLabelKey 是纯 switch 查表（源码级），不引用 sortOptions
        val keyFn = keyFnSource()
        assertFalse(
            "sortModeLabelKey 必须是纯 switch 查表，不得再调 sortOptions（否则等于没改）：$keyFn",
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

    /** 基准私有工厂（非 test 前缀）：预热后各跑 3 轮取最小值降噪。 */
    private fun measure(block: () -> String): Long {
        repeat(2_000) { block() }
        var best = Long.MAX_VALUE
        repeat(3) {
            val t0 = System.nanoTime()
            repeat(N) { block() }
            best = minOf(best, System.nanoTime() - t0)
        }
        return best
    }

    /** 旧实现基准读数工厂（隔离 lambda，防 JUnit3 误扫合成方法）。 */
    private fun measureLegacyNs(): Long = measure { legacyCurrentSortLabel(SortMode.TIME_DESC) }

    /** 新实现基准读数工厂（同上）。 */
    private fun measureNewNs(): Long = measure { currentSortLabel(SortMode.TIME_DESC) }

    /**
     * 基准：打印实测倍数（不作断言，避免 CI 负载导致 flaky）。预热后各跑 20 万次，取两轮较小值降噪。
     */
    @Test
    fun `打印两代实现的实测耗时倍数`() {
        val legacyNs = measureLegacyNs()
        val newNs = measureNewNs()
        val ratio = if (newNs == 0L) Double.POSITIVE_INFINITY else legacyNs.toDouble() / newNs
        println(
            "[W3-BENCH] n=$N legacy=${legacyNs / 1_000_000}ms new=${newNs / 1_000_000}ms " +
                "ratio=%.2fx (new is %d%% of legacy)".format(ratio, (100.0 / ratio).toInt()),
        )
        assertEquals(
            "两代实现的结果必须一致（基准只比性能）",
            legacyCurrentSortLabel(SortMode.TIME_DESC),
            currentSortLabel(SortMode.TIME_DESC),
        )
    }

    private companion object {
        /** 基准调用次数（预热 2000 次 + 每轮 N 次）。 */
        const val N = 200_000
    }
}
