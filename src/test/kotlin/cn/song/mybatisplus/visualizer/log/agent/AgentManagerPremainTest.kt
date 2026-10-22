package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * T-M56Phase2-Premain：端口前置协商、回连建会话、幂等与 attach 跳过（T-M56P2-05）。
 *
 * 覆盖 headless 可验的部分——真实 `-javaagent` 注入与 premain 阶段装载必须 runIde 真机
 * （见计划 §五 走查清单），本类只钉住 IDE 侧自己的行为：闸门、端口、回连、去重、跳过。
 */
class AgentManagerPremainTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = false
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = 0
        manager.clockMs = { System.currentTimeMillis() }
    }

    override fun tearDown() {
        try {
            manager.shutdownAll()
            val state = PluginSettings.getInstance().state
            state.enhancedCaptureEnabled = false
            state.enhancedCaptureConfirmed = false
            state.enhancedCaptureRejectedAtMs = 0
            manager.clockMs = { System.currentTimeMillis() }
        } finally {
            super.tearDown()
        }
    }

    // ---------- 闸门 ----------

    fun test未开启增强捕获_不注入不协商端口() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = false
        state.enhancedCaptureConfirmed = true
        assertNull("未开启时不得注入", manager.preparePremainVmOption())
        assertTrue("未开启时不得协商端口", manager.premainChannels.isEmpty())
        assertFalse(SqlCaptureGate.paused.get())
    }

    /** T-EnhancedCaptureDefaultOn：旧 XML 残留 confirmed=false 不得再拦 premain。 */
    fun test已开启_旧未确认态也注入() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        assertNotNull("开关打开即注入，confirmed 不再参与判定", manager.preparePremainVmOption())
        manager.shutdownAll()
    }

    // ---------- 注入产物 ----------

    fun test已确认_注入_端口协商与jar落盘同在位() {
        enableAndConfirm()
        val option = manager.preparePremainVmOption()
        assertNotNull("已确认应产出 -javaagent VM 选项", option)
        assertTrue(option!!.startsWith("-javaagent:"))
        val port = portOf(option)
        assertTrue("端口应为可用 loopback 端口", port > 0)
        assertEquals(1, manager.premainChannels.size)
        assertEquals(port, manager.premainChannels[0].port)
        assertTrue("通道在场时应压制 attach", manager.hasPendingPremainChannel())

        // jar 必须在进程启动前就已落盘且非空（-javaagent 由目标 JVM 启动时读；读不到会让 JVM 终止）
        val jarPath = option.removePrefix("-javaagent:").substringBefore("=")
        val jar = File(jarPath)
        assertTrue("premain agent jar 未落盘：$jarPath", jar.isFile)
        assertTrue("premain agent jar 为空：$jarPath", jar.length() > 0L)
        // 落盘形态即「不被 deleteOnExit 提前清掉」的实现口径：插件临时目录 + 内容定名（非随机临时名）
        assertEquals("premain jar 应落在插件临时目录的 mpsv-agent 子目录", "mpsv-agent", jar.parentFile?.name)
        assertTrue(
            "premain jar 名应为内容定名的 mps-sql-agent-<crc>.jar，实际 ${jar.name}",
            Regex("""mps-sql-agent-\d+\.jar""").matches(jar.name),
        )
        // 重复准备复用同一路径（内容定名），不重复落盘
        val again = manager.preparePremainVmOption()!!
        assertEquals(jarPath, again.removePrefix("-javaagent:").substringBefore("="))
    }

    fun test挂起通道达上限_拒绝继续协商() {
        enableAndConfirm()
        val options = (1..AgentManager.MAX_PREMAIN_CHANNELS).map { manager.preparePremainVmOption() }
        assertTrue("上限内每次准备都应成功", options.all { it != null })
        assertNull("超上限应拒绝（防重复准备泄漏 ServerSocket）", manager.preparePremainVmOption())
        assertEquals(AgentManager.MAX_PREMAIN_CHANNELS, manager.premainChannels.size)
    }

    // ---------- 回连 ----------

    fun test回连_建会话_写幂等表_置在线态() {
        enableAndConfirm()
        val option = manager.preparePremainVmOption()!!
        val port = portOf(option)
        val pid = 424242L
        val socket = Socket("127.0.0.1", port)
        writeFrame(socket, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")

        awaitTrue("premain 会话未建立") { manager.sessions.isNotEmpty() }
        // sessions 先入表、updateOnline/publishStatusChanged 随后，故在线态各自等一次
        awaitTrue("回连后在线态未置位") { manager.online }
        // T-AgentReconnect T-03：通道不再「accept 一次即摘除」——认领后转入等重连（绑定到本次运行），
        // 由运行结束信号/pid 消亡/TTL 回收。旧断言（通道被摘除）即 30 秒摘除后无法恢复的根因形态。
        awaitTrue("通道未认领（应留在表内等重连）") { manager.premainChannels.size == 1 && manager.premainChannels[0].claimed }
        assertEquals(pid, manager.sessions[0].pid)
        assertTrue("回连后 pid 应写入幂等表（attach 链据此跳过）", manager.attachedPids.containsKey(pid))
        assertEquals("已挂载 pid=$pid", manager.statusSummary())
        assertFalse("通道认领后不再压制 attach", manager.hasPendingPremainChannel())
        socket.close()
    }

    fun test同pid二次回连被丢弃_会话不叠加() {
        enableAndConfirm()
        val first = portOf(manager.preparePremainVmOption()!!)
        val second = portOf(manager.preparePremainVmOption()!!)
        val pid = 515151L
        val s1 = Socket("127.0.0.1", first)
        writeFrame(s1, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")
        awaitTrue("首条 premain 会话未建立") { manager.sessions.size == 1 }

        val s2 = Socket("127.0.0.1", second)
        s2.soTimeout = 3_000
        writeFrame(s2, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")
        // 服务端应主动关闭重复连接（客户端读到 EOF）
        assertEquals("重复回连应被服务端关闭", -1, DataInputStream(s2.getInputStream()).read())
        assertEquals("同 pid 只允许一条会话", 1, manager.sessions.size)
        assertEquals(1, manager.attachedPids.size)
    }

    // ---------- T-AgentReconnect T-03：会话摘除后的重连（通道绑定到本次运行） ----------

    /**
     * 现场根因回归：会话被摘除（心跳窗口过期 → 巡检摘除 + 幂等表摘条目）后，agent 侧 `SenderLoop`
     * 无限重连同一端口——旧实现 accept 一次即关通道，重连撞在已关端口上（connection refused），
     * 该次运行永久失去增强通道。本用例断言重连能重建会话并恢复在线态。
     */
    fun test会话被摘除后同pid回连_自动恢复在线() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val pid = 636_001L
        val first = Socket("127.0.0.1", port)
        writeFrame(first, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")
        awaitTrue("首条 premain 会话未建立") { manager.sessions.size == 1 }
        awaitTrue("首条会话未置在线态") { manager.online }

        // 模拟「被摘除」：会话关闭 + 巡检摘除（心跳窗口过期的现场形态，同 pid 幂等表条目一并清掉）
        manager.sessions[0].close()
        manager.sweepOffline()
        assertTrue("摘除后不应残留会话", manager.sessions.isEmpty())
        assertFalse("摘除后应回落离线", manager.online)

        // 同一 pid 再次回连（agent 侧 SenderLoop 无限重连同一端口，断线退避 1s→5s）
        val second = Socket("127.0.0.1", port)
        writeFrame(second, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")
        awaitTrue("摘除后重连未重建会话（通道是否 accept 一次即关？）") { manager.sessions.size == 1 }
        awaitTrue("重连后在线态未恢复") { manager.online }
        assertEquals("重连应复用同一 pid", pid, manager.sessions[0].pid)
        assertTrue("重连后幂等表应重新登记", manager.attachedPids.containsKey(pid))
        assertEquals("已挂载 pid=$pid", manager.statusSummary())
        second.close()
        first.close()
    }

    /** 反复摘除/重连多轮：同一通道持续服役，通道表与会话表规模都不随轮次增长。 */
    fun test重复多轮回连_通道与会话规模有界() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val pid = 636_002L
        repeat(10) { i ->
            val socket = Socket("127.0.0.1", port)
            writeFrame(socket, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")
            awaitTrue("第 ${i + 1} 轮回连未建立会话") { manager.sessions.size == 1 }
            manager.sessions[0].close()
            manager.sweepOffline()
            assertTrue("第 ${i + 1} 轮后会话未清空", manager.sessions.isEmpty())
            assertEquals(
                "第 ${i + 1} 轮后 premain 通道数应恒为 1（同一通道服务全部重连）",
                1, manager.premainChannels.size,
            )
            socket.close()
        }
        assertTrue(
            "通道数不得随重连轮次增长（MAX_PREMAIN_CHANNELS=${AgentManager.MAX_PREMAIN_CHANNELS} 不被撞到）",
            manager.premainChannels.size < AgentManager.MAX_PREMAIN_CHANNELS,
        )
    }

    /**
     * 运行结束信号（生产链路：`SqlLogManager.onConsoleRemoved` → [AgentManager.onRunEnded]）关闭该轮通道，
     * 连续多次运行不累积通道——否则第 `MAX_PREMAIN_CHANNELS` 次运行起 premain 注入会被上限拒绝。
     */
    fun test运行结束关闭本轮通道_连续多次运行不累积() {
        enableAndConfirm()
        val runs = AgentManager.MAX_PREMAIN_CHANNELS + 3
        repeat(runs) { i ->
            val option = manager.preparePremainVmOption()
            assertNotNull("第 ${i + 1} 次运行应仍能协商端口（通道未随运行次数累积）", option)
            val port = portOf(option!!)
            val channel = manager.premainChannels[0]
            val socket = Socket("127.0.0.1", port)
            writeFrame(socket, AgentProtocol.CMD_HELLO, """{"pid":${636_100L + i}}""")
            awaitTrue("第 ${i + 1} 次运行的 premain 会话未建立") { manager.sessions.size == 1 }

            manager.onRunEnded()
            awaitTrue("第 ${i + 1} 次运行结束后通道未回收") { manager.premainChannels.isEmpty() }
            assertTrue("运行结束信号应关闭通道（该轮不再等重连）", channel.closed)

            manager.sessions.toList().forEach { it.close() }
            manager.sweepOffline()
            assertTrue("第 ${i + 1} 次运行结束后会话未清空", manager.sessions.isEmpty())
            socket.close()
        }
    }

    /** 回连次数达上限后通道退休（防异常抖动下的 accept 热循环）。 */
    fun test回连次数达上限_通道退休() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val pid = 636_003L
        repeat(AgentManager.MAX_PREMAIN_RECONNECTS) { i ->
            Socket("127.0.0.1", port).use { socket ->
                writeFrame(socket, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")
                awaitTrue("第 ${i + 1} 轮回连未建立会话") { manager.sessions.size == 1 }
                manager.sessions[0].close()
                manager.sweepOffline()
            }
        }
        awaitTrue("回连次数达上限后通道未回收") { manager.premainChannels.isEmpty() }
        // 通道已关：端口不再接受连接（agent 侧后续回连 connection refused，需重新 Run 注入）
        try {
            Socket("127.0.0.1", port).use { fail("通道退休后不应再接受连接") }
        } catch (expected: java.io.IOException) {
            // 预期路径：ConnectException（connection refused）
        }
    }

    // ---------- attach 跳过与窗口失效 ----------

    /**
     * T-PerfSweep6 T-PS6-06：去重判据须为 [AgentManager] 的 `isSessionHealthy`（socket 未断**且**
     * 心跳窗口内有过帧），而非只判 socket 未断的 `alive`。
     *
     * 缺陷面：半死会话（应用侧停摆、连接还在）会把同 pid 的**真重连**挡下一次，而挡下的这一次
     * 恰恰是「应用重启/卡死恢复」场景里最需要的那一次；半死会话随后仍会被巡检摘除，等于白挡。
     *
     * 判据形态：去重若命中，服务端会**主动关闭**本次连接（客户端读到 EOF）。故读到的首字节为 -1
     * 即「被当成重复连接丢弃」；非 -1（服务端写了 CONFIG 帧）或读超时（连接仍在服务端手里）都表示放行。
     */
    fun test半死会话语境下同pid重连放行() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val pid = 717_001L
        // 可控时钟：把会话建好后再推过心跳窗口，制造「alive 但心跳过期」的半死会话
        var now = 2_000_000L
        manager.clockMs = { now }
        manager.heartbeatWindowMs = 30_000L

        val sweepsBefore = manager.fallbackSweepCount
        val first = Socket("127.0.0.1", port)
        writeFrame(first, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")
        // **必须等处理线程越过兜底清扫**（T-PS6-06③ 复核订正）：处理线程在 `sessions.add` 之后才调
        // `ensureMonitorStarted`，其末尾有一次**同步** `sweepOffline()`。若只等 `sessions.size == 1`
        // 就推进假时钟，这条刚建好的会话会被那次兜底清扫摘除 → 第二次连接的去重分支根本不发生 →
        // 用例在旧判据（`alive`）下也恒绿（复核实测：退回 `alive` 连跑 3 次得 fail/fail/pass）。
        // 等 `fallbackSweepCount` 增长即证明那次清扫已执行完（它在 `sessions.add` 之后，故不早于会话入表）。
        awaitTrue("首条 premain 会话未建立或处理线程尚未越过兜底清扫") {
            manager.sessions.size == 1 && manager.fallbackSweepCount > sweepsBefore
        }
        assertEquals("首条会话 pid", pid, manager.sessions[0].pid)

        // 推进时钟越窗：会话进入「alive 但心跳过期」——本用例**不**调 sweepOffline（保持半死在表内）
        now += 60_000L
        assertTrue("前置：会话 socket 未断（半死形态）", manager.sessions[0].alive)
        assertTrue(
            "前置：半死会话仍须在表内——被巡检摘除则本次去重判定根本不发生，用例失去判别力",
            manager.sessions.any { it.pid == pid },
        )

        val second = Socket("127.0.0.1", port)
        second.soTimeout = 1_500
        writeFrame(second, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")
        val firstByte = try {
            DataInputStream(second.getInputStream()).read()
        } catch (e: java.net.SocketTimeoutException) {
            0 // 服务端既未写帧也未关连接 = 连接被正常接收
        }
        assertTrue(
            "半死会话语境下同 pid 重连必须放行——首字节 $firstByte 为 -1 表示服务端按重复连接关闭了它" +
                "（去重判据仍是 alive 而非 isSessionHealthy）",
            firstByte != -1,
        )
        // 巡检懒启的兜底清扫可能已把半死会话摘掉，故只断言「有该 pid 的会话在场」
        awaitTrue("重连后应有该 pid 的活动会话") { manager.sessions.any { it.pid == pid } }
        first.close()
        second.close()
    }

    fun testpremain通道在场_attach不排队() {
        enableAndConfirm()
        manager.preparePremainVmOption()
        assertTrue(manager.hasPendingPremainChannel())
        manager.onProcessAppeared(FakeProcessHandler(nativePid = 777_001L))
        assertTrue("premain 在场时不得入 attach 排队表", manager.pendingPids.isEmpty())
        assertFalse("premain 在场时不得 attach", manager.attachedPids.containsKey(777_001L))
    }

    fun test跳过窗失效后_attach恢复排队() {
        enableAndConfirm()
        val base = 1_000_000L
        manager.clockMs = { base }
        manager.preparePremainVmOption()
        assertTrue(manager.hasPendingPremainChannel())
        // 推到跳过窗之外（仍在 accept 超时内）：通道还活着但不再压制 attach
        manager.clockMs = { base + AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1 }
        assertFalse("跳过窗外的通道不再压制 attach", manager.hasPendingPremainChannel())
        assertEquals("窗口外通道仍在等回连", 1, manager.premainChannels.size)
        manager.onProcessAppeared(FakeProcessHandler(nativePid = 777_002L))
        assertTrue("窗口外 attach 应恢复排队", manager.pendingPids.containsKey(777_002L))
    }

    // ---------- HELLO 首帧读取与交接 ----------

    fun test读HELLO取pid_非HELLO与畸形容错() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket("127.0.0.1", server.localPort).use { client ->
                server.accept().use { accepted ->
                    writeFrame(client, AgentProtocol.CMD_HELLO, """{"pid": 9876}""")
                    assertEquals(9876L, manager.readHelloPid(accepted))
                }
            }
            Socket("127.0.0.1", server.localPort).use { client ->
                server.accept().use { accepted ->
                    writeFrame(client, AgentProtocol.CMD_HEARTBEAT, "{}")
                    assertEquals("非 HELLO 帧按 pid 未知处理", 0L, manager.readHelloPid(accepted))
                }
            }
        }
    }

    fun test读HELLO后剩余帧不被吞掉() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket("127.0.0.1", server.localPort).use { client ->
                server.accept().use { accepted ->
                    writeFrame(client, AgentProtocol.CMD_HELLO, """{"pid":1234}""")
                    writeFrame(client, AgentProtocol.CMD_EVENT, """{"sql":"select 1"}""")
                    assertEquals(1234L, manager.readHelloPid(accepted))
                    // DataInputStream 无预读：随后交给 AgentSession 的读线程仍能取到 EVENT 帧
                    val input = DataInputStream(accepted.getInputStream())
                    val len = input.readInt()
                    assertEquals(AgentProtocol.CMD_EVENT, input.readUnsignedByte())
                    val body = ByteArray(len)
                    input.readFully(body)
                    assertEquals("""{"sql":"select 1"}""", String(body, Charsets.UTF_8))
                }
            }
        }
    }

    // ---------- 辅助 ----------

    private fun enableAndConfirm() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = true
    }

    private fun portOf(vmOption: String): Int =
        Regex("port=(\\d+)").find(vmOption)?.groupValues?.get(1)?.toInt()
            ?: error("VM 选项里没有 port：$vmOption")

    private fun writeFrame(socket: Socket, cmd: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = DataOutputStream(socket.getOutputStream())
        out.writeInt(bytes.size)
        out.writeByte(cmd)
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

    /** 反射缝假 ProcessHandler：只暴露 getNativePid（premain 测试只需「pid 可解析」这一条）。 */
    private class FakeProcessHandler(private val nativePid: Long? = null) {
        fun getNativePid(): Long? = nativePid
    }
}
