# T-PanweiInsertCapture：磐维库上 SQL 日志只捕获到 SELECT、INSERT 缺失（2026-11-10）

> 用户报障（2026-11-10）：「Panwei 数据库的 SQL 日志只能捕获到 select 语句，insert 语句没有捕获到」。
> 约束：测试服务器不可用，**不能连真库复现**；本计划全部结论来自静态取证（jar 反编译 / 第三方框架源码 / 仓内代码路径），
> 真机复验条款单列在 §九，未取得前不得写「已验证」。

---

## 一、结论（2026-11-10 用户截图到位后**订正**，第一版结论作废）

> **第一版（纯静态推演）的结论是「形态缺口 G1/G2/G3」，已被截图推翻，勿按第一版派活。**
> 截图硬事实见 §2.2：应用是 iBatis 2.x、INSERT 在控制台打的就是解析器支持的标准 `{pstm-N}` 三行组、
> 面板里的 SELECT 条目带耗时与 `[Panwei（磐维）]` 角标（agent 独有字段）。

症状的真实结构是**两条通道各有一半**：

1. **控制台通道**：INSERT 的日志形态**完全可解析**，但 agent 在线后桥被让位（`SqlCaptureGate.bridgeSuppressed()`，
   `SqlLogConsoleBridge.kt:933`；`CaptureActivityFallback.everReceivedAgentEvent` 一旦为真永久不再回落，
   `:986` / `:1048` / `:1086`）——这行**从未进入解析器**；
2. **agent 通道**：同一秒的 SELECT 产出正常，同一条 INSERT 没有产出。

**而且「INSERT 的执行方法没织上」这一层差异也不成立**：iBatis 2.3.4 的 `SqlExecutor` 对查询与更新
**都调 `ps.execute()`**（源码实证，§3.4 G4）。剩下的候选收敛到三类——**实例级**（这条 PS 未进
`StatementRegistry` ⇒ 取不到 SQL ⇒ 静默跳过）、**线程级**（`NestGate` 深度残留 ⇒ 该线程后续全抑制）、
**事件组装异常被自吞**（`SqlHookCallbacks.onExecuteExit` 的 `catch (Throwable)`），见 §四。

**已排除（本用户）**

| 原假设 | 排除依据 |
|---|---|
| G1 MyBatis `statementType="STATEMENT"` → `==>  Executing:` | 应用不是 MyBatis（logger 是 `j.s.PreparedStatement` = iBatis 日志代理） |
| G2 iBatis plain Statement → `{stmt-N} Statement:` | 控制台里是 `{pstm-N} Executing Statement:` |
| G3 iBatis 批处理不打印 | 控制台里有完整的 `Executing Statement:` / `Parameters:` / `Types:` 三行组 |
| 「`executeUpdate()` 未织入」 | iBatis 根本不调 `executeUpdate()`，查询与更新都走 `execute()`（§3.4 G4） |

**仍然成立的部分**：G1/G2/G3 是**真实存在的识别面缺口**（换一个工程形态仍会中），但与本次报障无关，
在 §五 降级为顺带修（F4~F7），不再作为主线。

**与磐维（PanWeiDB）的关系：无。** 三面登记（`WeaveSpecs.java:134-136` 语句面 + `:216` prepare 面 +
`DriverRegistry.java:72` connect 面）经 javap 与 V1/V2 两代 jar 逐方法核对一致，`execute()` / `executeUpdate()` /
`executeBatch()` 全在织入面内（§3.4）。缺口是方言无关的。

---

## 二、现象与证据边界

### 2.1 已知（用户口述 + 2026-11-10 截图）

- 数据库：磐维 PanWeiDB（Oracle 兼容模式）
- 应用：**iBatis 2.x + log4j2**（控制台 logger 名 `j.s.PreparedStatement` / `j.s.Connection` 即 iBatis 的
  `PreparedStatementLogProxy` / `ConnectionLogProxy`）
- 症状：SQL 日志面板只出现 SELECT，INSERT 一条都没有
- 不可复现：测试服务器（103.36.220.245）不可用，无法连真库跑 e2e

### 2.2 用户截图给出的硬事实（2026-11-10，两张）

| # | 事实 | 判读价值 |
|---|---|---|
| S1 | 控制台里 INSERT 打的是 `{pstm-100021} Executing Statement:    INSERT INTO bs_rwd_group_info_his (…` + `{pstm-100021} Parameters: [...]` + `{pstm-100021} Types: [...]` | **形态标准**：解析器支持这一档 → G2/G3 排除 |
| S2 | 面板 6 条 SELECT 每条都带耗时（6/11/90/22/28/7ms）与 `[Panwei（磐维）]` 角标 | 这两个字段只有 `AgentEventAdapter` 会填（`databaseName` 全仓仅 agent 适配器与日志文件导入两处赋值，`AgentEventAdapter.kt:69` / `SqlLogFileReader.kt:358`）⇒ **条目来自 agent，增强捕获在线** |
| S3 | 面板列表 `#4`~`#9` 全为 SELECT，末条 `#9` 落在列表底部（自动滚动到底） | INSERT 不在列表里，不是「滚上去没看见」 |
| S4 | 设置页：`SQL 前缀 = Executing Statement:`、`参数前缀 = Parameters:`、启用混合前缀；`SQL 去重` 勾选；时间来源＝日志时间 | 前缀是 20261106 那次手工改的；去重开启 ⇒ 还要排除「被折叠成 ⨉N」 |
| S5 | 状态栏被截图裁在 `控制台: 已挂载 1 个, 已扫描 7,055 行 |` 处 | 增强段（已挂载 pid / 已收事件数）缺失，见 §2.3 |

### 2.3 未取得（还需三样，都很便宜）

1. INSERT 的 `Types:` 行**之后**紧跟的几行（是否抛异常）——排除「应用侧没执行成功」；
2. 状态栏**完整一行**（`|` 之后的「增强：已挂载 pid=… ｜已收事件 N 条」）——`已收事件数` 与面板 9 条对不对得上，直接判
   「事件没产」还是「产了但没显示」；
3. 应用控制台里有没有 `[SqlVisAgent]` 开头的行（`系统性盲区` / `登记盲区` / `fallbackCount`）——`StatementRegistry`
   盲区与钩子降级都会打这两类行。

### 2.4 已排除（历史同类报障，勿重复踩）

| 历史任务 | 症状相似点 | 真实根因 | 与本次的关系 |
|---|---|---|---|
| `T-AgentCudSource`（20260916） | 「只有 SELECT 捕获到、INSERT/UPDATE/DELETE 捕获不到」 | `StackLocator` 调用方缓存把 null 位置永久缓存（**条目在、位置缺**），不是采集丢失 | 已修；本次是**条目整条不存在** |
| `T-CrossChannelDedupe`（20261105） | 「INSERT 没有耗时和执行位置」 | 控制台条与 agent 条同次执行并存、去重键不同源 | 已修；本次不是「两条」而是「零条」 |

---

## 三、代码路径与取证

### 3.1 两条采集通道

```
控制台通道：RunConfiguration 控制台行 → SqlLogConsoleBridge / consoleFilterProvider(EP)
            → MyBatisLogParser / HibernateLogParser / JdbcTemplateLogParser / ShardingSphereLogParser
            → LogSqlEntryBuffer.addEntry

agent 通道：JVM 内字节码织入（StatementWeaver）+ 连接代理（出厂关）
            → SqlHookCallbacks.onExecuteExit → WovenEvents.emit → EventBus
            → AgentManager → AgentEventAdapter.toEntry → LogSqlEntryBuffer.addEntry
```

两条通道最终都进同一个 `LogSqlEntryBuffer`，**入库前没有任何按语句类型的闸门**（`SqlLogEntryBuffer.kt:436` 起的
`addEntryInternal`：`paused` / `isFiltered`(用户关键字) / `suppressKeys`(用户屏蔽规则) / 去重融合，仅此四类）。
所以「INSERT 整类消失」只能发生在**产出侧**。

### 3.2 通道让位（放大器）

- `SqlCaptureGate.paused` 在 agent 在线且应用侧 ACK 确认采集时置真 → 桥短路；
- `CaptureActivityFallback` 只兜「agent 从头到尾零事件」这一种形态：`everReceivedAgentEvent` 一旦为真
  （`SqlLogConsoleBridge.kt:1048`），`evaluateConsoleLine` 直接 `return@run false`（`:1086`），此后不再解封；
- 结论：**agent 只要「部分语句能采到」，控制台通道就永久沉默**。这是「部分缺失」被放大成「整类缺失」的机制。

### 3.3 解析器识别面矩阵

`MyBatisLogParser`（`src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt`）：

| 日志形态 | 产生者 | 现状 | 代码位置 |
|---|---|---|---|
| `==>  Preparing: <sql>` / `==> Parameters: …` | MyBatis PS（默认 `PREPARED`） | ✅ 已识别 | `feedInto` 分支 1/4 |
| `==>  Executing: <sql>` | MyBatis `SimpleStatementHandler`（mapper 上 `statementType="STATEMENT"`） | ❌ 未识别（G1，**非本次**：应用不是 MyBatis） | 无任何分支；`grep "Executing:"` 在 `src/main` 命中 0 处 |
| `{pstm-N} Executing Statement:` + `Parameters:` + `Types:` | iBatis 2.x PreparedStatement | ✅ 已识别（**本次 INSERT 就是这一档，见 S1**） | `feedIbatis` `:229-252` |
| `{conn-N} Preparing Statement: <sql>` | iBatis 2.x `Connection.prepareStatement` | ✅ 消费不产条目（决策 D-2，防与 execute 重复） | `:234-238` |
| `{stmt-N} Statement: <sql>` | iBatis 2.x plain `Statement`（`Connection.createStatement`） | ❌ 未识别（G2，**非本次**） | `:230`、`:436-448` |
| iBatis 批处理（`executeBatch`） | iBatis 2.x batch | ❌ 驱动层不打印（G3，**非本次**：本次有完整三行组） | 见 §3.4 |

`HibernateLogParser` 的 `looksLikeSql`（`:409-413`）覆盖 SELECT/INSERT/UPDATE/DELETE/MERGE/WITH，但要求行首即 SQL；
`==>  Executing:` 行首是箭头，不会被它接住。

### 3.4 四个形态事实的实证（全部离线可复现）

**G1：MyBatis `StatementLogger`**（非本次）

```
$ javap -p -c /tmp/StatementLogger.class | grep ldc
  49: ldc  #51   // String  Executing:
```

`StatementLogger` 由 `SimpleStatementHandler` 使用（`statementType="STATEMENT"`，或 MyBatis-Plus 里显式声明
`<insert ... statementType="STATEMENT">`）。它只在 `execute*` 时打印一行 `==>  Executing: <sql>`，
**不打印 `Preparing:`、不打印 `Parameters:`**——解析器两个入口都接不住，整条语句连「有 SQL 无参数」的条目都产不出来。

**G2：iBatis 2.x `StatementLogProxy`**

```java
// _ibatis-research/src/com/ibatis/common/jdbc/logging/StatementLogProxy.java
if (EXECUTE_METHODS.contains(method.getName())) {
    log.debug("{stmt-" + id + "} Statement: " + removeBreakingWhitespace((String) params[0]));
}
```

注意与 PS 的差别：PS 是 `{pstm-N} Executing Statement:`（`PreparedStatementLogProxy`），plain Statement 是
`{stmt-N} Statement:`——**标记前缀和关键字都不同**。`feedIbatis` 的前置判断（`MyBatisLogParser.kt:230`）只认
`{pstm-` / `{conn-`，`{stmt-` 直接漏过。

**G3：iBatis 2.x 批处理不打印**

```java
// BaseLogProxy 静态块
EXECUTE_METHODS.add("execute");
EXECUTE_METHODS.add("executeUpdate");
EXECUTE_METHODS.add("executeQuery");   // ← 没有 executeBatch / addBatch
```

`SqlExecutor.executeBatch` 走 `ps.addBatch()` + `ps.executeBatch()`，两个方法都不在 `EXECUTE_METHODS` 里
→ **一行日志都不产生**。批处理 INSERT（iBatis 里最常见的批量写形态）在控制台通道结构性不可见。

**G4：iBatis 查询与更新走同一个执行方法（**本次最关键的一条**）**

```java
// _ibatis-research/src/com/ibatis/sqlmap/engine/execution/SqlExecutor.java
// executeUpdate（INSERT / UPDATE / DELETE）
ps = prepareStatement(statementScope.getSession(), conn, sql);
statementScope.getParameterMap().setParameters(statementScope, ps, parameters);
ps.execute();                       // ← 不是 executeUpdate()
rows = ps.getUpdateCount();

// executeQuery（SELECT）
ps = prepareStatement(statementScope.getSession(), conn, sql);
statementScope.getParameterMap().setParameters(statementScope, ps, parameters);
ps.execute();                       // ← 同一个方法
rs = handleMultipleResults(ps, ...);
```

**含义**：`StatementWeaver` 的织入面（`EXEC_NAMES` 含 `execute`）对 SELECT 与 INSERT **完全等价**，
「INSERT 的方法没织上」不可能成立。所以 agent 侧的差异只能来自**实例**（这条 PS 未进 `StatementRegistry`）、
**线程**（`NestGate`）或**事件组装抛异常被自吞**——三者与语句类型都无关，与「同一次会话里某个 PS/某条线程」有关。

**磐维侧无缺口（对照）**：`javap -classpath panweidb-jdbc-2.0.0.jar org.panweidb.jdbc.PgPreparedStatement`
实测 `execute()` / `executeQuery()` / `executeUpdate()` / `executeLargeUpdate()` 均有自有声明；
`PgStatement` 声明 `executeBatch()` / `executeLargeBatch()` / `addBatch(String)` / `clearBatch()`；
`PgConnection` 声明 9 个 `prepareStatement`/`prepareCall` 重载（全部以 `String` 打头，命中 `PREPARE` 形态的
`desc.startsWith("(Ljava/lang/String;")`）。与 `WeaveSpecs.java:126-136`、`:214-216` 的登记逐条对齐。

**测试面缺口（顺带登记）**：`StatementWeaverVendorCoverageTest.磐维字节层_…`（`:558-590`）只断言「整类 changed + 过 JVM 校验」，
**不断言逐方法命中**。若某天 `executeUpdate` 因字节形态被跳过，该类仍会「changed」（因为 `execute` 织上了），用例照样绿。

---

## 四、根因链（截图到位后重排）

**前提（已由截图与源码锁死）**：agent 在线（S2）→ 控制台桥让位（§3.2）→ 控制台里那条标准形态的 INSERT（S1）
没有被任何通道接住。iBatis 的 SELECT 与 INSERT 走**同一个** `ps.execute()`（G4），所以差异不在方法面。

### H1（首选）这条 PS 未进 `StatementRegistry` ⇒ SQL 取不到 ⇒ 静默跳过

`SqlHookCallbacks.onExecuteExit` 的 EXEC_CTX 分支：无登记 → `getOrCountUnresolved` 返回 null → `return`（不产事件）。
触发条件与语句类型无关，但**同一次会话里「某些 PS 登记上了、某些没登记上」是可能的**，例如：

- 该 PS 由**未被织入的连接实现** prepare（连接池代理由另一代 API 创建、或驱动用了另一个类）；
- prepare 出口织入**在该类上没生效**（`PgConnection` 的某个重载在 V1/V2 之外的新版驱动里改名/移位）；
- `registerWithDelegates` 解包失败，登记挂在包装器上而执行落在内层（Oracle 包装器同款形态，`StatementRegistry` KDoc 已记）。

**判据（离线可做，§7.5）**：让 demo 在**同一次会话**里连跑 SELECT + INSERT，看宿主 stderr 是否出现
`[SqlVisAgent] 织入通道登记盲区：无登记 PS 执行被跳过 psClass=…`（`StatementRegistry.getOrCountUnresolved`，
`logLevel=debug` 时打一次；累计 ≥5 必报）。出现 = H1 成立且能直接看到 psClass。

### H2 该线程的 `NestGate` 深度残留 ⇒ 后续全抑制

`NestGate` 按线程维护 depth / innerEmitted（`NestGate.java`）。若某条 `execute` 帧的出口钩子没执行（只有
token≠0 的帧才入栈/出栈，理论上配平），该线程会永久处于「内层已产出」态，**后续每条语句都被抑制**。
若 INSERT 常年跑在一条专用线程（历史表写入/审计线程很常见），表现就是「这条线程的语句全丢」。

**判据**：`-Dsqlvis.agent.nestGate=false` 重启应用后 INSERT 出现 = H2 成立（该属性已存在于 `NestGate`）。

### H3 事件组装抛异常被自吞

`onExecuteExit` 外层 `catch (Throwable)` 静默（采集红线）。`StackLocator.locate` / `StackDigest.build` /
`params.snapshot(sql, …)` / `EventBus.offer` 任一抛出都会**丢这一条**。该形态与 SQL 文本/参数规模相关——
本次 INSERT 正是**36 列 36 参**的大语句，与 SELECT 的形态差异最大。

**判据**：把 `logLevel` 调到 `debug` 并观察宿主 stderr 是否有异常行；或离线用 36 参夹具跑
`WovenEvents`/`ParamCapture` 的单测（§7.2 用例 7）。**当前实现无任何出口**——这是 F1 要补的。

### H4 应用侧问题（低）

INSERT 在 `Types:` 之后抛异常（业务/约束）→ 但异常路径**也会产事件**（`errorText`），所以除非异常发生在
`setParameters`（execute 之前），否则不成立。判据见 §2.3 第 1 项。

### H5 入库后被折叠/屏蔽/筛选（低）

- 跨通道折叠（`crossChannelKey`）只折叠「同一次执行的两份观测」；
- `filterKeywords` / `SqlSuppressRuleManager` 默认空；类型筛选默认全选（`FILTERABLE_TYPES`，`LogEntryFiltering.kt:236-246`）；
- 但 `SQL 去重` 勾着（S4）：若同一 INSERT 之前执行过，会折叠成 ⨉N 挂在前一条上（前一条得看得见才算排除）。
判据：关掉去重、清空过滤后仍缺 → 排除 H5。

---

## 五、修法设计

### F1（主线）：让「让位期间被丢弃的可解析 SQL 行」可见

本次现场最刺眼的不是缺口本身，而是**这个状态完全不可见**：控制台里有可解析的 INSERT，agent 让它去了，桥按设计丢弃，
面板零信息、状态栏零信息。修法：

- 在 `processConsoleLine` 的 `bridgeSuppressed()` 短路点（`SqlLogConsoleBridge.kt:1148` 附近，`paused` 分支）丢弃前做一次
  **廉价嗅探**——复用既有 `sniffLogTimestamp` 的门控成本（每行 3 次 `contains`），命中即计数
  `CaptureDiag.suppressedSqlLines`（**不建条目、不入队**，纯计数）；
- 出口：状态栏 agent 段追加一段「让位期间丢弃可解析 SQL 行 N」，只有 N>0 才显示；
- 意义：本次这种「控制台明明有、面板就是没有」的现场，下次一眼可判，不用再靠人肉比对控制台。

### F2（主线）：agent 侧两条静默路径加出口

`SqlHookCallbacks.onExecuteExit` 目前有两处**完全无出口**的丢弃：

| 丢法 | 位置 | 现状 |
|---|---|---|
| 事件组装抛异常 | `onExecuteExit` 外层 `catch (Throwable)` | 静默，计数器都没有 |
| 被 `NestGate` 抑制 | `if (!NestGate.exit(token)) return;` | 静默，无计数 |

修法：各加一个 `AtomicLong`（`assembleFailures` / `suppressedByNest`），随心跳（`SenderLoop` 的 `unreg`/`dropped`
同款通道）带到 IDE，状态栏 agent 段 >0 才追加显示；`logLevel=debug` 时组装失败打一次栈（首次）。
T-07 通道守卫：新增心跳键须同步 `AgentProtocolSyncTest` 的白名单。

### F3（主线）：`StatementRegistry` 盲区按类聚合点名

`unresolvedCount` 现在只有总数 + debug 级别的首条 psClass。H1 若成立，需要的是「**哪个 PS 类**一直取不到 SQL」：

- 盲区按 `psClass` 聚合（有界 ≤8，形态照 `CaptureBudget.noteUnregisteredDriver` 的 `unreg`）；
- 心跳带出，状态栏显示（与既有「未登记 JDBC 实现」同款的具名事实）。

### F4（顺带，原 F1）：补 MyBatis `Executing:` 形态（G1）

`MyBatisLogParser` 新增一条分支（与 `Preparing:` 同级，不新造解析器，避免同一条语句双条目）：

- **不新增设置项**：`Executing:` 是 MyBatis `StatementLogger` 的固定文案，按内建第二形态处理；
  但只在 `mixedPrefix = true`（默认，宽容匹配）时生效——`mixedPrefix = false` 的语义是「只认用户配置的行首前缀」，
  硬塞内建形态会破坏该模式的契约（该模式下 `Executing:` 行仍按噪声处理，属已知边界，写进类注释）；
- 该形态**无参数行**（plain Statement 由 MyBatis 直接拼 SQL，`Parameters:` 不会出现）→ 命中即产条目、`params = emptyList()`，
  并且**终止当前续行态**（它本身是完整语句）；
- 与 `Preparing:` 的关系：`SimpleStatementHandler` 与 `PreparedStatementHandler` 由 MappedStatement 的 `statementType` 二选一，
  正常情况下不会对同一次执行同时打印两种行（P-2/P-3 证伪）。**不额外做跨形态去重**——万一前提不成立而两种行都出现，
  交给 `LogSqlEntryBuffer` 既有去重（同 SQL 指纹折叠 ⨉N），不新增第二套折叠逻辑；类注释里写清这条边界。

### F5（顺带，原 F2）：补 iBatis `{stmt-N} Statement:` 形态（G2）

`feedIbatis` 改造：

- 前置判断扩为 `{pstm-` / `{conn-` / `{stmt-` 三选一（仍是一次 `contains` 级别的成本，不引正则到热路径）；
- `IBATIS_MARKER` 分组扩到 `(pstm|conn|stmt)`；
- `stmt` 分支：`rest.startsWith("Statement:")` → 产条目（SQL 取关键字后文本，`params = emptyList()`），
  其它 `{stmt-N}` 行（`Closing statement` 等）只消费；
- 顺序陷阱：`{stmt-N} Statement:` 必须**先** `closePending(bucket)` 再产条目，与 `onIbatisExecuting` 同序，
  否则上一条 MyBatis 语句会被吞进本条的上下文。

### F6（顺带，原 F3）：iBatis 批处理（G3）——控制台补不了，改为「可见化 + 引导」

驱动层不打印是框架事实，插件无法从控制台侧补。处置：

- 在面板空态/状态栏诊断里增加一条**具名事实**：「iBatis 批处理（`executeBatch`）不产生控制台 SQL 行，需开启增强捕获」；
- 触发条件不做「猜」：只在会话内**已识别到 iBatis 形态**（出现过 `{pstm-`/`{conn-`/`{stmt-`）且 `agent 未在线` 时提示，
  避免对 MyBatis/Hibernate 用户误报；
- 增强捕获侧确认：`PgStatement.executeBatch()` / `executeLargeBatch()` 已在 BATCH 形态内（`WeaveSpecs` 语句面），
  该形态产**一条**事件（SQL 取 prepare 登记，`SqlHookCallbacks.onExecuteExit` 的 `isBatchOp` 分支），与产品语义一致。

### F7（顺带，原 F4）：控制台侧未识别行计数

`LogParseDiag`（`LogThreadRouter.kt:223-263`）新增计数器 `unrecognizedSqlLine`：

- 计数点：行内出现 `Executing:`、`{stmt-`、`{pstm-` 但未被任何分支消费时 +1（只在 `feedInto` 末尾的噪声分支判一次，
  前置仍是廉价 `contains`，不引入热路径正则）；
- 出口：并入 `takeDeltaReport()` 的增量诊断行，并在 SQL 日志面板空态诊断行里显示（有界，只报计数与前缀样本）；
- 意义：下次再出现「某类语句整类不见」，用户与支持方第一眼就能看到「有 N 行 SQL 日志没被识别」，而不是零信息。

### F8（明确否决）：让位策略不动

否决「agent 在线时也放控制台条进来」：

- 会立刻回到 `T-CrossChannelDedupe` 修掉的「同一次执行两条、一条无耗时无位置」现场；
- `CaptureActivityFallback` 的 `everReceivedAgentEvent` 是 2026-11-05 `T-CaptureGateStability P2` 专门为「两次操作间隔 >10s
  就解封、解封瞬间产无耗时条目」加的闸门，翻案等于回退该修复。

替代做法是 F1/F2/F3 的可见化，把「为什么缺」告诉用户，而不是用双通道抖动换覆盖。

### 不做

- 不按方言分支（缺口与方言无关，按方言修必留同类窟窿）；
- 不动 `LogSqlEntryBuffer` 的入库闸门（不是入库侧问题）；
- 不新增「写语句专用通道」；
- 不在本任务里改 `NestGate` 语义（只在 F2 加计数；语义变更需单独立项 + 反向验证）。

---

## 六、影响面与写集

| 文件 | 改动 |
|---|---|
| `src/main/kotlin/…/log/SqlLogConsoleBridge.kt` | F1（让位丢弃计数 + 嗅探）、F7 诊断行接线 |
| `src/main/kotlin/…/log/CaptureStatusBar.kt` | F1/F2/F3 三段新读数进状态栏（>0 才显示） |
| `src/main/kotlin/…/log/agent/AgentManager.kt` | F2/F3 心跳新键的接收与状态装载 |
| `sub-projects/sql-visualizer-agent/…/hook/SqlHookCallbacks.java` | F2：`assembleFailures` / `suppressedByNest` 计数 |
| `sub-projects/sql-visualizer-agent/…/hook/NestGate.java` | F2：抑制计数（语义不动） |
| `sub-projects/sql-visualizer-agent/…/hook/StatementRegistry.java` | F3：盲区按 psClass 聚合（有界 ≤8） |
| `sub-projects/sql-visualizer-agent/…/transport/SenderLoop.java` + `AgentProtocol.kt` | F2/F3 心跳新键（两侧同步 + 协议守卫白名单） |
| `src/main/kotlin/…/log/MyBatisLogParser.kt` | F4（`Executing:`）、F5（`{stmt-`）、F7 计数点 |
| `src/main/kotlin/…/log/LogThreadRouter.kt` | F7：`LogParseDiag` 新计数器与增量报告文案 |
| `src/main/resources/messages/*.properties` | 新增诊断与提示文案（双语） |
| `src/test/kotlin/…/log/*`、`sub-projects/sql-visualizer-agent/src/test/java/…` | §七 用例 |

**不碰**：磐维三面登记（`WeaveSpecs` / `DriverRegistry` 的磐维条目——已核对无缺口）、`update/update.json`。

---

## 七、测试计划

### 7.1 夹具来源（全部离线，权威形态）

| 夹具 | 来源 | 用途 |
|---|---|---|
| `==>  Executing: INSERT INTO …` + `<==    Updates: 1` | MyBatis 3.5.19 `StatementLogger` 字节码实证（§3.4 G1） | F4 正向 |
| `{stmt-100023} Statement: INSERT INTO …` | iBatis 2.3.4 `StatementLogProxy` 源码（§3.4 G2） | F5 正向 |
| 用户 20261106 真机 INSERT 语料（36 参数 + `Types:`） | `src/test/kotlin/…/log/IbatisLogParseTest.kt:52-63` 既有夹具 | 回归（F5 不得破坏 `{pstm-}` 解析） |
| 用户 20261110 截图的 `{pstm-100021}` 三行组 | 本次 §2.2 S1（36 列 36 参 INSERT） | agent 侧组装用例（F2 用例 7） |

### 7.2 用例

**主线（F1~F3）**

1. 让位丢弃计数：`paused` 态喂入一条 `{pstm-N} Executing Statement:` 行 → `CaptureDiag.suppressedSqlLines` +1，且**不产条目**（入队数 0）；
2. 让位丢弃计数只在 SQL 行上递增（噪声行不计数）——用 `Parameters:` 行与业务日志行各喂一次做负对照；
3. agent 组装失败计数：用会抛异常的注入点（如 `params.snapshot` 打桩抛）跑一条 → `assembleFailures` +1 且宿主执行语义不变；
4. `NestGate` 抑制计数：外层 + 内层各一条 execute，内层产出后外层被抑制 → `suppressedByNest` +1；
5. 盲区按 psClass 聚合：同款 PS 类未登记执行 N 次 → 心跳 `unreg` 式载荷只出现一个类名 + 计数 N。

**顺带（F4~F7）**

6. `MyBatisLogParserTest`：`Executing:` 行产条目、`params` 为空、`restoredSql` 与原 SQL 逐字符一致（不引号化、不吞行）；
7. 两种形态各自独立产条目；同一 SQL 先 `Executing:` 后 `Preparing:` 时由 buffer 既有去重折叠为 1 行 ⨉2（钉住既有行为，不新增折叠）；
8. `IbatisLogParseTest`：`{stmt-N} Statement:` 产条目；`{stmt-N}` 其它行（`Closing statement`）只消费不产条目；
9. `{pstm-}` 三行组回归全绿（既有 11 例）；
10. `LogParseDiag`：未识别行计数随 `Executing:`/`{stmt-` 行递增，`takeDeltaReport()` 文案含该增量；
11. 续行态：`Executing:` 行出现时终止上一条的 `continuing`（不得把 `==>  Executing:` 并进上一条 SQL）。

### 7.3 反向验证（必须成对，逐条给两次读数）

| 探针 | 摘除后必须变红 |
|---|---|
| 注释 F1 的让位计数点 | 用例 1、2 红 |
| 摘除 F2 的 `assembleFailures` 计数 | 用例 3 红 |
| 摘除 F2 的 `suppressedByNest` 计数 | 用例 4 红 |
| 摘除 F3 的按类聚合（退回总数） | 用例 5 红 |
| 注释 F4 的 `Executing:` 分支 | 用例 6、7、10 红，其余保绿 |
| 注释 F5 的 `stmt` 分支 | 用例 8 红，`{pstm-}` 11 例保绿 |
| 注释 F7 的计数点 | 用例 10 红 |

### 7.4 回归面

`IbatisLogParseTest`、`MyBatisLogParser*`、`LogThreadRouter*`、`SqlLogConsoleBridge*`、`LogEntryFiltering*`、
`CaptureStatusBar*`、agent 侧 `CaptureRulesTest` / `NestGateTest` / `StatementRegistryLimitTest` / `AgentProtocolSyncTest`
定向全绿；不跑全量（收口交只读收口代理）。

### 7.5 最小离线复现装置（不连任何真库）

测试服务器不可用期间，用 **H2 内存库 + 一个 iBatis demo** 复现「同一次会话里 SELECT 进面板、INSERT 不进」：

| 形态 | demo 写法 | 期望 |
|---|---|---|
| 参照 | iBatis `queryForList`（SELECT） | 修前修后都出现（防「修完反而漏 SELECT」） |
| H1 判别 | iBatis `insert()`（PS `execute()`） | 修前：若假说成立则 INSERT 缺失，且宿主 stderr 出现「无登记 PS 执行被跳过 psClass=…」；修后该行带类名与计数进状态栏 |
| H2 判别 | 同上，但加 `-Dsqlvis.agent.nestGate=false` 重启 | INSERT 出现 ⇒ H2（NestGate 抑制）；仍不出现 ⇒ 回到 H1/H3 |
| F4/F5 回归 | MyBatis `statementType="STATEMENT"` 的 insert / iBatis `createStatement()` 直写 | 修前：控制台有行、面板零条目；修后：出现条目 |

装置只需 ibatis / mybatis 两个 jar + H2（`_ibatis-research/jars/` 已有），**不写进生产代码**，
放 `dev-support/` 下的取证脚本即可；结论回填本计划 §十一。

---

## 八、待验证前提（实施者先证伪再动手，报告逐条给结论）

| # | 前提 | 证伪方式 |
|---|---|---|
| P-1 | 应用是 iBatis 2.x 且 INSERT 走标准 `{pstm-N}` 三行组 | **已由截图 S1 证实**（2026-11-10） |
| P-2 | 面板条目来自 agent（而非控制台） | **已由截图 S2 证实**（耗时 + Panwei 角标为 agent 独有字段） |
| P-3 | iBatis 查询与更新都走 `ps.execute()` | **已由 2.3.4 源码证实**（G4） |
| P-4 | agent 在线且已让位控制台桥 | 状态栏 agent 段读数（§2.3 第 2 项）；S2 已间接证明「在线」，「已让位」需 `paused` 侧面确认 |
| P-5 | H1：INSERT 的 PS 未进 `StatementRegistry` | 宿主 stderr `[SqlVisAgent] 织入通道登记盲区…psClass=`；F3 落地后看状态栏具名事实 |
| P-6 | H2：该线程 `NestGate` 深度残留 | `-Dsqlvis.agent.nestGate=false` 重启后 INSERT 出现 |
| P-7 | H3：事件组装抛异常被自吞 | F2 的 `assembleFailures` 计数 >0（或 `logLevel=debug` 看首条栈） |
| P-8 | H5：被 `SQL 去重` 折叠成 ⨉N | 关掉去重再看；或核对前一条同 SQL 条目的 ⨉N 徽章 |
| P-9 | MyBatis `SimpleStatementHandler` 只打一行 `Executing:`（不与 `Preparing:` 并存） | 本地 `statementType="STATEMENT"` demo（H2）跑一遍 |
| P-10 | 磐维两代 jar 的 `execute` / `executeUpdate` / `executeQuery` 逐方法命中 | 扩展 `StatementWeaverVendorCoverageTest`：断言三个方法各自命中（现用例只断言「整类 changed」，见 §3.4 末段） |
| P-11 | 新增心跳键不会撞 `AgentProtocolSyncTest` | 改键时同批跑该守卫；帧字段白名单两侧同步 |

> P-5 / P-6 / P-7 是本次的主分叉，三者互斥且判据各不相同（盲区行 / nestGate 开关 / 组装失败计数）。
> P-1~P-3 已被证据锁死，实施时不得再按 G1/G2/G3 派工。

---

## 九、验收判据

**离线可完成（本计划交付面）**

1. §7.2 十一组用例全绿 + §7.3 七对反向验证读数成对；
2. P-10 的逐方法织入断言落地并绿（磐维 V1/V2 两代 jar）；
3. 状态栏三段新读数（让位丢弃行数 / 组装失败与 NestGate 抑制计数 / 盲区类名）在 demo 上可复现出非零值。

**需真机/用户环境（测试服务器恢复后，或用户自测）**

4. 用户工程复跑：INSERT 出现在 SQL 日志面板，且 `restoredSql` 与业务 SQL 一致；
5. 若为 H1/H2/H3 之一：状态栏对应计数器先归零（说明缺口已修）；若缺口在应用侧，状态栏给出具名事实；
6. 磐维真机 e2e（`dev-support/e2e-fake-ide/script-panwei-gaussdb.txt` 扩一条 INSERT 窗口）——**服务器不可用期间不得写「已验证」**。

---

## 十、范围外

- 磐维方言/渲染面（本轮零改动）；
- iBatis 批处理的**逐条**还原（一次 prepare 多次 Parameters 的拆分）——`MyBatisLogParser` 类注释已登记为缺口，需另立项；
- 增强捕获对 plain Statement / batch 的补充织入（现有 `EXEC_PARAM` / `BATCH` 已覆盖，不重复造）。

---

## 附录 A：用户取证清单

### A.1 已收到（2026-11-10 截图，见 §2.2）

- [x] 控制台里 INSERT 的日志形态（S1）→ 锁定 iBatis 标准 `{pstm-N}` 档，排除 G1/G2/G3
- [x] 面板条目形态（S2/S3）→ 锁定「条目来自 agent、INSERT 不在列表」
- [x] 设置快照（S4）→ 前缀、去重、时间来源

### A.2 还差三项（都很便宜，决定 H1/H2/H3 走哪支）

1. **`Types:` 之后几行**：INSERT 是成功还是抛异常（排除 H4）；
2. **状态栏完整一行**：`|` 之后的「增强：已挂载 pid=… ｜已收事件 N 条」——`N` 与面板条目数对不对得上
   （对不上 ⇒ 事件产了但被消费侧丢；对得上 ⇒ agent 侧压根没产）；
3. **宿主 stderr 的 `[SqlVisAgent]` 行**：`织入通道登记盲区…psClass=`（H1）、`织入通道系统性盲区…`、
   `hook 包装失败…fallbackCount=`（钩子降级）——有其中任一行就直接定位。

附加：面板列表滚到 `#9` 之后确认没有更多条目（排除「只是没滚动到底」）。

---

## 十一、实施记录（2026-11-10）

### 11.1 落地内容（提交 `e2b0b581`）

| 券 | 落点 | 说明 |
|---|---|---|
| F1 | `SqlCaptureGate.suppressedSqlLines` + `processConsoleLine` 让位分支 + `flushSettledEntries` 聚合 | 按**语句**计一次（iBatis 三行组只在 `Types:` 闭合行计），噪声行不计 |
| F2 | `SqlHookCallbacks.ASSEMBLE_FAILURES` / `NestGate.SUPPRESSED` + 心跳 `assembleFailures`/`nestSuppressed` | 两条此前 `catch (Throwable)` 静默的路径有了出口 |
| F3 | `StatementRegistry.BLIND_CLASSES`（有界 8）+ 心跳 `blind`/`unresolved` | 盲区按 PS 实现类点名 |
| F4 | `MyBatisLogParser` 分支 0.5（`Executing:`，`mixedPrefix` 门控） | 严格模式下仍按未识别行计数 |
| F5 | `feedIbatis` 扩 `{stmt-N} Statement:` | 来源标记 `IBATIS` |
| F6 | `LogParseDiag.sawIbatisForm` + 状态栏 `iBatis 批处理` 提示 | 仅在「iBatis 形态 + 增强未开启」出现 |
| F7 | `LogParseDiag.unrecognizedSqlLine` + 增量诊断行 | 噪声档只查 `Executing:`（标记行在分支 0 必被消费） |

状态栏新增四段（均 >0 才挂）：`让位丢弃 SQL 行 N` / `组装失败 N` / `门禁抑制 N` / `登记盲区 N 次（类名…）`，
外加 F6 的 iBatis 批处理具名提示。文案键 zh/en 成对新增，占位符元数一致。

### 11.2 读数

| 面 | 读数 |
|---|---|
| 根项目定向 | `MyBatisLogParserTest` 32/0、`IbatisLogParseTest` 14/0、`CaptureStatusBarTest` 28/0、`LogParseConcurrencyTest` 11/0、`CaptureDiagnosticsTest` 27/0、`CaptureActivityFallbackTest`（含新增让位计数用例）全绿 |
| agent 子项目全量 | `:sql-visualizer-agent:test` BUILD SUCCESSFUL（含新增盲区聚合、门禁抑制计数、心跳三键断言） |
| 反向验证（成对） | 摘 F4 分支 → MyBatisLogParserTest **4 红**（Executing 三条 + 宽松模式计数一条），F5 用例与 strict 模式用例保绿；摘 F5 分支 → IbatisLogParseTest **2 红**（stmt 两条），`{pstm-}` 11 例保绿。恢复后复绿 |

### 11.3 横向审计（用户令「其他方言同类问题一起修」）

按真实 jar 逐个 javap 核对 agent 三面登记，补五处（同提交）：

| 缺口 | 类 | 影响 |
|---|---|---|
| pg 家族函数模式 | `org.postgresql.jdbc.PgCallableStatement` 等 7 个 | 函数模式 `executeUpdate` 走 `executeWithFlags` 分支漏采 |
| MySQL 5.1 callable | `com.mysql.jdbc.CallableStatement` | 与 GoldenDB 组对称补登（字节层环境受限） |
| 华为兼容模式批文本 | `...gaussdb...MPreparedStatement` ×2 | `addBatch(String)` 覆盖父类不回调 |
| 读写分离 | 海量/社区 openGauss 的 `ReadWriteSplittingPg*` + 连接类 | 读写分离模式整条连接的 PS 进不了登记表 |
| SQLite 四参 prepare | `org.sqlite.jdbc4.JDBC4Connection` | 父类该重载 abstract，织入跳过 |
| 华为 connect 面 | `com.huawei.gaussdb.jdbc.Driver` / `com.huawei.opengauss.jdbc.Driver` | 仅影响 lastConnectUrl 兜底 |

新类均进 `STATEMENT_FACE_CLASSES` / `PREPARE_FACE_CLASSES`；缺 jar 的进 `BYTE_VERIFIABLE_EXCLUDES` 并注明原因。
**未取证项**：MySQL 5.1 上游 jar 本机不可得，该类的字节层与真实行为**未验证**（只做了谓词层与登记）。

### 11.4 「控制台兜底为什么没生效」核实结论（只读审计）

- `paused` 的唯一生产写入点是 `AgentManager.updateOnline`（在线 + ACK 确认采集 + 非用户暂停）；
- `everReceivedAgentEvent` 一旦为真，`evaluateConsoleLine` **本纪元内永久返回 false**（`SqlLogConsoleBridge.kt:1113`），
  只有闸门回落（断线/暂停/关闭增强）才清零；
- 同一秒的 SELECT 事件还会刷新 `lastAgentEventMs`，使 10s 静默判据同时为假（`:1114-1117`）；
- 结论：回落机制的目标模型是「agent 从头到尾零事件」，**不覆盖「agent 半活」**——本次结构性命中它的盲区。
  本轮按 F8 不改语义（改动会回退 `T-CrossChannelDedupe` 与 `T-CaptureGateStability P2`），只做 F1 可见化。
- 已知计数边界（登记，不在本轮改）：`epActive` 短路行不计入 `suppressedSqlLines`（由 EP 侧同一函数计数）；
  EP 与桥双 Filter 在 `epActive` 翻真之前可能对同一行各计一次；嗅探路径不调 `flushSettled`，无闭合行的语句不计数。

### 11.5 not run

- 磐维/任何真库的端到端复现（测试服务器不可用）；
- 真机 runIde 沙盒验收（本轮无 GUI 面改动，状态栏文本由单测覆盖）；
- 全量门禁 `testShards` 与 `verifyGate`（按纪律交收口代理，本轮只跑定向与 agent 子项目全量）；
- MySQL 5.1 `CallableStatement` 的字节层与行为验证（jar 不可得）。

### 11.6 Oracle 真机复现实验（2026-11-10，**未复现**）

用户令「用 DBX 的 Oracle 尝试复现」，遂用 DBX 在册的 **Oracle AI Database 26ai Free 23.26.2.0.0**
（`114.66.55.245:20095/FREEPDB1`，凭据取自 `.local/dialect-external-matrix.release.properties` 的 `oracle23` 段）
搭了一个最小真机级端到端：

- 探针：`tmp/oracle-repro/Probe.java`（极简假 IDE——收 HELLO 即下发 `CONFIG disabled=false`，逐帧打印 EVENT，
  心跳原样打印）；
- 载荷：`tmp/oracle-repro/OracleRepro.java`（同一连接依次跑四条：`ps.executeUpdate()`（MyBatis 写路径）、
  `ps.execute()`（**iBatis 写路径** —— `SqlExecutor` 对更新也调 execute）、`ps.executeQuery()`、plain
  `Statement.executeUpdate()`）；
- 运行：JDK 17 + `-javaagent:mps-sql-agent.jar=port=53991;logLevel=debug`。

**读数（probe.log 原文）**：8 条事件全部到达，四条写入路径无一遗漏——

```
EVENT #3 kind=INSERT u=1  sql=INSERT INTO T_CAP_REPRO (ID, NAME) VALUES (?, ?)      ← ps.executeUpdate()
EVENT #4 kind=INSERT u=null sql=INSERT INTO T_CAP_REPRO (ID, NAME) VALUES (?, ?)    ← ps.execute()（iBatis 路径）
EVENT #5 kind=SELECT u=null sql=SELECT NAME FROM T_CAP_REPRO WHERE ID = ?           ← ps.executeQuery()
EVENT #6 kind=INSERT u=1  sql=INSERT INTO T_CAP_REPRO (ID, NAME) VALUES (3, 'stmt-update')  ← plain Statement
```

应用侧回读 `rows in table = 3`（写入真落库，排除「语句没执行」）。

**顺带验证了本轮 F2/F3 的端到端接线**：同一实验的心跳 body 里已带出
`"blind":"","assembleFailures":0,"nestSuppressed":0,"unresolved":0` 四键（诊断读数在生产链路上是活的）。

**结论**：Oracle + 直连 JDBC 的形态下，「写语句丢失」**不能复现** —— 说明 capture 链对 INSERT 本身没有
方法/语句类型层面的缺口。用户现场的差异只剩三处外部条件：**iBatis + 连接池的代理层次**、**该应用的实际线程**、
**磐维驱动**（V3.0-S3.4.0，A 模式）。下一步判据：香港机磐维实例就位后，用同一载荷换成 `jdbc:panweidb:` 复跑。

#### 11.6b 加测：Hikari + iBatis 式 JDK 代理（仍**未复现**）

用户澄清「Oracle 模式」指的是**磐维的 Oracle 兼容模式**，不是真 Oracle。在等香港磐维实例期间，
先把「iBatis + 连接池的代理层次」这一层用真 Oracle 排掉（`tmp/oracle-repro/OraclePooledRepro.java`）：

- 连接走 `HikariDataSource`（最大池 2），PS 再套一层与 iBatis `PreparedStatementLogProxy` 同形的 JDK 动态代理
  （`Proxy.newProxyInstance(cl, {PreparedStatement, CallableStatement}, handler)`）——即 agent 视角的三层
  已织 execute 帧嵌套，正是 `NestGate` 的判定面；
- 三条路径各跑一次：代理 `executeUpdate()`、代理 `execute()`（iBatis 写路径）、代理 `executeQuery()`。

**读数（probe2.log 原文）**：

```
EVENT #3 kind=INSERT u=1    sql=INSERT INTO T_CAP_POOL (ID, NAME) VALUES (?, ?)   ← 代理.executeUpdate()
EVENT #4 kind=INSERT u=null sql=INSERT INTO T_CAP_POOL (ID, NAME) VALUES (?, ?)   ← 代理.execute()（iBatis）
EVENT #5 kind=SELECT u=null sql=SELECT NAME FROM T_CAP_POOL WHERE ID = ?          ← 代理.executeQuery()
```

`pooled rows = 2`（两条写入真落库），心跳里 `nestSuppressed=0 / assembleFailures=0`。

**结论**：代理层次 + 连接池**也不是**丢失原因（三层嵌套下写语句照常产出，且门禁抑制计数为 0）。
剩余差异面收敛到**磐维驱动（A 模式内核）本身**与**该应用特有的线程间/实例间形态**两处。
香港磐维 A 模式实例就位后，用同一份 `OraclePooledRepro` 换 `jdbc:panweidb://` 复跑即可定案。

#### 11.7 香港磐维 A 模式真机复现（2026-11-10，**已复现，定因 NestGate**）

靶机：子智能体按 `db-env-provisioning` skill 在 `不大-香港-4G`（155.103.159.54）部署的
`panweidb-hk-ora`（`V3.0-S3.4.0_B01`，`datcompatibility=A`，26201→17700，1.5G cap，五条验收全过）。
驱动用仓库夹具 `database-lab/vendor/drivers/panweidb-jdbc-2.0.0.jar`。

**第一步：现象复现（`tmp/oracle-repro/run3.sh`，Hikari + iBatis 式代理）**

```
EVENT #1..#6  驱动连接期内部语句（PgStatement 的 executeQuery/executeUpdate）
EVENT #7      CREATE TABLE …                       ← 本应用 plain Statement
               之后 ps.executeUpdate() / ps.execute() / ps.executeQuery() 全部无事件
宿主 stderr   [SqlVisAgent] 织入通道登记盲区：无登记 PS 执行被跳过 psClass=org.panweidb.jdbc.OracleCmptPreparedStatement
```

**第二步：排除「登记真的没发生」（`run4.sh`，直连 + 反射查登记表）**

```
[probe] psClass=org.panweidb.jdbc.OracleCmptPreparedStatement
[probe] after prepare(1arg) registry=sql=INSERT INTO T_REG_PROBE (ID, NAME) VALUES (?, ?) url=jdbc:panweidb://…
[probe] after prepare(3arg) registry=sql=INSERT …（同上）
[probe] after prepare(select) registry=sql=SELECT NAME FROM T_REG_PROBE WHERE ID = ?
```

⇒ **登记在册**（prepare 面织入正常、`UrlResolver` 正常），但执行侧仍无事件、且这次连「无登记」告警都没有
⇒ 说明帧在**查登记表之前**就被挡下了 —— 即 `NestGate.exit(token)` 返回 false。

**第三步：开关判别（`run5.sh`，同一载荷加 `-Dsqlvis.agent.nestGate=false`）**

```
EVENT #6 kind=INSERT u=1  sql=INSERT INTO T_REG_PROBE (ID, NAME) VALUES (?, ?)   ← 出现了
EVENT #7 kind=INSERT u=1  sql=INSERT INTO T_REG_PROBE (ID, NAME) VALUES (?, ?)   ← 出现了
EVENT #8 kind=SELECT u=null sql=SELECT NAME FROM T_REG_PROBE WHERE ID = ?        ← 出现了
```

**定因**：磐维 A 模式（`PreparedStatement` 实际实现类 `OracleCmptPreparedStatement`）下，`NestGate` 把
**该线程后续全部语句**抑制掉了。`NestGate` 的判据是「同一线程嵌套只报最内层」，它依赖**深度最终归零**；
一旦某帧的出口没配平，该线程就永久处于「内层已产出」态 —— 本次现场（连接创建期驱动内部语句之后）
正是这个形态：前 5 条（plain Statement）正常产出，随后所有 PS 语句整类消失，且**两条静默路径的诊断
计数在为 0**（`nestSuppressed` 只统计「被抑制的外层帧」，而这里被抑制的是最外层自己）。

**这是 H2，且是本轮新增可见化没覆盖到的那种**：`nestSuppressed` 计的是 `mayEmit=false` 的外层帧；
本次的抑制发生在 `depth` 已经被抬高、`innerEmitted` 残留为真的状态下，表现更像「深度泄漏」而不是「嵌套」。

#### 11.7b 自愈尝试：**试过，被测试当场否掉，已整案回退**

实现思路（未保留）：`enter()` 在 `depth > 0` 时用「链首业务帧是否仍在栈上」判定泄漏——不在即复位整条链，
计 `nestLeaks` 并进心跳/状态栏；真实嵌套时外层帧必然还在栈上，故不误治愈。

**失败读数**：agent 子项目 287 例中 **10 红**（`NestGateTest` 2 + `StatementWeaveEndToEndTest` 8），
症状一律是「本该产出的语句零事件」。根因是**锚帧选错**：`businessFrameKey()` 跳过 agent 包取第一个
非 agent 帧，而 agent 子项目的测试夹具本身就在 `cn.song.mybatisplus.sqlvis.agent` 包下，
取到的锚帧落到 JUnit/反射帧上——这类帧**永远在栈上**，于是「泄漏」判不出、「自愈」不触发，
而我自己那条泄漏用例留下的 `depth=1` 又把后续用例连坐成整片抑制。

**处置**：整案 `git checkout` 回退（NestGate / SqlHookCallbacks / SenderLoop / AgentAttachStats /
CaptureStatusBar / SqlLogPanel / 两份 bundle / NestGateTest），回退后 agent 全量 **287 例
1 红 1 skip**，那 1 红是 `SenderLoopBackoffTest` 的端口/时序 flake（单跑 **BUILD SUCCESSFUL** 复现不出），
与本改动无关。

**结论与下一步**：泄漏判据不能锚在「第一个非 agent 帧」上（测试/宿主形态会漂），可行的方向是
①锚**最外层已织帧的调用方是否仍在「本链自己的栈深度区间」内**（记录链首的栈深，不是帧名）；
②或让 `onStatementExit` 对「`mayEmit=false` 且 `depth` 回到 0」这一异常形态直接放行（保留 `nestSuppressed`
之外的 `suppressedOutermost` 计数，先观测一轮再决定是否放行）。两条都要先补**跨测试包的锚点一致性用例**
（夹具在 agent 包内、业务在包外两种形态各一例），否则仍会重演本次的假阳性。

**现场可用判据（现状唯一手段）**：`-Dsqlvis.agent.nestGate=false`。

### 11.8 只观测轮：深度泄漏的可观测化与真机取证（2026-11-10，提交 `8e85d397`）

**目标与红线**：§11.7 已把「磐维 A 模式下 NestGate 深度泄漏」作为事实定死，但泄漏发生在哪一帧没有证据；
§11.7b 证明在这里顺手改语义会被 10 例测试当场否掉。本轮**只加观测**：不改 `depth`/`innerEmitted` 的
流转、不改任何返回值、异常全吞、正常语句不抓栈。真机数据见下，泄漏帧已定位到驱动类与方法。

#### 11.8.1 改了什么

| 项 | 落点 | 语义影响 |
|---|---|---|
| 链龄 | `NestGate.State.chainStartNanos` + `chainId`，只在 `depth 0→1` 记一次 `nanoTime`（普通语句每帧一次，与入口 token 同量级） | 无 |
| 陈旧嵌套计数 | `NestGate.NEST_STALE`：`enter()` 在 `depth > 0` 时若 `now - chainStartNanos > sqlvis.agent.nestStaleMs`（默认 5000ms）则 +1 | 无（纯计数，抑制仍由 depth/innerEmitted 决定） |
| 最高深度 | `NestGate.MAX_DEPTH`（`AtomicInteger` 取大）——判「泄漏后深度是否持续攀升」 | 无 |
| 一次性取证 dump | 首次判陈旧且 `CaptureBudget.isVerboseLogging()`：一行摘要 + 当前栈前 12 帧，`AtomicBoolean` 只打一次，不含 SQL 原文 | 无 |
| 接线 | `SqlHookCallbacks.nestStaleCount()/nestMaxDepth()`；心跳 `nestStale`/`nestMaxDepth`（键序固定、无空格、恒出现）；`AgentAttachStats` 字段 + 正则键；状态栏「门禁陈旧 N」（>0 才挂）；zh/en 两份 bundle 成对加键（2 占位） | 无 |
| 测试缝 | `NestGate.clockOverride`（package-private `LongSupplier`）经 `SqlHookCallbacks.setNestClockForTest/…MsForTest/resetNestGateForTest` 转发 | 仅测试 |

#### 11.8.2 定向读数（只跑定向，未跑全量）

| 面 | 命令 | 读数 |
|---|---|---|
| agent | `:sql-visualizer-agent:test --tests "…NestGateTest" --tests "…StatementWeaveEndToEndTest"` | `NestGateTest` **6/0/0/0**（4 旧 + 2 新：`正常嵌套_不误报陈旧且仍只报最内层`、`陈旧嵌套_超阈值计数且语义不变`）、`StatementWeaveEndToEndTest` **13/0/0/0** |
| 根项目 | `test --tests "…CaptureStatusBarTest" --tests "…BundlePlaceholderArityTest"` | `CaptureStatusBarTest` **29/0/0/0**（新增「门禁陈旧 >0 才挂」）、`BundlePlaceholderArityTest` **4/0/0/0** |

新用例同时钉住两件事：正常嵌套（一次 execute 的时长）`nestStale` 恒 0；用时钟缝把链龄拨过阈值后
`nestStale` +1，且事件条数与原行为完全一致（嵌套仍只报最内层一条、外层仍计 `nestSuppressed`）。

#### 11.8.3 真机两档读数（香港 `panweidb` A 模式，`155.103.159.54:26201`）

载荷 `tmp/repro/PanweiObserveProbe.java` + 假 IDE 探针 `tmp/repro/Probe.java`，脚本
`tmp/repro/run-observe.sh`（`GATE=on` / `GATE=off`，端口 53996），agent jar 为本工作树
`./gradlew :sql-visualizer-agent:agentFatJar` 产物，驱动 `database-lab/vendor/drivers/panweidb-jdbc-2.0.0.jar`。
载荷顺序＝连接 → `DROP` + `CREATE`（plain Statement）→ **8s**（越过陈旧阈值）→ `INSERT`（`executeUpdate`）→
`INSERT`（`execute`，iBatis 写路径）→ `SELECT`（`executeQuery`）→ `SELECT COUNT(*)` → **25s 心跳观察窗**。

**默认档（门禁开）** —— 事件列表：

```
EVENT #1 kind=SELECT u=null sql=select name, setting from pg_settings where name in ('connection_info')
EVENT #2 kind=SET u=0 sql=set connection_info = '{"driver_name":"JDBC","driver_version":"@GSVERSION@"}'
EVENT #3 kind=SELECT u=null sql=select count(*) from pg_settings where name = 'support_batch_bind' and setting = 'on';
EVENT #4 kind=CREATE u=0 sql=CREATE TABLE T_OBS_PROBE (ID NUMBER, NAME VARCHAR2(40))
（此后 ps.executeUpdate / ps.execute / ps.executeQuery / SELECT COUNT(*) 全部零事件）
```

心跳原文（连接瞬间那拍全 0；泄漏后的三拍）：

```
HEARTBEAT {"weavable":1,...,"assembleFailures":0,"nestSuppressed":0,"nestStale":0,"nestMaxDepth":0,"unresolved":0}
HEARTBEAT {"weavable":4,"woven":4,...,"assembleFailures":0,"nestSuppressed":4,"nestStale":4,"nestMaxDepth":2,"unresolved":0}
HEARTBEAT {"weavable":4,"woven":4,...,"assembleFailures":0,"nestSuppressed":4,"nestStale":4,"nestMaxDepth":2,"unresolved":0}
HEARTBEAT {"weavable":4,"woven":4,...,"assembleFailures":0,"nestSuppressed":4,"nestStale":4,"nestMaxDepth":2,"unresolved":0}
```

dump 原文（宿主 stderr，debug 档，只此一次）：

```
[SqlVisAgent] 门禁陈旧链 chainId=4 ageMs=8644 depth=2 thresholdMs=5000
  at cn.song.mybatisplus.sqlvis.agent.hook.NestGate.dumpStaleChain(NestGate.java:191)
  at cn.song.mybatisplus.sqlvis.agent.hook.NestGate.noteNestedEnter(NestGate.java:174)
  at cn.song.mybatisplus.sqlvis.agent.hook.NestGate.enter(NestGate.java:144)
  at cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.onStatementEnter(SqlHookCallbacks.java:129)
  at org.panweidb.jdbc.PgPreparedStatement.executeUpdate(PgPreparedStatement.java)
  at PanweiObserveProbe.main(PanweiObserveProbe.java:44)
```

**关档（`-Dsqlvis.agent.nestGate=false`）** —— 同一载荷、同一实例：

```
EVENT #1..3 同上前三条
EVENT #4 kind=DROP   u=0 sql=DROP TABLE T_OBS_PROBE
EVENT #5 kind=CREATE u=0 sql=CREATE TABLE T_OBS_PROBE (ID NUMBER, NAME VARCHAR2(40))
EVENT #6 kind=INSERT u=1 sql=INSERT INTO T_OBS_PROBE (ID, NAME) VALUES (?, ?)
EVENT #7 kind=INSERT u=1 sql=INSERT INTO T_OBS_PROBE (ID, NAME) VALUES (?, ?)
EVENT #8 kind=SELECT u=null sql=SELECT NAME FROM T_OBS_PROBE WHERE ID = ?
EVENT #9 kind=SELECT u=null sql=SELECT COUNT(*) FROM T_OBS_PROBE
EVENT #10 kind=DELETE u=2 sql=DELETE FROM T_OBS_PROBE
HEARTBEAT {... "nestSuppressed":0,"nestStale":0,"nestMaxDepth":0,"unresolved":0}   （三拍一致）
```

**判读**：门禁开时 PS 面与 plain Statement 面在泄漏线程上整类消失，而 `nestSuppressed=0`（首拍）证明
§11.7 的诊断口径——被抑制的是最外层自己；泄漏后 `nestStale` / `nestMaxDepth` 明显非零。
关档后事件全出、`nestStale` 不再增长。**关档时 `nestMaxDepth` 也恒 0 属预期**：观测点与门禁共用
`enabled` 短路（enter 直接返回），不是观测缺失。

#### 11.8.4 泄漏帧定位（本轮最有价值的结论）：`org.panweidb.jdbc.PgStatement.execute`

上面的 dump 只给出「触发陈旧判定的那次嵌套进入」（PS 面），要回答「哪一帧没配平」，用
`tmp/repro/PanweiNestLeakFrameProbe.java` 做了两档对照（`MODE=fail` / `MODE=ok`，脚本 `run-leakframe.sh`，
端口 53997）：同一 `createStatement()` 先打一条语句、睡 8s、再打一条——若第一条的出口没配平，第二条
就是 depth=1 上的嵌套进入，dump 会打出**这条 plain Statement 的实现类**。

`MODE=fail`（第一条 `DROP` 打不存在的表，确定性抛错）：

```
[probe +2924ms] drop1 failed: ERROR: table "t_leak_frame_absent" does not exist
[SqlVisAgent] 门禁陈旧链 chainId=4 ageMs=8163 depth=2 thresholdMs=5000
  ...
  at org.panweidb.jdbc.PgStatement.execute(PgStatement.java)
  at PanweiNestLeakFrameProbe.main(PanweiNestLeakFrameProbe.java:49)
HEARTBEAT {... "nestSuppressed":0,"nestStale":2,"nestMaxDepth":3,...}
```

`MODE=ok`（`CREATE` 成功，8s 后 `DROP` 成功）：

```
[probe +2819ms] create ok
[SqlVisAgent] 门禁陈旧链 chainId=4 ageMs=8170 depth=2 thresholdMs=5000
  ...
  at org.panweidb.jdbc.PgStatement.execute(PgStatement.java)
  at PanweiNestLeakFrameProbe.main(PanweiNestLeakFrameProbe.java:39)
HEARTBEAT {... "nestSuppressed":2,"nestStale":2,"nestMaxDepth":2,...}
```

**结论**：泄漏帧落在 **plain `Statement` 面（`org.panweidb.jdbc.PgStatement.execute`）**，与该次调用
成功或失败**无关**（成功 DDL 与失败 DROP 都能复现；主观测档的 chainId=4 起点 +2668ms 正对 `DROP` 的
执行时刻）。此后该线程的所有语句（含 PS 面）都在这条链上被当成外层：一旦链内某帧真的产出事件
（主观测档的 `CREATE`），`innerEmitted` 置位，后续整类语句消失——与 §11.7 的现场形态完全一致。

**仍未证的部分（如实登记）**：dump 只能证明「这条 `execute` 帧的链没有归零」，还不能区分
「多一次入口（同一方法被织两遍，两个 enter 的 token 被出口去重吞掉一个）」与「少一次出口（某条
return/athrow 未被织入）」——两者在当前观测面上同形。下一步的取证路径：把 `PgStatement` 的原字节与
运行时织入后的字节做离线对比（数 `onStatementEnter` / `onExecuteExit` 的调用点数）、或用
`-javaagent` 之外的 `retransform` 单类工具取织入后字节 javap。**本轮按红线不做任何自愈/放行**，
`-Dsqlvis.agent.nestGate=false` 仍是唯一现场规避手段。

#### 11.8.5 not run

- 全量门禁 `testShards` 与 `verifyGate` / `verifyPlugin`（按纪律交收口代理）；
- runIde 沙盒验收（本轮无面板布局/控件改动，状态栏文案由 `CaptureStatusBarTest` 覆盖）；
- 「多入口 vs 缺出口」的字节层归因（见 §11.8.4 末尾，已给取证路径，未在本轮执行）；
- 其他数据库（Oracle/PostgreSQL/MySQL 等）的同类观测复跑——本轮只打磐维 A 模式实例。
