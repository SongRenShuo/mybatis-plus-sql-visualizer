package cn.song.mybatisplus.visualizer.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat

/**
 * T-AgentDialectLoop / T-AgentLogTime 嗅探态测试：增强接管（[SqlCaptureGate.paused]=true）期间，
 * [processConsoleLine] 不产出条目，但把「[logTimeKey] → 日志行原始时间」暂存 [LogTimeCorrelator]；
 * agent 事件侧以同键取回（[stampLogTime]；跨通道端到端守卫见 `AgentLogTimeParityTest`）。
 * 纯逻辑测试，不依赖 IDE 环境；全局态（paused/相关器）随用例收尾还原。
 */
class SqlCaptureGateSniffTest {

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    /** 日志行时间取「当前时刻往前 500ms」——与墙钟同源，不依赖相关器时间窗规则。 */
    private fun recentLineTime(): String = sdf.format(java.util.Date(System.currentTimeMillis() - 500))

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

    /** 期望键：与嗅探侧同源（占位符版 SQL 指纹），由 [logTimeKey] 唯一口径产出。 */
    private fun keyOf(rawSql: String): String = LogSqlModelsKt.logTimeKey(
        LogSqlEntry(rawSql, rawSql, emptyList(), SqlSource.MYBATIS),
    )

    @Test
    fun `嗅探态_零条目产出_日志时间进相关器`() {
        SqlCaptureGate.paused.set(true)
        val ctx = newCtx()
        val ts = recentLineTime()
        val entries = listOf(
            "$ts [main] ==>  Preparing: SELECT * FROM user WHERE id = ?",
            "$ts [main] ==>  Parameters: 1(Long)",
        ).flatMap { SqlLogConsoleBridgeKt.processConsoleLine(it, ctx, null, {}) }
        assertTrue("接管期间控制台链不产出条目", entries.isEmpty())
        assertEquals(1, LogTimeCorrelator.size())
        val got = LogTimeCorrelator.take(keyOf("SELECT * FROM user WHERE id = ?"), "1")!!
        assertEquals("回填的是日志行原始时间（Preparing 行）", sdf.parse(ts).time, got)
    }

    @Test
    fun `未接管时正常产出条目_不写相关器`() {
        val ctx = newCtx()
        val entries = listOf(
            "==>  Preparing: SELECT * FROM user WHERE id = ?",
            "==>  Parameters: 1(Long)",
        ).flatMap { SqlLogConsoleBridgeKt.processConsoleLine(it, ctx, null, {}) }
        assertEquals("回落态控制台链照常捕获", 1, entries.size)
        assertEquals("正常捕获路径不写相关器", 0, LogTimeCorrelator.size())
    }

    @Test
    fun `无日志时间戳的行_不把IDE解析时间入册`() {
        SqlCaptureGate.paused.set(true)
        val ctx = newCtx()
        listOf(
            "==>  Preparing: SELECT * FROM t WHERE id = ?",
            "==>  Parameters: 9(Long)",
        ).forEach { SqlLogConsoleBridgeKt.processConsoleLine(it, ctx, null, {}) }
        assertEquals("无真实日志时间不能制造候选", 0, LogTimeCorrelator.size())
        val ts = LogTimeCorrelator.take(keyOf("SELECT * FROM t WHERE id = ?"), "9")
        assertNull("无真实日志时间时未命中，由agent保持执行开始时间", ts)
    }

    @Test
    fun `take未命中返回null_回落捕获时间语义`() {
        SqlCaptureGate.paused.set(true)
        LogTimeCorrelator.clear()
        assertNull(LogTimeCorrelator.take(keyOf("SELECT 1")))
    }
}
