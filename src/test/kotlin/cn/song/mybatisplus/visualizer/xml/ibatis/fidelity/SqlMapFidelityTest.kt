package cn.song.mybatisplus.visualizer.xml.ibatis.fidelity

import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisStatementKind
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapParser
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapSqlRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iBatis 2.x 静态渲染 **golden oracle 保真对照**（T-Ibatis2Support 券 B 验收的唯一可信手段）。
 *
 * 口径：**同一份 sqlMap + 同一组参数 → 我方渲染结果与 iBatis 2.3.4.726 真引擎 `getSql` 输出逐字节相等**。
 * 真引擎不连库（见 [IbatisOracle]），断言一律用 `assertEquals` 直接比全文——
 * **不许「归一化空白」后再比**，否则 `open`/`prepend` 不补空格这条保真性就失去了判别力。
 *
 * 覆盖域（对齐派出要求的 11 类形态）：`<dynamic prepend>` 的 prepend 消费、14 个条件标签、
 * `compareProperty` vs `compareValue`、`<iterate>` open/conjunction/close 与 `#ids[]#`、
 * `$` 后置替换、`##`/`$$` 转义、两段/三段占位符、`parameterMap` 分支（`#` 不转 `?`）、
 * `nullValue` 哨兵、`<sql>/<include>`（同文件 + 带 namespace 的跨引用）、`<selectKey>` pre/post。
 */
class SqlMapFidelityTest {

    private val parser = SqlMapParser()
    private val renderer = SqlMapSqlRenderer()

    @Test
    fun `全覆盖形态与真引擎逐字节一致`() {
        val sqlMap = sqlMapFixture()
        val parsed = parser.parse(sqlMap)
        assertTrue("对照夹具本身不应产生降级告警，实际：${parsed.warnings}", parsed.warnings.isEmpty())

        val cases = listOf(
            // 两段/三段占位符 + <sql>/<include>（同文件 + 带 namespace 的跨引用 + namespace 回落）
            Case("staticTwo", mapOf("orgId" to "A001", "orgName" to "Org")),
            // 三段：nullValue 内可再含 ':'（token >= 5 的拼接口径）
            Case("staticThree", mapOf("orgId" to "A001", "orgName" to "Org")),
            // 高级语法（无冒号走这一支）
            Case("newSyntax", mapOf("id" to 7, "name" to "N")),
            // `##` → 字面量 `#`
            Case("hashEscape", mapOf("c" to "C1")),
            // `$$` → 字面量 `$`，配 `$x$` 直替
            Case("dollarEscape", mapOf("c" to "C1")),
            // `$` 在动态标签之后替换
            Case("dollarAfterDynamic", mapOf("tableName" to "T_ORG", "orgId" to "A001")),
            // <dynamic prepend> 吃掉第一个「有 prepend」的子标签 prepend
            Case("dynPrependConsume", mapOf("a" to 1, "b" to 2)),
            // marker 存活到第一个带 prepend 的子标签（无 prepend 的子标签不消耗 marker）
            Case("dynPrependMarkerSurvives", mapOf("a" to 1, "b" to 2)),
            // 14 个条件标签：全真 / 全假 / 缺属性三档
            Case("cond14", mapOf("pNull" to "x", "pEmpty" to listOf("a"), "pAvail" to 1, "n" to 1, "x" to 5, "y" to 5)),
            Case("cond14", mapOf("pEmpty" to emptyList<String>(), "n" to 7, "x" to 5, "y" to 6)),
            Case("cond14", mapOf("pNull" to null, "pEmpty" to "", "n" to 0, "x" to "5", "y" to "5")),
            // isParameterPresent / isNotParameterPresent：非空参数对象 / 空 Map / null
            Case("paramPresent", mapOf("k" to 1)),
            Case("paramPresent", emptyMap<String, Any>()),
            Case("paramPresent", null),
            // <iterate> open/conjunction/close + `#ids[]#`
            Case("iterBasic", mapOf("ids" to listOf("X1", "X2", "X3"))),
            Case("iterBasic", mapOf("ids" to emptyList<String>())),
            // 嵌套 iterate（property="a[].b"）
            Case(
                "iterNested",
                mapOf(
                    "orConditions" to listOf(
                        mapOf("conditions" to listOf("c1", "c2")),
                        mapOf("conditions" to listOf("c3")),
                    ),
                ),
            ),
            // iterate + dynamic prepend + removeFirstPrepend="iterate"
            Case("iterRemoveFirst", mapOf("ids" to listOf(1, 2, 3))),
            // parameterMap 分支：`#` 不转 `?`（手写 ? 生效），并核对 nullValue 哨兵绑定
            Case("pmInsert", mapOf("orgId" to "A001", "orgName" to "N/A"), captureBinds = true),
            // 手写 ? + parameterMap（这类语句真正生效的是手写 ?）
            Case("pmHandWritten", mapOf("orgId" to "A001", "orgName" to "Org")),
        )

        val checked = LinkedHashSet<String>()
        for (case in cases) {
            val oracle = IbatisOracle.render(sqlMap, case.id, case.params, captureBinds = case.captureBinds)
            val statement = parsed.statementById(case.id)
            assertNotNull("我方解析器未产出语句 ${case.id}", statement)
            val ours = renderer.render(statement!!, case.params)

            assertEquals("SQL 与真引擎不一致：${case.id} / ${case.label}", oracle.sql, ours.sql)
            assertEquals("参数名序列不一致：${case.id}", oracle.paramProperties, ours.paramNames)
            assertEquals("jdbcType 序列不一致：${case.id}", oracle.paramJdbcTypes, ours.paramJdbcTypes)
            assertEquals("nullValue 序列不一致：${case.id}", oracle.paramNullValues, ours.paramNullValues)
            if (case.captureBinds) {
                val oracleNullIndices = oracle.bindCalls.withIndex()
                    .filter { it.value.startsWith("setNull") }
                    .map { it.index }
                val ourNullIndices = ours.nullBindFlags.withIndex().filter { it.value }.map { it.index }
                assertEquals("nullValue 哨兵命中位置与真引擎 setNull 不一致：${case.id}", oracleNullIndices, ourNullIndices)
                assertTrue("应至少有一处 setNull 记录（否则该用例无判别力）", oracle.bindCalls.any { it.startsWith("setNull") })
            }
            if (ours.paramNames.isNotEmpty()) checked.add("${case.id}:params")
            checked.add(case.id)
        }

        assertTrue("覆盖域应包含 parameterMap 分支", checked.contains("pmInsert"))
        assertTrue("覆盖域应包含 iterate", checked.contains("iterBasic"))
    }

    /** `<selectKey>` 的 pre/post 顺序（位置推断 + `type` 显式覆盖）与真引擎一致。 */
    @Test
    fun `selectKey 顺序与 SQL 与真引擎一致`() {
        val sqlMap = sqlMapFixture()
        val parsed = parser.parse(sqlMap)
        val expectations = mapOf(
            // type="pre" 显式
            "preKey" to false,
            // type="post" 显式
            "postKey" to true,
            // 无 type，selectKey 在 SQL 之前 → 位置推断为 pre
            "inferredPre" to false,
            // 无 type，selectKey 在 SQL 之后 → 位置推断为 post
            "inferredPost" to true,
        )
        for ((insertId, expectedRunAfter) in expectations) {
            val statement = parsed.statementById(insertId)
            assertNotNull("我方解析器未产出语句 $insertId", statement)
            assertEquals(IbatisStatementKind.INSERT, statement!!.kind)
            assertEquals("$insertId 应有且仅有一个 selectKey", 1, statement.selectKeys.size)
            val ourSelectKey = statement.selectKeys.first()
            assertEquals("runAfterSql 不符：$insertId", expectedRunAfter, ourSelectKey.runAfterSql)

            val oracle = IbatisOracle.renderSelectKey(sqlMap, insertId, mapOf("orgId" to "A001"))
            assertEquals("selectKey SQL 不一致：$insertId", oracle.sql, renderer.renderSelectKey(ourSelectKey).sql)
            assertEquals("selectKey runAfterSql 与引擎不一致：$insertId", oracle.runAfterSql, ourSelectKey.runAfterSql)
        }
    }

    /** 静态路径与动态路径的 `Sql` 实现类不同（`StaticSql` vs `DynamicSql`），确认对照的是两条链。 */
    @Test
    fun `静态与动态两条链都被对照覆盖`() {
        val sqlMap = sqlMapFixture()
        val impls = setOf("staticTwo", "dynPrependConsume", "iterBasic", "dollarAfterDynamic")
            .map { IbatisOracle.render(sqlMap, it, mapOf("a" to 1, "b" to 2, "ids" to listOf("X1"))) }
            .map { it.sqlImpl }
            .toSet()
        assertTrue("对照集应同时触及 StaticSql/SimpleDynamicSql 与 DynamicSql，实际：$impls", impls.size >= 2)
    }

    // ------------------------------------------------------------------ 夹具

    private data class Case(
        val id: String,
        val params: Any?,
        val captureBinds: Boolean = false,
    ) {
        val label: String get() = if (params == null) "params=null" else params.toString()
    }

    /** 把 `~D~` 替换成 `$`（Kotlin 字符串模板会吃掉裸 `$`）。 */
    private fun fx(text: String): String = text.replace("~D~", "$")

    @Suppress("LongMethod")
    private fun sqlMapFixture(): String = fx(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE sqlMap PUBLIC "-//iBATIS.com//DTD SQL Map 2.0//EN" "http://ibatis.apache.org/dtd/sql-map-2.dtd">
        <sqlMap namespace="demo">

          <parameterMap id="pmOrg" class="java.util.HashMap">
            <parameter property="orgId" jdbcType="VARCHAR"/>
            <parameter property="orgName" jdbcType="VARCHAR" nullValue="N/A"/>
          </parameterMap>

          <sql id="baseCols">ORG_ID, ORG_NAME</sql>
          <sql id="demo.tableName">T_ORG</sql>

          <select id="staticTwo">SELECT <include refid="baseCols"/> FROM <include refid="tableName"/> WHERE ORG_ID = #orgId# AND NAME = #orgName:VARCHAR#</select>
          <select id="staticThree">SELECT * FROM T WHERE ORG_ID = #orgId:VARCHAR:N/A# AND NAME = #orgName:VARCHAR:a:b#</select>
          <select id="newSyntax">SELECT * FROM T WHERE ID = #id,jdbcType=INTEGER,numericScale=0# AND NAME = #name,javaType=string,jdbcType=VARCHAR,mode=IN#</select>
          <select id="hashEscape">SELECT A ## B FROM T WHERE C = #c#</select>
          <select id="dollarEscape">SELECT A ~D~~D~ B FROM T WHERE C = ~D~c~D~</select>
          <select id="dollarAfterDynamic">SELECT * FROM ~D~tableName~D~ WHERE 1=1<isNotNull property="orgId" prepend=" AND ">ORG_ID = #orgId#</isNotNull></select>

          <select id="dynPrependConsume">SELECT * FROM T_ORG WHERE 1=1<dynamic prepend=" AND "><isNotNull property="a" prepend="DROPPED">ORG_ID = #a#</isNotNull><isNotNull property="b" prepend="ZZZ">NAME = #b#</isNotNull></dynamic></select>
          <select id="dynPrependMarkerSurvives">SELECT * FROM T_ORG WHERE 1=1<dynamic prepend=" WHERE "><isNotNull property="a">A = #a#</isNotNull><isNotNull property="b" prepend="AND-DROPPED">B = #b#</isNotNull></dynamic></select>

          <select id="cond14">SELECT X FROM T WHERE<isNull property="pNull" prepend=" [isNull]">N</isNull><isNotNull property="pNull" prepend=" [isNotNull]">N</isNotNull><isEmpty property="pEmpty" prepend=" [isEmpty]">N</isEmpty><isNotEmpty property="pEmpty" prepend=" [isNotEmpty]">N</isNotEmpty><isPropertyAvailable property="pAvail" prepend=" [isPropertyAvailable]">N</isPropertyAvailable><isNotPropertyAvailable property="pAvail" prepend=" [isNotPropertyAvailable]">N</isNotPropertyAvailable><isEqual property="n" compareValue="1" prepend=" [isEqual]">N</isEqual><isNotEqual property="n" compareValue="1" prepend=" [isNotEqual]">N</isNotEqual><isGreaterThan property="n" compareValue="0" prepend=" [isGreaterThan]">N</isGreaterThan><isGreaterEqual property="n" compareValue="1" prepend=" [isGreaterEqual]">N</isGreaterEqual><isLessThan property="n" compareValue="5" prepend=" [isLessThan]">N</isLessThan><isLessEqual property="n" compareValue="1" prepend=" [isLessEqual]">N</isLessEqual><isParameterPresent prepend=" [isParameterPresent]">N</isParameterPresent><isNotParameterPresent prepend=" [isNotParameterPresent]">N</isNotParameterPresent><isEqual property="x" compareProperty="y" prepend=" [compareProperty]">N</isEqual></select>

          <select id="paramPresent">SELECT 1<isParameterPresent prepend=" [PP]">X</isParameterPresent><isNotParameterPresent prepend=" [NPP]">Y</isNotParameterPresent> FROM D</select>

          <select id="iterBasic">SELECT * FROM T_ORG WHERE <iterate property="ids" open="(" close=")" conjunction=" OR ">ORG_ID = #ids[]#</iterate></select>
          <select id="iterNested">SELECT * FROM T WHERE <iterate property="orConditions" open="(" close=")" conjunction=" OR "><iterate property="orConditions[].conditions" open="(" close=")" conjunction=" AND ">C = #orConditions[].conditions[]#</iterate></iterate></select>
          <select id="iterRemoveFirst">SELECT * FROM T<dynamic prepend=" WHERE "><iterate property="ids" prepend=" ID IN " open="(" close=")" conjunction="," removeFirstPrepend="iterate">#ids[]#</iterate></dynamic></select>

          <insert id="pmInsert" parameterMap="pmOrg">INSERT INTO T_ORG (ORG_ID, NAME) VALUES (#orgId#, #orgName#)</insert>
          <insert id="pmHandWritten" parameterMap="pmOrg">INSERT INTO T_ORG (ORG_ID, NAME) VALUES (?, ?)</insert>

          <insert id="preKey">INSERT INTO T_ORG (ORG_ID) VALUES (#orgId#)<selectKey keyProperty="newId" resultClass="java.lang.Integer" type="pre">SELECT MAX(ID)+1 FROM T_ORG</selectKey></insert>
          <insert id="postKey"><selectKey keyProperty="newId" resultClass="java.lang.Integer" type="post">SELECT MAX(ID)+1 FROM T_ORG</selectKey>INSERT INTO T_ORG (ORG_ID) VALUES (#orgId#)</insert>
          <insert id="inferredPre"><selectKey keyProperty="newId" resultClass="java.lang.Integer">SELECT MAX(ID)+1 FROM T_ORG</selectKey>INSERT INTO T_ORG (ORG_ID) VALUES (#orgId#)</insert>
          <insert id="inferredPost">INSERT INTO T_ORG (ORG_ID) VALUES (#orgId#)<selectKey keyProperty="newId" resultClass="java.lang.Integer">SELECT MAX(ID)+1 FROM T_ORG</selectKey></insert>
        </sqlMap>
        """.trimIndent(),
    )
}
