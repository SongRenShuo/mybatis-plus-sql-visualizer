package cn.song.mybatisplus.sqlvis.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 增强捕获兜底（catch-all）的预算、熔断与取证计数（T-CaptureCatchAll 券 09）。
 *
 * <p><b>为什么单独成类且零 ASM 依赖。</b>本类被 premain 路径（{@link AgentMain}）与 transformer
 * 入口（{@link SqlHookTransformer}）共同读取，而 premain 阶段任何一次多余的类初始化都可能成为
 * 「宿主起不来」的成因（见 {@code AgentMain} 的 T-JRebelPremainCrash 注释）。故本类只依赖
 * {@code java.util.concurrent.atomic}、{@code java.util} 集合与 {@code System}（全是 java.base），
 * 不引用任何织入器/ASM 类型。
 *
 * <p><b>红线口径（用户令）。</b>增强捕获的任何新增采集面都必须「失败即原样返回 + 异常全吞 +
 * 不改返回值 + 不改对象身份 + 有预算上限」。本类承担最后一条：三类上限 + 一个总闸。
 * <ul>
 *   <li>① 接口匹配**候选类**数上限（{@code catchAllScanLimit}，默认 5000）：只统计真正跑了接口
 *       判定的候选类（JDK 前缀、接口/枚举/数组/基本型、无 classloader、JDK 动态代理这些零成本
 *       排除项不占预算）。T-IbatisPanwei 券 E 修正：此前计的是遍历到的每一个类，5000 在大型应用里
 *       会按 `getAllLoadedClasses()` 的数组顺序截断，把未扫到的候选一并丢掉；</li>
 *   <li>② transformer 兜底累计耗时预算（{@code catchAllBudgetMs}，默认 300ms）：只累计
 *       **兜底分支**自身的耗时（子串预筛 + header 解析 + 兜底织入），不累计白名单路径；超预算即
 *       关闭兜底分支（白名单照旧），打一行诊断；</li>
 *   <li>③ 连续失败 N 次（{@code catchAllKillAfter}，默认 3）→ **整体 kill switch**：退回纯白名单，
 *       打一行诊断。成功一次即清零连败计数（「偶发一次」不熔断）。</li>
 * </ul>
 *
 * <p><b>默认值取向（计划 D5 保守档）</b>：宁可少采，不许拖慢/拖垮宿主。所有上限均可经系统属性
 * 覆盖（命名沿用既有 {@code sqlvis.agent.*} 风格），非法值一律回落默认——注入失误不得改变
 * 生产行为。
 *
 * <p><b>取证计数</b>：{@code matchedByInterface}/{@code interfaceWoven}/{@code interfaceFailed}
 * 由 attach 反射匹配轮写入，{@code transformMatched}/{@code transformWoven}/{@code skippedByVersion}
 * 由 transformer 写入；心跳帧（{@link cn.song.mybatisplus.sqlvis.agent.transport.SenderLoop}）
 * 带出这些计数，供 IDE 侧诊断行显示「本次 attach 到底命中多少」——不再靠「面板零条目」反推。
 */
public final class CaptureBudget {

    // ---------- 系统属性（全部可配，非法值回落默认） ----------

    /** 券 01：attach 反射式接口匹配总开关（默认开——零字节解析、单类失败即跳过）。 */
    static final String PROP_ATTACH_MATCH = "sqlvis.agent.catchAll";

    /** 券 06：transformer 兜底分派开关（默认**关**——需真机验证后再考虑默认打开）。 */
    static final String PROP_TRANSFORM_MATCH = "sqlvis.agent.catchAllTransform";

    /** 券 09①：接口匹配扫描类数上限。 */
    static final String PROP_SCAN_LIMIT = "sqlvis.agent.catchAllScanLimit";

    /** 券 09②：transformer 兜底分支累计耗时预算（毫秒）。 */
    static final String PROP_BUDGET_MS = "sqlvis.agent.catchAllBudgetMs";

    /** 券 09③：连续失败多少次触发整体 kill switch。 */
    static final String PROP_KILL_AFTER = "sqlvis.agent.catchAllKillAfter";

    /** 券 08：premain 启动后首轮补挂延迟（毫秒；0 = 不补挂，只记候选）。 */
    static final String PROP_PREMAIN_DELAY_MS = "sqlvis.agent.catchAllPremainDelayMs";

    /** 券 08：premain 补挂轮间隔（毫秒）。 */
    static final String PROP_PREMAIN_REPEAT_MS = "sqlvis.agent.catchAllPremainRepeatMs";

    /** 券 08：premain 补挂轮数上限（含首轮）。 */
    static final String PROP_PREMAIN_PASSES = "sqlvis.agent.catchAllPremainPasses";

    /** 券 E（T-IbatisPanwei）：attach 路径补挂首轮延迟（毫秒；0 = 不补挂，只跑 attach 当轮）。 */
    static final String PROP_ATTACH_DELAY_MS = "sqlvis.agent.catchAllAttachDelayMs";

    /** 券 E（T-IbatisPanwei）：attach 补挂轮间隔（毫秒）。 */
    static final String PROP_ATTACH_REPEAT_MS = "sqlvis.agent.catchAllAttachRepeatMs";

    /** 券 E（T-IbatisPanwei）：attach 补挂轮数上限。 */
    static final String PROP_ATTACH_PASSES = "sqlvis.agent.catchAllAttachPasses";

    // ---------- 默认值（计划 D5 保守档） ----------

    static final int DEFAULT_SCAN_LIMIT = 5000;
    static final long DEFAULT_BUDGET_MS = 300L;
    static final int DEFAULT_KILL_AFTER = 3;
    static final long DEFAULT_PREMAIN_DELAY_MS = 10_000L;
    static final long DEFAULT_PREMAIN_REPEAT_MS = 60_000L;
    static final int DEFAULT_PREMAIN_PASSES = 5;

    /**
     * attach 补挂默认节奏（券 E）：5s 后首轮、20s 后第二轮。
     *
     * <p>为什么是这两档而不是 premain 的 10s/60s×5：attach 到运行中应用时数据源/驱动**通常已经**
     * 加载完（不像 premain 那样必然早于 Spring 上下文），首轮已覆盖绝大多数候选；剩下的只是
     * 「attach 之后才懒加载数据源」这一类迟到形态（现场 P-4 的独立成因），两次 5s/20s 足够覆盖，
     * 又不至于在宿主里长期留着轮询线程。两次都只做反射判定 + retransform，成本与首轮同量级。
     */
    static final long DEFAULT_ATTACH_DELAY_MS = 5_000L;
    static final long DEFAULT_ATTACH_REPEAT_MS = 20_000L;
    static final int DEFAULT_ATTACH_PASSES = 2;

    /** 未登记 JDBC 实现的上报上限（券 D）：只报类名，绝不带 SQL/凭据/全路径。跨包测试亦读，故 public。 */
    public static final int MAX_UNREGISTERED = 8;

    /** 诊断行前缀（宿主 stderr 唯一出口；不含 SQL 原文/凭据/全路径）。 */
    static final String DIAG_PREFIX = "[SqlVisAgent] catch-all: ";

    // ---------- 配置（进程内只读；测试缝可改） ----------

    private static volatile boolean attachMatchEnabled = boolProp(PROP_ATTACH_MATCH, true);

    private static volatile boolean transformMatchEnabled = boolProp(PROP_TRANSFORM_MATCH, false);

    private static volatile int scanLimit = intProp(PROP_SCAN_LIMIT, DEFAULT_SCAN_LIMIT, 1);

    private static volatile long budgetNanos = msProp(PROP_BUDGET_MS, DEFAULT_BUDGET_MS);

    private static volatile int killAfter = intProp(PROP_KILL_AFTER, DEFAULT_KILL_AFTER, 1);

    // 注意：这两个是**毫秒**语义（直接喂 Thread.sleep），不能走 [msProp]（它返回纳秒）。
    // 2026-10-30 D3 实测缺陷：误用 msProp 后默认 10s 变 10^10ms（约 317 年），premain 补挂线程
    // 永不执行 → 未知驱动的 premain 形态 100% 静默。此处改用 [rawMsProp]。
    private static volatile long premainDelayMs = rawMsProp(PROP_PREMAIN_DELAY_MS, DEFAULT_PREMAIN_DELAY_MS);

    private static volatile long premainRepeatMs = rawMsProp(PROP_PREMAIN_REPEAT_MS, DEFAULT_PREMAIN_REPEAT_MS);

    private static volatile int premainPasses = intProp(PROP_PREMAIN_PASSES, DEFAULT_PREMAIN_PASSES, 1);

    // 同 premain 两个延迟字段：**毫秒**语义，直接喂 Thread.sleep，必须走 rawMsProp。
    private static volatile long attachDelayMs = rawMsProp(PROP_ATTACH_DELAY_MS, DEFAULT_ATTACH_DELAY_MS);

    private static volatile long attachRepeatMs = rawMsProp(PROP_ATTACH_REPEAT_MS, DEFAULT_ATTACH_REPEAT_MS);

    private static volatile int attachPasses = intProp(PROP_ATTACH_PASSES, DEFAULT_ATTACH_PASSES, 1);

    // ---------- 熔断态 ----------

    /** 连续失败计数（成功一次清零）。 */
    private static final AtomicInteger CONSECUTIVE_FAILURES = new AtomicInteger();

    /** 整体 kill switch：true = 退回纯白名单（不再做任何兜底匹配）。 */
    private static volatile boolean killed;

    /** kill switch 触发原因（诊断行/心跳用）。 */
    private static volatile String killReason;

    /** 兜底分支累计耗时（纳秒）。 */
    private static final AtomicLong TRANSFORM_NANOS = new AtomicLong();

    /** 耗时预算是否已耗尽（耗尽后兜底分支直返，白名单不受影响）。 */
    private static volatile boolean budgetExhausted;

    // ---------- 取证计数（心跳带出） ----------

    private static final AtomicLong INTERFACE_MATCHED = new AtomicLong();
    private static final AtomicLong INTERFACE_WOVEN = new AtomicLong();
    private static final AtomicLong INTERFACE_FAILED = new AtomicLong();
    private static final AtomicLong INTERFACE_SKIPPED = new AtomicLong();
    private static final AtomicLong ATTACH_MATCHED = new AtomicLong();
    private static final AtomicLong ATTACH_UNMODIFIABLE = new AtomicLong();
    private static final AtomicLong TRANSFORM_MATCHED = new AtomicLong();
    private static final AtomicLong TRANSFORM_WOVEN = new AtomicLong();
    private static final AtomicLong SKIPPED_BY_VERSION = new AtomicLong();

    /**
     * 券 D（T-IbatisPanwei）：接口命中但**不在驱动白名单**的类名，有界（{@link #MAX_UNREGISTERED}）、
     * 去重、只类名。锁保护而非并发容器：写入只在扫描轮（低频、单线程），读取在心跳线程。
     */
    private static final Object UNREGISTERED_LOCK = new Object();
    private static final Set<String> UNREGISTERED = new LinkedHashSet<String>();

    private CaptureBudget() {
    }

    // ---------- 读侧 ----------

    /** attach 反射匹配是否启用（券 01 总开关）。 */
    public static boolean attachMatchEnabled() {
        return attachMatchEnabled && !killed;
    }

    /** transformer 兜底分派是否启用（券 06；默认关，且受预算与 kill switch 约束）。 */
    public static boolean transformMatchEnabled() {
        return transformMatchEnabled && !killed && !budgetExhausted;
    }

    /** 接口匹配扫描类数上限（券 09①）。 */
    public static int scanLimit() {
        return scanLimit;
    }

    /** 整体 kill switch 是否已触发。 */
    public static boolean killed() {
        return killed;
    }

    /** kill switch 触发原因（未触发为 null）。 */
    public static String killReason() {
        return killReason;
    }

    /** 耗时预算是否已耗尽。 */
    public static boolean budgetExhausted() {
        return budgetExhausted;
    }

    /** 兜底分支累计耗时（毫秒，取证用）。 */
    public static long transformMillis() {
        return TRANSFORM_NANOS.get() / 1_000_000L;
    }

    /** premain 首轮补挂延迟（毫秒；0 = 不补挂）。 */
    public static long premainDelayMs() {
        return premainDelayMs;
    }

    /** premain 补挂轮间隔（毫秒）。 */
    public static long premainRepeatMs() {
        return premainRepeatMs;
    }

    /** premain 补挂轮数上限。 */
    public static int premainPasses() {
        return premainPasses;
    }

    /** attach 补挂首轮延迟（毫秒；0 = 不补挂）。 */
    public static long attachDelayMs() {
        return attachDelayMs;
    }

    /** attach 补挂轮间隔（毫秒）。 */
    public static long attachRepeatMs() {
        return attachRepeatMs;
    }

    /** attach 补挂轮数上限。 */
    public static int attachPasses() {
        return attachPasses;
    }

    public static long interfaceMatchedCount() {
        return INTERFACE_MATCHED.get();
    }

    /** 白名单轮命中类数（attach 名单轮；与接口兜底合计即 IDE 侧「本次 attach 命中多少」）。 */
    public static long attachMatchedCount() {
        return ATTACH_MATCHED.get();
    }

    /** 白名单轮里不可重转换（{@code isModifiableClass} 为假）的类数。 */
    public static long attachUnmodifiableCount() {
        return ATTACH_UNMODIFIABLE.get();
    }

    /** attach 命中总数（白名单 + 接口兜底）——IDE 侧零命中判据的取数口径。 */
    public static long attachMatchedTotal() {
        return ATTACH_MATCHED.get() + INTERFACE_MATCHED.get();
    }

    public static long interfaceWovenCount() {
        return INTERFACE_WOVEN.get();
    }

    public static long interfaceFailedCount() {
        return INTERFACE_FAILED.get();
    }

    public static long interfaceSkippedCount() {
        return INTERFACE_SKIPPED.get();
    }

    public static long transformMatchedCount() {
        return TRANSFORM_MATCHED.get();
    }

    public static long transformWovenCount() {
        return TRANSFORM_WOVEN.get();
    }

    /** 类文件 major 超 ASM 支持上限而被原字节放行的累计类数（券 05）。 */
    public static long skippedByVersionCount() {
        return SKIPPED_BY_VERSION.get();
    }

    // ---------- 写侧（取证） ----------

    public static void noteInterfaceMatched() {
        INTERFACE_MATCHED.incrementAndGet();
    }

    /** 白名单轮命中一个类（attach 名单轮调用）。 */
    public static void noteAttachMatched() {
        ATTACH_MATCHED.incrementAndGet();
    }

    /** 白名单轮遇到一个不可重转换的类。 */
    public static void noteAttachUnmodifiable() {
        ATTACH_UNMODIFIABLE.incrementAndGet();
    }

    public static void noteInterfaceWoven() {
        INTERFACE_WOVEN.incrementAndGet();
    }

    public static void noteInterfaceFailed() {
        INTERFACE_FAILED.incrementAndGet();
    }

    public static void noteInterfaceSkipped() {
        INTERFACE_SKIPPED.incrementAndGet();
    }

    public static void noteTransformMatched() {
        TRANSFORM_MATCHED.incrementAndGet();
    }

    public static void noteTransformWoven() {
        TRANSFORM_WOVEN.incrementAndGet();
    }

    public static void noteSkippedByVersion() {
        SKIPPED_BY_VERSION.incrementAndGet();
    }

    /**
     * 券 D：记下一个「接口命中但不在驱动白名单」的类名（有界 {@link #MAX_UNREGISTERED}、去重）。
     *
     * <p>只存点分类名：这是「下一个国产/未知驱动是谁」的取证替代路径（计划券 D），心跳把它带给 IDE，
     * 面板才能把「增强零产出」从猜测变成一个具名事实。达上限后新类名静默丢弃（诊断有界是硬要求，
     * 不能让一个未知驱动生态把心跳撑大）。
     */
    public static void noteUnregisteredDriver(String dotted) {
        if (dotted == null || dotted.isEmpty()) {
            return;
        }
        try {
            synchronized (UNREGISTERED_LOCK) {
                if (UNREGISTERED.size() >= MAX_UNREGISTERED) {
                    return;
                }
                UNREGISTERED.add(dotted);
            }
        } catch (Throwable ignore) {
            // 诊断记账失败绝不影响采集（红线）
        }
    }

    /** 券 D：未登记 JDBC 实现类名快照（只读副本，按首次发现顺序，≤{@link #MAX_UNREGISTERED}）。 */
    public static List<String> unregisteredDrivers() {
        try {
            synchronized (UNREGISTERED_LOCK) {
                return new ArrayList<String>(UNREGISTERED);
            }
        } catch (Throwable ignore) {
            return new ArrayList<String>(0);
        }
    }

    /**
     * 兜底分支耗时记账（券 09②）：累计值超预算即关闭兜底分支并打一行诊断。
     * 只由 transformer 的兜底分支调用，白名单路径不计入。
     */
    public static void noteTransformNanos(long nanos) {
        if (nanos <= 0L) {
            return;
        }
        final long total = TRANSFORM_NANOS.addAndGet(nanos);
        if (!budgetExhausted && total >= budgetNanos) {
            budgetExhausted = true;
            diagAlways("transform budget exhausted (" + (total / 1_000_000L) + "ms >= "
                    + (budgetNanos / 1_000_000L) + "ms), transform-level catch-all disabled"
                    + "; whitelist weaving unaffected");
        }
    }

    /** 兜底织入成功一次：连败计数清零（偶发失败不熔断）。 */
    public static void noteCatchAllSuccess() {
        CONSECUTIVE_FAILURES.set(0);
    }

    /**
     * 兜底织入失败一次：连败达 {@code catchAllKillAfter} 即触发整体 kill switch。
     *
     * <p>为什么「整体」而不是只关某一路：连续失败说明兜底形态在本 JVM/本驱动族上系统性不可用
     * （典型：某驱动字节形态触发 VerifyError），继续逐类尝试只是持续付成本。退回纯白名单是
     * 计划 §五 D5 的定案语义——**只减少增强覆盖，不改变白名单行为**。
     */
    public static void noteCatchAllFailure(String what) {
        final int n = CONSECUTIVE_FAILURES.incrementAndGet();
        if (!killed && n >= killAfter) {
            killed = true;
            killReason = what;
            diagAlways("kill switch tripped after " + n + " consecutive failures (last=" + what
                    + "), catch-all disabled; whitelist weaving unaffected");
        }
    }

    // ---------- 测试缝 ----------

    /** 仅测试用：复位全部熔断态与计数（用例间隔离；跨包测试亦用，故 public）。 */
    public static void resetForTest() {
        killed = false;
        killReason = null;
        budgetExhausted = false;
        TRANSFORM_NANOS.set(0L);
        CONSECUTIVE_FAILURES.set(0);
        INTERFACE_MATCHED.set(0L);
        INTERFACE_WOVEN.set(0L);
        INTERFACE_FAILED.set(0L);
        INTERFACE_SKIPPED.set(0L);
        ATTACH_MATCHED.set(0L);
        ATTACH_UNMODIFIABLE.set(0L);
        TRANSFORM_MATCHED.set(0L);
        TRANSFORM_WOVEN.set(0L);
        SKIPPED_BY_VERSION.set(0L);
        attachMatchEnabled = true;
        transformMatchEnabled = false;
        scanLimit = DEFAULT_SCAN_LIMIT;
        budgetNanos = DEFAULT_BUDGET_MS * 1_000_000L;
        killAfter = DEFAULT_KILL_AFTER;
        premainDelayMs = DEFAULT_PREMAIN_DELAY_MS;
        premainRepeatMs = DEFAULT_PREMAIN_REPEAT_MS;
        premainPasses = DEFAULT_PREMAIN_PASSES;
        attachDelayMs = DEFAULT_ATTACH_DELAY_MS;
        attachRepeatMs = DEFAULT_ATTACH_REPEAT_MS;
        attachPasses = DEFAULT_ATTACH_PASSES;
        synchronized (UNREGISTERED_LOCK) {
            UNREGISTERED.clear();
        }
        // 2026-10-30：verbose 属于进程级开关，测试必须复位回产品默认（静默），
        // 否则开了 verbose 的用例会把「默认静音」的判据污染给后续用例。
        verboseLogging = false;
    }

    static void setAttachMatchEnabledForTest(boolean value) {
        attachMatchEnabled = value;
    }

    static void setTransformMatchEnabledForTest(boolean value) {
        transformMatchEnabled = value;
    }

    static void setScanLimitForTest(int value) {
        scanLimit = Math.max(1, value);
    }

    static void setBudgetMsForTest(long ms) {
        budgetNanos = Math.max(0L, ms) * 1_000_000L;
    }

    /** 仅测试用：连败上限（跨包测试亦用，故 public）。 */
    public static void setKillAfterForTest(int value) {
        killAfter = Math.max(1, value);
    }

    static void setPremainScheduleForTest(long delayMs, long repeatMs, int passes) {
        premainDelayMs = Math.max(0L, delayMs);
        premainRepeatMs = Math.max(0L, repeatMs);
        premainPasses = Math.max(1, passes);
    }

    /**
     * 仅测试用：attach 补挂节奏。
     *
     * <p>`delayMs <= 0` 即**关闭补挂**（既有 attach 用例都按「不补挂」跑：遗留的 daemon 线程会在
     * 后续用例里继续 retransform 并改计数，正是 premain 侧早就踩过的污染形态）。
     */
    static void setAttachScheduleForTest(long delayMs, long repeatMs, int passes) {
        attachDelayMs = Math.max(0L, delayMs);
        attachRepeatMs = Math.max(0L, repeatMs);
        attachPasses = Math.max(1, passes);
    }

    // ---------- 属性解析 ----------

    private static boolean boolProp(String key, boolean def) {
        try {
            final String raw = System.getProperty(key);
            if (raw != null) {
                final String v = raw.trim();
                if ("true".equalsIgnoreCase(v) || "1".equals(v)) {
                    return true;
                }
                if ("false".equalsIgnoreCase(v) || "0".equals(v)) {
                    return false;
                }
            }
        } catch (Throwable ignore) {
            // 非法值回落默认（不因注入失误改变生产行为）
        }
        return def;
    }

    private static int intProp(String key, int def, int min) {
        try {
            final String raw = System.getProperty(key);
            if (raw != null) {
                final int v = Integer.parseInt(raw.trim());
                if (v >= min) {
                    return v;
                }
            }
        } catch (Throwable ignore) {
            // 非法值回落默认
        }
        return def;
    }

    /**
     * 毫秒属性 → **原样毫秒**（不做单位换算）。
     *
     * 与 [msProp] 的分工：需要纳秒的（预算/耗时比较）走 [msProp]；需要直接喂 `Thread.sleep`
     * 或按毫秒展示的走本方法。二者混用会出数量级错误（2026-10-30 D3 实测：premain 补挂
     * 延迟被放大 10^6 倍，线程永不执行）。
     */
    static long rawMsProp(String key, long defMs) {
        try {
            final String raw = System.getProperty(key);
            if (raw != null) {
                final long v = Long.parseLong(raw.trim());
                if (v >= 0L) {
                    return v;
                }
            }
        } catch (Throwable ignore) {
            // 非法值回落默认
        }
        return defMs;
    }

    /** 毫秒属性 → 纳秒（0 合法：预算 0 表示立即关闭兜底分支）。 */
    static long msProp(String key, long defMs) {
        try {
            final String raw = System.getProperty(key);
            if (raw != null) {
                final long v = Long.parseLong(raw.trim());
                if (v >= 0L) {
                    return v * 1_000_000L;
                }
            }
        } catch (Throwable ignore) {
            // 非法值回落默认
        }
        return defMs * 1_000_000L;
    }

    /**
     * 诊断/进度行（stderr；失败静默——诊断出口绝不二次抛错）。
     *
     * **默认静默**（2026-10-30 用户令：「控制台 [SqlVisAgent] 输出有点多，只保留必要的」）：
     * 匹配数、逐轮补挂、首条事件这类进度信息只在 `-javaagent:...=logLevel=debug` 时输出；
     * 真实降级/熔断/放弃走 {@link #diagAlways(String)}，任何级别都打。
     */
    static void diag(String msg) {
        if (!verboseLogging) return;
        diagAlways(msg);
    }

    /** 必报行：真实降级、预算耗尽、kill switch、注册盲区——与日志级别无关，永远输出。 */
    static void diagAlways(String msg) {
        try {
            System.err.println(DIAG_PREFIX + msg);
        } catch (Throwable ignore) {
            // stderr 不可用：静默
        }
    }

    /**
     * 是否输出诊断/进度行。由 {@link AgentMain} 解析完 agent 参数后写入（`logLevel=debug|verbose|trace`）。
     *
     * 刻意**不**在这里读 `AgentState`：本类被 premain 路径共用，KDoc 顶部已声明
     * 「只依赖 java.util.concurrent.atomic 与 System」，多引一个类的静态初始化就可能成为
     * 「宿主起不来」的成因。故级别由调用方推进来。
     */
    static volatile boolean verboseLogging;

    /** 供子包（hook / transport）判断是否需要输出进度行。 */
    public static boolean isVerboseLogging() {
        return verboseLogging;
    }

    /** 按 agent 参数里的日志级别设置诊断输出开关（非法值按关闭处理）。 */
    public static void setVerboseLogging(String level) {
        boolean verbose = level != null
            && ("debug".equalsIgnoreCase(level.trim())
                || "verbose".equalsIgnoreCase(level.trim())
                || "trace".equalsIgnoreCase(level.trim()));
        verboseLogging = verbose;
    }
}
