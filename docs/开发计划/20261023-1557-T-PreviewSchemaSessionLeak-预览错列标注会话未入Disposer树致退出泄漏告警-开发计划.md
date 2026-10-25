# T-PreviewSchemaSessionLeak：预览错列标注会话未入 Disposer 树，IDE 退出时报内存泄漏（开发计划）

**创建时间**：2026-10-23 15:57
**触发源**：用户真机沙盒复测（同一轮 T-ShadowEntityResolve 收尾）——问题已修复，但用户要求「核实日志」时，
在关 IDE 的日志中发现 **24 条** `Memory leak detected` 告警，全部指向同一类。
**基线**：`master@1b61964f`（分析时 tip；相关特性为 UI-025「预览编辑器错列 Schema 标注」）。
**状态**：已实施（本文档与实现同批提交）。**未占 O/R/D/V 编号**（缺陷级修复）。
**门禁**：按用户 20261023 明确要求，**本轮不跑全量 `testShards`**；验证只做定向。

---

## 一、现象与证据

关 IDE 时 `idea.log`（沙盒 `log_runIdeHotSwap/idea.log`，用户实际使用路径）出现 24 条：

```
SEVERE - #c.i.o.u.ObjectTree - Plugin to blame: SQL Visualizer version: 2026.10.23-beta.2
SEVERE - #c.i.o.u.ObjectTree - Memory leak detected: '...PreviewSchemaHighlighter$Session@1716dbc0'
  (class cn.song.mybatisplus.visualizer.ui.annotate.PreviewSchemaHighlighter$Session)
  was registered in Disposer as a child of 'ROOT_DISPOSABLE' (class com.intellij.openapi.util.Disposer$2)
  but wasn't disposed.
（同型共 24 条，泄漏对象类全部是 PreviewSchemaHighlighter$Session）
```

读数口径：`grep "Memory leak detected" <log> | sed "s/.*detected: '//; s/@[0-9a-f]*'.*//" | sort | uniq -c`
⇒ `24  cn.song.mybatisplus.visualizer.ui.annotate.PreviewSchemaHighlighter$Session`。

影响：每次退出都刷 24 条 SEVERE 并把插件点名，**淹没日志里的真实问题**；泄漏的 Session 还经 Alarm
反向持有 `Editor`，在被平台挂到 ROOT 的整段生命周期内阻止其回收（同型陷阱见本仓既有约定：
「子面板须注册到父面板/弹窗 disposable 树」）。

## 二、根因（代码级，可证）

`src/main/kotlin/.../ui/annotate/PreviewSchemaHighlighter.kt`：

1. `private class Session(private val editor: Editor) : Disposable`，内部持有
   `private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)`（`Session.kt:98-100`）。
2. **该文件内没有任何 `Disposer.register`** —— `Session` 从未进入 Disposer 树；它只被放进
   `WeakHashMap<Editor, Session>`（`sessions`）并以 `uninstall(editor)` 手动销毁。
3. `Alarm(..., parentDisposable = this)` 会把 Alarm 注册为 Session 的子对象；**Session 自身不在树里**，
   平台遂把这条孤儿分支挂到 `ROOT_DISPOSABLE` 下 → 关 IDE 时未被 dispose → ObjectTree 报泄漏。
4. 正常路径确实会销毁：`SqlPreviewPanel.disposeCurrentEditorOnly()`（`SqlPreviewPanelEditorLifecycle.kt:987`）
   在释放编辑器前调 `uninstall(it)`。**但「关 IDE 时仍打开的预览编辑器」走不到这一步**（面板 dispose
   不会回头调 `disposeCurrentEditorOnly`），另有 `SqlPreviewPanelExecution.kt:1721` 对控制台编辑器的
   install 没有对称的 uninstall 调用点。⇒ 退出时必然残留。

平台契约（243 sources `com/intellij/openapi/util/Disposer.java`，三处与本修法直接相关）：

- `register(parent, child)` **在 child 已注册到该 parent、或 parent 正在/已被 dispose 时抛异常**；
- 有 `tryRegister(parent, child)`：parent 已 dispose 时不注册并返回 false；
- **`Disposer.isDisposed(disposable)` 已标 `@Deprecated`**，文档明说其信息会被清理（动态卸载/大 GC）不可靠，
  并给出替代：注册进父树 / 或用对象自带布尔标志 / 或用 `Disposer.newCheckedDisposable()`。
  ⇒ **修法里不得用 `Disposer.isDisposed` 做守卫**。

## 三、修法（按推荐：注册进父树 + 自身可判定 disposed）

1. **把 Session 注册进父树**：`install(editor)` 内部以 `editor.project` 为属主
   `Disposer.tryRegister(owner, session)`（project 必在树中；`tryRegister` 在项目正在关闭时不抛异常）。
   这样 Alarm 正确落在 `session → project` 之下，不再挂 ROOT；项目关闭即一并回收。
   为便于测试，内部留一个显式属主重载 `internal fun install(editor, owner: Disposable?)`。
2. **Session 自带 disposed 标志**（平台文档推荐的替代写法）：`@Volatile private var disposed` +
   覆写 `Disposable.isDisposed()`；`dispose()` 幂等（重复调用直接返回）。
   这样 `uninstall` 不需要（也不许用）已废弃的 `Disposer.isDisposed`。
3. **uninstall 走树感知销毁**：`if (!session.isDisposed) Disposer.dispose(session)`
   —— 由树负责移除节点并连带 dispose 子对象 Alarm（此前直接调 `session.dispose()` 只跑了一半清理，
   节点与 Alarm 仍留在树里）。
4. **测试缝**：`internal fun sessionDisposedForTest(editor): Boolean`、
   `internal fun sessionOwnerForTest(editor): Any?`（读 Session 记录的属主，用于断言生产路径确实注册到 project）。

保持不变的语义：正常释放路径（面板 release）仍由 `uninstall` 提前销毁；项目级注册只是**兜底**，
不改变编辑器释放的时机，也不把 Session 的存活期从「编辑器释放」放宽到「项目关闭」——因为
`uninstall` 照旧先跑。

## 四、验证方案

1. 新增/扩展 heavy 测试（沿用 `ui/annotate/PreviewUnknownColumnAnnotatorTest` 的
   `BasePlatformTestCase` + `myFixture.editor` 脚手架）：
   - 属主 dispose 后 Session 必被 dispose（行为断言：兜底真的生效）；
   - 生产入口 `install(editor)` 记录的属主 == `editor.project`（断言没退化成"不注册"）；
   - `uninstall` 幂等（重复调用不抛、第二次无动作）。
2. 反向验证：① 把 `Disposer.tryRegister` 摘掉（回到"不入树"）→ 属主 dispose 用例应变红；
   ② 去掉 `disposed` 标志幂等守卫 → 重复 `uninstall` 用例应变红。
3. 真机复核（用户侧）：重开沙盒跑一遍预览后**正常关闭** IDE，日志应**零** `Memory leak detected`。
   判据与本次取证同口径（同上 grep 管道），两次读数对照。
4. **不跑全量**（用户 20261023 明确要求）。

## 五、待验证前提（实施者先证伪再动手）

1. `editor.project` 在 install 的两个调用点（`SqlPreviewPanelEditorLifecycle.kt:686`、
   `SqlPreviewPanelExecution.kt:1721`）均非 null（面板自持 project；若为 null 则退化为不注册，
   该分支需在测试里显式覆盖并说明）。
2. `Disposer.tryRegister(project, session)` 在项目正常打开态返回 true；项目正在关闭时返回 false 且不抛。
3. `Alarm(POOLED_THREAD, session)` 反向持有 `this`，故 Session 入树后 Alarm 随之入树——泄漏告警应消失
   （本轮真机复核即验证此条）。
4. `Disposable.isDisposed()` 在 243 平台是可覆写的 default 方法（否则改用 `newCheckedDisposable()`）。

## 六、边界与不做

- **不动** `PreviewSchemaHighlighter` 的标注算法、范围判定与 hover 行为（本次只改生命周期）。
- **不动** ExternalAnnotator 通道（`PreviewUnknownColumnAnnotator`）——它不涉及本次泄漏。
- 不为「控制台编辑器无 uninstall」新增对称调用点：项目级兜底已覆盖，避免在无关路径引入新耦合。

## 七、实施记录（2026-10-23，已实施）

**改动**（`src/main/kotlin/.../ui/annotate/PreviewSchemaHighlighter.kt`，单文件）：

1. `install(editor)` → 转调 `install(editor, editor.project)`；新增内部重载
   `install(editor, owner: Disposable?)`，内部 `Disposer.tryRegister(owner, session)` 把 Session 入树
   （用 `tryRegister` 而非 `register`：属主正在关闭时不抛异常）。
2. `Session` 增 `@Volatile private var disposed` + **普通方法** `isDisposed()`
   （**不是 `override`**——243 的 `com.intellij.openapi.Disposable` 只声明 `dispose()`，没有 `isDisposed()`；
   这也正是平台给「`Disposer.isDisposed` 已废弃」的官方替代写法）；`dispose()` 首行加幂等短路。
3. `uninstall(editor)` 改走树感知销毁：`if (!session.isDisposed()) Disposer.dispose(session)`
   ——由树移除节点并连带 dispose 子对象 Alarm（此前直接调 `session.dispose()` 只跑一半清理）。
4. 测试缝：`hasLiveSessionForTest(editor)`（**语义＝是否仍存在"已安装且未 dispose"的会话**——
   `uninstall` 会先把会话移出 map，故"map 里没有"＝无活会话＝无泄漏风险，这才是泄漏判据）、
   `sessionOwnerForTest(editor)`。

**新增护栏**（`src/test/.../ui/annotate/PreviewSchemaSessionLifecycleTest.kt`，5 例）：
①属主 dispose 连带销毁会话（兜底生效）；②生产入口以 `editor.project` 为属主（没退化成"不注册"）；
③uninstall 幂等；④属主 dispose 与 uninstall 先后到达仍幂等；⑤无属主分支显式覆盖。

**读数**：

| 项 | 读数 |
| --- | --- |
| 新护栏 `PreviewSchemaSessionLifecycleTest` | **5/0/0/0** |
| 反向①（摘掉 `Disposer.tryRegister`） | **恰 1 红**（`testOwnerDisposalReclaimsSession`），其余 4 绿 |
| 反向②（摘掉 `uninstall` 的幂等守卫） | **未变红（5/0/0/0）——该守卫在当前断言下不可观测** |
| 回归（既有 `PreviewUnknownColumnAnnotatorTest`，**属 `testDbSchemaAnnotator` 桶，不在默认 `test` 内**） | **41/0/0/0** |

**诚实边界**：反向②没有取得红读数，说明「幂等守卫」在本套断言下**不可观测**（`Disposer.dispose` 对已
销毁对象不抛，故重复 dispose 不产生可断言差异）。该守卫保留为**防御性**实现（平台文档要求避免重复
dispose），但**不作为"已验证"项**登记；如需把它纳入验证，得引入"dispose 调用计数"类测试缝。

**本轮附带发现（工具层，非产品）**：`PreviewUnknownColumnAnnotatorTest` 不在默认 `test` 任务内，
`--tests` 指定它会报「No tests found for given includes」——按 `buildSrc/src/main/kotlin/mpv-testing.gradle.kts:126`
它归属 `testDbSchemaAnnotator`，须用该任务跑。

## 八、附：与 T-ShadowEntityResolve 的关系

本缺陷**与实体解析无关**：日志同时暴露的「HQL 预览降级」是另一条链（已由 `1b61964f` 修好），
两条线索只是被同一次「核实日志」一并发现。本计划独立立项、独立提交。
## 九、回归回填（2026-10-23，T-PreviewChainNoise W3）

本计划的树感知销毁改动引入了一处收尾瑕疵：`Session.dispose` 里的手动摘监听与两参重载注册
生成的子 disposable 撞车（ObjectTree 后序销毁子先父后），真机日志出现
「Can't remove document listener」+ EditorImpl「Assertion failed」各一条（每次面板关闭）。
修法＝删掉 dispose 里两行手动摘除（摘除职责只归注册时的 parentDisposable，T-RA-02 定例），
随 T-PreviewChainNoise（`9809a5b7`）实施；护栏 `PreviewSchemaSessionLifecycleTest` 补
「uninstall 零平台 ERROR」用例，反向（回填手动摘除）恰 1 红。详见
`20261023-2204-T-PreviewChainNoise-预览执行链三组平台SEVERE收口-开发计划.md` §2.3/§十。
