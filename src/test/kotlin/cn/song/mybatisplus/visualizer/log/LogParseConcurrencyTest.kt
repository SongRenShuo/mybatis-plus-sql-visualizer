package cn.song.mybatisplus.visualizer.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat

/**
 * T-LogParseConc 并发配对回归守卫（用户实测驱动，2026-09-16）。
 *
 * 用户报「并发情况下 SQL 日志严重不正确」，附控制台原文（12 个 Tomcat 线程交错、MP 分页
 * `selectPage` + `selectPage_mpCount` 成对）。诊断探针实证两个缺陷，本用例把它钉成断言：
 *
 * 1. `MyBatisLogParser` 单槽状态机无线程分桶——另一线程的语句行会提前闭合本线程尚未收到参数的
 *    语句，本线程随后到达的参数行因无上下文被丢弃（参数全丢、`?` 未还原）；
 * 2. 空 `Parameters:` 行的「等下一行」态跨语句残留——把下一行整行吞成参数文本（含另一线程
 *    完整的 `Parameters:` 行），面板于是出现 `LIMIT 2026-09-16 14:35:00.413 DEBUG …` 这类文字。
 *
 * 全部为纯逻辑用例（不依赖 IDE 环境）；时间断言按系统时区现算，不写死毫秒常量。
 */
class LogParseConcurrencyTest {

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    private fun ms(text: String): Long = sdf.parse(text).time

    private fun myBatis() = MyBatisLogParser(
        sqlPrefix = "Preparing:", paramPrefix = "Parameters:", mixedPrefix = true, maxParseLength = 20000,
    )

    @After
    fun tearDown() {
        LogParseDiag.reset()
    }

    /** 用户控制台原文的最小复现序列（selectPage 与其 mpCount 成对 + 空 Parameters 行）。 */
    @Test
    fun `空Parameters行不再吞掉下一行_两条语句各带自己的参数`() {
        val p = myBatis()
        listOf(
            "2026-09-16 14:34:59.938 DEBUG 36516 [http-nio-8088-exec-9] UserMapper.selectPage [] - ==>  Preparing: SELECT id,username,password FROM user ORDER BY id DESC LIMIT ?",
            "2026-09-16 14:34:59.939 DEBUG 36516 [http-nio-8088-exec-2] UserMapper.selectPage_mpCount [] - ==>  Preparing: SELECT COUNT(*) AS total FROM user",
            "2026-09-16 14:34:59.939 DEBUG 36516 [http-nio-8088-exec-2] UserMapper.selectPage_mpCount [] - ==> Parameters: ",
            "2026-09-16 14:34:59.940 DEBUG 36516 [http-nio-8088-exec-9] UserMapper.selectPage [] - ==> Parameters: 10(Long)",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        assertEquals("两条语句各产出一条，不丢条目", 2, entries.size)
        val count = entries.first { it.rawSql.contains("COUNT(*)") }
        val page = entries.first { it.rawSql.contains("LIMIT ?") }

        assertTrue("空参语句（COUNT）参数为空", count.params.isEmpty())
        assertEquals("参数值不得再是整行日志", listOf("10"), page.params.map { it.value })
        assertEquals(
            "LIMIT ? 必须还原为字面量",
            "SELECT id,username,password FROM user ORDER BY id DESC LIMIT 10",
            page.restoredSql,
        )
        assertTrue(
            "SQL 正文不得混入日志行（是否含 DEBUG/==>）",
            entries.none { it.restoredSql.contains("DEBUG") || it.restoredSql.contains("==>") },
        )
    }

    /** 空 `Parameters:` 行之后是同线程日志行：判别式拦下（旧实现会把整行吞成参数值）。 */
    @Test
    fun `空Parameters行后跟同线程日志行_拦下不吞`() {
        val p = myBatis()
        listOf(
            "2026-09-16 10:00:00.100 DEBUG 36516 [exec-2] UserMapper.selectPage_mpCount [] - ==>  Preparing: SELECT COUNT(*) AS total FROM user",
            "2026-09-16 10:00:00.101 DEBUG 36516 [exec-2] UserMapper.selectPage_mpCount [] - ==> Parameters: ",
            "2026-09-16 10:00:00.102 DEBUG 36516 [exec-2] UserMapper.selectPage_mpCount [] - <==      Total: 1",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        assertEquals("无参语句照常产出条目", 1, entries.size)
        assertTrue("参数必须为空（整行 Total 日志不得成为参数值）", entries[0].params.isEmpty())
        assertEquals("SELECT COUNT(*) AS total FROM user", entries[0].restoredSql)
        assertTrue("等待态拦下计数递增", LogParseDiag.swallowedLineBlocked.get() >= 1L)
    }

    /** 纯交错（无空行）：后到线程的参数不得丢，也不得错挂到别人身上。 */
    @Test
    fun `并发交错_参数各归其主`() {
        val p = myBatis()
        listOf(
            "2026-09-16 14:34:59.938 DEBUG 36516 [exec-9] UserMapper.selectPage [] - ==>  Preparing: SELECT id FROM user WHERE name = ? AND id = ?",
            "2026-09-16 14:34:59.938 DEBUG 36516 [exec-2] UserMapper.selectPage_mpCount [] - ==>  Preparing: SELECT id FROM t WHERE a = ?",
            "2026-09-16 14:34:59.939 DEBUG 36516 [exec-2] UserMapper.selectPage_mpCount [] - ==> Parameters: 999(Long)",
            "2026-09-16 14:34:59.940 DEBUG 36516 [exec-9] UserMapper.selectPage [] - ==> Parameters: abc(String), 42(Long)",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        assertEquals(2, entries.size)
        val twoParam = entries.first { it.rawSql.contains("name = ?") }
        val oneParam = entries.first { it.rawSql.contains("FROM t WHERE a = ?") }
        assertEquals("exec-9 的两个参数不丢", 2, twoParam.params.size)
        assertEquals(listOf("abc", "42"), twoParam.params.map { it.value })
        assertEquals(
            "exec-9 的 SQL 用 exec-9 的参数还原",
            "SELECT id FROM user WHERE name = 'abc' AND id = 42",
            twoParam.restoredSql,
        )
        assertEquals("exec-2 的参数不被 exec-9 抢走", listOf("999"), oneParam.params.map { it.value })
    }

    /** 空 `Parameters:` 行之后确实是「裸参数文本行」时，旧兼容语义不变（不能一刀切）。 */
    @Test
    fun `空Parameters行后跟裸参数文本_维持兼容语义`() {
        val p = myBatis()
        listOf(
            "2026-09-16 14:34:59.938 DEBUG 36516 [exec-2] UserMapper.selectPage [] - ==>  Preparing: SELECT id FROM user WHERE id = ?",
            "2026-09-16 14:34:59.938 DEBUG 36516 [exec-2] UserMapper.selectPage [] - ==> Parameters: ",
            "42(Long)",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        assertEquals(1, entries.size)
        assertEquals(listOf("42"), entries[0].params.map { it.value })
        assertEquals("SELECT id FROM user WHERE id = 42", entries[0].restoredSql)
    }

    /** 空 `Parameters:` 行之后跟的是另一线程语句头：该语句头不得被吞（它承载的 SQL 照常产出）。 */
    @Test
    fun `空Parameters行后跟另一线程语句头_不吞行`() {
        val p = myBatis()
        listOf(
            "2026-09-16 14:34:59.938 DEBUG 36516 [exec-2] UserMapper.selectPage_mpCount [] - ==>  Preparing: SELECT COUNT(*) AS total FROM user",
            "2026-09-16 14:34:59.939 DEBUG 36516 [exec-2] UserMapper.selectPage_mpCount [] - ==> Parameters: ",
            "2026-09-16 14:34:59.940 DEBUG 36516 [exec-9] UserMapper.selectPage [] - ==>  Preparing: SELECT id FROM user WHERE id = ?",
            "2026-09-16 14:34:59.941 DEBUG 36516 [exec-9] UserMapper.selectPage [] - ==> Parameters: 7(Long)",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        assertEquals("两条语句都在（旧实现会吞掉第二条的语句头）", 2, entries.size)
        val page = entries.first { it.rawSql.contains("WHERE id = ?") }
        assertEquals("SELECT id FROM user WHERE id = 7", page.restoredSql)
        assertTrue("无条目混入日志行", entries.none { it.restoredSql.contains("DEBUG") })
    }

    /** 多行 SQL 续行（T-LPC-07）：mapper 里格式化的 SQL 不再只留首行。 */
    @Test
    fun `多行SQL续行_并入同一语句`() {
        val p = myBatis()
        listOf(
            "2026-09-16 10:00:00.100 DEBUG 36516 [exec-1] UserMapper.selectPage [] - ==>  Preparing: SELECT id, name",
            "  FROM user",
            " WHERE id = ?",
            "2026-09-16 10:00:00.101 DEBUG 36516 [exec-1] UserMapper.selectPage [] - ==> Parameters: 7(Long)",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        assertEquals(1, entries.size)
        assertEquals("SELECT id, name\n  FROM user\n WHERE id = ?", entries[0].rawSql)
        assertEquals("SELECT id, name\n  FROM user\n WHERE id = 7", entries[0].restoredSql)
        assertEquals("日志时间取语句首行", ms("2026-09-16 10:00:00.100"), entries[0].logTimestamp)
    }

    /** 续行遇日志行即终止：紧随的业务日志行不得被并进 SQL。 */
    @Test
    fun `续行遇日志行终止_不吞后续业务日志`() {
        val p = myBatis()
        listOf(
            "2026-09-16 10:00:00.100 DEBUG 36516 [exec-1] UserMapper.selectPage [] - ==>  Preparing: SELECT id FROM user",
            "2026-09-16 10:00:00.101 INFO 36516 [exec-1] o.s.SomeService - 业务日志一行",
            "2026-09-16 10:00:00.102 DEBUG 36516 [exec-1] UserMapper.selectPage [] - ==> Parameters: ",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        assertEquals(1, entries.size)
        assertEquals("SELECT id FROM user", entries[0].rawSql)
        assertTrue("业务日志行未被并入 SQL", !entries[0].rawSql.contains("业务日志"))
    }

    /** 无参语句（无 Parameters 行）：另一线程语句到来即闭合，参数保持为空、时间取自己的语句行。 */
    @Test
    fun `无参语句由后续语句闭合_时间不被污染`() {
        val p = myBatis()
        listOf(
            "2026-09-16 10:00:00.100 DEBUG 36516 [exec-1] UserMapper.selectPage [] - ==>  Preparing: SELECT id FROM user WHERE id = 42",
            "2026-09-16 10:00:00.500 DEBUG 36516 [exec-1] UserMapper.selectPage [] - ==>  Preparing: SELECT count(*) FROM user",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        assertEquals(2, entries.size)
        assertEquals("无参语句参数为空", 0, entries[0].params.size)
        assertEquals("第一条时间取自己的语句行", ms("2026-09-16 10:00:00.100"), entries[0].logTimestamp)
        assertEquals(ms("2026-09-16 10:00:00.500"), entries[1].logTimestamp)
    }

    /** 参数行无上下文时留痕（旧实现完全静默）。 */
    @Test
    fun `参数行无上下文_计数留痕`() {
        val p = myBatis()
        p.feed("2026-09-16 10:00:00.100 DEBUG 36516 [exec-1] UserMapper.selectPage [] - ==> Parameters: 7(Long)")
        assertTrue("无上下文参数丢弃计数递增", LogParseDiag.paramWithoutContext.get() >= 1L)
        assertNull("无 SQL 上下文时不产出条目", p.closePending())
    }

    /** JdbcTemplate 多行 SQL（T-LPC-04）：条目时间取收集首行，不再回落捕获时间。 */
    @Test
    fun `Jdbc多行SQL_日志时间取首行`() {
        val p = JdbcTemplateLogParser(enabled = true, maxParseLength = 20000)
        listOf(
            "2026-09-16 14:19:13.387 [TID: FED5CD4B] [http-nio-0.0.0.0-6608-exec-5] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:643 - Executing prepared SQL statement [SELECT",
            "    T.id AS businessId,",
            "    T.name AS businessName",
            "  FROM t_business T",
            "  WHERE T.status = 1]",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        assertEquals(1, entries.size)
        assertEquals(
            "多行条目的日志时间 = 首行时间（旧实现恒 null → 回落捕获时间）",
            ms("2026-09-16 14:19:13.387"),
            entries[0].logTimestamp,
        )
        assertTrue(
            "多行 SQL 内容完整保留",
            entries[0].rawSql.contains("businessName") && entries[0].rawSql.contains("WHERE T.status = 1"),
        )
    }

    /** JdbcTemplate 单行 SQL：时间解析对照组（防修反）。 */
    @Test
    fun `Jdbc单行SQL_日志时间正常`() {
        val p = JdbcTemplateLogParser(enabled = true, maxParseLength = 20000)
        listOf(
            "2026-09-16 14:19:13.387 [TID: FED5CD4B] [http-nio-8088-exec-5] DEBUG org.springframework.jdbc.core.JdbcTemplate Line:643 - Executing prepared SQL statement [SELECT * FROM t WHERE id = ?]",
            "2026-09-16 14:19:13.388 [TID: FED5CD4B] [http-nio-8088-exec-5] DEBUG org.springframework.jdbc.core.StatementCreatorUtils - Setting SQL statement parameter value: column index 1, parameter value [7], value class [java.lang.Integer], SQL type unknown",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        assertEquals(1, entries.size)
        assertEquals(ms("2026-09-16 14:19:13.387"), entries[0].logTimestamp)
        assertEquals("SELECT * FROM t WHERE id = 7", entries[0].restoredSql)
    }

    // ---------- T-SAF-12（log.threadrouter.failopen-fallback-cross-thread-param-mixing）：兜底桶错配 ----------

    /**
     * 多来源控制台（近期出现过 ≥2 个线程 token）下的无 token 交错流：兜底桶配对必须延迟处置，
     * 不得把 A 的参数还原进 B 的语句。
     *
     * 复现证据（NEEDS-VALIDATION #12，审计边界/入点 threadTokenOf 的 fail-open）：裸
     * `==> Preparing:` / `==> Parameters:` 行（stdout MyBatis 形态，无级别关键字）全部落共享
     * 兜底桶，A/B 两线程的语句在其中交错——修复前 B 的 SQL 被用 A 的参数还原
     * （`INSERT INTO b VALUES (1)`，参数直达执行链），断言转红。
     */
    @Test
    fun `多来源控制台下无token行配对不再错配`() {
        val p = myBatis()
        listOf(
            "2026-09-16 10:00:00.000 INFO [exec-1] app - booted",
            "2026-09-16 10:00:00.001 INFO [exec-2] app - booted",
            "==>  Preparing: SELECT * FROM a WHERE id = ?",
            "==>  Preparing: INSERT INTO b VALUES (?)",
            "==> Parameters: 1(Long)",
            "==> Parameters: 9(Long)",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        val b = entries.first { it.rawSql.contains("INSERT INTO b") }
        assertTrue(
            "B 的语句不得用 A 的参数还原（修复前 restoredSql=${b.restoredSql}）",
            b.params.isEmpty() || b.restoredSql == b.rawSql,
        )
        val a = entries.first { it.rawSql.contains("FROM a WHERE") }
        assertTrue("A 的语句同样不得被错配还原", a.params.isEmpty() || a.restoredSql == a.rawSql)
    }

    /**
     * 纯裸流（全程无 token）的交错：第一轮无可判定信号（信息论边界——首条错配即探测信号），
     * 第二轮起兜底桶已自学「多来源疑似」，同形交错不得再产出错配还原。
     */
    @Test
    fun `纯裸流第二轮交错起不再错配`() {
        val p = myBatis()
        // 第一轮：建立「参数行无上下文 + 刚发生过错配」的自学信号
        listOf(
            "==>  Preparing: SELECT * FROM a WHERE id = ?",
            "==>  Preparing: INSERT INTO b VALUES (?)",
            "==> Parameters: 1(Long)",
            "==> Parameters: 9(Long)",
        ).forEach { p.feed(it) }
        // 第二轮：同形交错（不同 SQL/参数值）
        listOf(
            "==>  Preparing: SELECT * FROM c WHERE id = ?",
            "==>  Preparing: DELETE FROM d WHERE id = ?",
            "==> Parameters: 2(Long)",
            "==> Parameters: 8(Long)",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        val d = entries.first { it.rawSql.contains("DELETE FROM d") }
        assertTrue(
            "第二轮 D 的语句不得用 C 的参数还原（修复前 restoredSql=${d.restoredSql}）",
            d.params.isEmpty() || d.restoredSql == d.rawSql,
        )
    }

    /** 对照组：单一流（无多来源信号）裸行配对行为不变——两条语句各自正确还原。 */
    @Test
    fun `单一流裸行配对行为不变`() {
        val p = myBatis()
        listOf(
            "==>  Preparing: SELECT * FROM a WHERE id = ?",
            "==> Parameters: 1(Long)",
            "==>  Preparing: INSERT INTO b VALUES (?)",
            "==> Parameters: 9(Long)",
        ).forEach { p.feed(it) }
        p.closePending()
        val entries = p.takeEntries()

        assertEquals(2, entries.size)
        assertEquals("SELECT * FROM a WHERE id = 1", entries[0].restoredSql)
        assertEquals("INSERT INTO b VALUES (9)", entries[1].restoredSql)
    }
}
