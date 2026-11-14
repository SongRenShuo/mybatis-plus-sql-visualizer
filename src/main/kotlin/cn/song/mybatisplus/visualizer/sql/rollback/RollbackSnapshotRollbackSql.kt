package cn.song.mybatisplus.visualizer.sql.rollback

/**
 * T-RollbackSnapshotPreExec P4：从快照条目生成回滚 SQL（纯逻辑，可进 testFast）。
 *
 * 与 [RollbackSqlGenerator.generateFromSnapshotRows] 的分工：
 * - DELETE → BACKFILL_INSERT：**直接复用生成器**（identity 包装/合批/截断语义全继承）。
 * - UPDATE → 生成器的 REVERT_UPDATE 形态是「单列改动 + 统一新值」（网格单格编辑场景），
 *   自动快照是「多行 × 各行各列」——本类按行装配 `UPDATE t SET 旧值 WHERE 主键 AND 改动列=新值`
 *   （沿用既有乐观锁谓词口径，[RollbackSqlGenerator.revertUpdateFromSnapshot] 同构；
 *   见计划 §3.7「沿用既有乐观锁」条）。
 *
 * 诚实评级：恒 PARTIAL——主键来自启发式（AlignResult.usedKeyHeuristic），且字符串化快照
 * 对 NULL/空串不可区分（落库形态按 '' 处理）。头注释声明，不做 COMPLETE 假象。
 */
object RollbackSnapshotRollbackSql {

    data class Result(val sqlText: String, val statementCount: Int, val rating: RollbackSqlGenerator.Rating)

    /** UPDATE 回滚：每行一条 `UPDATE … SET 旧值 WHERE 主键 AND 改动列=新值`（CAS）。 */
    fun buildUpdateRollback(
        table: String,
        columns: List<String>,
        keyColumnIndexes: List<Int>,
        alignedRows: List<RollbackSnapshotAligner.AlignedRow>,
    ): Result {
        if (keyColumnIndexes.isEmpty()) {
            return Result("", 0, RollbackSqlGenerator.Rating.UNABLE)
        }
        val statements = mutableListOf<String>()
        for (row in alignedRows) {
            if (row.status != RollbackSnapshotAligner.CellStatus.CHANGED) continue
            val before = row.beforeCells ?: continue
            val after = row.afterCells ?: continue
            val sets = columns.indices
                .filter { it in row.changedColumnIndexes }
                .map { "${columns[it]} = ${RollbackSnapshotAligner.quoteLiteral(before.getOrNull(it) ?: "")}" }
            if (sets.isEmpty()) continue
            val predicates = mutableListOf<String>()
            for (k in keyColumnIndexes) {
                val col = columns[k]
                val oldVal = before.getOrNull(k) ?: ""
                predicates += if (oldVal.isEmpty()) "$col IS NULL" else "$col = ${RollbackSnapshotAligner.quoteLiteral(oldVal)}"
            }
            // CAS（乐观锁）：改动列的「改后值」进 WHERE——行被他人改过则命中 0 行而不是覆盖别人
            for (c in row.changedColumnIndexes) {
                if (c in keyColumnIndexes) continue
                val newVal = after.getOrNull(c) ?: ""
                predicates += if (newVal.isEmpty()) "${columns[c]} IS NULL" else "${columns[c]} = ${RollbackSnapshotAligner.quoteLiteral(newVal)}"
            }
            statements += "UPDATE $table SET ${sets.joinToString(", ")} WHERE ${predicates.joinToString(" AND ")};"
        }
        return Result(statements.joinToString("\n"), statements.size, RollbackSqlGenerator.Rating.PARTIAL)
    }

    /**
     * DELETE 回滚：复用 [RollbackSqlGenerator] 的 BACKFILL_INSERT（identity 包装/合批继承）。
     * v1 不接 identity 包装（identityColumns 恒空——快照侧未采集自增列），登记为后续项。
     */
    fun buildDeleteRollback(
        table: String,
        columns: List<String>,
        beforeRows: List<List<String>>,
        primaryKeyColumns: List<String>?,
    ): Result {
        val result = RollbackSqlGenerator.generateFromSnapshotRows(
            table = table,
            columns = columns,
            rows = beforeRows.map { row -> row.map { it as String? } },
            mode = RollbackSqlGenerator.SnapshotMode.BACKFILL_INSERT,
            primaryKeyColumns = primaryKeyColumns,
        )
        if (result.statements.isEmpty()) return Result("", 0, result.rating)
        return Result(result.statements.joinToString("\n"), result.statements.size, RollbackSqlGenerator.Rating.PARTIAL)
    }
}
