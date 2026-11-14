package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 K：OGNL 数值边界回归。
 *
 * ①超宽整数字面量（>Long.MAX_VALUE）曾 toLong() 直抛 NumberFormatException——unchecked
 * 异常穿透 parseUncached 的 OgnlParseException 收敛层直上 EDT（mapper 里
 * `test="id > 99999999999999999999"` 即触发预览整链失败）；真实 OGNL 按大整数处理。
 * ②除法曾统一 BigDecimal 精确除，整型语义丢失（7/2=3.5 vs 真实 OGNL=2）——
 * if/choose 分支与运行时分叉、`${pages / 2}` 产物出非法 SQL 片段。
 */
class OgnlNumericBoundaryTest {

    private fun okValue(expr: String, bindings: Map<String, Any?> = emptyMap()): Any? =
        when (val r = OgnlEvaluator.evaluate(expr, bindings)) {
            is EvalResult.Ok -> r.value
            is EvalResult.Err -> error("unexpected eval error: ${r.message}")
        }

    private fun truth(expr: String, bindings: Map<String, Any?> = emptyMap()): Boolean =
        OgnlEvaluator.isTrue(okValue(expr, bindings))

    @Test
    fun `超宽整数字面量不抛异常_按大整数参与比较`() {
        // 修复前：词法器 toLong() 抛 NumberFormatException（非 OgnlParseException）穿透 parse
        val r = OgnlEvaluator.evaluate("99999999999999999999 > 1", emptyMap())
        assertTrue("不得抛异常，应得 Ok", r is EvalResult.Ok)
        assertTrue("99999999999999999999 > 1 应判真", truth("99999999999999999999 > 1"))
        assertTrue("大整数字面量可做 bind 比较对象", truth("id > 99999999999999999999", mapOf("id" to 1L)) == false)
    }

    @Test
    fun `整型除法_截断向零_7除2等于3_取模等于1`() {
        // Java/OGNL 整除 7/2=3（截断向零）；修复前 BigDecimal 精确除得 3.5——
        // test="total / 2 > 10" 在 total=21 时插件判 true、运行时（21/2=10）判 false
        assertEquals(3L, (okValue("7 / 2") as Number).toLong())
        assertEquals(1L, (okValue("7 % 2") as Number).toLong())
        assertTrue("21/2=10 不大于 10（与运行时同判假）", !truth("total / 2 > 10", mapOf("total" to 21)))
        assertTrue("25/2=12 大于 10", truth("total / 2 > 10", mapOf("total" to 25)))
    }

    @Test
    fun `小数除法保持精确除`() {
        assertEquals("3.5", (okValue("7 / 2.0") as Number).toString())
        assertTrue(truth("7 / 2.0 == 3.5"))
    }

    @Test
    fun `除零仍收敛为null判假_不抛`() {
        assertTrue(OgnlEvaluator.evaluate("7 / 0", emptyMap()) is EvalResult.Err || OgnlEvaluator.isTrue(okValue("7 / 0")) == false)
    }
}
