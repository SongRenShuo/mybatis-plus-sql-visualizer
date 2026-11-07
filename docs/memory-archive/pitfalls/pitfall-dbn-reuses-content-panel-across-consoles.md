---
name: pitfall-dbn-reuses-content-panel-across-consoles
description: DBN 对同一 exec 文件复用同一个 RunnerLayoutUi 结果面板实例，旧 Result 页签跨预览残留——未执行先出结果栏、换预览结果不隔离；修法＝预览路径清场
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3045a9d4-9fa0-4752-affe-3a191dc8b96f
---

`JdbcConsole` 每次 `attachConsole` 都是新对象，但 `RunnerLayoutUi.getComponent()`（结果区那个
`ContentManagerImpl$MyNonOpaquePanel`）对**同一个 exec 文件**是复用的：真机日志里两次预览
`resultComponent=…MyNonOpaquePanel[,0,263,889x248…]` 与 `[,0,315,889x196…]` 的 identity 同为
**id=839493854**，且首次取到时**已经带布局尺寸**——这就是「不是新建的」的判据。

后果两条（用户 2026-11-03 原话）：

1. **还没执行就先出现结果栏**：旧的 `Result 1` 页签还在，`resultAreaHasContent(ui)` 为 true，
   `ConsoleEmbedMode.PREVIEW.shouldCollapseDivider(false→true)` 失效 → divider 停在 0.6、结果区常驻。
2. **换一条 SQL 再预览，结果栏还是上一条的**：清场只发生在复用路径（`reuseSharedConsole` 里的
   `clearResultContents` + `clearConsoleHistory`），**新建控制台路径（`prepareAfterEditorResolved`）一直缺**。

**How to apply**：预览准备链在拿到 `ui` 之后、组 split 之前按 `!executeNow` 清场
（`clearResultContents(ui, LOG)` + `clearConsoleHistory(console, LOG)`）。执行路径不清——
结果马上就到，清空会让 DBN 回显断链（`clearConsoleHistory` 的 KDoc 已写明该约束）。
清场只摘非 Output 页签，Output/Tx 保留。

**取证**：`[M36] resultComponent=…` 日志的 identity + bounds；两次 `attachConsole` 之间 id 不变即复用。

**Why 容易漏**：这个缺陷只有在「控制台终于能正常显示」之后才可见——第一轮修的是显示槽归属
（[[pitfall-editorholder-single-slot-console-eviction]]），修好之后才轮到结果区内容对不对。
排查顺序：整块控制台不出现 → 查显示槽；控制台在但结果区内容不对 → 查清场。

**How to apply（判读）**：`[M36] result contents cleared (preview switch, removed=N)` 应在每次预览准备时出现；
`removed=0` 且用户仍看到旧结果 ⇒ 该路径没走到清场。

## 清场之后仍常驻：还有第二层（2026-11-03 第二轮真机）

清场生效（日志 `removed=0/1` 都在）后，结果栏**仍然占着下半屏**——那已经不是内容问题，是
divider 被结果区 160px `minimumSize` 钳住（`setDividerLocation(1.0)` 推不到底）。详见
[[pitfall-jsplitpane-divider-clamped-by-minimum]]。排查顺序因此是三段：
**整块控制台不出现 → 显示槽；结果区内容不对 → 清场；清场对了但栏还在 → divider 钳制。**

## `clearConsoleHistory` 在 262 上必失败（同轮实测）

日志每次预览都 WARN `clear console history FAILED: com.intellij.openapi.editor.impl.DocumentImpl.setText(java.lang.String)`
——裸反射 `setText` 不在写命令里，撞 262 的文档写断言，被 `runCatching` 吞成 WARN，Output 页签
一直留着上一轮 SQL 的回显。修法：`clearConsoleHistory(project, console, LOG)` 增 project 参数，
`WriteCommandAction.runWriteCommandAction(project) { ... }` 内写；失败日志改打 `e.cause?.message`
（InvocationTargetException 的 message 只有方法签名，真因在 cause）。

关联 [[project-embedded-native-console-scope]]。
