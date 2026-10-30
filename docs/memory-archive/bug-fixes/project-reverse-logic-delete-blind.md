---
name: project-reverse-logic-delete-blind
description: SQL→Java 反向生成器不认实体 @TableLogic，把 MP 自动注入的 deleted = 0
  当用户手写条件再生成一遍——20261021 已修（EntityTarget 增 logicDeleteColumn/logicDeleteValue）
metadata:
  node_type: memory
  type: project
  originSessionId: session_2329619ef00e777ab8a30e733ab3cd3c
---

2026-10-21 用户在真实工程（cyzc-platform）预览 SQL 后转 Java 代码，发现生成结果多出 `.eq(BizCarOccupyRecordEntity::getDeleted, 0)` 与 `select(... getDeleted)`，而**源方法 `buildBaseWrapper` 里根本没有 deleted 条件**。

**根因链**：
- 用户工程 `BaseEntity` 有 `@TableLogic private Integer deleted;`。
- MP 在 `SelectList` 注入时走 `sqlWhereEntityWrapper`，`table.isWithLogicDelete()` 为真就拼 `table.getLogicDeleteSql(false, true)`（`AbstractMethod.java:243`）⇒ 预览出的 SQL 自带 `AND deleted = 0`。
- 反向侧对此完全无知：`JavaCodeGenerator.EntityTarget` 只有 `className`/`columnToField`/`subEntities`，**没有逻辑删除列**；而正向链路的 `EntityResolver` 早已算得出 `logicDeleteColumn`（`EntityResolver.kt:115-118`，能识别父类继承字段），只是这个信息没往反向侧传。

**影响面**：重复一个 `deleted = 0` 语义无害；若项目把 `logic-not-delete-value` 改成非 0，生成器硬编码的 `= 0` 会把结果全过滤掉。`.select(…getDeleted)` 同样多余——MP 默认全列 select 本来就含逻辑删除列。

**已实施（20261021，提交 `661019b6`，已合入 master `9dbe619b`，分支与工作树已删）**：
- `EntityTarget` 增 `logicDeleteColumn: String? = null` 与 `logicDeleteValue: String = "0"`（都给默认值，既有构造点零改动）。
- `filterConditions` 增 `entity` 参数，命中「列名匹配逻辑删除列 + `=` + 值等于未删除值」即跳过；`In` 单值形态同样识别。**值不等一律保留**——用户手写的 `deleted = 1`（查已删除数据）是真过滤条件，不能被吞。
- select 投影里的同名列一并跳过；注释头追加一行「逻辑删除列 X 由 MP 依据 @TableLogic 自动追加，未重复生成该条件与投影」，避免用户以为生成器漏了条件。
- **逻辑删除值由 UI 层传入**：`ConvertSqlToJavaAction` / `SqlToJavaPanel` 读 `ProjectPluginSettings.getInstance(project).effectiveLogicDeleteValue()`（项目级→全局→兜底 `"0"`，与正向注入同口径），`SqlToJavaConverter.generateCode/generateBatchCode` 加参数转交。这样 `JavaCodeGenerator` 保持纯函数（不碰 PSI/IDE，可单测）——**这条取舍是可复用模式：设置值走参数注入，别在纯函数里读配置**。

**同轮另两条不算缺陷、但会被误报成缺陷的形态**：①`eq(stationId).in(stationId)` 同时出现＝预览时两个互斥入参都填了值，生成器只能忠实反映预览入参；②`between(createTime, "1", "1")` 是字符串字面量（原代码传 `LocalDateTime`），SQL 里只有 `'1'` 无从推断类型，需人工替换（仍在残留）。

**未覆盖的残留**：`@TableLogic(delval=…)` 实体级自定义值（`TableInfo` 不携带，只认全局设置）；JOIN 副表逻辑删除仍走既有 `isSubLogicalDelete`（ON 条件容忍），与主表过滤口径独立但互不冲突。

相关：[[mp-wrapper-nested-vs-and-semantics]]（同轮实测的另一处）、[[mp-core-no-selectas]]（同为反向生成器的 MP 语义契约）。
