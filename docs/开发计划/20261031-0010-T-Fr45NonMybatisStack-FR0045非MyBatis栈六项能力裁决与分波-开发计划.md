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
- 用户实测矩阵（LIMIT/LIMIT 0,1/ROWNUM/OFFSET..FETCH FIRST/IFNULL/DATE_FORMAT/NOW()/`||`/WM_CONCAT/LISTAGG 支持；GROUP_CONCAT 报 -2207、反引号报 -2007 不支持）**已由我方真机逐条复验（2026-10-31，P-1）且逐字吻合**，资产可入册；但「内置成规则表」仍需拍板落点（证据化代码表＋执行前提示 vs 文档站方言页）。**挂拍板，不进第一波主修；证据已备，拍板后可即落**。

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

- **P-1 DM8 裸 JDBC EXPLAIN 结果集形态**：**已实测（2026-10-31 主控亲测；DBX 在册 DM8 `114.66.55.245:20096` + 仓内驱动 `DmJdbcDriver18-8.1.3.140`，服务端 DM DBMS 8.1.3.12）**，读数**推翻计划原假设**：
  - **`EXPLAIN <sql>` 在 DM8 上不返回结果集**——`executeQuery` 得 0 列 0 行；`execute()` 得 `isResultSet=false, updateCount=0`，再 `getMoreResults()` 即 `-1`（无第二结果集）。⇒「计划正文为空」的真因是**模板语句用错**，与多结果集/宽容通道无关，加 `channelNote` 也救不回来。
  - **`EXPLAIN FOR <sql>` 正确返回 19 列结构化计划表**：`PLAN_ID, PLAN_NAME, CREATE_TIME, LEVEL_ID, OPERATION, TAB_NAME, IDX_NAME, SCAN_TYPE, SCAN_RANGE, ROW_NUMS, BYTES, COST, CPU_COST, IO_COST, FILTER, JOIN_COND, ADVICE_INFO, PSTART, PSTOP`；`SELECT` 与 `DELETE ... WHERE 1=0` 均 4 行（OPERATION=DELETE、TAB_NAME=ARTICLE、COST=0）。⇒ 修法＝DM 条目改 `EXPLAIN FOR {sql}` 且**档位 TEXT→TABLE**（TABLE 档顺带解锁历史持久化：`ExplainPlanSnapshot.persistable` 认 TABLE/JSON；`ExplainPlanDiff.diffTable` 按小写列名取 `cost` 命中 DM 的 `COST`，`ROW_NUMS` 不命中但优雅降级）。
  - **4c 矩阵真机读数顺带取到**：正向全部成立——`LISTAGG`/`WM_CONCAT`/`IFNULL`/`DATE_FORMAT`/`NOW()`/`LIMIT`/`LIMIT 0,1`/`ROWNUM`/`||`；负向与用户实测逐字一致——`GROUP_CONCAT` → **-2207**「无法解析的成员访问表达式」、反引号 → **-2007**「语法分析出错」；表不存在 → -2106。4c 落点拍板未定，证据已备。
  - 备选载体（未用）：`database-lab/load-dm8.ps1` docker 脚手架。
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

### 8.1 T-Fr45DmGuard 实施记录

- 分支 `t-fr45-dmguard`（基线 `4cd4ac35`），2026-10-31。范围＝本册 §三·任务 A 的 S1/S2；4c 函数矩阵未动，其它 MCP 工具的非 SQL 守卫未扩面，其它方言条目未动。
- **P-1 已落地（主控真机读数，本任务直接采信）**：DM8 实例 `114.66.55.245:20096` + 仓内 `DmJdbcDriver18-8.1.3.140`（服务端 DM DBMS 8.1.3.12）。`EXPLAIN <sql>` 走裸 JDBC **不返回结果集**（`executeQuery` 0 列 0 行；`execute()` 得 `isResultSet=false, updateCount=0`，`getMoreResults()` 随即 -1）；`EXPLAIN FOR <sql>` 返回 19 列结构化计划表（`PLAN_ID, PLAN_NAME, CREATE_TIME, LEVEL_ID, OPERATION, TAB_NAME, IDX_NAME, SCAN_TYPE, SCAN_RANGE, ROW_NUMS, BYTES, COST, CPU_COST, IO_COST, FILTER, JOIN_COND, ADVICE_INFO, PSTART, PSTOP`），`SELECT …` 与 `DELETE … WHERE 1=0` 均得 4 行。负向对照：`GROUP_CONCAT` → -2207、反引号标识符 → -2007、表不存在 → -2106。
  - 结论：用户报障「计划正文为空、只剩 `dialect: DM（达梦）`」的真因是**模板语句用错**（旧模板 `EXPLAIN {sql}` 返回 0 列 0 行 → `McpExplainFormatter.format` 输出空串），不是多结果集/宽容通道问题——不需要 `channelNote`，也不需要 `postPlanQuery`。旧证据「`EXPLAIN` 返回 `#NSET2` 计划文本」来自 DBX 通道，对 MCP 裸 JDBC 链不成立，已在 `ExplainSupport` 证据字段改写。
- **P-2 已证实（按「成立」落地）**：`DataSourceDialectClassification` 与 `mcp` 包同在 `src/main` 一个 source set，`isNonSqlUrl` 由 `private` 改 `internal` 后编译即通——不需要下沉到 `sql/DialectInference.kt`。

**S1（4b）非 SQL 数据源早拒**

- 改动：
  - `mcp/McpToolErrors.kt:28-29` 新增 `NOT_A_SQL_DATASOURCE`（MCP 错误文案为英文常量，**不进 bundle**，无新增 i18n 键）。
  - `mcp/ExplainTargetGuard.kt`（新增）：`checkDataSourceUrl(url): String?` 纯逻辑，命中返回可直接抛出的 `[NOT_A_SQL_DATASOURCE] …` 文本；语料来自 `sql/DataSourceDialectClassification.isNonSqlUrl`（键表不复制）。文案只带子协议（`subprotocolOf`），不回显 URL 其余部分。
  - `sql/DataSourceUrlProbe.kt:74-79`：`isNonSqlUrl` `private` → `internal`。
  - `mcp/SqlVisualizerExplainTool.kt:129-135`：取 `url` 之后、`McpDialectDetector.resolve` 之前调用守卫。
- 测试 `mcp/McpExplainTargetGuardTest`：redis/mongodb 命中；大小写（`JDBC:REDIS:`）与前后空格命中；mysql/dm/postgresql/mariadb/oracle/sqlserver/kingbase8/opengauss 放行；`jdbc:clickhouse:`、null、空串、纯空白、非 jdbc 前缀放行（不猜）；账号/口令/主机不泄漏；源码扫描断言接线顺序（取 `getConnectionConfig` 之后、`McpDialectDetector.resolve` 之前）。

**S2（4a）DM 改 `EXPLAIN FOR` + 档位 TEXT→TABLE**

- 改动：`sql/ExplainSupport.kt:138-146` DM 条目（模板 `EXPLAIN FOR {sql}`、`TABLE`、evidence 换本轮真机读数）；同步 `ExplainOutputFormat`/DM 证据 KDoc、`util/ExplainIndexAdvisor.kt` 与 `ui/ExplainPlanDiff.kt` 的档位注释。
- 测试：`sql/DialectExplainTest` 的 DM 用例改「模板 + 档位」双断言、幂等用例改 `EXPLAIN FOR`；新增 `mcp/DmExplainPlanFixtureTest` 用 P-1 的真实 19 列做夹具，断言 `McpExplainFormatter.format` 输出非空、含 `OPERATION`/`TAB_NAME`/`ARTICLE`/`DELETE`，另加「空结果集 → 空正文」用例复现用户报障形态。
- **下游语义核查（TABLE 档，均只降级不崩，无需改码）**：
  - `execute/ExplainPanelChannel.kt:29` `STRUCTURED_FORMATS = {TABLE, JSON}` → DM 从此走结构化面板通道（此前 TEXT 档走直送控制台）。
  - `history/ExplainPlanSnapshot.kt:66-69` `persistable` 认 TABLE/JSON → DM 计划可落历史（4a 的附加收益；用例 `TABLE 档让 DM 计划可落历史` 钉住）。
  - `ui/ExplainPlanDiff.kt:127-181` `diffTable` 按小写列名取值：`COST` 命中 `cost` → 产出数值增量；DM 无 `id`/`table` 列 → 匹配键退化为行序 `row#n`；`ROW_NUMS` 不在 `NUMERIC_TABLE_COLUMNS` → 走字段级变化（用例断言 `rowsDelta == null` 且 `costDelta` 非空）。
  - `util/AiContextPlanSummary.kt:25-34` TABLE 分支：`COST` 命中、`rows`/`filtered` 缺省不追加（输出仍有 `cost=`，不返回 null）。
  - `util/ExplainIndexAdvisor.kt:51-53` TABLE → `adviseMysql`，DM 无 `type/key/Extra` 列 → 提前返回空清单（不噪音、不抛）。
  - `ui/ExplainPlanResultView.kt` TABLE 分支是通用 `JBTable` 渲染任意列头，无 MySQL 列名硬编码。

**定向读数与反向验证**

| 阶段 | 读数 |
|---|---|
| 先红（测试先写、生产未落） | `compileTestKotlin FAILED`，`Unresolved reference 'ExplainTargetGuard'`（×9）与 `'NOT_A_SQL_DATASOURCE'` |
| 中间红（S1 已落、S2 未落） | `40 tests completed, 3 failed`：DM 模板/档位 ComparisonFailure、DM persistable=false、`DialectExplainTest` ComparisonFailure |
| 全绿 | `BUILD SUCCESSFUL`（40 用例） |
| **反向验证-红** | 注释掉守卫调用 + DM 条目改回 `EXPLAIN {sql}`/TEXT → `40 tests completed, 4 failed`（接线守卫、DM 模板档位、TABLE 落历史、`DialectExplainTest`） |
| **反向验证-复绿** | `git checkout --` 两文件后同三组 → `BUILD SUCCESSFUL` |

- `testFast`（本轮唯一一次）：`4334 tests completed, 2 failed, 7 skipped`——两处都是本轮改动的**连带面**：①`convention/GuardSelfTestCoverageMetaGuardTest`（`:400`）新守卫类未登记（已补）；②`util/ExplainIndexAdvisorTest`（`:217`）钉住「DM 路由 TEXT」的过期断言（已改 TABLE + DM 真机列集零命中）。修复后五类定向复跑 `BUILD SUCCESSFUL`；全量交只读收口代理。
- 新增错误码：`NOT_A_SQL_DATASOURCE`。新增 i18n 键：无。
- **未闭环项**：DM 真机端到端（`sqlv_explain` 经 IDE remote JDBC 通道 + 预览面板结构化通道）需 runIde 沙盒，本轮未跑；本轮 DM 证据是裸 JDBC 读数 + 19 列夹具，不等于 UI/IDE 链已验。`ROW_NUMS` 等 DM 语义列名未纳入 `ExplainPlanDiff` 数值白名单（只降级），补挂挂后续裁量。`docs/mcp/MCP-Tools-Agent-Guide.md` 错误码清单与文档站 DM 段由主控统一更新。

### 8.2 T-TraceDocExport 实施记录

- 工作树：`mpsv-fr45-tracedoc` / 分支 `t-fr45-tracedoc`（基线 `4cd4ac35`）
- 提交：`2ac30bba`（S1 组装器）、`b08be19d`（S3 TEXT 档快照）、`da4d0d56`（S2 出口与 i18n）

**S1 留痕组装器（`share/format/TraceDocFormatter.kt`，纯逻辑）**

- 输入为独立 `TraceDocInput` / `TracePlanSnapshot`（不依赖历史域数据类与平台类型），输出固定六段：
  现象 / 基线 / 根因 / 手段 / 前后对比 / 结论与回滚。
- 事实字段自动填充（SQL 原文进 ```sql 围栏、方言、来源 `Xxx.java:42`、捕获时间、耗时 ms、
  0~2 份计划快照）；叙述字段（现象/基线/根因/手段/结论/回滚）缺失留「（待补充）」。
- **fail-closed**：无计划 → 基线段明写「无计划快照：…不对计划内容做任何推断」；计划行集为空 →
  「空计划快照」；缺耗时/来源/捕获时间 → 「未采集」；方言未知 → 「未知」；单份计划 →
  「没有可比基线」。
- 前后对比：TABLE/JSON 复用 `ui/ExplainPlanDiff.kt`（rows/cost 增量 + 字段级变化）；TEXT/XML
  或结构化解析失败退化为 `ui/diff/SqlDiffer.kt` 行级差异，标题明示「文本级…不推断代价」。
- 转义：围栏按内容最长连续反引号加长（SQL 自带 ``` 时不提前闭合）；表格单元格 `|`→`\|`、
  换行→`<br>`。
- 读数：`TraceDocFormatterTest` 11 用例 / 0 失败 / 0 跳过（有单份、双份结构化对比、TEXT 行对比、
  无计划降级、缺耗时、围栏/表格转义、截断、空计划、默认文件名、叙述回填）。

**S3 TEXT 档执行计划落历史（`history/ExplainPlanSnapshot.kt`）**

- `ExplainPlanKey.persistable` 扩员 TEXT（DM/Oracle/OceanBase/H2/SQLite 计划可持久化）；
  XML 维持不落盘（无消费方）。
- `ExplainPlanSnapshot.create`：TEXT 单列计划存原文文本 payload
  `{"text": "...", "headers": [...], "truncated": bool}`；多列或单元格含换行回退结构化 JSON
  （保行列边界）。`raw()` 双形态读回：旧 headers/rows payload 原样解析（`truncated` 缺省 false
  容错），文本 payload 还原单列 `RawResult`（缺 headers 用 `TEXT_HEADER = "PLAN"`）。
- 兼容（P-5）：旧 payload 读回零迁移；真实 `XmlSerializer` 往返用例同时钉 TEXT 与旧结构化
  两种快照。`SqlLogFileReader`/自动保存链不涉及计划字段（grep 零命中），无需改动。
- 读数：`ExplainPlanSnapshotTextTest` 10 用例 + `ExplainPlanHistoryTest` 11 用例（含新增 TEXT
  序列化往返）/ 0 失败 / 0 跳过。

**S2 导出出口**

- 历史页二级右键「导出优化留痕 Markdown」（`log/HistorySqlLogPanel.kt`，作用于右键行）：
  计划按「同项目 + SQL 文本精确相等」从预览历史取最近两份（跨项目不借用，R91 口径），
  匹配不到按无计划快照降级。
- 预览面板「更多」新增「导出优化留痕」（`ui/SqlPreviewPanelResultView.kt` +
  `ui/SqlPreviewPanelTraceDoc.kt`）：取数口径同 `copyAsAiContext` 的精确计划键，键不成立回落
  本预览历史条目。
- 写盘：`log/TraceDocExport.kt` 照抄 `HistorySqlLogPanel.exportEntries`
  （FileSaverDescriptor → pooled 线程 → `SqlExportService.exportToFile` → 通知）；默认名
  `trace-doc-<yyyyMMdd-HHmmss>.md`；显示脱敏开关开时只对 SQL 做投影级打码（同 SqlExportService）。
- i18n（中英各 7 键）：`panel.traceDoc`；`log.history.traceDoc` / `.tip`；
  `log.traceDoc.export` / `.descriptor` / `.done` / `.failed`。
- `SqlPreviewTopRowLayoutTest` 的「更多」菜单十项顺序断言同步更新。

**反向验证（2026-10-31）**

- 先红：仅落测试骨架（formatter 返回空串、TEXT 档位未扩、raw() 不认文本 payload）→
  `TraceDocFormatterTest + ExplainPlanSnapshotTextTest` 共 21 用例，**20 失败**；唯一通过的是
  「旧 headers/rows payload 读回不变」对照用例。
- 反向摘除：临时把 `persistable` 的 TEXT 档位删掉、把基线段「无计划快照」降级句换成空串 →
  同两类 **21 用例 / 6 失败**（S3 五例：persistable、文本 payload 落盘往返、多列回退、
  含换行单元格回退、保留上限；S1 一例：无计划降级）。
- 还原后复绿：三类 **32 用例 / 0 失败 / 0 跳过**（11 + 10 + 11）。
- 反向过程还暴露一处测试弱点：原「无计划」断言只查「无计划快照」四字，被第 5 段降级句满足，
  已收紧为匹配基线段完整句（反向验证由此才变红）。

**未闭环 / 登记**

- TEXT 档计划**暂无生产写入方**：面板 EXPLAIN 结构化通道只覆盖 TABLE/JSON
  （`execute/ExplainPanelChannel.STRUCTURED_FORMATS`），TEXT 方言仍走直送控制台。S3 只完成
  存储层（persistable + payload + 读回）；DM/Oracle 等要真正落历史还依赖 4a 的通道改造，
  登记随 T-Fr45DmGuard 收口时复评。
- 按任务约束未跑 runIde/构建校验：UI 接线以编译通过 + 纯逻辑定向用例为准。`testFast` 触链回归
  单跑读数：**4341 用例 / 0 失败 / 7 跳过**（跳过为既有 Assume，本轮未新增），其中
  `TraceDocFormatterTest` 11 例与 `ExplainPlanSnapshotTextTest` 10 例已进快桶；`ExplainPlanHistoryTest`
  11 例（含 TEXT 序列化往返）在平台桶单跑 0 失败。

