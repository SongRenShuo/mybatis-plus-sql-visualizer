# T-SqlListCatchup：SQL 列表追赶延迟与风暴期刷新成本优化（2026-11-07 立项）

> 起因：2026-11-07 用 join-demo（PID 65608，端口 8082）三阶段并发压测（4/32/64 线程，7134 请求全 200）实测，HTTP 层与 agent 通道零异常，但用户走查发现**面板加载速度跟不上实际请求速率，请求结束后列表还在持续追赶**。压测脚本留档 `mybatis-plus-sql-visualizer-demo/stress-test/stress_capture.py`。

## 一、现状（链路上每一环都读过，位置准确）

数据从捕获到上屏分三段，各段的节流点：

1. **摄取段（控制台桥）**：`SqlLogConsoleBridge.kt` 轮询制——`pollTimer` 起步 `FAST_POLL_INTERVAL_MS=500ms`，指数退避 ×2 至 `POLL_INTERVAL_MS=2000ms` 常驻（:690-697）。每拍 `scanAll()` 读控制台文本 → `handleConsoleLine` 做 Preparing/Parameters 配对 → SQL 还原。风暴期解析吞吐低于生产速率即积压，请求停止后队列仍有余量——这是「结束后还在追」的机制源头。agent 通道（`SenderLoop.java`）事件即时 flush，**不是**瓶颈。

   > **【20261107 证伪订正】**上段桥机制描述有误：解析**不在轮询拍里**——`handleConsoleLine` 由平台 `applyFilter(line, entireLength)` 逐行驱动（`LogFilterProxyHandler.kt:23-27` → `SqlLogConsoleBridge.kt:356`），每行在控制台输出线程同步完成解析（线程模型 KDoc 自证 `SqlLogConsoleBridge.kt:388-390`）；`scanAll()` 不读控制台文本、无扫描位、无逐行循环（`SqlLogConsoleBridge.kt:222-264`），git 全历史自 T-M31-03（`ce816772`）起即 filter 逐行制。残余追赶的真实归因：EDT 刷新队列（券 A/B 域）+ 平台 ConsoleViewImpl 文本 flush（filter 上游，桥不可达）；桥侧尾巴有界（≤2s 静默桶轮询拍，非滚动积压）。证据链与处置见 §九。原文保留不改动，供追溯。
2. **缓冲段**：`SqlLogEntryBuffer.kt` 内容通知双阈值节流（:864-876）——`CONTENT_NOTIFY_BATCH=20` 条或 `CONTENT_NOTIFY_INTERVAL_NANOS=200ms`，原子量无 Timer，这段已合理。
3. **刷新段**：`SqlLogManager.notifyUi()`（:580-588）——`uiFlushPending` 合拍只跳过「在途那一拍」，EDT 一空立刻刷下一拍，**无时间窗下限**。风暴期每条 SQL 执行都改计数 → 每秒几十拍高频小刷新，每拍 `SqlLogPanel.refreshList()`（:1729 起）全量做：`snapshot()` 物化 + `dedupeCountsSnapshot()` + `structureKeyCounts()` O(N) 全量计数 + 过滤链 + diff + JList 重绘。单拍不贵（`maxEntries=200` 封顶），但每拍重复劳动 × 每秒几十拍 = EDT 被面板刷帧吃掉，IDE 全局体感变卡。

教训关联：T-SqlLogPerf P5「监听加载有延迟」同族问题；T-PerfSweep 系列的「先量化再调参」口径。

## 二、目标 / 非目标

**目标**
1. 风暴期（~60 SQL 语句/s）面板刷新拍数从数百次/min 降到每秒个位数（时间窗合拍），EDT 占用显著下降。
2. 单拍 `refreshList` 的计数计算从「每拍 O(N) 全量」改为「增量推送变更键」。
3. 控制台桥在风暴期保持追赶语义但解析不积压失控，追赶进度可量化可见。
4. 全部既有定向测试绿 + 新增反向验证用例。

**非目标**
- 不改捕获语义（不丢条目、不丢计数、⨉N 徽章口径不变）。
- 不动 agent 通道（`sub-projects/sql-visualizer-agent`）。
- 不改 dedupe/跨通道折叠判据本身。

## 三、方案（四券，写集互斥可并行）

### 3.1 券 A：notifyUi 刷新时间窗下限（`SqlLogManager.kt`）

`notifyUi()` 保持「在途跳过」语义，追加：flush 完成时记 `lastFlushAtNanos`；新通知到达时若距上次 flush < 150ms（常量 `MIN_FLUSH_INTERVAL_MS`），不再 `invokeLater` 立即刷，改为挂一个 Alarm 在剩余延迟后补刷一拍（Alarm 触发时若已有更新在途则跳过）。**窗口内第一条仍立即刷**（保住「单条 SQL 秒显」的日常体验，只压合并拍）。

### 3.2 券 B：计数快照增量化（`SqlLogEntryBuffer.kt` + `SqlLogPanel.kt`）

- buffer 侧：利用既有 `dedupeKeyPresence` 与 structureKey 惰性缓存，新增「变更键」增量接口——`pendingNotifyCount(): Int`（诊断读数，券 D 用）+ 通知携带本次变更的 dedupeKey 集合（或提供 `drainChangedKeys(): Set<String>`）。
- 面板侧：`refreshList` 中 `dedupeCounts`/`templateCounts` 不再每拍全量重算——维护本地累积 map，只按 drain 到的变更键增量更新；`structureKey` 未变的条目计数沿用。
- 兜底：设置开关翻动 / clear / 去重开关翻转等已知的「索引重建」事件点全量重建一次（对齐 P6 的 rebuildDedupeIndexAfterHandoff 口径）。

### 3.3 券 C：控制台桥风暴感知轮询与解析预算（`SqlLogConsoleBridge.kt`）

> **【20261107 证伪订正】本券已证伪结案（未实码）**——本节字面机制（扫描位行距、2000 行/拍预算）无锚点：桥解析是平台 filter 逐行制（控制台输出线程），`scanAll` 无文本解析路径，「本拍解析 N 行剩 M 行」这个量不存在；强行落码须在 `scanAll` 新造文本解析路径，与 filter 逐行路径双路重复产出，违反 §二非目标。证据链、三个可选处置与采纳结论见 §九「券 C：证伪结案」。原文保留不改动，供追溯。

- 轮询节奏：`scanAll()` 单拍仍解析不完（上次扫描位后新增行数超阈值）时**不退避**——保持快速拍或直接跟拍连续 `scanAll()`，直至追平才恢复指数退避（现状是无论积压多少都机械 ×2 到 2000ms，风暴中越积越多）。
- 解析预算：单拍解析行数/时长封顶（预算常量），超预算的剩余行留到下一拍——保证单拍不长时间霸占调用线程。
- 退避重置：检测到「有新行且本拍没追平」→ 重置回快速拍；连续 N 拍追平才退避。

### 3.4 券 D：追赶量化读数（`CaptureStatusBar.kt` + `LogPerfProbe.kt`）

- 状态栏/诊断行新增「追赶中：落后 N 条」（buffer `pendingNotifyCount` + 桥通道未解析行数，取大者），追平后消失。
- `LogPerfProbe` 加两个打点：`refreshList` 每拍耗时分布、桥 `scanAll` 每拍耗时分布——A/C 的优化效果必须有读数可验，不许按体感调参。

## 四、决策点（推荐值）

| 决策 | 推荐 | 理由 |
|---|---|---|
| 最小刷新间隔 | 150ms | 人眼对列表滚动无感知差异的下限附近；再大伤「秒显」体感 |
| 时间窗实现 | Alarm 补刷，非固定周期 Timer | 无通知时零开销；风暴期拍距自然收敛到 150ms |
| 风暴判定（桥） | 单拍解析后仍有未处理行 | 直接、无新状态 |
| 解析预算 | 行数优先（如 2000 行/拍），时长作保险丝 | 行数可预测；时长受行内容影响波动大 |

## 五、待验证前提（实施者先证伪再动手，报告里逐条给结论）

1. **前提：尾巴延迟主要在控制台桥轮询/解析，而非 EDT 排队。** 证伪手段：先落券 D 的探针打点跑一轮定向压测，若 `refreshList` 单拍耗时中位数 > 桥 `scanAll` 且 EDT 队列深，则把优先级倒转（A 提前、C 缩水）。**这是唯一可能推翻券序的前提。**
2. **前提：`invokeLater` 的 `uiFlushPending` 合拍在高频通知下确实退化成高频小拍**（而不是被平台自身合并）。用现有 `LogPerfProbe` 若有刷新计数打点；没有就在券 D 里补。
3. **前提：`structureKey` 惰性缓存使 `structureKeyCounts` 的 O(N) 是便宜的一遍扫。** 若实际是每条重新解析指纹，券 B 收益被低估，需重估。
4. **前提：`SqlLogPanel.refreshList` 的 diff 更新对「计数变化但内容不变」的条目只重绘不重建**（若每次都整表 model 重建，需在券 B 一并处理）。

## 六、写集与实施顺序

| 券 | 写集（互斥） | 依赖 |
|---|---|---|
| A | `SqlLogManager.kt` | 无 |
| B | `SqlLogEntryBuffer.kt`、`SqlLogPanel.kt` | 无 |
| C | `SqlLogConsoleBridge.kt` | 无 |
| D | `CaptureStatusBar.kt`、`LogPerfProbe.kt` | B 的 `pendingNotifyCount()` 访问器（接口先约定，D 可与 B 同拍约定后并行） |

- 实施顺序：A/B/C 三路并行 → 合入集成分支 → D 落探针 → **定向压测复测（join-demo 脚本）拿前后对比读数** → 收口。
- 集成分支：`t-sqllist-catchup`（工作树 `.worktrees/sqllist-catchup`），子券分支 `t-sqllist-catchup-{a,b,c,d}`。
- 提交纪律：每完成一件可提交的就立即提交；掉线不得留下未提交的半成品。

## 七、验收

1. **定向测试**：各券新增/改动面跑定向（`--tests <FQCN>`；涉及面板 UI 的遵守 L13——testFast 绿不算绿，平台 UI 测试在全量跑到）；新增反向验证：时间窗内高频通知合拍数、增量计数与全量重算等价性（随机构造变更序列，断言两口径结果一致）、桥退避重置状态机（风暴→追平→退避各一例）、探针读数非空。
2. **复测读数**：join-demo 压测脚本复跑一轮（同参数），对比优化前后「请求结束后追赶时长」「面板刷新拍数」，两项均须有实测数字进实施记录。
3. **守卫**：合流后补跑 GiantFileBudget / GuardSelfTestCoverage / BundlePlaceholderArity 等 convention 守卫（新能力面连坐惯例）。
4. **not run 条款**：真机 GUI 验收（面板卡顿体感）由用户走查；实施报告须列明哪些没跑、为什么。

## 八、风险

- 时间窗过大伤「单条秒显」体感 → 首条立即刷语义保底，窗值留常量可调。
- 增量计数与全量重算口径漂移（历史 bug 家族）→ 等价性用例 + 兜底全量重建点一个不少。
- 桥退避重置在「慢生产但持续输出」的日常场景可能常驻快速拍（500ms）→ CPU 回升有限（现状 100ms 常驻先例），探针读数复核。
- 桥/缓冲/面板三方计数状态机历来是缺陷高发区（跨通道折叠、dedupeIndex 重建、悬挂节流余量），每券必须先读全链再动手。

## 九、实施记录

### 券A：notifyUi 刷新时间窗下限（2026-11-07，分支 `t-sqllist-catchup-a`）

**改动**：`7be82118` feat(log): T-SqlListCatchup 券A——notifyUi 刷新时间窗下限（150ms Alarm 补刷）。写集 `SqlLogManager.kt`（+76/-2）+ 新增测试 `SqlLogManagerFlushWindowTest.kt`（156 行）。机制：flush 完成时刻记 `lastFlushAtNanos`（0L=哨兵）；窗口内通知挂 `Alarm(POOLED_THREAD, this)` 补刷拍（已在挂则复用；到点时在途/已退订跳过）；窗口外含首条仍立即刷；`removeUiListener` 退订与 `dispose` 均 `cancelAllRequests` + Alarm 以服务为 parentDisposable 挂 Disposer 树。测试缝：`catchUpScheduler` 注入（SenderLoop BackoffSleeper 同款，断言调度值不赌墙钟——L18）、`lastFlushAtNanos` / `setUiFlushPendingForTest`。

**平台取证**：2024.3.7.1（243）`Alarm.ThreadToUse` 反编译实证无 `EDT` 值，仅 `SWING_THREAD`（已 @Deprecated，指 POOLED_THREAD）/`SHARED_THREAD`/`POOLED_THREAD`——选 POOLED_THREAD（动作只碰 volatile 标志 + invokeLater，任意线程安全；PreviewSchemaHighlighter 同款先例）。

**前提证伪结论（§五）**：

1. **§五.2 成立**。临时探针（未入库）对旧代码实测：`addUiListener` 计数 + 5 条 `addEntry`（逐条泵 EDT）→ **5 条通知 5 拍**，证实「在途合拍」无时间窗下限、EDT 一空立刻刷下一拍。
2. **§五.4 基本成立（代码读数，登记两点）**：`diffVisible` 以条目实例同一性 `===` 判差（`LogEntryFiltering.kt:402-448`）；⨉N 计数存侧表（`dedupeCounts`/`templateCounts`，`SqlLogPanel.kt:1744-1750` 每拍全量重算——O(N) 部分归券 B），纯计数变化 → `VisibleDiff.Same` → 零 model 写、不重建。两点登记：①计数侧的 Same 路径**无显式 `list.repaint()`**（「只重绘」并非字面实现，徽章新鲜度依赖 Swing 正常绘制循环/同屏其它变化），归券 B 关注；②融合升级形态（O-147 W1 补空字段，`mergeDuplicateEntry` + `replaceEntryLocked`）会**换实例**——同位身份断、中段离散变化走 `FullRebuild` 整表重建，但那是内容真变化，不算对本前提的反例。两处均不越写集去修。

**定向测试读数**（本机 2024.3.7.1 沙盒，`-Pkotlin.compiler.execution.strategy=in-process`，JUnit XML 判据数核对）：

| 类 | 例数 | 结果 |
|---|---|---|
| `SqlLogManagerFlushWindowTest`（新增） | 7 | 0 failures 0 skipped |
| `SqlLogManagerBehaviorTest`（回归） | 35 | 0 failures 0 skipped |
| `SqlLogManagerAutoShowTest`（回归） | 9 | 0 failures 0 skipped |

**反向验证（S2）**：摘除 notifyUi 时间窗分支重跑 → 窗口机制 5 例（test03 合拍 / test04 多波次 / test05 在途竞态 / test06 dispose / test07 removeUiListener）**全红**，窗口外语义 2 例（test01/test02）保持绿（语义在旧机制下同样成立）；恢复分支后 7 例全绿。

**not run**：join-demo 压测复测读数（§七.2 前后对比）待券 D 探针落位后统一复测；真机 GUI 卡顿体感待用户走查；CHANGELOG 回填未做（写集限本券两文件，合流收口时由主控统一补账）。
### 券 B：计数快照增量化（2026-11-07）

- 分支：`t-sqllist-catchup-b`（工作树 `.worktrees/sqllist-catchup-b`），提交：`5a32e33d`（生产：buffer 变更键出口 + 面板累积器）、`3440f739`（测试：等价性反向验证用例）。
- 写集：`SqlLogEntryBuffer.kt`（`pendingNotifyCount()` / `drainChangedKeys()` / `drainCountRebuildFlag()` / 按键出口 `dedupeCountForKey`·`dedupePresenceForKey`·`structureKeyForKey`）、`SqlLogPanel.kt`（纯逻辑累积器 `CountSnapshotAccumulator` + `refreshCountMaps`）。
- **前提证伪（§五.3）**：成立。`structureKeyCounts` = `entries.groupingBy { it.structureKey }.eachCount()`（LogSqlModels.kt:388），`structureKey` 是 LogSqlEntry 的 body lazy 属性（每实例首算一次后缓存；入队实例即快照实例，生命周期内不重算指纹）——全量聚合确是便宜的一遍扫，收益估算不需重估。附带发现：`dedupeKey` 同为 lazy，但条目入库走 `entry.copy(sequenceNumber=…)`，惰性缓存在**入库实例**上首算，与快照实例同一，无重复计算面。
- 增量机制：面板每拍先 drain 变更键、后 drain 全量标记（标记落两次 drain 之间则本拍即走全量，无陈旧窗口）；增量路径是**状态对账式**——变更键用 buffer presence 当前值与面板镜像求净差，节流窗内同键多次增减折叠为一次净变化、无悬挂余量。兜底全量重建点（buffer 置位，面板回退一次）：dedupeEnabled 翻转 / rebuildDedupeIndexAfterHandoff / clear / 淘汰扫出键域（F83 短路与 P6 重指的缺陷高发点，键域收缩时重新锚定基线）/ 去重关闭期间或不可去重条目（ShardingSphere）变更（presence 口径外）/ dedupeCounts 键上限溢出（静默逐键键集无法表达）。
- 已知边界：buffer 满 + 异键风暴下每拍都有键扫出域 → 该形态每拍仍走全量（与现状同价，无退化；该场景由券 A 的合拍承担降频）。面板 EDT 与生产线程并发时标记用 AtomicBoolean getAndSet 消费，不丢标记。
- 等价性验证：`SqlLogCountAccumulatorEquivalenceTest` 随机操作序列（3 种子 × 600 操作 × 1~3 拍合并 drain）下增量累积与每拍全量重算逐键逐值相等；淘汰路径 drain 含被淘汰键；节流窗同键折叠净差正确；clear 后双双清空。buffer 侧守卫 `SqlLogEntryBufferChangedKeysTest` 8 例；面板接线面 2 例（`SqlLogPanelDedupeToggleTest` 追加）。
- 定向测试读数：5 类 **99 例 0 红**（`SqlLogEntryBufferTest` 60 / `SqlLogEntryBufferDedupeIndexTest` 18 / `SqlLogEntryBufferChangedKeysTest` 8 / `SqlLogCountAccumulatorEquivalenceTest` 5 / `SqlLogPanelDedupeToggleTest` 8；`--tests` 定向 + `-Pkotlin.compiler.execution.strategy=in-process`）。首轮 4 红均为测试侧缺陷（预 drain 致断言失真 ×2、presence 算术口径错、键上限 4096 封顶断言错），修正后全绿，生产代码零返工。
- not run：全量门禁（本券纪律不跑）；真机 GUI 走查（面板卡顿体感）待用户；前后对比压测读数待券 D 探针落位后统一复测（计划 §七.2）。

### 券 C：证伪结案（未实码）（t-sqllist-catchup-c，2026-11-07）

**结论：前提 §五.1 前半被代码证据证伪，券 C 字面机制未实施**（派活条款「若发现积压另有去向，如实报告——那会推翻券 C 的必要性」分支命中；主控采纳处置①）。

**证据链**（均带 文件:行，分支 t-sqllist-catchup-c 基线 b2ccd4f9）：

1. **解析不在轮询拍里**。`handleConsoleLine` 由平台 `applyFilter(line, entireLength)` 逐行驱动（`LogFilterProxyHandler.kt:23-27` → `SqlLogConsoleBridge.kt:356`），每行在**控制台输出线程**同步完成解析——桥内 KDoc 自证线程模型（`SqlLogConsoleBridge.kt:388-390`：「本方法在控制台输出线程执行，而轮询侧 flushSettledEntries 在 EDT 冲刷」）。
2. **`scanAll()` 全程不读控制台文本、无扫描位、无逐行循环**（`SqlLogConsoleBridge.kt:222-264`），只做三件事：幂等挂 filter（缓存短路）、jdbc/sharding 静默桶冲刷、主控制台出现/移除检测。git 全历史确认 `scanAll` 自 T-M31-03（`ce816772`）起从未有文本解析路径——计划 §一「每拍 scanAll() 读控制台文本」为误读。
3. 因此券 C 的「上次扫描位→文档末尾行距」「2000 行/拍预算」没有锚点：桥不存在「本拍解析 N 行、剩 M 行」这个量。强行落码须在 `scanAll` 新造文本解析路径，与 filter 逐行路径**双路重复产出**（条目翻倍），违反计划非目标「不改捕获语义」。
4. **scanAll 线程归属**：EDT（javax.swing.Timer 回调固定在事件分发线程）。但 scanAll 无行解析，2000 行预算的 EDT 顾虑随之失效；若按字面实施则解析必落 EDT，§四 20ms 时长保险丝确实必要——又一重不应实施的理由。
5. **积压真实去向**：①EDT 刷新队列——每条 SQL 通知一拍 `refreshList`（O(N) 全量计数），风暴期 EDT 吃不下即无界积压（券 A/B 域）；②平台 ConsoleViewImpl 文本→文档 flush 延迟（filter 上游，桥管不着）；③桥侧尾巴有界——风暴中 jdbc/sharding 条目逐行即闭合（`processConsoleLine` 喂行后即时 `flushSettled`，SqlLogConsoleBridge.kt:1165-1166），风暴结束后仅静默桶等 2s 轮询拍，尾巴 ≤2s，非滚动积压。

**可选处置与采纳**：

| 处置 | 内容 | 结论 |
|---|---|---|
| ①取消 C 实码 | 证伪记录回填 §九 + §一/§三.3.3 订正标注 + 桥定向回归零改动确认 | **采纳（主控 2026-11-07）** |
| ②C 缩水为 pending 量暴露 | 暴露 jdbc/sharding pending 条目数——该量有界且风暴中≈0，喂 D 状态栏会误导 | 不采纳 |
| ③按字面实施 | 与 filter 路径双路重复产出，违反非目标 | 不采纳 |

**连带影响**：券 D 规格中「桥通道未解析行数」是同一误读的衍生物，建议 D 改口径用 buffer 真实量（如 `pendingNotifyCount`）。

**定向回归读数**（零改动确认，2026-11-07，`-Pkotlin.compiler.execution.strategy=in-process`）：三类 24 例全绿零跳过——`SqlLogConsoleBridgeTest` 1 例 / `SqlLogConsoleBridgePerfTest` 10 例 / `SqlLogConsoleBridgeMarkerTest` 13 例，XML 判据数逐类核对（skipped=0, failures=0, errors=0），BUILD SUCCESSFUL。

**未跑**：join-demo 压测复测（归主控复测条款）。
### 券 D：追赶量化读数（2026-11-07）

- 分支：`t-sqllist-catchup-d`（工作树 `.worktrees/sqllist-catchup-d`），提交：`5bdefafc`（生产：追赶读数 + 探针与分布采样；含测试）、`d0c0c005`（返工两处：构造期序坑 / reset 后 null 语义）、`4766081c`（探针接线守卫 + 反向验证读数）。
- 写集：`CaptureStatusBar.kt`、`LogPerfProbe.kt`、`SqlLogPanel.kt`（最小接线）、双语 bundle（`log.statusbar.catchup`）、测试三处。
- **口径修正（主控 2026-11-07 转达，券 C 证伪链）**：`SqlLogConsoleBridge` 为 **filter 逐行制**（每行解析在控制台输出线程完成，`SqlLogConsoleBridge.kt:388-390` KDoc 自证）——「桥逐拍解析积压」这个量不存在，券 C 实码取消；追赶读数唯一数据源＝buffer 的 `pendingNotifyCount()`（券 B），**未留 bridgeBacklog supplier 死接缝**（仓规不留无接线计划的口子），`BRIDGE_SCAN_ALL` 打点亦取消。**给主控的合流接线清单：无（桥接缝与桥打点已随券 C 证伪取消）**。
- 机制：`CaptureStatusLine.catchUpBehind`（默认 0）>0 时控制台段尾挂「追赶中：落后 N 条」，=0 不挂；`CatchUpBadgeState` 一拍节流（>0 立即显示；归零首拍持有上一读数＝计划「=0 持续一拍后消失」口径；持有拍内又升起无缝续显，抖动不闪烁）；接线不新开轮询——驱动拍与状态栏既有刷新拍同源（2s 诊断定时器 + refreshList→syncDiagnostics 链，前提「核实既有通知链可挂」成立）；非捕捉态 reset。
- 探针：`refreshList` 拆门（`flushRebuildChainSync`）与拍体 `refreshListBeat`，全拍耗时打点 key=`LogPanel.refreshList`（补完让路的空拍不计入）；既有 `LogPanel.refreshList.snapshot` 打点不动。**LogPerfProbe 补全量分布采样**（阈值 INFO 只留 ≥100ms 拍，refreshList 单拍常态低于阈值拿不到中位数）：每名环形 1024 样本、`sampleStats(name)` 取 count/mean/p50/p90/max、`resetSamples` 划压测窗口、每累计 512 样本打一条 INFO 分布摘要（沿用 idea.log 留痕呈现）。
- 前提证伪结论（§五.1 后半）：「refreshList 单拍中位数 vs 桥 scanAll 单拍中位数」对比在探针落位前拿不到——已把 API 与点位备好（`sampleStats`/`resetSamples` + `LogPanel.refreshList` 点位），measure 开销实测可忽略：**211ns/次**（百万次空块均值，上界断言 1000ns，`LogPerfProbeSampleTest`）。压测复测取数路径：`LogPerfProbe.resetSamples()` 划窗口 → 复测 → `sampleStats("LogPanel.refreshList")`。
- 已知边界（数据源口径，登记不越写集修）：`pendingContentNotify` 只在通知触发（批 20 / 下一条落 200ms 窗外）或 `clear()` 时归零——风暴**末尾**被抑制的余量若之后无新条目，计数会悬挂 >0，「追赶中」徽章随之滞留显示，直到下一条条目触发通知或清空。修复方向在 buffer 侧（券 B 写集 / 主控合流评估），面板与状态栏侧不越界处理。
- 返工两处（`d0c0c005`）：①`catchUpBadgeState` 声明误落在 init 块后——init 内首次 refreshList 即读它 → SqlLogPanelLayoutTest 19 例 NPE（构造期序坑同族，声明已移 init 前统一区块）；②`sampleStats` 无样本应返回 null（reset 后原返回 count=0 零读数）。
- 定向测试读数：3 类 **43 例 0 红**（`CaptureStatusBarTest` 18 / `LogPerfProbeSampleTest` 4（新增） / `SqlLogPanelLayoutTest` 21；`--tests` 定向 + `-Pkotlin.compiler.execution.strategy=in-process`，JUnit XML 判据数核对）。
- 反向验证（S2）：临时摘除三处修复分支重跑——追赶读数拼接 1 红（`test追赶读数大于零时挂控制台段尾`）、pendingNotifyCount 接线 1 红（面板接线用例）、全拍打点 1 红（探针接线守卫），**恰 3 红**；其余用例（=0 不挂、节流状态机、既有 20 例）语义在旧机制下同样成立保持绿；恢复分支后 43 例全绿。
- not run：join-demo 压测复测前后对比读数（§七.2，探针已备好、由主控统一复测）；真机 GUI 走查（追赶徽章观感）待用户；全量门禁与 convention 守卫（§七.3）按券序由收口轮统一跑。


### 小券 B2：悬挂节流余量过期冲洗（2026-11-07）

- 分支：`t-sqllist-catchup-b2`（自集成 tip `0596a476` 新建），提交：`47f762ef`（生产：buffer 过期冲洗 + 面板诊断拍接线；含测试与附带修正）。
- 缺陷（券 D 登记的边界）：`pendingContentNotify` 只在通知触发或 `clear()` 归零——风暴末尾末批变更不足批 20 且落在 200ms 窗内，之后无新条目则再无触发源，悬挂的 UI 不刷新与「落后 N 条」徽章滞留正好命中风暴结束后的静默期。
- 修法：buffer 侧 `hasStalePendingNotify()`（pending>0 且距上次通知超窗的悬挂态判定）+ `flushPendingNotify()`（补上本应发生的那次通知：归零+刷新时间戳+触发 listeners；pending=0 no-op；与并发生产者竞争最坏是各发一次幂等通知，不丢变更）；时间注入缝 `nowNanosForTest`（节流时间戳与悬挂判定统一走 `nowNanos()`，照券 B「别赌墙钟」口径）。面板 `syncCaptureStatusBar`（2s 诊断拍，EDT）捕捉态先探询后冲洗——悬挂变更最迟 2s 上屏；flushPendingNotify 走 notifyUi 合拍链，EDT 调用与既有桥/agent 生产线程同路径安全。
- 测试：`SqlLogEntryBufferPendingNotifyFlushTest` 新增 4 例（悬挂态构造 hasStale=false / 时间推过窗 flush 归零且 listener 恰 1 次 / pending=0 no-op / flush 后窗内抑制不重·超窗即时补发不丢 / clear 复位后不误冲洗）+ `SqlLogPanelLayoutTest` 接线守卫 1 例（诊断拍冲洗后 pending 归零且悬挂条目上屏；摘除冲洗分支必红）。
- 定向读数：8 类 **143 例 0 红**（新增 5 例 + `BufferTest` 60 / `DedupeIndexTest` 18 回归 + 券 B 面板等价 2 类 13 例 + 券 D `CaptureStatusBarTest` 18 / `SqlLogPanelLayoutTest` 22 回归；`--tests` 定向 + in-process）。
- 附带修正（非缺陷，根因在券 A 交互）：`SqlLogPanelDedupeToggleTest` setUp 注入同步 `catchUpScheduler`（照 `SqlLogManagerFlushWindowTest`「不赌墙钟时序」口径）——券 A 150ms 时间窗把窗口内通知挂 `uiCatchUpAlarm` 补刷，headless `dispatchAllInvocationEvents` 泵不到未到点的 Alarm，券 B 面板接线用例原依赖「通知立即刷」语义在集成后失真（增量拍断言读到基线态、首拍报 {A:1} vs {}）。生产代码零返工。
- not run：全量门禁（纪律）；真机 GUI 走查（徽章观感）待用户。

### 集成收口（主控，2026-11-07，分支 t-sqllist-catchup）

- 合流顺序：券A（526ed2df）→ 券B（b066e9ab，计划文档 §九冲突手工双保留）→ 券C 证伪结案（fe516540，同款冲突双保留）→ 券D（0596a476，同款冲突双保留）→ 小券B2（无冲突）→ CHANGELOG 回填（7fe6fc09）。
- **定向收口读数（集成 tip，`--tests` 指定十类 + `-Pkotlin.compiler.execution.strategy=in-process`，XML 判据数核对）**：10 类 **101 例 0 failures 0 errors 0 skipped**——A 面（FlushWindow 7 / Behavior 35 / AutoShow 9）+ 桥三类（Bridge 1 / Perf 10 / Marker 13）+ 守卫（BundlePlaceholderArity 4 / GuardSelfTestCoverageMetaGuard 8 / BannedApiScanRule 12 / SqlLogManagerLookupGuard 2）。连同 B2 在集成基线跑过的 8 类 143 例，定向面合计 **244 例全绿**。
- 券 C 证伪的连带处置：D 的桥接缝/桥打点已取消（无死接缝）；A×B 测试语义交互（时间窗改变「通知立即刷」假设）由 B2 在集成侧发现并修（测试侧注入同步 catchUpScheduler，生产零返工）。
- **not run**：全量门禁 testShards（含平台 UI 测试，L13 口径——用户本轮指令「只做代码收口」）；verifyGate/buildPlugin（非大改动判据）；join-demo 压测前后对比读数（需新插件包装进 IDE，用户改约）；真机 GUI 走查（用户）。合入 master 待用户点名。

### 券 E：refreshList 拆拍瘦身 + 桥侧行处理探针（2026-11-07）

- 分支：`t-sqllist-catchup-e`（工作树 `.worktrees/sqllist-catchup-e`，基线 = 集成 tip `6859384d`），提交：`9d760fa0`（探针，一次到位）、`c1764eaa`（瘦身：渲染器条目级 preferred size 缓存）、`4703ed64`（淘汰扫出键域兜底升级为增量核销；含等价性用例与反向验证）。
- 写集：`SqlLogPanel.kt`、`LogPerfProbe.kt`、`SqlLogEntryBuffer.kt`、`SqlLogConsoleBridge.kt`、`RailCellRendererBase.kt`（账单显示重段在渲染器——超出原写集的第四文件，主控知情路径：账单定位后按「只动真正重的段」落刀；历史页二级列表与日志页共用该基类，一并受益）+ 测试五处。

**拆拍账单**（headless 本地模拟「visible=200、buffer 恒满、异键风暴、去重关闭=用户真机实态」，临时测试未入库，分波 30 条/波停 170ms 越过券A 时间窗逼出真拍距；拍数 12~15 拍/段）：

| 段 | key | 瘦身前 mean | 瘦身后 mean |
|---|---|---|---|
| 全拍 | `LogPanel.refreshList` | 16.0~33.3ms（三账单） | 7.7~9.4ms |
| 快照 | `.snapshot` | ~0ms | ~0ms |
| 诊断同步 | `.diagnostics` | 0.9~1.2ms | 0.6~0.8ms |
| 增量计数（含每拍全量回退） | `.counts` | 0.6~1.4ms | 0.4~0.6ms |
| 过滤链+排序 | `.filters` | 0.1ms | 0.1ms |
| 差分+模型+贴底滚动 | `.diffModel` | 14.4~30.7ms | 6.6~7.9ms |

**两个假设被账单证伪，真刀落点**：①主控主攻假设「每拍全量回退计数是最大头」——证伪：counts 段（去重关闭期每拍置全量标记形态）实测仅 0.6~1.4ms（N≤200 的便宜一遍扫），增量化的收益在此量级；②diffModel 段钻取：大头是**尾部贴底滚动** `scrollToBottom` → `ensureIndexIsVisible` → BasicListUI 变高布局 **O(N) 逐格全扫**（默认 KEYWORD_WRAP 可变行高模式；对照实验：模型变更后首次 `getCellBounds` 27~31ms、温态 0.05ms，每拍都有模型变更 → 每拍必付）。真机 102~232ms 同型且更贵（真机渲染更贵）。

**瘦身刀（c1764eaa）**：`RailCellRendererBase` 条目级 preferred size 缓存——IdentityHashMap 按条目身份键控（LogSqlEntry 不可变），代戳（SQL 区宽/显示模式/选中态/字体）任一变化整缓存作废，上限 1024；bind 链先解锚再重锚防陈旧命中。行高口径钉死：⨉N/×N 徽章与选中态只改文字不改行高（徽章 map 每拍换实例，不进代戳）。效果：同款账单全拍 p50 22~33ms → 7~9ms（单拍 <30ms 目标达成）；实验读数「模型变更后首 bounds」27~31ms → 8.7ms（1 新条目）/20ms（30 新条目）。

**淘汰增量化（4703ed64）**：主控必做指令按计划执行——扫出键域是确定性核销（presence 减一、归零摘键、被淘汰键随变更键集出域），面板净差对账即可，addEntry 淘汰循环与 removeEntry 单键删除两处不再置全量标记；其余五类兜底点（开关翻转/接管沿重建/clear/去重关闭或不可去重条目/键上限溢出）逐字不动。等价性证据：`SqlLogCountAccumulatorEquivalenceTest` 新增「异键风暴」场景（2 种子 × 300 恒异键 add、buffer 恒满 5、键持续进出、1~3 拍合并 drain）增量与全量逐键逐值相等，且**基线锚定后全程断言不置全量标记**（防全量回退口径下平凡通过——空绿防线）；反向验证（S2）：恢复 buffer 侧置位重跑恰 2 红（等价性「不走全量」+ ChangedKeys 扫出键域），恢复后全绿。

**桥侧探针**（9d760fa0）：`LogBridge.handleLine`（逐行整方法耗时，风暴期数万行/秒的每行成本盲区补上）；`LogBridge.settledBacklog`（flushSettledEntries 每拍入队前待产出条数 gauge，含 0 每拍记录）；`LogPerfProbe.recordGauge` 补非耗时读数共用券D 分布设施（值 ×1e6 入环使 stats 原值回显）。真机压测复测取数路径与券D 同款：`resetSamples()` 划窗口 → 复测 → `sampleStats(...)` 逐 key 取账单。

**定向测试读数**（`--tests` 定向 + `-Pkotlin.compiler.execution.strategy=in-process`，XML 判据数核对）：12 类 **163 例 0 failures 0 errors 0 skipped**（BufferTest 60 / DedupeIndexTest 18 / ChangedKeysTest 8 / EquivalenceTest 6（新增 1）/ PendingNotifyFlush 4 / PanelDedupeToggle 8 / PanelLayout 23 / Probe 6 / 桥 4 类 30）+ 渲染器共用基类连坐面 5 类 **115 例全绿**（HistoryCellRenderer 30 / PanelRendererUi 53 / RailPerf 17 / ListParamRenderer 10 / HistoryScrollToEnd 5）。探针接线反向验证（9d760fa0）：断开三处探针 key 重跑恰 2 红（面板 diffModel 段 / 桥 handleLine），恢复全绿。

**not run**：真机 GUI 走查（面板卡顿体感/贴底滚动观感）待用户；join-demo 压测复测前后对比（需新插件包装进 IDE，主控统一复测条款——探针与分布采样已就位，`sampleStats` 逐 key 可取）；全量门禁 testShards 与 convention 守卫（券纪律，收口轮统一）；暂缓：diffModel 段余量 ~7ms 的下一层（逐格 bind/片段裁剪成本）有账单可查但已达标未动。
