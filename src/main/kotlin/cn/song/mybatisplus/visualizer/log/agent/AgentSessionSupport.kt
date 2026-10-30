package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.LogTimeCorrelator
import cn.song.mybatisplus.visualizer.log.RunConfigurationProcessListener
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import cn.song.mybatisplus.visualizer.log.sqlLogManagerIfCreated
import cn.song.mybatisplus.visualizer.log.sqlLogManagerOrNull
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.log.logTimeKey
import cn.song.mybatisplus.visualizer.log.logTimeParamSignature
import cn.song.mybatisplus.visualizer.log.stampLogTime
import cn.song.mybatisplus.visualizer.util.FatalErrors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.Topic
import com.sun.tools.attach.VirtualMachine
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// AgentManager 的状态快照/Premain 通道/会话支撑类型
// （T-M53-II 2026-10-10：由 AgentManager.kt 原样搬出，同包同可见性，零行为改动。）
/** T-CaptureTruth T-CT-01：增强捕获状态行输入快照（纯数据，[agentStatusText] 入参，零平台依赖）。 */
internal data class AgentStatusSnapshot(
    val enabled: Boolean,
    val livePids: List<Long>,
    val everAttached: Boolean,
    /** T-ReachAudit T-RA-01：最近一次摘除是否因心跳过期（应用侧无响应，而非连接断开）。 */
    val heartbeatExpired: Boolean = false,
    /**
     * T-AgentHeartbeat T-05：最近一次摘除是否属「连接刚建立、首条心跳尚未到达」
     * （过期会话从未收到过 HELLO 之外的存活帧）。与 [heartbeatExpired] **可同时置位**（不是互斥，
     * 独立复核订正）：多会话同轮过期时可两者都为真，此时叙事以 [heartbeatExpired] 优先。
     */
    val firstHeartbeatPending: Boolean = false,
    /** T-AgentHeartbeat T-05：在线窗口秒数（文案带出的可核实读数；默认取协议常量，测试可注入短窗）。 */
    val heartbeatWindowSeconds: Long = AgentProtocol.ONLINE_WINDOW_MS / 1000,
    /**
     * T-CaptureCatchAll 券02：控制台桥活性回落已解封——agent 在线但长期零事件、控制台仍有 SQL 行，
     * 桥已恢复解析。状态行必须如实披露（用户看到「已挂载」却零条目时，这句是唯一解释）。
     */
    val activityFallbackActive: Boolean = false,
)

/**
 * T-CaptureTruth T-CT-01：增强捕获状态行文案（纯函数，全分支可单测）。
 *
 * 立项动因：原实现只有「有活会话 / 曾挂载 / 其余 null」三态——**「开关开着但从未授权」落进 null
 * 分支**，设置页状态行渲染成空白而复选框仍显示勾选，用户读作「开着且没事」，实际一条都捕不到
 * （T-CaptureTruth 根因之三；旧守卫断言「无会话应返回 null」把该行为钉死，本次一并改）。
 *
 * T-EnhancedCaptureDefaultOn（2026-10-19）：注入确认与拒绝冷却已移除，状态行不再有「未授权」类叙事。
 *
 * 各态：
 *  1. 关闭（!enabled）→ null（复选框未勾选，无需叙事）；
 *  2. 有活会话 →「已挂载 pid=…」；
 *  3. 曾挂载 + 心跳过期 → 带窗口秒数的可核实表述（T-AgentHeartbeat T-05 去因果断言）；
 *  4. 曾挂载 + 首条心跳未到 → 中性等待叙述（T-AgentHeartbeat T-05 新增）；
 *  5. 曾挂载 + 其余 → 连接中断回落；
 *  6. 从未挂载 → 等待应用进程启动后挂载。
 */
internal fun agentStatusText(s: AgentStatusSnapshot): String? {
    if (!s.enabled) return null
    if (s.livePids.isNotEmpty()) {
        val attached = MpSqlVisualizerBundle.message(
            "settings.enhancedCapture.status.attached",
            s.livePids.joinToString(",") { "pid=$it" },
        )
        // T-CaptureCatchAll 券02：解封态必须与「已挂载」同时出现——只说已挂载会让用户以为
        // agent 在产出；只说回退会丢掉「会话还在」这一事实。
        return if (s.activityFallbackActive) {
            attached + "｜" + MpSqlVisualizerBundle.message("settings.enhancedCapture.status.activityFallback")
        } else {
            attached
        }
    }
    if (s.everAttached) {
        // T-ReachAudit T-RA-01：心跳过期 = socket 未断而帧停，与真断连分开叙述。
        // T-AgentHeartbeat T-05：过期再分两因，且只报**可核实的事实**（已 N 秒未收到心跳）——
        // 旧文案「进程可能已卡死」是代码无从得知的因果断言（现场应用 JVM 又活了 7 分半）。
        if (s.heartbeatExpired) {
            return MpSqlVisualizerBundle.message(
                "settings.enhancedCapture.status.heartbeatLost",
                s.heartbeatWindowSeconds,
            )
        }
        return MpSqlVisualizerBundle.message(
            if (s.firstHeartbeatPending) {
                // 连接刚建立、首条心跳尚未到达：中性等待叙述，不得说成「心跳中断/停摆」
                "settings.enhancedCapture.status.firstHeartbeatPending"
            } else {
                "settings.enhancedCapture.status.fellBack"
            },
        )
    }
    return MpSqlVisualizerBundle.message("settings.enhancedCapture.status.authorizedWaiting")
}

/**
 * T-AgentResiduals R4-①：CONFIG 回执超时的**三态文案**（纯函数，全分支可单测）。
 *
 * 复核 F3 的现场：原实现只有一句「CONFIG 未获确认——应用侧可能未生效」，把三种成因（连接已断 /
 * 应用侧在跑但不回执 / 应用侧静默）混成一种叙述。**半死 socket 被判成「应用侧未生效」是错叙事**——
 * 连接都不在了，「应用侧应用与否」根本无从谈起，用户会去查应用侧配置而真正的处置是重连。
 *
 * 三态判据（调用方在巡检到点那一刻取实况）：
 *  - [aliveNow] = `false` → **控制通道已断开**（socket 断 / 读循环退出）；
 *  - [frameAfterSend] = `true`（下发之后仍收到入站帧）→ **应用侧仍在跑**，典型是旧版 agent 无回执
 *    能力或入站帧解析错位；
 *  - 两者皆非 → **应用侧静默**，这才是「可能未生效」真正对应的形态。
 *
 * 三种文案**互斥且各含判据词**（`控制通道已断开` / `应用侧仍在跑` / `应用侧可能未生效`），
 * 供 `AgentManagerIntakeGateTest` 按态断言——文案分叉是本项交付的主体，不能只测「有告警」。
 */
internal fun configAckTimeoutMessage(
    pid: Long,
    disabled: Boolean,
    aliveNow: Boolean,
    frameAfterSend: Boolean,
    sinceLastFrameMs: Long,
): String = when {
    !aliveNow ->
        "CONFIG 未获确认，且**控制通道已断开**（pid=$pid, 下发 disabled=$disabled 后 " +
            "${AgentManager.CONFIG_ACK_TIMEOUT_MS}ms 内未收到 CMD_CONFIG_ACK，且会话 socket 已断/" +
            "读循环已退出）——连接已断时「应用侧是否曾生效」无从确认，请以应用侧日志与实际采集" +
            "行为为准（面板条目真实性以本机应用侧为准）"
    frameAfterSend ->
        "CONFIG 未获确认，但**应用侧仍在跑**（pid=$pid, 下发 disabled=$disabled 后 " +
            "${AgentManager.CONFIG_ACK_TIMEOUT_MS}ms 内未收到 CMD_CONFIG_ACK；期间仍收到存活帧，" +
            "最近一条距巡检 ${sinceLastFrameMs}ms）——应用侧活着却不回执，典型是**旧版 agent " +
            "不支持回执**或入站帧解析错位；若条目仍在产出，属混合版本下的正常双通道（列表去重" +
            "窗口内折叠），不必按「开关未生效」处理"
    else ->
        "CONFIG 未获确认——应用侧可能未生效（pid=$pid, 下发 disabled=$disabled 后 " +
            "${AgentManager.CONFIG_ACK_TIMEOUT_MS}ms 内未收到 CMD_CONFIG_ACK，且期间无任何入站帧）；" +
            "控制通道可能失效（入站帧解析错位 / 旧版 agent 不支持回执）——面板条目真实性以本机应用侧为准"
}

/**
 * T-M56Phase2-Premain：一条待回连的 premain 通道——启动前协商出的 loopback 端口 + 其监听 socket。
 * [claimed] 由 accept 线程在收到回连时置位（[AgentManager.hasPendingPremainChannel] 与 prune 的判据）。
 */
/**
 * premain 通道（T-AgentReconnect T-03：生命周期 = 绑定到本次运行）。
 *
 * 一个通道 = 一个前置协商的监听端口 + 一个循环 accept 的守护线程，服务**一次运行**内 agent 的
 * 首次回连与后续全部重连。实现 [Disposable] 并注册进项目 disposable 树——socket 与 accept 线程
 * 都在 [dispose] 里收口（Disposer 树是既有踩坑：不注册会在 IDE 退出时被 ObjectTree 报泄漏）。
 */
internal class PremainChannel(
    val port: Int,
    val server: ServerSocket,
    val preparedAtMs: Long,
) : Disposable {

    /** 是否已等到**首次**回连（[AgentManager.hasPendingPremainChannel] 的跳过判据读它）。 */
    @Volatile
    var claimed: Boolean = false

    /** 首次回连到达时刻（0=尚未回连）；兜底 TTL 自此计时（T-AgentReconnect T-03）。 */
    @Volatile
    var claimedAtMs: Long = 0L

    /** 已接受的回连次数（首次 + 重连；上限 [AgentManager.MAX_PREMAIN_RECONNECTS]）。 */
    @Volatile
    var acceptCount: Int = 0

    /** 首次回连自报的 pid（0=HELLO 未取到）；进程存活判定依据（运行结束的旁证）。 */
    @Volatile
    var pid: Long = 0L

    /** accept 线程引用（dispose 时打断——线程与 socket 同随 disposable 树释放）。 */
    @Volatile
    var acceptThread: Thread? = null

    /** 通道是否已关闭（accept 循环退出判据）。 */
    @Volatile
    var closed: Boolean = false

    /**
     * T-PremainReattach S1：暂停待重连态（关开关 [AgentManager.shutdownAll] 对 claimed 通道的分流语义）。
     * 暂停 = accept 循环退出、**监听 socket 保留**——agent 侧 SenderLoop 重连在内核 backlog 停泊
     * （不被 accept 也就不计入 agent 侧「连上即被关」的 [放弃端口计数]，暂停期可任意长），重开后
     * [AgentManager.resumePremainChannel] 重建 accept 循环，第一个停泊连接即被取回重建会话，
     * 跳过注定失败的 loadAgent（agentmain 不重入，新端口送不进去）。
     */
    @Volatile
    var paused: Boolean = false

    /**
     * T-ReleasePerf8（S5 挂死修复）：accept 循环代际。每次起 accept 循环（[AgentManager.openPremainChannel]
     * / [AgentManager.resumePremainChannel]）递增取新代；旧循环在 accept 返回/超时拍处发现失主即让位
     * 退出——不依赖 `paused`（pause→resume 竞态下 paused 先被 resume 翻回 false，旧循环若只依赖它会
     * 失去全部退出条件而无限 tick，S5 发行门禁轮实测挂 75min）。
     */
    @Volatile
    var acceptGeneration: Int = 0

    /** 关闭通道（幂等）：置位 + 关监听 socket——阻塞中的 accept 立即抛 SocketException 退出。 */
    fun closeChannel() {
        closed = true
        runCatching { server.close() }
    }

    /** 项目 dispose 路径：关 socket + 打断 accept 线程。 */
    override fun dispose() {
        closeChannel()
        acceptThread?.interrupt()
    }
}

/**
 * 单个 agent 会话：读线程循环解帧（EVENT → 适配 → buffer；HEARTBEAT 刷新在线窗口；
 * HELLO 记录 agent 侧 pid；CONFIG_ACK 记录生效值并清挂起），CONFIG 下发。alive = socket 未断。
 *
 * T-AgentConfigChannel T-02/T-03 两条新增语义：
 *  - **回执可观测**：每次 [sendConfig] 记一次挂起，窗口内未收到 [AgentProtocol.CMD_CONFIG_ACK]
 *    即 WARN「CONFIG 未获确认——应用侧可能未生效」（排定复用管理器的 monitor，不新开线程）；
 *  - **入队闸门**：EVENT 帧在 SQL 监听关闭（[SqlLogManager.capturing] == false）时**不入队**并计数
 *    ——即便应用侧再次失控投递，面板显示也不说谎（只闸入队点，MCP 审计/历史回读不经此处）。
 */
internal class AgentSession(
    private val project: Project,
    val pid: Long,
    private val socket: Socket,
    private val onStateChange: () -> Unit,
    /**
     * T-ReachAudit T-RA-01：时间源（默认系统时钟）。与 [AgentManager.clockMs] 同源注入——心跳窗口判定
     * 跨「会话记 lastSeen」与「管理器算过期」两侧，两侧时钟不同源则测试无法推进时间（假时钟一注入，
     * 真实时间戳的 lastSeen 会让会话永远健康），真机也无差异。
     */
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    /**
     * T-PS5-04：连接 URL 首次捕获/变化回调（默认空操作）——管理器据此把推断 URL 回写
     * [AgentUrlRegistry]。只在 URL **变化**时触发（每个 SQL 事件都带 URL，逐事件回调无意义）。
     */
    private val onUrlCaptured: () -> Unit = {},
    /**
     * T-AgentConfigChannel T-02：CONFIG 回执超时巡检的排定入口（默认空操作 = 测试不排定）。
     * 生产链路注入 [AgentManager] 的 monitor 排定（见 `AgentManager.scheduleConfigAckCheck`）——
     * 不新开常驻线程；测试可直接持有任务引用同步驱动，无需真等 5s。
     */
    private val scheduleConfigAckCheck: (Long, () -> Unit) -> Unit = { _, _ -> },
    /**
     * 追加 P-5（计划 §五 P-5）：应用侧采集确认态变化通知（收到回执时触发，默认空操作）。
     * 生产链路由 [AgentManager] 注入 → 重算 [SqlCaptureGate.paused]（见
     * `AgentManager.captureConfirmedActive`）。
     */
    private val onCaptureConfirmationChanged: () -> Unit = {},
    /**
     * R53：用户暂停闸门（默认 false = 不暂停）。生产由 [AgentManager] 注入 `{ userPaused }`——
     * 暂停只停 agent 通道（EVENT 在入队点丢弃），连接/心跳/CONFIG ACK 链不动（暂停≠关闭）。
     * internal 可注入为同模块守护测试缝（照 [listenActiveProvider] 先例）。
     */
    internal var pausedProvider: () -> Boolean = { false },
) {

    private val LOG = Logger.getInstance(AgentSession::class.java)

    @Volatile
    var alive: Boolean = true
        private set

    @Volatile
    var lastSeen: Long = clockMs()
        private set

    private val output = java.io.DataOutputStream(socket.getOutputStream())
    private val eventDropped = AtomicLong()

    /**
     * T-AgentConfigChannel T-03：入队闸门丢弃计数（监听关闭状态下投递的 EVENT 条数）。
     * 单调递增——它是「控制通道可能失效」的量化证据（应用侧仍在采集投递），不只丢弃了事。
     */
    internal val intakeDropped = AtomicLong()

    /**
     * R53：用户暂停态丢弃计数（暂停期间投递的 EVENT 条数）。单调递增——「暂停中」不是「通道断了」：
     * 计数在涨 = agent 通道仍活着、只是被用户停掉展示；结合 [AgentEventAdapter.receivedEvents]
     * （两者同帧同增）可区分「暂停拦截」与「通道失效」。
     */
    internal val pausedDropped = AtomicLong()

    /** T-AgentConfigChannel T-03：入队闸门判据（可注入测试缝；默认读 [SqlLogManager.capturing]）。 */
    internal var listenActiveProvider: () -> Boolean = {
        // 服务未创建/项目已销毁按 false —— 宁丢弃勿谎报（与 AgentManager.isListenActive 同口径）；
        // T-ST-02b2：读路径经 sqlLogManagerIfCreated（永不发起创建 → 不会毒化服务容器）
        sqlLogManagerIfCreated(project)?.capturing ?: false
    }

    /**
     * T-AgentConfigChannel T-02：已收到的 CONFIG 回执数（可观测/测试断言）。
     * 收到回执 = 应用侧确实解析并应用了控制指令，与「已下发」是两件事。
     */
    internal val configAckCount = AtomicLong()

    /** T-AgentConfigChannel T-02：回执超时告警数（每会话；>0 即「控制通道可能失效」）。 */
    internal val configAckTimeoutCount = AtomicLong()

    /** 挂起中的 CONFIG 回执（seq 区分「同一次下发的等待」，见 [onConfigAckTimeout]）。 */
    private class PendingConfigAck(
        val seq: Long,
        val disabled: Boolean,
        val sentAtMs: Long,
        /** 下发那一刻的 [inboundFrameCount]——超时文案据此判「下发之后是否还收到过入站帧」。 */
        val framesAtSend: Long,
    )

    private val pendingConfigAck = java.util.concurrent.atomic.AtomicReference<PendingConfigAck?>(null)
    private val configAckSeq = AtomicLong()

    /**
     * 入站帧计数（单调递增，含 HELLO 之外的**一切**帧）。
     *
     * 为什么不用 `lastSeen` 判「下发后是否还有入站」：`lastSeen` 在会话构造时就取了当前时刻，
     * 紧接其后的 CONFIG 下发会得到 `lastSeen == sentAtMs`，时钟粒度下**分不清**「刚建连、什么都没来」
     * 与「刚来过一帧」——正是超时文案要区分的那两种形态。计数器没有这个问题（严格比较大小）。
     */
    private val inboundFrameCount = AtomicLong()

    /**
     * T-AgentConfigChannel T-02：记录一次 CONFIG 下发并排定回执超时巡检。
     *
     * 只在下发**成功写出**后调用（写失败走既有降级，无回执可等）。同会话可连续下发（脱敏开关、
     * 监听开关），故用 seq 标记本次挂起：巡检到点时若挂起已被更新（更新的下发）或已被清（回执到达、
     * 会话关闭）则静默退出——**不会**为一次已被取代的下发误报「未获确认」。
     *
     * 同时记下发时刻（T-AgentResiduals R4-①）：超时文案要区分「连接已断」「应用侧在跑但没回执」
     * 「应用侧静默」三态，判据是「下发之后是否还收到过入站帧」。
     */
    private fun markConfigSent(disabled: Boolean) {
        val seq = configAckSeq.incrementAndGet()
        pendingConfigAck.set(PendingConfigAck(seq, disabled, clockMs(), inboundFrameCount.get()))
        scheduleConfigAckCheck(AgentManager.CONFIG_ACK_TIMEOUT_MS) { onConfigAckTimeout(seq) }
    }

    /**
     * T-AgentConfigChannel T-02：回执超时巡检体（internal 供测试同步驱动，生产由 monitor 排定）。
     *
     * 到点时仍挂着**同一次**下发 → 计一次并 WARN：这是本轮缺陷从「只能靠行为反推」变成
     * 一行日志的直接判据。
     *
     * **T-AgentResiduals R4-①：文案分会话健康（复核 F3）**。原实现只有一句「应用侧可能未生效」，
     * 而三种成因对用户的处置完全不同、原句把它们混成一种：
     *  ① **连接已断**（`alive == false`：socket 断 / 读循环已退出）——此时 CONFIG 是否曾生效
     *     **无从确认**，说「应用侧未生效」是**错叙事**（连接都不在了，与应用侧应用与否无关）；
     *     正常路径下掉线会清挂起（见 [clearPendingConfigAck]），本分支覆盖的是
     *     「清挂起与巡检并发」的竞态窗口，以及半死 socket（TCP 未察觉、读循环尚未退出）；
     *  ② **应用侧在跑却没回执**（下发后仍收到存活帧）——应用侧确实活着，典型是**旧版 agent
     *     不支持回执**或入站帧解析错位；此时若条目仍在产出，属**混合版本下的正常双通道**（见
     *     [configAckDisabled] 的边界说明），不该让用户以为「开关没生效」；
     *  ③ **应用侧静默**（下发后一条入站帧都没有）——这才是原句真正要说的形态。
     *
     * 三态**共用同一个计数器**（[configAckTimeoutCount]：它记的是「回执超时」这一事实，与成因无关），
     * 只有文案分叉；测试按文案分态断言（见 `AgentManagerIntakeGateTest`）。
     */
    internal fun onConfigAckTimeout(seq: Long) {
        val pending = pendingConfigAck.get() ?: return
        if (pending.seq != seq) return // 已被更新的下发取代或已收到回执
        if (!pendingConfigAck.compareAndSet(pending, null)) return
        configAckTimeoutCount.incrementAndGet()
        // 健康判据一律取「到点那一刻」的实况：alive 为 volatile、帧计数单调递增
        val aliveNow = alive
        val frameAfterSend = inboundFrameCount.get() > pending.framesAtSend
        val sinceLastFrameMs = (clockMs() - lastSeen).coerceAtLeast(0L)
        val message = configAckTimeoutMessage(pid, pending.disabled, aliveNow, frameAfterSend, sinceLastFrameMs)
        lastConfigAckTimeoutMessage = message // 测试缝：文案分态靠它断言（日志本身在单测里读不到）
        LOG.warn(message)
    }

    /** T-AgentConfigChannel T-02：清挂起（收到回执 / 会话关闭 / 掉线——挂起状态按会话，不得跨会话残留）。 */
    private fun clearPendingConfigAck() {
        pendingConfigAck.set(null)
    }

    /** T-AgentConfigChannel T-02：当前是否有挂起中的 CONFIG 下发（测试/诊断）。 */
    internal fun hasPendingConfigAck(): Boolean = pendingConfigAck.get() != null

    /**
     * T-AgentResiduals R4-① 测试缝：最近一次回执超时文案（`null` = 尚未发生过超时）。
     *
     * 为什么留这条缝：三态文案的分叉是本次交付的**主体**，而日志在单测里读不到（平台 Logger 无
     * 内存捕获面）——若只测纯函数 [configAckTimeoutMessage]，就漏了「巡检体是否把实况传对了」
     * 这一半（恰好是本轮最容易被改坏的一半：alive/帧计数快照）。故巡检体把成品文案记在这里，
     * 由 `AgentManagerIntakeGateTest` 端到端断言（真 socket 写帧 → 驱动巡检 → 读文案）。
     * 生产链路零读取，仅一个 volatile 写。
     */
    @Volatile
    internal var lastConfigAckTimeoutMessage: String? = null
        private set

    /**
     * 追加 P-5：应用侧**已确认**的采集开关值（`null` = 未确认/待确认）。
     *
     * 与「已下发」严格区分：本字段只由 [AgentProtocol.CMD_CONFIG_ACK] 写入，每次 [sendConfig]
     * 下发即清回 `null`（下发成功 ≠ 应用侧生效——本轮缺陷正是这两件事被混为一谈）。
     * 读侧见 `AgentManager.captureConfirmedActive`（桥闸门依据）。
     *
     * 清空而非保留旧值：控制通道再出问题时，闸门必须回到「不确定就不封桥」这一侧——
     * 宁可短暂双通道产出（列表去重窗口内折叠），也不接受「封着桥 + 应用侧静默 = 零捕获且无信号」。
     *
     * **已知边界（T-AgentResiduals R4-③，事实登记，行为不变）**：不含回执能力的**旧版 agent**
     * 恒不回 ACK → 本字段恒 `null` → 桥永久放行 → 双通道共存（同 [captureConfirmedActive] 的边界段）。
     * 用户可见结果由列表去重窗口保证正确；该形态不修，理由同上（改判据=回到有害的「按在线封桥」）。
     */
    @Volatile
    internal var configAckDisabled: Boolean? = null
        private set

    /** T-DialectAutoInfer：最近一次事件携带的连接 URL（connect 时捕获，会话内记忆；null=旧版 agent/未捕获）。 */
    @Volatile
    var lastConnectionUrl: String? = null
        private set

    /** T-AgentLogTime：日志时间未命中计数（诊断——键不等 vs 时序赶不上，真机排障一眼分辨）。 */
    private val timeMiss = AtomicLong()

    /**
     * T-AgentHeartbeat T-05：是否收到过 **HELLO 之外的存活帧**（HEARTBEAT 或 EVENT）。
     *
     * 语义只服务**状态行叙事**，不参与协议判定：读循环对**所有**帧都刷新 [lastSeen]，
     * 于是「连接刚建立、首条心跳还没到」与「曾有心跳、之后停摆」在 [lastSeen] 上完全同形，
     * 摘除时区分不出来——而两者对用户的处置完全不同（前者等一等，后者去看应用侧）。
     * HELLO 是连接首帧、只证明 socket 建起来了，不证明应用侧越过启动期，故不计入存活帧。
     *
     * 读线程写、巡检线程（[AgentManager.sweepOffline]）读，故 @Volatile。
     */
    @Volatile
    var livenessFrameSeen: Boolean = false
        private set

    init {
        socket.soTimeout = 0
        Thread({
            try {
                val input = DataInputStream(socket.getInputStream())
                while (true) {
                    val len = input.readInt()
                    if (len < 0 || len > AgentProtocol.MAX_FRAME_LEN) {
                        // 超限帧容错：读掉 body 丢弃继续，不杀会话（一次大事件不应永久掉线）
                        LOG.warn("frame len=$len 超限（MAX=${AgentProtocol.MAX_FRAME_LEN}）——丢弃该帧继续")
                        if (len > 0) input.skipNBytes(len.toLong())
                        continue
                    }
                    val cmd = input.readUnsignedByte()
                    val body = ByteArray(len)
                    input.readFully(body)
                    lastSeen = clockMs()
                    inboundFrameCount.incrementAndGet() // R4-①：超时文案判「下发后是否还有入站」的依据
                    val bodyText = String(body, Charsets.UTF_8)
                    // T-CaptureCatchAll 券03：attach 覆盖自检计数（可选键，旧版 agent 无 → 零动作）。
                    // 放在 handleFrame 之前：计数上报帧可能是任何命令（C1 路当前挂在心跳里），
                    // 与主链分发解耦；解析失败在内部吞掉，绝不影响事件处理（红线）。
                    AgentAttachReport.updateFromFrame(bodyText)
                    handleFrame(cmd, bodyText)
                }
            } catch (t: Throwable) {
                if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传（读线程：透传即线程终止，平台记录现场）
                LOG.warn("会话读循环退出（pid=$pid, alive 前=$alive）——掉线原因如下", t)
                alive = false
                // T-AgentConfigChannel T-02：挂起状态按会话——掉线即清（重连是新会话，旧挂起不得残留）
                clearPendingConfigAck()
                onStateChange()
            }
        }, "mps-agent-session-$pid").apply { isDaemon = true }.start()
    }

    private fun handleFrame(cmd: Int, body: String) {
        when (cmd) {
            AgentProtocol.CMD_EVENT -> {
                // T-AgentHeartbeat T-05：EVENT 与 HEARTBEAT 同证「应用侧活着且已越过启动期」
                livenessFrameSeen = true
                val event = AgentEvent.parse(body)
                if (event == null) {
                    LOG.warn("EVENT 帧解析失败丢弃（pid=$pid, dropped=${eventDropped.incrementAndGet()}, bodyLen=${body.length}, head=${body.take(120)}）")
                    return
                }
                try {
                    // T-DialectAutoInfer：connect URL 会话内记忆（推断依据；null 容错——旧版 agent 无此键）
                    // T-PS5-04：URL 变化才回调（回写推断持有者；逐事件回调无意义）
                    event.url?.takeIf { it.isNotBlank() }?.let { url ->
                        if (url != lastConnectionUrl) {
                            lastConnectionUrl = url
                            onUrlCaptured()
                        }
                    }
                    // T-AgentConfigChannel T-03：**入队闸门**——监听关闭时条目不得进列表。
                    //
                    // 放在此处（URL 记忆之后、stampLogTime 之前）的两个理由：
                    //  ① 闸的是「入队点」本身（本任务边界：只作用于 agent 通道入队点，MCP 审计与
                    //     历史回读不经这里，行为不变）；URL 记忆是**方言推断依据**，与列表显示无关，
                    //     且既有用例 AgentManagerLazyMonitorTest 以非捕获态验证 URL 回写，闸在它之前会误伤；
                    //  ② stampLogTime 会**消费**日志时间相关器的暂存匹配（未命中才回落），先丢弃再
                    //     调用会把本该留给控制台通道的匹配白白取走。
                    if (!listenActiveProvider()) {
                        val n = intakeDropped.incrementAndGet()
                        if (n == 1L || n % AgentManager.INTAKE_DROP_WARN_STEP == 0L) {
                            LOG.warn(
                                "增强通道在监听关闭状态下投递了 $n 条——控制通道可能失效" +
                                    "（pid=$pid, 已丢弃不入队；首条/每 ${AgentManager.INTAKE_DROP_WARN_STEP} 条记一次）",
                            )
                        }
                        return
                    }
                    val entry = AgentEventAdapter.toEntry(event)
                    // T-DevAcceptFix2 ⑤（20261006 第二轮验收）：事件到达打点——收到即计数
                    //（含后续被去重/屏蔽/展示链丢弃的）。面板零产出时据此分流「事件到没到」：
                    // N=0 → 查 agent 侧（织入/门禁/传输）；N>0 → 查消费侧。
                    AgentEventAdapter.markReceived()
                    // T-AttachZeroEvent（2026-10-09 现场复盘）：receivedCount 此前零出口——「计数在涨」
                    // 与「面板零产出」并存时无法判「事件没到」还是「消费链丢了」。首条到达打一行 INFO
                    //（与宿主侧 WovenEvents 首条 stderr 行配对），三分故障域：宿主无首条=agent 侧；
                    // 宿主有、IDE 无=传输；两侧都有而面板空=消费链（去重/屏蔽/过滤/暂停）。
                    if (AgentEventAdapter.receivedEvents() == 1L) {
                        LOG.info("增强通道首条事件已到达并进入消费链（pid=$pid）——若面板仍零产出，查消费链（去重窗口/屏蔽/过滤），不再疑 agent 侧")
                    }
                    // R53：用户暂停闸门（在收到计数**之后**——暂停中「已收事件 N」照常增长，
                    // 状态栏据此显示「暂停中且通道活着」而非「通道断了」）。只丢入列表，
                    // 连接/心跳/CONFIG ACK 链一概不动（暂停≠关闭，V80② 自洽）。
                    if (pausedProvider()) {
                        val n = pausedDropped.incrementAndGet()
                        if (n == 1L || n % AgentManager.INTAKE_DROP_WARN_STEP == 0L) {
                            LOG.info(
                                "增强通道在用户暂停态收到 $n 条事件——丢弃不入队（暂停只停 agent 通道，" +
                                    "控制台通道照常；恢复即续采）",
                            )
                        }
                        return
                    }
                    // T-AgentDialectLoop / T-AgentLogTime：接管期间控制台嗅探暂存的「日志行原始时间」
                    // 按 [logTimeKey]（占位符版 SQL 指纹）取回，参数值序列作次级判别（[stampLogTime]）。
                    // 未命中回落捕获时间（= execute 开始时刻，与日志行只差毫秒级）并留痕 [logTimeMiss]。
                    // T-ST-02b2：读路径经 sqlLogManagerIfCreated（永不发起创建）；取不到即丢弃该事件，不斩断流
                    val logManager = sqlLogManagerIfCreated(project) ?: return
                    logManager.entries.addEntry(stampLogTime(entry) ?: logTimeMiss(entry))
                } catch (t: Throwable) {
                    if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
                    LOG.warn("EVENT 入队失败（不斩断流）", t)
                }
            }
            // T-AgentHeartbeat T-05：HEARTBEAT 是存活帧（EVENT 分支已在上面置位）
            AgentProtocol.CMD_HEARTBEAT -> livenessFrameSeen = true
            // T-AgentConfigChannel T-02：CONFIG 回执——应用侧「已生效」的唯一直接证据。
            // 与 HEARTBEAT/EVENT 同属存活帧（应用侧真在跑才会发）。
            AgentProtocol.CMD_CONFIG_ACK -> {
                livenessFrameSeen = true
                val ack = AgentProtocol.parseConfigAck(body)
                if (ack == null) {
                    LOG.warn(
                        "CONFIG 回执解析失败（pid=$pid, 累计失败=${AgentProtocol.configAckParseFailures.get()}, " +
                            "bodyLen=${body.length}, head=${body.take(120)}）——两侧回执形态漂移，按未确认处理",
                    )
                    return
                }
                configAckCount.incrementAndGet()
                clearPendingConfigAck()
                // 追加 P-5：确认值写入（桥闸门唯一真值来源）——disabled=false 才意味着「应用侧真在采集」
                configAckDisabled = ack.disabled
                LOG.info(
                    "CONFIG 已生效（pid=$pid, ACK#${configAckCount.get()}）：disabled=${ack.disabled}, " +
                        "desensitize=${ack.desensitize}, skipPrefixes=${ack.skipPrefixes}, " +
                        "bizJarPrefixes=${ack.bizJarPrefixes}",
                )
                // 追加 P-5：回执是桥闸门的唯一依据——收到即同步（不能在等下一次 updateOnline 才刷）
                onCaptureConfirmationChanged()
            }
            // HELLO/未知命令：lastSeen 已刷新；HELLO 只是连接首帧、不算存活帧；未知命令忽略（协议向前兼容）
            else -> Unit
        }
    }

    /**
     * T-AgentLogTime：日志时间未命中留痕——前 [MISS_LOG_LIMIT] 条逐条打印键与 SQL 头部
     * （判「两侧 rawSql 不等」），其后每 [MISS_LOG_STEP] 条打一次累计（判「时序赶不上」）。
     * 未命中只影响时间字段（回落 execute 开始时刻），不影响条目本身，故只记日志不动流程。
     */
    private fun logTimeMiss(entry: LogSqlEntry): LogSqlEntry {
        val n = timeMiss.incrementAndGet()
        if (n <= MISS_LOG_LIMIT) {
            LOG.info(
                "日志时间未命中 #$n（回落捕获时间）：key=${logTimeKey(entry)} " +
                    "params=${logTimeParamSignature(entry.params).take(60)} sql=${entry.rawSql.take(80)}",
            )
        } else if (n % MISS_LOG_STEP == 0L) {
            LOG.info("日志时间未命中累计 $n 条（最近：key=${logTimeKey(entry)} 相关器存量=${LogTimeCorrelator.size()}）")
        }
        return entry
    }

    fun sendConfig(disabled: Boolean, desensitize: Boolean =
        PluginSettings.getInstance().state.enhancedCaptureDesensitize) {
        try {
            // T-SourceJumpFunnel P1-3：随 CONFIG 附带用户登记的帧过滤前缀（热下发；旧 agent 忽略该键）
            val prefixes = PluginSettings.getInstance().state.sourceFrameSkipPrefixes
                .lineSequence().filter { it.isNotBlank() }.toList()
            // T-ToolSiteBizFrame：随 CONFIG 附带 JAR 业务帧反向名单（语义与跳过前缀相反，热下发）
            val bizPrefixes = PluginSettings.getInstance().state.sourceFrameBizJarPrefixes
                .lineSequence().filter { it.isNotBlank() }.toList()
            // T-StackHoverUi：随 CONFIG 热下发调用栈快照帧数预算（设置改动即生效，无需重附 agent）
            val stackMaxFrames = PluginSettings.getInstance().state.enhancedCaptureStackMaxFrames
            val body = AgentProtocol.configBody(disabled, desensitize, prefixes, bizPrefixes, stackMaxFrames)
                .toByteArray(Charsets.UTF_8)
            synchronized(output) {
                output.writeInt(body.size)
                output.write(AgentProtocol.CMD_CONFIG)
                output.write(body)
                output.flush()
            }
            // 追加 P-5：下发即转「待确认」——应用侧是否真的应用了这次 CONFIG，只有回执说了算。
            // 随后立即重算桥闸门：待确认（null）与「确认停采」在闸门上一视同仁，都不封桥——
            // 这是有意的取侧：宁可在一个 ACK 往返（毫秒级、且列表去重窗口会折叠同指纹条目）里
            // 短暂双通道产出，也不接受「桥封着 + 应用侧静默 = 零捕获且无任何可见信号」。
            // 注意次序：先清确认态再排超时巡检（巡检到点时读的是清空后的挂起，语义一致）。
            configAckDisabled = null
            onCaptureConfirmationChanged()
            markConfigSent(disabled)
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传（原状：静默置 alive=false，致命错误会丢现场）
            alive = false
            // T-AgentConfigChannel T-02：写失败即掉线——挂起与确认态一并清（无回执可等，也不该拿旧确认当真）
            clearPendingConfigAck()
            configAckDisabled = null
            onStateChange()
        }
    }

    fun close() {
        alive = false
        // T-AgentConfigChannel T-02：挂起/确认态按会话——关闭即清，不残留到重连后的新会话
        clearPendingConfigAck()
        configAckDisabled = null
        runCatching { socket.close() }
        onStateChange()
    }

    private companion object {
        /** 未命中逐条打印上限（前 N 条够看清两侧 rawSql 差异）。 */
        const val MISS_LOG_LIMIT = 3L

        /** 逐条打印用尽后的累计打印步长。 */
        const val MISS_LOG_STEP = 200L
    }
}

