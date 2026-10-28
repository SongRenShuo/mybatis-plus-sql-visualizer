# MP / MPJ 官方 API 覆盖矩阵（全量兼容测试语料基座）

> **文档用途**：本矩阵是「MyBatis-Plus SQL Visualizer」插件对 **MyBatis-Plus（MP）与 MyBatis-Plus-Join（MPJ）官方全部 API** 的覆盖矩阵，作为**全量兼容测试语料基座**（对应开发计划 **T-M20-01**）：每行一个「API → 目标状态 → 对应任务 → 备注」，用于批量生成 fixture 测试（已支持 API 断言 SQL 文本；未支持 API 断言不抛异常 + 可读占位提示），并作为各里程碑验收的对照清单。
>
> **版本基线**：MP **3.5.15**（真实项目）/ 最新 **3.5.17**（2026-07-08 发布）；MPJ **1.5.6**（真实项目）/ 最新 **1.5.9**。插件为 PSI 静态分析（不依赖用户工程 classpath），**按最新稳定版 API 方法名超集收录**，旧版本项目天然兼容（子集）；API 引入版本见 §2.7 与备注。
>
> **状态图例**：✅ = 已支持（目标达成）；🟡 = 部分支持（形态有限制）；⬜ = 暂不支持 / 文档化边界（远期或外部依赖）。
>
> **状态说明**：矩阵中「目标状态」为里程碑完成后应达成的最终状态；当前实现快照以《[MPJ 用法收集与兼容性测试清单](../需求文档/MPJ用法收集与兼容性测试清单.md)》§5.6 为准。**状态随开发计划（[20260813-1730-全量API预览支持-开发计划](../开发计划/20260813-1730-全量API预览支持-开发计划.md)）各里程碑（M20/M21/M22）交付更新**：交付一项、对应行 🟡/⬜ → ✅ 或标注文档化边界，并同步新增 A 段 fixture 测试（沿用 P/Q/U 编号惯例，新增段建议 **A01~Axx**，测试先行）。
>
> **交付记录**：**M20 ✅（2026-08-13，379 测试全绿）**——P0 差距清零（JoinWrappers 工厂/entity 构造/批量族/Map 消费/eqOrIsNull/selectIgnore/逻辑删除六方法/消费全集）；**M21 ✅（2026-08-13，420 测试全绿）**——中等成本补全（allEq/setEntity/selectFilter/枚举 selectFunc/rightAlias/子表 MConsumer/ext/prefix-free/from/around/UpdateJoin+DeleteJoin）；**M22 ✅（2026-08-14，444 测试全绿）**——P2 项（方言 6 种扩展、@Version 乐观锁注入、Db kit/SimpleQuery/ActiveRecord 识别）。后续交付：Tenant/DataPermission/DynamicTableName 拦截器模拟已随 F-6 补齐（§1.8 ✅）；实体 `@TableField(fill=…)` 现由 **FieldFill 安全模拟**部分覆盖（显式字面量覆盖、不加载用户 `MetaObjectHandler`、UPSERT 不模拟，T-FieldFillPriority）；仍为文档化边界的：Kotlin 侧 PSI 级续链、部分方言真实库实测。
>
> 关联文档：《MPJ 用法收集与兼容性测试清单.md》（§5.6 为现状矩阵）、《20260813-0829-低频复杂API后期实现计划.md》（P 分组背景）、《20260813-1730-全量API预览支持-开发计划.md》（M20~M22 任务来源，本矩阵任务编号均出自该文档）。

---

## 1. MP 官方 API 矩阵（MyBatis-Plus 3.5.x）

### 1.1 条件构造器（AbstractWrapper / QueryWrapper / UpdateWrapper）

> 说明：每个条件方法均有 **boolean 重载**（`condition=false` 时该条件不产生 SQL），boolean 重载随主方法一并覆盖，不单列。

| 方法 / 重载形态 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `eq/ne/gt/ge/lt/le` | ✅ | —（既有能力） | 值支持字面量 / 变量占位 / 参数面板动态条件 |
| `eqOrIsNull`（3.5.17 新增） | ✅ | T-M20-09 | 值可静态判定 null → `col IS NULL`，否则 `= ?`；boolean 重载同 |
| `between/notBetween` | ✅ | —（既有能力） | lo/hi 双占位符参数面板 |
| `like/notLike/likeLeft/likeRight/notLikeLeft/notLikeRight` | ✅ | —（既有能力） | like 六族全支持；LIKE 字面量引号渲染 |
| `in/notIn`（Collection / varargs） | ✅ | —（既有能力） | IN 多值参数面板（逗号分割、逐值引号） |
| `inSql/notInSql` | ✅ | —（既有能力） | `col IN (sql 片段)` 原样透传 |
| `eqSql/gtSql/geSql/ltSql/leSql`（3.5.6+） | ✅ | —（既有能力） | `col op (sql)` |
| `isNull/isNotNull` | ✅ | —（既有能力） | 可挂条件开关 |
| `exists/notExists`（含 `{0}` 参数） | ✅ | —（既有能力） | `EXISTS (SELECT ...)` |
| `allEq`（6 重载：Map / boolean / null2IsNull / Predicate 组合） | ✅ | T-M21-04 | `Map.of(...)` 字面量 → 展开多条 eq；变量 Map → WHERE 占位条件组；`null2IsNull=true` 且可判 null → IS NULL |
| `and(Consumer)` / `or()` / `or(Consumer)` / `nested(Consumer)` / `not(Consumer)` | ✅ | —（既有能力） | lambda 内 or() 双层括号展平 |
| `func(Consumer)` | ✅ | —（既有能力） | 条件直接展开 |
| `apply`（raw / `{0}` / boolean） | ✅ | —（既有能力） | apply 短路 `"1 = 0"` 自含 toggle 开关 |
| `last` / `comment` / `first` | ✅ | —（既有能力） | rawFirst 前缀 + comment 注释 |
| `groupBy` / `having` | ✅ | —（既有能力） | having 多条件 → AND 组 |
| `orderBy` / `orderByAsc` / `orderByDesc` | ✅ | —（既有能力） | orderBy 双布尔前缀（condition/isAsc）解析 |
| `select(String...)` / `select(List)` / `select(Class)` | ✅ | —（既有能力） | 列投影 |
| `select(Class, Predicate)` | 🟡 | T-M21-01 | 谓词 lambda 不可精确静态求值 → 白/黑名单静态近似，复杂谓词回退全列 |
| `set` / `setSql` / `setIncrBy` / `setDecrBy`（UpdateWrapper，3.5.6+） | ✅ | —（既有能力） | setIncrBy/DecrBy 官方 API 补充 |
| `setEntity(entity)` | ✅ | T-M21-05 | 实体非 null 字段追加等值条件（复用实体字段展开逻辑） |
| `lambda()` 转换 | ✅ | —（既有能力） | QueryWrapper → LambdaQueryWrapper |
| `checkSqlInjection`（3.5.3.2+） | ✅ | —（语义开关） | SQL 片段注入校验开关；静态分析无实际注入面，按开关接受、不生成 SQL 差异 |
| `clear()` | ✅ | —（既有能力） | M19.5：clear 前条件截断丢弃 |

### 1.2 Wrapper 类型与工厂

| 方法 / 重载形态 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `QueryWrapper` / `LambdaQueryWrapper` | ✅ | —（既有能力） | 含实体对象 where 条件形态 |
| `UpdateWrapper` / `LambdaUpdateWrapper` | ✅ | —（既有能力） | 字符串列版与 lambda 版 |
| `Wrappers` 工厂 5 方法：`query/lambdaQuery/update/lambdaUpdate/emptyWrapper`（3.5.9+ 位于 core.toolkit） | ✅ | —（既有能力） | 含 `Wrappers.<T>lambdaQuery()` 泛型形态 |
| 链族 4 类：`QueryChainWrapper` / `LambdaQueryChainWrapper` / `UpdateChainWrapper` / `LambdaUpdateChainWrapper` | ✅ | —（既有能力） | 内部委托普通 Wrapper，SQL 生成复用 |
| `ChainWrappers` 工厂：`queryChain/lambdaQueryChain/updateChain/lambdaUpdateChain/deleteChain/lambdaDeleteChain` | ✅ | —（P1-05，M19.5） | 6 方法 |
| Kotlin 侧：`KtQueryWrapper` / `KtUpdateWrapper` | 🟡 | T-M22-06 | ktQuery/ktUpdate 扩展链已支持（KtWrapperDetector 文本正则 MVP）；两 Wrapper 类直接构造形态仍缺 Kotlin PSI 解析 |

### 1.3 链式调用（ChainQuery / ChainUpdate / IService 链入口）

| 方法 / 重载形态 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `ChainQuery` 终结点 7 个：`list()` / `list(IPage)` / `one()` / `oneOpt()` / `count()` / `exists()` / `page(P)` | ✅ | —（既有能力） | 赋值形态锚点（结果变量）已修复（M19.5） |
| `ChainUpdate` 终结点 3 个：`update()` / `update(T)` / `remove()` | ✅ | —（P1-04，M19.5） | 链上 `update(T)` 实体字段展开 SET |
| IService 链入口 5 个：`query()` / `lambdaQuery()` / `lambdaQuery(T)` / `update()` / `lambdaUpdate()` | ✅ | —（T-M15-01 / P1-03，M19.5） | 字符串链与 lambda 链；`isServiceChainStart` 守卫区分消费调用 |
| IService 链入口 2 个：`ktQuery()` / `ktUpdate()` | ✅ | T-M22-06 | KtWrapperDetector 文本正则识别（映射 LAMBDA_QUERY/LAMBDA_UPDATE），PSI 级续链远期深化 |

### 1.4 IService / IRepository（3.5.9 重构：IRepository 承载 CRUD，IService 保留便捷方法）

| 方法 / 重载形态 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `save` / `saveBatch` | ✅ | —（M19.5 / T-M15-08） | INSERT 全列模板；T-M23-02 参数面板每 `?` 登记 `实体.列名` 输入行；T-M23-04 参数列级勾选——`@TableId(type = IdType.AUTO)` 主键默认不勾选（对齐 MP：自增主键不进 INSERT 列清单）；T-M23.3 列顺序角色分组（主键置顶/业务居中/fill 与逻辑删除沉底，面板同序）；T-M23.4 保留字按方言引用（`table` → `` `table` ``，列清单/表名/ON CONFLICT/EXCLUDED） |
| `saveOrUpdate` / `saveOrUpdateBatch` | ✅ | —（T-M15-08） | UPSERT 模板；T-M23.2 主键**可选**（toggle 列默认勾选渲染完整 upsert，取消 → 纯 INSERT 新记录路径），非主键列可勾选（取消 → 同步退出 INSERT 列清单与冲突子句）；**T-M43 冲突子句按方言渲染**——MySQL 系 `ON DUPLICATE KEY UPDATE 列 = VALUES(列)`、PG 系 `ON CONFLICT (主键) DO UPDATE SET 列 = EXCLUDED.列`、Oracle/SQL Server/DM 降级纯 INSERT（无原生简洁 upsert） |
| `saveOrUpdate(T, Wrapper)` | ✅ | T-M20-10 | UPSERT + wrapper 条件 + 实体字段展开 SET |
| `removeById` / `removeByIds` / `remove` / `removeByMap` | ✅ | —（T-M15-08 / T-M20-07） | removeByMap：Map 等值链（字面量展开 / 变量占位）；T-M23-01 @TableLogic 实体软删除（UPDATE SET deleted=1 + AND deleted=0，无 @TableLogic 维持 DELETE）；T-M23-02 实参字面量内联 / 变量参数行 |
| `updateById` | ✅ | —（既有能力） | UPDATE 主键等值；T-M23-04 SET 列级勾选（默认全勾选，可按需取消——近似 NOT_NULL"只更新需要修改的字段"），WHERE 主键固定 |
| `update(Wrapper)` / `update(T, Wrapper)` | ✅ | —（T-M15-04） | 实体字段展开 SET + wrapper WHERE |
| `updateBatchById(Collection)` | ✅ | T-M20-10 | 批量 UPDATE 主键 IN（IService 便捷重载核对） |
| `getById` / `getOptById` | ✅ | —（M19.5）/ T-M20-10 | getOptById → SELECT 主键等值（Optional 返回）；T-M23-01 @TableLogic 追加 `AND deleted = 0`；T-M23-02 实参字面量内联 / 变量参数行 |
| `getOne` / `getOne(w, false)` / `getOneOpt` | ✅ | T-M20-06 | 双参 / 三参形态核对与锚点 |
| `getMap` / `getObj` | ✅ | T-M20-06 | SQL 形态同 selectList，仅返回差异 |
| `list` / `listByIds` / `listByMap` / `listMaps` / `listObjs` | ✅ | —（M19.5）/ T-M20-06、T-M20-07 | listByMap Map 等值链；listMaps/listObjs 返回差异；T-M23-01 @TableLogic 批量查询追加 `AND deleted = 0` |
| `count` / `exists` | ✅ | T-M20-06 | exists 消费锚点与 SQL 形态核对 |
| `page` / `pageMaps` | ✅ | T-M20-06 | pageMaps SQL 同 page |
| 便捷 4 方法：`saveBatch` / `saveOrUpdateBatch` / `removeBatchByIds` / `updateBatchById`（IService 3.5.9+ 仅剩默认方法，无 batchSize 版） | ✅ | —（T-M15-08 / T-M20-10） | 批量模板；与 IRepository 同名方法（含 batchSize 重载）同模板 |

### 1.5 BaseMapper

| 方法 / 重载形态 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `insert` | ✅ | —（既有能力） | INSERT 全列 |
| `deleteById` | ✅ | —（既有能力） | DELETE 主键等值（逻辑删除转 UPDATE，T-M23-01 落地：@TableLogic 实体软删除） |
| `deleteByMap` | ✅ | T-M20-07 | DELETE + Map 等值链（T-M23-01 @TableLogic 转软删除） |
| `delete` | ✅ | —（既有能力） | DELETE + wrapper |
| `deleteBatchIds`（旧名） / `deleteByIds`（新名，3.5.9+） | ✅ | —（P1-02，M19.5） | 新旧双名同模板；逻辑删除批量 → UPDATE SET del=1 WHERE id IN（T-M23-01 落地） |
| `updateById` / `update` | ✅ | —（既有能力） | 实体字段展开 SET |
| `selectById` | ✅ | —（M19.5） | SELECT 主键等值（Operation.SELECT；T-M23-01 @TableLogic 追加 AND deleted = 0） |
| `selectBatchIds`（旧名） / `selectByIds`（新名，3.5.9+） | ✅ | —（P1-02，M19.5） | SELECT 主键 IN（T-M23-01 @TableLogic 追加 AND deleted = 0） |
| `selectByMap` | ✅ | T-M20-07 | SELECT 全列 + Map 等值链（T-M23-01 @TableLogic 追加 AND deleted = 0） |
| `selectOne` / `selectOne(w, false)` | ✅ | T-M20-06 | 双参形态核对（追加 LIMIT 1 语义） |
| `exists` | ✅ | T-M20-06 | SELECT EXISTS/1 形态 |
| `selectCount` | ✅ | —（既有能力） | SELECT COUNT(*) |
| `selectList` | ✅ | —（既有能力） | SELECT 全列 + wrapper；T-M23.3 列顺序角色分组（`id, 业务字段…, create_time, update_time, deleted`——主键置顶/业务居中/审计沉底，组内声明顺序）；T-M23.4 保留字按方言引用（列名/表名命中本方言保留字 → 按方言符号包裹；raw 形态 apply/子查询为文档化边界）；T-M23.5 批量 SELECT/DELETE/Map 模板与 setIncrBy/selectFunc/applyFunc 解析期拼接同规则补齐 |
| `selectMaps` / `selectObjs` | ✅ | T-M20-06 | SQL 同 selectList，仅返回差异 |
| `selectPage` / `selectMapsPage` | ✅ | T-M20-06 | 分页 SQL（LIMIT/OFFSET 按方言） |
| `selectWithCursor`（3.5.17 新增） | ✅ | T-M20-06/09 | SELECT + ResultHandler 游标消费形态 |
| 批量族（3.5.7+）：`insert(Collection)` / `insert(Collection, int)` / `updateById(Collection)` / `insertOrUpdate` 全重载 | ✅ | T-M20-08 | insertOrUpdate 含 BiPredicate 重载（谓词静态不可求值，忽略，模板归一到 UPSERT，备注近似） |
| ResultHandler 流式重载（`selectList(w, rh)` / `selectMaps(w, rh)` / `selectObjs(w, rh)` 等） | ✅ | T-M20-06 | 按方法名 + 参数计数兼容，SQL 同无 handler 版本 |

### 1.6 注解

| 注解 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `@TableName` | ✅ | —（既有能力） | 含 3.5.10+ `properties`/`excludeProperty`（静态近似）；表前缀配置联动 |
| `@TableId` | ✅ | —（既有能力） | 主键列识别（type 取值近似） |
| `@TableField` | ✅ | —（既有能力） | fill/typeHandler/exist/value/updateStrategy 已支持；insertStrategy/whereStrategy 语义随 T-M22-02 补全 |
| `@TableLogic` | ✅ | —（既有能力） | 逻辑删除四形态改写 |
| `@Version` | ✅ | T-M22-02（M22） | updateById / 实体更新 / wrapper 更新自动追加 `SET version = version + 1` + `AND version = ?`（未显式涉及 version 列时）；显式 set/eq version 不重复注入 |
| `@KeySequence` | ⬜ | T-M22-02 | M22 按需：主键取序列 |
| `@InterceptorIgnore` | ⬜ | T-M22-02 | M22 按需：跳过对应方法注入 |
| `@EnumValue` | ✅ | —（既有能力） | 枚举字段按 DB 存储值映射展开（静态近似） |
| `@OrderBy`（MP 注解） | ⬜ | T-M22-02 补列 | annotation jar 3.5.15 实证存在（2026-09-04 增量核验补列）；SET/查询联动 |
| `@SqlCondition`（MP 注解） | ⬜ | T-M22-02 补列 | 同上；运行时条件拼接，静态近似或文档化边界 |

### 1.7 内置 SqlInjector（无 XML 内置 SQL 模板面）

**DefaultSqlInjector 默认注入 19 个 core 方法**（与 §1.5 BaseMapper 同名同模板）：

| core 方法 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `Insert` | ✅ | —（既有能力） | insert 模板 |
| `Delete` / `DeleteById` / `DeleteBatchByIds` / `DeleteByIds` | ✅ | —（既有能力 / P1-02） | 逻辑删除自动转 UPDATE |
| `DeleteByMap` | ✅ | T-M20-07 | |
| `Update` / `UpdateById` | ✅ | —（既有能力） | |
| `SelectById` / `SelectBatchByIds` / `SelectByIds` | ✅ | —（既有能力 / P1-02） | |
| `SelectByMap` | ✅ | T-M20-07 | |
| `SelectOne` / `SelectCount` | ✅ | —（既有能力） | |
| `SelectList` / `SelectMaps` / `SelectObjs` / `SelectPage` / `SelectMapsPage` | ✅ | —（既有能力 / T-M20-06） | |

**extension 选装（自定义 SqlInjector 方法，用户工程装哪套注入哪套）**：

| 选装方法 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `InsertBatchSomeColumn` | ✅ | —（T-M15-08） | 批量插入自定义列 |
| `AlwaysUpdateSomeColumnById` | ✅ | —（静态近似） | 与 updateById 同模板，列集合语义静态近似 |
| `LogicDeleteBatchByIds` | ✅ | —（静态近似） | 逻辑删除批量 → UPDATE SET del=1 WHERE id IN |
| `Upsert`（3.5.13+） | ✅ | T-M20-08 | 复用 UPSERT 模板（对应 insertOrUpdate 全重载） |
| `LogicDeleteByIdWithFill` | ⬜ | T-M22-02 补列 | ext jar 3.5.15 实证存在（2026-09-04 增量核验补列）；逻辑删除带 fill 字段 UPDATE 模板 |

### 1.8 拦截器（模拟 = 在预览 SQL 中注入拦截器的 SQL 改写效果）

| 拦截器 | 目标状态 | 对应任务编号 | 是否模拟 / 备注 |
|------|:----:|------|------|
| `PaginationInnerInterceptor` | ✅ | —（既有能力） | **模拟**：分页 SQL 渲染（LIMIT/OFFSET 按方言）已等价实现；count 改写 / optimizeCountSql 为语义近似 |
| `TenantLineInnerInterceptor` | ✅ | T-M22-02 | **已模拟**（F-6 交付，sql/interceptor/TenantLineEnhancer.kt）：设置页开关注入 tenant 条件 |
| `OptimisticLockerInnerInterceptor` | ✅ | T-M22-02（M22） | **模拟**：`SET version = version + 1 WHERE version = ?` 自动注入（与 @Version 注解联动） |
| `BlockAttackInnerInterceptor` | 🟡 | T-M22-02 | **警示形态已落地**（无 WHERE 全表操作红色警示，[BlockAttack] 风格）；硬阻断不模拟 |
| `IllegalSQLInnerInterceptor` | ⬜ | T-M22-02 | **不模拟**（文档化边界）：运行时 SQL 合法性校验，非 SQL 生成面 |
| `DynamicTableNameInnerInterceptor` | ✅ | T-M22-02 | **已模拟**（F-6 交付，DynamicTableNameProcessor.kt 正则表名替换引擎） |
| `DataPermissionInterceptor`（3.5.9+） | ✅ | T-M22-02 | **已模拟**（F-6 交付，DataPermissionPlaceholder.kt 占位提示形态） |

### 1.9 其他 SQL 生成面

| 面 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `Page` 构造（无参 / current,size / 带 OrderItem）与 `addOrder` / `optimizeCountSql` / `maxLimit` | ✅ | —（既有能力） | 分页渲染驱动；addOrder → ORDER BY；optimizeCountSql/maxLimit 语义开关近似 |
| XML `${ew.customSqlSegment}` / `${ew.sqlSelect}` / `${ew.sqlSet}`（配套 `@Param("ew")`） | ✅ | T-M22-05（M26-B） | M26-B：Java↔XML 关联（Mapper `@Param("ew")` 解析 + WrapperLocator 展开 + 语句 id 关联） |
| `Db` kit（`Db.getById/list/remove/update/saveBatch` 等静态 CRUD） | ✅ | T-M22-04 | 已交付：WrapperLocator.wrapperFactoryClassNames 含 "Db"，实体模板消费 |
| `SimpleQuery`（`keyMap` / `map` / `group`） | ✅ | T-M22-04（M22） | wrapper 消费形态（默认 SELECT）；另有 list/list2List/list2Map/listGroupBy/peekStream（3.5.15 jar 增量核验补记） |
| ActiveRecord（`AbstractModel.insert()/updateById()/deleteById()/selectAll()`） | ✅ | T-M22-04（M22） | qualifier 实体类型解析（无参形态）+ 注册表；另有 selectById/selectOne/selectPage/selectCount/selectList/delete/insertOrUpdate/update（3.5.15 jar 增量核验补记，3.5.15 无独立 Model 类） |

---

## 2. MPJ 官方 API 矩阵（MyBatis-Plus-Join 1.5.x）

### 2.1 Wrapper 体系与工厂

| 方法 / 重载形态 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `MPJLambdaWrapper`（主形态：lambda 列 + 条件） | ✅ | —（既有能力） | 分步链 / helper 跨方法续链支持 |
| `MPJLambdaQueryWrapper`（字符串查询列 + lambda 条件） | ✅ | T-M20-04 | 字符串链补齐：selectIgnore、groupByStr/orderByStr |
| `MPJQueryWrapper`（全字符串，自连接 / 同表多次 join） | ✅ | —（T-M15-02） | |
| 互转：`stringQuery()` / `lambda()` | ✅ | T-M20-04 | 复用 `wrapper.lambda()` 思路 |
| `UpdateJoinWrapper`（1.4.5+ 联表更新） | ✅ | T-M21-08 | `UPDATE t LEFT JOIN t1 ON ... SET t.x=?, t1.y=? WHERE ...` |
| `DeleteJoinWrapper`（1.4.5+ 联表删除） | ✅ | T-M21-08 | `DELETE t,t1 FROM ...`（全逻辑删除表自动转 UPDATE SET del=1） |
| `JoinWrappers` 工厂 12 形态（1.4.4+）：lambda 族 6 形态（`lambda()/lambda(alias)/lambda(Class)/lambda(alias,Class)/lambda(entity)/lambda(alias,entity)`）+ query 族 3 形态 + delete 族 2 形态 + update 族 2 形态 | ✅ | T-M20-02 | 官方推荐工厂；lambda→MPJ_LAMBDA、query→MPJ_QUERY（按实参形态）、delete/update→DELETE_JOIN/UPDATE_JOIN |
| 旧工厂 `MPJWrappers`（`queryJoin` / `lambdaJoin`） | ✅ | T-M20-02 | 同映射兼容 |
| 构造 6 形态：`()` / `(Class)` / `(T entity)` / `(String alias)` / `(Class, alias)` / `(T entity, alias)`（另全限定名形态） | ✅ | T-M20-03 | entity 构造经实体解析；alias 提取复用既有逻辑；优先级：构造别名 > setAlias > 默认 t |
| `MPJBaseJoin` 标记接口（wrapper.interfaces 包） | ✅ | — | 1.5.8 jar 增量核验补列（2026-09-04）：getDeleteSql/getDeleteLogicSql/getUnionSql，链路识别可按接口判定 |

### 2.2 列选择

| 方法 / 重载形态 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `select(SFunction...)` / `select(String...)` | ✅ | —（既有能力） | 字符串列 1.4.x+ |
| `selectAll()` / `selectAll(Class)` / `selectAll(Class, prefix)` / `selectAll(Class, exclude...)` | ✅ | —（既有能力） | 4 形态；exclude/prefix 为官方 API 补充（1.4.4+/1.4.13+） |
| `selectAs` 4 重载（SFunction / 字符串列 / index 别名） | ✅ | —（既有能力） | |
| `selectAsClass(Class, Class)` | ✅ | —（既有能力） | 属性名交集映射 |
| `selectSum/Max/Min/Avg/Len/Count` 各 3 重载（含别名） | ✅ | —（既有能力） | selectLen → LENGTH |
| `selectFunc` 10 重载（BaseFuncEnum 8 形态 + String 模板 2 形态，`%s` 列占位 + `{0}` 参数，1.5.2+ 支持子查询占位） | ✅ | T-M21-06 | BaseFuncEnum → 内置函数模板映射（SUM/COUNT/MAX/MIN/AVG/LEN 等）；String 模板既有能力 |
| `selectSub`（1.4.5+，字符串别名 1.5.1+） | ✅ | —（M15 增补） | `(SELECT ...) AS alias` 子查询列 |
| `selectFilter`（1.4.4.1+） | 🟡 | T-M21-01 | 谓词精确求值 → 静态近似（与 MP `select(Class, Predicate)` 同规则） |
| `selectCollection` / `selectAssociation` 5 重载（prefix / map 的 id/result / 嵌套 collection/association / **free 形态** 1.4.4+） | ✅ | T-M21-03 | resultMap 嵌套映射渲染；free 形态列映射来自 lambda 体（对照 MPJ 源码 Builder API） |
| `distinct` | ✅ | —（既有能力） | SELECT DISTINCT |
| `selectIgnore`（字符串链） | ✅ | T-M20-04 | 从 selectAll 结果剔除列 |

### 2.3 联表

| 方法 / 重载形态 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `leftJoin` / `innerJoin` / `rightJoin` / `fullJoin`（各 **18 重载**，4 类同形态） | ✅ | —（既有能力）/ T-M21-02 | fullJoin 文档称无独立方法已过时，**按源码支持**（与 leftJoin 同 18 重载）；主表默认别名 t、副表 t1/t2... |
| — 3 参单条件（`leftJoin(Class, left, right)`） | ✅ | —（既有能力） | 列对列比较识别 |
| — 4 参别名（`leftJoin(Class, alias, left, right)`） | ✅ | —（既有能力） | M19.6 修复 on 双字符串 eq 列对列比较 |
| — on lambda 多条件（`leftJoin(Class, on -> ...)`） | ✅ | —（既有能力） | parseOnLambda 字符串列 / apply 子查询支持 |
| — ext MFunction（扩展条件/列） | ✅ | T-M21-02 | ON 后附加条件/列 |
| — 5 参 rightAlias（1.4.8+） | ✅ | T-M21-02 | ColumnRef 补 rightAlias |
| — 子表 MConsumer（副表变子查询 `LEFT JOIN (SELECT ...) t1 ON ...`，1.5.2+） | ✅ | T-M21-02 | 复用子查询渲染抽象（与 from/fill 共享） |
| `join(String keyWord, cond, ...)` 自定义关键字 | ✅ | —（既有能力） | 关键字如 `"left join"` 等 |
| 字符串 SQL join：`join(keyWord, cond, "sql {0}", args)`（1.5.2+ 参数化） | ✅ | —（既有能力） | `{0}` 参数替换（兼容 `{i}`） |

### 2.4 条件

| 方法 / 重载形态 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| MP 条件族全继承 + 每方法 alias 参数重载（eq 族 / like 六族 / between / in 族 / isNull / isNotNull / exists / apply / last / groupBy / having / orderBy 族等） | ✅ | —（既有能力） | 列自动带 t./t1. 前缀；`groupByStr/orderByStr` 字符串形态归一（T-M20-04） |
| 子查询条件 12 个：`eq/ne/gt/ge/lt/le/in/notIn/exists/notExists/isNull/isNotNull(col, Class, wrapper)`（1.5.2+） | ✅ | —（M15 增补） | 独立解析器渲染 `t.id IN (SELECT ...)` |
| IfExists 族 12 个（`eqIfExists` 等，1.4.9+） | ✅ | —（既有能力） | 归一为普通条件 |
| `betweenIfExists` / `notBetweenIfExists`（1.5.6+） | ✅ | —（既有能力） | 归一为普通条件 |
| `inIfNotEmpty` / `notInIfNotEmpty`（1.5.6+） | ✅ | T-M21-09 | IfNotEmpty 后缀归一 |
| `applyFunc`（`%s` 列 + `{0}` 参数，1.4.13+） | ✅ | —（既有能力） | |
| `allEqStr`（字符串列 allEq 变体） | ⬜ | T-M21-04 补列 | 1.5.8 jar 增量核验补列（2026-09-04）：JoinAbstractWrapper/JoinAbstractLambdaWrapper 均有；复用 MapArg 模型 |
| `around`（1.4.11+） | ✅ | T-M21-09 | 渲染位置对照 MPJ 源码 `getSqlAround()` 确认（ORDER BY 前后） |
| `setIfExists` | ✅ | T-M21-09 | 条件开关，值非空/非 null 策略近似 |

### 2.5 其他能力

| 方法 / 重载形态 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `setAlias` | ✅ | —（M15 增补） | 主表别名：构造别名 > setAlias > 默认 t |
| `union` / `unionAll`（1.4.8+，brackets 可选） | ✅ | —（M15 增补） | `UNION (子查询)` |
| `from(MFunction)` 主表子查询（1.5.2+） | ✅ | T-M21-07 | `FROM (SELECT ...) alias`；主表 TableInfo 与子查询模型冲突处理 |
| `disableLogicDel` / `enableLogicDel`（主表开关） | ✅ | —（P1-01，M19.5） | 与副表开关按源码顺序统一求值 |
| `disableSubLogicDel` / `enableSubLogicDel`（副表开关） | ✅ | T-M20-05 | 副表 deleted 条件注入控制 |
| `logicDelToOn` / `logicDelToWhere`（副表条件位置，1.4.8 起默认 ON） | ✅ | T-M20-05 | 精确化：deleted 进 ON / 移到 WHERE 末尾 |
| `setTableName(Function)` 动态表名（1.4.4+） | ✅ | T-M21-09 | 动态不可求值 → 表名占位/提示 |
| `pageByMain()` 主表分页 | ✅ | T-M21-09 | 渲染同 page |
| `fill` 6 形态（1.5.1+ 关联填充） | ✅ | T-M22-01 | 已交付（MpjLambdaConverter 六形态解析 + MpjFillConverterTest）；主查询 + 关联键 IN 附加查询两段渲染 |
| `checkSqlInjection` | ✅ | —（语义开关） | 同 MP，按开关接受 |

### 2.6 消费方法与链终结点

| 方法 / 重载形态 | 目标状态 | 对应任务编号 | 备注 |
|------|:----:|------|------|
| `selectJoinList` / `selectJoinOne` | ✅ | —（既有能力） | |
| `selectJoinPage` | ✅ | —（既有能力） | |
| `selectJoinCount` | ✅ | —（既有能力） | |
| `selectJoinMaps` / `selectJoinMapsPage` | ✅ | —（M15 增补） | |
| `selectJoinListPage`（MPJBaseService 别名） | ✅ | —（既有能力） | Service 层别名 |
| `deleteJoin` | ✅ | —（M15 增补） | DELETE（全逻辑删除表转 UPDATE SET del=1） |
| `updateJoin` / `updateJoinAndNull` | ✅ | —（M15 增补） | 实体字段展开 SET |
| 链终结点：`count` / `one` / `one(Class)` / `first` / `list` / `page` / `mapOne` / `mapFirst` / `mapList` / `mapPage` | ✅ | T-M20-06 | 带 Class 实参形态核对（1.4.4+ / map* 1.5.0） |
| UpdateJoin 链终结点：`update()` / `update(T)` / `updateAndNull()` / `updateAndNull(T)`（1.4.10+） | ✅ | T-M21-08 | |
| UpdateJoin set 族：`setUpdateEntity` / `setUpdateEntityAndNull` / `setApply`；DeleteJoin `deleteAll()` | ✅ | T-M21-08 补列 | 1.5.8 jar 增量核验补列（2026-09-04）：实体 SET 展开与全表删除形态 |
| DeleteJoin 链终结点：`deleteJoin()` | ✅ | T-M21-08 | |
| 原生方法混用（MPJ wrapper 直接喂 `getOne/count/remove/update` 等原生消费方法） | ✅ | —（既有能力） | MPJ wrapper 继承 MP Wrapper，可喂原生消费 |

> 注：官方确认**不存在** `selectJoinOnePage` 与 `insertJoin`，无需支持。

### 2.7 版本备注（各 API 引入版本）

| API | 引入版本 | 说明 |
|------|:----:|------|
| `selectCollection` / `selectAssociation`（基础形态） | 1.3.0 | 首个 MPJ 版本 |
| 链式终结点 / `JoinWrappers` 工厂 / free 形态 / `setTableName` / `selectAll` prefix | 1.4.4 | |
| `selectFilter` | 1.4.4.1 | |
| `UpdateJoinWrapper` / `DeleteJoinWrapper` / `selectSub` | 1.4.5 | selectSub 字符串别名 1.5.1+ |
| `union` / `unionAll` / 5 参 rightAlias / 副表逻辑删除默认 ON | 1.4.8 | |
| IfExists 族（`eqIfExists` 等 12 个） | 1.4.9 | |
| UpdateJoin 链 `updateAndNull` | 1.4.10 | |
| `around` | 1.4.11 | |
| `selectAll(Class, exclude...)` / `applyFunc` | 1.4.13 | |
| map* 链终结点 / `one(Class)` 等带 Class 终结点 | 1.5.0 | 移除独立 core 模块 |
| `fill` / `selectSub` 字符串别名 | 1.5.1 | |
| 子查询条件 12 个 / `from` / join `{0}` 参数化 / selectFunc 子查询占位 | 1.5.2 | 大版本（子查询族） |
| `betweenIfExists` / `notBetweenIfExists` / `inIfNotEmpty` / `notInIfNotEmpty` | 1.5.6 | |
| 最新版本 | 1.5.9 | 2026-07-10 发布（2026-09-04 核验修正，双渠道 GitHub atom + Maven Central lastUpdated），对应 MP 3.5.17+；1.5.8 ↔ MP 3.1.2~3.5.16 |

---

## 3. 目标状态填写规则

> 矩阵「目标状态」为里程碑计划完成后应达成的最终状态；⬜ 项均为**文档化边界**（对外能力声明），插件对不支持面必须优雅降级（可读占位提示），不得抛异常（M1-T1-08 健壮性原则）。

| 场景 | 目标状态 | 说明 |
|------|:----:|------|
| 绝大多数行（计划内全部实现） | ✅ | 本矩阵即 T-M20-01 语料基座；实现一项、对应行标 ✅ 并新增 A 段 fixture 测试（断言 SQL 文本） |
| `select(Class, Predicate)` / `selectFilter` 谓词精确求值 | 🟡 | 静态近似（T-M21-01）：psi 层识别 predicate lambda 体的字段白/黑名单，复杂谓词回退全列 |
| `fill` 6 形态 | ✅ | T-M22-01 已交付（MpjLambdaConverter 六形态 + MpjFillConverterTest，见 §2.5）；MP 实体 `@TableField(fill=…)` 另由 FieldFill 安全模拟覆盖（显式字面量，不仿真 `MetaObjectHandler`；UPSERT 不模拟） |
| `@Version` 乐观锁 / 拦截器模拟 | ✅ | T-M22-02（M22）——@Version 注入已交付；Tenant/DataPermission/DynamicTableName 已随 F-6 交付 ✅、BlockAttack 警示形态 🟡 |
| 方言扩展（SQL Server / H2 / SQLite / MariaDB / 达梦 / KingBase） | ✅ | T-M22-03（M22），未知方言回退 MySQL；T-M43 补 upsert 按方言（MySQL 系 ON DUPLICATE KEY UPDATE / PG 系 ON CONFLICT / Oracle·SQLServer·DM 降级纯 INSERT）+ 达梦分页官方实证 + 反向解析分页三形态宽容（LIMIT..OFFSET / OFFSET..FETCH / FETCH FIRST） |
| `Db` kit / `SimpleQuery` / ActiveRecord | ✅ | T-M22-04（M22）——Db.getById/类字面量/lambdaQuery 链、SimpleQuery wrapper 消费、ActiveRecord 无参形态 |
| XML `${ew.customSqlSegment}` | ✅ | 远期（T-M22-05，M26-B 已交付） |
| Kotlin 侧（`KtQueryWrapper` / `KtUpdateWrapper` / `ktQuery()` / `ktUpdate()` / MPJ Kotlin 扩展） | 🟡 | ktQuery()/ktUpdate() 扩展链已支持（文本正则 MVP）；Wrapper 类构造与 PSI 级续链远期（T-M22-06） |
| `allEq` / `selectFunc(BaseFuncEnum)` / `from` / 子表 MConsumer / rightAlias / prefix / free 等 | ✅ | 对应 T-M21-xx 任务（T-M21-04 / 06 / 07 / 02 / 03），里程碑 M21 交付 |

---

## 4. 信息来源

| 来源 | 地址 / 版本 | 核实内容 |
|------|------|------|
| MyBatis-Plus 官方文档 | https://baomidou.com | guides/wrapper、guides/data-interface、更新日志 |
| GitHub：baomidou/mybatis-plus | https://github.com/baomidou/mybatis-plus | 本地 **3.5.15 jar（javap 反编译）** + **v3.5.9 / v3.5.17 tag 源码**，与官方文档三方交叉核实 |
| MPJ 官方文档 | https://mybatis-plus-join.github.io | 43 页文档（md 源） |
| GitHub：yulichang/mybatis-plus-join | https://github.com/yulichang/mybatis-plus-join | master 分支源码（wrapper/query/base/interfaces/segments 40+ 文件逐行核对）；fullJoin 等文档/源码出入按源码为准 |
| 版本信息 | GitHub releases/tags + Maven Central | MP 3.5.17（2026-07-08 发布）；MPJ 1.5.9（2026-07-10 发布）；各 API 引入版本见 §2.7 |

---

## 5. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-10-27 | **时效性复核（矩阵状态列无变化）**：核对 2026-10-20 之后 `converter/` 与 `psi/` 的提交，无影响 API 覆盖面者，三条均属实现加固——`f69f75fe`/`58dd0c23` 补 SQL 片段入口的字符串拼接实参与 `select`/`first`/`around`/`UNION`/`LIMIT` 占位符来源登记（§1.9 消费面，不新增 API）；`8267394d` 增 `[T-EmbeddedIdentify]` 嵌入展平诊断日志（INFO 级、不改语义）。另确认反向链路条件组修复 `661019b6`（OR 组不吞 OR、子组保留 NOT、`@TableLogic` 条件不重复生成）属 §1 条件构造器已有行的语义修正，不涉及新方法。 |
| 2026-10-20 | **现状回填**：修正交付记录摘要「剩余 ⬜」旧口径——Tenant/DataPermission/DynamicTableName 已随 F-6 交付 ✅（§1.8）、MPJ `fill` 6 形态已随 T-M22-01 交付 ✅（§2.5/§3）；实体 `@TableField(fill=…)` 新增 **FieldFill 安全模拟**（显式字面量覆盖、不加载用户 `MetaObjectHandler`、UPSERT 不模拟、INSERT/UPDATE 模拟值优先，T-FieldFillPriority），按「部分覆盖」描述而非全仿真。 |
| 2026-09-04 | **二轮时效性核验（四智能体 jar javap + releases 双渠道）**：① 确认 MP 3.5.17 / MPJ 1.5.9 仍为最新，修正 MPJ 1.5.9 发布日期为 2026-07-10（§2.7/§4）；② 过期行回填 8 处——§1.2 Kotlin 🟡、§1.3 ktQuery/ktUpdate ✅、§1.8 Tenant/DynamicTableName/DataPermission ✅ + BlockAttack 🟡、§1.9 Db kit ✅（SimpleQuery/ActiveRecord 备注补全）、§2.5 fill ✅；③ 新增行 5 处——§1.6 MP @OrderBy/@SqlCondition、§1.7 LogicDeleteByIdWithFill、§2.1 MPJBaseJoin、§2.4 allEqStr、§2.6 UpdateJoin setUpdateEntity 族/deleteAll（均注「增量核验补列」，引入版本待考证）；④ Kotlin 侧 §3 规则表同步 🟡。同日新建姊妹文档《JPA-Hibernate官方API覆盖矩阵》 |
| 2026-08-14 | **T-M23.7 同步（497 测试全绿）**：触发/定位能力变更（任意位置触发 + 选择区多 SQL 选择）——无 API 矩阵行影响，记录于清单 §8 与开发计划 `20260814-1115-任意位置触发与多SQL选择-开发计划.md` |
| 2026-08-14 | **T-M23.6 同步（492 测试全绿）**：UI 行为变更（复制/执行 SQL 成功提示加「关闭 SQL 面板」按钮）——无 API 矩阵行影响，记录于清单 §8 与开发计划 `20260814-1100-弹窗提示一键关闭SQL面板-开发计划.md` |
| 2026-08-14 | **T-M23.5 同步（490 测试全绿）**：§1.5 `selectList` 行备注——批量 SELECT/DELETE/Map 模板与 setIncrBy/selectFunc/applyFunc 解析期拼接按方言引用补齐（psi 层 `currentDialect` 共享 + `ColumnRef.qualifiedName(dialect)` 统一入口） |
| 2026-08-14 | **T-M23.4 同步（486 测试全绿）**：§1.4 `save/saveBatch` 与 §1.5 `selectList` 行备注——保留字按方言引用（五方言保留字集合，列名/表名/ON CONFLICT/EXCLUDED；符号按方言：反引号/双引号/方括号） |
| 2026-08-14 | **T-M23.3 同步（472 测试全绿）**：§1.4 `save/saveBatch` 与 §1.5 `selectList` 行备注——字段顺序角色分组（`ColumnRole`：主键置顶/业务居中/fill 与逻辑删除沉底，组内声明顺序稳定排序；SELECT 全列展开与批量面板同序） |
| 2026-08-13 | 初版：基于《20260813-1730-全量API预览支持-开发计划.md》§1 调研结论（MP 官网 + jar javap + v3.5.9/v3.5.17 tag 源码；MPJ 官网 + master 源码 + releases/tags 三重交叉核实），建立 MP 3.5.17 / MPJ 1.5.9 **官方 API 全量覆盖矩阵**（T-M20-01 语料基座）：MP 9 个分类面、MPJ 7 个分类面，每行标注目标状态 / 对应任务编号（T-M20-xx / T-M21-xx / T-M22-xx）/ 备注；目标状态按 §3 规则填写（🟡 2 项、⬜ 10 项，其余 ✅） |
