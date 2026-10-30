# T-LogListAnchor：日志列表「暂停态视口被顶上去」与「关键字换行退化成单行」修复开发计划

- 日期：2026-10-29 14:00；分析基线：`master@2ac39a95`（工作树有在途改动，仅读不改）。
- 状态：**代码侧已实施（2026-10-29）**——券01（取源+预算）、券02（视口锚定）、券03（程序性滚动判据抽纯函数）、券04（文档同步）全部落码；定向门禁 log 包 **1782/0/0/0**，三次反向验证读数见 §八。**实机（runIde 沙盒）确认未做**：用户 2026-10-29 要求「先不要 UI 验收，我要用电脑」，沙盒未启动，见 §8.5 第 2 条（前置条件已备好）。本计划不预占 O/R/D/V 编号。
- 触发：用户 2026-10-29 在日常 IDEA 2026.2 实测两点——
  ① 手动滑动 SQL 日志列表后，新 SQL 到达仍把当前正在看的内容顶上去；用户期望「手动滚动即停，只有点『滚动到底部』或手动滚回底部才恢复跟随」。
  ② 显示模式为「关键字换行」时应为三行，实际很多条目只剩单行。
- 关联：`20261026-2215-T-ListMetaRevert-头部信息恢复1023顺序与自动滚动修复-开发计划.md`（尾部跟随机制与「滚到底恢复」规则的来源；其 §风险 已登记「内容追加时的调整事件可能被误判为回到底部」这一未决项，本计划 §二 R4 就是它的现场形态）。

## 一、报障与证据

### 1.1 用户截图可读出的形态

- 同屏 11 条中，#1、#4、#5 是标准三行（`SELECT` / `FROM` / `INNER JOIN`），其余 8 条是**单行 + 行尾 `…;`**，且行尾形态是「摘要被截断」而不是「三行里的最后一行」。
- 三行的那三条共同点：`SELECT` 列清单很短（`SELECT DISTINCT t1.station_id`、`SELECT COUNT(*)`），`FROM` 落在摘要长度之内。
- 单行的那几条共同点：`SELECT` 后跟长列清单（`code, station_name, device_id, …`），摘要长度内一个子句关键字都没有。

### 1.2 运行期日志读数（`%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2\log\idea.log`）

```
13:46:06  LogPerfProbe - [T-SqlLogPerf] LogPanel.refreshList.snapshot 495ms (size=200)
13:47:05  LogPerfProbe - [T-SqlLogPerf] LogPanel.refreshList.snapshot 1780ms (size=200)
13:48:39  SqlPreviewPanel - [M36] showSql branch=createEditor: len=3500
13:49:56  LogPerfProbe - [T-SqlLogPerf] LogPanel.refreshList.snapshot 3570ms (size=165)
```

- `size=200` 且默认上限 `DEFAULT_LOG_MAX_COUNT = 200`（`PluginSettings.kt:790`）⇒ 缓冲长期处于满载，每次刷新都走 `VisibleDiff.HeadEvictAppend`（`SqlLogPanel.kt:1708`，`listModel.remove(0)` + 尾部追加）。
- 同批 SQL 的完整还原文本 `len=3500`（用户单击预览触发的那次 `createEditor`），而列表摘要上限只有 160 字（`LogSqlModels.kt:258`）。

### 1.3 从历史日志取到的真实 SQL（`idea.1.log:18624` 内的应用日志行）

```
==>  Preparing: SELECT code, station_name AS stationName, device_id AS deviceId,
    device_code AS deviceCode, user_id AS userId, user_phone AS userPhone,
    device_port_number AS devicePortNumber, create_time AS createTime, start_time AS startTime,
    dic_charge_type AS dicChargeType, dic_status AS dicStatus, actually_amount AS actuallyAmount,
    discount_amount AS discount …
```

逐字累计：到第 160 字时仍在 `device_port_number AS devicePortNumber,` 一带，`FROM` 出现在 24 列之后——**根本不在摘要里**。

### 1.4 隔离探针（不进仓构建，`javac` 直跑）

探针 A（`tmp/probe-head-evict/ProbeHeadEvict.java`，JDK8 + 纯 Swing，变行高 JList + JScrollPane）：

```
before: viewPos=(0,1200) topIndex=30 name=row-30 barValue=1200 barMax=8000  extent=397
after : viewPos=(0,1200) topIndex=30 name=row-31 barValue=1200 barMax=7968  extent=397
```

删除模型第 0 条后，`viewPosition` 一点没动，但视口顶部那条从 `row-30` 换成了 `row-31`——**内容整体上移了一行高**。JList 没有滚动锚定能力，这是 Swing 的固有语义，不是插件某处写法的问题。

探针 B（`tmp/probe-head-evict/ProbeEvictBar.java`，同一装置 + `AdjustmentListener`）：

```
paused at value=8963 maxScrollable=9203 (距底 5 行)
  [adj] value=8963 adjusting=false max=9552 maxScrollable=9155      <- remove(0) 触发的事件
  [adj] value=8963 adjusting=false max=9600 maxScrollable=9203      <- addElement 触发的事件
判定跟随=false
```

结论：头部淘汰会**自行触发调整事件**；距底 5 行时 `value` 不被 clamp，因此不会误恢复跟随；但只要用户停在距底不足一行高的位置，`value` 会被 clamp 到 `maxScrollable`，命中 `SqlLogPanel.kt:1572` 的 `value >= maxScrollable - 2` 判据 → **静默恢复跟随**。

## 二、根因

| 编号 | 机制 | 证据位置 | 影响 |
|---|---|---|---|
| R1 | 「关键字换行」取源是**摘要**（首行或 160 字处截断），不是完整 SQL；只有「格式化」分支用 `restoredSql` | `SqlListFragmentRenderer.kt:27-35`（`displaySqlForList`：`if (mode == FORMATTED) return maskDisplay(restoredSql)`，其余返回 `entry.summary`）、`LogSqlModels.kt:258-271` | 列清单长的 SELECT 在 160 字内到不了 `FROM`，`formatClauseLine` 一个换行点都找不到 → 只产 1 行 → `visibleSqlLines` 的三行上限无从发挥 → 宽度裁切补 `…`，即截图里的单行 `…;` |
| R2 | 能否凑出三行取决于「列清单是否在 160 字内结束」，与「三行显示」这一承诺无关 | 同上；`SqlEntryPresentation.kt:189-201`（`MAX_KEYWORD_WRAP_LINES=3` 只封顶不兜底） | 用户看到的是「有的三行、有的单行」，观感像随机 |
| R3 | 暂停只挡住了「贴底」这一步，没挡住「头部淘汰导致的内容位移」 | `SqlLogPanel.kt:1680-1683`（`if (tailFollow.shouldFollow …) scrollToBottom`）与 `SqlLogPanel.kt:1708-1712`（`HeadEvictAppend` 无条件 `remove(0)`） | 暂停态下每条新 SQL 都把内容上顶一行高；`KEYWORD_WRAP` 变行高（`applyListCellHeight` 置 `fixedCellHeight=-1`，`SqlLogPanel.kt:1596-1603`）时一行高可达 ~100px，跳动非常显眼 |
| R4 | 「回到尾部」的判定无法区分「用户手势」与「程序性/模型变化引起的滚动条变化」 | `SqlLogPanel.kt:1566-1575` | 停在贴底一行内时，淘汰引起的 clamp 会被判成「用户滚回底部」→ 静默恢复跟随，用户再滚也拉不回来 |
| R5 | 差分失败走全量重建时视口直接回顶/回底，暂停态不保留阅读位置 | `SqlLogPanel.kt:1736-1762`（`listModel.clear()` + 分批 `addAll`） | 改排序、清搜索、去重开关切换后阅读位置丢失（次要，但与 R3 同源） |

R1、R3 是两个报障的直接成因；R2 是 R1 的必然结果；R4、R5 是同一「视口意图」缺失的另外两个出口。

**为什么测试一直绿**：`SqlListFragmentRendererTest.kt:138`（`SELECT a, b FROM t WHERE x = 1`）、`SqlLogPanelRendererUiTest.kt:290-293`（断言 ≥3 行、`FROM`/`WHERE` 独立成行）用的都是**短列清单**夹具——摘要能覆盖到 `WHERE`，于是「取摘要」这个错误前提被完全遮住。这与仓内既有「假绿」形态同族（`pitfall-guard-covers-audited-targets`）。

## 三、修复方案

### 券01（P0）：关键字换行改取完整 SQL，并给输入加预算

- `displaySqlForList`（`SqlListFragmentRenderer.kt:27`）里 `KEYWORD_WRAP` 与 `FORMATTED` 合流：取 `DisplayMasker.maskDisplay(entry.restoredSql, masked)`，脱敏口径与格式化分支逐字一致（`restoredSql` 是 `summary` 的唯一来源，见 `LogSqlModels.kt:80-89` 的默认值，不存在「有摘要无还原文本」的条目）。
- 加输入预算 `KEYWORD_WRAP_SOURCE_BUDGET = 4096`（按空白边界截断、尾部补 `…`）：IN 列表上万字的语句不能把整段词法搬到 EDT。预算只影响「第四行及以后」的可见性——三行封顶后本来也看不到。
- 条目级缓存键保持 `(mode, masked)` 不变（`RailCellRendererBase.kt:292-308`），语义不变：同一 (模式, 脱敏态) 下渲染输入不变。
- 不强行造行：完整 SQL 的词法结果若本来只有一行（DDL、无子句的单值查询），保持单行——「三行」是上限不是配额。
- 末尾 `… (+N)` 尾巴与 `MAX_KEYWORD_WRAP_LINES=3` 均不动。

### 券02（P0）：暂停态视口锚定

在 `refreshList` 的模型变更外层做一次「锚点保留」，不散落到各个分支：

1. 变更前（仅当 `!tailFollow.shouldFollow` 且列表有可滚动内容）：取视口左上角在列表坐标系的位置 `p`，`anchorIndex = list.locationToIndex(p)`，`anchorEntry = lastRendered[anchorIndex]`，`anchorOffset = cellBounds.y - p.y`。
2. 变更后：在新模型里定位 `anchorEntry`（`HeadEvictAppend` 下即 `anchorIndex - evicted`，按同一性校验；找不到则退到新首条 + 原偏移），把 `viewport.viewPosition` 复原到 `newBounds.y - anchorOffset`。
3. `applyFullRebuild`（R5）同样在首屏批次写完复原一次；锚点条目不在新集合里（被过滤/被淘汰）时退到「新首条 + 原偏移」，至少不回到顶部。
4. 复原动作走 S3 的程序性通道，不得反过来触发「恢复跟随」。

实现要点：`HeadEvictAppend` 只需把视口上移量等于被删行的总高，可用「变更前 `getCellBounds(0, evicted-1).height`」直接算，避免变更后全量重算布局；两种实现都行，验收以**「顶部可见条目不变」这一可观察量**为准，不锁实现。

### 券03（P1）：程序性滚动不改跟随意图

- `scrollToBottom`（`SqlLogPanel.kt:302`）与券02 的视口复原统一经 `setViewPositionProgrammatically { … }`，期间置标志位，`addAdjustmentListener`（`:1566`）在标志位期间直接返回。
- 「用户把滚动条拖到底 / 滚轮滚到底恢复跟随」的行为**保持现状**（用户 2026-10-26 定案的 T-ListMetaRevert 语义，本次不动）。
- 顺带把 `scrollToBottom` 的 `bar.value = bar.maximum` 改为 `bar.value = (bar.maximum - bar.visibleAmount).coerceAtLeast(0)`：现写法靠 Swing clamp 兜底，语义上写错了量，变行高下容易差一两像素。

### 券04（P2）：文档与索引同步

- `docs/架构设计/02-核心流程详解.md` 的 `SqlListFragmentRenderer` 行：补「关键字换行取源=完整还原 SQL（预算 4096 字符），不再取摘要」。
- `docs/架构设计/01-项目架构总览.md` 同名条目同批补一句。
- `docs/索引/开发计划.md` 补本计划行（时间倒序，置于表首）。
- 不新增设置项、不改历史文件格式、不改 `SqlListDisplayMode` 枚举值（已持久化在用户配置里，改值即丢设置）。

## 四、实施顺序与待验证前提

1. 先落夹具（§五 用例 4），跑出「旧实现必红」的读数，再动生产代码（仓内定例：守卫/形态类改动必须先取红基线）。
2. 券01（取源 + 预算），跑定向 `SqlListFragmentRendererTest` / `SqlLogPanelRendererUiTest` / `HistoryCellRendererTest`。
3. 券02（锚点）+ 券03（程序性标志），跑定向 `SqlLogTailFollowTest`。
4. 券04 文档同步。
5. 单路只读收口跑全量 → 真机（`runIde` 沙盒，200 上限 + 高频 SQL 灌入，双模式各看一次）——**操作卡见 §九**；本条 2026-10-29 因用户要求先用电脑而暂停，见 §8.5 第 2 条。

待验证前提（实施者先证伪再动手）：

- **P-1**：历史页读回的条目是否都带 `restoredSql`。`LogSqlEntry.restoredSql` 是必填构造参数、`summary` 默认由它派生，理论上不存在空值；但历史解析链（`SqlLogFileReader`）若某分支塞了空串，券01 会让该条目渲染成空行。实施前 grep 全部 `LogSqlEntry(` 构造点确认。
- **P-2**：4096 字符预算下 `WHERE` 是否总能落在预算内。用真实日志取样（列清单 24 列 + IN 列表 500 项两类）统计 `FROM`/`WHERE` 的字符位置，给出分布再定预算；若发现常见语句超预算，改为「流式词法、凑够三行即停」而不是加大预算。
- **P-3**：锚点复原会不会被随后的 `revalidate`/布局覆盖。headless 夹具能证布局层（`doLayout` 递归），证不了真机 EDT 时序；须在 `runIde` 沙盒里确认，若被覆盖则改为「在 `invokeLater` 里补一次复原」。
- **P-4**：`snapshot()` 实测 0.5~3.5s（`size≤200`，`toList` 本身是微秒级）的成因未定性——锁等待或 GC 暂停都可能。**本计划两条问题不依赖它**；若 S2/S3 落地后用户仍报「滚动没反应」，再单独取证（在 `snapshot` 前后加锁等待计时探针），不在本轮顺手改。
- **P-5**：`KEYWORD_WRAP` 取完整 SQL 后，同屏 11 条 × 4096 字符的词法成本。条目级缓存保证每条只算一次，但首帧仍要在 EDT 上算可见行；须给 `LogEntryRailPerfTest` 补一条预算内读数，超标则退回「摘要优先 + 无子句时再取完整 SQL」的两段式。

## 五、测试计划

新增/扩写（IDE 侧定向，不跑全量）：

| 用例 | 夹具 | 断言 |
|---|---|---|
| `SqlLogTailFollowTest#暂停且缓冲已满_头部淘汰不得移动用户视口` | `maxEntries=80`，灌满，暂停在中段（`bar.value=800`） | 连续追加 3 条后，**视口顶部可见条目对象不变**、视口像素位置按被删行高上移、仍 `PAUSED` |
| `SqlLogTailFollowTest#暂停贴底附近_头部淘汰后仍保持暂停` | 暂停在距底 10px 内 | 追加后仍 `PAUSED`（端到端冒烟；区分力见下条） |
| `SqlLogTailFollowTest#滚底判据_位置变小与程序性滚动都不算用户手势` | 纯函数 `isUserScrollToBottom` 六形态 | 位置变大到底=是；位置变小到底（clamp）=否；程序性=否；未到底=否；容差 2px 内=是；不可滚动=否 |
| `SqlLogTailFollowTest#暂停态全量重建按锚点条目复原视口` | 暂停后改排序触发 `FullRebuild`（耗时与时间同序，降序即整体倒排，锚点落在后续批次） | 首条换成耗时最大的一条（证确实重排）；顶部可见条目为原条目；仍 `PAUSED` |
| `SqlListFragmentRendererTest#关键字换行取源为完整SQL_长列清单仍三行` | §1.3 的真实 24 列 SQL | 3 行；首行 `SELECT`、次行 `FROM`、第三行 `WHERE` |
| `SqlListFragmentRendererTest#单行模式仍取摘要_不受券01影响` | 同上 | 单行模式返回 `entry.summary`；格式化模式含表名（同源完整 SQL） |
| `SqlListFragmentRendererTest#关键字换行取源预算_超长按空白边界截断` | 2000 项 IN 列表 | 长度 ≤ 预算+1；带 `…`；末字符不是字母数字（不劈标识符）；短 SQL 原样返回 |
| `SqlLogPanelRendererUiTest#关键字换行取源为完整SQL_长列清单仍三行` | 同上，走真实渲染器 | `renderer.sqlLines.size == 3`，且不再出现摘要截断形态（`…;`） |
| `LogEntryRailPerfTest#关键字换行_预算边界SQL首帧成本与缓存命中` | 400 列 SQL（远超预算） | 3 行封顶；首帧 < 50ms；第二次渲染命中同一缓存实例且 < 5ms |

反向验证（三次，均先临时移除再复跑，各贴读数；实际读数见 §8.2）：

1. 把券01 改回取 `entry.summary` → 「长列清单」两条必红；
2. 摘掉券02 的补偿与锚点复原 → 「头部淘汰」与「全量重建」两条必红；
3. 摘掉券03 的单调性判据 → **纯函数判据用例**必红（不是集成用例——集成用例摘掉后仍绿，见 §8.2 说明）。

回归面：`HistoryCellRendererTest`（两个列表共用 `RailCellRendererBase`）、`SqlLogListFreezeGuardTest`（行高/冻结守卫）、`SqlListV179HeadlessTest`、`BundleParityGuardTest`（本计划不加文案键，预期零改动）。

## 六、验收边界

- 关键字换行：能凑够三个子句的语句恒三行实内容；长列清单不再退化成单行；单行/格式化两种模式形态不变。
- 暂停态：新 SQL 到达时列表尾部照常增长，但**正在看的那条不动**；只有点「滚动到底部」或用户手动滚到底才恢复跟随。
- 历史页：跟随行为不变（历史页无尾随，只有跳转），但关键字换行形态与日志页同批修复。
- 不做：不做「暂停时冻结列表内容」（用户要求的是视口不动，不是停止入列）；不改排序/过滤语义；不引入新设置项。
- 证据边界：本轮根因由源码 + 运行期日志 + 两个独立 Swing 探针三方交叉，但**没有在真机上复现「暂停后跳动」的录屏**；真机确认放在 §四 第 5 步，若沙盒不复现，按 P-3 处置而不是回头改判根因。

## 七、风险与回退

- 券01 改变了列表里看到的文本：从「摘要前 160 字」变成「完整 SQL 的前三个子句」。这是用户诉求本身，但会连带改变用户对「列表显示什么」的既有印象；若用户希望单行模式也看完整列清单，那是另一个诉求（单行模式按设计只显示一行，不动）。
- 券01 的成本风险由 P-5 兜底：超标则退回两段式（摘要优先，摘要里找不到第二个子句关键字时再取完整 SQL），行为等价、成本减半。
- 券02 触碰 Swing 布局时序，是三条里唯一有「改了也没用」风险的（P-3）；回退只需删锚点一处，券01/03 独立生效。
- 券03 只在标志位期间屏蔽监听器，若标志位泄漏（异常路径）会导致「滚到底不恢复跟随」；用 `try/finally` 复位，并加一条「标志位复位」断言。

## 附：探针复现命令（不启 Gradle）

```bash
cd tmp/probe-head-evict
"D:/HuanJing/Java/jdk1.8.0_181/bin/javac" -encoding UTF-8 ProbeHeadEvict.java
"D:/HuanJing/Java/jdk1.8.0_181/bin/java" -Dfile.encoding=UTF-8 ProbeHeadEvict
# 判据：before/after 的 name 从 row-30 变 row-31 而 viewPos 不变 ⇒ 头部淘汰即上移一行

"D:/HuanJing/Java/jdk1.8.0_181/bin/javac" -encoding UTF-8 ProbeEvictBar.java
"D:/HuanJing/Java/jdk1.8.0_181/bin/java" -Dfile.encoding=UTF-8 ProbeEvictBar
# 判据：remove(0)/addElement 各触发一次 adjustment 事件（程序性滚动与用户手势在监听器里不可区分）
```

探针目录 `tmp/` 已在 `.gitignore` 内，不进仓、不参与构建；实施完成后如需保留证据，把读数抄进 §八 实施记录，探针本身可删。

## 八、实施记录（2026-10-29）

### 8.1 改动清单

| 文件 | 改动 |
|---|---|
| `SqlListFragmentRenderer.kt` | `displaySqlForList` 按模式分叉取源（单行=摘要，关键字换行/格式化=完整还原 SQL）；新增 `KEYWORD_WRAP_SOURCE_BUDGET=4096` 与 `clipKeywordWrapSource`（按空白边界截断 + `…`）；摘要脱敏缓存 `cachedMaskedSummary` 收窄为只服务单行模式 |
| `SqlLogPanel.kt` | 程序性滚动窗口 `programmaticScrollDepth` / `withProgrammaticScroll`（模型变更 + 锚点复原整体入窗）；`lastScrollBarValue` 基线 + 调整监听器改调纯函数 `isUserScrollToBottom`；`scrollToBottom` 取 `maximum - visibleAmount`；新增 `ScrollAnchor` / `pendingScrollAnchor` / `captureTopAnchor` / `restoreAnchor` / `shiftViewportUpBy`；`HeadEvictAppend` 按被删行高上移视口；`refreshList` 走全量重建前取锚点；`appendRebuildChunk` / `flushRebuildChainSync` 逐拍复原 |
| `TailFollowState.kt` | 「已滚到底」容差 `SCROLL_BOTTOM_TOLERANCE_PX` 迁入（与判据同处）；新增纯函数 `isUserScrollToBottom(previous, current, maxScrollable, programmatic)` |
| `SqlLogTailFollowTest.kt` | 新增 4 例：暂停态头部淘汰不移动视口、贴底附近淘汰后仍暂停、全量重建按锚点复原、滚底判据纯函数六形态；抽出 `layoutTree` / `topVisibleEntry` / `sqlEntry` / `wheelUpOnce` / `fillToCapacity` 夹具 |
| `SqlListFragmentRendererTest.kt` | 新增 3 例：完整 SQL 取源长列清单三行、单行模式仍取摘要、预算截断不劈字面量 |
| `SqlLogPanelRendererUiTest.kt` | 新增 1 例：真实渲染器下长列清单仍三行且不再出现 `…;` 摘要截断形态 |
| `docs/架构设计/02-核心流程详解.md`、`01-项目架构总览.md` | 补「取源按模式分叉 + 4096 预算」「暂停态视口锚定」 |
| `docs/索引/开发计划.md` | 本计划登记行（状态回填已实施） |

### 8.2 反向验证（三次，均已复跑取读数）

| 移除项 | 读数 |
|---|---|
| 券01：`KEYWORD_WRAP` 取源改回 `entry.summary` | `SqlListFragmentRendererTest` + `SqlLogPanelRendererUiTest` 共 70 例 **2 红**，且恰是两条「长列清单仍三行」——其余 68 例仍绿，说明摘要形态只被这两条钉住 |
| 券02：摘除 `HeadEvictAppend` 补偿 + 全量重建锚点 | `SqlLogTailFollowTest` 14 例 **2 红**：「头部淘汰不得移动用户视口」「全量重建按锚点条目复原」 |
| 券03：摘除单调性判据（`current < previous` 一律否决） | 首轮用集成用例验证时**未变红**（15 例全绿）——原因是券02 的视口补偿已抢先消除「内容变矮 → clamp 到底」这条通路，集成夹具没有区分力。改为把判据抽成纯函数并补 `test滚底判据_位置变小与程序性滚动都不算用户手势` 后再摘除，**1 红**（恰为该判据用例）。集成用例保留但已改名 `test暂停贴底附近_头部淘汰后仍保持暂停`，KDoc 里如实标注「本条无区分力，只作端到端冒烟」 |

### 8.3 P-2 实测：4096 预算的依据

从 `%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2\log\idea.1.log:18624` 取出用户现场那条 `Preparing:` 语句（即报障截图 #2 类形态），逐字测子句位置：

```
chars=2958  FROM@405  WHERE@431  ORDER@2917  LIMIT@2941
```

结论：`FROM`/`WHERE` 落在 4096 预算内且余量充足；`ORDER` 也在预算内，但受三行封顶不进可见区。预算取值成立，无需改为流式词法。

### 8.4 测试读数

- 定向批（`./gradlew test --tests "cn.song.mybatisplus.visualizer.log.*"`）：**162 类 / 1782 例 / 0 失败 / 0 错误 / 0 跳过**，墙钟 6m04s（该批跑在新增 perf 用例之前）。
- 性能守卫（`LogEntryRailPerfTest`，17 例含新增 `test关键字换行_预算边界SQL首帧成本与缓存命中`）：**BUILD SUCCESSFUL**，1m32s。
- P-3 补充用例（`SqlLogTailFollowTest`，16 例含新增 `test锚点复原后经revalidate重布局仍停在原条目`）：**16 / 0 / 0 / 0**，BUILD SUCCESSFUL，2m17s。
- 全量门禁（`./gradlew testShards --no-configuration-cache`）：五片合计 **782 类 / 8368 例 / 4 失败 / 0 错误 / 8 跳过**。墙钟：shard1~4 同一次运行 23m01s（在 shard4 中止，故 shard5 单独补跑 3m30s）。

  **4 条失败全部是 `DialectDocTableGuardTest`（分页表/UPSERT 表 zh+en），与本次改动无关**：
  ```
  wrapper-preview 分页表（zh）: 出现未知方言 gaussdb（与 DialectRegistry 对不上）
  ```
  取证：该守卫读的是**兄弟文档站仓** `../mybatis-plus-sql-visualizer-docs` 的 `features/wrapper-preview.md` /
  `batch-operation.md`，该仓已落 `fa7ce8e docs(features): 方言表补 GaussDB（第 12 个方言）`（表格首列含
  `PostgreSQL / openGauss / GaussDB`）；而本仓 `Dialect.kt:DialectRegistry` 里 gaussdb 只作为 openGauss 的
  URL 别名出现，没有独立方言登记——GaussDB 方言计划（`20261029-1215-T-GaussDbDialect`）仍是**待拍板**。
  `git status` 里 `src/main/.../sql/` 零改动，本次 diff 也不含任何方言与文档站文件，故这条红在 HEAD 上同样成立。
  **留给 GaussDB 那一路**：方言登记落地（或文档站撤回该行）后自动转绿。
- 中途一次红：首版「暂停且缓冲已满」用例把视口停在第 2 行（`bar.value=120`），三次淘汰后锚点条目本身被淘汰——属夹具构造问题（锚点被删时退到顶部是设计行为），已把锚点移到中段（`bar.value=800`）复跑转绿。

### 8.5 遗留与证据边界

1. **P-1 已验证**：13 处 `LogSqlEntry(` 构造点全部传非空 `restoredSql`（解析器传解析出的 `sql`；`SqlLogParamCodec` 走 `rawSql` 双写；历史读回 `SqlLogFileReader:348/376` 传 `sql`），不存在「有摘要无还原文本」的条目，券01 不会凭空渲染空行。
2. **P-3 部分闭环（headless），沙盒部分被用户中止**。
   - **已证（headless，2026-10-29 补测）**：`test锚点复原后经revalidate重布局仍停在原条目` —— 复原后强制再走两轮 `revalidate()` + 完整树 `doLayout` + 排空 EDT 队列，视口顶部仍是原条目、状态仍 `PAUSED`（`SqlLogTailFollowTest` 16 例 / 0 失败）。即「布局过程本身不会把复原结果覆盖掉」这条成立。
   - **未证（必须真机）**：两次 EDT 事件之间的**真实异步时序**（平台布局脉冲会不会在我们设置位置之后才跑、并以旧的 `viewSize` 把位置夹回去），headless 的同步布局证不了这条。
   - 起因：一开始做前置检查时用户要求「先不要 UI 验收，我要用电脑」，沙盒进程**一个都没启动**，未产生界面占用。**可复跑配方见 §九**（隔离 init 脚本 `record/t-loglstanchor-ui.init.gradle` 已落、临时夹具与灌入循环已按字符算术核对区分力、前置逐条核对就绪；§九 命令尚未首跑）。
3. **P-4 未取证**：`refreshList.snapshot` 实测 0.5~3.5s（锁等待 vs GC）仍未定性，本轮不改动；若真机仍报「滚动没反应」，先补这条探针。
4. **P-5 已补守卫、未取真机读数**：`test关键字换行_预算边界SQL首帧成本与缓存命中` 钉住「首帧宽松预算 + 二次命中同一缓存实例」；headless 绝对耗时不能当真机读数，真机若见卡顿仍需重测。
5. **`ORDER BY` 与 `LIMIT` 不在可见三行内**：这是 `MAX_KEYWORD_WRAP_LINES=3` 的既有取舍（本次未改），要看得点单击预览。

## 九、实机验收操作卡（待执行）

> 本节是 §四第 5 步「真机 runIde 沙盒」的可复跑配方。**尚未首跑**——2026-10-29 用户要求「先不要 UI 验收，我要用电脑」；随后（同日 15:4x）就「机器现在可以给我占用吗」明确询问一次，**未获答复**，故按原指示继续待命、沙盒未启动。下面每条路径都已在做前置检查时逐个核对存在性（见 9.1），夹具 SQL 已在真库跑通、端口冲突已改 8082，但**命令本身未执行过**，首跑时要按实际输出校正。
>
> **开工条件：用户明确同意占用机器。** 这四项判据全部依赖真实桌面（从 IDE 内 Run demo、滚轮、截图），无 headless 替代路径。

### 9.1 前置（2026-10-29 15:0x 已核对，均就绪）

| 项 | 实测值 |
|---|---|
| **8081 被占（重要）** | 8081 上跑的是**用户自己的业务应用**（Java 进程 pid 59104，命令行带 `-javaagent:...mpsv-agent/mps-sql-agent-*.jar=port=60346`，即已挂增强捕获、事件发往日常 IDE），**不是 demo**；`/user/list` 等 demo 端点一律 404。demo 的 `application.yml` 默认也是 `server.port: 8081` ⇒ **沙盒里 Run demo 时必须加程序参数 `--server.port=8082`**，否则端口冲突起不来 |
| MySQL | `127.0.0.1:3306` 在跑；demo 连的是 `jdbc:mysql://localhost:3306/mp_demo`（`application.yml:9`） |
| demo 仓 | `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo`（多模块，业务 app 在 `mybatis-plus-demo`） |
| robot-server | `record/robot-plugins/robot-server-plugin/lib` 存在（`-PgifRobotServer=true` 可用） |
| 许可 agent | `C:/Users/USER/.7t/patch/qwdn-afb.jar` |
| 许可实体 | `%APPDATA%/JetBrains/IntelliJIdea2026.2/idea.key`（5300B，2026-10-29 13:06） |
| 构建 JDK | `C:\Users\USER\.jdks\ms-21.0.11`（照 `.local/start-explain-pwd-ui.ps1` 先例） |

### 9.2 判别力前提（最重要的一条，不满足就白验）

demo 仓现有实体最宽是 `User`（12 个真实列），MP 生成的列清单约 93 字符——`FROM` 落在**旧 160 字摘要**之内，旧实现也能出三行，**没有区分力**。故必须按 skill 既有做法造临时夹具（验完撤销，不入 demo 仓历史）。

**夹具（2026-10-29 已按 demo 实体逐列核对，全部是 `user` 表真实列 + 真实列上的表达式；`role_name`/`department`/`orders` 是 `@TableField(exist=false)` 非表字段，不能用）**

`mybatis-plus-demo/.../mapper/UserMapper.java` 追加：

```java
    /** T-LogListAnchor 实机验收临时夹具（验完删除）：16 列别名清单，前 160 字不含任何子句关键字。 */
    @Select("SELECT id AS userId, username AS userName, password AS userPassword, age AS userAge, "
            + "email AS userEmail, role_id AS roleId, dept_id AS deptId, status AS userStatus, "
            + "create_time AS createTime, update_time AS updateTime, deleted AS deletedFlag, "
            + "version AS versionNo, CONCAT(username, '-', email) AS userLabel, "
            + "IFNULL(age, 0) AS userAgeSafe, "
            + "DATE_FORMAT(create_time, '%Y-%m-%d') AS createDate, "
            + "CONCAT(role_id, ':', dept_id) AS roleDeptPair "
            + "FROM user WHERE username LIKE CONCAT('%', #{q}, '%') AND deleted = 0")
    java.util.List<User> selectWideAliasList(@Param("q") String q);
```

`.../controller/UserController.java` 追加：

```java
    @GetMapping("/wide")
    public List<User> wide(String q) {
        return userMapper.selectWideAliasList(q == null || q.isEmpty() ? "wide" : q);
    }
```

**区分力已按字符算术核过**（`restoredSql` 单行形态，与列表摘要同源）：

```
chars=492   FROM@436   WHERE@446   前 160 字含 FROM/WHERE/JOIN/LEFT/INNER/LIMIT/GROUP/ORDER = 无
```

即旧实现（取 160 字摘要）只会渲染成单行 + `…;`，新实现（取完整 SQL）必然三行——这条夹具能真正区分新旧。

**夹具 SQL 已在真库执行通过**（2026-10-29，DBX 只读通道，无需界面）：

```
库名 = mp_demo（注意不是 mpj_demo——那是 mybatis-plus-join-demo 的库）
mp_demo.user 列 = id/username/password/age/email/role_id/dept_id/status/remark/create_time/update_time/deleted/version
夹具 SELECT 实跑 = 16 列别名全部解析成功，19ms 返回 0 行（列名、CONCAT/IFNULL/DATE_FORMAT 表达式、WHERE 全部有效）
表内现有数据 = 10 行（admin…zhangsan）
```

所以现场不会因为列名写错而白跑一轮。

### 9.3 启动（隔离沙盒 + 登记）

```powershell
# 0) 先登记，避免与并行会话的沙盒/索引互锁
python C:\Users\USER\.zcode\cli\parallel_tasks.py register --name t-loglstanchor-accept --sandbox <repo>/build/MPSV-LogAnchor-UI

# 1) 隔离 init（照 .local/explain-pwd-ui.init.gradle 形态：sandboxContainer=build/MPSV-LogAnchor-UI、autoReload=false）
#    内容：allprojects { afterEvaluate { p -> if (p == p.rootProject) { intellijPlatform { sandboxContainer = layout.buildDirectory.dir('MPSV-LogAnchor-UI') }; tasks.named('runIde') { autoReload = false } } } }

# 2) 起沙盒（打开 demo 工程；robot-server 供组件树定位）
.\gradlew.bat -I record/t-loglstanchor-ui.init.gradle runIde `
  --args="F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo" `
  -Pmpvs.licenseAgent="C:/Users/USER/.7t/patch/qwdn-afb.jar" `
  -PgifRobotServer=true -Pkotlin.compiler.execution.strategy=in-process --no-configuration-cache --console=plain
```

判身份：`build/MPSV-LogAnchor-UI/log/idea.log` 的 mtime 在刷新；界面不弹 `Start Free Trial`（弹了就是缺 `config/idea.key`，先关 IDE 再拷）。

### 9.4 三条断言与判据

高频灌入（**参数必须逐次变化**——面板默认开着「SQL 去重」（`SqlLogEntryBuffer.kt:51` `dedupeEnabled = true`），同一条 SQL 会被折叠成 ⨉N 而不占新条目；每 5 条掺一条 9.2 的长列夹具）：

```powershell
# 端口用 8082：8081 被用户自己的业务应用占着（见 §9.1），demo 默认也是 8081
1..400 | ForEach-Object {
  Invoke-WebRequest -UseBasicParsing "http://localhost:8082/user/list?username=ui$_" | Out-Null
  if ($_ % 5 -eq 0) { Invoke-WebRequest -UseBasicParsing "http://localhost:8082/user/wide?q=wide$_" | Out-Null }
  Start-Sleep -Milliseconds 40
}
```

| # | 操作 | 判据（证据） |
|---|---|---|
| 1 | 沙盒内**从 IDE Run 起 demo**（外部启动捕获链挂不上，既有定例）→ 跑上面的灌入循环，直到面板 ≥250 条。demo 仓 `.idea/runConfigurations` 是空的，没有现成配置：在 main class 的 gutter 上点 Run 让 IDE 自建 Spring Boot 配置，再在其 Program arguments 里填 `--server.port=8082`（若该配置带了 `-javaagent:...qwdn-afb.jar` 这类模板残留 VM 参数就删掉——demo 不需要许可补丁） | 面板条目数停在 200 不再增长但仍在刷新 = 已满载（此后每条新 SQL 都在淘汰头部） |
| 2 | 用滚轮把列表滚到中段停下 → 再跑 30 条灌入 → 前后各截一帧 | **两帧的视口顶部那条 SQL 文本必须逐字一致**，滚动条位置按被删行高上移；旧实现在此会整体上跳一行高（这正是用户报障形态） |
| 3 | 在列表里找到 `/user/wide` 那条（带 `userLabel`/`createDate` 别名的长列查询） | 该条**三行**：首行 `SELECT`（超宽按宽度裁切补 `…` 属正常）、次行 `FROM user`、第三行 `WHERE`；旧实现是单行 + 行尾 `…;` |
| 4 | （P-3）暂停态下用工具栏排序按钮切一次排序触发全量重建 | 重建完成后**视口顶部仍是原条目**（不是回顶、也不是贴底）；若被 `revalidate` 覆盖，表现就是回顶——过则 P-3 闭环，不过则按计划 §四「在 `invokeLater` 里补一次复原」修 |

证据帧统一落 `build/MPSV-LogAnchor-UI-shots/`（build 生命周期、不入 git），文件名带动作序；语义级文本拿不到组件树时降级为「截图 + 区域指纹」并如实标注。

### 9.5 收尾

```powershell
# demo 临时夹具撤销——**只撤我加的那两个文件，禁用 `git checkout -- .`**：
# demo 仓是共用工作树，2026-10-29 15:5x 实测其已有别人的在途改动（OrderMapper.xml 被改、.ai/ 未跟踪），
# 全工作树还原会把别人的活儿一起清掉（与本仓「并行会话别用 git add -A」同族陷阱）。
git -C F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo checkout -- `
    mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/mapper/UserMapper.java `
    mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/controller/UserController.java
git -C F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo status --short
# 判据：输出里**不应再出现上面两个文件**；其它既有改动继续保持原样（不是「输出为空」）
# 关沙盒（runIde 退出常卡 Waiting for process detach：90s 有界等待后按 PID 精确 kill）
python C:\Users\USER\.zcode\cli\parallel_tasks.py done --name t-loglstanchor-accept
```

读数回填到 §8.5 第 2 条（把「未做」改成「已做」+ 三条判据的实际结果），索引行同步。

