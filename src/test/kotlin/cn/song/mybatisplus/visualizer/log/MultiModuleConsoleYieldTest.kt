package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-MultiModuleConsoleYield：让位的行级进程归属（多模块并行修复）。
 *
 * 现场读数（2026-11-21 15:35 沙盒）：两模块并行、只挂一个 agent 时，另一模块的 MyBatis 三段
 * 日志全进「让位丢弃」，面板 0 条。修复 = 让位分支按**行前缀 PID 字段**归属：pid ∈ 健康会话集
 * → 既有让位（防双报）；pid ∉ 集 → 照常解析（修复点）；提不出 pid → 保持全局让位（宁丢勿重）。
 *
 * 反向验证：git 补丁摘掉归属分支（恢复无条件让位）→ 「pid ∉ 集」用例必红（回归全局吞）。
 */
class MultiModuleConsoleYieldTest : BasePlatformTestCase() {

    private var fakeNow = 0L

    override fun setUp() {
        super.setUp()
        SqlCaptureGate.paused.set(false)
        SqlCaptureGate.epActive = false
        SqlCaptureGate.liveSessionPids.clear()
        SqlCaptureGate.suppressedSqlLines.set(0)
    }

    override fun tearDown() {
        SqlCaptureGate.paused.set(false)
        SqlCaptureGate.epActive = false
        SqlCaptureGate.liveSessionPids.clear()
        SqlCaptureGate.suppressedSqlLines.set(0)
        super.tearDown()
    }

    // ---------- logLinePid：行前缀 PID 提取 ----------

    @Test
    fun testpid提取_现场两种日志形态() {
        // 用户控制台原文形态（日期 + 时间两段前缀）
        assertEquals(
            52324L,
            SqlCaptureGate.logLinePid(
                "2026-11-21 15:35:42.823  INFO 52324 --- [nio-8090-exec-1] o.s.web.servlet.DispatcherServlet        : init",
            ),
        )
        // 仅时间一段前缀
        assertEquals(
            48216L,
            SqlCaptureGate.logLinePid("14:41:06.810  INFO 48216 --- [main] c.s.j.DemoApplication : Started"),
        )
        // DEBUG 级（MyBatis 三段所在级别）
        assertEquals(
            99999L,
            SqlCaptureGate.logLinePid(
                "2026-11-21 15:35:42.823 DEBUG 99999 --- [nio-8090-exec-1] c.s.j.mapper.UserMapper.selectList       : ==>  Preparing: SELECT 1",
            ),
        )
    }

    @Test
    fun testpid提取_非日志形态返回null() {
        assertNull("裸 SQL 无前缀不可归属", SqlCaptureGate.logLinePid("==>  Preparing: SELECT 1"))
        assertNull("纯文本不可归属", SqlCaptureGate.logLinePid("Started Jdk16DemoApplication in 4.297 seconds"))
        assertNull("小写级别不是日志前缀", SqlCaptureGate.logLinePid("2026-11-21 15:35:42 info 52324 --- [t] x : y"))
        assertNull("空串", SqlCaptureGate.logLinePid(""))
    }

    // ---------- 让位的行级归属（端到端：processConsoleLine 直驱） ----------

    private fun newContext(): BridgeLineContext {
        val cfg = PluginSettings.getInstance().state
        return BridgeLineContext(
            myBatisParser = MyBatisLogParser(
                sqlPrefix = cfg.logSqlPrefix,
                paramPrefix = cfg.logParamPrefix,
                mixedPrefix = cfg.logMixedPrefix,
                maxParseLength = cfg.logMaxParseLength,
                clock = { fakeNow },
            ),
            hibernateParser = HibernateLogParser(
                sqlPrefix = cfg.logHibernateSqlPrefix,
                paramPrefix = cfg.logHibernateParamPrefix,
                mixedPrefix = cfg.logMixedPrefix,
                maxParseLength = cfg.logMaxParseLength,
            ),
            jdbcParser = JdbcTemplateLogParser(
                enabled = cfg.logJdbcEnabled,
                maxParseLength = cfg.logMaxParseLength,
            ),
            configProvider = {
                ParserConfig(
                    sqlPrefix = cfg.logSqlPrefix,
                    paramPrefix = cfg.logParamPrefix,
                    mixedPrefix = cfg.logMixedPrefix,
                    maxParseLength = cfg.logMaxParseLength,
                    jdbcEnabled = cfg.logJdbcEnabled,
                )
            },
            hibernateEnabled = cfg.logHibernateEnabled,
        )
    }

    /** 用户现场行形态：Spring Boot 默认前缀 + MyBatis 三段（Parameters 带值 → 行级同步闭合，避免静默冲刷依赖）。 */
    private fun mybatisLine(pid: Int, text: String): String =
        "2026-11-21 15:35:42.823 DEBUG $pid --- [nio-8090-exec-1] c.s.j.mapper.UserMapper.selectList       : $text"

    private val sql = "SELECT `id`, `name`, `email`, `age`, `status`, `created_time` FROM `user` ORDER BY `id` DESC"

    private fun trio(pid: Int) = listOf(
        mybatisLine(pid, "==>  Preparing: $sql"),
        mybatisLine(pid, "==> Parameters: 1(Long)"),
        mybatisLine(pid, "<==      Total: 2"),
    )

    private fun feed(ctx: BridgeLineContext, lines: List<String>): List<LogSqlEntry> =
        lines.flatMap { processConsoleLine(it, ctx, null, {}) }

    @Test
    fun test有agent会话的pid_让位丢弃并计数() {
        SqlCaptureGate.paused.set(true)
        SqlCaptureGate.liveSessionPids.clear()
        SqlCaptureGate.liveSessionPids.add(52324L)
        val before = SqlCaptureGate.suppressedSqlLines.get()
        val ctx = newContext()

        val out = feed(ctx, trio(52324))

        assertTrue("挂 agent 进程的行仍按既有语义让位（零条目）", out.isEmpty())
        assertEquals(
            "Parameters 行闭合条目时嗅探产出 → 让位丢弃计数 +1（F1 可见化口径）",
            before + 1, SqlCaptureGate.suppressedSqlLines.get(),
        )
    }

    @Test
    fun test无agent的pid_照常解析产出() {
        SqlCaptureGate.paused.set(true)
        SqlCaptureGate.liveSessionPids.clear()
        SqlCaptureGate.liveSessionPids.add(52324L) // 另一模块挂了 agent
        val ctx = newContext()

        val out = feed(ctx, trio(99999))

        assertTrue(
            "无 agent 模块的 MyBatis 三段必须照常产出（修复点；回归即全局让位吞行）",
            out.isNotEmpty(),
        )
        assertTrue(
            "产出条目的 SQL 应为无 agent 模块的语句",
            out.any { it.rawSql.contains("SELECT") },
        )
    }

    @Test
    fun test提不出pid的行保持全局让位() {
        SqlCaptureGate.paused.set(true)
        SqlCaptureGate.liveSessionPids.clear()
        SqlCaptureGate.liveSessionPids.add(52324L)
        val before = SqlCaptureGate.suppressedSqlLines.get()
        val ctx = newContext()

        // 无前缀形态（提不出 pid）：保守面钉住——保持既有全局让位
        val out = feed(ctx, listOf("==>  Preparing: $sql"))

        assertTrue(out.isEmpty())
        assertEquals(before, SqlCaptureGate.suppressedSqlLines.get()) // 该行连嗅探都不产出条目，计数不动
        assertTrue("归属不了的行不得产出", ctx.myBatisParser.takeEntries().isEmpty())
    }
}
