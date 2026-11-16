package cn.song.mybatisplus.visualizer.ui.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多字符运算符保真（T-SqlOperatorFidelity，用户 2026-11-11 真机反馈）。
 *
 * 立项动因：iBatis 的 `'%' || #GROUP_NAME# || '%'` 在预览面板被显示成 `'%' | | '弘博' | | '%'`。
 * 根因是 [SqlLexer.scanOperator] 只合并 `!=` `<>` `<=` `>=` 四个双字符运算符，其余多字符运算符
 * 被切成两个单字符 token，[SqlFormatter] 的「运算符两侧空格」规则把它们拼成 `| |` / `: :` / `<= >`
 * ——Oracle 下这是语法错误，而面板编辑器文本同时是复制可执行 SQL、分享、直接执行的输入源。
 *
 * 本类只钉「一个运算符 = 一个 token = 产物里整体保留」，不涉及格式化器的空格/换行规则。
 */
class SqlFormatterOperatorFidelityTest {

    private fun format(sql: String): String = SqlFormatter.format(sql)

    // ------------------------------------------------------------------
    // T2/T3：用户原样语料与三条格式化路径
    // ------------------------------------------------------------------

    @Test
    fun testIbatisLikeConcatStaysIntact() {
        val sql = "select * from BS_CMIOTGROUP_SUCCESS where CMIOT_GROUP_ID is not null " +
            "and GROUP_NAME like '%' || #GROUP_NAME# || '%' group by CMIOT_GROUP_ID"
        val out = format(sql)
        assertTrue("拼接运算符被拆开：$out", out.contains("'%' || #GROUP_NAME# || '%'"))
        assertFalse("拼接运算符被拆成两条竖线：$out", out.contains("| |"))
    }

    @Test
    fun testConcatStaysIntactInAllThreeFormatPaths() {
        val sql = "select * from t where name like '%' || 'x' || '%'"

        val formatted = SqlFormatter.format(sql)
        assertTrue("format：$formatted", formatted.contains("'%' || 'x' || '%'"))

        val clauseLine = SqlFormatter.formatClauseLine(sql)
        assertTrue("formatClauseLine：$clauseLine", clauseLine.contains("'%' || 'x' || '%'"))

        val vertical = SqlFormatter.formatVertical(sql)
        assertTrue("formatVertical：$vertical", vertical.contains("'%' || 'x' || '%'"))

        assertFalse(formatted.contains("| |"))
        assertFalse(clauseLine.contains("| |"))
        assertFalse(vertical.contains("| |"))
    }

    @Test
    fun testConcatBetweenNumbers() {
        // P4：数字后紧跟运算符，scanNumber 不得把运算符第一个字符吃掉
        val out = format("select 1||2 from dual")
        assertTrue("数字邻接拼接被拆开：$out", out.contains("1 || 2"))
    }

    // ------------------------------------------------------------------
    // T4：同类运算符矩阵（同一 class 的两半拆分）
    // ------------------------------------------------------------------

    @Test
    fun testPgCastOperator() {
        // `::` 是运算符，格式化器按「运算符两侧空格」输出（PG 允许 `amount :: numeric`）；
        // 这里钉的是**不被劈成两个冒号**——劈开就是 `amount : : numeric`（非法）。
        val out = format("select amount::numeric from t where id = 1")
        assertTrue("PG 强转被拆开：$out", out.contains("::"))
        assertFalse("PG 强转被劈成两个冒号：$out", out.contains(": :"))
    }

    @Test
    fun testMySqlNullSafeEquals() {
        val out = format("select * from t where a<=>b")
        assertTrue("空安全等值被拆开：$out", out.contains("a <=> b"))
    }

    @Test
    fun testJsonArrows() {
        val out = format("select payload->'k', payload->>'k2' from t")
        assertTrue("-> 被拆开：$out", out.contains("payload -> 'k'"))
        assertTrue("->> 被拆开：$out", out.contains("payload ->> 'k2'"))
    }

    @Test
    fun testLogicalAndAndShifts() {
        val out = format("select * from t where a&&b and c<<2 and d>>1")
        assertTrue("&& 被拆开：$out", out.contains("a && b"))
        assertTrue("<< 被拆开：$out", out.contains("c << 2"))
        assertTrue(">> 被拆开：$out", out.contains("d >> 1"))
    }

    @Test
    fun testRegexAndContainmentOperators() {
        val out = format("select * from t where a ~* b and c @> d and e <@ f and g !~ h")
        assertTrue("~* 被拆开：$out", out.contains("a ~* b"))
        assertTrue("@> 被拆开：$out", out.contains("c @> d"))
        assertTrue("<@ 被拆开：$out", out.contains("e <@ f"))
        assertTrue("!~ 被拆开：$out", out.contains("g !~ h"))
    }

    @Test
    fun testAssignmentAndNamedArgument() {
        val out = format("select f(a => 1) from t")
        assertTrue("=> 被拆开：$out", out.contains("a => 1"))
    }

    // ------------------------------------------------------------------
    // T5：回归守卫——既有原子段与既有比较运算符逐字节不变
    // ------------------------------------------------------------------

    @Test
    fun testExistingComparisonOperatorsUnchanged() {
        val out = format("select * from t where a != 1 and b <> 2 and c <= 3 and d >= 4")
        assertTrue(out.contains("a != 1"))
        assertTrue(out.contains("b <> 2"))
        assertTrue(out.contains("c <= 3"))
        assertTrue(out.contains("d >= 4"))
    }

    @Test
    fun testPlaceholderAtomsUnchanged() {
        val out = format("select * from t where a = #{x} and b = \${y} and c in #ids[0]#")
        assertTrue("#{} 被拆开：$out", out.contains("#{x}"))
        assertTrue("${'$'}{} 被拆开：$out", out.contains("\${y}"))
        assertTrue("iBatis 带下标参数被拆开：$out", out.contains("#ids[0]#"))
    }

    // ------------------------------------------------------------------
    // T6：排除项负向守卫——`#>` 不合并（否则 iBatis 闭合 `#` 配不上）
    // ------------------------------------------------------------------

    @Test
    fun testHashRightAngleStillClosesIbatisPlaceholder() {
        // iBatis 参数 `#id#` 的闭合 `#` 紧跟 `>`（无空格）。若把 `#>` 当多字符运算符合并，
        // 闭合界就消失了（`matchHashClose` 只认单独的 `#` token），参数会被拆成 `# id` 且 `>` 单飞。
        // 这里钉住「不合并 `#>`」时该形态仍然正确：参数整体 + 空格 + 比较符。
        val out = format("select * from t where #id#>0")
        assertTrue("iBatis 闭合 # 被吃掉：$out", out.contains("#id# >"))
        assertFalse("参数被拆开：$out", out.contains("# id"))
    }

    @Test
    fun testLexerEmitsSingleTokenForMultiCharOperators() {
        val cases = listOf("||", "::", ":=", "<=>", "->>", "->", "&&", "<<", ">>", "!~", "~*", "@>", "<@", "=>")
        for (op in cases) {
            val sql = "a${op}b"
            val operators = lexerTokens(sql).filter { it.first == SqlTokenTypes.OPERATOR }
            // 注意断言的是「**一个** token」而不是拼起来的文本：两个相邻的 `|` token 拼起来也是 `||`，
            // 只比文本会假绿（反向验证首轮实证）。
            assertEquals("运算符 $op 未整体分词（operator token：$operators）", 1, operators.size)
            assertEquals("运算符 $op 的分词文本不对", op, operators.single().second)
        }
    }

    /** 词法器分词（剔除空白），断言用。 */
    private fun lexerTokens(sql: String): List<Pair<com.intellij.psi.tree.IElementType, String>> {
        val lexer = SqlLexer()
        lexer.start(sql, 0, sql.length, 0)
        val out = mutableListOf<Pair<com.intellij.psi.tree.IElementType, String>>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != com.intellij.psi.TokenType.WHITE_SPACE) out += type to lexer.tokenText
            lexer.advance()
        }
        return out
    }
}
