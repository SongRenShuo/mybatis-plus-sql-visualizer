package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * T-SAF-02（agent-ipc-loopback-unauth-session-trust）：agent 通道双向令牌认证回归。
 *
 * 信任边界（审计 architecture.md「本地进程 ↔ IDE agent 通道」）：loopback 绑定 + 1MB 帧上限
 * 不是对端认证——本机任意进程都可连上 premain/attach 端口，凭自报 HELLO pid 换取完整会话权限
 * （注入伪造 SQL 事件、翻转 CONFIG_ACK 桥闸门、投毒 attachedPids、端口抢占窃听 agent 帧）。
 * 修复语义：IDE 侧每通道生成随机令牌（随 agentArgs 下发），agent 在 HELLO 帧回带；
 * IDE 在**提交任何会话之前**校验令牌，无令牌/不匹配的连接一律拒绝（fail-closed）。
 *
 * 复现证据（NEEDS-VALIDATION #2 local 计划的两进程变体的进程内形态）：修复前，无凭据的
 * 裸 socket 写一帧 `{"pid":<选定>}` HELLO 即被接受为完整会话（AgentManagerPremainTest
 * 既有用例正是这么写的）——前两个用例在修复前转红。
 */
class AgentManagerChannelAuthTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        PluginSettings.getInstance().state.enhancedCaptureEnabled = false
        manager.clockMs = { System.currentTimeMillis() }
    }

    override fun tearDown() {
        try {
            manager.shutdownAll()
            PluginSettings.getInstance().state.enhancedCaptureEnabled = false
            manager.clockMs = { System.currentTimeMillis() }
        } finally {
            super.tearDown()
        }
    }

    // ---------- 拒绝路径 ----------

    /**
     * 无令牌连接必须被拒：连接被服务端关闭（客户端读到 EOF），且不得建立会话。
     * 修复前：该连接被接受为完整会话（客户端读到的是服务端下发的 CONFIG 帧、会话入表）——
     * 「首字节必须 -1」与「会话表必须为空」双双转红，即审计 #2 的进程内复现。
     */
    fun test无令牌连接被拒_不建会话() {
        val port = portOf(manager.preparePremainVmOption()!!)
        val socket = Socket("127.0.0.1", port)
        writeFrame(socket, AgentProtocol.CMD_HELLO, """{"pid":424242}""")

        socket.soTimeout = 3_000
        val firstByte = try {
            DataInputStream(socket.getInputStream()).read()
        } catch (e: java.net.SocketTimeoutException) {
            0 // 服务端既未写帧也未关连接 = 连接被接受（期望形态是拒绝）
        }
        assertEquals(
            "无令牌连接必须被服务端关闭（首字节 $firstByte != -1 表示连接被接受且服务端在写帧——" +
                "修复前即此形态：自报 pid 换取完整会话）",
            -1,
            firstByte,
        )
        assertFalse("被拒连接不得建立会话", manager.sessions.any { it.pid == 424242L })
        assertFalse("被拒连接不得写幂等表", manager.attachedPids.containsKey(424242L))
        socket.close()
    }

    /** 令牌不匹配同理拒绝（凭空构造的令牌 ≠ 通道令牌——防「知道协议形态但不知道秘密」的对端）。 */
    fun test令牌不匹配被拒() {
        val option = manager.preparePremainVmOption()!!
        val port = portOf(option)
        val socket = Socket("127.0.0.1", port)
        writeFrame(
            socket, AgentProtocol.CMD_HELLO,
            """{"pid":424243,"token":"deadbeefdeadbeefdeadbeefdeadbeef"}""",
        )

        socket.soTimeout = 3_000
        val firstByte = try {
            DataInputStream(socket.getInputStream()).read()
        } catch (e: java.net.SocketTimeoutException) {
            0
        }
        assertEquals("令牌不匹配必须被服务端关闭（首字节 $firstByte != -1 表示被接受）", -1, firstByte)
        assertFalse("被拒连接不得建立会话", manager.sessions.any { it.pid == 424243L })
        socket.close()
    }

    // ---------- 放行路径（正对照） ----------

    /** 携带通道令牌的回连照常建立会话（正常 agent 路径零劣化）。 */
    fun test正确令牌回连建立会话() {
        val option = manager.preparePremainVmOption()!!
        val token = tokenOf(option)
        assertNotNull("vmOption 须随带通道令牌（修复前 args 段无 token 键）", token)
        val port = portOf(option)
        val socket = Socket("127.0.0.1", port)
        writeFrame(socket, AgentProtocol.CMD_HELLO, """{"pid":424244,"token":"$token"}""")

        awaitTrue("正确令牌的回连未建立会话") { manager.sessions.any { it.pid == 424244L } }
        awaitTrue("回连后在线态未置位") { manager.online }
        assertTrue(manager.attachedPids.containsKey(424244L))
        socket.close()
    }

    /** 令牌不出现在日志面：vmOption 是唯一下发出口（命令行/agentArgs），其格式本身即公开面——
     *  本用例钉住「token 键在 args 段、hex 形态」，防止实现把令牌写进会话日志等通道。 */
    fun test令牌形态_每通道独立_hex() {
        val option1 = manager.preparePremainVmOption()!!
        val option2 = manager.preparePremainVmOption()!!
        val token1 = tokenOf(option1)
        val token2 = tokenOf(option2)
        assertNotNull(token1)
        assertNotNull(token2)
        assertTrue("令牌须为 hex 形态", Regex("^[0-9a-f]{16,64}\$").matches(token1!!))
        assertTrue("各通道令牌相互独立（不得复用同一秘密）", token1 != token2)
        assertTrue(
            "通道令牌不得写进状态行/日志摘要（statusSummary 不含 token）",
            !manager.statusSummary().contains(token1),
        )
    }

    // ---------- 辅助 ----------

    private fun portOf(vmOption: String): Int =
        Regex("port=(\\d+)").find(vmOption)?.groupValues?.get(1)?.toInt()
            ?: error("VM 选项里没有 port：$vmOption")

    private fun tokenOf(vmOption: String): String? =
        Regex("token=([0-9a-fA-F]+)").find(vmOption)?.groupValues?.get(1)

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
}
