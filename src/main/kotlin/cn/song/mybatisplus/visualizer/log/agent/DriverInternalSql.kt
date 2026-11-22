package cn.song.mybatisplus.visualizer.log.agent

import java.util.concurrent.atomic.AtomicLong

/**
 * T-DriverInternalSqlFilter（2026-11-21）：驱动内部语句识别（连接初始化族），纯函数零平台依赖。
 *
 * 动因（沙盒实证）：mysql-connector 5.1.49 建连接时自执行一批系统变量查询——带版本注释的
 * `SELECT @@session.auto_increment_increment AS ...` 大查询、`SELECT @@session.autocommit;`、
 * `SELECT @@session.transaction.read_only;`——agent 挂上后全部进了 SQL 日志（Jdk16Demo 现场，
 * 46 条事件头部全是这些，业务 SQL 一条没跑）。它们不是业务 SQL，在插件入口层过滤：
 * 对已挂载的存量 agent 立即生效（用户应用不用重启），agent 侧继续全量上报
 * （判据留调整余地，事件流量开销可忽略）。
 *
 * 判据闭集（扩展须先有实证，见计划 20261121-1412 §一）：
 *  1. 归一化后以驱动版本注释开头——Connector/J 5.0/5.1 初始化查询自钉
 *     「斜杠星号 + mysql-connector-java-…」形态的指纹注释（字面常量见 [CRITERION_COMMENT]）；
 *  2. 归一化后以 `select @@session.` / `set @@session.` 开头——同驱动系统变量族，无注释形态。
 *
 * 刻意不收（防误伤，教训：保守兜底须先量真实数据）：
 *  - `@@` 宽匹配——SQL Server 的 `SELECT @@IDENTITY` / `@@ROWCOUNT` 是真业务 SQL 常客，
 *    `@@` 家族只收 `@@session.` 带点形态；
 *  - `SET NAMES` / `SET character_set_results` 等——本沙盒未见实证，拿到证据再登记。
 *
 * 大小写与前导空白归一后比较（驱动实际输出为小写注释 + 原样 SQL，归一只放宽不收紧）。
 */
internal object DriverInternalSql {

    /** 判据 1 的注释指纹字面量（KDoc 里不能写字面「斜杠星号」——Kotlin 块注释嵌套会吞文档）。 */
    private const val CRITERION_COMMENT = "/* mysql-connector-java-"

    /** 判据 2 的系统变量族前缀（无注释形态）。 */
    private val CRITERION_SESSION_PREFIXES = listOf("select @@session.", "set @@session.")

    /** 被过滤的驱动内部语句计数（诊断缝；测试读）。 */
    private val droppedCount = AtomicLong()

    fun isInternal(sql: String?): Boolean {
        if (sql.isNullOrBlank()) return false
        val lower = sql.trim().lowercase()
        if (lower.startsWith(CRITERION_COMMENT)) return true
        return CRITERION_SESSION_PREFIXES.any { lower.startsWith(it) }
    }

    /** 丢弃计数 +1（[AgentSessionSupport] 入队点调用）。 */
    internal fun markDropped() {
        droppedCount.incrementAndGet()
    }

    /** 已丢弃的驱动内部语句数（测试可读）。 */
    fun droppedEvents(): Long = droppedCount.get()
}
