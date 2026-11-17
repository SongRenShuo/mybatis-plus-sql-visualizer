// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/FirstCaptureGuide.kt 移植
package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;

import org.jetbrains.annotations.NotNull;

/**
 * 首次捕获成功指路（T-UxOnboard T-UX-03）：
 *
 * <p>会话内首次捕获到 SQL 时经预览组发一条通知，指路 SQL 日志工具窗（带「打开面板」动作）——
 * 新用户从「代码侧引导」（T-UX-01）自然过渡到「日志侧能力」。防打扰三重约束：
 * <ul>
 * <li>会话级一次性（object 记忆，IDE 重启自然重置）；</li>
 * <li>历史非空跳过——首个入列条目之外缓冲里已有条目（更早会话捕获残留），说明用户已在用；</li>
 * <li>headless（isUnitTestMode）直接静默（平台通知 API 测试进程降级路径，不入）。</li>
 * </ul>
 *
 * <p>挂载点：{@code SqlLogManager} 把本入口与自动展开同挂缓冲 onEntryAccepted 出口（每次成功入队
 * 即时回调），零新增监听链；决策矩阵抽 {@link #shouldGuide} 纯函数供裸 JUnit 覆盖。
 */
public final class FirstCaptureGuide {

    public static final FirstCaptureGuide INSTANCE = new FirstCaptureGuide();

    private FirstCaptureGuide() {
    }

    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** 会话级一次性记忆（volatile——入列回调可能来自控制台输出线程）。
     *  字段名刻意避开 {@code alreadyGuided}：Kotlin 调用方按属性名读取时优先解析同名字段，
     *  字段私有即报「不可访问」（master 是 {@code private set} 属性，外部只读，故走 getter）。 */
    private static volatile boolean alreadyGuidedState = false;

    public static boolean isAlreadyGuided() {
        return alreadyGuidedState;
    }

    /** Kotlin {@code var alreadyGuided}（{@code private set}）的 JVM 访问器等价入口。 */
    public static boolean getAlreadyGuided() {
        return alreadyGuidedState;
    }

    /**
     * 每次成功入队回调（SqlLogManager 挂载；headless 下恒静默）。
     *
     * @param autoOpened 本次回调里 maybeAutoShow() 是否真的请求了自动展开——是则窗口自身已是
     *   「发现」，不再叠一条气球（T-NotifyTune 券 B / D-6）。
     */
    public static void onEntryAccepted(@NotNull Project project, @NotNull SqlLogEntryBuffer buffer,
                                       boolean autoOpened) {
        cn.song.mybatisplus.visualizer.config.PluginSettings.State settings =
                cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().getState();
        boolean headless = ApplicationManager.getApplication() != null
            && ApplicationManager.getApplication().isUnitTestMode();
        boolean windowVisible = false;
        try {
            com.intellij.openapi.wm.ToolWindow tw = com.intellij.openapi.wm.ToolWindowManager
                    .getInstance(project).getToolWindow(SqlLogManager.TOOL_WINDOW_ID);
            windowVisible = tw != null && tw.isVisible();
        } catch (Throwable ignored) {
            windowVisible = false;
        }
        // O(1) size 访问器——本回调在控制台流式灌入热路径上，禁用 snapshot() 全量拷贝
        if (!shouldGuide(
                settings.getFirstCaptureGuideEnabled(),
                alreadyGuidedState || settings.getFirstCaptureGuideShown(),
                buffer.size(),
                windowVisible,
                autoOpened,
                headless)) {
            return;
        }
        alreadyGuidedState = true;
        settings.setFirstCaptureGuideShown(true);
        if (project.isDisposed()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                com.intellij.notification.Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("onboarding.captureGuide.title"),
                        MpSqlVisualizerBundle.message("onboarding.captureGuide.content"),
                        NotificationType.INFORMATION);
                notification.addAction(NotificationAction.createSimpleExpiring(
                    MpSqlVisualizerBundle.message("onboarding.captureGuide.openPanel"),
                    () -> {
                        if (!project.isDisposed()) {
                            com.intellij.openapi.wm.ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                                .getToolWindow(SqlLogManager.TOOL_WINDOW_ID);
                            if (toolWindow != null) {
                                toolWindow.show();
                            }
                        }
                    }));
                notification.notify(project);
            } catch (Throwable ignored) {
                // runCatching 等价：通知链路任何失败静默（指路属体验增强，不干扰捕获主链）
            }
        });
    }

    /**
     * 指路决策（纯函数，测试缝）：未指路过 + 缓冲恰为 1 条（本条即会话首条，历史非空则跳过）
     * + 非 headless。入列回调触发时条目已入缓冲，size==1 即「首次捕获」。
     */
    /**
     * 指路决策（纯函数，测试缝）：开关开 + 未指路/未展示 + 缓冲恰为 1 条（本条即首条）
     * + 窗口未可见 + 本次未自动展开 + 非 headless。入列回调触发时条目已入缓冲，size==1 即「首次捕获」。
     */
    public static boolean shouldGuide(boolean enabled, boolean alreadyGuided, int bufferCount,
                                      boolean windowVisible, boolean autoOpened, boolean unitTestMode) {
        return enabled && !alreadyGuided && bufferCount == 1
                && !windowVisible && !autoOpened && !unitTestMode;
    }

    /** 测试重置（会话记忆清零）。 */
    public static void resetForTest() {
        alreadyGuidedState = false;
    }
}
