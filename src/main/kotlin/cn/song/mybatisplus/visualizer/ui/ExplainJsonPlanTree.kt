package cn.song.mybatisplus.visualizer.ui

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * R33：PG/KingBase JSON 计划树 → 可折叠树模型的**纯逻辑构建器**（无 IDE/UI 类型，裸 JUnit 可直测）。
 *
 * 输入形态（[cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor] T-ECH-03 同款真机实证）：
 * PG `EXPLAIN (FORMAT JSON, VERBOSE)` 返回单列 `QUERY PLAN`，值为数组
 * `[{ "Plan": { "Node Type": …, "Relation Name": …, "Plan Rows": …, "Plans": [ …子计划 ] } }]`。
 *
 * 折叠规则：
 *  - 带 `"Node Type"` 的对象 = 计划节点，标签 `Node Type on Relation (rows=N)`——关系名缺省退
 *    `"Alias"`（PG 对无表节点不出 Relation Name），两段各自缺省按段省略（不虚标）；
 *  - 行数 `"Plan Rows"` 缺省退 `"Actual Rows"`（EXPLAIN ANALYZE 才出现，容忍读取不改变口径）；
 *  - 标签键（Node Type/Relation Name/Alias/Plan Rows/Actual Rows）收进标签，不重复出现在子节点；
 *  - 单键且值为对象/数组的包装对象（顶层 `{"Plan": …}` 正是此形态）**塌缩**为其值节点——
 *    用户看到的根就是 `Limit`/`Seq Scan`，不是一层无信息量的 `Plan` 包装；
 *  - 标量键值入树为 `key: value` 叶节点；对象/数组键以键名为节点标签继续下钻；
 *  - **诚实降级**：单元格解析不出 JSON（2000 字符单元格预算截断是 T-ECH-03 实测最常见成因）→
 *    原文降级为文本叶节点，不猜不炸。
 */
object ExplainJsonPlanTree {

    // ---------- PEV-02/06（postgres-explain-visualizer/pev2 吸收）：估算偏差徽章与大计划首屏收拢 ----------

    /** PEV-02：估算偏差判定的小样本豁免线——Plan Rows 低于此值不判偏差（小表偏差是常态噪音；吸收源 pev2 无忽略带，此为我方改进）。 */
    const val ESTIMATION_EXEMPTION_ROWS = 10L

    /** PEV-06：首屏收拢阈值——子树节点数超过则默认收拢（UI 层执行收拢，展开不受限）。 */
    private const val COLLAPSE_SUBTREE_THRESHOLD = 30

    /** PEV-06：全量节点数超此值时收紧收拢阈值（自适应预算——大计划首屏保持轻量）。 */
    private const val ADAPTIVE_COLLAPSE_TOTAL = 2000

    private const val ADAPTIVE_COLLAPSE_THRESHOLD = 10

    /** PEV-06：宽父收拢线——单父直接子节点数超过也收拢（子树规模判据覆盖不了的「一父多子」宽形态）。 */
    private const val COLLAPSE_WIDE_CHILDREN = 12

    /** PEV-02：估算偏差倍数分档（吸收源 pev2 阈值 >10/100/1000；色阶由 UI 层按 LAF namedColor 给，纯逻辑层不带颜色）。 */
    enum class Tier { WARN, HIGH, CRITICAL }

    /** PEV-02：估算偏差徽章（actual 对 plan 的对称倍数；null=无 ANALYZE 数据或在豁免带内——不虚标）。 */
    data class EstimationBadge(val factor: Double, val tier: Tier)

    /** 可展示树节点（纯数据；UI 层据此建 Swing TreeModel）。 */
    data class Node(
        val label: String,
        val children: List<Node> = emptyList(),
        /** PEV-02：估算偏差徽章——仅当 Plan Rows 与 Actual Rows 同时在场且超出豁免带（EXPLAIN ANALYZE 档）。 */
        val estimationBadge: EstimationBadge? = null,
        /** PEV-06：子树规模超阈，UI 层默认收拢（用户展开不受限；规模见 [collapsedChildCount]）。 */
        val initiallyCollapsed: Boolean = false,
        /** PEV-06：被收拢子树的节点数（不含自身，供 UI 层规模提示）。 */
        val collapsedChildCount: Int = 0,
    )

    /** 计划标签键（收进标签，不重复出现在子节点）。 */
    private val LABEL_KEYS = setOf("Node Type", "Relation Name", "Alias", "Plan Rows", "Actual Rows")

    /**
     * 构建树根清单。
     *
     * @return 每个可解析单元格贡献一组根、每个解析失败单元格贡献一个文本叶节点；
     *   全部单元格空白 → null（调用方按空态处理，不建空树）
     */
    fun build(rows: List<List<String>>): List<Node>? {
        val out = mutableListOf<Node>()
        var sawAny = false
        for (row in rows) {
            for (cell in row) {
                val text = cell.trim()
                if (text.isEmpty()) continue
                sawAny = true
                val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
                if (parsed != null && (parsed.isJsonObject || parsed.isJsonArray)) {
                    out += fromElement(parsed)
                } else {
                    out += Node(text)
                }
            }
        }
        if (!sawAny) return null
        // PEV-06：全量节点数决定收拢阈值（自适应预算），根节点豁免——首屏永远看得到顶层节点
        val total = out.sumOf { subtreeSize(it) }
        val threshold = if (total > ADAPTIVE_COLLAPSE_TOTAL) ADAPTIVE_COLLAPSE_THRESHOLD else COLLAPSE_SUBTREE_THRESHOLD
        return out.map { markCollapsed(it, depth = 0, threshold) }
    }

    /** 子树节点总数（含自身）。 */
    private fun subtreeSize(node: Node): Int = 1 + node.children.sumOf { subtreeSize(it) }

    /** PEV-06：深度 >0 且子树超阈的节点打收拢标记（嵌套标记合法——逐层展开语义）。 */
    private fun markCollapsed(node: Node, depth: Int, threshold: Int): Node {
        val size = subtreeSize(node)
        val children = node.children.map { markCollapsed(it, depth + 1, threshold) }
        val collapsed = depth > 0 && (size > threshold || node.children.size > COLLAPSE_WIDE_CHILDREN)
        return if (collapsed || node.initiallyCollapsed) {
            node.copy(children = children, initiallyCollapsed = collapsed, collapsedChildCount = if (collapsed) size - 1 else 0)
        } else {
            if (children == node.children) node else node.copy(children = children)
        }
    }

    /**
     * PEV-02：估算偏差徽章。factor＝max(actual, plan)/min(actual, plan)（**对称倍数**——低估与
     * 高估都算偏差；吸收源 pev2 只看 actual/plan 单向，此为我方改进）。豁免带：Plan Rows 小于
     * [ESTIMATION_EXEMPTION_ROWS]（小表偏差是常态）、任一行为 0/负（无法判倍数）一律不标。
     */
    private fun estimationBadgeOf(obj: com.google.gson.JsonObject): EstimationBadge? {
        val plan = longOf(obj, "Plan Rows") ?: return null
        val actual = longOf(obj, "Actual Rows") ?: return null
        if (plan < ESTIMATION_EXEMPTION_ROWS) return null
        val p = plan.toDouble()
        val a = actual.toDouble()
        if (p <= 0.0 || a <= 0.0) return null
        val factor = maxOf(a, p) / minOf(a, p)
        val tier = when {
            factor > 1000.0 -> Tier.CRITICAL
            factor > 100.0 -> Tier.HIGH
            factor > 10.0 -> Tier.WARN
            else -> return null
        }
        return EstimationBadge(factor, tier)
    }

    private fun fromElement(el: JsonElement): List<Node> = when {
        el.isJsonArray -> el.asJsonArray.flatMap { fromElement(it) }
        el.isJsonObject -> listOf(nodeFromObject(el.asJsonObject))
        else -> listOf(Node(el.asString))
    }

    private fun nodeFromObject(obj: com.google.gson.JsonObject): Node {
        planLabel(obj)?.let { label ->
            val children = obj.entrySet()
                .filter { it.key !in LABEL_KEYS }
                .flatMap { (key, value) ->
                    // 「Plans」类子计划数组：元素节点直接摊平进父节点——计划树用户关心的是
                    // 子计划节点本身，中间再垫一层 "Plans" 键包装只会多一次展开（真机 PG 形态）
                    if (value.isJsonArray && value.asJsonArray.size() > 0 && value.asJsonArray.all { it.isJsonObject }) {
                        fromElement(value)
                    } else {
                        listOf(entryNode(key, value))
                    }
                }
            return Node(label, children, estimationBadge = estimationBadgeOf(obj))
        }
        // 单键包装对象（顶层 {"Plan": …} 正是此形态）塌缩为其值节点
        if (obj.size() == 1) {
            val entry = obj.entrySet().first()
            val value = entry.value
            if (value.isJsonObject || value.isJsonArray) {
                val inner = fromElement(value)
                return if (inner.size == 1) inner[0] else Node(entry.key, inner)
            }
            return Node("${entry.key}: ${primitiveText(value)}")
        }
        return Node("{plan}", obj.entrySet().map { entryNode(it.key, it.value) })
    }

    private fun entryNode(key: String, value: JsonElement): Node = when {
        value.isJsonObject || value.isJsonArray -> Node(key, fromElement(value))
        else -> Node("$key: ${primitiveText(value)}")
    }

    /**
     * 计划节点标签（null = 非计划对象）：`Node Type on Relation (rows=N)`，两段各自缺省省略——
     * 关系名缺省退 Alias（不虚报表名，ExplainIndexAdvisor 同款口径）；行数 Plan Rows 缺省退 Actual Rows。
     */
    private fun planLabel(obj: com.google.gson.JsonObject): String? {
        val nodeType = stringOf(obj, "Node Type") ?: return null
        val relation = stringOf(obj, "Relation Name") ?: stringOf(obj, "Alias")
        val rows = longOf(obj, "Plan Rows") ?: longOf(obj, "Actual Rows")
        return buildString {
            append(nodeType)
            if (relation != null) append(" on ").append(relation)
            if (rows != null) append(" (rows=").append(rows).append(')')
        }
    }

    private fun stringOf(obj: com.google.gson.JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    private fun longOf(obj: com.google.gson.JsonObject, key: String): Long? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asLong }.getOrNull() ?: runCatching { it.asString.trim().toLongOrNull() }.getOrNull()
        }

    private fun primitiveText(el: JsonElement): String = runCatching { el.asString }.getOrDefault(el.toString())

    // ---------- R61（V113①）：计划节点稳定标识与结构化提取 ----------

    /**
     * R61（V113①）：JSON 计划节点的**结构化视图**（跨次对比的对齐底座）。
     *
     * 节点稳定标识双档：
     *  - [PlanNodeInfo.path]＝结构位置路径（`"0"`、`"0.2"`——根序.子序，同次结果内唯一；
     *  - [PlanNodeInfo.matchKey]＝对齐键 `nodeType on relation#出现序`（同键多次出现按前序
     *    遍历出现序消歧）——跨次对比按本键对齐，兄弟增删只挪 path 不影响同键节点配对。
     */
    data class PlanNodeInfo(
        /** 结构位置路径（根序.子序.……，同次结果内稳定唯一）。 */
        val path: String,
        /** 对齐键（nodeType + relation + 出现序——跨次对比的节点身份）。 */
        val matchKey: String,
        /** 人读标签（与展示树 planLabel 同口径）。 */
        val label: String,
        val nodeType: String,
        val relation: String?,
        /** Plan Rows，缺省退 Actual Rows（EXPLAIN ANALYZE）。 */
        val rows: Long?,
        /** Total Cost（PG JSON 档；缺 null 不虚标）。 */
        val cost: Double?,
        /** 其余标量键值（diff 细节比对面；对象/数组值跳过——标量才可比）。 */
        val extras: Map<String, String>,
    )

    /**
     * 把 EXPLAIN 结果行扁平化为计划节点清单（前序遍历；解析失败/非计划对象 → 空清单——
     * 诚实降级：没有可对齐的结构就没有对比，调用方按无可比处理）。
     */
    fun flattenPlanNodes(rows: List<List<String>>): List<PlanNodeInfo> {
        val out = mutableListOf<PlanNodeInfo>()
        val ordinals = HashMap<String, Int>()
        var rootOrdinal = 0
        for (row in rows) {
            for (cell in row) {
                val text = cell.trim()
                if (text.isEmpty()) continue
                val parsed = runCatching { JsonParser.parseString(text) }.getOrNull() ?: continue
                collectPlanObjects(parsed, 0) { obj, _ ->
                    val nodeType = stringOf(obj, "Node Type") ?: return@collectPlanObjects
                    val relation = stringOf(obj, "Relation Name") ?: stringOf(obj, "Alias")
                    val base = buildString {
                        append(nodeType)
                        if (relation != null) append(" on ").append(relation)
                    }
                    val ord = ordinals.merge(base, 1, Int::plus)!! - 1
                    val rowsVal = longOf(obj, "Plan Rows") ?: longOf(obj, "Actual Rows")
                    val costVal = doubleOf(obj, "Total Cost")
                    val extras = LinkedHashMap<String, String>()
                    obj.entrySet().forEach { (k, v) ->
                        if (k !in LABEL_KEYS && k != "Plans" && k != "Total Cost" && v.isJsonPrimitive) {
                            extras[k] = primitiveText(v)
                        }
                    }
                    out += PlanNodeInfo(
                        path = "$rootOrdinal",
                        matchKey = "$base#$ord",
                        label = buildString {
                            append(base)
                            if (rowsVal != null) append(" (rows=").append(rowsVal).append(')')
                        },
                        nodeType = nodeType,
                        relation = relation,
                        rows = rowsVal,
                        cost = costVal,
                        extras = extras,
                    )
                    rootOrdinal++
                }
            }
        }
        return out
    }

    /**
     * 递归收集计划对象（顶层包装塌缩语义与展示树一致：数组摊平、`{"Plan": …}` 单键包装下钻）。
     *
     * PEV-03 起 internal 并带 [depth]（根层 0，`Plans` 子计划逐层 +1）——时间统计的层级聚合
     * 与 unaccounted 计算共用本遍历（单一来源，禁复制第二套塌缩语义）。R61 的
     * [flattenPlanNodes] 消费方忽略 depth，行为不变。
     *
     * UI-001：EXPLAIN **ANALYZE** 档顶层是双键 `{"Plan": {…}, "Planning Time": 0.xxx}`
     * （还可能带 "Triggers"/"JIT" 等键）——单键塌缩不命中曾让统计遍历一无所获。放宽：非单键
     * 且自身非计划节点的对象逐 entry 下钻——值为含 `"Node Type"` 的对象、或键名为
     * `"Plan"`/`"Plans"` 且值为对象/数组。depth 传递语义不变：包装层不加 depth，只有真正
     * 计划节点 sink 后其 `"Plans"` 子计划才 +1。
     */
    internal fun collectPlanObjects(el: JsonElement, depth: Int, sink: (com.google.gson.JsonObject, Int) -> Unit) {
        when {
            el.isJsonArray -> el.asJsonArray.forEach { collectPlanObjects(it, depth, sink) }
            el.isJsonObject -> {
                val obj = el.asJsonObject
                if (obj.get("Node Type") != null) {
                    sink(obj, depth)
                    obj.get("Plans")?.let { plans -> collectPlanObjects(plans, depth + 1, sink) }
                } else if (obj.size() == 1) {
                    collectPlanObjects(obj.entrySet().first().value, depth, sink)
                } else {
                    obj.entrySet().forEach { (key, value) ->
                        val drill = (value.isJsonObject && value.asJsonObject.get("Node Type") != null) ||
                            ((key == "Plan" || key == "Plans") && (value.isJsonObject || value.isJsonArray))
                        if (drill) collectPlanObjects(value, depth, sink)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun doubleOf(obj: com.google.gson.JsonObject, key: String): Double? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asDouble }.getOrNull() ?: runCatching { it.asString.trim().toDoubleOrNull() }.getOrNull()
        }
}
