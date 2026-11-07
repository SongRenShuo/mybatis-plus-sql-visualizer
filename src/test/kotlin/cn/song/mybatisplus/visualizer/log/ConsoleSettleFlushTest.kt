package cn.song.mybatisplus.visualizer.log

import com.intellij.execution.filters.Filter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * T-ConsolePendingFlush（O-70）回归测试：按线程分桶后，**语句行之后没有同线程后继行**的语句必须被
 * 静默冲刷交出，否则面板表现为「已扫描 N 行 / 产出 0 条」（用户实测现场：JdbcTemplate 只开 DEBUG、
 * 无 TRACE 参数行 + Tomcat 按请求新建线程 `exec-1`/`exec-2`，每条语句各自独占一个线程）。
 *
 * 用例分层：
 * - 纯逻辑（[processConsoleLine] 直驱 + 注入时钟）：阈值判别力、参数不被截断、同线程即时闭合保绿；
 * - 桥桩控制台（[SqlLogConsoleBridge.attachFilter]）：轮询静默冲刷、旧代际 filter 失活。
 *
 * 行文本照抄用户控制台现场（Spring Boot 默认日志格式）。
 */
class ConsoleSettleFlushTest : BasePlatformTestCase() {

    /** 可控时钟（纯逻辑用例的确定性来源；阈值 1000ms 见 [JdbcTemplateLogParser.DEFAULT_SETTLE_MS]）。 */
    private var fakeNow = 0L

    override fun setUp() {
        super.setUp()
        fakeNow = 0L
        SqlCaptureGate.paused.set(false)
        SqlCaptureGate.epActive = false
    }

    override fun tearDown() {
        SqlCaptureGate.paused.set(false)
        SqlCaptureGate.epActive = false
        super.tearDown()
    }

    // ---------- 现场行文本（Spring Boot 默认格式） ----------

    private fun springBootLine(thread: String, text: String, level: String = "DEBUG"): String =
        "2026-09-23 14:48:06.128 $level 72660 --- [$thread] o.s.jdbc.core.JdbcTemplate               : $text"

    private fun traceParamLine(thread: String, text: String): String =
        "2026-09-23 14:48:06.129 TRACE 72660 --- [$thread] o.s.jdbc.core.StatementCreatorUtils      : $text"

    private val stmt1 =
        "Executing prepared SQL statement [SELECT p.id, p.plan_id FROM biz_project_plan p WHERE p.status = ?]"

    private val stmt2 =
        "Executing prepared SQL statement [SELECT m.id FROM biz_project_member m WHERE m.plan_id = ?]"

    // ---------- 纯逻辑：解析器 + 逐行入口（注入时钟） ----------

    private fun newContext(): BridgeLineContext {
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
            jdbcParser = JdbcTemplateLogParser(
                enabled = cfg.jdbcEnabled,
                maxParseLength = cfg.maxParseLength,
                clock = { fakeNow },
            ),
            configProvider = { cfg },
            hibernateEnabled = cfg.hibernateEnabled,
        )
    }

    private fun feed(ctx: BridgeLineContext, vararg lines: String): List<LogSqlEntry> =
        lines.flatMap { processConsoleLine(it, ctx, null, {}) }

    /**
     * 主场景：两条语句分属 `exec-1`/`exec-2`，都没有 TRACE 参数行、线程此后不再有行。
     * 修复前：永远 0 条（各自停在桶里等一个不存在的后继行）；修复后：静默冲刷交出 2 条。
     */
    fun test逐行冲刷_两线程各一条语句_静默后都产出() {
        val ctx = newContext()
        assertTrue("exec-1 语句行本身不产出（等后继行/等冲刷）", feed(ctx, springBootLine("nio-8086-exec-1", stmt1)).isEmpty())
        assertTrue("exec-2 语句行本身不产出", feed(ctx, springBootLine("nio-8086-exec-2", stmt2)).isEmpty())
        // 阈值判别力：静默未满阈值不得提前产出（此时同线程的参数行仍有到达可能）
        assertEquals("未满静默阈值不得提前产出", 0, ctx.jdbcParser.takeEntries().size)

        fakeNow = JdbcTemplateLogParser.DEFAULT_SETTLE_MS
        val out = feed(ctx, springBootLine("nio-8086-exec-3", "Initializing Servlet 'dispatcherServlet'", level = "INFO"))
        assertEquals("两条各自独占线程的语句都被静默冲刷交出", 2, out.size)
        assertEquals(
            "条目内容与顺序",
            listOf("biz_project_plan", "biz_project_member"),
            out.map { if (it.rawSql.contains("biz_project_plan")) "biz_project_plan" else "biz_project_member" },
        )
        assertTrue("来源标记为控制台 JDBC", out.all { it.source == SqlSource.JDBC })
    }

    /** 参数行在阈值内到达 → 不得被冲刷截断（防「冲刷吃掉参数」）。 */
    fun test参数行在阈值内到达_不被冲刷截断() {
        val ctx = newContext()
        feed(ctx, springBootLine("nio-8086-exec-1", stmt1))
        fakeNow = JdbcTemplateLogParser.DEFAULT_SETTLE_MS / 2
        feed(
            ctx,
            traceParamLine(
                "nio-8086-exec-1",
                "Setting SQL statement parameter value: column index 1, parameter value [7], value class [java.lang.Integer], SQL type unknown",
            ),
        )
        fakeNow = JdbcTemplateLogParser.DEFAULT_SETTLE_MS * 2
        val out = feed(ctx, springBootLine("nio-8086-exec-2", "Executing prepared SQL query"))
        assertEquals("冲刷交出 1 条", 1, out.size)
        assertEquals("参数已随条目还原（未被冲刷截断）", 1, out[0].params.size)
        assertEquals("7", out[0].params[0].value)
        assertTrue("还原 SQL 含参数值", out[0].restoredSql.contains("7"))
    }

    /** 既有即时闭合语义保绿：同线程两条语句，第一条在第二条到达时闭合，不依赖冲刷。 */
    fun test同线程两条语句_第二条到达即闭合第一条() {
        val ctx = newContext()
        feed(ctx, springBootLine("nio-8086-exec-1", stmt1))
        val out = feed(ctx, springBootLine("nio-8086-exec-1", stmt2))
        assertEquals("第一条即时闭合（原语义不变）", 1, out.size)
        assertTrue(out[0].rawSql.contains("biz_project_plan"))
    }

    // ---------- 桥桩控制台：轮询静默冲刷 + 旧代际 filter 失活 ----------

    /** 反射面桩控制台：记录挂载的 filter；getEditor 返回 null（本组用例聚焦产出时机，重放守卫另测）。 */
    private class FakeConsole {
        val attached = mutableListOf<Any>()
        fun addMessageFilter(filter: Filter) {
            attached += filter
        }

        @Suppress("unused")
        fun getEditor(): Any? = null
    }

    private fun newBridgeFilter(buffer: SqlLogEntryBuffer): Pair<SqlLogConsoleBridge, Pair<Filter, FakeConsole>> {
        val bridge = SqlLogConsoleBridge(buffer)
        val console = FakeConsole()
        bridge.attachFilter(console)
        val filter = bridge.attachedConsoles[console]!!.first as Filter
        return bridge to (filter to console)
    }

    /** 应用安静下来（执行完 SQL 后一行都不再打）→ 轮询静默冲刷是唯一出路。 */
    fun test桥轮询冲刷_应用安静后仍产出() {
        val buffer = SqlLogEntryBuffer().apply { dedupeEnabled = false }
        val (bridge, pair) = newBridgeFilter(buffer)
        val filter = pair.first

        filter.applyFilter(springBootLine("nio-8086-exec-1", stmt1), 200)
        filter.applyFilter(springBootLine("nio-8086-exec-2", stmt2), 400)
        assertEquals("轮询前零产出（行已到桥，条目停在各自线程桶里）", 0, buffer.snapshot().size)

        // 走真实轮询入口（scanAll）而不是直调冲刷方法——验的是**接线**：定时器那一拍是否真会冲刷。
        // 静默阈值默认 1s（DEFAULT_SETTLE_MS），故先等待过阈值再走一拍。
        Thread.sleep(JdbcTemplateLogParser.DEFAULT_SETTLE_MS + 150)
        bridge.scanAll()
        assertEquals("静默冲刷交出 2 条", 2, buffer.snapshot().size)

        bridge.scanAll()
        assertEquals("再次轮询不重复产出（桶已清空）", 2, buffer.snapshot().size)
        assertEquals("显式时点直调亦无重复", 0, bridge.flushSettledEntries(System.currentTimeMillis() + 5_000))
    }

    /**
     * T-SqlListCatchup 券 E：桥侧逐行探针接线守卫——经 filter 链驱动的每一行（含噪音行）必须
     * 在 `LogBridge.handleLine` 分布落一枚样本；轮询冲刷拍必须在 `LogBridge.settledBacklog`
     * gauge 落一枚样本且本拍待产出条数入环。反向验证：摘掉 handleConsoleLine 的 measure 包裹
     * 或 flushSettledEntries 的 recordGauge 调用，本用例必红。
     */
    fun test桥侧探针_逐行与冲刷拍读数非空() {
        LogPerfProbe.resetSamples()
        val buffer = SqlLogEntryBuffer().apply { dedupeEnabled = false }
        val (bridge, pair) = newBridgeFilter(buffer)
        val filter = pair.first
        try {
            filter.applyFilter(springBootLine("nio-8086-exec-1", stmt1), 200)
            filter.applyFilter(springBootLine("nio-8086-exec-2", stmt2), 400)
            val lineStats = LogPerfProbe.sampleStats("LogBridge.handleLine")
            assertTrue(
                "逐行探针应每行落样本（2 行 → count ≥2）：$lineStats",
                lineStats != null && lineStats.count >= 2,
            )
            assertTrue("冲刷拍前 gauge 不应存在（未走冲刷）", LogPerfProbe.sampleStats("LogBridge.settledBacklog") == null)

            Thread.sleep(JdbcTemplateLogParser.DEFAULT_SETTLE_MS + 150)
            bridge.scanAll()
            val gauge = LogPerfProbe.sampleStats("LogBridge.settledBacklog")
            assertTrue("冲刷拍应落 gauge 样本：$gauge", gauge != null && gauge.count >= 1)
            assertEquals("本拍待产出 2 条应按原值入环", 2.0, gauge!!.maxMs, 1e-9)
            assertEquals("冲刷产出 2 条", 2, buffer.snapshot().size)
        } finally {
            LogPerfProbe.resetSamples()
        }
    }

    /**
     * 旧代际 filter 失活：2026.2 平台无 `removeMessageFilter`（只有 `clearMessageFilters`），
     * stop→start 留下的旧代理摘不掉；若它按 console 键取到**新**上下文就会与新代理逐行重复解析/计数。
     */
    fun test旧代际filter代理_重挂后不再解析与计数() {
        val buffer = SqlLogEntryBuffer().apply { dedupeEnabled = false }
        val (bridge, pair) = newBridgeFilter(buffer)
        val old = pair.first

        bridge.detachAllFilters() // 平台不支持单 filter 移除：上下文已摘除，旧代理仍在链上
        bridge.attachFilter(pair.second)
        val fresh = bridge.attachedConsoles[pair.second]!!.first as Filter

        val linesBefore = bridge.consoleLinesSeen.get()
        old.applyFilter(springBootLine("nio-8086-exec-1", stmt1), 200)
        assertEquals("旧代际代理不得计数", linesBefore, bridge.consoleLinesSeen.get())
        assertEquals("旧代际代理不得产出条目", 0, buffer.snapshot().size)

        fresh.applyFilter(springBootLine("nio-8086-exec-2", stmt2), 400)
        assertEquals("新代际代理照常计数", linesBefore + 1, bridge.consoleLinesSeen.get())
    }
}
