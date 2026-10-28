# T-AgentHeartbeat 增强捕获会话 30 秒必掉线 + 诊断行不换行 — 开发计划

> 来源：2026-09-21 用户沙盒实跑反馈「SQL 日志没有正常捕获到」，面板状态行显示
> 「增强捕获：应用侧心跳中断（进程可能已卡死），已回落控制台捕获」。
> 定位依据：沙盒日志 `.intellijPlatform/sandbox/.../IU-2024.3.7.1/log/idea.log`（2026-09-21 全天四轮运行）
> + agent 侧心跳节奏实测探针（本轮新做，读数见 §二.3）。
> 相邻事项：本次修复只动「心跳节奏 / 在线窗口 / 重连通道」三处，不改捕获语义、不改控制台桥链路；
> 面板换行只动渲染层，不动 [captureDiagnosticsText] 的取数契约。

## 一、现象与影响

- **现象**：沙盒里启动 demo（`EasyCodeDemoApplication`）后，增强捕获会话在建立后 **恰好 30 秒**被摘除，
  状态行落到「应用侧心跳中断（进程可能已卡死），已回落控制台捕获」；此后整轮运行再无任何条目。
- **两条通道同时归零**，但原因完全不同（这是本轮最花时间的地方）：
  1. **增强通道**：会话被误杀，且**无法重连**（§二.2、§二.4）——这是缺陷；
  2. **控制台通道**：demo 工程 `dev` 档根本不打印 MyBatis SQL，控制台确实没有可捕的料——**这不是缺陷**
     （既有结论见记忆 `reference-demo-console-sql-logging`；现场读数「已挂载 5 个，已扫描 88 行，产出 0 条」与此一致）。
- **影响面**：所有「应用启动后 30 秒内没有 SQL 事件」的运行都必掉线。应用启动本身通常就要 20~40 秒，
  所以**首轮查询几乎必然落在掉线之后**——对用户而言就是「一条都捕不到」。这不是边角场景，是主路径。
- **回归性质**：由 2026-09-18 的 `82feb08`（T-RA-01 心跳窗口落地）引入。该提交之前在线态只看 socket 是否断开，
  会话永远不会因心跳过期被摘除，所以 agent 侧心跳节奏的偏差被**掩盖了整整一个版本周期**。
- **量化（沙盒日志四轮运行，全部命中）**：

  | 轮次 | 会话建立 | 在线态 true→false | 存活 |
  |------|----------|-------------------|------|
  | 1 | 13:25:01,561 | 13:25:31,570 | 30.0s |
  | 2 | 13:26:47,575 | 13:27:21,622 | 34.0s |
  | 3 | 13:41:49,544 | 13:42:23,103 | 33.6s |
  | 4 | 15:56:19,066 | 15:56:49,071 | 30.0s |

  （>30s 的三轮是巡检 `scheduleWithFixedDelay(5s)` 的量化误差，不是第二个成因。独立核实代理另找到**第 5 个**
  窗口：`16:07:19,271 → 16:07:59,285` = 40.0s，属另一个项目且由 DatabaseTools 的远程 JDBC 驱动进程触发——
  它超出相位模型能解释的上界，成因未定位，见 §七 独立核实条目 3。）
- **用户侧后果（第 4 轮实测）**：应用 JVM 存活 15:56:19 → 16:03:53（**7 分 34 秒**，以 16:03:53 的
  `scanAll: 主控制台已移除` 为终点），而增强会话在 15:56:49 就已摘除且**再未重建**——
  用户在这 7 分半里打的每一次请求（15:56:47 / 15:56:52 / 15:56:54 / 15:56:55 …）都落在「两条通道都死」的窗口里。

## 二、根因（三段证据链，逐段可复现）

### 1. IDE 侧：在线窗口 30s 被真正执行了（这是新行为，不是老代码）

`AgentProtocol.ONLINE_WINDOW_MS = 30_000L`（`AgentProtocol.kt:25`），`AgentManager` 在 T-RA-01 后按它判定
会话健康（`AgentManager.kt:150` / `:157`：`alive && (now - lastSeen) <= heartbeatWindowMs`），
巡检 `sweepOffline()` 每 5s 摘除不健康会话（`AgentManager.kt:845-861`），摘除时 `dead.any { it.alive }`
为真即记为「心跳过期」（`:849`）→ 状态行走 `heartbeatLost` 文案（`:1042-1048`）。

也就是说：**只要 30 秒内没有任何入站帧（EVENT/HEARTBEAT/HELLO），会话必被摘除**。

### 2. agent 侧：心跳实际是 **60.6 秒**一发，不是协议写的 10 秒

`SenderLoop.java` 主循环每轮做三件事（`:36-79`）：

1. 事件尽出（`burst < 64`）；
2. 读入站 CONFIG —— `socket.setSoTimeout(1000)`（`:33`），**无数据时这一读要阻塞满 1000ms**；
3. `if (burst == 0) { Thread.sleep(200); idleMs += slept; if (idleMs >= 10_000) 发心跳 }`（`:68-75`）。

问题在第 3 步：`idleMs` 记的是**假定耗时**（每次 +200ms），而不是真实流逝时间。每轮实际耗时约
1000（CONFIG 读超时）+ 200（sleep）≈ **1200ms**，却只记 200ms——于是「静默 10s 发心跳」需要
50 轮 ≈ **60 秒**才凑够 `idleMs >= 10_000`。

**实测读数**（本轮新做探针：`ServerSocket(0,1,loopback)` + `EventBus.start(port)` + 读帧打时间戳，
复刻 IDE 侧 accept 形态；100s 观察窗）：

```
[probe] +17ms     cmd=HELLO      gapSincePrev=17ms
[probe] +60633ms  cmd=HEARTBEAT  gapSincePrev=60616ms   heartbeats=1
[probe] 观察窗口 100s 到，退出
```

HELLO 后 **60.6 秒**才来第一条心跳，100 秒窗口内只有这一条。即：**心跳节奏比协议声明的慢 6 倍，
比 IDE 的在线窗口慢一倍**——首条心跳永远赶不上 30 秒窗口，会话必然在第一条心跳之前被摘除。

> 附带一条：入站 CONFIG 会把 `idleMs` 清零（`:63`），所以「点开始捕捉」下发 CONFIG 反而把心跳又推后一轮，
> 但这不是主因（第 4 轮 startCapture 在 15:56:41，会话仍死在 15:56:19 起算的 30 秒处）。

### 3. 重连通道不存在：掉了就是永久掉了

agent 侧设计上是无限重连的（`SenderLoop` 断线按 1s→5s 退避重连同一端口，`EventBus` 注释明确写了这点），
但 IDE 侧**只 accept 一次**：

- premain 通道 `acceptPremain()`（`AgentManager.kt:653-669`）`accept()` 到一条连接后立即
  `finally { channel.server.close(); premainChannels.remove(channel) }` —— 监听 socket 被关掉，
  agent 的退避重连只会撞 connection refused；
- attach 链 `attachAndOffer()`（`AgentManager.kt:542-571`）同样是 `ServerSocket.use { ... server.accept() }` 一次性；
- 而重新 attach 的唯一触发点是 `onProcessAppeared`（进程启动事件），应用已经在跑，不会再触发；
  `sweepOffline` 还会把该 pid 从幂等表摘掉（`:854`）。

结果：**任何一次会话摘除，对该次应用运行而言都是终局**。第 4 轮日志里 15:56:49 之后到应用结束（16:03:53）
再无任何 `premain 会话建立` / `attachToPid` 记录，与代码结论一致。

### 4. 守卫为什么没拦住（空绿）

`AgentProtocolSyncTest.帧格式_长度前缀加单字节命令`（`AgentProtocolSyncTest.kt:64-65`）唯一的「心跳」断言是
`assertTrue(loop.contains("10_000L"))` —— 它只证明**源码里存在这个字面量**，对「有效节奏是否 ≤ 在线窗口」
完全不可见。两侧常量各自漂移、跨侧不变量（节奏 < 窗口）无人守，这正是本缺陷能长期存在的结构原因。

### 5. 用户看不到可执行的提示（第二个诉求的由来）

面板空态第二行由 `captureDiagnosticsText`（`CaptureDiagnostics.kt:62`）拼成
`"$channels\n$hint"`，其中 `hint` 正是这条现场最该被读到的话：
「控制台有输出但未见 MyBatis SQL 日志——请确认应用已开启 SQL 日志输出（mapper 包日志级别为 debug），或启用增强捕获」。

但 Swing 的 `JLabel`（`JBList` 空态文本就是一个 `JBLabel`）**不把 `\n` 当换行**——截图里两段被连成一行，
在面板右边界被截断（`…产出 0 条｜重放守卫已丢弃 8 行控制台有输出但…`）。
即：**能解释「为什么捕不到」的那句话，用户根本读不到**，而同一行前半段的「心跳中断」错误叙事又把人往错方向引。
`log.panel.empty`（双语 bundle，`MpSqlVisualizerBundle_zh.properties:443`）同样用 `\n`，有同样的问题。

## 三、修法

### T-01（P0，根因）agent 心跳改为按真实流逝时间计

`SenderLoop.java` 第 3 步不再累加假定的 200ms，改为记录**上一条出站帧的时刻**，每轮比较墙钟：

- `lastOutboundMs` 在每次 `writeFrame`（事件/心跳/HELLO）后更新；
- 每轮循环（无论 `burst` 是否为 0）判 `System.currentTimeMillis() - lastOutboundMs >= heartbeatIntervalMs` → 发心跳；
- 连接建立、写完 HELLO 之后**立即补发一条心跳**——这样即便首轮迭代被 CONFIG 读超时拖住，
  IDE 侧的 `lastSeen` 也从头就是新鲜的，在线窗口不再依赖首轮迭代速度；
- 心跳间隔改由可注入值提供（系统属性如 `sqlvis.agent.heartbeatMs`，默认 10000），
  供 T-02 的快测驱动（默认值不变，生产行为不受影响）。

保留单发送线程模型（事件与心跳共用一条连接，IO 串行是既有设计，不引入第二条写线程）。

### T-02（P0，反漂移）把「节奏 < 窗口」变成跨侧不变量并加真守卫

- IDE 侧在线窗口不再写死 30s 与心跳间隔各自为政：窗口改为按心跳间隔推导并留 ≥3 倍余量
  （心跳 10s → 窗口 30s，值不变，但**推导关系写进代码**，任一侧改动都会体现在另一侧）；
- 测试替换掉 `contains("10_000L")` 这条空绿断言，新增两类：
  1. **跨侧常量守卫**（源码文本扫描，与既有 `AgentProtocolSyncTest` 同款）：断言
     agent 侧心跳间隔常量与 IDE 侧窗口常量满足 `window >= 3 * interval`——两侧任一方漂移即转红；
  2. **节奏实测**（真起 agent `EventBus` + 探针 ServerSocket，用 T-01 的可注入间隔压到 300ms）：
     断言「HELLO 后 1.5 倍间隔内收到第一条心跳」且「相邻心跳间隔 ≤ 1.5 倍设定值」——
     这条用例如果放回修复前的实现，读数会是 6 倍间隔，必红（反向验证见 §五）。

### T-03（P1，韧性）premain 通道支持重连，会话摘除不再等于终局

- `acceptPremain` 改为**循环 accept**（同一 `ServerSocket` 持续监听），每次接受后建会话；
  同 pid 已有活动会话时先关旧会话再挂新（幂等，沿用 `onPremainConnected` 现有判据的语义）；
- 通道生命周期从「accept 一次即关」改为**绑定到本次运行**：以控制台移除（`SqlLogManager.onConsoleRemoved`，
  即项目停止语义的既有承载点，见 `RunConfigurationProcessListener` 类注释）为关闭信号，
  另设一个长 TTL 兜底；重连次数设上限（如 32）防异常抖动下的热循环；
- 泄漏面必须收口：accept 线程持有的 socket 注册进项目 disposer 树（Disposer 树是既有踩坑，
  见全局记忆 `pitfall` 条目），`shutdownAll` / 项目 dispose 时一并关闭；
- 收尾验证：连跑两次运行 + 手工 kill 会话（或在测试里直接 `session.close()`）后确认能自动恢复，
  且 `premainChannels` 不随运行次数增长（`MAX_PREMAIN_CHANNELS = 4` 的上限不应被撞到）。

### T-04（P0，用户第二个诉求）诊断行与空态文案换行

> **⚠️ 本节前提已被实施时实测推翻（2026-09-21）**：下面写的 HTML 路线在本平台**不可行**——
> `JBList.getEmptyText()` 返回的是 `com.intellij.util.ui.StatusText`，不是 `JLabel`；`StatusText` 及其底层
> `SimpleColoredComponent` 在绘制路径上完全不处理 `\n`、也不解析 HTML。照本节原样实现会把
> `<html><body style='width:Npx'>…` **字面量画到面板上**，比修复前更糟。实际落地见文末
> §实施记录「T-04（空态诊断文案换行）」——改走「自算折行 + `StatusText.appendLine` 逐视觉行写入」。
> 本节文字保留原样，作为「计划前提被实测推翻」的记录。

- 渲染层（`SqlLogPanel.syncDiagnostics`，`SqlLogPanel.kt:1240` 与 `buildListPane`，`:1255`）把纯文本改成 HTML：
  `<html><body style='width:Npx'>…<br>…</body></html>`——仓内已有同款先例
  （`EnhancedCaptureConfirmDialog.kt:92` 用 `JBLabel` + `<html><body style='width:480px'>` 做自动换行）；
  （**该先例在 `JBLabel` 上成立，但面板空态根本不是 `JBLabel`——先例的适用面没有核实，这是本节出错的直接原因**）
- 换行点按语义给：增强通道 / 控制台通道各占一行，丢弃与短路计数并到控制台那行，
  最后的「下一步提示」单独一行（这是用户真正要读的）；**（此条照做，未变）**
- **不改** `captureDiagnosticsText` 的取数契约与单测（它仍返回带 `\n` 的纯文本，由渲染层转 HTML），
  这样既有 5 例诊断用例零改动；`log.panel.empty` 的 `\n` 一并走同一转换；**（此条照做，只是渲染层手段换了）**
- 宽度取值需在沙盒里按工具窗实际宽度确认（见 §四 P4）。**（仍待验）**

### T-05（P1，文案真实性）状态行不再断言它不知道的原因

「应用侧心跳中断（进程可能已卡死）」在这次现场是**错误叙事**：进程活得好好的（又跑了 7 分钟），
只是第一条心跳还没到。改法：

- 文案降级为可核实的表述（例：「已 30 秒未收到应用侧心跳」），去掉「进程可能已卡死」这种因果断言；
- 补一态区分「刚连上、首条心跳未到」与「曾有心跳、之后停摆」——前者是连接早期，
  后者才是应用侧无响应，二者对用户的处置建议不同（等一等 / 去看应用进程）。

### T-06（P1，独立核实追加立项）子类加载器下代理定义失败 → 静默零捕获

**来源**：本轮独立核实代理（只读）在验 P6 时实测挖出，与原计划的心跳问题**无关**，是另一条通向「零产出」的路。

- **现象**：当 JDBC 驱动由「系统类加载器之下的子加载器」加载时——`java -jar` 的 Spring Boot fat jar
  （`LaunchedURLClassLoader`）、`spring-boot-devtools`（`RestartClassLoader`）、任何嵌套加载器容器——
  SQL 织入**整体失效且零诊断**：连接仍是原始 `com.mysql.cj.jdbc.ConnectionImpl`，事件数 0，插件侧一条日志都没有。
- **机制**（探针单独钉死）：`ProxySupport.java` 用 `ProxySupport.class.getClassLoader()`（agent 侧加载器）去
  `Proxy.newProxyInstance` 定义代理类，而真实 `ConnectionImpl` 的接口集含 `com.mysql.cj.jdbc.JdbcConnection` /
  `com.mysql.cj.MysqlConnection` / `com.mysql.cj.TransactionEventHandler` / `Session$SessionEventListener`，
  这些属于**驱动加载器**（子加载器）→ JDK 代理生成器拒绝：
  `IllegalArgumentException: interface com.mysql.cj.jdbc.JdbcConnection is not visible from class loader`。
  异常被 `SqlHookCallbacks` 的 `catch (Throwable ignore)` 静默吞掉并返回原连接，既不记日志也不计丢弃数。
- **测试为什么全绿**：`FakeDriver` 的 `connect` 返回的代理只实现 `java.sql.Connection` 一个接口——那是 bootstrap
  接口，任何加载器都可见，所以 `ConnectWeaveEndToEndTest` / `ConnectWeaverTest` **从未执行过那条失败分支**。
  夹具与真实驱动的唯一实质差距，恰好落在唯一的失败点上。
- **修法**：① 代理定义改用能看见全部接口的加载器（连接对象自身的加载器 / 接口的加载器，取哪个需论证并审全部
  `newProxyInstance` 调用点）；② 失败改为可观测（限频 WARN 或计数，避免日志洪水）；③ 夹具补一个驱动自定义接口
  使失败分支被真实覆盖，并新增「驱动在子 URLClassLoader 里」的用例；④ 三种既有形态（premain+系统加载器 /
  agentmain+retransform / Hikari 连接池包装）不得回退。
- **边界**：这条不改变当前 IDE Run 形态的行为（已实测能捕到），修的是 fat jar / devtools 等启动形态。

## 四、待验证前提

| # | 前提 | 验证方式 | 状态 |
|---|------|----------|------|
| P1 | 修复后首条心跳 ≤ 1 倍间隔内到达（探针复测） | 探针重跑，断言 HELLO→HEARTBEAT 间隔 | **待验**（修复前读数 60.6s，已实测） |
| P2 | 应用启动期（Spring Boot 正在初始化、类加载密集）sender 线程仍按节奏发心跳 | 沙盒实跑，读 `idea.log` 的在线态切换时间戳；应用启动期无法用纯 JVM 探针覆盖 | **待验** |
| P3 | 保持 premain 端口常开不产生端口/线程/会话泄漏 | 连续两次运行后检查 `premainChannels` 规模与线程名 `mps-agent-premain-*` 残留；`shutdownAll` 后端口应释放 | **待验** |
| P4 | ~~`JBList` 空态文本（`JBLabel`）渲染 HTML 且 `body width` 生效~~ → **前提已推翻**：`JBList.getEmptyText()` 返回 `com.intellij.util.ui.StatusText`（2024.3.7.1 与 2026.2.1 两个发行版 `javap` 双向确认），不解析 HTML、不把 `\n` 当换行、超宽只裁不折 | 实施时实测（`javap` 双向 + 反编译确认无 HTML 处理）；观感仍需沙盒截图（空态 + 有诊断两态，工具窗拉到最窄再截一次） | **前提推翻**——已改走「自算折行 + `StatusText.appendLine` 逐视觉行写入」；**窄窗观感仍待验**（未跑 runIde） |
| P5 | 修复前「应用侧心跳中断」文案只在真停摆时出现 | 修复后连跑，确认不再有误报；必要时用测试注入短窗口驱动 | **待验** |
| P6 | 增强通道在本 demo 上确实能捕到 SQL（立项时**没有**任何 SQL 事件样本，因为会话从未活到用户查询） | 修复后沙盒实跑：应用启动 → 打 `http://localhost:8088/user/getAll` → 面板应出现条目 | **已证实（独立核实代理实测）**：用生产 agent jar + 真实驱动 `mysql-connector-java-8.0.28` + HikariCP 4.0.3 + 真库跑了三种形态，全部捕到 SQL 事件（premain+系统加载器 / agentmain+retransform / Hikari 连接池包装）。**IDE Run 这个形态下能捕到**——但见 T-06，换成 fat jar/devtools 启动仍会零产出 |

> P6 原是本计划最大的不确定项（立项时所有读数只能证明「通道死得早」，证不了「通道活着时能捕到 SQL」）。
> 独立核实代理用真实驱动实测后已证实**能捕到**，且顺手挖出 T-06 这条同类的静默零捕获路径——那是另一个成因，
> 与心跳无关，本次一并立项修掉。

## 五、测试

1. **T-02 跨侧常量守卫**：源码扫描两侧常量，`window >= 3 * interval`。
   **反向验证**：把 agent 侧间隔改回 60_000 或把窗口改回 10_000 → 必红（贴两次读数）。
2. **T-01 节奏实测**（真起 `EventBus`，间隔压到 300ms）：HELLO 后 ≤450ms 内收到首条心跳；
   相邻心跳间隔 ≤450ms；观察窗 3s 内至少 6 条心跳。
   **反向验证**：把 `SenderLoop` 的计时改回 `idleMs += slept` 形态 → 读数退化为 ~6 倍间隔，必红。
3. **T-03 重连**：`AgentManagerPremainTest` 现有夹具上加一例——建会话 → 主动 `close()` →
   同一 pid 再次回连 → 断言新会话建立、`sessions.size == 1`、`online == true`；
   再加一例断言重复回连不撞 `MAX_PREMAIN_CHANNELS`。
4. **T-04 渲染**：`CaptureDiagnosticsTest` 既有 5 例零改动（纯函数契约不变）；
   新增渲染层用例断言 HTML 串含 `<br>`、且 `\n` 已全部转成 `<br>`（含 `log.panel.empty`）。
5. **T-05 文案**：`AgentStatusTextTest` / `AgentHeartbeatWindowTest` 按新文案更新断言，
   并新增「首条心跳未到」与「曾有心跳后停摆」两态可区分的用例。
6. **定向回归**：`log` 包 + `log/agent` 包全量；agent 子项目 `./gradlew :sql-visualizer-agent:test`。
7. 构建校验（`verifyGate` / `verifyPlugin` / `buildPlugin`）在全部改动完成后统一跑一次。

## 六、真机验收口径

沙盒 `./gradlew runIde` + `easy-code-demo`：

1. 启动 `EasyCodeDemoApplication`，等应用起来（观察控制台出现 `Started EasyCodeDemoApplication`）；
2. **不碰面板**，静等 **2 分钟以上**；
3. 读 `idea.log`：`增强捕获在线态切换: true -> false` **0 条**（修复前：30 秒处必有一条）；
4. 面板状态行应显示「已挂载 pid=…」，**不得**出现「心跳中断」；
5. 打 `http://localhost:8088/user/getAll`（IDE HTTP Client 或浏览器）→ 面板应出现条目（P6）；
6. 面板空态诊断行截图：提示语应**完整可见且换行**，不被右边界截断（P4）；
7. 手工制造一次掉线（停应用再启、或临时 kill 会话）→ 增强通道应能自行恢复（T-03）。

## 七、未收编项与边界

- **控制台通道零产出不是缺陷**：demo 工程 `dev` 档（`application-dev.yml:35` 用 `Slf4jImpl` +
  `:45-47` 的 `logging.level` 整段注释 + `logback-spring.xml` 不在 classpath 根）本就不打印 MyBatis SQL。
  本计划不改 demo 工程，也不改控制台桥的识别规则；验证控制台链路时按既有记忆的配方临时开 mapper DEBUG 或切 prod。
- **「已挂载 N 个控制台」含 HTTP Client 控制台**（现场 5 个里多数是它），对「应用控制台有没有输出」这个判断是噪音。
  属可选打磨，本次不做。
- **首条心跳之前的窗口**：T-01 的「连上即补发心跳」消除了大部分风险，但若 agent 侧 `SenderLoop`
  线程本身起不来（极端场景），IDE 侧仍会在 30 秒后摘除——这条不再做额外兜底，因为摘除后的恢复由 T-03 承接。
- **`82feb08` 之前的会话永不摘除**这一行为不回退：心跳窗口本身是对的（半死会话封口控制台桥是更糟的形态），
  本次修的是两侧节奏不匹配，不是取消窗口。

### 独立核实代理在同一份沙盒日志里另找到的问题（本次只登记，不实施）

核实代理被要求「不要只复述结论，自己扫一遍同一份日志」，除确认上述根因外还带回了五条。按处置分两类：

**值得单独立项（真问题，本次不做）**

1. **`detachAllFilters: FAILED to remove filter` ×17，根因已定**：IU-243 的
   `com.intellij.execution.impl.ConsoleViewImpl` **根本没有 `removeMessageFilter` 方法**（javap `app-client.jar`
   只有 `addMessageFilter` 与 `clearMessageFilters`），`SqlLogConsoleBridge.kt` 的 `FILTER_IFACE_CANDIDATES`
   两个候选都没有它 → 反射移除**永远不可能成功**，WARN 文案「filter will remain active!」属实但被夸大。
   缓解是既有的：`detachAllFilters` 同时 `consoleContexts.remove(console)`，而 filter 体第一道守卫是
   `consoleContexts[console] ?: return` → 残留 filter 实为惰性。**净影响：每轮捕获周期泄漏一个 filter 对象 +
   一条误导 WARN**，低危。
2. **`onConsoleRemoved` 的 armed 语义自相矛盾（3 次，两种落点）**：`13:26:32` 落到
   `停止捕捉（autoStart=false, armed=false）`，而 `13:44:30` / `16:03:53` 落到 `回到 armed 态`。
   同一个事件在「手动开启」路径下把 armed 也清成 false，之后项目重跑不会自动恢复捕获。与桥停摆耦合，低危。
3. **agent 被挂到 IDE 自己的 JDBC 驱动进程上（中危）**：日志里第 5 个在线窗口（`16:07:19,271 → 16:07:59,285`，
   属另一个项目 `mybatis-plus-sql-visualizer-demo`）是 **DatabaseTools 的远程 JDBC 驱动进程**触发的——
   紧邻 `RemoteProcessSupport - WARNING: A Java agent has been loaded dynamically (…mps-sql-agent…jar)`，
   随后 `16:07:23 onProcessAppeared: 目标 pid=52172`。用户只是打开了 Database 工具窗，插件就把 agent 挂到了
   IDE 自身的驱动 JVM 上，并在状态行显示「在线」。挂载目标会被非目标进程污染，且这一轮的窗口时长 40.0s
   **超出 5s 巡检相位模型能解释的上界**，成因未定位。

**判为噪音（不建议立项，可选打磨）**

4. **HTTP Client 进程刷 WARN**：`resolvePid: 三步回退全失败（handler=HttpClientRequestProcessHandler…getProcess()）`
   ×6。HTTP Client 不是 Java 应用进程，跳过挂载是正确行为，只是每次运行都刷一条 WARN，可降 DEBUG。
5. **「重放守卫已丢弃 8 行」不在 `idea.log` 里**：全日志 grep 零命中——它是 UI 诊断量（`CaptureDiagnostics.kt`），
   逐行日志是 `LOG.isDebugEnabled` 级别而该日志为 INFO。**这条读数的来源是用户截图，不是日志**，
   立项文档里不要把它当日志证据引用。

### 实施 T-06 时顺带发现的同族未修缺陷（W6 代理带回，本次不改）

- **`util/MdcAccessor.java` 用 `Class.forName("org.slf4j.MDC")` 经 agent 自身加载器解析宿主类**：
  fat jar / devtools 形态下 slf4j 在子加载器里 → 解析不到 → 请求标签（traceId 聚合）恒为 null。
  与 T-06 同源（都是「拿 agent 侧加载器去够宿主侧类」），但属「请求聚合」特性、不在本次缺陷面内，
  且本次改动没有让它变差（改前就如此）。若要一并修，取法可直接复用 T-06 的候选加载器校验链。
- **Hikari 与驱动分处不同加载器的组合未实测**：T-06 的验证里 Hikari 与驱动同在系统 classpath，
  `HikariProxyConnection` 接口集跨加载器的那种形态只是靠候选链兜底的理论推断，未实测。

### 跨车道独立复核（W7，只读）另找到的问题

五条车道各自验过自己，但**没人检查过它们互相之间怎么作用**。合流后另派了一条只读复核专门做这件事，
总判是「**不存在会让人不敢交付的问题**」（没有出现「两条通道同时零产出」「状态行说假话」「宿主语义被钩子破坏」这类形态），
但带回四条真缺陷与九条「看起来验过、其实没验」。处置如下：

**本次已修（复核后直接改）**

1. **KDoc 谎报「互斥」**：`firstHeartbeatPending` 与 `heartbeatDropDetected` 由两个独立 `any` 计算，
   多会话同轮过期时可**同时为真**，而三处 KDoc/注释都写「互斥」——与实现及作者自己的用例
   （`AgentStatusTextTest` 里就在测「同时置位时以停摆为准」）矛盾。已改口为「可同时置位，叙事以停摆优先」。
2. **`onPremainConnected` 漏清回落标记**（与 `attachToPid` 不对称）：`attachToPid` 建会话时清两位，
   premain 通道建会话时只写 `attachedPids` 不清标记 → 重连后状态行可能残留上一轮摘除的叙事。
   当前无可见后果（`agentStatusText` 先判 `livePids`），但任何人重排分支就会露出来。已补对称的两行。

**本次登记不修（需真机确认或属独立面）**

3. **`contentRemoved` 未做「主控制台」过滤（低-中）**：`scanAll` 用「上轮有主控制台、本轮无」的边沿判据，
   而 `ContentManagerListener.contentRemoved` 无条件调 `onConsoleRemoved()`——跑着应用时关掉一个
   HTTP 响应页签也会走这条路。**这是 T-03 之前就有的形态**，但 T-03 把 `onRunEnded()` 挂到了
   `onConsoleRemoved` 上，于是后果从「误停捕捉」扩大到「顺手关掉本轮 premain 通道、该次运行剩余时间失去重连路径」。
   不修的理由：触发路径未真机确认（HTTP 响应页签是否真走这条 listener 未实测），而它改的是核心桥文件的语义；
   且 T-01 修好后会话本就不易掉线，重连只是安全网。**建议单独立项**：让 listener 路径复用 `scanAll` 的边沿判据。
4. **`closePremainChannels` 的 `clear()` 竞态（极窄窗口）**：快照关闭 + 尾随 `clear()`，若 `openPremainChannel`
   在两者之间插入新通道会被无关闭地摘出表 → 该通道只能靠自身 120s 超时自收，`MAX_PREMAIN_CHANNELS` 计数失真。
   **T-03 之前就有**（旧版同款），T-03 只是新增了 `onRunEnded` 这个高频调用者、把暴露面放大。窗口微秒级，不列为交付阻塞。

**九条「看起来验过、其实没验」的清单（按建议处理成本排序）**

5. **T-06 新增的失败路径零覆盖**（讽刺点：该车道 commit 正是批评这种形态）：`grep dropToRaw|fallbackCount` 在 agent 测试目录**零命中**。
   T-06 的卖点是「失败不再静默（计数 + 一次性 WARN + 返回原语句）」，而这条路径既无夹具也无断言。建议补「跨 loader 不可见接口集」用例。
6. **`AgentManagerPremainTest` 的 10 处关键断言走 `awaitTrue`**（5s 轮询）→ 失败读数是**超时红而非断言红**；
   T-03 自报的 4 条反向红里有一条正是这种。建议改两段式（先 `awaitTrue` 建前置，再断言终态）。
7. **心跳窗口在三处测试里硬编码 `30_000L`/`30L`**，未引用 `AgentProtocol.ONLINE_WINDOW_MS`：跨侧不变量测试能红，但这批行为测试不会跟着窗口改动而动。
8. **`EmptyTextRenderTest` 只有 2 条断言真正承重**（`!text.contains("\n")` 与 Fragment 数 = 3）；另 2 条在修复前形态下也是绿的。
   澄清：W4 自报「4 例中 2 例转红」是**准确的**，复核者按计划文档措辞误读成「4 例红」，此处一并订正。
9. **`SettingsTruthTest` 与 `AgentProtocolSyncTest` 多处是源码文本 `contains` 扫描**（如 `!code.contains("idleMs")`）：
   换一种累加写法（`elapsed += 200`）即可通过，判别力有限——是「防漂移」不是「防回归」。
10. **`HeartbeatRhythmTest` 依赖 `EventBus.start` 的 JVM 单例与系统属性注入**：今天 agent 测试目录无第二个调用点故稳定；
    将来若有人加一个先跑的用例，间隔注入会静默失效、本用例转**假红**。且整类只能有 1 例。
11. **`AgentHeartbeatWindowTest` 用反射置 `everAttached`**：「attach 成功 → everAttached → 状态行」这条接线在生产侧无运行期测试，只有文本扫描兜底。

## 实施记录

| 项 | 内容 |
|----|------|
| 合流 | 五条车道（`t-agent-heartbeat` / `t-agent-status` / `t-agent-reconnect` / `t-agent-proxycl` / `t-agent-diagwrap`）已全部并入 master；`AgentManager.kt` 由 git **自动合并成功**（T-03 与 T-05 两侧改动并存，各 11 处命中），计划文档 `实施记录` 表每次冲突均按**取并集**裁决 |
| 统一门禁 | **待跑**——`./gradlew releaseGate`（`buildPlugin → verifyGate` 三目标兼容门禁 `→ testShards` 全量测试）在全部车道合流后只跑一次 |
| 真机验收 | **待人工**——`docs/规范/测试沙盒验证清单.md` 的「增强捕获心跳与重连」九条：2 分钟不掉线、状态行两态措辞、掉线自恢复、连跑通道不堆积、IDE 退出无内存泄漏、诊断行三行不截断 |
| T-01/T-02 心跳节奏与跨侧窗口（W1b，commit `5efc758`） | 代码：`SenderLoop` 心跳判定由「假定每轮 200ms」累加计时（`idleMs += 200`；每轮真实≈1.2s＝入站读超时 1s＋睡眠 200ms → 静默 10s 实际要 50 轮≈60s）改为以 `lastOutboundMs`（最近一次出站帧时刻）的**墙钟差**判定，且**每轮都判**（判定移出 `burst == 0` 分支——事件突发期间也按节奏发）；HELLO 写帧后立即补发一条 HEARTBEAT（首轮迭代被读超时拖住时 IDE 侧 `lastSeen` 也从头新鲜）；间隔可经系统属性 `sqlvis.agent.heartbeatMs` 注入（缺省/非法/非正数回落 `DEFAULT_HEARTBEAT_MS = 10_000L`，生产行为不变）；入站读超时改为 `间隔/3` 钳在 [50,1000] ms（默认 10000/3 被 1000ms 上限钳住，与修复前逐值一致）；空转睡眠取 `min(POLL_IDLE_MS, 距下次心跳剩余)`，不跨过下一次心跳时刻。单发送线程、退避重连（1s→5s）、帧格式与 `CMD_*` 常量均未动。`AgentProtocol.kt`：新增 `HEARTBEAT_INTERVAL_MS = 10_000L`，`ONLINE_WINDOW_MS` 改由它推导（`= 3 倍`，值仍 30s），不变量与理由写进 KDoc。测试：`AgentProtocolSyncTest` 删掉空绿断言 `assertTrue(loop.contains("10_000L"))`（只证明字面量存在，对「有效节奏是否 ≤ 在线窗口」完全不可见——agent 侧改成 60s 或窗口改成 10s 它照样绿），换成源码扫描的**跨侧不变量** `ONLINE_WINDOW_MS >= 3 * agentIntervalMs` 与心跳计时形态守卫（禁 `idleMs`、须 `lastOutboundMs`、HELLO 后须紧跟 HEARTBEAT 写帧、间隔可注入）；新增 `HeartbeatRhythmTest` 节奏实测（真起 `EventBus` + 探针 ServerSocket，间隔压到 300ms）。 |
| T-01/T-02 定向读数与反向验证 | **定向读数**：`./gradlew :sql-visualizer-agent:test`（先 `rm -rf sub-projects/sql-visualizer-agent/build` 防陈旧 UP-TO-DATE）**66 通过 / 0 失败 / 0 错误**（含 `HeartbeatRhythmTest` 1/1，3.475s）；主项目 `./gradlew test --tests cn.song.mybatisplus.visualizer.log.agent.AgentProtocolSyncTest` **4 通过 / 0 失败**（0.161s）。**反向验证＝断言红（非超时红）**：把 `SenderLoop` 心跳判定退回旧形态（固定 1s 读超时 + 取消 HELLO 后补发 + `idleMs += 200` 累加、阈值取注入间隔；仅保留间隔注入钩子以便把刻度压到 300ms）→ `HeartbeatRhythmTest` 转红，报错原文 `java.lang.AssertionError: HELLO 后首条心跳应在 450ms 内，实测 2410ms（「假定每轮 200ms」累加计时形态实测 2410ms；生产默认间隔下实测 +60633ms）`（`HeartbeatRhythmTest.java:73`；1 test / 1 failed / 0 errors，2.541s）。逐字还原后转绿：`git hash-object` 与 `HEAD:...SenderLoop.java` 同为 `0a1944b7`、`git diff HEAD` 为空、`grep RED PROBE` 零残留；还原后单跑 **1/1 通过**（3.102s，非 UP-TO-DATE 实跑），agent 子项目全量复跑 **66 通过 / 0 失败**。 |
| T-01/T-02 测试健壮化（`ee10b86`） | 反向验证期间发现的可诊断性问题，判据/阈值/断言结构未动：①单帧读超时由 `INTERVAL_MS * 4`（1200ms）改为固定常量 `FRAME_WAIT_MS = 3000`——读超时只作「帧彻底不来」的兜底，节奏判定一律走帧到达时刻的实测差值，放宽不削弱断言；而 1200ms 在本机五树并行 gradle 下离调度抖动太近。**收益实证**：反向验证时首条心跳实测 2410ms 落在 3000ms 内，拿到的是断言红（贴出实测值），而不是 1200ms 下的 `SocketTimeoutException` 超时红——失败读数从「帧没来」升级为「帧来了、晚了 5 倍」。②首帧读加 `SocketTimeoutException` 显式失败消息（贴等待上限、期望上限与累加形态预期读数）。③首帧间隔断言的失败消息补实测锚点：原先凭估算写的「1446ms」改为实测 2410ms（与另一处 `INTERVAL_MS * 8 = 2400ms` 的估算一致）。 |
| T-01/T-02 未验前提 | 真机口径（计划 §六 1~4：`runIde` 沙盒挂 `easy-code-demo`、静等 2 分钟、`增强捕获在线态切换: true -> false` 应为 0 条）**未验**——本树按分工只跑定向单测，`runIde` 真机验收与全量测试 / `verifyGate` / `verifyPlugin` 由 Astra 合流后统一跑一次。另：「首条心跳 ≤1.5 倍间隔、3s 窗口 ≥6 条」是在间隔压到 300ms 的刻度上取得的读数，生产 10s 刻度下未直接实测（墙钟差判定与刻度无关，但缺 10s 刻度的直接读数）。 |
| T-05（状态行文案真实性） | `a430c12`。①`AgentSession.livenessFrameSeen`（`@Volatile`，读线程写/巡检线程读）：是否收到过 **HELLO 之外的存活帧**（HEARTBEAT/EVENT）——读循环对所有帧都刷 `lastSeen`，故「刚连上」与「曾有心跳后停摆」在 `lastSeen` 上同形，此前无从区分；HELLO 只证明 socket 建立、不证明应用侧越过启动期，不计入。②`sweepOffline` 按该位分两类（**可同时置位，非互斥**——独立复核订正；叙事以停摆优先）：曾收到存活帧 → `heartbeatDropDetected`（语义不变）；从未收到 → 新增 `firstHeartbeatPending`。③`AgentStatusSnapshot` 加 `firstHeartbeatPending`（默认 false）与 `heartbeatWindowSeconds`（默认 `ONLINE_WINDOW_MS/1000`，带默认值故既有调用点不动），`agentStatusText` 过期态由两分支扩为三分支。④文案：`heartbeatLost` 去掉「进程可能已卡死」因果断言 → zh「已 {0} 秒未收到应用侧心跳，已回落控制台捕获」/en「No application heartbeat for {0} seconds, fell back to console capture」（旧 zh「应用侧心跳中断（进程可能已卡死），已回落控制台捕获」）；新增 `firstHeartbeatPending` zh「连接已建立，尚未收到应用侧心跳（等待首条心跳）」/en「Connection established, no application heartbeat received yet (waiting for the first heartbeat)」，中英键集合全等（`BundleParityGuardTest` 绿）。⑤测试 `AgentStatusTextTest` +5、`AgentHeartbeatWindowTest` +3（含「仅收到 HELLO 仍算首条心跳未到」——用 `lastSeen` 推进证明 HELLO 真被读线程处理，防「帧没被读」的空绿），既有「心跳中断」断言按新文案更新（未削弱为「非空」）。**反向验证**：把该支临时去掉（`heartbeatDropDetected.set(dead.any { it.alive })` + 过期态两分支）→ 5 个新增用例 +「五态互不重复」共 6 红，报错原文即现场错误叙事「pending=已 30 秒未收到应用侧心跳，已回落控制台捕获 lost=…」（两态收敛成同一句）；`git checkout --` 逐字还原后转绿。定向测试 112 绿（`log/agent` 全包 + 两个 bundle 守卫 + `SettingsTruthTest`）。 |
| T-03 premain 通道重连（W2，commit 6178713） | 代码：`acceptPremain` 改**循环 accept**（`MAX_PREMAIN_RECONNECTS=32` 上限防抖动热循环），每次回连仍交既有 `onPremainConnected`（同 pid 有活动会话丢弃重复连接的判据未动）。通道生命周期从「accept 一次即关」改为**绑定到本次运行**：`PREMAIN_ACCEPT_TIMEOUT_MS` 语义收窄为「等首次回连」（accept 的 soTimeout），认领后转 30s 空转拍复核；**运行结束信号接在 `SqlLogManager.onConsoleRemoved` → `AgentManager.onRunEnded`**（`RunConfigurationProcessListener` 类注释指定的项目停止语义承载点；放在 capturing 守卫之前 + `getServiceIfCreated`，不为关通道实例化服务）。`processTerminated` 未采纳——它对 JUnit/HTTP 等非主配置进程也会触发，误关通道会让正在跑的应用丢掉重连路径。另设两重不依赖钩子的兜底：pid 消亡判定（`ProcessHandle`，pid 取 agent 自报 HELLO = 目标 JVM 自身 pid）与 12h TTL；`prunePremainChannels` 判据同步改写（不再把服役中的已认领通道当 stale）。泄漏收口：`PremainChannel` 实现 `Disposable` 并注册进项目 disposable 树，`closePremainChannel` 单一出口同时释放 socket / accept 线程 / disposer 条目，`onRunEnded`、`shutdownAll`、`dispose` 三路共用。 |
| T-03 测试与反向验证 | `AgentManagerPremainTest` 14 例全绿（新增 4 例：摘除后同 pid 回连自动恢复在线、多轮回连通道与会话规模有界、运行结束关闭本轮通道且连续 7 次运行不累积、回连达上限通道退休；既有「回连后通道被摘除」断言按新语义改为「认领后留表等重连」——旧断言正是根因形态）。反向验证：临时把重连上限压到 1（还原旧单次 accept 语义）→ 4 例转红（3 例 `java.net.ConnectException: Connection refused: connect`，1 例「等待超时：通道未认领」）；逐字还原（`git diff` 为空、探针零残留）→ 14 例转绿。真机连续多次运行的通道回收未验（见下行）。 |
| T-03 未验前提 | 真机上「应用停止 → 主控制台移除」这条钩子在增强捕获**未处于 capturing 态**时不会触发（桥未挂载即无移除检测），此时通道靠 pid 消亡判定与 12h TTL 兜底回收；`ProcessHandle` 对 IDE 子进程的存活判定在真机未实测（查询失败按存活处理，退化形态 = 回到 T-03 之前的「无重连」而非误关正在服役的通道）。 |

### T-04（空态诊断文案换行）实施记录

| 项 | 内容 |
|----|------|
| 代码 | `CaptureDiagnostics.kt` 新增纯函数 `wrapEmptyTextLines(text, widthPx, measure)`（像素宽度贪心折行，中文按字符、英文优先空白处断，宽度 clamp 到 200px）；`SqlLogPanel.kt` 新增顶层 `applyEmptyTextLines(status, text, widthPx, measure)`（`clear()` + 逐视觉行 `appendLine`）与面板内 `applyEmptyText` / `renderEmptyText` / `emptyTextWidthPx`；两处渲染点（`syncDiagnostics`、`buildListPane`）改走 `applyEmptyText`，宽度取 `list.width` 扣内边距、首帧兜底 400px、挂 `ComponentListener` 在 resize 时重排。`captureDiagnosticsText` 语义行切三行（捕捉中+增强 / 控制台+计数 / 提示）。commit `bc6e2f4` |
| 测试 | 定向绿：`CaptureDiagnosticsTest` 17 例（+6 折行用例）、新增 `EmptyTextRenderTest` 4 例（用真实 `StatusText`，不 mock），全 0 失败。反向验证：把渲染点改回 `status.setText(纯文本)`（修复前形态）→ `EmptyTextRenderTest` 4 例红；逐字还原 → 绿 |
| 真机 | **未做**——窄窗截断/换行观感只能在 `./gradlew runIde` 沙盒截图确认，本次未跑，P4 仍**待验** |
| 前提推翻 | 计划 §四 P4 写的「`JBList` 空态文本（`JBLabel`）渲染 HTML」**实测不成立**：`JBList.getEmptyText()` 返回 `com.intellij.util.ui.StatusText`（2024.3.7.1 与 2026.2.1 两个发行版 `javap` 双向确认），内容落在 `SimpleColoredComponent` 上——不解析 HTML、不把 `\n` 当换行、超宽只裁不折。故本任务**未走 HTML 路线**（照原计划会把 `<html><body …>` 字面量画到面板上），改走「自算折行 + `StatusText.appendLine` 逐视觉行写入」；宽度声明（`body style='width:Npx'`）随之作废，改为按 `list.width` 折行 |
