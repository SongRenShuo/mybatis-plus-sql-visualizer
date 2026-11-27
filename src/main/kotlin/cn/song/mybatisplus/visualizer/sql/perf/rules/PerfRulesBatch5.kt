package cn.song.mybatisplus.visualizer.sql.perf.rules

import cn.song.mybatisplus.visualizer.sql.perf.DialectAwarePerfRule
import cn.song.mybatisplus.visualizer.sql.perf.PerfContext
import cn.song.mybatisplus.visualizer.sql.perf.PerfRule
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.operators.conditional.AndExpression
import net.sf.jsqlparser.expression.operators.conditional.OrExpression
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.expression.operators.relational.InExpression
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.util.TablesNamesFinder

/**
 * 第五批性能规则（方言感知 + 规模硬限，T-ParamLimit / D272）。
 *
 * 与第四批的差别只是**动机**：第四批处理「写法是否被方言接受」，本批处理「规模是否越方言硬限」。
 * 实现范式完全沿用 [DialectAwarePerfRule]（AST 优先、ast==null 降级正则、无方言上下文不报）。
 *
 * 规则清单：
 *  - `param_limit_exceeded`：单个 `IN (...)` 列表的项数超过目标方言的硬限
 *    （Oracle 系 1000 / SQL Server 2100 / PG 族 65535），超限在真机是**运行时直接报错**而非变慢。
 */
object PerfRulesBatch5 {

    /** 所有第五批规则实例。 */
    val all: List<PerfRule> = listOf(
        ParamLimitRule,
    )

    /**
     * 各方言的 `IN` 列表项数硬限。
     *
     * ⚠️ **key 必须是 [cn.song.mybatisplus.visualizer.sql.Dialect.name] 的真实字面量**：
     * 达梦与人大金仓的名字带**全角括号**（`DM（达梦）` / `KingBase（人大金仓）`），SQL Server 中间是
     * 空格。写成短名 `"DM"` / `"KingBase"` 会让规则**静默永不触发**，而且测试若同样写错还会绿
     * （空绿）。[ParamLimitDialectNameTest] 从
     * [cn.song.mybatisplus.visualizer.sql.DialectRegistry.dialects] 遍历取真名钉住本表。
     *
     * 不在表内 = 该方言无明确 `IN` 列表项数硬限（MySQL / MariaDB / OceanBase / H2 / SQLite /
     * GoldenDB / HighGo / Vastbase），不报。
     */
    internal val IN_LIST_LIMIT: Map<String, Int> = mapOf(
        "Oracle" to 1000,
        "DM（达梦）" to 1000,
        "KingBase（人大金仓）" to 1000,
        "SQL Server" to 2100,
        "PostgreSQL" to 65535,
        "openGauss" to 65535,
        "GaussDB" to 65535,
    )

    /**
     * 硬限**未经真机取证**的方言（计划 §六.4）：达梦与人大金仓是 Oracle 兼容模式，1000 这个数
     * 大概率一致，但没有真机读数。先降为 WARN，等真机验证再升档——与其用 ERROR 假装确定，
     * 不如用 WARN 明确说出「这条限制我们没验过」。
     */
    internal val UNVERIFIED_LIMIT_DIALECTS: Set<String> = setOf("DM（达梦）", "KingBase（人大金仓）")

    /**
     * `param_limit_exceeded`：单个 `IN (...)` 列表项数超过目标方言硬限 → 告警。
     *
     * **只报告不改写**：与 [PerfRulesBatch4.DialectQuoteMismatchRule] 同口径——插件渲染的是
     * MyBatis-Plus 真实发出的 SQL，把 1001 个参数「悄悄拆成两批」会让预览偏离真机。拆批是用户的
     * 架构决策（事务边界、性能曲线都变），不该由静态检查代劳。
     *
     * 判定路径：
     *  - **AST 优先**：用 [TablesNamesFinder] 遍历整树收集每个 [InExpression]，取其右值里的列表长度
     *    （见 [inListSize]）。`IN (SELECT …)` 的右值是 `ParenthesedSelect` 而非列表，天然不算参数；
     *    字符串字面量里的 `IN (1,2,3)` 不是节点，天然不误伤。
     *  - **降级（ast == null）**：先剥离字符串字面量与注释，再正则扫 `IN <spaces> (`，排除
     *    `IN (SELECT`（子查询），按顶层逗号计数。
     *
     * 严重度三档：
     *  - AST + 方言有明确硬限 + 已真机取证 + 超出 → **ERROR**（真机确定会失败）；
     *  - AST + 方言硬限存在但**未真机取证**（达梦 / 金仓）+ 超出 → **WARN**（文案带前提）；
     *  - 降级路径（正则计数可能不准）+ 超出 → **WARN**；
     *  - 方言无明确硬限 / 无方言上下文 → **不报**。
     *
     * 命中时**至多一条**告警，报全语句最大的那个 `IN` 列表（用户一次只需改一处）。
     */
    object ParamLimitRule : DialectAwarePerfRule {

        override val ruleId = "param_limit_exceeded"

        /** 无方言上下文的降级：不做判定（[DialectAwarePerfRule] 契约要求实现，语义即「不报」）。 */
        override fun check(sql: String, ast: Statement?): List<PerfWarning> = emptyList()

        override fun check(ctx: PerfContext): List<PerfWarning> = try {
            evaluate(ctx)
        } catch (e: Exception) {
            // 规则隔离：契约明写「不得上抛」。正则回溯、栈溢出以外的异常都吞掉返回空。
            emptyList()
        }

        private fun evaluate(ctx: PerfContext): List<PerfWarning> {
            val dialect = ctx.dialect ?: return emptyList()
            val limit = IN_LIST_LIMIT[dialect.name] ?: return emptyList()

            val fromAst = ctx.ast != null
            val maxSize = if (fromAst) {
                maxInListSizeFromAst(ctx.ast!!)
            } else {
                maxInListSizeFromText(ctx.sql)
            }
            if (maxSize <= limit) return emptyList()

            val unverified = dialect.name in UNVERIFIED_LIMIT_DIALECTS
            val severity = if (fromAst && !unverified) PerfSeverity.ERROR else PerfSeverity.WARN
            val messageKey = when {
                // 未真机取证：文案必须自曝「这条限制没验过」，否则用户会把 ERROR/WARN 当同等可信
                unverified -> "perf.param_limit_exceeded_unverified"
                !fromAst -> "perf.param_limit_exceeded_regex"
                else -> "perf.param_limit_exceeded"
            }
            return listOf(
                PerfWarning(
                    ruleId = ruleId,
                    severity = severity,
                    messageKey = messageKey,
                    messageArgs = listOf(maxSize.toString(), dialect.name, limit.toString()),
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // AST 路径
    // ------------------------------------------------------------------

    /**
     * 收集整树所有 [InExpression]，返回其中最大的列表项数；无列表形态时返回 -1。
     *
     * 用 [TablesNamesFinder] 驱动整树遍历（它实现了 SELECT / 表达式 / 列 / 表等全部访问面，
     * 见第四批同款用法），只覆写 [visit] 取 IN 节点——子查询、JOIN ON、HAVING、CASE WHEN、
     * UPDATE SET 里的 IN 都在遍历范围内（探针实测）。
     */
    private fun maxInListSizeFromAst(ast: Statement): Int {
        val collector = InExpressionCollector()
        collector.getTables(ast)
        return collector.maxSize
    }

    private class InExpressionCollector : TablesNamesFinder<Any?>() {
        var maxSize = -1

        override fun <S> visit(inExpression: InExpression, context: S): Any? {
            val size = inListSize(inExpression.rightExpression)
            if (size > maxSize) maxSize = size
            return super.visit(inExpression, context)
        }
    }

    /**
     * 取 `IN` 右值里列表的项数；右值不是列表（`IN (SELECT …)`）返回 -1。
     *
     * **必须沿着 AND / OR 向左解包**——实测（JSqlParser 5.3）：
     * `WHERE id IN (?,?,?) AND deleted = 0` 的 `InExpression.rightExpression` 不是列表，而是
     * `AndExpression`，列表藏在它的**左**分支里；右分支 `deleted = 0` 是普通谓词。只判
     * `right is ExpressionList` 会让**最常见的「IN + 其它条件」形态静默漏报**（探针读数见计划
     * 实施记录）。左分支再套 AND/OR 时继续向左，直到拿到列表或别的节点。
     *
     * 为什么只往左：`getRightExpression()` 的语义是「IN 之后紧跟的那一段」，语法上 IN 的列表
     * 必然在段的**最左**；右侧全是后续谓词。行值 IN `(a,b) IN ((1,2),(3,4))` 的左值是列表，
     * 右值仍是列表，不受影响。
     */
    private fun inListSize(right: Expression?): Int {
        var expr = right
        var guard = 0
        while (expr != null && guard++ < AND_UNWRAP_GUARD) {
            when (expr) {
                is ExpressionList<*> -> return expr.size
                is AndExpression -> { expr = expr.leftExpression; continue }
                is OrExpression -> { expr = expr.leftExpression; continue }
                else -> return -1
            }
        }
        return -1
    }

    /** 解包深度上限（防御异常 AST 造成的环；正常 SQL 的谓词嵌套远低于此）。 */
    private const val AND_UNWRAP_GUARD = 64

    // ------------------------------------------------------------------
    // 降级路径（ast == null）
    // ------------------------------------------------------------------

    /**
     * 正则降级路径：剥离单引号字符串字面量与注释后，扫每个 `IN (`，取其括号内顶层逗号数 + 1。
     *
     * `IN (SELECT …)` 必须排除——那是子查询，括号里的逗号不是参数分隔。只报 WARN（正则可能
     * 因换行、嵌套括号、`IN((…))` 等形态数错）。
     */
    private fun maxInListSizeFromText(sql: String): Int {
        val stripped = stripLiteralsAndComments(sql)
        var max = -1
        for (m in IN_LIST_PATTERN.findAll(stripped)) {
            val inner = m.groupValues[1]
            // 子查询不是参数列表：`IN (SELECT a, b FROM t)` 的逗号是投影分隔，不是参数分隔
            if (isSubqueryList(inner)) continue
            val size = topLevelItemCount(inner)
            if (size > max) max = size
        }
        return max
    }

    /** 括号内顶层项数：顶层逗号数 + 1；空内容算 0。 */
    private fun topLevelItemCount(inner: String): Int {
        val trimmed = inner.trim()
        if (trimmed.isEmpty()) return 0
        var depth = 0
        var count = 1
        for (ch in trimmed) {
            when (ch) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                ',' -> if (depth == 0) count++
            }
        }
        return count
    }

    /**
     * 逐字符剥离：单引号字符串（含两个连写单引号的转义）与双引号字符串、双连字符行注释、
     * 以及成对星号包裹的块注释。
     *
     * 双引号也要剥：`SELECT "IN (1,2,3)" FROM t` 这类标识符、以及 Oracle 系的引号标识符，
     * 剥掉后不会误判。与第三/四批的剥离函数同口径（那里只要单引号，因为标识符引号本身是
     * 检测对象；本批引号内容一律不参与计数）。
     */
    private fun stripLiteralsAndComments(sql: String): String {
        val sb = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' || c == '"' -> {
                    val quote = c
                    i++
                    while (i < sql.length) {
                        if (sql[i] == quote) {
                            // 连写两个同种引号是转义（SQL 标准对单引号成立；双引号同理保守处理）
                            if (i + 1 < sql.length && sql[i + 1] == quote) {
                                i += 2
                                continue
                            }
                            i++
                            break
                        }
                        i++
                    }
                }
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                }
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                    if (i + 1 < sql.length) i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /**
     * `IN` + 可选空白/注释残留 + `(` + 到最近 `)` 的内容。
     *
     * 用**非贪婪到第一个 `)`** 而不是配平括号：配平要写状态机，而多元素列表里不会出现内层
     * 括号（出现了也只是少少数项，方向是漏报不是误报）。`(?i)` 兼容小写 `in`。
     */
    private val IN_LIST_PATTERN = Regex("(?i)\\bIN\\s*\\(([^)]*)\\)")

    /** 已剥离字面量与注释的文本上判断该 `IN (...)` 是否是子查询。 */
    internal fun isSubqueryList(inner: String): Boolean =
        inner.trimStart().startsWith("SELECT", ignoreCase = true) ||
            inner.trimStart().startsWith("WITH", ignoreCase = true)
}
