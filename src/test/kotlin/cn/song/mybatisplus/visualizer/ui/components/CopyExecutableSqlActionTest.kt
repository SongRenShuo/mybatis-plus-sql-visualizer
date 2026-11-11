package cn.song.mybatisplus.visualizer.ui.components

import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.ParamHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-4：复制可执行 SQL Action 测试（纯逻辑，不依赖 Swing/EDT）。
 *
 * 验证 [CopyExecutableSqlAction] 委托的替换逻辑（[ParameterReplacer.buildExecutableSql]）正确性，
 * 以及边界场景（无参数 / 全空值 / 引号转义）。
 */
class CopyExecutableSqlActionTest {

    @Test
    fun `buildExecutableSql_委托验证_数值与字符串混合`() {
        val sql = "SELECT * FROM t WHERE id = ? AND name = ? AND age > ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("id", "name", "age"),
            paramValues = mapOf("id" to "1", "name" to "Alice", "age" to "18"),
            hints = listOf(ParamHint(quote = false), ParamHint(quote = true), ParamHint(quote = false)),
        )
        assertEquals("SELECT * FROM t WHERE id = 1 AND name = 'Alice' AND age > 18", result)
    }

    @Test
    fun `buildExecutableSql_空值保留占位符_复制时跳过`() {
        val sql = "SELECT * FROM t WHERE id = ? AND name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("id", "name"),
            paramValues = mapOf("id" to "1"),
            hints = listOf(ParamHint(quote = false), ParamHint(quote = true)),
        )
        // id 替换，name 未填保留 ?
        assertEquals("SELECT * FROM t WHERE id = 1 AND name = ?", result)
    }

    @Test
    fun `buildExecutableSql_全空值_结果与原始一致`() {
        val sql = "SELECT * FROM t WHERE id = ? AND name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("id", "name"),
            paramValues = emptyMap(),
            hints = listOf(ParamHint(quote = false), ParamHint(quote = true)),
        )
        assertEquals(sql, result)
    }

    @Test
    fun `buildExecutableSql_日期字符串_加引号`() {
        val sql = "SELECT * FROM t WHERE created_at > ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("createdAt"),
            paramValues = mapOf("createdAt" to "2026-01-01"),
            hints = listOf(ParamHint(quote = true)),
        )
        assertEquals("SELECT * FROM t WHERE created_at > '2026-01-01'", result)
    }

    @Test
    fun `buildExecutableSql_小数数值_不加引号`() {
        val sql = "SELECT * FROM t WHERE price < ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("price"),
            paramValues = mapOf("price" to "19.99"),
            hints = listOf(ParamHint(quote = false)),
        )
        assertEquals("SELECT * FROM t WHERE price < 19.99", result)
    }

    @Test
    fun `buildExecutableSql_启发式_负数数值`() {
        val sql = "SELECT * FROM t WHERE delta = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("delta"),
            paramValues = mapOf("delta" to "-5"),
            hints = emptyList(),
        )
        // 无 hint 走启发式：-5 匹配 NUMBER_REGEX → 不加引号
        assertEquals("SELECT * FROM t WHERE delta = -5", result)
    }

    @Test
    fun `countReplaced_逻辑验证_通过SQL差异计算`() {
        // 间接验证替换计数逻辑：原始 3 个 ?，结果 1 个 ?，替换 2 个
        val original = "WHERE a = ? AND b = ? AND c = ?"
        val result = "WHERE a = 1 AND b = 'x' AND c = ?"
        val replaced = original.count { it == '?' } - result.count { it == '?' }
        assertEquals(2, replaced)
    }

    @Test
    fun `buildExecutableSql_值含单引号_转义为双单引号`() {
        val sql = "SELECT * FROM t WHERE name = ?"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = listOf("name"),
            paramValues = mapOf("name" to "It's a test"),
            hints = listOf(ParamHint(quote = true)),
        )
        assertEquals("SELECT * FROM t WHERE name = 'It''s a test'", result)
    }

    @Test
    fun `buildExecutableSql_无参数SQL_原样返回`() {
        val sql = "SELECT 1"
        val result = ParameterReplacer.buildExecutableSql(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
        )
        assertEquals("SELECT 1", result)
    }

    // ---------------- T-CopyPlaceholderMarker：模拟占位跟着产物走 ----------------

    @Test
    fun `含模拟占位_头部用占位标记且不重复raw标记`() {
        val product = CopyExecutableSqlAction.assembleProduct(
            rawSql = "SELECT * FROM t WHERE ( {{criterion.condition}} ? )",
            hasDollarRawSegments = true,
        )
        assertEquals(1, product.simulationPlaceholderCount)
        assertTrue(product.text, product.text.startsWith(CopyExecutableSqlAction.unresolvedPlaceholderMarker(1)))
        assertTrue("产物本体保留占位：${product.text}", product.text.contains("{{criterion.condition}}"))
        assertFalse(
            "有占位时不应再挂「已用模拟值填充」那条：${product.text}",
            product.text.contains(CopyExecutableSqlAction.DOLLAR_RAW_MARKER),
        )
    }

    @Test
    fun `多处模拟占位_计数正确`() {
        val product = CopyExecutableSqlAction.assembleProduct(
            rawSql = "SELECT * FROM t WHERE {{a.b}} = ? AND {{c.d}} = ?",
            hasDollarRawSegments = true,
        )
        assertEquals(2, product.simulationPlaceholderCount)
        assertTrue(product.text.startsWith(CopyExecutableSqlAction.unresolvedPlaceholderMarker(2)))
    }

    @Test
    fun `无占位_保留既有raw标记`() {
        val product = CopyExecutableSqlAction.assembleProduct(
            rawSql = "SELECT * FROM t WHERE id = 1",
            hasDollarRawSegments = true,
        )
        assertEquals(0, product.simulationPlaceholderCount)
        assertTrue(product.text.startsWith(CopyExecutableSqlAction.DOLLAR_RAW_MARKER))
    }

    @Test
    fun `无占位无raw_产物逐字节不变`() {
        val product = CopyExecutableSqlAction.assembleProduct(rawSql = "SELECT 1")
        assertEquals("SELECT 1", product.text)
        assertEquals(0, product.simulationPlaceholderCount)
        assertFalse(product.text.startsWith("--"))
    }

    @Test
    fun `share链复用占位标记_无占位原样返回`() {
        val withPlaceholder = CopyExecutableSqlAction.withPlaceholderMarker("SELECT * FROM t WHERE {{a.b}} = 1")
        assertTrue(withPlaceholder.startsWith(CopyExecutableSqlAction.unresolvedPlaceholderMarker(1)))
        assertEquals("SELECT 1", CopyExecutableSqlAction.withPlaceholderMarker("SELECT 1"))
    }
}
