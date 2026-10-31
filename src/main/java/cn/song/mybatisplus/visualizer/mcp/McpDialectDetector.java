// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpDialectDetector.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import java.util.AbstractMap.SimpleEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * T-McpToolset 方言解析（纯逻辑层）：JDBC URL 推断 + agent 显式指定优先。
 *
 * 返回值为插件方言注册表名（`DialectRegistry.dialects` 的
 * {@code cn.song.mybatisplus.visualizer.sql.Dialect} 的 name，
 * 与 Settings 下拉一致）。URL 关键字对齐插件已验证的真实样例（P-立项-05 方言矩阵）。
 */
public final class McpDialectDetector {

    private McpDialectDetector() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpDialectDetector INSTANCE = new McpDialectDetector();

    /** JDBC URL 子串 → 方言注册名（先匹配先赢，次序即优先级；注册名以 Dialect.name 为准）。 */
    private static final List<Map.Entry<String, String>> URL_PATTERNS = List.of(
        new SimpleEntry<>("jdbc:mysql", "MySQL"),
        new SimpleEntry<>("jdbc:mariadb", "MariaDB"),
        new SimpleEntry<>("jdbc:postgresql", "PostgreSQL"),
        new SimpleEntry<>("jdbc:oracle", "Oracle"),
        new SimpleEntry<>("jdbc:sqlserver", "SQL Server"),
        new SimpleEntry<>("jdbc:h2", "H2"),
        new SimpleEntry<>("jdbc:sqlite", "SQLite"),
        new SimpleEntry<>("jdbc:dm", "DM（达梦）"),
        new SimpleEntry<>("jdbc:kingbase", "KingBase（人大金仓）"),
        // T-DialectXcFamily（O-63）：opengauss 归 openGauss；T-GaussDbDialect（券 04）起 gaussdb 拆出
        // 独立方言（华为 GaussDB 官方驱动 gaussdbjdbc.jar / gsjdbc200.jar 的前缀）
        new SimpleEntry<>("jdbc:opengauss", "openGauss"),
        new SimpleEntry<>("jdbc:gaussdb", "GaussDB"),
        new SimpleEntry<>("jdbc:oceanbase", "OceanBase"),
        // T-XcDialects3（券 03）：瀚高 jdbc:highgo:；海量 jdbc:vastbase: 与 jdbc:dws:iam:
        //（cn.com.vastbase.Driver 错误串原文 "must start with jdbc:vastbase: or jdbc:dws:iam:"）
        new SimpleEntry<>("jdbc:highgo", "HighGo"),
        new SimpleEntry<>("jdbc:vastbase", "Vastbase"),
        new SimpleEntry<>("jdbc:dws", "Vastbase")
    );

    /** agent 显式方言名 → 注册名的精确别名表（小写；不 contains 防止 "oracle-no-such-x" 误归一）。 */
    private static final Map<String, String> ALIASES = buildAliases();

    private static Map<String, String> buildAliases() {
        // LinkedHashMap 保持声明次序（语义等价 Kotlin mapOf 的插入序）
        Map<String, String> m = new LinkedHashMap<>();
        m.put("mysql", "MySQL");
        m.put("mariadb", "MariaDB");
        m.put("postgresql", "PostgreSQL");
        m.put("postgres", "PostgreSQL");
        m.put("pgsql", "PostgreSQL");
        m.put("pg", "PostgreSQL");
        m.put("oracle", "Oracle");
        m.put("sqlserver", "SQL Server");
        m.put("sql server", "SQL Server");
        m.put("mssql", "SQL Server");
        m.put("h2", "H2");
        m.put("sqlite", "SQLite");
        m.put("dm", "DM（达梦）");
        m.put("dameng", "DM（达梦）");
        m.put("达梦", "DM（达梦）");
        m.put("kingbase", "KingBase（人大金仓）");
        m.put("kingbase8", "KingBase（人大金仓）");
        m.put("金仓", "KingBase（人大金仓）");
        m.put("人大金仓", "KingBase（人大金仓）");
        // T-DialectXcFamily（O-63）
        m.put("oceanbase", "OceanBase");
        m.put("opengauss", "openGauss");
        // T-GaussDbDialect（券 04）：gaussdb 从同归 openGauss 改为独立方言；中文别名按既有惯例补
        m.put("gaussdb", "GaussDB");
        m.put("高斯", "GaussDB");
        m.put("华为高斯", "GaussDB");
        // T-XcDialects3（券 03）：瀚高/海量。瀚高官方写「瀚高」，但用户手输常写「翰高」，
        // 两种写法都收（精确匹配互不冲突）；海量按厂商中文名收「海量」。
        m.put("highgo", "HighGo");
        m.put("瀚高", "HighGo");
        m.put("翰高", "HighGo");
        m.put("vastbase", "Vastbase");
        m.put("海量", "Vastbase");
        return m;
    }

    /** agent 显式方言名归一化（精确别名匹配；不可识别返回 null）。 */
    public static String normalize(String requested) {
        if (requested == null) {
            return null;
        }
        String trimmed = requested.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        return ALIASES.get(trimmed);
    }

    /** 从 JDBC URL 推断方言名；无 URL/不可识别返回 null。 */
    public static String guessFromUrl(String url) {
        String lower = url == null ? null : url.toLowerCase(Locale.ROOT).trim();
        if (lower == null || lower.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> pattern : URL_PATTERNS) {
            String prefix = pattern.getKey();
            if (lower.startsWith(prefix) || lower.contains(prefix)) {
                return pattern.getValue();
            }
        }
        return null;
    }

    /**
     * 方言解析：显式可识别名优先，否则 URL 推断，再否则默认 MySQL（DialectRegistry.byName 的默认口径）。
     * 注意：显式名<b>不可识别</b>时同样忽略并走 URL/默认——不可识别名由调用方前置
     * {@link #isRecognized} 校验报 INVALID_ARGUMENT（给 agent 列可选项），本函数不做静默兜底之外的任何处理。
     */
    public static String resolve(String requested, String url) {
        String normalized = normalize(requested);
        if (normalized != null) {
            return normalized;
        }
        String guessed = guessFromUrl(url);
        if (guessed != null) {
            return guessed;
        }
        return "MySQL";
    }

    /**
     * agent 指定的方言名是否可识别（不可识别时调用方报 INVALID_ARGUMENT 并列可选项）。
     *
     * <p>T-McpRenderDialectFix：null/空白一律视为「未指定」放行——平台 JSON 绑定把省略的可选
     * 参数落成 Kotlin 默认值 {@code ""}，此前 {@code ""} 被判不可识别 → 省略 dialect 必报
     * INVALID_ARGUMENT，与「omit to auto-detect」约定矛盾（2026-09-23 真机实测复现）；
     * {@link #resolve} 对空白本就有 URL 推断 + 默认 MySQL 兜底，行为不受影响。
     */
    public static boolean isRecognized(String requested) {
        if (requested == null) {
            return true;
        }
        String trimmed = requested.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        return normalize(trimmed) != null;
    }
}
