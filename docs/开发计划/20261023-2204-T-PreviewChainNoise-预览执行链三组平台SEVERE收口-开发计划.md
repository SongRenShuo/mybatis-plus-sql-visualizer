# T-PreviewChainNoise：预览执行链三组平台 SEVERE 收口（开发计划）

**创建时间**：2026-10-23 22:04
**触发源**：用户粘贴真机日志——同一次会话（预览面板点「执行计划」→ 无已绑数据源 → 选源重试 → 关闭面板）内共 5 条 Throwable。
**基线**：`master@a553f3d4`（分析时 tip）
**状态**：已实施（`9809a5b7`，2026-10-23；本文档与实现分两笔提交，实施记录见 §十）
**编号**：未占 O/R/D/V（缺陷级修复，同 `T-PreviewSchemaSessionLeak` 口径）
**门禁**：定向测试 + 反向验证；全量 `testShards` 待用户授权（20261023 用户已两次要求本轮不跑全量）

---

## 一、现象与证据

五条 Throwable 实际只有**三组根因**，其中两条是 2026-10-23 当天 `ebfaf73f`（T-PreviewSchemaSessionLeak）修复直接引入的回归。

| # | 日志首行 | 关键栈帧 | 组 |
|---|---|---|---|
| S1 | `Slow operations are prohibited on EDT` | `DataSourceSelector.syncExecVfContentIfNeeded$lambda$0(DataSourceSelector.kt:1555)` ← `createTempPsiFile(DataSourceSelectorResults.kt:1333)` | **A** |
| S2 | 同上（同一条，仅调用点不同） | 同上 ← `createTempPsiFile(DataSourceSelectorResults.kt:1313)` | **A** |
| S3 | `IllegalStateException: There is no ProgressIndicator or Job in this thread, the current job is not cancellable` | `McpDbExecutor.executeQueryInternal(:255)` ← `executeQuery(:182)` ← `ExplainPanelChannel.executeStructured(:144)`，运行在 `ApplicationImpl$4.run`（`executeOnPooledThread`） | **B** |
| S4 | `Can't remove document listener (PreviewSchemaHighlighter$Session$documentListener$1@…)` | `PreviewSchemaHighlighter$Session.dispose(:265)` ← `uninstall(:113)` ← `disposeCurrentEditorOnly(:987)` ← `SqlPreviewPanel.removeNotify(:881)` | **C** |
| S5 | `Assertion failed`（`EditorImpl.removeEditorMouseMotionListener`） | `PreviewSchemaHighlighter$Session.dispose(:266)`，同一条关闭链 | **C** |

计数口径：S1/S2 是**同一次点击的两条**（`createTempPsiFile` 内前后两次收敛探测），不是重复上报——`SlowOperations.isAlreadyReported()` 按栈身份去重，但两处调用点的栈不同（1313 与 1333）。

---

## 二、根因

### 2.1 A：EDT 上读 VFS 文件内容（`syncExecVfContentIfNeeded`）

`DataSourceSelector.kt:1551-1557`：

```kotlin
internal fun syncExecVfContentIfNeeded(project: Project, vf: VirtualFile, sql: String): Boolean {
    val diverged = ReadAction.compute<Boolean, Throwable> {
        val doc = FileDocumentManager.getInstance().getCachedDocument(vf)
        (doc != null && doc.text != sql) ||
            vf.inputStream.readBytes().toString(Charsets.UTF_8) != sql   // ← :1555 真实磁盘 IO
    }
    if (!diverged) return false
    return dispatchDocWriteOnEdt(project, vf, sql)
}
```

调用点（`DataSourceSelectorResults.kt`）：

- `:1313` refresh 前收敛、`:1333` refresh 后收敛 —— **都在 `createTempPsiFile` 内**；
- `:1481`、`:1508` —— `prewarmExecTempFileInner`（BGT 预热链）。

`createTempPsiFile` 由 `chooseAndPrepare`（`:259`）→ `prepareConsole`（`DataSourceSelector.kt:475`）→ `promptDataSourceThenRetryStructured`（`SqlPreviewPanelExecution.kt:1503`）← `submitExplainOrStructured`（`:1329`）← `explainSql`（`:1084`）← 工具栏按钮（`SqlPreviewPanelResultView.kt:62`）触发，**全链在 EDT**。栈里的 `EventDispatchThread.pumpOneEventForFilters` 即直接证据。

平台判据（243 sources `com/intellij/util/SlowOperations.java:106-115`）：

```java
String error = !EDT.isCurrentThreadEdt() || isAlwaysAllowed() || isSlowOperationAllowed() ? null : ERROR_EDT;
if (error == null || isAlreadyReported()) return;
Holder.LOG.error(error);
```

`ReadAction.compute` 不豁免——断言只看线程与 section 栈（T-ExecSlowOps §2.3 已两次证伪「allowSlowOperations 逃生舱」路线，禁再试）。`vf.inputStream` 落到 `LocalFileSystemBase.convertToNioFileAndCheck` 即触发。

**这是同族缺陷的第二次犯**：同文件 `execFileContentMatches`（`:1837-1854`）早在 2026-10-06（T-DevAcceptFix2 ④）就因为**完全相同的调用链**删掉了这行读盘，注释原文就写着「点执行计划 → createTempPsiFile → 本函数，全程 EDT」，改用 `lastWrittenExecContent` 内存快照。`syncExecVfContentIfNeeded` 写于 2026-09-16（`d7843563`，T-ExecDocSync），此后一直没按那条定例回填。

附带一处口径问题：该函数 KDoc 写「BGT 调用安全」，只承诺了 BGT，但它的两个 EDT 调用点从未被这条约束覆盖。

### 2.2 B：结构化 EXPLAIN 跑在无 Job 的池线程（`createBlocking`）

`ExplainPanelChannel.kt:137-150`：

```kotlin
ApplicationManager.getApplication().executeOnPooledThread {
    val raw = McpDbExecutor.executeQuery(project, dataSource, explainSql, ...)   // :144
```

`McpDbExecutor.kt:255` 反射调用 `DatabaseConnectionManager$Builder.createBlocking`。平台实现（243 sources `com/intellij/openapi/progress/coroutines.kt:138-143`）：

```kotlin
private fun <T> runBlockingCancellable(allowOrphan: Boolean, ...): T {
  assertBackgroundThreadOrWriteAction()
  return prepareThreadContext { ctx ->
    if (!allowOrphan && ctx[Job] == null && !Cancellation.isInNonCancelableSection()) {
      LOG.error(IllegalStateException("There is no ProgressIndicator or Job in this thread, the current job is not cancellable."))
    }
```

而 `context.kt:74-88` 只有在**存在全局进度指示器**时才会补一个 Job；`executeOnPooledThread` 起的是裸池线程，两者都没有 → 命中 `LOG.error`。

**同一坑在本仓已有修法且已留档**：`PreviewSideQuery.kt:92-110` 用 `ProgressManager.getInstance().runProcess(Computable { … }, EmptyProgressIndicator())` 包住同一句 `McpDbExecutor.executeQuery`，KDoc 逐字点名了这条异常。`ExplainPanelChannel` 是漏掉的第二个调用点。

值得单独记一笔：`PreviewSideQuery` 的注释写「真机 IDE 的池线程恰好有上下文，测试/纯后台调用没有」——**本次真机日志直接推翻了这句判断**，说明这层包装不能按「只有 headless 需要」来理解，必须无条件兜住。

### 2.3 C：Session 销毁时重复摘监听（`ebfaf73f` 引入的回归）

`PreviewSchemaHighlighter.kt` 两处事实并存：

```kotlin
// :164-165 注册：两参重载，监听的所有权交给 Session（父 disposable）
editor.document.addDocumentListener(documentListener, this)
editor.addEditorMouseMotionListener(mouseListener, this)

// :265-266 销毁：又手动摘一次
runCatching { editor.document.removeDocumentListener(documentListener) }
runCatching { editor.removeEditorMouseMotionListener(mouseListener) }
```

平台两参重载本身就把「摘除」注册成子对象：

- `DocumentImpl.java:1003-1006`：`addDocumentListener(listener, parent)` → `Disposer.register(parent, new DocumentListenerDisposable(...))`，该子对象 `dispose()` 里 `myList.remove(myListener)`（`:1019-1022`）。
- `Editor.java:342-345`：`addEditorMouseMotionListener(listener, parent)` 默认实现 → `Disposer.register(parentDisposable, () -> removeEditorMouseMotionListener(listener))`。

`uninstall` 现走 `Disposer.dispose(session)`（`:113`）。`ObjectTree.runWithTrace` 明确按**后序（子先父后）**销毁（`ObjectTree.java:127-137`「third, dispose in post-order (bottom-up)」）：

1. 先跑 Session 的两个子 disposable → 文档监听与鼠标监听**已被摘掉**；
2. 再跑 `Session.dispose()` → 手动再摘一次 →
   - `DocumentImpl.removeDocumentListener` 失败 → `LOG.error("Can't remove document listener …")`（`:1025-1028`，即 S4）；
   - `EditorImpl.removeEditorMouseMotionListener` 失败 → `LOG.assertTrue(success || isReleased)` 不过（编辑器此时尚未 release）→ `Assertion failed`（`:1975-1977`，即 S5）。

`runCatching` 拦不住这两条——它们本身是日志/断言，不是被抛出的普通异常（断言虽会抛，但已被 `runCatching` 吞掉，日志早写了）。

**为什么是回归**：`ebfaf73f` 之前 `uninstall` 直接调 `session.dispose()`，不经过 Disposer 树，子 disposable 从不运行，手动摘除是唯一路径；改成树感知销毁后，两条路径同时生效。仓内对这条规律已有明文定例——`SqlPreviewPanelEditorLifecycle.kt:969-974`（T-RA-02）写着「移除职责只归 autoPopupDisposable……二次移除会报 "Can't remove document listener"」，`PreviewSchemaHighlighter` 复发了同一个坑。

---

## 三、修法（推荐设计）

### W1（对应 A）：EDT 不读盘，判据分线程

把探测改成三态，与 `execFileContentMatches` 同口径（有文档只看内存态），EDT 且无文档时直接短路：

```kotlin
internal fun syncExecVfContentIfNeeded(project: Project, vf: VirtualFile, sql: String): Boolean {
    val fdm = FileDocumentManager.getInstance()
    val doc = ReadAction.compute<Document?, Throwable> { fdm.getCachedDocument(vf) }
    val diverged = when {
        // 有文档：只看内存态（与 execFileContentMatches 一致）；未保存且内容相等也要收敛，否则 refresh 命中冲突
        doc != null -> doc.text != sql || fdm.isDocumentUnsaved(doc)
        // 无文档 + EDT：禁止读盘；无文档即不存在 File Cache Conflict 条件（冲突需 "已加载且已修改" 的文档）
        ApplicationManager.getApplication().isDispatchThread -> false
        // 无文档 + BGT：保留原防御（SlowOperations 断言只对 EDT 生效）
        else -> vf.inputStream.readBytes().toString(Charsets.UTF_8) != sql
    }
    if (!diverged) return false
    return dispatchDocWriteOnEdt(project, vf, sql)
}
```

要点：

- 原实现里 `||` 是**短路**，所以慢操作只在「无文档」或「文档内容已等于 sql」时发生——都不是罕见分支，几乎是每次冷路径必付一次读盘。
- 新增 `isDocumentUnsaved(doc)` 覆盖了原实现靠读盘才能发现的一档：文档内容已等于 sql 但未落盘（`ExecDocSync2Test` F4 用例正是这一形态，`doc.text != sql` 为 false，靠磁盘分叉才判出）。补上后该用例语义不变。
- 有文档时不再读盘属于**收窄**（原实现会在文档内容相同时仍验磁盘），与 `execFileContentMatches` 既有取舍一致，需在 KDoc 写明。

备选（不推荐）：把整个探测派到池线程 + 有界等待。EDT 阻塞等 IO 比现在的「就地读」更差，且与 T-PrewarmWilDeadlock 那类自锁风险相邻。

### W2（对应 B）：把进度上下文兜底下沉到 `McpDbExecutor`

不要只补 `ExplainPanelChannel` 一处（那就成了第三个「漏掉的兄弟」）。在 `McpDbExecutor` 内收口：

```kotlin
private fun <T> withDbBlockingContext(block: () -> T): T {
    val pm = ProgressManager.getInstance()
    // 已有指示器/Job 时不得替换——否则丢掉外层取消语义（MCP 工具跑在协程里带 Job）
    return if (pm.progressIndicator != null) block()
    else pm.runProcess(Computable { block() }, EmptyProgressIndicator())
}

val ref = withDbBlockingContext {
    Class.forName(BUILDER).getMethod("createBlocking").invoke(builder)
}
```

- `internal` 可见性，供 headless 直测（不碰真库）。
- `ExplainPanelChannel` 本身可保持原样（收口已覆盖）；`PreviewSideQuery` 已有的包装变成冗余但无害，**本轮不动它**（少改一处少一份回归面）。

备选（记录留档，不推荐默认采用）：`Cancellation.computeInNonCancelableSection { createBlocking() }`（`Cancellation.java:108-119`）。它能合法跳过该断言，但会把这一段整体标成「不可取消」，内部嵌套的 `checkCanceled` 一并失效；语义面比补一个空指示器更宽，故只作备选。

### W3（对应 C）：摘监听的职责只归 Disposer

```kotlin
override fun dispose() {
    if (disposed) return
    disposed = true
    alarm.cancelAllRequests()          // Alarm 是子对象，已先 dispose，这里是防御性重复调用（幂等）
    for (highlighter in highlighters) { // RangeHighlighter 不是 Disposable，必须手动摘
        runCatching { editor.markupModel.removeHighlighter(highlighter) }
    }
    highlighters.clear()
}
```

即：**删掉 :265-266 两行**，并在 KDoc 里写明「监听摘除只由注册时的 `parentDisposable` 负责，禁止手工二次摘除」。高亮器仍手动清（它们没走 Disposable 注册）。

同时把 `init` 的注释补一句：注册用的两参重载已经包含摘除动作，`dispose` 不要重复。

---

## 四、验证方案

### 4.1 定向测试

| 改动 | 用例 | 断言要点 |
|---|---|---|
| W1 | 新增 `ExecVfDivergenceProbeTest`（纯判定函数直测，抽 `internal fun probeExecVfDivergence(...)`） | ① 有文档且内容不同 → 分叉；② 有文档、内容相同但未保存 → 分叉；③ 有文档、内容相同且已保存 → 不分叉**且不读盘**；④ 无文档 + `allowDiskRead=false` → 不分叉；⑤ 无文档 + `allowDiskRead=true` + 盘上不同 → 分叉 |
| W1 | `ExecDocSyncTest` / `ExecDocSync2Test` 现有 3 例改造 | 原「EDT 无文档 + 盘上分叉 → repaired=true」的断言与新契约冲突，改为「不读盘、不修复（false）」；磁盘分叉修复语义移到 BGT 判据用例上覆盖 |
| W1 | 新增 EDT 端到端例（`LoggedErrorProcessor` 捕获，参照 `SqlPreviewPanelPrewarmWriteSafeTest`） | EDT 上跑 `syncExecVfContentIfNeeded`，全程零 `Slow operations` ERROR |
| W2 | 新增 `McpDbExecutorProgressContextTest`（不碰 DB） | 裸池线程内调用包装体，断言块内 `ProgressManager.getInstance().progressIndicator != null`；另一例预置真实指示器，断言块内拿到的是**同一个**指示器（没被替换） |
| W3 | 扩展 `PreviewSchemaSessionLifecycleTest` | `install` → `uninstall` 全程用 `LoggedErrorProcessor` 捕获，断言零 `Can't remove document listener`、零 `Assertion failed` |

### 4.2 反向验证（每条改动都要有）

- W1：把 `isDispatchThread -> false` 分支摘掉（恢复无条件读盘）→ 4.1 的「零 ERROR」用例（或纯判定函数第 ④ 条）必须变红。
- W2：把 `withDbBlockingContext` 还原成直接调用 → `McpDbExecutorProgressContextTest` 必须变红（块内 `progressIndicator == null`）。
- W3：把 :265-266 手动摘除加回去 → `PreviewSchemaSessionLifecycleTest` 的零 ERROR 用例必须变红。

⚠️ W1 的「零 ERROR」用例依赖 headless 下 `SlowOperations` 断言真的会触发——light 测试里 `ourStack` 为空时 `isSlowOperationAllowed()` 直接放行（`SlowOperations.java:140-142`）。**这条必须先做对照实验**；若 headless 复现不出，退化为只依赖纯判定函数那 5 条（确定性高、不依赖平台断言）。

### 4.3 真机复核（用户侧）

重跑同一操作序列（点「执行计划」→ 无数据源 → 选源 → 关面板），`idea.log` 应零这 5 条。取证口径与本次一致：按首行 grep 后计数，改前 2/1/1/1，改后应为 0/0/0/0。

---

## 五、待验证前提（实施者先证伪再动手）

1. `FileDocumentManager.isDocumentUnsaved(doc)` 在 243 可用且是廉价内存判据（本仓 `DataSourceSelector.kt:1457`、`:1474` 已在用，`Document.isModified` 于 262 已移除，这是唯一查询口径，见 `:1677-1678`）。
2. ~~`MemoryDiskConflictResolver` 的弹窗条件确为「document 已加载且已修改」~~ → **已证**（实施时
   243 sources `MemoryDiskConflictResolver.java:41-46` 核对：`beforeContentChange` 首行
   `document == null || !isDocumentUnsaved(document) → return`——无缓存 document 即无冲突收集，
   「EDT + 无文档 → 不读盘」的收窄安全）。
3. headless 下 `SlowOperations` 断言能否被构造出来（见 §4.2 末尾）。这条决定 W1 反向验证用哪种 oracle。
4. `ProgressManager.getInstance().progressIndicator` 在裸池线程上返回 null、在 `runProcess` 块内非 null（`PreviewSideQuery` 已在真机/headless 走过同款，应成立；但需本仓自证）。
5. `DatabaseConnectionManager$Builder.createBlocking` 是**唯一**需要该上下文的入口（`McpDbExecutor.kt:255` 是仓内唯一调用点，grep 已核）。若后续新增反射入口，需复用同一包装。
6. 262（用户真机 IDE）的 `EditorImpl.removeEditorMouseMotionListener` 断言形态与 243 sources 一致（仅行号不同：日志 `:2419` vs 243 `:1977`）；W3 是否彻底清零，以真机日志读数为准。
7. `PreviewSchemaSessionLifecycleTest` 与新增测试类的分桶：`testDbSchemaAnnotator` 桶目前只登记了 `PreviewUnknownColumnAnnotatorTest`（`mpv-testing.gradle.kts:126`），新类默认进分片，无需登记；但**别用 `test --tests` 去跑 annotator 那个类**（会「No tests found」）。

---

## 六、边界与不做

- 不动 `createTempPsiFile` / `prepareConsole` 的**线程模型**（把整段 EDT 冷路径搬去 BGT 是另一个量级的改造，且会牵动 Runner 选择器与 PSI 时机）。本次只消除链内的单个慢操作。
- 不动 `execFileContentMatches`、`lastWrittenExecContent` 快照机制——W1 只是与它们对齐口径。
- 不动 `PreviewSideQuery` 已有的进度包装（冗余但无害），避免扩大写集。
- 不改 `SlowOperations` 相关 Registry、不引入 `allowSlowOperations`（243+262 双重证伪，已作废）。
- 不为「EDT 无文档」补快照兜底——那会引入「快照过期即误判分叉」的新风险；本轮先按「无文档 ⇒ 无冲突条件」收窄，前提 2 成立即闭环。

---

## 七、任务清单与写集

| 项 | 内容 | 写集 |
|---|---|---|
| W1 | `syncExecVfContentIfNeeded` 三态判定 + 抽出 `probeExecVfDivergence`；KDoc 更正「BGT 调用安全」为分线程说明 | `execute/DataSourceSelector.kt` |
| W1-t | 新增纯判定测试；改造 `ExecDocSyncTest`/`ExecDocSync2Test` 冲突用例 | `execute/ExecVfDivergenceProbeTest.kt`（新）、`execute/ExecDocSyncTest.kt`、`execute/ExecDocSync2Test.kt` |
| W2 | `McpDbExecutor` 增 `withDbBlockingContext` 并包住 `createBlocking` | `mcp/McpDbExecutor.kt` |
| W2-t | 新增进度上下文包装用例 | `mcp/McpDbExecutorProgressContextTest.kt`（新） |
| W3 | 删除 `Session.dispose` 两行手动摘除 + KDoc 定则（注册即所有权） | `ui/annotate/PreviewSchemaHighlighter.kt` |
| W3-t | 生命周期测试补「零 ERROR」用例 | `ui/annotate/PreviewSchemaSessionLifecycleTest.kt` |
| doc | 索引登记（`docs/索引/开发计划.md` 顶部一行） | 文档 |

三组写集互不重叠，可并行；W2 与 W3 为独立文件，W1 单独一路。

---

## 八、风险

1. **W1 收窄了 EDT 侧的防御面**：EDT + 无文档时不再验磁盘内容。理论上会漏掉「本项目之外有人改写 exec 文件」这一档——该类改写同时会让 `timeStamp` 变化，后续 refresh 会以磁盘内容重建 PSI，属可接受降级；但前提 2 若不成立需改判据。
2. **W2 下沉到 `McpDbExecutor` 会改变所有调用点的执行上下文**（含 MCP 工具链）。必须保留「已有指示器不替换」的条件分支，否则会静默削弱取消能力。
3. **W3 依赖 Disposer 后序销毁**：243 sources 已证；262 未在源码级核对，靠真机日志读数兜底（前提 6）。
4. 本轮若只改 W3（最小回归修复）也能消掉 2/5 条 SEVERE，但 A、B 两组仍会持续污染日志——建议三组同批收口。

---

## 九、附：与既有任务的关系

- **C 组是 `T-PreviewSchemaSessionLeak`（`ebfaf73f`）的回归**，属该任务收尾瑕疵，不新立编号；修好后应回填该计划文档的实施记录。
- **A 组是 `T-EC-04`（`d7843563`）与 `T-DevAcceptFix2 ④`（2026-10-06）两条定例的冲突残留**：后者已把同类读盘从 `execFileContentMatches` 摘掉，前者新增的函数没回填。
- **B 组是 `T-PreviewSideQuery` 已修同类问题的漏网调用点**，且推翻了该处 KDoc 里「真机池线程有上下文」的判断，需同步订正那段注释。

---

## 十、实施记录（2026-10-23，已实施）

**生产改动**（提交 `9809a5b7`，8 文件 +395/−15）：

1. **W1**（`execute/DataSourceSelector.kt`）：`syncExecVfContentIfNeeded` 改为线程感知入口
   （`allowDiskRead = !isDispatchThread`），探测抽纯函数 `probeExecVfDivergence(vf, sql, allowDiskRead)`
   三态判定（有文档只看内存态 + `isDocumentUnsaved` 补 F4 判据 / EDT 无文档短路 / BGT 保留读盘），
   KDoc 更正原「BGT 调用安全」的半截承诺并记录 T-DevAcceptFix2 ④ 漏回填事实。
2. **W2**（`mcp/McpDbExecutor.kt`）：顶层函数 `withDbBlockingContext`（非 object 成员，与
   `drainWarnings` 同款理由）包住 `createBlocking` 反射调用；缺上下文补
   `runProcess(EmptyProgressIndicator)`，已有指示器原样透传。
3. **W3**（`ui/annotate/PreviewSchemaHighlighter.kt`）：`Session.dispose` 删除两行手动摘除，
   init 与 dispose 双处注释写明「摘除职责只归注册时的 parentDisposable」。
4. **W2 附带订正**（`ui/PreviewSideQuery.kt`，仅注释）：其 KDoc 里「真机 IDE 的池线程恰好有
   上下文」已被本次真机日志推翻，按 §九 要求在原注释下追加订正段（包装本体未动，§六 口径）。

**测试**：新增 `ExecVfDivergenceProbeTest`（7 例，③ 用例以「盘上另写第三种内容后文档在场仍判不分叉」
钉死文档分支不读盘的不变式；⑤c 用 BGT 工作线程 + 泵 EDT 覆盖端到端修复）、
`McpDbExecutorProgressContextTest`（2 例）；`ExecDocSyncTest` 盘上分叉用例改写为
`testSyncVfContentIfNeededSkipsDiskProbeOnEdtWithoutDocument`（新旧实现判别器）；
`PreviewSchemaSessionLifecycleTest` 补 `testUninstallLogsNoPlatformErrors`
（`LoggedErrorProcessor` 捕获，`SqlPreviewPanelPrewarmWriteSafeTest` 同款 RecordingProcessor 口径）。

**读数**：

| 项 | 读数 |
| --- | --- |
| 定向 5 类（XML 判例数已核，防 `--tests` 静默漏跑） | **37/0/0/0**（Probe 7 + ProgressContext 2 + Lifecycle 6 + ExecDocSync 14 + ExecDocSync2 8） |
| 反向①（W1 摘 EDT 短路 → 无条件读盘） | **恰 3 红**（`testSyncEntryOnEdtNeverReadsDiskWithoutDocument`、`testNoDocOnEdtSkipsDiskProbe`、`testSyncVfContentIfNeededSkipsDiskProbeOnEdtWithoutDocument`），其余 18 绿 |
| 反向②（W2 摘兜底 → 裸调） | **恰 1 红**（`testBarePooledThreadGetsFallbackIndicatorInsideWrapper`） |
| 反向③（W3 回填手动摘除） | **恰 1 红**（`testUninstallLogsNoPlatformErrors`） |
| 还原后复跑 | 5 类全绿（37/0/0/0），探针残留 grep = 0 |

**诚实边界**：

- §4.2 末尾担心的「headless 下 SlowOperations 断言不可构造」成立与否**未走对照实验**——反向①
  实际用的判据是纯判定函数与入口行为（确定性 oracle），不依赖平台断言在测试里触发；
  headless 触发形态留作真机复核的附属观察项（前提 3 保持开放）。
- 前提 2 已在实施时以 243 sources 源码级闭环（`MemoryDiskConflictResolver.java:41-46`：
  无缓存 document 即直接 return，冲突收集不可能发生）——「EDT + 无文档 → 不读盘」收窄安全。
- 全量 `testShards` 按用户 2026-10-23 口径未跑；真机日志零 SEVERE 复核（§4.3）留用户侧。
