package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat

/**
 * T-AgentLogTime 跨通道端到端守卫：**真实控制台解析链 + 真实 agent 适配器 + 真实回填入口**
 * （[processConsoleLine] 嗅探态 → [LogTimeCorrelator] → [stampLogTime]），不手写中间产物。
 *
 * 立项背景：旧口径用「还原后 SQL 指纹」当关联键，而该口径从未被真验过——既有
 * `AgentEventAdapterTest.dedupeKey与桥通道一致` 是手写一条 restoredSql 与自己比对（自证）。
 * 实测（2026-09-16 探针）证实在布尔参数、注释内 `?`、参数个数不等、多行 SQL 四类形态下
 * 静默失配，面板于是显示回落的捕获时间。本用例把每类形态钉成断言：命中即比对到日志行原始时间。
 */
class AgentLogTimeParityTest {

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    /** 日志行时间用「当前时刻往前一点」生成——与墙钟同源，不会因相关器时间窗规则变动而假红。 */
    private fun lineTime(agoMs: Long) = sdf.format(java.util.Date(System.currentTimeMillis() - agoMs))

    private val logLineTime = lineTime(500)

    private val logLineMillis = sdf.parse(logLineTime).time

    /** agent 侧捕获时刻（命中时应被日志行时间覆盖；未命中时应原样保留 = 回落语义）。 */
    private val agentCapturedAt = 1_700_000_000_000L

    @After
    fun tearDown() {
        SqlCaptureGate.paused.set(false)
        LogTimeCorrelator.clear()
    }

    private fun newCtx(): BridgeLineContext {
        val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000)
        return BridgeLineContext(
            MyBatisLogParser(cfg.sqlPrefix, cfg.paramPrefix, cfg.mixedPrefix, cfg.maxParseLength),
            HibernateLogParser(cfg.hibernateSqlPrefix, cfg.hibernateParamPrefix, cfg.mixedPrefix, cfg.maxParseLength),
            JdbcTemplateLogParser(cfg.jdbcEnabled, cfg.maxParseLength),
            { cfg },
            cfg.hibernateEnabled,
        )
    }

    /**
     * 一条端到端链路：喂控制台行（嗅探态）→ agent 事件过适配器 → [stampLogTime] 回填。
     * 返回回填后的条目（[LogSqlEntry.logTimestamp] 为 null 即未命中）。
     */
    private fun runChain(
        consoleLines: List<String>,
        agentSql: String,
        agentParams: List<Triple<Int, String?, String?>>,
    ): LogSqlEntry {
        SqlCaptureGate.paused.set(true)
        LogTimeCorrelator.clear()
        val ctx = newCtx()
        consoleLines.forEach { SqlLogConsoleBridgeKt.processConsoleLine(it, ctx, null, {}) }
        val event = AgentEvent(
            agentSql,
            agentCapturedAt,
            5_000_000L,
            null,
            null,
            null,
            0,
            null,
            null,
            agentParams,
            "main",
            null,
        )
        val entry = AgentEventAdapter.toEntry(event)
        return LogSqlModelsKt.stampLogTime(entry) ?: entry
    }
    private fun assertHit(entry: LogSqlEntry, label: String) {
        assertEquals("$label：应与日志行时间一致", logLineMillis, entry.logTimestamp)
    }

    private fun assertMissKeepsCaptureTime(entry: LogSqlEntry, label: String) {
        assertNull("$label：未命中时 logTimestamp 保持 null（显示回落捕获时间）", entry.logTimestamp)
        assertEquals("$label：回落值是 agent 采集时刻（execute 开始）", agentCapturedAt, entry.timestamp)
    }

    // ---------- 命中面：键不再依赖参数还原口径 ----------

    @Test
    fun `字符串与数值参数_命中日志行时间`() {
        val entry = runChain(
            listOf(
                "$logLineTime [main] ==>  Preparing: SELECT * FROM user WHERE name = ? AND id = ?",
                "$logLineTime [main] ==> Parameters: abc(String), 42(Long)",
            ),
            "SELECT * FROM user WHERE name = ? AND id = ?",
            listOf(Triple(1, "abc", "String"), Triple(2, "42", "Long")),
        )
        assertHit(entry, "String+Long")
    }

    @Test
    fun `布尔参数_命中日志行时间`() {
        // 旧键死因：agent 还原成 'true'（引号），控制台还原成 true（直出）→ 指纹不等
        val entry = runChain(
            listOf(
                "$logLineTime [main] ==>  Preparing: SELECT * FROM t WHERE flag = ?",
                "$logLineTime [main] ==> Parameters: true(Boolean)",
            ),
            "SELECT * FROM t WHERE flag = ?",
            listOf(Triple(1, "true", "Boolean")),
        )
        assertHit(entry, "布尔参数")
    }

    @Test
    fun `时间参数_命中日志行时间`() {
        val entry = runChain(
            listOf(
                "$logLineTime [main] ==>  Preparing: SELECT * FROM t WHERE ct = ?",
                "$logLineTime [main] ==> Parameters: 2026-09-16 10:30:45.0(Timestamp)",
            ),
            "SELECT * FROM t WHERE ct = ?",
            listOf(Triple(1, "2026-09-16 10:30:45.0", "Timestamp")),
        )
        assertHit(entry, "Timestamp 参数")
    }

    @Test
    fun `null参数_命中日志行时间`() {
        val entry = runChain(
            listOf(
                "$logLineTime [main] ==>  Preparing: SELECT * FROM t WHERE a = ?",
                "$logLineTime [main] ==> Parameters: null",
            ),
            "SELECT * FROM t WHERE a = ?",
            listOf(Triple(1, null, "NULL")),
        )
        assertHit(entry, "null 参数")
    }

    @Test
    fun `字符串字面量内含问号_命中日志行时间`() {
        val entry = runChain(
            listOf(
                "$logLineTime [main] ==>  Preparing: SELECT * FROM t WHERE tag = '?' AND id = ?",
                "$logLineTime [main] ==> Parameters: 9(Long)",
            ),
            "SELECT * FROM t WHERE tag = '?' AND id = ?",
            listOf(Triple(1, "9", "Long")),
        )
        assertHit(entry, "字面量内 ?")
    }

    @Test
    fun `块注释内含问号_命中日志行时间`() {
        // 旧键死因：agent 占位符计数把注释里的 ? 算进去 → 数量守卫触发 → 整句不还原 → 指纹不等
        val entry = runChain(
            listOf(
                "$logLineTime [main] ==>  Preparing: SELECT * FROM t /* hint ? */ WHERE id = ?",
                "$logLineTime [main] ==> Parameters: 42(Long)",
            ),
            "SELECT * FROM t /* hint ? */ WHERE id = ?",
            listOf(Triple(1, "42", "Long")),
        )
        assertHit(entry, "注释内 ?")
    }

    @Test
    fun `参数个数不匹配_不借用其他签名的日志时间`() {
        // rawSql相同不能抵消已知参数签名不一致；少捕参数时同时保持SQL与时间的保守回退。
        val entry = runChain(
            listOf(
                "$logLineTime [main] ==>  Preparing: SELECT * FROM t WHERE a = ? AND b = ?",
                "$logLineTime [main] ==> Parameters: 1(Long), 2(Long)",
            ),
            "SELECT * FROM t WHERE a = ? AND b = ?",
            listOf(Triple(1, "1", "Long")),
        )
        assertMissKeepsCaptureTime(entry, "参数个数不匹配")
        assertEquals("不匹配不能消费其他执行的时间", 1, LogTimeCorrelator.size())
        assertEquals("少捕参数时 agent 侧保持原样不还原（防错位）", "SELECT * FROM t WHERE a = ? AND b = ?", entry.restoredSql)
    }

    // ---------- 同键多次执行：签名判别 + FIFO ----------

    @Test
    fun `同SQL不同参数_各配各的日志时间`() {
        SqlCaptureGate.paused.set(true)
        LogTimeCorrelator.clear()
        val ctx = newCtx()
        val first = lineTime(1200)
        val second = lineTime(700)
        listOf(
            "$first [main] ==>  Preparing: SELECT * FROM user WHERE id = ?",
            "$first [main] ==> Parameters: 1(Long)",
            "$second [main] ==>  Preparing: SELECT * FROM user WHERE id = ?",
            "$second [main] ==> Parameters: 2(Long)",
        ).forEach { SqlLogConsoleBridgeKt.processConsoleLine(it, ctx, null, {}) }

        fun fire(id: String) = LogSqlModelsKt.stampLogTime(
            AgentEventAdapter.toEntry(
                AgentEvent(
                    "SELECT * FROM user WHERE id = ?", agentCapturedAt, 1L,
                    null, null, null, 0,
                    null, null,
                    listOf(Triple(1, id, "Long")), "main", null,
                ),
            ),
        ) ?: error("应命中")

        assertEquals("参数 2 命中第二条日志行", sdf.parse(second).time, fire("2").logTimestamp)
        assertEquals("参数 1 命中第一条日志行", sdf.parse(first).time, fire("1").logTimestamp)
    }

    @Test
    fun `同SQL同参数连续两次_FIFO逐条配对`() {
        SqlCaptureGate.paused.set(true)
        LogTimeCorrelator.clear()
        val ctx = newCtx()
        val first = lineTime(1200)
        val second = lineTime(700)
        listOf(
            "$first [main] ==>  Preparing: SELECT * FROM user WHERE id = ?",
            "$first [main] ==> Parameters: 1(Long)",
            "$second [main] ==>  Preparing: SELECT * FROM user WHERE id = ?",
            "$second [main] ==> Parameters: 1(Long)",
        ).forEach { SqlLogConsoleBridgeKt.processConsoleLine(it, ctx, null, {}) }

        val e1 = LogSqlModelsKt.stampLogTime(entryOf("SELECT * FROM user WHERE id = ?", "1"))
        val e2 = LogSqlModelsKt.stampLogTime(entryOf("SELECT * FROM user WHERE id = ?", "1"))
        assertNotNull(e1); assertNotNull(e2)
        assertEquals("先到的事件配先到的日志行（旧实现同键覆盖会丢第一条）", sdf.parse(first).time, e1!!.logTimestamp)
        assertEquals(sdf.parse(second).time, e2!!.logTimestamp)
    }

    private fun entryOf(sql: String, param: String) = AgentEventAdapter.toEntry(
        AgentEvent(
            sql, agentCapturedAt, 1L,
            null, null, null, 0,
            null, null,
            listOf(Triple(1, param, "Long")), "main", null,
        ),
    )

    @Test
    fun `超长参数截断后_仍命中日志行时间`() {
        // T-PS4-05：>256 字符参数两侧共用 logTimeParamSignature 截断——真实控制台链 put 与
        // agent take 签名一致，仍精确配对（不退队首不落空）
        val longValue = "x".repeat(LogSqlModelsKt.PARAM_SIGNATURE_MAX_CHARS + 100)
        val entry = runChain(
            listOf(
                "$logLineTime [main] ==>  Preparing: SELECT * FROM t WHERE blob = ?",
                "$logLineTime [main] ==> Parameters: $longValue(String)",
            ),
            "SELECT * FROM t WHERE blob = ?",
            listOf(Triple(1, longValue, "String")),
        )
        assertHit(entry, "超长参数截断")
    }

    // ---------- 未命中面：占位符版原文两侧不同的已知残余（回落语义受 L1 保护） ----------

    @Test
    fun `多行SQL_续行落地后命中日志行时间`() {
        // T-LPC-07（2026-09-16 本期实施）：MyBatisLogParser 支持多行 SQL 续行后，控制台侧 rawSql
        // 与 agent 侧完整 SQL 同形 → 键相等 → 命中。本用例此前记录为「已知未命中（待 L5 落地）」。
        val entry = runChain(
            listOf(
                "$logLineTime [main] ==>  Preparing: SELECT id, name",
                "FROM user",
                "WHERE id = ?",
                "$logLineTime [main] ==> Parameters: 42(Long)",
            ),
            "SELECT id, name\nFROM user\nWHERE id = ?",
            listOf(Triple(1, "42", "Long")),
        )
        assertHit(entry, "多行 SQL（续行已落地）")
    }

    @Test
    fun `无参语句未闭合_未命中回落捕获时间`() {
        // 无参语句没有 Parameters 行，控制台条目要等下一条语句到来才闭合（closePending）——其日志时间
        // 赶不上同一条 SQL 的 agent 事件。回落值 = execute 开始时刻，与日志行只差毫秒级，故不单独治。
        val entry = runChain(
            listOf("$logLineTime [main] ==>  Preparing: SELECT * FROM user WHERE id = 42"),
            "SELECT * FROM user WHERE id = 42",
            emptyList(),
        )
        assertMissKeepsCaptureTime(entry, "无参未闭合")
    }
}
