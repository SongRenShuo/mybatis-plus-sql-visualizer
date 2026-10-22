# T-AgentCaptureProvenance 增强捕获混合通道 SQL 日志源码跳转缺失——开发计划

> 立项时间：2026-10-14 14:53
>
> 编号：O-147 / R97 / D157 / V180（立项期间 O-146 / R96 / D156 / V179 被并行计划占用，已避让并复核 `docs/`）
>
> 基线：`master` `e15ee699`；日常 IDE：IntelliJ IDEA 2026.2.1，已安装发行版 `v2026.10.13`
>
> 状态：已实施（2026-10-14，W0~W3 全落地；定向 97 例绿 + 两轮反向验证定向命中；真机复验留用户，见 §八）。
>
> 用户现象：已开启增强捕获后，同一 SQL 日志列表中一部分条目带耗时、数据库和 `*.java:行号`，可跳转；另一部分只有 `—`，右键跳转无源码位置。用户怀疑后者仍由控制台捕获。

## 一、结论与影响

这不是“增强捕获整体失效”，也不是 `EntryJumpResolver` 对已有源码位置的解析失败。

日常 IDEA 的 `idea.log` 在 14:18:02 已记录 `AgentSession - 日志时间未命中累计 600 条`，随后在 14:22、14:29、14:31 继续增长为 800、1000、1200 条。这证明 agent 事件正在持续进入 IDE。截图同屏也有 `20ms [PostgreSQL] [RefundOrderService.java:67]`、`23ms [PostgreSQL] [RefundOrderService.java:67]`、`26ms [PostgreSQL] [MerchantWalletRechargeServiceImpl.java:43]` 三类完整条目，进一步证明增强通道在该轮运行中有效。

同时，截图中的 `—`、无 `[PostgreSQL]`、无 `[文件:行号]` 条目可高置信度判为控制台回落条目：

- `formatDurationFragment` 仅在 `executionTimeMs == null` 时显示 `—`；agent 事件解析即使协议缺 `dn` 也补 `0`，适配后会显示 `0ms`，不会显示 `—`。
- agent 适配器固定写入 `SqlSource.AGENT`，并转交 `sourceFile`、`sourceCandidates`、耗时和 JDBC URL 派生的数据库角标。
- 控制台链在无源码标记时明确构造 `sourceFile = null`、`sourceLine = 0`；它只有从日志文本成功解析时才可能补出耗时，也没有 agent 的 JDBC URL 数据库角标。

因此，用户的“疑似控制台捕获”判断成立。直接影响是：增强捕获已开启时，用户看到的仍可能是低信息的控制台版本；该行进入 `MissingSource` 分支后，会给出不适合当前状态的源码捕获引导，无法跳转到业务代码。

## 二、现场证据

### 2.1 日常 IDEA 日志

取证文件：`C:\Users\USER\AppData\Local\JetBrains\IntelliJIdea2026.2\log\idea.log`。

| 时间 | 证据 | 含义 |
|---|---|---|
| 14:18:02.681 | `AgentSession - 日志时间未命中累计 600 条` | agent 事件已大量到达；“未命中”只表示没有可配对的控制台日志时间，不代表 agent SQL 丢失。 |
| 14:18:29 起 | 截图中的 #1/#2 为无耗时、无库角标、无源码条目 | 与 agent 条目的渲染契约不符，属于控制台回落形态。 |
| 14:19:06～14:20:10 | 截图中同时出现带 `[RefundOrderService.java:67]`、`[MerchantWalletRechargeServiceImpl.java:43]` 的完整条目和无来源条目 | 同一运行窗口发生混合输入，不能归因于“增强开关未生效”。 |
| 14:22:08 / 14:29:18 / 14:31:30 | agent 未命中累计依次为 800 / 1000 / 1200 | agent 通道没有在首批条目后中断。 |

日志没有记录“每条最终入库 SQL 的输入通道、当时桥接闸门状态、去重命中对方条目的元数据质量”。因此，它足以定位到混合通道与信息丢失，尚不足以断言控制台为何在该时刻没有被闸门压住。

### 2.2 代码链路

1. `AgentEventAdapter.toEntry` 固定将事件映射为 `SqlSource.AGENT`，写入 `executionTimeMs`、`sourceFile`、`sourceCandidates` 与 `databaseName`。
2. `processConsoleLine` 在 `SqlCaptureGate.paused` 为 `false` 时解析控制台；没有 `SqlSourceMarker` 时按设计生成无源码位置的条目。
3. `AgentManager.updateOnline` 仅在“会话健康且应用确认 `disabled=false`”时将 `SqlCaptureGate.paused` 置为 `true`。回执等待、会话退化、旧 agent 混用均可使桥回落，属于为避免“双通道均零捕获”保留的安全策略。
4. `SqlLogEntryBuffer.addEntryInternal` 的去重窗口按 SQL 指纹保留先入队条目；后到同键条目仅增加 `×N` 计数后丢弃，既不比较信息完整性，也不合并来源、候选帧、耗时或数据库信息。
5. 代码已明确登记旧 agent 不支持 CONFIG ACK 时会让 agent 与控制台同时产出，并依赖上述去重窗口折叠；原设计把“列表只留一条”视为用户可见正确，却没有覆盖“先到的控制台条目比后到的 agent 条目缺少跳转元数据”的保真要求。

## 三、根因判定

### 已坐实

- 增强 agent 在本次现场持续工作；不能把所有无跳转条目归为 agent 离线或字节码织入失败。
- 无源码条目进入实时跳转时必走 `MissingSource`，因为跳转器只消费 `sourceFile` 与 `sourceCandidates`，不会也不应按表名猜测业务调用点。
- 当前去重只做“先到即留、后到即丢”，没有“同一条目可被更完整事实升级”的模型。这是源码、耗时和数据库角标丢失的直接代码缺陷。

### 高优先级待证伪前提

| 编号 | 前提 | 首先验证的办法 | 证伪后的处理 |
|---|---|---|---|
| V180-1 | 现场无来源条目是控制台桥在 agent 已确认采集期间产出，而不是某种缺字段的 agent 协议事件。 | 为成功入库、去重丢弃和升级决策记录不可逆的捕获路径枚举；复现后对照截图 SQL 指纹。 | 若为 agent 缺字段，转查 agent 侧事件 JSON 与 `StackLocator` / JDBC URL 采集，保留本计划的合并机制但调整主修复点。 |
| V180-2 | 控制台桥在现场被打开是 ACK 等待窗、健康会话切换、旧 agent 混用还是实际 gate 状态异常。 | 在 `updateOnline` 闸门边沿和控制台解析入口记录抽样的 `online`、ACK、活动 PID、gate 值及回落原因。 | 若 gate 在确认采集时仍为 false，按状态机缺陷修复；若属于合法回落，保持回落能力，只修低信息条目被永久保留的问题。 |
| V180-3 | 相同 SQL 的两路条目确属同一次执行而非 60 秒窗口内两次独立执行。 | 使用既有 `dedupeKey` 加参数签名、时间差和采集路径做复现记录；不扩大 SQL 文本匹配范围。 | 若现有去重语义本身把独立执行折叠，则另立去重语义计划；本计划不得借“信息升级”改变窗口定义。 |

## 四、目标、范围与非目标

### 目标

1. 每个实时 SQL 条目具备可诊断的捕获路径，用户和维护者能区分增强、控制台回落及融合条目。
2. 同一去重桶内后到的更完整事实不得因为“先到的控制台副本”被永久丢弃。
3. agent 已带可解析源码时，最终保留的列表条目必须可跳转；agent 无源码而控制台标记有源码时也不得反向降级。
4. 控制台回落仍保留：agent 未确认、失联或主动停采时不能为了消除无跳转行而造成零捕获。

### 非目标

- 不按表名、Mapper 名或 SQL 文本猜测业务调用点；无真实栈帧/源码标记的条目继续明确为不可精确跳转。
- 不把“增强捕获”退化为只保留 console 结果，也不因本问题移除 ACK 确认闸门。
- 不在本计划内改变 60 秒去重窗口、SQL 指纹、分页 COUNT 的无调用点语义或历史文件格式；如落盘需要记录新 provenance，采用可选字段向前兼容，不重写旧历史。
- 不把逐行来源标签做成默认噪音。默认只对“增强已启用却无真实源码”的退化条目展示短提示；完整条目维持现有紧凑样式。

## 五、设计方案

### W0：可归因探针与复现夹具

先补不改变捕获结果的轻量观测，解决“从日志无法知道某条 SQL 为什么无源码”的盲区：

- 在内存条目增加内部 `CaptureProvenance`（agent、控制台 EP、控制台桥轮询、融合、未知）；它与 `SqlSource` 分工明确：后者是 MyBatis/Hibernate/JDBC 等 ORM 日志形态，前者是进入插件的传输通道。
- `AgentSession` 入站、`SqlLogConsoleBridge.emitEntries`、EP 产出处都显式赋值；历史旧条目读取为 `UNKNOWN`。
- 为一次“来源缺失入队”、一次“同指纹跨通道冲突”、一次“桥在 agent 会话存活时仍产生条目”提供限频 INFO 诊断。字段只含指纹、来源、是否有 `sourceFile`/候选、是否有耗时/数据库、gate 与 ACK 摘要；不得输出 SQL 参数、JDBC 凭据或整段堆栈。
- 建立纯逻辑复现夹具：先喂无源码控制台条目，再喂同指纹 agent 条目；反向顺序及 agent 无源码、控制台有标记两种交叉形态也必须覆盖。

W0 的验收是能从单条诊断判断 V180-1/2，而不是仅靠 UI 猜测。日志验证后再决定 W1 的闸门分支是否需要修正。

### W1：同去重桶的信息融合

将当前“后到即丢弃”改为“保留一个行号稳定的条目，但允许更完整事实升级”。核心是纯函数 `mergeDuplicateEntry(existing, incoming)`，而非简单的“agent 总是覆盖 console”。

合并优先级：

1. 保留原条目的会话序号和列表位置，避免一条正在被选中/复制的 SQL 因升级跳行。
2. 源码字段按可用性合并：有主源码位置优先；`sourceCandidates` 只由 agent 补充；不得以空值覆盖已有位置。若两侧都有位置，保留可跳转的主位置并在诊断保留冲突事实。
3. 执行耗时、更新行数和数据库角标以 agent 的结构化事实优先；控制台已解析到但 agent 缺失时保留控制台值。
4. `logTimestamp` 保留控制台嗅探的日志时间；捕获时间使用首次入桶时间，避免列表排序因后到升级跳动。
5. provenance 升级为 `MERGED`；若仅第二条更丰富但 SQL 并非同一语义，绝不合并，仍遵守既有 dedupeKey + 窗口判定。
6. `×N` 继续表示被折叠的重复观测次数；一次升级同样属于第二次观测，计数不回退。

实现上扩展去重索引，使其能在 O(1) 判断重复后定位当前保留条目；更新/淘汰/清空/手工去重/接管纪元重置都必须与现有索引维护面同步。不能为解决此问题恢复锁内全表扫描。

### W2：闸门状态机的针对性修复

仅当 W0 证实“最新 agent 已 ACK `disabled=false` 且健康，但 console 桥仍连续产出”时实施：

- 查清 ACK 清空、回执回调、心跳巡检和多会话关闭是否会把 `SqlCaptureGate` 错误翻为 false。
- 修复状态边沿后仍保留“未确认/确认停采/会话失联 → console 回落”的安全语义。
- 多会话时按当前项目的健康且已确认会话计算，不允许已停止 PID 的残留状态压住或错误放开桥。
- 若 W0 证明这是旧 agent 混用的正常回落，不改闸门策略；W1 的融合即为主交付，并在诊断中提示版本混用。

### W3：跳转反馈与历史兼容

- `MissingSource` 的实时提示读取 `CaptureProvenance`：增强已在线但当前条目来自控制台回落时，明确提示“本条未携带源码位置”，不再笼统建议用户再次开启增强捕获。
- 对 `MERGED` 或 agent 来源且有候选帧的条目，复用既有 `EntryJumpResolver` 主位置→候选帧回退，不新增文本猜测。
- 评估 `SqlLogParamCodec`、自动保存和历史读回对 provenance 的可选字段兼容；旧文件保持 `UNKNOWN`，不迁移、不报错。
- 增加右键“复制捕获诊断”或等价低噪声入口，内容只含路径、是否有跳转元数据与去重/闸门摘要，便于后续用户日志取证。

## 六、测试、反向验证与真机验收

### 定向自动化测试

- `SqlLogEntryBufferTest`：控制台先到、agent 后到时仅保留一条且升级为可跳转；agent 先到、控制台后到不得降级；双方各自仅有部分字段时字段并集正确；升级后的序号、位置、去重计数、淘汰和通道纪元重置均正确。
- `AgentManagerIntakeGateTest`：ACK 确认采集后桥让位；未确认/停采/会话摘除回落不变；若 W2 触发，覆盖多会话和 ACK 竞态。
- `AgentEventAdapterTest` 与控制台桥测试：provenance 不改变既有 SQL 参数恢复、候选帧和 JDBC URL 脱敏契约。
- `SqlLogPanelLiveJumpWithSourceTest`、`EntryJumpResolverTest`、渲染测试：融合条目直达业务文件；真实无来源的 console 条目给出“回落且无位置”提示，不触发旧的误导性引导。
- 历史读写测试：新字段存在时回环，缺字段旧样本仍可读。

### 反向验证

1. 临时摘掉“重复后信息融合”分支：`控制台先到_后到agent升级为可跳转` 必须变红；恢复后转绿。
2. 临时让空值覆盖已有源码：`agent先到_控制台后到不降级` 必须变红；恢复后转绿。
3. 临时撤去 ACK 后桥让位判据：确认采集的桥封停用例必须变红；恢复后转绿。
4. 真机重复执行截图中的 `RefundOrderService` / `MerchantWalletRechargeServiceImpl` 路径：在“控制台先到 + agent 后到”的诊断样本中，最终仅保留一行并可跳转；仅有 console 回落时保留一行、说明原因但不伪造跳转。

### 门禁

实施子任务仅跑上述相关测试类；W1/W2 结束后按项目三档验证制度由独立只读收口任务执行权威全量。若改动协议、保存格式或 agent 模块，追加 `:sql-visualizer-agent:test`；不在本计划阶段运行全量构建或发布门禁。

## 七、风险与约束

- 去重语义本身会折叠 60 秒窗口内同文本的独立执行；本计划只提升已被既有规则折叠桶的保真，不借机扩大匹配范围。
- 不能因为“agent 更完整”无条件覆盖 console：agent 也可能缺源码，而 console 拦截器标记可能恰好有真实位置，故必须字段级合并。
- `LogSqlEntry` 是数据类且已被历史、去重、UI、MCP 审计多处消费；新增字段默认值必须保持旧构造点与旧持久化兼容，并核对 `equals`/`copy` 对既有缓存的影响。
- 日志探针不可泄露 SQL 参数、连接凭据或用户业务包全栈；采用 SQL 指纹、布尔完整性位和限频计数。
- agent/IDE 通讯和平台 UI 线程的既有边界不变：后台接收只改模型与缓冲；源码解析仍沿 `EntryJumpResolver` 的读权限约定执行。

## 八、实施记录

### 2026-10-14 立项取证（日志三卷对读）

- 完成日常 IDEA 2026.2 `idea.log`/`idea.1.log`/`idea.2.log` 与截图对照：截图时段 agent 事件持续入站（日志时间未命中累计 600→1200，13:55→14:31 稳定流），带 `[PostgreSQL] [RefundOrderService.java:67]` 的条目证实增强通道有效；`—`（无耗时）行按渲染契约（agent 事件缺 `dn` 也补 0ms、`—` 恒等于 `executionTimeMs == null`）只能产自控制台通道——两通道并存实锤。
- **闸门史取证**：`增强捕获在线态切换: true -> false` 出现在 13:14:40，此后到 14:32 新进程启动前无回升记录；但事件流持续（未命中计数在涨），且现场两个会话 pid（47740 投递事件、12144 持续 ACK）的建立记录在所有日志卷中均缺位。据此判定：**现场无法离线裁决 V180-1/V180-2**（回落沿完全静默 + 会话建立面缺记录），可观测缺口即 W0 的立项动因，而非先入为主改闸门状态机。

### 2026-10-14 实施（分支 `t-agent-capture-prov` 自 master `16e30bc4`，工作树 mpsv-o147）

- **W0 通道归因**：新增 `CaptureProvenance`（AGENT / CONSOLE_EP / CONSOLE_BRIDGE / MERGED / UNKNOWN，`LogSqlModels.kt`），三条生产点打标——`AgentEventAdapter.toEntry`（AGENT）、`SqlConsoleBridgeFilterProvider.EpLineFilter`（CONSOLE_EP）、`SqlLogConsoleBridge.emitEntries`（CONSOLE_BRIDGE）；`SqlLogEntryBuffer` 对「缺源码入队」与「同指纹跨通道融合」两条限频 INFO 诊断（10s 窗 + 抑制计数，内容红线＝指纹前 8 位/布尔位/通道/`SqlCaptureGate` 快照，不含 SQL 原文与参数）。
- **W1 同桶信息融合**：去重索引由「键 → 时间戳」升级「键 → 保留实例」（`isDuplicate`/淘汰回扫/单删重算/裁剪重建/通道纪元重置五处维护面同步，O(1) 判定不变）；去重命中分支改调纯函数 `mergeDuplicateEntry`——桶身份（序号/捕获时间/rawSql/params）不动，耗时/行数/库标/mapper 语句/请求标签/报错/日志时间按「只补空不覆盖」（`existing ?: incoming`）合并，源码位置真实值优先（MP 分页伪标记让位于 agent 真实调用点）、候选帧/类名/栈快照只增不删，跨通道标 MERGED、同通道纯重复零替换（全等即跳过，不制造假刷新）；融合仍计一次 ⨉N、不触发 `onEntryAccepted`、`addEntry` 返回 false 不变。
- **W2 闸门回落沿补日志**：`AgentManager.updateOnline` 在闸门 true→false 沿输出健康会话 PID 与各会话 ACK 确认态快照（此前回落沿完全静默，只有在线态整跳留痕）。**未改回落语义**——ACK 等待窗/心跳抖动的控制台回落是防「双通道零捕获」的既定安全侧（V180-2 的裁决依赖新增日志的下一次现场读数）。
- **W3 反馈分流与持久化**：`LogEntryJumpOps.jumpToSourceRealtime` 的 MissingSource 分支对控制台回落条目（`isConsoleFallbackWithoutSource`：无源码/无候选且通道为 CONSOLE_EP/CONSOLE_BRIDGE/MERGED）改轻提示，新双语键 `log.panel.jumpToSource.consoleFallback`，UNKNOWN 保守维持既有引导；历史 pd 载荷新增可选 `pv` 键（枚举名非序号；缺键/未知值降级 UNKNOWN，`SqlLogFileReader` 新旧两版头两处重构点回填），旧历史文件零迁移。

### 验证读数（2026-10-14，工作树 mpsv-o147 独立 build，未借用并行会话沙盒）

- **定向**：`SqlLogEntryBufferTest` 59 + `SqlLogParamCodecTest` 15 + `LogEntryJumpOpsConsoleFallbackTest` 4 + `AgentEventAdapterTest` 13 + `EntryJumpResolverTest` 6 = **97 例 0 失败 0 错误 0 跳过**（BUILD SUCCESSFUL；首轮曾 1 红＝本用例 `maxEntries=2` 下新条目必然挤出队首 B，系测试预期写错非生产缺陷，修正断言后复跑绿）。
- **反向验证探针 1（摘融合分支）**：`REVERSE_PROBE_MERGE_DISABLED=true` 使融合分支短路 → 59 例 **恰 3 红**（控制台先到_agent 后到_融合升级 / MP 分页伪标记升级 / agent 先到_控制台后到_不降级），其余 56 例保绿（含「只补空不覆盖」「融合条目淘汰回收」两条——其判别点不依赖融合本身，如实登记）→ 还原后复跑绿。
- **反向验证探针 2（强制空值覆盖）**：`mergeDuplicateEntry` 的源码规则临时改为无条件取 incoming → 59 例 **恰 1 红**（agent 先到_控制台后到_不降级，即「真实源码不被空值覆盖」断言）→ 还原后复跑绿。
- 探针残留复核：`rg REVERSE_PROBE src/` = 0 命中；探针常量与注释均已删除。
- **被改文件的守护测试复跑**（本次改动触达 `AgentManager.updateOnline` 与桥/EP 入队点，补跑其守护类）：`AgentManagerIntakeGateTest` 20（含既有「在线且确认采集_桥封」/「接管沿_项目缓冲去重索引按通道纪元重置」用例）+ `SqlLogManagerBehaviorTest` 35 + `SqlLogConsoleBridgeMarkerTest` 13 + `SqlLogCaptureRealtimeScenarioTest` 9 + `EpBridgeSinglePathTest` 7 + `SqlConsoleBridgeFilterProviderTest` 3 = **87 例 0 失败 0 错误 0 跳过**（BUILD SUCCESSFUL）。
- **反向验证探针 3（撤去 ACK 后桥让位判据）**：`AgentManager.updateOnline` 的 `gateNow` 临时退回「按在线封桥」（`online`，即 ACK 判据被撤）→ `AgentManagerIntakeGateTest` 20 例 **恰 1 红**（`test在线但未确认_桥不封`——该判据自己的用例），其余 19 例保绿 → 还原后复跑 **20/20 绿**、`git diff` 该文件为空、探针注释残留 0。
  **过程留痕（首轮我把它免做了，事后补做）**：合并前我以「W2 未改闸门语义、无靶可打」为由跳过本条，登记为「由既有用例承担」——这是**拿既有测试的绿当判别力证据**，而反向验证的意义正是不假设判别力（既有用例是本任务之外的历轮作者写的，其判别力未经本轮实测）。用户质询后补做，结论：既有 `AgentManagerIntakeGateTest` 对 ACK 判据确有判别力，且该探针在本轮改动后仍有效（本轮给 `updateOnline` 加的是回落沿日志，未改变判据本身）。**教训登记**：计划列明的反向验证不因「本轮未改该语义」而免除——除非同时证明该用例的判别力已被近期轮次实测过。
- 未跑全量 `testShards`（改动面 = log 包 + 两个消息资源，不触 build.gradle.kts/plugin.xml EP/依赖版本；按验证三档分层交下轮收口批）。

### W2-补（2026-10-14 真机复验发现并修复）：接管沿「全清索引」切断同一次执行的双通道副本

**真机现场（沙盒 IDE + `mybatis-plus-demo` 8081，两轮并发压测）**：

- 第一轮（约 2340 请求 + 2 次 storm，agent 事件 2800+）：闸门全程封停、无回落，控制台只在启动后 13s 窗口产 1 条条目（`捕获归因: 通道=CONSOLE_BRIDGE 指纹=846aa9aa`）——**设计预期内的正常态**；融合诊断出现 1 次（`AGENT+AGENT→AGENT 升级=[日志时间]`）。
- 第二轮（重启应用 + 4 路并发约 4300 请求）暴露缺陷，时间线（同一日志、毫秒级）：

  | 时刻 | 事件 |
  |---|---|
  | 16:10:38,697 | 用户手动点「捕捉」→ `startCapture: manual=true`，capturing=true |
  | 16:10:38,842 | 控制台通道（EP）入库一条**无源码**条目：`捕获归因: 通道=CONSOLE_EP 指纹=d359b6d2 有源码=false 有耗时=false 有库标=false` |
  | 16:10:38,848 | agent ACK 到位 → **接管沿触发「去重索引按通道纪元重置」**（旧语义＝清空整个索引） |
  | 16:10:39,057+ | 同一条 SQL 的 agent 事件涌入，此后 45s 内该 SQL 又被执行 4000+ 次 |

  控制台副本入库后 **6ms** 索引被清空 ⇒ 随后 agent 对同一条 SQL 的观测不再判重、**新开一行**——正是用户报障的「一行带源码、一行 `—` 无源码」形态。根因是 P3（2026-10-09）为防「陈旧桥纪元条目吞 agent 首批」而做的**全清**语义，与本计划的融合机制在接管沿上互相抵消。

**修复**：`resetDedupeIndexOnChannelHandoff()` → [`rebuildDedupeIndexAfterHandoff(graceMs = 5_000, nowMs)`](../../../src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogEntryBuffer.kt)：重建时**只清陈旧键、保留 `now - 5s` 内的键**——新鲜键（同一次执行的控制台副本）留下供 `mergeDuplicateEntry` 融合升级为可跳转行，陈旧键照旧清掉（P3 原意保留）。`nowMs` 可注入以保持既有合成时间戳用例的确定性。接线与日志文案同步（`AgentManager.updateOnline` 接管沿）。

**读数**：定向 `SqlLogEntryBufferDedupeIndexTest` 10 + `SqlLogEntryBufferTest` 59 + `SqlLogParamCodecTest` 15 + `LogEntryJumpOpsConsoleFallbackTest` 4 + `AgentEventAdapterTest` 13 + `AgentManagerIntakeGateTest` 21 + `SqlLogManagerBehaviorTest` 35 = **157 例 0 失败 0 错误 0 跳过**；**反向验证**：把 `HANDOFF_MERGE_GRACE_MS` 置 0（等价旧全清）→ `AgentManagerIntakeGateTest` 恰 1 红（`test接管沿_新鲜桥纪元键按同一次执行融合`），还原后复跑绿、探针残留 0（缓冲层用例显式传 grace，故对默认常量不敏感——判别力分工：缓冲用例钉机制、网关用例钉生产默认接线）。

### 真机验收记录（2026-10-14，沙盒 IDE + `mybatis-plus-demo` 8081，MySQL 3306 本机）

**轮 1（约 2340 请求 + 2 次 storm，agent 事件 2800+）**：闸门全程封停、无回落（`闸门回落` 计数 0）；控制台通道仅产 1 条——启动后 13s 窗口内 `捕获归因: 通道=CONSOLE_BRIDGE 来源=MYBATIS 指纹=846aa9aa 有源码=false`（agent 接入前）；融合诊断 1 条 `AGENT+AGENT→AGENT 升级=[日志时间]`。**结论＝设计预期正常态**：agent 已确认时控制台不产条目，面板无不可跳转行。

**轮 2（重启应用 + 4 路并发 4300+ 请求）**：暴露 W2-补 缺陷（见上节 6ms 窗口时间线），已修。

**轮 3（重启沙盒载入修复版后三组操作）**：

| 操作 | 读数 | 结论 |
|---|---|---|
| 应用重启（捕获已开） | `premain 会话建立 16:28:55,626 → ACK#1 16:28:55,635`（10ms）；控制台零条目 | 未命中窗口——启动期无 MyBatis SQL 可解析，ACL 后闸门即封 |
| 手动「停止→捕捉」×2（16:31:37/40、16:31:48/50） | 同一毫秒序列：`40,010 pushCaptureActive(true)` → `40,010 ACK#3 disabled=false` → `40,010 接管沿` → `40,011 attachFilter` → `40,012 已开始捕捉`；两次均无 `捕获归因` | **「捕捉」路径上闸门先于过滤器挂载升起 ⇒ 窗口按构造 ≈0ms**（该顺序对用户有利：控制台产条目被最小化，非缺陷） |
| 32 路重压 + 手动「停止→捕捉」第三轮（16:38:55/57） | 同样同一毫秒序列（`57,778 pushCaptureActive` → `ACK#9` → `接管沿` → `attachFilter`；`57,779 已开始捕捉`），仍无 `捕获归因` | 32 路并发（空库、单请求 ~1ms）**未能拖慢 ACK**——窗口不因请求并发度而开，只取决于 agent 回执延迟 |

**旁证（控制台链在跑但被闸门正确压制）**：压测期间 `控制台解析诊断（增量）` 的「空参数行拦下」计数 6 秒内 4150→4272，说明压测 SQL 行确实进入控制台解析链；闸门封停时只做日志时间嗅探、不产条目 ✓ 与设计一致。另外 `闸门回落（桥恢复解析）` 在用户 Stop 应用时给出确切原因（`online=false, 健康会话=[], 确认态=[pid=20024:已断]`）——V180-2 想要的"闸门为何放开"首次具备可离线裁决的日志。

**窗口成因（轮 2 日志复核，勘误上文"配方"表述）**：轮 2 的 153ms 窗口**不是**"控制台先拿到解析机会"，而是 **CONFIG 往返本身被拖慢**——`16:10:38,695 pushCaptureActive(active=true) 已下发 CONFIG disabled=false` → `16:10:38,848 ACK#2`（153ms）。那一刻应用启动约 19 秒、正被 4 路压测且 JIT 正在编译查询热路径，agent 读线程处理 CONFIG 帧被挤慢；控制台过滤器在 38,696 挂上、38,697 capturing=true，于是这 153ms 里打印的 SQL 被控制台通道捕获（38,842 `捕获归因: 通道=CONSOLE_EP`）。

**据此归纳的复现条件（必要条件，非充分条件）**：应用侧 CONFIG 往返被拖慢（冷启动后 JIT 热化期 + 并发压测）**且**此刻触发 sendConfig（toggle/设置变更）。**2026-10-14 16:43 按此配方冷启动重试未命中**（`43,26,651 下发 → 43,26,652 ACK#2`，亚毫秒；控制台零条目）——说明该窗口是**偶发竞态**，不能稳定复现，也不因加大并发而变宽（32 路实测无效：空库单请求 ~1ms，应用未饱和）。

**口径**：W2-补 的**已验证面＝单测 157 例 + 定向反向验证 + `testFast` 3739 例 0 失败**；**真机面＝跨通道 `→MERGED` 未观测**——窗口宽度取决于 agent 回执被拖慢的程度（warm 实测 1~10ms，cold+busy 实测 150ms，见轮 2），属时序竞态而非稳定复现条件。真机结论按"未复现"记录，不写作"已验证"。

### 遗留（真机复验，用户执行）

1. 复现原报障路径（增强捕获开启 + 应用控制台有 SQL 输出）：确认同一条 SQL 最终**只保留一行且可跳转**（融合生效），或仅控制台一条时右键得到「该条来自控制台日志捕获，未携带源码位置」轻提示（不再是笼统引导）。
2. 若仍出现无源码条目，取 `idea.log` 中 `捕获归因:` 行（新诊断）与 `增强捕获闸门回落（桥恢复解析）` 行回填 **V180-1/V180-2** 裁决：通道=AGENT 缺源码 → 转查 agent 侧栈定位（`StackLocator`）；通道=CONSOLE_* → 按回落行给出的健康会话/ACK 快照定位闸门放开原因。
3. **V180-3**（同 SQL 两路观测的指纹是否相等）沿用现场比对：若控制台与 agent 条目因还原口径差异未被折叠（各自成行），属既有指纹口径边界，本计划未扩大匹配范围，如需收敛另立计划。
4. 历史文件回环：新写入条目读回后 `pv` 通道保留（旧文件读回 UNKNOWN 属预期）。
5. **W2-补 需在新构建上复验接管沿窗口**（本轮真机跑的是修复前构建）：重启应用或点「捕捉」后，若控制台条目与 agent 条目在 5s 内相遇，应看到 `重复观测跨通道融合: … 通道=CONSOLE_EP+AGENT→MERGED 升级=[源码,耗时,库标…]`，且面板同一条 SQL 只留一行、带 `[Xxx.java:行号]`。
