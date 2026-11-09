# T-AgentSilentAfter16s：增强捕获在应用启动约 16 秒后停止产出（2026-11-06）

- 立项：2026-11-06 13:36
- 状态：**已实施**（2026-11-06，分支 `t-agent-silent16s`；根因＝H3 家族的 **T-07 CONFIG 看门狗**，H1/H2 证伪——取证与修复读数见 §十）
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

## 十、实施记录（2026-11-06，分支 `t-agent-silent16s`）

### 1. 取证做法与读数（§五 的取舍）

§五-2 的临时计数器**没有启用**。理由：决定性证据本来就在，而且更便宜——T-07 看门狗在宿主 stderr 打的那一行是**带原因**的，基线轮的时间线又精确对上「HELLO + 15000ms」。加 RED PROBE 只会多烧一轮 e2e，还留下必须删净的临时计数器（漏删即污染生产）。`unresolved` 日志限频同样没做：它不是本次根因的观察面。§五-3 的 jstack 也不需要——问题不在「进没进已织 execute」，请求线程从未被阻断。

基线轮（`script-silent-window.txt` 当时**没有** CONFIG 握手；磐维 V1 驱动 + GaussDB 503.1.0）：

| 读数 | 值 |
|---|---|
| HELLO | +4170ms |
| 末条事件 | +19106ms（= 4170 + 15000 的下一轮轮询） |
| A 窗口（约 +16~21s） | 30 条，PASS |
| A2（+24.9s 请求）/ B（+34.0s）/ C（+43.1s） | 各 0 条，三段 FAIL |
| `CONFIG_ACK` | 0（探针从未下发 CONFIG） |
| app.log | 有且仅有一条 `[T-AgentConfigChannel] 会话建立后 15000ms 内未收到任何 CONFIG，按保守策略主动停采…` |
| 断言 | PASS=7 FAIL=3，RESULT: FAILED |

对照轮（**唯一变量**：补 `config {"disabled":false,…}` + `expect-ack`）：四窗口 21.5 / 30.8 / 40.0 / 49.0s 分别 31 / 2 / 1 / 1 条，PASS=12 FAIL=0，ALL-PASS，app.log 零条 `T-AgentConfigChannel`。

结论：

- **H1（SQL 归属丢失）证伪**：15s 之后业务请求照旧 200（语句真在执行），补一次 CONFIG 后同一批 `PgPreparedStatement` 继续产事件；`unresolvedCount` 只在首轮出现一次，静默段没有新增——静默与登记表无关。
- **H2（快路径绕过钩子）证伪**：补 CONFIG 后事件仍从 `PgPreparedStatement#execute` 织入点产出，定位器 primary 与基线逐值一致。
- **H3 成立，但闸门是计划没列到的第四种**：不是 kill switch / 队列满 / transform 预算，而是 `SenderLoop` 的 T-07 看门狗——**会话建立后 15s 内从未收到任何 CONFIG → `AgentState.setDisabled(true)`**（全局、进程内一直保持，直到有 CONFIG 进来）。它按设计工作（保守停采），但现场**无法从线上区分「agent 自停」与「钩子不产」**：事件归零、心跳照发、请求 200，与 H1/H2 的形态完全同形，四轮排查都撞在这上面。
- **P-1 不成立**：静默点与池预热无因果，严格等于 HELLO+15s。
- **P-3 依据在代码**：真 IDE 两条会话建立路径（`AgentManager.attachToPid`、`onPremainConnected`）都在建会话后立刻 `sendConfig`，故现网不会常态命中；但「CONFIG 下发失败」这一档存在（`runCatching` 只记 WARN），届时现网表现成同款静默——这正是把自停搬上心跳的理由。
- **P-4 未单独验**：看门狗在 `SenderLoop`，premain/attach 共用同一条发送线程，与注入方式无关，不另跑对照。
- **§五-4 跨驱动对照**：静态上与本现象无关（闸门在任何 JDBC 代码之前）；仍按 §七-3 跑一轮华为原生驱动（`DEMO_PROFILE=gaussdb` + `jdbc:gaussdb:`）的同脚本验收，读数见 §十.4。

### 2. 修复（agent 侧最小改动）

- `AgentState`：新增自停归因（`GATE_NONE=0` / `GATE_CONFIG_WATCHDOG=1`）、`setDisabledByWatchdog()`、`gateCode()`；**任何显式开关（CONFIG / 测试缝）清归因**——用户自己关的采集不得被心跳报成自停。
- `SenderLoop`：看门狗改走 `setDisabledByWatchdog()`；心跳 body 追加 `"gate":<0|1>`（键序固定、无空格、恒出现；旧 IDE 的宽容扫描零影响）；看门狗告警行带出 `gate` 与「下发任一 CONFIG 即解除」。
- 红线：不改采集钩子的返回值/对象身份，不新增可配置面，异常路径保持原样自吞；`gate` 只读一个 volatile int，心跳异常仍回落 `"{}"`。

### 3. 先红后绿与反向验证

| 步骤 | 读数 |
|---|---|
| 先写失败用例（`ConfigApplyEndToEndTest.看门狗_…` 追加 gate=1 断言），生产代码未动 | 7 tests / **1 failed**：`看门狗自停后心跳须带 gate=1（否则对端只能看到「静默」…）` |
| 实现后定向（Config/Heartbeat*/SenderLoop* 五类） | 22 tests / **0 failed** |
| 反向验证：摘掉 `heartbeatBody` 的 `"gate"` 段 | 12 tests / **3 failed**（`ConfigApplyEndToEndTest.看门狗_…`、`HeartbeatDiagnosticsTest.心跳body_自停归因恒带出且随CONFIG恢复归零`、`HeartbeatDiagnosticsTest.心跳body_主动停采不报自停归因`） |
| 还原 `"gate"` 段后复跑 | 22 tests / **0 failed** |

### 4. e2e 验收读数

见 §十.4 表（三行：主验收轮 / 负对照轮 / 跨驱动轮）。

| 轮次 | 驱动 / profile | 脚本 | 各窗口事件数 | 断言 | app.log 看门狗行 |
|---|---|---|---|---|---|
| **主验收（修复后）** | 磐维 V1 + GaussDB 503.1.0（`panwei-gaussdb`） | `script-silent-window.txt`（含 CONFIG 握手） | 30 / 2 / 1 / 1（@19.7 / 28.9 / 38.1 / 47.2s） | **PASS=12 FAIL=0，ALL-PASS**，总长 47.2s，无静默段 | 0 |
| 负对照（修复后仍不发 CONFIG，脚本不入库） | 同上 | `dev-support/e2e-fake-ide/build/script-nocfg-gate.txt` | 业务请求窗口 0 条（预期自停） | FAIL 1（预期：自停仍成立） | 1；心跳 `gate` 由 #1/#2 的 0 → #3（HELLO+21.5s）起 **1** |
| 跨驱动（修复后） | 华为原生驱动 + `jdbc:gaussdb:`（`gaussdb`） | `script-silent-window.txt` | 33 / 4 / 3 / 3（@24.3 / 33.4 / 42.5 / 51.7s） | **PASS=12 FAIL=0，ALL-PASS**，总长 51.7s | 0 |

负对照那一行是本修复的验收要点：同样的「不发 CONFIG」形态，修复前对端只能看到 0 条事件；修复后心跳一帧就写着 `"gate":1`。

主验收轮连跑两次（改动前一次 31 / 2 / 1 / 1 @21.5 / 30.8 / 40.0 / 49.0s、提交后按提交树重构建 agent jar 再一次为表内读数），两次都是 PASS=12 FAIL=0、零条看门狗行——读数稳定，不靠单次运气。

### 5. 未完成与风险

- 本修复**只让自停可见，不改变「没有 CONFIG 就停采」的既有设计**：T-07 的红线是有意为之（不知道用户是否要采 → 不采）。因此 `script-silent-window.txt` 必须像真 IDE 一样先下发 CONFIG，否则测到的仍是自停。
- IDE 侧尚未消费 `gate`。可后续在 `AgentAttachStats` 加该键，并在「gate=1」时重发一次 CONFIG，把恢复也自动化——跨侧改动，本任务口径是 agent 侧最小改动，未做。
- 「CONFIG 下发失败」这条现网路径没有做端到端演练（需人为让 `sendConfig` 抛错），留作下一批。

### 6. 同批修正的误归因

- `dev-support/e2e-fake-ide/README.md`「已知边界」原写「指向 Hikari 池预热完成后 SQL 归属取不到」——证伪，已改写为看门狗自停 + 探针必须先下发 CONFIG。
- `dev-support/e2e-fake-ide/script-panwei-gaussdb.txt` 补上 CONFIG 握手（原脚本没有，正是这轮误判的来源）。
