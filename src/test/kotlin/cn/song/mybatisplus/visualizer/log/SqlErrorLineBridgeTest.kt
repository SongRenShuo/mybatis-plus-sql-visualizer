package cn.song.mybatisplus.visualizer.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-M55-02：控制台链异常行时序绑定测试（Preparing→Parameters→异常行）。
 *
 * 验证 [processConsoleLine] 的错误旁路分支：
 *  - 异常行/堆栈行旁路聚合为错误块文本，经 onErrorText 实时回调（桥层据此调
 *    [SqlLogEntryBuffer.bindErrorToRecent] 反向绑定最近条目）；
 *  - 非错误行为零变化（条目产出与无识别器逐字一致）；
 *  - 错误块被普通行终止；MyBatis `###` 续行不重开块；行数上限收敛。
 */
class SqlErrorLineBridgeTest {

    /**
     * T-CiMatrix（2026-11-04 GitHub Actions 稳定复现）：接管期用例必须先把 [CaptureActivityFallback]
     * 关掉——它按「让位 ≥10s 且控制台仍有 SQL 行」自动解封，CI 的 2 核 runner 跑到断言时通常已过
     * 10s，于是 paused 期间照常产出条目/错误块，断言落空。本地 6 核几百毫秒即过，一直未见。
     */
    @Before
    fun setUp() {
        CaptureActivityFallback.resetForTest()
        CaptureActivityFallback.enabledOverride = false
    }

    @After
    fun tearDown() {
        SqlCaptureGate.paused.set(false)
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
        )
    }

    /** 驱动一行并收集 onErrorText 回调序列。 */
    private fun feed(
        line: String,
        ctx: BridgeLineContext,
        errors: MutableList<String>,
    ): List<LogSqlEntry> = processConsoleLine(line, ctx, null, {}, onErrorText = { errors.add(it) })

    @Test
    fun `Preparing到Parameters到异常行_错误块聚合回调`() {
        val ctx = newCtx()
        val errors = mutableListOf<String>()
        feed("==>  Preparing: INSERT INTO t_user(name) VALUES(?)", ctx, errors)
        val entries = feed("==> Parameters: 'Tom'(String)", ctx, errors)
        assertEquals("参数行闭合 1 条", 1, entries.size)

        feed("org.springframework.dao.DuplicateKeyException: ### Error updating database.  Cause: java.sql.SQLException: Duplicate entry 'Tom' for key 'name'", ctx, errors)
        feed("\tat org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator.translate(SQLErrorCodeSQLExceptionTranslator.java:246)", ctx, errors)
        feed("### The error may exist in file [com/demo/UserMapper.xml]", ctx, errors)
        feed("### SQL: INSERT INTO t_user(name) VALUES(?)", ctx, errors)
        feed("### Cause: java.sql.SQLException: Duplicate entry 'Tom' for key 'name'", ctx, errors)

        assertEquals("5 次实时回调（首行 + 堆栈 + 3 条 ### 续行）", 5, errors.size)
        val final = errors.last()
        assertTrue("首行为异常行原文", final.startsWith("org.springframework.dao.DuplicateKeyException:"))
        assertTrue("堆栈帧聚合在内（续行按 trim 清洗入块）", final.contains("at org.springframework.jdbc"))
        assertTrue("MyBatis ### 续行聚合在内", final.contains("### SQL: INSERT INTO t_user"))
    }

    @Test
    fun `错误行旁路不干扰解析器主链_随后语句照常产出`() {
        val ctx = newCtx()
        val errors = mutableListOf<String>()
        feed("==>  Preparing: SELECT * FROM t1", ctx, errors)
        feed("==> Parameters: 1(Long)", ctx, errors)
        feed("java.sql.SQLException: Unknown column 'x' in 'where clause'", ctx, errors)
        feed("\tat com.mysql.cj.jdbc.PreparedStatement.execute(PreparedStatement.java:1)", ctx, errors)
        // 错误块后新语句照常解析（错误行自身不产出条目、也不阻断下一条）
        feed("==>  Preparing: SELECT * FROM t2", ctx, errors)
        val entries = feed("==> Parameters: 2(Long)", ctx, errors)
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM t2", entries[0].rawSql)
        assertNull("普通行已终止错误块", ctx.pendingError)
    }

    @Test
    fun `MyBatis错误块续行不重开块_首行不丢`() {
        val ctx = newCtx()
        val errors = mutableListOf<String>()
        feed("java.sql.SQLException: Unknown column 'name' in 'field list'", ctx, errors)
        feed("### The error may exist in file [com/demo/UserMapper.xml]", ctx, errors)
        feed("### Cause: java.sql.SQLException: Unknown column 'name' in 'field list'", ctx, errors)
        assertEquals("首行 + 2 续行（### 续行同时命中词表，但不得重开块）", 3, errors.size)
        assertTrue(
            "重开块会丢首行——final 文本必须仍以首行开头",
            errors.last().startsWith("java.sql.SQLException: Unknown column"),
        )
    }

    @Test
    fun `错误块被空行外的普通行终止`() {
        val ctx = newCtx()
        val errors = mutableListOf<String>()
        feed("java.sql.SQLException: boom", ctx, errors)
        feed("\tat a.B.c(B.java:1)", ctx, errors)
        feed("2026-09-16 INFO Started Application in 5.2 seconds", ctx, errors)
        assertNull("普通行终止错误块", ctx.pendingError)
        // 终止后的堆栈行不再聚合
        val before = errors.size
        feed("\tat a.B.d(B.java:2)", ctx, errors)
        assertEquals("块外堆栈行不聚合", before, errors.size)
    }

    @Test
    fun `堆栈行数上限收敛`() {
        val ctx = newCtx()
        val errors = mutableListOf<String>()
        feed("java.sql.SQLException: boom", ctx, errors)
        repeat(SqlErrorLineRecognizer.MAX_ERROR_STACK_LINES + 10) { i ->
            feed("\tat a.B.c(B.java:$i)", ctx, errors)
        }
        val last = errors.last()
        assertEquals(
            "首行 + 最多 ${SqlErrorLineRecognizer.MAX_ERROR_STACK_LINES} 行堆栈",
            SqlErrorLineRecognizer.MAX_ERROR_STACK_LINES + 1,
            last.lineSequence().count(),
        )
    }

    @Test
    fun `无错误行_回调零触发_行为与现状一致`() {
        val ctx = newCtx()
        val errors = mutableListOf<String>()
        feed("==>  Preparing: SELECT * FROM t", ctx, errors)
        val entries = feed("==> Parameters: 1(Long)", ctx, errors)
        assertTrue(errors.isEmpty())
        assertEquals(1, entries.size)
        assertNull(entries[0].errorText)
    }

    @Test
    fun `agent接管期_错误行同样短路不回调`() {
        val ctx = newCtx()
        val errors = mutableListOf<String>()
        SqlCaptureGate.paused.set(true)
        try {
            feed("java.sql.SQLException: boom", ctx, errors)
            assertTrue("接管期桥链路整体短路（agent 通道结构化上报错误）", errors.isEmpty())
        } finally {
            SqlCaptureGate.paused.set(false)
        }
    }
}
