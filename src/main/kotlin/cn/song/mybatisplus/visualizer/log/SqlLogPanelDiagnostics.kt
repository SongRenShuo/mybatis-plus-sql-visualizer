package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import javax.swing.JLabel
import javax.swing.JPanel

// 扩展函数看不到接收者类的 companion 成员，显式 import 引入：
import cn.song.mybatisplus.visualizer.log.SqlLogPanel.Companion.DIAGNOSTICS_TICK_MS

// T-M53-II（2026-10-10）同法，D202 第六次登记：SqlLogPanel 的「空态文案渲染 / 零产出诊断 /
// 双通道状态栏」内聚成员，以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需
// 放宽为 internal。

    /** 写入空态文案（纯文本入口，渲染层在此做折行）。 */
    internal fun SqlLogPanel.applyEmptyText(plain: String) {
        lastEmptyText = plain
        emptyTextLinkEnabled = false
        renderEmptyText()
    }

    /**
     * 写入空态文案 + 尾部可点「增强捕获」链接（20261008 验收缺陷 #3：第三径原为纯文本不可点，
     * 与验收文档 3.22「点该引导走到增强捕获的开启流程」不符）。仅常规空态用；诊断行不加链接。
     */
    internal fun SqlLogPanel.applyEmptyTextWithEnhancedLink() {
        lastEmptyText = MpSqlVisualizerBundle.message("log.panel.empty")
        emptyTextLinkEnabled = true
        renderEmptyText()
    }

    /** 按当前列表可用宽度重排并逐视觉行写入（EDT）。 */
    internal fun SqlLogPanel.renderEmptyText() {
        val plain = lastEmptyText ?: return
        val status = list.emptyText
        status.clear()
        applyEmptyTextLines(status, plain, emptyTextWidthPx()) { s -> list.getFontMetrics(list.font).stringWidth(s) }
        if (emptyTextLinkEnabled) {
            // T-SettingsFocusFromLink：引导链接（**单一整体入口**——用户定「一个整体，点击都跳到目标位置」：
            // 不再拆页签/设置页/增强捕获三个去向的分链），点击打开主设置页并定位高亮「增强捕获」设置行
            // （空态文案里「参数实参与精确耗时…在设置页开启 →」所指）。经 appendLinkRow 单点——
            // 平台 appendSecondaryText 固定写第二行的坑见该函数 KDoc。
            appendLinkRow(
                status,
                listOf(
                    MpSqlVisualizerBundle.message("log.panel.empty.link") to
                        java.awt.event.ActionListener {
                            cn.song.mybatisplus.visualizer.config.PluginSettingsFolderConfigurable.openSqlLogSettingsFocused(
                                project,
                                MpSqlVisualizerBundle.message("settings.enhancedCapture"),
                            )
                        },
                ),
            )
        }
    }

    /** 空态文案可用宽度：列表宽度扣掉自身内边距与左右余量；尚未布局（首帧 / 隐藏卡）时给兜底值。 */
    internal fun SqlLogPanel.emptyTextWidthPx(): Int {
        val insets = list.insets
        val w = list.width - insets.left - insets.right - EMPTY_TEXT_EDGE_PADDING_PX * 2
        return if (w <= 0) EMPTY_TEXT_FALLBACK_WIDTH_PX else w
    }
    /**
     * R53 测试缝：双通道状态栏两段标签（第一段=控制台通道，第二段=增强通道）。
     */
    internal fun SqlLogPanel.statusBarLabelsForTest(): Pair<JLabel?, JLabel?> = statusConsoleLabel to statusAgentLabel

    /**
     * R53 测试缝：同步驱动状态栏刷新（等价 2s 诊断定时器的一拍）。
     */
    internal fun SqlLogPanel.syncStatusBarForTest() = syncCaptureStatusBar()

    /** R53 测试缝：状态栏宿主面板（非捕捉态应整条隐藏——不留分隔符/空白高度）。 */
    internal fun SqlLogPanel.statusBarForTest(): JPanel? = statusBarPanel

    /** 诊断行同步（EDT）：更新列表空态文案 + 双通道状态栏 + 管理定时器生命周期。
     * 取数全部为只读内存态（buffer 快照 / 桥计数 / AgentManager 状态），不触发平台调用。
     *
     * R53：定时器生命周期由「空态才跑」放宽为「捕捉期间常跑」——状态栏需要持续刷新；
     * 空态判定改 [SqlLogEntryBuffer.size]（O(1)）而非 snapshot().isEmpty()（O(N) 拷贝，
     * 长列表下 2s 常跑会白付整表拷贝）。
     */
    internal fun SqlLogPanel.syncDiagnostics() {
        syncCaptureStatusBar()
        val active = manager.capturing && manager.entries.size() == 0
        val text = if (active) {
            captureDiagnosticsText(
                CaptureDiagnostics(
                    capturing = true,
                    waitingForConsole = manager.isWaitingForConsole,
                    entryCount = 0,
                    consolesAttached = manager.consoleBridge.attachedConsoles.size,
                    linesSeen = manager.consoleBridge.consoleLinesSeen.get(),
                    enhancedStatus = runCatching {
                        AgentManager.getInstance(project).statusSummary()
                    }.getOrNull(),
                    // T-RA-03：两个「行去哪了」计数——守卫误丢行 / EP 短路后条目未落到本面板时，
                    // 它们是面板唯一可见的证据（此前生产侧零读取，故障被伪装成「控制台没输出」）。
                    replayDropped = manager.consoleBridge.replayDropCount,
                    epShortCircuited = manager.consoleBridge.epActiveShortCircuitCount.get(),
                    // T-DevAcceptFix2 ⑤：增强通道已收事件数（恒显示）——「事件到没到」的唯一判别量
                    agentEventsReceived = cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter.receivedEvents(),
                    // T-DevAcceptFix2 ⑩：屏蔽规则命中次数（应用级累计，与规则面板同源计数）——
                    // 「事件到了却 0 条」时最容易被忽略的一条吃行通道（用户自建规则，设计内但必须可见）
                    suppressed = runCatching {
                        val mgr = SqlSuppressRuleManager.getInstance()
                        mgr.rules.sumOf { it.id.let { id -> mgr.hitCount(id) } }.toLong()
                    }.getOrDefault(0L),
                    // T-CaptureCatchAll 券02：活性回落解封态——「增强已挂载却零产出」的唯一解释
                    activityFallbackActive = CaptureActivityFallback.isActive(),
                    // T-CaptureCatchAll 券03：agent 侧 attach 覆盖自检（旧版 agent 为 null，不显示）
                    attachStats = cn.song.mybatisplus.visualizer.log.agent.AgentAttachReport.latest(),
                ),
            )
        } else {
            null
        }
        if (text != null) applyEmptyText(text) else applyEmptyTextWithEnhancedLink()
        // R53：定时器捕捉期间常跑（状态栏持续刷新）；非捕捉态停表清栏
        if (manager.capturing) {
            if (diagnosticsTimer == null) {
                diagnosticsTimer = javax.swing.Timer(DIAGNOSTICS_TICK_MS) { syncDiagnostics() }
                    .apply { isRepeats = true; start() }
            }
        } else {
            diagnosticsTimer?.stop()
            diagnosticsTimer = null
        }
    }

    /**
     * R53：双通道捕获状态栏刷新（EDT，挂在诊断定时器上 2s 无锁轮询）。
     * 文案由纯函数 [captureStatusLineText] 产出（V80①：取数全为只读内存态）；
     * 非捕捉态整条面板隐藏（只清文案会留下分隔符与空白高度）。
     */
    internal fun SqlLogPanel.syncCaptureStatusBar() {
        val label = statusConsoleLabel ?: return
        val texts = if (manager.capturing) {
            runCatching {
                val am = AgentManager.getInstance(project)
                captureStatusLineText(
                    CaptureStatusLine(
                        capturing = true,
                        consolesAttached = manager.consoleBridge.attachedConsoles.size,
                        linesSeen = manager.consoleBridge.consoleLinesSeen.get(),
                        agentStatus = am.statusSummary(),
                        agentLivePids = am.healthyPids(),
                        agentPaused = am.userPaused,
                        agentEventsReceived = cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter.receivedEvents(),
                    ),
                )
            }.getOrNull()
        } else {
            null
        }
        if (texts == null) {
            label.text = ""
            statusAgentLabel?.text = ""
            setStatusBarVisible(false)
        } else {
            label.text = texts.first
            statusAgentLabel?.text = texts.second
            setStatusBarVisible(true)
        }
    }

    /** R53：状态栏显隐（BorderLayout 跳过不可见子件，须 revalidate 才重排高度）。 */
    internal fun SqlLogPanel.setStatusBarVisible(visible: Boolean) {
        val bar = statusBarPanel ?: return
        if (bar.isVisible == visible) return
        bar.isVisible = visible
        bar.parent?.revalidate()
    }
