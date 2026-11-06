# T-AgentSilentAfter16s：增强捕获在应用启动约 16 秒后停止产出（2026-11-06）

- 立项：2026-11-06 13:36
- 状态：**待实施**（现象已复现 4 次、候选根因已排到 2 条；尚未定位到具体代码路径）
- 目标分支：`master`
- 来源：T-IbatisPanwei 真机验收（磐维 V1 驱动 + GaussDB 503.1.0）时偶发发现——前 ~16s 事件正常，之后钩子不再产事件，但应用请求照常 200、agent 心跳照发
- 取证环境：Spring Boot 2.6.3 / JDK8 / HikariCP / `panweidb-jdbc-1.0.0.jar`（`org.panweidb.Driver`）→ GaussDB Kernel 503.1.0（`103.36.220.245:25882/xc3_test`）；假 IDE 探针 `dev-support/e2e-fake-ide`（非 GUI）

## 一、现象（4 次复现的读数）

| 轮次 | 脚本段序 | 最后一条事件 | 静默段 | 断言 |
|---|---|---|---|---|
| 1 | A(`/order/list`) → B(`/order/sum`) | 16.4s（B） | 无（探针 20.5s 收口） | ALL-PASS |
| 2 | A → A2(带参 `/order/list`) → B | 14.9s（A2） | B 在 20.4s，0 条 | FAIL 1 |
| 3 | A → B → A2（B 提到前） | 16.4s（B） | A2 在 ~21s，0 条 | FAIL 1 |
| 4 | 同 3，`-Dsqlvis.agent.nestGate=false` | 15.0s（A2，3 条） | 后续 0 条 | FAIL 1 |
| 5 | 同 3，`-Dsqlvis.agent.catchAll=false` | 16.8s | 后续 0 条 | FAIL 2 |

共同点：**截止时间落在 14.9~16.8s**；之后业务请求返回 200（SQL 确实在库里执行了）、`HEARTBEAT` 仍按时发出（有一轮 `HEARTBEAT=2`，说明 sender 线程活着）；`EVENT` 归零。

## 二、已排除

1. **NestGate**：`-Dsqlvis.agent.nestGate=false` 后现象不变（只是每个请求从 1 条变 3 条，代理层不再被抑制）。
2. **premain 的接口兜底扫描**：`-Dsqlvis.agent.catchAll=false` 后现象不变（该开关会让 `afterInstall` 直接返回，10s/70s 的补扫轮不跑）。
3. **sender 线程死亡 / socket 断开**：心跳仍在发（`HEARTBEAT` 计数在增长），探针侧无 `连接断开` 记录。
4. **探针窗口太短**：静默段的 `assert-events` 给了 5000ms，而事件截止到下一个请求之间隔了 5s 以上。

## 三、关键线索

1. **宿主 stderr 有且仅有一条登记盲区**（`logLevel=debug` 才打）：
   `织入通道登记盲区：无登记 PS 执行被跳过（SQL 不可得） psClass=org.panweidb.jdbc.PgPreparedStatement unresolvedCount=1 — 一期已知盲区（attach 之前 prepare 的 PS 无 SQL 归属）`
   注意这条日志**只打一次**（首现），`unresolvedCount` 后续增长不可见。
2. **静默点 ≈ Hikari 池预热完成之后**：应用日志里 `HikariPool-1 - Start completed` 与事件截止在同一时间窗；此前的事件里有大量 `PgStatement#executeQuery/executeUpdate`（每个新连接的 3 条初始化语句），预热结束后这类事件也一起消失。
3. **驱动侧有 prepared statement 缓存**：`preparedStatementCacheQueries` 默认 256、`preparedStatementCacheSizeMiB` 5、`prepareThreshold` 5（官方《应用程序开发指南》§4.4.5）。池预热完成后，后续查询大量命中缓存/复用语句对象。

## 四、候选根因（待证，不预设结论）

- **H1（首选）SQL 归属丢失**：`StatementRegistry` 里「语句对象 → SQL」的登记在语句 `close()` 或对象回收时被清掉；池预热后语句对象被**复用**（Hikari 池 + 驱动语句缓存），但没有重新经过 prepare 面登记 → `EXEC_CTX` 取不到 SQL → `WovenEvents` 按设计静默丢弃。与「`unresolvedCount=1` 只打一条」和「预热完成后全部静默」两点都吻合。
- **H2 快路径绕过钩子**：驱动在缓存命中后走 `executeWithFlags`/内部 `CachedQuery` 路径，而钩子只挂在 `execute()`/`executeQuery()`（Java 层入口本应仍被调用，但需要字节/栈取证排除）。
- **H3 全局门误触**：`CaptureBudget` 的 kill switch / 队列满 / transform 预算。已被 `catchAll=false` 轮部分排除（kill switch 只影响兜底），但队列满（`EventBus.offer` 静默丢弃）还需计数证实。
- **H4 与驱动无关**：只是 demo 的 Hikari 行为 + 我们的归属表在「复用语句」场景下的通用缺陷 → 其他驱动同样中招（对照实验见 §五-4）。

## 五、取证计划（先不改生产代码）

1. **驱动缓存对照**：`application-panwei-gaussdb.yml` 加 `?preparedStatementCacheQueries=0&prepareThreshold=0` 变体，跑同一脚本 → 静默点消失/后移即指向 H1/H2 的缓存路径。
2. **登记命中率探针**（临时，按 RED PROBE 纪律，跑完回滚）：在 `StatementRegistry.get` 的 miss 分支与 `WovenEvents.emit` 的三个早退分支（`sql==null` / NestGate 抑制 / `EventBus.offer` 失败）各加一个计数器，随心跳 body 带出（复用 `unreg` 同款键）；`unresolved` 日志从「只打一条」改为限频。
3. **线程转储**：静默发生后 `jstack` 取一次，确认业务请求线程是否进入过已织 `execute`（区分「没进方法」与「进了但早退」）。
4. **跨驱动对照**：同一个探针脚本换成 demo 的 `gaussdb` profile（华为原生驱动 + `jdbc:gaussdb:`）跑一轮 → 判断是否磐维驱动特有；再与 join-demo/mysql 脚本对照（现有脚本 20s 内收口，本来就照不到这个窗口）。

## 六、修复方向（证后定，先登记不实施）

- H1 成立：语句 `close()` 不立即清登记（弱引用 + 延迟清），或 `EXEC_CTX` 取不到 SQL 时按「连接 + 最近 prepare 的 SQL」兜底——兜底必须带守卫（同连接多语句并发会错配，需按语句对象身份而非顺序）。
- H2 成立：把钩子补挂到快路径方法（`executeWithFlags`），或在 prepare 面把 SQL 写进语句对象的外部弱表（transformer 不改成员的红线内只能外部表）。
- H3 成立：把丢弃原因计数化（`queueFull` / `killed` / `budget`），先可见再谈恢复。

## 七、验收

1. 恢复三段脚本（A 无参 / A2 带参 / B 分组，总时长 > 30s）**全程 ALL-PASS**，静默点消失；
2. 反向验证：摘掉修复分支 → 对应断言必红（贴两次读数）；
3. 跨驱动对照轮（§五-4）仍全绿，确认修复不是「把磐维特判了」。

## 八、待验证前提（P）

- **P-1** 静默点是否严格绑定「池预热完成」而非固定 16s（加大连接池 / 关缓存两种对照各一轮）。
- **P-2** `unresolvedCount` 在静默段的真实增长量（需 §五-2 的计数）。
- **P-3** 该现象在 plugin 现网（非 demo）是否同样存在——需要一次带 `logLevel=debug` 的长跑真机。
- **P-4** 是否与 `-javaagent`（premain）有关：改走 agentmain attach 路径对照一轮。

## 九、口径

现象出现在「磐维 V1 驱动 + GaussDB 503.1.0」的验收轮，但**没有任何证据表明它是磐维特有的**——在 §五-4 跨驱动对照完成前，不得写成「磐维驱动缺陷」；对外只说「增强捕获在应用启动约 16s 后静默（待查）」。
