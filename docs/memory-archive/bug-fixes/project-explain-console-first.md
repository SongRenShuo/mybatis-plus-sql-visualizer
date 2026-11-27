---
name: project-explain-console-first
description: T-ExplainConsoleFirst（20261024 已实施，`6e6da1d4` 功能 + `f3abe99a` W6 + 两笔文档，未 push）——执行计划默认/回退从「旁路 JDBC + 报告对话框」改回**内嵌原生控制台**（初版形态 A，代码一直活着）；根治 `currentEditorSql()` 漂移用 `previewSqlForReadonly()`；**用户拍板「结构化通道保留、默认关」**，W6 把「启用结构化并重试」推荐通知改成只提示一次（`explainStructuredOfferedShown`）；定向 124/0/0/0 与 83/0/0/0，反向五探针各恰 1 红
metadata:
  node_type: memory
  type: project
  originSessionId: session_b380d49669e298cff6e310c72afca927
---

# 执行计划回到预览窗内嵌原生控制台（T-ExplainConsoleFirst，2026-10-24）

用户提问：「如果这个执行计划直接在 sql 预览窗口里执行，是不是就没有那么多问题了，并且右上角还有蓝色的返回预览按钮，你分析下这个的可行性，也是初版执行计划的实现方式」。

## 三条链的现状（回答类问题的标准口径）

| 形态 | 入口 | 执行者 | 取源/密码/方言/报错归属 |
|---|---|---|---|
| **A 内嵌原生控制台**（现默认） | `submitExplainDirectConsole` → `SqlExecutor.execute` + `embedConsole(EXECUTE)` | 平台控制台 | **全部平台负责** |
| B 旁路 JDBC + 只读对话框（EXPLAIN 已退役，仍服务预估影响行数与回滚快照） | `PreviewSideQuery` → `McpDbExecutor` | 插件 | 插件自己负责 |
| C 结构化计划表格/JSON 树（`ExplainPlanDialog`） | `submitExplainDirectConsole` 之外走 `ExplainPanelChannel` | 插件 | 插件自己负责，**默认关** |

A 就是 `1a1cf724`（2026-10-19）之前的初版，代码一行未删；`1a1cf724` 把默认改成 B 的真实原因写在 commit message 里：「会把预览 SQL 顶掉，并让 `currentEditorSql()` 漂移到试算/EXPLAIN 语句」。

## 为什么 A 能一次性消掉十二轮换源拉锯

B/C 是插件自建 JDBC 通道 ⇒「用哪条源、密码从哪来、方言对不对、失败怎么呈现」全落插件；而平台「选中即关联」（`getForcedDataSourceForExec` 命中即 `singletonList` 短路枚举、无公开清除 API）让插件侧选源列表第二次起恒为单源——这是 W1~W12 全部拉锯的根因（见 [[project-explain-fail-ux]] 与 docs 经验总结 `20261024-1914`）。A 把这些整类交回平台：换源走内嵌控制台自带的原生 `Data Sources` 菜单，密码走平台凭据链，报错在控制台原生呈现。

## 实施（`6e6da1d4`，8 文件 +501/−268）

- **W1 分派改形**：`submitExplainDirect` 收成三段＝Schema 校验 → 零源判定 → `submitExplainDirectConsole`。零源判定用 `DataSourceSelector.registeredDataSourceCount(project) > 0`（该函数此前已存在但**零调用**），**插件不枚举/不筛/不猜**。删掉整段 `PreviewSideQuery.run` 与 `Result.Table/Blocked/Scalar` 四分支分型。`showExplainRawResultDialog` 零引用后删除（四个 import 一并清）。
- **W2 偿债（本任务实质工作量）**：新增 `previewSqlForReadonly()`——`executeViewHeader != null`（执行视图在场）时读 `lastRenderedSql` 快照，否则读真实编辑器。依据：执行链写 `mpsql-exec.sql` document **不经过 `showSql`**，故 `lastRenderedSql` 恰是最后一次预览的 SQL。五处只读动作改用它：`showRollbackSql` / `exportReplayScript` / `copyAsAiContext` / `exportPortableSql` / `estimateDmlImpact`。**`executeSql`/`explainSql` 不改**（执行链必须执行用户手改的真实文本）。
- **W3 契约反转**：`ExplainSideQueryPanelTest` 从「不得换入控制台」反转为「必须交给执行链」（用 `SqlExecutor.executionAttemptForTest` 截获）；新增 `PreviewReadonlySnapshotTest` 四例，其中一例**专门对照断言 `currentEditorSql()` 已漂移**，证明测的是真问题。
- **W4 守卫**：新增 `W2_只读动作取词必须走预览快照`、`W1_执行计划直送不得回流旁路查询`。三条旧守卫按新契约修订而非删除——AUTH / WRONG_TARGET 分支条数断言 `>=2`→`>=1`（直送链那处随 B 退役），呈现纪律不变；「直送链取源接模块学习回落」改写为「直送链不得再做插件侧选源」。

## 读数与反向

定向 10 类 **124/0/0/0**；反向三探针**各恰 1 红**（1 把 `showRollbackSql` 取词改回 `currentEditorSql`；2 在 `submitExplainDirect` 内插入真实 `PreviewSideQuery.run` 调用；3 把 `previewSqlForReadonly()` 改成直返 `currentEditorSql`）。探针 2 首版写成编译不过的形态（`proj` 不匹配），**那次不算数**，重写为可编译形态后才取到读数。提交态 `compileKotlin`/`compileTestKotlin` 双通过。

## ⚠️ 用户「点击执行计划还是弹窗了」的真因（务必先查这一条）

**不是本改动失效**。沙盒持久化设置里写着 `explainStructuredRender=true`：

```
<intellijPlatform/sandbox/.../config/options/mp-sql-visualizer.xml>
  <component name="MpSqlVisualizerSettings">
    <option name="explainStructuredRender" value="true" />
```

代码默认 `false`（`PluginSettings.kt:550`），但早前某次点通知里的「启用结构化并重试」会**永久写入**该设置（`enableStructuredExplainAndRetry`）且不会自己改回来。为 true 时 `submitExplainOrStructured` 走 `Decision.Structured`（形态 C，计划表格 + `ExplainPlanDialog`），与本任务改的 `submitExplainDirect` 无关。

切回控制台：设置页 `Tools → MyBatis-Plus SQL Visualizer → 预览` 取消「预览面板内结构化渲染 EXPLAIN（实验特性，默认关闭：走原生控制台）」，或在关机状态下改 xml 的 value 为 `false`。详见 [[pitfall-persisted-experimental-setting-changes-path]]。

## 留给用户的取舍（我未擅自决定）

结构化通道（C）未动、默认仍关。三个选项已摆给用户：①保留结构化默认关；②干脆退役结构化通道（失去计划表格/索引建议/跨次 diff）；③保持现状。等用户拍板。

## W8：沙盒验收抓出「切回旧通道就复活的既有慢操作」（`6aac532a`）

沙盒 243 点「执行计划」刷 **9 条** `SEVERE - SlowOperations ... Plugin to blame: SQL Visualizer`：

```
explainSql → submitExplainDirectConsole → SqlExecutor.execute → chooseAndPrepare
  → createTempPsiFile → PsiManagerImpl.findFile
```

即 `createTempPsiFile` 的**冷路径**（VFS refresh + `PsiManager.findFile`）在 EDT 上跑。

**为什么现在才暴露**：`1a1cf724` 之后 EXPLAIN 走旁路查询、不经控制台链；W1 把它切回控制台，
这条既有冷路径重新进入热路径。既有预热 `prewarmExecTempFile` 只服务「预览 SQL」
（`mpsql-preview-*`），EXPLAIN 文本从未预热过——两个是不同的文件。

**修法**：`submitExplainDirectConsole` 进链前把**本条 EXPLAIN 文本**交后台预热
（`prewarmExecTempFile` 自带 EDT 拒绝、预算等待、并发去重），`get(8s)` 有界等待后进链。
新常量 `EXPLAIN_EXEC_PREWARM_BUDGET_MS = 8_000L`（不用既有的 30s——那是后台链上限，
不该成为 UI 交互等待窗）。预热失败只回落冷路径，不阻断执行。

**实测：9 条 → 0 条**，日志有 `[M36] exec prewarm DONE (cache filled…)` 佐证。
守卫 `W8_执行计划进链前必须预热exec文件`（断言预热在 `SqlExecutor.execute` 之前、投在池线程、
预算是有界常量），反向删预热恰 1 红。

### 教训（可复用）

**「把某功能切回一条老通道」时，必须把那条老通道当作**从未被验收过**来重新走一遍日志。**
`1a1cf724` 把 EXPLAIN 迁走之后，控制台链只剩普通执行在用；这期间新增的 EDT 慢操作守卫
（`SlowOperations`）与这条件冷路径的组合从未有人看过。切回去的第一件事应是读日志而不是看功能。

## 边界

- GUI 已走查（沙盒 243）：选源弹 5 个源 → 建会话 → attach → 内嵌控制台 → 提交 → 出结果；
  插件归因 SEVERE **0 条**。
- 全量 `testShards` 与 `verifyPlugin` 未跑（不碰 build 脚本/plugin.xml/依赖，按三档分层不触发构建校验）。
- `currentSortLabel` 类微优化的独立收益未单独测量（同批提交）。

相关：[[project-preview-side-query]]（形态 B 的原始设计与读数）、[[project-explain-fail-ux]]（为什么换源必须交还平台）、[[project-explain-pwd-prompt]]（askPassword 按通道表态）、[[project-module-scoped-last-used-and-log-bgt]]（同批合并）。
