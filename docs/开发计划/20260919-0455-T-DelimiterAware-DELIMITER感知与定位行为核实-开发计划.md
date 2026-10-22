# T-DelimiterAware DELIMITER 感知与定位行为核实 — 开发计划

- 日期：2026-09-19 04:55
- 任务编号：T-DelimiterAware（第八轮报告 §9.4 **O-39**，P3；原拟名「DELIMITER 定位锚点核实」——核实后**改名并收窄落点**，见 §一）
- 来源：第八轮报告 §8 DBX 增量清单 **#39**——DBX `4699295`（issue #9485）：光标停在 `delimiter` 行时执行定位错误，修法为「取其后的首条语句，无则取前一条」（`sqlStatementRanges.ts:2208` `mysqlDelimiterDirectiveCursorRange`，集入 `statementRangeAtCursor`）
- 性质：**先核实、后补缺**——插件自研面与 DBX 原生面分离，原建议的落点在核实后需要换位置
- 实施分支：自 master 拉任务分支 t-delimiter-aware
- 验证基线：master（testShards 5153/0/0/6 + testDatabase 10/0/0/0 + agent 绿）

---

## 一、背景与依据（现状核实，含两处口径修正）

| # | 原建议口径 | 核实事实 | 影响 |
|---|-----------|---------|------|
| 1 | 「核实插件控制台有无光标定位点，有则补 delimiter 行处理」 | **半对**。插件自研层**无光标定位面**：提交文本恒为整篇文档（预览链 `ui/SqlPreviewPanel.kt:885-887` 取 `document.text`；已备控制台链 `execute/DataSourceSelector.kt:2367`/`:2379`/`:2427` 取 `editor.document.text`；日志链 `log/LogToSqlPanel.kt:216`），grep `selectionModel`/`getSelectedText` 在预览/日志面板 **0 命中**。**但语句级定位实际由 DBX 原生 `findScriptModel(editor, execOption)` 承担**（`DataSourceSelector.kt:2523-2529` 反射调用、实调 `:2568`；`execOption` 取 `DatabaseSettings.getDefaultExecOption()` `:1403-1412`） | 「在控制台链补 delimiter 处理」**可能无从下手**（原生行为不在我方代码里）→ 先做行为核实 |
| 2 | （无此预期） | **插件自研拆分器无 DELIMITER 感知**：`util/SqlStatementSplitter.kt:27`（`split():43`/`splitStripped():50`）的状态机只处理引号/注释/括号深度/`BEGIN…END` 配对/T-SQL `IF` 批首（`:9-23`、`:216-239`），全文无 `DELIMITER` 关键字（grep `-i delimiter src/main/kotlin` 仅命中 `SourceInterceptorTemplates.kt:131-133` 的无关字符串工具） | **这才是插件可控的真实缺口**：粘贴含 `DELIMITER $$` 的存储过程时自研切分会产出碎句 |

**自研拆分器的消费面**（决定缺口影响范围）：`log/LogToSqlPanel.kt:218-250`（多语句勾选浮层）、`execute/SqlTypeClassifier.kt:40`/`:53`（类型分类与确认流）、`mcp/CheckSqlTool.kt:46`（MCP 静态核查）、`execute/ExecutionTimeoutGuard.kt:38`（超时预算语句计数）。**控制台提交链不走它**（走 DBX 原生）。

**诊断探针已在读光标（但不参与决策）**：`DataSourceSelector.kt:1036-1046` 调 `ScriptModelUtil.getSelectedStatementsRange(client, editor, execOption)`，日志打印 `caret=${editor.caretModel.offset}`、`execSelection=…`（`:1172`），但整段在 `LOG.isDebugEnabled` 下（`:1005` 早退）——**只诊断、不决策**。这给「真机核实」提供了现成的取证入口。

**最优落点（若要做提交前预处理）**：`DataSourceSelector.stripGSuffixForSubmit(sqlText, dataSourceUrl)`（`:3332`）+ 纯函数 `execute/MysqlClientGSuffix.kt`（`:47`/`:70`）——`\G` 剥离（T-MysqlGStrip `6810ad1`）就在这一层，形态是「提交前预处理 + 回写文档」（`:2367-2374`），有现成测试范式（`MysqlClientGSuffixTest`/`MysqlClientGSuffixSubmitChainTest`）。DELIMITER 处理可挂同族。

## 二、目标与非目标

**目标**：①**核实**控制台在 delimiter 行执行时的实际行为（原生 DBX 负责，须有结论）；②补插件自研拆分器对 `DELIMITER` 指令的感知，消除碎句。

**非目标**：不改 DBX 原生执行语义（不在我方代码里，也不应绕过）；不重造语句定位（原生 `findScriptModel` 已承担）；不动 `\G` 剥离的既有行为。

## 三、方案

**T-DA-01（真机行为核实，先做，可能得出「无需动作」）**：用现成探针（`LOG.isDebugEnabled` 打开 `[M36]` 诊断行）在真机 MySQL 数据源上实录四种光标位置下的执行范围：①光标在 `DELIMITER $$` 行；②光标在指令后的语句内；③光标在 `$$` 结尾行；④光标在普通语句。**产出**：实录 + 判定——若原生已正确处理（DBX 自身支持 delimiter，其 batch/DELIMITER 自动转批在既往调研中已有记载），则本项**结案为「原生已覆盖、无需动作」**，仅登记；若原生确有偏差，则登记为平台侧限制（我方不可修）或按用户决策另议。**这一项是「先证伪再动手」的应用——不先假定缺口存在。**

**T-DA-02（自研拆分器 DELIMITER 感知）**：`util/SqlStatementSplitter.kt` 状态机增 `DELIMITER <token>` 指令识别：识别到指令后，后续语句边界改用该 token（而非 `;`），直到下一条 `DELIMITER` 指令或文本结束；`DELIMITER` 指令行本身**不作为独立语句**产出（当前会自成一段且残留 `$$`）。**约束**：①保持 `split()`/`splitStripped()` 既有签名与「注释随语句保留」语义；②非 MySQL 族方言是否识别（`DELIMITER` 是 MySQL 客户端指令，其他方言无此概念）——建议**无条件识别**（文本层面即可判定，不影响其他方言的正常分号语义，因为只有出现指令才切换）；③`BEGIN…END` 配对与 T-SQL 批首逻辑不得回归。

**T-DA-03（消费面回归）**：四个消费面各补用例——①`LogToSqlPanel` 多语句勾选浮层对含 DELIMITER 脚本的切分正确（不再碎句）；②`SqlTypeClassifier` 对存储过程整体判 `DDL`（而非把 `DELIMITER` 判成未知 + 碎句）；③`mcp/CheckSqlTool` 不再把 `$$` 残留当语句；④`ExecutionTimeoutGuard` 语句计数正确（防预算被碎句放大）。

**T-DA-04（已定案：不实施，留档备选）**：若 T-DA-01 结论为「原生有偏差且用户希望我方兜底」，则在 `stripGSuffixForSubmit` 同族增 `DELIMITER` 指令剥离/规范化。**D1 已定案（2026-09-19，用户认可本会话推荐）：不做**——控制台链由原生负责，我方插手的收益与风险都需另行确认；本项保留为留档备选（不删）。

**T-DA-05（文档）**：CHANGELOG + 变更记录 + 第八轮报告 §8 #39 落地备注（含「我方可控面在自研拆分器、控制台链归原生」的口径说明）。

## 四、验收标准

1. T-DA-01 有实录与明确判定（原生已覆盖 / 有偏差 / 不可判），不得以「未验证」含糊结案——**若环境受限确实测不到，按项目惯例显式写「环境受限未验证」并说明缺什么**。
2. `SqlStatementSplitter` 对含 `DELIMITER $$` 的存储过程脚本切分正确：指令行不产出、`$$` 不残留、过程体为一条语句（先红后绿）。
3. 既有切分用例全绿（分号语义、注释保留、`BEGIN…END` 配对、T-SQL `IF` 批首四条不回归）。
4. 四个消费面各有对应用例（T-DA-03）。
5. 全量 testShards + testDatabase + agent 绿；跑前按并行铁律登记。

## 五、真机走查清单（留人工）

1. 真机 MySQL 控制台粘贴含 `DELIMITER $$` 的存储过程 → 四种光标位置执行行为实录（T-DA-01）。
2. 日志面板粘贴同一脚本 → 多语句勾选浮层显示正确（不碎句）。
3. MCP `sqlv_check_sql` 对同一脚本 → 不报 `$$` 残留。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 原生行为不可控 | 控制台链的定位在 DBX 内，我方无法修 | T-DA-01 先核实再定；结论若为平台限制则如实登记（不硬造兜底） |
| 拆分器改动回归 | 状态机是全仓唯一拆分实现，四处消费 | 验收 3 的四条既有语义作回归门；先红后绿 |
| 方言误判 | 非 MySQL 文本含 `DELIMITER` 字样（如注释） | 只在**行首指令形态**识别（对齐 MySQL 客户端语义），注释内不识别 |
| 与 T-TailSweep2 交叠 | 后者 T-FS-* 在 `xml/`、`converter/`、`config/`，与本任务 `util/`、`log/`、`mcp/` 不交 | 仍按并行铁律 check/register |

## 七、实施顺序

```
T-DA-01（真机核实，先做——可能结案）→ T-DA-02（拆分器感知）→ T-DA-03（消费面回归）→ T-DA-04 已定案不做（留档）→ T-DA-05（文档）
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-DA-01 | 无提交（探针代码已删除，结论见 §8.2） | 判定＝**原生已覆盖（静态证据）＋光标行取值环境受限未验证**；两次探针实录见 §8.2 |
| T-DA-02 | `b1b63ef` | `SqlStatementSplitter` 增「当前界符」维度 + 行首 `DELIMITER <token>` 指令识别；先红后绿见 §8.3 |
| T-DA-03 | `a981e13` | 四消费面各 1 例（log/execute×2/mcp），共 +4 例；读数见 §8.4 |
| T-DA-04 | 不实施 | 已定案（2026-09-19，用户认可）：不做；留档备选。T-DA-01 结论亦不构成启动理由（§8.2） |
| T-DA-05 | 收窄（见 §8.6 偏离①） | 仅本计划 §八；`CHANGELOG.md`、`docs/变更记录.md`、第八轮报告 §8 #39 落地备注未动 |

### 8.1 落地形态（T-DA-02）

`util/SqlStatementSplitter.kt` 状态机新增三处，既有语义零改动：

- **当前界符维度**：`var delimiter`（默认 `;`）。`DELIMITER` 指令切换后，顶层切分改用该 token 整串匹配
  （`sql.startsWith(delimiter, i)`），`;` 分支仅在默认界符下生效（界符生效期内的顶层 `;` 原样进段）；
- **指令识别**：词终结处（`else` 分支）判「`DELIMITER` 词 + 后续为空白 + 行首 + 词前缓冲全空白」四条同时成立
  → 切界符、整行丢弃（`readDelimiterDirective` 取行内首个空白分隔词，空 token 复位 `;`）。行首判定
  `isLineHead`、词起点缓冲长度 `bufferLenAtWordStart`（首版用 `sb.isBlank()` 判「待切段为空」误判——
  词本身已在缓冲里，红测实录见 §8.3）；
- **分支优先级**：自定义界符匹配置于注释/引号/标识符分支之前（`$$` 的 `$` 是标识符字符，须先命中；
  `//` 与块注释起始 `/*` 字符不同故不冲突）；`END$$` 形态由「匹配分支先 `flushWord()` 再切」处理。

消费面零改动：四处消费方（`LogToSqlPanel.splitSqlStatements` / `SqlTypeClassifier.splitStatements` /
`CheckSqlTool` / `ExecutionTimeoutGuard` 的计数入参）经同一单实现自动获益，未改其代码。

### 8.2 T-DA-01 判定与实录（先证伪）

**判定：原生已覆盖 DELIMITER 指令语义（静态证据）；「光标停在 delimiter 行取哪条语句」这一取值行为
环境受限未验证。对行动无影响——T-DA-04 已定案不做，故本项不阻塞任何后续工作。**

静态证据（本机 jar 类文件方法表/常量池实读，非推演）：

- 243 平台（测试/运行基准）`…/transformed/ideaIU-2024.3.7.1-win/plugins/DatabaseTools/lib/database-plugin.jar`：
  `com.intellij.database.script.ScriptModelUtil` 有 public static
  `getSelectedStatementsRange(DatabaseScriptingSessionClient, Editor, ExecOption) → TextRange`、
  `getSelectionForConsole(Editor, ExecOption) → TextRange`、`adjustSelectionRange(ScriptModel, Document, TextRange, ExecOption) → TextRange`；
  `com.intellij.database.console.JdbcConsoleProvider` 有 public static
  `findScriptModel(PsiFile, PsiElement, Editor, ExecOption) → Info`、`getScriptModel(PsiFile) → ScriptModel`。
  即：语句范围解析确在原生侧，我方只消费（与 §一 核实一致）。
- 262 平台（本机 DataGrip `DB-262.10315.132`，`plugins/DatabaseTools/lib/modules/*.jar`）——DELIMITER 是一等公民：
  `intellij.database.dialects.mysqlbase.jar` 的 `MysqlGeneratedParser` 含 `MYSQL_DELIMITER`、
  `psi/MysqlDelimiterStatementImpl`（`getDelimiter`/`isDelimiter`）、`MysqlDialectBase` 含 `MYSQL_DELIMITER_TOKEN`
  且实现 `SubstitutionDescriptorImpl$DelimiterAware`（`getDelimiter`/`getDelimiterAt`）；
  `intellij.database.sql.core.impl.jar` 的 `SqlPreprocessingLexer` 含 `statementDelimiter`/`isDelimiterMatch`、
  `PreprocessorMatchState` 含 `myStatementDelimiter`/`findDelimiterMatch`/`myDelimiterMatched`/
  `onlySpacesAndDelimitersUpToEol`——原生在**词法预处理层**就跟踪「当前语句界符」并把指令行排除在语句外，
  正是 DBX #9485 需要自己补 `mysqlDelimiterDirectiveCursorRange` 的那一层。
- 我方侧（源码实读）：提交恒为整篇文档、无 `selectionModel`/`getSelectedText` 光标面（§一 表 1 已核实），
  故 delimiter 行的定位语义我方既不可控、也无落点。

行为实录（两次探针，均未入库；探针代码跑完即删，避免把夹具扰动留给共享通道）：

1. **无控制台会话的独立探针**（临时 `.sql` + MySQL 方言映射 + `EditorFactory` 编辑器，反射调
   `ScriptModelUtil.getSelectionForConsole(Editor, ExecOption)`）：API 可达，但四种光标位置全部返回
   退化空范围 `(0,0)` / `(54,54)` / `(67,67)` / `(82,82)` → 该 API 的有效取值依赖**活控制台会话**
   （与插件经 client 变体取值的形态一致），无会话时取不到语句范围。
2. **活控制台会话探针**（testDatabase 沙盒内走完 prepareConsole：introspection 成功、runner=1、
   控制台编辑器夹具就位，以控制台为 client 调
   `getSelectedStatementsRange(DatabaseScriptingSessionClient, Editor, ExecOption)`）：四位置仍为退化范围
   （`(0,0)` / `(54,54)` / `(13,13)` / `(82,82)`），同轮 e2e 用例报「数据库控制台被 IDE 回收（数据源模型
   尚未就绪）」——headless 夹具持不住稳定的原生脚本模型，取值不可信，故不作为判定依据。

**缺什么（环境受限的具体口径）**：可交互的 runIde 沙盒（Database 插件 + 真 MySQL 数据源 + 手工放置光标，
即 §五 走查清单第 1 条）或一个能稳定持有控制台会话与脚本模型的自动化夹具。前者属真机走查、后者当前
基建不具备（DB 集成类的控制台在 headless 下会被 DBN 回收，已在册现象）。

### 8.3 T-DA-02 先红后绿实录

- **红**（实现前，`./gradlew test --tests …SqlStatementSplitterTest`）：`49 tests completed, 6 failed`。
  失败读数即缺陷实录：`DELIMITER $$` 脚本切出 **4 段**（期望 2），首段为 `DELIMITER $$`、第二段残留
  `SELECT 1$$`；`DELIMITER //` 形态切出 1 段（`//` 被当普通字符吞进语句）；`DELIMITER` 空参数形态切出 4 段。
  两条负向用例（字符串/注释内不识别、非行首不识别）改前即绿——它们锁的是「不误识别」边界。
- **绿**（实现后）：`49 tests / 0 failures / 0 errors / 0 skipped`，新增 8 例：
  过程体整段一条+指令行不产出+`$$` 不残留、自定义界符生效期内 `;` 不切分、`DELIMITER ;` 复位、
  `//` 界符与块注释共存、空参数复位、字符串/注释内不识别、非行首/待切段非空不识别、`splitStripped` 同口径。
- 既有四条语义回归门（分号/引号、注释保留、括号深度、`BEGIN…END` 配对、T-SQL `IF` 批首）全部沿用原用例，
  未改期望；`DELIMITER` 形态另进「三委托方一致性样例池」，锁死三处委托方同口径。

### 8.4 T-DA-03 消费面用例（4 例，各面 1 例）

| 面 | 测试类 | 用例 | 断言要点 |
|---|---|---|---|
| ① 日志面板多语句勾选浮层 | `log/LogToSqlPanelExecuteTest` | `test拆分_DELIMITER脚本整段一条不碎句` | 候选 2 条、过程体整段、内部 `;` 保留、`$$` 不残留、指令行不成候选 |
| ② 类型分类/确认流 | `execute/SqlTypeClassifierTest` | `testDelimiterProcedureScriptClassifiesDdlNotFragments` | 过程体判 DDL、复位后判 QUERY、`getFirstKeyword` 不再回 `DELIMITER`、整脚本需确认 |
| ③ MCP 静态核查 | `mcp/CheckSqlToolTest` | `DELIMITER脚本_界符残留不当语句` | 输出 `statements: 2`、`$$` 不进输出、过程体判 DDL |
| ④ 超时预算语句计数 | `execute/ExecutionTimeoutGuardTest` | `testDelimiterScriptStatementCountNotAmplified` | 计数 2 → 预算 60s（改前碎 4 段会放大到 120s） |

定向读数：上述四类 `93 tests / 0 failures / 0 errors`（`ExecutionTimeoutGuardTest` 12、`SqlTypeClassifierTest` 66、
`LogToSqlPanelExecuteTest` 9、`CheckSqlToolTest` 6）。

### 8.5 门禁读数（最终代码状态，2026-09-19）

- `./gradlew testShards`：**5292 tests / 0 failures / 0 errors / 6 skipped**（片 1873+1792+1627，耗时 194s/207s/188s）。
  计划 §三 记的基线 `5153/0/0/6` 是立项时读数，其后 master 合入他轮任务用例，故本轮总数更高（非本任务引入的差异）。
- `./gradlew testDatabase`：**10 tests / 0 failures / 0 errors**（5 类，与基线一致）。
- `./gradlew :sql-visualizer-agent:test`：**58 tests / 0 failures / 0 errors**（13 类）。
- 触达测试类读数（分片 XML）：`SqlStatementSplitterTest` 49、`SqlTypeClassifierTest` 66、
  `ExecutionTimeoutGuardTest` 12、`LogToSqlPanelExecuteTest` 9、`CheckSqlToolTest` 6，全 0 失败。

### 8.6 偏离计划之处

1. **T-DA-05 收窄**：只回填本计划 §八。`CHANGELOG.md`、`docs/变更记录.md`、第八轮报告 §8 #39 落地备注
   均未改——前者不在派活方写集内，后两者被明令禁改（同批三路已因误改主仓/共享文档返工）。请派活方决定
   是否由收口方统一补这三处落地备注。
2. **T-DA-01 的取证方式**：计划 §三 指定用现成 `[M36]` debug 探针在真机实录；实际改为「静态证据 + 两次
   探针尝试」，原因见 §8.2（该探针只在 `LOG.isDebugEnabled` 下打日志、且要真机交互放光标，本机不具备）。
   探针代码跑完即删、未入库（第一版曾把探针留在 `DataSourceSelectorIntegrationTest`/`DbConsoleIntegrationTest`，
   实测会扰动同 JVM 内的既有 e2e 用例——控制台被回收——故整段还原，两文件回到 `6a6e963` 原状）。
3. **`split()` 与 `splitStripped()` 同口径丢弃指令行**：计划 §三 只要求「指令行不作为独立语句产出」，
   实现上两者都把指令行整行丢弃（含界符 token），未采用「把指令行挂到下一条语句前」的备选形态——
   后者会让 `SqlTypeClassifier` 首关键字回 `DELIMITER` 判 UNKNOWN，与验收 4② 冲突。

### 8.7 未验证项与残留

1. **T-DA-01 的光标行取值行为**：环境受限未验证（缺什么见 §8.2 末段）。不影响交付面（T-DA-04 已定案不做）。
2. **控制台提交链的实际可执行性**：本任务只改自研拆分器，控制台链仍走原生、提交整篇文档。含 `DELIMITER $$`
   的脚本在日志面板执行时，切分产物是「过程体一条（无 `DELIMITER` 指令、无尾界符）」——送进原生控制台后
   原生是否按单条执行，未在真机验证（同属 runIde 走查面）。改前该场景是碎句，改后至少是单条完整语句；
   这一层属 §五 走查清单第 2 条。
3. **非 MySQL 方言的 `DELIMITER` 字样**：识别无方言条件（文本层判定，仅出现指令才切换）。风险已在计划 §六
   登记，实现按「行首 + 语句边界」双限定收敛；未在真机对 PG/Oracle 脚本回归（既有用例池全绿）。

