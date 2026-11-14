package cn.song.mybatisplus.visualizer.sql.rollback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-RollbackSnapshotPreExec P3 纯逻辑测试（testFast 快桶）：主键启发式、改后反查 SQL、
 * 行对齐（主键/行序降级/无主键不等）、引号策略。
 */
class RollbackSnapshotAlignerTest {

    @Test
    fun guessKeyByIdColumn() {
        assertEquals(listOf(1), RollbackSnapshotAligner.guessKeyColumnIndexes(listOf("name", "ID", "age"), "t_order"))
    }

    @Test
    fun guessKeyByTableIdColumn() {
        assertEquals(listOf(0), RollbackSnapshotAligner.guessKeyColumnIndexes(listOf("order_id", "name"), "t_orders"))
        // 复数表名去尾 s 的形态也认
        assertEquals(listOf(0), RollbackSnapshotAligner.guessKeyColumnIndexes(listOf("order_id", "name"), "t_order"))
    }

    @Test
    fun guessKeyEmptyWhenNothingLooksLikePk() {
        assertTrue(RollbackSnapshotAligner.guessKeyColumnIndexes(listOf("code", "name"), "t_order").isEmpty())
    }

    @Test
    fun postSelectSingleKeyNumeric() {
        val sqls = RollbackSnapshotAligner.buildPostSelectSql(
            "t_order", listOf("id", "status"),
            listOf(listOf("1", "a"), listOf("5", "b")),
            keyColumnIndexes = listOf(0),
        )
        assertEquals(1, sqls.size)
        assertEquals("SELECT * FROM t_order WHERE id IN (1, 5)", sqls[0])
    }

    @Test
    fun postSelectQuotesNonNumeric() {
        val sqls = RollbackSnapshotAligner.buildPostSelectSql(
            "t_order", listOf("id", "name"),
            listOf(listOf("x", "a'b")),
            keyColumnIndexes = listOf(1),
        )
        assertEquals("SELECT * FROM t_order WHERE name IN ('a''b')", sqls[0])
    }

    @Test
    fun postSelectCompositeKeyTuple() {
        val sqls = RollbackSnapshotAligner.buildPostSelectSql(
            "t", listOf("a", "b", "c"),
            listOf(listOf("1", "x"), listOf("2", "y")),
            keyColumnIndexes = listOf(0, 1),
        )
        assertEquals(1, sqls.size)
        assertEquals("SELECT * FROM t WHERE (a, b) IN ((1, 'x'), (2, 'y'))", sqls[0])
    }

    @Test
    fun postSelectNoKeyMeansSkip() {
        assertTrue(
            RollbackSnapshotAligner.buildPostSelectSql("t", listOf("c"), listOf(listOf("x")), emptyList()).isEmpty(),
        )
    }

    @Test
    fun alignKeylessEqualRowsByOrder() {
        val result = RollbackSnapshotAligner.align(
            listOf("a"),
            listOf(listOf("1"), listOf("2")),
            listOf(listOf("9"), listOf("2")),
            keyColumnIndexes = emptyList(),
        )
        assertTrue(result.keylessByOrder)
        assertEquals(
            listOf(RollbackSnapshotAligner.CellStatus.CHANGED, RollbackSnapshotAligner.CellStatus.EQUAL),
            result.rows.map { it.status },
        )
        assertTrue(result.warnings.first().contains("行序"))
    }

    @Test
    fun alignKeylessUnequalRowsUnmatched() {
        val result = RollbackSnapshotAligner.align(
            listOf("a"),
            listOf(listOf("1"), listOf("2")),
            listOf(listOf("9")),
            keyColumnIndexes = emptyList(),
        )
        assertTrue(!result.keylessByOrder)
        assertEquals(3, result.rows.size) // 2 删除 + 1 新增
    }

    @Test
    fun quoteLiteralRules() {
        assertEquals("123", RollbackSnapshotAligner.quoteLiteral("123"))
        assertEquals("-1.5", RollbackSnapshotAligner.quoteLiteral("-1.5"))
        assertEquals("''", RollbackSnapshotAligner.quoteLiteral(""))
        assertEquals("'abc'", RollbackSnapshotAligner.quoteLiteral("abc"))
        assertEquals("'it''s'", RollbackSnapshotAligner.quoteLiteral("it's"))
        assertEquals("'12abc'", RollbackSnapshotAligner.quoteLiteral("12abc"))
    }
}
