# T-DialectGateExit：方言闸门阻断态出口与绑定后重渲染

2026-10-20。来源：用户在 dev 包验收 UI-038/UI-039 修复时，回滚弹窗报「该语句无法自动生成回滚。已阻止执行：当前 SQL 按 PostgreSQL 渲染，但所选数据源的实际方言是 MySQL……」（UI-040）。用户追问「这种失败原因是不是让用户可以选择数据源会更好些」，据此落成计划。

## 一、现象与判定

截图现场（2026-10-20，243 沙盒）：

- 预览 meta 行：`方言: PostgreSQL (模块已学数据源)`；右上 `数据源（上次使用）：mpj_demo@localhost`
- 回滚弹窗：`该语句无法自动生成回滚。` + `已阻止执行：当前 SQL 按 PostgreSQL 渲染，但所选数据源的实际方言是 MySQL。当前模型无法无损重追，或显示文本已被编辑。请重新生成 SQL 或切换目标后再执行。`
- 预览 SQL 本身正确（`SET amount = amount * 1` / `WHERE status = 1`）——**与占位符修复无关**，纯粹是方言闸门。

判定：闸门姿态是对的（跨语法族 SQL 送进目标库必然出错），**缺的是出口**——阻断态只给一行红字，没有「换个目标」或「按目标方言重渲染」的按钮。

## 二、根因（代码证据）

1. `RollbackSqlDialog.runSnapshotQuery`（`ui/RollbackSqlDialog.kt:374-383`）自己拼闸请求，把 `canLosslesslyRerender` **写死 false**；而面板侧早就有 `SqlPreviewPanel.executionDialectGateRequest(...)`（`ui/SqlPreviewPanelExecution.kt:656-668`）按模型真实能力计算该位。
2. `ExecutionDialectGate.evaluate`（`execute/ExecutionDialectGate.kt:83-96`）只有在 `AUTO && canLosslesslyRerender && userTextUntouched` 时才返回 `AUTO_RERENDER_REQUIRED`（可重渲染档）；写死 false 后必然落到 `RERENDER_UNAVAILABLE`（阻断）。
3. 面板已有无损重渲染能力：`SqlPreviewPanel.rerenderForExecutionTarget(targetDialectName)`（`ui/SqlPreviewPanelExecution.kt:697-740`）——按模型 copy 目标方言并重渲染；面板自己的闸门处理 `onExecutionDialectGate` 在 `AUTO_RERENDER_REQUIRED` 时会自动调用它。**回滚弹窗没有复用这条链**。
4. 「切换数据源」不可达：`promptDataSourceThenRetrySnapshot`（`ui/RollbackSqlDialog.kt:425-464`）只在 `target == null`（未绑定）时被调用；已绑定但方言冲突时没有任何选择器入口。且 `DataSourceSelector.prepareConsole` 的选择策略是「唯一数据源/last-used 命中 → 静默」（`execute/DataSourceSelectorResults.kt:299-317` 的 last-used 静默命中、`:388-410` 的共享控制台复用），**没有强制弹选择器**的口子。
5. P1（三者不一致）：`refreshDialectStatusRow`（`ui/DialectStatusRefresh.kt:95-117`）在绑定/执行后重算决策，但只 `applyDialectStatusSegment()` **重组状态行**，不重渲染 SQL——于是「状态行说 A、SQL 文本是 B、执行目标是 C」可以并存。

## 三、方案

### P0-1 回滚弹窗改用面板真实闸请求

`runSnapshotQuery` 不再手拼请求，改用 `p.executionDialectGateRequest(Intent.QUERY, userTextUntouched = true)`（`canLosslesslyRerender` 由模型决定）。`renderedDialectName` 语义不变。

### P0-2 阻断态两个出口按钮

- `PreviewSideQuery.Result.Blocked` 增加 `verdict: ExecutionDialectGate.Verdict? = null`，`run` 在方言闸阻断时填入 → 对话框能区分「方言冲突」与「只读护栏 / 目标未知」。
- 快照块底部新增一行（默认隐藏）：
  - `[按目标方言重新渲染并重试]`：`p.rerenderForExecutionTarget(verdict.targetDialectName)` → 成功则重建请求、**重试一次**（只重试一次，防循环）；失败就地显示原因。`MANUAL_CONFLICT`（用户手动选了方言）时**不显示**——闸门语义是「插件不覆盖手动选择」。
  - `[切换数据源]`：走强制选择器（P0-3），绑定成功后自动重试快照查询（复用 UI-037 的 `awaitDataSourceBoundThen` 链路）。
- 仅在方言类阻断时显示；只读护栏/目标未知仍只有文案。

### P0-3 强制弹数据源选择器

`DataSourceSelector.prepareConsole(..., forceChoose: Boolean = false)` → `chooseAndPrepare(forceChoose = ...)`：
- `forceChoose = true` 时跳过 `reuseSharedConsole` 与 last-used 静默命中，让平台 runner 弹窗出现；
- 默认 false，既有行为逐字不变（两处判据各自加一个 `&& !forceChoose`）。

### P1 绑定后按目标方言重渲染

`refreshDialectStatusRow` 在决策升级后判断：新决策来自 `Source.BOUND_DATASOURCE`、方言与当前渲染不同、且**编辑器文本未被用户编辑**（`lastRenderedSql == 编辑器文本`）→ 调 `rerenderForExecutionTarget(新方言)`。这样绑定即完成「状态行 / SQL 文本 / 目标」三对齐，回滚、DML 预演、EXPLAIN 三条闸门都不会再因为旧渲染方言被挡。

## 四、改动清单

- `ui/RollbackSqlDialog.kt`：请求改用面板 API；阻断态按钮行 + 重渲染/切换/重试；测试缝 `snapshotGateRequestForTest` / `snapshotRerenderForTest`。
- `ui/PreviewSideQuery.kt`：`Blocked` 携带 `verdict`。
- `execute/DataSourceSelector.kt` + `execute/DataSourceSelectorResults.kt`：`forceChoose` 贯穿。
- `ui/DialectStatusRefresh.kt`：绑定后按目标方言重渲染（带未编辑护栏）。
- 资源：新增按钮文案 key（中英）。

## 五、测试方案

1. 回滚弹窗：`AUTO_RERENDER_REQUIRED` 阻断 → 两个按钮可见；点重渲染 → 重渲染被调用且重试后成功填表；`RERENDER_UNAVAILABLE` → 重渲染可点（失败就地报错）；`MANUAL_CONFLICT` → 只显示切换数据源；只读护栏阻断 → 不显示按钮。
2. 切换数据源：点击后 `prepareConsole` 以 `forceChoose=true` 调用（测试缝断言参数），绑定回调触发重试。
3. `forceChoose` 判据：抽纯函数（是否静默复用 last-used / 是否复用共享控制台）单测两态。
4. P1：面板级测试——决策升级为 BOUND_DATASOURCE 且方言不同 → 调 `rerenderForExecutionTarget`；文本被编辑时不重渲染。
5. 反向验证：把 `forceChoose` 的两处判据摘掉 → 强制选择器用例必红；把 P1 的重渲染摘掉 → 一致性用例必红。

## 六、风险与边界

- 自动重渲染会改写编辑器文本：只在「绑定源方言更强 + 文本未编辑」时做，且模型不支持无损重渲染（XML/批量/分页行）时静默跳过。
- `MANUAL_CONFLICT` 不给重渲染按钮，尊重用户手动选择。
- 重试只做一次，避免「渲染→仍不匹配→再渲染」循环。
- 真机复验由用户承接。

## 七、待验证前提

1. `p.executionDialectGateRequest(Intent.QUERY, ...)` 在「无面板 current」时（测试夹具）返回 `renderMode = ?`、`renderedDialectName = null` → 会落到 `RENDER_UNKNOWN_QUERY_CONFIRM`；因此测试必须用 `snapshotGateRequestForTest` 注入冲突请求（实施时先证伪）。
2. `rerenderForExecutionTarget` 对 XML/批量模型返回 false（代码可判：`old.xmlModel != null || paginationLimitLine != null` 直接 return false）。
3. `prepareConsole` 的 `forceChoose` 只影响「是否弹选择器」，不影响方言闸本身（闸在 session 建好后评估）。

## 八、实施记录

落地与计划的差异：

1. **P0-2 的「重试」要重算快照 SQL**（计划没写）：`snapshotSelectSql` 是 `RollbackSqlGenerator` 从语句 AST 拼的，标识符引用跟随输入文本的方言；重渲染后若沿用旧文本，等于把旧方言的引用样式提交给新目标。改为 `refreshedSnapshotSql()` 按新预览文本重算（主键列沿用对话框输入），重算失败回退原文本不阻断。
2. **面板无当前预览时的兜底**（计划没写）：旧实现 `renderedDialect = p.current?.effectiveDialect ?: MySqlDialect`，换成面板闸请求后 `renderedDialectName` 会变成 null → 被闸门判成 `RERENDER_UNAVAILABLE`，既有 headless 用例与异常态会误判。保留 MySQL 兜底。
3. **P1 的接线守卫**：`refreshDialectStatusRow` 的池线程 + EDT 链在 headless 下难端到端驱动，改按仓内惯例加源码守卫（`DialectStatusRefreshWiringGuardTest`）钉「EDT 段调用 `rerenderIfBoundDialectDiffers(chosen)`」与「两处静默判据都接了 forceChoose」。

测试清单：

- `RollbackSqlDialogTest` 四例：方言冲突露出口行 + 点重渲染后按目标方言重渲染并重试成功；手动方言（MANUAL_CONFLICT）不给重渲染；非方言阻断（渲染未知）不露出口行；切换数据源触发选择器。
- `DialectGateExitPolicyTest` 三例：`allowsSharedConsoleReuse` / `allowsSilentLastUsed` / `shouldRerenderForBoundDialect` 的判据。
- `DialectStatusRefreshWiringGuardTest` 两例：绑定后重渲染接线、forceChoose 两处判据接线。

反向验证（三处探针）：不登记 verdict / 摘 P1 接线 / 摘 forceChoose 判据 → **4 红**（3 条弹窗用例 + forceChoose 守卫）。过程中发现 P1 接线守卫**被注释掉的调用骗成假绿**（`t.contains("rerenderIfBoundDialectDiffers(chosen)")` 命中了 `// rerenderIfBoundDialectDiffers(chosen)`）——已改为「未注释整行匹配」，单独复验探针 2 后**恰 1 红**。这是仓内既有「守卫可能看不见它要防的站点」的又一实例：守卫自身必须能挡住注释掉的修复。

### 8.1 改动清单

| 文件 | 改动 |
| --- | --- |
| `ui/PreviewSideQuery.kt` | `Result.Blocked` 增 `verdict`（只读护栏阻断时为 null） |
| `ui/RollbackSqlDialog.kt` | 闸请求改用面板 API（无预览时 MySQL 兜底）；出口行两按钮；重渲染后按新预览文本重算快照 SELECT 再重试一次；`snapshotGateRequestForTest` / `snapshotRerenderForTest` 测试缝；`promptDataSourceThenRetrySnapshot` 增 `forceChoose` |
| `execute/DataSourceSelector.kt` | `prepareConsole` 增 `forceChoose` 并透传 |
| `execute/DataSourceSelectorResults.kt` | `chooseAndPrepare` 增 `forceChoose`；新增纯判据 `allowsSharedConsoleReuse` / `allowsSilentLastUsed` 并在两处静默路径生效 |
| `ui/DialectStatusRefresh.kt` | P1：EDT 段在决策升级后调 `rerenderIfBoundDialectDiffers`；新增纯判据 `shouldRerenderForBoundDialect` |
| `messages/*.properties` | 5 个 key（重渲染/切换数据源/重渲染失败 + 两个 tooltip，中英） |
| `test/ui/RollbackSqlDialogTest.kt` | 新增 4 例 |
| `test/ui/DialectGateExitPolicyTest.kt` | 新增 3 例判据 |
| `test/ui/DialectStatusRefreshWiringGuardTest.kt` | 新增 2 例接线守卫（整行匹配） |

### 8.2 读数

- 定向：`RollbackSqlDialogTest` 32/0/0、`DialectGateExitPolicyTest` 3/0/0、`DialectStatusRefreshWiringGuardTest` 6/0/0、`PreviewSideQueryTest` 10/0/0、`ExecutionDialectGateTest` 17/0/0 —— 合计 **68/0/0**。
- 反向验证：三探针 4 红；修正守卫后探针 2 恰 1 红（见上）。
- 定向回归（execute / ui / converter 三包）**3083/3/0**——3 条失败即那三条已知环境用例（`InjectionGateTest`×2、`UnknownParamGateTest`×1），本改动面零新增失败。
- 全量门禁（`testShards --no-configuration-cache --continue`，单路）**7982/3/8**，三条失败即上述三条既有用例（已确认是 UI-035 后的期望漂移，非产品缺陷，修复见第九节）。

## 九、三条既有失败的归因与修复（后续批次）

三条全量失败（`InjectionGateTest.testEXPLAIN含raw段_同门控`、`InjectionGateTest.testEXPLAIN无raw段_不弹门`、`UnknownParamGateTest.testEXPLAIN含UNKNOWN参数_同门控`）是同一条断言 `assertProceeded`：点「执行计划」放行后断言 `resultPanel` 状态非 `EMPTY`，实际停在 `EMPTY`。

归因（代码可判）：`explainStructuredRender` 默认 false → `ExplainPanelChannel.decide` 返回 `DirectSilent` → `submitExplainDirect`；该函数在无绑定数据源时（headless 无控制台）命中 UI-035 新增分支——`showExplainErrorDialog("…无数据源…", openDatabase = true)` 后 `return`，而 `resultPanel.showLoading()` 写在这个 `return` 之后。也就是说：**产品行为是对的（无源不空跑一次必然失败的执行），用例期望停在 UI-035 之前**。三条在干净 dev HEAD 上同样红（`git stash` 基线复现），与本轮改动无关。

修法（测试侧，产品代码零改动）：把断言从「放行 = 面板进入执行链（`state != EMPTY`）」改成「放行 = 给出无数据源引导」——用 `explainDialogSinkForTest` 截获 `showExplainErrorDialog` 并断言文案含 `panel.explain.noDataSource`；`${}` 门的「弹几次 / NO 中止 / YES 放行」断言全部保留。

**为什么不是「注入目标」**：第一版照 `ExplainSideQueryPanelTest` 的做法注入 `explainTargetForTest`（带 `getUrl()` 的 MySQL 目标），仍然红。探针实测（把读数带进断言消息）得到 `state=EMPTY sinkHit=true dialogs=0 dialect=null text=35`——放行后**确实**走到了 `showExplainErrorDialog(openDatabase = true)`，但目标注入对这条分支不生效（引导来自「无绑定数据源」判定，与 `explainTargetForTest` 无关）；而把 runner 改成同步回调又会 `showIdle() → showEmpty()` 立刻把状态置回 `EMPTY`，让断言更没意义。结论：这几条用例的可观测契约已经变成「引导对话框」，按它断言才准确。

读数：`InjectionGateTest` **18/0/0**、`UnknownParamGateTest` **6/0/0**；修后全量门禁 **7982 / 0 failed / 0 errors / 8 skipped**（`BUILD SUCCESSFUL in 17m41s`，`testShards --no-configuration-cache --continue`，单路）——首次全绿。

顺带：门禁进度包装 `dev-support/testShardsProgress.ps1` 在实跑中暴露并修掉两个自身缺陷——①分片完成判定拿旧 XML 当本轮产物（改按 mtime ≥ 启动时刻过滤）；②`Get-Content` 默认 ANSI 读 UTF-8 的 TEST XML 导致中文用例名乱码、解析失败被 `catch {}` 吞掉（汇总 395 vs 真实 1568）——改 `-Raw -Encoding UTF8` + `DocumentElement.GetAttribute` 属性式读法，实测与 Python 逐类核对一致。
