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

    // ---------- iBatis 2 识别与双链择优（T-StandaloneIbatisRecognize） ----------

    /** 用户 2026-11-18 报障原样片段（iBatis 2 SQL Map）。 */
    private val ibatisFragment = """
        <select id="selectByCondition" parameterClass="User" resultMap="userResult">
          SELECT <include refid="userColumns"/>
          FROM T_USER
          <dynamic prepend="WHERE">
            <isNotNull   prepend="AND " property="id">ID = #id#</isNotNull>
            <isNotEmpty  prepend="AND " property="username">USER_NAME LIKE '%${'$'}username${'$'}%'</isNotEmpty>
            <isEqual     prepend="AND " property="status" compareValue="1">STATUS = #status#</isEqual>
            <isGreaterThan prepend="AND " property="age" compareValue="18">AGE &gt; #age#</isGreaterThan>
          </dynamic>
          ORDER BY ID
        </select>
    """.trimIndent()

    @Test
    fun `用户报障的 iBatis 片段走 iBatis 链而非报 Unknown element`() {
        val resolved = StandaloneSqlSourceResolver.resolve(ibatisFragment)
        assertNotNull(resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.XML_STATEMENT, resolved!!.kind)
        assertEquals(XmlFlavorDetector.Flavor.IBATIS, resolved.flavor)
        assertNull("iBatis 链应还原成功，不得带失败事实：${resolved.failure}", resolved.failure)
        assertNotNull("iBatis 链应产出条件/参数模型", resolved.result.ibatisParamModel)
        assertEquals("selectByCondition", resolved.statementId)
        val warning = resolved.result.warning.orEmpty()
        assertFalse("不得再出现 MyBatis 的 Unknown element 假错误：$warning", warning.contains("Unknown element"))
    }

    @Test
    fun `iBatis 四个条件行全部建行`() {
        val resolved = StandaloneSqlSourceResolver.resolve(ibatisFragment)
        val model = resolved!!.result.ibatisParamModel!!
        assertEquals("isNotNull/isNotEmpty/isEqual/isGreaterThan 各一行", 4, model.conditionRows.size)
    }

    @Test
    fun `裸 statement 标签靠双链回退被 iBatis 接管`() {
        // `<statement>` 是 iBatis 专有语句标签：MyBatis 链不收集（无 select/insert/update/delete），
        // 判定器也拿不到风味信号（同分判 MyBatis）→ 首选链失败 → 回退 iBatis 成功。
        val text = """<statement id="q9">SELECT * FROM T_USER WHERE ID = #id#</statement>"""
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull("statement 标签必须被检测门纳入", resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.XML_STATEMENT, resolved!!.kind)
        assertNull("回退成功不得留失败事实", resolved.failure)
        assertEquals(XmlFlavorDetector.Flavor.IBATIS, resolved.flavor)
    }

    @Test
    fun `MyBatis 片段仍判 MyBatis（回归）`() {
        val text = """
            <mapper namespace="n">
              <select id="q10">SELECT * FROM t WHERE a = #{aa}</select>
            </mapper>
        """.trimIndent()
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertEquals(XmlFlavorDetector.Flavor.MYBATIS, resolved!!.flavor)
        assertNull(resolved.failure)
        assertNotNull(resolved.result.xmlModel)
    }

    @Test
    fun `缺失 include 片段渲染成 TODO 占位注释而不是残句`() {
        // T-IncludePlaceholder C 券（iBatis 侧）：旧行为整段丢弃 → `SELECT` 后直接接 `FROM`（残句）；
        // 现在留注释占位，结构完整且能看出缺哪一段。`parameterClass` 保证走 iBatis 链——
        // MyBatis 侧同形态被既有用例锁定了「缺失即抹掉」的 SQL 文本契约，本轮不动（见 CHANGELOG 遗留）。
        val text = """<select id="q13" parameterClass="User">SELECT <include refid="userColumns"/> FROM T_USER</select>"""
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(XmlFlavorDetector.Flavor.IBATIS, resolved!!.flavor)
        assertTrue(
            "缺失处应留 TODO 注释：${resolved.result.sql}",
            resolved.result.sql.contains("TODO: missing <sql id=\"userColumns\">"),
        )
    }

    @Test
    fun `MyBatis 侧缺失 include 同样留 TODO 占位（两条链同形）`() {
        // T-IncludePlaceholder 二轮：MyBatis 链也改成注释占位（此前整段抹掉 → 残句）
        val text = """<select id="q14">SELECT <include refid="userColumns"/> FROM T_USER</select>"""
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertEquals(XmlFlavorDetector.Flavor.MYBATIS, resolved!!.flavor)
        assertTrue(
            "MyBatis 侧缺失处也应留 TODO：${resolved.result.sql}",
            resolved.result.sql.contains("TODO: missing <sql id=\"userColumns\">"),
        )
    }

    @Test
    fun `无动态标签的 iBatis 片段靠占位语法判对且参数名正确`() {
        // 这条是风味判定的**不可替代性**证据：MyBatis 链能「解析成功但解析错」——
        // `#id#` 在 MyBatis 眼里是普通文本（xmlModel 非空、无参数），双链回退因此救不了它，
        // 只有占位语法判定能把片段交给 iBatis 链（产出参数名 id）。
        val text = """<select id="q12">SELECT * FROM T_USER WHERE ID = #id#</select>"""
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(XmlFlavorDetector.Flavor.IBATIS, resolved!!.flavor)
        assertEquals(listOf("id"), resolved.result.unresolvedParams)
    }

    @Test
    fun `两条链都还原不了时给出失败事实`() {
        // 缺闭合标签：两代链都解析不出语句 → 失败事实带语句 id 与原因
        val text = """<select id="q11">SELECT 1 FROM t"""
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        val failure = resolved!!.failure
        assertNotNull("必须给出失败事实（面板据此组装可复制文案）", failure)
        assertEquals("q11", failure!!.statementId)
        assertEquals("select", failure.statementTagName)
        assertNotNull("原因不得为空", failure.reason)
    }

    // ---------- T-PreviewActionRow D-4：缺失片段事实随载荷走 ----------

    @Test
    fun `引用但未定义的片段id挂到载荷上`() {
        // 面板只拿得到渲染后的 SQL（include 已展开成 TODO 占位），缺失事实必须由解析侧随载荷给出，
        // 否则弹窗与工作台都得各自回头扫原文。
        val text = """
            <select id="q20">
              SELECT <include refid="Base_Column_List"/>
              FROM t WHERE id = #{id}
            </select>
        """.trimIndent()
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(listOf("Base_Column_List"), resolved!!.result.missingFragmentIds)
        assertTrue("缺失处仍留 TODO 占位：${resolved.result.sql}", resolved.result.sql.contains("TODO: missing"))
    }

    @Test
    fun `片段定义在同一段文本里时不算缺失`() {
        val text = """
            <sql id="Base_Column_List">id, name</sql>
            <select id="q21">SELECT <include refid="Base_Column_List"/> FROM t</select>
        """.trimIndent()
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertTrue("已定义过就不该报缺失", resolved!!.result.missingFragmentIds.isEmpty())
        assertFalse("展开后不应再有 TODO 占位", resolved.result.sql.contains("TODO: missing"))
    }

    @Test
    fun `纯SQL路径不产缺失片段事件`() {
        val resolved = StandaloneSqlSourceResolver.resolve("SELECT * FROM t WHERE a = ?")
        assertNotNull(resolved)
        assertTrue(resolved!!.result.missingFragmentIds.isEmpty())
    }

    @Test
    fun `转义形态的include也能算出缺失`() {
        // 网页复制的 `&lt;include …&gt;` 只有解码后才是真 XML——缺失判定必须按解码文本走
        val text = "<select id=\"q22\">SELECT &lt;include refid=\"Cols\"/&gt; FROM t</select>"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(listOf("Cols"), resolved!!.result.missingFragmentIds)
    }

    // T-XmlEntityDecode：解码实现统一到 xml.decodeXmlTextSegment（单趟、只解一次、补数字引用）

    @Test
    fun `转义整段里的数字字符引用一并还原`() {
        // 网页转义后仍保留的数字引用（`&#62;` → `>`）必须与其他实体一并还原，
        // 否则整段粘贴的 SQL 里会带 `&#62;=` 这种执行不了的形态
        val text = "&lt;mapper&gt;&lt;select id=&quot;q6&quot;&gt;SELECT * FROM t WHERE a &#62;= 5&lt;/select&gt;&lt;/mapper&gt;"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals(StandaloneSqlSourceResolver.Kind.XML_STATEMENT, resolved!!.kind)
        assertTrue("数字引用应还原为比较符：${resolved.result.sql}", resolved.result.sql.contains("a >= 5"))
    }

    @Test
    fun `转义整段里的CDATA内容只解一层`() {
        // 网页对原 XML 做字符级转义：CDATA 里的字面 `&gt;` 会变成 `&amp;gt;`。
        // 解码一次后还原成 `<![CDATA[... &gt; ...]]>`，DOM 再解析时 CDATA 保持字面量——
        // 这正是 MyBatis 真机语义；实现不得把 CDATA 内容当普通文本再解一层。
        val text = "&lt;mapper&gt;&lt;select id=&quot;q7&quot;&gt;" +
            "&lt;![CDATA[SELECT * FROM t WHERE a &amp;gt;= 5]]&gt;" +
            "&lt;/select&gt;&lt;/mapper&gt;"
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertEquals("SELECT * FROM t WHERE a &gt;= 5", resolved!!.result.sql.trim())
    }

    @Test
    fun `跨命名空间refid按末段名对定义`() {
        val text = """
            <sql id="Cols">id</sql>
            <select id="q23">SELECT <include refid="base.Cols"/> FROM t</select>
        """.trimIndent()
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        assertNotNull(resolved)
        assertTrue(
            "ns 前缀的引用与裸 id 定义应视为已定义：${resolved!!.result.missingFragmentIds}",
            resolved.result.missingFragmentIds.isEmpty(),
        )
    }
}
