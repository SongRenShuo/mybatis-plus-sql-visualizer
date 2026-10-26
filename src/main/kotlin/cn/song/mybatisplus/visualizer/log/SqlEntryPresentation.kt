package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import com.intellij.ui.SimpleTextAttributes
import java.awt.Font
import javax.swing.JList
import javax.swing.JPanel

internal object SqlEntryPresentation {
    private val secondaryAttributes = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY)

    /**
     * 头部行片段（T-LogListHeader）：SQL 上方那一行的全部元信息。
     *
     * 顺序（用户 2026-10-26 定案「方案④ 库前置」）：
     * ```
     * #序号 · 时间 · 耗时 · [目标库] · [MCP] · 类名:行号 · (影响行数) · ⨉去重 · 同结构 N 条
     * ```
     *
     * 为什么库在类名之前：
     *  - 库角标是**定长短串**（`[MySQL]` 8 字符 / `[PostgreSQL]` 13 字符），位置固定，
     *    扫视时形成稳定锚点；类名长度随内容浮动（骨架化后 13 字符 + 行号），放它之后，
     *    宽度不足时先裁它不会破坏前几列的稳定性。
     *  - 多数据源场景下「这条打的是哪个库」是判定 SQL 是否走对源的第一判据，
     *    比「哪个类打的」更前置。
     *
     * `(影响行数)` 只在 DML 上有值；SELECT 不显示（不补空占位——那会让每行多出一段空白）。
     */
    fun headerFragments(
        entry: LogSqlEntry,
        selected: Boolean,
        showDuration: Boolean,
        templateCounts: Map<String, Int>,
        dedupeCounts: Map<String, Int> = emptyMap(),
    ): List<SqlTextFragment> = buildList {
        fun addFragment(fragment: Pair<String, SimpleTextAttributes>?) {
            if (fragment != null) add(SqlTextFragment(fragment.first, fragment.second))
        }
        if (entry.sequenceNumber > 0) add(SqlTextFragment("#${entry.sequenceNumber} ", secondaryAttributes))
        addFragment(formatTimestampFragment(entry))
        if (showDuration) addFragment(formatDurationFragment(entry.executionTimeMs, selected))
        addFragment(formatDatabaseFragment(entry, selected))
        addFragment(formatMcpFragment(entry, selected))
        addFragment(sourceNameText(entry))
        addFragment(formatUpdateCountFragment(entry, selected))
        // UI013：同键的其他来源可能有丢弃计数，不能把徽章借给未参与去重的 Sharding 执行。
        dedupeCounts[entry.dedupeKey]?.takeIf { it > 0 && entry.source != SqlSource.SHARDINGSPHERE }?.let {
            add(SqlTextFragment("⨉$it ", secondaryAttributes))
        }
        addFragment(formatTemplateCountFragment(entry, templateCounts, selected))
    }

    /**
     * 通栏注脚片段（历史遗留：`bed58b64` 把角标/徽章放在 SQL 下方通栏）。
     *
     * T-LogListHeader 起**渲染器不再使用本方法**——注脚行已合并进 [headerFragments]。
     * 保留是因为存量测试仍直接断言它（`SqlEntryPresentationTest`），且它是
     * 「哪些片段属于诊断类」的口径文档。新代码请用 [headerFragments]。
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

// ---------- T-LogListHeader：头部行按优先级丢片段 ----------

/**
 * 头部行片段在宽度不足时的**丢弃顺序**（数值越小越先丢）。
 *
 * 用户定案的优先级（2026-10-26）：日期 → 影响行数 → 库角标 → 同结构徽章 → 来源行号 → 序号。
 * 语义依据：
 *  - **日期**最占地方（`2026-10-26 ` 占 11 字符）且信息密度最低——单次会话内跨天罕见，
 *    跨天时预览窗仍能看到完整时间戳；
 *  - **影响行数**只有 DML 有，且排查时很少是第一判据；
 *  - **库角标**是定长短串，丢了也只是少一层确认（本项目多数场景单数据源）；
 *  - **同结构徽章**是 N+1 线索，丢之前应尽量保；
 *  - **来源行号**是定位锚点，只在极窄时才退化（保留类名、丢行号）；
 *  - **序号**最后丢——它是最常用的引用锚点（「#42 那条」）。
 *
 * 未列入的片段（时刻、耗时、类名、MCP、去重计数）按 [DROP_PRIORITY_NEVER] 处理：
 * 它们排在最后、只在整个头部行真的塞不下时才被截断。
 */
internal object HeaderFragmentPriority {
    /** 日期段（`yyyy-MM-dd ` 前缀，仅当时间片段以此开头时剥离）。 */
    const val DATE_PART = 1

    /** 影响行数 `(N rows)`。 */
    const val UPDATE_COUNT = 2

    /** 目标库角标 `[MySQL]`。 */
    const val DATABASE = 3

    /** 同结构徽章。 */
    const val TEMPLATE_BADGE = 4

    /** 来源行号（`User:41` → `User`）。 */
    const val SOURCE_LINE = 5

    /** 序号 `#N`。 */
    const val SEQUENCE = 6

    /** 不参与主动丢弃（只在整个头部行放不下时被截断）。 */
    const val NEVER = Int.MAX_VALUE
}

/** 片段类别判定（按片段文本形态识别，避免给 [SqlTextFragment] 加字段破坏既有构造点）。 */
private fun dropPriorityOf(fragment: SqlTextFragment): Int {
    val text = fragment.text
    return when {
        // `(3 rows) ` —— 影响行数
        text.contains(" rows)") -> HeaderFragmentPriority.UPDATE_COUNT
        // 时间片段（含剥完日期的 `[09:42:02] `）不参与主动丢弃——它不是「库角标」。
        // 必须先于下面的「方括号」判定，否则 `[09:42:02]` 会被当成库角标提前丢掉。
        isTimeFragment(text) -> HeaderFragmentPriority.NEVER
        // `[MySQL] ` / `[MySQL · jdbc:...] ` —— 目标库角标（MCP 徽标 `[MCP] ` 单独放过）
        text.startsWith("[") && text.trim().endsWith("]") && !text.startsWith("[MCP]") -> HeaderFragmentPriority.DATABASE
        // `同结构 N 条` / `×N 同模板`
        text.contains("同结构") || text.contains("同模板") -> HeaderFragmentPriority.TEMPLATE_BADGE
        // `⨉N ` —— 去重计数（不是模板徽章，保留优先级高）
        text.startsWith("⨉") -> HeaderFragmentPriority.NEVER
        else -> HeaderFragmentPriority.NEVER
    }
}

/** 是否时间片段（`HH:mm:ss` 或 `yyyy-MM-dd HH:mm:ss` 形态，可带方括号）。 */
private fun isTimeFragment(text: String): Boolean {
    val core = text.trim().removeSurrounding("[", "]")
    // 含冒号且含数字即可判定为时刻——库角标/徽章不会出现冒号
    return core.contains(':') && core.any { it.isDigit() }
}

/**
 * 按优先级裁剪头部行片段（T-LogListHeader 新增算法）。
 *
 * 与 [clipSqlFragments] 的差别：后者是「从左到右、超出即停、末尾补省略号」，
 * 无法表达「先丢掉中间那个占地方的日期、保住后面的徽章」。本函数按 [HeaderFragmentPriority]
 * 由低到高依次丢弃，直到总宽放得下；全部丢完仍放不下才退化为 [clipSqlFragments] 的截断。
 *
 * 纯函数（测量器注入），便于单测断言各种宽度档位下的保留集。
 *
 * @param maxWidth 可用像素宽；<=0 视为「未测量」直接原样返回（headless / 首帧）
 */
internal fun clipFragmentsByPriority(
    fragments: List<SqlTextFragment>,
    maxWidth: Int,
    measure: (String, SimpleTextAttributes) -> Int,
): List<SqlTextFragment> {
    if (maxWidth <= 0 || fragments.isEmpty()) return fragments
    var total = 0
    for (f in fragments) total += measure(f.text, f.attributes)
    if (total <= maxWidth) return fragments

    // 第一步：剥日期（独立于优先级循环）。
    // 时间片段本身是 NEVER 级（时刻不能丢），但它的**日期段**是 [HeaderFragmentPriority.DATE_PART]。
    // 两件事必须分开：先尝试剥日期，再进优先级循环——否则「时间 = NEVER」会让日期永远剥不掉
    // （曾踩过：把日期剥离塞进优先级循环，结果 levels 里根本没有时间片段）。
    val dateIdx = fragments.indexOfFirst { isDatePrefixed(it) }
    if (dateIdx >= 0) {
        val stripped = stripDatePrefix(fragments[dateIdx])
        val afterDate = fragments.toMutableList().also { it[dateIdx] = stripped }
        var strippedTotal = 0
        for (f in afterDate) strippedTotal += measure(f.text, f.attributes)
        if (strippedTotal <= maxWidth) return afterDate
        return dropByPriority(afterDate, maxWidth, measure)
    }
    return dropByPriority(fragments, maxWidth, measure)
}

/**
 * 按优先级逐级丢弃（日期剥离之后的阶段）。
 *
 * 同一级内从右往左丢（越靠右越次要）；全部丢完仍放不下才退化为 [clipSqlFragments] 的顺序截断。
 */
private fun dropByPriority(
    fragments: List<SqlTextFragment>,
    maxWidth: Int,
    measure: (String, SimpleTextAttributes) -> Int,
): List<SqlTextFragment> {
    var total = 0
    for (f in fragments) total += measure(f.text, f.attributes)
    if (total <= maxWidth) return fragments

    val dropped = BooleanArray(fragments.size)
    val levels = fragments.indices
        .filter { dropPriorityOf(fragments[it]) != HeaderFragmentPriority.NEVER }
        .sortedWith(compareBy({ dropPriorityOf(fragments[it]) }, { -it }))

    for (idx in levels) {
        dropped[idx] = true
        total -= measure(fragments[idx].text, fragments[idx].attributes)
        if (total <= maxWidth) return rebuild(fragments, dropped, null)
    }
    val kept = fragments.filterIndexed { i, _ -> !dropped[i] }
    return clipSqlFragments(kept, maxWidth, measure)
}

/**
 * 片段是否是「日期+时刻」形态。
 *
 * 实际片段来自 [formatTimestampFragment]，形态是 `[2026-10-26 09:42:02] `（带方括号）——
 * 故必须容忍前导 `[`，否则剥日期这条规则永远不生效（踩过一次：单测里手写无括号形态能过、
 * 真实渲染不生效）。
 */
private fun isDatePrefixed(fragment: SqlTextFragment): Boolean {
    val text = fragment.text
    val start = if (text.startsWith("[")) 1 else 0
    // `yyyy-MM-dd` 共 10 位，后跟一个空格
    if (text.length < start + 11) return false
    return text[start].isDigit() &&
        text[start + 4] == '-' &&
        text[start + 7] == '-' &&
        text[start + 10] == ' '
}

/** 剥掉日期前缀，保留时刻（带方括号时一并保留括号）。 */
private fun stripDatePrefix(fragment: SqlTextFragment): SqlTextFragment {
    val text = fragment.text
    return if (text.startsWith("[")) {
        // `[2026-10-26 09:42:02] ` → `[09:42:02] `
        val close = text.indexOf(']')
        if (close < 0) SqlTextFragment(text, fragment.attributes)
        else SqlTextFragment("[" + text.substring(12, close + 1) + text.substring(close + 1), fragment.attributes)
    } else {
        SqlTextFragment(text.substring(11), fragment.attributes)
    }
}

private fun rebuild(
    fragments: List<SqlTextFragment>,
    dropped: BooleanArray,
    replaced: Pair<Int, SqlTextFragment>?,
): List<SqlTextFragment> = fragments.mapIndexedNotNull { i, f ->
    when {
        replaced != null && replaced.first == i -> replaced.second
        dropped[i] -> null
        else -> f
    }
}

