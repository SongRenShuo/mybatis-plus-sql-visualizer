package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iBatis 2.x 控制台日志解析（T-IbatisPanwei 券 A/B）。
 *
 * 真实语料形态（用户真机 log4j2，`java.sql.PreparedStatement` / `java.sql.Connection` 日志代理）：
 * ```
 * {pstm-100021} Executing Statement: <sql>
 * {pstm-100021} Parameters: [v1, null, v2, …]
 * {pstm-100021} Types: [java.lang.String, null, java.lang.Long, …]
 * {conn-100022} Preparing Statement: <sql>
 * ```
 * 三条硬指标：36 参数 INSERT 还原后占位符全有值；`00340025002200200006` 因 `java.lang.String`
 * 必须带引号（只剥括号不接 Types 会裸出成数值字面量）；`restoredSql` 中不得出现 `[` / `]`。
 */
class IbatisLogParseTest {

    private fun parser() = MyBatisLogParser()

    private fun feedAll(p: MyBatisLogParser, lines: List<String>): List<LogSqlEntry> {
        lines.forEach { p.feed(it) }
        p.closePending()
        return p.takeEntries()
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    // ---------- 用户真机语料 ----------

    private val insertSql =
        "INSERT INTO bs_rwd_group_info_his (GROUP_ID, PARENT_ID, GROUP_NAME, GROUP_MEMO, " +
            "GROUP_SOURCE, SORT_NO, STATUS, DEL_FLAG, ORGID, CREATE_ID, CREATE_TIME, UPDATE_ID, " +
            "UPDATE_TIME, GROUP_TYPE, GROUP_LEVEL, GROUP_PATH, GROUP_CODE, EXT1, EXT2, EXT3, " +
            "EXT4, EXT5, EXT6, EXT7, EXT8, EXT9, EXT10, EXT11, EXT12, EXT13, EXT14, EXT15, EXT16, " +
            "EXT17, EXT18, REMARK) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, to_date(?, 'YYYYMMDDHH24MISS'), " +
            "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

    /** 用户日志的 36 个参数（`Parameters:` 行 `List.toString()` 元素，`null` 是 Java null 的打印形态）。 */
    private val insertValues = listOf(
        "00340025002200200006", "null", "测试OrgManager", "市场经营部", "OA",
        "1", "3", "0", "00340025002200200006", "admin", "20261106101423",
        "null", "null", "1", "1", "/1/2", "G0001",
        "null", "null", "null", "null", "null", "null", "null", "null", "null",
        "null", "null", "null", "null", "null", "null", "null", "null", "null", "0000",
    )

    /** 紧随其后的 `Types:` 行（iBatis 打全限定名；null 参数打 `null`）。 */
    private val insertTypes = listOf(
        "java.lang.String", "null", "java.lang.String", "java.lang.String", "java.lang.String",
        "java.lang.Integer", "java.lang.Short", "java.lang.Short", "java.lang.String",
        "java.lang.String", "java.lang.String",
        "null", "null", "java.lang.Integer", "java.lang.Integer", "java.lang.String",
        "java.lang.String",
        "null", "null", "null", "null", "null", "null", "null", "null", "null",
        "null", "null", "null", "null", "null", "null", "null", "null", "null", "java.lang.String",
    )

    /** 用户控制台真实行首（log4j2 pattern，logger 名被缩成 `j.s.*`）。 */
    private val logPrefix = "2026-11-06 10:14:23.506 DEBUG 8984 --- [main] j.s.PreparedStatement : "

    @Test
    fun `用户真机 INSERT 36 参数三行全量还原`() {
        assertEquals("语料本身须是 36 列/36 占位符", 36, countOccurrences(insertSql, "?"))
        assertEquals(36, insertValues.size)
        assertEquals(36, insertTypes.size)
        val expectedNulls = insertValues.count { it == "null" }

        val entries = feedAll(
            parser(),
            listOf(
                "${logPrefix}{pstm-100021} Executing Statement: $insertSql",
                "${logPrefix}{pstm-100021} Parameters: [${insertValues.joinToString(", ")}]",
                "${logPrefix}{pstm-100021} Types: [${insertTypes.joinToString(", ")}]",
            ),
        )

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(36, entry.params.size)
        assertEquals("java.lang.String", entry.params[0].type)
        assertNull("第 2 个参数是 null 字面量", entry.params[1].value)

        // 硬指标 1：36 个占位符全有值（旧行为只填前 13 个 null、其余留 ?）
        assertFalse("restoredSql 不得残留占位符: ${entry.restoredSql}", entry.restoredSql.contains("?"))
        // 硬指标 2/3：方括号不入 SQL；纯数字串因 java.lang.String 带引号
        assertFalse("方括号不得入 SQL: ${entry.restoredSql}", entry.restoredSql.contains("["))
        assertFalse("方括号不得入 SQL: ${entry.restoredSql}", entry.restoredSql.contains("]"))
        assertTrue("字符串类型必须加引号: ${entry.restoredSql}", entry.restoredSql.contains("'00340025002200200006'"))
        // null 位置渲染 NULL（iBatis 对 null 参数打 null 值与 null 类型）
        assertEquals(expectedNulls, countOccurrences(entry.restoredSql, "NULL"))
        // 数值类型标注（java.lang.Integer/java.lang.Short）原样直出；to_date 的实参是 String → 带引号
        assertTrue(entry.restoredSql.contains("to_date('20261106101423', 'YYYYMMDDHH24MISS')"))
    }

    @Test
    fun `单参 SELECT 三行含 conn Preparing 不产重复条目`() {
        val selectSql = "select * from bs_rwd_group_info where ORGID = ?"
        val entries = feedAll(
            parser(),
            listOf(
                "${logPrefix}{conn-100022} Preparing Statement: $selectSql",
                "${logPrefix}{pstm-100023} Executing Statement: $selectSql",
                "${logPrefix}{pstm-100023} Parameters: [00340025002200200006]",
                "${logPrefix}{pstm-100023} Types: [java.lang.String]",
            ),
        )
        // D-2：conn Preparing 只消费不产条目——一条语句只能有一个条目
        assertEquals(1, entries.size)
        assertEquals(
            "select * from bs_rwd_group_info where ORGID = '00340025002200200006'",
            entries[0].restoredSql,
        )
    }

    @Test
    fun `用户配置 SQL 前缀为 Executing Statement 时不产生双条目`() {
        val selectSql = "select * from bs_rwd_group_info where ORGID = ?"
        // 用户真机设置：sqlPrefix = "Executing Statement:"，paramPrefix 保持默认 "Parameters:"
        val p = MyBatisLogParser(sqlPrefix = "Executing Statement:", paramPrefix = "Parameters:")
        p.feed("${logPrefix}{pstm-100023} Executing Statement: $selectSql")
        p.feed("${logPrefix}{pstm-100023} Parameters: [00340025002200200006]")
        p.feed("${logPrefix}{pstm-100023} Types: [java.lang.String]")
        p.closePending()
        val entries = p.takeEntries()
        assertEquals("iBatis 分支先行消费，用户前缀分支不得再产一条", 1, entries.size)
        assertEquals(
            "select * from bs_rwd_group_info where ORGID = '00340025002200200006'",
            entries[0].restoredSql,
        )
    }

    // ---------- 兜底闭合路径 ----------

    @Test
    fun `Types 缺行按无类型启发式兜底不伪造类型`() {
        val selectSql = "select * from bs_rwd_group_info where ORGID = ?"
        val p = parser()
        p.feed("${logPrefix}{pstm-100023} Executing Statement: $selectSql")
        p.feed("${logPrefix}{pstm-100023} Parameters: [00340025002200200006]")
        // 生产桥每行喂入后都会 takeEntries——挂起态不得在此被取出（否则随后的 Types 行无处并入）
        assertEquals(0, p.takeEntries().size)
        p.closePending()
        val entries = p.takeEntries()

        assertEquals(1, entries.size)
        assertNull("Types 缺行不伪造类型", entries[0].params[0].type)
        // 现状无类型启发式：纯数字串裸出（正因如此生产形态必须并入 Types）——方括号仍不得出现
        assertEquals(
            "select * from bs_rwd_group_info where ORGID = 00340025002200200006",
            entries[0].restoredSql,
        )
    }

    @Test
    fun `Types 缺行由下一条 conn Preparing 兜底闭合`() {
        val selectSql = "select * from bs_rwd_group_info where ORGID = ?"
        val p = parser()
        p.feed("${logPrefix}{pstm-100023} Executing Statement: $selectSql")
        p.feed("${logPrefix}{pstm-100023} Parameters: [00340025002200200006]")
        // 下一条语句的 conn Preparing = 语句组边界（生产路径下的静默兜底闭合点）
        p.feed("${logPrefix}{conn-100024} Preparing Statement: $selectSql")
        val entries = p.takeEntries()
        assertEquals(1, entries.size)
        assertEquals(0, p.takeEntries().size)
    }

    @Test
    fun `Parameters 与 Types 之间夹噪声行仍按 pstm id 归位`() {
        val selectSql = "select * from bs_rwd_group_info where ORGID = ?"
        val entries = feedAll(
            parser(),
            listOf(
                "${logPrefix}{pstm-100023} Executing Statement: $selectSql",
                "${logPrefix}{pstm-100023} Parameters: [00340025002200200006]",
                "2026-11-06 10:14:23.518  INFO 8984 --- [main] c.s.BizService : 业务日志行",
                "${logPrefix}{pstm-100023} Types: [java.lang.String]",
            ),
        )
        assertEquals(1, entries.size)
        assertEquals("java.lang.String", entries[0].params[0].type)
        assertEquals(
            "select * from bs_rwd_group_info where ORGID = '00340025002200200006'",
            entries[0].restoredSql,
        )
    }

    @Test
    fun `多组 iBatis 语句连续配对互不串扰`() {
        val entries = feedAll(
            parser(),
            listOf(
                "${logPrefix}{conn-1} Preparing Statement: select * from a where x = ?",
                "${logPrefix}{pstm-2} Executing Statement: select * from a where x = ?",
                "${logPrefix}{pstm-2} Parameters: [1]",
                "${logPrefix}{pstm-2} Types: [java.lang.Integer]",
                "${logPrefix}{conn-3} Preparing Statement: select * from b where y = ?",
                "${logPrefix}{pstm-4} Executing Statement: select * from b where y = ?",
                "${logPrefix}{pstm-4} Parameters: [abc]",
                "${logPrefix}{pstm-4} Types: [java.lang.String]",
            ),
        )
        assertEquals(2, entries.size)
        assertEquals("select * from a where x = 1", entries[0].restoredSql)
        assertEquals("select * from b where y = 'abc'", entries[1].restoredSql)
    }

    // ---------- MyBatis 既有语法回归（零回归） ----------

    @Test
    fun `MyBatis 值类型语法不受 iBatis 分支影响`() {
        val entries = feedAll(
            parser(),
            listOf(
                "==>  Preparing: SELECT id, name FROM user WHERE id = ? AND status = ?",
                "==> Parameters: 1(Long), null",
            ),
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT id, name FROM user WHERE id = 1 AND status = NULL", entries[0].restoredSql)
        assertEquals("Long", entries[0].params[0].type)
    }

    @Test
    fun `MyBatis 无类型标注回落逗号粗分不受影响`() {
        val entries = feedAll(
            parser(),
            listOf(
                "==>  Preparing: SELECT * FROM t WHERE a = ? AND b = ?",
                "==> Parameters: 1, abc",
            ),
        )
        assertEquals(1, entries.size)
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = 'abc'", entries[0].restoredSql)
    }
}
