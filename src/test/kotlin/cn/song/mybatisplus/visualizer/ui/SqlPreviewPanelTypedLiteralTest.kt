package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue

/**
 * F-01 类型感知字面量在面板回填链（[SqlPreviewPanel.renderParamValue] → renderSingleValue）的
 * headless 集成用例：
 *  - **有签名类型类别**（TEMPORAL/NUMBER）→ 值按类型 × 方言渲染（日期出 ANSI 类型字面量——
 *    headless 无数据源证据，方言为 null 走通用档）；
 *  - **无类型信息**（UNKNOWN）→ 引号兜底，与升级前逐字节一致（闲时任务册 F-01「不改变既有
 *    无类型信息的回退行为」的面板侧快照）。
 *
 * 类型类别由预览模型携带（无需 PSI：直接构造 `paramTypes` 注入），形态同 SqlPreviewPanelParamKindTest。
 * 属平台桶（`com.intellij` 引用），不在 `testFast` 快桶内，定向跑。
 */
class SqlPreviewPanelTypedLiteralTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    override fun setUp() {
        super.setUp()
        // O-97 治理：进态恢复出厂默认 + 关格式化（格式化会重排空白，contains 断言需原文稳定）
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = false }
    }

    override fun tearDown() {
        runCatching { panel?.clear() }
        panel = null
        super.tearDown()
    }

    private val mapper = """
        <mapper namespace="com.example.ProbeMapper">
          <select id="probe">
            SELECT * FROM t
            WHERE created = #{startDate} AND page_num = #{pageNum} AND name = #{userName}
          </select>
        </mapper>
    """.trimIndent()

    /** startDate=TEMPORAL、pageNum=NUMBER、userName 无类型信息（UNKNOWN）。 */
    private fun result(): PreviewResult {
        val parsed = XmlMapperParser().parse(mapper)
        val model = XmlPreviewModel.from(
            parsed.statements.single(), parsed.errors,
            paramTypes = mapOf(
                "startDate" to ParamTypeInfo(ParamKind.TEMPORAL),
                "pageNum" to ParamTypeInfo(ParamKind.NUMBER, nullable = false),
            ),
        )
        val rendered = model.renderSql()
        return PreviewResult(
            sql = rendered.sql,
            compactSql = rendered.sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = rendered.mappings.map { it.property },
            paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
            wrapperType = null,
            tableName = null,
            xmlModel = model,
        )
    }

    private fun openPanel(): SqlPreviewPanel {
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        p.showResult(result())
        return p
    }

    private fun sqlText(p: SqlPreviewPanel): String = p.editorForTest()!!.document.text

    fun test时间类型_日期值出ANSI类型字面量() {
        val p = openPanel()
        p.paramFieldForTest(0)!!.text = "2024-01-01"
        p.renderSqlForTest()
        val sql = sqlText(p)
        assertTrue(
            "TEMPORAL 参数应出 DATE 类型字面量（方言未知走 ANSI 通用档）: $sql",
            sql.contains("created = DATE '2024-01-01'"),
        )
    }

    fun test时间类型_时间戳值出TIMESTAMP字面量() {
        val p = openPanel()
        p.paramFieldForTest(0)!!.text = "2024-01-01T12:30"
        p.renderSqlForTest()
        val sql = sqlText(p)
        assertTrue(
            "T 分隔符应归一且出 TIMESTAMP 字面量: $sql",
            sql.contains("created = TIMESTAMP '2024-01-01 12:30:00'"),
        )
    }

    fun test数值类型_科学计数裸渲染() {
        val p = openPanel()
        p.paramFieldForTest(1)!!.text = "1.0E10"
        p.renderSqlForTest()
        val sql = sqlText(p)
        assertTrue("NUMBER 参数科学计数值应裸渲染（ORA-01722 场景）: $sql", sql.contains("page_num = 1.0E10"))
    }

    fun test无类型信息_引号兜底回退不变() {
        val p = openPanel()
        p.paramFieldForTest(2)!!.text = "2024-01-01"
        p.renderSqlForTest()
        val sql = sqlText(p)
        assertTrue(
            "UNKNOWN 参数应维持引号兜底（升级前形态，回退快照）: $sql",
            sql.contains("name = '2024-01-01'"),
        )
    }
}
