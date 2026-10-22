# T-ParamImportColumnKey：参数导入裸列名互认与预填最短唯一键

2026-10-20。来源：用户在 dev 分支真机验收时发现——单表无别名的 `UPDATE t_order` 预览，导入参数的 JSON 键是 `order.status` / `order.create_time` / `order.id`；用户按列名写会判未匹配。

## 一、现象与判定

- 面板参数行与导入弹框预填都带 `order.` 前缀；SQL 本身没有别名（`UPDATE t_order SET … WHERE id = 1`）。
- 前缀不是 SQL 别名，而是 `BatchOperationConverter.entityPrefix(call)` 取 wrapper 调用首参表达式（如 `update(order, wrapper)` 里的变量名 `order`），仅作参数 source 的命名空间。
- 单表无别名场景把这个内部命名空间暴露成导入格式要求，不合理（UI-032，体验改进而非渲染缺陷）。

## 二、根因（代码证据）

- `BatchOperationConverter.kt:413/416/462`：`source = "$prefix.$column"`；`entityPrefix` 取首参表达式文本。
- `SqlPreviewPanelParamPanel.kt` 的 `resolveSource` 只有三级匹配：精确、去 `#`、驼峰/下划线互转——不认裸列名。
- `serializeParamsToJson` 直接用 `paramValues` 的原始 key（带前缀）预填。

## 三、待验证前提（已核实）

1. 绑定/记忆数据源名即 `LocalDataSource.getName()` 原名（显示名与数据源名一致）——成立（与本次改动无强依赖，仅用于确认 source 语义）。
2. 多实体同名列（如 `order.status` 与 `user.status`）确实存在歧义，裸列名不能猜——成立，保留 fail-closed。
3. 导入侧两种形态都要接受（旧脚本/习惯不受影响）——成立。

## 四、方案与改动

- `resolveSource` 增第 4 级「末段唯一匹配」：`status` 在全部 source 的末段中唯一时命中 `order.status`；多命中保持未匹配（不猜）。
- `serializeParamsToJson` 取最短唯一键：单表输出裸列名，歧义时保留完整 source。
- 测试：`SqlPreviewPanelImportTest` 增裸列名命中、歧义不猜、预填短键、歧义保留完整键四例。

## 五、验证读数

- 定向：导入面板 + 解析器 + 弹框 **59/0/0**（独立沙盒）。
- 反向：同时摘掉末段匹配与最短唯一键 → **恰 2 红**（裸列名导入、预填短键），恢复后全绿、探针零残留。
- 提交：`3cb4b49c`。

## 六、遗留与注意

- 参数行标签仍显示完整 source（`order.status`）——它是多实体消歧键与参数历史键；若要把标签也简化为裸列名，需要单独评估参数历史键口径（未做）。
- 真机复验待用户。
- 验收台账登记：UI-032。
