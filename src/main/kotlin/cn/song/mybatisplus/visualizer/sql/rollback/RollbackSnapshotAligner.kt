package cn.song.mybatisplus.visualizer.sql.rollback

/**
 * T-RollbackSnapshotPreExec P3：改前/改后行对齐（纯逻辑，可进 testFast）。
 *
 * 对齐键（计划 §3.5）：优先主键启发式（`id` 或 `<table>_id` 单列）；无主键时若改前/改后
 * 行数相等按行序对齐（加告警「行序对齐，可能错位」）；行数不等且无主键 → 全部进 unmatched。
 *
 * 主键真值来源（information_schema/DBN 元数据）是后续项；v1 用启发式并在结果里如实标注
 * [AlignResult.usedKeyHeuristic] / [AlignResult.keylessByOrder]，UI 据此提示可信度。
 */
object RollbackSnapshotAligner {

    enum class CellStatus { EQUAL, CHANGED, ADDED, REMOVED }

    data class AlignedRow(
        val status: CellStatus,
        val key: String?,
        val beforeCells: List<String>?,
        val afterCells: List<String>?,
        val changedColumnIndexes: Set<Int> = emptySet(),
    )

    data class AlignResult(
        val rows: List<AlignedRow>,
        val keyColumnIndexes: List<Int>,
        val usedKeyHeuristic: Boolean,
        val keylessByOrder: Boolean,
        val warnings: List<String>,
    )

    /**
     * 主键列启发式：优先 `id`（大小写不敏感）；其次 `<表名词干>_id` 形态（覆盖 t_order/order_id、
     * t_orders/order_id、t_user/user_id 等常见命名）；无表名时退「唯一一个 *_id 列」。
     * 找不到返回空表。
     */
    fun guessKeyColumnIndexes(columns: List<String>, table: String?): List<Int> {
        val lower = columns.map { it.lowercase() }
        lower.indexOfFirst { it == "id" }.takeIf { it >= 0 }?.let { return listOf(it) }
        val stems = if (table.isNullOrBlank()) emptySet() else buildSet {
            val tableName = table.substringAfterLast('.').lowercase()
            add(tableName)
            add(tableName.removeSuffix("s"))
            add(tableName.removePrefix("t_"))
            add(tableName.removePrefix("t_").removeSuffix("s"))
        }
        if (stems.isNotEmpty()) {
            lower.forEachIndexed { idx, col ->
                if (col.endsWith("_id") && col.removeSuffix("_id") in stems) return listOf(idx)
            }
            return emptyList()
        }
        // 无表名兜底：恰好只有一个 *_id 列时取它
        val idColumns = lower.filter { it.endsWith("_id") }
        return if (idColumns.size == 1) listOf(lower.indexOf(idColumns[0])) else emptyList()
    }

    /** 单片 IN 上限（计划 §3.5）。 */
    const val CHUNK_SIZE = 500

    /**
     * 改后反查 SQL（按主键 IN 分片，每片 ≤[CHUNK_SIZE]）。
     * 无主键返回空表（编排层据此跳过改后采集，条目降级「仅改前」）。
     * 值引号策略：可解析为数字 → 裸值；否则单引号包裹 + '' 转义。
     */
    fun buildPostSelectSql(
        table: String,
        columns: List<String>,
        rows: List<List<String>>,
        keyColumnIndexes: List<Int>,
    ): List<String> {
        if (keyColumnIndexes.isEmpty() || rows.isEmpty()) return emptyList()
        val columnList = keyColumnIndexes.joinToString(", ") { columns.getOrNull(it) ?: "" }
        val single = keyColumnIndexes.size == 1
        val keys = rows.map { row -> keyColumnIndexes.map { row.getOrNull(it) ?: "" } }.distinct()
        return keys.chunked(CHUNK_SIZE).map { chunk ->
            val tupleList = chunk.joinToString(", ") { keyParts ->
                val literals = keyParts.map { quoteLiteral(it) }
                if (single) literals.first() else literals.joinToString(", ", "(", ")")
            }
            val predicate = if (single) {
                "${columns[keyColumnIndexes[0]]} IN ($tupleList)"
            } else {
                "($columnList) IN ($tupleList)"
            }
            "SELECT * FROM $table WHERE $predicate"
        }
    }

    /** 值引号：数字裸值，其余单引号 + '' 转义。 */
    fun quoteLiteral(value: String): String {
        if (value.isEmpty()) return "''"
        if (isNumericLiteral(value)) return value
        return "'" + value.replace("'", "''") + "'"
    }

    /** 数字字面量判定（整数/小数/负号；NULL 除外）。 */
    fun isNumericLiteral(value: String): Boolean {
        if (value.isEmpty()) return false
        var i = if (value[0] == '-') 1 else 0
        if (i >= value.length) return false
        var seenDot = false
        while (i < value.length) {
            val c = value[i]
            when {
                c.isDigit() -> i++
                c == '.' && !seenDot -> {
                    seenDot = true
                    i++
                }
                else -> return false
            }
        }
        return true
    }

    fun align(
        columns: List<String>,
        beforeRows: List<List<String>>,
        afterRows: List<List<String>>,
        keyColumnIndexes: List<Int>,
    ): AlignResult {
        val warnings = mutableListOf<String>()
        if (keyColumnIndexes.isEmpty()) {
            if (beforeRows.size == afterRows.size) {
                warnings += "无主键：改前/改后按行序对齐，若执行期间行序变化可能错位"
                val rows = beforeRows.mapIndexed { index, before ->
                    val after = afterRows.getOrNull(index)
                    if (after == null) {
                        AlignedRow(CellStatus.REMOVED, null, before, null)
                    } else {
                        val changed = columns.indices.filter { before.getOrNull(it) != after.getOrNull(it) }.toSet()
                        AlignedRow(
                            if (changed.isEmpty()) CellStatus.EQUAL else CellStatus.CHANGED,
                            null, before, after, changed,
                        )
                    }
                }
                return AlignResult(rows, emptyList(), usedKeyHeuristic = false, keylessByOrder = true, warnings)
            }
            warnings += "无主键且行数不等：无法对齐（改前 ${beforeRows.size} 行 / 改后 ${afterRows.size} 行）"
            val rows = buildList {
                beforeRows.forEach { add(AlignedRow(CellStatus.REMOVED, null, it, null)) }
                afterRows.forEach { add(AlignedRow(CellStatus.ADDED, null, null, it)) }
            }
            return AlignResult(rows, emptyList(), usedKeyHeuristic = false, keylessByOrder = false, warnings)
        }

        val keyOf = { row: List<String> -> keyColumnIndexes.map { row.getOrNull(it) ?: "" }.joinToString("\u0001") }
        val afterByKey = afterRows.groupBy { keyOf(it) }
        val rows = mutableListOf<AlignedRow>()
        val consumed = mutableSetOf<List<String>>()
        for (before in beforeRows) {
            val key = keyOf(before)
            val candidates = afterByKey[key].orEmpty()
            val after = candidates.firstOrNull { it !in consumed }
            if (after == null) {
                rows.add(AlignedRow(CellStatus.REMOVED, key, before, null))
                continue
            }
            consumed.add(after)
            val changed = columns.indices.filter { before.getOrNull(it) != after.getOrNull(it) }.toSet()
            rows.add(
                AlignedRow(
                    if (changed.isEmpty()) CellStatus.EQUAL else CellStatus.CHANGED,
                    key, before, after, changed,
                ),
            )
        }
        // 剩余改后行 = 新增（含主键被改掉旧键的行——它们表现为 REMOVED+ADDED 对）
        for (after in afterRows) {
            if (after in consumed) continue
            rows.add(AlignedRow(CellStatus.ADDED, keyOf(after), null, after))
        }
        return AlignResult(rows, keyColumnIndexes, usedKeyHeuristic = true, keylessByOrder = false, warnings)
    }
}
