package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注解 SQL 解析器测试（T-M25-02）：script/纯文本分派、动态标签渲染、参数名 mock 注入、
 * kind 映射、非良构 script 报错——对齐 MyBatis MapperAnnotationBuilder 构建规则。
 */
class AnnotationSqlParserTest {

    private val parser = AnnotationSqlParser()

    private fun compact(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    @Test
    fun `非script纯文本_参数占位与映射`() {
        val model = parser.parse("SELECT * FROM t WHERE id = #{id}", XmlStatementKind.SELECT, emptyList())
        val rendered = model.renderSql()
        assertEquals("SELECT * FROM t WHERE id = ?", compact(rendered.sql))
        assertEquals(listOf("id"), rendered.mappings.map { it.property })
        assertTrue(model.conditionViews.isEmpty())
    }

    @Test
    fun `非script纯文本_尖括号无需转义`() {
        val model = parser.parse("SELECT * FROM t WHERE age < 18 AND id = #{id}", XmlStatementKind.SELECT, emptyList())
        assertEquals("SELECT * FROM t WHERE age < 18 AND id = ?", compact(model.renderSql().sql))
    }

    @Test
    fun `非script纯文本_dollar根变量占位求值`() {
        // 根变量统一占位 "1"（对齐 ContextMap → _parameter fallback）
        val model = parser.parse("SELECT ${'$'}{cols} FROM t", XmlStatementKind.SELECT, emptyList())
        assertEquals("SELECT 1 FROM t", compact(model.renderSql().sql))
    }

    @Test
    fun `非script纯文本_dollar属性链求值为空时提示`() {
        val model = parser.parse("SELECT ${'$'}{x.y} FROM t", XmlStatementKind.SELECT, emptyList())
        val rendered = model.renderSql()
        // T-MbgExamplePreview R1：预览链占位模式——求值为空写成 {{表达式}}，不再是无声空串
        assertEquals("SELECT {{x.y}} FROM t", compact(rendered.sql))
        assertTrue(rendered.errors.any { it.contains("求值为空") })
    }

    @Test
    fun `script_动态标签全兼容`() {
        val model = parser.parse(
            "<script>SELECT * FROM t" +
                "<where><if test=\"a != null\">AND a = #{a}</if></where>" +
                "<if test=\"ids != null\">AND id IN <foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach></if>" +
                "</script>",
            XmlStatementKind.SELECT,
            emptyList(),
        )
        val rendered = model.renderSql()
        assertEquals(
            "SELECT * FROM t WHERE a = ? AND id IN ( ? , ? )",
            compact(rendered.sql),
        )
        assertEquals(2, model.conditionViews.size)
        assertEquals(listOf("ids"), model.forEachViews.map { it.collectionExpr })
    }

    @Test
    fun `script_条件开关关闭分支`() {
        val model = parser.parse(
            "<script>SELECT * FROM t<if test=\"a != null\"> WHERE a = 1</if></script>",
            XmlStatementKind.SELECT,
            emptyList(),
        )
        val state = model.defaultState().copy(enabledIds = emptySet())
        assertEquals("SELECT * FROM t", compact(model.renderSql(state).sql))
    }

    @Test
    fun `参数名mock注入_param1可求值`() {
        // MyBatis 多参数 Map 键：@Param 名/实际名 + param1..N
        val model = parser.parse(
            "SELECT * FROM t WHERE a = ${'$'}{param1} AND b = ${'$'}{id}",
            XmlStatementKind.SELECT,
            listOf("id", "param1"),
        )
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = 1", compact(model.renderSql().sql))
    }

    @Test
    fun `kind透传`() {
        val model = parser.parse("UPDATE t SET a = 1", XmlStatementKind.UPDATE, emptyList())
        assertEquals(XmlStatementKind.UPDATE, model.kind)
    }

    @Test
    fun `script非良构报错`() {
        assertThrows(XmlParseException::class.java) {
            parser.parse("<script><if test=\"a\">x</script>", XmlStatementKind.SELECT, emptyList())
        }
    }

    @Test
    fun `script内include报未知元素_与MyBatis一致`() {
        assertThrows(XmlScriptException::class.java) {
            parser.parse("<script>SELECT * FROM t <include refid=\"cols\"/></script>", XmlStatementKind.SELECT, emptyList())
        }
    }

    // ------------------------------------------------------------------
    // T-XmlDtdHardening T-XDH-05：注解 <script> 路径与 XML mapper 同规（共用 parseDocument 入口）
    // ------------------------------------------------------------------

    @Test
    fun `script内DTD指数膨胀被拒绝_与mapper同规`() {
        // 注解链与 mapper 链共用 XmlMapperParser.parseDocument → 同一膨胀预扫。`<script>` 之后的
        // DOCTYPE 位置本身非法（DOCTYPE 只能在序言），但预扫先于 DOM 解析，故拒绝口径是膨胀判据
        // 而非「DOCTYPE 位置非法」——本用例钉住「共享入口」这一事实。
        val script = buildString {
            append("<script><!DOCTYPE script [\n<!ENTITY e0 \"xxxxxxxxxx\">\n")
            for (level in 1..4) {
                append("<!ENTITY e").append(level).append(" \"")
                repeat(10) { append("&e").append(level - 1).append(';') }
                append("\">\n")
            }
            append("]><select id=\"s1\">&e4;</select></script>")
        }
        val e = assertThrows(XmlParseException::class.java) {
            parser.parse(script, XmlStatementKind.SELECT, emptyList())
        }
        assertTrue("拒绝原因须点明实体展开，实际：${e.message}", e.message.orEmpty().contains("实体展开"))
    }

    @Test
    fun `script内无法夹带DTD声明_序言契约拒绝`() {
        // 注解 SQL 文本恒以 `<script>` 开头（MyBatis XMLLanguageDriver 的包装形态），DOCTYPE 只能在
        // 序言出现 → 注解路径无法夹带内联子集/实体定义；本用例钉住「夹带即拒绝」
        val script =
            "<script><!DOCTYPE script [<!ENTITY status \"ACTIVE\">]><select id=\"s1\">SELECT '&status;'</select></script>"
        assertThrows(XmlParseException::class.java) {
            parser.parse(script, XmlStatementKind.SELECT, emptyList())
        }
    }
}
