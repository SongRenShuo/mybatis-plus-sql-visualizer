package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import com.intellij.ui.SimpleTextAttributes
import java.awt.Font
import javax.swing.JList
import javax.swing.JPanel

internal object SqlEntryPresentation {
    private val secondaryAttributes = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY)

    /**
     * 通栏注脚片段（T-LogListRail 新增）：目标库角标 / MCP 徽标 / 影响行数 / 去重计数 / 同结构计数。
     *
     * 这些片段的宽度不可控（连接串、影响行数、计数徽章），88px 轨道装不下，改挂 SQL 区下方通栏。
     * 与 [metadata] 的差别只在**剔除已在轨道里展示的三项**：序号、日期时间、耗时、来源类名。
     * 顺序沿用改造前口径（库 → MCP → 影响行数 → 去重 → 同结构），保证老用户视觉记忆连续。
     *
     * @param includeError 是否把报错片段并进注脚。默认 false——报错由渲染器单独挂错误行（红字通栏），
     *   避免同一文本出现两次。
     */
    fun details(
        entry: LogSqlEntry,
        selected: Boolean,
        templateCounts: Map<String, Int>,
        dedupeCounts: Map<String, Int> = emptyMap(),
        includeError: Boolean = false,
    ): List<SqlTextFragment> = buildList {
        fun addFragment(fragment: Pair<String, SimpleTextAttributes>?) {
            if (fragment != null) add(SqlTextFragment(fragment.first, fragment.second))
        }
        addFragment(formatDatabaseFragment(entry, selected))
        addFragment(formatMcpFragment(entry, selected))
        addFragment(formatUpdateCountFragment(entry, selected))
        if (includeError) addFragment(formatErrorFragment(entry))
        // UI013：同键的其他来源可能有丢弃计数，不能把徽章借给未参与去重的 Sharding 执行。
        dedupeCounts[entry.dedupeKey]?.takeIf { it > 0 && entry.source != SqlSource.SHARDINGSPHERE }?.let {
            add(SqlTextFragment("⨉$it ", secondaryAttributes))
        }
        addFragment(formatTemplateCountFragment(entry, templateCounts, selected))
    }

    fun metadata(
        entry: LogSqlEntry,
        selected: Boolean,
        showDuration: Boolean,
        templateCounts: Map<String, Int>,
        dedupeCounts: Map<String, Int> = emptyMap(),
        showLogDetails: Boolean = false,
    ): List<SqlTextFragment> = buildList {
        fun addFragment(fragment: Pair<String, SimpleTextAttributes>?) {
            if (fragment != null) add(SqlTextFragment(fragment.first, fragment.second))
        }

        if (entry.sequenceNumber > 0) add(SqlTextFragment("#${entry.sequenceNumber} ", secondaryAttributes))
        addFragment(formatTimestampFragment(entry))
        if (showDuration) addFragment(formatDurationFragment(entry.executionTimeMs, selected))
        addFragment(formatDatabaseFragment(entry, selected))
        addFragment(formatMcpFragment(entry, selected))
        if (showLogDetails) {
            addFragment(formatUpdateCountFragment(entry, selected))
            addFragment(formatErrorFragment(entry))
            // UI013：同键的其他来源可能有丢弃计数，不能把徽章借给未参与去重的 Sharding 执行。
            dedupeCounts[entry.dedupeKey]?.takeIf { it > 0 && entry.source != SqlSource.SHARDINGSPHERE }?.let {
                add(SqlTextFragment("⨉$it ", secondaryAttributes))
            }
        }
        addFragment(formatTemplateCountFragment(entry, templateCounts, selected))
        addFragment(formatSourceFragment(entry, selected))
    }
}

/**
 * 可见行裁剪（T-LogListRail 改判：关键字换行从「前 2 行 + 整行占位符」改为「3 行实内容」）。
 *
 * 原实现超 3 行时取前 2 行、第三行整行放 `… (+N)`，等于**三行只读到两个子句**。用户实测反馈
 * 这种形态下 `WHERE` 之后的子句全部不可见，而 `WHERE` 恰是排查时最需要看的部分。
 *
 * 现形态：三个子句全部渲染，剩余条数作为**末行行尾**的小尾巴（`… (+N)`），
 * 信息量从「2 个子句」提到「3 个子句」，且不增加行高。
 *
 * FORMATTED 不裁剪（格式化模式按用户要求不封顶——SQL 区能高多少就高多少）。
 */
internal fun visibleSqlLines(
    lines: List<List<SqlTextFragment>>,
    mode: SqlListDisplayMode,
): List<List<SqlTextFragment>> {
    if (mode != SqlListDisplayMode.KEYWORD_WRAP || lines.size <= MAX_KEYWORD_WRAP_LINES) return lines
    val hidden = lines.size - MAX_KEYWORD_WRAP_LINES
    val tail = SqlTextFragment(" … (+$hidden)", KEYWORD_WRAP_TAIL_ATTRIBUTES)
    val lastVisible = lines[MAX_KEYWORD_WRAP_LINES - 1] + tail
    return lines.take(MAX_KEYWORD_WRAP_LINES - 1) + listOf(lastVisible)
}

/** 关键字换行的可见行数上限（三个子句：SELECT / FROM / WHERE 量级）。 */
internal const val MAX_KEYWORD_WRAP_LINES = 3

/** 末行小尾巴的着色（灰、不抢 SQL 语法高亮）。 */
private val KEYWORD_WRAP_TAIL_ATTRIBUTES =
    com.intellij.ui.SimpleTextAttributes(
        com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN,
        cn.song.mybatisplus.visualizer.util.MpvsUiColors.META_GRAY,
    )

internal fun selectedAttributes(attributes: SimpleTextAttributes, foreground: java.awt.Color): SimpleTextAttributes =
    SimpleTextAttributes(attributes.style, foreground)

internal fun clipForList(
    fragments: List<SqlTextFragment>,
    list: JList<*>?,
    cell: JPanel,
    colorBarWidth: Int,
): List<SqlTextFragment> {
    if (list == null || list.width <= 0) return fragments
    val width = list.width - cell.insets.left - cell.insets.right - colorBarWidth - list.insets.left - list.insets.right
    val baseFont = list.font ?: return fragments
    val metrics = HashMap<Int, java.awt.FontMetrics>(3)
    return clipSqlFragments(fragments, width) { text, attributes ->
        val style = (if (attributes.style and SimpleTextAttributes.STYLE_BOLD != 0) Font.BOLD else 0) or
            (if (attributes.style and SimpleTextAttributes.STYLE_ITALIC != 0) Font.ITALIC else 0)
        metrics.getOrPut(style) { list.getFontMetrics(baseFont.deriveFont(style)) }.stringWidth(text)
    }
}

internal fun clipSqlFragments(
    fragments: List<SqlTextFragment>,
    maxWidth: Int,
    measure: (String, SimpleTextAttributes) -> Int,
): List<SqlTextFragment> {
    if (maxWidth <= 0 || fragments.isEmpty()) return fragments
    var total = 0
    for (fragment in fragments) total += measure(fragment.text, fragment.attributes)
    if (total <= maxWidth) return fragments

    val ellipsis = "…"
    val clipped = ArrayList<SqlTextFragment>(fragments.size)
    var used = 0
    for (fragment in fragments) {
        val available = maxWidth - used - measure(ellipsis, fragment.attributes)
        if (available <= 0) break
        val text = fragment.text
        if (measure(text, fragment.attributes) <= available) {
            clipped.add(fragment)
            used += measure(text, fragment.attributes)
            continue
        }
        var low = 0
        var high = text.codePointCount(0, text.length)
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            val prefix = text.substring(0, text.offsetByCodePoints(0, mid))
            if (measure(prefix, fragment.attributes) <= available) low = mid else high = mid - 1
        }
        if (low > 0) clipped.add(SqlTextFragment(text.substring(0, text.offsetByCodePoints(0, low)), fragment.attributes))
        used += if (low > 0) measure(clipped.last().text, fragment.attributes) else 0
        break
    }
    val attrs = clipped.lastOrNull()?.attributes ?: fragments.first().attributes
    if (used + measure(ellipsis, attrs) <= maxWidth) clipped.add(SqlTextFragment(ellipsis, attrs))
    return clipped
}
