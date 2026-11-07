package cn.song.mybatisplus.visualizer.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-SettingsTopicBus（2026-11-01 真机发现）：`SETTINGS_CHANGE_TOPIC` 的**发布总线与订阅总线是否同一条**。
 *
 * 真机现象：在设置页把「增强捕获」关掉再打开（Apply），`AgentManager` 的上升沿日志一次都没出现
 * （`设置变更：增强捕获重新开启——用户暂停已复位` / `在跑进程补挂 N 个`），因而「已在跑的应用补挂」
 * 这条路径在真机上从未被触发过。
 *
 * 代码事实（写本用例时逐行核对）：
 *  - 发布点 `PluginSettingsPanel.apply` / `SourceCaptureGuide.enableEnhancedCapture` 都用
 *    `ApplicationManager.getApplication().messageBus.syncPublisher(...)`（**应用级**总线）；
 *  - 订阅点 `AgentManager.init` / `HistorySqlLogPanel` 都用 `project.messageBus.connect(this)`
 *    （**项目级**总线）；
 *  - 既有用例 `SourceCaptureGuideEnableFlowTest` 也在**应用级**总线上订阅，所以它恒绿——
 *    这正是「测试与生产用了不同总线」的典型假绿形态。
 *
 * 本用例把两种总线组合都钉住：只要「应用级发布 → 项目级订阅」不成立，它就是缺陷的证据。
 */
class SettingsTopicBusTest : BasePlatformTestCase() {

    /** 应用级发布 → 应用级订阅（既有用例的形态，应当成立）。 */
    fun testApplicationPublishReachesApplicationSubscriber() {
        var hits = 0
        ApplicationManager.getApplication().messageBus
            .connect(testRootDisposable)
            .subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, SettingsChangeListener { hits++ })

        ApplicationManager.getApplication().messageBus
            .syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()

        assertEquals("应用级发布应送达应用级订阅者", 1, hits)
    }

    /** 应用级发布 → 项目级订阅（**生产实际形态**：AgentManager / HistorySqlLogPanel 都是这样订阅的）。 */
    fun testApplicationPublishReachesProjectSubscriber() {
        var hits = 0
        project.messageBus
            .connect(testRootDisposable)
            .subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, SettingsChangeListener { hits++ })

        ApplicationManager.getApplication().messageBus
            .syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()

        assertEquals(
            "生产订阅点在项目级总线上，发布点在应用级总线上——若本条为 0，则设置变更永远送不到 " +
                "AgentManager（增强捕获关→开的上升沿补挂失效，真机已复现）",
            1,
            hits,
        )
    }

    /** 反向对照：项目级发布 → 项目级订阅（同总线必然成立，用来证明上面的失败不是测试环境问题）。 */
    fun testProjectPublishReachesProjectSubscriber() {
        var hits = 0
        project.messageBus
            .connect(testRootDisposable)
            .subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, SettingsChangeListener { hits++ })

        project.messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()

        assertEquals("项目级发布应送达项目级订阅者（同总线对照）", 1, hits)
    }
}
