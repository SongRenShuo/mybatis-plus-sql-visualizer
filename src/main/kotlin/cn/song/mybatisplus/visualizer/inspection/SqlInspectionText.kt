package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.xml.OgnlEvaluator

/**
 * 编辑器内 SQL 检查的**纯逻辑内核**（NEW-26）。
 *
 * 分层照 [BannedApiMatcher] 的薄壳范式：inspection 只做 PSI 取数与 `registerProblem`，
 * 这里全部是「文本 → 结论」的纯函数——普通 JUnit 可直测，不需要工程 fixture。
 *
 * 判据来源（**不重写判据**）：
 *  - `${}`/`#{}` 的 token 语义照 `xml/TokenParser` + `xml/XmlSqlRenderer`（`\` 转义、无闭合原样保留）；
 *  - 参数根名解析照 MyBatis `ParamNameResolver`（`@Param` 值 > 实际参数名，多参补 `param1..N`，
 *    另认 `_parameter` / `_databaseId`）；`<bind>` / `<foreach item>` 声明的名字也是合法根名；
 *  - OGNL 常量条件判定直接复用 [OgnlEvaluator]（与预览渲染同一套求值器），不另写表达式解析；
 *  - 方言引号判定用 `Dialect.identifierQuotes`（能力矩阵单一事实源）。
 *
 * 为什么不做「取整个 mapper 文件文本喂 `mapperCheck` 再解析它的输出」：`mapperCheck` 的返回值是
 * **给人看的报告字符串**（不是机器契约），反解析既脆又拿不到源文件偏移；它内部用的
 * `XmlMapperParser` / `XmlPreviewModel` / `SqlPerfAnalyzer` 才是判定本体，适配层
 * [MapperSqlElementSupport] 直接复用这些本体（并在文件级解析路径上使用 [cn.song.mybatisplus.visualizer.xml.XmlMapperParser]
 * 展开 `<include>` 前先跳过——见 P-1 结论：PSI 文本不含展开，故扫描面按源文件字面走）。
 */
object SqlInspectionText {

    /** 一个 `#{...}` / `${...}` token 在被分析文本中的位置与内容。 */
    data class SqlToken(
        /** token 原文（含 `${` / `#{` 与 `}`）。 */
        val raw: String,
        /** token 内容（`#{x,jdbcType=VARCHAR}` 取逗号前的 `x`，去空白）。 */
        val expression: String,
        /** 表达式根名（`p.startTime` → `p`；`@类@常量` → 原样）。 */
        val root: String,
        /** 在文本中的起始偏移（指向 `$` / `#`）。 */
        val start: Int,
        /** 结束偏移（不含）。 */
        val end: Int,
    )

    /** MyBatis-Plus Wrapper 直接替换占位：语义上就是「把 Wrapper 生成的片段整段拼进来」，非用户输入。 */
    private val MP_WRAPPER_EXPRESSIONS = setOf(
        "ew.customSqlSegment", "ew.sqlSegment", "ew.sqlSelect", "ew.nonEmptyOfWhere",
        "ew.nonEmptyOfNormal", "ew.nonEmptyOfEntity", "ew.nonEmptyOfWhereChoose",
    )

    /** 平台/框架注入的特殊参数名（MyBatis `_parameter` / `_databaseId`）。 */
    val RESERVED_ROOTS: Set<String> = setOf("_parameter", "_databaseId", "ew", "ewc")

    /**
     * 扫描文本里的 `${...}` token（`\${` 视为转义、不算 token——与 [cn.song.mybatisplus.visualizer.xml.TokenParser] 同语义）。
     */
    fun dollarTokens(text: String): List<SqlToken> = scanTokens(text, '$')

    /** 扫描文本里的 `#{...}` token（`\#{` 转义不计）。 */
    fun hashTokens(text: String): List<SqlToken> = scanTokens(text, '#')

    private fun scanTokens(text: String, sigil: Char): List<SqlToken> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<SqlToken>()
        var i = 0
        while (i + 1 < text.length) {
            if (text[i] == sigil && text[i + 1] == '{' && (i == 0 || text[i - 1] != '\\')) {
                val close = text.indexOf('}', i + 2)
                if (close < 0) break // 无闭合：MyBatis 原样保留，不是 token
                val raw = text.substring(i, close + 1)
                val expression = text.substring(i + 2, close).substringBefore(',').trim()
                out += SqlToken(raw = raw, expression = expression, root = rootOf(expression), start = i, end = close + 1)
                i = close + 1
                continue
            }
            i++
        }
        return out
    }

    /** 表达式根名：取首个 `.`/`[` 之前的部分；`@类@常量` 形态原样返回。 */
    fun rootOf(expression: String): String {
        val trimmed = expression.trim()
        if (trimmed.startsWith("@")) return trimmed
        return trimmed.substringBefore('.').substringBefore('[').trim()
    }

    // ------------------------------------------------------------------
    // 规则 1：${} 直接替换注入风险
    // ------------------------------------------------------------------

    /**
     * `${}` 注入风险判定（**0 误报口径**）。
     *
     * 不是「见到 `${}` 就报」——mapper 里 `${}` 最常见的合法用法是「`<bind>` 静态常量 → 结果集列别名」
     * （本仓语料清单 X02 的 22 处全部属此类）。故放行三类：
     *  1. 绑定到**静态常量 / 字面量**的 `<bind>` 名字（`value="@com.x.Const@FIELD"`、`value="'x'"`、纯数字）；
     *  2. MyBatis-Plus Wrapper 片段（`ew.customSqlSegment` 等，框架自带、非用户输入）；
     *  3. 静态字段引用表达式（以 `@` 开头，OGNL 静态访问语法，值来自编译期常量）。
     *
     * 其余一律报「直接替换、值未转义」。这样 `ORDER BY ${column}`、`${tableName}`、`LIMIT ${n}`
     * 这类真正由参数拼进 SQL 结构的形态会被标出来，而列别名场景不产生噪音。
     *
     * @param staticBindNames 绑定到常量/字面量的 `<bind>` 名字集合（PSI 层收集）
     */
    fun dollarFindings(text: String, staticBindNames: Set<String> = emptySet()): List<SqlInspectionFinding> =
        dollarTokens(text)
            .filterNot { token ->
                token.expression in MP_WRAPPER_EXPRESSIONS ||
                    token.expression.startsWith("@") ||
                    token.root in staticBindNames
            }
            .map { token ->
                SqlInspectionFinding(
                    rule = SqlInspectionRule.DOLLAR_INJECTION,
                    messageKey = "inspection.sqlv.dollar.problem",
                    messageArgs = listOf(token.raw),
                    start = token.start,
                    length = token.raw.length,
                    token = token.raw,
                )
            }

    /**
     * `<bind>` 的值是否「静态常量 / 字面量」——决定该绑定名能否给 `${}` 规则放行。
     *
     * 保守口径：只认 `@类@常量` 与带引号字面量、纯数字；`value="p.orderBy"` 这类**来自参数**的
     * 绑定一律不算静态（正是注入面）。
     */
    fun isStaticBindValue(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return false
        if (v.startsWith("@")) return true
        if (v.length >= 2 && ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\"")))) return true
        return v.toBigDecimalOrNull() != null
    }

    // ------------------------------------------------------------------
    // 规则 2：#{}/#{} 引用的参数在方法签名里不存在
    // ------------------------------------------------------------------

    /**
     * `#{}` 引用的根名不在「方法参数 + 语句内声明（bind/foreach item）」里 → 报。
     *
     * 只判**根名**（`#{p.startTime}` 看 `p`）：属性路径是否合法要解析实体/Map，静态不可判定，
     * 判了必误报。
     *
     * @param knownRoots   方法参数名集合（`@Param` 值 / 实际参数名 / `param1..N` / `_parameter` 等）
     * @param declaredRoots 语句内声明的名字（`<bind name>`、`<foreach item|index>`），这些不是方法参数但合法
     */
    fun missingParameterFindings(
        text: String,
        knownRoots: Set<String>,
        declaredRoots: Set<String> = emptySet(),
    ): List<SqlInspectionFinding> = hashTokens(text)
        .filter { it.root.isNotEmpty() }
        .filterNot { it.root in knownRoots || it.root in declaredRoots || it.root in RESERVED_ROOTS }
        .filter { IDENTIFIER.matches(it.root) }
        .map { token ->
            SqlInspectionFinding(
                rule = SqlInspectionRule.MISSING_PARAMETER,
                messageKey = "inspection.sqlv.param.problem",
                messageArgs = listOf(token.expression),
                start = token.start,
                length = token.raw.length,
                token = token.raw,
            )
        }

    /** 合法标识符（含 `$`/数字尾段）；OGNL 表达式型根名（带运算符/括号）不判，避免误报。 */
    private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    /**
     * 方法参数 → 可用的 `#{}` 根名集合（对齐 `ParamNameResolver`）。
     *
     * @param paramNames     参数名（`@Param` 值优先，否则实际参数名）
     * @param hasParamAnnotation 是否任一参数带 `@Param`
     * @param paramCount     参数个数
     * @return null = **无法判定**（单参数且无 `@Param` 时 MyBatis 允许任意属性名 → 必须放弃判定，
     *         否则会把 `#{id}`（单参实体属性）误报成缺参）
     */
    fun paramRoots(paramNames: List<String>, hasParamAnnotation: Boolean, paramCount: Int): Set<String>? {
        if (paramCount == 0) return setOf("_parameter", "_databaseId")
        if (paramCount == 1 && !hasParamAnnotation) return null
        val roots = LinkedHashSet<String>(paramNames.size + paramCount + 2)
        roots += paramNames
        for (index in 1..paramCount) roots += "param$index"
        roots += RESERVED_ROOTS
        return roots
    }

    // ------------------------------------------------------------------
    // 规则 3：resultMap 属性在实体类里不存在
    // ------------------------------------------------------------------

    /**
     * `<result property="x">`（及 `<id>`/`<arg>`）的属性名在 `resultMap type` 类里找不到 → 报。
     *
     * 只判「类已解析且成员集合非空」的情形：`type` 未解析（短名别名 / 依赖缺失 / dumb 索引）
     * 一律不判——**无法确认时保持沉默**是 0 误报的前提。
     *
     * @param memberNames 类成员名（字段 + getter/setter 派生属性名）；null = 类未解析
     * @return 不存在的属性名；null = 不判定
     */
    fun unknownResultMapProperty(property: String, memberNames: Set<String>?): String? {
        val target = property.trim()
        if (target.isEmpty() || memberNames == null || memberNames.isEmpty()) return null
        val first = target.substringBefore('.').substringBefore('[').trim()
        if (first.isEmpty()) return null
        return if (first in memberNames) null else target
    }

    // ------------------------------------------------------------------
    // 规则 4：方言不兼容的标识符引号
    // ------------------------------------------------------------------

    /** 反引号标识符（`` `col` ``）。 */
    private val BACKTICK_IDENTIFIER = Regex("`[^`\\n]+`")

    /** 方括号标识符（`[col]`）——要求首字符是字母/下划线，避免把数组下标 `[0]`/`[i]` 当标识符。 */
    private val BRACKET_IDENTIFIER = Regex("\\[[A-Za-z_][A-Za-z0-9_$]*\\]")

    /**
     * 方言不兼容引号判定：反引号 / 方括号标识符若不在当前方言的 `identifierQuotes` 里 → 报。
     *
     * **只判这两种引号**：双引号在 MySQL 家族是字符串字面量、在 PG/Oracle 是标识符，静态无法区分，
     * 判它必误报（故不判）。反引号/方括号不存在这种歧义——当前方言不收却出现，就是真语法错误。
     *
     * 扫描时跳过单引号字符串字面量与 `--` / `/* */` 注释。
     *
     * @param allowedQuotes 当前方言接受的标识符引号字符集（`Dialect.identifierQuotes`）
     * @param dialectName   文案里显示的方言名
     */
    fun dialectQuoteFindings(
        text: String,
        allowedQuotes: Set<Char>,
        dialectName: String,
    ): List<SqlInspectionFinding> {
        if (text.isEmpty()) return emptyList()
        val visible = stripLiteralsAndComments(text)
        val findings = ArrayList<SqlInspectionFinding>()
        if ('`' !in allowedQuotes) {
            for (m in BACKTICK_IDENTIFIER.findAll(visible)) {
                findings += SqlInspectionFinding(
                    rule = SqlInspectionRule.DIALECT_QUOTE,
                    messageKey = "inspection.sqlv.dialect.problem",
                    messageArgs = listOf("`", dialectName),
                    start = m.range.first,
                    length = m.value.length,
                )
            }
        }
        if ('[' !in allowedQuotes) {
            for (m in BRACKET_IDENTIFIER.findAll(visible)) {
                findings += SqlInspectionFinding(
                    rule = SqlInspectionRule.DIALECT_QUOTE,
                    messageKey = "inspection.sqlv.dialect.problem",
                    messageArgs = listOf("[ ]", dialectName),
                    start = m.range.first,
                    length = m.value.length,
                )
            }
        }
        return findings
    }

    /**
     * 把单引号字符串字面量、`--` 行注释、`/* */` 块注释**逐字符等长涂白**（空格替换），
     * 使偏移与原文严格对齐，而引号/注释里的内容不参与判定。
     */
    fun stripLiteralsAndComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\'' -> {
                    out.append(' ')
                    i++
                    while (i < text.length) {
                        val c = text[i]
                        out.append(if (c == '\n') '\n' else ' ')
                        i++
                        if (c == '\'') break
                    }
                }
                ch == '-' && i + 1 < text.length && text[i + 1] == '-' -> {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                ch == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < text.length) {
                        if (text[i] == '*' && i + 1 < text.length && text[i + 1] == '/') {
                            out.append("  ")
                            i += 2
                            break
                        }
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // 规则 5：OGNL test 恒真 / 恒假
    // ------------------------------------------------------------------

    /**
     * `test` 表达式是否为**常量表达式**，是则返回其真值；非常量（引用参数/属性/bind）→ null。
     *
     * 判据：表达式里没有任何根变量、也没有静态字段引用，然后交给 [OgnlEvaluator] 求值——
     * 与预览渲染同一套求值器，不另写解析。`test="true"` / `test="1==1"` / `test="1 != 2"` 命中；
     * `test="p.startTime != null"`（引用参数）不命中——这是 0 误报的边界。
     */
    fun constantTestTruth(test: String): Boolean? {
        val expr = test.trim()
        if (expr.isEmpty()) return null
        val roots = runCatching { OgnlEvaluator.rootVariables(expr) }.getOrNull() ?: return null
        if (roots.isNotEmpty()) return null
        val staticRefs = runCatching { OgnlEvaluator.staticFieldRefs(expr) }.getOrNull() ?: return null
        if (staticRefs.isNotEmpty()) return null
        return when (val result = runCatching { OgnlEvaluator.evaluate(expr, emptyMap()) }.getOrNull()) {
            is cn.song.mybatisplus.visualizer.xml.EvalResult.Ok -> OgnlEvaluator.isTrue(result.value)
            else -> null
        }
    }
}