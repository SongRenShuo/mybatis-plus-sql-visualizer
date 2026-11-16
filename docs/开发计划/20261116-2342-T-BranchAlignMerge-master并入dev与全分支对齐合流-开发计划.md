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
| `t-standalone-hotreload` | 0/81 | 124/341 | 无独立工作树，**仅本地**（origin/github 均无） | 是 master 祖先（ahead=0），吃 master 即 fast-forward |
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
2. **第二步**：把 master 与 dev 合入其余全部 7 支（k2j 除外，见 D-1）。
3. **等价性说明**：第一步完成后 `dev ⊇ master`，因此第二步每支 `merge dev` 一次即同时吃进 master 与 dev。为与用户指令逐字对应，默认写法为**每支两笔**：先 `merge master`、后 `merge dev`（轨迹清晰）；若用户嫌合流提交翻倍，可拍板改单笔 `merge dev`（D-3）。**两种写法的最终内容完全等价**。
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

### 第 0 步：前置准备

```bash
git fetch origin --prune          # 补齐远端分支
git switch -c t-hiddenbug-perf-scan origin/t-hiddenbug-perf-scan   # 远端支建本地
git switch -c t-branch-merge-route origin/t-branch-merge-route
```

### 第 1 步：master → dev（dev 工作树）

```bash
git -C mybatis-plus-sql-visualizer-dev switch dev
git -C mybatis-plus-sql-visualizer-dev merge --no-ff master
# 逐 hunk 裁决 §三 的 20 个冲突；文档双保留、代码以 master 基线为准
git -C mybatis-plus-sql-visualizer-dev add <明确路径>   # 禁 -A
git -C mybatis-plus-sql-visualizer-dev commit           # 默认合并信息
```

收口：dev 工作树编译（`compileKotlin` 或 testFast 定向）+ 冲突文件涉及的定向测试（三件测试类冲突必验）。

### 第 2 步：master+dev 合入其余各支（按体量从小到大，逐支顺序）

每支两笔（D-3 可改单笔）：

```bash
git merge --no-ff master
git merge --no-ff dev
```

| 顺序 | 分支 | 执行落点 | 预计冲突面 | 收口 |
|---|---|---|---|---|
| 2.1 | `t-standalone-hotreload` | 主仓（无工作树） | 吃 master 是 ff、吃 dev 约为主→dev 那 20 个文件的子集 | 编译 |
| 2.2 | `T-SqlPreviewStandalone` | 其工作树 | 本支 4 笔领先且近期贴 master 开发，预计小 | 编译 + PreviewStandalone 定向 |
| 2.3 | `t-domestic-db-dialects` | 主仓 | 方言注册表与 dev/master 交叠小 | 编译 + DialectDomesticFamilyTest |
| 2.4 | `t-rollback-preexec` | 其工作树 | ahead/dev=130，SQL 执行链文件交叠风险中 | 编译 + RollbackSnapshot 定向 |
| 2.5 | `t-security-audit-fixes` | 主仓 | 守卫/构建链文件交叠风险中 | 编译 + 安全守卫定向 |
| 2.6 | `t-hiddenbug-perf-scan` | 主仓（新建本地） | 与 security 同基、修复面交叠风险中 | 编译 + testFast 快桶定向 |
| 2.7 | `t-branch-merge-route` | 主仓（新建本地） | 纯文档支，预计小 | 文档无编译面 |
| 2.8 | `t-sql-insight` | mpsv-sql-insight 工作树 | ahead/dev=236、behind/dev=187，**最大**；硬前置见下 | 合并后编译 + 四件定向守卫 + NestGate 默认值重拍 |

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
| 第 0 步 fetch+建本地 | 待做 | | |
| 第 1 步 master→dev | 待做 | | |
| 2.1 hotreload | 待做 | | |
| 2.2 preview | 待做 | | |
| 2.3 dialects | 待做 | | |
| 2.4 rollback | 待做 | | |
| 2.5 security | 待做 | | |
| 2.6 hiddenbug | 待做 | | |
| 2.7 merge-route | 待做 | | |
| 2.8 insight | 待做 | | |

## 七、决策点（须用户拍板）

- **D-1 k2j 支**：`feature/kotlin-to-java` 是 100% Java 端口支，机械合并 master 会把 Kotlin 源整树注入（k2j-port-sync 明文「同步功能不合并分支」）。**默认本轮跳过**，用户若要跟版则另行点名走 k2j-port-sync 独立轮（手工翻译，非合并）。
- **D-2 远端支**：`t-hiddenbug-perf-scan`/`t-branch-merge-route` 仅在远端，本轮 fetch 后建本地支合入；是否把这两支也纳入「所有分支」范围，默认**纳入**。
- **D-3 合流写法**：每支两笔（先 master 后 dev）还是单笔 merge dev（内容等价、提交减半）。默认**两笔**。
- **D-4 推送**：全部合并完成后是否立即 push 双仓（含补 github/master 落后一笔）。默认**等用户点名**。

## 八、待验证前提（实施前先证伪）

- P-1：§三 冲突清单取自 master↔dev 预演，各支实际冲突面未预演——2.1~2.7 每支动手前先对本支跑一次 `merge-tree` 预演再裁决。
- P-2：`t-standalone-hotreload` ahead/master=0 判定其为 master 祖先——吃 master 应 fast-forward，若实际产生冲突则前提错，改走正常裁决。
- P-3：六个工作树当前干净——动手前逐个复跑 `status --porcelain`（并行会话随时可能落改动，快照易过时）。
- P-4：dev 侧 341 笔领先里含 MagpieGems 制度线，与 master 侧 11-16 发行批是否在 `plugin.xml` / Bundle 同键竞争，以第一步实际 hunk 为准，不按预演清单当裁决依据。
