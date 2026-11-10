package cn.song.mybatisplus.visualizer.xml

import java.math.BigDecimal
import java.math.BigInteger

/**
 * 受限 OGNL 子集求值器（T-M24-02）：MyBatis XML 动态 SQL 的 test / bind / `${}` 表达式求值。
 *
 * 覆盖 MyBatis 常见表达式形态：
 *  - 字面量：数字 / 字符串（单双引号，转义）/ 布尔 / null / 列表 `{a, b}`
 *  - 引用链：`a.b.c`、`list[0]`、`map['key']`、`_parameter.getTitle()`
 *  - 运算符：`and or not !`、`&& ||`、`== != < > <= >=`、`+ - * / %`、`in`、`not in`
 *  - 方法白名单：size / length / isEmpty / contains / startsWith / endsWith / containsKey / toString，
 *    以及 getter 形态 `getX()` / `isX()`（OGNL 属性访问语义）
 *  - 属性访问：`MockObject`（模拟参数对象）/ Map / List / String 的 size 属性等
 *
 * 不支持完整 OGNL（静态方法、构造器、赋值、lambda 等）——解析失败返回 [ParseResult.Err]，
 * 由预览面板提供手动开关兜底（见 XmlPreviewModel）。
 *
 * 判真规则对齐 MyBatis ExpressionEvaluator：Boolean → 原值；Number → 非零为真；
 * 其他 → 非 null 为真（**空串为真、0 为假**）。
 */
data class MockObject(val properties: Map<String, Any?> = emptyMap()) {
    operator fun get(name: String): Any? = properties[name]
}

/** 求值器的模拟可迭代值（foreach 展开 N 次，元素为无属性 MockObject）。 */
class MockIterable(val size: Int) : Iterable<MockObject> {
    override fun iterator(): Iterator<MockObject> = (0 until size).asIterable().map { MockObject() }.iterator()
}

/** 表达式 AST。 */
sealed interface OgnlExpr {
    data class Literal(val value: Any?) : OgnlExpr

    data class ListLiteral(val items: List<OgnlExpr>) : OgnlExpr

    /** 根变量引用（从 bindings 取值的起始标识符）。 */
    data class RootRef(val name: String) : OgnlExpr

    /** 静态字段引用（T-M25.2）：`@com.xxx.Class@FIELD`（MyBatis mapper 常见 OGNL 常量写法）。 */
    data class StaticFieldRef(val className: String, val fieldName: String) : OgnlExpr

    /** 引用链：基础表达式（[RootRef] 或字面量，如 `'str'.length()`）+ 后缀步骤。 */
    data class Chain(val base: OgnlExpr, val steps: List<ChainStep>) : OgnlExpr

    data class Unary(val op: String, val operand: OgnlExpr) : OgnlExpr

    data class Binary(val op: String, val left: OgnlExpr, val right: OgnlExpr) : OgnlExpr
}

sealed interface ChainStep {
    data class Property(val name: String) : ChainStep

    data class Method(val name: String, val args: List<OgnlExpr>) : ChainStep

    data class Index(val expr: OgnlExpr) : ChainStep
}

sealed interface ParseResult {
    data class Ok(val expr: OgnlExpr) : ParseResult

    data class Err(val message: String) : ParseResult
}

sealed interface EvalResult {
    data class Ok(val value: Any?) : EvalResult

    data class Err(val message: String) : EvalResult
}

/**
 * 表达式支持面分档（T-ExprAstPrecheck T-EP-01）：把「求值失败/静默降级」提前成声明性判定。
 *
 * 支持面清单不另造语义，直接取自本文件既有事实：解析器能产出的 AST 节点与运算符、
 * [OgnlEvaluator.KNOWN_METHODS] 方法白名单、[OgnlEvaluator] 的属性/getter 访问语义。
 */
enum class ExprSupport {
    /** 语法与语义都在受限子集内（含属性链、下标、getter 形态、载体无关的白名单方法）。 */
    SUPPORTED,

    /** 语法即超出受限子集（三元 / 赋值 / lambda / 静态方法 / new 等），解析返回 [ParseResult.Err]。 */
    UNSUPPORTED_SYNTAX,

    /** 语法可解析，但含求值期降级形态（未知方法链 / 载体受限的白名单方法 / 静态字段引用）——求值会返回 null 而不报错。 */
    EVAL_FALLBACK,
}

/** 分档理由码（T-EP-03 条件行标注 tooltip 用；UI 侧映射 bundle 文案，xml 层不持有文案）。 */
enum class ExprReason {
    /** 解析失败（[ExprSupport.UNSUPPORTED_SYNTAX] 的唯一理由）。 */
    SYNTAX,

    /** 未知方法调用：不在白名单内且非 getter 形态，求值静默返回 null。 */
    UNKNOWN_METHOD,

    /** 白名单方法在接收者载体上无实现（如 `length()` 只对字符串有实现），求值静默返回 null。 */
    NARROW_CARRIER,

    /** 静态字段引用：取值由 PSI 层注入，xml 层无法判定可用性，未注入时求值为 null。 */
    STATIC_FIELD,
}

/**
 * 分类结论：档位 + 理由码 + 细节（方法名 / `@类@字段` / 解析错误消息）。
 * [ExprSupport.SUPPORTED] 时 [reason] 与 [detail] 均为 null。
 */
data class ExprClassification(
    val support: ExprSupport,
    val reason: ExprReason? = null,
    val detail: String? = null,
)

object OgnlEvaluator {

    /**
     * T-Perf-04：表达式解析缓存——同一表达式（test/bind/`${}` 在每次 renderSql 重求值）
     * 只 parse 一次。解析是表达式的纯函数（与 bindings 无关），缓存安全；
     * 键为表达式原文，超上限整体清空（表达式多样性有限，简单封顶防膨胀）。
     */
    private val parseCache = java.util.concurrent.ConcurrentHashMap<String, ParseResult>()

    /** 解析表达式（T-M25.2：`||X||` 剥壳兼容——社区非标准写法，真实 OGNL 会报 Malformed；预览宽容按 X 解析）。 */
    fun parse(text: String): ParseResult {
        val cached = parseCache[text]
        if (cached != null) return cached
        val fresh = parseUncached(text)
        if (parseCache.size >= PARSE_CACHE_MAX) parseCache.clear()
        parseCache[text] = fresh
        return fresh
    }

    private fun parseUncached(text: String): ParseResult = try {
        Parser(Lexer(unwrapOrBars(text))).parseExpression()
    } catch (e: OgnlParseException) {
        ParseResult.Err(e.message ?: "parse error")
    }

    /** `||` 包裹剥壳：`||X||` → `X`（递归，`||||X||||` 亦支持）；非包裹原样返回。 */
    private fun unwrapOrBars(text: String): String {
        var result = text
        while (true) {
            val trimmed = result.trim()
            if (trimmed.length > 4 && trimmed.startsWith("||") && trimmed.endsWith("||")) {
                result = trimmed.substring(2, trimmed.length - 2)
            } else {
                return result
            }
        }
    }

    /** 解析 + 求值（bindings 为变量上下文；staticFieldValues 为 `@class@field` → 值的映射，T-M25.2）。求值期异常安全降级为 [EvalResult.Err]。 */
    fun evaluate(text: String, bindings: Map<String, Any?>, staticFieldValues: Map<String, Any?> = emptyMap()): EvalResult =
        when (val p = parse(text)) {
            is ParseResult.Err -> EvalResult.Err(p.message)
            is ParseResult.Ok -> evaluate(p.expr, bindings, staticFieldValues)
        }

    /** 求值（AST）。 */
    fun evaluate(expr: OgnlExpr, bindings: Map<String, Any?>, staticFieldValues: Map<String, Any?> = emptyMap()): EvalResult =
        try {
            EvalResult.Ok(eval(expr, bindings, staticFieldValues))
        } catch (e: Throwable) {
            EvalResult.Err(e.message ?: "evaluation error")
        }

    /**
     * 判真规则（对齐 MyBatis ExpressionEvaluator.evaluateBoolean——**if test 判定用**）：
     * Boolean → 原值；Number → compareTo(0) != 0；其他 → != null（空串为真、0 为假）。
     */
    fun isTrue(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> runCatching { toDecimal(value).compareTo(BigDecimal.ZERO) != 0 }.getOrDefault(true)
        else -> value != null
    }

    /**
     * OGNL 操作数判真（对齐 OgnlOps.booleanValue——`||`/`&&`/`!` 等 OGNL 操作符用）：
     * 与 [isTrue] 的关键差异——**空串为 false**、集合/数组/Map 按 size 判真。
     * （MyBatis 的 test 表达式走 ExpressionEvaluator 空串为真；OGNL 操作符走本规则。）
     */
    fun ognlTruthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> runCatching { toDecimal(value).compareTo(BigDecimal.ZERO) != 0 }.getOrDefault(true)
        is Char -> value != '\u0000'
        is CharSequence -> value.length > 0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        is Array<*> -> value.isNotEmpty()
        else -> true
    }

    /** 提取表达式中的静态字段引用（`class@field` 集合，T-M25.2 求值为空提示用）；解析失败返回空集。 */
    fun staticFieldRefs(text: String): Set<String> {
        val result = parse(text)
        if (result !is ParseResult.Ok) return emptySet()
        val out = mutableSetOf<String>()
        collectStaticRefs(result.expr, out)
        return out
    }

    /** 只识别无法静态读取的对象属性载体，不反射执行用户 getter。 */
    fun unsupportedPropertyPaths(text: String, bindings: Map<String, Any?>): Set<String> {
        val parsed = parse(text) as? ParseResult.Ok ?: return emptySet()
        val paths = linkedSetOf<String>()
        fun walk(expr: OgnlExpr) {
            when (expr) {
                is OgnlExpr.Chain -> {
                    walk(expr.base)
                    var value: Any? = (expr.base as? OgnlExpr.RootRef)?.let { bindings[it.name] }
                    var path = (expr.base as? OgnlExpr.RootRef)?.name.orEmpty()
                    for (step in expr.steps) {
                        when (step) {
                            is ChainStep.Property -> {
                                path = if (path.isEmpty()) step.name else "$path.${step.name}"
                                if (value != null && value !is MockObject && value !is Map<*, *> &&
                                    value !is Collection<*> && value !is Array<*> && value !is CharSequence) {
                                    paths += path
                                }
                                value = property(value, step.name)
                            }
                            is ChainStep.Method -> {
                                step.args.forEach(::walk)
                                value = null
                            }
                            is ChainStep.Index -> {
                                walk(step.expr)
                                value = null
                            }
                        }
                    }
                }
                is OgnlExpr.Unary -> walk(expr.operand)
                is OgnlExpr.Binary -> {
                    walk(expr.left)
                    walk(expr.right)
                }
                is OgnlExpr.ListLiteral -> expr.items.forEach(::walk)
                is OgnlExpr.Literal, is OgnlExpr.RootRef, is OgnlExpr.StaticFieldRef -> Unit
            }
        }
        walk(parsed.expr)
        return paths
    }

    private fun collectStaticRefs(expr: OgnlExpr, out: MutableSet<String>) {
        when (expr) {
            is OgnlExpr.StaticFieldRef -> out += "${expr.className}@${expr.fieldName}"
            is OgnlExpr.Chain -> {
                collectStaticRefs(expr.base, out)
                expr.steps.forEach { step ->
                    when (step) {
                        is ChainStep.Property -> Unit
                        is ChainStep.Method -> step.args.forEach { collectStaticRefs(it, out) }
                        is ChainStep.Index -> collectStaticRefs(step.expr, out)
                    }
                }
            }
            is OgnlExpr.Unary -> collectStaticRefs(expr.operand, out)
            is OgnlExpr.Binary -> {
                collectStaticRefs(expr.left, out)
                collectStaticRefs(expr.right, out)
            }
            else -> Unit
        }
    }

    /** 提取表达式中引用的根变量（变量收集用）；解析失败返回空集。 */
    fun rootVariables(text: String): Set<String> {
        val result = parse(text)
        if (result !is ParseResult.Ok) return emptySet()
        val out = mutableSetOf<String>()
        collectRoots(result.expr, out)
        return out
    }

    /**
     * 提取根路径：`_parameter.title`（_parameter 的直接属性，供模拟参数对象属性收集）、
     * `list` 等普通根变量；`_parameter.getTitle()` 按 getter 推断为 `_parameter.title`。
     */
    fun rootPaths(text: String): Set<String> {
        val result = parse(text)
        if (result !is ParseResult.Ok) return emptySet()
        val out = mutableSetOf<String>()
        collectPaths(result.expr, out)
        return out
    }

    /** Chain 首步的属性名（Property 直取；getX()/isX() 方法推断）。 */
    private fun firstPropName(step: ChainStep): String? = when (step) {
        is ChainStep.Property -> step.name
        is ChainStep.Method -> when {
            step.name.length > 3 && step.name.startsWith("get") && step.name[3].isUpperCase() ->
                step.name.substring(3).replaceFirstChar { it.lowercaseChar() }
            step.name.length > 2 && step.name.startsWith("is") && step.name[2].isUpperCase() ->
                step.name.substring(2).replaceFirstChar { it.lowercaseChar() }
            else -> null
        }
        is ChainStep.Index -> null
    }

    private fun collectPaths(expr: OgnlExpr, out: MutableSet<String>) {
        when (expr) {
            is OgnlExpr.Literal, is OgnlExpr.ListLiteral, is OgnlExpr.StaticFieldRef -> Unit
            is OgnlExpr.RootRef -> out += expr.name
            is OgnlExpr.Chain -> {
                val base = expr.base
                if (base is OgnlExpr.RootRef) {
                    val firstProp = expr.steps.firstOrNull()?.let { firstPropName(it) }
                    if (base.name == DynamicContext.PARAMETER_OBJECT_KEY && firstProp != null) {
                        out += "${DynamicContext.PARAMETER_OBJECT_KEY}.$firstProp"
                    } else {
                        out += base.name
                    }
                } else {
                    collectPaths(base, out)
                }
                expr.steps.forEach { step ->
                    when (step) {
                        is ChainStep.Property -> Unit
                        is ChainStep.Method -> step.args.forEach { collectPaths(it, out) }
                        is ChainStep.Index -> collectPaths(step.expr, out)
                    }
                }
            }
            is OgnlExpr.Unary -> collectPaths(expr.operand, out)
            is OgnlExpr.Binary -> {
                collectPaths(expr.left, out)
                collectPaths(expr.right, out)
            }
        }
    }

    private fun collectRoots(expr: OgnlExpr, out: MutableSet<String>) {
        when (expr) {
            is OgnlExpr.Literal, is OgnlExpr.ListLiteral, is OgnlExpr.StaticFieldRef -> Unit
            is OgnlExpr.RootRef -> out += expr.name
            is OgnlExpr.Chain -> {
                collectRoots(expr.base, out)
                expr.steps.forEach { step ->
                    when (step) {
                        is ChainStep.Property -> Unit
                        is ChainStep.Method -> step.args.forEach { collectRoots(it, out) }
                        is ChainStep.Index -> collectRoots(step.expr, out)
                    }
                }
            }
            is OgnlExpr.Unary -> collectRoots(expr.operand, out)
            is OgnlExpr.Binary -> {
                collectRoots(expr.left, out)
                collectRoots(expr.right, out)
            }
        }
    }

    // ------------------------------------------------------------------
    // 支持面分类（T-ExprAstPrecheck T-EP-01）
    // ------------------------------------------------------------------

    /** 表达式支持面分档（三档，见 [ExprSupport]）；供条件行声明性标注使用，纯 AST 遍历、零 PSI。 */
    fun classify(text: String): ExprSupport = classifyDetail(text).support

    /**
     * 分类结论（含理由码与细节）。解析失败即 [ExprSupport.UNSUPPORTED_SYNTAX]；解析成功后按
     * [collectFallbacks] 遍历 AST 收集降级形态（首个命中即结论），无命中为 [ExprSupport.SUPPORTED]。
     */
    fun classifyDetail(text: String): ExprClassification =
        when (val parsed = parse(text)) {
            is ParseResult.Err -> ExprClassification(ExprSupport.UNSUPPORTED_SYNTAX, ExprReason.SYNTAX, parsed.message)
            is ParseResult.Ok -> {
                val findings = mutableListOf<ExprClassification>()
                collectFallbacks(parsed.expr, findings)
                findings.firstOrNull() ?: ExprClassification(ExprSupport.SUPPORTED)
            }
        }

    /** 降级形态遍历（结构照抄 [collectStaticRefs]：Chain 先基础表达式、再逐步骤及其实参/下标）。 */
    private fun collectFallbacks(expr: OgnlExpr, out: MutableList<ExprClassification>) {
        when (expr) {
            is OgnlExpr.StaticFieldRef ->
                out += ExprClassification(ExprSupport.EVAL_FALLBACK, ExprReason.STATIC_FIELD, "@${expr.className}@${expr.fieldName}")
            is OgnlExpr.Chain -> {
                collectFallbacks(expr.base, out)
                expr.steps.forEachIndexed { index, step ->
                    when (step) {
                        is ChainStep.Property -> Unit
                        is ChainStep.Method -> {
                            // 接收者载体只在方法为链首步且基础表达式本身可判定时成立（如 `'x'.length()`）；
                            // `a.b.length()` 的接收者是上一级的求值结果，类型未知
                            methodFallback(if (index == 0) staticCarrier(expr.base) else null, step.name)?.let { out += it }
                            step.args.forEach { collectFallbacks(it, out) }
                        }
                        is ChainStep.Index -> collectFallbacks(step.expr, out)
                    }
                }
            }
            is OgnlExpr.Unary -> collectFallbacks(expr.operand, out)
            is OgnlExpr.Binary -> {
                collectFallbacks(expr.left, out)
                collectFallbacks(expr.right, out)
            }
            else -> Unit
        }
    }

    /**
     * 方法调用的降级判定（null = 落在支持面内）：
     *  - 白名单方法（[METHOD_CARRIERS]）：接收者载体已知时要求该载体有实现；载体未知时要求方法对
     *    全部容器载体（[CARRIER_AGNOSTIC]）都有实现——否则实际类型一换就静默返回 null；
     *  - getter 形态（`getX()` / `isX()`）走属性访问语义，属支持面；
     *  - 其余方法名不在白名单内 → 未知方法（求值静默返回 null）。
     */
    private fun methodFallback(receiverCarrier: ExprCarrier?, name: String): ExprClassification? {
        val carriers = METHOD_CARRIERS[name]
        if (carriers == null) {
            return if (isGetterName(name)) {
                null
            } else {
                ExprClassification(ExprSupport.EVAL_FALLBACK, ExprReason.UNKNOWN_METHOD, name)
            }
        }
        val supported = if (receiverCarrier != null) {
            receiverCarrier in carriers
        } else {
            CARRIER_AGNOSTIC.all { it in carriers }
        }
        return if (supported) null else ExprClassification(ExprSupport.EVAL_FALLBACK, ExprReason.NARROW_CARRIER, name)
    }

    /** 接收者的静态载体：仅 AST 自身可判定时返回非 null（变量引用 / 链式结果 / 算术结果类型未知）。 */
    private fun staticCarrier(expr: OgnlExpr): ExprCarrier? = when (expr) {
        is OgnlExpr.ListLiteral -> ExprCarrier.COLLECTION
        is OgnlExpr.Literal -> if (expr.value is CharSequence) ExprCarrier.CHAR_SEQUENCE else ExprCarrier.OTHER
        else -> null
    }

    /**
     * getter 形态判定（`getX()` / `isX()`，OGNL 属性访问语义）——直接复用 [firstPropName] 的方法分支
     * 判据，与 [method] 的 getter 推断、[collectPaths] 的路径收集同源（三处不会漂移）。
     */
    private fun isGetterName(name: String): Boolean =
        firstPropName(ChainStep.Method(name, emptyList())) != null

    /** 值载体（[METHOD_CARRIERS] 与 [staticCarrier] 用）：容器类载体 + 标量兜底。 */
    private enum class ExprCarrier { CHAR_SEQUENCE, COLLECTION, MAP, ARRAY, OTHER }

    /**
     * 方法白名单 → 该方法在求值器中实现了的载体（与 [method] 的 known 分支一一对应，同一事实的
     * 声明式投影）。[KNOWN_METHODS] 由此派生，两处清单不会漂移。
     */
    private val METHOD_CARRIERS: Map<String, Set<ExprCarrier>> = mapOf(
        "size" to setOf(ExprCarrier.CHAR_SEQUENCE, ExprCarrier.COLLECTION, ExprCarrier.MAP, ExprCarrier.ARRAY),
        "length" to setOf(ExprCarrier.CHAR_SEQUENCE),
        "isEmpty" to setOf(ExprCarrier.CHAR_SEQUENCE, ExprCarrier.COLLECTION, ExprCarrier.MAP),
        "contains" to setOf(ExprCarrier.CHAR_SEQUENCE, ExprCarrier.COLLECTION),
        "containsKey" to setOf(ExprCarrier.MAP),
        "startsWith" to setOf(ExprCarrier.CHAR_SEQUENCE),
        "endsWith" to setOf(ExprCarrier.CHAR_SEQUENCE),
        "toString" to ExprCarrier.values().toSet(),
    )

    /**
     * 接收者类型未知时要求方法对全部容器载体都有实现才算「载体无关」——载体集合取自
     * [METHOD_CARRIERS] 的并集（xml 层 bindings 可能取到的值：字符串 / 集合 / Map 等）。
     */
    private val CARRIER_AGNOSTIC: Set<ExprCarrier> = setOf(
        ExprCarrier.CHAR_SEQUENCE,
        ExprCarrier.COLLECTION,
        ExprCarrier.MAP,
    )

    // ------------------------------------------------------------------
    // 求值
    // ------------------------------------------------------------------

    private fun eval(expr: OgnlExpr, bindings: Map<String, Any?>, staticFieldValues: Map<String, Any?>): Any? = when (expr) {
        is OgnlExpr.Literal -> expr.value
        is OgnlExpr.ListLiteral -> expr.items.map { eval(it, bindings, staticFieldValues) }
        is OgnlExpr.RootRef -> bindings[expr.name]
        is OgnlExpr.StaticFieldRef -> staticFieldValues["${expr.className}@${expr.fieldName}"]
        is OgnlExpr.Chain -> {
            var current = eval(expr.base, bindings, staticFieldValues)
            for (step in expr.steps) {
                current = when (step) {
                    is ChainStep.Property -> property(current, step.name)
                    is ChainStep.Method -> method(current, step.name, step.args.map { eval(it, bindings, staticFieldValues) })
                    is ChainStep.Index -> index(current, eval(step.expr, bindings, staticFieldValues))
                }
            }
            current
        }
        is OgnlExpr.Unary -> when (expr.op) {
            "!", "not" -> !ognlTruthy(eval(expr.operand, bindings, staticFieldValues))
            "-" -> (eval(expr.operand, bindings, staticFieldValues) as? Number)?.let { toDecimal(it).negate() }
            else -> null
        }
        is OgnlExpr.Binary -> evalBinary(expr.op, eval(expr.left, bindings, staticFieldValues), eval(expr.right, bindings, staticFieldValues))
    }

    private fun evalBinary(op: String, left: Any?, right: Any?): Any? = when (op) {
        // 对齐 OGNL ASTOr/ASTAnd：返回第一个 truthy/falsy 操作数的**值**（非 Boolean）——
        // `'' || @F` 返回 @F 的值；`'x' || anything` 返回 'x'；判真用 OgnlOps 语义（空串为 false）
        "or", "||" -> if (ognlTruthy(left)) left else right
        "and", "&&" -> if (!ognlTruthy(left)) left else right
        "==" -> equal(left, right)
        "!=" -> !equal(left, right)
        "<" -> compare(left, right)?.let { it < 0 }
        ">" -> compare(left, right)?.let { it > 0 }
        "<=" -> compare(left, right)?.let { it <= 0 }
        ">=" -> compare(left, right)?.let { it >= 0 }
        "in" -> contains(right, left)
        "notin" -> !contains(right, left)
        "+" -> plus(left, right)
        "-" -> minus(left, right)
        "*" -> times(left, right)
        "/" -> div(left, right)
        "%" -> mod(left, right)
        else -> null
    }

    /** 数值统一为 BigDecimal（Number 无 toBigDecimal 扩展，经 toString 转换最通用）。 */
    private fun toDecimal(n: Number): BigDecimal = runCatching { BigDecimal(n.toString()) }.getOrDefault(BigDecimal.ZERO)

    /** 相等：Number 数值比较（对齐 OGNL OgnlOps.equal），其余 equals；null == null 为真。 */
    private fun equal(left: Any?, right: Any?): Boolean = when {
        left is Number && right is Number ->
            runCatching { toDecimal(left).compareTo(toDecimal(right)) == 0 }.getOrDefault(left == right)
        left == null || right == null -> left == null && right == null
        else -> left == right
    }

    /** 比较：Number / String（CharSequence）；其余返回 null（不可比）。 */
    private fun compare(left: Any?, right: Any?): Int? = when {
        left is Number && right is Number ->
            runCatching { toDecimal(left).compareTo(toDecimal(right)) }.getOrNull()
        left is CharSequence && right is CharSequence -> left.toString().compareTo(right.toString())
        else -> null
    }

    /** `+`：任一为 String/CharSequence → 字符串连接（null 显示为 "null"，对齐 OGNL）；否则数值相加。 */
    private fun plus(left: Any?, right: Any?): Any? = when {
        left is Number && right is Number -> toDecimal(left).add(toDecimal(right))
        left is CharSequence || right is CharSequence -> left.toString() + right.toString()
        else -> null
    }

    private fun minus(left: Any?, right: Any?): Any? =
        if (left is Number && right is Number) toDecimal(left).subtract(toDecimal(right)) else null

    private fun times(left: Any?, right: Any?): Any? =
        if (left is Number && right is Number) toDecimal(left).multiply(toDecimal(right)) else null

    private fun div(left: Any?, right: Any?): Any? {
        if (left !is Number || right !is Number || toDecimal(right) == BigDecimal.ZERO) return null
        // T-HiddenBugPerfScan 券 K②：真实 OGNL（ASTDivide/OgnlOps.divide）对两个整型操作数
        // 做整除（7/2=2），曾统一 BigDecimal.divide 得 3.5——test="total / 2 > 10" 分支与
        // 运行时分叉、${pages / 2} 产物出非法 SQL 片段；循环小数再抛 ArithmeticException。
        // 同为整型（含溢出回落的 BigDecimal 整数值）→ BigInteger 整除（截断向零）；
        // 混有小数 → 保持 BigDecimal 精确除。
        return if (isIntegral(left) && isIntegral(right))
            toDecimal(left).divideToIntegralValue(toDecimal(right))
        else toDecimal(left).divide(toDecimal(right))
    }

    /** 整型判定（含词法溢出回落的 BigDecimal——无小数点即整数值）。 */
    private fun isIntegral(n: Number): Boolean =
        n is Byte || n is Short || n is Int || n is Long || n is java.math.BigInteger ||
            (n is BigDecimal && n.stripTrailingZeros().scale() <= 0)

    private fun mod(left: Any?, right: Any?): Any? =
        if (left is Number && right is Number && toDecimal(right) != BigDecimal.ZERO)
            toDecimal(left).remainder(toDecimal(right))
        else null

    /** `in`：右为集合/数组 → 成员相等；String → 子串；Map → 含键。 */
    private fun contains(container: Any?, target: Any?): Boolean = when (container) {
        is Collection<*> -> container.any { equal(it, target) }
        is Array<*> -> container.any { equal(it, target) }
        is CharSequence -> container.contains(target?.toString() ?: "")
        is Map<*, *> -> container.containsKey(target)
        else -> false
    }

    private fun property(current: Any?, name: String): Any? = when (current) {
        is MockObject -> current[name]
        is Map<*, *> -> current[name]
        is Collection<*> -> when (name) {
            "size" -> current.size
            "isEmpty" -> current.isEmpty()
            else -> null
        }
        is Array<*> -> when (name) {
            "size" -> current.size
            "isEmpty" -> current.isEmpty()
            else -> null
        }
        is CharSequence -> when (name) {
            "length" -> current.length
            "size" -> current.length
            "isEmpty" -> current.isEmpty()
            else -> null
        }
        else -> null
    }

    /** 方法白名单（真实方法优先于 getter 推断，如 `isEmpty()` 不被 `is` 前缀规则误捕）；未知方法 → null。 */
    private fun method(current: Any?, name: String, args: List<Any?>): Any? {
        val first = args.firstOrNull()
        val known = when {
            current is CharSequence && name == "length" -> current.length
            current is CharSequence && name == "size" -> current.length
            current is Collection<*> && name == "size" -> current.size
            current is Map<*, *> && name == "size" -> current.size
            current is Array<*> && name == "size" -> current.size
            current is CharSequence && name == "isEmpty" -> current.isEmpty()
            current is Collection<*> && name == "isEmpty" -> current.isEmpty()
            current is Map<*, *> && name == "isEmpty" -> current.isEmpty()
            current is CharSequence && name == "contains" -> current.contains(first?.toString() ?: return null)
            current is Collection<*> && name == "contains" -> current.contains(first)
            current is Map<*, *> && name == "containsKey" -> current.containsKey(first)
            current is CharSequence && name == "startsWith" -> current.startsWith(first?.toString() ?: return null)
            current is CharSequence && name == "endsWith" -> current.endsWith(first?.toString() ?: return null)
            current != null && name == "toString" -> current.toString()
            else -> null
        }
        if (known != null || name in KNOWN_METHODS) return known
        // getter 推断（OGNL 属性访问语义）：`obj.getTitle()` ≡ `obj.title`、`obj.isFlag()` ≡ `obj.flag`
        if (name.length > 3 && name.startsWith("get") && name[3].isUpperCase()) {
            return property(current, name.substring(3).replaceFirstChar { it.lowercaseChar() })
        }
        if (name.length > 2 && name.startsWith("is") && name[2].isUpperCase()) {
            return property(current, name.substring(2).replaceFirstChar { it.lowercaseChar() })
        }
        return null
    }

    /** 方法白名单（T-ExprAstPrecheck T-EP-01：由 [METHOD_CARRIERS] 派生，分类器与求值器共用同一清单）。 */
    private val KNOWN_METHODS: Set<String> = METHOD_CARRIERS.keys

    private fun index(current: Any?, key: Any?): Any? = when (current) {
        is List<*> -> (key as? Number)?.toInt()?.let { current.getOrNull(it) }
        is Array<*> -> (key as? Number)?.toInt()?.let { current.getOrNull(it) }
        is Map<*, *> -> current[key]
        else -> null
    }

    // ------------------------------------------------------------------
    // 词法
    // ------------------------------------------------------------------

    private enum class TokenKind { IDENT, NUMBER, STRING, SYMBOL, EOF }

    private class Token(val kind: TokenKind, val text: String, val value: Any? = null)

    private class OgnlParseException(message: String) : RuntimeException(message)

    private class Lexer(private val text: String) {
        private var pos = 0

        fun next(): Token {
            skipWhitespace()
            if (pos >= text.length) return Token(TokenKind.EOF, "")
            val c = text[pos]
            return when {
                c.isLetter() || c == '_' || c == '$' -> ident()
                c.isDigit() -> number()
                c == '\'' || c == '"' -> string(c)
                else -> symbol()
            }
        }

        fun peek(): Token {
            val saved = pos
            val token = next()
            pos = saved
            return token
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun ident(): Token {
            val start = pos
            while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_' || text[pos] == '$')) pos++
            return Token(TokenKind.IDENT, text.substring(start, pos))
        }

        private fun number(): Token {
            val start = pos
            while (pos < text.length && text[pos].isDigit()) pos++
            if (pos < text.length && text[pos] == '.' && pos + 1 < text.length && text[pos + 1].isDigit()) {
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            // T-HiddenBugPerfScan 券 K①：超宽整数字面量（>Long.MAX_VALUE）曾 toLong() 直抛
            // NumberFormatException——unchecked 异常穿透 parseUncached 的 OgnlParseException
            // 收敛层直上 EDT（mapper 里 test="id > 99999999999999999999" 即触发预览整链失败）。
            // 真实 OGNL 按大整数处理：回落 BigDecimal（Number 家族，比较/算术/toDecimal 全兼容）。
            val value = if (raw.contains('.')) raw.toDouble() else raw.toLongOrNull() ?: java.math.BigDecimal(raw)
            return Token(TokenKind.NUMBER, raw, value)
        }

        private fun string(quote: Char): Token {
            val start = pos
            pos++ // 开引号
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos]
                if (c == quote) {
                    pos++
                    return Token(TokenKind.STRING, text.substring(start, pos), sb.toString())
                }
                if (c == '\\' && pos + 1 < text.length) {
                    when (val n = text[pos + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = text.substring(pos + 2, minOf(pos + 6, text.length))
                            if (hex.length == 4 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            } else sb.append('u')
                        }
                        else -> sb.append(n) // \' \" \\ 等
                    }
                    pos += 2
                } else {
                    sb.append(c)
                    pos++
                }
            }
            throw OgnlParseException("unterminated string literal at $start")
        }

        private fun symbol(): Token {
            val two = text.substring(pos, minOf(pos + 2, text.length))
            if (two in SYMBOLS_TWO) {
                pos += 2
                return Token(TokenKind.SYMBOL, two)
            }
            val one = text[pos].toString()
            if (one in SYMBOLS_ONE) {
                pos++
                return Token(TokenKind.SYMBOL, one)
            }
            throw OgnlParseException("unexpected character '$one' at $pos")
        }

        companion object {
            val SYMBOLS_TWO = setOf("&&", "||", "==", "!=", "<=", ">=")
            val SYMBOLS_ONE = setOf("!", "<", ">", "+", "-", "*", "/", "%", "(", ")", "{", "}", "[", "]", ",", ".", "@")
        }
    }

    // ------------------------------------------------------------------
    // 语法分析（递归下降）
    // ------------------------------------------------------------------

    private class Parser(private val lexer: Lexer) {
        private var current = lexer.next()

        fun parseExpression(): ParseResult = try {
            val expr = parseOr()
            if (current.kind != TokenKind.EOF) {
                throw OgnlParseException("unexpected trailing token '${current.text}'")
            }
            ParseResult.Ok(expr)
        } catch (e: OgnlParseException) {
            ParseResult.Err(e.message ?: "parse error")
        }

        private fun advance() {
            current = lexer.next()
        }

        private fun expect(text: String) {
            if (current.text != text) throw OgnlParseException("expected '$text' but found '${current.text}'")
            advance()
        }

        private fun parseOr(): OgnlExpr {
            var left = parseAnd()
            while (current.text == "or" || current.text == "||") {
                val op = current.text
                advance()
                left = OgnlExpr.Binary(op, left, parseAnd())
            }
            return left
        }

        private fun parseAnd(): OgnlExpr {
            var left = parseEquality()
            while (current.text == "and" || current.text == "&&") {
                val op = current.text
                advance()
                left = OgnlExpr.Binary(op, left, parseEquality())
            }
            return left
        }

        private fun parseEquality(): OgnlExpr {
            var left = parseRelational()
            while (current.text == "==" || current.text == "!=") {
                val op = current.text
                advance()
                left = OgnlExpr.Binary(op, left, parseRelational())
            }
            return left
        }

        private fun parseRelational(): OgnlExpr {
            var left = parseAdditive()
            while (current.text in RELATIONAL_OPS || isNotIn()) {
                val op = if (isNotIn()) {
                    advance()
                    expect("in")
                    "notin"
                } else {
                    val t = current.text
                    advance()
                    t
                }
                left = OgnlExpr.Binary(op, left, parseAdditive())
            }
            return left
        }

        /** `not in` 检测：只查看下一个 token（peek 不动 current——advance 无法回退 Lexer 位置）。 */
        private fun isNotIn(): Boolean {
            if (current.text != "not") return false
            return lexer.peek().text == "in"
        }

        private fun parseAdditive(): OgnlExpr {
            var left = parseMultiplicative()
            while (current.text == "+" || current.text == "-") {
                val op = current.text
                advance()
                left = OgnlExpr.Binary(op, left, parseMultiplicative())
            }
            return left
        }

        private fun parseMultiplicative(): OgnlExpr {
            var left = parseUnary()
            while (current.text == "*" || current.text == "/" || current.text == "%") {
                val op = current.text
                advance()
                left = OgnlExpr.Binary(op, left, parseUnary())
            }
            return left
        }

        private fun parseUnary(): OgnlExpr {
            if (current.text == "!" || current.text == "not") {
                val op = current.text
                advance()
                return OgnlExpr.Unary(op, parseUnary())
            }
            if (current.text == "-") {
                advance()
                return OgnlExpr.Unary("-", parseUnary())
            }
            return parsePostfix()
        }

        private fun parsePostfix(): OgnlExpr {
            val base = parsePrimary()
            val steps = mutableListOf<ChainStep>()
            while (true) {
                when {
                    current.text == "." -> {
                        advance()
                        if (current.kind != TokenKind.IDENT) throw OgnlParseException("expected property name after '.'")
                        val name = current.text
                        advance()
                        if (current.text == "(") {
                            steps += ChainStep.Method(name, parseArgs())
                        } else {
                            steps += ChainStep.Property(name)
                        }
                    }
                    current.text == "[" -> {
                        advance()
                        val indexExpr = parseOr()
                        expect("]")
                        steps += ChainStep.Index(indexExpr)
                    }
                    else -> break
                }
            }
            return if (steps.isEmpty()) base else OgnlExpr.Chain(base, steps)
        }

        /**
         * 静态字段引用（T-M25.2）：`@com.xxx.Class@FIELD`（OGNL 静态访问，MyBatis mapper 常见）。
         * 类名支持包路径与 `$`（内部类）；`@@`（root 类）与静态方法调用暂不支持（解析报错）。
         */
        private fun parseStaticFieldRef(): OgnlExpr {
            advance() // '@'
            val className = StringBuilder()
            while (true) {
                when {
                    current.kind == TokenKind.IDENT -> {
                        className.append(current.text)
                        advance()
                    }
                    current.text == "." -> {
                        // 点后必须跟标识符（拒绝尾点如 `@a.b.@X`）
                        if (lexer.peek().kind != TokenKind.IDENT) {
                            throw OgnlParseException("expected class name after '@'")
                        }
                        className.append('.')
                        advance()
                    }
                    else -> break
                }
            }
            if (current.text != "@") {
                throw OgnlParseException("expected '@' after class name in static reference, found '${current.text}'")
            }
            advance()
            if (current.kind != TokenKind.IDENT) {
                throw OgnlParseException("expected static field name after '@class@'")
            }
            val field = current.text
            advance()
            return OgnlExpr.StaticFieldRef(className.toString(), field)
        }

        private fun parseArgs(): List<OgnlExpr> {
            expect("(")
            val args = mutableListOf<OgnlExpr>()
            if (current.text != ")") {
                args += parseOr()
                while (current.text == ",") {
                    advance()
                    args += parseOr()
                }
            }
            expect(")")
            return args
        }

        private fun parsePrimary(): OgnlExpr {
            return when {
                current.text == "@" -> parseStaticFieldRef()
                current.kind == TokenKind.NUMBER -> {
                    val token = current
                    advance()
                    OgnlExpr.Literal(token.value)
                }
                current.kind == TokenKind.STRING -> {
                    val token = current
                    advance()
                    OgnlExpr.Literal(token.value)
                }
                current.kind == TokenKind.IDENT -> {
                    when (current.text) {
                        "true" -> {
                            advance()
                            OgnlExpr.Literal(true)
                        }
                        "false" -> {
                            advance()
                            OgnlExpr.Literal(false)
                        }
                        "null" -> {
                            advance()
                            OgnlExpr.Literal(null)
                        }
                        else -> {
                            val name = current.text
                            advance()
                            if (current.text == "(") {
                                // 根级方法调用（罕见）：按 bindings 根的 method 调用处理
                                OgnlExpr.Chain(OgnlExpr.RootRef(name), listOf(ChainStep.Method(name, parseArgs())))
                            } else {
                                OgnlExpr.RootRef(name)
                            }
                        }
                    }
                }
                current.text == "(" -> {
                    advance()
                    val inner = parseOr()
                    expect(")")
                    inner
                }
                current.text == "{" -> {
                    advance()
                    val items = mutableListOf<OgnlExpr>()
                    if (current.text != "}") {
                        items += parseOr()
                        while (current.text == ",") {
                            advance()
                            items += parseOr()
                        }
                    }
                    expect("}")
                    OgnlExpr.ListLiteral(items)
                }
                else -> throw OgnlParseException("unexpected token '${current.text}'")
            }
        }

        companion object {
            val RELATIONAL_OPS = setOf("<", ">", "<=", ">=", "in")
        }
    }

    /** T-Perf-04：表达式解析缓存上限（超过整体清空——防一次性生成海量动态表达式的膨胀）。 */
    private const val PARSE_CACHE_MAX = 1024
}
