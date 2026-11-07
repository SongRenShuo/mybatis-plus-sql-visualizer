package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.hook.StatementRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-CaptureCatchAll 券 01/08 守卫：接口匹配兜底（白名单优先）与 premain 安全策略。
 *
 * <p>三条红线断言：
 * <ol>
 *   <li><b>白名单优先</b>：已登记类不重复进兜底轮（{@code retransform} 只对白名单外类发起）；</li>
 *   <li><b>premain 不内联未知形态（券 08）</b>：premain 路径对接口命中类**零 retransform**、
 *       接受表为空——只记候选；</li>
 *   <li><b>同一织入通道</b>：attach 轮登记进接受表后，transformer 对该类走既有
 *       {@link StatementWeaver#weaveSpec} 通道产出新字节（不是另开一条采集通道）。</li>
 * </ol>
 *
 * <p>Hikari 池包装类从 {@code sqlvis.attachPoolChild} 配置解析出的 jar 里以隔离 loader
 * （{@code initialize=false}）加载——不初始化，故不拉起 slf4j 等运行时依赖，只证「反射匹配
 * 看得见池包装类」这一条（P-2）。
 */
public class CatchAllMatcherTest {

    /** 兜底命中类（语句面）：{@code implements java.sql.PreparedStatement}，不在任何白名单。 */
    private static final String STATEMENT_CANDIDATE =
            "cn.song.mybatisplus.sqlvis.agent.FakeVendorFixtures$VendorPs";

    private static final String HIKARI_PS = "com.zaxxer.hikari.pool.HikariProxyPreparedStatement";
    private static final String HIKARI_CONN = "com.zaxxer.hikari.pool.HikariProxyConnection";

    private final List<String> retransformed = new ArrayList<String>();

    @Before
    public void setUp() {
        CaptureBudget.resetForTest();
        CatchAllMatcher.resetForTest();
        SqlHookTransformer.resetCountersForTest();
        StatementRegistry.clearForTest();
        retransformed.clear();
        // T-IbatisPanwei 券 E：attach 路径现在默认会排 2 轮补挂（5s/20s）。attach 用例一律按
        // 「不补挂」跑——否则遗留的 daemon 线程会在后续用例里继续 retransform 并改计数
        // （premain 侧早已踩过同一种污染形态）。需要验证补挂的用例自己显式开启。
        CaptureBudget.setAttachScheduleForTest(0L, 0L, 1);
    }

    @After
    public void tearDown() {
        CaptureBudget.resetForTest();
        CatchAllMatcher.resetForTest();
    }

    // ---------- 反射判定 ----------

    @Test
    public void 池包装类_反射匹配命中且形态正确() throws Exception {
        final ClassLoader hikari = hikariLoader();
        final Class<?> ps = Class.forName(HIKARI_PS, false, hikari);
        final Class<?> conn = Class.forName(HIKARI_CONN, false, hikari);

        assertFalse("Hikari 生成物是实体类而非 JDK 动态代理（P-2 前提）", Proxy.isProxyClass(ps));
        assertArrayEquals("PS 池包装类必须走兜底语句面",
                WeaveSpecs.catchAllStatementFace(), CatchAllMatcher.specOf(ps));
        assertArrayEquals("Connection 池包装类必须走兜底 prepare 面",
                WeaveSpecs.catchAllPrepareFace(), CatchAllMatcher.specOf(conn));
    }

    @Test
    public void JDK动态代理与JDK自身类_不参与兜底() {
        final Object proxy = Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class},
                (p, m, a) -> null);
        assertNull("JDK 动态代理方法体只是转发 trampoline，织入零收益",
                CatchAllMatcher.specOf(proxy.getClass()));
        assertNull("java.* 自身类排除", CatchAllMatcher.specOf(String.class));
        assertNull("接口本身不可织入", CatchAllMatcher.specOf(java.sql.Connection.class));
        assertNull("bootstrap 类（无 classloader）排除",
                CatchAllMatcher.specOf(java.util.ArrayList.class));
    }

    @Test
    public void header判定_只认直接实现的javaSql接口() {
        assertArrayEquals("直接 implements PreparedStatement",
                WeaveSpecs.catchAllStatementFace(),
                CatchAllMatcher.specOfHeader("x.y.Wrapper", 0x0021,
                        new String[]{"java/sql/PreparedStatement"}));
        assertArrayEquals("直接 implements Connection",
                WeaveSpecs.catchAllPrepareFace(),
                CatchAllMatcher.specOfHeader("x.y.Conn", 0x0021, new String[]{"java/sql/Connection"}));
        assertNull("无 java.sql 接口即不命中",
                CatchAllMatcher.specOfHeader("x.y.Plain", 0x0021, new String[]{"java/lang/Runnable"}));
        assertNull("接口类自身不织入（ACC_INTERFACE）",
                CatchAllMatcher.specOfHeader("x.y.Iface", 0x0201, new String[]{"java/sql/Statement"}));
        assertNull("JDK 包前缀排除",
                CatchAllMatcher.specOfHeader("java.sql.Fake", 0x0021, new String[]{"java/sql/Statement"}));
        assertNull("模块信息类不织入（ACC_MODULE）",
                CatchAllMatcher.specOfHeader("module-info", 0x8000, null));
    }

    // ---------- attach 轮（券 01） ----------

    @Test
    public void attach轮_白名单优先且命中即登记接受表() throws Exception {
        final Class<?> candidate = Class.forName(STATEMENT_CANDIDATE, false, getClass().getClassLoader());
        final Class<?> whitelisted = h2IsolatedClass("org.h2.jdbc.JdbcStatement");

        CatchAllMatcher.afterInstall(stubInstrumentation(new Class<?>[]{whitelisted, candidate}), false);

        assertTrue("接口命中类必须登记进接受表（transformer 据此走同一织入通道）",
                CatchAllMatcher.acceptedSpecs().containsKey(STATEMENT_CANDIDATE));
        assertEquals("只对白名单外类发起 retransform", 1, retransformed.size());
        assertEquals(STATEMENT_CANDIDATE, retransformed.get(0));
        assertEquals("matchedByInterface 计数", 1L, CaptureBudget.interfaceMatchedCount());
        assertEquals("interfaceWoven 计数", 1L, CaptureBudget.interfaceWovenCount());
    }

    @Test
    public void attach轮_接受表命中后transformer走同一织入通道() throws Exception {
        final Class<?> candidate = Class.forName(STATEMENT_CANDIDATE, false, getClass().getClassLoader());
        CatchAllMatcher.afterInstall(stubInstrumentation(new Class<?>[]{candidate}), false);

        final byte[] original = resourceBytes(STATEMENT_CANDIDATE.replace('.', '/') + ".class");
        final byte[] out = new SqlHookTransformer().transform(getClass().getClassLoader(),
                STATEMENT_CANDIDATE.replace('.', '/'), null, null, original);

        assertNotNull("兜底织入不得返回 null（原字节直返由 noChange 分支表达）", out);
        assertFalse("接受表命中类必须真被织入（changed）", java.util.Arrays.equals(original, out));
        assertEquals("织入计数进 weavable", 1L, SqlHookTransformer.weavableCount());
        assertEquals("真织上计数进 woven", 1L, SqlHookTransformer.wovenCount());
    }

    @Test
    public void 扫描上限_超出即停止扫描() throws Exception {
        CaptureBudget.setScanLimitForTest(1);
        // 两个真候选（语句面 + 连接面），预算 1 只能评估其中一个
        final Class<?> candidate = Class.forName(STATEMENT_CANDIDATE, false, getClass().getClassLoader());
        final Class<?> conn = Class.forName(HIKARI_CONN, false, hikariLoader());

        CatchAllMatcher.afterInstall(stubInstrumentation(new Class<?>[]{candidate, conn}), false);

        assertEquals("扫描类数达上限后不得继续匹配（券 09①）", 1L, CaptureBudget.interfaceMatchedCount());
        assertEquals("预算 1 只能 retransform 一个类：" + retransformed, 1, retransformed.size());
    }

    @Test
    public void kill开关触发后_attach轮整体退回白名单() throws Exception {
        CaptureBudget.setKillAfterForTest(1);
        CaptureBudget.noteCatchAllFailure("fixture=VerifyError");
        assertTrue("连败达上限必须触发 kill switch", CaptureBudget.killed());
        assertFalse("kill 后兜底入口关闭", CaptureBudget.attachMatchEnabled());

        final Class<?> candidate = Class.forName(STATEMENT_CANDIDATE, false, getClass().getClassLoader());
        CatchAllMatcher.afterInstall(stubInstrumentation(new Class<?>[]{candidate}), false);

        assertTrue("kill 后不得再匹配", retransformed.isEmpty());
        assertTrue("kill 后接受表保持为空", CatchAllMatcher.acceptedSpecs().isEmpty());
    }

    // ---------- T-IbatisPanwei 券 E：attach 补挂轮（迟到类） ----------

    /**
     * 迟到类用例：attach 首轮只看得到连接类，语句类在首轮之后才加载。
     *
     * <p>现场形态（计划 §3.2 的独立成因）：应用在 attach **之后**才懒加载数据源 → 驱动类在扫描
     * 之后才加载。修复前 attach 只跑一轮，这类进程 100% 漏扫；券 E 加 2 轮补挂后必须补上。
     * 反向验证：摘掉 attach 路径的 {@code scheduleDeferredPasses} 调用，本用例必须红。
     */
    @Test
    public void attach补挂轮_迟到类在后续轮被补扫并织入() throws Exception {
        // 首轮只看得见连接类（Hikari 池包装 Connection，白名单外）；补挂轮才看得见语句类。
        // 注：VendorConn 是普通夹具类（不 implements java.sql.Connection），不能当「已命中的连接类」。
        final Class<?> conn = Class.forName(HIKARI_CONN, false, hikariLoader());
        final Class<?> latePs = Class.forName(STATEMENT_CANDIDATE, false, getClass().getClassLoader());

        CaptureBudget.setAttachScheduleForTest(1L, 1L, 2);
        final Instrumentation inst = stubInstrumentationRounds(
                new Class<?>[][]{{conn}, {conn, latePs}}, false);

        CatchAllMatcher.afterInstall(inst, false);
        final Thread deferred = CatchAllMatcher.lastDeferredThreadForTest();
        assertNotNull("attach 路径必须排补挂轮（券 E）", deferred);
        deferred.join(10_000L);

        assertTrue("迟到类必须被补挂轮补扫到：" + retransformed,
                retransformed.contains(STATEMENT_CANDIDATE));
        assertEquals("连接类与迟到语句类各织一次（PROCESSED 去重不得重复 retransform）：" + retransformed,
                2, retransformed.size());
        assertEquals("两轮命中合计", 2L, CaptureBudget.interfaceMatchedCount());
        assertEquals("两轮织入合计", 2L, CaptureBudget.interfaceWovenCount());
    }

    /**
     * 补挂轮不得绕过 kill switch：首轮就熔断时，后续轮一个类也不得再碰。
     *
     * <p>反向验证：把 {@code run()} 里的 {@code attachMatchEnabled()} 重查删掉，本用例必须红
     * （迟到的连接类会被补挂轮织入）。
     */
    @Test
    public void attach补挂轮_kill开关触发后不再补扫() throws Exception {
        final Class<?> candidate = Class.forName(STATEMENT_CANDIDATE, false, getClass().getClassLoader());
        final Class<?> lateConn = Class.forName(HIKARI_CONN, false, hikariLoader());

        CaptureBudget.setKillAfterForTest(1);
        CaptureBudget.setAttachScheduleForTest(1L, 1L, 2);
        // 首轮：只有语句类，且 retransform 被 JVM 拒绝 → 连败达上限即熔断
        final Instrumentation inst = stubInstrumentationRounds(
                new Class<?>[][]{{candidate}, {lateConn}}, true);

        CatchAllMatcher.afterInstall(inst, false);
        assertTrue("首轮失败必须熔断", CaptureBudget.killed());
        final Thread deferred = CatchAllMatcher.lastDeferredThreadForTest();
        assertNotNull("补挂线程照旧排程（是否执行由 kill switch 决定）", deferred);
        deferred.join(10_000L);

        assertTrue("kill 后补挂轮不得再 retransform 任何类（只剩首轮那一次尝试）：" + retransformed,
                retransformed.size() == 1 && retransformed.contains(STATEMENT_CANDIDATE));
        assertFalse("kill 后补挂轮不得登记接受表",
                CatchAllMatcher.acceptedSpecs().containsKey(HIKARI_CONN));
        assertEquals("kill 后命中计数不得增长", 1L, CaptureBudget.interfaceMatchedCount());
    }

    // ---------- T-IbatisPanwei 券 D：未登记 JDBC 实现取证 ----------

    @Test
    public void 未登记实现_接口命中即记入快照且白名单类不计() throws Exception {
        final Class<?> candidate = Class.forName(STATEMENT_CANDIDATE, false, getClass().getClassLoader());
        final Class<?> whitelisted = h2IsolatedClass("org.h2.jdbc.JdbcStatement");

        CatchAllMatcher.afterInstall(stubInstrumentation(new Class<?>[]{candidate, whitelisted}), false);

        assertTrue("白名单外的接口命中类必须记入未登记快照（券 D 取证替代路径）："
                        + CaptureBudget.unregisteredDrivers(),
                CaptureBudget.unregisteredDrivers().contains(STATEMENT_CANDIDATE));
        assertFalse("白名单类不得被报成未登记：", CaptureBudget.unregisteredDrivers().contains(
                "org.h2.jdbc.JdbcStatement"));
    }

    // ---------- T-SqlListCatchup 券 G1：已知连接池包装族从「未登记」曝光排除 ----------

    /**
     * 池包装类是包装层不是驱动：从「未登记 JDBC 实现」曝光排除，织入面零变化。
     *
     * <p>真机形态（2026-11-07 join-demo，修复前）：Hikari 三类
     * （{@code HikariProxyPreparedStatement}/{@code ProxyStatement}/{@code ProxyConnection}）
     * 常驻诊断行「发现未登记 JDBC 实现：…」。修复后：该行不再出现池包装类；同时池包装类
     * **照旧兜底匹配并织入**（真机记忆里「解一层包装登记内层」的先例＝
     * StatementRegistry 内层登记 + NestGate 嵌套去重，采集语义不动）。
     *
     * <p>反向验证（S2）：从 {@link CatchAllMatcher} 的池包装前缀表里删掉
     * {@code com.zaxxer.hikari.pool.} 重跑，本用例必红（Hikari 类重新混进未登记快照）。
     */
    @Test
    public void 池包装类_不报未登记但仍织入() throws Exception {
        final Class<?> candidate = Class.forName(STATEMENT_CANDIDATE, false, getClass().getClassLoader());
        final Class<?> hikariPs = Class.forName(HIKARI_PS, false, hikariLoader());
        final Class<?> hikariConn = Class.forName(HIKARI_CONN, false, hikariLoader());

        CatchAllMatcher.afterInstall(stubInstrumentation(new Class<?>[]{candidate, hikariPs, hikariConn}), false);

        assertTrue("真实未知驱动仍必须报（池排除不得误伤取证路径）："
                        + CaptureBudget.unregisteredDrivers(),
                CaptureBudget.unregisteredDrivers().contains(STATEMENT_CANDIDATE));
        assertFalse("Hikari PS 生成物不得再报未登记（券 G1）：", CaptureBudget.unregisteredDrivers().contains(HIKARI_PS));
        assertFalse("Hikari Connection 生成物不得再报未登记（券 G1）：",
                CaptureBudget.unregisteredDrivers().contains(HIKARI_CONN));
        assertTrue("池包装类织入面零变化：仍须登记接受表（transformer 走同一织入通道）",
                CatchAllMatcher.acceptedSpecs().containsKey(HIKARI_PS)
                        && CatchAllMatcher.acceptedSpecs().containsKey(HIKARI_CONN));
        assertEquals("三类各织一次（真驱动 1 + 池包装 2）：" + retransformed, 3, retransformed.size());
    }

    /**
     * 前缀表覆盖五族已知池包装且不放行真驱动类名。
     *
     * <p>形态核对口径见 {@link CatchAllMatcher} POOL_WRAPPER_PREFIXES KDoc（真机 jar 仅有
     * Hikari，其余四族按字节/常识形态列具名代表；前缀级判定整族覆盖）。
     */
    @Test
    public void 池包装前缀表_已知族命中且不放行真驱动() {
        assertTrue("Hikari 生成物（真机三类之一）",
                CatchAllMatcher.isKnownPoolWrapper("com.zaxxer.hikari.pool.ProxyStatement"));
        assertTrue("c3p0 NewProxy*（impl 子包被前缀覆盖）",
                CatchAllMatcher.isKnownPoolWrapper("com.mchange.v2.c3p0.impl.NewProxyPreparedStatement"));
        assertTrue("DBCP 2.x Delegating*/Poolable*",
                CatchAllMatcher.isKnownPoolWrapper("org.apache.commons.dbcp2.PoolablePreparedStatement"));
        assertTrue("DBCP 1.x（遗留应用仍在用）",
                CatchAllMatcher.isKnownPoolWrapper("org.apache.commons.dbcp.PoolableConnection"));
        assertTrue("Tomcat-JDBC 静态包装（其语句代理多为 JDK 动态代理，另被代理排除兜住）",
                CatchAllMatcher.isKnownPoolWrapper("org.apache.tomcat.jdbc.pool.ProxyConnection"));
        assertTrue("Druid DruidPooled*",
                CatchAllMatcher.isKnownPoolWrapper("com.alibaba.druid.pool.DruidPooledPreparedStatement"));

        assertFalse("真驱动类名不得被误吞（国产驱动取证面）",
                CatchAllMatcher.isKnownPoolWrapper("org.panweidb.jdbc.PgPreparedStatement"));
        assertFalse("仓内夹具驱动不得被误吞", CatchAllMatcher.isKnownPoolWrapper(STATEMENT_CANDIDATE));
        assertFalse("非池包前缀的同名字类不得被误吞",
                CatchAllMatcher.isKnownPoolWrapper("cn.example.pool.HikariProxyPreparedStatement"));
        assertFalse("null 安全", CatchAllMatcher.isKnownPoolWrapper(null));
    }

    // ---------- T-IbatisPanwei 券 E：扫描预算只对候选类计数 ----------

    /**
     * 预算语义：零成本排除项（这里是枚举，现场对应 `java.*` / 接口 / 注解 / 代理）不占扫描上限。
     *
     * <p>反向验证：把 {@code cheapExcluded} 的短路去掉（回到「遍历到的每个类都占预算」），
     * 上限 1 会被枚举吃掉，本用例的 matched 变 0 → 必须红。
     */
    @Test
    public void 扫描预算_零成本排除项不占上限() throws Exception {
        CaptureBudget.setScanLimitForTest(1);
        final Class<?> enumClass = StatementWeaver.Kind.class; // 应用命名、非 JDK 前缀，但 isEnum
        final Class<?> candidate = Class.forName(STATEMENT_CANDIDATE, false, getClass().getClassLoader());

        CatchAllMatcher.afterInstall(stubInstrumentation(new Class<?>[]{enumClass, candidate}), false);

        assertEquals("枚举不得占用候选预算（上限 1 仍须扫到候选）：" + retransformed,
                STATEMENT_CANDIDATE, retransformed.isEmpty() ? null : retransformed.get(0));
        assertEquals("预算 1 = 只评估 1 个候选类", 1L, CaptureBudget.interfaceMatchedCount());
    }

    // ---------- premain 轮（券 08，红线核心） ----------

    @Test
    public void premain轮_只记候选零retransform() throws Exception {
        // 关掉补挂线程（否则本用例结束后线程会继续跑，污染其它用例的计数）
        CaptureBudget.setPremainScheduleForTest(0L, 0L, 1);
        final Class<?> candidate = Class.forName(STATEMENT_CANDIDATE, false, getClass().getClassLoader());
        final Class<?> conn = Class.forName(
                "cn.song.mybatisplus.sqlvis.agent.FakeVendorFixtures$VendorConn", false,
                getClass().getClassLoader());

        CatchAllMatcher.afterInstall(stubInstrumentation(new Class<?>[]{candidate, conn}), true);

        assertTrue("premain 阶段绝不内联织入未知字节形态（券 08 红线）：不得发起任何 retransform",
                retransformed.isEmpty());
        assertTrue("premain 阶段不得登记接受表（transformer 会据此在加载期织入）",
                CatchAllMatcher.acceptedSpecs().isEmpty());
        assertEquals("premain 阶段也不计入 attach 匹配读数", 0L, CaptureBudget.interfaceMatchedCount());
    }

    // ---------- 辅助 ----------

    private Instrumentation stubInstrumentation(final Class<?>[] loaded) {
        return stubInstrumentationRounds(new Class<?>[][]{loaded}, false);
    }

    /**
     * 多轮枚举桩：第 i 次 {@code getAllLoadedClasses()} 返回 {@code rounds[i]}（越界取最后一轮），
     * 用来模拟「补挂轮才看见的迟到类」。
     *
     * @param throwOnRetransform true = 每次 retransform 先记账再抛（模拟 JVM 拒绝重转换）
     */
    private Instrumentation stubInstrumentationRounds(final Class<?>[][] rounds,
            final boolean throwOnRetransform) {
        final java.util.concurrent.atomic.AtomicInteger call =
                new java.util.concurrent.atomic.AtomicInteger();
        return (Instrumentation) Proxy.newProxyInstance(
                CatchAllMatcherTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAllLoadedClasses":
                            final int i = call.getAndIncrement();
                            return rounds[Math.min(i, rounds.length - 1)];
                        case "isModifiableClass":
                            return true;
                        case "retransformClasses":
                            for (Object c : (Object[]) args[0]) {
                                retransformed.add(((Class<?>) c).getName());
                            }
                            if (throwOnRetransform) {
                                throw new IllegalStateException("fixture: retransform rejected");
                            }
                            return null;
                        case "addTransformer":
                            return null;
                        case "getObjectSize":
                            return 0L;
                        case "isRetransformClassesSupported":
                        case "isRedefineClassesSupported":
                        case "isNativeMethodPrefixSupported":
                            return true;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "StubInstrumentation";
                        default:
                            return null;
                    }
                });
    }

    /** Hikari jar 隔离 loader（initialize=false 用；父为平台 loader，避免装载测试 classpath 的副本）。 */
    private static ClassLoader hikariLoader() throws Exception {
        final File jar = findChildJar("HikariCP");
        assertNotNull("sqlvis.attachPoolChild 未提供 HikariCP jar——池包装类结构自检失去夹具", jar);
        return new URLClassLoader(new URL[]{jar.toURI().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    /** 从既有 attachPoolChild 配置解析出的路径里挑指定前缀的 jar（不新增构建依赖）。 */
    private static File findChildJar(String namePrefix) {
        final String path = System.getProperty("sqlvis.attachPoolChild");
        if (path == null || path.isEmpty()) {
            return null;
        }
        for (String p : path.split(File.pathSeparator)) {
            final File f = new File(p);
            if (f.getName().startsWith(namePrefix)) {
                return f;
            }
        }
        return null;
    }

    /** H2 类按隔离 loader 取（不污染系统 loader——ZeroLoadClassWriterWeaveTest 的前置条件）。 */
    private static Class<?> h2IsolatedClass(String name) throws Exception {
        final URL resource = CatchAllMatcherTest.class.getClassLoader().getResource("org/h2/Driver.class");
        if (resource == null) {
            throw new AssertionError("测试 classpath 缺 H2（夹具前置）");
        }
        final String url = resource.toString();
        final int sep = url.indexOf("!/");
        final URL jarUrl = new URL(sep >= 0 ? url.substring(0, sep + 2) : url);
        try (URLClassLoader l = new URLClassLoader(new URL[]{jarUrl}, ClassLoader.getPlatformClassLoader())) {
            return Class.forName(name, false, l);
        }
    }

    private static byte[] resourceBytes(String path) throws Exception {
        try (java.io.InputStream in = CatchAllMatcherTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull("夹具字节缺失：" + path, in);
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
