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
    val console = if (d.catchUpBehind > 0) {
        MpSqlVisualizerBundle.message("log.statusbar.catchup", consoleBase, d.catchUpBehind)
    } else {
        consoleBase
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
    return console to agentWithDropped
}
