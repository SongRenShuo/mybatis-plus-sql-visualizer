package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.UpdateStatement

/**
 * NEW-21 信创兼容性体检的**编排层**：一次输入 → 每方言一份渲染结果 + 差异化清单。
 *
 * ## 边界（立项册 §三：不要为它改渲染引擎）
 *
 * 本对象**只做编排**：逐方言调用既有渲染入口 [SqlGenerator.generate]（同一入口、同一模型，
 * 只换 `dialect` 参数），差异比较在本层做。渲染引擎一行未改。
 *
 * ## P-5 证伪结论（逐方言渲染是否需要 PSI/工程上下文）→ **不需要**
 *
 * [SqlGenerator] 是纯函数（其类 KDoc 原文：「本类为纯函数，不依赖 PSI / IDE，可独立单测」），
 * 输入是已构建好的语句模型 [SqlStatement]，import 面只有 `model` 包。PSI/工程上下文只需要在
 * **构建模型**那一步（预览链已经在做了），渲染与差异比较都是纯逻辑。
 * 故本对象做成纯函数，测试形态是**普通 JUnit**（无 BasePlatformTestCase）。
 *
 * ## 四档差异的事实源
 *
 *  - **保留字冲突**：查 [Dialect.reservedWords]（模型输入的标识符 → 精确判定；
 *    纯文本输入 → 结构化关键字白名单之外的裸词启发式判定，见 [identifiersIn]）；
 *  - **分页语法**：查 [Dialect.renderLimit] 形态 + [Dialect.limitRequiresOrderBy] +
 *    [Dialect.supportsDeleteLimit]；
 *  - **函数不存在**：查 [DialectFunctionCatalog]（**未收录的函数不判**——不拿猜测当结论）；
 *  - **隐式转换**：查模型的列类型 [cn.song.mybatisplus.visualizer.model.ColumnInfo.fieldType]
 *    —— 仅在「数值列 + 非数值字符串字面量」时可静态判定（P-6 结论见 [implicitConversionDifferences]）。
 */
object MultiDialectRenderPlan {

    /** 差异分类（对应立项册 §三 四档）。 */
    enum class Category {
        /** 标识符命中本方言保留字（未引用时语法错误）。 */
        RESERVED_WORD,

        /** 分页子句形态 / ORDER BY 强制 / DELETE LIMIT 支持面差异。 */
        PAGINATION,

        /** 函数在本方言不可用（仅报目录内已收录的函数）。 */
        FUNCTION,

        /** 隐式类型转换（数值列 vs 非数值字符串字面量）。 */
        IMPLICIT_CONVERSION,
    }

    /**
     * 输入来源。
     *
     *  - [Text]：只有 SQL 文本（例如用户手写/粘贴）——**没有模型就无法重渲染**，故每方言的
     *    `sql` 原样透传原文（如实标注，不伪造「渲染结果」），差异仍按文本证据判定；
     *  - [Model]：Wrapper/XML 预览已产出的语句模型 → 逐方言真渲染（分页/引用形态随方言变）。
     */
    sealed class Source {
        /** 纯 SQL 文本。 */
        data class Text(val sql: String) : Source()

        /** 已构建的语句模型（Wraper/XML 预览链产出）。 */
        data class Model(val statement: SqlStatement, val compact: Boolean = false) : Source()
    }

    /**
     * 单条差异。
     *
     * @param category 分类
     * @param messageKey 文案键（`compat.diff.*`，formatter 翻译；本对象不出成品文案）
     * @param args   文案实参
     */
    data class Difference(val category: Category, val messageKey: String, val args: List<String>)

    /**
     * 单方言结果行。
     *
     * @param dialectName 方言显示名
     * @param sql         本方言渲染产物；[Source.Text] 输入时为原文（见 [Source.Text]）
     * @param differences 本方言相对基线的差异
     */
    data class DialectRow(val dialectName: String, val sql: String, val differences: List<Difference>)

    /**
     * 编排结果。
     *
     * @param rows           每方言一行（顺序 = 传入方言顺序）
     * @param notes          全局说明（**bundle 文案键**，由呈现层翻译；如「纯文本输入无列类型证据」）
     * @param elapsedMillis  编排总耗时（含 N 次渲染；判据要求 < 200ms）
     */
    data class Result(val rows: List<DialectRow>, val notes: List<String>, val elapsedMillis: Long) {
        /** 全部差异条数（报告摘要用）。 */
        val differenceCount: Int get() = rows.sumOf { it.differences.size }
    }

    /**
     * 编排。
     *
     * @param source        输入（文本或模型）
     * @param dialects      参与体检的方言集合（默认全部 14 个注册方言）
     * @param sourceDialect 基线方言（差异以它为参照；null → 取 [dialects] 首个）
     */
    fun plan(
        source: Source,
        dialects: List<Dialect> = DialectRegistry.dialects,
        sourceDialect: Dialect? = null,
    ): Result {
        val started = System.nanoTime()
        val baseline = sourceDialect ?: dialects.firstOrNull() ?: MySqlDialect
        val notes = mutableListOf<String>()

        val identifiers: List<String>
        val functions: List<String>
        val pagination: PaginationFacts
        val implicitHits: List<ImplicitHit>
        val renderedSqls: (Dialect) -> String

        when (source) {
            is Source.Text -> {
                identifiers = identifiersIn(source.sql)
                functions = functionsIn(source.sql)
                pagination = paginationFromText(source.sql)
                implicitHits = emptyList()
                notes += "compat.note.textInputNoTypes"
                renderedSqls = { source.sql }
            }

            is Source.Model -> {
                identifiers = identifiersOf(source.statement)
                functions = functionsIn(renderOnce(source, baseline))
                pagination = paginationOf(source.statement)
                implicitHits = implicitHitsOf(source.statement)
                renderedSqls = { dialect -> renderOnce(source, dialect) }
                if (implicitHits.isEmpty()) notes += "compat.note.implicitNotApplicable"
            }
        }

        val baselinePagination = paginationSignature(baseline, pagination)
        val rows = dialects.map { dialect ->
            val sql = renderedSqls(dialect)
            val differences = mutableListOf<Difference>()
            differences += reservedWordDifferences(dialect, identifiers)
            differences += paginationDifferences(dialect, pagination, baselinePagination)
            differences += functionDifferences(dialect, functions)
            differences += implicitConversionDifferences(dialect, implicitHits)
            DialectRow(dialect.name, sql, differences.distinct())
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return Result(rows, notes, elapsed)
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /** 逐方言调用既有渲染入口（引擎零改动）。 */
    private fun renderOnce(source: Source.Model, dialect: Dialect): String = runCatching {
        SqlGenerator.generate(source.statement, source.compact, dialect)
    }.getOrElse { "/* render failed: ${it.message?.take(120).orEmpty()} */" }

    // ------------------------------------------------------------------
    // 档一：保留字冲突
    // ------------------------------------------------------------------

    private fun reservedWordDifferences(dialect: Dialect, identifiers: List<String>): List<Difference> =
        identifiers
            .filter { it.lowercase() in dialect.reservedWords }
            .distinct()
            .map {
                Difference(
                    Category.RESERVED_WORD,
                    "compat.diff.reservedWord",
                    listOf(it, dialect.name, dialect.quoteIdentifier(it)),
                )
            }

    /**
     * 从 SQL **文本**抽候选标识符（纯文本输入路径）。
     *
     * 口径：先屏蔽字符串字面量与引号标识符（已引用的标识符不会撞保留字），再取裸词，
     * 排除 [STRUCTURAL_WORDS]（结构关键字——`ORDER`/`LIMIT` 在结构位上出现是合法用法，按保留字
     * 报会制造大量噪音）与函数名（后随 `(`）。
     *
     * **已知漏报面**：把结构关键字当列名裸用（`SELECT order FROM t`）会被白名单连带排除。
     * 模型输入走 [identifiersOf]（精确），不受此限——本启发式只服务「只有文本」的降级路径。
     */
    internal fun identifiersIn(sql: String): List<String> {
        val masked = maskLiterals(sql)
        val words = mutableListOf<String>()
        for (m in WORD.findAll(masked)) {
            val word = m.value
            if (word.lowercase() in STRUCTURAL_WORDS) continue
            if (isFollowedByParen(masked, m.range.last + 1)) continue
            words += word
        }
        return words.distinct()
    }

    /** 从**语句模型**抽标识符（精确路径：模型自知哪些是表名/列名）。 */
    internal fun identifiersOf(statement: SqlStatement): List<String> {
        val out = LinkedHashSet<String>()
        fun addColumn(column: cn.song.mybatisplus.visualizer.model.ColumnRef?) {
            column ?: return
            out += column.columnName
            column.alias?.let { out += it }
        }
        fun addCondition(condition: Condition?) {
            when (condition) {
                is Condition.Simple -> addColumn(condition.column)
                is Condition.Unary -> addColumn(condition.column)
                is Condition.In -> addColumn(condition.column)
                is Condition.Between -> addColumn(condition.column)
                is Condition.Like -> addColumn(condition.column)
                is Condition.ColumnComparison -> {
                    addColumn(condition.left)
                    addColumn(condition.right)
                }
                is Condition.Group -> condition.children.forEach { addCondition(it) }
                is Condition.Raw -> Unit
                null -> Unit
            }
        }
        fun addJoin(join: JoinClause) {
            if (join.rawSql == null) out += join.tableName
            join.onConditions.forEach { addCondition(it) }
        }

        out += statement.table.tableName
        statement.table.columns.forEach { out += it.columnName }
        statement.conditions.forEach { addCondition(it) }
        when (statement) {
            is SelectStatement -> {
                statement.selectColumns.forEach { addColumn(it) }
                statement.joins.forEach { addJoin(it) }
                statement.groupBy.forEach { addColumn(it) }
                addCondition(statement.having)
                statement.orderBy.forEach { addColumn(it.column) }
            }
            is UpdateStatement -> {
                statement.joins.forEach { addJoin(it) }
                statement.sets.forEach { set -> set.column?.let { addColumn(it) } }
            }
            is DeleteStatement -> statement.joins.forEach { addJoin(it) }
        }
        return out.toList()
    }

    // ------------------------------------------------------------------
    // 档二：分页语法
    // ------------------------------------------------------------------

    /**
     * 分页事实。
     *
     * @param limit       分页子句（null = 源里没有分页）
     * @param hasOrderBy  源里是否已有 ORDER BY（`limitRequiresOrderBy` 判定用）
     * @param deleteLimit 源里是否是「DELETE ... LIMIT」（[Dialect.supportsDeleteLimit] 判定用）
     */
    internal data class PaginationFacts(
        val limit: LimitClause?,
        val hasOrderBy: Boolean,
        val deleteLimit: Boolean,
    )

    private fun paginationOf(statement: SqlStatement): PaginationFacts = when (statement) {
        // UPDATE 模型无分页与排序字段（见 UpdateStatement 定义）→ 无分页事实
        is SelectStatement -> PaginationFacts(statement.limit, statement.orderBy.isNotEmpty(), false)
        is UpdateStatement -> PaginationFacts(null, false, false)
        is DeleteStatement -> PaginationFacts(statement.limit, false, statement.limit != null)
    }

    /** 纯文本路径：从原文取分页形态与 ORDER BY / DELETE 痕迹（结构位，字面量已屏蔽）。 */
    private fun paginationFromText(sql: String): PaginationFacts {
        val masked = maskLiterals(sql)
        val limit = LIMIT_NUMBER.find(masked)?.let { LimitClause(count = SqlValue.Literal(it.groupValues[1])) }
            ?: OFFSET_FETCH.find(masked)?.let { LimitClause(count = SqlValue.Literal(it.groupValues[1])) }
        val hasOrderBy = ORDER_BY.containsMatchIn(masked)
        val deleteLimit = DELETE_HEAD.containsMatchIn(masked.trim()) && limit != null
        return PaginationFacts(limit, hasOrderBy, deleteLimit)
    }

    /** 基线方言的分页形态签名（差异参照）。 */
    private fun paginationSignature(dialect: Dialect, facts: PaginationFacts): String? =
        facts.limit?.let { runCatching { dialect.renderLimit(it) }.getOrNull() }

    private fun paginationDifferences(
        dialect: Dialect,
        facts: PaginationFacts,
        baselineSignature: String?,
    ): List<Difference> {
        val limit = facts.limit ?: return emptyList()
        val out = mutableListOf<Difference>()
        val form = runCatching { dialect.renderLimit(limit) }.getOrNull()
        if (form != null && form != baselineSignature) {
            out += Difference(
                Category.PAGINATION,
                "compat.diff.paginationForm",
                listOf(dialect.name, form, baselineSignature ?: "-"),
            )
        }
        if (dialect.limitRequiresOrderBy && !facts.hasOrderBy) {
            out += Difference(
                Category.PAGINATION,
                "compat.diff.paginationRequiresOrderBy",
                listOf(dialect.name),
            )
        }
        if (facts.deleteLimit && !dialect.supportsDeleteLimit) {
            out += Difference(
                Category.PAGINATION,
                "compat.diff.deleteLimitDropped",
                listOf(dialect.name),
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    // 档三：函数不存在
    // ------------------------------------------------------------------

    /** 文本里的函数调用名（大写去重）；字面量已屏蔽，结构关键字后随 `(` 的不算函数。 */
    internal fun functionsIn(sql: String): List<String> {
        val masked = maskLiterals(sql)
        return FUNCTION_CALL.findAll(masked)
            .map { it.groupValues[1].uppercase() }
            .filter { it.lowercase() !in STRUCTURAL_WORDS }
            .distinct()
            .toList()
    }

    private fun functionDifferences(dialect: Dialect, functions: List<String>): List<Difference> = functions
        .mapNotNull { function ->
            // dialectsFor 返回 null = 目录未收录 → 不判（宁可少报）
            val supported = DialectFunctionCatalog.dialectsFor(function) ?: return@mapNotNull null
            if (dialect.name in supported) return@mapNotNull null
            Difference(Category.FUNCTION, "compat.diff.functionMissing", listOf(function, dialect.name))
        }

    // ------------------------------------------------------------------
    // 档四：隐式转换（P-6）
    // ------------------------------------------------------------------

    /**
     * 隐式转换命中：数值类型列 + **非数值字符串字面量**谓词值。
     *
     * P-6 结论：隐式转换**不是普遍可静态判定**（需要列类型、字符集/排序规则、目标库的转换规则，
     * 字符集不匹配这类又要库级元数据），但**本插件已有的模型里有列类型**
     * （[cn.song.mybatisplus.visualizer.model.ColumnInfo.fieldType]，来自实体注解/字段声明），
     * 于是「数值列 vs 明显非数值的字符串字面量」这一子集是可静态判定的——各库行为**不同**
     * （MySQL 族静默转 0、PG/Oracle 直接报 invalid input syntax），迁移时是真风险。
     * 纯文本输入没有类型证据 → 本档不判（[Result.notes] 如实标注）。
     */
    internal data class ImplicitHit(val column: String, val literal: String)

    private fun implicitHitsOf(statement: SqlStatement): List<ImplicitHit> {
        val numericColumns = statement.table.columns
            .filter { isNumericType(it.fieldType) }
            .map { it.columnName.lowercase() }
            .toSet()
        if (numericColumns.isEmpty()) return emptyList()
        val hits = mutableListOf<ImplicitHit>()
        fun walk(condition: Condition?) {
            when (condition) {
                is Condition.Simple -> {
                    if (condition.column.columnName.lowercase() in numericColumns) {
                        nonNumericLiteral(condition.value)?.let { hits += ImplicitHit(condition.column.columnName, it) }
                    }
                }
                is Condition.In -> if (condition.column.columnName.lowercase() in numericColumns) {
                    condition.values.forEach { value ->
                        nonNumericLiteral(value)?.let { hits += ImplicitHit(condition.column.columnName, it) }
                    }
                }
                is Condition.Between -> if (condition.column.columnName.lowercase() in numericColumns) {
                    listOf(condition.lo, condition.hi).forEach { value ->
                        nonNumericLiteral(value)?.let { hits += ImplicitHit(condition.column.columnName, it) }
                    }
                }
                is Condition.Group -> condition.children.forEach { walk(it) }
                else -> Unit
            }
        }
        statement.conditions.forEach { walk(it) }
        return hits
    }

    /** 数值列 + 字符串字面量且**不是数值串** → 隐式转换；返回该字面量原文（null = 不命中）。 */
    private fun nonNumericLiteral(value: SqlValue): String? {
        val literal = value as? SqlValue.Literal ?: return null
        if (!literal.raw.trim().startsWith("'")) return null
        val body = literal.unquoted.trim()
        return if (NUMERIC_TEXT.matches(body)) null else literal.raw.trim()
    }

    private fun isNumericType(fieldType: String?): Boolean {
        val simple = fieldType?.substringAfterLast('.')?.lowercase() ?: return false
        return simple in NUMERIC_TYPES
    }

    /** 隐式转换的方言行为（PG/Oracle 报错，MySQL 族静默转 0，其余按实现）。 */
    private fun implicitBehaviorKey(dialect: Dialect): String = when (dialect.name) {
        in PG_ORACLE_NAMES -> "compat.implicit.error"
        in MYSQL_FAMILY_NAMES -> "compat.implicit.coerce"
        else -> "compat.implicit.vendor"
    }

    private fun implicitConversionDifferences(dialect: Dialect, hits: List<ImplicitHit>): List<Difference> =
        hits.map { hit ->
            Difference(
                Category.IMPLICIT_CONVERSION,
                "compat.diff.implicitConversion",
                listOf(dialect.name, hit.column, hit.literal, implicitBehaviorKey(dialect)),
            )
        }

    // ------------------------------------------------------------------
    // 词法辅助（与 DialectCapabilities.maskLiterals 同口径的私有副本——两处模块边界不同）
    // ------------------------------------------------------------------

    /** 屏蔽字符串字面量与引号标识符（内容替换为空格，保持偏移稳定）。 */
    private fun maskLiterals(sql: String): String {
        val sb = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            val close = when (c) {
                '\'' -> '\''
                '"' -> '"'
                '`' -> '`'
                '[' -> ']'
                else -> null
            }
            if (close == null) {
                sb.append(c)
                i++
                continue
            }
            sb.append(' ')
            i++
            while (i < sql.length) {
                if (close != '[' && sql[i] == close && i + 1 < sql.length && sql[i + 1] == close) {
                    sb.append("  ")
                    i += 2
                    continue
                }
                if (sql[i] == close) {
                    sb.append(' ')
                    i++
                    break
                }
                sb.append(' ')
                i++
            }
        }
        return sb.toString()
    }

    private fun isFollowedByParen(text: String, from: Int): Boolean {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i < text.length && text[i] == '('
    }

    private val WORD = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val FUNCTION_CALL = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
    private val LIMIT_NUMBER = Regex("\\bLIMIT\\s+(\\d+)", RegexOption.IGNORE_CASE)
    private val OFFSET_FETCH = Regex("\\bFETCH\\s+(?:FIRST|NEXT)\\s+(\\d+)", RegexOption.IGNORE_CASE)
    private val ORDER_BY = Regex("\\bORDER\\s+BY\\b", RegexOption.IGNORE_CASE)
    private val DELETE_HEAD = Regex("^\\s*DELETE\\b", RegexOption.IGNORE_CASE)
    private val NUMERIC_TEXT = Regex("[+-]?\\d+(\\.\\d+)?")

    /** 数值类型简单名（小写；`fieldType` 全限定名取末段后比对）。 */
    private val NUMERIC_TYPES = setOf(
        "byte", "short", "int", "integer", "long", "float", "double",
        "biginteger", "bigdecimal", "number",
    )

    /** MySQL 族（静默转换一侧）。 */
    private val MYSQL_FAMILY_NAMES: Set<String> = setOf(
        MySqlDialect.name, MariaDbDialect.name, OceanBaseDialect.name,
    )

    /** 报错一侧（PG / Oracle 兼容面）。 */
    private val PG_ORACLE_NAMES: Set<String> = setOf(
        PostgreSQLDialect.name, KingBaseDialect.name, OpenGaussDialect.name, GaussDbDialect.name,
        HighGoDialect.name, VastbaseDialect.name, OracleDialect.name,
    )

    /**
     * 结构关键字白名单（纯文本路径的裸词过滤，见 [identifiersIn]）。
     *
     * 收录的是「出现在结构位上属合法用法、按保留字报纯属噪音」的关键字。刻意**不**收录
     * `KEY`/`VALUE`/`TYPE`/`LEVEL`/`STATUS` 这类高频列名——它们在真实建表里就是列名，
     * 撞保留字正是本体检要报的。
     */
    private val STRUCTURAL_WORDS: Set<String> = setOf(
        "select", "from", "where", "and", "or", "not", "null", "is", "in", "like", "between",
        "join", "left", "right", "inner", "outer", "cross", "full", "on", "as", "by", "group",
        "having", "order", "limit", "offset", "fetch", "first", "next", "rows", "row", "only",
        "distinct", "insert", "into", "values", "update", "set", "delete", "asc", "desc",
        "union", "all", "exists", "case", "when", "then", "else", "end", "with", "cast",
        "database", "table", "index", "default", "primary", "foreign", "references",
    )
}
