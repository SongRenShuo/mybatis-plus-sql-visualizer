package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日期族 ANSI 字面量渲染测试（T-DateLiteralBackfill）。
 *
 * 覆盖 R3 缺口矩阵的核心形态：ISO `T` 归一、缺秒补 `:00`、date-only → `DATE`、
 * datetime → `TIMESTAMP`、形态不匹配回落 null，以及 SQL Server / SQLite 方言门禁。
 */
class SqlDateLiteralTest {

    // ------------------------------------------------------------------
    // 类型判定
    // ------------------------------------------------------------------

    @Test
    fun `日期族类型判定`() {
        listOf(
            "LocalDate", "LocalDateTime", "Timestamp", "Date",
            "DATE", "TIMESTAMP", "DATETIME",
            "java.time.LocalDateTime", "java.sql.Timestamp", "java.util.Date",
        ).forEach { assertTrue("$it 应为日期族", SqlDateLiteral.isTemporalType(it)) }

        listOf("String", "Long", "Integer", "BigDecimal", "Boolean", "LocalTime", "OffsetDateTime", null)
            .forEach { assertFalse("$it 不应为日期族", SqlDateLiteral.isTemporalType(it)) }
    }

    // ------------------------------------------------------------------
    // 按形态渲染
    // ------------------------------------------------------------------

    @Test
    fun `纯日期产出DATE字面量`() {
        assertEquals("DATE '2026-11-07'", SqlDateLiteral.renderByShape("2026-11-07"))
    }

    @Test
    fun `ISO的T归一为空格`() {
        assertEquals(
            "TIMESTAMP '2026-11-07 13:20:14.417'",
            SqlDateLiteral.renderByShape("2026-11-07T13:20:14.417"),
        )
        assertEquals(
            "TIMESTAMP '2026-11-07 13:20:14'",
            SqlDateLiteral.renderByShape("2026-11-07T13:20:14"),
        )
    }

    @Test
    fun `空格形态原样保留`() {
        assertEquals(
            "TIMESTAMP '2026-11-07 13:20:14.417'",
            SqlDateLiteral.renderByShape("2026-11-07 13:20:14.417"),
        )
    }

    @Test
    fun `缺秒补00`() {
        assertEquals("TIMESTAMP '2026-11-07 13:20:00'", SqlDateLiteral.renderByShape("2026-11-07 13:20"))
        assertEquals("TIMESTAMP '2026-11-07 13:20:00'", SqlDateLiteral.renderByShape("2026-11-07T13:20"))
    }

    @Test
    fun `九位小数保留`() {
        assertEquals(
            "TIMESTAMP '2026-11-07 13:20:14.123456789'",
            SqlDateLiteral.renderByShape("2026-11-07T13:20:14.123456789"),
        )
    }

    @Test
    fun `形态不匹配返回null`() {
        listOf("10:15:30", "abc", "2026-11-07T13:20:14Z", "2026-11-07T13:20:14+08:00", "").forEach {
            assertNull("$it 不应被包装", SqlDateLiteral.renderByShape(it))
        }
    }

    @Test
    fun `SQLServer与SQLite门禁回落null`() {
        assertNull(SqlDateLiteral.renderByShape("2026-11-07T13:20:14.417", "SQL Server"))
        assertNull(SqlDateLiteral.renderByShape("2026-11-07T13:20:14.417", "SQLite"))
        assertEquals(
            "TIMESTAMP '2026-11-07 13:20:14.417'",
            SqlDateLiteral.renderByShape("2026-11-07T13:20:14.417", "Oracle"),
        )
        assertEquals(
            "TIMESTAMP '2026-11-07 13:20:14.417'",
            SqlDateLiteral.renderByShape("2026-11-07T13:20:14.417", null),
        )
    }

    // ------------------------------------------------------------------
    // 类型 + 值（R3 family 限制）
    // ------------------------------------------------------------------

    @Test
    fun `LocalDate只接受纯日期形态`() {
        assertEquals("DATE '2026-11-07'", SqlDateLiteral.renderFor("2026-11-07", "LocalDate"))
        assertNull(SqlDateLiteral.renderFor("2026-11-07 13:20:14", "LocalDate"))
    }

    @Test
    fun `Date两态都接受`() {
        assertEquals("DATE '2026-11-07'", SqlDateLiteral.renderFor("2026-11-07", "Date"))
        assertEquals(
            "TIMESTAMP '2026-11-07 13:20:14.0'",
            SqlDateLiteral.renderFor("2026-11-07 13:20:14.0", "java.util.Date"),
        )
    }

    @Test
    fun `Timestamp族纯日期产出DATE`() {
        // D4=A：TIMESTAMP 列填纯日期 → DATE 字面量（Oracle 隐式 DATE→TIMESTAMP 等价当日 0 点）
        assertEquals("DATE '2026-11-07'", SqlDateLiteral.renderFor("2026-11-07", "LocalDateTime"))
        assertEquals("DATE '2026-11-07'", SqlDateLiteral.renderFor("2026-11-07", "TIMESTAMP"))
    }

    @Test
    fun `LocalDateTime的ISO形态归一`() {
        assertEquals(
            "TIMESTAMP '2026-11-07 13:20:14.417'",
            SqlDateLiteral.renderFor("2026-11-07T13:20:14.417", "LocalDateTime"),
        )
        assertEquals(
            "TIMESTAMP '2026-11-07 13:20:14.417'",
            SqlDateLiteral.renderFor("2026-11-07T13:20:14.417", "java.time.LocalDateTime"),
        )
    }

    @Test
    fun `非日期族类型不包装`() {
        assertNull(SqlDateLiteral.renderFor("2026-11-07", "String"))
        assertNull(SqlDateLiteral.renderFor("2026-11-07", "Long"))
        assertNull(SqlDateLiteral.renderFor("2026-11-07", null))
        assertNull(SqlDateLiteral.renderFor("10:15:30", "LocalTime"))
    }
}
