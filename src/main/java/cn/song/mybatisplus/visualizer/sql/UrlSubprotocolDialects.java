// T-SyncK2JMaster309 自 master sql/DialectInference.kt 移植
// T-SyncK2JMaster187 c07b8e3 增量：T-DialectXcFamily（O-63）补 oceanbase/opengauss/gaussdb 三前缀
// （openGauss 一 object 两前缀，D2=A 定案）
package cn.song.mybatisplus.visualizer.sql;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * T-DialectAutoInfer：JDBC URL 子协议 → 渲染方言名（{@link DialectRegistry} 命名空间）映射。
 *
 * <p>与 {@code cn.song.mybatisplus.visualizer.log.agent.JdbcUrlDialects} 的关系：同键空间（子协议）、
 * 不同值命名空间——那边是面板角标<b>事实展示名</b>（如「HSQL」），这边必须精确等于
 * {@link DialectRegistry} 各方言的 {@link Dialect#getName()}（如 DM/KingBase 为中缀名），照搬会
 * {@code byName} 失配静默回退 MySQL。未收录子协议 → null（调用方回退，不猜）。
 *
 * <p>纯函数、无状态；键统一小写。
 */
public final class UrlSubprotocolDialects {

    private UrlSubprotocolDialects() {}

    /** 子协议 → 方言名。未列出=不支持推断（如 derby/clickhouse 等插件无对应渲染方言）。 */
    private static final Map<String, String> SUBPROTOCOL_TO_DIALECT = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("mysql", "MySQL"),
            Map.entry("mariadb", "MariaDB"),
            Map.entry("postgresql", "PostgreSQL"),
            Map.entry("oracle", "Oracle"),
            Map.entry("sqlserver", "SQL Server"),
            Map.entry("h2", "H2"),
            Map.entry("sqlite", "SQLite"),
            Map.entry("dm", "DM（达梦）"),
            Map.entry("kingbase8", "KingBase（人大金仓）"),
            Map.entry("kingbase", "KingBase（人大金仓）"),
            // T-DialectXcFamily（O-63）：oceanbase（OceanBase Connector/J 官方《Database URL》）与
            // opengauss（openGauss 官方《Connecting to a Database》）。
            // T-GaussDbDialect（券 04）：gaussdb 从「同归 openGauss（D2=A）」改为归独立的 GaussDB 方言
            // ——华为 GaussDB 官方《获取驱动 jar 与驱动表》实锤 `gaussdbjdbc.jar` / `gsjdbc200.jar` 的
            // URL 前缀都是 `jdbc:gaussdb`，与 `jdbc:opengauss` 是两个产品线。
            Map.entry("oceanbase", "OceanBase"),
            Map.entry("opengauss", "openGauss"),
            Map.entry("gaussdb", "GaussDB"),
            // T-XcDialects3（券 03）：瀚高官方驱动 HgdbJdbc 的 Driver.class 常量池仅认 jdbc:highgo:；
            // 海量 vastbase-jdbc 的 cn.com.vastbase.Driver 同时认 jdbc:vastbase: 与 jdbc:dws:iam:
            // （取子协议得 vastbase / dws）。注意 com.highgo:hgdb-pgjdbc 走 jdbc:postgresql:，
            // 是原版 pgjdbc，按 PostgreSQL 处理不纠正（调研报告 §5.2 字节码实证）。
            Map.entry("highgo", "HighGo"),
            Map.entry("vastbase", "Vastbase"),
            Map.entry("dws", "Vastbase")
    ));

    /**
     * 从 JDBC URL 提取子协议并映射方言名。
     * 形态：`jdbc:&lt;子协议&gt;:...`（子协议大小写不敏感）；非 jdbc: 前缀/空 URL/未知子协议 → null。
     */
    @Nullable
    public static String dialectName(@Nullable String url) {
        if (url == null || url.isBlank()) return null;
        String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, "jdbc:", 0, 5)) return null;
        String rest = trimmed.substring(5);
        int colon = rest.indexOf(':');
        String subprotocol = (colon >= 0 ? rest.substring(0, colon) : rest).toLowerCase(Locale.ROOT);
        return SUBPROTOCOL_TO_DIALECT.get(subprotocol);
    }
}
