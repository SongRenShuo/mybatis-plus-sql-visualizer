package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.util.registry.Registry
import java.text.SimpleDateFormat
import java.util.Date

/**
 * T-Nplus1Aggregate D235：N+1 嫌疑徽章聚合视图「点开看全」——纯逻辑面（模型 + 摘要构建器，
 * UI 接线层保持薄；F-13「单一纯比较器」拆小文件先例）。
 *
 * 设计要点（计划册 §二）：
 *  - **口径一致性是生命线**：过滤态复用既有 [FocusTab.Template]（O-90「只看同结构」形态，
 *    过滤键＝徽章判定用的同一个 structureKey，见 [NplusOneSuspect] / buffer `windowSuspectKeys`）；
 *    摘要行条数源＝**完整列表**该键条目数（[buildNplusOneAggregateSummary]），与过滤组
 *    （[filterByFocusTabs] 按 structureKey 精确匹配）构造上同源同值——A1/A3 断言钉死。
 *  - **回退开关**：[REGISTRY_KEY]（默认开；关＝徽章纯行级，点击无过滤态；徽章本身仍由
 *    `NplusOneSuspect.REGISTRY_KEY` 独立控制——两层开关语义分层）。
 */
internal object NplusOneAggregate {

    /** Registry 总开关（plugin.xml `<registryKey>` 登记）；关闭＝徽章纯行级、点击无过滤态。 */
    internal const val REGISTRY_KEY = "sqlvis.log.nplusOneSuspect.aggregate"

    /** 测试缝：总开关读取（生产＝`Registry.is(REGISTRY_KEY, true)`）。 */
    internal var enabledProvider: () -> Boolean = {
        runCatching { Registry.`is`(REGISTRY_KEY, true) }.getOrDefault(true)
    }
}

/** D235：N+1 嫌疑徽章片段 tag（渲染时挂在徽章片段上，点击命中判定用，见 [nplusOneBadgeEntryAt]）。 */
internal const val NPLUS_ONE_BADGE_TAG = "mpvs.nplusOneBadge"

/** 摘要行线程名单最多展示数（超出截断加省略号——单行纪律，名单无限长会顶爆元信息行）。 */
internal const val NPLUS_ONE_SUMMARY_THREAD_MAX = 3

/**
 * 聚合摘要（D235 过滤态顶部一行）：条数 / 首末时间 / 线程名单（跨线程如实标注）。
 * [count] 恒取**完整列表**该键条目数（口径生命线，见文件 KDoc）。
 */
internal data class NplusOneAggregateSummary(
    val structureKey: String,
    val count: Int,
    val firstTimestampMs: Long,
    val lastTimestampMs: Long,
    /** 非空线程名去重保序名单（null 线程条目不进名单；全 null 时线程段缺省）。 */
    val threadNames: List<String>,
) {
    /** 组内条目分散在多个线程（摘要行如实标注「分散在 N 个线程」）。 */
    val crossThread: Boolean get() = threadNames.size > 1
}

/** 摘要行时间格式（`HH:mm:ss`——N+1 组自 60s 滑动窗判定而来，日期粒度无信息量）。 */
private const val SUMMARY_TIME_FORMAT = "HH:mm:ss"

/**
 * 构建聚合摘要：从**完整列表**中取该 structureKey 的条目组（条数/首末时间/线程名单）。
 * 组空或键空返回 null（调用方据此隐藏摘要行）。
 */
internal fun buildNplusOneAggregateSummary(
    entries: List<LogSqlEntry>,
    structureKey: String,
): NplusOneAggregateSummary? {
    if (structureKey.isEmpty()) return null
    val group = entries.filter { it.structureKey == structureKey }
    if (group.isEmpty()) return null
    val threads = LinkedHashSet<String>()
    for (e in group) e.threadName?.let { threads.add(it) }
    return NplusOneAggregateSummary(
        structureKey = structureKey,
        count = group.size,
        firstTimestampMs = group.minOf { it.timestamp },
        lastTimestampMs = group.maxOf { it.timestamp },
        threadNames = threads.toList(),
    )
}

/**
 * 摘要行文案（单行 `｜` 分段纪律——JBLabel `\n` 不渲染，见 AGENTS 平台坑；`…` 截断线程名单）。
 * 线程段：单线程直报名；跨线程如实标注条数＋名单（超出 [NPLUS_ONE_SUMMARY_THREAD_MAX] 截断）；
 * 无线程名（控制台回落通道）缺省该段。
 */
internal fun formatNplusOneAggregateSummary(summary: NplusOneAggregateSummary): String {
    val fmt = SimpleDateFormat(SUMMARY_TIME_FORMAT)
    val parts = mutableListOf(
        MpSqlVisualizerBundle.message("log.nplusOneAggregate.label"),
        MpSqlVisualizerBundle.message("log.nplusOneAggregate.count", summary.count.toString()),
        MpSqlVisualizerBundle.message(
            "log.nplusOneAggregate.timeRange",
            fmt.format(Date(summary.firstTimestampMs)),
            fmt.format(Date(summary.lastTimestampMs)),
        ),
    )
    val shown = summary.threadNames.take(NPLUS_ONE_SUMMARY_THREAD_MAX)
    val suffix = if (summary.threadNames.size > NPLUS_ONE_SUMMARY_THREAD_MAX) "…" else ""
    when {
        summary.threadNames.isEmpty() -> Unit
        summary.crossThread -> parts.add(
            MpSqlVisualizerBundle.message(
                "log.nplusOneAggregate.threads",
                summary.threadNames.size.toString(),
                shown.joinToString(MpSqlVisualizerBundle.message("log.nplusOneAggregate.threadSep")) + suffix,
            ),
        )
        else -> parts.add(MpSqlVisualizerBundle.message("log.nplusOneAggregate.thread", shown.first()))
    }
    return parts.joinToString(" ｜ ")
}
