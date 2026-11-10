package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * R53（V80① 落地）：工具窗双通道捕获状态栏（SQL 日志页签底部，捕捉期间常驻）。
 *
 * 立项动因：增强捕获「现在捕获没有 / 暂停了」状态此前只藏在空态诊断行里——列表有条目时
 * 用户完全看不到双通道状态；R53 把两条通道的实时状态摆到台面上：
 *  - 控制台通道：[SqlLogConsoleBridge.attachedConsoles]（已挂载数）+ [SqlLogConsoleBridge.consoleLinesSeen]（已扫描行）；
 *  - 增强 agent 通道：总开关关（未开启）/ 暂停中 / 已挂载 pid / 已收事件 N（复用
 *    [cn.song.mybatisplus.visualizer.log.agent.AgentManager.statusSummary] 的五态文案）。
 *
 * V80① 证伪结论：取数全为只读内存态（CopyOnWriteArrayList / ConcurrentHashMap / AtomicLong /
 * @Volatile 字段），工具窗 2s 无锁轮询安全——与既有空态诊断行（[SqlLogPanel.syncDiagnostics]）
 * 同一取数口径，不新开线程。
 *
 * 纯函数（零平台依赖）便于全分支单测；面板只负责取数与渲染（[SqlLogPanel.syncCaptureStatusBar]）。
 */
internal data class CaptureStatusLine(
    /** 是否在捕捉中（状态栏只在捕捉期间显示；非捕捉态返回 null）。 */
    val capturing: Boolean,
    /** 控制台通道：已挂载控制台数。 */
    val consolesAttached: Int,
    /** 控制台通道：已扫描行数（[SqlLogConsoleBridge.consoleLinesSeen]）。 */
    val linesSeen: Long,
    /**
     * 增强 agent 通道：[AgentManager.statusSummary] 成品文案（null = 增强总开关关）。
     * 只传成品文案而非快照，面板取数经 statusSummary 单点（与设置页/空态诊断行同源）。
     */
    val agentStatus: String?,
    /** 增强 agent 通道：健康（已挂载）会话 pid 列表（[AgentManager.healthyPids]）。 */
    val agentLivePids: List<Long>,
    /** 增强 agent 通道：用户是否已暂停（[AgentManager.userPaused]）。 */
    val agentPaused: Boolean,
    /** 增强 agent 通道：已收事件累计（[AgentEventAdapter.receivedEvents]）。 */
    val agentEventsReceived: Long,
    /**
     * T-IbatisPanwei 券 D：接口命中但不在驱动白名单的类名（有界 ≤8，只类名）。
     *
     * 为什么必须落在状态栏：空态诊断行在 `entryCount > 0` 时直接返回 null（控制台有产出就完全不
     * 显示），而「驱动没登记」这条事实恰恰在「控制台有产出、增强零产出」的现场最需要被看见。
     * 状态栏是捕捉期间常驻的那一处，不新造面板。
     */
    val unregisteredImplementations: List<String> = emptyList(),
    /**
     * T-SqlListCatchup 券D：追赶读数——buffer 未消费内容通知计数（[SqlLogEntryBuffer.pendingNotifyCount]）。
     * >0 时控制台段追加「追赶中：落后 N 条」；0 时不追加（已过 [CatchUpBadgeState] 一拍节流）。
     *
     * 口径修正（2026-11-07，券C 证伪链）：原计划的「桥通道未解析行数取大者」不存在——
     * `SqlLogConsoleBridge` 为 filter 逐行制（每行解析在控制台输出线程完成，KDoc :388-390 自证），
     * 无逐拍解析积压量，桥接缝已随券C取消，本字段是追赶读数的唯一数据源。
     */
    val catchUpBehind: Int = 0,
    /**
     * T-SqlListCatchup 券 F2a：agent 侧事件队列**丢弃累计**（agent 心跳 `dropped` 键 →
     * [cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats.dropped]）。
     *
     * 为什么必须落在状态栏：风暴期事件队列打满时 agent 侧 `offer()` 满即丢（非阻塞红线，丢弃本身
     * 是对的），但「丢了多少」此前只在 agent 进程内可见——用户视角是「已收事件 2,227 条就不再涨了」，
     * 无法区分「应用真停了」和「队列溢出在丢」。0 或旧版 agent 未上报时不挂段。
     */
    val agentDropped: Long = 0L,
    /**
     * T-PanweiInsertCapture F1：让位（agent 接管）期间**被丢弃的可解析 SQL 行**累计
     * （[SqlLogConsoleBridge.suppressedSqlLines]）。
     *
     * 立项动因＝本次真机现场：控制台里明明有一条标准形态的 `INSERT`（`{pstm-N} Executing Statement:`），
     * 面板一条都没有——因为 agent 在线、桥按设计让位，那一行被直接丢掉，**两条通道都没有任何读数**
     * 能说明「有 SQL 行被让位吃掉」。本字段让这类现场一眼可判。0 时不挂段。
     */
    val suppressedSqlLines: Long = 0L,
    /** T-PanweiInsertCapture F2：agent 出口路径组装失败累计（心跳 `assembleFailures`）。0 时不挂段。 */
    val agentAssembleFailures: Long = 0L,
    /** T-PanweiInsertCapture F2：被 NestGate 抑制的外层帧累计（心跳 `nestSuppressed`）。0 时不挂段。 */
    val agentNestSuppressed: Long = 0L,
    /**
     * T-NestGateObserve：**陈旧嵌套**累计（心跳 `nestStale`）。0 时不挂段。
     *
     * 与 [agentNestSuppressed] 的分工是深度泄漏的确诊面：后者只计「被内层挤掉的外层帧」，泄漏时
     * 被抑制的是最外层自己（恒为 0）；本字段 >0 直接说明某条线程有一条活过阈值（默认 5s）的嵌套链，
     * 即 NestGate 深度未归零——磐维 A 模式「前几条有、之后整类消失」的现场读数。
     */
    val agentNestStale: Long = 0L,
    /** T-PanweiInsertCapture F3：登记盲区累计（心跳 `unresolved`）。 */
    val agentUnresolvedBlind: Long = 0L,
    /** T-PanweiInsertCapture F3：登记盲区的 PS 实现类名（心跳 `blind`，有界 ≤8）。 */
    val agentBlindClasses: List<String> = emptyList(),
    /**
     * T-PanweiInsertCapture F6：iBatis 批处理提示——本会话**已识别到 iBatis 日志形态**且增强捕获未开启。
     *
     * 为什么必须可见：iBatis 2.3.4 的 `BaseLogProxy.EXECUTE_METHODS` 不含 `executeBatch`，
     * 批处理语句在控制台**一行日志都不产生**（框架事实，控制台侧无法补救）。此时用户看到的是
     * 「批量写语句一条都不见」，而面板此前对此零解释。
     */
    val ibatisBatchHint: Boolean = false,
)

/**
 * T-SqlListCatchup 券D：追赶徽章一拍节流状态机（纯逻辑，零平台依赖，面板每拍驱动）。
 *
 * 为什么需要它：追赶读数在节流窗（200ms）边界抖动——风暴期每拍在 0 与小正数间振荡，
 * 直接透传会让「追赶中」徽章以 2s 轮询频率闪烁。本机给防闪烁语义：
 *  - 落后数 >0 → 立即显示该数（升起不等拍）；
 *  - 落后数首次归零 → **持有上一读数再亮一拍**（=0 持续一拍后消失，计划 §三.4 口径）；
 *  - 持有拍内又升起（短暂归零又升高）→ 无缝续显新数，不闪烁。
 *
 * 持有拍显示的是上一拍的读数（最多滞后一拍刷新周期，状态栏轮询 2s / 刷新拍更快），有界陈旧。
 */
internal class CatchUpBadgeState {
    private var lastShown = 0
    private var zeroBeatSeen = false

    /** 每拍驱动（[SqlLogPanel.syncCaptureStatusBar] 的每个刷新拍），返回本拍应显示的落后数（0 = 不显示）。 */
    fun onNextBeat(behind: Int): Int {
        if (behind > 0) {
            lastShown = behind
            zeroBeatSeen = false
            return behind
        }
        if (lastShown > 0 && !zeroBeatSeen) {
            // 追平的第一拍：持有上一读数再亮一拍——短暂归零又升起在这里被吸收
            zeroBeatSeen = true
            return lastShown
        }
        lastShown = 0
        zeroBeatSeen = false
        return 0
    }

    /** 非捕捉态/清栏时复位（下次捕捉从干净态起算）。 */
    fun reset() {
        lastShown = 0
        zeroBeatSeen = false
    }
}

/**
 * 状态栏两段文案（第一段=控制台通道，第二段=增强通道）；非捕捉态返回 null（状态栏清空隐藏）。
 */
internal fun captureStatusLineText(d: CaptureStatusLine): Pair<String, String>? {
    if (!d.capturing) return null
    val consoleBase = MpSqlVisualizerBundle.message("log.statusbar.console", d.consolesAttached, d.linesSeen)
    // 券D：追赶读数挂控制台段尾（>0 才挂；已过 CatchUpBadgeState 一拍节流，无抖动闪烁）
    val consoleCatchUp = if (d.catchUpBehind > 0) {
        MpSqlVisualizerBundle.message("log.statusbar.catchup", consoleBase, d.catchUpBehind)
    } else {
        consoleBase
    }
    // T-PanweiInsertCapture F1：让位期间被丢弃的可解析 SQL 行（>0 才挂——零值不添噪）
    // 【反向验证探针锚点】注释掉下面 if 块 = 摘除让位丢弃可见化，CaptureStatusBarTest 对应用例必红。
    val console = if (d.suppressedSqlLines > 0L) {
        MpSqlVisualizerBundle.message("log.statusbar.console.suppressed", consoleCatchUp, d.suppressedSqlLines)
    } else {
        consoleCatchUp
    }
    val agent = when {
        // 总开关关：无叙事（与 agentStatusText 的 !enabled → null 同口径）
        d.agentStatus == null -> MpSqlVisualizerBundle.message("log.statusbar.agent.off")
        // 暂停中：暂停语义盖过挂载叙事（暂停中也可能已挂载——连接活着、只是不入列表）
        d.agentPaused -> MpSqlVisualizerBundle.message(
            "log.statusbar.agent.paused",
            d.agentStatus,
            d.agentEventsReceived,
        )
        // 已挂载：状态文案（已挂载 pid=…）+ 已收事件 N
        d.agentLivePids.isNotEmpty() -> MpSqlVisualizerBundle.message(
            "log.statusbar.agent.attached",
            d.agentStatus,
            d.agentEventsReceived,
        )
        // 未挂载：等待/冷却/回落等既有五态文案
        else -> MpSqlVisualizerBundle.message("log.statusbar.agent.summary", d.agentStatus)
    }
    // 券 D：未登记驱动具名事实挂尾（有界；增强未开启时无意义，不挂）
    val agentWithDrivers = if (d.agentStatus != null && d.unregisteredImplementations.isNotEmpty()) {
        MpSqlVisualizerBundle.message(
            "log.statusbar.agent.unregistered",
            agent,
            d.unregisteredImplementations.joinToString(", "),
        )
    } else {
        agent
    }
    // 券 F2a：agent 侧事件队列丢弃累计挂尾（>0 才挂——零丢弃/旧版 agent 未上报时不加噪音）
    // 【反向验证探针锚点】注释掉下面 if 块 = 摘除丢弃可见化，CaptureStatusBarTest 丢弃用例必红。
    val agentWithDropped = if (d.agentStatus != null && d.agentDropped > 0L) {
        MpSqlVisualizerBundle.message("log.statusbar.agent.dropped", agentWithDrivers, d.agentDropped)
    } else {
        agentWithDrivers
    }
    // T-PanweiInsertCapture F3：登记盲区（>0 才挂；类名有则带括号后缀）——「哪个 PS 类取不到 SQL」
    // 【反向验证探针锚点】注释掉下面 if 块 = 摘除盲区可见化，CaptureStatusBarTest 盲区用例必红。
    val agentWithBlind = if (d.agentStatus != null && d.agentUnresolvedBlind > 0L) {
        val suffix = if (d.agentBlindClasses.isEmpty()) {
            ""
        } else {
            MpSqlVisualizerBundle.message(
                "log.statusbar.agent.blind.classes",
                d.agentBlindClasses.joinToString(", "),
            )
        }
        MpSqlVisualizerBundle.message(
            "log.statusbar.agent.blind",
            agentWithDropped,
            d.agentUnresolvedBlind,
            suffix,
        )
    } else {
        agentWithDropped
    }
    // T-PanweiInsertCapture F2：两条静默路径的计数（>0 才挂——正常嵌套会产生少量门禁抑制，持续增长才异常）
    // 【反向验证探针锚点】注释掉下面 if 块 = 摘除组装失败可见化，CaptureStatusBarTest 对应用例必红。
    val agentWithAssemble = if (d.agentStatus != null && d.agentAssembleFailures > 0L) {
        MpSqlVisualizerBundle.message("log.statusbar.agent.assemble", agentWithBlind, d.agentAssembleFailures)
    } else {
        agentWithBlind
    }
    val agentWithNest = if (d.agentStatus != null && d.agentNestSuppressed > 0L) {
        MpSqlVisualizerBundle.message("log.statusbar.agent.nest", agentWithAssemble, d.agentNestSuppressed)
    } else {
        agentWithAssemble
    }
    // T-NestGateObserve：陈旧嵌套（深度泄漏的确诊读数）——只在 >0 时挂；正常嵌套链存活不足阈值，
    // 该计数恒 0，不产生噪音。
    // 【反向验证探针锚点】注释掉下面 if 块 = 摘除陈旧嵌套可见化，CaptureStatusBarTest 对应用例必红。
    val agentWithStale = if (d.agentStatus != null && d.agentNestStale > 0L) {
        MpSqlVisualizerBundle.message("log.statusbar.agent.nestStale", agentWithNest, d.agentNestStale)
    } else {
        agentWithNest
    }
    // T-PanweiInsertCapture F6：iBatis 批处理无控制台日志（增强未开启时才提示——已开启时由 agent 兜住）
    val finalAgent = if (d.ibatisBatchHint && d.agentStatus == null) {
        MpSqlVisualizerBundle.message("log.statusbar.agent.ibatisBatch", agentWithStale)
    } else {
        agentWithStale
    }
    return console to finalAgent
}
