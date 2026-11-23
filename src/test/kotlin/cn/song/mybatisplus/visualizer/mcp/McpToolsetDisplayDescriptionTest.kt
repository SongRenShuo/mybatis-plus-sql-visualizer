package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.ResourceBundle

/**
 * MCP 工具灰字描述双语化测试（T-McpToolDisplayDesc，20261121-1104 计划）。
 *
 * 根因与修法见计划 §二/§三：平台设置页「公开的工具」灰字读 `displayDescription(fallback)`
 * 钩子（fallback＝工具名），此前基类透传导致灰字＝工具名；现改读 bundle 简述键
 * （与插件设置页门控区同键、双语跟随插件语言）。
 *
 * 语言控制说明：bundle 按插件语言设置选 locale、测试环境（settings service 未注册）
 * 兜底中文（MpSqlVisualizerBundle 头注）——本类中文态断言走该兜底；英文态不断言
 * 切换链（那是既有 message 机制），改为**直接按 locale 读两份 properties 断言键面
 * 齐全**（键对称自查同时承担英文文案在场的职责）。
 *
 * 反向验证（计划 §五）：把 `McpToolsetSupport.displayDescription(fallback)` 改回
 * 透传（`= fallback`）→ `十一工具_中文态_灰字是描述不是工具名` 恰红（灰字回落工具名
 * ＝fallback，`assertNotEquals(toolName, …)` 触发），还原复绿。
 */
class McpToolsetDisplayDescriptionTest {

    /** 基类构造只吃 toolName，无平台依赖（displayDescription 只走 bundle），纯 JUnit 可实例化。 */
    private class ProbeTool(toolName: String) : McpToolsetSupport(toolName)

    private val elevenTools = listOf(
        "sqlv_check_sql",
        "sqlv_crud_sql",
        "sqlv_mapper_check",
        "sqlv_history_search",
        "sqlv_render_logs",
        "sqlv_perf_advice",
        "sqlv_render_sql",
        "sqlv_explain",
        "sqlv_rollback_plan",
        "sqlv_schema_context",
        "sqlv_reverse_sql",
    )

    @Test
    fun `键派生_与门控区既有键规则一致`() {
        assertEquals("settings.mcp.tool.desc.checksql", McpToolsetSupport.toolDescKey("sqlv_check_sql"))
        assertEquals("settings.mcp.tool.desc.renderlogs", McpToolsetSupport.toolDescKey("sqlv_render_logs"))
        assertEquals("settings.mcp.tool.desc.schemacontext", McpToolsetSupport.toolDescKey("sqlv_schema_context"))
        // 无 sqlv_ 前缀的健壮性：replace 是全局去下划线（与 McpConfigurable 原规则逐字一致）
        assertEquals("settings.mcp.tool.desc.customtool", McpToolsetSupport.toolDescKey("custom_tool"))
    }

    @Test
    fun `中文态_灰字是描述不是工具名`() {
        val tool = ProbeTool("sqlv_check_sql")
        val shown = tool.displayDescription("sqlv_check_sql")
        // 兜底中文（测试环境 settings 未注册）→ 应读到 zh 简述
        assertEquals("SQL 静态写风险与类型核查", shown)
    }

    @Test
    fun `十一工具_中文态_灰字非空且不等于工具名`() {
        for (name in elevenTools) {
            val shown = ProbeTool(name).displayDescription(name)
            assertTrue("工具 $name 灰字应为非空描述，实得「$shown」", shown.isNotBlank())
            assertNotEquals("工具 $name 灰字不应回落到工具名（键缺失或派生错误）", name, shown)
            assertFalse("工具 $name 灰字不应是 bundle 键名字符串（messageOrNull 失效）", shown.contains("settings.mcp.tool.desc."))
        }
    }

    @Test
    fun `十一工具_英文键面_两份properties齐全且非空`() {
        val en = ResourceBundle.getBundle(
            "messages.MpSqlVisualizerBundle", Locale.ROOT,
            MpSqlVisualizerBundle::class.java.classLoader,
        )
        val zh = ResourceBundle.getBundle(
            "messages.MpSqlVisualizerBundle", Locale.SIMPLIFIED_CHINESE,
            MpSqlVisualizerBundle::class.java.classLoader,
        )
        for (name in elevenTools) {
            val key = McpToolsetSupport.toolDescKey(name)
            val enText = en.getString(key)
            val zhText = zh.getString(key)
            assertTrue("键 $key 英文文案应为非空", enText.isNotBlank())
            assertTrue("键 $key 中文文案应为非空", zhText.isNotBlank())
            assertNotEquals("键 $key 中英文案不应相同（双语未铺）", enText, zhText)
        }
    }

    @Test
    fun `缺键_带参版回落fallback_无参版给空串`() {
        val absent = ProbeTool("sqlv_absent_probe_tool")
        val fallback = "whatever the platform handed over"
        assertEquals(fallback, absent.displayDescription(fallback))
        assertEquals("", absent.displayDescription())
    }

    @Test
    fun `messageOrNull_缺键给null_命中给值`() {
        // 与 message() 的「缺键返回 key 本身」区分——回落语义依赖这一点
        org.junit.Assert.assertNull(MpSqlVisualizerBundle.messageOrNull("settings.mcp.tool.desc.absentprobe"))
        assertEquals(
            "SQL 静态写风险与类型核查",
            MpSqlVisualizerBundle.messageOrNull("settings.mcp.tool.desc.checksql"),
        )
    }
}
