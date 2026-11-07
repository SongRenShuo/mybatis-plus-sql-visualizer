---
name: pitfall-editorholder-single-slot-console-eviction
description: 预览面板 editorHolder 是编辑器/原生控制台共用单槽，装配编辑器会把已嵌入控制台整块挤出显示链
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3045a9d4-9fa0-4752-affe-3a191dc8b96f
---

`SqlPreviewPanel.editorHolder` 是「预览编辑器」与「原生控制台 consoleHost」**共用且唯一**的显示槽。
`assemblePreviewEditor` / `showPreviewPreparingPlaceholder` 原本无条件 `removeAll()` 再挂自己的编辑器；
冷启首开预热超预算时，降级装配与迟到换装都**晚于** `embedConsole`，于是 `consoleHost` 整块被挤出显示链。

两个判据同时失效，导致「谁都不会修回来」：`consoleAttachedToMe()` 只查 `consoleHost.isAncestorOf(console)`
（宿主被摘下仍为 true）、`embedConsole` 同实例早退只查 `console.parent === consoleHost`。
真机症状＝点执行后预览面板仍是插件自建编辑器（带行号、无原生工具栏），结果只在 IDEA Database 控制台；
`[A49] mySplitBottom=null` 与 `sp.isShowing=false` 让 `installResultReclaimGuard` 永远静默。

**Why**：前一轮把它误判成「结果区组件被抽走」，加了三层补挂仍不生效——补的是一块不在显示链上的 SplitPane。

**How to apply**：显示槽只走 `mountEditorHolderContent`；所有权判据必须包含 `editorHolder.isAncestorOf(consoleHost)`；
真机判读锚点 `[M36] editor assembly kept native console mounted`。复现用例 `PreviewEditorAssemblyKeepsConsoleTest`。

**修复已落地（2026-11-03，未提交）**：`mountEditorHolderContent` 成为 `editorHolder` 唯一装配口
（控制台在位时保留 consoleHost，编辑器只作文档/状态载体）；`consoleAttachedToMe()` 与 `embedConsole` 的
`stillMounted` 都加 `editorHolder.isAncestorOf(consoleHost)`；`rehostConsoleForTest` 一并复位宿主；
`onNativeEditorReady` 不再盲清显示槽。读数：`PreviewEditorAssemblyKeepsConsoleTest` 4 例
**修复前 4 红 → 修复后 4 绿**；回归 137 例全绿（面板与结果换入 111 + ConsoleHost 9 + ConsoleStealRemount 3
+ PreparingPlaceholder 2 + PrewarmLateRecover 5 + 约定守卫 7）。

**真机部署（2026-11-03 用户令「你来帮我覆盖 jar」）**：装到
`%APPDATA%\JetBrains\IntelliJIdea2026.2\plugins\mybatis-plus-sql-visualizer\lib\mybatis-plus-sql-visualizer-2026.11.2.jar`。
必须先关 IDEA（运行中 jar 被锁），旧包备份到仓内 `build/plugin-backup/*.before-console-ownership`；
覆盖后校验＝sha256 与 `build/libs` 产物一致 + Python zipfile 读 `SqlPreviewPanelExecutionKt.class`
确认新锚点字符串在场（`unzip|grep` 会假阴性）。

**真机复验通过（2026-11-03 10:08 会话）**：日志出现 `editor assembly kept native console mounted`，
用户确认控制台与原生工具栏恢复显示——第一轮修复成立。**但同一次复验立刻暴露第二层缺陷**：
面板虽然显示了控制台，却「还没执行就先出现结果栏」「预览 A 后再预览 B 结果栏还是 A 的」——
那是另一个独立机制（DBN 复用同一个结果面板实例、新建控制台路径没清场），
见 [[pitfall-dbn-reuses-content-panel-across-consoles]]。

**判读顺序**：控制台整块不出现 → 查本文件（显示槽归属）；控制台在但结果区内容不对 → 查清场。

关联 [[project-embedded-native-console-scope]]。
