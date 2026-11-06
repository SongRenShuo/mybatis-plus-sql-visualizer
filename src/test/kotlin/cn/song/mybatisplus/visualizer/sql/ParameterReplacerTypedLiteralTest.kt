package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-01 类型感知字面量在参数注入链（[ParameterReplacer]）的接线行为：
 *  - typeText 在场 → 走 [TypedLiteralRenderer]（类型 × 方言）；
 *  - **typeText 缺失 / 渲染器判不了 → 与升级前逐字节一致**（回退快照，闲时任务册 F-01 验收）；
 *  - dialect 经新参数传入，缺省 null = 通用档（既有调用方零改动）。
 *
 * 纯逻辑（零平台依赖）→ testFast 快桶。
 */
class ParameterReplacerTypedLiteralTest {

    // ------------------------------------------------------------------
    // 类型感知：数字列传字符串（ORA-01722 主场景）
    // ------------------------------------------------------------------

    @Test
    fun 类型在场_科学计数值按类型裸渲染() {
        val sql = ParameterReplacer.buildExecutableSql(
            sql = "select * from t where amount = ? and id = ?",
            sources = listOf("amount", "id"),
            paramValues = mapOf("amount" to "1.0E10", "id" to "7"),
            hints = listOf(ParamHint(typeText = "BigDecimal"), ParamHint(typeText = "Long")),
        )
        assertEquals("select * from t where amount = 1.0E10 and id = 7", sql)
    }

    @Test
    fun 类型在场_无类型启发式会引号_类型感知救回() {
        // 同值无类型：NUMBER_REGEX 不收科学计数 → 走引号兜底（升级前形态，正是 ORA-01722 来源）
        val untyped = ParameterReplacer.buildExecutableSql(
            sql = "select * from t where amount = ?",
            sources = listOf("amount"),
            paramValues = mapOf("amount" to "1.0E10"),
        )
        assertEquals("select * from t where amount = '1.0E10'", untyped)
    }

    @Test
    fun 类型在场_日期按方言出形态() {
        val oracle = ParameterReplacer.buildExecutableSql(
            sql = "select * from t where created >= ?",
            sources = listOf("created"),
            paramValues = mapOf("created" to "2024-01-01"),
            hints = listOf(ParamHint(quote = true, typeText = "DATE")),
            dialect = "Oracle",
        )
        assertEquals("select * from t where created >= DATE '2024-01-01'", oracle)

        val mysql = ParameterReplacer.buildExecutableSql(
            sql = "select * from t where created >= ?",
            sources = listOf("created"),
            paramValues = mapOf("created" to "2024-01-01"),
            hints = listOf(ParamHint(quote = true, typeText = "DATE")),
            dialect = "MySQL",
        )
        assertEquals("select * from t where created >= '2024-01-01'", mysql)
    }

    @Test
    fun 类型在场_布尔按方言出形态() {
        val oracle = ParameterReplacer.buildExecutableSql(
            sql = "update t set flag = ?",
            sources = listOf("flag"),
            paramValues = mapOf("flag" to "true"),
            hints = listOf(ParamHint(quote = true, typeText = "Boolean")),
            dialect = "Oracle",
        )
        assertEquals("update t set flag = 1", oracle)

        val pg = ParameterReplacer.buildExecutableSql(
            sql = "update t set flag = ?",
            sources = listOf("flag"),
            paramValues = mapOf("flag" to "true"),
            hints = listOf(ParamHint(quote = true, typeText = "BOOLEAN")),
            dialect = "PostgreSQL",
        )
        assertEquals("update t set flag = TRUE", pg)
    }

    // ------------------------------------------------------------------
    // 回退快照：无类型信息时行为与升级前逐字节一致
    // ------------------------------------------------------------------

    @Test
    fun 回退_typeText判不了时落回quote规则() {
        // typeText 是未知类型（实体名）→ 渲染器 null → quote=false 原样（与升级前一致）
        val fallback = ParameterReplacer.buildExecutableSql(
            sql = "select * from t where code = ?",
            sources = listOf("code"),
            paramValues = mapOf("code" to "abc"),
            hints = listOf(ParamHint(quote = false, typeText = "UserEntity")),
        )
        assertEquals("select * from t where code = abc", fallback)
    }

    @Test
    fun 回退_无hint时启发式不变() {
        val sql = ParameterReplacer.buildExecutableSql(
            sql = "select * from t where a = ? and b = ?",
            sources = listOf("a", "b"),
            paramValues = mapOf("a" to "42", "b" to "x"),
        )
        assertEquals("select * from t where a = 42 and b = 'x'", sql)
    }

    @Test
    fun 回退_空值仍保留占位符() {
        val sql = ParameterReplacer.buildExecutableSql(
            sql = "select * from t where a = ?",
            sources = listOf("a"),
            paramValues = mapOf("a" to "  "),
            hints = listOf(ParamHint(typeText = "Long")),
        )
        assertEquals("select * from t where a = ?", sql)
    }

    @Test
    fun 回退_单引号转义不变() {
        val sql = ParameterReplacer.buildExecutableSql(
            sql = "select * from t where name = ?",
            sources = listOf("name"),
            paramValues = mapOf("name" to "o'neil"),
            hints = listOf(ParamHint(quote = true)),
        )
        assertEquals("select * from t where name = 'o''neil'", sql)
    }

    // ------------------------------------------------------------------
    // 命名参数链（NamedParameterJdbcTemplate）同口径
    // ------------------------------------------------------------------

    @Test
    fun 命名参数_类型在场按类型渲染() {
        val sql = ParameterReplacer.replaceNamedParams(
            sql = "select * from t where amount = :amount",
            paramValues = mapOf("amount" to "1.0E10"),
            hints = mapOf("amount" to ParamHint(typeText = "BigDecimal")),
        )
        assertEquals("select * from t where amount = 1.0E10", sql)
    }

    @Test
    fun 命名参数_日期按方言渲染() {
        val sql = ParameterReplacer.replaceNamedParams(
            sql = "select * from t where created >= :created",
            paramValues = mapOf("created" to "2024-01-01"),
            hints = mapOf("created" to ParamHint(quote = true, typeText = "DATE")),
            dialect = "Oracle",
        )
        assertEquals("select * from t where created >= DATE '2024-01-01'", sql)
    }

    @Test
    fun 命名参数_类型判不了回退quote规则() {
        val sql = ParameterReplacer.replaceNamedParams(
            sql = "select * from t where name = :name",
            paramValues = mapOf("name" to "zhang"),
            hints = mapOf("name" to ParamHint(quote = true, typeText = "MapKey")),
        )
        assertEquals("select * from t where name = 'zhang'", sql)
    }

    // ------------------------------------------------------------------
    // classify 在接线层的守门位（类型不在场直接短路）
    // ------------------------------------------------------------------

    @Test
    fun hint缺失时零行为变更() {
        // hints 全空 + 有值 → 启发式（数值裸、字符串引号）——升级前读数
        val sql = ParameterReplacer.buildExecutableSql(
            sql = "update t set n = ? where s = ?",
            sources = listOf("n", "s"),
            paramValues = mapOf("n" to "3.14", "s" to "pi"),
        )
        assertEquals("update t set n = 3.14 where s = 'pi'", sql)
    }

    @Test
    fun dialect缺省为通用档() {
        // 不传 dialect：TEMPORAL 走 ANSI（R3 六引擎实测通用档），与显式 null 等价
        val sql = ParameterReplacer.buildExecutableSql(
            sql = "select * from t where d = ?",
            sources = listOf("d"),
            paramValues = mapOf("d" to "2024-01-01"),
            hints = listOf(ParamHint(quote = true, typeText = "LocalDate")),
        )
        assertEquals("select * from t where d = DATE '2024-01-01'", sql)
    }
}
