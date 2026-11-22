---
name: pitfall-edt-psi-warm-and-write-safe-modality
description: 两类真机实测的 EDT 线程违规——①console.getScriptModel() 首次调用会实例化 PsiFile 走 findDirectory 慢操作，且方言映射的 setMapping 会 forceReload 把 PSI 作废使缓存无效（须预热）；②ModalityState.any() 与「省略模态」都恒 write-unsafe，只有 nonModal 在构造期登记为 write-safe
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

# B/C 两类 EDT 线程违规（2026-11-04 真机日志实证，UI-007 / UI-008）

## B：`console.getScriptModel()` 在 EDT 首次实例化 PsiFile

慢操作链（9 条 SEVERE）：

```
FileManagerImpl.findDirectoryImpl
 → ProjectFileIndexImpl.isUnderIgnored
  → WorkspaceFileIndexDataImpl.ensureIsUpToDate
```

根因：`DataSourceSelectorConsole.kt` 的 `console.getScriptModel()` 反射调用；平台内部
`PsiUtilCore.getPsiFile(console.getVirtualFile())` **首次实例化** PsiFile，
`AbstractFileViewProvider.shouldCreatePsi` 于是走 `PsiManager.findDirectory`。

**为什么不能靠缓存**：方言映射 `setMapping` 会触发
`LanguageSubstitutors.requestReparsing → FileManagerImpl.forceReload`，把 PSI 作废——
缓存命中不了，同一轮 write-unsafe 栈里能看到这条 forceReload。

修法（`ce499a5e`）：新增 `warmConsolePsiThen`——EDT 判定 → `executeOnPooledThread` +
`ReadAction{PsiManager.findFile}` 预热 vf 与 console vf → `invokeLater(nonModal)` 回 EDT；
两处 `prepareAfterEditorResolved` 调用点都落进续接 lambda。

**异步化的连带坑**：续接改异步后，异常不再冒泡到外层 `runCatching`——若不处理，单飞门会悬挂 60s、
面板恒「执行中」。所以必须把失败收口抽成 `handlePrepareFailure` 给两处共用。
守卫 `ConsolePsiWarmGuardTest`（三段式齐备 + 续接点必须全部落在续接 lambda 作用域内）。

**残余风险**：预暖与迟到的 `forceReload` 仍可能竞态，此时 `getScriptModel` 会在 EDT 重建 PSI，
SEVERE 可能偶发（窗口已压到一次 EDT hop）；预热失败只回落旧行为。

## C：`any()` 与「省略模态」都恒 write-unsafe

平台事实：`ModalityState.any()` **恒 write-unsafe**——构造期只有 `nonModal` 被登记为 write-safe。
另一条更隐蔽：**从 BGT 用「省略模态」派发也算 write-unsafe**。

真机两条落点（3 条 `TransactionGuardImpl - Write-unsafe context`）：

1. `submitExplainDirectConsole` 用 `ModalityState.any()` 投递 → 链内
   `reuseSharedConsole` 的 `WriteCommandAction{doc.setText}` 与 `executeOnConsole` 的
   `WriteAction{writeVfTextViaDocument}` 全部无写资格；
2. `DialectPreMapping` 从 BGT 省略模态派发 `setMapping` → 平台内部 `reparseFiles` 与
   `LanguageSubstitutors.requestReparsing` 两拍写动作同样无资格（栈首是
   `PsiModificationTrackerImpl.fireEvent ← FileManagerImpl.forceReload`）。

修法（`f8608db5`）：全部换 `ModalityState.nonModal()`（`SqlPreviewPanelExecutionExplain.kt:225,245`、
`DialectPreMapping.kt:166,271`）。语义＝写入类回调延后到模态弹窗关闭，与仓内 `DocWriteContext` 定案一致。

**回归风险（最高的一项）**：`nonModal` 在有模态弹窗时会延后执行——方言映射可能因此超 2s 预算被跳过
（既有降级：回落 GenericSQL PSI / 由 DBN `SqlDialectPusher` 自愈）；`submitExplainDirectConsole` 的执行
会等弹窗关闭（属平台要求的语义，非功能退化）。

守卫 `ExecWriteSafeDispatchGuardTest`（作用域内禁 `any()`、每个 `invokeLater` 实参区必须含
`nonModal`、且至少一处派发防「空绿」）。

**守卫覆盖面边界**：只覆盖已登记的两个入口——将来新增一条 `any()` 入口进执行链仍会打 SEVERE。

## 怎么验 C 项的模态退化面（2026-11-04 用户追问「这个怎么试」时的定论）

**风险的确切条件**：`ensureMapped` 是 BGT 有界等待（2s 上限），改 `nonModal` 后若这 2s 窗口内存在模态弹窗，投递不执行 → 超时 → 该次方言映射被跳过。

**关键时序判据（真机日志顺序）**：

```
[M36] exec file updated via document+save
[M36] dialect pre-mapped to PostgreSQL for mpsql-exec.sql   ← 映射在这里就完成了
[M36] show popup with 7 runners                             ← 所有模态弹窗都在它之后
```

即映射跑在**选数据源 / `${}` 原文确认 / DML 二次确认 / 密码框之前**，正常点击流程撞不上。唯一重叠场景是「用户在映射那 2 秒内手动开模态框」。

**推荐验法（零成本）**：正常做几次预览+执行后查日志——`grep "dialect pre-mapped to"` 有命中、`grep -iE "ensureMapped.*(timeout|skip)|方言映射.*(超时|跳过)"` 为 0，即正常路径未退化。

**刻意构造（仅在要彻底放心时）**：预览刚弹出的 1~2 秒内立刻按 `Ctrl+Alt+S` 开设置（模态），看该次是否超时降级；关掉设置后点执行，确认 SQL 仍按正确方言渲染并执行成功。降级但结果正确＝兜底有效；渲染错乱＝需回退 `DialectPreMapping` 两处（保留 `submitExplainDirectConsole` 两处）。

## D（2026-11-04 补，回 port 后真机 36 条）：`createTempPsiFile` 本身在 EDT 被同步调用

B 类修的是 `prepareAfterEditorResolved` 里的 `getScriptModel`；**另一条完全不同的路**是
`chooseAndPrepare` 开头的同步 `createTempPsiFile`（内部 `ReadAction.compute { PsiManager.findFile }`）——
`ReadAction` 不改变线程，PSI 首次实例化仍在 EDT。三路共用入口：执行 SQL / 执行计划 / 回滚快照。

**为什么 B 项修完还会犯**：两条栈落点不同（`continuePrepareAfterTempFile` vs `chooseAndPrepare`），
只看 B 的栈会以为覆盖了。dev 侧 `ba314f42` 早已修（`createTempPsiFileAsync` + 续接函数），
但回 port 清单漏了它——**归因教训见 [[project-dev-vs-master-262-acceptance-20261104]]**。

修法要点（master 侧 `c62f23b8`，不整搬 dev 的 `ExecTempFileFactory.kt`）：异步包装 + `revalidateOnEdt`
（PSI 失效兜底，`psi.isValid` 必须包 ReadAction）+ 把后续全链抽成续接函数；投递用 `nonModal` 不用 `any()`。

**新坑：慢段搬到池线程后，refresh 段的 WIRA 会自锁**——`createTempPsiFile` 原本**无条件**包
`WriteIntentReadAction`（EDT 上防「write thread only」）。搬到 BGT 后 BGT 持 WIL 等刷新会话、会话推进又需
WIL/EDT ⇒ 自锁（T-PrewarmWilDeadlock 同款）。**必须按线程分档：EDT 保留 WIRA / BGT 裸调**。

## 关联

[[pitfall-intellij-actiongroup-takeover]]（同轮 A 类：接管组必须声明 BGT）、
[[pitfall-bgt-psi-isvalid-needs-readaction]]（读锁只解决权限、不解决慢操作）、
[[project-dev-vs-master-262-acceptance-20261104]]（三项修复的账本与真机归零读数）。
