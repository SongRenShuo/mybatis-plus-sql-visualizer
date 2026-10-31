package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.diagnostic.Logger;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import kotlin.jvm.functions.Function0;

/**
 * T-CaptureCatchAll 券02：控制台桥**活性回落**（调研报告 R10「{@code paused} 假在线」）。
 *
 * <p>立项动因：{@code paused} 在「agent 在线且应用侧 ACK 确认在采集」时置真，控制台桥随即短路。
 * 但 ACK 只证明「应用侧打开了采集开关」，不证明「织入真的命中过驱动」——白名单漏一个驱动时，
 * agent 零事件、桥已让位，用户视角就是「插件坏了」。本类把「agent 沉默但控制台仍有 SQL」
 * 变成可自动恢复的事实：静默超过阈值后解封桥，事件恢复后立即重新让位（滞回）。</p>
 *
 * <p>判据（全部为可核实的事实，不做因果断言）：
 * <ul>
 * <li>让位已持续 ≥ 阈值（默认 10s，{@code -Dmpsv.capture.activityFallback.thresholdMs=<ms>} 可调）；</li>
 * <li>窗口内<b>零</b> agent 事件（{@link #onAgentEvent()} 由事件到达打点驱动）；</li>
 * <li>窗口内控制台<b>有 SQL 行</b>（由嗅探路径实证产出条目，不是「有任意控制台输出」）。</li>
 * </ul></p>
 *
 * <p>滞回：agent 事件一到达即解封回落（{@link #onAgentEvent()}），且静默计时从该事件重新起算——
 * 事件断断续续时不会在两条通道间反复横跳。</p>
 *
 * <p>默认开启（{@code -Dmpsv.capture.activityFallback=false} 可关）：这是纯降级收益，不引入任何新采集面。
 * 选择系统属性而非设置项的理由：它不是功能开关（用户无「想要/不想要」之分），只是排障旋钮；
 * 放进设置页要连带改复选框计数守卫与 UI，收益不抵噪音。解封状态本身在面板空态诊断行与设置页
 * 状态行可见（见 {@code CaptureDiagnostics.activityFallbackActive}）。</p>
 *
 * <p>线程：状态迁移由控制台输出线程（逐行）与 agent 读线程（事件）并发触发，统一 {@code synchronized}；
 * 判定本身 O(1)，只在 paused 期间执行。任何异常不得穿出（调用方在 filter 回调内）。</p>
 *
 * <p>master 侧为本 .kt 文件内 {@code object CaptureActivityFallback}（SqlLogConsoleBridge.kt），
 * k2j 按既有 SqlCaptureGate 分载先例独立成文件。</p>
 */
public final class CaptureActivityFallback {

    private CaptureActivityFallback() {
    }

    /** 默认静默阈值：10s（调研报告 D4 倾向 A）。 */
    public static final long DEFAULT_THRESHOLD_MS = 10_000L;

    /** 开关系统属性（{@code false}/{@code 0}/{@code off} 关闭，其余视为开启）。 */
    public static final String ENABLED_PROP = "mpsv.capture.activityFallback";

    /** 阈值系统属性（毫秒；非正数/非法回落 {@link #DEFAULT_THRESHOLD_MS}）。 */
    public static final String THRESHOLD_PROP = "mpsv.capture.activityFallback.thresholdMs";

    private static final Object LOCK = new Object();
    private static long pausedSinceMs = 0L;
    private static long lastAgentEventMs = 0L;
    private static long lastConsoleSqlMs = 0L;

    private static volatile boolean active = false;

    /** 解封次数（诊断/测试读数）。 */
    public static final AtomicLong ENGAGEMENTS = new AtomicLong();

    /** 事件恢复后重新让位次数（诊断/测试读数）。 */
    public static final AtomicLong RELEASES = new AtomicLong();

    /** 测试注入点：开关/阈值/时钟（null = 走系统属性与墙钟；master internal——测试同包可达）。 */
    static volatile Boolean enabledOverride;
    static volatile Long thresholdOverrideMs;
    static volatile Function0<Long> clockOverride;

    /** 当前时钟（毫秒）。 */
    public static long now() {
        Function0<Long> clock = clockOverride;
        return clock != null ? clock.invoke() : System.currentTimeMillis();
    }

    /** 是否启用（系统属性优先，出厂默认开启）。 */
    public static boolean enabled() {
        Boolean override = enabledOverride;
        if (override != null) {
            return override;
        }
        String prop = System.getProperty(ENABLED_PROP);
        String value = prop != null ? prop.trim().toLowerCase(Locale.ROOT) : null;
        return !("false".equals(value) || "0".equals(value) || "off".equals(value) || "no".equals(value));
    }

    /** 静默阈值毫秒。 */
    public static long thresholdMs() {
        Long override = thresholdOverrideMs;
        if (override != null) {
            return override;
        }
        String prop = System.getProperty(THRESHOLD_PROP);
        if (prop != null) {
            Long parsed = toLongOrNull(prop.trim());
            if (parsed != null && parsed > 0L) {
                return parsed;
            }
        }
        return DEFAULT_THRESHOLD_MS;
    }

    /** 解封态（false = 桥仍让位；agent 事件恢复即回落为 false）。 */
    public static boolean isActive() {
        return active;
    }

    /**
     * 闸门迁移回调（{@code cn.song.mybatisplus.visualizer.log.agent.AgentManager.updateOnline} 调用）：
     * 置真时记录让位起点；置假时清空全部状态（新一次接管从零起算，不继承上一轮的解封态）。
     */
    public static void onGateChanged(boolean paused) {
        onGateChanged(paused, now());
    }

    /** 显式时钟形态（master 默认实参 {@code now = now()} 的等价重载）。 */
    public static void onGateChanged(boolean paused, long now) {
        try {
            synchronized (LOCK) {
                if (paused) {
                    if (pausedSinceMs == 0L) {
                        pausedSinceMs = now;
                    }
                } else {
                    resetLocked();
                }
            }
        } catch (Throwable t) {
            // 红线：任何异常不得穿出（调用链在 agent 会话状态迁移内）
        }
    }

    /**
     * agent 事件到达打点（{@code cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter.markReceived}
     * 调用）：事件恢复 → 立即重新让位，静默计时从此刻重算（滞回）。
     */
    public static void onAgentEvent() {
        onAgentEvent(now());
    }

    /** 显式时钟形态（master 默认实参的等价重载）。 */
    public static void onAgentEvent(long now) {
        try {
            boolean wasActive;
            synchronized (LOCK) {
                lastAgentEventMs = now;
                wasActive = active;
                if (wasActive) {
                    active = false;
                    RELEASES.incrementAndGet();
                }
            }
            if (wasActive) {
                LOG.info("增强捕获活性回落解除：agent 事件恢复，控制台桥重新让位");
            }
        } catch (Throwable t) {
            // 红线：任何异常不得穿出（调用方在事件适配热路径）
        }
    }

    /**
     * 控制台行打点（仅在 {@code paused} 分支调用）：推进状态机并返回<b>本行是否应按正常路径解析</b>。
     *
     * @param sqlSeen 本行是否被嗅探路径实证解析出 SQL 条目（「控制台有 SQL 行」的唯一判据）
     * @return true = 解封态（含本行刚解封），调用方应解析该行；false = 继续让位
     */
    public static boolean onConsoleLine(boolean sqlSeen) {
        return onConsoleLine(sqlSeen, now());
    }

    /** 显式时钟形态（master 默认实参的等价重载）。 */
    public static boolean onConsoleLine(boolean sqlSeen, long now) {
        try {
            return evaluateConsoleLine(sqlSeen, now);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean evaluateConsoleLine(boolean sqlSeen, long now) {
        if (!enabled()) {
            synchronized (LOCK) {
                active = false;
            }
            return false;
        }
        long threshold = thresholdMs();
        boolean alreadyActive;
        boolean engagedNow;
        synchronized (LOCK) {
            if (pausedSinceMs == 0L) {
                pausedSinceMs = now;
            }
            if (sqlSeen) {
                lastConsoleSqlMs = now;
            }
            alreadyActive = active;
            engagedNow = !active && engageConditionMet(now, sqlSeen, threshold);
            if (engagedNow) {
                active = true;
                ENGAGEMENTS.incrementAndGet();
            }
        }
        if (engagedNow) {
            LOG.info("增强捕获活性回落：agent 已静默 ≥" + threshold + "ms 而控制台仍有 SQL 行，"
                    + "解封控制台桥恢复解析（本次解封第 " + ENGAGEMENTS.get()
                    + " 次；agent 事件恢复即重新让位）");
        }
        return alreadyActive || engagedNow;
    }

    /** 解封判据（须在 LOCK 内调用）：静默 ≥ 阈值 且 窗口内控制台实证有 SQL 行。 */
    private static boolean engageConditionMet(long now, boolean sqlSeen, long threshold) {
        long silenceStart = Math.max(pausedSinceMs, lastAgentEventMs);
        boolean silentLongEnough = now - silenceStart >= threshold;
        boolean consoleHasSql = sqlSeen || (lastConsoleSqlMs > 0L && now - lastConsoleSqlMs <= threshold);
        return silentLongEnough && consoleHasSql;
    }

    /** 测试/项目销毁：清空全部状态与计数（不动测试注入点——由 {@link #resetForTest()} 一并清）。 */
    public static void reset() {
        synchronized (LOCK) {
            resetLocked();
        }
    }

    private static void resetLocked() {
        active = false;
        pausedSinceMs = 0L;
        lastAgentEventMs = 0L;
        lastConsoleSqlMs = 0L;
    }

    /** 测试收尾：状态 + 注入点 + 计数全部复位（master internal——测试同包可达）。 */
    static void resetForTest() {
        synchronized (LOCK) {
            resetLocked();
        }
        enabledOverride = null;
        thresholdOverrideMs = null;
        clockOverride = null;
        ENGAGEMENTS.set(0L);
        RELEASES.set(0L);
    }

    /** Kotlin {@code String.toLongOrNull} 等价（非数值/溢出 → null）。 */
    private static Long toLongOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Logger LOG =
            Logger.getInstance("cn.song.mybatisplus.visualizer.log.CaptureActivityFallback");
}
