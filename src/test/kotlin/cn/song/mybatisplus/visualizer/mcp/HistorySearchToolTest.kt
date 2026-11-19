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
        // K2J 互操作：Result 为 Java record，具名实参改 8 参位置形态。
        val r = HistorySearchService.Result(
            0, 0, false,
            2, 5, false,
            null, emptyList(),
        )
        val text = tool.formatResult(r)
        assertTrue(text.contains("No history entries matched"))
        assertTrue("应指引 allDates 扩展", text.contains("allDates=true"))
    }

    @Test
    fun test命中输出_计数与条目摘要行() {
        val hit = HistorySearchService.Hit(
            1_759_190_400_000L,
            "SELECT * FROM t_user WHERE id = 1",
            "UNKNOWN",
            "src/UserService.java",
            42,
            12L,
            "MySQL",
            null,
            null,
            "sql-history-2026-09-30.sql",
        )
        val r = HistorySearchService.Result(
            7, 1, true,
            3, 100, false,
            null, listOf(hit),
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

    @Test
    fun test扫描截断_输出警示() {
        val r = HistorySearchService.Result(
            1, 1, false,
            3, 5, true,
            null, emptyList(),
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
            tool.searchHistory("/no-such-dir", HistorySearchService.Query(null, "[unclosed", null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS))
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
            tool.searchHistory("/no-such-dir", HistorySearchService.Query(null, null, 100L, 50L, null, true, HistorySearchService.DEFAULT_MAX_RESULTS))
        }.exceptionOrNull()
        assertTrue(error is SqlvExpectedToolException)
        assertTrue((error as SqlvExpectedToolException).message!!.contains(McpToolErrors.INVALID_ARGUMENT))
    }

    @Test
    fun test合法查询_目录不存在零命中不报错() {
        val r = tool.searchHistory("/no-such-dir", HistorySearchService.Query("x", null, null, null, null, false, HistorySearchService.DEFAULT_MAX_RESULTS))
        assertEquals(0, r.totalHits)
    }

    // ------------------------------------------------------------------
    // 审计入参摘要（T-MAT-04）
    // ------------------------------------------------------------------

    @Test
    fun test审计摘要_含真实过滤条件形态() {
        val s = SqlVisualizerHistorySearchToolKt.historySearchArgsSummary("user", "select\\s.+", 100L, 200L, "UserService", true)
        assertTrue("应含 keyword 段：$s", s.contains("keyword=user"))
        assertTrue("应含 regex 段：$s", s.contains("regex=select\\s.+"))
        assertTrue("应含 fromMs 段：$s", s.contains("fromMs=100"))
        assertTrue("应含 toMs 段：$s", s.contains("toMs=200"))
        assertTrue("应含 sourceFile 段：$s", s.contains("sourceFile=UserService"))
        assertTrue("应含 allDates 段：$s", s.contains("allDates=true"))
        // null 值显式落 "null"（summarize 口径），不留空值歧义
        val s2 = SqlVisualizerHistorySearchToolKt.historySearchArgsSummary("", "", null, null, "", false)
        assertTrue("空过滤条件的 null 段应为字面 null：$s2", s2.contains("fromMs=null"))
    }

    // ------------------------------------------------------------------
    // D219 输出分档：detail=false 紧凑清单（无 SQL 预览正文），null/true 历史契约不变
    // ------------------------------------------------------------------

    /** k2j 适配：Result/Hit 为 Java record——具名实参改位置形态（与上方既有构造同款）。 */
    private fun oneHitResult(): HistorySearchService.Result = HistorySearchService.Result(
        1, 1, false,
        1, 1, false,
        null,
        listOf(
            HistorySearchService.Hit(
                1_759_190_400_000L,
                "SELECT * FROM t_user WHERE id = 1",
                "UNKNOWN",
                "src/UserService.java",
                42,
                12L,
                "MySQL",
                "UserMapper#selectById",
                null,
                "sql-history-2026-09-30.sql",
            ),
        ),
    )

    @Test
    fun test分档_默认档含SQL预览() {
        val text = tool.formatResult(oneHitResult())
        assertTrue(text.contains("SELECT * FROM t_user WHERE id = 1"))
    }

    @Test
    fun test分档_显式明细档含SQL预览() {
        val text = tool.formatResult(oneHitResult(), true)
        assertTrue(text.contains("SELECT * FROM t_user WHERE id = 1"))
    }

    @Test
    fun test分档_紧凑档无SQL预览但有头字段() {
        val text = tool.formatResult(oneHitResult(), false)
        assertTrue("紧凑档应含头字段 mapper", text.contains("mapper=UserMapper#selectById"))
        assertTrue("紧凑档应含时间戳", text.contains("2026-09-30"))
        assertTrue("紧凑档不应输出 SQL 预览正文", !text.contains("SELECT * FROM t_user WHERE id = 1"))
        assertTrue("紧凑档应带 compact 说明", text.contains("compact listing"))
        // 反向对照：同一条目默认档必含预览（摘掉 compact 分支此断言必红）
        assertTrue(tool.formatResult(oneHitResult()).contains("SELECT * FROM t_user WHERE id = 1"))
    }
}
