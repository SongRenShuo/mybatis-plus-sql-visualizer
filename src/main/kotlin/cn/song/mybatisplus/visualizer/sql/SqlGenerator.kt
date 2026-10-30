package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.FillClause
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement

/**
 * SQL 生成结果：SQL 文本 + 未解析参数来源（F-02 参数对照表基础）。
 *
 * @param sql              生成的 SQL（未解析参数渲染为 `?`）
 * @param unresolvedParams 按出现顺序的未解析参数来源（变量名/表达式文本）
 * @param paramHints       与 [unresolvedParams] 并行的回填提示（M13）：引号与 LIKE 百分号，
 *                         供面板按用户输入值回填渲染；无上下文信息时为空
 */
data class GeneratedSql(
    val sql: String,
    val unresolvedParams: List<String>,
    val paramHints: List<ParamHint> = emptyList(),
)

/**
 * SQL 生成器（M1-04 / M2-03 / M4-04）：把 [SqlStatement] 模型渲染为 SQL 文本。
 *
 * 支持语句类型：SELECT（含 MPJ 联表）、UPDATE、DELETE。
 *
 * 输出约定（与 docs/项目介绍.md 联表示例风格对齐）：
 *  - 关键字大写
 *  - 默认多行模式：每个子句独占一行，WHERE 后续条件以 4 空格缩进 + `AND` 开头
 *  - 压缩模式（compact）：子句间以空格连接，WHERE 条件内联（F-03 格式化视图切换）
 *  - 参数：psi 层已求值的字面量直接渲染；运行时值渲染为 `?` 并收集来源（F-02）
 *
 * 本类为纯函数，不依赖 PSI / IDE，可独立单测。
 */
object SqlGenerator {

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 生成 SELECT SQL（多行模式，兼容旧调用）。
     */
    fun generate(statement: SelectStatement): String = generate(statement as SqlStatement, compact = false)

    /**
     * 生成 SQL（多行模式，任意语句类型）。
     */
    fun generate(statement: SqlStatement): String = generate(statement, compact = false)

    /**
     * 生成 SQL（按语句类型分发）。
     *
     * @param statement          语句模型（SELECT / UPDATE / DELETE）
     * @param compact            true=压缩模式，false=多行缩进模式
     * @param dialect            数据库方言（影响分页子句，默认 MySQL）
     * @param enabledConditionIds 动态条件启用集合（M12）；null（默认）→ 按 toggle.defaultEnabled
     * @return SQL 文本
     */
    fun generate(
        statement: SqlStatement,
        compact: Boolean,
        dialect: Dialect = MySqlDialect,
        enabledConditionIds: Set<String>? = null,
    ): String = generateInternal(statement, compact, null, null, dialect, enabledConditionIds)

    /**
     * 生成 SQL 并收集未解析参数来源与回填提示（F-02 参数替换与对照表，M13 hints）。
     *
     * @param statement          语句模型
     * @param compact            true=压缩模式，false=多行缩进模式
     * @param dialect            数据库方言（影响分页子句）
     * @param enabledConditionIds 动态条件启用集合（M12）：仅渲染 id 在该集合内的动态条件；
     *                             null（默认）→ 按各条件 toggle.defaultEnabled 渲染（与 M11 基线一致）
     * @return SQL、参数来源列表与并行的 [ParamHint] 回填提示
     */
    fun generateWithParams(
        statement: SqlStatement,
        compact: Boolean = false,
        dialect: Dialect = MySqlDialect,
        enabledConditionIds: Set<String>? = null,
    ): GeneratedSql {
        val params = mutableListOf<String>()
        val hints = mutableListOf<ParamHint>()
        val sql = generateInternal(statement, compact, params, hints, dialect, enabledConditionIds)
        return GeneratedSql(sql, params, hints)
    }

    /**
     * 渲染单个条件为文本（占位符渲染为 `?`，不收集参数）。
     * 供动态条件复选框标签 / 条件视图复用（M12）。
     */
    internal fun renderConditionText(condition: Condition): String = renderCondition(condition)

    // ------------------------------------------------------------------
    // 内部渲染
    // ------------------------------------------------------------------

    private fun generateInternal(
        statement: SqlStatement,
        compact: Boolean,
        paramSink: MutableList<String>?,
        hintSink: MutableList<ParamHint>?,
        dialect: Dialect,
        enabledConditionIds: Set<String>?,
    ): String = when (statement) {
        is SelectStatement -> generateSelect(statement, compact, paramSink, hintSink, dialect, enabledConditionIds)
        is UpdateStatement -> generateUpdate(statement, compact, paramSink, hintSink, dialect, enabledConditionIds)
        is DeleteStatement -> generateDelete(statement, compact, paramSink, hintSink, dialect, enabledConditionIds)
    }

    /** SELECT 渲染（含 MPJ 主表别名与联表子句）。 */
    private fun generateSelect(
        statement: SelectStatement,
        compact: Boolean,
        paramSink: MutableList<String>?,
        hintSink: MutableList<ParamHint>?,
        dialect: Dialect,
        enabledConditionIds: Set<String>?,
    ): String {
        val nl = if (compact) " " else "\n"

        // SELECT 列：未显式指定时展开为表全部列（联表场景同主表列）
        // T-M23.4：展开列改为 tableAlias + columnName 构造（原 `prefix + it` 拼进 columnName，
        // 关键字引用无法拆分别名前缀；顺带修复 quoteHint 按 columnName 匹配的隐患）
        val selectColumns = if (statement.selectColumns.isEmpty()) {
            statement.table.allColumnNames.map { ColumnRef(tableAlias = statement.tableAlias, columnName = it) }
        } else {
            statement.selectColumns
        }
        // first 片段（官方 API）：置于 SELECT 之前（如 `/* hint */ SELECT ...`）
        // T-UpdateSetSqlConcat：片段里的 `?` 按序登记来源（SELECT 之前，与文本顺序一致）
        statement.rawFirstParams.forEach { source ->
            paramSink?.add(source)
            hintSink?.add(ParamHint(quote = null))
        }
        val sb = StringBuilder(statement.rawFirst?.let { "$it " } ?: "")
        sb.append("SELECT ")
        if (statement.distinct) sb.append("DISTINCT ")
        sb.append(selectColumns.joinToString(", ") { renderColumnRef(it, dialect, paramSink, hintSink) })

        // FROM 主表（可带别名）；T-M21-07：from(子查询主表) 形态 → FROM (子查询SQL) 外层别名
        // （主表实体表名不再直接出现——在子查询内部）
        statement.fromSubQuery?.let { (subSql, outerAlias) ->
            sb.append(nl).append("FROM (").append(subSql).append(") ").append(outerAlias)
        } ?: run {
            sb.append(nl).append("FROM ").append(renderTableName(statement.table.tableName, dialect))
            statement.tableAlias?.let { sb.append(" ").append(it) }
        }

        // JOIN 子句
        statement.joins.forEach { join ->
            sb.append(nl).append(renderJoin(join, compact, paramSink, hintSink, enabledConditionIds, dialect))
        }

        // WHERE 条件
        val whereSql = renderConditionList(
            statement.conditions, Connector.AND, indent = !compact, paramSink, enabledConditionIds, hintSink, statement.table, dialect,
        )
        if (whereSql.isNotEmpty()) {
            sb.append(nl).append("WHERE ").append(whereSql)
        }

        // GROUP BY
        if (statement.groupBy.isNotEmpty()) {
            sb.append(nl).append("GROUP BY ")
                .append(statement.groupBy.joinToString(", ") { renderQualified(it, dialect) })
        }

        // HAVING
        // T-M29.1：HAVING 子条件不在面板展示（无开关行）——按各自 defaultEnabled 渲染，
        // 不按 enabledConditionIds 过滤（否则面板流程恒丢失 HAVING，M19.5 预存缺陷）
        statement.having?.let { h ->
            if (h.isConditionVisible(enabledConditionIds)) {
                val havingSql = renderCondition(h, paramSink, hintSink, statement.table, null, dialect)
                if (havingSql.isNotEmpty()) sb.append(nl).append("HAVING ").append(havingSql)
            }
        }

        // ORDER BY
        if (statement.orderBy.isNotEmpty()) {
            sb.append(nl).append("ORDER BY ")
                .append(statement.orderBy.joinToString(", ") {
                    "${renderQualified(it.column, dialect)} ${if (it.asc) "ASC" else "DESC"}"
                })
        } else if (statement.limit != null && dialect.limitRequiresOrderBy) {
            // T-SqlServerLimitOrder（2026-10-30 真机）：SQL Server 的 OFFSET/FETCH 必须跟在 ORDER BY
            // 之后，否则 `Incorrect syntax near '0'`。无排序 + 分页时补语义中性的
            // `ORDER BY (SELECT NULL)`（T-SQL 社区标准写法 = 不指定顺序），保证产物可执行。
            sb.append(nl).append("ORDER BY (SELECT NULL)")
        }

        // around 片段（官方 API，T-M21-09）：渲染于 ORDER BY 之后、LIMIT 之前
        // （MP 内部 sqlSegments 中 LAST 段位于 ORDER BY 之后、分页 LIMIT 之前，位置对齐）
        statement.rawAround?.let {
            // T-UpdateSetSqlConcat：片段里的 `?` 按序登记来源（位于 ORDER BY 之后）
            statement.rawAroundParams.forEach { source ->
                paramSink?.add(source)
                hintSink?.add(ParamHint(quote = null))
            }
            sb.append(nl).append(it)
        }

        // LIMIT（分页子句按方言渲染；T-M29-03：分页运行时值占位符参与参数收集——面板回填）
        statement.limit?.let { limit ->
            collectLimitValue(limit.offset, paramSink, hintSink)
            collectLimitValue(limit.count, paramSink, hintSink)
            sb.append(nl).append(dialect.renderLimit(limit))
        }

        // UNION / UNION ALL 子查询（MPJ 官方 API，M15 增补）
        statement.unions.forEach { union ->
            // T-UpdateSetSqlConcat：子语句里的 `?` 按序登记来源（子语句整段渲染于此处）
            union.params.forEach { source ->
                paramSink?.add(source)
                hintSink?.add(ParamHint(quote = null))
            }
            sb.append(nl).append(union.keyword).append(" (").append(union.sql).append(")")
        }

        // comment 片段（官方 API）：追加到语句末尾（`/* comment */`）
        statement.comment?.let { sb.append(nl).append("/* $it */") }

        return sb.toString()
    }

    /**
     * UPDATE 渲染：UPDATE 表 [别名] [JOIN ...] SET 列 = 值, ... WHERE ...
     *
     * T-M21-08：UpdateJoinWrapper 联表更新形态——tableAlias 非空时 `UPDATE 表名 别名`，
     * joins 逐行渲染于 FROM 之后、SET 之前（复用 [renderJoin]）：
     * `UPDATE user t LEFT JOIN address t1 ON (t1.user_id = t.id) SET t.name = ?, t1.tel = ? WHERE ...`。
     */
    private fun generateUpdate(
        statement: UpdateStatement,
        compact: Boolean,
        paramSink: MutableList<String>?,
        hintSink: MutableList<ParamHint>?,
        dialect: Dialect,
        enabledConditionIds: Set<String>?,
    ): String {
        val nl = if (compact) " " else "\n"
        val sb = StringBuilder("UPDATE ").append(renderTableName(statement.table.tableName, dialect))
        statement.tableAlias?.let { sb.append(" ").append(it) }
        statement.joins.forEach { join ->
            sb.append(nl).append(renderJoin(join, compact, paramSink, hintSink, enabledConditionIds, dialect))
        }

        if (statement.sets.isNotEmpty()) {
            sb.append(nl).append("SET ")
            sb.append(statement.sets.joinToString(", ") { set ->
                when {
                    set.isRaw -> {
                        // T-UpdateSetSqlConcat：rawSql 里的 `?` 按序登记来源——不登记会让面板把
                        // 用户的 WHERE 值填到 SET 的占位符上（首例即 setSql 拼接形态）
                        set.rawParams.forEach { source ->
                            paramSink?.add(source)
                            hintSink?.add(ParamHint(quote = null))
                        }
                        set.rawSql.orEmpty()
                    }
                    set.column != null -> "${renderQualified(set.column, dialect)} = ${renderValue(
                        set.value ?: SqlValue.Placeholder("?"), paramSink, hintSink, quoteHint(statement.table, set.column),
                    )}"
                    else -> ""
                }
            })
        }

        appendWhere(sb, statement.conditions, nl, compact, paramSink, hintSink, enabledConditionIds, statement.table, dialect)
        // 非 limit 尾缀透传（U05，T-M15-04）：`last("AND (last_total - n >= 0)")` 追加到语句末尾
        statement.rawTail?.let {
            // T-UpdateSetSqlConcat：尾缀片段里的 `?` 按序登记来源（与文本顺序一致——WHERE 之后）
            statement.rawTailParams.forEach { source ->
                paramSink?.add(source)
                hintSink?.add(ParamHint(quote = null))
            }
            sb.append(nl).append(it)
        }
        return sb.toString()
    }

    /**
     * DELETE 渲染：DELETE FROM 表 WHERE ...；T-M21-08 DeleteJoinWrapper 多表删除形态：
     * `delete(Class...)/delete(String...)/deleteAll()` 的删除目标列表非空时渲染
     * `DELETE t, t1 FROM 表名 t`（主表别名拼在 FROM 后）+ joins + WHERE——
     * `DELETE t,t1 FROM user t LEFT JOIN address t1 ON (t1.user_id = t.id) WHERE (t.id = ?)`。
     * deleteTargets 为空（单表删除）走既有 `DELETE FROM 表名` 形态，回归不变。
     *
     * O-8（缺陷 C）：`last("LIMIT n")` 尾段渲染于语句末尾（MP 语义 MySQL `DELETE ... LIMIT n`）——
     * 仅 [Dialect.supportsDeleteLimit] 为 true 的方言（MySQL 语法族）输出，
     * PostgreSQL/Oracle 等不支持的方言静默省略（与分页子句方言策略一致）。
     */
    private fun generateDelete(
        statement: DeleteStatement,
        compact: Boolean,
        paramSink: MutableList<String>?,
        hintSink: MutableList<ParamHint>?,
        dialect: Dialect,
        enabledConditionIds: Set<String>?,
    ): String {
        val nl = if (compact) " " else "\n"
        val sb = StringBuilder()
        if (statement.deleteTargets.isEmpty()) {
            sb.append("DELETE FROM ").append(renderTableName(statement.table.tableName, dialect))
        } else {
            sb.append("DELETE ").append(statement.deleteTargets.joinToString(", "))
            sb.append(" FROM ").append(renderTableName(statement.table.tableName, dialect))
            statement.tableAlias?.let { sb.append(" ").append(it) }
        }
        statement.joins.forEach { join ->
            sb.append(nl).append(renderJoin(join, compact, paramSink, hintSink, enabledConditionIds, dialect))
        }
        appendWhere(sb, statement.conditions, nl, compact, paramSink, hintSink, enabledConditionIds, statement.table, dialect)
        statement.limit?.takeIf { dialect.supportsDeleteLimit }?.let { limit ->
            sb.append(nl).append(dialect.renderLimit(limit))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // T-MpjFill：fill 附加 SELECT 生成（MPJ 1.5.1+）
    // ------------------------------------------------------------------

    /**
     * 生成 fill 附加 SELECT SQL（MPJ 1.5.1+，T-MpjFill）。
     *
     * 渲染形态：
     * ```
     * SELECT <列> FROM <表> <别名> WHERE <tagField> IN (?)[ LIMIT <n>]
     * ```
     *
     * IN 列表为主查询结果集关联键，静态预览无法获取，统一渲染 `IN (?)` 占位符（与面板参数对照表语义一致）。
     *
     * @param fillClause  fill 子句模型
     * @param dialect     数据库方言（影响标识符引用与 LIMIT 形态，默认 MySQL）
     * @return fill 附加 SELECT SQL（无末尾分号）
     */
    fun generateFill(fillClause: FillClause, dialect: Dialect = MySqlDialect): String {
        val sb = StringBuilder("SELECT ")
        sb.append(fillClause.selectColumns.joinToString(", ") { renderColumnRef(it, dialect) })
        sb.append(" FROM ").append(renderTableName(fillClause.childTable.tableName, dialect))
        sb.append(" ").append(fillClause.childAlias)
        sb.append(" WHERE ").append(
            renderQualified(ColumnRef(tableAlias = fillClause.childAlias, columnName = fillClause.tagField), dialect),
        )
        sb.append(" IN (?)")
        fillClause.limit?.let { sb.append(" LIMIT ").append(it) }
        return sb.toString()
    }

    /** 追加 WHERE 子句（无条件时不输出）。 */
    private fun appendWhere(
        sb: StringBuilder,
        conditions: List<Condition>,
        nl: String,
        compact: Boolean,
        paramSink: MutableList<String>?,
        hintSink: MutableList<ParamHint>?,
        enabledConditionIds: Set<String>?,
        table: TableInfo,
        dialect: Dialect,
    ) {
        val whereSql = renderConditionList(conditions, Connector.AND, indent = !compact, paramSink, enabledConditionIds, hintSink, table, dialect)
        if (whereSql.isNotEmpty()) {
            sb.append(nl).append("WHERE ").append(whereSql)
        }
    }

    /** JOIN 子句渲染：`INNER JOIN 表 别名 ON 条件1 AND 条件2`；rawSql/customKeyword 形态原样透传（M15-P0）。 */
    private fun renderJoin(
        join: JoinClause,
        compact: Boolean,
        paramSink: MutableList<String>?,
        hintSink: MutableList<ParamHint>?,
        enabledConditionIds: Set<String>?,
        dialect: Dialect,
    ): String {
        val typeName = join.customKeyword ?: when (join.type) {
            cn.song.mybatisplus.visualizer.model.JoinType.INNER -> "INNER JOIN"
            cn.song.mybatisplus.visualizer.model.JoinType.LEFT -> "LEFT JOIN"
            cn.song.mybatisplus.visualizer.model.JoinType.RIGHT -> "RIGHT JOIN"
            cn.song.mybatisplus.visualizer.model.JoinType.FULL -> "FULL JOIN"
        }
        // 原生 join 子句（leftJoin("address t1 on t1.user_id = t.id", args)）：整段原样
        join.rawSql?.let { raw ->
            // T-UpdateSetSqlConcat：join 片段里的 `?` 按序登记（渲染位置在 SET/WHERE 之前）
            join.rawParams.forEach { source ->
                paramSink?.add(source)
                hintSink?.add(ParamHint(quote = null))
            }
            return "$typeName $raw"
        }
        val onPart = if (join.onConditions.isEmpty()) {
            ""
        } else {
            " ON " + renderConditionList(join.onConditions, Connector.AND, indent = false, paramSink, enabledConditionIds, hintSink, null, dialect)
        }
        return "$typeName ${renderTableName(join.tableName, dialect)} ${join.alias}$onPart"
    }

    // ------------------------------------------------------------------
    // 条件渲染（SELECT/UPDATE/DELETE 共用）
    // ------------------------------------------------------------------

    /**
     * 渲染一组条件。
     *
     * @param indent   true=顶层多条件换行缩进（如 `    AND xxx`），false=压缩连接
     * @param paramSink 参数收集器（可为 null）
     * @param dialect  方言（T-M23.4 关键字标识符引用；默认 MySQL）
     */
    private fun renderConditionList(
        conditions: List<Condition>,
        connector: Connector,
        indent: Boolean,
        paramSink: MutableList<String>?,
        enabledIds: Set<String>? = null,
        hintSink: MutableList<ParamHint>? = null,
        table: TableInfo? = null,
        dialect: Dialect = MySqlDialect,
    ): String {
        // 动态条件按启用集合/defaultEnabled 过滤；组内全空的 Group 渲染为空字符串后剔除
        val rendered = conditions
            .filter { it.isConditionVisible(enabledIds) }
            .map { renderCondition(it, paramSink, hintSink, table, enabledIds, dialect) }
            .filter { it.isNotEmpty() }
        if (rendered.isEmpty()) return ""
        return if (indent) {
            rendered.mapIndexed { index, r -> if (index == 0) r else "    ${connector.name} $r" }.joinToString("\n")
        } else {
            rendered.joinToString(" ${connector.name} ")
        }
    }

    /**
     * 动态条件可见性（M12）：无 toggle 恒可见；有 toggle 时按启用集合（enabledIds 非 null）
     * 或 defaultEnabled（enabledIds 为 null，默认渲染与 M11 基线一致）判定。
     */
    private fun Condition.isConditionVisible(enabledIds: Set<String>?): Boolean {
        val t = toggle ?: return true
        return if (enabledIds != null) t.id in enabledIds else t.defaultEnabled
    }

    /**
     * 渲染单个条件节点。
     *
     * @param enabledIds 动态条件启用集合（递归传给 Group 子条件；默认 null 按 defaultEnabled）
     * @param hintSink   回填提示收集器（M13，可为 null）
     * @param table      主表信息（列类型查询用；join ON 条件等无法匹配时类型未知）
     * @param dialect    方言（T-M23.4 关键字标识符引用；默认 MySQL）
     */
    private fun renderCondition(
        condition: Condition,
        paramSink: MutableList<String>? = null,
        hintSink: MutableList<ParamHint>? = null,
        table: TableInfo? = null,
        enabledIds: Set<String>? = null,
        dialect: Dialect = MySqlDialect,
    ): String = when (condition) {
        is Condition.Simple ->
            "${renderQualified(condition.column, dialect)} ${condition.op} ${renderValue(condition.value, paramSink, hintSink, quoteHint(table, condition.column))}"

        is Condition.Unary ->
            "${renderQualified(condition.column, dialect)} ${condition.op}"

        is Condition.In -> {
            val op = if (condition.negated) "NOT IN" else "IN"
            // M19.6（用户实测）：IN 列表占位符标记 inList 提示——面板回填按逗号分割多值、每个值独立引号
            val values = condition.values.joinToString(", ") { value ->
                when (value) {
                    is SqlValue.Placeholder -> {
                        paramSink?.add(value.source)
                        hintSink?.add(ParamHint(quote = quoteHint(table, condition.column), inList = true))
                        "?"
                    }
                    is SqlValue.Literal -> value.raw
                }
            }
            "${renderQualified(condition.column, dialect)} $op ($values)"
        }

        is Condition.Between -> {
            val op = if (condition.negated) "NOT BETWEEN" else "BETWEEN"
            "${renderQualified(condition.column, dialect)} $op ${renderValue(condition.lo, paramSink, hintSink, quoteHint(table, condition.column))} AND ${renderValue(condition.hi, paramSink, hintSink, quoteHint(table, condition.column))}"
        }

        is Condition.Like -> {
            val op = if (condition.negated) "NOT LIKE" else "LIKE"
            "${renderQualified(condition.column, dialect)} $op ${renderLike(condition.pattern, condition.position, paramSink, hintSink)}"
        }

        is Condition.Group -> {
            // T-M29.1：组带开关（布尔重载）时子条件开关并入组行、不在面板单独展示——
            // 子条件按各自 defaultEnabled 渲染（enabledIds 仅控制组本身显隐）；
            // 无开关组维持 enabledIds 透传（子条件行仍可单独勾选）
            val childIds = if (condition.toggle != null) null else enabledIds
            val childrenSql = renderConditionList(condition.children, condition.connector, indent = false, paramSink, childIds, hintSink, table, dialect)
            if (childrenSql.isEmpty()) "" else if (condition.negated) "NOT ($childrenSql)" else "($childrenSql)"
        }

        is Condition.ColumnComparison ->
            "${renderQualified(condition.left, dialect)} = ${renderQualified(condition.right, dialect)}"

        is Condition.Raw -> {
            // T-UpdateSetSqlConcat：片段里的 `?` 按序登记来源——不登记会让面板按位置回填整体错位
            // （片段里的 `?` 会被当成下一个条件的参数）
            condition.params.forEach { source ->
                paramSink?.add(source)
                hintSink?.add(ParamHint(quote = null))
            }
            condition.sqlText
        }
    }

    // ------------------------------------------------------------------
    // T-M23.4：标识符渲染统一入口（关键字命中 → 按方言符号包裹；模型层列名保持原始值）
    // ------------------------------------------------------------------

    /**
     * 渲染带表限定的列引用：`别名.列`——别名前缀不包，列名按需引用（`t.`table``）。
     * 与 [cn.song.mybatisplus.visualizer.model.ColumnRef.qualifiedName]（dialect 重载）同一入口。
     */
    private fun renderQualified(column: ColumnRef, dialect: Dialect): String = column.qualifiedName(dialect)

    /**
     * 渲染完整列表达式（含聚合函数与 AS 别名）：rawExpr 原样透传（selectFunc 表达式，不拆解——
     * 文档化边界）；否则 `函数(限定列)` 或 `限定列`；别名 `AS xxx` 不引用。
     */
    private fun renderColumnRef(
        column: ColumnRef,
        dialect: Dialect,
        paramSink: MutableList<String>? = null,
        hintSink: MutableList<ParamHint>? = null,
    ): String {
        // T-UpdateSetSqlConcat：`select("col AS " + x)` 这类片段列的 `?` 按序登记（SELECT 列表渲染点）
        column.rawParams.forEach { source ->
            paramSink?.add(source)
            hintSink?.add(ParamHint(quote = null))
        }
        val expr = column.rawExpr ?: (column.function?.let { "$it(${renderQualified(column, dialect)})" } ?: renderQualified(column, dialect))
        return if (column.alias != null) "$expr AS ${column.alias}" else expr
    }

    /** 渲染表名（关键字命中 → 按方言符号包裹）。 */
    private fun renderTableName(tableName: String, dialect: Dialect): String = dialect.quoteIfKeyword(tableName)

    /**
     * 渲染参数值：字面量原样输出；占位符输出 `?` 并收集来源与回填提示（M13）。
     */
    private fun renderValue(value: SqlValue, paramSink: MutableList<String>?, hintSink: MutableList<ParamHint>?, quote: Boolean?): String = when (value) {
        is SqlValue.Literal -> value.raw
        is SqlValue.Placeholder -> {
            paramSink?.add(value.source)
            hintSink?.add(ParamHint(quote = quote))
            "?"
        }
    }

    /**
     * 渲染 LIKE 模式：按 [LikePosition] 在字面量两侧拼接 `%`。
     * 占位符值输出 `?` 并收集回填提示（M13：按位置拼 `%`、恒引号——LIKE 模式恒为字符串）。
     */
    private fun renderLike(pattern: SqlValue, position: LikePosition, paramSink: MutableList<String>?, hintSink: MutableList<ParamHint>?): String = when (pattern) {
        is SqlValue.Placeholder -> {
            paramSink?.add(pattern.source)
            hintSink?.add(ParamHint(quote = true, likePosition = position))
            "?"
        }
        is SqlValue.Literal -> {
            val v = pattern.unquoted
            val core = when (position) {
                LikePosition.ANYWHERE -> "%$v%"
                LikePosition.LEFT -> "%$v"
                LikePosition.RIGHT -> "$v%"
                LikePosition.NONE -> v
            }
            "'$core'"
        }
    }

    /**
     * T-M29-03：分页子句占位符收集（`service.page` 的运行时 pageNum/pageSize）——
     * 数值参数无引号提示（与 [renderValue] 的 quote 参数语义一致）。
     */
    private fun collectLimitValue(value: SqlValue?, paramSink: MutableList<String>?, hintSink: MutableList<ParamHint>?) {
        if (value is SqlValue.Placeholder) {
            paramSink?.add(value.source)
            hintSink?.add(ParamHint(quote = false))
        }
    }

    /**
     * 列值引号提示（M13，T-M23-03 修正）：按实体字段类型判定——
     * **除确定的数值类型（int/long 等）外，其他类型默认带引号**（用户确认：LocalDateTime/Date/Boolean
     * 等非数值类型原实现一律不加引号，回填后 SQL 无效）；列或类型未知返回 null（面板走原数字启发式）。
     * 判定逻辑见 [ParamQuote]（批量模板路径共用，T-M23-02）。
     */
    private fun quoteHint(table: TableInfo?, column: ColumnRef): Boolean? {
        val type = table?.columns?.firstOrNull { it.columnName == column.columnName }?.fieldType
        return ParamQuote.decide(type)
    }
}
