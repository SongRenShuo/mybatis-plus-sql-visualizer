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
import cn.song.mybatisplus.visualizer.sql.ExplainGate
import cn.song.mybatisplus.visualizer.sql.ExplainSupport
import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer
import cn.song.mybatisplus.visualizer.util.SqlStatementSplitter
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

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

    /** `EXPLAIN` 与真正查询之间可能出现的选项词（PG `EXPLAIN ANALYZE …` 形态）；词表只用于剥前缀。 */
    private val EXPLAIN_OPTION_WORDS = setOf(
        "analyze", "analyse", "verbose", "costs", "buffers", "format", "timing", "summary",
        "settings", "wal", "generic_plan", "memory", "serialize", "text", "json", "xml", "yaml",
        "true", "false", "on", "off",
    )

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

    /**
     * 统一渲染入口（薄转发，T5-3）。引擎只做「纯逻辑可达」的渲染：
     *  - `logs` / `check` / `perf` 直接转到既有门面；
     *  - `wrapper` / `xml` / `annotation` / `jpa` / `jdbcTemplate` 的「文本 → 模型」解析依赖 PSI，
     *    引擎 jar 不含 PSI，因此返回结构化 `unresolved`（由 wasm 侧 L1/L8 自带解析完成），
     *    **绝不伪造 SQL**（产品纪律：unresolved 不出 SQL）。
     *
     * @return 与既有门面同形 JSON；不支持的 kind 为 `{"ok":false,"kind":"unresolved","error":…}`。
     */
    @JvmStatic
    fun render(kind: String, text: String, dialect: String?): String {
        if (text.isBlank()) return errorJson("text is empty.")
        return when (kind.lowercase()) {
            "logs" -> renderLogs(text, dialect)
            "check" -> checkSql(text)
            "perf" -> perfAdvice(text, dialect ?: "MySQL")
            else -> "{\"ok\":false,\"kind\":\"unresolved\",\"error\":" +
                jsonStr("render kind '$kind' needs PSI, which the engine-only jar does not provide") + "}"
        }
    }

    /**
     * EXPLAIN 包装（纯逻辑，不连库、不执行，T5-3）。复用 [ExplainGate.planExplainSubmit]：
     * 只读首关键字白名单 + 幂等（已是 EXPLAIN 则原样）+ 方言模板，返回可提交的 EXPLAIN
     * 语句与输出格式；不可解释 / 方言不接入时返回结构化错误（kind = `syntax` / `unsupported`）。
     * 实际执行由持有连接的一侧完成——引擎 jar 不带 JDBC 驱动。
     *
     * @return `{"ok":true,"dialect":…,"explainSql":…,"format":"TABLE|TEXT|JSON|XML"}` 或结构化错误。
     */
    @JvmStatic
    fun explain(sql: String, dialect: String): String {
        if (sql.isBlank()) return errorJson("sql is empty.")
        val resolved = DialectRegistry.byName(dialect)
        return when (val plan = ExplainGate.planExplainSubmit(resolved, sql)) {
            is ExplainGate.SubmitPlan.Submit -> {
                val format = ExplainSupport.formatFor(resolved.name)?.name
                "{\"ok\":true,\"dialect\":" + jsonStr(resolved.name) +
                    ",\"explainSql\":" + jsonStr(plan.statement) +
                    ",\"format\":" + (format?.let { jsonStr(it) } ?: "null") + "}"
            }
            is ExplainGate.SubmitPlan.NotExplainable -> "{\"ok\":false,\"kind\":\"syntax\",\"error\":" +
                jsonStr(
                    "only read-only queries can be explained; first keyword: " +
                        (plan.firstKeyword ?: "<none>"),
                ) + "}"
            is ExplainGate.SubmitPlan.DialectUnsupported -> "{\"ok\":false,\"kind\":\"unsupported\",\"error\":" +
                jsonStr("EXPLAIN is not supported for dialect " + plan.dialectName) + "}"
        }
    }

    /**
     * 真连库 EXPLAIN 执行（T5-5）。
     *
     * 与纯逻辑的 [explain] 不同：本入口**持有连接并真的提交** EXPLAIN，由引擎 jar 自带的
     * PostgreSQL / SQL Server 驱动完成。安全边界有三层：
     *  1. **静态门**：只放行单条只读查询（首关键字 `SELECT`/`WITH`/`VALUES`/`TABLE`，词边界由
     *     [ExplainGate] 判定）；写/DDL/多语句直接结构化拒绝，**不建连接**。
     *  2. **连接只读**：PostgreSQL 显式 `SET default_transaction_read_only = on`；SQL Server 走
     *     `SET SHOWPLAN_XML ON`（只编译不执行），两者都不给写入留口子。
     *  3. **超时**：连接（connectTimeout/loginTimeout）与语句（queryTimeout）都按 `timeoutMs` 设。
     *
     * 已经带 `EXPLAIN` 前缀的输入会先剥掉前缀再按本方言重新包装——`EXPLAIN ANALYZE` 会**真的执行**
     * 查询，不能原样放行。
     *
     * @return 成功 `{"ok":true,"format":"json"|"xml","plan":"…"}`；
     *         失败 `{"ok":false,"kind":"timeout"|"permission"|"syntax"|"connection"|"other"|"rejected",
     *         "messageKey":"…","detail":"…"}`（detail 只有单条异常消息，不带栈）。
     */
    @JvmStatic
    fun explainExecute(url: String, user: String, password: String, sql: String, timeoutMs: Long): String {
        if (url.isBlank()) return explainFailure("other", "explain.error.connection", "jdbc url is empty")
        if (sql.isBlank()) return explainFailure("syntax", "explain.error.syntax", "sql is empty")
        val statements = SqlStatementSplitter.splitStripped(sql)
        if (statements.size != 1) {
            return explainFailure(
                "syntax",
                "explain.reject.multiStatement",
                "exactly one statement can be explained, got " + statements.size,
            )
        }
        val target = when {
            url.startsWith("jdbc:postgresql:", ignoreCase = true) -> ExplainTarget.POSTGRESQL
            url.startsWith("jdbc:sqlserver:", ignoreCase = true) -> ExplainTarget.SQL_SERVER
            else -> return explainFailure(
                "other",
                "explain.reject.dialect",
                "live EXPLAIN supports jdbc:postgresql: and jdbc:sqlserver: only",
            )
        }
        val query = unwrapExplain(statements[0])
        if (query.isBlank() || !ExplainGate.isExplainableSource(query)) {
            return explainFailure(
                "syntax",
                "explain.reject.writeStatement",
                "only read-only SELECT/WITH/VALUES/TABLE queries can be explained",
            )
        }
        val timeoutSeconds = ((timeoutMs.coerceAtLeast(1L) + 999L) / 1000L).toInt().coerceAtLeast(1)
        return try {
            when (target) {
                ExplainTarget.POSTGRESQL -> runPostgresExplain(url, user, password, query, timeoutSeconds)
                ExplainTarget.SQL_SERVER -> runSqlServerExplain(url, user, password, query, timeoutSeconds)
            }
        } catch (e: SQLException) {
            val kind = classifySqlError(e)
            explainFailure(
                kind,
                "explain.error." + kind,
                (e.sqlState ?: "-") + ": " + (e.message ?: e.javaClass.simpleName),
            )
        } catch (e: Throwable) {
            // 驱动类缺失/连接工厂失败等非 SQLException 路径：仍给结构化错误，不把栈交给插件。
            explainFailure("other", "explain.error.other", e.message ?: e.javaClass.simpleName)
        }
    }

    private enum class ExplainTarget { POSTGRESQL, SQL_SERVER }

    private fun runPostgresExplain(
        url: String,
        user: String,
        password: String,
        query: String,
        timeoutSeconds: Int,
    ): String {
        Class.forName("org.postgresql.Driver")
        val props = Properties().apply {
            setProperty("user", user)
            setProperty("password", password)
            // 秒；pgjdbc 的 connectTimeout/socketTimeout/loginTimeout 都是秒口径。
            setProperty("connectTimeout", timeoutSeconds.toString())
            setProperty("socketTimeout", timeoutSeconds.toString())
            setProperty("loginTimeout", timeoutSeconds.toString())
        }
        DriverManager.getConnection(url, props).use { conn ->
            runCatching { conn.isReadOnly = true }
            conn.createStatement().use { statement ->
                // setReadOnly 在 autoCommit 下不足以覆盖每条语句；会话级默认只读是硬闸。
                runCatching { statement.execute("SET default_transaction_read_only = on") }
                statement.queryTimeout = timeoutSeconds
                if (!statement.execute("EXPLAIN (FORMAT JSON) " + query)) {
                    return explainFailure("other", "explain.error.other", "EXPLAIN returned no result set")
                }
                statement.resultSet.use { rs ->
                    if (!rs.next()) {
                        return explainFailure("other", "explain.error.other", "EXPLAIN returned no rows")
                    }
                    return explainSuccess("json", rs.getString(1) ?: "")
                }
            }
        }
    }

    private fun runSqlServerExplain(
        url: String,
        user: String,
        password: String,
        query: String,
        timeoutSeconds: Int,
    ): String {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver")
        val props = Properties().apply {
            setProperty("user", user)
            setProperty("password", password)
            setProperty("loginTimeout", timeoutSeconds.toString())
        }
        DriverManager.getConnection(url, props).use { conn ->
            // SQL Server 驱动不支持真正的只读会话（setReadOnly 可能抛 Unsupported），
            // 由 SHOWPLAN 保证不执行；调用本身仍保留，驱动支持时就是第二道闸。
            runCatching { conn.isReadOnly = true }
            conn.createStatement().use { statement ->
                statement.queryTimeout = timeoutSeconds
                statement.execute("SET SHOWPLAN_XML ON")
                val plan = try {
                    if (!statement.execute(query)) {
                        return explainFailure("other", "explain.error.other", "SHOWPLAN returned no result set")
                    }
                    statement.resultSet.use { rs ->
                        if (!rs.next()) {
                            return explainFailure("other", "explain.error.other", "SHOWPLAN returned no rows")
                        }
                        rs.getString(1) ?: ""
                    }
                } finally {
                    // 即使查询报错也要复位会话开关；失败无补救（连接即将关闭）。
                    runCatching { statement.execute("SET SHOWPLAN_XML OFF") }
                }
                return explainSuccess("xml", plan)
            }
        }
    }

    /** `EXPLAIN` 前缀剥离：跳过 PG 的选项串（括号组与 ANALYZE/VERBOSE 等词），返回可包装的查询。 */
    private fun unwrapExplain(sql: String): String {
        if (!ExplainGate.startsWithExplainKeyword(sql)) return sql
        var i = 7
        while (i < sql.length) {
            val c = sql[i]
            val next = if (i + 1 < sql.length) sql[i + 1] else '\u0000'
            when {
                c.isWhitespace() -> i++
                c == '-' && next == '-' -> { while (i < sql.length && sql[i] != '\n') i++ }
                c == '/' && next == '*' -> {
                    val end = sql.indexOf("*/", i + 2)
                    i = if (end < 0) sql.length else end + 2
                }
                c == '(' -> i = skipBalancedParentheses(sql, i)
                c.isLetterOrDigit() || c == '_' -> {
                    var j = i
                    while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                    if (sql.substring(i, j).lowercase() !in EXPLAIN_OPTION_WORDS) return sql.substring(i)
                    i = j
                }
                else -> return sql.substring(i)
            }
        }
        return ""
    }

    private fun skipBalancedParentheses(sql: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < sql.length) {
            when (sql[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return sql.length
    }

    private fun classifySqlError(e: SQLException): String {
        val state = e.sqlState ?: ""
        val message = (e.message ?: "").lowercase()
        return when {
            state == "57014" || message.contains("timeout") || message.contains("timed out") ||
                message.contains("canceled") || message.contains("cancelled") -> "timeout"
            state == "42501" || message.contains("permission") || message.contains("denied") ||
                message.contains("not authorized") || message.contains("privilege") -> "permission"
            state.startsWith("08") || state.startsWith("28") || message.contains("connection") ||
                message.contains("refused") || message.contains("no route") ||
                message.contains("unknown host") || message.contains("authentication failed") -> "connection"
            state.startsWith("42") || message.contains("syntax") || message.contains("incorrect syntax") -> "syntax"
            else -> "other"
        }
    }

    private fun explainSuccess(format: String, plan: String): String =
        "{\"ok\":true,\"format\":" + jsonStr(format) + ",\"plan\":" + jsonStr(plan) + "}"

    private fun explainFailure(kind: String, messageKey: String, detail: String): String =
        "{\"ok\":false,\"kind\":" + jsonStr(kind) +
            ",\"messageKey\":" + jsonStr(messageKey) +
            ",\"detail\":" + jsonStr(detail) + "}"

    /**
     * Schema 元数据入口（T5-3）。引擎 jar 不提供 schema 自省（自带的两个驱动只用于
     * [explainExecute]），因此恒返回结构化 `connection` 错误（由持有连接的宿主补齐）；
     * 绝不返回空表假装成功。
     */
    @JvmStatic
    fun schema(table: String, dialect: String): String {
        if (table.isBlank()) return errorJson("table is empty.")
        return "{\"ok\":false,\"kind\":\"connection\",\"error\":" +
            jsonStr(
                "schema introspection for '" + table + "' (" + dialect +
                    ") needs a JDBC connection, which the engine-only jar does not provide",
            ) + "}"
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