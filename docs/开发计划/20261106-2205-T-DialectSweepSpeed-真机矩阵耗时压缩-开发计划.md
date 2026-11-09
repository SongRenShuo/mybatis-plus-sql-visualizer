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

**分支**：`t-dialect-sweep-speed`（工作树 `.worktrees/sweep-speed`，基于 master `5c008566`）
**提交**：`fa0a63a3` 实例并发 + 减往返 + CI 预算口径（本册回填紧随其后一笔）
**写集**：与 §六 一致；`src/main` 零改动、`update/update.json` 未动。

### 1. 落地内容

- **实例并发（3.1）**：`DialectExternalMatrixVerificationTest` 由「串行 for」改为「声明期解析计划 → 固定线程池并发 → 按下标回填结局」。并发度默认 `min(实例数, 4)`，覆盖通道 `-Dmpvs.external.maxConcurrency` → 矩阵全局 `maxConcurrency` → 默认 4。每实例仍是自己的 `Connection` + `InstanceBudgetSupport` 的独立 daemon 看门狗线程（**超时语义一字未改**）。
- **summary 顺序（D-3）**：结局按下标回填后按 `instances` 声明顺序记账，新增 `assertBucketOrder`（桶内下标单调）把「不按完成顺序」钉成断言。
- **MatrixSummary**：`record` 加 `@Synchronized`（并发调用安全；落桶口径不变，既有 5 个单测全绿）。
- **去预备 DROP（3.2.1）**：表名 `kw_probe_<idx>`，只在本词 `finally` 删；`CREATE` 失败时 DROP 后重试一次（上一轮崩溃残留）。
- **多语句批处理（3.2.2）**：`probeMultiStatement` 用「CREATE+INSERT 两条 + 单独 SELECT 数行」探测——**必须读到 1 行才判支持**，防驱动在分号处静默截断造成假绿；支持时四条渲染 DML 合成一次执行（参数按面板「回填后执行」形态内联成字面量）；批处理失败则**清表重建**后逐条 `?` 绑定重跑（批内已部分生效时直接续跑会把「主键已存在」误记成该词失败），逐条通过则判为批处理形态问题 → 该词 PASS、后续词回退逐条、明细写 `batch=degraded(...)`。
- **CI 预算（P-5）**：`nightly-full.yml` 的 `dialect` job 步骤 **120→60min**、job **130→70min**；算式由「步骤 ≥ Σ(每实例预算)」改为「**max(每实例预算) + 8min**」。`T-ReleaseChecklist` 同口径，并写明压小并发度时回到 `ceil(实例数 / 并发度) × max(预算)`。
- **收尾加固两处**（反向验证过程中暴露，同券修掉）：①桶内顺序断言挪到**写完 summary 之后**——原位置在写盘前，断言一红就连当轮工件都没有（反向验证 3 第一轮就是这样只剩 JUnit 文本）；②`execBatch` 的 drain **不再吞异常**——SQL Server 形态的驱动把后续语句的错误留到 `getMoreResults()` 才抛，吞掉即假绿；抛出后走回退逐条，代价是往返、换来不漏判。

### 2. §五 待验证前提逐条结论

| 编号 | 结论 | 读数 / 依据 |
|---|---|---|
| P-1 同机争抢失真 | **不成立（未观测到系统性失真）** | 同一实例在「并发」与「串行」两种编排下的自身耗时：mysql84 95.5s / 96.1s、pg16 9.0s / 8.1s、panwei_a 28.8s / 31.2s（均在噪声内）；gaussdb_kernel503 并发 97.3s，但**单实例独跑也有 87.9s**（另一次串行轮 38.3s）——波动来自实例侧负载而非同机争抢。保留 4 并发。 |
| P-2 并发下看门狗仍独立超时 | **成立** | 预算压到 1s 复跑单实例 → `通过 0 / 跳过 0 / 超时 1 / 失败 0`，处置链 `statement.cancel✓ connection.close✓ thread.interrupt✓` 齐全，summary 含「超时桶说明」；`InstanceBudgetSupportTest` 5 例仍绿。 |
| P-3 PG 族接受多语句 / MySQL 需 allowMultiQueries | **成立，四实例都探到了** | pg16、panwei_a、gaussdb_kernel503 = `batch=on`；mysql84 = `batch=off(引擎拒绝多语句：You have an error in your SQL syntax; … near 'INSERT INTO mpv_batch_probe (id) VALUES (7)' at line 1)`——错误可读、自动回退逐条，覆盖不变（未为此改矩阵 URL）。 |
| P-4 唯一表名 + 失败回退不产生假红 | **成立** | 四实例全 PASS 且词数与基线逐字一致；「残留同名表」场景用 H2 定向探针验证（预置 `kw_probe_0` 异形表 → 首条 CREATE 必失败 → DROP 重试后该词 PASS，`tests=1 failures=0`）；批处理侧假红由反向验证 2A 挡住。 |
| P-5 预算公式改 max 口径 | **已改** | 见 §1 CI 预算；`nightly-full.yml` 与 `T-ReleaseChecklist` 两处同步，旧的「步骤必须 ≥ Σ」表述已删。 |

### 3. 读数（本机，2026-11-06 22:30~23:10，非 CI）

1. **P-3 探测**（真实一轮的 summary 明细，非单独脚本）：mysql84 `batch=off(引擎拒绝多语句：You have an error in your SQL syntax; … near 'INSERT INTO mpv_batch_probe (id) VALUES (7)' at line 1)`；pg16 / panwei_a / gaussdb_kernel503 `batch=on`。
2. **pg16 单实例前后对照**：改前 `PASS pg16 PostgreSQL words=86 11827ms` → 改后 `PASS pg16 PostgreSQL words=86 batch=on 9094ms`（−23%）。本机 RTT 低，省下的是往返次数而非等网络；跨洋环境下同样的往返数削减放大得更多。
3. **四实例子集并发一轮**（mysql84,pg16,panwei_a,gaussdb_kernel503）：任务墙钟 `dialectExternalVerify START→END = 107.4s`；实例耗时 95547 / 9006 / 28814 / 97263ms；`通过 4 / 跳过 0 / 超时 0 / 失败 0`。收尾加固（顺序断言挪位 + drain 不吞异常）后**复跑仍 4/4 PASS**：99.2 / 7.6 / 84.1 / 279.0s（该轮测试服务器负载明显偏高，见 §4 波动项）。
4. **覆盖不变**：`mysql84 words=262`、`pg16 words=86`、`panwei_a words=109 skip=1(rowid)`、`gaussdb_kernel503 words=197` —— 与压缩前基线逐字一致（无抽样、无 skipWords 变更）。
5. **四桶语义**：`-Dmpvs.external.instanceTimeout=1`（矩阵条目去掉 `timeoutSeconds`，否则实例条目优先）→ `TIMEOUT  pg16  预算 1s  处置=[statement.cancel✓ connection.close✓ thread.interrupt✓]  线程已退出：…  耗时 1074ms`，落超时桶且 summary 有「超时桶说明」，JUnit 按「超时未验证」判红。
6. **反向验证 1（并发改回串行）**：同一四实例子集 `-Dmpvs.external.maxConcurrency=1` → 实例耗时 96099 / 8125 / 31231 / 38320ms（Σ=173.7s），并发一轮 max=97.3s、墙钟 107.4s → 并发把墙钟压到「最贵那一个实例」量级。
7. **反向验证 2（去掉批处理回退）**：注入「只在批处理里出现」的缺陷（拼接末尾加 `; SELECT 1/0`）——有回退时 `PASS pg16 … batch=on batch=degraded(批处理执行失败、逐条通过，后续词回退逐条)  10604ms`（**绿**，没有词被冤判）；去掉回退后 `FAIL pg16 … 保留字扫描失败 86/86 个词: all -> ERROR: division by zero …`（**红**，86 个词全被批级错误冤判、明细与词本身无关）。
8. **反向验证 3（summary 改成完成顺序）**：用 `instances = panwei_a, pg16`（完成顺序 pg16 先）改成「完成即记账」→ 断言必红：`PASS 桶的输出顺序必须按矩阵 instances 声明顺序（不按完成顺序）：实际：[PASS pg16 …, PASS panwei_a …] 声明：[panwei_a, pg16]`。

### 4. 未完成 / 风险

- **CI 实测未做**（不 push、不 dispatch，按券内纪律）：60/70min 是按 max 口径重算的预算值，真实 CI 墙钟要等下一轮 nightly 才能读；本地 107s 与 CI 的跨洋倍率不能直接换算，`≤25min` 目标只能由 CI 读数确认。
- **`-Dmpvs.*` 与配置缓存**：本轮实证 `-Dmpvs.external.maxConcurrency=1` 在配置缓存命中时**被静默忽略**（`Configuration cache entry reused`，summary 仍写「默认=4」），需 `--no-configuration-cache` 才生效。这是 `buildSrc` 里 `System.getProperties()` 扫描在 CC 下被冻结的既有接线问题，`buildSrc` 不在本券写集，登记为后续项。
- **gaussdb_kernel503 耗时波动极大**（同一套代码：单实例独跑 38.3s / 87.9s，并发下 83.5~97.3s，收尾复跑 279.0s）：波动来自测试服务器侧负载，与并发度无稳定相关；`墙钟≈max(单项)` 只能当量级估计。预算 3000s 目前能兜住（279s 仍 PASS），但**要收紧预算前必须先按住这个波动**，否则会把负载误判成超时。
- **批处理路径不再逐词验证 `?` 绑定**（值内联成字面量）：`runTcSuite` 仍覆盖 `?` 绑定，逐条回退路径也保留绑定执行；覆盖口径的这处「形式变化」已在此登记，判据（可执行性）未变。
- **P-1 的结论只覆盖当前四实例编排**：全量 12+ 实例矩阵并发跑时，同机实例数会上升（114.66.55.245 上有 mysql84/pg16/oracle23/dm8/kingbase/opengauss 等），届时是否要按 host 分组限流需要单独实测——本券只对 CI 子集下结论。

## 八、验收

| 项 | 判据 |
|---|---|
| 覆盖不变 | 同一子集（mysql84, pg16, panwei_a, gaussdb_kernel503）全部实例 PASS、词数与 `skip=1(rowid)` 与压缩前逐字一致 |
| 耗时 | CI 实测 `dialect` 腿总耗时明显下降（目标 ≤ 25min；并行生效时应接近 mysql84 单实例耗时） |
| 四桶语义 | 用 `-Dmpvs.external.instanceTimeout=1` 复跑单实例 → 仍落 TIMEOUT 桶、summary 有「超时桶说明」 |
| 反向验证 1 | 把并发改成串行 → 耗时回升（贴两次读数） |
| 反向验证 2 | 去掉批处理回退 → 人为让中间一条语句失败时，明细丢失（必红） |
| 反向验证 3 | 把 summary 顺序改成「完成顺序」→ 顺序断言必红 |

## 九、CI 实测（2026-11-07 01:2x，run 42258160804）

压缩前后同一子集（mysql84 / pg16 / panwei_a / gaussdb_kernel503）对照：

| 项 | 压缩前（run 42230813933） | 压缩后（run 42258160804） |
|---|---|---|
| `dialect` 腿总耗时 | **43.5 min** | **21.6 min**（−50.3%） |
| mysql84 | 1219s | **897s**（−26%，`batch=off` 引擎拒绝多语句，只吃到「去预备 DROP」的收益） |
| pg16 | 213s | **92s**（−57%） |
| panwei_a | 329s | **204s**（−38%） |
| gaussdb_kernel503 | 847s | **477s**（−44%） |
| 结果 | 4/4 PASS | **4/4 PASS**（词数 262/86/109(skip=1 rowid)/197 与基线逐字一致） |

summary 里同时落了两条新元信息，便于事后对账：`实例并发：默认=4；实例 4 个 → 并发度 min(上限, 实例数) = 4`、
每实例明细行的 `batch=on / batch=off(<引擎原话>) / batch=degraded(<原因>)`。

墙钟≈max(实例)=mysql84 的 14.9min + 冷启动 ≈ 6min，与「max(预算)+余量」口径吻合；
同轮 nightly 其余 16 个 job 全绿、push-test 8/8 全绿。
