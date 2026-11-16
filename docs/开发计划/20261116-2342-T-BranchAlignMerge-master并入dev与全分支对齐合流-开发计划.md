# T-BranchAlignMerge：master 并入 dev，再将 master+dev 对齐合入全部分支 — 开发计划

- 日期：2026-11-16 23:42
- 任务编号：T-BranchAlignMerge
- 来源：用户 2026-11-16 指示「先将主分支合并到 dev 分支，再将主分支和 dev 分支合并到对应的其他所有分支中，先生成开发计划文件」
- 性质：全分支对齐合流（用户已点名授权本轮合流方向；**推远端仍需单独授权**，AGENTS 规则 9/10）
- 状态：计划（未实施）
- 与先例关系：前接 [T-BranchMergeRoute（20261114 合流评估）](../开发计划/20261114-1149-T-BranchMergeRoute-全分支合流评估与合入路线-开发计划.md)；本轮是其「合流动作」的**反向对齐轮**——不是把分支合进 master，而是把 master+dev 合进每支，消掉各支与主线的落后

---

## 一、侦察基线（2026-11-16 23:35 实测，`git rev-list --count` 口径：ahead/behind）

### 1.1 本地有落点的分支

| 分支 | vs master | vs dev | 落点 | 说明 |
|---|---|---|---|---|
| `dev` | 0/205 | 0/0 | 独立工作树（mybatis-plus-sql-visualizer-dev） | 落后 master 205 笔，工作树干净 |
| `t-domestic-db-dialects` | 9/384 | 9/520 | 无独立工作树 | A 级自包含方言支 |
| `T-SqlPreviewStandalone` | 4/90 | 119/341 | `.worktrees/T-SqlPreviewStandalone` | 预览独立打开支，工作树干净 |
| `t-rollback-preexec` | 12/87 | 130/341 | `.worktrees/t-rollback-preexec` | 快照预执行支，工作树干净 |
| `t-security-audit-fixes` | 61/383 | 61/519 | 无独立工作树 | 安全审计支 |
| `t-sql-insight` | 568/383 | 236/187 | 独立工作树（mpsv-sql-insight） | 语义引擎支，体量最大；ahead/dev=236、behind/dev=187 说明与 dev 各有独有提交 |
| `feature/kotlin-to-java` | 820/4282 | 820/4418 | 独立工作树（kotlin-to-java 目录） | **100% Java 端口支，见 D-1，不做机械合并** |

### 1.2 仅远端的分支（无本地分支，须先 fetch 建本地）

| 分支 | vs master | vs dev | 说明 |
|---|---|---|---|
| `t-hiddenbug-perf-scan` | 57/384 | 57/520 | 隐藏缺陷扫描支（origin + github 都有） |
| `t-branch-merge-route` | 127/87 | 245/341 | 上轮合流路线文档支（origin + github 都有） |

### 1.3 工作树状态

六个工作树 `status --porcelain` 全空（干净），可直接落合并。

### 1.4 远端注意项

`github/master` 停在 `cb7a48e66`（v2026.11.16-beta.2），`origin/master` 已到 `56753b1bf`（正式版落账）——**github 远端落后一笔落账提交**，本轮收尾推送时一并补齐（推送动作本身待授权）。

---

## 二、目标与总原则

1. **第一步**：`master → dev`（在 dev 工作树执行）。
2. **第二步**：其余各支**分组对齐**（用户 2026-11-16 明确：有的吃 dev、有的吃 master，不是每支都吃两份，k2j 除外见 D-1）：
   - **吃 master 组**（6 支）：基线在 master（behind master ≪ behind dev）、归宿是合回 master 的功能支——只 `merge master`，**不吃 dev**（吃 dev 会把 dev 线 341 笔 MagpieGems 等整线拖进功能支，造成反向污染）；
   - **吃 dev 组**（1 支）：`t-sql-insight` 基线在 dev（behind dev 187 ≪ behind master 383），且先例定案走 insight→dev→master——只 `merge dev`（第一步后 dev ⊇ master，顺带吃进主线 205 笔）。
3. 分组判据（双证）：① 数值接近度（每支 behind master vs behind dev，§1.1 实测）；② 先例路线归属（T-BranchMergeRoute 定案：dialects/hiddenbug/security/rollback/preview 走合回 master 路线，insight 走 dev 路线）。两组无歧义支。
4. 冲突裁决约定：
   - 文档类（CHANGELOG.md / README.md / docs/LESSONS.md / 架构设计 / 索引）：**双保留**，按时间顺序重排条目；LESSONS 单行表格行沿用「checkout 选边 + 小 Edit 补格」先例。
   - 代码类：逐 hunk 裁决，以 master 侧为基线语义（本轮目的就是让各支吃进主线新形态），分支侧增量保留。
   - 合并提交本身**不回填 CHANGELOG `[Unreleased]`**（合并不是用户可感新改动）。
5. 并行纪律：跑 Gradle 前先 `parallel_tasks.py check` + register/done；构建并发 ≤2；每支只跑**定向测试**，不跑全量、不 clean、不 --stop。

---

## 三、master↔dev 冲突预演（`git merge-tree --write-tree master dev` 实测）

预演产出约 **20 个冲突文件**，分三类：

- 文档/索引（约 9）：`CHANGELOG.md`、`README.md`、`docs/LESSONS.md`、`docs/memory-archive/README.md`、`docs/架构设计/01-项目架构总览.md`、`02-核心流程详解.md`、`03-开发与测试指南.md`、`docs/索引/开发计划.md`
- 生产代码（8）：`PluginSettingsFolderConfigurable.kt`、`DataSourceSelector.kt`、`MapperXmlLocator.kt`、`SqlLogPanel.kt`、`SqlPreviewService.kt`、`StartupUpdateCheck.kt`、`plugin.xml`、Bundle.properties / Bundle_zh.properties
- 测试（3）：`PluginSettingsPersistenceTest.kt`、`GuardSelfTestCoverageMetaGuardTest.kt`、`LastUsedWriteOnEdtGuardTest.kt`

代码冲突集中在 11-12~11-16 master 侧的发行批次与 dev 侧 MagpieGems 线的交叠文件（SqlLogPanel / SqlPreviewService 是两条线共同高发区，与在册「并行会话高频撞车面」一致）。

---

## 四、实施步骤

### 第 0 步：前置准备与清理（清理部分 2026-11-16 已完成）

```bash
git fetch origin --prune          # 补齐远端分支
git switch -c t-hiddenbug-perf-scan origin/t-hiddenbug-perf-scan   # 远端支建本地
git switch -c t-branch-merge-route origin/t-branch-merge-route
```

已完成清理（用户 2026-11-16 点头）：① `t-standalone-hotreload` 本地支删除（ahead/master=0 已被 master 吸收，`git branch -d` 安全删，悬置哈希 `28531e967` reflog 可回溯）；② `.worktrees/mybatis-plus-sql-visualizer-demo/` 孤儿目录删除（是 demo 仓 gitee `mybatis-plus-sql-visualizer-demo.git` 的独立完整克隆，不在本仓 worktree 注册表，local master 0 笔独有、落后 origin 19 笔、树干净）。

### 第 1 步：master → dev（dev 工作树）

```bash
git -C mybatis-plus-sql-visualizer-dev switch dev
git -C mybatis-plus-sql-visualizer-dev merge --no-ff master
# 逐 hunk 裁决 §三 的 20 个冲突；文档双保留、代码以 master 基线为准
git -C mybatis-plus-sql-visualizer-dev add <明确路径>   # 禁 -A
git -C mybatis-plus-sql-visualizer-dev commit           # 默认合并信息
```

收口：dev 工作树编译（`compileKotlin` 或 testFast 定向）+ 冲突文件涉及的定向测试（三件测试类冲突必验）。

### 第 2 步：分组对齐（按体量从小到大，逐支顺序，每组单笔）

**2A 组——吃 master（6 支，基线在 master、归宿合回 master）**：

```bash
git merge --no-ff master
```

| 顺序 | 分支 | 执行落点 | 预计冲突面 | 收口 |
|---|---|---|---|---|
| 2.1 | `T-SqlPreviewStandalone` | 其工作树 | 本支 4 笔领先且近期贴 master 开发，预计小 | 编译 + PreviewStandalone 定向 |
| 2.2 | `t-domestic-db-dialects` | 主仓 | 方言注册表交叠小 | 编译 + DialectDomesticFamilyTest |
| 2.3 | `t-rollback-preexec` | 其工作树 | SQL 执行链文件交叠风险中 | 编译 + RollbackSnapshot 定向 |
| 2.4 | `t-security-audit-fixes` | 主仓 | 守卫/构建链文件交叠风险中 | 编译 + 安全守卫定向 |
| 2.5 | `t-hiddenbug-perf-scan` | 主仓（新建本地） | 与 security 同基、修复面交叠风险中 | 编译 + testFast 快桶定向 |
| 2.6 | `t-branch-merge-route` | 主仓（新建本地） | **非纯文档支**（20261116 复核实测：213 文件 +13064/−811，含 agent hook 等真实代码），冲突面预计中等 | 编译 |

**2B 组——吃 dev（1 支，dev 线上演进支；dev 已含 master，单笔 merge dev 即同时吃进主线）**：

```bash
git merge --no-ff dev
```

| 顺序 | 分支 | 执行落点 | 预计冲突面 | 收口 |
|---|---|---|---|---|
| 2.7 | `t-sql-insight` | mpsv-sql-insight 工作树 | ahead/dev=236、behind/dev=187，**最大**；硬前置见下 | 合并后编译 + 四件定向守卫 + NestGate 默认值重拍 |

**insight 支硬前置**（沿用 T-BranchMergeRoute 在册结论）：
- merge-tree 预演先行，真冲突面逐 hunk 出裁决清单后才动；
- 合入后重拍 NestGate 默认值（insight 线默认关 vs 主线默认开，合流时必须显式裁决，不能隐式继承）；
- 四件定向守卫复跑。

每支收口后立即提交合并结果（**达标即提交**，防掉线丢工作）；每支完成即更新本计划 §六 实施记录。

**并发车道说明**：合并裁决需要连续上下文，默认主控逐支顺序做；若用户要求提速，2.2/2.3/2.4/2.6 可派子代理并行（各支工作树互不相干、无共享 build 面），但须 register 独立沙盒且并发 ≤3。

### 第 3 步：k2j 支（见 D-1，默认本轮不动）

### 第 4 步：收尾（推送待授权）

- 全部支落定后，把本轮各支合并结果 push 双仓须**用户点名**：`dev-support/push-all.ps1 -Branch <分支>`（禁裸 push）。
- 顺带补 `github/master` 落后的一笔落账提交。
- 本地独有的 `t-standalone-hotreload` 无远端，按「本地独有分支尽快 push 留底」惯例建远端留底（待授权）。

---

## 五、验证与收口纪律

- 每支合并后：**编译门禁**（该工作树 `compileKotlin`）+ **定向测试**（§四 表逐支列出；纯文档支免测试）。
- 反向验证不适用（本轮无行为改动，是纯合流）；但**每个冲突文件的裁决结果须在 §六 实施记录中登记**（文件 → 保留哪侧/怎么合），便于复盘追责。
- 全量门禁不本地跑（用户 2026-11-13 口径）：本轮合流不动 master，各支若后续要往 master 合，各自走当时的收口轮；本轮只保证「各支编译绿 + 定向绿」。
- not run 条款照实登记：任何没跑的验证写明原因。

## 六、实施记录（随进度回填）

| 步骤 | 状态 | 合并哈希 | 冲突文件与裁决 |
|---|---|---|---|
| 第 0 步 清理 | **已做**（2026-11-16）：删 `t-standalone-hotreload`（悬置 `28531e967`）+ 删 demo 孤儿目录 | | |
| 第 0 步 fetch+建本地 | 已可用（master 本地引用在位，第 1 步 merge 直接受益） | | |
| 第 1 步 master→dev | **已合并＋编译定向全绿**（2026-11-17，merge commit `aba1e47ba`；实际冲突 20 文件＝预演清单逐个一致，无增减）。编译：`compileKotlin` BUILD SUCCESSFUL 3m32s（`-Pkotlin.compiler.execution.strategy=in-process`）。定向 10 类合并一次 96 用例 0 失败 0 错误 0 跳过（PluginSettingsPersistence 24、GuardSelfTestCoverageMetaGuard 8、LastUsedWriteOnEdt 3、DumbModeClickChain 5、MapperXmlLocator 10、SqlPreviewServicePreview 16、DataSourceSelector 2、StartupUpdateCheck 10、PreviewConsoleEager 7、SqlLogPanelSortAndSlowQuery 11）。not run：全量门禁/verifyGate/testShards（本轮纪律单路收口由主控统一安排，本步只做定向）；复选框计数 46 预置经 PluginSettingsPersistenceTest 24 例实跑核对成立。补充修复 2 笔：`5bcfe2de6`（dumb 夹具退役缝引用随 F1''' 同步）+ `097474f1e`（dumb 态断言对齐 master 契约——locate 抛 IndexNotReadyException、locateOrUnknown 归 Lookup.Unknown；首跑 2 红修正后复跑绿） | `aba1e47ba` | 见下方逐文件裁决表 |

**第 1 步逐文件裁决表（20 文件，实际清单＝预演清单）**

| 文件 | 裁决 |
|---|---|
| CHANGELOG.md | 双保留重排：dev `[Unreleased]` 的 OB 收口条目在前，master 的 2026.11.16 / 2026.11.14 版本段随后；python 脚本写盘曾出现半落位（标记残留+段落重复），已用 Edit 工具修正复核 |
| README.md | hunk1 取 master 新措辞（运行期占位逐个填值 + Mapper 双向跳转）并保留 dev 独有「禁用 API 检查」条目；hunk2 增强捕获取 dev 全句（JDBC 兜底匹配为 dev 独有文档增量，master 未删该能力）+ 控制台回落取 master（补 iBatis） |
| docs/LESSONS.md | master L52~L79 编号优先；dev 侧 L52（testShards lane）撞号顺延重编为 L80 置于 master 块后；两侧无文内交叉引用需修正 |
| docs/memory-archive/README.md | 计数撞数按统计节口径（归档目录文件数）实测重登：bug-fixes 300 / pitfalls 112，统计行 archive 420；双保留无丢行 |
| 架构设计/01-项目架构总览.md | 6 hunk：对齐头取「master@2026-11-13 并入 dev 合并态」合并表述；附属能力取 master 新基线 + dev 独有增量（java.sql 兜底、重渲染回落出口、达梦 EXPLAIN/NOT_A_SQL_DATASOURCE、禁用 API/留痕/模板 hook 三件、9 子页）；测试行按合并态实测 1024 文件（1017 kt+7 java，*Test.kt 996）重写；E1/E2 与 X1/X2 取 master（19 provider）+ dev ScanBannedApiUsagesAction；表格 gutter/Action 行同理合并 |
| 架构设计/02-核心流程详解.md | 两侧各有一个 §2.9：master MBG 段留 2.9，dev FR-0045 段重编 2.10，双保留 |
| 架构设计/03-开发与测试指南.md | 3 hunk：三档计数按合并态实测（537/436/23=996）；守卫族清单取 master 行 + dev 独有成员（DumbModeClickChainGuardTest、HotUpdateSettingsGuardTest），计数按实测 70/7/83 GuardEntry；3.2 分包表 master 散文为基线 + dev 独有包（diagnostic 4 / dumbmode 1 / inspection 12 / ui/nav 等）并按脚本实测重登各类数 |
| docs/索引/开发计划.md | 3 hunk：hunk1 master 14 新条目 + dev 20261113-0032 条目按日期序插入并去重（master 重复的 20261112-1350 行取 master 新版删 dev 旧版）；hunk2 dev 36 行 + master 独有 11 行并集按日期倒序重排；hunk3 取 master（状态已更新为已闭环/部分落地） |
| PluginSettingsFolderConfigurable.kt | 双保留：dev gutterGroup.aggregate 与 master consolePrep 两条搜索索引条目并存 |
| DataSourceSelector.kt | 3 hunk 均取 dev：`onEdtForLastUsed` 辅助函数与 master 内联 invokeLater 逻辑逐字等价（同 ModalityState.any()），dev 重构形态承载 |
| MapperXmlLocator.kt | 取 master F1''' 索引化新形态（120 行版）；dev 侧 T-PS5-05 单槽 TTL/memo 机制随 master「整体退役」删除，import 去重（IndexNotReadyException 单份） |
| SqlLogPanel.kt | 取 dev 单行 `internal var pendingRefreshAfterChain`；master inline 的 appendRebuildChunk/flushRebuildChainSync/排序族与 dev 拆分文件 SqlLogPanelRefresh.kt 语义等价，丢弃 inline 块防重复定义 |
| SqlPreviewService.kt | dev dispatchPrewarm 重构形态为基线 + master T-PreviewExecWriteDefer 惰性门禁（`PreviewConsoleEager.enabled()`）提至派发点；dumb 重试路径同补门禁 |
| StartupUpdateCheck.kt | 双保留：master HotUpdateSettingsGuard 拉起 + dev UpstreamVersionMonitor，两钩子并存 |
| plugin.xml | 3 hunk 均双保留（notificationGroup 三组并存；startup activity 六条并存；dev inspection 族 8 条 localInspection + master 6 条 registryKey 顺序拼接）；XML minidom 校验通过 |
| MpSqlVisualizerBundle.properties | 双保留：dev onboarding.mapperPrimer/statusbar.sqlviz 键 + master dialectGapTip 键 |
| MpSqlVisualizerBundle_zh.properties | 同上（中文档） |
| PluginSettingsPersistenceTest.kt | 复选框计数断言：dev 注释链为基线（含 SettingsConverge -6 等）+ master 两开关（dialectTip/firstCaptureGuide）+2 → 预置 46，实跑核对 |
| GuardSelfTestCoverageMetaGuardTest.kt | 2 hunk 双保留：dev 5 条 + master 2 条 GUARD_CLASSES 全并存（合并态 83 条 GuardEntry） |
| LastUsedWriteOnEdtGuardTest.kt | add/add 取 dev 版（大括号配对切体防假绿 + 委托归位形态 + 辅助函数兜底用例），与合并后生产代码委托形态匹配 |
| 2.1 preview 吃 master | 待做 | | |
| 2.2 dialects 吃 master | 待做 | | |
| 2.3 rollback 吃 master | 待做 | | |
| 2.4 security 吃 master | 待做 | | |
| 2.5 hiddenbug 吃 master | 待做 | | |
| 2.6 merge-route 吃 master | 待做 | | |
| 2.7 insight 吃 dev | **已合并**（2026-11-17，merge commit `805068ede`；实际冲突 7 文件＝预演清单一致）。编译与定向读数见本行补记（构建车道等位后回填）。NestGate 默认值硬前置裁决：**主线默认开**（`resolveEnabled` 恢复 dev 形态，回退开关 `-Dsqlvis.agent.nestGate=false`），insight 侧 2026-10-30「默认关」裁决注释留档备查；SqlInsightWeaveProbeTest 改按回退开关形态守卫洞察身份模型契约（门开时异对象嵌套外层帧被抑制＝已登记运行时差异）；NestGateTest 开闸注释同步改口径 | `805068ede` | docs/索引/开发计划.md＝dev 侧 19 行全并入 + T-SqlInsight 行保留 insight 交付状态（dev 侧是「功能未开工」旧档）；SqlPreviewPanel.kt＝两枚测试锚点常量并存（BIND_STATE_SWITCH_NAME / BIND_STATE_SHOW_CONSOLE_NAME）；SqlPreviewPanelExecution.kt＝insight 代际守卫（generation/result/acceptsDelivery）+ 主线 preparedAt 计时并存；xml 四件＝主线语义为基线 + insight 渲染留痕并入：TrimSqlNode.appendSql 取主线 R2 粘词补空格 + insight `accountAppend` 记账与 renderTrace/traceSlice 成员；ForeachSqlNode 取 insight `applyNode` 派发 + 主线 `hasContent` 判据；XmlSqlRenderer.render 取 insight traceEnabled/isCancelled + 主线 emptyDollarPlaceholder（DynamicContext 五参）；XmlPreviewModel.renderSql 取 insight scenarioBindings（collection/parameter values）+ 主线 kindDriven 过滤与 ${} 空值占位 |

## 七、决策点（须用户拍板）

- **D-1 k2j 支**：`feature/kotlin-to-java` 是 100% Java 端口支，机械合并 master 会把 Kotlin 源整树注入（k2j-port-sync 明文「同步功能不合并分支」）。**默认本轮跳过**，用户若要跟版则另行点名走 k2j-port-sync 独立轮（手工翻译，非合并）。
- **D-2 远端支**：`t-hiddenbug-perf-scan`/`t-branch-merge-route` 仅在远端，本轮 fetch 后建本地支合入；是否把这两支也纳入「所有分支」范围，默认**纳入**。
- **D-3 合流写法**：**已定案**（用户 2026-11-16）——按支分组，不是每支吃两份：吃 master 组 6 支单笔 `merge master`，吃 dev 组 insight 单笔 `merge dev`（dev 已含 master）。
- **D-4 推送**：全部合并完成后是否立即 push 双仓（含补 github/master 落后一笔）。默认**等用户点名**。

## 八、待验证前提（实施前先证伪）

- P-1：§三 冲突清单取自 master↔dev 预演，各支实际冲突面未预演——2.1~2.7 每支动手前先对本支跑一次 `merge-tree` 预演再裁决。
- P-2：`t-standalone-hotreload` ahead/master=0 判定其为 master 祖先——吃 master 应 fast-forward，若实际产生冲突则前提错，改走正常裁决。
- P-3：六个工作树当前干净——动手前逐个复跑 `status --porcelain`（并行会话随时可能落改动，快照易过时）。
- P-4：dev 侧 341 笔领先里含 MagpieGems 制度线，与 master 侧 11-16 发行批是否在 `plugin.xml` / Bundle 同键竞争，以第一步实际 hunk 为准，不按预演清单当裁决依据。
