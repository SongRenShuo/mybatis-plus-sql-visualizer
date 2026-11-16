---
name: project-copy-placeholder-marker-20261111
description: T-CopyPlaceholderMarker（20261111）——复制可执行 SQL 会带出 `{{…}}` 模拟占位，已加带计数头部标记并补上分享链；已合 master，复制仍不弹确认
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f586a157-4652-4c9d-b73f-14df449f2952
---

T-MbgExamplePreview 收尾复查时发现：预览产物里的 `{{表达式}}` 占位**不是 SQL 注释**，
`SqlCommentStripper.strip` 不会碰它，会原样跟着「复制可执行 SQL」进剪贴板，粘到库里只报语法错。

两条链的原始状态（2026-11-11 实测）：

- `CopyExecutableSqlAction.assembleProduct`：已有 `DOLLAR_RAW_MARKER`（`-- contains ${} raw segments
  (mock values)`），但对占位场景文案不准——产物里不是「mock value」，而是压根没填；
- `SqlShareManager.copyExecutableSql` → `ExecutableSqlFormatter.format`：**一条标记都没有**（缺口更大）。

**已实施（`2e553316`，计划 `999eabb8`，文档 `9f1160b8`，已合 master）**：

- `countSimulationPlaceholders(sql)`：按 `\{\{[^{}]*}}` 计数；
  `unresolvedPlaceholderMarker(n)`：`-- contains N unresolved ${} placeholders shown as {{...}}; replace them before executing`；
  `withPlaceholderMarker(sql)`：无占位原样返回（share 链复用）。
- `assembleProduct` 的 markers 改为**互斥**：有占位 → 只挂占位标记；否则沿用 raw 标记（同一件事不说两遍）。
- `Product` 增 `simulationPlaceholderCount`；share 链改调 `withPlaceholderMarker`。

读数：`CopyExecutableSqlActionTest` 14 例 + `InjectionGateTest` + `RuntimeSlotChainConsistencyTest` 全绿；
反向把 `if (placeholderCount > 0)` 改成恒假 → 恰 2 红。

**决策与边界**：否决三条——①复制时把 `{{expr}}` 换成 `/*expr*/`（产物「看起来能执行」而语义错位，
比显式占位更危险）②复制弹确认（弹窗克制只留给执行，T-IG-03 定过）③拒绝复制（用户常是想拿去手工补）。
不动执行/EXPLAIN（有确认框）；Markdown 分享链不加标记（它不承诺可执行）。
文案用英文与既有 marker 同语言（注释随产物走到 PR/聊天/工单，同一条 SQL 不混排中英）。

计划：`docs/开发计划/20261111-1205-T-CopyPlaceholderMarker-复制可执行SQL带出模拟占位-开发计划.md`。
关联：[[project-mbg-example-preview-20261111]]、[[pitfall-git-checkout-reverts-uncommitted-probe]]。