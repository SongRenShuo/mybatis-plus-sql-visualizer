package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import com.intellij.ui.SimpleTextAttributes

/**
 * 头部行与条目的展示辅助（T-LogListHeader）。
 *
 * 演变：`bed58b64` 曾用 88px 左侧信息轨承载「序号/时刻/耗时/进度条/类名」，用户实测后否决——
 * 左侧栏占宽度且与 SQL 正文不同轴，扫视时要左右来回。现改为**头部一行承载全部元信息**，
 * 色条回到最左、SQL 区拿到整行宽度。
 *
 * 本文件只保留与「不依赖 Swing 组件」的纯投影函数，便于单测直接断言；
 * 渲染容器见 [RailCellRendererBase]（类名沿用历史，实际已是「头部行渲染基类」）。
 */

/**
 * 可见条目的最大耗时。
 *
 * 保留原因：虽然进度条已退役，但「本次可见条目的最慢一条」仍是排查时的常用读数
 * （慢查询徽章计数用的是阈值判定，不是相对最大值）。单遍遍历、零分配——
 * 不用 `mapNotNull{}.maxOrNull()`，后者要为每条分配一个列表槽，而本函数每次刷新都跑。
 */
internal fun maxDurationOf(entries: List<LogSqlEntry>): Long? {
    var max: Long? = null
    for (i in entries.indices) {
        val ms = entries[i].executionTimeMs ?: continue
        if (max == null || ms > max) max = ms
    }
    return max
}

/**
 * 头部行的来源类名文本。
 *
 * 类名走 [shortSourceName] 骨架化压缩：原实现取前 11 字符，实测
 * `BizCarStationOccupyInfoServiceImpl` 与 `BizCarStationOccupyController` 都压成
 * `BizCarStati…` 完全无法区分；改为先剥公共后缀再取尾部（业务词通常在尾部）。
 */
internal fun sourceNameText(entry: LogSqlEntry): Pair<String, SimpleTextAttributes>? {
    if (SourceMarkers.isMpPaginationCount(entry.sourceFile)) {
        return "MP 分页计数" to SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY)
    }
    val path = entry.sourceFile ?: return null
    val fileName = path.substringAfterLast('/')
    val className = fileName.substringBeforeLast('.', fileName)
    val line = if (entry.sourceLine > 0) ":${entry.sourceLine}" else ""
    // 尾随空格是片段间距契约（与 formatDurationFragment / formatDatabaseFragment 一致）——
    // 漏掉会让下一个片段粘上来（实测 `Order:42⨉3`）
    return (shortSourceName(className) + line + " ") to
        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY)
}

/** 公共后缀——剥掉后仍不足以表达类身份时才继续截断。 */
private val SOURCE_SUFFIXES = listOf(
    "ServiceImpl", "Controller", "Repository", "Service", "Mapper", "Impl",
)

/** 骨架化后允许的最大字符数。 */
internal const val SOURCE_NAME_MAX_CHARS = 14

/** 骨架化类名：剥公共后缀 → 仍超 [SOURCE_NAME_MAX_CHARS] 则保留尾部。 */
internal fun shortSourceName(className: String): String {
    var base = className
    for (suffix in SOURCE_SUFFIXES) {
        if (base.length > suffix.length && base.endsWith(suffix)) {
            base = base.dropLast(suffix.length)
            break
        }
    }
    if (base.length <= SOURCE_NAME_MAX_CHARS) return base
    return "…" + base.takeLast(SOURCE_NAME_MAX_CHARS - 1)
}
