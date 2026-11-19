---
name: pitfall-toolbar-refresh-before-addnotify-warn
description: 工具栏在 addNotify 之前调 updateActionsImmediately 是无操作，平台还记 WARN「toolbar manual update
  is ignored」——SqlLogPanel 构造期刷新属冗余，推荐清掉（本文含最小改法与验证点）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_76f10071-704d-480c-be5c-0d8ff4f786f3
---

# 工具栏 addNotify 之前手动刷新＝无操作 + 一条带我们 toolbar id 的 WARN

**平台判据**（2026-11-16 真机 2026.3 日志原文）：`'cn.song.mybatisplus.visualizer.log.toolbar' toolbar manual update is ignored. Newly created toolbars are updated automatically on `addNotify`.` ⇒ 组件树还没挂上时 `ActionToolbarImpl.updateActionsImmediately()` 直接被忽略，而且平台还打一条 `WARN` + `java.lang.Throwable` 记录调用点。

**本项目实例**：`log/SqlLogPanel.buildToolbar`（`:1009`）在构造期调 `updateSortButtonText`（`:2199`）→ `refreshSortAction`（`:2191`，体就是 `_toolbarRef?.updateActionsImmediately()`）；调用链 `buildTopPanel:994 → init:840 → SqlLogToolWindowFactory.createToolWindowContent:58`。首次创建 SQL 日志工具窗时打一条（2026-11-16 08:43:58 实测）。

**为什么它是纯冗余**：排序按钮文案/选中态由 `sortModeAction.update()` 写进 presentation（工具窗工具栏只渲染图标，当前方式由 tooltip「排序：<方式>」与菜单勾选承载），平台在 `addNotify` 时会自动刷一遍——构造期这次刷新不产生任何效果。

**推荐清掉的理由**（不是「有害」，是「读数被污染」）：验收口径一直有「我方 0 ERROR / 0 WARN」，这条带我们自己的 toolbar id，每份日志都得解释一次「这是平台节流的无害告警」，长期成本高于改动成本。

**最小改法**：`buildToolbar` 里去掉 `updateSortButtonText()`（`updateTypeFilterButton()` 同批核一下是否也刷工具栏）；兄弟面 `HistorySqlLogPanel.refreshNavToolbar()` 只在层级切换后调用，需核一遍有无构造期调用点。

**验证只需一次真机**：打开 SQL 日志工具窗，排序按钮 tooltip 显示「排序：<当前方式>」、菜单里当前方式带勾（`addNotify` 首次 update 出文案，本来就是这条路）。

**状态**：2026-11-16 用户问「toolbar WARN 推荐清吗」，答复推荐清、给了上述改法与验证点，**未实施**（当时 master 工作树有并行车道在途的 `src/main` 改动，跑 gradle 会把它的半成品编进读数 ⇒ 等其收口或另起 `.worktrees/` 做）。

**Why:** 平台把「组件未挂树时刷新」设计成静默忽略，我们却在构造期照着「先设状态再刷界面」的习惯写了一遍，结果只换来噪声。
**How to apply:** 见到 `toolbar manual update is ignored` 先看调用栈是不是构造期/未挂树；是就删这次刷新而不是加 try/catch 或换 `updateActionsAsync`；改完靠真机 tooltip/勾选验证，不必造单测。相关日志噪声归属见 [[reference-real-ide-2026.3-log-and-build-probe]]。
