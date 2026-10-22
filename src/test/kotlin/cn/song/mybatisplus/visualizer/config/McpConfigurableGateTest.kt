package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.mcp.McpToolGate
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * MCP 设置页门控区测试（T-McpToolset2 / T-MTS2-00）：组件构建（总开关 + 工具复选框，R14 起十一工具）、
 * isModified/apply/reset 往返、全部启用/全部禁用状态切换单按钮、apply 后 McpToolGate 即时跟随；
 * P1-3 增补页内就绪横幅的零监听器静态渲染断言。
 */
class McpConfigurableGateTest : BasePlatformTestCase() {

    private val state get() = PluginSettings.getInstance().state

    override fun tearDown() {
        state.mcpToolsEnabled = true
        state.mcpDisabledTools.clear()
        super.tearDown()
    }

    private fun newConfigurable(): McpConfigurable {
        val configurable = McpConfigurable()
        Disposer.register(testRootDisposable) { configurable.disposeUIResources() }
        configurable.createComponent()
        return configurable
    }

    fun test构建_默认全启用且未修改() {
        val configurable = newConfigurable()
        assertTrue(configurable.createComponent() != null)
        configurable.apply() // 基线空跑
        assertTrue(state.mcpToolsEnabled)
        assertTrue(state.mcpDisabledTools.isEmpty())
        assertFalse(configurable.isModified)
    }

    fun test总开关apply写入状态且门控即时跟随() {
        val configurable = newConfigurable()
        configurable.apply() // 基线
        configurable.masterForTest()!!.isSelected = false
        assertTrue("改动后应判 modified", configurable.isModified)
        configurable.apply()
        assertFalse(state.mcpToolsEnabled)
        assertFalse("门控即时跟随设置", McpToolGate.isEnabled("sqlv_render_sql"))
    }

    fun test工具级禁用apply写入禁用清单() {
        val configurable = newConfigurable()
        configurable.apply() // 基线
        configurable.toolCheckBoxForTest("sqlv_explain")!!.isSelected = false
        configurable.toolCheckBoxForTest("sqlv_mapper_check")!!.isSelected = false
        assertTrue(configurable.isModified)
        configurable.apply()
        assertEquals(listOf("sqlv_explain", "sqlv_mapper_check"), state.mcpDisabledTools)
        assertFalse(McpToolGate.isEnabled("sqlv_explain"))
        assertTrue(McpToolGate.isEnabled("sqlv_render_sql"))
    }

    fun testreset恢复设置态() {
        state.mcpToolsEnabled = false
        state.mcpDisabledTools.add("sqlv_explain")
        val configurable = newConfigurable()
        configurable.reset()
        configurable.apply()
        assertFalse(state.mcpToolsEnabled)
        assertEquals(listOf("sqlv_explain"), state.mcpDisabledTools)
        assertFalse(configurable.isModified)
    }

    fun test全部启用禁用切换按钮() {
        val configurable = newConfigurable()
        configurable.apply() // 基线
        val button = configurable.toggleAllButtonForTest()!!
        // 初始全勾 → 按钮为「全部禁用」；点击 → 全不勾 → 按钮翻转「全部启用」
        assertEquals(MpSqlVisualizerBundle.message("settings.mcp.tools.none"), button.text)
        button.doClick()
        assertEquals(MpSqlVisualizerBundle.message("settings.mcp.tools.all"), button.text)
        configurable.apply()
        assertEquals(McpToolGate.TOOL_NAMES.sorted(), state.mcpDisabledTools.sorted())
        button.doClick()
        configurable.apply()
        assertTrue(state.mcpDisabledTools.isEmpty())
    }

    // ---------------- P1-3：页内就绪横幅（零监听器静态渲染） ----------------

    fun test就绪横幅默认态_计数动态取TOOL_NAMES且门控行为全启用() {
        val configurable = newConfigurable()
        assertEquals(
            "横幅标题计数应动态取 TOOL_NAMES.size（写死数字会随工具清单漂移过期，R14 十→十一工具即前车）",
            MpSqlVisualizerBundle.message("settings.mcp.banner.declared", McpToolGate.TOOL_NAMES.size.toString()),
            configurable.bannerHeadlineForTest(),
        )
        assertEquals(
            "默认（无禁用清单）门控行应为全启用文案",
            MpSqlVisualizerBundle.message("settings.mcp.banner.allEnabled"),
            configurable.bannerGateLineForTest(),
        )
    }

    fun test就绪横幅按门控现状渲染_工具禁用与总开关两态() {
        state.mcpDisabledTools.add("sqlv_explain")
        state.mcpDisabledTools.add("sqlv_mapper_check")
        val disabledConfigurable = newConfigurable()
        assertEquals(
            "禁用 2 个工具后横幅门控行应按禁用计数渲染",
            MpSqlVisualizerBundle.message("settings.mcp.banner.someDisabled", "2"),
            disabledConfigurable.bannerGateLineForTest(),
        )
        state.mcpToolsEnabled = false
        val offConfigurable = newConfigurable()
        assertEquals(
            "总开关关闭时横幅门控行应优先给总开关文案（关闭 > 工具级禁用）",
            MpSqlVisualizerBundle.message("settings.mcp.banner.masterOff"),
            offConfigurable.bannerGateLineForTest(),
        )
    }
}
