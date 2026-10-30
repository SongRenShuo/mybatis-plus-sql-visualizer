# T-Fr45NonMybatisStack —— FR-0045 非 MyBatis 技术栈六项能力缺口：深度分析与分波开发计划

- 任务编号：T-Fr45NonMybatisStack（建议占 **O-157**；O-156 已被 T-PreviewPsiDialect 建议占用、O-154 有与竞品第三十三轮 rp10 的撞号对账在途——实施前按口径 ㉕ 再实测台账确认）
- 立项日期：2026-10-31
- 触发：官网需求池 **FR-0045**「补上非 MyBatis 技术栈的 6 项能力（自研 SQL 模板 / Spring JDBC / 达梦）」（2026-10-29 09:32 提交，web 来源，提交人李昂，状态 2；正文经用户脱敏）
- 用户环境：Spring Boot 2.5.15 + JDK 8 + Spring JDBC；SQL 不在 MyBatis 里，为**自研模板**（根节点 `<sqls>`、语句节点 `<sql id="x">`、FreeMarker 动态标签 657 处、`${}` 334 处、`:命名参数`），`#{}` 与 `<mapper namespace>` 均为 0 处（67 个 mapper XML / 391 条语句）；数据库**达梦 DM8**；IDEA 2026.1.3 / 插件 **v2026.10.28**
- 性质：需求裁决 + 分波开发计划。**本册先收口裁决**——六项中两项已交付、四项真缺口；第一波小件即期可实施，第二/三波挂拍板
- 分析基线：master `ab40a179`（2026-10-31 00:0x，工作树 clean）

---

## 一、需求盘点

用户按投入产出排序提出 6 项，另有 4 项「不建议做」：

| # | 诉求 | 用户定级 | 本册裁决 |
|---|---|---|---|
| 1 | Spring JdbcTemplate 日志还原（`Executing prepared SQL statement` + `StatementCreatorUtils` 参数行按线程/时间窗配对） | 高优先·成本低 | **已交付**（M47，2026-08-24）——回复使用指路，见 §2.1 |
| 2 | 禁用 API 清单检查（类/方法正则 + 说明 + 级别，编辑器标红 + 存量统计） | 高优先·成本低 | 真缺口，新能力面，**待拍板**（T-BannedApiCheck） |
| 3 | 通用 SQL 模板适配层，解锁 render_sql / mapper_check / history_search（MVP=外部渲染器 hook；完整版=内置模板 SPI） | 中优先 | 真缺口，**采纳 MVP**（T-TemplateHook，第二波） |
| 4 | 达梦 DM 方言补强（EXPLAIN 正文空 / 非 SQL 数据源早拒 / 实测方言矩阵内置） | 中优先 | 真缺口三小件，**即期采纳**（T-Fr45DmGuard，第一波） |
| 5 | 调用点回溯（不依赖 MyBatis 的源码定位） | 中优先 | **已交付**（StackHoverUi + StackBizFrame + CaptureCatchAll），用户版本卡位，见 §2.5 |
| 6 | 优化留痕文档一键生成（SQL 原文+执行计划+耗时+前后对比 → Markdown） | 低优先·黏性高·纯导出 | 真缺口，**即期采纳**（T-TraceDocExport，第一波） |
| — | 不建议做：SELECT 结果比较 / 本地 Schema 比较 / 离线 Trace 导入 / 结构树增强 | — | 复核维持不做，见 §2.7 |

用户环境的关键背景：**报障版本 v2026.10.28 恰好卡在两批交付之间**——栈快照调用点（StackBizFrame，2026-10-29）与 attach 反射兜底捕获（T-CaptureCatchAll，2026-10-30）都晚于它，而 Spring JDBC 日志解析（M47）又早于它两个月。这解释了「六项里两项看似空白」的大部分观感。

---

## 二、逐项深度分析与裁决

### 2.1 第 1 项 Spring JdbcTemplate 日志还原 ——【已交付，无需开发】

**功能在仓内已完整实现且接线齐全：**

- `log/JdbcTemplateLogParser.kt`（M47，首次提交 `71f9cc8a` 2026-08-24，随 2026-09-02 大版本发行，发行说明在案「Spring JdbcTemplate SQL 日志捕获（M47）」）。
- **正则与用户贴的日志行逐字段吻合**：`SQL_ACTION_PATTERN`（JdbcTemplateLogParser.kt:469）匹配 `Executing prepared SQL statement|update|query`/`SQL batch update`/`Calling stored procedure`；`PARAM_LINE_PATTERN`（:481）匹配 `Setting SQL statement parameter value: column index N, parameter value [...], value class [...], SQL type ...`；另有 NamedParameterJdbcTemplate 单行格式（:493）。
- 用户建议的三条设计全部已实现：**线程配对**＝T-JdbcThreadBucket 线程分桶（:25-29，`LogThreadRouter` 按行内线程 token 分桶防并发错挂）；**缺参数降级 `?` 不猜**＝`LogSqlRestorer.restore`（LogSqlRestorer.kt:34，参数不足时剩余 `?` 保留）；**时间窗冲刷**＝静默 1s settle 兜底低频/新线程场景（:235-241）。
- **四条消费链全部接通**：EP 过滤器（SqlConsoleBridgeFilterProvider.kt:56-59）、反射兜底桥（SqlLogConsoleBridge.kt:787/:1126/:1136）、MCP `sqlv_render_logs`（SqlVisualizerRenderLogsTool.kt:157-176，吃纯日志文本）、日志转 SQL 面板（LogToSqlPanel.kt:457-472）。设置开关 `logJdbcEnabled` **默认开**（PluginSettings.kt:474）。

**用户为什么没感知**（回复稿要点，见 §三·三）：功能需要插件侧「捕捉」开启（控制台链）或主动把日志文本喂给 `sqlv_render_logs`；用户两个日志行级别前提（JdbcTemplate 行＝DEBUG、StatementCreatorUtils 参数行＝TRACE，Spring 默认 INFO）他们已满足（引了原文）。

**残余增量（可选搭车件，不阻塞本册）**：`LogThreadRouter.threadTokenOf` 提取的线程 token 只用于分桶、提取后即弃，`LogSqlEntry.threadName` 字段存在（LogSqlModels.kt:126）但 KDoc 语义是「仅 agent 通道」——若要列表条目展示「来自哪个线程」，需在 closeBucketPending 透传并换代该 KDoc。登记不派工，随下轮顺路。

### 2.2 第 2 项 禁用 API 清单检查 ——【真缺口·新能力面·待拍板】

**现状证据**：

- 插件**零 inspection 先例**：plugin.xml 无任何 `localInspection` EP；编辑器级注册只有 lineMarkerProvider 十几处、一个 SQL 语言的 externalAnnotator（plugin.xml:302）、XML referenceContributor（plugin.xml:413，旁边注释明确写「**不做校验器形态**」红线——语境是 XML 引用只产 soft 引用，非 Java inspection，但表述是泛化的）。
- PSI 基础现成：`psi/WrapperLocator.kt:28,98,102,118` 等深度使用 `PsiMethodCallExpression`，方法调用解析经验可复用。
- 设置面与管理 UI 先例可照抄：App 级 `@State` 轻服务＋独立 storage＋DISABLED roaming 的完整模板＝`log/SqlSuppressRuleManager.kt:33-46`（规则模型 SqlSuppressRule.kt:21-27 纯基本类型，XmlSerializer 友好）；管理对话框＝`log/SqlSuppressManagerDialog.kt`（列表＋编辑器，关闭即生效）。**仓内没有 JBTable 行编辑先例**（唯一 JBTable 是 ExplainPlanResultView.kt:185-194 的只读展示）。
- 全项目扫描/统计动作**无先例**（plugin.xml 仅 9 个 action，全部编辑器/DB 视图上下文），需从零写（DumbService＋后台线程纪律）。

**裁决**：立项 **T-BannedApiCheck**，挂用户拍板——拍板点＝①产品方向：插件能力面是否从「SQL 可视化」扩展到「团队规范检查」；②plugin.xml:412 红线注释须随立项修订表述。拍板后按 §三·wave 3 设计要点出独立立项册。用户定级「高优先·通用性极高」与本仓「从未做过编辑器校验」是真实张力，不抢跑。

### 2.3 第 3 项 通用 SQL 模板适配层 ——【真缺口·采纳 MVP（外部渲染器 hook）】

**现状证据**：

- 用户看到的 `[SOURCE_NOT_FOUND] no mapper statement (select/insert/update/delete) at the given location`＝`mcp/McpSourceLocator.renderAtOffset`（McpSourceLocator.kt:140）：XML 分支光标元素向上爬到 `STATEMENT_TAGS`（＝`{select, insert, update, delete}`，**硬编码在** ui/gutter/XmlSqlGutterLineMarkerProvider.kt:88），`<sqls>/<sql id>` 不在名单 → 必然落到 ：164-168 抛错；其后注解 SQL（:182）/Wrapper 链（:190）/HQL·JdbcTemplate·JPA·方法体字符串（:194-229）等 13 类触发面是一段硬编码 if 序列，**没有 StatementResolver 抽象**。
- `sqlv_mapper_check` 走 MyBatis 专用 `XmlMapperParser`，非 select/insert/update/delete 根直接报 INVALID_ARGUMENT（SqlVisualizerMapperCheckTool.kt:100-108）——对自研格式同样不适用。
- `PluginSettings` 无任何 external/command 字段（grep 零命中）。
- 用户列的三个受益工具里 **`sqlv_history_search` 存在**（SqlVisualizerHistorySearchTool.kt:32，搜的是插件捕获历史）——它不依赖 mapper 模板，用户把它列进「解锁清单」是理解偏差，回复稿顺带澄清。

**裁决**：采纳 **MVP＝外部渲染器 hook**（用户自己的两阶段建议，与现状吻合）：插件把「模板文件路径 + sqlId + 参数 JSON」交给用户配置的外部命令，取回 stdout 作为 SQL 展示/分析/EXPLAIN；**完整版内置模板 SPI 缓行**（登记）。挂点两处：①renderAtOffset XML 分支——tag 名命中用户配置的语句 tag 集（如 `sql`）时取 `id` 属性分流外部命令；②链尾兜底 fail（McpSourceLocator.kt:232-241）之前加一步外部模板定位。`Outcome` 载体（:46-54）天然容纳新 `sourceType: external`；工具层签名零改动。第二波立项独立册时细化（进程执行超时、UTF-8、命令注入文档免责、预览/gutter 面是否同步接线）。

### 2.4 第 4 项 DM 方言补强 + 非 SQL 数据源守卫 ——【真缺口·三小件·即期采纳】

**(a) sqlv_explain 在 DM 上计划正文为空——根因已定位，修法待真机读数定档：**

- 每方言 EXPLAIN 档位注册表＝`sql/ExplainSupport.kt:45`（单一事实源，`Dialect.renderExplainStatement` 只读表，Dialect.kt:204）；DM 条目在 ExplainSupport.kt:135：`EXPLAIN {sql}` / **TEXT 档** / 证据「真机实证 DM8（返回 #NSET2 计划文本）」——但该证据来自 **DBX 通道**（memory-archive project-m37），MCP headless 裸 JDBC 通道上「EXPLAIN 返回单结果集计划行」这个前提**从未复验**。
- 正文为空的落点：DM 无 `channelNote`/`postPlanQuery`（Oracle/SQL Server 那套降级件，ExplainSupport.kt:156-173）→ 默认 `executeQuery` 单结果集通道（McpDbExecutor.kt:324-327）→ `collect()` 空 → `McpExplainFormatter.format`（:69-81）在 headers+rows 全空时返回空串 → 工具输出只剩 `dialect: DM（达梦）`（SqlVisualizerExplainTool.kt:51）。与用户实测逐字吻合。
- 修法：真机复探（见 P-1）→ 计划走第二结果集/`execute()` 通道则给 DM 加 `channelNote` 自动切宽容通道（McpDbExecutor.kt:394-411）；需二段式则加 `postPlanQuery`。守卫 `DialectExplainTest` 钉字面量须同步。
- **关联缺口（搭车件）**：`ExplainPlanKey.persistable` 只认 TABLE/JSON 档（ExplainPlanSnapshot.kt:66-69）——**TEXT 档（DM/Oracle/OceanBase/H2/SQLite）的执行计划根本不落历史**，会话内只有上一次可比（ExplainPlanHistorySession）。4a 修好后 DM 计划仍进不了历史，第 6 项对比导出对 DM 依旧断头。搭车方案见 T-TraceDocExport S3。

**(b) 非 SQL 数据源早拒——IDE 推断侧已被 T-DialectInferFix 修掉，MCP explain 链仍是敞口：**

- IDE 侧：`DataSourceUrlProbe`（sql/DataSourceUrlProbe.kt:222-258）经 `DataSourceDialectClassification.classify`（:54-67），`NON_SQL_KEYS`（:36-41）含 redis/mongo 等，`jdbc:redis://` 命中 `isNonSqlUrl`（:74-78）→ **NON_SQL，不投票**（:244-250）——不再产出误导性 MySQL 共识（2026-10-29 已落 master，用户 v2026.10.28 未含）。
- MCP 侧敞口：`McpDialectDetector.URL_PATTERNS`（McpDialectDetector.kt:12-32）不认 `jdbc:redis` → `guessFromUrl` null → `resolve()` **兜底 MySQL**（:87）→ EXPLAIN 发给 Redis 报 JedisDataException——用户实测复现点。`resolveExplainTarget` 四步链（SqlVisualizerExplainTool.kt:62，数据源定位 ：91-112、方言派发 ：115-137）上没有任何「非 SQL 数据源」守卫。
- 修法：`McpToolErrors`（McpToolErrors.kt:12-46，现 10 个错误码）加 **`NOT_A_SQL_DATASOURCE`**；在 `resolveExplainTarget` 拿到数据源 URL 后用现成 `DataSourceDialectClassification.isNonSqlUrl` 判一次即抛结构化错误（带 URL 子协议说明）。可见性见 P-2。其余工具链（check_sql 等）是否同守卫登记不扩面——用户报障点在 explain。

**(c) DM 实测方言矩阵内置——无现成落点，挂拍板：**

- 全仓**没有「方言×函数」能力表**：IFNULL/DATE_FORMAT/NOW/`||`/WM_CONCAT/LISTAGG/GROUP_CONCAT 不存在于任何方言知识表（`DM_RESERVED` 保留字表在 SqlKeywords.kt:216 起，`ifnull` 已在；PerfRulesBatch2.kt:422 的聚合名单是性能规则用，非方言能力）。DmDialect（Dialect.kt:724）已覆盖 MySQL 式 LIMIT、双引号引用（对应反引号 -2007）、identity 包装、DM_RESERVED。
- 用户实测矩阵（LIMIT/LIMIT 0,1/ROWNUM/OFFSET..FETCH FIRST/IFNULL/DATE_FORMAT/NOW()/`||`/WM_CONCAT/LISTAGG 支持；GROUP_CONCAT 报 -2207、反引号报 -2007 不支持）是有价值的资产，但「内置成规则表」需要先拍板落点（证据化代码表＋执行前提示 vs 文档站方言页），且用户读数未经我方真机复验（P-1 顺带可验 GROUP_CONCAT/反引号两条否定项）。**挂拍板，不进第一波主修**。

### 2.5 第 5 项 调用点回溯 ——【已交付，用户版本卡位 + DM 织入缺口已被兜底件补上】

时间线拼起来正好解释用户的观感：

- **机制本体早交付**：agent 通道栈快照 → meta 行 `Xxx.java:行号`（log/LogEntryRendering.kt:301-304）→ 跳转（EntryJumpResolver.kt:48-53 + LogEntryJumpOps.kt:66）→ 调试器风格悬停弹层（ui/StackFrameHover.kt，T-StackHoverUi 2026-10-07 交付，**随 v2026.10.13 发行**——用户 v2026.10.28 已含）。弹层标题带线程名**已实现**（StackFrameHover.kt:69-73 `buildStackPopupTitle`，bundle 键 `panel.stackPopup.titleWithThread`）——项目记忆里「未实施」一条已滞后，本册勘误。
- **用户场景的两块短板都发生在 agent 通道**：①栈快照质量（定时任务/异步丢项目帧）→ T-StackBizFrame 2026-10-29 收口（`768851c5`，已含 master）；②**达梦此前根本不在织入名单**——`StatementWeaver.SPECS` 语句面无 DM 条目、connect 面又因 VerifyError 在 T-ConnCastSafety（2026-10-28）主动撤出 → DM 应用增强捕获结构性不可用，用户拿到的自然「只有 SQL 文本」。→ T-CaptureCatchAll（2026-10-30，随 v2026.10.31-beta.1）attach 反射接口匹配兜底（agent 侧 CatchAllMatcher.java:17-23，`java.sql.Statement/Connection` isAssignableFrom，默认开）**覆盖未知驱动含 DM**。
- **残余**：DM 驱动 attach 端到端真机未验（并入 T-CaptureCoverage 已收窄的未闭环项「事件回流 IDE 面板未验」）；控制台链条目无栈信息＝结构性边界（控制台行没有栈，不是缺口）。控制台链无源码时的轻提示已随 O-147 交付。

**裁决**：不做新开发。回复稿指路＝升级 v2026.10.31-beta.1+ 并开增强捕获；DM attach 真机验证挂在既有在途账。

### 2.6 第 6 项 优化留痕文档一键生成 ——【真缺口·低成本·即期采纳】

**现状证据——三要素齐备，缺组装器与出口**：

- Markdown 生成代码现成：`share/format/MarkdownFormatter.kt`（```sql 代码块 + `> 来源：Xxx.java:42 | 方言 | 时间` meta 行 + `formatAsTable()`），消费方 `share/SqlShareManager.kt:34,55,99`（copyAsMarkdown / copyAsMarkdownTable / saveHtmlReport）。
- 执行计划三要素可取：SQL 原文＝`SqlHistoryEntry.sql`；计划正文＝`ExplainPlanSnapshot`（headers/rows/truncated payload JSON，ExplainPlanSnapshot.kt:18-28，`raw():34` 还原）挂历史条目（SqlHistoryEntry.kt:51）；耗时＝`SqlHistoryEntry.executionTimeMs`（:32）。跨次对比逻辑现成：`ui/ExplainPlanDiff.kt`（rows/cost 增量＋字段级变化，纯逻辑可直测）。
- 出口先例：预览面板「更多」菜单动作集（SqlPreviewPanelResultView.kt:80-96）、历史面板右键、`log/SqlExportService.kt` 后台线程写文件链（CSV/TSV/TXT/SQL）。

**缺口**：①无「留痕文档」组装器（用户流程是固定六段式：现象/基线/根因/手段/前后对比/结论与回滚）；②无导出入口；③**TEXT 档计划不落盘**（§2.4(a) 关联件）——DM/Oracle/OceanBase/H2/SQLite 无历史计划可导，日志面板路径本就不挂计划（计划只在历史条目上）。

**裁决**：立项 **T-TraceDocExport**（第一波），含 TEXT 档快照搭车件（S3）。

### 2.7 用户「不建议做」四项 ——复核维持不做

- **SELECT 结果比较**：仓内两处「对比」都是 **SQL 文本 diff**（预览面板 showSqlDiff＝SqlPreviewPanelSqlView.kt:524-547，基线只有 `previousSql` 单变量即用户说的「非原子快照」；历史面板 CompareSelectedAction＝HistorySqlLogPanel.kt:949-991），**没有结果集数据比较**；用户提的「限 100 行」实为 `HistorySearchService.HARD_MAX_RESULTS = 100`（MCP 历史搜索条数上限，语义无关）。结果集级比较维持不做。
- 本地 Schema 比较 / 离线 Trace 导入 / 「分析 SQL」结构树增强：维持不做，与用户判断一致，回复稿确认即可。

---

## 三、分波实施计划

> 波次启动条件各自独立：第一波即期（用户令即动工）；第二、三波各挂一个拍板点。拍板后第二/三波按惯例出独立立项册细化，本册给设计要点与边界。

### 第一波（即期，两任务可并行两工作树）

#### 任务 A：T-Fr45DmGuard（第 4 项 a+b）

- **S1（4b）非 SQL 数据源早拒**：`McpToolErrors` 加 `NOT_A_SQL_DATASOURCE`（错误文案含 URL 子协议与「该数据源不是关系型 SQL 库」指引）；`SqlVisualizerExplainTool.resolveExplainTarget` 在数据源定位后（:125-130 一带）用 `DataSourceDialectClassification.isNonSqlUrl` 判定即抛。复用不复制（NON_SQL_KEYS 单点维护），可见性确认见 P-2。测试：redis/mongo URL → 结构化错误；正常 JDBC URL 不受扰；IDE 推断侧回归（DialectInferFallbackGuardTest 族）。反向验证：摘除守卫 → redis 用例复红。
- **S2（4a）DM EXPLAIN 真机复探与档位修正**：先 DBX 真机复探（载体见 P-1，凭据只引 `.local/test-databases.md`）：`EXPLAIN SELECT ...` 经裸 JDBC 的结果集形态/headers/rows/是否第二结果集；顺带验 GROUP_CONCAT（预期 -2207）与反引号（预期 -2007）两条否定项为 4c 备证。按读数改 `ExplainSupport` DM 条目（加 `channelNote` 或 `postPlanQuery`，evidence 字段改记本轮真机通道）；`DialectExplainTest` 字面量同步；反向验证＝夹具模拟空结果集 → format 空串复现（对应用户读数）。
- **S3 测试与文档**：定向（mcp 包＋sql 包）；文档站 features/mcp 错误码表补 NOT_A_SQL_DATASOURCE、方言页 DM 段同步（兄弟仓，随批 push）。
- **范围外（登记）**：4c 能力表（挂拍板）；其余 MCP 工具的非 SQL 守卫扩面。

#### 任务 B：T-TraceDocExport（第 6 项）

- **S1 留痕组装器**：`share/format/TraceDocFormatter.kt`（纯逻辑，照抄 MarkdownFormatter 形态）——输入 `SqlHistoryEntry`（sql + executionTimeMs + explainPlans[]）＋可选 `ExplainPlanDiff`；输出用户六段式模板（现象/基线/根因/手段/前后对比/结论与回滚），无计划时 fail-closed 出「无计划快照」段落不臆造。
- **S2 出口**：历史面板右键「导出优化留痕 Markdown」＋预览面板「更多」菜单项；写盘走 SqlExportService 后台线程链。
- **S3 搭车件：TEXT 档计划落历史**：`ExplainPlanKey.persistable`（ExplainPlanSnapshot.kt:66-69）扩 TEXT 档为原文文本快照（payload 增 raw text 形态，沿用 truncated 预算）；向后兼容读回（旧文件零迁移）。这是 DM/Oracle/OceanBase/H2/SQLite 能参与「前后对比导出」的前置。
- **S4 测试与反向**：组装器纯逻辑用例（有/无计划/TEXT 档/双快照 diff）；快照扩展旧 payload 读回兼容；反向＝摘 S3 档位分支 → TEXT 档用例复红。

### 第二波（拍板点 W2-1 后立项独立册）

- **T-TemplateHook（第 3 项 MVP）**：PluginSettings 增「外部渲染命令」配置（命令模板含 `{template}`/`{sqlId}`/`{paramsJson}` 占位符、超时、工作目录）；renderAtOffset 两挂点分流（§2.3）；`Outcome` 增 `external` sourceType；进程执行纪律（UTF-8 stdout、超时杀进程、stderr 只进诊断不进正文、命令注入＝文档明示用户自担）；预览/gutter 面接线列为同册可选券（XmlSqlGutterLineMarkerProvider 的 STATEMENT_TAGS 同源问题顺带评估）。

### 第三波（拍板点 W3-1 后立项独立册）

- **T-BannedApiCheck（第 2 项）**：规则模型（类 FQN 或正则＋方法名正则＋说明＋级别 error/warning）照抄 SqlSuppressRuleManager 存储（独立 storage＋DISABLED roaming）；管理 UI 照抄 SqlSuppressManagerDialog（列表＋编辑器形态，先例优先于自创表格）；`LocalInspectionTool` 首例（plugin.xml 注册＋:412 红线注释修订）；resolve 方法归属类（`PsiMethod.resolve()` + containingClass FQN，WrapperLocator 经验）；存量统计动作（DumbService＋后台只读扫描，报告落工具窗或文件）。风险＝inspection 是插件首例，EDT/ReadAction/SlowOperations 纪律按平台踩坑清单走。

### 已交付项的用户回复（外发动作，须用户令后执行）

回复 FR-0045 的要点（不发，先给用户过目）：

1. **第 1 项已支持两个月**（v2026.9 起随 M47）：用法＝打开 SQL Visualizer 面板「捕捉」后跑应用（控制台链），或把日志文本直接喂 `sqlv_render_logs`；参数行需 TRACE 级已满足；线程配对/缺参数留 `?` 正是他们建议的形态。
2. **第 5 项随 v2026.10.31-beta 已补齐他们场景的两块短板**（栈快照修复＋达梦等未知驱动 attach 兜底），请升级并开增强捕获复验；欢迎回报 DM attach 效果。
3. 第 2、3、4、6 项已进入裁决与排期（本册），按波次推进。
4. `sqlv_history_search` 澄清：搜的是插件捕获历史，不依赖 mapper 模板。

---

## 四、待验证前提（实施前先证伪，不成立就改方案）

- **P-1 DM8 裸 JDBC EXPLAIN 结果集形态**：决定 4a 修法走 channelNote 还是 postPlanQuery。载体＝DBX 在册 DM8 实例 `114.66.55.245:20096`（DM8，端口 5236，凭据见仓根 `.local/test-databases.md`，**不抄进文档/代码**）；备选＝`database-lab/load-dm8.ps1` docker 脚手架。若 MCP 通道读数与 DBX 通道（#NSET2 有正文）矛盾，以 MCP 通道为准（那是用户报障链路）。
- **P-2 internal 可见性**：`DataSourceDialectClassification.isNonSqlUrl` 与 `McpDialectDetector` 同在 src/main 一个 source set，Kotlin internal 模块内可见——编译期即证；若实际跨模块（子项目拆分）则把 NON_SQL_KEYS/isNonSqlUrl 下沉到 `sql/DialectInference.kt` 共享位。
- **P-3 用户 v2026.10.28 的 render_logs 场景**：该版本已含 JdbcTemplateLogParser，用户「主打 MyBatis 配对」的表述与代码不符——按「0 条/不对号」分诊口诀（EP 过滤器→桥→解析器开关 logJdbcEnabled→去重窗→屏蔽规则）待用户回报复现步骤；不排除用户控制台日志格式被自研日志框架二次包装（logback pattern 带前缀）——`extractAfterKeyword` 与前缀容忍性实施时用真实样本核。
- **P-4 第 2 项拍板未决前不动工**（含 plugin.xml:412 红线表述修订归属）。
- **P-5 ExplainPlanSnapshot TEXT 档扩展向后兼容**：旧 payload JSON（headers/rows 形态）读回不破；`SqlLogFileReader` 历史回读链同验。
- **P-6 threadName 落条目**（§2.1 搭车件）是否做：展示增益小、动 KDoc 语义（「仅 agent 通道」）——登记不派工，除非用户点名。

---

## 五、验收

- **4b**：redis URL 数据源调 `sqlv_explain` → `[NOT_A_SQL_DATASOURCE]` 结构化错误（MCP 测试客户端 `dev-support/mcp-test/mpsv-mcp-test.mjs` 或沙盒实测）；正常数据源 explain 不受扰；`DialectInferFallbackGuardTest` 族回归绿。
- **4a**：DM8 真机 `sqlv_explain`（或预览执行计划链）计划正文非空且为计划文本；`DialectExplainTest` 同步绿。
- **第 6 项**：历史条目一键导出 Markdown 六段模板在案；有计划快照条目含前后对比表；无计划条目出降级段落；TEXT 档计划可落历史可导出。
- **门禁**：各任务定向＋`testFast`；全量按三档分层由只读收口单跑；构建校验按判据（本波不动 build.gradle.kts/plugin.xml EP——wave 3 的 inspection 注册属大改动，届时跑 verifyGate）。
- **证据红线**：DM 相关结论只认真机读数；P-1 未落读数前不得把「DBX 通道 #NSET2 有正文」当作 MCP 通道已修复的证据。

## 六、风险

- 4a 修法被 P-1 读数推翻（DM EXPLAIN 走存储过程/多语句形态）→ 改 postPlanQuery 二段式，成本上浮一档。
- TEXT 档快照扩大历史文件体积 → 沿用 truncated 预算与行数硬顶，登记体积观察。
- wave 3 inspection 首例的平台纪律风险（EDT read access / SlowOperations / DumbService）→ 独立立项册单列平台前提。
- 需求池回复是外发动作：§三·三回复稿先给用户过目，未经令不发。
- 并行会话撞面：本册不改生产代码；第一波两任务若并行，工树分配与登记照「并行测试协调登记表」流程。

## 七、登记与联动

- 本册已登记 `docs/索引/开发计划.md` 表首（状态：裁决完成，第一波待用户令）。
- 联动在途账：DM attach 真机未验 → 并入 T-CaptureCoverage 未闭环项；「弹层标题带线程名已实现」勘误 → project-stack-digest-biz-frame 记忆；threadName 落条目 → 搭车件登记（P-6）。
- 4c 的 DM 函数矩阵用户读数已留证（本册 §2.4(c)），真机复验两条否定项随 P-1 顺带。

## 八、实施记录

（待回填——第一波动工后按券补读数与反向验证。）

### 8.3 T-TemplateHook 实施记录

工作树 `mpsv-fr45-tplhook` / 分支 `t-fr45-tplhook` / 基线 `4cd4ac35`（核心实现 WIP 留底 `370116a1`）。范围＝§2.3 的 MVP（外部渲染器 hook）+ §三·第二波的 T-TemplateHook 券；**不含**内置模板 SPI、预览/gutter 面接线。

#### 改动清单

新增：

- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/ExternalTemplateRenderer.kt:31` —— 纯逻辑外部渲染器：`tokenize`（:78，引号感知切分，未闭合引号/空模板明确失败）、`substitute`（:141，整串与子串都替，`paramsJson=null` 替空串）、`run`（:160，`ProcessBuilder` 列表形态、stdout/stderr 分线程 UTF-8、超时 `destroyForcibly`、stderr 首行截 500、输出上限 1 MiB 截断标注、`start` 注入缝）。安全红线写进类 KDoc（命令用户自担、日志/错误文本不含 `paramsJson`）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/ExternalTemplateSettings.kt:31` —— App 级 `@State` 服务，独立 storage `mp-sql-visualizer-external-template.xml` + `RoamingType.DISABLED`；字段 `enabled=false`/`statementTags=sql`/`commandTemplate`/`timeoutSeconds=10`/`workingDirectory`；`isActive()`（:75，开关 + 命令非空白）、`statementTagSet()`（逗号/中文逗号、去空白、大小写不敏感）、`effectiveTimeoutSeconds()`（:69，钳 1..120）、`workingDirectoryOrNull()`（:92，空/不存在→null 回落项目根）、`update()`（:103，设置页唯一写入口）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/ExternalTemplateConfigurable.kt:26` —— 普通 `Configurable`（不继承 `SectionConfigurable`）：开关 / 语句 tag / 命令多行域 / 超时 / 工作目录 + 占位符说明行；`apply()` 经 `update()` 落状态。
- `src/test/kotlin/cn/song/mybatisplus/visualizer/mcp/ExternalTemplateRendererTest.kt:24` —— 14 例：切分 4（空格/引号/转义/未闭合+空模板）、替换 2、假 Process 6（成功/非零退出+stderr 截断/超时/1MiB 截断/空输出/启动失败+空命令）、真实进程 1（`cmd /c echo` vs `/bin/echo`，`SystemInfo.isWindows` 分支 + `assumeTrue` 保护）。
- `src/test/kotlin/cn/song/mybatisplus/visualizer/config/ExternalTemplateSettingsTest.kt:16` —— 7 例：出厂默认关闭、`enabled=false` 时 `isActive()` 恒 false、空白命令不激活、tag 集解析、超时写时钳位、`loadState` 钳位与回读、工作目录回落。
- `src/test/kotlin/cn/song/mybatisplus/visualizer/convention/ExternalTemplateWiringSourceScanTest.kt:25` —— 接线守卫 4 例：挂点①早于 `STATEMENT_TAGS` 爬升、挂点前 12 行有 `isActive()` 门、`enabled` 出厂 false、合成样本自证（挂点后置/无门两形态必判红）。

修改：

- `mcp/McpSourceLocator.kt` —— `renderAtLine`（:73）增可选 `paramsJson`；`RenderStep`（:117）把「PSI 定位」与「跑外部进程」拆开（外部命令在读锁**外**执行，`runExternalRender` :146）；`renderAtOffset`（:225）XML 分支增挂点①（:250-262，位于 `STATEMENT_TAGS` 爬升之前；`enabled` 关闭时整块不执行）与挂点②（:268-274，链尾兜底向上找带 `id` 的 tag，仍失败才用原文案一字不改）；新增 `externalStep`（:378）与 `nearestAncestorWithId`（:401）。
- `mcp/McpToolErrors.kt:42` —— 新增 `EXTERNAL_RENDER_FAILED`（只加这一码，未动 `NOT_A_SQL_DATASOURCE` 所在行的语义）。
- `mcp/McpRenderInput.kt` —— `Parsed.paramsJson`（:31）；`parse(...)`（:46）第 5 个可选参数，只对路径形态生效，空白视同未传（:56）。
- `mcp/SqlVisualizerRenderSqlTool.kt` —— 工具签名增 `paramsJson: String = ""`（:42），工具描述补一句用法（:27）；透传至 `renderAtLine`（:56）。不传时与接线前逐字节一致。
- `src/main/resources/META-INF/plugin.xml:213-219` —— `applicationConfigurable` 注册，id `cn.song.mybatisplus.visualizer.settings.externalTemplate`，**`groupWeight="11"`**（15 > 11 > 10，声明序严格递减）。注意：基线**没有** `bannedApi=12` 页（另分支产物未进本分支），11 仍落在 15 与 10 之间，与另一分支合并后自然成 15>12>11>10。
- `config/PluginSettingsFolderConfigurable.kt` —— `ID_EXTERNAL_TEMPLATE`（:508）、`PAGE_EXTERNAL_TEMPLATE`（:538-541）、`SUB_PAGES`（:546-549，插在 mcp 与 about 之间）、搜索索引 5 项（:642-648）。
- `config/PluginXmlStructureGuardTest.kt` / `config/PluginSettingsFolderConfigurableTest.kt` —— 子页基数 7→8、登记目标 8→9（本分支自洽同步）。
- `convention/GuardSelfTestCoverageMetaGuardTest.kt` —— 新增 `ExternalTemplateWiringSourceScanTest` 登记项（`decisionFns` 留空 + 灵敏度说明）。
- 双语 bundle —— `MpSqlVisualizerBundle.properties:968-981` / `MpSqlVisualizerBundle_zh.properties:944-957`，13 键（`settings.section.externalTemplate` + `.desc` + 11 个 `settings.externalTemplate.*`），中英键集全等。

新增设置键 / i18n 键 / 错误码：

- 设置服务与 storage：`MpSqlVisualizerExternalTemplate` / `mp-sql-visualizer-external-template.xml`；状态字段 `enabled`、`statementTags`、`commandTemplate`、`timeoutSeconds`、`workingDirectory`（均不进 `PluginSettings`）。
- i18n 键 13 个（见上）。
- MCP 错误码 1 个：`EXTERNAL_RENDER_FAILED`。

#### 定向读数（本轮实测）

命令：`./gradlew test --tests ... -Pkotlin.compiler.execution.strategy=in-process`（跑前 `parallel_tasks.py check` 有活跃任务，按仓规在 `build.gradle.kts` 临时加 `sandboxContainer=MPSV-Fr45TplHook`，用后还原）。

| 类 | tests | failures/errors | skipped |
| --- | --- | --- | --- |
| mcp.ExternalTemplateRendererTest | 14 | 0 | 0 |
| config.ExternalTemplateSettingsTest | 7 | 0 | 0 |
| config.PluginSettingsFolderConfigurableTest | 11 | 0 | 0 |
| config.PluginXmlStructureGuardTest | 22 | 0 | 0 |
| convention.ExternalTemplateWiringSourceScanTest | 4 | 0 | 0 |
| convention.GuardSelfTestCoverageMetaGuardTest | 8 | 0 | 0 |
| i18n.BundleParityGuardTest | 5 | 0 | 0 |
| config.SettingsRealityGuardTest | 4 | 0 | 0 |
| config.SettingsIdeSearchContributorTest | 8 | 0 | 0 |
| **合计** | **83（9 类）** | **0** | **0** |

真实进程用例在 Windows 上真跑未跳过（skipped=0，走 `cmd /c echo` 分支）。

`testFast`：`BUILD SUCCESSFUL in 4m 7s`，`classes=361 tests=4331 failed=0 skipped=7`（跳过项为仓内既有的环境门控用例，与本券无关）。

#### 反向验证（两次读数）

1. 摘接线：把 `renderAtOffset` XML 分支的挂点①整块注释掉（只留挂点②）→ 跑 `ExternalTemplateWiringSourceScanTest`：
   - `tests=4 failures=1 errors=0 skipped=0`，红例＝`外部渲染挂点必须早于STATEMENT_TAGS爬升`，断言原文「当前 externalStep@11904 vs STATEMENT_TAGS@11415」——挂点只剩爬升之后的那一处，正是 FR-0045 报障形态。
2. 还原接线 → 复跑同一类：`tests=4 failures=0 errors=0 skipped=0`，BUILD SUCCESSFUL。

#### 未闭环项（如实登记）

- 未跑 `runIde`：设置页 UI（`ExternalTemplateConfigurable` 组件布局、apply/reset 交互）**无真机走查**，只有源码 + 平台 Configurable 契约层保证；headless 下未单独断言该页控件。
- 未跑全量 `testShards` / `verifyGate` / `verifyPlugin` / `buildPlugin`：按任务约束只跑定向 + 一次 `testFast`。`plugin.xml` 新增 EP 属「大改动判据」里的 EP 面变更，**发布前须补一轮 `verifyGate`**。
- 预览/gutter 面未接线：`XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS`（硬编码 4 个 mapper tag + 父级须为 `<mapper>`）保持原样，自研 `<sqls>/<sql id>` 在本轮仍不出 gutter 图标，只有 MCP `sqlv_render_sql` 通道可用——与券范围一致，登记为可选搭车件。
- `<sqls>` 根下**无 `id`** 的 tag：挂点②找不到带 `id` 的祖先，仍回落原 `SOURCE_NOT_FOUND` 文案（设计如此：无 id 无法传给外部命令）。
- 外部命令的真机联调未做（用户侧需自备渲染脚本）；工作目录回落、超时、超限三条分支由假 Process 覆盖，真实脚本的编码/退出码行为待用户回报。
- `ExternalTemplateConfigurable` 的 `isModified()` 在无服务环境（headless 无 PlatformImpl）恒 false——与 `McpConfigurable` 同款降级，真机不受影响。

