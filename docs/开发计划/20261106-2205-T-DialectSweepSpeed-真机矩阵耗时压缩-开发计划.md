# T-DialectSweepSpeed：真机矩阵耗时压缩（2026-11-06 立项）

> 用户令：**不要抽样**（覆盖口径不许降），其余按建议实施——即「实例并行」+「减往返」，批量建表那种改考题的做法不做。
> CI 现状：`dialect` 腿 2026-11-06 实测 **43.5 分钟**（mysql84 20.3 / pg16 3.5 / panwei_a 5.5 / gaussdb 14.1），四实例**串行**。

## 一、成本结构（读代码 + 对账 CI 读数，不是估算）

`sweepReservedWords`（`DialectVerifySupport.kt:343-427`）对**每个保留字**打 **8 个往返**：

```
DROP(预备) → CREATE TABLE → INSERT → SELECT(渲染) → INSERT(批量·带参) → UPDATE(渲染) → UPSERT(带参) → DROP(收尾)
```

对账：mysql84 262 词 / 1219s → ≈**0.58s/往返**（跨洋 RTT 200ms+ 与远端 DDL 开销叠加）。
而 mysql84 用「词数 × 往返」估算已接近其总耗时 ⇒ **方言套件（`runTcSuite`）占比很小**，时间几乎全在「词数 × 往返数 × RTT」。

三条抓手：**往返数、RTT、并行度**（词数被用户否掉——不抽样）。

## 二、目标 / 非目标

**目标**：同一词集、同一引擎、同一判据下，CI `dialect` 腿耗时显著下降；四桶结局与看门狗语义**一字不变**。

**非目标**：
- 不抽样、不减保留字、不改 `skipWords` 口径；
- 不做「一张表塞 N 个引号列」的批量建表（会把「表位置 vs 列位置」的考题改掉，且失败要靠 bisect 定位）；
- 不动生产代码（`src/main` 零改动）。

## 三、手段

### 3.1 实例并行（主收益，预计 −50%）

现状：单个 `@Test` 里 `for (key in keys)` 串行。改为**同 JVM 内按实例并发**：

- 每实例一个线程 + 自己的 `Connection`；实例级看门狗（`InstanceBudgetSupport`，券 A 已落地）本来就是「每实例一个 executor」，天然兼容；
- `MatrixSummary` 的写入加锁，**输出顺序按矩阵里的 `instances` 顺序**（不按完成顺序），保证 summary 可比对；
- 保留既有四条判红底线与「至少一条 PASS」；
- 并发度 = `min(instances.size, 4)`（可配），不引入 Gradle 级 `maxParallelForks` 变化。

**收益和对预算的影响**：墙钟 ≈ max(单实例) 而不是 Σ。最坏情况（每个实例都挂到各自预算）从 Σ=6600s 变成 max=1800s ⇒ `nightly-full.yml` 的步骤预算可以从 120min 降到 **50min 左右**（1800s + 8min 余量 = 38min，留抖动到 50），job 相应 60min。

### 3.2 减往返（预计 −12%~−35%，分两档）

1. **去掉预备 DROP**：每词用唯一表名 `kw_probe_<idx>`，只在 `finally` 里 DROP。为防上一轮崩溃留下的表，CREATE 失败时**回退一次**（DROP 后重试）——只在失败路径多一个往返。→ 8 → 7 往返。
2. **渲染 DML 三连批处理**（条件生效）：`SELECT` / `INSERT(批量)` / `UPDATE` / `UPSERT` 在支持多语句的引擎上合并成一次 `Statement.execute("s1; s2; s3; s4")`（PG 族通常可；MySQL 需 `allowMultiQueries`，**不为此改矩阵 URL**，探测不支持就退回逐条）。→ 支持时有参 DML 从 4 个往返降到 1，整体 7 → 4 往返（≈ −43%）。
   - **错误隔离**：批处理失败时**自动回退逐条重跑**，用逐条结果产出逐词失败明细（失败是少数，回退成本可接受）。

### 3.3 Runner 挪近网（真 10x，非代码）

self-hosted runner 放国内：RTT 200ms → 5-20ms，43.5min → 6-8min。属基础设施决策，不在本券实施范围，只在文档里登记为后续选项。

## 四、决策

| 编号 | 事项 | 定 |
|---|---|---|
| D-1 | 是否抽样 | **不抽样**（用户明确否决；覆盖口径不变） |
| D-2 | 并发度 | 默认 `min(实例数, 4)`，可用矩阵级 `maxConcurrency` 或系统属性覆盖 |
| D-3 | summary 顺序 | 按 `instances` 声明顺序，不按完成顺序 |
| D-4 | 唯一表名清理 | 每词 `finally` 里 DROP；不额外做批量清理 |
| D-5 | 多语句批处理 | 探测通过才用；失败回退逐条并产出明细；不因它改矩阵 URL |

## 五、待验证前提（先证伪）

| 编号 | 前提 | 不成立时 |
|---|---|---|
| P-1 | 四实例并发不会让任一引擎侧的测量失真（同一台服务器上不会有同库实例？当前 4 个实例分属 `114.66.55.245` 与 `103.36.220.245` 两台，可能有两个实例同机） | 若同机争抢导致耗时/超时，降到 2 并发或按 host 分组串行同机实例 |
| P-2 | 并发下看门狗仍能独立超时、不影响其他实例 | 回退串行 |
| P-3 | PG 族 JDBC 接受 `;` 多语句并能返回清晰错误；MySQL 需 `allowMultiQueries`（默认关） | 不支持就只保留 3.2.1 的 −1 往返 |
| P-4 | 唯一表名 + 失败回退重试不产生「假红」（表已存在等） | 恢复预备 DROP（回到 8 往返） |
| P-5 | 并发后 CI 预算公式改为「max(预算)+余量」，`nightly-full.yml` 与 `T-ReleaseChecklist` 同步改，别留下 Σ 的旧口径 | 保持 120min 不动，只改代码 |

## 六、写集

- `src/test/kotlin/.../sql/verify/DialectExternalMatrixVerificationTest.kt`（并发编排、summary 顺序）
- `src/test/kotlin/.../sql/verify/DialectVerifySupport.kt`（去预备 DROP + 唯一表名 + 多语句批处理与回退）
- `src/test/kotlin/.../sql/verify/InstanceBudgetSupport.kt`（如并发需要共享执行器，改动接口；否则不动）
- `.github/workflows/nightly-full.yml`（步骤/job 预算 + 注释里的算式改写为 max 口径）
- `docs/发行说明/T-ReleaseChecklist-发行检查清单.md`（同口径）
- 本册 §七 实施记录

## 七、实施记录

<!-- 待回填 -->

## 八、验收

| 项 | 判据 |
|---|---|
| 覆盖不变 | 同一子集（mysql84, pg16, panwei_a, gaussdb_kernel503）全部实例 PASS、词数与 `skip=1(rowid)` 与压缩前逐字一致 |
| 耗时 | CI 实测 `dialect` 腿总耗时明显下降（目标 ≤ 25min；并行生效时应接近 mysql84 单实例耗时） |
| 四桶语义 | 用 `-Dmpvs.external.instanceTimeout=1` 复跑单实例 → 仍落 TIMEOUT 桶、summary 有「超时桶说明」 |
| 反向验证 1 | 把并发改成串行 → 耗时回升（贴两次读数） |
| 反向验证 2 | 去掉批处理回退 → 人为让中间一条语句失败时，明细丢失（必红） |
| 反向验证 3 | 把 summary 顺序改成「完成顺序」→ 顺序断言必红 |
