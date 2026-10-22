# T-LaneB：Desensitizer 延伸线三件套（R60 展示/导出链脱敏 + R37 安全 AI 上下文交递 + R62 更新失败可诊断化）

- 日期：2026-10-07 20:29（车道 B 全批，20261007 盘点 72 版定案「立即可动」车道）
- 基线：dev `508205e`（车道 A 批一 F79+R54 已并入后）
- 定位：本册是车道 B 的立项与实施分册。R37/R60 按 0701 第十七轮审计「同 Desensitizer 延伸线一次设计」合并立项（D95），R62 独立快件。上位定义以 `20261007-0701` §二（R60/R62 + V112/V114）与 `20261002-0645` §三.1/§五 D39（R37 + V51）为准，冲突时以上位册为准。

## §一 前提回执（动手前证伪，全部实测）

### V112（R60 前提）回执——三条全部成立

1. **Desensitizer 双条件规则逐条落档**（`sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/util/Desensitizer.java:10-30`）：
   - 打码结果固定 `"***"`；空/null 值原样返回（不打码）。
   - 条件①列名/参数名提示：hint 小写化后 contains 命中关键词表任一项 → 打码。关键词表 24 项：`password/passwd/pwd/token/secret/credential/apikey/api_key/access_key/private_key/idcard/id_card/mobile/phone/身份证/手机号/密码/密钥/令牌`。
   - 条件②值形态兜底（无 hint 也命中）：18 位身份证（前 17 位数字 + 末位数字/X/x）或 11 位手机号（全数字且首位 1）。
   - 开关语义：`desensitize=false` 原样返回（agent 侧 `enhancedCaptureDesensitize` 下发，默认关）。
2. **导出链挂点＝SqlExportService 单点**：`format(rows, format)`（SqlExportRow.sql，CSV/TSV/TXT 三格式分发）与 `formatPortable(rows, format)`（O-91 可移植脚本）是全部导出文本的格式化单点；调用方（SqlLogPanel.exportVisibleEntries / HistorySqlLogPanel / PortableSqlExport.collectRows 后）只做行集投影与写盘。**定案：脱敏挂在两 format 入口对行集 sql 字段做投影级改写，数据层条目（LogSqlEntry.restoredSql）不动。**
3. **重绘入口存在**：SqlLogPanel/HistorySqlLogPanel 均已订阅 `PluginSettings.SETTINGS_CHANGE_TOPIC` → `refreshList()/repaintSqlList()`（设置变更即时重绘既有通道）；单击预览浮层每次点击新建（无重绘需求）。**附带发现**：多行模式片段缓存键只含显示模式（`cachedListLineFragmentsMode`），脱敏开关翻转若不入键会读到脏缓存——实施必须补键位（已纳入 D96）。

### V114（R62 前提）回执——三条全部成立

1. **失败路径现状**：`UpdateApi.fetchManifest`（UpdateApi.kt:24-33）`runCatching` 把**一切异常吞成 null**（网络不通与 HTTP 404 同归），`PluginUpdateRunner.check` 映射为 `Failure("network unavailable")`——内部英文串经 `settings.update.checkFailed` 直出设置页；启动检查静默。三态不可判：404/网络/清单损坏混为一谈。
2. **兜底链接常量**：Gitee＝`UpdateSource.GITEE_RELEASES_URL`（UpdateSource.kt:36，UpdateSection/StartupUpdateCheck 已复用）；**Marketplace 无 Kotlin 常量**——`https://plugins.jetbrains.com/plugin/33633-sql-visualizer` 仅存在于 `src/main/resources/html/marketplace-custom-page.html:455` 静态页。V114② 原文「about 页已有 Gitee/Marketplace 链接可复用」半数证伪：需在 UpdateSource 新增 `MARKETPLACE_URL` 常量（取 html 页同值）。
3. **HTTP 状态码可得性**：`UpdateApi.fetchManifest` 已设 `throwStatusCodeException(true)`，非 2xx 抛 `com.intellij.util.io.HttpRequests$HttpStatusException`；javap 实证 243 基线（ideaIU-2024.3.7.1 app-client.jar）声明 `public int getStatusCode()`。**三态判别链路可用，无需降级。**

### V51（R37 前提）回执——四项全部核实

1. **Desensitizer 复用面**：agent 是独立源集（`sub-projects/sql-visualizer-agent`，随 jar 注入目标应用 JVM），主仓编译 classpath 不可引用——**规则只能在主仓等价重写**（与 R60 审计册形态描述一致：DisplayMasker 双条件逐条对齐）。
2. **哪些字段算敏感**：承 Desensitizer 双条件（列名/参数名关键词 hint + 值形态兜底），展示链的「列名 hint」从 SQL 字面量的前置上下文（`= / IN / LIKE / VALUES` 等运算符前的最近标识符）推导。
3. **参数化规则**：R37 原始形态「敏感字面量参数化」——敏感字面量替换为 `?`，参数表行记 `n: ***`（值永不离开本机；AI 拿到结构不拿值）。
4. **输出块格式（定案）**：注释头（来源声明+方言+打码计数）+ 参数化 SQL + 脱敏参数表。**EXPLAIN 计划与表列摘要不入首版块**：ExplainPlanResultView 的计划文本在面板侧无稳定引用（控制台文档随渲染轮换）；schema 列走 `DatabaseSchemaResolver.columnsCached` 预热缓存，表集抽取链（SqlTableExtractor）面向日志条目而非任意 SQL——两处接入成本与首版价值不匹配，留扩展位（见 §五 未尽事项）。
5. **与 sqlv_schema_context 的脱敏口径对齐**：该工具输出纯元数据（表名/列名/类型/注释/NOT NULL，描述明示 credentials never leave the IDE）——**零字面量值，无需挂值脱敏**；对齐点＝MCP 输出面（sqlv_render_logs）的脱敏选项与 R37/R60 共用同一 DisplayMasker 双条件规则（D97）。

## §二 三件定义（承上位册，含本册裁量）

### R60 展示/导出链字面量脱敏（S-M）

- **主仓新增 `DisplayMasker`**（`util` 包，纯 Kotlin 零平台依赖）：`mask(columnHint, value)` 逐条对齐 Desensitizer；`maskSqlText(sql)` 扫描 SQL 文本内的字符串字面量（`'...'` 含 `''` 转义）与裸数字形态，按双条件打码（带引号字面量打为 `'***'`，裸数字打为 `***`）。
- **四链挂接**（每链一条判别断言，见 §四）：
  1. 日志列表（SqlLogPanel + HistorySqlLogPanel 的 `sqlLineFragmentsFor`，经共享纯入口 `DisplayMasker.maskDisplay(sql, enabled)`；缓存键补 mask 位）；
  2. 预览浮层（`SqlClickPreview.clickPreviewSql` 增加 mask 形参，两面板调用方传设置值）；
  3. 导出（`SqlExportService.format/formatPortable` 增加 mask 形参，调用方传设置值；只改投影行文本）；
  4. MCP sqlv_render_logs（`renderLogs` 输出前对 `entry.restoredSql` 打码，独立选项控制）。
- **开关语义（D96）**：`displayMaskEnabled`（展示/导出三链：列表/预览浮层/导出，默认关——不改变既有用户观感）＋`mcpMaskEnabled`（MCP 面，默认关——脱敏降低 AI 诊断价值，承审计册口径「默认不脱并在工具描述里声明」）。两者与采集侧 `enhancedCaptureDesensitize` 完全解耦（三开关三个面）。MCP 开关 UI 落 McpConfigurable（MCP 面的设置页）。
- **边界（红线）**：脱敏只动展示层文本——LogSqlEntry/参数预设/历史文件/回滚快照等数据层一概不动；**主预览编辑器文档（SqlPreviewPanel 双击日志条目后的可编辑+可执行面）不挂脱敏**：执行/EXPLAIN/DDL 失效链消费文档文本（SqlPreviewPanel.kt:1412/:1552/:1592），脱敏会破坏执行语义，且该面参数行本就是 `?`+参数表形态（值在参数表，用户自输/预设回填）。此裁量与 V112③ 重绘入口回执同档落档。

### R37 安全 AI 上下文交递（S~M，与 R60 同线合并设计）

- **出口形态（「只差出口」的真实缺口核实）**：批次 9 后 MCP 面已有 sqlv_schema_context/sqlv_explain 等结构化数据工具（接了 MCP 的 agent 可用），**未接 MCP 的用户没有任何「把 SQL 安全交给任意 AI」的插件内出口**——缺口成立，出口＝预览面板「更多」菜单新增「复制为 AI 上下文」动作（低频动作收纳处，T-UiCoord F1 既有位）。
- **动作体**：取当前编辑器 SQL + 方言（current.previewModel.dialect）→ `AiContextExport.build`（DisplayMasker 敏感字面量参数化）→ 剪贴板 + 复制成功反馈。
- **输出块格式**（V51.4 定案）：`-- SQL Visualizer AI context` 头（方言+打码计数）+ 参数化 SQL + `-- params (masked): 1=*** …` 参数表。零敏感字面量时无参数表行（块即方言+原文）。
- **口径对齐**（D97）：R37/R60-MCP/采集侧三面同一套双条件规则文本（DisplayMasker 与 Desensitizer 逐条对齐，关键词表/形态判定/打码值一致）。

### R62 更新失败可诊断化（S）

- **UpdateApi**：`fetchManifest` 拆出 `fetchOutcome(url): FetchOutcome`（sealed：`Success(text)`/`HttpError(status)`/`NetworkError(desc)`，HttpError 由 `HttpStatusException.getStatusCode()` 判）；原 `fetchManifest` 保留为 outcome 的文本视图（兼容既有调用注释口径）。
- **PluginUpdateRunner**：`CheckResult.Failure` 增 `kind`（NETWORK/CHANNEL_MISSING/MANIFEST_MALFORMED/CURRENT_VERSION_UNAVAILABLE），fetch seam 类型改 `(String) -> FetchOutcome`；check 按 outcome 映射（404→CHANNEL_MISSING、连接类异常→NETWORK、解析失败→MANIFEST_MALFORMED）。
- **UpdateSection**：Failed 态按 kind 分流文案（bundle 三键）；CHANNEL_MISSING 态额外显示 Marketplace+Gitee 兜底链接行（复用 `UpdateSource.GITEE_RELEASES_URL`＋新增 `MARKETPLACE_URL` 常量，SafeBrowser 打开）。启动检查维持静默（既有定案，不扩面）。
- **「已最新」态**：既有 `UpToDate` 已可判，本件不改其语义（三态＝失败二分+最新，合计三态可诊断）。

## §三 占号声明

- 本册新占：**D95~D97、V121**。R37/R60/R62 系上位册既有占号（0701 册 §二/§六），本册不重复占用。
- D95＝R37+R60 合并立项同 Desensitizer 延伸线一次设计（规则源复用/两链开关语义/块格式定案）；D96＝R60 四链挂点与开关语义（displayMaskEnabled+mcpMaskEnabled 双开关默认关、主预览编辑器不挂的裁量、缓存键补 mask 位）；D97＝三面口径对齐声明（采集 agent/MCP/展示导出同规则文本）。V121＝本册验证判据（§四）。
- 取号实测（2026-10-07 车道 B）：主线台账（docs/变更记录.md）最高占用 O-120/R66/D93~D94/V120（T-ExistsChainTerminal 行）；下一全新号 O-121/R67/D95/V121——本册取 D95~D97/V121，不撞号。后续批次全新号自 O-121/R67/D98/V122 起（先查台账再取）。

## §四 验证判据（V121）

1. **R60 四链各至少一条判别断言**（脱敏开→展示/导出/MCP 文本含 `***` 且原文手机号/身份证/密码值不出现；关→原文逐字保留）：
   - 列表链＝`DisplayMasker.maskDisplay`（两面板 sqlLineFragmentsFor 的实际入口）；
   - 预览浮层链＝`clickPreviewSql` mask 形参两态；
   - 导出链＝`SqlExportService.format/formatPortable` mask 形参两态（CSV/TXT 至少各一）；
   - MCP 链＝`renderLogs` maskLiterals 两态。
2. **R37 出口断言**：AiContextExport 块含方言行/参数化 SQL（敏感字面量→`?`）/参数表 `***`；非敏感字面量保留；DisplayMasker 规则断言（关键词 24 项/双形态/开关关原样）单列。
3. **R62 三态各一例**：check 映射（HttpError(404)→CHANNEL_MISSING、NetworkError→NETWORK、合法清单且版本不高→UpToDate）＋UpdateSection 渲染（CHANNEL_MISSING→404 文案+兜底链接可见、NETWORK→网络文案+链接不可见、UpToDate→既有文案）。
4. **先红后绿＋反向验证**：每件测试先于实现落盘（红＝编译缺符号或断言失败，留读数）；绿后做反向验证（摘掉对应修复分支→定向复红，贴两次读数）后复原。
5. **i18n**：新增文案键全部走 MpSqlVisualizerBundle/MpSqlVisualizerBundle_zh 双语。
6. 测试纪律：只跑定向（脱敏/导出/MCP 工具族/更新检查族/设置族），不跑全量与构建校验；gradle 前查 parallel_tasks.py 登记表。

## §五 未尽事项与风险

- **R37 块首版不含 EXPLAIN 计划与表列摘要**（V51.4 裁量）：计划文本面板侧无稳定引用、schema 摘要表集抽取链不复用；后续若 R61（EXPLAIN 跨次对比）落地，结构化计划同域可顺势补块内 `PLAN` 段。
- **DisplayMasker 列名 hint 推导是启发式**（前置最近标识符）：覆盖 `= / IN / LIKE / VALUES` 常见形态；别名/函数包裹列（`JSON_EXTRACT(u.profile, '$.phone') = '138…'`）取到的是函数名不命中——值形态兜底仍可命中 11 位手机号；关键词面靠列名别名的场景（`contact = '13812345678'`）只靠形态兜底。与采集侧 Desensitizer 语义等价（采集侧 hint 来自 ParamMap 参数名，同为启发式）。
- **MCP 开关热生效**：mcpMaskEnabled 为设置态直读（每次工具调用读取，无缓存），改设置即生效，无需重启。
- 主仓 DisplayMasker 与 agent Desensitizer 是两份同规则文本——关键词表将来增改需双侧同步（本册在两处 KDoc 互指锚点）。

## §六 实施记录

- 2026-10-07 20:29 立项册落盘（车道 B，工作树 mpsv-laneb，分支 t-laneb，基线 508205e）。前提回执 V112/V114/V51 三册九条全部实测落档（§一）。待实施：R60 → R37 → R62（分件提交）。
- 2026-10-07 21:0x **R60 落地（提交 8a903d5）**：DisplayMasker（util 包纯 Kotlin）+四链挂接（列表两面板 sqlLineFragmentsFor 经 maskDisplay＋缓存键补 cachedListLineFragmentsMasked；clickPreviewSql/format/formatPortable/renderLogs 各增 mask 形参，调用方传设置）＋双开关（displayMaskEnabled/mcpMaskEnabled 默认关，PluginSettingsPanel 增强捕获区与 McpConfigurable 各一勾选）＋i18n 双语。定向读数：DisplayMaskerTest 17/0、SqlExportServiceTest 9/0、RenderLogsToolTest 7/0、SqlLogPanelRendererUiTest 38/0、SqlClickPreviewTest 18/0。先红＝compileTestKotlin 失败（Unresolved reference 'DisplayMasker'）；反向验证＝maskDisplay 摘除→89 例 7 红精确落四链「脱敏开」用例，复原复绿。实施勘误一处：IN 列表同列多值全打码（hint 按 IN 前列名继承）对齐采集侧「该列全部参数值打码」语义，测试期望据此修正。插曲：DisplayMasker.kt 首版 Write 引号转义被吃（`"\'`）致语法红，经探针测试定位后改 `SQL_QUOTE` 常量规避引号字面量。
- 2026-10-07 21:1x **R37 落地（提交 1cb2a24）**：AiContextExport（纯 Kotlin）+预览面板「更多」菜单「复制为 AI 上下文」动作（copyAsAiContext：currentEditorSql+方言→块→剪贴板→toast）。AiContextExportTest 5/0（红＝Unresolved reference 'AiContextExport'——测试先落盘、实现后写，红读数取自实现落盘前的编译轮）。与 R60 提交拆分：SqlPreviewPanel 的 R60 hunk（可移植导出脱敏）随 R60 笔、R37 hunk（动作）随 R37 笔（中间态手工摘复原，两笔各自可编译）。
- 2026-10-07 21:2x **R62 落地（提交 5e47d6a）**：UpdateApi.FetchOutcome 三分支（HttpError 经 HttpStatusException.getStatusCode() 分层，fetchManifest 保留兼容视图）＋PluginUpdateRunner.FailureKind 四枚举（404/410→CHANNEL_MISSING，其余 HTTP→NETWORK 带状态码）＋UpdateSource.MARKETPLACE_URL 新常量＋UpdateSection 三态文案与兜底链接行（仅 CHANNEL_MISSING 可见）。定向读数：UpdateCheckDiagnosisTest 8/0、PluginUpdateRunnerTest 14/0、UpdateSectionTest 17/0、StartupUpdateCheckTest 7/0、UpdateSource/UpdateManifest 回归 2/0+6/0。反向验证＝404 判别分支摘除→「通道404_通道缺失态」精确复红（25 例 1 红），复原复绿。启动检查静默语义未动（既有定案）。
- 2026-10-07 21:3x **收口**：全量定向复跑（脱敏/导出/MCP/更新/设置五族 15 类）＋SettingsRealityGuardTest/McpConfigurableGateTest/PluginSettingsPanelBindingTest/SettingsIOTest/PluginSettingsPersistenceTest 设置族回归——新 State 字段两枚（displayMaskEnabled 消费在 log 包、mcpMaskEnabled 消费在 mcp 包，断言一「写而不读」不触发）；push t-laneb 留底，不并 dev。
- 实施与计划的偏差登记：①R62 的「三态」按 V121.3 落为「失败二分（网络/通道404）+已最新」合计三态，与 §二 定义一致；②`settings.update.checkFailed` 旧键保留（MANIFEST_MALFORMED/VERSION_UNREADABLE 两辅助态沿用），仅 NETWORK/CHANNEL_MISSING 走新键——安装失败（installFailed）文案路径未动。
