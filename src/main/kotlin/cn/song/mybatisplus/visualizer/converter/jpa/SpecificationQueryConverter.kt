package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.RepositoryBaseTypes
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParamQuote
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable

/**
 * JpaSpecificationExecutor 调用转换器（T-J5）。
 *
 * 把 `userRepository.findAll(spec)` / `findOne(...)` / `count(...)` / `exists(...)` / `delete(...)`
 * 的 Specification 实参当作谓词源，解析 lambda 体（`cb.equal(root.get("x"), ?)` 等）与
 * Specification 组合器（where/not/and/or/allOf/anyOf），生成 SELECT / COUNT / DELETE 语句。
 *
 * ## 识别（[isSpecificationExecutorCall]）
 *
 *  1. receiver 类型父链含 Spring Data Repository 基类（与 DerivedMethodLocator /
 *     JpaCrudOperationConverter 名单对齐）；
 *  2. 方法名 ∈ [EXECUTOR_METHODS]（JpaSpecificationExecutor 8 方法去 findBy fluent 后的可渲染子集）；
 *  3. 首实参「Specification 形态」：lambda（`->` 三参形态）或类型/初始化式含 Specification
 *     （`Example` 实参不命中——归 [QueryByExampleConverter]；无参 `findAll()` 也不命中——归
 *     JpaCrudOperationConverter 的 CRUD 语义）。
 *
 * ## 谓词解析面（lambda 体 / 组合器叶子）
 *
 *  - 比较：cb.equal/notEqual/greaterThan(greaterThanOrEqualTo/gt/ge)/lessThan(.../lt/le) → Simple；
 *  - 一元：isNull/isNotNull；区间：between；模糊：like/notLike；集合：in（链式 .value()/直接实参）；
 *  - 逻辑：cb.and/or → Group；cb.not → 取反组；isTrue/isFalse → = TRUE/FALSE；
 *  - 路径：`root.get("userName")` / `root.get(Xxx_.userName)`（静态字段引用取引用名）；
 *    嵌套路径 `root.get("a").get("b")` 静态降级注释（关联列需运行时元数据）；
 *  - 值：字面量/可追踪变量内联（[ExpressionEvaluator]），方法参数等运行时值 `?` 占位 + 参数行；
 *  - 组合：`Specification.where/and/or/not/allOf/anyOf`、spec 变量链式 `.and(...)/.or(...)`、
 *    谓词变量（`Predicate p = cb.equal(...)`）有界追踪（声明先于使用、深度 ≤ 5）。
 *
 * ## 降级（绝不抛异常）
 *
 * 无法解析的叶子调用 → `Condition.Raw` 原样注释透传；复杂组合整体不可解析 → SQL 呈现
 * 「运行时构建」注释卡；receiver 非 Spring Data 家族 / 无 Specification 实参 → [convert] 返回 null
 * （交回 JpaCrudOperationConverter / MP 批量路径，存量行为零变化）。
 */
object SpecificationQueryConverter {

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /**
     * Specification 预览结果。
     *
     * @param statement    语句模型（SELECT/COUNT/DELETE），供面板条件交互复用
     * @param sql          渲染 SQL（含语义注释行）
     * @param paramSources `?` 占位参数来源列表（按出现顺序）
     * @param paramHints   与 [paramSources] 并行的回填提示
     * @param tableName    实体表名
     * @param columns      实体列名（面板上下文）
     * @param warning      风险警示（delete(Spec) 无条件时 BlockAttack 风格）
     */
    data class SpecificationPreview(
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

    /** Spring Data Repository 基类 FQN（SD6 起统一走 [RepositoryBaseTypes] 名单，receiver 家族判定与其他三处对齐）。 */
    private val REPOSITORY_BASE_TYPES: Set<String> = RepositoryBaseTypes.FQNS

    /** 基类简单名（canonicalText 不可解析时的兜底）。 */
    private val REPOSITORY_BASE_SIMPLE_NAMES: Set<String> = RepositoryBaseTypes.SIMPLE_NAMES

    /** JpaSpecificationExecutor 家族方法名（锚点 + 转换共用）。 */
    val EXECUTOR_METHODS: Set<String> = setOf(
        "findAll", "findOne", "count", "exists", "delete", "findBy",
    )

    /** cb 比较方法 → SQL 操作符。 */
    private val COMPARISON_OPS: Map<String, String> = mapOf(
        "equal" to "=",
        "notEqual" to "!=",
        "greaterThan" to ">",
        "greaterThanOrEqualTo" to ">=",
        "gt" to ">",
        "ge" to ">=",
        "lessThan" to "<",
        "lessThanOrEqualTo" to "<=",
        "lt" to "<",
        "le" to "<=",
    )

    /** 谓词追踪最大深度（Specification 组合变量链）。 */
    private const val MAX_TRACE_DEPTH = 5

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /**
     * 调用是否为 Specification executor 形态（锚点判定与转换同源，宁可无图标不可死图标）。
     */
    fun isSpecificationExecutorCall(call: PsiMethodCallExpression): Boolean = runCatching {
        val methodName = call.methodExpression.referenceName ?: return@runCatching false
        if (methodName !in EXECUTOR_METHODS) return@runCatching false
        if (call.argumentList.expressions.isEmpty()) return@runCatching false
        if (!hasRepositoryReceiver(call)) return@runCatching false
        isSpecificationLike(call.argumentList.expressions.first())
    }.getOrDefault(false)

    /**
     * 转换 Specification executor 调用。
     *
     * @param call             方法调用表达式
     * @param resolveVariables 变量求值开关（false 时实参一律 `?` 占位）
     * @return 预览结果；receiver 非 Spring Data 家族 / 无 Specification 实参 / 实体不可解析时 null
     */
    fun convert(call: PsiMethodCallExpression, resolveVariables: Boolean = true): SpecificationPreview? = runCatching {
        convertInternal(call, resolveVariables)
    }.getOrNull()

    // ------------------------------------------------------------------
    // receiver / 实体解析
    // ------------------------------------------------------------------

    private fun convertInternal(call: PsiMethodCallExpression, resolveVariables: Boolean): SpecificationPreview? {
        val methodName = call.methodExpression.referenceName ?: return null
        if (methodName !in EXECUTOR_METHODS) return null
        val firstArg = call.argumentList.expressions.firstOrNull() ?: return null
        if (!isSpecificationLike(firstArg)) return null
        if (!hasRepositoryReceiver(call)) return null

        val entityClass = resolveRepositoryEntity(call) ?: return null
        val table = JpaEntityResolver().resolve(entityClass)
        if (table.entityClassName.isBlank()) return null

        val parseCtx = ParseContext(resolveVariables)
        val conditions = resolveSpecificationArg(firstArg, table, parseCtx, 0)

        val notes = mutableListOf<String>()
        notes += parseCtx.notes
        if (parseCtx.unresolvedCombo) {
            notes += "Specification 组合含静态不可解析片段（嵌套变量/远端组合），仅呈现已解析部分"
        }

        val selectColumnList = table.columns.joinToString(", ") { it.columnName }.ifEmpty { "*" }
        val from = "FROM ${table.tableName}"
        return when (methodName) {
            "count" -> {
                val statement = SelectStatement(
                    table = table,
                    selectColumns = listOf(ColumnRef(columnName = "*", function = "COUNT")),
                    conditions = conditions,
                )
                SpecificationPreview(
                    methodName = methodName,
                    statement = statement,
                    sql = renderWithNotes("SELECT COUNT(*)\n$from", conditions, notes),
                    paramSources = parseCtx.paramSources,
                    paramHints = parseCtx.paramHints,
                    tableName = table.tableName,
                    columns = table.allColumnNames,
                )
            }
            "exists" -> {
                val statement = SelectStatement(
                    table = table,
                    selectColumns = listOf(ColumnRef(columnName = "1")),
                    conditions = conditions,
                )
                SpecificationPreview(
                    methodName = methodName,
                    statement = statement,
                    sql = renderWithNotes("SELECT 1\n$from", conditions, notes + "exists：命中即真语义（运行时渲染 SELECT 1 ... LIMIT 1 形态）"),
                    paramSources = parseCtx.paramSources,
                    paramHints = parseCtx.paramHints,
                    tableName = table.tableName,
                    columns = table.allColumnNames,
                )
            }
            "delete" -> {
                val statement = DeleteStatement(table = table, conditions = conditions)
                val warning = if (conditions.isEmpty()) "⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！" else null
                SpecificationPreview(
                    methodName = methodName,
                    statement = statement,
                    sql = renderWithNotes("DELETE FROM ${table.tableName}", conditions, notes),
                    paramSources = parseCtx.paramSources,
                    paramHints = parseCtx.paramHints,
                    tableName = table.tableName,
                    columns = table.allColumnNames,
                    warning = warning,
                )
            }
            // findAll / findOne / findBy(fluent) 同为实体 SELECT
            else -> {
                val statement = SelectStatement(table = table, conditions = conditions)
                val extraNotes = if (methodName == "findBy") {
                    listOf("findBy(spec, function) fluent 形态：按 findAll 语义呈现（fetch function 运行时回调不解析）")
                } else {
                    emptyList()
                }
                SpecificationPreview(
                    methodName = methodName,
                    statement = statement,
                    sql = renderWithNotes("SELECT $selectColumnList\n$from", conditions, notes + extraNotes),
                    paramSources = parseCtx.paramSources,
                    paramHints = parseCtx.paramHints,
                    tableName = table.tableName,
                    columns = table.allColumnNames,
                )
            }
        }
    }

    /** 条件 → WHERE SQL 文本（顶层 AND 连接；组/原样透传）。 */
    private fun whereText(conditions: List<Condition>): String? {
        if (conditions.isEmpty()) return null
        return conditions.joinToString("\n  AND ") { ConditionText.render(it) }
    }

    /** 拼装 SQL + WHERE + 语义注释行（`-- ` 前缀，对齐 JpaCrudOperationConverter 惯例）。 */
    private fun renderWithNotes(head: String, conditions: List<Condition>, notes: List<String>): String {
        val sb = StringBuilder(head)
        whereText(conditions)?.let { sb.append("\nWHERE ").append(it) }
        for (note in notes.filter { it.isNotBlank() }) sb.append("\n-- ").append(note)
        return sb.toString()
    }

    /** Condition → SQL 文本渲染器（与 SqlGenerator 条件渲染同形，供字符串拼装路径复用）。 */
    private object ConditionText {
        fun render(condition: Condition): String = when (condition) {
            is Condition.Simple -> "${condition.column.qualifiedName} ${condition.op} ${renderValue(condition.value)}"
            is Condition.Unary -> "${condition.column.qualifiedName} ${condition.op}"
            is Condition.In -> {
                val op = if (condition.negated) "NOT IN" else "IN"
                "${condition.column.qualifiedName} $op (${condition.values.joinToString(", ") { renderValue(it) }})"
            }
            is Condition.Between ->
                "${condition.column.qualifiedName} ${if (condition.negated) "NOT BETWEEN" else "BETWEEN"} " +
                    "${renderValue(condition.lo)} AND ${renderValue(condition.hi)}"
            is Condition.Like -> {
                val op = if (condition.negated) "NOT LIKE" else "LIKE"
                "${condition.column.qualifiedName} $op ${renderValue(condition.pattern)}"
            }
            is Condition.Group -> {
                val inner = condition.children.joinToString(" ${condition.connector.name} ") { render(it) }
                val body = "($inner)"
                if (condition.negated) "NOT $body" else body
            }
            is Condition.ColumnComparison ->
                "${condition.left.qualifiedName} = ${condition.right.qualifiedName}"
            is Condition.Raw -> condition.sqlText
        }

        private fun renderValue(value: SqlValue): String = when (value) {
            is SqlValue.Literal -> value.raw
            is SqlValue.Placeholder -> "?"
        }
    }

    // ------------------------------------------------------------------
    // Specification 实参解析（组合器树）
    // ------------------------------------------------------------------

    /** 解析上下文：参数行收集 + 注释 + 降级标志。 */
    private class ParseContext(val resolveVariables: Boolean) {
        val paramSources = mutableListOf<String>()
        val paramHints = mutableListOf<ParamHint>()
        val notes = mutableListOf<String>()
        var unresolvedCombo = false

        fun addParam(source: String, hint: ParamHint) {
            paramSources += source
            paramHints += hint
        }
    }

    /**
     * 解析 Specification 实参（lambda / 变量 / 组合器链）→ 顶层条件列表（AND 连接）。
     */
    private fun resolveSpecificationArg(
        arg: PsiExpression,
        table: TableInfo,
        ctx: ParseContext,
        depth: Int,
    ): List<Condition> {
        val node = resolveSpecNode(arg, table, ctx, depth)
        return flattenNode(node, ctx)
    }

    /** Specification 组合节点（有界建模）。 */
    private sealed class SpecNode {
        /** lambda / 谓词变量已解析出的条件（AND 连接）。 */
        class Preds(val conditions: List<Condition>) : SpecNode()

        class And(val parts: List<SpecNode>) : SpecNode()

        class Or(val parts: List<SpecNode>) : SpecNode()

        class Not(val inner: SpecNode) : SpecNode()

        object Unknown : SpecNode()
    }

    private fun resolveSpecNode(expr: PsiExpression?, table: TableInfo, ctx: ParseContext, depth: Int): SpecNode {
        if (expr == null || depth > MAX_TRACE_DEPTH) {
            ctx.unresolvedCombo = true
            return SpecNode.Unknown
        }
        return when (expr) {
            is PsiLambdaExpression -> SpecNode.Preds(parseLambdaBody(expr, table, ctx))
            is PsiMethodCallExpression -> resolveCombinatorCall(expr, table, ctx, depth)
            is PsiReferenceExpression -> {
                val initializer = (expr.resolve() as? PsiVariable)?.initializer
                if (initializer != null) {
                    resolveSpecNode(initializer, table, ctx, depth + 1)
                } else {
                    ctx.unresolvedCombo = true
                    SpecNode.Unknown
                }
            }
            else -> {
                ctx.unresolvedCombo = true
                SpecNode.Unknown
            }
        }
    }

    /** 组合器调用解析：Specification.where/not/allOf/anyOf 与 spec 变量链式 and/or。 */
    private fun resolveCombinatorCall(call: PsiMethodCallExpression, table: TableInfo, ctx: ParseContext, depth: Int): SpecNode {
        val name = call.methodExpression.referenceName ?: return SpecNode.Unknown
        val args = call.argumentList.expressions
        // 链式 and/or 的 receiver：Specification 静态工厂（qualifier 文本 Specification）或 spec 变量
        val qualifierText = call.methodExpression.qualifierExpression?.text.orEmpty()
        val isStaticFactory = qualifierText.substringAfterLast('.') == "Specification"
        return when {
            isStaticFactory && name == "where" -> resolveSpecNode(args.firstOrNull(), table, ctx, depth + 1)
            isStaticFactory && name == "not" -> SpecNode.Not(resolveSpecNode(args.firstOrNull(), table, ctx, depth + 1))
            isStaticFactory && name == "allOf" -> SpecNode.And(args.map { resolveSpecNode(it, table, ctx, depth + 1) })
            isStaticFactory && name == "anyOf" -> SpecNode.Or(args.map { resolveSpecNode(it, table, ctx, depth + 1) })
            name == "and" -> SpecNode.And(listOf(resolveSpecNode(call.methodExpression.qualifierExpression, table, ctx, depth + 1)) + args.map { resolveSpecNode(it, table, ctx, depth + 1) })
            name == "or" -> SpecNode.Or(listOf(resolveSpecNode(call.methodExpression.qualifierExpression, table, ctx, depth + 1)) + args.map { resolveSpecNode(it, table, ctx, depth + 1) })
            // Specification 变量上的其他方法（isSatisfiedBy 等）不解析
            else -> {
                ctx.unresolvedCombo = true
                SpecNode.Unknown
            }
        }
    }

    /** 组合树扁平化：顶层 AND 展平；OR/NOT 物化为 Group。 */
    private fun flattenNode(node: SpecNode, ctx: ParseContext): List<Condition> = when (node) {
        is SpecNode.Preds -> node.conditions.flatMap { flattenAndGroup(it) }
        is SpecNode.And -> node.parts.flatMap { flattenNode(it, ctx) }
        is SpecNode.Or -> {
            val children = node.parts.flatMap { flattenNode(it, ctx) }
            if (children.isEmpty()) emptyList() else listOf(Condition.Group(Connector.OR, children))
        }
        is SpecNode.Not -> {
            val children = flattenNode(node.inner, ctx)
            if (children.isEmpty()) emptyList() else listOf(Condition.Group(Connector.AND, children, negated = true))
        }
        SpecNode.Unknown -> emptyList()
    }

    /** 顶层非取反 AND 组展平（`cb.and(a, b)` 与条件列表语义一致，渲染按顶层 AND 连接）。 */
    private fun flattenAndGroup(condition: Condition): List<Condition> = when {
        condition is Condition.Group &&
            condition.connector == Connector.AND && !condition.negated ->
            condition.children.flatMap { flattenAndGroup(it) }
        else -> listOf(condition)
    }

    // ------------------------------------------------------------------
    // lambda 体谓词解析
    // ------------------------------------------------------------------

    /**
     * 解析 Specification lambda 体（`(root, query, cb) -> ...`）。
     *
     * 表达式体 → 单谓词；块体 → `return` 表达式与 `query.where(...)` 调用收集（AND 连接）。
     */
    private fun parseLambdaBody(lambda: PsiLambdaExpression, table: TableInfo, ctx: ParseContext): List<Condition> {
        val params = lambda.parameterList.parameters
        if (params.size < 3) {
            ctx.unresolvedCombo = true
            return emptyList()
        }
        val lambdaCtx = LambdaContext(
            rootParamName = params[0].name ?: "root",
            queryParamName = params[1].name ?: "query",
            cbParamName = params[2].name ?: "cb",
            table = table,
            ctx = ctx,
        )
        val body = lambda.body ?: return emptyList()
        val conditions = mutableListOf<Condition>()
        if (body is PsiExpression) {
            parsePredicate(body, lambdaCtx)?.let { conditions += it }
        } else if (body is PsiCodeBlock) {
            // return <谓词>/null 语句 + query.where(p1, p2) 调用（谓词变量优先按声明登记解析）
            for (statement in body.statements) {
                collectBlockPredicates(statement, lambdaCtx, conditions)
            }
        }
        return conditions
    }

    private class LambdaContext(
        val rootParamName: String,
        val queryParamName: String,
        val cbParamName: String,
        val table: TableInfo,
        val ctx: ParseContext,
    ) {
        /** 谓词变量登记：`Predicate p = cb.equal(...)`（块体内声明先于使用）。 */
        val predicateVars = mutableMapOf<String, Condition>()
    }

    /** 块体单语句的谓词收集（声明/return/query.where）。 */
    private fun collectBlockPredicates(statement: PsiStatement, lc: LambdaContext, sink: MutableList<Condition>) {
        when (statement) {
            is com.intellij.psi.PsiDeclarationStatement -> {
                for (element in statement.declaredElements) {
                    val variable = element as? PsiLocalVariable ?: continue
                    val initializer = variable.initializer ?: continue
                    parsePredicate(initializer, lc)?.let { condition ->
                        lc.predicateVars[variable.name] = condition
                        sink += condition
                    }
                }
            }
            is com.intellij.psi.PsiReturnStatement -> {
                parsePredicate(statement.returnValue, lc)?.let { sink += it }
            }
            is com.intellij.psi.PsiExpressionStatement -> {
                val call = statement.expression as? PsiMethodCallExpression ?: return
                // query.where(p1, p2)：谓词收集（cb.and 已由解析器处理组合）
                if (call.methodExpression.referenceName == "where") {
                    for (arg in call.argumentList.expressions) {
                        parsePredicate(arg, lc)?.let { sink += it }
                    }
                }
            }
        }
    }

    /**
     * 谓词表达式 → Condition。识别 cb.* 条件方法与谓词变量引用；
     * 未知形态降级 Raw（原样注释透传，不产生错误 SQL）。
     */
    private fun parsePredicate(expr: PsiExpression?, lc: LambdaContext): Condition? {
        if (expr == null) return null
        if (expr is PsiMethodCallExpression) {
            val name = expr.methodExpression.referenceName ?: return rawCondition(expr)
            return when {
                name in COMPARISON_OPS.keys -> buildComparison(expr, lc)
                name == "isNull" -> unary(expr, "IS NULL", lc)
                name == "isNotNull" -> unary(expr, "IS NOT NULL", lc)
                name == "isTrue" -> booleanPredicate(expr, "TRUE", lc)
                name == "isFalse" -> booleanPredicate(expr, "FALSE", lc)
                name == "like" -> like(expr, negated = false, lc)
                name == "notLike" -> like(expr, negated = true, lc)
                name == "between" -> between(expr, lc)
                name == "in" -> inPredicate(expr, lc)
                name == "and" -> group(expr, Connector.AND, lc)
                name == "or" -> group(expr, Connector.OR, lc)
                name == "not" -> {
                    val inner = parsePredicate(expr.argumentList.expressions.firstOrNull(), lc)
                        ?: return rawCondition(expr)
                    Condition.Group(Connector.AND, listOf(inner), negated = true)
                }
                name == "conjunction" -> Condition.Raw("1 = 1")
                name == "disjunction" -> Condition.Raw("1 = 0")
                // 谓词变量引用（cb 之外：`p` 等已登记变量）→ 查登记表；其余降级 Raw
                isCbCall(expr, lc) -> rawCondition(expr)
                else -> predicateVarOrNull(expr, lc) ?: rawCondition(expr)
            }
        }
        // 纯变量引用 → 谓词变量登记表
        if (expr is PsiReferenceExpression) {
            return predicateVarOrNull(expr, lc)
        }
        return if (expr is PsiMethodCallExpression) rawCondition(expr) else Condition.Raw(expr.text)
    }

    /** 谓词变量解析（登记表命中 → 已解析 Condition；未命中 → null）。 */
    private fun predicateVarOrNull(expr: PsiExpression, lc: LambdaContext): Condition? {
        val name = (expr as? PsiReferenceExpression)?.referenceName ?: return null
        return lc.predicateVars[name]
    }

    private fun isCbCall(call: PsiMethodCallExpression, lc: LambdaContext): Boolean =
        call.methodExpression.qualifierExpression?.text == lc.cbParamName

    private fun rawCondition(call: PsiMethodCallExpression): Condition =
        Condition.Raw("/* ${call.text}（Specification 条件运行时构建，静态预览降级） */")

    /** cb.equal(root.get("x"), value) → Simple；路径/值不可解析降级 Raw。 */
    private fun buildComparison(call: PsiMethodCallExpression, lc: LambdaContext): Condition {
        val op = COMPARISON_OPS[call.methodExpression.referenceName] ?: return rawCondition(call)
        val args = call.argumentList.expressions
        if (args.size < 2) return rawCondition(call)
        val column = columnRef(args[0], lc) ?: return rawCondition(call)
        val fieldType = fieldTypeOf(args[0], lc)
        val value = evaluateValue(args[1], lc, ParamQuote.decide(fieldType), temporal = ParamQuote.isTemporal(fieldType))
        return Condition.Simple(column, op, value)
    }

    /** 路径表达式的实体字段类型（列回填引号提示用；不可解析 null）。 */
    private fun fieldTypeOf(expr: PsiExpression?, lc: LambdaContext): String? {
        val call = expr as? PsiMethodCallExpression ?: return null
        if (call.methodExpression.referenceName != "get") return null
        val arg = call.argumentList.expressions.firstOrNull() ?: return null
        val fieldName = when (arg) {
            is PsiLiteralExpression -> arg.value as? String
            is PsiReferenceExpression -> arg.referenceName
            else -> null
        } ?: return null
        return lc.table.columnByField(fieldName)?.fieldType
    }

    private fun unary(call: PsiMethodCallExpression, op: String, lc: LambdaContext): Condition {
        val column = columnRef(call.argumentList.expressions.firstOrNull(), lc) ?: return rawCondition(call)
        return Condition.Unary(column, op)
    }

    private fun booleanPredicate(call: PsiMethodCallExpression, literal: String, lc: LambdaContext): Condition {
        val column = columnRef(call.argumentList.expressions.firstOrNull(), lc) ?: return rawCondition(call)
        return Condition.Simple(column, "=", SqlValue.Literal(literal, literal))
    }

    private fun like(call: PsiMethodCallExpression, negated: Boolean, lc: LambdaContext): Condition {
        val args = call.argumentList.expressions
        if (args.size < 2) return rawCondition(call)
        val column = columnRef(args[0], lc) ?: return rawCondition(call)
        // Specification like 的模式自带 %（与 Criteria 一致），NONE 表示不额外拼 %
        return Condition.Like(column, negated, evaluateValue(args[1], lc), LikePosition.NONE)
    }

    private fun between(call: PsiMethodCallExpression, lc: LambdaContext): Condition {
        val args = call.argumentList.expressions
        if (args.size < 3) return rawCondition(call)
        val column = columnRef(args[0], lc) ?: return rawCondition(call)
        val temporal = ParamQuote.isTemporal(fieldTypeOf(args[0], lc))
        return Condition.Between(
            column,
            false,
            evaluateValue(args[1], lc, temporal = temporal),
            evaluateValue(args[2], lc, temporal = temporal),
        )
    }

    /** cb.in(root.get("x"), v1, v2) / cb.in(x).value(v).value(v)。 */
    private fun inPredicate(call: PsiMethodCallExpression, lc: LambdaContext): Condition {
        val args = call.argumentList.expressions
        if (args.isEmpty()) return rawCondition(call)
        val column = columnRef(args[0], lc) ?: return rawCondition(call)
        val values = args.drop(1).map { evaluateValue(it, lc) } + collectInChainValues(call, lc)
        if (values.isEmpty()) return rawCondition(call)
        return Condition.In(column, false, values)
    }

    /** 沿 `.value(...)` 链收集值实参（cb.in(x).value(a).value(b)）。 */
    private fun collectInChainValues(call: PsiMethodCallExpression, lc: LambdaContext): List<SqlValue> {
        val values = mutableListOf<SqlValue>()
        var current: PsiElement? = call.parent
        while (true) {
            val ref = current as? PsiReferenceExpression ?: break
            val chained = ref.parent as? PsiMethodCallExpression ?: break
            if (chained.methodExpression.referenceName != "value") break
            chained.argumentList.expressions.forEach { values += evaluateValue(it, lc) }
            current = chained.parent
        }
        return values
    }

    private fun group(call: PsiMethodCallExpression, connector: Connector, lc: LambdaContext): Condition {
        val children = call.argumentList.expressions.mapNotNull { parsePredicate(it, lc) }
        if (children.isEmpty()) return rawCondition(call)
        return Condition.Group(connector, children)
    }

    // ------------------------------------------------------------------
    // 路径与值
    // ------------------------------------------------------------------

    /**
     * 路径表达式 → ColumnRef。
     *
     * `root.get("userName")` / `root.get(Xxx_.userName)`（静态字段引用取引用名）；
     * 嵌套路径 `root.get("a").get("b")` 静态降级（注释 Raw 由调用方处理——此处返回 null）。
     */
    private fun columnRef(expr: PsiExpression?, lc: LambdaContext): ColumnRef? {
        val call = expr as? PsiMethodCallExpression ?: return null
        if (call.methodExpression.referenceName != "get") return null
        // receiver 必须是 root 参数（或 root.get 链——嵌套时不解析）
        val qualifier = call.methodExpression.qualifierExpression ?: return null
        if (qualifier !is PsiReferenceExpression || qualifier.referenceName != lc.rootParamName) return null
        val arg = call.argumentList.expressions.firstOrNull() ?: return null
        val fieldName = when (arg) {
            is PsiLiteralExpression -> arg.value as? String
            is PsiReferenceExpression -> arg.referenceName
            else -> null
        } ?: return null
        val columnName = lc.table.columnByField(fieldName)?.columnName ?: NamingStrategy.toSnakeCase(fieldName)
        return ColumnRef(columnName = columnName)
    }

    /** 值表达式求值：字面量/可追踪变量内联；运行时值 `?` 占位 + 参数行登记。 */
    private fun evaluateValue(
        expr: PsiExpression?,
        lc: LambdaContext,
        quoteHint: Boolean? = null,
        temporal: Boolean = false,
    ): SqlValue {
        if (expr == null) return SqlValue.Placeholder("?")
        val value = if (lc.ctx.resolveVariables) {
            ExpressionEvaluator.evaluate(expr)
        } else {
            SqlValue.Placeholder(expr.text)
        }
        return when (value) {
            is SqlValue.Literal -> value
            is SqlValue.Placeholder -> {
                val source = expr.text.ifBlank { "?" }
                lc.ctx.addParam(source, ParamHint(quote = quoteHint, temporal = temporal))
                SqlValue.Placeholder(source)
            }
        }
    }

    // ------------------------------------------------------------------
    // 实参形态判定与实体解析
    // ------------------------------------------------------------------

    /** 首实参是否为 Specification 形态（lambda 三参形态 / 类型或初始化式含 Specification）。 */
    private fun isSpecificationLike(arg: PsiExpression): Boolean = runCatching {
        if (arg is PsiLambdaExpression) {
            // Specification.toPredicate(root, query, cb) 三参；两参 lambda（Root/Query）也为 Specification 变体
            return@runCatching arg.parameterList.parameters.size >= 2
        }
        val typeText = (arg.type as? PsiClassType)?.canonicalText.orEmpty()
        if (typeText.substringBefore('<').endsWith("Specification")) return@runCatching true
        // 变量初始化式（Specification<UserEntity> spec = ...）类型即可判定；再兜底初始化式文本
        val variable = (arg as? PsiReferenceExpression)?.resolve() as? PsiVariable
        val varTypeText = (variable?.type as? PsiClassType)?.canonicalText.orEmpty()
        varTypeText.substringBefore('<').endsWith("Specification") ||
            variable?.initializer?.text?.contains("Specification") == true
    }.getOrDefault(false)

    /** receiver 类型父链是否含 Spring Data Repository 基类。 */
    private fun hasRepositoryReceiver(call: PsiMethodCallExpression): Boolean {
        val qualifier = call.methodExpression.qualifierExpression ?: return false
        val varType = when (qualifier) {
            is PsiReferenceExpression ->
                (qualifier.resolve() as? PsiVariable)?.type as? PsiClassType ?: qualifier.type as? PsiClassType
            else -> qualifier.type as? PsiClassType
        } ?: return false
        return isRepositoryType(varType)
    }

    private fun isRepositoryType(type: PsiClassType): Boolean {
        val direct = type.canonicalText?.substringBefore('<') ?: return false
        if (direct in REPOSITORY_BASE_TYPES || direct in REPOSITORY_BASE_SIMPLE_NAMES) return true
        val clazz = type.resolve() ?: return false
        for (superType in clazz.superTypes) {
            val canonical = superType.canonicalText ?: continue
            val simple = canonical.substringBefore('<').substringAfterLast('.')
            if (REPOSITORY_BASE_TYPES.any { canonical.startsWith(it) } || simple in REPOSITORY_BASE_SIMPLE_NAMES) {
                return true
            }
        }
        return false
    }

    /** Repository 泛型实体解析（Spring Data 基类第一个类型参数；不可解析回退变量类型首泛型）。 */
    private fun resolveRepositoryEntity(call: PsiMethodCallExpression): PsiClass? {
        val qualifier = call.methodExpression.qualifierExpression ?: return null
        val varType = when (qualifier) {
            is PsiReferenceExpression ->
                (qualifier.resolve() as? PsiVariable)?.type as? PsiClassType ?: qualifier.type as? PsiClassType
            else -> qualifier.type as? PsiClassType
        } ?: return null
        val clazz = varType.resolve()
        if (clazz != null) {
            for (superType in clazz.superTypes) {
                val entity = entityFromSuperType(superType) ?: continue
                return entity
            }
        }
        return (varType.parameters.firstOrNull() as? PsiClassType)?.resolve()
    }

    /** superType 第一个类型参数（Spring Data 基类泛型约定：第一参数为实体类）。 */
    private fun entityFromSuperType(superType: PsiType): PsiClass? {
        val classType = superType as? PsiClassType ?: return null
        if (classType.parameters.isEmpty()) return null
        return (classType.parameters.firstOrNull() as? PsiClassType)?.resolve()
    }
}
