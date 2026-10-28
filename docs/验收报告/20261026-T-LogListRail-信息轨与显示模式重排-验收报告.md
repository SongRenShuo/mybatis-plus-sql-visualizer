# T-LogListRail / T-LogListHeader 验收报告：SQL 列表版面演进（信息轨 → 头部行）

> **更新（2026-10-26 晚）**：本文档主体记录 `bed58b64`（T-LogListRail，左侧 88px 信息轨）。
> 用户实测后否决该形态，同日实施 `T-LogListHeader` 改为**头部行 + 上下 12px 留白 + 1px 细线**，
> 左侧栏、进度条、斑马纹全部撤除。**当前生效形态以 §零 为准**，§一 以下为历史记录。

---

## 零、最终形态（T-LogListHeader，当前生效）

### 0.1 版面

```
[色条] #序号 时间 耗时 [库] 类名:行号 (行数) 同结构 N 条   ← 头部行
       SELECT ...                                       ← SQL 第 1 行
       FROM ...
       WHERE ...
       ⛔ 报错（仅 errorText 存在时）
```

- **无左侧栏、无进度条、无斑马纹**（三项均经预览后由用户否决）
- 条目分割 = 上下各 12px 留白（单行模式 8px）+ 底部 1px 略强色线
- 头部行字段顺序 = 用户选定**方案④「库前置」**

### 0.2 头部行超宽的处置（优先级丢片段）

按优先级从低到高丢：**日期段 → 影响行数 → 库角标 → 同结构徽章 → 来源行号 → 序号**。
未列入的（时刻、耗时、类名、MCP、去重计数）只在整个头部行真放不下时才被截断。

新算法 `clipFragmentsByPriority`，9 条单测覆盖各宽度档位。

### 0.3 与 §一 及以下的关键差异

| 项 | §一 记录（已废弃） | 当前生效 |
|---|---|---|
| 左侧信息轨 | 88px 固定宽 | **已撤除** |
| 耗时进度条 | 2px 归一化条 | **已撤除** |
| 条目分割 | 强色线 + 斑马纹 | **留白 12px + 细线，无斑马纹** |
| 方言/类名位置 | 注脚行 / 左侧栏 | **头部行内，库在类名之前** |
| 头部行裁剪 | 无（靠注脚行分流） | **按优先级丢片段** |
| 测试读数 | 1750 | **1759** |

### 0.4 本次新增测试与验证

| 项目 | 结果 |
|---|---|
| `log` 包全量 | **1759 / 0 失败 / 0 跳过** |
| 反向验证 | R1~R5 五组，分别 1/6/1/1/2 红，全部精确命中 |
| 新增守卫 | 优先级裁剪 9 条、字段顺序 2 条、条目分割 2 条 |

**实施中抓出三个真 bug**（详见计划 §7.2）：
1. 类名片段缺尾随空格 → `Order:42⨉3` 粘连
2. 日期剥离规则因方括号形态不匹配而静默失效
3. 日期剥离被塞进优先级循环，而时间片段优先级是 NEVER → 永不剥离

**一个测试覆盖缺口由反向验证暴露**：R1（留白退回 5px）第一轮没变红，说明「条目分割」
这个用户最在意的特性零守卫。补 2 条守卫后精确命中。

### 0.5 待真机确认

- 12px 留白是否合适（常量 `RailCellRendererBase.ROW_VERTICAL_PADDING`，改一个数即可）
- 三种模式 × 两页 × 窄宽两档的观感
- 头部行在真实字体下的裁剪点位是否符合预期

---


> **验收日期**：2026-10-26
> **实施分支**：`master`
> **计划文档**：[20261026-1616-T-LogListRail-...-开发计划.md](../开发计划/20261026-1616-T-LogListRail-日志列表左侧信息轨与三种显示模式重排-开发计划.md)
> **形态定稿依据**：用户经 9 套 HTML 方案对照后逐轮校正（关键字换行三行实内容 / 格式化不封顶且类名沉底 / 去掉增删改查字样 / 行号并入轨道）

---

## 一、交付内容

### 1.1 版面变化

改造前：每条 = 左侧 3px 色条 + 元信息行（平铺文本）+ 1~N 行 SQL。

改造后：每条 = 左侧 3px 色条 + **88px 固定宽信息轨** + 1px 竖线 + SQL 区（SQL 行 + 可选注脚行 + 可选错误行）。

信息轨竖排四行：

```
#3 13:03:55      ← 序号 + 时刻（日期不进轨道）
          28ms    ← 耗时，按阈值分档着色
▁▁▁▁▁▁▁▁▁        ← 2px 进度条，按当前可见条目最大耗时归一
…alletRecharge    ← 来源类名（骨架化压缩）
```

类型表达只保留左侧色条，**不加「查/增/改/删」字样**（用户明确否决）。

### 1.2 三种显示模式的轨道形态

| 模式 | 轨道形态 | SQL 区 |
|---|---|---|
| 单行 | 压成两行（`#N 时刻` / `耗时 + 类名`），无进度条 | 单行固定行高 |
| 关键字换行 | 四行靠上堆叠 | **三行实内容**（SELECT/FROM/WHERE 都读到），剩余条数作末行尾巴 |
| 格式化 | 四行**顶/中/底分布**（时刻顶、耗时居中、进度条与类名沉底） | **不封顶**，全部子句展开 |

### 1.3 顺带修复的既有不一致

- **历史页耗时开关失效**：`HistoryCellRenderer` 此前把 `showDuration` 硬编码为 `true`，设置页关掉耗时对历史页无效。现两页同走 `PluginSettings.state.showDurationInLogList`。
- **历史页不显示报错**：此前 `showLogDetails=false` 把报错与影响行数一并吞掉。现历史条目带 `errorText` 时同样显示错误行（去重计数仍只属实时通道——历史没有 `dedupeCounts` 快照）。
- **两个渲染器重复实现**：`LogEntryListCellRenderer` 与 `HistoryCellRenderer` 曾有三组方法逐行同构。现抽出共用基类 `RailCellRendererBase`，子类只声明「计数快照从哪取」与「耗时是否显示」两个差异点。

### 1.4 类名压缩修正

原实现取前 11 字符，`BizCarStationOccupyInfoServiceImpl` 与 `BizCarStationOccupyController` 都压成 `BizCarStati…`，无法区分。现改为**先剥公共后缀（ServiceImpl/Controller/Service/Mapper/Repository/Impl）再取尾部 13 字符**：

| 原类名 | 改造前 | 改造后 |
|---|---|---|
| `BizCarStationOccupyInfoServiceImpl` | `BizCarStati…` | `…ionOccupyInfo` |
| `MerchantWalletRechargeServiceImpl` | `MerchantWal…` | `…alletRecharge` |
| `BizTaskRecordServiceImpl` | `BizTaskReco…` | `BizTaskRecord` |

## 二、测试读数

### 2.1 定向测试

| 范围 | 结果 |
|---|---|
| `log` 包全量（含两个压测套件共 31 例） | **1750 / 0 失败 / 0 跳过** |

涉及迁移的测试文件 5 个（`SqlLogPanelRendererUiTest`、`HistoryCellRendererTest`、`SqlEntryPresentationTest`、`SqlListV179HeadlessTest`、`SqlLogListFreezeGuardTest`），共迁移约 30 处断言。

### 2.2 反向验证（四组，各自还原一处缺陷）

| 探针 | 还原的缺陷 | 结果 |
|---|---|---|
| R1 | `sqlAreaWidth` 漏扣轨道宽度（SQL 静默少字符） | **恰 1 红**：`testSQL区宽度_必须扣除轨道宽度` |
| R2 | 关键字换行退回「前 2 行 + 整行占位符」 | **恰 2 红**：两条三行语义用例 |
| R3 | 类名压缩退回「取前 11 字符」 | **恰 2 红**：`HistoryCellRendererTest` + `SqlListV179HeadlessTest` 各一条 |
| R4 | 注脚缓存键漏掉计数快照引用 | **恰 1 红**：`test注脚缓存随计数快照失效` |

R1 是最重要的一条：它对应的缺陷不抛异常、不打日志，只让 SQL 末尾静默少几个字符，只能靠算式层面的断言拦住。

## 三、压测（用户重点关注：面板内容多、更新快，不得卡顿）

### 3.1 压测覆盖范围

两个压测套件，共 **31 个用例**：

| 套件 | 压什么 | 用例数 |
|---|---|---|
| `LogEntryRailPerfTest` | **渲染器热路径**：单格渲染成本、组件树稳定性、缓存失效、宽度算式 | 17 |
| `LogPipelinePerfTest` | **刷新管线**：搜索、筛选（类型/慢查询/屏蔽）、排序（七种模式）、完整管线、线性度 | 14 |

分两档判据：

**A. 绝对耗时预算**——只拦「数量级退化」，不是性能基准。阈值取实测值的数倍到数十倍余量，CI 抖动下不假红；但把热路径改成「每格 new 组件 / 每格 buildList / 每格 revalidate」就会红。

**B. 结构性判据**——连续渲染后组件树规模必须稳定、`preferredSize` 变更次数必须为零、管线成本必须随条数线性增长。这类判据**不依赖机器快慢**，能抓住构造性缺陷（真机上表现为越滚越慢、越用越卡）。

数据规模：200 条（默认 `logMaxCount`）、2000 条、**20000 条**（用户把缓冲调到极端的场景）。

### 3.2 渲染器读数（headless，JDK 21）

| 场景 | 200 条缓冲 | 20000 条缓冲 |
|---|---|---|
| 单行模式 单格渲染 | 0.0480 ms | 0.0469 ms |
| 关键字换行 单格渲染 | 0.0324 ms | 0.0408 ms |
| 格式化 单格渲染 | 0.0338 ms | 0.0358 ms |
| 最大耗时扫描（每次刷新一次） | — | **0.6092 ms** |

**结论一：渲染成本与总条数无关。** 20000 条与 200 条的单格成本基本持平（差异在测量噪声内）——渲染器只处理当前绘制的格子，不遍历缓冲。

**结论二：一屏重绘在毫秒以内。** 一屏 20 格 × 0.05ms ≈ 1ms，远低于一帧预算（16.7ms）。

### 3.3 管线读数（20000 条）

| 操作 | 耗时 |
|---|---|
| 子串搜索（有命中） | 在 40ms 预算内 |
| 子串搜索（无命中，最坏） | 在 40ms 预算内 |
| 正则搜索 | 在 120ms 预算内 |
| 空查询 / 类型全选 / 慢查询关闭 / 无屏蔽规则 | **零成本透传**（<0.05ms） |
| 类型筛选（勾一类） | 在 20ms 预算内 |
| 慢查询筛选 | 在 20ms 预算内 |
| 屏蔽规则筛选（500 条规则） | 在 30ms 预算内 |
| 排序 TIME_ASC | 3.7 ms |
| 排序 TIME_DESC | 4.1 ms |
| 排序 DURATION_ASC | 10.2 ms |
| 排序 DURATION_DESC | 9.1 ms |
| 排序 SEQUENCE | 0.3 ms |
| 排序 STRUCTURE_CLUSTER | 16.4 ms |
| 排序 DATABASE_GROUP | 7.1 ms |
| **完整刷新管线**（屏蔽→类型→慢查询→聚焦→搜索→排序→扫描） | **22.4 ms** |

**结论三：两万条下完整刷新一次 22.4ms。** 这是既有管线的固有成本（本次改动只新增了最后的 `maxDurationOf` 扫描 0.6ms，占 3%）。

**结论四：四条常态路径是零成本的**——不搜索、不勾类型、不筛慢查询、无屏蔽规则时都是直接透传，不产生任何遍历。

**结论五：管线随条数线性增长。** 20000 条 vs 2000 条的搜索成本比与排序成本比都落在 n log n 预期内（判据阈值 25 与 40，实测远低于此），未退化为平方级。

### 3.4 关于「刷新频率」的既有保护

刷新本身已有两道节流，不是每条日志都触发一次全量管线：

- **内容通知节流**（`SqlLogEntryBuffer.notifyContentChangedThrottled`）：数量 20 条或时间 200ms 双阈值，首条即时、后续累计到阈值才再通知。
- **搜索输入防抖**：每击键一次 O(N) 过滤 → 停顿 200ms 应用一次。

按两万条最坏情况估算：每秒最多 5 次刷新 × 22.4ms ≈ 112ms，占 EDT 约 11%。这是既有管线成本，本次改动未使其变差（新增扫描仅 0.6ms/次，占单次刷新 3%）。

### 3.5 压测中发现并修复的性能隐患

改版初稿有四处会在流式灌入下退化的写法，均已在压测与审查中修掉：

1. **`preferredSize` 每格新建 Dimension**。`JComponent.setPreferredSize` 触发 propertyChange → 上层 revalidate；渲染热路径每格都设一次等于每格调度一次重排。改为只在宽度真变化时设。
   - 副产 bug：`LogEntryRail.setRailWidth` 的缓存初值等于默认宽度时，仅比数值会把首次调用误判为「已设置」，导致窄侧栏降档失效。由压测用例 `test轨道宽度_值不变不重设首选宽` 抓出，加 `railWidthApplied` 标志修掉。

2. **注脚行片段每格重建**。`SqlEntryPresentation.details()` 每次要构造 3~5 个片段 + `buildList` 分配。改为按（条目引用 + 选中态 + 两份计数快照引用）缓存，任一变化即失效。

3. **最大耗时用 `mapNotNull{}.maxOrNull()`**。为两万条分配两万槽临时列表，而该行每次刷新都跑。改为单遍遍历零分配（`maxDurationOf`）。

4. **时间格式串剥日期每格重走字符扫描**。改为按配置串缓存（单变量，非 Map）。

### 3.6 压测的已知边界

- **headless 无真实字体测量与 EDT 调度**，绝对耗时远小于真机。真机读数须在 IDE 沙盒走查（滚动流畅度、切换显示模式、流式灌入时的观感）。
- **未覆盖真实数据库/日志源**：压测用内存构造的条目，未覆盖「真实日志解析 → 条目构造」这一段的成本（该段本次未改动）。
- **未做长时间 soak 测试**（持续灌入数小时的内存曲线与 GC 分布）。建议后续在有真实高流量环境时补。
- **未覆盖历史页的大文件加载链路**（`SqlLogFileReader` 解析 + 分批填充），该链路本次未改动。

## 四、待真机走查项

headless 测试覆盖不到、需在 IDE 沙盒确认的：

1. 三种显示模式切换时，轨道内容不动、只有 SQL 区变化
2. 窄侧栏（420px）下轨道降档到 76px，类名仍可辨识、SQL 区不被挤死
3. 宽侧栏（760px）下 SQL 区占比
4. 选中态：平台选中填充下轨道各行前景色是否统一
5. 流式灌入（真实应用持续打日志）时的滚动流畅度
6. 格式化模式下长 SQL（10+ 子句）的轨道顶/中/底分布是否如设计

## 五、改动文件

**新增**
- `src/main/kotlin/.../log/LogEntryRail.kt`（轨道组件、进度条、类名骨架化、时间格式剥日期）
- `src/main/kotlin/.../log/RailCellRendererBase.kt`（共用渲染基类）
- `src/test/kotlin/.../log/LogEntryRailPerfTest.kt`（渲染热路径压测，17 例）
- `src/test/kotlin/.../log/LogPipelinePerfTest.kt`（搜索/筛选/排序管线压测，14 例）

**修改**
- `LogEntryRendering.kt`：`LogEntryListCellRenderer` 收为基类子类；新增 `railFormFor` / `railBadges` / `sqlAreaWidth` / `clipSqlFragmentsToWidth`
- `HistorySqlLogPanel.kt`：`HistoryCellRenderer` 收为基类子类（净减约 100 行）
- `SqlEntryPresentation.kt`：`visibleSqlLines` 改三行实内容；新增 `details()` 注脚片段生成器
- `SqlLogPanel.kt`：新增 `DEFAULT_TIMESTAMP_FORMAT_TIME_ONLY`；refreshList 接入 `maxDurationOf`

**测试迁移**
- `SqlLogPanelRendererUiTest.kt`、`HistoryCellRendererTest.kt`、`SqlEntryPresentationTest.kt`、`SqlListV179HeadlessTest.kt`、`SqlLogListFreezeGuardTest.kt`
