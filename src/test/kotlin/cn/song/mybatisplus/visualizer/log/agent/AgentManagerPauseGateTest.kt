package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.testutil.AlreadyDisposedDiagnostics
import cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport
import cn.song.mybatisplus.visualizer.testutil.SqlLogManagerProjectState
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * R53（V80②③）：用户暂停增强捕获的协议/状态机守护测试。
 *
 * 三条核心语义（按「暂停只停 agent 通道、控制台通道照常」最简口径）：
 *  1. **暂停≠关闭**：暂停只丢 agent 事件入列表，连接/心跳/CONFIG ACK 链一概不动——
 *     会话照常收发帧，恢复后照常续采；
 *  2. **桥不封**：暂停期间 [SqlCaptureGate.paused] 强制 false——控制台通道照常解析兜底，
 *     双通道不并存（agent 侧已丢，无重复条目）；
 *  3. **三态不冲突（V80③）**：暂停不动总开关/确认态/拒绝冷却，只动 userPaused；
 *     关开关（[shutdownAll]）复位暂停，不留「暂停中但开关已关」的悬空态。
 *
 * 会话构造照 `AgentManagerIntakeGateTest` 惯例：loopback 真 socket 对 + 注入时钟。
 */
class AgentManagerPauseGateTest : BasePlatformTestCase() {

    private var serviceState: SqlLogManagerProjectState.Snapshot? = null
    private var settingsSnapshot: PluginSettings.State? = null
    private var now = 5_000_000L
    private val sockets = mutableListOf<java.io.Closeable>()
    private val freshManagers = mutableListOf<AgentManager>()
    private val agentSide = mutableMapOf<Long, Socket>()

    override fun setUp() {
        super.setUp()
        serviceState = SqlLogManagerProjectState.capture(logManager())
        settingsSnapshot = PluginSettingsTestSupport.snapshot()
        now = 5_000_000L
        SqlCaptureGate.paused.set(false)
    }

    override fun tearDown() {
        freshManagers.forEach { runCatching { Disposer.dispose(it) } }
        freshManagers.clear()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        agentSide.clear()
        SqlCaptureGate.paused.set(false)
        PluginSettingsTestSupport.restore(settingsSnapshot!!)
        SqlLogManagerProjectState.restore(serviceState)
        super.tearDown()
    }

    // ---------- 夹具（照 AgentManagerIntakeGateTest） ----------

    private fun logManager(): SqlLogManager =
        AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "pause.fixture")

    private fun freshManager(): AgentManager = AgentManager(project).also {
        it.clockMs = { now }
        freshManagers += it
    }

    private fun newSession(pid: Long, manager: AgentManager? = null): AgentSession {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        sockets += client
        sockets += accepted
        sockets += server
        agentSide[pid] = client
        return AgentSession(
            project,
            pid,
            accepted,
            onStateChange = { manager?.onCaptureConfirmationChanged() },
            clockMs = { now },
            onCaptureConfirmationChanged = { manager?.onCaptureConfirmationChanged() },
        )
    }

    private fun writeFrame(pid: Long, cmd: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = DataOutputStream(agentSide.getValue(pid).getOutputStream())
        out.writeInt(bytes.size)
        out.write(cmd)
        out.write(bytes)
        out.flush()
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        fail("等待超时：$what")
    }

    private fun eventBody(sql: String): String = """{"sql":"$sql","ts":1}"""

    // ---------- ① 暂停≠关闭：桥闸门 ----------

    /** 用户暂停 → 桥不封（控制台通道照常）；恢复 → 桥重新封上（agent 接管）。 */
    fun test用户暂停_桥不封_恢复后桥重新封上() {
        val manager = freshManager()
        val session = newSession(8601L, manager)
        manager.sessions.add(session)
        writeFrame(8601L, AgentProtocol.CMD_CONFIG_ACK, AgentProtocol.configAckBody(false, true, 0, 0))
        awaitTrue("前置：确认在采集后桥应让位") { SqlCaptureGate.paused.get() }

        manager.setUserPaused(true)
        assertTrue("前置：暂停已置位", manager.userPaused)
        assertFalse("用户暂停 → 桥不得封（暂停只停 agent 通道、控制台通道照常）", SqlCaptureGate.paused.get())

        manager.setUserPaused(false)
        awaitTrue("恢复后桥应重新封上（agent 重新接管）") { SqlCaptureGate.paused.get() }
    }

    // ---------- ① 暂停≠关闭：事件入队闸门 ----------

    /** 暂停态：agent 事件丢弃不入列表，但照常计入「已收事件」（暂停≠通道断），且暂停丢弃单独计数。 */
    fun test用户暂停_事件丢弃不入队但计收到() {
        val manager = logManager()
        manager.entries.paused = false
        manager.setCapturingForTest(true)
        val session = newSession(8602L)
        session.listenActiveProvider = { true }
        session.pausedProvider = { true }
        val before = manager.entries.size()
        val beforeReceived = AgentEventAdapter.receivedEvents()

        writeFrame(8602L, AgentProtocol.CMD_EVENT, eventBody("select paused_1"))

        awaitTrue("暂停态事件应被丢弃并计数") { session.pausedDropped.get() == 1L }
        assertEquals("暂停态事件不得入列表", before, manager.entries.size())
        assertEquals("暂停态事件仍应计为「已收」（暂停≠通道断）", beforeReceived + 1, AgentEventAdapter.receivedEvents())
        assertEquals("暂停丢弃不得计进入队闸门丢弃（语义分开）", 0L, session.intakeDropped.get())
    }

    /** 恢复后：agent 事件照常入队续采。 */
    fun test恢复后_事件照常入队() {
        val manager = logManager()
        manager.entries.paused = false
        manager.setCapturingForTest(true)
        val session = newSession(8603L)
        session.listenActiveProvider = { true }
        session.pausedProvider = { false }
        val before = manager.entries.size()

        writeFrame(8603L, AgentProtocol.CMD_EVENT, eventBody("select resumed_1"))

        awaitTrue("恢复后事件应照常入队") { manager.entries.size() == before + 1 }
        assertEquals("恢复后不得计暂停丢弃", 0L, session.pausedDropped.get())
    }

    // ---------- ② V80③ 状态机：暂停与总开关正交 ----------

    /** 暂停只动 userPaused，不动总开关（T-EnhancedCaptureDefaultOn：确认/冷却语义已移除）。 */
    fun test暂停_不动总开关() {
        val manager = freshManager()
        val state = PluginSettings.getInstance().state
        val enabledBefore = state.enhancedCaptureEnabled

        manager.setUserPaused(true)

        assertEquals("暂停不得动总开关", enabledBefore, state.enhancedCaptureEnabled)
    }

    /** 关开关（shutdownAll）复位用户暂停——不残留「暂停中但开关已关」的悬空态。 */
    fun test关开关_复位用户暂停() {
        val manager = freshManager()
        manager.setUserPaused(true)
        assertTrue("前置：暂停已置位", manager.userPaused)

        manager.shutdownAll()

        assertFalse("关开关应复位用户暂停（V80③：总开关关 > 暂停）", manager.userPaused)
    }

    /**
     * T-CaptureGateStability P1（2026-11-05 真机实测）：CONFIG 下发**不得**把确认态清回「待确认」。
     *
     * 原口径下发即清空，于是「清空 → 回执到达」之间开出一段桥放行窗（真机实测 2ms~1.25s）；
     * 周期性 CONFIG 下发（配置根本没变）每轮都会触发，控制台桥在窗内完整解析一轮并与 agent
     * 各产一条。保留旧确认值后：配置未变无窗，配置真变了窗口内按旧值封桥（安全的一侧）。
     */
    fun testConfig下发不清确认态_闸门保持封停() {
        val manager = freshManager()
        val session = newSession(8611L, manager)
        manager.sessions.add(session)
        writeFrame(8611L, AgentProtocol.CMD_CONFIG_ACK, AgentProtocol.configAckBody(false, true, 0, 0))
        awaitTrue("前置：确认在采集后桥应让位") { SqlCaptureGate.paused.get() }

        // 再下发一次（配置未变）——确认态必须保留，闸门不得放开
        session.sendConfig(disabled = false, desensitize = false)
        assertTrue("CONFIG 下发后闸门仍应封停（不清回待确认）", SqlCaptureGate.paused.get())
        assertEquals("确认态保持 false 而非 null", false, session.configAckDisabled)
    }
}
