package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.ExpressionEvaluator
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.RepositoryBaseTypes
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParamQuote
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable

/**
 * QueryByExampleExecutor 调用转换器（T-J20）。
 *
 * 把 `userRepository.findAll(Example.of(probe[, matcher]))` / `findOne(...)` / `count(...)` / `exists(...)`
 * 的 Example 实参解析为等值 WHERE：probe 实体的「非 null 字段」（静态可得的 setter/字段赋值）
 * → `col = value`（字面量内联 / 运行时 `?` 占位）。
 *
 * ## 识别（[isExampleCall]）
 *
 *  1. receiver 类型父链含 Spring Data Repository 基类；
 *  2. 方法名 ∈ [EXAMPLE_METHODS]（QueryByExampleExecutor findOne/findAll/count/exists）；
 *  3. 首实参 Example 形态：类型 `Example` 或初始化式 `Example.of(...)`（Specification lambda 不命中——归
 *     [SpecificationQueryConverter]；无参 findAll() 归 JpaCrudOperationConverter）。
 *
 * ## ExampleMatcher 语义面（有界）
 *
 *  - `matching()/matchingAll()` → 默认 AND；`matchingAny()` → 条件 OR 连接；
 *  - `withIgnorePaths("age", ...)` → 剔除列（按实体字段名或列名匹配）；
 *  - `withIgnoreCase()` → 等值条件降级 `LOWER(col) = LOWER(v)`（Raw 形态，保语义可读）；
 *  - `withMatcher("field", GenericPropertyMatchers.contains()/startsWith()/endsWith()/exact())` →
 *    CONTAINING → `LIKE '%v%'`、STARTING → `'v%'`、ENDING → `'%v'`、EXACT → 等值；
 *  - `withStringMatcher(StringMatcher.CONTAINING/...)` → 全字段默认匹配器同上；
 *  - `withIncludeNulls()` / NullHandler → 运行时按 probe null 值生成 IS NULL，静态不可得 → 注释说明。
 *
 * ## 降级（绝不抛异常）
 *
 * probe 变量不可追踪（跨方法/构建器形态）→ SQL 呈现「运行时确定」注释卡；receiver 非 Spring Data
 * 家族 / 实参非 Example → 返回 null（交回既有管道，存量行为零变化）。
 */
object QueryByExampleConverter {

    // ------------------------------------------------------------------
    // 结果模型
    // ------------------------------------------------------------------

    /**
     * Query By Example 预览结果。
     *
     * @param sql          渲染 SQL（含语义注释行）
     * @param paramSources `?` 占位参数来源列表（按出现顺序）
     * @param paramHints   与 [paramSources] 并行的回填提示（LIKE 条件带 likePosition）
     * @param tableName    实体表名
     * @param columns      实体列名
     * @param statement    语句模型（SELECT/COUNT），供上层复用
     */
    data class ExamplePreview(
        val methodName: String,
        val sql: String,
        val paramSources: List<String> = emptyList(),
        val paramHints: List<ParamHint> = emptyList(),
        val tableName: String = "",
        val columns: List<String> = emptyList(),
        val statement: SelectStatement? = null,
    )

    // ------------------------------------------------------------------
    // 名单
    // ------------------------------------------------------------------

    /** Spring Data Repository 基类 FQN（SD6 起统一走 [RepositoryBaseTypes] 名单，与 SpecificationQueryConverter/JpaCrudOperationConverter 对齐）。 */
    private val REPOSITORY_BASE_TYPES: Set<String> = RepositoryBaseTypes.FQNS

    private val REPOSITORY_BASE_SIMPLE_NAMES: Set<String> = RepositoryBaseTypes.SIMPLE_NAMES

    /** QueryByExampleExecutor 家族方法名（锚点 + 转换共用）。 */
    val EXAMPLE_METHODS: Set<String> = setOf("findOne", "findAll", "count", "exists")

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /** 调用是否为 Query By Example 形态（锚点判定与转换同源）。 */
    fun isExampleCall(call: PsiMethodCallExpression): Boolean = runCatching {
        val methodName = call.methodExpression.referenceName ?: return@runCatching false
        if (methodName !in EXAMPLE_METHODS) return@runCatching false
        if (call.argumentList.expressions.isEmpty()) return@runCatching false
        if (!hasRepositoryReceiver(call)) return@runCatching false
        isExampleLike(call.argumentList.expressions.first())
    }.getOrDefault(false)

    /**
     * 转换 Query By Example 调用。
     *
     * @param call             方法调用表达式
     * @param resolveVariables 变量求值开关（false 时 probe 字段值一律 `?` 占位）
     * @return 预览结果；receiver 非 Spring Data 家族 / 实参非 Example / 实体不可解析时 null
     */
    fun convert(call: PsiMethodCallExpression, resolveVariables: Boolean = true): ExamplePreview? = runCatching {
        convertInternal(call, resolveVariables)
    }.getOrNull()

    // ------------------------------------------------------------------
    // 转换
    // ------------------------------------------------------------------

    private fun convertInternal(call: PsiMethodCallExpression, resolveVariables: Boolean): ExamplePreview? {
        val methodName = call.methodExpression.referenceName ?: return null
        if (methodName !in EXAMPLE_METHODS) return null
        val firstArg = call.argumentList.expressions.firstOrNull() ?: return null
        if (!isExampleLike(firstArg)) return null
        if (!hasRepositoryReceiver(call)) return null

        val entityClass = resolveRepositoryEntity(call) ?: return null
        val table = JpaEntityResolver().resolve(entityClass)
        if (table.entityClassName.isBlank()) return null

        // Example.of(probe[, matcher])：实参直取或变量初始化式回溯
        val ofCall = exampleOfCall(firstArg) ?: return degradeCard(methodName, table, "Example 实参不可静态追踪（跨方法传递/构建器形态）")
        val probeExpr = ofCall.argumentList.expressions.getOrNull(0)
        val matcherExpr = ofCall.argumentList.expressions.getOrNull(1)

        val probe = probeExpr?.let { resolveProbe(it) }
            ?: return degradeCard(methodName, table, "probe 实体字段值运行时确定（静态不可得），WHERE 条件按非空属性生成")

        val matcher = matcherExpr?.let { resolveMatcher(it) } ?: Matcher()
        val ctx = Ctx(resolveVariables)

        // probe 显式赋值字段 → 条件（按实体列顺序渲染，剔除 ignorePaths）
        val conditions = mutableListOf<Condition>()
        for (field in probe.fields) {
            val columnInfo = table.columnByField(field.fieldName)
            val columnName = columnInfo?.columnName ?: cn.song.mybatisplus.visualizer.util.NamingStrategy.toSnakeCase(field.fieldName)
            if (matcher.isIgnored(field.fieldName, columnName)) continue
            val column = ColumnRef(columnName = columnName)
            val stringMatcher = matcher.matcherFor(field.fieldName)
            val likePosition = when (stringMatcher) {
                StringMatcher.CONTAINING -> LikePosition.ANYWHERE
                StringMatcher.STARTING -> LikePosition.RIGHT
                StringMatcher.ENDING -> LikePosition.LEFT
                else -> null
            }
            val value = evaluateValue(
                field.valueExpr,
                ctx,
                ParamQuote.decide(columnInfo?.fieldType),
                likePosition,
                temporal = ParamQuote.isTemporal(columnInfo?.fieldType),
            )
            conditions += buildFieldCondition(column, value, stringMatcher, matcher.ignoreCase)
        }

        val notes = mutableListOf<String>()
        notes += ctx.notes
        if (matcher.matchAny) notes += "ExampleMatcher.matchingAny：任意属性匹配即命中（条件 OR 连接）"
        if (matcher.ignoreCase) notes += "ExampleMatcher.withIgnoreCase：等值条件按 LOWER(col) = LOWER(?) 呈现"
        matcher.includeNulls?.let { notes += "withIncludeNulls/NullHandler：probe 的 null 属性按 IS NULL 参与（静态不可得，运行时生成）" }
        if (probe.fields.isEmpty()) notes += "probe 无静态可得的非空属性：运行时按实际非 null 字段生成等值 WHERE"

        val conditionsFinal: List<Condition> =
            if (matcher.matchAny && conditions.size > 1) listOf(Condition.Group(Connector.OR, conditions)) else conditions

        val columnList = table.columns.joinToString(", ") { it.columnName }.ifEmpty { "*" }
        val selectColumns: List<ColumnRef>
        val head: String
        when (methodName) {
            "count" -> {
                selectColumns = listOf(ColumnRef(columnName = "*", function = "COUNT"))
                head = "SELECT COUNT(*)\nFROM ${table.tableName}"
            }
            "exists" -> {
                selectColumns = listOf(ColumnRef(columnName = "1"))
                head = "SELECT 1\nFROM ${table.tableName}"
                notes += "exists：命中即真语义（运行时渲染 SELECT 1 ... LIMIT 1 形态）"
            }
            else -> {
                selectColumns = emptyList()
                head = "SELECT $columnList\nFROM ${table.tableName}"
                if (methodName == "findOne") notes += "findOne：单结果语义（运行时非唯一结果抛异常），SQL 与 findAll 同形"
            }
        }
        val statement = SelectStatement(table = table, selectColumns = selectColumns, conditions = conditionsFinal)
        val sql = buildString {
            append(head)
            if (conditionsFinal.isNotEmpty()) {
                val rendered = conditionsFinal.joinToString("\n  AND ") { renderCondition(it) }
                append("\nWHERE ").append(rendered)
            }
            for (note in notes.filter { it.isNotBlank() }) append("\n-- ").append(note)
        }
        return ExamplePreview(
            methodName = methodName,
            sql = sql,
            paramSources = ctx.paramSources,
            paramHints = ctx.paramHints,
            tableName = table.tableName,
            columns = table.allColumnNames,
            statement = statement,
        )
    }

    /** 无 Example.of 细节可解析时的降级注释卡（不产生错误 SQL）。 */
    private fun degradeCard(methodName: String, table: TableInfo, reason: String): ExamplePreview = ExamplePreview(
        methodName = methodName,
        sql = "-- Query By Example（$methodName）：$reason\n-- 运行时 SELECT ${table.tableName} WHERE <probe 非空属性等值>",
        tableName = table.tableName,
        columns = table.allColumnNames,
    )

    // ------------------------------------------------------------------
    // 条件构建
    // ------------------------------------------------------------------

    /** 字段条件：匹配器形态 → 等值 / LIKE；ignoreCase → LOWER 包裹（Raw 语义可读形态）。 */
    private fun buildFieldCondition(column: ColumnRef, value: SqlValue, stringMatcher: StringMatcher, ignoreCase: Boolean): Condition {
        val col = column.columnName
        return when (stringMatcher) {
            StringMatcher.EXACT ->
                if (ignoreCase) Condition.Raw("LOWER($col) = LOWER(${renderValue(value)})") else Condition.Simple(column, "=", value)
            StringMatcher.CONTAINING ->
                likeCondition(column, value, LikePosition.ANYWHERE, ignoreCase)
            StringMatcher.STARTING ->
                likeCondition(column, value, LikePosition.RIGHT, ignoreCase)
            StringMatcher.ENDING ->
                likeCondition(column, value, LikePosition.LEFT, ignoreCase)
            StringMatcher.REGEX ->
                Condition.Raw("/* $col REGEX 匹配（${valueText(value)}）：方言相关（RLIKE/REGEXP_LIKE），静态预览降级 */")
        }
    }

    private fun likeCondition(column: ColumnRef, value: SqlValue, position: LikePosition, ignoreCase: Boolean): Condition {
        val pattern = when (value) {
            // Literal：raw 带引号（renderCondition 直用），unquoted 带 %（SqlGenerator 语义一致）
            is SqlValue.Literal -> {
                val core = wrapLike(value.unquoted, position)
                SqlValue.Literal("'$core'", core)
            }
            // 占位值：SQL 呈 `?`，% 按位置由面板回填（ParamHint.likePosition）
            is SqlValue.Placeholder -> value
        }
        val lower = if (ignoreCase) "LOWER(" else ""
        val close = if (ignoreCase) ")" else ""
        if (ignoreCase) {
            return Condition.Raw("$lower${column.columnName}$close LIKE $lower${renderValue(pattern)}$close")
        }
        return Condition.Like(column, false, pattern, position)
    }

    /** LIKE 模式拼接（Literal 值直接拼 %；占位值由面板按 likePosition 回填）。 */
    private fun wrapLike(unquoted: String, position: LikePosition): String = when (position) {
        LikePosition.ANYWHERE -> "%$unquoted%"
        LikePosition.LEFT -> "%$unquoted"
        LikePosition.RIGHT -> "$unquoted%"
        LikePosition.NONE -> unquoted
    }

    private fun renderValue(value: SqlValue): String = when (value) {
        is SqlValue.Literal -> value.raw
        is SqlValue.Placeholder -> "?"
    }

    private fun valueText(value: SqlValue): String = when (value) {
        is SqlValue.Literal -> value.unquoted
        is SqlValue.Placeholder -> "?"
    }

    /** Condition → SQL 文本（与 SqlGenerator 条件渲染同形）。 */
    private fun renderCondition(condition: Condition): String = when (condition) {
        is Condition.Simple -> "${condition.column.qualifiedName} ${condition.op} ${renderValue(condition.value)}"
        is Condition.Like -> "${condition.column.qualifiedName} LIKE ${renderValue(condition.pattern)}"
        is Condition.Raw -> condition.sqlText
        is Condition.Group -> "(${condition.children.joinToString(" ${condition.connector.name} ") { renderCondition(it) }})"
        is Condition.Unary -> "${condition.column.qualifiedName} ${condition.op}"
        is Condition.In -> "${condition.column.qualifiedName} IN (${condition.values.joinToString(", ") { renderValue(it) }})"
        is Condition.Between -> "${condition.column.qualifiedName} BETWEEN ${renderValue(condition.lo)} AND ${renderValue(condition.hi)}"
        is Condition.ColumnComparison -> "${condition.left.qualifiedName} = ${condition.right.qualifiedName}"
    }

    // ------------------------------------------------------------------
    // Example / ExampleMatcher 解析
    // ------------------------------------------------------------------

    /** probe 显式赋值字段（字段名 + 值表达式文本）。 */
    private class ProbeField(val fieldName: String, val valueExpr: PsiExpression)

    /** probe 解析结果。 */
    private class Probe(val fields: List<ProbeField>)

    /** StringMatcher 语义枚举（ExampleMatcher.StringMatcher 的静态可解析子集）。 */
    private enum class StringMatcher { EXACT, CONTAINING, STARTING, ENDING, REGEX }

    /** ExampleMatcher 解析结果（默认 ALL + EXACT + 大小写敏感）。 */
    private class Matcher {
        var matchAny: Boolean = false
        var ignoreCase: Boolean = false
        var includeNulls: String? = null
        val ignorePaths = mutableSetOf<String>()
        val perField = mutableMapOf<String, StringMatcher>()
        var defaultMatcher: StringMatcher = StringMatcher.EXACT

        fun isIgnored(fieldName: String, columnName: String): Boolean =
            fieldName in ignorePaths || columnName in ignorePaths

        fun matcherFor(fieldName: String): StringMatcher = perField[fieldName] ?: defaultMatcher
    }

    /** 解析上下文：参数行收集 + 注释。 */
    private class Ctx(val resolveVariables: Boolean) {
        val paramSources = mutableListOf<String>()
        val paramHints = mutableListOf<ParamHint>()
        val notes = mutableListOf<String>()
    }

    /** 实参 → `Example.of(...)` 调用（直接调用或变量初始化式回溯一次）。 */
    private fun exampleOfCall(arg: PsiExpression): PsiMethodCallExpression? {
        val direct = arg as? PsiMethodCallExpression
        if (direct != null && isExampleOfCall(direct)) return direct
        val initializer = (arg as? PsiReferenceExpression)?.resolve()?.let { it as? PsiVariable }?.initializer
        val viaVar = initializer as? PsiMethodCallExpression
        if (viaVar != null && isExampleOfCall(viaVar)) return viaVar
        // Example probe = ...; Example.of(example) 的再包一层变量形态：实参变量初始化式已是 of 调用
        return null
    }

    private fun isExampleOfCall(call: PsiMethodCallExpression): Boolean =
        call.methodExpression.referenceName == "of" &&
            call.methodExpression.qualifierExpression?.text?.substringAfterLast('.') == "Example"

    /**
     * probe 解析：变量声明 `UserEntity probe = new UserEntity()` + 同作用域内 `probe.setX(v)` /
     * `probe.x = v` 的静态收集（值字面量/变量均可，求值交给 [ExpressionEvaluator]）。
     */
    private fun resolveProbe(expr: PsiExpression): Probe? = runCatching {
        val variable = (expr as? PsiReferenceExpression)?.resolve() as? PsiVariable ?: return@runCatching null
        // probe 必须初始化为 new 实体（builder/工厂形态静态不可得）
        val initializer = variable.initializer as? PsiNewExpression ?: return@runCatching null
        val newClass = (initializer.classReference?.resolve() as? PsiClass)
            ?: ((initializer.type as? PsiClassType)?.resolve())
            ?: return@runCatching null
        val qualifiedName = newClass.qualifiedName
        if (qualifiedName != null && (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax."))) {
            return@runCatching null
        }

        val fields = mutableListOf<ProbeField>()
        // 收集范围：probe 变量所在语句块/方法体（向上找到首个 CodeBlock/Method/Class 边界）
        var scope: PsiElement? = variable
        while (scope != null && scope !is com.intellij.psi.PsiCodeBlock && scope !is com.intellij.psi.PsiMethod &&
            scope !is com.intellij.psi.PsiClass
        ) {
            scope = scope.parent
        }
        val scopeElement = scope ?: return@runCatching null
        val probeName = variable.name ?: return@runCatching null
        collectProbeAssignments(scopeElement, probeName, fields)
        Probe(fields)
    }.getOrNull()

    /** 块内收集 `probe.setX(v)` 与 `probe.x = v`（qualifier 文本精确匹配 probe 变量名）。 */
    private fun collectProbeAssignments(scope: PsiElement, probeName: String, sink: MutableList<ProbeField>) {
        scope.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                // 不深入 probe 所在声明自身之外的限制：无（值表达式嵌套 probe 引用属罕见形态，忽略）
                super.visitElement(element)
                when (element) {
                    is PsiMethodCallExpression -> {
                        val name = element.methodExpression.referenceName ?: return
                        if (!name.startsWith("set") || name.length <= 3) return
                        val qualifier = element.methodExpression.qualifierExpression as? PsiReferenceExpression ?: return
                        if (qualifier.referenceName != probeName) return
                        val arg = element.argumentList.expressions.firstOrNull() ?: return
                        val fieldName = name.removePrefix("set").replaceFirstChar { it.lowercaseChar() }
                        sink += ProbeField(fieldName, arg)
                    }
                    is com.intellij.psi.PsiAssignmentExpression -> {
                        val lhs = element.lExpression as? PsiReferenceExpression ?: return
                        val qualifier = lhs.qualifierExpression as? PsiReferenceExpression ?: return
                        if (qualifier.referenceName != probeName) return
                        val rhs = element.rExpression ?: return
                        sink += ProbeField(lhs.referenceName ?: return, rhs)
                    }
                }
            }
        })
    }

    /**
     * matcher 解析：`ExampleMatcher.matching()/matchingAll()/matchingAny()` 链式
     * withIgnorePaths / withIgnoreCase / withMatcher / withStringMatcher / withIncludeNulls。
     */
    private fun resolveMatcher(expr: PsiExpression): Matcher? = runCatching {
        var call: PsiMethodCallExpression? = expr as? PsiMethodCallExpression
            ?: (expr as? PsiReferenceExpression)?.resolve()?.let { it as? PsiVariable }?.initializer as? PsiMethodCallExpression
            ?: return@runCatching null
        val matcher = Matcher()
        // 从最外层沿 qualifier 下钻（PSI：外层调用 qualifier 是内层调用）
        var guard = 0
        while (call != null && guard++ < 20) {
            applyMatcherCall(call, matcher)
            call = call.methodExpression.qualifierExpression as? PsiMethodCallExpression
        }
        matcher
    }.getOrNull()

    /** 单个 matcher 链方法应用（未知方法忽略——优雅降级）。 */
    private fun applyMatcherCall(call: PsiMethodCallExpression, matcher: Matcher) {
        val name = call.methodExpression.referenceName ?: return
        val args = call.argumentList.expressions
        when (name) {
            "matchingAny" -> matcher.matchAny = true
            "matchingAll", "matching" -> Unit
            "withIgnorePaths" -> {
                for (arg in args) {
                    val path = (arg as? PsiLiteralExpression)?.value as? String
                    if (path != null) matcher.ignorePaths += path
                }
            }
            "withIgnoreCase" -> matcher.ignoreCase = args.firstOrNull()?.text != "false"
            "withIncludeNulls" -> matcher.includeNulls = "includeNulls"
            "withNullHandler" -> matcher.includeNulls =
                (args.firstOrNull() as? PsiReferenceExpression)?.referenceName ?: "include"
            "withStringMatcher" -> {
                val enumName = (args.firstOrNull() as? PsiReferenceExpression)?.referenceName
                matcher.defaultMatcher = mapStringMatcher(enumName) ?: matcher.defaultMatcher
            }
            "withMatcher" -> {
                val fieldName = (args.getOrNull(0) as? PsiLiteralExpression)?.value as? String ?: return
                val matcherExpr = args.getOrNull(1) ?: return
                matcher.perField[fieldName] = parseGenericPropertyMatcher(matcherExpr) ?: matcher.matcherFor(fieldName)
            }
        }
    }

    /** `GenericPropertyMatchers.contains()/startsWith()/endsWith()/exact()/regex()` 链解析。 */
    private fun parseGenericPropertyMatcher(expr: PsiExpression): StringMatcher? {
        var call: PsiMethodCallExpression? = expr as? PsiMethodCallExpression ?: return null
        var guard = 0
        var result: StringMatcher? = null
        while (call != null && guard++ < 10) {
            val name = call.methodExpression.referenceName
            val mapped = when (name) {
                "contains" -> StringMatcher.CONTAINING
                "startsWith" -> StringMatcher.STARTING
                "endsWith" -> StringMatcher.ENDING
                "exact" -> StringMatcher.EXACT
                "regex" -> StringMatcher.REGEX
                "caseInsensitive" -> null // 记录忽略：等值 LOWER 已由 withIgnoreCase 覆盖
                else -> null
            }
            if (mapped != null) result = mapped // 最外层方法即最终形态（链尾语义）
            call = call.methodExpression.qualifierExpression as? PsiMethodCallExpression
        }
        return result
    }

    /** ExampleMatcher.StringMatcher 枚举名映射。 */
    private fun mapStringMatcher(enumName: String?): StringMatcher? = when (enumName) {
        "EXACT" -> StringMatcher.EXACT
        "CONTAINING" -> StringMatcher.CONTAINING
        "STARTING" -> StringMatcher.STARTING
        "ENDING" -> StringMatcher.ENDING
        "REGEX" -> StringMatcher.REGEX
        else -> null
    }

    // ------------------------------------------------------------------
    // 值求值与 receiver/实体解析
    // ------------------------------------------------------------------

    /** probe 字段值求值：字面量/可追踪变量内联；运行时值 `?` 占位 + 参数行（LIKE 条件带 likePosition）。 */
    private fun evaluateValue(
        expr: PsiExpression,
        ctx: Ctx,
        quoteHint: Boolean?,
        likePosition: LikePosition? = null,
        temporal: Boolean = false,
    ): SqlValue {
        val value = if (ctx.resolveVariables) ExpressionEvaluator.evaluate(expr) else SqlValue.Placeholder(expr.text)
        return when (value) {
            is SqlValue.Literal -> value
            is SqlValue.Placeholder -> {
                val source = expr.text.ifBlank { "?" }
                ctx.paramSources += source
                ctx.paramHints += ParamHint(quote = quoteHint, likePosition = likePosition, temporal = temporal)
                SqlValue.Placeholder(source)
            }
        }
    }

    /** 首实参是否 Example 形态（类型 Example / 初始化式 Example.of(...)）。 */
    private fun isExampleLike(arg: PsiExpression): Boolean = runCatching {
        if (arg is PsiLambdaExpression) return@runCatching false
        val direct = arg as? PsiMethodCallExpression
        if (direct != null && isExampleOfCall(direct)) return@runCatching true
        val typeText = (arg.type as? PsiClassType)?.canonicalText.orEmpty()
        if (typeText.substringBefore('<').substringAfterLast('.') == "Example") return@runCatching true
        val variable = (arg as? PsiReferenceExpression)?.resolve() as? PsiVariable
        (variable?.type as? PsiClassType)?.canonicalText?.substringBefore('<')?.substringAfterLast('.') == "Example"
    }.getOrDefault(false)

    /** receiver 类型父链是否含 Spring Data Repository 基类。 */
    private fun hasRepositoryReceiver(call: PsiMethodCallExpression): Boolean {
        val qualifier = call.methodExpression.qualifierExpression ?: return false
        val varType = receiverClassType(qualifier) ?: return false
        val direct = varType.canonicalText?.substringBefore('<') ?: return false
        if (direct in REPOSITORY_BASE_TYPES || direct in REPOSITORY_BASE_SIMPLE_NAMES) return true
        val clazz = varType.resolve() ?: return false
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
        val varType = receiverClassType(qualifier) ?: return null
        val clazz = varType.resolve()
        if (clazz != null) {
            for (superType in clazz.superTypes) {
                val classType = superType as? PsiClassType ?: continue
                val entity = (classType.parameters.firstOrNull() as? PsiClassType)?.resolve()
                if (entity != null) return entity
            }
        }
        return (varType.parameters.firstOrNull() as? PsiClassType)?.resolve()
    }

    private fun receiverClassType(qualifier: PsiExpression): PsiClassType? = when (qualifier) {
        is PsiReferenceExpression ->
            (qualifier.resolve() as? PsiVariable)?.type as? PsiClassType ?: qualifier.type as? PsiClassType
        else -> qualifier.type as? PsiClassType
    }
}
