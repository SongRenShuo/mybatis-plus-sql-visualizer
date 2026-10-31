package cn.song.mybatisplus.visualizer.sql

/**
 * 方言**函数可用性**最小目录（NEW-21 信创兼容性体检「函数不存在」档的事实源）。
 *
 * ## 为什么是「最小目录」而不是全量函数表
 *
 * 仓内此前**没有**任何方言函数表（立项册 §三 现状：渲染入口只由 [Dialect] 驱动，函数名原样透传），
 * 而全量函数面（各库 1000+ 内置函数）既无可靠抓取渠道、也无法逐条验证。故本目录按立项册允许的
 * 降级口径落地：**只登记证据足够的函数**，未登记的函数一律**不判**（[dialectsFor] 返回 null，
 * 编排层不出「函数不存在」结论）——宁可少报，不拿猜测当结论。
 *
 * 判定语义与代价不对称：本目录用于**风险提示**（「迁移到 X 可能不可用」），不是执行闸门。
 * 故每条登记都取「保守下界」= 有文档/实测背书的方言集合；集合外的方言报风险提示（可能是误报，
 * 但用户会去核对一次），集合内的方言不报（漏报由未知函数的不判兜底）。
 *
 * ## 证据级别（逐条标注；未标注处不得默认「已实测」）
 *
 *  - **引擎级实测**（本仓既有测试/真机记录）：`GROUP_CONCAT` / `IFNULL` / `IF` / `DATE_FORMAT` /
 *    `STR_TO_DATE` / `JSON_EXTRACT` 在 MySQL 族（H2 MySQL 兼容模式实测）；
 *  - **官方文档级**：PG 族 `STRING_AGG` / `ARRAY_AGG` / `TO_CHAR`；Oracle `NVL` / `NVL2` /
 *    `LISTAGG` / `DECODE` / `TO_CHAR` / `SYSDATE`；SQL Server `GETDATE` / `STRING_AGG`（2017+）；
 *    DM8（Oracle 兼容模式）的 `NVL` / `NVL2` / `DECODE` / `LISTAGG` / `SYSDATE`；
 *    H2 的 `GROUP_CONCAT` / `IFNULL` / `STRING_AGG` / `ARRAY_AGG` / `LISTAGG` / `TO_CHAR`；
 *    SQLite 的 `GROUP_CONCAT` / `IFNULL` / `JSON_EXTRACT`（3.38+）。
 *
 * **明确不登记**（避免用族推定冒充事实）：openGauss/GaussDB/Vastbase 的 `GROUP_CONCAT`
 * （openGauss 有 `ON DUPLICATE KEY` 一类的 MySQL 兼容面，但 `GROUP_CONCAT` 未见文档，
 * 真机也未测）；SQLite 的 `NOW()`（SQLite 只有 `datetime('now')`）。
 *
 * 纯逻辑（只依赖 [Dialect] 身份），普通 JUnit 可覆盖。
 */
object DialectFunctionCatalog {

    /** MySQL 方言族（含 MySQL 兼容面；DM 的「MySQL 形态分页」不等于函数兼容，不入族）。 */
    private val MYSQL_FAMILY: Set<String> = names(MySqlDialect, MariaDbDialect, OceanBaseDialect)

    /** PG 方言族（PG / 金仓 / openGauss / GaussDB / 瀚高 / 海量）。 */
    private val PG_FAMILY: Set<String> = names(
        PostgreSQLDialect, KingBaseDialect, OpenGaussDialect, GaussDbDialect, HighGoDialect, VastbaseDialect,
    )

    /** Oracle 兼容面的商用库（Oracle / DM）。 */
    private val ORACLE_FAMILY: Set<String> = names(OracleDialect, DmDialect)

    /** 函数名（大写）→ 有证据支持的方言显示名集合。 */
    private val SUPPORT: Map<String, Set<String>> = mapOf(
        // MySQL 族专有
        "GROUP_CONCAT" to MYSQL_FAMILY + names(SqliteDialect, H2Dialect),
        "IFNULL" to MYSQL_FAMILY + names(SqliteDialect, H2Dialect),
        "IF" to MYSQL_FAMILY,
        "DATE_FORMAT" to MYSQL_FAMILY,
        "STR_TO_DATE" to MYSQL_FAMILY,
        "JSON_EXTRACT" to MYSQL_FAMILY + names(SqliteDialect),
        // PG 族专有
        "STRING_AGG" to PG_FAMILY + names(SqlServerDialect, H2Dialect),
        "ARRAY_AGG" to PG_FAMILY + names(H2Dialect),
        // Oracle 兼容面
        "NVL" to ORACLE_FAMILY,
        "NVL2" to ORACLE_FAMILY,
        "DECODE" to ORACLE_FAMILY,
        "LISTAGG" to ORACLE_FAMILY + names(H2Dialect),
        "TO_CHAR" to ORACLE_FAMILY + PG_FAMILY + names(H2Dialect),
        "SYSDATE" to ORACLE_FAMILY + MYSQL_FAMILY,
        // 时间函数（各库形态不同，是信创迁移的高频撞点）
        "NOW" to MYSQL_FAMILY + PG_FAMILY + names(H2Dialect),
        "GETDATE" to names(SqlServerDialect),
    )

    /**
     * 本函数的可用方言集合；**null = 本目录未收录 → 不判**（不同于空集，空集表示「已知哪儿都不支持」）。
     */
    fun dialectsFor(functionName: String): Set<String>? = SUPPORT[functionName.uppercase()]

    /** 已登记的函数名（大写，供报告/测试枚举）。 */
    val cataloguedFunctions: Set<String> get() = SUPPORT.keys

    private fun names(vararg dialects: Dialect): Set<String> = dialects.map { it.name }.toSet()
}
