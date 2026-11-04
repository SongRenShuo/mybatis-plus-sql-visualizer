# T-ExecSlowOps 回 port：执行临时文件构建移出 EDT（2026-11-04）

- 立项：2026-11-04 15:06
- 目标分支：`master`（当前 tip `00b71210`，含五项回 port 与发行材料）
- 阻塞：`2026.11.4-beta.1` 内测发行前必须修掉，否则用户会看到「IDE error occurred」横幅

## 一、缺陷（真机实证）

用户 2026-11-04 用 master 包（jar 14:50）打开 XML 预览，`idea.log` 本轮出现 **36 条** `Slow operations are prohibited on EDT`，栈固定：

```
SqlPreviewDialog.<init> → showResult → prepareDefaultConsole
  → SqlExecutor.prepareConsole → DataSourceSelector.prepareConsole
    → chooseAndPrepare:292
      → createTempPsiFile:1529
        → ReadAction.compute { PsiManager.findFile(vf) }
          → FileManagerImpl.findDirectory → ProjectFileIndexImpl.isUnderIgnored
            → WorkspaceFileIndexDataImpl.ensureIsUpToDate   ← 慢操作
```

`ReadAction` 只保证读语义、**不改变线程**——PSI 首次实例化实际跑在 EDT。

**三路共用入口**：执行 SQL / 执行计划 / 回滚快照都走 `prepareConsole → chooseAndPrepare → createTempPsiFile`，冷路径（缓存未命中）三路皆可达。

## 二、根因：回 port 漏了这一笔

dev 侧已由 `ba314f42`（2026-11-01，「执行临时文件构建移出 EDT」）修复：`chooseAndPrepare` 改调 `createTempPsiFileAsync`，后续全链迁入 `continuePrepareAfterTempFile`，由 `onReady` 驱动。

本轮五项回 port 清单（UI-003 / UI-004 / A / B / C）**未包含这一笔**——当时从真机日志归类的三类线程违规里，这条栈没出现（那轮方言为 PostgreSQL、`runners=7`，缓存命中率高），漏判了。

**对比读数**（同一操作序列）：

| 包 | 本轮该栈次数 | 历史总数 |
|---|---|---|
| dev 13:58 | 0 | 27（`ba314f42` 之前累积） |
| master 14:50 | 9 | — |

## 三、修法

照 dev `ba314f42` 的形态，在 master 现有结构上重放（**不引入 dev 的 `ExecTempFileFactory.kt` 整文件**——master 无该文件，整搬会带来 dev 其它拆分依赖）：

1. 在 `execute/DataSourceSelectorResults.kt` 内新增一个等价于 dev `createTempPsiFileAsync` 的异步包装（约 20 行）：
   - 非 EDT → 就地建 + `invokeLater(nonModal)` 回调
   - EDT → `executeOnPooledThread { createTempPsiFile(...) }` + `invokeLater(nonModal)` 回调
   - 回调前 `revalidateOnEdt`：PSI 在异步窗口内失效时重解析（照 dev 的 T-ExecPsiInvalid 兜底）
2. 把 `chooseAndPrepare` 第 292 行之后的全部逻辑抽成 `continuePrepareAfterTempFile(...)`，由异步回调驱动。
3. 单飞门与失败路径不变：`createTempPsiFile` 返回 null 时的 `failPrepareRequests` 收口必须在回调内保留。

**必须保持**：
- 投递模态是 `ModalityState.nonModal()`（write-safe），**不是** `any()`——续接链会写文档，`any()` 恒 write-unsafe（仓内既有定案与 `ExecWriteSafeDispatchGuardTest` 守卫）。
- 控制台挂载、runner 枚举、弹窗等 UI 动作仍在 EDT。
- 缓存命中路径（`consumeCachedTempPsiFile`）行为不变。

## 四、待验证前提（实施者先证伪）

1. master 的 `DataSourceSelectorResults.kt` 第 292 行之后是否与 dev `ba314f42` **修复前**同构（若 master 该段已含其它增量，拆分要按实际结构重做）。
2. master 是否已有 `revalidateOnEdt` 或等价兜底（没有则一并加，否则异步窗口内 PSI 失效会硬抛 `PsiInvalidElementAccessException`）。
3. `chooseAndPrepare` 内是否有只能 EDT 执行的语句被卷入异步段（弹窗、工具窗、文档写）——有则必须留在续接回调内。

## 五、验收

- **定向**：执行链既有测试全绿（`ChooseDataSourceTakeoverTest` / `PrepareSingleFlightGateTest` / `PrepareGatePendingTimeoutTest` / `ExecTempFile*` / `CachedTempPsiFileTest` / `PreviewPreparePickTest` / `DialectGateRenderDialectTest` / 三个线程守卫）。
- **反向验证**：把异步包装改回同步调用 → 必须有一条守卫或测试红（若无现成守卫，补一条源码形态守卫：`chooseAndPrepare` 不得直接调用 `createTempPsiFile`）。
- **真机**：重装 master 包，打开 XML 预览 + 执行，`idea.log` 该栈归零。
- 证据层级如实标注：源码守卫/定向测试 ≠ 真机消除。

## 六、实施记录

2026-11-04 回 port 完成（本次提交）。

### 6.1 前提核查（先证伪）

1. **master 该段与 dev 修复前同构**——成立。`git show ba314f42 -- .../DataSourceSelectorResults.kt` 与 master 现状逐字比对：`createTempPsiFile`（F2a 失败分级 / F2b WIRA refresh / 缓存命中 / 方言映射 / findFile）与 `chooseAndPrepare` 292 行之后的绑定对齐、Runner 枚举、last-used、弹窗、建链全链一致。master 唯一增量是 UI-003 的 `PreviewPreparePick.shouldPopup` 静默分支（dev 同位置是学习绑定分支），拆分按 master 实际结构保留。
2. **master 无 `revalidateOnEdt`**——成立（全仓 0 处），按计划新增。
3. **EDT-only 语句**——`showRunnerPopup`、`prepareInConsole`（内部还有 `warmConsolePsiThen` 预暖）、`openDatabaseToolWindow`、`prepareGate.beginInteraction/endInteraction`、`rememberRunnerName`、`callback` 全部落在续接函数里，经 `invokeLater(nonModal)` 回到 EDT。另查出计划未列的两处线程细节：
   - `createTempPsiFile` 的 refresh 段原本**无条件**包 `WriteIntentReadAction`。慢段搬到池线程后这一形态就是 T-PrewarmWilDeadlock 自锁（持 WIL 等刷新会话推进），必须改成「EDT 分支保留 WIRA / BGT 裸调」（照 dev 形态）。
   - 本版 `consumeCachedTempPsiFile` 只判 `vf.isValid`（VFS 判定，不需读锁），不含 dev HEAD 后来加的 `psi.isValid`，缓存命中路径在 BGT 上无读锁风险。

### 6.2 改动

`execute/DataSourceSelectorResults.kt`：
- 新增 `createTempPsiFileAsync`：非 EDT 就地建、EDT 走 `executeOnPooledThread`；两条分支都经 `invokeLater(..., ModalityState.nonModal())` 回调（**nonModal，不是 any()**）。
- 新增 `revalidateOnEdt`：`psi.isValid` 与失效重解析的 `findFile` 各自包 `ReadAction`（UI-002 口径），重解析仍失败返回 null 走既有建链失败文案。
- `chooseAndPrepare` 临时文件之后的整段抽成 `continuePrepareAfterTempFile(...)`，由回调驱动。补**迟到续接守卫**（`isPrepareActive()` 不成立即放弃，异步化引入的新竞态面）；两处 `return@chooseAndPrepare` 改普通 `return`；三处 `if (ownsGate)` 改 `if (prepareOwner != null)`（等价：`prepareOwner = queuedIntent.takeIf { ownsGate }`）。
- `createTempPsiFile` 的 refresh 段按 `isDispatchThread` 分流：EDT 保留 WIRA，BGT 裸调。

`convention/ExecTempFileEdtGuardTest.kt`（新增）：六条源码形态判据（同步直调 / 三段式 + nonModal / revalidateOnEdt / 两处读锁 / refresh 线程分流 / 续接收口）＋自证语料；已登记进 `GuardSelfTestCoverageMetaGuardTest`。

`execute/PrepareSingleFlightGateTest.kt`：取消路径终结器断言随函数拆分改为 `if (prepareOwner != null) …`（语义等价，注释说明原因）。

### 6.3 定向读数（逐类核对 test-results XML，0 失败）

| 类 | 例数 | 失败 |
|---|---|---|
| ChooseDataSourceTakeoverTest | 3 | 0 |
| PrepareSingleFlightGateTest | 26 | 0 |
| PrepareGatePendingTimeoutTest | 3 | 0 |
| CachedTempPsiFileTest | 3 | 0 |
| PreviewPreparePickTest | 8 | 0 |
| ExecTempFileFixTest（master 无 ExecTempFileFactory，此为本仓等价类） | 6 | 0 |
| ExecTempFileEdtGuardTest（新增） | 2 | 0 |
| ExecDialectRoutingTest | 28 | 0 |
| ExecutionDialectGateTest | 21 | 0 |
| RefreshLateErrorWatchTest | 4 | 0 |
| ErrorPositionWiringGuardTest | 2 | 0 |
| TakeoverUpdateThreadGuardTest | 2 | 0 |
| ConsolePsiWarmGuardTest | 2 | 0 |
| ExecWriteSafeDispatchGuardTest | 2 | 0 |
| SyncRefreshWriteIntentGuardTest | 7 | 0 |
| GuardSelfTestCoverageMetaGuardTest | 8 | 0 |
| BannedApiScanRuleTest | 12 | 0 |
| DialectGateRenderDialectTest | 14 | 0 |
| DialectStatusRefreshWiringGuardTest | 6 | 0 |
| IdentityWholeScriptWiringGuardTest | 4 | 0 |

合计 20 类 161 例，全绿。

### 6.4 反向验证

临时把 `chooseAndPrepare` 改回同步直调 `createTempPsiFile` → `ExecTempFileEdtGuardTest` 必红，实测：

```
执行临时文件构建的 EDT 慢操作纪律被破坏……违规 1 处：
chooseAndPrepare 直接调用 createTempPsiFile——EDT 上首次实例化 PSI，平台 SlowOperations SEVERE（真机 36 条）
```

移除探针后复绿（该类的两条用例 0 失败）。

### 6.5 遗留与边界

- **真机复验未做**（本轮禁 GUI）：源码守卫 + 定向测试只证「形态与行为不回退」，不等于 `idea.log` 该栈归零，需下一轮真机确认。
- 顺带发现一处**与本次无关的既有红**：`ExplainAskPasswordChannelGuardTest.渲染方言缺失时不得判成未知阻断执行` 的锚点 `renderedDialectName = result?.effectiveDialect?.name` 在 master tip 已不存在（UI-004 改为 `effectiveDialectName = …` + `DialectGateRenderDialect.resolve`；`git show HEAD:…SqlPreviewPanelExecution.kt` grep 计数 0）。属 UI-004 留下的陈旧守卫，本轮未动。

