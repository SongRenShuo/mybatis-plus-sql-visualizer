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
)

/**
 * 状态栏两段文案（第一段=控制台通道，第二段=增强通道）；非捕捉态返回 null（状态栏清空隐藏）。
 */
internal fun captureStatusLineText(d: CaptureStatusLine): Pair<String, String>? {
    if (!d.capturing) return null
    val console = MpSqlVisualizerBundle.message("log.statusbar.console", d.consolesAttached, d.linesSeen)
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
    return console to agentWithDrivers
}
