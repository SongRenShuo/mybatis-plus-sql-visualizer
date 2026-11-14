package cn.song.mybatisplus.visualizer.mcp

/**
 * T-McpToolset 方言解析（纯逻辑层）：JDBC URL 推断 + agent 显式指定优先。
 *
 * 返回值为插件方言注册表名（`DialectRegistry.dialects` 的 [cn.song.mybatisplus.visualizer.sql.Dialect.name]，
 * 与 Settings 下拉一致）。URL 关键字对齐插件已验证的真实样例（P-立项-05 方言矩阵）。
 */
object McpDialectDetector {

    /** JDBC URL 子串 → 方言注册名（先匹配先赢，次序即优先级；注册名以 Dialect.name 为准）。 */
    private val URL_PATTERNS: List<Pair<String, String>> = listOf(
        "jdbc:mysql" to "MySQL",
        "jdbc:mariadb" to "MariaDB",
        "jdbc:postgresql" to "PostgreSQL",
        "jdbc:oracle" to "Oracle",
        "jdbc:sqlserver" to "SQL Server",
        "jdbc:h2" to "H2",
        "jdbc:sqlite" to "SQLite",
        "jdbc:dm" to "DM（达梦）",
        "jdbc:kingbase" to "KingBase（人大金仓）",
        // T-DialectXcFamily（O-63）：opengauss 归 openGauss；T-GaussDbDialect（券 04）起 gaussdb 拆出
        // 独立方言（华为 GaussDB 官方驱动 gaussdbjdbc.jar / gsjdbc200.jar 的前缀）
        "jdbc:opengauss" to "openGauss",
        "jdbc:gaussdb" to "GaussDB",
        "jdbc:oceanbase" to "OceanBase",
        // T-XcDialects3（券 03）：瀚高 jdbc:highgo:；海量 jdbc:vastbase: 与 jdbc:dws:iam:
        //（cn.com.vastbase.Driver 错误串原文 "must start with jdbc:vastbase: or jdbc:dws:iam:"）
        "jdbc:highgo" to "HighGo",
        "jdbc:vastbase" to "Vastbase",
        "jdbc:dws" to "Vastbase",
        // T-GoldenDbDialect（券 A）：中兴 GoldenDB 官方驱动 `jdbc:goldendb://`（p9 文档级 +
        // 驱动字节码 URL_PREFIX 常量双证）。次序安全：`jdbc:goldendb` 与首位的 `jdbc:mysql`
        // 前缀互不包含（前缀匹配 startsWith/contains），不会被误命中。
        "jdbc:goldendb" to "GoldenDB",
        // T-DomesticDialects（2026-11-09）：国产/信创第三批 URL 前缀位（证据档位见各方言 KDoc
        // 与调研报告 §3.2；TiDB/PolarDB-X 的 MySQL 版走 jdbc:mysql: 不劫持，不在此登记）。
        // 次序敏感：polardbx 必须在 polardb 之前（前缀包含关系）、gbase8c 必须在 gbase 之前
        //（「先匹配先赢」）；polardb 前缀同时覆盖 polardb:// 与 polardb2:// 两种 URL。
        "jdbc:polardbx" to "PolarDB-X",
        "jdbc:polardb" to "PolarDB",
        "jdbc:gbase8c" to "GBase 8c",
        "jdbc:gbase" to "GBase 8a",
        "jdbc:oscar" to "Oscar（神通）",
        "jdbc:uxdb" to "UXDB（优炫）",
        "jdbc:antdb" to "AntDB",
        "jdbc:xugu" to "XuGu（虚谷）",
        "jdbc:yasdb" to "YashanDB（崖山）",
    )

    /** agent 显式方言名 → 注册名的精确别名表（小写；不 contains 防止 "oracle-no-such-x" 误归一）。 */
    private val ALIASES: Map<String, String> = mapOf(
        "mysql" to "MySQL",
        "mariadb" to "MariaDB",
        "postgresql" to "PostgreSQL",
        "postgres" to "PostgreSQL",
        "pgsql" to "PostgreSQL",
        "pg" to "PostgreSQL",
        "oracle" to "Oracle",
        "sqlserver" to "SQL Server",
        "sql server" to "SQL Server",
        "mssql" to "SQL Server",
        "h2" to "H2",
        "sqlite" to "SQLite",
        "dm" to "DM（达梦）",
        "dameng" to "DM（达梦）",
        "达梦" to "DM（达梦）",
        "kingbase" to "KingBase（人大金仓）",
        "kingbase8" to "KingBase（人大金仓）",
        "金仓" to "KingBase（人大金仓）",
        "人大金仓" to "KingBase（人大金仓）",
        // T-DialectXcFamily（O-63）
        "oceanbase" to "OceanBase",
        "opengauss" to "openGauss",
        // T-GaussDbDialect（券 04）：gaussdb 从同归 openGauss 改为独立方言；中文别名按既有惯例补
        "gaussdb" to "GaussDB",
        "高斯" to "GaussDB",
        "华为高斯" to "GaussDB",
        // T-XcDialects3（券 03）：瀚高/海量。瀚高官方写「瀚高」，但用户手输常写「翰高」，
        // 两种写法都收（精确匹配互不冲突）；海量按厂商中文名收「海量」。
        "highgo" to "HighGo",
        "瀚高" to "HighGo",
        "翰高" to "HighGo",
        "vastbase" to "Vastbase",
        "海量" to "Vastbase",
        // T-GoldenDbDialect（券 A，P-6 销案）：中兴 GoldenDB 别名。中文「金篆」已确证——
        // 官方《开发支持 V6.1.03.10》p1 法律声明「金篆信科有限责任公司」（全文 9 处）即
        // GoldenDB 厂商主体；「中兴」全文 0 命中，不加（加错别名不会误判，但无证据不收）。
        "goldendb" to "GoldenDB",
        "金篆" to "GoldenDB",
        // T-DomesticDialects（2026-11-09）：国产/信创第三批别名位。英文小写别名照各库官方
        // 英文名；中文别名只收厂商/产品公开中文名（「中兴」GoldenDB 先例：无证据不收）。
        "tidb" to "TiDB",
        "polardbx" to "PolarDB-X",
        "polardb" to "PolarDB",
        "gbase" to "GBase 8a",
        "gbase 8a" to "GBase 8a",
        "gbase8c" to "GBase 8c",
        "gbase 8c" to "GBase 8c",
        "oscar" to "Oscar（神通）",
        "神通" to "Oscar（神通）",
        "uxdb" to "UXDB（优炫）",
        "优炫" to "UXDB（优炫）",
        "antdb" to "AntDB",
        "xugu" to "XuGu（虚谷）",
        "虚谷" to "XuGu（虚谷）",
        "yashandb" to "YashanDB（崖山）",
        "yasdb" to "YashanDB（崖山）",
        "崖山" to "YashanDB（崖山）",
    )

    /** agent 显式方言名归一化（精确别名匹配；不可识别返回 null）。 */
    fun normalize(requested: String?): String? =
        requested?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { ALIASES[it] }

    /** 从 JDBC URL 推断方言名；无 URL/不可识别返回 null。 */
    fun guessFromUrl(url: String?): String? {
        val lower = url?.lowercase()?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        return URL_PATTERNS.firstOrNull { (prefix, _) -> lower.startsWith(prefix) || lower.contains(prefix) }?.second
    }

    /**
     * 方言解析：显式可识别名优先，否则 URL 推断，再否则默认 MySQL（DialectRegistry.byName 的默认口径）。
     * 注意：显式名**不可识别**时同样忽略并走 URL/默认——不可识别名由调用方前置 [isRecognized]
     * 校验报 INVALID_ARGUMENT（给 agent 列可选项），本函数不做静默兜底之外的任何处理。
     */
    fun resolve(requested: String?, url: String?): String =
        normalize(requested) ?: guessFromUrl(url) ?: "MySQL"

    /**
     * agent 指定的方言名是否可识别（不可识别时调用方报 INVALID_ARGUMENT 并列可选项）。
     *
     * T-McpRenderDialectFix：null/空白一律视为「未指定」放行——平台 JSON 绑定把省略的可选
     * 参数落成 Kotlin 默认值 `""`，此前 `""` 被判不可识别 → 省略 dialect 必报
     * INVALID_ARGUMENT，与「omit to auto-detect」约定矛盾（2026-09-23 真机实测复现）；
     * [resolve] 对空白本就有 URL 推断 + 默认 MySQL 兜底，行为不受影响。
     */
    fun isRecognized(requested: String?): Boolean =
        requested?.trim()?.takeIf { it.isNotEmpty() }?.let { normalize(it) != null } ?: true
}
