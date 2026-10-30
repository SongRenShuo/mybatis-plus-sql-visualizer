package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * T-CaptureTruth T-CT-03：捕获链路零产出诊断（SQL 日志面板空态第二行）。
 *
 * 立项动因：捕获零产出时面板只给一个空列表，用户无从判断卡在哪一环——本次现场（沙盒里
 * 「增强已开启但未授权 → 24h 冷却 → 不挂载」+「应用控制台本就没有 SQL 日志」两条通道同时归零）
 * 就是被这条沉默拖了整整一轮排障。诊断行把两条通道的状态摆到台面上：
 *  - 增强通道：复用 [cn.song.mybatisplus.visualizer.log.agent.AgentManager.statusSummary] 的五态文案
 *    （未授权/冷却中/等待进程/已挂载/已回落），未开启时显示「未启用」；
 *  - 控制台通道：已挂载控制台数 + 已扫描行数（[SqlLogConsoleBridge.consoleLinesSeen]）；
 *  - 提示语按「等待控制台 / 控制台无输出 / 有输出但没匹配到 SQL」三档给出可执行的下一步。
 *
 * T-RA-03：控制台通道再加两个「行去哪了」的计数——[SqlLogConsoleBridge.replayDropCount]
 * （重放守卫丢弃）与 [SqlLogConsoleBridge.epActiveShortCircuitCount]（EP 短路）。二者原本只在
 * 测试里读，生产侧零出口：守卫误把正常流式行判成重放、或 EP 短路后条目未落到本面板时，
 * 面板照旧显示「已扫描 N 行 / 产出 0 条」，把「有行但被吃掉」伪装成「压根没输出」。
 * 只在计数 > 0 时显示——常态零值不应给正常场景添噪。
 *
 * 纯函数（零平台依赖）便于全分支单测；面板只负责取数（[SqlLogPanel.syncDiagnostics]）。
 */
internal data class CaptureDiagnostics(
    val capturing: Boolean,
    val waitingForConsole: Boolean,
    val entryCount: Int,
    val consolesAttached: Int,
    val linesSeen: Long,
    /** 增强通道状态文案（null = 增强未开启）。 */
    val enhancedStatus: String?,
    /** 重放守卫丢弃行数（T-RA-03；0 = 无丢弃，不显示）。 */
    val replayDropped: Long,
    /** EP 通道短路行数（T-RA-03；0 = 无短路，不显示）。 */
    val epShortCircuited: Long,
    /**
     * T-DevAcceptFix2 ⑤（20261006 第二轮验收）：**增强通道已收事件数**（agent 事件到达并适配的累计）。
     *
     * 与上面两个计数不同，本字段**恒显示**（不设「非零才显示」）——它是「事件到没到」的唯一判别量：
     * 用户现场「增强捕获已挂载但产出 0 条」时，N=0 说明事件压根没到（要查 agent 侧织入/门禁），
     * N>0 说明到了但被去重/屏蔽/展示链吃掉（要查消费侧）。口径 = 收到即计数（含后续被丢弃的）。
     */
    val agentEventsReceived: Long = 0L,
    /**
     * T-DevAcceptFix2 ⑩（20261006 第三轮走查）：**屏蔽规则命中次数**（应用级累计，规则面板同源计数）。
     *
     * 立项动因＝用户现场「增强捕获已挂载、已收事件 8 条、列表 0 条」却无任何提示——事后查明是用户
     * 自建的**结构匹配**屏蔽规则按设计拦下了那些 SQL（入队前丢弃，设计内行为），可面板一个字没说，
     * 只能靠翻配置文件才判得出来。与重放/EP 两个计数同口径：**> 0 才显示**（零值不添噪）。
     */
    val suppressed: Long = 0L,
    /**
     * T-CaptureCatchAll 券02：控制台桥**活性回落已解封**——agent 在线但长期零事件、控制台仍有
     * SQL 行，桥已恢复解析。必须恒显示（不设「非零才显示」）：这是「增强已挂载却零产出」的
     * 唯一解释，藏起来就退回静默。
     */
    val activityFallbackActive: Boolean = false,
    /**
     * T-CaptureCatchAll 券03：agent 侧 attach 覆盖自检计数（null = 旧版 agent 未上报）。
     * 零命中/零织入/有失败/接口兜底命中这几类事实按「异常才显示」进首行（见 [attachCoverageNotes]）。
     */
    val attachStats: cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats? = null,
)

/**
 * T-CaptureCatchAll 券03：attach 覆盖事实 → 诊断片段（纯函数，零平台依赖）。
 *
 * 只报**用户需要据此行动**的形态：
 *  - `matched == 0`：本次 attach 一个驱动都没命中 → 增强通道本会话不可能有事件，白名单/加载时机问题；
 *  - `matched > 0 && woven == 0`：命中了却没织上 → 织入器/可见性闸门（不是「没装 agent」）；
 *  - `failed > 0`：JVM 拒绝 retransform（版本/VerifyError）——数量足够说明形态漂移；
 *  - `matchedByInterface > 0`：接口兜底生效的**正向事实**（旧版 agent 恒缺省 0，不显示）。
 */
internal fun attachCoverageNotes(stats: cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats?): List<String> {
    if (stats == null) return emptyList()
    return buildList {
        if (stats.zeroMatched) {
            add(MpSqlVisualizerBundle.message("log.diag.attachZero"))
        } else if (!stats.anyWoven) {
            // 命中数取 matched/weavable 较大值（心跳只带 weavable 时不得报「命中 0」）
            add(MpSqlVisualizerBundle.message("log.diag.attachNoWeave", stats.matchedTotal, stats.weavable, stats.woven))
        }
        if (stats.failed > 0L) add(MpSqlVisualizerBundle.message("log.diag.attachFailed", stats.failed))
        // C1 路新增：major 版本闸挡下的类数——「命中了却没织」的一条硬原因
        if (stats.skippedByVersion > 0L) {
            add(MpSqlVisualizerBundle.message("log.diag.attachSkippedVersion", stats.skippedByVersion))
        }
        // C1 路新增：transformer 兜底熔断（整体退回白名单）——覆盖变窄的直接证据
        if (stats.catchAllKilled) add(MpSqlVisualizerBundle.message("log.diag.attachCatchAllKilled"))
        if (stats.matchedByInterface > 0L) {
            add(MpSqlVisualizerBundle.message("log.diag.attachInterface", stats.matchedByInterface, stats.interfaceWoven))
        }
    }
}

/**
 * 诊断行文案；不需要诊断时返回 null（未捕捉 / 已有条目——空态文案由面板还原为默认）。
 */
internal fun captureDiagnosticsText(d: CaptureDiagnostics): String? {
    if (!d.capturing || d.entryCount > 0) return null
    val head = MpSqlVisualizerBundle.message("log.diag.capturing")
    val enhanced = if (d.enhancedStatus == null) {
        MpSqlVisualizerBundle.message("log.diag.enhancedOff")
    } else {
        // T-DevAcceptFix2 ⑤：增强通道状态后恒挂「已收事件 N 条」——判「事件到没到」的唯一判别量
        MpSqlVisualizerBundle.message("log.diag.enhancedWithEvents", d.enhancedStatus, d.agentEventsReceived)
    }
    // T-CaptureCatchAll 券02/03：增强通道的**事实补充**与状态同处首行（不新增语义行——空态折行
    // 守卫锁定「三行语义」形态，第四行会被面板右边界裁掉）：
    //  - 活性回落解封：桥为什么在产出；
    //  - attach 覆盖：为什么 agent 没产出。
    val enhancedParts = buildList {
        add(enhanced)
        if (d.activityFallbackActive) add(MpSqlVisualizerBundle.message("log.diag.activityFallback"))
        addAll(attachCoverageNotes(d.attachStats))
    }
    val console = MpSqlVisualizerBundle.message("log.diag.console", d.consolesAttached, d.linesSeen)
    // T-RA-03：丢弃/短路计数只在非零时挂尾——零值是常态，常态不该给诊断行添噪；
    // 非零则意味着「桥看到过行却没产出」，是排障时最先要看的两条线索。
    val dropped = buildList {
        if (d.replayDropped > 0L) add(MpSqlVisualizerBundle.message("log.diag.replayDropped", d.replayDropped))
        if (d.epShortCircuited > 0L) add(MpSqlVisualizerBundle.message("log.diag.epShortCircuit", d.epShortCircuited))
        // T-DevAcceptFix2 ⑩：屏蔽规则命中——用户自建规则的拦截是设计内行为，但必须可见
        if (d.suppressed > 0L) add(MpSqlVisualizerBundle.message("log.diag.suppressed", d.suppressed))
    }
    // T-AgentDiagWrap T-04：语义行按「读的人下一步要干什么」切——
    // 第一行「在捕捉吗 + 增强通道通不通」，第二行「控制台通道通不通 + 行被谁吃了」，
    // 第三行「所以下一步该做什么」。三者混成一行时，被面板右边界裁掉的总是第三行（可执行的那句）。
    val channels = (listOf(head) + enhancedParts).joinToString(SEP)
    val consoleLine = (listOf(console) + dropped).joinToString(SEP)
    val hintKey = when {
        d.waitingForConsole -> "log.diag.hint.waiting"
        d.linesSeen <= 0L -> "log.diag.hint.noOutput"
        else -> "log.diag.hint.noSql"
    }
    return "$channels\n$consoleLine\n${MpSqlVisualizerBundle.message(hintKey)}"
}

/** 通道片段分隔符（同一语义行内的并列项）。 */
private const val SEP = "｜"

// ---------- T-AgentDiagWrap T-04：空态文案「文本 → 显示」 ----------

/** 折行宽度下限：窗口尚未布局（首帧 / 隐藏卡）时算出的可用宽度可能是 0 或负值，clamp 到这里。 */
internal const val EMPTY_TEXT_MIN_WIDTH_PX = 200

/** 列表尚未布局时的兜底宽度（不 clamp 到下限，而是给一个像样的初始排版宽度）。 */
internal const val EMPTY_TEXT_FALLBACK_WIDTH_PX = 400

/** 折行时左右各留的余量：空态文案居中，贴边会让人以为还有下文。 */
internal const val EMPTY_TEXT_EDGE_PADDING_PX = 12

/** 空态引导链接之间的分隔（T-SettingsFocusFromLink）。 */
internal const val EMPTY_LINK_SEPARATOR = "　·　"

/**
 * 空态引导链接行渲染（T-SettingsFocusFromLink，**唯一入口**）。
 *
 * 必须「`appendLine("")` 起新行 + `appendText` 追加」——平台 `StatusText.appendSecondaryText`
 * 内部固定写 **row=1**（第二行/副列语义：243 源码 `appendText(true, 1, …)`）：多行空态文案下
 * 用它追加链接会把链接叠印进第二行中段（20261010 真机实证「三个链接贴在一起」）；单链接历史
 * 实现显正常只因文案恰好两行、row 1 恰为末行。复发由源码守卫 `EmptyTextLinkApiGuardTest` 拦截。
 */
internal fun appendLinkRow(
    status: com.intellij.util.ui.StatusText,
    links: List<Pair<String, java.awt.event.ActionListener>>,
) {
    status.appendLine("")
    links.forEachIndexed { index, (text, listener) ->
        if (index > 0) status.appendText(EMPTY_LINK_SEPARATOR)
        status.appendText(text, com.intellij.ui.SimpleTextAttributes.LINK_ATTRIBUTES, listener)
    }
}

/**
 * 空态文案折行（纯函数，零平台依赖）：把「语义行（`\n` 分隔）」按像素宽度软折成「视觉行」列表。
 *
 * 为什么折行必须在写入前自己算：面板空态用的是 `JBList` 的 `StatusText`（内部是
 * `SimpleColoredComponent`），它**既不解析 HTML、也不把 `\n` 当换行**——
 * `\n` 被当普通字符画成零宽，两段文字被无声拼接成一行，超宽只裁不折
 * （本次现场读数里被裁掉的恰是「控制台有输出但未见 MyBatis SQL 日志……请确认 mapper 包日志级别为 debug」
 * 这句唯一可执行的提示）。平台侧唯一的多行手段是 `StatusText.appendLine`（每行一个 Fragment，
 * `paintColumnInBounds` 逐 Fragment 单独绘制），但它同样不折行——所以折行只能自己算，
 * 再把视觉行逐条 `appendLine`。
 *
 * @param widthPx 可用像素宽度；小于 [EMPTY_TEXT_MIN_WIDTH_PX] 时按该下限排版（防 0 宽死循环/整段压平）
 * @param measure 单串像素宽度测量（渲染层传 `FontMetrics::stringWidth`；测试传可预测桩，保持纯函数）
 */
internal fun wrapEmptyTextLines(text: String, widthPx: Int, measure: (String) -> Int): List<String> {
    val width = if (widthPx < EMPTY_TEXT_MIN_WIDTH_PX) EMPTY_TEXT_MIN_WIDTH_PX else widthPx
    return text.split('\n').flatMap { wrapSingleLine(it, width, measure) }
}

/** 单条语义行的贪心折行：优先在空白处断（英文 bundle），无空白则按字符断（中文）。 */
private fun wrapSingleLine(line: String, widthPx: Int, measure: (String) -> Int): List<String> {
    if (line.isEmpty()) return listOf(line)
    val out = ArrayList<String>()
    var start = 0
    while (start < line.length) {
        var width = 0
        var i = start
        var lastSpace = -1
        while (i < line.length) {
            val cw = measure(line[i].toString())
            // i > start：首个字符即便自身超宽也必须吃掉，否则死循环
            if (width + cw > widthPx && i > start) break
            if (line[i] == ' ') lastSpace = i
            width += cw
            i++
        }
        if (i >= line.length) {
            out.add(line.substring(start).trimEnd())
            break
        }
        val cut = if (lastSpace > start) lastSpace else i
        out.add(line.substring(start, cut).trimEnd())
        start = cut
        while (start < line.length && line[start] == ' ') start++
    }
    return if (out.isEmpty()) listOf(line) else out
}
