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

<!-- 待回填 -->

## 六、验收

| 项 | 判据 |
|---|---|
| 覆盖不变 | 四实例 `words=262 / 86 / 109(skip=1 rowid) / 197` 与基线逐字一致，4/4 PASS |
| 耗时 | CI 实测 `dialect` 腿 ≤ 15min（目标 10~12min）；mysql84 明细行 `batch=on` |
| 语义 | `-Dmpvs.external.instanceTimeout=1` 单实例仍落 TIMEOUT 桶（四桶未变） |
| 反向验证 1 | 去掉 `allowMultiQueries` → mysql84 回到 `batch=off` 且耗时回升（两次读数） |
| 反向验证 2 | worker 数置 1 → 耗时回升到接近当前值（两次读数） |
| 反向验证 3 | 词级并行下人为让某词失败 → 失败明细仍点名该词（不是整批） |
