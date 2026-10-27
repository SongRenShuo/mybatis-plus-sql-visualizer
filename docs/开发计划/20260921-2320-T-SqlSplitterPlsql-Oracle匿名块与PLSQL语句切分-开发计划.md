# T-SqlSplitterPlsql Oracle 匿名块与 PL/SQL 语句切分 — 开发计划

- 日期：2026-09-21 23:20
- 任务编号：T-SqlSplitterPlsql（第十轮报告 §9.4 **O-47**，**P1**）
- 来源：第十轮报告 §8 DBX 增量清单 **#52**——DBX `ffe1e3a2`：Oracle 匿名块含局部例程的语句切分，前后端双实现（`crates/dbx-sql/src/sql.rs:2535-2560` 作用域栈推进、`:2650-2660` `OraclePlSqlScope`（Object/Declaration/RoutineHeader/Routine/Block/Case）；`apps/desktop/src/lib/sql/sqlStatementRanges.ts:1988-2020`）
- 性质：**正确性**——不改渲染语义；修的是「合法 Oracle 脚本被切碎后逐条提交」这一真实执行面
- 实施分支：自 master 拉任务分支 t-sql-splitter-plsql
- 验证基线：master `28a1efa`（最近可核读数 491 类/5604 用例 0 失败（T-AgentHeartbeat 收口）与 5577 例 0 失败（T-TemplateKeyDrop 收口）——**两者属不同批次树，实施时以当次实测为准**）

---

## 一、背景与依据（现状核实，含两处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「按 `;` 朴素切分会把匿名块切碎」 | **成立但机制更具体（口径修正 1）**：我方**已有** BEGIN/END 块感知（不是「没有」），但模型是「**单次武装 + 全文单一深度计数**」——`SqlStatementSplitter.kt:220` 的武装条件含 `!beginModeEnabled`，首个 `BEGIN` 之后**永不重新武装**；`blockDepth` 是**全文一个整数**，无 per-routine 归属；`CASE…END` 的 `END` 会被 `flushWord()`（`:89-95`）与 `beginEndBalance`（`:361`）当作块尾递减 | 落点是**补作用域语义**，不是从零写块感知 |
| 2 | 「碎句只影响展示」 | **不成立（口径修正 2，更严重）**：`log/LogToSqlPanel.kt:601` 的 `splitSqlStatements` 委托给本切分器，而 `:218-222` `executeResult()` **按切分结果逐条 `executeStatement` 提交**——碎句会**真的被逐条执行**。`execute/SqlTypeClassifier.kt:194-195` 亦按切分**逐条分类**（影响免确认门判定） | 本任务从「显示问题」升级为**执行正确性问题**，这是 P1 的依据 |

### 1.1 现有状态维度（逐项实测）

| 维度 | 落点 |
|---|---|
| 单/双引号（含反斜杠转义、`''` 加倍） | `:116-137`（`:9-11` 注释） |
| `--` 行注释 / `/* */` 块注释 | `:151-162`；保留/剥离分流 `:56`/`:63` |
| 括号深度 `parenDepth` | `:186-197`，判定 `:177` |
| **BEGIN/END 块感知 `blockDepth`** | `:74-96`、`:220-227`、`:237-245`，判定 `:142`/`:177` |
| T-SQL 批首控制流合并（`CONTROL_FLOW_BATCH_HEADS = setOf("IF")`） | `:40`、`:265-288`、`:350-392` |
| DELIMITER 指令感知 | `:46`、`:140-150`、`:207-219`、`:313-318` |
| 顶层 `;` 为界 / trim / 空段跳过 / 无分号尾段容错 | `:175-185`、`:246-249` |

**无 Oracle PL/SQL 处理**：全文无 `DECLARE` / `IS`/`AS` 前向声明 / `CASE…END` 与块 `END` 的区分 / SQL*Plus `/` 终结符（`DECLARE` 只出现在词表 `sql/SqlKeywords.kt:49/148/221`）。

### 1.2 缺陷推演（三个具体形态）

输入 `DECLARE … PROCEDURE p IS BEGIN NULL; END; BEGIN p; END;`：

1. 局部例程的 `END` 处 `blockDepth` 归 0 → 紧随的 `;` **切段**（`:177-180`）；
2. 外层 `BEGIN…END` 成第二段；
3. 末尾 `END` 成第三段。

**推演结果：一条合法匿名块被切成 3 段**（每段都会被 `LogToSqlPanel` 逐条提交）。

> **实施期更正（2026-09-22，实测）**：该推演偏大——实测为 **2 段**。原因是 `beginModeEnabled` 永不复位只挡「前视武装」，而 `flushWord()`（`:89-95`）在 armed 后对 BEGIN/END **无条件**计数，故局部例程 `END;` 之后外层 `BEGIN` 仍把 blockDepth 抬回 1，外层块不碎；碎的是 DECLARE 声明段本身（`DECLARE … PROCEDURE p IS BEGIN NULL; END` 被切出去）。缺陷成立，受损形态是 2 段而非 3 段。

另两个形态：**`CASE … END CASE`** 的 `END` 被当块尾递减 → 块内 `CASE` 同样切碎；**前向声明 `PROCEDURE p IS;`**（无 `BEGIN`）直接触发 `;` 切分，无任何拦截。

### 1.3 消费方全清单（碎句影响面）

| 消费方 | 影响 | 证据 |
|---|---|---|
| `log/LogToSqlPanel.kt:601` → `:218-222` | **按切分逐条执行**（硬证据） | 同左 |
| `execute/SqlTypeClassifier.kt:194-195`（`splitStripped`）→ `:39-44`/`:52-55`/`:67-78` | 逐条分类 → 影响免确认门；上层 `ui/SqlPreviewPanel.kt:961/1004/1207/1209/1092`、`log/LogToSqlPanel.kt:232/263/265` | 同左 |
| `reverse/SqlToJavaConverter.kt:57-58` → `actions/ConvertSqlToJavaAction.kt:57`、`log/SqlToJavaPanel.kt:781` | 批量转换逐条解析，碎句必失败 | 同左 |
| `mcp/SqlVisualizerCheckSqlTool.kt:46` | 语句数 + 分类 | 同左 |
| `execute/ExecutionTimeoutGuard.kt:38-44` | `statementCount` 放大超时预算——**未找到生产调用方**（仅测试使用，注释自述「消费方接线时按…」） | 同左 |

### 1.4 既有测试（`SqlStatementSplitterTest.kt`，463 行）

- BEGIN/END：`:155` 块内分号不切分、`:164` 嵌套逐层配对、`:170` 大小写不敏感、`:176` 字符串内 BEGIN 不算、`:182` 无配对 END 保守原样、`:189` 块结束后恢复顶层切分；
- T-SQL：`:281-341`（IF/ELSE 双 BEGIN、裸 IF、ELSE IF 链、嵌套、批后独立语句、失衡、字符串内不误判、MySQL `IF()` 不误吞）；
- Oracle：**仅 MERGE**（`:355`/`:367`），**无 PL/SQL 用例**；
- DELIMITER 存储过程：`:376-385` 脚本 + `:388-462` 八例（MySQL 客户端形态）；
- 委托一致守卫：`:234/241/248`、迁移一致 `:255`。

## 二、目标与非目标

**目标**：合法 Oracle 匿名块（含局部例程）与含 `CASE` 的 PL/SQL 块被切分为**单条语句**，不再被逐条提交；既有 MySQL DELIMITER / T-SQL 批 / 引号注释语义**逐字节不变**。

**非目标**：不做完整 Oracle 语法解析（只做「语句边界」判定，保持 `:32` 的纯函数零依赖契约）；不改渲染链与预览语义；不引入 SQL*Plus 专有的 `/` 终结符支持**除非** §八 待验证前提 3 证伪为必要；不动 `ExecutionTimeoutGuard`（其消费方未接线，属另一议题）。

## 三、方案

**T-SSP-01（DECLARE 段整体吞并，最小可行）**：`DECLARE` 起头且后方存在配对 `END` 时，整段吞到末个 `END;`——复用既有前视模式（`:398-432`）。覆盖「匿名块 + 局部例程 + 前向声明」三种形态。

**T-SSP-02（CASE 计数隔离）**：`CASE … END` 不参与块配对（`CASE` 与 `END CASE`/`END` 的配对自成一层，不递减 `blockDepth`）。落点 `flushWord()`（`:89-95`）与 `beginEndBalance`（`:361`）。

**T-SSP-03（完整方案，可选升级）**：轻量作用域栈（Object/Declaration/RoutineHeader/Routine/Block/Case），对齐 DBX `OraclePlSqlScope` 的形态。**默认不做**——T-SSP-01+02 若已覆盖全部用例则按最小可行收口，栈式实现留作后续（避免为不存在的语法面过度设计）。

**T-SSP-04（测试与文档）**：`SqlStatementSplitterTest.kt` 新增用例（**先红后绿**，改前必红）：
- 匿名块含局部 `PROCEDURE`（推演形态）→ 整段一条；
- 匿名块含前向声明 `PROCEDURE p IS;` → 整段一条；
- 块内 `CASE…END` → 不切碎；
- 多个匿名块串接 `BEGIN…END; BEGIN…END;` → **两条**（不是一条也不是四条）；
- 回归：既有 6 例 BEGIN/END + T-SQL 8 例 + DELIMITER 八例全绿。
文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第十轮报告 §8 #52 落地备注。

## 四、验收标准

1. 上述五组新用例全绿，且**改前必红**（先红后绿两次读数留档）。
2. 既有 BEGIN/END（6 例）、T-SQL 批（8 例）、DELIMITER 存储过程（八例）、委托一致（4 例）全绿。
3. `LogToSqlPanel` 执行链对匿名块**只提交一条**（可用既有 headless 测试形态断言切分段数）。
4. `SqlTypeClassifier` 对匿名块的分类不再按碎片产出（免确认门行为如实登记）。
5. 全量 testShards + testDatabase + agent 绿；跑前按并行铁律登记。

## 五、真机走查清单（留人工 / 或经 DBX MCP 执行）

1. **Oracle 匿名块端到端**：DBX MCP 有可用 Oracle 连接（`Oracle Free 23 / FREEPDB1` @ dbx-test-host:20095，connection_id `311778f3-…`；19c 服务名不通）——可经此连接验证 `DECLARE … BEGIN … END;` 的实际可执行形态与切分结果是否一致。**纪律：该库为共享测试库，只读验证、禁止建删对象。**
2. 真机日志面板粘贴含匿名块的脚本 → 多语句浮层显示为**一条**（不碎句）。
3. 真机控制台粘贴同脚本 → 实际提交的语句数与切分一致。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **改语义连带执行面**（最大风险） | 碎句变整段会改变 `LogToSqlPanel` 的执行条数与 `SqlTypeClassifier` 的分类结果——后者影响**免确认门** | 验收 3/4 显式断言两处行为；变更登记写明「匿名块此前被切碎执行，现整段提交」 |
| 吞并过度 | DECLARE 段吞并若边界算错，会把后续独立语句一起吃进来 | 用例 4（多匿名块串接）专测边界；保守原则＝拿不准就按现状切（宁碎不吞错） |
| 破坏既有 DELIMITER 形态 | 两者都在「多语句脚本」面 | DELIMITER 八例与迁移一致用例作为回归门 |
| 真机场景零证据 | 我方**当前无 Oracle 匿名块的真机或测试证据**（锚点核实确认） | §五-1 经 DBX MCP 补证据；若证明我方用户场景中不存在该形态，则本任务降级为「防御性支持」并如实登记 |
| 过度设计 | 直接上作用域栈会引入未验证的复杂度 | T-SSP-03 默认不做，以用例覆盖度为升级判据 |

## 七、实施顺序

```
T-SSP-01（DECLARE 段吞并）→ T-SSP-02（CASE 计数隔离）→ 用例先红后绿（T-SSP-04）
  → [按覆盖度决定是否 T-SSP-03 栈式升级] → §五 验证 → 收口
```

## 八、实施记录（2026-09-22 回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-SSP-01 | （本任务未提交，待主流程统一提交；分支 t-round10） | **实现形态**：新增两态 `declareSection`（声明段）+ `bodyBeginExpected`（声明段边界）。语句首 `DECLARE`（`isStatementHead` 判定，跳过前导空白与注释）且 `hasMatchingEndAhead` 为真 → 进入声明段，段内顶层 `;` 不切分；声明段边界（`DECLARE` 之后或段内顶层 `;` 之后）的 `BEGIN` 即块主体起始，退出声明段后按常规块计数，主体 `END` 使 blockDepth 归零、后续顶层 `;` 恢复切分。**复用点**：`hasMatchingEndAhead`（既有前视模式）、`firstKeywordOf`（语句首判定复用其跳过注释规则）。**吞并边界＝深度归零处，不用「末个 END」**：实测 `DECLARE … END; BEGIN … END;` 为 2 条，取末个 END 会并成 1 条；用例 `PLSQL_声明段后接BEGIN块不误并` 钉住。**改前实测**（先红读数）：含局部例程 2 段、前向声明 2 段、`DECLARE…END; SELECT 1;` 3 段。**偏离**：计划 §1.2 推演「切成 3 段」实测为 **2 段**——机制更正见下「前提 1」。 |　**→ 独立复核订正（2026-09-22，本分支）**：① 声明段**武装条件过宽**——原判据只要求「语句首 `DECLARE` + 后方存在 `END` 词」，而 T-SQL `DECLARE @x INT;` 与 PG 合法顶层语法 `DECLARE c CURSOR FOR SELECT … CASE … END …` 后方本就有 END 词，一旦武装则顶层 `;` 直到文本尾都不再切分（实测 3 段 → 1 段）；现补「后方必须存在 `BEGIN` 词」。② 同族缺陷原先只修了 CASE 一类——`END IF`/`END LOOP`/`END WHILE`/`END REPEAT` 仍无条件递减块深度，含 IF/LOOP 的 PL/SQL 块（匿名块与具名过程）依旧被切碎；现主状态机与 `beginEndBalance()` 两处同口径跳过递减。**先红后绿读数**：新增 9 条用例（4 条声明段/EXPLAIN 订正 + 5 条 IF/LOOP）改前必红；摘掉修复分支 → `SqlStatementSplitterTest` 73 例 5 失败（恰为 5 条 IF/LOOP 用例）、`DialectExplainTest` 亦按订正转红；还原后 `testFast` 全量 **3104 例 0 失败 6 跳过**。
| T-SSP-02 | 同上 | **落点**：`flushWord()`（主循环）与 `beginEndBalance()`（T-SQL IF 批配对，服务 `mergeControlFlowBatches`）同口径改造——`CASE` 自增 `caseDepth`，`END` 时 `caseDepth>0` 则递减 CASE 层而不动 blockDepth；语句边界（顶层 `;` 切分点、自定义界符切分点）重置 caseDepth，防截断 CASE 的层数泄漏到后续语句。**`END CASE` 必须显式识别双词终结形态**（新增词级前视 `nextWordIs`）：END 前视到 CASE 词则整体按 CASE 终结符消费、尾随 CASE 不再开新层。**反向验证**：临时移除该特判 → `PLSQL_CASE块后独立语句不误并` 由 2 段变 **1 段**（块尾 END 被 CASE 层吞掉，后续 `SELECT 1;` 一并吞入），比改前更糟，违反「宁碎不吞错」；已还原（工作树无残留）。**既有 BEGIN/END 用例影响：零**（6 例全绿）。 |
| T-SSP-03 | 同上 | **结论：不升级为栈式**。依据：T-SSP-01+02 落地后新增 13 例全绿（含局部例程、前向声明、CASE 裸 END 与 END CASE 两种收尾、多匿名块串接、吞并边界、自定义界符下的匿名块），`declareSection + bodyBeginExpected + caseDepth` 三态已覆盖「声明段—主体」与「CASE 层」两个维度，无用例逼出 Object/Declaration/RoutineHeader 归属需求；栈式实现（对齐 DBX `OraclePlSqlScope`）留作后续，避免为不存在的语法面过度设计。 |
| T-SSP-04 | 同上 | **先红后绿两次读数**（`gradlew.bat testFast --tests "*SqlStatementSplitterTest*"`）：改前 **61 例 8 失败**——`PLSQL_匿名块含局部例程整段一条` 2 段、`PLSQL_前向声明不切碎` 2 段、`PLSQL_匿名块后独立语句不误并` 3 段、`PLSQL_声明段后接BEGIN块不误并` 3 段、`PLSQL_块内CASE表达式不误判块尾` 3 段、`PLSQL_块内CASE语句END_CASE不误判块尾` 3 段、`PLSQL_CASE块后独立语句不误并` 4 段、`TSQL_批内CASE不误判配对失衡` 2 段；实施后 **64 例 0 失败**。**多匿名块串接的期望段数以改前实测钉住＝2 条**（非先红组，见下「偏离 2」）。**加宽读数**：同命令加 `--tests "*SqlTypeClassifierTest*" --tests "*ExecutionTimeoutGuardTest*"`（另两个 testFast 桶内的切分器消费方）142 例 0 失败（独立复核订正：本节原记 139，系改前 61 例态的旧值；复核实跑 ExecutionTimeoutGuardTest 12 + SqlTypeClassifierTest 66 + SqlStatementSplitterTest 64 = **142**）。**计数口径补注**：上句「改前 61 例」与交付测试文件对不上——交付态为 64 个 `@Test`、基线 b4cf2ec 为 51（即新增 13 例），61 = 51+10 意味着取改前读数之后又补了 3 例。实质结论不受影响（8 条先红用例的实际段数已由复核员逐条独立复现：基线 2/2/3/3/3/3/4/2、实现 1/1/2/2/1/1/2/1，与本节自述一致）。**testShards 未跑**——跑时点有 7 个活跃并行任务，按并行红线禁止默认沙盒全量（且派单明确「全量由主流程统一跑」），故验收 ④ 登记为未验证。**文档偏离**：本任务只回填本节，CHANGELOG / 变更记录 / 第十轮报告 §8 #52 交 T-Round10-08。 |

### 8.1 待验证前提的实测结论

1. **多个匿名块串接的吞并边界**——实测改前即 **2 段**（= 计划推演值），该用例不是先红组。计划 §1.2 对「一条合法匿名块被切成 3 段」的推演**证伪**：实测 2 段。机制更正——`beginModeEnabled` 永不复位只挡「前视武装」，而 `flushWord()` 在 armed 后对 BEGIN/END **无条件**计数，故局部例程 `END;` 之后外层 `BEGIN` 仍能把 blockDepth 抬回 1，外层块不碎，碎的是 DECLARE 声明段本身。
2. 「我方用户确实会用到 Oracle 匿名块」——**未验证**（无真机/用户侧证据）；§五-1 的 DBX MCP 真机走查未做（本任务为纯函数改动，真机 Oracle 走查登记为未验证）。
3. **JDBC 直连是否接受 `/` 终结符**——未做 `/` 支持（计划默认不做），未验证。
4. **`CASE` 两种收尾可否统一处理**——**证伪**：不能靠单一计数器统一处理，必须显式识别 `END CASE` 双词形态（反向验证读数见 T-SSP-02 行）；不需要按方言分流，词法前视即可。

### 8.2 与派单/计划的偏离

1. **验收 ① 的「五组用例改前必红」不成立**：多匿名块串接组改前即绿（2 段＝期望），回归组按定义不红；先红组为 DECLARE 段（含局部例程）、前向声明、块内 CASE（裸 END 与 `END CASE` 各一例）、吞并边界，共 8 例。
2. **验收 3/4 落点不在写集**：`LogToSqlPanelExecuteTest.kt`（平台桶）与 `SqlTypeClassifierTest.kt` 的既有断言不在本任务写集内，按派单降级为「执行链与分类行为如实登记，不新增断言」——切分行为面已由 `SqlStatementSplitterTest` 覆盖，且 `consistencySamples()` 已加入匿名块样例，三委托方（LogToSqlPanel / SqlTypeClassifier / SqlToJavaConverter）与单实现同口径由既有委托一致用例守护。
3. **文档清单**：计划 §三 T-SSP-04 的「CHANGELOG `[Unreleased]` + 变更记录行 + 第十轮报告 §8 #52 备注」按派单删除，仅回填本节。
4. **分支口径**：计划写「自 master 拉任务分支 t-sql-splitter-plsql」，实际按派单在当前 `t-round10`（HEAD `b4cf2ec`）上实施，未新建任务分支。
5. **环境**：读数期间共享 `build/` 与 `~/.gradle` 转换缓存被 7 个并行任务并发写入（testFast 多次 `java.io.EOFException` / `Unable to delete ... transforms` / 转换锁超时），最终读数以临时 init script 把构建目录重定向到仓库外取得（脚本位于系统临时目录，不入仓库、不改工作树）。

**待验证前提（实施者先证伪再动手）**：

1. **多个匿名块串接的吞并边界**（`BEGIN…END; BEGIN…END;`）——本计划推演为「两条」，但未实测；实施第一步应先写该用例跑改前代码，确认现状切几条。
2. 「我方用户确实会用到 Oracle 匿名块」**零证据**（锚点核实结论）——若 §五-1 的验证显示该形态在我方场景中罕见，本任务可降级为防御性支持（仍建议做，因为碎句**会被执行**，代价是错误执行）。
3. **JDBC 直连是否接受含 `/` 终结符的脚本**——`/` 是 SQL*Plus 专有，直连通常不接受。若证伪为不需要，则不做 `/` 支持（本计划默认不做）。
4. `CASE` 的两种收尾（`END CASE` 与裸 `END`）在 Oracle 与 T-SQL 下形态不同，须按方言区分还是统一处理——实施时用真机或文档确认。
