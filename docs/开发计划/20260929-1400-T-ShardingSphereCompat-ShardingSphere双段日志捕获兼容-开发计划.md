# R10 T-ShardingSphereCompat ShardingSphere 双段日志捕获兼容（Logic SQL / Actual SQL 对照渲染）— 开发计划

- **日期**：2026-09-29
- **编号**：R10（第五轮报告 §8 新功能推荐池 D16 定案排序第一；本计划为独立实施计划，不占新 O 号）
- **动机**（第五轮报告 §8 原锚点，2026-09-29 已逐条复核）：MyBatis Log 付费版 2023 评论原话求助 ShardingSphere 支持；我方捕获格式现仅 MyBatis/Hibernate/JdbcTemplate 三种——`grep -rni "sharding" src/` 零命中（20260929 实测，exit=1）。分库分表场景的 **Logic SQL 与 Actual SQL 对照**是竞品空白 + 用户原声诉求，捕获格式扩展属三支柱「增强捕获准确性」主轴
- **成本**：M 级
- **状态**：已核实待立项实施（用户 D16 定案排序第一）；证伪清单继承第五轮 §8 十条，勿重复立项

---

## 一、现状锚点（2026-09-29 全部实测重锚）

| # | 现状 | 锚点（文件:行，本工作树实测） |
|---|---|---|
| ① | 捕获双轨入口：EP 为主（consoleFilterProvider），桥反射轮询兜底 | `log/SqlConsoleBridgeFilterProvider.kt:26-29`（getDefaultFilters）、`:41-56`（EpLineFilter 内 BridgeLineContext 三解析器实例化）、`:58-71`（parserConfig 读设置）；桥侧 `log/SqlLogConsoleBridge.kt:51`（BridgeLineContext 同构三解析器） |
| ② | 三解析器并列运行，互不干扰，共享 LogSqlEntry 模型 | `log/MyBatisLogParser.kt:27`、`log/HibernateLogParser.kt:20`（:360 严格模式行首精确前缀匹配）、`log/JdbcTemplateLogParser.kt:33` |
| ③ | 设置面前缀族：MyBatis 前缀组 + Hibernate 前缀组 + JDBC 开关 | `config/PluginSettings.kt:254-260`（logSqlPrefix/logParamPrefix/logMixedPrefix）、`:388-394`（logHibernateEnabled/logHibernateSqlPrefix/logHibernateParamPrefix）、`:399`（logJdbcEnabled） |
| ④ | 消费面两处复用同链：面板批量还原 + MCP 工具 | `log/LogToSqlPanel.kt:39`（复用 MyBatisLogParser 配对与 LogSqlRestorer，与自动捕获同链路）；`mcp/SqlVisualizerRenderLogsTool.kt:19-60`（sqlv_render_logs feed→closePending→takeEntries 同构纯函数） |
| ⑤ | ShardingSphere 相关 SQL 形态解析现状 | 全仓零命中（`grep -rni "sharding" src/` 无输出）——R10 为从零新增第四解析器 |
| ⑥ | 双段日志的先例：Hibernate 解析器已是「SQL 行 + 参数行分离等待态配对」形态 | `log/HibernateLogParser.kt:360-370`（行首精确前缀 + 独占一行进入等待态）——ShardingSphere 的 Logic SQL/Actual SQL 配对可循此先例 |

## 二、方案（四个子任务，解析器先行）

| 子任务 | 内容 | 写集 |
|---|---|---|
| **T-01 第四解析器（主体）** | 新增 `ShardingSphereLogParser`：解析 ShardingSphere SQL_SHOW 日志的 Logic SQL（`Logic SQL: ...`）与 Actual SQL（`Actual SQL: db_0 ::: ...`，绑定参数 `Parameters: [...]`）双段，配对成 LogSqlEntry（复用 Hibernate 等待态配对先例）；Logic SQL 为主条目（可还原参数执行），Actual SQL 段作为附注（对照渲染数据源） | 新文件 `log/ShardingSphereLogParser.kt` + `log/LogSqlModels.kt`（条目模型扩 actualSql 附注字段） |
| **T-02 捕获双轨接线** | EpLineFilter 的 BridgeLineContext 与桥侧同构点各挂第四解析器；设置开关 `logShardingSphereEnabled`（默认开，与 hibernateEnabled 同款默认口径） | `log/SqlConsoleBridgeFilterProvider.kt:41-56` + `log/SqlLogConsoleBridge.kt:51` + `log/BridgeLineContext`（归属文件实施时重锚） |
| **T-03 设置面 + 面板渲染** | 设置页增 ShardingSphere 组（开关 + 前缀可配，照抄 Hibernate 组形态，禁自创 UI）；SQL 面板条目 Logic/Actual 对照渲染（Actual 段折叠附注，样式对齐既有条目附注形态）；`sqlv_render_logs` 工具描述与解析链同步扩第四格式 | `config/PluginSettings.kt:388-399` 邻域 + `config/PluginSettingsPanel.kt` + `log/LogToSqlPanel.kt` + `mcp/SqlVisualizerRenderLogsTool.kt` + bundle 双语 |
| **T-04 语料测试集** | 真机/社区采样 ShardingSphere 日志语料钉格式（V11 前提先证伪再动码），按版本差异（4.x/5.x 输出形态）建测试夹具；先红后绿 | `src/test/` 新增解析器测试类 |

**实施顺序**：T-04 语料采样（外部动作，可与 T-01 并行启动）→ T-01 → T-02 → T-03。
**写集预警**：`PluginSettings.kt` 为全仓热文件（并行任务多），开工前 `git log --oneline -5 -- <file>` 实测在途；行号以当次实测为准。

## 三、验收

1. T-01 先红后绿：语料夹具在解析器存在前解析失败（红），实现后全绿；
2. 双轨不重不漏：EP 与桥同一条 ShardingSphere 日志只产一条条目（既有去重口径兜底断言）；
3. 三解析器零回归：既有 MyBatis/Hibernate/JdbcTemplate 捕获测试全绿；
4. 面板渲染真机走查：Logic/Actual 对照在 SQL 面板可读、折叠态不炸版；
5. sqlv_render_logs 对 ShardingSphere 语料的 round-trip 还原断言。

## 四、决策点

| 编号 | 决策点 | 推荐 |
|---|---|---|
| **D1（本计划）** | Actual SQL 段的渲染形态 | **A：Logic 主条目 + Actual 折叠附注**——对照价值保留、面板不炸版；B：两条独立条目（面板翻倍噪音大）；C：只收 Logic 丢弃 Actual（丢对照价值，R10 名不副实） |
| **D2（本计划）** | 版本兼容口径 | **A：以 5.x（SQL_SHOW / props sql-show）为主，4.x 登记不支持**——5.x 是现役主流，4.x 语料难采；B：双版本都做（成本升 L 级不值） |
| **D3（本计划）** | 设置默认值 | **A：默认开**（与 logHibernateEnabled 同款口径，用户零配置收益）；B：默认关防误捕获（与 jdbcEnabled 默认开口径矛盾） |

## 五、待验证前提（显式标注推断项）

- **V11（继承第五轮）**：ShardingSphere 双段日志格式存在版本差异——SQL_SHOW 配置形态、druid/sharding-jdbc 输出差别、`Logic SQL:`/`Actual SQL:`/`Parameters:` 字面格式均系**社区文档推断，未实测采样**。T-04 第一步即采样钉格式，格式不符则 T-01 设计推翻重议。
- **V11a（新增推断）**：ShardingSphere 日志经 SLF4J 落控制台时是否与 MyBatis `Preparing:/Parameters:` 行交错——若用户同时开 MyBatis 日志，两解析器对同一行是否产生重复条目，依赖既有去重口径（同 SQL 同指纹）兜底，未实测。
- **V11b（新增推断）**：Actual SQL 的绑定参数还原（`Parameters:` 行 → 可执行 SQL）是否可复用 LogSqlRestorer 现链，未核实——若 ShardingSphere 参数行格式与 MyBatis `Parameters:` 不同构，还原需独立实现（成本 +S）。
- **V11c（新增推断）**：BridgeLineContext 的归属文件与构造签名按 `SqlConsoleBridgeFilterProvider.kt:41-56` 用法推断，实施时以定义处实测为准。

## 六、红线继承

付费订阅 × 纯格式化插件化 × 订阅制 AI × 插件生态平台化 × 数据库管理工具化 × 独立桌面应用 × MCP 工具数竞赛 × inspection 校验器 × 悬停预览回归——均不做。R10 属捕获准确性主轴，不在红线清单。

## 七、实施记录

- **2026-09-30（批次9 R10，骨架首版落地；V36 语料采样外部动作缺位→按计划降级口径执行）**：
  - **已落地**：第四解析器 `ShardingSphereLogParser`（`log/ShardingSphereLogParser.kt`，Logic SQL 主条目 + Actual SQL 逐行折叠附注，循 Hibernate 等待态配对先例 + LogThreadRouter 线程分桶 + JdbcTemplate 静默冲刷先例）；`LogSqlEntry.actualSql` 附注字段 + `SqlSource.SHARDINGSPHERE`（D1=A 模型承载）；pd 载荷扩展键 `a` + SHARDINGSPHERE 来源落键（SqlLogParamCodec/AutoSaver/FileReader 持久化闭环，旧文件兼容）；双轨接线（EP EpLineFilter + 桥 attachFilter 的 BridgeLineContext 第四解析器、processConsoleLine/sniffLogTimestamp 喂行、applyConfig 快照同步、flushSettledEntries 静默兜底）；设置开关 `logShardingSphereEnabled`（D3=A 默认开，照抄 Hibernate 组形态，复选框对账守卫 39→40 同步）；`sqlv_render_logs` 第四格式 + `LogToSqlPanel` 四解析器（T-03 解析链面）。
  - **等语料（V36，真实双段日志语料到位后补全）**：①`Parameters: [..]` 值含 ", " 的拆分规则（骨架按 5.x 公开文档样例「逗号+空格分隔」直拆）；②4.x 输出形态（D2=A 登记不支持）；③与 MyBatis `Preparing:` 行交错的配对语义与去重兜底（V11a；骨架保守：PENDING 态噪声行不闭合，条目滞后一条产出、静默冲刷兜底）；④Actual 折叠附注的面板对照渲染（T-03 UI 面，模型字段已在）；⑤参数行类型标注是否在位（当前无类型走 LogSqlRestorer 启发式，V11b 复用结论=可复用）。显式 TODO 注释见解析器类头与 `extractParams`。
  - **销案（2026-11-22，V36 真实语料采样闭合）**：真实双段日志已采样留存于 demo 仓 `mybatis-plus-sql-visualizer-demo/shardingsphere-h2-demo/sample-logic-actual-log.txt`（真实 `shardingsphere-jdbc` 5.5.2 + H2 2.2.224 纯内存 CLI 实跑 stdout 原样节选，`--once` 退出码 0、stderr 无输出；含 `BOOK,100`/`BOOK,101` 逗号参数边界形态与跨片双 Actual 行）。对齐结论（与 `ShardingSphereLogParser.kt` 逐字段静态比对）：①行前缀（logback `HH:mm:ss.SSS LEVEL [thread] ShardingSphere-SQL - `）被 `indexOf` 任意位置定位容忍，命中；②`Logic SQL:` 主条目与 `Actual SQL: <ds> ::: <sql> ::: [100, BOOK,100]` 整段原文保留（现行口径＝Actual 不拆参，`extractParams` 已不在主源，逗号参数形态天然无损），命中；③5.5.2 参数为 Actual 行内第三段，**无独立 `Parameters:` 行**（`PARAMETERS_KEYWORD` 常量在位但 5.5.2 采样不含该形态，等语料①的拆分规则随之消解）；④跨片两条 Actual 归一条 Logic 的折叠（actualLines join `\n`）与采样一致。解析器定向测试已跑：`ShardingSphereLogParserTest` 18/0 绿（2026-11-22 复跑，BUILD SUCCESSFUL；用例夹具与本采样形态逐字同构）。②4.x 形态／③V11a 交错／④面板渲染／⑤类型标注等其余等语料子项不在本销案范围，仍按原留账执行。排期册同条留账（V36 行）一并销案。
  - **测试**：合成夹具全部按 5.x 公开文档形态合成、注明 synthetic（不冒充真实语料）。先红后绿：判别测试实现前编译红 32 处 Unresolved reference（ShardingSphereLogParser×9/SHARDINGSPHERE×2/actualSql×4/shardingEnabled×2 及连坐）→ 实现后 ShardingSphereLogParserTest 9/0 绿；反向验证摘 processConsoleLine 第四解析器注册 → 1 failed（9 tests, failures=1）→ 还原复绿 9/0。定向回归（快桶）：SqlLogParamCodecTest 14/0 + SqlLogConsoleBridgeMarkerTest 13/0 + HibernateLogParserTest 56/0 + JdbcTemplateLogParserTest 52/0 + RenderLogsToolTest 4/0 + McpRenderInputTest 6/0 + BundleParityGuardTest 5/0；平台桶：PluginSettingsPersistenceTest 15/0（含复选框 40 守卫）+ ConsoleSettleFlushTest 5/0。插曲：主源 ABI 变更（ParserConfig/LogSqlEntry 构造签名）后 Kotlin 增量编译未重建未改动测试调用方 → NoSuchMethodError 假红，定向清 test classes 全量重编测试后全绿（非 clean，主产物未动）。
