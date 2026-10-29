# T-DialectInferFix 预览「智能推断」被非 SQL 数据源与日志还原链规避 — 开发计划

> 来源：用户 2026-10-29 真机报障（日常 IDEA 2026.2.1，项目 `cyzc-platform`）：SQL 预览把一段合法 SQL 标红
> 「应为 `<expression>`, 得到 `'signal'`」，同时设置页方言下拉已是「智能推断（跟随数据源）」。
> 基线：`master` @ `2ac39a95`。本册只动 `docs/`（工作树另有他会话在途改动，不回填、不 `add -A`）。
> 状态：**已实施**（2026-10-29，分支 `feature/dialect-infer-fix`；D1~D3 定案见 §十，实施记录见 §十一）。

---

## 一、结论

用户问「是智能推断没有生效吗」——**是，但根因不在推断链的入口，而在两处「把结论丢掉」的地方**：

1. **候选投票被非 SQL 数据源搅黄**。该项目有两个 IDE 数据源：`cyzc_db1`（PostgreSQL）与 `1@121.40.45.91`（Redis）。
   `DataSourceUrlProbe.probeCandidateDialects` 把 `DataSourceManager.getDataSources()` 的**全量**数据源都当方言候选，
   Redis 解析不出方言 → 产出 `Candidate(id, null)`；`DialectDecisionEngine.fromCandidates` 把 null 也算进
   「不同方言」集合 → `AMBIGUOUS` → 按既有设计 fail-closed 到 MySQL 兼容兜底。
2. **日志/历史双击预览这条路根本不带方言结论**。`LogSqlEntry.toPreviewResult` 不填 `renderedDialect` /
   `dialectDecision`，于是 `PreviewResult.effectiveDialect == null`：渲染器按 `byName(null)` 落 MySQL，
   `syncPreviewDialectMapping` 把预览临时目录映射成 MySQL（还写进了项目共享的 `.idea/sqldialects.xml`）。
   本次报障的 SQL 正是从 SQL 日志条目打开的（界面形态：头部带「调用栈」，只有 agent 条目有栈快照）。

两处叠加的结果：**一个纯 PostgreSQL 项目里，插件按 MySQL 生成文本、按 MySQL 解析 PSI**，而平台 MySQL 保留字表里有
`MYSQL_SIGNAL`、PostgreSQL 没有 `signal`——列名 `signal` 在真库完全合法，标红是方言错配的假红。

---

## 二、现场证据（全部可复现，按取证手段分组）

| # | 证据 | 取法 | 读数 |
|---|---|---|---|
| E1 | 项目数据源构成 | `cyzc-platform/.idea/dataSources.xml` | `cyzc_db1` → `jdbc:postgresql://121.40.45.91:5432/cyzc_db1`；`1@121.40.45.91` → `jdbc:redis://121.40.45.91:6379/1` |
| E2 | 平台数据源枚举**包含 Redis** | MCP `sqlv_schema_context`（不存在的表，触发诊断行） | `scanned: ds=cyzc_db1@121.40.45.91 targetDb=cyzc_db1 modelTables=339 …; ds=1@121.40.45.91 targetDb=1 modelTables=0` |
| E3 | 项目级推断结果当前是 MySQL | MCP `sqlv_perf_advice`（`SELECT "id" …`） | `[WARN] dialect_quote_mismatch: … dialect MySQL does not accept it by default`（该规则只在方言为 MySQL 族时触发，null 时不判） |
| E4 | 决策状态是 AMBIGUOUS | 设置页状态行（截图 3） | 「项目候选方言不一致；预览兼容按 MySQL，执行前须核对目标」＝ `settings.dialect.inferred.ambiguous` |
| E5 | 预览目录被映射成 MySQL 并落盘 | `cyzc-platform/.idea/sqldialects.xml`（mtime `2026-10-29 10:06:34`） | `<file url="file://$PROJECT_DIR$/.idea/sql-visualizer/preview" dialect="MySQL" />` |
| E6 | 平台确实对预览文件做了 PG→MySQL 语言替换 | `idea.3.log:12035` | `2026-10-29 10:05:51,803 … Reparsing …/preview/mpsql-preview-40168-1.sql because of language substitution PostgreSQL->MySQL` |
| E7 | `signal` 是 MySQL 保留字、不是 PG 保留字 | `javap -cp intellij.database.dialects.mysqlbase.jar com.intellij.sql.dialects.mysql.MysqlReservedKeywords` / 同法取 `PgReservedKeywords` | MySQL 侧有 `MYSQL_RESIGNAL` / `MYSQL_SIGNAL`；PG 侧零命中；两侧 Optional 表均无 `signal` |
| E8 | 引擎把「未知方言候选」当冲突项 | `sql/DialectDecisionEngine.kt:98-118`（`names.toSet()` 含 null → `distinct.size > 1` → AMBIGUOUS） | 与 `IdeaSqlDialectDecisionTest.kt:79-87`「known and unknown candidates cannot be promoted to false consensus」一致——**这是有意语义** |
| E9 | 候选来源是全量数据源 | `sql/DataSourceUrlProbe.kt:147-173` | 每个 `getDataSources()` 元素都进 `Candidate(id, UrlSubprotocolDialects.dialectName(url))`，Redis 得 null |
| E10 | 日志预览结果不带方言 | `log/LogEntryPreviewRecognizer.kt:122-130`（`toPreviewResult` 各分支构造 `PreviewResult` 时无 `renderedDialect`/`dialectDecision`） | `PreviewResult.effectiveDialect`（`model/PreviewResult.kt:149`）为 null |
| E11 | null 方言 → 强制写 MySQL 映射 | `ui/SqlPreviewPanelEditorLifecycle.kt:370-401`（`desiredName == null` → `targetId = MYSQL_LANGUAGE_ID`）→ `execute/DialectPreMapping.kt:148-190`（`targetLanguage == null` → `setMySqlOnEdt`） | 与 E5/E6 完全吻合 |
| E12 | 面板不披露方言 | `ui/SqlPreviewPanelResultView.kt:574`（`currentDialectDecision = result.dialectDecision`，日志预览为 null → 无方言段） | 截图 1 头部只有「调用栈 / 数据源（上次使用）」，没有方言段 |
| E13 | O-145 设计的「精确 JDBC URL」证据链是死代码 | 全仓 `grep capturedJdbcUrl` | 只有 `context/PreviewSourceContext.kt:21-22` 的声明与 KDoc，**0 处读取** |
| E14 | 该列在真库里真实存在且是 PG 类型 | MCP `sqlv_schema_context`（`biz_car_device_info`） | `signal integer -- 信号强度 0-100`（同表 27 列，`dic_status smallint` 等与截图 SQL 一致）——SQL 本身合法，标红纯属方言错配 |

> 补一条对照组：同一台机器、同一插件，纯 PG 项目不会出这个问题（没有第二个数据源，候选集只有 PostgreSQL）。
> 这解释了为什么此前多轮真机走查没撞到——本缺陷的触发条件是「项目里同时存在非 SQL 数据源」。

---

## 三、根因链（四层，逐层可独立修）

**L1 候选污染**（触发点）
`probeCandidateDialects` 不区分「SQL 数据源」与「Redis/Mongo/DDL 这类根本不是 SQL 目标的数据源」，全部投进候选。

**L2 设计内 fail-closed**（放大点）
`DialectDecisionEngine` 的「未知候选不得宣称共识」是对的（`IdeaSqlDialectDecisionTest:79-87` 专门锁住），
但它的前提是「未知候选**有可能是**本次 SQL 的目标」。Redis 永远不可能是 SQL 预览的目标，前提不成立。

**L3 日志还原链丢方言**（本次报障的直接路径）
`LogSqlEntry` 明明带着 `databaseName`（`AgentEventAdapter.kt:62` 由 `event.url` 解析，形如
`PostgreSQL · cyzc_db1@121.40.45.91`，且已随 `-- … | db: …` 头持久化，`SqlLogAutoSaver.kt:500` / `SqlLogFileReader.kt:35-37,358`），
但打开预览时这条事实被丢掉，既不进 `PreviewResult` 也不进决策链。

**L4 兜底硬编码 MySQL + 写进项目共享配置**（后果放大）
未知方言时渲染与 PSI 都落 MySQL，且把目录映射写进 `.idea/sqldialects.xml`。用户之后即使把推断修对，
历史遗留的 MySQL 映射仍会压住新结论（见 P-6）。

---

## 四、影响面

同一根因（L1+L2）在本项目里影响的不止「预览标红」：

1. **Wrapper / XML / JPA 预览**：`DialectRegistry.decision(project, hostVirtualFile, …)` 非 PSI 分支同样落到项目候选 → MySQL。
2. **右键表生成 CRUD SQL**：`database/DbTableSpecAdapter.kt:55-63` 走 `currentDialect(table.project)` →
   `DialectRegistry.effective(project)` → MySQL。这正是 2026-09-23 修过一次的同类缺陷（当时是「不传 project」），
   现在换了一条路径复发：**在 PG 库上生成反引号标识符**，真执行必报语法错。
3. **分页 / 引号 / UPSERT / EXPLAIN 提交 / 方言闸门**：全部读同一个 `DialectRegistry.effective`，一并偏移。
4. **预览临时文件 PSI**：按 MySQL 解析，产生本册开头那类假红（还会连带补全、注释、未知列标注走错方言）。

不受影响：只配了一个 SQL 数据源的项目；手动指定方言的用户（`MANUAL` 优先级最高）。

---

## 五、待验证前提（实施前先证伪）

| 编号 | 前提 | 证伪方式 | 若不成立的影响 |
|---|---|---|---|
| **P-1** | `DataSourceManager.getQueryLanguage(ds)` 在 262/2026.2 可用，且对 Redis 数据源返回 `Redis`（非 SQL） | `javap` 已确认接口有该方法；需 runIde 沙盒或真机跑一次反射调用（打印每个数据源的 language id） | 退回用 `getDatabaseDialect(ds).getDisplayName()` 分类，再退 URL 子协议 |
| **P-2** | 反射取 `getQueryLanguage` 必须传 `RawDataSource` 类，传 `Object::class.java` 找不到方法 | `javap -p com.intellij.database.psi.DataSourceManager` 已确认签名 `getQueryLanguage(T)`（擦除后为 `RawDataSource`） | 直接用 `getMethod("getQueryLanguage", Class.forName("com.intellij.database.model.RawDataSource"))`；本仓已有同类「从声明接口取 Method」纪律（`DatabaseSchemaResolver.kt:49-56`） |
| **P-3** | agent 事件确实带 connect URL（券 02 的 EXACT 证据成立） | 真机跑一次应用，看日志条目角标是否为 `PostgreSQL · …`（有角标即有 URL） | 若 URL 缺失：券 02 降级为「只从已持久化的 `db:` 头回填」，桥通道条目仍靠项目决策 |
| **P-4** | ~~`getConfiguredMapping(previewDir)` 返回「项目/全局」继承值~~ **已证伪一半（实现级）**：`getConfiguredMapping` = pusher 持久值 → 目录/父目录显式映射 → **项目级（映射表 `null` 键，且仅当文件在内容根内）**；**不含全局**——全局由 `getDefaultMapping`（= `getConfiguredDefaultSqlDialect()`，用户设置页「全局 SQL 方言」）另行兜底，全无配置时 = GenericSQL | fernflower 反编译 `PerFileMappingsBase` / `SqlDialectMappings` / `SqlDialectImplUtilCore` / `SqlDialectPusher` 读实现（记录见 §十一） | **已按此修正**：`inheritedLanguageId` 就是「项目级（`getConfiguredMapping` 读项目根）→ 全局（`getConfiguredDefaultSqlDialect`）」，与平台语义一致，不需改策略 |
| **P-5** | 清掉插件自己写的目录映射后，平台会回到继承语义（不会缓存旧语言） | **已证（实现级）**：`setMapping(file, null)` → `myMappings.remove(file)` → `handleMappingChange`（清 pusher 持久值 + `pushAll` + `reparseFiles([file], includeOpenFiles=false)`）；之后 `getMapping` = configured ?: default | 不需「显式重写」兜底；`clearMapping` 仍保留 EDT 两段式（写映射必须 EDT）；`includeOpenFiles=false` 不影响未打开的预览临时文件 |
| **P-6** | 老用户 `.idea/sqldialects.xml` 里已有的 `preview → MySQL` 不会继续压住新结论 | 用当前工作树跑一次修复后的构建，观察映射是否被改写（E5 的现场即夹具） | 若不改写：券 03 增加「打开预览时按决策校正映射」的强制路径 |

---

## 六、实施步骤（券 01~06）

> 依赖：01 独立；02 独立；03 依赖 01+02（要有正确结论才谈兜底）；04 依赖 02+03；05 依赖 02；06 依赖 01~05。

### 券 01 — 候选只来自 SQL 数据源（修根因，不动引擎语义）

**目标**：Redis/DDL/文档型数据源不再参与方言投票；真 SQL 源但方言未知时仍保留「不宣称共识」。

1. `sql/DataSourceUrlProbe.kt`
   - 新增内部纯函数 `classifyDataSource(languageId: String?, dbmsDisplayName: String?, url: String?)` →
     `SQL_KNOWN(name) / SQL_UNKNOWN / NON_SQL`。判定顺序：平台 query language id → DBMS 显示名 → URL 子协议
     （`UrlSubprotocolDialects`）；命中 `redis/mongo/mongodb/cassandra/couchbase/dynamo/…` 归 NON_SQL。
   - `probeCandidateDialects`：NON_SQL **不产出候选**（可另记诊断计数，不进 `Decision.dataSourceIds`）；
     SQL_UNKNOWN 仍产出 `Candidate(id, null)`。
   - `probeFirstUrl` / `firstUrl`：跳过 NON_SQL（当前是「首个非空 URL」，Redis 排前面时会取到 redis URL）。
2. 测试：`classifyDataSource` 表驱动（PG/MySQL/Oracle/TSQL/Redis/Mongo/DDL/未知子协议/null）；
   探针注入（`probeCandidatesForTest`）覆盖「PG+Redis → 只剩 PG」。
3. 反向验证：临时把 Redis 重新计入候选 → 决策回到 AMBIGUOUS、设置页状态行回到「候选不一致」（贴两次读数）。

**必改**：`sql/DataSourceUrlProbe.kt`；**新增/改测**：`DialectProbeCacheTest` 或新建 `DataSourceUrlProbeClassifyTest`。
**注意**：`DialectDecisionEngine` 一行不改——「未知候选不得宣称共识」有专测锁定，是安全语义。

### 券 02 — 日志/历史预览携带本次执行方言（接 O-145 第 2 条证据）

**目标**：从 SQL 日志/历史打开预览时，用「这条 SQL 当时连的那个库」定方言，`Source.CAPTURED_URL / Confidence.EXACT`。

1. `log/LogSqlModels.kt`：`LogSqlEntry` 增 `dialectName: String?`（`DialectRegistry` 命名空间，不是显示名）。
   照 `databaseName` 先例：进主构造 + `mergeWith` 里 `existing.dialectName ?: incoming.dialectName`。
2. `log/agent/AgentEventAdapter.kt`：`dialectName = UrlSubprotocolDialects.dialectName(event.url)`（与角标同源 URL，不同命名空间）。
3. 持久化：`log/SqlLogParamCodec.kt` 增键（如 `"dl"`，仅非空落键，旧 payload 解码 null）；
   `log/SqlLogAutoSaver.kt` 落键条件与 `log/SqlLogFileReader.kt` 解码同步；`SqlLogHistoryRoundTripTest` 补一条往返断言。
4. 老条目回填：`databaseName`（`-- … | db: <方言显示名> · …`）解析出显示名前缀 → 映射到注册名
   （`JdbcUrlDialects` 的显示名与注册名在 MySQL/PG/Oracle/SQL Server/H2/SQLite/MariaDB/DM/KingBase/OceanBase/openGauss
   上同名；`HSQL`/`GBASE` 无注册方言 → 忽略）。
5. `log/LogEntryPreviewRecognizer.kt`：`toPreviewResult(formatSql, literalRecovery, capturedDialect)`，
   命中时填 `renderedDialect = DialectRegistry.byName(capturedDialect)` +
   `dialectDecision = Decision(RESOLVED, name, EXACT, CAPTURED_URL, dataSourceIds = [])`。
6. 两个入口传参：`log/SqlLogPanel.kt:1994-1999`、`log/HistorySqlLogPanel.kt:892-909`（`buildPreviewResult` 是 internal，测试可直断）。
7. 效果：`showResult` → `currentDialectDecision` 自动有值 → meta 行出现「方言: PostgreSQL（运行期连接）」。

### 券 03 — 兜底不再硬编码 MySQL；渲染与 PSI 映射同源

**目标**：任何路径下，「渲染用的方言」和「预览临时文件映射的方言」取同一个值；未知时**继承**而不是冒充 MySQL。

1. 新增单一入口（建议 `sql/PreviewDialectResolver.kt`）：`resolve(project, capturedDialect, boundDataSourceDialect)`
   → `DialectDecisionEngine.Decision`，优先级：手动 > captured（券 02）> 绑定/上次使用数据源 > 模块学习 > 项目候选 >
   **平台继承**（P-4 已实现级证实：项目级 `getConfiguredMapping` → 全局 `getConfiguredDefaultSqlDialect` → GenericSQL；`Source.IDEA_DIALECT`、`Confidence.FALLBACK`）。
2. `ui/SqlPreviewPanelEditorLifecycle.kt:370-401`：`desiredName == null` 时不再落 `MYSQL_LANGUAGE_ID`——
   有继承值就按继承值映射；没有就**不写映射**（保持平台默认），并在返回的决策里标 fallback。
3. `ui/SqlPreviewPanelEditorLifecycle.kt:432-437`：`syncPreviewDialectMapping` 改为喂 `resolve(...)` 的结果；
   `PreviewResult.renderedDialect` 在同一处补齐（日志链由此获得与 PSI 一致的值）。
4. `ui/SqlPreviewPanelExecution.kt:690-695`：`executionDialectGateRequest` 的 MySQL 硬兜底改读同一函数（保留 null 防御，但不再静默称 MySQL）。
5. 迁移：已存在的 `preview → MySQL` 映射在目标方言已知时被改写（P-6），未知时按 P-5 结论处理（清除或保留 + 提示）。

### 券 04 — 面板与设置页披露口径统一

1. `ui/DialectPreviewStatusText.kt`：fallback 分支补「未判定原因」（候选不一致 / 无证据 / 已继承平台设置），
   并给出消歧动作（绑定数据源 / 手动指定）。
2. 设置页状态行（`config/PluginSettingsPanel.kt:1175-1178`）与面板同源同一 `decision`，避免「设置页说候选不一致、面板什么都不说」。
3. 面板在日志预览路径上必须出现方言段（券 02 已给值；补一条 UI 测试断言 `metaLineHead` 含方言段）。

### 券 05 — 死字段收口（O-145 欠账）

`context/PreviewSourceContext.kt:21-22` 的 `capturedJdbcUrl` / `selectedDataSourceId`：要么由券 02/03 真正消费，
要么删除。禁止保留「声明 + KDoc 承诺、全仓 0 读取」的假证据字段（E13）——它会让人误以为证据链已接线。

### 券 06 — 守卫与回归

1. 守卫 A：`classifyDataSource` 表驱动（含 Redis/Mongo/DDL/未知子协议）。
2. 守卫 B：日志预览必须带方言——`toPreviewResult` 对带 `databaseName` 的条目断言 `renderedDialect != null`。
3. 守卫 C：未知方言不得写 MySQL 映射——`mapPreviewDialectBeforePsi` 在 `desiredName == null` 时不得调用
   `setMySqlOnEdt`（源码扫描或行为用例）。
4. 反向验证三连（每条贴两次读数）：①Redis 回候选 → 决策 AMBIGUOUS；②去掉 capturedDialect → 面板显示 fallback
   且映射不写 MySQL；③恢复 `MYSQL_LANGUAGE_ID` 兜底 → 守卫 C 必红。

---

## 七、验证与收口

**定向测试**（实施子代理各自跑）：
`sql/DialectDecisionEngineTest`、`sql/IdeaSqlDialectDecisionTest`、`sql/DialectInferenceTest`、`sql/DialectProbeCacheTest`、
`sql/DialectRegistryIdeaContextTest`、`log/LogEntryPreviewRecognizerTest`、`log/HistorySqlLogPanelPreviewTest`、
`log/SqlLogHistoryRoundTripTest`、`ui/DialectPreviewStatusTextTest`、`ui/SqlPreviewPanelPrewarmConsumeTest`。

**全量门禁**：单路 `testShards`（5 片）一次；因动到 DatabaseTools 反射面（新方法 `getQueryLanguage`）与
`src/main` 公共决策入口，属大改动 → 追加 `verifyGate`。

**真机验收（cyzc-platform，用户日常 IDE）**：

1. 从 SQL 日志双击打开本次那条 `getDevice` 预览：头部显示「方言: PostgreSQL（运行期连接）」；`signal` 不再标红；
   平台不再出现 `PostgreSQL->MySQL` 的 Reparsing（除非确有目标切换）。
2. 设置页方言状态行变为 PostgreSQL 共识（不再是「候选不一致」）。
3. `.idea/sqldialects.xml` 的 `preview` 行变为 PostgreSQL，或按 D3 不再由插件写入。
4. 右键 `biz_car_device_info` 生成 CRUD SQL：标识符用双引号、分页用 PG 形态（对照组：修复前是反引号）。
5. 反向：临时把 Redis 计入候选 → 上述 1~4 全部回到 MySQL 兜底且 UI 明确写出原因（证明守卫有效，不是空绿）。

**文档同步**：券 03 引入公共入口后，同步 `docs/架构设计/02-*.md` 的方言决策链一节；本册实施完成后按规范产出
发行说明（未发布能力前的缺陷也按「修复」写）。

---

## 八、决策点

| 编号 | 决策 | 选项 | 推荐 |
|---|---|---|---|
| **D1** | 非 SQL 数据源的判定依据 | (a) 平台 `getQueryLanguage(ds).id`；(b) `getDatabaseDialect(ds).getDisplayName()`；(c) JDBC URL 子协议黑名单 | **(a) 主判 + (c) 兜底**：(a) 是平台自己的事实，(c) 不依赖反射面扩张；(b) 显示名跨版本不稳 |
| **D2** | 未知方言时的兜底 | (a) 继承平台项目/全局 SQL 方言（不写映射）；(b) 继续 MySQL 但在 UI 明示；(c) GenericSQL | **(a)**：用户本来就在设置里配了项目方言，插件不该覆盖；GenericSQL 会让补全/注解大面积失效 |
| **D3** | 历史遗留 `preview → MySQL` 映射 | (a) 打开预览时按当前决策校正（含清除）；(b) 只在决策为已知时改写；(c) 不处理只提示 | **(a)**，但先过 P-5：确认清除后平台能回到继承语义 |

---

## 九、明确不做

1. **不改** `DialectDecisionEngine` 的「未知候选不得宣称共识」语义——`IdeaSqlDialectDecisionTest:79-87` 专门锁定，
   是 fail-closed 安全设计；本次只修「谁有资格当候选」。
2. 不为 Redis/Mongo 提供 SQL 渲染方言（它们不是 SQL 目标，不该出现在方言证据里）。
3. 不改用户 `.idea/dataSources.xml`，不动 `update/update.json`（后者只有用户能改）。
4. 不在本册里处理「同一项目多 SQL 数据源且方言真的不一致」的消歧交互（那是 O-145 的既有设计，已由
   `AMBIGUOUS` + 绑定源信号覆盖）。

---

## 十、决策结论（实施时定案）

| 编号 | 定案 | 落地位置 |
|---|---|---|
| **D1** | (a) 平台 `getQueryLanguage(ds).id` 主判 + DBMS 显示名 + URL 子协议兜底；非 SQL 家族用**精确键**比对（不做前缀，避免 Redshift/前缀误伤） | `sql/DataSourceUrlProbe.kt` 的 `DataSourceDialectClassification` |
| **D2** | (a) 继承平台：决策链最后一层读「项目级 → 全局默认 SQL 方言」（`IDEA_DIALECT`/`FALLBACK`）；预览映射侧走 `DialectPreMapping.inheritedLanguageId`，未命中才退回 GenericSQL，**不再硬编码 MySQL** | `sql/Dialect.kt` 的 `withLowConfidenceEvidence`、`ui/SqlPreviewPanelEditorLifecycle.kt` 的 `inheritPlatformPreviewDialect` |
| **D3** | (a) 打开预览时按当前决策校正：`inheritedLanguageId` 读到继承值就显式改写本目录映射（覆盖历史 MySQL），读不到则 `clearMapping` 清掉旧插件映射 | `execute/DialectPreMapping.kt`（`clearMapping` / `inheritedLanguageId`） |

---

## 十一、实施记录（2026-10-29，分支 `feature/dialect-infer-fix`）

**券 01 — 候选只取 SQL 数据源**（`sql/DataSourceUrlProbe.kt`）
- 新增纯函数对象 `DataSourceDialectClassification`：`SQL_KNOWN / SQL_UNKNOWN / NON_SQL`，判定链 = 平台 query language id → DBMS 显示名 → URL 子协议；NON_SQL 家族（Redis/Mongo/Cassandra/Couchbase/Dynamo/Elastic/HBase/Neo4j/InfluxDB）用**精确键**比对。
- `probeCandidateDialects` 跳过 NON_SQL（`DEBUG` 留诊断行）；`probeFirstUrl` 同样跳过（Redis 排前面时首个 URL 不再被它占住）。
- 反射口径：`getQueryLanguage(T)` 的 Method 从**声明接口** `DataSourceManager` 取、参数类型传 `RawDataSource`（P-2 落实）。

**券 02 — 日志/历史预览携带本次执行方言**（`log/*`）
- `LogSqlEntry.dialectName`（注册方言名）新增，`mergeWith` 同步；`AgentEventAdapter` 用 `UrlSubprotocolDialects.dialectName(event.url)` 填。
- 持久化：`SqlLogParamCodec` 增 `dl` 键（仅非空落键）、`SqlLogAutoSaver` 落键条件、`SqlLogFileReader` 解码回填。
- `toPreviewResult(..., capturedDialect)`：注册名优先、旧条目退 `db:` 角标头（`PreviewDialectResolver.dialectFromBadge`，HSQL/GBASE 无注册方言则不猜），命中即写 `renderedDialect` + `dialectDecision(CAPTURED_URL/EXACT)`。
- 三个入口（`SqlLogPanel` / `HistorySqlLogPanel.buildPreviewResult` / `LogEntryPreviewOpener`）全部传 `entry.dialectName`。

**券 03 — 兜底继承、渲染与映射同源**
- 新增 `sql/PreviewDialectResolver.kt`：`resolve` / `knownDialect` / `dialectFromBadge` / `capturedDecision` + `PreviewResult.withResolvedDialect`；`showResult` 入口先补齐方言再渲染（`ui/SqlPreviewPanelResultView.kt`）。
- `DialectPreMapping` 增 `inheritedLanguageId`（项目根继承 → 全局默认）、`configuredImmediateLanguageId`、`clearMapping`（EDT 两段式）。
- `mapPreviewDialectBeforePsi`：`desiredName == null` 时走继承路径，**删掉 `MYSQL_LANGUAGE_ID` 兜底**；有继承值显式改写本目录映射（覆盖历史 MySQL），无值则清映射 + GenericSQL。
- `executionDialectGateRequest` 的 MySQL 硬兜底改为先读决策的 `compatibilityFallbackName`。

**券 04 — 披露口径统一**
- `DialectPreviewStatusText` / `DialectSettingsStatusText` 补 `PROJECT_CONFIG` / `PROJECT_DEPENDENCY` / `SQL_SYNTAX` / `IDEA_DIALECT` 来源文案；未判定时文案从「兼容按 MySQL」改为「沿用 IDEA 的 SQL 方言设置」。
- 双语 bundle 同步（`panel.meta.dialectStatus.*`、`settings.dialect.inferred.*`）。

**券 05 — 死字段收口**
- 删除 `PreviewSourceContext.capturedJdbcUrl` / `selectedDataSourceId`（全仓 0 读取），KDoc 说明事件级方言改由 `LogSqlEntry.dialectName` 承载。
- `BannedApiSourceScanTest` 的 O-145 证据边界规则同步到新符号（禁 `lastConnectionUrl` / 裸角标 / `JdbcUrlDialects` 冒充条目方言），扫描面扩到 `LogEntryPreviewRecognizer.kt`。

**券 06 + 增补券 — 守卫与「没有数据源」的聪明推断**
- 新增 `sql/ProjectDialectEvidence.kt`：配置文件 JDBC URL（`application*`/`bootstrap*`）→ 构建文件驱动坐标（`pom.xml`/`build.gradle(.kts)`）→ SQL 方言独占语法特征；三层全部要求「唯一共识」，多来源不一致不猜；单槽 TTL 30s + 项目弱引用；EDT 只读缓存；单元测试模式默认关闭真实扫描（`build.gradle.kts` 自带 `libs.postgresql`，否则会污染全量断言）。
- 决策链接入：`DialectRegistry.decision` 的 `withLowConfidenceEvidence` 依次尝试项目证据 → SQL 语法 → IDEA 方言设置；`CONFLICT` 保持阻断。
- 新增守卫 `convention/DialectInferFallbackGuardTest`：预览映射编排不得再出现 MySQL 硬兜底、`PreviewSourceContext` 不得长回死字段、三个打开入口必须传 `entry.dialectName`；每条规则都有合成样本自证。

**读数**
- `compileKotlin` / `compileTestKotlin`：BUILD SUCCESSFUL。
- 定向测试两批共 **163 例 0 失败**（首批 127：`DataSourceDialectClassificationTest`、`ProjectDialectEvidenceTest`、`PreviewDialectResolverTest`、`LogEntryPreviewDialectTest`、`LogEntryPreviewRecognizerTest`、`SqlLogHistoryRoundTripTest`、`DialectInferFallbackGuardTest`、`BannedApiSourceScanTest`、`DialectDecisionEngineTest`、`IdeaSqlDialectDecisionTest`、`DialectInferenceTest`、`DialectProbeCacheTest`、`DialectRegistryIdeaContextTest`、`DialectPreviewStatusTextTest`；第二批 36：`HistorySqlLogPanelPreviewTest`、`SqlPreviewServiceProjectSettingsTest`、`SqlPreviewPanelPrewarmConsumeTest`、`DialectStatusRefreshPolicyTest`、`DialectStatusRefreshWiringGuardTest`、`DialectSettingsStatusTextTest`、`DialectPreviewStatusTextTest`）。
- **反向验证（三个缺陷形态同时注入，一次跑红）**：①预览映射编排加回 `MYSQL_LANGUAGE_ID` → `DialectInferFallbackGuardTest > 预览映射编排不得硬编码 MySQL 兜底` **红**；②`SqlLogPanel` 改 `capturedDialect = null` → 同守卫 `日志识别通道必须把条目方言传下去` **红**；③`ProjectDialectEvidence.syntaxHint` 恒返回 null → `PreviewDialectResolverTest > sql syntax hint is the last resort…` **红**。读数：`14 tests completed, 3 failed`。撤回三处注入后复跑同批：`BUILD SUCCESSFUL`（绿）。
- 全量门禁：按用户 2026-10-29 指示**未跑**（"最后的全量门禁不用跑"）。
- **P-4 / P-5 实现级取证（2026-10-29，用户要求"现在做"）**：用 IDE 自带 fernflower（`plugins/java-decompiler/lib/java-decompiler.jar` + JBR 21）反编译平台 jar 的四个类，逐行读实现（不是只读签名）：
  - `PerFileMappingsBase.getMappingInner`：① 先 `pusherKey.getPersistentValue(file)`（VFS 上上次 push 的结果）→ ② `getMappingForHierarchy`（file → 父目录逐个 `getImmediateMapping`）→ ③ 原文件（`VirtualFileWindow` 委托）同样走 ② → ④ `getNotInHierarchy`：**只有文件在内容根内**（`ProjectFileIndex.isInContent`）才返回映射表 `null` 键（项目级），否则 null。
  - `PerFileMappingsBase.setMapping(file, null)`：`myMappings.remove(file)` → `handleMappingChange([file], [file], false)`：清 `SQL_DIALECT_KEY` 持久值 → `PushedFilePropertiesUpdater.pushAll(SqlDialectPusher)` → `PsiDocumentManager.reparseFiles([file], includeOpenFiles=false)`。
  - `SqlDialectMappings.getMapping` = `super.getMapping` = configured ?: `getDefaultMapping`；`getDefaultMapping` = `getDefaultSqlDialect()`，而 `SqlDialectImplUtilCore.getDefaultSqlDialect()` = `getConfiguredDefaultSqlDialect() ?: SqlDialects.getGenericDialect()`；`getConfiguredDefaultSqlDialect()` 读 `PropertiesComponent "DBE.Default.Dialect"`（设置页「全局 SQL 方言」）。
  - 结论：**P-4 部分证伪**（`getConfiguredMapping` 不含全局，全局走 `getDefaultMapping`）但**继承链成立**；我们的 `inheritedLanguageId` 恰好是「项目级 `getConfiguredMapping` → 全局 `getConfiguredDefaultSqlDialect`」，无需改策略。**P-5 成立**（清除即回到继承并触发重解析）。
  - 残留（只有真机能答）：`.idea/sql-visualizer/preview` 是否处于内容根内（决定项目级对该文件是否生效，还是直接落到全局）；预览目录上是否残留旧的 pusher 值（`clearMapping` 会一并清掉）。
- 真机验收（cyzc-platform）待发版验证：日志条目预览头部应显示「方言: PostgreSQL（当前运行条目）」，`signal` 不再标红，`.idea/sqldialects.xml` 的 preview 映射应被改写为 PostgreSQL 或清除。

---

## 十二、增补：T-DialectInferSmart（2026-10-29，同日落 master）

用户追问「多模块项目用不同方言能否识别；希望再聪明些，例如排除测试资源、按激活 profile 优先」。原实现是**项目级全量求共识**：多模块各自连不同库、或 `src/test/resources/application-test.yml` 用 H2，都会把候选挤成「无共识」而整体退化。本次三处收窄：

1. **模块优先**（`ProjectDialectEvidence.forProject(project, module)`）：`DialectRegistry.decision` 用 `ModuleUtilCore.findModuleForFile(来源文件)` 解析模块并传入；配置扫描只收**该模块自己的文件**（`findModuleForFile(file) === module`），模块级结论单独缓存（`WeakHashMap<Module, Slot>`）。模块内无结论时由 `projectEvidence` 回落项目级。⇒ 多模块 A=MySQL、B=PostgreSQL 时，各自预览各得其所。
2. **排除测试资源**：`readProjectTexts` 过滤 `ProjectFileIndex.isInTestSourceContent`。⇒ 主库 PG + 测试 H2 不再被挤成无共识。
3. **激活 profile 优先**：`activeProfilesIn` 支持 `spring.profiles.active|include` 的四种写法（扁平键 / properties `=` / YAML 缩进块 / 逗号与 `[a, b]` 与 `- item` 列表），`${ENV:default}` 取默认值、`${ENV}` 与 `@maven@` 视为未知；有激活 profile 时**只让激活 profile 的 `application-<profile>.*` 投票**（覆盖基础 `application.*`），其它环境的文件不参与；拿不到激活 profile 才回到「全部文件求共识」。

配套单测：`ProjectDialectEvidenceTest` 扩到 15 例（激活 profile 选中/覆盖基础/profile 无 URL 回落/prod 默认值/四种写法解析/profile 后缀/未知值不猜）。**反向验证**：把 `activeProfilesIn` 的结果强行忽略（`if (true)` 走全量分支）→ 三个 profile 用例红（`active profile file wins over other environments`、`active profile overrides base file`、`profile placeholder default value is used`）；撤回后同批 15 例绿。

未覆盖（只有真机能确认）：模块收窄与测试资源排除走的是平台 `ProjectFileIndex`/`ModuleUtilCore`，语义层用测试缝验证、接线待真机；多文档 YAML 里 `spring.config.activate.on-profile` 分档暂未解析（同一文件内多环境段会按「同文件多 URL」判无共识）。

