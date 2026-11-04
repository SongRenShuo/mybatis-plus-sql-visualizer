package cn.song.mybatisplus.visualizer.diagnostic

import cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * NEW-18：一键环境体检报告（纯数据 + 纯函数，零平台依赖）。
 *
 * 用户视角的痛点：出现「预览正常但一条 SQL 都抓不到 / 执行按钮点了才报错 / 方言不是我以为的那个」
 * 时，只能逐页翻设置、逐条猜。本报告把五项环境事实摆成一张可导出的体检单，逐项给绿灯或
 * **可执行的修复指引**，导出走 `LightVirtualFile` 在编辑器打开（不落盘、不弹模态框），用户
 * 直接复制贴工单。
 *
 * 纪律（与采集侧 [EnvironmentCheckCollector] 的分工）：
 *  - 本文件**只做判定与排版**：输入全是普通值（计数、文案、null），可在非沙盒 JVM 里全分支单测；
 *  - 取数全部只读：不执行任何 SQL、不发起数据源连接、不创建未创建过的服务；
 *  - 「不可用」必须如实显示为 [CheckLevel.UNAVAILABLE]，绝不把缺失读数编成 0 或绿灯——
 *    这正是 NEW-18 立项要防的「看着支持、一条不出」。
 *  - UI-005：每一项都带**采集耗时**与**结果来源**（[CheckOutcome]）。超时/失败的项降级为「未采集」，
 *    整单照常产出——体检单本身就是诊断工具，它自己卡死是最不可接受的形态。
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

/** 体检单项：标题 + 等级 + 事实行 + 非绿灯时的修复指引 + 采集耗时/结果来源。 */
internal data class EnvironmentCheckItem(
    val title: String,
    val level: CheckLevel,
    val facts: List<String>,
    val guidance: String? = null,
    /** 该项采集耗时（ms）。纯规则构造的项为 null——不是采集来的，没有耗时可言。 */
    val elapsedMs: Long? = null,
    /** 采集结果来源；[CheckOutcome.COLLECTED] 之外的取值都表示该项已降级。 */
    val outcome: CheckOutcome = CheckOutcome.COLLECTED,
) {
    /** 是否降级（未采到事实，只报了原因）。 */
    val degraded: Boolean get() = outcome != CheckOutcome.COLLECTED
}

/**
 * 体检单。`items` 顺序即展示顺序（数据源 → 方言 → 增强捕获 → 捕获覆盖 → Community 可用性）。
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
            item.guidance?.takeIf { it.isNotBlank() }?.let { guidance ->
                appendLine(MpSqlVisualizerBundle.message("envCheck.item.guidance", guidance))
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
 * 五项体检的判定规则（纯函数）。
 *
 * 每项只回答两个问题：「事实是什么」「用户下一步该做什么」。绿灯项不给指引（避免噪音），
 * 非绿灯项必须给指引（NEW-18 验收项：逐项绿灯或修复指引，不允许只有红灯没有下一步）。
 */
internal object EnvironmentCheckRules {

    // 五项标题键：采集侧（[EnvironmentCheckBounded] 的任务表）与降级项共用同一组键，
    // 保证「降级项标题」与「正常项标题」逐字一致（成败不该改变用户看到的项名）。
    const val TITLE_KEY_DATA_SOURCE = "envCheck.item.dataSource"
    const val TITLE_KEY_DIALECT = "envCheck.item.dialect"
    const val TITLE_KEY_AGENT = "envCheck.item.agent"
    const val TITLE_KEY_CAPTURE = "envCheck.item.capture"
    const val TITLE_KEY_COMMUNITY = "envCheck.item.community"

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
            guidance = MpSqlVisualizerBundle.message("envCheck.degraded.guidance"),
            elapsedMs = elapsedMs,
            outcome = outcome,
        )
    }

    /** 数据源识别：Community 下 Database 插件缺席 → 如实「不可用」，不把 0 个源报成正常。 */
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
                guidance = MpSqlVisualizerBundle.message("envCheck.dataSource.none.guidance"),
            )
        } else {
            EnvironmentCheckItem(title = title, level = CheckLevel.OK, facts = facts)
        }
    }

    /**
     * 方言判定：[weak] = 判定为歧义/冲突/不可用（采集侧按 `Decision.status` 换算）。
     * 弱证据不等于错误——手动指定与已解析是绿灯，其余给「为什么会这样 + 怎么变强」。
     */
    fun dialect(visible: String?, details: String?, weak: Boolean): EnvironmentCheckItem {
        val title = MpSqlVisualizerBundle.message(TITLE_KEY_DIALECT)
        if (visible.isNullOrBlank()) {
            return EnvironmentCheckItem(
                title = title,
                level = CheckLevel.WARN,
                facts = listOf(MpSqlVisualizerBundle.message("envCheck.dialect.unresolved")),
                guidance = MpSqlVisualizerBundle.message("envCheck.dialect.guidance"),
            )
        }
        val facts = buildList {
            add(visible)
            details?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        return EnvironmentCheckItem(
            title = title,
            level = if (weak) CheckLevel.WARN else CheckLevel.OK,
            facts = facts,
            guidance = if (weak) MpSqlVisualizerBundle.message("envCheck.dialect.guidance") else null,
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
        val level = when {
            stats.zeroMatched && !premainSession -> CheckLevel.FAIL
            stats.zeroMatched -> CheckLevel.WARN
            !stats.anyWoven -> CheckLevel.WARN
            stats.failed > 0L -> CheckLevel.WARN
            stats.catchAllKilled -> CheckLevel.WARN
            else -> CheckLevel.OK
        }
        val guidance = when (level) {
            CheckLevel.FAIL -> MpSqlVisualizerBundle.message("envCheck.agent.zero.guidance")
            CheckLevel.WARN -> MpSqlVisualizerBundle.message("envCheck.agent.warn.guidance")
            else -> null
        }
        return EnvironmentCheckItem(title = title, level = level, facts = facts, guidance = guidance)
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
        val guidance = when {
            !capturing -> MpSqlVisualizerBundle.message("envCheck.capture.off.guidance")
            entries > 0 -> null
            linesSeen > 0L || agentEventsReceived > 0L ->
                MpSqlVisualizerBundle.message("envCheck.capture.noSql.guidance")
            else -> MpSqlVisualizerBundle.message("envCheck.capture.noSource.guidance")
        }
        return EnvironmentCheckItem(title = title, level = level, facts = facts, guidance = guidance)
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
                guidance = MpSqlVisualizerBundle.message("envCheck.community.unavailable.guidance"),
            )
        }
    }
}
