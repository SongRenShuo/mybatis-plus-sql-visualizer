package cn.song.mybatisplus.visualizer.ui

import com.google.gson.JsonParser

/**
 * PEV-03（postgres-explain-visualizer/pev2 吸收）：EXPLAIN 计划**四象限时间聚合**（纯逻辑，无 IDE/UI
 * 类型，裸 JUnit 可直测）。
 *
 * 「这次查询的 100ms 都花在哪」：按 Table（Relation Name）/ Index（Index Name）/ Function
 * （Function Name）/ NodeType（Node Type）四个维度聚合 `Actual Total Time`，另算
 * **unaccounted-time**——内节点自身时间＝本节点 − 直接子节点和，占根总时 ≥25% 才报（吸收源同阈值，
 * 防逐级重复计数噪音）；叶子节点不参与（其时间已完整计入四类聚合）。
 *
 * 层级来源：[ExplainJsonPlanTree.collectPlanObjects]（单一来源的塌缩遍历，depth 维度）——
 * 不用 [ExplainJsonPlanTree.flattenPlanNodes]，因其 path 是全平序号（R61 对齐键口径，无层级）。
 * `Actual Total Time` 只在 EXPLAIN ANALYZE 档出现（容忍读取，不改变「本插件不产 ANALYZE」口径）；
 * 无任何时间数据 → 返回 null，调用方整块不占位（诚实降级，不虚标）。
 */
object ExplainPlanStats {

    /** PEV-03：unaccounted-time 上报线（占根总时比例；吸收源 pev2 同为 25%）。 */
    private const val UNACCOUNTED_SHARE_THRESHOLD = 0.25

    data class Entry(val label: String, val totalMs: Double, val nodes: Int)

    data class Unaccounted(val label: String, val ownMs: Double, val share: Double)

    data class Stats(
        val byTable: List<Entry>,
        val byIndex: List<Entry>,
        val byFunction: List<Entry>,
        val byNodeType: List<Entry>,
        val unaccounted: List<Unaccounted>,
        val rootTotalMs: Double?,
    )

    private data class TimedNode(
        val label: String,
        val nodeType: String,
        val relation: String?,
        val indexName: String?,
        val functionName: String?,
        val time: Double,
        val depth: Int,
    )

    /**
     * 聚合。@return null＝无 ANALYZE 时间数据或解析不出计划结构（调用方整块不占位）。
     */
    fun analyze(rows: List<List<String>>): Stats? {
        val nodes = mutableListOf<TimedNode>()
        for (row in rows) {
            for (cell in row) {
                val text = cell.trim()
                if (text.isEmpty()) continue
                val parsed = runCatching { JsonParser.parseString(text) }.getOrNull() ?: continue
                ExplainJsonPlanTree.collectPlanObjects(parsed, 0) { obj, depth ->
                    val nodeType = stringOf(obj, "Node Type") ?: return@collectPlanObjects
                    val time = num(obj, "Actual Total Time") ?: return@collectPlanObjects
                    if (time <= 0.0) return@collectPlanObjects
                    val relation = stringOf(obj, "Relation Name") ?: stringOf(obj, "Alias")
                    nodes += TimedNode(
                        label = buildString {
                            append(nodeType)
                            if (relation != null) append(" on ").append(relation)
                        },
                        nodeType = nodeType,
                        relation = relation,
                        indexName = stringOf(obj, "Index Name"),
                        functionName = stringOf(obj, "Function Name"),
                        time = time,
                        depth = depth,
                    )
                }
            }
        }
        if (nodes.isEmpty()) return null

        // 根总时＝各单元格根层节点之和（多单元格各自一个根）
        val rootTotal = nodes.filter { it.depth == 0 }.sumOf { it.time }
        if (rootTotal <= 0.0) return null

        // unaccounted：内节点自身时间＝本节点 −（相邻层级段内的）直接子节点和；叶子不参与
        val unaccounted = mutableListOf<Unaccounted>()
        for (i in nodes.indices) {
            val d = nodes[i].depth
            var j = i + 1
            var childSum = 0.0
            var hasChildren = false
            while (j < nodes.size && nodes[j].depth > d) {
                if (nodes[j].depth == d + 1) {
                    hasChildren = true
                    childSum += nodes[j].time
                }
                j++
            }
            if (!hasChildren) continue
            val own = nodes[i].time - childSum
            val share = own / rootTotal
            if (own > 0.0 && share >= UNACCOUNTED_SHARE_THRESHOLD) {
                unaccounted += Unaccounted(nodes[i].label, own, share)
            }
        }

        fun aggregate(keyOf: (TimedNode) -> String?): List<Entry> =
            nodes.mapNotNull { keyOf(it)?.let { key -> key to it } }
                .groupBy({ it.first }, { it.second.time })
                .map { (label, times) -> Entry(label, times.sum(), times.size) }
                .sortedByDescending { it.totalMs }

        return Stats(
            byTable = aggregate { it.relation },
            byIndex = aggregate { it.indexName },
            byFunction = aggregate { it.functionName },
            byNodeType = aggregate { it.nodeType },
            unaccounted = unaccounted.sortedByDescending { it.ownMs },
            rootTotalMs = rootTotal,
        )
    }

    private fun stringOf(obj: com.google.gson.JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    private fun num(obj: com.google.gson.JsonObject, key: String): Double? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asDouble }.getOrNull() ?: runCatching { it.asString.trim().toDoubleOrNull() }.getOrNull()
        }
}
