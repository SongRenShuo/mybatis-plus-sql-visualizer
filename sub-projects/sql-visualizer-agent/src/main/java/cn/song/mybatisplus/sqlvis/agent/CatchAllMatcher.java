package cn.song.mybatisplus.sqlvis.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.Collections;
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

    private CatchAllMatcher() {
    }

    // ---------- 判定（反射 / header 两形态） ----------

    /**
     * 反射判定：该类是否应走兜底织入；命中返回兜底面形态，否则 null。
     *
     * <p>单类异常一律吞掉返回 null（调用方也各自 try）——判定失败等于「这个类不兜底」，
     * 绝不影响 attach 的其余类与宿主。
     */
    static StatementWeaver.Kind[] specOf(Class<?> c) {
        if (c == null) {
            return null;
        }
        try {
            if (c.isInterface() || c.isArray() || c.isPrimitive() || c.isEnum()) {
                return null;
            }
            final String name = c.getName();
            if (excludedName(name)) {
                return null;
            }
            // bootstrap（JDK 自身实现）无织入意义；同时避免把 java.sql 内部类拖进 retransform 面
            if (c.getClassLoader() == null) {
                return null;
            }
            // JDK 动态代理（MyBatis Mapper/PreparedStatementLogger 等）：方法体只是转发 trampoline，
            // 织它得不到自有 SQL，且代理类是运行时生成物，retransform 支持面因 JDK 版本而异
            if (Proxy.isProxyClass(c)) {
                return null;
            }
            // PreparedStatement/CallableStatement 都继承 Statement，一条判定覆盖三接口
            if (java.sql.Statement.class.isAssignableFrom(c)) {
                return WeaveSpecs.catchAllStatementFace();
            }
            if (java.sql.Connection.class.isAssignableFrom(c)) {
                return WeaveSpecs.catchAllPrepareFace();
            }
        } catch (Throwable t) {
            return null;
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
            scheduleDeferredPasses(inst);
            return;
        }
        runPass(inst, "attach interface-match");
    }

    /**
     * premain 候选记录（券 08）：只数不织。
     *
     * <p>现场判读价值：这一行把「premain 阶段到底看见几个 JDBC 实现」变成宿主侧可见事实，
     * 与后续补挂轮的 matched/wove 配对即可区分「类还没加载」与「匹配失效」。
     */
    private static void recordPremainCandidates(Instrumentation inst) {
        int scanned = 0;
        int candidates = 0;
        try {
            final Class<?>[] loaded = inst.getAllLoadedClasses();
            if (loaded != null) {
                for (Class<?> c : loaded) {
                    if (scanned >= CaptureBudget.scanLimit()) {
                        break;
                    }
                    scanned++;
                    try {
                        final String name = c.getName();
                        if (isWhitelisted(name)) {
                            continue;
                        }
                        if (specOf(c) != null) {
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
        CaptureBudget.diag("premain: " + candidates + " interface candidate(s) among " + scanned
                + " loaded class(es); premain never inlines unknown byte shapes"
                + " — interface weaving deferred to post-start pass(es)");
    }

    /**
     * premain 启动后补挂（券 08）：daemon 线程、有限轮数、每轮受扫描上限与 kill switch 约束。
     *
     * <p>为什么必须延后：premain 时点类还没加载完（驱动/连接池通常在 Spring 上下文启动后才装载），
     * 立即匹配命中率为零；而在 premain 阶段内联织入未知字节形态正是红线禁止项。延后到应用
     * 启动后走 retransform，失败只影响「该类不增强」。
     */
    private static void scheduleDeferredPasses(final Instrumentation inst) {
        final long delayMs = CaptureBudget.premainDelayMs();
        final int passes = CaptureBudget.premainPasses();
        if (delayMs <= 0L || passes <= 0) {
            return;
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
                        if (!CaptureBudget.attachMatchEnabled()) {
                            return;
                        }
                        try {
                            runPass(inst, "premain pass " + (i + 1) + "/" + passes);
                        } catch (Throwable ignore) {
                            // 单轮失败不终止后续轮（预算/kill switch 在 runPass 内已记账）
                        }
                        waitMs = CaptureBudget.premainRepeatMs();
                        if (waitMs <= 0L) {
                            waitMs = CaptureBudget.premainDelayMs();
                        }
                    }
                }
            }, "SqlVisAgent-catchall");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignore) {
            // 线程创建失败（极端受限环境）：放弃补挂，宿主不受影响
        }
    }

    /**
     * 一轮接口匹配 retransform。
     *
     * <p>顺序即「白名单优先」：先按白名单谓词排除（这些类已由 {@code AgentMain} 的名单轮织过），
     * 再对剩余类做接口判定。命中类先登记进 {@link #ACCEPTED}，再 retransform——transformer 在
     * 同一次调用里就能查到形态，走既有织入通道。
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
        final int limit = CaptureBudget.scanLimit();
        int scanned = 0;
        int whitelist = 0;
        int matched = 0;
        int wove = 0;
        int skipped = 0;
        int failed = 0;
        boolean limitHit = false;
        final StringBuilder failures = new StringBuilder();
        for (Class<?> c : loaded) {
            if (CaptureBudget.killed()) {
                break;
            }
            if (scanned >= limit) {
                limitHit = true;
                break;
            }
            scanned++;
            final String name;
            try {
                name = c.getName();
            } catch (Throwable t) {
                continue;
            }
            try {
                if (isWhitelisted(name)) {
                    whitelist++;
                    continue;
                }
                final StatementWeaver.Kind[] spec = specOf(c);
                if (spec == null) {
                    continue;
                }
                // 周期补挂去重：同一 Class 只处理一次（失败也不重试——失败形态是稳定的）
                if (!PROCESSED.add(c)) {
                    continue;
                }
                matched++;
                CaptureBudget.noteInterfaceMatched();
                ACCEPTED.put(name, spec);
                if (!inst.isModifiableClass(c)) {
                    skipped++;
                    CaptureBudget.noteInterfaceSkipped();
                    continue;
                }
                inst.retransformClasses(c);
                wove++;
                CaptureBudget.noteInterfaceWoven();
                CaptureBudget.noteCatchAllSuccess();
            } catch (Throwable t) {
                failed++;
                CaptureBudget.noteInterfaceFailed();
                CaptureBudget.noteCatchAllFailure(name + "=" + t.getClass().getSimpleName());
                if (failures.length() < 400) {
                    failures.append(name).append('=').append(t.getClass().getSimpleName()).append(' ');
                }
            }
        }
        CaptureBudget.diag(phase + ": matched=" + matched + " wove=" + wove
                + " (whitelist=" + whitelist + " interface=" + matched + ")"
                + " scan=" + scanned + (limitHit ? "+limit(" + limit + ")" : "")
                + " skipped=" + skipped + " failed=" + failed
                + (failures.length() > 0 ? " [" + failures.toString().trim() + "]" : "")
                + (CaptureBudget.killed() ? " KILLED(" + CaptureBudget.killReason() + ")" : ""));
    }

    /** 白名单谓词（零 ASM；与 {@code AgentMain} 名单轮同一判定口径）。 */
    private static boolean isWhitelisted(String dotted) {
        return DriverRegistry.isWeavable(dotted)
                || DriverRegistry.isStatementWeavable(dotted)
                || DriverRegistry.isPrepareWeavable(dotted);
    }

    /** 仅测试用：清空接受表与已处理集合。 */
    static void resetForTest() {
        ACCEPTED.clear();
        synchronized (PROCESSED) {
            PROCESSED.clear();
        }
    }
}
