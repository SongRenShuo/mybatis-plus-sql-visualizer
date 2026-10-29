# T-GaussDbDialect GaussDB 方言支持 — 开发计划

> 来源：同日调研报告 [`../调研报告/20261029-1215-GaussDB方言支持-调研报告.md`](../调研报告/20261029-1215-GaussDB方言支持-调研报告.md)。
> 基线：`master` @ `a8e330bd`。
> 状态：**待拍板**（决策点 D1~D4 见 §五）。

---

## 一、背景与缺口

用户提出「插件需要支持 GaussDB」。现状核查结论与直觉相反：

1. **不是零支持**。`jdbc:gaussdb://` 自 2026-09-23 的 T-DialectXcFamily（O-63）起被并进 `OpenGaussDialect`（`sql/Dialect.kt:851-880`，决策代号 D2=A「一 object 两前缀」）。连接能认、SQL 能渲染、EXPLAIN 能出。
2. **但用户看不到 GaussDB**：设置页下拉（`config/PluginSettingsPanel.kt:217`）、日志角标（`log/agent/JdbcUrlDialects.kt:32`）、MCP 方言名（`mcp/McpDialectDetector.kt:24,52`）全部显示 `openGauss`。
3. **而且当前产物在 openGauss/GaussDB 上有两类真实缺陷**：
   - **保留字用错基线**：`OpenGaussDialect.reservedWords = PG_RESERVED`（`sql/Dialect.kt:869`），比真机 `pg_get_keywords()` 的保留类少 17 词（`rownum`/`minus`/`modify`/`procedure`/`is` 等）。列名 `rownum` 已被真机确认「cannot be used as an alias」→ 插件产物直接语法报错。顺带 `PG_RESERVED` 自身缺 `is`、`procedure`，**PostgreSQL 方言也受影响**。
   - **`supportsDeleteLimit` 依据失效**：`DELETE ... LIMIT n` 在 openGauss 上语法通过（只被权限拦下），插件仍静默丢弃 `last("LIMIT n")` 尾段，与 `DialectCapabilities` 的「能力如实披露」初衷相悖。
4. **两条既有「真机实证」是假绿**（详见调研报告 §四）：`LIMIT 1, 2` 的「PG 语义静默错解」结论来自单行源探针，两种解释给出同一读数（都是 0 行），没有区分力；本次多行源重测证明是 MySQL 语义。

5. **增强捕获链更严重：一条都采不到**（调研报告 §七）。`DriverRegistry`（connect 面）与 `WeaveSpecs`（语句面 / prepare 面）都是**按驱动类名硬编码的白名单**，华为官方 GaussDB 驱动 `com.huawei.gaussdb.jdbc.Driver` 与 `com.huawei.opengauss.jdbc.Driver` **三面全未登记** → attach 后 `matched=0`、零事件、零角标、无方言推断，且宿主侧无任何报错。更麻烦的是仓内三处注释把 openGauss 驱动谱系写反了（实测 2.x/3.x 是 `org.opengauss.*`、5.x/7.x 才回到 `org.postgresql.*`），`VENDOR_NOT_COVERED` 据此把 3.x 认领成「已被 PG 覆盖」——守卫因此保持绿色，属于「守卫看不见它要防的站点」。

6. **两个后备事实**：①渲染形态不需要分叉——六项能力在 GaussDB 上与 openGauss 同形，新的 `GaussDbDialect` 只是「同形不同名 + 不同保留字表」；②驱动 jar 全部在 Maven Central，增强捕获可做到「真实驱动字节织入 + JVM 链接期校验」，比金仓/翰高/海量的「本机无 jar 未实证」高一个档。

因此本任务不是「从零加方言」，而是四件事：**修错 → 拆身份 → 同步守卫与文档 → 补增强捕获驱动登记**（后两件互相独立，可分批交付）。

---

## 二、待验证前提（实施前先证伪）

> 每条都要先证伪再动手。上一轮 T-DialectXcFamily 就是靠这一步推翻了两条「族推定」。

| 编号 | 前提 | 证伪方式 | 若不成立的影响 |
|---|---|---|---|
| **P-1** | GaussDB 商用版在**默认 A 兼容模式**下支持 `ON DUPLICATE KEY UPDATE col = VALUES(col)` | 官方 INSERT 页文法（文档级）已确认全模式支持；需用户侧真机复核一次。openGauss 侧 A 模式已真机通过（`EXPLAIN` 只被权限拦） | 若 A 模式不支持，`GaussDbDialect.renderUpsert` 必须按模式分流或降级纯 INSERT（与 Oracle/DM 同处置） |
| **P-2** | `DELETE ... LIMIT n` 在 openGauss/GaussDB 上**语义真的限行** | 需一个可写实例：建表插 5 行，`DELETE ... WHERE ... LIMIT 1` 后确认只删 1 行。现有在册实例非超管、无用户表 → **本轮只能证到语法通过** | 若语义成立 → `supportsDeleteLimit` 翻 true；若语义不成立 → 保留 false，但必须把 KDoc 的理由从「PG 语义错解」改为「语法接受但语义未验证/不生效」 |
| **P-3** | 新增保留字不会打红高亮守卫 | `ui/inject/SqlMapperLanguage.DIALECT_RESERVED` 是 registry 并集，自动吸收；检查 `ui/inject/SqlMapperKeywordCoverageGuardTest` 与 `SqlMapperLanguage` 的按长度分桶表 | 若某守卫对词数有硬断言，需同步（当前未发现） |
| **P-4** | 兼容模式（`sql_compatibility = A/B/C/PG/M`）差异不影响本轮渲染 | 真机只有 A 模式库。B 模式需 dolphin 插件（`CREATE DATABASE` 文档）→ 本机无法自建 | 若模式差异影响渲染（如 B 模式反引号、`IFNULL`、`DATE_FORMAT`），本轮**明确不做模式感知**，在能力矩阵文案里如实标注「按 A 模式口径」 |
| **P-5** | 官方关键字页机读结果（GaussDB 934 词/保留 130，openGauss 862/122）无解析误伤 | 从两份页面表格各抽 20 词人工复核，重点核 `TINYINT`（两表都有）与 `IFNULL`（arg1 说 GaussDB 独有+B 模式专属） | 若机读有误，保留字表退回「真机 `pg_get_keywords()` 全量 + 差异词人工补」 |
| **P-6** | IDEA 262 运行时确实没有 GaussDB 平台方言 | `.intellijPlatform` 制品目录 grep 已零命中（代码事实）；运行时需 runIde 沙盒调 `SqlDialectMappings.getAvailableValues()` 实测 | 若平台已有（如某版本新增），券 06 的 `sql.dialect` 注册就多余，直接复用平台 id |
| **P-7** | 文档站表守卫能区分 `GaussDB` 与 `openGauss` 两个方言 | `DialectDocTableGuardTest.normalizeDialectName` 只剥括号/空格并小写 → `gaussdb` ≠ `opengauss`，两键可区分；同一格写 `PostgreSQL / openGauss / GaussDB` 会拆成三个 key | 若归一出错，先修守卫再改文档，否则「加了行但守卫看不见」= 空绿 |
| **P-8** | `Dialect.name = "GaussDB"` 与平台 language id 的精确匹配路径不冲突 | `IdeaSqlContextBridge.pluginDialectName`（`:219-226`）与 `IdeaSourceDialectProbe.dialectName`（`:14-17`）都是「精确名匹配 + TSQL 特判」。若平台 id 恰为 `GaussDB` 则命中；若平台 id 是 `PostgreSQL` 则落空（现状即如此） | 落空是可接受的（现状同），但要靠 `ExecDialectRouting` 的候选链把 `PostgreSQL` 放在兜底位 |
| **P-9** | 华为两族驱动的语句类织入后**能通过 JVM 链接期校验**（无 `VerifyError`） | 达梦先例是出口 `chop_frame` 致 `VerifyError`，premain 路径会让宿主类定义失败。华为驱动是 pgjdbc 深度改写（`PgStatement implements GsStatement`），形态未知 → **必须先跑字节层**：`testImplementation("com.huaweicloud.gaussdb:gaussdbjdbc:506.0.0.b058-jdk7")` + 既有 `assertWovenAndVerifiable` | **决定红线的关键前提**。若产生 `VerifyError`：attach 路径安全（原字节保留）但零采集；**premain 路径会让宿主起不来** → 该类只能进语句面且必须排除 premain，或整族不登记 |
| **P-10** | openGauss 社区驱动的包名分界确实是「2.x/3.x = `org.opengauss.*`，5.x/7.x = `org.postgresql.*`」 | 已实测：`3.0.0` 内为 `org/opengauss/Driver.class`、`org/postgresql` 计数 **0**；`7.0.0` 内为 `org/postgresql/Driver.class`（`META-INF/services/java.sql.Driver` 声明）、`org/opengauss` 计数 **0** | 若前提有别的版本例外，`VENDOR_NOT_COVERED` 的改写要按版本区间而不是整段认领 |
| **P-11** | `com.huawei.gaussdb.jdbc.Driver` 的 connect 出口织入是否与达梦同型（chop_frame） | 需字节层实测（同 P-9）。达梦处置是**整个撤出 connect 面**，靠 prepare 面 + `UrlResolver` 反射拿 URL（`PgConnection.getURL()` 为 public，已实测） | 若同型：GaussDB 也**不登记 connect 面**，只走「语句面 + prepare 面」，与达梦同款路径——URL 仍可得，损失仅「最近 connect URL」兜底 |
| **P-12** | 增强捕获的厂商对齐守卫改口径后不会误伤既有厂商 | `vendorKey()` 现在对 `org.x`/`com.x` 取两段。改成对 `com.huawei.` 取三段，需确认 `com.microsoft.`/`com.mysql.`/`com.dameng.`/`com.gbasedbt.`/`com.kingbase8.` 仍取两段不受影响 | 若误伤：只对 `com.huawei.` 白名单化特判，不全局改段数 |

---

## 三、实施步骤

> 券间依赖：01 独立；02 独立（但需可写实例）；03 独立；04 依赖 01（保留字表）；05 依赖 04；06 独立可选；**07 独立于 01~06**（只动 agent 子项目），但依赖 P-9/P-11 的字节层读数；**08 依赖 07**（先有登记才谈守卫口径）。

### 券 01 — 保留字基线收口（含 PostgreSQL 既有缺陷）

**目标**：让三方言（PostgreSQL / openGauss / GaussDB）的保留字集合与权威来源一致。

1. `sql/SqlKeywords.kt`：
   - `PG_RESERVED` 补 `is`、`procedure`（PG 官方附录为 reserved；当前缺失导致 PG 方言产物语法错误）。
   - 新增 `OPENGAUSS_RESERVED`：真机 `pg_get_keywords()` 的 `R∪T∪C`（93+27+63 = 183 词）∪ openGauss 官方关键字页「保留」列，取并集。KDoc 写清两个来源、并集理由、以及「两个来源对『保留』定义不同」这一事实，防止后人拿单一来源「订正」回去。
   - 新增 `GAUSSDB_RESERVED`：同上口径，基线换成 GaussDB 官方关键字页（130 保留 + 独有词表）。
2. `sql/Dialect.kt`：`OpenGaussDialect.reservedWords` 改为 `OPENGAUSS_RESERVED`；新增 `GaussDbDialect.reservedWords = GAUSSDB_RESERVED`（若券 04 未拍板则先只改 openGauss）。
3. `sql/verify/DialectDocGoldenTest.kt`：为三方言各加一份**独立维护**的黄金清单断言（照 DM/KingBase 既有模式：黄金清单与实现两侧独立，任何一侧漂移即红）。
4. 反向验证：从 `OPENGAUSS_RESERVED` 里临时移除 `rownum` → `DialectDocGoldenTest` 必红，贴两次读数。

**必改文件**：`sql/SqlKeywords.kt`、`sql/Dialect.kt`、`src/test/.../sql/verify/DialectDocGoldenTest.kt`。
**注意**：`ui/inject/SqlMapperLanguage.kt:60-66` 的注释写死「十一个方言保留字的并集」「九个 `SqlKeywords.kt` 顶层 val」，新增 val 后这段注释要同步（注释不影响逻辑，但守卫若按注释对账会红，先看再改）。

### 券 02 — `DELETE ... LIMIT` 能力重新取证与处置

**目标**：把「静默丢尾段」这件事变成有证据的显式决定。

1. 取证：优先在 DBX 在册**可写**实例（如本地 MySQL/PG 容器，或向用户索取 openGauss 可写库）跑 P-2 脚本；openGauss 侧若仍拿不到可写库，如实登记「语义未验证」。
2. 处置二选一（决策点 D3）：
   - 语义成立 → `OpenGaussDialect`（及 `GaussDbDialect`）覆写 `supportsDeleteLimit = true`，并在 `DialectCapabilitiesTest` 补缺口判定对照（`DELETE ... LIMIT` 不再报缺口）。
   - 语义不成立 → 保持 false，但把 KDoc 依据改为「语法接受、语义未验证/不生效」，并在 `dialect.capability.reason.deleteLimit` 的既有文案路径上确认用户看得到说明。
3. 同步 `src/test/.../sql/DialectXcFamilyTest.kt:97-98` 的注释依据（当前写着「真机佐证：LIMIT 1,2 按 PG 语义解析」——该依据已被推翻）。

### 券 03 — 假绿订正与「探针区分力」守卫

**目标**：把「两种假设给同一读数」这个失效模式钉在测试里。

1. `src/test/.../sql/DialectXcFamilyTest.kt`：
   - 订正 KDoc（`:24-25`）与 `:97-98`：`LIMIT a, b` 是 MySQL 语义；保留 PG 形态渲染的理由改为「两解同义、消除歧义」，不再写「防静默错解」。
   - 新增用例：`分页_LIMIT逗号形态是多行源可区分的探针` —— 用**多行来源**（如 `generate_series` 等价物或既有夹具多行表）钉住语义；并显式断言「单行源不可区分」这一点，作为注释保留方法学。
2. 在 `docs/架构设计/03-开发与测试指南.md` 的方言验证节补一条口径：**方言语义探针必须有多行源，单行源对 offset 类语义无区分力**（与既有「假绿断言的三十一种形态」记忆对齐）。
3. 反向验证：把多行源改回单行源 → 新用例必红（或退化为无区分力），贴读数。

### 券 04 — 拆出 `GaussDbDialect`（形态复用，身份独立）

**目标**：`jdbc:gaussdb://` 落到独立方言对象，渲染形态与 openGauss 逐字节一致。

1. `sql/Dialect.kt`：
   - 新增 `object GaussDbDialect : Dialect`，`name = "GaussDB"`。
   - **形态全量复用**：`renderLimit` = PG 形态（`renderPgStyleLimit`）、`quoteIdentifier` = 双引号、`identifierQuotes = setOf('"')`、`identifierFolding = LOWER`、`renderUpsert` = MySQL 形态（`renderMySqlStyleUpsert`）、`transactionBegin = "BEGIN"`、`supportsDeleteLimit` 按券 02 结论、`renderInsertWrapper` = null。KDoc 必须写明「形态与 openGauss 相同是**实测结论**，不是族推定」，并给 GaussDB 官方文档出处。
   - 注册进 `DialectRegistry.dialects`（置于 `OpenGaussDialect` 之后）。
2. `sql/ExplainSupport.kt`：加 `Entry("GaussDB", "EXPLAIN (FORMAT JSON) {sql}", JSON, <证据>)`。证据栏写「GaussDB 官方 EXPLAIN 页文法 + openGauss 5.0.0 真机同形（单列 QUERY PLAN，`[{"Plan":…}]`）」，并标注 GaussDB 侧无真机。
3. 三条 URL 推断表把 `gaussdb` 改指 `GaussDB`（`opengauss` 保持指向 `openGauss`）：
   - `sql/DialectInference.kt:32`
   - `log/agent/JdbcUrlDialects.kt:32`（角标：`"gaussdb" to "GaussDB"`）
   - `mcp/McpDialectDetector.kt:24,52`（URL 前缀 + 别名，另可加中文别名 `"高斯"`）
4. `execute/ExecDialectRouting.kt:51-63`：加 `"GaussDB" to listOf("GaussDB", "openGauss", "PostgreSQL")`（顺序：先精确 id，再同族，最后 PG 兜底）。**漏这一步会导致执行目标 Runner 枚举落空→回退 MySQL**。
5. `mcp/SqlVisualizerExplainTool.kt:119-120`：报错支持列表加 `GaussDB`（否则 MCP 传 `gaussdb` 会被 `isRecognized` 拒）。
6. 反向验证：从 `DialectRegistry.dialects` 临时摘掉 `GaussDbDialect` → `byName("GaussDB")` 回落 `MySqlDialect`、URL 全链断言必红；恢复后复绿。贴两次读数。

### 券 05 — 守卫、文档、对外文案同步

**MUST（不改即红）**

| 守卫 | 位置 | 改法 |
|---|---|---|
| plugin.xml 方言数 | `config/PluginXmlStructureGuardTest.kt:151-158` + `META-INF/plugin.xml:39,55` | `11` → `12`（中英各一处） |
| 注册表精确顺序 | `sql/DialectTest.kt:85-91` | 末尾追加 `"GaussDB"` |
| 折叠族成员 | `sql/DialectTest.kt:139-146` | LOWER 列表追加 `"GaussDB"` |
| NONE 族成员 | `generator/CrudSqlGeneratorTest.kt:521-524` | 不改（GaussDB 是 LOWER 族） |
| EXPLAIN 条目数 | `sql/DialectExplainTest.kt:213-223` | 券 04 加了 Entry 后自动满足 |
| `transactionBegin` 黄金表 | `log/ReplayScriptExportTest.kt:120-142` | 加 `GaussDbDialect.name to "BEGIN"`；「判无恰为 Oracle/DM」断言不变 |
| URL 推断期望 | `sql/DialectInferenceTest.kt:17-33`、`sql/DialectXcFamilyTest.kt:166-180`、`log/agent/JdbcUrlDialectsTest.kt:37-39`、`mcp/McpRenderInputTest.kt:30-33,56-59` | `gaussdb` 期望值改 `"GaussDB"`；`opengauss` 断言保留不动 |
| 兄弟文档仓 4 张表 | `../mybatis-plus-sql-visualizer-docs/features/wrapper-preview.md:68+`、`en/features/wrapper-preview.md:67+`、`features/batch-operation.md:42+`、`en/features/batch-operation.md:42+` | 加 `GaussDB` 行；分页写 PG 形态、upsert 写 `ON DUPLICATE KEY UPDATE` |
| identity 声明 | `features/crud-sql.md:32`、`en/features/crud-sql.md:32` | 仅在 GaussDB 需要包装时才加（本方案不需要） |
| 元守卫登记 | `convention/GuardSelfTestCoverageMetaGuardTest.kt` | 若新增 `*GuardTest` 必须登记；`*WiringTest` 不强制 |

**SHOULD（不红但会对外漂移）**

- `docs/架构设计/01-项目架构总览.md:14`（「11 种方言」）、`02-核心流程详解.md:110,131`（推断链）、`03-开发与测试指南.md:205-213`（「新增方言」清单，当前只有 7 步、漏 `ExplainSupport`/`JdbcUrlDialects`/`McpDialectDetector`/`ExecDialectRouting`/文档表守卫/plugin.xml 计数守卫）、`M37M38新增功能-说明文档.md:49-59`（EXPLAIN 表已过期）。
- `README.md:121,162`、`docs/mcp/MCP-Tools-Agent-Guide.md:87`、`src/main/resources/html/marketplace-custom-page.html:174,216,251`（3 处「11 种方言」）。
- 兄弟文档仓 `wrapper-preview.md:79` / `en:78` 的「OceanBase 与 openGauss 的 upsert」散文，补 GaussDB。

### 券 06（可选）— 平台 `sql.dialect` 注册

**目标**：让 IDEA 的 SQL 方言链能精确认出 GaussDB，而不是靠 PostgreSQL 兜底。

1. 照 `sql/dm/DmSqlDialect.kt` 模板新建 `sql/gaussdb/GaussDbSqlDialect.kt`：继承 `SqlLanguageDialectBase("GaussDB")`，`createTokensHelper` 委托 `Sql92Dialect`，`getDbms() = Dbms.UNKNOWN`，`supportsErrorHighlighting() = false`，`@JvmField INSTANCE`。
2. `META-INF/database-optional.xml` 加 `<sql.dialect dbms="UNKNOWN" order="last" implementationClass="...GaussDbSqlDialect"/>`。
3. 新建 `sql/GaussDbDialectRegistrationWiringTest.kt`（照 `DmDialectRegistrationWiringTest`）：候选链含注册 id、URL 兜底链全通、xml 注册在场（摘掉即红）。
4. 真机沙盒验证（runIde）：设置页 SQL Dialects 能选到 `GaussDB`，执行 Runner 精确匹配。

**归属**：P-6 若证伪（平台已有）则本券撤销。

### 券 07 — 增强捕获：GaussDB / openGauss 驱动登记（**独立成批，不阻塞方言主线**）

**目标**：让华为官方 GaussDB 驱动（及华为 openGauss 品牌驱动）真正采到事件。

**前置**：P-9 / P-11 必须先跑通。**在字节层绿灯之前，任何一类驱动都不得进 `DriverRegistry`（connect 面）** —— premain 路径会让宿主起不来，这是 `T-ConnCastSafety` 定下的红线（达梦 `DmDriver` 因此整类撤出）。

1. `sub-projects/sql-visualizer-agent/build.gradle.kts`：加 `testImplementation("com.huaweicloud.gaussdb:gaussdbjdbc:506.0.0.b058-jdk7")`（Java 8 字节码、jar 自包含）。与既有 `DmJdbcDriver18` / `postgresql` / `mssql-jdbc` / `ojdbc11` 的写法同款（`build.gradle.kts:110-118`）。
2. `StatementWeaverVendorCoverageTest`：
   - `STATEMENT_FACE_CLASSES` 加 `com.huawei.gaussdb.jdbc.jdbc.PgStatement`、`PgPreparedStatement`；`PREPARE_FACE_CLASSES` 加 `com.huawei.gaussdb.jdbc.jdbc.PgConnection`。
   - 先只跑**字节层**（`assertWovenAndVerifiable`）验证 P-9；绿灯后再进 `WeaveSpecs`。
3. `WeaveSpecs`：按 PG 那套 spec 登记——`PgStatement` / `PgPreparedStatement` 用 `statementFace`（EXEC_PARAM / EXEC_CTX / BATCH / ADD_BATCH / CLEAR_BATCH / PARAM），`PgConnection` 用 `prepareFace`。`PgCallableStatement` 不单列（继承 `PgPreparedStatement`，自有 execute 族很少）。
4. `DriverRegistry`：按 P-11 结论二选一——
   - 字节层证明 connect 出口安全 → 加 `com.huawei.gaussdb.jdbc.Driver`；
   - **或**（更保守，与达梦同款）**不加 connect 面**，URL 走 `UrlResolver` 反射 `PgConnection.getURL()`（已实测 public）+ prepare 面。**推荐后者**：少一颗 premain 的雷，功能损失只有「最近 connect URL」兜底。
5. 华为 openGauss 品牌驱动同法：`com.huawei.opengauss.jdbc.jdbc.PgStatement/PgPreparedStatement/PgConnection`（`com.huaweicloud.gaussdb:opengaussjdbc:506.0.T35`）。
6. openGauss 2.x/3.x：`org.opengauss.jdbc.PgStatement/PgPreparedStatement/PgConnection`（`org.opengauss:opengauss-jdbc:3.0.0`）。同时把 `VENDOR_NOT_COVERED` 的 `org.opengauss.` 那条理由**改成事实**（现写「2.x 走 PG 驱动」与实际相反）。
7. 反向验证：临时从 `WeaveSpecs` 移除 `PgPreparedStatement` → 字节层用例必红；恢复转绿。贴两次读数。

**不做（明确出界）**：`com.huawei.gauss200.jdbc.Driver`（Maven Central 无制品，未实测）、openGauss 5.x/7.x 的 `readwritesplitting.*` 与 `OR*` 族（不继承 `Pg*`，需单独取证）、`SmartRouteConnection` 的两个 `prepareStatement` 重载。这三项进「后续券」而不是硬塞进本券。

### 券 08 — 增强捕获：守卫口径与帧前缀补齐

**目标**：把券 07 之后仍会「假绿」和「跳错位置」的两处收口。

1. **厂商前缀口径**（`StatementWeaverVendorCoverageTest.vendorKey`，`:180-187`）：现在对 `com.x` 只取两段，`com.huawei.gaussdb.` 与 `com.huawei.opengauss.` 都压成 `com.huawei.`——登记一族即可让另一族假绿。改成对 `com.huawei.` 取三段（P-12 先验不影响其它厂商）。
2. **名单漂移反向断言**：现有断言只有「测试数组 ⊆ SPECS」。补一条 `specClassNames() ⊆ 测试数组 ∪ BYTE_VERIFIABLE_EXCLUDES ∪ 已认领厂商`，让「加了 spec 没加测试」变红——这是新类唯一能拿到字节层校验的入口。
3. **帧跳过前缀四份同步**：`StackLocator.java:88-90`（agent）、`CallerSourceLocator.kt:77-80`、`SourceInterceptorTemplates.kt:281-284`、`StackDigestFrames.kt:149-151` 各补 `com.huawei.`、`org.opengauss.`。由 `LocatorSkipPrefixSyncTest` 锁「四份同集」，**必须一次改齐四份**，否则红。
4. **KDoc 事实订正**：`DriverRegistry.java:31`、`WeaveSpecs.java:63`、`SqlHookCallbacks.java:42-43` 里「openGauss 2.x 走 PG 驱动 / 增强采集与数据库类型无关」的表述按实测改写。
5. **宿主安全提示**：`WRAP_CONNECTION_PROP` 的 KDoc 点名 GaussDB——`wrapConnection=true` 时 `(PgConnection) conn` 必抛 CCE（类实测 public 非 final）。默认值 `false` 不得改。

---

## 四、验证

### 4.1 定向测试（每券随交付）

| 券 | 定向测试 |
|---|---|
| 01 | `--tests "cn.song.mybatisplus.visualizer.sql.verify.DialectDocGoldenTest"` + `--tests "cn.song.mybatisplus.visualizer.sql.DialectTest"` |
| 02 | `--tests "cn.song.mybatisplus.visualizer.sql.DialectCapabilitiesTest"` + `--tests "cn.song.mybatisplus.visualizer.sql.DialectXcFamilyTest"` |
| 03 | `--tests "cn.song.mybatisplus.visualizer.sql.DialectXcFamilyTest"` |
| 04 | `--tests "cn.song.mybatisplus.visualizer.sql.*"` + `mcp.McpRenderInputTest` + `log.agent.JdbcUrlDialectsTest` + `log.ReplayScriptExportTest` + `config.PluginXmlStructureGuardTest` |
| 05 | `--tests "cn.song.mybatisplus.visualizer.sql.verify.DialectDocTableGuardTest"` + `generator.CrudSqlGeneratorTest` |
| 07 | `:sql-visualizer-agent:test --tests "cn.song.mybatisplus.sqlvis.agent.StatementWeaverVendorCoverageTest"` + `--tests "cn.song.mybatisplus.sqlvis.agent.CaptureRulesTest"`（只需 agent 子项目，不动根项目） |
| 08 | 同上 + `--tests "cn.song.mybatisplus.visualizer.log.LocatorSkipPrefixSyncTest"`（在根项目） |

### 4.2 反向验证（每券必做，贴红/绿两次读数）

| 对象 | 反向操作 | 期望 |
|---|---|---|
| 01 | 从 `OPENGAUSS_RESERVED` 移除 `rownum` | `DialectDocGoldenTest` 红 |
| 02/03 | 探针改回单行源 | 新用例红（或无区分力） |
| 04 | 从 registry 摘掉 `GaussDbDialect` | `byName`/URL 全链断言红 |
| 05 | 文档站删掉 `GaussDB` 那行 | `DialectDocTableGuardTest` 报「缺少方言 GaussDB」 |
| 07 | 从 `WeaveSpecs` 移除 `PgPreparedStatement` | `StatementWeaverVendorCoverageTest` 字节层用例红；厂商对齐守卫红 |
| 08 | 把 `vendorKey` 改回两段 | 新增的「同厂商跨族假绿」用例红（若该用例只写正向，属空绿，必须先造缺陷样本） |
| 08 | 从四份帧前缀里删掉 `com.huawei.` 中的一份 | `LocatorSkipPrefixSyncTest` 红 |

### 4.3 全量门禁

券 01~05 全部完成后，单派只读收口代理跑一次权威全量（按仓库既有口径：`testShards` 默认 5 片、单路），主控并行写文档。**券 06 落 `src/main` 后必须复跑**（改了 `database-optional.xml` 属 EP 面变更）。

券 07 只动 `sub-projects/sql-visualizer-agent`，跑该子项目 `:sql-visualizer-agent:test` 即可，**不触发根项目全量**；券 08 同时动根项目 `log/` 与 agent，**需复跑全量**。

### 4.4 构建校验

券 06 触达 `plugin.xml` 的 optional depends 与 `database-optional.xml` 的 EP，属「大改动」判据 → 需 `verifyGate`（Compatible 判定）；若同时动 `since-build` 或新增平台 API 面，追加 `verifyPlugin`。

---

## 五、决策点

| 编号 | 问题 | 选项 | 推荐 |
|---|---|---|---|
| **D1** | 要不要拆独立方言对象 | A 不拆（只改文档）/ B 拆身份（形态复用）/ C 拆身份 + 平台方言 | **B**。理由：保留字真实不同（GaussDB 独有保留字 12 个）+ 用户可见性 + MP 生态已分。C 作为券 06 可选 |
| **D2** | 保留字取哪个来源 | A 只用真机 `pg_get_keywords()` / B 只用官方页「保留」列 / C 并集 | **C 并集**（沿用 `Dialect` KDoc 的「宁可多包、不漏包」口径），KDoc 必须写明两来源口径不同 |
| **D3** | `supportsDeleteLimit` 是否翻 true | A 翻 true（需 P-2 证成）/ B 保持 false 但改依据 | 视 P-2。**在拿到可写实例前一律 B**，不得凭语法通过臆断语义 |
| **D4** | `Dialect.name` 用 `GaussDB` 还是 `GaussDB（高斯）` | A `GaussDB`（与 MP `DbType.GAUSS_DB` 的 `db="gaussDB"` 对齐）/ B 带中文括注（与 `DM（达梦）` 风格一致） | **A**。理由：`name` 被 `IdeaSqlContextBridge.pluginDialectName` 与 `IdeaSourceDialectProbe` 做**精确匹配**，带中文会命中不了任何平台 id；中文别名另在 `McpDialectDetector.ALIASES` 补 |
| **D5** | 增强捕获的驱动覆盖粒度 | A 只做 GaussDB 商用（`com.huawei.gaussdb.*`）/ B 商用 + 华为 openGauss 品牌（`com.huawei.opengauss.*`）/ C 再加 openGauss 2.x/3.x（`org.opengauss.*`） | **B**。理由：两族同为华为分发、形态一致、成本几乎为零；openGauss 2.x/3.x 属「已认领的旧缺口」，可同批修也可另立，但不该和商用版混在同一笔提交里 |
| **D6** | GaussDB 是否登记 connect 面 | A 登记（`com.huawei.gaussdb.jdbc.Driver`）/ B 不登记，只走语句面 + prepare 面 | **B，除非 P-9/P-11 字节层证明 connect 出口无 `VerifyError`**。达梦先例是撤出 connect 面后功能无损（URL 由 prepare 面 + `UrlResolver` 反射拿到），代价只有「最近 connect URL」兜底 |

---

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **R1 GaussDB 商用版零真机** | Docker Hub 无华为官方镜像（只有第三方零 star），本轮 GaussDB 侧全部为官方文档级；`OVERRIDING SYSTEM VALUE`、`DELETE...LIMIT` 在 GaussDB 是否成立未验证 | 计划内所有 GaussDB 断言标注「文档级」；能力矩阵与文档不写「真机通过」；用户侧真机读数到位后再升档 |
| **R2 openGauss 读数冒充 GaussDB** | 上一轮已出现过「openGauss 5.0.0 真机 → 结论推广到 GaussDB」的先例（D2=A 本身即此形态） | KDoc/文档逐条标证据档；GaussDB 专属结论必须单独给文档出处 |
| **R3 保留字并集把产物改宽** | 新增 17+ 词会让部分列名从裸写变引用，产物字节变化 | 属预期（原产物是错的）；但 `DialectRenderDumpTest` 等产物快照若红，需逐条确认是「修正」而非「回归」 |
| **R4 `DELETE LIMIT` 误翻** | 语法通过 ≠ 语义生效；若草率翻 true，用户照预览执行会无界删除 | D3 强制「先有可写实例读数」；无读数保持 false 并改文案 |
| **R5 平台方言注册干扰现有数据源** | `database-optional.xml` 注册新 `sql.dialect` 会影响 `SqlDialectMappings` 全局可用值集合 | 券 06 独立成券、单独全量 + runIde 沙盒；`dbms="UNKNOWN"` + `order="last"` 与 DM 同款最小实现 |
| **R6 兼容模式未覆盖** | 真机只有 A 模式；B/M/PG 模式下 `LIMIT m,n`、`IFNULL`、反引号行为不同 | 本轮明示「按 A 模式口径」；模式感知另立任务（需可建多模式库的实例） |
| **R7 文档站是独立仓** | 4 张表在兄弟仓，守卫不在场时显式绿跳 → 本地容易漏改 | 券 05 必须确认兄弟仓在场再跑守卫；提交时插件仓与文档仓分两笔 |
| **R8 织入产物让宿主起不来（最高危）** | premain 路径把驱动类定义失败 → 宿主 `NoClassDefFoundError`。达梦 `DmDriver` 就是这样被整类撤出的 | P-9 字节层先过；connect 面按 D6 默认不登记；attach 路径失败只降级不炸（原字节保留）；券 07 单独一笔便于回滚 |
| **R9 驱动版本漂移** | 华为驱动 5xx 系列内部类层次会变（`SmartRoute*`、`TenantConnection`、`MPreparedStatement` 都是新子类）；openGauss 社区驱动的包名在 5.x 整体换过一次 | 登记只锁「父类名」（`PgStatement`/`PgPreparedStatement`/`PgConnection`），子类靠继承覆盖；字节层守卫钉形态不锁版本，漂移按「织不上降级」契约 |
| **R10 与在途会话同模块并行** | 另一会话正在改 agent 的 `hook/StatementHandler.java`、`hook/WovenEvents.java`、`util/StackDigest.java`、`util/StackLocator.java`（T-StackBizFrame）。实测未触及 `DriverRegistry`/`WeaveSpecs`/测试/build，但同一子项目并行编译会互相打断 | 券 07 等其收口后再开；券 08 的第 3 项（帧前缀四份）**必须**与其改动解耦后再动 `StackLocator.java` |
| **R11 用户可见降级仍是空白** | 驱动不在名单时面板零条目，用户只知道「没有 SQL」，看不到「你的驱动暂不支持，可用控制台通道」 | 沿用 `T-ConnCastSafety` 已登记的「GBASE/sqlite/hsqldb 零事件无文案提示」欠账，本任务一并处理或明确挂账 |

---

## 七、实施记录

> 实施后回填。禁止预填。

### 7.1 交付物（提交清单）

| 券 | 提交 | 说明 |
|---|---|---|
| 01 | `89dec3ab` | feat(dialect): 券01 保留字基线收口（openGauss/GaussDB 独立集合 + PG 补 is/procedure） |
| 02 | `6c39fc07`（与 03/04/05 同笔） | 只改 `Dialect.kt` 的 KDoc 与 `supportsDeleteLimit` 显式声明，与券 04 同文件，无法单独成笔 |
| 03 | `6c39fc07`（同上） | 同上；新增「只产 PG 形态不产逗号形态」用例 |
| 04 | `6c39fc07` | feat(dialect): GaussDB 独立方言落地（券02~05 代码面） |
| 05 代码面 | `6c39fc07`（同上） | plugin.xml 12 方言 + 6 处守卫同步——必须与 04 同笔，否则单笔 checkout 是红的 |
| 05 文档面 | 分支 `feature/gaussdb-docs` 三笔 → 合入 `e4e24be1` | 架构文档 01/02/03、README、MCP 指南、对外文案；兄弟文档站另仓 **`fa7ce8e`**（其 master，**未推送**，推送会触发站点部署） |
| 06 | `47e7693f` | feat(dialect): 券06 注册 GaussDB 平台 SQL 方言（仿达梦先例） |
| 07 | 待补 | |
| 08 | 待补 | |

### 7.2 §二 待验证前提的证伪结论

| 前提 | 结论 | 证据 |
|---|---|---|
| P-1 | **成立** | GaussDB 官方《INSERT》文法全模式含 `ON DUPLICATE KEY UPDATE`，而 `ON CONFLICT` 官方明注「仅支持 PG 兼容性」；openGauss 5.0.0（A 模式）真机 EXPLAIN 只被权限拦（语法+计划通过）。故 `GaussDbDialect` 取 MySQL 形态 |
| P-2 | **语法成立、语义未验证** | `EXPLAIN DELETE FROM db4ai.snapshot WHERE id = 1 LIMIT 1` → `permission denied for relation snapshot`；同库对照组 `ON CONFLICT` 直接 `syntax error`（证明通道能区分）。实例非超管且 `public` 无用户表，无法观察影响行数 → 按 D3 保持 `supportsDeleteLimit=false` 并改依据 |
| P-3 | **成立** | `SqlMapperLanguage.DIALECT_RESERVED` 是 registry 并集自动吸收；`SqlMapperKeywordCoverageGuardTest` 7/0 绿 |
| P-4 | **未验证（环境受限）** | 只有 `sql_compatibility='A'` 库；B 模式需 dolphin 插件、无法自建。本轮明示按 A 模式口径，B 模式专属能力（反引号/`IFNULL`/`DATE_FORMAT`/`LAST_INSERT_ID`）一概不用 |
| P-5 | **部分证伪** | 官方页机读的 934/862 词、130/122 保留字未逐词人工复核，故**没有采信绝对数**；改为以真机 `pg_get_keywords()` 的 R∪T∪C（183）为基线，文档只贡献 3 个独有词（共 186），GaussDB 增量 11 词单独列出并由黄金清单钉死 |
| P-6 | **成立（静态）；运行时未验** | `.intellijPlatform` 制品目录 `grep -ril gauss` 零命中；运行时 `SqlDialectMappings.getAvailableValues()` 需 runIde，未做 → 券 06 已实现注册与静态接线守卫，真机可见性列入未闭环 |
| P-7 | **成立，且已实证** | `DialectDocTableGuardTest` 9/0 **skipped=0**（实读兄弟站表格）——合并单元格 `PostgreSQL / openGauss / GaussDB` 被正确拆成三个键，`GaussDB` 与 `openGauss` 是两个独立键 |
| P-8 | **成立** | `Dialect.name`=`GaussDB` 与 `GaussDbSqlDialect.ID` 逐字相等，由 `GaussDbDialectRegistrationWiringTest` 断言 |
| P-9 | 待补 | |
| P-10 | 待补 | |
| P-11 | 待补 | |
| P-12 | 待补 | |

### 7.3 验证读数

**定向测试（本工作树，逐类 tests/skipped/failures）**

| 类 | 读数 |
|---|---|
| `DialectTest` | 19 / 0 / 0 |
| `DialectXcFamilyTest` | 15 / 0 / 0 |
| `DialectExplainTest` | 26 / 0 / 0 |
| `DialectInferenceTest` | 9 / 0 / 0 |
| `DialectDocGoldenTest` | 11 / 0 / 0 |
| `ReplayScriptExportTest` | 11 / 0 / 0 |
| `JdbcUrlDialectsTest` | 16 / 0 / 0 |
| `McpRenderInputTest` | 6 / 0 / 0 |
| `PluginXmlStructureGuardTest` | 22 / 0 / 0 |
| `DialectDocTableGuardTest` | 9 / 0 / 0（skipped=0，实读兄弟站） |
| `DialectCapabilitiesTest` | 9 / 0 / 0 |
| `SqlMapperKeywordCoverageGuardTest` | 7 / 0 / 0 |
| `CrudSqlGeneratorTest` | 29 / 0 / 0 |
| `DialectRenderDumpTest` | 1 / 0 / 0 |
| `GaussDbDialectRegistrationWiringTest` | 4 / 0 / 0 |
| `DmDialectRegistrationWiringTest` | 3 / 0 / 0 |
| `GuardSelfTestCoverageMetaGuardTest` | 8 / 0 / 0 |
| `DialectRegistryIdeaContextTest` | 2 / 0 / 0 |
| `DialectStaleManualLeakGuardTest` | 3 / 0 / 0 |
| `SqlDialectEdtSourceScanTest` | 4 / 0 / 0 |
| `ExecutionDialectGateTest` | 18 / 0 / 0 |
| `ExecDialectRoutingTest` | 27 / 0 / 0 |
| `SettingsTruthTest` | 10 / 0 / 0 |
| `DialectSettingsStatusTextTest` | 3 / 0 / 0 |
| `DialectH2VerificationTest` | 14 / 0 / 0 |
| `DialectDockerVerificationTest` | 6 / 6 skip / 0（本机无 docker，预期跳过） |

**反向验证（红/绿两次读数）**

| 对象 | 反向操作 | 红 | 绿（恢复后） |
|---|---|---|---|
| 券 01 | 从 `OPENGAUSS_KEYWORDS_RESERVED` 移除 `rownum` | `openGauss保留字_真机黄金清单逐词一致` + `openGauss保留字_旧PG基线漏包十七词回归防护` 红；合并批次 `26 tests completed, 4 failed` | `BUILD SUCCESSFUL` |
| 券 04 | 从 `DialectRegistry.dialects` 摘掉 `GaussDbDialect` | `注册表身份_摘掉注册即红` + `url推断_三前缀命中且全链落到本方言对象` 红（同上 4 failed 中的两例） | `BUILD SUCCESSFUL` |
| 07 | 待补 | | |
| 08 | 待补 | | |

**全量门禁**：待补。
**`verifyGate`**：待补。

### 7.4 未做项与观察登记

- **GaussDB 商用版零真机**：Docker Hub 无华为官方镜像，全部 GaussDB 结论为**官方文档级**；各项形态以 openGauss 5.0.0 真机（同源 PG 9.2.4 基线）为旁证。真机读数到位前，文档与能力矩阵不得写「GaussDB 已验证」。
- **`DELETE ... LIMIT` 语义未验证**：只证到语法接受。需一个可写实例（建表插 5 行、`DELETE ... LIMIT 1` 后核影响行数）才能决定是否翻转 `supportsDeleteLimit`。
- **兼容模式未覆盖**：只有 `sql_compatibility='A'` 库。B/C/PG/M 模式下 `LIMIT m,n`、`IFNULL`、`DATE_FORMAT`、反引号的行为全部未验。
- **券 06 平台 EP 运行期可见性未验**：需 runIde 沙盒确认「设置页 SQL Dialects 能选到 GaussDB」与「Runner 精确匹配」。静态接线由 `GaussDbDialectRegistrationWiringTest` 守住。
- **官方关键字页计数未逐词复核**：934/862 与 130/122 为 HTML 表格机读结果，本轮以真机 186 词兜底，GaussDB 增量 11 词单列（见 P-5）。
- **增强捕获 07/08**：见 7.1 待补行。
