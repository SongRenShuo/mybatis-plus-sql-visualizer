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
            "Set 'chain=true' to also get the pre-execution checklist for the write path: risk level and " +
            "execution gate from the unbounded-write rules, a read-only COUNT impact probe (pass 'dialect'), " +
            "the rollback plan generated from the same SQL text (pass 'primaryKeyColumns' for precise " +
            "per-row rollback), and the execution hand-off. The chain never executes SQL and never opens a " +
            "write channel — execution stays in the IDE preview panel confirmation flow. " +
            "Read-only: analysis only, never executes SQL.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun sqlv_check_sql(
        sql: String,
        chain: Boolean = false,
        primaryKeyColumns: String = "",
        dialect: String = "",
    ): String = audit("sqlv_check_sql") { checkSql(sql, chain, primaryKeyColumns, dialect) }

    /**
     * 纯函数核心（零 IDE 依赖）。
     *
     * NEW-23：`chain=true` 追加执行前清单（[McpWritePathChain]）；`chain=false` 输出与接线前逐字节一致。
     * 本工具**不执行任何语句**——清单只产出语句与判定，执行交回 IDE 既有确认流。
     */
    internal fun checkSql(
        sql: String,
        chain: Boolean = false,
        primaryKeyColumns: String = "",
        dialect: String = "",
    ): String {
        if (sql.isBlank()) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "sql is empty."))
        }
        if (!McpDialectDetector.isRecognized(dialect)) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "dialect '$dialect' is not recognized; omit it or pass one of the supported dialect names.",
                ),
            )
        }
        val statements = SqlStatementSplitter.splitStripped(sql)
        val types = statements.map { SqlTypeClassifier.classify(it) }
        val risk = UnboundedWriteDetector.detect(sql)
        val base = buildString {
            append("statements: ").append(statements.size).append('\n')
            types.forEachIndexed { index, type ->
                append("statement #").append(index + 1).append(": ").append(type).append('\n')
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
        if (!chain) return base
        val dialectName = dialect.trim().takeIf { it.isNotEmpty() }?.let { McpDialectDetector.resolve(it, null) }
        return base + McpWritePathChain.checklist(sql, types, risk, primaryKeyColumns, dialectName)
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
