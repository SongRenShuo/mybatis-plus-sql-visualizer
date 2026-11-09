package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * T-SqlLogPerf：log 包热路径耗时探针（阈值触发 INFO）。
 *
 * 背景（20261006-1020 开发计划 §阶段 0）：用户日常 IDE 实测并发压测下 8708 条历史文件加载
 * 15~16.6s、实时列表灌入 65 条/秒时面板动作等待 EDT 358~1110ms——但热路径零耗时打点，
 * 延迟无法从 idea.log 直接归因。本探针给 readFromFile/解析/列表填充/refreshList 等
 * 疑似瓶颈段统一打点；**阈值触发**（单次低于 [THRESHOLD_MS] 毫秒零输出）保证常态零噪音，
 * 风暴期才在 idea.log 留痕，事后可 `[T-SqlLogPerf]` 检索对账。
 *
 * 探针是**转正**的观测设施（不是临时调试代码）：供后续性能轮与真机报障复用。
 *
 * T-SqlListCatchup 券D（2026-11-07）：补**全量分布采样**——阈值触发只留 ≥100ms 的拍，
 * `refreshList` 单拍常态远低于阈值，光靠阈值日志拿不到中位数（计划 §五.1 的「refreshList
 * 单拍中位数」对比读数取不到）。每名样本落入环形缓冲（[SAMPLE_RING_SIZE] 个），按名可取
 * 计数/均值/分位（[sampleStats]，压测复测程序化读数出口）；每累计 [SAMPLE_SUMMARY_EVERY]
 * 个样本打一条 INFO 分布摘要（沿用本类既有「idea.log 留痕」呈现方式）；[resetSamples]
 * 供压测复测划窗口。采样成本每次一入环 + 一次取模判断（锁内短临界区，实测开销见券D报告）。
 *
 * T-SqlListCatchup 券E（2026-11-07）：补 [recordGauge]——非耗时型读数（gauge）共用同一
 * 分布设施，供桥侧逐行/积压量打点（`LogBridge.handleLine` / `LogBridge.settledBacklog`）。
 */
object LogPerfProbe {

    /** internal inline（非 public）才能在 inline 体内直读非公开成员；调用面限本包热路径。 */
    private val LOG = Logger.getInstance(LogPerfProbe::class.java)

    /** 单次耗时达到该毫秒数才输出 INFO（低于阈值的调用零成本输出、零日志噪音）。 */
    internal const val THRESHOLD_MS = 100L

    /** 券D：每名保留的最近样本数（8B/样本 × 1KB ≈ 8KB/名，探针名个位数，内存面可忽略）。 */
    private const val SAMPLE_RING_SIZE = 1024

    /** 券D：每累计多少个样本打一条 INFO 分布摘要（风暴期 refreshList 每秒几十拍 → 约每 10~20s 一条）。 */
    private const val SAMPLE_SUMMARY_EVERY = 512L

    /**
     * 包裹 [block] 计时；达到 [THRESHOLD_MS] 输出 `[T-SqlLogPerf] name …ms (detail)`。
     * detail 惰性求值——未达阈值时零开销，不触发字符串拼接。
     * 券D：无论是否达阈值，样本一律入环（[sampleStats] / 周期摘要的分布数据源）。
     */
    internal inline fun <T> measure(name: String, detail: () -> String = { "" }, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val costNanos = System.nanoTime() - start
        recordSample(name, costNanos)
        val costMs = costNanos / 1_000_000
        if (costMs >= THRESHOLD_MS) {
            val suffix = detail().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
            LOG.info("[T-SqlLogPerf] $name ${costMs}ms$suffix")
        }
        return result
    }

    /**
     * T-SqlListCatchup 券E：非耗时型读数（gauge）入环——与 [measure] 共用环形分布设施
     * （[sampleStats] / [resetSamples]），但 value 的量纲由调用方定义（如「条」）：环内按
     * 纳秒耗时口径存取（[SampleRing.stats] 出数时 ÷1e6 折毫秒），gauge 值先 ×1e6 入环，
     * 使 stats 读数原值回显（[SampleStats] 的 ms 字样对 gauge 名只是名义单位）。value 达
     * [THRESHOLD_MS] 走同款 INFO 留痕（量纲与场景写进 detail）。
     * 券E 消费点：`LogBridge.settledBacklog`（flushSettledEntries 每拍的待产出条目数）。
     */
    internal inline fun recordGauge(name: String, value: Long, detail: () -> String = { "" }) {
        recordSample(name, value * 1_000_000)
        if (value >= THRESHOLD_MS) {
            val suffix = detail().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
            LOG.info("[T-SqlLogPerf] $name $value$suffix")
        }
    }

    // ---------- T-SqlListCatchup 券D：全量分布采样 ----------

    /** 券D：按名分布读数（[sampleStats] 出口；count=0 时 stats 各值为 0，见 [sampleStats] 返回 null）。 */
    internal class SampleStats(
        /** 样本总数（自 [resetSamples] 起累计；环形只保留最近 [SAMPLE_RING_SIZE] 个的明细）。 */
        val count: Long,
        val meanMs: Double,
        val p50Ms: Double,
        val p90Ms: Double,
        val maxMs: Double,
    ) {
        override fun toString(): String =
            "count=$count mean=%.1fms p50=%.1fms p90=%.1fms max=%.1fms".format(meanMs, p50Ms, p90Ms, maxMs)
    }

    /** 环形样本缓冲（按名一把锁——measure 调用方跨线程但低频，临界区毫微秒级，无争用面）。 */
    private class SampleRing {
        private val buf = LongArray(SAMPLE_RING_SIZE)
        var count = 0L
            private set
        private var sumNanos = 0L

        fun add(costNanos: Long) {
            buf[((count++) % SAMPLE_RING_SIZE).toInt()] = costNanos
            sumNanos += costNanos
        }

        /** 快照统计（count 全量口径；分位取环形内最近明细，count ≤ 环容量时两者一致）。 */
        fun stats(): SampleStats {
            val n = minOf(count, SAMPLE_RING_SIZE.toLong()).toInt()
            val recent = LongArray(n) { i -> buf[i] }
            recent.sort()
            fun at(pct: Double): Double {
                if (n == 0) return 0.0
                val idx = (pct * (n - 1)).toInt()
                return recent[idx] / 1_000_000.0
            }
            return SampleStats(
                count = count,
                meanMs = if (count == 0L) 0.0 else sumNanos / 1_000_000.0 / count,
                p50Ms = at(0.50),
                p90Ms = at(0.90),
                maxMs = if (n == 0) 0.0 else recent[n - 1] / 1_000_000.0,
            )
        }

        fun reset() {
            buf.fill(0)
            count = 0
            sumNanos = 0
        }
    }

    private val rings = ConcurrentHashMap<String, SampleRing>()

    /**
     * 券D：取按名分布读数（count / mean / p50 / p90 / max）。
     * 压测复测读数出口——复测前 [resetSamples] 划窗口，结束后本函数取数；
     * 无样本（未采样过或 reset 后）返回 null。
     */
    internal fun sampleStats(name: String): SampleStats? = rings[name]?.let {
        synchronized(it) { if (it.count == 0L) null else it.stats() }
    }

    /** 券D：清空全部采样（压测复测划窗口用；生产常态勿调）。 */
    internal fun resetSamples() {
        rings.values.forEach { synchronized(it) { it.reset() } }
    }

    /** 券D：样本入环 + 周期摘要（每 [SAMPLE_SUMMARY_EVERY] 个样本一条 INFO，沿用 idea.log 留痕呈现）。 */
    private fun recordSample(name: String, costNanos: Long) {
        val ring = rings.computeIfAbsent(name) { SampleRing() }
        var summary: SampleStats? = null
        synchronized(ring) {
            ring.add(costNanos)
            if (ring.count % SAMPLE_SUMMARY_EVERY == 0L) summary = ring.stats()
        }
        summary?.let { LOG.info("[T-SqlLogPerf] $name 分布摘要: $it") }
    }
}
