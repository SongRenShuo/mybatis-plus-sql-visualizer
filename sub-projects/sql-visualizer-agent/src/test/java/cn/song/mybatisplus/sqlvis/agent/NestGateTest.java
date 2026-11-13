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
        // T-NestGateObserve：观测计数与线程态逐用例复位（既有 4 例的读数不受影响，
        // 新增用例依赖「陈旧计数从 0 起」；同时也清掉上一例可能的残留 depth）
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.resetNestGateForTest();
        savedDisabled = AgentState.isDisabled();
        AgentState.setDisabled(false);
        dir = Files.createTempDirectory("sqlvis-nestgate-").toFile();
        writeWoven("FakeVendorFixtures$VendorPs", PS_FACE);
        writeWoven("NestGateTest$OuterPs", PS_FACE);
        loader = childFirstLoader();
    }

    @After
    public void tearDown() {
        AgentState.setDisabled(savedDisabled);
        StatementRegistry.clearForTest();
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.resetNestGateForTest();
        try {
            queue().clear();
        } catch (Exception ignore) {
            // 队列反射失败不影响用例结论
        }
    }

    @Test
    public void 嵌套链_只报最内层一条() throws Exception {
        final Object outer = newInstance("NestGateTest$OuterPs");
        final Object inner = outer.getClass().getMethod("inner").invoke(outer);
        StatementRegistry.register(outer, "SELECT outer", "jdbc:nest://db");
        StatementRegistry.register(inner, "SELECT inner", "jdbc:nest://db");

        // T-PanweiInsertCapture F2：抑制计数基线（静态累计，跨用例不复位）
        final long suppressedBefore = cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks
                .nestSuppressedCount();

        outer.getClass().getMethod("executeUpdate").invoke(outer);

        final SqlEvent first = queue().poll();
        assertNotNull("嵌套链必须恰好产出 1 条事件（外层被抑制）", first);
        assertEquals("必须报最内层（驱动层）那条", "SELECT inner", first.sql);
        assertNull("嵌套链不得产出第二条（双报红线）", queue().poll());
        assertEquals(
                "被抑制的外层帧必须计数（F2：此前这条路径零读数）",
                suppressedBefore + 1,
                cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.nestSuppressedCount());
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

    /**
     * T-OracleWrapperRegistry 的放大器修复：内层取不到 SQL（登记盲区）时**不得**把外层一起抑制。
     *
     * <p>真实形态＝Oracle 包装器语句：prepare 面登记的是包装器，exec 钩子落在内层物理语句上，
     * 内层查表必然落空。旧实现把「进入过」当「产出过」，内层 exit 时就置 `innerEmitted`，
     * 结果外层那条本可产出的 SQL 也被吞掉（面板表现为「一条都不出」）。
     */
    @Test
    public void 内层盲区_不得抑制外层() throws Exception {
        final Object outer = newInstance("NestGateTest$OuterPs");
        // 只登记外层；内层不登记 = 盲区
        StatementRegistry.register(outer, "SELECT outer", "jdbc:nest://db");

        outer.getClass().getMethod("executeUpdate").invoke(outer);

        final SqlEvent event = queue().poll();
        assertNotNull("内层无 SQL 时外层必须兜底产出（旧实现此处为 0 条）", event);
        assertEquals("SELECT outer", event.sql);
        assertNull("仍不得双报", queue().poll());
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

    // ---------- T-NestGateObserve：深度泄漏观测（纯观测，不改抑制语义） ----------

    /**
     * 正常嵌套**不误报**：外层+内层真嵌套（一次 execute 的时长）→ {@code nestStale} 保持 0，
     * 事件仍只报最内层一条（语义与原行为一致）。
     */
    @Test
    public void 正常嵌套_不误报陈旧且仍只报最内层() throws Exception {
        final Object outer = newInstance("NestGateTest$OuterPs");
        final Object inner = outer.getClass().getMethod("inner").invoke(outer);
        StatementRegistry.register(outer, "SELECT outer", "jdbc:nest://db");
        StatementRegistry.register(inner, "SELECT inner", "jdbc:nest://db");

        final long staleBefore = cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.nestStaleCount();

        outer.getClass().getMethod("executeUpdate").invoke(outer);

        final SqlEvent event = queue().poll();
        assertNotNull("嵌套链仍必须恰好产出 1 条事件", event);
        assertEquals("必须报最内层（驱动层）那条", "SELECT inner", event.sql);
        assertNull("不得双报", queue().poll());
        assertEquals("正常嵌套不得被计为陈旧（链存活远小于阈值）",
                staleBefore, cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.nestStaleCount());
    }

    /**
     * 陈旧**可判**：进一帧不退出 + 时钟拨过阈值 + 再 enter → {@code nestStale} +1；同一链的产出
     * 语义不变（仍只报最内层、外层照旧计 nestSuppressed）。
     *
     * <p>时钟经 {@link cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks#setNestClockForTest}
     * 注入（NestGate 包内可见，跨包测试走这一条转发）；阈值显式设 5000ms，不依赖系统属性。
     */
    @Test
    public void 陈旧嵌套_未达连续次数不计_长事务合法嵌套不误报() throws Exception {
        final Object outer = newInstance("FakeVendorFixtures$VendorPs");
        final Object inner = newInstance("FakeVendorFixtures$VendorPs");
        StatementRegistry.register(outer, "SELECT outer", "jdbc:nest://db");
        StatementRegistry.register(inner, "SELECT inner", "jdbc:nest://db");

        final java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.setNestClockForTest(clock::get);
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.setNestStaleMsForTest(5_000L);

        final long staleBefore = cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.nestStaleCount();
        final long suppressedBefore = cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks
                .nestSuppressedCount();

        // 链首：depth 0→1（记链龄起点）
        final long tokenOuter = cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks
                .onStatementEnter(outer);
        assertTrue("链首帧 token 非 0", tokenOuter != 0L);

        // 时钟拨过阈值（+6s > 5s）后再 enter：这是一条「跑很久的语句里再执行一条」的合法嵌套，
        // 只进 1 次、远未达连续 3 次门槛 → 不得计数（2026-11-10 收口：单次超阈值不足定性）。
        clock.addAndGet(6_000_000_000L);
        final long tokenInner = cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks
                .onStatementEnter(inner);
        assertTrue("嵌套帧 token 非 0", tokenInner != 0L);
        assertEquals("单次超阈值嵌套不得计为陈旧（避免长事务误报）",
                staleBefore, cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.nestStaleCount());

        // 语义不变：内层产出、外层被抑制，全链仍只 1 条事件
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks
                .onExecuteExit(inner, null, "executeUpdate", tokenInner, null, null);
        final SqlEvent event = queue().poll();
        assertNotNull("长链下内层仍必须产出（观测不改放行语义）", event);
        assertEquals("SELECT inner", event.sql);
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks
                .onExecuteExit(outer, null, "executeUpdate", tokenOuter, null, null);
        assertNull("外层仍不得双报", queue().poll());
        assertEquals("外层帧仍按原语义计 nestSuppressed",
                suppressedBefore + 1,
                cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.nestSuppressedCount());
    }

    /**
     * 泄漏形态**才**计数：同一链上连续 3 次「超阈值仍进入」→ {@code nestStale} 恰好 +1；
     * 之后同一链继续进入**不再重复计**（泄漏期不刷读数）。
     *
     * <p>判据差别即本轮收口的目的：合法长语句内部的嵌套是个位数且随后闭合；真泄漏的链一直不闭合，
     * 每条语句都以嵌套进入出现，连续次数持续累积。
     */
    @Test
    public void 连续陈旧三次_计一次且同一链不重复计() throws Exception {
        final java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.setNestClockForTest(clock::get);
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.setNestStaleMsForTest(5_000L);
        final long staleBefore = cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.nestStaleCount();

        final Object first = newInstance("FakeVendorFixtures$VendorPs");
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.onStatementEnter(first); // 链首
        clock.addAndGet(6_000_000_000L);

        // 第 1、2 次超阈值进入：未达门槛
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.onStatementEnter(new Object());
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.onStatementEnter(new Object());
        assertEquals("未达连续 3 次不得计数",
                staleBefore, cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.nestStaleCount());

        // 第 3 次：达门槛，计一次
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.onStatementEnter(new Object());
        assertEquals("连续第 3 次超阈值进入必须计一次陈旧",
                staleBefore + 1, cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.nestStaleCount());

        // 第 4、5 次：同一链不再重复计（泄漏期读数不刷）
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.onStatementEnter(new Object());
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.onStatementEnter(new Object());
        assertEquals("同一链只计一次",
                staleBefore + 1, cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.nestStaleCount());
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
