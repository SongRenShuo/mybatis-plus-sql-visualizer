package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.HibernateLogParser
import cn.song.mybatisplus.visualizer.log.JdbcTemplateLogParser
import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.LogThreadRouter
import cn.song.mybatisplus.visualizer.log.MyBatisLogParser
import cn.song.mybatisplus.visualizer.log.ShardingSphereLogParser
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints

/**
 * sqlv_render_logs 工具（T-McpToolset2 / T-MTS2-01，R1 纯逻辑）：MyBatis / Hibernate /
 * JdbcTemplate 日志文本 → 可执行 SQL。
 *
 * 复用 IDE「日志 → SQL」面板同款三解析器链（feed → closePending → takeEntries，
 * `LogToSqlPanel.computeConversion` 同构、纯计算不触 Swing）；条目直接消费
 * `LogSqlEntry.restoredSql`。前缀参数缺省读插件设置（与面板同源单点），工具参数可逐项覆盖
 * （null/空串 = 不覆盖，遵循设置）。逻辑分层同 [SqlVisualizerCheckSqlTool]：[renderLogs] 纯函数可测。
 */
open class SqlVisualizerRenderLogsTool : McpToolsetSupport("sqlv_render_logs") {

    @McpTool(name = "sqlv_render_logs", title = "Restore MyBatis/Hibernate/JdbcTemplate/ShardingSphere logs into executable SQL")
    @McpDescription(
        "Restore executable SQL from application log text (MyBatis 'Preparing:'/'Parameters:' pairs, " +
            "Hibernate 'Hibernate:' SQL with 'binding parameter' lines, Spring JdbcTemplate debug lines, " +
            "or ShardingSphere 5.x sql-show 'Logic SQL:'/'Actual SQL:' dual segments). " +
            "Pass the raw multi-line log text as 'logText'. Prefix overrides (sqlPrefix/paramPrefix/" +
            "hibernateSqlPrefix/hibernateParamPrefix) and 'mixedPrefixMatching' are optional and default to " +
            "the plugin's log-capture settings. Each restored statement is prefixed with a '-- SQL #N' " +
            "comment line carrying its source format. Deterministic restoration, not validation — pair with " +
            "sqlv_check_sql to assess write risk before executing. Read-only: never executes SQL. " +
            "Output tier (D219): 'detail' defaults to true (full restored SQL per statement, the historical " +
            "contract); pass detail=false to get a bounded summary — one header line plus a 160-char SQL " +
            "preview per statement — when only counts/shapes are needed and the AI context budget is tight. " +
            "Masking (R60): when the plugin's 'MCP output masking' setting is enabled, sensitive literals " +
            "(password-like columns, ID-card/mobile shapes) are masked as '***' in the restored statements " +
            "to keep real production values out of the AI context; disabled by default for diagnosability.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun sqlv_render_logs(
        logText: String,
        sqlPrefix: String = "",
        paramPrefix: String = "",
        hibernateSqlPrefix: String = "",
        hibernateParamPrefix: String = "",
        mixedPrefixMatching: Boolean? = null,
        /** D219（T-HdAbsorb）：输出分档——null=明细（历史契约，不变）；false=摘要档（计数+160 字预览）；true=明细。 */
        detail: Boolean? = null,
    ): String = audit("sqlv_render_logs") {
        renderLogs(
            logText, sqlPrefix, paramPrefix, hibernateSqlPrefix, hibernateParamPrefix, mixedPrefixMatching,
            detail = detail,
        )
    }

    /** 纯函数核心：defaults 传 null 时从插件设置装配（服务未注册兜底内置默认）。 */
    internal fun renderLogs(
        logText: String,
        sqlPrefix: String = "",
        paramPrefix: String = "",
        hibernateSqlPrefix: String = "",
        hibernateParamPrefix: String = "",
        mixedPrefixMatching: Boolean? = null,
        defaults: LogPrefixDefaults? = null,
        /** R60（T-LaneB D96/D97）：MCP 面脱敏——null 读设置 mcpMaskEnabled（默认关，审计册口径）。 */
        maskLiterals: Boolean? = null,
        /** D219：输出分档——null/true=明细（历史契约不变）；false=摘要档（每条一行头 + 160 字预览）。 */
        detail: Boolean? = null,
    ): String {
        if (logText.isBlank()) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "logText is empty."))
        }
        val effectiveDefaults = defaults ?: runCatching {
            PluginSettings.getInstance().state.let { state ->
                LogPrefixDefaults(
                    sqlPrefix = state.logSqlPrefix,
                    paramPrefix = state.logParamPrefix,
                    mixedPrefixMatching = state.logMixedPrefix,
                    hibernateSqlPrefix = state.logHibernateSqlPrefix,
                    hibernateParamPrefix = state.logHibernateParamPrefix,
                    jdbcEnabled = state.logJdbcEnabled,
                    maxParseLength = state.logMaxParseLength,
                    shardingEnabled = state.logShardingSphereEnabled,
                )
            }
        }.getOrDefault(LogPrefixDefaults())
        val entries = renderLogEntries(
            logText, sqlPrefix, paramPrefix, hibernateSqlPrefix, hibernateParamPrefix,
            mixedPrefixMatching, effectiveDefaults,
        )
        if (entries.isEmpty()) {
            // 0 条是合法结果（日志里确实没有匹配对）——正常返回 + 指引，不走错误通道
            return "No log entries recognized. Expected MyBatis '${effectiveDefaults.sqlPrefix}' / " +
                "'${effectiveDefaults.paramPrefix}' pairs (or Hibernate / JdbcTemplate formats) with SQL and " +
                "parameters on adjacent lines. Check the prefixes or pass explicit overrides."
        }
        return buildString {
            // R60（T-LaneB D96/D97）：MCP 面脱敏——null 读设置（服务未注册兜底关），只打码输出文本
            val maskOn = maskLiterals ?: runCatching {
                PluginSettings.getInstance().state.mcpMaskEnabled
            }.getOrDefault(false)
            // D219：输出分档——null/true=明细（历史契约逐字不变）；false=摘要档
            if (detail == false) {
                append("Restored ").append(entries.size).append(" statement(s) (summary — pass detail=true for full SQL):\n\n")
                entries.forEachIndexed { index, entry ->
                    append("-- SQL #").append(index + 1)
                    append(" [").append(entry.source.name.lowercase()).append("]")
                    if (entry.sourceLine > 0) append(" log line ").append(entry.sourceLine)
                    entry.executionTimeMs?.let { append(" (").append(it).append("ms)") }
                    cn.song.mybatisplus.visualizer.util.SqlStatementNamer.nameOf(entry.restoredSql)
                        ?.let { append(" [").append(it).append("]") }
                    append("\n")
                        .append(cn.song.mybatisplus.visualizer.util.DisplayMasker.maskDisplay(
                            entry.restoredSql.previewHead(), maskOn))
                    if (index < entries.size - 1) append("\n\n")
                }
                return@buildString
            }
            append("Restored ").append(entries.size).append(" statement(s):\n\n")
            entries.forEachIndexed { index, entry ->
                append("-- SQL #").append(index + 1)
                append(" [").append(entry.source.name.lowercase()).append("]")
                if (entry.sourceLine > 0) append(" log line ").append(entry.sourceLine)
                entry.executionTimeMs?.let { append(" (").append(it).append("ms)") }
                // T-StatementNaming（O-19）：语句前导注释名附注（无注释零差异）
                cn.song.mybatisplus.visualizer.util.SqlStatementNamer.nameOf(entry.restoredSql)
                    ?.let { append(" [").append(it).append("]") }
                append("\n")
                    .append(cn.song.mybatisplus.visualizer.util.DisplayMasker.maskDisplay(entry.restoredSql, maskOn))
                if (index < entries.size - 1) append("\n\n")
            }
        }
    }

    data class LogPrefixDefaults(
        val sqlPrefix: String = "Preparing:",
        val paramPrefix: String = "Parameters:",
        val mixedPrefixMatching: Boolean = true,
        val hibernateSqlPrefix: String = "Hibernate:",
        val hibernateParamPrefix: String = "binding parameter",
        val jdbcEnabled: Boolean = true,
        val maxParseLength: Int = 20_000,
        /** R10：ShardingSphere 双段日志解析开关（D3=A 默认开）。 */
        val shardingEnabled: Boolean = true,
    )

    companion object {
        /** D219 摘要档单条 SQL 预览长度上限（非行为契约，明细档不受影响）。 */
        private const val SUMMARY_PREVIEW_CHARS = 160

        fun String.previewHead(): String =
            if (length <= SUMMARY_PREVIEW_CHARS) this else substring(0, SUMMARY_PREVIEW_CHARS) + "…"

        /**
         * 纯函数核心（普通 JUnit 直测）：装配四解析器 → 逐行喂 → 合并条目。
         * 覆盖参数空串/null 回落 [defaults]；解析器装配与 `LogToSqlPanel.buildParsers` 同参同构。
         * R10：第四格式 = ShardingSphere 双段日志（Logic SQL / Actual SQL，骨架按公开文档形态）。
         */
        fun renderLogEntries(
            logText: String,
            sqlPrefix: String = "",
            paramPrefix: String = "",
            hibernateSqlPrefix: String = "",
            hibernateParamPrefix: String = "",
            mixedPrefixMatching: Boolean? = null,
            defaults: LogPrefixDefaults = LogPrefixDefaults(),
        ): List<LogSqlEntry> {
            val myBatis = MyBatisLogParser(
                sqlPrefix = sqlPrefix.ifBlank { defaults.sqlPrefix },
                paramPrefix = paramPrefix.ifBlank { defaults.paramPrefix },
                mixedPrefix = mixedPrefixMatching ?: defaults.mixedPrefixMatching,
                maxParseLength = defaults.maxParseLength,
            )
            val hibernate = HibernateLogParser(
                sqlPrefix = hibernateSqlPrefix.ifBlank { defaults.hibernateSqlPrefix },
                paramPrefix = hibernateParamPrefix.ifBlank { defaults.hibernateParamPrefix },
                mixedPrefix = mixedPrefixMatching ?: defaults.mixedPrefixMatching,
                maxParseLength = defaults.maxParseLength,
            )
            val jdbc = JdbcTemplateLogParser(
                enabled = defaults.jdbcEnabled,
                maxParseLength = defaults.maxParseLength,
            )
            val sharding = ShardingSphereLogParser(
                maxParseLength = defaults.maxParseLength,
            )
            logText.lineSequence().forEach { line ->
                // F58（T-PerfSweep18 压轴）：同一行喂四解析器——线程 token 每行只提一次下传
                val threadToken = LogThreadRouter.threadTokenOf(line)
                myBatis.feed(line, threadToken)
                hibernate.feed(line, threadToken)
                jdbc.feed(line, threadToken)
                if (defaults.shardingEnabled) sharding.feed(line, threadToken)
            }
            myBatis.closePending()
            hibernate.closePending()
            jdbc.closePending()
            sharding.closePending()
            return myBatis.takeEntries() + hibernate.takeEntries() + jdbc.takeEntries() + sharding.takeEntries()
        }
    }
}
