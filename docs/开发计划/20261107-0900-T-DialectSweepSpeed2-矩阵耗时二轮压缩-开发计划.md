# T-DialectSweepSpeed2：矩阵耗时二轮压缩（MySQL 多语句 + 词级并行 + 减语句）

> 用户令（2026-11-07）：把上一轮列出的「1 和 2 和 3」一起立项派活。
> 上一轮成果（`T-DialectSweepSpeed`）：`dialect` 腿 **43.5min → 22.1min**（实例并发 + 去预备 DROP + PG 族批处理）。
> 本轮目标：**22.1min → 10~12min**，且**保留字覆盖与判据不变**。

## 一、当前成本结构（2026-11-07 run 42315646150 实测）

| 实例 | 耗时 | 批处理 | 说明 |
|---|---|---|---|
| **mysql84** | **897s（14.9min）** | `batch=off` | **关键路径**：并发 4 后墙钟≈最慢实例 |
| gaussdb_kernel503 | 477s | `batch=on` | |
| panwei_a | 204s | `batch=on` | |
| pg16 | 92s | `batch=on` | |
| 冷启动 | ≈6min | — | checkout + gradle 配置 + 平台缓存恢复 |

每词固定开销：`CREATE → INSERT → SELECT → INSERT(批量·带参) → UPDATE → UPSERT → DROP` = **7 个往返**（PG 族批处理后实际 4 个；MySQL 因拒绝多语句仍是 7 个）。

## 二、三个手段

### 2.1 MySQL 开多语句（最大单点收益）

`mysql84` 现在 `batch=off`（引擎原话：`You have an error in your SQL syntax; … near 'INSERT INTO mpv_batch_probe (id) VALUES (7)'`）。JDBC 默认不允许多语句，需 URL 参数 `allowMultiQueries=true`。

- 改法：`.local/dialect-external-matrix.properties` 的 `mysql84.url` 追加 `?allowMultiQueries=true`（若已有参数用 `&`），并同步 CI secret（主控做）；`matrix.example.properties` 补一行说明。
- 预期：mysql84 7 → 4 往返，**897s → ~500s**；总墙钟 22.1 → ~15min。
- 风险：低。批处理失败已有「回退逐条 + 逐词明细」路径，语义不变。

### 2.2 单实例内词级并行

现在并行粒度只到「实例」。把单实例的词表切成 N 份并发跑（每词已是唯一表名 `kw_probe_<idx>`，天然可并行）。

- 改法：`sweepReservedWords` 增加 worker 池（默认 `min(4, 词数)`，可配 `mpvs.external.wordWorkers`）；每 worker 独立 `Statement`/连接？**共用同一 Connection 不安全**——每 worker 各开一条连接（同一实例）。
- 预期：mysql84 897 → **~250-300s**；gaussdb 477 → ~150s；总墙钟 **~15min → ~10min**（下限受冷启动 6min 约束）。
- 风险：中。要处理：①同库并发 DDL 争用（唯一表名已隔离）；②失败归属（每词仍独立记 `word -> 错误`，只是汇总加锁）；③**预算语义**：实例级预算/看门狗现在是「实例整体」，词级并行后仍是整实例计时，**不改预算口径**（避免二次改语义）；④连接数上限（MySQL 默认 151，4 worker × 1 实例无压力）。

### 2.3 减语句：去掉 plain INSERT

每词现在有两条 INSERT：`INSERT`（字面量值）与 `INSERT(批量·带参)`。前者验「渲染出的字面量 INSERT 可执行」，后者验「`?` 绑定路径」。批处理里两者合并成一条多语句执行，收益是 7 → 6。

- 改法：删掉 plain INSERT 那一步；在计划册与 summary 里注明「字面量 INSERT 渲染不再逐词验证，`runTcSuite` 仍覆盖 INSERT 渲染」。
- 预期：约 −14%（仅对 batch=off 的 MySQL 有实际收益）。
- 风险：**覆盖度变化**，必须在文档里写明（用户已同意）。

## 三、待验证前提

| 编号 | 前提 | 不成立时 |
|---|---|---|
| P-1 | `allowMultiQueries=true` 真让 MySQL 接受 `;` 多语句，且失败时错误可读 | 保持 `batch=off`，只靠 2.2/2.3 |
| P-2 | 词级并行不会让同一实例的 DDL 互相阻塞到超时（唯一表名已隔离） | 降 worker 数到 2，或按「每 50 词一批」串行 |
| P-3 | 词级并行下失败归属仍逐词可读（不是整批失败） | 失败时回退该 worker 的串行重跑以定位 |
| P-4 | 去掉 plain INSERT 后覆盖口径可接受（文档已声明） | 恢复该步 |
| P-5 | 实例级预算/看门狗语义**不变**（仍是整实例计时） | 若必须改，单独一券，不混在本轮 |

## 四、写集

- `src/test/kotlin/.../sql/verify/DialectVerifySupport.kt`（词级并行 + 去 plain INSERT + 批处理探测）
- `src/test/kotlin/.../sql/verify/DialectExternalMatrixVerificationTest.kt`（worker 数解析、summary 元信息、并发度说明）
- `src/test/kotlin/.../sql/verify/InstanceBudgetSupport.kt`（仅在确有必要时改，且不得改变既有 5 用例语义）
- `dev-support/dialect-external/matrix.example.properties`（`allowMultiQueries` 说明 + worker 配置）
- `.github/workflows/nightly-full.yml` + `docs/发行说明/T-ReleaseChecklist-发行检查清单.md`（预算/耗时口径按新读数重算）
- 本册 §五 实施记录

## 五、实施记录

实施于 2026-11-07，分支 `t-dialect-sweep-speed2`。代码提交 `890b6154`（词级并行 + 种子并入批处理），
文档提交见 §五末。`src/main` 零改动。

### 5.1 三个手段的落地形态

| 手段 | 落地 | 与计划的差异 |
|---|---|---|
| 2.1 MySQL 多语句 | 矩阵 URL 加 `allowMultiQueries=true`（主控同步 `.local` 与 CI secret）；仓库侧只在 `matrix.example.properties` 写说明 | 与计划一致 |
| 2.2 词级并行 | `sweepReservedWords` 加 worker 池：默认 `min(4, 词数)`，`-Dmpvs.external.wordWorkers` / 矩阵全局 `wordWorkers` 覆盖；**每 worker 一条独立连接**（worker 0 复用套件连接，其余由测试侧工厂新开并发布给看门狗）；实例级预算/看门狗语义未动 | 与计划一致；另加：看门狗句柄从「单连接单语句」改为「多连接多语句」，超时处置（cancel → close）覆盖并行连接 |
| 2.3 去 plain INSERT | **计划前提不成立**（见 P-4）：代码里没有「插件渲染的字面量 INSERT」，只有夹具种子 INSERT。改为**把种子并入批处理**——省掉同一个往返，覆盖不减 | 计划写「删掉该步 + 声明覆盖变化」；实际是「并入批处理 + 覆盖不变」 |

### 5.2 待验证前提逐条结论

| 编号 | 结论 | 证据 |
|---|---|---|
| P-1 | **成立** | 加参数后 mysql84 明细行 `batch=on`；不加时 `batch=off(引擎拒绝多语句：You have an error in your SQL syntax; ... near 'INSERT INTO mpv_batch_probe (id) VALUES (7)' at line 1)`（引擎原话可读） |
| P-2 | **成立（本机）** | 6 轮真机跑（4 实例 × 4 worker）零失败零超时，无 DDL 互锁；但 PG 族在本机**没有提速**——gaussdb/panwei 容器同跑在 2 核测试机上，服务端已饱和（读数见 5.3） |
| P-3 | **成立** | 临时注入坏 SQL（`word == "select"` 追加 `SELECT * FROM mpv_inject_no_such_table`）→ 明细 `保留字扫描失败 1/262 个词: select -> Table 'dbx_test.mpv_inject_no_such_table' doesn't exist`，其余 261 词照常 PASS；探针已删除（`git diff` 无残留） |
| P-4 | **不成立（前提误读）** | 「字面量 INSERT」是**夹具种子**（`INSERT INTO kw_probe_i (id, <kw>) VALUES (1,1)`，手写、非渲染产物），且是 UPSERT 的 id=1 **冲突分支**的前提：删掉它，upsert 只剩插入分支，词数与 4/4 PASS 都不变——覆盖缺口不会被验收判据发现。故改为并入批处理：往返收益相同（batch=on 时 4 → 3），覆盖零损失 |
| P-5 | **成立** | 计时仍包住整实例（`InstanceBudgetSupport.runWithBudget` 未改，词并行只在 task 内部）；`instanceTimeout=1`（矩阵不写 `timeoutSeconds`）仍落 `通过 0 / 跳过 0 / 超时 1 / 失败 0`，处置串 `statement.cancel✗(超时点无正在执行的语句) connection.close✓ thread.interrupt✓` |

### 5.3 耗时读数（本机，四实例子集，实例并发 4）

同一份代码，四组配置（墙钟 = 该实例自身耗时，来自 summary 明细行）：

| 实例 | A 改前口径<br>(batch=off, worker=1) | B 全开<br>(batch=on, worker=4) | C 只并行<br>(batch=off, worker=4) | D 只批处理<br>(batch=on, worker=1) |
|---|---|---|---|---|
| mysql84（262 词） | **87.15s** | **20.27s** | 27.26s | 61.74s |
| pg16（86 词） | 7.11s | 3.75s | 3.31s | 8.76s |
| panwei_a（109 词，skip=1 rowid） | 23.55s | 23.34s | 23.21s | 47.55s |
| gaussdb_kernel503（197 词） | 75.94s | 78.84s | 40.50s | 106.51s |

- mysql84：**4.3×**（87.15 → 20.27s），两因素可分离——批处理 1.4×（87.15 → 61.74）× 词并行 3.05×（61.74 → 20.27）。
  全开配置三次读数 **20.27 / 20.16 / 20.45s**（B / 仅 mysql84 复跑 / B 重跑），复现性在 ±1.5% 内。
- PG 族读数被**同机争用**污染：gaussdb 全开三次 78.84 / 40.50 / 57.93s，worker=1 两次 75.94 / 106.51s——
  同配置相差 40% 以上，方向是「并行不亏」，但本机测不出真实收益：gaussdb/panwei 跑在同一台 2 核 16G
  测试机上，4 worker 只换来排队。panwei_a 全开 23.34 / 23.21 / 19.47s，worker=1 23.55 / 47.55s。
- **结论口径**：本机读数是「往返压缩 + 并行机制正确」的证据，不是 CI 收益的度量。CI 的收益来自跨洋 RTT
  （200ms × 每词 3 个往返 × 4 路并行），投影见 5.6。

### 5.4 覆盖不变（四轮全部一致）

`words=262 / 86 / 109(skip=1 rowid) / 197`，`通过 4 / 跳过 0 / 超时 0 / 失败 0`（A/B/C/D 四轮逐字相同）。
summary 新增一行覆盖口径说明：「字面量 INSERT 是夹具种子（并入批处理，不是渲染验证项），INSERT 渲染由
`runTcSuite` 覆盖」。

### 5.5 三条反向验证

| 验证 | 读数 | 判定 |
|---|---|---|
| ① 去掉 `allowMultiQueries` | mysql84：20.27s（on）→ **27.26s**（off），明细行回到 `batch=off(...)` | 通过（+34%，且 `batch=off` 原话在场） |
| ② `-Dmpvs.external.wordWorkers=1` | mysql84：20.27s（4 worker）→ **61.74s**（1 worker），3.05× | 通过 |
| ③ 人为让某词失败 | `1/262 个词: select -> Table '...mpv_inject_no_such_table' doesn't exist`，其余 261 词 PASS | 通过（逐词归属，不是整批） |

### 5.6 CI 预算重算

旧值：步骤 60min / job 70min（max 预算 3000s = 50min + 8min）。
新投影（run 42315646150 实测 897/477/204/92s 按往返数折算）：mysql84 `897×3/7÷4 ≈ 96s`、
gaussdb `477÷4 ≈ 120s`、panwei_a `204÷4 ≈ 51s`、pg16 `92÷4 ≈ 23s` ⇒ 墙钟 ≈ max ≈ 2min；
全腿 ≈ 冷启动 6min + 实例启动 1min + 2min ≈ **9min**（目标 10~12min）。
预算按**悲观口径**（假设词并行在 CI 一点不省）取：mysql84 385s→900s、gaussdb 477s→900s、
panwei_a 204s→600s、pg16 92s→300s ⇒ max = 900s = 15min → 步骤 = 15 + 8 = 23 → **25min**，job **35min**。
⚠️ 前提：secret 里的实例预算须同步下调为 ≤900s；未同步时 max 仍是 3000s，25min 步骤会被 CI 掐断
（丢 summary 工件）。workflow 与发行检查清单都写了这条前置。

### 5.7 未完成项与风险

- **CI 实测未取**：本轮只有本机读数。`dialect` 腿 ≤ 15min 的验收判据要等一次真实 dispatch 才能落账；
  在此之前「CI 已覆盖 10~12min」不得写进发行说明。
- **secret 未同步**（助手无权）：`DIALECT_MATRIX_PROPERTIES` 需含 `allowMultiQueries=true` 与下调后的
  `timeoutSeconds`；`.local/dialect-external-matrix.properties` 同样由主控改。
- **PG 族并行收益未证实**：本机被 2 核测试机饱和掩盖。若 CI 上 PG 族也不提速，墙钟由 gaussdb 的
  `477÷?` 决定——悲观情形仍是 8min 级，步骤 25min 够用。
- **`-Dmpvs.external.wordWorkers` 透传**：需要 `buildSrc/.../mpv-testing.gradle.kts` 的白名单加一行
  （已加；该文件不在原写集内，属必要偏离——不加则 `-D` 被 Gradle 吞掉，反向验证②无法执行）。
- 词级并行默认 4 是**连接数**承诺：实例侧若限制连接数（如 PG 的 `max_connections` 很小），需用
  `wordWorkers` 调小；矩阵说明里已写。


## 六、验收

| 项 | 判据 |
|---|---|
| 覆盖不变 | 四实例 `words=262 / 86 / 109(skip=1 rowid) / 197` 与基线逐字一致，4/4 PASS |
| 耗时 | CI 实测 `dialect` 腿 ≤ 15min（目标 10~12min）；mysql84 明细行 `batch=on` |
| 语义 | `-Dmpvs.external.instanceTimeout=1` 单实例仍落 TIMEOUT 桶（四桶未变） |
| 反向验证 1 | 去掉 `allowMultiQueries` → mysql84 回到 `batch=off` 且耗时回升（两次读数） |
| 反向验证 2 | worker 数置 1 → 耗时回升到接近当前值（两次读数） |
| 反向验证 3 | 词级并行下人为让某词失败 → 失败明细仍点名该词（不是整批） |
