# T-ExplainFailUx：执行计划失败改为就地引导换源，预览默认展示数据源工具栏（开发计划）

**创建时间**：2026-10-24 14:20
**触发源**：用户沙盒实测（T-DialectInferPollution W8~W12 之后）——`Table 'mp_demo.wms_inventory_jar_demo'
doesn't exist` 仍以**模态错误对话框**呈现（图一），而同一次会话里另一次执行（图二）已经能看到
「已绑定：mp_demo@localhost」+ 原生控制台工具栏。用户两条诉求：
1. 这类「选错源」失败**不该弹模态框**，应就地引导换数据源（像图二那样把执行落进控制台并给切换入口）；
2. 预览窗口应**默认展示原生 SQL 控制台工具栏**（含数据源下拉），让用户一眼看到接下来会用哪条源，
   减少误解；数据源插件不可用时必须降级（不能因此报错或空白）。
**基线**：`master@24d98f56`
**状态**：仅计划（本篇未改任何生产代码）
**编号**：不占 O/R/D/V（体验缺陷修复，承接 T-DialectInferPollution 的呈现层收尾）

---

## 一、现象与证据

**图一（当前）**：点「执行计划」→ 模态对话框，红字 `Table 'mp_demo.wms_inventory_jar_demo' doesn't exist`，
底部只有「在控制台打开」「关闭」两个按钮。用户必须先关掉弹窗，再点「在控制台打开」，才知道要换源。

**图二（期望形态）**：预览面板右侧已是原生控制台（工具栏：运行/运行配置/Tx 模式/Playground/**数据源下拉
mp_demo@localhost**），左下状态栏显示「执行失败：[42S02][1146] ... [当前库=mp_demo, 数据源=mp_demo@localhost,
数据源默认库=mp_demo]」——**失败原因、当前库、数据源三要素同屏**，工具栏里就能直接换源。

**用户原话**：「这种情况就不应该弹窗了吧，应该直接是第二个图，引导用户更换数据源」「这个 sql 窗口的原生工具栏
能不能默认就显示，这样用户知道接下来要用的数据源是哪个，减少误解」「注意当数据源插件不可用时要降级的」。

---

## 二、现状与根因

### 2.1 为什么「执行计划」失败会弹模态框

`submitExplainDirect`（`ui/SqlPreviewPanelExecution.kt:1155+`）是**直送通道**：它走 `PreviewSideQuery`
只读旁路（不经原生控制台），失败时调 `showExplainErrorDialog(...)`（`ui/ExplainPlanDialog.kt:99`）——
**模态 `DialogWrapper`**，只有「在控制台打开 / 关闭」两个出口。

```kotlin
is PreviewSideQuery.Result.Failed -> when (classifyExplainFailure(result.message)) {
    ExplainFailureKind.OTHER -> showExplainErrorDialog(result.message, openConsole)   // ← 本次现象
    ...
}
```

而 `Table ... doesn't exist` 落 `OTHER` 分型（不是认证、不是取消），所以走模态。**「表不在该源」是最典型的
「该换源」信号，却被当成通用失败弹框**——这是呈现层的分类缺失，不是取源逻辑问题（取源已在 W10~W12 收口）。

### 2.2 为什么预览没默认显示原生工具栏

`prepareDefaultConsole()`（`:1884`）每次预览都会发起原生控制台准备，但：

```kotlin
onError = null,   // 「默认准备失败（无数据源等）静默——不打扰预览」
```

⇒ 数据源没绑 / runner 枚举为空 / 方言路由不匹配时，**准备链静默失败**，用户看不到任何「源是谁」的提示；
面板上方只有一行 `数据源（上次使用）：mp_demo@localhost`（`bindstate.associated`，见
`SqlPreviewPanelParamPanel.kt:421-434` 三级文案：bound / associated / unbound）。这行是**记忆**不是**事实**，
用户按它理解「接下来会用它」，一旦实际执行换了源就产生误解。

图二之所以有工具栏，是因为那次**绑定成功**（`boundConsoleDataSource` 命中）。即工具栏是否出现取决于
准备链成败——**不是默认展示**。

### 2.3 插件不可用时的现有降级（必须保持）

- `ExecuteSqlSupport.isAvailable()`（`execute/ExecuteSqlSupport.kt:40`）：反射探 `Database` 插件标记类。
- `chooseAndPrepare` 首行：不可用 → `onSubmission(BLOCKED)` + `onError(execute.noDatabase)` + `callback(false)`（`:202`）。
- `prepareDefaultConsole` 传 `onError = null` ⇒ 社区版（无 Database 插件）**完全静默**，预览仍可用
  （纯 SQL 渲染、参数面板、复制 SQL 都不依赖该插件）。

⇒ 新方案必须让「默认展示工具栏」在**插件不可用时优雅降级**：不显示工具栏、不报错、不影响预览本体，
且**不能**把社区版变成「每次预览都弹一句没插件」。

---

## 三、修法（推荐设计）

### W1：失败分类新增「选错源」档，走就地引导而非模态

`ExplainFailureKind` 增一档 `WRONG_TARGET`（或复用现有枚举扩语义），判定签名（纯函数，headless 直测）：

```
"doesn't exist" / "unknown table" / "table ... doesn't exist"（MySQL 1146 / [42S02]）
"relation ... does not exist"（PG 42P01）
"invalid object name"（SQL Server 208）
"table or view does not exist"（Oracle 942）
```

命中 `WRONG_TARGET` → **不弹模态**，改两条就地动作（二选一，推荐 a+b 同时）：
- **a) 轻通知 + 双动作**：「表 `X` 不在当前数据源 `Y`。可切换到持有该表的数据源。」动作 =「切换数据源」
  （走既有 `DataSourceSelector` 选择器，`forceChoose=true` 跳过 last-used 静默）+「在控制台打开」；
- **b) 面板结果区就地横幅**（复用 `SqlResultPanel.showErrorWithActions`，T-DialectGateUx 已建的同款出口），
  横幅内嵌同一对动作——不抢焦点、不遮预览，用户看完可直接点。

保留模态的只剩：`OTHER` 的其余通用失败（真·未知错误）、`Blocked`（方言闸阻断，已有专门出口）。

### W2：预览默认展示数据源工具栏（含不可用降级）

目标：**只要插件可用且预览已渲染，右侧/上方就显示「当前数据源 + 切换入口」**，不依赖准备链成败。

- **首选（低风险）**：不改控制台，改**指示行升级**——把 `bindstate` 三级文案从「一行灰字」升级为
  **带数据源下拉的工具栏控件**（`JComboBox` 列出在籍数据源 + 当前选中项 = 绑定/记忆/未绑定三态着色）。
  数据源列表与选择都走既有 `DataSourceSettings.getAllDataSources` + `DataSourceSelector` 切换链。
- **次选（体验更接近图二）**：`prepareDefaultConsole` 的失败路径不再静默——`onError` 改为把**失败原因**
  填进指示行（如「未绑定数据源（准备失败：runner 无匹配）」），并让控制台区域显示一行可点的引导
  （「选择数据源」按钮），而不是空白。
- **插件不可用降级（硬约束）**：`ExecuteSqlSupport.isAvailable()==false` → 不渲染下拉（退化为纯文本指示行
  「社区版无 Database 插件」或直接隐藏该控件），**绝不**弹提示、绝不让预览失败。

> 推荐组合：W2 = 指示行升级为「数据源下拉工具栏」（插件可用）+ 不可用时退化为纯文本一行。这样
> 用户在任何预览里都能看到「接下来用哪条源」，且能就地换源，与图二的工具栏语义对齐但不依赖控制台成败。

### W3：换源动作与执行计划链打通

「切换数据源」动作 = 既有 `DataSourceSelector.prepareConsole(forceChoose=true)`（T-DialectGateExit 已建）；
切换成功后**自动重试当前动作**（执行计划/预估行数），与 T-UI030 / T-DialectGateExit 的重试语义一致。
防循环：一次用户点击内最多自动重试一次（复用 W7 的 `rerendered` 同款参数位）。

---

## 四、验证方案

1. **纯函数定向**：`classifyExplainFailure` 新增 `WRONG_TARGET` 档的签名表（MySQL 1146/PG 42P01/
   SQLServer 208/Oracle 942/反例：`Unknown column` 不算选错源、`Access denied` 仍 AUTH）。
2. **守卫**：
   - 源码形守卫：`WRONG_TARGET` 分支**不得**调 `showExplainErrorDialog`（与 W6 的 AUTH 同款纪律，
     本轮教训：守卫文件归属先 grep）；
   - 指示行/工具栏守卫：插件不可用分支存在（`ExecuteSqlSupport.isAvailable` 判据在渲染前）。
3. **反向验证**：摘掉 `WRONG_TARGET` 分支 → 守卫恰 1 红；摘掉不可用降级 → 守卫恰 1 红。
4. **沙盒/真机**（用户）：jar demo 表（表在 jpa_demo）点执行计划 → **不弹模态**，就地出现「表不在当前源，
   可切换数据源」+ 切换入口；点切换 → 选 jpa_demo → 自动重试出计划。社区版（无插件）预览 → 无工具栏、
   无报错、预览正常。

## 五、待验证前提（实施者先证伪）

1. **MySQL 1146 的真实错误文本**在 `PreviewSideQuery` 失败消息里的形态（是否带 `[42S02][1146]` 前缀、
   表名是否带库前缀）——决定签名正则；用户图二状态栏已给出样本，仍需代码侧确认（`failureMessage` 取
   `cause.message` 首行）。
2. `SqlResultPanel.showErrorWithActions`（T-DialectGateUx 建的横幅出口）能否复用承载「切换数据源」动作
   （该出口原本用于方言闸，需确认动作槽可配）。
3. 指示行控件化（JComboBox）在参数区/工具栏的布局约束——既有教训：`BoxLayout(Y_AXIS)` 里裸放
   `JComboBox` 会横跨整屏（`pitfall-combobox-stretches-in-boxlayout`，已立守卫），须 FlowLayout + 定宽。
4. 社区版路径：`prepareDefaultConsole` 静默 + 新工具栏控件隐藏的组合是否会让参数区高度塌陷/布局跳动。
5. 「切换数据源后自动重试」在 EXPLAIN 结构化通道（开关开）与直送通道（开关关）两条链上都要生效，
   且不能与 W7 的 `rerendered` 重提交叠加成两次执行（防重复执行）。

## 六、边界与不做

- 不改取源逻辑（W8~W12 已收口）：本任务只改**失败呈现**与**数据源可见性**。
- 不做「自动猜正确数据源并静默切换」——换源是用户决策（错源执行代价高），只给引导。
- 不改控制台本身的工具栏（那是平台 UI）；只做「默认让控制台/指示行出现」与「失败时不遮不弹」。
- 不在社区版引入任何 Database 相关硬依赖（插件不可用必须零报错）。

## 七、任务清单与写集

| 项 | 内容 | 写集 |
|---|---|---|
| W1 | `ExplainFailureKind.WRONG_TARGET` + 签名表 + 就地引导（轻通知双动作 + 面板横幅） | `execute/ExplainPanelChannel.kt`、`ui/SqlPreviewPanelExecution.kt` |
| W2 | 指示行升级为数据源下拉工具栏（插件可用）/ 纯文本降级（不可用） | `ui/SqlPreviewPanelParamPanel.kt`、`ui/SqlPreviewPanelResultView.kt` |
| W3 | 切换数据源 → 自动重试当前动作（一次，防循环） | `ui/SqlPreviewPanelExecution.kt` |
| test | 签名表 + 两守卫 + 反向 | `execute/ExplainFailureClassifierTest.kt`、`convention/ExplainAskPasswordChannelGuardTest.kt` |
| doc | 索引登记 | `docs/索引/开发计划.md` |

---

## 八、实施记录（2026-10-24，W1~W3 全部落地，`020bfc39`）

**W1（就地引导）**：`ExplainFailureKind` 新增 `WRONG_TARGET`；签名表
`EXPLAIN_WRONG_TARGET_SIGNATURES`（doesn't exist / does not exist / unknown table /
invalid object name / ora-00942 / no such table）+ `isWrongTargetText`。分型顺序
AUTH > WRONG_TARGET > CANCELLED > OTHER（认证误判代价更高；`Unknown column` 明确不算——
换源解决不了列级问题，已在测试里钉死）。两处出口（结构化 `onFailed` + 直送 `Result.Failed`）
改走 `showExplainWrongTarget`：结果区就地横幅（`showErrorWithActions` 双动作槽）+
「切换数据源」主动作 +「在控制台打开」次动作；**不弹模态、不回退直送**。

**W2（工具栏默认展示）**：`buildBindStateRow` 分支化——`ExecuteSqlSupport.isAvailable()` 为真时
渲染「数据源：」+ 数据源下拉（`BIND_STATE_COMBO_NAME` 锚点，在籍源清单，当前项=绑定/记忆/
未绑定）；为假时**逐字保持**原纯文本灰字形态（社区版零打扰）。`refreshBindStateRow` 同步分支
（工具栏形态只更新选中项，降级形态更新文本）。布局按仓内定例 FlowLayout + 定宽 180（防
`BoxLayout(Y_AXIS)` 横跨整屏的既有坑）。下拉切换走 `prepareConsole(forceChoose=true)`。

**W3（换源后自动重试）**：`switchDataSourceAndRetryExplain` 复用 T-UI030 既有
`awaitDataSourceBoundThenRetry`（同 generation 快照、`allowDataSourcePrompt=false` 防循环）。

**读数**：定向 5 类 **45/0/0/0**（Guard 14 + Classifier 12 + Fallback 6 + Timeout 3 +
PreviewSideQuery 10，XML 判例数已核）。反向三条各恰 1 红：W1 直送分支改回模态、W2 摘掉
可用性判定、W3 `forceChoose` 改 false；还原后全绿，探针残留 0。

**诚实边界（未验项）**：
- **GUI 未走查**（用户已明确沙盒自行启动）：横幅视觉、下拉在顶栏的实际布局、换源后自动重试的
  真实时序，均需沙盒/真机确认。本轮的绿只覆盖分型逻辑与源码形接线。
- W2 的下拉**只在插件可用时**出现；`isAvailable()` 在社区版的判定本仓已有既有用例，但
  「指示行降级后参数区高度是否跳动」未验（计划 §五 P-4 仍开放）。
- 计划 §五 P-5（结构化与直送两通道重试叠加成两次执行）未实测——W3 复用的
  `awaitDataSourceBoundThenRetry` 内部 `submitExplainOrStructured(allowDataSourcePrompt=false)`
  未传 `rerendered=true`，理论上闸门重渲染仍可能再触发一次重提交；沙盒复验时若观察到
  「一次点击执行两次」须补该位。

---

## 九、W4 追加（2026-10-24，用户实测「为什么我需要选两次数据源」，`29f3e4b2`）

**现象**：W2 工具栏上线后，在预览的数据源下拉里选一次，平台又弹原生「选择数据源」让选第二次。

**根因**：W2 的下拉切换调 `prepareConsole(forceChoose=true)`——而 `forceChoose` 的语义恰是
「跳过静默、强制弹平台选择器」（T-DialectGateExit 定义，用于「点了切换就等于要弹窗」的场景）。
用户在自己的下拉里已经显式选定，反被要求再选一次。

**修法**：`chooseAndPrepare`/`prepareConsole` 增 `preferredRunnerName`——在 runner 枚举里**按名
唯一命中就直接建链**（记忆 + `runRunner`），不再弹选择器；名字未命中/同名歧义回落既有弹窗（不猜）。
只跳过「选择」一步，方言闸在 `sessionConsumer` 内照旧执行。

**分工（对应用户「我希望选的优先是 idea 原生的」）**：
- 预览下拉 = 快速通道，一次选择直接绑定；
- 结果区横幅「切换数据源」按钮 = 仍走平台原生选择器（`forceChoose=true` 保留，W3 自动重试不变）。

**读数**：定向 5 类 **46/0/0/0**（Guard 15 含新例「数据源下拉选定后不得再弹平台选择器」）；
反向把下拉改回 `forceChoose=true` 恰 1 红，还原后全绿，探针残留 0。

**诚实边界**：下拉选定后「按名直选」依赖 runner 枚举里存在该名（方言匹配集里没有该源时仍会
回落弹窗）——这属平台行为，未在 GUI 验证；沙盒复验时应确认「选一次即绑定」在 jar demo 场景成立。
