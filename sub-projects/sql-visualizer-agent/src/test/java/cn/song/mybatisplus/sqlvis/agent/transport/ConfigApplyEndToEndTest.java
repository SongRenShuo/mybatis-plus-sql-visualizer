package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.FakeDriver;
import cn.song.mybatisplus.sqlvis.agent.hook.ConnectionHandler;
import cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.util.StackLocator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-AgentConfigChannel T-01/T-01b/T-02/T-07 环回端到端：真 [SenderLoop] + 真 loopback
 * {@link ServerSocket} + **真语句执行**（经 agent 的 StatementHandler 代理）。
 *
 * <p>与 {@code SenderLoopFrameTest} 的分工：那边钉帧格式（解析层），这边钉「下发 → 生效 → 行为」
 * 整链——发 {@code disabled=true} 后一次语句执行**不产出事件帧**，发 {@code disabled=false} 后恢复。
 * 语句执行走 {@link SqlHookCallbacks#onConnection}（织入字节码在 connect 出口调用的同一个公开入口）
 * → {@link ConnectionHandler} → agent 的 StatementHandler 代理，并对代理身份做前置断言，
 * 避免「根本没走采集路径」的空真绿。
 *
 * <p>计时全部经系统属性压短（心跳 200ms；看门狗按用例注入），整套用例秒级完成。
 *
 * <p><b>对入站读序的鉴别力（实测）</b>：读序改回错位形态后本类 5 例红 2（{@code 停采生效}、
 * {@code 停采期间_队列非空也不写出事件帧}——都依赖 CONFIG 真被解析生效并回 ACK），
 * 绿 3（看门狗 2 例 + 下降沿清队列 1 例，均不经入站读序）。
 */
public class ConfigApplyEndToEndTest {

    /** 心跳间隔注入（毫秒）：缩短事件轮询/读超时节奏，让断言窗口落在百毫秒级。 */
    private static final long HEARTBEAT_MS = 200L;

    /**
     * CONFIG 用例的看门狗窗口（毫秒）：取 60s 让看门狗在本用例内**不可能**触发。
     * 不能用「刚过首段」的量级（如 3s）：本用例前置含反射包装 + 语句执行 + 等帧，
     * 高负载门禁下首段就可能超窗口 → 看门狗抢先把状态置成停采 → 步骤 A 断言偶发转红。
     * 看门狗本身由 {@link #看门狗_从未收到CONFIG时主动停采而会话仍在} 用短窗口单独覆盖。
     */
    private static final long WATCHDOG_MS_CONFIG_CASE = 60_000L;

    /** 看门狗用例窗口（毫秒）：会话建立后不发 CONFIG，应在窗口过后主动停采。 */
    private static final long WATCHDOG_MS_CASE = 500L;

    /** 帧读超时（毫秒）：小于心跳间隔的 3 倍，读不到帧即回轮询（不做「一读到底」的等待）。 */
    private static final int FRAME_READ_TIMEOUT_MS = 500;

    private String prevHeartbeat;
    private String prevWatchdog;
    private ServerSocket server;
    private Socket socket;
    private Thread loop;

    @Before
    public void setUp() {
        prevHeartbeat = System.getProperty(SenderLoop.HEARTBEAT_MS_PROP);
        prevWatchdog = System.getProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP);
        AgentState.setDisabled(false);      // 生产初值 fail-open；用例自证「停采是 CONFIG/看门狗带来的」
        AgentState.setDesensitize(true);
        StackLocator.setExtraSkipPrefixes(new String[0]);
        // T-ConnCastSafety：本类 A 段用例钉的是「连接代理通道」的停采语义——该通道自 2026-10-28 起
        // 出厂不再替换宿主连接（$Proxy 无法强转到驱动实现类），故这里按测试缝显式打开。
        SqlHookCallbacks.setWrapConnectionForTest(Boolean.TRUE);
    }

    @After
    public void tearDown() {
        closeQuietly(socket);
        closeQuietly(server);
        if (loop != null) {
            loop.interrupt();               // 循环里的退避 sleep 立即抛 InterruptedException → 线程退出
            try {
                loop.join(3000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        restore(SenderLoop.HEARTBEAT_MS_PROP, prevHeartbeat);
        restore(SenderLoop.CONFIG_WATCHDOG_MS_PROP, prevWatchdog);
        AgentState.setDisabled(false);
        AgentState.setDesensitize(true);
        StackLocator.setExtraSkipPrefixes(new String[0]);
        SqlHookCallbacks.setWrapConnectionForTest(null);
    }

    /**
     * 主用例：下发停采 → 生效（ACK + 状态）→ 语句照旧执行但不产出事件帧 → 下发恢复 → 事件帧回来。
     * 旧读序下 CONFIG 被解析成 cmd=0 丢弃，ACK 永不到达、停采永不生效 → 本用例在 ACK/无事件断言处必红。
     */
    @Test
    public void 停采生效_语句不再产出事件帧_恢复后继续产出() throws Exception {
        startLoop(WATCHDOG_MS_CONFIG_CASE);
        final DataInputStream in = new DataInputStream(socket.getInputStream());
        final DataOutputStream out = new DataOutputStream(socket.getOutputStream());

        assertEquals("会话建立应先握手 HELLO", EventBus.CMD_HELLO, awaitFrame(in, 3000L).cmd);
        assertEquals("连接建立即补发心跳", EventBus.CMD_HEARTBEAT, awaitFrame(in, 3000L).cmd);

        // 采集态下先备好语句代理：停采后 ConnectionHandler 会直通（不再包装），此处是唯一的包装窗口
        final PreparedStatement ps = wrappedStatement("INSERT INTO t VALUES (1)");

        // A) 采集态：一次语句执行 → 事件帧
        assertEquals(1, ps.executeUpdate());
        final Frame event = awaitFrame(in, EventBus.CMD_EVENT, 3000L);
        assertNotNull("采集态下 execute 应产出事件帧（本断言同时证明后面「无事件」不是空真）", event);
        assertTrue("事件帧应携带该语句： " + event.payload, event.payload.contains("INSERT INTO t VALUES (1)"));

        // B) 下发停采 → 应用侧回 ACK，状态真生效
        SenderLoop.writeFrame(out, EventBus.CMD_CONFIG,
                "{\"disabled\":true,\"desensitize\":false,\"skipPrefixes\":[]}");
        final Frame ack = awaitFrame(in, EventBus.CMD_CONFIG_ACK, 3000L);
        assertNotNull("CONFIG 应获 ACK（IDE 侧据此判「生效」，不再靠行为反推）", ack);
        assertEquals("ACK body 为跨侧字面契约",
                "{\"disabled\":true,\"desensitize\":false,\"skipPrefixes\":0,\"bizJarPrefixes\":0}", ack.payload);
        assertTrue("应用侧应处于停采态", AgentState.isDisabled());

        // C) 停采态：语句照旧执行（返回原语义值），窗口内不得出现任何事件帧
        assertEquals("停采只停采集，不得影响执行语义", 1, ps.executeUpdate());
        assertNoEventFrame(in, 1200L);

        // D) 下发恢复 → 事件帧回来
        SenderLoop.writeFrame(out, EventBus.CMD_CONFIG,
                "{\"disabled\":false,\"desensitize\":false,\"skipPrefixes\":[]}");
        final Frame ack2 = awaitFrame(in, EventBus.CMD_CONFIG_ACK, 3000L);
        assertEquals("恢复采集的 ACK 应反映生效态",
                "{\"disabled\":false,\"desensitize\":false,\"skipPrefixes\":0,\"bizJarPrefixes\":0}", ack2.payload);
        assertFalse(AgentState.isDisabled());
        assertEquals(1, ps.executeUpdate());
        assertNotNull("恢复采集后应重新产出事件帧", awaitFrame(in, EventBus.CMD_EVENT, 3000L));
    }

    /**
     * T-07 看门狗：会话建立后窗口内从未收到任何 CONFIG → 主动停采（且会话本身不死，心跳照发）。
     * 这条是 fail-open 的兜底：控制通道再出任何问题，损害也被限制在窗口内，而不是整场静默采集。
     */
    @Test
    public void 看门狗_从未收到CONFIG时主动停采而会话仍在() throws Exception {
        startLoop(WATCHDOG_MS_CASE);
        final DataInputStream in = new DataInputStream(socket.getInputStream());
        assertEquals(EventBus.CMD_HELLO, awaitFrame(in, 3000L).cmd);

        final long deadline = System.currentTimeMillis() + WATCHDOG_MS_CASE + 3000L;
        while (!AgentState.isDisabled() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertTrue("会话建立后 " + WATCHDOG_MS_CASE + "ms 内未收到 CONFIG，应主动停采（fail-open 看门狗）",
                AgentState.isDisabled());

        // 停采只停采集：会话仍活着（心跳继续），否则 IDE 会误判掉线并重建会话
        final Frame f = awaitFrame(in, 3000L);
        assertNotNull("看门狗停采后会话不得关闭（应仍有心跳帧）", f);
        assertEquals("停采后仍应为心跳（会话存活）", EventBus.CMD_HEARTBEAT, f.cmd);
    }

    /**
     * T-01b 尽出闸门（计划 §五 P-3 要求的「队列补投断言」）：**队列非空且已停采**时，
     * 不得写出任何 EVENT 帧（心跳不受影响）。
     *
     * <p>为什么是「先停采、再塞事件」而不是「先塞、再停采」：后者要断言「停采生效前没被尽出」
     * 就必须赌「入队 vs 尽出」的线程交错，那本身就是竞态，用例会偶发。这里构造的状态与现场等价
     * ——队列里有事件、当前处于停采态——只是把顺序固定下来：停采帧先经真 socket 生效（ACK 为凭），
     * 再注入事件，于是「有没有被写出」只取决于尽出前那道 {@code isDisabled()} 短路。
     * 删掉该短路 → 下一轮尽出就把这 5 条写成 EVENT 帧 → 本用例转红（读数见实施记录）。
     *
     * <p>边界（有意保持现状）：短路只保证「停采期间不投递」，**不承诺丢弃**——丢弃职责在下降沿清空
     * 与 IDE 侧入队闸门（T-03）。故末尾清队列，免把存量事件漏给同 JVM 的其它用例。
     */
    @Test
    public void 停采期间_队列非空也不写出事件帧() throws Exception {
        startLoop(WATCHDOG_MS_CONFIG_CASE);
        final DataInputStream in = new DataInputStream(socket.getInputStream());
        final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        assertEquals(EventBus.CMD_HELLO, awaitFrame(in, 3000L).cmd);

        SenderLoop.writeFrame(out, EventBus.CMD_CONFIG, "{\"disabled\":true}");
        assertNotNull("停采 CONFIG 应获 ACK", awaitFrame(in, EventBus.CMD_CONFIG_ACK, 3000L));
        assertTrue("应用侧应处于停采态", AgentState.isDisabled());

        final int queuedBefore = EventBus.queuedCount();
        for (int i = 0; i < 5; i++) {
            EventBus.offer(staleEvent("SELECT stale-" + i));
        }

        assertNoEventFrame(in, 1000L);
        assertTrue("这 5 条应仍留在队列里（证明是「停采期间不投递」而非「已丢弃」）：实测 "
                        + EventBus.queuedCount() + " 条",
                EventBus.queuedCount() >= queuedBefore + 5);

        EventBus.clearQueued(); // 边界清理：短路不承诺丢弃，用例内自行清干净
    }

    /** T-07 窗口取值：缺省/非法/非正数一律回落默认，不得因注入失误改变生产行为。 */
    @Test
    public void 看门狗窗口_缺省与非法回落默认() {
        System.clearProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP);
        assertEquals("缺省应为 15s", 15000L, SenderLoop.resolveConfigWatchdogMs());
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, "abc");
        assertEquals(15000L, SenderLoop.resolveConfigWatchdogMs());
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, "0");
        assertEquals(15000L, SenderLoop.resolveConfigWatchdogMs());
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, "-5");
        assertEquals(15000L, SenderLoop.resolveConfigWatchdogMs());
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, "800");
        assertEquals("合法注入应生效（测试可短跑）", 800L, SenderLoop.resolveConfigWatchdogMs());
    }

    /**
     * T-01b：停采下降沿清空已排队事件并计数（计数可观测），且重复停采不重复计数。
     * 否则「点停之后还冒条目」——连接建立时 SenderLoop 先尽出队列，存量事件会在停采生效后被补投。
     */
    @Test
    public void 停采下降沿_清空已排队事件并计数() {
        EventBus.clearQueued(); // 归零起点（同 JVM 其它用例可能留存量）
        final long before = EventBus.clearedCount();
        EventBus.offer(staleEvent("SELECT stale-1"));
        EventBus.offer(staleEvent("SELECT stale-2"));
        EventBus.offer(staleEvent("SELECT stale-3"));

        AgentState.setDisabled(true); // 上升沿：false → true

        assertTrue("停采下降沿应清空已排队事件并累计计数（实测增量 " + (EventBus.clearedCount() - before) + "）",
                EventBus.clearedCount() >= before + 3);
        final long afterFirst = EventBus.clearedCount();
        AgentState.setDisabled(true); // 非下降沿
        assertEquals("重复停采不应重复计数", afterFirst, EventBus.clearedCount());
    }

    /**
     * T-AgentConfigChannel T-08（2026-09-22 真机实证，假 IDE 探针现场）：**停采期间建立的连接也必须被包装**。
     *
     * <p>真机现场（`dev-support/e2e-fake-ide`）：`disabled=true` 的 CONFIG 与 ACK 均已生效，但随后
     * 建立的连接（连接池在应用就绪后创建）在恢复采集后**一条事件都不产出**——根因是 `onConnection`
     * 在**连接级**判 `disabled` 直接返回原连接，而连接池会复用同一批连接，于是
     * 「先跑应用、再点开始捕获」与「停采 → 恢复」两条主路径都永久捕不到。
     *
     * <p>修复＝连接级一律包装（`disabled` 只做**逐调用**短路，与「禁用 = volatile 短路、不卸载」的设计红线一致）。
     * 本用例钉住三件事：① 停采态建立的连接仍被包装；② 停采态执行照旧且**不产出事件**（证明包装没把「停采静音」弄丢）；
     * ③ 恢复采集后**同一连接**上执行立刻产出事件（无需新建连接）。
     *
     * <p>**原已知边界已在 R5 收口**：本用例此前登记「停采期间 prepare 的语句仍不包装——`ConnectionHandler`
     * 的逐调用短路在 `prepareStatement` 处即直通，仅 `ReuseExecutor` 一类会命中」。
     * T-AgentResiduals R5 起该顶层短路已删除（本仓统一为「只在执行级短路」），
     * 故这里 D 段用的是**恢复后新 prepare** 的语句——「停采期间 prepare、恢复后执行同一语句」的
     * 专项用例见 {@link #停采期间prepare的语句_恢复后执行同一语句即产出事件}。
     */
    @Test
    public void 停采期间建立的连接仍被包装_恢复后同一连接即可产出事件() throws Exception {
        startLoop(WATCHDOG_MS_CONFIG_CASE);
        final DataInputStream in = new DataInputStream(socket.getInputStream());
        final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        assertEquals("会话建立应先握手 HELLO", EventBus.CMD_HELLO, awaitFrame(in, 3000L).cmd);
        assertEquals("连接建立即补发心跳", EventBus.CMD_HEARTBEAT, awaitFrame(in, 3000L).cmd);

        // A) 先停采（模拟「开着捕获关跑应用」）
        SenderLoop.writeFrame(out, EventBus.CMD_CONFIG,
                "{\"disabled\":true,\"desensitize\":false,\"skipPrefixes\":[]}");
        assertNotNull("CONFIG 应获 ACK", awaitFrame(in, EventBus.CMD_CONFIG_ACK, 3000L));
        assertTrue("应用侧应处于停采态", AgentState.isDisabled());

        // B) 停采态建立连接：必须仍被包装（连接级跳过＝恢复采集失效的根因，此处必红）
        final Connection raw = new FakeDriver().connect("jdbc:fake://cfg-e2e-late", new Properties());
        assertNotNull("夹具前提：FakeDriver 应返回连接", raw);
        final Connection conn = (Connection) SqlHookCallbacks.onConnection(raw, "jdbc:fake://cfg-e2e-late");
        assertTrue("停采态建立的连接仍须被 agent 包装（连接级跳过会让恢复采集永久失效）",
                Proxy.getInvocationHandler(conn) instanceof ConnectionHandler);

        // C) 停采态：执行照旧、窗口内无事件（证明「一律包装」没有破坏停采静音）
        final PreparedStatement offStmt = conn.prepareStatement("INSERT INTO t VALUES (2)");
        assertEquals("停采只停采集，不得影响执行语义", 1, offStmt.executeUpdate());
        assertNoEventFrame(in, 1200L);

        // D) 恢复采集 → 同一连接上**新 prepare** 的语句立刻产出事件（无需新建连接）
        SenderLoop.writeFrame(out, EventBus.CMD_CONFIG,
                "{\"disabled\":false,\"desensitize\":false,\"skipPrefixes\":[]}");
        assertNotNull("恢复采集的 ACK 应到达", awaitFrame(in, EventBus.CMD_CONFIG_ACK, 3000L));
        assertFalse(AgentState.isDisabled());
        final PreparedStatement onStmt = conn.prepareStatement("INSERT INTO t VALUES (3)");
        assertEquals(1, onStmt.executeUpdate());
        final Frame ev = awaitFrame(in, EventBus.CMD_EVENT, 3000L);
        assertNotNull("恢复采集后同一连接上应立刻产出事件（连接级跳过的缺陷形态在此必红）", ev);
        assertTrue("事件帧应携带该语句：" + ev.payload, ev.payload.contains("INSERT INTO t VALUES (3)"));
    }

    /**
     * T-AgentResiduals R5（定案 B：只在执行级短路）：**停采期间 prepare 的语句，恢复后执行同一语句必须产出事件**。
     *
     * <p>现场（R5 立项）：`ConnectionHandler.invoke` 顶层有 `if (AgentState.isDisabled()) return
     * method.invoke(raw, args);` → 停采期间 `prepareStatement` 返回**原语句**（不包装），于是
     * 「停采期间 prepare、恢复后才执行」的语句**永远哑**。MyBatis 默认 `SimpleExecutor` 每条查询现
     * prepare、执行后关闭，故实际不可达；`ReuseExecutor` 一类复用语句的执行器、以及应用自持语句的场景会命中。
     * R5 删掉该顶层短路后，包装一律在位，唯一闸门是 `StatementHandler.invoke` 的逐调用短路。
     *
     * <p>三段断言（缺一不可）：
     * ① 停采态 `prepareStatement` 返回的语句**已被 agent 包装**（删顶层短路后才有；回退即红）；
     * ② **对照臂**：停采态执行同一语句 → 窗口内无事件帧（证明停采静音没被这次改动弄丢）；
     * ③ 恢复态执行**同一语句**（不重新 prepare）→ 产出事件帧且带该 SQL（本项的核心交付）。
     *
     * <p>为什么必须用「同一语句」而不是恢复后重新 prepare：重新 prepare 走的是另一条已生效的路径
     * （R5 之前也能过），钉不住「停采期间那条语句恢复后能不能用」。
     */
    @Test
    public void 停采期间prepare的语句_恢复后执行同一语句即产出事件() throws Exception {
        startLoop(WATCHDOG_MS_CONFIG_CASE);
        final DataInputStream in = new DataInputStream(socket.getInputStream());
        final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        assertEquals("会话建立应先握手 HELLO", EventBus.CMD_HELLO, awaitFrame(in, 3000L).cmd);
        assertEquals("连接建立即补发心跳", EventBus.CMD_HEARTBEAT, awaitFrame(in, 3000L).cmd);

        // A) 先停采
        SenderLoop.writeFrame(out, EventBus.CMD_CONFIG,
                "{\"disabled\":true,\"desensitize\":false,\"skipPrefixes\":[]}");
        assertNotNull("CONFIG 应获 ACK", awaitFrame(in, EventBus.CMD_CONFIG_ACK, 3000L));
        assertTrue("应用侧应处于停采态", AgentState.isDisabled());

        // B) 停采态 prepare：必须已被包装（① —— 顶层短路回退时这里是原语句，本用例必红）
        final Connection raw = new FakeDriver().connect("jdbc:fake://cfg-e2e-offprepare", new Properties());
        assertNotNull("夹具前提：FakeDriver 应返回连接", raw);
        final Connection conn = (Connection) SqlHookCallbacks.onConnection(raw, "jdbc:fake://cfg-e2e-offprepare");
        final PreparedStatement offStmt = conn.prepareStatement("INSERT INTO t VALUES (9)");
        assertTrue("停采期间 prepare 的语句也须被 agent 包装（顶层 disabled 短路会让它恢复后仍哑）",
                Proxy.isProxyClass(offStmt.getClass()));
        assertEquals("包装者须为本 agent 的 StatementHandler（否则本用例对采集路径无鉴别力）",
                "cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler",
                Proxy.getInvocationHandler(offStmt).getClass().getName());

        // C) 对照臂：停采态执行同一语句 → 无事件帧（停采静音仍然成立）
        assertEquals("停采只停采集，不得影响执行语义", 1, offStmt.executeUpdate());
        assertNoEventFrame(in, 1200L);

        // D) 恢复采集 → 执行**同一语句**（不重新 prepare）→ 必须产出事件
        SenderLoop.writeFrame(out, EventBus.CMD_CONFIG,
                "{\"disabled\":false,\"desensitize\":false,\"skipPrefixes\":[]}");
        final Frame ack = awaitFrame(in, EventBus.CMD_CONFIG_ACK, 3000L);
        assertNotNull("恢复采集的 ACK 应到达", ack);
        assertFalse(AgentState.isDisabled());
        assertEquals(1, offStmt.executeUpdate());
        final Frame ev = awaitFrame(in, EventBus.CMD_EVENT, 3000L);
        assertNotNull("恢复采集后执行「停采期间 prepare 的同一语句」应产出事件（R5 的核心断言）", ev);
        assertTrue("事件帧应携带该语句：" + ev.payload, ev.payload.contains("INSERT INTO t VALUES (9)"));
    }

    // ---------- 辅助 ----------

    private void startLoop(long watchdogMs) throws Exception {        System.setProperty(SenderLoop.HEARTBEAT_MS_PROP, String.valueOf(HEARTBEAT_MS));
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, String.valueOf(watchdogMs));
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        server.setSoTimeout(5000);
        loop = new Thread(new SenderLoop(server.getLocalPort()), "sqlvis-agent-cfg-e2e");
        loop.setDaemon(true);
        loop.start();
        socket = server.accept();
        socket.setSoTimeout(FRAME_READ_TIMEOUT_MS);
    }

    /**
     * 起一条经 agent 采集链的 PreparedStatement：{@link SqlHookCallbacks#onConnection} 是织入字节码在
     * connect 出口调用的同一个公开入口（此处省去字节码织入这一步——织入本身由
     * {@code ConnectWeaveEndToEndTest} 覆盖），其后的语句代理就是产线上的 StatementHandler。
     * 代理身份做前置断言：不成立即说明本用例对采集路径无鉴别力（空真绿）。
     */
    private static PreparedStatement wrappedStatement(String sql) throws Exception {
        final Connection raw = new FakeDriver().connect("jdbc:fake://cfg-e2e", new Properties());
        assertNotNull("夹具前提：FakeDriver 应返回连接", raw);
        final Connection conn = (Connection) SqlHookCallbacks.onConnection(raw, "jdbc:fake://cfg-e2e");
        assertTrue("夹具前提：连接应被 agent 包装",
                Proxy.getInvocationHandler(conn) instanceof ConnectionHandler);
        final PreparedStatement ps = conn.prepareStatement(sql);
        assertTrue("夹具前提：语句应被 agent 包装", Proxy.isProxyClass(ps.getClass()));
        assertEquals("夹具前提：语句代理须为本 agent 的 StatementHandler（否则不产出事件）",
                "cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler",
                Proxy.getInvocationHandler(ps).getClass().getName());
        return ps;
    }

    private static SqlEvent staleEvent(String sql) {
        final SqlEvent e = new SqlEvent();
        e.sql = sql;
        e.timestamp = System.currentTimeMillis();
        e.threadName = Thread.currentThread().getName();
        return e;
    }

    private static final class Frame {
        final int cmd;
        final String payload;

        Frame(int cmd, String payload) {
            this.cmd = cmd;
            this.payload = payload;
        }
    }

    /** 读下一帧（跳过其它命令），budgetMs 内没有帧则返回 null。 */
    private static Frame awaitFrame(DataInputStream in, long budgetMs) throws IOException {
        final long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            final Frame f = tryReadFrame(in);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** 读下一帧并要求命令匹配（不匹配继续等），budgetMs 内没有匹配帧则返回 null。 */
    private static Frame awaitFrame(DataInputStream in, int cmd, long budgetMs) throws IOException {
        final long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            final Frame f = tryReadFrame(in);
            if (f != null && f.cmd == cmd) {
                return f;
            }
        }
        return null;
    }

    /** 窗口内不得出现事件帧；同时要求窗口内确有帧到达（证明通道活着，不是「连接死了所以没事件」）。 */
    private static void assertNoEventFrame(DataInputStream in, long windowMs) throws IOException {
        final long deadline = System.currentTimeMillis() + windowMs;
        int frames = 0;
        while (System.currentTimeMillis() < deadline) {
            final Frame f = tryReadFrame(in);
            if (f == null) {
                continue;
            }
            frames++;
            assertFalse("停采态下不得产出事件帧（收到 EVENT：" + f.payload + "）", f.cmd == EventBus.CMD_EVENT);
        }
        assertTrue("窗口内应持续有帧（心跳）证明通道存活，实测 " + frames + " 帧", frames > 0);
    }

    private static Frame tryReadFrame(DataInputStream in) throws IOException {
        try {
            final int len = in.readInt();
            final int cmd = in.readUnsignedByte();
            final byte[] body = new byte[len];
            in.readFully(body);
            return new Frame(cmd, new String(body, java.nio.charset.StandardCharsets.UTF_8));
        } catch (SocketTimeoutException noData) {
            return null;
        }
    }

    private static void restore(String key, String prev) {
        if (prev == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prev);
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
            }
        }
    }
}
