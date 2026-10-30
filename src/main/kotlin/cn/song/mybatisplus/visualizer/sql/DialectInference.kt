package cn.song.mybatisplus.visualizer.sql

/**
 * T-DialectAutoInfer：JDBC URL 子协议 → 渲染方言名（[DialectRegistry] 命名空间）映射。
 *
 * 与 [cn.song.mybatisplus.visualizer.log.agent.JdbcUrlDialects] 的关系：同键空间（子协议）、
 * 不同值命名空间——那边是面板角标**事实展示名**（如「HSQL」），这边必须精确等于
 * [DialectRegistry] 各方言的 [Dialect.name]（如 DM/KingBase 为中缀名），照搬会 `byName`
 * 失配静默回退 MySQL。未收录子协议 → null（调用方回退，不猜）。
 *
 * 纯函数、无状态；键统一小写。
 */
internal object UrlSubprotocolDialects {

    /** 子协议 → 方言名。未列出=不支持推断（如 derby/clickhouse 等插件无对应渲染方言）。 */
    private val SUBPROTOCOL_TO_DIALECT: Map<String, String> = mapOf(
        "mysql" to "MySQL",
        "mariadb" to "MariaDB",
        "postgresql" to "PostgreSQL",
        "oracle" to "Oracle",
        "sqlserver" to "SQL Server",
        "h2" to "H2",
        "sqlite" to "SQLite",
        "dm" to "DM（达梦）",
        "kingbase8" to "KingBase（人大金仓）",
        "kingbase" to "KingBase（人大金仓）",
        // T-DialectXcFamily（O-63）：oceanbase（OceanBase Connector/J 官方《Database URL》）与
        // opengauss（openGauss 官方《Connecting to a Database》）。
        // T-GaussDbDialect（券 04）：gaussdb 从「同归 openGauss（D2=A）」改为归独立的 GaussDB 方言
        // ——华为 GaussDB 官方《获取驱动 jar 与驱动表》实锤 `gaussdbjdbc.jar` / `gsjdbc200.jar` 的
        // URL 前缀都是 `jdbc:gaussdb`，与 `jdbc:opengauss` 是两个产品线。
        "oceanbase" to "OceanBase",
        "opengauss" to "openGauss",
        "gaussdb" to "GaussDB",
        // T-XcDialects3（券 03）：瀚高官方驱动 HgdbJdbc 的 Driver.class 常量池仅认 jdbc:highgo:；
        // 海量 vastbase-jdbc 的 cn.com.vastbase.Driver 同时认 jdbc:vastbase: 与 jdbc:dws:iam:
        // （取子协议得 vastbase / dws）。注意 com.highgo:hgdb-pgjdbc 走 jdbc:postgresql:，
        // 是原版 pgjdbc，按 PostgreSQL 处理不纠正（调研报告 §5.2 字节码实证）。
        "highgo" to "HighGo",
        "vastbase" to "Vastbase",
        "dws" to "Vastbase",
    )

    /**
     * 从 JDBC URL 提取子协议并映射方言名。
     * 形态：`jdbc:<子协议>:...`（子协议大小写不敏感）；非 jdbc: 前缀/空 URL/未知子协议 → null。
     */
    fun dialectName(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (!trimmed.startsWith("jdbc:", ignoreCase = true)) return null
        val subprotocol = trimmed.substring(5).substringBefore(':').lowercase()
        return SUBPROTOCOL_TO_DIALECT[subprotocol]
    }
}

/**
 * T-DialectAutoInfer：智能推断纯逻辑层（优先级合并 + 结果模型）。
 *
 * 优先级（强→弱）：
 *  1. agent 最近连接 URL（运行中应用的真实数据源，最能代表「当前执行的 SQL 跑在哪个库」）
 *  2. IDE 数据源（DatabaseView 配置的连接 URL）
 *  3. 未命中 → [Result.dialectName] 为 null，消费方回退 MySQL（与既有 `byName(null)` 语义一致）
 *
 * 平台查询（agent 会话状态/数据源列表）由调用方注入 URL 候选，本层只做合并与映射——纯函数可单测。
 */
object DialectInference {

    /** 推断结果：[dialectName] 命中（DialectRegistry 命名空间）或 null=未命中；[basis] 为展示用依据文案（i18n 前的原始键由 UI 层组装，此处为参数原文）。 */
    data class Result(val dialectName: String?, val basis: String?)

    /**
     * 按 URL 候选优先级推断。
     *
     * @param agentUrl      agent 最近一次捕获的连接 URL（null=增强未开/未捕获/旧版 agent）
     * @param dataSourceUrl IDE 数据源 URL（null=无数据源/探测失败）
     */
    fun infer(agentUrl: String?, dataSourceUrl: String?): Result {
        UrlSubprotocolDialects.dialectName(agentUrl)?.let { return Result(it, agentUrl) }
        UrlSubprotocolDialects.dialectName(dataSourceUrl)?.let { return Result(it, dataSourceUrl) }
        return Result(null, null)
    }

    /** 便捷重载：单 URL（如仅有数据源场景）。 */
    fun infer(dataSourceUrl: String?): Result = infer(null, dataSourceUrl)
}
