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
