package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.diagnostic.Logger;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
 * <p>T-SqlListCatchup 券D（2026-11-07）：补**全量分布采样**——阈值触发只留 ≥100ms 的拍，
 * {@code refreshList} 单拍常态远低于阈值，光靠阈值日志拿不到中位数（计划 §五.1 的「refreshList
 * 单拍中位数」对比读数取不到）。每名样本落入环形缓冲（{@link #SAMPLE_RING_SIZE} 个），按名可取
 * 计数/均值/分位（{@link #sampleStats}，压测复测程序化读数出口）；每累计 {@link #SAMPLE_SUMMARY_EVERY}
 * 个样本打一条 INFO 分布摘要（沿用本类既有「idea.log 留痕」呈现方式）；{@link #resetSamples()}
 * 供压测复测划窗口。采样成本每次一入环 + 一次取模判断（锁内短临界区，实测开销见券D报告）。</p>
 *
 * <p>T-SqlListCatchup 券E（2026-11-07）：补 {@code recordGauge}——非耗时型读数（gauge）共用同一
 * 分布设施，供桥侧逐行/积压量打点（{@code LogBridge.handleLine} / {@code LogBridge.settledBacklog}）。</p>
 *
 * <p>k2j 翻译注：Kotlin 侧为 {@code object} + {@code internal inline fun measure}——Java 无 inline，
 * 本类落为 final 工具类 + 普通静态方法。语义等价：未达阈值时仅一次 {@code System.nanoTime()} 差值
 * 与一次 Supplier 装载开销，可忽略；detail 经 {@link Supplier} 保持惰性求值（未达阈值不触发字符串拼接）。
 * SampleStats/SampleRing 落静态嵌套类（Kotlin 内嵌类等价）。</p>
 */
public final class LogPerfProbe {

    /** 单次耗时达到该毫秒数才输出 INFO（低于阈值的调用零成本输出、零日志噪音）。 */
    public static final long THRESHOLD_MS = 100L;

    /** 券D：每名保留的最近样本数（8B/样本 × 1KB ≈ 8KB/名，探针名个位数，内存面可忽略）。 */
    private static final int SAMPLE_RING_SIZE = 1024;

    /** 券D：每累计多少个样本打一条 INFO 分布摘要（风暴期 refreshList 每秒几十拍 → 约每 10~20s 一条）。 */
    private static final long SAMPLE_SUMMARY_EVERY = 512L;

    /** internal inline（非 public）才能在 inline 体内直读非公开成员；调用面限本包热路径。 */
    private static final Logger LOG = Logger.getInstance(LogPerfProbe.class);

    private LogPerfProbe() {
    }

    /**
     * 包裹 block 计时；达到 {@link #THRESHOLD_MS} 输出 {@code [T-SqlLogPerf] name …ms}。
     * detail 缺省空串（Kotlin 默认参数 {@code detail: () -> String = { "" }} 的显式重载形态）。
     */
    public static <T> T measure(String name, Supplier<T> block) {
        return measure(name, () -> "", block);
    }

    /**
     * 包裹 block 计时；达到 {@link #THRESHOLD_MS} 输出 {@code [T-SqlLogPerf] name …ms (detail)}。
     * detail 惰性求值——未达阈值时零开销，不触发字符串拼接。
     * 券D：无论是否达阈值，样本一律入环（{@link #sampleStats} / 周期摘要的分布数据源）。
     */
    public static <T> T measure(String name, Supplier<String> detail, Supplier<T> block) {
        long start = System.nanoTime();
        T result = block.get();
        long costNanos = System.nanoTime() - start;
        recordSample(name, costNanos);
        long costMs = costNanos / 1_000_000;
        if (costMs >= THRESHOLD_MS) {
            String text = detail.get();
            String suffix = text != null && !text.isEmpty() ? " (" + text + ")" : "";
            LOG.info("[T-SqlLogPerf] " + name + " " + costMs + "ms" + suffix);
        }
        return result;
    }

    /**
     * T-SqlListCatchup 券E：非耗时型读数（gauge）入环——与 {@link #measure} 共用环形分布设施
     * （{@link #sampleStats} / {@link #resetSamples()}），但 value 的量纲由调用方定义（如「条」）：环内按
     * 纳秒耗时口径存取（{@code SampleRing.stats()} 出数时 ÷1e6 折毫秒），gauge 值先 ×1e6 入环，
     * 使 stats 读数原值回显（{@link SampleStats} 的 ms 字样对 gauge 名只是名义单位）。value 达
     * {@link #THRESHOLD_MS} 走同款 INFO 留痕（量纲与场景写进 detail）。
     * 券E 消费点：{@code LogBridge.settledBacklog}（flushSettledEntries 每拍的待产出条目数）。
     * detail 缺省空串（Kotlin 默认参数的显式重载形态）。
     */
    public static void recordGauge(String name, long value) {
        recordGauge(name, value, () -> "");
    }

    /** 券E：recordGauge 全参形态（detail 惰性求值，见 {@link #recordGauge(String, long)}）。 */
    public static void recordGauge(String name, long value, Supplier<String> detail) {
        recordSample(name, value * 1_000_000L);
        if (value >= THRESHOLD_MS) {
            String text = detail.get();
            String suffix = text != null && !text.isEmpty() ? " (" + text + ")" : "";
            LOG.info("[T-SqlLogPerf] " + name + " " + value + suffix);
        }
    }

    // ---------- T-SqlListCatchup 券D：全量分布采样 ----------

    /** 券D：按名分布读数（{@link #sampleStats} 出口；count=0 时返回 null）。 */
    public static final class SampleStats {
        /** 样本总数（自 {@link #resetSamples()} 起累计；环形只保留最近 {@link #SAMPLE_RING_SIZE} 个的明细）。 */
        private final long count;
        private final double meanMs;
        private final double p50Ms;
        private final double p90Ms;
        private final double maxMs;

        SampleStats(long count, double meanMs, double p50Ms, double p90Ms, double maxMs) {
            this.count = count;
            this.meanMs = meanMs;
            this.p50Ms = p50Ms;
            this.p90Ms = p90Ms;
            this.maxMs = maxMs;
        }

        public long count() {
            return count;
        }

        public double meanMs() {
            return meanMs;
        }

        public double p50Ms() {
            return p50Ms;
        }

        public double p90Ms() {
            return p90Ms;
        }

        public double maxMs() {
            return maxMs;
        }

        @Override
        public String toString() {
            return String.format("count=%d mean=%.1fms p50=%.1fms p90=%.1fms max=%.1fms",
                count, meanMs, p50Ms, p90Ms, maxMs);
        }
    }

    /** 环形样本缓冲（按名一把锁——measure 调用方跨线程但低频，临界区毫微秒级，无争用面）。 */
    private static final class SampleRing {
        private final long[] buf = new long[SAMPLE_RING_SIZE];
        private long count = 0L;
        private long sumNanos = 0L;

        void add(long costNanos) {
            buf[(int) ((count++) % SAMPLE_RING_SIZE)] = costNanos;
            sumNanos += costNanos;
        }

        /** 快照统计（count 全量口径；分位取环形内最近明细，count ≤ 环容量时两者一致）。 */
        SampleStats stats() {
            int n = (int) Math.min(count, (long) SAMPLE_RING_SIZE);
            long[] recent = new long[n];
            for (int i = 0; i < n; i++) {
                recent[i] = buf[i];
            }
            Arrays.sort(recent);
            double p50 = at(recent, n, 0.50);
            double p90 = at(recent, n, 0.90);
            return new SampleStats(
                count,
                count == 0L ? 0.0 : sumNanos / 1_000_000.0 / count,
                p50,
                p90,
                n == 0 ? 0.0 : recent[n - 1] / 1_000_000.0);
        }

        private static double at(long[] recent, int n, double pct) {
            if (n == 0) return 0.0;
            int idx = (int) (pct * (n - 1));
            return recent[idx] / 1_000_000.0;
        }

        void reset() {
            Arrays.fill(buf, 0L);
            count = 0L;
            sumNanos = 0L;
        }
    }

    private static final ConcurrentHashMap<String, SampleRing> RINGS = new ConcurrentHashMap<>();

    /**
     * 券D：取按名分布读数（count / mean / p50 / p90 / max）。
     * 压测复测读数出口——复测前 {@link #resetSamples()} 划窗口，结束后本函数取数；
     * 无样本（未采样过或 reset 后）返回 null。
     */
    public static SampleStats sampleStats(String name) {
        SampleRing ring = RINGS.get(name);
        if (ring == null) {
            return null;
        }
        synchronized (ring) {
            return ring.count == 0L ? null : ring.stats();
        }
    }

    /** 券D：清空全部采样（压测复测划窗口用；生产常态勿调）。 */
    public static void resetSamples() {
        for (SampleRing ring : RINGS.values()) {
            synchronized (ring) {
                ring.reset();
            }
        }
    }

    /** 券D：样本入环 + 周期摘要（每 {@link #SAMPLE_SUMMARY_EVERY} 个样本一条 INFO，沿用 idea.log 留痕呈现）。 */
    private static void recordSample(String name, long costNanos) {
        SampleRing ring = RINGS.computeIfAbsent(name, k -> new SampleRing());
        SampleStats summary = null;
        synchronized (ring) {
            ring.add(costNanos);
            if (ring.count % SAMPLE_SUMMARY_EVERY == 0L) {
                summary = ring.stats();
            }
        }
        if (summary != null) {
            LOG.info("[T-SqlLogPerf] " + name + " 分布摘要: " + summary);
        }
    }
}
