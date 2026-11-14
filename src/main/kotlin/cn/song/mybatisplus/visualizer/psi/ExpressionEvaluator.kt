package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.SqlFragment
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable

/**
 * 表达式求值器（F-02 前置）：把 Wrapper 方法实参表达式求值为 [SqlValue]。
 *
 * 分级策略：
 *  - 字面量（数字 / 字符串 / 布尔 / char / null）：求值为 [SqlValue.Literal]，可直接渲染进 SQL
 *  - 变量引用：追踪同文件初始化赋值（M2-01，`Long deviceId = 1001L;` → `1001`），
 *    支持多级引用链；追踪不到（方法参数等运行时值）求值为 [SqlValue.Placeholder]
 *  - 其他表达式（算术 / 方法调用 / new）：求值为 [SqlValue.Placeholder]，渲染为 `?`
 */
object ExpressionEvaluator {

    /** 变量追踪最大深度，防止循环引用死循环。 */
    private const val MAX_TRACE_DEPTH = 5

    /** 片段中用户自写、无可追溯来源的占位符所用的中性来源名（保持参数表与 `?` 数量对齐）。 */
    private const val UNKNOWN_PARAM = "?"

    /**
     * 求值表达式。
     *
     * @param expression 实参表达式（PSI）
     * @return SQL 值模型
     */
    fun evaluate(expression: PsiExpression): SqlValue = when (expression) {
        is PsiLiteralExpression -> evaluateLiteral(expression)
        is PsiReferenceExpression -> evaluateReference(expression)
        // `X.class` 类字面量（MPJ join 参数）→ 求值为简单类名（如 DeviceInfoEntity）
        is PsiClassObjectAccessExpression -> evaluateClassLiteral(expression)
        // 字符串拼接表达式（`"LIMIT " + N`、`ALIAS + ".username"`）→ 静态拼接
        is PsiPolyadicExpression -> evaluatePolyadic(expression)
        // T-Mxx：getter 方法调用（`user.getId()`）→ 字段引用格式（`user.id`）
        is PsiMethodCallExpression -> evaluateMethodCall(expression)
        // M38.2（语义对照矩阵实证）：一元负/正号 + 数值字面量（`-1`、`-1.5`）→ 静态字面量——
        // Java 没有负数字面量 token，`-1` 是 PsiPrefixExpression，落 else 分支会被误判为运行时占位符
        is com.intellij.psi.PsiPrefixExpression -> evaluatePrefix(expression)
        else -> SqlValue.Placeholder(expression.text)
    }

    /**
     * 一元 +/- 表达式求值（M38.2）：仅当操作数是数值字面量时静态求值（`-1` → `-1`），
     * 其余（变量/方法调用取负）降级为占位符。
     */
    private fun evaluatePrefix(expr: com.intellij.psi.PsiPrefixExpression): SqlValue {
        val inner = evaluate(expr.operand ?: return SqlValue.Placeholder(expr.text))
        if (inner !is SqlValue.Literal) return SqlValue.Placeholder(expr.text)
        val operandText = (expr.operand as? com.intellij.psi.PsiLiteralExpression)?.text
            ?: return SqlValue.Placeholder(expr.text)
        if (!operandText.matches(Regex("-?\\d+(\\.\\d+)?([fFlLdD])?"))) return SqlValue.Placeholder(expr.text)
        // SQL 数值字面量不带 Java 后缀（`5L` → `5`、`1.5f` → `1.5`），与 evaluateLiteral 数值路径一致
        val plain = operandText.trimEnd('f', 'F', 'l', 'L', 'd', 'D')
        return when (expr.operationTokenType) {
            JavaTokenType.MINUS -> SqlValue.Literal(raw = "-$plain", unquoted = "-$plain")
            JavaTokenType.PLUS -> SqlValue.Literal(raw = plain, unquoted = plain)
            else -> SqlValue.Placeholder(expr.text)
        }
    }

    /**
     * 求值字符串拼接表达式（清单 P11 `selectAs("alias.col" ...)`、P12 `last("LIMIT " + N)`）。
     *
     * 仅当连接符全为 `+` 且所有操作数可静态求值（字面量/可追踪变量/嵌套拼接）时拼接为字符串；
     * 否则降级为占位符（运行时值），同时尽力给出该表达式的**原始 SQL 片段**形态（[fragmentOf]）。
     */
    private fun evaluatePolyadic(expr: PsiPolyadicExpression): SqlValue {
        if (expr.operationTokenType != JavaTokenType.PLUS) return SqlValue.Placeholder(expr.text)
        val parts = mutableListOf<String>()
        for (operand in expr.operands) {
            when (val v = evaluate(operand)) {
                is SqlValue.Literal -> parts += v.unquoted
                else -> return SqlValue.Placeholder(expr.text, fragmentOf(expr))
            }
        }
        val joined = parts.joinToString("")
        return SqlValue.Literal(raw = "'" + joined.replace("'", "''") + "'", unquoted = joined)
    }

    /**
     * 拼接表达式的「原始 SQL 片段」形态（T-UpdateSetSqlConcat）：静态段保留、运行期段写 `?`，
     * 同时按文本顺序记录每个占位符的来源（片段里的 `?` 不登记进参数表就会让面板回填整体错位）。
     *
     * 例：`"amount = amount * " + rate` → 文本 `amount = amount * ?`、来源 `[rate]`。
     *
     * 来源按**真实占位符位点**收敛：字符串字面量/注释内的 `?` 不计（`first("/*+ ...(" + ms + ") */")`
     * 的 `?` 在块注释里，不能凭空多出一个来源——那会让面板把后面的值填错位）。
     *
     * 只在整体是字符串拼接时才给值——数值算术（`1 + n`）不是 SQL 片段，给 null 以免拼出 `1?`。
     */
    private fun fragmentOf(expr: PsiPolyadicExpression): SqlFragment? {
        if (expr.operationTokenType != JavaTokenType.PLUS) return null
        if (!isStringConcat(expr)) return null
        val text = StringBuilder()
        // 文本位点 → 来源（仅用于最终按真实占位符位点过滤；注释/引号内的会被丢弃）
        val marks = mutableListOf<Pair<Int, String>>()
        fun appendRuntime(source: String) {
            marks += text.length to source
            text.append('?')
        }
        for (operand in expr.operands) {
            when (val v = evaluate(operand)) {
                is SqlValue.Literal -> {
                    // 字面量里用户自写的 `?` 也是一次占位（注释/引号内的会被最终过滤掉）
                    v.unquoted.forEachIndexed { index, ch ->
                        if (ch == '?') marks += (text.length + index) to UNKNOWN_PARAM
                    }
                    text.append(v.unquoted)
                }
                is SqlValue.Placeholder -> {
                    val nested = v.fragment
                    if (nested != null) {
                        // 嵌套拼接：按嵌套文本的**真实占位符位点**逐位对齐来源（引号/注释内的 `?` 不参与）
                        val realOffsets = placeholderPositions(nested.text)
                        nested.params.forEachIndexed { i, source ->
                            realOffsets.getOrNull(i)?.let { at -> marks += (text.length + at) to source }
                        }
                        text.append(nested.text)
                    } else {
                        appendRuntime(v.source)
                    }
                }
            }
        }
        val effective = placeholderPositions(text.toString()).toSet()
        return SqlFragment(text.toString(), marks.filter { it.first in effective }.map { it.second })
    }

    /**
     * 是否为字符串拼接：类型可解析时按 `String` 判定（Java 里 `String + 任意类型` 结果恒为 String），
     * 类型解析不到（light fixture 缺 classpath 等）时回落到「任一操作数静态求值为字符串/字符字面量」
     * ——判据取求值结果而非 PSI 字面量，这样变量追踪到的静态前缀（`String p = "a = "; p + x`）也能识别。
     */
    private fun isStringConcat(expr: PsiPolyadicExpression): Boolean {
        if (expr.type?.equalsToText("java.lang.String") == true) return true
        return expr.operands.any { operand ->
            val value = evaluate(operand)
            // 字符串/字符字面量的 raw 带单引号（见 SqlValue.Literal KDoc），数值原样
            value is SqlValue.Literal && value.raw.startsWith("'")
        }
    }

    /**
     * 求值类字面量 `X.class` → 简单类名。
     * 直接从文本提取（如 `com.foo.X.class` → `X`），避免依赖类解析。
     */
    private fun evaluateClassLiteral(expr: PsiClassObjectAccessExpression): SqlValue {
        val className = expr.text.removeSuffix(".class").trim().substringAfterLast('.')
        return SqlValue.Literal(className)
    }

    /**
     * 求值字面量。
     */
    private fun evaluateLiteral(literal: PsiLiteralExpression): SqlValue {
        val value = literal.value
        return when (value) {
            // null 字面量 → SQL NULL
            null -> SqlValue.Literal("NULL")

            // 字符串 → 单引号包裹（转义内部单引号）；unquoted 保留原始值供 SQL 片段场景使用
            is String -> SqlValue.Literal(
                raw = "'" + value.replace("'", "''") + "'",
                unquoted = value,
            )

            // 布尔
            is Boolean -> SqlValue.Literal(value.toString())

            // 字符 → 单引号包裹
            // T-HiddenBugPerfScan 券 Y8：与 String 分支同口径补 ' → '' 转义——Char 值
            // 含单引号（.eq(::getX, '\'')）曾产出三引号断串 raw
            is Char -> SqlValue.Literal(
                raw = "'" + value.toString().replace("'", "''") + "'",
                unquoted = value.toString(),
            )

            // 数值类型（Byte/Short/Int/Long/Float/Double）
            is Number -> SqlValue.Literal(value.toString())

            // 其他（如文本块等）按文本原样
            else -> SqlValue.Literal(literal.text)
        }
    }

    /**
     * T-Mxx：方法调用求值——识别 getter 方法（getXxx/isXxx）为字段名。
     *  - `user.getId()` → Placeholder("id")
     *  - `user.getName()` → Placeholder("name")
     *  - `isActive()` → Placeholder("active")
     *  - 非 getter 方法（`user.toString()`、`list.get(0)`）→ Placeholder(expression.text)
     *
     * 仅处理无实参方法（getter 特征），有实参或无前缀匹配时降级为完整表达式文本。
     * 返回纯字段名（不带 qualifier 前缀），与 SQL 列名对齐。
     */
    private fun evaluateMethodCall(call: PsiMethodCallExpression): SqlValue {
        val methodName = call.methodExpression.referenceName ?: return SqlValue.Placeholder(call.text)
        // 仅处理无实参的 getter/is 方法
        if (call.argumentList.expressions.isNotEmpty()) return SqlValue.Placeholder(call.text)
        val fieldName = when {
            methodName.startsWith("get") && methodName.length > 3 -> methodName.removePrefix("get")
            methodName.startsWith("is") && methodName.length > 2 -> methodName.removePrefix("is")
            else -> return SqlValue.Placeholder(call.text)
        }
        if (fieldName.isEmpty()) return SqlValue.Placeholder(call.text)
        // 首字母小写 → 字段名（纯字段名，不带 qualifier）
        return SqlValue.Placeholder(fieldName.replaceFirstChar { it.lowercaseChar() })
    }

    /**
     * 求值变量引用（M2-01 变量追踪）。
     *
     * 沿变量的初始化表达式递归追踪：
     *  - `Long deviceId = 1001L;` → 字面量求值
     *  - `Long a = b; Long b = 2L;` → 多级引用链（受 [MAX_TRACE_DEPTH] 保护）
     *  - 无法静态确定（方法参数等）→ 占位符
     */
    private fun evaluateReference(ref: PsiReferenceExpression, depth: Int = 0): SqlValue {
        if (depth > MAX_TRACE_DEPTH) {
            return SqlValue.Placeholder(ref.text)
        }
        val variable = ref.resolve() as? PsiVariable ?: return SqlValue.Placeholder(ref.text)
        val initializer = variable.initializer ?: return SqlValue.Placeholder(ref.text)
        return when (initializer) {
            is PsiLiteralExpression -> evaluateLiteral(initializer)
            is PsiReferenceExpression -> evaluateReference(initializer, depth + 1)
            else -> SqlValue.Placeholder(ref.text)
        }
    }
}
