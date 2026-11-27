package cn.song.mybatisplus.visualizer.model

/**
 * SQL 语句模型（SQL 生成器的输入）。
 *
 * M1 实现 SELECT（LambdaQueryWrapper）；M4 扩展 UPDATE（LambdaUpdateWrapper）与
 * DELETE（LambdaDeleteWrapper），并支持 MPJ 联表（SelectStatement 带 [JoinClause]）。
 */
sealed class SqlStatement {

    /** 目标表信息。 */
    abstract val table: TableInfo

    /** WHERE 条件列表（顶层以 AND 连接）。 */
    abstract val conditions: List<Condition>
}

/**
 * 联表子句（MPJ，F-11/F-12）。
 *
 * @param type        联表类型（INNER / LEFT / RIGHT / FULL）
 * @param tableName   关联表名
 * @param alias       关联表别名（MPJ 惯例：实体类名首字母小写）
 * @param onConditions ON 连接条件（如 `cssm.scheme_id = t.share_scheme_id`）
 * @param rawSql      原生 join 子句（`leftJoin("address t1 on t1.user_id = t.id", args)`，M15-P0）；
 *                    非空时整段原样渲染（含别名与 ON），忽略 tableName/alias/onConditions
 * @param customKeyword 自定义 join 关键字（`join("FULL JOIN", ...)`，M15-P0）；null 时按 type 映射
 * @param logicDeleteColumn 关联表逻辑删除列（@TableLogic 标注字段的列名，T-M20-05）；null 表示无逻辑删除
 *                          （类名兜底 / 字符串 join / selectCollection 子查询形态无 TableInfo）。 *                          SqlPreviewService 据此按 MPJ 官方语义注入副表 deleted 条件（默认 ON，
 *                          `logicDelToWhere()` 移 WHERE 末尾；`disableSubLogicDel()` 关闭）
 * @param tableInfo          关联表实体元数据（PSI 解析的 EntityRefArg）；null 表示无实体信息
 *                          （类名兜底 / 字符串 join）。图表渲染据此查找字段 Java 类型。
 */
data class JoinClause(
    val type: JoinType,
    val tableName: String,
    val alias: String,
    val onConditions: List<Condition>,
    val rawSql: String? = null,
    val customKeyword: String? = null,
    val logicDeleteColumn: String? = null,
    val tableInfo: TableInfo? = null,
    /** [rawSql] 中占位符 `?` 的来源（按出现顺序，T-UpdateSetSqlConcat）：渲染时按序登记进参数表。 */
    val rawParams: List<String> = emptyList(),
)

/**
 * 联表类型。
 */
enum class JoinType { INNER, LEFT, RIGHT, FULL }

/**
 * SELECT 语句模型。
 *
 * @param selectColumns SELECT 列；为空表示全部列（`SELECT *` 语义，生成器展开为全列名）
 * @param tableAlias    主表别名（单表查询为 null；MPJ 联表默认 `t`）
 * @param joins         联表子句列表（MPJ）
 * @param distinct      是否 SELECT DISTINCT（清单 P05）
 * @param groupBy       GROUP BY 列
 * @param having        HAVING 条件（单个）
 * @param orderBy       ORDER BY 项列表
 * @param limit         分页子句（null 表示无 LIMIT）
 * @param rawFirst      SQL 最前片段（`first("...")` 官方 API，M15 增补）；非 null 时置于 SELECT 之前
 * @param rawAround     around 片段（`around(firstSql, lastSql)` 的 lastSql 段，MPJ 1.4.11+，T-M21-09）；
 *                      非 null 时渲染于 ORDER BY 之后、LIMIT 之前（MP 内部 LAST 段位置）
 * @param comment       SQL 注释（`comment("...")` 官方 API，M15 增补）；非 null 时追加到语句末尾
 * @param unions        UNION / UNION ALL 子查询（MPJ 官方 API，M15 增补）；按调用顺序追加到主语句之后
 * @param fromSubQuery  from 子查询主表（`from(w -> ...)`，MPJ 官方 API，T-M21-07）：first=子查询 SQL
 *                      （compact 单行渲染）、second=外层别名（主表别名）；非 null 时生成器渲染
 *                      `FROM (子查询SQL) 外层别名`（忽略主表 tableName，主表实体表名在子查询内部）
 * @param fillClauses   fill 附加查询子句列表（MPJ 1.5.1+，T-MpjFill）：按调用顺序；
 *                      非 null 时生成器在主 SELECT 之后追加渲染 fill 附加 SELECT 段
 */
data class SelectStatement(
    override val table: TableInfo,
    val selectColumns: List<ColumnRef> = emptyList(),
    val tableAlias: String? = null,
    val joins: List<JoinClause> = emptyList(),
    val distinct: Boolean = false,
    override val conditions: List<Condition> = emptyList(),
    val groupBy: List<ColumnRef> = emptyList(),
    val having: Condition? = null,
    val orderBy: List<OrderByItem> = emptyList(),
    val limit: LimitClause? = null,
    val rawFirst: String? = null,
    /** [rawFirst] 中占位符 `?` 的来源（按出现顺序，T-UpdateSetSqlConcat）。 */
    val rawFirstParams: List<String> = emptyList(),
    val rawAround: String? = null,
    /** [rawAround] 中占位符 `?` 的来源（按出现顺序，T-UpdateSetSqlConcat）。 */
    val rawAroundParams: List<String> = emptyList(),
    val comment: String? = null,
    val unions: List<UnionClause> = emptyList(),
    val fromSubQuery: Pair<String, String>? = null,
    val fillClauses: List<FillClause>? = null,
    /**
     * D264 修5（L9/L16）：解析期**未识别**的链方法名（按出现顺序、已去重）。
     *
     * 语义：这些方法的条件/结构未被静态还原，[conditions] 里**没有**它们的贡献；非空即代表本次
     * SQL 可能少了条件，[cn.song.mybatisplus.visualizer.ui.SqlPreviewService.buildWarning] 据此
     * 渲染面板红字（与 `unparsedSets` 同款口径）。
     *
     * 默认空表且置于参数列表末尾：既有构造点（main 17 处 + test 93 处）零改动。
     * 只承载解析循环里**已经收集到的**名字，不引入任何额外遍历。
     */
    val unknownMethods: List<String> = emptyList(),
) : SqlStatement()

/**
 * UNION 子查询子句（MPJ 官方 API）：`union(Class, w -> ...)` → `UNION (子查询)`。
 *
 * @param keyword UNION / UNION ALL
 * @param sql     子查询 SQL（compact 单行渲染）
 * @param params   [sql] 中占位符 `?` 的来源（按出现顺序，T-UpdateSetSqlConcat）：子语句在渲染点按序登记
 */
data class UnionClause(
    val keyword: String,
    val sql: String,
    val params: List<String> = emptyList(),
)

/**
 * fill 附加查询子句（MPJ 1.5.1+，T-MpjFill）。
 *
 * MPJ `fill(field, tagField)` 语义：主查询执行后收集结果集中关联键 → 对关联表发起 IN 附加查询 →
 * 内存回填到主结果集合。静态预览无法获取主查询结果集，IN 列表统一渲染 `IN (?)` 占位符。
 *
 * @param childTable    关联表 TableInfo（psi 解析或类名推断）
 * @param childAlias    关联表别名（实体类名首字母小写）
 * @param selectColumns 关联表 SELECT 列列表（默认全列）
 * @param tagField      关联表 IN 条件列（关联键字段名，列名）
 * @param limit         附加查询条数限制（null 表示不限制）
 * @param condition     条件控制（null 表示无条件，true 执行/false 跳过）
 * @param data          指定数据源对象（null 表示从主查询结果收集）
 */
data class FillClause(
    val childTable: TableInfo,
    val childAlias: String,
    val selectColumns: List<ColumnRef>,
    val tagField: String,
    val limit: Int? = null,
    val condition: Boolean? = null,
    val data: SqlValue? = null,
)
