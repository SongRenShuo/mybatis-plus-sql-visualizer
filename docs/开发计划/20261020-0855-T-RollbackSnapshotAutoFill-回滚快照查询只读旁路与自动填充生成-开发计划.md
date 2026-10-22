# T-RollbackSnapshotAutoFill：回滚快照查询只读旁路与自动填充生成

2026-10-20。来源：用户在 dev 分支真机验收 U37/回滚 SQL 时发现——点「执行快照查询」后 SQL 执行在了 SQL 预览窗口（编辑器被切成执行视图），回滚弹窗下方的快照框仍为空。

## 一、现象与判定

- UPDATE 无法自动回滚时进入快照工作流：弹窗给出 `SELECT * FROM t_order WHERE id = 1`，点「执行快照查询」。
- 旧实现直接调用 `panel.executeAdhocSql(sql)`：预览编辑器被替换成执行视图（显示快照 SELECT 与结果网格），结果只在控制台网格，快照框不自动填充，用户须从另一个窗口手工复制粘贴。
- 与 T-PreviewSideQuery 已确立的原则（只读动作走旁路、不替换预览编辑器）不一致（UI-033）。另发现整行等值定位告警文案写死「DELETE」，UPDATE 场景误标。

## 二、根因（代码证据）

- `RollbackSqlDialog.runSnapshotQuery()` 旧实现只有两行：`currentResult.snapshotSelectSql?.let { panel?.executeAdhocSql(it) }`。
- `executeAdhocSql` 会把控制台换入预览编辑器（T-PreviewSideQuery 已把影响行数/EXPLAIN 迁走，快照查询漏迁）。
- `rollback.warn.fullRowPredicate` 文案写死 DELETE，但 REVERT_INSERT/REVERT_UPDATE 共用。

## 三、待验证前提（已核实）

1. `PreviewSideQuery` 可作为快照查询通道：只读 SELECT 过 `McpSelectGuard`，再按绑定数据源过方言闸——成立（与 DML 影响面预演同口径）。
2. 结果结构 `McpExplainFormatter.RawResult(headers, rows)` 可直接转 TSV 供 `parseSnapshotTsv` 消费——成立。
3. 空主键列时 `generateFromSnapshotRows` 走整行镜像并可生成——成立（会带整行定位风险告警）。
4. 无绑定数据源/空结果需要明确提示，不能静默——成立，新增提示文案。

## 四、方案与改动

- `runSnapshotQuery` 改走 `PreviewSideQuery.run(scalar=false, requireSelectGuard=true)`：不换入控制台、不替换编辑器；方言闸请求按预览实际方言构造（与 DML 影响面预演同口径）。
- 结果网格转 TSV 自动填入快照框并直接调用 `generateFromPaste()` 生成回滚。
- 阻断/失败/空结果在对话框内提示（`panel.explain` 无关，新增 `rollback.snapshot.noDataSource` / `emptyResult`）；「复制快照 SQL」+ 手工粘贴保留为降级通道。
- 新增测试缝 `snapshotQueryTargetForTest`；告警文案改为中性「未提供主键列时整行等值定位」。
- 测试：`RollbackSqlDialogTest` 增旁路自动填充、编辑器/控制台不被替换（runner 判别断言）、无数据源提示、空结果提示四例。

## 五、验证读数

- 定向：回滚弹窗 + 面板 + 生成器 **90/0/0**（独立沙盒）。
- 反向：恢复旧 `executeAdhocSql` 路径 → **恰 3 红**（自动填充、无数据源提示、空结果提示），恢复后全绿、探针零残留。
- 提交：`8c54189a`。

## 六、遗留与注意

- 主键列留空时自动生成走整行等值定位并显示风险告警；填主键列后重新生成可得到精确 WHERE。
- 真机复验待用户（需重建插件包）。
- 验收台账登记：UI-033。
