package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks;
import cn.song.mybatisplus.sqlvis.agent.hook.StatementRegistry;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.concurrent.BlockingQueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T-CaptureCatchAll 红线用例（券 10）：**不能因为增强捕获导致用户服务受影响**。
 *
 * <p>四条读数（用户令的直接可执行面）：
 * <ol>
 *   <li><b>带/不带兜底，宿主行为一致</b>：同一夹具类（兜底面织入 vs 原字节）对同一条 SQL 返回
 *       同值、对同一异常路径抛同类同消息——采集绝不改写返回值或异常；</li>
 *   <li><b>matcher 抛异常，宿主仍正常</b>：接口匹配轮里单类判定/retransform 抛异常只计数，
 *       不外溢、不中止整轮；</li>
 *   <li><b>强转 assertSame</b>：兜底织入不替换对象身份——连接原样返回、prepare 面产出的 PS
 *       仍是驱动实例（可强转到具体实现类）；</li>
 *   <li><b>连续失败 → kill switch → 退回白名单</b>：兜底全关后白名单织入照旧。</li>
 * </ol>
 */
public class CatchAllRedLineTest {

    private static final String PKG = "cn.song.mybatisplus.sqlvis.agent";
    private static final String FIXTURE_PREFIX = PKG + ".FakeVendorFixtures";
    private static final String CANDIDATE = FIXTURE_PREFIX + "$VendorPs";

    private File dir;
    private ClassLoader loader;
    private boolean savedDisabled;

    @Before
    public void setUp() throws Exception {
        CaptureBudget.resetForTest();
        CatchAllMatcher.resetForTest();
        SqlHookTransformer.resetCountersForTest();
        StatementRegistry.clearForTest();
        queue().clear();
        savedDisabled = AgentState.isDisabled();
        AgentState.setDisabled(false);

        dir = Files.createTempDirectory("sqlvis-redline-").toFile();
        // 兜底面形态：与 attach/transformer 兜底完全一致（不含 ADD_BATCH/CLEAR_BATCH 两个读原局部的出口）
        writeWoven("VendorStmt", WeaveSpecs.catchAllStatementFace());
        writeWoven("VendorConn", WeaveSpecs.catchAllPrepareFace());
        writeWoven("VendorPs", WeaveSpecs.catchAllStatementFace());
        // 异常夹具不织入，但必须随织入类一起落盘：子优先 loader 下 VendorStmt 抛出的
        // BoomException 只能从同一目录解析（否则 NoClassDefFoundError 会掩盖「异常原样重抛」判据）
        copyRaw("BoomException");
        loader = childFirstLoader();
    }

    @After
    public void tearDown() {
        AgentState.setDisabled(savedDisabled);
        StatementRegistry.clearForTest();
        CaptureBudget.resetForTest();
        CatchAllMatcher.resetForTest();
        try {
            queue().clear();
        } catch (Exception ignore) {
            // 反射失败不影响结论
        }
    }

    // ---------- ① 带/不带兜底，行为一致 ----------

    @Test
    public void 带与不带兜底_同SQL同结果同异常() throws Exception {
        final Object woven = newInstance("VendorStmt");
        final FakeVendorFixtures.VendorStmt raw = new FakeVendorFixtures.VendorStmt();

        assertEquals("executeUpdate 返回值必须一致",
                raw.executeUpdate("UPDATE t SET x=1"), invokeInt(woven, "executeUpdate", "UPDATE t SET x=1"));
        assertEquals("execute 返回值必须一致",
                raw.execute("SELECT 1"), invokeBool(woven, "execute", "SELECT 1"));
        assertNull("executeQuery 返回值必须一致", invoke(woven, "executeQuery", "SELECT 1"));
        assertEquals("executeBatch 返回值必须一致（兜底面不含 ADD_BATCH，批串不累积，但返回值不变）",
                raw.executeBatch().length, ((int[]) invokeNoArg(woven, "executeBatch")).length);

        // 异常路径：类型 + 消息逐字一致（织入出口不得改写异常）
        final Throwable rawFailure = captureFailure(raw, "UPDATE boom");
        final Throwable wovenFailure = captureFailure(woven, "UPDATE boom");
        assertNotNull("原字节路径必须抛异常（判据前置）", rawFailure);
        assertNotNull("织入路径必须抛异常", wovenFailure);
        assertEquals("异常类型必须一致", rawFailure.getClass().getName(), wovenFailure.getClass().getName());
        assertEquals("异常消息必须一致", rawFailure.getMessage(), wovenFailure.getMessage());
        assertEquals("异常类型必须是夹具原始类型（不得被织入层包装）",
                FIXTURE_PREFIX + "$BoomException", wovenFailure.getClass().getName());
    }

    // ---------- ② matcher 抛异常，宿主仍正常 ----------

    @Test
    public void matcher抛异常_attach轮不中止且不外溢() throws Exception {
        final Class<?> candidate = Class.forName(CANDIDATE, false, getClass().getClassLoader());
        final Instrumentation hostile = stubInstrumentation(new Class<?>[]{candidate}, true);

        // 不得外抛：外抛在 premain 路径就是宿主起不来（红线）
        CatchAllMatcher.afterInstall(hostile, false);

        assertEquals("单类失败只计数，不外溢", 1L, CaptureBudget.interfaceFailedCount());
        assertFalse("一次失败不得熔断（killAfter 默认 3）", CaptureBudget.killed());
    }

    @Test
    public void matcher枚举异常_attach轮安全返回() {
        final Instrumentation broken = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if ("getAllLoadedClasses".equals(method.getName())) {
                        throw new IllegalStateException("fixture: enumeration exploded");
                    }
                    return defaultValue(proxy, method, args);
                });

        CatchAllMatcher.afterInstall(broken, false); // 不得外抛
        assertEquals("枚举失败即本轮无匹配，不产生失败计数", 0L, CaptureBudget.interfaceMatchedCount());
    }

    // ---------- ③ 强转 assertSame ----------

    @Test
    public void 兜底织入不替换对象身份_强转与assertSame() throws Exception {
        // 连接面：兜底全开时 onConnection 仍原样返回（连接替换通道出厂关闭，兜底不得复活它）
        final Object rawConn = new FakeDriver().connect("jdbc:fake://redline", new java.util.Properties());
        assertSame("兜底全开时连接对象必须原样返回（身份不变）",
                rawConn, SqlHookCallbacks.onConnection(rawConn, "jdbc:fake://redline"));

        // prepare 面：织入只插出口回调，返回的仍是驱动 PS 实例——可强转到具体实现类
        final Object conn = newInstance("VendorConn");
        final Object ps = conn.getClass().getMethod("prepareStatement", String.class)
                .invoke(conn, "SELECT 1");
        assertNotNull("prepare 面不得吞掉返回值", ps);
        final Class<?> psClass = Class.forName(FIXTURE_PREFIX + "$VendorPs", true, loader);
        assertTrue("返回值必须是驱动实现类实例（可强转）", psClass.isInstance(ps));
        assertSame("强转前后必须是同一对象（不得换成代理）", ps, psClass.cast(ps));
    }

    // ---------- ④ 连续失败 → kill switch → 退回白名单 ----------

    @Test
    public void 连续失败触发kill_白名单织入照旧() throws Exception {
        CaptureBudget.setKillAfterForTest(2);
        CaptureBudget.noteCatchAllFailure("a=VerifyError");
        CaptureBudget.noteCatchAllFailure("b=VerifyError");
        assertTrue("连败达上限必须熔断", CaptureBudget.killed());

        final SqlHookTransformer transformer = new SqlHookTransformer();
        // 白名单类：熔断与它无关，照旧织入
        final byte[] whitelistBytes = resourceBytes(FIXTURE_PREFIX.replace('.', '/') + "$VendorConn.class");
        final byte[] woven = transformer.transform(getClass().getClassLoader(),
                "org/h2/jdbc/JdbcConnection", null, null, whitelistBytes);
        assertFalse("kill switch 只关兜底：白名单织入必须照旧产出新字节",
                java.util.Arrays.equals(whitelistBytes, woven));

        // 兜底类：熔断后即便接受表残留条目也不得生效
        final byte[] candidateBytes = resourceBytes(CANDIDATE.replace('.', '/') + ".class");
        CatchAllMatcher.afterInstall(stubInstrumentation(new Class<?>[]{
                Class.forName(CANDIDATE, false, getClass().getClassLoader())}, false), false);
        assertTrue("kill 后接受表必须为空（attach 轮整体退回白名单）",
                CatchAllMatcher.acceptedSpecs().isEmpty());
        assertNull("kill 后兜底类不得被织入",
                transformer.transform(getClass().getClassLoader(), CANDIDATE.replace('.', '/'),
                        null, null, candidateBytes));
    }

    // ---------- 辅助 ----------

    private static Throwable captureFailure(Object target, String sql) throws Exception {
        try {
            target.getClass().getMethod("executeUpdate", String.class).invoke(target, sql);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    private Object newInstance(String simpleName) throws Exception {
        return Class.forName(FIXTURE_PREFIX + "$" + simpleName, true, loader)
                .getDeclaredConstructor().newInstance();
    }

    private static int invokeInt(Object target, String method, String arg) throws Exception {
        return (Integer) target.getClass().getMethod(method, String.class).invoke(target, arg);
    }

    private static boolean invokeBool(Object target, String method, String arg) throws Exception {
        return (Boolean) target.getClass().getMethod(method, String.class).invoke(target, arg);
    }

    private static Object invoke(Object target, String method, String arg) throws Exception {
        return target.getClass().getMethod(method, String.class).invoke(target, arg);
    }

    private static Object invokeNoArg(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    /** hostile=true 时 isModifiableClass 抛异常（模拟单类判定/retransform 失败形态）。 */
    private static Instrumentation stubInstrumentation(final Class<?>[] loaded, final boolean hostile) {
        return (Instrumentation) Proxy.newProxyInstance(
                CatchAllRedLineTest.class.getClassLoader(), new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if ("getAllLoadedClasses".equals(method.getName())) {
                        return loaded;
                    }
                    if ("isModifiableClass".equals(method.getName()) && hostile) {
                        throw new IllegalStateException("fixture: matcher exploded");
                    }
                    if ("isModifiableClass".equals(method.getName())) {
                        return true;
                    }
                    if ("retransformClasses".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(proxy, method, args);
                });
    }

    private static Object defaultValue(Object proxy, java.lang.reflect.Method method, Object[] args) {
        final String n = method.getName();
        if ("getObjectSize".equals(n)) {
            return 0L;
        }
        if ("isRetransformClassesSupported".equals(n) || "isRedefineClassesSupported".equals(n)
                || "isNativeMethodPrefixSupported".equals(n)) {
            return true;
        }
        if ("hashCode".equals(n)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(n)) {
            return proxy == (args == null ? null : args[0]);
        }
        if ("toString".equals(n)) {
            return "StubInstrumentation";
        }
        return null;
    }

    private void writeWoven(String simpleName, StatementWeaver.Kind[] spec) throws Exception {
        final byte[] original = resourceBytes(FIXTURE_PREFIX.replace('.', '/') + "$" + simpleName + ".class");
        final byte[] woven = StatementWeaver.weaveSpec(spec, ClassLoader.getSystemClassLoader(), original);
        final File cls = new File(dir, PKG.replace('.', '/') + "/FakeVendorFixtures$" + simpleName + ".class");
        cls.getParentFile().mkdirs();
        Files.write(cls.toPath(), woven);
        assertTrue(simpleName + " 必须真被织入（夹具前置）", !java.util.Arrays.equals(original, woven));
    }

    /** 原字节落盘（不织入的兄弟类，供子优先 loader 解析）。 */
    private void copyRaw(String simpleName) throws Exception {
        final byte[] original = resourceBytes(FIXTURE_PREFIX.replace('.', '/') + "$" + simpleName + ".class");
        final File cls = new File(dir, PKG.replace('.', '/') + "/FakeVendorFixtures$" + simpleName + ".class");
        cls.getParentFile().mkdirs();
        Files.write(cls.toPath(), original);
    }

    private ClassLoader childFirstLoader() throws Exception {
        return new java.net.URLClassLoader(new java.net.URL[]{dir.toURI().toURL()},
                ClassLoader.getSystemClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(FIXTURE_PREFIX)) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name);
                        if (c == null) {
                            c = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(c);
                        }
                        return c;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    private static byte[] resourceBytes(String path) throws Exception {
        try (java.io.InputStream in = ClassLoader.getSystemClassLoader().getResourceAsStream(path)) {
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

    @SuppressWarnings("unchecked")
    private static BlockingQueue<SqlEvent> queue() throws Exception {
        final Field field = cn.song.mybatisplus.sqlvis.agent.transport.EventBus.class
                .getDeclaredField("QUEUE");
        field.setAccessible(true);
        return (BlockingQueue<SqlEvent>) field.get(null);
    }
}
