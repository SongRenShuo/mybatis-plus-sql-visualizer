package cn.song.mybatisplus.visualizer.onboarding;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.PluginSettingsFolderConfigurable;
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBusConnection;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SQL 方言死路主动提示（T-NotifyTune 券 A）：
 *
 * <p>竞品 MyBatisCodeHelper-Pro 在打开 MyBatis 文件且方言未配置时主动弹一条「说后果 + 一键配置 +
 * 当前项目不再提示」的气球。本插件默认智能推断、多数项目零配置，故只吸收<b>形态</b>：
 * 只在推断走到死路（Status.UNAVAILABLE 且无方言名）时提示一次，不把方言变成必须配置项。</p>
 *
 * <p>触发约束（与 OnboardingAnchorTip 同形，热路径零涉足）：探测挂<b>低频入口</b>——
 * postStartupActivity 订阅 {@code FileEditorManagerListener.fileOpened}，后台线程先做一次文件文本
 * 启发式扫描（{@link #isSqlBearingFile}），命中才进方言决策；一次性——「已展示」持久化到
 * {@code PluginSettings.State.dialectTipShown}（application 级），每台机器至多一条；
 * 让位——首用锚点引导尚未展示且仍启用时，先让新用户看「预览怎么用」，不叠第二条气球；
 * 开关 + 本项目永久抑制（dialectTipEnabled / dialectTipSuppressedProjects），过弹窗克制红线
 * 准入五问第 1/3/4 问；headless（isUnitTestMode）下持久化语义照常、不弹通知。</p>
 *
 * <p>为什么不用 MyBatis 口径：本插件支持 MyBatis / MyBatis-Plus / MPJ / iBatis 2.x / MBG Example /
 * JPA-HQL / JdbcTemplate / 通用 XML 内联 SQL（2026-11-11 用户指出），识别面必须同宽。</p>
 */
public class DialectGapTip implements ProjectActivity {

    /** Kotlin suspend execute 的 Java 侧等价形态（无实际挂起点，直接同步执行）。 */
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        // 连接绑定 project（Disposable），项目关闭自动注销；弹过即注销（一次性语义下已无使命）。
        MessageBusConnection connection = project.getMessageBus().connect(project);
        connection.subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override
                    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                        onFileOpened(project, file, connection);
                    }
                });
        return Unit.INSTANCE;
    }

    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** 超大文件跳过探测（封顶一次扫描成本）。 */
    private static final long MAX_PROBE_BYTES = 1_000_000L;

    /** 探测进行中节流（文件连续打开时只保留一个后台扫描）。 */
    private static final AtomicBoolean PROBING = new AtomicBoolean(false);

    /** 一次性展示权领取锁（并发探测命中只弹一条）。 */
    private static final Object CLAIM_LOCK = new Object();

    /** Kotlin object 语义兼容实例。 */
    public static final DialectGapTip INSTANCE = new DialectGapTip();

    /**
     * 文件打开入口（EDT 回调）：守卫全走纯函数，命中才投一个后台扫描（低频 + 廉价）。
     *
     * @param connection 总线连接句柄（弹过后注销；null = 无连接上下文，仅测试直调）
     */
    public static void onFileOpened(@NotNull Project project,
                                    @NotNull VirtualFile file,
                                    @Nullable MessageBusConnection connection) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        boolean anchorTipPending = state.getOnboardingAnchorTipEnabled() && !state.getOnboardingAnchorTipShown();
        if (!shouldProbe(
                state.getDialectTipEnabled(),
                state.getDialectTipShown(),
                isSuppressed(state, project),
                anchorTipPending)) {
            return;
        }
        if (!isProbeTarget(file.getName())) {
            return;
        }
        if (!PROBING.compareAndSet(false, true)) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                probe(project, file, connection);
            } finally {
                PROBING.set(false);
            }
        });
    }

    /** 后台扫描：读文本（ReadAction）→ 启发式认领 → 方言决策 → 领取展示权 → 弹通知。 */
    private static void probe(@NotNull Project project,
                              @NotNull VirtualFile file,
                              @Nullable MessageBusConnection connection) {
        String text;
        try {
            text = ReadAction.compute(() -> {
                if (file.isValid() && file.getLength() > 0 && file.getLength() <= MAX_PROBE_BYTES) {
                    return VfsUtil.loadText(file);
                }
                return "";
            });
        } catch (Throwable t) {
            text = "";
        }
        if (!isSqlBearingFile(file.getName(), text)) {
            return;
        }
        if (project.isDisposed()) {
            return;
        }
        DialectDecisionEngine.Decision decision;
        try {
            decision = ReadAction.compute(() -> DialectRegistry.INSTANCE.decision(project, file));
        } catch (Throwable t) {
            return;
        }
        if (decision == null) {
            return;
        }
        if (!isDialectGap(decision.status(), decision.dialectName())) {
            return;
        }
        if (!claimAndMark()) {
            return;
        }
        if (connection != null) {
            connection.disconnect();
        }
        boolean unitTest;
        try {
            unitTest = ApplicationManager.getApplication().isUnitTestMode();
        } catch (Throwable t) {
            unitTest = false;
        }
        if (unitTest) {
            return; // headless：持久化已置位，通知跳过
        }
        ApplicationManager.getApplication().invokeLater(() -> notifyGap(project));
    }

    private static void notifyGap(@NotNull Project project) {
        if (project.isDisposed()) {
            return;
        }
        try {
            var notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                            MpSqlVisualizerBundle.message("dialectGapTip.title"),
                            MpSqlVisualizerBundle.message("dialectGapTip.content"),
                            NotificationType.WARNING);
            notification.addAction(NotificationAction.createSimpleExpiring(
                    MpSqlVisualizerBundle.message("dialectGapTip.configure"),
                    () -> {
                        if (!project.isDisposed()) {
                            PluginSettingsFolderConfigurable.openSettingsFocused(
                                    project,
                                    PluginSettingsFolderConfigurable.PAGE_ID_PREVIEW,
                                    MpSqlVisualizerBundle.message("settings.dialect"));
                        }
                    }));
            notification.addAction(NotificationAction.createSimpleExpiring(
                    MpSqlVisualizerBundle.message("dialectGapTip.suppressProject"),
                    () -> suppressForProject(project)));
            notification.notify(project);
        } catch (RuntimeException ignored) {
            // 通知链失败静默（提示是有价值非必需）
        }
    }

    /**
     * 领取一次性展示权：通过守卫则置「已展示」并返回 true（并发命中只有首个领取者返回 true）。
     * headless 测试同样走本入口——持久化语义与通知解耦（测试断言状态翻转）。
     */
    public static boolean claimAndMark() {
        synchronized (CLAIM_LOCK) {
            PluginSettings.State s = PluginSettings.getInstance().getState();
            if (!s.getDialectTipEnabled() || s.getDialectTipShown()) {
                return false;
            }
            s.setDialectTipShown(true);
            return true;
        }
    }

    /** 「本项目不再提示」：按项目路径哈希记入抑制集合（幂等）。 */
    public static void suppressForProject(@NotNull Project project) {
        String key = projectKey(project);
        PluginSettings.State state = PluginSettings.getInstance().getState();
        if (!state.getDialectTipSuppressedProjects().contains(key)) {
            state.getDialectTipSuppressedProjects().add(key);
        }
    }

    public static boolean isSuppressed(@NotNull PluginSettings.State state, @NotNull Project project) {
        return state.getDialectTipSuppressedProjects().contains(projectKey(project));
    }

    /**
     * 项目键（纯函数，测试缝）：优先项目根路径、退化项目名，SHA-256 前 8 字节十六进制——
     * 只存哈希不存明文路径，避免配置里泄漏用户目录。
     */
    public static String projectKey(@NotNull Project project) {
        String base = project.getBasePath() != null ? project.getBasePath() : project.getName();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(base.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(base.hashCode());
        }
    }

    /**
     * 展示决策（纯函数，测试缝）：开关开 + 未展示过 + 本项目未抑制 + 首用锚点引导不在待展示态。
     *
     * <p>「让位」只在锚点引导<b>仍启用且尚未展示</b>时生效——若用户关掉了锚点引导，它永远不会展示，
     * 此时不应连带压掉方言提示。</p>
     */
    public static boolean shouldProbe(boolean enabled,
                                      boolean alreadyShown,
                                      boolean suppressedForProject,
                                      boolean anchorTipPending) {
        return enabled && !alreadyShown && !suppressedForProject && !anchorTipPending;
    }

    /** 探测目标（纯函数，测试缝）：仅 Java / Kotlin / XML 源文件。 */
    public static boolean isProbeTarget(@NotNull String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return Set.of("java", "kt", "xml").contains(ext);
    }

    /** 死路判据（纯函数，测试缝）：仅「证据不足且无方言名」；歧义/冲突已有面板内处理，不再主动弹。 */
    public static boolean isDialectGap(@Nullable DialectDecisionEngine.Status status,
                                       @Nullable String dialectName) {
        return status == DialectDecisionEngine.Status.UNAVAILABLE && dialectName == null;
    }

    /**
     * 「SQL 承载文件」判定（纯函数，测试缝）：文本级子串匹配，零 PSI resolve / 零索引依赖。
     *
     * <p>覆盖本插件全部支持面（2026-11-11 用户要求「不止 MyBatis」）：
     * Java/Kotlin——Wrapper 链、MyBatis 注解 SQL、JPA @Query、JdbcTemplate、iBatis SqlMapClient；
     * XML——mapper 语句标签、Hibernate hbm、iBatis {@code <sqlMap>} / {@code <statement>} /
     * {@code <procedure>}。</p>
     *
     * <p>与 OnboardingAnchorTip.anchorHintHit <b>刻意分开</b>：那是首用引导的语义与误报面，
     * 改成超集会连带改变它的触发。</p>
     */
    public static boolean isSqlBearingFile(@NotNull String fileName, @NotNull String text) {
        if (text.isEmpty()) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if ("java".equals(ext) || "kt".equals(ext)) {
            for (String hint : JAVA_HINTS) {
                if (text.contains(hint)) {
                    return true;
                }
            }
            return false;
        }
        if ("xml".equals(ext)) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (String hint : XML_HINTS) {
                if (lower.contains(hint.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /** Java/Kotlin 侧锚点（大小写敏感——类名/注解形态固定，误报面最小）。 */
    private static final List<String> JAVA_HINTS = List.of(
            // MyBatis / MyBatis-Plus / MPJ Wrapper 链
            "QueryWrapper", "UpdateWrapper", "JoinWrapper", "MPJLambdaWrapper", "MPJQueryWrapper", "Wrappers.",
            // MyBatis 注解 SQL
            "@Select", "@Insert", "@Update", "@Delete",
            // JPA / HQL
            "@Query", "@NativeQuery",
            // Spring JdbcTemplate
            "JdbcTemplate", "jdbcTemplate", "queryForList(", "queryForObject(", "queryForMap(",
            // iBatis 2.x Java 调用
            "SqlMapClient", "getSqlMapClient");

    /** XML 侧锚点（大小写不敏感）。 */
    private static final List<String> XML_HINTS = List.of(
            "<select", "<insert", "<update", "<delete",
            "<hibernate-mapping", "<sql-query",
            "<sqlMap", "<statement", "<procedure");
}
