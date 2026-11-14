package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.NamingStrategy
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiPrefixExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil

/**
 * 实参解析器：把 Wrapper 方法实参 [PsiExpression] 解析为 [WrapperArg]。
 *
 * 处理四种实参：
 *  - 方法引用 `Entity::getXxx` → [WrapperArg.ColumnArg]（列引用，F-13 Lambda 解析）
 *  - 字面量 / 变量 / 表达式 → [WrapperArg.ValueArg]（[ExpressionEvaluator] 求值）
 *  - Lambda `w -> w.eq(...)` → [WrapperArg.NestedArg]（and/or 嵌套，递归解析）
 *  - 其他（new 表达式、集合等）→ [WrapperArg.ValueArg] 占位
 */
object ArgResolver {

    /**
     * 解析单个实参表达式。
     *
     * @param expression     实参（PSI 表达式）
     * @param table          实体表信息（解析 Lambda 列引用用，可为 null）
     * @param entityClass    外层实体类名（嵌套链填充用，可为 null）
     * @param asAlias        true 表示该位置是 MPJ selectAs 的目标 VO 字段（方法引用 → [WrapperArg.AliasArg]，
     *                       保留驼峰属性名作别名，不做 snake_case 列名转换）
     * @param resolveVariables 变量求值开关（M14.6「替换 ? 占位符」配置）：false 时跳过
     *                         [ExpressionEvaluator] 变量追踪，可静态求值的变量也渲染为 `?`；
     *                         字面量（代码直接写的常量）恒内联，不受此开关影响
     * @return WrapperArg 模型
     */
    fun resolve(
        expression: PsiExpression,
        table: TableInfo?,
        entityClass: String?,
        asAlias: Boolean = false,
        resolveVariables: Boolean = true,
    ): WrapperArg {
        // selectAs(Entity::getX, VO::getY)：第二参方法引用 → 目标 VO 属性名（别名）+ 返回类型
        if (asAlias && expression is PsiMethodReferenceExpression) {
            val name = expression.referenceName ?: return WrapperArg.ValueArg(SqlValue.Placeholder(expression.text))
            val property = getterToProperty(name)
            // Extract return type for diagram column type display
            val returnType = (expression.functionalInterfaceType as? PsiClassType)?.parameters?.firstOrNull()?.canonicalText
            return if (property != null) {
                WrapperArg.AliasArg(property, returnType)
            } else {
                WrapperArg.ValueArg(SqlValue.Placeholder(expression.text))
            }
        }
        return when (expression) {
        // Lambda 方法引用：Entity::getXxx → 列引用
        is PsiMethodReferenceExpression ->
            WrapperArg.ColumnArg(resolveLambdaColumn(expression, table))

        // MPJ on(col1, col2)：两列比较实参（F-11）
        is PsiMethodCallExpression -> resolveMethodCallArg(expression, table, resolveVariables)

        // 字面量：直接求值（不受「替换 ? 占位符」开关影响，代码常量恒内联）
        is PsiLiteralExpression ->
            WrapperArg.ValueArg(ExpressionEvaluator.evaluate(expression))

        // Lambda 表达式体：and(w -> w.eq(...)) → 嵌套链
        is PsiLambdaExpression ->
            WrapperArg.NestedArg(resolveNestedLambda(expression, table, entityClass))

        // 变量 / 算术 / new 表达式：求值为字面量或占位符（开关关闭时恒为占位符）
        else ->
            resolveValueArg(expression, resolveVariables)
        }
    }

    /**
     * 非字面量实参求值（M14.6）：resolveVariables=false 时跳过变量追踪，直接渲染 `?`（保留表达式原文作 source）。
     */
    private fun resolveValueArg(expression: PsiExpression, resolveVariables: Boolean): WrapperArg =
        WrapperArg.ValueArg(
            if (resolveVariables) ExpressionEvaluator.evaluate(expression)
            else SqlValue.Placeholder(expression.text),
        )

    /**
     * 解析方法调用实参：MPJ 的 `on(col1, col2)` → [WrapperArg.OnConditionArg]。
     * 其他方法调用（如集合工具调用）→ 占位符（受 resolveVariables 开关控制）。
     */
    private fun resolveMethodCallArg(call: PsiMethodCallExpression, table: TableInfo?, resolveVariables: Boolean): WrapperArg {
        val methodName = call.methodExpression.referenceName
        if (methodName == ON_METHOD) {
            val args = call.argumentList.expressions
            if (args.size >= 2) {
                val left = (args[0] as? PsiMethodReferenceExpression)?.let { resolveLambdaColumn(it, table) }
                val right = (args[1] as? PsiMethodReferenceExpression)?.let { resolveLambdaColumn(it, table) }
                if (left != null && right != null) {
                    return WrapperArg.OnConditionArg(left, right)
                }
            }
        }
        return resolveValueArg(call, resolveVariables)
    }

    /** MPJ on 函数名。 */
    private const val ON_METHOD = "on"

    // ------------------------------------------------------------------
    // Lambda 方法引用 → 列
    // ------------------------------------------------------------------

    /**
     * 解析 `Entity::getXxx` / `Entity::isXxx` 方法引用为列引用。
     *
     * getter 名 → 属性名 → 查实体字段映射；无实体信息时按命名策略兜底推断。
     */
    private fun resolveLambdaColumn(methodRef: PsiMethodReferenceExpression, table: TableInfo?): ColumnRef {
        val methodName = methodRef.referenceName ?: return ColumnRef(columnName = methodRef.text)
        val propertyName = getterToProperty(methodName) ?: return ColumnRef(columnName = methodName)

        // 优先从实体字段映射取列名
        val mapped = table?.columnByField(propertyName)?.columnName
        val columnName = mapped ?: NamingStrategy.toSnakeCase(propertyName)

        // 提取方法引用接收者实体类名（用于 selectAs 图表列归属推断）
        val entityClass = (methodRef.qualifiedName?.substringBefore("::") ?: methodRef.firstChild?.text)

        return ColumnRef(columnName = columnName, entityClass = entityClass)
    }

    /**
     * getter 方法名转属性名：`getStationId` → `shareSchemeId`，`isEnabled` → `enabled`。
     * 非 getter/is 前缀返回 null。
     */
    private fun getterToProperty(methodName: String): String? {
        val prefix = when {
            methodName.startsWith("get") && methodName.length > 3 -> "get"
            methodName.startsWith("is") && methodName.length > 2 -> "is"
            else -> return null
        }
        val name = methodName.removePrefix(prefix)
        // 首字母小写
        return name.replaceFirstChar { it.lowercaseChar() }
    }

    // ------------------------------------------------------------------
    // Lambda 体 → 嵌套调用链
    // ------------------------------------------------------------------

    /**
     * 解析 `and(w -> w.eq(...).like(...))` 的 Lambda 体为嵌套调用链。
     *
     * 支持两种体：
     *  - 表达式体（方法调用链）：直接收集链
     *  - 代码块体：收集块内全部方法调用（按源码顺序）
     */
    private fun resolveNestedLambda(lambda: PsiLambdaExpression, table: TableInfo?, entityClass: String?): WrapperChain {
        val body = lambda.body ?: return WrapperChain(WrapperType.NESTED, entityClass, emptyList())
        val calls = mutableListOf<PsiMethodCallExpression>()

        when (body) {
            // 表达式体：w.eq(...).like(...)
            is PsiMethodCallExpression -> calls += collectCallChain(body)

            // 代码块体：只收「属于本 lambda 的顶层语句链」——
            // ① 跳过嵌套 lambda 体内的调用（`o.or(x -> x.eq(B))` 的 x.eq(B) 已由递归
            //    resolveNestedLambda 进入 or 的嵌套链，再混入本层即渲染重复条件，
            //    T-HiddenBugPerfScan 券 A；与 collectChainFromInitializer 同一防线，
            //    判据是「最近外围 lambda 就是本 lambda」而非任意 lambda）；
            // ② 只取链尾（parent 不是另一调用的 qualifier 引用）回溯整链——
            //    `o.eq(A).like(B)` 的 eq 是链中段，整链由 collectCallChain 补齐，不重复计。
            else -> PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
                .filter { call ->
                    val owner = PsiTreeUtil.getParentOfType(call, PsiLambdaExpression::class.java)
                    (owner == null || owner == lambda) &&
                        !(call.parent is PsiReferenceExpression && call.parent.parent is PsiMethodCallExpression)
                }
                .sortedBy { it.textOffset }
                .forEach { tail -> calls += collectCallChain(tail) }
        }

        val wrapperCalls = calls
            .distinctBy { it.textRange.endOffset }
            .map { toWrapperCall(it, table, entityClass) }
        return WrapperChain(WrapperType.NESTED, entityClass, wrapperCalls)
    }

    // ------------------------------------------------------------------
    // 调用链收集
    // ------------------------------------------------------------------

    /**
     * 从链尾调用沿 qualifier 回溯收集整条链（含自身）。
     *
     * 如 `new W().eq(a).like(b)` 传入 like 调用 → [eq, like]。
     */
    internal fun collectCallChain(call: PsiMethodCallExpression): List<PsiMethodCallExpression> {
        val chain = mutableListOf<PsiMethodCallExpression>()
        var current: PsiMethodCallExpression? = call
        while (current != null) {
            chain.add(0, current)
            current = current.methodExpression.qualifierExpression as? PsiMethodCallExpression
        }
        return chain
    }

    /**
     * 收集初始化表达式中的链式调用（new LambdaQueryWrapper<>().eq(...) 部分）。
     *
     * 找出所有"不是其他调用 qualifier"的链尾调用，各自回溯整链。
     * 注意：跳过 lambda 体内的调用（如 `and(x -> x.eq(...))` 中的 x.eq），
     * 它们由 [resolveNestedLambda] 作为嵌套链解析，不应混入外层链。
     */
    internal fun collectChainFromInitializer(initializer: PsiElement): List<PsiMethodCallExpression> {
        val result = mutableListOf<PsiMethodCallExpression>()
        PsiTreeUtil.collectElementsOfType(initializer, PsiMethodCallExpression::class.java)
            .filter { call ->
                // 跳过 lambda 体内的调用
                if (PsiTreeUtil.getParentOfType(call, PsiLambdaExpression::class.java) != null) {
                    return@filter false
                }
                // 链尾：其父不是另一调用的 qualifier 引用
                val parent = call.parent
                !(parent is PsiReferenceExpression && parent.parent is PsiMethodCallExpression)
            }
            .forEach { chainTail -> result += collectCallChain(chainTail) }
        return result
    }

    /**
     * 把 PsiMethodCallExpression 转为模型 [WrapperCall]。
     *
     * MPJ 特殊参数位置：
     *  - `selectAs(Entity::getX, VO::getY)` / `selectCount(...)` 第二参按「目标 VO 字段」解析（[WrapperArg.AliasArg]）
     *  - `selectFunc(模板, func -> func.accept(列).values(值), 别名)` 第二参按「聚合函数」解析（[WrapperArg.FuncArg]）
     * 其余参数按常规实参解析。
     */
    internal fun toWrapperCall(
        call: PsiMethodCallExpression,
        table: TableInfo?,
        entityClass: String?,
        resolveVariables: Boolean = true,
    ): WrapperCall {
        val methodName = call.methodExpression.referenceName ?: call.methodExpression.text
        val expressions = call.argumentList.expressions
        // T-M21-04：allEq 重载的 null2IsNull 尾参（`allEq(Map, null2IsNull)` / `allEq(boolean, Map, null2IsNull)`
        // 末位布尔字面量）；运行时布尔（变量）不可静态判定 → 恒 false（仅字面量 true 生效）
        val allEqNull2IsNull = methodName == "allEq" &&
            (expressions.lastOrNull() as? PsiLiteralExpression)?.value == true
        val args = expressions.mapIndexed { index, expr ->
            when {
                // T-M21-04：allEq 的 Map 实参——Map.of / Map.ofEntries 调用静态展开键值对；
                // 非布尔/非 lambda 实参（Map 变量、new HashMap 等）→ 空条目 MapArg（converter 渲染占位）
                methodName == "allEq" && expr !is PsiLambdaExpression && !isBooleanLiteral(expr) ->
                    resolveMapArg(expr, allEqNull2IsNull, resolveVariables)
                // T-M21-01：selectFilter(Class, Predicate) 第一参实体类引用、第二参谓词 lambda
                methodName == SELECT_FILTER && index == 0 ->
                    resolveJoinClass(expr, resolveVariables)
                methodName == SELECT_FILTER && index == 1 ->
                    resolveSelectPredicate(expr)
                methodName in JOIN_METHODS && index == 0 ->
                    resolveJoinClass(expr, resolveVariables)
                methodName == SELECT_AS_CLASS ->
                    resolveJoinClass(expr, resolveVariables)
                // union/unionAll/selectSub 的第一参为子查询实体类引用（官方 API，M15 增补）
                (methodName in UNION_METHODS || methodName == SELECT_SUB) && index == 0 ->
                    resolveJoinClass(expr, resolveVariables)
                // 子查询条件（官方 sub-query API）：eq(col, SubEntity.class, w -> ...) 的第二参为子表类引用
                methodName in SUBQUERY_CONDITION_METHODS && index == 1 &&
                    expressions.size >= 3 &&
                    expressions.getOrNull(2) is PsiLambdaExpression ->
                    resolveJoinClass(expr, resolveVariables)
                // T-1800：selectCollection / selectAssociation 的类参解析为 EntityRefArg（携带 @TableName 注解）。
                // prefix 形态（第 2 参为类名）> 普通形态（第 1 参为类名）> free 形态（无类参，不产生子查询）。
                (methodName == SELECT_COLLECTION || methodName == SELECT_ASSOCIATION) &&
                    isCollectionClassArg(expr, expressions, index) ->
                    resolveJoinClass(expr, resolveVariables)
                // T-1800：selectCollection / selectAssociation 的 VO getter（prefix 形态第 3 参 / 普通形态第 2 参）
                // 解析为 AliasArg（保留驼峰属性名）。
                (methodName == SELECT_COLLECTION || methodName == SELECT_ASSOCIATION) &&
                    isCollectionVoGetter(expressions, index) ->
                    resolve(expr, table, entityClass, asAlias = true, resolveVariables = resolveVariables)
                // selectSub 的第三参为结果别名（方法引用 → AliasArg 驼峰属性名）
                methodName == SELECT_SUB && index == 2 ->
                    resolve(expr, table, entityClass, asAlias = true, resolveVariables = resolveVariables)
                methodName in ALIAS_SECOND_ARG_METHODS && index == 1 ->
                    resolve(expr, table, entityClass, asAlias = true, resolveVariables = resolveVariables)
                // T-HiddenBugPerfScan 券 L：&& 优先于 ||——原式实为「selectFunc 任意位 ∪
                // apply 第 2 位」。按 KDoc（第二参聚合函数）与姊妹分支 SELECT_SUB && index == 2
                // 对齐：第 2 位走 resolveFuncLambda；selectFunc 第 3 位（别名位）走 asAlias
                // （方法引用 → AliasArg）——原式把别名位也喂给 resolveFuncLambda，恒产 FuncArg，
                // 解析端（QueryWrapperParser.parseSelectFunc）的别名位只认 AliasArg/ValueArg
                // → PSI 链路上别名恒丢失；别名位直落 else 的 resolve 对方法引用产 ColumnArg，
                // 同样进不了别名分支，故须显式 asAlias。
                (methodName == SELECT_FUNC || methodName == APPLY_FUNC) && index == 1 ->
                    resolveFuncLambda(expr, table)
                methodName == SELECT_FUNC && index == 2 ->
                    resolve(expr, table, entityClass, asAlias = true, resolveVariables = resolveVariables)
                else -> resolve(expr, table, entityClass, resolveVariables = resolveVariables)
            }
        }
        return WrapperCall(methodName, args)
    }

    /** T-M21-04：布尔字面量实参（allEq 的条件标志 / null2IsNull 尾参，不做 Map 解析）。 */
    private fun isBooleanLiteral(expression: PsiExpression): Boolean =
        (expression as? PsiLiteralExpression)?.value is Boolean

    /**
     * T-1800：判断 selectCollection / selectAssociation 的当前实参是否为类参（需解析为 EntityRefArg）。
     *
     * 形态判定：
     *  - prefix 形态：第 2 参（index=1）为类参（第 1 参为字符串 prefix）
     *  - 普通形态：第 1 参（index=0）为类参
     *  - free 形态：无类参（首参为 VO 方法引用），返回 false
     *
     * 判定规则：当前实参是 PsiClassObjectAccessExpression（XxxEntity.class）即视为类参。
     */
    private fun isCollectionClassArg(expr: PsiExpression, allArgs: Array<PsiExpression>, index: Int): Boolean {
        if (expr !is PsiClassObjectAccessExpression) return false
        // prefix 形态：第 2 参（index=1）且第 1 参为字符串字面量
        if (index == 1 && allArgs.getOrNull(0) is PsiLiteralExpression) return true
        // 普通形态：第 1 参（index=0）
        if (index == 0) return true
        return false
    }

    /**
     * T-1800：判断 selectCollection / selectAssociation 的当前实参是否为 VO getter（解析为 AliasArg）。
     *
     * 形态判定：
     *  - prefix 形态：第 3 参（index=2）为 VO getter
     *  - 普通形态：第 2 参（index=1）为 VO getter
     */
    private fun isCollectionVoGetter(allArgs: Array<PsiExpression>, index: Int): Boolean {
        // prefix 形态：第 1 参为字符串字面量 → VO getter 在第 3 参
        if (allArgs.getOrNull(0) is PsiLiteralExpression) return index == 2
        // 普通形态：VO getter 在第 2 参
        return index == 1
    }

    /** MPJ join 方法名集合（第一参为关联实体类引用；join 为自定义关键字形态，第一参为关键字字符串）。 */
    private val JOIN_METHODS = setOf("leftJoin", "innerJoin", "rightJoin", "fullJoin", "join")

    /**
     * 解析 join 的关联实体引用：`leftJoin(XxxEntity.class, ...)` → [WrapperArg.EntityRefArg]（F-13 关联实体注解解析）。
     * 无法解析为实体类时降级为类名字面量（converter 按命名策略兜底）。
     */
    private fun resolveJoinClass(expr: PsiExpression, resolveVariables: Boolean): WrapperArg {
        val classAccess = expr as? PsiClassObjectAccessExpression
        val psiClass = (classAccess?.operand?.type as? PsiClassType)?.resolve()
        return if (psiClass != null) {
            WrapperArg.EntityRefArg(EntityResolver().resolve(psiClass))
        } else {
            resolveValueArg(expr, resolveVariables)
        }
    }

    /** T-1800：selectCollection / selectAssociation 方法名（类参需解析为 EntityRefArg）。 */
    private const val SELECT_COLLECTION = "selectCollection"
    private const val SELECT_ASSOCIATION = "selectAssociation"

    /** MPJ selectAs 方法名（第二参为别名位置）。 */
    private const val SELECT_AS = "selectAs"

    /** MPJ selectFunc / applyFunc 方法名（第二参为 func -> func.accept(...).values(...) lambda）。 */
    private const val SELECT_FUNC = "selectFunc"
    private const val APPLY_FUNC = "applyFunc"

    /** MPJ selectFilter 方法名（T-M21-01：第一参实体类引用、第二参谓词 lambda）。 */
    private const val SELECT_FILTER = "selectFilter"

    /** MPJ selectAsClass 方法名（两个实参均为实体类引用，converter 按字段交集映射）。 */
    private const val SELECT_AS_CLASS = "selectAsClass"

    /** 支持子查询条件的比较方法（eq(col, Sub.class, wrapper) 等官方 sub-query API）。 */
    private val SUBQUERY_CONDITION_METHODS = setOf("eq", "ne", "gt", "ge", "lt", "le", "in", "notIn")

    /** UNION 方法（第一参为子查询实体类引用）。 */
    private val UNION_METHODS = setOf("union", "unionAll")

    /** selectSub 方法（子查询列，第一参为子查询实体类引用）。 */
    private const val SELECT_SUB = "selectSub"

    /** MPJ 中第二参为「目标 VO 字段/别名」的方法名集合（selectAs、selectCount、selectSum/Min/Max/Avg/Len）。 */
    private val ALIAS_SECOND_ARG_METHODS = setOf(
        "selectAs", "selectCount", "selectSum", "selectMin", "selectMax", "selectAvg", "selectLen",
    )

    /**
     * T-M21-04：解析 allEq 的 Map 实参为键值对列表。
     *
     *  - `Map.of(k1, v1, k2, v2, ...)`：实参按两两一组解析；
     *  - `Map.ofEntries(Map.entry(k, v), ...)`：每个实参为 entry 调用，取其前两参；
     * 键为字符串字面量 → [WrapperArg.MapArg.entries]（[ColumnRef]，columnName = 键）；
     * 值为字面量/可追踪变量 → [SqlValue]（其余占位符）；非 Map.of 的 Map 变量 → 空条目（converter 渲染占位）。
     */
    private fun resolveMapArg(expression: PsiExpression, null2IsNull: Boolean, resolveVariables: Boolean): WrapperArg {
        val call = expression as? PsiMethodCallExpression ?: return WrapperArg.MapArg(emptyList(), null2IsNull)
        val methodName = call.methodExpression.referenceName
        val rawArgs = call.argumentList.expressions
        val pairs: List<Pair<PsiExpression, PsiExpression>> = if (methodName == "ofEntries") {
            // Map.ofEntries(Map.entry(k, v), ...)：每个实参为 entry 调用，取其前两参
            rawArgs.mapNotNull { entry ->
                val entryCall = entry as? PsiMethodCallExpression ?: return@mapNotNull null
                val entryArgs = entryCall.argumentList.expressions
                if (entryArgs.size < 2) null else entryArgs[0] to entryArgs[1]
            }
        } else {
            // Map.of(k1, v1, k2, v2, ...)：实参按两两一组解析
            val result = mutableListOf<Pair<PsiExpression, PsiExpression>>()
            var index = 0
            while (index + 1 < rawArgs.size) {
                result += rawArgs[index] to rawArgs[index + 1]
                index += 2
            }
            result
        }
        val entries = pairs.mapNotNull { (keyExpr, valueExpr) ->
            val key = (keyExpr as? PsiLiteralExpression)?.value as? String ?: return@mapNotNull null
            val value = if (resolveVariables) ExpressionEvaluator.evaluate(valueExpr)
            else SqlValue.Placeholder(valueExpr.text)
            ColumnRef(columnName = key) to value
        }
        return WrapperArg.MapArg(entries, null2IsNull)
    }

    /**
     * T-M21-01：解析 selectFilter 的谓词 lambda（`t -> !t.getProperty().equals("xxx")` 黑名单 /
     * `t -> t.getColumn().equals("col_name")` 白名单）为静态白/黑名单。
     *
     * 收集 lambda 体内方法调用实参中的字符串字面量作为候选字段名；
     * 体内含 `!` 前缀表达式（negated equals）→ 黑名单 [WrapperArg.PredicateArg.exclude]，否则白名单 [include]。
     * 无法静态分析（无字符串字面量）→ 双空列表（converter 回退全列）。
     */
    private fun resolveSelectPredicate(expression: PsiExpression): WrapperArg {
        val lambda = expression as? PsiLambdaExpression ?: return WrapperArg.PredicateArg(emptyList(), emptyList())
        val body = lambda.body ?: return WrapperArg.PredicateArg(emptyList(), emptyList())
        val names = PsiTreeUtil.collectElementsOfType(body, PsiLiteralExpression::class.java)
            .filter { it.parent is PsiExpressionList } // 仅方法调用实参（排除其他位置的字符串）
            .mapNotNull { it.value as? String }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val hasNegation = PsiTreeUtil.collectElementsOfType(body, PsiPrefixExpression::class.java)
            .any { it.operationSign.text == "!" }
        return if (hasNegation) {
            WrapperArg.PredicateArg(include = emptyList(), exclude = names)
        } else {
            WrapperArg.PredicateArg(include = names, exclude = emptyList())
        }
    }

    /**
     * 解析 selectFunc 的聚合 lambda：`func -> func.accept(列1, 列2).values(值1, 值2)`。
     *
     * 收集 accept 的列引用与 values 的绑定参数值（清单 P09）。
     * T-M21-06：枚举形态 `selectFunc(DefaultFuncEnum.SUM, Entity::getX, ...)` 的第二参为列方法引用
     * → 单列 FuncArg（无绑定参数），与 lambda 形态并存。
     * 实参不是 lambda / 方法引用（异常代码）时降级为占位值。
     */
    private fun resolveFuncLambda(expression: PsiExpression, table: TableInfo?): WrapperArg {
        if (expression is PsiMethodReferenceExpression) {
            return WrapperArg.FuncArg(listOf(resolveLambdaColumn(expression, table)), emptyList())
        }
        val lambda = expression as? PsiLambdaExpression
            ?: return WrapperArg.ValueArg(ExpressionEvaluator.evaluate(expression))
        val body = lambda.body
        val calls = if (body is PsiMethodCallExpression) collectCallChain(body) else emptyList()
        var columns = emptyList<ColumnRef>()
        var values = emptyList<SqlValue>()
        for (call in calls) {
            when (call.methodExpression.referenceName) {
                "accept" -> columns = call.argumentList.expressions.mapNotNull { expr ->
                    (expr as? PsiMethodReferenceExpression)?.let { resolveLambdaColumn(it, table) }
                }
                "values" -> values = call.argumentList.expressions.map { ExpressionEvaluator.evaluate(it) }
            }
        }
        return WrapperArg.FuncArg(columns, values)
    }
}
