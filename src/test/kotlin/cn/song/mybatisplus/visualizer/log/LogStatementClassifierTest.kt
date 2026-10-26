package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * SQL 语句类型分类器单元测试（P-06，11 用例覆盖全部分支 + 边界）。
 */
class LogStatementClassifierTest {

    @Test
    fun `SELECT 标准大写`() {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("SELECT * FROM user"))
    }

    @Test
    fun `SELECT 小写`() {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("select * from user"))
    }

    @Test
    fun `SELECT 前导空白`() {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("\n  SELECT * FROM user"))
    }

    @Test
    fun `WITH CTE`() {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("WITH t AS (SELECT 1) SELECT * FROM t"))
    }

    @Test
    fun `INSERT`() {
        assertEquals(StatementType.INSERT, LogStatementClassifier.classify("INSERT INTO user VALUES (1, 'a')"))
    }

    @Test
    fun `UPDATE`() {
        assertEquals(StatementType.UPDATE, LogStatementClassifier.classify("UPDATE user SET name = 'x'"))
    }

    @Test
    fun `DELETE`() {
        assertEquals(StatementType.DELETE, LogStatementClassifier.classify("DELETE FROM user WHERE id = 1"))
    }

    @Test
    fun `MERGE 归 OTHER`() {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("MERGE INTO target USING source"))
    }

    @Test
    fun `TRUNCATE 归 DROP`() {
        // T-DdlTypeColor（2026-10-26）：TRUNCATE 原归 OTHER（不着色），现归 DROP（删表红 + 虚线条）。
        // 改判依据：清表是结构性删除，与 DROP 同族；留 OTHER 会让它在列表里没有任何类型标识。
        assertEquals(StatementType.DROP, LogStatementClassifier.classify("TRUNCATE TABLE user"))
    }

    @Test
    fun `空串归 OTHER`() {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify(""))
    }

    @Test
    fun `仅空白归 OTHER`() {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("   "))
    }

    @Test
    fun `按条目分类取 restoredSql`() {
        val entry = LogSqlEntry(
            rawSql = "Preparing: SELECT 1",
            restoredSql = "SELECT * FROM t",
            params = emptyList(),
            timestamp = 0L,
        )
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify(entry))
    }

    // ---------- T-CrudColor：条目级缓存属性 statementType ----------

    @Test
    fun `条目缓存属性与按串分类全类型等价`() {
        listOf(
            "SELECT * FROM user" to StatementType.SELECT,
            "select * from user" to StatementType.SELECT,
            "  \n SELECT 1" to StatementType.SELECT,
            "WITH cte AS (SELECT 1) SELECT * FROM cte" to StatementType.SELECT,
            "INSERT INTO user VALUES (1)" to StatementType.INSERT,
            "UPDATE user SET a = 1" to StatementType.UPDATE,
            "DELETE FROM user WHERE id = 1" to StatementType.DELETE,
            "MERGE INTO t USING s ON (1=1)" to StatementType.OTHER,
            "TRUNCATE TABLE user" to StatementType.DROP,
            "" to StatementType.OTHER,
            "   " to StatementType.OTHER,
        ).forEach { (sql, expected) ->
            val entry = LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList())
            assertEquals("statementType 应与 classify(sql) 一致：$sql", expected, entry.statementType)
            assertEquals("应与 classify(sql) 完全等价：${LogStatementClassifier.classify(sql)}",
                LogStatementClassifier.classify(sql), entry.statementType)
        }
    }

    @Test
    fun `条目缓存属性_copy后按新SQL重算`() {
        val entry = LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList())
        assertEquals(StatementType.SELECT, entry.statementType)
        val copied = entry.copy(restoredSql = "DELETE FROM t WHERE id = 1")
        assertEquals("copy 产生的实例应持有独立缓存（按新 SQL 重算）",
            StatementType.DELETE, copied.statementType)
        assertEquals("原实例缓存不受影响", StatementType.SELECT, entry.statementType)
    }

    @Test
    fun `条目缓存属性_不参与equals`() {
        // T-FlakyTs（2026-09-21）：**固定 timestamp**——`LogSqlEntry.timestamp` 默认取
        // `System.currentTimeMillis()`（`LogSqlModels.kt:46`），而 timestamp 参与 equals；两次构造
        // 跨过 1ms 边界就会假红（实测见过 1789926554606 vs …607）。本用例的本意只是断言
        // `statementType` 是 body 内属性、不进 equals，不该顺带比时间戳（引入者 `641c815` T-CrudColor）。
        val ts = 1_700_000_000_000L
        val a = LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(), timestamp = ts)
        assertEquals("两个同内容条目应相等：statementType 是 body 内属性，不进 equals",
            a, LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(), timestamp = ts))
    }

    // ---------- T-DdlTypeColor：DDL 独立成档 ----------

    fun testCreateAlterRename归为CREATE() {
        assertEquals(StatementType.CREATE, LogStatementClassifier.classify("CREATE TABLE t (id INT)"))
        assertEquals(StatementType.CREATE, LogStatementClassifier.classify("create table t (id INT)"))
        assertEquals(StatementType.CREATE, LogStatementClassifier.classify("ALTER TABLE t ADD COLUMN c INT"))
        assertEquals(StatementType.CREATE, LogStatementClassifier.classify("  \n  RENAME TABLE a TO b"))
    }

    fun testDropTruncate归为DROP() {
        assertEquals(StatementType.DROP, LogStatementClassifier.classify("DROP TABLE IF EXISTS t"))
        assertEquals(StatementType.DROP, LogStatementClassifier.classify("drop table t"))
        assertEquals(StatementType.DROP, LogStatementClassifier.classify("TRUNCATE TABLE t"))
        assertEquals(StatementType.DROP, LogStatementClassifier.classify("truncate t"))
    }

    /**
     * DDL 不复用 INSERT/DELETE 档。
     *
     * 为什么重要：筛选按 StatementType 勾选。若 CREATE 就是 INSERT，用户勾「只看插入」
     * 会把建表语句一起筛出来——语义错误。
     */
    fun testDdl不复用InsertDelete档() {
        assertNotSame(
            "CREATE 不得等于 INSERT",
            StatementType.INSERT,
            LogStatementClassifier.classify("CREATE TABLE t (id INT)"),
        )
        assertNotSame(
            "DROP 不得等于 DELETE",
            StatementType.DELETE,
            LogStatementClassifier.classify("DROP TABLE t"),
        )
        assertNotSame(
            "TRUNCATE 不得等于 DELETE",
            StatementType.DELETE,
            LogStatementClassifier.classify("TRUNCATE TABLE t"),
        )
    }

    /** 未识别的结构类语句仍留 OTHER（避免误导性归类）。 */
    fun test未识别语句仍为OTHER() {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("MERGE INTO t USING s ON (1=1)"))
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("REPLACE INTO t VALUES (1)"))
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("CALL some_proc()"))
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify(""))
    }

    /** 既有四类不受影响（回归确认）。 */
    fun test既有四类分类不回归() {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("SELECT 1"))
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("WITH c AS (SELECT 1) SELECT * FROM c"))
        assertEquals(StatementType.INSERT, LogStatementClassifier.classify("INSERT INTO t VALUES (1)"))
        assertEquals(StatementType.UPDATE, LogStatementClassifier.classify("UPDATE t SET a = 1"))
        assertEquals(StatementType.DELETE, LogStatementClassifier.classify("DELETE FROM t"))
    }
}
