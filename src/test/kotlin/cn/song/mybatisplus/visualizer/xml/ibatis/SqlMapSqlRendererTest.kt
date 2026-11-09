package cn.song.mybatisplus.visualizer.xml.ibatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染器单测（T-Ibatis2Support 券 B）——钉住几条**不许「顺手改好」**的口径：
 * `prepend` 逐字拼接不补空格（`ANDORG_NAME` 形态）、静态/动态两条路径的归一化差异、
 * `parameterMap` 守卫只作用于非动态语句、`nullValue` 哨兵语义。
 */
class SqlMapSqlRendererTest {

    private val parser = SqlMapParser()
    private val renderer = SqlMapSqlRenderer()

    @Test
    fun `prepend 逐字拼接不补空格`() {
        val text = """
            <sqlMap>
              <select id="dyn">
            SELECT * FROM T_ORG WHERE ORG_ID = #orgId#<isNotNull prepend="AND" property="orgName">ORG_NAME LIKE '%${'$'}orgName${'$'}%' </isNotNull><isNotNull prepend="AND" property="flag">FLAG = #flag#</isNotNull>ORDER BY ORG_ID
              </select>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("dyn")!!
        val result = renderer.render(statement, mapOf("orgId" to "A001", "orgName" to "Org", "flag" to "1"))

        assertTrue(
            "真实输出会出现 ANDORG_NAME 这种粘连，渲染器禁止补空格：${result.sql}",
            result.sql.contains("= ?ANDORG_NAME"),
        )
        assertTrue("第二处同样不补空格：${result.sql}", result.sql.contains("'%Org%' ANDFLAG = ?"))
        // orgName 走的是 `$orgName$` 直替（不是 `#`），所以参数表里没有它
        assertEquals(listOf("orgId", "flag"), result.paramNames)
        assertEquals(listOf("Org"), result.dollarRenderedValues)
        // 模板里换行/缩进被引擎归一为空格，输出保留原空白（不做美化重排）
        assertFalse("换行应被归一成空格而不是被删：${result.sql.replace("\n", "<LF>")}", result.sql.contains("\n"))
        assertEquals(
            "compactSql 只做折叠，不改 sql 本体",
            result.sql.split(' ', '\r', '\n', '\t').filter { it.isNotEmpty() }.joinToString(" "),
            result.compactSql,
        )
    }

    @Test
    fun `dynamic prepend 吃掉第一个子 prepend 且本层 prepend 保留`() {
        val text = """
            <sqlMap>
              <select id="s">SELECT 1<dynamic prepend=" AND "><isNotNull prepend="DROPPED" property="a">A = #a#</isNotNull><isNotNull prepend="ZZZ" property="b">B = #b#</isNotNull></dynamic></select>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("s")!!
        val sql = renderer.render(statement, mapOf("a" to 1, "b" to 2)).sql
        assertEquals("SELECT 1 AND A = ?ZZZB = ?", sql.trim())
    }

    @Test
    fun `静态路径参数图为空则按语句声明处理且不转问号`() {
        val text = """
            <sqlMap namespace="demo">
              <parameterMap id="pm" class="java.util.HashMap">
                <parameter property="orgId" jdbcType="VARCHAR"/>
                <parameter property="orgName" jdbcType="VARCHAR" nullValue="N/A"/>
              </parameterMap>
              <insert id="i" parameterMap="pm">INSERT INTO T (A, B) VALUES (#orgId#, #orgName#)</insert>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("i")!!
        val result = renderer.render(statement, mapOf("orgId" to "A001", "orgName" to "N/A"))

        assertEquals(
            "声明了 parameterMap 的非动态语句：# 原样保留（引擎 applyInlineParameterMap 守卫）",
            "INSERT INTO T (A, B) VALUES (#orgId#, #orgName#)",
            result.sql,
        )
        assertEquals("参数名应取自 parameterMap 声明", listOf("orgId", "orgName"), result.paramNames)
        assertEquals(listOf("VARCHAR", "VARCHAR"), result.paramJdbcTypes)
        assertEquals(listOf(null, "N/A"), result.paramNullValues)
        assertEquals("orgName 命中哨兵 → 引擎按 SQL NULL 绑定", listOf(false, true), result.nullBindFlags)
        assertTrue(result.warnings.any { it.contains("不转 ?") })
    }

    @Test
    fun `动态语句即使声明 parameterMap 仍然把井号转成问号`() {
        val text = """
            <sqlMap namespace="demo">
              <parameterMap id="pm" class="java.util.HashMap">
                <parameter property="orgId" jdbcType="VARCHAR"/>
              </parameterMap>
              <select id="s" parameterMap="pm">SELECT 1<isNotNull property="orgId">A = #orgId#</isNotNull></select>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("s")!!
        assertTrue(statement.isDynamic)
        val result = renderer.render(statement, mapOf("orgId" to "A001"))
        assertEquals("SELECT 1A = ?", result.sql)
        assertEquals(listOf("orgId"), result.paramNames)
    }

    @Test
    fun `静态路径普通分支把制表符也归一为空格`() {
        val statement = parser.parse("<sqlMap><select id=\"s\">SELECT\tA</select></sqlMap>").statementById("s")!!
        assertEquals("SELECT A", renderer.render(statement).sql)
    }

    @Test
    fun `parameterMap 分支保留制表符只归一换行`() {
        val text = "<sqlMap>" +
            "<parameterMap id=\"pm\" class=\"java.util.HashMap\">" +
            "<parameter property=\"a\" jdbcType=\"VARCHAR\"/></parameterMap>" +
            "<select id=\"s\" parameterMap=\"pm\">SELECT\tA\nFROM T</select>" +
            "</sqlMap>"
        val statement = parser.parse(text).statementById("s")!!
        val sql = renderer.render(statement).sql
        assertTrue("制表符保留：${sql.replace("\t", "<TAB>")}", sql.contains("SELECT\tA"))
        assertFalse("换行归一为空格", sql.contains("\n"))
    }

    @Test
    fun `美元直替在动态标签之后并把统计透出`() {
        val text = """
            <sqlMap>
              <select id="s">SELECT * FROM ${'$'}tableName${'$'} WHERE 1=1<isNotNull property="a" prepend=" AND ">A = #a#</isNotNull></select>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("s")!!
        val result = renderer.render(statement, mapOf("tableName" to "T_ORG", "a" to 1))
        assertEquals("SELECT * FROM T_ORG WHERE 1=1 AND A = ?", result.sql)
        assertEquals(1, result.dollarRawCount)
        assertEquals(listOf("T_ORG"), result.dollarRenderedValues)
        assertTrue("含 $ 直替段必须可被注入闸门识别", result.hasDollarRawSegments)
    }

    @Test
    fun `iterate 产出 open conjunction close 与下标参数名`() {
        val text = """
            <sqlMap>
              <select id="s">SELECT * FROM T WHERE <iterate property="ids" open="(" close=")" conjunction=" OR ">ID = #ids[]#</iterate></select>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("s")!!
        val result = renderer.render(statement, mapOf("ids" to listOf("X1", "X2", "X3")))
        assertEquals("SELECT * FROM T WHERE (ID = ? OR ID = ? OR ID = ?)", result.sql.trim())
        assertEquals(listOf("ids[0]", "ids[1]", "ids[2]"), result.paramNames)
    }

    @Test
    fun `iterate 取值为空时整段不产出`() {
        val text = """
            <sqlMap>
              <select id="s">SELECT * FROM T WHERE 1=1<iterate property="ids" open="(" close=")" conjunction=" OR ">ID = #ids[]#</iterate></select>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("s")!!
        assertEquals(
            "SELECT * FROM T WHERE 1=1",
            renderer.render(statement, mapOf("ids" to emptyList<String>())).sql.trim(),
        )
    }

    @Test
    fun `iterate 属性不是集合时降级并保留可读原文`() {
        val text = """
            <sqlMap>
              <select id="s">SELECT * FROM T WHERE <iterate property="ids">ID = #ids[]#</iterate></select>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("s")!!
        val result = renderer.render(statement, mapOf("ids" to "notACollection"))
        assertTrue("必须是降级而不是抛穿", result.warnings.any { it.contains("渲染降级") })
        assertTrue("降级产物仍要可读：${result.sql}", result.sql.contains("SELECT * FROM T WHERE"))
    }

    @Test
    fun `渲染告警可回溯到语句 id`() {
        val text = """
            <sqlMap>
              <parameterMap id="pm" class="java.util.HashMap"><parameter property="a" jdbcType="VARCHAR"/></parameterMap>
              <select id="myStatement" parameterMap="pm">SELECT ?</select>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("myStatement")!!
        val result = renderer.render(statement)
        assertTrue(
            "告警必须带语句 id：${result.warnings}",
            result.warnings.isNotEmpty() && result.warnings.all { it.contains("myStatement") },
        )
    }

    @Test
    fun `selectKey 渲染使用独立 id 且参数名来自体内解析`() {
        val text = """
            <sqlMap>
              <insert id="i">INSERT INTO T (A) VALUES (#a#)<selectKey keyProperty="k" resultClass="java.lang.Integer" type="pre">SELECT MAX(ID) + #offset# FROM T</selectKey></insert>
            </sqlMap>
        """.trimIndent()
        val selectKey = parser.parse(text).statementById("i")!!.selectKeys.single()
        val result = renderer.renderSelectKey(selectKey, mapOf("offset" to 1))
        assertEquals("i-SelectKey", result.usedStatementIds.single())
        assertEquals("SELECT MAX(ID) + ? FROM T", result.sql)
        assertEquals(listOf("offset"), result.paramNames)
        assertFalse(selectKey.runAfterSql)
    }

    @Test
    fun `哨兵判定按类型换算而非纯字符串`() {
        assertEquals(true, IbatisProbe.matchesNullValue(5, "5"))
        assertEquals(true, IbatisProbe.matchesNullValue("N/A", "N/A"))
        assertEquals(false, IbatisProbe.matchesNullValue("n/a", "N/A"))
        assertEquals(false, IbatisProbe.matchesNullValue(5, "N/A"))
        assertEquals(false, IbatisProbe.matchesNullValue(null, "N/A"))
        assertEquals(false, IbatisProbe.matchesNullValue("x", null))
    }

    // ------------------------------------------------------------------
    // 券 C（T-Ibatis2Gap）：parameterMap 语句参数名按位对齐
    // ------------------------------------------------------------------

    @Test
    fun `parameterMap 声明多于位点时按位取前 N 剩余透出未消费`() {
        val text = """
            <sqlMap namespace="demo">
              <parameterMap id="pm" class="java.util.HashMap">
                <parameter property="username" jdbcType="VARCHAR"/>
                <parameter property="email" jdbcType="VARCHAR"/>
                <parameter property="age" jdbcType="INTEGER"/>
                <parameter property="memo" jdbcType="VARCHAR"/>
              </parameterMap>
              <statement id="s" parameterMap="pm">SELECT * FROM T WHERE A = ? AND B = ? AND C = ?</statement>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("s")!!
        val result = renderer.render(statement)

        assertEquals(
            "引擎按位置把前 N（= `?` 个数）个声明映射绑到位点上，参数名不再是全部声明",
            listOf("username", "email", "age"),
            result.paramNames,
        )
        assertEquals(
            "剩余声明映射不丢，透出给 UI 提示「声明了但未消费」",
            listOf("memo"),
            result.unusedDeclaredParams.map { it.propertyName },
        )
    }

    @Test
    fun `parameterMap 位点计数 string aware_字符串与注释里的问号不算`() {
        val text = """
            <sqlMap namespace="demo">
              <parameterMap id="pm" class="java.util.HashMap">
                <parameter property="p1" jdbcType="VARCHAR"/>
                <parameter property="p2" jdbcType="VARCHAR"/>
              </parameterMap>
              <statement id="s" parameterMap="pm">SELECT 'a?b' /* c? */ FROM T WHERE A = ?</statement>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("s")!!
        val result = renderer.render(statement)
        assertEquals(
            "字符串字面量与注释里的 `?` 不是位点（复用 sql 包 string-aware 口径）",
            listOf("p1"),
            result.paramNames,
        )
        assertEquals(listOf("p2"), result.unusedDeclaredParams.map { it.propertyName })
    }

    @Test
    fun `parameterMap 混合位点_内联井号对与手写问号都算_等价 viaParameterMapRawHash 形态`() {
        // 形态复刻 demo `User.xml` 的 viaParameterMapRawHash（含 `##` 转义与字符串内 `#` 的对照归零面）
        val text = """
            <sqlMap namespace="User">
              <parameterMap id="userParamMap" class="User">
                <parameter property="username" javaType="java.lang.String" jdbcType="VARCHAR" mode="IN"/>
                <parameter property="email" javaType="java.lang.String" jdbcType="VARCHAR" nullValue="none@example.com"/>
                <parameter property="age" javaType="java.lang.Integer" jdbcType="INTEGER" mode="INOUT"/>
              </parameterMap>
              <statement id="viaParameterMapRawHash" parameterMap="userParamMap">
                SELECT 'A ## B' AS ESCAPE_PAIR, #username# AS RAW_HASH_KEPT, ? AS POSITIONAL
                FROM T_USER
                WHERE USER_NAME = ? AND EMAIL = ?
              </statement>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("viaParameterMapRawHash")!!
        val result = renderer.render(statement)

        assertEquals(
            "4 个位点（1 个 `#username#` 对 + 3 个 `?`；`##` 转义与字符串内 `#` 不算）≥ 3 个声明 ⇒ 全部消费",
            listOf("username", "email", "age"),
            result.paramNames,
        )
        assertTrue("位点数 ≥ 声明数 ⇒ 无未消费映射", result.unusedDeclaredParams.isEmpty())
        assertEquals(
            "jdbcType 与 paramNames 同序投影",
            listOf("VARCHAR", "VARCHAR", "INTEGER"),
            result.paramJdbcTypes,
        )
        assertTrue("SQL 逐字节不变：`#` 在 parameterMap 分支仍原样保留", result.sql.contains("#username#"))
    }

    @Test
    fun `callUserProcedure 形态_手写三个问号_参数名按位对齐`() {
        val text = """
            <sqlMap namespace="User">
              <parameterMap id="userParamMap" class="User">
                <parameter property="username" jdbcType="VARCHAR"/>
                <parameter property="email" jdbcType="VARCHAR"/>
                <parameter property="age" jdbcType="INTEGER"/>
              </parameterMap>
              <procedure id="callUserProcedure" parameterMap="userParamMap">{ call PKG_USER.SYNC_USER(?, ?, ?) }</procedure>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("callUserProcedure")!!
        val result = renderer.render(statement)
        assertEquals(listOf("username", "email", "age"), result.paramNames)
        assertTrue(result.unusedDeclaredParams.isEmpty())
    }

    // ------------------------------------------------------------------
    // 券 D（T-Ibatis2Gap）：降级结构化 + 分档文案（不再谎称「按原文保留 # 直替」）
    // ------------------------------------------------------------------

    @Test
    fun `iterate 无参降级_结构化事实与分档文案`() {
        val text = """
            <sqlMap>
              <select id="s">SELECT * FROM T_USER<dynamic prepend="WHERE"><iterate property="ids" prepend="AND " open="(" close=")" conjunction=" OR ">ID = #ids[]#</iterate></dynamic></select>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("s")!!
        val result = renderer.render(statement)

        assertTrue("降级事实必须透出", result.degraded)
        val node = result.unrenderedNodes.single()
        assertEquals("未渲染的顶层节点是 <dynamic>", "dynamic", node.tagName)
        assertEquals("第 2 个顶层节点起未渲染（0 基）", 1, node.topNodeIndex)
        assertEquals(IbatisRenderFailureKind.ITERATE_NOT_COLLECTION, node.reason)
        assertEquals("需要填值的参数名要透出（UI 据此显示待填参数）", "ids", node.property)

        val degraded = result.warnings.single { it.contains("渲染降级") }
        assertTrue("分档文案要给出标签与 property：$degraded", degraded.contains("<iterate") && degraded.contains("ids") && degraded.contains("集合"))
        assertFalse(
            "文案禁止与事实不符的「按原文保留 # 直替」：${result.warnings}",
            result.warnings.any { it.contains("按原文保留") },
        )
        assertTrue("回退拼装仍是既有行为（仅顶层文本节点）", result.sql.contains("SELECT * FROM T_USER"))
        assertFalse("<dynamic> 段不应出现在降级产物里：${result.sql}", result.sql.contains("WHERE"))
    }

    @Test
    fun `iterate 位于顶层时未渲染节点直接指向 iterate`() {
        val text = """
            <sqlMap>
              <select id="s">SELECT * FROM T WHERE <iterate property="ids" open="(" close=")">ID = #ids[]#</iterate></select>
            </sqlMap>
        """.trimIndent()
        val statement = parser.parse(text).statementById("s")!!
        val result = renderer.render(statement)
        assertTrue(result.degraded)
        val node = result.unrenderedNodes.single()
        assertEquals("iterate", node.tagName)
        assertEquals(1, node.topNodeIndex)
        assertEquals(IbatisRenderFailureKind.ITERATE_NOT_COLLECTION, node.reason)
        assertEquals("ids", node.property)
    }
}
