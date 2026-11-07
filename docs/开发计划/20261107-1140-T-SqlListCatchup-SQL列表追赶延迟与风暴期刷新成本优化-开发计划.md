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

### 券 F：真机验收缺陷两件（F1 跨通道重复兜底对账 / F2 队列风暴丢弃可见化与聚合）（2026-11-07）

- 分支：`t-sqllist-catchup-f`（工作树 `.worktrees/sqllist-catchup-f`，基线 = 集成 tip `6c52564a`），提交三笔：`f7c20f7b`（F1）、`bb5652ed`（F2a）、`2521991a`（F2b）。

#### F1：跨通道重复条目在去重开启态共存

**定因（两候选机制的裁决）**：机制①（六族还原器指纹分叉）**不是独立成因**——六族分叉只动 `dedupeKey`（还原后 SQL 指纹），不动 crossKey 的原材料（占位符版 rawSql + 参数值签名，两侧同源），窗内折叠本就接得住（`CrossChannelDedupeTest` 既有 18 例钉死）。真缺陷 = **机制②（超窗）**：`crossChannelIndex` 的 5s 折叠窗错过——控制台副本经平台 ConsoleViewImpl 冲刷延迟 >5s 到达，且风暴期 `purgeExpiredCrossChannelLocked`（`SqlLogEntryBuffer.kt:189`，每次入队用**墙钟**惰性清除 >5s 的键）把 agent 行的 crossKey 提前摘掉 → 窗内窗两路都接不住，同一次执行各占一行（真机形态：`#1 [15:36:09] —` 控制台行 + `4ms [MYSQL] ⨉123 [来源]` agent 行）。

**修法**（`f7c20f7b`，写集 `SqlLogEntryBuffer.kt` + `LogSqlModels.kt` + 新测试）：

1. `crossChannelIndex` **取消 5s 时间窗惰性清除**——超窗键是越窗兜底的候选源；键域仍随队列生命周期封顶（upsert / 融合重指 / 淘汰摘键 / removeEntry 反扫 / clear / 两类重建同步出域），无新增长面；
2. 入队折叠窗未命中时追加**越窗兜底对账** `findStaleSameSourceCandidateLocked`：同 crossKey 索引命中、超 `CROSS_CHANNEL_WINDOW_MS`、异通道标记（`crossChannelProvenanceFoldable`，窗内/越窗共用同一判据防漂移）、仍在去重窗（60s，与 `isDuplicate` 同款单边判定）内 → `mergeDuplicateEntry` 折回一行（先到者保留、补空不覆盖——agent 耗时/角标/位置全保留，红线「agent 先到不覆盖」），不计 ⨉N、不占捕获序号，与窗内跨通道折叠同口径；两到达顺序（agent 先到/控制台先到占 #1）都覆盖；
3. 通道判据收紧一处：**两侧都 UNKNOWN 不折**（观测合并的语义前提是可识别通道）——取消时间清除后由旧时间戳夹具暴露 `purge` 此前在静默挡住「UNKNOWN 对 UNKNOWN 落窗内折叠」的失真形态（`SqlLogEntryBufferTest` 六红根因，修后语义 = 历史读回/未打标条目按去重口径走，不被观测合并误折）；
4. **接管沿语义不回破**：`rebuildDedupeIndexAfterHandoff` 的 grace 截断照旧清索引——被截断的键不在索引，越窗兜底天然无候选，P3「attach 即生效」可见性不受影响（专守卫用例 `接管沿截断后_agent首批_不被越窗兜底折进桥纪元条目` 钉住）。已知边界：接管沿截断形态（控制台行 >5s 旧时 attach、随后 agent 新执行开新行）**不在本券修复范围**——它与 P3 可见性语义互斥（buffer 无法区分「同一次执行的迟到副本」与「attach 后的新执行」），保持 P3 优先。

**测试**：`CrossChannelStaleReconcileTest` 新增 8 例（超窗异指纹 agent 先到/控制台先到、超窗同指纹走既有同键去重、越去重窗不折、同通道不折、去重关闭不折、接管沿截断不折、折叠后副本续折）；真实链路夹具（控制台行真解析 + AgentEventAdapter 真适配），合成时间戳注入不赌墙钟（L18）。

**反向验证（S2）**：摘除越窗兜底分支重跑**恰 3 红**（两超窗复现 + 后续副本续折），5 个守卫/安全用例保持绿；恢复后全绿。过程记录一次判据演进：首版兜底未收紧 UNKNOWN 口径时 `SqlLogEntryBufferTest` dedupeExisting 面 6 红（purge 移除暴露的既有语义失真），加「至少一侧已知通道」判据后归零——教训：时间清除曾是语义载体，摘它必须先验它挡了什么。

**F1 定向读数**：核心 6 类 **118 例 0 红**（StaleReconcile 8 / CrossChannel 18 / Buffer 60 / DedupeIndex 18 / ChangedKeys 8 / Equivalence 6）+ 外围 8 类 112 例 0 红（O147 4 / PanelLayout 23 / CaptureStatusBar 18 / 桥 3 类 24 / ManagerBehavior 35）。

#### F2a：agent 事件队列丢弃计数可见化（`bb5652ed`）

- 链路（全复用既有搭载点，零新协议命令）：agent `SenderLoop.heartbeatBody()` 追加整数键 `dropped`（`EventBus.droppedCount()`，恒出现、键序固定、无空格；旧 IDE 宽容扫描零行为变化）→ IDE `AgentAttachStats.dropped`（缺键按 0 降级）→ `AgentAttachReport.latest()` → `CaptureStatusLine.agentDropped`（`SqlLogPanel.syncCaptureStatusBar` 2s 诊断拍接线）→ 增强段尾挂「｜已丢弃 N 条」（>0 才挂）→ bundle 双语 `log.statusbar.agent.dropped`（占位符 {0}{1} 两侧一致，BundlePlaceholderArity 口径）。
- 测试：`CaptureStatusBarTest` +3（挂尾保既有叙事 / =0 不挂 / 增强未开启不挂）、`AgentAttachStatsTest` +2、agent `HeartbeatDiagnosticsTest` +1（键恒出现且与 `EventBus.droppedCount()` 同源——静态累计量测试间共享，值断言用同源比对禁断言字面 0）。
- 反向验证（S2）：摘 `agentDropped` 拼接重跑恰 1 红，恢复全绿。读数：IDE 3 类 59 例 0 红 + agent HeartbeatDiagnostics 6 例 0 红。

#### F2b：队列满时同模板聚合，降精度不丢信号（`2521991a`）

- 机制（agent 侧 `EventBus`，`offer()` 全程非阻塞红线不碰）：满队列时①**同模板聚合**——同模板（同 sql，PreparedStatement 占位符文本即模板）事件移除队内旧条、聚合计数并入最新事件重投（`SqlEvent.aggregatedCount`），腾出容量给新模板；②**异模板逐旧**——无同模板候选时逐出队头最旧事件计丢弃，保最新弃最旧；竞争失败回落原丢弃语义。「已收事件」口径不变（仍按帧计 1）。
- **协议兼容（双向）**：JSON 键 `c` 仅在 >1 时写出（`Jsons.writeEvent`）——常态事件字节面零变化；旧 IDE（Gson 宽容解析）忽略未知键；新 IDE（`AgentEvent.parse`）读旧事件（无 `c` 键）按 1 计、畸形值回落 1。`AgentProtocolSyncTest` 约束（CMD 常量两侧同值 / 帧形态唯一性 / 心跳节奏 ≥3×间隔不变量）全部未触。
- 测试：agent `EventBusAggregateTest` 新增 5 例（满+同模板聚合零丢弃且执行总次数不丢 / 满+异模板逐最旧且新模板入队 / 未满不聚合常态计数恒 1 / offer 恒非阻塞+null 计丢弃 / 聚合后 clearQueued 语义不变）、`JsonsEventFormatTest` +1、IDE `AgentEventAdapterTest` +3。
- 反向验证（S2）：摘聚合+逐旧分支重跑恰 2 红，守卫用例保持绿。
- 已知边界：面板级消费（聚合余量计入 ⨉N 徽章）未做——需 buffer 计数 API 扩展，超本券写集；聚合计数已在协议层不丢（`AgentEvent.aggregatedCount` 可读），留待主控评估是否立券。

**F2 综合读数**：agent 子项目全量 `:sql-visualizer-agent:test` **48 类 270 例 0 failures 0 errors 1 skipped**（skip 为既有预置条件用例，与本券无关）；IDE 侧 F 面 **18 类 478 例 0 红**（含 F1 全部核心/外围 + F2a/F2b 接线面 + agent 包 `AgentEventAdapter` 16 / `AgentAttachStats` 15 / `AgentManagerIntakeGate` 21 / 状态文本等）。

**工程记录**：构建期并行登记 `sqllist-f`（沙盒 `sqllist-f-sandbox` 临时隔离已用后还原，未入库）；F2b 落地后 IDE 侧首跑 24 红＝`AgentEvent` 构造签名变更后 Kotlin 增量编译残留旧测试字节码（NoSuchMethodError），删除 `build/classes/*/test` 强制重编后归零——非真实回归，**改 data class 构造签名后定向读数必须核对是否重编**。

**not run**：全量门禁 testShards 与 convention 守卫（券纪律，收口轮统一；BundlePlaceholderArity/GiantFileBudget 等由收口连坐）；join-demo 压测复测（主控统一条款——F2a 上线后状态栏可直接观察 dropped 读数，F2b 聚合效果看「已收事件」与 dropped 差值）；真机 GUI 走查（F1 两行并存的消失、F2a「已丢弃 N 条」观感）待用户；面板级聚合计数消费（见 F2b 已知边界）。

### 券 G：真机走查三件（G1 池代理误报修复 / G2 重放守卫大额丢弃核验 / G3 桥让位成本量化）（2026-11-07）

- 分支：`t-sqllist-catchup-g`（工作树 `.worktrees/sqllist-catchup-g`，基线 = 集成 tip `df92576b`），提交三笔：`886a0d33`（G1）、`cc2d3606`（G2）、G3 薄交付零实码（读数见下，未入库临时账单测试已删）。

#### G1：Hikari 连接池代理被标「未登记 JDBC 实现」（误报，已修）

- 定性：池代理是包装层不是驱动（Hikari 几乎人人用，真机 2026-11-07 第三/四轮压测该诊断行常驻三类：`HikariProxyPreparedStatement`/`ProxyStatement`/`ProxyConnection`）。「未登记」曝光面单点在 agent 侧——`CatchAllMatcher.evaluate` 接口命中且不在驱动白名单时调 `CaptureBudget.noteUnregisteredDriver`（唯一调用点，全仓 grep 实证），IDE 侧（`AgentAttachStats`→`CaptureDiagnostics`→`CaptureStatusBar`）只渲染快照，**零改动**。
- 修法（`886a0d33`）：`CatchAllMatcher` 新增 `POOL_WRAPPER_PREFIXES` 前缀表（六前缀：`com.zaxxer.hikari.pool.`、`com.mchange.v2.c3p0.`、`org.apache.commons.dbcp.`、`org.apache.commons.dbcp2.`、`org.apache.tomcat.jdbc.pool.`、`com.alibaba.druid.pool.`）+ `isKnownPoolWrapper`（前缀级判定，族内新包整族覆盖——仓内既有「前缀级守卫挡得住整族静默」教训的正面用法）；evaluate 对命中前缀的类跳过 `noteUnregisteredDriver`。
- **织入面零变化（捕获路径核验结论）**：池包装类照旧兜底匹配 + retransform；「代理外层 + 驱动内层」双织入的解包装已有先例兜住——`StatementRegistry.register` 对包装语句把内层实体语句一并登记（T-OracleWrapperRegistry，`StatementRegistry.java:155-180` `delegateStatementOf`）+ `NestGate` 嵌套只报最内层（`SqlHookCallbacks.java:153` 注释自证「Hikari/MyBatis 包装层形态」在内）——**无需补解包装**。Tomcat-JDBC 的语句代理多为 JDK 动态代理，已被 `cheapExcluded` 的 `Proxy.isProxyClass` 排除，前缀表兜住其余静态包装。
- 测试 2 例（`CatchAllMatcherTest`，真 Hikari jar 隔离 loader 夹具沿用）：attach 级（三类不报、真驱动仍报、接受表仍登记、三类各织一次）+ 前缀表级（五族具名代表命中、真驱动/同名字类/null 不误吞）。
- **反向验证（S2）**：摘除 `com.zaxxer.hikari.pool.` 前缀重跑**恰 2 红**（attach 级 + 前缀级），恢复后全绿。
- 定向读数：`CatchAllMatcherTest` 14 + `CaptureBudgetTest` 10 + `HeartbeatDiagnosticsTest` 6 = **3 类 30 例 0 failures 0 errors 0 skipped**（`--tests` 定向 + in-process，XML 判据数核对）。

#### G2：重放守卫大额丢弃核验（量化定责 → 发现真实丢数据缺陷并最小修复）

- **分流结论**：①真重放（rehighlight 全文档重跑）被守卫按设计丢弃＝无害（历史行已入库过）；②但发现**真实丢数据缺陷**：`console.clear()` 后流式新行会被判 Replay 持续丢弃。
- 缺陷机制（平台实证，243 sources `ConsoleViewImpl.kt`）：`clear()` → `doClear()` 是**同一文档实例** `deleteString(0, textLength)`（:698，经 CLEAR FlushRunnable :1438）——偏移从头重计、stamp 变、桥的反射句柄持续有效。重印首行必然「等于行 0」+ 行偏移 ≤ 陈旧水位 → 旧判据「stamp 变 + 行=行0」判 Replay，且 `lastDocStamp` 已同步使其后所有新行走「stamp 未变 → Replay」分支——**丢到新文档长度追平旧水位为止**。风暴期大文档（真机 55 万行量级）clear 一次＝控制台通道静默分钟级，与真机「丢弃计数只涨不产出」形态吻合。`replayGuard.reset()` 全仓无调用点（grep 实证），守卫此前只靠 Reset 判定自愈，而行 0 比对恰是该形态的盲区。
- EP 归属：EP 接管态下桥在守卫之前短路（`epActiveShortCircuitCount`），重放计数增长说明丢弃发生在 EP 未接管窗口；138,243 的量级与「单次 rehighlight 的全文档行数」或「clear 后重印累积」两种机制都自洽，缺陷本身独立于 EP。
- 最小修复（`cc2d3606`，不动四态设计、只修判据）：`decide` 增加惰性 `docLengthProvider`（仅 stamp 变分支求值——流式/纯重放热路径零调用）；**文档总长度 < 水位 = 文档被缩小、水位陈旧** → 先于行 0 比对核销水位按 Reset 处理。长度不可得（反射桩缺方法）回退旧行为。桥侧复用 `attachReplayGuard` 已解析的 `getCharsSequence` 句柄取 `.length`（零新反射），`BridgeLineContext.docLengthReader` 接线。
- 测试：纯逻辑 4 例（clear 后首行/后续行、文档未缩水混合态维持 Replay、长度不可得回退）+ `ReplayGuardLine0ReadTest` 真实 readers 驱动 clear→重印 1 例（混合态夹具改长文档保原语义钉死——旧夹具 17 字符 < 合成水位 100 会落进新缩水分支）+ **桥级复现实验** `ReplayGuardStormVerdictTest` 2 例（真实 filter 代理链路：真重放丢弃不重复产出 / clear 后重印恢复产出）。
- **反向验证（S2）**：摘除缩水分支重跑**恰 4 红**（纯逻辑 2 + 真实 readers 1 + 桥级 1），非缩水形态与真重放用例保持绿；恢复后全绿。
- 定向读数：`ConsoleReplayGuardTest` 11 + `ReplayGuardLine0ReadTest` 7 + `ReplayGuardStormVerdictTest` 2 = **3 类 20 例 0 failures 0 errors 0 skipped**；桥族回归 5 类 **37 例 0 红**（ConsoleSettleFlush 6 / EpBridgeSinglePath 7 / Marker 13 / Perf 10 / Bridge 1）。

#### G3：控制台停摆量化 + 桥让位升级（探针先行 → 账单不显著，薄交付不动刀）

- **前提订正**：派活背景「filter 逐行调用占 EDT 预算」与线程模型不符——`handleConsoleLine` 在**控制台输出线程**执行（桥 KDoc `SqlLogConsoleBridge.kt` + 券 C 证据链），EDT 只承担 `flushSettledEntries` 冲刷与面板刷新（券 A/B/E 域）。「占 EDT 预算 >10% 才动刀」的判据在本链路上不可成立（成本根本不落 EDT）。
- **账单**（临时基准测试，未入库，filter 代理真实链路 + JIT 预热，200k 行/态，`LogBridge.handleLine` 探针读数 + 墙钟直测）：全解析噪音行 ~9.0µs/行；EP 短路（agent 健康期常态）~6.1µs/行（含测试 JVM debug 日志分支放大的成分——该路径有 `LOG.isDebugEnabled` 包裹的逐行 debug 调用；守卫丢弃态同前缀路径实测 ~0.35µs/行，agent 健康期真实成本量级以此为下界）；守卫丢弃（重放风暴）~0.35µs/行。
- **预算占比**：真机风暴 ~60 SQL/s × 8-10 行/SQL ≈ 500-600 行/s → EP 短路态成本 ≈ 0.2~3.6ms/s ≈ **单核 0.02%~0.36%**（下界/上界）；即便 10 倍风暴（6k 行/s）≤ 3.6%。判据「>10%」远不达标——**成本不显著，薄交付：登记读数后不动**。
- **注销/挂回方案的平台边界登记（若未来要动刀，先过这三关）**：①243 起 `ConsoleViewImpl.clearMessageFilters()`（:777）移除**全部** message filter（EP 经 `consoleFilterProvider` 挂的 filter 同池）——无单 filter 移除 API（券 C 已取证 2026.2 无 `removeMessageFilter`），注销桥 filter 必连坐 EP 通道，挂回要走完整 attach 周期；②注销期 `consoleLinesSeen`（面板「已扫描 N 行 / 产出 0 条」的诊断输入）与 `epActiveShortCircuitCount` 冻结，诊断叙事失真；③注销期重放守卫水位冻结——正是 G2 修掉的陈旧水位缺陷族的制造机。`CaptureActivityFallback` 滞回状态机本身可用（`onGateChanged`/`onAgentEvent`/`onConsoleLine`，测试缝齐全），但收益面（≤0.4% 单核）不抵上述三险。
- 「文档长度 vs 桥已见行数」落差读数：需真控制台（headless 桩无平台 flush 管线），本轮未取；若未来动刀，取数路径 = `attachReplayGuard` 已持 doc 句柄 + `scanAll` 每拍 recordGauge（复用券 E 设施）。
- 测试：探针接线用例已存在并反向验证过（券 E：`ConsoleSettleFlushTest` 探针样例落环断言 + 摘 key 恰红），本轮零新增生产代码 → 无新增测试面；桥族回归 37 例全绿（见 G2 读数）。
- not run：真机侧账单复核（本机 headless 读数为代理读数，真机控制台输出线程竞争形态未测——量级结论 0.4% 单核对真机放大不敏感）；join-demo 压测复测（主控条款）。

### 券 H：SenderLoop 排水读超时税（2026-11-07）

- 分支：`t-sqllist-catchup-h`（工作树 `.worktrees/sqllist-catchup-h`，基线 = 集成 tip `207a03f5`），提交：`0d50931d`（修复 + 复现测试 + 反向，一笔含反向）。
- 写集：`sub-projects/sql-visualizer-agent/.../transport/SenderLoop.java`（主循环步骤 3 + 常量 + KDoc）+ 新增测试 `SenderLoopDrainRateTest.java`（4 例）。

**缺陷证据链**（主控真机六轮压测定位 + 本轮代码行号复核）：

- `SenderLoop.java` 主循环步骤 1 尽出事件 `while (burst < 64 && pollEvent(slot, 0))` 每轮最多 64 条（原 ：285 行）；步骤 3 读入站帧是**带 SO_TIMEOUT 的阻塞读**（`socket.setSoTimeout(configReadTimeoutMs(...))`，默认心跳间隔下钳到 1s，原 ：254/:306 行，:299 上方注释自证「读超时（默认 1s）」）。
- agent→IDE 方向平时**无入站流量**（CONFIG 仅会话初期一次，之后静默是常态）→ 每排满 64 条干等一个读超时 → 排水天花板 ≈64 条/秒。真机读数吻合：风暴生产 ~1700 语句/s、到达仅 ~74 条/s；队列（2048，F2b 聚合后）持续满 → 溢出丢弃（第三轮 1,402 条）→ 静默后尾巴按 64/s 慢排（用户反复报「已收事件还在涨」）。
- 原 ：289-293 注释「反过来把读帧提到尽出之前，会让每次事件投递都多等一个读超时」——作者只优化了**首批 64 条**不等待，持续突发下**每一批都交税**未覆盖（本券闭合）。

**修法**（外科手术，不重构）：burst 达尽出上限（新常量 `EVENT_BURST_MAX=64`）＝「队列很可能仍非空」——该形态的入站读改按 `in.available()>0` 探测：无入站数据**跳过本轮读帧**（零等待继续排水），有字节到店本轮立即读（CONFIG 不被排水饿死）；队列排空（burst<上限）才回长超时阻塞读。选择探测而非改短 SO_TIMEOUT：零系统调用成本、不动 socket 状态、`readInboundFrame` 的「完全无数据 vs 半截帧」鉴别逻辑原样保留。心跳判据（按真实流逝时间、睡眠不跨心跳时刻）、T-07 看门狗判据、停采短路、F2b 聚合语义、协议字节面**全部未触**。

**排水速率前后对比**（复现测试读数，桩税 100ms＝真机 SO_TIMEOUT 1s 的 1/10 缩放，判据不赌墙钟——读超时税做成可数事件、采样点钉在第 512 条 EVENT 帧写出的同步点）：

| 形态 | 第 512 条帧写出时读超时税 | 排水 512 条耗时 | 真机换算 |
|---|---|---|---|
| 修复前（红跑即摘分支实跑） | 7（每排 64 条付一税） | 726ms | ≈61 条/秒（与真机 ~64-74 条/s 吻合） |
| 修复后 | 0 | 31ms | ≈23 倍提速（上限转为帧写速率/队列供给，量级覆盖风暴生产 1700 条/s） |

**测试**（`SenderLoopDrainRateTest`，桩 Socket 走既有 `SocketDialer` 注入缝 + 桩流替换，4 例）：

1. 主判据（先红后绿）：队列持续非空 + 入站静默 → 排水期间读超时税=0；且 512 条全数按序投出、拨号计数=1（probe 不得把会话搞断）；
2. 静默期回归（约束 2 前半）：队列空时 CONFIG 仍走长超时阻塞路径即时处理（ACK 跨侧字面契约、状态生效）；
3. 风暴期 CONFIG 到达即被处理（probe 路径 available()>0 立即转入阻塞读）且 500ms 短看门狗不误触发（configSeen 已置位）；
4. 持续风暴从未收到 CONFIG → 看门狗仍按窗口触发停采（probe 跳读不绕过看门狗判据，停采前已有事件投出）。

**反向验证（S2）**：红跑＝摘除修复分支的实跑（`expected:<0> but was:<7>`，判据恰对准缺陷站点）；其余 3 例为守卫/回归语义（修复前后同绿，钳住的是「probe 不得改变空闲/看门狗/CONFIG 语义」）。

**测试夹具自身缺陷一并记录**：首轮 stop() 只 interrupt——退避睡眠缝是记录器（不真睡），中断→异常→退避→记录器立即返回→**立刻重连**成僵尸连接；先行用例的僵尸在后续用例的看门狗窗口内（拨号时读当前属性）触发 `setDisabledByWatchdog` 全局停采，风暴用例假红在 384/512。修法：停止＝stopped 标志 + 拨号缝拒绝 + 退避缝抛 InterruptedException（run() 捕获即 return），线程确定性退出（`join` 后断言 `!isAlive`）。

**三条约束守卫证明**：
- 心跳节拍：判据 `System.currentTimeMillis() - lastOutboundMs` 未触；probe 只改「本轮是否读帧」，心跳检查每轮照跑；`HeartbeatRhythmTest`（真 socket、累加计时形态必红）回归绿；
- CONFIG 看门狗（T-07）语义：`configSeen`/`watchdogFired` 判据未触；守卫用例 3/4 钉「风暴期到达即处理 + 从未收到仍触发」双面；
- `AgentProtocolSyncTest` 三约束：CMD 常量两侧同值 / 入站帧形态唯一性（`len<<8` 与 `cmd=readUnsignedByte` 各恰一处，probe 未新增同形语句）/ 心跳窗口 ≥3×间隔——IDE 侧实跑 4 例全绿（BUILD SUCCESSFUL，XML 判据数核对）。

**定向读数**：`SenderLoopDrainRateTest` 4 例 0 failures（红→绿两轮读数见上表）；`:sql-visualizer-agent:test` 全量 **49 类 276 例 0 failures 0 errors 1 skipped**（skip 为既有预置条件用例，与本券无关；基线 270 例 + 本券 4 例 + 桩类计数口径差 2）。

**not run**：真机压测复测（排水速率的真机端到端读数——需新 agent jar 注入宿主，归主控统一复测条款；本券速率对比以桩缩放换算交付）；IDE 侧全量门禁与 convention 守卫（券纪律，收口轮统一）；CHANGELOG 回填（主控合流补账）。


### 战役终态（主控，2026-11-07 晚）

- **合入 master**：`80512ec7`（基线 v2026.11.8 正式版 `659a44cc` 之上；CHANGELOG 冲突按「[Unreleased] 在上 + [2026.11.8] 在下」双段保留）。九券 A/B/C/D/B2/E/F1/F2/G1/G2/G3/H 全部落账，CHANGELOG [Unreleased] 共九条。
- **真机验收收官（第七轮，去重关闭 + 券 H 构建）**：已收事件 158,015 条（上轮 11,136，14 倍——券 H 排水天花板移除后到达量跟随生产与采集预算）、已丢弃 110 条（上轮 1,402，降 13 倍）、静默后**秒停**（用户确认）；慢拍 0、徽章 0 触发、插件 SEVERE 0；101,586 请求风暴两相位 0 失败。「没跟上」从现象到根因闭环。
- **全量门禁**：按用户令交 CI（本地 testShards 启动后停止），门禁读数以 CI 轮为准。
- **清理**：五棵工作树（集成+e/f/g/h）与五条分支（t-sqllist-catchup 及子券）全部删除，删前逐条 `git merge-base --is-ancestor` 复验已含 master。
- **经验沉淀**：`docs/经验总结/20261107-2230-T-SqlListCatchup-SQL列表追赶九券战役经验总结.md` + LESSONS L21（data class 签名跨工作树伪红，Seen 2×）/ L22（后台 tail 吞失败正文）/ L23（prepareSandbox mmap 锁＝沙盒占用）。

### 券 I：溢出聚合账本——被逐事件计数保全（2026-11-07，追加券）

- 分支：`t-sqllist-catchup-i`（工作树 `.worktrees/sqllist-catchup-i`，基线 = master `7cf2b1d3`），提交：`27ec0173`（修复 + 复现 + 反向，一笔）。
- 写集：`sub-projects/sql-visualizer-agent/.../transport/EventBus.java`（账本 + 入账/出账/清空 + KDoc）、`SenderLoop.java`（主循环步骤 1.5 出账接线 + KDoc）、`EventBusAggregateTest`（②用例按新语义更新）、新增 `EventBusOverflowLedgerTest`（5 例）。

**缺口**：券 F2b 的异模板逐旧路径对被逐事件直接 `DROPPED++`——「执行了 N 次」随计数整体丢失。券 H 把排水提速 23 倍后队列很少满，但尖峰仍会溢出（第七轮真机 110 条丢弃），而每一条被逐事件本来都可以只降精度不丢信号。

**机制**（协议零改动，复用 `SqlEvent.aggregatedCount` 与 JSON 键 `c`）：

1. **账本结构**：`ConcurrentHashMap<String, AtomicLong>`（键 = 模板 sql，值 = 该模板被逐且未发出的累计次数），键数封顶 `OVERFLOW_MAP_CAPACITY = 512`。内存上界 ≈512 × 模板串长度（典型 SQL 模板 <2KB）< 1MB。
2. **入账点**：`evictOldestAndOffer` 逐出最旧者后走 `recordEvicted`——把被逐者的 `aggregatedCount`（≥1，含队内聚合代表自身）按模板累加；同模板键**永远合并**（无论账本是否满），新键仅在账本未满时创建，满则计真丢失。**宿主线程红线**：全程只有 `ConcurrentHashMap.compute`（分段锁、纳秒级、无 sleep/无显式锁等待），任何异常回落 `DROPPED` 计数。
3. **出账点**：`SenderLoop` 主循环步骤 1.5——每轮排空队列后 `EventBus.drainOverflow` 把账本条目转成聚合事件**直接写帧**（不走队列，不再受 2048 容量约束）。时间戳取**出账时刻**（不记首入时刻：省每键第二份状态，风暴期溢出事件的时间精度降级到「出账批次」可接受，换宿主入账路径最简）。竞争安全：先按键 `compute` 置 null 原子摘除（与入账同键串行，无丢失更新）再写帧；写帧 IOException = 连接死 → 条目回账（`restoreCount` 无条件并入）后上抛走重连，重连后下轮重试——「发出成功才离开账本」以「失败必回账」等价达成。编码缺陷该帧按帧计真丢失并继续（不让毒条目卡死出账循环）。账本空时零分配直接返回（空闲热路径每轮都调）。
4. **语义口径**：`DROPPED` 收窄为「连计数都未能保全的真丢失」＝①账本满收不下新模板 ②入/出账异常兜底；心跳键 `dropped` 与 IDE 状态栏「已丢弃 N 条」链路不变，KDoc 写明读数含义（>0 = 已超出「聚合 + 账本」两级保全容量，属强告警；=0 不再意味着「队列从未满过」）。
5. **停采清空**：`clearQueued()` 同步清账本，账本代表的执行次数并入 `CLEARED` 计数（停采后不补投语义不回破）。
6. **队列容量 2048 不动**：加大只会放大 `tryAggregateOnFull` 在宿主采集线程上的 O(n) 线性扫描成本（成本注释写进 KDoc）。

**设计取舍（账本淘汰策略二选一定案：不再收新键）**：不选「随机逐一键给新键让位」的近似 LRU——让位要在宿主线程做驱逐决策，且可能把热模板换成冷模板；省下的几个冷模板计数抵不上复杂度与误逐风险，且 512 键的内存上界 <1MB，宁可少量真丢失也不加宿主成本。

**测试**（`EventBusOverflowLedgerTest` 5 例 + `EventBusAggregateTest` ②更新）：

1. 主判据（先红后绿）：队列灌满 + 520 条异模板溢出 → 账本保全 512 个模板的计数、`DROPPED` 只剩账本满的 8 条真丢失；出账 512 帧后账本清空、容量即刻回收（再溢出 4 条重新入账）；
2. 同模板优先级 + 聚合计数继承：整队同一模板（每条 `aggregatedCount=3`）+ 10 条异模板逐出 → 账本**只 1 个键**、次数 30（按 N 次入账不按 1 次），出账 1 帧且协议键 `c:30`；
3. 账本满反向：超限新模板计真丢失（语义收窄的正确性），已存在键照常合并；
4. 红线静态断言：读生产源码，`offer`→`droppedCount` 区间（含逐旧/入账/出账全部路径）无 `Thread.sleep`/`.wait(`/`.join(`/`synchronized`/`park(`/`ReentrantLock`/`Semaphore`/`.acquire(`/`.await(`；
5. 停采清空：`clearQueued` 后账本清零、代表次数并入 `CLEARED`、无残余可出账；
6. `SenderLoop` 集成：队头放 `aggregatedCount=7` 的聚合代表 + 2047 条异模板（队列满）+ 600 条异模板溢出 → 发送循环排空队列后账本 512 条全部发出：代表帧带 `c:7`、被逐异模板各恰一帧、账本满后未保全的 88 条确实不出现、事件帧总数 = 2048 + 512。

**反向验证（S2，两次）**：
- 摘「入账」分支（`recordEvicted(oldest)` 改回 `DROPPED.incrementAndGet()`）重跑**恰 5 红**：主判据 `expected:<74> but was:<586>`（修复前 520 条全丢）、同模板 `596 vs 606`、集成 `694 vs 1206`、`EventBusAggregateTest` ②、clearQueued 用例；守卫用例（未满不聚合、offer 非阻塞、聚合后 clearQueued 语义、红线静态）保持绿。恢复后全绿。
- 摘「出账接线」（`SenderLoop` 步骤 1.5 改 `overflowFlushed = 0`）重跑**恰 1 红**（集成用例），证明出账接线被钉住。

**定向读数**：`EventBusOverflowLedgerTest` 5 + `EventBusAggregateTest` 5 + `SenderLoopDrainRateTest` 4 + `HeartbeatDiagnosticsTest` 6 = **4 类 20 例 0 failures 0 errors 0 skipped**；`:sql-visualizer-agent:test` 全量 **50 类 281 例 0 failures 0 errors 1 skipped**（基线 49 类 276 例 + 本券 5 例；skip 为既有预置条件用例，与本券无关）。

**not run**：真机压测复测（新 agent jar 注入宿主后的 dropped 读数与「已收事件」差值——归主控统一复测条款；本券保全能力以合成溢出场景交付）；IDE 侧门禁与 convention 守卫（券纪律，收口轮统一）；CHANGELOG 回填（主控合流补账）；面板级聚合计数消费（仍为 F2b 已知边界）。
