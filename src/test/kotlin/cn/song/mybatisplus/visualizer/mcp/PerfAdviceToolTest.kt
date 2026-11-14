package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sqlv_perf_advice 工具测试（T-McpToolset2 / T-MTS2-01）：发现输出（英文渲染）、
 * 无发现输出、空输入拒绝；T-McpOutputBudget / O-20 输出预算（条数裁剪与计数后缀、
 * SEVERITY 优先保留、单条文案截断、少于上限零差异）。规则细节由 PerfRule 系列测试覆盖。
 */
class PerfAdviceToolTest {

    private fun advise(sql: String) = SqlVisualizerPerfAdviceTool().perfAdvice(sql)

    /** 合成发现（messageKey 用真实键保文案可复现；line=-1 不带行号段）。 */
    private fun finding(ruleId: String, severity: PerfSeverity) =
        PerfWarning(ruleId, severity, "perf.no_where")

    // ------------------------------------------------------------------
    // 基础渲染（T-MTS2-01 既有口径）
    // ------------------------------------------------------------------

    @Test
    fun `SELECT星号_给出发现清单`() {
        val output = advise("SELECT * FROM t_user WHERE id = 1")
        assertTrue(output, output.contains("finding"))
        assertTrue("发现行带严重度标记", output.contains("["))
    }

    @Test
    fun `无发现语句_如实输出通过`() {
        val output = advise("SELECT id FROM t_user WHERE id = 1")
        assertTrue(output, output.contains("No performance findings"))
    }

    @Test
    fun `空输入_预期错误`() {
        val error = runCatching { SqlVisualizerPerfAdviceTool().perfAdvice("") }.exceptionOrNull()
        assertTrue(error is SqlvExpectedToolException)
    }

    // ------------------------------------------------------------------
    // 条数预算（MAX_FINDINGS=30）
    // ------------------------------------------------------------------

    @Test
    fun `超限_条数裁剪且计数后缀恒可见`() {
        val warnings = (1..45).map { finding("rule_$it", PerfSeverity.INFO) }
        val output = SqlVisualizerPerfAdviceTool().renderFindings(warnings)
        assertTrue("头部计总数: $output", output.startsWith("45 finding(s):"))
        assertEquals("只渲染 30 条", 30, output.split("\n- [").size - 1)
        assertTrue(
            "计数后缀恒可见: $output",
            output.contains(
                "[TRUNCATED] showing 30 of 45 findings " +
                    "(15 more; ordered ERROR > WARN > INFO, lower severities trimmed first).",
            ),
        )
    }

    @Test
    fun `超限_SEVERITY优先保留_低级先裁`() {
        val info = (1..40).map { finding("info_$it", PerfSeverity.INFO) }
        val errors = (1..5).map { finding("error_$it", PerfSeverity.ERROR) }
        // ERROR 全部放在清单尾部——排序后必须整体保留并前移
        val output = SqlVisualizerPerfAdviceTool().renderFindings(info + errors)
        assertEquals("5 条 ERROR 全保留", 5, output.split("[ERROR]").size - 1)
        assertTrue("ERROR 块在前: $output", output.indexOf("[ERROR]") < output.indexOf("[INFO]"))
        assertTrue("INFO 保留前 25 条", output.contains("info_25"))
        assertFalse("第 26 条起被裁", output.contains("info_26"))
    }

    // ------------------------------------------------------------------
    // 单条文案预算（MAX_ADVICE_CHARS=200）
    // ------------------------------------------------------------------

    @Test
    fun `单条文案超预算_截断加省略号`() {
        // 未知键兜底返回键名 = 稳定的超长文案（messageEn 契约）
        val longKey = "z".repeat(SqlVisualizerPerfAdviceTool.MAX_ADVICE_CHARS + 50)
        val output = SqlVisualizerPerfAdviceTool().renderFindings(listOf(finding("r", PerfSeverity.WARN).copy(messageKey = longKey)))
        assertTrue(
            "含截断形态: $output",
            output.contains("z".repeat(SqlVisualizerPerfAdviceTool.MAX_ADVICE_CHARS) + "…"),
        )
        assertFalse("不含未截断全文", output.contains(longKey))
    }

    // ------------------------------------------------------------------
    // 防回归硬门禁：少于上限时输出逐字节不变
    // ------------------------------------------------------------------

    @Test
    fun `少于上限_不排序不裁剪_与无预算版逐字节一致`() {
        // 严重度刻意乱序——排序即暴露（预算版只在真超限时排序）
        val warnings = listOf(
            finding("rule_b", PerfSeverity.INFO),
            finding("rule_a", PerfSeverity.ERROR),
            finding("rule_c", PerfSeverity.WARN),
        )
        val output = SqlVisualizerPerfAdviceTool().renderFindings(warnings)
        val message = MpSqlVisualizerBundle.messageEn("perf.no_where")
        val legacy = buildString {
            append("3 finding(s):\n")
            append("- [INFO] rule_b: ").append(message).append('\n')
            append("- [ERROR] rule_a: ").append(message).append('\n')
            append("- [WARN] rule_c: ").append(message)
        }
        assertEquals(legacy, output)
    }

    @Test
    fun `恰好三十条_不裁剪不排序无尾注`() {
        val warnings = (1..SqlVisualizerPerfAdviceTool.MAX_FINDINGS).map { i ->
            finding("r$i", if (i % 2 == 0) PerfSeverity.INFO else PerfSeverity.ERROR)
        }
        val output = SqlVisualizerPerfAdviceTool().renderFindings(warnings)
        assertFalse("不裁剪: $output", output.contains("TRUNCATED"))
        assertTrue("保持输入顺序（ERROR/INFO 交错不被打散）", output.contains("- [ERROR] r1:") && output.contains("- [INFO] r2:"))
        assertEquals("头部计总数", 0, output.indexOf("${SqlVisualizerPerfAdviceTool.MAX_FINDINGS} finding(s):"))
    }

    // ------------------------------------------------------------------
    // T-SAF-17（mcp-sqlv-text-tools-unbounded-sql-input）：输入长度上限
    // ------------------------------------------------------------------

    @Test
    fun `超长输入_结构化拒绝（先红）`() {
        // PluginSettings.DEFAULT_LOG_MAX_PARSE_LENGTH（20000）口径：改前无上限，超长 SQL
        // 直通 21 条规则分析链（先红：断言结构化拒绝失败）。
        val big = "SELECT * FROM t WHERE id IN (" + "1, ".repeat(11_000) + "1)"
        val error = runCatching { SqlVisualizerPerfAdviceTool().perfAdvice(big) }.exceptionOrNull()
        assertTrue("超长应抛 SqlvExpectedToolException：$error", error is SqlvExpectedToolException)
        val message = (error as? SqlvExpectedToolException)?.message
        assertTrue("错误应带 INVALID_ARGUMENT 码：$message", message.orEmpty().contains(McpToolErrors.INVALID_ARGUMENT))
        assertTrue("错误应带长度上限语义：$message", message.orEmpty().contains("maximum length"))
    }
}
