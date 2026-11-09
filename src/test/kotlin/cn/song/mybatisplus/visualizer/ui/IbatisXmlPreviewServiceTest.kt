package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iBatis 预览服务端到端测试（T-Ibatis2Support 券 A 触发面三登记义务的第 3 条）。
 *
 * 形态照 `PagesXmlPreviewServiceTest`：**真实 iBatis XML 文本 → 预览 → 断言 [cn.song.mybatisplus.visualizer.model.PreviewResult]
 * 的期望快照**（`?` 形态 + 参数名序列）——纯 JUnit 零平台依赖，可进 testFast 桶。
 *
 * 覆盖域（对齐验收项）：
 * - `#name#` → `?`，参数名按占位符序落到 `unresolvedParams`（面板据此列出参数行，不再显示「无参数」）；
 * - 两段/三段占位符 → jdbcType / nullValue 只影响绑定，SQL 文本仍是 `?`；
 * - 含 `<dynamic>` + `<isNotNull>` 的语句：条件在「无参基线」下按引擎语义跳过，prepend **逐字拼接不补空格**；
 * - `$x$` 直替与未替换的 `${prop}` 都置 `hasDollarRawSegments`（接既有注入闸门）；
 * - 声明 `parameterMap` 的非动态语句：`#` 不转 `?`（引擎守卫），面板不得报假参数表；
 * - 非 `<sqlMap>` / 定位失败 / 空体 → 不抛穿，返回带 warning 的空结果。
 */
class IbatisXmlPreviewServiceTest {

    private fun preview(xml: String, id: String?, tagName: String? = "select") =
        IbatisXmlPreviewService.preview(xml, id, tagName)

    // ------------------------------------------------------------------
    // 端到端正例：静态语句
    // ------------------------------------------------------------------

    @Test
    fun `端到端正例_静态语句_期望快照`() {
        val xml =
            """<?xml version="1.0" encoding="UTF-8"?>
               |<!DOCTYPE sqlMap PUBLIC "-//iBATIS.com//DTD SQL Map 2.0//EN" "http://ibatis.apache.org/dtd/sql-map-2.dtd">
               |<sqlMap namespace="Org"><select id="findOrgByName">SELECT ORG_ID FROM T_ORG WHERE ORG_NAME = #orgName#</select></sqlMap>
            """.trimMargin()
        val result = preview(xml, "findOrgByName")
        assertEquals(
            "静态语句：`#name#` 必须原样转 `?`，其余文本逐字保留（不美化重排）",
            "SELECT ORG_ID FROM T_ORG WHERE ORG_NAME = ?",
            result.sql,
        )
        assertEquals("参数名按 `?` 出现序（面板据此列参数行）", listOf("orgName"), result.unresolvedParams)
        assertEquals("参数对照表复用既有渲染", "-- 参数对照: [orgName=?]", result.paramTable)
        assertNull("无告警语句不应有降级横幅", result.warning)
        assertFalse("无 \$ / \${} 时注入闸门不得误报", result.hasDollarRawSegments)
    }

    @Test
    fun `端到端正例_多参数按出现序命名`() {
        val xml =
            """<sqlMap><update id="upd">UPDATE T_ORG SET ORG_NAME = #orgName# WHERE ORG_ID = #orgId#</update></sqlMap>"""
        val result = preview(xml, "upd", "update")
        assertEquals("UPDATE T_ORG SET ORG_NAME = ? WHERE ORG_ID = ?", result.sql)
        assertEquals(listOf("orgName", "orgId"), result.unresolvedParams)
    }

    @Test
    fun `两段三段占位符_SQL文本仍是问号`() {
        val xml =
            """<sqlMap><select id="s">SELECT A FROM T WHERE B = #b:VARCHAR# AND C = #c:VARCHAR:N/A#</select></sqlMap>"""
        val result = preview(xml, "s")
        assertEquals("SELECT A FROM T WHERE B = ? AND C = ?", result.sql)
        assertEquals(listOf("b", "c"), result.unresolvedParams)
    }

    @Test
    fun `按标签名与序号定位_无id也能对齐`() {
        val xml =
            """<sqlMap><select id="first">SELECT 1 FROM T</select><select>SELECT 2 FROM T</select></sqlMap>"""
        val result = IbatisXmlPreviewService.preview(xml, null, "select", 1)
        assertEquals("SELECT 2 FROM T", result.sql)
    }

    // ------------------------------------------------------------------
    // 端到端正例：动态标签（含 <dynamic> 子标签的语句必须能预览）
    // ------------------------------------------------------------------

    @Test
    fun `端到端正例_含dynamic与isNotNull_无参基线_条件整段跳过`() {
        // 无参基线（params=null）下引擎语义：`<isNotNull property>` 判否 → 片段整段跳过 →
        // `<dynamic>` 的缓冲为空 → 其 prepend 也不插入（`hasContent` 为假）。
        val xml =
            """<sqlMap><select id="dyn">SELECT ORG_ID FROM T_ORG<dynamic prepend="WHERE">""" +
                """<isNotNull property="orgId">ORG_ID = #orgId#</isNotNull></dynamic></select></sqlMap>"""
        val result = preview(xml, "dyn")
        assertEquals("SELECT ORG_ID FROM T_ORG", result.sql)
        assertEquals("被跳过的条件片段不产生参数名", emptyList<String>(), result.unresolvedParams)
    }

    @Test
    fun `端到端正例_prepend逐字拼接不补空格`() {
        // `<isNotParameterPresent>` 在无参基线下成立 → 片段进 SQL。引擎把 dynamic 的 prepend 插在
        // **自己的缓冲首位**，父缓冲里紧随其后的文本与它直接粘连（`ANDORG_NAME` 同族形态）——
        // 渲染器禁止美化重排，预览必须逐字复现。
        val xml =
            """<sqlMap><select id="glue">SELECT * FROM T_ORG<dynamic prepend="WHERE">""" +
                """<isNotParameterPresent property="x">AND ID = #id#</isNotParameterPresent>""" +
                """</dynamic></select></sqlMap>"""
        val result = preview(xml, "glue")
        assertEquals("SELECT * FROM T_ORGWHEREAND ID = ?", result.sql)
        assertEquals(listOf("id"), result.unresolvedParams)
    }

    @Test
    fun `含dynamic与isNull_换行与制表归一为空格`() {
        val xml = """
            <sqlMap>
                <select id="dyn2">SELECT ORG_ID FROM T_ORG
                    <dynamic prepend="WHERE">
                        <isNull property="orgId">ORG_ID = #orgId#</isNull>
                    </dynamic>
                </select>
            </sqlMap>
        """.trimIndent()
        val result = preview(xml, "dyn2")
        // `<isNull property="orgId">` 在无参基线下成立 → 片段进 SQL；`#orgId#` → `?`；
        // prepend `WHERE` 只出现一次（prepend 消费正确，不重复）
        assertEquals("WHERE 只应出现一次：${result.sql}", 1, result.sql.split("WHERE").size - 1)
        assertTrue("输出应以 `ORG_ID = ?` 收尾（尾部空白来自缩进文本节点）：${result.sql}", result.sql.trimEnd().endsWith("ORG_ID = ?"))
        assertEquals(listOf("orgId"), result.unresolvedParams)
        assertFalse("换行已按引擎归一为空格", result.sql.contains("\n"))
        assertFalse("制表符已按引擎归一为空格", result.sql.contains("\t"))
    }

    // ------------------------------------------------------------------
    // 安全面：$ 直替与未替换的 ${} 都进既有注入闸门
    // ------------------------------------------------------------------

    @Test
    fun `美元直替段_置注入闸门`() {
        val xml = "<sqlMap><select id=\"d\">SELECT * FROM \$tableName\$</select></sqlMap>"
        val result = preview(xml, "d")
        assertTrue(
            "`\$x\$` 站点是运行期原文直拼面，无参基线取不到值也必须进既有 \${} 确认闸门",
            result.hasDollarRawSegments,
        )
        assertEquals("无参基线：`\$x\$` 取不到值被静默丢弃（引擎语义）", "SELECT * FROM ", result.sql)
    }

    @Test
    fun `美元转义_不置闸门`() {
        val xml = "<sqlMap><select id=\"e\">SELECT '\$\$' AS DOLLAR FROM T</select></sqlMap>"
        val result = preview(xml, "e")
        assertEquals("`\$\$` 是引擎的字面量 `\$`，转义段不是直拼面", "SELECT '$' AS DOLLAR FROM T", result.sql)
        assertFalse("转义段不得误报注入闸门", result.hasDollarRawSegments)
    }

    @Test
    fun `未替换的属性占位_同样置注入闸门并给告警`() {
        val xml = "<sqlMap><select id=\"p\">SELECT * FROM \${tableName}</select></sqlMap>"
        val result = preview(xml, "p")
        assertTrue("`\${...}` 原文段必须进闸门", result.hasDollarRawSegments)
        assertNotNull("引擎会替换全局属性，本链保留原文 → 必须有可见告警", result.warning)
    }

    // ------------------------------------------------------------------
    // parameterMap 分支（引擎 `#` 不转 `?`）
    // ------------------------------------------------------------------

    @Test
    fun `声明parameterMap的非动态语句_井号不转问号_并给可见告警`() {
        val xml = """
            <sqlMap>
                <parameterMap id="pm1" class="java.util.Map">
                    <parameter property="orgId" jdbcType="VARCHAR"/>
                </parameterMap>
                <statement id="st" parameterMap="pm1">UPDATE T SET A = #orgId# WHERE ID = ?</statement>
            </sqlMap>
        """.trimIndent()
        val result = preview(xml, "st", "statement")
        assertTrue("引擎守卫：声明 parameterMap 的静态语句 `#` 原样保留", result.sql.contains("#orgId#"))
        assertNotNull("「# 未转 ?」是配置错配，必须给面板可见告警", result.warning)
        assertEquals("手写 ? 由 parameterMap 按声明序绑定", listOf("orgId"), result.unresolvedParams)
    }

    @Test
    fun `手写问号无parameterMap_参数名按paramN兜底并明示不可知`() {
        val xml = """<sqlMap><statement id="st2">UPDATE T SET A = ? WHERE ID = ?</statement></sqlMap>"""
        val result = preview(xml, "st2", "statement")
        assertEquals("UPDATE T SET A = ? WHERE ID = ?", result.sql)
        assertEquals("拍板 D-7：参数名不可知时按 param1..N 兜底", listOf("param1", "param2"), result.unresolvedParams)
        assertNotNull("须明示「参数名不可知」", result.warning)
    }

    // ------------------------------------------------------------------
    // 异常路径（不抛穿）
    // ------------------------------------------------------------------

    @Test
    fun `非sqlMap根_返回空结果带告警`() {
        val result = preview("<mapper><select id=\"x\">SELECT 1</select></mapper>", "x")
        assertEquals("", result.sql)
        assertNotNull(result.warning)
    }

    @Test
    fun `id定位不中_按同标签名序号回落不空白`() {
        val result = preview("<sqlMap><select id=\"a\">SELECT 1 FROM T</select></sqlMap>", "missing")
        // 定位回落：同标签名序号 0 → 仍能给出内容（不让用户看到空白）
        assertEquals("SELECT 1 FROM T", result.sql)
    }

    @Test
    fun `空体语句_不抛穿`() {
        val result = preview("<sqlMap><select id=\"empty\"></select></sqlMap>", "empty")
        assertEquals("", result.sql)
        assertNull("空体不是错误路径，只应是空 SQL", result.warning)
    }

    // ------------------------------------------------------------------
    // T-Ibatis2Gap 券 A：参数可交互（条件勾选 + 值可填，rerender 回调）
    // ------------------------------------------------------------------

    /** 对齐 demo `User.xml` 的 selectByCondition 形态。 */
    private val dollar = "${'$'}"
    private val conditionXml = """
        <sqlMap namespace="user">
            <select id="selectByCondition" parameterClass="java.util.Map" resultMap="userResultMap">
                SELECT ID, USER_NAME, EMAIL, AGE, STATUS FROM T_USER
                <dynamic prepend="WHERE">
                    <isNotNull property="id" prepend="AND"> ID = #id# </isNotNull>
                    <isNotEmpty property="username" prepend="AND"> USER_NAME LIKE '%${dollar}username${dollar}%' </isNotEmpty>
                    <isEqual property="status" compareValue="1" prepend="AND"> STATUS = #status# </isEqual>
                    <isGreaterThan property="age" compareValue="18" prepend="AND"> AGE > #age# </isGreaterThan>
                </dynamic>
                ORDER BY ID
            </select>
            <select id="selectByIterate" parameterClass="java.util.Map" resultMap="userResultMap">
                SELECT ID, USER_NAME FROM T_USER
                <dynamic prepend="WHERE">
                    <iterate property="ids" open="(" close=")" conjunction="OR"> ID = #ids[]# </iterate>
                </dynamic>
            </select>
        </sqlMap>
    """.trimIndent()

    private fun condRow(result: cn.song.mybatisplus.visualizer.model.PreviewResult, tagName: String) =
        result.ibatisParamModel!!.conditionRows.first { it.kindTagName == tagName }

    private fun rerender(result: cn.song.mybatisplus.visualizer.model.PreviewResult, input: cn.song.mybatisplus.visualizer.model.PreviewRerenderInput) =
        result.rerender!!(input)

    @Test
    fun `券A_预览携带行清单与重渲染回调_基线与现状一致`() {
        val result = preview(conditionXml, "selectByCondition")
        assertTrue("条件四件套进行清单", result.ibatisParamModel!!.conditionRows.map { it.kindTagName } ==
            listOf("isNotNull", "isNotEmpty", "isEqual", "isGreaterThan"))
        assertEquals("占位符行让用户看见语句需要哪些参数", listOf("id", "status", "age"), result.ibatisParamModel!!.placeholderRows.map { it.name })
        assertNotNull("重渲染回调必须在场", result.rerender)
        // 基线（无参）= 现状：条件整段跳过、零参数（多行夹具的空白归一是引擎行为，比对走 compactSql）
        val baseline = rerender(result, cn.song.mybatisplus.visualizer.model.PreviewRerenderInput())
        assertEquals("SELECT ID, USER_NAME, EMAIL, AGE, STATUS FROM T_USER ORDER BY ID", baseline.compactSql)
        assertEquals("基线零占位符", emptyList<String>(), baseline.unresolvedParams)
    }

    @Test
    fun `券A_勾isEqual_status进WHERE且参数在`() {
        val result = preview(conditionXml, "selectByCondition")
        val isEqual = condRow(result, "isEqual")
        val fresh = rerender(
            result,
            cn.song.mybatisplus.visualizer.model.PreviewRerenderInput(enabledConditionIds = setOf(isEqual.id)),
        )
        assertTrue("SQL 出现 STATUS 段：${fresh.sql}", fresh.sql.contains("STATUS = ?"))
        assertEquals("参数 status（compareValue 兜底成立）", listOf("status"), fresh.unresolvedParams)
    }

    @Test
    fun `券A_勾isNotNull加isEqual_首段AND被WHERE消费`() {
        val result = preview(conditionXml, "selectByCondition")
        val fresh = rerender(
            result,
            cn.song.mybatisplus.visualizer.model.PreviewRerenderInput(
                params = mapOf("id" to "5"),
                enabledConditionIds = setOf(condRow(result, "isNotNull").id, condRow(result, "isEqual").id),
            ),
        )
        assertEquals(
            "两段拼接形态（首段 prepend 被 dynamic 的 WHERE 消费；空白归一后单空格）：${fresh.sql}",
            "SELECT ID, USER_NAME, EMAIL, AGE, STATUS FROM T_USER WHERE ID = ? AND STATUS = ? ORDER BY ID",
            fresh.compactSql,
        )
        assertEquals(listOf("id", "status"), fresh.unresolvedParams)
    }

    @Test
    fun `券A_填age20勾isGreaterThan_出现AGE段`() {
        val result = preview(conditionXml, "selectByCondition")
        val fresh = rerender(
            result,
            cn.song.mybatisplus.visualizer.model.PreviewRerenderInput(
                params = mapOf("age" to "20"),
                enabledConditionIds = setOf(condRow(result, "isGreaterThan").id),
            ),
        )
        assertTrue("AND AGE > ? 应出现：${fresh.sql}", fresh.sql.contains("AGE > ?"))
        assertEquals(listOf("age"), fresh.unresolvedParams)
    }

    @Test
    fun `券A_数值比较_age9勾isGreaterThan条件不成立_字典序会误判`() {
        val result = preview(conditionXml, "selectByCondition")
        val fresh = rerender(
            result,
            cn.song.mybatisplus.visualizer.model.PreviewRerenderInput(
                params = mapOf("age" to "9"),
                enabledConditionIds = setOf(condRow(result, "isGreaterThan").id),
            ),
        )
        assertFalse("age=9 不满足 >18（字典序 \"9\">\"18\" 会误判成立——本券要修的形态）：${fresh.sql}", fresh.sql.contains("AGE > ?"))
    }

    @Test
    fun `券A_iterate_三个id展开OR链`() {
        val result = preview(conditionXml, "selectByIterate")
        val fresh = rerender(
            result,
            cn.song.mybatisplus.visualizer.model.PreviewRerenderInput(collections = mapOf("ids" to listOf("X1", "X2", "X3"))),
        )
        assertTrue("OR 链：${fresh.sql}", fresh.compactSql.contains("( ID = ? OR ID = ? OR ID = ? )"))
        assertEquals("参数名按列表长度生成 ids[0..2]", listOf("ids[0]", "ids[1]", "ids[2]"), fresh.unresolvedParams)
    }

    @Test
    fun `券A_isNotEmpty_填ali_美元直替照常置闸`() {
        val result = preview(conditionXml, "selectByCondition")
        val fresh = rerender(
            result,
            cn.song.mybatisplus.visualizer.model.PreviewRerenderInput(
                params = mapOf("username" to "ali"),
                enabledConditionIds = setOf(condRow(result, "isNotEmpty").id),
            ),
        )
        assertTrue("USER_NAME LIKE '%ali%'：${fresh.sql}", fresh.sql.contains("USER_NAME LIKE '%ali%'"))
        assertTrue("`\$x\$` 直替照常触发注入闸门", fresh.hasDollarRawSegments)
    }

    @Test
    fun `券A_基线保留dollar站点_闸门不因空值丢失`() {
        val result = preview(conditionXml, "selectByCondition")
        assertTrue("`\$x\$` 站点存在即置闸（即使基线取值被引擎静默丢弃）", result.hasDollarRawSegments)
    }

    @Test
    fun `券A_iterate留空_降级路径与现状一致`() {
        val result = preview(conditionXml, "selectByIterate")
        val fresh = rerender(result, cn.song.mybatisplus.visualizer.model.PreviewRerenderInput())
        assertEquals("iterate 取值非集合 ⇒ 语句级降级（顶层文本节点），与无参基线现状一致", "SELECT ID, USER_NAME FROM T_USER", fresh.sql.trim())
        assertNotNull("降级必须有可见告警", fresh.warning)
    }

    @Test
    fun `券A_静态语句_行清单在但重渲染SQL不变`() {
        val xml = "<sqlMap><select id=\"s\">SELECT A FROM T WHERE B = #b:VARCHAR#</select></sqlMap>"
        val result = preview(xml, "s")
        assertEquals(listOf("b"), result.ibatisParamModel!!.placeholderRows.map { it.name })
        val fresh = rerender(result, cn.song.mybatisplus.visualizer.model.PreviewRerenderInput(params = mapOf("b" to "X")))
        assertEquals("静态路径渲染结果与基线一致（值只影响回填）", result.sql, fresh.sql)
    }
}
