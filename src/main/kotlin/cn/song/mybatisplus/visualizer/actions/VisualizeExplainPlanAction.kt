package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.ui.ExplainPlanDialog
import cn.song.mybatisplus.visualizer.ui.ExplainPlanResultView
import cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.database.actions.ExplainActionBase
import com.intellij.database.console.JdbcConsole
import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * PEV-05（postgres-explain-visualizer/pev2 吸收）：控制台选中语句**直出结构化执行计划**。
 *
 * 平台契约（tjhelmuth 壳 1.12.2 实证、javap 2024.1 复核）：继承 [ExplainActionBase.Ui]（static 内类）
 * 白拿选区提取、控制台定位与 update 禁用态；覆写 [explainStatement] 拿到 `provider + 活连接
 * [JdbcConsole] + 选中语句` 后**改走我方自持结构化链**（[ExplainPanelChannel.executeStructured]），
 * 渲染复用 [ExplainPlanResultView]（徽章/统计/收拢三件随 PEV-02/03/06 自动生效）。本类不碰
 * 平台 DataRequest 生产线，也不做壳那套「假域 scheme handler + 手拼 executeJavaScript 注入」
 * （三反面教材，吸收报告 §一.3）。
 *
 * 结构化收敛面（[isSupported]）：仅 PG 族（JSON 档）与 MySQL 族（TABLE 档）亮起；其余方言
 * 保持禁用——原生 EXPLAIN 动作仍是那条路（双通道并存，不做能力收窄）。MySQL 的
 * EXPLAIN ANALYZE 输出 TEXT 树（结构化通道不收），故 Analyze 动作只对 PG 开。
 *
 * 安全裁量两条（吸收源壳「写死命令零防护」的反面教材翻正面）：
 *  ① DML + ANALYZE＝真实执行写（触发器会触发、序列会烧号）——拦截并给可读提示
 *    （[SqlTypeClassifier] 既有豁免盲区建模同源）；
 *  ② 未回填 `?` 占位符不执行（R33 既有口径复用）。
 */
abstract class VisualizeExplainPlanActionBase(private val analyze: Boolean) : ExplainActionBase.Ui(analyze) {

    override fun isSupported(e: AnActionEvent): Boolean {
        val console = getConsole(e) ?: return false
        val ds = console.dataSource ?: return false
        return when {
            // PG 族：JSON 档（ANALYZE 档按平台 provider 对该版本的支持面给）
            ds.dbms.isPostgres() -> ExplainPlanProvider.getDefaultProvider(ds)?.isSupported(ds.version, analyze) == true
            // MySQL 族：仅普通 EXPLAIN（TABLE 档）；EXPLAIN ANALYZE 输出 TEXT 树，结构化不收
            ds.dbms.isMysql() -> !analyze
            else -> false
        }
    }

    override fun explainStatement(provider: ExplainPlanProvider, console: JdbcConsole, statement: String) {
        val project = console.project ?: return
        val ds = console.dataSource ?: return
        val isPostgres = ds.dbms.isPostgres()
        val dialectName = if (isPostgres) "PostgreSQL" else "MySQL"

        if (analyze && SqlTypeClassifier.classify(statement) == SqlTypeClassifier.SqlType.DML) {
            notify(
                project,
                MpSqlVisualizerBundle.message("action.visualizePlan.analyzeDmlBlocked.title"),
                MpSqlVisualizerBundle.message("action.visualizePlan.analyzeDmlBlocked.body"),
                NotificationType.WARNING,
            )
            return
        }
        if (statement.contains("?")) {
            notify(
                project,
                MpSqlVisualizerBundle.message("panel.explain.structured.title"),
                MpSqlVisualizerBundle.message("panel.explain.structured.reason.unfilledParams"),
                NotificationType.WARNING,
            )
            return
        }

        val format = if (isPostgres) ExplainOutputFormat.JSON else ExplainOutputFormat.TABLE
        ExplainPanelChannel.executeStructured(
            project,
            ds,
            buildExplainCommand(isPostgres, statement, analyze),
            timeoutSeconds = PluginSettings.getInstance().state.executionTimeoutSeconds,
            onPlan = { outcome ->
                // 索引建议联动与宿主装配段同口径：建议器异常不连坐计划渲染
                val hints = runCatching {
                    ExplainIndexAdvisor.advise(dialectName, outcome.raw.headers, outcome.raw.rows)
                }.getOrDefault(emptyList())
                val view = ExplainPlanResultView(
                    dialectName = dialectName,
                    dataSourceName = outcome.dataSourceName,
                    elapsedMs = outcome.elapsedMs,
                    raw = outcome.raw,
                    format = format,
                    hints = hints,
                )
                ExplainPlanDialog(project, view).show()
            },
            onFailed = { errorText ->
                notify(
                    project,
                    MpSqlVisualizerBundle.message("action.visualizePlan.failed.title"),
                    errorText.take(MAX_FAILURE_PREVIEW),
                    NotificationType.ERROR,
                )
            },
        )
    }

    /**
     * PEV-05 选项渐进披露：默认 FORMAT JSON（PG 族，结构化可渲染档）；ANALYZE/BUFFERS 只随
     * 显式的 Analyze 动作带上——「安全默认 + 用户显式升级」，不做成静默全局配置。
     */
    private fun buildExplainCommand(isPostgres: Boolean, statement: String, analyze: Boolean): String {
        val trimmed = statement.trim().trimEnd(';').trim()
        return if (isPostgres) {
            "EXPLAIN (FORMAT JSON, VERBOSE, COSTS" + (if (analyze) ", BUFFERS, ANALYSE" else "") + ") " + trimmed
        } else {
            "EXPLAIN " + trimmed
        }
    }

    private fun notify(project: Project, title: String, body: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, body, type)
            .notify(project)
    }

    private companion object {
        const val NOTIFICATION_GROUP = "cn.song.mybatisplus.visualizer.preview"
        const val MAX_FAILURE_PREVIEW = 500
    }
}

/** 普通可视化（EXPLAIN，不执行语句）。 */
class VisualizeExplainPlanAction : VisualizeExplainPlanActionBase(false)

/** ANALYZE 可视化（EXPLAIN ANALYZE——真实执行；DML 会被拦截）。 */
class VisualizeExplainPlanAnalyzeAction : VisualizeExplainPlanActionBase(true)
