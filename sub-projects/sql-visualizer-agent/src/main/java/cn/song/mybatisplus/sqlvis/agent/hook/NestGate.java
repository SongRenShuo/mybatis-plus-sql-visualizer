package cn.song.mybatisplus.sqlvis.agent.hook;

/**
 * 「嵌套只报最内层」门（T-CaptureCatchAll 券 07，计划 D3=A）。
 *
 * <p><b>为什么需要。</b>接口匹配兜底会把连接池/日志包装类一并命中：HikariCP 的
 * {@code HikariProxyPreparedStatement}（{@code final}，{@code implements java.sql.PreparedStatement}）
 * 的 {@code execute()} 只是转发给底层驱动 PS；MyBatis 的 {@code PreparedStatementLogger} 同理
 * （JDK 动态代理）。两者与内层驱动 PS 都织上钩子后，**一次执行产两条事件**：IDE 侧 60s 指纹
 * 去重只会折叠成 {@code ×2}，关掉去重就是两行；{@code EXEC_CTX} 还会抬高
 * {@code unresolvedCount} 并打出误导性类名。
 *
 * <p><b>判据为什么不用类名黑名单。</b>类名黑名单会随生态漂移（Hikari 改包名、MyBatis 换代理实现、
 * 新池化框架出现都要改名单），而且「哪些类算包装类」在字节层根本没有稳定特征。本门改用
 * **调用栈深度**：同一线程内，一个已织入的 execute 方法体里再进入另一个已织入的 execute
 * 方法体 = 嵌套；嵌套链只让**最内层**（先退出者）产事件，外层一律抑制。
 *
 * <p><b>语义与开销。</b>入口自增、出口自减，每线程一个两字段小对象；无嵌套时就是两次
 * ThreadLocal 读（外加一次复位，避免池线程残留）。判定按「**真的产出过**」而不是「进入过」：
 * 内层取不到 SQL（登记盲区）时不置位，外层仍可兜底产出（见 {@link #markEmitted()}）。
 * 所有方法自吞异常并返回「放行」——去重门自身绝不能成为丢事件或影响宿主的原因。
 *
 * <p><b>已知取舍</b>：真正的嵌套执行（存储过程内部再执行语句、同一线程里语句套语句）只报最内层，
 * 外层那条会缺——计划 §六 已登记，与「双报」相比是更小的损害（双报会污染归属与计数）。
 * 需要恢复旧行为可置 {@code -Dsqlvis.agent.nestGate=false}。
 *
 * <p><b>T-NestGateObserve（2026-11-10，只观测不改语义）。</b>磐维 A 模式真机证明本门存在**深度泄漏**：
 * 连接创建期之后该线程 depth 未归零、innerEmitted 残留为真，后续每条语句都被当成外层抑制，而
 * {@code nestSuppressed} 计为 0（被抑制的是最外层自己）。本轮加三样**纯观测**：链龄（链首 nanoTime +
 * 链号）、陈旧嵌套计数 {@code nestStale}（嵌套进入时链龄超阈值即 +1）、一次性取证 dump（debug 档、
 * 只第一次、前 12 帧），外加进程级 {@code maxDepth}。任何一项都不改变 depth/innerEmitted 的流转，
 * 也不改变任何返回值——上一轮在此处改语义导致 10 例转红整案回退（计划 §11.7b）。
 */
final class NestGate {

    /** 开关（默认开）：false = 关闭去重，恢复「每个已织 execute 都产事件」。 */
    static final String ENABLED_PROP = "sqlvis.agent.nestGate";

    /**
     * T-NestGateObserve：陈旧嵌套阈值（毫秒，默认 5000）。
     *
     * <p>真实嵌套链的存活时间是一次 execute 的时长（毫秒级）；链首距今超过该值**仍有新帧进入**，
     * 说明本链早已不是「一次执行」——典型成因是某帧出口钩子缺失、深度未归零，此线程此后每条语句
     * 都被当成外层抑制（磐维 A 模式真机形态，计划 §11.7）。
     */
    static final String STALE_MS_PROP = "sqlvis.agent.nestStaleMs";

    /** 陈旧阈值默认值（毫秒）。2026-11-10 收口：5s → 30s，避开长耗时语句的合法嵌套。 */
    static final long DEFAULT_STALE_MS = 30_000L;

    /**
     * 判定陈旧所需的**连续进入次数**（默认 3，系统属性 {@code sqlvis.agent.nestStaleStreak}）。
     *
     * <p>为什么不是「超阈值就算一次」：一条跑几十秒的语句内部再执行语句（存储过程、批量里逐批刷新）
     * 是**合法嵌套**，单次超阈值不足以定性。真正的泄漏形态是「链一直不闭合」——该线程此后每条语句
     * 都以「嵌套进入」的姿态出现，连续次数会持续累积；而合法长语句内部的嵌套次数是个位数且必然
     * 随后闭合（depth 归零即清零）。
     */
    static final String STALE_STREAK_PROP = "sqlvis.agent.nestStaleStreak";

    private static final int DEFAULT_STALE_STREAK = 3;

    /** 一次性取证 dump 的栈帧数上限（只在这一条路径抓栈，不在每条语句上抓）。 */
    static final int STALE_DUMP_FRAMES = 12;

    private static volatile boolean enabled = resolveEnabled();

    /**
     * T-PanweiInsertCapture F2：被本门抑制的帧累计（单调）。
     *
     * <p>为什么需要：本门的正确性依赖「深度最终归零」——一旦某帧的出口钩子缺失，该线程会永久处于
     * 「内层已产出」态，此后该线程的每条语句都被静默抑制。这类残留此前**零读数**：用户只看到
     * 「这条线程上的 SQL 全部不见」，日志里一个字都没有。计数随心跳上报，把「抑制了多少」变成事实。
     */
    private static final java.util.concurrent.atomic.AtomicLong SUPPRESSED =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * T-NestGateObserve：**陈旧嵌套**累计（纯观测，不参与任何抑制判定）。
     *
     * <p>与 {@link #SUPPRESSED} 的口径差别是本轮立项的核心：{@code nestSuppressed} 计的是
     * 「已有内层产出、本帧被判为外层」的帧；深度泄漏时被抑制的是**最外层自己**（链首帧早已返回，
     * 却仍占着 depth），那条路径一条都不计。{@code nestStale} 只回答一个问题：**这条线程有没有一条
     * 活过阈值的链**——有，就是深度未归零的硬证据。
     */
    private static final java.util.concurrent.atomic.AtomicLong NEST_STALE =
            new java.util.concurrent.atomic.AtomicLong();

    /** 进程级最高嵌套深度（取大）——判「泄漏后深度是否持续攀升」。 */
    private static final java.util.concurrent.atomic.AtomicInteger MAX_DEPTH =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 链号自增（dump 打点与后续读数配对用；不改判定）。 */
    private static final java.util.concurrent.atomic.AtomicLong CHAIN_IDS =
            new java.util.concurrent.atomic.AtomicLong();

    /** 陈旧链取证 dump 只打一次（AtomicBoolean，不刷屏）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean STALE_DUMPED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 测试缝：时钟（null = {@link System#nanoTime()}）。**仅测试可设**，经
     * {@link SqlHookCallbacks} 的转发方法注入（本类包内可见，跨包测试够不着）。
     */
    static volatile java.util.function.LongSupplier clockOverride;

    /** 陈旧阈值（纳秒；系统属性可覆盖，非法值回落默认）。 */
    private static volatile long staleNanos = resolveStaleNanos();

    /** 连续陈旧进入次数门槛（系统属性可覆盖，非法值回落默认）。 */
    private static volatile int staleStreak = resolveStaleStreak();

    /** 每线程嵌套态：depth = 当前已织 execute 帧数；innerEmitted = 本链已有内层产过事件。 */
    private static final class State {
        int depth;
        boolean innerEmitted;
        /**
         * 上次出栈帧的 token（T-OracleWrapperRegistry 同批修复）：一次调用可能命中**两条** ATHROW 出口钩子
         * （try 内抛出的 athrow + 处理器清理后的重抛），同一帧会被出口钩子访问两次。若两次都出栈，
         * 深度会被多减一次、嵌套判定随之错位（真机实证：一条失败 DDL 产出 3 条事件）。
         */
        long lastExitToken;
        /** T-NestGateObserve：本链起点（只在 depth 0→1 时记一次）；0 = 未记（异常路径）。 */
        long chainStartNanos;
        /** T-NestGateObserve：本链号（单调；dump 行用它配对）。 */
        long chainId;
        /** T-NestGateObserve：本链内「超阈值仍进入」的连续次数（链首重置）。 */
        int staleStreak;
        /** T-NestGateObserve：本链是否已计过一次陈旧（同一链只计一次，避免泄漏期刷计数）。 */
        boolean staleCounted;
    }

    private static final ThreadLocal<State> STATE = new ThreadLocal<State>() {
        @Override
        protected State initialValue() {
            return new State();
        }
    };

    private NestGate() {
    }

    /** 当前线程进入一个已织 execute 帧。 */
    static void enter() {
        if (!enabled) {
            return;
        }
        try {
            final State s = STATE.get();
            if (s.depth == 0) {
                // 上一条链已完结：复用同一 State 并复位（等价于旧实现在出口 remove，
                // 但不会每次执行都新建对象，也不留「深度 0 却仍标记已产出」的残态）。
                // lastExitToken 也在这里清——它只服务「同帧第二条 ATHROW」这一窗口。
                s.innerEmitted = false;
                s.lastExitToken = 0L;
                // T-NestGateObserve：链龄起点只在链首记一次——普通语句每帧一次 nanoTime，
                // 与入口 token 的取值同量级；嵌套帧与出口路径**不**再读时钟。
                s.chainStartNanos = nowNanos();
                s.chainId = CHAIN_IDS.incrementAndGet();
                // 新链：陈旧连续计数与「已计过」标记一并清零（同一链只计一次陈旧）
                s.staleStreak = 0;
                s.staleCounted = false;
            } else {
                // 疑似嵌套：这里是唯一的第二个时钟读取点，也是陈旧判定唯一发生地。
                // 纯计数（NEST_STALE / MAX_DEPTH / 一次性 dump），**不**参与下面 depth 的流转。
                noteNestedEnter(s);
            }
            s.depth++;
            noteMaxDepth(s.depth);
        } catch (Throwable ignore) {
            // 门自身失败：不计数即等于关闭本帧去重（放行），绝不影响采集
        }
    }

    /**
     * T-NestGateObserve：嵌套进入时的**纯观测**（不改 depth / innerEmitted / 返回值）。
     *
     * <p>判定（2026-11-10 收口）：链首距今 &gt; {@link #STALE_MS_PROP}（默认 **30s**）**且**本链的
     * 连续陈旧进入次数达到 {@link #STALE_STREAK_PROP}（默认 **3**）才计一次陈旧，同一链只计一次。
     * 单次超阈值不足以定性——一条跑几十秒的语句内部再执行语句是合法嵌套；真泄漏的形态是
     * 「链一直不闭合」，该线程此后每条语句都以嵌套进入出现，连续次数持续累积。
     * 陈旧本身不产生任何行为变化：抑制语义仍由 depth/innerEmitted 决定（本轮只观测，见计划 §11.7b）。
     */
    private static void noteNestedEnter(State s) {
        if (s.staleCounted) {
            return; // 同一链已计过一次，不再重复计（泄漏期不刷读数）
        }
        final long start = s.chainStartNanos;
        if (start == 0L) {
            return; // 未记起点（异常路径/旧 State）：无法判龄，不计
        }
        final long ageNanos = nowNanos() - start;
        if (ageNanos <= staleNanos) {
            s.staleStreak = 0; // 未超阈值：链仍「年轻」，连续计数清零
            return;
        }
        s.staleStreak++;
        if (s.staleStreak < staleStreak) {
            return; // 未达连续次数门槛（可能是合法长语句内部的少量嵌套）
        }
        s.staleCounted = true;
        NEST_STALE.incrementAndGet();
        // 取证 dump：只在 debug 档、只第一次——不打 SQL 原文，不刷屏。
        if (cn.song.mybatisplus.sqlvis.agent.CaptureBudget.isVerboseLogging()
                && STALE_DUMPED.compareAndSet(false, true)) {
            dumpStaleChain(s, ageNanos);
        }
    }

    /**
     * 一次性取证：一行摘要 + 当前栈前 {@link #STALE_DUMP_FRAMES} 帧。
     *
     * <p>为什么栈只在这里抓：正常语句（depth==0→1）与每次出口都不抓栈，抓栈成本只付一次。
     * 栈里出现的是**触发陈旧判定的那个嵌套 enter 的调用链**（例如驱动的 executeUpdate 及其调用方），
     * 而「哪一帧没配平」要看链首帧——它不是当前栈上的帧，故 dump 与 {@code chainId/ageMs} 联合读。
     */
    private static void dumpStaleChain(State s, long ageNanos) {
        try {
            System.err.println("[SqlVisAgent] 门禁陈旧链 chainId=" + s.chainId
                    + " ageMs=" + (ageNanos / 1_000_000L)
                    + " depth=" + (s.depth + 1)
                    + " thresholdMs=" + (staleNanos / 1_000_000L));
            final StackTraceElement[] frames = new Throwable().getStackTrace();
            final int n = Math.min(frames.length, STALE_DUMP_FRAMES);
            for (int i = 0; i < n; i++) {
                System.err.println("  at " + frames[i]);
            }
        } catch (Throwable ignore) {
            // 取证失败绝不影响采集
        }
    }

    /** 进程级最高嵌套深度取大（无锁 CAS；纯计数）。 */
    private static void noteMaxDepth(int depth) {
        int cur = MAX_DEPTH.get();
        while (depth > cur) {
            if (MAX_DEPTH.compareAndSet(cur, depth)) {
                return;
            }
            cur = MAX_DEPTH.get();
        }
    }

    /** 读时钟（测试缝可注入；注入器抛错时回落系统时钟，保证 enter 的流转不被观测量影响）。 */
    private static long nowNanos() {
        final java.util.function.LongSupplier clock = clockOverride;
        if (clock == null) {
            return System.nanoTime();
        }
        try {
            return clock.getAsLong();
        } catch (Throwable ignore) {
            return System.nanoTime();
        }
    }

    /**
     * 当前线程离开一个已织 execute 帧。
     *
     * @param token 入口回调返回的计时起点（同一帧的唯一标识）
     * @return true = 本帧**可以尝试**产出（链上还没有内层真的产过事件）
     */
    static boolean exit(long token) {
        if (!enabled) {
            return true;
        }
        try {
            final State s = STATE.get();
            if (s.lastExitToken != 0L && s.lastExitToken == token) {
                // 同一帧的第二个出口（try 内 athrow + 处理器重抛，字节码层是两条 ATHROW）：
                // 已经出栈过一次，不再重复出栈、也不再产出。**这条判定必须排在深度复位之前**——
                // 最外层帧第一次出栈时深度已归零，若先按「深度<=0」放行，同帧第二条 ATHROW 会再产一条
                // （真机实证：一条失败 DDL 出 3 条事件）。
                return false;
            }
            s.lastExitToken = token;
            if (s.depth > 0) {
                s.depth--;
            }
            final boolean mayEmit = !s.innerEmitted;
            if (!mayEmit) {
                // T-PanweiInsertCapture F2：被本门抑制的帧数——「同一秒 SELECT 有、INSERT 没有」
                // 若源于该线程的残留态（深度未归零），这里是唯一读数。单调累计、随心跳上报。
                SUPPRESSED.incrementAndGet();
            }
            if (s.depth == 0) {
                // 最外层已退出：此后没有更外层需要被抑制，清标记即为完整复位。
                // lastExitToken 刻意不清——同帧的第二条 ATHROW 紧随其后，要靠它去重；下一次
                // enter()（depth==0）才重置。
                s.innerEmitted = false;
            }
            return mayEmit;
        } catch (Throwable t) {
            return true; // 门自身失败：放行（宁可多报，不可漏报）
        }
    }

    /**
     * 本帧**真的产出事件之后**调用（T-OracleWrapperRegistry）。
     *
     * <p><b>为什么不能像旧实现那样在 {@link #exit()} 里就置位。</b>{@code exit()} 只知道「本帧
     * 拿到发言权」，还不知道 SQL 是否取得到：登记表盲区（内层物理语句无登记）会让本帧一条都不产。
     * 旧实现把「进入过」当「产出过」，于是一次「内层盲区、外层有登记」的嵌套——正是 Oracle 包装器
     * 语句的形态——连外层那条本可产出的 SQL 一起被吞掉。置位只在这里做，且仅当 `depth > 0`
     * （最外层后面没有帧需要抑制）。
     */
    static void markEmitted() {
        if (!enabled) {
            return;
        }
        try {
            final State s = STATE.get();
            if (s.depth > 0) {
                s.innerEmitted = true;
            }
        } catch (Throwable ignore) {
            // 门自身失败：不抑制外层（宁可多报，不可漏报）
        }
    }

    /** 仅测试用：复位线程态与开关。 */
    static void resetForTest() {
        STATE.remove();
        enabled = resolveEnabled();
        SUPPRESSED.set(0L);
        // T-NestGateObserve：观测计数与取证闸门同批复位（用例间隔离；不碰抑制语义）
        NEST_STALE.set(0L);
        MAX_DEPTH.set(0);
        CHAIN_IDS.set(0L);
        STALE_DUMPED.set(false);
        clockOverride = null;
        staleNanos = resolveStaleNanos();
        staleStreak = resolveStaleStreak();
    }

    static void setEnabledForTest(boolean value) {
        enabled = value;
        STATE.remove();
    }

    /** 仅测试用：注入时钟（包内直用；跨包测试经 {@link SqlHookCallbacks} 转发）。 */
    static void setStaleMsForTest(long ms) {
        staleNanos = Math.max(0L, ms) * 1_000_000L;
    }

    /** T-PanweiInsertCapture F2：被本门抑制的帧累计（心跳 {@code nestSuppressed} 键取值）。 */
    static long suppressedCount() {
        return SUPPRESSED.get();
    }

    /** T-NestGateObserve：陈旧嵌套累计（心跳 {@code nestStale} 键取值）。纯观测。 */
    static long staleCount() {
        return NEST_STALE.get();
    }

    /** T-NestGateObserve：进程级最高嵌套深度（心跳 {@code nestMaxDepth} 键取值）。 */
    static int maxDepth() {
        return MAX_DEPTH.get();
    }

    /** 陈旧阈值（纳秒）——由系统属性解析，非法值回落 {@link #DEFAULT_STALE_MS}。 */
    private static long resolveStaleNanos() {
        try {
            final String raw = System.getProperty(STALE_MS_PROP);
            if (raw != null) {
                final long v = Long.parseLong(raw.trim());
                if (v >= 0L) {
                    return v * 1_000_000L;
                }
            }
        } catch (Throwable ignore) {
            // 非法值按默认
        }
        return DEFAULT_STALE_MS * 1_000_000L;
    }

    /** 连续陈旧进入门槛：系统属性优先，非法/小于 1 回落默认。 */
    private static int resolveStaleStreak() {
        try {
            final String raw = System.getProperty(STALE_STREAK_PROP);
            if (raw != null) {
                final int v = Integer.parseInt(raw.trim());
                if (v >= 1) {
                    return v;
                }
            }
        } catch (Throwable ignore) {
            // 非法值按默认
        }
        return DEFAULT_STALE_STREAK;
    }

    private static boolean resolveEnabled() {
        try {
            final String raw = System.getProperty(ENABLED_PROP);
            if (raw != null) {
                final String v = raw.trim();
                if ("false".equalsIgnoreCase(v) || "0".equals(v)) {
                    return false;
                }
            }
        } catch (Throwable ignore) {
            // 非法值按默认开
        }
        return true;
    }
}
