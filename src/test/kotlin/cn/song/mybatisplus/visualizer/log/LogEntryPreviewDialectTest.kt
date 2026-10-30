package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-DialectInferFix 券 02：日志/历史条目 → 预览必须带上「这条 SQL 当时连的库」。
 *
 * 真机根因：旧实现 `toPreviewResult` 不带方言，预览 `effectiveDialect` 为 null →
 * 渲染回落 MySQL、临时文件被映射成 MySQL，PG 合法列名（`signal`）被 MySQL 保留字表标红。
 */
class LogEntryPreviewDialectTest {

    @Test
    fun `captured dialect becomes exact preview dialect`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT signal FROM biz_car_device_info",
            restoredSql = "SELECT signal FROM biz_car_device_info",
            params = emptyList(),
            dialectName = "PostgreSQL",
        )
        val result = entry.toPreviewResult(formatSql = false, literalRecovery = false, capturedDialect = entry.dialectName)
        assertEquals("PostgreSQL", result.renderedDialect?.name)
        assertEquals("PostgreSQL", result.effectiveDialect?.name)
        assertEquals(DialectDecisionEngine.Source.CAPTURED_URL, result.dialectDecision?.source)
        assertEquals(DialectDecisionEngine.Confidence.EXACT, result.dialectDecision?.confidence)
    }

    @Test
    fun `legacy entry falls back to persisted badge`() {
        // 旧条目没有 pd 载荷的 dl 键，但角标随 `-- … | db: …` 头持久化过
        val entry = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            databaseName = "PostgreSQL · cyzc_db1@121.40.45.91:5432",
        )
        val result = entry.toPreviewResult(formatSql = false, literalRecovery = false)
        assertEquals("PostgreSQL", result.renderedDialect?.name)
        assertEquals(DialectDecisionEngine.Source.CAPTURED_URL, result.dialectDecision?.source)
    }

    @Test
    fun `entry without any dialect evidence stays unresolved`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            databaseName = "cyzc_db1@121.40.45.91:5432",
        )
        val result = entry.toPreviewResult(formatSql = false, literalRecovery = false)
        assertNull(result.renderedDialect)
        assertNull(result.dialectDecision)
    }

    @Test
    fun `unknown dialect name is not accepted as evidence`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            dialectName = "HSQL",
            databaseName = "HSQL · local:h2",
        )
        val result = entry.toPreviewResult(formatSql = false, literalRecovery = false)
        assertNull(result.renderedDialect)
        assertNull(result.dialectDecision)
    }
}
