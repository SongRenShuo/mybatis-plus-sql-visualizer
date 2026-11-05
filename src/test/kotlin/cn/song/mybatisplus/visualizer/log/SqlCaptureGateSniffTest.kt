package cn.song.mybatisplus.visualizer.log

import org.junit.After
import org.junit.Before
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
 *
 * T-CiMatrix（2026-11-04 GitHub Actions 稳定复现）：本类与前后的用例都要先把
 * [CaptureActivityFallback] 关掉。它按「让位持续 ≥10s 且控制台仍有 SQL 行」自动解封控制台桥，
 * 而本类的用例会喂进真实 SQL 行——CI 的 2 核 runner 跑到断言时往往已过 10s，fallback 判为
 * 「agent 静默但控制台有 SQL」并解封，于是 paused 期间照常产出条目，断言「不产出」失败。
 * 本地 6 核几百毫秒就过阈值，所以一直是绿的。注入 enabledOverride=false 让 fallback 不参与。
 */
class SqlCaptureGateSniffTest {

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    /** 日志行时间取「当前时刻往前 500ms」——与墙钟同源，不依赖相关器时间窗规则。 */
    private fun recentLineTime(): String = sdf.format(java.util.Date(System.currentTimeMillis() - 500))

    @Before
    fun setUp() {
        // T-SniffGlobalIsolation（2026-11-01 门禁实证）：`bridgeSuppressed() = paused && !active`，
        // 而 `CaptureActivityFallback.active` 是**全局单例态**——同分片里先跑的回落链用例把它置
        // 解封态后，本用例只置 paused 会让封停判定失效（条目照常产出，断言红）。
        // 用例自证前提：回落态必须由本用例自己置干净，不继承任何前序残留；回落活性全程关断
        //（r36 门禁教训：2 核 CI 放大时间阈值假红，防回落中途解封）。
        CaptureActivityFallback.resetForTest()
        CaptureActivityFallback.enabledOverride = false
    }

    @After
    fun tearDown() {
        SqlCaptureGate.paused.set(false)
        LogTimeCorrelator.clear()
        // resetForTest 会一并清掉 enabledOverride/thresholdOverrideMs/clockOverride，不污染同分片其它用例。
        CaptureActivityFallback.resetForTest()
    }

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

    /** 期望键：与嗅探侧同源（占位符版 SQL 指纹），由 [logTimeKey] 唯一口径产出。 */
    private fun keyOf(rawSql: String): String = logTimeKey(
        LogSqlEntry(rawSql = rawSql, restoredSql = rawSql, params = emptyList(), source = SqlSource.MYBATIS),
    )

    @Test
    fun `嗅探态_零条目产出_日志时间进相关器`() {
        SqlCaptureGate.paused.set(true)
        val ctx = newCtx()
        val ts = recentLineTime()
        val entries = listOf(
            "$ts [main] ==>  Preparing: SELECT * FROM user WHERE id = ?",
            "$ts [main] ==>  Parameters: 1(Long)",
        ).flatMap { processConsoleLine(it, ctx, null, {}) }
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
        ).flatMap { processConsoleLine(it, ctx, null, {}) }
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
        ).forEach { processConsoleLine(it, ctx, null, {}) }
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
