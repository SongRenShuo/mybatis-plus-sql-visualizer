package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * T-JRebelPremainCrash §5.2「降级后仍可 attach」。
 *
 * <p>计划表原文判据：*模拟 premain 失败 → 触发 attach 路径 → {@code sqlvis.agent.active} 未被写入，
 * attach 成功建会话*。此前只验了「降级不写 active」这一半（agent 侧
 * {@code PremainFailsafeBehaviorTest.降级后不得写active标记}），**「attach 真能建会话」从未验过**
 * ——本类补上后一半。
 *
 * <p>为什么这半很重要：A 案降级的前提假设是「不写标记 ⇒ IDE 侧 attach 兜底仍可工作」。若降级后
 * attach 链因某种耦合（例如 premain 通道残留把 attach 挡掉）而不通，那么修复就只是把「启动失败」
 * 换成了「静默无捕获」，问题并未解决。本类用真实 loopback 会话证明兜底确实通。
 */
class AgentManagerAttachAfterDegradeTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager
    private var fakeNow = 2_000_000L
    private val sockets = mutableListOf<java.io.Closeable>()
    private var originalFactory: ((Long) -> AgentSession?)? = null
    private var originalDispatcher: ((Runnable) -> Unit)? = null
    private var originalDebounceMs: Long = 0L
    private var originalInspector: ((Long, Any?, Boolean) -> AgentAttachTarget.Decision)? = null

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        fakeNow = 2_000_000L
        manager.clockMs = { fakeNow }
        // AgentManager 是项目级服务，实例会跨测试类复用——凡改写可变缝必须在 tearDown 还原，
        // 否则会把「同步派发/零去抖」泄漏给后续测试类（实测曾致 4 个既有用例误红）。
        originalFactory = manager.attachSessionFactory
        originalDispatcher = manager.attachTaskDispatcher
        originalDebounceMs = manager.attachDebounceMs
        originalInspector = manager.attachTargetInspector
        manager.premainDegradedNotifiedThisSession.set(false)
        manager.attachFallbackNotifiedThisSession.set(false)
        PluginSettings.getInstance().state.enhancedCaptureEnabled = true
    }

    override fun tearDown() {
        try {
            originalFactory?.let { manager.attachSessionFactory = it }
            originalDispatcher?.let { manager.attachTaskDispatcher = it }
            manager.attachDebounceMs = originalDebounceMs
            originalInspector?.let { manager.attachTargetInspector = it }
            manager.shutdownAll()
            sockets.forEach { runCatching { it.close() } }
            PluginSettings.getInstance().state.enhancedCaptureEnabled = false
            manager.clockMs = { System.currentTimeMillis() }
        } finally {
            super.tearDown()
        }
    }

    /** 建一条真实 loopback 会话（AgentSession 需要真 socket 才能起读循环）。 */
    private fun newSession(pid: Long): AgentSession {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        sockets += client
        sockets += accepted
        sockets += server
        return AgentSession(project, pid, accepted, onStateChange = {}, clockMs = { fakeNow })
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        fail("等待超时：$what")
    }

    private fun writeFrame(socket: Socket, cmd: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = DataOutputStream(socket.getOutputStream())
        out.writeInt(bytes.size)
        out.writeByte(cmd)
        out.write(bytes)
        out.flush()
    }

    /**
     * 核心：premain 降级（通道过窗未认领）之后，attach 路径必须**仍能建立会话**。
     */
    fun testpremain降级后attach仍能建会话() {
        // ① 造出 premain 降级态：开通道但永不回连，时间推过抑制窗
        val port = manager.openPremainChannel()
        assertTrue("通道未打开（用例无意义）", port != null && port > 0)
        fakeNow += AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1
        assertTrue("前置：应已判定为 premain 降级", manager.detectPremainDegraded())

        // ② 注入 attach 会话工厂（计数器 + 真会话），走 attach 主路径
        val pid = 8_100_001L
        val attachCalls = AtomicInteger()
        manager.attachSessionFactory = { p ->
            attachCalls.incrementAndGet()
            if (p == pid) newSession(p) else null
        }

        val attached = manager.attachToPid(pid)

        assertTrue("降级后 attach 必须成功建会话（否则修复只是把「启动失败」换成「静默无捕获」）", attached)
        assertEquals("attach 应恰被调用一次", 1, attachCalls.get())
        assertTrue("会话表里应有一个会话", manager.sessions.isNotEmpty())
        assertEquals("会话 pid 应为目标 pid", pid, manager.sessions.first().pid)
        assertTrue("降级后 attach 成功，在线态应为 true", manager.online)
    }

    /**
     * 降级态下 attach 会话能消费 agent 侧写入的帧（证明读循环真的在跑，不是只把对象放进表里）。
     */
    fun test降级后attach会话读循环在工作() {
        val port = manager.openPremainChannel()
        assertNotNull(port)
        fakeNow += AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1
        manager.detectPremainDegraded()

        val pid = 8_100_002L
        var sessionRef: AgentSession? = null
        manager.attachSessionFactory = { p ->
            if (p == pid) newSession(p).also { sessionRef = p.let { _ -> it } } else null
        }
        assertTrue("降级后 attach 应成功", manager.attachToPid(pid))
        val session = sessionRef
        org.junit.Assert.assertNotNull("会话应已建立", session)

        // 写一帧 HELLO，等会话把它消费掉：读循环对所有入站帧都刷新 lastSeen，
        // 故 lastSeen 前移即证明「会话的读循环真的在跑」，不是只把对象放进表里。
        val before = session!!.lastSeen
        fakeNow += 1_000
        val clientSide = sockets.filterIsInstance<Socket>().first()
        writeFrame(clientSide, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")

        awaitTrue("会话读循环未消费 HELLO（lastSeen 未前移）") {
            session.lastSeen > before
        }
        assertFalse("握手后连接不应被关闭", clientSide.isClosed)
    }

    /**
     * 负对照：降级判定**不阻断** attach——即使提示已发过，attach 也不受影响。
     *
     * <p>反向验证：把 `detectPremainDegraded()` 改成 `return true` 提前退出 `doAttachAsync`，
     * 本用例会因「attach 未被调用」而红。
     */
    fun test降级提示不阻断attach链路() {
        val counter = AtomicInteger()
        manager.premainDegradedNotifier = { counter.incrementAndGet() }
        val port = manager.openPremainChannel()
        assertNotNull(port)
        fakeNow += AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1
        manager.detectPremainDegraded()
        assertEquals("降级提示应已发一次", 1, counter.get())

        // 再判一次（不应重复提示），且不得影响 attach
        manager.detectPremainDegraded()
        assertEquals("提示不得重复", 1, counter.get())

        val pid = 8_100_003L
        val attachCalls = AtomicInteger()
        manager.attachSessionFactory = { p -> attachCalls.incrementAndGet(); null }
        manager.attachToPid(pid)
        assertEquals("降级判定（含重复判定）不得吞掉 attach 调用", 1, attachCalls.get())
    }

    /** 降级态的通道仍在表内时，attach 也不得因它而失败（通道只是监听，不占 attach 路径）。 */
    fun test通道仍在表内时attach不受阻() {
        val port = manager.openPremainChannel()
        assertNotNull(port)
        fakeNow += AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1

        val pid = 8_100_004L
        manager.attachSessionFactory = { p -> if (p == pid) newSession(p) else null }
        assertTrue("通道过窗未认领不应阻断 attach", manager.attachToPid(pid))
        assertFalse("降级通道不应被误判为已认领", manager.premainChannels.any { it.claimed })
    }

    /**
     * **关键用例**：走真实入口 `onProcessAppeared` → `doAttachAsync`（而不是直调 `attachToPid`）。
     *
     * <p>为什么必须走这条：降级判定 `detectPremainDegraded()` 接在 `doAttachAsync` 内、premain
     * 跳过分支之后。直调 `attachToPid` 会**绕过**该归一化点——反向验证时把判定改成
     * `if (detectPremainDegraded()) return`（即「降级就吞掉 attach」的缺陷形态）后，直调用例
     * 依然全绿，测不出问题。本用例补上这条路，保证缺陷形态能被捕获。
     */
    fun test真实入口_降级后onProcessAppeared仍能挂载() {
        // 降级态：开通道、过抑制窗
        val port = manager.openPremainChannel()
        assertNotNull(port)
        fakeNow += AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1

        val pid = 8_100_005L
        val attachCalls = AtomicInteger()
        // 只统计**目标 pid** 的工厂调用：AgentManager 是项目级服务，同 JVM 内其他测试类
        // 排队的迟到任务（真实 dispatcher 上的 3s 静默等待）会带着自己的 pid 打到本类的
        // 工厂缝上——计全量会把别人的调用算成本用例的，实测同包全跑时稳定 +1 误红。
        val probedPids = java.util.Collections.synchronizedList(mutableListOf<Long>())
        // 让身份探测放行、去抖窗归零、任务同步执行——把链路收束到「判定 → 排队 → attach」
        manager.attachTargetInspector = { _, _, _ -> AgentAttachTarget.Decision.APPLICATION }
        manager.attachDebounceMs = 0L
        manager.attachTaskDispatcher = { it.run() }
        manager.attachSessionFactory = { p ->
            probedPids.add(p)
            if (p == pid) {
                attachCalls.incrementAndGet()
                newSession(p)
            } else {
                null
            }
        }

        manager.onProcessAppeared(FakeProcessHandler(pid))

        assertEquals(
            "降级后真实入口必须仍走到 attach（把判定改成提前 return 即红）；" +
                "目标 pid 调用次数；观测到的全部 pid=$probedPids",
            1, attachCalls.get(),
        )
        assertTrue("会话应已建立", manager.sessions.isNotEmpty())
        assertTrue("降级后挂上仍应在线", manager.online)
    }

    private class FakeProcessHandler(private val nativePid: Long) {
        fun getNativePid(): Long = nativePid
    }

    private fun assertNotNull(msg: String, v: Any?): Any? {
        org.junit.Assert.assertNotNull(msg, v); return v
    }
}
