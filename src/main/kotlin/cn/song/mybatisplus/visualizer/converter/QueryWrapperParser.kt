package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.ConditionToggle
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlFragment
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.psi.currentDialect
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.SqlGenerator

/**
 * LambdaQueryWrapper → SelectStatement 转换器（M1 核心，F-01）。
 *
 * 遍历 [WrapperChain.calls]，按方法名分派构建 SELECT 语句的各个部分。
 * 纯逻辑、无 PSI 依赖，可独立单测。
 *
 * @param table        实体表信息
 * @param columnPrefix 列引用表别名前缀（MPJ 联表场景传主表别名 "t"；单表查询为 null）
 *
 * 已知简化（后续里程碑补齐）：
 *  - `or()` 无参调用（切换连接符）暂不建模，M1 条件间为隐式 AND
 *  - `having` 仅支持单条件
 *  - 集合实参（`in(column, collection)`）由 psi 层展开为多值
 */
class QueryWrapperParser(
    private val table: TableInfo,
    private val columnPrefix: String? = null,
    /**
     * T-M23.5：方言（解析期 raw 拼接的保留字引用；默认读当前设置，测试环境兜底 MySQL）。
     * 子查询递归（[subQueryCondition]）沿用同一方言。
     */
    private val dialect: Dialect = currentDialect(),
) {

    /** 动态条件 toggle id 序号（M12，pre-order 递增，同一 parse 树内稳定）。 */
    private var toggleSeq = 0

    /**
     * 解析调用链为 SELECT 语句模型。
     *
     * @param chain PSI 解析产物
     * @return 可直接交给 SqlGenerator 的语句模型
     */
    fun parse(chain: WrapperChain): SelectStatement {
        toggleSeq = 0
        return parseInternal(chain)
    }

    /** 递归解析体：顶层 parse 重置 toggleSeq，嵌套调用走本方法（id 延续同一序列）。 */
    private fun parseInternal(chain: WrapperChain): SelectStatement {
        val selectColumns = mutableListOf<ColumnRef>()
        val conditions = mutableListOf<Condition>()
        val groupBy = mutableListOf<ColumnRef>()
        var having: Condition? = null
        val orderBy = mutableListOf<OrderByItem>()
        var limit: LimitClause? = null
        var rawFirst: String? = null
        var rawFirstParams: List<String> = emptyList()
        var comment: String? = null
        var distinct = false
        var orPending = false

        for (rawCall in chain.calls) {
            // IfExists / IfNotEmpty 条件族（MPJ 官方 API）：eqIfExists/neIfExists/likeIfExists、
            // inIfNotEmpty/notInIfNotEmpty 等——「值存在才拼接」的运行时语义，静态渲染为普通条件
            // （T-M21-09：inIfNotEmpty/notInIfNotEmpty 与 IfExists 同法剥离后缀归一化）
            val effectiveCall = when {
                rawCall.methodName.endsWith("IfExists") ->
                    rawCall.copy(methodName = rawCall.methodName.removeSuffix("IfExists"))
                rawCall.methodName.endsWith("IfNotEmpty") ->
                    rawCall.copy(methodName = rawCall.methodName.removeSuffix("IfNotEmpty"))
                else -> rawCall
            }
            // 布尔条件重载（P02/M12）：eq(boolean, col, val) 首参为布尔标志 →
            // 剥离标志并固化为动态条件 toggle 元数据（不再丢弃 false 条件，改由生成层默认排除）
            val analysis = effectiveCall.analyzeFlag()
            val call = analysis?.call ?: effectiveCall
            if (call.args.isEmpty() && call.methodName in CONDITION_METHODS) continue

            when (call.methodName) {
                // ---------- SELECT 列 ----------
                "select" -> {
                    // T-M21-01：select(Class, Predicate)（MP 3.4.3.2+）→ 谓词过滤列；其余形态保持原列解析
                    val classRef = call.args.getOrNull(0) as? WrapperArg.EntityRefArg
                    val predicate = call.args.getOrNull(1) as? WrapperArg.PredicateArg
                    selectColumns += if (classRef != null && predicate != null) {
                        selectColumnsByPredicate(predicate)
                    } else {
                        // T-UpdateSetSqlConcat：字符串表达式按片段解析（运行期段写 `?` 并带来源，
                        // 在 SELECT 列表渲染点按序登记；列引用（Lambda）不受影响）
                        selectColumnArgs(call)
                    }
                }
                // T-M21-01：selectFilter(Class, Predicate<SelectCache>)（MPJ）→ 按谓词白/黑名单过滤列
                "selectFilter" -> {
                    val predicate = call.args.firstOrNull { it is WrapperArg.PredicateArg } as? WrapperArg.PredicateArg
                    selectColumns += if (predicate != null) selectColumnsByPredicate(predicate) else emptyList()
                }
                "selectMax" -> selectColumns += parseAggregate(call, "MAX")
                "selectSum" -> selectColumns += parseAggregate(call, "SUM")
                "selectMin" -> selectColumns += parseAggregate(call, "MIN")
                "selectAvg" -> selectColumns += parseAggregate(call, "AVG")
                "selectLen" -> selectColumns += parseAggregate(call, "LENGTH")
                "selectCount" -> selectColumns += parseAggregate(call, "COUNT")
                "selectFunc" -> selectColumns += parseSelectFunc(call)
                "distinct" -> distinct = true

                // ---------- 二元比较 ----------
                "eq" -> orPending = addConditionFlagged(conditions, simple(call, "=").flagged(analysis), orPending)
                // eqOrIsNull（MP 3.5.17 官方 API，T-M20-09）：值静态求值为 null → IS NULL，否则 = 值
                "eqOrIsNull" -> orPending = addConditionFlagged(conditions, eqOrIsNull(call).flagged(analysis), orPending)
                // ne 渲染 <>（M38.2 语义对照矩阵实证：MP SqlKeyword.NE = "<>"，原 "!=" 与 MP 不一致）
                "ne" -> orPending = addConditionFlagged(conditions, simple(call, "<>").flagged(analysis), orPending)
                "gt" -> orPending = addConditionFlagged(conditions, simple(call, ">").flagged(analysis), orPending)
                "ge" -> orPending = addConditionFlagged(conditions, simple(call, ">=").flagged(analysis), orPending)
                "lt" -> orPending = addConditionFlagged(conditions, simple(call, "<").flagged(analysis), orPending)
                "le" -> orPending = addConditionFlagged(conditions, simple(call, "<=").flagged(analysis), orPending)
                // 比较 SQL 片段（eqSql/gtSql/geSql/ltSql/leSql，3.4.3.2+/3.5.6，官方 API）：`col op (sql)`
                "eqSql" -> orPending = addConditionFlagged(conditions, compareSql(call, "=").flagged(analysis), orPending)
                "gtSql" -> orPending = addConditionFlagged(conditions, compareSql(call, ">").flagged(analysis), orPending)
                "geSql" -> orPending = addConditionFlagged(conditions, compareSql(call, ">=").flagged(analysis), orPending)
                "ltSql" -> orPending = addConditionFlagged(conditions, compareSql(call, "<").flagged(analysis), orPending)
                "leSql" -> orPending = addConditionFlagged(conditions, compareSql(call, "<=").flagged(analysis), orPending)
                // T-M21-04：allEq（MP 官方 API，6 重载）——Map 键值对展开为多条 `col = ?` AND 连接
                "allEq" -> orPending = addConditionFlagged(conditions, parseAllEq(call).flagged(analysis), orPending)
                // T-M21-05：setEntity(entity)（MP 官方 API）——实体全部列 `col = ?`（静态近似）
                "setEntity" -> {
                    parseSetEntity().forEach { cond ->
                        orPending = addConditionFlagged(conditions, cond.flagged(analysis), orPending)
                    }
                }

                // ---------- 模糊 ----------
                "like" -> orPending = addConditionFlagged(conditions, like(call, negated = false, LikePosition.ANYWHERE).flagged(analysis), orPending)
                "notLike" -> orPending = addConditionFlagged(conditions, like(call, negated = true, LikePosition.ANYWHERE).flagged(analysis), orPending)
                "likeLeft" -> orPending = addConditionFlagged(conditions, like(call, negated = false, LikePosition.LEFT).flagged(analysis), orPending)
                "likeRight" -> orPending = addConditionFlagged(conditions, like(call, negated = false, LikePosition.RIGHT).flagged(analysis), orPending)
                "notLikeLeft" -> orPending = addConditionFlagged(conditions, like(call, negated = true, LikePosition.LEFT).flagged(analysis), orPending)
                "notLikeRight" -> orPending = addConditionFlagged(conditions, like(call, negated = true, LikePosition.RIGHT).flagged(analysis), orPending)

                // ---------- 范围 ----------
                "in" -> orPending = addConditionFlagged(conditions, inList(call, negated = false).flagged(analysis), orPending)
                "notIn" -> orPending = addConditionFlagged(conditions, inList(call, negated = true).flagged(analysis), orPending)
                "between" -> orPending = addConditionFlagged(conditions, between(call, negated = false).flagged(analysis), orPending)
                "notBetween" -> orPending = addConditionFlagged(conditions, between(call, negated = true).flagged(analysis), orPending)
                "inSql" -> orPending = addConditionFlagged(conditions, inSql(call, negated = false).flagged(analysis), orPending)
                "notInSql" -> orPending = addConditionFlagged(conditions, inSql(call, negated = true).flagged(analysis), orPending)

                // ---------- 空值 ----------
                "isNull" -> orPending = addConditionFlagged(conditions, unary(call, "IS NULL").flagged(analysis), orPending)
                "isNotNull" -> orPending = addConditionFlagged(conditions, unary(call, "IS NOT NULL").flagged(analysis), orPending)

                // ---------- 自定义片段 ----------
                // M19.6：applyFunc/exists/notExists 同样挂复选框（无布尔重载时 flagSource 为 null）；
                // apply 的布尔前缀与 toggle 由 parseApply 自含（parseRawTemplate），此处不加 flagged 避免覆盖
                "apply" -> orPending = addConditionFlagged(conditions, parseApply(call), orPending)
                "applyFunc" -> orPending = addConditionFlagged(conditions, parseApplyFunc(call).flagged(analysis), orPending)
                "exists" -> orPending = addConditionFlagged(conditions, parseExists(call, negated = false).flagged(analysis), orPending)
                "notExists" -> orPending = addConditionFlagged(conditions, parseExists(call, negated = true).flagged(analysis), orPending)

                // ---------- 结构 ----------
                "groupBy" -> groupBy += columnArgs(call)
                // groupByStr（MPJ 字符串版 groupBy，1.4.x 官方 API，T-M20-04）：字符串列原样（可含表别名）
                "groupByStr" -> groupBy += columnArgs(call)
                "having" -> having = parseHaving(call)
                "orderByAsc" -> orderBy += orderItems(call, asc = true)
                "orderByDesc" -> orderBy += orderItems(call, asc = false)
                // orderByStr（MPJ 字符串版 orderBy，T-M20-04）：字符串列原样、默认 ASC（MPJ 语义：orderByStr 恒升序）
                "orderByStr" -> orderBy += orderByStrItems(call)
                // orderBy(condition?, isAsc, R...)：condition 布尔前缀（orderBy 不在 CONDITION_METHODS，手动剥离）
                // + isAsc 布尔字面量（true=ASC，false=DESC）
                "orderBy" -> {
                    var orderArgs = call.args
                    val condValue = orderArgs.getOrNull(0).asValue()
                    if (orderArgs.size >= 3 && condValue is SqlValue.Literal &&
                        (condValue.unquoted == "true" || condValue.unquoted == "false")
                    ) {
                        orderArgs = orderArgs.drop(1)
                    }
                    val ascValue = orderArgs.getOrNull(0).asValue()
                    val asc = ascValue is SqlValue.Literal &&
                        (ascValue.unquoted == "true" || ascValue.unquoted == "false")
                    val columns = if (asc) call.copy(args = orderArgs.drop(1)) else call.copy(args = orderArgs)
                    orderBy += orderItems(columns, if (asc) ascValue.unquoted == "true" else true)
                }
                "last" -> limit = parseLimit(call)
                // first / comment（官方 API，M15 增补）：SQL 最前片段与注释原样透传
                // T-UpdateSetSqlConcat：片段位取 asSqlFragment；first 的来源随片段登记（SELECT 之前渲染）
                // ——comment 的 `?` 落在 `/* ... */` 注释里，placeholderPositions 不计，故无来源
                "first" -> call.args.firstOrNull().asSqlFragment()?.let {
                    rawFirst = it.text
                    rawFirstParams = it.params
                }
                "comment" -> comment = call.args.firstOrNull().asSqlFragment()?.text

                // ---------- 逻辑分组 ----------
                // 无参 or()（P14）：与前一条件组成 OR 分组（分隔符）；有参 and/or → 嵌套 lambda 分组
                // T-M29-01：分组方法布尔条件重载（`and(condition, w -> ...)`）——剥离标志、
                // 组挂动态条件开关（此前首参布尔被当 NestedArg 取 → 整组静默丢弃）
                "or" -> if (call.args.isEmpty()) {
                    orPending = true
                } else {
                    // M38.2：or(Consumer) = 外层 OR 连接 + 组内恒 AND（MP 语义）。强制 orPending
                    // 让组与前一条件组成 OR 分组（addCondition 的 OR 语义），无前条件时组直接落位。
                    // 原实现把 OR 用作组内连接词 → or(w -> w.eq(2).eq(3)) 错渲染为 `AND (2 OR 3)`（内外颠倒）。
                    val group = parseGroupFlagged(call)
                    if (group != null) {
                        orPending = addConditionFlagged(conditions, group, true)
                    }
                }
                "and" -> orPending = addConditionFlagged(conditions, parseGroupFlagged(call), orPending)
                // nested(consumer)：独立括号块（官方 API，语义与 and 等价——隐式 AND 连接）
                "nested" -> orPending = addConditionFlagged(conditions, parseGroupFlagged(call), orPending)
                // not(consumer)：NOT (条件)（官方 API）
                "not" -> orPending = addConditionFlagged(conditions, parseNotFlagged(call), orPending)
                // func(consumer)：条件分支动态构建（官方 API）——consumer 内条件按 AND 直接展开，不产生括号组；
                // func(condition, consumer) 布尔重载（T-M29-01）→ 子条件包成 AND 组挂开关（默认勾选按标志）
                "func" -> {
                    val analysis = stripGroupFlag(call)
                    val effective = analysis?.call ?: call
                    val nested = effective.args.firstOrNull() as? WrapperArg.NestedArg
                    if (nested != null) {
                        val children = parseInternal(nested.chain).conditions
                        if (analysis != null) {
                            if (children.isNotEmpty()) {
                                orPending = addConditionFlagged(
                                    conditions,
                                    Condition.Group(Connector.AND, children).flagged(analysis),
                                    orPending,
                                )
                            }
                        } else {
                            conditions += children
                        }
                    }
                }

                // 其他方法（not/嵌套条件等）暂忽略，保证健壮性
            }
        }

        return SelectStatement(
            table = table,
            selectColumns = selectColumns,
            distinct = distinct,
            conditions = conditions,
            groupBy = groupBy,
            having = having,
            orderBy = orderBy,
            limit = limit,
            rawFirst = rawFirst,
            rawFirstParams = rawFirstParams,
            comment = comment,
        )
    }

    /**
     * 解析聚合列（selectCount/selectSum/selectMin/selectMax/selectAvg/selectLen，官方 API）：
     * `selectCount(Entity::getX, VO::getY)` → `COUNT(列) [AS 别名]`；selectLen → `LENGTH(列)`。
     *
     * 第一参为列（带主表前缀）；第二参为 [WrapperArg.AliasArg]（VO 属性名，保留驼峰）或字符串别名。
     */
    private fun parseAggregate(call: WrapperCall, function: String): List<ColumnRef> {
        val column = columnArgs(call).firstOrNull() ?: return emptyList()
        val alias = when (val arg = call.args.getOrNull(1)) {
            is WrapperArg.AliasArg -> arg.propertyName
            is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.unquoted
            else -> null
        }
        return listOf(if (alias != null) column.copy(function = function, alias = alias) else column.copy(function = function))
    }

    /**
     * 解析 selectFunc(模板, func -> func.accept(列).values(值), 别名) → 完整聚合列（清单 P09）。
     *
     * 模板规则（对齐 MPJ）：
     *  - `%s`：列占位符，按 [WrapperArg.FuncArg.columns] 顺序替换（列带主表别名前缀）
     *  - `{0}`/`{1}`：绑定参数占位符，按 [WrapperArg.FuncArg.values] 顺序替换（字面量原样、运行时值渲染为 `?`）
     *
     * T-M21-06：枚举形态 `selectFunc(DefaultFuncEnum.SUM, col, alias)` 与 String 模板形态并存——
     * 第一参为枚举引用占位（source 形如 "DefaultFuncEnum.SUM" / "SUM"）→ 映射内置函数模板，
     * 列取 [WrapperArg.FuncArg.columns] 第一列（带主表别名）、别名取第三参（AliasArg/ValueArg）。
     */
    private fun parseSelectFunc(call: WrapperCall): List<ColumnRef> {
        val first = call.args.getOrNull(0).asValue()
        val func = call.args.getOrNull(1) as? WrapperArg.FuncArg ?: return emptyList()
        val alias = when (val arg = call.args.getOrNull(2)) {
            is WrapperArg.AliasArg -> arg.propertyName
            is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.unquoted
            else -> null
        }
        // 枚举形态（T-M21-06）：`SUM(t.col) [AS alias]`
        if (first is SqlValue.Placeholder) {
            val fn = enumFunctionOf(first.source) ?: return emptyList()
            val column = func.columns.firstOrNull() ?: return emptyList()
            val qualified = columnPrefix?.let { column.withTableAlias(it) }?.qualifiedName(dialect) ?: column.qualifiedName(dialect)
            val expr = "$fn($qualified)"
            return listOf(ColumnRef(columnName = expr, rawExpr = expr, alias = alias))
        }
        // T-UpdateSetSqlConcat：片段位取 asSqlFragment（模板含运行期段时静态段保留、运行期段写 `?`）
        val template = call.args.getOrNull(0).asSqlFragment()?.text ?: return emptyList()

        var expr = template
        // %s 列占位替换（逐列、带主表别名前缀）；T-M23.5 列名按方言引用
        for (col in func.columns) {
            if (!expr.contains("%s")) break
            expr = expr.replaceFirst("%s", columnPrefix?.let { col.withTableAlias(it) }?.qualifiedName(dialect) ?: col.qualifiedName(dialect))
        }
        // {0}/{1} 绑定参数替换
        for ((index, value) in func.values.withIndex()) {
            expr = expr.replace("{$index}", renderFuncValue(value))
        }
        if (expr.contains("%s")) return emptyList() // 列数不足，无法完整渲染
        return listOf(ColumnRef(columnName = expr, rawExpr = expr, alias = alias))
    }

    /**
     * T-M21-06：MPJ 内置函数枚举名 → SQL 函数名映射（未知枚举返回 null，调用方忽略不崩溃）。
     * 枚举引用占位 source 形如 `DefaultFuncEnum.SUM` / `SUM`（取点号后末段、大写归一）。
     */
    private fun enumFunctionOf(source: String): String? {
        val enumName = source.substringAfterLast('.').trim().uppercase()
        return when (enumName) {
            "SUM" -> "SUM"
            "COUNT" -> "COUNT"
            "MAX" -> "MAX"
            "MIN" -> "MIN"
            "AVG" -> "AVG"
            "LEN" -> "LENGTH"
            "CONCAT" -> "CONCAT"
            else -> null
        }
    }

    /** selectFunc 绑定参数渲染：字面量原样（raw），运行时值渲染为 `?`。 */
    private fun renderFuncValue(value: SqlValue): String = when (value) {
        is SqlValue.Literal -> value.raw
        is SqlValue.Placeholder -> "?"
    }

    // ------------------------------------------------------------------
    // 各操作解析
    // ------------------------------------------------------------------

    /** 二元比较：`column op value`；列对列（MPJ 子查询内 `eq(子表列, 主表列)`）与子查询形态。 */
    private fun simple(call: WrapperCall, op: String): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        // 子查询条件：eq(col, SubEntity.class, w -> w.select(...).eq(...)) → col = (SELECT ...)
        if (call.args.size >= 3 && call.args.getOrNull(2) is WrapperArg.NestedArg) {
            return subQueryCondition(call, op)
        }
        // 列对列比较（MPJ 官方）：eq(子表列, 主表列) → 列 = 列
        val right = call.args.getOrNull(1)
        if (right is WrapperArg.ColumnArg) {
            return Condition.ColumnComparison(left = column, right = right.column)
        }
        val value = argAsValue(call.args.getOrNull(1)) ?: return null
        return Condition.Simple(column, op, value)
    }

    /**
     * eqOrIsNull（MP 3.5.17 官方 API，T-M20-09）：`eqOrIsNull(col, val)` → 值实参静态求值为 null 时
     * 生成 `col IS NULL`，否则 `col = 值`（与 eq 同语义）；布尔重载（condition 首参）由 analyzeFlag 剥离。
     *
     * null 判定：psi 层 Java `null` 字面量经 ExpressionEvaluator 求值为 [SqlValue.Literal]（raw/unquoted
     * 均为 `NULL`，无引号）——与字符串 `"NULL"`（raw 为带引号的 `'NULL'`）通过 raw 区分。
     * 占位符（运行时值）恒走 `= ?`（运行时才能确定是否 null）。
     */
    private fun eqOrIsNull(call: WrapperCall): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        val value = argAsValue(call.args.getOrNull(1)) ?: return null
        return if (value is SqlValue.Literal && value.raw == "NULL") {
            Condition.Unary(column, "IS NULL")
        } else {
            Condition.Simple(column, "=", value)
        }
    }

    /**
     * allEq（T-M21-04，MP 官方 API）：`allEq(Map)` / `allEq(Map, null2IsNull)` /
     * `allEq(boolean, Map)` / `allEq(boolean, Map, null2IsNull)` / `allEq(Map, BiPredicate)` /
     * `allEq(boolean, Map, BiPredicate, null2IsNull)` —— Map 键值对展开为多条 `col = ?` AND 连接；
     * null2IsNull=true 且值静态判 null（[SqlValue.Literal] raw == "NULL"）→ `col IS NULL`。
     *
     * Map 变量（[WrapperArg.MapArg.entries] 为空，运行时条目不可知）→ 可读占位条件。
     * 布尔条件前缀（condition 首参）由 [analyzeFlag] 剥离；null2IsNull 由 psi 层解析进 [WrapperArg.MapArg]。
     */
    private fun parseAllEq(call: WrapperCall): Condition? {
        val mapArg = call.args.firstOrNull { it is WrapperArg.MapArg } as? WrapperArg.MapArg ?: return null
        if (mapArg.entries.isEmpty()) {
            // 变量 Map：运行时键值对不可静态展开，渲染可读占位（近似语义）
            return Condition.Raw("<Map 等值条件: col = ? AND col = ? ...>")
        }
        val children = mapArg.entries.mapNotNull { (column, value) ->
            val qualified = columnPrefix?.let { column.withTableAlias(it) } ?: column
            if (mapArg.null2IsNull && value is SqlValue.Literal && value.raw == "NULL") {
                Condition.Unary(qualified, "IS NULL")
            } else {
                Condition.Simple(qualified, "=", value)
            }
        }
        if (children.isEmpty()) return null
        return Condition.Group(Connector.AND, children)
    }

    /**
     * setEntity（T-M21-05，MP 官方 API）：`setEntity(entity)` → 实体**非 null 字段**追加 where 等值条件。
     *
     * 静态近似：运行时实体字段值不可知（实参变量忽略具体值），实体全部列展开为 `col = ?`
     * （列带主表别名前缀，与条件渲染一致；注释标注近似，运行时仅非 null 字段生效）。
     */
    private fun parseSetEntity(): List<Condition> = table.columns.map { col ->
        val column = ColumnRef(columnName = col.columnName)
        val qualified = columnPrefix?.let { column.withTableAlias(it) } ?: column
        Condition.Simple(qualified, "=", SqlValue.Placeholder("?"))
    }

    /**
     * 子查询条件（官方 sub-query API，1.5.2+）：`eq(col, SubEntity.class, w -> w.select(...).between(...))`
     * → `col op (SELECT ... FROM sub_entity WHERE ...)`。子查询用**独立解析器**（不继承主表别名前缀），
     * 渲染为单行（compact）嵌入条件。
     */
    private fun subQueryCondition(call: WrapperCall, op: String): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        val subTable = (call.args.getOrNull(1) as? WrapperArg.EntityRefArg)?.tableInfo ?: return null
        val nested = call.args.getOrNull(2) as? WrapperArg.NestedArg ?: return null
        val subStatement = QueryWrapperParser(subTable, dialect = dialect).parse(nested.chain)
        val subSql = SqlGenerator.generate(subStatement, compact = true)
        return Condition.Raw("${column.qualifiedName(dialect)} $op ($subSql)")
    }

    /** LIKE 条件。 */
    private fun like(call: WrapperCall, negated: Boolean, position: LikePosition): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        val value = argAsValue(call.args.getOrNull(1)) ?: return null
        return Condition.Like(column, negated, value, position)
    }

    /** IN 列表条件：`column IN (v1, v2, ...)`；子查询形态 `column IN (SELECT ...)`（官方 sub-query API）。 */
    private fun inList(call: WrapperCall, negated: Boolean): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        // 子查询形态：in(col, SubEntity.class, w -> ...) → col IN (子查询)
        if (call.args.size >= 3 && call.args.getOrNull(2) is WrapperArg.NestedArg) {
            return subQueryCondition(call, if (negated) "NOT IN" else "IN")
        }
        val values = call.args.drop(1).mapNotNull { argAsValue(it) }
        if (values.isEmpty()) return null
        return Condition.In(column, negated, values)
    }

    /** BETWEEN 条件。 */
    private fun between(call: WrapperCall, negated: Boolean): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        val lo = argAsValue(call.args.getOrNull(1)) ?: return null
        val hi = argAsValue(call.args.getOrNull(2)) ?: return null
        return Condition.Between(column, negated, lo, hi)
    }

    /** IN SQL 片段条件（inSql / notInSql，官方 API）：`column IN (sql 片段)`（支持子查询）。 */
    private fun inSql(call: WrapperCall, negated: Boolean): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        // T-UpdateSetSqlConcat：片段位取 asSqlFragment（`"SELECT id FROM t WHERE x = " + x` → `... x = ?`）
        val frag = call.args.getOrNull(1).asSqlFragment() ?: return null
        val op = if (negated) "NOT IN" else "IN"
        return Condition.Raw("${column.qualifiedName(dialect)} $op (${frag.text})", params = frag.params)
    }

    /** 比较 SQL 片段条件（eqSql/gtSql/geSql/ltSql/leSql，官方 API）：`column op (sql 片段)`。 */
    private fun compareSql(call: WrapperCall, op: String): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        val frag = call.args.getOrNull(1).asSqlFragment() ?: return null
        return Condition.Raw("${column.qualifiedName(dialect)} $op (${frag.text})", params = frag.params)
    }

    /** NOT 嵌套条件（not(consumer)，官方 API）：`NOT (嵌套条件)`。 */
    private fun parseNot(call: WrapperCall): Condition? {
        val nested = call.args.firstOrNull() as? WrapperArg.NestedArg ?: return null
        val children = parseInternal(nested.chain).conditions
        if (children.isEmpty()) return null
        return Condition.Group(Connector.AND, children, negated = true)
    }

    /** 一元条件：IS NULL / IS NOT NULL。 */
    private fun unary(call: WrapperCall, op: String): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        return Condition.Unary(column, op)
    }

    /** 逻辑分组：and(lambda) / or(lambda) / and(wrapper) / or(wrapper)。 */
    private fun parseGroup(call: WrapperCall): Condition? {
        // M38.2（语义对照矩阵实证）：组内连接词恒为 AND——lambda 内条件默认 AND 连接，lambda 内
        // 显式无参 or() 由嵌套解析自身的 orPending 产生 OR 子组（下方展平保留）。组与前条件的
        // **外层**连接词由调用方的 orPending 表达：or(Consumer) → OR，and/nested → AND。
        // 原实现把方法名 or 用作组内连接词 → or(w -> w.eq(2).eq(3)) 错渲染为 `AND (2 OR 3)`（内外颠倒）。
        val connector = Connector.AND
        val nested = call.args.firstOrNull() as? WrapperArg.NestedArg ?: return null
        // 递归解析嵌套链的条件列表，按连接符组装为分组（parseInternal 延续 toggle id 序列）
        val children = parseInternal(nested.chain).conditions
        if (children.isEmpty()) return null
        // 展平单子组（M15-P0）：lambda 内 `w.eq(a).or().eq(b)` 的 or 分隔符已产生 Group(OR, [...])，
        // 直接返回该组避免 `AND ((a OR b))` 双层括号
        if (children.size == 1 && children[0] is Condition.Group) return children[0]
        return Condition.Group(connector, children)
    }

    /**
     * T-M29-01：分组方法布尔条件重载（`and(condition, w -> ...)` 等）——剥离布尔首参后
     * 解析嵌套组，组挂动态条件开关（默认勾选按标志：字面量 false 不勾选，运行时布尔勾选）。
     * 无布尔首参走原 [parseGroup]（不挂开关，回归不变）。
     */
    private fun parseGroupFlagged(call: WrapperCall): Condition? {
        val analysis = stripGroupFlag(call)
        val effective = analysis?.call ?: call
        val group = parseGroup(effective) ?: return null
        return if (analysis != null) group.flagged(analysis) else group
    }

    /** T-M29-01：not(condition, w -> ...) 布尔重载（与 [parseGroupFlagged] 同模式）。 */
    private fun parseNotFlagged(call: WrapperCall): Condition? {
        val analysis = stripGroupFlag(call)
        val effective = analysis?.call ?: call
        val group = parseNot(effective) ?: return null
        return if (analysis != null) group.flagged(analysis) else group
    }

    /** T-M29-01：支持布尔条件重载的分组方法（and/or/nested/not/func 的 condition 首参）。 */
    private val GROUP_FLAG_METHODS = setOf("and", "or", "nested", "not", "func")

    /**
     * T-M29-01：剥离分组方法的布尔条件首参（`and(StrUtil.isNotBlank(x), w -> ...)` 形态）。
     * 判定与 [analyzeFlag] 同一规则：首参仅 true/false 字面量或占位符（运行时布尔）为标志，
     * 首参即 NestedArg（无标志形态）或其他实参类型返回 null（走原逻辑）。
     */
    private fun stripGroupFlag(call: WrapperCall): FlagAnalysis? {
        if (call.methodName !in GROUP_FLAG_METHODS) return null
        val first = call.args.getOrNull(0) ?: return null
        if (first is WrapperArg.NestedArg) return null
        val firstValue = first.asValue() ?: return null
        val flagText = when (firstValue) {
            is SqlValue.Literal -> firstValue.unquoted
            is SqlValue.Placeholder -> firstValue.source
        }
        // 字符串字面量不可能是布尔标志（字符串版列名实参等），仅 true/false 字面量与占位符为条件重载
        if (firstValue is SqlValue.Literal && flagText != "true" && flagText != "false") return null
        val defaultEnabled = (firstValue as? SqlValue.Literal)?.unquoted != "false"
        return FlagAnalysis(WrapperCall(call.methodName, call.args.drop(1)), flagText, defaultEnabled)
    }

    /** HAVING：P1-06（M19.5）多条件支持——嵌套链**全部条件**组成 AND 组（M1 起仅取第一个）。 */
    private fun parseHaving(call: WrapperCall): Condition? {
        // M38.2（语义对照矩阵实证）：字符串版 having("count(*) > 3")（MP 官方 API）——
        // 原实现只认 lambda 嵌套形态，字符串片段被静默丢弃。按原生片段透传渲染。
        val first = call.args.firstOrNull()
        if (first is WrapperArg.ValueArg) {
            // T-UpdateSetSqlConcat：片段位取 asSqlFragment（`having("count(*) > " + n)` → `count(*) > ?`）
            first.asSqlFragment()?.let { frag ->
                if (frag.text.isNotBlank()) return Condition.Raw(frag.text, params = frag.params)
            }
        }
        val nested = call.args.firstOrNull() as? WrapperArg.NestedArg ?: return null
        val conditions = parseInternal(nested.chain).conditions
        if (conditions.isEmpty()) return null
        return if (conditions.size == 1) conditions.first() else Condition.Group(Connector.AND, conditions)
    }

    /** ORDER BY 项列表；支持 `orderByAsc("alias", Entity::getX)` 字符串别名前缀（清单 P11）。 */
    private fun orderItems(call: WrapperCall, asc: Boolean): List<OrderByItem> {
        val first = call.args.getOrNull(0)
        // 形式一：orderByAsc("alias", Entity::getX) —— 第一参为表别名。
        // T-HiddenBugPerfScan 券 N：别名分支加「第二参是 ColumnArg」判据——字符串版多列
        // orderByAsc("a","b") 的实参全为 ValueArg，曾把首参误判为别名且别名分支只收
        // ColumnArg → 字符串列全被 mapNotNull 过滤，ORDER BY 整体静默消失（单列形态
        // size==1 恰好走 fallback 掩盖了缺陷）。别名后跟 lambda 列才成立。
        if (first is WrapperArg.ValueArg && call.args.size >= 2 && call.args[1] is WrapperArg.ColumnArg) {
            val alias = (first.value as? SqlValue.Literal)?.unquoted
            if (!alias.isNullOrEmpty()) {
                return call.args.drop(1)
                    .mapNotNull { (it as? WrapperArg.ColumnArg)?.column?.withTableAlias(alias) }
                    .map { OrderByItem(it, asc) }
            }
        }
        // 形式二：orderByDesc(Entity::getX) / 字符串列
        return columnArgs(call).map { OrderByItem(it, asc) }
    }

    /**
     * orderByStr（MPJ 字符串版 orderBy，1.4.x 官方 API，T-M20-04）：`orderByStr("t.create_time", ...)` →
     * 字符串列原样（可含表别名）按 ASC 排序（MPJ 语义：orderByStr 恒升序，无 isAsc 参数）。
     *
     * 不复用 [orderItems]：其「首参为表别名」形态（orderByAsc("alias", Entity::getX)）会把多字符串列的
     * 首参误判为别名而静默丢弃（orderByStr 无别名重载，全部实参均为列）。
     */
    private fun orderByStrItems(call: WrapperCall): List<OrderByItem> =
        columnArgs(call).map { OrderByItem(it, asc = true) }

    /**
     * LIMIT 解析：last("limit 10") / last("limit 5, 10")（MP 语义：offset, count）。
     *
     * T-UpdateSetSqlConcat：运行期段渲染成 `?` 后按**占位形态**建模（`last("LIMIT " + n)`）——
     * [LimitClause] 本就持有 [SqlValue]，生成器的 limit 渲染点会按序登记占位符来源。
     */
    private fun parseLimit(call: WrapperCall): LimitClause? {
        val literalText = (argAsValue(call.args.firstOrNull()) as? SqlValue.Literal)?.unquoted
        if (literalText != null) return parseLimitText(literalText)
        val fragment = call.args.firstOrNull().asSqlFragment() ?: return null
        return parseLimitFragment(fragment)
    }

    /** 纯文本 LIMIT（既有口径：数字 / `5, 10` / `5 OFFSET 10`）。 */
    private fun parseLimitText(text: String): LimitClause? {
        val limitMatch = QWP_LIMIT_PATTERN.find(text) ?: return null
        val first = limitMatch.groupValues[1].toLongOrNull() ?: return null
        val second = limitMatch.groupValues[2].toLongOrNull()
        // "limit 5, 10" → offset=5, count=10；"limit 10" → count=10
        if (second != null) return LimitClause(offset = first, count = second)
        // M38.2（语义对照矩阵实证）：MySQL 另一形态 "limit 5 OFFSET 10"（MP last 常用）——
        // 原正则不认 OFFSET，OFFSET 被静默丢弃
        val offsetMatch = Regex("""(?i)offset\s+(\d+)""").find(text)
        val offset = offsetMatch?.groupValues?.get(1)?.toLongOrNull()
        return if (offset != null) LimitClause(offset = offset, count = first)
        else LimitClause(count = first)
    }

    /**
     * 占位 LIMIT（T-UpdateSetSqlConcat）：`limit ?` / `limit ?, ?` / `limit ? offset ?`，
     * 也接受数字与占位混排（`limit 5, ?`）。占位符按片段来源顺序取名。
     */
    private fun parseLimitFragment(fragment: SqlFragment): LimitClause? {
        val match = Regex("""(?i)limit\s+(.+)$""").find(fragment.text.trim()) ?: return null
        val body = match.groupValues[1].trim()
        var paramIndex = 0
        fun valueOf(token: String): SqlValue? = when {
            token == "?" -> SqlValue.Placeholder(fragment.params.getOrNull(paramIndex++) ?: UNKNOWN_PARAM)
            token.matches(Regex("""\d+""")) -> SqlValue.Literal(token)
            else -> null
        }
        val offsetForm = Regex("""(?i)^(.+?)\s+offset\s+(.+)$""").find(body)
        if (offsetForm != null) {
            val count = valueOf(offsetForm.groupValues[1].trim()) ?: return null
            val offset = valueOf(offsetForm.groupValues[2].trim()) ?: return null
            return LimitClause(offset = offset, count = count)
        }
        val parts = body.split(",").map { it.trim() }
        return when (parts.size) {
            1 -> valueOf(parts[0])?.let { LimitClause(count = it) }
            2 -> {
                val offset = valueOf(parts[0]) ?: return null
                val count = valueOf(parts[1]) ?: return null
                LimitClause(offset = offset, count = count)
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // 参数辅助
    // ------------------------------------------------------------------

    /** 可空条件安全添加辅助（解析失败返回 null 时静默跳过）。 */
    private fun MutableList<Condition>.addNotNull(condition: Condition?) {
        if (condition != null) add(condition)
    }

    // ------------------------------------------------------------------
    // 条件布尔重载 / 无参 or() / apply（清单 P02/P10/P14）
    // ------------------------------------------------------------------

    /** 支持布尔条件重载的方法集合（eq/like/in/between 等）。 */
    private val CONDITION_METHODS = setOf(
        "eq", "ne", "gt", "ge", "lt", "le",
        "eqOrIsNull", // T-M20-09：MP 3.5.17，布尔重载形态 eqOrIsNull(condition, col, val)
        "like", "notLike", "likeLeft", "likeRight",
        "in", "notIn", "between", "notBetween",
        "isNull", "isNotNull",
        "allEq", // T-M21-04：allEq(boolean, Map[, null2IsNull]) 首布尔参剥离
    )

    /** 一元条件方法（isNull/isNotNull 布尔重载形态为 [flag, col] 2 参，区别于 3 参二元方法）。 */
    private val UNARY_METHODS = setOf("isNull", "isNotNull")

    /**
     * 布尔条件重载分析结果：剥离布尔标志后的调用 + 动态条件 toggle 预置信息（默认勾选态/标志来源）。
     */
    private data class FlagAnalysis(
        val call: WrapperCall,
        val flagSource: String?,
        val defaultEnabled: Boolean,
    )

    /**
     * 分析条件调用的布尔条件重载（P02/M12）：首参为布尔标志 → 剥离并固化动态条件元数据。
     * 非布尔重载（普通条件/其他方法）返回 null。
     *
     * 标志判定：一元方法（isNull/isNotNull）标志形态为 [flag, col]（2 参）；
     * 其余为 [flag, col, val...]（≥3 参）。首参为 ColumnArg 等列引用时恒非标志。
     *
     * M13 简化：不再识别判空语义（isNotBlank/isNotNull 等）、不做静态求值，
     * 默认勾选态仅按字面量 true/false 判定，运行时布尔一律默认勾选（复选框纯手动）。
     */
    private fun WrapperCall.analyzeFlag(): FlagAnalysis? {
        if (methodName !in CONDITION_METHODS) return null
        val first = args.getOrNull(0)
        if (first is WrapperArg.ColumnArg || first is WrapperArg.NestedArg || first is WrapperArg.OnConditionArg) return null
        val firstValue = first.asValue() ?: return null
        val flaggedArity = when {
            methodName in UNARY_METHODS -> args.size == 2
            // T-M21-04：allEq(boolean, Map) 双参重载的首参布尔即条件标志（3/4 参形态同理）
            methodName == "allEq" -> args.size >= 2
            else -> args.size >= 3
        }
        if (!flaggedArity) return null

        val flagText = when (firstValue) {
            is SqlValue.Literal -> firstValue.unquoted
            is SqlValue.Placeholder -> firstValue.source
        }
        // 字符串字面量是字符串版列名（F-17：`between("create_time", start, end)`），不可能是布尔标志；
        // 仅 true/false 字面量与占位符（运行时布尔）为条件重载
        if (firstValue is SqlValue.Literal && flagText != "true" && flagText != "false") return null
        val defaultEnabled = (firstValue as? SqlValue.Literal)?.unquoted != "false"
        return FlagAnalysis(WrapperCall(methodName, args.drop(1)), flagText, defaultEnabled)
    }

    /**
     * 为条件挂载动态条件元数据（值来源/标签由条件模型计算，id 按 pre-order 递增）。
     * M19.6：analysis 可为 null（无布尔重载的条件同样生成复选框）。
     */
    private fun Condition.bindToggle(analysis: FlagAnalysis?): Condition {
        val sources = valueSourcesOf(this)
        val toggle = ConditionToggle(
            id = "c${toggleSeq++}",
            flagSource = analysis?.flagSource,
            defaultEnabled = analysis?.defaultEnabled ?: true,
            label = SqlGenerator.renderConditionText(this),
            valueSource = sources.firstOrNull(),
            valueSources = sources,
        )
        return withToggle(toggle)
    }

    /**
     * 条件挂载动态条件元数据（M12 + M19.6 扩展，2026-08-13 用户实测建议）：
     * **所有条件**均可勾选开关——布尔重载（[FlagAnalysis]）携带 flagSource（tooltip）；
     * 无布尔重载的条件（`apply("1 = 0")` 短路、`in` 动态集合、`isNull` 等）同样生成复选框
     * （flagSource 为 null，默认勾选），面板可按需关停查看 SQL 变化。
     */
    private fun Condition?.flagged(analysis: FlagAnalysis?): Condition? =
        if (this == null) null else bindToggle(analysis)

    /**
     * 条件关联的未解析参数来源（M12 单值 + M19.5/M19.6 多值）：
     *  - Simple/Like：占位符 source（单值，条件行一个输入框）；
     *  - Between：lo/hi 两个占位符 source（M19.5：条件行两个输入框，2026-08-13 用户实测）；
     *  - In：values 各占位符 source（M19.6：in(col, List 变量) 的值来源进条件行输入框，
     *    多值逗号分割回填——2026-08-13 用户实测，替代普通参数区避免重复）；
     *  - 其余（一元/raw/分组）：无独立值输入框（checkbox-only）。
     */
    private fun valueSourcesOf(condition: Condition): List<String> = when (condition) {
        is Condition.Simple -> listOfNotNull((condition.value as? SqlValue.Placeholder)?.source)
        is Condition.Like -> listOfNotNull((condition.pattern as? SqlValue.Placeholder)?.source)
        is Condition.Between -> listOfNotNull(
            (condition.lo as? SqlValue.Placeholder)?.source,
            (condition.hi as? SqlValue.Placeholder)?.source,
        )
        is Condition.In -> condition.values.mapNotNull { (it as? SqlValue.Placeholder)?.source }
        else -> emptyList()
    }

    /**
     * 添加条件并维护 orPending 状态：条件成功加入后 orPending 复位（无参 or() 已被消费），
     * 条件解析失败（null）时保持 orPending（后续条件仍与 or() 前的条件组合）。
     */
    private fun addConditionFlagged(
        conditions: MutableList<Condition>,
        condition: Condition?,
        orPending: Boolean,
    ): Boolean {
        if (condition == null) return orPending
        conditions.addCondition(condition, orPending)
        return false
    }

    /**
     * 添加条件；orPending 为 true 时与上一条件组成 OR 分组（无参 or() 分隔符，清单 P14）。
     */
    private fun MutableList<Condition>.addCondition(condition: Condition, orPending: Boolean) {
        if (orPending && isNotEmpty()) {
            val prev = removeAt(size - 1)
            add(Condition.Group(Connector.OR, listOf(prev, condition)))
        } else {
            add(condition)
        }
    }

    /**
     * apply / exists 共用的「布尔前缀 + SQL 模板 + {i} 绑定参数」解析（T-M15-03）。
     *
     * 形态：
     *  - `apply("1 = 0")` → 无 flag、无参数
     *  - `apply(condition, "(rel.a, rel.id) > ({0}, {1})", a, b)` → 布尔前缀 + `{i}` 参数替换
     *  - `exists(dto.getStationId() != null, "SELECT 1 FROM rel WHERE ... = {0}", v)`（Q14-①）
     *
     * M12：布尔前缀不再丢弃 false 条件，而是固化为动态条件 toggle（默认排除，生成层过滤）。
     */
    private data class RawTemplate(
        val hasFlag: Boolean,
        val flagSource: String?,
        val defaultEnabled: Boolean,
        val sql: String,
        /** 片段中占位符 `?` 的来源（T-UpdateSetSqlConcat）：渲染时按序登记进参数表。 */
        val params: List<String> = emptyList(),
    )

    private fun parseRawTemplate(call: WrapperCall): RawTemplate? {
        val args = call.args
        if (args.isEmpty()) return null
        val first = args[0]
        // 布尔条件前缀：args>=2 且首参为布尔字面量（true/false）或运行时布尔表达式（占位符/非值实参）
        val flagValue = first.asValue()
        val hasFlag = args.size >= 2 && when {
            flagValue is SqlValue.Literal -> flagValue.unquoted == "true" || flagValue.unquoted == "false"
            // T-UpdateSetSqlConcat：带片段形态的运行期实参是 SQL 模板（`apply("x > " + v, w)`），
            // 不是布尔前缀——否则模板会被当 flag 吞掉，真正的模板位被错取成第二参
            flagValue is SqlValue.Placeholder -> first.asSqlFragment() == null
            first !is WrapperArg.ValueArg -> true
            else -> false
        }
        val startIndex = if (hasFlag) 1 else 0
        if (args.size <= startIndex) return null
        // T-UpdateSetSqlConcat：片段位取 asSqlFragment（`apply("amount > " + x)` → `amount > ?`）
        val template = args.getOrNull(startIndex).asSqlFragment() ?: return null
        // `{i}` 绑定参数替换 + 按最终文本顺序登记占位符来源（不登记会让面板回填整体错位）
        val rendered = renderSqlFragment(template, args.drop(startIndex + 1).map { it.asValue() })
        val sql = rendered.text
        val flagSource = if (hasFlag) {
            when (val f = first) {
                is WrapperArg.ValueArg -> when (val v = f.value) {
                    is SqlValue.Literal -> v.unquoted
                    is SqlValue.Placeholder -> v.source
                }
                else -> null
            }
        } else {
            null
        }
        return RawTemplate(hasFlag, flagSource, flagSource != "false", sql, rendered.params)
    }

    /**
     * 解析 apply（清单 P10）：原样 SQL 片段透传 + `{i}` 参数替换 + 布尔前缀 toggle。
     * M19.6（2026-08-13 用户实测）：**无布尔前缀同样挂复选框**（flagSource 为 null，默认勾选）——
     * `apply("1 = 0")` 短路空集可在参数面板勾选开关。
     */
    private fun parseApply(call: WrapperCall): Condition? {
        val t = parseRawTemplate(call) ?: return null
        return Condition.Raw(
            t.sql,
            toggle = ConditionToggle(
                id = "c${toggleSeq++}",
                flagSource = t.flagSource,
                defaultEnabled = t.defaultEnabled,
                label = SqlGenerator.renderConditionText(Condition.Raw(t.sql)),
            ),
            params = t.params,
        )
    }

    /**
     * 解析 applyFunc（MPJ 官方 API，1.4.13+）：`applyFunc("concat(%s,%s,{0}) is not null", func -> func.accept(列1, 列2).values("12"))`
     * → 条件片段。`%s` 列占位（按 accept 顺序替换）、`{i}` 绑定参数替换（与 selectFunc 占位语义一致）。
     */
    private fun parseApplyFunc(call: WrapperCall): Condition? {
        // T-UpdateSetSqlConcat：片段位取 asSqlFragment（模板含运行期段时静态段保留、运行期段写 `?`）
        val template = call.args.getOrNull(0).asSqlFragment() ?: return null
        val func = call.args.getOrNull(1) as? WrapperArg.FuncArg ?: return null
        var sql = template.text
        for (col in func.columns) {
            // MPJ 主表别名前缀（concat(t.order_code, t.code, ...)）；T-M23.5 列名按方言引用
            val qualified = columnPrefix?.let { col.withTableAlias(it) }?.qualifiedName(dialect)
                ?: col.qualifiedName(dialect)
            sql = sql.replaceFirst("%s", qualified)
        }
        if (sql.contains("%s")) return null
        // `{i}` 绑定参数替换 + 按最终文本顺序登记占位符来源
        val rendered = renderSqlFragment(SqlFragment(sql, template.params), func.values)
        return Condition.Raw(rendered.text, params = rendered.params)
    }

    /**
     * 解析 exists / notExists（Q14-①，T-M15-03）：
     * `exists(condition, "SELECT 1 FROM ... WHERE ... = {0}", v)` → `EXISTS (子查询)`，
     * 子查询模板原样透传 + `{i}` 绑定参数替换 + 布尔首参剥离（动态条件 toggle）。
     */
    private fun parseExists(call: WrapperCall, negated: Boolean): Condition? {
        val t = parseRawTemplate(call) ?: return null
        val sql = (if (negated) "NOT EXISTS" else "EXISTS") + " (" + t.sql + ")"
        return Condition.Raw(
            sql,
            toggle = if (t.hasFlag) {
                ConditionToggle(
                    id = "c${toggleSeq++}",
                    flagSource = t.flagSource,
                    defaultEnabled = t.defaultEnabled,
                    label = SqlGenerator.renderConditionText(Condition.Raw(sql)),
                )
            } else {
                null
            },
            params = t.params,
        )
    }

    /** 取调用中所有列实参（带主表别名前缀）。 */
    /** 列参数集合：Lambda 列（ColumnArg）与字符串列名（ValueArg，F-17 字符串版）统一经 [argAsColumn] 解析。 */
    private fun columnArgs(call: WrapperCall): List<ColumnRef> =
        call.args.mapNotNull { argAsColumn(it) }

    /**
     * SELECT 列表实参（T-UpdateSetSqlConcat）：字符串实参按**片段**解析——
     * `select("amount * " + x + " AS v")` 的运行期段渲染成 `?` 并带来源，由生成器在列列表渲染点按序登记。
     *
     * 与 [columnArgs] 的区别只在字符串实参：条件/排序/分组是**标识符**位置（拼运行期值会产出非法标识符，
     * 保持 `Literal` 口径），SELECT 列表才是「原样拼进语句的 SQL 表达式」位置。
     */
    private fun selectColumnArgs(call: WrapperCall): List<ColumnRef> =
        call.args.mapNotNull { arg ->
            when (arg) {
                is WrapperArg.ColumnArg, is WrapperArg.AliasArg -> argAsColumn(arg)
                is WrapperArg.ValueArg -> arg.asSqlFragment()?.let {
                    ColumnRef(columnName = it.text, rawParams = it.params)
                }
                else -> argAsColumn(arg)
            }
        }

    /**
     * T-M21-01：按谓词白/黑名单过滤实体列（静态近似，selectFilter / select(Class, Predicate)）。
     *
     *  - [WrapperArg.PredicateArg.include] 非空 → 白名单：取 include 中存在于实体表的列（按表列顺序）
     *  - include 空 → 全列减 [WrapperArg.PredicateArg.exclude]（黑名单）
     *  - include/exclude 均空（谓词无法静态分析）→ 回退全列
     * 列均带主表别名前缀（与条件渲染一致）。
     */
    private fun selectColumnsByPredicate(predicate: WrapperArg.PredicateArg): List<ColumnRef> {
        val allColumns = table.allColumnNames
        // T-Perf-23：List.contains O(n^2) -> Set O(n)（列数多时显著）
        val includeSet = predicate.include.toSet()
        val excludeSet = predicate.exclude.toSet()
        val selected = if (includeSet.isNotEmpty()) {
            allColumns.filter { it in includeSet }
        } else {
            allColumns.filterNot { it in excludeSet }
        }
        return selected.map { col ->
            val ref = ColumnRef(columnName = col)
            columnPrefix?.let { ref.withTableAlias(it) } ?: ref
        }
    }

    /** 实参转列：Lambda 列（ColumnArg）补主表别名前缀；字符串列名（ValueArg）原样（MPJQuery 已含别名）。 */
    private fun argAsColumn(arg: WrapperArg?): ColumnRef? = when (arg) {
        is WrapperArg.ColumnArg -> columnPrefix?.let { arg.column.withTableAlias(it) } ?: arg.column
        is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.let { ColumnRef(columnName = it.unquoted) }
        else -> null
    }

    /** 实参转值。 */
    private fun argAsValue(arg: WrapperArg?): SqlValue? = arg.asValue()
    // T-Perf-23：函数体内 Regex(...) 提为常量（热路径免重复编译 Pattern）
    private val QWP_LIMIT_PATTERN = Regex("""(?i)limit\s+(\d+)(?:\s*,\s*(\d+))?""")

}
