package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-01 类型感知字面量渲染：六类型 × 方言差异用例（闲时任务册 §一之二 F-01 验收）。
 *
 * 判定本体是纯函数（零平台依赖，本类进 testFast 快桶）；接线层（ParameterReplacer / 面板）
 * 的回退行为另由 `ParameterReplacerTypedLiteralTest` 与平台桶用例钉住。
 *
 * 反向验证锚点：摘掉 [TypedLiteralRenderer.render] 的类型分派（让它恒返回 null），
 * 本类全部「非 null 期望」用例恰红；还原后全绿。
 */
class TypedLiteralRendererTest {

    // ------------------------------------------------------------------
    // classify：三类来源归一（Java 类型名 / JDBC 类型名 / ParamKind 枚举名）
    // ------------------------------------------------------------------

    @Test
    fun classify_Java类型名与全限定名() {
        assertEquals(TypedLiteralRenderer.LiteralType.NUMBER, TypedLiteralRenderer.classify("Long"))
        assertEquals(TypedLiteralRenderer.LiteralType.NUMBER, TypedLiteralRenderer.classify("java.math.BigDecimal"))
        assertEquals(TypedLiteralRenderer.LiteralType.TEXT, TypedLiteralRenderer.classify("String"))
        assertEquals(TypedLiteralRenderer.LiteralType.TEMPORAL, TypedLiteralRenderer.classify("java.time.LocalDateTime"))
        assertEquals(TypedLiteralRenderer.LiteralType.BOOLEAN, TypedLiteralRenderer.classify("Boolean"))
        assertEquals(TypedLiteralRenderer.LiteralType.BINARY, TypedLiteralRenderer.classify("byte[]"))
        assertEquals(TypedLiteralRenderer.LiteralType.BINARY, TypedLiteralRenderer.classify("java.lang.Byte[]"))
    }

    @Test
    fun classify_JDBC类型名() {
        assertEquals(TypedLiteralRenderer.LiteralType.NUMBER, TypedLiteralRenderer.classify("NUMERIC"))
        assertEquals(TypedLiteralRenderer.LiteralType.TEXT, TypedLiteralRenderer.classify("VARCHAR"))
        assertEquals(TypedLiteralRenderer.LiteralType.TEMPORAL, TypedLiteralRenderer.classify("TIMESTAMP"))
        assertEquals(TypedLiteralRenderer.LiteralType.TEMPORAL, TypedLiteralRenderer.classify("Timestamp"))
        assertEquals(TypedLiteralRenderer.LiteralType.BOOLEAN, TypedLiteralRenderer.classify("BIT"))
        assertEquals(TypedLiteralRenderer.LiteralType.BINARY, TypedLiteralRenderer.classify("BLOB"))
    }

    @Test
    fun classify_ParamKind枚举名与未知值() {
        assertEquals(TypedLiteralRenderer.LiteralType.TEXT, TypedLiteralRenderer.classify("TEXT"))
        assertEquals(TypedLiteralRenderer.LiteralType.NUMBER, TypedLiteralRenderer.classify("NUMBER"))
        assertEquals(TypedLiteralRenderer.LiteralType.TEMPORAL, TypedLiteralRenderer.classify("TEMPORAL"))
        // ParamKind 的 COLLECTION / OBJECT 不参与字面量判定（集合/对象不是字面量类别）
        assertNull(TypedLiteralRenderer.classify("COLLECTION"))
        assertNull(TypedLiteralRenderer.classify("OBJECT"))
        assertNull(TypedLiteralRenderer.classify("UNKNOWN"))
        assertNull(TypedLiteralRenderer.classify("UserEntity"))
        assertNull(TypedLiteralRenderer.classify(null))
        assertNull(TypedLiteralRenderer.classify("  "))
    }

    // ------------------------------------------------------------------
    // 字符串：引号与转义的方言差异（MySQL 反斜杠加倍，其余 `''`）
    // ------------------------------------------------------------------

    @Test
    fun 字符串_单引号转义_三方言一致() {
        assertEquals("'don''t'", TypedLiteralRenderer.render("don't", "String", null))
        assertEquals("'don''t'", TypedLiteralRenderer.render("don't", "VARCHAR", "Oracle"))
        assertEquals("'don''t'", TypedLiteralRenderer.render("don't", "TEXT", "PostgreSQL"))
        assertEquals("'don''t'", TypedLiteralRenderer.render("don't", "String", "MySQL"))
    }

    @Test
    fun 字符串_反斜杠_MySQL族加倍_其余原样() {
        assertEquals("'a\\\\b'", TypedLiteralRenderer.render("a\\b", "String", "MySQL"))
        assertEquals("'a\\\\b'", TypedLiteralRenderer.render("a\\b", "VARCHAR", "MariaDB"))
        assertEquals("'a\\b'", TypedLiteralRenderer.render("a\\b", "String", "Oracle"))
        assertEquals("'a\\b'", TypedLiteralRenderer.render("a\\b", "String", "PostgreSQL"))
        assertEquals("'a\\b'", TypedLiteralRenderer.render("a\\b", "String", null))
    }

    // ------------------------------------------------------------------
    // 数字：恒原样（ORA-01722 主场景——科学计数不得被引号兜底吃掉）
    // ------------------------------------------------------------------

    @Test
    fun 数字_三方言恒裸渲染() {
        for (dialect in arrayOf(null, "MySQL", "Oracle", "PostgreSQL")) {
            assertEquals("123", TypedLiteralRenderer.render("123", "Long", dialect))
            assertEquals("1.5", TypedLiteralRenderer.render("1.5", "NUMERIC", dialect))
            assertEquals("-3", TypedLiteralRenderer.render("-3", "Integer", dialect))
            assertEquals("1.0E10", TypedLiteralRenderer.render("1.0E10", "BigDecimal", dialect))
            assertEquals("1E-7", TypedLiteralRenderer.render("1E-7", "Double", dialect))
        }
    }

    @Test
    fun 数字_非数值形态回退null() {
        assertNull(TypedLiteralRenderer.render("abc", "Long", "Oracle"))
        assertNull(TypedLiteralRenderer.render("12,300", "NUMERIC", null))
        assertNull(TypedLiteralRenderer.render("0x1F", "Integer", null))
    }

    // ------------------------------------------------------------------
    // 日期/时间戳：MySQL 族引号串，Oracle/PG/通用 ANSI 类型字面量
    // ------------------------------------------------------------------

    @Test
    fun 日期_方言差异() {
        assertEquals("DATE '2024-01-01'", TypedLiteralRenderer.render("2024-01-01", "LocalDate", null))
        assertEquals("DATE '2024-01-01'", TypedLiteralRenderer.render("2024-01-01", "DATE", "Oracle"))
        assertEquals("DATE '2024-01-01'", TypedLiteralRenderer.render("2024-01-01", "LocalDate", "PostgreSQL"))
        assertEquals("'2024-01-01'", TypedLiteralRenderer.render("2024-01-01", "DATE", "MySQL"))
        assertEquals("'2024-01-01'", TypedLiteralRenderer.render("2024-01-01", "DATE", "MariaDB"))
    }

    @Test
    fun 时间戳_T分隔符与缺秒归一() {
        assertEquals(
            "TIMESTAMP '2024-01-01 12:30:00'",
            TypedLiteralRenderer.render("2024-01-01T12:30", "LocalDateTime", null),
        )
        assertEquals(
            "TIMESTAMP '2024-01-01 12:30:00'",
            TypedLiteralRenderer.render("2024-01-01 12:30:00", "TIMESTAMP", "Oracle"),
        )
        assertEquals(
            "TIMESTAMP '2024-01-01 12:30:00.0'",
            TypedLiteralRenderer.render("2024-01-01T12:30:00.0", "Timestamp", "PostgreSQL"),
        )
        assertEquals(
            "'2024-01-01 12:30:00'",
            TypedLiteralRenderer.render("2024-01-01 12:30:00", "DATETIME", "MySQL"),
        )
    }

    @Test
    fun 纯时刻_恒引号_Oracle不支持TIME字面量() {
        assertEquals("'12:30:00'", TypedLiteralRenderer.render("12:30:00", "LocalTime", null))
        assertEquals("'12:30:00'", TypedLiteralRenderer.render("12:30:00", "TIME", "Oracle"))
        assertEquals("'12:30:00'", TypedLiteralRenderer.render("12:30:00", "TIME", "MySQL"))
    }

    @Test
    fun 时间_值形态与类型不符回退null() {
        assertNull(TypedLiteralRenderer.render("2025", "LocalDate", null))
        assertNull(TypedLiteralRenderer.render("明天", "DATE", "Oracle"))
    }

    // ------------------------------------------------------------------
    // 布尔：按方言（Oracle 族 1/0，其余 TRUE/FALSE）
    // ------------------------------------------------------------------

    @Test
    fun 布尔_方言差异() {
        assertEquals("TRUE", TypedLiteralRenderer.render("true", "Boolean", null))
        assertEquals("FALSE", TypedLiteralRenderer.render("false", "BOOLEAN", "PostgreSQL"))
        assertEquals("TRUE", TypedLiteralRenderer.render("true", "BIT", "MySQL"))
        assertEquals("1", TypedLiteralRenderer.render("true", "Boolean", "Oracle"))
        assertEquals("0", TypedLiteralRenderer.render("false", "Boolean", "DM（达梦）"))
        // 值不是布尔字样 → 回退
        assertNull(TypedLiteralRenderer.render("yes", "Boolean", null))
    }

    // ------------------------------------------------------------------
    // 二进制：hex 形态按方言出函数/X 字面量，判不了回退
    // ------------------------------------------------------------------

    @Test
    fun 二进制_方言差异() {
        assertEquals("X'1A2B'", TypedLiteralRenderer.render("1A2B", "byte[]", "MySQL"))
        assertEquals("decode('1A2B','hex')", TypedLiteralRenderer.render("1A2B", "BLOB", "PostgreSQL"))
        assertEquals("HEXTORAW('1A2B')", TypedLiteralRenderer.render("1A2B", "VARBINARY", "Oracle"))
        // 方言未知 / 值非 hex → 回退（引号档），不臆造函数形态
        assertNull(TypedLiteralRenderer.render("1A2B", "BLOB", null))
        assertNull(TypedLiteralRenderer.render("[B@1a2b3c", "byte[]", "MySQL"))
        assertNull(TypedLiteralRenderer.render("1A2", "byte[]", "MySQL"))
    }

    // ------------------------------------------------------------------
    // NULL 字面量与守门（空值 / 无类型）
    // ------------------------------------------------------------------

    @Test
    fun null字样_非字符串类出NULL_字符串类引号直出() {
        assertEquals("NULL", TypedLiteralRenderer.render("null", "Long", null))
        assertEquals("NULL", TypedLiteralRenderer.render("NULL", "TIMESTAMP", "Oracle"))
        assertEquals("'null'", TypedLiteralRenderer.render("null", "String", "Oracle"))
    }

    @Test
    fun 守门_空值与无类型一律回退null() {
        assertNull(TypedLiteralRenderer.render(null, "Long", "Oracle"))
        assertNull(TypedLiteralRenderer.render("", "String", null))
        assertNull(TypedLiteralRenderer.render("  ", "String", null))
        assertNull(TypedLiteralRenderer.render("123", null, "Oracle"))
    }
}
