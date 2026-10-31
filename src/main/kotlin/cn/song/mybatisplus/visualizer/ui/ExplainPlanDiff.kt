package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat

/**
 * R61：EXPLAIN 执行计划**跨次对比**（纯逻辑，无 IDE/UI 类型——裸 JUnit 可直测）。
 *
 * 输入＝同一 SQL 先后两次结构化 EXPLAIN 结果（[McpExplainFormatter.RawResult]，R33 结构化底座），
 * 覆盖结构化档两族（ExplainPanelChannel.STRUCTURED_FORMATS 同口径）：
 *  - **JSON 档**（PG/KingBase/openGauss）：经 [ExplainJsonPlanTree.flattenPlanNodes] 取计划节点，
 *    按**对齐键**（`nodeType on relation#出现序`，V113①）配对，比 rows（Plan Rows/Actual Rows）、
 *    cost（Total Cost）与其余标量键；
 *  - **TABLE 档**（MySQL/MariaDB；DM8 经 T-Fr45DmGuard 改登 TABLE 档——`EXPLAIN FOR` 返回
 *    `COST`/`ROW_NUMS` 等列，故按小写列名取 `cost` 命中、`rows` 缺省走字段级变化；
 *    OceanBase 已于 T-OBExplainFormat 按真机实证改登 TEXT 档——
 *    其 `EXPLAIN` 返回单列 `Query Plan` ASCII 树，不是表格行）：行按 `id`+`table` 列（缺列退行序）配对，
 *    数值列（rows/filtered/cost）给增量，其余列给文本变化。
 *
 * 诚实降级：格式不同族 / 解析不出计划结构 / 任一侧无行 → null（调用方按无可比处理，不硬比）。
 * Oracle DBMS_XPLAN 文本与 SQL Server XML 不建模（V113③ 裁量在册——文本缩进树需独立解析器，
 * 且无真机回归面，成本高于本期收益）。
 */
object ExplainPlanDiff {

    /** 数值度量变化（old→new；泛型保住 Long/Double 各自精度）。 */
    data class MetricDelta<T : Number>(val old: T, val new: T)

    /** 非数值字段/列变化（名，旧值，新值）。 */
    data class FieldChange(val name: String, val old: String, val new: String)

    /** 一个配对节点的变化集（rows/cost 增量 + 字段级变化；全空则不产出）。 */
    data class NodeChange(
        val matchKey: String,
        val label: String,
        val rowsDelta: MetricDelta<Long>?,
        val costDelta: MetricDelta<Double>?,
        val fieldChanges: List<FieldChange>,
    )

    /** 跨次对比结果（null=无可比；isEmpty=两次计划等价）。 */
    data class PlanDiff(
        val changed: List<NodeChange>,
        val added: List<String>,
        val removed: List<String>,
    ) {
        val isEmpty: Boolean get() = changed.isEmpty() && added.isEmpty() && removed.isEmpty()
    }

    /** TABLE 档参与数值增量的列名（小写匹配；缺列自然跳过）。 */
    private val NUMERIC_TABLE_COLUMNS = setOf("rows", "filtered", "cost")

    /**
     * 对比两次结果。任一侧不可结构化（空行/JSON 解析失败/格式不同族/非结构化档）→ null。
     */
    fun diff(previous: McpExplainFormatter.RawResult, current: McpExplainFormatter.RawResult, format: ExplainOutputFormat): PlanDiff? {
        if (previous.rows.isEmpty() || current.rows.isEmpty()) return null
        return when (format) {
            ExplainOutputFormat.JSON -> diffJson(previous, current)
            ExplainOutputFormat.TABLE -> diffTable(previous, current)
            else -> null
        }
    }

    // ---------- JSON 档 ----------

    private fun diffJson(previous: McpExplainFormatter.RawResult, current: McpExplainFormatter.RawResult): PlanDiff? {
        val oldNodes = ExplainJsonPlanTree.flattenPlanNodes(previous.rows)
        val newNodes = ExplainJsonPlanTree.flattenPlanNodes(current.rows)
        if (oldNodes.isEmpty() || newNodes.isEmpty()) return null // 解析不出计划结构：无可比
        val oldByKey = oldNodes.associateBy { it.matchKey }
        val newByKey = newNodes.associateBy { it.matchKey }

        val changed = mutableListOf<NodeChange>()
        for ((key, newNode) in newByKey) {
            val oldNode = oldByKey[key] ?: continue
            val nodeChange = nodeChangeOf(key, oldNode, newNode) ?: continue
            changed += nodeChange
        }
        val added = newByKey.keys.filter { it !in oldByKey }.map { newByKey.getValue(it).label }
        val removed = oldByKey.keys.filter { it !in newByKey }.map { oldByKey.getValue(it).label }
        return PlanDiff(changed, added, removed)
    }

    private fun nodeChangeOf(
        key: String,
        oldNode: ExplainJsonPlanTree.PlanNodeInfo,
        newNode: ExplainJsonPlanTree.PlanNodeInfo,
    ): NodeChange? {
        val rowsDelta = if (oldNode.rows != null && newNode.rows != null && oldNode.rows != newNode.rows) {
            MetricDelta(oldNode.rows, newNode.rows)
        } else null
        val costDelta = if (oldNode.cost != null && newNode.cost != null && oldNode.cost != newNode.cost) {
            MetricDelta(oldNode.cost, newNode.cost)
        } else null
        val fieldChanges = mutableListOf<FieldChange>()
        for ((name, oldValue) in oldNode.extras) {
            val newValue = newNode.extras[name] ?: continue
            if (oldValue != newValue) fieldChanges += FieldChange(name, oldValue, newValue)
        }
        if (rowsDelta == null && costDelta == null && fieldChanges.isEmpty()) return null
        return NodeChange(key, oldNode.label, rowsDelta, costDelta, fieldChanges)
    }

    // ---------- TABLE 档（MySQL 家族） ----------

    /** TABLE 行的对齐视图（matchKey + 列值表）。 */
    private data class TableRow(val matchKey: String, val label: String, val values: List<String>, val headerIndex: Map<String, Int>)

    private fun tableRows(raw: McpExplainFormatter.RawResult): List<TableRow> {
        val headers = raw.headers.mapIndexed { i, h -> normalizeHeader(h) to i }.toMap()
        val idIdx = headers["id"]
        val tableIdx = headers["table"]
        val ordinals = HashMap<String, Int>()
        return raw.rows.mapIndexed { rowIdx, row ->
            val id = idIdx?.let { row.getOrNull(it) }?.trim().orEmpty()
            val table = tableIdx?.let { row.getOrNull(it) }?.trim().orEmpty()
            val base = if (id.isEmpty() && table.isEmpty()) "row" else "id=$id table=$table".trim()
            val ord = ordinals.merge(base, 1, Int::plus)!! - 1
            val label = buildString {
                append(base)
                // 行内自带 rows 展示（供 added/removed 行的上下文）
                headers["rows"]?.let { idx -> row.getOrNull(idx)?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" (rows=").append(it).append(')') } }
            }
            TableRow("$base#$ord", label, row, headers)
        }
    }

    private fun diffTable(previous: McpExplainFormatter.RawResult, current: McpExplainFormatter.RawResult): PlanDiff? {
        val oldRows = tableRows(previous)
        val newRows = tableRows(current)
        if (oldRows.isEmpty() || newRows.isEmpty()) return null
        val oldByKey = oldRows.associateBy { it.matchKey }
        val newByKey = newRows.associateBy { it.matchKey }

        val changed = mutableListOf<NodeChange>()
        for ((key, newRow) in newByKey) {
            val oldRow = oldByKey[key] ?: continue
            val change = rowChangeOf(key, oldRow, newRow) ?: continue
            changed += change
        }
        val added = newByKey.keys.filter { it !in oldByKey }.map { newByKey.getValue(it).label }
        val removed = oldByKey.keys.filter { it !in newByKey }.map { oldByKey.getValue(it).label }
        return PlanDiff(changed, added, removed)
    }

    private fun rowChangeOf(key: String, oldRow: TableRow, newRow: TableRow): NodeChange? {
        var rowsDelta: MetricDelta<Long>? = null
        var costDelta: MetricDelta<Double>? = null
        val fieldChanges = mutableListOf<FieldChange>()
        for ((header, oldIdx) in oldRow.headerIndex) {
            val newIdx = newRow.headerIndex[header] ?: continue
            val oldValue = oldRow.values.getOrNull(oldIdx)?.trim().orEmpty()
            val newValue = newRow.values.getOrNull(newIdx)?.trim().orEmpty()
            if (oldValue == newValue) continue
            when {
                header == "rows" -> {
                    val oldNum = oldValue.toLongOrNull()
                    val newNum = newValue.toLongOrNull()
                    if (oldNum != null && newNum != null) rowsDelta = MetricDelta(oldNum, newNum)
                    else fieldChanges += FieldChange(header, oldValue, newValue)
                }
                header == "cost" -> {
                    val oldNum = oldValue.toDoubleOrNull()
                    val newNum = newValue.toDoubleOrNull()
                    if (oldNum != null && newNum != null) costDelta = MetricDelta(oldNum, newNum)
                    else fieldChanges += FieldChange(header, oldValue, newValue)
                }
                header in NUMERIC_TABLE_COLUMNS -> {
                    val oldNum = oldValue.toDoubleOrNull()
                    val newNum = newValue.toDoubleOrNull()
                    if (oldNum != null && newNum != null) {
                        fieldChanges += FieldChange(header, oldValue, newValue)
                    } else {
                        fieldChanges += FieldChange(header, oldValue, newValue)
                    }
                }
                else -> fieldChanges += FieldChange(header, oldValue, newValue)
            }
        }
        if (rowsDelta == null && costDelta == null && fieldChanges.isEmpty()) return null
        return NodeChange(key, oldRow.label, rowsDelta, costDelta, fieldChanges)
    }

    /** 列名规范化（小写去空白——MySQL EXPLAIN 列名族大小写稳定，防御性归一）。 */
    private fun normalizeHeader(header: String): String = header.trim().lowercase()
}
