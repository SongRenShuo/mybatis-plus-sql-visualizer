package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-05（JPA/HQL 捕获延伸）归因最小切片：JPA/Hibernate SQL 在捕获侧**已可见**（控制台通道
 * [HibernateLogParser] + agent 通道 CaptureCatchAll 方言无关捕获），本文件钉住的是
 * 「归因」事实——两条链各自产出的条目带什么 source 与通道标记、同一条 SQL 跨通道不重复计数、
 * 折叠后归因保留 HIBERNATE。
 *
 * 每个用例走真实链路（循 [CrossChannelDedupeTest] 立项口径，不手写条目自证）：
 *  控制台行 → [processConsoleLine] → buffer.addEntry（CONSOLE_BRIDGE 打标同生产 emitEntries）
 *  同一 SQL → [AgentEventAdapter.toEntry] → buffer.addEntry
 *
 * 反向验证：摘 processConsoleLine 的 hibernateParser.feed → 控制台归因用例恰红。
 */
class JpaHibernateCaptureAttributionTest {

    @After
    fun tearDown() {
        SqlCaptureGate.paused.set(false)
        SqlCaptureGate.epActive = false
        LogTimeCorrelator.clear()
    }

    // ---------- 真实链路夹具（同 CrossChannelDedupeTest 口径） ----------

    private fun newCtx(): BridgeLineContext {
        val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000)
        return BridgeLineContext(
            myBatisParser = MyBatisLogParser(
                sqlPrefix = cfg.sqlPrefix,
                paramPrefix = cfg.paramPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
            ),
            hibernateParser = HibernateLogParser(
                sqlPrefix = cfg.hibernateSqlPrefix,
                paramPrefix = cfg.hibernateParamPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
            ),
            jdbcParser = JdbcTemplateLogParser(enabled = cfg.jdbcEnabled, maxParseLength = cfg.maxParseLength),
            configProvider = { cfg },
            hibernateEnabled = cfg.hibernateEnabled,
        )
    }

    private fun consoleEntries(vararg lines: String): List<LogSqlEntry> {
        SqlCaptureGate.paused.set(false)
        val ctx = newCtx()
        val out = mutableListOf<LogSqlEntry>()
        lines.forEach { out += processConsoleLine(it, ctx, null, {}) }
        // Hibernate 解析器等待态配对：绑定行后条目待闭合（同桥「真实格式桥分派」测试的收尾口径）
        ctx.hibernateParser.closePending()
        out += ctx.hibernateParser.takeEntries()
        return out.map { it.copy(captureProvenance = CaptureProvenance.CONSOLE_BRIDGE) }
    }

    private fun agentEntry(sql: String, params: List<Triple<Int, String?, String?>>): LogSqlEntry =
        AgentEventAdapter.toEntry(
            AgentEvent(
                sql = sql,
                timestamp = System.currentTimeMillis(),
                durationNanos = 5L * 1_000_000L,
                updateCount = null,
                errorText = null,
                sourceFile = null,
                sourceLine = 0,
                methodKey = null,
                requestTag = null,
                params = params,
                threadName = "main",
                url = "jdbc:h2:mem:demo",
            ),
        )

    // ---------- 控制台通道：Hibernate SQL 可见且归因 HIBERNATE ----------

    @Test
    fun `控制台通道_Hibernate语句归因HIBERNATE且参数可还原`() {
        val entries = consoleEntries(
            "Hibernate: insert into jpa_order (amount, state, id) values (?, ?, ?)",
            "binding parameter [1] as [INTEGER] - [100]",
            "binding parameter [2] as [VARCHAR] - [PAID]",
        )
        assertEquals("捕获侧可见：JPA/Hibernate 语句入条目链", 1, entries.size)
        assertEquals("归因标记：source=HIBERNATE", SqlSource.HIBERNATE, entries[0].source)
        assertEquals("通道归因：控制台桥", CaptureProvenance.CONSOLE_BRIDGE, entries[0].captureProvenance)
        val restored = entries[0].restoredSql
        assertTrue("amount 回填: $restored", restored.contains("100"))
        assertTrue("state 回填", restored.contains("'PAID'"))
    }

    @Test
    fun `控制台通道_与MyBatis行同屏不串归因`() {
        val ctx = newCtx()
        val out = mutableListOf<LogSqlEntry>()
        out += processConsoleLine("==>  Preparing: SELECT * FROM mb WHERE id = ?", ctx, null, {})
        out += processConsoleLine("==> Parameters: 7(Integer)", ctx, null, {})
        out += processConsoleLine("Hibernate: SELECT * FROM hb WHERE id = ?", ctx, null, {})
        // Hibernate 条目待闭合（等待态配对），与桥收尾口径一致
        ctx.hibernateParser.closePending()
        out += ctx.hibernateParser.takeEntries()
        ctx.jdbcParser.closePending()
        val hibernate = out.filter { it.source == SqlSource.HIBERNATE }
        val mybatis = out.filter { it.source == SqlSource.MYBATIS }
        assertEquals(1, hibernate.size)
        assertEquals(1, mybatis.size)
        assertTrue("互不重复计数：各归其源", hibernate.single().restoredSql.contains("hb"))
        assertTrue(mybatis.single().restoredSql.contains("mb"))
    }

    // ---------- agent 通道：方言无关捕获 + 归因 ----------

    @Test
    fun `agent通道_同一Jpa语句归因AGENT与控制台不重复计数`() {
        val sql = "insert into jpa_order (amount, state, id) values (?, ?, ?)"
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        // 控制台先到
        buffer.addEntry(consoleEntries(
            "Hibernate: $sql",
            "binding parameter [1] as [INTEGER] - [100]",
            "binding parameter [2] as [VARCHAR] - [PAID]",
        ).single())
        // agent 后到（CaptureCatchAll 方言无关捕获同一语句）
        val agent = agentEntry(sql, listOf(Triple(1, "100", "Integer"), Triple(2, "PAID", "String")))
        buffer.addEntry(agent)
        val rows = buffer.snapshot()
        assertEquals("同一条 JPA 语句跨通道只留一行（不重复计数）", 1, rows.size)
        val row = rows.single()
        assertEquals("折叠后归因保留 HIBERNATE（控制台先到、source 不被 agent 改写）",
            SqlSource.HIBERNATE, row.source)
        assertEquals("通道归并 MERGED", CaptureProvenance.MERGED, row.captureProvenance)
    }
}
