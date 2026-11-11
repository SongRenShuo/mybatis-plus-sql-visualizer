package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.psi.ArgResolver
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.searches.ReferencesSearch

/**
 * Querydsl 转换器（QD，M44 其他 ORM 第一批，矩阵 §3）。
 *
 * `JPAQueryFactory` receiver（`private JPAQueryFactory queryFactory = new JPAQueryFactory(em);`）链 +
 * **表达式树 walker**——where 实参是 Operation mixin 表达式树而非平铺链（javap querydsl-jpa 5.1.0 /
 * querydsl-core 5.1.1 实证）：
 *
 * ```
 * queryFactory.selectFrom(QUser.user)
 *     .where(QUser.user.status.eq(1), user.name.contains("x"))
 *     .orderBy(QUser.user.id.desc())
 *     .limit(10).offset(5)
 *     .fetchOne()
 * ```
 *
 * ## 表达式树解析
 *  - 根：Q 类静态字段（`QUser.user`）或 Q 类型变量（`QUser user = QUser.user;`）——
 *    Q 类 superType（EntityPathBase/BeanPath）泛型参数拿实体类 → [JpaEntityResolver] 出 TableInfo；
 *  - receiver 链末段属性名（`status`）→ 实体列名（按 @Table/@Column 注解映射，缺省 snake 兜底）；
 *  - op（javap SimpleExpression/ComparableExpression/StringExpression/BooleanExpression）：
 *    eq/ne/gt/goe/lt/loe → Simple；in/notIn → In；isNull/isNotNull → Unary；
 *    like/notLike → Like；contains/startsWith/endsWith → Like（% 位置映射）；
 *    between/notBetween → Between；isTrue/isFalse → = TRUE/FALSE；
 *  - and/or/not → Group（嵌套表达式递归 [parsePredicate]，谓词变量有界追踪，深度 ≤ 5）；
 *  - BooleanBuilder 动态累积（`b.and(p1).or(p2)`）→ ReferencesSearch 收集 and/or 链；
 *  - Expressions.*Template 等未支持形态 → 该谓词跳过（不出错误 SQL）。
 *
 * ## 链面
 *  - selectFrom(Q 根)/select(...)/from(...)；orderBy(path.desc())；limit/offset → 分页；
 *  - 写路径：update(根).set(path, 值).where(...).execute() / delete(根).where(...).execute() /
 *    insert(根).columns(paths).values(vals).execute()；
 *  - 终结点 fetch/fetchOne/fetchFirst/execute（fetchCount/fetchResults 已弃用，忽略）。
 *
 * ## 预览口径
 *  Q 类只携带 Java 属性名，列名 = 实体 JPA 注解映射（复用 [JpaEntityResolver]）——
 *  预览注释恒标注「按实体注解映射的近似 SQL」。
 *
 * ## 边界（MVP）
 *  - join 基础形态暂不建模（出现 join 段降级注释）；JPAExpressions 子查询、Expressions.*Template、
 *    CaseBuilder、PathBuilder 动态路径（get(String)）、GroupBy 投影暂不建模。
 */
object QuerydslConverter {

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /** Querydsl 预览结果。 */
    data class QdPreview(
        val methodName: String,
        val statement: SqlStatement?,
        val sql: String,
        val paramSources: List<String> = emptyList(),
        val paramHints: List<ParamHint> = emptyList(),
        val tableName: String = "",
        val columns: List<String> = emptyList(),
        val warning: String? = null,
    )

    // ------------------------------------------------------------------
    // 名单
    // ------------------------------------------------------------------

    /** JPAQueryFactory entry 方法（javap JPAQueryFactory：10 方法）。 */
    private val ENTRY_METHODS: Set<String> = setOf(
        "select", "selectDistinct", "selectOne", "selectZero", "selectFrom", "from", "query",
        "update", "delete", "insert",
    )

    /** 比较 op → SQL 操作符（javap SimpleExpression/ComparableExpression）。 */
    private val COMPARE_OPS: Map<String, String> = mapOf(
        "eq" to "=", "ne" to "!=",
        "gt" to ">", "goe" to ">=",
        "lt" to "<", "loe" to "<=",
    )

    /** 集合 / 一元 / LIKE / BETWEEN op（javap SimpleExpression/StringExpression）。 */
    private val IN_OPS: Set<String> = setOf("in", "notIn")
    private val UNARY_OPS: Map<String, String> = mapOf("isNull" to "IS NULL", "isNotNull" to "IS NOT NULL")

    /** LIKE op → (position, negated)（contains→%v%、startsWith→v%、endsWith→%v）。 */
    private val LIKE_OPS: Map<String, Pair<LikePosition, Boolean>> = mapOf(
        "like" to (LikePosition.NONE to false),
        "notLike" to (LikePosition.NONE to true),
        "likeIgnoreCase" to (LikePosition.NONE to false),
        "contains" to (LikePosition.ANYWHERE to false),
        "containsIgnoreCase" to (LikePosition.ANYWHERE to false),
        "startsWith" to (LikePosition.RIGHT to false),
        "startsWithIgnoreCase" to (LikePosition.RIGHT to false),
        "endsWith" to (LikePosition.LEFT to false),
        "endsWithIgnoreCase" to (LikePosition.LEFT to false),
    )

    private val BETWEEN_OPS: Set<String> = setOf("between", "notBetween")
    private val BOOLEAN_OPS: Set<String> = setOf("isTrue", "isFalse")

    /** 逻辑组合 op（BooleanExpression/BooleanBuilder）。 */
    private val GROUP_OPS: Set<String> = setOf("and", "or", "not")

    /** join 段方法（降级注释用；无 fullJoin——JPQL 限制）。 */
    private val JOIN_METHODS: Set<String> = setOf("join", "innerJoin", "leftJoin", "rightJoin", "fetchJoin")

    /** 终结点（fetchCount/fetchResults 已弃用，忽略）。 */
    private val TERMINALS: Set<String> = setOf("fetch", "fetchOne", "fetchFirst", "iterate", "stream", "execute")

    /** 谓词变量 / BooleanBuilder 追踪深度守卫。 */
    private const val MAX_TRACE_DEPTH = 5

    private const val MAX_CHAIN_DEPTH = 32

    /** Q 路径类型后缀（javap EntityPathBase 系：StringPath/NumberPath/...）。 */
    private const val PATH_TYPE_SUFFIX = "Path"

    // ------------------------------------------------------------------
    // 识别与入口
    // ------------------------------------------------------------------

    /** 调用是否为 Querydsl 链头（entry 方法名 + receiver 类型启发）。 */
    fun isQuerydslCall(call: PsiMethodCallExpression): Boolean = runCatching {
        val methodName = call.methodExpression.referenceName ?: return@runCatching false
        if (methodName !in ENTRY_METHODS) return@runCatching false
        receiverIsFactory(call)
    }.getOrDefault(false)

    /** receiver 是否为 JPAQueryFactory（变量声明类型 / 表达式类型；负例守卫：普通 receiver 不识别）。 */
    private fun receiverIsFactory(call: PsiMethodCallExpression): Boolean {
        val qualifier = call.methodExpression.qualifierExpression ?: return false
        if (qualifier is PsiReferenceExpression) {
            (qualifier.resolve() as? PsiVariable)?.let { variable ->
                if ((variable.type as? PsiClassType)?.className == "JPAQueryFactory") return true
            }
        }
        return (qualifier.type as? PsiClassType)?.className == "JPAQueryFactory"
    }

    /**
     * 转换 Querydsl 链（链上任意位置调用均可，自动回溯 entry）。
     * 非 Querydsl 链 / 实体不可解析 → null（交回既有管道）。
     */
    fun convert(call: PsiMethodCallExpression, resolveVariables: Boolean = true): QdPreview? = runCatching {
        convertInternal(call, resolveVariables)
    }.getOrNull()

    private fun convertInternal(call: PsiMethodCallExpression, resolveVariables: Boolean): QdPreview? {
        val entry = findEntryCall(call, MAX_CHAIN_DEPTH) ?: return null
        val entryName = entry.methodExpression.referenceName ?: return null
        val segments = collectSegments(entry)

        val ctx = QdCtx(resolveVariables)
        for (segment in segments.drop(1)) {
            when (segment.methodExpression.referenceName) {
                "where" -> {
                    for (arg in segment.argumentList.expressions) {
                        parsePredicate(arg, ctx, 0)?.let { ctx.conditions.add(it) }
                    }
                }
                "orderBy" -> ctx.addOrderBy(parseOrderBy(segment, ctx))
                "limit" -> ctx.limit = segment.argumentList.expressions.firstOrNull()
                    ?.let { ExpressionEvaluator.evaluate(it) }?.let { LimitClause(count = it) }
                "offset" -> ctx.offset = segment.argumentList.expressions.firstOrNull()
                    ?.let { ExpressionEvaluator.evaluate(it) }
                "set" -> parseSet(segment, ctx)
                "columns" -> ctx.insertColumns = parsePathColumns(segment, ctx).toMutableList()
                "values" -> ctx.insertValues = segment.argumentList.expressions.map { ExpressionEvaluator.evaluate(it) }.toMutableList()
                in JOIN_METHODS -> ctx.joinSegmentSeen = true
                "groupBy", "having", "restrict" -> Unit // MVP 边界
                else -> Unit
            }
        }

        // entry 实参：selectFrom(QUser.user)/update(根)/delete(根)/insert(根) 的根路径；
        // select(...) 的投影列
        if (entryName == "select" || entryName == "selectDistinct") {
            for (arg in entry.argumentList.expressions) {
                resolvePath(arg, ctx, 0)?.let { ctx.selectColumns.add(ctx.columnRefOf(it)) }
            }
        }
        val rootPath = entry.argumentList.expressions.firstOrNull()?.let { resolveRootPath(it, ctx, 0) }
        val entityClass = rootPath?.entityClass ?: run {
            // select(QUser.user.id, ...) 形态：从首个投影路径反查实体
            entry.argumentList.expressions.firstNotNullOfOrNull { resolvePath(it, ctx, 0) }?.entityClass
        } ?: return null
        val table = ctx.tableOf(entityClass)

        val notes = mutableListOf("Querydsl：Q 类仅携带 Java 属性名，列名按实体注解映射的近似 SQL")
        if (ctx.joinSegmentSeen) notes += "join(...)：基础 join 形态本版本暂不建模，预览不含 JOIN 子句"
        val statement: SqlStatement?
        val sql: String
        val params: List<String>
        val hints: List<ParamHint>
        when (entryName) {
            "update" -> {
                // 普查批：update 无 where → notes + 警示（对齐同函数 delete 分支既有形态）
                if (ctx.conditions.isEmpty()) notes += "无 WHERE 条件：update(根).execute() 将更新全表（运行时 Querydsl update 无条件即全改）"
                statement = UpdateStatement(table, ctx.sets, ctx.conditions)
                val generated = SqlGenerator.generateWithParams(statement)
                sql = generated.sql
                params = generated.unresolvedParams
                hints = generated.paramHints
            }
            "delete" -> {
                if (ctx.conditions.isEmpty()) notes += "无 WHERE 条件：delete(根).execute() 将删除全表（运行时 Querydsl delete 无条件即全删）"
                statement = DeleteStatement(table, ctx.conditions)
                val generated = SqlGenerator.generateWithParams(statement)
                sql = generated.sql
                params = generated.unresolvedParams
                hints = generated.paramHints
            }
            "insert" -> {
                // insert(根).columns(path...).values(v...)：列/值按位置对齐；无 columns 段 → 实体全列
                val columns = ctx.insertColumns.ifEmpty { table.allColumnNames.map { ColumnRef(columnName = it) } }
                val values = ctx.insertValues
                val colNames = columns.map { it.columnName }
                val valueTexts = colNames.mapIndexed { index, _ ->
                    (values.getOrNull(index) as? SqlValue.Literal)?.raw ?: "?"
                }
                sql = "INSERT INTO ${table.tableName} (${colNames.joinToString(", ")})\nVALUES (${valueTexts.joinToString(", ")})"
                params = colNames.mapIndexed { index, _ ->
                    (values.getOrNull(index) as? SqlValue.Placeholder)?.source ?: "values[$index]"
                }
                hints = List(params.size) { index ->
                    ParamHint(quote = values.getOrNull(index)?.let { it is SqlValue.Placeholder } ?: true)
                }
                statement = null
            }
            else -> {
                statement = SelectStatement(
                    table,
                    selectColumns = ctx.selectColumns,
                    tableAlias = null,
                    conditions = ctx.conditions,
                    orderBy = ctx.orderBy,
                    limit = ctx.limit?.let { LimitClause(offset = ctx.offset ?: it.offset, count = it.count) },
                )
                val generated = SqlGenerator.generateWithParams(statement)
                sql = generated.sql
                params = generated.unresolvedParams
                hints = generated.paramHints
            }
        }
        return QdPreview(
            methodName = entryName,
            statement = statement,
            sql = sql + notes.joinToString("") { "\n-- $it" },
            paramSources = params,
            paramHints = hints,
            tableName = table.tableName,
            columns = table.allColumnNames,
            warning = when {
                entryName == "delete" && ctx.conditions.isEmpty() -> "⚠ 无 WHERE 条件，将删除全表数据！"
                entryName == "update" && ctx.conditions.isEmpty() -> "⚠ 无 WHERE 条件，将更新全表数据！"
                else -> null
            },
        )
    }

    // ------------------------------------------------------------------
    // 段解析
    // ------------------------------------------------------------------

    /** orderBy(QUser.user.id.desc(), ...) → ORDER BY。 */
    private fun parseOrderBy(segment: PsiMethodCallExpression, ctx: QdCtx): List<OrderByItem> =
        segment.argumentList.expressions.mapNotNull { arg ->
            if (arg !is PsiMethodCallExpression) return@mapNotNull null
            val asc = when (arg.methodExpression.referenceName) {
                "desc" -> false
                "asc" -> true
                else -> return@mapNotNull null
            }
            val path = resolvePath(arg.methodExpression.qualifierExpression, ctx, 0) ?: return@mapNotNull null
            OrderByItem(ctx.columnRefOf(path), asc)
        }

    /** update 系 set(path, 值) → SET 子句。 */
    private fun parseSet(segment: PsiMethodCallExpression, ctx: QdCtx) {
        val args = segment.argumentList.expressions
        if (args.isEmpty()) return
        val path = resolvePath(args[0], ctx, 0) ?: return
        val value = args.getOrNull(1)?.let { ExpressionEvaluator.evaluate(it) } ?: return
        ctx.sets.add(SetClause(column = ctx.columnRefOf(path), value = value))
    }

    /** columns(path...) → INSERT 列清单。 */
    private fun parsePathColumns(segment: PsiMethodCallExpression, ctx: QdCtx): List<ColumnRef> =
        segment.argumentList.expressions.mapNotNull { arg ->
            resolvePath(arg, ctx, 0)?.let { ctx.columnRefOf(it) }
        }

    // ------------------------------------------------------------------
    // 表达式树 walker（谓词 → Condition）
    // ------------------------------------------------------------------

    /**
     * 谓词表达式 → Condition（递归）：
     *  - 谓词变量（`Predicate p = ...`）有界追踪初始化式；BooleanBuilder 变量收集 and/or 引用链；
     *  - 方法调用形态：op = 方法名，receiver = 属性路径，实参求值；
     *  - and/or/not → Group 递归组合。
     */
    private fun parsePredicate(expr: PsiExpression?, ctx: QdCtx, depth: Int): Condition? {
        if (expr == null || depth > MAX_TRACE_DEPTH) return null
        // 谓词变量追踪
        if (expr is PsiReferenceExpression) {
            val resolved = expr.resolve()
            if (resolved is PsiVariable) {
                resolved.initializer?.let { init ->
                    parsePredicate(init, ctx, depth + 1)?.let { return it }
                }
                if (isBooleanBuilderType(resolved.type)) {
                    return booleanBuilderCondition(resolved, ctx, depth)
                }
            }
            return null
        }
        if (expr !is PsiMethodCallExpression) return null
        val op = expr.methodExpression.referenceName ?: return null
        val receiver = expr.methodExpression.qualifierExpression
        return when {
            op in GROUP_OPS -> {
                val connector = if (op == "or") Connector.OR else Connector.AND
                if (op == "not") {
                    val child = receiver?.let { parsePredicate(it, ctx, depth + 1) } ?: return null
                    Condition.Group(Connector.AND, listOf(child), negated = true)
                } else {
                    val children = listOfNotNull(
                        receiver?.let { parsePredicate(it, ctx, depth + 1) },
                        expr.argumentList.expressions.firstOrNull()?.let { parsePredicate(it, ctx, depth + 1) },
                    )
                    if (children.size < 2) {
                        children.firstOrNull()
                    } else {
                        Condition.Group(connector, children)
                    }
                }
            }
            op in COMPARE_OPS -> {
                val column = receiver?.let { resolvePath(it, ctx, depth) }?.let { ctx.columnRefOf(it) } ?: return null
                val value = expr.argumentList.expressions.firstOrNull()?.let { ExpressionEvaluator.evaluate(it) }
                    ?: return null
                Condition.Simple(column, COMPARE_OPS[op]!!, value)
            }
            op in IN_OPS -> {
                val column = receiver?.let { resolvePath(it, ctx, depth) }?.let { ctx.columnRefOf(it) } ?: return null
                val values = expr.argumentList.expressions.map { ExpressionEvaluator.evaluate(it) }
                if (values.isEmpty()) null else Condition.In(column, negated = op == "notIn", values = values)
            }
            op in UNARY_OPS -> {
                val column = receiver?.let { resolvePath(it, ctx, depth) }?.let { ctx.columnRefOf(it) } ?: return null
                Condition.Unary(column, UNARY_OPS[op]!!)
            }
            op in LIKE_OPS -> {
                val column = receiver?.let { resolvePath(it, ctx, depth) }?.let { ctx.columnRefOf(it) } ?: return null
                val (position, negated) = LIKE_OPS[op]!!
                val value = expr.argumentList.expressions.firstOrNull()?.let { ExpressionEvaluator.evaluate(it) }
                    ?: return null
                Condition.Like(column, negated, value, position)
            }
            op in BETWEEN_OPS -> {
                val column = receiver?.let { resolvePath(it, ctx, depth) }?.let { ctx.columnRefOf(it) } ?: return null
                val lo = expr.argumentList.expressions.getOrNull(0)?.let { ExpressionEvaluator.evaluate(it) } ?: return null
                val hi = expr.argumentList.expressions.getOrNull(1)?.let { ExpressionEvaluator.evaluate(it) } ?: return null
                Condition.Between(column, negated = op == "notBetween", lo = lo, hi = hi)
            }
            op in BOOLEAN_OPS -> {
                val column = receiver?.let { resolvePath(it, ctx, depth) }?.let { ctx.columnRefOf(it) } ?: return null
                Condition.Simple(column, "=", SqlValue.Literal(if (op == "isTrue") "TRUE" else "FALSE"))
            }
            else -> null
        }
    }

    /** BooleanBuilder 变量 and/or 引用链收集（`b.and(p1).or(p2)`）→ AND 组（OR 内嵌组）；按源码顺序连接。 */
    private fun booleanBuilderCondition(variable: PsiVariable, ctx: QdCtx, depth: Int): Condition? {
        if (depth > MAX_TRACE_DEPTH) {
            return null
        }
        // 初始谓词（new BooleanBuilder(pred)）
        val initPredicate = (variable.initializer as? PsiMethodCallExpression)
            ?.argumentList?.expressions?.firstOrNull()?.let { parsePredicate(it, ctx, depth + 1) }
        val conditions = mutableListOf<Condition>()
        initPredicate?.let { conditions.add(it) }
        var sawOr = false
        data class RefEntry(val offset: Int, val op: String?, val call: PsiMethodCallExpression)
        // T-HiddenBugPerfScan 券 Y17：ReferencesSearch 仓内定性「较重」（psi/WrapperCache.kt）——
        // 曾每次预览裸跑全默认 scope（字段变量时为全项目）；限 useScope 到变量可见域。
        val refs = ReferencesSearch.search(variable, variable.useScope).mapNotNull { reference ->
            val ref = reference.element as? PsiReferenceExpression ?: return@mapNotNull null
            // .and(pred) / .or(pred)：引用的父是 methodExpression → 调用
            val call = ref.parent?.parent as? PsiMethodCallExpression ?: return@mapNotNull null
            val op = call.methodExpression.referenceName
            if (op != "and" && op != "or") return@mapNotNull null
            RefEntry(call.textRange.startOffset, op, call)
        }.sortedBy { it.offset }
        for (entry in refs) {
            val arg = entry.call.argumentList.expressions.firstOrNull()?.let { parsePredicate(it, ctx, depth + 1) }
                ?: continue
            if (entry.op == "or" && conditions.isNotEmpty()) {
                sawOr = true
                val prev = conditions.removeAt(conditions.size - 1)
                conditions.add(Condition.Group(Connector.OR, listOf(prev, arg)))
            } else {
                conditions.add(arg)
            }
        }
        if (conditions.isEmpty()) return null
        return if (sawOr || conditions.size == 1) {
            conditions.first()
        } else {
            Condition.Group(Connector.AND, conditions)
        }
    }

    private fun isBooleanBuilderType(type: PsiType?): Boolean =
        (type as? PsiClassType)?.className == "BooleanBuilder"

    // ------------------------------------------------------------------
    // Q 路径解析（根 → 实体；属性 → 列）
    // ------------------------------------------------------------------

    /**
     * 路径解析产物。
     *
     * @param entityClass 根 Q 类对应的实体类
     * @param property    receiver 链末段属性名（`QUser.user.status` → status）；根本身为 null
     * @param nestedDepth 属性段数（>1 = 嵌套路径，末段近似 + 注释）
     */
    private data class QdPath(val entityClass: PsiClass, val property: String?, val nestedDepth: Int = 0)

    /** 表达式 → Q 路径（根 = Q 类静态字段 / Q 类型变量；属性段沿 qualifier 外扩收集）。 */
    private fun resolvePath(expr: PsiExpression?, ctx: QdCtx, depth: Int): QdPath? {
        if (expr == null || depth > MAX_TRACE_DEPTH) return null
        val segments = mutableListOf<String>()
        var current: PsiElement? = expr
        while (current is PsiReferenceExpression) {
            val resolved = current.resolve() ?: return null
            // 根形态 1：Q 类静态字段（`QUser.user`）——字段类型即 Q 类
            if (resolved is PsiField && isQClass((resolved.type as? PsiClassType)?.resolve())) {
                val qClass = (resolved.type as? PsiClassType)?.resolve() ?: return null
                val entity = entityOfQClass(qClass) ?: return null
                return QdPath(entity, segments.lastOrNull(), segments.size)
            }
            // 根形态 2：Q 类型变量（`QUser user = QUser.user;`）
            if (resolved is PsiVariable && isQClass((resolved.type as? PsiClassType)?.resolve())) {
                val qClass = (resolved.type as? PsiClassType)?.resolve() ?: return null
                val entity = entityOfQClass(qClass) ?: return null
                return QdPath(entity, segments.lastOrNull(), segments.size)
            }
            // 属性段（Q 类实例字段：StringPath/NumberPath/...）
            segments.add(current.referenceName ?: return null)
            current = current.qualifierExpression
        }
        return null
    }

    /** 根路径（selectFrom/update/delete/insert 实参）：忽略属性段，仅取实体。 */
    private fun resolveRootPath(expr: PsiExpression, ctx: QdCtx, depth: Int): QdPath? =
        resolvePath(expr, ctx, depth)?.let { it.copy(property = null) }

    /** 类是否为 Q 类（简名 Q 前缀 + EntityPathBase/BeanPath 系 superType）。 */
    private fun isQClass(clazz: PsiClass?): Boolean {
        if (clazz == null) return false
        val name = clazz.name ?: return false
        if (!name.startsWith("Q")) return false
        for (superType in clazz.superTypes) {
            val canonical = superType.canonicalText ?: continue
            val simple = canonical.substringBefore('<').substringAfterLast('.')
            if (simple in setOf("EntityPathBase", "BeanPath", "EntityPathBase<", "BeanPath<")) return true
            if (canonical.startsWith("com.querydsl.core.types.dsl.EntityPathBase") ||
                canonical.startsWith("com.querydsl.core.types.dsl.BeanPath")
            ) {
                return true
            }
        }
        return false
    }

    /** Q 类 → 实体类（superType EntityPathBase/BeanPath 泛型第一参；测试环境 stub 按简名匹配）。 */
    private fun entityOfQClass(qClass: PsiClass): PsiClass? {
        for (superType in qClass.superTypes) {
            val canonical = superType.canonicalText ?: continue
            val simple = canonical.substringBefore('<').substringAfterLast('.')
            if (simple in setOf("EntityPathBase", "BeanPath") ||
                canonical.startsWith("com.querydsl.core.types.dsl.EntityPathBase") ||
                canonical.startsWith("com.querydsl.core.types.dsl.BeanPath")
            ) {
                val param = (superType as? PsiClassType)?.parameters?.firstOrNull() as? PsiClassType ?: continue
                return param.resolve()
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 链回溯与上下文
    // ------------------------------------------------------------------

    private fun findEntryCall(from: PsiMethodCallExpression, maxDepth: Int): PsiMethodCallExpression? {
        var current: PsiMethodCallExpression = from
        var depth = 0
        while (depth++ < maxDepth) {
            if (isQuerydslCall(current)) return current
            current = (current.methodExpression.qualifierExpression as? PsiMethodCallExpression) ?: return null
        }
        return null
    }

    /**
     * entry → 链尾完整段列表（头=entry）。
     * 回溯处理调用层 + **引用层**（entry.parent 是外层调用的 methodExpression）两层延续——
     * 漏掉引用层则 segments 恒为 [entry]，where/orderBy/limit/set 全丢（表名来自 entry 实参不受影响）。
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
        // query() 中转形态：queryFactory.query().from(...) —— from 段成为 entry
        return ArgResolver.collectCallChain(outermost)
    }

    /** 转换上下文（每次 convert 独立实例）。 */
    private class QdCtx(val resolveVariables: Boolean) {
        val selectColumns = mutableListOf<ColumnRef>()
        val conditions = mutableListOf<Condition>()
        val orderBy = mutableListOf<OrderByItem>()
        val sets = mutableListOf<SetClause>()
        var insertColumns = mutableListOf<ColumnRef>()
        var insertValues = mutableListOf<SqlValue>()
        var limit: LimitClause? = null
        var offset: SqlValue? = null
        var joinSegmentSeen = false

        /** 实体类 → TableInfo 缓存（QD 预览口径：实体注解映射）。 */
        private val tableCache = mutableMapOf<PsiClass, TableInfo>()

        fun tableOf(entityClass: PsiClass): TableInfo =
            tableCache.getOrPut(entityClass) { JpaEntityResolver().resolve(entityClass) }

        fun addOrderBy(items: List<OrderByItem>) {
            orderBy.addAll(items)
        }

        /** 路径 → 列引用：属性列名 = 实体注解映射（columnByField）或 snake 兜底；嵌套路径末段近似。 */
        fun columnRefOf(path: QdPath): ColumnRef {
            val property = path.property
            val columnName = if (property == null) {
                path.entityClass.name?.let { NamingStrategy.toSnakeCase(it) } ?: "id"
            } else {
                tableOf(path.entityClass).columnByField(property)?.columnName ?: NamingStrategy.toSnakeCase(property)
            }
            return ColumnRef(columnName = columnName)
        }
    }
}
