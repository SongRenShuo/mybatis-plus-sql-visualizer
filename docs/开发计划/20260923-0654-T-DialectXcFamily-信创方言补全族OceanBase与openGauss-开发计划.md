# T-DialectXcFamily 信创方言补全族（OceanBase + openGauss） — 开发计划

- 日期：2026-09-23 06:54
- 任务编号：**O-63**（第三轮全景审计 §8.1）
- 来源：UX 锚点核实——方言注册表 9 方言（`sql/Dialect.kt:689-699`），OceanBase / openGauss / GaussDB 全文 grep 零命中；信创双备（DM8 真机 + KingBase）已有交付与验证先例
- 性质：功能补全（既有方言矩阵扩容，非新环；竞品侧零家支持信创方言，r11 §5 矩阵——补齐后「信创四备」叙事成立）
- 实施分支：自 master 拉任务分支（实施前先让 O-62 §三.1 把本地 master 快进到 origin/master）
- 验证基线：origin/master `fa9b57e`

---

## 一、背景与缺口

现役 9 方言（`Dialect.kt`）：MySQL `:361` / PostgreSQL `:387` / Oracle `:420` / SQL Server `:453` / H2 `:518` / SQLite `:556` / MariaDB `:582` / DM 达梦 `:613` / KingBase 人大金仓 `:657`。信创装机量最大的两个缺口：**OceanBase**（MySQL 模式为主流）与 **openGauss/GaussDB**（PG 系）。两者都不在任何注册表、URL 推断表与 EXPLAIN 支持面表里。

每个方言的接入面（参照 KingBase=PG 系先例）：

1. `Dialect` object 注册（`:689-699` 清单）+ 引号规则 + 折叠方向族（O-51 形态 `IdentifierFolding`，`Dialect.kt:101/:267`）+ `renderLimit` + 关键字换行格式化词表
2. EXPLAIN 支持面表 `sql/ExplainSupport.kt`（`:96` entries、`:111` render，O-48 后为显式登记驱动）
3. agent URL 推断：`DialectInference` 的 URL 前缀匹配面（`jdbc:oceanbase://`、`jdbc:opengauss://` 等，前缀清单见 §二.4）
4. CRUD / 回滚 / 格式化链共用面自动继承

**明确出圈（不做）**：OceanBase Oracle 模式（D1）；identity 包装（DM8 教训——先推定不需要，真机暴露再按 O-52 先例增量，`fba833c` 先例在册）；GBase / TiDB 等（登记观察，§8.3 以族为单位控制重演半径）。

## 二、待验证前提（实施前先证伪）

1. **OceanBase MySQL 模式与 MySQL 方言兼容面**：backtick 引号、LIMIT 语法、EXPLAIN 形态推定高兼容（MySQL 5.7/8.0 语法族）——需真库或官方文档证实。dbx MCP 在册连接无 oceanbase 类型；若环境不可得，按「文档证实 + 真机留账」口径交付，禁冒充「验证通过」。
2. **openGauss 内核基线**：openGauss 系 PG 9.2 演化但语法有分叉；EXPLAIN 支持面推定与 PG 同形（TEXT/JSON）。**GaussDB（华为云版）与 openGauss 社区版的 JDBC URL 前缀差异**（`jdbc:opengauss://` vs `jdbc:gaussdb://`）需查证后写入 D2 的定案。
3. **折叠方向**：openGauss 推定 LOWER（PG 系，KingBase 同款先例）；OceanBase MySQL 模式推定 NONE。两者均属推定——真机打脸按 H2 先例处理（`97e28c3` 订正 + 反向验证），不预设为已验。
4. **URL 推断注册点**：`DialectInference` 的 URL 匹配面位置与既有前缀表形态（agent 侧 URL 全链透传 → IDE 侧推断）——实施前先锚点定位，确认新前缀要在几处登记。
5. **ExplainSupport 显式表登记格式**：按引擎文档登记 OB / openGauss 的 EXPLAIN 输出格式支持位，不臆测格式名。
6. **Dialect 契约测试冻结面**：O-51/O-52 的契约断言族（折叠方向、identity 包装）对新增 object 的约束范围——实施前核对契约测试的扫描面，确认新方言要过哪几条。

## 三、实施步骤（草案）

1. 单测先行：双方言的格式化 / 折叠 / limit / 引号规则语料（对照 MySQL、PG 既有语料模板）；含反向验证夹具（摘掉注册 → 推断/格式化回落失败路径必红）。
2. `Dialect` 双 object 注册 + 折叠方向 + renderLimit + 换行词表。
3. `ExplainSupport` 表登记 + `ExplainGate` 词法核对（EXPLAIN 关键字两侧预期无差异，核对即可）。
4. `DialectInference` URL 前缀登记（前缀清单按 §二.2/§二.4 查证结果）。
5. 设置页 / i18n 文案（方言显示名，双 bundle 同步）。
6. 定向测试（`--tests` Dialect 族 + ExplainSupport 族；纯逻辑可走 `testFast`）。不跑全量、不跑构建校验、不 clean——三档分层口径。

## 四、验证

- 定向测试全绿 + 反向验证读数（§三.1 夹具）。
- dbx MCP 真库探询（用户定例：需真库验证先问 dbx MCP）；在册无 OB / openGauss 连接则如实登记「环境受限未验证」，交付说明按「引擎级已验 / 文档级已验 / 环境受限」三态写。
- 不跑 verifyGate（核对四判据：不动 build.gradle.kts 与依赖、不动 plugin.xml 的 EP 与 since-build、不引入新平台 API 面或反射入口、不加 MCP 工具——预期全不触发；若实施中打破任一条，升级为大改动并复跑）。

## 五、决策点

| # | 决策 | 选项 | 倾向 |
|---|------|------|------|
| D1 | OceanBase Oracle 模式 | A 不做（MySQL 模式覆盖主流装机）/ B 同任务做（体量翻倍 + 需 Oracle 形态真机） | A |
| D2 | openGauss 与 GaussDB | A 一个 object 两 URL 前缀 / B 只做 openGauss | A（以前缀查证为前提） |
| D3 | TiDB 顺手加不加 | A 登记观察 / B 顺手注册（MySQL 兼容推定） | A——推定不白拿，TiDB 分布语法有差异，真机验证跟上再说 |

## 六、风险

- 推定被打脸（折叠方向 / EXPLAIN 格式 / 兼容面）：H2 先例处理——真机订正 + 反向验证 + 契约断言同步。
- 宣传口径先于验证：对外「信创四备」叙事在引擎级验证前只说「已适配」，不说「已验证」。
- `Dialect.kt` 与 O-51/O-52 契约测试的冻结面：新增 object 必须过契约族，防「断言把缺口冻成期望」（㉘ 教训）。
- 与在途任务撞 `Dialect.kt` 写集：开工前按登记表 check（O-51 已交付，预期无冲突；如有在途，串行）。

## 七、实施记录

- 实施日期：2026-09-23；分支 `t-dialect-xc`（基线 dev `0f4b531`）；沙盒 `MPSV-DialectXc`（登记名 T-DialectXc，已注销）；`build.gradle.kts` 沙盒临时改动已还原（git diff 零残留）。

### 7.1 交付物（提交清单）

| 提交 | 内容 |
|---|---|
| 见 git log `feat(sql)` | `Dialect.kt` 双 object（`OceanBaseDialect`/`OpenGaussDialect`）+ 注册表扩容 9→11 + `ExplainSupport.kt` 两行登记 + URL 前缀三处登记（`DialectInference.kt` IDE 侧推断 / `JdbcUrlDialects.kt` agent 角标 / `McpDialectDetector.kt` MCP 链 URL_PATTERNS+ALIASES）+ `SqlVisualizerExplainTool` 校验报错文案补全 + 契约测试同步（`DialectTest`/`DialectExplainTest`/`CrudSqlGeneratorTest`/`DialectInferenceTest`/`JdbcUrlDialectsTest`/`McpRenderInputTest` 冻结面解冻）+ 新增 `DialectXcFamilyTest`（双方言语料 + 反向验证夹具）。代码与测试同一提交：言语料用例与 URL 推断链夹具互相咬合，拆开会产生中间红提交 |
| 见 git log `docs(plan)` | 计划 §七 本节回填 + 陈旧计数文案订正（marketplace 页 9→11 种、`SqlTypeClassifierTest`/`DialectRenderDumpTest` 注释） |

方言显示名定为 `OceanBase` / `openGauss`（品牌 ASCII 名，与既有 `MySQL`/`PostgreSQL` 命名风格一致；DM/KingBase 的中文后缀是既有先例的特例，非模板）。

### 7.2 §二 六条待验证前提证伪结论

1. **OceanBase MySQL 模式兼容面**：部分证实（文档级）。官方《与 MySQL 兼容性对比》声明 5.7/8.0 绝大部分语法兼容；EXPLAIN 文法官方在册（见 7.4）；反引号/LIMIT/`ON DUPLICATE KEY UPDATE`（官方《唯一键冲突》）形态成立。**dbx 在册确无 oceanbase 连接**——环境受限未验证，真机留账（折叠方向 NONE 与保留字集合均属推定，打脸按 H2 先例订正）。
2. **openGauss 内核基线**：证实且**超预期**——dbx 在册连接里实际存在 **openGauss 5.0.0 真库**（`GaussDB / openGauss 5.0.0`，dbx-test-host:20105/dbx_test），与计划「预期没有」相反（计划依据的连接清单过时）。EXPLAIN 与 PG 同形获**真机实证**（见 7.4）。GaussDB 前缀差异查证：华为官方《Creating a Database Connection》实锤 `jdbc:gaussdb://`，与 `jdbc:opengauss://`（openGauss 官方）并存 → D2=A 落地为**一 object 三前缀键**（opengauss + gaussdb 两个子协议都归 openGauss；oceanbase 为 OB 自己的）。
3. **折叠方向**：openGauss=LOWER 由推定升级为**真机实锤**（`INFORMATION_SCHEMA.TABLES` 裸大写 455 行命中；`information_schema."TABLES"` 报 `relation "information_schema.TABLES" does not exist on gaussdb`）；附带实锤反引号不可用（`` SELECT 1 AS `x` `` → syntax error）。OceanBase=NONE 维持推定（无真机）。
4. **URL 推断注册点**：证实为**三处**而非两处——IDE 侧 `UrlSubprotocolDialects`（`DialectInference.kt`）、agent 角标 `JdbcUrlDialects.kt`、MCP 链 `McpDialectDetector.kt`（URL_PATTERNS + ALIASES 两张表）。**计划前提部分证伪**：`JdbcUrlDialects.kt` 在册已有 `"opengauss" to "openGauss"`（计划「全文 grep 零命中」对该文件不成立——openGauss 此前只有角标无渲染方言），本轮只需补 `oceanbase`/`gaussdb` 两个键。
5. **ExplainSupport 表登记格式**：OB 登 TABLE 档（官方 V4.x《SQL 执行计划简介》：`EXPLAIN [explain_type] dml_statement`、`FORMAT = {TRADITIONAL | JSON}`、缺省 TRADITIONAL 表格行）；openGauss 登 JSON 档（真机 `EXPLAIN (FORMAT JSON) SELECT 1` 返回单列 `QUERY PLAN` JSON 文本）。均未臆测格式名。
6. **Dialect 契约测试冻结面**：核对出 5 处冻结断言并同步解冻——`DialectTest.注册表_按名称与回退`（全量名单）、`DialectTest.标识符折叠方向族`（LOWER 族点名）、`CrudSqlGeneratorTest.nonFoldingDialects...`（NONE 族点名 + OceanBase 入列）、`CrudSqlGeneratorTest.onlyEvidenceBackedDialectsDeclareInsertWrapper`（包装白名单不改——双新方言保持默认无包装，断言原样绿）、`DialectExplainTest.守卫-支持表覆盖注册表全量方言`（自动覆盖，补两条字面量用例）。

### 7.3 dbx 真库探询（只读通道，2026-09-23）

**openGauss 5.0.0 探针读数**（会话已关闭，零写入——全部为 SELECT/EXPLAIN 规划期探针）：

| 探针 | 读数 | 结论 |
|---|---|---|
| `SELECT version()` | `(openGauss 5.0.0 build ...)` | 引擎确认 |
| `SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES` | 455 行 | 裸大写折小写命中 → LOWER |
| `SELECT COUNT(*) FROM information_schema."TABLES"` | `relation "information_schema.TABLES" does not exist on gaussdb` | 引号保留大小写 → LOWER（与 PG/KingBase 同向） |
| `SELECT 1 AS \`x\`` | `syntax error at or near "`"` | 反引号不可用 → 双引号族 |
| `EXPLAIN (FORMAT JSON) SELECT 1` | 单列 `QUERY PLAN` JSON 文本 | JSON 档实锤 |
| `EXPLAIN SELECT 1` | 文本计划树 | 默认 TEXT |
| `SELECT 1 LIMIT 1 OFFSET 0` | 1 行 | PG 形态可用 |
| `SELECT 1 LIMIT 1, 2` | **0 行**（无报错） | `LIMIT a,b` 被**按 PG 语义**（OFFSET a LIMIT b）静默错解 → MySQL 形态 LIMIT 不可用，必须渲染 PG 形态 |
| `EXPLAIN INSERT INTO __no_such_table__ ... ON DUPLICATE KEY UPDATE name = VALUES(name)` | `relation ... does not exist`（规划期错） | **MySQL 形态 upsert 语法解析通过** |
| `EXPLAIN INSERT INTO __no_such_table__ ... ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name` | `syntax error at or near "CONFLICT ("` | **PG 形态 upsert 语法不支持** |

**关键证伪**：openGauss 虽为 PG 系，原生 upsert 却是 **MySQL 形态**（官方《INSERT》on_duplicate_key 文法同向，`VALUES(column_name)` 引用在册）——照抄 KingBase=PG 形态先例会产出语法错误 SQL。「族=形态」推定在 upsert 维度被打脸，`OpenGaussDialect.renderUpsert` 按真机证据落 `renderMySqlStyleUpsert`（引用符号仍随本方言双引号），并用 `opengauss_upsert_MySQL形态_非PG_ON_CONFLICT` 用例钉死。

**OceanBase**：dbx 在册无 oceanbase 类型连接 → 环境受限未验证，全部形态按文档级登记（7.4），未冒充「验证通过」。

### 7.4 查证结论与证据层级（三态口径）

| 项 | 结论 | 层级 | 出处 |
|---|---|---|---|
| `jdbc:oceanbase://host:port/db` | OceanBase Connector/J URL 形态（另有 hamode/loadbalance 变体，子协议同为 oceanbase） | 文档级 | OceanBase 官方《Database URL》（Connector/J V2.4.16，en.oceanbase.com/docs） |
| OB MySQL 模式 EXPLAIN=表格行 | `FORMAT={TRADITIONAL\|JSON}` 缺省 TRADITIONAL | 文档级 | OceanBase 官方《SQL 执行计划简介》（V4.2.1，oceanbase.com/docs） |
| OB MySQL 模式反引号/LIMIT/`ON DUPLICATE KEY UPDATE` | MySQL 5.7/8.0 兼容面在册 | 文档级 | OceanBase 官方《与 MySQL 兼容性对比》《唯一键冲突》 |
| `jdbc:opengauss://host:port/database` | openGauss JDBC URL 形态 | 文档级 | openGauss 官方《Connecting to a Database》（docs.opengauss.org） |
| `jdbc:gaussdb://host:port/database` | 华为 GaussDB 驱动 URL 前缀，与 openGauss 同内核 → 同 object | 文档级 | 华为 GaussDB 官方《Creating a Database Connection》（doc.hcs.huawei.com） |
| openGauss `EXPLAIN (FORMAT JSON)` | 单列 JSON 文本；官方文法 `FORMAT { TEXT \| XML \| JSON \| YAML }` | **引擎级（真机）** | openGauss 5.0.0 实测 + 官方《EXPLAIN》（6.0.0） |
| openGauss 折叠 LOWER/双引号/LIMIT OFFSET | 见 7.3 读数 | **引擎级（真机）** | openGauss 5.0.0 实测 |
| OceanBase 全形态 | 推定 + 官方文档 | **环境受限未验证**（真机留账） | — |

### 7.5 验证读数

- **反向验证（摘掉注册 → 必红）**：注释 `DialectRegistry.dialects` 末两项后 `./gradlew test --tests DialectXcFamilyTest --tests DialectTest` → **30 tests completed, 4 failed**：`注册表身份_摘掉注册即红`、`url推断_三前缀命中且全链落到本方言对象`（DialectXcFamilyTest.kt:161/:173）、`注册表_按名称与回退`、`标识符折叠方向族`（DialectTest.kt:79/:143）——URL 推断与格式化回落路径如预期变红。
- **还原注册 → 绿**：定向 9 类 `--tests` 全量 `./gradlew test` → **BUILD SUCCESSFUL，110 tests, 0 failed, 0 skipped**（DialectXcFamilyTest 11 / DialectTest 19 / DialectExplainTest 23 / CrudSqlGeneratorTest 27 / DialectInferenceTest 9 / JdbcUrlDialectsTest 4 / McpDialectDetectorTest 6 / McpRenderInputTest 6 / SqlKeywordsTest 5）。
- **testFast 快桶全量**：`./gradlew testFast` → **BUILD SUCCESSFUL，3189 tests, 0 failed, 6 skipped**（skip 为既有跳过项）——纯逻辑面回归零新增失败。
- 未跑全量 testShards/test 全量、未跑构建校验、未 clean、未降片（三档分层口径）。

### 7.6 i18n / 设置页核实结论

设置页方言下拉（全局 `PluginSettingsPanel.kt:195` 与项目级 `ProjectPluginSettingsConfigurable.kt:165`）均直接取 `DialectRegistry.dialects.map { it.name }`，**无按方言的 i18n key**（双 bundle 只有 `settings.dialect.*` 通用键）——新方言 ASCII 显示名无需也无法加 bundle 条目，双 bundle 零改动即天然同步。此项按实况登记为「核实无需改动」。

### 7.7 未做项与观察登记

- **D1=A 已执行**：OceanBase Oracle 模式不做；**D3=A 已执行**：TiDB 登记观察（`url推断_未知前缀仍不猜` 用例把 `jdbc:tidb://` 钉在不猜面）。
- **驱动跳帧前缀未动**：`CallerSourceLocator.kt`/`SourceInterceptorTemplates.kt`/agent StackLocator 的 JDBC 驱动族前缀表（`dm.jdbc.`/`com.kingbase8.` 等）未加 `com.oceanbase.`/`org.opengauss.`——属 T-LocatorSkip 家族写集，本任务写集不含；OceanBase/openGauss 用户如遇日志跳帧误指可按该家族流程增量（观察项）。
- **`@KeySequence` 方言集未动**：`BatchOperationConverter.KEY_SEQUENCE_DIALECT_NAMES` 只含 Oracle/DM/KingBase（Oracle 式序列方言）；PostgreSQL 本就不在列，openGauss 同为 PG 系不加（与既有口径一致，非遗漏）。
- **MCP 工具描述未动**：`SqlVisualizerExplainTool` 的 `@McpDescription`（工具声明面）保持原样——它本就是非穷举枚举（连 SQLite 都未列）；仅更新了**运行时校验报错文案**（`supported:` 清单，非声明面）。`plugin.xml`/`build.gradle.kts`/MCP 工具声明零改动（verifyGate 四判据全不触发）。
- **保留字集合为基线复用**：OceanBase 用 `MYSQL_RESERVED`、openGauss 用 `PG_RESERVED`（各自 KDoc 标注基线口径）；双方言专属保留字黄金清单未逐词核对（OB/openGauss 官方保留字附录体量大，且本任务无真机逐词验证面）——真机到位后按 DM/KingBase 黄金清单先例增量。
- **对外口径**：OceanBase/openGauss 为「已适配」（openGauss 核心形态引擎级已验，OceanBase 文档级）；「信创四备」叙事在 OceanBase 真机验证前不说「已验证」。
