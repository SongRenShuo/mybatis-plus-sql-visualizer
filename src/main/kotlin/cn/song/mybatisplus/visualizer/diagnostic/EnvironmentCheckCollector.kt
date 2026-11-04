package cn.song.mybatisplus.visualizer.diagnostic

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
import cn.song.mybatisplus.visualizer.execute.DataSourceSettings
import cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport
import cn.song.mybatisplus.visualizer.log.SqlLogConsoleBridge
import cn.song.mybatisplus.visualizer.log.agent.AgentAttachReport
import cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter
import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import cn.song.mybatisplus.visualizer.log.sqlLogManagerIfCreated
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.ui.DialectPreviewStatusText
import cn.song.mybatisplus.visualizer.update.PluginVersion
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * NEW-18：环境体检的**只读取数**侧（平台依赖集中在此，判定与排版全在
 * [EnvironmentCheckReport]/[EnvironmentCheckRules]）。
 *
 * 三条硬约束：
 *  - **不执行任何 SQL**：只读内存计数、设置值、方言决策（方言决策内部只做 URL 探测与设置读取）；
 *  - **不创建未创建过的服务**：AgentManager / SqlLogManager 一律 `getServiceIfCreated`——体检是
 *    旁路诊断，不得为了「看一眼」而拉起增强捕获或日志管理器的常驻线程；
 *  - **有界取数**：五项采集逐项施加时间上界（见 [EnvironmentCheckBounded]）——任一项卡住只降级该项，
 *    整单照常产出。调用方仍应在池线程调 [collect]（平台 API 与文件索引都不该在 EDT 上跑），
 *    但**不需要**再自己加超时：本函数保证在总预算内返回。
 */
internal object EnvironmentCheckCollector {

    private val LOG = Logger.getInstance(EnvironmentCheckCollector::class.java)
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 收集一张体检单；[project] 为 null（无打开项目）时仍产出 IDE/Community 两项事实。 */
    fun collect(project: Project?): EnvironmentCheckReport {
        val startedNs = System.nanoTime()
        val databaseAvailable = ExecuteSqlSupport.isAvailable()
        // UI-005：顺序提交 + 足量线程池（见 EnvironmentCheckBounded 的模型说明）。
        val items = EnvironmentCheckBounded.run(
            listOf(
                EnvironmentCheckTask(EnvironmentCheckRules.TITLE_KEY_DATA_SOURCE) {
                    dataSourceItem(project, databaseAvailable)
                },
                EnvironmentCheckTask(EnvironmentCheckRules.TITLE_KEY_DIALECT) { dialectItem(project) },
                EnvironmentCheckTask(EnvironmentCheckRules.TITLE_KEY_AGENT) { agentItem(project) },
                EnvironmentCheckTask(EnvironmentCheckRules.TITLE_KEY_CAPTURE) { captureItem(project) },
                EnvironmentCheckTask(EnvironmentCheckRules.TITLE_KEY_COMMUNITY) {
                    EnvironmentCheckRules.community(databaseAvailable)
                },
            ),
        )
        return EnvironmentCheckReport(
            generatedAt = TIME_FORMATTER.format(LocalDateTime.now()),
            pluginVersion = PluginVersion.current()
                ?: MpSqlVisualizerBundle.message("envCheck.header.plugin.unknown"),
            ideName = ideName(),
            projects = openProjectNames(),
            items = items,
            totalElapsedMs = (System.nanoTime() - startedNs) / 1_000_000L,
        )
    }

    private fun ideName(): String = runCatching {
        val info = ApplicationInfo.getInstance()
        "${info.fullApplicationName} (${info.build})"
    }.getOrElse { MpSqlVisualizerBundle.message("envCheck.header.ide.unknown") }

    private fun openProjectNames(): List<String> = runCatching {
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed }.map { it.name }
    }.getOrDefault(emptyList())

    private fun dataSourceItem(project: Project?, databaseAvailable: Boolean): EnvironmentCheckItem {
        if (project == null) {
            return EnvironmentCheckRules.dataSource(databaseAvailable, 0, null, null)
        }
        val count = if (databaseAvailable) {
            runCatching { DataSourceSettings.getAllDataSources(project).size }.getOrDefault(0)
        } else {
            0
        }
        val defaultName = runCatching { DataSourceSettings.getDefaultName(project) }.getOrNull()
        val lastUsed = runCatching { DataSourceSelector.lastRunnerName(project) }.getOrNull()
        return EnvironmentCheckRules.dataSource(databaseAvailable, count, defaultName, lastUsed)
    }

    private fun dialectItem(project: Project?): EnvironmentCheckItem {
        if (project == null) return EnvironmentCheckRules.dialect(null, null, weak = true)
        val decision = runCatching { DialectRegistry.decision(project = project) }
            .onFailure { LOG.warn("[New18] dialect decision FAILED: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
            ?: return EnvironmentCheckRules.dialect(null, null, weak = true)
        val text = DialectPreviewStatusText.describe(decision)
        // 手动/已解析＝强证据；歧义/冲突/不可用＝弱证据（给修复指引，但不报错）
        val weak = decision.status != DialectDecisionEngine.Status.MANUAL &&
            decision.status != DialectDecisionEngine.Status.RESOLVED
        return EnvironmentCheckRules.dialect(text.visible, text.details, weak)
    }

    /**
     * 增强捕获项：**只读快照**，不触发任何 attach 探测（UI-005 复核结论）。
     *
     * 逐条核对过这条链上没有会阻塞的调用：
     *  - `getServiceIfCreated`——未创建返回 null，不构造 AgentManager（构造会起监听/巡检线程）；
     *  - `statusSummary()`——只读 `CopyOnWriteArrayList` 会话表与若干 `AtomicBoolean`，无锁等待；
     *  - `AgentAttachReport.latest()`——`AtomicReference.get()`，读的是**已上报**的最近一次计数，
     *    本身不会去枚举 JVM 进程（`VirtualMachine.list()`/attach API 只在真正 attach 时才用）；
     *  - `premainChannels`——`CopyOnWriteArrayList` 遍历。
     *
     * 因此本项**不需要**改成「只读缓存」以外的形态：它本来就不主动探测。真正的慢点不在 agent。
     */
    private fun agentItem(project: Project?): EnvironmentCheckItem {
        val settings = runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state }
            .getOrNull()
        val enabled = settings?.enhancedCaptureEnabled ?: false
        if (project == null) {
            return EnvironmentCheckRules.agent(enabled, null, null, premainSession = false)
        }
        val agent = runCatching { project.getServiceIfCreated(AgentManager::class.java) }.getOrNull()
        val status = runCatching { agent?.statusSummary() }.getOrNull()
        val premain = runCatching {
            agent?.premainChannels?.any { it.claimed && it.pid > 0L && !it.closed } == true
        }.getOrDefault(false)
        return EnvironmentCheckRules.agent(
            enabled = enabled,
            statusText = status,
            stats = AgentAttachReport.latest(),
            premainSession = premain,
        )
    }

    private fun captureItem(project: Project?): EnvironmentCheckItem {
        if (project == null) {
            return EnvironmentCheckRules.capture(
                capturing = false,
                entries = 0,
                consolesAttached = 0,
                linesSeen = 0L,
                agentEventsReceived = AgentEventAdapter.receivedEvents(),
                replayDropped = 0L,
                epShortCircuited = 0L,
            )
        }
        // 只读：未创建＝未在采，绝不为了体检拉起日志管理器
        val manager = runCatching { sqlLogManagerIfCreated(project) }.getOrNull()
        val bridge: SqlLogConsoleBridge? = runCatching { manager?.consoleBridge }.getOrNull()
        return EnvironmentCheckRules.capture(
            capturing = manager?.capturing == true,
            entries = runCatching { manager?.entries?.size() }.getOrNull() ?: 0,
            consolesAttached = runCatching { bridge?.attachedConsoles?.size }.getOrNull() ?: 0,
            linesSeen = runCatching { bridge?.consoleLinesSeen?.get() }.getOrNull() ?: 0L,
            agentEventsReceived = AgentEventAdapter.receivedEvents(),
            replayDropped = runCatching { bridge?.replayDropCount }.getOrNull() ?: 0L,
            epShortCircuited = runCatching { bridge?.epActiveShortCircuitCount?.get() }.getOrNull() ?: 0L,
        )
    }
}
