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
 *
 * k2j 互操作注（master afd3d091 终态移植）：
 *  - toPreviewResult 为 LogEntryPreviewRecognizerKt 静态方法（capturedDialect 走 4 参重载，位置实参）
 *  - 条目经 23 参规范构造器（databaseName/dialectName 后置段位置传参）
 *  - Decision 为 Java record：source()/confidence() 显式调用
 */
class LogEntryPreviewDialectTest {

    private fun entry(sql: String, databaseName: String? = null, dialectName: String? = null) = LogSqlEntry(
        sql, sql, emptyList(), 1_700_000_000_000L,
        null, null, SqlSource.UNKNOWN, null,
        null, 0, emptyList(), emptyList(),
        null, null, 0L,
        null, null, null, null,
        databaseName, dialectName, null, null,
    )

    @Test
    fun `captured dialect becomes exact preview dialect`() {
        val entry = entry("SELECT signal FROM biz_car_device_info", dialectName = "PostgreSQL")
        val result = LogEntryPreviewRecognizerKt.toPreviewResult(entry, false, false, entry.dialectName())
        assertEquals("PostgreSQL", result.renderedDialect?.name)
        assertEquals("PostgreSQL", result.effectiveDialect?.name)
        assertEquals(DialectDecisionEngine.Source.CAPTURED_URL, result.dialectDecision?.source())
        assertEquals(DialectDecisionEngine.Confidence.EXACT, result.dialectDecision?.confidence())
    }

    @Test
    fun `legacy entry falls back to persisted badge`() {
        // 旧条目没有 pd 载荷的 dl 键，但角标随 `-- … | db: …` 头持久化过
        val entry = entry("SELECT 1", databaseName = "PostgreSQL · cyzc_db1@121.40.45.91:5432")
        val result = LogEntryPreviewRecognizerKt.toPreviewResult(entry, false, false)
        assertEquals("PostgreSQL", result.renderedDialect?.name)
        assertEquals(DialectDecisionEngine.Source.CAPTURED_URL, result.dialectDecision?.source())
    }

    @Test
    fun `entry without any dialect evidence stays unresolved`() {
        val entry = entry("SELECT 1", databaseName = "cyzc_db1@121.40.45.91:5432")
        val result = LogEntryPreviewRecognizerKt.toPreviewResult(entry, false, false)
        assertNull(result.renderedDialect)
        assertNull(result.dialectDecision)
    }

    @Test
    fun `unknown dialect name is not accepted as evidence`() {
        val entry = entry("SELECT 1", databaseName = "HSQL · local:h2", dialectName = "HSQL")
        val result = LogEntryPreviewRecognizerKt.toPreviewResult(entry, false, false)
        assertNull(result.renderedDialect)
        assertNull(result.dialectDecision)
    }
}
