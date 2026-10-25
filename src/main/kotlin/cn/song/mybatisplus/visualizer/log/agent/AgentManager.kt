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

/**
 * AgentManager（§4.4，M56 T-M56-01/02）：project 级服务，agent 生命周期全权管理。
 *
 * - attach 全链：agent jar 解压落盘（失败显式上报）→ 端口前置协商（ServerSocket(0)）→
 *   `VirtualMachine.attach(pid)` + loadAgent → 会话接受 → 在线态。
 * - 惰性挂载（Ultra 4.0.20 教训）：开启增强 + 注入确认后，后台线程等进程就绪 + 去抖 3s 再
 *   attach，全程不进 EDT——不拖慢项目启动。
 * - 幂等表：插件侧 pid 表 + agent 侧 system property 双保险（loadAgent 抛
 *   AgentLoadException("0") 实测容错——JDK8 二次挂载与 243 沙盒现代 JDK 首次挂载均观察过，
 *   见 AgentMain javadoc；握手成功为准）。
 * - 降级三分支：attach 失败 / 连接丢失 / 关闭开关 → 桥恢复（capturePaused=false），零劣化。
 * - 在线态：最近 [AgentProtocol.ONLINE_WINDOW_MS] 内有心跳/事件（§4.3）。
 * - 随 project dispose 摘除：会话全关 + 连接池线程停转。
 */
/**
 * T-SettingsTruth P2：增强捕获状态行广播（app 级 Topic，设置页面板订阅后刷新状态行）。
 */
interface AgentStatusListener {
    fun onStatusChanged()
}

@Service(Service.Level.PROJECT)
class AgentManager(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(AgentManager::class.java)
        const val AGENT_RESOURCE = "/agent/mps-sql-agent.jar"

        /** T-FirstUsePremainConsent S2：attach 兜底能力边界通知的独立分组（「一域一组」惯例）。 */
        const val AGENT_NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.agent"

        /** attach 排队去抖窗默认值（T-PS4-03）：同 pid 窗口内重复启动不重复排队（与挂载前静默等待解耦）。 */
        const val DEFAULT_ATTACH_DEBOUNCE_MS = 3_000L

        /** 挂载前静默等待（T-PS4-03 提常量与去抖窗解耦）：等应用初始化热点过去。 */
        private const val ATTACH_SETTLE_DELAY_MS = 3_000L

        // ---------- T-M56Phase2-Premain 常量 ----------

        /**
         * premain 通道等待 agent **首次**回连的时长：agent 侧 `SenderLoop` 连接失败按 1s→5s 退避**无限重连**
         * （SenderLoop.java:87），故本窗口只决定「IDE 等多久后放弃这个端口」，不是连接窗口。
         *
         * T-AgentReconnect T-03 语义拆分：此前本常量同时充当「通道总寿命」（accept 一次即关通道），
         * 于是会话一旦被摘除就再也回不来；现在它只管**首次**回连，认领后通道转入等重连阶段
         * （见 [PREMAIN_IDLE_TICK_MS] / [PREMAIN_CHANNEL_TTL_MS] / [onRunEnded]）。
         */
        internal const val PREMAIN_ACCEPT_TIMEOUT_MS = 120_000L

        /**
         * premain 通道**认领之后**的空转唤醒间隔（T-AgentReconnect T-03）：通道已等到首次回连，
         * accept 循环转为「等重连」，用短超时周期性复核运行结束信号与兜底 TTL，
         * 而不是无限阻塞在 accept 上（关闭 socket 虽也能唤醒 accept，但 TTL 需要自己的复核拍）。
         * T-ReleasePerf8：const→internal var＝同模块测试缝（P5 回归把拍调小到毫秒级加速旧循环让位）。
         */
        internal var PREMAIN_IDLE_TICK_MS = 30_000L

        /**
         * T-ReleasePerf8（S5 挂死修复）：[resumePremainChannel] 等旧 accept 线程退出的 join 预算。
         * internal var 而非 const＝同模块测试缝（回归用例覆写成毫秒级驱动「停等超预算」分支）。
         * 默认盖过旧线程最长合法停等：claimed 拍 [PREMAIN_IDLE_TICK_MS]（30s）+ HELLO 读 5s + 余量。
         */
        internal var PREMAIN_RESUME_JOIN_BUDGET_MS = 40_000L

        /**
         * premain 通道兜底 TTL（T-AgentReconnect T-03）：通道绑定到**本次运行**，正常由运行结束信号
         * （[onRunEnded]）关闭；本 TTL 只在信号漏掉的极端情况（钩子未触发且 pid 未知）兜底回收，
         * 防监听 socket 与 accept 线程长挂、防通道规模随运行次数增长撞 [MAX_PREMAIN_CHANNELS]。
         */
        internal const val PREMAIN_CHANNEL_TTL_MS = 12L * 60 * 60 * 1000

        /**
         * premain 通道回连次数上限（T-AgentReconnect T-03）：agent 断线无限重连，异常抖动
         * （对端反复建连即断）会把 accept 循环变成热循环，故设上限；超限关通道并留 WARN。
         */
        internal const val MAX_PREMAIN_RECONNECTS = 32

        /**
         * premain 通道在场时跳过 attach 的窗口（T-M56P2-04）：远小于 [PREMAIN_ACCEPT_TIMEOUT_MS]——
         * 窗口内认为「本次启动的 agent 会走 premain」，窗口外回归 attach 主路径。收窄的理由是
         * premain 失效（jar 被外部删除/目标 JVM 拒载）时不能让 attach 被长期压制（零劣化）。
         */
        internal const val PREMAIN_SKIP_ATTACH_WINDOW_MS = 30_000L

        /**
         * 同时挂起的 premain 通道上限：`updateJavaParameters` 每次 Run 都会触发，防重复准备泄漏 ServerSocket。
         *
         * T-PerfSweep6 T-PS6-06①② 登记的两处已知边界（有意保持现状，勿顺手「修」）：
         *  ① **上限按并发服役数计，不按累计 Run 次数计**——通道绑定到单次运行（[onRunEnded] 关闭），
         *     外加兜底 TTL 与 stale/pid 消亡清扫（[prunePremainChannels]），「规模随使用增长」的形态
         *     不存在；只有同时活跃的运行超过本值，第 5 个 Run 才拿不到通道，而那一次回落 attach/桥（零劣化）。
         *  ② **pid 未知时按 pid 的会话去重失效**（[onPremainConnected] 只在 pid>0 时判重）：同一应用
         *     反复回连不会被去重，但峰值有上界——每条通道的 accept 数受 [MAX_PREMAIN_RECONNECTS]=32
         *     封顶（达上限关通道并留 WARN；重新 Run 重注入即自愈），故「去重失效」形态下单通道至多
         *     产生 32 条无去重会话，且其中不健康者仍会被 [sweepOffline] 摘除。
         */
        internal const val MAX_PREMAIN_CHANNELS = 4

        /** HELLO 首帧读取超时（agent 连上后立即发 HELLO，秒级足够；超时按「pid 未知」处理不丢会话）。 */
        private const val HELLO_READ_TIMEOUT_MS = 5_000

        /**
         * T-AgentConfigChannel T-02：CONFIG 回执等待窗——[AgentSession.sendConfig] 后本窗口内未收到
         * [AgentProtocol.CMD_CONFIG_ACK] 即 WARN「CONFIG 未获确认——应用侧可能未生效」。
         *
         * 取值 5s 的理由：回执是「同一连接上、应用侧应用开关后立刻回一帧」，正常在毫秒级；
         * 窗口只需容一次读循环轮转 + 调度抖动。窗口过长会把「控制通道已失效」这条最要紧的告警
         * 推后到用户已经看到不该出现的条目之后。**不作为掉线判据**（掉线由心跳窗口判定）。
         */
        internal const val CONFIG_ACK_TIMEOUT_MS = 5_000L

        /**
         * T-AgentConfigChannel T-03：入队闸门告警步长——首次丢弃即 WARN，其后每本步长条记一次。
         * 关闭状态下投递的事件会持续成批到达（应用侧在跑、控制通道失效），逐条 WARN 会刷满 idea.log。
         */
        internal const val INTAKE_DROP_WARN_STEP = 50L

        /** T-PS5-12：HELLO 首帧 pid 提取正则（冷路径，提常量免每次连接重编译）。 */
        private val HELLO_PID_REGEX = Regex("\"pid\"\\s*:\\s*(\\d+)")

        fun getInstance(project: Project): AgentManager = project.getService(AgentManager::class.java)

        /** T-SettingsTruth P2：增强捕获状态变更 Topic（app 级；仿 PluginSettings.SETTINGS_CHANGE_TOPIC）。 */
        @JvmField
        val AGENT_STATUS_TOPIC: Topic<AgentStatusListener> = Topic.create(
            "MpSqlVisualizerAgentStatusChange",
            AgentStatusListener::class.java,
        )
    }

    /** 在线态：任一会话活跃且最近窗口内有心跳/事件。UI 状态行/接管切换依据。 */
    @Volatile
    var online: Boolean = false
        private set

    /**
     * R53（V80③ 最简口径）：用户暂停增强捕获——**暂停只停 agent 通道、控制台通道照常**。
     *
     * 语义三要点：
     *  - 暂停≠关闭：连接/心跳/CONFIG ACK 链一概不动（[AgentSession] 照常收发帧），只在**入队点**
     *    丢弃 EVENT（[AgentSession.pausedProvider]），恢复后 agent 通道照常产出；
     *  - 桥不封：暂停期间 [SqlCaptureGate.paused] 强制为 false——控制台桥恢复解析兜底，
     *    双通道不并存（agent 侧已丢），列表不出现重复条目；
     *  - 三态不冲突（V80③ 排查结论）：与拒绝冷却无关（冷却只作用于确认弹窗频率），
     *    与总开关正交——[shutdownAll]（关开关）与「关闭→再开启」上升沿（SETTINGS_CHANGE）均复位，
     *    不残留「暂停中但开关已关」的悬空态。
     *
     * EDT 写（暂停动作/设置监听）、读线程读（intake 闸门 provider）、monitor 读（桥闸门），@Volatile 足够。
     */
    @Volatile
    var userPaused: Boolean = false
        private set

    /**
     * R53：切换用户暂停态（工具窗暂停/恢复入口调用）。状态变化即重算桥闸门
     * （暂停 → 桥立即恢复，不等 5s 巡检拍）并广播（设置页状态行同步）。
     */
    fun setUserPaused(paused: Boolean) {
        if (userPaused == paused) return
        userPaused = paused
        LOG.info("增强捕获用户暂停切换：$paused（暂停只停 agent 通道，控制台通道照常；连接与 CONFIG 链不动）")
        updateOnline()
        publishStatusChanged()
    }

    /**
     * R53：健康会话 pid 列表（工具窗状态栏取数，无锁只读——同 [statusSummary] 口径）。
     * internal 为同模块守护测试缝（[sweepOffline] 同款）。
     */
    internal fun healthyPids(): List<Long> = sessions.filter { isSessionHealthy(it) }.map { it.pid }

    /** 会话表。internal 为同模块守护测试缝（T-PS4-03 sweepOffline 清表断言）；生产链路勿直接读写。 */
    internal val sessions = CopyOnWriteArrayList<AgentSession>()

    /**
     * T-PremainReattach：premain 会话提交段与关开关清表段的互斥锁。覆盖两段临界区——
     * [onPremainConnected] 的「提交门禁 + sessions.add + 幂等表登记」与 [shutdownAll] 的
     * 「会话关闭/清表 + 暂停分流」。没有它，首次会话构建（毫秒级）与关开关并发时，会话可能在
     * 清表**之后**才 add 进表（复活态：用户已关增强却仍在线采，关闭语义静默失效）。
     * 锁内无阻塞 I/O（socket 读写都在锁外；暂停分流的哑元自连是 loopback 亚毫秒级连接）；
     * sweepOffline 的死会话摘除不与此竞争（摘除面是已死会话，与新提交的健康会话不相交），不入锁。
     */
    private val sessionCommitLock = Any()

    /** 幂等表：pid → attach 时间戳（同一 pid 不二次 attach）。internal 为同模块守护测试缝（T-PS4-03）。 */
    internal val attachedPids = ConcurrentHashMap<Long, Long>()

    /**
     * T-PremainReattach P4：attach 装载进程的回连端口登记表（pid → [attachAndOffer] 供端口）。
     *
     * attach 链的 ServerSocket 是一次性的（accept 一次即随 `use{}` 关闭），agent 侧 SenderLoop
     * 之后重连的是这个已死端口——关开关再重开时 loadAgent 注不进去（agentmain 不重入），进程从此
     * 永久回落桥。本表在 attach 握手成功时登记端口，重开上升沿命中即**原端口 re-bind** 起 accept
     * 循环接住 SenderLoop 回连（D2 形态；re-bind 用 SO_REUSEADDR 容忍旧连接 TIME_WAIT）。恢复成功
     * 后该进程成为 claimed 通道（[premainChannels] 表内），后续关开走 premain 同一套暂停/恢复语义。
     * internal 为同模块测试缝（生产链路勿直接读写）。
     */
    internal val attachPorts = ConcurrentHashMap<Long, Int>()

    /**
     * 惰性挂载去抖（T-PS4-03 落地）：pid → 上次排队时刻——同 pid 在 [attachDebounceMs] 窗口内
     * 重复启动不重复排队（声明多年未用，本次兑现为真实去抖表；attach 任务完成时摘除条目，
     * 失败后的同 pid 后续启动可再排队，成功则由 attachedPids 幂等表兜底去重）。
     * internal 为同模块守护测试缝（T-PS4-01 静默跳过断言 / T-PS4-03 去抖落地）；生产链路勿直接读写。
     */
    internal val pendingPids = ConcurrentHashMap<Long, Long>()

    /**
     * T-M56Phase2-Premain：待回连的 premain 通道表（启动前协商的端口 → 监听 socket）。
     * internal 为同模块守护测试缝；生产链路勿直接读写。
     */
    internal val premainChannels = CopyOnWriteArrayList<PremainChannel>()

    // ---------- T-PS4-03 测试缝：去抖窗与时间源可注入（生产链路勿改默认值） ----------

    /** attach 排队去抖窗（默认 [DEFAULT_ATTACH_DEBOUNCE_MS]；测试注入短窗驱动去抖语义）。 */
    internal var attachDebounceMs: Long = DEFAULT_ATTACH_DEBOUNCE_MS

    /** 时间源（attach 排队去抖等使用；默认系统时钟，测试注入可控时钟）。 */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    /**
     * T-ReachAudit T-RA-01：心跳窗口（默认 [AgentProtocol.ONLINE_WINDOW_MS]；测试注入短窗驱动过期语义）。
     *
     * 此前该窗口只存在于协议常量与 KDoc（[AgentProtocol.ONLINE_WINDOW_MS] 全仓零消费、[AgentSession.lastSeen]
     * 有写无读），在线态只看 socket 是否断开——agent 侧停摆但连接仍在时 `online` 恒 true，控制台桥被
     * `SqlCaptureGate.paused` 一直封着，两条通道同时零产出且无超时恢复。现按协议既有语义落地。
     */
    internal var heartbeatWindowMs: Long = AgentProtocol.ONLINE_WINDOW_MS

    /**
     * T-ReachAudit T-RA-01：会话健康判定——socket 未断**且**窗口内有过心跳/事件。
     * 纯判定（可单测）：`now - lastSeen` 用注入时钟，故测试可推进时间而不真等 30s。
     */
    private fun isSessionHealthy(session: AgentSession): Boolean =
        session.alive && (clockMs() - session.lastSeen) <= heartbeatWindowMs

    /** T-ReachAudit T-RA-01：最近一次摘除是否因心跳过期（区分「连接断开」与「应用侧无响应」两类回落）。 */
    private val heartbeatDropDetected = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * T-AgentHeartbeat T-05：最近一次摘除是否属「连接刚建立、首条心跳尚未到达」
     * （过期会话里存在从未收到过 HELLO 之外存活帧者）。
     *
     * 与 [heartbeatDropDetected] **可同时置位**（不是互斥——独立复核订正）：多会话同轮过期、
     * 其中一条曾收到存活帧、另一条没有时，两个标志都为真。叙事上以「停摆」优先（[agentStatusText]
     * 先判 [heartbeatDropDetected]），故结果仍正确；但**不要**据本条去排除停摆。语义上本条只说明
     * 连接还在早期，不构成「应用侧无响应」的证据——现场正是这一类被误叙成
     * 「应用侧心跳中断（进程可能已卡死）」，而应用 JVM 又活了 7 分半。
     */
    private val firstHeartbeatPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 「关闭 → 再开启增强捕获」边沿检测（SETTINGS_CHANGE 监听重置拒绝冷却用）。 */
    private var lastKnownEnabled = PluginSettings.getInstance().state.enhancedCaptureEnabled

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mps-agent-manager").apply { isDaemon = true }
    }

    /** 在线态巡检（5s）：心跳窗口过期/连接死亡 → 会话摘除 → 桥回落。 */
    private val monitor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mps-agent-monitor").apply { isDaemon = true }
    }

    /**
     * T-PS5-04：巡检懒启标志——`monitor` 的 5s 定时任务由**首条会话建立**才挂上
     * （[ensureMonitorStarted]）。解耦前服务一实例化就排定时器：默认开智能推断的项目
     * 「预览一次即驻留」一条守护线程 + 5s 空转，即便增强捕获从未使用。
     */
    @Volatile
    private var monitorStarted = false

    /**
     * T-PerfSweep6 T-PS6-08：当前排定的 5s 巡检任务句柄（懒启时写入，[stopMonitor] 取消并置空）。
     *
     * 必须持有它才能真取消——`ScheduledFuture.cancel` 是唯一能停掉已排定周期任务的途径，
     * 只翻 [monitorStarted] 标志会留下一个仍在跑的旧任务（见 [stopMonitor] 注释）。
     */
    @Volatile
    private var monitorTask: java.util.concurrent.ScheduledFuture<*>? = null

    private val monitorLock = Any()

    init {
        // T-PS5-04：monitor 定时任务不再在此排定（见 ensureMonitorStarted）。
        // T-M56-08：设置变更驱动——开启 → 惰性挂载由进程出现回调承接；
        // 关闭 → CONFIG 停采集 + 会话关闭 + 桥回落；脱敏开关 → CONFIG 下发
        project.messageBus.connect(this).subscribe(
            PluginSettings.SETTINGS_CHANGE_TOPIC,
            cn.song.mybatisplus.visualizer.config.SettingsChangeListener {
                val state = PluginSettings.getInstance().state
                // T-EnhancedCaptureDefaultOn（2026-10-19）：关闭 → 再开启的上升沿只做两件事——
                // 复位用户暂停、扫描在跑进程补挂。注入确认与拒绝冷却已随确认流整体移除。
                if (state.enhancedCaptureEnabled && !lastKnownEnabled) {
                    // R53（V80③）：关闭→再开启的上升沿复位用户暂停——不残留「暂停中但开关重开」的悬空态
                    userPaused = false
                    LOG.info("设置变更：增强捕获重新开启——用户暂停已复位")
                    // 20261008 验收偏差 #6 修复（D120，拍板 A）：上升沿扫描本工程在跑 JVM 进程主动
                    // attach——此前 attach 只挂进程启动钩子，对已在跑的应用开开关无任何入口挂载
                    // （真机实测仅记本行日志，进程零挂载），验收文档 3.27「无需重启应用」不可达。
                    // 后台线程执行，attachedPids 幂等表防重复挂载；反射失败内部只 WARN 不影响设置链。
                    executor.execute {
                        val attached = RunConfigurationProcessListener
                            .scanAndAttachRunningProcesses(project, logTag = "settings-rescan")
                        if (attached > 0) {
                            LOG.info("设置变更上升沿：在跑进程补挂 $attached 个——存量连接下一次执行即被捕获")
                        }
                    }
                }
                lastKnownEnabled = state.enhancedCaptureEnabled
                if (!state.enhancedCaptureEnabled) {
                    // T-PS5-07：关闭下降沿复位 EP 短路标志——[SqlCaptureGate.epActive] 是「EP 在本会话
                    // 实证产出过条目」的**会话级粘滞**标志（置位点 SqlConsoleBridgeFilterProvider），
                    // 解耦前无任何复位点：关掉增强捕获再新建控制台时桥侧仍被粘滞标志短路 = 两条通道
                    // 同时零产出（静默零捕获的复发形态）。与上方上升沿复位用户暂停对称；EP 链路失效的
                    // 极端场景由短路计数 + debug 日志（epActiveShortCircuitCount）可观测兜底。
                    SqlCaptureGate.epActive = false
                    executor.execute { shutdownAll() }
                } else {
                    executor.execute { pushDesensitize(state.enhancedCaptureDesensitize) }
                }
            },
        )
    }

    // ---------- 挂载入口 ----------

    /**
     * T-PS5-04：巡检懒启 + 建立兜底清扫——首条会话建立时排定 5s 巡检，并**先兜底扫一次**
     * （巡检未启动的窗口期内可能已积压死会话，不能等首个 5s 拍）。新会话刚建（心跳新鲜）
     * 不会被本次清扫摘除。
     *
     * internal 为同模块守护测试缝（[sweepOffline] 同款：测试直连会话不经 attachToPid）。
     */
    internal fun ensureMonitorStarted() {
        synchronized(monitorLock) {
            if (!monitorStarted) {
                monitorStarted = true
                monitorTask = monitor.scheduleWithFixedDelay({ sweepOffline() }, 5, 5, TimeUnit.SECONDS)
            }
        }
        sweepOffline() // 兜底清扫（幂等；无死会话时零动作）
        fallbackSweepCount++
    }

    /**
     * T-PerfSweep6 T-PS6-06③ 测试缝：兜底清扫的执行次数（[ensureMonitorStarted] 末尾那次**同步**
     * [sweepOffline] 每执行一次 +1）。
     *
     * 守护测试（`AgentManagerPremainTest.test半死会话语境下同pid重连放行`）据此**确定性地**等待
     * 「首条会话的处理线程已越过兜底清扫」。不这样等会漏红：处理线程在 `sessions.add` 之后才调
     * [ensureMonitorStarted]，若测试在它尚未清扫时就推进假时钟，那条刚建好的「半死会话」会被这次
     * 兜底清扫直接摘除 → 第二次连接的去重分支根本不发生 → 用例在**旧判据（`alive`）下也恒绿**。
     * 独立复核实测：把判据退回 `alive` 后连跑 3 次得 fail / fail / **pass**（绿色那跑的日志里同 pid
     * 出现两次「premain 会话建立」且没有「丢弃重复连接」行）。
     *
     * `@Volatile`：自增发生在连接处理线程，读取发生在测试线程（跨线程可见性）。
     */
    @Volatile
    internal var fallbackSweepCount: Int = 0
        private set

    /**
     * T-PerfSweep6 T-PS6-08（性能报告发现 8）：巡检复位——取消 5s 定时任务并清懒启标志。
     *
     * **为什么必须持 [monitorTask] 而不是只翻标志**：`scheduleWithFixedDelay` 的返回值此前被丢弃，
     * 只把 `monitorStarted` 置回 false 并不能取消已排定的任务——下一次 [ensureMonitorStarted] 会在
     * 同一个单线程执行器上再排一个，5s 巡检变双份（且每轮开关一次多一份）。故必须存下
     * `ScheduledFuture` 并 [ScheduledFuture.cancel]。
     *
     * **执行器本体不关**：`cancel(false)` 只取消这一个周期性任务，`mps-agent-monitor` 线程随即回到
     * 空闲等待（守护线程、零 CPU），下次开启照常复用；`monitor.shutdownNow()` 只在项目 dispose
     * （服务生命周期终点）调用——提前关会让后续 enable 时 [ensureMonitorStarted] 抛
     * `RejectedExecutionException`，等于把「开关关一次」变成「本会话巡检永久失效」。
     */
    private fun stopMonitor() {
        synchronized(monitorLock) {
            monitorTask?.cancel(false)
            monitorTask = null
            monitorStarted = false
        }
    }

    /** T-PS5-04 测试缝：巡检是否已懒启（生产链路无调用点）。 */
    internal fun monitorStartedForTest(): Boolean = monitorStarted

    /**
     * T-PS5-04：把当前 agentUrl 回写 [AgentUrlRegistry]（推断层读该持有者，不再经
     * `getInstance` 触发服务实例化）。值取 [lastConnectionUrl]，语义与解耦前逐字一致；
     * 无会话/全离线 → null（清条目，推断回落数据源 URL）。
     *
     * internal 为同模块守护测试缝（测试直连会话驱动 URL 捕获回调）。
     */
    internal fun syncInferenceUrl() {
        AgentUrlRegistry.set(project, lastConnectionUrl())
    }

    /**
     * T-PS5-03/04：**会话生命周期点收口**——推断 URL 回写 + 数据源 URL 探测缓存失效。
     *
     * 探测缓存（[cn.song.mybatisplus.visualizer.sql.DataSourceUrlProbe]）失效双保险之一：
     * 会话建立/断开是「方言推断依据可能整体换了一套」的事件点（agent URL 出现/消失），
     * 顺带清掉数据源 URL 探测槽；另一重保险是 TTL 上界（数据源编辑后至多一个窗口滞后）。
     */
    internal fun onSessionLifecycleChanged() {
        syncInferenceUrl()
        cn.song.mybatisplus.visualizer.sql.DataSourceUrlProbe.invalidate(project)
    }

    /**
     * T-DialectAutoInfer：最近一次 agent 捕获的连接 URL（活跃会话优先，按会话加入序取最后值；
     * 全部离线/无会话 → null——推断层回落数据源探测）。纯读、无锁（CopyOnWriteArrayList 快照遍历）。
     *
     * T-PS5-04：本函数仍是唯一取数口径，但**推断层不再直调**——[syncInferenceUrl] 在会话
     * 生命周期点把结果写入 [AgentUrlRegistry]，推断层读持有者（零服务实例化）。
     */
    fun lastConnectionUrl(): String? =
        sessions.lastOrNull { it.alive && it.lastConnectionUrl != null }?.lastConnectionUrl
            ?: sessions.lastOrNull { it.lastConnectionUrl != null }?.lastConnectionUrl

    // UI-007：测试缝止于实际 attach/建会话边界，不用假 PID 的 attach 失败冒充安全门生效。
    internal var attachTargetInspector: (Long, Any?, Boolean) -> AgentAttachTarget.Decision = AgentAttachTarget::inspect
    internal var attachSessionFactory: (Long) -> AgentSession? = ::createSession
    internal var attachTaskDispatcher: (Runnable) -> Unit = { executor.execute(it) }

    private fun permitsAttachTarget(pid: Long, handler: Any?, readFiles: Boolean): Boolean {
        return when (attachTargetInspector(pid, handler, readFiles)) {
            AgentAttachTarget.Decision.HELPER, AgentAttachTarget.Decision.NON_JAVA -> {
                LOG.info("attach 目标排除：pid=$pid（IDE/构建辅助进程或非 Java 入口）")
                false
            }
            else -> true // UNKNOWN 保留既有授权及项目候选边界，不扩成系统扫描。
        }
    }

    internal fun isAttachCandidate(processHandler: Any?): Boolean {
        val pid = resolvePid(processHandler) ?: return false
        return permitsAttachTarget(pid, processHandler, readFiles = false)
    }

    /**
     * 进程出现回调（RunConfigurationProcessListener.processStarted 转接；增强未开启时空操作）。
     * 后台线程：进程就绪等待 + 3s 去抖 + attach——全程不进 EDT。
     *
     * T-EnhancedCaptureDefaultOn（2026-10-19）：注入确认闸门已整体移除——开关打开即挂载，
     * 不再区分主配置/非主配置（原区分只为决定是否弹确认框）。UI-007 目标身份门仍然保留：
     * 开关打开不等于 IDE/构建 helper 可注入，已知非目标进程始终排除。
     */
    fun onProcessAppeared(processHandler: Any?) {
        if (!isEnhancedEnabled()) {
            LOG.info("onProcessAppeared: 增强未开启（enabled=${PluginSettings.getInstance().state.enhancedCaptureEnabled}, " +
                "disposed=${project.isDisposed}）——跳过挂载")
            return
        }
        val candidatePid = resolvePid(processHandler)
        // T-PremainReattach S2/P4：暂停通道与 attach 端口命中必须先于目标探测——通道/端口本身
        // 已证明该 pid 曾是本插件目标，不能因本轮 target inspector 判为 NON_JAVA/HELPER 或
        // 启动文件读失败而丢掉「重开增强即恢复」的入口。
        if (candidatePid != null && (resumePausedPremainChannelIfHit(candidatePid) || resumeAttachPortIfHit(candidatePid))) {
            return
        }
        if (candidatePid != null && !permitsAttachTarget(candidatePid, processHandler, readFiles = false)) return
        doAttachAsync(processHandler)
    }

    /** 实际挂载（阻塞等待与 attach 全链在单线程 executor；T-PS4-03：pid 解析与排队去抖前移到请求线程）。 */
    private fun doAttachAsync(processHandler: Any?) {
        // T-M56Phase2-Premain（T-M56P2-04）：premain 通道在场 → 本次启动已注入 -javaagent，跳过 attach。
        // 反方向（premain 回连先到）由 attachedPids 兜底：回连时已写表，attachToPid 的 putIfAbsent 挡下。
        if (hasPendingPremainChannel()) {
            LOG.info("onProcessAppeared: premain 通道在场（启动即捕获已注入）——跳过 attach（T-M56Phase2）")
            return
        }
        // T-JRebelPremainCrash C 案：抑制窗已过仍未认领 ⇒ premain 实际降级（agent 侧 install 失败后
        // 不回连）。此处一次性告知 + 照常走下方 attach 回落（不再 return——降级恰恰是「必须回落」的场景）。
        detectPremainDegraded()
        // T-PS4-03：去抖必须在「排队时刻」判定——若迟到 executor 任务起跑才查表，静默等待已把
        // 窗口耗尽、判定失真。pid 解析是轻反射（getNativePid/getProcess().pid()，无阻塞等待），
        // 前移到请求线程安全；重活（静默等待 + attach）仍留在 executor，不进调用线程。
        val pid = resolvePid(processHandler)
        if (pid == null) {
            LOG.warn("onProcessAppeared: resolvePid 失败（handler=${processHandler?.javaClass?.name}）——跳过挂载")
            return
        }
        // T-PremainReattach S2/P4：暂停通道与 attach 端口命中必须先于目标探测——通道/端口本身
        // 已证明该 pid 曾是本插件目标，不能因本轮 target inspector 判为 NON_JAVA/HELPER 或需读
        // 启动文件而丢掉恢复入口（T-AttachTarget 接入后曾把 resume 挡在探测之后，重开增强失效）。
        if (resumePausedPremainChannelIfHit(pid)) return
        if (resumeAttachPortIfHit(pid)) return
        val target = attachTargetInspector(pid, processHandler, false)
        if (target == AgentAttachTarget.Decision.HELPER || target == AgentAttachTarget.Decision.NON_JAVA) return
        if (target == AgentAttachTarget.Decision.NEEDS_FILES) {
            // 启动文件读 I/O 不进 EDT；身份判定前不写 pendingPids、不启动 attach。
            attachTaskDispatcher(Runnable {
                if (isEnhancedActive() && permitsAttachTarget(pid, processHandler, readFiles = true)) {
                    queueAttach(pid, processHandler)
                }
            })
            return
        }
        queueAttach(pid, processHandler)
    }

    private fun queueAttach(pid: Long, processHandler: Any?) {
        // T-PremainReattach S2：pid 命中暂停通道 → resume 重建会话并跳过 loadAgent。settings-rescan
        // （重开增强上升沿补挂）与进程启动钩子两条路径都经本入口，单点收口防双路径漏；返回 false
        // （无命中，或暂停期间通道已被运行结束/dispose 关闭）则照旧回落下方 loadAgent 路径。
        if (resumePausedPremainChannelIfHit(pid)) {
            return
        }
        // T-PremainReattach P4：attach 装载进程关再开——原端口 re-bind 恢复（D2 形态），命中即跳过
        // loadAgent；无登记或 re-bind 失败照旧回落下方 loadAgent 路径。
        if (resumeAttachPortIfHit(pid)) {
            return
        }
        val now = clockMs()
        val lastQueued = pendingPids[pid]
        if (lastQueued != null && now - lastQueued < attachDebounceMs) {
            LOG.info("onProcessAppeared: pid=$pid 去抖窗内重复启动（距上次排队 ${now - lastQueued}ms < ${attachDebounceMs}ms）——不重复排队（T-PS4-03）")
            return
        }
        pendingPids[pid] = now
        attachTaskDispatcher(Runnable {
            try {
                LOG.info("onProcessAppeared: 目标 pid=$pid，${ATTACH_SETTLE_DELAY_MS}ms 静默等待后挂载")
                // 静默等待：等应用初始化热点过去（驱动已加载场景 retransform 直达；未加载场景 transformer 兜底）。
                // 单线程池串行性质：executor 仅此一条工作线程，跨 pid 的静默等待天然串行排队——
                // 多进程先后启动时后者在前者等待结束后才进入 attach，不会并发压目标 JVM，维持既有语义。
                Thread.sleep(ATTACH_SETTLE_DELAY_MS)
                attachToPid(pid, processHandler)
            } catch (t: Throwable) {
                if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
                LOG.warn("onProcessAppeared: 惰性 attach 失败（非致命，桥继续捕获）", t)
            } finally {
                pendingPids.remove(pid)
            }
        })
    }

    /** attach 全链（幂等表去重；测试可直调）。返回是否达到在线。 */
    fun attachToPid(pid: Long): Boolean = attachToPid(pid, null)

    private fun attachToPid(pid: Long, processHandler: Any?): Boolean {
        if (!isEnhancedActive()) return false
        // 最终入口也复核；直接 PID 调用不能绕过，排队期间身份变化也不得沿用旧结论。
        if (!permitsAttachTarget(pid, processHandler, readFiles = true)) return false
        if (attachedPids.putIfAbsent(pid, System.currentTimeMillis()) != null) {
            LOG.info("attachToPid: pid=$pid 已挂载（幂等表命中），跳过")
            return online
        }
        val session = try {
            attachSessionFactory(pid)
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
            attachedPids.remove(pid)
            LOG.warn("attachToPid: pid=$pid attach 失败——回落桥（零劣化）", t)
            return false
        }
        if (session == null) {
            // attach 已失败（容错后仍未在线）：回落桥
            attachedPids.remove(pid)
            return false
        }
        sessions.add(session)
        // B1：新 agent 默认 disabled=false（静态字段初始值），须按当前监听状态立即校正——
        // 否则「监听关闭状态启动项目」场景新 agent 又开始白采集。
        // T-AgentConfigChannel T-06：本行只保证「已下发」，生效以 CMD_CONFIG_ACK 为准（见 pushCaptureActive KDoc）
        val active = isListenActive()
        runCatching { session.sendConfig(disabled = !active) }
            .onFailure { LOG.warn("attachToPid: 初始 CONFIG 下发失败（pid=$pid）", it) }
        updateOnline()
        // T-PS5-04：首条会话建立 → 巡检懒启（含兜底清扫）+ 推断 URL 回写持有者（含探测缓存失效）
        ensureMonitorStarted()
        onSessionLifecycleChanged()
        // T-SettingsTruth P2：attach 成功 → 状态行「已挂载 pid=x」
        everAttached.set(true)
        // T-ReachAudit T-RA-01：新会话挂上即清「心跳过期」标记——回落文案只描述最近一次摘除原因
        heartbeatDropDetected.set(false)
        // T-AgentHeartbeat T-05：同一口径清「首条心跳未到」标记
        firstHeartbeatPending.set(false)
        publishStatusChanged()
        // T-FirstUsePremainConsent S2：attach 兜底挂载成功（能走到这里必非 premain 在场——
        // premain 通道在场的进程在 doAttachAsync 入口就跳过）→ 能力边界告知（每轮至多一次）
        notifyAttachFallbackOnce()
        return true
    }

    /**
     * T-FirstUsePremainConsent S2：attach 兜底挂载成功后的能力边界告知——agent 只在驱动 connect
     * 时包装连接，attach 前已存在的（池化）连接不可回溯包装，该轮 SQL 可能不全；如实告知
     * 「重新运行项目后完整生效」，不做虚假承诺。
     *
     * 频次收敛（用户反馈「气泡出现频繁影响体验」）：由「每轮运行至多一次」改为**每项目会话至多一次**
     * （[attachFallbackNotifiedThisSession] 不再随 [onRunEnded] 复位）。文案口径与首次语义不变——
     * 首次挂载成功仍如实告知能力边界；此后每轮挂载只走状态行「已挂载 pid=x」与日志，不再重复弹气泡，
     * 避免开发期反复重启项目 / 执行 SQL 时同一条告知反复打扰。
     */
    internal fun notifyAttachFallbackOnce() {
        if (!attachFallbackNotifiedThisSession.compareAndSet(false, true)) return
        LOG.info("notifyAttachFallbackOnce: attach 兜底挂载成功——发能力边界通知（本项目会话仅一次）")
        // 通知出口自吞异常（挂载已成功，通知失败不得打断链路）；fatal 按项目惯例透传（T-FP-02）
        runCatching { attachFallbackNotifier?.invoke() }
            .onFailure {
                if (FatalErrors.isFatal(it)) throw it
                LOG.warn("notifyAttachFallbackOnce: 能力边界通知发送失败（挂载不受影响）", it)
            }
    }

    /**
     * 通知出口（默认 balloon，测试模式跳过平台通知；测试注入计数器驱动断言）。
     */
    internal var attachFallbackNotifier: (() -> Unit)? = {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app == null || !app.isUnitTestMode) {
            runCatching {
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup(AGENT_NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("notification.agent.attachFallback.title"),
                        MpSqlVisualizerBundle.message("notification.agent.attachFallback.body"),
                        com.intellij.notification.NotificationType.INFORMATION,
                    ).notify(project)
            }
        }
    }

    /** attach 兜底通知已发标记（**每项目会话一次**，不随 [onRunEnded] 复位；internal 供测试复位）。 */
    internal val attachFallbackNotifiedThisSession = AtomicBoolean(false)

    // ---------- T-JRebelPremainCrash C 案：premain 降级感知 ----------

    /**
     * premain 降级的判定入口（T-JRebelPremainCrash C 案）。
     *
     * <p>「premain 失败」在 IDE 侧的唯一可观测信号是**通道始终未被认领**：agent 侧 `install` 失败后
     * 不再回连（`AgentMain` 的失败兜底不写 `sqlvis.agent.active`、不启 `EventBus`），于是
     * [PremainChannel.claimed] 永远为 false，accept 线程最后按
     * [PREMAIN_ACCEPT_TIMEOUT_MS] 超时收口。本方法在「通道已过 [PREMAIN_SKIP_ATTACH_WINDOW_MS]
     * 抑制窗、仍未认领」时把它判为降级。
     *
     * <p>为什么要显式判：抑制窗内 [hasPendingPremainChannel] 会让 attach 让位；窗口过后 attach 主路径
     * 恢复（零劣化的既有设计），但**用户侧没有任何提示**——在 JRebel 现场表现为「增强捕获悄悄只剩
     * 半条通道」，用户会以为插件坏了。本方法把这条静默降级变成一次性可见告知。
     *
     * <p>频次与 [notifyAttachFallbackOnce] 同口径：**每项目会话一次**，不随 [onRunEnded] 复位
     * （开发期反复重启不该反复弹窗）。返回是否本次新判定出降级。
     */
    internal fun detectPremainDegraded(): Boolean {
        val now = clockMs()
        val degraded = premainChannels.any { ch ->
            !ch.claimed && !ch.closed && now - ch.preparedAtMs > PREMAIN_SKIP_ATTACH_WINDOW_MS
        }
        if (!degraded) return false
        if (premainDegradedNotifiedThisSession.compareAndSet(false, true)) {
            LOG.warn(
                "detectPremainDegraded: premain 通道超过 ${PREMAIN_SKIP_ATTACH_WINDOW_MS}ms 未认领" +
                    "——启动即捕获未生效（agent premain 可能已降级：第三方 agent 干扰/ASM 不可见），" +
                    "回落运行期 attach（本项目会话仅提示一次）",
            )
            runCatching { premainDegradedNotifier?.invoke() }
                .onFailure {
                    if (FatalErrors.isFatal(it)) throw it
                    LOG.warn("detectPremainDegraded: 降级提示发送失败（不影响 attach 回落）", it)
                }
        }
        return true
    }

    /** premain 降级提示出口（默认 balloon；测试注入计数器驱动断言，口径同 [attachFallbackNotifier]）。 */
    internal var premainDegradedNotifier: (() -> Unit)? = {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app == null || !app.isUnitTestMode) {
            runCatching {
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup(AGENT_NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("notification.agent.premainDegraded.title"),
                        MpSqlVisualizerBundle.message("notification.agent.premainDegraded.body"),
                        com.intellij.notification.NotificationType.WARNING,
                    ).notify(project)
            }
        }
    }

    /** premain 降级提示已发标记（**每项目会话一次**；internal 供测试复位）。 */
    internal val premainDegradedNotifiedThisSession = AtomicBoolean(false)

    /**
     * T-AgentConfigChannel T-02：CONFIG 回执超时巡检的排定入口（构造会话时注入）。
     *
     * 复用既有 [monitor]（那条 5s 在线态巡检所在线程池），**不新开常驻线程**——回执窗口是
     * 一次性延时任务，monitor 本就随会话懒启（[ensureMonitorStarted]）。项目销毁后 monitor 已
     * shutdownNow，排定抛 RejectedExecutionException：会话随之关闭、无回执可等，按既有
     * 「非致命只记一行」口径处理，不升级成错误。
     */
    private fun scheduleConfigAckCheck(delayMs: Long, task: () -> Unit) {
        runCatching { monitor.schedule(task, delayMs, TimeUnit.MILLISECONDS) }
            .onFailure {
                LOG.info("CONFIG 回执超时巡检未排定（monitor 已停：${it.javaClass.simpleName}）——会话即将随项目销毁")
            }
    }

    /**
     * 创建会话：解压 agent jar → ServerSocket 端口协商 → attach → accept（10s 超时）。
     * 返回 null = attach 后 agent 未在超时内连接（视同失败）。
     */
    private fun createSession(pid: Long): AgentSession? {
        val jar = extractAgentJar() ?: run {
            notifyAgentFailure("增强捕获：agent 解压落盘失败，已回落控制台捕获")
            return null
        }
        val socket = attachAndOffer(jar, pid) ?: return null
        // T-ReachAudit T-RA-01：会话与管理器共用同一时间源（心跳窗口判定跨「记 lastSeen」与
        // 「算过期」两侧，不同源则测试无法推进时间）
        return AgentSession(
            project, pid, socket,
            onStateChange = { updateOnline() },
            clockMs = clockMs,
            // T-PS5-04：URL 捕获即回写推断持有者（会话建立时 URL 尚不可知，事件到达才拿到）
            onUrlCaptured = { syncInferenceUrl() },
            // T-AgentConfigChannel T-02：CONFIG 回执超时巡检排定（复用 monitor）
            scheduleConfigAckCheck = ::scheduleConfigAckCheck,
            // 追加 P-5：回执到达 → 重算桥闸门（在线态不变时也要刷，故不走「仅变化才广播」的路径）
            onCaptureConfirmationChanged = ::onCaptureConfirmationChanged,
            // R53：用户暂停闸门（暂停只停 agent 通道——事件在入队点丢弃，连接/回执链不动）
            pausedProvider = { userPaused },
        )
    }

    /** agent jar 提取一次缓存（T-PS4-03）：解压 + deleteOnExit 登记只做一次；失败不缓存（下次 attach 重试）。 */
    @Volatile
    private var cachedAgentJar: File? = null

    /**
     * 解压 agent jar 到临时目录（T-PS4-03：提取一次缓存到服务字段复用，deleteOnExit 仅登记一次；
     * 原「每次 createSession 解压一个新临时文件」会随 attach 次数累积 deleteOnExit 登记与磁盘文件，
     * 直到 IDE 退出才统一回收。失败 null 由调用方显式上报——禁静默吞）。
     */
    private fun extractAgentJar(): File? {
        cachedAgentJar?.let { return it }
        val extracted = runCatching {
            val stream = javaClass.getResourceAsStream(AGENT_RESOURCE)
                ?: error("资源 $AGENT_RESOURCE 缺失（构建未内嵌 agent jar）")
            stream.use { input ->
                val out = File.createTempFile("mps-sql-agent", ".jar")
                out.deleteOnExit()
                out.outputStream().use { input.copyTo(it) }
                out
            }
        }.getOrNull()
        if (extracted != null) cachedAgentJar = extracted
        return extracted
    }

    /**
     * attach + loadAgent（端口下发）+ accept 握手。
     * JDK 错配容忍（§4.5）：`AgentLoadException("0")` / `Non-numeric value found` 视为可能
     * 仍成功，继续等 accept；finally detach。
     */
    private fun attachAndOffer(jar: File, pid: Long): Socket? {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val port = server.localPort
            server.soTimeout = 10_000
            var vm: VirtualMachine? = null
            try {
                vm = VirtualMachine.attach(pid.toString())
                try {
                    // T-StackHoverUi：随 attach 下发调用栈快照帧数预算（运行期可经 CONFIG 帧热更新）
                    vm.loadAgent(
                        jar.absolutePath,
                        AgentProtocol.agentArgs(
                            port,
                            stackMaxFrames = PluginSettings.getInstance().state.enhancedCaptureStackMaxFrames,
                        ),
                    )
                } catch (e: com.sun.tools.attach.AgentLoadException) {
                    // JDK8 二次挂载抛 AgentLoadException: 0——agent 可能已在目标 JVM 内，继续握手
                    LOG.warn("loadAgent AgentLoadException(${e.message})——按可能已挂载继续握手")
                } catch (e: IOException) {
                    if (e.message?.contains("Non-numeric value found") == true) {
                        LOG.warn("loadAgent Non-numeric value found（JDK 错配魔数）——按可能已挂载继续握手")
                    } else {
                        throw e
                    }
                }
                return try {
                    val accepted = server.accept()
                    // T-PremainReattach P4：握手成功 = agent 的 SenderLoop 认下了这个端口（之后
                    // 断线重连也拨它）——登记 pid→端口供关再开时原端口 re-bind 恢复（见 [attachPorts]）
                    attachPorts[pid] = port
                    accepted
                } catch (e: SocketTimeoutException) {
                    LOG.warn("accept 超时：agent 未在 10s 内连接（pid=$pid）——回落桥")
                    null
                }
            } finally {
                runCatching { vm?.detach() }
            }
        }
    }

    // ---------- T-M56Phase2-Premain：启动即捕获补位 ----------

    /**
     * premain 注入准备（`PremainRunConfigurationExtension.updateJavaParameters` 的唯一入口）：
     * 过三道闸门 → 产出 `-javaagent:…` VM 选项字符串，null = 本次不注入。
     *
     * 闸门顺序与副作用解耦（零劣化的关键）：开关为假时**不提取 jar、不协商端口**——
     * 这两步在未开启态纯属浪费（且端口协商会开 ServerSocket）。判定本身走纯函数
     * [PremainInjection.decide]，此处只负责按序求值事实与打日志。
     *
     * T-EnhancedCaptureDefaultOn（2026-10-19）：注入确认闸门已移除——开关打开即注入，
     * 不再有 confirmed 前置条件，也不再有弹窗与 EDT 依赖。
     */
    internal fun preparePremainVmOption(): String? {
        val enabled = isEnhancedEnabled()
        if (!enabled) {
            val reason = (PremainInjection.decide(false) as PremainInjection.Decision.Skip).reason
            LOG.info("premain 注入跳过：$reason（不提取 jar、不协商端口）")
            return null
        }
        val jar = extractPremainAgentJar()
        val port = if (jar != null) openPremainChannel() else null
        return when (val decision = PremainInjection.decide(true, jar != null, port)) {
            is PremainInjection.Decision.Inject -> {
                LOG.info("premain 注入：端口 ${decision.port} 已前置协商（agent 回连前不阻塞用户 Run）")
                // T-StackHoverUi：-javaagent 选项随带调用栈快照帧数预算（premain 场景无 attach 二次下发）
                PremainInjection.vmOption(
                    jar!!.absolutePath,
                    decision.port,
                    PluginSettings.getInstance().state.enhancedCaptureStackMaxFrames,
                )
            }
            is PremainInjection.Decision.Skip -> {
                LOG.warn("premain 注入跳过：${decision.reason}——回落 attach/桥（零劣化）")
                null
            }
        }
    }

    /**
     * 端口前置协商（T-M56P2-01）：开 loopback 端口 + 起守护 accept 线程，立即返回端口。
     * 与 attach 链的 [attachAndOffer] 同一套「`ServerSocket(0, 1, loopback)` 取端口」思路，
     * 差别只在**端口生命周期前移到进程启动之前**——agent 侧 `EventBus.start(port)` 起
     * `SenderLoop` 后按 1s→5s 退避无限重连，端口先开不丢连接。
     *
     * T-AgentReconnect T-03：端口生命周期进一步延长为**绑定到本次运行**——accept 线程循环接受
     * 回连（会话被摘除后 agent 可重连同一端口），运行结束由 [onRunEnded] 关闭。
     *
     * 返回 null 的两条路径（绑定失败 / 挂起通道超上限）都只记日志：调用方回落 attach/桥。
     */
    internal fun openPremainChannel(): Int? {
        prunePremainChannels()
        if (premainChannels.size >= MAX_PREMAIN_CHANNELS) {
            LOG.warn("openPremainChannel: 挂起通道已达上限 $MAX_PREMAIN_CHANNELS——本次不注入（回落 attach/桥）")
            return null
        }
        val server = try {
            // backlog 4：暂停期 backlog 需容纳 agent 停泊重连（drainPremainBacklog 保证暂停交接时
            // 已清空，此处为长暂停期「停泊连接死亡+重拨」等罕见多连接形态留余量；Windows 下为 hint）
            ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
            LOG.warn("openPremainChannel: 端口前置协商失败——本次不注入（回落 attach/桥）", t)
            return null
        }
        val channel = PremainChannel(server.localPort, server, clockMs())
        premainChannels.add(channel)
        registerChannelDisposable(channel)
        // T-ReleasePerf8（S5 挂死修复）：启动即取新代（0→1），后续 resume 递增取代
        val generation = ++channel.acceptGeneration
        val acceptThread = Thread({ acceptPremain(channel, generation) }, "mps-agent-premain-${channel.port}")
            .apply { isDaemon = true }
        channel.acceptThread = acceptThread
        acceptThread.start()
        LOG.info("openPremainChannel: 端口 ${channel.port} 已前置协商（挂起 ${premainChannels.size} 个）")
        return channel.port
    }

    /**
     * T-AgentReconnect T-03 泄漏收口：通道（监听 socket + accept 线程引用）注册进**项目 disposable 树**，
     * 项目 dispose 时由平台关闭（Disposer 树是既有踩坑：组件不注册会在 IDE 退出时被 ObjectTree 报
     * "Memory leak detected"）。注册失败非致命——[closePremainChannels]（运行结束/关开关/dispose）仍是收口路径。
     */
    private fun registerChannelDisposable(channel: PremainChannel) {
        runCatching { Disposer.register(project, channel) }
            .onFailure {
                LOG.warn("premain 通道 ${channel.port} 注册项目 disposable 树失败（非致命——仍由 closePremainChannels 收口）", it)
            }
    }

    /** premain 通道是否在场（[doAttachAsync] 跳过判据）：未认领且准备时刻在跳过窗内。 */
    internal fun hasPendingPremainChannel(): Boolean {
        val now = clockMs()
        return premainChannels.any { !it.claimed && now - it.preparedAtMs <= PREMAIN_SKIP_ATTACH_WINDOW_MS }
    }

    /**
     * 清理已失效通道（T-AgentReconnect T-03 判据更新）：此前「已认领」也算 stale（旧语义 =
     * accept 一次即摘除），现在认领只代表**开始服役**（等重连），不得 prune。现行判据三条：
     * 已关闭 / [isChannelExpired]（pid 消亡或超兜底 TTL）/ 首次回连未到且已过 [PREMAIN_ACCEPT_TIMEOUT_MS]。
     */
    private fun prunePremainChannels() {
        val stale = premainChannels.filter { isStaleChannel(it) }
        stale.forEach { closePremainChannel(it) }
    }

    private fun isStaleChannel(channel: PremainChannel): Boolean =
        channel.closed ||
            isChannelExpired(channel) ||
            (!channel.claimed && clockMs() - channel.preparedAtMs > PREMAIN_ACCEPT_TIMEOUT_MS)

    /**
     * 通道是否该回收（T-AgentReconnect T-03）：目标进程已消亡（运行结束的旁证，pid 取自 agent 自报
     * HELLO，即目标 JVM 自身 pid）或已超兜底 TTL。两条都是**不依赖运行结束钩子**的兜底——
     * 钩子漏掉时通道规模也不会随运行次数增长（[MAX_PREMAIN_CHANNELS] 不被撞到）。
     */
    private fun isChannelExpired(channel: PremainChannel): Boolean {
        val pid = channel.pid
        if (pid > 0L && !isProcessAlive(pid)) {
            LOG.info("premain 通道 ${channel.port}：目标进程 pid=$pid 已消亡——回收通道（本次运行已结束）")
            return true
        }
        val since = if (channel.claimedAtMs > 0L) channel.claimedAtMs else channel.preparedAtMs
        if (clockMs() - since > PREMAIN_CHANNEL_TTL_MS) {
            LOG.warn("premain 通道 ${channel.port} 已达兜底 TTL ${PREMAIN_CHANNEL_TTL_MS}ms（运行结束信号未到）——回收通道")
            return true
        }
        return false
    }

    /**
     * 目标进程是否仍存活（T-AgentReconnect T-03 运行结束的旁证）。pid 未知或查询异常一律按存活处理
     * （宁多留一拍通道，不误关正在服役的通道——误关会让该次运行永久失去重连路径）。
     */
    private fun isProcessAlive(pid: Long): Boolean = runCatching {
        java.lang.ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }.getOrDefault(true)

    /**
     * premain accept 线程体（T-AgentReconnect T-03：由「accept 一次即关通道」改为**循环 accept**）。
     *
     * 会话被摘除（心跳窗口过期/连接断开）不再等于本次运行的终局——agent 侧 `SenderLoop` 无限重连
     * 同一端口（SenderLoop.java:29-91），只要监听 socket 还在，下一次回连就能重新建会话。
     * 循环退出路径：首次回连迟迟不到（[PREMAIN_ACCEPT_TIMEOUT_MS]，该进程回落 attach/桥）、
     * 运行结束（[onRunEnded] / pid 消亡 / 兜底 TTL）、回连次数达 [MAX_PREMAIN_RECONNECTS]（防热循环）、
     * **代际失主让位**（T-ReleasePerf8 S5 挂死修复：[resumePremainChannel] 递增 [PremainChannel.acceptGeneration]
     * 后旧循环即失主——pause→resume 竞态下 `paused` 先被翻回 false，旧循环若只依赖 paused 会失去
     * 全部退出条件而无限 tick，S5 发行门禁轮实测挂 75min）。
     *
     * 退出时按代际分流收口：失主让位既不关通道也不清 backlog（监听与通道都归新代所有）；
     * 其余路径照旧走 [closePremainChannel]（关 socket + 从通道表摘除 + 释放 disposable 条目）
     * 或暂停交接（[drainPremainBacklog]）。
     */
    internal fun acceptPremain(channel: PremainChannel, generation: Int) {
        try {
            // resume 重建的循环（已 claimed）走短超时周期拍（复核过期信号），与首次等待的 120s 长超时区分
            channel.server.soTimeout =
                (if (channel.claimed) PREMAIN_IDLE_TICK_MS else PREMAIN_ACCEPT_TIMEOUT_MS).toInt()
            while (!channel.closed && !channel.paused) {
                if (channel.acceptGeneration != generation) return
                val socket = try {
                    channel.server.accept()
                } catch (e: SocketTimeoutException) {
                    if (!channel.claimed) {
                        LOG.info(
                            "premain 通道 ${channel.port} 未在 ${PREMAIN_ACCEPT_TIMEOUT_MS}ms 内收到首次回连" +
                                "——该进程回落 attach/桥（零劣化）",
                        )
                        return
                    }
                    // 已认领：本拍无重连到达，复核运行结束信号后继续等（不再无限阻塞在 accept 上）
                    if (channel.acceptGeneration != generation) return
                    if (isChannelExpired(channel)) return
                    continue
                }
                if (channel.acceptGeneration != generation) {
                    // 代际失主：本连接归新循环，弃连让位（不让位会把停泊/哑元连接误当真回连，
                    // 且旧循环失去退出条件——S5 挂死形态的另一半）
                    runCatching { socket.close() }
                    return
                }
                if (channel.paused || channel.closed) {
                    // T-PremainReattach S3：暂停唤醒哑元连接 / 暂停竞态期到达的连接——丢弃后退出循环，
                    // **监听 socket 保留**：agent 的重连此后在内核 backlog 停泊（不被 accept 即不计入
                    // agent 侧「连上即被关」计数，PORT_ABANDONED 阈值不可能被暂停期累计到），
                    // resume 后第一个 accept 即取回停泊连接。
                    runCatching { socket.close() }
                    return
                }
                val firstConnect = !channel.claimed
                channel.claimed = true
                if (firstConnect) {
                    channel.claimedAtMs = clockMs()
                    // 首次回连到达 → 转入「等重连」阶段：短超时周期性复核（[PREMAIN_IDLE_TICK_MS]）
                    channel.server.soTimeout = PREMAIN_IDLE_TICK_MS.toInt()
                }
                val round = ++channel.acceptCount
                val pid = onPremainConnected(channel, socket)
                if (pid > 0L) channel.pid = pid
                if (round >= MAX_PREMAIN_RECONNECTS) {
                    LOG.warn(
                        "premain 通道 ${channel.port} 回连次数达上限 $MAX_PREMAIN_RECONNECTS" +
                            "——关闭通道（防异常抖动热循环；该次运行后续回连需重新 Run 注入）",
                    )
                    return
                }
            }
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传（accept 线程：透传即线程终止，平台记录现场）
            if (!channel.closed) {
                LOG.info(
                    "premain 通道 ${channel.port} accept 循环退出（${t.javaClass.simpleName}: ${t.message}）" +
                        "——该进程回落 attach/桥（零劣化）",
                )
            }
        } finally {
            // T-ReleasePerf8（S5 挂死修复）：代际失主的让位退出不收口——监听 socket 与通道都归新代
            // 循环所有（本代若顺手 closePremainChannel，resume 刚重建的通道会被自己人拆掉）。
            val superseded = channel.acceptGeneration != generation
            when {
                superseded && !channel.closed -> Unit
                // T-PremainReattach S1：暂停退出（paused 且未 closed）不收口——socket 与通道都保留，
                // 等 resume 重建 accept 循环；其余退出路径照旧收口。暂停中被关闭的竞态（onRunEnded /
                // dispose）也照旧收口（closeChannel 与本路径都幂等）。
                channel.closed || !channel.paused -> closePremainChannel(channel)
                else -> {
                    // 暂停退出先清空内核 backlog（哑元唤醒连接 / 暂停竞态期到达的连接——close 即弃；
                    // agent 侧至多累计 1~2 次「连上即被关」，远低于 PORT_ABANDONED 阈值）。 backlog 不清，
                    // 滞留的死连接会占满 backlog 槽位（cap=4）令暂停期 agent 重连被拒、resume 后第一个
                    // accept 吃到哑元产生 pid=0 闪断会话。清空后 resume 的第一个 accept 必是 agent 真连接。
                    drainPremainBacklog(channel)
                }
            }
        }
    }

    /**
     * 清空暂停通道的内核 backlog（[acceptPremain] 暂停退出路径专用）：短超时循环 accept+close 直到
     * 无 pending。仅 accept 线程自身在退出前调用（无并发竞争者）；通道此刻已被 closeChannel 竞态
     * 关闭时 accept 抛 SocketException，静默结束。100ms 窗口内 agent 真回连到达也会被弃（计入
     * agent 侧快速断开一次，可接受）。
     */
    private fun drainPremainBacklog(channel: PremainChannel) {
        runCatching {
            channel.server.soTimeout = 100
            // **时间有界**（500ms 封顶）：本清理只服务「暂停交接时清空 backlog」，绝不服务暂停期——
            // 若在暂停期常驻 accept+close，agent 的每次重连都会被弃（「连上即被关」计满即
            // PORT_ABANDONED，resume 永久失效）。超时（无 pending）/closed/paused 被翻回（resume
            // 已接手，共享 soTimeout 字段不得再纠缠）任一即退出；resume 侧 join 本线程后才起新循环。
            val deadlineMs = System.currentTimeMillis() + 500
            while (!channel.closed && channel.paused && System.currentTimeMillis() < deadlineMs) {
                val socket = try {
                    channel.server.accept()
                } catch (e: SocketTimeoutException) {
                    break
                }
                runCatching { socket.close() }
            }
        }
    }

    /**
     * premain 回连处理（T-M56P2-04 幂等）：读 HELLO 取 pid → 建会话 → 写幂等表。返回本次回连自报的
     * pid（0=HELLO 未取到/连接被丢弃），供 accept 循环登记到通道上做进程存活判定。
     *
     * 去重判据是「同 pid 是否有**活动会话**」而非查 [attachedPids]：premain 是更强通道（启动期
     * 就能织入，无 3s 静默等待），先到者优先，写表时覆盖——否则 attach 先占表会让 premain 连接
     * 被误丢，反而丢掉唯一一条可用连接。
     *
     * T-AgentReconnect T-03：该判据对**重连**天然放行——旧会话已被 [sweepOffline] 摘除（alive=false），
     * 故重连不再被判据挡下，语义与「同 pid 已有活动会话时丢弃重复连接」并存不变。
     */
    private fun onPremainConnected(channel: PremainChannel, socket: Socket): Long {
        try {
            if (!isEnhancedActive()) {
                LOG.info("premain 回连到达但增强捕获已关闭/未确认——丢弃连接")
                runCatching { socket.close() }
                return 0L
            }
            val pid = readHelloPid(socket)
            // T-PremainReattach：pid 读到即尽早登记（会话构建链里还有 CONFIG 下发/巡检懒启/广播等
            // 毫秒级步骤）——关开关分流（pauseOrClosePremainChannels）与 resume 查表都按 pid 判，
            // 登记晚于「会话已建立」会造成关开关恰落窗口内、通道被误关而非暂停的竞态。
            if (pid > 0L) channel.pid = pid
            // T-PerfSweep6 T-PS6-06：去重判据由 `alive` 收紧为 [isSessionHealthy]（socket 未断**且**
            // 心跳窗口内有过帧）。`alive` 只表示 socket 未断——半死会话（应用侧停摆、连接还在）会
            // 把同 pid 的**真重连**挡下一次，而挡下的这一次恰恰是「应用重启/卡死恢复」场景里最需要的
            // 那一次；半死会话随后仍会被 [sweepOffline] 摘除，等于白挡。
            // 不会误放行「刚建会话的立即重复连接」：新会话 lastSeen 在建会话时初始化为当前时刻
            //（见 AgentSession 构造），窗口内必判健康。
            // T-ReachAudit T-RA-01 合流补位：会话时间源须与管理器同源注入（[createSession] 同款）——
            // 缺省真实时钟时，假时钟驱动的心跳窗口判定两侧不同源（manager 算出的「已过期」被真实
            // 时间戳的 lastSeen 抵消），premain 通道建立的半死会话不会被巡检摘除，封口在此复活
            val session = AgentSession(
                project, pid, socket,
                onStateChange = { updateOnline() },
                clockMs = clockMs,
                // T-PS5-04：premain 会话同 attach 链——URL 捕获回写推断持有者
                onUrlCaptured = { syncInferenceUrl() },
                // T-AgentConfigChannel T-02：premain 会话同 attach 链——CONFIG 回执超时巡检排定
                scheduleConfigAckCheck = ::scheduleConfigAckCheck,
                // 追加 P-5：premain 会话同 attach 链——回执驱动桥闸门
                onCaptureConfirmationChanged = ::onCaptureConfirmationChanged,
                // R53：premain 会话同 attach 链——用户暂停闸门
                pausedProvider = { userPaused },
            )
            // T-PremainReattach：提交段入锁（与 shutdownAll 清表+暂停分流互斥）——门禁复核放最前：
            // 关开关与首次会话构建并发时，不得把会话登记进已清空的会话表（复活态：用户已关增强
            // 却仍在线采，关闭语义静默失效）。门禁与登记在同一临界区，清表+置暂停也在同一临界区，
            // 交错只剩「先提交后被清」与「先暂停后被门禁拦下」两种，复活态不可能。
            synchronized(sessionCommitLock) {
                if (channel.paused || channel.closed) {
                    LOG.info("premain 回连处理完成前通道已暂停/关闭——丢弃连接（关开关竞态）")
                    runCatching { socket.close() }
                    return 0L
                }
                if (pid > 0L && sessions.any { isSessionHealthy(it) && it.pid == pid }) {
                    LOG.info("premain 回连 pid=$pid 已有活动会话——丢弃重复连接（幂等）")
                    runCatching { socket.close() }
                    return pid
                }
                sessions.add(session)
                if (pid > 0L) attachedPids[pid] = clockMs()
            }
            // B1 同款：新 agent 默认 disabled=false，须按当前监听状态立即校正
            // （T-06：只保证「已下发」，生效以 CMD_CONFIG_ACK 为准）
            val active = isListenActive()
            runCatching { session.sendConfig(disabled = !active) }
                .onFailure { LOG.warn("premain: 初始 CONFIG 下发失败（pid=$pid）", it) }
            everAttached.set(true)
            // T-AgentHeartbeat T-05（独立复核补位）：与 attachToPid 对称——新会话挂上即清两类回落标记。
            // 此前只写 attachedPids 不清标记，premain 重连后状态行可能残留上一轮摘除的叙事；
            // 当前无可见后果（agentStatusText 先判 livePids），但任何人重排分支就会露出来。
            heartbeatDropDetected.set(false)
            firstHeartbeatPending.set(false)
            updateOnline()
            // T-PS5-04：premain 会话建立同 attach 链——巡检懒启 + 推断 URL 回写
            ensureMonitorStarted()
            onSessionLifecycleChanged()
            publishStatusChanged()
            LOG.info("premain 会话建立：pid=$pid（启动即捕获，attach 链已跳过）")
            return pid
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
            LOG.warn("premain 回连处理失败——丢弃连接（回落桥）", t)
            runCatching { socket.close() }
            return 0L
        }
    }

    /**
     * 读首帧 HELLO（`{"pid":<n>}`，SenderLoop.java:39）取 agent 自报 pid；非 HELLO/超时/畸形返回 0。
     *
     * 用裸 `DataInputStream` 读且只读一帧：它不做预读缓冲，剩余字节原样留给随后构造的
     * [AgentSession]（其内部另起 `DataInputStream` 走同一 socket 输入流），不会吞掉后续帧。
     */
    internal fun readHelloPid(socket: Socket): Long = runCatching {
        socket.soTimeout = HELLO_READ_TIMEOUT_MS
        val input = DataInputStream(socket.getInputStream())
        val len = input.readInt()
        if (len <= 0 || len > AgentProtocol.MAX_FRAME_LEN) return@runCatching 0L
        val cmd = input.readUnsignedByte()
        val body = ByteArray(len)
        input.readFully(body)
        if (cmd != AgentProtocol.CMD_HELLO) return@runCatching 0L
        HELLO_PID_REGEX.find(String(body, Charsets.UTF_8))
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }.getOrElse {
        LOG.info("readHelloPid: HELLO 未取到（${it.javaClass.simpleName}: ${it.message}）——按 pid 未知继续")
        0L
    }

    /** premain 专用 jar 落盘路径缓存（与 attach 的 [cachedAgentJar] 分开，语义不同见 [extractPremainAgentJar]）。 */
    @Volatile
    private var cachedPremainAgentJar: File? = null

    /**
     * premain 专用 agent jar 落盘（T-M56P2-02）。与 attach 的 [extractAgentJar] 分开的理由是
     * `-javaagent` 与 `loadAgent` 对文件的要求根本不同：
     *
     * - `loadAgent` 在调用当次读文件 → 随机临时名 + `deleteOnExit` 足够；
     * - `-javaagent` 由**目标 JVM 在启动时**读，且打不开 agent jar 时 JVM 会在初始化阶段直接终止
     *   （`Error occurred during initialization of VM`）——`deleteOnExit` 与「IDE 退出 / 子 JVM 启动」
     *   之间存在删除竞态，随机名又会让同会话重复 Run 每次新路径、旧文件堆积。
     *
     * 故落盘策略：插件临时目录（[PathManager.getPluginTempPath]，不可用回落 `java.io.tmpdir`）+
     * 按内容 CRC32 定名（同一构建产物永远同一路径，重复 Run 复用）+ 「临时文件 + ATOMIC_MOVE 覆盖改名」
     * （并发/多项目同时准备只收敛到同一份完整文件）+ **不登记 deleteOnExit**。残留单文件约 500KB
     * 落在插件临时目录，由 OS 临时目录自行回收，换「进程启动时文件必然在位」。
     */
    private fun extractPremainAgentJar(): File? {
        cachedPremainAgentJar?.let { if (it.isFile && it.length() > 0L) return it }
        val extracted = runCatching {
            val bytes = javaClass.getResourceAsStream(AGENT_RESOURCE)
                ?.use { it.readBytes() }
                ?: error("资源 $AGENT_RESOURCE 缺失（构建未内嵌 agent jar）")
            val dir = File(premainAgentDir(), "mpsv-agent")
            if (!dir.isDirectory && !dir.mkdirs()) error("premain agent 目录不可建：$dir")
            val crc = java.util.zip.CRC32().apply { update(bytes) }.value
            val target = File(dir, "mps-sql-agent-$crc.jar")
            if (target.isFile && target.length() == bytes.size.toLong()) return@runCatching target
            val tmp = File(dir, "mps-sql-agent-$crc.jar.tmp")
            tmp.outputStream().use { it.write(bytes) }
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            target
        }.getOrNull()
        if (extracted != null) cachedPremainAgentJar = extracted
        return extracted
    }

    /** premain jar 目录根：插件临时目录优先（平台 API 在 headless 测试环境可能未初始化，故带回退）。 */
    private fun premainAgentDir(): File = runCatching {
        File(com.intellij.openapi.application.PathManager.getPluginTempPath())
    }.getOrElse { File(System.getProperty("java.io.tmpdir") ?: ".") }

    // ---------- 会话/在线态 ----------

    private fun updateOnline() {
        val wasOnline = online
        // T-ReachAudit T-RA-01：在线态 = 存在**健康**会话（socket 未断 + 心跳窗口内），不再只看 socket
        online = sessions.any { isSessionHealthy(it) }
        if (online != wasOnline) {
            LOG.info("增强捕获在线态切换: $wasOnline -> $online")
        }
        // 接管/回落切换：桥暂停挂载 ↔ 恢复（§4.3；桥链路本身零改动）
        //
        // 追加 P-5（计划 §五 P-5）：判据由「在线」收窄为「在线 **且** 应用侧已确认在采集」。
        // 原判据把「agent socket 活着」当成「agent 是产出方」——修复应用侧解析（T-01）后出现新常态
        // 「agent 在线但已停采」（停采开关关着，或应用侧看门狗收不到 CONFIG 而自停）：此时桥被封着、
        // agent 又静默 = **两条通道同时零产出，而用户以为捕获是开的**（把「误采集」换成「零捕获」，
        // 且零产出没有任何可见信号）。收窄后：确认在采集才让桥让位（不引入双通道重复产出——
        // T-M56-02 闸门的本意保留），未确认/确认停采则桥照常解析兜底。
        // 「捕获关」时桥本就不产条目（Filter 的 isCapturing 守卫不挂载），故本处不必再判 capturing。
        // R53：用户暂停（userPaused）时强制不封桥——暂停只停 agent 通道（事件在入队点丢弃），
        // 控制台通道照常解析兜底，两通道不并存（agent 侧已丢，无重复条目）。
        val gateNow = online && captureConfirmedActive() && !userPaused
        val gateWas = SqlCaptureGate.paused.getAndSet(gateNow)
        // T-AttachZeroEvent P3（2026-10-09 现场复盘）：通道纪元——接管沿（false→true，桥让位、
        // agent 接管）重置**本项目**缓冲的去重索引。dedupeKey 跨通道统一（AgentEventAdapter 契约）
        // 的目的是折叠接管/回落重叠期的双通道双报；桥一旦封停，重叠窗语义即结束——接管前桥纪元的
        // 索引不得再吞 agent 纪元的首批事件（现场：接管前 60s 内桥刚捕过同端点，接管后同 SQL 的
        // agent 事件全落窗口内被当重复丢弃，面板零新条目、「attach 即生效」看似失效）。
        // 放 AgentManager 侧而非 SqlCaptureGate 对象内：闸门是进程级单例、缓冲是项目级——上升沿由
        // 本项目的会话确认引发，重建只该落本项目缓冲；闸门对象保持哑标志位（「桥链路本身零改动」）。
        // O-147 W2-补（2026-10-14 真机复验）：由「全清」改「只清陈旧键、保留新鲜键」——全清会把接管沿
        // 前几毫秒入库的控制台条目也切断（实测 6ms 窗口），随后同一条 SQL 的 agent 副本不再判重、新开
        // 一行，用户看到「一行带源码、一行无源码」的原始报障形态；保留 5s 内键后该副本按融合升级。
        if (gateNow && !gateWas) {
            sqlLogManagerIfCreated(project)?.entries?.rebuildDedupeIndexAfterHandoff()
            LOG.info("增强捕获接管沿（桥让位）：去重索引按通道纪元重建——陈旧桥纪元键清掉（不吞 agent 首批），近 5s 内键保留（同一次执行的双通道副本按融合升级）")
        }
        // O-147 W2（V180-2）：回落沿补日志——此前 true→false **完全静默**（只有在线态整跳才留痕，
        // 而 online 翻转不等于闸门翻转：ACK 清空窗口、确认态单会话变化都只动闸门），20261014 现场
        // （增强事件持续在收、列表却混入控制台无源码条目）无法从日志裁决「闸门何时/为何放开」。
        // 回落沿带闸门判据实况快照：健康会话 + 各会话 ACK 确认态，与既有「接管沿」「CONFIG 已生效」
        // 行配对后 V180-2 可离线裁决。只读内存集合，任意线程安全；回落沿本就低频。
        if (!gateNow && gateWas) {
            val healthy = sessions.filter { isSessionHealthy(it) }.joinToString { "pid=${it.pid}" }
            val ackStates = sessions.joinToString {
                "pid=${it.pid}:" + when {
                    !it.alive -> "已断"
                    it.configAckDisabled == null -> "待确认"
                    else -> "disabled=${it.configAckDisabled}"
                }
            }
            LOG.info("增强捕获闸门回落（桥恢复解析）：online=$online, 健康会话=[$healthy], 确认态=[$ackStates]")
        }
        // 回落沿（true→false）**不**重置，三条理由：
        // ① 语义不对称——上升沿吞的是 agent 纪元的「首批证据」（用户开增强跑同端点验证，吞掉即
        //    「attach 即生效」零正向证明，12:54 现场根因）；回落沿即使吞，吞的是桥回归后的首条，
        //    面板上同 key 的 agent 纪元行还在，且「同 SQL 60s 内重复折叠」在回落期（桥为唯一产出方）
        //    正是去重的本职语义；
        // ② 抖动源在回落侧——configAckDisabled 每次 CONFIG 下发即清回「待确认」（本类 captureConfirmedActive
        //    KDoc 自述），下发→回执窗口内被周期 sweep 的本方法撞上时闸门会瞬时 true→false→true；
        //    回落沿若也重置，一次设置变更连带降/升两次纪元重置，去重窗口在设置变更附近反复失效。
        //    上升沿要求 ACK 在手、是稳态，无此抖动；
        // ③ 主控候选②语义边界即「桥一旦封停，重叠窗语义结束」——回落是重叠窗的重启，不在其内。
        //    真机若出现回落侧同症状（回落首条被 agent 纪元残索引吞），补一行对称调用即可（方法本身边沿无关）。
        // T-AgentDialectLoop：回落/关闭时清空日志时间相关器，防跨运行陈旧匹配
        if (!online) LogTimeCorrelator.clear()
    }

    /**
     * 追加 P-5（计划 §五 P-5）：应用侧采集**已确认在跑**——存在存活会话且其最近一次 CONFIG 回执为
     * `disabled=false`（见 [AgentSession.configAckDisabled]）。
     *
     * 派生自会话表（不另设布尔字段），故复位点天然齐全、无需逐处维护：会话被摘除/关闭 → 不再在表内；
     * [shutdownAll] / 项目 [dispose] → 表清空；每次 CONFIG 下发 → 该会话确认态被清回「待确认」。
     * 语义上「确认」是**值**（disabled true/false）而非单纯「收到过回执」：应用侧确认停采（disabled=true）
     * 与从未确认（null）在闸门上一视同仁——都不封桥。
     *
     * **已知边界（T-AgentResiduals R4-③，事实登记，行为不变）——旧 agent 混合版本下桥永久放行**：
     * 不含回执能力的旧版 agent 永远不回 `CMD_CONFIG_ACK` → `configAckDisabled` 恒 `null` →
     * 本判据恒 `false` → [SqlCaptureGate.paused] 恒 `false` → 控制台桥**永久不封**。
     * 于是「应用侧增强通道 + IDE 侧控制台桥」**两条通道同时产出**同一条 SQL。
     * 该形态**不修**（改判据就得回到「按 socket 在线封桥」那条已证有害的老路，见
     * [updateOnline] 的 P-5 论证），靠**列表去重窗口**折叠（`entries.dedupeWindowMs`，同指纹条目
     * 在窗口内合并）——用户可见结果正确，代价是窗口内多一次解析。混合版本期间若发现重复条目，
     * 先确认两侧 agent jar 版本是否一致，再看去重窗口设置。
     */
    internal fun captureConfirmedActive(): Boolean = sessions.any { it.alive && it.configAckDisabled == false }

    /**
     * 追加 P-5：CONFIG 回执驱动 / 待确认下发的桥闸门刷新入口。
     * 生产链路由会话回执回调（[AgentSession] 构造时注入）与 [pushCaptureActive] 调用；
     * internal 为同模块测试缝（测试直连会话不经 attach 链）。
     */
    internal fun onCaptureConfirmationChanged() {
        updateOnline()
    }

    /**
     * T-SettingsTruth P2 / T-CaptureTruth T-CT-01：状态行文案（设置页增强捕获状态行展示）。
     * 本函数只取数，文案由纯函数 [agentStatusText] 产出（全分支可单测）。
     * 只读内存集合（CopyOnWriteArrayList/ConcurrentHashMap），任意线程安全。
     */
    internal fun statusSummary(): String? = agentStatusText(
        AgentStatusSnapshot(
            enabled = isEnhancedEnabled(),
            // T-ReachAudit T-RA-01：只把**健康**会话算作「已挂载」——半死会话（socket 在、心跳停）
            // 若仍显示「已挂载 pid=…」，用户会以为捕获正常，实际两条通道都已零产出
            livePids = sessions.filter { isSessionHealthy(it) }.map { it.pid },
            everAttached = everAttached.get(),
            heartbeatExpired = heartbeatDropDetected.get(),
            // T-AgentHeartbeat T-05：另一类过期原因（连接早期，首条心跳未到）。
            // 与 heartbeatExpired 可同时为真（多会话同轮过期），叙事以 heartbeatExpired 优先。
            firstHeartbeatPending = firstHeartbeatPending.get(),
            // 秒数取当前在线窗口值（测试注入短窗时文案跟着变，不硬编码 30）
            heartbeatWindowSeconds = heartbeatWindowMs / 1000,
        ),
    )

    /** T-SettingsTruth P2：是否发生过成功挂载（区分「从未挂载」与「挂载后中断回落」）。 */
    private val everAttached = java.util.concurrent.atomic.AtomicBoolean(false)

    /** T-SettingsTruth P2：状态迁移点广播（attach 成功 / sweepOffline 摘除 / shutdownAll）。 */
    private fun publishStatusChanged() {
        runCatching {
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .messageBus.syncPublisher(AGENT_STATUS_TOPIC).onStatusChanged()
        }.onFailure { LOG.warn("publishStatusChanged 广播失败（非致命）", it) }
    }

    /**
     * 在线态巡检（5s）：心跳窗口过期/连接死亡 → 会话摘除 → 桥回落。
     * internal 为同模块守护测试缝（T-PS4-03）。
     *
     * T-ReachAudit T-RA-01：摘除面由 `!alive` 扩为 `!isSessionHealthy`——半死会话（socket 在、心跳停）
     * 此前永远不会被摘除，控制台桥也就永远回不来；现按协议语义在窗口过期后摘除并回落。
     */
    internal fun sweepOffline() {
        val dead = sessions.filter { !isSessionHealthy(it) }
        if (dead.isNotEmpty()) {
            // 仍 alive 却过期（socket 在、帧停）= 两类，T-AgentHeartbeat T-05 起分开记：
            //  ①曾收到过存活帧（HEARTBEAT/EVENT）→ 应用侧曾活着且越过启动期，现在是真停摆；
            //  ②从未收到过存活帧（只有 HELLO 或干脆没帧）→ 只是首条心跳还没到，属连接早期，不是停摆。
            // 合并记成①就是现场那条「应用侧心跳中断（进程可能已卡死）」的错误叙事。
            val expiredAlive = dead.filter { it.alive }
            heartbeatDropDetected.set(expiredAlive.any { it.livenessFrameSeen })
            firstHeartbeatPending.set(expiredAlive.any { !it.livenessFrameSeen })
            dead.forEach { it.close() }
            sessions.removeAll(dead.toSet())
            // T-PS4-03：死会话顺带清幂等表——pid 对应进程已消亡，残留会让该 pid 复用/重启后
            // 无法重挂；[shutdownAll] 的全表清空语义保留不动。
            dead.forEach { attachedPids.remove(it.pid) }
            updateOnline()
            // T-PS5-04：会话摘除后推断 URL 同步（全摘完 → 清条目，推断回落数据源 URL）
            onSessionLifecycleChanged()
            // T-SettingsTruth P2：掉线摘除 → 状态行「连接中断，已回落控制台捕获」
            publishStatusChanged()
        }
        // T-PremainReattach S4：暂停通道巡检——pid 消亡 / 兜底 TTL 到期即关闭（resume 已无意义）。
        // 「未认领超时」判据天然豁免（暂停通道必已 claimed）；shutdownAll 分流后巡检因暂停通道
        // 驻留而保持运行（见 shutdownAll），进程被外部杀掉（无运行结束信号）时由本扫兜底回收，
        // 不让 resume 撞上死进程空转，也不让通道规模随「开→关」次数增长。
        premainChannels.toList()
            .filter { it.paused && isChannelExpired(it) }
            .forEach { closePremainChannel(it) }
        // T-PremainReattach P4：attach 端口登记表顺带清死 pid 条目（表规模有界；活 pid 条目保留）
        attachPorts.entries.removeIf { e -> !isProcessAlive(e.key) }
    }

    /**
     * 增强捕获当前是否生效。
     *
     * T-EnhancedCaptureDefaultOn（2026-10-19）：授权语义已收敛到总开关，本入口与
     * [isEnhancedEnabled] 等价；保留独立名字是为了让调用点继续表达「生效」而非「配置开」。
     */
    private fun isEnhancedActive(): Boolean = isEnhancedEnabled()

    /** 开关已开且项目未销毁。 */
    internal fun isEnhancedEnabled(): Boolean {
        val state = PluginSettings.getInstance().state
        return state.enhancedCaptureEnabled && !project.isDisposed
    }

    /**
     * 关闭并摘除单个通道（T-AgentReconnect T-03 单一收口出口）：关监听 socket（阻塞中的 accept
     * 立即抛 SocketException 退出）→ 打断 accept 线程 → 从通道表摘除 → 释放项目 disposable 树条目。
     * 幂等（[PremainChannel.closeChannel] 与 [Disposer.dispose] 都幂等）。
     */
    private fun closePremainChannel(channel: PremainChannel) {
        channel.closeChannel()
        channel.acceptThread?.interrupt()
        premainChannels.remove(channel)
        runCatching { Disposer.dispose(channel) }
    }

    /**
     * 关闭全部待回连/服役中的通道（运行结束 [onRunEnded] / 项目 dispose 共用；关开关 [shutdownAll]
     * 改走 [pauseOrClosePremainChannels] 分流）。
     * 快照遍历——[closePremainChannel] 会改通道表。
     */
    private fun closePremainChannels() {
        premainChannels.toList().forEach { closePremainChannel(it) }
        premainChannels.clear()
    }

    /**
     * T-PremainReattach S1：关开关时的通道分流（[shutdownAll] 专用）。判据：claimed 且 pid 已知且
     * 未过期 → [pausePremainChannel] 暂停；其余照旧 [closePremainChannel] 关闭。
     *
     * pid 未知的 claimed 通道也关闭（相比「claimed 一律暂停」收窄暂停面）：resume 查表按 pid 命中，
     * pid=0 永远无法被 resume（进程扫描拿不到 pid=0），保留监听只会驻留到兜底 TTL。
     */
    private fun pauseOrClosePremainChannels() {
        var paused = 0
        premainChannels.toList().forEach { channel ->
            if (channel.claimed && channel.pid > 0L && !isChannelExpired(channel)) {
                pausePremainChannel(channel)
                paused++
            } else {
                closePremainChannel(channel)
            }
        }
        if (paused > 0) {
            LOG.info(
                "shutdownAll: $paused 个 premain 通道转暂停待重连（监听保留；重开增强后 resume 重建会话，" +
                    "不再重走 loadAgent）",
            )
        }
    }

    /**
     * 通道转暂停待重连：置位 + 哑元自连唤醒阻塞中的 accept（accept 收到连接即复核 paused 退出循环，
     * 监听 socket 保留）。哑元自连是必须的：claimed 通道的 accept 拍长 [PREMAIN_IDLE_TICK_MS]=30s，
     * 靠超时自醒的话，这 30s 里 agent 的重连仍会被 accept 后按「增强已关闭」丢弃（agent 侧
     * 「连上即被关」计数，SenderLoop [MAX_FAST_DROPS]=10 次即放弃端口）——唤醒窗压到毫秒级，
     * 至多损失 1 次计数，远低于放弃阈值。
     */
    private fun pausePremainChannel(channel: PremainChannel) {
        channel.paused = true
        runCatching { Socket("127.0.0.1", channel.port).close() }
        channel.acceptThread?.interrupt()
        LOG.info("premain 通道 ${channel.port} 转暂停待重连（pid=${channel.pid}，监听保留）")
    }

    /**
     * T-PremainReattach S2：恢复暂停通道（重开增强的上升沿，pid 命中暂停表时由 [doAttachAsync]
     * 调用，settings-rescan 与进程启动钩子两条路径同经该入口，单点收口）。重建 accept 循环：
     * 内核 backlog 里的停泊连接被立即 accept → HELLO 已在缓冲区 → 会话秒级重建（agent 侧
     * SenderLoop 全程无感知，无需重连竞速）。幂等：已在役（未暂停）重复触发零动作；返回 false =
     * 通道已不可恢复（暂停期间被运行结束/dispose 关闭），调用方回落既有 loadAgent 路径。
     */
    private fun resumePremainChannel(channel: PremainChannel): Boolean {
        if (channel.closed || !channel.paused) return false
        channel.paused = false
        // T-ReleasePerf8（S5 挂死修复）双防线取代原「无界 join」——原注释断言「旧线程全部退出路径有界
        //（最长数秒）」被发行门禁 S5 轮实测证伪：pause→resume 竞态下 resume 先行把 paused 翻回 false，
        // 旧 accept 循环失去 paused 退出条件，30s tick 里 isChannelExpired 恒 false（pid 活着）即无限
        // continue，Test worker 在无界 join 上挂 75min（jstack 实录，AgentManagerPremainReattachTest P4）。
        //   ① 代际让位：递增 [PremainChannel.acceptGeneration] 取新代，旧循环最迟下一拍（≤30s tick /
        //      accept 返回即刻）发现失主退出；
        //   ② join 限定预算（[PREMAIN_RESUME_JOIN_BUDGET_MS]，默认盖过 30s 拍 + 5s HELLO 读 + 余量），
        //      超时即关监听强制唤醒并判通道不可恢复（返回 false，调用方回落 attach 兜底链）——无论旧线程
        //      滞留在何种非预期停等形态，resume 都有界返回。
        val generation = ++channel.acceptGeneration
        channel.acceptThread?.let { thread ->
            runCatching { thread.join(PREMAIN_RESUME_JOIN_BUDGET_MS) }
            if (thread.isAlive) {
                LOG.warn(
                    "premain 通道 ${channel.port} 旧 accept 线程 ${PREMAIN_RESUME_JOIN_BUDGET_MS}ms 未退出" +
                        "（非预期停等形态）——关闭监听强制收口，通道判不可恢复（回落 attach 兜底链）",
                )
                runCatching { channel.server.close() }
                runCatching { thread.join(2_000) }
                closePremainChannel(channel)
                return false
            }
        }
        val acceptThread = Thread({ acceptPremain(channel, generation) }, "mps-agent-premain-${channel.port}-r")
            .apply { isDaemon = true }
        channel.acceptThread = acceptThread
        acceptThread.start()
        LOG.info(
            "premain 通道 ${channel.port} 恢复：重建 accept 循环等 agent 停泊/重连（pid=${channel.pid}）" +
                "——跳过 loadAgent（agentmain 不重入，重复挂载必失败）",
        )
        return true
    }

    /** pid 命中的暂停通道（[resumePausedPremainChannelIfHit] 判据；internal 为同模块测试缝）。 */
    internal fun findPausedPremainChannel(pid: Long): PremainChannel? =
        premainChannels.firstOrNull { it.paused && !it.closed && it.pid == pid }

    /**
     * T-PremainReattach S2 的收口入口：pid 命中暂停通道即 [resumePremainChannel] 恢复并返回 true
     * （调用方跳过 loadAgent）。internal 为同模块测试缝（测试直连，绕开 attach 兜底链的真实挂载）。
     */
    internal fun resumePausedPremainChannelIfHit(pid: Long): Boolean {
        val channel = findPausedPremainChannel(pid) ?: return false
        return resumePremainChannel(channel)
    }

    /**
     * T-PremainReattach P4（D2 形态）：attach 装载进程的关再开恢复。pid 命中 [attachPorts] 时在
     * **原端口 re-bind** 起 accept 循环（SO_REUSEADDR 容忍旧连接 TIME_WAIT），接住 agent 侧
     * SenderLoop 对原端口的无限回连——重开上升沿 loadAgent 注不进去（agentmain 不重入），这是
     * 唯一恢复路径。恢复成功后按 claimed 通道入 [premainChannels]（后续关开自动走 premain 同一套
     * 暂停/恢复语义）。返回 false = 无登记或 re-bind 失败（端口被 ephemeral 复用等），失效登记
     * 条目随之清除，调用方回落既有 loadAgent 路径（零劣化）。
     */
    internal fun resumeAttachPortIfHit(pid: Long): Boolean {
        val port = attachPorts.remove(pid) ?: return false
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            }
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
            LOG.warn("attach 恢复：端口 $port re-bind 失败（pid=$pid，端口可能已被占用）——清除登记回落 attach/桥", t)
            return false
        }
        val channel = PremainChannel(port, server, clockMs())
        channel.claimed = true
        channel.claimedAtMs = clockMs()
        channel.pid = pid
        premainChannels.add(channel)
        registerChannelDisposable(channel)
        // T-ReleasePerf8（S5 挂死修复）：re-bind 同款取新代
        val generation = ++channel.acceptGeneration
        val acceptThread = Thread({ acceptPremain(channel, generation) }, "mps-agent-premain-$port-ra")
            .apply { isDaemon = true }
        channel.acceptThread = acceptThread
        acceptThread.start()
        LOG.info("attach 恢复：端口 $port re-bind 完成（pid=$pid）——等 agent 回连重建会话，跳过 loadAgent")
        return true
    }

    /**
     * 本次运行结束（T-AgentReconnect T-03）：关闭该轮 premain 通道。
     *
     * 通道生命周期语义 = **绑定到本次运行**（启动前协商端口 → 进程存活期间持续等回连 → 运行结束关闭），
     * 不再「accept 一次即关」。信号落在项目停止语义的既有承载点 [SqlLogManager.onConsoleRemoved]
     * （见 [RunConfigurationProcessListener] 类注释：`processTerminated` 不处理、停止语义归 onConsoleRemoved）；
     * 关开关走 [shutdownAll]、项目关闭走 [dispose]，三条路径都经 [closePremainChannels] 收口。
     *
     * 兜底另有两重（钩子漏掉时不至于让通道随运行次数累积撞 [MAX_PREMAIN_CHANNELS]）：pid 消亡判定
     * 与 [PREMAIN_CHANNEL_TTL_MS]（见 [isChannelExpired]）。
     */
    internal fun onRunEnded() {
        // T-FirstUsePremainConsent S2 频次收敛：attach 兜底通知改为「每项目会话至多一次」——
        // 运行结束**不再**复位该标记（复位会让每次 Run / 重启项目都重弹同一条能力边界告知）。
        if (premainChannels.isEmpty()) return
        val closed = premainChannels.size
        closePremainChannels()
        LOG.info("onRunEnded: 本次运行结束——关闭 $closed 个 premain 通道（该轮不再等重连）")
    }

    /** 关闭开关：CONFIG 下发停采集（禁用不卸载语义）→ 会话关闭 → 桥回落。 */
    fun shutdownAll() {
        // R53（V80③）：关开关即复位用户暂停——暂停只对「增强开着」有意义，总开关关后不留悬空态
        userPaused = false
        sessions.forEach { it.sendConfig(disabled = true) }
        // T-PremainReattach：清表 + 暂停分流与 premain 会话提交段互斥（[sessionCommitLock]），
        // 堵「清表后新会话才 add 进表」的复活态竞态。sendConfig 在锁外（socket 写，逐会话幂等）。
        synchronized(sessionCommitLock) {
            sessions.forEach { it.close() }
            sessions.clear()
            attachedPids.clear()
            // T-PS4-03：关闭开关同时清排队去抖表——关后不应残留排队记忆（3s 窗内的重复启动照常重判）
            pendingPids.clear()
            // T-M56Phase2-Premain：待回连通道一并关掉（关后即便 agent 回连也会被 isEnhancedActive 挡下，
            // 此处提前关是防 socket 挂到超时才回收）。
            // T-PremainReattach S1 改为分流：claimed 且目标进程仍活的通道转「暂停待重连」（监听保留，
            // 重开增强后 resume 重建会话——不再重走注定失败的 loadAgent：agentmain 不重入、新端口送不进
            // 去，真机 08:10 时间线实证）；未认领 / pid 未知 / 已过期（进程消亡 / TTL）照旧关闭。
            pauseOrClosePremainChannels()
        }
        // T-PerfSweep6 T-PS6-08（发现 8）：巡检一并复位——关开关后 5s 空转线程不再驻留到项目 dispose
        // （取消任务而非关执行器，见 stopMonitor 注释；再开启时 ensureMonitorStarted 照常重排）。
        // T-PremainReattach S4 例外：有暂停通道驻留时巡检保持运行（5s 扫 pid 消亡 / 兜底 TTL，
        // 到期关闭；进程被外部杀掉无运行结束信号时由本扫兜底），resume 重建会话后照常维持。
        if (premainChannels.any { it.paused }) ensureMonitorStarted() else stopMonitor()
        updateOnline()
        // T-PS5-04：会话全清 → 推断 URL 清条目（方言推断回落数据源 URL）
        onSessionLifecycleChanged()
        // T-SettingsTruth P2：回落链完成 → 状态行「连接中断，已回落控制台捕获」
        publishStatusChanged()
    }

    /** 脱敏开关下发（设置变更）。disabled 跟随当前监听状态（B2：不写死 false——监听关闭时改设置不得重置为采集）。 */
    fun pushDesensitize(desensitize: Boolean) {
        val active = isListenActive()
        sessions.forEach { it.sendConfig(disabled = !active, desensitize = desensitize) }
    }

    // T-PS4-14③：pushFrameSkipPrefixes 死代码已删除（全仓库零调用）——帧过滤前缀的真实下发链为
    // SETTINGS_CHANGE_TOPIC 监听（本类 init）→ executor → pushDesensitize/sendConfig（sendConfig 读设置现拼 skipPrefixes）。

    /**
     * 监听开关联动（T-AgentListenSync）：停止监听 → 向各会话**下发**停采指令；开启监听 → 下发恢复采集。
     * 由 SqlLogManager.startCapture/stopCapture 调用。
     *
     * **文案订正（T-AgentConfigChannel T-06）**：此前 KDoc 写「真停，非 IDE 侧丢弃」「agent 侧 CONFIG
     * 即时生效（volatile 标志）」，把「指令写出」说成了「应用侧已停采」。事实是——本轮现场（开关
     * 未开却被捕获）里应用侧**从未收到过指令**（入站帧解析与出站格式错位，CONFIG 被静默丢弃），
     * 而 IDE 侧日志照样写「已下发 disabled=true（sessions=N）」，排障时据此误判成「已停采」。
     * 故本方法只保证**指令已写出到 socket**；是否生效以 [AgentProtocol.CMD_CONFIG_ACK] 为准
     * （收到即 INFO 记一行含 pid 与三值，窗口内未收到即 WARN「CONFIG 未获确认」）。
     */
    fun pushCaptureActive(active: Boolean) {
        if (!isEnhancedActive()) return
        val desensitize = PluginSettings.getInstance().state.enhancedCaptureDesensitize
        // 追加 P-5：每次下发（本方法 + pushDesensitize）在 AgentSession.sendConfig 内已把该会话确认态
        // 清回「待确认」并触发桥闸门重算（见那里的 KDoc）——「下发成功但应用侧没生效」不会让桥一直封着。
        sessions.forEach { it.sendConfig(disabled = !active, desensitize = desensitize) }
        LOG.info(
            "pushCaptureActive: active=$active → 已下发 CONFIG disabled=${!active}（sessions=${sessions.size}；" +
                "生效与否待 CMD_CONFIG_ACK，超时 ${AgentManager.CONFIG_ACK_TIMEOUT_MS}ms 内无回执将 WARN）",
        )
    }

    /** 当前 SQL 监听是否开启（SqlLogManager.capturing；服务缺失/异常按 false——宁停采勿白采）。 */
    internal fun isListenActive(): Boolean =
        // T-ST-02b2：读路径经 sqlLogManagerIfCreated（永不发起创建 → 不会毒化服务容器；未创建＝未在采）
        sqlLogManagerIfCreated(project)?.capturing ?: false

    // ---------- pid 解析（复用桥反射骨架；getNativePid 能力探测缺失回退 getProcess().pid()） ----------

    internal fun resolvePid(processHandler: Any?): Long? = runCatching {
        if (processHandler == null) return null
        // 1) KillableProcessHandler.getNativePid()（262+ 平台；243 实测无此方法）
        runCatching {
            val m = processHandler.javaClass.methods.firstOrNull { it.name == "getNativePid" && it.parameterCount == 0 }
            (m?.invoke(processHandler) as? Number)?.toLong()
        }.getOrNull()
            // 2) getProcess() 直接接口调用 pid()（Java 9+）——禁反射 invoke：ProcessImpl 是 JDK
            //    package-private 类，getMethod("pid").invoke 会抛 IllegalAccessException
            //    （测试匿名子类可访问故测试通过、真实环境失败的根因）
            ?: runCatching {
                val process = processHandler.javaClass.getMethod("getProcess").invoke(processHandler) as java.lang.Process
                process.pid().toLong()
            }.getOrNull()
            // 3) OSProcessUtil.getProcessID(Process)（243 平台稳定 API，util.jar）
            ?: runCatching {
                val process = processHandler.javaClass.getMethod("getProcess").invoke(processHandler) as java.lang.Process
                com.intellij.execution.process.OSProcessUtil.getProcessID(process).toLong()
            }.getOrNull()
            ?: run {
                val (processRepr, procErr) = try {
                    val p = processHandler.javaClass.getMethod("getProcess").invoke(processHandler)
                    (p?.javaClass?.name ?: "null") to null
                } catch (t: Throwable) {
                    if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
                    "N/A" to t.toString()
                }
                LOG.warn("resolvePid: 三步回退全失败（handler=${processHandler.javaClass.name}, process=$processRepr, procErr=$procErr）")
                null
            }
    }.getOrElse { e ->
        // T-FatalPassthrough T-FP-02b（独立复核同族抵消点）：本函数外层 runCatching 会接住上面捕获块的
        // fatal 透传——不在此处放行等于透传无效（改前：fatal 落成 pid=null，调用方只 warn
        // "resolvePid 失败——跳过挂载"）。非 fatal 仍返回 null（pid 解析失败语义逐字不变）；fatal 经
        // doAttachAsync → onProcessAppeared → 监听器（RunConfigurationProcessListener:135）再透传，
        // 交给平台记录现场。
        if (FatalErrors.isFatal(e)) throw e
        null
    }

    /** agent 失败显式上报（禁静默吞）：桥侧继续捕获，用户无感；日志 WARN 留排查锚点。 */
    private fun notifyAgentFailure(message: String) {
        LOG.warn(message)
    }

    override fun dispose() {
        runCatching {
            sessions.forEach { it.close() }
            sessions.clear()
            // T-PS5-04：项目销毁 → 推断 URL 条目清除（不引入跨项目残留）
            onSessionLifecycleChanged()
            // T-PS4-03：服务随 project 生命周期，去抖/幂等表一并清空（不引入跨项目残留）
            pendingPids.clear()
            attachedPids.clear()
            // T-M56Phase2-Premain：待回连通道随项目销毁一并关闭
            closePremainChannels()
            executor.shutdownNow()
            monitor.shutdownNow()
            SqlCaptureGate.paused.set(false)
        }
    }
}

