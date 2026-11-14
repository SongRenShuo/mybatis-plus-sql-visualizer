package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.reverse.SqlTokenizer.Token
import cn.song.mybatisplus.visualizer.reverse.SqlTokenizer.TokenType

/**
 * SQL → MyBatis-Plus 代码 反向解析器（P0）。
 *
 * 把单表 SELECT SQL 文本解析为可复用的条件模型（[Condition] 家族——与正向
 * SqlGenerator 同一模型，round-trip 自洽），供 [JavaCodeGenerator] 生成 QueryWrapper 代码。
 *
 * P0 支持范围（超出即抛 [SqlReverseException]，错误文案走 bundle key）：
 *  - `SELECT 列 FROM 表 [WHERE 顶层 AND 链] [ORDER BY ...] [LIMIT ...]`；
 *  - 条件算子：`= != <> > >= < <=`、`IS [NOT] NULL`、`[NOT] IN (...)`、
 *    `[NOT] BETWEEN a AND b`、`[NOT] LIKE '模式'`（通配符位置 → LikePosition；
 *    `LIKE ?` 占位符模式 → LikePosition.NONE）；
 *  - `?` 参数占位符（WHERE/SET/INSERT/LIMIT 均支持）→ [SqlValue.Placeholder]，
 *    生成 `"?"` 占位参数由用户替换（round-trip：正向参数渲染的 `?` 可转回）；
 *  - 列名/表名支持反引号与点分前缀（`t.col` / `` `t`.`col` ``），前缀在生成时剥离（单表无别名）。
 *
 * 明确不支持（P1+ 范围，报错而非猜测）：OR/嵌套括号、函数表达式、子查询、
 * JOIN、DISTINCT、GROUP BY/HAVING、UNION、列对列比较、多条语句。
 */
object SqlReverseParser {

    /** 解析入口。 @throws SqlReverseException 不支持或语法错误 */
    fun parse(sqlText: String): SqlReverseResult {
        val tokens = SqlTokenizer().tokenize(sqlText)
        if (tokens.isEmpty()) {
            throw SqlReverseException(0, KEY_PARSE, "SQL 文本为空")
        }
        val cursor = Cursor(tokens, sqlText)

        // 语句头分发（T-M36.7：增删改查全支持）
        return when {
            cursor.isKeyword(cursor.current, "SELECT") -> {
                cursor.advance()
                parseSelectBody(cursor)
            }
            cursor.isKeyword(cursor.current, "UPDATE") -> {
                cursor.advance()
                parseUpdateBody(cursor)
            }
            cursor.isKeyword(cursor.current, "DELETE") -> {
                cursor.advance()
                parseDeleteBody(cursor)
            }
            cursor.isKeyword(cursor.current, "INSERT") -> {
                cursor.advance()
                parseInsertBody(cursor)
            }
            else -> {
                val first = cursor.current
                throw SqlReverseException(
                    first?.offset ?: 0,
                    KEY_NOT_SELECT,
                    first?.takeIf { it.type == TokenType.WORD }?.text ?: "?",
                )
            }
        }
    }

    /** SELECT 主体：列清单 + FROM 表 + 子句序列（T-M37：含 JOIN 解析）。 */
    private fun parseSelectBody(cursor: Cursor): SqlReverseResult {
        val (selectColumns, distinct) = parseSelectColumns(cursor)
        if (!cursor.isKeyword(cursor.current, "FROM")) {
            throw SqlReverseException(cursor.current?.offset ?: 0, KEY_PARSE, "缺少 FROM 子句")
        }
        cursor.advance()
        val tableAlias: String?
        val tableName = if (cursor.current?.type == TokenType.SYMBOL && cursor.current?.text == "(") {
            // FROM 子查询：别名已并入 tableName 文本（parseSubqueryTable 返回含别名的整段），此处无独立别名
            tableAlias = null
            parseSubqueryTable(cursor).first
        } else {
            val name = parseTableName(cursor)
            tableAlias = consumeOptionalAlias(cursor)
            name
        }
        val (conditions, orderBy, limit, warnings, joins, groupBy, having, forUpdate, unions) =
            parseTailClauses(cursor, allowJoin = true)
        return SqlReverseResult(
            tableName = tableName,
            selectColumns = selectColumns,
            conditions = conditions,
            orderBy = orderBy,
            limit = limit,
            warnings = warnings,
            joins = joins,
            tableAlias = tableAlias,
            distinct = distinct,
            groupBy = groupBy,
            having = having,
            forUpdate = forUpdate,
            unions = unions,
        )
    }

    /** UPDATE 主体：表 + SET 赋值 + 子句序列（T-M36.7）。 */
    private fun parseUpdateBody(cursor: Cursor): SqlReverseResult {
        val tableName = parseTableName(cursor)
        val tableAlias = consumeOptionalAlias(cursor)
        if (!cursor.isKeyword(cursor.current, "SET")) {
            throw SqlReverseException(cursor.current?.offset ?: 0, KEY_PARSE, "UPDATE 缺少 SET 子句")
        }
        cursor.advance()
        val sets = parseSets(cursor)
        val (conditions, orderBy, limit, warnings) = parseTailClauses(cursor)
        return SqlReverseResult(
            tableName = tableName,
            conditions = conditions,
            orderBy = orderBy,
            limit = limit,
            warnings = warnings,
            kind = ReverseStatementKind.UPDATE,
            sets = sets,
            tableAlias = tableAlias,
        )
    }

    /**
     * DELETE 主体（T-M36.7 + 多表删除）：
     * `DELETE FROM 表 [WHERE ...]`（无目标清单，单表，现状不变）或
     * `DELETE t, t1 FROM 表 [别名] [JOIN 链] [WHERE ...]`（目标清单 → deleteTargets，联表删除）。
     */
    private fun parseDeleteBody(cursor: Cursor): SqlReverseResult {
        val deleteTargets = mutableListOf<String>()
        if (!cursor.isKeyword(cursor.current, "FROM")) {
            // DELETE 目标清单：逗号分隔的别名/表名（剥反引号）
            while (true) {
                val t = cursor.current
                    ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "DELETE 缺少 FROM 子句")
                if (t.type != TokenType.WORD) {
                    throw SqlReverseException(t.offset, KEY_PARSE, "DELETE 目标格式不正确")
                }
                deleteTargets.add(t.text.trim('`'))
                cursor.advance()
                if (cursor.isSymbol(cursor.current, ",")) {
                    cursor.advance()
                    continue
                }
                break
            }
        }
        if (!cursor.isKeyword(cursor.current, "FROM")) {
            throw SqlReverseException(cursor.current?.offset ?: 0, KEY_PARSE, "DELETE 缺少 FROM 子句")
        }
        cursor.advance()
        val tableName = parseTableName(cursor)
        val tableAlias = consumeOptionalAlias(cursor)
        // 联表删除：DELETE 的尾子句放开 JOIN（UPDATE 的 JOIN 保持报错不变）
        val (conditions, orderBy, limit, warnings, joins) = parseTailClauses(cursor, allowJoin = true)
        return SqlReverseResult(
            tableName = tableName,
            conditions = conditions,
            orderBy = orderBy,
            limit = limit,
            warnings = warnings,
            joins = joins,
            kind = ReverseStatementKind.DELETE,
            deleteTargets = deleteTargets,
            tableAlias = tableAlias,
        )
    }

    /** INSERT 主体：INTO 表 (列清单) VALUES (值清单)（T-M36.7；多行 VALUES 支持）。 */
    private fun parseInsertBody(cursor: Cursor): SqlReverseResult {
        if (!cursor.isKeyword(cursor.current, "INTO")) {
            throw SqlReverseException(cursor.current?.offset ?: 0, KEY_PARSE, "INSERT 缺少 INTO 子句")
        }
        cursor.advance()
        val tableName = parseTableName(cursor)

        expectSymbol(cursor, "(")
        val columns = mutableListOf<String>()
        while (true) {
            val t = cursor.current
                ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "INSERT 列清单不完整")
            if (t.type != TokenType.WORD) {
                throw SqlReverseException(t.offset, KEY_PARSE, "INSERT 列名格式不正确")
            }
            columns.add(stripQualified(consumeQualified(cursor)))
            if (cursor.isSymbol(cursor.current, ",")) {
                cursor.advance()
                continue
            }
            break
        }
        expectSymbol(cursor, ")")
        if (!cursor.isKeyword(cursor.current, "VALUES")) {
            throw SqlReverseException(cursor.current?.offset ?: 0, KEY_PARSE, "INSERT 缺少 VALUES")
        }
        cursor.advance()
        // VALUES 多行：`(v1, v2), (v3, v4)`——每行独立值列表；行内列数须与列清单一致，不齐报 KEY_PARSE
        val rows = mutableListOf<List<SqlValue>>()
        while (true) {
            expectSymbol(cursor, "(")
            val values = mutableListOf<SqlValue>()
            while (true) {
                // T-SqlToJavaPromote G6：NULL 字面量（parseValue 返回 null）建模为 Literal("NULL")
                // ——Java 生成 null、SQL 重渲染 NULL；不再报错
                val value = parseValue(cursor) ?: SqlValue.Literal("NULL")
                values.add(value)
                if (cursor.isSymbol(cursor.current, ",")) {
                    cursor.advance()
                    continue
                }
                break
            }
            expectSymbol(cursor, ")")
            if (columns.size != values.size) {
                throw SqlReverseException(cursor.previousOffset(), KEY_PARSE, "INSERT 列数与值数不一致")
            }
            rows.add(values)
            if (cursor.isSymbol(cursor.current, ",")) {
                cursor.advance()
                continue
            }
            break
        }
        return SqlReverseResult(
            tableName = tableName,
            warnings = emptyList(),
            kind = ReverseStatementKind.INSERT,
            insertColumns = columns,
            insertValues = rows,
        )
    }

    /** SET 赋值清单（T-M36.7）：`列 = 值` 逗号分隔；函数/表达式值降级 rawSql 透传。 */
    private fun parseSets(cursor: Cursor): List<ReverseSet> {
        val sets = mutableListOf<ReverseSet>()
        while (true) {
            val column = parseColumnRef(cursor)
            expectSymbol(cursor, "=")
            val value = try {
                parseValue(cursor)
            } catch (e: SqlReverseException) {
                if (e.key == KEY_UNSUPPORTED) {
                    // 函数/表达式值（如 update_time = NOW()）→ 收集到逗号边界的原文 rawSql 透传
                    val startIdx = cursor.tokens.indexOfFirst { it.offset == e.offset }.coerceAtLeast(0)
                    val endIdx = cursor.tokens.indexOfFirst {
                        it.offset > e.offset && it.type == TokenType.SYMBOL && it.text == ","
                    }.let { if (it < 0) cursor.tokens.size else it }
                    val endOffset = if (endIdx > 0) {
                        val last = cursor.tokens[endIdx - 1]
                        last.offset + last.text.length
                    } else {
                        e.offset
                    }
                    cursor.pos = endIdx
                    sets.add(ReverseSet(column = column.columnName, value = null, rawSql = "${column.columnName} = ${cursor.source.substring(e.offset, endOffset).trim()}"))
                    if (cursor.isSymbol(cursor.current, ",")) {
                        cursor.advance()
                        continue
                    }
                    break
                }
                throw e
            }
            sets.add(ReverseSet(column = column.columnName, value = value))
            if (cursor.isSymbol(cursor.current, ",")) {
                cursor.advance()
                continue
            }
            break
        }
        return sets
    }

    /**
     * 尾子句序列（SELECT/UPDATE/DELETE 共用）：WHERE / ORDER BY / LIMIT 及不支持子句报错。
     * T-M37：allowJoin=true（SELECT）时解析 JOIN 链；DELETE 多表删除同样放开（UPDATE 保持报错）。
     */
    private fun parseTailClauses(cursor: Cursor, allowJoin: Boolean = false): TailClauses {
        val warnings = mutableListOf<String>()
        var conditions = emptyList<Condition>()
        var orderBy = emptyList<OrderByItem>()
        var limit: LimitClause? = null
        val joins = mutableListOf<ReverseJoin>()
        var groupBy = emptyList<ColumnRef>()
        var having: Condition? = null
        var forUpdate = false
        val unions = mutableListOf<ReverseUnion>()
        while (true) {
            val t = cursor.current ?: break
            when {
                cursor.isKeyword(t, "WHERE") -> {
                    cursor.advance()
                    conditions = parseConditionList(cursor, warnings)
                }
                cursor.isKeyword(t, "ORDER") -> {
                    cursor.advance()
                    expectKeyword(cursor, "BY")
                    orderBy = parseOrderBy(cursor)
                }
                cursor.isKeyword(t, "GROUP") -> {
                    cursor.advance()
                    expectKeyword(cursor, "BY")
                    groupBy = parseGroupBy(cursor)
                }
                cursor.isKeyword(t, "HAVING") -> {
                    cursor.advance()
                    having = parseHaving(cursor, warnings)
                }
                cursor.isKeyword(t, "LIMIT") -> {
                    cursor.advance()
                    limit = parseLimit(cursor)
                }
                cursor.isKeyword(t, "OFFSET") -> {
                    cursor.advance()
                    limit = parseOffsetFetch(cursor)
                }
                cursor.isKeyword(t, "FETCH") -> {
                    cursor.advance()
                    limit = parseFetchFirst(cursor)
                }
                allowJoin && isJoinStart(cursor) -> joins.add(parseJoin(cursor, warnings))
                cursor.isKeyword(t, "FOR") -> {
                    // FOR UPDATE（MySQL/PostgreSQL 行锁）；FOR 后非 UPDATE → 不支持
                    if (cursor.isKeyword(cursor.peek(), "UPDATE")) {
                        cursor.advance()
                        cursor.advance()
                        forUpdate = true
                    } else {
                        unsupported(cursor, "FOR 子句")
                    }
                }
                cursor.isKeyword(t, "UNION") -> {
                    // T-SqlToJavaPromote G8：UNION [ALL] 子查询——收集 keyword + 子查询原文
                    // （MP core 无 union() 方法、MPJ 签名本地不可得 → 模型层原文承载，Java 生成层降级注释）
                    cursor.advance()
                    val all = if (cursor.isKeyword(cursor.current, "ALL")) {
                        cursor.advance()
                        true
                    } else {
                        false
                    }
                    unions.add(ReverseUnion(keyword = if (all) "UNION ALL" else "UNION", sql = collectUnionSubquery(cursor)))
                }
                cursor.isKeyword(t, "JOIN") || cursor.isKeyword(t, "LEFT") || cursor.isKeyword(t, "RIGHT") ||
                    cursor.isKeyword(t, "INNER") || cursor.isKeyword(t, "FULL") || cursor.isKeyword(t, "CROSS") ->
                    unsupported(cursor, "JOIN")
                t.type == TokenType.SYMBOL && t.text == ";" -> {
                    cursor.advance()
                    if (cursor.current != null) unsupported(cursor, "多条语句（一次转换一条）")
                    break
                }
                else -> throw SqlReverseException(
                    t.offset,
                    KEY_PARSE,
                    "无法识别的子句：${t.text}",
                )
            }
        }
        return TailClauses(conditions, orderBy, limit, warnings, joins, groupBy, having, forUpdate, unions)
    }

    /** 尾子句解析产物。 */
    private data class TailClauses(
        val conditions: List<Condition>,
        val orderBy: List<OrderByItem>,
        val limit: LimitClause?,
        val warnings: List<String>,
        val joins: List<ReverseJoin> = emptyList(),
        val groupBy: List<ColumnRef> = emptyList(),
        val having: Condition? = null,
        val forUpdate: Boolean = false,
        val unions: List<ReverseUnion> = emptyList(),
    )

    /** UNION 后子查询原文收集：推进到下一个 UNION 或语句尾，取原文；首尾括号剥除（渲染 `UNION (子查询)` 单层括号）。 */
    private fun collectUnionSubquery(cursor: Cursor): String {
        val start = cursor.current?.offset
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "UNION 后缺少子查询")
        while (cursor.current != null && !cursor.isKeyword(cursor.current, "UNION")) {
            cursor.advance()
        }
        val last = cursor.tokens.getOrNull(cursor.pos - 1)
        val end = if (last != null) last.offset + last.text.length else start
        val raw = cursor.source.substring(start, end).trim()
        return if (raw.startsWith("(") && raw.endsWith(")")) {
            raw.substring(1, raw.length - 1).trim()
        } else {
            raw
        }
    }

    /** GROUP BY 列清单：`列[, 列...]`（点分/反引号同列引用）。 */
    private fun parseGroupBy(cursor: Cursor): List<ColumnRef> {
        val columns = mutableListOf<ColumnRef>()
        while (true) {
            columns.add(parseColumnRef(cursor))
            if (cursor.isSymbol(cursor.current, ",")) {
                cursor.advance()
                continue
            }
            break
        }
        return columns
    }

    /**
     * HAVING 条件（T-SqlToJavaPromote G2）：单条件直取；多条件包 [Condition.Group](AND)。
     * 聚合表达式（`COUNT(*) > 1`）经 [parseCondition] 的 KEY_UNSUPPORTED 降级为 [Condition.Raw]（记 warning）。
     */
    private fun parseHaving(cursor: Cursor, warnings: MutableList<String>): Condition {
        val conditions = parseConditionList(cursor, warnings)
        if (conditions.isEmpty()) {
            throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "HAVING 后缺少条件")
        }
        return if (conditions.size == 1) conditions[0] else Condition.Group(Connector.AND, conditions)
    }

    // ------------------------------------------------------------------
    // JOIN 解析（T-M37）
    // ------------------------------------------------------------------

    /** JOIN 起始判定（含修饰词 + [OUTER] JOIN）。 */
    private fun isJoinStart(cursor: Cursor): Boolean {
        val t = cursor.current ?: return false
        if (cursor.isKeyword(t, "JOIN")) return true
        if (!cursor.isKeyword(t, "LEFT") && !cursor.isKeyword(t, "RIGHT") && !cursor.isKeyword(t, "INNER") &&
            !cursor.isKeyword(t, "FULL") && !cursor.isKeyword(t, "CROSS")
        ) {
            return false
        }
        // 修饰词后须跟 [OUTER] JOIN 才算 JOIN 起始（LEFT JOIN / LEFT OUTER JOIN）
        val next = cursor.peek()
        if (next != null && cursor.isKeyword(next, "JOIN")) return true
        if (next != null && cursor.isKeyword(next, "OUTER")) {
            val after = cursor.tokens.getOrNull(cursor.pos + 2)
            return after != null && cursor.isKeyword(after, "JOIN")
        }
        return false
    }

    /** 解析一个 JOIN 子句：类型 + 副表[别名] + ON 条件（等值列对 → ColumnComparison）。 */
    private fun parseJoin(cursor: Cursor, warnings: MutableList<String>): ReverseJoin {
        val type = when {
            cursor.isKeyword(cursor.current, "JOIN") -> {
                cursor.advance()
                cn.song.mybatisplus.visualizer.model.JoinType.INNER
            }
            else -> {
                val modifier = cursor.current?.text?.uppercase()
                cursor.advance()
                if (cursor.isKeyword(cursor.current, "OUTER")) cursor.advance()
                expectKeyword(cursor, "JOIN")
                when (modifier) {
                    "LEFT" -> cn.song.mybatisplus.visualizer.model.JoinType.LEFT
                    "RIGHT" -> cn.song.mybatisplus.visualizer.model.JoinType.RIGHT
                    "FULL" -> cn.song.mybatisplus.visualizer.model.JoinType.FULL
                    else -> cn.song.mybatisplus.visualizer.model.JoinType.INNER
                }
            }
        }
        val (tableName, alias, subQuery) = parseJoinTableExpression(cursor)
        if (!cursor.isKeyword(cursor.current, "ON")) {
            throw SqlReverseException(cursor.current?.offset ?: 0, KEY_PARSE, "JOIN 缺少 ON 子句")
        }
        cursor.advance()
        // 收集 ON 原文（raw join 字符串透传用；从 ON 后首个 token 到条件解析结束）
        val onStart = cursor.current?.offset
        val onConditions = parseOnConditions(cursor, warnings)
        val rawOn = if (onStart != null && cursor.tokens.isNotEmpty()) {
            val last = cursor.tokens.getOrNull(cursor.pos - 1)
            if (last != null && last.offset >= onStart) {
                cursor.source.substring(onStart, last.offset + last.text.length).trim()
            } else {
                null
            }
        } else {
            null
        }
        return ReverseJoin(type, tableName, alias, onConditions, rawOn, subQuery)
    }

    /** JOIN 副表别名（ON 为边界；无别名返回 null）。 */
    private fun consumeJoinAlias(cursor: Cursor): String? {
        val t = cursor.current ?: return null
        if (t.type == TokenType.WORD && !cursor.isKeyword(t, "ON") && !cursor.isKeyword(t, "WHERE") &&
            !cursor.isKeyword(t, "GROUP") && !cursor.isKeyword(t, "ORDER") && !cursor.isKeyword(t, "LIMIT") &&
            !cursor.isKeyword(t, "LEFT") && !cursor.isKeyword(t, "RIGHT") && !cursor.isKeyword(t, "INNER") &&
            !cursor.isKeyword(t, "FULL") && !cursor.isKeyword(t, "CROSS") && !cursor.isKeyword(t, "JOIN")
        ) {
            val alias = t.text.trim('`')
            cursor.advance()
            return alias
        }
        return null
    }

    /** ON 条件列表（AND 连接；等值列对自动识别为 ColumnComparison；遇下一个 JOIN/子句结束）。 */
    private fun parseOnConditions(cursor: Cursor, warnings: MutableList<String>): List<Condition> {
        val conditions = mutableListOf<Condition>()
        while (true) {
            val t = cursor.current ?: break
            if (isJoinStart(cursor)) break
            if (t.type == TokenType.WORD && (isClauseStart(cursor) || cursor.isKeyword(t, "WHERE"))) break
            if (t.type == TokenType.SYMBOL && t.text == ";") break
            if (cursor.isKeyword(t, "AND")) {
                cursor.advance()
                continue
            }
            if (cursor.isKeyword(t, "OR")) unsupported(cursor, "ON 内 OR 嵌套条件")
            conditions.add(parseCondition(cursor, warnings))
        }
        return conditions
    }

    // ------------------------------------------------------------------
    // SELECT 列清单
    // ------------------------------------------------------------------

    /**
     * 解析 SELECT 与 FROM 之间的列清单。
     *
     * 收集「简单列」（标识符 / 点分 / 反引号，可带 AS 别名）与「函数/聚合列」
     * （T-SqlToJavaPromote G5：`COUNT(*) AS cnt` 等收集原文，不再放弃）；`*` 或裸括号
     * 表达式时整体放弃（返回 null——生成器不生成 select() 调用，MP 全列语义）。
     * T-M37：保留原文（含表别名前缀与反引号）——多表列归属由生成器拆分（renderSelectRef）。
     * T-M46：捕获 AS 别名（SelectColumn.alias）——生成 selectAs。
     * 返回 (列清单, DISTINCT)（T-SqlToJavaPromote G3：DISTINCT 建模为 distinct=true，不放弃列）。
     */
    private fun parseSelectColumns(cursor: Cursor): Pair<List<SelectColumn>?, Boolean> {
        val columns = mutableListOf<SelectColumn>()
        var anyComplex = false
        var distinct = false
        while (true) {
            val t = cursor.current ?: break
            when {
                cursor.isKeyword(t, "FROM") -> break
                t.type == TokenType.SYMBOL && t.text == "," -> {
                    cursor.advance()
                    continue
                }
                t.type == TokenType.SYMBOL && t.text == "*" -> {
                    anyComplex = true
                    cursor.advance()
                    continue
                }
                t.type == TokenType.WORD -> {
                    // DISTINCT：建模 distinct=true，继续收集列（T-SqlToJavaPromote G3）
                    if (cursor.isKeyword(t, "DISTINCT")) {
                        distinct = true
                        cursor.advance()
                        continue
                    }
                    if (isFunctionCall(cursor)) {
                        // 函数/聚合列：收集原文（含平衡括号）+ 可选 AS 别名（T-SqlToJavaPromote G5）
                        val expr = collectFunctionExpr(cursor)
                        val alias = consumeSelectAlias(cursor)
                        columns.add(SelectColumn(expr = expr, alias = alias))
                        continue
                    }
                    val expr = consumeQualified(cursor)
                    val alias = consumeSelectAlias(cursor)
                    columns.add(SelectColumn(expr = expr, alias = alias))
                }
                t.type == TokenType.SYMBOL && t.text == "(" -> {
                    // 裸括号表达式（少见）→ 放弃 select 生成，整体跳过
                    anyComplex = true
                    skipBalanced(cursor)
                }
                else -> break
            }
        }
        return if (anyComplex) Pair(null, distinct) else Pair(columns, distinct)
    }

    /** 当前 WORD 后紧跟 `(` → 函数调用。 */
    private fun isFunctionCall(cursor: Cursor): Boolean {
        val next = cursor.peek()
        return next != null && next.type == TokenType.SYMBOL && next.text == "("
    }

    /**
     * 收集函数列原文：函数名 + 平衡括号内容（`COUNT(*)` / `DATE_FORMAT(create_time, '%Y-%m')`）。
     * 消费到顶层 `)` 结束；返回源文本（不含 AS 别名）。
     */
    private fun collectFunctionExpr(cursor: Cursor): String {
        val start = cursor.current?.offset ?: return ""
        cursor.advance() // 函数名
        var depth = 0
        while (true) {
            val t = cursor.advance() ?: break
            if (t.type == TokenType.SYMBOL && t.text == "(") depth++
            if (t.type == TokenType.SYMBOL && t.text == ")") {
                depth--
                if (depth == 0) break
            }
        }
        val last = cursor.tokens.getOrNull(cursor.pos - 1)
        val end = if (last != null) last.offset + last.text.length else cursor.source.length
        return cursor.source.substring(start, end)
    }

    /** 消费 `AS 别名` 或直接别名（`a AS x` / `a x`；别名是普通 WORD），返回别名（无别名返回 null）。 */
    private fun consumeSelectAlias(cursor: Cursor): String? {
        val t = cursor.current
        if (t == null || t.type != TokenType.WORD) return null
        if (cursor.isKeyword(t, "AS")) {
            cursor.advance()
            return cursor.advance()?.text?.trim('`')
        }
        if (!cursor.isKeyword(t, "FROM")) {
            // `a x` 形态：x 是别名（无关键字风险——FROM 已在上层判定）
            cursor.advance()
            return t.text.trim('`')
        }
        return null
    }

    /**
     * 跳过整个表达式（平衡括号 + 词）直到顶层逗号或 FROM。
     * FROM 不消费（交回上层循环判定）；逗号消费（它是表达式的终止符）。
     */
    private fun skipExpression(cursor: Cursor) {
        var depth = 0
        while (true) {
            val t = cursor.current ?: break
            if (depth == 0 && cursor.isKeyword(t, "FROM")) break
            cursor.advance()
            if (t.type == TokenType.SYMBOL && t.text == "(") {
                depth++
                continue
            }
            if (t.type == TokenType.SYMBOL && t.text == ")") {
                depth--
                if (depth < 0) break
                continue
            }
            if (depth == 0 && t.type == TokenType.SYMBOL && t.text == ",") break
        }
    }

    /** 跳过一对平衡括号（当前 token 为 `(`）。 */
    private fun skipBalanced(cursor: Cursor) {
        var depth = 0
        while (true) {
            val t = cursor.advance() ?: break
            if (t.type == TokenType.SYMBOL && t.text == "(") depth++
            if (t.type == TokenType.SYMBOL && t.text == ")") {
                depth--
                if (depth == 0) break
            }
        }
    }

    // ------------------------------------------------------------------
    // 表名
    // ------------------------------------------------------------------

    /** 表名：`表` / `库.表`（点分/反引号，库前缀剥掉取末段）。 */
    private fun parseTableName(cursor: Cursor): String {
        val t = cursor.current
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "缺少表名")
        if (t.type != TokenType.WORD) {
            throw SqlReverseException(t.offset, KEY_PARSE, "表名格式不正确")
        }
        return stripQualified(consumeQualified(cursor))
    }

    /**
     * 解析子查询表表达式：`(SELECT ...) [别名]`。
     * 前置条件：当前 token 为 `(`。
     * 返回 (完整文本, 子查询结构)：子查询结构在简单单表查询时非 null，否则 null（回落字符串形态）。
     *
     * @throws SqlReverseException 括号不匹配（KEY_PARSE）
     */
    private fun parseSubqueryTable(cursor: Cursor): Pair<String, JoinSubQuery?> {
        val startToken = cursor.current
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "缺少子查询")
        val startOffset = startToken.offset
        var depth = 0
        while (cursor.current != null) {
            val tk = cursor.current!!
            if (tk.type == TokenType.SYMBOL && tk.text == "(") depth++
            if (tk.type == TokenType.SYMBOL && tk.text == ")") {
                depth--
                if (depth == 0) {
                    val endOffset = tk.offset + 1
                    cursor.advance()
                    val subqueryText = cursor.source.substring(startOffset, endOffset)
                    val alias = consumeJoinAlias(cursor)
                    val structure = parseSubqueryStructure(subqueryText, alias ?: "")
                    return (if (alias != null) "$subqueryText $alias" else subqueryText) to structure
                }
            }
            cursor.advance()
        }
        throw SqlReverseException(startOffset, KEY_PARSE, "子查询括号不匹配")
    }

    /**
     * 尝试解析子查询内部结构（简单单表查询）。
     * 成功返回 [JoinSubQuery]，不满足条件（含 JOIN/嵌套子查询/复杂 SELECT）返回 null。
     */
    private fun parseSubqueryStructure(subqueryText: String, alias: String): JoinSubQuery? {
        // 去掉外层括号
        val inner = subqueryText.trim()
        if (!inner.startsWith("(") || !inner.endsWith(")")) return null
        val selectSql = inner.substring(1, inner.length - 1).trim()
        if (!selectSql.uppercase().startsWith("SELECT")) return null

        return try {
            val result = parse(selectSql)
            // 必须是简单单表查询：无 JOIN、无嵌套子查询、表名是简单 WORD
            if (result.joins.isNotEmpty()) return null
            if (result.tableName.startsWith("(")) return null

            val selectColumns = result.selectColumns?.map { stripQualified(it.expr) } ?: emptyList()
            val selectAll = result.selectColumns == null

            JoinSubQuery(
                tableName = result.tableName,
                alias = alias,
                selectAll = selectAll,
                selectColumns = selectColumns,
                whereConditions = result.conditions,
            )
        } catch (e: SqlReverseException) {
            null  // 无法解析 → 回落字符串形态
        }
    }

    /**
     * 解析 JOIN 表表达式：普通表名 或 子查询 `(SELECT ...) [别名]`。
     * 返回 (tableName, alias, subQuery)：
     *   - 普通表：("table", "alias", null)
     *   - 子查询：("(SELECT ...) alias", null, JoinSubQuery?)
     */
    private fun parseJoinTableExpression(cursor: Cursor): Triple<String, String?, JoinSubQuery?> {
        val t = cursor.current
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "缺少 JOIN 表名")
        if (t.type == TokenType.SYMBOL && t.text == "(") {
            val (text, structure) = parseSubqueryTable(cursor)
            return Triple(text, null, structure)
        }
        return Triple(parseTableName(cursor), consumeJoinAlias(cursor), null)
    }

    /** 可选表别名：`FROM user u`——u 非子句关键字时视为别名消费（T-M36.7：SET/INTO 亦为边界）；
     * 返回别名（无别名返回 null）。T-SqlToJavaPromote M1：返回值供主表别名建模。 */
    private fun consumeOptionalAlias(cursor: Cursor): String? {
        val t = cursor.current ?: return null
        if (t.type == TokenType.WORD && !cursor.isKeyword(t, "WHERE") && !cursor.isKeyword(t, "GROUP") &&
            !cursor.isKeyword(t, "ORDER") && !cursor.isKeyword(t, "LIMIT") && !cursor.isKeyword(t, "HAVING") &&
            !cursor.isKeyword(t, "UNION") && !cursor.isKeyword(t, "JOIN") && !cursor.isKeyword(t, "FOR") &&
            !cursor.isKeyword(t, "SET") && !cursor.isKeyword(t, "INTO") &&
            // T-M43：分页关键字不当作表别名（`FROM t OFFSET 5 ROWS...` 的 OFFSET 是分页子句）
            !cursor.isKeyword(t, "OFFSET") && !cursor.isKeyword(t, "FETCH") &&
            !cursor.isKeyword(t, "LEFT") && !cursor.isKeyword(t, "RIGHT") && !cursor.isKeyword(t, "INNER") &&
            !cursor.isKeyword(t, "FULL") && !cursor.isKeyword(t, "CROSS")
        ) {
            cursor.advance()
            return t.text.trim('`')
        }
        return null
    }

    // ------------------------------------------------------------------
    // WHERE 条件序列
    // ------------------------------------------------------------------

    /**
     * 条件序列（T-M36.1 升级）：顶层按 OR 分段，段内 AND 连接；出现 OR 时整体建模为
     * `Group(OR, 段们)`（段内多条件包 `Group(AND)`）——与正向链路 `or()` 分隔符语义一致。
     *
     * 形态：`a = 1 AND b = 2` → `[a, b]`（无 OR 保持扁平，P0 行为不变）；
     * `a = 1 OR b = 2` → `[Group(OR, [a, b])]`；
     * `a = 1 AND b = 2 OR c = 3` → `[Group(OR, [Group(AND, [a, b]), c])]`（AND 优先级高于 OR）。
     */
    private fun parseConditionList(cursor: Cursor, warnings: MutableList<String>): List<Condition> {
        val orSegments = mutableListOf<List<Condition>>()
        orSegments.add(parseAndSegment(cursor, warnings))
        while (cursor.isKeyword(cursor.current, "OR")) {
            cursor.advance()
            orSegments.add(parseAndSegment(cursor, warnings))
        }
        if (orSegments.size == 1) return orSegments[0]
        return listOf(
            Condition.Group(
                connector = Connector.OR,
                children = orSegments.map { segment ->
                    if (segment.size == 1) segment[0] else Condition.Group(Connector.AND, segment)
                },
            ),
        )
    }

    /** AND 连接的条件段（遇 OR / `)` / 子句起点 / 分号终止，均不消费）。 */
    private fun parseAndSegment(cursor: Cursor, warnings: MutableList<String>): List<Condition> {
        val conditions = mutableListOf<Condition>()
        while (true) {
            val t = cursor.current ?: break
            if (t.type == TokenType.WORD && isClauseStart(cursor)) break
            if (t.type == TokenType.SYMBOL && (t.text == ";" || t.text == ")")) break
            if (cursor.isKeyword(t, "AND")) {
                cursor.advance()
                continue
            }
            if (cursor.isKeyword(t, "OR")) break
            conditions.add(parseCondition(cursor, warnings))
        }
        return conditions
    }

    /**
     * 条件序列中的子句起点判定：`ORDER BY` / `GROUP BY`（须成对出现——`WHERE order = 1`
     * 的列名 order 不误判）与 HAVING / LIMIT / UNION / FOR。
     * T-M43：`OFFSET`（后随数字/`?` 才判定，列名 offset 不误判）与 `FETCH`（后随 FIRST/NEXT）。
     */
    private fun isClauseStart(cursor: Cursor): Boolean {
        val t = cursor.current ?: return false
        val by = cursor.isKeyword(cursor.peek(), "BY")
        return (cursor.isKeyword(t, "ORDER") && by) || (cursor.isKeyword(t, "GROUP") && by) ||
            cursor.isKeyword(t, "HAVING") || cursor.isKeyword(t, "LIMIT") ||
            cursor.isKeyword(t, "UNION") || cursor.isKeyword(t, "FOR") ||
            (cursor.isKeyword(t, "OFFSET") && isLimitValueStart(cursor.peek())) ||
            (cursor.isKeyword(t, "FETCH") &&
                (cursor.isKeyword(cursor.peek(), "FIRST") || cursor.isKeyword(cursor.peek(), "NEXT")))
    }

    /** 分页子句值起点判定：数字字面量或 `?` 占位符（`OFFSET 5 ROWS` / `OFFSET ? ROWS`）。 */
    private fun isLimitValueStart(t: Token?): Boolean =
        t != null && (t.type == TokenType.NUMBER || (t.type == TokenType.SYMBOL && t.text == "?"))

    /**
     * 单个条件（T-M36.1 升级）：括号组 / NOT 组 / 普通条件。
     * 解析失败且为"表达式形态不支持"（[KEY_UNSUPPORTED]：函数、子查询、列对列、`?` 占位符等）
     * → 降级为 [Condition.Raw] 整条件 apply() 透传（记 warning）；语法错误（[KEY_PARSE]）保持报错。
     */
    private fun parseCondition(cursor: Cursor, warnings: MutableList<String>): Condition {
        val startOffset = cursor.current?.offset
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "条件不完整")
        return try {
            parseConditionInternal(cursor, warnings)
        } catch (e: SqlReverseException) {
            if (e.key == KEY_UNSUPPORTED) {
                degradeToRaw(cursor, startOffset, warnings)
            } else {
                throw e
            }
        }
    }

    private fun parseConditionInternal(cursor: Cursor, warnings: MutableList<String>): Condition {
        val head = cursor.current ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "条件不完整")
        // 括号组 `( ... )` / `NOT ( ... )`（T-M36.1）
        if (head.type == TokenType.SYMBOL && head.text == "(") {
            cursor.advance()
            return parseGroup(cursor, warnings, negated = false)
        }
        if (cursor.isKeyword(head, "NOT") && cursor.isSymbol(cursor.peek(), "(")) {
            cursor.advance()
            cursor.advance()
            return parseGroup(cursor, warnings, negated = true)
        }
        val column = parseColumnRef(cursor)
        // 操作符判定基于列名之后的当前 token（P0 行为：t 为列名解析后的 current）
        val t = cursor.current ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "条件不完整")
        return when {
            // = != <> < <= > >= 二元比较（操作符 token 已判定，先消费再取右侧值）
            t.type == TokenType.SYMBOL && t.text == "=" -> {
                cursor.advance()
                parseBinary(cursor, column, "=")
            }
            t.type == TokenType.SYMBOL && t.text == "!" -> {
                cursor.advance()
                expectSymbol(cursor, "=")
                parseBinary(cursor, column, "!=")
            }
            t.type == TokenType.SYMBOL && t.text == "<" -> {
                cursor.advance()
                when (cursor.current?.text) {
                    "=" -> {
                        cursor.advance()
                        if (cursor.isSymbol(cursor.current, ">")) {
                            // T-SqlToJavaPromote G10：`<=>` NULL-safe 等值——MP 无对应方法 → 降级 Raw
                            cursor.advance()
                            unsupported(cursor, "<=> 空安全比较")
                        }
                        parseBinary(cursor, column, "<=")
                    }
                    ">" -> {
                        cursor.advance()
                        parseBinary(cursor, column, "<>")
                    }
                    else -> parseBinary(cursor, column, "<")
                }
            }
            t.type == TokenType.SYMBOL && t.text == ">" -> {
                cursor.advance()
                when (cursor.current?.text) {
                    "=" -> {
                        cursor.advance()
                        parseBinary(cursor, column, ">=")
                    }
                    else -> parseBinary(cursor, column, ">")
                }
            }
            // IS [NOT] NULL
            cursor.isKeyword(t, "IS") -> {
                cursor.advance()
                val negated = if (cursor.isKeyword(cursor.current, "NOT")) {
                    cursor.advance()
                    true
                } else {
                    false
                }
                if (!cursor.isKeyword(cursor.current, "NULL")) {
                    throw SqlReverseException(cursor.current?.offset ?: t.offset, KEY_PARSE, "IS 后应为 NULL")
                }
                cursor.advance()
                Condition.Unary(column, if (negated) "IS NOT NULL" else "IS NULL")
            }
            // NOT IN / NOT BETWEEN / NOT LIKE（NOT ( 组已在入口分支处理）
            cursor.isKeyword(t, "NOT") -> {
                cursor.advance()
                parseNegated(cursor, column, t.offset, warnings)
            }
            // IN / BETWEEN / LIKE
            cursor.isKeyword(t, "IN") -> {
                cursor.advance()
                parseIn(cursor, column, negated = false, t.offset)
            }
            cursor.isKeyword(t, "BETWEEN") -> {
                cursor.advance()
                parseBetween(cursor, column, negated = false, t.offset)
            }
            cursor.isKeyword(t, "LIKE") -> {
                cursor.advance()
                parseLike(cursor, column, negated = false, t.offset, warnings)
            }
            // 列名后紧跟 `(` → 函数表达式（DATE_FORMAT(...) 等，降级 apply 透传）
            t.type == TokenType.SYMBOL && t.text == "(" ->
                unsupported(cursor, "函数表达式 ${column.columnName}(...)")
            // T-SqlToJavaPromote G10：列名后无法识别的操作符（算术表达式 `price*qty`、ILIKE 等）
            // → 抛 KEY_UNSUPPORTED 走降级 Raw 透传（「任何条件都不挡路」原则）
            else -> unsupported(cursor, "条件操作符 ${t.text}")
        }
    }

    /**
     * 括号组解析（T-M36.1）：当前位于 `(` 之后；组内递归走 [parseConditionList]（OR 分段语义一致），
     * 直到 `)`。组内为单个 Group（如 `(a = 1 OR b = 2)`）时直接复用该组（避免双重括号）；
     * 组内仅 1 个非 Group 条件且非取反（T-ReverseParenWira：MPJ 渲染 ON/WHERE 自带括号，
     * `(st.station_id = t.id)`/`(t.id = 1)` 单条件括号无语义增量）时直接解包返回该条件——
     * 否则下游 `extractJoinKey` 认不出 ColumnComparison 致 MPJ lambda 形态退化、
     * WHERE 多渲染一层 nested；`NOT (a = 1)` 取反组仍保留 Group 承载 negated 标记。
     * 其余多条件情况包 `Group(AND, ...)`（保留括号，round-trip 等价）。空组报语法错误。
     */
    private fun parseGroup(cursor: Cursor, warnings: MutableList<String>, negated: Boolean): Condition {
        val inner = parseConditionList(cursor, warnings)
        expectSymbol(cursor, ")")
        if (inner.isEmpty()) {
            throw SqlReverseException(cursor.previousOffset(), KEY_PARSE, "空括号组")
        }
        val single = inner.singleOrNull()
        if (single is Condition.Group) {
            return if (negated) single.copy(negated = true) else single
        }
        if (single != null && !negated) {
            return single
        }
        return Condition.Group(connector = Connector.AND, children = inner, negated = negated)
    }

    /**
     * 表达式形态降级（T-M36.1）：从条件起始偏移扫描到顶层 AND/OR/`)`/子句边界，
     * 取原文整段为 [Condition.Raw]（生成 apply() 透传），游标推进到条件末尾。
     */
    private fun degradeToRaw(cursor: Cursor, startOffset: Int, warnings: MutableList<String>): Condition.Raw {
        val startIdx = cursor.tokens.indexOfFirst { it.offset == startOffset }.coerceAtLeast(0)
        var depth = 0
        var idx = startIdx
        while (idx < cursor.tokens.size) {
            val tk = cursor.tokens[idx]
            if (depth == 0 && (
                    (tk.type == TokenType.WORD && (isKeyword(tk, "AND") || isKeyword(tk, "OR") || isClauseToken(tk, cursor.tokens.getOrNull(idx + 1)))) ||
                        (tk.type == TokenType.SYMBOL && (tk.text == ";" || tk.text == ")"))
                    )
            ) {
                break
            }
            if (tk.type == TokenType.SYMBOL && tk.text == "(") depth++
            if (tk.type == TokenType.SYMBOL && tk.text == ")") depth--
            idx++
        }
        val endOffset = if (idx > startIdx) {
            val last = cursor.tokens[idx - 1]
            last.offset + last.text.length
        } else {
            startOffset
        }
        cursor.pos = idx
        val rawText = cursor.source.substring(startOffset, endOffset).trim()
        warnings.add("条件「$rawText」无法静态转换为 Wrapper 方法，已用 apply() 透传")
        return Condition.Raw(rawText)
    }

    private fun isKeyword(t: Token, kw: String): Boolean =
        t.type == TokenType.WORD && t.text.equals(kw, ignoreCase = true)

    /**
     * 降级扫描的子句边界（T-M43 加 OFFSET/FETCH，须带后继判定防列名误判——offset 是常见列名）。
     */
    private fun isClauseToken(t: Token, next: Token?): Boolean =
        isKeyword(t, "ORDER") || isKeyword(t, "GROUP") || isKeyword(t, "HAVING") ||
            isKeyword(t, "LIMIT") || isKeyword(t, "UNION") || isKeyword(t, "FOR") ||
            (isKeyword(t, "OFFSET") && next != null && isLimitValueStart(next)) ||
            (isKeyword(t, "FETCH") && next != null &&
                (isKeyword(next, "FIRST") || isKeyword(next, "NEXT")))

    /**
     * 二元比较（T-M37：`=` 右侧为裸标识符 → 列对列 [Condition.ColumnComparison]——
     * WHERE/ON 均适用；NULL 字面量 → IS NULL/IS NOT NULL；其余值走 [parseValue]）。
     */
    private fun parseBinary(cursor: Cursor, column: ColumnRef, sqlOp: String): Condition {
        if (sqlOp == "=") {
            val next = cursor.current
            if (next != null && next.type == TokenType.WORD && !cursor.isKeyword(next, "TRUE") &&
                !cursor.isKeyword(next, "FALSE") && !cursor.isKeyword(next, "NULL") &&
                // T-SqlToJavaPromote G10：DATE/TIMESTAMP 类型字面量（后随字符串）排除出列对列判定
                !cursor.isKeyword(next, "DATE") && !cursor.isKeyword(next, "TIMESTAMP")
            ) {
                // 右值为函数调用（后跟 `(`）→ 非列对列，走值解析（降级 Raw 透传）
                val after = cursor.peek()
                val isFunction = after != null && after.type == TokenType.SYMBOL && after.text == "("
                if (!isFunction) {
                    val right = parseColumnRef(cursor)
                    return Condition.ColumnComparison(column, right)
                }
            }
        }
        val value = parseValue(cursor) ?: return when (sqlOp) {
            "=" -> Condition.Unary(column, "IS NULL")
            "!=", "<>" -> Condition.Unary(column, "IS NOT NULL")
            else -> throw SqlReverseException(cursor.previousOffset(), KEY_PARSE, "NULL 只能与 = / != 比较")
        }
        return Condition.Simple(column, sqlOp, value)
    }

    /** NOT 变体分发：NOT IN / NOT BETWEEN / NOT LIKE；其余 → 报错。 */
    private fun parseNegated(cursor: Cursor, column: ColumnRef, notOffset: Int, warnings: MutableList<String>): Condition {
        val t = cursor.current ?: throw SqlReverseException(notOffset, KEY_PARSE, "NOT 后缺少条件")
        return when {
            cursor.isKeyword(t, "IN") -> {
                cursor.advance()
                parseIn(cursor, column, negated = true, t.offset)
            }
            cursor.isKeyword(t, "BETWEEN") -> {
                cursor.advance()
                parseBetween(cursor, column, negated = true, t.offset)
            }
            cursor.isKeyword(t, "LIKE") -> {
                cursor.advance()
                parseLike(cursor, column, negated = true, t.offset, warnings)
            }
            else -> unsupported(cursor, "NOT 表达式（仅支持 NOT IN / NOT BETWEEN / NOT LIKE）")
        }
    }

    /** IN (v1, v2, ...)：空列表或含 NULL → 报错。 */
    private fun parseIn(cursor: Cursor, column: ColumnRef, negated: Boolean, opOffset: Int): Condition {
        expectSymbol(cursor, "(")
        val values = mutableListOf<SqlValue>()
        while (true) {
            val t = cursor.current ?: break
            if (t.type == TokenType.SYMBOL && t.text == ")") {
                cursor.advance()
                break
            }
            val value = parseValue(cursor)
                ?: throw SqlReverseException(t.offset, KEY_PARSE, "IN 列表不支持 NULL")
            values.add(value)
            if (cursor.isSymbol(cursor.current, ",")) {
                cursor.advance()
            } else if (!cursor.isSymbol(cursor.current, ")")) {
                throw SqlReverseException(cursor.current?.offset ?: opOffset, KEY_PARSE, "IN 列表格式不正确")
            }
        }
        if (values.isEmpty()) {
            throw SqlReverseException(opOffset, KEY_PARSE, "IN 列表为空")
        }
        return Condition.In(column, negated, values)
    }

    /** BETWEEN lo AND hi。 */
    private fun parseBetween(cursor: Cursor, column: ColumnRef, negated: Boolean, opOffset: Int): Condition {
        val lo = parseValue(cursor)
            ?: throw SqlReverseException(cursor.previousOffset(), KEY_PARSE, "BETWEEN 不支持 NULL 边界")
        if (!cursor.isKeyword(cursor.current, "AND")) {
            throw SqlReverseException(cursor.current?.offset ?: opOffset, KEY_PARSE, "BETWEEN 缺少 AND")
        }
        cursor.advance()
        val hi = parseValue(cursor)
            ?: throw SqlReverseException(cursor.previousOffset(), KEY_PARSE, "BETWEEN 不支持 NULL 边界")
        return Condition.Between(column, negated, lo, hi)
    }

    /**
     * LIKE '模式'：按通配符位置映射 [LikePosition]（ANYWHERE→like 家族）。
     * 模式含 `_`/转义/中间 `%`/无通配符 → 无法用 like 家族表达 → 降级 [Condition.Raw]
     * 透传 `列 LIKE 原文`（生成器渲染为 apply()），并记入 warnings（进生成代码注释）。
     */
    private fun parseLike(
        cursor: Cursor,
        column: ColumnRef,
        negated: Boolean,
        opOffset: Int,
        warnings: MutableList<String>,
    ): Condition {
        val t = cursor.current
            ?: throw SqlReverseException(opOffset, KEY_PARSE, "LIKE 后缺少模式")
        // `LIKE ?` 占位符模式（`.like(Entity::getX, param)` 正向产物）：无法判断通配符位置 →
        // LikePosition.NONE（生成 like/notLike，参数由用户替换）
        if (t.type == TokenType.SYMBOL && t.text == "?") {
            cursor.advance()
            return Condition.Like(column, negated, SqlValue.Placeholder("?"), LikePosition.NONE)
        }
        if (t.type != TokenType.STRING) {
            throw SqlReverseException(t.offset, KEY_PARSE, "LIKE 模式必须是字符串字面量")
        }
        cursor.advance()
        val pattern = decodeString(t.text)
        val position = likePositionOf(pattern)
        if (position == null) {
            warnings.add("LIKE 模式 $pattern 无法映射为 like 家族方法，已用 apply() 透传")
            return Condition.Raw(
                "${column.columnName} ${if (negated) "NOT LIKE" else "LIKE"} ${t.text}",
            )
        }
        return Condition.Like(
            column,
            negated,
            SqlValue.Literal(raw = t.text, unquoted = stripWildcards(pattern)),
            position,
        )
    }

    /** 模式 → LikePosition；无法映射（_ / \ / 中间 % / 无 %）返回 null（降级 apply）。 */
    private fun likePositionOf(pattern: String): LikePosition? {
        if ('_' in pattern || '\\' in pattern) return null
        if ('%' !in pattern) return null
        return when {
            pattern.startsWith("%") && pattern.endsWith("%") && pattern.length >= 2 -> LikePosition.ANYWHERE
            pattern.startsWith("%") -> LikePosition.LEFT
            pattern.endsWith("%") -> LikePosition.RIGHT
            else -> null // 中间 %（如 'a%b'）
        }
    }

    /** 去掉模式首尾通配符得核心值（like 家族参数：`%abc%` → `abc`）。 */
    private fun stripWildcards(pattern: String): String = pattern.trim('%')

    // ------------------------------------------------------------------
    // 值解析
    // ------------------------------------------------------------------

    /**
     * 解析条件值。返回 null 表示 NULL 字面量（由调用方决定语义）。
     *
     * 支持：字符串（保留引号原文 + unquoted 解转义）、数字、TRUE/FALSE、`?` 占位符。
     * 明确不支持：函数/子查询/列引用（列对列）。
     */
    private fun parseValue(cursor: Cursor): SqlValue? {
        val t = cursor.current
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "条件缺少值")
        return when (t.type) {
            TokenType.STRING -> {
                cursor.advance()
                SqlValue.Literal(raw = t.text, unquoted = decodeString(t.text))
            }
            TokenType.NUMBER -> {
                cursor.advance()
                SqlValue.Literal(t.text)
            }
            TokenType.WORD -> when (t.text.uppercase()) {
                "TRUE", "FALSE" -> {
                    cursor.advance()
                    SqlValue.Literal(t.text.lowercase())
                }
                "NULL" -> {
                    cursor.advance()
                    null
                }
                "DATE", "TIMESTAMP" -> {
                    // ANSI 类型字面量（T-SqlToJavaPromote G10）：DATE '2024-01-01' / TIMESTAMP '...'
                    // raw 保留前缀（往返渲染一致）；后续须是字符串字面量
                    val next = cursor.peek()
                    if (next == null || next.type != TokenType.STRING) {
                        throw SqlReverseException(t.offset, KEY_UNSUPPORTED, "类型字面量 ${t.text} 后缺少字符串")
                    }
                    val prefix = t.text.uppercase()
                    cursor.advance()
                    cursor.advance()
                    SqlValue.Literal(raw = "$prefix ${next.text}", unquoted = decodeString(next.text))
                }
                else -> throw SqlReverseException(
                    t.offset,
                    KEY_UNSUPPORTED,
                    "标识符值 ${t.text}（列对列比较/函数表达式暂不支持）",
                )
            }
            TokenType.SYMBOL -> when (t.text) {
                // 参数占位符 → Placeholder（生成 "?" 占位参数由用户替换；round-trip 自洽）
                "?" -> {
                    cursor.advance()
                    SqlValue.Placeholder("?")
                }
                "(" -> throw SqlReverseException(t.offset, KEY_UNSUPPORTED, "子查询/表达式")
                else -> throw SqlReverseException(t.offset, KEY_PARSE, "条件缺少值")
            }
        }
    }

    // ------------------------------------------------------------------
    // ORDER BY / LIMIT
    // ------------------------------------------------------------------

    /** ORDER BY 列 [ASC|DESC]，逗号分隔（默认 ASC）。 */
    private fun parseOrderBy(cursor: Cursor): List<OrderByItem> {
        val items = mutableListOf<OrderByItem>()
        while (true) {
            val column = parseColumnRef(cursor)
            var asc = true
            val t = cursor.current
            if (t != null && t.type == TokenType.WORD) {
                when (t.text.uppercase()) {
                    "ASC" -> cursor.advance()
                    "DESC" -> {
                        asc = false
                        cursor.advance()
                    }
                }
            }
            items.add(OrderByItem(column, asc))
            if (cursor.isSymbol(cursor.current, ",")) {
                cursor.advance()
                continue
            }
            break
        }
        return items
    }

    /**
     * LIMIT 形态（T-M43 输入侧方言宽容）：
     *  - `LIMIT count`（通用）
     *  - `LIMIT offset, count`（MySQL/MariaDB/DM）
     *  - `LIMIT count OFFSET offset`（PostgreSQL/H2/SQLite/KingBase）
     * 值可为数字或 `?` 占位符（分页参数运行期值）。
     */
    private fun parseLimit(cursor: Cursor): LimitClause {
        val first = parseLimitValue(cursor)
        if (cursor.isSymbol(cursor.current, ",")) {
            cursor.advance()
            val second = parseLimitValue(cursor)
            return LimitClause(
                offset = first,
                count = second,
            )
        }
        if (cursor.isKeyword(cursor.current, "OFFSET")) {
            cursor.advance()
            return LimitClause(
                offset = parseLimitValue(cursor),
                count = first,
            )
        }
        return LimitClause(count = first)
    }

    /**
     * `OFFSET n ROWS FETCH NEXT m ROWS ONLY`（SQL Server 2012+ / Oracle 12c+ / DM；n/m 可为数字或 `?`）。
     */
    private fun parseOffsetFetch(cursor: Cursor): LimitClause {
        val offset = parseLimitValue(cursor)
        expectKeyword(cursor, "ROWS")
        // T-HiddenBugPerfScan 券 Y12：OFFSET n ROWS 单独出现是 SQL Server 2012+ 合法分页，
        // 但 LimitClause.count 非空、模型不可表达「无 count」——保持拒绝，但把 KEY_PARSE
        // 的「缺少关键字 FETCH」换成可行动文案（声明支持面内的缺口如实指路）。
        val afterRows = cursor.peek()
        if (afterRows == null || !cursor.isKeyword(afterRows, "FETCH") &&
            !cursor.isKeyword(afterRows, "FIRST") && !cursor.isKeyword(afterRows, "NEXT")
        ) {
            throw SqlReverseException(
                afterRows?.offset ?: cursor.offsetAtEnd(),
                KEY_PARSE,
                "OFFSET n ROWS 单独形态暂不支持（模型需成对 FETCH）——请补 FETCH NEXT m ROWS ONLY 或改用 OFFSET m ROWS FETCH NEXT",
            )
        }
        expectKeyword(cursor, "FETCH")
        expectKeyword(cursor, "NEXT")
        val count = parseLimitValue(cursor)
        expectKeyword(cursor, "ROWS")
        expectKeyword(cursor, "ONLY")
        return LimitClause(offset = offset, count = count)
    }

    /**
     * `FETCH FIRST n ROWS ONLY`（Oracle 12c+ 无 offset 形态；`FETCH NEXT` 亦接受）。
     */
    private fun parseFetchFirst(cursor: Cursor): LimitClause {
        val t = cursor.current
        if (t == null || !(isKeyword(t, "FIRST") || isKeyword(t, "NEXT"))) {
            throw SqlReverseException(t?.offset ?: cursor.offsetAtEnd(), KEY_PARSE, "FETCH 后缺少 FIRST/NEXT")
        }
        cursor.advance()
        val count = parseLimitValue(cursor)
        expectKeyword(cursor, "ROWS")
        expectKeyword(cursor, "ONLY")
        return LimitClause(count = count)
    }

    /** LIMIT 值：数字字面量或 `?` 占位符（日志场景分页参数）。 */
    private fun parseLimitValue(cursor: Cursor): SqlValue {
        val t = cursor.current
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "LIMIT 缺少行数")
        return when {
            t.type == TokenType.NUMBER -> {
                cursor.advance()
                SqlValue.Literal(t.text)
            }
            t.type == TokenType.SYMBOL && t.text == "?" -> {
                cursor.advance()
                SqlValue.Placeholder("?")
            }
            else -> throw SqlReverseException(t.offset, KEY_PARSE, "LIMIT 必须是数字或 ? 占位符")
        }
    }

    // ------------------------------------------------------------------
    // 列引用 / 通用工具
    // ------------------------------------------------------------------

    /** 列引用（T-M37：保留表别名供多表归属）：`col` / `t.col` / `` `col` `` / `` `t`.`col` ``。 */
    private fun parseColumnRef(cursor: Cursor): ColumnRef {
        val text = consumeQualified(cursor)
        val parts = text.split('.')
        val alias = if (parts.size > 1) parts.dropLast(1).joinToString(".").trim('`').ifEmpty { null } else null
        return ColumnRef(tableAlias = alias, columnName = parts.last().trim('`'))
    }

    /** 消费 `词[.词]` 组合（点分反引号是 3 个 token）。 */
    private fun consumeQualified(cursor: Cursor): String {
        val t = cursor.current
            ?: throw SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "缺少列名/表名")
        if (t.type != TokenType.WORD) {
            throw SqlReverseException(t.offset, KEY_PARSE, "缺少列名/表名")
        }
        val sb = StringBuilder(t.text)
        cursor.advance()
        if (cursor.isSymbol(cursor.current, ".")) {
            cursor.advance()
            val after = cursor.current
            if (after != null && after.type == TokenType.WORD) {
                sb.append('.').append(after.text)
                cursor.advance()
            }
        }
        return sb.toString()
    }

    /**
     * 剥点分前缀与反引号，取末段：`t.col`→`col`、`` `t`.`col` ``→`col`、`db.user`→`user`、
     * `` `col` ``→`col`。
     */
    private fun stripQualified(text: String): String {
        val last = text.substringAfterLast('.')
        return last.trim('`')
    }

    /** 字符串解转义：去首尾引号 + `''` → `'`。 */
    private fun decodeString(quoted: String): String {
        val inner = quoted.substring(1, quoted.length - 1)
        return inner.replace("''", "'")
    }

    private fun expectKeyword(cursor: Cursor, kw: String) {
        val t = cursor.current
        if (t == null || t.type != TokenType.WORD || !t.text.equals(kw, ignoreCase = true)) {
            throw SqlReverseException(t?.offset ?: cursor.offsetAtEnd(), KEY_PARSE, "缺少关键字 $kw")
        }
        cursor.advance()
    }

    private fun expectSymbol(cursor: Cursor, symbol: String) {
        val t = cursor.current
        if (t == null || t.type != TokenType.SYMBOL || t.text != symbol) {
            throw SqlReverseException(t?.offset ?: cursor.offsetAtEnd(), KEY_PARSE, "缺少符号 $symbol")
        }
        cursor.advance()
    }

    private fun unsupported(cursor: Cursor, what: String): Nothing {
        val t = cursor.current
        throw SqlReverseException(t?.offset ?: cursor.offsetAtEnd(), KEY_UNSUPPORTED, what)
    }

    /** 偏移量 → 行号（1 起）。 */
    internal fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

    // ------------------------------------------------------------------
    // bundle key（UI 层翻译；纯字符串引用，parser 保持零 IDE 依赖）
    // ------------------------------------------------------------------

    const val KEY_NOT_SELECT = "reverse.error.notSelect"
    const val KEY_UNSUPPORTED = "reverse.error.unsupported"
    const val KEY_PARSE = "reverse.error.parse"

    /** Token 游标。 */
    private class Cursor(val tokens: List<Token>, val source: String) {
        var pos = 0

        val current: Token? get() = tokens.getOrNull(pos)

        fun peek(): Token? = tokens.getOrNull(pos + 1)

        fun advance(): Token? {
            val t = current
            if (t != null) pos++
            return t
        }

        fun previousOffset(): Int = tokens.getOrNull(pos - 1)?.offset ?: 0

        fun offsetAtEnd(): Int = tokens.lastOrNull()?.offset ?: 0

        fun isKeyword(t: Token?, kw: String): Boolean =
            t != null && t.type == TokenType.WORD && t.text.equals(kw, ignoreCase = true)

        fun isSymbol(t: Token?, symbol: String): Boolean =
            t != null && t.type == TokenType.SYMBOL && t.text == symbol
    }
}

