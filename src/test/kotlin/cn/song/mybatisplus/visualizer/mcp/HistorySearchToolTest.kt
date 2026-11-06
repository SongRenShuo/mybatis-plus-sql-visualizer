package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sqlv_history_search 工具层测试（R14 T-HistorySearch / T-02，普通 JUnit 零平台 fixture）：
 * 结果格式化（命中计数/截断说明/零命中指引/条目元信息行）、入参异常转译（INVALID_ARGUMENT）、
 * 审计入参摘要形态。检索语义（四维过滤/当日范围/截断）由 [HistorySearchServiceTest] 覆盖。
 */
class HistorySearchToolTest {

    private val tool = SqlVisualizerHistorySearchTool()

    // ------------------------------------------------------------------
    // formatResult：命中形态
    // ------------------------------------------------------------------

    @Test
    fun test零命中_合法结果带指引() {
        val r = HistorySearchService.Result(
            totalHits = 0, returned = 0, truncated = false,
            scannedFiles = 2, scannedEntries = 5, scanTruncated = false,
            scopeStartMs = null, hits = emptyList(),
        )
        val text = tool.formatResult(r)
        assertTrue(text.contains("No history entries matched"))
        assertTrue("应指引 allDates 扩展", text.contains("allDates=true"))
    }

    @Test
    fun test命中输出_计数与条目摘要行() {
        val hit = HistorySearchService.Hit(
            timestamp = 1_759_190_400_000,
            sqlPreview = "SELECT * FROM t_user WHERE id = 1",
            source = "UNKNOWN",
            sourceFile = "src/UserService.java",
            sourceLine = 42,
            executionTimeMs = 12,
            databaseName = "MySQL",
            mapperStatement = null,
            errorText = null,
            historyFile = "sql-history-2026-09-30.sql",
        )
        val r = HistorySearchService.Result(
            totalHits = 7, returned = 1, truncated = true,
            scannedFiles = 3, scannedEntries = 100, scanTruncated = false,
            scopeStartMs = null, hits = listOf(hit),
        )
        val text = tool.formatResult(r)
        assertTrue("应含总命中计数", text.contains("Found 7 match(es)"))
        assertTrue("应含返回计数", text.contains("showing 1 newest-first"))
        assertTrue("超限应有截断说明（D2=A 防上下文爆炸）", text.contains("truncated"))
        assertTrue("应含扫描计数", text.contains("Scanned 3 file(s), 100 entries"))
        assertTrue("应含序号+时间戳行", text.contains("-- #1"))
        assertTrue("应含 SQL 预览", text.contains("SELECT * FROM t_user WHERE id = 1"))
        assertTrue("应含来源位置", text.contains("src/UserService.java:42"))
        assertTrue("应含耗时", text.contains("(12ms)"))
        assertTrue("应含目标库", text.contains("db=MySQL"))
        assertTrue("应含落盘文件", text.contains("in sql-history-2026-09-30.sql"))
    }

    // ------------------------------------------------------------------
    // T-SAF-07（mcp.history_search.masking-not-applied）：输出脱敏
    // ------------------------------------------------------------------

    private fun maskedResult(): HistorySearchService.Result {
        val hit = HistorySearchService.Hit(
            timestamp = 1_759_190_400_000,
            sqlPreview = "SELECT * FROM t_user WHERE password = 'hunter-pw-123'",
            source = "UNKNOWN",
            sourceFile = null,
            sourceLine = 0,
            executionTimeMs = null,
            databaseName = null,
            mapperStatement = null,
            errorText = null,
            historyFile = "sql-history-2026-09-30.sql",
        )
        return HistorySearchService.Result(
            totalHits = 1, returned = 1, truncated = false,
            scannedFiles = 1, scannedEntries = 1, scanTruncated = false,
            scopeStartMs = null, hits = listOf(hit),
        )
    }

    @Test
    fun test脱敏开_敏感字面量打码() {
        // T-SAF-07 先红：改前 formatResult 直接追加 hit.sqlPreview（无任何 maskDisplay 调用），
        // maskOn 下断言「字面量不出现在输出」失败——敏感字面量原样进 agent 上下文。
        // 打码口径与 SqlVisualizerRenderLogsTool（DisplayMasker.maskDisplay）一致。
        val text = tool.formatResult(maskedResult(), maskLiterals = true)
        assertTrue(
            "maskOn 时敏感字面量必须打码：$text",
            !text.contains("hunter-pw-123"),
        )
        assertTrue("打码值应为 '***'：$text", text.contains("'***'"))
    }

    @Test
    fun test脱敏关_字面量原样() {
        val text = tool.formatResult(maskedResult(), maskLiterals = false)
        assertTrue(
            "maskOff 时字面量应原样保留（诊断口径）：$text",
            text.contains("hunter-pw-123"),
        )
    }

    @Test
    fun test脱敏默认_设置不可读兜底关() {
        // 默认路径读设置 mcpMaskEnabled（普通 JUnit 无平台服务 → runCatching 兜底关），
        // 与 renderLogs 同款语义：输出与无脱敏版一致
        val text = tool.formatResult(maskedResult())
        assertTrue(text.contains("hunter-pw-123"))
    }

    @Test
    fun test扫描截断_输出警示() {
        val r = HistorySearchService.Result(
            totalHits = 1, returned = 1, truncated = false,
            scannedFiles = 3, scannedEntries = 5, scanTruncated = true,
            scopeStartMs = null, hits = emptyList(),
        )
        // totalHits=1 但 hits 空是非法组合？不——本测试只验证 scanTruncated 警示分支，hits 留空简化夹具
        val text = tool.formatResult(r)
        assertTrue("扫描截断应警示 agent", text.contains("truncated") || text.contains("scan"))
    }

    // ------------------------------------------------------------------
    // searchHistory：入参异常转译
    // ------------------------------------------------------------------

    @Test
    fun test非法正则_转译INVALID_ARGUMENT预期错误() {
        val error = runCatching {
            tool.searchHistory("/no-such-dir", HistorySearchService.Query(regex = "[unclosed"))
        }.exceptionOrNull()
        assertTrue("应抛 SqlvExpectedToolException", error is SqlvExpectedToolException)
        assertTrue(
            "错误文本应带 INVALID_ARGUMENT 码：${(error as SqlvExpectedToolException).message}",
            error.message!!.contains(McpToolErrors.INVALID_ARGUMENT),
        )
    }

    @Test
    fun test时间范围倒置_转译INVALID_ARGUMENT预期错误() {
        val error = runCatching {
            tool.searchHistory("/no-such-dir", HistorySearchService.Query(fromMs = 100, toMs = 50, allDates = true))
        }.exceptionOrNull()
        assertTrue(error is SqlvExpectedToolException)
        assertTrue((error as SqlvExpectedToolException).message!!.contains(McpToolErrors.INVALID_ARGUMENT))
    }

    @Test
    fun test合法查询_目录不存在零命中不报错() {
        val r = tool.searchHistory("/no-such-dir", HistorySearchService.Query(keyword = "x"))
        assertEquals(0, r.totalHits)
    }

    // ------------------------------------------------------------------
    // 审计入参摘要（T-MAT-04）
    // ------------------------------------------------------------------

    @Test
    fun test审计摘要_含真实过滤条件形态() {
        val s = historySearchArgsSummary("user", "select\\s.+", 100L, 200L, "UserService", allDates = true)
        assertTrue("应含 keyword 段：$s", s.contains("keyword=user"))
        assertTrue("应含 regex 段：$s", s.contains("regex=select\\s.+"))
        assertTrue("应含 fromMs 段：$s", s.contains("fromMs=100"))
        assertTrue("应含 toMs 段：$s", s.contains("toMs=200"))
        assertTrue("应含 sourceFile 段：$s", s.contains("sourceFile=UserService"))
        assertTrue("应含 allDates 段：$s", s.contains("allDates=true"))
        // null 值显式落 "null"（summarize 口径），不留空值歧义
        val s2 = historySearchArgsSummary("", "", null, null, "", allDates = false)
        assertTrue("空过滤条件的 null 段应为字面 null：$s2", s2.contains("fromMs=null"))
    }
}
