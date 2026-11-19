package cn.song.mybatisplus.visualizer.log;

import java.util.List;

/**
 * D235：N+1 嫌疑徽章聚合视图的聚合摘要（过滤态顶部一行）：条数 / 首末时间 / 线程名单（跨线程如实标注）。
 * {@link #count} 恒取<b>完整列表</b>该键条目数（口径生命线，见 NplusOneAggregateKt 文件 KDoc）。
 */
public record NplusOneAggregateSummary(
        String structureKey,
        int count,
        long firstTimestampMs,
        long lastTimestampMs,
        /** 非空线程名去重保序名单（null 线程条目不进名单；全 null 时线程段缺省）。 */
        List<String> threadNames) {

    /** 组内条目分散在多个线程（摘要行如实标注「分散在 N 个线程」）。 */
    boolean crossThread() {
        return threadNames.size() > 1;
    }
}
