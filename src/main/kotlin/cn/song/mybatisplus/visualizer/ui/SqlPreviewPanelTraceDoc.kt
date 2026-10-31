package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.history.ExplainPlanKey
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager
import cn.song.mybatisplus.visualizer.log.TraceDocExport
import cn.song.mybatisplus.visualizer.share.format.TraceDocInput
import cn.song.mybatisplus.visualizer.sql.ExplainGate
import cn.song.mybatisplus.visualizer.sql.ExplainSupport

/**
 * T-TraceDocExport S2：预览面板「更多」→「导出优化留痕 Markdown」。
 *
 * 取数与 [SqlPreviewPanel.copyAsAiContext] 同口径：用**当前编辑 SQL 的精确计划键**
 * （[ExplainPlanKey]）从历史域取快照，绝不借用别条 SQL/别个数据源的计划；键不成立时
 * 回落到本预览自己的历史条目（[cn.song.mybatisplus.visualizer.history.SqlHistoryEntry]）。
 * 完全没有计划也允许导出——组装器会明写「无计划快照」（fail-closed，不臆造）。
 */
internal fun SqlPreviewPanel.exportTraceDoc() {
    // S1-4④：无项目/无 SQL 时不再静默 return（走平台通知，不弹模态框；无项目降级静默）。
    val proj = project
    val sql = previewSqlForReadonly()?.takeIf { it.isNotBlank() }
    if (proj == null || sql == null) {
        cn.song.mybatisplus.visualizer.log.ExportFeedback
            .notifyNothingToExport(proj, "export.nothing.traceDoc")
        return
    }
    val preview = current
    val dialect = preview?.effectiveDialect
    val history = SqlHistoryManager.getInstance()
    val entry = preview?.historyEntryId?.takeIf { it.isNotBlank() }?.let { history.findById(it) }

    val target = aiContextTarget?.takeIf {
        it.preview === preview && runCatching(it.stillCurrent).getOrDefault(false)
    }
    val wrapped = dialect?.let { ExplainGate.planExplainSubmit(it, sql) as? ExplainGate.SubmitPlan.Submit }
    val format = dialect?.let { ExplainSupport.formatFor(it.name) }
    val key = if (target != null && dialect != null && wrapped != null && format != null) {
        ExplainPlanKey.create(
            ExplainPlanKey.projectIdentity(proj.basePath),
            target.key.dataSourceUuid, dialect.name, format, wrapped.statement,
        ).takeIf { it == target.key }
    } else {
        null
    }
    val plans = if (key != null) {
        history.matchingExplainPlans(key) + listOfNotNull(
            aiContextPlan?.takeIf { acquired ->
                acquired.first == history.explainPlanGeneration() && acquired.second.key() == key
            }?.second,
        )
    } else {
        entry?.explainPlans.orEmpty()
    }

    val base = entry?.let { TraceDocExport.fromHistoryEntry(it) }
    val input = TraceDocInput(
        sql = sql,
        dialect = (dialect?.name ?: base?.dialect)?.takeIf { it.isNotBlank() },
        source = base?.source,
        capturedAt = base?.capturedAt ?: System.currentTimeMillis(),
        executionTimeMs = base?.executionTimeMs,
        plans = plans.mapNotNull { TraceDocExport.planSnapshot(it) }.sortedBy { it.capturedAt },
    )
    TraceDocExport.export(proj, input)
}
