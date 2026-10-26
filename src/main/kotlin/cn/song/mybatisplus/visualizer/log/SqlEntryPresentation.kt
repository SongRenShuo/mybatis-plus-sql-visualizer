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

        /** 标注丢弃优先级的片段（宽度不足时按优先级从低到高丢）。 */
        fun addPrioritized(fragment: Pair<String, SimpleTextAttributes>?, priority: Int) {
            fragment?.let { add(SqlTextFragment(it.first, it.second, priority)) }
        }

        // 序号：最后才丢（最常用的引用锚点「#42 那条」）
        if (entry.sequenceNumber > 0) {
            add(SqlTextFragment("#${entry.sequenceNumber} ", secondaryAttributes, HeaderFragmentPriority.SEQUENCE))
        }
        // 时间与耗时：显式 NEVER——时间是用户配置格式的投影，宽度不足时宁可截断末尾也不整段丢
        addFragment(formatTimestampFragment(entry))
        if (showDuration) addFragment(formatDurationFragment(entry.executionTimeMs, selected))
        addPrioritized(formatDatabaseFragment(entry, selected), HeaderFragmentPriority.DATABASE)
        addFragment(formatMcpFragment(entry, selected))
        addPrioritized(sourceNameText(entry), HeaderFragmentPriority.SOURCE_NAME)
        addPrioritized(sourceLineText(entry), HeaderFragmentPriority.SOURCE_LINE)
        addPrioritized(formatUpdateCountFragment(entry, selected), HeaderFragmentPriority.UPDATE_COUNT)
        // UI013：同键的其他来源可能有丢弃计数，不能把徽章借给未参与去重的 Sharding 执行。
        dedupeCounts[entry.dedupeKey]?.takeIf { it > 0 && entry.source != SqlSource.SHARDINGSPHERE }?.let {
            add(SqlTextFragment("⨉$it ", secondaryAttributes))
        }
        addPrioritized(formatTemplateCountFragment(entry, templateCounts, selected), HeaderFragmentPriority.TEMPLATE_BADGE)
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
 * 优先级（2026-10-26 定案，同日修正）：影响行数 → 库角标 → 同结构徽章 → 来源行号 → 序号。
 *
 * **时间片段整段不可拆**（见 §修正记录）：一度打算「宽度不足时剥掉日期、只留时刻」，
 * 但那等于按宽度偷偷改写用户配置的 `logTimestampFormat`——设置里写的是
 * `yyyy-MM-dd HH:mm:ss`，显示就该是它。宽度不够时宁可按顺序截断（末尾 `…`），
 * 也不该让同一份配置在宽栏/窄栏下显示成两种格式。故时间整段不可拆。
 *
 * 语义依据：
 *  - **影响行数**只有 DML 有，排查时很少是第一判据；
 *  - **库角标**是定长短串，丢了只是少一层确认（本项目多数场景单数据源）；
 *  - **同结构徽章**是 N+1 线索，丢之前应尽量保；
 *  - **来源行号**是定位锚点，只在极窄时才退化；
 *  - **序号**最后丢——它是最常用的引用锚点（「#42 那条」）。
 *
 * 未列入的片段（时间、耗时、类名、MCP、去重计数）不参与主动丢弃，
 * 只在整个头部行真的塞不下时被 [clipSqlFragments] 按顺序截断。
 */
internal object HeaderFragmentPriority {
    /**
     * 丢弃顺序（用户 2026-10-26 定案）：**方言与类位置先丢，序号绝不先丢**。
     *
     * 号码越小越先丢：
     * ```
     * 影响行数(1) → 库角标(2) → 来源类名(3) → 源码行号(4) → 同结构徽章(5) → 序号(6)
     * ```
     *
     * 依据：
     *  - 「类位置」＝来源类名 + 源码行号，是**最细的定位信息**，窄了先牺牲它；
     *  - **序号（`#42`）是引用锚点**（「#42 那条」），不能先丢——丢了就无法在团队里指认某条；
     *  - 同结构徽章是 N+1 线索，排在类位置之后、序号之前。
     *
     * 注意区分：此处的「源码行号」是 `:183`（Mapper 里的代码行），**不是**捕获序号 `#42`。
     * 两者一度被混为一谈，导致误以为「序号不能先丢」与「行号不能先丢」冲突——实为两个字段。
     */
    const val UPDATE_COUNT = 1

    /** 目标库角标 `[MySQL]`——定长短串，多数场景单数据源，先牺牲。 */
    const val DATABASE = 2

    /** 来源类名（整段丢，不做像素级二次截断，避免双层省略号残片）。 */
    const val SOURCE_NAME = 3

    /** 源码行号 `":183"` 整段——与类名同属「类位置」，紧随其后丢。 */
    const val SOURCE_LINE = 4

    /** 同结构徽章。 */
    const val TEMPLATE_BADGE = 5

    /** 捕获序号 `#N`——**最后才丢**，它是引用锚点。 */
    const val SEQUENCE = 6

/** 不参与主动丢弃（只在整个头部行放不下时被截断）。 */
    const val NEVER = Int.MAX_VALUE
}

/**
 * 片段优先级**显式读取**（不做形态猜测——见 [SqlTextFragment.dropPriority] 的说明）。
 *
 * 历史教训：曾按文本形态猜类别，`5ms ` 与骨架化类名 `…ionOccupyInfo ` 同形（都是不含方括号的
 * 短串），结果耗时被误判成可丢类名、480px 就把耗时丢了。现由生成片段处标注，读取处只取值。
 *
 * 唯一保留的形态兜底：**时间片段永远 NEVER**（即便调用方漏标）。时间是用户配置
 * `logTimestampFormat` 的投影，宽度不足时宁可截断末尾也不得整段丢弃。
 */
private fun dropPriorityOf(fragment: SqlTextFragment): Int {
    if (isTimeText(fragment.text)) return HeaderFragmentPriority.NEVER
    return fragment.dropPriority
}

/**
 * 兜底：时间片段判定。
 *
 * 形态：`HH:mm:ss`、`yyyy-MM-dd HH:mm:ss`（可带方括号）。
 *
 * **必须排除 `:183`（源码行号）**：它同样含冒号与数字，早期版本用「含冒号 + 含数字」
 * 判定，把源码行号误当成时间片段、强行升为 NEVER，导致它在窄宽度下永不丢弃
 * （实测 260px 时应丢行号却丢了序号）。现要求冒号左侧必须还有内容。
 */
private fun isTimeText(text: String): Boolean {
    val core = text.trim().removeSurrounding("[", "]")
    val colon = core.indexOf(':')
    // 冒号必须在中间（左侧有两位以上数字，如 `06:13:20` / `2023-11-15 06:13:20`），
    // 且不能是 `:183` 这种以冒号开头的行号形态
    if (colon <= 1) return false
    return core.any { it.isDigit() } && core.count { it == ':' } >= 1
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

    // 时间格式严格跟随设置（用户 2026-10-26 明确）：绝不为适配宽度改写时间文本，
    // 故这里没有「剥日期」一步——只按优先级丢其他片段，整段放不下才顺序截断。
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

