package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PEV-03 四象限时间聚合单测（渲染判别测试）。
 *
 * 形态：PG EXPLAIN ANALYZE JSON——`Actual Total Time` 只在 ANALYZE 档出现；路径 "0" 为根、
 * "0.x" 为直接子节点（[ExplainJsonPlanTree.flattenPlanNodes] 前序遍历语义）。
 */
class ExplainPlanStatsTest {

    private fun rowsOf(json: String) = listOf(listOf(json))

    @Test
    fun `无ANALYZE时间数据_返回null`() {
        assertNull(
            ExplainPlanStats.analyze(
                rowsOf("""[{"Plan": {"Node Type": "Seq Scan", "Relation Name": "t", "Plan Rows": 100}}]"""),
            ),
        )
        assertNull(ExplainPlanStats.analyze(emptyList()))
    }

    @Test
    fun `四象限聚合与降序`() {
        val json = """
            [{"Plan": {"Node Type": "Limit", "Actual Total Time": 100.0, "Plans": [
                {"Node Type": "Seq Scan", "Relation Name": "t1", "Actual Total Time": 10.0},
                {"Node Type": "Index Scan", "Relation Name": "t2", "Index Name": "idx_a", "Actual Total Time": 5.0},
                {"Node Type": "Function Scan", "Function Name": "fn", "Actual Total Time": 2.5}
            ]}}]
        """.trimIndent()
        val stats = ExplainPlanStats.analyze(rowsOf(json))!!
        assertEquals(listOf("Limit", "Seq Scan", "Index Scan", "Function Scan"), stats.byNodeType.map { it.label })
        assertEquals(listOf("t1", "t2"), stats.byTable.map { it.label })
        assertEquals(listOf("idx_a"), stats.byIndex.map { it.label })
        assertEquals(listOf("fn"), stats.byFunction.map { it.label })
        assertEquals(1, stats.byTable[0].nodes)
        assertEquals(100.0, stats.rootTotalMs!!, 1e-9)
    }

    @Test
    fun `unaccounted_仅内节点且超25才报`() {
        val json = """
            [{"Plan": {"Node Type": "Nested Loop", "Actual Total Time": 100.0, "Plans": [
                {"Node Type": "Seq Scan", "Relation Name": "t1", "Actual Total Time": 80.0},
                {"Node Type": "Sort", "Actual Total Time": 3.0}
            ]}}]
        """.trimIndent()
        val stats = ExplainPlanStats.analyze(rowsOf(json))!!
        // 根自身时间 100−83=17 → 17% < 25% 不报；叶子时间已计入聚合，不参与 unaccounted
        assertTrue(stats.unaccounted.isEmpty())

        val json2 = """
            [{"Plan": {"Node Type": "Nested Loop", "Actual Total Time": 100.0, "Plans": [
                {"Node Type": "Seq Scan", "Relation Name": "t1", "Actual Total Time": 10.0}
            ]}}]
        """.trimIndent()
        val stats2 = ExplainPlanStats.analyze(rowsOf(json2))!!
        assertEquals(1, stats2.unaccounted.size)
        assertEquals(90.0, stats2.unaccounted[0].ownMs, 1e-9)
        assertEquals(0.90, stats2.unaccounted[0].share, 1e-9)
    }

    @Test
    fun `多单元格_根总时按各单元格根求和`() {
        val json1 = """[{"Plan": {"Node Type": "Limit", "Actual Total Time": 100.0}}]"""
        val json2 = """[{"Plan": {"Node Type": "Sort", "Actual Total Time": 50.0}}]"""
        val stats = ExplainPlanStats.analyze(listOf(listOf(json1), listOf(json2)))!!
        assertEquals(150.0, stats.rootTotalMs!!, 1e-9)
        // 两个互不相干的叶子根：无内节点 → 无 unaccounted
        assertTrue(stats.unaccounted.isEmpty())
    }

    // ---------- UI-001：EXPLAIN ANALYZE 档顶层双键形态（真机 PG16 实证） ----------

    @Test
    fun `ANALYZE档顶层双键_统计条恢复`() {
        // 真机形态：EXPLAIN (FORMAT JSON, ANALYZE) 顶层是 {"Plan": …, "Planning Time": 0.xxx}
        // 双键——单键塌缩不命中曾整块丢失统计条（UI-001）
        val json = """
            [{"Plan": {"Node Type": "Aggregate", "Plan Rows": 1, "Actual Rows": 1, "Actual Total Time": 6.078, "Plans": [
                {"Node Type": "Seq Scan", "Relation Name": "pev_big", "Plan Rows": 10000, "Actual Rows": 12000, "Actual Total Time": 230.0}
            ]}, "Planning Time": 0.3}]
        """.trimIndent()
        val stats = ExplainPlanStats.analyze(rowsOf(json))!!
        // byNodeType 按总耗时降序：Seq Scan 230.0 > Aggregate 6.078
        assertEquals(listOf("Seq Scan", "Aggregate"), stats.byNodeType.map { it.label })
        assertEquals(listOf("pev_big"), stats.byTable.map { it.label })
        // 根总时＝depth0 节点之和：只有 Aggregate（包装层不加 depth，真计划节点才在根层）
        assertEquals(6.078, stats.rootTotalMs!!, 1e-9)
        // 全节点时间和（含子层 Seq Scan）：6.078 + 230.0
        assertEquals(236.078, stats.byNodeType.sumOf { it.totalMs }, 1e-9)
        // Aggregate 自身时间 6.078 − 230.0 < 0 → 不报（unaccounted 空）
        assertTrue(stats.unaccounted.isEmpty())
    }

    @Test
    fun `顶层含Triggers键_容忍`() {
        // ANALYZE 档可能还带 "Triggers"/"JIT" 等顶层键——不下钻不误收（键名非 Plan/Plans 且
        // 数组元素无 "Node Type"）
        val json = """
            [{"Plan": {"Node Type": "Nested Loop", "Actual Total Time": 100.0, "Plans": [
                {"Node Type": "Seq Scan", "Relation Name": "t1", "Actual Total Time": 10.0}
            ]}, "Triggers": [{"Trigger Name": "trg", "Relation": "t2", "Time": 1.5}]}]
        """.trimIndent()
        val stats = ExplainPlanStats.analyze(rowsOf(json))!!
        assertEquals(listOf("Nested Loop", "Seq Scan"), stats.byNodeType.map { it.label })
        assertTrue("Triggers 数据不得混入节点聚合", stats.byNodeType.none { it.label.contains("trg") })
        assertEquals(100.0, stats.rootTotalMs!!, 1e-9)
        // 内节点自身时间 100 − 10 = 90 → 90% ≥ 25% 照常上报（主链路未受额外键干扰）
        assertEquals(1, stats.unaccounted.size)
        assertEquals(90.0, stats.unaccounted[0].ownMs, 1e-9)
    }
}
