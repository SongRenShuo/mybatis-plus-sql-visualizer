# T-ExplainSwitchNative：切换数据源改走 IDEA 原生 Data Sources 弹窗，撤掉自研下拉（开发计划·修订）

**创建时间**：2026-10-24 16:10
**触发源**：用户沙盒实测 T-ExplainFailUx（W1~W4）后报三问题，均为「换源」这条链的呈现/生效偏差。
**基线**：`master@423c2c44`
**状态**：仅计划（本篇未改任何生产代码）
**编号**：不占 O/R/D/V（承接 T-ExplainFailUx 的换源链收尾，替代其 W2 的自研 UI 决策）
**关系**：**修订** T-ExplainFailUx W2/W3 的设计取舍——原 W2 自研「数据源下拉」被用户否决，
改为「优先用 IDEA 原生选择器」。

---

## 一、三个问题与证据

### 问题一：点「切换数据源」没弹列表，反而直接重试执行了

图一（`mp_demo@localhost` 在下拉里、横幅在）→ 用户点横幅「切换数据源」→ 没有出现图三那种
原生 Data Sources 列表，而是直接又跑了一次执行计划（`mp_demo` 再次失败）。

根因（代码级）：`switchDataSourceAndRetryExplain`（`ui/SqlPreviewPanelExecution.kt`）调用
`prepareConsole(forceChoose = true)`。而 **`forceChoose` 的语义是「跳过 last-used 静默」**，
不是「弹窗」——弹窗与否由 runner 枚举数量决定（`runners.size == 1 && shouldBeChosenIfAlone`
直接跑、多 runner 才 `showRunnerPopup`）。在用户环境里 mp_demo 是唯一方言匹配源 ⇒ **直选执行**，
用户看到的「直接重试」正是这条分支。同时横幅文案承诺「切换后自动重试」进一步误导。

⇒ **「切换数据源」必须走显式选择器**，不能依赖 runner 枚举的偶然数量。

### 问题二：自研下拉「切换」不生效，真正执行仍用原生工具栏的源

图二：下拉显示 `jpa_demo@localhost`，但内嵌控制台工具栏的数据源仍是 `mp_demo@localhost`，
错误栏显示 `数据源=mp_demo@localhost`——**下拉只是显示，没改到执行目标**。

根因：W2 的下拉切换调 `prepareConsole(preferredRunnerName=...)`，它只**新建/替换共享控制台**；
而面板右侧已嵌入的那个原生控制台（图二工具栏）是**平台自己的 ConsoleView**，其数据源由
平台 attach 时确定、后续不再跟随插件的准备链。于是「插件记了新源、平台控制台还用旧源」两套状态。

⇒ 用户诉求正确：**执行目标必须由原生控制台自己说了算**，插件不应维护第二套源状态。

### 问题三：理想形态是 IDEA 原生 Data Sources 弹窗（图三）

图三：`Data Sources` 弹窗（`Detach Data Source` + 数据源列表，带平台数据源图标）——
这是平台 `ChooseDataSourceAction`（`com.intellij.database.run.actions.ChooseDataSourceAction`，
`ActionGroup`，243 反编译实证存在）在**原生 SQL 控制台上下文**里弹出的。

⇒ 修法方向：**不自研任何源选择 UI**，把「切换数据源」委托给平台；插件只保留「提示 + 触发」。

---

## 二、修法（推荐设计）

### W5：撤销自研下拉，改为「只读指示 + 触发原生选择器」

- **删** W2 的 `buildDataSourceCombo` 与 `bindStateCombo` 字段/常量（自研 UI 整体退役）；
- **留** 指示行纯文本（`bindStateText()` 三级文案，插件不可用时同款降级——W2 的降级语义保留）；
- **加** 指示行右侧一个小按钮/链接「切换数据源」（或复用横幅动作），点击调平台
  `ChooseDataSourceAction`（构造 DataContext 后 `actionPerformed`），**弹图三那种原生列表**；
- 平台不可用（社区版）→ 不显示该按钮（沿用 W2 降级纪律：零报错零弹窗）。

> 平台调用方式（待验证前提 P-1）：`ChooseDataSourceAction` 需要 `FileActionContext`/`PsiFile`
> （`getClientPsiFile`），且 `contextMenuInvokedOnResultSet` 等分支暗示它期望「在控制台上下文中」。
> 若无法在预览面板上下文构造出可用 DataContext，则退化为**方案 B**：直接调平台原生
> `Runner` 选择弹窗（我们已有 `showRunnerPopup`，它就是图二那种「选择数据源」列表，
> 只是标题文案与图标由平台 Runner 提供）——用户明确要「IDEA 原生的」，`showRunnerPopup`
> 用的是平台 `PersistenceConsoleProvider.Runner` 对象 + 平台 ListPopup，视觉上与图三同族。

### W6：「切换数据源」一律弹选择器，且不自动重试

- 横幅「切换数据源」动作：改为**强制弹选择器**（不经 `runners.size==1` 直选分支）——
  实现上新增 `forceChoosePopup: Boolean` 或直接调 `showRunnerPopup(runners)`；
- **取消「切换后自动重试」**（用户实测「点切换却重试执行了」即此承诺误导）：
  切换成功只**更新绑定并刷新指示行**，执行由用户自己再点「执行计划」；
- 文案同步改：横幅说明改为「该表不在当前数据源中。可切换数据源后重试。」（去掉「自动重试」）。

### W7：执行目标单一事实源 = 原生控制台

- 插件侧不再保存「用户选的源」作为执行依据：EXPLAIN 取源仍以
  `boundConsoleDataSource()`（= 原生控制台实际 attach 的源）为第一优先；
- W4 的 `preferredRunnerName` **保留**但语义收窄为「准备控制台时的选择提示」，不再被当作
  「执行目标」的第二事实源；
- 用户在下拉/选择器里换源后，必须**重建控制台**（`prepareConsole` 建新 session），
  让原生工具栏的数据源同步变化——图二的错位（下拉 jpa / 工具栏 mp_demo）即此不一致的后果。

---

## 三、验证方案

1. **守卫（源码形）**：
   - 预览面板不得再有自研数据源下拉（`buildDataSourceCombo`/`BIND_STATE_COMBO_NAME` 全仓零命中）；
   - 横幅「切换数据源」动作必须走弹窗路径（不得命中 `runners.size == 1` 直选分支）；
   - 社区版降级分支仍在（`ExecuteSqlSupport.isAvailable()` 判据保留）。
2. **纯函数/定向**：`bindStateText()` 三级文案不变；`preferredRunnerName` 直选分支仅用于
   「准备控制台」路径（有测试缝）。
3. **反向验证**：把横幅动作改回 `prepareConsole(forceChoose=true)` → 守卫应红；
   把自研下拉加回 → 守卫应红。
4. **沙盒/真机（用户）**：点横幅「切换数据源」→ **弹原生 Data Sources 列表**（图三形态）→
   选 jpa_demo → 原生控制台工具栏数据源随之变成 jpa_demo → 再点执行计划 → 出计划。

## 四、待验证前提（实施者先证伪）

1. **P-1（承重墙）**：`ChooseDataSourceAction` 能否在「预览面板」上下文（无原生控制台 DataContext）
   被程序化调起并弹出图三列表。若不能 → 走方案 B（`showRunnerPopup`，平台 Runner + 平台 ListPopup）。
2. **P-2**：换源后原生控制台工具栏的数据源是否随 `prepareConsole` 重建而更新（图二错位的
   根治前提）；若平台控制台不复用新 session 则需 detach + 重建。
3. **P-3**：`forceChoosePopup` 的既有消费方（T-DialectGateExit「切换数据源」按钮、
   快照查询切换）是否都期望「必弹」——避免本次改动把那些路径也变成必弹（可能是有害的）。
4. **P-4**：取消「自动重试」后，用户需要多点一次；确认这符合用户预期（用户原话是「正常应该
   弹数据源列表」，未要求自动重试）。

## 五、边界与不做

- **不自研任何源选择 UI**（本轮核心决策）：一律用平台 `ActionGroup`/`Runner` 弹窗。
- 不改取源推断逻辑（W8~W12 已收口）；本任务只改「切换入口 + 事实源 + 重试语义」。
- 社区版（无 Database 插件）零报错、零弹窗、指示行纯文本降级（W2 纪律沿用）。
- 不引入「插件侧记住用户选择并静默复用」的第二事实源（问题二的根因）。

## 六、任务清单与写集

| 项 | 内容 | 写集 |
|---|---|---|
| W5 | 删自研下拉（字段/常量/构建/刷新分支）；指示行保留纯文本 + 触发原生选择器入口 | `ui/SqlPreviewPanelParamPanel.kt`、`ui/SqlPreviewPanel.kt` |
| W6 | 横幅「切换数据源」改必弹选择器；取消自动重试；文案改 | `ui/SqlPreviewPanelExecution.kt`、`execute/DataSourceSelectorResults.kt`、bundle |
| W7 | 执行目标单一事实源＝原生控制台（换源后重建控制台，工具栏同步） | `execute/DataSourceSelector*.kt` |
| test | 三条守卫 + 反向 | `convention/ExplainAskPasswordChannelGuardTest.kt` |
| doc | 修订 T-ExplainFailUx W2/W3 决策记录 | 本文件 + 原计划 §九 |

---

## 七、实施记录（2026-10-24，W5~W7 全部落地，`54f7d784`）

**P-1 结论（方案 A 可行）**：`ChooseDataSourceAction` 反编译显示其 `getContext` 需要
`PsiFile` + `VirtualFile`，且 `contextMenuInvokedInsideSelection/OnResultSet` 为真时直接返回
null。我们的预览编辑器有 PsiFile（exec 文件），故**不必**构造 DataContext 硬调 ActionGroup；
改用**方案 B**——`prepareConsole(forceChoosePopup=true)` 触发我们既有的
`showRunnerPopup`（平台 `PersistenceConsoleProvider.Runner` 对象 + 平台 `ListPopup`），
视觉与图三同族且无需平台内部 DataContext 依赖。这比硬调 ActionGroup 更稳（不赌平台内部契约）。

**W5**：自研下拉整体退役——`buildDataSourceCombo`/`bindStateCombo`/`BIND_STATE_COMBO_NAME`
全删；指示行回到纯文本三级文案 + 「切换数据源」链接入口（`BIND_STATE_SWITCH_NAME`，
`JBUI.CurrentTheme.Link.Foreground`）；社区版 `ExecuteSqlSupport.isAvailable()` 判据保留，
按钮不渲染（零报错零弹窗）。

**W6**：换源统一 `switchDataSourceViaNativeChooser`（横幅动作与指示行链接共用）——
`prepareConsole(forceChoose=true, forceChoosePopup=true)`；`chooseAndPrepare` 的
`shouldBeChosenIfAlone` 直选分支加 `!forceChoosePopup` 抑制（问题一根治）。
**取消自动重试**（问题一另一半）：`switchDataSourceAndRetryExplain` 整体删除，
`awaitDataSourceBoundThenRetry` 调用不再出现；文案去掉「切换后自动重试」。

**W7**：换源前 `dropSharedConsole() + clearPrepared()`——避免 `reuseSharedConsole` 命中旧源
控制台（其 attach 源不跟随新选择），使「原生控制台工具栏数据源」成为唯一事实源（问题二根治）。

**读数**：定向 5 类 **44/0/0/0**（Guard 13 + Classifier 12 + Fallback 6 + Timeout 3 +
PreviewSideQuery 10）。反向：真删 `!forceChoosePopup` 与 `dropSharedConsole/clearPrepared`
→ 守卫「换源必须弹原生选择器且不自动重试」恰 1 红；还原后全绿，探针残留 0。

**🆕探针教训（入档）**：首轮把探针写成注释留在原位（`// REVERSE-PROBE ...`），源码形守卫
仍能匹配到关键字 → **假绿**；必须**真删符号**才见红（与 W5 那条「false && 骗不过源码形守卫」
同族，本轮再犯一次）。

**诚实边界（未验）**：
- GUI 未走查（用户自行启动沙盒）：`showRunnerPopup` 弹层与图三的实际视觉差异、
  「换源后原生控制台工具栏数据源是否真的跟着变」（W7 只保证丢弃旧控制台，平台重建行为未实测）、
  社区版按钮不渲染的布局。
- 计划 §五 P-3（`forceChoosePopup` 既有消费方是否都期望必弹）未逐一复核：本轮只在
  「切换数据源」入口置 true，`executeSql`/快照查询等既有路径保持原语义。

---

## 八、W8~W10 追加（2026-10-24 第二轮用户实测，`7e88ade4`）

| 项 | 用户现象 | 根因（实证） | 修法 |
|---|---|---|---|
| W10 | 「为什么 sql 无法执行」：红条「当前 SQL 按 **未知** 渲染，所选数据源是 MySQL」 | `executionDialectGateRequest` 传 `result?.effectiveDialect?.name`；JPA/日志还原等载体 `renderedDialect` 未赋值 → null → 闸门判 `RENDER_UNKNOWN_BLOCK` | 请求侧按渲染器既有口径回落 `MySqlDialect.name`（`Dialect.kt:908-909` `byName(null)` 即回落 MySQL，实际渲染方言从不是「未知」） |
| W9 | 「点击下方的切换数据源依然有问题，点击没有反应」 | 红条按钮接 `executeSql(forceChoose=true)`——**执行链**，会先过同一方言闸门再被同一原因阻断 | 改走 `switchDataSourceViaNativeChooser`（只换源、不执行） |
| W8 | 「点击切换数据源只有一个数据源？当前可是配置了很多」 | 平台 attach 版枚举 → `getAppropriateDataSourcesForExec` → `.filter(CAN_CONNECT_TO)`，滤掉当前不可连接的源（日志 09:53 `runners=5` 而现在 1 个，体现可连接性波动） | 用户主动切换改调平台切换专用入口 `getSwitchDataSourceRunners`（`allDataSources=forceChoosePopup`） |

**W8 的诚实边界（重要）**：平台**未公开**「按指定源建 Runner」的 API——`runInDataSource` 是
直接建会话、不产 Runner；`createDataSourceRunner` 是私有。因此「不可连接的源」**仍然不会**
出现在弹层里，插件无法凭空造出合法平台 Runner。已加 `registeredDataSourceCount` 归因缝
（在籍源计数），供后续 UI 在「可选源 < 在籍源」时给出可行动说明（打开 Database 工具窗口连一次
即可恢复可选）。若用户要求「必须列出全部源」，需要另立任务研究平台私有 API 的可行性/风险。

**读数**：定向 5 类 **47/0/0/0**（Guard 16 + Classifier 12 + Fallback 6 + Timeout 3 +
PreviewSideQuery 10）。反向三条真删符号各恰 1 红（渲染回落 / 红条入口 / 切换枚举），还原后
全绿，探针残留 0。

**未验**：GUI 未走查（用户自行启动沙盒）——W10 是否真的让执行放行、W9 红条按钮是否真的弹出
列表、W8 弹层里源数量是否随「连过一次」而增加，均需沙盒/真机确认。

---

## 九、W8-hotfix（2026-10-24 第三轮用户实测，`8b4f7568`）

**用户现象**：点「切换数据源」直接报错——红条「没有与该 SQL 方言（MySQL）匹配的已配置数据源」，
而 Database 视图里 7 个源都在。**这是 W8（`7e88ade4`）引入的回归**。

**根因（javap 实证）**：`getSwitchDataSourceRunners` 真实签名是
`(PsiFile, String, DatabaseSession, Consumer)` **四参**，比 attach 版多一个「当前会话」。
W8 按**三参**反射调用 → `NoSuchMethodException` → 被 `runCatching` 吞成空列表 → `runners=0`
→ 走空枚举分支的既有归因文案（「有在册数据源 = 方言不匹配」）→ 真正的反射失败被伪装成
「方言不匹配」，红条因此完全误导。

**修法**：
1. 准备链枚举**退回 attach 版**——这正是此前多个版本一直正常、能弹平台原生选择列表的形态；
   切换专用入口只在「已有 attach 会话」时才有意义，本链不持有会话。
2. 枚举实现保留 switch 版但按**真实四参签名**调用（`Class.forName(SESSION_CLASS)` 作第 3 参
   类型），且 `switched.ifEmpty { attach() }`——任何失败/空结果回退 attach，**绝不再返回空**。
   这是防同类回归的结构性约束。

**教训（已入 commit）**：反射改入口必须先 javap 核**签名**（不止方法名）——家规「方法名以 jar
反编译为唯一权威」本轮只核了「方法存在」，漏了参数个数；而 `runCatching` 把反射异常吞成空列表，
使错误以「方言不匹配」的假象出现，掩盖真因。

**读数**：定向 5 类 **47/0/0/0**；守卫改为「切换枚举必须回退 attach 且不得返回空」。

**回归说明**：本 hotfix 后，「切换数据源」恢复到此前的行为——弹平台原生 Runner 列表
（受 `CAN_CONNECT_TO` 过滤，可连接源才在列）。W8 想要的「列出全部在籍源」**未实现**
（平台无公开 API，见 §八 诚实边界），用户若仍需要，须另立任务研究私有 API 风险。

---

## 十、W11（2026-10-24 第四轮用户实测，`c2e77950`）：「第二次点击只剩一个源」根治

**用户现象**（沙盒日志精确复现）：
```
16:34:10  runners=5 → show popup with 5 runners   ← 第一次：5 个 ✓
16:34:14  popup selected: mp_demo@localhost       ← 选中
16:34:17  runners=1 → show popup with 1 runners   ← 第二次起永远 1 个 ✗
```

**根因（双重实证）**：
- 日志：选中后枚举数从 5 掉到 1 并保持。
- 字节码（`DatabaseRunners.getDataSourceRunners` 偏移 34/63）：首跳
  `DbImplUtil.getForcedDataSourceForExec(psiFile)`，命中即 `Collections.singletonList(该源)`
  **短路返回**，不进正常枚举。平台把 exec 文件「关联」到选中源后即进入该形态；而清除关联
  无公开 API（`VirtualFileDataSourceProvider` 仅 `findDataSource`/`getDataSource`，
  `getForcedDataSource` 私有）。

**修法**：切换路径（`forceChoosePopup=true`）在 attach 枚举之上**合并全部在籍源 Runner**——
用公开的 `DatabaseRunners.createDataSourceConsolesRunner(DbDataSource, Consumer)` 逐源建
（`DbPsiFacade.findDataSource(uniqueId)` 取 `DbDataSource`），`mergeRunnersByName` 按显示名
去重（平台结果优先）。普通预览准备/执行不启用，保持既有方言匹配语义。

**读数**：定向 5 类 **47/0/0/0**；反向真删合并调用恰 1 红。

**未验**：GUI——第二次及以后点击是否真列出全部在籍源；选中「当前不可连接」源时平台失败路径
的表现。均需沙盒确认。

**累积教训（本任务三轮）**：①反射换入口必须先 javap 核**完整签名**（W8 漏参数个数致
`runners=0` 假「方言不匹配」）；②`runCatching` 吞反射异常会把「实现失败」伪装成「业务空结果」，
关键路径上必须区分「空」与「失败」；③平台「选中即关联」类隐式状态会让第二次行为与第一次不同，
排障必须拿**连续多次操作**的日志而非单次。
