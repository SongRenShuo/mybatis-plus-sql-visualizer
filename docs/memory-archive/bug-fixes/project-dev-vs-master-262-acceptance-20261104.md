---
name: dev-vs-master-262-acceptance-20261104
description: 2026-11-04 dev vs master 262 沙盒增量验收——五缺陷 UI-001~005 全部修复合 dev；真机复验又暴露三类既有线程缺陷（EDT 读 psi.File / EDT findDirectory / Write-unsafe）；账本在 docs/验收文档（勿用已废止的 docs/验收报告）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

dev（`1bf55efa`→约 290 笔）相对 master 生产差异 116 文件的 262 沙盒增量验收。权威账本＝仓内 `docs/验收文档/20261104-0410-dev-vs-master-262沙盒增量功能验收-验收文档.md`（19 域矩阵 + 缺陷表 + 环境债 + 真机人工步骤）。

## 19 域验收（GUI 闭环）

新 EP 全数在 262 生效（statusBarWidget 点击开日志窗 + 捕捉计数、6 条 inspection 含 `${orderBy}` 真机命中、2 设置子页、4 启动活动）；Banned API 全链路（规则→编辑器命中→Tools 扫描 245 文件/命中 1/明细到行）；捕捉链（curl 触发→方言/耗时/源码定位全在列）；方言红条 + `${}` 执行守卫 + 错误回流 + Gutter 分组 N-M 摘要 + 外部模板默认关，全部真机证据在案。

## 五缺陷全部修复并合 dev

| 缺陷 | 修复 | 真机复验 |
|---|---|---|
| UI-001 扫描动作 EDT VFS refresh | `dc43c477`+`5b3e0ab0` | `ScanBannedApiUsages` 特征 **0 次** |
| UI-002 BGT 无读锁读 PSI | `4f52078a`→合 `63156c92`（三站点全入 ReadAction、兜底 true→false、守卫规则六） | `Read access is allowed…` **0 次** |
| UI-003 预览弹选择器 | `0a2cd8b3`→合 `6f982d55`（`PreviewPreparePick` 纯函数） | **正证**：`[UI-003] preview prepare: no unique target among 7 runners —` 后无弹窗，点执行才 `show popup` |
| UI-004 方言状态行陈旧 | `f562a9ce`→合 `6f982d55`（`DialectGateRenderDialect`） | 该轮未走到红条路径，无证据也无反证 |
| UI-005 体检永久「体检中」 | `1ef83728`（模态态 + 有界内核） | **正证**：361ms 出结果、三段式生效、日志诊断扫 256KB/12ms |

详见各自条目：[[pitfall-bgt-psi-isvalid-needs-readaction]]、[[pitfall-preview-open-pops-datasource-picker]]、[[pitfall-dialect-status-row-stale-after-dialect-change]]、[[pitfall-unbounded-collect-leaves-ui-in-progress]]、[[project-env-check-auto-diagnosis-proposal]]。

## 真机复验又抓出三类既有线程缺陷（master 也有，**已修并真机归零**）

用户跑 `12c38` 包做「预览 + 执行」，一轮产生 **22 条插件相关 SEVERE**（全集中在 12:40~12:41）：

| 类 | 条数 | 落点 |
|---|---|---|
| **A** `PreCachedDataContext 'psi.File' is requested on EDT` | 1 + 8 条同栈 SlowOperations | `ChooseDataSourceTakeoverGroup.update`（平台明确指名违反 `ActionUpdateThread`，栈穿过 `ChooseDataSourceAction.getClientPsiFile`） |
| **B** EDT 做 `FileManagerImpl.findDirectory` → `WorkspaceFileIndexDataImpl.ensureIsUpToDate` | 9 | 执行链 `continuePrepareAfterTempFile` / `prepareInConsole` / `prepareAfterEditorResolved` |
| **C** `Write-unsafe context!`（**最危险，文档写可能被拒**） | 3 | `SqlExecutor.execute` → `reuseSharedConsole` → `executeOnConsole` |

修复交 `wt-threadfix` / `fix-edt-thread-violations`，三类各一笔提交，合 dev `d0afa46a`：

| 编号 | 修复 | 机制要点 |
|---|---|---|
| **UI-006**（A） | `f0ae0598` | `override fun getActionUpdateThread() = BGT`（覆写 `update()` 会让平台判「非默认更新」落 EDT） |
| **UI-007**（B） | `ce499a5e` | `warmConsolePsiThen` 池线程 `ReadAction{PsiManager.findFile}` 预热 → `invokeLater(nonModal)` 回 EDT；异步化后异常不再冒泡到外层 `runCatching`，必须抽 `handlePrepareFailure` 否则单飞门悬挂 60s |
| **UI-008**（C） | `f8608db5` | `any()`/省略模态 → `nonModal()`（4 处） |

**注意 UI-002 补的读锁 ≠ 移出 EDT**——读锁只解决权限，慢操作本身仍在 EDT 上。

**真机归零验证（2026-11-04 新包 13:58 / 启动行 40087）**：`psi.File on EDT` **0**、`Slow operations` **0**、
`Write-unsafe` **0**；本轮 SEVERE 仅 1 条且是平台自身 `PropertiesComponent`。**防空绿**：同轮 155 条
`[M36]` 行、走过 `prepareDefaultConsole → embedConsole → silent reuse last runner → executeSql: len=250, prepared=true`，
证明用户真做了预览+执行。唯一 WARN 是既有的 `EditorCommentShortcuts` 改全局 action 快捷键——根因（`replaceAction` 内部副作用，非我们调 API）与四条消除路径评价见 [[pitfall-replaceaction-shortcut-warn]]，不影响功能。
合流后定向 11 类 74 例全绿。

## master 合流与回 port（2026-11-04）

- **master → dev**：`633450b3`，带入 `64371dd8 fix(agent): Oracle 增强捕获 PS 事件全丢`（包装器登记错位 + 嵌套门两处）。3 处冲突全是文档（CHANGELOG 取并集合成单个 `### Fixed`、MEMORY 取 master 全版、索引取 dev 侧为基补 master 读数）；agent 子项目 `:test` **46 类 247 例全绿**。
- **回 port 范围（只有 5 项需要）**：UI-001/002/005 的落点（`ScanBannedApiUsagesAction` / `ExecTempFileFactory` / `EnvironmentCheckCollector`）**master 根本没有**，不用搬；需搬＝UI-003、UI-004、UI-006、UI-007、UI-008。计划书 `docs/开发计划/20261104-1313-master回port线程与预览方言修复-开发计划.md` §八 已回填 5 项 dev 提交映射。
- **关键前提**：UI-003 的 B 方案依赖 `ExecBindFallback.pickTarget`，**master 无该文件**（S2-12 是 dev 新增）→ master 上只能降级实施 A（预览不弹不建链）。
- 回 port 代理在 `wt-backport` / `backport-thread-dialect-fixes`（主仓建 worktree），逐项重放、禁 `merge dev`；合入 master 前须用户批准。
- **合入前另有一个阻塞项**：master 主仓工作树里有几份**未跟踪文档**（第二十七轮全景、竞品第三十六轮、优劣势深查草稿），是别的会话产物——须用户决定提交还是清掉，否则合并提交会顺带一堆无关文件。
- **回 port 收尾三道关**：①代码重放是否只做了 A 方案（UI-003 的 B 依赖 master 缺失的 `ExecBindFallback`）②master 侧定向测试必须有逐类读数（用户关沙盒后 243 JBR 释放，才跑得动插件侧 test）③五项各自回归风险说明，尤其 C 的 `nonModal` 会把写入延后到模态关闭。

### 回 port 已收官（2026-11-04，用户批准后合入 master `12d15f37`）

master 侧 5 笔：`85a31059`(UI-003 只做 A) / `c8528bf9`(UI-004) / `8d82d375`(A) / `1c174173`(B) / `42209e7d`(C)，19 文件 +1614/−63，`master..HEAD` 恰 5 笔零 merge。主仓主工作树未污染（未跟踪 docs 原样保留）。

**三项前提核查全部同构**，最硬的一条：master 的 `prepareAfterEditorResolved` 与 dev **修复前逐字一致**，落位后文件 blob `74436448` 与 dev 修复后**逐字节相同** → B 项是干净移植而非重新设计。UI-003 的 `ExecBindFallback` 只出现在**注释**里（说明「master 侧只实施 A」），grep 命中 2 处但无真实依赖。

**主控复跑 12 类 89 例 0 失败**（`gradle_exit=0`）——代理交付时 `build/test-results` 只剩最后一次单类的 9 例（**多次跑互相覆盖**），读数不可复核，故主控按同一组重跑。**教训：子代理分多轮跑测试时，XML 只留最后一轮，收口必须自己重跑一遍才算数。**

**用户定下的取舍**：①「多源无记忆预览控制台留空」可接受，但要补一行「点执行 SQL 选择数据源」提示——**该项延后到本版发行之后**（改动会变更已验证的发行包，需重跑测试与重打包）；②C 项模态交互不必刻意构造，用日志观察（`grep "dialect pre-mapped to"` 有 + 超时/跳过为 0）即可，判据见 [[pitfall-edt-psi-warm-and-write-safe-modality]]。

**当前阶段**：master 已进发行准备（今天的内测版），发行内容＝`64371dd8`(Oracle) + 上述 5 笔。见 [[project-release-workflow]]。

### 回 port 后用户真机又抓出 UI-009：回 port 漏了一整笔（2026-11-04）

用户装 master 包（jar 14:50）打开 XML 预览，`idea.log` 本轮 **36 条** `Slow operations`，栈固定：

```
SqlPreviewDialog.<init> → showResult → prepareDefaultConsole → SqlExecutor.prepareConsole
  → chooseAndPrepare:292 → createTempPsiFile:1529 → ReadAction.compute { PsiManager.findFile }
    → FileManagerImpl.findDirectory → ensureIsUpToDate   ← 慢操作，在 EDT
```

**根因是漏项不是修错**：dev 侧 `ba314f42`（2026-11-01 T-ExecSlowOps「执行临时文件构建移出 EDT」）从未进回 port 清单。当时把真机日志归成 A/B/C 三类，这条栈**那一轮没出现**——因为那次方言 PostgreSQL、`runners=7`、缓存命中率高，冷路径没被反复走到。用户这次 `runners=0` 让链路重试多次才打出来。

**教训（可复用）**：按「某一轮真机日志」归纳缺陷清单会**系统性漏掉该轮没触发的路径**——清单要以「代码里所有同类站点」为口径复核，不能只看日志出现过什么。

修法 `c62f23b8`（master，+216/−11）：照 dev 形态但**不整搬 `ExecTempFileFactory.kt`**（master 无该文件），在 `DataSourceSelectorResults.kt` 内新增 `createTempPsiFileAsync` + `revalidateOnEdt`，把 `chooseAndPrepare` 292 行之后抽成 `continuePrepareAfterTempFile`。定向 20 类 163 例绿，反向（改回同步直调）新守卫恰 1 红。

**另一处计划未列的坑**：`createTempPsiFile` 的 refresh 段原本**无条件**包 `WriteIntentReadAction`，慢段搬到池线程后就是 T-PrewarmWilDeadlock 同款自锁 → 必须改成「EDT 保留 WIRA / BGT 裸调」。详见 [[pitfall-edt-psi-warm-and-write-safe-modality]]。

**连带清掉一处既有红**（`7c14f147`）：`ExplainAskPasswordChannelGuardTest` 的锚点 `renderedDialectName = result?.effectiveDialect?.name` 在 UI-004 后已不存在 → 恒红；生产侧 MySQL 回落链完好（W10 定案未破），只是守卫锚点过时。dev 侧同病已同步修。

**发行被这一项阻塞**：发行材料（`00b71210`，`2026.11.4-beta.1`）已备好，但用户定「先修再发」——内测包不能带着 36 条 SEVERE 出去。修完需重建包 → 用户真机验归零 → 才推 tag 与建版。

## 收口债（合流带出，已修）

- 巨文件二连触顶：`DataSourceSelector.kt` 2000→2010、`DataSourceSelectorResults.kt` 2000→2030（各留痕说明理由与「拆分仍首选」）；
- 元守卫补登记 `psiValidityReadLockOffenders`(VIOLATIONS) + `unprotectedIsValidSites`(HELPER/via)；
- 教训见 [[feedback-merge-subagent-branch-must-run-guards]]。

## 其它

- 定向收口口径：从 `test-results XML` 逐类汇总，**不只看 BUILD SUCCESSFUL**（多 pattern 并集假绿，见 [[pitfall-targeted-test-unmatched-pattern-false-green]]）。本轮 12 类 90 例、体检 6 类 59 例。
- 两处测试缺口已补（`5c4af996`）：`SqlLogPanelTypeFilterTest` 10 例、`VersionHighlightsActivityTest` 8 例；契约意外＝类型筛选触发钮显示预算仅 64px 多类型必截尾「…」、筛选态是会话级不进 PluginSettings。
- EXPLAIN 结构化面板 + 换源重渲染 + mask 三档 + 环境自检按钮：合成输入不可达（自绘控件/设置滚动债），真机人工步骤已写进账本 §四，**三项仍待走查**。
- 沙盒数据源套件：拷真机 `MySQL ConnectorJ` 驱动树进沙盒 `config/jdbc-drivers` + 工作区 `.idea` 自带 `mp_demo@localhost`（凭据内嵌 URL）＝免下载免口令。

探针坑见 [[pitfall-probe-ime-and-widget-coords-262]]；找沙盒日志见 [[pitfall-sandbox-log-accumulates-and-default-path]]；账本目录曾被我写错并复活废止目录（`docs/验收报告/`），见 [[project-doc-conventions]]。

## UI-049：runners=0 的提示改为可操作（2026-11-04，用户报「非常不友好」）

用户在 master 包上撞到 `runners=0`（方言 MySQL，而 12 个源无一声明 MySQL），底部红字是「执行失败：没有与该 SQL 方言（MySQL）匹配的已配置数据源……请打开 Database 工具窗口，在目标数据源属性的 Dialects 设置中把 SQL 方言与语句对齐」，后面跟全部 12 个源名。**用户原话：「目前非常不友好，用户看到错误会不知道怎么处理」**。

三个病灶：①一长串源名，不知道该改哪个；②「打开工具窗口→属性→Dialects」纯文字，没有可点入口；③用「执行失败」措辞，实为没配置，用户以为插件坏了（且与右上「未绑定数据源」读起来矛盾）。

修法（`1d8bdc73` + 我修编译 `5cd2ca49`）：新建 `execute/DialectMismatchHint.kt`，用 `UrlSubprotocolDialects` 按 JDBC URL 子协议推断哪些源「看起来就是目标方言」——唯一命中把源名写进标题，多个点名前 5 个其余折叠，推断不出说「给一个数据源启用」；状态行从红色 `showError` 降级为橘色 `showStatusWithActions(warning=true)`，右侧给「打开数据源属性」与「切换数据源」两个可点入口；详文写明「右上『未绑定数据源』就是这个状态——这不是执行失败」，落点改为**属性页底部的 Dialect 下拉**（旧文案「属性→Dialects」位置不准）。`runners` 筛选逻辑与五态归因未动，只接第五态。

**平台 API 查证（javap 双版本）**：入口＝`com.intellij.database.view.ui.DataSourceManagerDialog.showDialog(Project, Object selection, DatabaseCredentials)`，243/262 签名一致，且正是平台右键「Properties」走的同一条路径。**一个被推翻的推断**：选中项不能直接传 `LocalDataSource`——侧栏用 `DbDataSourceImpl` 做 identity map 的 key，须先用 `DbPsiFacade.getInstance(project).getDataSources()` 换成 `DbDataSource`，换不到传 null；全部走 `Class.forName` 反射以遵守 Community 隔离约定。

**用户定下的口径**：UX 改进归「优化」类一句话，不单列成修复。

## 发行落位（2026-11-04）

- **`2026.11.4-beta.1`**：Gitee 发行版 id **1272467**，附件 7,400,631 字节 sha256 `37e49dc2…`（`--prerelease`）；双仓 master+tag 齐（gitee `7c14f147..f6bbf814`、github 一次性补齐 `03edae49..f6bbf814`）；`preview.update.json` 指向 beta.1（`447a461b`）。
- **`2026.11.4-beta.2`**（UX-049 增量）：版本号 `-beta.N` 递增、changeNotes 只留最新 beta 块（beta.1 的 7 条并入）、全量 `releaseGate` 重跑中。
- 分支清理：本地 16→6（保留 master / dev / backup-pre-squash / k2j / t-sql-insight / t-testgap-speed），远端 gitee 删 5 个、github 删 1 个，8 个 `wt-*` 目录用 robocopy 后台清。
