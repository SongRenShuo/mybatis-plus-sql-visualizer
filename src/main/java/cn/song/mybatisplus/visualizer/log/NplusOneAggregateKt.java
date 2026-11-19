package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * T-Nplus1Aggregate D235：N+1 嫌疑徽章聚合视图「点开看全」——纯逻辑面（模型 + 摘要构建器，
 * UI 接线层保持薄；F-13「单一纯比较器」拆小文件先例）。
 *
 * <p>设计要点（计划册 §二）：
 * <ul>
 *   <li><b>口径一致性是生命线</b>：过滤态复用既有 {@code FocusTab.Template}（O-90「只看同结构」形态，
 *       过滤键＝徽章判定用的同一个 structureKey，见 NplusOneSuspect / buffer windowSuspectKeys）；
 *       摘要行条数源＝<b>完整列表</b>该键条目数（{@link #buildNplusOneAggregateSummary}），与过滤组
 *       （按 structureKey 精确匹配）构造上同源同值——A1/A3 断言钉死。</li>
 *   <li><b>回退开关</b>：{@code NplusOneAggregate.REGISTRY_KEY}（默认开；关＝徽章纯行级，点击无过滤态）。</li>
 * </ul>
 */
public final class NplusOneAggregateKt {

    private NplusOneAggregateKt() {}

    /** D235：N+1 嫌疑徽章片段 tag（渲染时挂在徽章片段上，点击命中判定用，见 nplusOneBadgeEntryAt）。 */
    static final String NPLUS_ONE_BADGE_TAG = "mpvs.nplusOneBadge";

    /** 摘要行线程名单最多展示数（超出截断加省略号——单行纪律，名单无限长会顶爆元信息行）。 */
    static final int NPLUS_ONE_SUMMARY_THREAD_MAX = 3;

    /** 摘要行时间格式（{@code HH:mm:ss}——N+1 组自 60s 滑动窗判定而来，日期粒度无信息量）。 */
    private static final String SUMMARY_TIME_FORMAT = "HH:mm:ss";

    /**
     * 构建聚合摘要：从<b>完整列表</b>中取该 structureKey 的条目组（条数/首末时间/线程名单）。
     * 组空或键空返回 null（调用方据此隐藏摘要行）。
     */
    static NplusOneAggregateSummary buildNplusOneAggregateSummary(List<LogSqlEntry> entries, String structureKey) {
        if (structureKey.isEmpty()) return null;
        List<LogSqlEntry> group = new ArrayList<>();
        for (LogSqlEntry e : entries) {
            if (structureKey.equals(e.structureKey())) {
                group.add(e);
            }
        }
        if (group.isEmpty()) return null;
        LinkedHashSet<String> threads = new LinkedHashSet<>();
        for (LogSqlEntry e : group) {
            if (e.threadName() != null) {
                threads.add(e.threadName());
            }
        }
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (LogSqlEntry e : group) {
            first = Math.min(first, e.timestamp());
            last = Math.max(last, e.timestamp());
        }
        return new NplusOneAggregateSummary(
                structureKey,
                group.size(),
                first,
                last,
                new ArrayList<>(threads));
    }

    /**
     * 摘要<b>详情</b>文案（badge 由面板单独渲染，见 {@link #formatNplusOneAggregateSummaryLabel}）。
     *
     * <p>排版纪律（2026-11-19 用户反馈「整行全红、与聚焦行重复、同秒时间重复」后重排）：
     * <ul>
     *   <li>不再重复「同结构 N 条」（上一行聚焦 chip 已有），改用「命中 N 条」表达 N+1 语义；</li>
     *   <li>分隔符用轻量的 {@code ·}（原 {@code ｜} 过重）；</li>
     *   <li>首末同秒时只显示一个时间（原样会渲染成 {@code 12:56:30 ~ 12:56:30}）；</li>
     *   <li>单行纪律不变（JBLabel {@code \n} 不渲染），线程名单超 NPLUS_ONE_SUMMARY_THREAD_MAX 截断。</li>
     * </ul>
     */
    static String formatNplusOneAggregateSummary(NplusOneAggregateSummary summary) {
        SimpleDateFormat fmt = new SimpleDateFormat(SUMMARY_TIME_FORMAT);
        List<String> parts = new ArrayList<>();
        parts.add(MpSqlVisualizerBundle.message("log.nplusOneAggregate.count", String.valueOf(summary.count())));
        String first = fmt.format(new Date(summary.firstTimestampMs()));
        String last = fmt.format(new Date(summary.lastTimestampMs()));
        parts.add(first.equals(last)
                ? first
                : MpSqlVisualizerBundle.message("log.nplusOneAggregate.timeRange", first, last));
        List<String> shown = summary.threadNames().size() > NPLUS_ONE_SUMMARY_THREAD_MAX
                ? summary.threadNames().subList(0, NPLUS_ONE_SUMMARY_THREAD_MAX)
                : summary.threadNames();
        String suffix = summary.threadNames().size() > NPLUS_ONE_SUMMARY_THREAD_MAX ? "…" : "";
        if (!summary.threadNames().isEmpty()) {
            if (summary.crossThread()) {
                parts.add(MpSqlVisualizerBundle.message(
                        "log.nplusOneAggregate.threads",
                        String.valueOf(summary.threadNames().size()),
                        String.join(MpSqlVisualizerBundle.message("log.nplusOneAggregate.threadSep"), shown) + suffix));
            } else {
                parts.add(MpSqlVisualizerBundle.message("log.nplusOneAggregate.thread", shown.get(0)));
            }
        }
        return String.join(" · ", parts);
    }

    /** 摘要<b>标签</b>文案（面板用强调色单独渲染，与详情分色——避免整行红字压屏）。 */
    static String formatNplusOneAggregateSummaryLabel() {
        return MpSqlVisualizerBundle.message("log.nplusOneAggregate.label");
    }
}
