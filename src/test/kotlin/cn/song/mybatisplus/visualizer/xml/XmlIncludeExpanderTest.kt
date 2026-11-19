package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * include 展开测试（T-M24-03）：同文件 refid 内联展开、property 静态替换（只作用于 `${}`）、
 * 嵌套 include 与 property 继承/覆盖、refid 动态 `${}`、循环保护、跨命名空间报错降级。
 */
class XmlIncludeExpanderTest {

    private fun renderSql(mapper: String): RenderedXmlSql {
        val parsed = XmlMapperParser().parse(mapper)
        val statement = parsed.statements.single()
        return XmlPreviewModel.from(statement, parsed.errors).renderSql()
    }

    private fun compact(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    @Test
    fun `property静态替换_只作用于dollar`() {
        val result = renderSql("""
            <mapper namespace="n">
              <sql id="cols">${'$'}{alias}.id, ${'$'}{alias}.name</sql>
              <select id="s1">SELECT <include refid="cols"><property name="alias" value="t1"/></include> FROM t</select>
            </mapper>
        """.trimIndent())
        assertEquals("SELECT t1.id, t1.name FROM t", compact(result.sql))
    }

    @Test
    fun `property不替换hash参数`() {
        val result = renderSql("""
            <mapper namespace="n">
              <sql id="w">a = #{v} AND ${'$'}{alias} = 1</sql>
              <select id="s1">SELECT * FROM t WHERE <include refid="w"><property name="alias" value="t1"/></include></select>
            </mapper>
        """.trimIndent())
        assertEquals("SELECT * FROM t WHERE a = ? AND t1 = 1", compact(result.sql))
        assertEquals("v", result.mappings.single().property)
    }

    @Test
    fun `动态refid_按property解析`() {
        val result = renderSql("""
            <mapper namespace="n">
              <sql id="cols">id, name</sql>
              <select id="s1">SELECT <include refid="${'$'}{c}"><property name="c" value="cols"/></include> FROM t</select>
            </mapper>
        """.trimIndent())
        // refid 中的 ${c} 由 property 静态替换后查表展开
        assertEquals("SELECT id, name FROM t", compact(result.sql))
    }

    @Test
    fun `property继承与覆盖`() {
        val result = renderSql("""
            <mapper namespace="n">
              <sql id="inner">${'$'}{alias}.id</sql>
              <sql id="outer"><include refid="inner"><property name="alias" value="t2"/></include></sql>
              <select id="s1">SELECT <include refid="outer"><property name="alias" value="t1"/></include> FROM t</select>
            </mapper>
        """.trimIndent())
        // 内层 include 的 property 覆盖外层同名（对齐 MyBatis：后定义覆盖）
        assertEquals("SELECT t2.id FROM t", compact(result.sql))
    }

    @Test
    fun `include循环_深度保护报错`() {
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <sql id="a"><include refid="a"/>x</sql>
              <select id="s1">SELECT <include refid="a"/> FROM t</select>
            </mapper>
        """.trimIndent())
        val m = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        assertTrue(m.errors.any { it.contains("too deep") || it.contains("cycle") })
        // 循环展开不残留 include（渲染不报 Unknown element）
        assertEquals("SELECT x FROM t", compact(m.renderSql().sql))
    }

    @Test
    fun `跨命名空间refid_报错并留占位`() {
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t <include refid="other.SqlId"/></select>
            </mapper>
        """.trimIndent())
        val m = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        assertTrue(m.errors.any { it.contains("other.SqlId") })
        // T-IncludePlaceholder：缺失片段留注释占位（旧契约是整段移除 → 残句）
        assertTrue(
            "缺失处应留 TODO 占位：${m.renderSql().sql}",
            compact(m.renderSql().sql).contains("TODO: missing <sql id=\"other.SqlId\">"),
        )
    }

    @Test
    fun `同一片段多语句复用_互不污染`() {
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <sql id="cols">id, name</sql>
              <select id="a">SELECT <include refid="cols"/> FROM t1</select>
              <select id="b">SELECT <include refid="cols"/> FROM t2</select>
            </mapper>
        """.trimIndent())
        val a = XmlPreviewModel.from(parsed.statements.first { it.id == "a" }, parsed.errors).renderSql()
        val b = XmlPreviewModel.from(parsed.statements.first { it.id == "b" }, parsed.errors).renderSql()
        assertEquals("SELECT id, name FROM t1", compact(a.sql))
        assertEquals("SELECT id, name FROM t2", compact(b.sql))
    }

    @Test
    fun `include片段中的动态标签正常渲染`() {
        val result = renderSql("""
            <mapper namespace="n">
              <sql id="cond">
                <if test="status != null">AND status = #{status}</if>
              </sql>
              <select id="s1">SELECT * FROM t WHERE 1 = 1 <include refid="cond"/></select>
            </mapper>
        """.trimIndent())
        assertEquals("SELECT * FROM t WHERE 1 = 1 AND status = ?", compact(result.sql))
        assertEquals("status", result.mappings.single().property)
    }

    // ------------------------------------------------------------------
    // T-IC-01 轻量片段收集入口（collectFragments）：只收集不展开
    // ------------------------------------------------------------------

    @Test
    fun `collectFragments_只收集片段不展开语句`() {
        val fragments = XmlMapperParser().collectFragments("""
            <mapper namespace="n">
              <sql id="cols">id, name</sql>
              <sql id="cond"><if test="status != null">AND status = #{status}</if></sql>
              <select id="s1">SELECT <include refid="cols"/> FROM t</select>
            </mapper>
        """.trimIndent())
        // 只有顶层 <sql id> 入表（语句不收集）
        assertEquals(setOf("cols", "cond"), fragments.keys)
        assertEquals("id, name", fragments.getValue("cols"))
        // 片段内部原样保留：动态标签不渲染、include 不展开
        assertEquals("""<if test="status != null">AND status = #{status}</if>""", fragments.getValue("cond"))
    }

    @Test
    fun `collectFragments_保留子标签属性与CDATA`() {
        val fragments = XmlMapperParser().collectFragments("""
            <mapper namespace="n">
              <sql id="c"><![CDATA[SELECT a < 1]]> &amp; <include refid="x"/></sql>
            </mapper>
        """.trimIndent())
        val text = fragments.getValue("c")
        assertTrue("CDATA 原样保留：$text", text.contains("<![CDATA[SELECT a < 1]]>"))
        assertTrue("文本实体按 XML 规则转义：$text", text.contains("&amp;"))
        assertTrue("自闭合子标签原样保留：$text", text.contains("""<include refid="x"/>"""))
    }

    @Test
    fun `collectFragments_根非mapper或非良构_返回空表`() {
        assertTrue(XmlMapperParser().collectFragments("<configuration><sql id=\"a\">x</sql></configuration>").isEmpty())
        assertTrue(XmlMapperParser().collectFragments("<mapper><select id=\"s\">").isEmpty())
    }

    // ------------------------------------------------------------------
    // T-IC-02 跨命名空间片段（CrossNamespaceFragments 注入展开）
    // ------------------------------------------------------------------

    @Test
    fun `跨命名空间refid_注入片段表后展开`() {
        // 目标片段文本取自 collectFragments 产物（序列化 → 重新解析往返）
        val external = XmlMapperParser().collectFragments("""
            <mapper namespace="base">
              <sql id="Cols"><if test="status != null">AND status = #{status}</if></sql>
            </mapper>
        """.trimIndent()).getValue("Cols")
        val parsed = XmlMapperParser().parse(
            """
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE 1 = 1 <include refid="base.Cols"/></select>
            </mapper>
            """.trimIndent(),
            CrossNamespaceFragments(mapOf("base.Cols" to external), emptyMap()),
        )
        val m = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        assertTrue("跨文件片段应展开且无错误：${m.errors}", m.errors.isEmpty())
        assertEquals("SELECT * FROM t WHERE 1 = 1 AND status = ?", compact(m.renderSql().sql))
    }

    @Test
    fun `跨命名空间片段_property穿透`() {
        val parsed = XmlMapperParser().parse(
            """
            <mapper namespace="n">
              <select id="s1">SELECT <include refid="base.AliasCol"><property name="alias" value="t1"/></include> FROM t</select>
            </mapper>
            """.trimIndent(),
            CrossNamespaceFragments(mapOf("base.AliasCol" to "${'$'}{alias}.id, ${'$'}{alias}.name"), emptyMap()),
        )
        val m = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        assertTrue(m.errors.isEmpty())
        assertEquals("SELECT t1.id, t1.name FROM t", compact(m.renderSql().sql))
    }

    @Test
    fun `跨命名空间片段_跨文件环检测`() {
        val parsed = XmlMapperParser().parse(
            """
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t <include refid="b.F2"/></select>
            </mapper>
            """.trimIndent(),
            CrossNamespaceFragments(
                mapOf(
                    "b.F2" to """<include refid="a.F1"/>y""",
                    "a.F1" to """<include refid="b.F2"/>x""",
                ),
                emptyMap(),
            ),
        )
        val m = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        assertTrue("跨文件环应报错：${m.errors}", m.errors.any { it.contains("cycle") || it.contains("too deep") })
        // 环截断后 include 不残留（渲染不报 Unknown element）
        assertTrue(compact(m.renderSql().sql).startsWith("SELECT * FROM t"))
    }

    @Test
    fun `跨命名空间片段_解析失败原因入警示并留占位`() {
        val parsed = XmlMapperParser().parse(
            """
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t <include refid="other.SqlId"/></select>
            </mapper>
            """.trimIndent(),
            CrossNamespaceFragments(emptyMap(), mapOf("other.SqlId" to "未找到 namespace 为 other 的 mapper 文件")),
        )
        val m = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        assertTrue("警示应含具体原因：${m.errors}", m.errors.any { it.contains("未找到 namespace 为 other") })
        // T-IncludePlaceholder：缺失片段留注释占位（旧契约是整段移除 → 残句）
        assertTrue(
            "缺失处应留 TODO 占位：${m.renderSql().sql}",
            compact(m.renderSql().sql).contains("TODO: missing <sql id=\"other.SqlId\">"),
        )
    }

    @Test
    fun `跨命名空间片段_文本非良构_降级不中断`() {
        val parsed = XmlMapperParser().parse(
            """
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t <include refid="base.Bad"/></select>
            </mapper>
            """.trimIndent(),
            CrossNamespaceFragments(mapOf("base.Bad" to """<if test="a">AND a = 1"""), emptyMap()),
        )
        val m = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        assertTrue("片段文本无法解析应报错：${m.errors}", m.errors.any { it.contains("无法解析为 XML") })
        // T-IncludePlaceholder：跨命名空间片段取不到时同样留注释占位
        assertTrue(
            "缺失处应留 TODO 占位：${m.renderSql().sql}",
            compact(m.renderSql().sql).contains("TODO: missing <sql id=\"base.Bad\">"),
        )
    }
}
