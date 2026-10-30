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

### 8.4 T-BannedApiCheck 实施记录

- **工作树/分支/基点**：`F:/code/Java/Gitee/SongRenShuo/mpsv-fr45-bannedapi`，分支 `t-fr45-bannedapi`，基点 `4cd4ac35`。
- **提交**：`57d85d9e`（S1 规则模型与管理器）+ `8da5f032`（S2–S4 检查器 / 设置子页 / 存量统计）+ 本记录所在提交。未 push、未 merge、未 rebase。
- **P-4 前提状态如实说明**：本册 §四 P-4 写「第 2 项拍板未决前不动工」。本轮由主控直接派工实施，按用户令视为已拍板；P-4 本身（含 `plugin.xml:412` 红线表述修订归属）在实施中已按 §2.2 裁决要求处理，处置见下。

#### 改动清单

生产代码（新增 8 个文件）

| 文件 | 关键锚点 |
|---|---|
| `src/main/kotlin/cn/song/mybatisplus/visualizer/inspection/BannedApiRule.kt` | :22 规则 data class（类匹配串 / `classRegex` 显式模式标记 / 方法名正则 / 说明 / 级别 / createdAt）；:44 `targetSummary`；:52 `isError` |
| `…/inspection/BannedApiMatcher.kt` | :22 纯逻辑匹配核心；:47 `validate`；:65 `compile` 预编译；:92 `CompiledRules`；:103 `Entry.matches`（类精确或正则整串 + 方法名正则整串，首条命中即返回，非法正则 `compileOrNull` 得 null → 规则静默失效）；:115 `mayMatchMethodName` 廉价短路 |
| `…/inspection/BannedApiRuleManager.kt` | :35–38 `@State(name="MpSqlVisualizerBannedApiRules", storages=[Storage("mp-sql-visualizer-banned-api.xml", DISABLED)])` + `@Service(Service.Level.APP)`；:78 `addRule`（判重只看类+模式+方法名）；:102 `updateRule`（保留 id/createdAt）；:125 `removeRule`；:132 `clearRules`；:156 `republish`（整体替换 `@Volatile compiledRules` + 通知）；:165 `loadState`（补 id、归一未知级别）；:216 `MAX_RULES = 200` |
| `…/inspection/BannedApiInspection.kt` | :34 `LocalInspectionTool` 薄壳；:46 `getShortName()` 与 plugin.xml `shortName` 同值；:48 `buildVisitor`（空规则 → `EMPTY_VISITOR`）；:50 零行为短路；:65 方法名短路；:68 `resolveMethod()`；:69 `containingClass.qualifiedName`；:72–84 显式 `PsiElement` 锚点 + `ProblemHighlightType.ERROR/WARNING` 逐规则级别 |
| `…/inspection/BannedApiProjectScanner.kt` | :22 `BannedApiScanResult`；:47 扫描器 object；:55 `scan`；:56 `FileTypeIndex.getFiles(JavaFileType.INSTANCE, projectScope)`；:104 明细上限；:76 取消检查 / :79 解析失败跳过（降级不崩） |
| `…/inspection/BannedApiScanReport.kt` | :15 `BannedApiScanHit`；:33 报告组装；:36 `MAX_DETAIL_ENTRIES = 500`；:54 截断；:73 `groupedCounts` 按规则聚合 |
| `…/config/BannedApiConfigurable.kt` | :52 Configurable（左 `JBList` + 右 `FormBuilder` 表单，照 `SqlSuppressManagerDialog` 形态）；:57 无参构造供 EP `instance=`；`isModified/apply` 空实现（关闭即生效，单源=manager） |
| `…/actions/ScanBannedApiUsagesAction.kt` | :42 AnAction；:51 BGT update；:54 `DumbService.isDumb` 不跑；:67 `runReadAction` 包住扫描；:72/:98 `catch (Throwable)` 先 `FatalErrors.isFatal` 透传；:110 写临时报告 / :131 打不开则对话框降级 |

注册与文案（改动 4 个文件）

| 文件 | 锚点 |
|---|---|
| `src/main/resources/META-INF/plugin.xml` | :215–220 `applicationConfigurable`（`id=…settings.bannedApi`，`groupWeight=12`，插在 mcp(15) 与 about(10) 之间）；:421–427 XML 引用红线注释修订（见下）；:457–477 `localInspection`（`language="JAVA"`、`shortName="BannedApi"`、`key`+`bundle`、`groupKey`+`groupBundle`、`enabledByDefault="true"`、`level="WARNING"`）；:576–580 `action` 挂 `ToolsMenu` |
| `…/config/PluginSettingsFolderConfigurable.kt` | :509 `ID_BANNED_API`；:538–542 `PAGE_BANNED_API`（图标 `AllIcons.General.InspectionsWarning`）；:549–552 进 `SUB_PAGES` |
| `messages/MpSqlVisualizerBundle.properties` | :1516 起 57 条英文键（与基点 `4cd4ac35` 逐键比对新增 57、删除 0） |
| `messages/MpSqlVisualizerBundle_zh.properties` | :1520 起同 57 条中文键（中英键集全等，`BundleParityGuardTest` 5 例绿） |

测试（新增 6 个类 / 改动 2 个类的基数）

- 新增：`inspection/BannedApiMatcherTest.kt`（9 例，纯 JUnit）、`inspection/BannedApiRuleManagerTest.kt`（11 例，纯 JUnit）、`inspection/BannedApiScanReportTest.kt`（4 例，纯 JUnit）、`inspection/BannedApiInspectionTest.kt`（5 例，`BasePlatformTestCase` + `enableInspections`）、`inspection/BannedApiProjectScannerTest.kt`（2 例，平台桶）、`inspection/BannedApiRuleSerializationTest.kt`（3 例，XmlSerializer round-trip + `localInspection` EP 解析）。
- 改动：`config/PluginSettingsFolderConfigurableTest.kt`（子页 7→8、登记目标 8→9）、`config/PluginXmlStructureGuardTest.kt`（子设置页注册数 7→8）。
- 新增：`config/BannedApiConfigurableTest.kt`（1 例 headless 冒烟：页面可构建 + 列表跟随管理器）。

#### 定向测试读数

`./gradlew test --tests "cn.song.mybatisplus.visualizer.inspection.*" --tests "…config.BannedApiConfigurableTest" --tests "…config.PluginSettingsFolderConfigurableTest" --tests "…config.PluginXmlStructureGuardTest" -Pkotlin.compiler.execution.strategy=in-process`

- 读法：`BUILD SUCCESSFUL`，聚合 `build/test-results/test/*.xml` 为 **9 个类 / 68 例 / 0 失败 / 0 错误 / 0 跳过**，逐类：BannedApiMatcherTest 9、BannedApiRuleManagerTest 11、BannedApiScanReportTest 4、BannedApiInspectionTest 5、BannedApiProjectScannerTest 2、BannedApiRuleSerializationTest 3、BannedApiConfigurableTest 1、PluginSettingsFolderConfigurableTest 11、PluginXmlStructureGuardTest 22。
- 触链回归 `./gradlew testFast`：**4344 例 / 0 失败 / 0 错误 / 7 跳过**（跑在 fatal 透传修复之后、两笔提交之后）；末次调用 Gradle 报 UP-TO-DATE 未重跑，故以此轮实跑读数为准。
- 过程中修正过两处「测试自身预期」而非实现：① 正则用例误把规则库存的 `className` 当实际类名断言（期望串写错）；② 扫描器用例原用 `addFileToProject` 建源，方法调用 resolve 落空导致 0 命中（夹具问题），改 `configureByText(JavaFileType)` 单文件多类后通过。
- 另一处实现缺陷（被 testFast 抓到，已修）：`ScanBannedApiUsagesAction` 两处 `catch (Throwable)` 未判 fatal → `convention.BannedApiScanRuleTest#catch_Throwable块必须判fatal或登记豁免` 红；按仓内 `util/FatalErrors.isFatal` 形态补齐后转绿（该文件 12 例复绿）。

#### 反向验证（三次，均还原复绿）

| # | 摘除点 | 期望 | 实测读数 | 还原后 |
|---|---|---|---|---|
| R1 | `BannedApiMatcher.kt:103` `Entry.matches` 末尾的方法名分支改成 `return true` | 方法名相关用例红 | `BannedApiMatcherTest` **9 例 / 2 失败**，失败点名 `方法名正则_整串匹配`、`多条规则_首条命中即返回_按规则表顺序` | 还原 → 9 例 0 失败 |
| R2 | `BannedApiInspection.kt` `checkCall` 里摘掉 `holder.registerProblem(...)`（在 report 前提前 return） | inspection 集成用例红 | `BannedApiInspectionTest` **5 例 / 4 失败**（唯「无规则零行为」仍绿，符合语义） | 还原 → 5 例 0 失败 |
| R3 | `plugin.xml` 把整段 `<localInspection>` 注释掉（守卫剥注释后视为未注册） | 注册守卫红 | `BannedApiRuleSerializationTest` **3 例 / 1 失败**，点名 `test注册_EP存在且属性解析正确` | 还原 → 3 例 0 失败 |

还原后复核：`grep -rn "RED PROBE\|RED-PROBE" src/` 零命中、`git diff` 为空、定向套件复跑 **68 例 / 0 失败**。

#### i18n 键（新增 57 键，中英两份同集）

`settings.section.bannedApi`、`settings.section.bannedApi.desc`、`settings.bannedApi.intro`、`settings.bannedApi.empty`、`settings.bannedApi.add`、`.delete`、`.clear`、`.clear.title`、`.clear.confirm`、`.save`、`.addConfirm`、`.cancelAdd`、`settings.bannedApi.field.className`(+`.tip`)、`settings.bannedApi.field.mode`、`settings.bannedApi.mode.exact`、`settings.bannedApi.mode.regex`、`settings.bannedApi.field.methodName`(+`.tip`)、`settings.bannedApi.field.level`、`settings.bannedApi.level.error`、`settings.bannedApi.level.warning`、`settings.bannedApi.field.description`(+`.tip`)、`settings.bannedApi.status.addHint`/`.added`/`.updated`/`.deleted`/`.cleared`/`.duplicate`/`.limit`/`.notFound`/`.invalid`/`.classBlank`/`.descriptionBlank`/`.classRegex`/`.methodRegex`、`inspection.bannedApi.displayName`、`inspection.bannedApi.groupName`、`inspection.bannedApi.problem`、`action.cn.song.mybatisplus.visualizer.ScanBannedApiUsages.text`/`.description`、`scan.bannedApi.title`/`.noRules`/`.dumb`/`.failed`/`.noHits`、`scan.bannedApi.report.title`/`.project`/`.scanned`/`.total`/`.byRule`/`.details`/`.ruleEntry`/`.hitEntry`/`.truncated`/`.canceled`。

#### 存储与注册键名

- 持久化文件：`mp-sql-visualizer-banned-api.xml`（`RoamingType.DISABLED`，App 级）。
- `@State` name：`MpSqlVisualizerBannedApiRules`；State 结构 `State.rules: MutableList<BannedApiRule>`。
- 设置页 EP id：`cn.song.mybatisplus.visualizer.settings.bannedApi`；检查短名：`BannedApi`（`@SuppressWarnings("BannedApi")` 可抑制单点）。
- 动作 id：`cn.song.mybatisplus.visualizer.ScanBannedApiUsages`（`ToolsMenu`）。

#### plugin.xml:412 红线注释的处置

原文（`T-XmlGap W7` 段）：

> `<!-- T-XmlGap W7：mapper XML 引用（跳转/查找用法）——只产 soft 引用，不注册任何 inspection/annotator（红线：不做校验器形态）；无目标时零报红 -->`

改为：

> `<!-- T-XmlGap W7：mapper XML 引用（跳转/查找用法）——只产 soft 引用，**XML 侧**不注册任何 inspection/annotator（红线：mapper XML 不做校验器形态）；无目标时零报红。T-BannedApiCheck（FR-0045 第 2 项）勘误：原注释「不注册任何 inspection/annotator」是这条 XML 引用链的约束（避免 XML 引用被做成报红校验器）；插件在 Java 侧新增的禁用 API 检查是独立能力面（见下方 localInspection），与本贡献器「只产 soft 引用」互不相干，本红线对 XML 引用仍成立。 -->`

改动理由：原句是泛化表述，字面上与新增 Java 侧 `localInspection` 冲突。修订**只收窄作用域到 XML 引用链**（原意保留：mapper XML 仍不做校验器形态、仍只产 soft 引用），并在注释里点明 Java inspection 是独立新增面、为何不算破线。没有删除或弱化这条红线，也没有改动该 `psi.referenceContributor` 注册本身。

#### 未闭环项（如实登记）

- **未跑**：`runIde` 真机走查（设置页视觉、编辑器标红/黄实际观感、Tools 菜单动作手动执行、`@SuppressWarnings("BannedApi")` 真机抑制）／`testShards` 全量／`verifyGate`、`verifyPlugin`、`buildPlugin`。本轮只跑定向套件与一次 `testFast`。
- **`verifyGate` 建议补跑**：本轮新增 `plugin.xml` 的 `localInspection` EP 与一个新 action，按 §五「wave 3 的 inspection 注册属大改动」判据，发版前应跑 `verifyGate`（本轮按派工纪律未跑）。
- **`localInspection` 分组显示名的语言**：平台解析 EP 文案走 IDE 语言（`DynamicBundle`），不跟插件语言设置；测试 JVM 下解析为英文 "Banned API usage"，故用例按中英任一断言。真机中文 IDE 应显示中文——未真机确认。
- **`BannedApiConfigurable` 只有 headless 冒烟**：表单布局、分栏比例、状态行文案在真实设置对话框里的观感未走查。
- **存量统计动作未手动执行**：扫描器与报告组装有平台/纯逻辑用例覆盖，但「写临时文件 + `FileEditorManager.openFile` + 失败降级对话框」这条 UI 链只有代码审读，无真机读数。
- **匹配语义边界**：只按方法声明类的 `qualifiedName` 判定，不做父类/接口/子类回溯（子类未覆写时调用点解析到的就是父类方法，规则写父类可命中；覆写后需规则写子类）。此语义已写入 `BannedApiMatcher` KDoc，未加「按继承链匹配」开关（不在本次需求内）。
- **Kotlin 调用点不覆盖**：inspection 注册 `language="JAVA"`，`PsiMethodCallExpression` 亦适用 KtLight 但未注册 Kotlin 语言（Kotlin 文件里对 Java 禁用 API 的调用不会被本检查捕获）。需求原文指向编辑期通用检查，Kotlin 面登记为增量，未实施。
