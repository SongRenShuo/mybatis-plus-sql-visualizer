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
        final Class<?> candidate = Class.forName(STATEMENT_CANDIDATE, false, getClass().getClassLoader());
        final Class<?> conn = Class.forName(
                "cn.song.mybatisplus.sqlvis.agent.FakeVendorFixtures$VendorConn", false,
                getClass().getClassLoader());

        CatchAllMatcher.afterInstall(stubInstrumentation(new Class<?>[]{candidate, conn}), false);

        assertEquals("扫描类数达上限后不得继续匹配（券 09①）", 1L, CaptureBudget.interfaceMatchedCount());
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
        return (Instrumentation) Proxy.newProxyInstance(
                CatchAllMatcherTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAllLoadedClasses":
                            return loaded;
                        case "isModifiableClass":
                            return true;
                        case "retransformClasses":
                            for (Object c : (Object[]) args[0]) {
                                retransformed.add(((Class<?>) c).getName());
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
