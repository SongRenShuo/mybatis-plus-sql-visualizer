---
name: project-preview-side-query
description: T-PreviewSideQuery 已实施（20261019，主提交 1a1cf724 + tooltip 4b81e09f）——预览面板只读动作改旁路查询不换编辑器；P1 内联结果、P2 EXPLAIN 非模态对话框、P3a 执行视图「返回预览」；读数 15 类 147/0/0/0
metadata:
  node_type: memory
  type: project
  originSessionId: session_7c4f9878c3e52efd1a834ad34e2354ac
---

# 预览面板只读动作不替换编辑器（T-PreviewSideQuery，2026-10-19 已实施）

> ⚠️ **2026-10-24 部分被 [[project-explain-console-first]] 取代**：EXPLAIN 的「直送」通道已从本档的
> `PreviewSideQuery` + 只读对话框（形态 B）改回**内嵌原生控制台**（形态 A）。原因：B 是插件自建
> JDBC 通道，取源/密码/方言/报错全落插件，而平台「选中即关联」让插件侧选源列表第二次起恒为单源
> （十二轮换源拉锯的根因）。本档描述的 P1 预估影响行数与回滚快照**仍在用 B**，P2 的 EXPLAIN 部分
> 已退役；`1a1cf724` 当初弃用 A 的理由（`currentEditorSql()` 漂移）已由 `previewSqlForReadonly()` 根治。

用户：「方案 3 落地，并且不止「预估影响行数」这个按钮有相同的问题 其他几个按钮可能也有相同的问题 一起生成开发计划文件先」→ 计划出台→「按照你的推荐拍板 实施开发计划中的所有内容」。计划文件 `docs/开发计划/20261019-1656-T-PreviewSideQuery-预览面板只读动作不替换编辑器-开发计划.md`，索引已登记并回填状态。

## 提交链（dev，均未 push）

- `1a1cf724` 主实施（16 文件 +800/-84）
- `4b81e09f` 内联行补试算详情 tooltip
- `3f27581b` 计划文档回填 SHA

## 根因（回答类问题的标准口径）

Ultimate 下**预览编辑器就是原生控制台文档**（`showResult` 末尾 `prepareDefaultConsole` → `embedConsole(PREVIEW)`，控制台挂在 `mpsql-exec.sql` 上）。任何走 `SqlExecutor.execute`/共享控制台的执行都会覆盖预览 SQL；`currentEditorSql()` 优先读 `nativeEditorDoc` ⇒ 再点「执行 SQL」会执行 COUNT/EXPLAIN 而非原 SQL（目标漂移）。所以只加自动刷新治标不治本。

## 实施形态

- **新增 `ui/PreviewSideQuery.kt`**：`sealed Result { Scalar(value, raw) / Table(raw) / Blocked(msg) / Failed(msg) }`；前置 `McpSelectGuard.checkSingleSelect`（`requireSelectGuard` 可关）+ `ExecutionDialectGate.fromDataSource/evaluate` 必须 MATCH，否则 Blocked；池线程 `McpDbExecutor.executeQuery`（`scalar` 时 maxRows=1，`channelNote` 供 Oracle/SQL Server），EDT 回调；`runnerForTest` 供 headless 打桩。
- **P1 预估影响行数**：`estimateDmlImpact` 不再 `SqlExecutor.execute`/`embedConsole`；目标取 `DataSourceSelector.boundConsoleDataSource()`（测试缝 `dmlImpactTargetForTest`），无绑定就地内联提示（**未实现**计划里的“弹数据源选择器后重试”）；结果写 `showDmlImpactResult(text, error, tooltip)` 到参数区底部 `impactResultLabel`（灰字/红字），tooltip 复用 `dml.impact.probe.body` 的试算 SQL/表/上限。旧 `clearImpactLoadingOnCancel` 与「取消转圈」测试随旧路径删除。
- **P2 执行计划**：新增 `ui/ExplainPlanDialog.kt`（非模态 `DialogWrapper`，只留 cancelAction；`createCenterPanel` 包 JBScrollPane；`explainDialogSinkForTest` 测试缝）。结构化改 `ExplainPlanDialog(proj, view)` + `showExplainDialog`，**`embedExplainView` 已删除**；直送/回退改 `PreviewSideQuery(scalar=false, requireSelectGuard=false, channelNote=ExplainSupport.entryFor(方言)?.postPlanQuery)` → `showExplainRawResultDialog`（只读 JBTable）或 `showExplainErrorDialog(msg, onOpenConsole)`。原控制台链保留为 **`submitExplainDirectConsole`**，仅由「在控制台打开」与结构化视图 `onBackToConsole` 显式调用。
- **P3a 执行 SQL**：`embedConsole(EXECUTE)` 在 `consoleHost` NORTH 加 `executeViewHeader()`（灰字说明 + 「返回预览」按钮 → `refreshView()`）；`detachConsole` 改为 `consoleHost.removeAll()`。P3b 页签式结果区**未做**（下一期）。

## 测试与守卫同步（改这些文件时别撞）

- 新增 `DmlImpactSideQueryPanelTest`（断言 `nativeEditorDoc`/`currentEditorSql`/`embeddedConsole` 不变、内联行含行数与 tooltip）与 `ExplainSideQueryPanelTest`（EXPLAIN 不改编辑器、经对话框展示）。
- `ExecutionDialectGateTest`：`estimateDmlImpact` 断言从「1 处 SqlExecutor.execute」改为 **0 处 + 含 `PreviewSideQuery.run(`**；删掉 `影响面预演取消必须解除loading`。
- `ExplainPanelWiringGuardTest`：测试名改为「非模态对话框展示不替换编辑器」，断言 `ExplainPlanDialog(`/`showExplainDialog(` 且 **不得含 `embedExplainView`**。
- `PrepareSingleFlightGateTest`：`embedConsole(..., EXECUTE)` 计数 6→**5**（预估与 EXPLAIN 直送不再入控制台）。
- `ConsoleHostTest`：`testReembedReplacesOldChild` 改为按 identity 找子组件 + `componentCount==2`（头+控制台）；新增「返回预览」断言。
- `GuardSelfTestCoverageMetaGuardTest`：顺手补登记了上一会话遗留的 `DialectStatusRefreshWiringGuardTest`（否则元守卫红）。

## 读数

15 类合计 **147/0/0/0**。反向：P1 改回 `embedConsole` → 恰 2 红；P2 删结果对话框 → 1 红；P3a 删执行视图头部 → 2 红（`ConsoleHostTest` 两条）。

## 后续轮次补充（2026-10-19）

- **P1 补上「无绑定 → 弹选择器 → 绑定后自动重试」**（`a7cb698a` 预估影响行数无绑定数据源时复用选择器绑定后重试；`a93bf7ca` 返回预览移除执行视图头部；`df480301` 不可试算时覆盖旧内联结果），上面「未做」第 2 条已不成立。
- **UI-033**：回滚弹窗「执行快照查询」原来走 `executeAdhocSql`（会换掉编辑器），已改走 `PreviewSideQuery` 并自动把结果转 TSV 填入快照框 + 直接生成回滚——**同一条原则「只读动作一律走旁路」现在覆盖到回滚快照**。
- **UI-034（`d65fb457`）**：结果不再只在左下角灰字——新增顶部独立蓝色加粗横幅 + 删除探针预演通知 + 底部「复制 SQL 并关闭」+ 返回预览主行动化，详见 [[project-dml-impact-prominence]]。

- **UI-035（`d201aa52`）**：执行计划对话框「在控制台打开」在无绑定数据源时改为「打开 Database 工具窗口」（`DataSourceSelector.openDatabaseToolWindow`），且**错误对话框动作后自动关闭**——旧实现点按钮触发一次必然失败的执行、弹窗还不关，用户看到的字面就是「没反应」。这条也为 P2 的 `showExplainErrorDialog` 增加了 `onOpenConsole`（默认空实现）+ `openDatabase` 两个可选参数。

## 未做/待真机

- 真机走查未做（需重建沙盒 jar + 重启 runIde）。
- ~~无绑定数据源只就地提示，无自动选择器重试~~（`a7cb698a` 已补）。
- `McpDbExecutor` 真连库端到端未在 headless 跑（测试全用 `runnerForTest` 桩）。

相关：[[project-dml-impact-editor-replacement]]（现象与机制）、[[pitfall-partial-submission-loading-stuck]]（旧路径的转圈坑，已随路径删除）、[[project-fieldfill-panel-ux]]（同轮 UI 定案）、[[feedback-all-plan-content-means-implementation]]。
