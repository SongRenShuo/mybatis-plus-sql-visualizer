# T-RollbackSnapshotDsPrompt：快照查询无数据源直弹数据源选择器

2026-10-20。来源：用户在 dev 分支真机验收回滚 SQL 时提出——点「执行快照查询」时若未绑定数据源，只出现红字「未绑定数据源：请先在控制台执行一次查询完成绑定」，应当**直接让用户选择数据源**。

## 一、现象与判定

- 无绑定数据源时，快照查询直接终止并显示提示；用户被要求「先去控制台执行一次查询」，多绕一步。
- 同类场景（DML 影响面预演、EXPLAIN 直送）早已采用「弹数据源选择器 → 绑定后自动重试」的 T-UI030 链路；快照查询是遗漏（UI-037）。

## 二、根因（代码证据）

- `RollbackSqlDialog.runSnapshotQuery` 的 `target == null` 分支只写 `warningLabel` 后 `return`，未触发任何选择器。
- 可复用的既有链路：`DataSourceSelector.prepareConsole(project, sql, onError)`（`executeAfterPrepare=false`，只准备/绑定不提交）+ `SqlPreviewPanel.awaitDataSourceBoundThen(proj, generation, onBound, onGiveUp)`（T-UI030 同款轮询与代际守卫）。

## 三、待验证前提（已核实）

1. `prepareConsole` 只绑定不执行（`executeAfterPrepare=false`）——成立，不会误执行 SQL。
2. `ExplainFallbackGuidance.shouldPromptForDataSource(!prepareInFlight)` 可避免重复弹窗——成立。
3. `awaitDataSourceBoundThen` 的 generation 守卫可用面板 `explainStructuredGeneration`（预览切换即停表）——成立。
4. 取消/超时仍需收口提示——保留原 `rollback.snapshot.noDataSource` 文案。

## 四、方案与改动

- `runSnapshotQuery` 无数据源分支改为 `promptDataSourceThenRetrySnapshot(proj, sql)`：弹选择器（只绑定）→ 绑定成功后自动重试快照查询；取消/超时显示原说明文案；选择器报错就地显示错误。
- 新增测试缝 `snapshotPromptForTest` / `snapshotAwaitBindForTest`（生产分别走 `prepareConsole` / `awaitDataSourceBoundThen`）。
- 测试：原「无数据源提示」用例拆为「先弹选择器并自动重试成功」「取消后提示」两例。

## 五、验证读数

- 定向：回滚弹窗 + 面板 + 生成器 **92/0/0**（独立沙盒）。
- 反向：恢复旧「只报错不弹选择器」行为 → **恰 1 红**（弹选择器并重试用例），恢复后全绿、探针零残留。

## 六、遗留与注意

- 绑定等待期间对话框无进度提示（选择器自身是模态交互，可接受）；如需可补一行「正在等待数据源绑定…」。
- 真机复验待用户（需重建插件包）。
- 验收台账登记：UI-037。
