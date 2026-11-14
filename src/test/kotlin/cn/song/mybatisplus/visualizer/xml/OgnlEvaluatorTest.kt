package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 受限 OGNL 求值器测试（T-M24-02）：判真规则（对齐 MyBatis ExpressionEvaluator）、
 * 运算符矩阵、属性/方法白名单、解析失败兜底。
 */
class OgnlEvaluatorTest {

    private fun eval(expr: String, bindings: Map<String, Any?> = emptyMap(), staticValues: Map<String, Any?> = emptyMap()): Any? =
        when (val r = OgnlEvaluator.evaluate(expr, bindings, staticValues)) {
            is EvalResult.Ok -> r.value
            is EvalResult.Err -> error("unexpected eval error: ${r.message}")
        }

    private fun truth(expr: String, bindings: Map<String, Any?> = emptyMap(), staticValues: Map<String, Any?> = emptyMap()): Boolean =
        OgnlEvaluator.isTrue(eval(expr, bindings, staticValues))

    // ------------------------------------------------------------------
    // 判真规则（MyBatis ExpressionEvaluator：Boolean / Number≠0 / 其他非 null）
    // ------------------------------------------------------------------

    @Test
    fun `判真规则_空串为真_零为假_null为假`() {
        assertFalse(OgnlEvaluator.isTrue(null))
        assertTrue(OgnlEvaluator.isTrue(""))
        assertFalse(OgnlEvaluator.isTrue(0))
        assertTrue(OgnlEvaluator.isTrue(1))
        assertTrue(OgnlEvaluator.isTrue(0.5))
        assertTrue(OgnlEvaluator.isTrue("x"))
        assertTrue(OgnlEvaluator.isTrue(true))
        assertFalse(OgnlEvaluator.isTrue(false))
    }

    // ------------------------------------------------------------------
    // 字面量与运算符
    // ------------------------------------------------------------------

    @Test
    fun `字符串与数字字面量`() {
        assertEquals("hello", eval("'hello'"))
        assertEquals("hello", eval("\"hello\""))
        assertEquals("he'l", eval("'he\\'l'")) // 转义单引号
        assertEquals(42L, eval("42"))
        assertEquals(3.5, eval("3.5"))
        assertEquals(true, eval("true"))
        assertEquals(null, eval("null"))
    }

    @Test
    fun `字符串转义`() {
        assertEquals("a'b", eval("'a\\'b'"))
        assertEquals("a\nb", eval("'a\\nb'"))
    }

    @Test
    fun `逻辑运算_and_or_not`() {
        assertTrue(truth("true and true"))
        assertFalse(truth("true and false"))
        assertTrue(truth("true or false"))
        assertTrue(truth("not false"))
        assertFalse(truth("!true"))
        assertTrue(truth("true && true"))
        assertTrue(truth("false || true"))
    }

    @Test
    fun `比较运算_数值与字符串`() {
        assertTrue(truth("1 == 1"))
        assertTrue(truth("1 != 2"))
        assertTrue(truth("1 < 2"))
        assertTrue(truth("2 > 1"))
        assertTrue(truth("2 <= 2"))
        assertTrue(truth("2 >= 2"))
        assertTrue(truth("'a' < 'b'"))
        // 跨类型数值比较（OGNL OgnlOps.equal 语义）
        assertTrue(truth("1 == 1.0"))
    }

    @Test
    fun `算术运算`() {
        assertEquals(5, (eval("2 + 3") as Number).toInt())
        assertEquals(1, (eval("3 - 2") as Number).toInt())
        assertEquals(6, (eval("2 * 3") as Number).toInt())
        assertEquals(2, (eval("6 / 3") as Number).toInt())
        assertEquals(1, (eval("7 % 3") as Number).toInt())
        assertEquals(-5, (eval("-5") as Number).toInt())
        // 字符串连接（含 null → "null"，对齐 OGNL）
        assertEquals("%null%", eval("'%' + null + '%'"))
    }

    @Test
    fun `in与列表字面量`() {
        assertTrue(truth("1 in {1, 2, 3}"))
        assertFalse(truth("5 in {1, 2, 3}"))
        assertTrue(truth("'a' not in {'b', 'c'}"))
        assertTrue(truth("'ell' in 'hello'"))
    }

    // ------------------------------------------------------------------
    // 属性链 / 方法白名单
    // ------------------------------------------------------------------

    private val param = MockObject(
        mapOf(
            "title" to "MyBatis",
            "count" to 3L,
            "flag" to false,
            "inner" to MockObject(mapOf("name" to "x")),
        ),
    )
    private val bindings = mapOf(
        "_parameter" to param,
        "list" to listOf("a", "b"),
        "map" to mapOf("k1" to "v1"),
    )

    @Test
    fun `属性访问链与getter形态`() {
        assertEquals("MyBatis", eval("_parameter.title", bindings))
        assertEquals("MyBatis", eval("_parameter.getTitle()", bindings))
        assertEquals("x", eval("_parameter.inner.name", bindings))
        assertEquals(null, eval("_parameter.missing", bindings))
        assertTrue(truth("_parameter.count > 0", bindings))
        assertTrue(truth("_parameter.flag == false", bindings))
    }

    @Test
    fun `对象属性无法静态读取时只登记不猜值`() {
        data class Dto(val name: String)
        assertEquals(setOf("u.name"), OgnlEvaluator.unsupportedPropertyPaths(
            "u.name != null", mapOf("u" to Dto("x"))))
        assertEquals(setOf("outer.u.name"), OgnlEvaluator.unsupportedPropertyPaths(
            "outer.u.name != null", mapOf("outer" to mapOf("u" to Dto("x")))))
        assertTrue(OgnlEvaluator.unsupportedPropertyPaths(
            "u.name != null", mapOf("u" to MockObject(mapOf("name" to "x")))).isEmpty())
        assertTrue(OgnlEvaluator.unsupportedPropertyPaths(
            "u.name != null", mapOf("u" to mapOf("name" to "x"))).isEmpty())
    }

    @Test
    fun `方法白名单_size_length_contains等`() {
        assertEquals(2, (eval("list.size()", bindings) as Number).toInt())
        assertEquals(2, (eval("list.size", bindings) as Number).toInt()) // 属性形态等价
        assertEquals(5, (eval("'hello'.length()", bindings) as Number).toInt())
        assertTrue(truth("list.contains('a')", bindings))
        assertTrue(truth("'hello'.startsWith('he')", bindings))
        assertTrue(truth("'hello'.endsWith('lo')", bindings))
        assertTrue(truth("list.isEmpty() == false", bindings))
        assertTrue(truth("map.containsKey('k1')", bindings))
        // T-GoldenDefense：Map 取值三形态锁定为金样矩阵 X4（XmlMapParamSpecMatrixTest）
        // 未知方法 → null（安全降级，不抛错）
        assertEquals(null, eval("_parameter.customMethod()", bindings))
    }

    @Test
    fun `下标访问`() {
        assertEquals("b", eval("list[1]", bindings))
        assertEquals("v1", eval("map['k1']", bindings)) // T-GoldenDefense：金样矩阵 X4（XmlMapParamSpecMatrixTest）
    }

    // ------------------------------------------------------------------
    // 静态字段引用（T-M25.2：@class@field，MyBatis mapper 常见 OGNL 常量写法）
    // ------------------------------------------------------------------

    @Test
    fun `静态字段引用_注入值求值`() {
        val staticValues = mapOf("com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE" to "1")
        assertEquals("1", eval("@com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE", staticValues = staticValues))
        assertEquals("%1%", eval("'%' + @com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE + '%'", staticValues = staticValues))
        assertTrue(truth("@com.cyzc.DicConstants@FLAG == true", staticValues = mapOf("com.cyzc.DicConstants@FLAG" to true)))
    }

    @Test
    fun `静态字段引用_未注入时求值为null`() {
        assertEquals(null, eval("@com.cyzc.DicConstants@DIC_VALUE"))
    }

    @Test
    fun `静态字段引用_内部类与dollar类名`() {
        val staticValues = mapOf("com.cyzc.Outer\$Inner@VALUE" to "x")
        assertEquals("x", eval("@com.cyzc.Outer\$Inner@VALUE", staticValues = staticValues))
    }

    @Test
    fun `静态字段引用_语法错误报错`() {
        assertTrue(OgnlEvaluator.parse("@com.cyzc.DicConstants") is ParseResult.Err) // 缺 @字段
        assertTrue(OgnlEvaluator.parse("@com.cyzc.@X") is ParseResult.Err) // 类名空
    }

    @Test
    fun `静态字段引用_不产生根变量`() {
        assertEquals(emptySet<String>(), OgnlEvaluator.rootVariables("@com.cyzc.DicConstants@DIC_VALUE"))
    }

    @Test
    fun `双竖线包裹剥壳兼容`() {
        // 社区非标准写法 ||X||（真实 OGNL Malformed）——预览宽容按 X 解析
        val staticValues = mapOf("com.cyzc.DicConstants@DIC_VALUE" to "1")
        assertEquals("1", eval("||@com.cyzc.DicConstants@DIC_VALUE||", staticValues = staticValues))
        assertEquals("1", eval("'' || @com.cyzc.DicConstants@DIC_VALUE || ''", staticValues = staticValues))
        // 剥壳不误伤真二元 ||（不以 || 开头/结尾）
        assertTrue(truth("true || false"))
        assertTrue(truth("null || 'x'"))
        // 极端嵌套剥壳
        assertEquals("1", eval("||||@com.cyzc.DicConstants@DIC_VALUE||||", staticValues = staticValues))
    }

    // ------------------------------------------------------------------
    // 解析失败兜底
    // ------------------------------------------------------------------

    @Test
    fun `解析失败返回Err_非法语法与未闭合`() {
        assertTrue(OgnlEvaluator.parse("a ==") is ParseResult.Err)
        assertTrue(OgnlEvaluator.parse("'unterminated") is ParseResult.Err)
        assertTrue(OgnlEvaluator.parse("a b c") is ParseResult.Err)
        assertTrue(OgnlEvaluator.parse("") is ParseResult.Err) // 空表达式无主
    }

    // ------------------------------------------------------------------
    // 异常封闭与递归深度守卫（T-SAF-19：Ognl.kt@parse-exception-confinement）
    // ------------------------------------------------------------------

    @Test
    fun `超长整数面值_不抛异常按大整数解析`() {
        // 修复前：Lexer.number() 的 raw.toLong() 对 20+ 位面值抛未捕获 NumberFormatException，
        // 逃出 parse() 的 ParseResult.Err 契约直达调用方（XmlPreviewModel / MCP mapper-check）。
        // 合流裁决（T-SecurityAuditFixes T-SAF-19 × T-HiddenBugPerfScan 券 K①）：异常封闭目标由
        // BigDecimal 回落达成（不抛即无外逃），语义取真实 OGNL 的大整数形态（OgnlNumericBoundaryTest 口径），
        // 不走 Err 拒绝
        val r = OgnlEvaluator.parse("99999999999999999999")
        assertTrue("超长面值应按大整数解析为 Ok：$r", r is ParseResult.Ok)
    }

    @Test
    fun `超长整数面值_求值不抛且按大整数比较`() {
        // 修复前：evaluate(text) 在 Throwable 守卫的 AST 求值器之外调用 parse()，NFE 直接外抛。
        // 合流裁决同上：BigDecimal 回落保证不抛，比较语义按真实 OGNL 大整数（>1 判真）
        val r = OgnlEvaluator.evaluate("99999999999999999999 > 1", emptyMap())
        assertTrue("求值应得 Ok：$r", r is EvalResult.Ok)
        assertTrue("99999999999999999999 > 1 应判真：$r", OgnlEvaluator.isTrue((r as EvalResult.Ok).value))
    }

    @Test
    fun `深度嵌套括号_有界失败而非栈溢出`() {
        // 修复前：50000 层括号驱动 parseOr..parsePrimary 递归下降 → StackOverflowError
        // 逃出 Err 契约；修复后递归深度计数（上限 512）超限抛 OgnlParseException → Err
        val expr = "(".repeat(50000) + "1" + ")".repeat(50000)
        val r = OgnlEvaluator.parse(expr)
        assertTrue("应有界失败返回 Err：$r", r is ParseResult.Err)
    }

    @Test
    fun `根变量提取`() {
        assertEquals(setOf("_parameter"), OgnlEvaluator.rootVariables("_parameter.getTitle()"))
        assertEquals(setOf("list"), OgnlEvaluator.rootVariables("list != null and list.size() > 0"))
        assertEquals(setOf("a", "b"), OgnlEvaluator.rootVariables("a == 1 or b == 2"))
        assertEquals(emptySet<String>(), OgnlEvaluator.rootVariables("1 + 2"))
        assertEquals(emptySet<String>(), OgnlEvaluator.rootVariables("not valid @@"))
    }
}
