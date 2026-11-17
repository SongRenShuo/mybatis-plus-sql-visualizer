package cn.song.mybatisplus.visualizer.xml.ibatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `<sqlMap>` 解析器单测（T-Ibatis2Support 券 B）——结构级失败抛异常、语句级问题降级为 warning。
 */
class SqlMapParserTest {

    private val parser = SqlMapParser()

    @Test
    fun `根元素不是 sqlMap 时抛结构级异常`() {
        val error = runCatching { parser.parse("<mapper namespace=\"x\"><select id=\"a\">SELECT 1</select></mapper>") }
            .exceptionOrNull()
        assertNotNull("非 sqlMap 根必须抛 IbatisParseException", error)
        assertTrue(error is IbatisParseException)
    }

    @Test
    fun `XML 语法错抛结构级异常`() {
        val error = runCatching { parser.parse("<sqlMap><select id=\"a\">SELECT 1</sqlMap>") }.exceptionOrNull()
        assertTrue(error is IbatisParseException)
    }

    @Test
    fun `namespace 可选且语句标签六种都能解析`() {
        val text = """
            <sqlMap>
              <select id="s">SELECT 1</select>
              <insert id="i">INSERT INTO T VALUES (1)</insert>
              <update id="u">UPDATE T SET A = 1</update>
              <delete id="d">DELETE FROM T</delete>
              <statement id="st">SELECT 2</statement>
              <procedure id="p">CALL F()</procedure>
            </sqlMap>
        """.trimIndent()
        val parsed = parser.parse(text)
        assertNull(parsed.namespace)
        assertEquals(
            listOf("s", "i", "u", "d", "st", "p"),
            parsed.statements.map { it.id },
        )
        assertEquals(IbatisStatementKind.PROCEDURE, parsed.statementById("p")!!.kind)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `parameterMap 与 parameter 声明被收下`() {
        val text = """
            <sqlMap namespace="demo">
              <parameterMap id="pm" class="java.util.HashMap">
                <parameter property="a" jdbcType="VARCHAR" nullValue="N/A" mode="IN" javaType="string"/>
              </parameterMap>
              <select id="s" parameterMap="pm">SELECT ?</select>
            </sqlMap>
        """.trimIndent()
        val parsed = parser.parse(text)
        val parameterMap = parsed.parameterMaps.single()
        assertEquals("pm", parameterMap.id)
        assertEquals("java.util.HashMap", parameterMap.className)
        val def = parameterMap.parameters.single()
        assertEquals("a", def.property)
        assertEquals("VARCHAR", def.jdbcType)
        assertEquals("N/A", def.nullValue)
        assertEquals("IN", def.mode)
        assertEquals("string", def.javaType)

        val statement = parsed.statementById("s")!!
        assertEquals("pm", statement.parameterMapRef)
        assertTrue(statement.hasParameterMapDeclaration)
        assertNotNull("应按 applyNamespace 解析到声明", statement.parameterMap)
        assertTrue(
            "非动态语句声明 parameterMap 必须给出配置错误告警",
            statement.warnings.any { it.contains("不转 ?") },
        )
    }

    @Test
    fun `参数图找不到时给出告警但仍按声明处理`() {
        val parsed = parser.parse("""<sqlMap><select id="s" parameterMap="missing">SELECT ?</select></sqlMap>""")
        val statement = parsed.statementById("s")!!
        assertTrue(statement.warnings.any { it.contains("找不到对应 <parameterMap>") })
        assertTrue(statement.hasParameterMapDeclaration)
        assertNull(statement.parameterMap)
    }

    @Test
    fun `include 先按原样 id 找再按 namespace 回落`() {
        val text = """
            <sqlMap namespace="demo">
              <sql id="cols">A, B</sql>
              <sql id="demo.tbl">T_X</sql>
              <select id="s">SELECT <include refid="cols"/> FROM <include refid="tbl"/></select>
              <select id="bad">SELECT <include refid="nope"/> FROM T</select>
            </sqlMap>
        """.trimIndent()
        val parsed = parser.parse(text)
        assertEquals(listOf("cols", "demo.tbl"), parsed.sqlFragmentIds)
        val statement = parsed.statementById("s")!!
        assertEquals(listOf("cols", "demo.tbl"), statement.usedSqlFragmentIds)
        assertTrue(parsed.statementById("bad")!!.warnings.any { it.contains("找不到 <include") })
    }

    @Test
    fun `未知标签降级为原文保留并告警且不抛穿`() {
        val text = """<sqlMap><select id="s">SELECT A<weird property="x">B = #b#</weird>FROM T</select></sqlMap>"""
        val parsed = parser.parse(text)
        val statement = parsed.statementById("s")!!
        assertTrue(
            "必须收集「未识别的标签」告警：${statement.warnings}",
            statement.warnings.any { it.contains("未识别的标签") },
        )
        assertFalse("未知标签不应把语句判成动态（引擎也不认它）", statement.isDynamic)
        val texts = statement.body.filterIsInstance<IbatisTextNode>().map { it.rawText }
        assertTrue("降级后原文应在场：$texts", texts.any { it.contains("B = ") })
    }

    @Test
    fun `selectKey 只认 insert 并按位置或 type 推断顺序`() {
        val text = """
            <sqlMap>
              <insert id="pre"><selectKey keyProperty="k" type="pre">SELECT 1</selectKey>INSERT INTO T (A) VALUES (1)</insert>
              <insert id="inferredPost">INSERT INTO T (A) VALUES (1)<selectKey keyProperty="k">SELECT 1</selectKey></insert>
              <insert id="explicitPost"><selectKey keyProperty="k" type="post">SELECT 1</selectKey>INSERT INTO T (A) VALUES (1)</insert>
              <select id="wrong"><selectKey keyProperty="k">SELECT 1</selectKey>SELECT 1</select>
            </sqlMap>
        """.trimIndent()
        val parsed = parser.parse(text)
        val pre = parsed.statementById("pre")!!
        assertEquals("pre-SelectKey", pre.selectKeys.single().id)
        assertFalse("type=\"pre\" 显式压过位置", pre.selectKeys.single().runAfterSql)
        assertTrue(
            "selectKey 内容绝不能落进宿主 insert 主体",
            pre.body.filterIsInstance<IbatisTextNode>().none { it.rawText.contains("SELECT 1") },
        )
        assertTrue("无 type 时由位置推断为 post", parsed.statementById("inferredPost")!!.selectKeys.single().runAfterSql)
        assertTrue(parsed.statementById("explicitPost")!!.selectKeys.single().runAfterSql)
        assertTrue(
            "selectKey 挂在非 insert 上要有告警",
            parsed.statementById("wrong")!!.warnings.any { it.contains("只允许挂在 <insert>") },
        )
    }

    @Test
    fun `属性占位符按原文保留并告警`() {
        val text = """<sqlMap><select id="s">SELECT * FROM ${'$'}{tableName}</select></sqlMap>"""
        val statement = parser.parse(text).statementById("s")!!
        assertTrue("必须给告警（已知偏差，见 SqlMapSqlRenderer 类注释 A）", statement.warnings.any { it.contains("属性占位") })
        assertTrue(
            "本链不做 \${...} 全局属性替换：${statement.body}",
            statement.body.filterIsInstance<IbatisTextNode>().any { it.rawText.contains("${'$'}{tableName}") },
        )
    }

    @Test
    fun `insert 上出现 resultClass 会被提示`() {
        val parsed = parser.parse("""<sqlMap><insert id="i" resultClass="java.lang.Integer">INSERT INTO T (A) VALUES (1)</insert></sqlMap>""")
        assertTrue(parsed.statementById("i")!!.warnings.any { it.contains("不接受 resultMap/resultClass") })
    }
}
