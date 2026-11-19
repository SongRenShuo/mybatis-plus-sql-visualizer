package cn.song.mybatisplus.visualizer.log.agent;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.registry.Registry;
import kotlin.jvm.functions.Function0;
import kotlin.ranges.LongRange;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * T-HdAbsorb D215（吸收自 Hibernate Debugger 的 {@code CaptureSuspensionLedger} 停驻账本 +
 * {@code StatementDurationCorrelator} 停驻补偿思路）：调试器<b>挂起时段账本</b>与 agent 耗时补偿。
 *
 * <p>问题形态：用户在调试会话里打了断点，被监控应用的 JDBC 执行线程在断点处挂起——agent 的
 * {@code nanoTime} 差值把<b>挂起时长</b>一并计进耗时，条目呈现虚假的超慢查询（墙钟膨胀）。对方插件
 * 按线程累计停驻时长并在耗时里扣减；本仓的补偿语义（吸收时按捕获通路差异收敛）：
 *
 * <ol>
 *   <li><b>时段账本</b>：订阅平台调试器事件（{@code DebuggerManagerListener} 会话挂接 +
 *       {@code DebugProcessListener.processSuspended/processResumed}），按<b>挂起时间墙钟区间</b>记账
 *       （环窗有界，容量 {@value #MAX_INTERVALS_PRIVATE_NOTE}）。对方按线程累计，本仓按全局区间——agent 侧默认
 *       全线程挂起（suspend-all 策略），全局区间是忠实近似；线程级精确口径登记为已知边界；</li>
 *   <li><b>补偿</b>：{@link #compensatedDurationMs} 把事件的测量窗 {@code [end-duration, end)} 与账本区间求交，
 *       交集时长从耗时中扣除（下限 0）；无调试会话/账本空/交不起 → 原值返回（诚实降级，不猜）；</li>
 *   <li><b>回退通道</b>：Registry {@code sqlvis.log.durationSuspendCompensation}（默认开）关掉即不补偿。</li>
 * </ol>
 *
 * <p>失败面纪律：订阅/监听全程 try/catch——调试器 API 在 Community/无 debugger 插件环境
 * 缺类时静默降级为「不补偿」（与 DataSourceUrlProbe 的 DatabaseTools 反射纪律同款）。
 */
public final class DebuggerSuspendTracker {

    private DebuggerSuspendTracker() {}

    private static final Logger LOG = Logger.getInstance(DebuggerSuspendTracker.class);

    /** KDoc 容量占位（避免 javadoc {@value} 引用私有常量的告警）。 */
    private static final String MAX_INTERVALS_PRIVATE_NOTE = "512";

    /** Registry 总开关（plugin.xml {@code <registryKey>} 登记）。 */
    public static final String REGISTRY_KEY = "sqlvis.log.durationSuspendCompensation";

    /** 挂起区间环窗容量（条）：一次调试会话的断点命中量级远小于此，溢出丢最旧。 */
    private static final int MAX_INTERVALS = 512;

    /** 测试缝：时钟（毫秒）。 */
    public static volatile Function0<Long> clockMs = System::currentTimeMillis;

    /** 测试缝：总开关（生产＝{@code Registry.is(REGISTRY_KEY, true)}）。 */
    public static volatile Function0<Boolean> enabledProvider =
            () -> {
                try {
                    return Registry.is(REGISTRY_KEY, true);
                } catch (Throwable t) {
                    return true;
                }
            };

    private static final Object LOCK = new Object();

    /** 已挂接的 DebugProcess 引用（detach 时移除监听；弱身份防泄漏用简单集合即可，会话数极小）。 */
    private static final ArrayList<Object> attached = new ArrayList<>(4);

    /** 挂起区间账本（毫秒墙钟；同步面 = messageBus 调度线程 + 适配链读，synchronized 双护）。 */
    private static final ArrayDeque<LongRange> intervals = new ArrayDeque<>();

    /** 当前挂起起点（null = 未挂起）。 */
    private static Long suspendStartedAt = null;

    /** 安装次数（守卫读数）。 */
    static volatile int installCount = 0;

    /**
     * 安装调试会话监听（幂等）：经应用级 {@code DebuggerManagerListener} topic 挂接——每个新调试
     * 会话挂 processListener，suspend/resume 记账。反射加载 debugger 面（Community/无
     * debugger 插件环境缺类 → 静默降级，onSuspended/onResumed 无人调用即零账本）。
     */
    public static void install() {
        boolean already;
        synchronized (LOCK) {
            if (installCount > 0) {
                already = true;
            } else {
                installCount++;
                already = false;
            }
        }
        if (already) return;
        try {
            Class<?> topicClass = Class.forName("com.intellij.debugger.impl.DebuggerManagerListener");
            Class<?> managerClass = Class.forName("com.intellij.debugger.DebuggerManager");
            Object topic = topicClass.getField("TOPIC").get(null);
            Method getProcessMethod = managerClass
                    .getMethod("getProcess", com.intellij.execution.process.ProcessHandler.class);
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    topicClass.getClassLoader(),
                    new Class<?>[]{topicClass},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "sessionCreated" -> {
                                try {
                                    Object process = args != null && args.length > 0 ? args[0] : null;
                                    if (process != null) attachProcessListener(process);
                                } catch (Throwable t) {
                                    LOG.debug("DebuggerSuspendTracker attach failed", t);
                                }
                            }
                            case "sessionRemoved" -> synchronized (LOCK) {
                                attached.clear();
                            }
                        }
                        return null;
                    });
            com.intellij.openapi.application.Application connection = com.intellij.openapi.application.ApplicationManager.getApplication();
            java.util.Optional<Method> subscribe = java.util.Arrays.stream(connection.getClass().getMethods())
                    .filter(m -> m.getName().equals("subscribe") && m.getParameterCount() == 2)
                    .findFirst();
            if (subscribe.isPresent()) {
                subscribe.get().invoke(connection, topic, listener);
            }
            // getProcess 仅用于类型校验面（未调用）；防误报未使用告警
            if (getProcessMethod == null) {
                LOG.debug("DebuggerSuspendTracker: unreachable");
            }
            LOG.info("DebuggerSuspendTracker installed");
        } catch (Throwable t) {
            LOG.debug("DebuggerSuspendTracker install degraded (debugger classes unavailable)", t);
        }
    }

    /** 给单个 DebugProcess 挂 suspend/resume 监听（反射；失败只丢本会话的账本能力）。 */
    private static void attachProcessListener(Object process) {
        try {
            Class<?> listenerClass = Class.forName("com.intellij.debugger.impl.DebugProcessListener");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "processSuspended" -> onSuspended();
                            case "processResumed" -> onResumed();
                        }
                        return null;
                    });
            java.util.Optional<Method> addListener = java.util.Arrays.stream(process.getClass().getMethods())
                    .filter(m -> m.getName().equals("addDebugProcessListener") && m.getParameterCount() == 1)
                    .findFirst();
            if (addListener.isPresent()) {
                addListener.get().invoke(process, listener);
            }
            synchronized (LOCK) {
                if (attached.size() > 16) attached.remove(0);
                attached.add(process);
            }
        } catch (Throwable t) {
            LOG.debug("DebuggerSuspendTracker process attach failed", t);
        }
    }

    /** 挂起开始（DebugProcessListener.processSuspended 回调落点）。 */
    static void onSuspended() {
        synchronized (LOCK) {
            if (suspendStartedAt == null) suspendStartedAt = clockMs.invoke();
        }
    }

    /** 恢复（DebugProcessListener.processResumed 回调落点）：闭合区间入账。 */
    static void onResumed() {
        synchronized (LOCK) {
            Long started = suspendStartedAt;
            if (started == null) return;
            suspendStartedAt = null;
            long end = clockMs.invoke();
            if (end > started) {
                if (intervals.size() >= MAX_INTERVALS) intervals.removeFirst();
                intervals.addLast(new LongRange(started, end));
            }
        }
    }

    /** 测试缝：清账本（用例隔离）。 */
    public static void clearForTest() {
        synchronized (LOCK) {
            intervals.clear();
            suspendStartedAt = null;
        }
    }

    /** 测试缝：直接注入区间（单测构造账本，不造真调试会话）。 */
    public static void plantIntervalsForTest(List<LongRange> ranges) {
        synchronized (LOCK) {
            intervals.clear();
            if (ranges != null) {
                for (LongRange r : ranges) {
                    intervals.addLast(r);
                }
            }
        }
    }

    /**
     * 停驻补偿（纯函数，headless 直测）：测量窗 {@code [end - durationMs, end)} 与挂起区间求交，
     * 交集时长从耗时中扣除；结果下限 0。总开关关闭 / 账本空 / 交集为 0 → 原值返回。
     */
    public static long compensatedDurationMs(long endMs, long measuredDurationMs) {
        if (measuredDurationMs <= 0) return measuredDurationMs;
        if (!enabledProvider.invoke()) return measuredDurationMs;
        long from = endMs - measuredDurationMs;
        long overlap;
        synchronized (LOCK) {
            long sum = 0L;
            for (LongRange range : intervals) {
                long lo = Math.max(range.getFirst(), from);
                long hi = Math.min(range.getLast(), endMs);
                if (hi > lo) sum += hi - lo;
            }
            overlap = sum;
        }
        if (overlap <= 0) return measuredDurationMs;
        return Math.max(measuredDurationMs - overlap, 0L);
    }

    /** 账本是否可能非空（诊断行用；进程级粗判即可）。 */
    public static boolean hasSuspendHistory() {
        synchronized (LOCK) {
            return !intervals.isEmpty() || suspendStartedAt != null;
        }
    }
}
