package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 设置文件 lastgood 防护的触发点（T-MagpieGems W3）。
 *
 * 为什么不用组件构造器/loadState 挂钩：坏文件场景下平台根本不会调 loadState（解析失败返回 null），
 * 钩子必然缺席；也不引入 `ApplicationInitializedListener`（243 已 Internal + suspend 化）——
 * 沿用本仓 postStartupActivity 惯例，首个项目打开时拉起一条 daemon 维护线程：
 * 启动延迟后做一次「损坏即存证回落、健康即刷新 .bak」，此后周期巡检（.bak 新鲜度上限=周期）。
 *
 * 应用级去重（多项目打开只拉一条线程）；headless/单测环境直接跳过（纯函数测试直接调
 * [StateFileLastGood.checkAndRecover]，接线测试用 [runMaintenanceOnce] 注入 targets/notifier）。
 *
 * 覆盖范围：应用级两份（`mp-sql-visualizer.xml` 主设置+历史+参数预设、`mp-sql-visualizer-suppress.xml`）；
 * 项目级 `.idea` 下文件二期跟进（已在 T-MagpieGems 计划登记）。
 */
class SettingsLastGoodStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!started.compareAndSet(false, true)) return
        val app = ApplicationManager.getApplication() ?: return
        if (app.isHeadlessEnvironment || app.isUnitTestMode) return
        val thread = Thread {
            try {
                Thread.sleep(INITIAL_DELAY_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            val notified = HashMap<String, StateFileLastGood.Status>()
            while (true) {
                try {
                    runMaintenanceOnce(defaultTargets(), ::notifyIfNeeded, notified)
                } catch (e: Throwable) {
                    // 维护线程绝不中断巡检：单轮失败记日志进下一轮
                    LOG.warn("SettingsLastGoodStartup: 巡检轮失败：$e")
                }
                try {
                    Thread.sleep(PERIOD_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }
        thread.isDaemon = true
        thread.name = THREAD_NAME
        thread.start()
        LOG.info("SettingsLastGoodStartup: lastgood 维护线程已拉起（initial=${INITIAL_DELAY_MS}ms, period=${PERIOD_MS}ms）")
    }

    internal data class Target(
        val main: Path,
        val componentName: String,
        val stateClass: Class<out Any>,
        val label: String,
        val apply: (Any) -> Unit,
    )

    internal fun defaultTargets(): List<Target> = listOf(
        Target(
            main = PathManager.getOptionsFile("mp-sql-visualizer").toPath(),
            componentName = "MpSqlVisualizerSettings",
            stateClass = PluginSettings.State::class.java,
            label = "插件设置/SQL 历史/参数预设",
            apply = { state -> dispatchEdt { PluginSettings.getInstance().loadState(state as PluginSettings.State) } },
        ),
        Target(
            main = PathManager.getOptionsFile("mp-sql-visualizer-suppress").toPath(),
            componentName = "MpSqlVisualizerSqlSuppressRules",
            stateClass = SqlSuppressRuleManager.State::class.java,
            label = "SQL 屏蔽规则",
            apply = { state -> dispatchEdt { SqlSuppressRuleManager.getInstance().loadState(state as SqlSuppressRuleManager.State) } },
        ),
    )

    /**
     * 一轮巡检：逐目标 checkAndRecover，状态变化时回调 notifier（HEALTHY 不通知）。
     * `lastNotified` 由调用方持有——跨轮去重，坏文件未修复期间同一状态只通知一次。
     */
    internal fun runMaintenanceOnce(
        targets: List<Target>,
        notifier: (Target, StateFileLastGood.Status, String?) -> Unit,
        lastNotified: MutableMap<String, StateFileLastGood.Status>,
    ) {
        for (target in targets) {
            val outcome = StateFileLastGood.checkAndRecover(
                mainFile = target.main,
                bakFile = target.main.resolveSibling(target.main.fileName.toString() + StateFileLastGood.BAK_SUFFIX),
                componentName = target.componentName,
                stateClass = target.stateClass,
                apply = target.apply,
            )
            val previous = lastNotified.put(target.label, outcome.status)
            if (outcome.status != previous && outcome.status != StateFileLastGood.Status.HEALTHY) {
                notifier(target, outcome.status, outcome.detail)
            }
        }
    }

    private fun notifyIfNeeded(target: Target, status: StateFileLastGood.Status, detail: String?) {
        val content = when (status) {
            StateFileLastGood.Status.RECOVERED ->
                MpSqlVisualizerBundle.message("settings.lastgood.recovered", target.label, detail ?: "-")
            StateFileLastGood.Status.CORRUPT_NO_BACKUP ->
                MpSqlVisualizerBundle.message("settings.lastgood.corruptNoBackup", target.label, detail ?: "-")
            else -> return
        }
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(MpSqlVisualizerBundle.message("settings.lastgood.title"), content, NotificationType.WARNING)
                .notify(null)
        } catch (e: Throwable) {
            // 通知失败不影响恢复本身
            LOG.warn("SettingsLastGoodStartup: 通知失败：$e")
        }
    }

    private fun dispatchEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication() ?: run(block)
        if (app.isDispatchThread) block() else app.invokeLater(block)
    }

    companion object {
        private val LOG = Logger.getInstance(SettingsLastGoodStartup::class.java)
        private val started = AtomicBoolean(false)

        /** 启动后延迟首查：给平台组件装载与首轮保存让路，避免与平台读并发。 */
        private const val INITIAL_DELAY_MS = 15_000L
        private const val PERIOD_MS = 5 * 60_000L
        private const val THREAD_NAME = "mpsv-settings-lastgood"
        private const val NOTIFICATION_GROUP = "cn.song.mybatisplus.visualizer.settingsguard"
    }
}
