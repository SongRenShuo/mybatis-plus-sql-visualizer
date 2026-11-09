---
name: pitfall-dialect-status-row-stale-after-dialect-change
description: 改 IDEA 方言+刷新后底部方言红条仍报旧渲染方言——顶部读 currentDialectDecision（实时）、底部读 result.effectiveDialect（预览生成时固化）；已修：方言无关载体取最新决策 + 刷新重算证据
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

2026-11-04 用户真机（UI-004）：「我已经修改了 idea 的方言，为什么还提示：方言不一致：当前 SQL 按 PostgreSQL 渲染，所选数据源是 MySQL」。截图三处矛盾：顶部 `方言: MySQL（已绑定数据源）`、SQL 已按 MySQL 渲染、右上已绑定 MySQL 源，**底部红字仍写「按 PostgreSQL 渲染」**。

## 三条根因（全部证实）

1. **两个数据来源不同步**：顶部用 `currentDialectDecision`（随绑定/刷新实时更新）；底部 `SqlPreviewPanelExecution.kt:709 executionDialectGateRequest()` 里 `renderedDialectName = result?.effectiveDialect?.name ?: currentDialectDecision?.compatibilityFallbackName ?: MySqlDialect.name` —— **固化的 `effectiveDialect` 优先级更高**。
2. **XML 载体无法重渲染**：`result.xmlModel != null` → `canRerender = false` → `AUTO_RERENDER_REQUIRED` 降级成 `RERENDER_UNAVAILABLE`（key `execute.dialectGate.rerenderUnavailable`，生成点 `DataSourceSelector.kt:307`）。该文案恰好写着「…把方言改为 MySQL 后刷新预览」——**提示语引导的动作修不好它自己**，闭环失败。
3. **「刷新」按钮只重渲染文本**：`refreshView()` 只调 `renderSql()`，既不重算决策也不更新 `effectiveDialect`；用户改 IDEA 方言（不发事件）后刷新，顶部/底部都停在旧值。

## 已修（`f562a9ce`，合 dev `6f982d55`）

- 新增纯函数 `ui/DialectGateRenderDialect.kt`：**方言无关载体**（XML 原文 / 纯文本，排除分页 LIMIT 行、previewModel、batchModel）取**最新决策**；模型/批量/分页载体仍取渲染事实。
- `refreshDialectStatusRow` 增 `includeSourceFile`；`refreshView` 传 true，在池线程 `ReadAction` 内按与预览生成同一份证据重算，EDT 只做「仅升级」替换；MATCH 时清空红条。
- **原样保留两条定案**：`canLosslesslyRerender=false`（禁字符串替换冒充重渲染）；`MANUAL_CONFLICT` / `RERENDER_UNAVAILABLE` 在 `allowDialectGate` 侧放行不拦执行（用户 2026-10-25 定案「方言不能影响最终的执行」）。

## 临时绕过（未升级时）

关掉预览窗口**重新打开**（重新生成 `PreviewResult` 让 `effectiveDialect` 按新方言算），而不是点面板里的「刷新」。

**可推广判据**：同一个事实在两个位置各读一份来源（实时决策 vs 生成时快照）时必然漂移；「头顶新、脚下旧」先去找这两个来源的赋值时机，而不是去查渲染。

相关：[[project-dialect-gate-ux]]（红条双蓝字入口）、[[project-dialect-gate-release-on-conflict]]、[[pitfall-preview-open-pops-datasource-picker]]（同分支同批修复）、[[dev-vs-master-262-acceptance-20261104]]。
