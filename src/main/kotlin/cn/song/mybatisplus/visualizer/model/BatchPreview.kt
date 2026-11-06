package cn.song.mybatisplus.visualizer.model

import cn.song.mybatisplus.visualizer.sql.ParamHint

/**
 * 批量操作预览（F-15，M6-01）：`service.updateBatchById(list)` 等批量调用的 SQL 模板。
 *
 * 批量方法的运行时参数（list 元素）静态不可得，因此：
 *  - [sql] 为 SQL 模板（`?` 占位符）
 *  - [paramGroups] 为参数组说明文本（如 `-- 实际参数: [stationId=?, schemeId=?, id=?] × N`）
 *  - T-M23-02：单主键方法实参可静态求值时**内联**进 SQL（无参数行）；不可求值时
 *    [paramSources]/[paramHints] 登记（面板参数行，与 SQL 中 `?` 顺序一一对应）
 *
 * @param methodName  批量方法名（如 updateBatchById）
 * @param tableName   目标表名
 * @param sql         SQL 模板
 * @param paramGroups 参数组说明（null 表示单语句、无需参数组）
 * @param paramSources 未解析参数来源（T-M23-02，与 SQL 中 `?` 顺序对应；无参数为空列表）
 * @param paramHints  与 [paramSources] 并行的回填引号提示（T-M23-02，按列类型 [ParamQuote]）
 * @param batchModel  批量可选参数模型（T-M23-04）：INSERT/UPDATE/UPSERT 的列级勾选结构，
 *                    驱动面板勾选重建 SQL；SELECT/DELETE 等无列级选择的方法为 null
 * @param warning     T-WriteFamily（R4）：无 WHERE 条件的高危写操作警示（`delete(null)` /
 *                    `update(entity, null)` / `remove(null)`），透传到 [cn.song.mybatisplus.visualizer.model.PreviewResult]；
 *                    null 表示无警示
 */
data class BatchPreview(
    val methodName: String,
    val tableName: String,
    val sql: String,
    val paramGroups: String?,
    val paramSources: List<String> = emptyList(),
    val paramHints: List<ParamHint> = emptyList(),
    val batchModel: BatchModel? = null,
    val warning: String? = null,
)
