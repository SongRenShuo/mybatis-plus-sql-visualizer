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

    // ---------- T-PanweiInsertCapture F4：MyBatis SimpleStatementHandler 形态 ----------

    @Test
    fun `Executing 形态产出无参条目`() {
        // 真机形态（mybatis-3.5.19 StatementLogger：`==>  Executing: <sql>`，无 Preparing/Parameters）
        val entries = feedAll(
            parser(),
            "2026-11-10 14:05:33.918 DEBUG 9754 --- [ main] o.m.l.jdbc.StatementLogger : ==>  Executing: INSERT INTO t (a, b) VALUES (1, 2)",
        )
        assertEquals("Executing 行必须产出 1 条", 1, entries.size)
        assertEquals("INSERT INTO t (a, b) VALUES (1, 2)", entries[0].restoredSql)
        assertTrue("plain Statement 无绑定参数", entries[0].params.isEmpty())
        assertEquals(SqlSource.MYBATIS, entries[0].source)
    }

    @Test
    fun `Executing 与 Preparing 两形态各自独立产出`() {
        // 同一 SQL 先 Executing 后 Preparing（互斥前提不成立时的边界）：解析器两条都产，
        // 折叠交给 LogSqlEntryBuffer 既有同 SQL 去重（不新增第二套折叠逻辑）。
        val entries = feedAll(
            parser(),
            "==>  Executing: INSERT INTO t (a) VALUES (1)",
            "==>  Preparing: INSERT INTO t (a) VALUES (1)",
            "==> Parameters: 1(Integer)",
        )
        assertEquals("两形态互不吞并，各产一条", 2, entries.size)
        assertEquals("INSERT INTO t (a) VALUES (1)", entries[0].restoredSql)
        assertEquals("INSERT INTO t (a) VALUES (1)", entries[1].restoredSql)
    }

    @Test
    fun `Executing 行终止上一条的续行`() {
        val entries = feedAll(
            parser(),
            "==>  Preparing: SELECT a",
            "FROM t",
            "==>  Executing: INSERT INTO t2 (a) VALUES (3)",
        )
        assertEquals(2, entries.size)
        assertEquals("SELECT a\nFROM t", entries[0].restoredSql)
        assertEquals("INSERT INTO t2 (a) VALUES (3)", entries[1].restoredSql)
    }

    @Test
    fun `严格模式下 Executing 行不识别但计入未识别计数`() {
        LogParseDiag.reset()
        try {
            val before = LogParseDiag.unrecognizedSqlLine.get()
            val p = MyBatisLogParser(mixedPrefix = false)
            p.feed("==>  Executing: INSERT INTO t (a) VALUES (1)")
            val entries = p.takeEntries()
            assertEquals("strict 模式只认用户配置前缀，Executing 不产条目", 0, entries.size)
            assertEquals(
                "未识别 SQL 形态行必须留痕（F7）",
                before + 1,
                LogParseDiag.unrecognizedSqlLine.get(),
            )
        } finally {
            LogParseDiag.reset()
        }
    }

    @Test
    fun `宽松模式下 Executing 行不计入未识别计数`() {
        LogParseDiag.reset()
        try {
            val before = LogParseDiag.unrecognizedSqlLine.get()
            val p = parser()
            p.feed("==>  Executing: INSERT INTO t (a) VALUES (1)")
            p.takeEntries()
            assertEquals(
                "宽松模式该行已被分支 0-5 消费，不得计入未识别",
                before,
                LogParseDiag.unrecognizedSqlLine.get(),
            )
        } finally {
            LogParseDiag.reset()
        }
    }
}
