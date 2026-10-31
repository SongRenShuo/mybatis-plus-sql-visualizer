# MCP 工具 Agent 使用指南

- 任务编号：O-89（MCP 工具使用指南，与 R7 二合一）
- 面向读者：接入本插件 MCP 工具的外部 AI agent（Claude Code、Cursor、JetBrains AI Assistant 的 agent 等）及其操作者。
- 口径来源：工具名、入参、只读/写语义、门控规则全部取自源码（`McpToolGate.kt` 的 TOOL_NAMES 清单与各 `SqlVisualizer*Tool.kt` 的 `@McpDescription`），未在源码出现的能力本文不写。
- 官网分发路径待发行窗口同步（部署须用户明示）。

---

## 一、接入前提

- 运行环境：IntelliJ IDEA（IU）2025.2 及以上，IDE 内置 MCP Server。更老版本或未装 MCP Server 的环境插件零影响，工具不注册。
- 插件侧开关：设置页「MCP 工具」子页有总开关（启用/停用插件 MCP 功能）和工具级启用/禁用清单。插件开关与 IDE「工具 → MCP 服务器」的平台开关相互独立，两处都要开 agent 才看得见工具。
- 生效时机：开关切换在下一次工具列表刷新或新会话即生效，不需要重启 IDE。
- 工具命名：全部工具带 `sqlv_` 前缀，共十一个，全部只读（readOnlyHint=true，destructiveHint=false）——没有任何工具会执行你给的 SQL 或改动项目文件。

## 二、工具总表

| 工具 | 用途一句话 | 是否需要项目/数据源 |
|------|-----------|-------------------|
| sqlv_render_sql | 源码定位渲染出带真实参数的可执行 SQL | 需项目 |
| sqlv_render_logs | 应用日志文本还原成可执行 SQL | 纯文本 |
| sqlv_schema_context | 从 IDE 配置的数据源取紧凑表列结构 | 需数据源 |
| sqlv_explain | 对单条 SELECT 跑 EXPLAIN 出执行计划 | 需数据源 |
| sqlv_check_sql | 静态写风险与语句类型核查 | 纯文本 |
| sqlv_rollback_plan | 为 INSERT/UPDATE/DELETE 生成回滚预案（不执行） | 纯文本 |
| sqlv_perf_advice | 21 条规则的静态性能建议 | 纯文本 |
| sqlv_crud_sql | 实体类生成 CRUD 四件套 SQL | 需项目（索引就绪） |
| sqlv_reverse_sql | SQL 转 MyBatis-Plus QueryWrapper 代码 | 纯文本 |
| sqlv_mapper_check | mapper XML 契约核查 + 渲染产物风险分析 | xmlSnippet 纯文本 / filePath 需项目 |
| sqlv_history_search | 检索本地 SQL 历史落盘（关键词/正则/时间/来源文件） | 纯检索，不执行 |

共同约定：

- 带 `filePath` 入参的工具统一接受项目相对路径或项目内绝对路径；项目外路径与 `..` 上跳一律拒绝。
- 纯文本工具（render_logs / check_sql / rollback_plan / perf_advice / reverse_sql / mapper_check 带 snippet 时）不依赖项目，agent 在任何会话都能调。
- 调用审计：每次调用记入 idea.log（INFO 级，grep `[MCP-AUDIT]`），含工具名、状态、耗时和不超过 120 字符的入参摘要（只记 SQL 文本摘要，不记参数值）。

## 三、逐工具说明

### 3.1 sqlv_render_sql — 源码渲染

用途：把 MyBatis-Plus Wrapper 链、mapper XML 语句、注解 SQL（@Select 等）、JPA @Query/派生查询/CriteriaQuery、HQL、JdbcTemplate 字符串 SQL 渲染成带真实参数值的可执行 SQL。这是确定性渲染器，返回 SQL 本身，不是校验结论。

| 入参 | 类型 | 必填 | 说明 |
|------|------|------|------|
| filePath | string | 二选一 | 项目相对路径（如 `src/main/resources/mapper/UserMapper.xml`）或项目内绝对路径 |
| line | int | 随 filePath | 1 起始的行号；省略取第一条语句 |
| xmlSnippet | string | 二选一 | mapper XML 文本 |
| sourceType | string | 随 xmlSnippet | 填 `xml_mapper` |

调用时机：agent 需要知道「这行源码实际执行什么 SQL」时。一次一条语句：位置命中多条候选时渲染第一条并在输出注明。

失败语义：`INVALID_ARGUMENT`（两种入参形态都没给全）；`SOURCE_NOT_FOUND`（路径不在项目内或文件不存在）。

### 3.2 sqlv_render_logs — 日志还原

用途：从应用日志文本还原可执行 SQL，支持 MyBatis 的 `Preparing:`/`Parameters:` 对、Hibernate 的 `Hibernate:` 加 `binding parameter` 行、Spring JdbcTemplate debug 行。

| 入参 | 类型 | 必填 | 说明 |
|------|------|------|------|
| logText | string | 是 | 原始多行日志文本 |
| sqlPrefix / paramPrefix | string | 否 | 前缀覆盖，默认取插件日志捕获设置（`Preparing:` 等） |
| hibernateSqlPrefix / hibernateParamPrefix | string | 否 | 同上 |
| mixedPrefixMatching | bool | 否 | 混合前缀匹配开关，默认取设置 |

调用时机：手里只有日志、要还原真实 SQL 时。每条还原语句前有 `-- SQL #N` 注释标明来源格式。多语句日志放心整段传入。

失败语义：`INVALID_ARGUMENT`（logText 为空）。还原结果建议接 sqlv_check_sql 评估写风险后再执行。

### 3.3 sqlv_schema_context — 表列结构

用途：从 IDE 已配置的真实数据源返回紧凑的表+列上下文（名称、类型、注释、NOT NULL 标记）。凭据不离开 IDE。

| 入参 | 类型 | 必填 | 说明 |
|------|------|------|------|
| tables | string | 是 | 逗号分隔的表白名单，1~50 个 |
| dataSource | string | 否 | 数据源显示名；配置了多个数据源时建议显式指名 |
| maxTables | int | 否 | 返回上限，默认 8，硬顶 20 |

调用时机：写 SQL 前需要真实列名和类型时，与 sqlv_render_sql 配对。表名在数据源自身 schema/database（取自 JDBC URL）内匹配，不会混入同名系统表。

失败语义：`DATASOURCE_NOT_FOUND`（名字没对上，错误文本带可用数据源名清单）；`DATASOURCE_UNREACHABLE`；`SCHEMA_NOT_AVAILABLE`。

### 3.4 sqlv_explain — 执行计划

用途：对单条只读 SELECT 在真实数据源上跑 EXPLAIN，返回计划文本。方言支持 MySQL/MariaDB/PostgreSQL/Oracle/SQL Server/H2/SQLite/DM/KingBase/OceanBase/openGauss/GaussDB，`dialect` 省略时按数据源 URL 自动识别。

| 入参 | 类型 | 必填 | 说明 |
|------|------|------|------|
| selectSql | string | 是 | 单条纯 SELECT（禁锁读、禁 INTO、禁写） |
| dataSource | string | 否 | 数据源显示名 |
| dialect | string | 否 | 方言覆盖 |
| maxRows | int | 否 | 计划行数上限，默认 200，硬顶 1000；越界静默钳制不报错 |

调用时机：已拿到 SQL（通常来自 sqlv_render_sql 或 sqlv_render_logs）、想看执行计划时。严格只读，拒绝 fail-closed。

失败语义：`MCP_READ_ONLY`（语句本身是写语句——结构判定优先）；`SQL_BLOCKED`（形态违规：多条语句、锁读 FOR UPDATE/SHARE、非单条纯 SELECT）；`DATASOURCE_NOT_FOUND` / `DATASOURCE_UNREACHABLE`；`NOT_A_SQL_DATASOURCE`（数据源 URL 子协议属非关系型家族，如 `jdbc:redis://`、`jdbc:mongodb://`——此时不再兜底猜方言，直接拒绝；错误文本只带子协议，不含账号口令）；`DIALECT_UNSUPPORTED`。

达梦（DM）说明：计划语句为 `EXPLAIN FOR <sql>`，结果为结构化计划表（19 列，含 `OPERATION`/`TAB_NAME`/`IDX_NAME`/`SCAN_TYPE`/`ROW_NUMS`/`COST`/`FILTER` 等）；裸 `EXPLAIN <sql>` 在 DM8 上不返回结果集（2026-10-31 真机实测），故不要自行拼 `EXPLAIN` 前缀。

### 3.5 sqlv_check_sql — 写风险核查

用途：纯文本静态核查：语句数（切分）、类型分类（QUERY/DML/DDL）、七规则无界写探测（无 WHERE 的 UPDATE/DELETE 等）。绝不执行。

| 入参 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sql | string | 是 | 待核查 SQL |
| chain | bool | 否 | 置 true 追加「执行前清单」（见下），默认 false（输出与旧版逐字节一致） |
| primaryKeyColumns | string | 否 | 主键列，逗号分隔；chain 清单里用它把回滚谓词收到主键上（评级 COMPLETE） |
| dialect | string | 否 | 目标方言名；给了才产出 UPDATE/DELETE 的只读 COUNT 试算语句 |

调用时机：任何执行前；尤其是 agent 自己生成的 UPDATE/DELETE。这是置信度评估的对面——它给确定性证据（语句真实形态）。

chain=true 的执行前清单（NEW-23）：一次调用拿到写路径的四件事——①风险等级与执行闸门（`riskLevel` LOW/MEDIUM/HIGH、`executionGate` CONFIRM/BLOCKED/READ_ONLY，判定来自无界写七规则，DDL 归 HIGH）②影响面要点（UPDATE/DELETE 的只读 `SELECT COUNT(*)` 试算语句，多表/无 WHERE 时如实降级说明）③回滚计划（与 `sqlv_rollback_plan` 同一生成器、同一条 SQL 文本、同一主键口径，评级 COMPLETE/PARTIAL/UNABLE）④执行交接说明：**本工具不执行，也不开写通道**——执行仍在 IDE 预览面板的确认流里完成，那里串着无界写探测、只读豁免证明与方言闸门。`executionGate: BLOCKED` 的语句（无界写或 DDL）不要交给任何自动化通道，先把谓词收窄或在 IDE 里显式确认全表范围。

失败语义：`INVALID_ARGUMENT`（sql 为空 / dialect 不可识别）。

### 3.6 sqlv_rollback_plan — 回滚预案

用途：为 INSERT/UPDATE/DELETE 生成回滚预案，不执行任何东西。输出含完整度评级（COMPLETE=已定位主键、可生成逐行精确回滚语句，仅 INSERT 可达；PARTIAL=可执行但有风险警告，如 INSERT 未给主键列；UNABLE=不可推导，语句清单留空并给恢复指引——UPDATE/DELETE 没有 before-image，一律 UNABLE，只给快照 SELECT 与恢复途径）、候选回滚语句、警告、执行前 COUNT 检查模板和快照 SELECT 模板。

| 入参 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sql | string | 是 | 目标写语句 |
| primaryKeyColumns | string | 否 | 逗号分隔主键列；给了才能生成逐行精确回滚 |

调用时机：准备执行危险写语句前先要预案。

失败语义：`INVALID_ARGUMENT`（sql 为空）。

### 3.7 sqlv_perf_advice — 性能建议

用途：21 条规则引擎的静态性能建议（复用 IDE 内置分析链），纯静态、不触数据库。

| 入参 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sql | string | 是 | 待分析 SQL |

调用时机：EXPLAIN 之前或之后做静态初筛；最多返回 30 条发现，单条建议不超过 200 字符。

失败语义：`INVALID_ARGUMENT`（sql 为空）。

### 3.8 sqlv_crud_sql — 实体生成 CRUD

用途：对项目内 MyBatis-Plus 实体类生成 INSERT/UPDATE/SELECT/DELETE 四件套，走与 IDE 右键动作同一链路：主键处理、INSERT 排除自动生成列、@Version 乐观锁 SET 尾巴、@TableLogic 逻辑删除变体；无主键降级为 `1 = 0` 占位 WHERE。

| 入参 | 类型 | 必填 | 说明 |
|------|------|------|------|
| filePath | string | 二选一 | .java/.kt 实体文件路径（项目相对或项目内绝对） |
| className | string | 二选一 | 全限定类名 |

调用时机：agent 需要按实体生成标准 CRUD 时。产物是 SQL 文本，不执行；真执行前接 sqlv_check_sql。

失败语义：`INVALID_ARGUMENT`（两个都没给 / 实体无可映射列）；`SOURCE_NOT_FOUND`；`PROJECT_NOT_READY`（IDE 索引进行中，等索引完成重试）。

### 3.9 sqlv_reverse_sql — SQL 转代码

用途：单条 SQL（SELECT/UPDATE/DELETE/INSERT，支持 JOIN）转 MyBatis-Plus Java 代码——字符串列形式的 QueryWrapper 链，可直接粘贴。

| 入参 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sql | string | 是 | 单条 SQL |

调用时机：要把 SQL 固化进 Java 代码时。解析失败时错误文本带行号和原因。实体类型的 LambdaQueryWrapper 形态（需在 IDE 里解析实体类）目前只在 IDE 的 SQL-to-Java 动作里有，MCP 工具不提供。

失败语义：`INVALID_ARGUMENT`（sql 为空或解析失败，含行号）。

### 3.10 sqlv_mapper_check — mapper 契约核查

用途：核查 MyBatis mapper XML：解析错误、语句清单（id、kind、parameterType、`#{}` 参数计数、`${}` 直接替换计数——注入面）。`simulate=true` 时额外用空模拟参数渲染每条语句并对渲染产物跑写风险与性能分析。

| 入参 | 类型 | 必填 | 说明 |
|------|------|------|------|
| xmlSnippet | string | 二选一 | mapper XML 文本 |
| filePath | string | 二选一 | mapper 文件路径（项目相对或项目内绝对） |
| simulate | bool | 否 | 默认 false；true 时渲染并分析 |

调用时机：review mapper 文件、评估注入面、要「渲染产物级」证据时。校验器给置信度评分，这里给确定性证据——静态校验器产不出的渲染产物实证。

失败语义：`INVALID_ARGUMENT`（两个都没给 / 不是合法 mapper XML）；`SOURCE_NOT_FOUND`。

### 3.11 sqlv_history_search — 历史检索

用途：检索插件落盘的 SQL 历史（之前预览/捕获过的语句），按条件筛出历史条目摘要。只读检索，不执行任何 SQL，也不需要数据源。

| 入参 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | string | 否 | 关键词，大小写不敏感，匹配 SQL 文本与 mapper 语句标识 |
| regex | string | 否 | 正则匹配 SQL 文本（与 keyword 二选一或组合，按工具返回的错误提示为准） |
| startTime / endTime | string | 否 | 时间范围过滤；缺省只查当天条目 |
| allDates | bool | 否 | true 时跨全部历史日期检索（默认 false=仅当日） |
| sourceFile | string | 否 | 按来源文件路径子串过滤 |
| maxResults | int | 否 | 返回条数上限，默认 20，硬上限 100 |

返回：命中条目摘要（SQL 预览截断至约 8 行/600 字符、命中计数、是否截断标记），最新在前；扫描量有封顶（约 20 万条），超量会在返回里说明。

调用时机：想知道「之前有没有见过这条 SQL / 某个表被哪些语句用过 / 今天预览过什么」——先检索历史再决定要不要 render/render_logs，比盲猜更快。

失败语义：`INVALID_ARGUMENT`（非法正则等）。

## 四、组合工作流

典型链路（按任务选段，不必全走）：

1. 源码预览：`sqlv_render_sql`（给 filePath+line）拿到真实 SQL；不想读源码、只有日志时用 `sqlv_render_logs` 还原。
2. 结构与风险：`sqlv_schema_context` 拿列类型；`sqlv_check_sql` 判类型与无界写；写语句先过 `sqlv_rollback_plan` 拿回滚预案。想一次拿全写路径的证据，改调 `sqlv_check_sql(chain=true, primaryKeyColumns=…, dialect=…)`——风险等级、执行闸门、只读 COUNT 试算、回滚计划与执行交接在一份清单里。
3. 执行计划：`sqlv_explain`（只收纯 SELECT）看真实计划；`sqlv_perf_advice` 补静态建议。
4. 代码化与 review：`sqlv_reverse_sql` 把 SQL 固化成 Wrapper 链；`sqlv_mapper_check` 带 simulate 复核 mapper 渲染产物。
5. 历史检索：`sqlv_history_search` 查本地历史落盘（默认当日，`allDates=true` 跨全量）——先检索历史再决定是否 render/render_logs，避免重复还原；只读，不执行。

失败语义约定（全工具通用）：错误以 `[错误码] 文本` 结构返回，agent 可解析。错误码：`MCP_READ_ONLY`、`SQL_BLOCKED`、`INVALID_ARGUMENT`、`DATASOURCE_NOT_FOUND`、`DATASOURCE_UNREACHABLE`、`NOT_A_SQL_DATASOURCE`、`SCHEMA_NOT_AVAILABLE`、`DIALECT_UNSUPPORTED`、`SOURCE_NOT_FOUND`、`PROJECT_NOT_READY`。拒绝通道零弹窗，agent 收到结构化错误即可自行决策重试或换路。

## 五、给 agent 的调用建议

- 何时用哪个：知道源码位置 → render_sql；只有日志 → render_logs；要执行前 → check_sql（`chain=true` 一次拿全写路径清单；仅看风险用默认参数）；执行前优化 → explain（SELECT）+ perf_advice；写代码 → reverse_sql / crud_sql；review mapper → mapper_check（开 simulate）。
- 数据源入参：配置了多个数据源时给 `dataSource` 显式指名；省略时的行为以错误文本里的可用清单为准。
- 所有工具都不会执行你的 SQL 或改文件；真正的执行、写库、回滚由 agent/人在自己的通道完成，插件产出的是决策证据。
- 调用不生效先查三层：平台「工具 → MCP 服务器」开关、插件设置页「MCP 工具」总开关、工具级禁用清单（工具被禁用后从外部会话清单隐藏，表现是 agent 看不到该工具而不是报错）。排障看 idea.log 的 `[MCP-AUDIT]` 行。
- 版本门槛：MCP 工具仅在 IDEA 2025.2+ 且装了插件的环境注册（243 等老版本线不支持 MCP 特性，属平台设计而非缺陷）。
