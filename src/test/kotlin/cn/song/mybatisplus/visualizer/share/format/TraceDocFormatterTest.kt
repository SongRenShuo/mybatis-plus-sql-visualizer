package cn.song.mybatisplus.visualizer.share.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-TraceDocExport S1：优化留痕文档组装器（六段式）纯逻辑用例。
 *
 * 覆盖：单份计划 / 双份计划（结构化对比）/ TEXT 档行级对比 / 无计划降级 /
 * 缺耗时与来源 / 围栏与表格转义 / 截断标注。
 */
class TraceDocFormatterTest {

    private fun tablePlan(
        capturedAt: Long = 1_761_800_000_000L,
        rows: List<List<String>> = listOf(listOf("1", "t", "100", "Using index")),
    ) = TracePlanSnapshot(
        capturedAt = capturedAt,
        format = "TABLE",
        headers = listOf("id", "table", "rows", "Extra"),
        rows = rows,
    )

    private fun textPlan(capturedAt: Long, lines: List<String>) = TracePlanSnapshot(
        capturedAt = capturedAt,
        format = "TEXT",
        headers = listOf("PLAN"),
        rows = lines.map { listOf(it) },
    )

    @Test
    fun `six sections always present with sql fence and facts`() {
        val md = TraceDocFormatter.format(
            TraceDocInput(
                sql = "SELECT id FROM t WHERE id = ?",
                dialect = "MySQL",
                source = "UserMapper.java:42",
                capturedAt = 1_761_800_000_000L,
                executionTimeMs = 1234L,
                plans = listOf(tablePlan()),
            ),
        )
        assertTrue(md.startsWith("# 优化留痕"))
        listOf("现象", "基线", "根因", "手段", "前后对比", "结论与回滚").forEach {
            assertTrue("missing section: $it", md.contains(it))
        }
        assertTrue(md.contains("```sql\nSELECT id FROM t WHERE id = ?\n```"))
        assertTrue(md.contains("MySQL"))
        assertTrue(md.contains("UserMapper.java:42"))
        assertTrue(md.contains("1234 ms"))
        // 单份计划：基线段落按表格渲染
        assertTrue(md.contains("| id | table | rows | Extra |"))
        assertTrue(md.contains("| 1 | t | 100 | Using index |"))
        // 只有一份快照时不得编造对比
        assertTrue(md.contains("仅一份计划快照"))
    }

    @Test
    fun `two structured plans produce comparison table with deltas`() {
        val md = TraceDocFormatter.format(
            TraceDocInput(
                sql = "SELECT id FROM t WHERE id = ?",
                dialect = "MySQL",
                executionTimeMs = 20L,
                plans = listOf(
                    // 入参按历史口径「最新在前」，组装器内部按采集时间升序解释
                    tablePlan(capturedAt = 200L, rows = listOf(listOf("1", "t", "10", "Using where"))),
                    tablePlan(capturedAt = 100L, rows = listOf(listOf("1", "t", "100", "Using index"))),
                ),
            ),
        )
        assertTrue(md.contains("| 节点 | rows 变化 | cost 变化 | 字段变化 |"))
        assertTrue(md.contains("100 → 10 (-90)"))
        // 字段名由 ExplainPlanDiff 归一为小写（既有口径），这里只钉值变化
        assertTrue(md.contains("Using index → Using where"))
    }

    @Test
    fun `two text plans fall back to honest line diff`() {
        val md = TraceDocFormatter.format(
            TraceDocInput(
                sql = "SELECT id FROM t",
                dialect = "DM（达梦）",
                plans = listOf(
                    textPlan(200L, listOf("PLAN", "NSET2", "  INDEX SCAN IDX_T", "  cost 10")),
                    textPlan(100L, listOf("PLAN", "NSET2", "  TABLE SCAN T", "  cost 100")),
                ),
            ),
        )
        assertTrue(md.contains("文本级"))
        assertTrue(md.contains("删除"))
        assertTrue(md.contains("新增"))
        assertTrue(md.contains("TABLE SCAN T"))
        assertTrue(md.contains("INDEX SCAN IDX_T"))
        // TEXT 档不做代价对比，不得出现结构化增量表头
        assertFalse(md.contains("| 节点 | rows 变化 |"))
    }

    @Test
    fun `no plan degrades explicitly without inventing content`() {
        val md = TraceDocFormatter.format(
            TraceDocInput(
                sql = "SELECT 1 FROM dual",
                dialect = "Oracle",
                executionTimeMs = null,
                plans = emptyList(),
            ),
        )
        // 基线段落必须自己明写「无计划快照」，不能只靠第 5 段的降级句
        assertTrue(md.contains("无计划快照：本次留痕没有可用的执行计划"))
        assertFalse(md.contains("| id | table |"))
        assertFalse(md.contains("cost"))
        assertTrue(md.contains("无法进行前后对比"))
    }

    @Test
    fun `missing duration and source are marked not captured`() {
        val md = TraceDocFormatter.format(
            TraceDocInput(sql = "SELECT 1", dialect = null, source = null, executionTimeMs = null),
        )
        assertTrue(md.contains("执行耗时：未采集"))
        assertTrue(md.contains("来源：未采集"))
        assertTrue(md.contains("方言：未知"))
        assertTrue(md.contains("捕获时间：未采集"))
    }

    @Test
    fun `sql with triple backticks gets a longer fence`() {
        val sql = "SELECT 1 -- ```\nFROM dual"
        val md = TraceDocFormatter.format(TraceDocInput(sql = sql, dialect = "MySQL"))
        assertTrue(md.contains("````sql\n"))
        assertTrue(md.contains("\n````"))
        assertTrue(md.contains(sql))
    }

    @Test
    fun `table cells escape pipes and newlines`() {
        val md = TraceDocFormatter.format(
            TraceDocInput(
                sql = "SELECT 1",
                plans = listOf(
                    tablePlan(rows = listOf(listOf("1", "t", "100", "a|b\nc"))),
                ),
            ),
        )
        assertTrue(md.contains("a\\|b<br>c"))
    }

    @Test
    fun `truncated plan is annotated`() {
        val md = TraceDocFormatter.format(
            TraceDocInput(
                sql = "SELECT 1",
                plans = listOf(tablePlan().copy(truncated = true)),
            ),
        )
        assertTrue(md.contains("截断"))
    }

    @Test
    fun `empty plan rows are labelled empty not fabricated`() {
        val md = TraceDocFormatter.format(
            TraceDocInput(
                sql = "SELECT 1",
                plans = listOf(TracePlanSnapshot(capturedAt = 1L, format = "TEXT", rows = emptyList())),
            ),
        )
        assertTrue(md.contains("空计划快照"))
    }

    @Test
    fun `default file name carries timestamp and md extension`() {
        val name = TraceDocFormatter.defaultFileName(1_761_800_000_000L)
        assertTrue(name.matches(Regex("trace-doc-\\d{8}-\\d{6}\\.md")))
    }

    @Test
    fun `narrative fields are emitted verbatim`() {
        val md = TraceDocFormatter.format(
            TraceDocInput(
                sql = "SELECT 1",
                symptom = "列表页加载 8 秒",
                rootCause = "缺少 (tenant_id, created_at) 联合索引",
                action = "新增联合索引",
                conclusion = "P99 降至 120ms",
                rollback = "DROP INDEX idx_t_tenant_created",
            ),
        )
        assertTrue(md.contains("列表页加载 8 秒"))
        assertTrue(md.contains("缺少 (tenant_id, created_at) 联合索引"))
        assertTrue(md.contains("新增联合索引"))
        assertTrue(md.contains("P99 降至 120ms"))
        assertTrue(md.contains("DROP INDEX idx_t_tenant_created"))
        // 未填写的基线段落保持「待补充」，不编造
        assertTrue(md.contains("（待补充）"))
        assertEquals(1, Regex("（待补充）").findAll(md).count())
    }
}
