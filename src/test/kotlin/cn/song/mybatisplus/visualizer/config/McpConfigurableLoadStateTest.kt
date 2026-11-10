package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 W：McpConfigurable.apply 走 loadState(copy) 回归。
 *
 * 全局面板（PluginSettingsPanel.apply :1488-1493）已实证「原地修改 state 属性后
 * 框架可能不检测引用变化导致 XML 未写入」，修复手法是 loadState(s.copy()) 换引用；
 * McpConfigurable 与 ProjectPluginSettingsConfigurable 曾仍是纯原地写。
 */
class McpConfigurableLoadStateTest : BasePlatformTestCase() {

    @Test
    fun testApply后state引用换新_持久化链路可触发() {
        val backup = PluginSettings.getInstance().state.copy()
        try {
            val before = PluginSettings.getInstance().state
            McpConfigurable().apply()
            val after = PluginSettings.getInstance().state
            assertNotSame(
                "apply 后 state 应换新引用（loadState(copy)，摘掉该行即红）",
                before,
                after,
            )
            assertTrue("未建组件时 apply 走默认值路径（mcpToolsEnabled=true）", after.mcpToolsEnabled)
        } finally {
            PluginSettings.getInstance().loadState(backup)
        }
    }
}
