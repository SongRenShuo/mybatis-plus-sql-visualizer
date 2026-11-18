package cn.song.mybatisplus.visualizer.ui.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 独立预览工作台双源解析器测试（T-SqlPreviewStandalone D-1 / D-1b）。
 *
 * 覆盖计划 §五 D-1b 夹具清单（P-8 语料）：完整 mapper / 裸语句块包根 / 实体转义 /
 * markdown 围栏 / 字面量内占位符 / `${}` raw / 非 SQL XML 降级（A-7）。
 * XML 路径走 project=null 纯文本链（与 MCP xmlSnippet 通道同源），纯 JUnit 无平台依赖。
 */
class StandaloneSqlSourceResolverTest {

    // ---------- XML 路径 ----------

    @Test
    fun `完整 mapper 直接识别`() {
        val text = """
            <mapper namespace="n">
              <select id="q1" resultType="map">SELECT * FROM t WHERE a = #{aa}</select>
            </mapper>
        """.trimIndent()
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.XML_STATEMENT, resolved!!.kind)
        assertEquals("q1", resolved.statementId)
        assertEquals(1, resolved.statementCount)
        assertNotNull(resolved.result.xmlModel)
        assertEquals(listOf("aa"), resolved.result.unresolvedParams)
        assertTrue(resolved.result.sql.contains("FROM t"))
    }

    @Test
    fun `裸语句块自动包 mapper 根`() {
        val text = "<select id=\"q2\">SELECT * FROM t WHERE b = #{bb}</select>"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.XML_STATEMENT, resolved!!.kind)
        assertNotNull(resolved.result.xmlModel)
    }

    @Test
    fun `多语句片段取第一个并如实报数`() {
        val text = """
            <mapper>
              <select id="first">SELECT 1</select>
              <update id="second">UPDATE t SET a = #{x}</update>
            </mapper>
        """.trimIndent()
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals("first", resolved!!.statementId)
        assertEquals(2, resolved.statementCount)
        assertNotNull(resolved.result.xmlModel)
    }

    @Test
    fun `同文 sql 片段 include 可展开`() {
        val text = """
            <sql id="cols">id, name</sql>
            <select id="q3">SELECT <include refid="cols"/> FROM t WHERE c = #{cc}</select>
        """.trimIndent()
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.XML_STATEMENT, resolved!!.kind)
        assertNotNull(resolved.result.xmlModel)
        assertTrue("include 片段应展开进渲染 SQL：${resolved.result.sql}", resolved.result.sql.contains("id, name"))
    }

    @Test
    fun `实体转义的整段 mapper 识别（J-2）`() {
        val text =
            "&lt;mapper&gt;&lt;select id=&quot;q4&quot;&gt;SELECT * FROM t WHERE a = #{aa}&lt;/select&gt;&lt;/mapper&gt;"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.XML_STATEMENT, resolved!!.kind)
        assertEquals("q4", resolved.statementId)
        assertNotNull(resolved.result.xmlModel)
    }

    @Test
    fun `合法 XML 里的实体比较符不被破坏（先试原文）`() {
        // `a &lt;= ?` 是合法 XML（DOM 解析还原为 `<=`）；不得因实体解码把 `<` 裸化破坏 XML 良构
        val text = """
            <mapper>
              <select id="q5">SELECT * FROM t WHERE a &lt;= 5 AND b = #{bb}</select>
            </mapper>
        """.trimIndent()
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertNotNull(resolved!!.result.xmlModel)
        assertTrue("实体应经 DOM 还原为比较符：${resolved.result.sql}", resolved.result.sql.contains("<="))
    }

    @Test
    fun `markdown 围栏剥离后识别 mapper（P-8）`() {
        val text = "```xml\n<select id=\"q6\">SELECT 1</select>\n```"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.XML_STATEMENT, resolved!!.kind)
    }

    @Test
    fun `混入说明文字时按语句块兜底包根`() {
        val text = "这是我的 mapper：\n<select id=\"q7\">SELECT 1</select>\n请查收。"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.XML_STATEMENT, resolved!!.kind)
        assertNotNull(resolved.result.xmlModel)
    }

    @Test
    fun `良构性破坏的 XML 返回带 warning 的结果不抛异常`() {
        val text = "<select id=\"q8\">SELECT 1 FROM t"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        // 语句块无闭合标签 → 检测门仍在，包根后 DOM 解析失败 → warning 如实呈现
        if (resolved != null) {
            assertEquals(StandaloneSqlSourceResolver.Kind.XML_STATEMENT, resolved.kind)
            assertNotNull(resolved.result.warning)
        }
        // 也允许降级为 null（调用方给引导文案），两条路都合法
    }

    // ---------- 纯 SQL 路径 ----------

    @Test
    fun `问号占位符按序编号`() {
        val text = "SELECT * FROM t WHERE a = ? AND b = ? AND c = ?"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.PLAIN_SQL, resolved!!.kind)
        assertEquals(3, resolved.paramCount)
        assertEquals(listOf("param1", "param2", "param3"), resolved.result.unresolvedParams)
        assertEquals(text, resolved.result.sql)
        assertTrue(resolved.result.paramTable!!.contains("param1=?"))
    }

    @Test
    fun `命名参数替换为问号并记录名`() {
        val text = "SELECT * FROM t WHERE name = #{userName} AND age = #{age}"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.PLAIN_SQL, resolved!!.kind)
        assertEquals(2, resolved.paramCount)
        assertEquals(listOf("userName", "age"), resolved.result.unresolvedParams)
        assertEquals("SELECT * FROM t WHERE name = ? AND age = ?", resolved.result.sql)
    }

    @Test
    fun `同名命名参数共用一个填值行（MyBatis 语义）`() {
        val text = "SELECT * FROM t WHERE a = #{x} AND b = #{x}"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertEquals(2, resolved!!.paramCount)
        assertEquals(listOf("x", "x"), resolved.result.unresolvedParams)
    }

    @Test
    fun `问号与命名参数混排按位点排序`() {
        val text = "INSERT INTO t (a, b, c) VALUES (?, #{name}, ?)"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertEquals(StandaloneSqlSourceResolver.Kind.PLAIN_SQL, resolved!!.kind)
        assertEquals(listOf("param1", "name", "param2"), resolved.result.unresolvedParams)
        assertEquals("INSERT INTO t (a, b, c) VALUES (?, ?, ?)", resolved.result.sql)
    }

    @Test
    fun `字符串字面量内的问号不当占位符`() {
        val text = "SELECT 'a?b' FROM t WHERE x = ?"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertEquals(1, resolved!!.paramCount)
        assertEquals("param1", resolved.result.unresolvedParams.single())
    }

    @Test
    fun `字符串字面量内的命名参数不参与转换`() {
        val text = "SELECT * FROM t WHERE note = 'keep #{x} literal' AND a = ?"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertEquals(1, resolved!!.paramCount)
        assertTrue("字面量应原样保留：${resolved.result.sql}", resolved.result.sql.contains("'keep #{x} literal'"))
    }

    @Test
    fun `注释内的占位符不计（string-aware 口径）`() {
        val text = "-- 数量? --\nSELECT * FROM t WHERE a = ? /* b? */"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertEquals(1, resolved!!.paramCount)
    }

    @Test
    fun `dollar raw 段置执行闸门标记且不参与填值`() {
        val text = "SELECT * FROM t ORDER BY ${'$'}{orderCol} WHERE a = #{aa}"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertEquals(StandaloneSqlSourceResolver.Kind.PLAIN_SQL, resolved!!.kind)
        assertTrue(resolved.result.hasDollarRawSegments)
        assertEquals(listOf("aa"), resolved.result.unresolvedParams)
    }

    @Test
    fun `字符串字面量内的 dollar raw 不算 raw 段`() {
        val text = "SELECT * FROM t WHERE a = '\${'$'}{literal}'"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertFalse(resolved!!.result.hasDollarRawSegments)
    }

    @Test
    fun `markdown 围栏剥离后按纯 SQL 解析`() {
        val text = "```sql\nSELECT * FROM t WHERE a = ?\n```"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertEquals(StandaloneSqlSourceResolver.Kind.PLAIN_SQL, resolved!!.kind)
        assertEquals(1, resolved.paramCount)
    }

    @Test
    fun `零占位符纯 SQL 也能出结果`() {
        val text = "SELECT * FROM t"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertEquals(StandaloneSqlSourceResolver.Kind.PLAIN_SQL, resolved!!.kind)
        assertEquals(0, resolved.paramCount)
        assertTrue(resolved.result.unresolvedParams.isEmpty())
        assertNull(resolved.result.paramTable)
    }

    // ---------- 降级与候选判定 ----------

    @Test
    fun `非 SQL 的 XML 不硬塞解析（A-7）`() {
        val text = "<dependency><groupId>org.springframework</groupId></dependency>"
        assertNull(StandaloneSqlSourceResolver.resolve(text))
    }

    @Test
    fun `空白与超长文本返回 null`() {
        assertNull(StandaloneSqlSourceResolver.resolve("   "))
        assertNull(StandaloneSqlSourceResolver.resolve("x".repeat(StandaloneSqlSourceResolver.MAX_TEXT_LENGTH + 1)))
    }

    @Test
    fun `剪贴板候选判定`() {
        assertTrue(StandaloneSqlSourceResolver.isClipboardCandidate("SELECT * FROM t WHERE a = ?"))
        assertTrue(StandaloneSqlSourceResolver.isClipboardCandidate("<select id=\"q\">SELECT 1</select>"))
        assertTrue(StandaloneSqlSourceResolver.isClipboardCandidate("WITH x AS (SELECT 1) SELECT * FROM x"))
        assertFalse(StandaloneSqlSourceResolver.isClipboardCandidate("<dependency/>"))
        assertFalse(StandaloneSqlSourceResolver.isClipboardCandidate("随便一句话"))
        assertFalse(StandaloneSqlSourceResolver.isClipboardCandidate("   "))
        assertFalse(StandaloneSqlSourceResolver.isClipboardCandidate(null))
    }
}
