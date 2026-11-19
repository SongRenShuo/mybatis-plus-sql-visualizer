---
name: pitfall-disposer-session-not-in-tree
description: 会话/监听持有者未注册进 Disposer 树 → IDE 退出报 Memory leak detected（插件被点名）；含 243
  平台三条 API 事实（Disposable 无 isDisposed 不能 override、Disposer.isDisposed
  已废弃、tryRegister 与树感知 dispose）与修法三步
metadata:
  node_type: memory
  type: project
  originSessionId: session_c36236063d4675c334523a578e1df7d3
---

# 会话未入 Disposer 树 → IDE 退出报内存泄漏（T-PreviewSchemaSessionLeak，20261023 已修 `ebfaf73f`）

**症状**：关沙盒 IDE 后 `log_runIdeHotSwap/idea.log` 出现 **24 条**
`SEVERE - #c.i.o.u.ObjectTree - Memory leak detected: '...PreviewSchemaHighlighter$Session@...'
(class ...PreviewSchemaHighlighter$Session) was registered in Disposer as a child of 'ROOT_DISPOSABLE'
(class com.intellij.openapi.util.Disposer$2) but wasn't disposed.`，并带 `Plugin to blame: SQL Visualizer`。
**计数口径**：`grep "Memory leak detected" <log> | sed "s/.*detected: '//; s/@[0-9a-f]*'.*//" | sort | uniq -c`
（注意消息里有两个 `(class ...)`，**前一个是泄漏对象本身**，后一个是 parent `Disposer$2`）。

**根因**：`Session : Disposable` 从未注册进 Disposer 树（`ui/annotate/PreviewSchemaHighlighter.kt` 内无任何
`Disposer.register`），而其内部字段 `Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)` 会把 Alarm 注册为它的子对象
⇒ 平台把这条**孤儿分支**挂到 `ROOT_DISPOSABLE` 下、退出时不 dispose。正常路径靠 `uninstall()` 主动销毁，但
**关 IDE 时仍打开的预览编辑器走不到那一步**（面板 dispose 不回头调 `disposeCurrentEditorOnly`；控制台编辑器那处
install 更无对称 uninstall）。泄漏的 Session 经 Alarm 反向持有 `Editor`，在被挂 ROOT 的整段时间里阻止其回收。
本次是"核实另一起报障的日志"时顺带发现的**独立缺陷**（与实体解析无关）。

**243 平台 API 事实（三条都会咬人）**：
1. `com.intellij.openapi.Disposable` **只声明 `dispose()`，没有 `isDisposed()`** ⇒「自带 disposed 标志」只能写成
   **普通方法**（`fun isDisposed(): Boolean = disposed`），`override fun isDisposed()` 编译不过
   （报 `Function invocation 'isDisposed()' expected`）。平台文档给「`Disposer.isDisposed` 已废弃」的官方替代正是
   这个「对象自带布尔标志」写法，并特别叮嘱**不要 override**。
2. `Disposer.isDisposed(disposable)` **已 `@Deprecated`**（诊断信息会被清理，动态插件卸载/大 GC 后不可靠）；
   官方替代＝注册进父树 / 对象自带布尔标志 / `Disposer.newCheckedDisposable()`。**生产代码里不许用它做守卫。**
3. `Disposer.register(parent, child)` 在「child 已注册到该 parent」或「parent 正在/已被 dispose」时**抛异常**
   ⇒ 可能落在关闭临界窗口的 install 应用 **`Disposer.tryRegister`**（parent 已 dispose 时返回 false 且不抛）；
   而 `register` 支持**改挂**（先把 child 从旧 parent 解除再挂新 parent，故"构造期先被挂 ROOT、随后 tryRegister 到 project"
   能收正）。
   另：`Disposer.dispose(d)` 是**树感知**的——移除节点并连带 dispose 子对象；**直接调 `d.dispose()` 只跑一半清理**
   （节点与子对象仍留在树里，正是本次泄漏的形态）。

**修法（三步，单文件 `PreviewSchemaHighlighter.kt`）**：①`install(editor)` 以 `editor.project` 为属主
`Disposer.tryRegister`（并留 `internal install(editor, owner: Disposable?)` 作测试缝）；②`Session` 加
`@Volatile disposed` + 普通 `isDisposed()`，`dispose()` 首行幂等短路；③`uninstall` 改
`if (!session.isDisposed()) Disposer.dispose(session)`（树感知销毁，连带 dispose 子 Alarm）。

**读数与诚实边界**：新护栏 `PreviewSchemaSessionLifecycleTest`（5 例）**5/0/0/0**；反向①摘掉 `tryRegister` →
**恰 1 红**（「属主 dispose 回收会话」那条）；**反向②摘掉幂等守卫 → 未变红**（`Disposer.dispose` 对已销毁对象不抛，
该守卫在本套断言下**不可观测** ⇒ 按防御性保留、**不登记为已验证**；要纳入验证须引入"dispose 调用计数"类测试缝）。
既有 `PreviewUnknownColumnAnnotatorTest` **41/0/0/0**（它**属 `testDbSchemaAnnotator` 桶、不在默认 `test` 内**）。
**用户明确要求本轮不跑全量门禁。**

**测试缝语义坑**：`hasLiveSessionForTest` 的正确语义＝「是否仍有已安装且未 dispose 的会话」（`uninstall` 会先把会话
移出 map ⇒「map 里没有」＝无活会话＝无泄漏风险）；若按"曾经安装过"写，会与 uninstall 的先移除行为冲突而**假失败**。

**计划文档**：`docs/开发计划/20261023-1557-T-PreviewSchemaSessionLeak-预览错列标注会话未入Disposer树致退出泄漏告警-开发计划.md`。

## 后续回归（20261023 同日晚，`ebfaf73f` 自身引入）：入树后不能再手工摘监听

**症状**：关预览面板时两条 SEVERE——
`Can't remove document listener (PreviewSchemaHighlighter$Session$documentListener$1@…)`
（`DocumentImpl.removeDocumentListener`）与 `Assertion failed`（`EditorImpl.removeEditorMouseMotionListener`
的 `LOG.assertTrue(success || isReleased)`），栈都在 `Session.dispose()`。

**根因（重复摘除，243 sources 实证）**：
1. `init` 里用的是**两参重载**：`editor.document.addDocumentListener(listener, this)` 与
   `editor.addEditorMouseMotionListener(listener, this)` —— 平台把它们各自的「摘除」注册成 Session 的**子对象**
   （`DocumentImpl.java:1003-1006` 的 `DocumentListenerDisposable`、`Editor.java:342-345` 默认方法里的 lambda）；
2. `dispose()` 里**又手动摘一次**；
3. `uninstall` 改走 `Disposer.dispose(session)` 后，`ObjectTree` 按**后序（子先父后）**销毁
   （`ObjectTree.java:127-137`「third, dispose in post-order (bottom-up)」）⇒ 子对象已摘，父对象再摘必失败。
   `runCatching` 拦不住——这两条本身是日志/断言，不是被抛出的普通异常。

**为什么之前不报**：`ebfaf73f` 之前 `uninstall` 直接调 `session.dispose()`，不经过树 ⇒ 子 disposable 从不运行，
手动摘除是唯一路径。**「修泄漏」把两条摘除路径同时启用了。**

**定则（修法）**：摘监听的职责**只归注册时的 `parentDisposable`**，`dispose()` 里删掉手动 `remove*Listener`；
只有 `RangeHighlighter` 这类非 Disposable 的对象仍需手动清。仓内早有同款明文定例：
`SqlPreviewPanelEditorLifecycle.kt:969-974`（T-RA-02，autoPopupListener）——这次是复发。

**验证口径**：`Session` 的 `install → uninstall` 全过程用 `LoggedErrorProcessor` 捕获日志并断言零 ERROR
（参照 `SqlPreviewPanelPrewarmWriteSafeTest` 的 RecordingProcessor 写法）；反向＝把手动摘除加回去必红。

关联：[[project-entity-name-scope]]（同一次「核实日志」里发现，但与实体解析无关）、
[[pitfall-sandbox-jar-staleness-and-window-enum]]（沙盒在役校验与干净复测现场）。