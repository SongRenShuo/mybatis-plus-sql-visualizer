package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * trim 族节点测试（T-M24-01）：复刻 MyBatis TrimSqlNode 算法——
 * 整体 trim、前缀/后缀 override 大小写不敏感只删一次、prefix 恒带空格、空内容不输出；
 * where 的换行/回车/制表符变体；set 的逗号处理。
 */
class TrimSqlNodeTest {

    private fun render(node: SqlNode): RenderedXmlSql = XmlSqlRenderer().render(node, emptyMap())

    private fun text(value: String): SqlNode = MixedSqlNode(listOf(StaticTextSqlNode(value)))

    @Test
    fun `trim无prefix时prefixOverrides不剥_券Y13`() {
        // T-HiddenBugPerfScan 券 Y13：对齐 MyBatis——无 prefix 属性的合法 <trim>
        // 运行时原样输出，本引擎曾剥掉 AND 前缀致预览与运行时产物分叉
        val node = TrimSqlNode(text("AND x = 1"), prefix = null, prefixesToOverride = listOf("AND "), suffix = null, suffixesToOverride = null)
        val out = render(node).sql
        org.junit.Assert.assertEquals("AND x = 1", out.trim())
    }

    // ------------------------------------------------------------------
    // where
    // ------------------------------------------------------------------

    @Test
    fun `where_去掉AND前缀并插WHERE`() {
        assertEquals("WHERE  id = ?", render(WhereSqlNode(text(" AND id = #{id}"))).sql)
    }

    @Test
    fun `where_OR前缀`() {
        assertEquals("WHERE  x = 1", render(WhereSqlNode(text("OR x = 1"))).sql)
    }

    @Test
    fun `where_小写前缀也匹配_大小写不敏感`() {
        assertEquals("WHERE  x = 1", render(WhereSqlNode(text("and x = 1"))).sql)
    }

    @Test
    fun `where_换行变体`() {
        assertEquals("WHERE \nx = 1", render(WhereSqlNode(text("AND\nx = 1"))).sql)
        assertEquals("WHERE \tx = 1", render(WhereSqlNode(text("OR\tx = 1"))).sql)
    }

    @Test
    fun `where_只删第一个匹配`() {
        assertEquals("WHERE  AND x = 1", render(WhereSqlNode(text("AND AND x = 1"))).sql)
    }

    @Test
    fun `where_内容为空白时不输出`() {
        assertEquals("", render(WhereSqlNode(text("  \n  "))).sql)
    }

    @Test
    fun `where_整体trim后再匹配_前后空白被去掉`() {
        assertEquals("WHERE  x = 1", render(WhereSqlNode(text("  AND x = 1  "))).sql)
    }

    // ------------------------------------------------------------------
    // set
    // ------------------------------------------------------------------

    @Test
    fun `set_去掉尾逗号`() {
        assertEquals("SET a = ?, b = ?", render(SetSqlNode(text("a = #{a}, b = #{b},"))).sql)
    }

    @Test
    fun `set_无尾逗号也正常`() {
        assertEquals("SET a = ?", render(SetSqlNode(text("a = #{a}"))).sql)
    }

    @Test
    fun `set_内容为空时不输出`() {
        assertEquals("", render(SetSqlNode(text("   "))).sql)
    }

    @Test
    fun `set_前导逗号被去掉`() {
        assertEquals("SET a = ?", render(SetSqlNode(text(",a = #{a}"))).sql)
    }

    // ------------------------------------------------------------------
    // 自定义 trim
    // ------------------------------------------------------------------

    @Test
    fun `trim_前缀后缀与overrides`() {
        val node = TrimSqlNode(text("B x,"), prefix = "PREFIX", prefixesToOverride = listOf("A ", "B "), suffix = "END", suffixesToOverride = listOf(","))
        assertEquals("PREFIX  x END", render(node).sql)
    }

    @Test
    fun `trim_prefix与内容之间恒有一个空格`() {
        val node = TrimSqlNode(text("x"), prefix = "PREFIX", prefixesToOverride = null, suffix = null, suffixesToOverride = null)
        assertEquals("PREFIX x", render(node).sql)
    }

    @Test
    fun `trim_内容为空时prefix与suffix都不加`() {
        val node = TrimSqlNode(text("  "), prefix = "PREFIX", prefixesToOverride = null, suffix = "END", suffixesToOverride = null)
        assertEquals("", render(node).sql)
    }

    // ------------------------------------------------------------------
    // 与 if 组合（条件开关覆盖）
    // ------------------------------------------------------------------

    @Test
    fun `where_内层if关闭后内容为空`() {
        val inner = IfSqlNode(StaticTextSqlNode(" AND id = #{id}"), "id != null")
        val node = WhereSqlNode(MixedSqlNode(listOf(inner)))
        assertEquals("", render(node).sql)
        assertEquals(
            "WHERE  id = ?",
            XmlSqlRenderer().render(node, emptyMap(), conditionOverrides = mapOf("id != null" to true)).sql,
        )
    }

}
