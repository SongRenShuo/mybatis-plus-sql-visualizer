# T-ErrorLocateUsability 错误定位按钮可用性判定与 Oracle 位置形态 — 开发计划

- 日期：2026-09-21 23:24
- 任务编号：T-ErrorLocateUsability（第十轮报告 §9.4 **O-49**，**P1**）
- 来源：第十轮报告 §8 DBX 增量清单 **#54**——DBX `0668bdc4`：`apps/desktop/src/lib/sql/errorPosition.ts:111-118` `sqlErrorHasMessagePosition`、`:145-148` `sqlErrorDisplayPosition`（显示与跳转**共用同一解析器**）、`:150-175` `resultSourceRange` 失败即拒绝、`ContentArea.vue:352-360`
- 性质：**可用性 + 诚实性**——修的是「按钮可见但点了没反应」这一静默形态
- 实施分支：自 master 拉任务分支 t-error-locate-usability
- 验证基线：master `28a1efa`（最近可核读数 491 类/5604 用例 0 失败 与 5577 例 0 失败，属不同批次树；实施时以当次实测为准）

---

## 一、背景与依据（现状核实，含四处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「跳不动就不给按钮」 | **未实现，且 KDoc 与实现不符（口径修正 1，本轮真缺陷）**：`ui/SqlResultPanel.kt:249-262` 的 `showErrorRich` 按 `locate != null`（`:252`）决定按钮显隐，KDoc 写「null = 锚点不可解析、按钮不出现」；但**唯一调用方恒传非 null lambda**（`ui/SqlPreviewPanel.kt:1030/1046/1055/1253/1271/1283/1392` 七处均 `onErrorRich = { info -> showErrorRichWithLocate(info) }`），而锚点解析发生在**点击时**（`:1296-1303` `locateErrorSource()` 里 `project ?: return`、`anchor ?: return` 静默返回）→ **按钮可见，点了什么都不发生** | 本任务的 P1 依据；修法极简（1 文件） |
| 2 | 「区分执行态 SQL 与源 SQL」 | **仅以文字承认，未结构化（口径修正 2）**：`ui/SqlPreviewPanel.kt:1290-1292` 的 KDoc 明写「驱动报错位置指向**被执行 SQL 文本**的字符/行，与 mapper 原文行不一一对应（动态 SQL 拼接、参数注入后行号必然错位），v1 只承诺『语句起始行』跳转」；`SqlResultPanel.kt:253-257` 的 `positionChar` **只进 tooltip 文案、不参与行计算**。全仓唯一显式两态分离在别处（`execute/MysqlClientGSuffix.kt:8` 提交文本与历史原文两态分离） | 缺口是「未结构化」而非「未意识到」——落点是把两态变成字段 |
| 3 | 「驱动无结构化位置时从消息文本解析」 | **部分实现**：`execute/ExecutionErrorInfo.kt:35` `ErrorPositionExtractor` 已解析**三种**文本 marker——`:38` PG `Position:\s*(\d+)`、`:41` `at line (\d+)`、`:44` `line:\s*(\d+)`；marker **单一性门控**（`:58-62` 恰好一个才归属）；越界钳制 `:65-68`。**无 Oracle 形态**（无 `ORA-` 数字偏移、无 `error occur at position: N`） | 补 Oracle 形态 |
| 4 | 「钳制已生效」 | **已实现但未接线（口径修正 3）**：四处投递点（`execute/DataSourceSelector.kt:2618`、`:2657`、`:2673`、`mcp/SqlVisualizerExplainTool.kt:155`）**均不传** `sqlMaxLength` → 越界钳制分支**只在单测生效** | 属「接线缺失」，随本任务一并接上 |

### 1.1 现有链（逐环节）

| 环节 | 落点 | 职责 |
|---|---|---|
| 位置提取纯函数 | `execute/ExecutionErrorInfo.kt:35` | `ErrorPositionExtractor`（三 marker + 单一性门控 + 越界钳制） |
| 富信息组装 | 同文件 `:84-116` | SQLState / errorCode（cause 链 ≤5 + nextException 链 ≤3） |
| 投递点（3 处 catch） | `execute/DataSourceSelector.kt:2618`/`:2657`/`:2673` | native 链 / executeQueries / onFailure |
| 穿参链 | `execute/SqlExecutor.kt:40`、`:95-98` → `DataSourceSelector.kt` 多处 | 默认 null |
| UI 按钮 | `ui/SqlResultPanel.kt:77-86`（创建）、`:249-262`（`showErrorRich`） | 仅错误态出现 |
| 锚点登记 | `actions/ConvertToSqlAction.kt:68-71` → `ui/SqlPreviewService.kt:106`/`:109` | 项目服务单槽 |
| MCP 侧 | `mcp/SqlVisualizerExplainTool.kt:155-161` | `errorPosition: char/line N` 附消息 |
| **对照组（同仓已做对）** | `log/SqlLogPanel.kt:1779-1782` | `e.presentation.isEnabledAndVisible = resolved != null`——**先解析、后显隐**；配 `log/EntryJumpResolver.kt:26-30` 的 `JumpTarget` 四态（Precise/NoSource/NotFound/MissingSource） |

**机制在同仓已存在**（`SqlLogPanel` 侧），错误定位侧漏用——这是本任务最省力的地方：照抄既有形态即可。

### 1.2 既有测试

`execute/ErrorPositionExtractorTest.kt`（**11 例**：PG Position 提取 / MySQL at line / line: 形态 / 多 marker 放弃归属 / 无 marker 返回 null / 越界钳制 / SQLState 与错误码 / cause 链穿透 / nextException 链 / 富信息位置字段 / 非 SQL 异常零 SQLState 不虚报）、`execute/ConnectionErrorClassifierTest.kt`、`log/EntryJumpResolverTest.kt`、`log/SqlLogPanelXmlJumpTest.kt`、`log/SqlLogPanelLiveJumpWithSourceTest.kt`、`log/HistorySqlLogPanelJumpToSourceTest.kt`。**缺口**：错误定位按钮的显隐/可用性**无 UI 测试**（`SqlResultPanel` 无对应测试类）。

## 二、目标与非目标

**目标**：① 跳转入口**先解析后显隐**——解析不出锚点就不显示按钮（对齐同仓 `SqlLogPanel` 的既有形态）；② 补 Oracle 的位置形态解析；③ 把 `sqlMaxLength` 钳制接上生产；④ 如实标注「报错位置指向执行态 SQL」。

**非目标**：不做执行态→源 SQL 的**位置换算**（动态 SQL 拼接 + 参数注入下行号必然错位，换算不可靠；现有「语句起始行」承诺维持，属诚实降级）；不改日志面板侧的跳转（那条链已做对）；不新增弹窗形态（沿用既有按钮与 tooltip）。

## 三、方案

**T-ELU-01（先解析后显隐，最小改动）**：`ui/SqlPreviewPanel.kt:1306-1307` 改为**先解析锚点、解析不出传 `null`**——`showErrorRich` 的 `locate` 参数本就支持 null（`SqlResultPanel.kt:252` 已按 null 判定），**UI 侧无需改动**。对齐 `SqlLogPanel.kt:1779-1782` 的形态。

**T-ELU-02（补 Oracle 位置形态）**：`execute/ExecutionErrorInfo.kt` 的 marker 集合增补 Oracle 形态。**先红后绿**：用真 Oracle 连接取真实报错文本再定 marker（见 §五-1），**不得凭 `ORA-` 前缀臆造**（Oracle 的错误文本里 `position: N` 出现在「table or view does not exist」这类错误里，形态与 PG 的 `Position:` 不同）。

**T-ELU-03（接线 `sqlMaxLength`）**：四处投递点（`DataSourceSelector.kt:2618`/`:2657`/`:2673`、`SqlVisualizerExplainTool.kt:155`）接上提交文本长度——使越界钳制从「仅单测生效」变为生产生效。

**T-ELU-04（两态如实标注）**：`ExecutionErrorInfo` 增字段（如 `executedSqlLength`）或显式标注来源为执行态文本，供 tooltip 如实说明「位置对应被执行的 SQL，非 mapper 原文行」。**不引入换算**。

**T-ELU-05（测试与文档）**：① 新增 `SqlResultPanel` 的显隐用例（锚点可解析 → 按钮可见；不可解析 → 不可见）——**这是本任务的核心判据**，当前该类无测试；② Oracle marker 用例（先红后绿）；③ 钳制接线用例；④ 既有 11 例全绿。文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第十轮报告 §8 #54 落地备注。

## 四、验收标准

1. **锚点不可解析时按钮不出现**（用例 + 真机各一）——改前「可见但点了没反应」，改后「不可见」。
2. Oracle 位置形态可解析（用真库文本钉住）；既有三种 marker 行为不变。
3. `sqlMaxLength` 钳制在生产路径生效（用例断言越界被钳）。
4. tooltip/文案如实标注「位置指向执行态 SQL」（不宣称能定位到 mapper 原文行）。
5. 既有 11 例 + `EntryJumpResolverTest` + 日志面板三跳转测试全绿；全量 testShards + testDatabase + agent 绿。

## 五、验证清单（可经 DBX MCP 执行）

**Oracle 侧已实测可用**：`Oracle Free 23 / FREEPDB1`（`dbx-test-host:20095`，connection_id `311778f3-…`）。**纪律：共享库只读验证、禁止建删对象。**

| # | 场景 | 判据 |
|---|---|---|
| 1 | 经 Oracle 连接执行一条**必然报错**的语句（如引用不存在的表：`SELECT * FROM dbx_test.no_such_table_xyz`） | 取真实报错文本，确认是否含可解析的位置形态（本轮尚未取，**这是 T-ELU-02 的第一步**） |
| 2 | 同场景换 PG（`ce028080-…`）/ MySQL 连接 | 既有三种 marker 仍生效 |
| 3 | 真机 IDE：故意让预览执行失败且无锚点（如无项目上下文） | 按钮**不出现**（改前会出现且点了无反应） |
| 4 | 真机 IDE：正常失败且锚点可解析 | 按钮出现且点击正确跳转（回归确认） |

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **Oracle marker 臆造**（最大风险） | 若凭 `ORA-` 前缀猜形态，会写出永不命中的规则（或误命中） | T-ELU-02 强制**先取真库文本**再定 marker；先红后绿两次读数 |
| 按钮消失被当成功能退化 | 用户可能习惯「有按钮」 | 变更登记写明「解析不出位置时不再显示按钮（此前点了无反应）」；tooltip 说明原因 |
| 钳制接线改变错误文案 | 接上 `sqlMaxLength` 后超长 SQL 的位置会被钳到 SQL 长度 | 用例断言钳制边界；文案保持既有形态 |
| 两态标注引入新字段影响 MCP 输出 | `SqlVisualizerExplainTool.kt:155-161` 的 `errorPosition` 文案若变会影响 agent 侧 | 保持既有 `char/line N` 形态，仅**追加**来源说明；MCP 用例确认 |
| 无 UI 测试基建 | `SqlResultPanel` 当前无测试类，新增需建基建 | 照既有 UI 测试形态（如 `log/SqlLogPanel*Test`）搭最小夹具；若成本超预期，退为「解析结果断言 + 真机走查」两段 |

## 七、实施顺序

```
T-ELU-01（先解析后显隐，最小改动、独立可并行）→ §五-1（取 Oracle 真实报错文本）
  → T-ELU-02（补 Oracle marker，先红后绿）→ T-ELU-03（接 sqlMaxLength）→ T-ELU-04（两态标注）
  → T-ELU-05（测试与文档）→ §五 真机走查 → 收口
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------------|
| T-ELU-01 | 见下 | 落地形态与计划一致：新增 `errorLocateAction()`——**先解析锚点、解析不出返回 null**，`showErrorRichWithLocate` 改为 `resultPanel.showErrorRich(info, errorLocateAction())`。**UI 侧零改动实证**：`SqlResultPanel` 未动一行（`locate != null` 判定与 `:252` 的显隐分支原样沿用），改动只在 `SqlPreviewPanel`（`locateErrorSource` 拆为「解析」与「跳转」两段）。**额外收紧一处**（计划外、1 行）：源文件已失效（`!anchor.file.isValid`）也算不可解析——「跳不动的按钮不给」比「给了点不动」诚实，配用例 `test错误定位_源文件失效时不给按钮` |
| T-ELU-02 | 见下 | **真库原文已取**（Oracle Free 23 / FREEPDB1，只读，三例）：`ORA-00942: … does not exist\n error occur at position: 23` / `ORA-00936: missing expression\n error occur at position: 7` / `ORA-00900: invalid SQL statement\n error occur at position: 0`。**取值语义实测钉死**：N = 出错记号起点减一（0-based 偏移）→ 归属值 **N+1**（三例一致：`FROM …` 的记号在 0 位 → N=0；`SELECT FROM` 的 `FROM` 在 1-based 第 8 位 → N=7；表名在第 15 位 → N=14）。**计划前提 3 部分证伪**：并非「无 Oracle 形态」——PG 的 `\bPosition:` 一直顺带命中 Oracle 报文里的 `position: N`，故 Oracle 位置**改前就被提取，只是标签错标成 `pg-position` 且按 1-based 直接报出（恒差一位）**。因此修法不是单纯「加 marker」（直接加会让两个 marker 并存、被单一性门控判成多信号而**丢失位置**），而是**先扫 Oracle 段并从文本中遮蔽为等长空格**再扫其余 marker——`PG_POSITION` 正则因此逐字未动，任何不含 Oracle 形态的消息行为零变化 |
| T-ELU-03 | 见下 | 四处投递点全部接上被执行文本长度：`DataSourceSelector` 三处 catch（native 链 / executeQueries / 外层 onFailure，均传 `executedSql.length`——提交文本即驱动报位置所指的文本）与 `SqlVisualizerExplainTool`（传 `target.explainSql.length`——MCP 提交的是 EXPLAIN 包装句，不是原始 `selectSql`）。**接线可验**：新增源码扫描守卫 `ErrorPositionWiringGuardTest`（生产链每个 `ErrorPositionExtractor.buildInfo/extractPosition` 调用的实参必须含 `.length`，且扫描到的调用点数 ≥4，防空绿），配合 `ExecutionErrorInfo.executedSqlLength` 字段（非 null 即接线到位的结构化证据） |
| T-ELU-04 | 见下 | 两态结构化 + 如实标注两处都做：① `ExecutionErrorInfo` 增 `executedSqlLength`（调用方传入的钳制上界，兼作接线证据）；② tooltip 中英双语改写为「驱动报错位置指向**被执行的 SQL 文本**，与 mapper 原文行不一一对应」（原「错误行映射不可靠」只说映射不可靠、没说位置属于哪一态）。**不引入换算**（非目标）。MCP 文案形态未动（仍是 `errorPosition: char/line N`，只是取值现在受钳制） |
| T-ELU-05 | 见下 | **UI 用例基建前提 4 证伪**：`SqlResultPanelUiTest` 早已存在（含 `stateForTest()` / `currentErrorForTest()` 缝），无需新建基建——新增 4 例（不可定位不显示按钮 / 可定位显示且 tooltip 两态 / 无位置信号用起始行口径 / 转普通错误后按钮收起）；`SqlPreviewPanelUiTest` 新增 3 例（无锚点不给按钮、有锚点给按钮、源文件失效不给按钮），加两个 `internal` 测试缝（`errorLocateActionForTest` / `showErrorRichWithLocateForTest`；`resultPanelForTest` 仓内已有，直接复用）。`ErrorPositionExtractorTest` 由 11 例扩到 18 例（Oracle 三例 + 不被 PG 误吞 + 越界钳制 + 接线字段 + 富信息 Oracle 位置）。文档：CHANGELOG `[Unreleased]` Fixed/Changed + 变更记录行 + 第十轮报告 §8 #54 落地备注。**门禁**：按主调度统一跑 `testShards`/`verifyGate`，本任务只跑定向用例 |

**待验证前提（实施者先证伪再动手）——实测结论**：

1. **Oracle 报错文本是否含可解析位置** → **是**（真库原文三例见 T-ELU-02 行）。**但留一条未决**：该文本经 **DBX agent** 通道取得，其后缀 `error occur at position: N` 在 **ojdbc11 23.3 的 jar 内查无此串**（`grep -a` 全量类文件，0 命中；Rust `oracle` crate 0.6.3 与 `sibyl` 0.5.2 源码亦无）——**本插件自身执行链（DBN/ojdbc）是否也附这段后缀，本轮无法验证**（禁 runIde，且无 Oracle 连接凭据）。若我方驱动不附，则该 marker 在我方路径上不命中（无害的死规则）；若附，则修正后的标签与 N+1 归一化生效。**两种情形下改前改后的差别只有「标签 + 是否差一位」，不存在把无位置变成有位置的虚报**——这也是它可安全落地的原因。取证建议：主调度真机跑一条 Oracle 报错 SQL，看面板 tooltip 是否出现「Position：N」。
2. **MySQL `at line N` 的语义** → **实测确认是「SQL 文本第 N 行」**（MySQL 8.4.6，真库）：三行语句 `SELECT 1 / FROM / WHERE` → `at line 3`；四行语句 `SELECT … / JOIN … / GROUP BY / ORDER BY x` → `at line 4`。既有实现按行号处理（`isCharPosition = false`）**正确**，无需改动。
3. **锚点单槽是否被多预览窗口覆盖** → **未验证**（需真机；KDoc 声称「每项目至多一个预览弹窗」）。本任务的改动不改变取值时刻的语义（改前是点击时读、改后是呈现时读，同一错误面板内两者读到的槽值相同），故即便证伪也不引入新问题；**登记为未决**：多预览窗口下若槽被别的预览覆盖，跳转目标会指向后登记的那份源（既有行为，非本任务引入）。
4. **`SqlResultPanel` 的 UI 用例基建成本** → **前提证伪，成本为零**：测试类已存在（见 T-ELU-05 行）。

**未覆盖 / 风险（如实登记）**：

- **真机 IDE 走查（§五-3/4）未做**（禁 runIde）：验收 1 的「真机各一」只有用例那一半。已做的反向验证见下。
- **Oracle marker 在我方驱动路径上的有效性未验证**（见前提 1）——本任务最实的不确定性。
- **`testFast` 不含平台型用例**：`SqlResultPanelUiTest`/`SqlPreviewPanelUiTest` 走 `test` 任务（源码含 `com.intellij` 引用的类不进快桶），定向验证时须用 `./gradlew test --tests`，用 `testFast` 会静默 0 例。
- MCP 的 `errorPosition` 取值现在受钳制（越界会被钳到 EXPLAIN 包装句长度）——文案形态未变，agent 侧无需适配。

