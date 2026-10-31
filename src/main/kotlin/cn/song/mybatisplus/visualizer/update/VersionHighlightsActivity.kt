package cn.song.mybatisplus.visualizer.update

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettingsFolderConfigurable
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.BrowseNotificationAction
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

/**
 * NEW-19：升级后一次性「本版亮点」通知（postStartupActivity，每项目触发、应用级单次守卫）。
 *
 * 与 [StartupUpdateCheck] 的分工：那条通知回答「**有没有新版本可装**」（网络检查）；本条回答
 * 「**刚装上的这一版带来了什么**」（本地登记表，零网络）。内容来自 [VersionHighlights] 的
 * changelog 提炼表，每条登记项带一个直达入口（设置页 / SQL 日志工具窗 / 更新日志）。
 *
 * 防打扰三重约束（与首捕通知 [cn.song.mybatisplus.visualizer.log.FirstCaptureGuide] 同口径）：
 *  - 一次性：`PluginSettings.lastHighlightedVersion` 持久化，同版本永不重弹；
 *  - 版本粒度：当前版本 ≠ 上次提示版本才弹；
 *  - headless 静默：`isUnitTestMode` 直接返回，**不写记忆**（测试不污染用户持久化状态）。
 *
 * 未登记亮点的版本静默（宁缺勿滥，不自动摘要 changelog）。写入记忆先于弹通知：领取即消耗，
 * 通知链异常也不会让同版本反复打扰。
 */
class VersionHighlightsActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val app = ApplicationManager.getApplication() ?: return
        val settings = PluginSettings.getInstance()
        val current = PluginVersion.current()
        if (!VersionHighlights.shouldNotify(current, settings.state.lastHighlightedVersion, app.isUnitTestMode)) {
            return
        }
        val version = current ?: return
        val highlights = VersionHighlights.highlightsFor(version)
        if (highlights.isEmpty()) return
        if (!claim(version)) return
        // 领取即消耗：先落记忆再弹（同版本不会因异常重弹）
        settings.state.lastHighlightedVersion = version
        app.invokeLater {
            if (project.isDisposed) return@invokeLater
            notify(project, version, highlights)
        }
    }

    private fun notify(project: Project, version: String, highlights: List<VersionHighlight>) {
        runCatching {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    MpSqlVisualizerBundle.message("update.highlights.title", MpSqlVisualizerBundle.message("plugin.name")),
                    VersionHighlights.contentText(version, highlights),
                    NotificationType.INFORMATION,
                )
            // 直达入口：按登记项涉及的入口去重，每类只挂一个动作
            highlights.map { it.target }.distinct().forEach { target ->
                addAction(notification, project, target)
            }
            notification.notify(project)
        }
    }

    private fun addAction(notification: Notification, project: Project, target: HighlightTarget) {
        val label = MpSqlVisualizerBundle.message(VersionHighlights.actionLabelKey(target))
        when (target) {
            HighlightTarget.SETTINGS -> notification.addAction(
                NotificationAction.createSimpleExpiring(label) {
                    if (!project.isDisposed) {
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, PluginSettingsFolderConfigurable::class.java)
                    }
                },
            )
            HighlightTarget.SQL_LOG -> notification.addAction(
                NotificationAction.createSimpleExpiring(label) {
                    if (!project.isDisposed) {
                        ToolWindowManager.getInstance(project).getToolWindow(SqlLogManager.TOOL_WINDOW_ID)?.show()
                    }
                },
            )
            HighlightTarget.RELEASES -> notification.addAction(
                BrowseNotificationAction(label, UpdateSource.GITEE_RELEASES_URL),
            )
        }
    }

    companion object {
        /** 复用「更新」域通知组（与 StartupUpdateCheck 同组，一域一组惯例）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.updates"

        /** 应用级单次守卫：postStartupActivity 每项目触发，一个会话只弹一条。 */
        private val CLAIM_LOCK = Any()

        @Volatile
        private var claimedVersion: String? = null

        /** 领取本版本的展示权（并发多项目只成功一次）。 */
        private fun claim(version: String): Boolean = synchronized(CLAIM_LOCK) {
            if (claimedVersion == version) return false
            claimedVersion = version
            true
        }

        /** 测试重置（会话记忆清零）。 */
        internal fun resetForTest() {
            synchronized(CLAIM_LOCK) { claimedVersion = null }
        }
    }
}
