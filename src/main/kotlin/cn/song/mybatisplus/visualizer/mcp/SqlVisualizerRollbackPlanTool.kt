package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints

/**
 * sqlv_rollback_plan 工具（T-McpToolset2 / T-MTS2-01，R1 纯逻辑）：为 INSERT / UPDATE / DELETE
 * 生成回滚预案（评级 + 可执行回滚语句 + 警示 + 自查模板）。
 *
 * 复用 T-RollbackSql 的 RollbackSqlGenerator（零 PSI 零 IDE，诚实拒绝不可逆推类型——
 * UNABLE 时 statements 恒空给人工恢复指引）。**只产出预案，绝不执行**——执行回滚是用户
 * 在 IDE 预览面板的显式动作，agent 面不提供执行通道。
 * 警示文案经 [MpSqlVisualizerBundle.messageEn]（bundle base 即英文，零新键）。
 * 逻辑分层同 [SqlVisualizerCheckSqlTool]：[rollbackPlan] 纯函数可测，挂起方法只做 audit 包装。
 */
open class SqlVisualizerRollbackPlanTool : McpToolsetSupport("sqlv_rollback_plan") {

    @McpTool(name = "sqlv_rollback_plan", title = "Generate a rollback plan for INSERT/UPDATE/DELETE")
    @McpDescription(
        "Generate a rollback plan for INSERT / UPDATE / DELETE statements WITHOUT executing anything: " +
            "a completeness rating (COMPLETE = primary-key located, PARTIAL = executable with risk " +
            "warnings, UNABLE = not derivable — statements list stays empty with recovery guidance), " +
            "candidate rollback statements, warnings, a pre-execution COUNT check template, and a " +
            "snapshot SELECT template. Pass 'primaryKeyColumns' (comma-separated) when the table has a " +
            "primary key to get precise per-row rollback. Deterministic text derivation; " +
            "read-only: produces a plan only — never touches the database.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun sqlv_rollback_plan(
        sql: String,
        primaryKeyColumns: String = "",
    ): String = audit("sqlv_rollback_plan") { rollbackPlan(sql, primaryKeyColumns) }

    /** 纯函数核心（零 IDE 依赖）。 */
    internal fun rollbackPlan(sql: String, primaryKeyColumns: String): String =
        formatRollbackPlan(rollbackResult(sql, primaryKeyColumns))

    /**
     * NEW-23 接线缝：只生成结果不渲染——[McpWritePathChain] 的执行前清单复用本函数的产物，
     * 保证「清单里的回滚语句」与 sqlv_rollback_plan 的输出出自同一条 SQL 文本、同一份主键口径。
     */
    internal fun rollbackResult(sql: String, primaryKeyColumns: String): RollbackSqlGenerator.RollbackResult {
        if (sql.isBlank()) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "sql is empty."))
        }
        val pk = primaryKeyColumns.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { null }
        return RollbackSqlGenerator.generate(sql, pk)
    }

    /** 结果 → 文本（工具输出与清单共用的唯一渲染点）。 */
    internal fun formatRollbackPlan(result: RollbackSqlGenerator.RollbackResult): String {
        return buildString {
            append("rating: ").append(result.rating).append('\n')
            result.targetTable?.let { append("table: ").append(it).append('\n') }
            if (result.statements.isEmpty()) {
                append("statements: (none — see warnings)\n")
            } else {
                append("rollback statements (").append(result.statements.size).append("):\n")
                result.statements.forEachIndexed { index, statement ->
                    // T-StatementNaming（O-19）：语句前导注释名附注（无注释零差异）
                    append("-- SQL #").append(index + 1)
                    cn.song.mybatisplus.visualizer.util.SqlStatementNamer.nameOf(statement)
                        ?.let { append(" [").append(it).append("]") }
                    append('\n').append(statement).append('\n')
                }
            }
            if (result.warnings.isNotEmpty()) {
                append("\nwarnings:\n")
                result.warnings.forEach { warning ->
                    append("- ").append(MpSqlVisualizerBundle.messageEn(warning.messageKey, *warning.messageArgs.toTypedArray())).append('\n')
                }
            }
            result.countCheckSql?.let { append("\ncount check (run first): ").append(it).append('\n') }
            result.snapshotSelectSql?.let { append("snapshot template: ").append(it).append('\n') }
        }.trimEnd()
    }
}
