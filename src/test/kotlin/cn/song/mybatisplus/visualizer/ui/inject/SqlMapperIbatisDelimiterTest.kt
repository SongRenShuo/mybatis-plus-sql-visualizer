package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-Ibatis2Support 券 D：iBatis 定界符词法（纯逻辑，无平台 fixture）。
 *
 * 判据（券 D 验收「正向」与「反向」两侧的机器化形态）：
 *  - **四态**：`#name#` / `#name:TYPE#` / `#name:TYPE:nullValue#` / `$name$` 各出**一个**参数 token
 *    （`#…` → [SqlMapperTT.PARAM_HASH]、`$…$` → [SqlMapperTT.PARAM_DOLLAR]）；
 *  - **转义字面量**：`##` / `$$` 出 [SqlMapperTT.IBATIS_ESCAPE]（不映射颜色＝普通文本），且
 *    **不产**参数 token；`##foo#` 不得错配出 `#foo#` 参数；
 *  - **配对闭合为准**：未闭合 / 形态非法的 `#`/`$` 退回单字符 [SqlMapperTT.OPERATOR]，
 *    **不吞后续文本**（后续 SQL 关键字照常着色，token 序列覆盖到缓冲末尾）；
 *  - **回归面**：`#{…}`/`${…}`（MyBatis 2 字符定界符）与 `\#{` 转义语义逐字节不变。
 *
 * 反向验证（券 D ②）：摘掉 `scanIbatisDelimiter` 里的 `##`/`$$` 转义分支 → [test转义_井号井号_字面量]
 * 与 [test转义_阻断错配_井号井号后跟属性井号] 必红。
 */
class SqlMapperIbatisDelimiterTest {

    private fun tokens(sql: String): List<Pair<IElementType, String>> {
        val lexer = SqlMapperHighlightingLexer()
        lexer.start(sql)
        val out = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            out += lexer.tokenType!! to lexer.bufferSequence.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            lexer.advance()
        }
        return out
    }

    private fun texts(sql: String, type: IElementType): List<String> =
        tokens(sql).filter { it.first == type }.map { it.second }

    /** 整趟扫完后的末 token 终点（用于「不吞文本 / 无洞」判定）。 */
    private fun endAfterFullScan(sql: String): Int {
        val lexer = SqlMapperHighlightingLexer()
        lexer.start(sql)
        while (lexer.tokenType != null) lexer.advance()
        return lexer.tokenEnd
    }

    // ------------------------------------------------------------------
    // 四态：`#name#` / `#name:TYPE#` / `#name:TYPE:nullValue#` / `$name$`
    // ------------------------------------------------------------------

    @Test
    fun test四态_单段_井号属性名() {
        val sql = "SELECT * FROM t WHERE id = #id#"
        assertEquals("`#id#` 应恰出一个 PARAM_HASH token", listOf("#id#"), texts(sql, SqlMapperTT.PARAM_HASH))
        assertTrue("iBatis 参数不得产 IBATIS_ESCAPE", texts(sql, SqlMapperTT.IBATIS_ESCAPE).isEmpty())
        assertFalse("`#` 不得再落运算符（`=` 才是运算符）：${texts(sql, SqlMapperTT.OPERATOR)}", "#" in texts(sql, SqlMapperTT.OPERATOR))
    }

    @Test
    fun test四态_两段_第二段是jdbcType() {
        val sql = "WHERE id = #id:VARCHAR#"
        assertEquals(listOf("#id:VARCHAR#"), texts(sql, SqlMapperTT.PARAM_HASH))
    }

    @Test
    fun test四态_三段_第二段jdbcType第三段nullValue() {
        // 券 D 口径：两段式第二段恒为 jdbcType（`#name:nullValue#` 里的 `nullValue` 是 jdbcType）
        val sql = "WHERE id = #id:VARCHAR:0#"
        assertEquals(listOf("#id:VARCHAR:0#"), texts(sql, SqlMapperTT.PARAM_HASH))
    }

    @Test
    fun test四态_美元号_字面替换() {
        val sql = "SELECT * FROM \$tableName\$ WHERE 1=1"
        assertEquals(listOf("\$tableName\$"), texts(sql, SqlMapperTT.PARAM_DOLLAR))
        assertFalse("`$` 不得再落运算符：${texts(sql, SqlMapperTT.OPERATOR)}", "$" in texts(sql, SqlMapperTT.OPERATOR))
    }

    @Test
    fun test四态_iterate下标与属性链() {
        // iBatis `<iterate>` 形态：`#ids[]#`、`#list[].name#`（`[]` 是下标形态，属内容字符）
        assertEquals(listOf("#ids[]#"), texts("IN (#ids[]#)", SqlMapperTT.PARAM_HASH))
        assertEquals(listOf("#list[].name#"), texts("AND x = #list[].name#", SqlMapperTT.PARAM_HASH))
    }

    @Test
    fun test四态_同句多参数_各自独立() {
        val sql = "SELECT * FROM t WHERE id = #id# AND nm = #nm:VARCHAR# AND tb = \$tb\$"
        assertEquals(listOf("#id#", "#nm:VARCHAR#"), texts(sql, SqlMapperTT.PARAM_HASH))
        assertEquals(listOf("\$tb\$"), texts(sql, SqlMapperTT.PARAM_DOLLAR))
    }

    // ------------------------------------------------------------------
    // 转义字面量 `##` / `$$`（券 D 反向验证 ② 的被摘面）
    // ------------------------------------------------------------------

    @Test
    fun test转义_井号井号_字面量() {
        val sql = "SELECT a ## b"
        assertEquals("`##` 应是转义字面量 token", listOf("##"), texts(sql, SqlMapperTT.IBATIS_ESCAPE))
        assertTrue("`##` 不得读成参数", texts(sql, SqlMapperTT.PARAM_HASH).isEmpty())
        assertTrue("`##` 不得落运算符（否则会读成两个 `#`）", texts(sql, SqlMapperTT.OPERATOR).isEmpty())
    }

    @Test
    fun test转义_美元美元_字面量() {
        val sql = "SELECT '\$\$' , a \$\$ b"
        assertEquals(listOf("\$\$"), texts(sql, SqlMapperTT.IBATIS_ESCAPE))
        assertTrue(texts(sql, SqlMapperTT.PARAM_DOLLAR).isEmpty())
    }

    @Test
    fun test转义_阻断错配_井号井号后跟属性井号() {
        // 关键判别位：`##foo#` = 「转义 `#`」+「`foo#`」；**不得**读成「空参数 + `#foo#` 参数」。
        // 摘掉转义分支时此处必红（第二个 `#` 会开出 `#foo#` 参数）。
        val sql = "SELECT ##foo#"
        assertEquals(listOf("##"), texts(sql, SqlMapperTT.IBATIS_ESCAPE))
        assertTrue("`##foo#` 不得错配出参数：${texts(sql, SqlMapperTT.PARAM_HASH)}", texts(sql, SqlMapperTT.PARAM_HASH).isEmpty())
    }

    // ------------------------------------------------------------------
    // 配对闭合为准：未闭合 / 形态非法 → 退回单字符运算符，不吞后续文本
    // ------------------------------------------------------------------

    @Test
    fun test未闭合_井号_退回运算符且后续关键字照常着色() {
        val sql = "SELECT a # b FROM t"
        assertTrue("未闭合 `#` 不得产 PARAM_HASH", texts(sql, SqlMapperTT.PARAM_HASH).isEmpty())
        assertEquals("落单 `#` 按单字符运算符", listOf("#"), texts(sql, SqlMapperTT.OPERATOR))
        assertEquals("后续关键字照常着色（未被吞进参数态）", 2, texts(sql, SqlMapperTT.KEYWORD).count { it.equals("SELECT", true) || it.equals("FROM", true) })
        assertEquals("token 序列覆盖到缓冲末尾（无洞）", sql.length, endAfterFullScan(sql))
    }

    @Test
    fun test形态非法_含空白的两个井号_不成参数() {
        // `# a #` 内容含空白 → 不是属性名 → 两个 `#` 各按运算符（不吞中间文本）
        val sql = "SELECT a # b # c"
        assertTrue(texts(sql, SqlMapperTT.PARAM_HASH).isEmpty())
        assertEquals(listOf("#", "#"), texts(sql, SqlMapperTT.OPERATOR))
    }

    @Test
    fun test未闭合_美元号_不吞后续文本() {
        val sql = "SELECT a \$ b FROM t WHERE x = 1"
        assertTrue(texts(sql, SqlMapperTT.PARAM_DOLLAR).isEmpty())
        assertTrue("后续 FROM/WHERE 关键字仍着色", texts(sql, SqlMapperTT.KEYWORD).any { it.equals("FROM", true) })
        assertEquals(sql.length, endAfterFullScan(sql))
    }

    @Test
    fun test跨行_不配对() {
        // 换行（空白）非内容字符 → 跨行的两个 `#` 不互配（防在长 SQL 里误配大段文本）
        val sql = "SELECT a #\n and b # c"
        assertTrue("跨行不得配对成参数", texts(sql, SqlMapperTT.PARAM_HASH).isEmpty())
    }

    @Test
    fun test井号后紧跟花括号_仍走MyBatis分支() {
        // 优先级：`#{` 是 MyBatis 分支（2 字符定界符），不被 iBatis 单字符配对逻辑抢走
        val all = tokens("#{id}")
        assertEquals(
            listOf(SqlMapperTT.PARAM_HASH, SqlMapperTT.IDENTIFIER, SqlMapperTT.PARAM_HASH),
            all.map { it.first },
        )
        assertEquals(listOf("#{", "}"), texts("#{id}", SqlMapperTT.PARAM_HASH))
        assertTrue(texts("#{id}", SqlMapperTT.IBATIS_ESCAPE).isEmpty())
    }

    @Test
    fun test美元号后紧跟花括号_仍走MyBatis分支() {
        assertEquals(listOf("\${", "}"), texts("\${n}", SqlMapperTT.PARAM_DOLLAR))
        assertTrue("`\${n}` 不得被 iBatis 分支读成 `\$...\$`", texts("\${n}", SqlMapperTT.IBATIS_ESCAPE).isEmpty())
    }

    @Test
    fun testMyBatis回归_反斜杠井号转义语义不变() {
        // `\#{abc}` 整体按普通文本：`abc` 是标识符，两侧花括号是普通 BRACE（非 PARAM_HASH）
        assertTrue(texts("\\#{escaped}", SqlMapperTT.PARAM_HASH).isEmpty())
        assertEquals(listOf("abc"), texts("\\#{abc}", SqlMapperTT.IDENTIFIER))
        assertEquals(listOf("{", "}"), texts("\\#{abc}", SqlMapperTT.BRACE))
    }

    @Test
    fun test组合_iBatis语句全场景() {
        val sql = "SELECT * FROM t WHERE id = #id:VARCHAR# AND nm = \$nm\$ AND a = ## AND b = #ids[]#"
        assertEquals(listOf("#id:VARCHAR#", "#ids[]#"), texts(sql, SqlMapperTT.PARAM_HASH))
        assertEquals(listOf("\$nm\$"), texts(sql, SqlMapperTT.PARAM_DOLLAR))
        assertEquals(listOf("##"), texts(sql, SqlMapperTT.IBATIS_ESCAPE))
    }
}
