package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.BatchKind
import cn.song.mybatisplus.visualizer.model.BatchModel
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.ConditionToggle
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.psi.ArgResolver
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader
import cn.song.mybatisplus.visualizer.sql.GeneratedSql
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParamQuote
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable

/**
 * Easy-Query proxy 风格转换器（EQ，M44 其他 ORM 第一批，矩阵 §4）。
 *
 * 官方主推 sql-api-proxy 的 APT 代理风格（javap sql-core/sql-api-proxy 3.2.15 实证）：
 * `easyEntityQuery.queryable(Topic.class).where(o -> o.id().eq("123")).orderBy(o -> o.createTime().desc())
 * .limit(1, 20).toList()`。where lambda 是 **void 注册式语句块**（多条 `o.col().op(args);` 分号分隔），
 * 按块内语句逐链收集（[parseConditionChain]），链形 = 列访问器（`o.id()` 无实参）+ 谓词方法。
 *
 * ## 条件映射（javap com.easy.query.core.proxy.predicate.* 实证）
 *  - 比较：eq/ne/gt/ge/lt/le（含 boolean 前缀重载 → ConditionToggle）；
 *  - 集合：in/notIn（Collection / 数组实参）；
 *  - 一元：isNull/isNotNull；字符串断言：isEmpty/isNotBlank（isNotEmpty/isBlank 同法近似）；
 *  - 模糊：like/notLike（%v%）、likeMatchLeft/likeMatchRight（%v / v%）；
 *  - 范围族：rangeClosed → BETWEEN；rangeOpen/rangeOpenClosed/rangeClosedOpen →
 *    开闭区间渲染为 AND 组（`col > ? AND col <= ?`，现有 Between 模型为闭区间，开区间语义在
 *    条件层组合表达）；
 *  - or/and 闭包嵌套 → Group（与前一条件 OR/AND 连接）；布尔前缀 where(boolean, lambda) → 整组 toggle。
 *
 * ## 链面
 *  - orderBy lambda 块 → ORDER BY（`o.createTime().desc()` / 缺省 ASC）；
 *  - limit(count) / limit(offset, count) → LIMIT/OFFSET；
 *  - leftJoin/innerJoin/rightJoin(Class, (a,b) -> a.uid().eq(b.id())) 双参 on lambda →
 *    JoinClause + ColumnComparison（首参=副表 proxy、次参=主表 proxy，主表别名 t / 副表 t1）；
 *  - 终结点 toList/firstOrNull/singleOrNull/count/any/toPageResult/toSQL（count → SELECT COUNT(*)）；
 *  - 写路径：insertable(entity/Class) → INSERT（BatchModel 模板）；updatable(...).setColumns(lambda)
 *    → UPDATE SET；deletable(...) → DELETE。
 *
 * ## 边界（MVP，KDoc 文档化）
 *  - core/String 风格（easyQueryClient.queryable + 字符串列名）与 sql-api4j 二期不做——
 *    本转换器只认 proxy 语句块 lambda；
 *  - select(lambda) 投影、group/having、@Navigate 关联展开、JPAExpressions 式子查询暂不建模
 *    （优雅降级：忽略该段，SELECT 按实体全列呈现，不出错误 SQL）。
 *
 * 识别（[isEasyQueryCall]）：entry 方法名 queryable/insertable/updatable/deletable +
 * receiver 变量名（easyEntityQuery/easyQueryClient）或类型（EasyEntityQuery/EasyQueryClient）
 * 启发——普通类同名方法不识别（宁可无图标不可误亮）。
 */
object EasyQueryConverter {

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /** Easy-Query 预览结果（与 SpecificationQueryConverter.SpecificationPreview 同构）。 */
    data class EqPreview(
        val methodName: String,
        val statement: SqlStatement?,
        val sql: String,
        val paramSources: List<String> = emptyList(),
        val paramHints: List<ParamHint> = emptyList(),
        val tableName: String = "",
        val columns: List<String> = emptyList(),
        val warning: String? = null,
        val batchModel: BatchModel? = null,
    )

    // ------------------------------------------------------------------
    // 名单
    // ------------------------------------------------------------------

    /** 链 entry 方法名（javap EasyQueryClient：queryable/insertable/updatable/deletable）。 */
    val ENTRY_METHODS: Set<String> = setOf("queryable", "insertable", "updatable", "deletable")

    /** receiver 变量名启发（官方文档示例名）。 */
    private val RECEIVER_VARIABLE_NAMES: Set<String> = setOf("easyentityquery", "easyqueryclient")

    /** receiver 类型启发（EasyEntityQuery 在 easy-query-core artifact，测试环境按 stub 简名判定）。 */
    private val RECEIVER_TYPE_NAMES: Set<String> = setOf("EasyEntityQuery", "EasyQueryClient")

    /** 比较 op → SQL 操作符（javap DSLValuePredicate：eq/ne/gt/ge/lt/le）。 */
    private val COMPARE_OPS: Map<String, String> = mapOf(
        "eq" to "=", "ne" to "!=", "gt" to ">", "ge" to ">=", "lt" to "<", "le" to "<=",
    )

    /** like 族 op → (position, negated)。 */
    private val LIKE_OPS: Map<String, Pair<LikePosition, Boolean>> = mapOf(
        "like" to (LikePosition.ANYWHERE to false),
        "notLike" to (LikePosition.ANYWHERE to true),
        "likeMatchLeft" to (LikePosition.LEFT to false),
        "notLikeMatchLeft" to (LikePosition.LEFT to true),
        "likeMatchRight" to (LikePosition.RIGHT to false),
        "notLikeMatchRight" to (LikePosition.RIGHT to true),
        "likeRaw" to (LikePosition.ANYWHERE to false),
        "notLikeRaw" to (LikePosition.ANYWHERE to true),
        "likeRawMatchLeft" to (LikePosition.LEFT to false),
        "likeRawMatchRight" to (LikePosition.RIGHT to false),
    )

    /** 一元 op → SQL 片段。 */
    private val UNARY_OPS: Map<String, String> = mapOf(
        "isNull" to "IS NULL", "isNotNull" to "IS NOT NULL",
    )

    /** 集合 op（javap DSLValuesPredicate）。 */
    private val VALUES_OPS: Set<String> = setOf("in", "notIn")

    /** 范围族 op（javap DSLRangePredicate / DSLRangeColumnFunctionPredicate）。 */
    private val RANGE_OPS: Set<String> = setOf("rangeClosed", "rangeOpen", "rangeOpenClosed", "rangeClosedOpen")

    /** 谓词方法全集（列访问器 vs 谓词的区分依据）。 */
    private val OP_METHODS: Set<String> =
        COMPARE_OPS.keys + LIKE_OPS.keys + UNARY_OPS.keys + VALUES_OPS + RANGE_OPS +
            setOf("isEmpty", "isNotEmpty", "isBlank", "isNotBlank")

    /** 联表方法（javap Joinable1；on 双参 lambda）。 */
    private val JOIN_METHODS: Map<String, JoinType> = mapOf(
        "leftJoin" to JoinType.LEFT, "innerJoin" to JoinType.INNER, "rightJoin" to JoinType.RIGHT,
    )

    /** proxy 写路径 setColumns lambda 内的 set 方法（javap SQLColumnSet*）。 */
    private const val SET_OP = "set"

    /** 查询链遍历深度守卫（异常 PSI 防御）。 */
    private const val MAX_CHAIN_DEPTH = 32

    // ------------------------------------------------------------------
    // 识别（锚点 / preview 同源）
    // ------------------------------------------------------------------

    /**
     * 调用是否为 Easy-Query proxy 链（entry 方法名 + receiver 启发）。
     * 负例守卫：普通类同名方法（无 easyEntityQuery/easyQueryClient receiver）不识别。
     */
    fun isEasyQueryCall(call: PsiMethodCallExpression): Boolean = runCatching {
        isEntryCall(call)
    }.getOrDefault(false)

    private fun isEntryCall(call: PsiMethodCallExpression): Boolean {
        val methodName = call.methodExpression.referenceName ?: return false
        if (methodName !in ENTRY_METHODS) return false
        val qualifier = call.methodExpression.qualifierExpression ?: return false
        // 变量名启发（O(1) 文本）
        val qualifierText = (qualifier as? PsiReferenceExpression)?.referenceName ?: qualifier.text
        if (qualifierText?.lowercase() in RECEIVER_VARIABLE_NAMES) return true
        // 类型启发：qualifier 变量 resolve 声明类型 / 表达式类型
        val receiverType = when (qualifier) {
            is PsiReferenceExpression -> (qualifier.resolve() as? PsiVariable)?.type ?: qualifier.type
            else -> qualifier.type
        }
        val typeName = (receiverType as? PsiClassType)?.className ?: return false
        return typeName in RECEIVER_TYPE_NAMES
    }

    /**
     * 转换 Easy-Query proxy 链。
     *
     * @param call             链上任意位置的方法调用（自动回溯 entry）
     * @param resolveVariables 变量求值开关
     * @return 预览结果；非 Easy-Query 链 / 实体不可解析时 null（交回既有管道）
     */
    fun convert(call: PsiMethodCallExpression, resolveVariables: Boolean = true): EqPreview? = runCatching {
        convertInternal(call, resolveVariables)
    }.getOrNull()

    private fun convertInternal(call: PsiMethodCallExpression, resolveVariables: Boolean): EqPreview? {
        val entry = findEntryCall(call, MAX_CHAIN_DEPTH) ?: return null
        val methodName = entry.methodExpression.referenceName ?: return null
        // 实体：queryable(Class)/insertable(entity)/updatable(...)/deletable(...) 首参
        val entityClass = resolveEntityArg(entry) ?: return null
        val table = JpaEntityResolver().resolve(entityClass)
        if (table.entityClassName.isBlank()) return null

        val ctx = ParseCtx(table, entityClass, resolveVariables)
        val segments = collectSegments(entry)
        for (segment in segments.drop(1)) {
            when (segment.methodExpression.referenceName) {
                "where" -> ctx.addConditions(parseWhereSegment(segment, ctx))
                "orderBy" -> ctx.addOrderBy(parseOrderBySegment(segment, ctx))
                "limit" -> ctx.limit = parseLimit(segment)
                "select" -> Unit // MVP：投影暂不建模（SELECT 全列降级，见类 KDoc）
                in JOIN_METHODS -> ctx.addJoin(parseJoin(segment, ctx))
                "setColumns" -> ctx.addSets(parseSetColumns(segment, ctx))
            }
        }
        val terminal = segments.lastOrNull()?.methodExpression?.referenceName

        return when (methodName) {
            "queryable" -> selectPreview(entry, table, ctx, terminal)
            "insertable" -> insertPreview(entry, table, ctx)
            "updatable" -> updatePreview(entry, table, ctx)
            "deletable" -> deletePreview(entry, table, ctx)
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // 查询（queryable）
    // ------------------------------------------------------------------

    private fun selectPreview(
        entry: PsiMethodCallExpression,
        table: TableInfo,
        ctx: ParseCtx,
        terminal: String?,
    ): EqPreview {
        val hasJoins = ctx.joins.isNotEmpty()
        val mainAlias = if (hasJoins) "t" else null
        ctx.applyMainAlias(mainAlias)
        val conditions = ctx.conditions
        val statement: SqlStatement = when (terminal) {
            "count" -> SelectStatement(
                table,
                selectColumns = listOf(ColumnRef(columnName = "*", function = "COUNT")),
                tableAlias = mainAlias,
                joins = ctx.joins,
                conditions = conditions,
                orderBy = ctx.orderBy,
                limit = ctx.limit,
            )
            else -> SelectStatement(
                table,
                tableAlias = mainAlias,
                joins = ctx.joins,
                conditions = conditions,
                orderBy = ctx.orderBy,
                limit = ctx.limit,
            )
        }
        val notes = mutableListOf<String>()
        if (terminal == "toSQL") notes += "toSQL()：仅生成 SQL 不执行"
        if (terminal != null && terminal in setOf("firstOrNull", "singleOrNull", "any")) {
            notes += "$terminal()：取单条语义（预览不追加 LIMIT 1，运行时按实现）"
        }
        if (terminal == "toPageResult") notes += "toPageResult()：分页查询（LIMIT/OFFSET 以链上 limit 段为准）"
        val generated = SqlGenerator.generateWithParams(statement)
        return previewOf(entry, statement, generated, notes, table, ctx)
    }

    // ------------------------------------------------------------------
    // 写路径（insertable / updatable / deletable）
    // ------------------------------------------------------------------

    private fun insertPreview(entry: PsiMethodCallExpression, table: TableInfo, ctx: ParseCtx): EqPreview {
        // EQ8：@Column(primaryKey = true, generatedKey = true) 主键应用端/数据库生成 → 不进 INSERT 列
        val insertColumns = table.columns.filter { it.insertable && it.columnName !in ctx.generatedKeyColumns }
        val toggleColumns = insertColumns.map { col ->
            BatchColumn(
                columnName = col.columnName,
                source = col.columnName,
                quote = ParamQuote.decide(col.fieldType),
                checked = true,
                temporal = ParamQuote.isTemporal(col.fieldType),
            )
        }
        val model = BatchModel(BatchKind.INSERT, table.tableName, toggleColumns)
        val active = toggleColumns.map { it.columnName }.toSet()
        val notes = mutableListOf<String>()
        if (ctx.generatedKeyColumns.isNotEmpty()) {
            notes += "主键 ${ctx.generatedKeyColumns.joinToString(", ")}：@Column(generatedKey = true) 应用端/数据库生成，不进 INSERT 列清单"
        }
        return EqPreview(
            methodName = entry.methodExpression.referenceName ?: "insertable",
            statement = null,
            sql = (listOf(model.renderSql(active)) + notes.map { "-- $it" }).joinToString("\n"),
            paramSources = model.orderedSources(active),
            paramHints = model.orderedHints(active),
            tableName = table.tableName,
            columns = table.allColumnNames,
            batchModel = model,
        )
    }

    private fun updatePreview(entry: PsiMethodCallExpression, table: TableInfo, ctx: ParseCtx): EqPreview {
        val conditions = ctx.conditions
        return if (ctx.sets.isEmpty()) {
            // updatable(entity) 无 setColumns：实体全列更新 + 主键 WHERE（对齐 merge 语义的静态近似）
            val idColumn = table.columns.firstOrNull { it.isId }
            val sets = table.columns
                .filter { !it.isId }
                .map { SetClause(column = ColumnRef(columnName = it.columnName), value = SqlValue.Placeholder("?")) }
            val where = idColumn?.let { listOf(Condition.Simple(ColumnRef(columnName = it.columnName), "=", SqlValue.Placeholder("?"))) }
                ?: conditions
            val statement = UpdateStatement(table, sets, where)
            val generated = SqlGenerator.generateWithParams(statement)
            // 普查批：无主键且无 where 段 → WHERE 整体缺位（全表更新），notes 按实际 WHERE 语义给
            val whereNote = when {
                idColumn != null -> "主键 WHERE"
                conditions.isNotEmpty() -> "沿用 where 段条件"
                else -> "无 WHERE 条件，将更新全表"
            }
            previewOf(
                entry, statement, generated,
                listOf("updatable(entity)：未调用 setColumns，按实体全列 SET 近似（$whereNote）"),
                table, ctx,
                if (idColumn == null && conditions.isEmpty()) "⚠ 无 WHERE 条件，将更新全表数据！" else null,
            )
        } else {
            val statement = UpdateStatement(table, ctx.sets, conditions)
            val generated = SqlGenerator.generateWithParams(statement)
            // 普查批：update 无 where → 全表更新警示（对齐同文件 deletePreview 既有形态）
            previewOf(entry, statement, generated, emptyList(), table, ctx,
                if (conditions.isEmpty()) "⚠ 无 WHERE 条件，将更新全表数据！" else null)
        }
    }

    private fun deletePreview(entry: PsiMethodCallExpression, table: TableInfo, ctx: ParseCtx): EqPreview {
        val conditions = ctx.conditions
        val statement = DeleteStatement(table, conditions)
        val generated = SqlGenerator.generateWithParams(statement)
        val warning = if (conditions.isEmpty()) "⚠ 无 WHERE 条件，将删除全表数据！" else null
        return previewOf(entry, statement, generated, emptyList(), table, ctx, warning)
    }

    // ------------------------------------------------------------------
    // 段解析：where / orderBy / limit / join / setColumns
    // ------------------------------------------------------------------

    /** where(boolean, lambda) / where(lambda) → 条件列表（布尔前缀 → 条件/组级 toggle）。 */
    private fun parseWhereSegment(segment: PsiMethodCallExpression, ctx: ParseCtx): List<Condition> {
        val args = segment.argumentList.expressions
        if (args.isEmpty()) return emptyList()
        val lambda = args.lastOrNull() as? PsiLambdaExpression ?: return emptyList()
        val conditions = parseWhereLambda(lambda, ctx)
        if (args.size >= 2) {
            val flag = ctx.flagOf(args[0]) ?: return conditions
            if (conditions.isEmpty()) return emptyList()
            return if (conditions.size == 1) {
                // 单条件：toggle 直接挂条件（避免 Group 双层括号）
                listOf(conditions.first().withToggle(ctx.toggleOf(conditions.first(), flag)))
            } else {
                listOf(Condition.Group(Connector.AND, conditions).withToggle(ctx.toggleOf(Condition.Group(Connector.AND, conditions), flag)))
            }
        }
        return conditions
    }

    /**
     * where lambda 解析入口：语句块 `{ o.id().eq("1"); o.name().like("x"); }` 逐语句收集；
     * 表达式体 `o -> o.id().eq("1")` 直接收链。
     */
    private fun parseWhereLambda(lambda: PsiLambdaExpression, ctx: ParseCtx): List<Condition> {
        val body = lambda.body ?: return emptyList()
        return when (body) {
            is PsiCodeBlock ->
                body.statements.flatMap { statement ->
                    val expr = (statement as? PsiExpressionStatement)?.expression
                    if (expr is PsiMethodCallExpression) parseConditionChain(expr, ctx) else emptyList()
                }
            is PsiMethodCallExpression -> parseConditionChain(body, ctx)
            else -> emptyList()
        }
    }

    /**
     * 单条注册语句链解析：`o.id().eq("1")` / `o.age().gt(1).or(o2 -> o2.name().eq("2"))`。
     * 链形 = 若干列访问器（无实参 proxy 列方法）+ 谓词/闭包方法；
     * or/and 闭包与**前一条件**组成 Group（Easy-Query 注册式语义）。
     */
    private fun parseConditionChain(tailCall: PsiMethodCallExpression, ctx: ParseCtx): List<Condition> {
        val chain = ArgResolver.collectCallChain(tailCall)
        val conditions = mutableListOf<Condition>()
        var pendingColumn: String? = null
        for (call in chain) {
            val name = call.methodExpression.referenceName ?: continue
            when {
                name in OP_METHODS -> {
                    pendingColumn?.let { column ->
                        buildCondition(column, name, call, ctx)?.let { conditions.add(it) }
                    }
                    pendingColumn = null
                }
                name == "or" || name == "and" -> {
                    val lambda = call.argumentList.expressions.firstOrNull() as? PsiLambdaExpression
                    val nested = lambda?.let { parseWhereLambda(it, ctx) }.orEmpty()
                    if (nested.isNotEmpty()) {
                        val connector = if (name == "or") Connector.OR else Connector.AND
                        val last = conditions.removeLastOrNull()
                        conditions.add(Condition.Group(connector, listOfNotNull(last) + nested))
                    }
                    pendingColumn = null
                }
                else -> {
                    if (call.argumentList.expressions.isEmpty()) pendingColumn = name
                }
            }
        }
        return conditions
    }

    /** 谓词方法 → Condition（op 名单见类 KDoc）；属性名按实体注解映射列名；布尔前缀剥离并挂 toggle。 */
    private fun buildCondition(column: String, op: String, call: PsiMethodCallExpression, ctx: ParseCtx): Condition? {
        val columnRef = ColumnRef(columnName = ctx.columnNameOf(column))
        val wrapperCall = ArgResolver.toWrapperCall(call, ctx.table, ctx.table.entityClassName, ctx.resolveVariables)
        var args = wrapperCall.args
        // boolean 前缀重载（eq(boolean, v) / rangeClosed(boolean, lo, hi) / in(boolean, values)...）
        var flag: Flag? = null
        if (args.size >= 2) {
            ctx.flagOfArg(args.first())?.let {
                flag = it
                args = args.drop(1)
            }
        }
        val condition = when {
            op in COMPARE_OPS -> {
                args.getOrNull(0).asValue()?.let { Condition.Simple(columnRef, COMPARE_OPS[op]!!, it) }
            }
            op in UNARY_OPS -> Condition.Unary(columnRef, UNARY_OPS[op]!!)
            op in VALUES_OPS -> {
                val values = args.mapNotNull { it.asValue() }
                if (values.isEmpty()) null else Condition.In(columnRef, negated = op == "notIn", values = values)
            }
            op in LIKE_OPS -> {
                val (position, negated) = LIKE_OPS[op]!!
                args.getOrNull(0).asValue()?.let { Condition.Like(columnRef, negated, it, position) }
            }
            op in RANGE_OPS -> rangeCondition(columnRef, op, args)
            op == "isEmpty" || op == "isBlank" ->
                Condition.Raw("( ${columnRef.columnName} = '' OR ${columnRef.columnName} IS NULL )")
            op == "isNotEmpty" || op == "isNotBlank" ->
                Condition.Raw("( ${columnRef.columnName} IS NOT NULL AND ${columnRef.columnName} <> '' )")
            else -> null
        } ?: return null
        return if (flag != null) {
            condition.withToggle(ctx.toggleOf(condition, flag!!))
        } else {
            condition
        }
    }

    /** 范围族 → 开闭区间条件（BETWEEN 闭区间 / AND 组表达开区间，见类 KDoc）。 */
    private fun rangeCondition(columnRef: ColumnRef, op: String, args: List<WrapperArg>): Condition? {
        val lo = args.getOrNull(0).asValue() ?: return null
        val hi = args.getOrNull(1).asValue() ?: return null
        return when (op) {
            "rangeClosed" -> Condition.Between(columnRef, negated = false, lo = lo, hi = hi)
            "rangeOpen" -> Condition.Group(
                Connector.AND,
                listOf(Condition.Simple(columnRef, ">", lo), Condition.Simple(columnRef, "<", hi)),
            )
            "rangeOpenClosed" -> Condition.Group(
                Connector.AND,
                listOf(Condition.Simple(columnRef, ">", lo), Condition.Simple(columnRef, "<=", hi)),
            )
            "rangeClosedOpen" -> Condition.Group(
                Connector.AND,
                listOf(Condition.Simple(columnRef, ">=", lo), Condition.Simple(columnRef, "<", hi)),
            )
            else -> null
        }
    }

    /** orderBy lambda 块 → ORDER BY（`o.createTime().desc()` / 缺省 ASC）。 */
    private fun parseOrderBySegment(segment: PsiMethodCallExpression, ctx: ParseCtx): List<OrderByItem> {
        val lambda = segment.argumentList.expressions.lastOrNull() as? PsiLambdaExpression ?: return emptyList()
        val body = lambda.body ?: return emptyList()
        val chains = when (body) {
            is PsiCodeBlock -> body.statements.mapNotNull { (it as? PsiExpressionStatement)?.expression as? PsiMethodCallExpression }
            is PsiMethodCallExpression -> listOf(body)
            else -> emptyList()
        }
        val items = mutableListOf<OrderByItem>()
        for (chainTail in chains) {
            val chain = ArgResolver.collectCallChain(chainTail)
            var column: String? = null
            var asc = true
            for (call in chain) {
                when (call.methodExpression.referenceName) {
                    "asc" -> asc = true
                    "desc" -> asc = false
                    else -> {
                        if (call.argumentList.expressions.isEmpty()) column = call.methodExpression.referenceName
                    }
                }
            }
            column?.let { items += OrderByItem(ctx.columnWithAliasRef(it), asc) }
        }
        return items
    }

    /** limit(count) / limit(offset, count) → LimitClause（运行时值渲染 `?`）。 */
    private fun parseLimit(segment: PsiMethodCallExpression): LimitClause? {
        val args = segment.argumentList.expressions
        if (args.isEmpty()) return null
        val values = args.map { ExpressionEvaluator.evaluate(it) }
        return when {
            values.size >= 2 -> LimitClause(offset = values[0], count = values[1])
            else -> LimitClause(count = values[0])
        }
    }

    /** leftJoin(Class, (a,b) -> a.uid().eq(b.id())) → JoinClause（首参=副表、次参=主表）。 */
    private fun parseJoin(segment: PsiMethodCallExpression, ctx: ParseCtx): JoinClause? {
        val args = segment.argumentList.expressions
        if (args.size < 2) return null
        val joinType = JOIN_METHODS[segment.methodExpression.referenceName] ?: return null
        // 副表实体：Class 字面量或实体类型实参
        val joinEntity = resolveEntityArgFrom(args[0]) ?: return null
        val joinTable = JpaEntityResolver().resolve(joinEntity)
        val joinAlias = "t1"
        val lambda = args[1] as? PsiLambdaExpression ?: return null
        val params = lambda.parameterList.parameters
        if (params.size < 2) return null
        val joinParamName = params[0].name
        val mainParamName = params[1].name
        val onConditions = parseOnLambda(lambda, ctx, joinTable, joinAlias, joinParamName, mainParamName)
        return JoinClause(joinType, joinTable.tableName, joinAlias, onConditions, tableInfo = joinTable)
    }

    /**
     * on 双参 lambda 解析：`a.uid().eq(b.id())` → ColumnComparison（a=副表列 + 副表别名 t1，
     * b=主表列 + 主表别名 t）。列名按各自实体注解映射（副表 joinTable / 主表 ctx.table），
     * 未知属性降级 snake。实参为 `<param>.<col>()` 方法调用形态（列对列），值形态降级 `col = ?`。
     * 链沿 qualifier 收集（on 实参里的列调用不在链内，无双重计数）。
     */
    private fun parseOnLambda(
        lambda: PsiLambdaExpression,
        ctx: ParseCtx,
        joinTable: TableInfo,
        joinAlias: String,
        joinParamName: String?,
        @Suppress("UNUSED_PARAMETER") mainParamName: String?,
    ): List<Condition> {
        val body = lambda.body ?: return emptyList()
        val chains = when (body) {
            is PsiCodeBlock -> body.statements.mapNotNull { (it as? PsiExpressionStatement)?.expression as? PsiMethodCallExpression }
            is PsiMethodCallExpression -> listOf(body)
            else -> emptyList()
        }
        val conditions = mutableListOf<Condition>()
        for (chainTail in chains) {
            val chain = ArgResolver.collectCallChain(chainTail)
            var pending: Pair<String, Boolean>? = null // (属性名, 是否副表列)
            for (call in chain) {
                val name = call.methodExpression.referenceName ?: continue
                when {
                    name in COMPARE_OPS.keys -> {
                        val (property, isJoinColumn) = pending ?: continue
                        val columnName = if (isJoinColumn) {
                            joinTable.columnByField(property)?.columnName ?: NamingStrategy.toSnakeCase(property)
                        } else {
                            ctx.columnNameOf(property)
                        }
                        val left = ColumnRef(columnName = columnName, tableAlias = if (isJoinColumn) joinAlias else "t")
                        val arg = call.argumentList.expressions.firstOrNull()
                        val rightCall = arg as? PsiMethodCallExpression
                        val rightColumn = rightCall?.methodExpression?.referenceName
                        val rightQualifier = (rightCall?.methodExpression?.qualifierExpression as? PsiReferenceExpression)?.referenceName
                        conditions += if (rightColumn != null && rightQualifier != null) {
                            val rightIsJoin = rightQualifier == joinParamName
                            val rightTableName = if (rightIsJoin) {
                                joinTable.columnByField(rightColumn)?.columnName ?: NamingStrategy.toSnakeCase(rightColumn)
                            } else {
                                ctx.columnNameOf(rightColumn)
                            }
                            Condition.ColumnComparison(
                                left = left,
                                right = ColumnRef(columnName = rightTableName, tableAlias = if (rightIsJoin) joinAlias else "t"),
                            )
                        } else {
                            Condition.Simple(
                                left,
                                COMPARE_OPS[name] ?: "=",
                                arg?.let { ExpressionEvaluator.evaluate(it) } ?: SqlValue.Placeholder("?"),
                            )
                        }
                        pending = null
                    }
                    call.argumentList.expressions.isEmpty() -> {
                        val qualifierName = (call.methodExpression.qualifierExpression as? PsiReferenceExpression)?.referenceName
                        pending = name to (qualifierName == joinParamName || joinParamName == null)
                    }
                }
            }
        }
        return conditions
    }

    /** setColumns lambda 块 → SET 子句（`s -> s.name().set("x")`）。 */
    private fun parseSetColumns(segment: PsiMethodCallExpression, ctx: ParseCtx): List<SetClause> {
        val lambda = segment.argumentList.expressions.firstOrNull() as? PsiLambdaExpression ?: return emptyList()
        val body = lambda.body ?: return emptyList()
        val chains = when (body) {
            is PsiCodeBlock -> body.statements.mapNotNull { (it as? PsiExpressionStatement)?.expression as? PsiMethodCallExpression }
            is PsiMethodCallExpression -> listOf(body)
            else -> emptyList()
        }
        val sets = mutableListOf<SetClause>()
        for (chainTail in chains) {
            val chain = ArgResolver.collectCallChain(chainTail)
            var pendingColumn: String? = null
            for (call in chain) {
                when (call.methodExpression.referenceName) {
                    SET_OP -> {
                        pendingColumn?.let { column ->
                            val value = ExpressionEvaluator.evaluate(call.argumentList.expressions.firstOrNull() ?: return@let)
                            sets += SetClause(column = ColumnRef(columnName = ctx.columnNameOf(column)), value = value)
                        }
                        pendingColumn = null
                    }
                    else -> {
                        if (call.argumentList.expressions.isEmpty()) pendingColumn = call.methodExpression.referenceName
                    }
                }
            }
        }
        return sets
    }

    // ------------------------------------------------------------------
    // 公共辅助
    // ------------------------------------------------------------------

    /** 从 entry 沿 qualifier 链回溯找到 entry 调用（深度守卫防异常 PSI）。 */
    private fun findEntryCall(from: PsiMethodCallExpression, maxDepth: Int): PsiMethodCallExpression? {
        var current: PsiMethodCallExpression = from
        var depth = 0
        while (depth++ < maxDepth) {
            if (isEntryCall(current)) return current
            current = (current.methodExpression.qualifierExpression as? PsiMethodCallExpression) ?: return null
        }
        return null
    }

    /**
     * 收集 entry → 链尾的完整段列表（头=entry）。
     *
     * 回溯必须处理**两层**延续（与 WrapperLocator.collectExtendingChain 同构）：
     *  - 调用层：外层调用的 qualifier 是当前链尾；
     *  - **引用层**：链式调用中 entry.parent 是外层调用的 methodExpression（PsiReferenceExpression），
     *    其 qualifier 才是 entry——漏掉这层则回溯第一步即 break，segments 恒为 [entry]，全部链段丢失
     *    （表名来自 entry 实参而 where/orderBy/limit/join 全丢的共性根因）。
     */
    private fun collectSegments(entry: PsiMethodCallExpression): List<PsiMethodCallExpression> {
        var outermost: PsiMethodCallExpression = entry
        var cursor: PsiElement? = entry.parent
        while (cursor != null) {
            when {
                cursor is PsiMethodCallExpression && cursor.methodExpression.qualifierExpression === outermost -> {
                    outermost = cursor
                    cursor = cursor.parent
                }
                cursor is PsiReferenceExpression && cursor.qualifierExpression === outermost -> {
                    val next = cursor.parent as? PsiMethodCallExpression ?: break
                    outermost = next
                    cursor = next.parent
                }
                else -> break
            }
        }
        return ArgResolver.collectCallChain(outermost)
    }

    /** 实体实参解析：Class 字面量（queryable(Topic.class)/insertable(Topic.class)）或实体类型实参。 */
    private fun resolveEntityArg(call: PsiMethodCallExpression): PsiClass? =
        call.argumentList.expressions.firstNotNullOfOrNull { resolveEntityArgFrom(it) }

    private fun resolveEntityArgFrom(expr: com.intellij.psi.PsiExpression): PsiClass? {
        (expr as? PsiClassObjectAccessExpression)?.let { access ->
            return (access.operand.type as? PsiClassType)?.resolve()
        }
        return (expr.type as? PsiClassType)?.resolve()
    }

    /**
     * 布尔前缀标志解析（true/false 字面量或运行时布尔占位）。
     *
     * R49 口径统一：字面量 true 与 false 一样携带源码文本（`"true"`/`"false"`）——null 保留给
     * 「非布尔重载」（见 [cn.song.mybatisplus.visualizer.model.ConditionToggle.flagSource] 契约）。
     * 此前 true 被折叠成 null，与 QueryWrapperParser 侧（`QueryWrapperParserTest` 钉 `flagSource=="true"`）
     * 不一致，且使同一用户表达式在两条解析链上标注不同（R49 Wrapper 布尔条件探镜据此显示）。
     * `enabled` 语义不变（字面量 true 仍默认勾选）。
     */
    private data class Flag(val source: String?, val enabled: Boolean)

    private fun ParseCtx.flagOf(expr: com.intellij.psi.PsiExpression): Flag? {
        val value = ExpressionEvaluator.evaluate(expr)
        return when (value) {
            is SqlValue.Literal -> when (value.unquoted) {
                "true" -> Flag("true", true)
                "false" -> Flag("false", false)
                else -> null
            }
            is SqlValue.Placeholder -> Flag(value.source, true)
        }
    }

    private fun ParseCtx.flagOfArg(arg: WrapperArg): Flag? {
        if (arg is WrapperArg.ColumnArg || arg is WrapperArg.NestedArg) return null
        val value = arg.asValue() ?: return null
        return when (value) {
            is SqlValue.Literal -> when (value.unquoted) {
                "true" -> Flag("true", true)
                "false" -> Flag("false", false)
                else -> null
            }
            is SqlValue.Placeholder -> Flag(value.source, true)
        }
    }

    private fun previewOf(
        entry: PsiMethodCallExpression,
        statement: SqlStatement,
        generated: GeneratedSql,
        notes: List<String>,
        table: TableInfo,
        ctx: ParseCtx,
        warning: String? = null,
    ): EqPreview {
        val sql = if (notes.isEmpty()) generated.sql else generated.sql + notes.joinToString("") { "\n-- $it" }
        return EqPreview(
            methodName = entry.methodExpression.referenceName ?: "",
            statement = statement,
            sql = sql,
            paramSources = generated.unresolvedParams,
            paramHints = generated.paramHints,
            tableName = table.tableName,
            columns = table.allColumnNames,
            warning = warning,
        )
    }

    /** 转换过程上下文（每次 convert 独立实例——object 无状态可重入）。 */
    private class ParseCtx(
        val table: TableInfo,
        val entityClass: PsiClass,
        val resolveVariables: Boolean,
    ) {
        var conditions = mutableListOf<Condition>()
        var orderBy = mutableListOf<OrderByItem>()
        var joins = mutableListOf<JoinClause>()
        var sets = mutableListOf<SetClause>()
        var limit: LimitClause? = null
        var mainAlias: String? = null
        var toggleSeq = 0

        /** EQ8：@Column(primaryKey = true, generatedKey = true) 的主键列（INSERT 排除）。 */
        val generatedKeyColumns: Set<String> by lazy {
            entityClass.allFields.mapNotNull { field ->
                val column = JpaMetadataReader.findFlex(field, JpaMetadataReader.FQN_EASY_QUERY_COLUMN)
                    ?: return@mapNotNull null
                val fieldName = field.name ?: return@mapNotNull null
                if (JpaMetadataReader.attrBoolean(column, "primaryKey") == true &&
                    JpaMetadataReader.attrBoolean(column, "generatedKey") == true
                ) {
                    JpaMetadataReader.columnValueOrSnake(field, fieldName)
                } else {
                    null
                }
            }.toSet()
        }

        fun addConditions(conditions: List<Condition>) {
            this.conditions.addAll(conditions)
        }

        fun addOrderBy(items: List<OrderByItem>) {
            orderBy.addAll(items)
        }

        fun addJoin(join: JoinClause?) {
            join?.let { joins.add(it) }
        }

        fun addSets(sets: List<SetClause>) {
            this.sets.addAll(sets)
        }

        /** join 存在时主表别名 t——渲染前统一回填条件/排序列别名（条件构建期别名未定，逐项补齐）。 */
        fun applyMainAlias(alias: String?) {
            mainAlias = alias
            if (alias == null) return
            conditions = conditions.map { it.withMainAlias(alias) }.toMutableList()
            orderBy = orderBy.map { it.copy(column = it.column.withTableAlias(alias)) }.toMutableList()
        }

        /** proxy 列访问器方法名（Java 属性名）→ 数据库列名（@Column 注解映射，缺省 snake 兜底）。 */
        fun columnNameOf(property: String): String =
            table.columnByField(property)?.columnName ?: NamingStrategy.toSnakeCase(property)

        /** toggle 构造（id 按 pre-order 递增）。 */
        fun toggleOf(condition: Condition, flag: Flag): ConditionToggle = ConditionToggle(
            id = "c${toggleSeq++}",
            flagSource = flag.source,
            defaultEnabled = flag.enabled,
            label = SqlGenerator.renderConditionText(condition),
        )

        fun columnWithAliasRef(column: String): ColumnRef =
            mainAlias?.let { ColumnRef(columnName = columnNameOf(column), tableAlias = it) }
                ?: ColumnRef(columnName = columnNameOf(column))

        private fun Condition.withMainAlias(alias: String): Condition = when (this) {
            is Condition.Simple -> copy(column = column.withTableAlias(alias))
            is Condition.Unary -> copy(column = column.withTableAlias(alias))
            is Condition.In -> copy(column = column.withTableAlias(alias))
            is Condition.Between -> copy(column = column.withTableAlias(alias))
            is Condition.Like -> copy(column = column.withTableAlias(alias))
            is Condition.ColumnComparison -> copy(left = left.withTableAlias(alias))
            is Condition.Raw -> this
            is Condition.Group -> copy(children = children.map { it.withMainAlias(alias) })
        }
    }
}
