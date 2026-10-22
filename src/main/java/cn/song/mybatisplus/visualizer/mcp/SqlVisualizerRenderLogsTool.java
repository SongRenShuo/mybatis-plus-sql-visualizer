// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerRenderLogsTool.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.log.HibernateLogParser;
import cn.song.mybatisplus.visualizer.log.JdbcTemplateLogParser;
import cn.song.mybatisplus.visualizer.log.LogSqlEntry;
import cn.song.mybatisplus.visualizer.log.MyBatisLogParser;
import cn.song.mybatisplus.visualizer.log.ShardingSphereLogParser;
import com.intellij.mcpserver.annotations.McpDescription;
import com.intellij.mcpserver.annotations.McpTool;
import com.intellij.mcpserver.annotations.McpToolHintValue;
import com.intellij.mcpserver.annotations.McpToolHints;

import java.util.ArrayList;
import java.util.List;

/**
 * sqlv_render_logs 工具（T-McpToolset2 / T-MTS2-01，R1 纯逻辑）：MyBatis / Hibernate /
 * JdbcTemplate / ShardingSphere 日志文本 → 可执行 SQL。
 *
 * 复用 IDE「日志 → SQL」面板同款解析器链（feed → closePending → takeEntries，
 * `LogToSqlPanel.computeConversion` 同构、纯计算不触 Swing）；条目直接消费
 * `LogSqlEntry.restoredSql`。前缀参数缺省读插件设置（与面板同源单点），工具参数可逐项覆盖
 * （null/空串 = 不覆盖，遵循设置）。逻辑分层同 {@link SqlVisualizerCheckSqlTool}：{@link #renderLogs} 纯函数可测。
 *
 * R10：第四格式 = ShardingSphere 双段日志（Logic SQL / Actual SQL，骨架按公开文档形态）。
 *
 * 移植注记：平台以 kotlin-reflect 识别工具方法并同步调用（见 McpToolsetSupport 类头移植注记）；
 * logText 必传，前缀覆盖参数的 Kotlin 默认值改为调用方必传空串语义等价（空串=不覆盖）。
 */
public class SqlVisualizerRenderLogsTool extends McpToolsetSupport {

    public SqlVisualizerRenderLogsTool() {
        super("sqlv_render_logs");
    }

    /** 前缀缺省装配载体（Kotlin data class → record + 缺省重载）。 */
    public record LogPrefixDefaults(
        /** MyBatis SQL 行前缀。 */
        String sqlPrefix,
        /** MyBatis 参数行前缀。 */
        String paramPrefix,
        /** 混合前缀匹配（宽松次序）。 */
        boolean mixedPrefixMatching,
        /** Hibernate SQL 行前缀。 */
        String hibernateSqlPrefix,
        /** Hibernate 参数行前缀。 */
        String hibernateParamPrefix,
        /** JdbcTemplate 通道启用。 */
        boolean jdbcEnabled,
        /** 单次解析的最大文本长度。 */
        int maxParseLength,
        /** R10：ShardingSphere 双段日志解析开关（D3=A 默认开）。 */
        boolean shardingEnabled
    ) {
        /** Kotlin 默认参数等价重载（与插件设置内置默认一致）。 */
        public LogPrefixDefaults() {
            this("Preparing:", "Parameters:", true, "Hibernate:", "binding parameter", true, 20_000, true);
        }
    }

    @McpTool(name = "sqlv_render_logs", title = "Restore MyBatis/Hibernate/JdbcTemplate/ShardingSphere logs into executable SQL")
    @McpDescription(
        description = "Restore executable SQL from application log text (MyBatis 'Preparing:'/'Parameters:' pairs, "
            + "Hibernate 'Hibernate:' SQL with 'binding parameter' lines, Spring JdbcTemplate debug lines, "
            + "or ShardingSphere 5.x sql-show 'Logic SQL:'/'Actual SQL:' dual segments). "
            + "Pass the raw multi-line log text as 'logText'. Prefix overrides (sqlPrefix/paramPrefix/"
            + "hibernateSqlPrefix/hibernateParamPrefix) and 'mixedPrefixMatching' are optional and default to "
            + "the plugin's log-capture settings. Each restored statement is prefixed with a '-- SQL #N' "
            + "comment line carrying its source format. Deterministic restoration, not validation — pair with "
            + "sqlv_check_sql to assess write risk before executing. Read-only: never executes SQL."
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE
    )
    public McpToolTextResult sqlv_render_logs(
        String logText,
        String sqlPrefix,
        String paramPrefix,
        String hibernateSqlPrefix,
        String hibernateParamPrefix,
        Boolean mixedPrefixMatching
    ) {
        return audit("sqlv_render_logs",
            () -> renderLogs(logText, sqlPrefix, paramPrefix, hibernateSqlPrefix, hibernateParamPrefix,
                mixedPrefixMatching));
    }

    /** 纯函数核心：defaults 传 null 时从插件设置装配（服务未注册兜底内置默认）。 */
    public String renderLogs(
        String logText,
        String sqlPrefix,
        String paramPrefix,
        String hibernateSqlPrefix,
        String hibernateParamPrefix,
        Boolean mixedPrefixMatching
    ) {
        return renderLogs(logText, sqlPrefix, paramPrefix, hibernateSqlPrefix, hibernateParamPrefix,
            mixedPrefixMatching, null);
    }

    /** Kotlin 默认参数 {@code maskLiterals = null} 的显式重载。 */
    public String renderLogs(
        String logText,
        String sqlPrefix,
        String paramPrefix,
        String hibernateSqlPrefix,
        String hibernateParamPrefix,
        Boolean mixedPrefixMatching,
        LogPrefixDefaults defaults
    ) {
        return renderLogs(logText, sqlPrefix, paramPrefix, hibernateSqlPrefix, hibernateParamPrefix,
            mixedPrefixMatching, defaults, null);
    }

    /**
     * 全参形态（Kotlin defaults 参数显式入口；测试直调）。
     *
     * @param maskLiterals R60（T-LaneB D96/D97）：MCP 面脱敏——null 读设置 mcpMaskEnabled（默认关，审计册口径）
     */
    public String renderLogs(
        String logText,
        String sqlPrefix,
        String paramPrefix,
        String hibernateSqlPrefix,
        String hibernateParamPrefix,
        Boolean mixedPrefixMatching,
        LogPrefixDefaults defaults,
        Boolean maskLiterals
    ) {
        if (logText.isBlank()) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "logText is empty."));
        }
        LogPrefixDefaults effectiveDefaults = defaults;
        if (effectiveDefaults == null) {
            try {
                PluginSettings.State state = PluginSettings.getInstance().getState();
                effectiveDefaults = new LogPrefixDefaults(
                    state.getLogSqlPrefix(),
                    state.getLogParamPrefix(),
                    state.getLogMixedPrefix(),
                    state.getLogHibernateSqlPrefix(),
                    state.getLogHibernateParamPrefix(),
                    state.getLogJdbcEnabled(),
                    state.getLogMaxParseLength(),
                    state.getLogShardingSphereEnabled()
                );
            } catch (Throwable ignored) {
                effectiveDefaults = new LogPrefixDefaults();
            }
        }
        List<LogSqlEntry> entries = renderLogEntries(
            logText, sqlPrefix, paramPrefix, hibernateSqlPrefix, hibernateParamPrefix,
            mixedPrefixMatching, effectiveDefaults
        );
        if (entries.isEmpty()) {
            // 0 条是合法结果（日志里确实没有匹配对）——正常返回 + 指引，不走错误通道
            return "No log entries recognized. Expected MyBatis '" + effectiveDefaults.sqlPrefix() + "' / "
                + "'" + effectiveDefaults.paramPrefix() + "' pairs (or Hibernate / JdbcTemplate formats) with SQL and "
                + "parameters on adjacent lines. Check the prefixes or pass explicit overrides.";
        }
        // R60（T-LaneB D96/D97）：MCP 面脱敏——null 读设置（服务未注册兜底关），只打码输出文本
        final boolean maskOn;
        if (maskLiterals != null) {
            maskOn = maskLiterals;
        } else {
            boolean resolved;
            try {
                resolved = PluginSettings.getInstance().getState().getMcpMaskEnabled();
            } catch (Throwable ignored) {
                resolved = false;
            }
            maskOn = resolved;
        }
        StringBuilder out = new StringBuilder();
        out.append("Restored ").append(entries.size()).append(" statement(s):\n\n");
        for (int index = 0; index < entries.size(); index++) {
            LogSqlEntry entry = entries.get(index);
            out.append("-- SQL #").append(index + 1);
            out.append(" [").append(entry.source().name().toLowerCase(java.util.Locale.ROOT)).append("]");
            if (entry.sourceLine() > 0) {
                out.append(" log line ").append(entry.sourceLine());
            }
            if (entry.executionTimeMs() != null) {
                out.append(" (").append(entry.executionTimeMs()).append("ms)");
            }
            // T-StatementNaming（O-19）：语句前导注释名附注（无注释零差异）
            String statementName = cn.song.mybatisplus.visualizer.util.SqlStatementNamer.nameOf(entry.restoredSql());
            if (statementName != null) {
                out.append(" [").append(statementName).append("]");
            }
            out.append("\n").append(cn.song.mybatisplus.visualizer.util.DisplayMasker.maskDisplay(
                    entry.restoredSql(), maskOn));
            if (index < entries.size() - 1) {
                out.append("\n\n");
            }
        }
        return out.toString();
    }

    /**
     * 纯函数核心（普通 JUnit 直测）：装配四解析器 → 逐行喂 → 合并条目。
     * 覆盖参数空串/null 回落 defaults；解析器装配与 `LogToSqlPanel.buildParsers` 同参同构。
     * R10：第四格式 = ShardingSphere 双段日志（Logic SQL / Actual SQL，骨架按公开文档形态）。
     */
    public static List<LogSqlEntry> renderLogEntries(
        String logText,
        String sqlPrefix,
        String paramPrefix,
        String hibernateSqlPrefix,
        String hibernateParamPrefix,
        Boolean mixedPrefixMatching,
        LogPrefixDefaults defaults
    ) {
        MyBatisLogParser myBatis = new MyBatisLogParser(
            sqlPrefix == null || sqlPrefix.isBlank() ? defaults.sqlPrefix() : sqlPrefix,
            paramPrefix == null || paramPrefix.isBlank() ? defaults.paramPrefix() : paramPrefix,
            mixedPrefixMatching != null ? mixedPrefixMatching : defaults.mixedPrefixMatching(),
            defaults.maxParseLength()
        );
        HibernateLogParser hibernate = new HibernateLogParser(
            hibernateSqlPrefix == null || hibernateSqlPrefix.isBlank() ? defaults.hibernateSqlPrefix() : hibernateSqlPrefix,
            hibernateParamPrefix == null || hibernateParamPrefix.isBlank() ? defaults.hibernateParamPrefix() : hibernateParamPrefix,
            mixedPrefixMatching != null ? mixedPrefixMatching : defaults.mixedPrefixMatching(),
            defaults.maxParseLength()
        );
        JdbcTemplateLogParser jdbc = new JdbcTemplateLogParser(
            defaults.jdbcEnabled(),
            defaults.maxParseLength()
        );
        ShardingSphereLogParser sharding = new ShardingSphereLogParser(
            defaults.maxParseLength()
        );
        logText.lines().forEach(line -> {
            // F58（T-PerfSweep18 压轴）：同一行喂四解析器——线程 token 每行只提一次下传
            String threadToken = cn.song.mybatisplus.visualizer.log.LogThreadRouter.threadTokenOf(line);
            myBatis.feed(line, threadToken);
            hibernate.feed(line, threadToken);
            jdbc.feed(line, threadToken);
            if (defaults.shardingEnabled()) {
                sharding.feed(line, threadToken);
            }
        });
        myBatis.closePending();
        hibernate.closePending();
        jdbc.closePending();
        sharding.closePending();
        List<LogSqlEntry> out = new ArrayList<>(myBatis.takeEntries());
        out.addAll(hibernate.takeEntries());
        out.addAll(jdbc.takeEntries());
        out.addAll(sharding.takeEntries());
        return out;
    }
}
