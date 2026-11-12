package cn.song.mybatisplus.visualizer.xml

import cn.song.mybatisplus.visualizer.ui.XmlPreviewService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-MbgExamplePreview：MBG 生成器 Example 三件套（selectByExample + Example_Where_Clause + trim）
 * 在模拟参数下的预览产物。
 *
 * 覆盖计划 §五 的验收项：
 *  - R1 `${}` 空值占位（预览开、renderer 默认关）；
 *  - R2 foreach 迭代分隔（不出现 `andand` / `)or(`）；
 *  - R4 choose 互斥组默认只开含参数分支；
 *  - R5 MBG Example profile（distinct / orderByClause 不展开、集合按 1 项）；
 *  - R6 告警分帧（基线渲染错误不进 result.warning）。
 */
class MbgExamplePreviewTest {

    private fun compact(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    /** MBG 生成器的固定 XML 形态（用户报障现场）。 */
    private val mapper = """
        <mapper namespace="n">
          <sql id="Base_Column_List">
            CUST_ID, STAFF_LOGIN
          </sql>
          <sql id="Example_Where_Clause">
            <where>
              <foreach collection="oredCriteria" item="criteria" separator="or">
                <if test="criteria.valid">
                  <trim prefix="(" prefixOverrides="and" suffix=")">
                    <foreach collection="criteria.criteria" item="criterion">
                      <choose>
                        <when test="criterion.noValue">
                          and ${'$'}{criterion.condition}
                        </when>
                        <when test="criterion.singleValue">
                          and ${'$'}{criterion.condition} #{criterion.value}
                        </when>
                        <when test="criterion.betweenValue">
                          and ${'$'}{criterion.condition} #{criterion.value} and #{criterion.secondValue}
                        </when>
                        <when test="criterion.listValue">
                          and ${'$'}{criterion.condition}
                          <foreach close=")" collection="criterion.value" item="listItem" open="(" separator=",">
                            #{listItem}
                          </foreach>
                        </when>
                      </choose>
                    </foreach>
                  </trim>
                </if>
              </foreach>
            </where>
          </sql>
          <select id="selectByExample" parameterType="x.CtCustmanagerRelExample">
            select
            <if test="distinct">
              distinct
            </if>
            'false' as QUERYID,
            <include refid="Base_Column_List" />
            from CT_CUSTMANAGER_REL
            <if test="_parameter != null">
              <include refid="Example_Where_Clause" />
            </if>
            <if test="orderByClause != null">
              order by ${'$'}{orderByClause}
            </if>
          </select>
        </mapper>
    """.trimIndent()

    private fun model(profile: XmlPreviewProfile? = null): XmlPreviewModel {
        val parsed = XmlMapperParser().parse(mapper)
        return XmlPreviewModel.from(
            parsed.statements.first { it.id == "selectByExample" },
            parsed.errors,
            profile = profile,
        )
    }

    // ---------------- M2：调用点反推的元素级模拟值 ----------------

    @Test
    fun `M2元素级模拟值_condition取到真实值且无占位`() {
        val elements = mapOf(
            "oredCriteria" to listOf(
                MbgCriterionMock.criteriaOf(listOf(MbgCriterionMock("cust_id =", listOf("42")))),
            ),
            "criteria.criteria" to listOf(MbgCriterionMock("cust_id =", listOf("42")).toMockObject()),
        )
        val parsed = XmlMapperParser().parse(mapper)
        val m = XmlPreviewModel.from(
            parsed.statements.first { it.id == "selectByExample" },
            parsed.errors,
            profile = XmlPreviewProfile.MBG_EXAMPLE,
            collectionElements = elements,
        )
        val sql = compact(m.renderSql().sql)
        assertEquals(
            "select 'false' as QUERYID, CUST_ID, STAFF_LOGIN from CT_CUSTMANAGER_REL WHERE ( cust_id = ? )",
            sql,
        )
        assertFalse("拿到真实 condition 后不应再有占位：$sql", sql.contains("{{"))
    }

    @Test
    fun `M2方法名后缀到分支类别`() {
        assertEquals(MbgCriterionKind.NO_VALUE, MbgCriterionMock.kindOf("andCustIdIsNull"))
        assertEquals(MbgCriterionKind.NO_VALUE, MbgCriterionMock.kindOf("andCustIdIsNotNull"))
        assertEquals(MbgCriterionKind.LIST_VALUE, MbgCriterionMock.kindOf("andCustIdIn"))
        assertEquals(MbgCriterionKind.LIST_VALUE, MbgCriterionMock.kindOf("andCustIdNotIn"))
        assertEquals(MbgCriterionKind.BETWEEN_VALUE, MbgCriterionMock.kindOf("andCustIdBetween"))
        assertEquals(MbgCriterionKind.BETWEEN_VALUE, MbgCriterionMock.kindOf("andCustIdNotBetween"))
        assertEquals(MbgCriterionKind.SINGLE_VALUE, MbgCriterionMock.kindOf("andCustIdEqualTo"))
        assertEquals(MbgCriterionKind.SINGLE_VALUE, MbgCriterionMock.kindOf("andCustIdLike"))
    }

    @Test
    fun `M2分支布尔值与参数个数一致`() {
        val noValue = MbgCriterionMock("cust_id is null", emptyList(), MbgCriterionKind.NO_VALUE).toMockObject()
        assertEquals(true, noValue["noValue"])
        assertEquals(null, noValue["value"])

        val single = MbgCriterionMock("cust_id =", listOf("42")).toMockObject()
        assertEquals(true, single["singleValue"])
        assertEquals("42", single["value"])

        val between = MbgCriterionMock(
            "cust_id between", listOf("1", "9"), MbgCriterionKind.BETWEEN_VALUE,
        ).toMockObject()
        assertEquals(true, between["betweenValue"])
        assertEquals("1", between["value"])
        assertEquals("9", between["secondValue"])

        val list = MbgCriterionMock(
            "cust_id in", listOf("1", "2"), MbgCriterionKind.LIST_VALUE,
        ).toMockObject()
        assertEquals(true, list["listValue"])
        assertEquals(listOf("1", "2"), list["value"])
    }

    // ---------------- T-MbgPreviewParityAndOpenPerf P1/P3：多 Criterion 的迭代间粘连 ----------------

    /**
     * 用户现场是 4 个 Criterion（我的金样只用 1 个 ⇒ 只有一次迭代，看不到相邻拼接）。
     *
     * R1：本引擎的 trim 缓冲里放的是**渲染前文本** `#{criterion.value}`（收尾字符是 `}`），
     * `#{}` → `?` 的替换在渲染最后一步才做（`XmlSqlRenderer.parseParameterMappings`）——
     * 补空格判据若漏掉 `}`（或只写渲染后的 `?`），两轮迭代就拼成 `?and`：那是**语法错的 SQL**，
     * 比当年的 `andand` 更隐蔽。
     */
    @Test
    fun `多Criterion_相邻迭代不得粘成问号加and`() {
        val criteria = listOf(
            MbgCriterionMock("CUSTOMERNUMBER =", listOf("3")),
            MbgCriterionMock("UNIT_ID =", listOf("1")),
            MbgCriterionMock("CUST_ROLE_CODE =", listOf("2")),
            MbgCriterionMock("CUST_ID =", listOf("4")),
        )
        val parsed = XmlMapperParser().parse(mapper)
        val m = XmlPreviewModel.from(
            parsed.statements.first { it.id == "selectByExample" },
            parsed.errors,
            profile = XmlPreviewProfile.MBG_EXAMPLE,
            collectionElements = mapOf(
                "oredCriteria" to listOf(MbgCriterionMock.criteriaOf(criteria)),
                "criteria.criteria" to criteria.map { it.toMockObject() },
            ),
        )
        val sql = compact(m.renderSql().sql)
        assertFalse("不得粘成 ?and（语法错 SQL）：$sql", sql.contains("?and"))
        assertTrue(
            "四个条件之间应各有一个空格：$sql",
            sql.contains("CUSTOMERNUMBER = ? and UNIT_ID = ? and CUST_ROLE_CODE = ? and CUST_ID = ?"),
        )
    }

    /** T-MbgPreviewParityAndOpenPerf P2：操作符与内层 foreach 的 `(` 之间要有空格（用户态 listValue）。 */
    @Test
    fun `用户态_listValue分支_操作符与左括号不粘连`() {
        val criteria = listOf(
            MbgCriterionMock("CUSTOMERNUMBER =", listOf("3"), MbgCriterionKind.LIST_VALUE),
        )
        val parsed = XmlMapperParser().parse(mapper)
        val m = XmlPreviewModel.from(
            parsed.statements.first { it.id == "selectByExample" },
            parsed.errors,
            profile = XmlPreviewProfile.MBG_EXAMPLE,
            collectionElements = mapOf(
                "oredCriteria" to listOf(MbgCriterionMock.criteriaOf(criteria)),
                "criteria.criteria" to criteria.map { it.toMockObject() },
            ),
        )
        val state = XmlPreviewState(
            enabledIds = setOf("_parameter != null", "criteria.valid", "criterion.listValue"),
            forEachCounts = mapOf("oredCriteria" to 1, "criteria.criteria" to 1, "criterion.value" to 1),
        )
        val sql = compact(m.renderSql(state).sql)
        assertFalse("操作符与左括号不得粘成 `=(`：$sql", sql.contains("=("))
        assertTrue("应为 `CUSTOMERNUMBER = (?)`：$sql", sql.contains("CUSTOMERNUMBER = (?)"))
    }

    /**
     * P2 的边界：`6212f24e0` 用两例 CI 红换来的「紧凑」约束不能被我这次的补空格规则破坏。
     *
     * **夹具必须在 `<where>`（trim）体内**——该约束管的是 trim 缓冲的拼接；放在 select 体内会走
     * 根上下文的 `StringJoiner(" ")`，那层本来就按段补空格，测不出任何东西（同
     * `pitfall-reverse-probe-fixture-layer-mismatch` 的层级错位）。
     */
    @Test
    fun `trim体内_IN列表与前缀函数保持紧凑`() {
        val m = XmlPreviewModel.from(
            XmlMapperParser().parse(
                """
                <mapper namespace="n">
                  <select id="s1">SELECT count(*) FROM t
                    <where>
                      id IN
                      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                    </where>
                  </select>
                </mapper>
                """.trimIndent(),
            ).statements.single(),
            emptyList(),
        )
        val sql = compact(m.renderSql().sql)
        assertTrue("IN 列表在 trim 体内保持紧凑：$sql", sql.contains("IN (?,?)"))
        assertTrue("前缀函数不被撑开：$sql", sql.contains("count(*)"))
    }

    // ---------------- R1 + R4 + R5：默认档下的整体产物 ----------------

    @Test
    fun `MBG形态_默认产物_结构正确且缺段可见`() {
        val rendered = model(XmlPreviewProfile.MBG_EXAMPLE).renderSql()
        assertEquals(
            "select 'false' as QUERYID, CUST_ID, STAFF_LOGIN from CT_CUSTMANAGER_REL " +
                "WHERE ( {{criterion.condition}} ? )",
            compact(rendered.sql),
        )
        // 缺段必须留痕：占位来自 ${criterion.condition} 求值为空
        assertTrue(rendered.errors.any { it.contains("criterion.condition") && it.contains("求值为空") })
    }

    @Test
    fun `无profile时保持旧默认_但choose组仍只开一个分支`() {
        val rendered = model().renderSql()
        val sql = compact(rendered.sql)
        // R5 不生效：distinct 与 orderByClause 仍按启发式展开
        assertTrue("无 profile 时应保留旧默认（distinct 展开）：$sql", sql.contains("select distinct"))
        assertTrue("无 profile 时应保留旧默认（order by 占位）：$sql", sql.contains("order by 1"))
        // R4 是通用规则，与 profile 无关：choose 只有一个分支进 SQL
        assertFalse("choose 互斥组不应同时展开多个分支：$sql", sql.contains("and {{criterion.condition}} ? and"))
    }

    // ---------------- R4：choose 互斥组 ----------------

    @Test
    fun `choose互斥组_默认只启用含参数的分支`() {
        val m = model(XmlPreviewProfile.MBG_EXAMPLE)
        assertEquals(1, m.chooseGroups.size)
        assertEquals(
            listOf("criterion.noValue", "criterion.singleValue", "criterion.betweenValue", "criterion.listValue"),
            m.chooseGroups.single().branchIds,
        )
        // 含 #{} 的分支里最先出现者 = singleValue（noValue 无参数，betweenValue/listValue 在后）
        assertEquals("criterion.singleValue", m.chooseGroups.single().defaultBranchId)
        val enabled = m.defaultState().enabledIds
        assertTrue(enabled.contains("criterion.singleValue"))
        assertFalse(enabled.contains("criterion.noValue"))
        assertFalse(enabled.contains("criterion.betweenValue"))
        assertFalse(enabled.contains("criterion.listValue"))
    }

    /**
     * T-MbgPreviewParityAndOpenPerf (d)：M2 命中时默认分支跟随**反推到的真实 kind**，
     * 而非启发式（「含 `#{}` 的分支里最先出现者」＝ `singleValue`）。
     * 这里给 M2 一个 `listValue` 语义的 criterion ⇒ 默认必须落到 `listValue`。
     */
    @Test
    fun `M2命中_choose组默认分支按真实kind取`() {
        val criteria = listOf(
            MbgCriterionMock("CUSTOMERNUMBER in", listOf("1", "2"), MbgCriterionKind.LIST_VALUE),
        )
        val parsed = XmlMapperParser().parse(mapper)
        val m = XmlPreviewModel.from(
            parsed.statements.first { it.id == "selectByExample" },
            parsed.errors,
            profile = XmlPreviewProfile.MBG_EXAMPLE,
            collectionElements = mapOf(
                "oredCriteria" to listOf(MbgCriterionMock.criteriaOf(criteria)),
                "criteria.criteria" to criteria.map { it.toMockObject() },
            ),
        )
        val enabled = m.defaultState().enabledIds
        assertTrue("M2 说是 listValue ⇒ 默认应选 listValue：$enabled", enabled.contains("criterion.listValue"))
        assertFalse("不应再落到启发式的 singleValue：$enabled", enabled.contains("criterion.singleValue"))
    }

    @Test
    fun `M2未命中_仍走启发式默认分支`() {
        val enabled = model(XmlPreviewProfile.MBG_EXAMPLE).defaultState().enabledIds
        assertTrue("无 M2 元素时应维持启发式 singleValue：$enabled", enabled.contains("criterion.singleValue"))
        assertFalse(enabled.contains("criterion.listValue"))
    }

    // ---------------- R2：迭代分隔 ----------------

    @Test
    fun `foreach多次迭代_不产生单词粘连`() {
        val m = XmlPreviewModel.from(
            XmlMapperParser().parse(
                """
                <mapper namespace="n">
                  <select id="s1">
                    SELECT * FROM t WHERE id IN
                    <foreach collection="ids" item="id" open="(" separator="or" close=")">and x = #{id}</foreach>
                  </select>
                </mapper>
                """.trimIndent(),
            ).statements.single(),
            emptyList(),
        )
        val sql = compact(m.renderSql().sql)
        assertFalse("不得出现 andand 粘连：$sql", sql.contains("andand"))
        assertFalse("不得出现 )or( 粘连：$sql", sql.contains(")or("))
        assertTrue("separator 前后应由空格分隔：$sql", sql.contains(" or "))
    }

    @Test
    fun `trim体内多次迭代紧贴_不产生单词粘连`() {
        // 反向探针的落点：上面那条用例在根上下文，appendSql 自带分隔，看不见 trim 缓冲的粘连。
        // 真实缺陷形态是 `<where>`/`<trim>` 体内 foreach 展开两轮——迭代体被 trim 成紧凑段后
        // 交给纯 append 的 filtered 缓冲，历史版本此处产出 `1or` / `1and`。
        val m = XmlPreviewModel.from(
            XmlMapperParser().parse(
                """
                <mapper namespace="n">
                  <select id="s1">
                    SELECT * FROM t
                    <where>
                      <foreach collection="cs" item="c" separator="or">
                        and c = 1
                      </foreach>
                    </where>
                  </select>
                </mapper>
                """.trimIndent(),
            ).statements.single(),
            emptyList(),
            profile = XmlPreviewProfile(forEachCount = 2),
        )
        val sql = compact(m.renderSql().sql)
        assertFalse("trim 体内不得出现 1or 粘连：$sql", sql.contains("1or"))
        assertFalse("trim 体内不得出现 1and 粘连：$sql", sql.contains("1and"))
        assertTrue("两段之间应由空格分隔：$sql", sql.contains(" or "))
    }

    // ---------------- R5：profile 的两项抑制 ----------------

    @Test
    fun `示例默认档_抑制distinct与orderByClause并只展开一项`() {
        val state = model(XmlPreviewProfile.MBG_EXAMPLE).defaultState()
        assertFalse(state.enabledIds.contains("distinct"))
        assertFalse(state.enabledIds.contains("orderByClause != null"))
        assertTrue(state.forEachCounts.values.all { it == 1 })
    }

    // ---------------- R6：告警分帧 ----------------

    @Test
    fun `告警分帧_基线渲染错误不进入result_warning`() {
        val m = model(XmlPreviewProfile.MBG_EXAMPLE)
        // 默认（注解链口径）：基线渲染错误并入 warning
        val withBaseline = XmlPreviewService.toResult(m)
        assertTrue(withBaseline.warning.orEmpty().contains("求值为空"))
        // 面板口径：只保留解析期/静态告警，本轮错误由 xmlErrors 单独显示
        val panel = XmlPreviewService.toResult(m, includeRenderErrors = false)
        assertFalse(panel.warning.orEmpty().contains("求值为空"))
    }

    // ---------------- 识别入口的 null 分支 ----------------

    @Test
    fun `Example识别_无project时返回null不改变默认`() {
        assertEquals(null, XmlPreviewService.resolveExampleProfile(null, "x.CtCustmanagerRelExample", "n"))
        assertEquals(null, XmlPreviewService.resolveExampleProfile(null, null, "n"))
    }

    /**
     * D5（真机 2026-11-12 复验）：M2 元素带**逐条真实 kind** 时，choose 不能再按「组内只开一个」强制。
     *
     * 混 kind 的判据点：`tenant_id =`（singleValue）+ `create_time between`（betweenValue）同批注入时，
     * 旧默认把整组强制成 singleValue ⇒ between 走单值模板渲染成 `between ?`，第二个占位（secondValue）
     * 连字面 `and` 一起丢掉，真机上就是 `WHERE (create_time BETWEEN '11' AND tenant_id = ?)`。
     * 期望：三条占位、between 出 `? and ?`。
     */
    @Test
    fun `M2混kind_每条criterion按自身分支渲染`() {
        fun render(vararg criteria: MbgCriterionMock): String {
            val list = criteria.toList()
            val parsed = XmlMapperParser().parse(mapper)
            val m = XmlPreviewModel.from(
                parsed.statements.first { it.id == "selectByExample" },
                parsed.errors,
                profile = XmlPreviewProfile.MBG_EXAMPLE,
                collectionElements = mapOf(
                    "oredCriteria" to listOf(MbgCriterionMock.criteriaOf(list)),
                    "criteria.criteria" to list.map { it.toMockObject() },
                ),
            )
            return compact(m.renderSql().sql)
        }

        val single = MbgCriterionMock("tenant_id =", listOf("tenantId"))
        val between = MbgCriterionMock("create_time between", listOf("startTime", "endTime"), MbgCriterionKind.BETWEEN_VALUE)

        val betweenFirst = render(between, single)
        assertTrue("between 必须保留两个占位：$betweenFirst", betweenFirst.contains("create_time between ? and ?"))
        assertTrue("单值条件仍是单占位：$betweenFirst", betweenFirst.contains("tenant_id = ?"))
        assertEquals("两条条件共三个占位：$betweenFirst", 3, betweenFirst.count { it == '?' })

        val singleFirst = render(single, between)
        assertTrue("顺序不影响：$singleFirst", singleFirst.contains("create_time between ? and ?"))
        assertEquals("两条条件共三个占位：$singleFirst", 3, singleFirst.count { it == '?' })

        val onlyBetween = render(between)
        assertEquals("单条 between 仍是两个占位：$onlyBetween", 2, onlyBetween.count { it == '?' })
    }
}
