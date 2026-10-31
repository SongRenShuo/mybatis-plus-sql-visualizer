package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4 导出格式化纯逻辑测试（CSV 转义 / TSV 扁平化 / TXT 保形 / SQL 脚本形态）。
 */
class SqlExportServiceTest {

    private val row = SqlExportRow(
        time = "2026-09-27 10:00:00.123",
        sql = "SELECT * FROM t WHERE a = 'x'",
        source = "UserMapper.java:10",
        durationMs = 42L,
    )

    @Test
    fun testCsv_常规行() {
        val out = SqlExportService.format(listOf(row), SqlExportFormat.CSV)
        assertEquals(
            "time,source,duration_ms,sql\n" +
                "2026-09-27 10:00:00.123,UserMapper.java:10,42,SELECT * FROM t WHERE a = 'x'\n",
            out,
        )
    }

    @Test
    fun testCsv_字段含逗号引号换行_包裹与加倍() {
        val tricky = SqlExportRow(
            time = "t1",
            sql = "SELECT 'a,b' FROM t -- 注释\"引号\"\n第二行",
            source = "",
            durationMs = null,
        )
        val out = SqlExportService.format(listOf(tricky), SqlExportFormat.CSV)
        // sql 字段含逗号/引号/换行 → 整体包裹 + 内部双引号加倍；空 source/duration 为空字段
        assertTrue(out.contains("\"SELECT 'a,b' FROM t -- 注释\"\"引号\"\"\n第二行\""))
        assertTrue(out.contains("t1,,,\"SELECT"))
    }

    @Test
    fun testTsv_字段内tab与换行扁平化() {
        val tricky = SqlExportRow("t1", "SELECT\ta\nFROM\tb", "", 7L)
        val out = SqlExportService.format(listOf(tricky), SqlExportFormat.TSV)
        assertEquals("time\tsource\tduration_ms\tsql\nt1\t\t7\tSELECT a FROM b\n", out)
    }

    @Test
    fun testTxt_多行SQL保形与序号() {
        val multi = SqlExportRow("t1", "SELECT 1;\nUPDATE t SET a = 1", "UserMapper.java:3", 12L)
        val out = SqlExportService.format(listOf(multi, row), SqlExportFormat.TXT)
        assertTrue(out.contains("-- === SQL #1 ==="))
        assertTrue(out.contains("-- === SQL #2 ==="))
        assertTrue(out.contains("-- time: t1 | source: UserMapper.java:3 | 12 ms"))
        assertTrue(out.contains("UPDATE t SET a = 1\n"))
    }

    @Test
    fun testCsv_空行集_仅表头() {
        assertEquals("time,source,duration_ms,sql\n", SqlExportService.format(emptyList(), SqlExportFormat.CSV))
    }

    // ---------- R60（T-LaneB D96）：导出链脱敏（判别断言——开→脱敏/关→原文） ----------

    private val sensitiveRow = SqlExportRow(
        time = "t1",
        sql = "SELECT id FROM user WHERE phone = '13812345678' AND password = 'p@ss' AND name = 'tom'",
        source = "",
        durationMs = null,
    )

    @Test
    fun testCsv_脱敏开_敏感字面量打码() {
        val out = SqlExportService.format(listOf(sensitiveRow), SqlExportFormat.CSV, mask = true)
        assertTrue("手机号应打码", out.contains("phone = '***'"))
        assertTrue("密码应打码", out.contains("password = '***'"))
        assertFalse("原文手机号不得出现", out.contains("13812345678"))
        assertFalse("原密码不得出现", out.contains("p@ss"))
        assertTrue("非敏感值保留", out.contains("name = 'tom'"))
    }

    @Test
    fun testCsv_脱敏关_原文逐字保留() {
        val out = SqlExportService.format(listOf(sensitiveRow), SqlExportFormat.CSV, mask = false)
        assertTrue(out.contains("phone = '13812345678'"))
        assertTrue(out.contains("password = 'p@ss'"))
    }

    @Test
    fun testTxt_脱敏开_保形不破坏注释头() {
        val out = SqlExportService.format(listOf(sensitiveRow), SqlExportFormat.TXT, mask = true)
        assertTrue(out.contains("-- === SQL #1 ==="))
        assertTrue(out.contains("'***'"))
        assertFalse(out.contains("13812345678"))
    }

    @Test
    fun testPortable_脱敏开_TSV同口径() {
        val row = PortableSqlRow(
            dialect = "MySQL",
            kind = PortableSqlKind.CRUD,
            upsert = "no",
            sql = "SELECT 1 FROM t WHERE mobile = 13812345678",
        )
        val masked = SqlExportService.formatPortable(listOf(row), SqlExportFormat.TSV, mask = true)
        assertTrue(masked.contains("mobile = ***"))
        assertFalse(masked.contains("13812345678"))
        val plain = SqlExportService.formatPortable(listOf(row), SqlExportFormat.TSV, mask = false)
        assertTrue(plain.contains("mobile = 13812345678"))
    }

    // ---------- UI-001：白名单豁免贯穿导出链 ----------

    @Test
    fun testCsv_脱敏开_白名单列豁免() {
        val out = SqlExportService.format(
            listOf(sensitiveRow), SqlExportFormat.CSV, mask = true, allowList = listOf("phone"),
        )
        assertTrue("白名单列应恢复原文", out.contains("phone = '13812345678'"))
        assertTrue("非白名单列仍打码", out.contains("password = '***'"))
        assertFalse("非白名单原值不得出现", out.contains("p@ss"))
    }

    @Test
    fun testPortable_脱敏开_白名单列豁免裸数字() {
        val row = PortableSqlRow(
            dialect = "MySQL",
            kind = PortableSqlKind.CRUD,
            upsert = "no",
            sql = "SELECT 1 FROM t WHERE mobile = 13812345678",
        )
        val out = SqlExportService.formatPortable(
            listOf(row), SqlExportFormat.TSV, mask = true, allowList = listOf("mobile"),
        )
        assertTrue("裸数字在白名单列下应豁免: $out", out.contains("mobile = 13812345678"))
        val masked = SqlExportService.formatPortable(listOf(row), SqlExportFormat.TSV, mask = true)
        assertTrue("空白名单仍打码", masked.contains("mobile = ***"))
    }

    // ---------- T-ExportSqlFormat（O-131/R77/D131/V154）：SQL 文件格式 ----------

    @Test
    fun testSql_单条无分号_文件头计数与补分号() {
        val out = SqlExportService.format(listOf(row), SqlExportFormat.SQL)
        val lines = out.lines()
        // 文件头：条数计数 + 导出时间（格式同 formatTime 口径）
        assertTrue(
            "文件头形态不符: ${lines[0]}",
            lines[0].matches(
                Regex("-- Exported 1 SQL statements by MyBatis-Plus SQL Visualizer at \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"),
            ),
        )
        assertEquals("-- === SQL #1 ===", lines[1])
        assertEquals("-- time: 2026-09-27 10:00:00.123 | source: UserMapper.java:10 | 42 ms", lines[2])
        // 无分号补 `;`
        assertEquals("SELECT * FROM t WHERE a = 'x';", lines[3])
        // 逐条后空行分隔（结尾 "\n\n" → 末两元素空串）
        assertEquals("", lines[4])
        assertEquals("", lines[5])
        assertEquals(6, lines.size)
    }

    @Test
    fun testSql_带分号不重复() {
        val ended = row.copy(sql = "SELECT 1;")
        val out = SqlExportService.format(listOf(ended), SqlExportFormat.SQL)
        assertTrue(out.contains("SELECT 1;\n"))
        assertFalse("已分号结尾不应出现双分号", out.contains(";;"))
    }

    @Test
    fun testSql_注释头字段与TXT同构() {
        val txt = SqlExportService.format(listOf(row), SqlExportFormat.TXT).lines()
        val sql = SqlExportService.format(listOf(row), SqlExportFormat.SQL).lines()
        // SQL 逐条注释头（#N / time|source|duration 元信息行）与 TXT 逐字节同构；SQL 行多分号
        assertEquals(txt[0], sql[1])
        assertEquals(txt[1], sql[2])
        assertEquals(txt[2] + ";", sql[3])
    }

    @Test
    fun testSql_多条序号连续() {
        val out = SqlExportService.format(listOf(row, row, row), SqlExportFormat.SQL)
        assertTrue(out.contains("-- === SQL #1 ==="))
        assertTrue(out.contains("-- === SQL #2 ==="))
        assertTrue(out.contains("-- === SQL #3 ==="))
        assertFalse(out.contains("-- === SQL #4"))
        assertTrue(
            "文件头计数应=3: ${out.lines()[0]}",
            out.startsWith("-- Exported 3 SQL statements by MyBatis-Plus SQL Visualizer at "),
        )
    }

    @Test
    fun testSql_空行集_仅文件头() {
        // 入口各面板已挡空集（exportEntries/exportVisibleEntries 均 return）；服务层防一把
        val out = SqlExportService.format(emptyList(), SqlExportFormat.SQL)
        val lines = out.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("-- Exported 0 SQL statements by MyBatis-Plus SQL Visualizer at "))
    }

    @Test
    fun testDefaultSqlExportFileName_时间戳形态() {
        assertEquals("sql", SqlExportFormat.SQL.ext)
        // yyyyMMdd-HHmmss 到秒——同日多次导出不静默覆盖；不与 sql-history/sql-log 名族重叠
        assertTrue(
            SqlExportService.defaultSqlExportFileName().matches(Regex("sql-export-\\d{8}-\\d{6}\\.sql")),
        )
    }
}
