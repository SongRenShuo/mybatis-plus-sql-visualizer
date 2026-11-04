package cn.song.mybatisplus.visualizer.diagnostic

import cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * NEW-18：一键环境体检报告（纯数据 + 纯函数，零平台依赖）。
 *
 * 用户视角的痛点：出现「预览正常但一条 SQL 都抓不到 / 执行按钮点了才报错 / 方言不是我以为的那个」
 * 时，只能逐页翻设置、逐条猜。本报告把环境事实摆成一张可导出的体检单，非绿灯项逐项回答三个问题：
 * **会导致什么（症状）→ 为什么（原因）→ 下一步点什么（动作）**，导出走 `LightVirtualFile` 在编辑器
 * 打开（不落盘、不弹模态框），用户直接复制贴工单。
 *
 * 纪律（与采集侧 [EnvironmentCheckCollector] 的分工）：
 *  - 本文件**只做判定与排版**：输入全是普通值（计数、文案、null），可在非沙盒 JVM 里全分支单测；
 *  - 取数全部只读：不执行任何 SQL、不发起数据源连接、不创建未创建过的服务；
 *  - 「不可用」必须如实显示为 [CheckLevel.UNAVAILABLE]，绝不把缺失读数编成 0 或绿灯——
 *    这正是 NEW-18 立项要防的「看着支持、一条不出」。
 *  - UI-005：每一项都带**采集耗时**与**结果来源**（[CheckOutcome]）。超时/失败的项降级为「未采集」，
 *    整单照常产出——体检单本身就是诊断工具，它自己卡死是最不可接受的形态。
 *  - 档 2：报告的文本是**用户要贴出去的**，只允许出现规则 id、结论文案与命中次数，
 *    日志原文（用户 SQL / 连接串 / 路径）一律不进报告。
 */
internal enum class CheckLevel(val severity: Int) {
    OK(0),
    UNAVAILABLE(1),
    WARN(2),
    FAIL(3),
}

/**
 * UI-005：单项采集结果来源。
 *
 * 除了「采到了」，其余三态都是**降级**：体检单照常产出，该项标「未采集」并写清原因与耗时——
 * 用户贴工单时能直接看出是哪一项慢/失败，而不是整单不出、只看到「体检中…」。
 */
internal enum class CheckOutcome {
    /** 正常采集完成。 */
    COLLECTED,

    /** 单项预算耗尽（该项仍在跑，已被放弃）。 */
    ITEM_TIMEOUT,

    /** 整张体检单预算耗尽（含「前几项吃光预算，本项未提交」）。 */
    TOTAL_TIMEOUT,

    /** 采集抛异常（含 Error）。 */
    FAILED,
}

/**
 * 体检单项：标题 + 等级 + 事实行 + 非绿灯时的「症状 / 原因 / 动作」三段 + 采集耗时/结果来源。
 *
 * 三段分工（档 1 的实质）：
 *  - [facts]：**事实**——测到了什么（计数、决策状态）。只陈述读数，不做推断；
 *  - [symptom]：**症状**——用户侧会看到什么（「执行会报『未绑定数据源』」）。不复述 facts；
 *  - [cause]：**原因**——体检推断出的成因（「有数据源但当前模块无绑定，且无上次使用记录」）；
 *  - [guidance]：**动作**——下一步具体点什么。字段名沿用历史的 `guidance`（避免改全部构造点），
 *    语义自本版起收窄为「动作」，不再承载原因或症状。
 */
internal data class EnvironmentCheckItem(
    val title: String,
    val level: CheckLevel,
    val facts: List<String>,
    /** 动作段：下一步具体做什么。绿灯项为 null。 */
    val guidance: String? = null,
    /** 症状段：这一项不健康时用户会撞到什么。 */
    val symptom: String? = null,
    /** 原因段：推断出的成因（不是 facts 的复述）。 */
    val cause: String? = null,
    /** 该项采集耗时（ms）。纯规则构造的项为 null——不是采集来的，没有耗时可言。 */
    val elapsedMs: Long? = null,
    /** 采集结果来源；[CheckOutcome.COLLECTED] 之外的取值都表示该项已降级。 */
    val outcome: CheckOutcome = CheckOutcome.COLLECTED,
) {
    /** 是否降级（未采到事实，只报了原因）。 */
    val degraded: Boolean get() = outcome != CheckOutcome.COLLECTED
}

/**
 * 体检单。`items` 顺序即展示顺序（数据源 → 方言 → 增强捕获 → 捕获覆盖 → Community 可用性 → 近期日志诊断）。
 */
internal data class EnvironmentCheckReport(
    val generatedAt: String,
    val pluginVersion: String,
    val ideName: String,
    val projects: List<String>,
    val items: List<EnvironmentCheckItem>,
    /** UI-005：整张体检单的取数总耗时（ms）；工单里一眼看出「慢在哪一项」。 */
    val totalElapsedMs: Long = 0L,
) {
    /** 总评取最严重项（FAIL > WARN > UNAVAILABLE > OK）。 */
    val overallLevel: CheckLevel
        get() = items.maxByOrNull { it.level.severity }?.level ?: CheckLevel.OK

    /** 渲染为可贴工单的纯文本（UTF-8 友好，无 ANSI 色、无 HTML）。 */
    fun render(): String = buildString {
        appendLine(MpSqlVisualizerBundle.message("envCheck.title"))
        appendLine(MpSqlVisualizerBundle.message("envCheck.header.time", generatedAt))
        appendLine(MpSqlVisualizerBundle.message("envCheck.header.plugin", pluginVersion))
        appendLine(MpSqlVisualizerBundle.message("envCheck.header.ide", ideName))
        appendLine(
            MpSqlVisualizerBundle.message(
                "envCheck.header.projects",
                if (projects.isEmpty()) MpSqlVisualizerBundle.message("envCheck.header.projects.none")
                else projects.joinToString(", "),
            ),
        )
        appendLine(MpSqlVisualizerBundle.message("envCheck.summary", levelLabel(overallLevel)))
        appendLine(MpSqlVisualizerBundle.message("envCheck.header.elapsed", totalElapsedMs.toString()))
        items.forEachIndexed { index, item ->
            appendLine()
            appendLine(
                MpSqlVisualizerBundle.message(
                    "envCheck.item.head",
                    (index + 1).toString(),
                    item.title,
                    levelLabel(item.level),
                ),
            )
            // UI-005：耗时逐项落地——「哪一项慢」是用户贴工单时最想被回答的问题。
            item.elapsedMs?.let { elapsed ->
                appendLine(MpSqlVisualizerBundle.message("envCheck.item.elapsed", elapsed.toString()))
            }
            item.facts.forEach { fact ->
                appendLine(MpSqlVisualizerBundle.message("envCheck.item.fact", fact))
            }
            // 档 1：非绿灯项按「症状 / 原因 / 动作」讲人话。缺哪段跳过（不打印空标签），
            // 绿灯项只留 facts——没毛病就不添噪（三段一并抑制，避免绿灯项混进半截指引）。
            if (item.level != CheckLevel.OK) {
                item.symptom?.takeIf { it.isNotBlank() }?.let { symptom ->
                    appendLine(MpSqlVisualizerBundle.message("envCheck.item.symptom", symptom))
                }
                item.cause?.takeIf { it.isNotBlank() }?.let { cause ->
                    appendLine(MpSqlVisualizerBundle.message("envCheck.item.cause", cause))
                }
                item.guidance?.takeIf { it.isNotBlank() }?.let { guidance ->
                    appendLine(MpSqlVisualizerBundle.message("envCheck.item.guidance", guidance))
                }
            }
        }
        appendLine()
        appendLine(MpSqlVisualizerBundle.message("envCheck.footer"))
    }

    companion object {
        /** 等级文案（双语走 bundle；测试直接断言中文键值）。 */
        internal fun levelLabel(level: CheckLevel): String = MpSqlVisualizerBundle.message(
            when (level) {
                CheckLevel.OK -> "envCheck.level.ok"
                CheckLevel.WARN -> "envCheck.level.warn"
                CheckLevel.FAIL -> "envCheck.level.fail"
                CheckLevel.UNAVAILABLE -> "envCheck.level.unavailable"
            },
        )
    }
}

/**
 * 各项体检的判定规则（纯函数）。
 *
 * 每项回答三个问题：「事实是什么」「这不健康会导致什么」「为什么」以及「用户下一步该做什么」。
 * 绿灯项不给任何指引（避免噪音），非绿灯项必须同时给出症状/原因与动作——NEW-18 验收项：
 * 逐项绿灯或可执行的三段式说明，不允许只有红灯没有下一步。
 */
internal object EnvironmentCheckRules {

    // 各项标题键：采集侧（[EnvironmentCheckBounded] 的任务表）与降级项共用同一组键，
    // 保证「降级项标题」与「正常项标题」逐字一致（成败不该改变用户看到的项名）。
    const val TITLE_KEY_DATA_SOURCE = "envCheck.item.dataSource"
    const val TITLE_KEY_DIALECT = "envCheck.item.dialect"
    const val TITLE_KEY_AGENT = "envCheck.item.agent"
    const val TITLE_KEY_CAPTURE = "envCheck.item.capture"
    const val TITLE_KEY_COMMUNITY = "envCheck.item.community"
    const val TITLE_KEY_LOG = "envCheck.item.log"

    /**
     * UI-005：降级项（超时/失败）——如实说「没采到」，不编造读数、也不让整单失败。
     *
     * 等级固定 [CheckLevel.UNAVAILABLE]：缺失读数就该显示为不可用（与「读数为 0」严格区分），
     * 也避免把一次超时升级成 FAIL 把整单总评压成红色。
     */
    fun degraded(
        titleKey: String,
        outcome: CheckOutcome,
        elapsedMs: Long,
        budgetMs: Long,
        reason: String? = null,
    ): EnvironmentCheckItem {
        val fact = when (outcome) {
            CheckOutcome.ITEM_TIMEOUT ->
                MpSqlVisualizerBundle.message("envCheck.degraded.itemTimeout", budgetMs.toString())
            CheckOutcome.TOTAL_TIMEOUT ->
                MpSqlVisualizerBundle.message("envCheck.degraded.totalTimeout", budgetMs.toString())
            CheckOutcome.FAILED ->
                MpSqlVisualizerBundle.message("envCheck.degraded.failed", reason ?: "?")
            CheckOutcome.COLLECTED -> ""
        }
        return EnvironmentCheckItem(
            title = MpSqlVisualizerBundle.message(titleKey),
            level = CheckLevel.UNAVAILABLE,
            facts = listOf(fact),
            symptom = MpSqlVisualizerBundle.message("envCheck.degraded.symptom"),
            cause = MpSqlVisualizerBundle.message("envCheck.degraded.cause"),
            guidance = MpSqlVisualizerBundle.message("envCheck.degraded.guidance"),
            elapsedMs = elapsedMs,
            outcome = outcome,
        )
    }

    /**
     * 数据源识别：Community 下 Database 插件缺席 → 如实「不可用」，不把 0 个源报成正常。
     *
     * 有源即绿灯（档 1 不动等级口径）：只要配置了数据源，绑定与否交给预览面板自己记，
     * 体检不把「还没绑定」升级成黄色——那会让大多数正常用户每次体检都看到告警。
     */
    fun dataSource(
        databaseAvailable: Boolean,
        count: Int,
        defaultName: String?,
        lastUsedName: String?,
    ): EnvironmentCheckItem {
        val title = MpSqlVisualizerBundle.message(TITLE_KEY_DATA_SOURCE)
        if (!databaseAvailable) {
            return EnvironmentCheckItem(
                title = title,
                level = CheckLevel.UNAVAILABLE,
                facts = listOf(MpSqlVisualizerBundle.message("envCheck.dataSource.noDatabase")),
                symptom = MpSqlVisualizerBundle.message("envCheck.dataSource.symptom"),
                cause = MpSqlVisualizerBundle.message("envCheck.dataSource.cause.noDatabase"),
                guidance = MpSqlVisualizerBundle.message("envCheck.dataSource.noDatabase.guidance"),
            )
        }
        val facts = buildList {
            add(MpSqlVisualizerBundle.message("envCheck.dataSource.count", count))
            defaultName?.takeIf { it.isNotBlank() }?.let {
                add(MpSqlVisualizerBundle.message("envCheck.dataSource.default", it))
            }
            lastUsedName?.takeIf { it.isNotBlank() }?.let {
                add(MpSqlVisualizerBundle.message("envCheck.dataSource.lastUsed", it))
            }
        }
        return if (count <= 0) {
            EnvironmentCheckItem(
                title = title,
                level = CheckLevel.WARN,
                facts = facts,
                symptom = MpSqlVisualizerBundle.message("envCheck.dataSource.symptom"),
                cause = MpSqlVisualizerBundle.message("envCheck.dataSource.cause.none"),
                guidance = MpSqlVisualizerBundle.message("envCheck.dataSource.none.guidance"),
            )
        } else {
            // 有源但既无默认绑定、也无上次使用记录——正是用户真机「预览打开弹选源 / 右上未绑定数据源」
            // 的场景。等级保持 OK（不打扰「刚配好还没执行」的用户、不翻转总评），但事实行必须带上，
            // 否则体检对最想看的这个态只说「已配置 N 个」，等于没诊断。
            val unbound = defaultName.isNullOrBlank() && lastUsedName.isNullOrBlank()
            EnvironmentCheckItem(
                title = title,
                level = CheckLevel.OK,
                facts = if (unbound) {
                    facts + MpSqlVisualizerBundle.message("envCheck.dataSource.unboundHint")
                } else {
                    facts
                },
            )
        }
    }

    /**
     * 方言判定：[weak] = 判定为歧义/冲突/不可用（采集侧按 `Decision.status` 换算）。
     * 弱证据不等于错误——手动指定与已解析是绿灯，其余给「会导致什么 + 为什么会这样 + 怎么变强」。
     *
     * [status] 用来把「歧义 / 冲突 / 证据不可用」讲成不同原因：三者的处置动作完全不同
     * （歧义要绑定或手动指定，冲突要二选一，不可用要先造出证据）。
     */
    fun dialect(
        visible: String?,
        details: String?,
        weak: Boolean,
        status: DialectDecisionEngine.Status? = null,
    ): EnvironmentCheckItem {
        val title = MpSqlVisualizerBundle.message(TITLE_KEY_DIALECT)
        if (visible.isNullOrBlank()) {
            return EnvironmentCheckItem(
                title = title,
                level = CheckLevel.WARN,
                facts = listOf(MpSqlVisualizerBundle.message("envCheck.dialect.unresolved")),
                symptom = MpSqlVisualizerBundle.message("envCheck.dialect.symptom"),
                cause = MpSqlVisualizerBundle.message("envCheck.dialect.cause.unresolved"),
                guidance = MpSqlVisualizerBundle.message("envCheck.dialect.guidance"),
            )
        }
        val facts = buildList {
            add(visible)
            details?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (!weak) {
            return EnvironmentCheckItem(title = title, level = CheckLevel.OK, facts = facts)
        }
        val causeKey = when (status) {
            DialectDecisionEngine.Status.AMBIGUOUS -> "envCheck.dialect.cause.ambiguous"
            DialectDecisionEngine.Status.CONFLICT -> "envCheck.dialect.cause.conflict"
            DialectDecisionEngine.Status.UNAVAILABLE -> "envCheck.dialect.cause.unavailable"
            else -> "envCheck.dialect.cause.other"
        }
        return EnvironmentCheckItem(
            title = title,
            level = CheckLevel.WARN,
            facts = facts,
            symptom = MpSqlVisualizerBundle.message("envCheck.dialect.symptom"),
            cause = MpSqlVisualizerBundle.message(causeKey),
            guidance = MpSqlVisualizerBundle.message("envCheck.dialect.guidance"),
        )
    }

    /**
     * 增强捕获（agent attach 状态 + 兜底熔断计数）。
     *
     * P-7 前提（已取证，见 [AgentAttachStats] 与 agent 侧 `SenderLoop.heartbeatBody`）：premain 与
     * agentmain **共用同一个心跳 body 构造器**，键面完全一致；差异只在时序——premain 发生在
     * 驱动类加载之前，`retransformLoadedDrivers` 当刻命中 0 属正常，接口兜底要等启动后补挂轮。
     * 因此 [premainSession] 为真时零命中降为 WARN 并写明时序原因，不误判成 FAIL。
     *
     * [stats] 为 null 表示「旧版 agent 未上报 / 尚未 attach」——显示「计数不可用」而不是 0。
     */
    fun agent(
        enabled: Boolean,
        statusText: String?,
        stats: AgentAttachStats?,
        premainSession: Boolean,
    ): EnvironmentCheckItem {
        val title = MpSqlVisualizerBundle.message(TITLE_KEY_AGENT)
        if (!enabled) {
            return EnvironmentCheckItem(
                title = title,
                level = CheckLevel.UNAVAILABLE,
                facts = listOf(MpSqlVisualizerBundle.message("envCheck.agent.off")),
                symptom = MpSqlVisualizerBundle.message("envCheck.agent.symptom.off"),
                cause = MpSqlVisualizerBundle.message("envCheck.agent.cause.off"),
                guidance = MpSqlVisualizerBundle.message("envCheck.agent.off.guidance"),
            )
        }
        val facts = ArrayList<String>(8)
        facts += statusText?.takeIf { it.isNotBlank() }
            ?: MpSqlVisualizerBundle.message("envCheck.agent.waiting")
        if (stats == null) {
            facts += MpSqlVisualizerBundle.message("envCheck.agent.statsUnavailable")
            return EnvironmentCheckItem(
                title = title,
                level = CheckLevel.WARN,
                facts = facts,
                symptom = MpSqlVisualizerBundle.message("envCheck.agent.symptom.statsUnavailable"),
                cause = MpSqlVisualizerBundle.message("envCheck.agent.cause.statsUnavailable"),
                guidance = MpSqlVisualizerBundle.message("envCheck.agent.statsUnavailable.guidance"),
            )
        }
        facts += MpSqlVisualizerBundle.message(
            "envCheck.agent.attach",
            stats.matched,
            stats.weavable,
            stats.woven,
            stats.failed,
            stats.unmodifiable,
        )
        if (stats.matchedByInterface > 0L || stats.interfaceWoven > 0L) {
            facts += MpSqlVisualizerBundle.message(
                "envCheck.agent.interface",
                stats.matchedByInterface,
                stats.interfaceWoven,
            )
        }
        if (stats.skippedByVersion > 0L) {
            facts += MpSqlVisualizerBundle.message("envCheck.agent.versionGate", stats.skippedByVersion)
        }
        facts += when {
            stats.catchAllKilled -> MpSqlVisualizerBundle.message("envCheck.agent.catchAllKilled")
            stats.catchAll == 1 -> MpSqlVisualizerBundle.message("envCheck.agent.catchAllOn")
            else -> MpSqlVisualizerBundle.message("envCheck.agent.catchAllOff")
        }
        if (stats.zeroMatched && premainSession) {
            facts += MpSqlVisualizerBundle.message("envCheck.agent.premainZero")
        }
        // 分支标识与等级判定同源：症状/原因必须跟着「实际是哪一档」走，不能拿 level 反推。
        val branch = when {
            stats.zeroMatched && !premainSession -> "zero"
            stats.zeroMatched -> "premainZero"
            !stats.anyWoven -> "notWoven"
            stats.failed > 0L -> "failed"
            stats.catchAllKilled -> "catchAllKilled"
            else -> null
        }
        val level = when (branch) {
            "zero" -> CheckLevel.FAIL
            null -> CheckLevel.OK
            else -> CheckLevel.WARN
        }
        val guidance = when (branch) {
            "zero" -> MpSqlVisualizerBundle.message("envCheck.agent.zero.guidance")
            null -> null
            else -> MpSqlVisualizerBundle.message("envCheck.agent.warn.guidance")
        }
        return EnvironmentCheckItem(
            title = title,
            level = level,
            facts = facts,
            symptom = branch?.let { MpSqlVisualizerBundle.message("envCheck.agent.symptom.$it") },
            cause = branch?.let { MpSqlVisualizerBundle.message("envCheck.agent.cause.$it") },
            guidance = guidance,
        )
    }

    /**
     * 捕获覆盖：只看「有没有产出」与「行/事件去哪了」。
     *
     * 有产出（entries > 0）即绿灯；在采但零产出 → WARN（有事件/有行却没解析成 SQL 是最常见的
     * 半通状态）；未在采 → WARN 并指向「启动应用/打开 SQL 日志」。
     */
    fun capture(
        capturing: Boolean,
        entries: Int,
        consolesAttached: Int,
        linesSeen: Long,
        agentEventsReceived: Long,
        replayDropped: Long,
        epShortCircuited: Long,
    ): EnvironmentCheckItem {
        val title = MpSqlVisualizerBundle.message(TITLE_KEY_CAPTURE)
        val facts = buildList {
            add(
                MpSqlVisualizerBundle.message(
                    "envCheck.capture.state",
                    MpSqlVisualizerBundle.message(
                        if (capturing) "envCheck.capture.state.on" else "envCheck.capture.state.off",
                    ),
                ),
            )
            add(MpSqlVisualizerBundle.message("envCheck.capture.entries", entries))
            add(MpSqlVisualizerBundle.message("envCheck.capture.console", consolesAttached, linesSeen))
            add(MpSqlVisualizerBundle.message("envCheck.capture.agentEvents", agentEventsReceived))
            if (replayDropped > 0L || epShortCircuited > 0L) {
                add(MpSqlVisualizerBundle.message("envCheck.capture.dropped", replayDropped, epShortCircuited))
            }
        }
        val level = when {
            !capturing -> CheckLevel.WARN
            entries > 0 -> CheckLevel.OK
            else -> CheckLevel.WARN
        }
        val branch = when {
            !capturing -> "off"
            entries > 0 -> null
            linesSeen > 0L || agentEventsReceived > 0L -> "noSql"
            else -> "noSource"
        }
        val guidance = when (branch) {
            "off" -> MpSqlVisualizerBundle.message("envCheck.capture.off.guidance")
            "noSql" -> MpSqlVisualizerBundle.message("envCheck.capture.noSql.guidance")
            "noSource" -> MpSqlVisualizerBundle.message("envCheck.capture.noSource.guidance")
            else -> null
        }
        return EnvironmentCheckItem(
            title = title,
            level = level,
            facts = facts,
            symptom = branch?.let { MpSqlVisualizerBundle.message("envCheck.capture.symptom.$it") },
            cause = branch?.let { MpSqlVisualizerBundle.message("envCheck.capture.cause.$it") },
            guidance = guidance,
        )
    }

    /** Community 可用性：无 Database 插件时执行入口不可用（预览/复制不受影响）。 */
    fun community(databaseAvailable: Boolean): EnvironmentCheckItem {
        val title = MpSqlVisualizerBundle.message(TITLE_KEY_COMMUNITY)
        return if (databaseAvailable) {
            EnvironmentCheckItem(
                title = title,
                level = CheckLevel.OK,
                facts = listOf(MpSqlVisualizerBundle.message("envCheck.community.available")),
            )
        } else {
            EnvironmentCheckItem(
                title = title,
                level = CheckLevel.UNAVAILABLE,
                facts = listOf(MpSqlVisualizerBundle.message("envCheck.community.unavailable")),
                symptom = MpSqlVisualizerBundle.message("envCheck.community.symptom"),
                cause = MpSqlVisualizerBundle.message("envCheck.community.cause"),
                guidance = MpSqlVisualizerBundle.message("envCheck.community.unavailable.guidance"),
            )
        }
    }

    /**
     * 档 2：近期日志诊断——把 [KnownIssueCatalog] 的命中结果讲成体检项。
     *
     * 隐私硬约束：入参 [tailText] 只用于**匹配**，本函数产出的 facts 只含「规则 id + 结论文案 +
     * 命中次数」，绝不回显日志原文（用户 SQL / 连接串 / 路径）。
     *
     * @param tailText    idea.log 尾部文本（不含被截断的首行）
     * @param windowBytes 实际参与判定的字节数（报告里换算成「尾部 N KB」）
     */
    fun logDiagnosis(tailText: String, windowBytes: Long): EnvironmentCheckItem {
        val title = MpSqlVisualizerBundle.message(TITLE_KEY_LOG)
        val kb = ((windowBytes + 1023L) / 1024L).toString()
        val hits = KnownIssueCatalog.scan(tailText).take(KnownIssueCatalog.MAX_HITS)
        if (hits.isEmpty()) {
            return EnvironmentCheckItem(
                title = title,
                level = CheckLevel.OK,
                facts = listOf(
                    MpSqlVisualizerBundle.message("envCheck.log.none", kb),
                    MpSqlVisualizerBundle.message(
                        "envCheck.log.scope",
                        kb,
                        KnownIssueCatalog.rules.size.toString(),
                    ),
                ),
            )
        }
        val facts = hits.map { hit ->
            MpSqlVisualizerBundle.message(
                "envCheck.log.hit",
                hit.id,
                MpSqlVisualizerBundle.message(hit.rule.titleKey),
                hit.count.toString(),
            )
        }
        val actions = hits.map { MpSqlVisualizerBundle.message(it.rule.actionKey) }.distinct()
        return EnvironmentCheckItem(
            title = title,
            level = hits.maxByOrNull { it.rule.severity.severity }!!.rule.severity,
            facts = facts,
            symptom = MpSqlVisualizerBundle.message("envCheck.log.symptom"),
            cause = MpSqlVisualizerBundle.message("envCheck.log.cause"),
            guidance = actions.joinToString(" "),
        )
    }

    /**
     * 档 2：日志不可读时的降级项。
     *
     * [reasonKey] 只允许 `envCheck.log.missing` / `envCheck.log.readFailed`；后者用
     * [errorType]（异常类名）填充，**不填异常 message**——message 里常带绝对路径。
     */
    fun logUnavailable(reasonKey: String, errorType: String? = null): EnvironmentCheckItem =
        EnvironmentCheckItem(
            title = MpSqlVisualizerBundle.message(TITLE_KEY_LOG),
            level = CheckLevel.UNAVAILABLE,
            facts = listOf(
                if (errorType == null) MpSqlVisualizerBundle.message(reasonKey)
                else MpSqlVisualizerBundle.message(reasonKey, errorType),
            ),
            symptom = MpSqlVisualizerBundle.message("envCheck.log.unavailable.symptom"),
            cause = MpSqlVisualizerBundle.message("envCheck.log.unavailable.cause"),
            guidance = MpSqlVisualizerBundle.message("envCheck.log.unavailable.guidance"),
        )
}
