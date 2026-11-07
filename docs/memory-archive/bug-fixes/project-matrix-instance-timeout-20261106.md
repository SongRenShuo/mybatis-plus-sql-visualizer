---
name: project-matrix-instance-timeout-20261106
description: T-MatrixInstanceTimeout（20261106 已合 master）——dialectExternalVerify 原本只有登录超时，实例挂在 socket 读就整轮无限期停；券 A 加实例级看门狗（四桶 PASS/SKIP/TIMEOUT/FAIL、预算优先级 timeoutSeconds>-D>300s、daemon 线程），券 B 把 CI 方言腿子集扩到 4 实例并重算预算（120/130min）；含 secret 无 gh CLI 的写入法、-D 透传缺线的修复与真机读数
metadata:
  node_type: memory
  type: project
  originSessionId: sess_33620d7f-a4ec-4f92-8fbe-dcd146afc43f
---

# T-MatrixInstanceTimeout（2026-11-06 立项并合 master）

计划册 `docs/开发计划/20261106-1757-T-MatrixInstanceTimeout-矩阵实例级超时与CI方言腿扩容-开发计划.md`（含 §十二 合流订正）。

## 问题

`DialectExternalMatrixVerificationTest` 只有 `DriverManager.setLoginTimeout(20)`（**只管登录**）；实例挂登录之后的语句读时既不抛也不返回，逐实例 `try/catch` 永不进入 → 整轮无限期停住。2026-11-06 三次实测停下并人工杀：`vastbase` / `sqlserver2022` / `panwei_a`（jstack 全停在驱动 `PGStream.receiveChar` / `TDSChannel.read` → `SocketDispatcher.read0`）；磐维实例 `pg_stat_activity` 无长事务无锁等待 ⇒ 实例/驱动侧读挂起。**强杀还连带把共享 Gradle transform 删成半截**，见 [[pitfall-gradle-transform-partial-delete]] / [[pitfall-gradle-cache-transform-locked-build]]。

## 券 A 的落地与读数（`t-matrix-instance-timeout`，合入 `3e913cab`）

- 支撑类 `sql/verify/InstanceBudgetSupport.kt`；结局四桶 **PASS / SKIP / TIMEOUT / FAIL**，summary 行 `通过 X / 跳过 Y / 超时 Z / 失败 W`，超时行带实例 key + 预算值。
- 预算优先级：实例条目 `timeoutSeconds` → `-Dmpvs.external.instanceTimeout`（秒）→ 默认 **300s**。
- 超时处置顺序 `statement.cancel()` → `connection.close()` → `thread.interrupt()`，仍不结束则标 daemon 放弃；`optional=true` 是**唯一**降级通道（行写作 `SKIP(超时)`）。
- 读数：单测 5/0/0/0；反向（摘掉 `future.get(budget)` 的有参重载）3 红；真机 1s 预算 → `TIMEOUT panwei_a … 2055ms`（处置 `connection.close✗` 因为挂在登录握手）；真机 15s → `处置=[statement.cancel✓ connection.close✓ thread.interrupt✓]`，**close 真的打断了 worker 的 socket 读**。
- 平台事实（P1/P3）：pgjdbc 42.7.1 `PgConnection.close()` → `PGStream.close()` → `Socket.close()`；而 **`PgStatement.cancel()` 只在 `IN_QUERY` 时发 CancelRequest、从不关本地 socket**（服务端沉默时本地读照旧阻塞）→ 真正收口靠 close，cancel 只留作「试过且无效」的证据。
- 本机单实例耗时（默认 300s 预算）：panwei_a 68.9s / panwei_b 167.3s / gaussdb_kernel503 201.6s / mysql84 103.0s（pg16 ≈60s）。

## 券 B 的落地与合流订正（`t-ci-dialect-keys`，合入 `b330f9ab` + `8eb28fda`）

- `nightly-full.yml` 的 `dialect` 腿 `CI_DIALECT_KEYS`：`mysql84,pg16` → **`mysql84,pg16,panwei_a,gaussdb_kernel503`**（**砍掉 panwei_b**：262 词、CI 投影 ≈30min，与 mysql84 同族重复；再吃紧砍序 gaussdb → panwei_a）。
- 预算：步骤 40→**120min**、job 50→**130min**。算式 `步骤 ≥ Σ(每实例预算) + 8min 冷启动`——必须 ≥ Σ，否则实例级判红会退化成 CI 掐进程（无 summary、分不清超时与崩溃）。
- 取数用**本机实测 × 跨洋倍率**：mysql84 锚点 1116s(CI run#9) ÷ 103.0s(本机) ≈ **10.8×**；CI 投影 mysql84 18.6 / pg16 3.3 / panwei_a ≈12.4 / gaussdb ≈36.3 ≈ 70.6min；Σ 上界 6600s = 110min。
- 本机矩阵（`.local/dialect-external-matrix.properties`，**同时是 secret 的来源**）：`instances` 补 panwei_a/panwei_b（此前条目在文件里但不在列表 ⇒ **本地 releaseGate 其实不跑磐维**，口径缺口已补）；每实例显式预算 mysql84 1800 / pg16 600 / panwei_a 1200 / gaussdb 3000 / panwei_b 2400 / 其余 600。

## 两个操作知识（下次直接用）

1. **`-Dmpvs.external.instanceTimeout` 曾完全没接线**：`buildSrc/src/main/kotlin/mpv-testing.gradle.kts` 的 `-Dmpvs.*` 透传只挂 `tasks.test`，`dialectExternalVerify` 没有 → 计划册/模板写的系统属性通道是假文档。修法＝在该任务的 configure 块补同一段扫描（放两条显式 `systemProperty` 之前）。实证：`-D…=1` → summary 写「默认 1s（系统属性…）」，pg16 落 TIMEOUT 1079ms。
2. **本机没有 `gh` CLI，写 GitHub secret 走 REST + pynacl**：`GET /repos/{o}/{r}/actions/secrets/public-key` → `nacl.public.SealedBox(PublicKey(key,Base64Encoder())).encrypt(内容)` → base64 放进 `PUT …/secrets/DIALECT_MATRIX_PROPERTIES` 的 `encrypted_value`（204 成功）；回读 `GET …/secrets/{name}` 的 `updated_at` 作为落地判据。触发 workflow 用 `POST /repos/{o}/{r}/actions/workflows/<file>/dispatches`（body `{"ref":"master"}`，204）。

## 状态（2026-11-06 收口：CI 已实证覆盖）

- 已合 master 并推双仓（`8eb28fda`）。
- **nightly-full run 42205858372 的 `dialect` 腿实证通过**。日志：「方言矩阵已落盘（子集 mysql84,pg16,panwei_a,gaussdb_kernel503；原矩阵 15 个实例中取 4 个）」。工件 `dialect-verify-summary` 的 `summary.md` 原文：

```
通过 4 / 跳过 0 / 超时 0 / 失败 0
PASS  mysql84            MySQL       words=262                1218639ms (20.3min)
PASS  pg16               PostgreSQL  words=86                  212903ms ( 3.5min)
PASS  panwei_a           Oracle      words=109 skip=1(rowid)   328518ms ( 5.5min)
PASS  gaussdb_kernel503  GaussDB     words=197                 846993ms (14.1min)
```

  整腿 43.5min（步骤预算 120min），四个实例预算 1800/600/1200/3000 全部按矩阵生效。实际比投影 70.6min 快（磐维 A 5.5min vs 投影 12.4min）⇒ **「CI 覆盖磐维 + GaussDB」成立，销案**。
- 该 run 判 failure 的**唯一红点**是 `fast (1)` 的 `GuardSelfTestCoverageMetaGuardTest`：「磁盘上存在未登记的守卫类 → [convention/DynamicEpComplianceGuardTest.kt]」。修法＝在 `GUARD_CLASSES` 登记（`decisionFns` 留空 + note 写明判定内核与灵敏度读数），提交 `9473a038`，本地 8/0/0/0。形态与 [[pitfall-new-capability-hits-repo-wide-convention-guards]] 的「元守卫登记」同族，**新增任何文件名含 Guard 的测试类都要先登记**。
- 未做：`docs/架构设计/03-开发与测试指南.md` 的 `sql/verify` 测试类计数（现应 10 个）。

相关：[[pitfall-gradle-transform-partial-delete]]（强杀导致 transform 半截）、[[reference-local-update-channel-testing]]、[[project-dynamic-unload-barriers]]、[[parallel-build-resource-rules]]。
