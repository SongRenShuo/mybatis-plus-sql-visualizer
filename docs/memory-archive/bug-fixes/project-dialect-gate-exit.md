---
name: project-dialect-gate-exit
description: T-DialectGateExit / UI-040（20261020 用户拍板 P0+P1 并实施）——回滚弹窗被方言闸门挡住时只给红字没出口：P0=改用面板闸请求（canLosslesslyRerender 不再写死 false）+阻断态两个按钮（按目标方言重渲染并重试 / 强制切换数据源）+ forceChoose 跳过两处静默选择；P1=绑定源方言升级后按目标方言重渲染
metadata:
  node_type: memory
  type: project
  originSessionId: session_85f51c5de37082b9d79ffa3f58bb93e2
---

# T-DialectGateExit：方言闸门阻断态出口与绑定后重渲染（UI-040）

2026-10-20 用户在 dev 包验收 UI-038/UI-039 修复时，点回滚弹窗的「执行快照查询」报：

> 该语句无法自动生成回滚。已阻止执行：当前 SQL 按 PostgreSQL 渲染，但所选数据源的实际方言是 MySQL。当前模型无法无损重渲染，或显示文本已被编辑；请重新生成 SQL 或切换目标后再执行。

同屏 meta 行是 `方言: PostgreSQL (模块已学数据源)`、`数据源（上次使用）：mpj_demo@localhost`（MySQL）；预览 SQL 本身正确 ⇒ **与占位符无关，纯方言闸门**。用户追问「这种失败原因是不是让用户可以选择数据源会更好些」，随后拍板「P0 和 P1 生成开发计划文件 实施」。

计划：dev 树 `docs/开发计划/20261020-1328-T-DialectGateExit-方言闸门阻断态出口与绑定后重渲染-开发计划.md`。

## 我对「只加选数据源」这个问法的答复（结论仍成立）

- 闸门姿态是对的（跨语法族 SQL 送目标库必然出错），缺的是**出口**。
- 真正矛盾是**渲染方言**：`DialectDecisionEngine.decide` 优先级 `手动 > 捕获URL > IDEA scope > IDEA 方言 > 绑定数据源 > 模块学习`，绑定源本应压过「模块已学」；落到「模块已学」说明绑定证据没进渲染决策（或进得太晚）。
- 所以只加选择器不够：项目里若只有 MySQL 源，选了仍被同一条闸挡。对症的是**按目标方言重渲染**。

## 根因（代码面）

1. `RollbackSqlDialog.runSnapshotQuery` 自己拼闸请求，`canLosslesslyRerender` **写死 false** ⇒ `ExecutionDialectGate.evaluate` 必然落到 `RERENDER_UNAVAILABLE`，而面板侧 `SqlPreviewPanel.executionDialectGateRequest(...)` 早就按模型能力算该位。
2. 面板已有无损重渲染 `SqlPreviewPanel.rerenderForExecutionTarget(name)`（面板自己的 `onExecutionDialectGate` 在 `AUTO_RERENDER_REQUIRED` 时会自动调它），**回滚弹窗没复用**。
3. 「切换数据源」不可达：`promptDataSourceThenRetrySnapshot` 只在 `target == null` 时被调；且 `DataSourceSelector.prepareConsole` 的选择策略是「唯一源/last-used 命中 → 静默」，**没有强制弹选择器的口子**。
4. P1 三者不一致：`refreshDialectStatusRow` 绑定后只 `applyDialectStatusSegment()` **重组状态行**、不重渲染 SQL ⇒ 「状态行说 A、文本是 B、目标 C」可以并存。

## 实施形态

**P0-1**：`runSnapshotQuery` 改用 `p.executionDialectGateRequest(Intent.QUERY)`。

**P0-2**：`PreviewSideQuery.Result.Blocked` 增 `verdict`（护栏阻断为 null）；对话框新增默认隐藏的「出口行」：
- `[按目标方言重新渲染并重试]`：`rerenderForExecutionTarget(target)` 成功后**重试一次**（`allowRerenderRetry=false` 防循环）；`MANUAL_CONFLICT` 时**不显示**（插件不覆盖手动选择）；`RERENDER_UNAVAILABLE` 仍显示，失败就地报错。
- `[切换数据源]`：走 `promptDataSourceThenRetrySnapshot(forceChoose = true)`。
- 非方言阻断（渲染未知/目标未知/只读护栏）不露出。

**P0-3**：`prepareConsole(..., forceChoose)` → `chooseAndPrepare(forceChoose)`；两处静默路径各抽纯函数短路：`allowsSharedConsoleReuse(forceChoose) = !forceChoose`、`allowsSilentLastUsed(executeAfterPrepare, forceChoose) = !executeAfterPrepare && !forceChoose`。

**P1**：`refreshDialectStatusRow` 的 EDT 段在 `applyDialectStatusSegment()` 后调 `rerenderIfBoundDialectDiffers(chosen)`；判据抽纯函数 `shouldRerenderForBoundDialect(source, target, rendered, textUntouched)`——只认 `Source.BOUND_DATASOURCE`、方言不同、**用户没改过编辑器文本**（`lastRenderedSql == 当前文本`）。

资源：`rollback.snapshot.rerender` / `.rerender.tooltip` / `.rerender.failed` / `.switchSource` / `.switchSource.tooltip`（中英各 5 key）。

## 实施期发现的两处计划外补充（值得记）

1. **重试前必须重算快照 SELECT**：`snapshotSelectSql` 是 `RollbackSqlGenerator` 从语句 AST 拼的，标识符引用跟随**输入文本**的方言；重渲染后沿用旧文本等于把旧方言引用样式提交给新目标。新增 `refreshedSnapshotSql()` 按新预览文本重算（主键列沿用对话框输入），重算失败回退原文本不阻断。
2. **面板无 `current` 时的兜底**：旧实现 `renderedDialect = current?.effectiveDialect ?: MySqlDialect`；换成面板闸请求后该位会变 null ⇒ 被闸门判成 `RERENDER_UNAVAILABLE`，既有 headless 用例与异常态会误判。保留 MySQL 兜底。

## 测试

- `RollbackSqlDialogTest` 4 例：方言冲突露出口行 + 点重渲染后按目标方言重渲染并重试成功（并断言阻断时不提交查询）；`MANUAL_CONFLICT` 不给重渲染按钮；渲染未知不露出口行；「切换数据源」真触发选择器。
- `DialectGateExitPolicyTest` 3 例：两个 forceChoose 判据 + `shouldRerenderForBoundDialect` 五态（含「用户改过文本不重渲染」「非绑定源不重渲染」）。
- `DialectStatusRefreshWiringGuardTest` 新增 2 例源码守卫：EDT 段调 `rerenderIfBoundDialectDiffers(chosen)`；两处静默判据都接了 forceChoose——**池线程 + EDT 链在 headless 下难端到端驱动，按仓内惯例用源码守卫钉接线**（与 T-DialectStatusLiveRefresh 同一手法）。
- **守卫写法教训（反向验证当轮抓出）**：P1 接线守卫首版用 `t.contains("rerenderIfBoundDialectDiffers(chosen)")`，把接线**注释掉**照样绿（子串命中了注释行）。改为「未注释整行匹配」（`lines().any { it.trim() == … }` / 跳过 `//` 开头行），重跑探针才恰 1 红。→ 源码守卫一律按未注释行匹配，别用文件级 contains。

## 读数（20261020，会话内实测）

- 定向 **68/0/0**：`RollbackSqlDialogTest` 32（含新增 4）、`DialectGateExitPolicyTest` 3、`DialectStatusRefreshWiringGuardTest` 6、`PreviewSideQueryTest` 10、`ExecutionDialectGateTest` 17。
- 反向：三探针（不登记 verdict / 摘 P1 接线 / 摘 forceChoose 判据）→ **4 红**；修正守卫后探针 2 单独复验 **恰 1 红**。
- 4 条既有 `RollbackSqlDialogTest` 用例曾因换成面板闸请求而红（面板无 `current` 时机 → 渲染方言未知），补 MySQL 兜底后 32/0/0 恢复。
- 定向回归（execute / ui / converter 三包）**3083/3/0**——3 条即下述已知环境用例，本改动面零新增失败。
- **全量门禁 7973/3/8 未变**，三条红仍是 `InjectionGateTest`×2 + `UnknownParamGateTest`×1。
- 提交状态：会话结束时**尚未提交**，引用前先 `git log` / 实跑。用户当轮定的流程＝「等现在的门禁跑完 提交一次代码再继续」，且提交只 add 自己文件（工作树里另有并行会话的 `docs/索引/开发计划.md` 与 `T-DegradedPreviewUx` 计划未提交，勿带走）。

## 那三条「环境失败」的真机制与修法（2026-10-20 用户追问「能修复掉吗」时查清）

三条红都是同一条断言：`assertProceeded` 断言点完「执行计划」后 `resultPanel` 状态 ≠ `EMPTY`，实际停在 `EMPTY`。

链路：`explainStructuredRender` 默认 false → `ExplainPanelChannel.decide` 返回 `DirectSilent` → `submitExplainDirect`。该函数先取目标（`explainTargetForTest?.invoke() ?: boundConsoleDataSource()`），headless 测试两者都是 null → 命中 **UI-035 新增分支：弹「无数据源」错误对话框后直接 `return`，不再 `resultPanel.showLoading()`** → 状态停在 EMPTY。

也就是说：旧行为是「无源也进执行链、最终落 ERROR」，UI-035 改成「无源就提示去选源、不空跑必然失败的执行」。**产品行为是对的，用例期望停在 UI-035 之前**（这也解释了为何在干净 dev HEAD 上基线照红）。

**修法（测试侧，不动产品代码）——注意「注入目标」这条第一版试过、不成立**：按 `ExplainSideQueryPanelTest` 注入 `explainTargetForTest`（带 `getUrl()` 的 MySQL 目标）后**仍然红**；探针实测读数 `state=EMPTY sinkHit=true dialogs=0 dialect=null text=35`——放行确实走到了 `showExplainErrorDialog(openDatabase = true)`，但引导来自「无绑定数据源」判定、与 `explainTargetForTest` 无关；而把 `PreviewSideQuery.runnerForTest` 改成同步回调又会让 `showIdle() → showEmpty()` 立刻把状态置回 EMPTY，断言更没意义。

**最终修法＝把断言改成 UI-035 之后的真实契约**：用 `explainDialogSinkForTest` 截获 `showExplainErrorDialog`，遍历 `dialog.contentForTest()` 的 `JBLabel` 回读文案，断言含 `MpSqlVisualizerBundle.message("panel.explain.noDataSource")`；`${}` 门原有的「弹几次 / NO 中止时不该有引导（`assertNull(dialogText())`）/ YES 放行」断言全部保留。三个用例（`InjectionGateTest.testEXPLAIN含raw段_同门控`、`testEXPLAIN无raw段_不弹门`、`UnknownParamGateTest.testEXPLAIN含UNKNOWN参数_同门控`）修后 `InjectionGateTest` **18/0/0**、`UnknownParamGateTest` **6/0/0**。

**判据**：判「这是环境失败还是真回归」不能停在 `git stash` 基线复现——基线同样红只说明「不是我改坏的」，还要**继续查到产品链上到底是哪个行为变了**；本例一旦查清就是可修的，不是只能挂账的「环境问题」。

## 流程偏好（用户当轮两令）

- 「不要用 sleep，应该等待后台通知，不要阻塞主控」——长命令一律后台 + 等完成通知，禁 `sleep N; grep` 轮询。
- 「你可以先写文档 最后回填下门禁结果 节省时间」——读数未到前先把计划文档 §8（改动清单/读数占位）与台账行写完，门禁数字最后回填。

相关：[[project-dialect-status-live-refresh]]（状态行刷新链的前一轮，P1 接在它的 EDT 段上）、[[project-dialect-auto-inference-gap]]（决策优先级全貌）、[[project-preview-side-query]]（`PreviewSideQuery` 正是本轮闸门所在通道）、[[project-update-setsql-concat-gap]]（同一轮验收里先修的两个缺陷）、[[project-dev-worktree-checkout-location]]。
