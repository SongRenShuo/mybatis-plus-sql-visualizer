package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * foreach 节点测试（T-M24-01）：复刻 MyBatis ForEachSqlNode——
 * `__frch_` 重写（item/index 独立名、嵌套属性、不误伤 itemName）、Map 迭代 index=键、
 * 空集合无输出、null 报错/可空、separator 惰性插入（首项无内容顺延）、嵌套遮蔽。
 */
class ForeachSqlNodeTest {

    private fun foreach(
        collection: String,
        item: String? = "x",
        index: String? = null,
        content: SqlNode = StaticTextSqlNode("#{x}"),
        separator: String? = ",",
        open: String? = "(",
        close: String? = ")",
        nullable: Boolean = false,
    ): ForeachSqlNode = ForeachSqlNode(
        contents = MixedSqlNode(listOf(content)),
        collectionExpression = collection,
        separator = separator,
        open = open,
        close = close,
        item = item,
        index = index,
        nullable = nullable,
    )

    private fun render(node: SqlNode, bindings: Map<String, Any?>): RenderedXmlSql =
        XmlSqlRenderer().render(node, bindings)

    @Test
    fun `基本展开_item重写为frch名`() {
        val result = render(foreach("list"), mapOf("list" to listOf("a", "b")))
        assertEquals("( ? , ? )", result.sql)
        assertEquals(listOf("__frch_x_0", "__frch_x_1"), result.mappings.map { it.property })
    }

    @Test
    fun `嵌套属性重写`() {
        val result = render(foreach("list", content = StaticTextSqlNode("#{x.id}")), mapOf("list" to listOf("a")))
        assertEquals("( ? )", result.sql)
        assertEquals(listOf("__frch_x_0.id"), result.mappings.map { it.property })
    }

    @Test
    fun `itemName不重写`() {
        val result = render(foreach("list", content = StaticTextSqlNode("#{xname}")), mapOf("list" to listOf("a")))
        assertEquals(listOf("xname"), result.mappings.map { it.property })
    }

    @Test
    fun `index绑定为序号`() {
        val result = render(
            foreach("list", content = StaticTextSqlNode("#{i}:#{x}"), index = "i"),
            mapOf("list" to listOf("a", "b")),
        )
        assertEquals("( ?:? , ?:? )", result.sql)
        assertEquals(
            listOf("__frch_i_0", "__frch_x_0", "__frch_i_1", "__frch_x_1"),
            result.mappings.map { it.property },
        )
    }

    @Test
    fun `Map迭代_index为键_item为值`() {
        val result = render(
            foreach("m", content = StaticTextSqlNode("#{i}:#{x}"), index = "i"),
            mapOf("m" to mapOf("k1" to 1, "k2" to 2)),
        )
        assertEquals("( ?:? , ?:? )", result.sql)
        assertEquals(
            listOf("__frch_i_0", "__frch_x_0", "__frch_i_1", "__frch_x_1"),
            result.mappings.map { it.property },
        )
    }

    @Test
    fun `空集合整段不输出_open与close都不出现`() {
        val result = render(foreach("list"), mapOf("list" to emptyList<Any>()))
        assertEquals("", result.sql)
    }

    @Test
    fun `null集合默认报错记录`() {
        val result = render(foreach("list"), mapOf("list" to null))
        assertEquals("", result.sql)
        // T-MbgExamplePreview R6：文案已中文化（原 MyBatis 英文原文透传到中文 UI）
        assertTrue(result.errors.any { it.contains("求值为 null") })
    }

    @Test
    fun `nullable为true时null集合静默跳过`() {
        val result = render(foreach("list", nullable = true), mapOf("list" to null))
        assertEquals("", result.sql)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `separator仅在项之间_首尾无`() {
        val result = render(foreach("list"), mapOf("list" to listOf(1, 2, 3)))
        assertEquals("( ? , ? , ? )", result.sql)
    }

    @Test
    fun `首项无内容时separator顺延到有内容的迭代`() {
        val node = foreach("list", content = IfSqlNode(StaticTextSqlNode("#{x}"), "x != null"))
        // 第一项 null → if 关闭（无输出）；第二项 "v" → 输出；first 顺延 → 无 separator
        val result = render(node, mapOf("list" to listOf(null, "v")))
        assertEquals("( ? )", result.sql)
        assertEquals(listOf("__frch_x_1"), result.mappings.map { it.property })
    }

    @Test
    fun `open与close自定义`() {
        val node = foreach("list", open = "IN (", close = ")", separator = ", ")
        val result = render(node, mapOf("list" to listOf(1, 2)))
        assertEquals("IN ( ? ,  ? )", result.sql)
    }

    @Test
    fun `迭代内容换行缩进被trim_预览紧凑`() {
        // T-M25.5（用户实测）：foreach 体 "\n  #{x}\n" 原样输出使 IN 每项换行——trim 后单行紧凑
        val node = ForeachSqlNode(
            contents = MixedSqlNode(listOf(StaticTextSqlNode("\n  #{x}\n"))),
            collectionExpression = "list",
            separator = ",",
            open = "(",
            close = ")",
            item = "x",
            index = null,
            nullable = false,
        )
        val result = render(node, mapOf("list" to listOf("a", "b")))
        assertEquals("( ? , ? )", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    @Test
    fun `嵌套foreach同名item内层遮蔽外层`() {
        val outer = ForeachSqlNode(
            contents = MixedSqlNode(
                listOf(
                    StaticTextSqlNode("#{x}->"),
                    foreach("list", content = StaticTextSqlNode("#{x}")),
                ),
            ),
            collectionExpression = "list",
            separator = null,
            open = null,
            close = null,
            item = "x",
            index = null,
            nullable = false,
        )
        val result = render(outer, mapOf("list" to listOf("a")))
        assertEquals("?-> ( ? )", result.sql)
        // 外层 __frch_x_0；内层独立编号 __frch_x_1（遮蔽）
        assertEquals(listOf("__frch_x_0", "__frch_x_1"), result.mappings.map { it.property })
    }
}
