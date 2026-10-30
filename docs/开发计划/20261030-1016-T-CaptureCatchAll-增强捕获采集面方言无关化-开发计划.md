# T-CaptureCatchAll 增强捕获采集面方言无关化（接口匹配兜底 + 活性回落 + 熔断） — 开发计划

> 来源：同日调研报告 [`../调研报告/20261030-1016-增强捕获采集面方言无关性-调研报告.md`](../调研报告/20261030-1016-增强捕获采集面方言无关性-调研报告.md)。
> 基线：`dev` @ `209e32cd`（T-CaptureCoverage A/B 两路已落，14 方言三面补齐）。
> 占号：**O-155 / D201 / V226**。
> 状态：**实施中**。
> **红线（用户令，凌驾于一切功能目标）**：不能因为增强捕获导致用户服务受影响——**不允许接口 500、不允许项目/应用无法启动**。任何新增采集面必须满足「失败即原样返回 + 异常全吞 + 不改返回值 + 不改对象身份 + 有预算上限」；违反任一条不得进入 premain 内联面。

---

## 一、背景与缺口

T-CaptureCoverage 把 14 方言的织入名单补齐了，但**机制没变**：`DriverRegistry`（connect 面 17 类）+ `WeaveSpecs`（语句面 24 / prepare 面 13）仍是**类名哈希表**，`SqlHookTransformer` 未命中就 `return null`，静默无提示。每来一个新驱动仍要人工补条目——这次补了三族，下次还得再补。

更要紧的是调研发现的两处结构性问题：

1. **未命中 ≠ 少一条通道，而是两条都没有**：agent 在线并 ACK 后 `SqlCaptureGate.paused` 置真，控制台桥随即短路。白名单漏一个驱动 = 增强捕获零事件 + 控制台解析让位 = 用户视角「插件坏了」。
2. **采集面本应与方言正交**：方言注册表服务渲染/预览/执行，采集不需要知道方言。当前的耦合纯属实现方式。

本任务把采集面从「名单驱动」改成「名单优先 + 接口兜底」，并补上活性回落与预算熔断。

## 二、待验证前提（实施前先证伪）

| 编号 | 前提 | 证伪方式 | 若不成立的影响 |
|---|---|---|---|
| **P-1** | `getAllLoadedClasses()` + `isAssignableFrom` 在 attach 场景能枚举到目标驱动的 Statement/Connection 实现 | 服务器真实 JVM 冒烟（`/opt/xc3-attach-smoke/`，JDK17，三族驱动 jar 已在位）：对 MariaDB/SQLite/OceanBase 三族**不登记白名单**、只靠反射匹配，看 `matched/wove` 是否 >0 | 若枚举不到（类未加载/名字不符），兜底失效 |
| **P-2** | 反射式 attach 匹配对连接池包装类有效 | HikariCP 包装下的 PS 是否被匹配到（本机只有 Hikari jar，javap 已知 `HikariProxyPreparedStatement implements java.sql.PreparedStatement`） | 池化场景兜底失效 |
| **P-3** | ASM 9.4 对目标 JDK 的类文件版本够用 | 已知 major 65（JDK21）抛 `Unsupported class file major version 65`；须实测 major 66/67 与现有 `h2-2.3.232` 的 `META-INF/versions/21` 类 | 版本闸上限取值与是否升 ASM |
| **P-4** | 「嵌套只报最内层」判据可实现且不误杀 | 造 Hikari + MyBatis 双层场景，断言单执行恰好 1 条事件 | 双报（`EXEC_PARAM` ×2、`unresolvedCount` 抬升） |
| **P-5** | 控制台桥「活性回落」不引发抖动 | 造「agent 在线但零事件 + 控制台有 SQL」场景，观察是否稳定解封而非反复横跳 | 双桥同时工作导致重复条目 |
| **P-6** | 预算熔断阈值可定 | 2 万类应用启动 A/B（调研仅探针外推 60–140ms CPU，非端到端） | 阈值过高失效、过低误熔断 |
| **P-7** | premain 阶段「记录候选 + attach 补挂」能覆盖冷启动早期 SQL | 服务器冒烟：premain 启动的宿主，首条 SQL 是否被补挂接住 | 冷启动早期 SQL 丢失（可接受，但须如实登记） |

## 三、实施步骤（券）

### 券 01 attach 反射式接口匹配（S，agent 模块）

`AgentMain.retransformLoadedDrivers`：在白名单遍历之外，追加一轮**接口匹配**——对 `getAllLoadedClasses()` 里 `java.sql.Statement` / `java.sql.PreparedStatement` / `java.sql.CallableStatement` / `java.sql.Connection` 的 `isAssignableFrom(c)` 为真、且未被白名单命中的类，走**同一织入通道**（`specOf` 兜底生成 spec）。

- 失败即跳过（单类 `try` 已有）；计数单列 `matchedByInterface` 便于诊断
- **不碰 connect 面**（`wrapConnection` 默认 false 不得动）
- 日志：`attach retransform: matched=N wove=M (whitelist=N1 interface=N2)`

### 券 02 控制台桥活性回落（S，IDE 侧）

`SqlCaptureGate.paused` 置真后，若 **≥N 秒（建议 10s）零 agent 事件**且控制台持续有 SQL 行，则**自动解封控制台桥**并打一行诊断（不弹窗）。判据复用既有 `CaptureDiagnostics.agentEventsReceived`。

- 必须有**滞回**：解封后若 agent 事件恢复（>0 条），重新让位；避免反复横跳
- 解封状态进设置页诊断行（如实披露「增强捕获疑似空转，已回退控制台解析」）

### 券 03 覆盖自检上报（S，IDE 侧）

把 agent 的 `weavable/woven/matchedByInterface/failed` 四类计数**上报到 IDE 侧**并进 `CaptureDiagnostics`：
面板空态与诊断行可显示「本次 attach 命中 0 个驱动」这类事实，替代现在的静默。

### 券 04 `Class` 强引用缓存改弱（S，agent 模块）

`SqlHookCallbacks.GET_CONNECTION_METHODS:254`、`UrlResolver.GET_URL_METHODS:19` 等 `Class` 强引用改 `ClassValue` 或弱键，防热部署钉住旧 classloader。

### 券 05 类文件 major 版本闸（S，agent 模块）

transformer 入口先读 `bytes[6..7]` 得 major，超过 ASM 支持上限即**原字节返回 + 计数 `skippedByVersion`**。同步评估是否升 ASM（P-3）。

### 券 06 transformer 兜底分派（M，agent 模块，**需真机验证后启用**）

两段式：裸字节 `java/sql/` 子串预筛（2–5µs/类）→ 命中才跑 ASM header（8–11µs/类）读 `interfaces` → 确认才织。
**默认关闭**（新增系统属性开关），真机验证通过后再考虑默认开。禁止对全部类直接全树解析。

### 券 07 「嵌套只报最内层」门（M，agent 模块）

同一执行链上若外层已登记（如 MyBatis `PreparedStatementLogger` 代理 → 内层驱动 PS），只报最内层一条。
判据不得用类名黑名单（会随生态漂移）。建议：**同一线程在同一 execute 调用栈深度内，只取最内层登记**。

### 券 08 premain 安全策略（M，agent 模块，红线核心）

**premain 不内联织入未知形态**：premain 阶段只处理白名单（已验证字节形态）；接口匹配命中的类**只记录候选**，由 attach 后的首轮 retransform 或周期补挂处理。理由：premain 织入失败会让目标 JVM 起不来。

### 券 09 预算熔断（M，agent 模块）

三类上限：①接口匹配扫描的**类数上限**；②transformer 累计耗时预算；③连续失败 N 次 → **整体 kill switch**（退回纯白名单，打一行诊断）。全部上限可配、默认保守。

### 券 10 守卫与红线测试（M）

| 项 | 内容 |
|---|---|
| 结构自检 | 织入产物在隔离 loader 定义 + 初始化（JVM 链接期校验），三族 + 池包装类 |
| 强转红线 | 带/不带 agent 对 `KbConnection` / `PgConnection`(海量/瀚高) / `HikariProxyConnection` 强转，必须成功且 `assertSame` |
| 双报门 | Hikari + MyBatis 双层，单执行恰好 1 条事件 |
| 熔断 | 造连续失败 matcher，断言 kill switch 生效且退回白名单 |
| 预算 | 造万级类，断言累计耗时在上限内且不阻断启动 |
| 源码守卫 | 新事件构造点必须经 `WovenEvents.java:88` 唯一出口（脱敏） |

### 券 11 文档与索引同步（S）

`docs/架构设计/01-项目架构总览.md` agent 段、`docs/变更记录.md`、`docs/索引/开发计划.md`、调研报告索引。

## 四、验证

- **定向**（各券实施后自跑）：agent 模块全量 + 主项目 `*CaptureDiagnostics*` / `*SqlCaptureGate*` / `*AgentManager*` 相关类
- **真实 JVM 冒烟**（服务器 `/opt/xc3-attach-smoke/`，JDK17）：premain + attach 两形态，三族 + Hikari 包装；读数含 `matched/wove/matchedByInterface/failed`、宿主退出码、强转断言
- **红线专项**：①带 agent 与不带 agent 的宿主**接口行为一致**（同 SQL 同结果同异常）；②宿主启动成功且启动耗时增量在预算内；③模拟 matcher 抛异常，宿主仍正常
- **统一全量门禁**：全部券完成后单派只读收口代理跑 `testShards`；改 agent `build.gradle.kts` → 另跑 `verifyGate`
- **不跑**：不在真机验证前把券 06 默认打开

## 五、决策点

| # | 决策 | 选项 | 倾向 |
|---|---|---|---|
| **D1** | 接口匹配与白名单的关系 | A 并存（白名单优先）/ B 替换 | **A**（白名单＝已验证字节形态与 premain 安全集） |
| **D2** | 券 06 transformer 兜底默认开关 | A 默认关、用例/真机验证后再开 / B 默认开 | **A**（红线优先） |
| **D3** | 券 07 双报判据 | A 栈深度「只报最内层」/ B 类名黑名单 | **A**（黑名单会随生态漂移） |
| **D4** | 活性回落触发阈值 | A 10s 零事件 + 控制台有 SQL / B 立即 / C 30s | **A**（滞回另计） |
| **D5** | 熔断默认值 | A 保守（类数 5k / 累计 300ms / 连败 3） | **A** |

## 六、风险

- **红线击穿即回滚**：任何一券若在真机冒烟里出现宿主启动失败/接口行为差异，**该券整体回滚**，不许"降级保留"。
- **双报与降级的相互影响**：券 02 活性回落与券 07 双报门都改「哪些条目不显示」，需一起验，避免出现「解封后重复条目」。
- **性能预算外推**：调研的 60–140ms 是探针外推，真实应用需 A/B 实测（P-6）。
- **与 T-CaptureCoverage 的兼容**：本任务在其基础上改同一批 agent 文件，须串行（其 A/B 两路已合并）。
- **热部署/classloader**：券 04 是唯一直接动既有类引用的改动，需专门的热部署用例。

## 七、实施记录

### 7.1 C1 路（agent 侧，券 01/04/05/06/07/08/09 + 券 10 agent 部分）

实施基线：`t-capture-catchall`（基于 `6ddf9119`）。写集严格限于
`sub-projects/sql-visualizer-agent/`；IDE 侧（券 02/03）由 C2 承担，agent 只把计数暴露进心跳。

#### 逐券落位

| 券 | 落点 | 关键形态 |
|---|---|---|
| 01 | `CatchAllMatcher`（新）、`WeaveSpecs.catchAllStatementFace/catchAllPrepareFace`、`AgentMain.installOrThrow`、`SqlHookTransformer.transform` 接受表分支 | attach 轮遍历 `getAllLoadedClasses()`，`java.sql.Statement/Connection.isAssignableFrom` 判定；命中类登记进接受表（类名→兜底面形态），transformer 据表走**同一** `StatementWeaver.weaveSpec` 通道。白名单优先（先按三谓词排除），`wrapConnection` 默认值未动 |
| 04 | `SqlHookCallbacks.GET_CONNECTION_METHODS`、`UrlResolver.GET_URL_METHODS` | `ConcurrentHashMap<Class,Method>` → `ClassValue<Object>`（含未命中哨兵）；值随 Class 回收，热部署不再钉旧 loader |
| 05 | `ClassFileVersion`（新）、`SqlHookTransformer.transform` 入口、`build.gradle.kts`（ASM 9.4→9.8） | 读 `bytes[6..7]` 得 major，超上限原字节放行 + `skippedByVersion`；上限 69（Java 25）与 `Opcodes.V25` 双向钉住 |
| 06 | `SqlHookTransformer.transformCatchAll`（默认关，`sqlvis.agent.catchAllTransform`） | 两段式：裸字节子串 `java/sql/`（朴素扫描，不分配 String）→ 命中才 `ClassReader` 读 access+interfaces → 确认才织。禁止全树解析 |
| 07 | `hook/NestGate`（新）、`SqlHookCallbacks.onStatementEnter/onExecuteExit` | 线程内深度栈：嵌套链只让**先退出者**（最内层）产事件，外层抑制；判据不含任何类名黑名单。出口出栈排在一切早退之前（防深度残留） |
| 08 | `AgentMain.premain/agentmain` 分流 + `CatchAllMatcher.recordPremainCandidates/scheduleDeferredPasses` | premain **零 retransform**、接受表为空，只打一行候选计数；启动后 daemon 线程补挂（默认 10s 首轮 / 60s 间隔 / 5 轮，可配，受扫描上限与 kill switch 约束） |
| 09 | `CaptureBudget`（新）、`StatementRegistry` 单桶上限 + 淘汰 | ①扫描类数 5000；②transformer 兜底累计 300ms（耗尽只关兜底，白名单不受影响）；③连败 3 → 整体 kill switch（退回纯白名单 + 一行诊断）；④`StatementRegistry` 单桶 512（≈8192 条）超限淘汰。全部可经 `sqlvis.agent.*` 覆盖 |

**兜底面形态的取舍（重要，与计划原文的差异）**：接口命中的类**不**使用白名单的完整
`statementFace`，而是 `{EXEC_PARAM, EXEC_CTX, BATCH, PARAM}`。理由：`ADD_BATCH`/`CLEAR_BATCH`
两个形态在出口读**原方法局部**（`ALOAD 0/1`），未知驱动编译器的 `chop_frame` 会让整类
`VerifyError`——连本可织上的 execute 面一起丢。代价：兜底驱动的批处理 SQL 文本不累积
（`addBatch(String)` 未织），如实登记为已知缺口。

**日志形态**：白名单轮汇总行逐字未变（3 个既有守卫依赖它），接口轮另起一行：
`[SqlVisAgent] attach interface-match: matched=N wove=M (whitelist=N1 interface=N2) scan=S skipped=K failed=F`
（premain 补挂轮前缀为 `premain pass k/5`）。premain 候选行为
`[SqlVisAgent] catch-all: premain: N interface candidate(s) among S loaded class(es); …`。

**心跳（券 03 的 agent 侧）**：`SenderLoop.heartbeatBody()` 带出
`weavable/woven/failed/matched/unmodifiable/matchedByInterface/interfaceWoven/skippedByVersion/catchAll`，
与 C2 的 `AgentAttachStats` 宽容扫描键逐字对齐；旧 body 是 `{}`、IDE 侧不解析 body，故向后兼容。

#### 验证读数（本机，agent 模块全量＝本路定向面）

| 项 | 读数 |
|---|---|
| 定向（`./gradlew :sql-visualizer-agent:test`） | **45 个测试类 / 237 例，0 失败 0 错误，2 skipped**（MariaDB/SQLite 可选 jar 字节层 skip，既有语义） |
| 结构自检（券 10） | HikariCP 4.0.3 jar 自带的 `HikariProxyPreparedStatement`（`final`，直接 implements `PreparedStatement`）与 `HikariProxyConnection`：兜底面织入 changed → 隔离子优先 loader 定义 + **初始化**过 JVM 链接期校验；类型契约（仍 implements PreparedStatement / 仍 final）不变 |
| 校验器判别力 | 同一 loader 定义「撒谎 StackMapTable」合成类 → `VerifyError`/`ClassFormatError`（防 loader 未触发校验的假绿） |
| 红线① 带/不带兜底行为一致 | 同 SQL：`executeUpdate`→2、`execute`→false、`executeQuery`→null、`executeBatch`→长度 2；异常路径类型+消息逐字一致（`…$BoomException: kaboom`） |
| 红线② matcher 抛异常 | `isModifiableClass` 抛异常 → 单类计 `interfaceFailed=1`、不熔断、不外溢；`getAllLoadedClasses` 抛异常 → 本轮安全返回、无匹配计数 |
| 红线③ 强转 assertSame | `onConnection` 原样返回（assertSame）；兜底 prepare 面产出的 PS 仍可强转到驱动实现类且 `assertSame` |
| 红线④ 连败→kill | `killAfter=2` 触发后：白名单类照旧织入（changed），兜底类返回 null、接受表为空 |
| 反向验证（摘掉接口匹配轮） | **红**：`attach轮_白名单优先且命中即登记接受表`、`attach轮_接受表命中后transformer走同一织入通道`、`扫描上限_超出即停止扫描` 共 3 例失败（expected:1 but was:0）；还原后 **绿**（同 3 例通过） |
| 版本闸 | major 69 放行 / 70 挡下并计 `skippedByVersion=1`（不计入 failed）；magic 不符与 null 不越权拦截 |

#### 大改动登记

- **`sub-projects/sql-visualizer-agent/build.gradle.kts`：ASM 9.4 → 9.8**（唯一构建面改动；
  未新增测试依赖）。依据：ASM 9.4 上限 major 64（Java 20），读 JDK21 类（major 65）直接抛
  `Unsupported class file major version 65`；9.8 上限 major 69（Java 25），已实测
  `Opcodes.V25 = 69`。reloc 源集同版本（fat jar 自 relocate 与织入期 ASM 同源）。
  **按大改动判据，收口须另跑 `verifyGate`**（本路未跑，按分工留给收口代理）。

#### 未尽项（留给真机冒烟 / 后续券）

1. **P-1/P-2/P-7 真机读数**：本机只证机制（假 Instrumentation + 隔离 loader），未在真实
   Spring Boot 应用上验「不登记白名单、只靠反射匹配」的 `matched/wove>0`；premain 补挂能否接住
   冷启动早期 SQL 亦未验。
2. **Hikari + MyBatis 真实双层**：本机用「外层织入方法转发内层织入方法」等价夹具证嵌套门；
   真实 `HikariProxyPreparedStatement` + MyBatis `PreparedStatementLogger` 组合未跑（需 MySQL 3306）。
3. **兜底 prepare 面的 VerifyError 残留风险**：未知 `Connection` 实现的 `prepareStatement` 出口
   仍读原局部（沿用既有 PREPARE 形态，未改已验证的 17 个白名单类），`chop_frame` 类会被 JVM 拒绝
   retransform（该类不增强，宿主无感）——真机若见 `failed>0` 应如实登记，不得改成 premain 内联。
4. **性能预算实测（P-6）**：2 万类应用启动 A/B 未做；兜底分派默认关，风险面仅 attach 轮
   （5000 类扫描上限，零字节解析）。
5. **券 06 默认开**：未做（计划 D2=A 要求真机验证后再考虑）。
6. **券 02/03（IDE 侧）**：C2 路，本路只提供心跳字段。

#### 待验证前提的实测结论（对 §二）

- P-2 **成立（机制层）**：Hikari 4.0.3 的 `HikariProxyPreparedStatement` 是 jar 内预生成实体类
  （非运行时 javassist 生成物），反射匹配与 header 匹配都命中，且兜底面织入产物过链接期校验。
- P-3 **成立且已处置**：ASM 9.4 上限 64；升 9.8 后上限 69，闸值同步为 69。
- P-4 **机制层成立**：调用栈深度判据在等价嵌套夹具上恰好 1 条事件，非嵌套不误杀。
- P-1/P-5/P-6/P-7 仍待真机（见未尽项）。

### 7.2 C2 路（IDE 侧：活性回落 + 覆盖自检）

#### 逐券落位

| 券 | 落点 | 关键形态 |
|---|---|---|
| 02 | `log/SqlLogConsoleBridge.kt`（新增 `CaptureActivityFallback`）、`AgentManager.updateOnline`、`AgentEventAdapter.markReceived` | `paused` 持续 ≥ 阈值（默认 10s）+ 窗口内零 agent 事件 + 窗口内控制台**实证解析出 SQL 条目** → 解封桥；事件到达立即重新让位（滞回）；闸门回落沿清空状态 |
| 03 | 新增 `log/agent/AgentAttachStats.kt`、`AgentSessionSupport` 读循环、`CaptureDiagnostics`、`SqlLogPanel.syncDiagnostics` | 逐帧宽容扫描 `"key":数字`，解析 `weavable/woven/failed/matched/unmodifiable/matchedByInterface/interfaceWoven/skippedByVersion/catchAll`；旧版/缺键/畸形/超长全部降级 |
| 10 | `CaptureActivityFallbackTest`（8）、`AgentAttachStatsTest`（9）、`CaptureDiagnosticsTest`（+7）、`AgentStatusTextTest`（+1） | 双哑、不丢行、滞回不横跳、双通道同指纹折叠、文案守卫 |
| 11 | `docs/架构设计/01-项目架构总览.md` agent 段 | 补「白名单优先 + 接口兜底 + 活性回落」三句口径（抽象措辞，不写未完成细节） |

**券 02 细节**

- 触发判据全部为可核实事实：`SqlCaptureGate.paused` 持续 ≥ 阈值 + 窗口内零 agent 事件 + 窗口内控制台**实证解析出 SQL 条目**（由既有嗅探路径产出，不是「有任意控制台输出」）。
- 不丢行：解封触发的那一行若已由嗅探解析出条目，经抽出的 `finishEntries` 直接采用（不重复喂解析器，Preparing/Parameters 配对不断）。
- 滞回：`AgentEventAdapter.markReceived` → `onAgentEvent` 立即重新让位，静默计时从事件时刻重算；`AgentManager.updateOnline` 的闸门回落沿 → `onGateChanged(false)` 清空。
- 消费点统一改用 `SqlCaptureGate.bridgeSuppressed()`（有效封停态 = paused 且未解封）：`scanAll` 静默冲刷守卫、`SqlLogEntryBuffer` 通道归因诊断不再裸读 `paused`。
- 可配：系统属性 `-Dmpsv.capture.activityFallback=false` 关、`-Dmpsv.capture.activityFallback.thresholdMs=<ms>` 调阈值；**出厂默认开启**。选系统属性而非设置项：纯降级路径、非功能开关，放进设置页要连带改复选框计数守卫（当前 47）与 UI，收益不抵噪音；解封状态本身已在面板空态与设置页状态行可见。
- 诊断（不弹窗）：面板空态首行「增强捕获疑似空转，已回退控制台解析」+ 设置页状态行「已挂载 pid=…｜增强捕获疑似空转，已回退控制台解析」（中英双份 bundle）。

**券 03 细节**

- 宽容解析任意入站帧 body（读循环逐帧、8KB 上限、`"matched"/"weavable"/"matchedByInterface"` 廉价前置门）；异常全吞、旧版 agent 返回 null。
- 与 C1 心跳实测对齐（C1 已补 `matched/unmodifiable`）；「零命中」判据取 `max(matched, weavable)`，即使心跳只带 `weavable` 也不会误报「命中 0 个驱动」。
- 面板空态首行按异常才显示：零命中 / 命中未织入 / retransform 失败 / 版本闸跳过 / 接口兜底熔断 / 接口兜底命中数（含织入数）。

#### 验证读数（2026-10-30）

| 项 | 读数 |
|---|---|
| 双哑 | 静默未达阈值 0 条 → 达阈值后 1 条，`isActive=true`、`engagements=1` |
| 不抖动 | 事件每 5s 一次 + 控制台持续 SQL，4 轮采样 `engagements=0`、`releases=0`；解封后事件恢复，其后 5s/9s 两轮均不再解封（`engagements` 仍 1） |
| 不重复 | 解封产出条目 + 同时到达的同指纹 agent 副本 → `SqlLogEntryBuffer.size()==1` |
| **反向验证** | 临时把解封判据改为恒 `false` → `CaptureActivityFallbackTest` **8 例中 5 例失败**（双哑、不丢行、滞回、去重折叠、闸门回落）；还原 → **全绿** |
| 定向（主项目） | `*CaptureDiagnostics*` / `*SqlCaptureGate*` / `*AgentManager*` / `*SqlLogConsoleBridge*` / `*BundleParityGuardTest` / `*CaptureActivityFallback*` / `*AgentAttachStats*` / `*AgentStatusText*` 合计 **172 例 0 失败 0 错误 0 跳过**；还原验证轮 47 例 0 失败 |

**测试口径说明**：C1 路当时在同一工作树改 agent 子项目且处于编译中间态（`SqlHookTransformer.skippedByVersionCount()` 重复定义等），主项目定向测试以 `-x :sql-visualizer-agent:compileJava -x :sql-visualizer-agent:compileTestJava` 复用上一轮已产出的 agent jar 运行；agent 子项目测试留统一收口轮。

#### 待与 C1 联调

- 心跳键名已按 C1 `SenderLoop.heartbeatBody()` 实测对齐；若后续改键名，只需在 `AgentAttachStats.KEY_VALUE` 加候选，IDE 侧无其他改动。
- `catchAll` 语义按 C1 注释（0=纯白名单 / 1=兜底开 / 2=已熔断），2 显示「接口兜底已熔断」。
- `matched` 现由 `CaptureBudget.attachMatchedTotal()` 上报（含白名单 + 接口），与 `weavable` 不再必然同值；面板「命中数」取两者较大值。

#### 未尽项

- `docs/变更记录.md` / `docs/索引/开发计划.md` 与调研报告索引同步留给券 11 收口（C1 形态确定后一次写全）。
- `SqlLogPanel.kt` 取数接线（新增两个诊断字段）虽不在原写集清单内，但为让新字段在生产路径生效所必需（与 C1 无交集），已一并落地。
- `verifyGate` / 全量 `testShards` 未跑（按分工留只读收口代理）。

### 7.3 两路合并与主控收口（2026-10-30）

- C1 提交：`2f5e40c0`（券 04/07）、`d5576cae`（券 01/05/06/08/09 + 心跳 + 测试 + §7.1）
- C2 提交：`4ab5cb6c`（券 02/03/10 IDE 侧/11，16 文件；另含 `SqlLogPanel.kt` 取数接线——原写集清单外但为生产生效所必需，与 C1 零交集）
- 合并：`t-capture-catchall` → dev（`f1037958`，无冲突；两路按模块分区，仅计划 §7 各自小节）

### 7.4 收口门禁读数（2026-10-30）

- **`verifyGate`：3/3 Compatible**（IU-243.28141.41 / IU-253.33514.17 / IU-262.10968.63，报告 11:19 生成）
  ——C1 把 ASM **9.4 → 9.8**（上限 major 69），属大改动判据，已覆盖。
- **`testShards`：8517 tests / 0 failures / 0 errors / 9 skipped**，`BUILD SUCCESSFUL in 9m 56s`
  （较 T-CaptureCoverage 那轮 8492 增 25 例，即本轮新增的 agent 侧 237 例与 IDE 侧 172 例中的净增量）。

**中途两次 `LogPipelinePerfTest` 红，判定为负载抖动（非本任务回归）**，证据三档：
1. 被测量路径未被本轮改动：`filterBySearch` 在 `log/LogEntryFiltering.kt`，其下游 `formatTimestampFragment`
   在 `log/LogEntryRendering.kt`——两文件在本轮合并的 `git diff --stat` 里**零命中**；测试自建 20000 条
   `LogSqlEntry` 直接调纯函数，不经过桥/agent/面板。
2. 用例自带注释即声明负载敏感：「本机实测 20~45ms（**随机器负载波动明显**），设 80ms 留一倍余量」，
   且「判据仍是不得退化为平方级——真退化会在 500ms 以上」。两次失败读数 103.93 / 103.87ms 与 139.15ms
   （正则档 120ms 预算）都在「30~50% 超预算」区间，离平方级退化差一个数量级。
3. 同期机器状态：31.9GB 内存 free 8.6GB、7 个 java 进程合计 6.7GB（含 3.7GB 的 Gradle daemon），
   与 C1/C2 两路并行构建残留叠加；第三轮（负载回落后）同一用例**通过**，全量 8517/0/0/9 绿。
   → 结论：**环境性假失败**，登记为观察项（若后续频繁复现，再按 T-PerfSweep28-05 的先例调预算或加
   「同轮取多次中位数」的稳健判据，不擅自放宽门槛）。

### 7.5 真机验收（2026-10-30，用户令「不要 GUI，用 demo 复用」）

#### 7.5.1 真实 Spring Boot + MyBatis-Plus + HikariCP 矩阵（10 族全绿）

复用既有 demo `mybatis-plus-sql-visualizer-demo/mybatis-plus-demo`（Spring Boot 2.6.3 + MP 3.5.7，
默认连接池即 HikariCP），**demo 仓零改动**；驱动用 `-Dloader.path` 外挂，数据源命令行覆盖，
SQL 全部经 curl 打现成控制器触发（无 GUI）。

| 族 | A(无 agent)/B(premain)/C(attach) 启动 | 接口 | A vs B 响应体 |
|---|---|---|---|
| MySQL 8.4.6 / PostgreSQL 16.14 / 达梦 DM8 / 金仓 V8 / 瀚高 / 海量 A / OceanBase / GaussDB Kernel 503 / SQL Server / SQLite | 6.2~8.0 s | **全 200** | **全 SAME** |

- **红线**：20 次带 agent 运行 `failed=0 / unmodifiable=0 / skippedByVersion=0`；
  `VerifyError|ClassCastException|NoClassDefFoundError|NoSuchMethodError|LinkageError` **零命中**；
  应用日志 `ERROR` 行 0；启动耗时 B−A ∈ [−0.40, +0.14] s（抖动内）。
- **双报 = 1 条**（`NestGate` 生效）：MySQL 四端点（心跳 / MP LambdaQuery / WrappedJdbc / user list）
  各 1 条事件，栈中 `HikariProxy*` 与 `com.mysql.cj.jdbc.*Impl` 同现但不重复。海量/GaussDB 的 4 条、
  SQLite 的 3 条经查是**驱动建连语句**（`pg_settings connection_info`、`pragma busy_timeout` 等），非双报。
- **兜底真救了白名单外的类**：SQLite 白名单登记 `org.sqlite.jdbc3.*`、运行时实例是 `org.sqlite.jdbc4.*`
  → attach 后 `matched=4 / matchedByInterface=2 / interfaceWoven=2`。这是券 01 在真实 Spring Boot 下的
  首个硬证据；premain 走父类织入路径，两路径互补不重复。
- **premain 冷启动 0 条 SQL 是真实事实、不是丢事件**：Spring Boot 2.6 + Hikari 惰性建池、MP 启动不探库，
  心跳 `weavable` 在首个请求前为 0、请求后到 14/13（驱动类按需加载）。
- **D1 自诊断的两个装载坑**（非插件缺陷）：①`opengauss-jdbc:5.0.0` 实为 pgjdbc 换名包（全
  `org/postgresql/*`），放进 classpath 会顶掉真 PG 驱动 → PG 全 500，移出即绿（A 形态同样 500 证明与
  agent 无关）；②`opengauss-jdbc` 只有 `-og` 后缀才是真驱动（`org.opengauss.Driver`），pgjdbc 连
  GaussDB 报 `Invalid SCRAM client initialization`。
- **未取证**：MariaDB 10.11（`114.66.55.245:20081` 两侧探测均拒连）→ 该族红线未取证。

#### 7.5.2 未支持方言的兜底验证（DuckDB 靶子）

原定 XuguDB 虚谷（DBX 在册 `20111`）未成：驱动两份都取到了（maven `com.xugudb:xugu-jdbc:12.3.4`、
厂商 `XuguDB-JDBC-12.3.7.jar`），但实例在握手阶段拒绝凭据 `[E50027] Illegal user.`（6 组组合 × 2 驱动
× 2 主机全拒）。**顺带查明一个环境事实**：DBX 清单里的「通用密码」对多主机已失效（Xugu/GBase 8a/
ClickHouse/TDengine/Neo4j/PG/MySQL 系同密码全拒）——DBX 实际用每条连接独立密文凭据
（`connection_secrets.secret_enc`），后续真机测试须走 DBX MCP 通道或逐条取真实凭据。

改用 **DuckDB**（`org.duckdb:duckdb_jdbc:1.1.3`）做机制级验证：插件 14 方言与三张白名单
（`Dialect.kt:1169-1184` / `DriverRegistry.java:24-67` / `WeaveSpecs.java:43-189`）grep 全零命中、
运行时谓词四项全 false → 纯兜底路径。

- **attach：兜底生效**。两次运行逐字一致 `matched=2 wove=2 (whitelist=0 interface=2) scan=1402
  skipped=0 failed=0`，`acceptedSpecs={DuckDBConnection, DuckDBPreparedStatement}`，宿主侧
  `op=executeBatch sqlLen=54`（批处理文本也采到）。
- **红线**：三形态退出码全 0；`(org.duckdb.DuckDBConnection) conn` 三形态全过、`impl` 仍原生类、
  `proxy=false`；10 份输出全文 grep `VerifyError|ClassCastException|NoClassDefFoundError` 零命中；
  熔断未触发。

#### 7.5.3 真机发现的缺陷与修复（premain 补挂单位错误）

**缺陷**：`CaptureBudget.msProp` 统一 `* 1_000_000` 返回**纳秒**（预算比较需要），但
`premainDelayMs`/`premainRepeatMs` 也走它，随后被 `CatchAllMatcher.scheduleDeferredPasses`
直接当**毫秒**喂 `Thread.sleep` → 默认 10 000 ms 变 **10^10 ms（约 317 年）**，补挂线程永不执行。
白名单驱动不受影响（类加载期织入），但**未知驱动的 premain 形态 100% 静默**——正是券 08 要解决的场景。

**修复**（`d4b0eecb`）：新增 `rawMsProp`（原样毫秒）供两个 premain 字段；`msProp` 保持纳秒只服务预算。
**为什么单测没抓到**：`resetForTest()` 会把两字段覆盖回正确默认值，行为断言永远绿 → 除单位契约用例外，
另加**源码扫描守卫**钉住「两字段必须经 `rawMsProp` 初始化」。

**复验（同 DuckDB 靶子）**：

| 读数 | 修复前 | 修复后 |
|---|---|---|
| `premainDelayMs` / `premainRepeatMs` 回读 | `10000000000` / `60000000000` | **`10000` / `60000`** |
| 补挂轮日志 | 无（含显式 3s 加速档也无） | `premain pass 1/5`、`2/5`；加速档 `1/2`、`2/2` |
| premain `matched` / `matchedByInterface` | 0 / 0 | **2 / 2** |
| premain 采到 SQL | 否 | **是**（`op=executeBatch sqlLen=54`） |

premain 与 attach 的 `interfaceMatchedCount=2`、`interfaceWovenCount=2`、命中类集合与首条事件**完全一致**。
**闭环**。

### 7.6 十四方言渲染套件真机矩阵（D2，2026-10-30）

复用 `DialectDockerVerificationTest` 外部实例模式（插件自己渲染的 SQL 逐保留字在真库执行），
**串行单路**跑完。矩阵：**PASS 9 / FAIL 7 / SKIP 0**（14 个显示名逐字核对 `Dialect.kt` 全部命中，
无一族走到 MySQL 静默回退）。

| 方言 | 实例 | 结果 | 词数 |
|---|---|---|---|
| H2 / SQLite | 嵌入式 | PASS | 100 / 147 |
| MySQL | 20084 (8.4.6) | PASS | 262 |
| PostgreSQL | 20033 (16.14) | PASS | 86 |
| KingBase | 20097 (V8) | PASS | 118 |
| OceanBase | 25881 | PASS | 262 |
| HighGo | 25866 | PASS | 153 |
| Vastbase | 25432 (A 模式) | PASS | 201 |
| MariaDB（替代） | 20084 用 maria 驱动 | PASS（**非真 MariaDB**，不能当结论） | 281 |
| **SQL Server** | 21433 | **FAIL 真缺陷** | 0/180 |
| MariaDB | 20081 | 环境受限（端口 dead） | — |
| DM（达梦） | 20096 | 环境受限（主项目 test classpath 无 DmJdbcDriver18） | — |
| openGauss / GaussDB | 25882 / 20105 | 环境受限（无 opengauss 驱动；pgjdbc 报 `Invalid or unsupported by client SCRAM mechanisms`） | — |
| Oracle | 20095 (Free 23) | 脚手架缺陷（见下） | — |

#### 7.6.1 真缺陷：SQL Server 无排序分页渲染非法 T-SQL（已修 `b0e79325`）

插件产物 `SELECT … FROM dev_info OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY` → 引擎原文
**`Incorrect syntax near '0'.`**；`OFFSET … FETCH` 在 T-SQL 里**必须**跟在 `ORDER BY` 之后。
**又是一处假绿**：`DialectH2VerificationTest` 走 H2 `MODE=MSSQLServer`，H2 不强制该规则
（与 T-OBExplainFormat 同型：兼容模式 ≠ 引擎现实）。

修法：`Dialect` 新增能力位 `limitRequiresOrderBy`（默认 false，`SqlServerDialect` 覆写 true），
`SqlGenerator` 在「有 limit、无 orderBy、方言要求」时补语义中性的 `ORDER BY (SELECT NULL)`
（位置在 around 片段与分页子句之前）。回归用例三条判据（补占位且位置正确 / 已有排序不补 /
MySQL+PG 不引入），反向验证短路兜底 → 必红、还原 → 绿。

#### 7.6.2 脚手架缺陷（影响读数真实性，建议另立任务）

1. 外部入口写死 `UpsertMode.REAL` 且冲突路径绑 `id=1` → Oracle/SQL Server/DM 这类无原生 upsert
   的族必挂主键冲突（Oracle `ORA-00001`），且抛错后**保留字扫描整段不执行**（Oracle 8 条语句中
   7 条其实已通过）；
2. 套件不幂等：`dev_info` 只建不删，持久库第二次跑必红（HighGo/Vastbase 首轮就是被上一轮残留挡住）；
3. `EngineSpec.multiRowValues` 注释「Oracle 不支持多行 VALUES」已过期（Free 23 实测支持）；
4. 必须加 `--no-configuration-cache`：`-Dmpvs.*` 在配置期读取，带缓存有复用上一族 URL 的假绿风险。

#### 7.6.3 六项脚手架缺陷已修（T-DialectDriverGap，2026-10-30）

用户要求「测试脚手架也修复下」。前四条是 D2 矩阵点名的，后两条是修的过程中顺着「读数凭什么可信」
补的：

| 缺陷 | 修法 | 落点 |
|---|---|---|
| 硬编码 `UpsertMode.REAL` | 新增 `DialectVerifySupport.upsertModeOf(dialect)`：`renderUpsert` 与 `renderUpsertStatement` 都返回 null ⇒ `DEGRADE_ONLY`，判定口径与渲染侧同源 | `DialectVerifySupport.kt`；外部入口改调该函数 |
| 套件不幂等 | 建表前 `runCatching { DROP TABLE dev_info }` + 收尾同样清场（无 `IF EXISTS` 的引擎靠 `runCatching` 兜） | `runTcSuite` 首尾 |
| 多行 VALUES 注释过期 | 注释改为「12c 起支持、11g 不支持」，默认按支持处理，外部实例可用 `-Dmpvs.dialect.multiRowValues=false` 回退 | `EngineSpec` / `seedRows` / Oracle 容器用例 |
| 缺 `--no-configuration-cache` 约束 | 写进外部实例入口 KDoc：`-Dmpvs.*` 由 `mpv-testing.gradle.kts` 在**配置期**经 `System.getProperties()` 透传，缓存命中会复用上一族的 `systemProperty` → 用上一族 URL 跑这一族（假绿），缓存未命中又可能静默换值 | 入口 KDoc |
| 方言名可能静默回退 MySQL | 外部入口加逐字核对断言：`byName` 对未知名称回退 `MySqlDialect`，名称写错或中文参数被转码时全绿但验的是 MySQL 方言 | 外部入口 |
| 空扫即空绿 | `assertSweepClean` 补「词表 < 50 词即失败」：原实现只判 `failures.isEmpty()`，词表没接上时空集合必然为空 | `DialectDockerVerificationTest.kt` |

配套回归用例 `DialectVerifySupportUpsertModeTest`（3 例，纯逻辑无 DB）：无原生 upsert 的三族必须
`DEGRADE_ONLY` 且两个渲染入口均为 null；注册表其余 11 族必须 `REAL`（族数写死 11，任一族的渲染入口
被误删即变红）；H2（整语句 MERGE）与 MySQL/PG（后缀形态）两种不同形态都算原生。


### 7.7 最终统一门禁（2026-10-30 13:07~13:28）

合并 master（`4b7a8075`，22 笔：含 T-ReleasePerf10 门禁限流/水位治理、T-PerfSweep29、全景审计第二十二轮）
之后，连同本轮两处修复一起跑：

- **`verifyGate`：3/3 Compatible**（IU-243.28141.41 / IU-253.33514.17 / IU-262.10968.63，报告 13:07 生成）
- **`testShards`：8522 tests / 0 failures / 0 errors / 9 skipped**，`BUILD SUCCESSFUL in 20m 57s`，
  parity 口径「计划类全集 793 个均有结果 XML」（较上轮 8517/790 增 5 例 / 3 类，即本轮两处修复的
  回归用例与合并带来的新类）
- 两条 WARN 属既有画像漂移（shard3 +17%，提示跑 `weight-refresh.ps1`），不影响判定。

**首轮门禁被内存水位门拦下（`watermarkGate`）**：`memory-hygiene.ps1` 报告 commit 72.5GB /
free 12.9GB、`suspects: 0`（无可清理孤儿），属并行会话与 IDE 占用；等待回落（free 回到 9.7GB、
java 进程从 7 个降到 4 个）后重跑即绿。**未使用 `-PwatermarkGate=off` 紧急放行**——理由：此前已出现
`LogPipelinePerfTest` 在负载下假失败，强行放行只会让门禁读数不可信。

### 7.8 驱动补齐与脚手架修复后的重跑（T-DialectDriverGap，2026-10-30 下午）

D2 矩阵里 4 个「环境受限」族（达梦 / openGauss / GaussDB / MariaDB），本轮补驱动 + 修脚手架后重跑，
另有一个官方 openGauss 实例做补充读数。每族独立一次 `test` 调用、串行，命令模板见 §7.6
（含 `--no-configuration-cache`），`-x :sql-visualizer-agent:test` 省时。

| 方言 | 实例 | 修复前 | 本轮 | 词数 | 结论 |
|---|---|---|---|---|---|
| `DM（达梦）` | `114.66.55.245:20096` DM8 | 无驱动，0.18s 挂 | **PASS 69.3s** | 254 / 254 | 达梦真机闭环 |
| `openGauss` | `103.36.220.245:25882`（GaussDB Kernel 503.1.0，openGauss 同源内核） | 无驱动 + SCRAM 报错 | **PASS 67.5s** | 186 / 186 | openGauss 方言闭环（**实例标注 Kernel 503，非 openGauss 5.0.0 官方版**） |
| `GaussDB` | 同上 `:25882` | 同上 | **PASS 69.9s** | 197 / 197 | GaussDB 方言闭环（同上标注） |
| `MariaDB` | `103.36.220.245:23306` **MariaDB 10.11.11**（本轮部署容器 `xc3-mariadb`） | 20081 端口拒连，只有「MySQL 8.4 + maria 驱动」替代读数 | **PASS 101.2s** | 281 / 281 | MariaDB 真引擎闭环（`lower_case_table_names=0`，与 OceanBase 的 1 相反） |
| `openGauss`（官方 5.0.0） | `114.66.55.245:20105` | 无驱动 + SCRAM | **仍不可用**：驱动能连上，但 `dbx_test` 在该实例 `has_database_privilege('CREATE')=false`、`has_schema_privilege('public','CREATE')=false` → 建夹具表被拒（`ERROR: permission denied for schema public`） | — | **环境受限（权限）**，非产品问题；换到 25882 才拿到读数 |

四族重跑**同时验证了 7.6.3 的脚手架修复**：达梦若仍走硬编码 `UpsertMode.REAL`，必然在主键冲突处
抛错并带崩 254 词扫描；本轮 PASS 说明能力推导生效。每族开跑前的方言名逐字核对也生效——
`DM（达梦）` 这类带中文括号的名称若被转码，新加的断言会直接失败而不是静默回退 MySQL。

**保留字扫描的空扫守卫**：`assertSweepClean` 新增「词表 < 50 词即失败」断言。原实现只判
`failures.isEmpty()`，词表若没接上（空集合）必然为空 → 空绿。现有 14 族最少 86 词（PG），下限取 50
足以拦住「词表没接上」的形态。

MariaDB：`114.66.55.245:20081` 仍然拒连（本机 socket 探测 `WinError 10061`），改在测试服务器
`103.36.220.245:23306` 部署 `mariadb:10.11.11` 容器补齐真引擎读数（D2 那轮的「MySQL 8.4 + maria 驱动」
只是替代读数，不能当 MariaDB 结论）。镜像走 `docker.m.daocloud.io`——直连 Docker Hub 单层实测只有
~76KB/s，拉 25 分钟未完；换 daocloud 后复用已下层的进度，几分钟拉完（`docker pull -q
<mirror>/library/alpine:3.19` 比墙钟可复现选源过程）。容器 `--restart unless-stopped`、`lower_case_table_names=0`。

**门禁（本轮改动收口，2026-10-30 14:06~14:22）**：`testShards` **8525 tests / 0 failures / 0 errors /
9 skipped**，`BUILD SUCCESSFUL in 11m 51s`，parity 口径「计划类全集 **794** 个均有结果 XML」（较上轮
793 增 1 类＝新增的 `DialectVerifySupportUpsertModeTest`，该类的 3 例在 shard3 通过）。
两条 WARN 仍是既有画像漂移（shard3 +20%，提示 `weight-refresh.ps1`），不影响判定。
**未跑 `verifyGate`**：本轮零生产代码改动（只动测试脚手架 + 测试 classpath 依赖 + 文档），
插件产物与 3 IDE 兼容性面不变，上一轮 3/3 Compatible 的读数仍然成立。


