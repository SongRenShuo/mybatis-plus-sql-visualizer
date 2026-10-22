package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener
import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * O-66 T-GuideEnhancedFirst / T-EnhancedCaptureDefaultOn：引导弹框「开启增强捕获」动作的编排测试。
 *
 * 2026-10-19 起注入确认流移除，本测试锁定新契约：
 *  - 总开关关闭时：置 enabled=true 并发布 SETTINGS_CHANGE_TOPIC（AgentManager 上升沿扫描补挂 +
 *    CONFIG 链跟随的触发面，与设置页 Apply 同通道）；
 *  - 已开启时：不得重复发布（设置页 isModified 对比读同一 state，乱发布会制造脏态假象）；
 *  - 回调是唯一结果通道：编排只负责置位与发布，不另藏持久化。
 */
class SourceCaptureGuideEnableFlowTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager
    private var settingsChangeCount = 0

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        manager.clockMs = { System.currentTimeMillis() }
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = false
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = 0L
        settingsChangeCount = 0
        ApplicationManager.getApplication().messageBus
            .connect(testRootDisposable)
            .subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, SettingsChangeListener { settingsChangeCount++ })
    }

    override fun tearDown() {
        manager.shutdownAll()
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = false
        state.enhancedCaptureConfirmed = true
        state.enhancedCaptureRejectedAtMs = 0L
        super.tearDown()
    }

    fun test总开关关闭时置位并发布设置变更_回调一次() {
        var called = 0
        SourceCaptureGuide.enableEnhancedCapture(project) { called++ }
        val state = PluginSettings.getInstance().state
        assertTrue("总开关应置位", state.enhancedCaptureEnabled)
        assertEquals("置开关应发布一次 SETTINGS_CHANGE", 1, settingsChangeCount)
        assertEquals("回调应执行一次", 1, called)
    }

    fun test已开启时不重复发布设置变更_回调仍执行() {
        PluginSettings.getInstance().state.enhancedCaptureEnabled = true
        var called = 0
        SourceCaptureGuide.enableEnhancedCapture(project) { called++ }
        assertEquals("已开启时不得重复发布 SETTINGS_CHANGE", 0, settingsChangeCount)
        assertEquals("回调应执行一次", 1, called)
    }
}
