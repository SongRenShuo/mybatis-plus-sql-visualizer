package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览执行范围判定测试（T-DateLiteralBackfill R10）。
 *
 * 回归现场（2026-11-09，PG）：日期族改为 ANSI 类型字面量后，长 SQL 的
 * `AND … >= TIMESTAMP '…'` 行被控制台折行，DBN 按「光标所在语句」把
 * `TIMESTAMP` 片段当独立语句提交，PostgreSQL 报 42601。单语句预览改整批提交。
 */
class ExecutionScriptScopeTest {

    private val regressedSql = """
        SELECT COALESCE(SUM(o.charge_degree), 0) AS chargeDegree
        FROM biz_bike_order_record o
        WHERE o.deleted = 0
            AND EXISTS (
                SELECT 1 FROM sys_merchant_share_record r
                WHERE r.biz_id = o.id
                    AND r.create_time >= TIMESTAMP '2025-12-31 00:00:00'
                    AND r.create_time <= TIMESTAMP '2026-11-08 23:59:59.999999999')
        UNION ALL
        SELECT COALESCE(SUM(o.charge_degree), 0) AS chargeDegree
        FROM biz_car_order_record o
    """.trimIndent()

    @Test
    fun `单语句含ANSI字面量_整批提交`() {
        assertTrue(shouldExecWholeScript(regressedSql))
    }

    @Test
    fun `单语句带尾分号_整批提交`() {
        assertTrue(shouldExecWholeScript("SELECT 1 FROM dual;"))
    }

    @Test
    fun `多语句预览_保持当前语句逻辑`() {
        val mainPlusCount = "SELECT * FROM t;\n-- COUNT\nSELECT COUNT(*) FROM t;"
        assertFalse(shouldExecWholeScript(mainPlusCount))
    }

    @Test
    fun `调用方显式要求整批_直接透传`() {
        val two = "SET IDENTITY_INSERT t ON;\nINSERT INTO t VALUES (1);\nSET IDENTITY_INSERT t OFF;"
        assertTrue(shouldExecWholeScript(two, requested = true))
    }

    @Test
    fun `空文本_按整批处理`() {
        assertTrue(shouldExecWholeScript(""))
    }
}
