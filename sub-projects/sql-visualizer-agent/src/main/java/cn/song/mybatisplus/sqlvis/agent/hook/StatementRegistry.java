package cn.song.mybatisplus.sqlvis.agent.hook;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语句归属登记表（T-StatementWeave S3）：prepare 面（连接实现类的 prepareStatement/prepareCall
 * 织入出口）把「PS 实例 → SQL + URL」写入本表；语句面 execute 钩子对**无参 PS 形态**
 * （PreparedStatement.execute() 无 SQL 参数）从本表取 SQL。
 *
 * <p>内存边界（计划 §四.1 定案）：键为**弱引用**（WeakHashMap）——PS 被宿主弃用后随 GC 自然清账，
 * 连接池「close=归还」复用同一实例期间登记保持有效（不织 close，归还≠废弃）。原「不设规模上限」
 * 的论证在 T-CaptureCatchAll 之后不再成立（接口兜底会把**池包装语句**也登记进来，池长期持有 ⇒
 * 弱键不再短命），故补单桶硬上限 + 淘汰，见 {@link #MAX_PER_BUCKET_PROP}。
 *
 * <p>盲区（如实登记，计划 §三 S3）：attach（agentmain retransform）**之前**已 prepare 的 PS
 * 从未经过织入的 prepare 出口，本表无其 SQL——一期处置=跳过不上报 + 计数（[unresolvedCount]，
 * 宿主侧可观测），不产伪事件；字段反射兜底列二期（计划 §四.2/3 先证伪）。
 */
public final class StatementRegistry {

    /** 单条登记：SQL 文本 + prepare 时的连接 URL（归属链首选源）。 */
    public static final class Ctx {
        public final String sql;
        public final String url;
        /**
         * 批处理累积（statement 面 addBatch(String) 织入出口追加）。
         * 生命周期对齐驱动批缓冲的 JDBC 语义（F74）：executeBatch 消费即复位（onExecuteExit 读后
         * setLength(0)）、clearBatch() 织入出口清账（clearBatchOf）——因此规模天然有界＝宿主单个
         * 批周期内的 addBatch 总量，与驱动自身批缓冲同寿，不另设截断上限（截断会静默篡改事件内容）。
         */
        public final StringBuilder batch = new StringBuilder();

        /**
         * 参数槽（T-ConnCastSafety）：语句面 setXxx 入口织入写入。生命周期同 JDBC 语义——
         * 参数在语句上保持到下次覆盖/重置，跨多次 execute 复用同一份（不消费即清）。
         */
        public final ParamCapture params = new ParamCapture();

        Ctx(String sql, String url) {
            this.sql = sql;
            this.url = url;
        }
    }

    /**
     * F78（T-PerfSweep23）：登记表按语句身份分桶（16 桶条带锁）——原单张
     * synchronizedMap(WeakHashMap) 上 EXEC_CTX 读取与 addBatch 建账共用**同一把全局监视器**，
     * 池化高并发下跨线程语句操作理论串行点；分桶后同语句恒落同桶（单语句 check-then-act
     * 原子性保持——bucketCtxOf 的建账语义不变），跨语句并发摊到 16 把锁。
     * 弱键边界不变（每桶各自 WeakHashMap，PS 弃用随 GC 清账）。
     */
    private static final int BUCKET_COUNT = 16;

    @SuppressWarnings("unchecked")
    private static final Map<Object, Ctx>[] BUCKETS = createBuckets();

    @SuppressWarnings("unchecked")
    private static Map<Object, Ctx>[] createBuckets() {
        final Map<Object, Ctx>[] a = (Map<Object, Ctx>[]) new Map[BUCKET_COUNT];
        for (int i = 0; i < BUCKET_COUNT; i++) {
            a[i] = Collections.synchronizedMap(new WeakHashMap<Object, Ctx>());
        }
        return a;
    }

    /** 语句所在桶（identityHashCode 条带——同实例恒同桶）。 */
    private static Map<Object, Ctx> bucketFor(Object statement) {
        return BUCKETS[(System.identityHashCode(statement) & 0x7fffffff) % BUCKET_COUNT];
    }

    /** attach 前 prepare、映射无登记的 PS 执行次数（盲区可观测口径）。 */
    private static final AtomicLong UNRESOLVED = new AtomicLong();

    /**
     * 单桶登记上限（T-CaptureCatchAll 券 09③ 的内存侧上限）。
     *
     * <p>原设计「弱键 + 不设规模上限」的论证前提是「泄漏面=未回收的弱键本身，与宿主 PS 生命周期
     * 同寿」。但增强捕获扩到接口兜底后，**池包装语句也会被登记**（Hikari 的
     * {@code HikariProxyPreparedStatement} 等），而连接池会长期持有这些实例——弱键不再是短命对象，
     * 单桶可能涨到与池容量同量级，且每条登记带 SQL 文本 + 参数槽。故补一条硬上限。
     *
     * <p>上限语义是**淘汰**，不是「拒绝新登记」：拒绝新登记会让高并发下新语句全部采不到（丢采集），
     * 淘汰只丢一部分已登记语句（其 SQL 会由下一次 prepare 重新登记）。淘汰顺序按 WeakHashMap 的
     * 哈希桶序，**不保证保留最新条目**——触发上限时优先保内存不保采集，这是红线的取向。
     * 单桶上限 × 16 桶 = 进程内登记条数上界，默认 512/桶（≈8192 条），可经
     * {@code sqlvis.agent.registryMaxPerBucket} 调整。
     */
    static final String MAX_PER_BUCKET_PROP = "sqlvis.agent.registryMaxPerBucket";

    private static final int DEFAULT_MAX_PER_BUCKET = 512;

    private static final int MAX_PER_BUCKET = resolveMaxPerBucket();

    /** 因超上限被淘汰的累计登记数（内存侧熔断的可观测口径）。 */
    private static final AtomicLong EVICTED = new AtomicLong();

    private static int resolveMaxPerBucket() {
        try {
            final String raw = System.getProperty(MAX_PER_BUCKET_PROP);
            if (raw != null) {
                final int v = Integer.parseInt(raw.trim());
                if (v >= 16) {
                    return v;
                }
            }
        } catch (Throwable ignore) {
            // 非法值回落默认（注入失误不得改变生产行为）
        }
        return DEFAULT_MAX_PER_BUCKET;
    }

    /**
     * T-AttachPoolWarm（2026-10-09）：盲区**首条**取证打点——只打一次，后续零噪音。
     *
     * <p>现场复盘：{@link #unresolvedCount()} 此前只有「织入通道首条事件已产出」这一条出口
     * （[WovenEvents] 的 FIRST_FIRE 行会带 registryUnresolved）——**一旦整场都是盲区（一次事件都
     * 不产），该计数就完全无出口**，宿主应用侧读不到「钩子其实在跑、只是 SQL 取不到」。真机
     * 「存量连接零事件」恰是这种形态。本行把盲区次数与 PS 实现类名打到宿主 stderr，与
     * 「attach retransform」行配对即可判定：matched/wove>0 且出现本行 = 织入在位但 PS 无登记。
     */
    private static final AtomicBoolean UNRESOLVED_WARNED_ONCE = new AtomicBoolean();

    private StatementRegistry() {
    }

    /** prepare 面登记（幂等：同实例重 prepare 覆盖，值一致）。 */
    public static void register(Object statement, String sql, String url) {
        if (statement == null || sql == null || sql.isEmpty()) {
            return;
        }
        final Map<Object, Ctx> bucket = bucketFor(statement);
        synchronized (bucket) {
            bucket.put(statement, new Ctx(sql, url));
            evictIfNeeded(bucket);
        }
    }

    /** 取登记（无则 null）。 */
    public static Ctx get(Object statement) {
        if (statement == null) {
            return null;
        }
        return bucketFor(statement).get(statement);
    }

    /** 取登记，无则登记盲区计数后返回 null（execute 出口调用方据此跳过不上报）。 */
    public static Ctx getOrCountUnresolved(Object statement) {
        final Ctx ctx = get(statement);
        if (ctx == null) {
            final long n = UNRESOLVED.incrementAndGet();
            // 首条盲区取证（仅一次；见 UNRESOLVED_WARNED_ONCE 注释）——「钩子在跑但 SQL 取不到」
            // 的宿主侧唯一出口，与 WovenEvents 首条事件行同款取舍（一次告警防宿主日志刷屏）。
            if (UNRESOLVED_WARNED_ONCE.compareAndSet(false, true)) {
                System.err.println("[SqlVisAgent] 织入通道登记盲区：无登记 PS 执行被跳过（SQL 不可得）"
                        + " psClass=" + (statement == null ? "null" : statement.getClass().getName())
                        + " unresolvedCount=" + n
                        + " —— 一期已知盲区（attach 之前 prepare 的 PS 无 SQL 归属；仅此一条告警）");
            }
        }
        return ctx;
    }

    /** statement 面 addBatch 目标 ctx（无则惰性建——URL 未知，execute 出口再走归属链；同桶内 check-then-act 原子）。 */
    public static Ctx batchCtxOf(Object statement) {
        final Map<Object, Ctx> bucket = bucketFor(statement);
        synchronized (bucket) {
            Ctx ctx = bucket.get(statement);
            if (ctx == null) {
                ctx = new Ctx(null, null);
                bucket.put(statement, ctx);
                evictIfNeeded(bucket);
            }
            return ctx;
        }
    }

    /**
     * 超上限即淘汰（调用方必须已持有桶监视器）。
     *
     * <p>淘汰到 3/4 上限而不是「刚好到上限」：每次淘汰都要迭代整桶，刚好淘汰会让后续每次登记都
     * 触发一次迭代（摊销成本变高）；留 1/4 余量把摊销压到「每 MAX/4 次登记一次迭代」。
     * 迭代顺序是 WeakHashMap 的桶序（近似插入序），被淘汰的是较早登记、通常已不再执行的语句。
     */
    private static void evictIfNeeded(Map<Object, Ctx> bucket) {
        if (bucket.size() <= MAX_PER_BUCKET) {
            return;
        }
        final int target = MAX_PER_BUCKET * 3 / 4;
        long removed = 0L;
        final java.util.Iterator<Object> it = bucket.keySet().iterator();
        while (bucket.size() > target && it.hasNext()) {
            it.next();
            it.remove();
            removed++;
        }
        if (removed > 0L) {
            EVICTED.addAndGet(removed);
        }
    }

    /** 单桶登记上限（取证/测试读数）。 */
    public static int maxPerBucket() {
        return MAX_PER_BUCKET;
    }

    /** 因超上限被淘汰的累计登记数（取证/测试读数）。 */
    public static long evictedCount() {
        return EVICTED.get();
    }

    /** statement 面 clearBatch 出口：清批串（有登记才清——清账不建账，无登记即无事可清）。 */
    public static void clearBatchOf(Object statement) {
        if (statement == null) {
            return;
        }
        final Map<Object, Ctx> bucket = bucketFor(statement);
        synchronized (bucket) {
            final Ctx ctx = bucket.get(statement);
            if (ctx != null) {
                ctx.batch.setLength(0);
            }
        }
    }

    /** 盲区计数（排障/测试观测）。 */
    public static long unresolvedCount() {
        return UNRESOLVED.get();
    }

    /**
     * 语句面 setXxx 入口：把参数记进该语句的登记槽（T-ConnCastSafety）。
     *
     * <p>无登记即跳过、不建账：SQL 都取不到的盲区语句，参数记了也产不出事件，建账反而制造
     * 弱键表膨胀。停采由调用方（{@link SqlHookCallbacks#onParamSet}）先行短路，故停采期间
     * 不累积参数——恢复后不会把停采期间的参数错配到恢复后的语句上（与代理层 R5 同口径）。
     */
    public static void captureParam(Object statement, int index, Object value) {
        if (statement == null) {
            return;
        }
        final Ctx ctx = get(statement);
        if (ctx != null) {
            ctx.params.capture(index, value);
        }
    }

    /** 仅测试用：清表复位（夹具间隔离；与 AgentState.resetPortAbandonedForTest 同款测试缝）。 */
    public static void clearForTest() {
        for (Map<Object, Ctx> bucket : BUCKETS) {
            synchronized (bucket) {
                bucket.clear();
            }
        }
        UNRESOLVED.set(0L);
        UNRESOLVED_WARNED_ONCE.set(false);
        EVICTED.set(0L);
    }
}
