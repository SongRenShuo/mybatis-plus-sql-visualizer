# T-StatementWeave：agent 语句级织入——吸收 DebugTools「attach 即生效」精华——开发计划

> 2026-10-05 用户点名转正：原 [T-FirstUsePremainConsent 计划](20261005-1026-T-FirstUsePremainConsent-首次启用增强捕获首轮运行零捕获修复-开发计划.md) §三 S6「agent 语句级织入」从登记候选升级为本任务。用户质询「DebugTools 插件支持项目启动后贴附上，这个精华没有吸收吗」——结论：只吸收了 retransform 教训（`AgentMain` javadoc「DebugTools 半成品陷阱的硬验收项」），**织入点模型这个真正的精华没吸收**。本计划写完停等确认，未获确认不实施。

## 一、缺口归因（现状架构事实）

- 当前捕获模型：`SqlHookTransformer` 只织驱动 `connect(String,Properties)`，连接**创建时**包装代理；`StatementHandler`（采集核心：参数捕获/耗时/栈定位/批处理）是 `InvocationHandler`，活在包装代理后面。
- attach（agentmain）虽对已加载驱动类 `retransformClasses`，但改的只是 connect 方法体——只影响**之后新建**的连接；池化场景（Hikari）attach 前建好的老连接永远裸奔 → attach 兜底对存量连接结构性零捕获。
- DebugTools 类工具 attach 即生效的原因：织入点在**语句执行方法**（execute 族方法体）——钩子随方法调用触发，与连接/Statement 对象的创建时间无关，retransform 后下一次 execute 即带钩子。

## 二、已实证事实（2026-10-05 javap + zip 扫描，实施者可直接引用）

| 项 | mysql-connector-j 8.0.33 | h2 2.3.232 |
|---|---|---|
| 语句实现类 | `com.mysql.cj.jdbc.StatementImpl` / `ClientPreparedStatement` / `ServerPreparedStatement`（另有 pooled Wrapper 族见 §四.5） | `org.h2.jdbc.JdbcStatement` / `JdbcPreparedStatement` / `JdbcCallableStatement` |
| 带参形态 | `StatementImpl.execute(String)` / `executeQuery(String)` / `executeUpdate(String)`（含 int[]/String[] 重载） | `JdbcStatement.execute(String)`（final）/ `executeQuery(String)` / `executeUpdate(String)` |
| PS 无参形态 | `ClientPreparedStatement.execute()` / `executeQuery()` / `executeUpdate()` | `JdbcPreparedStatement.execute()` / `executeQuery()` / `executeUpdate()` |
| 连接归属 | `ConnectionImpl.getURL()` **public** 直调 | `JdbcConnection.getURL()` **包私有**（反射 + setAccessible，classpath 应用为未命名模块可行） |
| 内部字段线索 | StatementImpl 无裸 sql 字段（SQL 在 Query 对象链，`getQueryAsString()` 待 javap） | `JdbcStatement.batchCommands`（ArrayList\<String\>，批处理现成）/ `executingCommand`（CommandInterface，toString 是否含 sql 待验） |
| 批处理入口 | `addBatch(String)`（带参，可织入累积） | 同左 |

IDE 侧既有地基：`AgentEventAdapter` 事件消费、`AgentUrlRegistry`/`JdbcUrlDialects` URL 推断链、`CONFIG` 热下发、脱敏/跳过前缀/业务帧定位全部在 `EventBuilder` 下游——语句级钩子只须产出一个同构事件。

## 三、方案设计

### S1：DriverRegistry 语句面登记

- `isWeavable` 拆两面：驱动面（现 connect 织入，不动）+ **语句面**（新增：上述两驱动的语句实现类名单；按具体类名登记，织不上安全降级——沿用现 classloader 契约「织入期判定可见性、回调自吞一切 Throwable」红线）。
- 织入面匹配走精确名 + 前缀双轨（防驱动小版本类名漂移静默漏织；漂移即降级不炸宿主）。

### S2：StatementWeaver（新织入器，与 ConnectWeaver 并列）

- 织 execute 族方法体，**入口+出口双插桩**：入口解析 sql 并记 nanoTime 起点，出口组装事件（耗时/异常/影响行数对齐代理层 `StatementHandler` 语义）；异常路径记录类名+消息后**原样重抛**（宿主语义不变，代理层同红线）。
- 带参形态 sql 从方法参数 aload 直取；无参 PS 形态从 S3 弱映射取，取不到按 S3 盲区策略处置。
- `executeBatch` 从 `addBatch(String)` 织入累积的批串组装（弱映射/字段，同 PS 口径）。
- 回调入口 `SqlHookCallbacks.onStatement(...)` 新增静态方法（签名被织入字节码硬编码，与 ConnectWeaver↔onConnection 同步约束相同，javadoc 互指）。
- retransform 合规：只改方法体、不增删成员（与 ConnectWeaver 同约束，agentmain 可重转换前提）。

### S3：SQL 解析链与盲区处置

- **PreparedStatement sql 登记**：织驱动的 `prepareStatement(String)`（及 `nativeSQL` 不需要）出口，把「PS 实例 → sql」写入**弱引用映射**（WeakHashMap/Identity 弱键，PS 被 GC 或 `close()` 织入移除——内存边界见 §四.1）。attach 后新建的 PS 全覆盖。
- **attach 前已存在的 PS**（映射未登记）：一期**跳过不上报 + 计数器**（`unresolvedPreparedStatements`，宿主侧可观测）；驱动字段反射兜底（mysql Query 链 / h2 executingCommand）列二期，先证伪 §四.2/3 再做。
- Statement 带参形态零盲区（sql 就在参数里）。

### S4：连接归属链

- 语句钩子从 `statement.getConnection()` 拿物理连接，URL 解析顺序：包装代理标记（attach 后新连接已带）→ 驱动内部 `getURL()` 反射（mysql public 直调 / h2 setAccessible 包私有）→ 最近 connect 钩子 URL 兜底 → 空（IDE 侧推断链接管，事件 url 允许空）。
- 方言识别复用 `JdbcUrlDialects`，空 url 事件面板按「来源未知」既有降级显示。

### S5：attach / premain 接线

- `AgentMain.retransformLoadedDrivers` 扩展为双面（驱动面 + 语句面）：attach 时对**已加载的语句实现类** retransform——这是「attach 即生效」的直接落点。
- premain 路径零改动（transformer 挂上后新加载类自动织入）。

### S6：IDE 侧配套

- 无 url 事件消费兜底（推断链）。
- **文案口径修订**：R2 S2 能力边界通知与状态行从「挂载前的连接不在捕获范围，重新运行后完整生效」改为「已挂载，此后执行的 SQL 即时可见；挂载前已 prepare 且未再执行的语句不在范围内」——attach 兜底价值从「下轮才完整」升级为「立即可用」。
- attach 兜底挂载成功后可同步触发一次回执性提示计数（登录 idea.log，不新增弹窗）。

### S7：测试与反向验证

- agent 单测：`FakeDriver` 扩展语句实现类 + 织入端到端（对齐 `ConnectWeaveEndToEndTest` 形态）：带参 execute / PS 映射形态 / 盲区跳过计数 / 批处理 / 异常原样重抛 / url 反射失败降级。
- 真驱动端到端：mysql 8.0.33 + h2 2.3.232 双 jar 驱动级测试（现有真驱动测试同形态），**attach 时序模拟**：先建连接池 → 再 install（retransform 语句面）→ 断言老连接上的后续 execute 产出事件（本任务的核心判别用例）。
- 反向验证：摘 S5 的语句面 retransform → 上述 attach 时序用例必红（两次读数留档）。
- IDE 侧：`AgentPremainConsentTest` 文案断言随 S6 修订；agent+config 全包回归 + `testFast`。
- 宿主零影响红线验证：钩子内异常注入 → 宿主 SQL 执行结果不变（现有采集红线测试同款）。
- 沙盒走查（留下轮批可并）：长跑 demo 中途 attach 场景面板可见性 + 老连接盲区计数展示。

## 四、待验证前提（实施者先证伪再动手）

1. **PS 弱映射内存边界**：键必须弱引用（PS 池长持有不泄漏）；规模上限与驱逐策略；`close()` 织入移除的时机准确性（连接池复用 close 是归还不是关闭——归还后映射应保留，判据=GC 而非 close，倾向只按 GC 清理不织 close）。
2. **mysql sql 字段反射链**：`StatementImpl` 的 Query 对象链（`query` 字段 → `getQueryAsString()`）逐环 javap 实证后才可做字段兜底；一期不做则盲区跳过策略即终态。
3. **h2 字段兜底可行性**：`executingCommand`（CommandInterface）的 toString/内部字段是否含原始 sql；`batchCommands` 能否直接服务批处理事件。
4. **插桩复杂度**：入口+出口双插桩在 ASM 层对多返回点方法（catch/finally/异常路径）的覆盖——若复杂超预算，降级方案=出口单点（duration 缺省，sql 仍可得），先保功能后保耗时精度。
5. **Wrapper 族是否登记**：mysql `StatementWrapper`/`PreparedStatementWrapper` 是 pooled/connection-wrapper 形态，Hikari 默认透传原生句柄大概率不需要；误登记会撞 `ProxySupport.isOurProxy` 幂等（无害但浪费）。先只登记原生实现类，实测漏了再补。
6. **性能红线**：每条 execute 过钩子的 volatile 短路成本须与现代理层同级（现有 perf 测试口径跑对照）；入口解析 sql（带参形态）是字符串引用传递非拷贝，预期零成本。
7. **驱动版本面**：实证面为 demo 工程在用版本（mysql 8.0.33 / h2 2.3.232）；更老/更新版本类名漂移按 S1 前缀+降级契约兜住，不追全版本兼容。
8. **`executeBatch` 空批/混合批**语义与代理层 `batchSqls` 组装口径对齐（防止同一批两种通道产出两种格式）。

## 五、验收标准

1. **核心**：应用已运行、连接池已建好的进程，中途 attach 后，**老连接上执行的新 SQL**（Statement 带参 + attach 后新建 PS）在面板即时可见——这是与现状的判别性差异。
2. premain 场景全链路回归绿（现行为零变化）。
3. 盲区如实：attach 前 prepare 的 PS 不产生错误/伪 sql 事件（跳过 + 计数可见）。
4. 宿主零影响：钩子异常自吞、SQL 执行语义与结果不变（红线测试）。
5. 反向验证「摘语句面 retransform 必红」两次读数留档；agent 定向 + `testFast` 绿。

## 六、范围外（登记不做）

- 更多驱动（PostgreSQL/Oracle/达梦等）语句类登记：随真库验证轮扩面（本任务架构留好登记位即可）。
- 驱动字段反射兜底（mysql Query 链 / h2 Command）：二期，前提 §四.2/3 证伪后立项。
- redefine（非 retransform）路径：不碰（增删成员能力带卸载屏障语义，风险面大收益小）。
- connect 织入与包装代理层不拆：语句级织入是**补位**通道，代理层对新连接的参数捕获/耗时精度更优，双通道并存按事件幂等去重（同语句同时间窗一条）——去重判据随 S7 定。


## 七、实施记录（2026-10-05 当轮回填，工作树 mybatis-plus-sql-visualizer-tsw @ t-statementweave）

### 落地内容

- **S1 登记单源化**：语句面/prepare 面名单与织入形态标注统一在 `StatementWeaver.SPECS`（`Kind[]` 按类标注），`DriverRegistry.isStatementWeavable/isPrepareWeavable` 委托 `specOf` 判定——防两份名单漂移（守卫钉住）。登记面：mysql 8.x（StatementImpl/ClientPreparedStatement/ServerPreparedStatement）+ 5.x 遗留路径 + h2 2.x（JdbcStatement/JdbcPreparedStatement/JdbcCallableStatement）；prepare 面：ConnectionImpl（mysql 双包路径）/JdbcConnection。
- **S2 织入器**：`StatementWeaver`（ASM Tree API，入口+出口双插桩）。出口覆盖 **ARETURN/ATHROW/IRETURN/LRETURN** 四类返回点——executeUpdate→int、execute→boolean 走 IRETURN，executeLargeUpdate→long 走 LRETURN，结果装箱（Integer/Long.valueOf）后恢复原值返回；PREPARE 面只插 ARETURN，ADD_BATCH 只插 RETURN。
- **回调四件套**（`SqlHookCallbacks`）：`onStatementEnter`（token=nanoTime，停采 volatile 短路 + 代理层门禁双闸，0=短路）／`onExecuteExit`（EXEC 与 BATCH 统一签名，批 SQL 按 op 名从登记表取）／`onAddBatchExit`（批串累积）／`onStatementPrepare`（PS→SQL+URL 登记）。`WovenEvents` 组装事件（字段语义对齐代理层；params 置空与 timestamp 出口回推两处结构性差异如实注释）。
- **S3 归属登记**：`StatementRegistry`（WeakHashMap 弱键，PS GC 即清账；盲区跳过 + `unresolvedCount` 计数可观测；`clearForTest` 测试缝）。`UrlResolver`（getURL 按类缓存 Method，h2 包私有 setAccessible；`noteConnectUrl` 在 onConnection 记最近 URL 兜底）。
- **S4 归属链**：ctx.url（prepare 时连接）→ `stmt.getConnection()` 反射 → 驱动 getURL → 最近 connect URL → 空（IDE 推断链接管）。
- **双通道去重门**：`WeaveGate`（ThreadLocal）——`StatementHandler.executeWithCapture` 在 `method.invoke(raw)` 期间置位，织入入口读到即短路。不设门：包装连接的语句 invoke 到的 raw 方法体已被织入，同一条 SQL 双通道双事件。
- **S5 attach 即生效落点**：`AgentMain.retransformLoadedDrivers` 扩展三面（驱动 connect + 语句 + prepare），attach 时对已加载语句实现类 retransform——存量对象的下一次 execute 即带钩子。
- **S6 IDE 侧**：能力边界通知双语口径升级（「此后执行的 SQL 即时可见（含挂载前已存在的连接）；挂载前已 prepare 且未再执行的语句不在捕获范围内」）。
- **S7 测试**：`StatementWeaveEndToEndTest` 8 例（带参/登记/盲区计数/门禁短路/停采短路/批处理/异常原样重抛/loader 契约）+ 主仓 `StatementWeaveWiringGuardTest` 6 例（源码扫描接线守卫 + 登记单一真相 + 通知口径）。

### 实施过程三坑（留档）

1. **出口只认 ARETURN 的盲织**（探针实证）：首版出口只插 ARETURN/ATHROW——executeUpdate→int、execute→boolean 走 IRETURN，整个出口钩子没插上，织入体执行但零事件。修正为四类返回点分派 + 基本类型装箱。
2. **IRETURN 装箱参数顺序反**：`Integer.valueOf` 先于 `ILOAD` 压栈 → VerifyError（Bad type on operand stack）。先压参再调 valueOf。
3. **BATCH 出口栈错位**（红探针钓出）：BATCH 出口块按 6 参压栈（多压一个 sql 槽）但 `onBatchExit` 描述符只收 5 参——token 读到垃圾值、failure 槽套上 int[]，回调链静默错乱且零事件零计数。定案=撤掉独立 onBatchExit，EXEC/BATCH 统一 `onExecuteExit` 签名（sql 槽 BATCH 传 null，op 名分支在回调内）。教训：**织入块的压栈序必须与回调描述符逐槽对账，多一个 ACONST_NULL 都是无声灾难**；这类错误 VerifyError 都未必报（栈形态经 COMPUTE_FRAMES 后可能恰好可验），只有运行期行为探针能钉住。

### 待验证前提的实测落账（对照 §四）

1. 弱映射：WeakHashMap 弱键 + 不织 close（连接池归还≠废弃）——按定案落地，泄漏面=弱键本身。
2/3. mysql Query 链/h2 Command 字段兜底：一期未做（盲区跳过+计数），维持二期登记。
4. 插桩复杂度：入口+出口双插桩落地（含基本类型返回分派），未走「出口单点」降级案。
5. Wrapper 族：未登记（原生实现类先行），守卫登记名单可随时扩。
6. 性能红线：双闸均为 volatile 读，与现代理层同级；对照性能轮（F 系列）未跑，登记下轮性能报告批。
7. 驱动版本面：demo 在用版本已 javap 实证；漂移按织不上降级契约。
8. batch 口径：织入通道批串格式 `;
` 连接与代理层 batchSqls 一致；双通道并存去重由 WeaveGate 保证（代理层优先，织入通道只服务无代理的老连接——天然互斥，无需时间窗去重）。

### 反向验证与读数

- **红**：摘 `AgentMain` 语句面/prepare 面 retransform → `test接线守卫_retransform三面_语句面摘除必红` 红（断言「retransformLoadedDrivers 必须对语句面 retransform（attach 即生效落点，摘掉必红）」）。
- **绿**：恢复后 agent 子项目 **101/0**（含 8 新例）+ 主仓定向 **31/0**（WiringGuard 6 + AgentPremainConsent + AgentConsentFlow）。
- **真机时序判别用例留下轮 runIde 批**（单测 JVM 无 Instrumentation，retransform 语义无法纯单测复现——本类只证织入通道自身正确；「先建池再 attach 存量连接可见」的端到端判别随下轮沙盒走查）。

### 遗留

- 全量门禁（testShards/agent 子项目全量）按三档分层另派只读收口代理。
- 性能对照（织入钩子每条 execute 的固定开销）随下轮性能报告批（§四.6）。
- 更多驱动语句类登记随真库验证轮扩面（§六）。

## 七A、F74 合并前置修复（20261006 合并评审轮）

性能二十三轮审计 F74 定案「合并前必改」，随本轮合并命令一并实施：登记批串 `executeBatch` 消费后不复位 + `clearBatch()` 未织入——同一条 Statement 批串跨批次只增不清（内存/组装耗时双无界 + 下一批事件携带上一批残串）。修复三件：

1. **消费即复位**：`onExecuteExit` 批串读后 `setLength(0)`（成功与异常出口同口径，对齐 JDBC 驱动批缓冲语义）。
2. **clearBatch 面登记**：`StatementWeaver.Kind.CLEAR_BATCH`（`clearBatch()V`，RETURN 单点插桩）+ 回调 `onClearBatchExit`（停采/代理门禁同款双闸；有登记才清不建账，清账语义 `StatementRegistry.clearBatchOf`）。
3. **规模边界定案**：复位+清账后批串天然有界＝宿主单个批周期内 addBatch 总量，与驱动自身批缓冲同寿，**不另设截断上限**（截断会静默篡改事件内容，比无界更糟）。

反向验证两读数（摘修复三刀＝删复位行 / 摘 statementFace 的 CLEAR_BATCH / 摘 `matches()` case；注意 e2e 走测试缝自传形态面，摘 SPECS 登记杀不到 e2e——行为红必须摘 `matches()`）：

- **红**：e2e 10 例 2 红（`批处理_executeBatch消费即复位_跨批次残串不得串包` + `clearBatch织入_清账后executeBatch零残串`）+ 主仓守卫 `test接线守卫_F74批串消费复位与clearBatch清账` 红。
- **绿**：恢复后 agent e2e **10/0** + 主仓守卫 **7/0**（守卫四件套升级五件套）。

过程坑留档：agent 子项目被根项目 `test` 任务依赖且 `failOnNoMatchingTests=false`——根项目带 `--tests` 过滤器跑守卫时 `:sql-visualizer-agent:test` 会以空匹配重跑并清空结果目录，e2e XML 随后跑被清非假绿；绿读数须在 e2e 单跑后立即取 XML。

## 七B、PG attach 零事件修复（T-PgWeave，2026-10-09，工作树 mybatis-plus-sql-visualizer-pgweave @ t-pgweave）

用户真机复现「对运行中 PostgreSQL 应用 attach 增强捕获 → 面板零条目」。沙盒 idea.log 证据链：settings-rescan 补挂 pid → loadAgent（AgentLoadException(0) 良性）→ 会话建立 → CONFIG ACK disabled=false（应用确认在采）→ 接管沿去重索引重置 → 28 秒内零事件（P2 观测行「增强通道首条事件已到达」全日志 0 次＝事件从未到达 IDE，与去重窗无关）。

**根因＝§六首条遗留落地**：语句面/prepare 面名单只覆盖 MySQL 与 H2。attach（agentmain）路径下存量池连接无法回溯 connect 包装，语句面无 PG 类可 retransform → 零事件；premain 路径不受影响（连接建立时被包装走代理面），故此前 PG 能捕获、掩盖了该缺陷。

**修复（§六首条扩面）**：

- 语句面新增 postgresql `PgStatement`/`PgPreparedStatement`/`PgCallableStatement`（继承链 PgCallableStatement → PgPreparedStatement → PgStatement）；prepare 面新增 `PgConnection`。javap 实测 42.7.8/42.7.10：execute 族 / executeBatch / executeLargeBatch / addBatch(String) / clearBatch 与 prepareStatement(String…) / prepareCall(String…) 描述符与 `Kind` 形态假设一致。
- 同批评估后一并登记 Oracle（`OracleStatement`/`OraclePreparedStatement`/`OracleCallableStatement`；prepare 面取包私有基类 `oracle.jdbc.driver.PhysicalConnection`，T4CConnection 继承其方法）、SQL Server（`SQLServerStatement`/`SQLServerPreparedStatement`；`SQLServerConnection`）。
- **未登记（如实）**：达梦——javap 形态全中，但字节级探针暴露**织入器帧-局部变量既有局限**：`DmdbStatement.addBatch(String)` 出口 `chop_frame(2)` 丢弃局部变量，ADD_BATCH 出口块读 this/首参落在帧后即 `VerifyError: Bad local variable type`；把出口块移到帧前又会在分支目标处缺帧（h2 `JdbcStatement` 探针实证）。须待出口锚点/帧重写方案，本轮不猜写。各驱动 pooled/代理包装族、PS 无参 `addBatch()` 同未登记。

**验证**：

- 新增 `StatementWeaverVendorCoverageTest`（7 例）：谓词层（登记类命中正确一面且两面不相交）+ 字节层（真实驱动类字节织入 changed + 子优先 loader 定义并初始化触发 JVM 栈图校验）+ 校验器判别力自证（撒谎的 StackMapTable 必被拒）。踩坑留档：① 隔离 loader 定义会被**包私有父类**跨 loader 拒（`IllegalAccessError: cannot access its superclass`）→ 对被织类厂商包前缀 child-first，使父类与被织类落同一 runtime package；② `resolveClass` 在 JDK17 HotSpot **不触发**栈图校验（负向用例假绿实测）→ 必须 `Class.forName(..., true, loader)` 初始化（链接含校验）。
- **绿读数**：agent 定向 **25/0**（VendorCoverage 7 + FramePatchVerify 3 + StatementWeaveEndToEnd 10 + ZeroLoadClassWriter 5）。
- **反向验证（摘登记必红）**：摘 SPECS 的 `org.postgresql.jdbc.PgStatement` → VendorCoverage **4 例红**（谓词层 2 + 字节层 1 + 单一真相 1）；恢复即绿。

**真机复测口径（用户执行）**：PG 应用跑着 → 勾开增强捕获 → 触发 SQL → 面板应出条目（含语句面路径）。

## 七C、达梦（DM8）语句面织入（T-DmWeave，2026-10-09，工作树 mybatis-plus-sql-visualizer-attachpool @ t-attachpool）

**用户 P0 真因（现场侧钉死）**：`mybatis-plus-join-demo` 编译产物 `target/classes/application.yml` 的 `spring.profiles.active: dm`（Maven 勾了 dm profile），实际跑达梦 DM8（`jdbc:dm://dbx-test-host:20096?schema=MPJ_DEMO`，driver `dm.jdbc.driver.DmDriver`），非 MySQL/PG。`DriverRegistry` 连接面登记了 `dm.jdbc.driver.DmDriver`，但 `StatementWeaver.SPECS` 语句面/prepare 面**一个达梦类都没有** —— attach 到在跑的达梦应用时 connect 面无法回溯存量池连接、语句面无类可 retransform ⇒ matched=0、零钩子、零事件；控制台桥与厂商无关，故关增强即恢复。

**本地复现（红）**：新增 childapp `ChildPoolApp` DM 模式（JDK8 目标，warm 建连接并跑一次，attach 后同连接新建 PS executeQuery）。摘除达梦登记时读数：`attach retransform: matched=1 wove=0 unmodifiable=0 failed=1 [dm.jdbc.driver.DmDriver=VerifyError]`，子进程 100 轮零事件（`eventReceived=false`）。**注意 matched=1 已含一处此前未知缺陷**：connect 面 `DmDriver.connect` 出口织入产物在 JVM 校验即失败（见下）。

**javap 定型（DmJdbcDriver18 8.1.3.140）**：`DmdbStatement` 自带 `execute(String)/executeQuery(String)/executeUpdate(String)/executeLargeUpdate(String)`（EXEC_PARAM）、`executeBatch()/executeLargeBatch()`（BATCH）、`addBatch(String)`、`clearBatch()`（另有同名字段 `public boolean clearBatch;`，但方法 `clearBatch()V` 也在）；`DmdbPreparedStatement extends DmdbStatement` 自带无参 `execute()/executeQuery()/executeUpdate()/executeLargeUpdate()/executeBatch()/executeLargeBatch()`（EXEC_CTX/BATCH）；`DmdbCallableStatement` 无自有 execute 族（继承 DmdbPreparedStatement）；`DmdbConnection` 自带 `prepareStatement(String…)/prepareCall(String…)`（PREPARE）。该族 `extends dm.jdbc.filter.Filterable`；实测 `getConnection()` 返回裸 `DmdbConnection`、`prepareStatement` 返回裸 `DmdbPreparedStatement`（非过滤包装态），故挂在 public 方法上即可触发。

**字节级探针（关键）**：旧织入形态下，达梦**所有** timed 出口方法均 `VerifyError: Bad local variable type`（`DmdbStatement.execute(String) @85 aload_0`、`DmdbPreparedStatement.execute() @71`、`addBatch(String)`/`clearBatch()` 等）——达梦 javac 在出口处对已死局部发 `chop_frame`，出口帧 `locals[0/1]=top`，而旧出口块读原局部 `this`/首参。仅 `DmdbConnection`（prepare 面）通过校验。

**修复（织入器改造 + 按面登记）**：
- `StatementWeaver.weaveMethod`：timed 面改为**入口捕获、出口读自有槽**——方法头把 `this`（EXEC_PARAM 再加首参 SQL）存入超出原 `maxLocals` 的新槽，出口块读自有槽（不再读原局部）；`patchFrames` 按槽位追加 token(long)/本类类型/String。对既有厂商语义等价（`this`/首参同对象），字节形态变化，全套既有 weaver 用例复跑零回归。
- 登记面（`SPECS`）：`DmdbStatement`/`DmdbPreparedStatement` = {EXEC_PARAM, EXEC_CTX, BATCH}；`DmdbConnection` = {PREPARE}；`DmdbCallableStatement` 不单列（无自有 execute 族）。**未登记（如实）**：达梦 `ADD_BATCH`/`CLEAR_BATCH` 面出口非 timed（仍读原局部），同 chop_frame 根因仍 VerifyError，本轮不猜写。

**验证**：
- **绿读数（真库 E2E，JDK8 目标）**：`attach retransform: matched=4 wove=3 unmodifiable=0 failed=1 [dm.jdbc.driver.DmDriver=VerifyError]`，首条事件 `op=executeQuery sqlHead=SELECT 1 AS attachpool_dm registryUnresolved=0`，事件 `sd` 栈含 `dm.jdbc.driver.DmdbPreparedStatement#executeQuery`。
- **反向验证（摘登记必红，贴两次读数）**：摘达梦登记 → 上列红读数；恢复 → 上列绿读数。
- **厂商对齐守卫反向验证**：摘达梦登记 → 守卫红，报文 `dm.jdbc.driver.DmDriver 的连接面已登记，但厂商前缀 dm. 在 StatementWeaver.SPECS 无语句面/prepare 面条目…`；恢复即绿。

**遗留（如实）**：达梦 connect 面 `DmDriver.connect` 出口织入命中同一 chop_frame（attach retransform 行残留 `failed=1 [dm.jdbc.driver.DmDriver=VerifyError]`）。存量连接场景已由语句面/prepare 面覆盖、事件到达，故本轮未改 `ConnectWeaver`；新建达梦连接不走代理面，但其语句面仍经织入通道产事件。后续若要 connect 面覆盖，按同款「入口捕获、出口读自有槽」改造 `ConnectWeaver`（需 `EXPAND_FRAMES` + 帧补丁）。

**四格矩阵（T-AttachPoolWarm，同工作树先行）**：HikariCP 4.0.3 真实池（预热 2 物理连接）/ JDK8 目标 / mysql-connector-java 8.0.28 / Debug(-agentlib:jdwp) 四格全绿（事件到达，`HikariProxyConnection` 代理族下织入面照常触发），排除 MySQL 池/JDK/驱动版本/Debug 维度。

**真机复测口径（用户执行）**：达梦应用跑着（`profiles.active=dm`）→ 勾开增强捕获 → 应用控制台应出现 `[SqlVisAgent] attach retransform: matched=4 wove=3 …` 且随后 `[SqlVisAgent] 织入通道首条事件已产出`；触发 SQL → 面板出条目。

**真机复验结果（主控亲跑，2026-10-09 晚，243 沙盒）**：✅ 通过，两路都出条目。
- attach 路线（与用户报障同形）：沙盒预置 `enhancedCaptureEnabled=false` → IDE Run 起 dm profile 应用（pid=30952；控制台实证 `HikariProxyConnection@… wrapping dm.jdbc.driver.DmdbConnection@…`）→ 控制台桥先出条目 #1（18:20:01）→ 设置页勾「增强捕获」并 Apply → 上升沿 attach（18:25:56，`AgentLoadException(0)` + `CONFIG 已生效 disabled=false`）→ 同端点再请求 → 面板 #2「[18:26:11] 26ms」（agent 通道条目，带毫秒耗时=桥给不出的字段）。宿主侧 `[SqlVisAgent] attach retransform: matched=6 wove=6 unmodifiable=0 failed=0` 与 `织入通道首条事件已产出 op=execute`；IDE 侧 `增强通道首条事件已到达并进入消费链（pid=30952）`（修复前该行恒缺）。
- premain 路线（增强开着启动）：`premain 会话建立：pid=35260（启动即捕获）` → 面板 #3「[18:34:15] 11ms [DM（达梦）]」。
- 截图存 `F:/code/Java/Gitee/SongRenShuo/mpsv-walk/`：`ide-21-attach.png`（安装读数行）、`ide-23-list.png`（面板三条目）、`ide-34-crop.png`（补全候选）。
- 收工核对：8082 closed、无残留 java/fsnotifier、demo 仓与 dev-bak 工作树零改动。

## 七D、首条事件取证行去 SQL 原文（2026-10-14）

- **背景**：用户报障宿主控制台有插件打点噪音，逐行核到本计划落的「织入通道首条事件已产出」行——原带 `sqlHead=`（SQL 前 80 字符，见上文 §七C 的读数实例）。
- **判据**：该行的用途是「钩子在产」的宿主侧证据（与 IDE 侧「首条事件已到达」配对三分故障域），SQL 原文对判读无贡献；而它**不过 agent 的脱敏开关**（脱敏只作用于事件载荷），Statement 路径的 SQL 可能含字面量（PreparedStatement 路径虽是占位符版也无打印必要）。
- **处置**：`sqlHead=` → `sqlLen=`（只留长度）。行内判读字段 = op / sqlLen / registryUnresolved，宿主日志不再留业务文本，三分故障域能力不变。
- **回归**：`WovenEventsFirstFireDiagTest` 增 `sqlLen=15` 正断言 + 「不得含 SELECT/FROM t」负断言（后者即本改动的守卫，回退 `sqlHead` 必红）。同批核过 `AttachPoolWarmGateTest` 只按行首前缀摘行、不解析 `sqlHead`，不受影响。
- **同批核过但未改**：`[SqlVisAgent] attach retransform:` 行已由 `install` 的幂等闸（`installed` 静态位 + `sqlvis.agent.active` 系统属性，两闸都在打印之前）保证**每 JVM 至多一行**，无需另加去重；每个新 JVM（应用重启）各一行属预期。
