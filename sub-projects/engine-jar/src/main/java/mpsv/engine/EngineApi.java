// T-SyncK2JMaster181 自 master sub-projects/engine-jar/src/main/kotlin/mpsv/engine/EngineApi.kt 移植
// （master 冻结 tip f06828c2d，462 行 Kotlin → Java）。
package mpsv.engine;

import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier;
import cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector;
import cn.song.mybatisplus.visualizer.log.HibernateLogParser;
import cn.song.mybatisplus.visualizer.log.JdbcTemplateLogParser;
import cn.song.mybatisplus.visualizer.log.LogSqlEntry;
import cn.song.mybatisplus.visualizer.log.LogThreadRouter;
import cn.song.mybatisplus.visualizer.log.MyBatisLogParser;
import cn.song.mybatisplus.visualizer.log.ShardingSphereLogParser;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.sql.ExplainGate;
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat;
import cn.song.mybatisplus.visualizer.sql.ExplainSupport;
import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.util.SqlStatementSplitter;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * T-OpenIDEAPort T0-0：引擎门面（**极薄**）。
 *
 * <p>三个入口 = 主仓既有纯逻辑的直接转发，入参出参一律 JSON 字符串（跨进程/跨语言边界只需字符串协议）：
 * <ul>
 *  <li>{@link #renderLogs}：MyBatis / Hibernate / JdbcTemplate / ShardingSphere 日志还原
 *      （装配逻辑逐字对齐主仓 {@code SqlVisualizerRenderLogsTool.renderLogEntries} 的纯函数核心，
 *      但**不引** mcp 外壳——它会拉 {@code PluginSettings} 平台服务）。</li>
 *  <li>{@link #checkSql}：语句数 + 逐条类型（QUERY/DML/DDL/UNKNOWN）+ 无界写七规则（fail-closed）。</li>
 *  <li>{@link #perfAdvice}：21 条性能规则静态分析。</li>
 * </ul>
 *
 * <p>JSON 手写（零新依赖）：字段值只可能是 SQL / 标识符 / 固定枚举名，转义按 RFC 8259 最小集处理
 * （{@code "}、{@code \}、控制字符）；不引 Gson/Jackson——给 OpenIDEA 侧增加的每个 jar 都要一份许可与体积账。
 *
 * <p><b>移植注记（k2j）</b>：master 侧为 {@code object EngineApi}（Kotlin 单例 + {@code @JvmStatic} 入口）。
 * Java 侧落成 {@code public final class} + 私有构造器 + 全 {@code static}——对外只有「静态门面」这一种形态，
 * 不保留 {@code INSTANCE}（master 的三处调用点都是 {@code EngineApi.xxx(...)}，没有「把单例当值捕获」的用法）。
 * {@code private const val} → {@code private static final}；
 * {@code when} 表达式 → {@code switch} 表达式 + {@code yield}（或 {@code when}/{@code is} 分支 → {@code instanceof} 模式判断，
 * 因为 Java 17 的 {@code switch} 模式匹配仍是 preview，本模块未开 {@code --enable-preview}）。
 */
public final class EngineApi {

    private EngineApi() {
    }

    // ---- 日志解析默认值（与主仓 LogPrefixDefaults 同值；引擎侧不做设置页，故常量固定） ----
    private static final String SQL_PREFIX = "Preparing:";
    private static final String PARAM_PREFIX = "Parameters:";
    private static final String HIBERNATE_SQL_PREFIX = "Hibernate:";
    private static final String HIBERNATE_PARAM_PREFIX = "binding parameter";
    private static final int MAX_PARSE_LENGTH = 20_000;

    /** {@code EXPLAIN} 与真正查询之间可能出现的选项词（PG {@code EXPLAIN ANALYZE …} 形态）；词表只用于剥前缀。 */
    private static final Set<String> EXPLAIN_OPTION_WORDS = Set.of(
            "analyze", "analyse", "verbose", "costs", "buffers", "format", "timing", "summary",
            "settings", "wal", "generic_plan", "memory", "serialize", "text", "json", "xml", "yaml",
            "true", "false", "on", "off");

    /**
     * 日志 → 还原 SQL。
     *
     * @param logText 日志原文（逐行喂四个解析器，与主仓同序）
     * @param dialect 目标方言名（仅回填输出，日志还原本身与方言无关——ANSI 日期字面量是全域通用形态）；可为 null
     * @return {@code {"ok":true,"dialect":…,"count":N,"statements":[{"sql":…,"source":…,"sourceLine":…,"executionTimeMs":…}]}}
     */
    public static String renderLogs(String logText, @Nullable String dialect) {
        if (logText.isBlank()) {
            return errorJson("logText is empty.");
        }
        List<LogSqlEntry> entries = renderLogEntries(logText);
        StringBuilder sb = new StringBuilder(256 + entries.size() * 160);
        sb.append("{\"ok\":true,\"dialect\":").append(jsonOrNull(dialect))
                .append(",\"count\":").append(entries.size())
                .append(",\"statements\":[");
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                sb.append(',');
            }
            LogSqlEntry entry = entries.get(index);
            Long executionTimeMs = entry.executionTimeMs();
            sb.append("{\"sql\":").append(jsonStr(entry.restoredSql()))
                    .append(",\"source\":").append(jsonStr(entry.source().name().toLowerCase(Locale.ROOT)))
                    .append(",\"sourceLine\":").append(entry.sourceLine())
                    .append(",\"executionTimeMs\":").append(executionTimeMs != null ? executionTimeMs.toString() : "null")
                    .append('}');
        }
        return sb.append("]}").toString();
    }

    /**
     * SQL 静态核查（不执行、不连库）。
     *
     * @return {@code {"ok":true,"statementCount":N,"statements":[{"index":1,"type":"DML"}],"dangerous":…,"findings":[…]}}；
     *         分类为 UNKNOWN / 解析失败一律按危险面处理（fail-closed 语义继承主仓）。
     */
    public static String checkSql(String sql) {
        if (sql.isBlank()) {
            return errorJson("sql is empty.");
        }
        List<String> statements = SqlStatementSplitter.splitStripped(sql);
        UnboundedWriteDetector.WriteRisk risk = UnboundedWriteDetector.detect(sql);
        List<UnboundedWriteDetector.Finding> findings = risk.findings();
        StringBuilder sb = new StringBuilder(128 + statements.size() * 48 + findings.size() * 96);
        sb.append("{\"ok\":true,\"statementCount\":").append(statements.size()).append(",\"statements\":[");
        for (int index = 0; index < statements.size(); index++) {
            if (index > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(index + 1)
                    .append(",\"type\":").append(jsonStr(SqlTypeClassifier.classify(statements.get(index)).name()))
                    .append('}');
        }
        sb.append("],\"dangerous\":").append(risk.isDangerous()).append(",\"findings\":[");
        for (int index = 0; index < findings.size(); index++) {
            if (index > 0) {
                sb.append(',');
            }
            UnboundedWriteDetector.Finding finding = findings.get(index);
            sb.append("{\"kind\":").append(jsonStr(finding.kind().name()))
                    .append(",\"detail\":").append(jsonStr(finding.detail()))
                    .append('}');
        }
        return sb.append("]}").toString();
    }

    /**
     * 性能建议（不执行、不连库）。
     *
     * @param dialect 方言名（{@code MySQL} / {@code PostgreSQL} / {@code Oracle} …；未知名按主仓
     *                {@link DialectRegistry#byName(String)} 回落 MySQL）
     * @return {@code {"ok":true,"dialect":…,"count":N,"warnings":[{"ruleId":…,"severity":…,"messageKey":…,"args":[…],"line":…}]}}。
     *         文案层（i18n 渲染）留给宿主——引擎只出结构化键与参数。
     */
    public static String perfAdvice(String sql, String dialect) {
        if (sql.isBlank()) {
            return errorJson("sql is empty.");
        }
        Dialect resolved = DialectRegistry.INSTANCE.byName(dialect);
        List<PerfWarning> warnings = SqlPerfAnalyzer.analyze(sql, resolved);
        StringBuilder sb = new StringBuilder(128 + warnings.size() * 160);
        sb.append("{\"ok\":true,\"dialect\":").append(jsonStr(resolved.getName()))
                .append(",\"count\":").append(warnings.size())
                .append(",\"warnings\":[");
        for (int index = 0; index < warnings.size(); index++) {
            if (index > 0) {
                sb.append(',');
            }
            PerfWarning warning = warnings.get(index);
            sb.append("{\"ruleId\":").append(jsonStr(warning.ruleId()))
                    .append(",\"severity\":").append(jsonStr(warning.severity().name()))
                    .append(",\"messageKey\":").append(jsonStr(warning.messageKey()))
                    .append(",\"args\":[");
            List<String> args = warning.messageArgs();
            for (int argIndex = 0; argIndex < args.size(); argIndex++) {
                if (argIndex > 0) {
                    sb.append(',');
                }
                sb.append(jsonStr(args.get(argIndex)));
            }
            sb.append("],\"line\":").append(warning.line()).append('}');
        }
        return sb.append("]}").toString();
    }

    /**
     * 统一渲染入口（薄转发，T5-3）。引擎只做「纯逻辑可达」的渲染：
     * <ul>
     *  <li>{@code logs} / {@code check} / {@code perf} 直接转到既有门面；</li>
     *  <li>{@code wrapper} / {@code xml} / {@code annotation} / {@code jpa} / {@code jdbcTemplate} 的
     *      「文本 → 模型」解析依赖 PSI，引擎 jar 不含 PSI，因此返回结构化 {@code unresolved}
     *      （由 wasm 侧 L1/L8 自带解析完成），<b>绝不伪造 SQL</b>（产品纪律：unresolved 不出 SQL）。</li>
     * </ul>
     *
     * @return 与既有门面同形 JSON；不支持的 kind 为 {@code {"ok":false,"kind":"unresolved","error":…}}。
     */
    public static String render(String kind, String text, @Nullable String dialect) {
        if (text.isBlank()) {
            return errorJson("text is empty.");
        }
        // Kotlin `when (kind.lowercase()) { "logs" -> …; else -> … }` → switch 表达式（穷尽由 default 覆盖）。
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "logs" -> renderLogs(text, dialect);
            case "check" -> checkSql(text);
            case "perf" -> perfAdvice(text, dialect != null ? dialect : "MySQL");
            default -> "{\"ok\":false,\"kind\":\"unresolved\",\"error\":"
                    + jsonStr("render kind '" + kind + "' needs PSI, which the engine-only jar does not provide") + "}";
        };
    }

    /**
     * EXPLAIN 包装（纯逻辑，不连库、不执行，T5-3）。复用 {@link ExplainGate#planExplainSubmit}：
     * 只读首关键字白名单 + 幂等（已是 EXPLAIN 则原样）+ 方言模板，返回可提交的 EXPLAIN
     * 语句与输出格式；不可解释 / 方言不接入时返回结构化错误（kind = {@code syntax} / {@code unsupported}）。
     * 实际执行由持有连接的一侧完成——引擎 jar 不带 JDBC 驱动。
     *
     * @return {@code {"ok":true,"dialect":…,"explainSql":…,"format":"TABLE|TEXT|JSON|XML"}} 或结构化错误。
     */
    public static String explain(String sql, String dialect) {
        if (sql.isBlank()) {
            return errorJson("sql is empty.");
        }
        Dialect resolved = DialectRegistry.INSTANCE.byName(dialect);
        // Kotlin `when (val plan = …) { is Submit -> …; is NotExplainable -> …; is DialectUnsupported -> … }`：
        // Java 17 的 switch 模式匹配仍是 preview（本模块未开 --enable-preview），落成 instanceof 模式判断链；
        // sealed 穷尽性由末尾不可达 throw 兜底（javac 需要显式出口）。
        ExplainGate.SubmitPlan plan = ExplainGate.planExplainSubmit(resolved, sql);
        if (plan instanceof ExplainGate.SubmitPlan.Submit submit) {
            ExplainOutputFormat formatEnum = ExplainSupport.formatFor(resolved.getName());
            String format = formatEnum != null ? formatEnum.name() : null;
            return "{\"ok\":true,\"dialect\":" + jsonStr(resolved.getName())
                    + ",\"explainSql\":" + jsonStr(submit.statement())
                    + ",\"format\":" + (format != null ? jsonStr(format) : "null") + "}";
        }
        if (plan instanceof ExplainGate.SubmitPlan.NotExplainable notExplainable) {
            String firstKeyword = notExplainable.firstKeyword();
            return "{\"ok\":false,\"kind\":\"syntax\",\"error\":"
                    + jsonStr("only read-only queries can be explained; first keyword: "
                    + (firstKeyword != null ? firstKeyword : "<none>")) + "}";
        }
        if (plan instanceof ExplainGate.SubmitPlan.DialectUnsupported unsupported) {
            return "{\"ok\":false,\"kind\":\"unsupported\",\"error\":"
                    + jsonStr("EXPLAIN is not supported for dialect " + unsupported.dialectName()) + "}";
        }
        throw new IllegalStateException("unreachable SubmitPlan: " + plan);
    }

    /**
     * 真连库 EXPLAIN 执行（T5-5）。
     *
     * <p>与纯逻辑的 {@link #explain} 不同：本入口<b>持有连接并真的提交</b> EXPLAIN，由引擎 jar 自带的
     * PostgreSQL / SQL Server 驱动完成。安全边界有三层：
     * <ol>
     *  <li><b>静态门</b>：只放行单条只读查询（首关键字 {@code SELECT}/{@code WITH}/{@code VALUES}/{@code TABLE}，
     *      词边界由 {@link ExplainGate} 判定）；写/DDL/多语句直接结构化拒绝，<b>不建连接</b>。</li>
     *  <li><b>连接只读</b>：PostgreSQL 显式 {@code SET default_transaction_read_only = on}；SQL Server 走
     *      {@code SET SHOWPLAN_XML ON}（只编译不执行），两者都不给写入留口子。</li>
     *  <li><b>超时</b>：连接（connectTimeout/loginTimeout）与语句（queryTimeout）都按 {@code timeoutMs} 设。</li>
     * </ol>
     *
     * <p>已经带 {@code EXPLAIN} 前缀的输入会先剥掉前缀再按本方言重新包装——{@code EXPLAIN ANALYZE} 会<b>真的执行</b>
     * 查询，不能原样放行。
     *
     * @return 成功 {@code {"ok":true,"format":"json"|"xml","plan":"…"}}；
     *         失败 {@code {"ok":false,"kind":"timeout"|"permission"|"syntax"|"connection"|"other"|"rejected",
     *         "messageKey":"…","detail":"…"}}（detail 只有单条异常消息，不带栈）。
     */
    public static String explainExecute(String url, String user, String password, String sql, long timeoutMs) {
        if (url.isBlank()) {
            return explainFailure("other", "explain.error.connection", "jdbc url is empty");
        }
        if (sql.isBlank()) {
            return explainFailure("syntax", "explain.error.syntax", "sql is empty");
        }
        List<String> statements = SqlStatementSplitter.splitStripped(sql);
        if (statements.size() != 1) {
            return explainFailure(
                    "syntax",
                    "explain.reject.multiStatement",
                    "exactly one statement can be explained, got " + statements.size());
        }
        // Kotlin `when { url.startsWith("jdbc:postgresql:", ignoreCase = true) -> …; else -> return … }`
        // （具名实参 ignoreCase 无 Java 等价重载 → regionMatches 的比较区间形态）。
        ExplainTarget target;
        if (url.regionMatches(true, 0, "jdbc:postgresql:", 0, "jdbc:postgresql:".length())) {
            target = ExplainTarget.POSTGRESQL;
        } else if (url.regionMatches(true, 0, "jdbc:sqlserver:", 0, "jdbc:sqlserver:".length())) {
            target = ExplainTarget.SQL_SERVER;
        } else {
            return explainFailure(
                    "other",
                    "explain.reject.dialect",
                    "live EXPLAIN supports jdbc:postgresql: and jdbc:sqlserver: only");
        }
        String query = unwrapExplain(statements.get(0));
        if (query.isBlank() || !ExplainGate.isExplainableSource(query)) {
            return explainFailure(
                    "syntax",
                    "explain.reject.writeStatement",
                    "only read-only SELECT/WITH/VALUES/TABLE queries can be explained");
        }
        // Kotlin `((timeoutMs.coerceAtLeast(1L) + 999L) / 1000L).toInt().coerceAtLeast(1)`
        int timeoutSeconds = Math.max((int) ((Math.max(timeoutMs, 1L) + 999L) / 1000L), 1);
        try {
            // Kotlin `when (target) { … }` 作表达式 → switch 表达式（枚举穷尽，不需要 default）。
            return switch (target) {
                case POSTGRESQL -> runPostgresExplain(url, user, password, query, timeoutSeconds);
                case SQL_SERVER -> runSqlServerExplain(url, user, password, query, timeoutSeconds);
            };
        } catch (SQLException e) {
            String kind = classifySqlError(e);
            return explainFailure(
                    kind,
                    "explain.error." + kind,
                    (e.getSQLState() != null ? e.getSQLState() : "-") + ": "
                            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } catch (Throwable e) {
            // 驱动类缺失/连接工厂失败等非 SQLException 路径：仍给结构化错误，不把栈交给插件。
            return explainFailure("other", "explain.error.other",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private enum ExplainTarget {
        POSTGRESQL,
        SQL_SERVER
    }

    private static String runPostgresExplain(
            String url,
            String user,
            String password,
            String query,
            int timeoutSeconds) throws Exception {
        Class.forName("org.postgresql.Driver");
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        // 秒；pgjdbc 的 connectTimeout/socketTimeout/loginTimeout 都是秒口径。
        props.setProperty("connectTimeout", Integer.toString(timeoutSeconds));
        props.setProperty("socketTimeout", Integer.toString(timeoutSeconds));
        props.setProperty("loginTimeout", Integer.toString(timeoutSeconds));
        try (Connection conn = DriverManager.getConnection(url, props)) {
            // Kotlin `runCatching { conn.isReadOnly = true }` → try/catch（吞异常语义不变）。
            try {
                conn.setReadOnly(true);
            } catch (Throwable ignored) {
                // 驱动不支持则忽略；破坏只读闸的是下面的会话级开关。
            }
            try (Statement statement = conn.createStatement()) {
                // setReadOnly 在 autoCommit 下不足以覆盖每条语句；会话级默认只读是硬闸。
                try {
                    statement.execute("SET default_transaction_read_only = on");
                } catch (Throwable ignored) {
                    // 同 master runCatching：失败不阻断（权限不足时由 EXPLAIN 自身报错）。
                }
                statement.setQueryTimeout(timeoutSeconds);
                if (!statement.execute("EXPLAIN (FORMAT JSON) " + query)) {
                    return explainFailure("other", "explain.error.other", "EXPLAIN returned no result set");
                }
                try (ResultSet rs = statement.getResultSet()) {
                    if (!rs.next()) {
                        return explainFailure("other", "explain.error.other", "EXPLAIN returned no rows");
                    }
                    String plan = rs.getString(1);
                    return explainSuccess("json", plan != null ? plan : "");
                }
            }
        }
    }

    private static String runSqlServerExplain(
            String url,
            String user,
            String password,
            String query,
            int timeoutSeconds) throws Exception {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("loginTimeout", Integer.toString(timeoutSeconds));
        try (Connection conn = DriverManager.getConnection(url, props)) {
            // SQL Server 驱动不支持真正的只读会话（setReadOnly 可能抛 Unsupported），
            // 由 SHOWPLAN 保证不执行；调用本身仍保留，驱动支持时就是第二道闸。
            try {
                conn.setReadOnly(true);
            } catch (Throwable ignored) {
                // 同 master runCatching。
            }
            try (Statement statement = conn.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.execute("SET SHOWPLAN_XML ON");
                String plan;
                try {
                    if (!statement.execute(query)) {
                        return explainFailure("other", "explain.error.other", "SHOWPLAN returned no result set");
                    }
                    try (ResultSet rs = statement.getResultSet()) {
                        if (!rs.next()) {
                            return explainFailure("other", "explain.error.other", "SHOWPLAN returned no rows");
                        }
                        String text = rs.getString(1);
                        plan = text != null ? text : "";
                    }
                } finally {
                    // 即使查询报错也要复位会话开关；失败无补救（连接即将关闭）。
                    try {
                        statement.execute("SET SHOWPLAN_XML OFF");
                    } catch (Throwable ignored) {
                        // 同 master runCatching：连接即将关闭，复位失败无补救。
                    }
                }
                return explainSuccess("xml", plan);
            }
        }
    }

    /** {@code EXPLAIN} 前缀剥离：跳过 PG 的选项串（括号组与 ANALYZE/VERBOSE 等词），返回可包装的查询。 */
    private static String unwrapExplain(String sql) {
        if (!ExplainGate.startsWithExplainKeyword(sql)) {
            return sql;
        }
        int i = 7;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\u0000';
            if (isKotlinWhitespace(c)) {
                i++;
            } else if (c == '-' && next == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
            } else if (c == '(') {
                i = skipBalancedParentheses(sql, i);
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                int j = i;
                while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                    j++;
                }
                if (!EXPLAIN_OPTION_WORDS.contains(sql.substring(i, j).toLowerCase(Locale.ROOT))) {
                    return sql.substring(i);
                }
                i = j;
            } else {
                return sql.substring(i);
            }
        }
        return "";
    }

    /**
     * Kotlin {@code Char.isWhitespace()} 的等价判定（{@code Character.isWhitespace || Character.isSpaceChar}）——
     * JDK 的 {@code Character.isWhitespace} 不含 NBSP 等 SPACE_SEPARATOR，单独用会与 master 剥前缀口径差一档。
     */
    private static boolean isKotlinWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static int skipBalancedParentheses(String sql, int from) {
        int depth = 0;
        int i = from;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return sql.length();
    }

    private static String classifySqlError(SQLException e) {
        String state = e.getSQLState() != null ? e.getSQLState() : "";
        String message = (e.getMessage() != null ? e.getMessage() : "").toLowerCase(Locale.ROOT);
        if (state.equals("57014") || message.contains("timeout") || message.contains("timed out")
                || message.contains("canceled") || message.contains("cancelled")) {
            return "timeout";
        }
        if (state.equals("42501") || message.contains("permission") || message.contains("denied")
                || message.contains("not authorized") || message.contains("privilege")) {
            return "permission";
        }
        if (state.startsWith("08") || state.startsWith("28") || message.contains("connection")
                || message.contains("refused") || message.contains("no route")
                || message.contains("unknown host") || message.contains("authentication failed")) {
            return "connection";
        }
        if (state.startsWith("42") || message.contains("syntax") || message.contains("incorrect syntax")) {
            return "syntax";
        }
        return "other";
    }

    private static String explainSuccess(String format, String plan) {
        return "{\"ok\":true,\"format\":" + jsonStr(format) + ",\"plan\":" + jsonStr(plan) + "}";
    }

    private static String explainFailure(String kind, String messageKey, String detail) {
        return "{\"ok\":false,\"kind\":" + jsonStr(kind)
                + ",\"messageKey\":" + jsonStr(messageKey)
                + ",\"detail\":" + jsonStr(detail) + "}";
    }

    /**
     * Schema 元数据入口（T5-3）。引擎 jar 不提供 schema 自省（自带的两个驱动只用于
     * {@link #explainExecute}），因此恒返回结构化 {@code connection} 错误（由持有连接的宿主补齐）；
     * 绝不返回空表假装成功。
     */
    public static String schema(String table, String dialect) {
        if (table.isBlank()) {
            return errorJson("table is empty.");
        }
        return "{\"ok\":false,\"kind\":\"connection\",\"error\":"
                + jsonStr("schema introspection for '" + table + "' (" + dialect
                + ") needs a JDBC connection, which the engine-only jar does not provide") + "}";
    }

    /** 四解析器装配（逐字对齐主仓 {@code SqlVisualizerRenderLogsTool.renderLogEntries} 的默认装配）。 */
    private static List<LogSqlEntry> renderLogEntries(String logText) {
        // Kotlin 具名实参 → Java 位置实参（构造器参数序与 master 声明序一致）。
        MyBatisLogParser myBatis = new MyBatisLogParser(
                SQL_PREFIX,
                PARAM_PREFIX,
                true,
                MAX_PARSE_LENGTH);
        HibernateLogParser hibernate = new HibernateLogParser(
                HIBERNATE_SQL_PREFIX,
                HIBERNATE_PARAM_PREFIX,
                true,
                MAX_PARSE_LENGTH);
        JdbcTemplateLogParser jdbc = new JdbcTemplateLogParser(true, MAX_PARSE_LENGTH);
        ShardingSphereLogParser sharding = new ShardingSphereLogParser(MAX_PARSE_LENGTH);
        // Kotlin `logText.lineSequence().forEach { … }` → String.lines()（同口径的 \r\n|\n|\r 切分）。
        for (String line : logText.lines().toList()) {
            String threadToken = LogThreadRouter.threadTokenOf(line);
            myBatis.feed(line, threadToken);
            hibernate.feed(line, threadToken);
            jdbc.feed(line, threadToken);
            sharding.feed(line, threadToken);
        }
        myBatis.closePending();
        hibernate.closePending();
        jdbc.closePending();
        sharding.closePending();
        List<LogSqlEntry> all = new ArrayList<>();
        all.addAll(myBatis.takeEntries());
        all.addAll(hibernate.takeEntries());
        all.addAll(jdbc.takeEntries());
        all.addAll(sharding.takeEntries());
        return all;
    }

    // ---- 手写 JSON 序列化（RFC 8259 最小转义集） ----

    private static String errorJson(String message) {
        return "{\"ok\":false,\"error\":" + jsonStr(message) + "}";
    }

    private static String jsonOrNull(@Nullable String value) {
        return value == null ? "null" : jsonStr(value);
    }

    private static String jsonStr(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\u000C' -> sb.append("\\f");
                default -> {
                    if (ch < ' ') {
                        sb.append("\\u").append(String.format("%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
