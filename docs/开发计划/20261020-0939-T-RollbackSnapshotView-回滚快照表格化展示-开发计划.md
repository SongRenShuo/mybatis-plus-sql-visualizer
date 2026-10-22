# T-RollbackSnapshotView：回滚快照表格化展示

2026-10-20。来源：用户在 dev 分支真机验收回滚 SQL 时提出——快照（执行前数据）以原始 TSV 文本单行展示，列多时一行挤不下、横向看不全，列名与值的对应关系不直观。

## 一、现象与判定

- 自动快照查询结果（TSV：首行列名 + 数据行）直接塞进 `JBTextArea`，制表符在文本框里只表现为空白间隔；列一多就横向溢出，无法确认「哪个值属于哪列」。
- 手动粘贴路径同样只能看文本，粘贴后也没有可视化校验。

## 二、根因（代码证据）

- `RollbackSqlDialog.buildSnapshotBlock` 只挂了一个 `JBScrollPane(pasteArea)`；`runSnapshotQuery` 自动填充时只写 `pasteArea.text`。
- 文本视图是生成回滚的权威来源（`generateFromPaste` 解析 `pasteArea.text`），因此不能简单替换成表格。

## 三、待验证前提（已核实）

1. `RollbackSqlGenerator.parseSnapshotTsv` 可把文本还原为（列名, 行）——成立，表格与生成共用同一解析。
2. 自动查询结果 `McpExplainFormatter.RawResult(headers, rows)` 可直接装表——成立。
3. 文本必须保持为权威来源（手工粘贴仍走文本）——成立，表格只是展示视图。
4. 表格只读即可（生成/执行仍走既有按钮）——成立。

## 四、方案与改动

- 快照区改 `CardLayout` 双态：**表格视图**（只读 `JBTable`，列名 + 行值，`AUTO_RESIZE_OFF`）与**文本视图**（原 `pasteArea`，手工粘贴/编辑）。
- 自动查询成功后：写 TSV 到文本（保持权威）→ 装表格 → 切到表格视图 → 摘要显示「快照：N 行 × M 列」。
- 表头新增「表格视图 / 文本（粘贴/编辑）」切换按钮：切到表格时按当前文本重新解析，解析失败留在文本视图并提示。
- 手动粘贴并生成成功后也自动切到表格视图（可视化确认）。
- 文案键：`rollback.snapshot.view.table/text/tooltip`、`rollback.snapshot.summary`（zh/en 同步）。
- 测试：新增「表格列行与摘要 + 双态切换」用例；手动粘贴用例补「生成后切表格」断言。

## 五、验证读数

- 定向：回滚弹窗 + 面板 + 生成器 **91/0/0**（独立沙盒，`--no-configuration-cache`）。
- 反向：摘掉两处表格填充（自动查询/手动生成）→ **恰 2 红**（表格切换用例、手动生成用例），恢复后全绿、探针零残留。

## 六、遗留与注意

- 回滚 SQL 编辑器中的超长单行仍按代码编辑器横向滚动展示（本轮未加软换行；如需可另议）。
- 真机复验待用户（需重建插件包）。
- 验收台账登记：UI-036。
