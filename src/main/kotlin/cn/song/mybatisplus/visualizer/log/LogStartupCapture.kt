package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目启动自动监听（T-M31.14）：设置「项目启动时自动开始监听」开启时，
 * 项目打开且已有运行中的主控制台时挂载监听（等价自动点击「捕捉」）；
 * 无运行控制台时 [SqlLogManager.startCapture] 守卫跳过——项目真正启动运行时由
 * onConsoleAppeared 自动捕捉。
 *
 * T-M51-04-DEL：删除 ProjectManagerListener 兜底（projectOpened 已 for-removal），
 * 仅保留 postStartupActivity 触发。startCapture() 幂等，onConsoleAppeared 兜底捕捉。
 *
 * F-03 工具窗常驻与随项目启动：设置「项目打开时自动打开日志窗口」开启时，项目打开即
 * [decideStartupToolWindowAction] 决定动作——显示工具窗（懒启动：面板内容由平台在首次显示时
 * 才创建，关闭开关的项目打开零动作）并进入记录态（startCapture，幂等不重复挂载）。
 * 项目关闭 = 项目服务销毁 + premain/会话全收口（既有 dispose 链），自然「关闭即停」。
 */
class LogStartupCapture : ProjectActivity {

    override suspend fun execute(project: Project) {
        // T-EnhancedCaptureDefaultOn（2026-10-19）：项目打开期不再做注入确认——增强捕获默认开启，
        // 开关打开即授权，premain/attach 由运行配置与进程事件各自承接。
        val settings = PluginSettings.getInstance().state
        LOG.info("LogStartupCapture.execute: 触发（project=${project.name}, logAutoCaptureOnStart=${settings.logAutoCaptureOnStart}, suppressed=${settings.logAutoCaptureSuppressed}, autoShow=${settings.logToolWindowAutoShowOnStart}）")

        // F-03：工具窗随项目打开自动显示（在捕捉决策之前——窗口先到位，记录态随后接上）
        when (decideStartupToolWindowAction(
            autoShow = settings.logToolWindowAutoShowOnStart,
            suppressed = settings.logAutoCaptureSuppressed,
        )) {
            StartupToolWindowAction.NONE -> Unit
            StartupToolWindowAction.SHOW_ONLY -> {
                LOG.info("LogStartupCapture: 自动显示日志工具窗（用户曾显式停止自动监听——只开窗不强制记录态）")
                showLogToolWindow(project)
                // 与既有「已显式停止」语义对齐：仍挂载桥+轮询（检测项目启动），不进入 armed 态
                sqlLogManagerOrNull(project)?.startListeningOnly()
            }
            StartupToolWindowAction.SHOW_AND_CAPTURE -> {
                LOG.info("LogStartupCapture: 自动显示日志工具窗并进入记录态（F-03）")
                showLogToolWindow(project)
                tryStartCaptureManager(project)
            }
        }

        // 自动启动条件：设置开启 且 用户未显式停止
        if (!shouldStartCapture(settings.logAutoCaptureSuppressed, settings.logAutoCaptureOnStart, hasOpenProject = true)) return
        // T-ST-02b2：取服务前先判 isDisposed（runCatching 挡不住服务容器永久中毒，见 sqlLogManagerOrNull KDoc）
        val manager = sqlLogManagerOrNull(project) ?: return
        if (manager.processListenerActive) {
            // 20260907-2045 第四轮：进程级监听已接管——被动模式，项目打开不预 armed，
            // Run 项目时 onProjectProcessStarted 驱动捕捉（含 suppressed 清除，恢复链自洽）
            LOG.info("LogStartupCapture: 进程级监听已接管——被动等待项目 Run（project=${project.name}）")
            return
        }
        if (settings.logAutoCaptureSuppressed) {
            // 用户已显式停止：仍挂载桥+轮询（检测项目启动），但不进入 armed 态
            LOG.info("LogStartupCapture: 用户已显式停止，仅挂载桥+轮询（project=${project.name}）")
            manager.startListeningOnly()
            return
        }
        tryStartCapture(manager, project, attempt = 0)
    }

    /** F-03：显示日志工具窗（不抢焦点；平台懒建内容——此刻才创建 SqlLogPanel）。 */
    private fun showLogToolWindow(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow(SqlLogManager.TOOL_WINDOW_ID) ?: return@invokeLater
            tw.show()
            LOG.info("LogStartupCapture: 日志工具窗已随项目打开显示（懒启动——面板此刻才创建）")
        }
    }

    /** F-03：进入记录态（复用既有重试链；管理器取用走 sqlLogManagerOrNull 防毒化服务容器）。 */
    private fun tryStartCaptureManager(project: Project) {
        val manager = sqlLogManagerOrNull(project) ?: return
        if (manager.processListenerActive) {
            // 进程级监听已接管的被动模式：Run 项目时自然驱动捕捉，无需 armed
            LOG.info("LogStartupCapture: 进程级监听已接管——被动等待项目 Run（F-03 不重复 armed）")
            return
        }
        tryStartCapture(manager, project, attempt = 0)
    }

    private fun tryStartCapture(manager: SqlLogManager, project: Project, attempt: Int) {
        // T-Perf-21：sleep 5000 后复查——项目可能在重试等待期间被关闭（project.name 访问已
        // dispose project 会抛 AlreadyDisposed）
        if (project.isDisposed) {
            LOG.info("LogStartupCapture: project disposed during retry wait, abort")
            return
        }
        LOG.info("LogStartupCapture.tryStartCapture: 第 ${attempt + 1} 次尝试（project=${project.name}）")
        manager.startCapture()
        if (!manager.capturing && attempt < MAX_RETRIES && !project.isDisposed) {
            LOG.info("LogStartupCapture: 未生效，${RETRY_DELAY_MS}ms 后重试 (${attempt + 1}/$MAX_RETRIES)")
            ApplicationManager.getApplication().executeOnPooledThread {
                Thread.sleep(RETRY_DELAY_MS)
                tryStartCapture(manager, project, attempt + 1)
            }
        } else if (manager.capturing) {
            LOG.info("LogStartupCapture: 已成功进入捕捉态")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(LogStartupCapture::class.java)
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 5000L

        /**
         * 判断是否应启动自动捕捉（测试缝）：纯函数，从 [execute] 抽取的条件判断逻辑，
         * 供测试直接调用（无需 PSI 环境、无需 PluginSettings/SqlLogManager 服务）。
         *
         * 条件矩阵：
         *  - suppressed=true → 不启动（用户已显式停止）
         *  - suppressed=false, autoStart=true → 启动
         *  - suppressed=false, autoStart=false, hasOpenProject=true → 不启动（设置未开启）
         *  - suppressed=false, autoStart=false, hasOpenProject=false → 不启动（无打开项目）
         *
         * @param suppressed    用户是否已显式停止自动启动
         * @param autoStart     设置「项目启动时自动开始监听」是否开启
         * @param hasOpenProject 当前是否有打开的项目
         * @return true 应启动自动捕捉
         */
        internal fun shouldStartCapture(suppressed: Boolean, autoStart: Boolean, hasOpenProject: Boolean): Boolean {
            if (!autoStart) return false
            if (suppressed) return false
            if (!hasOpenProject) return false
            return true
        }

        /**
         * F-03 决策（测试缝，纯函数）：项目打开时工具窗该做什么。
         *
         * 条件矩阵：
         *  - autoShow=false → [StartupToolWindowAction.NONE]（懒启动：开关关闭的项目打开零动作）
         *  - autoShow=true, suppressed=true → [StartupToolWindowAction.SHOW_ONLY]
         *    （用户曾显式停止自动监听——窗口照开，不强制进入记录态）
         *  - autoShow=true, suppressed=false → [StartupToolWindowAction.SHOW_AND_CAPTURE]
         *    （显示 + 进入记录态；与「项目启动时自动开始监听」独立，本开关自带记录态语义）
         */
        internal fun decideStartupToolWindowAction(autoShow: Boolean, suppressed: Boolean): StartupToolWindowAction = when {
            !autoShow -> StartupToolWindowAction.NONE
            suppressed -> StartupToolWindowAction.SHOW_ONLY
            else -> StartupToolWindowAction.SHOW_AND_CAPTURE
        }
    }
}

/** F-03：项目打开时日志工具窗的动作档。 */
internal enum class StartupToolWindowAction {
    /** 什么都不做（开关关闭——懒启动）。 */
    NONE,

    /** 只显示工具窗，不强制进入记录态（用户曾显式停止自动监听）。 */
    SHOW_ONLY,

    /** 显示工具窗并进入记录态（startCapture）。 */
    SHOW_AND_CAPTURE,
}
