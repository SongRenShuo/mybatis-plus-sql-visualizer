package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.util.Jsons;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.*;
import java.util.Collections;
import java.util.regex.*;
import static org.junit.Assert.*;

public class SqlInsightQualityTest {
    private boolean disabled;
    @Before public void before() { disabled = AgentState.isDisabled(); AgentState.setDisabled(false); EventBus.clearQueued(); }
    @After public void after() { EventBus.clearQueued(); AgentState.setDisabled(disabled); }
    private static long count(String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(\\d+)").matcher(CaptureLedger.snapshot());
        assertTrue(key, m.find());
        return Long.parseLong(m.group(1));
    }
    private static SqlEvent event() { SqlEvent e = new SqlEvent(); e.sql = "select 1"; return e; }

    @Test public void unknownPreparedSqlIsOmittedNotZeroExecutions() {
        long generated = count("generated"), omitted = count("omitted"), completed = count("completed");
        Object raw = new Object();
        long token = SqlHookCallbacks.onStatementEnter(raw);
        SqlHookCallbacks.onExecuteExit(raw, null, "executeQuery", token, new Object(), null);
        assertEquals(generated + 1, count("generated"));
        assertEquals(omitted + 1, count("omitted"));
        assertEquals(completed + 1, count("completed"));
        assertEquals(0, EventBus.queuedCount());
    }

    @Test public void disabledDoesNotAllocateAndActivePreventsTerminal() {
        Object raw = new Object();
        long token = SqlHookCallbacks.onStatementEnter(raw);
        long generated = count("generated");
        AgentState.setDisabled(true);
        assertEquals(0, SqlHookCallbacks.onStatementEnter(new Object()));
        assertEquals(generated, count("generated"));
        assertTrue(CaptureLedger.snapshot().contains("\"terminal\":false"));
        SqlHookCallbacks.onExecuteExit(raw, "select 1", "executeQuery", token, new Object(), null);
        assertTrue(CaptureLedger.snapshot().contains("\"terminal\":true"));
        assertTrue(CaptureLedger.snapshot().contains("\"coverage\":\"INSTRUMENTED_JDBC_ONLY\""));
        AgentState.setDisabled(false);
        assertTrue(CaptureLedger.snapshot().contains("\"terminal\":false"));
    }

    @Test public void concurrentStopMustWaitForInFlightExecutionEvidence() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            Object raw = new Object();
            long token = SqlHookCallbacks.onStatementEnter(raw);
            entered.countDown();
            try { assertTrue(finish.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (Throwable t) { failure.set(t); }
            finally { SqlHookCallbacks.onExecuteExit(raw, "select 1", "executeQuery", token, new Object(), null); }
        });
        worker.start();
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            AgentState.setDisabled(true);
            assertTrue(count("active") > 0);
            assertTrue(CaptureLedger.snapshot().contains("\"terminal\":false"));
        } finally { finish.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertTrue(CaptureLedger.snapshot().contains("\"terminal\":true"));
    }

    @Test public void staleEpochCannotRefillAfterStopAndReopen() {
        Object raw = new Object();
        long token = SqlHookCallbacks.onStatementEnter(raw);
        long cleared = count("cleared");
        AgentState.setDisabled(true);
        AgentState.setDisabled(false);
        SqlHookCallbacks.onExecuteExit(raw, "select 1", "executeQuery", token, new Object(), null);
        assertEquals(cleared + 1, count("cleared"));
        assertEquals(0, EventBus.queuedCount());
    }

    @Test public void queueOverflowCannotHideBehindContinuousSequenceOrFullQueue() {
        // t-sql-insight 合并适配（dev T-SqlListCatchup 券 I）：满队列逐出的事件**先入溢出账本**
        // （模板与次数保全，drainOverflow 排空后补发聚合事件），droppedCount 语义收窄为
        // 「连账本都收不下的真丢失」。故灌 2049 条只应：队列满 2048 + 溢出账本 +1、dropped 不变——
        // 「一次溢出即 dropped+1」是账本机制之前的旧契约。
        long dropped = count("queueDropped");
        for (int i = 0; i < 2049; i++) EventBus.offer(event());
        assertEquals("第 2049 条不得计真丢失（同模板走 F2 聚合保全，异模板走券 I 溢出账本——"
                        + "账本路径由 EventBusOverflowLedgerTest 守卫）",
                dropped, count("queueDropped"));
        assertEquals("队列应恰好满", 2048, EventBus.queuedCount());
        // Control state is available without inserting anything into the full queue.
        assertTrue(CaptureLedger.snapshot().contains("\"terminal\":false"));
    }

    @Test public void encoderFailureAndOversizedFramesAreExplicitlyCounted() throws Exception {
        long before = count("encodingFailed");
        SqlEvent malformed = event();
        malformed.params = Collections.singletonList(new String[0]);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertFalse(SenderLoop.sendEvent(new DataOutputStream(bytes), malformed));
        SqlEvent oversized = event();
        char[] chars = new char[SenderLoop.MAX_FRAME_LEN + 1];
        java.util.Arrays.fill(chars, 'x');
        oversized.sql = new String(chars);
        assertFalse(SenderLoop.sendEvent(new DataOutputStream(bytes), oversized));
        assertEquals(before + 2, count("encodingFailed"));
        assertEquals(0, bytes.size());
    }

    @Test public void partialSocketWriteIsUncertainNotDelivered() throws Exception {
        long before = count("transportUncertain"), written = count("written");
        OutputStream failing = new OutputStream() {
            int bytes;
            @Override public void write(int value) throws IOException { if (++bytes > 7) throw new IOException("injected"); }
        };
        try { SenderLoop.sendEvent(new DataOutputStream(failing), event()); fail(); }
        catch (IOException expected) { assertEquals("injected", expected.getMessage()); }
        assertEquals(before + 1, count("transportUncertain"));
        assertEquals(written, count("written"));
    }

    @Test public void optionalWireFactsPreserveLegacyKeysAndNanoseconds() {
        SqlEvent old = event();
        String legacy = Jsons.writeEvent(old);
        assertFalse(legacy.contains("executionId"));
        old.runId = CaptureLedger.RUN_ID; old.executionId = "test:1"; old.sequence = 1L;
        old.threadId = 42L; old.startNanos = -987654321L; old.durationNanos = 1234567L;
        old.resultStatus = "UNKNOWN";
        String modern = Jsons.writeEvent(old);
        assertTrue(modern.contains("\"dn\":1234567"));
        assertTrue(modern.contains("\"startNanos\":-987654321"));
        assertTrue(modern.contains("\"sql\":\"select 1\""));
        assertTrue(modern.contains("\"resultStatus\":\"UNKNOWN\""));
    }
}
