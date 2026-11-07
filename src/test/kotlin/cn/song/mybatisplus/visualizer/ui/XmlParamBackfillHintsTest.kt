package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo
import cn.song.mybatisplus.visualizer.xml.ParameterMapping
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XML 预览参数回填提示组装测试（O-81 T-XmlListParamSplit）：
 * 签名集合参数无条件拆分 + 引号按元素类别派生；typeHandler 豁免；
 * 无类型信息/非集合参数走形态通道；foreach item 键不走类型拆分。
 */
class XmlParamBackfillHintsTest {

    private val mapper = """
        <mapper namespace="n">
          <select id="q">
            SELECT * FROM t
            WHERE a IN (#{taskTypeList})
            AND b IN
            (#{rawList})
            <if test="stList != null">
              AND c IN <foreach collection="stList" item="st" open="(" separator="," close=")">#{st}</foreach>
            </if>
          </select>
        </mapper>
    """.trimIndent()

    private fun model(paramTypes: Map<String, ParamTypeInfo>): XmlPreviewModel =
        XmlMapperParser().parse(mapper).let {
            XmlPreviewModel.from(it.statements.single(), it.errors, paramTypes = paramTypes)
        }

    @Test
    fun `签名集合参数无条件拆分且引号按元素类别派生`() {
        val m = model(mapOf("taskTypeList" to ParamTypeInfo(ParamKind.COLLECTION, elementKind = ParamKind.TEXT)))
        val hints = xmlBackfillHints(m, listOf("taskTypeList"), emptySet())
        assertTrue("签名 COLLECTION 无条件拆分（形态未命中也拆）", hints[0].inList)
        assertTrue("TEXT 元素恒引号", hints[0].quote == true)
    }

    @Test
    fun `NUMBER元素裸渲染`() {
        val m = model(mapOf("taskTypeList" to ParamTypeInfo(ParamKind.COLLECTION, elementKind = ParamKind.NUMBER)))
        val hints = xmlBackfillHints(m, listOf("taskTypeList"), emptySet())
        assertTrue(hints[0].inList)
        assertEquals("List<Long> 元素裸渲染（升级前集合恒引号，先红读数实证 '1','2'）", false, hints[0].quote)
    }

    @Test
    fun `无元素类别回落kind引号`() {
        val m = model(mapOf("taskTypeList" to ParamTypeInfo(ParamKind.COLLECTION)))
        val hints = xmlBackfillHints(m, listOf("taskTypeList"), emptySet())
        assertTrue(hints[0].inList)
        assertEquals("裸集合回落类别引号档（宁缺勿错）", true, hints[0].quote)
    }

    @Test
    fun `显式typeHandler不拆走形态`() {
        val m = model(mapOf("taskTypeList" to ParamTypeInfo(ParamKind.COLLECTION, elementKind = ParamKind.TEXT)))
        val mappings = listOf(ParameterMapping.parse("taskTypeList,typeHandler=com.x.JsonListHandler"))
        val hints = xmlBackfillHints(m, listOf("taskTypeList"), emptySet(), mappings)
        assertFalse("自定义序列化形态不按逗号拆分", hints[0].inList)
    }

    @Test
    fun `无类型信息走形态通道`() {
        val m = model(emptyMap())
        val hints = xmlBackfillHints(m, listOf("taskTypeList", "rawList"), setOf(0))
        assertEquals("形态命中 → 拆分", true, hints[0].inList)
        assertEquals("形态未命中 → 不拆（rawList 处 IN 与 ( 间换行，此处测的是索引位不命中）", false, hints[1].inList)
        assertEquals("引号兜底", true, hints[0].quote)
    }

    @Test
    fun `非集合签名参数走形态通道`() {
        val m = model(mapOf("taskTypeList" to ParamTypeInfo(ParamKind.TEXT)))
        assertTrue(xmlBackfillHints(m, listOf("taskTypeList"), setOf(0))[0].inList)
        assertFalse(xmlBackfillHints(m, listOf("taskTypeList"), emptySet())[0].inList)
    }

    @Test
    fun `foreachItem键不触发集合拆分`() {
        // item 键 paramTypeFor 返回元素类别（TEXT），非 COLLECTION → 形态通道
        // （O-78 foreach 集合值输入框已逐项回填，类型拆分再介入会双拆——语义钉住）
        val m = model(mapOf("stList" to ParamTypeInfo(ParamKind.COLLECTION, elementKind = ParamKind.TEXT)))
        val hints = xmlBackfillHints(m, listOf("__frch_st_0"), emptySet())
        assertFalse(hints[0].inList)
    }

    // ------------------------------------------------------------------
    // T-DateLiteralBackfill：日期族 temporal 标记
    // ------------------------------------------------------------------

    @Test
    fun `日期族签名参数带temporal`() {
        val m = model(mapOf("startDate" to ParamTypeInfo(ParamKind.TEMPORAL)))
        val hints = xmlBackfillHints(m, listOf("startDate"), emptySet())
        assertTrue("TEMPORAL 参数应带 temporal", hints[0].temporal)
        assertTrue("TEMPORAL 仍走引号兜底", hints[0].quote == true)
    }

    @Test
    fun `日期族集合元素带temporal`() {
        val m = model(mapOf("taskTypeList" to ParamTypeInfo(ParamKind.COLLECTION, elementKind = ParamKind.TEMPORAL)))
        val hints = xmlBackfillHints(m, listOf("taskTypeList"), emptySet())
        assertTrue(hints[0].inList)
        assertTrue("List<LocalDate> 元素应带 temporal", hints[0].temporal)
    }

    @Test
    fun `非日期族参数不带temporal`() {
        val m = model(mapOf("taskTypeList" to ParamTypeInfo(ParamKind.TEXT)))
        assertFalse(xmlBackfillHints(m, listOf("taskTypeList"), emptySet())[0].temporal)
    }
}
