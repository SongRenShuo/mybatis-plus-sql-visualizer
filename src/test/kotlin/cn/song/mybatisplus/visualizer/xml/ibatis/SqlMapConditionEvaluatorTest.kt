package cn.song.mybatisplus.visualizer.xml.ibatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 条件标签求值单测（T-Ibatis2Support 券 B）——对齐 `ConditionalTagHandler` 与 14 个 `isXxx` 处理器。
 */
class SqlMapConditionEvaluatorTest {

    private val evaluator = SqlMapConditionEvaluator()

    @Test
    fun `compareValue 按 value1 的声明类型换算`() {
        assertEquals(0L, evaluator.compareValues(Integer::class.java, 5, "5"))
        assertEquals(0L, evaluator.compareValues(Integer::class.java, 5, 5))
        assertEquals(0L, evaluator.compareValues(java.lang.Byte::class.java, 5.toByte(), "5"))
        assertEquals(0L, evaluator.compareValues(java.lang.Short::class.java, 5.toShort(), "5"))
        assertEquals(0L, evaluator.compareValues(java.lang.Long::class.java, 5L, "5"))
        assertEquals(0L, evaluator.compareValues(java.lang.Float::class.java, 5.0f, "5"))
        assertEquals(0L, evaluator.compareValues(java.lang.Double::class.java, 5.0, "5.0"))
        assertEquals(0L, evaluator.compareValues(java.lang.Boolean::class.java, true, "true"))
        assertEquals(0L, evaluator.compareValues(BigInteger::class.java, BigInteger("12"), "12"))
        assertEquals(0L, evaluator.compareValues(BigDecimal::class.java, BigDecimal("1.50"), "1.50"))
        assertEquals(0L, evaluator.compareValues(Character::class.java, 'x', "x"))
        assertTrue("5 < 7", evaluator.compareValues(Integer::class.java, 5, "7") < 0)
        assertTrue("7 > 5", evaluator.compareValues(Integer::class.java, 7, "5") > 0)
    }

    @Test
    fun `不可比时返回 NOT_COMPARABLE 且它是负数`() {
        assertEquals(SqlMapConditionEvaluator.NOT_COMPARABLE, evaluator.compareValues(Integer::class.java, null, "1"))
        // 引擎在此处抛 NumberFormatException；本链按券 B 的健壮性口径折成 NOT_COMPARABLE + 告警（已登记偏差）
        assertEquals(SqlMapConditionEvaluator.NOT_COMPARABLE, evaluator.compareValues(Integer::class.java, 5, "N/A"))
        // NOT_COMPARABLE = Long.MIN_VALUE → isLessThan 判真、isGreaterThan 判假（引擎同口径）
        assertTrue("NOT_COMPARABLE 必须 < 0", SqlMapConditionEvaluator.NOT_COMPARABLE < 0)
    }

    @Test
    fun `Date 掩码固定为 yyyy_MM_dd hh_mm_ss`() {
        assertEquals("yyyy/MM/dd hh:mm:ss", SqlMapConditionEvaluator.DATE_MASK)
        val literal = "2020/01/02 03:04:05"
        val converted = evaluator.convertValue(Date::class.java, literal) as Date
        assertEquals(SimpleDateFormat(SqlMapConditionEvaluator.DATE_MASK).parse(literal), converted)
    }

    @Test
    fun `isEmpty 覆盖集合 数组与字符串三态`() {
        val ctx = IbatisTagContext()
        assertTrue(condition(IbatisTagKind.IS_EMPTY, ctx, tag(IbatisTagKind.IS_EMPTY), null))
        assertTrue(condition(IbatisTagKind.IS_EMPTY, ctx, tag(IbatisTagKind.IS_EMPTY, "p"), mapOf("p" to emptyList<String>())))
        assertTrue(condition(IbatisTagKind.IS_EMPTY, ctx, tag(IbatisTagKind.IS_EMPTY, "p"), mapOf("p" to IntArray(0))))
        assertTrue(condition(IbatisTagKind.IS_EMPTY, ctx, tag(IbatisTagKind.IS_EMPTY, "p"), mapOf("p" to "")))
        assertFalse(condition(IbatisTagKind.IS_EMPTY, ctx, tag(IbatisTagKind.IS_EMPTY, "p"), mapOf("p" to "x")))
        assertFalse(condition(IbatisTagKind.IS_EMPTY, ctx, tag(IbatisTagKind.IS_EMPTY, "p"), mapOf("p" to listOf(1))))
        assertTrue(condition(IbatisTagKind.IS_NOT_EMPTY, ctx, tag(IbatisTagKind.IS_NOT_EMPTY, "p"), mapOf("p" to listOf(1))))
    }

    @Test
    fun `isPropertyAvailable 对 Map 走 containsKey 而不是 Probe`() {
        val ctx = IbatisTagContext()
        val params = mapOf("present" to 1)
        assertTrue(condition(IbatisTagKind.IS_PROPERTY_AVAILABLE, ctx, tag(IbatisTagKind.IS_PROPERTY_AVAILABLE, "present"), params))
        // Probe 对 Map 的任何属性名都返回 true——必须用 containsKey，否则这里会假真
        assertFalse(condition(IbatisTagKind.IS_PROPERTY_AVAILABLE, ctx, tag(IbatisTagKind.IS_PROPERTY_AVAILABLE, "missing"), params))
        assertTrue(condition(IbatisTagKind.IS_NOT_PROPERTY_AVAILABLE, ctx, tag(IbatisTagKind.IS_NOT_PROPERTY_AVAILABLE, "missing"), params))
        assertFalse(condition(IbatisTagKind.IS_PROPERTY_AVAILABLE, ctx, tag(IbatisTagKind.IS_PROPERTY_AVAILABLE, "x"), null))
    }

    @Test
    fun `复合属性取最后一段点号后再判可读性`() {
        // 引擎的复合属性分支只在**非 Map** 参数对象上生效（Map 走 containsKey 原始属性名，见上一个用例）
        val ctx = IbatisTagContext()
        val params = NestedHolder(mapOf("flag" to 1))
        assertTrue(condition(IbatisTagKind.IS_PROPERTY_AVAILABLE, ctx, tag(IbatisTagKind.IS_PROPERTY_AVAILABLE, "nested.flag"), params))
        assertFalse(condition(IbatisTagKind.IS_PROPERTY_AVAILABLE, ctx, tag(IbatisTagKind.IS_PROPERTY_AVAILABLE, "nested.other"), params))
        assertFalse(condition(IbatisTagKind.IS_PROPERTY_AVAILABLE, ctx, tag(IbatisTagKind.IS_PROPERTY_AVAILABLE, "absent.flag"), params))

        // Map 参数对象下 `nested.flag` 走 containsKey("nested.flag") → 恒 false
        val mapParams = mapOf("nested" to mapOf("flag" to 1))
        assertFalse(condition(IbatisTagKind.IS_PROPERTY_AVAILABLE, ctx, tag(IbatisTagKind.IS_PROPERTY_AVAILABLE, "nested.flag"), mapParams))
    }

    @Test
    fun `isParameterPresent 只看参数对象是否为 null`() {
        val ctx = IbatisTagContext()
        val present = tag(IbatisTagKind.IS_PARAMETER_PRESENT)
        val notPresent = tag(IbatisTagKind.IS_NOT_PARAMETER_PRESENT)
        assertFalse(condition(IbatisTagKind.IS_PARAMETER_PRESENT, ctx, present, null))
        assertTrue(condition(IbatisTagKind.IS_NOT_PARAMETER_PRESENT, ctx, notPresent, null))
        assertTrue(condition(IbatisTagKind.IS_PARAMETER_PRESENT, ctx, present, emptyMap<String, Any>()))
        assertFalse(condition(IbatisTagKind.IS_NOT_PARAMETER_PRESENT, ctx, notPresent, emptyMap<String, Any>()))
    }

    @Test
    fun `比较族六种符号与 compareProperty 形态`() {
        val ctx = IbatisTagContext()
        val params = mapOf("n" to 3, "same" to 3, "other" to 9)
        fun compare(kind: IbatisTagKind, compareValue: String? = null, compareProperty: String? = null) =
            condition(kind, ctx, tag(kind, "n", compareProperty, compareValue), params)

        assertFalse(compare(IbatisTagKind.IS_EQUAL, compareValue = "4"))
        assertTrue(compare(IbatisTagKind.IS_EQUAL, compareValue = "3"))
        assertTrue(compare(IbatisTagKind.IS_NOT_EQUAL, compareValue = "4"))
        assertTrue(compare(IbatisTagKind.IS_GREATER_THAN, compareValue = "2"))
        assertTrue(compare(IbatisTagKind.IS_GREATER_EQUAL, compareValue = "3"))
        assertTrue(compare(IbatisTagKind.IS_LESS_THAN, compareValue = "4"))
        assertTrue(compare(IbatisTagKind.IS_LESS_EQUAL, compareValue = "3"))
        assertTrue(compare(IbatisTagKind.IS_EQUAL, compareProperty = "same"))
        assertFalse(compare(IbatisTagKind.IS_EQUAL, compareProperty = "other"))
    }

    @Test
    fun `缺少比较对象时按不成立处理并告警`() {
        val warnings = ArrayList<String>()
        val ctx = IbatisTagContext()
        val evaluator = SqlMapConditionEvaluator { warnings.add(it) }
        val result = evaluator.isCondition(IbatisTagKind.IS_EQUAL, ctx, tag(IbatisTagKind.IS_EQUAL, "n"), mapOf("n" to 1))
        assertFalse(result)
        assertTrue(warnings.any { it.contains("compareProperty") })
    }

    // ------------------------------------------------------------------
    // T-Ibatis2Gap 券 A：数值比较增强（Map 参数下两侧都是 String，引擎走字典序会误判）
    // ------------------------------------------------------------------

    @Test
    fun `数值比较增强_两侧可解析为数值时按数值比较`() {
        // 本券要修的形态：`"9" vs "18"` 字典序会判 9 > 18（误）；数值比较必须 9 < 18
        assertTrue("9 < 18（字典序会误判 9 > 18）", evaluator.compareValues(String::class.java, "9", "18") < 0)
        assertEquals(0L, evaluator.compareValues(String::class.java, "18", "18"))
        assertTrue(evaluator.compareValues(String::class.java, "20", "18") > 0)
        // 负数与小数同口径
        assertTrue(evaluator.compareValues(String::class.java, "-2", "-1") < 0)
        assertTrue(evaluator.compareValues(String::class.java, "1.5", "1.05") > 0)
        // 与 Integer 声明类型路径同结果（超集增强不得改变既有正确路径）
        assertTrue(evaluator.compareValues(Integer::class.java, 9, "18") < 0)
        // 小数走 Double 声明类型的既有换算路径
        assertTrue(evaluator.compareValues(java.lang.Double::class.java, 2.0, "1.5") > 0)
        // Integer 声明类型比 "1.5"：引擎换算失败（NumberFormatException）→ 既有口径 NOT_COMPARABLE
        assertEquals(SqlMapConditionEvaluator.NOT_COMPARABLE, evaluator.compareValues(Integer::class.java, 2, "1.5"))
    }

    @Test
    fun `数值比较增强_非数值路径维持既有行为`() {
        // Date 字面量不可解析为数值 → 走既有转换路径（照旧可比）
        assertTrue(evaluator.compareValues(Date::class.java, Date(0), "1970/01/01 08:00:01") != 0L)
        // 布尔走既有路径
        assertTrue(evaluator.compareValues(java.lang.Boolean::class.java, true, "false") > 0)
        // 纯字符串不可解析为数值 → 维持字典序
        assertTrue(evaluator.compareValues(String::class.java, "abc", "abd") < 0)
        assertTrue(evaluator.compareValues(String::class.java, "abc", "abc") == 0L)
        // 任一侧非数值 → 既有路径（本例换算失败 → NOT_COMPARABLE）
        assertEquals(SqlMapConditionEvaluator.NOT_COMPARABLE, evaluator.compareValues(Integer::class.java, 5, "N/A"))
    }

    @Test
    fun `数值比较增强_条件族端到端_isGreaterThan在Map参数下不再字典序误判`() {
        val ctx = IbatisTagContext()
        val tag = tag(IbatisTagKind.IS_GREATER_THAN, "age", null, "18")
        // Map 参数（预览交互装配的形态：值一律 String）
        assertFalse("age=9 不满足 >18（字典序会误判成立）", condition(IbatisTagKind.IS_GREATER_THAN, ctx, tag, mapOf("age" to "9")))
        assertTrue("age=20 满足 >18", condition(IbatisTagKind.IS_GREATER_THAN, ctx, tag, mapOf("age" to "20")))
        // 小数值在 isLessThan 下同样按数值判（既有字典序会误判 "20" < "18" 不成立）
        val less = tag(IbatisTagKind.IS_LESS_THAN, "age", null, "18")
        assertTrue(condition(IbatisTagKind.IS_LESS_THAN, ctx, less, mapOf("age" to "9")))
        assertFalse(condition(IbatisTagKind.IS_LESS_THAN, ctx, less, mapOf("age" to "20")))
    }

    private fun condition(kind: IbatisTagKind, ctx: IbatisTagContext, tag: IbatisTagNode, params: Any?): Boolean =
        evaluator.isCondition(kind, ctx, tag, params)

    /** 复合属性判定的对照夹具（非 Map 的 JavaBean）。 */
    private data class NestedHolder(val nested: Map<String, Int>)

    private fun tag(
        kind: IbatisTagKind,
        property: String? = null,
        compareProperty: String? = null,
        compareValue: String? = null,
        parent: IbatisTagNode? = null,
    ): IbatisTagNode = IbatisTagNode(
        kind = kind,
        prepend = null,
        property = property,
        removeFirstPrepend = null,
        open = null,
        close = null,
        conjunction = null,
        compareProperty = compareProperty,
        compareValue = compareValue,
        postParseRequired = false,
        children = emptyList(),
    ).also { parent?.let { p -> it.attachParent(p) } }
}
