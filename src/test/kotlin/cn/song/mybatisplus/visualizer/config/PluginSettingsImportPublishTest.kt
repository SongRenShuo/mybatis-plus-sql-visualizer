package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 V：设置 JSON 导入后发布 SETTINGS_CHANGE 回归。
 *
 * 导入链（applyImportedText → PluginSettings.fromJson → loadState）曾不发布
 * SETTINGS_CHANGE_TOPIC——SqlLogManager（前缀/去重窗即时生效）、面板列表、
 * AgentManager CONFIG 热下发全部保持旧值，直到用户下次在设置页点 Apply。
 * 修复后 fromJson 成功即经 EDT 发布（与设置页 apply() :1495 同语义）。
 */
class PluginSettingsImportPublishTest : BasePlatformTestCase() {

    fun test导入后SETTINGS_CHANGE被发布() {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        var fired = 0
        app.messageBus.connect(testRootDisposable).subscribe(
            PluginSettings.SETTINGS_CHANGE_TOPIC,
            SettingsChangeListener { fired++ },
        )
        // 备份现场（fromJson 覆盖全局 state，测完还原——服务为测试应用全局单例）
        val backup = PluginSettings.getInstance().state.copy()
        try {
            PluginSettings.fromJson("""{"version":1}""")
            // 经 EDT 派发——泵事件队列
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("导入后应恰好发布一次", 1, fired)
        } finally {
            PluginSettings.getInstance().loadState(backup)
        }
    }

    fun test导入失败不发布() {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        var fired = 0
        app.messageBus.connect(testRootDisposable).subscribe(
            PluginSettings.SETTINGS_CHANGE_TOPIC,
            SettingsChangeListener { fired++ },
        )
        try {
            // ImportException 路径（坏 JSON）——loadState 未发生，不得发布
            runCatching { PluginSettings.fromJson("""{"version":999}""") }
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(0, fired)
        } catch (_: Exception) {
            // 拒绝即预期
        }
    }
}
