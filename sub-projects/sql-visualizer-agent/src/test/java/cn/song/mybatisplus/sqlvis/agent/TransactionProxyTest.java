package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks;
import cn.song.mybatisplus.sqlvis.agent.transport.EventBus;
import cn.song.mybatisplus.sqlvis.agent.transport.TraceBus;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import org.junit.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import static org.junit.Assert.*;

/** Real H2 Connection/Statement under the production proxy; independent DB results prove outcomes. */
public class TransactionProxyTest {
    private boolean disabled;
    private Connection raw, connection;
    private java.net.URLClassLoader driverLoader;
    @Before public void setup() throws Exception {
        disabled = AgentState.isDisabled(); AgentState.setDisabled(false); TraceBus.disconnected();
        TraceBus.configure(false, true, "abcd", true); EventBus.clearQueued();
        // 本类驱动的是连接代理通道（出厂默认 wrapConnection=false，采集走织入面）——
        // 必须自足开启 seam，不依赖其他测试类遗留的开关态（此前 8/8 预存红即因缺此步）。
        SqlHookCallbacks.setWrapConnectionForTest(Boolean.TRUE);
        // Keep real-driver execution out of the test application's loader: the existing
        // zero-load weaving guard intentionally asserts H2 is not preloaded there.
        java.net.URL resource = getClass().getClassLoader().getResource("org/h2/Driver.class");
        assertNotNull(resource);
        java.net.URL jar = ((java.net.JarURLConnection) resource.openConnection()).getJarFileURL();
        driverLoader = new java.net.URLClassLoader(new java.net.URL[]{jar}, ClassLoader.getPlatformClassLoader());
        Driver driver = (Driver) driverLoader.loadClass("org.h2.Driver").getDeclaredConstructor().newInstance();
        raw = driver.connect("jdbc:h2:mem:tx" + System.nanoTime(), new Properties());
        raw.createStatement().execute("create table t(id int primary key)");
        connection = (Connection) SqlHookCallbacks.onConnection(raw, "jdbc:h2:mem:tx");
    }
    @After public void cleanup() throws Exception {
        TraceBus.disconnected();
        SqlHookCallbacks.setWrapConnectionForTest(null); // seam 复位出厂默认，不污染后续测试类
        if (raw != null) raw.close();
        if (driverLoader != null) driverLoader.close();
        EventBus.clearQueued(); AgentState.setDisabled(disabled);
    }
    @Test public void commitIsObservedAtBoundaryNotAtSqlSuccess() throws Exception {
        connection.setAutoCommit(false); connection.createStatement().executeUpdate("insert into t values(1)");
        List<String> before = ServletRequestWeaveTest.drain();
        assertFalse(before.toString().contains("COMMITTED"));
        SqlEvent sql = sql(); assertNotNull(sql.connectionId); assertNotNull(sql.leaseId); assertNotNull(sql.transactionId);
        connection.commit();
        String event = only("COMMIT"); assertTrue(event.contains("COMMITTED")); assertTrue(event.contains(sql.transactionId));
        assertEquals(1, count());
        connection.createStatement().executeUpdate("insert into t values(2)");
        SqlEvent next = sql(); assertNotEquals(sql.transactionId, next.transactionId);
    }
    @Test public void rollbackAndSavepointsAreDistinctFromWholeCommit() throws Exception {
        connection.setAutoCommit(false); connection.createStatement().executeUpdate("insert into t values(1)");
        Savepoint point = connection.setSavepoint("private-customer-label");
        connection.createStatement().executeUpdate("insert into t values(2)");
        connection.rollback(point); assertEquals(1, count());
        connection.releaseSavepoint(point); connection.rollback(); assertEquals(0, count());
        String events = ServletRequestWeaveTest.drain().toString();
        assertTrue(events.contains("ROLLBACK_TO_SAVEPOINT")); assertTrue(events.contains("SAVEPOINT_ROLLED_BACK"));
        assertTrue(events.contains("RELEASE_SAVEPOINT")); assertTrue(events.contains("ROLLED_BACK"));
        assertFalse(events.contains("private-customer-label")); assertFalse(events.contains("COMMITTED"));
    }
    @Test public void autoCommitTransitionCanCommitButCloseNeverClaimsIt() throws Exception {
        connection.setAutoCommit(false); connection.prepareStatement("insert into t values(1)").executeUpdate();
        connection.setAutoCommit(true); assertEquals(1, count());
        String events = ServletRequestWeaveTest.drain().toString(); assertTrue(events.contains("COMMITTED"));
        connection.setAutoCommit(false); connection.createStatement().executeUpdate("insert into t values(2)");
        ServletRequestWeaveTest.drain(); connection.close();
        String close = only("CLOSE"); assertTrue(close.contains("\"outcome\":\"UNKNOWN\""));
        assertFalse(close.contains("COMMITTED"));
    }
    @Test public void failedCommitPreservesOriginalExceptionAndInvalidatesOutcome() throws Exception {
        SQLException original = new SQLException("injected commit", "08006");
        Connection failingRaw = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (p,m,a) -> {
            if ("commit".equals(m.getName())) throw original;
            try { return m.invoke(raw, a); } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        Connection failing = (Connection) SqlHookCallbacks.onConnection(failingRaw, null);
        failing.setAutoCommit(false); failing.createStatement().executeUpdate("insert into t values(1)");
        ServletRequestWeaveTest.drain();
        try { failing.commit(); fail(); } catch (SQLException actual) { assertSame(original, actual); }
        String event = only("COMMIT"); assertTrue(event.contains("\"status\":\"ERROR\""));
        assertTrue(event.contains("08006")); assertTrue(event.contains("\"outcome\":\"UNKNOWN\""));
        failing.createStatement().executeQuery("select 1");
        sql(); assertNull(sql().transactionId);
    }
    @Test public void disabledDefaultAndUnknownInitialStateDoNotInventTransactions() throws Exception {
        TraceBus.disconnected(); connection.setAutoCommit(false);
        connection.createStatement().executeUpdate("insert into t values(1)"); connection.commit();
        assertTrue(ServletRequestWeaveTest.drain().isEmpty()); assertNull(sql().transactionId);
        TraceBus.configure(false, true, "abcd", true);
        connection.createStatement().executeUpdate("insert into t values(2)");
        assertNull(sql().transactionId); connection.commit();
        assertTrue(only("COMMIT").contains("\"outcome\":\"UNKNOWN\""));
    }
    @Test public void nonceAndStopReopenInvalidatePriorTransactionEvidence() throws Exception {
        connection.setAutoCommit(false); connection.createStatement().executeUpdate("insert into t values(1)");
        assertNotNull(sql().transactionId);
        TraceBus.configure(false, true, "ffff", true);
        connection.commit(); assertTrue(only("COMMIT").contains("\"outcome\":\"UNKNOWN\""));
        connection.setAutoCommit(false); connection.createStatement().executeUpdate("insert into t values(2)");
        sql(); ServletRequestWeaveTest.drain();
        AgentState.setDisabled(true); AgentState.setDisabled(false);
        connection.rollback(); assertTrue(only("ROLLBACK").contains("\"outcome\":\"UNKNOWN\""));
    }

    @Test public void failedRollbackAndSetAutoCommitNeverClaimSuccess() throws Exception {
        for (String target : new String[]{"rollback", "setAutoCommit", "close"}) {
            SQLException original = new SQLException("injected " + target, "08006");
            Connection failingRaw = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (p,m,a) -> {
                if (target.equals(m.getName())) throw original;
                try { return m.invoke(raw, a); } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            Connection failing = (Connection) SqlHookCallbacks.onConnection(failingRaw, null);
            try {
                if ("rollback".equals(target)) failing.rollback();
                else if ("close".equals(target)) failing.close();
                else failing.setAutoCommit(false);
                fail();
            } catch (SQLException actual) { assertSame(original, actual); }
            String events = ServletRequestWeaveTest.drain().toString();
            assertTrue(events.contains("\"status\":\"ERROR\""));
            assertFalse(events.contains("COMMITTED"));
        }
    }

    @Test public void separateObservedWrappersHaveSeparateLeaseIdentity() throws Exception {
        connection.createStatement().executeQuery("select 1"); SqlEvent first = sql();
        Connection another = (Connection) SqlHookCallbacks.onConnection(raw, null);
        another.createStatement().executeQuery("select 1"); SqlEvent second = sql();
        assertNotEquals(first.connectionId, second.connectionId); assertNotEquals(first.leaseId, second.leaseId);
        assertNull(first.transactionId); assertNull(second.transactionId);
    }
    private int count() throws SQLException {
        try (Statement statement = raw.createStatement(); ResultSet rows = statement.executeQuery("select count(*) from t")) {
            rows.next(); return rows.getInt(1);
        }
    }
    private static String only(String kind) throws Exception {
        List<String> rows = ServletRequestWeaveTest.drain(); assertEquals(rows.toString(), 1, rows.size());
        assertTrue(rows.get(0), rows.get(0).contains("\"kind\":\"" + kind + "\"")); return rows.get(0);
    }
    @SuppressWarnings("unchecked") private static SqlEvent sql() throws Exception {
        Field field = EventBus.class.getDeclaredField("QUEUE"); field.setAccessible(true);
        SqlEvent event = ((BlockingQueue<SqlEvent>) field.get(null)).poll(); assertNotNull(event); return event;
    }
}
