package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 I：ParamImportParser 两缺陷回归。
 *
 * ①嵌套 JSON 值跳过不推进游标——skipValue 返回值被丢弃，外层 while 停在 '{' 处
 * 被「非引号即返回」分支截断，嵌套 key 之后的全部顶层键静默丢失；
 * ②多行 key=value 首行值含逗号被误分流 CSV，键值全错位。
 */
class ParamImportParserSkipResumeTest {

    @Test
    fun `嵌套对象后的顶层键不丢失`() {
        val result = ParamImportParser.parseJson("""{"user":{"name":"x"},"status":"1"}""")
        assertEquals("嵌套 key 跳过、后续顶层键应保留", "1", result["status"])
        assertEquals("嵌套 key 不展平（预期跳过）", null, result["user"])
        assertEquals("嵌套内部键不得泄漏为顶层", null, result["name"])
    }

    @Test
    fun `嵌套数组后的顶层键不丢失`() {
        val result = ParamImportParser.parseJson("""{"ids":[1,2,3],"status":"ok"}""")
        assertEquals("ok", result["status"])
    }

    @Test
    fun `首行值含逗号的key=value不走CSV`() {
        val result = ParamImportParser.parse("a=1,2\nb=3")
        assertEquals("1,2", result["a"])
        assertEquals("3", result["b"])
    }

    @Test
    fun `真CSV形态不受守卫影响`() {
        val result = ParamImportParser.parse("name,age\n张三,20")
        assertEquals("张三", result["name"])
        assertEquals("20", result["age"])
        assertTrue(result.containsKey("age"))
    }
}
