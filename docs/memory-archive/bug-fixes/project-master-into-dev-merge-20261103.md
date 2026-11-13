---
name: project-master-into-dev-merge-20261103
description: T-MasterToDevMerge（2026-11-03 已收口待推送）——master 57 笔并入 dev 的反向合流完成：合并提交 1bf55efa、全量两轮 8941/0/0/16、续拆两件回 2000 线内；剩仅 dev 欠推 1 笔快进
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d285b961-3ef5-4316-bcdb-e38a66214c8a
---

用户 2026-11-03 令：dev 合并 master（master 侧是 v2026.11.2/11.3 两个发行批 + 预览执行链十连修，dev 侧是 D202 巨文件拆分 + 真机/门禁/文档），解冲突、跑全量测试修问题，**构建校验不跑**，先出计划册再动代码。

## 分叉实况（实测）

| 项 | 读数 |
|---|---|
| merge-base | `802fe329`（2026-10-31 21:08） |
| master 独有 | 57 笔，tip `738f9fa2`（11-03 12:49） |
| dev 独有 | 246 笔，tip `43891d81`（11-03 10:58） |
| 试合并冲突 | 13 文件 / 22 块 |

**关键形态**：master 的修复打在 D202 拆分**之前**的大文件上，dev 已把函数搬到新文件（`DataSourceSelector.kt` −339 行 / `DataSourceSelectorResults.kt` −391 / `SqlPreviewService.kt` −235），所以每个代码冲突都是 [[pitfall-merge-doc-union-resolution]] 第 23 条那种 move-vs-modify。另注意两侧有**同主题不同 hash 的提交**（`2aa4f080` vs `43891d81` 都是「二维码卡片两列显式等宽」），不能凭 commit subject 判重复。

## 逐文件裁决（已解并 `git add`）

- `DataSourceSelector.kt`：删 master 侧 341 行整块（控制台文本工具已在 `DataSourceSelectorConsoleText.kt`）；`isOutputContent` 保 dev 的 `internal`；补 master 的 `currentResultContentNames()`
- `DataSourceSelectorConsoleText.kt`（非冲突文件，手工补搬）：`clearConsoleHistory` 改**三参** `(project, console, LOG)` + `WriteCommandAction` 包 `doc.setText`；调用点 `DataSourceSelectorConsole.kt:212` / `Results.kt:774` 已是三参，且新增的 `PreviewResultIsolationTest` 做源码扫描断言该字符串，签名必须逐字一致
- `SqlPreviewService.kt`：删 master 侧 245 行整块（语句后处理已在 `SqlPreviewServiceStatementOps.kt`）；`activePreviewPanel` 已自动合并
- `SqlPreviewServiceStatementOps.kt`（手工补搬）：`paginationCountSql` 补 `if (chain.pageLimit == null) return null`（T-Exec262ReadLock B）
- `DataSourceSelectorResults.kt`：S2-12 绑定回落与 `emptyRunnersReason` 三态**并存**——`PLATFORM_CALL_FAILED` 单独报 `execute.datasource.platformCallFailed`（异常首行），其余仍走 `EmptyRunnersHint.message(project, registered, renderedDialectName, vf)`（保住 `ExecDialectRoutingTest` 的源码扫描断言，且 `EmptyRunnersHint` 的 key 映射本就覆盖 empty/dialectMismatch，不重复报）
- `plugin.xml` / `bundle`（zh+en）/ `PluginXmlStructureGuardTest`：⑤ 取 dev 的动态数字口径（方言数取 `DialectRegistry.dialects`、工具数取 `McpToolGate.TOOL_NAMES`），补 master 的 `platformCallFailed` / `prepare.superseded` / `prepare.queuedTimeout` 三键
- 文档六件（CHANGELOG / 变更记录 / MEMORY / 架构01 / 架构03 / 索引·开发计划）：并集去重，**计数按合并后实测重算**（详见第 24 条）

## 顺带确认的平台事实

- 自动合并已把 master 的 T-DialectGatePassDeadChain（`blockMessage` 延迟投递）、T-Exec262ReadLock A（`RunnerEnumeration` + `enumerateDataSourceRunners` + `WriteIntentReadAction.compute`）、`queuedTimeout` 文案全部带进来了；`getAttachDataSourceRunners` 仍保留为薄包装（返回 `.runners`），dev 侧 `ExecBindFallback` / `EmptyRunnersHint` / `ExecDialectRouting` 的调用点不受影响
- `EmptyRunnersReason` 现在**有两份**：顶层（master 加在 `DataSourceSelectorResults.kt`）+ `ExecDialectRouting` 对象内嵌套（dev 侧），不冲突但同义重复，后续可收口
- 已删的 master 整块里没有任何 dev `DataSourceSelectorConsoleText.kt` 缺的函数（逐名 grep 20 个符号全在）

## 收口结果（2026-11-03 23:5x 主控复核，全部完成）

- **合并提交 `1bf55efa` 已落 dev**（dev 工作树干净），计划册 `20261103-1330-master并入dev-冲突裁决与全量回归-开发计划.md` 落在 dev 工作树。
- 编译 BUILD SUCCESSFUL（5m38s，in-process）；定向 15 类 **210/0/0/0**。
- 全量第 1 轮抓到 `GiantFileBudgetTest` 红（DataSourceSelector 2068 / Results 2110 超 2000 预算——合流把 master 增量合进 dev 刚拆到位的文件）→ 按「继续拆不是提额」**续拆**出 `DataSourceSelectorRunners.kt` 与 `DataSourceSelectorBottomCollapse.kt` 两件，回线内（1962 / 1977）。
- **全量第 2 轮（权威）8941 / 0 / 0 / 16**（14:37–14:57，5 片单路；与第 1 轮总数一致，分片切分随权重表变化）。
- **唯一剩余动作**：dev 对 origin/github 各欠推 1 笔（快进 `1bf55efa`，携带 57 笔 master 内容），走 push-all.ps1 后 `git ls-remote` 直查（tracking ref 口径不随 URL 推送刷新）。**dev 欠推的 rev-list 计数会显示 58——那是 57 笔 master 内容 + 1 合并提交，实际快进只有 1 笔**，勿被计数吓住。
- 后续拓扑已重开：insight→dev 的 merge-tree 预演翻转为 4 文件冲突（25 轮册「零冲突」前提失效），见 [[project-panorama-round26]]。

关联：[[project-dev-worktree-checkout-location]]（dev 在 `-dev` 独立工作树）、[[pitfall-merge-doc-union-resolution]]（合并 playbook 第 23/24 条即本轮沉淀）、[[project-preview-console-ownership]]、[[project-exec262-readlock-and-count-gate]]。

## 第二轮（S1-7，2026-11-06 已执行）：master 128 笔并入 dev，22 文件冲突

用户授权「master→dev 允许」+ 全流程令（dev 合 master→testFast→三组并发实施→主控合并，**只跑定向不跑全量**）。

- 基线：merge-base `62aca9c9`，master tip `d13aade6`（独有 128），dev tip `84dd8b59`（独有 298）。合并提交 **`fafc2042`** + 收尾 **`f80d7aab`**（dev 现值）。
- 裁决形态与首轮同构（move-vs-modify，dev 拆分结构为基底），但**master 语义增量必须全数搬进 dev 新文件**：T8 `hasConsoleCompletionEvidence` + `COMPLETION_EVIDENCE_LINES` 落 `DataSourceSelectorConsoleText.kt`（扩展函数形态）；UI-049 结构化提示通道（`onDialectMismatchHint` 参数链）与 dev 的 `EmptyRunnersHint` 单一文案出口**并存**（else 分支先试结构化、失败兜底 EmptyRunnersHint——保住 `ExecDialectRoutingTest` 源码扫描锚点）；UI-003 走 dev 的 `PreviewPreparePick.decide` 三态（master 只有 shouldPopup 两态）。master 侧重复块按 dev 版删除：`explainSql`/`submitExplainDirectConsole`（→ SqlPreviewPanelExecutionExplain）、`createTempPsiFile`/`createTempPsiFileAsync`/`revalidateOnEdt`（→ ExecTempFileFactory）。
- **守卫前提会过期**：`ExecBindFallbackTest.fallbackWiringShape` 断言 pickTarget 唯一，但 UI-003 静默选源是**第二处合法接线**（同一口径复用于预览准备）——守卫改「恰两处、第三处即红」。巨文件预算提额留痕：Selector 2010→2025（实测 2019）、Results 2030→**2090**（实测 2087，master UI-049+T8 调用点并入），拆分债挂闲时清单 G-1。
- **判「失败是否合并引入」的对照法**：临时 worktree 检出合并前 HEAD 单跑同批用例——本轮 6 红全部在合并前复现（SqlLogPanelTypeFilterTest 3=平台 `JBUIScale` Must be precomputed 断言；O147HistoryUpgradeTest 3=C 盘 0.6GB 致 IOException+T9 子 JVM 登记数不符），非合并引入，留环境修复。
- master 在合并后又前进（`980a2d14` D201 起 4 笔 docs），**下次 master→dev 仍需再合**；dev 此前 github 欠推 39 笔仍未推。

## 第三轮（2026-11-08 待启动）

master 已再前进（dev 落后 326 笔），本轮并入「三车道闲时任务」与 insight / k2j 一起推，提示词已交付、尚未创建任务。调度与约束见 [[project-three-lane-branch-merge-20261108]]。
