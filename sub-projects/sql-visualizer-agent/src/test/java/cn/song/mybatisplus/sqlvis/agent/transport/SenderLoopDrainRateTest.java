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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-SqlListCatchup 券 H：突发排水期的「入站读超时税」测试。
 *
 * <p>缺陷现场（主控真机六轮压测定位）：{@code SenderLoop} 主循环每轮先尽出事件（每次最多 64 条，
 * {@code burst} 达上限即停），随后做**带 SO_TIMEOUT 的阻塞读**（默认 1s）。agent→IDE 方向平时
 * 无入站流量（CONFIG 仅会话初期一次，之后静默是常态）→ 每排满 64 条就干等一个读超时 →
 * 持续风暴下投递速率被钉死在 ~64 条/秒（真机读数：风暴生产 ~1700 语句/s，到达仅 ~74 条/s，
 * 队列持续满 → F2b 聚合后仍溢出丢弃 → 静默后尾巴按 64/s 慢排，用户反复报「已收事件还在涨」）。
 *
 * <p>判据设计（**不赌墙钟**，L18 同款口径）：把「读超时税」做成**可数事件**——桩入站流每次
 * 阻塞等待超时即计一次税；税的采样点钉在**出站帧写出的那一拍**（桩出站流在写出第 N 条 EVENT
 * 帧的同步点快照税计数）。于是：
 * <ul>
 *   <li>修复态：排满 64 条/轮的每一轮**跳过**阻塞读（available() 探测无数据）→ 排水全程税 = 0
 *       （第 512 条帧写出后的空闲轮读发生在采样点**之外**，不进判据）；</li>
 *   <li>缺陷态：每一轮排满 64 条都付一税 → 第 512 条帧写出时税计数恰为 7（第 1~7 轮各一税，
 *       第 8 轮的税在写出之后）——回退修复分支本用例必红。</li>
 * </ul>
 * 桩读阻塞时长 [READ_BLOCK_MS] 是「真机 SO_TIMEOUT=1s」的等比缩放（真机每轮税 1s、本轮 100ms），
 * 仅用于墙钟读数换算（信息性），判据全部建立在计数上。
 *
 * <p>桩接缝：{@link SenderLoop.SocketDialer}（T-PerfSweep7 T-PS7-02 同款注入缝）+ 桩 Socket 的
 * 流替换——不启真 loopback，无端口/调度竞态；既有 {@code SenderLoopBackoffTest} 用真 socket 的
 * 面不受影响。
 */
public class SenderLoopDrainRateTest {

    /** 预填事件数（8 整批 × 64）：采样点取第 512 条 EVENT 帧写出的同步快照。 */
    private static final int DRAIN_TARGET = 512;

    /** 桩入站读的阻塞等待时长（毫秒）：真机 SO_TIMEOUT=1s 的 1/10 缩放，仅作读数换算。 */
    private static final long READ_BLOCK_MS = 100L;

    /** 排水速率用例的看门狗窗口（60s：用例窗口内不可能触发；触发形态由专项用例覆盖）。 */
    private static final long WATCHDOG_MS_NEVER = 60_000L;

    @Before
    public void setUp() {
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, String.valueOf(WATCHDOG_MS_NEVER));
        AgentState.setDisabled(false);
        AgentState.setDesensitize(true);
        EventBus.clearQueued(); // 归零起点（同 JVM 其它用例可能留存量）
    }

    @After
    public void tearDown() {
        EventBus.clearQueued();
        AgentState.setDisabled(false);
        AgentState.setDesensitize(true);
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, String.valueOf(WATCHDOG_MS_NEVER));
    }

    // ---------- 用例 ----------

    /**
     * 主判据（先红后绿）：队列持续有事件 + 入站静默 → 排水 512 条期间读超时税必须为 0。
     * 缺陷态每排满 64 条付一税（512 条 = 7 税）→ 本用例必红；真机换算即「64 条/秒」的排水天花板。
     */
    @Test
    public void 风暴期持续排水_入站静默_不交读超时税() throws Exception {
        final StubSocket stub = new StubSocket(READ_BLOCK_MS);
        stub.outbound.markAtEvent(DRAIN_TARGET, stub.inbound.timeoutReads);
        prefillEvents(DRAIN_TARGET);

        final RunHandle run = startLoop(stub);
        try {
            awaitEvents(stub, DRAIN_TARGET, 15_000L);

            // 读数先行于判据（红跑也要留痕）：信息性换算——桩税 100ms = 真机 SO_TIMEOUT 1s 的 1/10
            System.out.println("[券H读数] 排水 " + DRAIN_TARGET + " 条耗时（桩税 " + READ_BLOCK_MS
                    + "ms，真机 SO_TIMEOUT=1s 的 1/10 缩放）: "
                    + stub.outbound.elapsedAtMarkedEventMs.get() + "ms；第 " + DRAIN_TARGET
                    + " 条帧写出时读超时税 = " + stub.outbound.taxesAtMarkedEvent.get());
            assertEquals("突发排水（队列持续非空）期间读超时税必须为 0："
                            + "排满 64 条/轮的每一轮都不得做无数据的阻塞读（缺陷态此处 = 7 税，"
                            + "真机换算即每 64 条干等 1s → 排水天花板 ~64 条/秒）",
                    0L, stub.outbound.taxesAtMarkedEvent.get());
            assertEquals("排水期间不得发生重连（probe 路径不得把会话搞断）",
                    1, run.dialCount.get());
            assertEquals("预填的 " + DRAIN_TARGET + " 条事件必须全部投出（税修复不得以丢事件为代价）",
                    DRAIN_TARGET, stub.outbound.eventCount());
            assertEventOrder(stub);
        } finally {
            run.stop();
        }
    }

    /**
     * 静默期回归（约束 2 前半）：队列为空时入站读仍走长超时阻塞路径——CONFIG 下发后即被处理
     * 并回 ACK。probe 分支只在 burst 达上限时启用，空闲轮询语义逐字不变。
     */
    @Test
    public void 静默期队列空_入站CONFIG仍走长超时路径即时处理() throws Exception {
        final StubSocket stub = new StubSocket(READ_BLOCK_MS);
        final RunHandle run = startLoop(stub);
        try {
            awaitFrame(stub, EventBus.CMD_HELLO, 5000L);

            // 会话初期下发 CONFIG（IDE 侧真实时序）：队列空 → 阻塞读路径 → 到达即处理
            stub.inbound.deliver(frame(EventBus.CMD_CONFIG,
                    "{\"disabled\":false,\"desensitize\":false,\"skipPrefixes\":[]}"));

            final Frame ack = awaitFrame(stub, EventBus.CMD_CONFIG_ACK, 5000L);
            assertNotNull("空闲路径下发的 CONFIG 应获 ACK（不得被 probe 分支改变语义）", ack);
            assertEquals("CONFIG ACK body 为跨侧字面契约",
                    "{\"disabled\":false,\"desensitize\":false,\"skipPrefixes\":0,\"bizJarPrefixes\":0}",
                    ack.payload);
            assertFalse("desensitize=false 应已生效（CONFIG 真被处理，而非被 probe 跳过）",
                    AgentState.isDesensitize());
        } finally {
            run.stop();
        }
    }

    /**
     * 风暴期 CONFIG 到达即处理（约束 2 后半）：队列非空（burst 达上限、走 probe 分支）时，
     * 一旦 available()>0（对端确有字节到店），本轮立即转入阻塞读——CONFIG 不得被排水饿死。
     * 同时以短看门狗（500ms）旁证 configSeen 已置位：看门狗不得误触发。
     */
    @Test
    public void 风暴排水期CONFIG到达_即被处理且看门狗不误触发() throws Exception {
        final StubSocket stub = new StubSocket(READ_BLOCK_MS);
        prefillEvents(DRAIN_TARGET);
        // CONFIG 先于首轮到达（字节已在桩流里）：round 1 即 burst=64 + available()>0 → 立即读
        stub.inbound.deliver(frame(EventBus.CMD_CONFIG,
                "{\"disabled\":false,\"desensitize\":false,\"skipPrefixes\":[]}"));

        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, "500");
        final RunHandle run = startLoop(stub);
        try {
            final Frame ack = awaitFrame(stub, EventBus.CMD_CONFIG_ACK, 5000L);
            assertNotNull("风暴期 CONFIG 必须获 ACK（probe 路径不得吞掉有数据的轮次）", ack);
            assertEquals("CONFIG ACK body 为跨侧字面契约",
                    "{\"disabled\":false,\"desensitize\":false,\"skipPrefixes\":0,\"bizJarPrefixes\":0}",
                    ack.payload);
            awaitEvents(stub, DRAIN_TARGET, 15_000L);
            assertFalse("CONFIG 先到时看门狗不得触发（configSeen 已置位）", AgentState.isDisabled());
            assertEquals("风暴期处理的 CONFIG 不得影响事件投递完整性",
                    DRAIN_TARGET, stub.outbound.eventCount());
        } finally {
            run.stop();
        }
    }

    /**
     * 看门狗回归（约束 2 收尾）：持续风暴 + 从未收到 CONFIG → 看门狗仍按窗口触发停采。
     * probe 跳读不改变看门狗判据（按真实流逝时间，每轮都执行）。
     */
    @Test
    public void 持续风暴从未收到CONFIG_看门狗仍按窗口触发停采() throws Exception {
        final StubSocket stub = new StubSocket(READ_BLOCK_MS);
        final long watchdogMs = 400L;
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, String.valueOf(watchdogMs));

        // 持续生产（风暴形态）：不停入队，保证队列长期非空、轮轮 burst 达上限（probe 路径常驻）
        final AtomicBoolean producing = new AtomicBoolean(true);
        final Thread producer = new Thread(() -> {
            int i = 0;
            while (producing.get()) {
                EventBus.offer(event("SELECT storm-" + (i++)));
            }
        }, "drainrate-storm-producer");
        producer.setDaemon(true);
        producer.start();

        final RunHandle run = startLoop(stub);
        try {
            final long deadline = System.currentTimeMillis() + watchdogMs + 3000L;
            while (!AgentState.isDisabled() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertTrue("持续风暴 + 从未收到 CONFIG：看门狗必须在 " + watchdogMs
                    + "ms 窗口后触发停采（probe 跳读不得绕过看门狗判据）", AgentState.isDisabled());
            assertTrue("停采前应至少投出部分事件（证明风暴确实在排水，而非队列从未供应）",
                    stub.outbound.eventCount() > 0);
        } finally {
            producing.set(false);
            run.stop();
        }
    }

    // ---------- 桩与夹具 ----------

    /** 桩入站流：可投递字节；读阻塞至多 [readBlockMs]，超时计一税并抛 SocketTimeoutException。 */
    private static final class ScriptedInbound extends InputStream {
        private final long readBlockMs;
        private byte[] pending = new byte[0];
        private int pos = 0;
        final AtomicLong timeoutReads = new AtomicLong();

        ScriptedInbound(long readBlockMs) {
            this.readBlockMs = readBlockMs;
        }

        synchronized void deliver(byte[] data) {
            final byte[] merged = new byte[(pending.length - pos) + data.length];
            System.arraycopy(pending, pos, merged, 0, pending.length - pos);
            System.arraycopy(data, 0, merged, 0, data.length);
            pending = merged;
            pos = 0;
            notifyAll();
        }

        private void awaitData(long deadlineMs) throws IOException {
            long remain = deadlineMs - System.currentTimeMillis();
            while (pos >= pending.length && remain > 0) {
                try {
                    wait(remain);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("stub read interrupted");
                }
                remain = deadlineMs - System.currentTimeMillis();
            }
            if (pos >= pending.length) {
                timeoutReads.incrementAndGet(); // 读超时税：阻塞满一个 SO_TIMEOUT 仍无数据
                throw new SocketTimeoutException("stub inbound idle");
            }
        }

        @Override
        public synchronized int read() throws IOException {
            awaitData(System.currentTimeMillis() + readBlockMs);
            return pending[pos++] & 0xFF;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            awaitData(System.currentTimeMillis() + readBlockMs);
            final int n = Math.min(len, pending.length - pos);
            System.arraycopy(pending, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public synchronized int available() {
            return pending.length - pos;
        }
    }

    /** 桩出站流：逐字节喂帧解析器（单写者 = loop 线程），统计 EVENT 帧并支持「第 N 条事件同步快照」。 */
    private static final class CountingOutbound extends OutputStream {
        private final List<Frame> frames = new ArrayList<>();
        private final AtomicLong taxesAtMarkedEvent = new AtomicLong(-1L);
        private final AtomicLong elapsedAtMarkedEventMs = new AtomicLong(-1L);
        private final AtomicLong loopStartMs = new AtomicLong(0L);
        private final AtomicReference<AtomicLong> markSource = new AtomicReference<>();
        private int markAt = -1;
        // 帧解析状态（单写者 = loop 线程；读侧快照走 synchronized）
        private int lenAcc = 0;
        private int lenBytes = 0;
        private int cmd = 0;
        private int remaining = 0;
        private byte[] bodyBuf = new byte[0];
        private int bodyPos = 0;

        /** 第 [n] 条 EVENT 帧写出的同步点：快照 [source] 的当前值与墙钟耗时。 */
        void markAtEvent(int n, AtomicLong source) {
            this.markAt = n;
            this.markSource.set(source);
        }

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
                    new String(bodyBuf, java.nio.charset.StandardCharsets.UTF_8)));
            if (cmd == EventBus.CMD_EVENT && markAt > 0
                    && countEvents() >= markAt && markSource.get() != null) {
                final AtomicLong src = markSource.getAndSet(null); // 只采一次（第 N 条事件的同步点）
                taxesAtMarkedEvent.set(src.get());
                elapsedAtMarkedEventMs.set(System.currentTimeMillis() - loopStartMs.get());
            }
            lenAcc = 0;
            lenBytes = 0;
            remaining = 0;
        }

        private int countEvents() {
            int n = 0;
            for (Frame f : frames) {
                if (f.cmd == EventBus.CMD_EVENT) {
                    n++;
                }
            }
            return n;
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
            return countEvents();
        }

        synchronized List<Frame> snapshotFrames() {
            return new ArrayList<>(frames);
        }
    }

    /** 桩 Socket：只替换两股流与连接期设置；不真拨号、不真 IO。 */
    private static final class StubSocket extends Socket {
        final ScriptedInbound inbound;
        final CountingOutbound outbound = new CountingOutbound();

        StubSocket(long readBlockMs) {
            this.inbound = new ScriptedInbound(readBlockMs);
        }

        @Override
        public InputStream getInputStream() {
            return inbound;
        }

        @Override
        public OutputStream getOutputStream() {
            return outbound;
        }

        @Override
        public void setSoTimeout(int timeout) {
            // 桩不实现 SO_TIMEOUT：读阻塞时长由 ScriptedInbound.readBlockMs 定
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
            // 测试结束靠线程中断退出；桩关闭无副作用
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
     * 运行句柄：拨号计数（重连探针）+ **确定性停止**。
     *
     * <p>为什么不能只 interrupt：loop 的退避睡眠缝是记录器（不真睡），中断标志会让阻塞读抛
     * InterruptedIOException → catch 走退避 → 记录器立刻返回 → **立刻重连**——僵尸连接在下一个
     * 用例的看门狗窗口内（拨号时读当前属性）触发 {@code setDisabledByWatchdog} 全局停采，
     * 污染后续用例（首轮实测：先行用例的僵尸让后行风暴用例停在 384/512）。
     * 故停止 = 置 stopped 标志 → 拨号缝拒绝 + 退避缝抛 InterruptedException（run() 捕获即 return）
     * → 线程确定性退出，无僵尸。
     */
    private static final class RunHandle {
        final AtomicInteger dialCount = new AtomicInteger();
        final AtomicBoolean stopped = new AtomicBoolean(false);
        Thread loop;

        RunHandle(Thread loop) {
            this.loop = loop;
        }

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
        final RunHandle handle = new RunHandle(null);
        final SenderLoop.SocketDialer dialer = (host, port) -> {
            if (handle.stopped.get()) {
                throw new IOException("run stopped");
            }
            handle.dialCount.incrementAndGet();
            stub.outbound.loopStartMs.compareAndSet(0L, System.currentTimeMillis());
            return stub;
        };
        final List<Long> backoffSchedule = new ArrayList<>();
        final SenderLoop.BackoffSleeper sleeper = ms -> {
            if (handle.stopped.get()) {
                throw new InterruptedException("run stopped");
            }
            backoffSchedule.add(ms);
            Thread.sleep(Math.min(ms, 50L));
        };
        final Thread loop = new Thread(
                new SenderLoop(1, 1_000L, 5_000L, 2_000L, 10, dialer, sleeper),
                "senderloop-drainrate");
        loop.setDaemon(true);
        handle.loop = loop;
        handle.loop.start();
        return handle;
    }

    private static void prefillEvents(int count) {
        for (int i = 0; i < count; i++) {
            EventBus.offer(event("SELECT drain-" + i));
        }
    }

    private static SqlEvent event(String sql) {
        final SqlEvent e = new SqlEvent();
        e.sql = sql;
        e.timestamp = System.currentTimeMillis();
        e.threadName = Thread.currentThread().getName();
        return e;
    }

    /** 用被测代码自身的 writeFrame 造帧（形态即契约）。 */
    private static byte[] frame(int cmd, String body) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        SenderLoop.writeFrame(new DataOutputStream(bos), cmd, body);
        return bos.toByteArray();
    }

    private static void awaitEvents(StubSocket stub, int count, long timeoutMs) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (stub.outbound.eventCount() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue("等待 " + timeoutMs + "ms 内应投出 " + count + " 条事件帧，实测 "
                        + stub.outbound.eventCount() + " 条（税修复不得造成投递停滞）",
                stub.outbound.eventCount() >= count);
    }

    private static Frame awaitFrame(StubSocket stub, int cmd, long timeoutMs) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Frame f : stub.outbound.snapshotFrames()) {
                if (f.cmd == cmd) {
                    return f;
                }
            }
            Thread.sleep(5L);
        }
        return null;
    }

    /** 投递顺序断言：第 i 条事件帧必须携带第 i 条预填事件的 SQL（排水不得乱序/丢失）。 */
    private static void assertEventOrder(StubSocket stub) {
        final List<Frame> frames = stub.outbound.snapshotFrames();
        int seen = 0;
        for (Frame f : frames) {
            if (f.cmd != EventBus.CMD_EVENT) {
                continue;
            }
            assertTrue("事件必须按队列序投出（第 " + seen + " 条），实际 body: " + f.payload,
                    f.payload.contains("\"sql\":\"SELECT drain-" + seen + "\""));
            seen++;
        }
        assertEquals("事件帧总数", DRAIN_TARGET, seen);
    }
}
