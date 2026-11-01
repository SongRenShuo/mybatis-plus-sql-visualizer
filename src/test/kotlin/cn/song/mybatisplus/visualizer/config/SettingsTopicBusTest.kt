package cn.song.mybatisplus.visualizer.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.messages.Topic

/**
 * T-SettingsTopicBus（2026-11-01 真机排查副产物）：消息总线的**应用级发布 → 项目级订阅**是否成立。
 *
 * 起因：真机上把「增强捕获」关掉再打开、Apply 之后，`AgentManager` 的上升沿日志一次都没出现
 * （`设置变更：增强捕获重新开启——用户暂停已复位` / `在跑进程补挂 N 个`），于是怀疑
 * `PluginSettingsPanel.apply` 用 `ApplicationManager.getApplication().messageBus` 发布、
 * 而 `AgentManager` 用 `project.messageBus` 订阅，两条总线不通 → 主题送不到。
 *
 * 结论（本用例钉住）：**应用级发布能送达项目级订阅者**（MessageBus 有父子链），总线不是那次的卡点。
 * 留着这组用例，是为了下次排查「设置改了不生效」时不必再怀疑总线。
 *
 * 关键实现约束：本用例**必须用自己声明的 Topic**，不能拿生产的
 * `PluginSettings.SETTINGS_CHANGE_TOPIC` 做发布——那会在测试 JVM 里真的触发 `AgentManager` /
 * `SqlLogManager` / `HistorySqlLogPanel` 等订阅方，污染同分片的其它用例
 * （2026-11-01 实证：用生产 Topic 发布后，同分片 3 个不相关用例跟着红——
 * SqlCaptureGateSniffTest / DmlImpactSideQueryPanelTest / RollbackSqlDialogTest）。
 */
class SettingsTopicBusTest : BasePlatformTestCase() {

    /** 与 `PluginSettings.SETTINGS_CHANGE_TOPIC` 同形态的本地主题（发布/订阅语义完全一致）。 */
    private val localTopic: Topic<SettingsChangeListener> =
        Topic.create("SettingsTopicBusTest.local", SettingsChangeListener::class.java)

    /** 应用级发布 → 应用级订阅（既有用例的形态，应当成立）。 */
    fun testApplicationPublishReachesApplicationSubscriber() {
        var hits = 0
        ApplicationManager.getApplication().messageBus
            .connect(testRootDisposable)
            .subscribe(localTopic, SettingsChangeListener { hits++ })

        ApplicationManager.getApplication().messageBus.syncPublisher(localTopic).settingsChanged()

        assertEquals("应用级发布应送达应用级订阅者", 1, hits)
    }

    /** 应用级发布 → 项目级订阅（**生产实际形态**：AgentManager / HistorySqlLogPanel 都这样订阅）。 */
    fun testApplicationPublishReachesProjectSubscriber() {
        var hits = 0
        project.messageBus
            .connect(testRootDisposable)
            .subscribe(localTopic, SettingsChangeListener { hits++ })

        ApplicationManager.getApplication().messageBus.syncPublisher(localTopic).settingsChanged()

        assertEquals(
            "生产订阅点在项目级总线上、发布点在应用级总线上——本条若为 0，说明设置变更送不到 " +
                "AgentManager（增强捕获关→开的上升沿补挂会失效）",
            1,
            hits,
        )
    }

    /** 反向对照：项目级发布 → 项目级订阅（同总线必然成立，证明上一条的失败不是环境问题）。 */
    fun testProjectPublishReachesProjectSubscriber() {
        var hits = 0
        project.messageBus
            .connect(testRootDisposable)
            .subscribe(localTopic, SettingsChangeListener { hits++ })

        project.messageBus.syncPublisher(localTopic).settingsChanged()

        assertEquals("项目级发布应送达项目级订阅者（同总线对照）", 1, hits)
    }
}
