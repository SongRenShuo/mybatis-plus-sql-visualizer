package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 首次捕获成功指路（T-UxOnboard T-UX-03；T-NotifyTune 券 B 降频）：
 *
 * 首次捕获到 SQL 时经预览组发一条通知，指路 SQL 日志工具窗（带「打开面板」动作）——
 * 新用户从「代码侧引导」（T-UX-01）自然过渡到「日志侧能力」。
 *
 * 为什么降频：原实现把「一次性」存在 companion 的 [alreadyGuided] 里，只活在本 IDE 进程 →
 * 每重启 IDE / 每拉起一次 runIde 沙盒就再弹一次，用户体感为高频（2026-11-11 用户反馈）。
 * 现收敛为**每安装一次性**（[PluginSettings.State.firstCaptureGuideShown]，跨会话/跨项目），
 * 并补开关（[PluginSettings.State.firstCaptureGuideEnabled]）——同时补齐弹窗克制红线的准入门禁。
 *
 * 其余防打扰约束：
 *  - 缓冲区非空跳过——首个入列条目之外缓冲里已有条目（更早会话捕获残留），说明用户已在用；
 *  - 日志窗已可见 = 用户已找到面板，跳过；
 *  - 本次回调里自动展开（`logAutoOpenOnCapture`）已生效 = 窗口本身即「发现」，跳过（不叠两条信号）；
 *  - headless（isUnitTestMode）直接静默（平台通知 API 测试进程降级路径，不入）。
 *
 * 挂载点：[SqlLogManager] 把本入口与自动展开同挂缓冲 onEntryAccepted 出口（每次成功入队
 * 即时回调），零新增监听链；决策矩阵抽 [shouldGuide] 纯函数供裸 JUnit 覆盖。
 */
object FirstCaptureGuide {

    private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

    /** 会话级热路径短路（@Volatile——入列回调可能来自控制台输出线程）；权威记忆是持久化字段。 */
    @Volatile
    var alreadyGuided: Boolean = false
        private set

    /**
     * 每次成功入队回调（[SqlLogManager] 挂载；headless 下恒静默）。
     *
     * @param autoOpened 本次回调里 `maybeAutoShow()` 是否真的请求了自动展开——是则窗口自身已是
     *   「发现」，不再叠一条气球（T-NotifyTune 券 B / D-6）。
     */
    fun onEntryAccepted(project: Project, buffer: SqlLogEntryBuffer, autoOpened: Boolean = false) {
        val headless = ApplicationManager.getApplication()?.isUnitTestMode == true
        val settings = PluginSettings.getInstance().state
        // O(1) size 访问器——本回调在控制台流式灌入热路径上，禁用 snapshot() 全量拷贝
        val windowVisible = runCatching {
            ToolWindowManager.getInstance(project).getToolWindow(SqlLogManager.TOOL_WINDOW_ID)?.isVisible == true
        }.getOrDefault(false)
        if (!shouldGuide(
                enabled = settings.firstCaptureGuideEnabled,
                alreadyGuided = alreadyGuided || settings.firstCaptureGuideShown,
                bufferCount = buffer.size(),
                windowVisible = windowVisible,
                autoOpened = autoOpened,
                unitTestMode = headless,
            )
        ) return
        alreadyGuided = true
        settings.firstCaptureGuideShown = true
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            runCatching {
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("onboarding.captureGuide.title"),
                        MpSqlVisualizerBundle.message("onboarding.captureGuide.content"),
                        NotificationType.INFORMATION,
                    )
                notification.addAction(
                    NotificationAction.createSimpleExpiring(
                        MpSqlVisualizerBundle.message("onboarding.captureGuide.openPanel"),
                    ) {
                        if (!project.isDisposed) {
                            ToolWindowManager.getInstance(project)
                                .getToolWindow(SqlLogManager.TOOL_WINDOW_ID)?.show()
                        }
                    },
                )
                notification.notify(project)
            }
        }
    }

    /**
     * 指路决策（纯函数，测试缝）：开关开 + 未指路/未展示 + 缓冲恰为 1 条（本条即首条）
     * + 窗口未可见 + 本次未自动展开 + 非 headless。入列回调触发时条目已入缓冲，size==1 即「首次捕获」。
     */
    internal fun shouldGuide(
        enabled: Boolean = true,
        alreadyGuided: Boolean,
        bufferCount: Int,
        windowVisible: Boolean = false,
        autoOpened: Boolean = false,
        unitTestMode: Boolean,
    ): Boolean = enabled && !alreadyGuided && bufferCount == 1 &&
        !windowVisible && !autoOpened && !unitTestMode

    /** 测试重置（会话记忆清零；持久化字段由用例自行设置）。 */
    internal fun resetForTest() {
        alreadyGuided = false
    }
}