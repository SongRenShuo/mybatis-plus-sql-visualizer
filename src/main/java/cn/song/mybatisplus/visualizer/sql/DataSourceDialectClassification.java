// T-SyncK2JMaster175 afd3d09 增量：自 master sql/DataSourceUrlProbe.kt 文件内顶层 internal object
// DataSourceDialectClassification 分家移植（k2j 顶层 object 分家惯例，同 UrlSubprotocolDialects）
package cn.song.mybatisplus.visualizer.sql;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * T-DialectInferFix：数据源「能不能当 SQL 方言候选」的分类（纯函数，表驱动可单测）。
 *
 * <p>背景（2026-10-29 真机报障）：{@code DataSourceManager.getDataSources()} 返回的是<b>全部</b>数据源，
 * 其中包含 Redis/Mongo 这类根本不是 SQL 执行目标的数据源。旧实现把这类数据源的方言记成 null
 * 投进候选，而 {@link DialectDecisionEngine} 的「未知候选不得宣称共识」是<b>有意语义</b>
 * （{@code IdeaSqlDialectDecisionTest}「known and unknown candidates cannot be promoted to false consensus」
 * 专门锁定），于是「PostgreSQL + Redis」被判 AMBIGUOUS → 整个项目回落 MySQL 兼容兜底，
 * 连 {@code signal} 这种 PG 合法列名都被 MySQL 解析器标红。</p>
 *
 * <p>判据（强→弱，只用平台事实，不猜）：
 * <ol>
 *  <li>平台 query language id 能映射到注册方言 → {@link Kind#SQL_KNOWN}
 *      （platform 对该数据源认的方言，比驱动 URL 子协议更权威）；</li>
 *  <li>URL 子协议能映射到注册方言 → {@link Kind#SQL_KNOWN}；</li>
 *  <li>language id / DBMS 显示名命中已知非 SQL 家族 → {@link Kind#NON_SQL}（<b>不参与投票</b>）；</li>
 *  <li>其余 → {@link Kind#SQL_UNKNOWN}（仍是候选：可能是插件未收录的 SQL 库，保持 fail-closed）。</li>
 * </ol></p>
 */
public final class DataSourceDialectClassification {

    private DataSourceDialectClassification() {}

    public enum Kind { SQL_KNOWN, SQL_UNKNOWN, NON_SQL }

    public record Result(Kind kind, @Nullable String dialectName) {

        /** 非 SQL 家族的展示名（排障日志用；SQL 家族为空）。 */
        public boolean isSqlTarget() {
            return kind != Kind.NON_SQL;
        }
    }

    /**
     * 非 SQL 家族键（小写、去空格后<b>精确</b>比对，不做前缀匹配——前缀会把 Redshift 之外的
     * 名字误伤，例如任何以 "mongo" 开头的新 SQL 方言都会被误判）。
     */
    private static final Set<String> NON_SQL_KEYS = Set.of(
            "redis", "mongo", "mongodb", "mongojs", "mongo js",
            "cassandra", "apachecassandra",
            "couchbase", "dynamo", "dynamodb", "amazondynamodb", "awsdynamodb",
            "elastic", "elasticsearch", "hbase", "neo4j", "influxdb");

    /** DBMS 显示名 → 注册方言名（平台显示名与注册名不同的少数形态）。 */
    private static final Map<String, String> DBMS_ALIASES = Map.of(
            "microsoftsqlserver", "SQL Server",
            "mssqlserver", "SQL Server",
            "sqlserver", "SQL Server",
            "postgresql", "PostgreSQL",
            "mysql", "MySQL",
            "mariadb", "MariaDB",
            "oracle", "Oracle");

    public static Result classify(@Nullable String queryLanguageId, @Nullable String dbmsDisplayName,
                                  @Nullable String url) {
        String byPlugin = IdeaSqlContextBridge.pluginDialectName(queryLanguageId);
        if (byPlugin != null) {
            return new Result(Kind.SQL_KNOWN, byPlugin);
        }
        String byUrl = UrlSubprotocolDialects.dialectName(url);
        if (byUrl != null) {
            return new Result(Kind.SQL_KNOWN, byUrl);
        }
        if (isNonSqlKey(queryLanguageId) || isNonSqlKey(dbmsDisplayName) || isNonSqlUrl(url)) {
            return new Result(Kind.NON_SQL, null);
        }
        String dbms = dbmsDisplayName != null ? dbmsDisplayName.trim() : "";
        if (!dbms.isEmpty()) {
            String alias = DBMS_ALIASES.get(dbms.toLowerCase(Locale.ROOT).replace(" ", ""));
            if (alias != null) {
                return new Result(Kind.SQL_KNOWN, alias);
            }
            for (Dialect d : DialectRegistry.INSTANCE.getDialects()) {
                if (d.getName().equalsIgnoreCase(dbms)) {
                    return new Result(Kind.SQL_KNOWN, d.getName());
                }
            }
        }
        return new Result(Kind.SQL_UNKNOWN, null);
    }

    private static boolean isNonSqlKey(@Nullable String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        return !normalized.isEmpty() && NON_SQL_KEYS.contains(normalized);
    }

    private static boolean isNonSqlUrl(@Nullable String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, "jdbc:", 0, 5)) return false;
        String rest = trimmed.substring(5);
        int colon = rest.indexOf(':');
        return isNonSqlKey(colon >= 0 ? rest.substring(0, colon) : rest);
    }
}
