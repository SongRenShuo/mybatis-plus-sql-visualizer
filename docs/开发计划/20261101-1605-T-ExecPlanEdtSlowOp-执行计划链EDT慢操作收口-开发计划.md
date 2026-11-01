# 执行计划链 EDT 慢操作收口 — 开发计划

- 创建：2026-11-01 16:05
- 来源：`docs/验收文档/20261101-1600-OB面板自动化验收第二轮-验收文档.md` §三
- 立项依据：平台 243 已明示违规（`SEVERE SlowOperations … Plugin to blame: SQL Visualizer`），且为 S2-15 OB 面板真机验收的唯一前置

## 一、缺陷事实（真机日志实证，非推断）

点「执行计划」时平台抛：

```
15:55:13,017 SEVERE SlowOperations - IntelliJ IDEA 2024.3.7.1 Build #IU-243.28141.41
15:55:13,018 SEVERE SlowOperations - Plugin to blame: SQL Visualizer version: 2026.10.31
15:55:13,018 SEVERE SlowOperations - Last Action: cn.song.mybatisplus.visualizer.ConvertToSql
15:55:13,021 SEVERE SlowOperations - Slow operations are prohibited on EDT.
```

调用栈（截取本插件帧）：

```
SqlPreviewPanelExecutionExplainKt.explainSql (:111)
 → submitExplainOrStructured (:410/:504)
 → promptDataSourceThenRetryStructured (:744)
 → DataSourceSelector.prepareConsole (:586/:605)
 → chooseAndPrepare (:186/:292)
 → createTempPsiFile (:1313)
 → ReadAction.compute { PsiManager.getInstance(project).findFile(vf) }   ← EDT 上做 PSI 查找
 → FileManagerImpl.findFile → findDirectoryImpl（递归数十层）
```

## 二、机制判定

- 入口 `explainSql` 由按钮点击触发，**在 EDT**；
- 整条 `prepareConsole → chooseAndPrepare → createTempPsiFile` 是**同步调用**，没有线程归位；
- `findFile` 虽包在 `ReadAction.compute` 里，但 `ReadAction` 只保证读语义，**不改变所在线程**；
- 平台 243 对 EDT 上的 PSI/文件系统慢操作有硬断言（`SlowOperations.assertSlowOperationsAreAllowed`），因此必然 SEVERE；
- 代码注释自陈「findFile 沿用 ReadAction.compute 既有包装，与 T-M36.6-03 前行为一致」——即历史上就没做线程归位，只是当时没撞断言。

**影响面**：`createTempPsiFile` 是执行链与 EXPLAIN 链**共用**入口，所以执行 SQL、执行计划、回滚快照查询三条路都在这条链上。

**为什么它挡住 OB 面板**：结构化通道在 `promptDataSourceThenRetryStructured` 内建控制台，正走这条 EDT 同步链；断言一抛即 fail-closed 回退原生控制台，S2-15 的 OB 解析器与建议条根本没机会执行。

## 三、待验证前提（实施者先证伪再动手）

1. **P-1**：断言是否只在「点执行计划」路径出现，还是「执行 SQL」「回滚快照」同样命中？先在真机三路各点一次取日志（本券实施者可要求主控配合取读数，或自己用已有日志推断——**不得假定**）。
2. **P-2**：`PsiManager.findFile` 是否真的必要？调用方拿到 `psiFile` 后用它做什么（grep `chooseAndPrepare` 的 `temp.second` 消费点）。若只用于 `getAttachDataSourceRunners(psiFile, ...)`，则 PSI 不可省；若另有 `vf` 即可满足的路径，优先用 `vf`。
3. **P-3**：把该段移出 EDT 后，`mapDialectBeforePsi`（注释写明「setMapping 须 EDT」）的线程要求不能一并移走——它必须留在 EDT。**这是本券最容易改错的地方**：同函数内两段线程要求相反，不能整体搬。
4. **P-4**：`ReadAction` 在 BGT 上的语义——BGT 上执行 `ReadAction.compute` 是否需要先 `ProgressManager`/`EmptyProgressIndicator`（仓内已有先例 `pitfall-blocking-call-needs-progress-context`），若需要则一并补。

## 四、修法方向（择一，实施者按 P-1~P-4 证伪结果定稿）

**方案 A（推荐，最小语义变更）**：只把 PSI 查找这一段移出 EDT——
- `createTempPsiFile` 拆成两段：EDT 段（`syncExecVfContentIfNeeded` + `mapDialectBeforePsi`，必须 EDT）+ BGT 段（`refreshAndFindFileByIoFile` + `PsiManager.findFile`，慢操作）；
- 用既有 `onEdtForLastUsed` 同款模式的**反向**：EDT 入口检测到自己在 EDT 就 `executeOnPooledThread` 跑慢段，完成后 `invokeLater` 回调继续后续链；
- 契约影响：`createTempPsiFile` 变**异步**，调用方 `chooseAndPrepare` 的后续逻辑要挪进回调。这是本方案的主要成本。

**方案 B（绕开 PSI）**：若 P-2 证伪「PSI 必需」，改用 `FileDocumentManager.getDocument(vf)`（平台内部自带线程语义）替代 `PsiManager.findFile`，同步契约不变、改动最小；但会改 `getAttachDataSourceRunners` 的入参类型，波及面需实测。

**禁止**：为消除断言而关掉 `SlowOperations` 断言（改 vmoptions / 加 suppress）——那是掩盖而非修复，且会连坐掩盖后续新增的慢操作。

## 五、验收

- **定向**：新增/调整的测试类全绿；若为方案 A 需补「EDT 入口不直接做 PSI 查找」的形态守卫（照 `LastUsedWriteOnEdtGuardTest` 的写法：源码扫描 + 大括号配平取函数体 + 自证语料）。
- **反向验证**：摘掉线程归位 → 守卫或用例必须变红，两次读数入报告。
- **真机三路回归**（主控执行，实施者只交代码）：执行 SQL / 执行计划 / 回滚快照各点一次，`grep "SEVERE.*SlowOperations.*SQL Visualizer"` 零命中为通过；同时确认三条路的既有行为不变（执行出结果集、执行计划出面板或回退、回滚快照出表格）。
- **OB 面板**：本券修完后，S2-15 的 OB 结构化面板才具备验收条件（解析器/建议条真机可见）。

## 六、红线

- 不新增执行通道、不改渲染语义、不动 `SlowOperations` 断言配置。
- `mapDialectBeforePsi` 的 EDT 要求不得被顺手移走（见 P-3）。
- 巨文件红线 2000 行（`GiantFileBudgetTest`）：`DataSourceSelectorResults.kt` 现 1992 行，只剩 8 行余量——**若方案 A 需要加代码，先拆文件**（把 `createTempPsiFile` 一族整段抽到 `ExecTempFileFactory.kt`），不许硬塞。
- 只跑定向，不跑全量；不 clean、不 `--stop`；不 push、不合 master。
