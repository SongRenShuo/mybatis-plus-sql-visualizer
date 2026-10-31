package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer
import cn.song.mybatisplus.visualizer.util.DisplayMasker
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.tree.IElementType
import com.intellij.ui.SimpleTextAttributes

/** SQL 日志/历史列表条目中的 SQL 展示形态。 */
enum class SqlListDisplayMode {
    SINGLE_LINE,
    KEYWORD_WRAP,
    FORMATTED,
}

/** 按显示模式变换 SQL 文本；字符串原文保留。 */
internal fun prepareSqlForList(sql: String, mode: SqlListDisplayMode): String = when (mode) {
    SqlListDisplayMode.SINGLE_LINE -> sql
    SqlListDisplayMode.KEYWORD_WRAP -> SqlFormatter.formatClauseLine(sql)
    SqlListDisplayMode.FORMATTED -> SqlFormatter.format(sql)
}

/**
 * 关键字换行的取源预算（字符）。
 *
 * 关键字换行必须看到 `FROM`/`WHERE` 才能产出三行，所以取源是**完整还原 SQL**（与格式化模式同源），
 * 不再是 160 字摘要——摘要在长列清单下连一个子句关键字都覆盖不到，恒退化成单行（T-LogListAnchor 券01）。
 * 预算只影响第四行及以后：三行封顶后本来也看不到，但词法成本不能随 IN 列表长度无界放大。
 */
internal const val KEYWORD_WRAP_SOURCE_BUDGET = 4096

/** 超预算时按空白边界截断并补省略号（避免把标识符劈成两半）。 */
internal fun clipKeywordWrapSource(sql: String): String {
    if (sql.length <= KEYWORD_WRAP_SOURCE_BUDGET) return sql
    var cut = KEYWORD_WRAP_SOURCE_BUDGET
    var i = KEYWORD_WRAP_SOURCE_BUDGET - 1
    while (i > 0 && !sql[i].isWhitespace()) i--
    if (i > 0) cut = i
    return sql.substring(0, cut).trimEnd() + "…"
}

/**
 * 列表展示用 SQL 文本（T-LogListAnchor 券01 取源分层 + T-MagpieGems W2 按 [maskMode] 分流）：
 *
 * - `SINGLE_LINE`：摘要（一行封顶，摘要正是为此而生）；MASK/BLOCK 的摘要缓存以模式+白名单为键
 *   （UI-001 修复：白名单进缓存键，改白名单后同 mode 下摘要即重算）。
 * - `KEYWORD_WRAP` / `FORMATTED`：完整还原 SQL（与格式化模式同源——摘要在长列清单下连一个
 *   子句关键字都覆盖不到，恒退化成单行），前者另受 [KEYWORD_WRAP_SOURCE_BUDGET] 约束。
 * - 脱敏分流（T-MagpieGems W2）：OFF/WARN → 原样（WARN 的警示行只进预览浮层/停靠预览，列表
 *   渲染不追加）；MASK → 打码；BLOCK → 阻断占位（白名单豁免后计数 0 时按 OFF 原样）。
 *   MASK/BLOCK 须先处理完整 SQL 再截摘要（防旧摘要在引号中间截断漏打码）。
 *
 * 摘要缓存（[LogSqlEntry.cachedMaskedSummary]）只服务单行模式；多行模式按
 * 「模式+脱敏模式+白名单」缓存词法片段（`RailCellRendererBase.sqlLineFragmentsFor`），不复用摘要。
 */
internal fun displaySqlForList(
    entry: LogSqlEntry,
    mode: SqlListDisplayMode,
    maskMode: DisplayMasker.MaskMode,
    allowList: List<String> = emptyList(),
): String {
    if (maskMode == DisplayMasker.MaskMode.OFF || maskMode == DisplayMasker.MaskMode.WARN) {
        if (mode == SqlListDisplayMode.SINGLE_LINE) return entry.summary
        // KEYWORD_WRAP/FORMATTED 取完整还原 SQL（券01：摘要盖不到子句关键字）
        val full = entry.restoredSql
        return if (mode == SqlListDisplayMode.KEYWORD_WRAP) clipKeywordWrapSource(full) else full
    }
    // UI-001 修复：白名单实参贯穿列表显示链（此前默认 emptyList，白名单在列表面恒不生效）
    if (mode == SqlListDisplayMode.SINGLE_LINE) {
        val cacheKey = maskMode.name + "|" + allowList.joinToString(",")
        return entry.cachedMaskedSummary
            ?.takeIf { entry.cachedMaskedSummaryMode == cacheKey }
            ?: LogSqlEntry.summarizeSql(DisplayMasker.maskDisplay(entry.restoredSql, maskMode, allowList)).also {
                entry.cachedMaskedSummary = it
                entry.cachedMaskedSummaryMode = cacheKey
            }
    }
    val full = DisplayMasker.maskDisplay(entry.restoredSql, maskMode, allowList)
    return if (mode == SqlListDisplayMode.KEYWORD_WRAP) clipKeywordWrapSource(full) else full
}

/** 不包含任何主题属性的词法片段，可安全存于条目缓存；空白 token 同样保留。 */
data class SqlSemanticFragment(val text: String, val tokenType: IElementType)

/** 列表组件可直接 append 的当前 scheme 着色片段；仅为一次渲染的临时投影。 */
data class SqlTextFragment(val text: String, val attributes: SimpleTextAttributes)

/** 分词/分行只做一次；每次绘制用当前 scheme 将语义缓存投影为组件属性。 */
object SqlListFragmentRenderer {

    /** 原始 SQL 词法片段，保留每个字符及其 token 类型，不读取主题。 */
    fun semanticFragments(sql: String): List<SqlSemanticFragment> {
        if (sql.isEmpty()) return emptyList()
        val lexer = SqlLexer()
        lexer.start(sql, 0, sql.length, 0)
        val fragments = mutableListOf<SqlSemanticFragment>()
        while (true) {
            val type = lexer.tokenType ?: break
            val text = lexer.tokenText
            if (text.isNotEmpty()) fragments += SqlSemanticFragment(text, type)
            lexer.advance()
        }
        return fragments
    }

    /** 先按 mode 变换，再按片段里的换行拆分；末尾换行保留末尾空行。 */
    fun semanticLineFragments(sql: String, mode: SqlListDisplayMode): List<List<SqlSemanticFragment>> {
        val lines = mutableListOf<MutableList<SqlSemanticFragment>>()
        var current = mutableListOf<SqlSemanticFragment>()
        lines += current
        for (fragment in semanticFragments(prepareSqlForList(sql, mode))) {
            if (!fragment.text.contains('\n')) {
                current += fragment
                continue
            }
            fragment.text.split('\n').forEachIndexed { index, part ->
                if (index > 0) {
                    current = mutableListOf()
                    lines += current
                }
                if (part.isNotEmpty()) current += SqlSemanticFragment(part, fragment.tokenType)
            }
        }
        return lines
    }

    /** 当前 scheme 投影：不把颜色写回语义片段，下一次绘制可传另一套 scheme。 */
    fun projectFragments(
        fragments: List<SqlSemanticFragment>,
        scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme,
    ): List<SqlTextFragment> = projectFragments(fragments, attributesFor(scheme))

    /** 对已缓存语义行在当前 scheme 下投影，适合列表多行渲染入口。 */
    fun projectLineFragments(
        lines: List<List<SqlSemanticFragment>>,
        scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme,
    ): List<List<SqlTextFragment>> {
        val attributes = attributesFor(scheme)
        return lines.map { projectFragments(it, attributes) }
    }

    /** 旧单段渲染接口保留；新缓存应使用 semanticFragments + projectFragments。 */
    fun renderFragments(
        sql: String,
        scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme,
    ): List<SqlTextFragment> = projectFragments(semanticFragments(sql), scheme)

    /** 旧 attrOf 测试接口保留。 */
    fun renderFragments(sql: String, attrOf: (IElementType) -> SimpleTextAttributes?): List<SqlTextFragment> =
        projectFragments(semanticFragments(sql), attrOf)

    /** 旧多行渲染接口保留；新缓存应使用 semanticLineFragments + projectLineFragments。 */
    fun renderLineFragments(
        sql: String,
        mode: SqlListDisplayMode,
        scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme,
    ): List<List<SqlTextFragment>> = projectLineFragments(semanticLineFragments(sql, mode), scheme)

    /** 旧 attrOf 测试接口保留。 */
    fun renderLineFragments(
        sql: String,
        mode: SqlListDisplayMode,
        attrOf: (IElementType) -> SimpleTextAttributes?,
    ): List<List<SqlTextFragment>> = semanticLineFragments(sql, mode).map { projectFragments(it, attrOf) }

    private fun projectFragments(
        fragments: List<SqlSemanticFragment>,
        attrOf: (IElementType) -> SimpleTextAttributes?,
    ): List<SqlTextFragment> = fragments.map { fragment ->
        SqlTextFragment(fragment.text, attrOf(fragment.tokenType) ?: SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    private fun attributesFor(scheme: EditorColorsScheme): (IElementType) -> SimpleTextAttributes? {
        val byType = SqlListSyntaxHighlighter.ATTR_BY_TYPE.mapValues { (_, key) -> attrOf(scheme, key) }
        return { type -> byType[type] }
    }

    private fun attrOf(scheme: EditorColorsScheme, key: TextAttributesKey): SimpleTextAttributes? {
        val ta = scheme.getAttributes(key) ?: return null
        val fg = ta.foregroundColor ?: return null
        return SimpleTextAttributes(ta.fontType, fg)
    }
}
