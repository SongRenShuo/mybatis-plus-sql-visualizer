---
name: project-three-lane-branch-merge-20261108
description: 2026-11-08 三车道闲时任务已执行完毕（offpeak-6a4afce2 本体）：3 条 CI 车道全部落地并配置验证、dev/insight 双合并收口（各+1 轮修复）、k2j 177 轮生产面全量收官（B/C 波留 178）；残余红均已逐例登记归因
metadata:
  node_type: memory
  type: project
  originSessionId: sess_ff47e879-9806-498a-8c26-cea7a692c6ac
---

用户 2026-11-08 令：建**闲时任务**，多子代理后台并行完成三个大任务。三条车道原文：

1. **master → dev**：切 dev，合 master 解冲突，跑全量测试修问题，**构建校验不跑**，动代码前先生成开发计划文件。
2. **dev（已合 master 的）→ t-sql-insight**：与任务一同规格。
3. **master → feature/kotlin-to-java**：把 master 增量**同步**过去（手工翻译，**不是合并分支**），解冲突、全量测试修问题、**确保插件构建校验通过**，先出计划文件；**额外编写该分支的 CI**，对齐 master 的「推送测试 + 全量门禁」两套口径。

## 任务已创建（2026-11-08）

`offpeak-6a4afce2-88df-46bb-b37f-ac32f4cccd32`，标题「三分支合并同步与 CI 门禁接入」，**排队第 12 位**，状态 queued。提示词为三轮迭代后的定稿，六车道编排：

- **阶段 0（先于任何编码，三路并行）**：CI-dev / CI-insight / CI-k2j，只碰 `.github/workflows/`，用分支现有提交验证 workflow 本身。
- **阶段 1（两路并行）**：Merge-dev（master→dev）、Sync-k2j（master→k2j 手工翻译）。
- **阶段 2**：Merge-insight，**硬依赖 Merge-dev 完成并冻结其合并提交 SHA**。
- **阶段 3**：三支复推让 CI 跑新代码。

## 用户三条修订（覆盖初版「不 push」）

- **并发上限尽可能试探**：全局活跃子代理从 4 起步，撞限流（1302 类）或成批失败就降 3、再降 2，降级要登记。
- **三条分支都允许推送**。tag / 发行 / `update/update.json` 仍禁止。
- **全量门禁与构建校验全部交给 CI**，本地只留「编译 + 定向测试」；本地 gradle 并发因此从 2 放宽到 3。
- **CI 调整可并行、不必等编码完成**：先用分支现有提交触发一轮，早发现 workflow 语法 / 任务名 / 分片口径问题。

## 侦察读数（2026-11-08 实测）

| 分支 | 落后 master | 领先 master | 工作树 |
|---|---|---|---|
| `dev` | 326 | 272 | `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev` |
| `t-sql-insight` | 380 | 475 | `F:/code/Java/Gitee/SongRenShuo/mpsv-sql-insight` |
| `feature/kotlin-to-java` | 665 | 3759 | `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-kotlin-to-java` |

**CI 文件在役分布（决定每条车道要做什么）**：

- `dev`：三个 workflow 全有，但 `push-test.yml` 的 `on.push.branches` 只写了 `[master]` → 改一行加上 dev 即可。
- `t-sql-insight`、`feature/kotlin-to-java`：**只有 `build-plugin.yml`**，推送测试需从 master 搬进来。
- master 的 `build-plugin.yml` 是 `workflow_dispatch`，且自带 `branch` 输入参数 → **门禁可对任意分支派发，不必在每个分支上新建门禁文件**（前提是 master 版本引用的任务在该分支存在；k2j 纯 Java 端口，这条仍是待验证风险）。
- k2j 分支 gradle 任务名与 master 同构（`testShards`/`verifyGate`/`buildPlugin` 均在），有 `dev-support/testShardsProgress.ps1`。
- `push-all.ps1 -Branch <任意分支>` 支持单分支推送，已固定「读 `.local/github-token.json` + `-c credential.helper=` 置空」。

## 已知阻塞：insight 工作树不在可访问目录 —— **已证伪（2026-11-08 执行轮实测）**

`mpsv-sql-insight` 虽不在会话的「可访问工作目录」清单，但 Read/Write/Edit/Bash 实测**全部可用**（本轮在该车道的计划文件、workflow、merge 操作全程主控+子代理直写，零降级）。下轮再遇「不在 additional working directories」的工作树，先实测一次文件工具再谈跳过。

## 提示词的结构性改动（三轮累计）

- **车道 B 不是并行的**：B 的输入是 A 的输出，必须等 A 的合并提交落地并**冻结该 SHA** 作基线。
- **推送必须走 `push-all.ps1`，禁裸 `git push`**：裸推会拉 GCM GUI 抢焦点，无人值守下等于整晚卡死。这条比「不许推送」更关键。
- **阶段 0 = 三条 CI 车道，先于任何编码**。首轮判据是「job 能启动、matrix 能展开、checkout 与编译能过、gradle 认得任务名」，**不是全绿**——否则子代理会拿代码测试红去改 workflow。
- **CI 资源纪律**（第三轮补入）：公开仓库标准 runner 免费故分钟数不设上限；但 Free 计划总并发 20 job 是**账户级跨仓库共享**，3×6 腿＝18 逼近上限，故同一时刻最多两路跑 CI、gate 不与 push-test 并行；cache 10GB/仓库按分支隔离会互相淘汰，**冷编译变慢属正常，不得据此判 workflow 配置有误**（判配置错只看四项：job 启动/matrix 展开/checkout 成功/gradle 报任务不存在）。详见 [[reference-github-actions-public-repo-limits]]。
- **合并车道与 CI 车道的 workflow 冲突裁决**：取并集，`branches` 列表必须保留阶段 0 加进去的分支名，否则 A 合并 master 时会冲掉 dev 的触发配置。
- **冻结基线 + 开工只 fetch 一次**；**可中断续跑三件套**（计划文件先落盘含基线 SHA/侦察/分阶段/判据/待决五段、每阶段回写「实施记录」、修复循环上限 3 轮）。
- **CI 轮询**：curl + `.local/github-token.json` 调 Actions API，禁 `gh auth login` 等交互命令；单轮上限 25 分钟，超时记「未结论」继续。

另写进硬约束的：冲突逐 hunk 裁决禁整侧采信、文档冲突取并集、master 语义增量必须搬进 dev 新文件、源码扫描守卫前提会过期需重核、判失败是否合并引入用临时 worktree 检出合并前 HEAD 对照（以上来自 [[project-master-into-dev-merge-20261103]] 两轮实证）；k2j 车道流程以 `.agents/skills/k2j-port-sync/SKILL.md` 为准且**禁 merge master、禁 cherry-pick 整提交**、CI **不许照抄 master**；insight 线 NestGate 默认关，合完须重拍默认值（见 [[project-nestgate-semantic-adjudication]]）。

## 已纠正的错误前提（留档防复发）

第一轮曾按「免费额度 2000 分钟/月」估算成本、并提出「每分支推送上限 6 次」。实测该前提不成立：仓库为 public，标准 runner 免费且不消耗 included compute；且 Free 计划口径已改为 $12/月 compute（≈2000 min 只是折算）。该条约束已从提示词删除，替换为并发与缓存纪律。

## 状态：已执行完毕（2026-11-08 闲时执行轮，冻结基线 master 95e107b1）

**阶段 0（三条 CI 车道）**：全部落地、配置判据全过——dev `c499bafa`（push-test 补齐 master 版+branches[master,dev]+nudge master 专属 if）、insight `04b6c05d`（push-test 从基线带入+补 `dev-support/ci/run-gradle-retry.ps1`）、k2j `1b3d1833`（push-test+test-demo 整目录带入+build-plugin 对齐 master 多 job 版，**按分支形态裁剪**：verify 矩阵 243/253/262（k2j 无 263）、marketplace 单 job 跑 `:verifyMarketplace` 聚合（无 verifyMarketplaceVerdict 分 tag 任务）、verify 腿无条件 verifyPlugin 预热替代播种）。

**阶段 1/2（合并车道）**：
- **dev**：合并 `8f4492d5`（21 冲突逐 hunk）+修复 `1961fe6f`（1/3 轮用满即收口：方言 15 断言/巨文件预算提额/fatal 站点/150ms 刷新窗注入五族修，TypeFilter 等 8 例登记环境/既有）。tip=`1961fe6f`。
- **insight**：以 8f4492d5 为基线合并 `feecfd7f`（实际冲突 15 件 24 块，预演 35 件中 20 件被 dev 裁决吸收）+修复 `57a11f65`；**NestGate insight 线默认关核对保持**；O147 族与 EnhancedCaptureDefaultOnGuard 合并后自愈（master buildSrc testFast 排除管线）。tip=`57a11f65`。
- **k2j（177 轮）**：**生产面 112 件全量收官**（A1 sql×14 / A2 execute+ui 非面板×27 / A3 log+config+update×31 / A4a 地基×13+两族 bug / A4b agent 15 件三 blob 实证整件直取 / A4c 面板+杂项×24 + 主控编译收口五轮 `354a2426`，BUILD SUCCESSFUL）。**波次 B（测试 166 件）/C（资源 union、.port-sync 锚点推进）未做**——锚点维持 102795a0，178 轮续跑入口=k2j 树 `docs/开发计划/20261108-T-SyncK2JMaster177-*-开发计划.md`。

**三分支首跑 CI 均非全绿但已逐例归因**（详情在各自计划文件）：dev 8 例（TypeFilter 字体环境/ColumnOrderings/ToolbarIcon/R51 既有）、insight 16 例（Insight* 面板族分支既有+Request/Runtime/OfflineTrace 环境敏感+跨分支同款）、k2j 21+ 例（ParamHistoryCache 19+ExecutionDialectGate 2 潜藏+方言族普查滞后——B 波移植后复验）。dev 轮曾现 gitee.com DNS 瞬断单腿红（环境类）。

**运维发现**：token（repo+workflow scope）调 `POST .../runs/<id>/rerun/failed-jobs` 恒 404（双端点确认）——单腿重跑不可用，只能整轮重推或接受读数；ci-watch.py 后台轮询的 stdout 缓冲导致中间读数不可见（结论仍准）；子代理峰值并发 3（4 上限未触顶、零 1302 限流，未降级）。

下次接手：进度权威=各工作树计划文件+各分支 CI run（dev 42409180430 / insight 42411300423 / k2j 见 177 计划）；k2j 178 轮先读本档+177 计划。

关联：[[reference-github-actions-public-repo-limits]]（并发/缓存/存储硬数字）、[[project-master-into-dev-merge-20261103]]（master→dev 裁决形态与守卫过期教训）、[[project-kotlin-to-java-port-sync]]（k2j 同步机制与锚点）、[[parallel-build-resource-rules]]（本地并发上限依据）、[[feedback-ci-trigger-push-cadence-and-watcher]]（推送取消在跑那轮 + 必须自起后台轮询）、[[project-dual-repo-github-mirror]]（push-all.ps1 令牌通路）、[[project-dev-worktree-checkout-location]]。
