package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * T-PremainReattach：关闭→重开增强捕获对 premain 已注入进程的恢复语义。
 *
 * 真机根因（dev-bak 243 沙盒 idea.log 08:07~08:10 时间线）：关闭增强时 `shutdownAll` 把 premain
 * 监听 socket 一并关闭；重开后 settings-rescan 对同一 pid 走 loadAgent——agent 类已由 premain
 * 装载，agentmain 不重入（AgentLoadException(0)，新端口送不进去），agent 侧 SenderLoop 只认
 * 启动参数里的旧端口（已死）→ accept 10s 超时 → 永久回落桥，面板零捕获。
 *
 * 修复语义（D1）：关闭时 claimed 通道转「暂停待重连」（监听保留，agent 重连在内核 backlog 停泊
 * ——不被 accept 即不计 agent 侧「连上即被关」计数，PORT_ABANDONED 阈值不可达）；重开上升沿
 * pid 命中暂停表 → resume 重建 accept 循环取回停泊连接，跳过 loadAgent。
 *
 * 假 pid 一律用测试 JVM 自身 pid（`ProcessHandle.current()`）：暂停分流判据 `isChannelExpired`
 * 里的进程存活检查必须通过；resume 链路全程无 loadAgent，不会向任何进程注入。
 *
 * 前置等待口径：shutdownAll 前必须 await「通道 pid 已登记」（分流与 resume 判据的确切前置；
 * pid 在 HELLO 读取后立即登记，但会话建立链还有毫秒级后续步骤，只等「会话已建立」会落在
 * pid 登记前的窗口内——首轮实测 5/12 恰落窗口，通道被误关）。
 */
class AgentManagerPremainReattachTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager
    private val selfPid: Long = ProcessHandle.current().pid()

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        resetState()
    }

    override fun tearDown() {
        try {
            manager.shutdownAll()
            manager.premainChannels.toList().forEach { ch ->
                ch.closeChannel()
                manager.premainChannels.remove(ch)
            }
            resetState()
        } finally {
            super.tearDown()
        }
    }

    private fun resetState() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = false
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = 0
        manager.clockMs = { System.currentTimeMillis() }
    }

    // ---------- S1：关闭分流 ----------

    /** 反向验证锚点：移除 shutdownAll 的暂停分流（退回 closePremainChannels）本用例必红。 */
    fun test关闭增强_claimed通道转暂停_监听保留() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val channel = manager.premainChannels[0]
        connectAndClaim(port, selfPid)
        awaitClaimReady(channel)

        manager.shutdownAll()

        assertTrue("关闭后不得残留会话", manager.sessions.isEmpty())
        assertFalse("关闭后应离线", manager.online)
        assertEquals("claimed 通道应留在通道表（暂停待重连）", 1, manager.premainChannels.size)
        assertTrue("通道应处于暂停态", channel.paused)
        assertFalse("暂停态不得关闭通道", channel.closed)
        // 监听保留：端口仍可建连（退回旧行为时 ServerSocket 已关，此处 ConnectException 即红）
        try {
            Socket("127.0.0.1", port).close()
        } catch (e: Exception) {
            fail("暂停通道的监听应保留（端口 $port 建连失败：$e）")
        }
    }

    fun test关闭增强_未认领通道照旧关闭() {
        enableAndConfirm()
        manager.preparePremainVmOption()
        assertEquals(1, manager.premainChannels.size)
        val channel = manager.premainChannels[0]

        manager.shutdownAll()

        assertTrue("未认领通道应照旧从通道表摘除", manager.premainChannels.isEmpty())
        assertTrue("未认领通道应照旧关闭", channel.closed)
    }

    /** 收窄语义：pid 未知的 claimed 通道无法被 resume（查表按 pid），照旧关闭、不驻留到 TTL。 */
    fun test关闭增强_pid未知的claimed通道_照旧关闭不暂停() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val channel = manager.premainChannels[0]
        connectAndClaim(port, 0L)
        awaitTrue("前置：通道应已认领（pid 未知）") { channel.claimed }

        manager.shutdownAll()

        assertTrue("pid 未知的 claimed 通道应照旧关闭（resume 查表按 pid，pid=0 永不命中）", manager.premainChannels.isEmpty())
    }

    // ---------- S3：暂停期重连停泊 ----------

    /**
     * 暂停期 agent 重连到达：不得被 accept 后关闭（SenderLoop「连上即被关」计满 10 次即
     * PORT_ABANDONED 永久放弃端口），也不得建会话——连接停在内核 backlog，resume 后取回。
     */
    fun test暂停期重连到达_连接停泊_不建会话不关闭() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val channel = manager.premainChannels[0]
        connectAndClaim(port, selfPid)
        awaitClaimReady(channel)
        manager.shutdownAll()
        // 等 pause 唤醒的 accept 线程真正退出（哑元连接已被消费），再停泊重连——否则竞态期
        // 停泊连接可能被退出路径 accept 后按「暂停竞态」丢弃（该路径至多 1 次，生产无碍；
        // 测试里避开它以保证断言确定性）
        awaitTrue("暂停后 accept 线程应退出（socket 保留）") { channel.acceptThread?.isAlive == false }

        val parked = Socket("127.0.0.1", port)
        writeFrame(parked, AgentProtocol.CMD_HELLO, """{"pid":$selfPid}""")
        Thread.sleep(600)
        assertTrue("暂停期不得建会话", manager.sessions.isEmpty())
        assertTrue("暂停通道应仍在表内", manager.premainChannels.contains(channel))
        parked.soTimeout = 1_500
        val firstByte = try {
            DataInputStream(parked.getInputStream()).read()
        } catch (e: SocketTimeoutException) {
            0 // 服务端既未读也未关 = 连接停泊在 backlog（期望形态）
        }
        assertTrue(
            "暂停期连接必须停泊（首字节 $firstByte == -1 表示被 accept 后关闭——会计入 agent 侧" +
                "「连上即被关」，PORT_ABANDONED 后 resume 永久失效）",
            firstByte != -1,
        )
        parked.close()
    }

    // ---------- S2：resume 入口 ----------

    /** 反向验证锚点：移除 resume 入口（resumePausedPremainChannelIfHit）本用例必红。 */
    fun test重开增强_pid命中暂停通道_resume取回停泊连接重建会话() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val channel = manager.premainChannels[0]
        connectAndClaim(port, selfPid)
        awaitClaimReady(channel)
        manager.shutdownAll()
        awaitTrue("暂停后 accept 线程应退出") { channel.acceptThread?.isAlive == false }

        // 模拟 agent 停泊重连（HELLO 已进内核缓冲区）
        val parked = Socket("127.0.0.1", port)
        writeFrame(parked, AgentProtocol.CMD_HELLO, """{"pid":$selfPid}""")

        // 重开增强（上升沿语义：开关与确认态恢复）→ pid 命中暂停表 → resume
        enableAndConfirm()
        assertTrue("resume 应命中暂停通道", manager.resumePausedPremainChannelIfHit(selfPid))
        assertFalse("恢复后不得仍是暂停态", channel.paused)

        // resume 重建 accept 循环：停泊连接被取回，缓冲区 HELLO 直接建会话（无需 agent 再拨一次）
        awaitTrue("resume 后未重建会话（停泊连接未被取回？）") { manager.sessions.size == 1 }
        awaitTrue("resume 后在线态未恢复") { manager.online }
        assertEquals("重建会话应复用停泊连接自报的 pid", selfPid, manager.sessions[0].pid)
        assertTrue("重建会话应写幂等表", manager.attachedPids.containsKey(selfPid))
        assertTrue("resume 应走通道 accept（回连计数 ≥2），而非 loadAgent 路径", channel.acceptCount >= 2)
        assertEquals("resume 不得进入 attach 排队", 0, manager.pendingPids.size)
        parked.close()
    }

    /** 双路径收口：settings-rescan（onProcessAppeared）与进程启动钩子同经 doAttachAsync，单点命中。 */
    fun test重开增强_onProcessAppeared命中暂停通道_resume且不进attach排队() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val channel = manager.premainChannels[0]
        connectAndClaim(port, selfPid)
        awaitClaimReady(channel)
        manager.shutdownAll()

        enableAndConfirm()
        manager.onProcessAppeared(FakeProcessHandler(selfPid))

        assertFalse("命中暂停通道不得进入 attach 排队", manager.pendingPids.containsKey(selfPid))
        assertFalse("onProcessAppeared 应触发 resume", channel.paused)
    }

    fun test_pid未命中暂停通道_照常走attach排队() {
        var now = 3_000_000L
        manager.clockMs = { now }
        enableAndConfirm()
        manager.preparePremainVmOption()
        // 推出 premain 跳过窗，排除「通道在场压制 attach」的既有语义干扰
        now += AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1
        val deadPid = 999_999_999L

        manager.onProcessAppeared(FakeProcessHandler(deadPid))

        assertTrue("未命中暂停通道应照常排队 attach（既有路径零劣化）", manager.pendingPids.containsKey(deadPid))
    }

    // ---------- S4：暂停通道巡检 ----------

    fun test巡检回收pid消亡的暂停通道() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val channel = manager.premainChannels[0]
        connectAndClaim(port, selfPid)
        awaitClaimReady(channel)
        manager.shutdownAll()
        assertEquals(1, manager.premainChannels.size)

        // 模拟进程消亡（外部 kill，无运行结束信号）：直接改写通道自报 pid
        channel.pid = 999_999_999L
        manager.sweepOffline()

        assertTrue("pid 消亡的暂停通道应被巡检回收", manager.premainChannels.isEmpty())
    }

    fun test巡检回收超兜底TTL的暂停通道() {
        var now = 5_000_000L
        manager.clockMs = { now }
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        connectAndClaim(port, selfPid)
        awaitClaimReady(manager.premainChannels[0])
        manager.shutdownAll()
        assertEquals(1, manager.premainChannels.size)

        now += AgentManager.PREMAIN_CHANNEL_TTL_MS + 1
        manager.sweepOffline()

        assertTrue("超兜底 TTL 的暂停通道应被巡检回收（运行结束信号丢失时通道不无限驻留）", manager.premainChannels.isEmpty())
    }

    fun test巡检不动健康的暂停通道() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val channel = manager.premainChannels[0]
        connectAndClaim(port, selfPid)
        awaitClaimReady(channel)
        manager.shutdownAll()

        manager.sweepOffline()

        assertEquals("健康的暂停通道（进程活、TTL 未到）不得被巡检回收", 1, manager.premainChannels.size)
        assertTrue(manager.premainChannels[0].paused)
    }

    /** 运行结束信号仍关闭暂停通道（通道生命周期 = 绑定到本次运行的既有语义不回退）。 */
    fun test运行结束仍关闭暂停通道() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val channel = manager.premainChannels[0]
        connectAndClaim(port, selfPid)
        awaitClaimReady(channel)
        manager.shutdownAll()
        assertEquals(1, manager.premainChannels.size)

        manager.onRunEnded()

        assertTrue("运行结束应关闭暂停通道", manager.premainChannels.isEmpty())
    }

    /** S4 巡检驻留：有暂停通道时巡检保持运行；全部清空后恢复 T-PS6-08 的巡检复位语义。 */
    fun test巡检随暂停通道驻留_清空后复位() {
        enableAndConfirm()
        val port = portOf(manager.preparePremainVmOption()!!)
        val channel = manager.premainChannels[0]
        connectAndClaim(port, selfPid)
        awaitClaimReady(channel)

        manager.shutdownAll()
        assertTrue("有暂停通道驻留时巡检应保持运行（S4 兜底回收依赖它）", manager.monitorStartedForTest())

        manager.onRunEnded()
        assertTrue(manager.premainChannels.isEmpty())
        manager.shutdownAll()
        assertFalse("暂停通道清空后巡检应复位（T-PS6-08 语义不回退）", manager.monitorStartedForTest())
    }

    // ---------- P4：attach 装载进程的关再开恢复（D2 形态：原端口 re-bind） ----------

    /**
     * 反向验证锚点：摘除 doAttachAsync 的 resumeAttachPortIfHit 钩子（或恢复函数本体）本用例必红。
     * attach 链的回连端口是一次性 ServerSocket，关闭后 agent 的 SenderLoop 对它无限回连——唯一
     * 恢复路径是原端口 re-bind 起 accept 循环接住回连（loadAgent 注不进去：agentmain 不重入）。
     */
    fun testP4_attach装载关再开_原端口rebind接住回连重建会话() {
        enableAndConfirm()
        // 取一个空闲端口再放掉（listen-only 无连接，关闭即干净释放、无 TIME_WAIT）——
        // 模拟「attach 一次性 ServerSocket 已死、agent 的 SenderLoop 仍认它」的现场形态
        val deadAttachPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).let { s ->
            val p = s.localPort
            s.close()
            p
        }
        manager.attachPorts[selfPid] = deadAttachPort

        assertTrue("P4 恢复应命中登记表", manager.resumeAttachPortIfHit(selfPid))
        assertTrue("恢复成功后登记条目应被消费", manager.attachPorts.isEmpty())
        assertEquals(1, manager.premainChannels.size)
        val channel = manager.premainChannels[0]
        assertEquals("恢复通道应复用原端口", deadAttachPort, channel.port)
        assertTrue("恢复通道应等价 claimed（后续关开走暂停/恢复语义）", channel.claimed)
        assertEquals(selfPid, channel.pid)

        // 模拟 agent 的 SenderLoop 对原端口回连（重开后下一拍拨号即中）
        val redial = Socket("127.0.0.1", deadAttachPort)
        writeFrame(redial, AgentProtocol.CMD_HELLO, """{"pid":$selfPid}""")
        awaitTrue("P4：re-bind 后 agent 回连未重建会话") { manager.sessions.size == 1 }
        awaitTrue("P4：重建会话后在线态未恢复") { manager.online }
        assertEquals(selfPid, manager.sessions[0].pid)
        assertTrue(manager.attachedPids.containsKey(selfPid))

        // 统一性：恢复后的通道就是 claimed 通道——关再开第二循环走 premain 暂停/恢复路径
        manager.shutdownAll()
        assertTrue("恢复通道关闭期应转暂停（监听保留）", channel.paused)
        enableAndConfirm()
        assertTrue("第二循环应命中暂停表 resume", manager.resumePausedPremainChannelIfHit(selfPid))
        val redial2 = Socket("127.0.0.1", deadAttachPort)
        writeFrame(redial2, AgentProtocol.CMD_HELLO, """{"pid":$selfPid}""")
        awaitTrue("P4：第二循环未重建会话") { manager.sessions.size == 1 }
        redial.close()
        redial2.close()
    }

    /** 反向验证锚点：摘除 doAttachAsync 的 P4 钩子（resumeAttachPortIfHit 调用）本用例必红。 */
    fun testP4_onProcessAppeared命中attach登记_resume且不进attach排队() {
        enableAndConfirm()
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).let { s ->
            val p = s.localPort
            s.close()
            p
        }
        manager.attachPorts[selfPid] = port

        manager.onProcessAppeared(FakeProcessHandler(selfPid))

        assertFalse("P4 命中不得进入 attach 排队", manager.pendingPids.containsKey(selfPid))
        assertEquals("onProcessAppeared 应触发原端口 re-bind 恢复", 1, manager.premainChannels.size)
        assertEquals(port, manager.premainChannels[0].port)
        assertTrue(manager.premainChannels[0].claimed)
    }

    fun testP4_端口被占rebind失败_清除登记回落attach路径() {
        val occupier = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val deadPid = 999_999_999L
            manager.attachPorts[deadPid] = occupier.localPort
            val before = manager.premainChannels.size

            assertFalse("端口被占时恢复应失败", manager.resumeAttachPortIfHit(deadPid))
            assertTrue("失败后应清除失效登记（不得残留）", manager.attachPorts.isEmpty())
            assertEquals("失败不得产生通道", before, manager.premainChannels.size)
            // 无登记的 pid 直接未命中
            assertFalse(manager.resumeAttachPortIfHit(deadPid))
        } finally {
            occupier.close()
        }
    }

    fun testP4_巡检清死pid的attach端口登记() {
        val freePort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).let { s ->
            val p = s.localPort
            s.close()
            p
        }
        manager.attachPorts[999_999_999L] = freePort
        manager.attachPorts[selfPid] = freePort + 1

        manager.sweepOffline()

        assertFalse("死 pid 的登记应被巡检清除", manager.attachPorts.containsKey(999_999_999L))
        assertTrue("活 pid 的登记应保留", manager.attachPorts.containsKey(selfPid))
    }

    // ---------- P5：resume 无界挂死回归（T-ReleasePerf8，S5 发行门禁轮 75min 挂死） ----------

    /**
     * 回归锚点①（预算兜底）：旧 accept 线程滞留非预期停等形态时，resume 必须在预算内有界返回并判
     * 通道不可恢复（false + 收口），而不是无界 join 挂死。S5 轮形态＝pause→resume 竞态下旧循环
     * 失去 paused 退出条件、30s tick 无限 continue，Test worker 在 join 上挂 75min（jstack 实录，
     * 本用例前身 testP4 第二循环）。反向锚点：摘除「超预算强制收口 + return false」分支（照旧起
     * 新循环返回 true）本用例必红。
     */
    fun testP5_旧Accept线程停等超预算_有界返回判通道不可恢复() {
        enableAndConfirm()
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        // 复刻挂死现场：claimed 暂停通道 + 一个永不退出的旧 accept 线程。
        // 注：线程体必须捕获 channel（非捕获 lambda 会被编译成 testXxx$lambda$N 合成方法，
        // JUnit3 反射把它当测试方法报「Test method isn't public」——下同，不再用裸 sleep 体）
        val channel = PremainChannel(server.localPort, server, 0L).apply {
            claimed = true
            claimedAtMs = System.currentTimeMillis()
            pid = selfPid
            paused = true
        }
        manager.premainChannels.add(channel)
        val wedge = Thread({ while (!channel.closed) { Thread.sleep(50) } }, "wedge-accept")
        wedge.isDaemon = true
        channel.acceptThread = wedge
        wedge.start()
        val originalBudget = AgentManager.PREMAIN_RESUME_JOIN_BUDGET_MS
        AgentManager.PREMAIN_RESUME_JOIN_BUDGET_MS = 300L
        try {
            val startAt = System.currentTimeMillis()
            assertFalse(
                "旧线程停等超预算应判不可恢复返回 false（而非无界等待）",
                manager.resumePausedPremainChannelIfHit(selfPid),
            )
            val elapsed = System.currentTimeMillis() - startAt
            assertTrue("resume 必须有界返回（实测 ${elapsed}ms）", elapsed < 5_000)
            assertTrue("不可恢复通道应已收口", channel.closed)
        } finally {
            AgentManager.PREMAIN_RESUME_JOIN_BUDGET_MS = originalBudget
            channel.acceptThread?.interrupt()
            runCatching { channel.closeChannel() }
            manager.premainChannels.remove(channel)
        }
    }

    /**
     * 回归锚点②（代际让位）：resume 递增代际后，阻塞中的旧 accept 循环最迟下一拍发现失主退出，
     * 通道由新循环接管（不收口、不双循环互抢）。反向锚点：摘除 accept 循环内的代际检查（只依赖
     * paused 退出）本用例必红——resume 先行翻回 paused=false 后旧循环失去全部退出条件，resume 只能
     * 靠预算兜底判死通道（返回 false）。本用例把 tick 调小到 300ms 加速旧循环的下一拍。
     */
    fun testP5_旧循环被新代取代_代际失主让位通道不收口() {
        enableAndConfirm()
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val channel = PremainChannel(server.localPort, server, 0L).apply {
            claimed = true
            claimedAtMs = System.currentTimeMillis()
            pid = selfPid
        }
        manager.premainChannels.add(channel)
        val originalTick = AgentManager.PREMAIN_IDLE_TICK_MS
        AgentManager.PREMAIN_IDLE_TICK_MS = 300L
        val generation = ++channel.acceptGeneration
        val oldThread = Thread({ manager.acceptPremain(channel, generation) }, "old-accept")
        oldThread.isDaemon = true
        channel.acceptThread = oldThread
        oldThread.start()
        try {
            awaitTrue("前置：旧循环应在役（parked in accept）") { oldThread.isAlive }
            // 竞态形态复刻：paused 置位后、旧线程未及观察任何停等返回，resume 已翻回 paused 并取新代
            channel.paused = true
            assertTrue(
                "resume 应在旧循环让位后成功（而非靠预算判死）",
                manager.resumePausedPremainChannelIfHit(selfPid),
            )
            oldThread.join(2_000)
            assertFalse("旧循环应代际失主退出（而非无限 tick）", oldThread.isAlive)
            assertFalse("通道应由新循环接管（不得收口）", channel.closed)
            assertTrue("acceptThread 应换成新循环", channel.acceptThread !== oldThread)
            assertTrue("新循环应在役", channel.acceptThread?.isAlive == true)
        } finally {
            AgentManager.PREMAIN_IDLE_TICK_MS = originalTick
            oldThread.interrupt()
            runCatching { server.close() }
            runCatching { channel.closeChannel() }
            channel.acceptThread?.interrupt()
            manager.premainChannels.remove(channel)
        }
    }

    // ---------- 辅助 ----------

    private fun enableAndConfirm() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = true
    }

    /**
     * 首次回连就绪前置：会话已建立 **且** 通道 pid 已登记（关开关分流/resume 判据的确切前置，
     * 见类 KDoc「前置等待口径」）。
     */
    private fun awaitClaimReady(channel: PremainChannel) {
        awaitTrue("前置：premain 会话未建立") { manager.sessions.size == 1 }
        awaitTrue("前置：通道 pid 未登记（分流判据依赖它）") { channel.pid == selfPid }
    }

    private fun portOf(vmOption: String): Int =
        Regex("port=(\\d+)").find(vmOption)?.groupValues?.get(1)?.toInt()
            ?: error("VM 选项里没有 port：$vmOption")

    /** 首次回连：建连 + HELLO（pid 由暂停判据的进程存活检查消费，须为测试 JVM 自身）。 */
    private fun connectAndClaim(port: Int, pid: Long): Socket {
        val socket = Socket("127.0.0.1", port)
        writeFrame(socket, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")
        return socket
    }

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

    /** 反射缝假 ProcessHandler：只暴露 getNativePid（resume 判据只需「pid 可解析」这一条）。 */
    private class FakeProcessHandler(private val nativePid: Long? = null) {
        fun getNativePid(): Long? = nativePid
    }
}
