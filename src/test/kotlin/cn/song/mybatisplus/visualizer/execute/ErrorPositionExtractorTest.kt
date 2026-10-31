package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-ErrorLocate（N-18）T-EL-01：驱动错误位置提取表驱动。
 *
 * 覆盖：PG `Position: N`（pgjdbc 拼 message 形态）/ MySQL `at line N` / `Line: N` 形态、
 * marker 单一性门控（多信号放弃归属）、越界钳制、无信号（连接类错误）降级 null、
 * SQLException 链 SQLState/errorCode 提取（cause 链 + nextException 链）。
 */
class ErrorPositionExtractorTest {

    @Test
    fun testRichFormatterPreservesFullMessageAndOnlyRealMetadata() {
        // T-MagpieGems W4 起 format 末行追加分类出路提示（SYNTAX 档），断言与实现同取 bundle 键
        val syntaxHint = "\n" + cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("error.category.syntax")
        val driver = java.sql.SQLException("Syntax <bad> & detail\nPosition: 12", "42601", 932)
        val info = ErrorPositionExtractor.buildInfo(driver, driver.message!!, 80)
        assertEquals("Syntax <bad> & detail\nPosition: 12\nsqlState: 42601\nerrorCode: 932\nerrorPosition: char 12$syntaxHint", ExecutionErrorFormatter.format(info))
        val mcp = cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.formatFailure(driver, "PostgreSQL", "EXPLAIN SELECT * FROM some_table")
        assertEquals("\nsqlState: 42601\nerrorCode: 932\nerrorPosition: char 12", mcp.substringAfter("Syntax <bad> & detail"))
        assertEquals("offline", ExecutionErrorFormatter.format(ExecutionErrorInfo("offline", errorCode = 0, positionChar = 3)))
        assertEquals("offline", ExecutionErrorFormatter.format(ExecutionErrorInfo("offline", sqlState = " ", lineInSql = 0, positionSource = "line")))
        val line = ErrorPositionExtractor.buildInfo(java.sql.SQLException("bad at line 3", "42000", 1064), "bad at line 3", 40)
        assertEquals("bad at line 3\nsqlState: 42000\nerrorCode: 1064\nerrorPosition: line 3$syntaxHint", ExecutionErrorFormatter.format(line))
        val ambiguous = ErrorPositionExtractor.buildInfo(null, "Position: 5 ... at line 2", 80)
        assertEquals("Position: 5 ... at line 2", ExecutionErrorFormatter.format(ambiguous))
    }

    // ---------- extractPosition：PG Position 形态 ----------

    @Test
    fun testPGPosition提取() {
        val msg = "ERROR: column \"naem\" does not exist\n  Position: 42"
        val pos = ErrorPositionExtractor.extractPosition(msg)!!
        assertEquals(42, pos.first)
        assertEquals(true, pos.second)
        assertEquals("pg-position", pos.third)
    }

    // ---------- extractPosition：MySQL at line 形态 ----------

    @Test
    fun testMySQLAtLine提取() {
        val msg = "You have an error in your SQL syntax; check the manual... near 'FORM t' at line 1"
        val pos = ErrorPositionExtractor.extractPosition(msg)!!
        assertEquals(1, pos.first)
        assertEquals(false, pos.second)
        assertEquals("mysql-at-line", pos.third)
    }

    // ---------- extractPosition：Line: 形态 ----------

    @Test
    fun testLineColon提取() {
        val pos = ErrorPositionExtractor.extractPosition("syntax error\nLine: 7")!!
        assertEquals(7, pos.first)
        assertEquals(false, pos.second)
        assertEquals("line", pos.third)
    }

    // ---------- marker 单一性门控：多信号放弃 ----------

    @Test
    fun test多Marker放弃归属() {
        // Position 与 at line 并存（跨驱动混排/日志拼接场景）——宁缺毋滥
        assertNull(ErrorPositionExtractor.extractPosition("Position: 5 ... at line 2"))
        // 同形态两处不同值
        assertNull(ErrorPositionExtractor.extractPosition("Position: 5 and Position: 9"))
    }

    @Test
    fun test无Marker返回null() {
        // 连接类错误（基建语句不带位置——DBX postgres.rs:1501 同款防误指）
        assertNull(ErrorPositionExtractor.extractPosition("Connection refused: no further information"))
        assertNull(ErrorPositionExtractor.extractPosition("Communications link failure"))
    }

    // ---------- 越界钳制 ----------

    @Test
    fun test越界钳制到SQL长度() {
        val pos = ErrorPositionExtractor.extractPosition("error at Position: 5000", sqlMaxLength = 120)!!
        assertEquals(120, pos.first)
        // 钳后 <=0（SQL 长度非法）→ null
        assertNull(ErrorPositionExtractor.extractPosition("Position: 3", sqlMaxLength = 0))
    }

    // ---------- T-ErrorLocateUsability T-ELU-02（O-49）：Oracle 位置形态 ----------

    /**
     * 真库实测原文钉住（Oracle Free 23 / FREEPDB1，DBX 通道 2026-09-22，只读）。
     *
     * 实测三例证明 N 是**出错记号起点减一**（0-based 偏移），本提取器契约是 1-based
     * → 归属值 = N+1：`SELECT * FROM t` 里表名在 1-based 第 15 位 → N=14 → 15。
     */
    @Test
    fun testOracle位置提取_真库原文() {
        val msg = "ORA-00942: table or view \"DBX_TEST\".\"NO_SUCH_TABLE_XYZ\" does not exist\n" +
            " error occur at position: 23"
        val pos = ErrorPositionExtractor.extractPosition(msg)!!
        assertEquals("N=23 是 0-based 记号偏移 → 归属 1-based 24", 24, pos.first)
        assertEquals(true, pos.second)
        assertEquals("oracle-position", pos.third)
    }

    @Test
    fun testOracle位置提取_记号在语句首位() {
        // 实测：`FROM dbx_test.issue_new` → `ORA-00900: invalid SQL statement` + position: 0
        val msg = "ORA-00900: invalid SQL statement\n error occur at position: 0"
        val pos = ErrorPositionExtractor.extractPosition(msg)!!
        assertEquals("N=0 → 1-based 第 1 位", 1, pos.first)
        assertEquals("oracle-position", pos.third)
    }

    /**
     * **标签与取值都不再落到 PG 分支**（改前缺陷留证）：Oracle 形态里含 `position: N`，
     * 而 PG 的 `\bPosition:` 会顺带命中它——改前 Oracle 位置被标成 `pg-position` 且按
     * 1-based 直接报出（恒差一位）。本用例把「遮蔽 Oracle 段」这条修复分支钉住。
     */
    @Test
    fun testOracle形态不被PG分支误吞() {
        val msg = "ORA-00936: missing expression\n error occur at position: 7"
        val pos = ErrorPositionExtractor.extractPosition(msg)!!
        assertEquals("oracle-position", pos.third)
        assertEquals("N=7 → 8（不是 7）", 8, pos.first)
    }

    /** 既有三种 marker 行为不变（Oracle 形态不在场时逐字不变）。 */
    @Test
    fun testOracle形态不在场时既有marker不变() {
        val pg = ErrorPositionExtractor.extractPosition("ERROR: x\n  Position: 42")!!
        assertEquals(42, pg.first)
        assertEquals("pg-position", pg.third)
        val multi = ErrorPositionExtractor.extractPosition("Position: 5 ... at line 2")
        assertNull("多 marker 仍放弃归属", multi)
    }

    /** Oracle 位置同样受越界钳制（与其它 marker 同口径）。 */
    @Test
    fun testOracle位置越界钳制() {
        val msg = "ORA-00942: nope\n error occur at position: 900"
        assertEquals(50, ErrorPositionExtractor.extractPosition(msg, sqlMaxLength = 50)!!.first)
    }

    // ---------- T-Round10FU（独立复核 ㉕）：Oracle 驱动自身的 `, Position : N` 形态 ----------

    /**
     * **形态来源（jar 字节码实证，非臆造）**：`oracle.jdbc.OracleDatabaseException.toString()`
     * 的拼接模板——ojdbc11 `23.3.0.23.09` 的 `oracle/jdbc/OracleDatabaseException.class`
     * 常量池 `#60` = `Error : , Position : , SQL =  , Original SQL = , Error Message = `。
     * 下面这串即该模板的产出形态（冒号前有空格，故 PG 的 `\bPosition:` 与既有 Oracle marker
     * 都不命中）。**改前读数**（最小探针逐字复制本类四条正则、喂入同一文本）：marker 数 **0**、
     * `extractPosition` 返回 **null**——即该形态此前不被任何 marker 覆盖。
     */
    @Test
    fun testOracle驱动toString形态_冒号前有空格() {
        val msg = "Error : 942, Position : 23, SQL = SELECT * FROM dbx_test.no_such_table_xyz, " +
            "Original SQL = SELECT * FROM dbx_test.no_such_table_xyz, " +
            "Error Message = ORA-00942: table or view \"DBX_TEST\".\"NO_SUCH_TABLE_XYZ\" does not exist"
        val pos = ErrorPositionExtractor.extractPosition(msg)!!
        assertEquals("与 error occur at position 同源服务端字段 → 同样 0-based → N+1", 24, pos.first)
        assertEquals(true, pos.second)
        assertEquals("oracle-position", pos.third)
    }

    /**
     * 两种 Oracle 形态并存（链上包装/拼接出同时含 `toString` 与 `error occur at position` 的消息）：
     * 同源字段同读数 → `distinctBy(source to value)` 归并为一个信号，**不**误判成多信号而丢位置。
     * （这正是新 marker 复用 `oracle-position` 标签的原因。）
     */
    @Test
    fun testOracle两种形态并存时同一读数归并() {
        val msg = "Error : 942, Position : 23, Error Message = ORA-00942: nope\n" +
            " error occur at position: 23"
        val pos = ErrorPositionExtractor.extractPosition(msg)!!
        assertEquals(24, pos.first)
        assertEquals("oracle-position", pos.third)
    }

    /** 两形态读数不一致（不该发生，但必须保守）→ 单一性门控放弃归属，不挑一个报出去。 */
    @Test
    fun testOracle两形态读数不一致时放弃归属() {
        val msg = "Error : 942, Position : 5, Error Message = ORA-00936: nope\n" +
            " error occur at position: 9"
        assertNull(ErrorPositionExtractor.extractPosition(msg))
    }

    /**
     * 驱动把「无位置」编码为 `errorPosition < 0`（`T4CTTIoer11` 偏移 404 的 `iflt` 守卫即此语义），
     * 故 `Position : -1` 这类文本**不得**被正则在负号后截出数字虚报位置。
     */
    @Test
    fun testOracle驱动负位置不虚报() {
        assertNull(
            ErrorPositionExtractor.extractPosition(
                "Error : 942, Position : -1, Error Message = ORA-00942: table or view does not exist",
            ),
        )
    }

    /**
     * 只带行号、不带字符位置的驱动报错（MySQL 语法错误）→ `lineInSql` 填充、`positionChar` 为 null。
     * 这是 UI 三态 tooltip 第二态的数据侧（T-Round10FU：改前 `lineInSql` 在 src/main 零消费方）。
     */
    @Test
    fun test只带行号的报错填lineInSql() {
        val info = ErrorPositionExtractor.buildInfo(
            null,
            "You have an error in your SQL syntax; check the manual... near 'FORM t' at line 3",
        )
        assertNull("行号形态不填字符位置", info.positionChar)
        assertEquals(3, info.lineInSql)
        assertEquals("mysql-at-line", info.positionSource)
    }

    // ---------- T-ELU-03（O-49）：被执行文本长度接线（钳制生产生效的前提） ----------

    /**
     * `executedSqlLength` 是「调用方确实传了被执行文本长度」的结构化证据——非 null 即接线到位。
     * 改前四处投递点全都不传，钳制只在单测生效（本字段恒 null）。
     */
    @Test
    fun test被执行文本长度随富信息结构化落字段() {
        val withLen = ErrorPositionExtractor.buildInfo(null, "x\n  Position: 9999", sqlMaxLength = 120)
        assertEquals(120, withLen.executedSqlLength)
        assertEquals("位置被钳到被执行文本长度", 120, withLen.positionChar)
        val without = ErrorPositionExtractor.buildInfo(null, "x\n  Position: 9999")
        assertNull("不传长度 → 字段为 null（不钳制）", without.executedSqlLength)
        assertEquals(9999, without.positionChar)
    }

    // ---------- buildInfo：SQLException 链提取 ----------

    @Test
    fun testSQLState与错误码提取() {
        val se = java.sql.SQLException("dup key", "23000", 1062)
        val info = ErrorPositionExtractor.buildInfo(se, se.message ?: "")
        assertEquals("23000", info.sqlState)
        assertEquals(1062, info.errorCode)
    }

    @Test
    fun test包装异常的cause链穿透() {
        val se = java.sql.SQLException("deadlock detected", "40P01", 1213)
        val wrapped = RuntimeException("execution failed", se)
        val info = ErrorPositionExtractor.buildInfo(wrapped, "execution failed")
        assertEquals("40P01", info.sqlState)
        assertEquals(1213, info.errorCode)
    }

    @Test
    fun testNextException链提取() {
        val head = java.sql.SQLException("batch head failed", "HY000", 1)
        head.nextException = java.sql.SQLException("second", "23000", 1062)
        val info = ErrorPositionExtractor.buildInfo(head, "batch head failed")
        // head 自身 sqlState 非空优先；errorCode 非空优先——next 链只补空位
        assertEquals("HY000", info.sqlState)
        assertEquals(1, info.errorCode)
        val head2 = java.sql.SQLException("batch head failed", "", 0)
        head2.nextException = java.sql.SQLException("second", "23000", 1062)
        val info2 = ErrorPositionExtractor.buildInfo(head2, "batch head failed")
        assertEquals("23000", info2.sqlState)
        assertEquals(1062, info2.errorCode)
    }

    @Test
    fun test富信息位置字段() {
        val se = java.sql.SQLException("column \"x\" does not exist\n  Position: 18", "42703", 0)
        val info = ErrorPositionExtractor.buildInfo(se, se.message ?: "", sqlMaxLength = 60)
        assertEquals(18, info.positionChar)
        assertNull(info.lineInSql)
        assertEquals("pg-position", info.positionSource)
    }

    @Test
    fun test富信息Oracle位置字段() {
        val se = java.sql.SQLException(
            "ORA-00942: table or view \"DBX_TEST\".\"NO_SUCH_TABLE_XYZ\" does not exist\n error occur at position: 23",
            "42000",
            942,
        )
        val info = ErrorPositionExtractor.buildInfo(se, se.message ?: "", sqlMaxLength = 500)
        assertEquals(24, info.positionChar)
        assertNull(info.lineInSql)
        assertEquals("oracle-position", info.positionSource)
        assertEquals(500, info.executedSqlLength)
    }

    @Test
    fun test非SQL异常零SQLState不虚报() {
        val info = ErrorPositionExtractor.buildInfo(IllegalStateException("console disposed"), "console disposed")
        assertNull(info.sqlState)
        assertNull(info.errorCode)
        assertNull(info.positionChar)
        assertNull(info.lineInSql)
    }
}
