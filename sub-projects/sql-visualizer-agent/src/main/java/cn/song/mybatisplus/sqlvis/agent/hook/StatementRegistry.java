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

    /**
     * 2026-11-02 用户口径：「这条应该有问题时才出现，平时不出现」。
     *
     * <p>单条盲区（attach 前 prepare 的 PS）是**常态**，属诊断信息——默认静默，仅
     * {@code logLevel=debug} 打一次；累计到 [BLIND_ALARM_THRESHOLD] 次才说明整场都在盲区
     * （钩子在跑但 SQL 一律取不到），那是真实降级，任何级别必报一次。
     */
    private static final AtomicBoolean BLIND_ALARM_EMITTED = new AtomicBoolean();

    /** 系统性盲区判据：累计无登记执行达到该次数即升级为必报。 */
    private static final long BLIND_ALARM_THRESHOLD = 5L;

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

    /**
     * prepare 面登记（含**包装语句的内层实体语句**）——T-OracleWrapperRegistry。
     *
     * <p><b>为什么必须解一层包装。</b>Oracle thin 驱动对外返回的是包装器
     * {@code oracle.jdbc.driver.OraclePreparedStatementWrapper}（真机 `ShapeProbe` 实测：
     * `createStatement`/`prepareStatement`/`prepareCall` 分别返回
     * `OracleStatementWrapper`/`OraclePreparedStatementWrapper`/`OracleCallableStatementWrapper`），
     * {@code prepareStatement} 出口钩子登记的自然是包装器；而 {@code executeQuery()} 的织入点落在
     * 包内物理语句（{@code OraclePreparedStatement}/{@code T4CPreparedStatement}）上，回调拿到的
     * {@code this} 是**内层对象**。同一 SQL 的「登记」与「查询」落在两个不同对象上，查表必然落空——
     * 2026-11-04 真机形态：plain `Statement`（EXEC_PARAM，SQL 取首参）正常，PreparedStatement
     * 全丢，宿主 stderr 报「无登记 PS 执行被跳过 psClass=oracle.jdbc.driver.T4CPreparedStatement」。
     *
     * <p>参数槽与批串同理：{@code setXxx}/`addBatch` 的织入点在物理语句上，写的是内层对象的登记槽；
     * 只登记包装器时参数根本无处落。故这里把内层一并登记，一次修掉「SQL 归属」与「绑定参数」。
     *
     * <p>解包只做一层、按**字段类型**（可赋给 {@code java.sql.Statement}）而非字段名扫描，
     * 驱动改字段名不影响；扫描不到就退回只登记包装器（旧行为），绝不外抛。
     */
    public static void registerWithDelegates(Object statement, String sql, String url) {
        if (statement == null || sql == null || sql.isEmpty()) {
            return;
        }
        register(statement, sql, url);
        final Object delegate = delegateStatementOf(statement);
        if (delegate != null && delegate != statement) {
            register(delegate, sql, url);
        }
    }

    /**
     * 包装器持有的内层语句字段（按类缓存；`ClassValue` 不静态钉住驱动类，热部署可回收）。
     *
     * <p>缓存的是**候选字段数组**而不是单个字段：同一包装器上可能同时存在多个 Statement 类型字段
     * （Oracle 的 {@code OracleStatementWrapper} 就有 {@code statement}/{@code closedStatement}/
     * {@code closedStatementAC}），需要按声明顺序取第一个**当前非空**的，缓存单值会在某些形态下取到空槽。
     * 上限 4 个，纯防御——正常驱动 1~3 个。
     */
    private static final ClassValue<java.lang.reflect.Field[]> DELEGATE_FIELDS =
            new ClassValue<java.lang.reflect.Field[]>() {
                @Override
                protected java.lang.reflect.Field[] computeValue(Class<?> type) {
                    final java.util.List<java.lang.reflect.Field> out =
                            new java.util.ArrayList<java.lang.reflect.Field>(2);
                    try {
                        for (Class<?> k = type; k != null && k != Object.class && out.size() < 4;
                             k = k.getSuperclass()) {
                            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                                if (out.size() >= 4) {
                                    break;
                                }
                                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                                    continue;
                                }
                                if (!java.sql.Statement.class.isAssignableFrom(f.getType())) {
                                    continue;
                                }
                                try {
                                    f.setAccessible(true);
                                } catch (Throwable ignore) {
                                    continue; // 不可访问的候选直接剔除（如 JPMS 强封装场景）
                                }
                                out.add(f);
                            }
                        }
                    } catch (Throwable ignore) {
                        // 字段扫描失败 = 无内层可登记，退回旧行为
                    }
                    return out.toArray(new java.lang.reflect.Field[0]);
                }
            };

    /** 取包装器持有的内层语句（第一顺位非空者）；无则 null。 */
    private static Object delegateStatementOf(Object statement) {
        try {
            for (java.lang.reflect.Field f : DELEGATE_FIELDS.get(statement.getClass())) {
                final Object v = f.get(statement);
                if (v != null && v != statement) {
                    return v;
                }
            }
        } catch (Throwable ignore) {
            // 读取失败按无内层处理（登记表契约：拿不到就不登记，不影响宿主）
        }
        return null;
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
            // T-PanweiInsertCapture F3：盲区按 PS 实现类聚合（有界、只类名）——「哪个类一直取不到
            // SQL」是 H1 假说的唯一直接证据；此前只有总数与 debug 级别的首条类名，现场无法判类。
            noteBlindClass(statement == null ? "null" : statement.getClass().getName());
            // 单条盲区＝诊断（attach 前 prepare 的 PS 是常态）：默认静默，logLevel=debug 打一次。
            if (cn.song.mybatisplus.sqlvis.agent.CaptureBudget.isVerboseLogging()
                    && UNRESOLVED_WARNED_ONCE.compareAndSet(false, true)) {
                System.err.println("[SqlVisAgent] 织入通道登记盲区：无登记 PS 执行被跳过（SQL 不可得）"
                        + " psClass=" + (statement == null ? "null" : statement.getClass().getName())
                        + " unresolvedCount=" + n
                        + " —— 一期已知盲区（attach 之前 prepare 的 PS 无 SQL 归属）");
            }
            // 累计到阈值＝系统性盲区（整场取不到 SQL 的真实降级）：任何级别必报，只报一次。
            if (n >= BLIND_ALARM_THRESHOLD && BLIND_ALARM_EMITTED.compareAndSet(false, true)) {
                System.err.println("[SqlVisAgent] 织入通道系统性盲区：累计 " + n
                        + " 次无登记 PS 执行被跳过 —— 钩子在跑但 SQL 归属一律缺失，"
                        + "请确认采集是否在连接/语句建立之后才 attach（一期已知限制）");
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

    // ---------- T-PanweiInsertCapture F3：盲区具名事实 ----------

    /** 盲区类名上报上限（与心跳 {@code unreg} 同款有界口径）。 */
    public static final int MAX_BLIND_CLASSES = 8;

    /** 盲区 PS 实现类名（插入序、去重、有界；只类名，不含 SQL/参数）。 */
    private static final java.util.LinkedHashSet<String> BLIND_CLASSES =
            new java.util.LinkedHashSet<String>();

    /** 记一个盲区类名（超上限丢弃——保内存不保取证，与 unreg 同取向）。 */
    private static void noteBlindClass(String className) {
        if (className == null || className.isEmpty()) {
            return;
        }
        synchronized (BLIND_CLASSES) {
            if (BLIND_CLASSES.size() < MAX_BLIND_CLASSES) {
                BLIND_CLASSES.add(className);
            }
        }
    }

    /** 盲区类名快照（心跳 {@code blind} 键取值；只读副本）。 */
    public static java.util.List<String> blindClassNames() {
        synchronized (BLIND_CLASSES) {
            return new java.util.ArrayList<String>(BLIND_CLASSES);
        }
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
        synchronized (BLIND_CLASSES) {
            BLIND_CLASSES.clear();
        }
    }
}
