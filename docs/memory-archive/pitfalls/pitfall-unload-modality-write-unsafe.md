---
name: pitfall-unload-modality-write-unsafe
description: 动态卸载窗口被我们自己的 ModalityState.any() 弄成 write-unsafe——Unloading of SQL Visualizer 模态串的完整根因链、平台源码落点、判别栈帧，以及 T-DynUpdateWriteSafe 的落地修法与守卫形态（含「按包+令牌」规则、write-safe 资格是进入模态那一刻的快照）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a78d6c91-beb4-4121-82a3-e18ca13acc4d
---

2026-09-22 用户贴报障：`java.lang.Throwable: Write-unsafe context! Model changes are allowed from write-safe contexts only. Please ensure you're using invokeLater/invokeAndWait with a correct modality state (not "any").`，`current modality=ModalityState:{[<设置对话框 DialogWrapperPeerImpl$MyDialog,APPLICATION_MODAL,title=设置>], [Unloading of SQL Visualizer]}`。**当日已分析 + 实施 + 合入 master**（见文末 §已实施）。

**这不是此前几次那种「我们某处写文档没走闸门」，而是「卸载窗口本身被我们弄成了 write-unsafe」。** 判据是模态串里那个 sentinel 字符串。

## 平台机制（262.9437.185 源码逐帧实证）

- 报错来自 `TransactionGuardImpl.assertWriteSafeEnvironment()`（`:137-148`）：EDT 上 `!myWritingAllowed && areAssertionsEnabled()` → `LOG.error(reportWriteUnsafeContext(current))`，报文与用户贴的逐字一致（`:159-164`）。同活动只报一次（`myErrorReported`）。
- **write-safe 资格是「进入模态那一刻 myWritingAllowed 的快照」**，不是模态自身的属性：
  ```java
  public boolean isWriteSafeModality(ModalityState s){ return Boolean.TRUE.equals(myWriteSafeModalities.get(s)); }  // :131-134
  public void enteredModality(ModalityState m){ myWriteSafeModalities.put(m, myWritingAllowed); }                  // :199-201
  ```
- 快照写入点：`LaterInvocator.enterModal(...)` → `guard.enteredModality(appendedState)`（`LaterInvocator.java:204-207`）；`inModalContext(entity){}` = `enterModal` + 执行 + `leaveModal`（`openapi/application/impl/modality.kt:9-17`）。
- **posted runnable 一律不算用户活动**：`IdeEventQueue.kt:1106-1122` 只把输入/Item/Focus 事件算用户活动 → `performActivity(false, …)`；`wrapLaterInvocation`（`TransactionGuardImpl:203-220`）只对 write-safe 模态套 `runWithWritingAllowed`。→ **`ModalityState.any()` 排出的 runnable 里 `myWritingAllowed` 恒 false**，它紧接着进入的任何模态都被登记成写不安全。这就是 DocWriteContext KDoc 里「any 恒 write-unsafe」的底层原因。
- 断言门禁 = `LoadingState.COMPONENTS_LOADED && Registry.is("ide.require.transaction.for.model.changes", false)`（`:174-176`）。**实测本机 IU 261 装机目录 `lib/util-8.jar!/misc/registry.properties` 该键 = `true`** → 普通用户也会记这条 SEVERE（不是仅内测模式）。
- `ModalityState.defaultModalityState()` 在 EDT 上等于 `getCurrentModalityState()`（`ApplicationImpl:699-702`），即**当前上下文模态**；非 EDT 才走 `defaultModalityImpl()`（`application/modality.kt:35-40`：progress 模态 → 临时线程上下文 → 线程上下文 → nonModal）。**推论：从 BGT 用它兜底可能拿到 nonModal，模态弹窗存续期会被推迟**——这是当初没选它的原因。
- `ModalityState.current()` 是 `@RequiresEdt`，`Application.getCurrentModalityState()` 是 `@Deprecated @ApiStatus.Internal`——**后台线程想拿「当前模态实例」只能派一次只读跳板到 EDT**。
- `invokeAndWait` 在 EDT 上同步执行（`ApplicationImpl.doInvokeAndWait:603-611` 开头 `EDT.isCurrentThreadEdt()` 分支）→ 跳板本身就是幂等的，不必额外判线程。

## 我们这边：谁把卸载窗口弄成了写不安全

- 模态串 `[Unloading of SQL Visualizer]` 来自 `DynamicPluginsLegacyImpl.kt:509` 的 `inModalContext(ObjectUtils.sentinel("Unloading of ${pluginDescriptor.name}"))`。走 legacy 实现是因为新实现默认关闭：`DynamicPluginsSupport.kt:51-62` `Registry.is("dynamic.plugins.support.new.impl", false)` 为 false 即返回 null。
- **主判（分支 A，我们的缺陷）**：`DynamicInstallSupport.unloadAndInstallOnEdt` 的 `invokeAndWait({ 卸载旧版 → 装载新版 }, ModalityState.any())`（改前在 `:226`，改后实现见 §已实施）——`UpdateSection.runInstall()` 在 `Task.Backgroundable.run()`（后台线程）里调 `installRunner` → `DynamicInstallSupport.install`，于是从 BGT 用 ANY 派发平台卸载：快照 false → `enterModal("Unloading of …")` 记 false → 整个卸载过程写不安全。设置对话框全程开着，故模态是 `[设置对话框, Unloading of …]` 这个嵌套形态，与用户贴的完全吻合。该行自 2026-09-11 `34055dd`（T-DynUpdate）起就在，T-WriteSafeCtx 那轮逐站点收口（09-17）晚于它、漏了这处。
- 旁证：平台自己的卸载入口都在 EDT 用户活动里（`DynamicPluginEnabler.disable:114` 设置→插件停用、`PluginDownloader.unloadDescriptorById:399-409` 插件更新），进入卸载时 `myWritingAllowed=true` → 窗口写安全 → 平台自身不刷这条日志。
- **分支 B（平台发起卸载）**：设置→插件 停用/更新本插件时，若某个排队的 `ModalityState.any()` 回调被 `flushQueue()`（`DynamicPluginsLegacyImpl.kt:605`，以及 `PotemkinProgress` 泵事件）卷进窗口执行、且它做了文档写/落盘/VFS 同步 refresh，同样得到这条消息与同一模态串。

## 卸载窗口里谁在写（能触发该断言的平台调用点）

- `DocumentImpl.java:768-780` —— 改**本地文件**支撑的文档才断言（内存 PSI 不算，这点决定哪些链路有份）
- `FileDocumentManagerImpl.java:265 / 349` —— `saveAllDocuments()` / `saveDocument()` **也断言**（不只是改内容）
- `PsiDocumentManagerBase.java:340/503/534/561`、`PsiModificationTrackerImpl.java:90`（PSI commit / 修改计数）
- `RefreshQueueImpl.kt:87` —— 同步 VFS refresh

窗口内实际发生的写有三类来源：平台卸载自身（dispose 我们的组件、`ActionToolbarImpl.resetAllToolbars`、`clearCachesAfterUnload`）、`flushQueue()` 与 `PotemkinProgress` 泵事件（**会跑掉我们所有排队的 `any()` 回调**）、我们自己的写路径（例：`DataSourceSelector` 的 exec 文档防抖落盘，1.5s 后从 daemon 线程 `invokeLater` → `autosaveExecDocNow` 里 `fdm.saveDocument(doc)`，正好落在 `FileDocumentManagerImpl:349` 的断言上）。

## 判别方法（拿栈一眼定）

日志里该 SEVERE 记录往下第一段栈：① `DynamicPluginsLegacyImpl.doUnloadPluginWithProgress/unloadPluginWithoutProgress` → 确在卸载窗口内；② `DynamicInstallSupport$PlatformApis.unloadAndInstallOnEdt` + `LaterInvocator.invokeAndWait` → 分支 A 的 `any()` 派发；③ 最内层写帧决定「谁写的」。同记录里通常还有 `Plugin to blame:` 行。复现：设置页 → 检查更新 → 下载并安装，grep `Write-unsafe context` 与 `Suppressed a frequent exception`（看频次）。**注意日志窗口**：本机开发 IDE 的 idea.log 被第三方插件刷到 10MB/轮转十余份，报障往往已滚掉（本轮 14 份全量 grep 命中 0）——先按构建戳定位，见 [[reference-real-ide-debug-entry]]。

## 既有写安全守卫的盲区（本条最重要的一般化结论）

`BannedApiSourceScanTest` 的两条写安全规则是**词法**的——扫 `invokeLater/invokeAndWait` 站点的实参区与尾随 lambda 体里有没有**写令牌**（`setText(`/`saveDocument(`/`runWriteCommandAction` 等）。本例回调体里没有任何写令牌，写发生在它**间接调用**的平台卸载内部，规则结构上永远看不见。

**修法不是加令牌，而是换判据维度**：对「危险动作集中在某个包」的形态，直接按**包 + 令牌**封口（`update` 包内出现 `ModalityState.any()` 即红），与写法无关——实测「直接实参 / 赋给变量再传 / 决策函数返回」三种形态全部命中，而按「站点实参区」判只覆盖第一种。

**同时发现一处既有债（登记未修）**：`invokeLaterSites` 提取器只认 `invokeLater(`，**`invokeAndWait` 站点对两条既有写安全规则从未生效**——而本次事故点正是 `invokeAndWait` 形态。修它属 T-WriteSafeCtx 规则族的面。

## 已实施（T-DynUpdateWriteSafe，2026-09-22，合入 master `cd54905`）

- 新增 `dispatchModalityForUnload()`：EDT 只读跳板取当前模态实例（`ModalityState.current()`）→ `chooseDispatchModality(captured)` 纯函数决策（**写安全则原样用**——`ModalityStateEx.accepts` 的 trivial 分支 `requested == this` 保证立即执行、不被模态推迟；取不到或非写安全则退 `nonModal`，即「延迟到弹窗关闭」的平台契约语义）。判定谓词 `isWriteSafeDispatch(m)` = `TransactionGuard.getInstance().isWriteSafeModality(m)` 直读，异常即 false。
- 派发改走可注入的 `edtDispatcher` 测试缝；新增派发日志行 `dynamic update: dispatch modality=… writeSafe=…` 作为真机取证锚点（模态串假设无法 headless 复现，只能靠这行）。
- 守卫：`BannedApiSourceScanTest` 新增「update 包禁 `ModalityState.any()`」（按包+令牌，豁免沿用「调用行上方连续注释块含 `write-safe:`」口径——T-01 的只读跳板正靠它放行）+ 负向自证三态（违规必报 / 带标注放行 / 他包不误伤）。新测试类 `DynamicUnloadWriteSafeTest` 6 例（决策拒 any / 保留写安全模态 / 取不到退 nonModal / **谓词判别力正对照** / 解析结果写安全 / 调用点派发模态非 any 且写安全）。
- 反向验证两臂（必红）：M1 派发点改回 `ModalityState.any()` → 2 例红；M2 决策函数直接 `return any()` → 3 例红；逐字节还原 `sha256` 一致。
- 读数：定向 32/0；全量 `testShards` 499 类/5705 例/1 失败（**master 既有** `EnumReachabilityGuardTest` 指向 T-ExplainChainHardening 的占位枚举常量，与本项 diff 无交集）/0 错误/6 跳过。
- **未取证（如实登记）**：真机端到端——动态安装链在开发态被 `PlatformPluginInstaller.devGuardOrFailure()` 主动拒绝，且自更新需更新源上存在比当前安装版本更新的发行包；两项都不具备，故「真机日志归零」这一最终证据只能等下次有新版发行包时 grep 那行 `writeSafe=…`。

**Why:** 这条报障的模态串带 `Unloading of <插件名>` 时，根因在**派发卸载的模态**而不在某次写；只看「哪行写了文档」会一直找不到落点。
**How to apply:** 见到 `Write-unsafe context` 先读模态串——带卸载/关闭类 sentinel 就往「窗口是怎么进入的」查（谁用什么模态派发了 enterModal 的那个动作）；本仓自更新链路即 `DynamicInstallSupport` 的派发模态。要派发「会间接改模型的平台动作」时，**别用 `any()`**：从 EDT 取当前模态实例，写安全就用它（保证立即执行），否则退 `nonModal`。守卫写不出来时，先问「这个危险动作是不是集中在某个包/某个类」——按包封口比按写令牌可靠。相关：[[project-dynamic-unload-barriers]]（卸载屏障与 T-DynUpdate 反射入口）、[[pitfall-guard-covers-audited-targets]]（守卫对真实站点不可见的空绿族）、[[pitfall-verification-blindspots-and-wrapup]]。

---

## 同族复发（2026-11-01）：间接写形态骗过了既有守卫

执行链把「临时文件构建」移出 EDT 时（T-ExecPlanEdtSlowOp），异步回调用 `invokeLater({ onReady(pair) }, ModalityState.any())` 派发。`onReady` 续接链里有 `consoleDoc.setText` + `PsiDocumentManager.commitDocument` + `saveDocument`——**写发生在回调内部**，于是 `any()` 又把回调变成了 write-unsafe 上下文，平台每次点「执行计划」都报一次 `TransactionGuardImpl … Plugin to blame: SQL Visualizer / Last Action: ConvertToSql`。

**为什么既有守卫没抓到**：`BannedApiScanRuleTest.anyModalityWriteOffenders` 只认**直接写令牌**（`setText(`/`commitDocument(`）出现在 `any()` 回调里。`invokeLater { callback(…) }` 这种**间接形态**里回调名不在写令牌表内，守卫全绿而真机必报。

**正确模态**：`ModalityState.nonModal()`。243 平台字节码实证——`TransactionGuardImpl.<init>` 里 `createConcurrentWeakMap()` 之后立刻 `put(ModalityState.nonModal(), TRUE)`，**只有 nonModal 在构造期被登记为 write-safe**；`wrapLaterInvocation` 产出的 `TransactionGuardImpl$1.run` 仅在 `isWriteSafeModality(模态)` 为真时才 `assertWriteIntentLockAcquired()` + `runWithWritingAllowed(runnable)`，否则裸 run。`TransactionGuard.submitTransaction` 系列在 243 已全部 `Deprecated`，不划算。

**新的守卫形态**（`ExecTempFileEdtGuardTest` 规则五）：异步函数内**每个**回调调用点都必须落在 `invokeLater(…, ModalityState.nonModal())` / `DocWriteContext.invokeLaterWriteSafe` / `submitTransaction` 之内，且函数体不得出现 `ModalityState.any()`；实参区按**括号配平**取（固定长度窗口会跨界读到下一个函数的调用而假绿——本轮同型踩过一次）。另加一条行为断言：回调内 `TransactionGuard.getInstance().isWritingAllowed()` 必须为 true（headless 下有判别力，`any()` 下实测 false）。

**判据一句话**：把「同步调用」改成「异步回调」时，**写资格会跟着上下文一起丢**——原来靠 action 的 write-safe 上下文合法，异步后必须显式用 write-safe 投递。

相关：[[pitfall-explain-result-shape-real-vs-synthetic]]（同一轮真机抓到的另一条）。
