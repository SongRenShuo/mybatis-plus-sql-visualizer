package cn.song.mybatisplus.visualizer.log

/**
 * SQL 语句类型分类器（P-06，纯逻辑，可独立单测）：按 restoredSql 首 token 分类。
 *
 * 分类规则：
 * - 取 [LogSqlEntry.restoredSql] trim 后首 token（空格/换行/制表符分割）。
 * - `SELECT` / `WITH`（CTE）→ [StatementType.SELECT]
 * - `INSERT` → [StatementType.INSERT]
 * - `UPDATE` → [StatementType.UPDATE]
 * - `DELETE` → [StatementType.DELETE]
 * - `CREATE` / `ALTER` / `RENAME` → [StatementType.CREATE]（结构性新增/变更）
 * - `DROP` / `TRUNCATE` → [StatementType.DROP]（结构性删除/清空）
 * - 其他（`MERGE` / `REPLACE` / 存储过程调用等）→ [StatementType.OTHER]
 *
 * T-DdlTypeColor（2026-10-26）：DDL 独立成两档而非并入 INSERT/DELETE。理由是
 * **筛选按 StatementType 勾选**——若 CREATE 就是 INSERT，勾「只看插入」会把建表语句
 * 一起筛出来，语义错误。着色可复用（CREATE 取绿、DROP 取红），但类型必须分开。
 */
enum class StatementType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,

    /** 结构性新增/变更：`CREATE` / `ALTER` / `RENAME`。着色复用 INSERT 绿。 */
    CREATE,

    /** 结构性删除/清空：`DROP` / `TRUNCATE`。着色复用 DELETE 红。 */
    DROP,

    /** 未识别（`MERGE` / `REPLACE` / 存储过程调用等）——不着色、不进筛选弹窗。 */
    OTHER,
}

object LogStatementClassifier {

    /**
     * 按 SQL 文本分类（忽略前导空白 + 大小写不敏感）。
     *
     * @param sql 还原后的可执行 SQL（[LogSqlEntry.restoredSql]）
     * @return 语句类型（永不 null：空串/纯空白 → [StatementType.OTHER]）
     */
    fun classify(sql: String): StatementType {
        val trimmed = sql.trim()
        if (trimmed.isEmpty()) return StatementType.OTHER
        // 首 token：以空白字符（空格/换行/制表符）分割
        val firstToken = trimmed.split(Regex("\\s+"))[0].uppercase()
        return when (firstToken) {
            "SELECT", "WITH" -> StatementType.SELECT
            "INSERT" -> StatementType.INSERT
            "UPDATE" -> StatementType.UPDATE
            "DELETE" -> StatementType.DELETE
            // DDL：结构性变更 vs 结构性删除
            "CREATE", "ALTER", "RENAME" -> StatementType.CREATE
            "DROP", "TRUNCATE" -> StatementType.DROP
            else -> StatementType.OTHER
        }
    }

    /**
     * 按条目分类（委托 [classify]；取 [LogSqlEntry.restoredSql]）。
     *
     * @param entry 日志 SQL 条目
     * @return 语句类型
     */
    fun classify(entry: LogSqlEntry): StatementType = classify(entry.restoredSql)
}
