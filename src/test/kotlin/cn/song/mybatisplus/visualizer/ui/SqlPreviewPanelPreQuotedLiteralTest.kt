package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals

/**
 * 已引号参数值不二次转义（T-PreQuotedLiteral，2026-11-18 用户报障修复）：
 * XML 预览 int 参数（类型未知走 quote 兜底引号档）输入带引号的非数值 → 修复前 `'''abc'''` /
 * `''''` 引号翻倍（先红读数见开发计划 §一），修复后原样渲染。
 * 反向断言：`O'Brien` 真实撇号仍转义、裸数字仍裸渲染——防「全部原样透传」的过度修复。
 */
class SqlPreviewPanelPreQuotedLiteralTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    private var oldEmptyParamFill: String = PluginSettings.DEFAULT_EMPTY_PARAM_FILL

    override fun setUp() {
        super.setUp()
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        runCatching { PluginSettings.getInstance().state.formatSql = false }
        oldEmptyParamFill = runCatching { PluginSettings.getInstance().state.emptyParamFill }
            .getOrDefault(PluginSettings.DEFAULT_EMPTY_PARAM_FILL)
        runCatching { PluginSettings.getInstance().state.emptyParamFill = PluginSettings.DEFAULT_EMPTY_PARAM_FILL }
    }

    override fun tearDown() {
        runCatching { panel?.clear() }
        runCatching { PluginSettings.getInstance().state.formatSql = true }
        runCatching { PluginSettings.getInstance().state.emptyParamFill = oldEmptyParamFill }
        panel = null
        super.tearDown()
    }

    private val mapper = """
        <mapper namespace="com.example.ProbeMapper">
          <select id="probe">
            SELECT * FROM t_order WHERE id = #{id}
          </select>
        </mapper>
    """.trimIndent()

    private fun renderWith(paramTypes: Map<String, ParamTypeInfo>, value: String): String {
        val parsed = XmlMapperParser().parse(mapper)
        val model = XmlPreviewModel.from(parsed.statements.single(), parsed.errors, paramTypes = paramTypes)
        val rendered = model.renderSql()
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        p.showResult(
            PreviewResult(
                sql = rendered.sql,
                compactSql = rendered.sql.replace(Regex("\\s+"), " ").trim(),
                unresolvedParams = rendered.mappings.map { it.property },
                paramTable = ParameterReplacer.buildParamTable(rendered.mappings.map { it.property }),
                wrapperType = null,
                tableName = null,
                xmlModel = model,
            )
        )
        val field = p.paramFieldForTest(0)!!
        field.text = value
        UIUtil.dispatchAllInvocationEvents()
        return p.editorForTest()!!.document.text.replace(Regex("\\s+"), " ")
    }

    /** 类型未知（quote 兜底 true）+ 用户手工加引号的非数值 → 原样，不翻倍（报障主场景）。 */
    fun test未知类型已引号输入原样渲染不翻倍() {
        assertEquals(
            "SELECT * FROM t_order WHERE id = 'abc'",
            renderWith(emptyMap(), "'abc'"),
        )
    }

    /** 报障四引号形态：键入孤引号的中间帧在输入成完整字面量后不再产出翻倍串。 */
    fun test未知类型空串字面量原样渲染() {
        assertEquals(
            "SELECT * FROM t_order WHERE id = ''",
            renderWith(emptyMap(), "''"),
        )
    }

    /** TEXT 签名参数同样不翻倍（quote=true 显式档）。 */
    fun testTEXT签名已引号输入原样渲染() {
        assertEquals(
            "SELECT * FROM t_order WHERE id = 'abc'",
            renderWith(mapOf("id" to ParamTypeInfo(ParamKind.TEXT)), "'abc'"),
        )
    }

    /** 已转义的完整字面量 `'a''b'` 原样（内层转义形态是用户自己的合法 SQL）。 */
    fun test已转义内层引号原样渲染() {
        assertEquals(
            "SELECT * FROM t_order WHERE id = 'a''b'",
            renderWith(emptyMap(), "'a''b'"),
        )
    }

    /** 反向：真实撇号 O'Brien 不以引号开头 → 维持转义 `'O''Brien'`。 */
    fun test真实撇号仍转义() {
        assertEquals(
            "SELECT * FROM t_order WHERE id = 'O''Brien'",
            renderWith(emptyMap(), "O'Brien"),
        )
    }

    /** 裸数字走启发式档裸渲染的语义由 ParameterReplacerTest 钉住；XML 面板链未知参数恒兜底
     *  quote=true（T-M29.4 设计，xmlBackfillHints `?: true`），数字输入同样出引号——钉住不漂移。 */
    fun test未知类型裸数字兜底引号档() {
        assertEquals(
            "SELECT * FROM t_order WHERE id = '123'",
            renderWith(emptyMap(), "123"),
        )
    }

    /** 反向：非引号非数字维持兜底引号（T-M29.4 设计行为不变）。 */
    fun test未知类型裸文本仍兜底引号() {
        assertEquals(
            "SELECT * FROM t_order WHERE id = 'abc'",
            renderWith(emptyMap(), "abc"),
        )
    }

    /** NUMBER 签名参数：非数值输入维持裸透传（数值列恒原样，语义不变）。 */
    fun testNUMBER签名非数值维持裸透传() {
        assertEquals(
            "SELECT * FROM t_order WHERE id = abc",
            renderWith(mapOf("id" to ParamTypeInfo(ParamKind.NUMBER)), "abc"),
        )
    }
}
