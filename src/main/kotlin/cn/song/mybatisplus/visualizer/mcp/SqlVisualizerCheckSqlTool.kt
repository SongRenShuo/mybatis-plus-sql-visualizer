package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector
import cn.song.mybatisplus.visualizer.util.SqlStatementSplitter
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints

/**
 * sqlv_check_sql 工具（T-McpToolset2 / T-MTS2-01，R1 纯逻辑）：SQL 静态写风险与类型核查。
 *
 * 组合三件纯逻辑资产：SqlStatementSplitter（语句数）+ SqlTypeClassifier（QUERY/DML/DDL 分类）
 * + UnboundedWriteDetector（O-9 七规则无界写探测）。纯文本判定，**绝不执行**。
 *
 * T-MTS2-05 分层：业务逻辑在 [checkSql]（internal 纯函数，普通 JUnit 直测）；挂起方法只做
 * audit 包装——243 测试 JVM 的 stdlib 缺 SpillingKt，audit 本体的高阶 suspend 结构性发射
 * 在测试进程加载即 NoClassDefFoundError（一期语义=mcp-optional.xml 不加载则类永不可达，
 * ignored-problems 豁免留痕），故测试一律走纯函数层，挂起链只有真机/生产触达。
 */
open class SqlVisualizerCheckSqlTool : McpToolsetSupport("sqlv_check_sql") {

    @McpTool(name = "sqlv_check_sql", title = "Static write-risk and type check for a SQL statement")
    @McpDescription(
        "Statically check SQL text (no execution, no database access): statement count, per-statement " +
            "type (QUERY/DML/DDL), and unbounded-write findings — missing WHERE (whole-table scope), " +
            "tautological conditions (e.g. 1=1), tautological OR branches, pure-wildcard LIKE ('%'), " +
            "constant-only WHERE, subqueries (informational), and unparseable statements (fail-closed). " +
            "Deterministic static analysis via JSqlParser AST. Run this before sqlv_explain on write " +
            "statements, or after sqlv_render_logs / sqlv_render_sql to assess what rendering produced. " +
            "Read-only: analysis only, never executes SQL.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun sqlv_check_sql(sql: String): String = audit("sqlv_check_sql") { checkSql(sql) }

    /** 纯函数核心（零 IDE 依赖）。 */
    internal fun checkSql(sql: String): String {
        if (sql.isBlank()) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "sql is empty."))
        }
        // T-SAF-17（mcp-sqlv-text-tools-unbounded-sql-input）：输入长度上限——对齐 render-logs
        // 通道 logMaxParseLength 默认 20000 口径（PluginSettings.DEFAULT_LOG_MAX_PARSE_LENGTH），
        // agent 提供的无界 SQL 不再直通语句拆分/解析/探测链（超长即结构化拒绝）
        if (sql.length > cn.song.mybatisplus.visualizer.config.PluginSettings.DEFAULT_LOG_MAX_PARSE_LENGTH) {
            throwExpected(
                McpToolErrors.maxLength("sql", cn.song.mybatisplus.visualizer.config.PluginSettings.DEFAULT_LOG_MAX_PARSE_LENGTH),
            )
        }
        val statements = SqlStatementSplitter.splitStripped(sql)
        val risk = UnboundedWriteDetector.detect(sql)
        return buildString {
            append("statements: ").append(statements.size).append('\n')
            statements.forEachIndexed { index, statement ->
                append("statement #").append(index + 1).append(": ")
                    .append(SqlTypeClassifier.classify(statement)).append('\n')
            }
            append("dangerous: ").append(risk.dangerous).append('\n')
            if (risk.findings.isEmpty()) {
                append("findings: none")
            } else {
                append("findings:\n")
                risk.kinds().sorted().forEachIndexed { index, kind ->
                    append(index + 1).append(". ").append(kindEnglish(kind))
                    risk.findings.firstOrNull { it.kind == kind }?.detail?.takeIf { it.isNotBlank() }
                        ?.let { append(" — matched: ").append(it) }
                    append('\n')
                }
            }
        }.trimEnd()
    }

    /** 发现类别 → agent 面英文短句（英文常量不进 bundle 的一期口径；detail 截断在探测器侧已做）。 */
    private fun kindEnglish(kind: UnboundedWriteDetector.FindingKind): String = when (kind) {
        UnboundedWriteDetector.FindingKind.MISSING_WHERE ->
            "MISSING_WHERE: no WHERE clause — the statement applies to the whole table"
        UnboundedWriteDetector.FindingKind.TAUTOLOGY ->
            "TAUTOLOGY: tautological condition (e.g. 1=1) — the WHERE clause does not filter"
        UnboundedWriteDetector.FindingKind.OR_TAUTOLOGY ->
            "OR_TAUTOLOGY: an OR branch is tautological — every row matches"
        UnboundedWriteDetector.FindingKind.LIKE_WILDCARD ->
            "LIKE_WILDCARD: LIKE with a pure-wildcard pattern (e.g. '%') — equivalent to no filter"
        UnboundedWriteDetector.FindingKind.NO_COLUMN_REF ->
            "NO_COLUMN_REF: the WHERE clause contains only constant expressions — no column reference"
        UnboundedWriteDetector.FindingKind.SUBQUERY ->
            "SUBQUERY: the WHERE clause contains a subquery — scope depends on its result (informational)"
        UnboundedWriteDetector.FindingKind.PARSE_FAILED ->
            "PARSE_FAILED: statement structure could not be parsed — verify the scope manually"
    }
}
