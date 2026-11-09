package cn.song.mybatisplus.visualizer.xml.ibatis

import cn.song.mybatisplus.visualizer.model.PreviewRerenderInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数/条件行清单与 Map 装配单测（T-Ibatis2Gap 券 A）。
 *
 * 装配语义（本券钥匙）：**勾选 = 放 key，取消 = 移除 key**；`IbatisProbe` 对 Map 原生取值
 * （`containsKey` / `map[name]`），所以条件开关与值语义全部由 Map 装配承担，渲染器零改动。
 */
class IbatisParamModelTest {

    /** 对齐 demo `User.xml` 两条语句形态的夹具（条件四件套 + iterate + `$` 直替）。 */
    private val dollar = "${'$'}"
    private val sqlMap = """
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
            <select id="dollarOnly" parameterClass="java.util.Map">SELECT * FROM ${dollar}tableName${dollar} WHERE C = '${dollar}${dollar}'</select>
        </sqlMap>
    """.trimIndent()

    private fun parse(): IbatisSqlMap = SqlMapParser().parse(sqlMap)

    private fun statement(id: String): IbatisStatement = parse().statementById(id)!!

    private fun model(id: String): IbatisParamModel = IbatisParamModelBuilder.build(statement(id))

    // ------------------------------------------------------------------
    // 行清单
    // ------------------------------------------------------------------

    @Test
    fun `行清单_占位符与条件iterate美元行各归其位`() {
        val m = model("selectByCondition")
        assertEquals(
            "占位符行按 `#` 首现序去重收集",
            listOf("id", "status", "age"),
            m.placeholderRows.map { it.name },
        )
        assertEquals("jdbcType 提示透传", null, m.placeholderRows.first().jdbcType)
        assertEquals(
            "四个条件行按树序（iterate 子树外的全部 isXxx）",
            listOf("isNotNull", "isNotEmpty", "isEqual", "isGreaterThan"),
            m.conditionRows.map { it.kindTagName },
        )
        assertEquals("isEqual 的比较值进条件行（面板预填值输入）", "1", m.conditionRows[2].compareValue)
        assertTrue("iterate 行清单为空", m.iterateRows.isEmpty())
        assertEquals("\$ 直替站点行（\$\$ 转义不算站点）", listOf("username"), m.dollarRows.map { it.name })
        assertTrue(m.hasControls)
    }

    @Test
    fun `行清单_条件摘要形态`() {
        val m = model("selectByCondition")
        assertEquals("isNotNull: id", m.labelOf(m.conditionRows[0]))
        assertEquals("isEqual: status == 1", m.labelOf(m.conditionRows[2]))
        assertEquals("isGreaterThan: age > 18", m.labelOf(m.conditionRows[3]))
    }

    @Test
    fun `行清单_iterate行收集_集合属性与连接词`() {
        val m = model("selectByIterate")
        assertEquals(1, m.iterateRows.size)
        assertEquals("ids", m.iterateRows[0].property)
        assertEquals("OR", m.iterateRows[0].conjunction)
    }

    // ------------------------------------------------------------------
    // Map 装配
    // ------------------------------------------------------------------

    @Test
    fun `装配_全不勾_全不填_回退null等于无参基线`() {
        val m = model("selectByCondition")
        assertNull("空装配 = 无参基线（与现状逐字节一致）", IbatisParamModelBuilder.assembleParams(m, PreviewRerenderInput()))
    }

    @Test
    fun `装配_勾选即放key_值缺省回落compareValue`() {
        val m = model("selectByCondition")
        val isEqual = m.conditionRows.first { it.kindTagName == "isEqual" }
        val params = IbatisParamModelBuilder.assembleParams(
            m,
            PreviewRerenderInput(enabledConditionIds = setOf(isEqual.id)),
        ) as Map<*, *>
        assertEquals("勾上 isEqual ⇒ status 进 Map（值缺省 = compareValue）", "1", params["status"])
        assertEquals("只放勾选条件的 key", setOf("status"), params.keys)
    }

    @Test
    fun `装配_用户输入优先于compareValue_空串占位行不污染`() {
        val m = model("selectByCondition")
        val isGreaterThan = m.conditionRows.first { it.kindTagName == "isGreaterThan" }
        val params = IbatisParamModelBuilder.assembleParams(
            m,
            PreviewRerenderInput(params = mapOf("age" to "20"), enabledConditionIds = setOf(isGreaterThan.id)),
        ) as Map<*, *>
        assertEquals("用户输入的值优先", "20", params["age"])
    }

    @Test
    fun `装配_条件未勾_同名占位符值不放key`() {
        val m = model("selectByCondition")
        // isNotNull(id) 未勾 ⇒ 即使 #id# 填了值也不放 id key（条件开关主导，整段跳过）
        val params = IbatisParamModelBuilder.assembleParams(
            m,
            PreviewRerenderInput(params = mapOf("id" to "5")),
        )
        assertNull(params)
    }

    @Test
    fun `装配_勾选且同名占位符填值_共用一个输入值`() {
        val m = model("selectByCondition")
        val isNotNull = m.conditionRows.first { it.kindTagName == "isNotNull" }
        val params = IbatisParamModelBuilder.assembleParams(
            m,
            PreviewRerenderInput(params = mapOf("id" to "5"), enabledConditionIds = setOf(isNotNull.id)),
        ) as Map<*, *>
        assertEquals("isNotNull(id) 勾选 + #id# 填 5 ⇒ 共用同一值", "5", params["id"])
    }

    @Test
    fun `装配_iterate_值列表非空放List_留空不放`() {
        val m = model("selectByIterate")
        val filled = IbatisParamModelBuilder.assembleParams(
            m,
            PreviewRerenderInput(collections = mapOf("ids" to listOf("X1", "X2", "X3"))),
        ) as Map<*, *>
        assertEquals(listOf("X1", "X2", "X3"), filled["ids"])
        val empty = IbatisParamModelBuilder.assembleParams(m, PreviewRerenderInput())
        assertNull("留空 = 引擎降级路径（与无参基线现状一致）", empty)
    }

    @Test
    fun `装配_美元行_填了值才放`() {
        val m = model("dollarOnly")
        val filled = IbatisParamModelBuilder.assembleParams(
            m,
            PreviewRerenderInput(params = mapOf("tableName" to "T_USER")),
        ) as Map<*, *>
        assertEquals("T_USER", filled["tableName"])
        val notFilled = IbatisParamModelBuilder.assembleParams(m, PreviewRerenderInput())
        assertNull(notFilled)
    }

    @Test
    fun `装配_isParameterPresent勾选_空map也成立`() {
        val sql = "<sqlMap><select id=\"pp\">SELECT 1 FROM T<dynamic prepend=\"WHERE\"><isParameterPresent prepend=\"AND\">1 = 1</isParameterPresent></dynamic></select></sqlMap>"
        val st = SqlMapParser().parse(sql).statementById("pp")!!
        val m = IbatisParamModelBuilder.build(st)
        val params = IbatisParamModelBuilder.assembleParams(
            m,
            PreviewRerenderInput(enabledConditionIds = setOf(m.conditionRows.single().id)),
        )
        assertEquals("isParameterPresent 判 params != null ⇒ 空 map 也要传（不能退 null）", emptyMap<String, Any?>(), params)
    }

    @Test
    fun `parameterMap声明参数_进占位符行_装配后渲染静态路径不变`() {
        val sql = """
            <sqlMap>
                <parameterMap id="pm" class="java.util.Map">
                    <parameter property="orgId" jdbcType="VARCHAR"/>
                </parameterMap>
                <statement id="st" parameterMap="pm">UPDATE T SET A = #orgId# WHERE ID = ?</statement>
            </sqlMap>
        """.trimIndent()
        val st = SqlMapParser().parse(sql).statementById("st")!!
        val m = IbatisParamModelBuilder.build(st)
        assertEquals(listOf("orgId"), m.placeholderRows.map { it.name })
        val params = IbatisParamModelBuilder.assembleParams(m, PreviewRerenderInput(params = mapOf("orgId" to "A001"))) as Map<*, *>
        assertEquals("A001", params["orgId"])
        // 静态路径不吃参数：SQL 逐字不变（引擎守卫 `#` 原样保留），值只供面板回填
        val rendered = SqlMapSqlRenderer().render(st, params)
        assertTrue(rendered.sql.contains("#orgId#"))
    }

    @Test
    fun `装配后的Map_渲染器照常吃_条件求值与占位符对齐`() {
        val m = model("selectByCondition")
        val isNotNull = m.conditionRows.first { it.kindTagName == "isNotNull" }
        val isEqual = m.conditionRows.first { it.kindTagName == "isEqual" }
        val params = IbatisParamModelBuilder.assembleParams(
            m,
            PreviewRerenderInput(params = mapOf("id" to "5"), enabledConditionIds = setOf(isNotNull.id)),
        )!!
        val rendered = SqlMapSqlRenderer().render(statement("selectByCondition"), params)
        assertTrue("isNotNull(id) 勾选 ⇒ ID 段在", rendered.sql.contains("ID = ?"))
        assertFalse("isEqual(status) 未勾 ⇒ status key 不放，STATUS 段不得出现", rendered.sql.contains("STATUS = ?"))
        assertEquals(listOf("id"), rendered.paramNames)
    }

    @Test
    fun `空串值_isNotEmpty不成立_引擎语义不做伪装`() {
        val m = model("selectByCondition")
        val isNotEmpty = m.conditionRows.first { it.kindTagName == "isNotEmpty" }
        val params = IbatisParamModelBuilder.assembleParams(
            m,
            PreviewRerenderInput(params = mapOf("username" to ""), enabledConditionIds = setOf(isNotEmpty.id)),
        )!!
        val rendered = SqlMapSqlRenderer().render(statement("selectByCondition"), params)
        assertFalse("空串对 isNotEmpty 天然为 false（引擎语义），不得「帮」用户转 null 伪装成立", rendered.sql.contains("USER_NAME LIKE"))
    }
}
