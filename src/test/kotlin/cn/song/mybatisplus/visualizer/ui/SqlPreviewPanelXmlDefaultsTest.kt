package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.xml.MbgCriterionMock
import cn.song.mybatisplus.visualizer.xml.MbgCriterionMock.MbgCriterionKind
import cn.song.mybatisplus.visualizer.xml.MockObject
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel.XmlPreviewProfile
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-MbgPreviewParityAndOpenPerf P7：面板**初始勾选**必须与渲染同源。
 *
 * 缺陷形态（2026-11-12 真机初始态截图）：面板一打开就带着 `DISTINCT`、`ORDER BY 1`、
 * 四个互斥分支全勾。根因是 `SqlPreviewPanelResultView` 的初始化读 `view.defaultEnabled`
 * 朴素启发式（只判 `== null`），而 profile 抑制、choose 组归并、M2 真实 kind 默认三项都只在
 * `XmlPreviewModel.defaultState()` 里实现——两条口径分叉，模型侧测试（`MbgExamplePreviewTest`）
 * 因此全绿而面板实际不生效。
 *
 * 本用例是面板侧的唯一防线：断言初始勾选集合与 `defaultState().enabledIds` 完全一致。
 *
 * K2J 适配：{@code XmlPreviewProfile}/{@code MbgCriterionKind} 落嵌套类型（import 嵌套形态）；
 * {@code XmlPreviewModel.from} 具名实参 → 9 参规范形态位置实参；{@code PreviewResult} 具名实参 →
 * 20 参历史重载位置实参（xmlModel 在第 15 位）；面板门面 {@code showResult}/{@code clear}
 * 走 ResultViewKt/SqlViewKt 静态扩展。
 */
class SqlPreviewPanelXmlDefaultsTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    override fun tearDown() {
        // 面板持有编辑器，不显式释放会让平台报 "Editor ... hasn't been released"（Disposer 泄漏）
        runCatching { panel?.let { SqlPreviewPanelSqlViewKt.clear(it) } }
        panel = null
        super.tearDown()
    }

    private val mapper = """
        <mapper namespace="n">
          <sql id="Base_Column_List">CUST_ID, STAFF_LOGIN</sql>
          <sql id="Example_Where_Clause">
            <where>
              <foreach collection="oredCriteria" item="criteria" separator="or">
                <if test="criteria.valid">
                  <trim prefix="(" prefixOverrides="and" suffix=")">
                    <foreach collection="criteria.criteria" item="criterion">
                      <choose>
                        <when test="criterion.noValue">and ${'$'}{criterion.condition}</when>
                        <when test="criterion.singleValue">and ${'$'}{criterion.condition} #{criterion.value}</when>
                        <when test="criterion.betweenValue">and ${'$'}{criterion.condition} #{criterion.value} and #{criterion.secondValue}</when>
                        <when test="criterion.listValue">
                          and ${'$'}{criterion.condition}
                          <foreach close=")" collection="criterion.value" item="listItem" open="(" separator=",">#{listItem}</foreach>
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
            <if test="distinct">distinct</if>
            'false' as QUERYID,
            <include refid="Base_Column_List" />
            from CUSTMANAGER_REL
            <if test="_parameter != null"><include refid="Example_Where_Clause" /></if>
            <if test="orderByClause != null">order by ${'$'}{orderByClause}</if>
          </select>
        </mapper>
    """.trimIndent()

    fun testPanelInitialSelectionMatchesDefaultState() {
        val criteria = listOf(MbgCriterionMock("CUST_ID =", listOf("1"), MbgCriterionKind.LIST_VALUE))
        val parsed = XmlMapperParser().parse(mapper)
        val model = XmlPreviewModel.from(
            parsed.statements.single(), parsed.errors,
            setOf<String>(), mapOf<String, Any>(), mapOf<String, Any>(), mapOf<String, Boolean>(),
            mapOf<String, cn.song.mybatisplus.visualizer.xml.ParamTypeInfo>(),
            XmlPreviewProfile.MBG_EXAMPLE,
            mapOf(
                "oredCriteria" to listOf(MbgCriterionMock.criteriaOf(criteria)),
                "criteria.criteria" to criteria.map { it.toMockObject() },
            ),
        )

        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        SqlPreviewPanelResultViewKt.showResult(
            p,
            PreviewResult(
                "", "", emptyList(), null, null, null, null,
                emptyList(), emptyMap(), null, null, emptyList(), emptyList(), null,
                model,
                emptyMap(), emptyList(), emptyList(), null, emptyList(),
            ),
        )

        val initial = p.enabledIdsForTest()
        assertEquals("面板初始勾选必须与 defaultState 同源", model.defaultState().enabledIds, initial)
        assertFalse("profile 应抑制 distinct：$initial", initial.contains("distinct"))
        assertFalse("profile 应抑制 orderByClause：$initial", initial.contains("orderByClause != null"))
        assertTrue("choose 组应按 M2 真实 kind 选 listValue：$initial", initial.contains("criterion.listValue"))
        assertFalse("互斥分支不得同时勾选（noValue）：$initial", initial.contains("criterion.noValue"))
        assertFalse("互斥分支不得同时勾选（singleValue）：$initial", initial.contains("criterion.singleValue"))
    }
}
