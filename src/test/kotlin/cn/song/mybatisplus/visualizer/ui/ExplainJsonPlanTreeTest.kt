package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R33 PG/KingBase JSON 计划树模型构建单测（渲染判别测试）。
 *
 * 形态来源（ExplainIndexAdvisor T-ECH-03 同款真机实证）：PG `EXPLAIN (FORMAT JSON, VERBOSE)`
 * 返回单列 `QUERY PLAN`，值为数组 `[{ "Plan": {…} }]`，节点键 `"Node Type"` / `"Relation Name"` /
 * `"Plan Rows"`，子节点在 `"Plans"` 数组里。本构建器把它折叠成可展示树：
 *  - 计划节点标签 = `Node Type on Relation (rows=N)`（缺关系名/行数按段省略）；
 *  - 非 JSON 文本（截断单元格/意外形态）降级为文本叶节点——诚实降级，不猜不炸。
 */
class ExplainJsonPlanTreeTest {

    @Test
    fun `真机PG形态_嵌套计划树`() {
        val json = """
            [{"Plan": {"Node Type": "Limit", "Plans": [
                {"Node Type": "Seq Scan", "Relation Name": "bench_items", "Plan Rows": 10000}
            ]}}]
        """.trimIndent()
        val roots = ExplainJsonPlanTree.build(listOf(listOf(json)))!!
        assertEquals(1, roots.size)
        val limit = roots[0]
        assertEquals("Limit", limit.label)
        assertEquals(1, limit.children.size)
        assertEquals("Seq Scan on bench_items (rows=10000)", limit.children[0].label)
    }

    @Test
    fun `顶层多节点数组_多根`() {
        val json = """[{"Node Type": "Sort"}, {"Node Type": "Nested Loop"}]"""
        val roots = ExplainJsonPlanTree.build(listOf(listOf(json)))!!
        assertEquals(2, roots.size)
        assertEquals("Sort", roots[0].label)
        assertEquals("Nested Loop", roots[1].label)
    }

    @Test
    fun `标签省略段_无关系名与行数`() {
        val roots = ExplainJsonPlanTree.build(listOf(listOf("""[{"Node Type": "Hash"}]""")))!!
        assertEquals("Hash", roots[0].label)
    }

    @Test
    fun `标签带别名回退_无RelationName时用Alias`() {
        val roots = ExplainJsonPlanTree.build(
            listOf(listOf("""[{"Node Type": "Seq Scan", "Alias": "u", "Plan Rows": 5}]""")),
        )!!
        assertEquals("Seq Scan on u (rows=5)", roots[0].label)
    }

    @Test
    fun `行数取PlanRows_缺省回退ActualRows`() {
        val roots = ExplainJsonPlanTree.build(
            listOf(listOf("""[{"Node Type": "Seq Scan", "Relation Name": "t", "Actual Rows": 42}]""")),
        )!!
        assertEquals("Seq Scan on t (rows=42)", roots[0].label)
    }

    @Test
    fun `标量键值入树`() {
        val roots = ExplainJsonPlanTree.build(
            listOf(listOf("""[{"Node Type": "Limit", "Planning Time": 0.123}]""")),
        )!!
        assertEquals(1, roots[0].children.size)
        assertEquals("Planning Time: 0.123", roots[0].children[0].label)
    }

    @Test
    fun `截断JSON降级文本叶节点_不猜不炸`() {
        // 单元格 2000 字符预算截断（T-ECH-03 实测最常见成因）：解析失败 → 原文文本叶节点
        val truncated = """[{"Plan": {"Node Type": "Seq Scan", "Relation Na"""
        val roots = ExplainJsonPlanTree.build(listOf(listOf(truncated)))!!
        assertEquals(1, roots.size)
        assertEquals(truncated, roots[0].label)
        assertTrue(roots[0].children.isEmpty())
    }

    @Test
    fun `非JSON文本降级文本叶节点`() {
        val roots = ExplainJsonPlanTree.build(listOf(listOf("not a json at all")))!!
        assertEquals(1, roots.size)
        assertEquals("not a json at all", roots[0].label)
    }

    @Test
    fun `空行清单_空树`() {
        assertNull(ExplainJsonPlanTree.build(emptyList()))
    }

    @Test
    fun `空白单元格跳过`() {
        val roots = ExplainJsonPlanTree.build(listOf(listOf("  ", "")))
        assertNull(roots)
    }

    // ---------- R61（V113①）：计划节点稳定标识 flattenPlanNodes ----------

    @Test
    fun `R61_扁平化提取节点_路径与对齐键`() {
        val json = """
            [{"Plan": {"Node Type": "Limit", "Total Cost": 12.5, "Plans": [
                {"Node Type": "Seq Scan", "Relation Name": "t", "Plan Rows": 100, "Filter": "(a > 1)"}
            ]}}]
        """.trimIndent()
        val nodes = ExplainJsonPlanTree.flattenPlanNodes(listOf(listOf(json)))
        assertEquals(2, nodes.size)
        assertEquals("0", nodes[0].path)
        assertEquals("Limit#0", nodes[0].matchKey)
        assertEquals("1", nodes[1].path)
        assertEquals("Seq Scan on t#0", nodes[1].matchKey)
        assertEquals(100L, nodes[1].rows)
        assertEquals(12.5, nodes[0].cost!!, 0.0001)
        assertNull(nodes[1].cost)
        assertEquals(mapOf("Filter" to "(a > 1)"), nodes[1].extras)
    }

    @Test
    fun `R61_同键兄弟节点按出现序消歧`() {
        val json = """
            [{"Plan": {"Node Type": "Nested Loop", "Plans": [
                {"Node Type": "Seq Scan", "Relation Name": "t", "Plan Rows": 1},
                {"Node Type": "Seq Scan", "Relation Name": "t", "Plan Rows": 2},
                {"Node Type": "Seq Scan", "Relation Name": "u", "Plan Rows": 3}
            ]}}]
        """.trimIndent()
        val nodes = ExplainJsonPlanTree.flattenPlanNodes(listOf(listOf(json)))
        assertEquals(
            listOf("Nested Loop#0", "Seq Scan on t#0", "Seq Scan on t#1", "Seq Scan on u#0"),
            nodes.map { it.matchKey },
        )
        // 兄弟增删只挪 path，不影响同键配对（对齐键不随位置漂移）
        assertEquals(listOf("0", "1", "2", "3"), nodes.map { it.path })
    }

    @Test
    fun `R61_解析失败或非计划对象_空清单`() {
        assertTrue(ExplainJsonPlanTree.flattenPlanNodes(listOf(listOf("not json"))).isEmpty())
        assertTrue(ExplainJsonPlanTree.flattenPlanNodes(emptyList()).isEmpty())
        // 非计划对象（无 Node Type）不下钻产出
        assertTrue(ExplainJsonPlanTree.flattenPlanNodes(listOf(listOf("""[{"x": 1}]"""))).isEmpty())
    }

    // ---------- PEV-02：估算偏差徽章（对称倍数 + 豁免带） ----------

    private fun badgeOf(plan: Long, actual: Long?): ExplainJsonPlanTree.EstimationBadge? {
        val actualPart = actual?.let { ", \"Actual Rows\": $it" }.orEmpty()
        return ExplainJsonPlanTree.build(
            listOf(listOf("""[{"Node Type": "Seq Scan", "Relation Name": "t", "Plan Rows": $plan$actualPart}]""")),
        )!![0].estimationBadge
    }

    @Test
    fun `估算徽章_三档分级`() {
        assertEquals(ExplainJsonPlanTree.Tier.WARN, badgeOf(1000, 25000)!!.tier)        // ×25
        assertEquals(ExplainJsonPlanTree.Tier.HIGH, badgeOf(1000, 250000)!!.tier)       // ×250
        assertEquals(ExplainJsonPlanTree.Tier.CRITICAL, badgeOf(1000, 2500000)!!.tier)  // ×2500
    }

    @Test
    fun `估算徽章_豁免带与零行不标`() {
        assertNull("小样本豁免带内不标", badgeOf(8, 8000))
        assertNull("零行无法判倍数不标", badgeOf(100, 0))
        assertNull("无 Actual Rows（非 ANALYZE 档）不标", badgeOf(10000, null))
    }

    @Test
    fun `估算徽章_对称方向同倍数`() {
        // 高估与低估同倍数同档（吸收源 pev2 只看 actual/plan 单向，此为我方改进）
        val under = badgeOf(1000, 25000)!!
        val over = badgeOf(25000, 1000)!!
        assertEquals(under.tier, over.tier)
        assertEquals(under.factor, over.factor, 1e-9)
    }

    // ---------- PEV-06：大计划首屏收拢 ----------

    @Test
    fun `收拢标记_宽父超线默认收拢`() {
        val children = (1..40).joinToString(",") { """{"Node Type": "Seq Scan", "Relation Name": "t$it"}""" }
        // 根节点豁免——宽父包在 Gather 之下（深度 1）才判收拢
        val roots = ExplainJsonPlanTree.build(
            listOf(listOf("""[{"Plan": {"Node Type": "Gather", "Plans": [{"Node Type": "Nested Loop", "Plans": [$children]}]}}]""")),
        )!!
        assertFalse("根节点豁免收拢", roots[0].initiallyCollapsed)
        val wide = roots[0].children[0]
        assertTrue("宽父（40 子 > 12）应标记收拢", wide.initiallyCollapsed)
        assertEquals("收拢规模=子树节点数−自身", 40, wide.collapsedChildCount)
    }

    @Test
    fun `收拢标记_深子树超阈`() {
        // 33 节点子链挂在根下：链根（深度 1）超阈 30 收拢
        fun chain(depth: Int): String =
            if (depth == 0) """{"Node Type": "Seq Scan", "Relation Name": "leaf"}"""
            else """{"Node Type": "Nested Loop", "Plans": [${chain(depth - 1)}]}"""
        val roots = ExplainJsonPlanTree.build(listOf(listOf("""[{"Plan": {"Node Type": "Gather", "Plans": [${chain(32)}]}}]""")))!!
        val deepRoot = roots[0].children[0]
        assertTrue("33 节点子树应被标记收拢", deepRoot.initiallyCollapsed)
        assertFalse("根节点豁免", roots[0].initiallyCollapsed)
    }

    @Test
    fun `收拢标记_全量超规模自适应收紧`() {
        // 两个单元格：2010 节点宽根（拉高全量计数）+ 12 节点深链（默认阈值 30 不收拢、收紧到 10 收拢）
        val leaves = (1..2010).joinToString(",") { """{"Node Type": "Seq Scan"}""" }
        fun chain(depth: Int): String =
            if (depth == 0) """{"Node Type": "Seq Scan", "Relation Name": "leaf"}"""
            else """{"Node Type": "Nested Loop", "Plans": [${chain(depth - 1)}]}"""
        val roots = ExplainJsonPlanTree.build(
            listOf(
                listOf("""[{"Plan": {"Node Type": "Gather", "Plans": [$leaves]}}]"""),
                listOf("""[${chain(11)}]"""),
            ),
        )!!
        assertTrue("全量 2022 > 2000 → 阈值收紧到 10，12 节点链的子层应收拢", roots[1].children[0].initiallyCollapsed)
    }
}
