package cn.song.mybatisplus.visualizer.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import org.junit.Test;

import java.util.List;

/**
 * SQL 语句类型分类器单元测试（P-06，11 用例覆盖全部分支 + 边界）。
 */
public class LogStatementClassifierTest {

    @Test
    public void SELECT标准大写() throws Exception {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("SELECT * FROM user"));
    }

    @Test
    public void SELECT小写() throws Exception {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("select * from user"));
    }

    @Test
    public void SELECT前导空白() throws Exception {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("\n  SELECT * FROM user"));
    }

    @Test
    public void WITH_CTE() throws Exception {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("WITH t AS (SELECT 1) SELECT * FROM t"));
    }

    @Test
    public void INSERT() throws Exception {
        assertEquals(StatementType.INSERT, LogStatementClassifier.classify("INSERT INTO user VALUES (1, 'a')"));
    }

    @Test
    public void UPDATE() throws Exception {
        assertEquals(StatementType.UPDATE, LogStatementClassifier.classify("UPDATE user SET name = 'x'"));
    }

    @Test
    public void DELETE() throws Exception {
        assertEquals(StatementType.DELETE, LogStatementClassifier.classify("DELETE FROM user WHERE id = 1"));
    }

    @Test
    public void MERGE归OTHER() throws Exception {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("MERGE INTO target USING source"));
    }

    @Test
    public void TRUNCATE归OTHER() throws Exception {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("TRUNCATE TABLE user"));
    }

    @Test
    public void 空串归OTHER() throws Exception {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify(""));
    }

    @Test
    public void 仅空白归OTHER() throws Exception {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("   "));
    }

    @Test
    public void 按条目分类取restoredSql() throws Exception {
        LogSqlEntry entry = new LogSqlEntry(
            "Preparing: SELECT 1",
            "SELECT * FROM t",
            List.<LogSqlParam>of(),
            0L
        );
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify(entry));
    }

    // ---------- T-CrudColor：条目级缓存属性 statementType ----------

    @Test
    public void 条目缓存属性与按串分类全类型等价() throws Exception {
        List<Object[]> cases = List.of(
            new Object[]{"SELECT * FROM user", StatementType.SELECT},
            new Object[]{"select * from user", StatementType.SELECT},
            new Object[]{"  \n SELECT 1", StatementType.SELECT},
            new Object[]{"WITH cte AS (SELECT 1) SELECT * FROM cte", StatementType.SELECT},
            new Object[]{"INSERT INTO user VALUES (1)", StatementType.INSERT},
            new Object[]{"UPDATE user SET a = 1", StatementType.UPDATE},
            new Object[]{"DELETE FROM user WHERE id = 1", StatementType.DELETE},
            new Object[]{"MERGE INTO t USING s ON (1=1)", StatementType.OTHER},
            new Object[]{"TRUNCATE TABLE user", StatementType.OTHER},
            new Object[]{"", StatementType.OTHER},
            new Object[]{"   ", StatementType.OTHER}
        );
        for (Object[] c : cases) {
            String sql = (String) c[0];
            StatementType expected = (StatementType) c[1];
            LogSqlEntry entry = new LogSqlEntry(sql, sql, List.of(), 0L);
            assertEquals("statementType 应与 classify(sql) 一致：" + sql, expected, entry.statementType());
            assertEquals("应与 classify(sql) 完全等价：" + LogStatementClassifier.classify(sql),
                LogStatementClassifier.classify(sql), entry.statementType());
        }
    }

    @Test
    public void 条目缓存属性_copy后按新SQL重算() throws Exception {
        LogSqlEntry entry = new LogSqlEntry("SELECT 1", "SELECT 1", List.of(), 0L);
        assertEquals(StatementType.SELECT, entry.statementType());
        // Kotlin data class copy(restoredSql = …) 的 Java 等价：同字段新实例（record 值相等共享缓存语义）
        LogSqlEntry copied = new LogSqlEntry(entry.rawSql(), "DELETE FROM t WHERE id = 1", entry.params(), entry.timestamp());
        assertEquals("copy 产生的实例应持有独立缓存（按新 SQL 重算）",
            StatementType.DELETE, copied.statementType());
        assertEquals("原实例缓存不受影响", StatementType.SELECT, entry.statementType());
    }

    @Test
    public void 条目缓存属性_不参与equals() throws Exception {
        LogSqlEntry a = new LogSqlEntry("SELECT 1", "SELECT 1", List.of(), 0L);
        assertEquals("两个同内容条目应相等：statementType 是 body 内属性，不进 equals",
            a, new LogSqlEntry("SELECT 1", "SELECT 1", List.of(), 0L));
    }

    // ---------- T-DdlTypeColor：DDL 独立成档 ----------

    @Test
    public void testCreateAlterRename归为CREATE() {
        assertEquals(StatementType.CREATE, LogStatementClassifier.classify("CREATE TABLE t (id INT)"));
        assertEquals(StatementType.CREATE, LogStatementClassifier.classify("create table t (id INT)"));
        assertEquals(StatementType.CREATE, LogStatementClassifier.classify("ALTER TABLE t ADD COLUMN c INT"));
        assertEquals(StatementType.CREATE, LogStatementClassifier.classify("  \n  RENAME TABLE a TO b"));
    }

    @Test
    public void testDropTruncate归为DROP() {
        assertEquals(StatementType.DROP, LogStatementClassifier.classify("DROP TABLE IF EXISTS t"));
        assertEquals(StatementType.DROP, LogStatementClassifier.classify("drop table t"));
        assertEquals(StatementType.DROP, LogStatementClassifier.classify("TRUNCATE TABLE t"));
        assertEquals(StatementType.DROP, LogStatementClassifier.classify("truncate t"));
    }

    /**
     * DDL 不复用 INSERT/DELETE 档。
     *
     * 为什么重要：筛选按 StatementType 勾选。若 CREATE 就是 INSERT，用户勾「只看插入」
     * 会把建表语句一起筛出来——语义错误。
     */
    @Test
    public void testDdl不复用InsertDelete档() {
        assertNotSame(
            "CREATE 不得等于 INSERT",
            StatementType.INSERT,
            LogStatementClassifier.classify("CREATE TABLE t (id INT)"));
        assertNotSame(
            "DROP 不得等于 DELETE",
            StatementType.DELETE,
            LogStatementClassifier.classify("DROP TABLE t"));
        assertNotSame(
            "TRUNCATE 不得等于 DELETE",
            StatementType.DELETE,
            LogStatementClassifier.classify("TRUNCATE TABLE t"));
    }

    /** 未识别的结构类语句仍留 OTHER（避免误导性归类）。 */
    @Test
    public void test未识别语句仍为OTHER() {
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("MERGE INTO t USING s ON (1=1)"));
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("REPLACE INTO t VALUES (1)"));
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify("CALL some_proc()"));
        assertEquals(StatementType.OTHER, LogStatementClassifier.classify(""));
    }

    /** 既有四类分类不回归（回归确认）。 */
    @Test
    public void test既有四类分类不回归() {
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("SELECT 1"));
        assertEquals(StatementType.SELECT, LogStatementClassifier.classify("WITH c AS (SELECT 1) SELECT * FROM c"));
        assertEquals(StatementType.INSERT, LogStatementClassifier.classify("INSERT INTO t VALUES (1)"));
        assertEquals(StatementType.UPDATE, LogStatementClassifier.classify("UPDATE t SET a = 1"));
        assertEquals(StatementType.DELETE, LogStatementClassifier.classify("DELETE FROM t"));
    }
}
