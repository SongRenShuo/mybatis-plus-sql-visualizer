package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T-PerfSweep6 T-PS6-05：`SenderLoop` 退避复位点后移 + 「连上即被关」放弃重试测试。
 *
 * 缺陷现场（性能报告发现 5 / 发现 9）：退避复位打在「socket 连上」处——只要 TCP 握手成功就算成功。
 * 于是「连上即被关」（attach 端口 accept 后即释放，或端口被无关进程复用）这种**必然失败**的连接
 * 每轮都把退避打回初始值，agent 以固定短节奏对着一个不存在的对端写帧。修法两条：
 *  1. 复位点后移到「连接存活超过 `backoffResetAfterMs`」的失败收口处；
 *  2. 连续「连上即被关」达 `maxFastDrops` 次即放弃该端口（退出重连循环）。
 *
 * **不注入系统属性、不启 `EventBus`**：退避参数走包内构造器注入（`SenderLoop(int, long, long, long, int)`），
 * 避免生产侧多出可配置面；`EventBus.start` 是 JVM 内单例（started 标志），第二个测试类会静默拿不到
 * `SenderLoop`，故本类直接 `new SenderLoop(...)` 起线程。
 *
 * 覆盖点：
 *  1. **退避确实增长**（先红后绿主判据）：连上即被关时相邻两次重连的间隔应显著大于前一次；
 *     退回「连上即复位」形态时间隔恒定 → 本用例必红；
 *  2. 达上限后放弃该端口：不再有新的 accept；
 *  3. 存活超过阈值的连接掉线后，快速断开计数与退避**都复位**（正常路径语义不变）；
 *  4. T-PerfSweep7 T-PS7-02（先红后绿主判据）：**慢拨号 + 连上即被关**——拨号耗时（注入 > 阈值）
 *     不得计入「连接存活时长」，否则每次「连上即被关」都被误判成正常掉线而复位退避与
 *     fastDrops（永不放弃端口，且误判恰发生在拨号最慢的真实场景）。
 */
public class SenderLoopBackoffTest {

    /** 初始退避（毫秒）：压到测试量级，比值判据与生产同形。 */
    private static final long INITIAL_MS = 150L;
    private static final long MAX_MS = 600L;
    /** 复位阈值：与生产同量级语义（存活过阈值才算一次正常掉线）。 */
    private static final long RESET_AFTER_MS = 200L;
    private static final int MAX_FAST_DROPS = 3;
    /**
     * 慢拨号注入耗时（毫秒）：必须 > [RESET_AFTER_MS]（测试量级对应生产「拨号 > 2s」——
     * 远端网络不可达时 TCP 握手可挂数秒，正是 T-PS7-02 要防的形态）。
     */
    private static final long SLOW_DIAL_MS = 350L;

    /** 「连上即被关」的模拟服务：记录每次 accept 时刻，随即关闭连接。 */
    private static final class ClosingServer implements AutoCloseable {
        final ServerSocket server;
        final List<Long> acceptAtMs = new ArrayList<>();
        private final long firstHoldMs;
        private volatile boolean running = true;
        private final Thread thread;

        ClosingServer(long firstHoldMs) throws Exception {
            this.firstHoldMs = firstHoldMs;
            this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::loop, "senderloop-backoff-probe");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void loop() {
            while (running) {
                try {
                    final Socket s = server.accept();
                    final long held = firstHoldMs;
                    synchronized (this) {
                        if (acceptAtMs.isEmpty() && held > 0L) {
                            acceptAtMs.add(System.currentTimeMillis());
                            // 首连保持一段时间（模拟一次正常连接），随后关闭
                            try {
                                Thread.sleep(held);
                            } catch (InterruptedException ignore) {
                                return;
                            }
                            s.close();
                            continue;
                        }
                        acceptAtMs.add(System.currentTimeMillis());
                    }
                    s.close();
                } catch (Throwable t) {
                    return;
                }
            }
        }

        synchronized List<Long> snapshot() {
            return new ArrayList<>(acceptAtMs);
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() {
            running = false;
            try {
                server.close();
            } catch (Throwable ignore) {
            }
            thread.interrupt();
        }
    }

    private static long awaitAccepts(ClosingServer server, int count, long timeoutMs) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (server.snapshot().size() >= count) {
                return System.currentTimeMillis();
            }
            Thread.sleep(10L);
        }
        return System.currentTimeMillis();
    }

    /** T-CiRelease（20261025）：等待放弃记账落地——放弃是 loop 线程异步执行的，调用方方法结束前
     * 必须确认它已入账，否则可能落进后续测试的计数复位窗（见「复位」用例尾注）。 */
    private static void awaitPortAbandoned(int port, long timeoutMs) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (AgentState.lastAbandonedPort() == port) {
                return;
            }
            Thread.sleep(10L);
        }
    }

    @Test
    public void 连上即被关_退避增长且达上限后放弃该端口() throws Exception {
        AgentState.resetPortAbandonedForTest();
        final PrintStream originalErr = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (ClosingServer server = new ClosingServer(0L)) {
            System.setErr(new PrintStream(captured, true, "UTF-8"));
            final Thread loop = new Thread(
                    new SenderLoop(server.port(), INITIAL_MS, MAX_MS, RESET_AFTER_MS, MAX_FAST_DROPS),
                    "senderloop-backoff");
            loop.setDaemon(true);
            loop.start();

            // 三次「连上即被关」= 三次 accept（第 3 次后达上限 → 放弃）
            awaitAccepts(server, MAX_FAST_DROPS, 10_000L);
            // 再观察一段，确认没有第 4 次 accept（放弃生效）
            final int afterWait = server.snapshot().size();
            Thread.sleep(1_500L);
            final List<Long> stamps = server.snapshot();
            assertEquals("达上限后不得再重连该端口（连续快速断开 = 端口上的监听者已不是本插件）",
                    MAX_FAST_DROPS, stamps.size());
            assertTrue("放弃前应至少观察到 3 次 accept，实际 " + afterWait, afterWait >= MAX_FAST_DROPS);

            final long gap1 = stamps.get(1) - stamps.get(0);
            final long gap2 = stamps.get(2) - stamps.get(1);
            // 复位点后移前：两段间隔都≈初始退避（恒定）；后移后第二段应≈2 倍初始退避
            assertTrue(
                    "退避必须在「连上即被关」下增长（第 1 段 " + gap1 + "ms，第 2 段 " + gap2 + "ms）——" +
                            "退回「连上即复位」形态时两段相等，本断言即先红后绿判据",
                    gap2 * 2L >= gap1 * 3L);

            // 复核 minor㉚：放弃必须是**可观测**的（此前只有一行散文式 stderr，无稳定签名、无记账，
            // 且通道已死 → 排障只能靠「面板没条目」反推）。两处断言：稳定签名行恰好一条 + 本进程记账。
            System.err.flush();
            final String errText = captured.toString("UTF-8");
            final long signatureHits = Arrays.stream(errText.split("\\R"))
                    .filter(line -> line.contains("PORT_ABANDONED port=" + server.port()))
                    .count();
            assertEquals("放弃该端口时必须打印恰好一条稳定签名行（支持按 PORT_ABANDONED grep 宿主日志），"
                    + "实际命中 " + signatureHits + " 条；stderr=\n" + errText, 1L, signatureHits);
            assertEquals("放弃必须记入 AgentState（计数），否则「agent 是否已放弃上报」在进程内不可读",
                    1L, AgentState.abandonedPortCount());
            assertEquals("记账必须带上被放弃的端口号", server.port(), AgentState.lastAbandonedPort());

            loop.interrupt();
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    public void 存活超过阈值的连接掉线后_退避与快速断开计数都复位() throws Exception {
        // 首连保持 400ms（> RESET_AFTER_MS）后关闭 = 一次正常掉线；此后连上即关
        try (ClosingServer server = new ClosingServer(400L)) {
            final Thread loop = new Thread(
                    new SenderLoop(server.port(), INITIAL_MS, MAX_MS, RESET_AFTER_MS, MAX_FAST_DROPS),
                    "senderloop-backoff-reset");
            loop.setDaemon(true);
            loop.start();

            // 正常掉线已把 fastDrops 复位 → 之后的「连上即被关」要重新数满 3 次才放弃 ⇒ 总 accept 数 = 1 + 3
            awaitAccepts(server, 1 + MAX_FAST_DROPS, 15_000L);
            final int observed = server.snapshot().size();
            assertEquals(
                    "首连存活过阈值应复位快速断开计数：总 accept 应为 1（正常连接）+ " + MAX_FAST_DROPS
                            + "（数满上限），实际 " + observed,
                    1 + MAX_FAST_DROPS, observed);
            // T-CiRelease（20261025）：等待本测试的放弃记账落地（lastAbandonedPort 变为本端口）——
            // 放弃由 loop 线程异步执行，方法不等记账就结束时，它可能落在下一个测试的计数复位
            // **之后**补记，把那个测试的期望 1 冲成 2（CI run 40157047667 实证：慢拨号用例
            // expected:<1> but was:<2>，本机调度快难以复现）。
            awaitPortAbandoned(server.port(), 5_000L);

            loop.interrupt();
        }
    }

    /**
     * T-PerfSweep7 T-PS7-02（先红后绿主判据）：慢拨号 + 连上即被关 → **不复位**且计入 fastDrops。
     *
     * 缺陷现场：拨号计时此前从「拨号发起前」（attemptStartMs）起算，且 {@code new Socket} 无 connect
     * 超时——拨号本身挂数秒时，「连上即被关」的存活读数被拨号耗时撑过复位阈值，被误判成
     * 「一次正常掉线」：退避与 fastDrops 每轮复位，永不放弃端口（且拨号越慢误判越必然）。
     * 修法：连接成功那一刻另记 connectedAtMs，复位与 fastDrops 判据改按
     * {@code now - connectedAtMs} 计——退回 attemptStartMs 起算本用例必红（accept 连绵不断）。
     */
    @Test
    public void 慢拨号后连上即被关_拨号耗时不得计入存活_不复位且计入快速断开() throws Exception {
        AgentState.resetPortAbandonedForTest();
        final PrintStream originalErr = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (ClosingServer server = new ClosingServer(0L)) {
            System.setErr(new PrintStream(captured, true, "UTF-8"));
            // 慢拨号缝：每次拨号先耗 SLOW_DIAL_MS（> RESET_AFTER_MS）再真拨号。
            // 退回 attemptStartMs 起算时，aliveMs ≈ SLOW_DIAL_MS > 阈值 → 每次都按「正常掉线」复位；
            // 改 connectedAtMs 起算后，aliveMs 只含建连后的存活（毫秒级）→ 正确计入 fastDrops。
            final SenderLoop.SocketDialer slowDial = new SenderLoop.SocketDialer() {
                @Override
                public Socket dial(String host, int port) throws java.io.IOException {
                    try {
                        Thread.sleep(SLOW_DIAL_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException(interrupted.toString());
                    }
                    return new Socket(host, port);
                }
            };
            final Thread loop = new Thread(
                    new SenderLoop(server.port(), INITIAL_MS, MAX_MS, RESET_AFTER_MS, MAX_FAST_DROPS, slowDial),
                    "senderloop-slowdial");
            loop.setDaemon(true);
            loop.start();

            awaitAccepts(server, MAX_FAST_DROPS, 15_000L);
            // 观察窗：修复态此时已放弃该端口（不再有第 4 次 accept）；
            // 缺陷态每次都被复位 → accept 连绵不断，观察窗内必然冒出第 4 次 → 本断言即先红判据
            Thread.sleep(1_500L);
            final List<Long> stamps = server.snapshot();
            assertEquals("慢拨号（拨号耗时 > 阈值）+ 连上即被关必须按「存活 < 阈值」计入快速断开："
                            + "恰 " + MAX_FAST_DROPS + " 次 accept 后放弃该端口，实际 " + stamps.size()
                            + "（超出 = 拨号耗时被计入存活，被误判为正常掉线而复位）",
                    MAX_FAST_DROPS, stamps.size());
            assertEquals("慢拨号下的放弃必须记入 AgentState 记账（否则进程内不可读）",
                    1L, AgentState.abandonedPortCount());
            assertEquals("记账必须带上被放弃的端口号", server.port(), AgentState.lastAbandonedPort());

            loop.interrupt();
        } finally {
            System.setErr(originalErr);
        }
    }
}
