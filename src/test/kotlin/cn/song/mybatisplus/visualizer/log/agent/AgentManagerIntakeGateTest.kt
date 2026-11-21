package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.log.CaptureProvenance
import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import cn.song.mybatisplus.visualizer.log.SqlLogFileReader
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.mcp.recordMcpExecution
import cn.song.mybatisplus.visualizer.testutil.AlreadyDisposedDiagnostics
import cn.song.mybatisplus.visualizer.testutil.SqlLogManagerProjectState
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * T-AgentConfigChannel：IDE 侧控制通道三条落地语义的守护测试。
 *
 * 现场（§一）：开关停在 ▶（未捕获）却有条目进列表，全天 0 条 `startCapture`/`pushCaptureActive`。
 * 两层原因里属本任务的一层是 **IDE 侧 agent 事件入队点完全不判 `capturing`**，加上「已下发」被当成
 * 「已生效」。本类锁住修复后的三条语义：
 *
 * 1. **T-03 入队闸门**：监听关闭（`SqlLogManager.capturing == false`）时 agent 事件**丢弃并计数**，
 *    不入列表；开启时照常入队。边界：只作用于 agent 通道入队点——MCP 审计（`recordMcpExecution`）
 *    与历史回读（`SqlLogFileReader`）不经此处，行为不变。
 * 2. **T-02 回执可观测**：CONFIG 回执（`CMD_CONFIG_ACK`）解析（规范/畸形）、收到即清挂起并记生效值、
 *    窗口内未收到即告警一次。
 * 3. **追加 P-5 桥闸门**：`SqlCaptureGate.paused` 判据由「agent 在线」收窄为「在线**且**应用侧已确认
 *    在采集」——否则修复后出现新常态「agent 在线但已停采」时，桥被封着、agent 又静默 = 两条通道
 *    同时零产出且无任何可见信号（把「误采集」换成「零捕获」）。
 *
 * 会话构造照 `AgentHeartbeatWindowTest` 惯例：loopback 真 socket 对（`AgentSession` internal 同包可构造）、
 * 时间源注入（无需真等心跳窗口）。共享项目服务级 `SqlLogManager` 状态照 `AgentListenSyncTest` 惯例
 * 经 [SqlLogManagerProjectState] 存初值/复位（light 测试项目片内跨类复用同一 ProjectImpl）。
 */
class AgentManagerIntakeGateTest : BasePlatformTestCase() {

    private var serviceState: SqlLogManagerProjectState.Snapshot? = null
    private var now = 5_000_000L
    private val sockets = mutableListOf<java.io.Closeable>()
    private val freshManagers = mutableListOf<AgentManager>()

    /** agent 侧（假应用侧）socket：按 pid 记，供补发帧用。 */
    private val agentSide = mutableMapOf<Long, Socket>()

    override fun setUp() {
        super.setUp()
        serviceState = SqlLogManagerProjectState.capture(logManager())
        now = 5_000_000L
        SqlCaptureGate.paused.set(false)
    }

    override fun tearDown() {
        // 独立 AgentManager 实例：Disposer.dispose 走完整子树（直接 dispose() 不关 messageBus 连接）
        freshManagers.forEach { runCatching { Disposer.dispose(it) } }
        freshManagers.clear()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        agentSide.clear()
        SqlCaptureGate.paused.set(false)
        SqlLogManagerProjectState.restore(serviceState)
        super.tearDown()
    }

    // ---------- 夹具 ----------

    private fun logManager(): SqlLogManager =
        AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "gate.fixture")

    private fun freshManager(): AgentManager = AgentManager(project).also {
        it.clockMs = { now }
        freshManagers += it
    }

    /**
     * loopback 真会话。
     *
     * @param manager 非空时把「回执到达」接进管理器的桥闸门重算（生产链路同款接线，测试直连会话不经 attach）
     * @param scheduleAckCheck 非空时收走回执超时巡检任务（测试同步驱动，不真等 [AgentManager.CONFIG_ACK_TIMEOUT_MS]）
     */
    private fun newSession(
        pid: Long,
        manager: AgentManager? = null,
        scheduleAckCheck: ((Long, () -> Unit) -> Unit)? = null,
    ): AgentSession {
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
            scheduleConfigAckCheck = scheduleAckCheck ?: { _, _ -> },
            onCaptureConfirmationChanged = { manager?.onCaptureConfirmationChanged() },
        )
    }

    /** agent → IDE 方向发一帧（协议帧：4 字节 len + 1 字节 cmd + UTF-8 body）。 */
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

    /** 事件帧 body（`ts` 固定小值，避免与真实时钟耦合）。 */
    private fun eventBody(sql: String): String = """{"sql":"$sql","ts":1}"""

    // ---------- ① T-03 入队闸门 ----------

    fun test监听关闭_事件丢弃并计数_不入队() {
        val manager = logManager()
        manager.entries.paused = false
        manager.setCapturingForTest(false)
        val before = manager.entries.size()

        val session = newSession(7401L)
        session.listenActiveProvider = { false } // 与 capturing=false 同义（此处显式注入，隔离共享服务状态）
        writeFrame(7401L, AgentProtocol.CMD_EVENT, eventBody("select gate_closed_1"))

        awaitTrue("关闭状态下的事件应被闸门丢弃并计数") { session.intakeDropped.get() == 1L }
        assertEquals("被闸门丢弃的事件不得入列表", before, manager.entries.size())
    }

    fun test监听开启_事件放行入队() {
        val manager = logManager()
        manager.entries.paused = false
        manager.setCapturingForTest(true)
        val before = manager.entries.size()

        val session = newSession(7402L)
        session.listenActiveProvider = { true }
        writeFrame(7402L, AgentProtocol.CMD_EVENT, eventBody("select gate_open_1"))

        awaitTrue("开启状态下的事件应入列表") { manager.entries.size() == before + 1 }
        assertEquals("放行路径不得计数丢弃", 0L, session.intakeDropped.get())
    }

    // ---------- T-DriverInternalSqlFilter：驱动内部语句（连接初始化族）不入列表 ----------

    fun test驱动内部语句_过滤不入队且计数() {
        val manager = logManager()
        manager.entries.paused = false
        manager.setCapturingForTest(true)
        val beforeSize = manager.entries.size()
        val beforeDropped = DriverInternalSql.droppedEvents()

        val session = newSession(7601L)
        session.listenActiveProvider = { true }
        writeFrame(
            7601L, AgentProtocol.CMD_EVENT,
            eventBody(
                "/* mysql-connector-java-5.1.49 (Revision: x) */" +
                    "SELECT @@session.auto_increment_increment AS auto_increment_increment",
            ),
        )

        awaitTrue("驱动内部语句应被过滤并计数") { DriverInternalSql.droppedEvents() == beforeDropped + 1 }
        assertEquals("驱动内部语句不得入列表", beforeSize, manager.entries.size())
        assertEquals("过滤不得误计入队闸门丢弃", 0L, session.intakeDropped.get())
    }

    fun test驱动内部语句过滤_紧随其后的业务语句照常入队() {
        val manager = logManager()
        manager.entries.paused = false
        manager.setCapturingForTest(true)
        val before = manager.entries.size()

        val session = newSession(7602L)
        session.listenActiveProvider = { true }
        writeFrame(7602L, AgentProtocol.CMD_EVENT, eventBody("SELECT @@session.autocommit;"))
        writeFrame(7602L, AgentProtocol.CMD_EVENT, eventBody("select biz_after_internal_1"))

        awaitTrue("过滤不得掐断通道：业务语句应照常入队") { manager.entries.size() == before + 1 }
        assertEquals("内部语句被过滤、业务语句入列，总增量恰为 1", before + 1, manager.entries.size())
    }

    /** 默认判据（不注入 provider）必须跟随 `SqlLogManager.capturing`——闸门不能只挂在测试缝上。 */
    fun test默认判据跟随捕获态() {
        val manager = logManager()
        manager.entries.paused = false
        val session = newSession(7403L)
        val before = manager.entries.size()

        manager.setCapturingForTest(false)
        assertFalse("监听关闭 → 默认判据为不活跃", session.listenActiveProvider())
        writeFrame(7403L, AgentProtocol.CMD_EVENT, eventBody("select gate_default_off"))
        awaitTrue("关闭态事件应被丢弃") { session.intakeDropped.get() == 1L }
        assertEquals(before, manager.entries.size())

        manager.setCapturingForTest(true)
        assertTrue("监听开启 → 默认判据为活跃", session.listenActiveProvider())
        writeFrame(7403L, AgentProtocol.CMD_EVENT, eventBody("select gate_default_on"))
        awaitTrue("开启态事件应入队") { manager.entries.size() == before + 1 }
    }

    /** T-03 边界：MCP 审计不经 agent 入队点——监听关闭（甚至缓冲暂停）也照旧落历史。 */
    fun test监听关闭_MCP审计路径不受影响() {
        val manager = logManager()
        manager.entries.paused = false
        manager.setCapturingForTest(false)
        val before = manager.entries.size()

        recordMcpExecution(project, "select mcp_gate_probe", null, 1L, null)

        assertEquals("MCP 审计不受入队闸门约束（D1 既有边界：只尊重 buffer.paused）", before + 1, manager.entries.size())
    }

    /** T-03 边界：历史回读不经 buffer 入队——监听关闭 + 缓冲暂停下照常读出条目。 */
    fun test监听关闭_历史回读路径不受影响() {
        val manager = logManager()
        manager.setCapturingForTest(false)
        manager.entries.paused = true

        val content = "--- source: a/A.java:9 | time: 5ms | 2026-08-25 10:00:00.123 ---\n" +
            "SELECT 1 FROM history_gate_probe\n\n"
        val entries = SqlLogFileReader.parseContent(content)

        assertTrue("历史回读须在「监听关闭 + 缓冲暂停」下照常返回条目", entries.isNotEmpty())
        assertEquals("SELECT 1 FROM history_gate_probe", entries[0].restoredSql)
    }

    // ---------- ② T-02 回执解析 ----------

    fun test回执解析_规范形态() {
        // T-ToolSiteBizFrame 起严格形态扩为四键（bizJarPrefixes 列尾追加段）
        val body = """{"disabled":true,"desensitize":false,"skipPrefixes":3,"bizJarPrefixes":1}"""
        val ack = AgentProtocol.parseConfigAck(body)
        assertNotNull("规范回执必须解析成功: $body", ack)
        assertTrue(ack!!.disabled)
        assertFalse(ack.desensitize)
        assertEquals(3, ack.skipPrefixes)
        assertEquals(1, ack.bizJarPrefixes)
        // 编解码往返：IDE 侧构造的规范形态必须能被自己的严格解析吃下
        assertEquals(
            body,
            AgentProtocol.configAckBody(disabled = true, desensitize = false, skipPrefixes = 3, bizJarPrefixes = 1),
        )
        assertEquals(ack, AgentProtocol.parseConfigAck(AgentProtocol.configAckBody(true, false, 3, 1)))
    }

    fun test回执解析_畸形形态返回null并计数() {
        val before = AgentProtocol.configAckParseFailures.get()
        val malformed = listOf(
            "",
            "{}",
            """{"disabled":true,"desensitize":false}""", // 缺 skipPrefixes
            """{"disabled":false, "desensitize":true,"skipPrefixes":0}""", // 键值间空格（非规范形态）
            """{"desensitize":true,"disabled":false,"skipPrefixes":0}""", // 键序不同
            """{"disabled":true,"desensitize":false,"skipPrefixes":0,"extra":1}""", // 多键
            """{"disabled":yes,"desensitize":false,"skipPrefixes":0}""", // 值域非法
            """{"disabled":true,"desensitize":false,"skipPrefixes":x}""", // 非数字
            """{"disabled":true,"desensitize":false,"skipPrefixes":99999999999999}""", // 溢出 Int
            """{"disabled":true,"desensitize":false,"skipPrefixes":0} """, // 尾随空格
            """{"disabled":true,"desensitize":false,"skipPrefixes":0}""", // 旧 agent 三键形态（缺 bizJarPrefixes）
        )
        malformed.forEach { assertNull("畸形回执必须返回 null: <$it>", AgentProtocol.parseConfigAck(it)) }
        assertEquals(
            "每个畸形回执各计一次失败",
            (before + malformed.size).toLong(),
            AgentProtocol.configAckParseFailures.get(),
        )
    }

    // ---------- ② T-02 回执到达 / 超时 ----------

    fun test收到回执_清挂起并记生效值() {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val session = newSession(7501L, scheduleAckCheck = { d, t -> scheduled += d to t })

        session.sendConfig(disabled = false, desensitize = false)
        assertTrue("下发后应有挂起等待回执", session.hasPendingConfigAck())
        assertEquals(
            "回执等待窗应为常量（与 AgentManager.CONFIG_ACK_TIMEOUT_MS 同值）",
            AgentManager.CONFIG_ACK_TIMEOUT_MS,
            scheduled.single().first,
        )

        writeFrame(7501L, AgentProtocol.CMD_CONFIG_ACK, AgentProtocol.configAckBody(false, true, 2, 0))
        awaitTrue("回执应被读线程处理") { session.configAckCount.get() == 1L }

        assertFalse("收到回执即清挂起", session.hasPendingConfigAck())
        assertEquals("生效值写入确认态（disabled=false）", false, session.configAckDisabled)

        scheduled.single().second() // 到点执行巡检：挂起已清 → 静默
        assertEquals("已获回执不得再报「未获确认」", 0L, session.configAckTimeoutCount.get())
    }

    fun test回执超时_告警一次且不重复() {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val session = newSession(7502L, scheduleAckCheck = { d, t -> scheduled += d to t })

        session.sendConfig(disabled = true, desensitize = false)
        assertTrue(session.hasPendingConfigAck())

        scheduled.single().second() // 驱动超时巡检（生产由 monitor 排定，测试同步执行）
        assertEquals("窗口内未收到回执 → 计一次告警", 1L, session.configAckTimeoutCount.get())
        assertFalse("超时后挂起清空（不再重复告警）", session.hasPendingConfigAck())

        session.onConfigAckTimeout(1L) // 同一 seq 再驱动：挂起已清 → 静默
        assertEquals("同一次下发只告警一次", 1L, session.configAckTimeoutCount.get())
    }

    // ---------- ② T-AgentResiduals R4：字面值断言 + 超时文案分态 ----------

    /**
     * R4-②：两条常量的**字面值**断言（复核 F7）。
     *
     * 原测试只有「排定窗 == `AgentManager.CONFIG_ACK_TIMEOUT_MS`」这种**常量对常量**的等式——
     * 把常量改成 50ms 或 500s 它照样绿，等于没有守卫。
     *
     * **为什么用反射读编译产物、而不是直接引用常量**（`assertEquals(5_000L, AgentManager.CONFIG_ACK_TIMEOUT_MS)`）：
     * `const val` 会被 Kotlin 编译期**内联**进引用点——增量编译下测试类未随常量改动重编译时，
     * 引用读到的是**编译那一刻**的旧值。R4 反向验证把两个方向都实测了：常量改 25L 而未重编译测试类 →
     * 断言仍绿（**假绿**）；改回 50L 而未重编译 → 断言报 `expected:<50> but was:<25>`（**假红**）。
     * 反射读的是主源集编译产物里的静态常量字段（javap 实证：`public static final long
     * INTAKE_DROP_WARN_STEP`），两个方向都不受内联影响。
     */
    fun test常量字面值_回执等待窗与入队丢弃告警步长() {
        assertEquals(
            "CONFIG 回执等待窗应为 5s 字面值（取值依据见常量 KDoc：回执是同一连接上毫秒级的一帧，" +
                "窗口只需容一次读循环轮转 + 调度抖动；改值须同步 KDoc 理由与本断言）",
            5_000L,
            constValue("CONFIG_ACK_TIMEOUT_MS"),
        )
        assertEquals(
            "入队丢弃告警步长应为 50 条字面值（取值依据见常量 KDoc：首条 + 每步长条各记一次，" +
                "逐条 WARN 会刷满 idea.log；改值须同步 KDoc 理由与本断言）",
            50L,
            constValue("INTAKE_DROP_WARN_STEP"),
        )
    }

    /** 反射读 `AgentManager` 伴生常量的**编译产物**值（绕过 `const val` 内联；见用例 KDoc）。 */
    private fun constValue(name: String): Long = AgentManager::class.java.getField(name).getLong(null)

    /**
     * R4-①：回执超时文案按**会话健康**分三态（复核 F3）。
     *
     * 纯函数面先钉死三态互斥与判据词（合成输入可直接喂，无需真 socket）；
     * 下面 [test超时文案_应用侧仍在跑] / [test超时文案_应用侧静默] 再用真 socket 端到端证明
     * 「巡检体把实况传对了」——两半都必要：只测纯函数会漏掉传参改坏，只测端到端则拿不到
     * 「连接已断」态（该态正常路径被 `clearPendingConfigAck` 提前挡掉，只有竞态窗口可达）。
     */
    fun test超时文案三态互斥且各含判据词() {
        val disconnected = configAckTimeoutMessage(
            pid = 1L, disabled = true, aliveNow = false, frameAfterSend = false, sinceLastFrameMs = 9_999L,
        )
        assertTrue("连接已断态必须叙述「控制通道已断开」：$disconnected", "控制通道已断开" in disconnected)
        assertFalse(
            "连接已断时不得叙述成「应用侧可能未生效」（连接都不在，与应用侧应用与否无关）：$disconnected",
            "应用侧可能未生效" in disconnected,
        )

        val appAlive = configAckTimeoutMessage(
            pid = 2L, disabled = false, aliveNow = true, frameAfterSend = true, sinceLastFrameMs = 120L,
        )
        assertTrue("应用侧在跑态必须叙述「应用侧仍在跑」：$appAlive", "应用侧仍在跑" in appAlive)
        assertTrue("应用侧在跑态应给出最近入站帧距巡检的毫秒数：$appAlive", "120ms" in appAlive)
        assertTrue("应用侧在跑态应点明旧版 agent 无回执这条成因：$appAlive", "旧版 agent" in appAlive)
        assertFalse("应用侧在跑时不得叙述成「应用侧可能未生效」：$appAlive", "应用侧可能未生效" in appAlive)

        val silent = configAckTimeoutMessage(
            pid = 3L, disabled = true, aliveNow = true, frameAfterSend = false, sinceLastFrameMs = 5_000L,
        )
        assertTrue("应用侧静默态才用「应用侧可能未生效」：$silent", "应用侧可能未生效" in silent)
        assertFalse("静默态不得叙述成连接已断：$silent", "控制通道已断开" in silent)
        assertFalse("静默态不得叙述成应用侧仍在跑：$silent", "应用侧仍在跑" in silent)
    }

    /** R4-① 端到端（臂 1）：下发后**仍收到入站帧** → 文案落在「应用侧仍在跑」。 */
    fun test超时文案_应用侧仍在跑() {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val session = newSession(7511L, scheduleAckCheck = { d, t -> scheduled += d to t })

        session.sendConfig(disabled = true, desensitize = false)
        // 下发之后再写一帧（HEARTBEAT）：应用侧「活着」的直接证据，且不清挂起（只有 ACK 才清）
        writeFrame(7511L, AgentProtocol.CMD_HEARTBEAT, "{}")
        awaitTrue("HEARTBEAT 应被读线程处理") { session.livenessFrameSeen }

        scheduled.single().second() // 到点驱动巡检
        assertEquals("仍计一次回执超时", 1L, session.configAckTimeoutCount.get())
        val message = session.lastConfigAckTimeoutMessage
        assertNotNull("巡检体应留下文案（测试缝）", message)
        assertTrue(
            "下发后仍有入站帧时，文案必须是「应用侧仍在跑」而非「应用侧可能未生效」：$message",
            "应用侧仍在跑" in message!! && "应用侧可能未生效" !in message,
        )
    }

    /** R4-① 端到端（臂 2）：下发后**一条入站帧都没有** → 文案落在「应用侧可能未生效」。 */
    fun test超时文案_应用侧静默() {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val session = newSession(7512L, scheduleAckCheck = { d, t -> scheduled += d to t })

        session.sendConfig(disabled = true, desensitize = false)
        scheduled.single().second() // 不写任何帧：应用侧静默
        assertEquals(1L, session.configAckTimeoutCount.get())
        val message = session.lastConfigAckTimeoutMessage
        assertNotNull("巡检体应留下文案（测试缝）", message)
        assertTrue(
            "下发后无任何入站帧时，文案应为「应用侧可能未生效」：$message",
            "应用侧可能未生效" in message!!,
        )
        assertFalse("静默态不得叙述成连接已断：$message", "控制通道已断开" in message)
        assertFalse("静默态不得叙述成应用侧仍在跑：$message", "应用侧仍在跑" in message)
    }

    fun test畸形回执_按未确认处理() {
        val session = newSession(7503L)
        val before = AgentProtocol.configAckParseFailures.get()

        writeFrame(7503L, AgentProtocol.CMD_CONFIG_ACK, """{"disabled":false, "desensitize":true,"skipPrefixes":0}""")
        awaitTrue("畸形回执应被计数") { AgentProtocol.configAckParseFailures.get() > before }

        assertEquals("畸形回执不得计入已确认", 0L, session.configAckCount.get())
        assertNull("畸形回执不得写入确认态（保持未确认）", session.configAckDisabled)
    }

    fun test关闭会话_清挂起与确认态() {
        val session = newSession(7504L)
        session.sendConfig(disabled = false, desensitize = false)
        writeFrame(7504L, AgentProtocol.CMD_CONFIG_ACK, AgentProtocol.configAckBody(false, true, 0, 0))
        awaitTrue("回执应被读线程处理") { session.configAckDisabled == false }

        session.close()

        assertNull("会话关闭即清确认态（挂起状态按会话，不跨重连残留）", session.configAckDisabled)
        assertFalse(session.hasPendingConfigAck())
    }

    // ---------- ③ 追加 P-5：回执驱动的桥闸门 ----------

    fun test在线但未确认_桥不封() {
        val manager = freshManager()
        val session = newSession(7601L, manager)
        manager.sessions.add(session)
        manager.onCaptureConfirmationChanged()

        assertTrue("前置：会话健康（在线）", manager.online)
        assertFalse("未确认应用侧在采集 → 不得封桥（封着桥 + agent 静默 = 零捕获且无信号）", SqlCaptureGate.paused.get())
        assertFalse(manager.captureConfirmedActive())
    }

    fun test在线且确认采集_桥封() {
        val manager = freshManager()
        val session = newSession(7602L, manager)
        manager.sessions.add(session)

        writeFrame(7602L, AgentProtocol.CMD_CONFIG_ACK, AgentProtocol.configAckBody(false, true, 0, 0))
        // 等**被断言的那个观测量**（闸门），不等中间态：确认值写入与回调刷新闸门是读线程上的两步，
        // 等中间态会与读线程赛跑（首跑曾因此假红一次）
        awaitTrue("确认在采集后桥应让位（回执经回调刷新闸门）") { SqlCaptureGate.paused.get() }

        assertEquals("回执生效值已写入会话", false, session.configAckDisabled)
        assertTrue("确认在采集 → 桥让位（不引入双通道重复产出，T-M56-02 闸门本意）", SqlCaptureGate.paused.get())
        assertTrue(manager.captureConfirmedActive())
    }

    fun test确认停采_桥不封() {
        val manager = freshManager()
        val session = newSession(7603L, manager)
        manager.sessions.add(session)

        writeFrame(7603L, AgentProtocol.CMD_CONFIG_ACK, AgentProtocol.configAckBody(true, true, 0, 0))
        awaitTrue("回执应被读线程处理") { session.configAckDisabled == true }

        assertFalse("确认停采不得算「应用侧在采集」", manager.captureConfirmedActive())
        assertFalse("应用侧确认已停采 → 桥恢复解析兜底（P-5 要防的零捕获）", SqlCaptureGate.paused.get())
    }

    fun test会话摘除_桥回落不封() {
        val manager = freshManager()
        val session = newSession(7604L, manager)
        manager.sessions.add(session)
        writeFrame(7604L, AgentProtocol.CMD_CONFIG_ACK, AgentProtocol.configAckBody(false, true, 0, 0))
        awaitTrue("确认采集后桥应封") { SqlCaptureGate.paused.get() }

        session.close()
        manager.sweepOffline()

        assertFalse("会话摘除 → 确认态随会话表消失，桥回落", manager.captureConfirmedActive())
        assertFalse("会话摘除后桥不得保持封着", SqlCaptureGate.paused.get())
    }

    // ---------- ④ T-AttachZeroEvent P3：接管沿的通道纪元重建（2026-10-09 现场复盘 + O-147 W2-补） ----------

    /**
     * 12:54 现场：接管前 60s 内控制台桥刚捕过同端点 → 接管沿后同 SQL 的 agent 事件全部落
     * 去重窗口内被当重复丢弃 → 面板零新条目、「attach 即生效」看似失效。守卫：接管沿（桥让位）
     * 必须重建**本项目**缓冲的去重索引——**陈旧**桥纪元键清掉，同键 agent 事件（窗口内）照常入队。
     * 反向验证锚点：摘掉 [AgentManager.updateOnline] 的 rebuildDedupeIndexAfterHandoff 接线本用例必红。
     */
    fun test接管沿_陈旧桥纪元键不吞新纪元首批() {
        val manager = freshManager()
        val logManager = logManager()
        logManager.entries.paused = false
        logManager.entries.dedupeEnabled = true
        val sql = "select handoff_epoch_same_key"
        // 陈旧：接管前 60s 内桥捕过同端点（P3 现场形态）
        val bridgeEraTs = System.currentTimeMillis() - 60_000L
        assertTrue(
            "前置：桥纪元同键条目在队（接管前桥捕过同端点）",
            logManager.entries.addEntry(LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), timestamp = bridgeEraTs)),
        )

        val session = newSession(7605L, manager)
        manager.sessions.add(session)
        writeFrame(7605L, AgentProtocol.CMD_CONFIG_ACK, AgentProtocol.configAckBody(false, true, 0, 0))
        awaitTrue("确认在采集后桥应让位（接管沿）") { SqlCaptureGate.paused.get() }

        assertTrue(
            "接管沿后同键 agent 事件（60s 窗口内）不得被陈旧桥纪元索引吞",
            logManager.entries.addEntry(LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), timestamp = System.currentTimeMillis())),
        )
    }

    /**
     * O-147 W2-补（2026-10-14 真机复验现场）：接管沿前**几毫秒**控制台条目入库（实测 6ms 窗口：
     * `捕获归因: 通道=CONSOLE_EP` 入库 → 接管沿 → 同一条 SQL 的 agent 副本），旧「全清」语义下 agent
     * 副本不再判重、新开一行 ⇒ 用户看到「一行带源码、一行无源码」的原始报障形态。
     * 守卫：新鲜键（5s 窗内）必须保留——agent 副本按融合升级为可跳转行，且同一次执行只留一行。
     * 反向验证锚点：把接线换回「全清」或把 grace 置 0 本用例必红。
     */
    fun test接管沿_新鲜桥纪元键按同一次执行融合() {
        val manager = freshManager()
        val logManager = logManager()
        logManager.entries.paused = false
        logManager.entries.dedupeEnabled = true
        val sql = "select handoff_fresh_same_key"
        assertTrue(
            "前置：接管沿前毫秒级入库的控制台副本在队（无源码/无耗时）",
            logManager.entries.addEntry(LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), timestamp = System.currentTimeMillis())),
        )

        val session = newSession(7606L, manager)
        manager.sessions.add(session)
        writeFrame(7606L, AgentProtocol.CMD_CONFIG_ACK, AgentProtocol.configAckBody(false, true, 0, 0))
        awaitTrue("确认在采集后桥应让位（接管沿）") { SqlCaptureGate.paused.get() }

        assertFalse(
            "同一次执行的 agent 副本应融合（不新开行）",
            logManager.entries.addEntry(
                LogSqlEntry(
                    rawSql = sql,
                    restoredSql = sql,
                    params = emptyList(),
                    timestamp = System.currentTimeMillis(),
                    executionTimeMs = 3,
                    sourceFile = "a/A.java",
                    sourceLine = 7,
                    sourceCandidates = listOf("a/A.java" to 7),
                    captureProvenance = CaptureProvenance.AGENT,
                ),
            ),
        )
        val rows = logManager.entries.snapshot().filter { it.restoredSql == sql }
        assertEquals("同一次执行只留一行", 1, rows.size)
        assertEquals("且该行已带源码（可跳转）", "a/A.java", rows.single().sourceFile)
    }
}

