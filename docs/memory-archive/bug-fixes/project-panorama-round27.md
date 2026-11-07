---
name: project-panorama-round27
description: 第二十七轮全景册（20261104 立项，11-05 复核 / 11-06 授权刷新）——master/dev 分叉 128/298、双预演冲突 22/7、v2026.11.4 三连发、master 巨文件 12,480 且守卫只在 dev、15 项新功能候选全授权转 F-01~F-15
metadata:
  node_type: memory
  type: project
  originSessionId: sess_6850c6af-2473-4ea6-b8b5-8e545096c0a0
---

第二十七轮全景审计（深查版）：报告 `docs/调研报告/20261104-0642-...第二十七轮调研报告.md` + 计划册 `docs/开发计划/20261104-0655-...第二十七轮落地...-开发计划.md`。计划册于 2026-11-05 23:5x 应用户令「最近代码又有变动，再更新一下全景文件」全节复核刷新（本地提交 `3b8d9bdd` + 口径订正 `6d04bc0b`，未推送）。

## 20261105 23:5x 复核读数（主控亲测）

- 分支：master `8bd4ddf5`（双远端一致）；dev `84dd8b59`（origin 一致、**github 欠推 39**）；k2j `aa004fab`；insight `0b8ef457`；repro 支 `70d44bf4`（master 之上 3 笔测试探针，保留不删）。merge-base `62aca9c9`；**master 独有 128 / dev 独有 298**（较 11-04 基线 master +132、dev +57）。dev 不含 master、master 不含 dev。
- 冲突预演（merge-tree 实测，`--name-only` 输出要先按空行截断，否则把 Auto-merging 行算成冲突）：master→dev **22 文件**（11-03 首轮 13）；insight→dev **7 文件**（26 册 4 个 + `SqlHookCallbacks.java`/`WovenEvents.java` 两个 agent 钩子 + `docs/MEMORY.md`）。
- 发行：**v2026.11.4 三连发**（11-04 当日 beta.1→beta.2→正式）；Gitee id 正式 **1273611** / beta.2 1273356 / beta.1 1272467；Marketplace 顶行已 2026.11.4、下载 **791**（11-04 读 721，约 41/天）、**votes 0**；`preview.update.json`=2026.11.4 而 **`update/update.json`=2026.11.4-beta.2**（用户已从 2026.10.31-beta.2 推进，仍差正式档一档）。
- 门禁（11.4 转正）：`releaseGate` **8607/0/0/14**（5 片）+ testExternal 31 + agent 247/0/0/1skip + verifyGate 三目标（IU-243/253/262）全 Compatible。
- 巨文件：master 五件 **12,480 行**（SqlPreviewPanelExecution 2816 / DataSourceSelectorResults 2646 / SqlLogPanel 2485 / DataSourceSelector 2432 / SqlPreviewService 2101）；**`GiantFileBudgetTest` 只存在于 dev，master 无守卫**；dev 实测 2027/2007，守卫已按 UI-002/003 提额至 2010/2030（留痕、拆分仍首选）。
- 纪律面：`docs/变更记录.md` 尾注仍 **5 处**、仓根 AGENTS.md 仍缺、W1 三件文案仍零稿。
- k2j：锚点 `102795a0` → master 增量 **151 笔**（27 册立项时 19 笔）。

## 本轮闭合与新增

- **闭合**：S1-4（T8 `3c55f11f` 已实测含于 master，随 t-testgap-speed 收编）、S1-5（fix-scan-bannedapi-edt 分支已不存在）。
- **新增 S1-7**：master→dev 第二轮消差（22 文件冲突，重点 `DataSourceSelector*.kt`、`SqlPreviewPanelExecution.kt`、`PreviewPreparePick.kt`、两套 bundle、`test-shard-weights.tsv`、`mpv-verification.gradle.kts`）；dev→master 顺延其后。
- **孤立丢件**：`6cfa5d12`（t-fix-completion-original-file 唯一提交）全库不可达——见 [[pitfall-branch-delete-orphans-unmerged-tip]]。
- master 独有 4 个新能力（dev 都没有）：PagesHql（`98554a4f`+`d04ffa2d`）、MapperNavShortcut（`f299417b`）、LogMethodName（`0f9e199c`）、CrossChannelDedupe 解耦（`16715ec9`/`b0591429`/`67520e95`）；另有捕获闸门修复 `5d1d2f79`、测试类提交 16 笔（JUnit3 语义清理为主体）、CI 夜间 18 笔。
- dev 独有：A/B/C 三类 EDT 线程修复（`f0ae0598`/`ce499a5e`/`f8608db5` + 合并 `d0afa46a`）、UI-002~005、PerfSweep30 `25cb1eec`、回 port 账本。

## 20261106 授权刷新（用户令）

- **15 项新功能候选全部授权**（§〇之二 A 组 11 项 + B 组 DBX 4 项）→ 拆成实施队列落
  `docs/开发计划/20261105-2350-闲时任务待办清单-开发计划.md` §一之二 **F-01~F-15**。统一约定＝
  **以 dev 为基底（NEW-26 Inspection 等新能力只在 dev）、每件一条 `t-idle-fNN` 分支、只实施不合并不推送、
  每件一提交、反向验证必做**。P0＝F-01 类型感知字面量还原 / F-02 参数可解析性 Inspection /
  F-03 工具窗常驻与随项目启动 / F-12 DBX #316 任务历史一键重执行。
- DBX 四项已核实到具体条目（此前只记号数）：**#316 任务历史一键重执行**（P2）/ **#317 结构预览列排序**
  （单一纯函数 + 三面板复用，勿三处各写）/ **#318 UTF-8 无 BOM CSV 开关**（默认仍带 BOM）/
  **#319 SQL Server 空间类型保真导出**（二进制列反解 `STGeomFromText('WKT', SRID)`）；下一全新 #320。
- **S1-7（master→dev）＝「已授权、未执行」**：用户先「master→dev 允许」，我起了 merge（22 冲突）后
  被「只更新 md，不用改代码」叫停，`git merge --abort` 回退，dev 回到 `84dd8b59` 无残留提交——
  见 [[feedback-doc-update-no-code]]。
- 仍冻结：**C 组 6 项功能调整/决策点**（R45 三件套、update.json 补至 2026.11.4、去 `-beta`、
  autoCheckUpdate 引导、版本内评分引导句、项目设置 VCS 分层）+ **D 组 4 项对外动作**
  （W1 发射、描述页线上更新、发行 11.5、推送）。合计口径＝需定案 25 项，其中已授权可动 15、冻结 10。
- 孤立提交 `6cfa5d12`：用户令「这个不要了」→ **不恢复**，S1-8 闭合（修法与教训见
  [[pitfall-branch-delete-orphans-unmerged-tip]]）。
- 本轮三份文档提交（全部本地未推）：`3b8d9bdd` 基线刷新 / `4a6ebb3b` 定案清单 / `699ab2ea` 功能授权；
  master 现累计欠推 5 笔（含 `d7ae1e9c` 闲时清单、`4c41c9d5`/`5fe317ca` 等）。

## 20261106 工作流执行（用户令全流程授权：dev 合 master→testFast→三组并发→主控合并，只跑定向）

- **S1-7 已执行**：dev 合并 master（d13aade6）＝合并 `fafc2042` + 收尾 `f80d7aab`，22 冲突逐 hunk 裁决——详见
  [[project-master-into-dev-merge-20261103]] 第二轮节。testFast 4703 例，4 例本轮修复转绿，余 6 例**合并前 HEAD 对照复现**（环境/存量）。
- **F 队列三组并发**：组1（F-01/F-02）✅、组2（F-03/07/08/12）✅、组3（余九件）在途——实施状态与读数见
  [[project-idle-queue-f15-20261106]]；集成分支 `t-idle-integration`=`b8b03520`（f1+f2 零冲突并入，预编译绿）。
- **master 在合并后又前进**：`980a2d14`（A-01 D201 落档：仓根 AGENTS.md 入库〔需删 .gitignore `/AGENTS.md` 行，同 231378e1 先例〕+
  尾注 5 处改口径）、`7f7255aa`（A-02/A-06 六册状态回填+11.4 落位补账 Gitee 1273611）、`090c7704`（A-03/A-04 W1 文案稿，见
  [[project-w1-n24-zero-review-campaign]]）、`0d73fc2c`（闲时清单进度回填）——这些不在 S1-7 合并内，**下次 master→dev 还要再合**。
- A-05/A-07~A-16 未动（A-07 需全量 testShards 与「只跑定向」冲突，留收口轮；A-10 只出方案）。
- 孤立提交 `6cfa5d12` 已按用户令闭合（不恢复）；Marketplace 插件 API id 实测是 **33633**（旧读数 1209800 已 404）。

## 11-04 立项时读数（历史）

- 两大新面孔：t-testgap-speed 含 T8 `3c55f11f`；fix-scan-bannedapi-edt 已 100% 含于 dev。双干欠推 6 笔；insight→dev 4 文件冲突；update.json 当时 beta.2 滞后两版。
- 竞品：v2026.11.3 当日过审（连续第二版成制度）、下载 721、双零 78 天；MybatisToolPlus 2.8.0 经 r36 取证零新功能（O-160 销案降 P2）。
- 六条新盲区：agent 卸载残留、SQL 历史多项目混流、超大 SQL 护栏、.idea VCS 协作面、中文注释 GBK 未验证等。
- 新功能候选 13+5：R45 EXPLAIN（只差合流）/类型感知字面量/参数可解析性 Inspection/代理层日志/JPA 捕获。

执行形态教训：三路后台并行时竞品路撞账户并发上限失败一次，重派即过——3 路后台并发是临界值。另：GitHub 匿名 API 额度会因共享出口 IP 耗尽（11-05 取 Release id 时 403/rate limit），缺失读数如实登记不得判发行缺漏。关联 [[project-panorama-round26]]、[[project-master-into-dev-merge-20261103]]。
