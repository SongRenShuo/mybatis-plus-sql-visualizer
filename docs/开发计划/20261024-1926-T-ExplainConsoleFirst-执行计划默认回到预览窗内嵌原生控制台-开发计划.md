# T-ExplainConsoleFirst：执行计划默认回到「预览窗内嵌原生控制台」并根治只读动作取词漂移

> **创建日期**：2026-10-24 19:26
> **触发**：用户提问「如果这个执行计划直接在 sql 预览窗口里执行，是不是就没有那么多问题了，并且右上角还有蓝色的返回预览按钮，你分析下这个的可行性，也是初版执行计划的实现方式」
> **前置**：`T-DialectInferPollution` / `T-ExplainFailUx` / `T-ExplainSwitchNative`（2026-10-24，合计 12 轮换源修复，已合入 `3117f6db`）

---

## 一、背景与现状判定

### 1.1 三条执行链并存（代码实证）

| 形态 | 入口函数 | 执行者 | 取源/密码/方言/报错归属 | 开关 |
|---|---|---|---|---|
| **A 初版**：内嵌原生控制台 + 蓝色「返回预览」 | `submitExplainDirectConsole`（`SqlPreviewPanelExecution.kt:1136`） | `SqlExecutor.execute` → 平台控制台 | **全部由平台负责** | 仅剩「在控制台打开」入口可达 |
| **B 当前默认**：旁路 JDBC + 只读对话框 | `submitExplainDirect`（`:1166`） | `PreviewSideQuery` → `McpDbExecutor` | 插件自己负责 | 默认 |
| **C 结构化**：计划表格/JSON 树 | `ExplainPanelChannel.executeStructured` | `McpDbExecutor` | 插件自己负责 | `explainStructuredRender`，默认 `false`（`PluginSettings.kt:550`） |

A 的代码一行未删：`SqlExecutor.execute(..., onConsole = { embedConsole(it, EXECUTE) })`；`embedConsole` 在 `EXECUTE` 模式下自动挂 `executeViewHeader()`（`:2035`），该头部即「当前显示执行结果；返回预览可继续编辑原 SQL」+ `primaryButton(panel.backToPreview)`。

### 1.2 为什么十二轮都在打 B / C

B / C 是插件自建 JDBC 通道，因此「用哪条源、密码从哪来、方言对不对、失败怎么呈现」全部落在插件身上。而平台「选中即关联」（`DbImplUtil.getForcedDataSourceForExec` 命中即 `singletonList` 短路枚举，且无公开清除 API）使插件侧的选源列表第二次起恒为单源——这是 W1~W12 全部换源拉锯的根因（详见 `docs/经验总结/20261024-1914-执行计划取源换源十二轮拉锯与平台关联副作用-经验总结.md`）。

A 不需要自建这些：换源走平台原生 `Data Sources` 菜单（Detach + 全部源），密码走平台凭据链，方言与报错都在控制台原生呈现。

### 1.3 B 被废弃的真实原因（必须同时偿还的技术债）

`1a1cf724`（2026-10-19）commit message 原文：

> 预估影响行数/执行计划此前走共享控制台，**会把预览 SQL 顶掉，并让 `currentEditorSql()` 漂移到试算/EXPLAIN 语句**；执行 SQL 后也缺少明确的返回预览入口。

即 A 唯一的真问题是：执行时 EXPLAIN 语句被写入 `mpsql-exec.sql` 的 document，而 `nativeEditorDoc` 指向它，于是 `currentEditorSql()` 从「预览 SQL」漂移成「EXPLAIN ...」。所有只读动作都读这个函数，在 A 的执行视图里点它们会拿到 EXPLAIN 语句。

**本任务的承重墙就是偿还这笔债**——不修它，A 上线即回归。

## 二、方案

### 2.1 分派改形

- `submitExplainDirect`（默认与全部 fail-closed 回退）→ **内嵌原生控制台（形态 A）**。
- `submitExplainDirectConsole` 保留为 A 的单一实现，`submitExplainDirect` 收敛为「前置零源判定 + 转调」。
- 形态 C（结构化）继续留在 `explainStructuredRender` 设置项后（默认关，用户显式开启才走）——不动。
- 形态 B 收窄：只服务「预估影响行数」（`estimateDmlImpact`）与回滚 SQL 对话框（`RollbackSqlDialog`），不再承接 EXPLAIN。

### 2.2 取词漂移根治（W2，本任务的实质工作量）

新增 `previewSqlForReadonly()`：**执行视图在场时读预览快照**，否则读真实编辑器。

```kotlin
internal fun SqlPreviewPanel.previewSqlForReadonly(): String? =
    if (executeViewHeader != null) lastRenderedSql?.takeIf { it.isNotBlank() } ?: currentEditorSql()
    else currentEditorSql()
```

判据依据：`lastRenderedSql` 由 `showSql` 在每次预览渲染后记录（`SqlPreviewPanelSqlView.kt:713/734/747`），而控制台执行链写 `mpsql-exec.sql` **不经过 `showSql`**，故它恰好保存着「最后一次预览的 SQL」。同款判据已在 `SqlPreviewPanelExecution.kt:657`（`currentText == lastRenderedSql`）与 `:813` 使用。

改造的只读动作（5 处，全部读 `currentEditorSql()`）：

| 动作 | 位置 |
|---|---|
| 回滚 SQL | `SqlPreviewPanelExecution.kt:43` |
| 导出回放脚本 | `:162` |
| 复制为 AI 上下文 | `:290` |
| 导出可移植 SQL | `:86` |
| 预估影响行数 | `:323` |

**`executeSql` / `explainSql` 不改**——它们必须执行编辑器里的真实文本（含用户手改）。

### 2.3 明确不做

- 不自建换源 UI（W12 已定案，经验文档第一节）。
- 不删形态 C（结构化计划是差异化能力，且默认关）。
- 不为 A 补「未回填 `?`」前置拦截（`ExplainGate` 既有前置校验保留；回填核对交给数据库如实报错，错误文本比「未绑定数据源」这类误归因清楚）。

## 三、工作项

### W1 分派改形（`ui/SqlPreviewPanelExecution.kt`）

1. `submitExplainDirect` 函数体改为：Schema 校验 → **前置零源判定** → `submitExplainDirectConsole(...)`。
   - 前置只判「是否一条源都没有」（`DataSourceSelector.registeredDataSourceCount(project) == 0`）→ `showExplainErrorDialog(noDataSource, openDatabase = true)`；**不做任何选源**（不在插件侧重演 W1~W12）。
   - 保留既有 `explainTargetForTest` 测试缝语义：非 null = 有可用源，直落控制台。
   - 删除对 `PreviewSideQuery.run` 的 EXPLAIN 调用与随之而来的 `Result.Table/Blocked/Scalar` 分型分支。
2. `showExplainRawResultDialog` 改为 `@Deprecated` 保留（C 通道仍可能用到表格形态），或确认零引用后删除——实施时以 grep 结果为准。

### W2 取词根治（`ui/SqlPreviewPanelExecution.kt`）

1. 新增 `previewSqlForReadonly()`（见 2.2）。
2. 五个只读动作改调用它。
3. `lastRenderedSql` 加注释说明「执行视图下它是预览快照的唯一来源」。

### W3 测试契约反转

1. `ui/ExplainSideQueryPanelTest.kt`：整类原断言「EXPLAIN 不得换入控制台 / 经对话框展示」是本次**主动反转**的契约。
   - 改为新契约：改用 `SqlExecutor.executionAttemptForTest` 截获执行入口，断言 `submitExplainDirect` 把 `EXPLAIN ...` 交给了执行链（即控制台路径），且未弹结果对话框。
   - 保留仍成立的两条：对话框取消按钮跟随插件语言、零源时按钮直达 Database（`showExplainErrorDialog` 未变）。
2. 新增 `ui/PreviewReadonlySnapshotTest.kt`：
   - 执行视图在场（`executeViewHeader != null`）时 `previewSqlForReadonly()` 返回 `lastRenderedSql`，即使 `nativeEditorDoc` 已是 EXPLAIN 文本；
   - 非执行视图时返回编辑器真实文本（含手改）。

### W4 守卫

`convention/ExplainPanelWiringGuardTest.kt`：

- 既有「`submitExplainDirect` 至少两处复用 + 保留 `SqlExecutor.execute`」继续成立（A 就是 `SqlExecutor.execute`）。
- 新增断言：`submitExplainDirect` 不得再调 `PreviewSideQuery.run`（防形态 B 悄悄回流）。
- 新增断言：五个只读动作读 `previewSqlForReadonly()`，不得直接读 `currentEditorSql()`（反向：把任一动作改回 `currentEditorSql()` 必红）。

### W5 文档回填

本文件 `## 实施记录` + `docs/索引/开发计划.md` 状态列。

## 四、待验证前提（先证伪再动码）

| # | 前提 | 证伪方式 |
|---|---|---|
| P-1 | `lastRenderedSql` 在「执行计划」按下前后确实是预览文本（不被执行链改写） | 读 `showSql` 三处赋值 + 确认 `SqlExecutor` 链路不调 `showSql`（已初查：写 document 在 `DataSourceSelector`，不经 `showSql`） |
| P-2 | `executeViewHeader` 在执行视图期间恒非 null，且「返回预览」后归 null | 读 `embedConsole`/`backToPreview`/`detachConsole` 三处（已初查：`EXECUTE` 挂载设值、`backToPreview` 与 `detachConsole` 清空） |
| P-3 | 零源判定在 headless 测试工程里稳定为「零源」，不会让既有 `InjectionGateTest`/`UnknownParamGateTest` 的「无数据源引导」断言失效 | 跑测试；若 headless 有隐式源，则改为 `explainTargetForTest ?: boundConsoleDataSource()` 判空（保持既有语义） |
| P-4 | `SqlExecutor.execute` 在 Community/无 Database 插件下走既有 `showInfo(execute.noDatabase)`，不需要新提示 | 读 `SqlExecutor.execute` callback 分支（已核实存在） |

## 五、验收判据

1. `submitExplainDirect` 的执行体交给 `SqlExecutor.execute`（源码守卫 + 定向用例双证）。
2. `previewSqlForReadonly()` 在执行视图下返回预览快照（用例 + 反向）。
3. 定向测试全绿；三个反向探针各恰 1 红（探针必须**真删符号**，禁用注释/`false &&`）。
4. 提交态 `compileKotlin` + `compileTestKotlin` 通过（防「工作树绿、提交态红」，见经验文档第七条）。

---

## 六、实施记录

**2026-10-24 已实施，提交 `HEAD`（未推送）。**

### W1 分派改形（实际落地）

`submitExplainDirect` 现在只有三段：Schema 校验 → 零源判定 → `submitExplainDirectConsole`。

零源判定用 `DataSourceSelector.registeredDataSourceCount(project) > 0`（该函数此前已存在但零调用，本轮才接上线）；只要有一条在籍源就交给平台，**插件不枚举、不筛选、不猜**。
删除了整段 `PreviewSideQuery.run` 调用与 `Result.Table/Blocked/Scalar` 四分支分型。

顺带退役：`showExplainRawResultDialog`（`ExplainPlanDialog.kt:77`）已零引用，删除并清理四个随之无用的 import（`McpExplainFormatter`/`JBTable`/`JTable`/`DefaultTableModel`），原位置留注释记录去向。

### W2 取词根治（实际落地）

`previewSqlForReadonly()` 落在 `SqlPreviewPanelExecution.kt:585`，判据 `executeViewHeader != null`。
五处只读动作全部改用它：`showRollbackSql` / `exportReplayScript` / `copyAsAiContext` / `exportPortableSql` / `estimateDmlImpact`。
`executeSql` / `explainSql` **未改**（执行链必须执行编辑器真实文本）。

### W3 测试契约反转

- `ExplainSideQueryPanelTest` 整类重写：从「不得换入控制台」反转为「必须交给执行链」，用 `SqlExecutor.executionAttemptForTest` 截获执行入口断言 `EXPLAIN ...` 原样下发，并断言不弹结果对话框。用例数 6 → 3（旧用例中依赖旁路结果分型的 4 条随形态 B 一并退役；对话框语言绑定与零源引导两条保留）。
- 新增 `PreviewReadonlySnapshotTest`（4 例）：执行视图返回快照且**同时对照** `currentEditorSql` 已漂移、返回预览后退回编辑器文本、无执行视图时用户手改优先、快照缺失时兜底编辑器。

### W4 守卫

`ExplainPanelWiringGuardTest` 新增两条：`W2_只读动作取词必须走预览快照`（五动作逐个断言用快照且不裸读 `currentEditorSql`；另断言 `executeSql` 仍读真实编辑器）、`W1_执行计划直送不得回流旁路查询`（`submitExplainDirect` 必须转调控制台且不得出现 `PreviewSideQuery.run`）。

旧守卫按新契约修订（不是删掉）：
- `ExplainAskPasswordChannelGuardTest.直送链取源必须接模块学习回落` → 改写为 `直送链不得再做插件侧选源`（断言 body 内无 `explainFallbackDataSource`、有 `submitExplainDirectConsole`、零源判定在场）。
- `认证失败不得弹红字模态框` / `选错源失败不得弹红字模态框`：分支条数断言 `>= 2` → `>= 1`（直送链那处随形态 B 退役，呈现交给平台控制台），**呈现纪律不变**。

### 读数

- 定向 10 类 **124 / 0 / 0 / 0**（`ExplainAskPasswordChannelGuardTest` 15、`ExplainPanelWiringGuardTest` 15、`ExplainSideQueryPanelTest` 3、`PreviewReadonlySnapshotTest` 4、`InjectionGateTest` 18、`UnknownParamGateTest` 6、`ExplainStructuredSideQueryPanelTest` 1、`DmlImpactSideQueryPanelTest` 9、`RollbackSqlDialogTest` 38、`SqlPreviewSchemaGateTest` 15）。
- 反向探针三组，**各恰 1 红**（均真删/真改符号，非注释、非 `false &&`）：
  1. 把 `showRollbackSql` 取词改回 `currentEditorSql()` → `W2_只读动作取词必须走预览快照` 红；
  2. 在 `submitExplainDirect` 内插入真实 `PreviewSideQuery.run(...)` 调用 → `W1_执行计划直送不得回流旁路查询` 红（首版探针写成编译不过的形态，那次不算数，重写为可编译形态后才取到读数）；
  3. 把 `previewSqlForReadonly()` 本体改成直返 `currentEditorSql()` → `testExecuteViewReturnsPreviewSnapshotNotExecDocument` 红。
- 主/测试源编译均通过（提交态复核）。

### 未做与遗留

- **沙盒 GUI 未走查**：本轮是通道改形，真机需确认「点执行计划 → 内嵌控制台出现 EXPLAIN 结果 + 顶部蓝色返回预览」。按经验文档第九条，沙盒合成输入对 `JTextField` 不可靠，此步留给用户真机（或下次沙盒会话用日志打点 `[M36] embedConsole embedMode=EXECUTE` 自证）。
- **形态 C（结构化）未动**：仍由 `explainStructuredRender` 开关控制，默认关。若用户后续想看计划表格/索引建议，开该开关即走 `ExplainPlanDialog`，与本轮改动不冲突（`submitExplainOrStructured` 的 Structured 分支未改）。
- **`showExplainWrongTarget` / `notifyExplainAuthFailure` / `notifyExplainCancelled` 仍在**：现仅由结构化通道调用，未成死代码。
- 全量 `testShards` 与 `verifyPlugin` 未跑（本改动不动 `build.gradle.kts` / `plugin.xml` / 依赖版本，按三档分层不触发构建校验；全量留收口阶段）。

---

## 七、追加：W6 结构化通道「默认关」定案与推荐通知一次性（同日，用户报障触发）

### 7.1 用户报障

实施 W1~W5 后用户实测：**「为什么我点击执行计划还是弹窗了？」**

### 7.2 归因（配置实证，非推测）

查沙盒持久化配置：

```
.intellijPlatform/sandbox/.../config/options/mp-sql-visualizer.xml
  <component name="MpSqlVisualizerSettings">
    <option name="explainStructuredRender" value="true" />
```

而代码默认值是 `false`（`PluginSettings.kt:550`）。差异只可能来自一处：`Decision.DirectSilent` 分支里那条「启用结构化并重试」推荐通知——**它每次点执行计划都出现**（开关关着就满足推荐条件），用户顺手点过一次之后 `enableStructuredExplainAndRetry` 把开关**永久**写成 true，而界面上再没有任何「现在是实验形态」的提示。于是下次点执行计划看到 `ExplainPlanDialog` 弹窗时，用户完全无从归因，只会认为「改了半天还是在弹窗」。

W1~W5 改的是 `Decision.DirectSilent` / `DirectFallback` 那条链（`submitExplainDirect` → 内嵌控制台），**结构化分支按 §2.3「明确不做」原样保留**——用户此前的点击恰好走的是结构化分支，所以改动对他不生效。这不是实现缺陷，是 §2.3 的边界没有对用户说清。

### 7.3 产品决定（用户拍板）

**结构化通道保留，默认关。** 不退役、不删代码、不改默认值（本来就是 false）。

### 7.4 W6 实施

- 新增 `PluginSettings.State.explainStructuredOfferedShown`（默认 `false`，持久化，循 `onboardingAnchorTipShown` 先例）：推荐通知**只提示一次**，之后全程静默。该标记只关掉「主动推荐」这一条噪音源，**不阻断显式入口**——用户想开仍可去设置页勾选。
- 通知接线处加一次性判定并置位（`SqlPreviewPanelExecution.kt:1441-1443`）。
- 新增 `ExplainStructuredDefaultOffGuardTest` 三条守卫：
  1. `explainStructuredRender` 声明默认值必须是 `false`（产品决定固化，防被改回）；
  2. 一次性标记必须**真置位**（防「读了个恒 false 的字段」的假一次性——只加判定不加置位，通知照样每次都弹）；
  3. 设置页 `explainStructuredCheck` 与 bundle 键必须仍在（**「默认关」≠「拆掉通道」**，防清理时误删）。

### 7.5 读数与遗留

- 定向 10 类 **83 / 0 / 0 / 0**。
- 反向探针两组，各恰 1 红：①把默认值改回 `true` → `结构化渲染默认必须关闭` 红；②删掉一次性标记的实际置位（保留条件读取，即伪一次性）→ `启用结构化推荐通知必须只提示一次` 红。
- **构建坑（记录）**：新增 `State` 字段后增量编译留下陈旧 class，`config` 包 10 个用例报 `NoSuchMethodError: State.copy$default(...)`。`clean` 后全绿——是构建缓存问题不是产品缺陷。同类改动后若见 `copy$default` / `copy` 签名类 `NoSuchMethodError`，直接 `clean`，别去查数据类定义。
- **用户当前沙盒状态未修**：沙盒 IDE 当时在运行（PID 37808），此时改配置文件会在退出时被回写覆盖，故未直接改盘。需用户在设置页（`设置 → 工具 → MyBatis-Plus SQL Visualizer → SQL 预览` 组）取消勾选「预览面板内结构化渲染 EXPLAIN」后 Apply，或在关闭 IDE 后改该 xml。
- 沙盒 GUI 未走查（同 §六）。

---

## 八、追加：W7 `Unknown column` 跨源升格（同日，用户报障触发）

### 8.1 用户报障

> 数据源选错了这个报错信息是正常情况吗
> `[42S22][1054] Unknown column 'first_name' in 'field list'`
> `[当前库=mp_demo，数据源=mp_demo@localhost，数据源默认库=mp_demo]`

### 8.2 归因：报错正常，但「选错源」这个判断此前插件给不出来

三段拼装来源已核实：
- `[42S22][1054] Unknown column ...` = MySQL 驱动原文（连接通、权限够、库切对了才会走到列级）；
- `[当前库=…]` = 插件诊断后缀（`ExecConsoleContext.describeContext`，`DataSourceSelectorResults.kt:1886` 调用）。

**三处读数全是 `mp_demo`，说明源没选错**——错的是「SQL 与库」这一对：`first_name` 属
`jpa_demo` 库的 `UserEntity`（`jpa-hibernate-demo/src/main/java/cn/song/jpademo/entity/UserEntity.java`），
而 `mp_demo` 的 `user` 表列是 `username`。

既有 `classifyExplainFailure` 把 `Unknown column` **明确排除**在 WRONG_TARGET 外
（`ExplainPanelChannel.kt:209`：「列级问题，同一张表在，换源解决不了」）。这个排除对
「列名拼错 / 前缀写错」成立，但对**同名表不同列集**漏判——那是源选错，换源正是解。

### 8.3 W7 实施：按证据升格，不一刀切

新增 `CrossSourceColumnEvidence`（纯函数，零 IDE 依赖／零连接／零内省，EDT 可直调）。
三条**同时**成立才升格：

1. 错误文本能解析出具体列名（`Unknown column '<col>'`，剥别名前缀取末段）；
2. 当前源的同名表**确证没有**该列（缓存未热 = 无从证明 → 不升格，诚实降级）；
3. **存在另一条在籍源**，其同名表**带这一列**（换个源真能解决）。

第 3 条是关键：它把「拼错列名」（任何源都没有 → 不升格，照旧只报错）与「源选错」
（另一源确实有 → 提示换源）分开。**宁可漏判不可误判**——误判会把拼错列名的用户引导去
换源，比不给引导更迷惑。

接线：`extractConsoleError` 在既有上下文行之后追加一句提示
（`execute.error.crossSourceColumn`，中英双语已加）。**纯加法**：判据不成立时输出与改前
逐字一致。控制台数据源经反射 `getDataSource` 取（该文件 Community 下也加载，不得引 DBN 类）。
`DatabaseSchemaResolver` 补 `allDataSourcesForEvidence`（只读枚举）与 `columnNameSourcesCached`
（跨源连读加同一把锁，防「读源 A 与读源 B 之间缓存被清」的不一致视图）。

### 8.4 读数与遗留

- 定向 5 类 **43 / 0 / 0 / 0**（`CrossSourceColumnEvidenceTest` 9、`ExplainFailureClassifierTest` 12、
  两个既有守卫类 18、`PreviewReadonlySnapshotTest` 4）。
- 反向探针：删掉第 3 条必要条件 → 恰 **2 红**，其中「列名拼错任何源都没有_不升格」正是本次
  要防的误判形态（证明护栏有效，不是空绿）。
- **遗留**：`refreshResultsComponent` 没有 `project` 参数，该调用点不参与升格（另两处
  ——每拍早检、40 拍超时兜底——生效）。沙盒 GUI 未走查。

---

## 九、追加：W8 exec 预热（沙盒验收实测触发的 EDT 慢操作修复）

### 9.1 症状

沙盒 243 验收时点「执行计划」，日志刷出 **9 条**
`SEVERE - #c.i.u.SlowOperations ... Plugin to blame: SQL Visualizer`，栈为：

```
explainSql → submitExplainDirectConsole → SqlExecutor.execute → chooseAndPrepare
  → createTempPsiFile → PsiManagerImpl.findFile
```

即 `createTempPsiFile` 的**冷路径**（VFS refresh + `PsiManager.findFile`）在 EDT 上跑。

### 9.2 归因：本轮改动暴露的既有缺陷

`1a1cf724`（2026-10-19）之后 EXPLAIN 默认走旁路查询（`PreviewSideQuery`），不经过控制台链，
所以这条冷路径一直不可达；本轮 W1 把 EXPLAIN 切回内嵌控制台后它重新进入热路径。
既有预热（`prewarmExecTempFile`）只服务「预览 SQL」，且仅在方言重渲染路径触发，
EXPLAIN 文本从未被预热过。

### 9.3 W8 修法（含一次自我修正）

**首版**：池线程 `submit` 预热 + EDT 上 `future.get(8s)` 等待。沙盒预热带 1s 侥幸通过，
但存在两个问题：

1. **EDT 阻塞**——慢盘/索引期会把 UI 冻住 8s；
2. **双层嵌套等待**——池线程里调 `prewarmExecTempFile`，而它内部又 submit 一个池线程
   再 `get(30s)`，形成「池线程 A 等池线程 B」。

**现版**：
- `DataSourceSelector` 新增 `prewarmExecTempFileOnBackgroundThread`——调用方已在 BGT 时用，
  **同步跑完、不自带预算等待**（EDT 调用仍拒绝）。既避免跨包访问文件级扩展函数，
  也消除嵌套等待。
- `submitExplainDirectConsole` 改为「池线程预热 → `invokeLater` 续接执行链」的**异步形态**，
  EDT 上一个 `get` 都没有。预热失败照常续接（预热只是优化，不是前置条件）。
- 删除随之失效的 `EXPLAIN_EXEC_PREWARM_BUDGET_MS` 死常量。

### 9.4 读数

沙盒 243 第二次会话（jar 23:20，含 W8-fix）：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| `SlowOperations` SEVERE | 9 条 | **0 条** |
| `Plugin to blame: SQL Visualizer` | 9 条 | **0 条** |
| 日志佐证 | — | `[M36] exec prewarm DONE (cache filled)` |

执行链路同时验证完整：选源弹出 5 个源 → 建会话 → `attachConsole` → `embedConsole`
→ 提交 → 出结果（`result/layout OK within 8s`）。

### 9.5 守卫

`ExplainPanelWiringGuardTest.W8_执行计划进链前必须预热exec文件` 改写：
原「预热文本位置必须在 `SqlExecutor.execute` 之前」在异步形态下**不成立**
（execute 在 `continueChain` 闭包里、源码位置反而在前）。改钉真正机制：

- 预热调用必须在 `executeOnPooledThread` 块内；
- `continueChain` 必须排在预热调用之后（这才是「先预热、后执行」的真实保证）；
- 函数内不得出现 `Future.get` + `TimeUnit`（EDT 阻塞回归红线）；
- 不得调用外层 `prewarmExecTempFile`（外层自带 submit+get，会形成嵌套等待）。

反向探针：改回「EDT 阻塞等待」形态 → **恰 1 红**。

### 9.6 测试契约随实现调整

`ExplainSideQueryPanelTest.testExplainDirectGoesToEmbeddedNativeConsole` 增加有界等待
（轮询 + `dispatchAllInvocationEvents`，最多 10s）——异步形态下 `submitExplainDirect`
返回时执行链尚未跑，原「返回后立即读」的时序只对首版同步实现成立。这是契约随实现的
正当调整，不是掩盖问题。




