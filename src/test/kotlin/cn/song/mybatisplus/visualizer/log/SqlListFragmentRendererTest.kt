package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors
import cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.tree.IElementType
import com.intellij.ui.SimpleTextAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.lang.reflect.Proxy

/**
 * 列表条目 SQL 着色片段管线单元测试（T-LogListFreeze-04，承接 SqlListHtmlRendererTest 语义：
 * 关键字/字符串/数字/注释着色与空串边界——载体由 HTML span 改为 SqlTextFragment）。
 */
class SqlListFragmentRendererTest {

    private val attrOf: (IElementType) -> SimpleTextAttributes? = {
        when (it) {
            SqlTokenTypes.KEYWORD -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0xC678DD))
            SqlTokenTypes.STRING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0x98C379))
            SqlTokenTypes.NUMBER -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0xD19A66))
            SqlTokenTypes.COMMENT -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0x7F848E))
            else -> null
        }
    }

    private fun fragmentOf(sql: String, text: String) =
        SqlListFragmentRenderer.renderFragments(sql, attrOf).firstOrNull { it.text.contains(text) }

    @Test
    fun test关键字着色() {
        val f = fragmentOf("SELECT 1", "SELECT")
        assertEquals("SELECT 关键字应着主题色", Color(0xC678DD), f?.attributes?.fgColor)
        assertTrue("数字 token 应保留", SqlListFragmentRenderer.renderFragments("SELECT 1", attrOf).any { it.text == "1" })
    }

    @Test
    fun test字符串与数字着色() {
        val s = fragmentOf("WHERE name = 'abc' AND id = 42", "'abc'")
        assertEquals("字符串应着绿色", Color(0x98C379), s?.attributes?.fgColor)
        val n = fragmentOf("WHERE name = 'abc' AND id = 42", "42")
        assertEquals("数字应着橙色", Color(0xD19A66), n?.attributes?.fgColor)
    }

    @Test
    fun test特殊字符原文保留_无HTML转义面() {
        // 片段通道不做 HTML 转义——文本原样保留即安全（无 BasicHTML 解析面）
        val f = fragmentOf("SELECT 'a<b&c>'", "a<b&c>")
        assertEquals("字符串内容应原文保留", "'a<b&c>'", f?.text)
    }

    @Test
    fun test无配色类型走REGULAR属性() {
        val f = fragmentOf("WHERE name = 'abc'", "name")
        assertSame("IDENTIFIER 未配色应透传 REGULAR_ATTRIBUTES（走组件前景色）", SimpleTextAttributes.REGULAR_ATTRIBUTES, f?.attributes)
    }

    @Test
    fun test空串渲染为空() {
        assertTrue(SqlListFragmentRenderer.renderFragments("", attrOf).isEmpty())
    }

    @Test
    fun test含注释的SQL() {
        val f = fragmentOf("SELECT 1 -- note", "-- note")
        assertEquals("注释应着灰色", Color(0x7F848E), f?.attributes?.fgColor)
    }

    /** 模拟 scheme 的显式属性优先、依次回落 key 的 fallback 链；不依赖当前 IDE 主题。 */
    private fun scheme(overrides: Map<TextAttributesKey, Color>): EditorColorsScheme =
        Proxy.newProxyInstance(
            EditorColorsScheme::class.java.classLoader,
            arrayOf(EditorColorsScheme::class.java),
        ) { _, method, args ->
            if (method.name == "getAttributes") {
                var key = args[0] as TextAttributesKey?
                while (key != null && !overrides.containsKey(key)) key = key.fallbackAttributeKey
                key?.let(overrides::get)?.let { color -> TextAttributes().apply { foregroundColor = color } }
            } else null
        } as EditorColorsScheme

    @Test
    fun testSemanticFragmentsKeepTextAndTokenTypesWithoutThemeState() {
        val sql = "SELECT 'a<b&c>'\nFROM users WHERE id = 42"
        val fragments = SqlListFragmentRenderer.semanticFragments(sql)
        assertEquals(sql, fragments.joinToString("") { it.text })
        assertEquals(SqlTokenTypes.KEYWORD, fragments.first().tokenType)
        assertEquals(SqlTokenTypes.STRING, fragments.first { it.text == "'a<b&c>'" }.tokenType)
        assertEquals(SqlTokenTypes.NUMBER, fragments.first { it.text == "42" }.tokenType)
        assertEquals("semantic fragment must only store text and token type", setOf("text", "tokenType"),
            SqlSemanticFragment::class.java.declaredFields.map { it.name }.toSet())
        assertTrue(SqlListFragmentRenderer.semanticFragments("").isEmpty())
    }

    @Test
    fun testSemanticLineSplitAndCacheDoNotStoreThemeColors() {
        val sql = "SELECT a\nFROM t\n"
        val lines = SqlListFragmentRenderer.semanticLineFragments(sql, SqlListDisplayMode.SINGLE_LINE)
        assertEquals(listOf("SELECT a", "FROM t", ""), lines.map { line -> line.joinToString("") { it.text } })
        assertEquals(SqlTokenTypes.KEYWORD, lines[1].first().tokenType)
        val entry = LogSqlEntry(sql, sql, emptyList())
        entry.cachedListLineFragments = lines
        assertSame(lines, entry.cachedListLineFragments)
        assertTrue(lines.flatten().none { it.text.contains('\n') })
    }

    @Test
    fun testSemanticFragmentsProjectUsingCurrentSchemeAndCanonicalOverrideFirst() {
        val semantic = SqlListFragmentRenderer.semanticFragments("SELECT 1")
        val canonical = SqlHighlightColors.KEYWORD
        val legacy = requireNotNull(canonical.fallbackAttributeKey)
        val first = scheme(mapOf(legacy to Color.BLUE))
        val second = scheme(mapOf(legacy to Color.GREEN, canonical to Color.RED))
        assertEquals(Color.BLUE, SqlListFragmentRenderer.projectFragments(semantic, first).first().attributes.fgColor)
        assertEquals(Color.RED, SqlListFragmentRenderer.projectFragments(semantic, second).first().attributes.fgColor)
        val lines = SqlListFragmentRenderer.semanticLineFragments("SELECT 1", SqlListDisplayMode.SINGLE_LINE)
        assertEquals(Color.BLUE, SqlListFragmentRenderer.projectLineFragments(lines, first)[0][0].attributes.fgColor)
        assertEquals(Color.RED, SqlListFragmentRenderer.projectLineFragments(lines, second)[0][0].attributes.fgColor)
        assertEquals(Color.BLUE, SqlListFragmentRenderer.projectFragments(semantic, first).first().attributes.fgColor)
    }

    // ---------- T-ListMode：显示模式变换与多行拆分（承接 SqlListHtmlRendererTest 三模式映射语义） ----------

    @Test
    fun test模式变换映射_单行原文() {
        val sql = "SELECT a FROM t WHERE b = 1"
        assertEquals("SINGLE_LINE 应原文直出", sql, prepareSqlForList(sql, SqlListDisplayMode.SINGLE_LINE))
    }

    @Test
    fun test模式变换映射_关键字换行与格式化() {
        val sql = "SELECT a, b FROM t WHERE x = 1 AND y = 2"
        val kw = prepareSqlForList(sql, SqlListDisplayMode.KEYWORD_WRAP)
        val fm = prepareSqlForList(sql, SqlListDisplayMode.FORMATTED)
        assertTrue("关键字换行应含换行", kw.contains('\n'))
        assertTrue("格式化应含换行", fm.contains('\n'))
        assertTrue("AND 同行（关键字换行）与 AND 独立行（格式化）应产出不同文本", kw != fm)
    }

    @Test
    fun test模式变换映射_空串() {
        assertEquals("", prepareSqlForList("", SqlListDisplayMode.SINGLE_LINE))
        assertEquals("", prepareSqlForList("", SqlListDisplayMode.KEYWORD_WRAP))
        assertEquals("", prepareSqlForList("", SqlListDisplayMode.FORMATTED))
    }

    @Test
    fun test多行片段按行拆分_属性继承() {
        val lines = SqlListFragmentRenderer.renderLineFragments(
            "SELECT a FROM t WHERE b = 1",
            SqlListDisplayMode.KEYWORD_WRAP,
            attrOf,
        )
        assertTrue("关键字换行应拆出多行（实际 ${lines.size} 行）", lines.size >= 3)
        assertTrue("首行含 SELECT 片段", lines[0].any { it.text.contains("SELECT") })
        assertTrue("FROM 独立成行", lines.any { line -> line.firstOrNull()?.text == "FROM" })
        assertTrue("WHERE 独立成行", lines.any { line -> line.firstOrNull()?.text == "WHERE" })
        val fromLine = lines.first { line -> line.firstOrNull()?.text == "FROM" }
        assertEquals(
            "FROM 关键字应着主题色（属性随行拆分继承）",
            Color(0xC678DD),
            fromLine.first().attributes.fgColor,
        )
    }

    @Test
    fun test多行片段按行拆分_空串单空行() {
        val lines = SqlListFragmentRenderer.renderLineFragments("", SqlListDisplayMode.FORMATTED, attrOf)
        assertEquals("空 SQL 变换后仍为空——拆分恒单空行", 1, lines.size)
        assertTrue(lines[0].isEmpty())
    }

    // ---------- T-LogListAnchor 券01：关键字换行取源为完整 SQL ----------

    /** 用户报障里的真实形态：24 列 SELECT，摘要（160 字）覆盖不到 FROM。 */
    private val longColumnSql = "SELECT code, station_name AS stationName, device_id AS deviceId, " +
        "device_code AS deviceCode, user_id AS userId, user_phone AS userPhone, " +
        "device_port_number AS devicePortNumber, create_time AS createTime, start_time AS startTime, " +
        "dic_charge_type AS dicChargeType, dic_status AS dicStatus, actually_amount AS actuallyAmount, " +
        "discount_amount AS discountAmount FROM biz_car_order_record WHERE id = 42"

    @Test
    fun test关键字换行取源为完整SQL_长列清单仍三行() {
        val entry = LogSqlEntry(rawSql = longColumnSql, restoredSql = longColumnSql, params = emptyList())
        assertFalse("夹具前提：摘要覆盖不到 FROM（否则本用例无区分力）", entry.summary.contains("FROM"))

        val source = displaySqlForList(entry, SqlListDisplayMode.KEYWORD_WRAP, masked = false)
        assertTrue("取源须是完整 SQL（含 FROM 之后的表名）", source.contains("FROM biz_car_order_record"))

        val lines = SqlListFragmentRenderer.semanticLineFragments(source, SqlListDisplayMode.KEYWORD_WRAP)
        assertEquals("长列清单也必须产出三行（SELECT/FROM/WHERE）", 3, lines.size)
        assertTrue("首行 SELECT 开头", lines[0].firstOrNull()?.text == "SELECT")
        assertTrue("次行 FROM 开头", lines[1].firstOrNull()?.text == "FROM")
        assertTrue("第三行 WHERE 开头", lines[2].firstOrNull()?.text == "WHERE")
    }

    @Test
    fun test单行模式仍取摘要_不受券01影响() {
        val entry = LogSqlEntry(rawSql = longColumnSql, restoredSql = longColumnSql, params = emptyList())
        assertEquals("单行模式取摘要（一行封顶）", entry.summary, displaySqlForList(entry, SqlListDisplayMode.SINGLE_LINE, false))
        assertTrue(
            "格式化模式与关键字换行同源：完整 SQL",
            displaySqlForList(entry, SqlListDisplayMode.FORMATTED, false).contains("biz_car_order_record"),
        )
    }

    @Test
    fun test关键字换行取源预算_超长按空白边界截断() {
        val inList = (1..2000).joinToString(", ") { "'v$it'" }
        val sql = "SELECT id FROM t WHERE code IN ($inList)"
        assertTrue("夹具前提：SQL 远超预算", sql.length > KEYWORD_WRAP_SOURCE_BUDGET)

        val clipped = clipKeywordWrapSource(sql)
        assertTrue("截断后长度受预算约束（含省略号）: ${clipped.length}", clipped.length <= KEYWORD_WRAP_SOURCE_BUDGET + 1)
        assertTrue("截断须带省略号", clipped.endsWith("…"))
        assertFalse(
            "不得劈开标识符/字面量（末字符不能是字母数字）: ${clipped.takeLast(3)}",
            clipped[clipped.length - 2].isLetterOrDigit(),
        )
        assertTrue("短 SQL 原样返回", clipKeywordWrapSource("SELECT 1") == "SELECT 1")
        assertTrue(
            "预算内仍能读到 WHERE 子句",
            clipKeywordWrapSource("SELECT a FROM t WHERE id = 1") == "SELECT a FROM t WHERE id = 1",
        )
    }
}
