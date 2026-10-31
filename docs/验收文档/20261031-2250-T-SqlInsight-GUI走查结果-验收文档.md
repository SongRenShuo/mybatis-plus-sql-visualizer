# T-SqlInsight GUI 走查结果（20261031）

> 工作树：`F:/code/Java/Gitee/SongRenShuo/mpsv-sql-insight`，分支 `t-sql-insight`，HEAD `2e7b934e`（src 与 `bef08646` 合流基线一致，本 HEAD 仅新增走查准备单文档）。
> 沙盒：`build/MPSV-InsightWalk/mybatis-plus-sql-visualizer/IU-2024.3.7.1`（独立沙盒名，init 脚本 `autoReload.set(false)`，`-PgifRobotServer=true`，未带 `-PgifRecord=true`）。
> 证据帧：`build/MPSV-InsightWalk-shots/`（build 生命周期，不入 git）。探针日志：同目录 `probe.log`。
> 走查时间：2026-10-31 21:00–22:50。**只登记缺陷，未改 `src/main` 一行**（`git diff --stat src/main` 为空）。

## 一、环境七项检查

| # | 项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 沙盒 `config/idea.key` | 通过 | 从 `%APPDATA%/JetBrains/IntelliJIdea2026.2/idea.key` 预置，启动无 Manage Licenses |
| 2 | license agent | 通过 | init 脚本 `jvmArgs(-javaagent:C:/Users/USER/.7t/patch/qwdn-afb.jar)`（实测该路径存在；准备单写的 `.qwdn/` 不存在，已按实测路径） |
| 3 | JDK | 通过 | `JAVA_HOME=C:\Users\USER\.jdks\ms-21.0.11`，runIde 构建/启动成功 |
| 4 | 最近工程预置 | 通过 | IDE 直接打开 `mybatis-plus-sql-visualizer-demo`，窗口标题即 demo 工程 |
| 5 | `dataSources.xml` | 通过 | 预览执行前「选择数据源」弹层列出 jpa_demo / mpj_demo / kotlin_demo / selfdev_demo / orm_demo / MySQL |
| 6 | MySQL 库 | 通过 | DBX 实查：`mp_demo` 11 表、`mpj_demo` 7 表 |
| 7 | 构建/起沙盒 | 通过 | `mybatis-plus-sql-visualizer-2026.10.31.jar`（7,618,499 B）21:21 装入沙盒；robot-server 8580 监听；`javap` 核在役 jar 有 `ui.insight.OfflineTraceView`（含 `present(OfflineTraceImport$Result)`），为本分支源码符号 |

沙盒在役包核验（重大项）：jar mtime 21:21 晚于本次构建起点（21:18），IDE PID 61708 于 21:21 启动，符号与本地源码一致。

## 二、冒烟四项

| 项 | 结果 | 真实操作 → 判别点 → 证据帧 |
|---|---|---|
| 1. SQL 日志捕获与还原 | 部分通过 | 工具窗 ▶ 开始捕捉（状态「捕捉中 / 控制台：已挂载 0 个，已扫描 0 行」）→ IDE 内 Shift+F10 起 demo（8081）→ 列表出现 #1..#11，含时间戳与耗时（11/37/42/223/48/42 ms），控制台行「已挂载 1 个，已扫描 361 行」；条目底部出现蓝色源码定位链接 `cn.song.mybatisplus.demo.controller.UserController…`。双击条目开预览后参数区 `#param1=support_batch_bind`、`#param2=on` 已按日志回填。证据 `w06-capture-clicked.png`、`w07-app-run.png`、`w08-log-list.png`、`w18-dblclick-row3.png`。**未取到 `[MySQL]` 方言标签帧**：列表列窄，方言标签被裁，只看到推断出的 GaussDB 预览副作用（见缺陷外观察） |
| 2. 分析 SQL | 通过 | 更多 → 分析SQL → 「SQL分析」页签：分析质量 PARTIAL / 源码区间质量 UNKNOWN / 已验证方言 UNKNOWN；四块树（语句结构与表读写角色 → 结果字段与已证实来源 / 值来源与行选择依赖 / 诊断与覆盖范围）齐全；诊断 `UNSUPPORTED_DIALECT · PARTIAL · Only verified MySQL/PostgreSQL identifier rules are supported.`；离线声明与「点击树节点或图节点定位SQL…」提示逐字在场。`w20-more-menu.png`、`w20-analysis.png` |
| 3. 参数场景 | 通过（非 XML 分支） | 更多 → 参数场景 → 「场景与渲染解释」：提示「参数场景需要XML预览模型；当前SQL和预设未改变。」、「预览脱敏回归JSON」置灰；表头 场景/分析质量/参数数/关系数失败 在场。`w21-param-scenario.png` |
| 4. 运行 SQL 分析入口 | 通过（入口/形态） | 更多 → 运行SQL分析 → 五个子页签（工作负载 / 请求与时间轴 / 事务 / 标签、重复与时间轴 / 请求基线）；「启用所有会话的分析」默认未勾；披露文案逐字在场；状态行 `1: enabled=false; quality=[coverage_not_proven]; bytes=0`（agent 会话已连）。**未产生工作负载数据行**（本轮未开启分析开关跑业务流量）。`w22-run-analysis.png` |

## 三、六项此前「未执行」的 GUI 验收

### 4.4 离线 Trace 导入 —— 通过（含负例）

- 面板「离线Trace投影」：粘贴区、`导入粘贴的JSON`、`关闭分析`、`含{traceId}的HTTPS模板`、`打开选中Trace链接`、`选择本地Trace JSON文件`；表头 traceId/spanId/事件/累计JDBC纳秒/状态；披露「粘贴本地OTLP trace JSON（最多1 MiB）后点击导入，不自动读取剪贴板或文件。仅保留有界数据库span元数据，外部覆盖保持UNKNOWN…」逐字在场。`w23-trace-panel.png`
- 非法 JSON → 状态行「导入已拒绝: invalid_or_oversized_json」，表保持空（整体拒绝，无部分导入）。`w26-bad-imported.png`
- 合法 OTLP JSON → 出现 1 行 span（traceId `4bf92f35…4736`、spanId `00f067aa0ba902b7`、事件 SELECT、累计 2,000,000,000 ns、状态 OK）；汇总行 `otlp-json-trace-v1; coverage=unknown_external_coverage; skipped=0`；详情 `parent=UNKNOWN / service=service_2bf4… / databaseSystem=mysql / coverage=unknown_external_coverage`——外部覆盖保持 UNKNOWN，不猜。`w26-good-pasted.png`、`w26-good-imported.png`
- 未执行子项：`选择本地Trace JSON文件`（本机文件选择器未点开）、`含{traceId}` 模板非法校验负例、>1 MiB 上限。

### 4.5 对象使用与影响 —— 部分通过（面板与扫描通过，反查按钮被裁）

- 面板「显式文件使用反查」：范围下拉（当前文件 / 显式当前模块（不含依赖库））、`显式数据库上下文`、`表名分段用/分隔`、`列（可选）`、`扫描所选范围`；披露「仅显式扫描当前文件，覆盖SQL文件、Java字面常量和静态mapper文本；动态／Kotlin／生成SQL可能未覆盖。数据库身份未知不能证明无影响，限制与诊断保留展示。」逐字在场。`w27-usage-panel.png`
- 填 `t_order` → 扫描所选范围 → 结构化诊断：`SqlUsageCoverage(scopeDescription=current file, visitedFiles=1, candidateCount=0, skippedFiles=0, truncated=false, cancelled=false)` + `DYNAMIC_MAPPER_SKIPPED`×5（动态 mapper 不产猜测 SQL）+ `SUPPORTED_SOURCES` 声明；候选数 0 与表内容一致，未编造引用。`w31-typed.png`、`w31-scanned.png`
- **未验**：`反查对象引用` / `打开选中引用` / `取消` / `关闭分析` 四个按钮在屏幕上看不到也点不中（见缺陷 UI-001）。

### 4.6 本地 Schema 比较 —— 通过

- 面板「Schema快照比较」：左右两个粘贴区 + `关联本次已收集的使用快照`、`打开选中引用`、`将旧/新快照用于当前分析`、`比较粘贴的快照`、`取消`、`关闭分析`（本面板按钮全部可见）；披露逐字在场。`w34-schema-panel.png`
- 左 v1 / 右 v2（同 `mp_demo@localhost`、MYSQL）→ 差异表两行：`NULLABILITY_CHANGED · t_order · amount`、`COLUMN_ADDED · t_order · remark`；详情 `before=DECIMAL / after=DECIMAL / nullable=true → false / affected=[]`；汇总 `database=mp_demo@localhost; v1 → v2; comparable=true; impactComplete=false` + `USAGE_SNAPSHOT_MISSING: …source mapping coverage was not supplied.`（影响范围不完整明确标注）。`w35-schema-compared.png`
- 版本相同负例 → `SCHEMA_VERSION_UNKNOWN: Distinct nonblank snapshot versions are required.`（comparable=false）。`w36-schema-same-version.png`
- 未执行子项：单份 >1 MiB、勾选「关联本次已收集的使用快照」后有快照的合并。

### 4.7 优化候选 —— 部分通过（仅宽投影规则真机命中）

- 面板「静态优化候选」：表头 规则/AST证据；按钮 `打开选中引用` / `关闭分析` / `取消`；披露「仅带前置条件的静态候选，不是实测性能结论。不改写SQL、不建索引、不执行EXPLAIN或查询；缺少精确区间时不猜位置。」逐字在场。`w37-optimization.png`
- 对 `SELECT * FROM t_order WHERE order_no LIKE CONCAT('%', ?, '%') AND amount >= ? AND status IN (?, ?) ORDER BY create_time DESC`（dialect 由 GaussDB 的 DIALECT_UNKNOWN 变为 MySQL 后）→ 命中 `SI.WIDE_STAR_PROJECTION`，AST 证据 `*`，详情含前置条件与 `statement=1; range=UNKNOWN`（缺精确区间不猜位置）。`w51-optimization.png`
- **未验**：`SI.NON_SARG_COLUMN_FUNCTION`（DATE()/LOWER() 包裹列）与 `SI.CARTESIAN_JOIN` 的真机命中。原因：预览 SQL 编辑器只读，无法在面板里替换 SQL；夹具走 .sql 文件与 XML 新增语句两条路都被沙盒 VFS 不刷新挡住（外部新建/修改的文件在 IDE 内 `Ctrl+Shift+N` 索引不到、重开页签仍是旧缓冲，IDE 未自动 reload）。规则语义有既有单测 `SqlOptimizationAdvisorTest`（三条规则 + 不编造负例）兜底，本轮未复跑（走查纪律：不跑测试占用构建槽位）。
- 空态行为：对无命中语句打开时表为空且只给诊断（`DIALECT_UNKNOWN` / `SOURCE_RANGE_UNKNOWN`），不编造建议——与预期一致。`w43-optimization.png`

### 4.8 SELECT 结果比较 —— 部分通过（形态与拒绝对话通过，未走审批执行）

- 面板「显式SELECT结果比较」：目标下拉为空、`发现目标`、`使用明确选中的目标`、`审阅准确SQL对`、`单次批准此SQL对`（置灰）、三个勾选默认未勾（`已审阅SELECT函数副作用` / `两段SQL均已填入明确字面参数` / `批准存在的原样替换片段`）；披露「两条SELECT会在新的只读auto-commit连接上顺序执行，不是原子快照；…每条最多100行／10秒，未知或截断结果为INCONCLUSIVE。仅打开本页不会执行。」逐字在场；表头 侧/行数/状态。`w63-comparison.png`
- 未选目标点「审阅准确SQL对」→ 状态行「请先明确选择目标，不默认选取任何数据源。」`w65-review.png`
- **未验**：发现目标 → 选目标 → 三勾 → 单次批准 → 执行（真实只读连接）；另「确认两段SQL方言适用于所选目标」勾选在本帧不可见（见 UI-001）。

### 4.9 写 SQL 影响面与事务关联 —— 部分通过（负例通过，正向 COUNT 链未复跑）

- 对 SELECT 预览执行「更多 → 预估影响行数（DML 影响面）」→ 顶部提示「仅 UPDATE/DELETE 语句有影响面预演。」（`dml.impact.reason.notDml`）。`w65-dml.png`
- **未复跑**：UPDATE 预览 → 只读 COUNT 试算 → 事务关联同屏。原因：本轮日志捕获面里没有 UPDATE 条目；造 UPDATE 预览的夹具路径与 4.7 同因受阻（只读预览编辑器 + VFS 不刷新）。正向链在 2026-10-29 轮已取证（入口→真库 COUNT→失败/成功呈现），本轮未重放。

## 四、合并带入项复看（§4.10 / §4.11）

| 项 | 结果 | 说明 / 证据 |
|---|---|---|
| 4.10 指示行形态 | 通过 | 预览参数区上方纯文本一行「未绑定数据源（在控制台执行一次查询即可绑定）」；robot 全窗查 `切换数据源` 命中 **0**、`已绑定`/`上次使用` 命中 0——**无自建换源控件**（W12 纪律）。`w18`、`w63`、`w66` 帧顶部均可见 |
| 4.10 三态 | 部分 | 本轮仅现场取到「两者都无」态；「已绑定 / 本模块上次使用 / 项目级上次使用」未在本轮造态（需先在控制台执行一次查询或换源，属环境构造），2026-10-29 轮已取到后两态 |
| 4.11 执行计划入口 | 部分 | 点「执行计划」→ 弹插件执行前置「选择数据源」（列出 6 个源；这是执行选源，不是已退役的换源控件）；**未点选源、未取到内嵌原生控制台（形态 A）**，也未触达红条/横幅「切换数据源 → 打开 Database 工具窗口」路径 |
| 社区版降级 | 未验 | 本沙盒为 IU Ultimate，无法验社区版形态 |

## 五、dev 新能力顺带确认

| 能力 | 结果 | 说明 / 证据 |
|---|---|---|
| 禁用 API 子页 | 通过（入口 + inspection 注册） | 设置树 Tools → SQL Visualizer 下可见 `禁用 API`；Editor → Inspections 页中 `SQL Visualizer / Banned API usage` 已注册且勾选。`w70`、`w74` |
| 外部 SQL 模板子页 | 通过（入口） | 设置树可见 `外部 SQL 模板` 子页（未逐项打开内部控件） |
| 环境体检按钮 | 未验 | 设置子页导航在合成输入下不稳定（搜索框会吞键、左树点击偶发不跳转），未打开到含该按钮的页；仅确认设置页整体可开可关 |
| EXPLAIN 结果建议条默认可见（R45） | 未验 | 无可用 EXPLAIN 执行（未绑定数据源 / 未点选执行源），未取到结果面板 |
| 预览「更多」→ 导出优化留痕 | 通过 | 菜单末项存在；点开弹出原生保存对话框，默认文件名 `trace-doc-20261031-222910.md`，文件类型 Markdown。`w67-export-trace.png` |
| 升级后版本亮点通知（一次性） | 通过 | 首次启动右下角通知「SQL Visualizer 新版本亮点 / 版本 2026.10.31 的新功能」+「打开设置 / 打开 SQL 日志 / 查看更新日志」。`w00-startup.png` |
| 设置页 gutter 来源分组多选 | 不符预期（登记 UI-002） | 本树实现为「界面与操作」分组下 **6 个独立复选框行**（mapper XML / 注解 SQL / JPA-HQL / JdbcTemplate / 通用 XML 内嵌兜底 / Wrapper 链·方法名字符串 SQL），**没有浮层，也没有全选/全不选**（`PluginSettingsPanelSections.kt:639-644`）。GUI 未打开到该分组（导航受限），形态结论取代码静态面 |

## 六、缺陷清单（UI-0xx）

| 编号 | 位置/路径 | 实际形态 | 预期 | 严重度 | 证据 |
|---|---|---|---|---|---|
| UI-001 | 预览 → 更多 → 对象使用与影响（以及 比较SELECT结果） | 面板顶部操作行内容超宽后 FlowLayout 换行，第二行控件被状态文案区**覆盖/裁掉**：对象使用页的 `反查对象引用` / `打开选中引用` / `取消` / `关闭分析` 屏幕不可见且点不动（robot 组件树可见其 bounds：反查 (443,377) 106×34、打开选中引用 (554,377)、关闭分析 (736,377)，但该区域实际被状态文本占据）；比较SELECT结果页的 `确认两段SQL方言适用于所选目标` 勾选落在 (494,529) 同样被披露文案覆盖 | 所有操作按钮/勾选可见可点 | 中（4.5 的核心「反查」与 4.8 的第三项批准条件不可达） | `w31-scanned.png`、`w33-usage-find.png`、`w63-comparison.png`；robot 读数见 `probe.log` |
| UI-002 | 设置 → Tools → SQL Visualizer → 界面与操作 | gutter 来源分组是 6 个独立 JBCheckBox 行，无聚合浮层、无全选/全不选 | 走查单要求「6 个开关合成一个浮层，点开确认 6 项都在、全选/全不选可用」 | 低（信息项；可能走查单预期已过期） | 代码静态面 `PluginSettingsPanelSections.kt:639-644`、bundle `settings.gutterGroup.*`（1744-1749） |

根因推测（供修复参考，未验证）：`InsightDetailPanel` 把 `actions`（FlowLayout）放在 BorderLayout.NORTH 的顶部，该 NORTH 面板高度取**单行**首选高度；换行后的第二行被裁在其父面板可视区之外，并落在相邻的 status 文案区下方。

## 七、探针限制与未验项（如实登记）

1. **预览对话框内页签切换**：对话框内的 JBTabs 不是 robot 可见的 `TabLabel`（只有外层编辑器页签与工具窗页签可见），合成点击 `SQL` 页签坐标多次无响应——导致 4.7 无法在 SQL 页签里读/换 SQL，也影响在页签间取证。属探针/输入限制，不判产品缺陷。
2. **沙盒 VFS 不刷新**：走查期间外部新建/修改的文件在 IDE 内查不到（`Ctrl+Shift+N` 无结果、重开页签仍加载旧缓冲），`Synchronize` 动作未生效。这是 4.7/4.9 夹具注入失败的直接原因；已还原所有外部改动（见第八节）。
3. **设置页导航**：搜索框对插件自定义 Configurable 无索引（出现 "Nothing to show"/重复串），左树点击偶发不跳转，改用键盘 Down/Up 可跳转（已验证走到 Table Name Mapping）。`环境体检按钮` 因此未打开验证。
4. **未验清单**：4.4 文件导入 / 模板非法校验 / 1MiB 上限；4.5 反查与跳转（被 UI-001 阻断）；4.6 >1MiB 与关联快照合并；4.7 NON_SARG / CARTESIAN 真机命中；4.8 审批+执行；4.9 正向 COUNT 与事务关联；4.10 其余三态；4.11 内嵌原生控制台形态 A 与横幅动作；社区版降级；EXPLAIN 建议条；环境体检按钮；gutter 分组 GUI 形态。

## 八、现场清理

- demo 仓：`walk-fixture-candidate.sql` 已删；`OrderMapper.xml` 的 `walkOptimizationFixture` 夹具已撤销，`git status` 回到走查前状态（只剩走查前就存在的 `application.yml` 改动与既有未跟踪目录）。
- 插件仓：`src/main` 零改动（`git diff --stat src/main` 为空）；新增的探针脚本在 `record/walk*.py`、`record/walk_util.py`、`record/ps_procs.ps1`（临时探针，可删）；新增 `.local/insight-walk.init.gradle`、`.local/start-insight-walk.ps1`（起沙盒用，可删）。
- 沙盒与 IDE 进程保留在 `build/MPSV-InsightWalk`，供复验；demo 应用（8081）仍在运行。
- 并行登记：已 `register --name insight-walk`，走查结束 `done --name insight-walk`。
