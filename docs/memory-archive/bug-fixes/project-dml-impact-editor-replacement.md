---
name: project-dml-impact-editor-replacement
description: 「预估影响行数」换掉预览编辑器的交互债——20261019 用户已选方案 3（内联结果、不换编辑器）并要求覆盖全部同类按钮；实施计划见 project-preview-side-query
metadata:
  node_type: memory
  type: project
  originSessionId: session_7c4f9878c3e52efd1a834ad34e2354ac
---

# 「预估影响行数」换掉预览编辑器：交互债 → 已定方案 3

## 状态（2026-10-19 更新）

用户先问「这个交互是否合理」，我给三个方案（不内嵌 / 自动恢复 / 内联结果）；用户回复**「方案 3 落地，并且不止「预估影响行数」这个按钮有相同的问题 其他几个按钮可能也有相同的问题 一起生成开发计划文件先」**。

⇒ **决策已定且已实施**（20261019，`1a1cf724`）。完整形态、提交链与读数见 [[project-preview-side-query]]（`T-PreviewSideQuery`）。本档只留现象与机制，避免两处重复。

> 下面「走完整执行链 → 覆盖预览 SQL」描述的是**修复前**的状态；现行代码里预估影响行数已改只读旁路查询 + 内联结果，EXPLAIN 改非模态对话框，执行 SQL 保留结果视图并带「返回预览」。

## 现象

点「更多 → 预估影响行数」后右侧预览编辑器被整体换成原生控制台，里面载入 COUNT 试算语句（`SELECT COUNT(*) FROM t_order WHERE id = ?`），结果网格正常。用户要接着编辑原 SQL 必须点「刷新」。

## 机制（回答类问题时的关键事实）

`estimateDmlImpact` 走完整执行链 `SqlExecutor.execute(... onConsole = { embedConsole(..., EXECUTE) })`。因为 Ultimate 下预览编辑器**本身就是原生控制台文档**（`prepareDefaultConsole` → `embedConsole(PREVIEW)`），所以：

1. 预览 SQL 被试算 SQL 覆盖；
2. `currentEditorSql()` 优先读 `nativeEditorDoc` ⇒ 再点「执行 SQL」会执行 COUNT 而非原 UPDATE（目标漂移）；
3. 左侧参数面板仍是原预览参数，与右侧 COUNT 混合；
4. 「刷新」(`refreshView()` → `renderSql()`) 是唯一回退手段。

功能本身成功，问题纯属「副作用式替换用户上下文」。

## 相关既有结论（都验证过，别反复解释错）

- 弹数据源选择框是**正常**的：显式执行链按设计不静默复用 last-used（`executeAfterPrepare=true` 走弹窗）；面板头部「(上次使用)」只是展示。
- 取消弹窗后「执行中...」永久转圈已修 `eca7aecc`，见 [[pitfall-partial-submission-loading-stuck]]。
- 方言闸「按 PostgreSQL 渲染但数据源是 MySQL」是 `canLosslesslyRerender = false` 的 fail-closed 正常结果；`"user"` 双引号在 MySQL 里是字符串字面量，判成 PG 不算误判。
- 「控制台准备超时」根因是组 Runner 包私有反射漏 `isAccessible`（已修 `0f06bcc4`），见 [[pitfall-reflection-invoke-visibility]]。
