package cn.song.mybatisplus.visualizer.psource

import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiDoWhileStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiSwitchStatement
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiWhileStatement
import com.intellij.psi.util.PsiTreeUtil

/**
 * SQL 字符串拼接聚合器（JDBC 模板 SQL 字符串预览）：把分散在字面量 `+` 拼接 /
 * StringBuilder 分片 / 变量引用中的 SQL 片段聚合为单一文本。
 *
 * 两路实现：
 *  - 路径 A — 字面量 `+` 拼接（`"SELECT " + "..."`）→ 递归收集 `PsiLiteralExpression` 并 join
 *  - 路径 B — StringBuilder 聚合（`new StringBuilder().append(...)` 或
 *    `StringBuilder sql = new StringBuilder(); sql.append(querySelect)...`）→
 *    收集同一方法体内对目标变量的 `append` 调用（按 offset 排序），递归聚合每个实参
 *
 * 变量引用沿 initializer 递归追踪（不跨方法体），含运行时值时标记 `complete=false`
 * 并在结果中保留占位标记。
 *
 * 零 resolve（仅结构遍历 + initializer 读取），单测可注入假 PSI。
 */
object SqlStringConcatenator {

    /** 字符串聚合结果。 */
    data class ConcatenationResult(
        /** 聚合后的 SQL 文本（含可能残留的运行时值占位标记）。 */
        val sql: String,
        /** true = 完全静态可聚合；false = 含运行时值（预览降级）。 */
        val complete: Boolean,
    )

    /**
     * 运行时值占位标记前缀（预览 warning 与 SQL 中保留位置）。
     *
     * R1：取值带空格与冒号，配合 [dynamicPlaceholder] 的收尾构成**单条自洽块注释**。
     */
    const val DYNAMIC_PLACEHOLDER_PREFIX = "/* 运行时值: "

    /** 占位文本长度上限（R1.4：超出截断加 `…`，避免整段三元把 SQL 撑爆）。 */
    private const val DYNAMIC_PLACEHOLDER_MAX_LENGTH = 120

    /** R3：循环体内追加的降级占位说明（运行期重复 N 次，线性拼接必错）。摘要取不到时回落本条。 */
    private const val LOOP_APPEND_LABEL = "循环内追加，运行时重复执行"

    /** P1：循环占位带模板摘要时的前缀（后接清洗后的模板摘要）。 */
    private const val LOOP_APPEND_SUMMARY_PREFIX = "循环内追加 "

    /** P1：循环占位带模板摘要时的后缀（摘要取不到时整条回落 [LOOP_APPEND_LABEL]，不加本后缀）。 */
    private const val LOOP_APPEND_SUMMARY_SUFFIX = "（运行时重复）"

    /** P1：模板摘要里「非字符串字面量片段」（变量引用 / 方法调用 / 任意表达式）的替代符。 */
    private const val TEMPLATE_ELLIPSIS = "…"

    /** P1：三元两支模板的分隔符——运行期只走一支，接起来会读成「两段都在」。 */
    private const val TEMPLATE_BRANCH_SEPARATOR = " / "

    /**
     * P1：模板摘要长度预算。
     *
     * 摘要先按本上限截断，再拼前后缀——保证整条占位不超 [DYNAMIC_PLACEHOLDER_MAX_LENGTH]，
     * 否则外层清洗的长度上限会把 `（运行时重复）` 从尾巴上截掉（那正是这条摘要的意义所在）。
     */
    private val TEMPLATE_SUMMARY_MAX_LENGTH: Int =
        DYNAMIC_PLACEHOLDER_MAX_LENGTH - LOOP_APPEND_SUMMARY_PREFIX.length - LOOP_APPEND_SUMMARY_SUFFIX.length

    /** R3：if/else 互斥两支都对同一 builder 追加时的降级占位说明。 */
    private const val PAIRED_APPEND_LABEL = "互斥分支追加，运行时只取一支"

    /** R3：无 else 的条件追加 — 保留片段，前面插本条说明注释。 */
    private const val CONDITIONAL_APPEND_NOTE = "/* 运行时值: 条件追加，可能不出现 */"

    /** R3：switch 分支内追加 — 保留片段，前面插本条说明注释。 */
    private const val SWITCH_APPEND_NOTE = "/* 运行时值: 分支追加，可能不出现 */"

    /** R3：lambda / 回调内追加 — 执行时机与次数由调用方决定。 */
    private const val LAMBDA_APPEND_NOTE = "/* 运行时值: 回调内追加，执行时机不定 */"

    /** 变量 initializer 追踪最大深度（防循环引用）。 */
    private const val MAX_TRACE_DEPTH = 5

    /** 跟随「源码可见方法返回值」的最大层数（T-HqlHelperReturn：防递归/环）。 */
    private const val MAX_METHOD_FOLLOW_DEPTH = 3

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 聚合表达式为 SQL 文本。
     *
     * @param expr SQL 字符串实参表达式（字面量 / 拼接 / 变量引用 / StringBuilder 变量）
     * @return 聚合结果；无法聚合返回 `null`
     */
    fun concat(expr: PsiExpression?): ConcatenationResult? {
        if (expr == null) return null
        return when (expr) {
            is PsiLiteralExpression -> concatLiteral(expr)
            is PsiConditionalExpression -> concatConditional(expr)
            is PsiBinaryExpression, is PsiPolyadicExpression -> concatPolyadic(expr)
            is PsiReferenceExpression -> concatReference(expr)
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // R1 — 运行时值占位（单条自洽块注释）
    // ------------------------------------------------------------------

    /**
     * 生成一条**自洽的**运行时值占位注释（R1）：块注释起始符 + ` 运行时值: ` + 清洗后文本 + 空格 + 块注释结束符。
     *
     * 输出必须是一条完整块注释 —— 不允许出现「注释先闭合 + 源码裸露 + 悬空收尾」的形态
     * （旧实现把前缀写成当场闭合的注释、源码原样跟在后面、末尾再补一个结束符，
     * 正是用户截图里右括号丢失、引号错位的根因，见开发计划 §2.2）。
     *
     * 清洗规则（缺一不可，见开发计划 R1.1）：
     *  1. 注释结束符 → `* /`：否则注释提前闭合，后面的源码变成活 SQL；
     *  2. 单/双引号 → 同形安全字符（`’` / `”`）：占位可能落在字符串字面量内部，原文引号会截断字符串；
     *  3. 空白（换行 / 制表 / 连续空格）折叠为单空格：多行注释会打乱格式化器的行结构；
     *  4. 长度上限 [DYNAMIC_PLACEHOLDER_MAX_LENGTH]，超出截断并加 `…`。
     *
     * @param exprText 静态不可求值的表达式原文
     */
    private fun dynamicPlaceholder(exprText: String): String =
        "$DYNAMIC_PLACEHOLDER_PREFIX${sanitizePlaceholderText(exprText)} */"

    /**
     * 占位文本清洗（规则见 [dynamicPlaceholder]）。
     *
     * @param maxLength 长度上限；默认 [DYNAMIC_PLACEHOLDER_MAX_LENGTH]，「还要再包一层前后缀」的
     *   场景（循环模板摘要）传更小的预算，见 [TEMPLATE_SUMMARY_MAX_LENGTH]。
     */
    private fun sanitizePlaceholderText(
        exprText: String,
        maxLength: Int = DYNAMIC_PLACEHOLDER_MAX_LENGTH,
    ): String {
        val normalized = exprText
            .replace("*/", "* /")
            .replace('\'', '’')
            .replace('"', '”')
            .replace(Regex("\\s+"), " ")
            .trim()
        val text = normalized.ifEmpty { "表达式" }
        return if (text.length > maxLength) {
            text.take(maxLength - 1) + "…"
        } else {
            text
        }
    }

    // ------------------------------------------------------------------
    // R2 — 条件表达式（三元）分派
    // ------------------------------------------------------------------

    /**
     * 聚合 Java 三元表达式（R2）。
     *
     * 三元在运行期**只会走一支**，因此绝不把两支都拼进 SQL（那等于编造 SQL）：
     *  - 条件是 `true` / `false` 字面量 → 直接聚合对应分支；
     *  - 两支都聚合成功且文本相同 → 折叠取该文本（`complete` = 两支的与）；
     *  - 其余（分支文本不同 / 有一支聚合失败）→ 单条占位注释，`complete=false`。
     */
    private fun concatConditional(expr: PsiConditionalExpression): ConcatenationResult? {
        val conditionValue = (expr.condition as? PsiLiteralExpression)?.value as? Boolean
        if (conditionValue != null) {
            val branch = if (conditionValue) expr.thenExpression else expr.elseExpression
            return branch?.let { concat(it) }
        }
        val thenResult = expr.thenExpression?.let { concat(it) }
        val elseResult = expr.elseExpression?.let { concat(it) }
        if (thenResult != null && elseResult != null && thenResult.sql == elseResult.sql) {
            return ConcatenationResult(
                sql = thenResult.sql,
                complete = thenResult.complete && elseResult.complete,
            )
        }
        return ConcatenationResult(sql = dynamicPlaceholder(expr.text), complete = false)
    }

    /**
     * 聚合「StringBuffer/StringBuilder 变量 `.toString()` 调用链」形态：先把
     * `xxx.toString()` / `xxx.append(y).toString()` / `xxx.append(y)` 归约到持有变量引用，
     * 再交 [concat] 走既有聚合（变量 initializer 为 `new StringBuilder()` / `new StringBuffer()`
     * 时聚合其同方法体内全部 `append` 调用）。
     *
     * [concat] 自身对 `PsiMethodCallExpression` 无分支（返回 null），直接把 `sb.toString()`
     * 喂进去聚合不出内容——JdbcTemplate 通道靠 `JdbcTemplateSqlLocator.resolveToStringCall`
     * 做这步解包。本方法把同一语义提为共享入口，供 CustomDaoHqlLocator / HqlQueryLocator
     * 复用（T-HqlBufConcat），避免各处再写一份。
     *
     * @param expr SQL 字符串实参表达式（字面量 / 拼接 / 变量引用 / `sb.toString()` 调用链）
     * @return 聚合结果；无法聚合返回 `null`
     */
    fun concatUnwrappingToString(expr: PsiExpression?): ConcatenationResult? =
        concatUnwrappingToString(expr, 0)

    /**
     * 聚合入口（带跟随深度）。
     *
     * `PsiMethodCallExpression` 分两种承载：
     *  1. `sb.toString()` / `sb.append(x)` → 解包到持有变量后走 [concat]（T-HqlBufConcat）；
     *  2. **源码可见的方法返回的字符串**（`findByQuery(buildHql(), …)`）→ 跟随到方法体聚合其
     *     `return` 表达式（T-HqlHelperReturn）。
     */
    private fun concatUnwrappingToString(expr: PsiExpression?, depth: Int): ConcatenationResult? {
        if (expr == null) return null
        if (expr is PsiMethodCallExpression) {
            unwrapToStringTarget(expr)?.let { return concat(it) }
            return concatMethodReturn(expr, depth)
        }
        return concat(expr)
    }

    /**
     * 跟随「首参是被调方法」的形态：`commonDao.findByQuery(buildHql(), …)` 这类把 SQL 交给一个
     * 同工程方法去拼的写法（T-HqlHelperReturn）。
     *
     * 判定与护栏（保守优先，与「宁缺不错」一致）：
     *  - 方法必须**源码可见**（`PsiMethod.body != null`）：JDK / 三方库方法不跟随；
     *  - 聚合其全部 `return` 表达式：一个都聚合不出 → null；聚合结果**文本不一致**（多分支返回不同
     *    内容）→ null（不猜哪条分支）；
     *  - 只收本方法自身的 `return`，排除嵌套方法 / lambda 体内的（那些不属于本方法的返回值）；
     *  - 深层跟随有上限 [MAX_METHOD_FOLLOW_DEPTH]，防递归与环。
     *
     * 运行时值照既有口径降级：方法体内出现参数 / 调用等静态不可求值片段时，聚合结果带
     * 单条自洽占位注释（见 [dynamicPlaceholder]）并置 `complete=false`，不编造内容。
     */
    private fun concatMethodReturn(call: PsiMethodCallExpression, depth: Int): ConcatenationResult? {
        if (depth >= MAX_METHOD_FOLLOW_DEPTH) return null
        val method = call.resolveMethod() ?: return null
        val body = method.body ?: return null
        val returns = PsiTreeUtil.collectElementsOfType(body, PsiReturnStatement::class.java)
            .filter { PsiTreeUtil.getParentOfType(it, PsiMethod::class.java) === method }
            .filter { PsiTreeUtil.getParentOfType(it, PsiLambdaExpression::class.java, true) == null }
        val aggregated = returns.mapNotNull { stmt ->
            stmt.returnValue?.let { concatUnwrappingToString(it, depth + 1) }
        }
        if (aggregated.isEmpty()) return null
        val texts = aggregated.map { it.sql }.distinct()
        if (texts.size != 1) return null
        return ConcatenationResult(sql = texts[0], complete = aggregated.all { it.complete })
    }

    /**
     * 把 `.toString()` / `.append(y)` 调用链归约到根变量引用
     * （`xxx.append(y).toString()` → `xxx`）。
     *
     * 方法名不在 {toString, append} 内、或 qualifier 链不落到变量引用时返回 null。
     */
    private fun unwrapToStringTarget(call: PsiMethodCallExpression): PsiExpression? {
        val name = call.methodExpression.referenceName ?: return null
        if (name != "toString" && name != "append") return null
        var qualifier: PsiExpression? = call.methodExpression.qualifierExpression ?: return null
        while (qualifier is PsiMethodCallExpression) {
            qualifier = qualifier.methodExpression.qualifierExpression ?: return null
        }
        return qualifier
    }

    // ------------------------------------------------------------------
    // 路径 A — 字面量 + 拼接
    // ------------------------------------------------------------------

    private fun concatLiteral(literal: PsiLiteralExpression): ConcatenationResult? {
        val value = literal.value as? String ?: return null
        return ConcatenationResult(sql = value, complete = true)
    }

    private fun concatPolyadic(expr: PsiExpression): ConcatenationResult? {
        if (expr !is PsiPolyadicExpression) return null
        if (expr.operationTokenType != JavaTokenType.PLUS) return null
        val parts = mutableListOf<String>()
        var complete = true
        for (operand in expr.operands) {
            when (val r = concat(operand)) {
                null -> {
                    // 不可静态求值的操作数（运行时值）→ 标记降级 + 单条自洽占位注释
                    complete = false
                    parts += dynamicPlaceholder(operand.text)
                }
                else -> {
                    if (!r.complete) complete = false
                    parts += r.sql
                }
            }
        }
        return ConcatenationResult(sql = parts.joinToString(""), complete = complete)
    }

    // ------------------------------------------------------------------
    // 变量引用（路径 A/B 共享）
    // ------------------------------------------------------------------

    private fun concatReference(ref: PsiReferenceExpression, depth: Int = 0): ConcatenationResult? {
        if (depth > MAX_TRACE_DEPTH) return null
        val variable = ref.resolve() as? PsiVariable ?: return null
        val initializer = variable.initializer ?: return null
        return when (initializer) {
            is PsiLiteralExpression -> concatLiteral(initializer)
            is PsiBinaryExpression, is PsiPolyadicExpression -> concatPolyadic(initializer)
            is PsiReferenceExpression -> concatReference(initializer, depth + 1)
            is PsiNewExpression -> concatNewStringBuilder(initializer, variable, depth)
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // 路径 B — StringBuilder 聚合
    // ------------------------------------------------------------------

    /**
     * 聚合 `new StringBuilder(...)` 初值 + 同一方法体内全部 `append` 调用。
     *
     * @param newExpr      `new StringBuilder(...)` 表达式
     * @param variable     持有该 new 表达式的变量（用于查找 append 调用）
     * @param depth        当前追踪深度
     */
    private fun concatNewStringBuilder(
        newExpr: PsiNewExpression,
        variable: PsiVariable,
        depth: Int,
    ): ConcatenationResult? {
        val parts = mutableListOf<String>()
        var complete = true

        // 初值（`new StringBuilder("SELECT ...")`）
        val args = newExpr.argumentList?.expressions
        if (args != null && args.isNotEmpty()) {
            val initExpr = args[0]
            when (val r = concat(initExpr)) {
                null -> {
                    complete = false
                    parts += dynamicPlaceholder(initExpr.text)
                }
                else -> {
                    if (!r.complete) complete = false
                    parts += r.sql
                }
            }
        }

        // 查找同一方法体内对目标变量的 `append` 调用
        val appendResults = collectAppendCalls(variable, depth)
        for (r in appendResults) {
            if (!r.complete) complete = false
            parts += r.sql
        }

        val sql = parts.joinToString("")
        if (sql.isBlank() && appendResults.isEmpty()) return null
        return ConcatenationResult(sql = sql, complete = complete)
    }

    /**
     * 收集同一方法体内对目标变量的 `append` 调用（按 offset 排序）。
     *
     * 查找范围：目标变量所在方法体（PsiCodeBlock），过滤
     * `methodExpression.referenceName == "append"` 且 qualifier 解析到目标变量的调用。
     */
    private fun collectAppendCalls(variable: PsiVariable, depth: Int): List<ConcatenationResult> {
        val method = PsiTreeUtil.getParentOfType(variable, com.intellij.psi.PsiMethod::class.java)
            ?: return emptyList()
        val body = method.body ?: return emptyList()
        val varName = variable.name ?: return emptyList()
        if (varName.isBlank()) return emptyList()

        val appendCalls = PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
            .filter { call ->
                val methodName = call.methodExpression.referenceName ?: return@filter false
                if (methodName != "append") return@filter false
                // qualifier 可以是直接变量引用，也可以是链式方法调用（sql.append("A").append(x)）——
                // 沿 qualifier 链回溯到根变量，判定是否为目标变量
                val rootVar = resolveRootVariable(call.methodExpression.qualifierExpression)
                    ?: return@filter false
                rootVar == variable
            }
            // 按 startOffset 升序；链式调用中多层 append 共享同一 startOffset（都从 `sql` 起），
            // 此时按文本长度升序（内层最短 → 排前面），保证 append 实参按正确顺序拼接
            .sortedWith(compareBy({ it.textRange.startOffset }, { it.textLength }))

        val results = mutableListOf<ConcatenationResult>()
        // R3：同一控制流上下文（同一个循环 / if / switch / lambda）只降级或只标注一次，避免同一片段刷三条注释
        val degradedContexts = mutableSetOf<PsiElement>()
        val notedContexts = mutableSetOf<PsiElement>()

        for (call in appendCalls) {
            val args = call.argumentList.expressions
            if (args.isEmpty()) continue
            val argExpr = args[0]
            val position = analyzeAppendPosition(call, variable)
            val anchor = position.anchor
            when (position.flow) {
                AppendFlow.LOOP -> {
                    // 循环体内：贡献整体降级为占位注释（运行期重复 N 次，线性拼接必错）
                    if (anchor != null && !degradedContexts.add(anchor)) continue
                    // P1：占位带上循环体的模板摘要（只取字符串字面量，其余替换为 …），
                    // 摘要取不到时回落 LOOP_APPEND_LABEL 现文案
                    val summary = summarizeLoopTemplate(loopContextArguments(appendCalls, variable, anchor))
                    val label = if (summary == null) {
                        LOOP_APPEND_LABEL
                    } else {
                        LOOP_APPEND_SUMMARY_PREFIX + summary + LOOP_APPEND_SUMMARY_SUFFIX
                    }
                    results += ConcatenationResult(sql = dynamicPlaceholder(label), complete = false)
                }
                AppendFlow.PAIRED -> {
                    // if/else 互斥两支都追加：互斥片段不能同时出现在 SQL 里 → 两支都降级
                    if (anchor != null && !degradedContexts.add(anchor)) continue
                    results += ConcatenationResult(sql = dynamicPlaceholder(PAIRED_APPEND_LABEL), complete = false)
                }
                AppendFlow.OPTIONAL -> {
                    val r = concat(argExpr) ?: ConcatenationResult(
                        sql = dynamicPlaceholder(argExpr.text),
                        complete = false,
                    )
                    val note = position.note.orEmpty()
                    val emitNote = note.isNotEmpty() && (anchor == null || notedContexts.add(anchor))
                    results += if (emitNote) {
                        ConcatenationResult(sql = note + r.sql, complete = false)
                    } else {
                        // 非直线形态即便片段可聚合，也必须标 complete=false（位置不确定）
                        if (r.complete) ConcatenationResult(sql = r.sql, complete = false) else r
                    }
                }
                AppendFlow.STRAIGHT -> {
                    // 直线代码：维持既有输出
                    results += concat(argExpr) ?: ConcatenationResult(
                        sql = dynamicPlaceholder(argExpr.text),
                        complete = false,
                    )
                }
            }
        }
        return results
    }

    // ------------------------------------------------------------------
    // R3 — append 的控制流位置
    // ------------------------------------------------------------------

    /** append 相对方法体的控制流位置（R3）。 */
    private enum class AppendFlow {
        /** 直线代码：维持既有输出。 */
        STRAIGHT,

        /** 循环体内：运行期重复 N 次，线性拼接必错 → 整体降级为占位注释。 */
        LOOP,

        /** 条件 / 分支 / 回调内：保留片段，前置一条说明注释。 */
        OPTIONAL,

        /** if/else 互斥两支都对该 builder 追加 → 两支贡献都降级为占位注释。 */
        PAIRED,
    }

    /**
     * append 的控制流判定结果。
     *
     * @param flow   控制流形态
     * @param anchor 同一上下文的锚点（loop / if / switch / 三元 / lambda），用于同上下文只标注一次
     * @param note   [AppendFlow.OPTIONAL] 的前置说明注释
     */
    private data class AppendPosition(
        val flow: AppendFlow,
        val anchor: PsiElement? = null,
        val note: String? = null,
    )

    /**
     * 判定一条 `append` 调用相对所在方法体的控制流位置（R3）。
     *
     * 向上找最近的 [PsiForStatement] / [PsiForeachStatement] / [PsiWhileStatement] /
     * [PsiDoWhileStatement] / [PsiIfStatement] / [PsiSwitchStatement] /
     * [PsiConditionalExpression] / [PsiLambdaExpression]；遇到所在方法即停（不看外层类）。
     *
     * 循环优先于条件分支：`if` 落在循环内时按循环处理（重复 + 可选，降级即可覆盖）。
     */
    private fun analyzeAppendPosition(call: PsiMethodCallExpression, variable: PsiVariable): AppendPosition {
        var loop: PsiElement? = null
        var branch: PsiElement? = null
        var current: PsiElement? = call.parent
        while (current != null && current !is PsiMethod) {
            if (current is PsiForStatement || current is PsiForeachStatement ||
                current is PsiWhileStatement || current is PsiDoWhileStatement
            ) {
                if (loop == null) loop = current
            } else if (branch == null &&
                (current is PsiIfStatement || current is PsiSwitchStatement ||
                    current is PsiConditionalExpression || current is PsiLambdaExpression)
            ) {
                branch = current
            }
            current = current.parent
        }
        loop?.let { return AppendPosition(AppendFlow.LOOP, it) }
        return when (branch) {
            null -> AppendPosition(AppendFlow.STRAIGHT)
            is PsiIfStatement -> {
                val inThenBranch = branch.thenBranch?.let { PsiTreeUtil.isAncestor(it, call, false) } == true
                val otherBranch = if (inThenBranch) branch.elseBranch else branch.thenBranch
                if (containsAppendTo(otherBranch, variable)) {
                    AppendPosition(AppendFlow.PAIRED, branch)
                } else {
                    AppendPosition(AppendFlow.OPTIONAL, branch, CONDITIONAL_APPEND_NOTE)
                }
            }
            is PsiSwitchStatement -> AppendPosition(AppendFlow.OPTIONAL, branch, SWITCH_APPEND_NOTE)
            is PsiConditionalExpression -> AppendPosition(AppendFlow.OPTIONAL, branch, CONDITIONAL_APPEND_NOTE)
            is PsiLambdaExpression -> AppendPosition(AppendFlow.OPTIONAL, branch, LAMBDA_APPEND_NOTE)
            else -> AppendPosition(AppendFlow.STRAIGHT)
        }
    }

    /** 指定分支内是否存在对同一 builder 变量的 `append`（if/else 成对判定）。 */
    private fun containsAppendTo(branch: PsiElement?, variable: PsiVariable): Boolean {
        if (branch == null) return false
        return PsiTreeUtil.collectElementsOfType(branch, PsiMethodCallExpression::class.java).any { call ->
            call.methodExpression.referenceName == "append" &&
                resolveRootVariable(call.methodExpression.qualifierExpression) == variable
        }
    }

    /**
     * 沿 qualifier 链回溯根变量。
     *
     * 处理两种形态：
     *  - 直接调用 `sql.append(...)` → qualifier 是 PsiReferenceExpression → 直接解析
     *  - 链式调用 `sql.append("A").append(x).append("B")` → 外层 qualifier 是 PsiMethodCallExpression →
     *    递归取 qualifier 的 qualifier，直到回溯到 PsiReferenceExpression
     *
     * @param qualifier 方法调用的 qualifier 表达式（可能 null）
     * @return 根变量；无法解析返回 null
     */
    private fun resolveRootVariable(qualifier: PsiExpression?): PsiVariable? {
        var current: PsiExpression? = qualifier
        while (current != null) {
            current = when (current) {
                is PsiReferenceExpression -> return current.resolve() as? PsiVariable
                is PsiMethodCallExpression -> current.methodExpression.qualifierExpression
                else -> return null
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // P1（polish）— 循环占位的「模板摘要」
    // ------------------------------------------------------------------

    /**
     * 收集同一循环上下文内全部 `append` 的实参（按源码顺序），作为模板摘要的取样集合。
     *
     * 循环体的追加常见形态是**跨多条 append**（`sb.append("or id='").append(ids.get(k)).append("' ")`），
     * 只取「第一个触发降级的 append」会把摘要截成半截，所以按同一循环锚点整段取样。
     *
     * @param appendCalls 已按源码顺序排好、且都已筛成「对目标 builder 的 append」的调用列表
     * @param variable    目标 builder 变量
     * @param anchor      循环锚点（[analyzeAppendPosition] 给出的 `loop` 元素）
     * @return 该循环体内各 append 的首个实参（顺序同源码）
     */
    private fun loopContextArguments(
        appendCalls: List<PsiMethodCallExpression>,
        variable: PsiVariable,
        anchor: PsiElement?,
    ): List<PsiExpression> {
        if (anchor == null) return emptyList()
        return appendCalls
            .filter { analyzeAppendPosition(it, variable).anchor === anchor }
            .mapNotNull { it.argumentList.expressions.firstOrNull() }
    }

    /**
     * 把循环体的 append 序列汇成一条**模板摘要**（P1）：告诉用户「循环里追加的是什么」。
     *
     * 硬约束（与 [dynamicPlaceholder] 的清洗口径一致，见开发计划 R1.1）：
     *  - 只保留**字符串字面量片段**，变量引用 / 方法调用 / 任意表达式一律替换为 [TEMPLATE_ELLIPSIS]
     *    ——`k == 0 ?`、`ids.get(k)` 这类源码绝不出现在产物里；
     *  - 取不到字面量的实参贡献一个 [TEMPLATE_ELLIPSIS] 占位——相邻片段之间必须有分隔，
     *    否则 `append("or id='")` + `append(变量)` + `append("' ")` 会读成 `or id=''`（像空字面量）；
     *  - 整段一个字面量都取不到 → 返回 null，调用方回落 [LOOP_APPEND_LABEL] 现文案，不凭空编造；
     *  - 摘要仍走 [sanitizePlaceholderText]（注释结束符拆成 `* /`、引号换安全同形字符、空白折叠、长度上限）。
     *
     * @param args 循环体内各 append 的实参（源码顺序）
     */
    private fun summarizeLoopTemplate(args: List<PsiExpression>): String? {
        if (args.isEmpty()) return null
        val raw = StringBuilder()
        var hasLiteral = false
        for (arg in args) {
            val fragment = summarizeExpression(arg)
            if (fragment == null) {
                raw.append(TEMPLATE_ELLIPSIS)
            } else {
                raw.append(fragment)
                hasLiteral = true
            }
        }
        if (!hasLiteral) return null
        return sanitizePlaceholderText(raw.toString(), TEMPLATE_SUMMARY_MAX_LENGTH)
    }

    /**
     * 表达式 → 模板片段（P1），**只读结构、不 resolve**。
     *
     *  - 字符串字面量 → 字面量值（`"or id='"` → `or id='`，后续由清洗换安全同形引号）；
     *  - `+` 拼接 → 各操作数片段级联，非字面量操作数占一个 [TEMPLATE_ELLIPSIS]；
     *  - 三元 → 两支片段以 [TEMPLATE_BRANCH_SEPARATOR] 分隔（缺片段的一支用 `…` 顶位）；
     *  - 其余（变量引用 / 方法调用 / 任意表达式）→ null（由调用方决定补 `…` 还是回落）。
     *
     * 返回 null 的语义是「本表达式里没有字符串字面量」，不是「算不出来」——所以
     * `ids.get(k)` / `k == 0` 这类内容结构上就不可能被带出来。
     */
    private fun summarizeExpression(expr: PsiExpression): String? {
        when (expr) {
            is PsiLiteralExpression -> return (expr.value as? String)?.takeIf { it.isNotEmpty() }
            is PsiConditionalExpression -> {
                val thenFragment = expr.thenExpression?.let { summarizeExpression(it) }
                val elseFragment = expr.elseExpression?.let { summarizeExpression(it) }
                if (thenFragment == null && elseFragment == null) return null
                // 两支都要在场：只留一支会读成「循环里只有这一种形态」
                return (thenFragment ?: TEMPLATE_ELLIPSIS) +
                    TEMPLATE_BRANCH_SEPARATOR +
                    (elseFragment ?: TEMPLATE_ELLIPSIS)
            }
            is PsiPolyadicExpression -> {
                if (expr.operationTokenType != JavaTokenType.PLUS) return null
                val raw = StringBuilder()
                var hasLiteral = false
                for (operand in expr.operands) {
                    val fragment = summarizeExpression(operand)
                    if (fragment == null) {
                        raw.append(TEMPLATE_ELLIPSIS)
                    } else {
                        raw.append(fragment)
                        hasLiteral = true
                    }
                }
                return if (hasLiteral) raw.toString() else null
            }
            else -> return null
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 递归收集 PSI 树中全部 `PsiLiteralExpression` 的字符串值（路径 A 兜底）。
     * 用于处理嵌套拼接表达式。
     */
    fun collectStringLiterals(element: PsiElement): List<String> {
        if (element is PsiLiteralExpression) {
            val value = element.value as? String ?: return emptyList()
            return listOf(value)
        }
        return element.children.flatMap { collectStringLiterals(it) }
    }
}
