package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-CaptureCatchAll 券02 / 券10（IDE 侧）：控制台桥活性回落守卫。
 *
 * 守卫目标（调研报告 R10「`paused` 假在线」）：白名单漏一个驱动时 agent 在线却零事件，
 * 控制台桥已被 `paused` 封停 —— **两条通道同时零产出**。本组用例锁住：
 *  - 双哑场景：静默 ≥ 阈值且控制台有 SQL 行 → 解封且条目真的出来；
 *  - 不丢行：解封触发的那一行（嗅探已解析出条目）直接采用，不重复喂解析器；
 *  - 滞回：agent 事件恢复 → 立即重新让位；事件断续时不得反复横跳（≥2 轮采样）；
 *  - 不重复：解封期间 agent 事件同时到达时，缓冲去重折叠为一条；
 *  - 默认开启、系统属性可关（纯降级收益，不引入新采集面）。
 */
class CaptureActivityFallbackTest {

    private var now = 1_000_000L

    private val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000)

    private fun newCtx(): BridgeLineContext = BridgeLineContext(
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

    private fun prep(sql: String) = "==>  Preparing: $sql"
    private fun params(values: String) = "==>  Parameters: $values"

    private fun beginDoubleSilence(thresholdMs: Long = 10_000L) {
        SqlCaptureGate.paused.set(true)
        CaptureActivityFallback.resetForTest()
        CaptureActivityFallback.enabledOverride = true
        CaptureActivityFallback.thresholdOverrideMs = thresholdMs
        CaptureActivityFallback.clockOverride = { now }
        CaptureActivityFallback.onGateChanged(true, now)
    }

    @After
    fun tearDown() {
        SqlCaptureGate.paused.set(false)
        CaptureActivityFallback.resetForTest()
        LogTimeCorrelator.clear()
        System.clearProperty(CaptureActivityFallback.ENABLED_PROP)
        System.clearProperty(CaptureActivityFallback.THRESHOLD_PROP)
    }

    /**
     * 双哑主用例：agent 在线（paused=true）且零事件，控制台持续打 SQL 行。
     * 静默未达阈值时不得解封；达阈值后必须解封并产出条目。
     */
    @Test
    fun `双哑场景_静默达阈值后解封并产出条目`() {
        beginDoubleSilence()
        val ctx = newCtx()
        val produced = mutableListOf<LogSqlEntry>()

        // 0~6s：两轮 SQL，均未达 10s 静默阈值 → 仍让位（条目被嗅探消费，不产出）
        produced += processConsoleLine(prep("SELECT 1 FROM t WHERE id = ?"), ctx, null, {})
        produced += processConsoleLine(params("1(Long)"), ctx, null, {})
        now += 5_000
        produced += processConsoleLine(prep("SELECT 2 FROM t WHERE id = ?"), ctx, null, {})
        produced += processConsoleLine(params("2(Long)"), ctx, null, {})
        assertTrue("静默未达阈值不得解封（条目仍由 agent 通道负责）：$produced", produced.isEmpty())
        assertFalse("未达阈值时回落态必须为 false", CaptureActivityFallback.isActive())
        assertEquals("不得产生解封计数", 0L, CaptureActivityFallback.engagements.get())

        // +10s：静默达阈值且控制台持续有 SQL 行 → 解封
        now += 10_000
        produced += processConsoleLine(prep("SELECT 3 FROM t WHERE id = ?"), ctx, null, {})
        produced += processConsoleLine(params("3(Long)"), ctx, null, {})
        assertEquals("解封后控制台桥应产出条目（双哑被打破）", 1, produced.size)
        assertTrue("解封态应为 true", CaptureActivityFallback.isActive())
        assertEquals("恰一次解封", 1L, CaptureActivityFallback.engagements.get())
    }

    /**
     * 不丢行：解封触发的那一行若恰好解析出条目，必须直接采用（不能「嗅探吃掉 + 下一行才开始解析」，
     * 否则单条 SQL 的 Preparing/Parameters 配对会断，用户少一条）。
     */
    @Test
    fun `解封触发行已解析出条目时直接采用_不丢行`() {
        beginDoubleSilence()
        val ctx = newCtx()
        // t0：首轮 SQL（建立「控制台有 SQL 行」证据）
        processConsoleLine(prep("SELECT 9 FROM t WHERE id = ?"), ctx, null, {})
        processConsoleLine(params("9(Long)"), ctx, null, {})
        assertFalse("首轮不得解封", CaptureActivityFallback.isActive())

        // 静默跨过阈值后，Preparing 行先到达（此时上一行 SQL 已超出窗口 → 不解封，只嗅探）
        now += 10_001
        val first = processConsoleLine(prep("SELECT 10 FROM t WHERE id = ?"), ctx, null, {})
        assertTrue("Preparing 行本身不产出条目：$first", first.isEmpty())
        assertFalse("上一行 SQL 超出窗口时不得解封（避免「控制台没 SQL 也解封」）", CaptureActivityFallback.isActive())

        // Parameters 行闭合条目 → 本行即解封触发点，条目必须直接产出
        now += 1
        val second = processConsoleLine(params("10(Long)"), ctx, null, {})
        assertEquals("解封触发行的条目不得被吞：$second", 1, second.size)
        assertTrue("解封态应为 true", CaptureActivityFallback.isActive())
    }

    /**
     * 滞回：agent 事件一恢复即重新让位；此后静默重新计时，不得立刻再次解封（防反复横跳）。
     */
    @Test
    fun `agent事件恢复立即重新让位_静默重新计时不横跳`() {
        beginDoubleSilence()
        val ctx = newCtx()
        processConsoleLine(prep("SELECT 1 FROM t WHERE id = ?"), ctx, null, {})
        processConsoleLine(params("1(Long)"), ctx, null, {})
        now += 10_000
        processConsoleLine(prep("SELECT 2 FROM t WHERE id = ?"), ctx, null, {})
        processConsoleLine(params("2(Long)"), ctx, null, {})
        assertTrue("先解封", CaptureActivityFallback.isActive())

        // agent 事件恢复（真实链路经 AgentEventAdapter.markReceived 打点）
        AgentEventAdapter.markReceived()
        assertFalse("事件恢复后必须立即重新让位", CaptureActivityFallback.isActive())
        assertEquals("重新让位计数 = 1", 1L, CaptureActivityFallback.releases.get())

        // 事件后 5s / 9s 的 SQL 行：仍让位（静默从事件时刻重算）
        val yielded = mutableListOf<LogSqlEntry>()
        now += 5_000
        yielded += processConsoleLine(prep("SELECT 3 FROM t WHERE id = ?"), ctx, null, {})
        yielded += processConsoleLine(params("3(Long)"), ctx, null, {})
        now += 4_000
        yielded += processConsoleLine(prep("SELECT 4 FROM t WHERE id = ?"), ctx, null, {})
        yielded += processConsoleLine(params("4(Long)"), ctx, null, {})
        assertTrue("事件后未满阈值不得再次解封：$yielded", yielded.isEmpty())
        assertEquals("不得重复解封", 1L, CaptureActivityFallback.engagements.get())
    }

    /**
     * 不抖动（≥2 轮采样）：事件每 5s 来一次、控制台 SQL 持续不断——任何时刻都不得解封。
     * 这是「两条通道并存」的最坏形态，若解封就是双通道双报。
     */
    @Test
    fun `事件断续且控制台持续有SQL_多轮采样均不解封`() {
        beginDoubleSilence()
        val ctx = newCtx()
        repeat(4) { round ->
            now += 5_000
            AgentEventAdapter.markReceived()
            val out = processConsoleLine(prep("SELECT $round FROM t WHERE id = ?"), ctx, null, {}) +
                processConsoleLine(params("$round(Long)"), ctx, null, {})
            assertTrue("第 ${round + 1} 轮：事件在阈值内恢复，不得解封：$out", out.isEmpty())
            assertFalse("第 ${round + 1} 轮：不得进入解封态", CaptureActivityFallback.isActive())
        }
        assertEquals("多轮采样均不得解封", 0L, CaptureActivityFallback.engagements.get())
        assertEquals("不得产生让位/解封切换", 0L, CaptureActivityFallback.releases.get())
    }

    /**
     * 不重复条目：解封期间控制台产出的条目与同时到达的 agent 事件副本，经缓冲去重折叠为一条。
     * （两条通道同指纹 → 列表只有一行，用户不会看到重复。）
     */
    @Test
    fun `解封期间agent事件同时到达_缓冲去重折叠为一条`() {
        beginDoubleSilence()
        val ctx = newCtx()
        processConsoleLine(prep("SELECT 7 FROM t WHERE id = ?"), ctx, null, {})
        processConsoleLine(params("7(Long)"), ctx, null, {})
        now += 10_000
        processConsoleLine(prep("SELECT 7 FROM t WHERE id = ?"), ctx, null, {})
        val bridgeEntries = processConsoleLine(params("7(Long)"), ctx, null, {})
        assertEquals("解封触发行应产出 1 条", 1, bridgeEntries.size)

        val buffer = SqlLogEntryBuffer()
        bridgeEntries.forEach { buffer.addEntry(it.copy(captureProvenance = CaptureProvenance.CONSOLE_BRIDGE)) }
        // agent 事件同时到达：同一 SQL + 同一参数 → 同 dedupeKey
        val event = AgentEvent.parse(
            """{"sql":"SELECT 7 FROM t WHERE id = ?","p":[[1,"7","Long"]],"ts":${System.currentTimeMillis()}}""",
        )!!
        // 真实链路：会话读线程先 markReceived（含回落解除打点），再适配入队
        AgentEventAdapter.markReceived()
        buffer.addEntry(AgentEventAdapter.toEntry(event))
        assertEquals("双通道同指纹必须折叠为一条", 1, buffer.size())

        // 且 agent 事件已把回落态解除（后续行重新让位）
        assertFalse("事件到达即解除解封态", CaptureActivityFallback.isActive())
    }

    @Test
    fun `默认开启_系统属性可关且阈值可配`() {
        CaptureActivityFallback.resetForTest()
        assertTrue("出厂默认开启（纯降级收益）", CaptureActivityFallback.enabled())
        assertEquals("默认阈值 10s", 10_000L, CaptureActivityFallback.thresholdMs())

        System.setProperty(CaptureActivityFallback.ENABLED_PROP, "false")
        assertFalse("系统属性可关闭", CaptureActivityFallback.enabled())
        System.clearProperty(CaptureActivityFallback.ENABLED_PROP)

        System.setProperty(CaptureActivityFallback.THRESHOLD_PROP, "3000")
        assertEquals("阈值可配", 3_000L, CaptureActivityFallback.thresholdMs())
        System.setProperty(CaptureActivityFallback.THRESHOLD_PROP, "not-a-number")
        assertEquals("非法阈值回落默认值", 10_000L, CaptureActivityFallback.thresholdMs())
        System.setProperty(CaptureActivityFallback.THRESHOLD_PROP, "0")
        assertEquals("非正阈值回落默认值", 10_000L, CaptureActivityFallback.thresholdMs())
    }

    @Test
    fun `关闭开关时即使静默超阈值也不解封`() {
        beginDoubleSilence()
        CaptureActivityFallback.enabledOverride = false
        val ctx = newCtx()
        processConsoleLine(prep("SELECT 1 FROM t WHERE id = ?"), ctx, null, {})
        processConsoleLine(params("1(Long)"), ctx, null, {})
        now += 60_000
        val out = processConsoleLine(prep("SELECT 2 FROM t WHERE id = ?"), ctx, null, {}) +
            processConsoleLine(params("2(Long)"), ctx, null, {})
        assertTrue("开关关闭时必须保持让位：$out", out.isEmpty())
        assertFalse("不得进入解封态", CaptureActivityFallback.isActive())
    }

    @Test
    fun `闸门回落沿清空解封态`() {
        beginDoubleSilence()
        val ctx = newCtx()
        processConsoleLine(prep("SELECT 1 FROM t WHERE id = ?"), ctx, null, {})
        processConsoleLine(params("1(Long)"), ctx, null, {})
        now += 10_000
        processConsoleLine(prep("SELECT 2 FROM t WHERE id = ?"), ctx, null, {})
        processConsoleLine(params("2(Long)"), ctx, null, {})
        assertTrue("先解封", CaptureActivityFallback.isActive())

        CaptureActivityFallback.onGateChanged(false, now)
        assertFalse("闸门回落（agent 恢复接管/关闭增强）后解封态必须清空", CaptureActivityFallback.isActive())
    }
}
