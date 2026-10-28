package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.hook.StatementRegistry;
import cn.song.mybatisplus.sqlvis.agent.hook.WeaveGate;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.BlockingQueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * [StatementWeaver] 端到端（T-StatementWeave S7）：织入夹具实体类字节 → 子优先 loader 定义 →
 * execute 族钩子随方法调用触发 → 事件落地（attach 即生效通道的单机全链）。
 *
 * <p>夹具 [FakeVendorFixtures] 为**非代理实体类**（真实驱动的语句实现类形态），三个类对应三个
 * 织入面：prepare 面（VendorConn）/ PS 面（VendorPs，SQL 走登记表）/ statement 面（VendorStmt，
 * SQL 取首参）。「先建池再 retransform」的真机时序判别用例留下轮 runIde 沙盒批（单测 JVM 拿不到
 * Instrumentation，retransform 语义无法在纯单测复现——本类只证织入通道自身正确）。
 */
public class StatementWeaveEndToEndTest {

    private static final String FIXTURE_PREFIX = "cn.song.mybatisplus.sqlvis.agent.FakeVendorFixtures";
    private static final StatementWeaver.Kind[] STATEMENT_FACE =
            {StatementWeaver.Kind.EXEC_PARAM, StatementWeaver.Kind.EXEC_CTX,
                    StatementWeaver.Kind.BATCH, StatementWeaver.Kind.ADD_BATCH,
                    StatementWeaver.Kind.CLEAR_BATCH, StatementWeaver.Kind.PARAM};
    private static final StatementWeaver.Kind[] PS_FACE =
            {StatementWeaver.Kind.EXEC_CTX, StatementWeaver.Kind.BATCH, StatementWeaver.Kind.PARAM};
    private static final StatementWeaver.Kind[] PREPARE_FACE = {StatementWeaver.Kind.PREPARE};

    private File dir;
    private ClassLoader loader;
    private boolean savedDisabled;

    @Before
    public void setUp() throws Exception {
        StatementRegistry.clearForTest();
        queue().clear();
        savedDisabled = AgentState.isDisabled();
        AgentState.setDisabled(false);
        dir = Files.createTempDirectory("sqlvis-stmtweave-").toFile();
        writeWoven("VendorStmt", STATEMENT_FACE);
        writeWoven("VendorPs", PS_FACE);
        writeWoven("VendorConn", PREPARE_FACE);
        loader = childFirstLoader();
    }

    @After
    public void tearDown() throws Exception {
        AgentState.setDisabled(savedDisabled);
        WeaveGate.exitProxy();
        StatementRegistry.clearForTest();
        queue().clear();
    }

    // ---------- statement 面：带参形态 ----------

    @Test
    public void 带参executeUpdate_事件取首参_sql影响行数与耗时在位() throws Exception {
        Object stmt = newInstance("VendorStmt");
        assertEquals(2, invokeInt(stmt, "executeUpdate", "UPDATE t SET x=1"));
        SqlEvent event = queue().poll();
        assertNotNull("带参 execute 必须产出事件", event);
        assertEquals("UPDATE t SET x=1", event.sql);
        assertEquals("executeUpdate 影响行数取返回值", Long.valueOf(2L), event.updateCount);
        assertTrue("durationNanos 应非负", event.durationNanos >= 0);
        // T-ConnCastSafety：参数改由 setXxx 入口织入采集（连接代理关闭后不能再靠代理层）
        assertNull("无参数绑定的语句 params 应保持 null（不产空壳）", event.params);
    }

    @Test
    public void 异常路径_事件记errorText且异常原样重抛() throws Exception {
        Object stmt = newInstance("VendorStmt");
        try {
            invokeInt(stmt, "executeUpdate", "UPDATE boom SET x=1");
            throw new AssertionError("夹具异常应原样抛出（宿主语义不变红线）");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            // 反射层包裹：cause 应为夹具原始异常（织入出口不得改写异常类型）。
            // 按「类名」断言而非 instanceof——夹具类由 child loader 定义（同包双 Class 对象），
            // instanceof 跨 loader 恒假（ConnectWeaveEndToEndTest 接口断言同款口径）。
            assertEquals("异常应原样重抛",
                    "cn.song.mybatisplus.sqlvis.agent.FakeVendorFixtures$BoomException",
                    expected.getCause().getClass().getName());
        }
        SqlEvent event = queue().poll();
        assertNotNull("异常路径也须产出事件", event);
        assertTrue("errorText 应含异常类名", event.errorText != null && event.errorText.contains("BoomException"));
    }

    @Test
    public void 参数面_setXxx入口采集_事件携参与脱敏同口径() throws Exception {
        Object ps = newInstance("VendorPs");
        StatementRegistry.register(ps, "SELECT * FROM t WHERE id = ?", "jdbc:fake://param");
        ps.getClass().getMethod("setInt", int.class, int.class).invoke(ps, 1, 42);
        ps.getClass().getMethod("executeQuery").invoke(ps);

        SqlEvent event = queue().poll();
        assertNotNull("带参 PS 执行必须产出事件", event);
        assertEquals("SELECT * FROM t WHERE id = ?", event.sql);
        assertNotNull("参数必须由 setXxx 入口织入采集（连接代理关闭后唯一来源）", event.params);
        assertEquals(1, event.params.size());
        assertEquals("1", event.params.get(0)[0]);
        assertEquals("42", event.params.get(0)[1]);
        assertEquals("Integer", event.params.get(0)[2]);
    }

    @Test
    public void 批处理_addBatch累积_executeBatch组装() throws Exception {
        Object stmt = newInstance("VendorStmt");
        invokeVoid(stmt, "addBatch", "INSERT INTO a VALUES (1)");
        invokeVoid(stmt, "addBatch", "INSERT INTO b VALUES (2)");
        // 反射调 executeBatch()（返回 int[]）
        Object result = stmt.getClass().getMethod("executeBatch").invoke(stmt);
        assertNotNull(result);
        SqlEvent event = queue().poll();
        assertNotNull("executeBatch 须产出事件", event);
        assertEquals("INSERT INTO a VALUES (1);\nINSERT INTO b VALUES (2)", event.sql);
        assertEquals("批影响行数求和", Long.valueOf(2L), event.updateCount);
    }

    @Test
    public void 批处理_executeBatch消费即复位_跨批次残串不得串包() throws Exception {
        Object stmt = newInstance("VendorStmt");
        invokeVoid(stmt, "addBatch", "INSERT INTO a VALUES (1)");
        stmt.getClass().getMethod("executeBatch").invoke(stmt);
        assertNotNull("第一批须产出事件", queue().poll());
        invokeVoid(stmt, "addBatch", "INSERT INTO b VALUES (2)");
        stmt.getClass().getMethod("executeBatch").invoke(stmt);
        SqlEvent second = queue().poll();
        assertNotNull("第二批须产出事件", second);
        assertEquals("executeBatch 消费后必须复位批串（F74），第二事件不得携带第一批残串",
                "INSERT INTO b VALUES (2)", second.sql);
    }

    @Test
    public void clearBatch织入_清账后executeBatch零残串() throws Exception {
        Object stmt = newInstance("VendorStmt");
        invokeVoid(stmt, "addBatch", "INSERT INTO stale VALUES (0)");
        stmt.getClass().getMethod("clearBatch").invoke(stmt);
        invokeVoid(stmt, "addBatch", "INSERT INTO fresh VALUES (1)");
        stmt.getClass().getMethod("executeBatch").invoke(stmt);
        SqlEvent event = queue().poll();
        assertNotNull("clearBatch 后新批仍须产出事件", event);
        assertEquals("clearBatch 必须织入清账（F74），事件不得携带清批前残串",
                "INSERT INTO fresh VALUES (1)", event.sql);
    }

    // ---------- prepare 面 + PS 面：登记表形态 ----------

    @Test
    public void prepare登记后_无参execute_取登记sql与url() throws Exception {
        Object conn = newInstance("VendorConn");
        Object ps = conn.getClass().getMethod("prepareStatement", String.class)
                .invoke(conn, "SELECT 1");
        assertEquals("executeUpdate() 返回夹具桩值", 3, invokeInt3(ps));
        SqlEvent event = queue().poll();
        assertNotNull("登记后无参 execute 必须产出事件", event);
        assertEquals("SELECT 1", event.sql);
        assertEquals("URL 应取 prepare 时连接的 getURL()", "jdbc:fakevendor://db1", event.url);
        assertEquals("executeUpdate 无参形态影响行数取返回值", Long.valueOf(3L), event.updateCount);
    }

    @Test
    public void 未登记PS执行_盲区跳过且计数递增() throws Exception {
        long before = StatementRegistry.unresolvedCount();
        Object ps = newInstance("VendorPs");
        // 直接 new（未经织入的 prepare 登记）→ 盲区
        invokeInt3(ps);
        assertNull("盲区 PS 不得产出伪事件", queue().poll());
        assertEquals("盲区计数应递增", before + 1, StatementRegistry.unresolvedCount());
    }

    // ---------- 双通道去重门 ----------

    @Test
    public void 代理门禁期间_织入通道短路() throws Exception {
        Object stmt = newInstance("VendorStmt");
        WeaveGate.enterProxy();
        try {
            invokeInt(stmt, "executeUpdate", "UPDATE gated SET x=1");
            assertNull("代理层活跃期间织入通道必须短路（双通道去重红线）", queue().poll());
        } finally {
            WeaveGate.exitProxy();
        }
        invokeInt(stmt, "executeUpdate", "UPDATE gated SET x=1");
        assertNotNull("门禁解除后织入通道恢复产出", queue().poll());
    }

    // ---------- 停采短路 ----------

    @Test
    public void 停采volatile短路_织入通道同受闸() throws Exception {
        Object stmt = newInstance("VendorStmt");
        AgentState.setDisabled(true); // 下降沿清队列
        try {
            invokeInt(stmt, "executeUpdate", "UPDATE t SET x=1");
            assertNull("停采期间织入通道不得产出", queue().poll());
        } finally {
            AgentState.setDisabled(false);
        }
        queue().clear();
        invokeInt(stmt, "executeUpdate", "UPDATE t SET x=1");
        assertNotNull("恢复采集后织入通道恢复产出", queue().poll());
    }

    // ---------- classloader 契约 ----------

    @Test
    public void 回调类不可见_整类不织入() throws Exception {
        byte[] original = classBytes("VendorStmt");
        // platform loader：JDK 可见、agent 类不可见——§4.2 #6 契约
        ClassLoader hidden = new java.net.URLClassLoader(new java.net.URL[0],
                ClassLoader.getPlatformClassLoader());
        byte[] woven = StatementWeaver.weaveSpec(STATEMENT_FACE, hidden, original);
        assertSame("回调不可见时原字节直返（安全降级）", original, woven);
    }

    // ---------- 辅助 ----------

    private Object newInstance(String simpleName) throws Exception {
        return Class.forName(FIXTURE_PREFIX + "$" + simpleName, true, loader)
                .getDeclaredConstructor().newInstance();
    }

    private int invokeInt(Object target, String method, String arg) throws Exception {
        return (Integer) target.getClass().getMethod(method, String.class).invoke(target, arg);
    }

    /** 调无参 executeUpdate()（PS 面：登记表形态）。 */
    private int invokeInt3(Object target) throws Exception {
        return (Integer) target.getClass().getMethod("executeUpdate").invoke(target);
    }

    private void invokeVoid(Object target, String method, String arg) throws Exception {
        target.getClass().getMethod(method, String.class).invoke(target, arg);
    }

    /** 三个夹具类按各自 spec 织入后落盘，其余同名前缀兄弟类原样复制（子优先 loader 全量可见）。 */
    private void writeWoven(String simpleName, StatementWeaver.Kind[] spec) throws Exception {
        final String pkgPath = "cn/song/mybatisplus/sqlvis/agent";
        byte[] woven = StatementWeaver.weaveSpec(spec, ClassLoader.getSystemClassLoader(),
                classBytes(simpleName));
        File cls = new File(dir, pkgPath + "/FakeVendorFixtures$" + simpleName + ".class");
        cls.getParentFile().mkdirs();
        Files.write(cls.toPath(), woven);
        // 兄弟类只复制一次（首个调用方顺带补齐）
        File[] existing = dir.listFiles();
        boolean copied = false;
        for (File f : existing) {
            if (f.getName().startsWith("FakeVendorFixtures$") && !f.getName().endsWith(simpleName + ".class")) {
                copied = true;
            }
        }
        if (!copied) {
            File srcDir = new File(FakeVendorFixtures.class.getResource("FakeVendorFixtures.class").toURI()).getParentFile();
            for (File f : srcDir.listFiles()) {
                final String name = f.getName();
                if (name.startsWith("FakeVendorFixtures") && name.endsWith(".class")
                        && !name.equals("FakeVendorFixtures.class")
                        && !new File(dir, pkgPath + "/" + name).exists()) {
                    Files.copy(f.toPath(), new File(dir, pkgPath + "/" + name).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private byte[] classBytes(String simpleName) throws Exception {
        String path = FIXTURE_PREFIX.replace('.', '/') + "$" + simpleName + ".class";
        try (java.io.InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertNotNull("夹具编译产物缺失：" + path, in);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** 子优先 loader：FakeVendorFixtures* 只从织入目录加载；回调类照常父委派。 */
    private ClassLoader childFirstLoader() throws java.net.MalformedURLException {
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

    @SuppressWarnings("unchecked")
    private static BlockingQueue<SqlEvent> queue() throws Exception {
        Field field = cn.song.mybatisplus.sqlvis.agent.transport.EventBus.class.getDeclaredField("QUEUE");
        field.setAccessible(true);
        return (BlockingQueue<SqlEvent>) field.get(null);
    }
}
