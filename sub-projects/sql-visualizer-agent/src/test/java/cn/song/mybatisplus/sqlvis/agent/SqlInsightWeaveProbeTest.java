package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.transport.EventBus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.BlockingQueue;

import static org.junit.Assert.*;

/** Real class bytes are woven and verified by the JVM; no mocked callback assertions. */
public class SqlInsightWeaveProbeTest {
    public interface ExecuteApi { int executeUpdate(String sql); }
    public static class StatementFixture implements ExecuteApi {
        public StatementFixture nested;
        public int executeUpdate(String sql) {
            if ("delegate".equals(sql)) return executeLargeUpdate(sql) == 7L ? 7 : 0;
            if ("nested".equals(sql)) return nested.executeUpdate("inner");
            return 7;
        }
        public long executeLargeUpdate(String sql) { return 7L; }
        public boolean execute(String sql) { return false; }
        public int getUpdateCount() { return 9; }
        public Object executeQuery(String sql) {
            if ("implicit".equals(sql)) return fail();
            if ("caught".equals(sql)) {
                try { throw new IllegalArgumentException("caught internally"); }
                catch (IllegalArgumentException ignored) { return new Object(); }
            }
            throw new IllegalStateException("explicit");
        }
        private Object fail() { throw new IllegalStateException("implicit"); }
    }

    private boolean disabled;
    private boolean gateSaved;
    private Object statement;

    @Before public void setUp() throws Exception {
        disabled = AgentState.isDisabled();
        AgentState.setDisabled(false);
        // T-BranchAlignMerge 2.7（2026-11-17）：主线（dev/master）并入口径＝NestGate 默认开。
        // 本类守卫的是洞察执行身份模型（异对象嵌套＝两次独立执行），该模型与「外层一律抑制」
        // 在门开启时正面相撞（NestGate 类 KDoc 存档的 2026-10-30 裁决）——主线下该形态的外层帧
        // 会被抑制，属已登记的运行时差异。本类全部用例按**回退开关形态**（nestGate=false）跑，
        // 即守卫「关闭去重门时每个已织 execute 各自产事件」的洞察契约；生产遇洞察身份丢失
        // 置 -Dsqlvis.agent.nestGate=false 即回到本类钉住的形态。
        gateSaved = nestGateEnabled(false);
        queue().clear();
        String name = StatementFixture.class.getName();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) >= 0) bytes.write(buffer, 0, n);
        }
        final byte[] woven = StatementWeaver.weaveSpec(
                new StatementWeaver.Kind[]{StatementWeaver.Kind.EXEC_PARAM},
                getClass().getClassLoader(), bytes.toByteArray());
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(name, woven, 0, woven.length); }
        };
        java.lang.reflect.Method define = loader.getClass().getDeclaredMethod("define");
        define.setAccessible(true);
        statement = ((Class<?>) define.invoke(loader)).getDeclaredConstructor().newInstance();
    }

    @After public void tearDown() throws Exception {
        queue().clear();
        nestGateRestore(gateSaved);
        AgentState.setDisabled(disabled);
    }

    /** NestGate 在 hook 包且 package-private，走反射桥（同 NestGateTest 先例）。 */
    private static boolean nestGateEnabled(boolean value) throws Exception {
        final Class<?> gate = Class.forName("cn.song.mybatisplus.sqlvis.agent.hook.NestGate");
        final Field cur = gate.getDeclaredField("enabled");
        cur.setAccessible(true);
        final boolean saved = cur.getBoolean(null);
        final java.lang.reflect.Method set = gate.getDeclaredMethod("setEnabledForTest", boolean.class);
        set.setAccessible(true);
        set.invoke(null, value);
        return saved;
    }

    private static void nestGateRestore(boolean value) throws Exception {
        final Class<?> gate = Class.forName("cn.song.mybatisplus.sqlvis.agent.hook.NestGate");
        final java.lang.reflect.Method set = gate.getDeclaredMethod("setEnabledForTest", boolean.class);
        set.setAccessible(true);
        set.invoke(null, value);
    }

    @Test public void pureIreturnUpdateIsAlreadyCaptured() throws Exception {
        assertEquals(7, call("executeUpdate", "update t set x=1"));
        SqlEvent event = queue().poll();
        assertNotNull(event);
        assertEquals(Long.valueOf(7), event.updateCount);
        assertNull(queue().poll());
    }

    @Test public void booleanIreturnMustReachJdbcUpdateCount() throws Exception {
        assertEquals(false, call("execute", "update t set x=1"));
        SqlEvent event = queue().poll();
        assertNotNull(event);
        assertEquals(Long.valueOf(9), event.updateCount);
        assertNull(queue().poll());
    }

    @Test public void implicitExceptionMustProduceOneError() throws Exception {
        assertFailure("implicit");
        SqlEvent event = queue().poll();
        assertNotNull("implicit exception must reach the exit hook", event);
        assertTrue(event.errorText.contains("IllegalStateException"));
        assertNull(queue().poll());
    }

    @Test public void internallyCaughtThrowMustNotReportAnExecutionError() throws Exception {
        assertNotNull(call("executeQuery", "caught"));
        SqlEvent event = queue().poll();
        assertNotNull(event);
        assertNull("an ATHROW caught by the driver is not a failed execute", event.errorText);
        assertNull("one invocation must yield one event", queue().poll());
    }

    @Test public void explicitExceptionIsRethrownUnchanged() throws Exception {
        assertFailure("explicit");
        SqlEvent event = queue().poll();
        assertNotNull(event);
        assertTrue(event.errorText.contains("IllegalStateException"));
        assertNull(queue().poll());
    }

    @Test public void sameObjectDelegationIsOneExecution() throws Exception {
        assertEquals(7, call("executeUpdate", "delegate"));
        SqlEvent event = queue().poll();
        assertNotNull(event.executionId);
        assertEquals("SUCCESS", event.resultStatus);
        assertEquals("KNOWN", event.updateCountStatus);
        assertNotNull(event.startNanos);
        assertEquals("ENTRY_WALL_MILLIS", event.timestampSource);
        assertNull(queue().poll());
    }

    @Test public void nestedDifferentStatementRemainsDistinctAndCanArriveOutOfOrder() throws Exception {
        Object inner = statement.getClass().getDeclaredConstructor().newInstance();
        statement.getClass().getField("nested").set(statement, inner);
        call("executeUpdate", "nested");
        SqlEvent first = queue().poll(), second = queue().poll();
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.executionId, second.executionId);
        assertTrue("entry order is not completion order", first.sequence > second.sequence);
        assertNull(queue().poll());
    }

    @Test public void actualProxyOverWovenStatementDoesNotDoubleReport() throws Exception {
        Class<?> support = Class.forName("cn.song.mybatisplus.sqlvis.agent.hook.ProxySupport");
        java.lang.reflect.Method wrap = support.getDeclaredMethod("wrapStatement", Object.class, String.class, String.class);
        wrap.setAccessible(true);
        ExecuteApi proxy = (ExecuteApi) wrap.invoke(null, statement, null, null);
        assertEquals(7, proxy.executeUpdate("update t set x=1"));
        SqlEvent event = queue().poll();
        assertNotNull(event);
        assertEquals("PROXY", event.captureSource);
        assertNotNull(event.executionId);
        assertNull(queue().poll());
    }

    @Test public void sameSqlOneHundredTimesHasOneHundredIdentities() throws Exception {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (int i = 0; i < 100; i++) {
            call("executeUpdate", "update t set x=1");
            SqlEvent event = queue().poll();
            assertNotNull(event);
            ids.add(event.executionId);
        }
        assertEquals(100, ids.size());
        assertNull(queue().poll());
    }

    private void assertFailure(String sql) throws Exception {
        try { call("executeQuery", sql); fail("expected fixture exception"); }
        catch (InvocationTargetException expected) {
            assertEquals(IllegalStateException.class, expected.getCause().getClass());
            assertEquals(sql, expected.getCause().getMessage());
        }
    }

    private Object call(String method, String sql) throws Exception {
        return statement.getClass().getMethod(method, String.class).invoke(statement, sql);
    }

    @SuppressWarnings("unchecked") private static BlockingQueue<SqlEvent> queue() throws Exception {
        Field field = EventBus.class.getDeclaredField("QUEUE");
        field.setAccessible(true);
        return (BlockingQueue<SqlEvent>) field.get(null);
    }
}
