---
name: project-ds-list-add-source-20261111
description: T-DsListAddSource 数据源列表 unknown 死项收口 + 末尾「添加数据源…」——20261112 已实施（分支 t-ds-list-add-source 未合流），定向 31/0/0、反向 12/3→31/0；not run＝真机 GUI 与全量门禁
metadata:
  node_type: memory
  type: project
  originSessionId: sess_216d62d0-f936-4e25-8d90-a3355e3af177
---

# T-DsListAddSource：数据源列表 unknown 改「添加数据源…」（20261112 已实施，未合流）

用户 2026-11-11 报「预览面板两处数据源列表（原生控制台下拉、更多→切换数据源）最底部多一个 unknown，改成添加新的数据源、点了弹 IDEA 原生添加数据源弹框」。计划 `docs/开发计划/20261111-1643-…-开发计划.md`（master `097a14cd8`，§十二已回填实施记录）。

**根因（已证到字节码）**：两处列表各有装配（控制台下拉 `ChooseDataSourceTakeover.dataSourcesFor`＝`DataSourceStorage.getDataSources()` ∪ `getAllDataSourcesForExec`；Runner 弹窗 `DataSourceSelector.createRunnerPopupStep`），名字解析失败都被渲染成字面量 `"Unknown"`——`DbImplUtil.getAllDataSourcesForExec` 返回 `JBIterable<DbDataSource>`（PSI 层，javap 243），对它反射 `LocalDataSource.getName` 抛 IllegalArgumentException → runCatching → `"Unknown"`，按名去重后**末尾恰一条**；Runner 弹窗同源于 `getRunnerName` 回落。条目点是死项（`blocksPreferredRunnerMiss` 报「数据源 Unknown 不存在」）。

**实施（分支 `t-ds-list-add-source` @ master `34d3af5ec`，工作树 `.worktrees/t-ds-list-add-source`，主控单干未派子代理——用户令）**：
- `c728c8058` 代码：`DataSourceSettings.resolveLocalDataSource`（`DbDataSource.getDelegate()` 解包）+ `nameOrNull`（可空；**既有 getName/getRunnerName 的 "Unknown" 回落不动**——选源记忆/chosenRunnerName/方言闸依赖非空串）；`mergeLocalDataSources` 纯函数（投影→无名丢弃→uniqueId 去重）；`DataSourceSelector.buildRunnerItems` 过滤无名 runner、`RunnerItem` 加 `addNew` 标记（选中＝按用户取消终结准备链、不提交 SQL）；新增 `execute/NewDataSourceEntry`（平台新建工厂 `DatabaseViewActions.getCreateDataSourceAction` 反射封装，`Consumer` 回调核在册后 D-1 自动切新源）；bundle `execute.datasource.add` 双语。
- `e6e4d4942` CHANGELOG [Unreleased] + 架构文档 02；`eb33c99d3` 计划 §十二；`379e6bc19` 索引改「已实施（未合流）」。
- 决策点按计划建议落：D-1 自动切新源 / D-2 三处装配点统一 / D-3 平台不可用隐藏 / D-4 驱动子项自渲染二级列表＋降级直开管理器。

**新平台坑（javap 243 实证，进架构文档 02）**：`NewDataSourceActionGroup.update` 要求 DataContext 带 `DatabaseView.DATABASE_VIEW_KEY`（`isDataSourceActionsEnabled`），**整组直弹必置灰**——须取驱动子项自渲染（子项 `LocalDataSourceManager$2.actionPerformed` 只读捕获字段，无 DataContext 依赖）；另 `ActionGroup.children` 在 Kotlin 不解析（须 `getChildren(null)`，OverrideOnly 但仓内有 `delegateChildren` 先例）；测试桩 `val delegate` 与 `fun getDelegate()` 撞 JVM 签名（改 `inner`）。

**读数**：定向 31/0/0（DsListAddSourceTest 12 新 + ChooseDataSourceTakeover 3 / PreviewPreparePick 8 / PreferredMiss 6 / TakeoverUpdateThreadGuard 2；独立沙盒 MPSV-DsList，临时 sandboxContainer 已还原未入库，并行登记 t-dslist 已注销）；反向三探针（merge 无名丢弃 / buildRunnerItems 回落 / 摘添加项）→ 12 例恰 3 失败，逐字还原后 31/0。

**not run**：真机 GUI 走查（计划 §八 1~6，含 P-5 回调时机与取消不落空源、P-1 `[M36][DsList]` 取证日志读数、P-2 262/263 驱动弹层行为）、resolveLocalDataSource delegate 成功路真反射（默认 test 沙盒无 Database jar；退化路＋注入桩语义路已验）、全量门禁（收口按纪律补 `mode=gate`）。

**合流后回归与修复（2026-11-12）**：推送测试连红四轮（`43127711688`/`43129449046`/`43134264631`/`43136753539`），失败点唯一：`LogToSqlPanelExecuteTest#testPlatformPopupSelectionDisposeIsNotUserCancellation`（`expected [CallbackRunner@…] but was []`）。根因＝W4 的「取名失败即不渲染」只认 `getDisplayName`，而夹具 `CallbackRunner` 与数据源对象形态只暴露 `getName()` ⇒ 唯一条目被滤掉、弹层变空、`selectedIndex=0` 落空、`onChosen`/`canceled` 都不触发。修法＝`optionalRunnerName` 改候选链 `getDisplayName`→`getName`（都空才判无名；「Unknown 占位」与死项收口语义不变），提交 `d289ac1a6`，用例 +2（只暴露 getName 必须保留 / 两段都空才判无名）。定向 49/0/0，反向把候选收窄回单方法 ⇒ 恰 2 红。教训入 `docs/LESSONS.md` **L41**（`a1abf484c`）。**CI 已复核绿**：修复轮 `43138414839`（sha `d289ac1a6`）success，文档轮 `43139674996`（sha `a1abf484c`）success；`origin/master` = `github/master` = `a1abf484c`，本地无未推差集。

**验收与收官（2026-11-12，用户）**：真机 GUI 走查经用户确认「验收没问题」，并明确「测试不用补」⇒ 全量门禁与 delegate 成功路真反射按用户决定不再补跑（有据省略，非遗漏）。计划 §十二 增「验收结论」条（`63b1d4def`），索引状态改「已实施并验收（已合流）」。任务收官：代码＋测试 `c728c8058`、文档 `e6e4d4942`/`eb33c99d3`/`379e6bc19`，merge `eb0dc7030`（合并态定向＋守卫 58/0/0/0），双仓已推、`origin/master` = `github/master` = `eb0dc7030`，工作树与分支已清理。相关：[[reference-intellij-native-new-datasource-entry]]、[[feedback-commit-other-lane-dirty-files-judge-by-diff]]。
