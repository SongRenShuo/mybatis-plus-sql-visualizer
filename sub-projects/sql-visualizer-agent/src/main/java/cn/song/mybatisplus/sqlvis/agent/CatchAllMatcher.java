package cn.song.mybatisplus.sqlvis.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口匹配兜底（T-CaptureCatchAll 券 01/08）：把「白名单优先 + 接口兜底」落成两个入口——
 * attach 时的反射匹配轮，以及 premain 阶段的候选记录 + 启动后补挂。
 *
 * <p><b>为什么兜底走反射而不是字节解析。</b>{@code getAllLoadedClasses()} +
 * {@code java.sql.Statement/Connection.isAssignableFrom} 是零 ASM 成本（调研 §2.1：覆盖
 * 「attach 到运行中应用」的全部白名单缺口，含连接池包装类），而 transformer 侧的全量字节解析
 * 是 200–515µs/类（调研 §2.2），红线不许走。因此 attach 主路径只做反射；字节预筛（券 06）是
 * 另一条**默认关闭**的补充通道，落在 {@link SqlHookTransformer}。
 *
 * <p><b>织入通道不变。</b>命中类登记进 {@link #acceptedSpecs()}（类名 → 兜底面形态），
 * transformer 对这批类走与白名单完全相同的 {@link StatementWeaver#weaveSpec} 通道——只改方法体、
 * 不增删成员、不改对象身份。{@code wrapConnection} 默认值不受任何影响（连接替换是另一条已关闭的
 * 旧通道，本类不触碰）。
 *
 * <p><b>premain 安全策略（券 08，红线核心）。</b>premain 阶段**绝不内联织入未知字节形态**：
 * premain 织入失败＝类定义失败＝宿主 JVM 起不来，比「采不到」严重一个数量级。故 premain 只
 * 记录候选（计数 + 一行诊断），真正的接口匹配交给启动后的补挂轮（默认 10s 后首轮、每 60s 一轮、
 * 共 5 轮，均可配）。补挂轮走 retransform——单类失败由 JVM 拒绝后原字节保留，宿主无感。
 *
 * <p><b>attach 补挂（T-IbatisPanwei 券 E）。</b>attach 路径此前**只跑一轮**，于是「attach 之后
 * 才懒加载数据源」的进程（Spring 延迟初始化 / 多数据源按需建池）永远漏扫——现场磐维驱动的
 * 零产出就有这一条独立成因。现在 attach 首轮之后再加 2 轮补挂（默认 5s / 20s，可配，
 * 见 {@link CaptureBudget#PROP_ATTACH_PASSES}）：与首轮共用 {@link #PROCESSED} 去重、
 * 每轮开跑前重查 {@link CaptureBudget#attachMatchEnabled()}（kill switch 与总开关都不得被绕过）。
 * premain 补挂链保持原样（它的节奏与 attach 场景不同，见 {@link CaptureBudget#DEFAULT_ATTACH_DELAY_MS}）。
 *
 * <p><b>扫描预算语义（券 E）。</b>{@code catchAllScanLimit} 计的是**候选类**（真正跑了接口判定的
 * 类），JDK 前缀、接口/枚举/数组/基本型、无 classloader、JDK 动态代理这些零成本排除项不占预算。
 * 判定顺序改为「名字快筛命中的候选**先**评估，其余类在其后」，因此数组顺序再也不能把某个 JDBC
 * 实现挤到预算之外——截断只会落到「名字里看不出 JDBC 线索」的类上（与修复前同等尽力而为）。
 *
 * <p><b>未登记实现取证（券 D）。</b>接口命中但不在白名单的类名按首次发现顺序记入
 * {@link CaptureBudget#noteUnregisteredDriver(String)}（有界 8 个），心跳带出给 IDE——
 * 「下一个未知驱动是谁」不再只能靠人工猜。券 G1：已知连接池包装族
 * （{@link #POOL_WRAPPER_PREFIXES}）是包装层不是驱动，从该曝光排除；织入面与采集语义零变化。
 * T-CatchAllFamilyFilter：与白名单类**同族**的类（{@link #isWhitelistFamily}——白名单类的祖先
 * 或子孙）同样不是「未登记驱动」，一并排除曝光（Oracle 连接继承链真机 8 条里占 3 条）；织入面同样零变化。
 *
 * <p><b>零 ASM 依赖</b>：本类在 premain 路径被触达（记录候选），任何对 ASM 的静态引用都会把
 * 「ASM 不可见」的第三方 agent 现场变成宿主启动失败（T-JRebelPremainCrash）。故这里只做反射与
 * 名字判定，形态数组取自零 ASM 的 {@link WeaveSpecs}。
 */
final class CatchAllMatcher {

    /** 类名（点分）→ 兜底面形态；transformer 据此把接口命中类接进既有织入通道。 */
    private static final Map<String, StatementWeaver.Kind[]> ACCEPTED =
            new ConcurrentHashMap<String, StatementWeaver.Kind[]>();

    /**
     * 已处理类（弱键）：周期补挂轮次之间去重，避免对同一类反复 retransform。
     * 弱键语义保证热部署/短命 loader 的类不会钉住旧 classloader（券 04 同一红线）。
     */
    private static final Set<Class<?>> PROCESSED = Collections.newSetFromMap(
            Collections.synchronizedMap(new WeakHashMap<Class<?>, Boolean>()));

    /** 类文件 access 标志（与 ASM Opcodes 同值；此处写字面量以保持零 ASM 依赖）。 */
    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ANNOTATION = 0x2000;
    private static final int ACC_ENUM = 0x4000;
    private static final int ACC_MODULE = 0x8000;

    /** 内部名形态（transformer 的 header 判定用）。 */
    private static final String IFACE_STATEMENT = "java/sql/Statement";
    private static final String IFACE_PREPARED = "java/sql/PreparedStatement";
    private static final String IFACE_CALLABLE = "java/sql/CallableStatement";
    private static final String IFACE_CONNECTION = "java/sql/Connection";

    /**
     * 明确不参与兜底匹配的包前缀：JDK 自身实现（bootstrap/平台 loader 的 {@code java.*}、
     * {@code javax.*}、{@code jdk.*}、{@code sun.*}、{@code com.sun.*}）与 W3C/XML 基础设施。
     * 这些类的织入对采集零收益（不是驱动实现），却会把 JDK 内部类拖进 retransform 面。
     */
    private static final String[] EXCLUDED_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml."};

    /**
     * 已知连接池包装族前缀（T-SqlListCatchup 券 G1）：这些前缀的类**是包装层不是驱动**——
     * 池代理几乎人人用，把它们记进「未登记 JDBC 实现」是诊断噪音（真机 2026-11-07 join-demo
     * 实测 Hikari 三类常驻该行）。故本表前缀的类从「未登记」曝光排除（
     * {@link #evaluate} 不再调 {@code noteUnregisteredDriver}）。
     *
     * <p><b>织入面不动</b>：池包装类照旧走兜底匹配与 retransform（header 判定 KDoc 早已把
     * Hikari 列为合法兜底目标）；一次执行的「代理外层 + 驱动内层」双织入由
     * {@code NestGate}（嵌套只报最内层）与 {@code StatementRegistry}（包装语句的内层实体
     * 一并登记，T-OracleWrapperRegistry 先例）兜住，采集语义零变化——本表**只**管诊断曝光面。
     *
     * <p>前缀级判定（点分名 {@code startsWith}）：族内新包（如 Hikari 的 {@code pool.}
     * 生成物）天然整族覆盖。形态核对（常识 + 仓内既有 javap 记录）：HikariCP 生成物全在
     * {@code com.zaxxer.hikari.pool.}（{@code HikariProxyPreparedStatement}/
     * {@code ProxyStatement}/{@code ProxyConnection}）；c3p0 为
     * {@code com.mchange.v2.c3p0.impl.NewProxy*}（前缀覆盖 impl 子包）；DBCP 1.x/2.x 为
     * {@code org.apache.commons.dbcp(.2).Delegating/Poolable} 系列；Tomcat-JDBC 为
     * {@code org.apache.tomcat.jdbc.pool.*}（其语句代理多为 JDK 动态代理，已被
     * {@link #cheapExcluded} 排除，此表兜住静态包装）；Druid 为
     * {@code com.alibaba.druid.pool.DruidPooled*}。
     */
    private static final String[] POOL_WRAPPER_PREFIXES = {
            "com.zaxxer.hikari.pool.",
            "com.mchange.v2.c3p0.",
            "org.apache.commons.dbcp.",
            "org.apache.commons.dbcp2.",
            "org.apache.tomcat.jdbc.pool.",
            "com.alibaba.druid.pool.",
    };

    /** 类名是否落在已知连接池包装族（券 G1：未登记曝光排除用；点分或斜杠名皆可——只看前缀）。 */
    static boolean isKnownPoolWrapper(String dotted) {
        if (dotted == null) {
            return false;
        }
        for (String p : POOL_WRAPPER_PREFIXES) {
            if (dotted.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private CatchAllMatcher() {
    }

    /**
     * T-CatchAllFamilyFilter：收集**已加载的白名单类**的祖先名（本轮快照）。
     *
     * <p>只做名字收集：{@code getSuperclass()} 不触发类初始化，也不加载新类（祖先必然已随子类加载）。
     * 单类异常一律吞掉——判定失败等于「这个类不参与同族排除」，绝不影响整轮扫描。
     *
     * <p>规模有界：白名单类约 40 个 × 链深 ≤6，集合上限数百项；预扫只多一遍名字遍历
     * （2 万类约 1~2ms），与券 E 的排序预扫同量级。
     */
    private static Set<String> collectWhitelistAncestors(Class<?>[] loaded) {
        final Set<String> out = new HashSet<String>();
        for (Class<?> c : loaded) {
            try {
                final String n = nameOf(c);
                if (n == null || !isWhitelisted(n)) {
                    continue;
                }
                for (Class<?> s = c.getSuperclass(); s != null; s = s.getSuperclass()) {
                    final String sn = nameOf(s);
                    if (sn == null) {
                        break;
                    }
                    out.add(sn);
                }
            } catch (Throwable ignore) {
                // 单类解析失败：该类不参与同族排除
            }
        }
        return out;
    }

    /**
     * T-CatchAllFamilyFilter：候选是否与白名单类**同族**（同一条继承链上的基类或子类）。
     *
     * <p>两个方向：
     * <ul>
     *   <li><b>祖先方向</b>——候选名在 {@code whitelistAncestors} 里：候选是某个白名单类的父类/包装器
     *       （Oracle 的 {@code OracleConnectionWrapper} / {@code OracleConnection} /
     *       {@code GeneratedPhysicalConnection} 都是 {@code PhysicalConnection} 的祖先）；</li>
     *   <li><b>子孙方向</b>——候选自己的父类链上出现白名单类名：候选是白名单类的具体实现子类
     *       （如 {@code T4CConnection extends PhysicalConnection}）。</li>
     * </ul>
     *
     * <p>语义：同族 ⇒ 该厂商已被登记 ⇒ 不是「未登记驱动」。织入面不受影响——候选照旧进
     * {@code ACCEPTED} 并 retransform，本判定**只**管诊断曝光面（与券 G1 同一条口径）。
     */
    static boolean isWhitelistFamily(Class<?> c, String name, Set<String> whitelistAncestors) {
        if (whitelistAncestors != null && whitelistAncestors.contains(name)) {
            return true;
        }
        try {
            for (Class<?> s = c.getSuperclass(); s != null; s = s.getSuperclass()) {
                final String sn = nameOf(s);
                if (sn == null) {
                    return false;
                }
                if (isWhitelisted(sn)) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
            return false;
        }
        return false;
    }

    // ---------- 判定（反射 / header 两形态） ----------

    /**
     * 反射判定：该类是否应走兜底织入；命中返回兜底面形态，否则 null。
     *
     * <p>单类异常一律吞掉返回 null（调用方也各自 try）——判定失败等于「这个类不兜底」，
     * 绝不影响 attach 的其余类与宿主。
     */
    static StatementWeaver.Kind[] specOf(Class<?> c) {
        try {
            if (cheapExcluded(c)) {
                return null;
            }
            final String name = c.getName();
            if (excludedName(name)) {
                return null;
            }
            return interfaceSpecOf(c);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 零成本排除（**不读类名、不做接口判定**）：接口/数组/基本型/枚举、bootstrap 装载的 JDK 自身实现、
     * JDK 动态代理。
     *
     * <p>券 E 把「是否占扫描预算」与「是否做接口判定」拆开：本方法只读 {@link Class} 自带的布尔标志
     * （JVM 侧缓存，无分配、无反射），命中即与「不是 JDBC 实现」等价，因此不必计入
     * {@code catchAllScanLimit}。大型应用里 `java.*` / 接口 / 注解 / 枚举常占半数以上，旧口径把它们
     * 全算进 5000 预算，真正候选反被数组顺序挤掉。
     */
    private static boolean cheapExcluded(Class<?> c) {
        if (c == null) {
            return true;
        }
        if (c.isInterface() || c.isArray() || c.isPrimitive() || c.isEnum()) {
            return true;
        }
        // bootstrap（JDK 自身实现）无织入意义；同时避免把 java.sql 内部类拖进 retransform 面
        if (c.getClassLoader() == null) {
            return true;
        }
        // JDK 动态代理（MyBatis Mapper/PreparedStatementLogger 等）：方法体只是转发 trampoline，
        // 织它得不到自有 SQL，且代理类是运行时生成物，retransform 支持面因 JDK 版本而异
        return Proxy.isProxyClass(c);
    }

    /**
     * 接口判定本体（调用方已保证：非接口/枚举/代理、有 classloader、名字未被排除）。
     *
     * <p>PreparedStatement/CallableStatement 都继承 Statement，一条判定覆盖三接口。
     */
    private static StatementWeaver.Kind[] interfaceSpecOf(Class<?> c) {
        if (java.sql.Statement.class.isAssignableFrom(c)) {
            return WeaveSpecs.catchAllStatementFace();
        }
        if (java.sql.Connection.class.isAssignableFrom(c)) {
            return WeaveSpecs.catchAllPrepareFace();
        }
        return null;
    }

    /**
     * header 判定（券 06 transformer 用）：只读类头（access + interfaces），不做全树解析。
     *
     * <p>只认**直接实现**的接口——间接继承（父类实现）需要再读父类字节，成本与风险都不划算；
     * 白名单覆盖已知驱动族，兜底只负责「直接 implements java.sql.*」的常见形态（含 Hikari 这类
     * 池包装类，javap 实测 {@code HikariProxyPreparedStatement implements java.sql.PreparedStatement}）。
     */
    static StatementWeaver.Kind[] specOfHeader(String dotted, int access, String[] interfaces) {
        if ((access & (ACC_INTERFACE | ACC_ANNOTATION | ACC_ENUM | ACC_MODULE)) != 0) {
            return null;
        }
        if (dotted == null || excludedName(dotted)) {
            return null;
        }
        boolean statement = false;
        boolean connection = false;
        if (interfaces != null) {
            for (String i : interfaces) {
                if (IFACE_STATEMENT.equals(i) || IFACE_PREPARED.equals(i) || IFACE_CALLABLE.equals(i)) {
                    statement = true;
                } else if (IFACE_CONNECTION.equals(i)) {
                    connection = true;
                }
            }
        }
        if (statement) {
            return WeaveSpecs.catchAllStatementFace();
        }
        if (connection) {
            return WeaveSpecs.catchAllPrepareFace();
        }
        return null;
    }

    /** 名字级排除（点分或斜杠名皆可：只看前缀，不含分隔符差异）。 */
    static boolean excludedName(String name) {
        if (name == null) {
            return true;
        }
        for (String p : EXCLUDED_PREFIXES) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** transformer 读接口匹配结果（未命中返回 null）。 */
    static StatementWeaver.Kind[] acceptedSpec(String dotted) {
        return dotted == null ? null : ACCEPTED.get(dotted);
    }

    /**
     * 类名快筛线索（大小写不敏感子串）。**只决定扫描优先级，不决定是否参与判定**——
     * 命中者先评估（保证 JDBC 实现不会因 {@code getAllLoadedClasses()} 的数组顺序被预算截断），
     * 未命中者随后照常判定（尽力而为，与修复前等价）。故这些词故意放宽：
     * 误命中的代价只是「早评估一个普通类」，漏命中的代价是「退回旧口径」，两者都不改变正确性。
     */
    private static final String[] JDBC_NAME_HINTS = {
            "statement", "connection", "jdbc", "conn", "stmt", "sql"};

    /** 类名是否带 JDBC 线索（券 E 扫描优先级；零反射、零异常）。 */
    static boolean nameHintsJdbc(String dotted) {
        if (dotted == null || dotted.isEmpty()) {
            return false;
        }
        final String lower;
        try {
            lower = dotted.toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return false;
        }
        for (String hint : JDBC_NAME_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** 已接受的兜底类名 → 形态（测试观测/取证用；生产路径只经 {@link #acceptedSpec} 读）。 */
    static Map<String, StatementWeaver.Kind[]> acceptedSpecs() {
        return Collections.unmodifiableMap(ACCEPTED);
    }

    // ---------- 入口（券 01 attach / 券 08 premain） ----------

    /**
     * 安装后的兜底入口。
     *
     * @param premain true = premain 路径：只记录候选 + 安排启动后补挂，**不**在本阶段织入未知形态
     */
    static void afterInstall(Instrumentation inst, boolean premain) {
        if (inst == null) {
            return;
        }
        if (!CaptureBudget.attachMatchEnabled()) {
            CaptureBudget.diag("interface matching disabled by "
                    + CaptureBudget.PROP_ATTACH_MATCH + "=false (whitelist only)");
            return;
        }
        if (premain) {
            recordPremainCandidates(inst);
            scheduleDeferredPasses(inst, CaptureBudget.premainDelayMs(), CaptureBudget.premainRepeatMs(),
                    CaptureBudget.premainPasses(), "premain pass");
            return;
        }
        runPass(inst, "attach interface-match");
        // 券 E：attach 后补挂 2 轮（默认 5s/20s）。覆盖「attach 之后才懒加载数据源」的进程——
        // 这类进程首轮必然漏扫，此前没有第二轮，表现就是「挂上了却一条不出」。补挂失败只影响
        // 覆盖，预热/白名单/事件链不受影响（runPass 内部逐类吞异常 + 每轮重查 kill switch）。
        scheduleDeferredPasses(inst, CaptureBudget.attachDelayMs(), CaptureBudget.attachRepeatMs(),
                CaptureBudget.attachPasses(), "attach pass");
    }

    /**
     * premain 候选记录（券 08）：只数不织。
     *
     * <p>现场判读价值：这一行把「premain 阶段到底看见几个 JDBC 实现」变成宿主侧可见事实，
     * 与后续补挂轮的 matched/wove 配对即可区分「类还没加载」与「匹配失效」。
     */
    private static void recordPremainCandidates(Instrumentation inst) {
        final int limit = CaptureBudget.scanLimit();
        int evaluated = 0;
        int candidates = 0;
        boolean limitHit = false;
        try {
            final Class<?>[] loaded = inst.getAllLoadedClasses();
            if (loaded != null) {
                for (Class<?> c : loaded) {
                    try {
                        final String name = c.getName();
                        // 与 runPass 同口径：白名单与零成本排除项不占候选预算（券 E）
                        if (isWhitelisted(name) || excludedName(name) || cheapExcluded(c)) {
                            continue;
                        }
                        if (evaluated >= limit) {
                            limitHit = true;
                            break;
                        }
                        evaluated++;
                        if (interfaceSpecOf(c) != null) {
                            candidates++;
                        }
                    } catch (Throwable ignore) {
                        // 单类判定失败：跳过该类，不阻断候选统计
                    }
                }
            }
        } catch (Throwable ignore) {
            // 枚举失败：本阶段无候选可言（补挂轮会重试）
        }
        CaptureBudget.diag("premain: " + candidates + " interface candidate(s) among " + evaluated
                + " evaluated candidate class(es)" + (limitHit ? "+limit(" + limit + ")" : "")
                + "; premain never inlines unknown byte shapes"
                + " — interface weaving deferred to post-start pass(es)");
    }

    /**
     * 启动后补挂（券 08 premain / 券 E attach）：daemon 线程、有限轮数、每轮受候选预算与 kill switch 约束。
     *
     * <p>为什么必须延后（premain）：premain 时点类还没加载完（驱动/连接池通常在 Spring 上下文启动后
     * 才装载），立即匹配命中率为零；而在 premain 阶段内联织入未知字节形态正是红线禁止项。
     * 为什么 attach 也要补（券 E）：「attach 之后才懒加载数据源」的进程首轮同样必然漏扫。
     *
     * <p>两条铁律在 {@code run()} 里逐轮重查，不在排程时一次性判定：① 总开关
     * {@link CaptureBudget#attachMatchEnabled()}（kill switch 触发后它返回 false）；
     * ② 每轮调用前都重新取延迟（CONFIG 热更新/测试注入都按当前值生效）。
     *
     * @return 已启动的补挂线程（null = 未排程）；测试用它 join 以获得确定性
     */
    private static Thread scheduleDeferredPasses(final Instrumentation inst, final long delayMs,
            final long repeatMs, final int passes, final String phasePrefix) {
        if (delayMs <= 0L || passes <= 0) {
            return null;
        }
        try {
            final Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    long waitMs = delayMs;
                    for (int i = 0; i < passes; i++) {
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException ie) {
                            return;
                        }
                        // 每轮开跑前重查：kill switch / catchAll=false 都不得被补挂轮绕过
                        if (!CaptureBudget.attachMatchEnabled()) {
                            return;
                        }
                        try {
                            runPass(inst, phasePrefix + " " + (i + 1) + "/" + passes);
                        } catch (Throwable ignore) {
                            // 单轮失败不终止后续轮（预算/kill switch 在 runPass 内已记账）
                        }
                        waitMs = repeatMs;
                        if (waitMs <= 0L) {
                            waitMs = delayMs;
                        }
                    }
                }
            }, "SqlVisAgent-catchall");
            t.setDaemon(true);
            lastDeferredThread = t;
            t.start();
            return t;
        } catch (Throwable ignore) {
            // 线程创建失败（极端受限环境）：放弃补挂，宿主不受影响
            return null;
        }
    }

    /** 最近一次启动的补挂线程（仅测试用：join 以获得确定性，避免 sleep 轮询）。 */
    private static volatile Thread lastDeferredThread;

    /** 仅测试用：取最近一次排程的补挂线程（未排程为 null）。 */
    static Thread lastDeferredThreadForTest() {
        return lastDeferredThread;
    }

    /**
     * 一轮接口匹配 retransform。
     *
     * <p>顺序即「白名单优先」：先按白名单谓词排除（这些类已由 {@code AgentMain} 的名单轮织过），
     * 再对剩余类做接口判定。命中类先登记进 {@link #ACCEPTED}，再 retransform——transformer 在
     * 同一次调用里就能查到形态，走既有织入通道。
     *
     * <p>券 E 的两处改动：
     * <ul>
     *   <li>预算只对**候选类**计数：接口/枚举/数组/基本型/bootstrap/JDK 代理、JDK 前缀名这些都是
     *       零成本排除，不占 {@code catchAllScanLimit}；</li>
     *   <li>名字带 JDBC 线索的候选**先**评估，其余类随后——数组顺序不再能决定谁被截断。
     *       这一遍排序只在 {@code loaded.length > limit}（**可能截断**）时做：中小应用单轮就扫得完，
     *       排序零收益却要多付一遍名字扫描（实测 20k 类约 4~5ms/轮）。</li>
     * </ul>
     */
    private static void runPass(Instrumentation inst, String phase) {
        final Class<?>[] loaded;
        try {
            loaded = inst.getAllLoadedClasses();
        } catch (Throwable t) {
            CaptureBudget.diag(phase + " aborted: getAllLoadedClasses failed ("
                    + t.getClass().getSimpleName() + ")");
            return;
        }
        if (loaded == null) {
            return;
        }
        final PassStats stats = new PassStats(CaptureBudget.scanLimit());
        // T-CatchAllFamilyFilter：白名单类的祖先名快照（本轮建一次，只读）。
        // 用途＝把「驱动内部的基类/包装器」从「未登记 JDBC 实现」曝光里排除：Oracle 的
        // OracleConnectionWrapper → OracleConnection → GeneratedPhysicalConnection →
        // PhysicalConnection 是一条继承链，白名单只登记真正声明 prepare 的 PhysicalConnection，
        // 其余三类因 isAssignableFrom 继承可见而命中兜底、被当成未知驱动记账（真机 2026-11-09
        // 现场 8 条里占 3 条，把上限 8 的取证位挤掉）。织入面不受影响。
        final Set<String> whitelistAncestors = collectWhitelistAncestors(loaded);
        // 名字快筛只在**可能截断**时才做：loaded.length <= limit 时单轮就扫得完，排序没有收益，
        // 白付一遍名字扫描（实测 20k 类约 4~5ms/轮，见券 E 实测）。这一条让中小应用的成本
        // 与修复前持平，大应用才为「候选不被数组顺序挤掉」付那一遍钱。
        final boolean prioritize = loaded.length > stats.limit;
        final Set<Class<?>> hinted = prioritize
                ? Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>())
                : Collections.<Class<?>>emptySet();
        if (prioritize) {
            // 第一轮：名字快筛命中者优先（含全部已知/未知驱动的语句与连接实现）
            for (Class<?> c : loaded) {
                if (stats.stop) {
                    break;
                }
                if (cheapExcluded(c)) {
                    continue;
                }
                final String name = nameOf(c);
                if (name == null || !nameHintsJdbc(name)) {
                    continue;
                }
                hinted.add(c);
                evaluate(inst, c, name, stats, whitelistAncestors);
            }
        }
        // 第二轮（或唯一一轮）：其余类，与修复前同等尽力而为
        for (Class<?> c : loaded) {
            if (stats.stop) {
                break;
            }
            if (hinted.contains(c)) {
                continue;
            }
            final String name = nameOf(c);
            if (name == null) {
                continue;
            }
            evaluate(inst, c, name, stats, whitelistAncestors);
        }
        CaptureBudget.diag(phase + ": matched=" + stats.matched + " wove=" + stats.wove
                + " (whitelist=" + stats.whitelist + " interface=" + stats.matched + ")"
                + " scan=" + stats.candidates + (stats.limitHit ? "+limit(" + stats.limit + ")" : "")
                + " total=" + loaded.length
                + " skipped=" + stats.skipped + " failed=" + stats.failed
                + (stats.failures.length() > 0 ? " [" + stats.failures.toString().trim() + "]" : "")
                + (CaptureBudget.killed() ? " KILLED(" + CaptureBudget.killReason() + ")" : ""));
    }

    /** 单类名（异常即视为「该类不可判定」，不占预算也不中断整轮）。 */
    private static String nameOf(Class<?> c) {
        try {
            return c == null ? null : c.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 单个候选类的判定 + 织入（券 01/09 原逻辑，拆出来供两轮扫描共用）。
     *
     * <p>预算检查放在**做完零成本排除之后**：被排除的类既不做接口判定也不占
     * {@code catchAllScanLimit}，这正是券 E 要的语义。
     */
    private static void evaluate(Instrumentation inst, Class<?> c, String name, PassStats stats,
            Set<String> whitelistAncestors) {
        try {
            if (isWhitelisted(name)) {
                stats.whitelist++;
                return;
            }
            if (excludedName(name)) {
                return;
            }
            if (cheapExcluded(c)) {
                return;
            }
            if (stats.candidates >= stats.limit) {
                stats.limitHit = true;
                stats.stop = true;
                return;
            }
            stats.candidates++;
            final StatementWeaver.Kind[] spec = interfaceSpecOf(c);
            if (spec == null) {
                return;
            }
            // 券 D：接口命中但不在白名单——记下类名（有界），心跳带出给 IDE。
            // 券 G1：已知连接池包装族是包装层不是驱动，从「未登记」曝光排除（织入面不动）。
            // T-CatchAllFamilyFilter：白名单类的祖先/子孙是同族层（Oracle 连接继承链），
            // 同样不是「未登记驱动」——排除，织入面不动。
            if (!isKnownPoolWrapper(name) && !isWhitelistFamily(c, name, whitelistAncestors)) {
                CaptureBudget.noteUnregisteredDriver(name);
            }
            // 周期补挂去重：同一 Class 只处理一次（失败也不重试——失败形态是稳定的）
            if (!PROCESSED.add(c)) {
                return;
            }
            stats.matched++;
            CaptureBudget.noteInterfaceMatched();
            ACCEPTED.put(name, spec);
            if (!inst.isModifiableClass(c)) {
                stats.skipped++;
                CaptureBudget.noteInterfaceSkipped();
                return;
            }
            inst.retransformClasses(c);
            stats.wove++;
            CaptureBudget.noteInterfaceWoven();
            CaptureBudget.noteCatchAllSuccess();
        } catch (Throwable t) {
            stats.failed++;
            CaptureBudget.noteInterfaceFailed();
            CaptureBudget.noteCatchAllFailure(name + "=" + t.getClass().getSimpleName());
            if (stats.failures.length() < 400) {
                stats.failures.append(name).append('=').append(t.getClass().getSimpleName()).append(' ');
            }
        }
        if (CaptureBudget.killed()) {
            stats.stop = true;
        }
    }

    /**
     * 单轮扫描读数（券 E 把预算语义改成「候选类计数」后，limit 与累计计数都挂在这里）。
     *
     * <p>刻意用可变对象而不是局部基本型：两轮扫描共用同一份账，且 {@code limitHit} 一旦置真
     * 就必须让两轮都停（预算是整轮的约束，不是某一轮的）。
     */
    private static final class PassStats {
        final int limit;
        int candidates;
        int whitelist;
        int matched;
        int wove;
        int skipped;
        int failed;
        boolean limitHit;
        boolean stop;
        final StringBuilder failures = new StringBuilder();

        PassStats(int limit) {
            this.limit = limit;
        }
    }

    /** 白名单谓词（零 ASM；与 {@code AgentMain} 名单轮同一判定口径）。 */
    private static boolean isWhitelisted(String dotted) {
        return DriverRegistry.isWeavable(dotted)
                || DriverRegistry.isStatementWeavable(dotted)
                || DriverRegistry.isPrepareWeavable(dotted);
    }

    /** 仅测试用：清空接受表、已处理集合与补挂线程记录。 */
    static void resetForTest() {
        ACCEPTED.clear();
        synchronized (PROCESSED) {
            PROCESSED.clear();
        }
        lastDeferredThread = null;
    }
}
