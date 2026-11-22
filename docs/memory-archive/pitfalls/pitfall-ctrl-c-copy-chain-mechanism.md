---
name: pitfall-ctrl-c-copy-chain-mechanism
description: 平台 Ctrl+C 复制链机制——$Copy 取 COPY_PROVIDER、编辑器组件不提供它、JTextComponent 无选中
  copy 是 no-op、列表绑定来自 LAF；含 InputMap.keys()==null 假象与正确修复落点（焦点组件 WHEN_FOCUSED
  覆盖）
metadata:
  node_type: memory
  type: pitfall
  originSessionId: sess_10bc2b20-39f0-4bfc-a093-b70f38a18a49
---

**2026-10-08 用户真机复验：「问题已修复」——本档的修复落点（焦点组件 WHEN_FOCUSED 覆盖 Ctrl+C/Ctrl+Insert）经实机确认有效。**

2026-10-08 T-ClickPreviewCopyRestore 两轮排查沉淀（一轮修复被真机证伪后逐层核验源码得出）。**凡「复制快捷键失灵」类问题直接照此链逐环核对，别再从交互层猜。**

**一、按键分派链（243 平台源码 + keymap 实测）**

1. 默认 keymap 里 Ctrl+C / Ctrl+Insert **只绑 `$Copy` 一个动作**（`keymaps/$default.xml`，jar 内 grep `control C` 只有它），**没有** `Copy` / `EditorCopy` 的按键绑定。
2. `$Copy` = `com.intellij.ide.actions.CopyAction`，实现只有两步：从 DataContext 取 `PlatformDataKeys.COPY_PROVIDER`，取到就 `provider.performCopy(dataContext)`；**取不到直接 return（静默无动作）**，update 时 `setEnabled(false)`。
3. **编辑器组件不提供 COPY_PROVIDER**：`EditorComponentImpl.uiDataSnapshot(DataSink)`（243 源码核验）只 `sink.set(CommonDataKeys.EDITOR/CARET, …)`，全文 `CopyProvider` / `PlatformDataKeys` 命中 0。⇒ 编辑器焦点时 `$Copy` 不可用，事件落回 Swing 分派 → 编辑器组件是 `JTextComponent`，**自带的无选中 copy 是 no-op** → 用户看到的是「按了 Ctrl+C 毫无反应」。**这正是报障形态。**
4. 编排器（`EditorImpl$MyEditable`）确实实现了 `CopyProvider`：`performCopy` → `executeAction("EditorCopy")` → `ActionManager.getAction("EditorCopy")`（= `openapi.editor.actions.CopyAction`，final，继承 `EditorAction`）→ 用它自己的 handler。`EditorActionManager.setActionHandler(id, h)` 内部就是 `((EditorAction)getAction(id)).setupHandler(h)`，**运行期真替换**（`getHandler()` 返回替换后的 handler）——**但整条链只有在「有人先拿到 provider 并调 performCopy」时才被走到**；真机按键路径大概率不走它（见第 3 条）。包装 `EditorCopy` handler 的修复因此可能在真机上完全不触发。
5. 列表（`JBList`）：`installDefaultCopyAction` 只装 **ActionMap["copy"]**（`doCopyToClipboardAction` = 选中条目逐条 `itemToText` 后 `join("\n")` 写剪贴板）；**Ctrl+C 的按键绑定来自平台 LAF**（`List.focusInputMap` 已含 `control C → copy`）——headless 实测 `list.inputMap.get(KeyStroke.getKeyStroke("control C"))` 直接命中 `"copy"`。⇒ **列表 Ctrl+C 本就可用**，不需要插件补绑。

**二、`InputMap.keys() == null` 是假象，不能当「无绑定」判据**

`InputMap.keys()` 对**懒加载**的 UIResource InputMap 返回 null（未解析出条目），实测同一列表 `keys()` 为 null 而 `get(keystroke)` 命中 `"copy"`。**判键位绑定必须用 `get(KeyStroke)`**，或用平台查证路径见 [[reference-platform-artifact-keymap-lookup]]。我据此误判「列表无绑定」并加了冗余补绑，反向验证（摘掉后绑定仍在）才发现。

**三、正确修复落点**

要让「无选中也能复制整条」，必须**在焦点组件自身的 WHEN_FOCUSED InputMap 上覆盖** Ctrl+C / Ctrl+Insert（同名 `put` 覆盖 JTextComponent 的默认 no-op 绑定，无优先级问题）：
```kotlin
comp.actionMap.put(k, action)                       // 无选中复制全文；hasSelection 时只复制 selectedText
comp.inputMap.put(KeyStroke.getKeyStroke("control C"), k)
comp.inputMap.put(KeyStroke.getKeyStroke("control INSERT"), k)
```
keymap 侧若可达（provider 链通）会走 `EditorCopy` handler；两条路指向同一动作才稳，**不要只赌一条**。

**已落地为共享件（2026-10-08 T-SqlCopyShortcuts O-122）**：按本节结论抽出 `util/EditorCopyShortcuts`（编辑器侧接线，7 处接入：主预览面板 / 最近执行记录 / 屏蔽规则 / 回滚 SQL / SQL→Java 输入区 / 参数导入 / 单击预览浮层）与 `util/TextCopyList`（列表侧基类，`itemToText` 委派 `copyTextFor`；SqlEntryList 与最近执行记录列表共用）。**新增带 SQL 编辑器的界面一律调 `EditorCopyShortcuts.install(...)`，勿再手写 InputMap 绑定**（手写会漏 Ctrl+Insert / 选区语义）；新增 SQL 列表继承 `TextCopyList`（否则复制出渲染文本）。审查判据：`grep -rn "control C" src/main` 应只命中共享件一处。

**四、验证纪律（与 [[pitfall-fake-green-test-assertions]] 第 ⑬ 形态同源）**

headless 里「直调 handler」或「直调 `provider.performCopy`」都绿，**都不等于真机按键绿**——真机先经 IDE keymap 分派，可能根本走不到那两条链。判别性断言要落在**按键解析落点**上：`inputMap.get(keystroke)` 得到预期 key + 触发 `actionMap.get(key)` 后剪贴板内容正确；最外层「真实按键」仍须真机走查（见 [[runide-demo-verify-workflow]]）。
