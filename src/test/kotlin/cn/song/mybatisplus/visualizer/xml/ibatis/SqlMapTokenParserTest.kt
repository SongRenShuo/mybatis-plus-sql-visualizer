package cn.song.mybatisplus.visualizer.xml.ibatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `#` / `$` 占位符解析单测（T-Ibatis2Support 券 B）——对齐 iBatis 2.3.4.726
 * `InlineParameterMapParser` / `SimpleDynamicSql` 的逐条实测语义。
 */
class SqlMapTokenParserTest {

    private val parser = SqlMapTokenParser()

    @Test
    fun `两段式第二段是 jdbcType 不是 nullValue`() {
        // 引擎简单语法按 token 数分档：3 = name:jdbcType
        val outcome = parser.parseInlineParameterMap("A = #name:VARCHAR#")
        assertEquals("A = ?", outcome.text)
        assertEquals(1, outcome.mappings.size)
        assertEquals("name", outcome.mappings[0].propertyName)
        assertEquals("VARCHAR", outcome.mappings[0].jdbcTypeName)
        assertNull("`#name:nullValue#` 的第二段永远是 jdbcType", outcome.mappings[0].nullValue)
    }

    @Test
    fun `三段式与 nullValue 内可含冒号`() {
        val three = parser.parseInlineParameterMap("#p:VARCHAR:N/A#")
        assertEquals("N/A", three.mappings[0].nullValue)

        // token >= 5：多余的 token 逐个拼回 nullValue
        val moreColons = parser.parseInlineParameterMap("#p:VARCHAR:a:b/c:d#")
        assertEquals("a:b/c:d", moreColons.mappings[0].nullValue)
        assertEquals("VARCHAR", moreColons.mappings[0].jdbcTypeName)
    }

    @Test
    fun `冒号分段数既不是 3 也不到 5 时报格式错并原文保留`() {
        // token 数 4（`p` `:` `VARCHAR` `:`）既不是 3 也不 >= 5 → 引擎抛 SqlMapException
        val outcome = parser.parseInlineParameterMap("A = #p:VARCHAR:#")
        assertEquals("A = #p:VARCHAR:#", outcome.text)
        assertTrue(outcome.mappings.isEmpty())
        assertTrue(outcome.warnings.any { it.contains("格式错误") })
    }

    @Test
    fun `四个以上分段按三段式解析且多余冒号并入 nullValue`() {
        // 7 个 token（>=5）是**合法**三段式：nullValue = "extra:four"
        val outcome = parser.parseInlineParameterMap("A = #p:VARCHAR:extra:four#")
        assertEquals("A = ?", outcome.text)
        assertEquals("p", outcome.mappings.single().propertyName)
        assertEquals("VARCHAR", outcome.mappings.single().jdbcTypeName)
        assertEquals("extra:four", outcome.mappings.single().nullValue)
    }

    @Test
    fun `高级语法解析成对字段且拒绝未知字段`() {
        val outcome = parser.parseInlineParameterMap("#p,javaType=string,jdbcType=VARCHAR,mode=IN,nullValue=N/A,numericScale=2#")
        val mapping = outcome.mappings.single()
        assertEquals("p", mapping.propertyName)
        assertEquals("string", mapping.javaTypeName)
        assertEquals("VARCHAR", mapping.jdbcTypeName)
        assertEquals("IN", mapping.mode)
        assertEquals("N/A", mapping.nullValue)
        assertEquals(2, mapping.numericScale)

        val unknown = parser.parseInlineParameterMap("#p,bogus=1#")
        assertTrue(unknown.mappings.isEmpty())
        assertTrue(unknown.warnings.any { it.contains("未识别的内联参数字段") })

        val unpaired = parser.parseInlineParameterMap("#p,jdbcType#")
        assertTrue(unpaired.mappings.isEmpty())
        assertTrue(unpaired.warnings.any { it.contains("不成对") })

        val badScale = parser.parseInlineParameterMap("#p,numericScale=-1#")
        assertTrue(badScale.warnings.any { it.contains("numericScale") })
    }

    @Test
    fun `两个井号是字面量井号`() {
        val outcome = parser.parseInlineParameterMap("SELECT A ## B FROM T WHERE C = #c#")
        assertEquals("SELECT A # B FROM T WHERE C = ?", outcome.text)
        assertEquals(listOf("c"), outcome.mappings.map { it.propertyName })
        assertTrue(outcome.warnings.isEmpty())
    }

    @Test
    fun `未闭合井号原文保留并告警`() {
        val outcome = parser.parseInlineParameterMap("SELECT A WHERE B = #b")
        assertEquals("SELECT A WHERE B = #b", outcome.text)
        assertTrue(outcome.warnings.any { it.contains("未闭合") })
    }

    @Test
    fun `参数名按出现顺序记录且支持下标形态`() {
        val outcome = parser.parseInlineParameterMap("A = #a# AND B = #b# AND C = #ids[0]#")
        assertEquals("A = ? AND B = ? AND C = ?", outcome.text)
        assertEquals(listOf("a", "b", "ids[0]"), outcome.mappings.map { it.propertyName })
    }

    @Test
    fun `美元直替取标量参数或属性并在 null 时静默丢弃`() {
        val map = mapOf("tableName" to "T_ORG", "nilValue" to null)
        val kept = parser.processDollarSubstitution("FROM \$tableName\$ WHERE 1=1", map)
        assertEquals("FROM T_ORG WHERE 1=1", kept.text)
        assertEquals(listOf("T_ORG"), kept.renderedValues)
        assertEquals(1, kept.siteCount)

        val dropped = parser.processDollarSubstitution("FROM \$nilValue\$ WHERE 1=1", map)
        assertEquals("FROM  WHERE 1=1", dropped.text)
        assertEquals(1, dropped.siteCount)
        assertTrue(dropped.renderedValues.isEmpty())

        val scalar = parser.processDollarSubstitution("X = \$anything\$", "hello")
        assertEquals("X = hello", scalar.text)
    }

    @Test
    fun `两个美元符是字面量美元符`() {
        val outcome = parser.processDollarSubstitution("SELECT '\$\$' AS d FROM T", null)
        assertEquals("SELECT '\$' AS d FROM T", outcome.text)
        assertEquals(0, outcome.siteCount)
    }

    @Test
    fun `未闭合美元符原文保留并告警`() {
        val outcome = parser.processDollarSubstitution("SELECT \$unclosed FROM T", mapOf("unclosed" to "x"))
        assertEquals("SELECT \$unclosed FROM T", outcome.text)
        assertTrue(outcome.warnings.any { it.contains("未闭合") })
    }

    @Test
    fun `标量参数下任意属性名都取整个对象`() {
        // 引擎 SimpleDynamicSql：hasTypeHandler(parameterObject.getClass()) 命中则取整个对象
        val outcome = parser.processDollarSubstitution("V = \$whatever\$", 42)
        assertEquals("V = 42", outcome.text)
        assertNotNull(outcome.renderedValues.singleOrNull())
    }
}
