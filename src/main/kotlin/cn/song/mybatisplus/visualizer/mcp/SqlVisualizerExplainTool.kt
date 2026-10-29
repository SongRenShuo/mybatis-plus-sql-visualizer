package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * sqlv_explain 工具（T-McpToolset2 / T-MTS2-00：一期方法体逐行迁入，按工具拆 toolset 类；
 * resolveExplainTarget/executeExplain/ExplainTarget 随本类走）。
 */
open class SqlVisualizerExplainTool : McpToolsetSupport("sqlv_explain") {

    @McpTool(name = "sqlv_explain", title = "Explain a SELECT against a configured data source")
    @McpDescription(
        "Run EXPLAIN for a single read-only SELECT statement against a real data source configured in the IDE " +
            "and return the plan text (MySQL/MariaDB/PostgreSQL/Oracle/SQL Server/H2/DM/KingBase dialect dispatch, " +
            "dialect auto-detected from the data source URL unless overridden). Deterministic plan rendering for " +
            "a statement you already have — pair with sqlv_render_sql to produce it first. " +
            "'maxRows' caps how many plan rows are returned (default 200, hard limit 1000; out-of-range " +
            "values are clamped, never rejected). " +
            "Strictly read-only: the statement must be a single plain SELECT (no locking reads, no INTO, no writes); " +
            "rejections are fail-closed.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun sqlv_explain(
        selectSql: String,
        dataSource: String = "",
        dialect: String = "",
        maxRows: Int = McpExplainFormatter.DEFAULT_MAX_ROWS,
    ): String = audit("sqlv_explain", executionArgsSummary(dataSource, dialect, selectSql)) {
        // 行预算钳制（T-McpRowBudget / T-MRB-03）：越界静默收进 [1..HARD_MAX_ROWS]，不抛错。
        // 生效值随 target 过挂起点（单引用，维持 ExplainTarget 的 K2 防溢出约定）；通道层另有一道兜底。
        val target = resolveExplainTarget(
            requireProject(), selectSql, dataSource, dialect, McpExplainFormatter.clampMaxRows(maxRows),
        )
        val raw = executeExplain(target, selectSql)
        // notices 节尾附在方言行后（T-NoticeChannel / O-16）；空清单零追加——输出逐字节与
        // 现状一致（防回归硬门禁）。失败路径 notices 无从产生：executeExplain 直接 throwExpected，
        // RawResult 只在成功时存在，天然满足 DBX「出错语句的 notices 丢弃」口径。
        // index hints 节（T-ExplainIndex / N-23）：索引缺失类模式建议（同款零追加硬门禁；
        // 落点定案=T-EI-01 核实 IDE 内嵌控制台无程序化行读取面，MCP 输出为承载位）。
        McpExplainFormatter.format(raw, target.maxRows) + "\n\ndialect: ${target.dialectName}" +
            McpExplainFormatter.formatNotices(raw.notices) +
            cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor.formatHints(
                cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor.advise(
                    target.dialectName, raw.headers, raw.rows,
                ),
            )
    }

    /** explain 前置链（白名单→数据源定位→方言派发，步骤 1~3）。全链非挂起：不进协程状态机，
     *  从结构上杜绝 SpillingKt 引用；语义与分步内联版逐行一致（T-McpToolset 253/243 门禁修复）。 */
    private fun resolveExplainTarget(
        project: Project,
        selectSql: String,
        dataSource: String,
        dialect: String,
        maxRows: Int,
    ): ExplainTarget {
        // 1) 只读白名单（fail closed：非 SELECT / 多语句 / 锁读 / INTO / 解析失败全拒）
        val verdict = McpSelectGuard.checkSingleSelect(selectSql)
        if (!verdict.allowed) {
            throwExpected(McpToolErrors.code(verdict.errorCode ?: McpToolErrors.SQL_BLOCKED, verdict.reason ?: "rejected."))
        }
        // 1b) EXPLAIN 首关键字边界（T-ECH-01：与 IDE 链同一判定）。
        // 这是**守卫之后的第二道**，对结构白名单接受的输入**不可能触发**——白名单只放行
        // Select 家族（含 `Values`/`TableStatement`，JSqlParser 5.3 里两者都是 Select 子类），
        // 而 Select 家族的首关键字必落在白名单内。包含性由 `ExplainGateTest` 的语料用例钉住
        // （计划 §八 待验证前提 2 由此证伪：结构白名单对 `VALUES(1)`/`TABLE users` **不过严**，
        // 两链白名单完全对齐）。保留本判定是为了两链共用一处口径，将来白名单若被放宽，
        // 这道仍在。
        if (!cn.song.mybatisplus.visualizer.sql.ExplainGate.isExplainableSource(selectSql)) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.SQL_BLOCKED,
                    "the statement is not an explainable query (first keyword must be SELECT, VALUES, TABLE or WITH).",
                ),
            )
        }

        // 2) 数据源定位（零弹窗：不给/给不出唯一别名即报错并列可选名，绝不弹选择框）
        if (!McpDbExecutor.isDatabaseAvailable()) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.DATASOURCE_NOT_FOUND,
                    "the Database tool plugin is not available in this IDE; configure a data source first.",
                ),
            )
        }
        val ds = McpDbExecutor.findDataSource(project, dataSource) ?: run {
            val names = McpDbExecutor.listDataSourceNames(project)
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.DATASOURCE_NOT_FOUND,
                    when {
                        names.isEmpty() -> "no data sources configured in this project."
                        dataSource.isBlank() -> "several data sources exist (${names.joinToString(", ")}); " +
                            "pass the 'dataSource' display name."
                        else -> "data source '$dataSource' not found; available: ${names.joinToString(", ")}."
                    },
                ),
            )
        }

        // 3) 方言派发（EXPLAIN 语句包装，七方言矩阵与 IDE 预览面板同链）
        if (!McpDialectDetector.isRecognized(dialect)) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "dialect '$dialect' is not recognized; supported: MySQL, MariaDB, PostgreSQL, Oracle, " +
                        "SQL Server, H2, SQLite, DM, KingBase, OceanBase, openGauss, GaussDB (omit to " +
                        "auto-detect from the data source URL).",
                ),
            )
        }
        val url = runCatching {
            Class.forName("com.intellij.database.model.DasDataSource")
                .getMethod("getConnectionConfig").invoke(ds)
                ?.let { Class.forName("com.intellij.database.model.RawConnectionConfig").getMethod("getUrl").invoke(it) as? String }
        }.getOrNull()
        val dialectName = McpDialectDetector.resolve(dialect, url)
        val explainSql = DialectRegistry.byName(dialectName).renderExplainStatement(selectSql.trim().removeSuffix(";"))
            ?: throwExpected(
                McpToolErrors.code(
                    McpToolErrors.DIALECT_UNSUPPORTED,
                    "dialect '$dialectName' has no EXPLAIN rendering in this plugin version.",
                ),
            )
        return ExplainTarget(project, ds, explainSql, dialectName, maxRows)
    }

    /** 4) headless 执行（同步、无 UI、密码未保存即失败）。挂起帧只持 target 单引用。
     *  行预算取 [ExplainTarget.maxRows]（工具层已钳制，T-McpRowBudget / T-MRB-03）。
     *
     *  T-McpAuditTrail（T-MAT-01/02）：[selectSql] 为**原始**语句——执行链的成功/失败两条路径都会
     *  经 [McpDbExecutor.executeQuery] 的 onExecuted 回调触发一次落历史，落的是本参数而非
     *  [ExplainTarget.explainSql]（EXPLAIN 包装句首 token 为 EXPLAIN，落它会判 OTHER → 无类型染色）。
     *  回调在 IO 线程同步执行，内部走既有入库链（面板刷新与写盘由 SqlLogManager 自管）。 */
    private suspend fun executeExplain(target: ExplainTarget, selectSql: String): McpExplainFormatter.RawResult =
        withContext(Dispatchers.IO) {
            runCatching {
                // T-ExecTimeoutGuard：设置项「执行超时」透传（0=不限；explain 单语句取每语句基础值）
                McpDbExecutor.executeQuery(
                    target.project, target.ds, target.explainSql, target.maxRows,
                    timeoutSeconds = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
                        .state.executionTimeoutSeconds,
                    // T-ExplainPwdPrompt：MCP 通道**必须** false（零弹窗红线）——程序性调用弹
                    // 模态密码框即挂死；密码未存时保持 fail closed，错误文本交 agent 可读通道
                    askPassword = false,
                    // R23：二段式/多结果集方言（Oracle/SQL Server）的通道说明——随执行结果以
                    // notices 节透出（「行里为什么没有计划」的诚实降级）；null（其余 9 家）零追加
                    channelNote = cn.song.mybatisplus.visualizer.sql.ExplainSupport
                        .entryFor(target.dialectName)?.channelNote,
                    onExecuted = { outcome ->
                        recordMcpExecution(
                            target.project,
                            selectSql,
                            outcome.dataSourceName,
                            outcome.elapsedMs,
                            outcome.errorText,
                        )
                    },
                )
            }.getOrElse { e ->
                // 反射层 message 恒 null：格式化入口解包目标异常后提取驱动位置；超时异常
                // 已在执行通道转为带 timed out 标记的 SQLException，此处保持原有错误码。
                throwExpected(McpExplainFormatter.formatFailure(e, target.dialectName, target.explainSql))
            }
        }

    /** explain 解析产物载体：项目/数据源/方言信息/生效行预算打包单值过挂起点（挂起帧单槽位，
     *  杜绝 K2 nullOutSpilledVariable → SpillingKt 引用；该类 243 平台 stdlib 缺失，verifyPlugin 实证）。 */
    private class ExplainTarget(
        val project: Project,
        val ds: Any,
        val explainSql: String,
        val dialectName: String,
        /** 已钳制的生效行预算（T-McpRowBudget：maxRows 参数经 clampMaxRows 后的值）。 */
        val maxRows: Int,
    )
}
