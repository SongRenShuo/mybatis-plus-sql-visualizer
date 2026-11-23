package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.mcp.McpToolGate
import cn.song.mybatisplus.visualizer.mcp.findMcpToolsetClass
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * MCP 设置页门控区测试（T-McpToolset2 / T-MTS2-00）：组件构建（总开关 + 工具复选框，R14 起十一工具）、
 * isModified/apply/reset 往返、全部启用/全部禁用状态切换单按钮、apply 后 McpToolGate 即时跟随；
 * UI011：声明数量、平台能力、页内开关及中英文语义回归。
 */
class McpConfigurableGateTest : BasePlatformTestCase() {

    private val state get() = PluginSettings.getInstance().state

    override fun tearDown() {
        state.mcpToolsEnabled = true
        state.mcpDisabledTools.clear()
        super.tearDown()
    }

    private fun newConfigurable(platformPresent: Boolean? = null): McpConfigurable {
        val configurable = if (platformPresent == null) McpConfigurable() else McpConfigurable { platformPresent }
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

    // ---------------- UI011：数量只指声明，能力不等于就绪 ----------------

    fun test声明横幅默认态_计数动态取TOOL_NAMES且门控行为全启用() {
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

    fun test横幅按门控现状渲染_工具禁用与总开关两态() {
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

    fun test平台能力正反与总开关正交_四种组合不掩盖缺失() {
        for (platformPresent in listOf(false, true)) {
            for (masterOn in listOf(false, true)) {
                state.mcpToolsEnabled = masterOn
                val configurable = newConfigurable(platformPresent)
                assertEquals(
                    MpSqlVisualizerBundle.message("settings.mcp.banner.declared", McpToolGate.TOOL_NAMES.size.toString()),
                    configurable.bannerHeadlineForTest(),
                )
                assertEquals(
                    MpSqlVisualizerBundle.message(
                        if (platformPresent) "settings.mcp.banner.platformPresent"
                        else "settings.mcp.banner.platformUnavailable",
                    ),
                    configurable.bannerPlatformLineForTest(),
                )
                assertEquals(
                    MpSqlVisualizerBundle.message(
                        if (masterOn) "settings.mcp.banner.allEnabled" else "settings.mcp.banner.masterOff",
                    ),
                    configurable.bannerGateLineForTest(),
                )
                assertFalse(configurable.isModified)
            }
        }
    }

    fun test默认构造使用真实能力探测而非版本号() {
        val configurable = newConfigurable()
        assertEquals(
            MpSqlVisualizerBundle.message(
                if (findMcpToolsetClass() != null) "settings.mcp.banner.platformPresent"
                else "settings.mcp.banner.platformUnavailable",
            ),
            configurable.bannerPlatformLineForTest(),
        )
    }

    fun test横幅跟随单项全选总开关及reset且能力提示不变() {
        val configurable = newConfigurable(false)
        val platformLine = configurable.bannerPlatformLineForTest()
        configurable.toolCheckBoxForTest("sqlv_explain")!!.doClick()
        assertEquals(MpSqlVisualizerBundle.message("settings.mcp.banner.someDisabled", "1"), configurable.bannerGateLineForTest())
        assertEquals(MpSqlVisualizerBundle.message("settings.mcp.tools.all"), configurable.toggleAllButtonForTest()!!.text)
        configurable.toggleAllButtonForTest()!!.doClick()
        assertEquals(MpSqlVisualizerBundle.message("settings.mcp.banner.allEnabled"), configurable.bannerGateLineForTest())
        configurable.toggleAllButtonForTest()!!.doClick()
        assertEquals(
            MpSqlVisualizerBundle.message("settings.mcp.banner.someDisabled", McpToolGate.TOOL_NAMES.size.toString()),
            configurable.bannerGateLineForTest(),
        )
        configurable.masterForTest()!!.doClick()
        assertEquals(MpSqlVisualizerBundle.message("settings.mcp.banner.masterOff"), configurable.bannerGateLineForTest())
        assertTrue("未应用的选择不能写入设置", state.mcpToolsEnabled)
        assertTrue(state.mcpDisabledTools.isEmpty())
        configurable.apply()
        assertFalse(state.mcpToolsEnabled)
        assertEquals(McpToolGate.TOOL_NAMES.size, state.mcpDisabledTools.size)
        state.mcpToolsEnabled = true
        state.mcpDisabledTools.clear()
        configurable.reset()
        assertEquals(MpSqlVisualizerBundle.message("settings.mcp.banner.allEnabled"), configurable.bannerGateLineForTest())
        assertEquals(platformLine, configurable.bannerPlatformLineForTest())
        assertFalse(configurable.isModified)
    }

    fun test中英文明确声明能力与选择且不再宣称就绪() {
        val originalLanguage = state.language
        try {
            for (language in listOf("zh", "en")) {
                state.language = language
                val unavailable = newConfigurable(false)
                val available = newConfigurable(true)
                val headline = available.bannerHeadlineForTest()!!
                val missing = unavailable.bannerPlatformLineForTest()!!
                val present = available.bannerPlatformLineForTest()!!
                val gate = available.bannerGateLineForTest()!!
                if (language == "zh") {
                    assertTrue(headline.contains("声明 ${McpToolGate.TOOL_NAMES.size} 个工具"))
                    assertTrue(headline.contains("不代表已注册或可调用数量"))
                    assertTrue(missing.contains("缺少 MCP 工具集接口"))
                    assertTrue(present.contains("仍取决于注册结果"))
                    assertTrue(gate.contains("修改需应用后生效"))
                    assertFalse(headline.contains("已就绪"))
                } else {
                    assertTrue(headline.contains("${McpToolGate.TOOL_NAMES.size} tool declarations"))
                    assertTrue(headline.contains("not a count of registered or callable tools"))
                    assertTrue(missing.contains("API unavailable"))
                    assertTrue(present.contains("still depends on registration"))
                    assertTrue(gate.contains("after Apply"))
                    assertFalse(headline.contains("tools ready"))
                }
                available.masterForTest()!!.doClick()
                val off = available.bannerGateLineForTest()!!
                assertTrue(off.contains(if (language == "zh") "不会启用平台服务器" else "does not enable the platform server"))
                available.toolCheckBoxForTest("sqlv_explain")!!.doClick()
                available.masterForTest()!!.doClick()
                val partial = available.bannerGateLineForTest()!!
                assertTrue(partial.contains(if (language == "zh") "停用 1 个工具" else "1 tools disabled"))
                for (text in listOf(headline, missing, present, gate, off, partial)) {
                    assertFalse("不能回退到资源键", text.startsWith("settings.mcp."))
                    assertFalse("占位符必须已格式化", text.contains("{0}"))
                }
            }
        } finally {
            state.language = originalLanguage
        }
    }

    fun test释放后横幅资源清空且重建重新探测() {
        var present = false
        val configurable = McpConfigurable { present }
        Disposer.register(testRootDisposable) { configurable.disposeUIResources() }
        configurable.createComponent()
        assertEquals(MpSqlVisualizerBundle.message("settings.mcp.banner.platformUnavailable"), configurable.bannerPlatformLineForTest())
        configurable.disposeUIResources()
        assertNull(configurable.bannerHeadlineForTest())
        assertNull(configurable.bannerPlatformLineForTest())
        assertNull(configurable.bannerGateLineForTest())
        present = true
        configurable.createComponent()
        assertEquals(MpSqlVisualizerBundle.message("settings.mcp.banner.platformPresent"), configurable.bannerPlatformLineForTest())
    }

    // ---- T-McpGlobalBreakHardening（20261119）：apply 必须广播设置变更 --------------------

    /**
     * 过滤 EP 的热同步（McpGateRegistrationController）订阅 [PluginSettings.SETTINGS_CHANGE_TOPIC]；
     * 261 真机 GUI 走查实测：MCP 子页 apply 不广播时，总开关 Apply 后两注册面都不摘（工具消失
     * 只是平台 isEnabled 过滤的副作用，过滤 provider 仍挂在平台上）——热切换必须靠这条广播。
     */
    fun testapply后必须广播设置变更事件() {
        var fired = 0
        val connection = com.intellij.openapi.application.ApplicationManager.getApplication()
            .messageBus.connect(testRootDisposable)
        connection.subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, SettingsChangeListener { fired++ })
        val configurable = newConfigurable()
        configurable.apply()
        assertEquals("apply 必须广播 SETTINGS_CHANGE_TOPIC（过滤 EP 热同步的触发源）", 1, fired)
    }
}
