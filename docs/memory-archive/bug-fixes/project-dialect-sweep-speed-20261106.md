---
name: project-dialect-sweep-speed-20261106
description: T-DialectSweepSpeed（20261106 立项并派活）——真机矩阵 dialect 腿 43.5min 压缩：成本模型＝每词 8 个往返 × 0.58s/往返，手段＝实例并行（Σ→max）＋减往返；用户明确不抽样、不做批量建表
metadata:
  node_type: memory
  type: project
  originSessionId: sess_33620d7f-a4ec-4f92-8fbe-dcd146afc43f
---

# T-DialectSweepSpeed：真机矩阵耗时压缩（2026-11-06 立项）

计划册＝`docs/开发计划/20261106-2205-T-DialectSweepSpeed-真机矩阵耗时压缩-开发计划.md`（已登记索引）；
**仅本地提交 `5c008566`，未推**（文档-only 按 [[feedback-ci-trigger-push-cadence-and-watcher]] 攒着）。
子代理在 `t-dialect-sweep-speed` / `.worktrees/sweep-speed` 实施，与 [[project-runtime-slot-fill-20261106]] 券 A 并行（文件域不相交）。

## 成本模型（读代码 + 对账 CI 读数，不是估算）

`sweepReservedWords`（`DialectVerifySupport.kt:343-427`）对**每个保留字打 8 个往返**：
`DROP(预备) → CREATE → INSERT → SELECT(渲染) → INSERT(批量·带参) → UPDATE(渲染) → UPSERT(带参) → DROP(收尾)`。

对账 2026-11-06 CI 实测（四实例子集，`dialect` 腿总 **43.5min**）：
`mysql84 20.3min(262词) / pg16 3.5min(86词) / panwei_a 5.5min(109词,skip=1 rowid) / gaussdb 14.1min(197词)`
⇒ ≈**0.58s/往返**；mysql84 单实例已接近「词数×往返×往返耗时」⇒ **方言套件 `runTcSuite` 占比很小，时间全在「词数 × 往返数 × RTT」**。

## 手段（用户已拍板）

1. **实例并行**（主收益 −50%）：同 JVM 内按实例并发（`min(实例数,4)`），各自连接、各自看门狗；`MatrixSummary` 加锁、**输出按 `instances` 声明顺序**（不按完成顺序）；四桶/optional/「至少一条 PASS」语义不变。墙钟 Σ→max ⇒ CI 步骤预算可从 **120min 降到 ~50min**（最坏等待＝最大单实例预算 1800s 而非 Σ=6600s）。
2. **减往返**：①去预备 `DROP`，每词唯一表名 `kw_probe_<idx>`，CREATE 失败回退一次（DROP 后重试）→ 8→7；②PG 族渲染 DML 走多语句批处理（`s1; s2; …`）→ 7→4，**批处理失败自动回退逐条**以保住逐词明细；MySQL 需 `allowMultiQueries`，**不为此改矩阵 URL**，探测不支持就退回逐条。
3. **Runner 挪近网**（RTT 200ms→5-20ms，43.5→6-8min）属基础设施，只登记不实施。

## 明确的非目标（用户当场否决）

- **不抽样**：覆盖口径不许降（CI 必须跑全量保留字，不做 `hash%3` 抽子集）。
- **不做批量建表**（一张表塞 N 个引号列）：会把「表位置 vs 列位置」的考题改掉，且失败要靠 bisect 定位，收益/风险不划算。

## 待验证前提（P-1~P-5）

| 编号 | 前提 | 退路 |
|---|---|---|
| P-1 | 四实例并发不互相干扰（四个实例分属两台测试机，可能同机两个） | 降并发或按 host 分组串行同机实例 |
| P-2 | 并发下看门狗仍独立超时 | 回退串行 |
| P-3 | PG 族接受 `;` 多语句且错误可读；MySQL 需 `allowMultiQueries` | 只保留「去预备 DROP」那一档 |
| P-4 | 唯一表名 + 失败回退重试不产生「表已存在」假红 | 恢复预备 DROP（回到 8 往返） |
| P-5 | 预算公式改成「max(预算)+余量」并同批改 `nightly-full.yml` 与 `T-ReleaseChecklist` | 保持 120min 只改代码 |

## 验收要求（已写进派活）

覆盖不变（四实例词数与 `skip=1(rowid)` 与基线逐字一致）／`pg16` 单实例改前改后耗时对照／完整四实例改后总耗时（目标 ≤25min）／`-Dmpvs.external.instanceTimeout=1` 仍落 TIMEOUT 桶／三条反向验证（并发改回串行→耗时回升、去掉批处理回退→明细丢失、summary 改成完成顺序→顺序断言红）。

## 一轮结果（2026-11-07 已合 master 并 CI 实测）

`dialect` 腿 **43.5min → 21.6min / 22.1min**（run 42258160804 / 42315646150，两轮稳定），4/4 PASS、词数与 `skip=1(rowid)` 逐字未变。逐实例：

| 实例 | 前 | 后 | 备注 |
|---|---|---|---|
| mysql84 | 1219s | **897s** | `batch=off`（MySQL 默认拒绝多语句），只吃到「去预备 DROP」 |
| gaussdb_kernel503 | 847s | **477s** | `batch=on` |
| panwei_a | 329s | **204s** | `batch=on` |
| pg16 | 213s | **92s** | `batch=on` |

预算口径随之改为「**max(每实例预算)+8min 余量**」：步骤 120→**60min**、job 130→**70min**（并行后最坏等待是最大单实例预算，不是 Σ）。

## 二轮：T-DialectSweepSpeed2（2026-11-07 立项 `264190cf`，用户批准 1/2/3 一起做）

计划册 `docs/开发计划/20261107-0900-T-DialectSweepSpeed2-矩阵耗时二轮压缩-开发计划.md`。目标 **22.1 → 10~12min**。
关键结构变化：**关键路径变成 mysql84**（897s）＋冷启动 ≈6min，所以三个手段都对着这两处：

1. **MySQL 开 `allowMultiQueries=true`**（矩阵 URL 加参数）→ 它从 `batch=off` 变 `batch=on`，7→4 往返，897→~500s。
   ⚠️ 这条**推翻了上一轮「不为此改矩阵 URL」的定案**——现在明确要改（真实 URL 在 `.local` 与 CI secret，由主控同步；仓库侧只动 `matrix.example.properties` 说明）。
2. **单实例内词级并行**：worker 池（默认 `min(4,词数)`，`-Dmpvs.external.wordWorkers` 覆盖），**每 worker 各开一条连接**（共用不安全）；失败仍逐词记。**实例级预算/看门狗语义不变**（仍按整实例计时，P-5）——不许顺手改语义。
3. **去掉 plain INSERT**（每词那条字面量 INSERT）→ 7→6；**覆盖度变化已声明**（字面量 INSERT 不再逐词验证，`runTcSuite` 仍覆盖）。

反向验证三条：去掉 `allowMultiQueries` → 回 `batch=off` 且变慢；`wordWorkers=1` → 耗时回升；人为让某词失败 → 明细仍点名该词（不是整批）。

**已派子代理**（2026-11-07）：分支 `t-dialect-sweep-speed2` / 工作树 `.worktrees/sweep2`，与发行 `2026.11.7-beta.1` 那路并行（构建位正好 2 个）。
派活里固定的两条纪律：①真机 URL 的 `allowMultiQueries` 改动只在它自己工作树的 `.local` 副本里做验证，**仓库侧只动 `matrix.example.properties` 说明**，真实 URL 与 CI secret 由主控同步；②`InstanceBudgetSupport` 若被触碰，既有 5 个用例语义不得变（实例级预算/看门狗仍按整实例计时）。

相关：[[project-matrix-instance-timeout-20261106]]（实例级看门狗与四桶，本券并行的基础）、[[parallel-build-resource-rules]]、[[project-github-release-pipeline]]（CI 腿构成）、[[pitfall-ci-slow-runner-amplifies-time-threshold-tests]]（同轮把 extras 的时序假红也治了）。
