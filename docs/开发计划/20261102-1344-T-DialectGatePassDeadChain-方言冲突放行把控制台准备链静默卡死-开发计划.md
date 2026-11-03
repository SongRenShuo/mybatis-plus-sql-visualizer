# T-DialectGatePassDeadChain：方言冲突「放行」把控制台准备链静默卡死（执行失败 + 换源无处可点）

> 2026-11-02 立项。来源＝用户报障「日常 IDEA 2026.2 执行 SQL 失败、也无法切换数据源」＋两张预览弹窗截图，
> 配合本机 `idea.log`（IU-262.11879.100，插件 **v2026.11.2**，`lib/mybatis-plus-sql-visualizer-2026.11.2.jar`）。
> 用户当前代码＝master `71ace631`，行号与日志逐帧吻合。

## 一、结论

| 项 | 判定 | 依据 |
|---|---|---|
| 是不是插件缺陷 | **是，P0**｜方言闸门「放行」分支把准备链自己的执行意图标成已完成，准备链随后静默早退，既不挂控制台也不释放单飞门 | `DataSourceSelector.kt:234-237` × `DataSourceSelectorResults.kt:337/359` |
| 触发条件 | **渲染方言 ≠ 所选数据源方言**（本例 GaussDB 渲染 + PostgreSQL 源 → `RERENDER_UNAVAILABLE`；`MANUAL_CONFLICT` 同码路径） | 截图 2 文案逐字等于 bundle `execute.dialectGate.rerenderUnavailable`（`:396`） |
| 用户可见后果 | ① 控制台永不挂载（`attachConsole` 一次都没到）→ 顶部常驻「未绑定数据源」；② 单飞门被占 60s，期间**执行 / 切换数据源 / 刷新**全部只排队，15s 后弹「数据库控制台准备超时，未提交 SQL。请重新选择数据源后重试」；③ 那条文案让用户去「重新选择数据源」，而预览弹窗里**根本没有这个入口** | 见 §二、§三 |
| 影响面 | 只要方言冲突就中招：GaussDB 渲染 + PG 源、MySQL 预览 + 上次使用的 PG 源、手动指定方言与源不一致……**预览准备链同样死**（PASS_THROUGH 链共用同一 `onSubmission` 完成语义），所以连控制台都建不出来 | `DataSourceSelectorResults.kt:229-233` |
| 起始版本 | **`8619a3af`（2026-10-25「方言冲突不再阻断执行」）** 起；`75cff7c4`（10-26 T-DialectGatePass）又移除了唯一的换源入口 | `git show 8619a3af -- …/DataSourceSelector.kt` |
| 13:24 为什么好的 | 那次在另一个工程、方言与源都是 PostgreSQL（`MATCH`），压根不进冲突分支 | idea.log `13:24:29,576-29,844` |

一句话：**「方言不影响执行」这条用户定案被实现成了「方言冲突时执行链直接死掉」**——闸门返回 `true`（放行），
但放行之前已经把执行意图结算成 `BLOCKED`，下游拿这个意图当「本次请求已结束」的证据，于是整条准备链原地消失。

## 二、日志证据（同一台机、同一插件版本、相隔 5 分钟）

### 2.1 成功的一次（13:24，工程 cyzc-platform，方言 PostgreSQL = 源 PostgreSQL）

```
13:24:29,576 [M36] executeSql: len=440, prepared=false
13:24:29,598 [T-FirstExec F1] prepare gate PROCEED: owning prepare chain (executeAfterPrepare=true)
13:24:29,658 [M36] dialect pre-mapped to PostgreSQL for mpsql-exec.sql
13:24:29,679 [M36] runners=1 → single runner, run directly
13:24:29,684 [O-145] session created: MessageBusSession, target=4c4efe62…/PostgreSQL, execute=true
13:24:29,833 [M36] attachConsole -> JdbcConsole{title=mpsql-exec.sql}      ← 149ms 后到位
13:24:29,844 [M36] editor=EditorImpl[file://…/cyzc-platform/.idea/sql-visualizer/exec/mpsql-exec.sql]
```

### 2.2 失败的一次（13:29，工程 mybatis-plus-sql-visualizer-demo，方言 GaussDB ≠ 源 PostgreSQL）

```
13:29:05,252 [M36] executeSql: len=206, prepared=false
13:29:05,255 [T-FirstExec F1] prepare gate PROCEED: owning prepare chain (executeAfterPrepare=true)
13:29:05,266 [M36] exec file updated via document+save: mpsql-exec.sql
13:29:05,277 [M36] dialect pre-mapped to GaussDB for mpsql-exec.sql (ds=null)
13:29:05,279 [M36] runners=1 → single runner, run directly
13:29:05,281 [O-145] session created: MessageBusSession, target=8d6c2a1b…/PostgreSQL, execute=true
13:29:05,287 [M36] runRunner invoked OK
（之后：没有任何 attachConsole / editor= / FAILED / aborted —— 链子就断在这里）
13:29:12,560 [T-FirstExec F1] prepare chain in flight: execute intent queued      ← 第 2 次点击
13:29:30,255 [T-FirstExec F1] prepare chain in flight: execute intent queued      ← 第 3 次点击
13:29:36,454 [T-FirstExec F1] prepare chain in flight: execute intent queued      ← 换面板后再点
13:31:04,294 [T-FirstExec F1] prepare gate PROCEED: owning prepare chain          ← 60s 逃生到期后才自愈
```

对照读数（`grep -c` 于 `idea.log`）：`attachConsole ->` 全日志仅 1 次（13:24 那次）；
`prepareInConsole FAILED` / `console editor unavailable` / `prepare chain aborted` 均为 **0 次**；
13:29–13:33 区间内 `attachConsole|editor=` 命中 **0**。

即：**既没成功也没失败也没被中止**——典型的「某个 `if` 静默 return 了」。

`session created`（455592）→ `runRunner invoked OK`（455598）只隔 6ms，而成功链的 `attachConsole` 单步就要 149ms，
反证 `attachConsole` 这一次根本没被调用。

## 三、根因（代码级）

### 3.1 意图被提前结算

`DataSourceSelectorResults.kt:229-233`：

```kotlin
val queuedIntent = QueuedExecuteIntent(onConsole, onEditor, onError, onErrorRich, …, onSubmission)
val onSubmission = queuedIntent.onSubmission     // ← 之后整条链用的 onSubmission 都是「结算这个意图」
```

`QueuedExecuteIntent.onSubmission`（`PrepareSingleFlightGate.kt:233`）= `{ complete(it) }`，
而 `complete` 会置 `completed=true`（`:235-239`）。

consumer 里的闸门调用（`DataSourceSelectorResults.kt:348-350`）把这个 `onSubmission` 原样传进去：

```kotlin
val allowed = prepareGate.duringInteraction(prepareOwner) {
    allowDialectGate(project, dialectGateRequest, actualTarget, onDialectGate, onError, onSubmission)
}
```

### 3.2 闸门在「放行」前先报了 BLOCKED

`DataSourceSelector.kt:234-237`：

```kotlin
if (verdict.status != Status.MATCH && !needsConfirmation) {
    // 先报告未提交，后交付原有详细红字
    onSubmission?.invoke(ExecutionSubmission.BLOCKED)     // ← 放行态也走这里
}
val allowed = when (verdict.status) {
    …
    Status.MANUAL_CONFLICT, Status.RERENDER_UNAVAILABLE -> { onError?.invoke(dialectGateMessage(verdict)); true }
    …
}
```

`8619a3af` 把这两态从「阻断」改成「放行（`true`）」，但上面那行 `BLOCKED` 没跟着收窄——
**放行也报 BLOCKED**，于是 `queuedIntent.completed = true`。

### 3.3 下游把「意图已完成」当成「本次请求结束」

`DataSourceSelectorResults.kt:337`：

```kotlin
fun isPrepareActive(): Boolean = !queuedIntent.isCompleted && (!ownsGate || prepareGate.isOwner(queuedIntent))
```

`DataSourceSelectorResults.kt:359`：

```kotlin
if (!isPrepareActive()) return@Consumer        // ← 静默 return：不 attachConsole，也不 abortPrepareGate
prepareInConsole(…)
```

于是：
- 控制台从未挂载 → 预览面板永远「未绑定数据源」；
- `prepareGate.prepareInFlight` 保持 `true`、`owner` 仍指向这个已完成的意图；
- 后续任何 `executeAfterPrepare=true` 的进入（执行 / 切换数据源 / 回滚执行）都走 `QUEUE_ONLY`；
- 排队意图 15s 后按 `PENDING_EXECUTE_TIMEOUT_MS` 结算，文案**复用** `execute.prepare.timeout`
  （`DataSourceSelector.kt:1817-1823`，注释自称「语义一致」）——用户看到的就是截图 1 底部那句；
- owner 自己的 60s 逃生于 13:30:05 触发 `abort`，但 `complete()` 已被 `BLOCKED` 消费过，超时消息
  也发不出来（`compareAndSet` 直接 return）→ **这一路全程零提示**，直到 13:31:04 用户再点才 PROCEED。

### 3.4 为什么「切换数据源」也没救

单飞门在 60s 内不放行，`forceChoose` 同样只排队 → 15s 超时 → 同一句红字。即使门被放开，
预览弹窗里也没有换源入口（见 §四）。

## 四、第二缺陷：文案指向一个不存在的入口

- 放行态走 `SqlPreviewPanelExecution.kt:731-738` → `resultPanel.showStatus(message)`；
- `SqlResultPanel.showStatus`（`:245-258`）第一件事就是 `clearErrorActions()` —— **显式把修复按钮隐藏**；
- 预览弹窗动作只有 4 枚（`SqlPreviewDialog.kt:91-108`：执行/复制/复制并关闭/关闭），
  面板工具栏 7 枚（压缩/格式化/关键字换行/竖向排列/执行计划/刷新/更多），
  「更多」9 项（回滚/导入参数/差异对比/分享/可移植导出/回放脚本/AI 上下文×2/DML 影响面，
  `SqlPreviewPanelResultView.kt:80-96`）——**没有任何一项是「切换数据源」**；
- 而文案 `execute.dialectGate.rerenderUnavailable`（bundle `:396`）写着「可点「切换数据源」换一个匹配的目标」。

用户说的「点切换数据源 没有看到在哪点」就是这个：**文案承诺的入口在放行态被主动隐藏了**。

需要强调：这两个入口**本来就是蓝色可点文字**，不是按钮。`errorFixButton` / `errorSettingsButton`
（`SqlResultPanel.kt:97-119`）都是 `isOpaque=false` + `isContentAreaFilled=false` + 无边框 + 11pt +
`foreground = JBColor.namedColor("Link.activeForeground", …)` 的透明按钮——渲染出来就是一行蓝色链接文字，
点击分别触发 `fixHandler` / `settingsHandler`。所以本次不是「新增一种控件」，而是**让它们在放行态别被清掉**
（`showStatus` 的 `clearErrorActions()` 是唯一元凶）。

更麻烦的是现有用例把这一行为钉成了期望：
`SqlPreviewPanelDialectGateUxTest.test闸门放行_手动冲突显示状态提示不进错误态` 断言
`fixActionForTest().isVisible == false`。修的时候必须**有意**改这条断言（它不是回归，是需求变更）。

## 五、修复方案

### P0-A｜闸门只在真正阻断时结算 BLOCKED（根因）

`DataSourceSelector.kt:221-281`：把 `onSubmission(BLOCKED)` 从「非 MATCH 一律报」改成「**确实不放行**才报」。

```kotlin
val allowed = when (verdict.status) { … }        // 先算
if (!allowed && !needsConfirmation) onSubmission?.invoke(ExecutionSubmission.BLOCKED)
if (!allowed && needsConfirmation)  onSubmission?.invoke(ExecutionSubmission.CANCELLED)
return allowed
```

要点：
- `MANUAL_CONFLICT` / `RERENDER_UNAVAILABLE` 放行 → **不得**结算意图；
- `TARGET_UNKNOWN_BLOCK` / `RENDER_UNKNOWN_BLOCK` / `AUTO_RERENDER_REQUIRED` 仍按原样结算（这几态 `allowed=false`，
  后续 `abortPrepareGate` 会释放门，行为不变）；
- 该函数有 3 个生产调用点（`DataSourceSelectorResults.kt:349`、`DataSourceSelectorConsole.kt:532`、
  `SqlPreviewPanelExecution.kt:1461`），改的是公共语义，**逐个入口核对**「谁在消费 BLOCKED」。

### P0-B｜consumer 的每条早退路径都必须结算门（结构性兜底）

`DataSourceSelectorResults.kt:341-366`：把 `if (!isPrepareActive()) return@Consumer` 改为
「早退前 `abortPrepareGate(prepareOwner, …, LOG)`」（`abort` 内部按 `matchesOwner` 幂等，重复调用无害）。

理由：A 修完仍会有人写新的早退分支；门是进程级单例状态，**悬挂一次就是 60s 全功能不可用**。
`prepareInConsole` 内两处 `if (!isPrepareActive()) return`（`DataSourceSelectorConsole.kt:1113`、`:1219`）同款处理。

### P0-C｜放行态保留两个蓝色可点入口（用户定案形态）

**目标形态**：状态行文案照旧（信息态配色），文案右侧跟两段**蓝色可点文字**——`切换数据源`、`方言设置`，
点下去分别执行换源与打开方言设置页。与错误态（`showErrorWithActions`）里的同款入口视觉一致。

实现（不新建控件）：

1. `SqlResultPanel` 新增 `showStatusWithActions(message, fixLabel, fixTooltip, fix, settingsLabel, settingsTooltip, settings)`：
   与 `showStatus` 完全同款（`state = EMPTY`、`currentError = null`、info 配色、`showContent()`），
   **唯一差别是不调 `clearErrorActions()`**，改为按入参挂 `errorFixButton` / `errorSettingsButton`（`SqlResultPanel.kt:292-316` 同款赋值段抽出来复用）。
2. `SqlPreviewPanelExecution.kt:731-738` 的 MANUAL_CONFLICT / RERENDER_UNAVAILABLE 分支由 `showStatus` 换成
   `showStatusWithActions`，两个动作：
   - `切换数据源` → **就地弹平台原生数据源列表**（用户 2026-11-02 定案），**只换源、不自动执行**；
   - `方言设置` → `openSqlDialectSettings(proj)`（`:796`，按 EP id `SQL Dialects` 打开，不实例化整棵设置树）。

3. 换源动作的接线（现有入口是断的，必须一并补）：
   - 不能复用 `executeSql(forceChoose = true)`：`selectAndExecute` / `SqlExecutor.execute` **没有透传
     `forceChoosePopup`**（`DataSourceSelector.kt:603` 只有 `prepareConsole` 有），runner 只剩一条时仍走
     `shouldBeChosenIfAlone` 直选执行（`DataSourceSelectorResults.kt:503`）——用户看不到列表，
     这正是 W6「点切换数据源却重试执行了」的原形；
   - 改为走 `DataSourceSelector.prepareConsole(project, sql, onConsole, onEditor, onError,
     dialectGateRequest, forceChoose = true, forceChoosePopup = true)`：`forceChoose` 跳过共享控制台复用与
     last-used 静默命中（`allowsSilentLastUsed(false, true) == false`），`forceChoosePopup` 保证**即便单源也弹列表**；
     `executeAfterPrepare = false` 天然做到「只换源不提交 SQL」；
   - 选完源后走既有 `sessionConsumer → prepareInConsole` 重建控制台，`onConsole` 重嵌本面板，
     状态行的方言提示随新目标刷新；用户再点「执行 SQL」。
   - 若实施时发现 `prepareConsole` 路线拿不到面板侧需要的东西（编辑器回调等），退路是给
     `selectAndExecute` / `SqlExecutor.execute` 补 `forceChoosePopup` 透传并在 `executeSql` 里加参数——
     但**不要**默认走「换源并自动执行」。
4. 顺带对齐颜色：`errorSettingsButton` 的 fallback 现在是 `JBColor.GRAY`（`:115`），`errorFixButton` 是 `JBColor.BLUE`（`:103`）。
   两段都是链接，fallback 统一为 `JBColor.BLUE`（正常主题下两者都取 `Link.activeForeground`，差异只在键缺失时暴露）。

不做的事：不改文案（`execute.dialectGate.rerenderUnavailable` 原文就写着这两个入口，补上控件后自洽）；
不动 `MANUAL_CONFLICT` 的文案措辞（它说「可切换数据源」，同样由这两个入口兜住）。

注意：`showStatus` 还有其它调用方（重渲染提示、普通状态行），**不要直接改它的语义**，新增函数另开。
仓内有「弹窗红线/子页基数」类 convention 守卫，`showErrorWithActions` 相关计数断言需同步登记。

### P1-D｜排队超时文案分型

`PENDING_EXECUTE_TIMEOUT_MESSAGE` 复用 `execute.prepare.timeout` 是错的：
用户没超时，是**上一条链卡住了**。新增键（双语）：

```
execute.prepare.queuedTimeout=上一次执行准备尚未结束（已等待 {0} 秒），本次点击未提交。请稍后重试；若持续出现请查看 idea.log 中的 [M36] 准备链日志。
```

### P1-E｜（顺带）`executeOnConsole` 的 BLOCKED 会吞掉一次性回执

`DataSourceSelectorConsole.kt:532` 的 `onSubmission` 在回滚弹窗路径是 `receipt`（`compareAndSet` 一次性，
`SqlPreviewPanelExecution.kt:408-410`）：放行态报 BLOCKED 会把这张回执用掉，后续真正的 `SUBMITTED` 被丢弃。
A 修完后此问题自动消失，但要补一条用例钉住。

## 六、验证计划

### 6.1 定向用例（新增）

| 用例 | 断言 | 反向验证 |
|---|---|---|
| `DialectGatePassKeepsPrepareIntentAliveTest` | `allowDialectGate(RERENDER_UNAVAILABLE, onSubmission=intent.onSubmission)` 返回 `true` **且** `intent.isCompleted == false` | 把实现改回旧版，用例必须变红 |
| `PrepareGateEarlyReturnReleasesGateTest` | owner 链在闸门后早退 → `prepareInFlight == false`，下一条链 `tryEnter` 得 `PROCEED` | 删掉 P0-B 的 abort，用例必须变红 |
| `SqlPreviewPanelDialectGateUxTest`（改既有） | 放行态状态行**必须**挂出「切换数据源」「方言设置」两段蓝色可点文字：`isVisible == true`、`foreground` 取到链接色、点击分别命中 `fixHandler` / `settingsHandler`（测试缝注入探针） | 现断言 `fixActionForTest().isVisible == false` 需改为 `true`；把 `clearErrorActions()` 放回去必须变红 |
| `QueuedTimeoutMessageTest` | 排队超时走新键，不含「重新选择数据源」 | — |

### 6.2 真机（runIde 沙盒）

1. demo 工程，预览方言设 **GaussDB**，数据源选 **PostgreSQL**（与报障同形）；
2. 点「执行 SQL」→ `idea.log` 必须出现 `[M36] attachConsole -> JdbcConsole{…}`；
3. 连点 3 次 → 不得出现 `prepare chain in flight: execute intent queued`，不得出现 15s 超时红字；
4. 状态行右侧必须看到两段蓝色可点文字：
   - 「切换数据源」→ **必须弹出原生源列表**（本例只有 1 条源时也要弹，不得直选），选 PG 源后控制台按新源重建、
     状态行的方言提示刷新，且**没有自动提交 SQL**；再点「执行 SQL」能跑通；
   - 「方言设置」→ 打开 `设置 > 语言与框架 > SQL 方言` 并选中该页；
5. 反向：把 `8619a3af` 的写法临时改回，上面 2/3 必现失败（作为本次修复的现场对照）。

### 6.3 门禁

定向用例 → 全量单路 `testShards` → 不涉 `build.gradle.kts`/`plugin.xml`，`verifyGate` 可延后到发版前。

## 七、待验证前提（实施前先证伪）

- **P-1**：只有 `MANUAL_CONFLICT` / `RERENDER_UNAVAILABLE` 会死链。`AUTO_RERENDER_REQUIRED` 虽也报 BLOCKED，
  但它 `allowed=false` 会走 `abortPrepareGate` 释放门，推断「门不悬挂、但用户仍看不到重渲染后的自动重提交」——
  真机各测一次再定。
- **P-2**：预览准备链（`PASS_THROUGH`）是否也被这条 BLOCKED 打死。按代码是同一个 `onSubmission`，
  但预览链的 `queuedIntent` 是否被别处复用需要读一遍 `prepareDefaultConsole` 才能确认。
- **P-3**：GaussDB 渲染 + PostgreSQL 源是否**本应判 MATCH**（PG 兼容族）。这是产品语义问题，需用户定案；
  即便定成 MATCH，本缺陷也只是触发面收窄，其它冲突（MySQL 预览 + PG 源）仍会死。
- **P-4**：13:29 那次 60s 内有没有别的入口能换源——已代码核实：预览弹窗没有；回滚弹窗有（`RollbackSqlDialog.kt:194`）。
- **P-5**：`prepareConsole(forceChoose, forceChoosePopup)` 在 262 上能否稳定拿到**完整**源列表。W12 的历史结论是
  「自建列表第二次起被平台 forced 关联短路成单源」，但那是 `T-ConsoleBind`（`ExecFileBinding.realignExecBind`
  反射清 pin）落地**之前**的观测。真机必须连点两次，验第二次列表仍是全量；若仍被短路，按 P0-C 第 3 条退路
  改为补 `forceChoosePopup` 透传（仍走就地弹列表，不退回工具窗）。

## 八、实施记录

2026-11-02 于 master（`71ace631`）实施完毕，未提交。

### 8.1 改动清单

| 项 | 文件 | 落点 |
|---|---|---|
| P0-A | `execute/DataSourceSelector.kt` | `allowDialectGate` 改为先算 `allowed`、再按 `allowed` 投回执：放行态只投详细提示（`blockMessage`），`BLOCKED` 仅限 `!allowed && !needsConfirmation`。顺序仍是「先报未提交、后交付红字」 |
| P0-B | `execute/DataSourceSelectorResults.kt` | 3 处早退结算：session consumer 起点、方言闸后、选源弹窗回调 |
| P0-B | `execute/DataSourceSelectorConsole.kt` | 4 处早退结算：`prepareAfterEditorResolved` 起点/中段、`prepareInConsole` 同步首行/异步 `invokeLater` |
| P0-C | `ui/SqlResultPanel.kt` | 新增 `showStatusWithActions`（与 `showStatus` 同款，只是清完动作后重新挂上）+ 抽出 `applyErrorActions` 供错误态复用；`errorSettingsButton` fallback 由 GRAY 改 BLUE |
| P0-C | `ui/SqlPreviewPanelExecution.kt` | 放行两态改走 `showStatusWithActions`（「切换数据源」「方言设置」两段蓝字）；新增 `switchDataSourceInPlace()`：`prepareConsole(forceChoose = true, forceChoosePopup = true)`，只换源不提交 |
| P1-D | `resources/messages/*.properties`（zh + en） | 新增 `execute.prepare.queuedTimeout`（带 `{0}` 秒数）、`execute.prepare.superseded`；`PENDING_EXECUTE_TIMEOUT_MESSAGE` 改用前者，不再复用 `execute.prepare.timeout` |
| 守卫同步 | `execute/PrepareSingleFlightGateTest.kt` | 三处计数随新调用点同步：`abortPrepareGate(prepareOwner,` 期望 5→12；异步早退断言由「静默 return」改为「必须结算门」；PREVIEW 嵌入点 1→2（新增换源链） |

P1-E（`executeOnConsole` 的 `BLOCKED` 吞掉一次性回执）无需改码——A 修完后放行态不再投 `BLOCKED`，回执不再被提前消费；
由新增用例 `pass through conflicts must not settle the execution intent` 在源头钉住。

### 8.2 读数

- 定向（10 类）：**108 tests / 0 failures / 0 errors / 0 skipped**
  `ExecutionDialectGateTest`、`PrepareSingleFlightGateTest`、`PrepareGatePendingTimeoutTest`、
  `ExplainFallbackGuidanceTest`、`SqlPreviewPanelDialectGateUxTest`、`SqlResultPanelUiTest`、
  `DialectGateExitPolicyTest`、`PrepareQueuedTimeoutMessageTest`、`BundleParityGuardTest`、
  `LogToSqlPanelExecuteTest`。
- 反向验证（P0-A 改回「非 MATCH 一律投 BLOCKED」）：**恰 1 红**，红的是
  `ExecutionDialectGateTest :: pass through conflicts must not settle the execution intent`；
  恢复修复后复跑 **108/0/0**。
- 首轮定向曾 3 红，全是 `PrepareSingleFlightGateTest` 的**调用点计数守卫**（期望值未随新增结算点更新），
  按「计数漂移」处置同步期望并写明理由，非绕过。
- 全量 `testShards`（5 片，28m25s）：**8517 tests / 1 failure / 0 errors / 14 skipped**
  （shard1 1680/0、shard2 1753/1、shard3 1692/0、shard4 1687/0、shard5 1705/0）。
  唯一失败＝`LogPipelinePerfTest.test搜索_子串_两万条在预算内`（83.47ms vs 预算 80ms），判为**负载型假失败**：
  ① 该用例自注「本机实测 20~45ms，随机器负载波动明显，设 80ms 留一倍余量」；
  ② 隔离复跑 2 次均绿（14/0/0）；
  ③ `testShard2` 单独复跑 **0 失败**（该任务本次跑了 1423 条，类集合与聚合跑的 1753 条不同批次，只取失败数作判据）；
  ④ 本次改动不触达日志面板搜索/排序/筛选链（只改方言闸门、准备链早退结算、结果行呈现、bundle 两行）。
- GUI 验收：用户明确「gui 验收交给我自己」，本轮未做 runIde 沙盒走查。

### 8.3 未覆盖 / 留给下一轮

- 真机判据（`attachConsole ->` 是否出现、连点三次不排队、换源列表第二次是否仍全量）未做，属 GUI 验收范畴。
- 待验证前提 P-1/P-2/P-3/P-5 仍待真机或产品定案。
