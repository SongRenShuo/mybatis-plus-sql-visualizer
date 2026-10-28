package cn.song.mybatisplus.visualizer.log;

/**
 * SQL 语句类型分类器（P-06，纯逻辑，可独立单测）：按 restoredSql 首 token 分类。
 *
 * 分类规则：
 * - 取 LogSqlEntry.restoredSql trim 后首 token（空格/换行/制表符分割）。
 * - `SELECT` / `WITH`（CTE）→ SELECT
 * - `INSERT` → INSERT
 * - `UPDATE` → UPDATE
 * - `DELETE` → DELETE
 * - `CREATE` / `ALTER` / `RENAME` → CREATE（结构性新增/变更）
 * - `DROP` / `TRUNCATE` → DROP（结构性删除/清空）
 * - 其他（`MERGE` / `REPLACE` / 存储过程调用等）→ OTHER
 *
 * T-DdlTypeColor（2026-10-26）：DDL 独立成两档而非并入 INSERT/DELETE。理由是
 * 筛选按 StatementType 勾选——若 CREATE 就是 INSERT，勾「只看插入」会把建表语句
 * 一起筛出来，语义错误。着色可复用（CREATE 取绿、DROP 取红），但类型必须分开。
 */
public enum StatementType {
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
