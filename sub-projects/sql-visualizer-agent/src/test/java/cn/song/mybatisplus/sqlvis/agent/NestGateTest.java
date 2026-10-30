package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.hook.StatementRegistry;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.BlockingQueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-CaptureCatchAll 券 07 守卫：「嵌套只报最内层」门（计划 D3=A，判据是**调用栈深度**，不是类名黑名单）。
 *
 * <p>形态建模：外层已织 execute 方法体里再调用内层已织 execute（HikariCP 的
 * {@code HikariProxyPreparedStatement.execute()} 转发给驱动 PS、MyBatis 的
 * {@code PreparedStatementLogger} 动态代理转发，都是这一形态）。两条事件会让 IDE 侧 60s 指纹
 * 去重折叠成 {@code ×2}，关掉去重就是两行；{@code EXEC_CTX} 还会抬高 {@code unresolvedCount}
 * 并打出误导性类名。
 *
 * <p>本类钉两件事：
 * <ol>
 *   <li>嵌套链**恰好 1 条事件**，且是**最内层**（SQL = 内层登记值）；</li>
 *   <li>非嵌套的连续执行**不误杀**（每次调用各 1 条）——门只在「同一线程同一链」内生效，
 *       且出口成对出栈，不会把线程深度永久抬高。</li>
 * </ol>
 */
public class NestGateTest {

    private static final String PKG = "cn.song.mybatisplus.sqlvis.agent";
    private static final String FIXTURE_PREFIX = PKG + ".FakeVendorFixtures";
    private static final String SELF_PREFIX = PKG + ".NestGateTest";

    /** 兜底/PS 面形态（无参 execute 族 + 批处理 + 参数面）。 */
    private static final StatementWeaver.Kind[] PS_FACE =
            {StatementWeaver.Kind.EXEC_CTX, StatementWeaver.Kind.BATCH, StatementWeaver.Kind.PARAM};

    private File dir;
    private ClassLoader loader;
    private boolean savedDisabled;

    @Before
    public void setUp() throws Exception {
        StatementRegistry.clearForTest();
        queue().clear();
        savedDisabled = AgentState.isDisabled();
        AgentState.setDisabled(false);
        // 门在本分支默认关（嵌套语义裁决见 NestGate 类 KDoc）——本类守卫的是「门开启时」的
        // 去重能力，故显式开闸跑；默认关的语义由 SqlInsightWeaveProbeTest 的嵌套用例守卫。
        nestGateEnabled(true);
        dir = Files.createTempDirectory("sqlvis-nestgate-").toFile();
        writeWoven("FakeVendorFixtures$VendorPs", PS_FACE);
        writeWoven("NestGateTest$OuterPs", PS_FACE);
        loader = childFirstLoader();
    }

    @After
    public void tearDown() {
        AgentState.setDisabled(savedDisabled);
        StatementRegistry.clearForTest();
        try {
            nestGateReset();
        } catch (Exception ignore) {
            // 复位失败不推翻用例结论（静态字段随下个用例 setUp 显式重设）
        }
        try {
            queue().clear();
        } catch (Exception ignore) {
            // 队列反射失败不影响用例结论
        }
    }

    /** NestGate 在 hook 包且 package-private，走反射桥（同 ProxySupport 反射先例）。 */
    private static void nestGateEnabled(boolean value) throws Exception {
        final Class<?> gate = Class.forName("cn.song.mybatisplus.sqlvis.agent.hook.NestGate");
        final java.lang.reflect.Method set = gate.getDeclaredMethod("setEnabledForTest", boolean.class);
        set.setAccessible(true);
        set.invoke(null, value);
    }

    private static void nestGateReset() throws Exception {
        final Class<?> gate = Class.forName("cn.song.mybatisplus.sqlvis.agent.hook.NestGate");
        final java.lang.reflect.Method reset = gate.getDeclaredMethod("resetForTest");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    @Test
    public void 嵌套链_只报最内层一条() throws Exception {
        final Object outer = newInstance("NestGateTest$OuterPs");
        final Object inner = outer.getClass().getMethod("inner").invoke(outer);
        StatementRegistry.register(outer, "SELECT outer", "jdbc:nest://db");
        StatementRegistry.register(inner, "SELECT inner", "jdbc:nest://db");

        outer.getClass().getMethod("executeUpdate").invoke(outer);

        final SqlEvent first = queue().poll();
        assertNotNull("嵌套链必须恰好产出 1 条事件（外层被抑制）", first);
        assertEquals("必须报最内层（驱动层）那条", "SELECT inner", first.sql);
        assertNull("嵌套链不得产出第二条（双报红线）", queue().poll());
    }

    @Test
    public void 非嵌套连续执行_不误杀() throws Exception {
        final Object inner = newInstance("FakeVendorFixtures$VendorPs");
        StatementRegistry.register(inner, "SELECT a", "jdbc:nest://db");
        inner.getClass().getMethod("executeUpdate").invoke(inner);
        inner.getClass().getMethod("executeUpdate").invoke(inner);

        assertNotNull("第一次执行必须有事件", queue().poll());
        assertNotNull("第二次执行必须有事件（出口成对出栈，深度不残留）", queue().poll());
        assertNull("两次执行不得多出第三条", queue().poll());
    }

    @Test
    public void 嵌套之后_单帧恢复产出() throws Exception {
        final Object outer = newInstance("NestGateTest$OuterPs");
        final Object inner = outer.getClass().getMethod("inner").invoke(outer);
        StatementRegistry.register(outer, "SELECT outer", "jdbc:nest://db");
        StatementRegistry.register(inner, "SELECT inner", "jdbc:nest://db");

        outer.getClass().getMethod("executeUpdate").invoke(outer);
        assertEquals("SELECT inner", queue().poll().sql);

        // 链结束后单独执行内层：必须照常产出（证明线程态已清、门没有把线程钉在「已抑制」态）
        inner.getClass().getMethod("executeUpdate").invoke(inner);
        final SqlEvent after = queue().poll();
        assertNotNull("嵌套链结束后单帧必须恢复产出", after);
        assertEquals("SELECT inner", after.sql);
    }

    /** 外层夹具：executeUpdate 转发给内层（池包装/日志代理的等价形态）。 */
    public static class OuterPs extends FakeVendorFixtures.VendorPs {
        private final FakeVendorFixtures.VendorPs inner = new FakeVendorFixtures.VendorPs();

        @Override
        public int executeUpdate() {
            return inner.executeUpdate();
        }

        public FakeVendorFixtures.VendorPs inner() {
            return inner;
        }
    }

    // ---------- 辅助 ----------

    private Object newInstance(String simpleName) throws Exception {
        return Class.forName(PKG + "." + simpleName, true, loader).getDeclaredConstructor().newInstance();
    }

    /** 织入后落盘（子优先 loader 只从该目录取这两个前缀的类）。 */
    private void writeWoven(String simpleName, StatementWeaver.Kind[] spec) throws Exception {
        final byte[] original = classBytes(simpleName);
        final byte[] woven = StatementWeaver.weaveSpec(spec, ClassLoader.getSystemClassLoader(), original);
        final File cls = new File(dir, PKG.replace('.', '/') + "/" + simpleName + ".class");
        cls.getParentFile().mkdirs();
        Files.write(cls.toPath(), woven);
        assertTrue(simpleName + " 必须真被织入（夹具前置）", !java.util.Arrays.equals(original, woven));
    }

    private static byte[] classBytes(String simpleName) throws Exception {
        final String path = PKG.replace('.', '/') + "/" + simpleName + ".class";
        try (java.io.InputStream in = ClassLoader.getSystemClassLoader().getResourceAsStream(path)) {
            assertNotNull("夹具编译产物缺失：" + path, in);
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private ClassLoader childFirstLoader() throws Exception {
        return new java.net.URLClassLoader(new java.net.URL[]{dir.toURI().toURL()},
                ClassLoader.getSystemClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(FIXTURE_PREFIX) || name.startsWith(SELF_PREFIX)) {
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

    @SuppressWarnings("unchecked")
    private static BlockingQueue<SqlEvent> queue() throws Exception {
        final Field field = cn.song.mybatisplus.sqlvis.agent.transport.EventBus.class
                .getDeclaredField("QUEUE");
        field.setAccessible(true);
        return (BlockingQueue<SqlEvent>) field.get(null);
    }
}
