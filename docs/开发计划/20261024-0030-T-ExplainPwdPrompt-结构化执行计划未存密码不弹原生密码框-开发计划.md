# T-ExplainPwdPrompt：结构化执行计划在数据源未存密码时不弹原生密码框（开发计划）

**创建时间**：2026-10-24 00:30
**触发源**：用户沙盒验收 T-PreviewChainNoise（`9809a5b7`）时实测截图——预览面板点「执行计划」，
弹出的「执行计划」对话框里红字 `Access denied for user 'root'@'localhost' (using password: NO)`，
**没有**弹出 IDE 原生的输入密码框（原生控制台链在这种场景会弹）。
**基线**：`master@f9e1650f`（分析时 tip，已含 T-PreviewChainNoise）
**状态**：已实施（`4bccd331`，2026-10-24；实施记录见 §九）
**编号**：未占 O/R/D/V（缺陷级修复）
**门禁**：定向测试 + 反向验证 + 沙盒手工验收；全量 `testShards` 按用户既有口径默认不跑

---

## 一、现象与证据

截图（沙盒）：标题「执行计划」的对话框（即 `ExplainPlanDialog`，T-PreviewSideQuery P2 引入的
EXPLAIN 失败呈现面），正文红字 MySQL 认证失败 `(using password: NO)`，底部仅「在控制台打开」
与「关闭」两个出口。用户预期：应先弹 IDE 原生密码输入框（与原生控制台连接时的行为一致）。

触发链（全部实码核对）：

```
工具栏「执行计划」→ explainSql → submitExplainOrStructured（SqlPreviewPanelExecution.kt:1329）
  → ExplainPanelChannel.executeStructured(:1358)          ← 结构化通道
    → McpDbExecutor.executeQuery（池线程）
      → executeQueryInternal(:281)  setAskPassword(false)  ← 关键点
      → createBlocking → MySQL 驱动：密码未存 → Access denied (using password: NO)
  → onFailed → showExplainErrorDialog（ExplainPlanDialog.kt:98-130，红字 + 在控制台打开）
```

影响面（`McpDbExecutor.executeQuery` 的全部调用点，grep 已核）：

| 调用点 | 通道 | 期望行为 |
|---|---|---|
| `ExplainPanelChannel.executeStructured`（`execute/ExplainPanelChannel.kt:144`） | 面板「执行计划」结构化渲染 | **应弹密码框**（本次缺陷） |
| `PreviewSideQuery`（`ui/PreviewSideQuery.kt:102`） | 预估行数 / EXPLAIN 表格旁路（T-PreviewSideQuery P1/P2） | **应弹密码框**（同病） |
| `SqlVisualizerExplainTool`（`mcp/SqlVisualizerExplainTool.kt:152`） | MCP 工具 `mpsv_explain` | **必须保持不弹**（MCP 零弹窗红线，fail-closed） |

不受影响：回滚快照查询走 `executeAdhocSql` 管线（原生控制台形态，自带密码询问），不直连
（`RollbackSnapshotStore` 仅注释提及 createBlocking 协议，不建连）。

## 二、根因

### 2.1 直接原因：共用通道显式关掉了密码询问，UI 链被连坐

`McpDbExecutor.executeQueryInternal`（`mcp/McpDbExecutor.kt:281-282`）无条件：

```kotlin
Class.forName(BUILDER).getMethod("setAskPassword", Boolean::class.javaPrimitiveType)
    .invoke(builder, false)
```

这是 2026-09-15 T-McpToolset 一期（`49b75752`）为 MCP 通道立的「零弹窗红线」（KDoc 明写
「零弹窗：密码未保存直接失败（fail closed）」）。此后 R33/T-PreviewSideQuery 把面板 EXPLAIN
与预估行数切到这条通道时**继承了这个 false**，没有按「UI 上下文应允许询问」重新表态。
git -S 实证：`setAskPassword` 全仓只有 2026-09-15 两笔 MCP 提交引入——**不是 `9809a5b7`
（T-PreviewChainNoise）的回归**，那笔只包了 `createBlocking` 的取消上下文。

### 2.2 平台事实：默认语义本来就是「缺密码就问」

243 DatabaseTools jar 反编译（javap，本机 corretto-21，`DatabaseConnectionManager$Builder`）：

```
public final ...Builder setAskPassword(boolean);
public final boolean getAskPassword();
// 构造器字节码：32: iconst_1; 33: putfield askPassword  →  默认值 = true
```

即平台 Builder 默认 `askPassword=true`，原生控制台链的「缺密码弹框」正是这个默认在起作用；
我们的 `false` 是显式覆盖。询问/重试逻辑在 `DatabaseConnectionEstablisher.Companion`（suspend
形态，含 `handleCredentialsFailure` / `resolvePasswordIssues` 凭据失败重试链，javap 实证）。

### 2.3 为什么现在才暴露

沙盒是干净环境，数据源凭据未保存；日常真机的数据源都存了密码，`createBlocking` 拿得到凭据
根本不走到询问分支。缺陷自 T-PreviewSideQuery 起就潜伏，本次验收首次踩中。

### 2.4 现状对这类失败零分型

`ConnectionErrorClassifier.EXCLUSION_SIGNATURES`（`execute/ConnectionErrorClassifier.kt:41`）
**明确把 "access denied" / "password" 排除出连接类错误**（那是为 DDL 已生效性探测设计的口径，
合理），但也意味着上层没有任何「认证失败 → 引导补密码」的专门处置——失败只有「红字 + 在
控制台打开」一个间接逃生门（控制台链会弹框，但用户要多跳一步且不知道原因）。

---

## 三、修法（推荐设计）

### W1：`askPassword` 按通道表态，且**必填**（编译器即守卫）

`McpDbExecutor.executeQuery` / `executeQueryInternal` 增加 `askPassword: Boolean` 参数，
**不设默认值**，反射透传 `setAskPassword(askPassword)`：

```kotlin
fun executeQuery(
    project: Project, dataSource: Any, sql: String, maxRows: Int,
    timeoutSeconds: Int = 0,
    askPassword: Boolean,                 // 必填：每个调用点必须显式表态
    onExecuted: ((ExecutedOutcome) -> Unit)? = null,
    channelNote: String? = null,
): McpExplainFormatter.RawResult
```

三处调用点各自写明：`ExplainPanelChannel` 与 `PreviewSideQuery` 传 `true`（UI 上下文，缺密码
交平台原生询问——askPassword 只在「未存密码」时问，已存密码零变化，正反两侧都安全）；
`SqlVisualizerExplainTool` 传 `false`（MCP 零弹窗红线，fail-closed 语义原样保留）。

必填而非默认 false 的理由：默认 false 会让未来新增的 UI 调用点「静默不弹」（重演本缺陷），
默认 true 则会击穿 MCP 红线——两个默认都是坑，让编译器强迫每个新调用点做选择，比源码扫描
守卫更硬。KDoc 写明两通道语义与本次缺陷编号。

**备选（不推荐默认采用）**：`DatabaseCredentials.loadPassword(connectionPoint)` 预判「密码未存」
再决定是否弹——多一次凭据库访问（时序/安全面待验，见前提 4），且「已存但密码错」同样需要
询问能力，预判路线覆盖不全。仅当 W1 主路线在沙盒被证伪（前提 1）时启用。

### W2：取消与失败分型，补第二出口

`askPassword=true` 后用户在密码框按 Esc 的异常形态需要接住（具体异常类型见前提 2）：取消
应得「已取消，未执行」的普通文案而非红字堆栈；真正的认证失败维持现有红字，但利用
`showExplainErrorDialog` 已有的 `openDatabase` 参数（`ExplainPlanDialog.kt:100-103`，为
「无数据源」场景建的「打开 Database 工具窗口」出口）扩一档——认证失败文案尾附「或在
Database 工具窗口中打开该数据源补存密码」，`DataSourceSelector.openDatabaseToolWindow` 现成
可复用。PreviewSideQuery 的失败呈现（预估行数内联区）同批对齐文案。

### W3：MCP 红线回归守卫

反向护栏：`SqlVisualizerExplainTool` 的调用点若被改成 `askPassword = true`（或未来重构把它
并进 UI 通道），MCP 工具会突然弹模态框——这是能力级红线。守卫两条：
① 源码扫描守卫（仿 `BannedApiScanRuleTest` 体例）：`SqlVisualizerExplainTool.kt` 内
`McpDbExecutor.executeQuery(` 调用行必须含 `askPassword = false`，`ExplainPanelChannel.kt` 与
`PreviewSideQuery.kt` 内必须含 `askPassword = true`；② 若采纳必填参数，编译期已挡住「漏传」，
守卫只防「显式传错」。

---

## 四、验证方案

1. **定向测试**：
   - 参数透传：`executeQueryInternal` 的反射调用难以 mock，改测「调用点表态」——守卫测试
     （W3 ①）做源码级对账；另补一个纯函数级用例（若抽 `internal` 决策函数则直测）。
   - 既有回归：`PreviewSideQueryIntegrationTest`、`ExplainPanelChannelTest`、
     `McpDbSignatureCrossVersionTest`（Builder 签名面）随改动复跑，读数填实施记录。
2. **反向验证**：①守卫里删掉任一 `askPassword = true` → 守卫红；②把 `SqlVisualizerExplainTool`
   改成 true → MCP 红线守卫红；③（若做 W2 分型）注入取消形态 → 断言文案走「已取消」分支。
3. **沙盒手工验收（用户侧，主验收）**：
   - 数据源不勾「保存密码」→ 预览 → 点「执行计划」→ **原生密码框弹出** → 输入后出结构化计划；
   - 密码框按 Esc → 得体文案（无红字堆栈），可重试；
   - 点「预估行数」（PreviewSideQuery 通道）同样先弹密码框；
   - MCP 客户端调 `mpsv_explain`（密码未存）→ 仍 fail-closed 返回错误文本，**不弹框**（红线）；
   - 已存密码的数据源全链回归：行为与改前逐字节一致（不弹框）。

## 五、待验证前提（实施者先证伪再动手）

1. **`setAskPassword(true)` + `createBlocking` 在我们的调用形态下真能弹框**：平台控制台自身在
   BGT 建连并弹模态框，但我们的调用无 `ConsoleRunConfiguration`、无 `setRequestor`、跑在
   `executeOnPooledThread` + `runProcess(EmptyProgressIndicator)` 里（T-PreviewChainNoise W2
   的包装）——询问链经 `invokeAndWait`-类机制应可弹，但**必须沙盒实测**；若不弹，W1 改为
   「识别认证失败 → 主动走第二出口」（W2 的 openDatabase 引导升为主路径）。
2. **用户取消密码框的异常形态**：PCE / CancellationException / SQLException（「authentication
   canceled」类）——决定 W2 分型写法；若实测是 PCE 家族，注意与既有超时护栏
   （`ExecutionTimeoutGuard.isTimeoutLike`）的分型互斥。
3. **「已存但密码错误」（using password: YES）形态**：`askPassword` 只在「未存」时问，还是
   失败后 `handleCredentialsFailure` 重试链会再问一次？实测确认；后者则错误文案分型要区分
   NO/YES 两种。
4. 备选路线的 `DatabaseCredentials.loadPassword` 是否可在 BGT 直调、会不会触发凭据库解锁 UI——
   仅当 W1 主路线证伪后才需要验证。
5. `PreviewSideQuery` 弹框时的前台焦点与父窗口（从预览面板触发的模态框归属）——沙盒走查确认
   不被面板挡住、可正常输入。

## 六、边界与不做

- **不动 MCP 通道的 fail-closed 语义**：`SqlVisualizerExplainTool` 保持 `askPassword=false`，
  零弹窗红线原样（MCP 客户端是程序性调用，弹框即挂死）。
- 不做「插件自绘密码输入框」——平台原生框（含「保存密码」勾选）是唯一正确形态，自绘要重复
  凭据存储/安全面，收益为零。
- 不改 `ConnectionErrorClassifier` 的排除口径——它的职责是 DDL 已生效性探测，把 access
  denied 归为连接类会引发无意义重试探测；认证失败的分型在 UI 呈现层做（W2），不进分类器。
- 不动 `executeAdhocSql`/控制台链（本就正常）。

## 七、任务清单与写集

| 项 | 内容 | 写集 |
|---|---|---|
| W1 | `executeQuery`/`executeQueryInternal` 增必填 `askPassword` 并透传；KDoc 记录缺陷与两通道语义 | `mcp/McpDbExecutor.kt` |
| W1-c | 三调用点显式表态（true/true/false） | `execute/ExplainPanelChannel.kt`、`ui/PreviewSideQuery.kt`、`mcp/SqlVisualizerExplainTool.kt` |
| W2 | 密码框取消分型 + 认证失败第二出口（openDatabase）文案 | `ui/ExplainPlanDialog.kt`、`ui/SqlPreviewPanelExecution.kt`（+PreviewSideQuery 呈现面） |
| W3 | 通道表态源码守卫 | `convention/`（新守卫或并入既有扫描守卫测试） |
| doc | 索引登记 | `docs/索引/开发计划.md` |

写集互相独立；W1/W1-c 是编译期一体改动（必填参数强制三处同批表态），W2 依赖前提 2 的实测
结论，W3 独立。

## 八、风险

1. **前提 1 是本计划的承重墙**：若平台在无 requestor 的裸建连形态下不弹框，W1 的价值降级为
   「失败更快更干净」，主路径改走 W2 的引导出口——两案都消掉「红字不知所措」的现状，但体验
   目标（原生密码框）只有前者能达到。**先做沙盒冒烟再动码**（半小时内可证伪）。
2. W1 必填参数是 API 破坏面：仓内调用点已穷举（3 处），但 `structuredRunnerForTest` 等 seam 的
   函数签名对齐要同批改，防漏编。
3. 弹框出现在池线程 + `runProcess` 包装内，若与既有超时护栏（driver `setQueryTimeout`）或
   MCP 取消语义叠加，取消传播路径需在 W2 一并对账。

---

## 九、实施记录（2026-10-24，已实施）

**生产改动**（提交 `4bccd331`，9 文件 +296/−6）：

1. **W1**（`mcp/McpDbExecutor.kt`）：`executeQuery`/`executeQueryInternal` 增必填
   `askPassword: Boolean`（无默认值）透传 `setAskPassword`；KDoc 记录两通道语义与缺陷编号。
2. **W1-c**：三调用点显式表态——`ExplainPanelChannel.executeStructured` 与 `PreviewSideQuery`
   传 `askPassword = true`，`SqlVisualizerExplainTool` 传 `false`（MCP 零弹窗红线原样）。
3. **W2**（`execute/ExplainPanelChannel.kt` 顶层 + `ui/SqlPreviewPanelExecution.kt` +
   `ui/PreviewSideQuery.kt` + 双语 bundle 键）：
   - 分型纯函数 `classifyExplainFailure`（AUTH/CANCELLED/OTHER）+ `isAuthFailureText`
     （五签名：access denied / password authentication failed / invalid username/password /
     ora-01017 / login failed for user）；
   - 结构化 onFailed：AUTH 就地呈现红字 + 补存密码指引（**不再回退直送**——同通道必同样失败）；
     CANCELLED 轻通知（`notifyExplainCancelled`，单测模式不弹，notifySchemaIssues 同款守卫）；
     OTHER 维持既有回退；
   - 直送链 `Result.Failed` 同款三分型出口；`PreviewSideQuery.failureMessage` 对认证失败
     附同一指引（预估行数内联区与对话框同文案）；
   - 取消词根用 `"cancell"`（覆盖 cancelled/cancellation——首跑实证只写 `"canceled"`
     漏平台 `CancellationException`）；超时消息（常含 cancelled）排除在取消外。
4. **W3**：`convention/ExplainAskPasswordChannelGuardTest` 通道表态源码守卫
   （`BannedApiScanSupport` 剥注释快照上对账三文件）。

**测试**：新增 `ExplainFailureClassifierTest`（8 例，零平台引用＝testFast 快桶成员；bundle 键
在场性走文件读取不触发平台 ResourceBundle）+ `ExplainAskPasswordChannelGuardTest`（3 例）。

**读数**：

| 项 | 读数 |
| --- | --- |
| 定向 3 类（XML 判例数已核） | **21/0/0/0**（Classifier 8 + Guard 3 + 既有 PreviewSideQueryTest 10） |
| 反向①（ExplainPanelChannel 表态改 false） | **恰 1 红**（`面板执行计划结构化通道必须允许询问密码`） |
| 反向②（SqlVisualizerExplainTool 改 true） | **恰 1 红**（`MCP工具通道必须保持零弹窗`） |
| 反向③（摘 "access denied" 签名） | **恰 1 红**（`mysqlAccessDeniedIsAuth`） |
| 还原后复跑 | 3 类全绿（21/0/0/0），探针残留 grep = 0 |

**诚实边界**：

- **前提 1（承重墙）未证**：`setAskPassword(true)` 在本插件的裸建连形态（无 requestor /
  ConsoleRunConfiguration，跑在 `executeOnPooledThread` + `withDbBlockingContext` 包装内）
  下平台是否真弹原生密码框，**留沙盒手工验收**（§四.3）。若证伪，W1 的价值降级为「分型
  指引更快更干净」，主路径转 W2 的 openDatabase 引导（代码两案都已就位，只需把 AUTH 分支
  的指引换成 openDatabase 出口）。
- 前提 2（取消异常形态）未实测——当前取消分型按**失败文本**判定（PCE/CancellationException
  的类名即含 cancell），异常驱动形态实测后如需精确分型再收紧。
- 前提 3（「已存但密码错」是否二次弹框）未实测，随沙盒验收一并观察。
- `PreviewSideQueryIntegrationTest`（DB 桶）与 `McpDbSignatureCrossVersionTest`（外部桶）
  未随本轮复跑——本批不触其覆盖面（反射签名面零变化，仅调用点参数），全量按用户口径不跑。

---

## 十、沙盒验收记录（2026-10-24，GUI 未闭环——环境阻塞，非产品缺陷）

**环境**：master@9a0c1ba2 独立沙盒 MPSV-ExplainPwd-UI3（243.28141.41 + robot-server 8580 + demo 工程）；
在役包核验通过（javap 沙盒内插件 jar：`executeQuery(...,boolean,...)` 含 askPassword、
`ExplainPanelChannelKt.classifyExplainFailure/isAuthFailureText` 在役，jar mtime 晚于 4bccd331）。

**GUI 走查达到的最深点**：预览面板「执行计划」→ 通知动作「启用结构化并重试」点击生效（开关启用 +
重试链运行，日志与红字证实）→ 绑定链 `chooseAndPrepare` 走到 **runner 枚举 = 0**（`registered=7`，
方言路由无匹配——数据源从未连接/学习过方言）→ 绑定失败，**结构化通道（McpDbExecutor）未被执行到**，
askPassword 行为无法在此形态下被检验。

**阻塞点（均为沙盒环境装配，非产品缺陷）**：
1. 沙盒 MySQL 驱动未生效：拷入 `config/jdbc-drivers/MySQL ConnectorJ` + 重启后，展开/Refresh
   数据源仍不触发连接（日志零驱动活动）；Data Sources 对话框显示「Download missing driver files」。
2. 方言路由空：数据源无 Dialects 映射且从未连接（runner=0），绑定链必败于方言闸红字
   （「没有与该 SQL 方言（MySQL）匹配的已配置数据源」——O-145 既有闸门行为）。
3. 探针损耗若干：前台锁（Chrome 抢前台，Alt+AttachThreadInput 解）、信任框、通知动作点击容差、
   Go to File 列表索引期重排（Enter 三次开错文件——改鼠标点击路线）。

**状态**：V1（承重墙）/V2/V3/V4 **未验**；V5（MCP 零弹窗）已由守卫测试静态面收口
（ExplainAskPasswordChannelGuardTest 3 例）。产品侧零缺陷登记。

**闭环路径（真机一次点击）**：用户日常 IDE（驱动/数据源/方言齐备）装含 4bccd331 的构建后——
①数据源不存密码 → 预览 → 执行计划 → **原生密码框**（V1）；②Esc → 轻通知（V2）；③输错密码 →
红字+补存密码指引（V3）；④预估行数 → 弹框（V4）。或下次沙盒战役先把驱动/方言前置装配好再验。

---

## 十一、用户实测验收记录（2026-10-24，V1 通过 + 两轮修复 W5/W6）

**V1 实测通过**（用户操作）：填参数（#id=1）后点「执行计划」→ askPassword=true 生效，平台
243 凭据链弹原生「Connecting to 'mp_demo@localhost'... Access denied + Change Credentials /
Abort」对话框（**非预弹密码框**——平台对未存密码的形态是「连接失败后给凭据入口」）；
Change Credentials 输入密码后**结构化 EXPLAIN 计划渲染成功**（mp_demo@localhost · 7929ms，
表格含 id/select_type/table/type/possible_keys）。Change Credentials/Abort 即 V3（补密码）
与 V2（取消）的原生出口。

**W5（`1bed57bc`）**：填参数前点击，结构化通道正确拒绝（通知说明「? 占位符未回填」）但随后
回退直送，又弹误导性「未绑定数据源」红字框（用户明明有 last-used 数据源 mp_demo@localhost）。
修＝UNFILLED_PARAMS 不回退直送，通知就地收口。

**W6（`bd9ac4f0`）**：用户点 Abort 后插件又弹认证失败红字模态框（平台把终止以
SQLException: Access denied 冒泡，分型判 AUTH 走了红字）。修＝AUTH 分支（结构化+直送两处）
改 notifyExplainAuthFailure 轻通知——平台凭据对话框已呈现失败与出口，红字框是二次打断。

**守卫两轮迭代教训（入档）**：AUTH 不弹红字框的守卫，v1 indexOf 近邻检查被直送链调用点干扰
（反向探针不红=假绿）；v2 只匹配带花括号分支漏掉直送无花括号形态；v3 按分支起点切片覆盖两
形态，反向恰 1 红。**探针不红的守卫不算验证过。**

**W4 诊断缝**：`lastUsedExplainDataSource` 曾在取源时打 INFO 日志（remembered/roster/resolved
三段），本轮定位「取源成功、断在下一环」全靠它；问题闭环并经用户复验后**已摘除**（用户拍板：
推荐去除即去除——每次执行计划一行属常态噪音，回归风险由 ExplainLastUsedFallbackTest 与通道
守卫兜底，不需要日志；需要时 git 历史可查）。
