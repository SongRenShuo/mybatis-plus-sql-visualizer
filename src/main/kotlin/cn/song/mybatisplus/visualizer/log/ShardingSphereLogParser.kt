package cn.song.mybatisplus.visualizer.log

/**
 * ShardingSphere 双段日志解析器。
 *
 * UI-008：以真实 5.5.2/H2 内存 fixture 的 sql-show 输出为兼容锚点：
 * - `Logic SQL: select ...` 为主条目；
 * - `Actual SQL: ds_0 ::: select ... ::: [100, BOOK,100]` 为路由原文；
 * - 旧 `::: Parameters: [...]` 也仅作为路由原文保留，不表示有可靠类型。
 * Actual 参数属于 SQLUnit 的物理 SQL，分页改写等会改变值，不能映射回 Logic。
 * 即使占位数相等、各片参数一致也不回填；始终保留 Logic 问号和只读 Actual 原文。
 *
 * 三态状态机（循 [HibernateLogParser] 等待态配对先例 + [LogThreadRouter] 线程分桶）：
 * - IDLE：等待 Logic SQL 行；
 * - PENDING：Logic 行开启、Actual 行逐行折叠；下一条 Logic 行 / [closePending] / [flushSettled]
 *   （静默 ≥1s，循 JdbcTemplate T-ConsolePendingFlush 先例）闭合产出。
 * - 噪声行在 PENDING 态**不闭合**（保守：与 MyBatis 行交错的场景 V11a 未采样，先保配对；
 *   代价是条目滞后一条语句产出，面板可见性由静默冲刷兜底）。
 *
 * 边界：List.toString 不携带类型或转义，001/true/逗号字符串均不能据此确定字面量。
 * 不拆 Actual 参数，不伪造类型或逻辑绑定；4.x 不在本次范围。
 *
 * 与其余解析器的互斥（F-04）：本解析器只认 `Logic SQL:` / `Actual SQL:` 关键字，
 * 不吃 MyBatis `Preparing:`/`Parameters:` 行、Hibernate `Hibernate:` 行、
 * JdbcTemplate `Executing prepared SQL statement` 行——各族的行形态互不重叠，
 * 桥 [SqlLogConsoleBridge.processConsoleLine] 对每行四解析器并行喂入即天然互斥，
 * 同一条语句不会被重复计数（判别用例见 `ShardingSphereLogParserTest.真实格式桥分派开关和其余三族互不干扰`）。
 */
class ShardingSphereLogParser(
    /** 行解析最大长度（超长截断，防控制台大输出卡顿；与其余解析器同口径）。 */
    var maxParseLength: Int = DEFAULT_MAX_PARSE_LENGTH,

    /** 静默冲刷阈值（毫秒）；语义与 [JdbcTemplateLogParser] 同款。 */
    private val settleMs: Long = DEFAULT_SETTLE_MS,

    /** settle 判定的时钟（测试注入可控件）。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 单线程桶：一条双段语句从 Logic 行到闭合之间的全部状态。 */
    private class Bucket {
        /** Logic SQL（关键字后原文；null = 空闲态）。 */
        var logicSql: String? = null

        /** Logic 行原文（日志时间取它）。 */
        var pendingLogLine: String? = null

        /** Actual 段折叠缓冲（关键字后原文逐行）。 */
        val actualLines = mutableListOf<String>()

        /**
         * F66（T-PerfSweep18）：[actualLines] join 后的总字符数（含 `\n` 分隔符）的累积计数器——
         * 追加前置判长不再全缓冲 joinToString 重建整串（每 Actual 行 O(已累积) → O(1)）。
         * 与 closeBucketPending 的 `joinToString("\n")` 结果恒等（追加时同步加分隔符）。
         */
        var actualJoinedLength = 0
    }

    private val router = LogThreadRouter(
        factory = { Bucket() },
        isCollecting = { it.logicSql != null },
        isIdle = { it.logicSql == null },
        // Logic/Actual 行各带日志框架前缀（线程 token 在位），裸行只有正文续行形态——骨架按
        // 「收集态桶优先」共享路由即可，不开 MRU（Hibernate 专属开关，无其双行形态）。
        // 时钟与 [clock] 同源（flushSettled 的静默判定一致性；测试注入可控件）。
        clock = clock,
    )

    private val entries = mutableListOf<LogSqlEntry>()

    /** 喂入一行控制台输出（任意线程；按线程 token 路由到独立桶）。 */
    fun feed(line: String) {
        feedInto(router.bucketFor(line), line)
    }

    /**
     * F58（T-PerfSweep18 压轴）：喂入一行＋**已提取**的线程 token（热路径每行一次下传，
     * 语义与 [feed] 自提逐字等价；token=null 走 fail-open 回退）。
     */
    fun feed(line: String, threadToken: String?) {
        feedInto(router.bucketForToken(threadToken), line)
    }

    private fun feedInto(bucket: Bucket, line: String) {
        // 1. Actual SQL 行 → 折叠附注（PENDING 态才有上下文；IDLE 态丢弃）
        val actualSegment = extractAfterKeyword(line, ACTUAL_SQL_PREFIX)
        if (actualSegment != null) {
            val logic = bucket.logicSql ?: return // 未配对 Actual 丢弃（等待态配对先例同口径）
            // F66：判长走累积计数器（等价于 joinToString("\n").length，免去整串重建）
            if (logic.isNotEmpty() && bucket.actualLines.size < MAX_ACTUAL_LINES &&
                bucket.actualJoinedLength < maxParseLength
            ) {
                val segment = actualSegment.take(maxParseLength)
                if (bucket.actualLines.isNotEmpty()) bucket.actualJoinedLength++ // 行间分隔符
                bucket.actualJoinedLength += segment.length
                bucket.actualLines += segment
            }
            return
        }
        // 2. Logic SQL 行 → 闭合上一条、开启新收集
        val logicSegment = extractAfterKeyword(line, LOGIC_SQL_PREFIX)
        if (logicSegment != null) {
            closeBucketPending(bucket)
            // TODO(等语料)：remainder 为空的分行形态（Logic SQL: 独占一行）5.x 文档无此形态，
            // 骨架按空串拒绝开启（不产条目），语料实证后再补等待态。
            if (logicSegment.isBlank()) return
            bucket.logicSql = logicSegment.take(maxParseLength)
            bucket.pendingLogLine = line
            return
        }
        // 3. 噪声行：PENDING 态不闭合（保守保配对，见类头 TODO/V11a）
    }

    /** 闭合全部桶的未完成条目（面板刷新/MCP 工具收尾用）。返回首个产出的条目。 */
    fun closePending(): LogSqlEntry? {
        var first: LogSqlEntry? = null
        for (bucket in router.allBuckets()) {
            val produced = closeBucketPending(bucket)
            if (first == null) first = produced
        }
        return first
    }

    /** 取出全部已闭合条目并清空缓冲区（面板刷新用）。 */
    fun takeEntries(): List<LogSqlEntry> {
        val result = entries.toList()
        entries.clear()
        return result
    }

    /**
     * 静默冲刷——闭合全部已静默 ≥ [settleMs] 的桶的待产出条目，产出进 [entries]
     * （由调用方随下一次 [takeEntries] 取走），返回本次产出条数。
     * 语义与调用点同 [JdbcTemplateLogParser.flushSettled]（逐行入口 + 桥轮询兜底两处）——
     * ShardingSphere 条目只在「下一条 Logic 行」闭合，低频访问下最后一条没有后继行，靠这一拍兜底。
     */
    fun flushSettled(nowMs: Long = clock()): Int {
        var produced = 0
        for (bucket in router.settledBuckets(settleMs, nowMs)) {
            if (closeBucketPending(bucket) != null) produced++
        }
        return produced
    }

    // ---------- 私有方法 ----------

    private fun closeBucketPending(bucket: Bucket): LogSqlEntry? {
        val logic = bucket.logicSql ?: return null
        bucket.logicSql = null
        val logLine = bucket.pendingLogLine
        bucket.pendingLogLine = null
        val actual = bucket.actualLines.joinToString("\n")
        bucket.actualLines.clear()
        bucket.actualJoinedLength = 0
        if (logic.isBlank()) return null
        val entry = LogSqlEntry(
            rawSql = logic,
            restoredSql = logic,
            params = emptyList(),
            source = SqlSource.SHARDINGSPHERE,
            logTimestamp = logLine?.let { LogTimestampParser.parse(it) },
            actualSql = actual.ifEmpty { null },
        )
        entries += entry
        return entry
    }

    /** 提取关键字后的文本（行中任意位置定位——容忍日志框架前缀；与其余解析器 mixed 语义一致）。 */
    private fun extractAfterKeyword(line: String, keyword: String): String? {
        val idx = line.indexOf(keyword)
        if (idx < 0) return null
        return line.substring(idx + keyword.length).trim()
    }

    companion object {
        const val LOGIC_SQL_PREFIX = "Logic SQL:"
        const val ACTUAL_SQL_PREFIX = "Actual SQL:"
        const val PARAMETERS_KEYWORD = "Parameters:"

        /** Actual 行折叠上限（防异常日志膨胀；路由/条目另有 maxParseLength 与桶上限兜底）。 */
        const val MAX_ACTUAL_LINES = 64

        const val DEFAULT_MAX_PARSE_LENGTH = 20000

        /** 静默冲刷阈值（对齐 [JdbcTemplateLogParser.DEFAULT_SETTLE_MS]）。 */
        const val DEFAULT_SETTLE_MS = 1000L
    }
}
