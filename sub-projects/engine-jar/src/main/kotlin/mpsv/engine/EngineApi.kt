package mpsv.engine

import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector
import cn.song.mybatisplus.visualizer.log.HibernateLogParser
import cn.song.mybatisplus.visualizer.log.JdbcTemplateLogParser
import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.LogThreadRouter
import cn.song.mybatisplus.visualizer.log.MyBatisLogParser
import cn.song.mybatisplus.visualizer.log.ShardingSphereLogParser
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer
import cn.song.mybatisplus.visualizer.util.SqlStatementSplitter

/**
 * T-OpenIDEAPort T0-0：引擎门面（**极薄**）。
 *
 * 三个入口 = 主仓既有纯逻辑的直接转发，入参出参一律 JSON 字符串（跨进程/跨语言边界只需字符串协议）：
 *  - [renderLogs]：MyBatis / Hibernate / JdbcTemplate / ShardingSphere 日志还原
 *    （装配逻辑逐字对齐主仓 `SqlVisualizerRenderLogsTool.renderLogEntries` 的纯函数核心，
 *    但**不引** mcp 外壳——它会拉 `PluginSettings` 平台服务）。
 *  - [checkSql]：语句数 + 逐条类型（QUERY/DML/DDL/UNKNOWN）+ 无界写七规则（fail-closed）。
 *  - [perfAdvice]：21 条性能规则静态分析。
 *
 * JSON 手写（零新依赖）：字段值只可能是 SQL / 标识符 / 固定枚举名，转义按 RFC 8259 最小集处理
 * （`"`、`\`、控制字符）；不引 Gson/Jackson——给 OpenIDEA 侧增加的每个 jar 都要一份许可与体积账。
 */
object EngineApi {

    // ---- 日志解析默认值（与主仓 LogPrefixDefaults 同值；引擎侧不做设置页，故常量固定） ----
    private const val SQL_PREFIX = "Preparing:"
    private const val PARAM_PREFIX = "Parameters:"
    private const val HIBERNATE_SQL_PREFIX = "Hibernate:"
    private const val HIBERNATE_PARAM_PREFIX = "binding parameter"
    private const val MAX_PARSE_LENGTH = 20_000

    /**
     * 日志 → 还原 SQL。
     *
     * @param logText 日志原文（逐行喂四个解析器，与主仓同序）
     * @param dialect 目标方言名（仅回填输出，日志还原本身与方言无关——ANSI 日期字面量是全域通用形态）
     * @return `{"ok":true,"dialect":…,"count":N,"statements":[{"sql":…,"source":…,"sourceLine":…,"executionTimeMs":…}]}`
     */
    @JvmStatic
    fun renderLogs(logText: String, dialect: String?): String {
        if (logText.isBlank()) return errorJson("logText is empty.")
        val entries = renderLogEntries(logText)
        val sb = StringBuilder(256 + entries.size * 160)
        sb.append("{\"ok\":true,\"dialect\":").append(jsonOrNull(dialect))
            .append(",\"count\":").append(entries.size)
            .append(",\"statements\":[")
        entries.forEachIndexed { index, entry ->
            if (index > 0) sb.append(',')
            sb.append("{\"sql\":").append(jsonStr(entry.restoredSql))
                .append(",\"source\":").append(jsonStr(entry.source.name.lowercase()))
                .append(",\"sourceLine\":").append(entry.sourceLine)
                .append(",\"executionTimeMs\":").append(entry.executionTimeMs?.toString() ?: "null")
                .append('}')
        }
        return sb.append("]}").toString()
    }

    /**
     * SQL 静态核查（不执行、不连库）。
     *
     * @return `{"ok":true,"statementCount":N,"statements":[{"index":1,"type":"DML"}],"dangerous":…,"findings":[…]}`；
     *         分类为 UNKNOWN / 解析失败一律按危险面处理（fail-closed 语义继承主仓）。
     */
    @JvmStatic
    fun checkSql(sql: String): String {
        if (sql.isBlank()) return errorJson("sql is empty.")
        val statements = SqlStatementSplitter.splitStripped(sql)
        val risk = UnboundedWriteDetector.detect(sql)
        val sb = StringBuilder(128 + statements.size * 48 + risk.findings.size * 96)
        sb.append("{\"ok\":true,\"statementCount\":").append(statements.size).append(",\"statements\":[")
        statements.forEachIndexed { index, statement ->
            if (index > 0) sb.append(',')
            sb.append("{\"index\":").append(index + 1)
                .append(",\"type\":").append(jsonStr(SqlTypeClassifier.classify(statement).name))
                .append('}')
        }
        sb.append("],\"dangerous\":").append(risk.dangerous).append(",\"findings\":[")
        risk.findings.forEachIndexed { index, finding ->
            if (index > 0) sb.append(',')
            sb.append("{\"kind\":").append(jsonStr(finding.kind.name))
                .append(",\"detail\":").append(jsonStr(finding.detail))
                .append('}')
        }
        return sb.append("]}").toString()
    }

    /**
     * 性能建议（不执行、不连库）。
     *
     * @param dialect 方言名（`MySQL` / `PostgreSQL` / `Oracle` …；未知名按主仓 [DialectRegistry.byName] 回落 MySQL）
     * @return `{"ok":true,"dialect":…,"count":N,"warnings":[{"ruleId":…,"severity":…,"messageKey":…,"args":[…],"line":…}]}`。
     *         文案层（i18n 渲染）留给宿主——引擎只出结构化键与参数。
     */
    @JvmStatic
    fun perfAdvice(sql: String, dialect: String): String {
        if (sql.isBlank()) return errorJson("sql is empty.")
        val resolved = DialectRegistry.byName(dialect)
        val warnings = SqlPerfAnalyzer.analyze(sql, resolved)
        val sb = StringBuilder(128 + warnings.size * 160)
        sb.append("{\"ok\":true,\"dialect\":").append(jsonStr(resolved.name))
            .append(",\"count\":").append(warnings.size)
            .append(",\"warnings\":[")
        warnings.forEachIndexed { index, warning ->
            if (index > 0) sb.append(',')
            sb.append("{\"ruleId\":").append(jsonStr(warning.ruleId))
                .append(",\"severity\":").append(jsonStr(warning.severity.name))
                .append(",\"messageKey\":").append(jsonStr(warning.messageKey))
                .append(",\"args\":[")
            warning.messageArgs.forEachIndexed { argIndex, arg ->
                if (argIndex > 0) sb.append(',')
                sb.append(jsonStr(arg))
            }
            sb.append("],\"line\":").append(warning.line).append('}')
        }
        return sb.append("]}").toString()
    }

    /** 四解析器装配（逐字对齐主仓 `SqlVisualizerRenderLogsTool.renderLogEntries` 的默认装配）。 */
    private fun renderLogEntries(logText: String): List<LogSqlEntry> {
        val myBatis = MyBatisLogParser(
            sqlPrefix = SQL_PREFIX,
            paramPrefix = PARAM_PREFIX,
            mixedPrefix = true,
            maxParseLength = MAX_PARSE_LENGTH,
        )
        val hibernate = HibernateLogParser(
            sqlPrefix = HIBERNATE_SQL_PREFIX,
            paramPrefix = HIBERNATE_PARAM_PREFIX,
            mixedPrefix = true,
            maxParseLength = MAX_PARSE_LENGTH,
        )
        val jdbc = JdbcTemplateLogParser(enabled = true, maxParseLength = MAX_PARSE_LENGTH)
        val sharding = ShardingSphereLogParser(maxParseLength = MAX_PARSE_LENGTH)
        logText.lineSequence().forEach { line ->
            val threadToken = LogThreadRouter.threadTokenOf(line)
            myBatis.feed(line, threadToken)
            hibernate.feed(line, threadToken)
            jdbc.feed(line, threadToken)
            sharding.feed(line, threadToken)
        }
        myBatis.closePending()
        hibernate.closePending()
        jdbc.closePending()
        sharding.closePending()
        return myBatis.takeEntries() + hibernate.takeEntries() + jdbc.takeEntries() + sharding.takeEntries()
    }

    // ---- 手写 JSON 序列化（RFC 8259 最小转义集） ----

    private fun errorJson(message: String): String = "{\"ok\":false,\"error\":" + jsonStr(message) + "}"

    private fun jsonOrNull(value: String?): String = if (value == null) "null" else jsonStr(value)

    private fun jsonStr(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u").append(String.format("%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}