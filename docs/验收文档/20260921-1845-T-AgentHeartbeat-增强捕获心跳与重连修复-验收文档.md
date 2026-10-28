# T-AgentHeartbeat 增强捕获 30 秒必掉线与诊断行换行 — 验收文档

- 日期：2026-09-21 18:45
- 任务编号：T-AgentHeartbeat（T-01 心跳节奏 / T-02 跨侧守卫 / T-03 premain 重连 / T-04 诊断行换行 / T-05 状态行真实性 / T-06 子类加载器静默失效）
- 来源：用户反馈「我通过沙盒运行，sql 日志没有正常捕获到，你分析下沙盒日志看看什么原因并生成开发计划文件，还有这个提示信息可以适当换行」→ 立项 `docs/开发计划/20260921-1630-T-AgentHeartbeat-增强捕获30秒必掉线与诊断行换行-开发计划.md`；随后用户指令「`$sam` 使用 git 工作树开多个分支和目录并行实施，本次不受并发智能体数量限制」
- 交付形态：`master` 自 `5b7ec4c`（立项提交）起，并入 5 条并行分支 + 1 条复核整改提交，末笔 `c37b9d2`

---

## 一、根因（实测钉死，非推断）

**增强捕获会话每次都在建立后恰好 30 秒被摘除，且摘除后无法恢复。**

| 环节 | 事实 | 证据 |
|---|---|---|
| IDE 侧窗口 | 30 秒内无入站帧即摘除会话 | `AgentProtocol.ONLINE_WINDOW_MS` 由 T-RA-01（`82feb08`，2026-09-18）起真正执行 |
| agent 侧节奏 | 心跳**实际 60.6 秒**一发，非协议声明的 10 秒 | 探针直连生产 agent jar：`HELLO +17ms`、首条 `HEARTBEAT +60633ms`；独立核实代理复测 61.6s |
| 机制 | `idleMs` 累加「假定每轮 200ms」，而每轮真实耗时≈1.2s（入站 CONFIG 读超时 1s + 睡眠 200ms）→ 凑够 10s 要 50 轮≈60s | `SenderLoop.java:70-84`（修复前） |
| 后果 | 首条心跳永远赶不上窗口 → 会话必被摘除；沙盒日志四轮运行全部命中 | 存活 30.009 / 34.047 / 33.559 / 30.005 秒 |
| 不可恢复 | premain 通道与 attach 链都只 `accept()` 一次就关监听 socket，重新挂载只由「进程启动」事件触发 | 现场：应用 JVM 又存活 **7 分 34 秒**（15:56:19→16:03:53），增强会话 15:56:49 摘除后**再无任何重建记录** |
| 控制台通道 0 产出 | **不是缺陷**：demo 工程 `dev` 档不打印 MyBatis SQL（`application-dev.yml:35` 用 `Slf4jImpl`、`:45-47` 的 `logging.level` 整段注释、`logback-spring.xml` 不在 classpath 根） | 现场读数「已挂载 5 个、已扫描 88 行、产出 0 条」与此吻合 |

**回归性质**：由 2026-09-18 的 `82feb08` 引入。该提交前在线态只看 socket 是否断开，会话永不因心跳过期被摘除，agent 侧这个节奏偏差被掩盖了一个版本周期。

---

## 二、改动总览（6 条工作树并行，写集互不相交）

| 工作树 / 分支 | 提交 | 内容 |
|---|---|---|
| `...-t-heartbeat` / `t-agent-heartbeat` | `5efc758` `ee10b86` `140655c` | **T-01**：心跳判定改按最近出站帧的**墙钟差**（每轮都判，不被 `burst==0` 包住）；写完 HELLO 立即补发一条 HEARTBEAT；间隔可经 `sqlvis.agent.heartbeatMs` 注入；入站读超时改为 `间隔/3` 钳在 [50,1000]ms（默认仍 1000ms，与修复前逐值一致）。**T-02**：`ONLINE_WINDOW_MS = HEARTBEAT_INTERVAL_MS * 3`（值仍 30s，推导关系入码）；删掉空绿断言 `contains("10_000L")`，换成源码扫描的跨侧不变量 `window >= 3 × interval`；新增 `HeartbeatRhythmTest` 节奏实测 |
| `...-t-reconnect` / `t-agent-reconnect` | `6178713` `51ad43d` | **T-03**：`acceptPremain` 改循环 accept（上限 32）；首次回连超时与通道寿命拆成两个语义；新增 `onRunEnded()` 接在 `SqlLogManager.onConsoleRemoved`；补 pid 消亡判定（`ProcessHandle`）与 12h TTL 兜底；`PremainChannel` 实现 `Disposable` 注册进项目 disposable 树 |
| `...-t-status` / `t-agent-status` | `a430c12` `80f67af` | **T-05**：新增 `livenessFrameSeen`（HELLO 不计入）；摘除分「首条心跳未到」与「心跳停摆」两类；文案去掉「进程可能已卡死」因果断言、带上窗口秒数；双语 bundle 同步 |
| `...-t-diagwrap` / `t-agent-diagwrap` | `bc6e2f4` `b7c8058` | **T-04**：渲染层改走 `StatusText.appendLine` + 自算折行（宽度取列表可用宽度、clamp 下限、首帧兜底、`ComponentListener` 缩放重排）；语义行切三行 |
| `...-t-proxycl` / `t-agent-proxycl` | `97a70d2` | **T-06**（独立核实追加立项）：代理定义加载器改候选链实测校验；失败不再静默（计数 + 一次性 WARN）；语句包装失败返回原语句；夹具补驱动自定义接口 + 子加载器用例 |
| 复核整改（直接在 master） | `c37b9d2` | `onPremainConnected` 补清两类回落标记（与 `attachToPid` 对称）；订正三处「互斥」谎报 |

**合流**：`AgentManager.kt` 由 git **自动合并成功**（T-03 与 T-05 两侧改动并存，各 11 处命中）；计划文档 `实施记录` 表每次冲突均按**取并集**裁决；全仓零冲突标记残留。

---

## 三、自动化验证清单

| 通道 | 结果 |
|---|---|
| `releaseGate`（= `buildPlugin → verifyGate` 三目标兼容门禁 `→ testShards` 全量） | **BUILD SUCCESSFUL**（5m55s，末笔提交 `4049043` 上执行）；三目标 verdict 全 **Compatible**；`testShards` **491 类 / 5604 用例 / 6 跳过 / 0 失败 / 0 错误** |
| 合流后复验（master `a1b3068` = 本批 + 另一会话的 `t-template-keydrop` 四笔） | `buildPlugin` + `verifyGate` 三目标仍全 **Compatible**；`testShards` **490 类 / 5601 用例 / 6 跳过 / 0 失败 / 0 错误**。本批全部改动经 `git show HEAD:<file>` 逐处确认仍在 HEAD（`lastOutboundMs` / `HEARTBEAT_INTERVAL_MS` / `appendLine` 均在位），`95d8eb8` 仍是 master 祖先——**合流未丢内容** |
| 合流后首跑的一次假失败（已三步证伪） | `McpHistoryIngestTest` 4 例红（`AlreadyDisposedException: [T-ST-02] AlreadyDisposed@shard1 … service=SqlLogManager`）。证伪：①隔离复跑 4/4 绿；②**`testShard1` 单独重跑全绿**；③随后 `testShards` 一次全绿。判为**分片顺序共享状态泄漏**（仓内既有 T-StabilityTail 在册项），由本轮测试类增删（本批新增 `EmptyTextRenderTest` 等 + 另一会话删 1 增 2 个测试类）引起的**分片重排**触发，非功能回归 |
| `buildPlugin` | **SUCCESS** |
| `verifyGate`（243 / 253 / 262 三目标） | 三条 verdict 全为 **Compatible** |
| `testShards`（合并树） | **491 类 / 5604 用例 / 6 跳过 / 0 失败 / 0 错误** |
| `:sql-visualizer-agent:test` | 66 / 0（清 `build` 后实跑，非 UP-TO-DATE） |

**先红后绿（6 条车道各自的实测反向验证读数）**

| 车道 | 反向验证红读数 |
|---|---|
| T-01 | **断言红（非超时红）**：`HELLO 后首条心跳应在 450ms 内，实测 2410ms`；还原后 1/1 绿 |
| T-02 | 旧形态下跨侧不变量与计时形态守卫**两处独立转红**（无单点兜底盲区） |
| T-03 | `ConnectException: Connection refused` ×4 —— **与现场根因逐字同形**（重连撞已关端口） |
| T-04 | 2 例转红：`StatusText 自己的文本不得含裸 \n…：甲行\n乙行\n丙行`、`窄窗必须折成多行：narrow=1` |
| T-05 | 6 例转红，报错原文即现场错误叙事（`pending` 与 `lost` 收敛成同一句） |
| T-06 | 3 例转红，红读数带着异常本身 `…VendorConnection referenced from a method is not visible from class loader`，同时证明「不再静默」且「不刷屏」（3 例失败只打 1 行、计数累加） |

**门禁首跑的假失败（已三步证伪，记录在案）**：首跑 `:testShard3 FAILED`，7 例 `EpBridgeSinglePathTest` + 1 例 `HelpUiTest`。三步证伪：①两类的 XML 均显示失败；②**隔离复跑全绿**（7/7、3/3）；③**单独重跑 `testShard3` 全绿**（同分片、同顺序，2096/0/0）。故判为环境性（当时另有两个会话在同机跑 gradle 并重写共享 `transforms/.../ideaIU-2024.3.7.1-win`，W4 代理也撞到同一现象），非本次改动引入。随后 `testShards` 一次性全绿。

> 另注：本轮我自己的收口命令犯过一次「用 `echo` 读退出码把真实码盖掉」的错——后台报 exit 0 而实际 `BUILD FAILED`。已改为一律读日志里的 `BUILD SUCCESSFUL/FAILED` 与 XML 读数，不靠退出码。

---

## 四、独立复核（写验分离，两路只读代理）

### W5：根因复核 + P6 判定（P6 是立项时最大的未知数）

- **逐条复核根因**：四条全部**成立**；并补一条我漏的——摘除确是 **IDE 主动关**（读循环退出栈首帧 `SocketException: Socket closed`，而 `alive=false` 只由 `AgentSession.close()` 置位）。另发现**第 5 个在线窗口**（`16:07:19→16:07:59` = 40.0s，属另一个项目、由 DatabaseTools 远程 JDBC 驱动进程触发），成因未定位。
- **P6「织入活着时能否捕到 SQL」→ 已证实能**：生产 agent jar + 真实 `mysql-connector-java-8.0.28` + HikariCP 4.0.3 + 真库，三种形态全部捕到事件（premain + 系统加载器 / agentmain + retransform / Hikari 连接池包装）。即：心跳修好后用户那个场景会正常出条目。
- **顺手挖出 T-06**（见下）。

### W7：跨车道交互复核

五条车道各自验过自己，但没人检查过它们**互相之间**怎么作用。总判：**不存在会让人不敢交付的问题**（没有出现「两条通道同时零产出」「状态行说假话」「宿主语义被钩子破坏」这类形态）。五问全落「良性」，另带回四条真缺陷与九条弱验证，已全部登记在计划 §七，其中两条本次直接修（见 §二 末行）。

---

## 五、一处计划前提被实测推翻（记录在案）

计划 §三 T-04 写「`JBList` 空态文本是 `JLabel`，用 HTML `<br>` 换行」，并引仓内 `EnhancedCaptureConfirmDialog.kt:92` 的先例为依据。**实测不成立**：`JBList.getEmptyText()` 返回 `com.intellij.util.ui.StatusText`（2024.3.7.1 与 2026.2.1 两发行版 `javap` 双向确认），不解析 HTML、不把 `\n` 当换行、超宽只裁不折。

**照原计划实现会把 `<html><body style='width:400px'>…` 字面量画到面板上，比修复前更糟。** 实施代理没有照做，改走平台唯一的多行手段 `StatusText.appendLine` + 自算折行。

我的错在哪儿：那个先例确实用 HTML 换行，但**它挂在 `JBLabel` 上，而面板空态根本不是 `JBLabel`——我引先例时没有核实适用面**。计划文档里该节文字已保留原样并标注「前提已推翻」，作为记录。

---

## 六、真机验收口径（留人工）

`docs/规范/测试沙盒验证清单.md` 新增「增强捕获心跳与重连」九条，其中一条口径已按独立复核订正（「等待中」态在 T-01 修复后几乎不可复现，真机只需确认**不误报停摆**）。要点：

1. 启动应用后**不碰面板**静等 2 分钟以上，`idea.log` 内 `增强捕获在线态切换: true -> false` **0 条**（修复前：30 秒处必有一条）；
2. 期间状态行持续显示「已挂载 pid=…」，不得出现任何心跳类回落文案；
3. 应用刚启动那几秒不得误报停摆（「等待中」态本身由单测覆盖）；
4. 应用真正停摆后才报回落，文案形如「已 30 秒未收到应用侧心跳，已回落控制台捕获」且**秒数随窗口值变动**；
5. 打 `http://localhost:8088/user/getAll` → 面板应出现条目（P6 的端到端确认）；
6. 手工制造一次掉线 → 增强通道**自行恢复**，无需重启 IDE；
7. 连续跑两次应用 → `premainChannels` 不随运行次数增长；
8. IDE 退出无 "Memory leak detected"；
9. 空态诊断行三行完整可见、不被右边界截断；工具窗拉到最窄再截一次；英文界面看一眼两态文案。

**至今未跑 `runIde`**，以上九条全部待人工。**不拿单测绿冒充视觉与真机验收。**

---

## 七、已知边界与未收编项（详见计划 §七）

- **`contentRemoved` 未做「主控制台」过滤（低-中，建议单独立项）**：`scanAll` 用边沿判据，而 `ContentManagerListener.contentRemoved` 无条件调 `onConsoleRemoved()`——跑着应用时关掉 HTTP 响应页签也会走这条路。**T-03 之前就有**，但 T-03 把 `onRunEnded()` 挂上去后，后果从「误停捕捉」扩大到「顺手关掉本轮 premain 通道」。触发路径未真机确认，故本次不修。
- **`closePremainChannels` 的 `clear()` 竞态（极窄窗口）**：T-03 之前就有，T-03 只是放大了暴露面。不列为交付阻塞。
- **T-06 的失败路径零测试覆盖**：T-06 的卖点是「失败不再静默」，而这条路径既无夹具也无断言（讽刺点：该车道 commit 正是批评这种形态）。建议补「跨 loader 不可见接口集」用例。
- **`MdcAccessor` 同族加载器缺陷**（T-06 实施时顺带发现）：用 `Class.forName("org.slf4j.MDC")` 经 agent 自身加载器解析宿主类，fat jar 下请求标签恒 null。不在本次缺陷面内，改前就如此。
- **九条弱验证清单**（`awaitTrue` 超时红、心跳窗口在测试里硬编码 `30_000L`、多处源码文本 `contains` 扫描、`HeartbeatRhythmTest` 依赖 `EventBus` JVM 单例、T-06 真驱动对照与 Hikari 跨加载器组合未实测等）逐条登记在计划 §七。
- **真机未验项**：pid 消亡判定对 IDE 子进程的存活判定、窄窗折行观感与 `StatusText` 实际绘制行数、T-01 的 10s 生产刻度读数（只在 300ms 刻度实测）、`contentRemoved` 是否真被 HTTP 页签触发。

---

## 八、实测读数汇总（便于复查）

| 项 | 读数 |
|---|---|
| 修复前首条心跳 | `HELLO +17ms` → `HEARTBEAT +60633ms`（独立复测 61.6s） |
| 修复后首条心跳 | `HELLO +231ms` → `HEARTBEAT +231ms`（间隔 0），此后 10785ms / 10685ms |
| 沙盒四轮会话存活 | 30.009 / 34.047 / 33.559 / 30.005 秒（修复前，全部必死） |
| 应用 JVM 实际存活 | 7 分 34 秒，而增强会话只活了 30 秒 |
| T-06 真驱动前后对照 | 子加载器形态 `events=0`（零诊断）→ `events=2` |
| 跨侧不变量 | agent `DEFAULT_HEARTBEAT_MS=10000`；IDE `ONLINE_WINDOW_MS = HEARTBEAT_INTERVAL_MS * 3 = 30000` |
| 全量测试 | 491 类 / 5604 用例 / 6 跳过 / 0 失败 / 0 错误 |
| `verifyGate` 三目标 | 243 / 253 / 262 全 Compatible |
