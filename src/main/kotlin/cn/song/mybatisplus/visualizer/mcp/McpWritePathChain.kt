package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * NEW-23 安全写路径「计划链」（执行前清单）。
 *
 * **本券是接线不是新建**：把已有的纯逻辑资产按一次调用串成 agent 可读的执行前清单——
 *  - 风险等级／执行闸门：`UnboundedWriteDetector`（无界写七规则，O-9）；
 *  - 影响面要点：`DmlImpactEstimator`（UPDATE/DELETE 的只读 COUNT 试算语句，V60）；
 *  - 回滚语句：`RollbackSqlGenerator`（T-RollbackSql），**格式与 sqlv_rollback_plan 同一处产出**
 *    （[SqlVisualizerRollbackPlanTool.rollbackResult]／[SqlVisualizerRollbackPlanTool.formatRollbackPlan]）——
 *    同一条 SQL 文本、同一份 before-image 判定、同一份方言口径，回滚语句与执行目标天然一致。
 *
 * **执行环**：本对象**不执行任何语句**，也不提供执行通道。清单第 4 步把执行交回 IDE 预览面板，
 * 那里的确认流已经串着三道既有闸门（`UnboundedWriteDetector`、只读豁免证明、`ExecutionDialectGate`）。
 * 红线「不得新增绕过写安全闸门的通道」由此按构造满足——MCP 侧根本不存在写通道可绕。
 *
 * **P-9 证伪结论（2026-10-31）**：三步之间**不需要**共享中间态。渲染步的产品就是 SQL 文本
 * （`sqlv_render_sql` 的返回值），EXPLAIN 与回滚计划都以「SQL 文本 + 主键列名」为入参；回滚所需的
 * before-image 在插件侧本就不可得（`RollbackSqlGenerator` 对 UPDATE/DELETE 诚实拒绝为 UNABLE，
 * 不伪造半成品）。故本链是纯文本进／纯文本出，**零临时文件、零跨调用存储**；内存里唯一的跨调用
 * 状态是探测器既有的单槽 AST 缓存（`UnboundedWriteDetector.astSlot`，容量一条、不落盘），本链不新增。
 *
 * 零 PSI／零 IDE 依赖（仅 JSqlParser 链上的既有纯逻辑对象），普通 JUnit 可直接覆盖。
 */
object McpWritePathChain {

    /** 风险等级（写安全口径：只看作用域能否静态收敛，回滚可逆性另行以 rating 报告）。 */
    enum class RiskLevel {
        /** 无写语句（纯查询）。 */
        NONE,
        /** 有界写（WHERE 带列引用），回滚另见 rollbackRating。 */
        LOW,
        /** 类型无法静态归类（如过程调用）——不阻断，按确认口径。 */
        MEDIUM,
        /** 无界写（七规则命中）或 DDL（不可静态收敛且文本不可逆推）。 */
        HIGH,
    }

    /** 执行闸门判定（agent 面口径：代理不得把 BLOCKED 的语句交给任何自动化通道）。 */
    enum class ExecutionGate { READ_ONLY, CONFIRM, BLOCKED }

    /** 判定结果：等级 + 闸门 + 理由（理由为融合发现的英文短句）。 */
    data class Assessment(val riskLevel: RiskLevel, val gate: ExecutionGate, val reason: String)

    /** 执行移交固定文案（零弹窗红线：执行动作只在 IDE 里由用户显式触发）。 */
    private const val EXECUTE_HANDOFF =
        "not performed by MCP — execution stays in the IDE preview panel, whose confirmation flow " +
            "applies UnboundedWriteDetector, the read-only exemption proof and ExecutionDialectGate. " +
            "This tool never opens a write channel."

    /**
     * 风险判定（纯函数）：探测结果 + 语句类型 → 等级／闸门。
     *
     * 判定序固定：无界写 → DDL → 有界 DML → 纯查询 → 未知类型（fail 到 MEDIUM，不阻断）。
     * DDL 归 HIGH 的理由：TRUNCATE/DROP 既无 WHERE 可估作用域，也无从文本反推回滚语句，
     * 与「有界写」不是同一档。
     */
    internal fun assess(
        types: List<SqlTypeClassifier.SqlType>,
        risk: UnboundedWriteDetector.WriteRisk,
    ): Assessment {
        val kinds = risk.kinds().sorted()
        val findings = kinds.joinToString(", ")
        return when {
            risk.dangerous -> Assessment(RiskLevel.HIGH, ExecutionGate.BLOCKED, "unbounded write ($findings)")
            types.any { it == SqlTypeClassifier.SqlType.DDL } ->
                Assessment(RiskLevel.HIGH, ExecutionGate.BLOCKED, "DDL is not statically bounded and has no derivable rollback")
            types.any { it == SqlTypeClassifier.SqlType.DML } -> Assessment(
                RiskLevel.LOW,
                ExecutionGate.CONFIRM,
                if (kinds.isEmpty()) "bounded write" else "bounded write (informational: $findings)",
            )
            types.isNotEmpty() && types.all { it == SqlTypeClassifier.SqlType.QUERY } ->
                Assessment(RiskLevel.NONE, ExecutionGate.READ_ONLY, "read-only statement")
            else -> Assessment(RiskLevel.MEDIUM, ExecutionGate.CONFIRM, "statement type not statically classifiable")
        }
    }

    /**
     * 生成清单正文（接在 sqlv_check_sql 既有输出之后，空行分隔）。
     *
     * @param sql               待核查 SQL（**原文**，回滚链拿同一份文本）
     * @param types             逐语句类型（调用方已判定，避免重复分类）
     * @param risk              无界写探测结果（调用方已判定，避免重复解析）
     * @param primaryKeyColumns 主键列（逗号/分号/换行分隔，空=无主键信息）
     * @param dialectName       目标方言注册名（null=未指定，不产出影响面试算）
     */
    internal fun checklist(
        sql: String,
        types: List<SqlTypeClassifier.SqlType>,
        risk: UnboundedWriteDetector.WriteRisk,
        primaryKeyColumns: String,
        dialectName: String?,
    ): String {
        val assessment = assess(types, risk)
        val rollbackTool = SqlVisualizerRollbackPlanTool()
        return buildString {
            append("\n\npre-execution checklist:")
            append("\nriskLevel: ").append(assessment.riskLevel).append(" — ").append(assessment.reason)
            append("\nexecutionGate: ").append(assessment.gate).append(" — ").append(gateAdvice(assessment.gate))
            dialectName?.let { append("\ntargetDialect: ").append(it) }
            if (assessment.gate == ExecutionGate.READ_ONLY) {
                append("\nstep1-render: the SQL above is the statement under review (from sqlv_render_sql / sqlv_render_logs).")
                append("\nstep2-plan: call sqlv_explain with this SQL (read-only; requires a data source configured in the IDE).")
                append("\nstep3-rollback: not applicable — no write statement.")
                append(
                    "\nstep4-execute: read-only statement; run it through sqlv_explain or the IDE SQL console, " +
                        "no write gate involved.",
                )
            } else {
                // 与 sqlv_rollback_plan 同一处产出的回滚结果（同一 SQL 文本 + 同一主键口径）
                val plan = rollbackTool.rollbackResult(sql, primaryKeyColumns)
                plan.targetTable?.let { append("\ntargetTable: ").append(it) }
                append("\nstep1-render: the SQL above is the statement under review (from sqlv_render_sql / sqlv_render_logs).")
                append("\nstep2-plan: ").append(planLine(sql, types, dialectName))
                append("\nstep3-rollback: ").append(plan.rating)
                if (plan.statements.isEmpty()) {
                    append(" — no executable rollback statement (see warnings)")
                } else {
                    append(" — ").append(plan.statements.size).append(" rollback statement(s), see the plan below")
                }
                append("\nstep4-execute: ").append(EXECUTE_HANDOFF)
                append("\n\nrollback plan:\n").append(rollbackTool.formatRollbackPlan(plan))
            }
        }
    }

    /** 闸门文案（agent 面英文短句；与 McpToolsetSupport 的零弹窗约定一致）。 */
    private fun gateAdvice(gate: ExecutionGate): String = when (gate) {
        ExecutionGate.BLOCKED ->
            "do not run this statement through any automated path; narrow the predicate, or confirm the " +
                "whole-table scope explicitly in the IDE."
        ExecutionGate.CONFIRM ->
            "run the count check / impact probe first, then execute through the IDE confirmation flow."
        ExecutionGate.READ_ONLY -> "read-only statement; no write gate involved."
    }

    /** 影响面要点：UPDATE/DELETE 给只读 COUNT 试算语句；给不出时如实说明原因（bundle 既有英文键）。 */
    private fun planLine(
        sql: String,
        types: List<SqlTypeClassifier.SqlType>,
        dialectName: String?,
    ): String {
        if (types.none { it == SqlTypeClassifier.SqlType.DML }) {
            return "no scope probe for DDL — the affected scope is not statically bounded."
        }
        if (dialectName == null) {
            return "pass 'dialect' to get the read-only COUNT probe for UPDATE/DELETE."
        }
        return when (val plan = DmlImpactEstimator.plan(sql, DialectRegistry.byName(dialectName))) {
            is DmlImpactEstimator.Plan.Applicable ->
                "impact probe (read-only COUNT, run it before executing): " + plan.probe.probeSql
            is DmlImpactEstimator.Plan.NotApplicable ->
                "no impact probe: " + MpSqlVisualizerBundle.messageEn(plan.reasonKey)
        }
    }
}
