---
name: project-dialect-status-live-refresh
description: T-DialectStatusLiveRefresh（20261019 已实施
  b32fa19f）——方言状态行执行后不刷新（决策固化在 PreviewResult 不可变字段且 embedConsole 钩子只刷绑定行）的根因与
  A/B/C 修法；含 BOUND_DATASOURCE 优先级插入点与「仅升级」守卫
metadata:
  node_type: memory
  type: project
  originSessionId: sess_13bd728a-c47f-4204-a267-f528b9f57f9d
---

2026-10-19 用户真机验收 dev 时发现：MPJ Lambda 预览执行 SQL 成功后，头部仍停在「方言未验证：兼容按 MySQL 预览（候选方言不一致）」，**关开预览才变成「方言: MySQL（模块已学数据源）」**；同一刻绑定行已升级为「已绑定」。计划 `docs/开发计划/20261019-1505-T-DialectStatusLiveRefresh-…-开发计划.md`，按用户「ABC 全部实施」落地，提交 **`b32fa19f`**（15 文件 +430/−11）。

## 根因（两个叠加问题，回答类问题的标准口径）

1. **状态陈旧**：方言决策只在预览生成时算一次，固化进 `PreviewResult.dialectDecision`（不可变字段，`Dialect.kt:942` 生产 / `updateMeta` 渲染）。执行时提交闸 `DataSourceSelectorConsole` 已 `ModuleDataSourceBindingStore.remember(...)` 写入模块学习（存内存态 PersistentState，立即可读），但**面板没有重算入口**——`embedConsole` 的 O-96fix2 钩子只调 `refreshBindStateRow()`（绑定行），所以「已绑定」升级了、方言行不动。重开预览之所以好，是因为 `showResult` 走了一次全新的 `sourceDecision`。
2. **歧义本可消解**：项目内多数据源（jpa_demo/mpj_demo）时决策输入只有项目候选/模块学习/agent URL，**面板已绑定/上次使用的数据源不是独立输入**——而绑定源的名字就在手边（`boundConsoleDataSourceName` 反射 `getName()`），`DataSourceUrlProbe.urlByName` + `UrlSubprotocolDialects.dialectName` 可推出方言。

## 实施形态

- **B（决策信号）**：`DialectDecisionEngine` 增 `Source.BOUND_DATASOURCE` + `Input.boundDataSourceDialect`，优先级**插在 scope/idea 决策之后、learnedModule 之前**；`IdeaSqlDialectDecision`、`DialectRegistry.decision(...)` 参数透传。手动 / captured URL / 显式 scope（含 AMBIGUOUS、CONFLICT）全部先于绑定信号 return —— 测试逐条钉死「绑定信号不绕过 scope 分歧」。
- **A（刷新链）**：新增 `ui/DialectStatusRefresh.kt`
  - `DialectStatusRefreshPolicy.pickStronger(old, fresh)` 纯函数：**先判 fresh 是否 MANUAL/CONFLICT（是则拒绝，刷新链不伪造用户选择/安全阻断），再 `old == null` 短路，再判 old 是否 MANUAL/CONFLICT**——这三步顺序是反向验证抓出来的：`old==null` 放最前会让 CONFLICT 乘虚而入。
  - `boundDataSourceDialectForDecision()`：活控制台对象 → `ExecutionDialectGate.fromDataSource(...).dialectName`；否则记忆名 → `urlByName` → 子协议方言。
  - `refreshDialectStatusRow()`：池线程 `DialectRegistry.decision(project, moduleKey, boundDataSourceDialect)` → EDT 经 pickStronger → 只调 `applyDialectStatusSegment()`；`Disposer.isDisposed` 守卫。
  - `applyDialectStatusSegment()`：用面板缓存的 `metaCoreText` / `metaTooltipCore` / `metaTooltipSuffix` 重组方言段，`lastMetaBudget = -1` 后 `applyMetaTruncation()`——**不重跑 `updateMeta` 的警示/横幅/错误按钮全链**。
- **挂点**：`embedConsole` 既有 `invokeLater` 块里与 `refreshBindStateRow()` 并列调用（单点覆盖执行/预览/复用三链）；`rerenderForExecutionTarget` 换方言时**同步**升级 `currentDialectDecision`（RESOLVED/EXACT/BOUND_DATASOURCE，先于 `renderSql()`）——该路径 `copy()` 不带新决策，是计划里发现的同类缺口。
- **C（文案）**：`panel.meta.dialectStatus.ambiguous` 改可操作指引「项目候选方言不一致；执行一次 SQL 或手动指定方言即可确定」；新增 `panel.meta.dialectStatus.boundSource`（「已绑定数据源」/ "bound data source"）；`DialectPreviewStatusText.source()` 补映射（全枚举遍历测试会自动覆盖）。
- 面板新增 4 个分段缓存字段，`clear()` 同源复位（防跨预览残留）。

## 测试

- `DialectStatusRefreshPolicyTest` 6 例（升级/不降级/终态拒绝/同级换证据/null 旧值）；`DialectStatusRefreshWiringGuardTest` 4 例**源码守卫**（钩子与绑定刷新同拍且相距 <800 字符、重渲染先升级再 renderSql 且不得走异步刷新、updateMeta 消费面板决策、clear 复位）——纯策略测试证不了「执行后真的会刷新」。
- 读数：定向 4 类 **30/0/0**；方言族回归 7 类 **154/0/0**。
- 反向两轮各恰 1 红：摘「仅升级」守卫（`>`→`>=`）→ `weaker or equal decision never replaces`；摘 `embedConsole` 刷新调用 → wiring guard。恢复后全绿、`REVERSE-PROBE` 残留 0。

## 教训

- **「刷新」类需求先分清「数据层已更新」与「展示层没重算」**：本轮学习写入时机是对的（提交闸后），坏的是面板没有重算入口；不要一上手去改学习时机。
- 执行链**成功路径零日志**（showSqlDiff/loadState 之类）时，idea.log 静默不等于没执行。
- 同类「视觉状态与事实矛盾」的既有先例：O-96fix2 绑定行。新增同类状态行时，检查是否有对应的刷新钩子，而不是依赖用户关开面板。

## 后续（20261020，UI-040）

只重组状态行**不够**：绑定源给出更强方言时 SQL 文本仍是旧方言，于是「状态行说 A、文本 B、目标 C」并存，回滚/EXPLAIN 被方言闸挡住。T-DialectGateExit P1 已在该 EDT 段 `applyDialectStatusSegment()` 之后接 `rerenderIfBoundDialectDiffers(chosen)`（判据 `shouldRerenderForBoundDialect`：只认 `BOUND_DATASOURCE` + 方言不同 + 文本未被编辑）。详见 [[project-dialect-gate-exit]]。

相关：[[project-dialect-auto-inference-gap]]（O-145 决策引擎全貌与证据顺序）、[[pitfall-sandbox-jar-staleness-and-window-enum]]（真机读数前先核在役包）、[[project-preview-side-query]]（同为「预览面板状态与实际脱节」族）、[[project-dialect-gate-exit]]。
