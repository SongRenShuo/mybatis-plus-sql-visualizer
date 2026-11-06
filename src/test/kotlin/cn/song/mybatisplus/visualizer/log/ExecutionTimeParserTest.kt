package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行耗时解析器单元测试（O-1 Phase B）：从日志文本中解析执行耗时（毫秒）。
 *
 * 覆盖场景：
 * - p6spy elapsed 格式：`elapsed 123 ms` 或 `consumed 456ms`
 * - MP TimeCount 格式：`TimeCount: 789ms` 或 `time: 100 ms`
 * - 自定义前缀（logTimePrefix 配置）：`Time: 200ms`
 * - 无耗时信息返回 null
 */
class ExecutionTimeParserTest {

    @Test
    fun `p6spy elapsed 格式解析`() {
        assertEquals(123L, ExecutionTimeParser.parse("elapsed 123 ms"))
    }

    @Test
    fun `p6spy consumed 格式解析`() {
        assertEquals(456L, ExecutionTimeParser.parse("consumed 456ms"))
    }

    @Test
    fun `MP TimeCount 格式解析`() {
        assertEquals(789L, ExecutionTimeParser.parse("TimeCount: 789ms"))
    }

    @Test
    fun `time 前缀格式解析`() {
        assertEquals(100L, ExecutionTimeParser.parse("time: 100 ms"))
    }

    @Test
    fun `自定义前缀 Time 解析`() {
        assertEquals(200L, ExecutionTimeParser.parse("Time: 200ms", "Time:"))
    }

    @Test
    fun `自定义前缀 duration 解析`() {
        assertEquals(50L, ExecutionTimeParser.parse("duration:50ms", "duration:"))
    }

    @Test
    fun `无耗时信息返回 null`() {
        assertNull(ExecutionTimeParser.parse("SELECT * FROM user WHERE id = 1"))
    }

    @Test
    fun `空串返回 null`() {
        assertNull(ExecutionTimeParser.parse(""))
    }

    @Test
    fun `仅数字无单位返回 null`() {
        assertNull(ExecutionTimeParser.parse("12345"))
    }

    @Test
    fun `耗时后紧跟其他文本`() {
        assertEquals(300L, ExecutionTimeParser.parse("elapsed 300 ms | SELECT * FROM t"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(150L, ExecutionTimeParser.parse("ELAPSED 150 MS"))
    }

    // ---------- 行尾「数字 + ms」兜底语义（T-SAF-21 重锚定时的语义护栏） ----------

    @Test
    fun `行尾方括号后空白加数字ms`() {
        assertEquals(789L, ExecutionTimeParser.parse("SELECT 1 FROM t WHERE x = 1] 789ms"))
    }

    @Test
    fun `行尾空格加数字ms`() {
        assertEquals(123L, ExecutionTimeParser.parse("abc 123ms"))
    }

    @Test
    fun `行尾数字紧贴右括号`() {
        assertEquals(456L, ExecutionTimeParser.parse("==> Parameters: 1(Long)]456ms"))
    }

    @Test
    fun `数字前无分隔符不匹配`() {
        assertNull("字母紧贴数字无 ] / 空白分隔 → 兜底不命中", ExecutionTimeParser.parse("abc123ms"))
    }

    @Test
    fun `长空白段后的行尾ms照常提取`() {
        assertEquals(
            321L,
            ExecutionTimeParser.parse("==> Parameters: 1(Long)" + " ".repeat(1_000) + "321ms"),
        )
    }

    @Test
    fun `多行文本首个行尾ms命中`() {
        assertEquals(
            100L,
            ExecutionTimeParser.parse("first line\nx 100ms\nsecond line without ms"),
        )
    }

    @Test
    fun `纯空白填充行无ms返回null`() {
        assertNull(ExecutionTimeParser.parse("==> Parameters: 1(Long)" + " ".repeat(1_000)))
    }

    // ---------- T-SAF-21（log.executiontimeparser.trailing-ms-whitespace-backtracking）：增长护栏 ----------

    /**
     * 单次操作耗时（至少 50ms 窗口内取均值，压小样本噪声；先预热 3 次）。
     */
    private fun measurePerOp(n: Int): Long {
        val line = "==> Parameters: 1(Long)" + " ".repeat(n)
        repeat(3) { ExecutionTimeParser.parse(line) }
        var ops = 0
        val start = System.nanoTime()
        do {
            ExecutionTimeParser.parse(line)
            ops++
        } while (System.nanoTime() - start < 50_000_000L)
        return (System.nanoTime() - start) / ops
    }

    /**
     * 增长护栏：行长从 1k → 100k（空白段 100 倍）时单次解析耗时不得超线性增长。
     *
     * 修复前（NEEDS-VALIDATION #21 复现证据）：TRAILING_MS_REGEX 的前导 `[\]\s]` 让每个空白
     * 索引都成为一次匹配尝试，`\s*(\d+)` 迫使每次尝试回溯整个空白段——二次曲线下
     * 100k/1k 耗时比约 100 倍（100k 单次即达秒级），本断言（20 倍阈值）转红。
     * 修复后（lookbehind 重锚定，每个空白索引 O(1) 失败）耗时比 ≈ 1，恒绿。
     */
    @Test
    fun `空白段增长不得超线性`() {
        val small = measurePerOp(1_000)
        val large = measurePerOp(100_000)
        assertTrue(
            "100k/1k 单次耗时比应近线性（实际 small=${small}ns large=${large}ns " +
                "ratio=${large / small.coerceAtLeast(1)}，阈值 20）",
            large < small * 20 + 1_000,
        )
    }
}
