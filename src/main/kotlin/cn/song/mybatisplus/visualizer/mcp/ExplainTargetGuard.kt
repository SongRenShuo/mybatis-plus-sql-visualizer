package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.sql.DataSourceDialectClassification

/**
 * T-Fr45DmGuard S1（FR-0045 第 4 项 b）：`sqlv_explain` 的**非关系型数据源早拒**（纯逻辑，裸 JUnit 可直测）。
 *
 * 报障链路（用户实测）：项目里配了 Redis/Mongo 数据源时，它和 SQL 数据源一样出现在
 * `DataSourceManager` 列表里；`McpDialectDetector.guessFromUrl` 不认 `jdbc:redis:` → null →
 * `resolve()` 兜底 MySQL → `EXPLAIN SELECT …` 被发给 Redis，得到 `JedisDataException`。
 *
 * 判定**复用** [DataSourceDialectClassification.isNonSqlUrl]（`NON_SQL_KEYS` 单点维护，
 * 与 IDE 方言推断侧同一事实源——不复制第二份键表）；本类只负责把布尔判定翻成给 agent 的结构化错误。
 *
 * 文案红线：错误文本**只带 URL 子协议**，不回显完整 URL——连接串里可能含账号/口令/主机。
 *
 * 为什么抽成独立 object（而不是内联在 [SqlVisualizerExplainTool.resolveExplainTarget]）：
 * 调用点依赖 Project/平台类，裸 JUnit 够不着；纯函数化后命中/放行/大小写/空值全部可直测，
 * 「守卫真的接线了」另由 `McpExplainTargetGuardTest` 的源码扫描断言钉住（只钉判定不钉接线＝空绿）。
 */
object ExplainTargetGuard {

    /**
     * 非 SQL 数据源早拒判定。
     *
     * @param url 数据源连接 URL（`DasDataSource.getConnectionConfig().getUrl()` 的反射取值；
     *            null/空白 = 取不到，不猜、放行到既有链路）
     * @return 可直接抛出的结构化错误文本（`[NOT_A_SQL_DATASOURCE] …`）；命中非 SQL 家族才非 null
     */
    fun checkDataSourceUrl(url: String?): String? {
        if (!DataSourceDialectClassification.isNonSqlUrl(url)) return null
        return McpToolErrors.code(
            McpToolErrors.NOT_A_SQL_DATASOURCE,
            "the data source URL sub-protocol '${subprotocolOf(url)}' is not a relational SQL database; " +
                "sqlv_explain needs a relational data source (MySQL / MariaDB / PostgreSQL / Oracle / " +
                "SQL Server / H2 / SQLite / DM / KingBase / OceanBase / openGauss / GaussDB / HighGo / " +
                "Vastbase). Point 'dataSource' at a relational data source and retry.",
        )
    }

    /**
     * 取 JDBC URL 的子协议（`jdbc:<sub>:` 的 `<sub>`，小写归一；非 jdbc 前缀取首个 `:` 前一段）。
     * 仅供错误文案使用——只暴露子协议，不暴露 URL 其余部分。
     */
    internal fun subprotocolOf(url: String?): String {
        val trimmed = url?.trim().orEmpty()
        val body = if (trimmed.startsWith("jdbc:", ignoreCase = true)) trimmed.substring(5) else trimmed
        return body.substringBefore(':').lowercase()
    }
}
