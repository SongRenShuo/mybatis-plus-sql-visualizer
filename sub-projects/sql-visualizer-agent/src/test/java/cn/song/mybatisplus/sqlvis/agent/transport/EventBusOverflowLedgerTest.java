package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-SqlListCatchup 券 I：溢出聚合账本（被逐事件计数保全）行为守卫。
 *
 * <p>背景：券 H 排水提速 23 倍后队列很少满，但流量尖峰仍会溢出（真机第七轮 110 条丢弃）——
 * 券 I 前每一条被逐事件的「执行了 N 次」随 DROPPED++ 整体丢失；券 I 起被逐事件先入有界账本
 * （{@code EventBus.OVERFLOW_MAP_CAPACITY}=512 模板键），发送线程排空队列后补发聚合事件，
 * 真丢失只剩「账本也满收不下新模板」一种。
 *
 * <p>本类钉六件事：①主判据（先红后绿）——队列灌满 + 持续异模板溢出，账本保全 512 模板、
 * DROPPED 只剩账本满的真丢失；②同模板在账本内永远合并（含协议键 c 字面）；③账本满反向
 * ——超限新模板计真丢失（语义收窄的正确性）、出账后容量即刻回收；④offer 入账路径红线
 * 静态断言（无 sleep/无锁等待/无 park）；⑤停采下降沿账本清零入 CLEARED（停采后不补投）；
 * ⑥SenderLoop 集成——账本条目经发送循环全部发出且聚合 count 正确。
 */
public class EventBusOverflowLedgerTest {

    /** 与 EventBus.QUEUE_CAPACITY 同值（改容量须同步本类：填满断言依赖确切值）。 */
    private static final int CAPACITY = 2048;

    /** 与 EventBus.OVERFLOW_MAP_CAPACITY 同值（改容量须同步本类）。 */
    private static final int LEDGER_CAPACITY = 512;

    /** SenderLoop 集成用例的看门狗窗口（60s：用例窗口内不可能触发）。 */
    private static final long WATCHDOG_MS_NEVER = 60_000L;

    @Before
    public void setUp() {
        EventBus.clearQueued(); // 归零起点（同 JVM 其它用例可能留队列与账本存量）
        AgentState.setDisabled(false);
        AgentState.setDesensitize(true);
    }

    @After
    public void tearDown() {
        EventBus.clearQueued();
        AgentState.setDisabled(false);
        AgentState.setDesensitize(true);
    }

    // ---------- 用例 ----------

    /**
     * 主判据（先红后绿）：队列灌满 + 持续异模板溢出 520 条 → 账本保全 512 个模板的计数，
     * DROPPED 仅剩账本满后收不下的 8 条真丢失；出账后账本清空、容量即刻回收。
     * 修复前同场景 DROPPED≈全部 520（红跑读数见计划册券 I 小节）。
     */
    @Test
    public void 队列满持续异模板溢出_账本保全512模板_真丢失仅剩账本满() throws Exception {
        fillDistinct(CAPACITY);
        assertEquals("前置：队列已满", CAPACITY, EventBus.queuedCount());
        final long droppedBefore = EventBus.droppedCount();
        final int overflowCount = LEDGER_CAPACITY + 8;

        for (int i = 0; i < overflowCount; i++) {
            EventBus.offer(ev("ovf-" + i));
        }

        assertEquals("DROPPED 仅剩账本满收不下的真丢失（" + overflowCount + " - " + LEDGER_CAPACITY
                        + " = 8；修复前此读数 ≈ 全部 " + overflowCount + "）",
                droppedBefore + (overflowCount - LEDGER_CAPACITY), EventBus.droppedCount());
        assertEquals("账本模板键数封顶", LEDGER_CAPACITY, EventBus.overflowTemplateCount());
        assertEquals("账本保全的执行次数 = 被逐且入账的事件数", LEDGER_CAPACITY, EventBus.overflowEventTotal());

        // 出账：账本条目全部转成聚合事件，出账后账本清空
        final List<SqlEvent> drained = new ArrayList<>();
        assertEquals("出账帧数 = 账本键数", LEDGER_CAPACITY, EventBus.drainOverflow(drained::add));
        assertEquals("出账后账本清空", 0, EventBus.overflowTemplateCount());
        assertEquals(LEDGER_CAPACITY, drained.size());
        final Set<String> seen = new HashSet<>();
        for (SqlEvent e : drained) {
            assertEquals("每条被逐异模板事件恰保全 1 次执行", 1, e.aggregatedCount);
            seen.add(e.sql);
        }
        for (int i = 0; i < LEDGER_CAPACITY; i++) {
            assertTrue("被逐的最旧模板 tpl-" + i + " 必在账本出账中（先逐者先保全）",
                    seen.contains("tpl-" + i));
        }

        // 容量即刻回收：出账后再溢出的新模板可重新入账
        assertEquals("前置：队列仍满", CAPACITY, EventBus.queuedCount());
        final long droppedAfterDrain = EventBus.droppedCount();
        for (int i = 0; i < 4; i++) {
            EventBus.offer(ev("ovf2-" + i));
        }
        assertEquals("出账释放容量后新模板重新入账（不再计真丢失）",
                droppedAfterDrain, EventBus.droppedCount());
        assertEquals(4, EventBus.overflowTemplateCount());
    }

    /**
     * 同模板优先级 + 聚合计数继承：同一模板被逐出**多次**时账本只保留一个键、次数累加；
     * 被逐事件自带的 {@code aggregatedCount}（队内聚合代表）按 N 次入账，不是按 1 次。
     *
     * <p>为什么这样构造：单生产者下「重复模板」会先被 F2b 第①步的队内聚合留住（重复 offer 只在
     * 队列里合并、不会再次被逐出），故让队列**整队都是同一模板**——此时每条新模板的 offer 都逐出
     * 队头那条同模板事件，10 次逐出走 10 遍「账本同键合并」，键数恒 1、次数按 3 倍累加。
     */
    @Test
    public void 队列满_同模板多次逐出_账本合并计数_协议键c带出() throws Exception {
        // 整队同一模板、每条自带 aggregatedCount=3（模拟风暴期已聚合过的代表事件）
        for (int i = 0; i < CAPACITY; i++) {
            final SqlEvent dup = ev("dup");
            dup.aggregatedCount = 3;
            EventBus.offer(dup);
        }
        assertEquals("前置：队列已满", CAPACITY, EventBus.queuedCount());
        final long droppedBefore = EventBus.droppedCount();

        for (int i = 0; i < 10; i++) {
            EventBus.offer(ev("fresh-" + i)); // 每次逐出队头那条 dup
        }

        assertEquals("入账不产生真丢失", droppedBefore, EventBus.droppedCount());
        assertEquals("10 次逐出同一模板只占 1 个账本键", 1, EventBus.overflowTemplateCount());
        assertEquals("次数按 aggregatedCount 累加（10 × 3 = 30，不是按 1 次记）",
                30L, EventBus.overflowEventTotal());

        final List<SqlEvent> drained = new ArrayList<>();
        assertEquals("账本 1 键出账 1 帧", 1, EventBus.drainOverflow(drained::add));
        assertEquals("dup", drained.get(0).sql);
        assertEquals("同模板 30 次执行合并为一条聚合事件", 30, drained.get(0).aggregatedCount);
        final String json = EventBus.encodeEvent(drained.get(0));
        assertTrue("聚合计数必须经既有协议键 c 带出（协议零改动）: " + json,
                json.contains("\"c\":30"));
    }

    /** 红线静态断言：offer→入账路径源码无 sleep/wait/join/synchronized/park/显式锁（宿主线程绝不阻塞）。 */
    @Test
    public void offer入账路径红线_源码无阻塞原语() throws Exception {
        final String source = readEventBusSource();
        final int from = source.indexOf("public static void offer(");
        final int to = source.indexOf("public static long droppedCount()");
        assertTrue("源码锚点必须命中（offer 起点）", from >= 0);
        assertTrue("源码锚点必须命中（droppedCount 终点，覆盖逐旧/入账/出账全部路径）", to > from);
        final String offerPath = source.substring(from, to);
        final String[] forbidden = {
            "Thread.sleep", ".wait(", ".join(", "synchronized", "park(", ".yield(",
            "ReentrantLock", "CountDownLatch", "Semaphore", ".acquire(", ".await(",
        };
        for (String token : forbidden) {
            assertFalse("offer 入账路径出现阻塞原语 " + token + "（宿主线程红线）",
                    offerPath.contains(token));
        }
    }

    /** 停采下降沿：clearQueued 同步清账本，账本代表的执行次数并入 CLEARED（停采后不补投）。 */
    @Test
    public void clearQueued_同步清账本_账本次数并入CLEARED() {
        fillDistinct(CAPACITY);
        for (int i = 0; i < 10; i++) {
            EventBus.offer(ev("pend-" + i)); // 队列满 → 逐旧入账本
        }
        assertEquals("前置：账本有 10 键", 10, EventBus.overflowTemplateCount());
        assertEquals("前置：账本保 10 次执行", 10L, EventBus.overflowEventTotal());

        final long clearedBefore = EventBus.clearedCount();
        final int cleared = EventBus.clearQueued();

        assertEquals("清空条数 = 队列 2048 + 账本代表执行次数 10", CAPACITY + 10, cleared);
        assertEquals("账本清零", 0, EventBus.overflowTemplateCount());
        assertEquals("CLEARED 累计并入账本次数（停采后不补投语义不回破）",
                clearedBefore + CAPACITY + 10, EventBus.clearedCount());
        final List<SqlEvent> drained = new ArrayList<>();
        final int flushed = noThrowDrain(drained);
        assertEquals("停采后账本无残余可出账", 0, flushed);
    }

    /**
     * SenderLoop 集成：队头放一条 aggregatedCount=7 的聚合代表 + 2047 条异模板（队列 2048 灌满），
     * 再 600 条异模板溢出（账本 512 键 + 88 真丢失）→ 发送循环排空队列后把账本 512 条全部发出：
     * 聚合代表经账本按 c:7 带出（count 正确），被逐异模板按 1 次各一帧。
     */
    @Test
    public void senderLoop_排空队列后账本全部出账_聚合count正确() throws Exception {
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, String.valueOf(WATCHDOG_MS_NEVER));
        final SqlEvent multi = ev("multi");
        multi.aggregatedCount = 7;
        EventBus.offer(multi);
        fillDistinct(CAPACITY - 1); // 队头 = multi(7)，其后 tpl-0..tpl-2046
        assertEquals("前置：队列已满", CAPACITY, EventBus.queuedCount());
        final long droppedBefore = EventBus.droppedCount();
        final int overflowCount = 600;
        for (int i = 0; i < overflowCount; i++) {
            EventBus.offer(ev("ovf-" + i));
        }
        assertEquals("溢出真丢失 = 600 - 512", droppedBefore + (overflowCount - LEDGER_CAPACITY),
                EventBus.droppedCount());
        assertEquals("账本键数封顶", LEDGER_CAPACITY, EventBus.overflowTemplateCount());
        assertEquals("账本保全次数 = multi 7 次 + 被逐异模板 511 次",
                7L + (LEDGER_CAPACITY - 1), EventBus.overflowEventTotal());

        final StubSocket stub = new StubSocket();
        final RunHandle run = startLoop(stub);
        try {
            final long deadline = System.currentTimeMillis() + 15_000L;
            while ((EventBus.queuedCount() > 0 || EventBus.overflowTemplateCount() > 0)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertEquals("队列须被排空", 0, EventBus.queuedCount());
            assertEquals("账本须被发送循环全部出账", 0, EventBus.overflowTemplateCount());

            final List<Frame> frames = stub.outbound.snapshotFrames();
            int eventFrames = 0;
            final java.util.Map<String, Integer> perSql = new java.util.HashMap<>();
            String multiPayload = null;
            for (Frame f : frames) {
                if (f.cmd != EventBus.CMD_EVENT) {
                    continue;
                }
                eventFrames++;
                final String sql = extractSql(f.payload);
                perSql.merge(sql, 1, Integer::sum);
                if ("multi".equals(sql)) {
                    assertNull("聚合代表只允许一帧（账本同键合并）", multiPayload);
                    multiPayload = f.payload;
                }
            }
            assertEquals("事件帧总数 = 队列 2048 + 账本出账 512", CAPACITY + LEDGER_CAPACITY, eventFrames);
            assertNotNull("账本里的聚合代表必须被发出", multiPayload);
            assertTrue("聚合代表按继承的 7 次带出协议键 c: " + multiPayload,
                    multiPayload.contains("\"c\":7"));
            for (int i = 0; i < LEDGER_CAPACITY - 1; i++) {
                assertEquals("被逐模板 tpl-" + i + " 经账本补发且恰一帧", Integer.valueOf(1),
                        perSql.get("tpl-" + i));
            }
            for (int i = LEDGER_CAPACITY - 1; i < overflowCount - 1; i++) {
                assertNull("账本满后未保全的 tpl-" + i + " 不应出现（真丢失）",
                        perSql.get("tpl-" + i));
            }
            for (int i = 0; i < overflowCount; i++) {
                assertEquals("溢出新模板 ovf-" + i + " 经队列恰一帧", Integer.valueOf(1),
                        perSql.get("ovf-" + i));
            }
            for (int i = overflowCount - 1; i < CAPACITY - 1; i++) {
                assertEquals("队列内 tpl-" + i + " 恰一帧", Integer.valueOf(1), perSql.get("tpl-" + i));
            }
        } finally {
            run.stop();
        }
    }

    // ---------- 桩与夹具 ----------

    private static int noThrowDrain(List<SqlEvent> sink) {
        try {
            return EventBus.drainOverflow(sink::add);
        } catch (IOException io) {
            throw new AssertionError("drainOverflow 不应抛 IO（测试桩不失败）", io);
        }
    }

    private static SqlEvent ev(String sql) {
        final SqlEvent e = new SqlEvent();
        e.sql = sql;
        e.timestamp = System.currentTimeMillis();
        return e;
    }

    private static void fillDistinct(int n) {
        for (int i = 0; i < n; i++) {
            EventBus.offer(ev("tpl-" + i));
        }
    }

    /** 从事件 JSON 提取 sql 值（测试局部解析，事件体由 Jsons.writeEvent 保证无内嵌引号形态）。 */
    private static String extractSql(String payload) {
        final int k = payload.indexOf("\"sql\":\"");
        final int from = k + "\"sql\":\"".length();
        final int to = payload.indexOf('"', from);
        return payload.substring(from, to);
    }

    /** 读 EventBus 生产源码（红线静态断言用）：按运行目录候选路径定位，全部缺失即失败。 */
    private static String readEventBusSource() throws IOException {
        final String relative = "src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/EventBus.java";
        final Path cwd = Paths.get("").toAbsolutePath();
        final Path[] candidates = {
            cwd.resolve(relative),
            cwd.resolve("sub-projects/sql-visualizer-agent").resolve(relative),
            cwd.getParent() == null ? null : cwd.getParent().resolve(relative),
            cwd.getParent() == null ? null
                    : cwd.getParent().resolve("sql-visualizer-agent").resolve(relative),
        };
        for (Path p : candidates) {
            if (p != null && Files.exists(p)) {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("EventBus.java 源码未定位到（红线静态断言需读源码），cwd=" + cwd);
    }

    /** 桩入站流：恒空、读即超时（agent→IDE 单向流场景，入站静默是常态）。 */
    private static final class EmptyInbound extends InputStream {
        @Override
        public int read() throws IOException {
            throw new SocketTimeoutException("stub inbound idle");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            throw new SocketTimeoutException("stub inbound idle");
        }

        @Override
        public int available() {
            return 0;
        }
    }

    /** 桩出站流：逐字节喂帧解析器（单写者 = loop 线程），统计 EVENT 帧。 */
    private static final class CountingOutbound extends OutputStream {
        private final List<Frame> frames = new ArrayList<>();
        private int lenAcc = 0;
        private int lenBytes = 0;
        private int cmd = 0;
        private int remaining = 0;
        private byte[] bodyBuf = new byte[0];
        private int bodyPos = 0;

        private void parse(byte b) {
            if (lenBytes < 4) {
                lenAcc = (lenAcc << 8) | (b & 0xFF);
                lenBytes++;
                if (lenBytes == 4) {
                    bodyBuf = new byte[lenAcc];
                    bodyPos = 0;
                }
                return;
            }
            if (remaining == 0) {
                cmd = b & 0xFF;
                remaining = lenAcc;
                if (remaining == 0) {
                    completeFrame();
                }
                return;
            }
            bodyBuf[bodyPos++] = b;
            remaining--;
            if (remaining == 0) {
                completeFrame();
            }
        }

        private void completeFrame() {
            frames.add(new Frame(cmd,
                    new String(bodyBuf, StandardCharsets.UTF_8)));
            lenAcc = 0;
            lenBytes = 0;
            remaining = 0;
        }

        @Override
        public void write(int b) {
            synchronized (this) {
                parse((byte) b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            synchronized (this) {
                for (int i = 0; i < len; i++) {
                    parse(b[off + i]);
                }
            }
        }

        synchronized int eventCount() {
            int n = 0;
            for (Frame f : frames) {
                if (f.cmd == EventBus.CMD_EVENT) {
                    n++;
                }
            }
            return n;
        }

        synchronized List<Frame> snapshotFrames() {
            return new ArrayList<>(frames);
        }
    }

    /** 桩 Socket：只替换两股流与连接期设置；不真拨号、不真 IO。 */
    private static final class StubSocket extends Socket {
        final CountingOutbound outbound = new CountingOutbound();

        @Override
        public InputStream getInputStream() {
            return new EmptyInbound();
        }

        @Override
        public OutputStream getOutputStream() {
            return outbound;
        }

        @Override
        public void setSoTimeout(int timeout) {
            // 桩不实现 SO_TIMEOUT：读阻塞行为由 EmptyInbound 定
        }

        @Override
        public void setTcpNoDelay(boolean on) {
            // 桩无 TCP 语义
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {
            // 测试结束靠 stopped 标志 + 中断退出；桩关闭无副作用
        }
    }

    private static final class Frame {
        final int cmd;
        final String payload;

        Frame(int cmd, String payload) {
            this.cmd = cmd;
            this.payload = payload;
        }
    }

    /**
     * 运行句柄：确定性停止（SenderLoopDrainRateTest 同款口径——不能只 interrupt：
     * 退避睡眠缝是记录器，中断→退避→立即返回→僵尸重连；停止 = stopped 标志 → 拨号缝拒绝 +
     * 退避缝抛 InterruptedException，线程确定性退出）。
     */
    private static final class RunHandle {
        final AtomicBoolean stopped = new AtomicBoolean(false);
        Thread loop;

        void stop() {
            stopped.set(true);
            loop.interrupt();
            try {
                loop.join(2000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            assertTrue("loop 线程必须确定性退出（僵尸连接会污染后续用例的全局状态）",
                    !loop.isAlive());
        }
    }

    private RunHandle startLoop(StubSocket stub) {
        final RunHandle handle = new RunHandle();
        final SenderLoop.SocketDialer dialer = (host, port) -> {
            if (handle.stopped.get()) {
                throw new IOException("run stopped");
            }
            return stub;
        };
        final SenderLoop.BackoffSleeper sleeper = ms -> {
            if (handle.stopped.get()) {
                throw new InterruptedException("run stopped");
            }
            Thread.sleep(Math.min(ms, 50L));
        };
        final Thread loop = new Thread(
                new SenderLoop(1, 1_000L, 5_000L, 2_000L, 10, dialer, sleeper),
                "overflowledger-senderloop");
        loop.setDaemon(true);
        handle.loop = loop;
        handle.loop.start();
        return handle;
    }
}
