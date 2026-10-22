package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.execute.ErrorPositionExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.sql.SQLException

/**
 * T-Round10FU（独立复核 ⑨）：`sqlv_explain` 失败消息里 `errorPosition` 文案形态的守卫。
 *
 * **症结（⑨ 原文）**：这段文案原先**内联**在 `SqlVisualizerExplainTool.executeExplain` 的
 * `getOrElse` 分支里，而该分支要真数据源 + 项目上下文才可达 → 全仓 `src/test` 对 `errorPosition`
 * 的 grep 命中数为 **0**，`McpToolGateTest` 只实例化工具类（不执行、不断言文案）。
 * 于是验收里「mcp 相关既有用例全绿」这句结论**没有任何用例支撑**（空绿）——改动本身形态未破坏，
 * 但「全绿」不能当证据。
 *
 * **本类补的是可执行支撑**：断言文案形态本身（`errorPosition: char N` / `line N` / 无信号零追加），
 * 并断言**生产链确实走那个纯函数**（源码扫描）。只钉文案不钉接线的话，把内联文案改回去就又回到空绿，
 * 故两条一起钉。
 *
 * **反向验证**：把 [McpExplainFormatter.formatErrorPosition] 的字面量改坏（或把
 * `SqlVisualizerExplainTool` 里那行改回内联）→ 本类必红。
 */
class McpErrorPositionTextTest {

    /** 字符位置形态（PG `Position: N` → isCharPosition=true）逐字钉住。 */
    @Test
    fun `字符位置形态逐字为 errorPosition char N`() {
        val pos = ErrorPositionExtractor.extractPosition("ERROR: column \"naem\" does not exist\n  Position: 42")!!
        assertEquals("\nerrorPosition: char 42", McpExplainFormatter.formatErrorPosition(pos))
    }

    /** 行号形态（MySQL `at line N` → isCharPosition=false）逐字钉住。 */
    @Test
    fun `行号形态逐字为 errorPosition line N`() {
        val pos = ErrorPositionExtractor.extractPosition(
            "You have an error in your SQL syntax; check the manual... near 'FORM t' at line 3",
        )!!
        assertEquals("\nerrorPosition: line 3", McpExplainFormatter.formatErrorPosition(pos))
    }

    /** 无信号 / 多 marker 放弃归属 → 空串（零追加，诚实降级；不得凭空补一个位置）。 */
    @Test
    fun `无位置信号零追加`() {
        assertEquals("", McpExplainFormatter.formatErrorPosition(null))
        val abandoned = ErrorPositionExtractor.extractPosition("Position: 5 ... at line 2")
        assertEquals("多 marker 放弃归属同样零追加", "", McpExplainFormatter.formatErrorPosition(abandoned))
    }

    /** 失败消息整行形态（错误码前缀 + 首行 + 位置行）——agent 侧读到的就是这一串。 */
    @Test
    fun `失败消息整行形态`() {
        val pos = ErrorPositionExtractor.extractPosition("ERROR: x\n  Position: 42")!!
        val message = McpToolErrors.code(
            McpToolErrors.DATASOURCE_UNREACHABLE,
            "EXPLAIN failed on 'PostgreSQL' data source: ERROR: x" +
                McpExplainFormatter.formatErrorPosition(pos),
        )
        assertEquals(
            "[DATASOURCE_UNREACHABLE] EXPLAIN failed on 'PostgreSQL' data source: ERROR: x" +
                "\nerrorPosition: char 42",
            message,
        )
    }

    /** 反射调用的非超时驱动错误：外层 message=null，必须读 SQLException 才能保住错误和位置。 */
    @Test
    fun `反射包装的驱动错误保留消息与位置及原错误码`() {
        val driverError = SQLException("ERROR: column naem does not exist\n  Position: 42")
        val reflected = InvocationTargetException(driverError)
        val message = McpExplainFormatter.formatFailure(reflected, "PostgreSQL", "EXPLAIN SELECT naem FROM some_table_with_a_longer_table_name")
        assertEquals(
            "[DATASOURCE_UNREACHABLE] EXPLAIN failed on 'PostgreSQL' data source: " +
                "ERROR: column naem does not exist\nerrorPosition: char 42",
            message,
        )
    }

    /** T-M19-McpErrorMeta：反射包装不应吞掉驱动状态码和厂商码，首行与位置仍取解包后的错误。 */
    @Test
    fun `反射包装的 SQL 异常保留状态码厂商码和位置`() {
        val driverError = SQLException("ERROR: column naem does not exist\n  Position: 42", "42703", 1049)
        val reflected = InvocationTargetException(driverError)
        assertEquals(
            "[DATASOURCE_UNREACHABLE] EXPLAIN failed on 'PostgreSQL' data source: " +
                "ERROR: column naem does not exist\nsqlState: 42703\nerrorCode: 1049\nerrorPosition: char 42",
            McpExplainFormatter.formatFailure(reflected, "PostgreSQL", "EXPLAIN SELECT naem FROM some_table_with_a_longer_table_name"),
        )
    }

    /** 状态码不存在时不伪造；厂商码独立存在仍必须保留。 */
    @Test
    fun `无 SQLState 时仅追加存在的厂商码`() {
        val driverError = SQLException("syntax error at line 2", null, 1064)
        assertEquals(
            "[DATASOURCE_UNREACHABLE] EXPLAIN failed on 'MySQL' data source: " +
                "syntax error at line 2\nerrorCode: 1064\nerrorPosition: line 2",
            McpExplainFormatter.formatFailure(InvocationTargetException(driverError), "MySQL", "EXPLAIN SELECT 1\nFROM t"),
        )
    }

    /** 超时异常已由执行通道改写为带 timed out 的 SQLException，不得替换原有消息。 */
    @Test
    fun `超时错误文本仍保留超时标记与原错误码`() {
        val timeout = SQLException("query timed out after 5s (cancel requested); original: statement canceled")
        assertEquals(
            "[DATASOURCE_UNREACHABLE] EXPLAIN failed on 'PostgreSQL' data source: " +
                "query timed out after 5s (cancel requested); original: statement canceled",
            McpExplainFormatter.formatFailure(timeout, "PostgreSQL", "EXPLAIN SELECT 1"),
        )
    }

    /**
     * 接线守卫：生产链必须走 [McpExplainFormatter.formatErrorPosition]，且不得再内联该文案。
     * 源码扫描形态照 `ErrorPositionWiringGuardTest`（同仓既有先例）——单测照不出「实现有、接线漏」。
     */
    @Test
    fun `生产链确实走纯函数而非内联文案`() {
        val tool = File(repoRoot(), "src/main/java/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerExplainTool.java")
        assertTrue("未定位到 SqlVisualizerExplainTool（扫描规则失效）", tool.isFile)
        val text = tool.readText()
        assertTrue(
            "SqlVisualizerExplainTool 必须使用测试覆盖的失败格式化入口",
            text.contains("McpExplainFormatter.formatFailure(e, target.dialectName, target.explainSql)"),
        )
        val formatter = File(repoRoot(), "src/main/java/cn/song/mybatisplus/visualizer/mcp/McpExplainFormatter.java")
        assertTrue("失败格式化入口必须保留 errorPosition 文案接线", formatter.readText().contains("formatErrorPosition(pos)"))
        assertFalse(
            "工具内不得再内联 errorPosition 文案（改回内联即绕开本守卫，重新变成空绿）",
            text.contains("errorPosition: "),
        )
    }

    /** 仓库根定位（同 `ErrorPositionWiringGuardTest`：从 user.dir 上溯到含 src/main/kotlin 的目录）。 */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }
}
