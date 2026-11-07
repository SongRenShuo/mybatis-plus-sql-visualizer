package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SqlListCatchup 券 F1（20261107 真机第三轮压测缺陷）：**跨通道折叠窗（5s）错过后的现存同源
 * 兜底二次对账**端到端守卫。
 *
 * 真机形态（join-demo 压测，99,731 请求全 200，面板「SQL 去重」勾选）：同一次执行的 SQL 在去重
 * 开启态出现两行——一行 `#1 [15:36:09] —`（无耗时/无 `[MYSQL]` 角标/无 ⨉N/无来源位置＝控制台
 * 通道签名），一行 `4ms [MYSQL] ⨉123 [来源]`（agent 通道）。定因：两通道 dedupeKey 分叉（既有
 * 六族）时靠 crossChannelIndex 的 **5s 折叠窗**兜底，而控制台条目经平台 ConsoleViewImpl 冲刷
 * 延迟 >5s 到达（或风暴期时间窗惰性清除先摘了键）——窗一错过，同一次执行的两份观测各占一行。
 *
 * 修复（本文件钉行为）：跨通道索引取消 5s 时间清除（键域仍随队列生命周期封顶），入队折叠窗
 * 未命中时追加**越窗兜底对账**——同 crossKey、异通道标记、且仍在去重窗（60s）内 → 折叠为一行
 * （先到者保留，字段补空不覆盖）。接管沿 grace 截断语义不动（索引重建只留新鲜键，越窗兜底对
 * 已被截断的键天然无候选——P3「attach 即生效」可见性不被本修复回破）。
 *
 * 每个用例都走**真实链路**（控制台行 → 真实解析还原；同 SQL → AgentEventAdapter 真实适配），
 * 时间差用合成时间戳注入（不赌墙钟，L18 口径）。
 */
class CrossChannelStaleReconcileTest {

    /** 基准时刻（合成时间戳锚点；各用例相对它偏移，与墙钟无关）。 */
    private val t0: Long = System.currentTimeMillis() - 1_000_000L

    @After
    fun tearDown() {
        SqlCaptureGate.paused.set(false)
        SqlCaptureGate.epActive = false
        LogTimeCorrelator.clear()
    }

    // ---------- 真实链路夹具（CrossChannelDedupeTest 同款，时间戳可注入） ----------

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

    /** 控制台行 → 条目（真实解析链；时间戳注入为合成值，captureProvenance 与生产 emitEntries 同款）。 */
    private fun consoleEntry(sql: String, consoleParams: String, timestamp: Long): LogSqlEntry {
        SqlCaptureGate.paused.set(false)
        val ctx = newCtx()
        val out = mutableListOf<LogSqlEntry>()
        out += processConsoleLine("==>  Preparing: $sql", ctx, null, {})
        out += processConsoleLine("==> Parameters: $consoleParams", ctx, null, {})
        val entry = out.singleOrNull() ?: throw AssertionError("控制台行未闭合出条目: $sql")
        return entry.copy(timestamp = timestamp, captureProvenance = CaptureProvenance.CONSOLE_BRIDGE)
    }

    /** agent 侧条目（真实适配链；时间戳注入为合成值）。 */
    private fun agentEntry(sql: String, params: List<Triple<Int, String?, String?>>, timestamp: Long): LogSqlEntry =
        AgentEventAdapter.toEntry(
            AgentEvent(
                sql = sql,
                timestamp = timestamp,
                durationNanos = 7L * 1_000_000L,
                updateCount = 1L,
                errorText = null,
                sourceFile = "src/main/java/com/example/UserService.java",
                sourceLine = 42,
                methodKey = "UserService#save",
                requestTag = null,
                params = params,
                threadName = "main",
                url = "jdbc:mysql://localhost:3306/demo",
            ),
        )

    /** 布尔参数族（既有六族分叉之一）：控制台 `true` 直出 / agent `'true'` 加引号 → dedupeKey 分叉、crossKey 同源。 */
    private val forkSql = "INSERT INTO t (flag) VALUES (?)"
    private val forkConsoleParams = "true(Boolean)"
    private val forkAgentParams = listOf(Triple(1, "true", "Boolean"))

    private fun assertForkPremise(console: LogSqlEntry, agent: LogSqlEntry) {
        assertNotEquals("前提：两侧 dedupeKey 分叉（六族形态）", console.dedupeKey, agent.dedupeKey)
        assertEquals("前提：两侧 crossKey 同源（rawSql+参数签名一致）",
            console.rawSql.trim(), agent.rawSql.trim())
    }

    // ---------- 形态一：同执行异指纹 + 超 5s 折叠窗（agent 先到，控制台副本迟到） ----------

    /**
     * 机制②（主控定因）：agent 条目早已落账，控制台副本被平台冲刷延迟 >5s 到达——
     * 修复前 5s 折叠窗错过 → 同一次执行留两行（真机缺陷形态）；修复后越窗兜底折回一行，
     * agent 事实（耗时/位置/行数/库标）不降级，且不虚计 ⨉N（观测合并不是重复丢弃）。
     */
    @Test
    fun `超窗异指纹_agent先到_控制台迟到6s_兜底折回一行`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        val agent = agentEntry(forkSql, forkAgentParams, timestamp = t0)
        assertTrue(buffer.addEntry(agent))
        val console = consoleEntry(forkSql, forkConsoleParams, timestamp = t0 + 6_000)
        assertForkPremise(console, agent)
        // 修复前：5s 窗错过 → 控制台副本新开一行（返回 true）；修复后：越窗兜底 → 折叠
        assertFalse("超窗同源副本应折回既有行（不新开）", buffer.addEntry(console))
        val rows = buffer.snapshot()
        assertEquals("同一次执行只留一行", 1, rows.size)
        val row = rows.single()
        assertEquals("耗时保留 agent 事实（先到者不降级）", 7L, row.executionTimeMs)
        assertEquals("位置保留 agent 事实", "src/main/java/com/example/UserService.java", row.sourceFile)
        assertEquals("行数保留 agent 事实", 1L, row.updateCount)
        assertTrue("库标保留 agent 事实", row.databaseName != null)
        assertEquals("通道归并 MERGED", CaptureProvenance.MERGED, row.captureProvenance)
        assertEquals("观测合并不计 ⨉N（不是重复丢弃）", 0, buffer.dedupeCountsSnapshot().size)
    }

    /**
     * 真机截图的到达顺序（控制台副本是 #1、agent 行带 ⨉123 与它并存）：控制台先到占行号 #1，
     * agent 事件 >5s 后到——修复前同一次执行两行且 #1 永不升级；修复后 agent 事件越窗折进 #1，
     * 行号与捕获时间稳定，字段补齐。
     */
    @Test
    fun `超窗异指纹_控制台先到占行号1_agent迟到6s_折进首行并升级`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        val console = consoleEntry(forkSql, forkConsoleParams, timestamp = t0)
        assertTrue(buffer.addEntry(console))
        assertEquals("控制台副本先入队占行号 #1", 1L, buffer.snapshot().single().sequenceNumber)
        val agent = agentEntry(forkSql, forkAgentParams, timestamp = t0 + 6_000)
        assertForkPremise(console, agent)
        assertFalse("超窗同源 agent 事件应折进首行（不新开）", buffer.addEntry(agent))
        val rows = buffer.snapshot()
        assertEquals("同一次执行只留一行", 1, rows.size)
        val row = rows.single()
        assertEquals("行号稳定（列表不跳行）", 1L, row.sequenceNumber)
        assertEquals("捕获时间保留首条", console.timestamp, row.timestamp)
        assertEquals("耗时由 agent 事件补齐", 7L, row.executionTimeMs)
        assertEquals("位置由 agent 事件补齐", "src/main/java/com/example/UserService.java", row.sourceFile)
        assertEquals(CaptureProvenance.MERGED, row.captureProvenance)
    }

    // ---------- 形态二：超窗同指纹（同 dedupeKey）守卫 ----------

    /**
     * 同 dedupeKey（无分叉）的控制台副本迟到 >5s：既有同键去重窗（60s）本来就会折——
     * 本用例守卫越窗兜底不得把它算两遍或改变计数口径（仍走既有同键分支，计 ⨉N）。
     */
    @Test
    fun `超窗同指纹_同键副本迟到6s_仍走既有同键去重`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        // 无布尔位等分叉族：两侧还原一致 → 同 dedupeKey
        val plainSql = "SELECT * FROM users WHERE id = ?"
        assertTrue(buffer.addEntry(agentEntry(plainSql, listOf(Triple(1, "42", "Long")), timestamp = t0)))
        val console = consoleEntry(plainSql, "42(Long)", timestamp = t0 + 6_000)
        assertEquals("前提：两侧同键（无分叉）", agentEntry(plainSql, listOf(Triple(1, "42", "Long")), t0).dedupeKey, console.dedupeKey)
        assertFalse("同键副本照常被去重折叠", buffer.addEntry(console))
        assertEquals("只留一行", 1, buffer.snapshot().size)
        assertEquals("同键重复照常计 ⨉N（既有口径不变）", 1, buffer.dedupeCountsSnapshot().values.sum())
    }

    // ---------- 安全边界：不误折、不改开关语义 ----------

    /** 越去重窗（60s）的同 crossKey 到达不得折——那是新一轮执行，兜底不扩大去重语义。 */
    @Test
    fun `越去重窗的同源到达_不折_仍开新行`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        assertTrue(buffer.addEntry(agentEntry(forkSql, forkAgentParams, timestamp = t0)))
        val console = consoleEntry(forkSql, forkConsoleParams, timestamp = t0 + 61_000)
        assertTrue("超去重窗 = 新执行，照常入队", buffer.addEntry(console))
        assertEquals(2, buffer.snapshot().size)
    }

    /** 同通道已知标记（控制台↔控制台）超窗不得互折——同通道同 crossKey 异指纹不是两份观测。 */
    @Test
    fun `同通道超窗同源_不折_语义保守`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        // 同为控制台通道的两条（还原器同源，dedupeKey 应一致；这里构造同 crossKey 同通道形态，
        // 断言既有同键去重处理它而不是越窗兜底——通道闸门是双保险）
        assertTrue(buffer.addEntry(consoleEntry(forkSql, forkConsoleParams, timestamp = t0)))
        assertFalse("同通道同键：既有去重照旧", buffer.addEntry(consoleEntry(forkSql, forkConsoleParams, timestamp = t0 + 6_000)))
        assertEquals("同通道不触发越窗兜底（同键去重已折）", 1, buffer.snapshot().size)
    }

    /** 去重关闭态越窗兜底不生效（修复方向明确「去重开启态下」；窗内兜底不受开关约束的既有语义不动）。 */
    @Test
    fun `去重关闭_超窗兜底不生效_新执行各占一行`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        buffer.disableDedupe()
        assertTrue(buffer.addEntry(agentEntry(forkSql, forkAgentParams, timestamp = t0)))
        val console = consoleEntry(forkSql, forkConsoleParams, timestamp = t0 + 6_000)
        assertTrue("去重关闭：越窗兜底不折（超窗副本各占一行）", buffer.addEntry(console))
        assertEquals(2, buffer.snapshot().size)
    }

    /**
     * 接管沿语义回归（P3 不回破）：陈旧键经 grace 截断摘出索引后，agent 首批事件**不得**被
     * 越窗兜底折进桥纪元条目——越窗兜底的候选源是 crossChannelIndex，接管沿重建清掉陈旧键后
     * 天然无候选（本用例钉住这条不变量）。
     */
    @Test
    fun `接管沿截断后_agent首批_不被越窗兜底折进桥纪元条目`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        // 桥纪元条目（ts=t0，分叉族）
        assertTrue(buffer.addEntry(consoleEntry(forkSql, forkConsoleParams, timestamp = t0)))
        // 接管沿：now = t0+30s、grace=5s → 桥纪元键被截断摘出索引（条目仍留队）
        buffer.rebuildDedupeIndexAfterHandoff(graceMs = 5_000, nowMs = t0 + 30_000)
        // agent 首批同源事件（ts=t0+30.5s）：不得被折进 t0 的桥纪元行（P3 可见性语义）
        assertTrue("接管沿后首批 agent 事件照常开新行（越窗兜底不得越过截断）",
            buffer.addEntry(agentEntry(forkSql, forkAgentParams, timestamp = t0 + 30_500)))
        assertEquals("桥纪元行 + agent 首批行", 2, buffer.snapshot().size)
    }

    /** 越窗折叠后，后续同源副本继续折回同一行（crossChannelIndex 重指到保留实例）。 */
    @Test
    fun `越窗折叠后_后续同源副本继续折回同一行`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        assertTrue(buffer.addEntry(agentEntry(forkSql, forkAgentParams, timestamp = t0)))
        assertFalse(buffer.addEntry(consoleEntry(forkSql, forkConsoleParams, timestamp = t0 + 6_000)))
        assertFalse("第二份超窗副本仍折回同一行", buffer.addEntry(consoleEntry(forkSql, forkConsoleParams, timestamp = t0 + 7_000)))
        assertEquals("仍只一行", 1, buffer.snapshot().size)
        assertEquals("仍不计 ⨉N", 0, buffer.dedupeCountsSnapshot().size)
        assertEquals(CaptureProvenance.MERGED, buffer.snapshot().single().captureProvenance)
    }
}
