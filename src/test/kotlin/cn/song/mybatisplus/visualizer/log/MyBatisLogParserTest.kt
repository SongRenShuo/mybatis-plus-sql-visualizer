package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志行解析与参数配对单元测试（T-M31-01）。
 */
class MyBatisLogParserTest {

    private fun parser() = MyBatisLogParser()

    private fun feedAll(p: MyBatisLogParser, vararg lines: String): List<LogSqlEntry> {
        lines.forEach { p.feed(it) }
        p.closePending()
        return p.takeEntries()
    }

    @Test
    fun `MP 前缀格式配对 Preparing 与 Parameters`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing: SELECT id, name FROM user WHERE id = ? AND status = ?",
            "==> Parameters: 1(Long), active(String)",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT id, name FROM user WHERE id = 1 AND status = 'active'", entries[0].restoredSql)
        assertEquals(2, entries[0].params.size)
        assertEquals("1", entries[0].params[0].value)
        assertEquals("Long", entries[0].params[0].type)
    }

    @Test
    fun `MyBatis 原生格式无前缀配对`() {
        val entries = feedAll(
            parser(),
            "Preparing: DELETE FROM t WHERE id = ?",
            "Parameters: 7(Integer)",
        )
        assertEquals(1, entries.size)
        assertEquals("DELETE FROM t WHERE id = 7", entries[0].restoredSql)
    }

    @Test
    fun `带日志框架前缀的行按关键字定位`() {
        val entries = feedAll(
            parser(),
            "2026-08-15 10:00:00.123  INFO 1234 --- [main] c.s.m.Log : ==>  Preparing: SELECT * FROM user WHERE id = ?",
            "2026-08-15 10:00:00.124  INFO 1234 --- [main] c.s.m.Log : ==> Parameters: 42(Long)",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM user WHERE id = 42", entries[0].restoredSql)
    }

    @Test
    fun `多条语句连续配对`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing: SELECT * FROM a WHERE x = ?",
            "==> Parameters: 1(Integer)",
            "==>  Preparing: SELECT * FROM b WHERE y = ?",
            "==> Parameters: 2(Integer)",
        )
        assertEquals(2, entries.size)
        assertEquals("SELECT * FROM a WHERE x = 1", entries[0].restoredSql)
        assertEquals("SELECT * FROM b WHERE y = 2", entries[1].restoredSql)
    }

    @Test
    fun `无参语句直接闭合不替换`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing: SELECT COUNT(*) FROM user",
            "==> Total: 1",
            "<== Columns: COUNT(*)",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT COUNT(*) FROM user", entries[0].restoredSql)
        assertEquals(emptyList<LogSqlParam>(), entries[0].params)
    }

    @Test
    fun `噪声行忽略`() {
        val entries = feedAll(
            parser(),
            "2026-08-15 10:00:00.100  INFO --- [main] c.s.m.Application : started in 2s",
            "==>  Preparing: SELECT * FROM t WHERE id = ?",
            "==> Parameters: 3(Long)",
            "<==    Columns: id, name",
            "<==      Total: 1",
        )
        assertEquals(1, entries.size)
    }

    @Test
    fun `无参 Preparing 由下一条 Preparing 闭合`() {
        val p = parser()
        p.feed("==>  Preparing: SELECT 1") // 无参语句
        p.feed("==>  Preparing: SELECT * FROM t WHERE id = ?") // 新语句闭合上一条
        p.feed("==> Parameters: 5(Long)") // 属于第二条
        p.closePending()
        val entries = p.takeEntries()
        assertEquals(2, entries.size)
        assertEquals("SELECT 1", entries[0].restoredSql)
        assertEquals("SELECT * FROM t WHERE id = 5", entries[1].restoredSql)
    }

    @Test
    fun `值内逗号与括号的字符串参数`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing: SELECT * FROM t WHERE name IN (?, ?)",
            "==> Parameters: a, b(String), c(x)(String)",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM t WHERE name IN ('a, b', 'c(x)')", entries[0].restoredSql)
    }

    @Test
    fun `null 字面量位于参数列表中间`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing: SELECT * FROM t WHERE a = ? AND b = ? AND c = ?",
            "==> Parameters: 1(Long), null, abc(String)",
        )
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = NULL AND c = 'abc'", entries[0].restoredSql)
    }

    @Test
    fun `无类型标注参数回落启发式`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing: SELECT * FROM t WHERE a = ? AND b = ?",
            "==> Parameters: 1, abc",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = 'abc'", entries[0].restoredSql)
    }

    @Test
    fun `占位符少于参数时忽略多余参数`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing: SELECT * FROM t WHERE id = ?",
            "==> Parameters: 1(Long), 2(Long)",
        )
        assertEquals("SELECT * FROM t WHERE id = 1", entries[0].restoredSql)
    }

    @Test
    fun `占位符多于参数时保留问号`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing: SELECT * FROM t WHERE a = ? AND b = ?",
            "==> Parameters: 1(Long)",
        )
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = ?", entries[0].restoredSql)
    }

    @Test
    fun `参数值包含单引号时转义`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing: SELECT * FROM t WHERE name = ?",
            "==> Parameters: O'Brien(String)",
        )
        assertEquals("SELECT * FROM t WHERE name = 'O''Brien'", entries[0].restoredSql)
    }

    @Test
    fun `takeEntries 取走后缓冲区清空`() {
        val p = parser()
        p.feed("==>  Preparing: SELECT 1")
        p.closePending()
        assertEquals(1, p.takeEntries().size)
        assertNull(p.closePending()) // 无挂起条目
        assertEquals(0, p.takeEntries().size)
    }

    // ---------- T-M31.14 可配置前缀 / 混合模式 / 最大长度 ----------

    @Test
    fun `自定义前缀解析`() {
        val p = MyBatisLogParser(sqlPrefix = "==> SQL:", paramPrefix = "==> ARGS:")
        p.feed("==> SQL: SELECT * FROM t WHERE id = ?")
        p.feed("==> ARGS: 5(Long)")
        p.closePending()
        val entries = p.takeEntries()
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM t WHERE id = 5", entries[0].restoredSql)
    }

    @Test
    fun `混合模式关闭时仅行首精确匹配`() {
        val p = MyBatisLogParser(mixedPrefix = false)
        p.feed("2026-08-15 INFO ==>  Preparing: SELECT 1") // 带日志前缀 → 不匹配（非行首）
        p.feed("Preparing: SELECT 2") // 行首匹配
        p.closePending()
        val entries = p.takeEntries()
        assertEquals(1, entries.size)
        assertEquals("SELECT 2", entries[0].restoredSql)
    }

    @Test
    fun `混合模式开启时带日志前缀仍匹配`() {
        val p = MyBatisLogParser(mixedPrefix = true)
        p.feed("2026-08-15 INFO ==>  Preparing: SELECT 1")
        p.closePending()
        val entries = p.takeEntries()
        assertEquals(1, entries.size)
        assertEquals("SELECT 1", entries[0].restoredSql)
    }

    @Test
    fun `超长 SQL 截断`() {
        val p = MyBatisLogParser(maxParseLength = 20)
        p.feed("Preparing: SELECT * FROM very_long_table_name WHERE id = 1")
        p.closePending()
        val entries = p.takeEntries()
        assertEquals(20, entries[0].rawSql.length)
    }

    // ---------- T-M31.xx 前缀与 SQL 分行兼容 ----------

    @Test
    fun `MP 前缀分行 Preparing 独占一行加 SQL 在下一行`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing:",
            "SELECT * FROM user WHERE id = ?",
            "==> Parameters: 1(Long)",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM user WHERE id = 1", entries[0].restoredSql)
        assertEquals(1, entries[0].params.size)
        assertEquals("1", entries[0].params[0].value)
        assertEquals("Long", entries[0].params[0].type)
    }

    @Test
    fun `原生前缀分行`() {
        val entries = feedAll(
            parser(),
            "Preparing:",
            "DELETE FROM t WHERE id = ?",
            "Parameters: 7(Integer)",
        )
        assertEquals(1, entries.size)
        assertEquals("DELETE FROM t WHERE id = 7", entries[0].restoredSql)
    }

    @Test
    fun `带日志框架前缀分行`() {
        val entries = feedAll(
            parser(),
            "2026-08-15 10:00:00.123 INFO --- [main] c.s.m.Log : ==>  Preparing:",
            "SELECT * FROM user WHERE id = ?",
            "2026-08-15 10:00:00.124 INFO --- [main] c.s.m.Log : ==> Parameters: 42(Long)",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM user WHERE id = 42", entries[0].restoredSql)
    }

    @Test
    fun `前缀与 SQL 间有空白行`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing:",
            "",
            "SELECT * FROM t WHERE id = ?",
            "==> Parameters: 3(Long)",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM t WHERE id = 3", entries[0].restoredSql)
    }

    @Test
    fun `无参语句分行`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing:",
            "SELECT COUNT(*) FROM user",
            "<== Total: 1",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT COUNT(*) FROM user", entries[0].restoredSql)
        assertEquals(emptyList<LogSqlParam>(), entries[0].params)
    }

    @Test
    fun `多条语句每条 Preparing 分行`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing:",
            "SELECT * FROM a WHERE x = ?",
            "==> Parameters: 1(Integer)",
            "==>  Preparing:",
            "SELECT * FROM b WHERE y = ?",
            "==> Parameters: 2(Integer)",
        )
        assertEquals(2, entries.size)
        assertEquals("SELECT * FROM a WHERE x = 1", entries[0].restoredSql)
        assertEquals("SELECT * FROM b WHERE y = 2", entries[1].restoredSql)
    }

    @Test
    fun `Parameters 也分行极端形态`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing:",
            "SELECT * FROM t WHERE id = ?",
            "==> Parameters:",
            "5(Long)",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM t WHERE id = 5", entries[0].restoredSql)
    }

    @Test
    fun `分行混合噪声行正确识别`() {
        val entries = feedAll(
            parser(),
            "2026-08-15 INFO started",
            "==>  Preparing:",
            "SELECT * FROM user WHERE id = ?",
            "==> Parameters: 9(Long)",
            "<== Total: 1",
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM user WHERE id = 9", entries[0].restoredSql)
    }

    @Test
    fun `严格模式下前缀分行仍生效`() {
        val p = MyBatisLogParser(mixedPrefix = false)
        p.feed("Preparing:")
        p.feed("SELECT * FROM t WHERE id = ?")
        p.feed("Parameters: 3(Long)")
        p.closePending()
        val entries = p.takeEntries()
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM t WHERE id = 3", entries[0].restoredSql)
    }

    // ---------- T-SAF-20（log.mybatis.parseparams.unbounded-quadratic-regex）：参数文本超长截断 ----------

    /**
     * Parameters 文本须与 Preparing SQL 正文同受 [MyBatisLogParser.maxParseLength] 截断。
     *
     * 审计复核证据（NEEDS-VALIDATION #20）：修复前 Preparing 的 SQL 正文在 `sql.take(maxParseLength)`
     * 截断，而 Parameters 余下文本不经截断直入 [parseParams]——其 PARAM_REGEX 以 `(?:\A|,\s*)` 为
     * 匹配起点，对逗号密集无括号文本每个逗号起点都惰性重扫到行尾，二次复杂度。
     */
    @Test
    fun `超长Parameters行截断到maxParseLength`() {
        val p = MyBatisLogParser(maxParseLength = 20000)
        p.feed("==>  Preparing: SELECT * FROM t WHERE id = ?")
        // 3 万个 `a,` = 6 万字符逗号密集无括号文本（PARAM_REGEX 二次回溯的最坏形态）
        p.feed("==> Parameters: " + "a,".repeat(30_000))
        p.closePending()
        val entries = p.takeEntries()
        assertEquals(1, entries.size)
        assertTrue(
            "参数条数须被 maxParseLength 截断（修复前约 30000 且解析秒级卡顿）",
            entries[0].params.size <= 10_100,
        )
    }

    /** Parameters 独占一行的续行形态（分支 3）同样受截断。 */
    @Test
    fun `超长续行参数文本同样截断`() {
        val p = MyBatisLogParser(maxParseLength = 20000)
        p.feed("==>  Preparing: SELECT * FROM t WHERE id = ?")
        p.feed("==> Parameters: ")
        // 无 `(类型)` 标注的纯逗号文本：走 PARAM_REGEX 二次回溯 + split(',') 回落两条路径
        p.feed("1, 2, " + "a,".repeat(30_000))
        p.closePending()
        val entries = p.takeEntries()
        assertEquals(1, entries.size)
        assertEquals("截断点之前的参数照常解析", "1", entries[0].params.firstOrNull()?.value)
        assertTrue(
            "参数条数须被 maxParseLength 截断（修复前约 30002）",
            entries[0].params.size <= 10_102,
        )
    }

    /** iBatis 2.x 的参数/类型行（集合 toString 形态）超长时同样截断（按无类型边界放弃解析）。 */
    @Test
    fun `iBatis超长参数与类型行截断`() {
        val p = MyBatisLogParser()
        p.feed("{pstm-1} Executing Statement: INSERT INTO t VALUES (?, ?)")
        p.feed("{pstm-1} Parameters: [" + "v, ".repeat(30_000) + "v]")
        p.feed("{pstm-1} Types: [" + "java.lang.String, ".repeat(30_000) + "java.lang.String]")
        p.closePending()
        val entries = p.takeEntries()
        assertEquals(1, entries.size)
        assertTrue(
            "iBatis 参数条数须被 maxParseLength 截断（修复前约 30001）",
            entries[0].params.size <= 10_100,
        )
    }

    // ---------- T-SAF-22（log.threadrouter.unbounded-nonidle-bucket-retention）：挂起态老化 ----------

    /**
     * 未闭合语句桶静默超阈值后被老化强制闭合产出（T-SAF-22）。
     * 不调 closePending：条目只能来自老化淘汰前的强制闭合——修复前 0 条产出且桶无界。
     */
    @Test
    fun `未闭合语句桶老化强制闭合产出`() {
        var now = 1_000_000L
        val p = MyBatisLogParser(maxParseLength = 20000, clock = { now })
        repeat(100) { i ->
            now += 61_000L
            p.feed("2026-09-16 10:00:00.000 DEBUG [t-$i] x - ==>  Preparing: SELECT * FROM t WHERE id = $i")
        }
        val entries = p.takeEntries()
        assertTrue(
            "老化桶须强制闭合产出（实际 ${entries.size}，修复前 0 条且桶数无界）",
            entries.size >= 100 - 64,
        )
    }

    /** iBatis 挂起态（pstm 键表）静默超阈值后按无类型兜底闭合（Types 缺行的兜底语义不变）。 */
    @Test
    fun `iBatis挂起态老化兜底闭合产出`() {
        var now = 1_000_000L
        val p = MyBatisLogParser(clock = { now })
        repeat(100) { i ->
            now += 61_000L
            p.feed("{pstm-$i} Executing Statement: INSERT INTO t VALUES (?)")
        }
        val entries = p.takeEntries()
        assertTrue(
            "老化挂起态须兜底闭合产出（实际 ${entries.size}，修复前 0 条且挂起表无界）",
            entries.size >= 100 - 64,
        )
    }
}
