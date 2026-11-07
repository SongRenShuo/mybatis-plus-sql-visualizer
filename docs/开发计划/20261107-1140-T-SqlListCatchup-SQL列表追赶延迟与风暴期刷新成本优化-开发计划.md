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
