package cn.song.mybatisplus.visualizer.sql.interceptor

import cn.song.mybatisplus.visualizer.config.TableNameRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DynamicTableNameProcessor 单元测试（F-6 Phase B）：验证正则表名替换引擎。
 */
class DynamicTableNameProcessorTest {

    @Before
    fun setUp() {
        DynamicTableNameProcessor.clearCache()
    }

    @Test
    fun `simple replacement without regex group`() {
        val rules = listOf(TableNameRule("user", "order", enabled = true))
        assertEquals("order", DynamicTableNameProcessor.process("user", rules))
    }

    @Test
    fun `regex capture group reference`() {
        val rules = listOf(TableNameRule("user_(\\d+)", "user", enabled = true))
        assertEquals("user", DynamicTableNameProcessor.process("user_2024", rules))
    }

    @Test
    fun `tableName variable reference`() {
        val rules = listOf(TableNameRule("order", "archive_\$tableName", enabled = true))
        assertEquals("archive_order", DynamicTableNameProcessor.process("order", rules))
    }

    @Test
    fun `no matching rule returns null`() {
        val rules = listOf(TableNameRule("user", "order", enabled = true))
        assertNull(DynamicTableNameProcessor.process("product", rules))
    }

    @Test
    fun `disabled rule is skipped`() {
        val rules = listOf(TableNameRule("user", "order", enabled = false))
        assertNull(DynamicTableNameProcessor.process("user", rules))
    }

    @Test
    fun `first matching rule wins`() {
        val rules = listOf(
            TableNameRule("user_(\\d+)", "first_\$1", enabled = true),
            TableNameRule("user_.*", "second", enabled = true),
        )
        assertEquals("first_2024", DynamicTableNameProcessor.process("user_2024", rules))
    }

    @Test
    fun `empty rules list returns null`() {
        assertNull(DynamicTableNameProcessor.process("user", emptyList()))
    }

    @Test
    fun `blank pattern is skipped`() {
        val rules = listOf(TableNameRule("", "order", enabled = true))
        assertNull(DynamicTableNameProcessor.process("user", rules))
    }

    @Test
    fun `invalid regex is skipped gracefully`() {
        val rules = listOf(TableNameRule("user[invalid", "order", enabled = true))
        assertNull(DynamicTableNameProcessor.process("user", rules))
    }

    @Test
    fun `multiple capture groups`() {
        val rules = listOf(TableNameRule("(\\w+)_(\\d+)", "\$1_archive_\$2", enabled = true))
        assertEquals("order_archive_2024", DynamicTableNameProcessor.process("order_2024", rules))
    }

    @Test
    fun `partial match replaces entire table name`() {
        // 正则匹配部分即可命中，但替换模板指定的是完整新表名（非仅替换匹配部分）
        val rules = listOf(TableNameRule("_\\d+", "archive", enabled = true))
        assertEquals("archive", DynamicTableNameProcessor.process("order_2024", rules))
    }

    // ======================== T-SAF-25：匹配预算（ReDoS 防护） ========================

    /**
     * T-SAF-25（dynamic-tablename-rule.regex-unbounded）回归：导入设置可安装灾难性回溯模式
     * （嵌套量词），预览渲染路径 process() 曾以 regex.find(tableName) 无预算执行——现实长度
     * 的非匹配表名即可挂死渲染线程（可用性影响波及文件作者之外）。
     *
     * **修复前失败方式**：用例挂死到测试超时——`(a+)+b` 对 48 个 'a' 的非匹配输入在无预算
     * java.util.regex 下是灾难性回溯（约 2^48 引擎步），永远到不了断言行。修复语义：求值有
     * 步数预算，超预算视为无匹配（fail-safe）并至多告警一次。
     */
    @Test
    fun `病态回溯模式有界返回_T-SAF-25`() {
        val rules = listOf(TableNameRule("(a+)+b", "order", enabled = true))
        val adversarial = "a".repeat(48)
        val start = System.nanoTime()
        val result = DynamicTableNameProcessor.process(adversarial, rules)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertNull("超预算应视为无匹配", result)
        assertTrue("病态回溯应在有界时间内返回（实际 ${elapsedMs}ms）", elapsedMs < 5_000)
    }

    /** T-SAF-25 回归：预算不误伤普通匹配——正常规则在预算内照常命中与替换。 */
    @Test
    fun `正常规则在预算内照常命中_T-SAF-25`() {
        val rules = listOf(TableNameRule("user_(\\d+)", "user", enabled = true))
        assertEquals("user", DynamicTableNameProcessor.process("user_2024", rules))
    }

    /**
     * T-SAF-25 回归：编译缓存容量封顶——regexCache 曾是普通 mutableMapOf 且无界（还非线程
     * 安全），海量 distinct pattern 灌入可无界膨胀。断言经内部测试缝（修复前不存在，随
     * fix 提交落地）读缓存条数 ≤ 256。
     */
    @Test
    fun `正则编译缓存容量封顶_T-SAF-25`() {
        try {
            repeat(400) { i ->
                DynamicTableNameProcessor.process(
                    "order_$i",
                    listOf(TableNameRule("distinct_pattern_$i", "x", enabled = true)),
                )
            }
            assertTrue(
                "缓存应封顶在 256（实际 ${DynamicTableNameProcessor.regexCacheSizeForTest()}）",
                DynamicTableNameProcessor.regexCacheSizeForTest() <= 256,
            )
        } finally {
            DynamicTableNameProcessor.clearCache()
        }
    }
}
