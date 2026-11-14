package cn.song.mybatisplus.visualizer.sql.rollback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-RollbackSnapshotPreExec P4 纯逻辑测试（testFast 快桶）：从快照条目生成回滚 SQL。
 * UPDATE 走按行装配的 CAS 谓词；DELETE 复用生成器 BACKFILL_INSERT；恒 PARTIAL 诚实评级。
 */
class RollbackSnapshotRollbackSqlTest {

    @Test
    fun updateRollbackPerRowWithCas() {
        val aligned = listOf(
            RollbackSnapshotAligner.AlignedRow(
                RollbackSnapshotAligner.CellStatus.CHANGED, "1",
                beforeCells = listOf("1", "old"), afterCells = listOf("1", "new"),
                changedColumnIndexes = setOf(1),
            ),
            RollbackSnapshotAligner.AlignedRow(
                RollbackSnapshotAligner.CellStatus.EQUAL, "2",
                beforeCells = listOf("2", "keep"), afterCells = listOf("2", "keep"),
            ),
        )
        val result = RollbackSnapshotRollbackSql.buildUpdateRollback(
            "t_order", listOf("id", "status"), keyColumnIndexes = listOf(0), alignedRows = aligned,
        )
        assertEquals(1, result.statementCount)
        val sql = result.sqlText
        assertTrue("SET 应取改前值", sql.contains("SET status = 'old'"))
        assertTrue("WHERE 应含主键旧值", sql.contains("id = 1"))
        assertTrue("WHERE 应含改动列新值（CAS）", sql.contains("status = 'new'"))
        assertEquals(RollbackSqlGenerator.Rating.PARTIAL, result.rating)
    }

    @Test
    fun updateRollbackNullOldValueUsesIsNull() {
        val aligned = listOf(
            RollbackSnapshotAligner.AlignedRow(
                RollbackSnapshotAligner.CellStatus.CHANGED, "7",
                beforeCells = listOf("7", ""), afterCells = listOf("7", "x"),
                changedColumnIndexes = setOf(1),
            ),
        )
        val result = RollbackSnapshotRollbackSql.buildUpdateRollback(
            "t", listOf("id", "v"), keyColumnIndexes = listOf(0), alignedRows = aligned,
        )
        // NULL/空串在字符串化快照里不可区分——SET 侧按 '' 落（类 KDoc 已声明该局限）；
        // 谓词侧：主键非空落 = ，CAS 侧取改后值 'x'
        assertTrue("SET 侧按 '' 落", result.sqlText.contains("SET v = ''"))
        assertTrue("WHERE 应含主键旧值", result.sqlText.contains("id = 7"))
        assertTrue("CAS 侧应取改后值", result.sqlText.contains("v = 'x'"))
    }

    @Test
    fun updateRollbackNoKeyIsUnable() {
        val result = RollbackSnapshotRollbackSql.buildUpdateRollback(
            "t", listOf("a"), keyColumnIndexes = emptyList(), alignedRows = emptyList(),
        )
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
        assertEquals("", result.sqlText)
    }

    @Test
    fun deleteRollbackReusesBackfillInsert() {
        val result = RollbackSnapshotRollbackSql.buildDeleteRollback(
            "t_order", listOf("id", "status"),
            listOf(listOf("1", "paid")),
            primaryKeyColumns = listOf("id"),
        )
        assertEquals(1, result.statementCount)
        assertTrue("DELETE 回滚应产 INSERT 回填", result.sqlText.contains("INSERT INTO"))
        assertEquals(RollbackSqlGenerator.Rating.PARTIAL, result.rating)
    }

    @Test
    fun deleteRollbackNoRowsIsEmpty() {
        val result = RollbackSnapshotRollbackSql.buildDeleteRollback(
            "t", listOf("id"), emptyList(), primaryKeyColumns = listOf("id"),
        )
        assertEquals(0, result.statementCount)
    }
}
