# T-McpAuditTrail MCP 执行链落历史与审计补全 — 开发计划

- 日期：2026-09-19 04:45
- 任务编号：T-McpAuditTrail（第八轮报告 §9.3 **N-29**，P1；原拟名「MCP 双通道审计闭环」——核实后改名收窄，见 §一 口径修正）
- 来源：第八轮报告 §8 DBX 增量清单 **#38**——DBX 的 MCP 通道执行 SQL 后落历史（HistoryEntry 带 `source:"mcp"`、耗时、成败、error），前端容错识别 + 历史列表显「MCP」徽标，风险类别复用既有分类器（DBX `0f6db4b` + `5465295`）
- 性质：**审计可见性 + 证据链补全**——不新增执行能力，把已发生的执行与调用如实留痕
- 实施分支：自 master 拉任务分支 t-mcp-audit-trail
- 验证基线：master（T-TailSweep2 交付后，testShards 5153/0/0/6 + testDatabase 10/0/0/0 + agent 绿；实施时以当次实测为准）

---

## 一、背景与依据（现状核实，含四处口径修正）

立项前的三路锚点核实推翻了原始建议的四处前提，**本计划的形态按核实结果重写**：

| # | 原建议口径 | 核实事实 | 影响 |
|---|-----------|---------|------|
| 1 | 「MCP **十工具**的执行类调用落历史」 | **十个工具里只有 1 个有执行面**：`sqlv_explain`（`mcp/ExplainTool.kt:18`）。其余九个为纯静态分析（PSI/JSqlParser/文本），`mcp/CheckSqlTool.kt:14-16` KDoc 明示「纯文本判定，绝不执行」 | 范围从「十工具」收窄为「执行类工具（当前仅 `sqlv_explain`）」 |
| 2 | 落「执行过的 SQL」 | 执行的不是用户原句，而是 `DialectRegistry.byName(dialectName).renderExplainStatement(selectSql)` 包出的 `EXPLAIN <select>`（`ExplainTool.kt:102`），原始 `selectSql` 只作参数存在、当前零留痕 | 落历史必须落**原始 `selectSql`**，见 §三 T-MAT-02 |
| 3 | 复用既有「来源」字段即可 | 历史模型有**两套**：预览历史 `SqlHistoryEntry`（`history/SqlHistoryEntry.kt:22-33`，**无任何 source 字段**）与日志历史 `LogSqlEntry`（`log/LogSqlModels.kt:26-115`，**已有** `SqlSource` 枚举 `:50` = MYBATIS/HIBERNATE/JDBC/AGENT/UNKNOWN）。但后者**根本不持久化**：`.sql` 头部 `source:` 段被 `SqlLogFileReader.kt:241-249` `parseSource()` 占用为「源码文件:行号」，读回路径 `:195-215`/`:218-236` **从不设置 `source` 字段** → round-trip 后必然退化为 `UNKNOWN` | 「加个枚举值」不够，必须同时解决持久化通道，见 T-MAT-03 |
| 4 | 「每调用已有 `[MCP-AUDIT]` 审计」 | 审计行存在但**不含入参**：`mcp/McpToolsetSupport.kt:68` 成功路径把字面量 `"ok"` 当 `argsSummary` 传入，`mcp/McpAuditLog.kt:24` 的 `summarize(vararg pairs)` 工具函数**全仓零调用点**（grep 仅定义处） | 「审计闭环」的一半是空的，须补入参接线，见 T-MAT-04 |

**现成资产（决定本计划成本低的原因）**：

- **执行链已有单点入口**：`mcp/McpDbExecutor.kt:120` `executeQuery(project, dataSource, sql, maxRows, timeoutSeconds)`，`:151` `REMOTE_STATEMENT.executeQuery(sql)`；全仓唯一调用点是 `ExplainTool.kt:117-121`。`McpSelectGuard.kt:10` KDoc 已把该通道定义为「sqlv_explain / **未来任何执行类工具共用**」——挂这一层，未来新增执行工具自动获得历史。
- **面板已有徽标渲染范式**：`log/HistorySqlLogPanel.kt:858-859` 目标库角标、`:855` 时间戳、`:856` 耗时、`:860` 来源、`:850-851` 行号，全部走 `log/SqlLogPanel.kt` 的 `internal` 片段工厂（`formatDatabaseFragment:1959`、`formatUpdateCountFragment:1974`、`formatErrorFragment:1984`、`formatSourceFragment:1993`），协议统一为 `Pair<String, SimpleTextAttributes>?`。加「MCP」徽标是**填空式增量**。
- **扩展载荷通道已存在且容错**：`-- pd: <base64> --` 行（写端 `log/SqlLogAutoSaver.kt:371-375`、编解码 `log/SqlLogParamCodec.kt:72`/`:119`），`decode` 全程 `runCatching` 失败返 null、读端降级为普通注释行（`SqlLogFileReader.kt:175-179`）。
- **分类器已是多消费方成熟资产**，无需新建：风险/类型面 `execute/SqlTypeClassifier.kt:67` `classify(): SqlType`（QUERY/DML/DDL/UNKNOWN，含只读二级证明 fail-closed `:96-108`）；渲染染色面 `log/LogStatementClassifier.kt:30` `classify(): StatementType`。

**两条硬约束**：

1. `src/test/kotlin/.../log/SqlLogHistoryRoundTripTest.kt:68` 断言 `.sql` 头部**逐字符相等**——任何新增头部段都会打断该测试；走 `pd:` 载荷则不打断。
2. 染色链对 `OTHER` 返回 null（`log/SqlListTypeColor.kt:103`）→ 若落 `EXPLAIN` 包装句，首 token 为 `EXPLAIN` 落入 `OTHER`（`LogStatementClassifier.kt:47`）→ **无染色**，#38 宣称的「风险类别复用分类器」当场失效。

## 二、目标与非目标

**目标**：`sqlv_explain` 的每次真实执行在 IDE 内留下可查痕迹（历史面板可见、带来源徽标、带耗时与成败），且 MCP 审计行带上真实入参摘要。

**非目标**：不给其余九个工具造历史（它们不执行，无历史可言）；不新建独立 MCP 历史存储（`SqlHistoryManager` 的面板不读它、`HistorySqlLogPanel` 只读 `.sql` 文件，另起一套会让徽标无处渲染）；不改执行语义与门控（`McpSelectGuard` 强只读闸门原样）；不动 MCP 工具签名（参数面变更归 T-McpRowBudget）。

## 三、方案

**T-MAT-01（执行链落历史，回调注入形态）**：`McpDbExecutor` 暴露 `onExecuted` 钩子（执行完成/失败后回调，携带原始 sql、耗时、成败、errorText、dataSource 名），由 `ExplainTool` 侧接线到 `log/SqlLogManager` 的既有入库路径。**理由**：`McpDbExecutor` 是纯反射、无 IDE 服务依赖的层（headless 可测性靠这个），直接依赖 `SqlLogManager` 会污染该边界。异常路径的 `SQLException` 文本已有可采点（`McpDbExecutor.kt:158-173`）。

**T-MAT-02（落原始 SQL，不落 EXPLAIN 包装句）**：入库内容为原始 `selectSql`；是否已 EXPLAIN 包装作为独立标记记录（不体现在 SQL 文本里）。**依据**：落包装句会让 `LogStatementClassifier` 判 `OTHER` → `SqlListTypeColor:103` 返回 null → 无染色（§一 硬约束 2）。

**T-MAT-03（`SqlSource.MCP` + 持久化走 pd 载荷）**：
- `log/LogSqlModels.kt:6-17` 枚举增 `MCP` 常量（零破坏，默认值仍 `UNKNOWN`）。
- **持久化不走头部 `source:` 段**（该段语义已被 `parseSource` 占用为源码位置）——走 `pd:` 载荷：`SqlLogParamCodec.kt` 增短键、`DecodedPayload` 加字段、读端 `SqlLogFileReader.kt:206-215` 回填。依据：`decode` 失败降级、旧文件无该键自然为 null、不改 `countInFile`（`:83-101` 只数头部行）、不打断 `SqlLogHistoryRoundTripTest.kt:68`。
- **配套必改**：`SqlLogAutoSaver.kt:371-374` 的 pd 写入条件当前只判 params/占位符/六个扩展字段——须追加「`source == MCP`」，否则一条无参数、无占位符、无 mapperStatement 的 MCP SELECT 会**丢掉来源标记**（这正是本任务的存在意义）。
- 向后兼容：旧版读到带 `MCP` 的新文件不受影响（未知键进 `DecodedPayload` 可选字段）；新版读旧文件 `source` 仍为 `UNKNOWN`（与现状一致）。

**T-MAT-04（审计行补真实入参）**：`McpToolsetSupport.kt:68` 把字面量 `"ok"` 换成经 `McpAuditLog.summarize`（`:24`，当前零调用）产出的摘要——工具名之外的 `dataSource` / `dialect` / SQL 首段（受既有 `SUMMARY_LIMIT=120` 截断）。**脱敏口径**：只记 SQL 文本摘要，不记参数值（对齐 T-RA 系列「不落异常 message」的脱敏惯例）；失败路径补记 errorText 类型而非原文全文。

**T-MAT-05（面板徽标）**：新增 `formatMcpFragment`，照 `formatDatabaseFragment`（`SqlLogPanel.kt:1959`）的 `Pair<String, SimpleTextAttributes>?` 协议，插在 `HistorySqlLogPanel.kt:858-859`（目标库角标）之后、`:860`（来源）之前。未知/非 MCP 返回 null 不显示（与目标库角标同规）。日志页同类渲染器（`SqlLogPanel.kt:2080-2090`）是否同步：先核实该处渲染的是否同一模型，是则一并接。

**T-MAT-06（测试与文档）**：用例覆盖——①`sqlv_explain` 执行后历史出现条目且 `source=MCP`；②round-trip 后 `source` 仍为 `MCP`（钉住 T-MAT-03 的持久化，这是当前 `SqlSource` 的既有缺陷面）；③无参数/无占位符场景 pd 行仍写出（钉住 T-MAT-03 的配套必改）；④染色：MCP 条目的 `statementType` 不为 `OTHER`（钉住 T-MAT-02）；⑤审计行含入参摘要、`summarize` 不再是死代码（补消费点守卫）；⑥失败路径落 `errorText`。文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第八轮报告 §8 #38 落地备注。

## 四、验收标准

1. `sqlv_explain` 成功执行一次 → 历史面板可见该条、带 MCP 徽标与耗时；IDE 重启后仍在（持久化成立）。
2. `source=MCP` 经 `.sql` round-trip 后**不退化**为 `UNKNOWN`（对照当前 `SqlSource` 的既有缺陷，先红后绿）。
3. 无参数、无占位符的 SELECT 场景来源标记不丢（T-MAT-03 配套必改的专属用例）。
4. 其余九个工具行为逐字节不变（无历史写入、审计行格式除入参段外不变）。**限定（独立复核轮订正）**：「逐字节不变」只对 **OK 路径**成立——错误路径的入参段格式十个工具统一变化（由「只放错误文本」改为 `args=<摘要> error=<文本>`），九静态工具的错误行同样变了字节；变化只落在审计入参段，工具结果与审计行其余字段不变。
5. `SqlLogHistoryRoundTripTest` 等既有头部断言测试全绿（未走头部段）。
6. 全量 testShards + testDatabase + agent 绿；跑前按并行铁律登记。

## 五、真机走查清单（留人工）

1. 真机 `sqlv_explain` 执行 → 历史面板出现条目、徽标与耗时正确；重启 IDE 后条目仍在且徽标未退化为无。
2. 无参数 mapper 的 SELECT 走 MCP EXPLAIN → 来源标记保留。
3. idea.log 的 `[MCP-AUDIT]` 行含 dataSource/dialect/SQL 摘要，且摘要已截断（不超 120 字符）。
4. 历史面板筛选（类型过滤）对 MCP 条目行为正常（不因 `source` 新值异常）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 跨层依赖污染 | `McpDbExecutor` 引入 IDE 服务依赖会破坏其 headless 边界 | 走 `onExecuted` 回调注入（T-MAT-01），依赖留在工具层 |
| 落历史写入性能 | 每次 EXPLAIN 多一次磁盘写 | 复用 `SqlLogAutoSaver` 既有节流/批量路径，不新开写通道 |
| 头部格式硬门禁 | 误改头部段会打断 `RoundTripTest:68` | 明确走 `pd:` 载荷；用例 5 作为回归门 |
| 旧版兼容 | 新版文件被旧版插件读 | 未知 pd 键在旧读端降级为注释行（既有机制），SQL 正文无损 |
| 染色失效 | 落包装句导致无染色 | T-MAT-02 落原始 SQL + 用例 4 钉住 |

## 七、实施顺序

```
T-MAT-02（落原始 SQL 口径先行，决定下游）→ T-MAT-01（回调钩子 + 接线）→ T-MAT-03（枚举 + pd 持久化 + 配套必改）→ T-MAT-05（徽标）→ T-MAT-04（审计入参）→ T-MAT-06（测试与文档）→ 真机走查 §五
```

T-MAT-04 与 01/03/05 写集不相交（`mcp/McpToolsetSupport.kt` / `mcp/McpAuditLog.kt` vs 其余），可并行；T-MAT-03 与 T-MAT-05 同触 `log/` 域但不同文件（`SqlLogParamCodec`/`SqlLogFileReader`/`SqlLogAutoSaver` vs `SqlLogPanel`/`HistorySqlLogPanel`）。

## 八、实施记录

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-MAT-01 | `2a1bf53` | 回调签名 `onExecuted: ((ExecutedOutcome) -> Unit)? = null`——可选参数而非全局钩子/接口：null 时零行为差异（执行本体原样搬进 private `executeQueryInternal`），成功与失败两条路径各回调一次，钩子自身异常 `runCatching` 只记日志（审计不得成为门控）。**headless 可测性结论**：`ExecutedOutcome` 只含 String/Long（不引平台类型），McpDbExecutor 仍零 IDE 服务依赖；落库接线做成**顶层函数** `recordMcpExecution`（与 `mcpHistoryEntry` 同在 McpToolsetSupport.kt 门面类）——平台测试可直调，不必加载抽象基类（其 mcpserver 接口来自可选插件，McpDbExecutor.drainWarnings 同款先例）。取服务用 `project.getService`（Kotlin 类无平台生成的 `getInstance` 静态，首版误用致编译失败，已订正）。**口径限定（独立复核轮订正）**：「成功与失败两条路径各回调一次」不覆盖 fatal——`McpDbExecutor.kt:180-181` 的 fatal 分支**跳过审计钩子**（`FatalErrors.isFatal` 命中即原样透传，不构造 `ExecutedOutcome`、不落历史），这是 T-FP-03 守卫（`catch (…: Throwable)` 块体必须含 `FatalErrors.isFatal(`）强制的结果，不是自选口径 |
| T-MAT-02 | `2a1bf53` | 入 `rawSql`/`restoredSql` 的是 `selectSql.trim()`；EXPLAIN 包装句只经 `outcome.executedSql` 暴露且 ExplainTool **显式不消费**（KDoc 写明勿落）。「已 EXPLAIN 包装」的独立标记由 `SqlSource.MCP` 承担（该来源仅 explain 通道产出）。**未新增 dialect 字段**：新增构造参数会改 data class equals（T-LogRowNumber 的 sequenceNumber 教训），且读端回填被并行占用文件阻塞 → 零可观测收益，故不做 |
| T-MAT-03 | `e8ce911` | pd 短键 = **`s`**（值 = 枚举名而非序号：新增常量不使旧文件失效）；`DecodedPayload` 增 `source: SqlSource?`（缺键/未知枚举名 → null，只丢来源标记不丢整条）。`SqlLogAutoSaver` pd 条件追加 **`entry.source == SqlSource.MCP`**——刻意**不**放宽为 `!= UNKNOWN`（否则给既有 MYBATIS/AGENT 条目凭空加 pd 行，读端口径被动扩张）。**读端未接**（SqlLogFileReader 被并行任务占用）：见 §九 |
| T-MAT-04 | `2a1bf53` | 顶层 `executionArgsSummary`（dataSource/dialect/sql 三段，空白回落 `auto`，换行压平）；`McpAuditLog.clip`/`SUMMARY_LIMIT` 改 internal 以便断言。**截断实测**：长 SQL 样例（三段全量 **562 字符**）经 `clip` 后 **121 字符**（= 120 + 省略号，McpAuditLogTest 钉住；计划 §五 走查 3 的「不超 120」由本函数保证）。其余九个纯静态工具保持字面量默认值 → 审计行逐字节不变（§验收 4）；预期错误路径由「只放错误文本」改为「入参摘要 + `error=<文本>`」（**限定**：九静态工具信息不减；`sqlv_explain` 长 SQL 场景下 error 文本此前可能被截——SQL 段 >80 字符起截、≥113 字符整段消失，本轮已修，见 §九 ⑧） |
| T-MAT-05 | `c51b505` | **日志页是否同步的核实结论：是**——日志页 `LogEntryListCellRenderer`（SqlLogPanel.kt）与历史页 `HistoryCellRenderer`（HistorySqlLogPanel.kt）渲染的是**同一模型** `LogSqlEntry`，故两页都接 `formatMcpFragment`（落点：目标库角标后、来源前，与既有角标同款协议）。历史页元信息行自建、不调 `formatSourceFragment`，故两处各自显式插一行，顺序与协议对齐避免两页不一致 |
| T-MAT-06 | 本提交 | **门禁读数**：`testShards` **5196/0/0/6**（= 交付基线 5176 + 本任务新增 20 例精确吻合：codec +4、autosaver +2、McpAuditLogTest +6、McpHistoryIngestTest +4、两渲染器各 +2）、`testDatabase` **10/0/0/0**、agent **58/0/0**（首跑 1 例墙钟 flake，见 §九）。定向读数：SqlLogParamCodecTest 14/0/0/0、SqlLogAutoSaverTest 25/0/0/0、McpAuditLogTest 6/0/0/0、McpHistoryIngestTest 4/0/0/0、SqlLogPanelRendererUiTest 33/0/0/0、HistoryCellRendererTest 26/0/0/0。**先红后绿实录**：①用例 3（MCP 无参无占位符仍写 pd 行）在条件未加 `source == MCP` 时必红——该条是 T-MAT-03 配套必改的专属判据；②`summarize` 消费点守卫（源码级）在 ExplainTool 未接线时必红——纯行为断言测不出「没人调用」，故按 T-ConsentTruth 口径补源码级守卫。本轮两条断言均随实现一次落绿（未留红态提交） |
| T-MAT-07（合流轮补记） | `cc6b152` + `ffb4b58` | **并入 master 与读端补齐**。① **合并**：`git merge --no-ff t-r8-mcp-audit-trail`，冲突 2 处——`mcp/ExplainTool.kt` 与 `mcp/McpDbExecutor.kt`，均按语义取并集：ExplainTool 保留 T-McpRowBudget 的 `maxRows` 形参 + `clampMaxRows` 钳制 + `format(raw, target.maxRows)`，同时接本任务的 `audit(..., executionArgsSummary(...))` 与 `executeExplain(target, selectSql)` 原始语句透传；McpDbExecutor 的 `executeQuery` KDoc 两段并存（行预算兜底钳制 + `onExecuted` 出口钩子）。派发方预期的冲突点 `mcp/McpToolsetSupport.kt` 登记表 KDoc 实际由 git **自动合并**，已复核两处改动同时在位（「行默认 ≤200 / 上限 1000」与 sqlv_explain 审计入参说明）。② **读端回填**：`log/SqlLogFileReader.kt` `parseBlock` 两条头部分支各加一行 `source = decodedExt?.source ?: SqlSource.UNKNOWN`（新版分支落 `executionTimeMs` 之后，旧版 F-43 分支落 `timestamp` 之后；`SqlSource` 与本文件同包，无需 import）。③ **合并后新暴露的两处门禁红**（均非读端改动引起，见 §九 ⑦）：fatal 捕获守卫命中 `McpDbExecutor.executeQuery`、去重用例受同 JVM 前置类的项目服务级状态污染。④ **门禁读数**：`testShards` **5261/0/0/6**、`testDatabase` **10/0/0/0**（5 类 10 例）。定向读数：SqlLogHistoryRoundTripTest **12/0/0/0**（合流前 9 + 本轮 3）、HistoryCellRendererTest **27/0/0/0**（合流前 26 + 本轮 1）。**先红后绿实录**：读端补两行前三条新用例全红——`expected:<MCP> but was:<UNKNOWN>` ×2（新版分支 round-trip、旧版头部分支）、`读回条目来源应仍为 MCP: UNKNOWN` ×1（渲染链），补后两文件 **39/0/0/0** 全绿 |
| T-MAT-04 回退修复（独立复核轮） | `8501730` | **问题**（独立验证者实测 + 代码推演）：`McpAuditLog.call` 对整串 `<摘要> error=<文本>` 施加 120 字符 clip，入参摘要（`dataSource=… dialect=… sql=…`，头 33 字符）先占满预算 → `sqlv_explain` 的 SQL 段 >80 字符起错误文本被截、≥113 字符**整段消失**；九静态工具因 `ok ` 前缀只占 4 字符，实质信息不减。**修法**：`mcp/McpAuditLog.kt` 新增顶层 `errorArgsSummary`（两段分预算：错误文本优先、摘要保底 16 字符、错误文本短时余量还给摘要、总长仍 ≤120），`AuditTicket.expectedError` 改调它；OK 路径零改动。**不取「error 段前置」**的理由与未改项见 §九 ⑧。**先红后绿**：`./gradlew test --tests "cn.song.mybatisplus.visualizer.mcp.*"` 红 **104/6**（错误路径 6 例全红，报文里审计槽位只剩 `dataSource=demo dialect=MySQL sql=…`，92 字符错误文本整段不在）→ 绿 **104/0/0/0**（McpAuditLogTest **11/0/0/0**，改造前 6 例 + 本轮 5 例） |

## 九、实施小结：偏离、阻塞与未验证项

**① 阻塞项（须派活方协调，我未动该文件）**

`log/SqlLogFileReader.kt` 被并行任务 T-FatalPassthrough 占用，按派发指令排除。需要改的是**两处各一行**：

- `parseBlock` 新版头部构造 `LogSqlEntry(...)`（约 206-215 行区间）加 `source = decodedExt?.source ?: SqlSource.UNKNOWN`；
- 同函数旧版头部分支（约 227-234 行区间）同样加一行。

不改这一行的后果：`.sql` 读回后来源恒 `UNKNOWN` → **§验收 1 的「重启后徽标仍在」与 §验收 2 的「round-trip 不退化」无法成立**（写入侧与 pd 载荷侧已就绪，且有用例钉住载荷层 round-trip）。徽标之外，耗时、SQL 正文、成败与错误文本在重启后**都还在**，只有来源标记退化——即本任务的可见损失面仅限「MCP」二字角标。修掉这两行后无需再改任何写入侧代码。

**② 写集偏离（已实施，须派活方确认）**

`mcp/McpDbExecutor.kt` 与 `mcp/ExplainTool.kt` 不在派发写集清单内，但计划 T-MAT-01 明确要求「McpDbExecutor 暴露 `onExecuted` 钩子」+「ExplainTool 侧接线到既有入库路径」——不动这两个文件则本任务无任何交付物（落历史根本不会发生）。二者**不在并行占用排除名单内**，改动形态为：新增一个可选参数 + 一处具名实参 + 执行本体搬进 private 函数（对既有调用点行为零变化）。其余写集内文件（`SqlLogPanel`/`HistorySqlLogPanel`/`SqlLogParamCodec`/`LogSqlModels`/`SqlLogAutoSaver`/`McpToolsetSupport`/`McpAuditLog`）按计划改动；`LogStatementClassifier.kt`、`SqlListTypeColor.kt`、`execute/SqlTypeClassifier.kt`、`history/SqlHistoryEntry.kt` 四个写集文件**未改**——计划 T-MAT-02 选择「落原始 SQL」路线后，染色链无需任何改动（落包装句才需要，而那条路被 §一 硬约束 2 否决），故保持原样是有意为之而非遗漏。

**③ 未做（越界或被禁）**

CHANGELOG `[Unreleased]`、`docs/变更记录.md`、盘点文档、第八轮报告 §8 #38 落地备注——派发指令明确禁改后两者；其余共享文档为多路并行合并冲突热点且不在写集，统一留给派活方在并轨时登记。

**④ 已知边界（设计取舍，非缺陷）**

MCP 条目经 `buffer.addEntry` 落库 → 三条既有门控同样生效：用户点过「停止捕捉」（`paused=true`）期间不落历史、去重窗口内同 SQL 折叠、过滤关键字命中丢弃。三者都是「SQL 列表」的既有语义，本任务不另开口子；每次调用仍有 `[MCP-AUDIT]` 行兜底留痕（真机走查 3 可查）。若派活方认为 MCP 执行必须绕开 `paused`，需要另立任务（改 `SqlLogEntryBuffer` 不在本任务写集）。

**⑤ 未验证（环境受限）**

`sqlv_explain` 的**真实执行**（需 Database 插件 + 已保存密码的数据源 + 真库）本机不可自动化 → §五 真机走查四项全部留人工。本任务测试覆盖的是「执行之后」的落库链（条目构造 + 入既有入库路径）、载荷/徽标/审计面；`onExecuted` 回调在成功/失败两条真实路径上的触发只能由真机走查确认。

**⑥ agent 门禁 flake（与本改动无依赖关系）**

首跑 `:sql-visualizer-agent:test` 报 **58/1/0**，失败用例 = `StackCaptureConvergenceTest.缓存命中路径不再全栈建池_深栈计时防回潮`，报文为墙钟断言「命中应显著快于 miss（hit=436900ns, miss=1091000ns）」（本机同时有 4 路并行会话在跑 gradle，负载抖动）；单类复跑 **8/0/0/0** 转绿。agent 子项目（ASM 字节码增强 fat jar）与本次改动零依赖，判为负载下的时序抖动。

**⑦ 合流轮收口（读端补齐 + 合并集成，`cc6b152` / `ffb4b58`）**

**① 阻塞项已闭合**：`log/SqlLogFileReader.kt` 两处各加一行（落点见 §八 T-MAT-07）。用 round-trip 用例先红后绿钉住三条口径：MCP 条目写盘 → 读回来源仍 `MCP`（新版头部分支）；旧版 F-43 头部分支下挂 pd 行同样回填（防「同一文件内徽标时有时无」——头部形态与 pd 行是否在场互相独立）；非 MCP 条目读回仍 `UNKNOWN`（钉住「不做未立项的读端口径扩张」）。徽标链的端到端半边（写盘 → 读端 → 渲染器出 `[MCP] `）落在 `HistoryCellRendererTest`（平台型用例）。至此 §验收 1「重启后徽标仍在」与 §验收 2「round-trip 不退化」在自动化门禁内成立。

**合并后新暴露的两处红**（均非读端改动引起）：

1. `BannedApiSourceScanTest.catch_Throwable块必须判fatal或登记豁免` 命中 `mcp/McpDbExecutor.kt`——本任务的 `catch (e: Throwable)` 块写于 T-FatalPassthrough 合入 master **之前**，分支自测时该守卫尚不存在，合流后才可见（分支读数 5196/0/0/6 不含这条判据）。按守卫口径补判定：先解包 `InvocationTargetException` 再判 `FatalErrors.isFatal`（与同文件 `errorTextOf` 同款解包），fatal 不投审计钩子直接透传——内存/链接已损坏时不再构造 `ExecutedOutcome` 与落历史；非 fatal 路径行为逐字节不变（仍投钩子后原样冒泡）。
2. `McpHistoryIngestTest` 去重用例红（`expected:<2> but was:<3>`，分片并行时出现、单类跑绿）。根因是**测试隔离**而非产品行为：`SqlConsoleBridgeFilterProviderTest.setUp` 把**项目服务级** `SqlLogManager` 的 `dedupeEnabled` 置 false，其 tearDown 只清条目不复位开关；light 测试项目在同一 JVM 内跨测试类复用 → 同片（同 JVM）先后顺序不同即复现/不复现。已复现定案：`./gradlew test --tests SqlConsoleBridgeFilterProviderTest --tests McpHistoryIngestTest` 必红。修法取「用例自证前提」——本任务用例内显式 `dedupeEnabled = true`（与 `SqlLogCaptureRealtimeScenarioTest` 既有风格一致）。**未改** `SqlConsoleBridgeFilterProviderTest`（不在本轮写集）：该类的状态泄漏仍在，若后续再撞同类红，建议单独登记「项目服务级测试状态复位」小任务。

**未验证 / 未宣称**：真机走查四项仍留人工（同 ⑤，本轮只验证自动化门禁与定向用例）；**分片组成由历史 XML 时长权重推导**（`mpv-testing.gradle.kts` 的时长均衡分区），并行跑时类间顺序不稳定——本轮实测同片顺序变化会让上述隔离缺陷时红时绿，故不宣称「分片顺序无关」，也不宣称 `testShards` 总数为稳定常量。`testShards` 本轮读数 5261 相对分支读数 5196 的差值中，可逐条核对的只有本轮新增 4 例（SqlLogHistoryRoundTripTest +3、HistoryCellRendererTest +1，两文件类级读数 12/27 可见）；其余来自 master 侧波 1 四路（MRB/FPT/PC2/XDH）合入的用例，本轮未跑「合流前 master」的隔离对照，未逐条溯源。本轮 `testShards` 首跑（读端回填后、两处集成红修复前）报 2 类红——即上述两处，与 §验收 面无关；修复后复跑 **5261/0/0/6**。

**⑧ 回退修复：错误路径的审计入参段（独立复核轮，`8501730`）**

独立验证者实测 + 代码推演查出 T-MAT-04 的一处**信息回退**：`McpAuditLog.call` 对整串 `<摘要> error=<文本>` 施加 120 字符 clip，而改造前该槽位只放错误文本（错误文本独占 120 字符）。组合串长度 ≈ `33 + sqlLen + 7 + errLen`，于是 `sqlv_explain` 的 SQL 段 >80 字符时错误文本开始被截、≥113 字符时**整段消失**（长 SQL 恰是最需要看错误原因的场景）；九个静态工具因 `ok ` 前缀只占 4 字符，实质信息不减。

修法取**两段分预算**（`McpAuditLog.kt` 新增顶层 `errorArgsSummary`，`AuditTicket.expectedError` 改调它）：错误文本优先占预算，入参摘要让位但保留 16 字符保底（数据源标识与字段格式不消失），错误文本短时余量全部还给摘要，总长仍 120 封顶（上限未取消——那是防日志爆量的）。**不取「error 段前置」的理由**：前置让长错误文本把入参摘要整段挤掉（审计行退化成只有 `error=`，T-MAT-04 补入参的意义在长 SQL 场景同样落空），而分预算下两段都不会归零。OK 路径逐字节不变（仍走 `McpAuditLog.clip`，未改一行）。

**未改**：错误文本内嵌的换行（`ExplainTool` 失败路径的 `\nerrorPosition:` 段）仍原样落审计行——改造前即如此，不在本次回退面内。

**⑨ 项目服务级测试状态复位（T-TestIsolation，接手 §九-⑦-2 登记的泄漏）**

§九 ⑦-2 记的是「`SqlConsoleBridgeFilterProviderTest` 改项目服务级 `dedupeEnabled` 不复位」，处置取「受害者自证前提」（`McpHistoryIngestTest` 用例内显式置 `dedupeEnabled = true`），污染源本身留待另立小任务。本轮接手该小任务，实测下来这一族的**污染源不止一个**，且本批新增用例已经把潜伏泄漏暴露成门禁红。

**机制（先说清楚为什么这类红总是「本类隔离跑绿、分片跑红」）**：light 测试项目在同一分片 JVM 内**跨测试类复用同一个 `ProjectImpl`**（`LightPlatformTestCase.ourProject` 片内只建一次，`doSetup` 每用例只 `setLightProjectName` 改名），因此 `project.getService(SqlLogManager::class.java)` 拿到的是**全片共享的单例**；`SqlLogManager` 在项目关闭时才 `dispose`，片内不会。任何测试类改它的可变状态而不复位，都会串台到同片后续测试类——是否发作取决于分片时长权重算出来的邻居（新增测试类即重排，见 memory「分片重平衡型顺序依赖假失败」）。

**修前红（未改任何代码，`./gradlew testShard2`）**：该轮 shard2 = 149 类，1 例红 —— `log.SqlConsoleBridgeFilterProviderTest.test非捕捉态_零处理_不置位`，`junit.framework.AssertionFailedError` at `SqlConsoleBridgeFilterProviderTest.kt:43`（`assertEquals(0, manager.entries.snapshot().size)`）。该片执行序里三处相关类的落位是 `config.SectionConfigurableAutoCaptureLinkageTest`(idx 0) → `mcp.McpHistoryIngestTest`(idx 1) → … → `log.SqlConsoleBridgeFilterProviderTest`(idx 51)：片内唯一会往共享 buffer 入队条目的是 `McpHistoryIngestTest`（`recordMcpExecution` → `buffer.addEntry`，其自身用例断言 `before + 1 == entries.size` 即在共享实例上成立），而受害者首个执行的用例断言 buffer 恰为空。

**顺序无关的因果钉住（临时探针，不入库）**：新增临时类 `model.ProjectServiceLeakProbeTest`（字典序落在 `mcp.*` 之后，故它看到的就是 log/mcp/config 邻居跑完后的共享实例），断言共享 buffer 无残留、`dedupeEnabled=true`、`paused=false`、`capturing=false`；把守卫 [restore] 临时短路后跑「四个污染类 + 探针」→ 红 `共享 buffer 应无残留条目 expected:<0> but was:<2>`（2 条正是 `McpHistoryIngestTest` 入队的两条）；守卫生效同组合 → 绿。探针与短路均已删除。

**泄漏清单（全部为项目服务级 `SqlLogManager`，本轮全修）**

| 类 | 泄漏字段 | 本轮处置 |
|----|----------|----------|
| `log/SqlConsoleBridgeFilterProviderTest` | `entries.dedupeEnabled=false`（tearDown 只 `clear()` 内容，不复位开关） | 加守卫（setUp capture / tearDown restore） |
| `log/EpBridgeSinglePathTest` | 同上 | 加守卫 |
| `mcp/McpHistoryIngestTest` | 经 `recordMcpExecution` 入队条目后不清（本轮红的直接来源） | 加守卫（新增 setUp/tearDown） |
| `config/SectionConfigurableAutoCaptureLinkageTest` | 经 `apply()`→`onAutoStartSettingEnabled()`→`startCapture()` 在共享实例上置 `capturing`/等待态、解除 `paused`、并**启动控制台轮询** | 加守卫 |
| `log/agent/AgentListenSyncTest` | 无（用例自存自复原 `capturing`），但无守卫 → 邻居留下的脏捕获态会被本类当作初值再传下去 | 加固（加守卫） |

**修法**：① 新增 `testutil/SqlLogManagerProjectState.kt`——`capture(manager)` 存初值（buffer 的 `dedupeEnabled`/`paused`/`filterKeywords`/`maxEntries`/`dedupeWindowMs`/`errorBindWindowMs`/`onEntryStored`/`onEvicted` + 条目内容 + `capturing`/`userArmed`/等待态 + 自动展开三态 + 桥轮询态），`restore(manager, snapshot)` 原样复位；条目按**引用同一性**移除本类新增的那些（与 `removeEntry` 的同一性语义一致），捕获时未在轮询则把本类起的轮询/内容监听/已挂 filter 一并收回。② `SqlLogManager` 加 `internal fun restoreCaptureStateForTest(capturing, userArmed, waitingForConsole)` 测试缝——`userArmed`/`isWaitingForConsole` 只有 `private set`，经公开路径（`startCapture`/`stopCapture`）改不回初值；`stopCapture()` 不能当代用（它带 `PluginSettings.state.logAutoCaptureSuppressed = true` 的应用级副作用）。③ 五个类各自在 `setUp`（`super.setUp()` 后、任何改动前）`capture`、`tearDown`（`super.tearDown()` 前）`restore`；**未动任何用例语义与断言**。④ 复位边界已在 KDoc 写明：只回滚本类引入的差异——被本类 `clear()` 掉的既有条目无法复活，故各测试类仍应自清。

**同族审计（grep 全部 `src/test` 后逐个判定，除上表外均非泄漏，本轮不改）**：`log/SqlLogPanel*Test`（9 类）与 `SqlLogManagerBehaviorTest`/`SqlLogManagerAutoShowTest`/`SqlLogConsoleBridgeRealEnvTest`/`SqlClickPreviewTest` 等一律 `SqlLogManager(project)` **自建实例**（不进容器）→ 改的是私有实例，无共享面；`converter/WrapperConverterProjectScopeTest`（ProjectPluginSettings + PluginSettings 双快照，tearDown `loadState`）、`config/ProjectPluginSettingsTest` 与 `sql/DialectProbeCacheTest`（finally `loadState`；后者另有静态缝 `DataSourceUrlProbe.probeForTest`/`probeCount` 在 setUp/tearDown 复位）、`sql/rollback/RollbackSnapshotStoreTest`、`ui/RollbackSqlPanelTest`、`ui/RollbackSqlDialogTest`（`clearForTest()`）均已自清；`integration/PreviewEndToEndTest`、`psi/jpa/HbmNamedQueryResolverTest` 只读 `SqlPreviewService` 不改状态。**只登记未修一项**：`util/PluginCacheManagerCoverageTest.testClearAllDoesNotInstantiatePreviewService` 的前置 `assertNull(getServiceIfCreated(SqlPreviewService::class.java))` 是**脆弱前置**而非污染源——`SqlPreviewService` 一旦被同片更早的类（`PreviewEndToEndTest`/`HbmNamedQueryResolverTest`）创建即红；本轮分片恰好把两者分到不同片（前者 shard3、本类 shard1）故未发作。建议改法（未做，避免动他人用例断言）：把前置换成「记录调用前实例，断言调用后仍是同一实例」，语义不变而去掉顺序依赖。

**修后读数**：`./gradlew testShards` 一命令全绿 —— shard1 **1591/0/0/6**、shard2 **1702/0/0/0**、shard3 **2069/0/0/0**，合计 **5362/0/0/6**（`BUILD SUCCESSFUL in 6m53s`，三片墙钟 206.5s/190.9s/243.5s）；`./gradlew testDatabase` **10/0/0/0**（5 类）；`:sql-visualizer-agent:test` **58/0/0/0**。三步证伪本轮**未用到**（无残留失败）。

**未验证 / 未宣称**：① 修后那轮的分片归属与修前红那轮**不同**（权重表随 XML 更新，shard2 由 149 类变 148 类，三处相关类被拆到 shard1/shard3），故**未在同一份分片组成上做红→绿对照**；红→绿的因果由上面那条顺序无关探针钉住，不宣称「同一分片组合下已复现转绿」。② 前一轮（本日 17:04 那次全量）另有 5 例 `AlreadyDisposedException: Container is already disposed`（`SectionConfigurableAutoCaptureLinkageTest` ×2、`AgentListenSyncTest` ×1、`McpHistoryIngestTest` ×2，均抛在 `project.getService(...)`）——属 memory 已登记的「分片重平衡型顺序依赖假失败」形态；本轮把该形态的**同族嫌疑面**（共享实例的捕获态与桥轮询泄漏，`SectionConfigurableAutoCaptureLinkageTest` 正是往共享实例上起轮询的那条路径）一并复位了，但**未单独复现**该形态（用同一批类名与顺序在 `test` 任务下复跑为绿），故只登记不宣称已根治。③ 分片组成由历史 XML 时长权重推导，类间顺序不稳定，本轮不宣称「分片顺序无关」。

