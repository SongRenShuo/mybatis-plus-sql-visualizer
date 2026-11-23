# T-PluginDescReshape：插件描述结构重排与声明对齐——开发计划

> 编号占用：O-126 / R72 / D113~D117 / V137~V140（20261008 15:01 全仓 grep 实测：O 最高 O-125、R 最高 R71、D 最高 D112、V 最高 V136——均被在途 T-ErrReportSubmitNoOp 计划占用；本组各号在 `docs/` 与 `src/` 全 0 命中）
> 来源：用户指令 2026-10-08 ——「src/main/resources/META-INF/plugin.xml 的插件描述 description 有些乱，我希望可以进行优化。你先分析需求生成开发计划文件」；同日追加「也可以参考下这个插件的精华，需求池|中文文档，并把中文放在上面 `https://plugins.jetbrains.com/plugin/9837-mybatiscodehelperpro`」
> 前序：`20260818-2125-...发行说明.md:34`（上一次描述优化：英文段改综合描述、中文段精简为 7 条、保留 ≥40 字符拉丁开头约束）；T-McpToolset / T-McpToolset2（描述里「10 个工具」的来源）；T-StackHoverUi（`562833d`）、T-RecentExecDialog（`374db88`）（描述最后两条 bullet 的来源）；T-RollbackSql（描述里回滚一条的来源）
> 状态：**已实施**（2026-10-08，用户令「按照推荐 先全部修改下 我自己看看效果」；实施记录与读数见 §八）

## 一、现状与实测证据

### 1.1 结构现状（`src/main/resources/META-INF/plugin.xml:25-66`）

一份 CDATA 描述承载两种语言，英文在上、中文在下，中间用 `<hr/>` 分隔：

| 段 | 英文块 | 中文块 |
|---|---|---|
| 开篇 | `<p><b>See the exact SQL your code will run — before the server starts.</b> …` | `<p><b>服务未启，SQL 先知</b>——…` |
| 兼容性 | `Compatible with every IntelliJ platform release since 2024.3 — including the full 2025.1–2025.3 line — continuously adapted.` | `支持 IDEA 2024.3 起全部版本（含 2025.1–2025.3 与 2026.x），持续适配。` |
| 小标题 | `<h3>Highlights</h3>` | `<h3>核心能力</h3>` |
| 功能清单 | `<ul>` 12 条 `<li>` | `<ul>` 12 条 `<li>` |
| 结尾 | 官网/Marketplace 链接段 + 反馈段 | 同两段 |

实测字符数：英文块 3331、中文块 1948、合计 **5306**（含缩进与换行）。两个语的 12 条清单逐条对应（编号 1~12 一一对齐），双语本身没有条目错位。

### 1.2 「乱」的五个落点（每条带证据）

**M1 中英之间的分隔线在 Marketplace 上根本不显示。** 实测路径：本地 tag `2026.10.7` 的 `plugin.xml` 描述里含 `<hr/>` 与一条 HTML 注释；Marketplace 插件 API（`GET /api/plugins/33633`）返回的同版本描述里 **`<hr` 出现 0 次**、注释同样被剥掉——两段语言之间只剩一段空白。线上描述原文的衔接处逐字为：

```
Feedback Pool</a>.</p>
        
        <p><b>服务未启，SQL 先知</b>——…
```

即英文段收尾后紧接中文段开篇，没有任何视觉分隔。这是「乱」最直接的一条，且读者（用户看 Marketplace 页）看到的就是它。

**M2 双语兼容性声明不一致，且英文侧用词不可核验。** 英文只说 2025.1–2025.3，漏了现网已支持的 2026.x；中文写了「含 2025.1–2025.3 与 2026.x」。另外 `since-build=243`（`plugin.xml:11`）推导出的是「2024.3 起」，英文的 "every IntelliJ platform release since 2024.3 … continuously adapted" 属于官方 best-practices 明确点名禁止的「unverifiable claims」（原文见 1.4）。

**M3 功能清单是追加式的，没有分组。** 12 条平铺：最后两条（Call stack hover、Recent executions）是上一轮功能（v2026.10.9）直接补在末尾的——`1e1acf6` 的 diff 证实这两条是追加笔；「触发零门槛（gutter 图标 / 右键 / Alt+Shift+Q）」作为第 12 条混在功能清单里，而它其实是入口说明；JOIN 一条里塞了三件事（ER 图 + 内嵌控制台执行 SQL + EXPLAIN）。

**M4 关键信息缺位，且对外三份清单各行其是。** 描述里没有「要求 / 安装」行——IDEA 版本只以兼容性口号出现，哪些能力需要 Ultimate 只在内文括号里出现两次（`(Ultimate)` / `（Ultimate）`），MCP 需 2025.2+ 只在一条 bullet 尾部括号里出现一次。同时描述未提已在 README、Marketplace 自定义页里列出的多项能力：Kotlin 链支持、多租户/逻辑删除/乐观锁拦截器模拟、SQL 补全、日志屏蔽与历史落盘、`${}` 注入确认与无界写闸门、执行超时护栏、Specification / Querydsl / EasyQuery / Xbatis / MDS 链。结果是同一件事（功能清单）在三处手写、长期各自漂移——本轮就抓到一例：描述、`mcp-optional.xml` 头注与 `CHANGELOG.md:88`/`:138` 都写「MCP 需 2025.2+」，而 `plugin.xml:94` 的注释写「253+/262 内置」，同一事实仓内两说（见 §四 P2）。

**M5 预览卡截断在半句话处。** Marketplace API 返回的 `preview` 字段实测为：

```
See the exact SQL your code will run — before the server starts. SQL Visualizer turns MyBatis-Plus Wrapper chains, XML mappers, annotation SQL, JPA/Hibernate queries...
```

官方要求「描述的前 40 个字符必须是英文且含一句短摘要」（1.4），而首 40 字符实测为 `See the exact SQL your code will run — b`，断在 `before` 一词内；`preview` 整体（168 字符）也断在 `queries...` 这半句处。

### 1.3 声明与代码的一致性核对（本次逐条实测）

数字类与行为类声明**目前全部为真**，可直接作为守卫基线：

| 描述里的声明 | 代码事实 | 位置 |
|---|---|---|
| 11 database dialects / 11 种数据库方言 | `DialectRegistry.dialects` 11 项（MySQL / PostgreSQL / Oracle / SQL Server / H2 / SQLite / MariaDB / DM / KingBase / OceanBase / openGauss） | `src/main/kotlin/.../sql/Dialect.kt:781` |
| 10 deterministic tools / 10 个确定性只读工具 | `SqlVisualizer*Tool.kt` 10 个类，`@McpTool` 名 10 个（render_sql / render_logs / check_sql / explain / rollback_plan / crud_sql / reverse_sql / mapper_check / perf_advice / schema_context） | `src/main/kotlin/.../mcp/` |
| Enhanced capture, on by default / 增强捕获（默认开启） | `enhancedCaptureEnabled: Boolean = true`（另有 `enhancedCaptureConfirmed = false` 的首用确认闸门，语义是「默认开、首次使用需确认」，描述不写确认流程不算失实，但可在文案上更精确） | `src/main/kotlin/.../config/PluginSettings.kt:475`、`:482` |
| one keystroke away: Alt+Shift+Q | `ConvertToSql` 默认键 `alt shift Q` | `plugin.xml:370` |
| Rollback SQL … identity-column wrapping for SQL Server and DM | `identityInsertWrapper` 形态在 `IdentityInsertStatements.kt`；`SET IDENTITY_INSERT <表> ON/OFF` 两方言逐字相同 | `src/main/kotlin/.../sql/IdentityInsertStatements.kt:6-7` |
| snapshot-assisted recovery for UPDATE / DELETE | `RollbackSnapshotStore` + `RollbackSqlDialog` 回滚视图在位 | `src/main/kotlin/.../sql/rollback/RollbackSnapshotStore.kt:28`、`ui/RollbackSqlDialog.kt:31` |
| Compatible with … since 2024.3 | `since-build=243`，无 `until-build`；verifyGate 三目标 243/253/262 | `plugin.xml:11` |

### 1.4 平台规则（本次新查证，来源：官方 best-practices-for-listing 页，2026-10-08 抓取）

- 描述「支持大多数 HTML 标签」，同时显示在 IDE 的 Plugins 设置页与 Marketplace 插件页；
- **前 40 个字符必须是英文且含一句短摘要**，用于 Marketplace 预览卡，且「预览卡文案不能单独修改」；
- 描述可以在插件页单独编辑（与插件包分开保存），编辑时平台让你二选一：「始终使用页面描述」或「页面描述用到下次上传为止」；若希望包内描述继续生效，就不要选第一个；
- DO：英文优先、拼写与语法正确、直白措辞、避免 `simple` / `lightweight` / `professional` 这类营销形容词；建议用 bullet 列主要收益、内联放 issue tracker/forum 链接（这样在 IDE 插件页里也能点）；
- DON'T：引用无关联的品牌、**做无法验证的声明**；
- 图片建议放 Media 区而不是内嵌（内嵌图不能放大；本仓另有 Gitee 302 防盗链坑，已有守卫禁 `<img>`）。

补充实测两条（本轮新得）：描述里的 **HTML 注释会被剥离**；**`<hr/>` 会被剥离**。现有守卫 `PluginXmlStructureGuardTest.kt:73` 只守了 `<img>` 一条，不管 hr/注释。

### 1.5 现有测试覆盖

`PluginXmlStructureGuardTest`（`src/test/kotlin/.../config/`）已有 `description不得内嵌外链img` 一条规则，其余规则都是注册形态类。描述与代码的数字类一致性、双语同构、首 40 字符格式，目前**零覆盖**。

### 1.6 参考件拆解：MyBatisCodeHelperPro（用户 2026-10-08 追加指令点名的插件）

`GET /api/plugins/9837`（143.5 万下载，在售）描述全长 4815 字符，结构逐段如下：

```
<p>MyBatisCodeHelperPro plugin for java mybatis framework, provide auto completion inspection, code generation, make mybatis easy to use</p>
<p>最好的Mybatis代码提示,代码检测插件,快速生成代码,测试sql,日志转sql等</p>
<p><a href="…/issues">Issues</a> |<a href="…bilibili…">BiliBili</a> |<a href="…/doc">中文文档</a></p>
<h3>中文:</h3>
<ul>（25 条中文功能，用户语言写收益，如「最好的ognl支持」「可以写出安全的sql」「兼容kotlin」）</ul>
<h3>Features:</h3>
<ul>（英文清单，条目与中文大致同构，但存在格式松散：混入 <p> 与未包 <li> 的裸文本行）</ul>
```

其 `preview` 字段（预览卡）实测为英文：「MyBatisCodeHelperPro plugin for java mybatis framework, provide auto completion inspection…」——即**它同时做到了「顶部英文摘要」与「中文正文在上」**。

可借鉴的四点（本方案吸收）：

1. **顶部两行摘要**：第一行英文一句话（满足预览卡首 40 字符英文的硬约束），第二行中文一句话（中文读者第一眼看到中文）；
2. **链接行独立成行平铺**：`Issues（需求池）| 视频 | 中文文档`，一条 `<p>` 内三个内联链接——按官方指引，内联链接在 IDE 插件页同样可点；
3. **中文正文在上、英文正文在下**，以 `<h3>` 分节（它用 `<h3>中文:</h3>` / `<h3>Features:</h3>`），中文条目不堆术语；
4. 清单条目多而短（中文 25 条），说明长度本身不是问题——但本仓已有 README / 自定义页承载全量矩阵，描述仍按 §三 S1 的五组收敛，不追它的条数。

不照抄的两点：它英文清单的格式松散（裸文本行、`<p>` 混排）不学；它的「最好的…」式最高级措辞与官方 best-practices 的「避免无法验证的声明」相抵，本仓沿用可核验句式。

## 二、目标与红线

目标：让描述在「包内（IDE 插件页 / 下次上传）」与「线上页面」两处呈现一致、可扫读、可核验，并把「描述里的数字与行为声明」钉进守卫，防止后续加方言/加工具/改键位时描述静默过期。

红线（不可动）：

- 不改插件 `id` / `name` / 图标 / 各 optional 配置文件（描述是本任务唯一的生产改动面）；
- 不动 change-notes 注入链（`buildSrc/.../mpv-release.gradle.kts:16-27` 的 `patchPluginXml.changeNotes` 来自 `gradle/plugin-change-notes.html`，与 description 无关，别顺手碰）；
- 不新增外链图片；不引用无关联品牌；不写无法核验的措辞（对应 M2）；
- 功能声明不得超出代码实际；总长目标 ≤ 现长 5306（只重排不加料时自然满足，见 §四 P3）；
- 「中文正文在上」是对官方 best-practices「英文优先」建议的**有意偏离**（用户 2026-10-08 指令）：硬约束（前 40 字符英文短摘要）由置顶的英文摘要行兜住，正文语言顺序不再受该建议约束；此偏离的依据与在售先例见 §1.6。

## 三、方案

### S1（D113）整体布局：英文摘要行置顶 + 中文正文在上 + 分组同构

目标形态（自上而下，取代现状的「英文块 + `<hr/>` + 中文块」）：

| # | 段 | 语言 | 内容 |
|---|---|---|---|
| 1 | 摘要行一 | 英文 | 首 40 字符自成语义的短句（S2 定稿）+ 一句补充，整段控制在 160 字符内，供预览卡截断 |
| 2 | 摘要行二 | 中文 | 「服务未启，SQL 先知」+ 一句话说清是什么（与官网、README 同源） |
| 3 | 链接行 | 双语标签 | 中文文档 · English Docs · 需求池 · Marketplace 截图页（D117） |
| 4 | 正文一 | 中文 | `<h3>中文</h3>` + 五组清单 + 要求行 |
| 5 | 正文二 | 英文 | `<h3>English</h3>` + 同构五组清单 + Requirements |

「中文在上」与平台约束的关系要说清：官方 best-practices 的建议是「英文优先」，但**硬约束只有「前 40 字符必须是英文且为一句短摘要」**（预览卡用，不可单独修改）。参考件（1.6）已证明「英文摘要行置顶 + 中文正文在上」的形态可在售过审，本方案取其形：第 1 段承担硬约束，中文内容整体位于英文正文之上。原 `<hr/>` 分隔随之取消，不必找替代分隔符——`<h3>` 分节标题本身即分界；**禁止再用 `<hr/>` 与 HTML 注释**（M1 实测被剥）。

**开篇要求行**并入各自正文（中文块写在清单末、英文块写 `<p>Requirements…</p>`），三条硬信息一次说清：支持 IDEA 2024.3–2026.x；执行 / EXPLAIN / JOIN 原生图等能力需 Ultimate（社区版自动降级）；MCP 工具需 IDEA 2025.2+（待 P2 定论）。

**功能清单按「编码期 → 运行期 → 生成 → 执行与分析 → AI」五组重排**（组名双语：编码期预览 / Preview before you run、运行期捕获 / Capture at runtime、生成与转换 / Generate & convert、执行与分析 / Execute & analyze、面向 AI / For AI agents），每组 2~3 条，组内按重要性、不按追加时间，中英两组条目一一同构。现行 12 条的处置映射：

| 现行条目 | 目标组 | 处置 |
|---|---|---|
| 1 All SQL sources covered | A 编码期预览 | 保留，补 Specification / Querydsl / EasyQuery / Xbatis / MDS 与 Kotlin 一句 |
| 2 Real parameters, one click | A 编码期预览 | 保留，补多租户 / 逻辑删除 / 乐观锁拦截器模拟一句 |
| 3 Live SQL log capture | B 运行期捕获 | 保留，补「屏蔽规则 + 历史落盘」半句 |
| 4 Enhanced capture, on by default | B 运行期捕获 | 保留（措辞精确化见 S3） |
| 5 SQL → Java | C 生成与转换 | 保留 |
| 6 JOIN diagram & execution | D 执行与分析 | 拆两条：JOIN 关系图（含导出）；执行 SQL 与 EXPLAIN（Ultimate） |
| 7 CRUD SQL in one step | C 生成与转换 | 保留 |
| 8 Rollback SQL in one click | C 生成与转换 | 保留 |
| 9 MCP tools for AI agents | E 面向 AI | 保留，版本口径随 P2 |
| 10 Call stack hover | D 执行与分析 | 移入 D 组（不再吊在末尾） |
| 11 Recent executions | D 执行与分析 | 移入 D 组 |
| 12 One keystroke away（入口） | 正文内独立一行 | 移出清单，写成每侧正文里的一行入口说明（链接统一归 D117 链接行） |

新增（不展开、各一句，取自 README/自定义页已有能力）：写安全三件套（`${}` 注入确认 / 无界写发现 / 执行超时护栏）、SQL 代码补全（表名 / 列名 / `#{}` / 动态标签）、i18n（界面语言独立于 IDE）。

**入口行**：gutter 图标 / 右键菜单 / `Alt+Shift+Q` 三条入口写成一行 `<p>`，放各自正文的清单之前（倒数第二段位置），不再作为清单条目。链接统一归 D117 的链接行，正文里不重复。

**末段**：中文块以要求行收尾，英文块以 `Requirements` 收尾；不再各写一遍官网链接与反馈链接（现状的双段重复）。

### S1a（D117）链接行：标签与目标

目标（20261008 全部实测 HTTP 200）：

| 标签 | 目标 |
|---|---|
| 中文文档 | `https://sql-visualizer.jvtools.cn/`（VitePress 中文站，导航含 /guide /features） |
| English Docs | `https://sql-visualizer.jvtools.cn/en/`（英文站，实测在位） |
| 需求池 | `https://sql-visualizer.jvtools.cn/about/feedback`（现描述里已用） |
| Marketplace | `https://plugins.jetbrains.com/plugin/33633-mybatis-sql-visualizer`（截图页） |

形态：一条 `<p>` 内四个内联 `<a>`，`·` 分隔，**标签双语并列**（参考件的 `Issues \| BiliBili \| 中文文档` 同形；本仓站点本身分中英两入口，双语标签让任一语言读者都能找到自己那侧）。不再像现状那样在两种语言里各写一遍链接段与反馈段。

### S2（D114）首 40 字符与预览卡

首句必须 ≤40 字符且自成一个完整主张，候选：

- A（推荐）：`See the exact SQL your code will run.`（37 字符，完整句，且是现有首句的第一分段）
- B：`Executable SQL from your code, before it runs.`（46 字符，前 40 字符断在 `before it` 处，仍不完整，不推荐）
- C：保留现有 `See the exact SQL your code will run — before the server starts.`（64 字符，前 40 字符断在 `before` 一词内）

推荐 A：第一句 37 字符之后换句，第二句承载「把 Wrapper / XML / 注解 / JPA / JdbcTemplate 渲染成可执行 SQL、参数自动回填」的说明，使 `preview` 字段（实测 168 字符，含尾部省略号）的截断点落在第二句的语义完整处。中文块首句沿用既有 slogan「服务未启，SQL 先知」，不动——它与官网、README 同源，已在用户侧形成识别。

### S3 声明对齐与补漏

- 兼容性双语同句：去「every release / continuously adapted」式不可核验措辞，改为「支持 2024.3 及之后全部版本（现行基线 2026.x）；已在 2024.3 / 2025.3 / 2026.2 三版本通过官方 Verifier 验证」——后半句即 verifyGate 现行三目标（`mpv-verification.gradle.kts:274`），可核验；
- MCP 版本口径：随 §四 P2 定论；未定论前不写具体版本号，改写「需平台 MCP Server（近版 IDE 内置）」；
- Ultimate 标注集中到开篇要求行，清单内不再逐条括号 `(Ultimate)`；
- 增强捕获一条精确化：「默认开启（首次运行前一次性确认）」——对齐 `enhancedCaptureEnabled=true` + `enhancedCaptureConfirmed=false` 的真实语义；
- 详略口径（防三处漂移，本次只定约定不改另两处）：描述 = 卖点摘要 + 入口（不追求全量细目）；Marketplace 自定义页 = 完整功能矩阵；README = 开发/贡献向。以后新增能力只按此分工落，不再三处同时补。

### S4（D115）守卫：把「描述 ↔ 代码」钉死

在 `PluginXmlStructureGuardTest` 增补（推荐；备选是新建 `PluginDescriptionGuardTest`，D115 定形态）。规则清单：

1. 描述内不得出现 `<hr`（对应 M1 的实测剥离；加回必红）；
2. 描述内不得出现 `<img`（既有规则保留）；
3. 剥标签后的前 40 字符须全为 ASCII 可打印拉丁（对应官方预览卡硬要求，防中文/标签开头）；
4. 数字与行为声明对账：`11 database dialects` 与 `11 种数据库方言` 两侧条数 == `DialectRegistry.dialects.size`；`10 deterministic` 与 `10 个确定性` == `@McpTool` 名计数；描述里出现的 `Alt+Shift+Q` == `ConvertToSql` 默认键；`2024.3` 与 `since-build=243` 对应；
5. 双语同构：EN 与 ZH 的 `<li>` 条数相等、`<h3>` 数相等（防单边改一条）。

反向验证（本仓「摘掉修复必红」定例）：① 把 `<hr/>` 加回描述 → 规则 1 必红；② 方言数声明改成 12 → 规则 4 必红；③ 只删中文一条 bullet → 规则 5 必红。三条各贴一次红读数。

### S5（D116）线上同步通道与时机

实测（`GET /api/plugins/33633/updates`）：Marketplace 最新已上架版本是 **2026.10.7**，`v2026.10.9` 尚未上传；因此线上描述当前等于 10.7 包内描述（含被剥 hr 的结果），且 `preserveUIEdits=false`（包内描述会覆盖页面）。两条路：

- 路 A（推荐）：文案定稿后随 `v2026.10.9` 上传一起生效，零额外操作；本次改动只落 `plugin.xml`；
- 路 B：不等上传，直接在 Marketplace 后台 General Information 粘贴新描述（可立即修掉线上中英粘连）。代价是平台会问「始终用页面描述 / 用到下次上传」——选后者才是本仓要的语义（页面临时、包内权威），且必须把这条操作写进 `docs/发行说明/Marketplace发布流程-说明文档.md`，否则下次上传后页面行为会与预期不符。

推荐 A + 把「描述改动随下次发行生效」写进发布流程文档（一句话），避免以后有人以为改 `plugin.xml` 会立刻上线。

## 四、待验证前提

- **P1**：`<hr/>` 与 HTML 注释被 Marketplace 剥离，是**平台规则**而非 10.7 那次上传的偶发——现有证据是「本地有 / 线上无」的单点对比。新方案已不用 hr，故风险低；但若 P1 被证伪（其实是我方打包环节剥的），需回头查 `patchPluginXml` 是否有清洗。
- **P2**：MCP 能力所需 IDE 版本仓内两说：描述与 `mcp-optional.xml` 头注、`CHANGELOG.md:88`/`:138` 写「2025.2+」，`plugin.xml:94` 的注释写「253+/262 内置」。动手前先定论（官方 MCP Server 插件从哪版开始捆绑）；定不下来就走 S3 的模糊表述。
- **P3**：描述长度上限官方文档未见明文（现长 5306、线上 4696 均已被接收）。新文案按 ≤5306 控制；若上传时被拒或被截，先按 5000 收口再复验。
- **P4**：页面编辑通道的两种模式语义（「始终用页面描述」/「用到下次上传」）来自官方文档文字，本仓未实操；路 B 若被选中，先在页面确认选项措辞再落盘，并按实操修正对 `preserveUIEdits` 的解读。
- **P5**：IDE 插件页与 Marketplace 页共用同一份 HTML，但两处渲染器未必一致（`<h3>` 在 IDE 侧观感未走查）。V137 用 runIde 沙盒确认；若 IDE 侧观感差，分组改粗体段落开头。
- **P6**：「中文正文在上」不触碰平台硬约束（硬约束只在前 40 字符），据参考件在售形态推断；若上传时审核对语言顺序提异议，退路是把英文摘要行扩写为三行英文摘要（仍在最顶），中文正文位置不动。
- **P7**：站点中英双入口内容同源（`/` 与 `/en/` 均为 VitePress 构建，20261008 双 200）——描述链接行给两语读者各一个入口；若某侧内容滞后，属站点侧问题，不在本任务范围（本任务只登记链接目标）。

## 五、验证方案

- **V137**：runIde 沙盒走查——设置 → Plugins → SQL Visualizer 详情页，确认分组小标题、列表、链接、中英分隔、首段截断观感（含 P5）。
- **V138**：发行后线上核对——上传后 `GET /api/plugins/33633` 取描述与 `preview`：中英分隔可见、无 `<hr`、无注释、`preview` 首句完整、双语条目数相等、中文正文在英文正文之上。
- **V139**：守卫反向验证三条必红读数（S4 的 ①②③）。
- **V140**：链接行可达性核对——四个外链逐一 `curl -o /dev/null -w %{http_code}` 取 200（20261008 已预核一次：`/`、`/en/`、`/about/feedback`、Marketplace 页全部 200），改动落盘后复跑一次防手误。平台侧另有硬约束「预览卡取前 40 字符」，由守卫规则 3 覆盖。
- 定向测试：`PluginXmlStructureGuardTest`（或新类）随改动即跑；纯 HTML/文本改动不触达 DB / agent 链，收口按「定向 + testFast 冒烟」档，不跑全量（本任务不是大改动：不动 `build.gradle.kts` / 依赖 / EP / 平台 API）。

## 六、改动清单（预估）

| 文件 | 改动 | 备注 |
|---|---|---|
| `src/main/resources/META-INF/plugin.xml` | 重写 `<description>` CDATA（`:25-66`） | 本次唯一生产改动 |
| `src/test/kotlin/.../config/PluginXmlStructureGuardTest.kt` | 增补 5 条规则（或新建 `PluginDescriptionGuardTest.kt`） | D115 定形态 |
| `docs/发行说明/Marketplace发布流程-说明文档.md` | 补「描述同步通道与时序」一小节（S5 + 1.4 的剥离实测） | 建议随本任务落 |
| `docs/变更记录.md` | 实施后落一行 | 收口惯例 |
| `docs/MEMORY.md` | 计划登记（文档检索索引） | 收口惯例 |

不动：`README.md`、`docs/发行说明/marketplace-custom-page.{md,html}`（只登记分工约定，不改内容）、`gradle/plugin-change-notes.html`、各 optional 配置文件。

## 七、不做项（登记备选）

- 描述内嵌截图（官方建议走 Media 区，且本仓有 302 防盗链坑，现有守卫禁 `<img>`）；
- 描述 i18n 资源化（双语直接写在 CDATA 里是现状，改成 bundle 反而失去 Marketplace 单字段承载双语的能力）；
- 自定义页 / README 的清单同步删改（本轮只定分工）；
- 给「插件页描述」做自动化抓取巡检（属发布流程自动化，另立）。

## 八、实施记录（2026-10-08 已实施）

### 8.1 落盘内容

| 文件 | 改动 |
|---|---|
| `src/main/resources/META-INF/plugin.xml` | `<description>` 整体重写（`:24-52` 注释块同步换代，写明五条结构约定与实测依据） |
| `src/test/kotlin/.../config/PluginXmlStructureGuardTest.kt` | 新增 4 条描述守卫 + 私有件 `rawDescription()` / `assertDeclaredNumber()` / `actionBlock()`；类 KDoc 规则清单同步 |
| `docs/发行说明/Marketplace发布流程-说明文档.md` | 2.1 表 `<description>` 行换代 + 新增 §8.1「描述的同步通道与时序」（含平台剥离清单表） |
| `docs/变更记录.md` / `docs/MEMORY.md` | 本任务行 + 计划索引行 |

最终形态与计划的两处实施偏差（都属计划内可选口径、已按「不超长」红线取舍）：

1. 组名不再单占一行 `<p><b>组名</b></p>`，改为并入每组**首条** bullet 的粗体引导（`编码期预览 · SQL 来源全覆盖`）；理由：首版带组标题段落时总长 6464 超红线，去掉 5×2 个组标题段落、组名并入首条后回到预算内，且分组可见性不变。
2. bullet 数由映射表的 12 条收为 **10 条**（A 组的「SQL 代码补全」并入 A1 尾句；D 组的「JOIN 关系图」并入 D1）。两侧一致，双语同构守卫按 10 == 10 钉住。

### 8.2 读数

- **定向**：`./gradlew test --tests "…PluginXmlStructureGuardTest"` = **22 tests / 0 failures / 0 errors / 0 skipped**（含四条新规则：`描述不得含hr分隔线与HTML注释`、`描述首40字符须为英文摘要`、`描述声明与代码对账`、`描述双语同构且中文在前`）。
- **反向验证（三条变异各一次红读数，跑完逐次还原再跑恢复态）**：
  - 变异①「把 `<hr/>` 插回 `<h3>中文</h3>` 之前」→ FAILED，点名 **描述不得含hr分隔线与HTML注释**（1 红）；
  - 变异②「`11 种数据库方言`/`11 database dialects` 改成 12」→ FAILED，点名 **描述声明与代码对账**（1 红）；
  - 变异③「删掉中文节末条 `面向 AI · MCP 工具`」→ FAILED **描述双语同构且中文在前** + **描述声明与代码对账**（2 红，后者因该条承载「10 个确定性」声明，属预期连带）；
  - 恢复备份后复跑 → **BUILD SUCCESSFUL**（22/0/0），并校验恢复文件与变异前备份 sha256 一致。
- **静态核对**（改后逐项）：描述总长 5298（旧 5306）；`<hr`/`<img`/`<!--` 各 0；中英 `<li>` 各 10；首 40 字符 `See the exact SQL your code will run. Re` 全 ASCII；`11 database dialects`/`11 种数据库方言`/`10 deterministic`/`10 个确定性`/`Alt+Shift+Q`/`2024.3` 各在场；XML well-formed（`xml.dom.minidom` 解析通过）；链接行四目标 curl 全 200。
- **未跑全量**：本任务改动面只有描述文本与守卫测试，不触达 `build.gradle.kts` / 依赖 / EP / 平台 API，按测试三档分层属「定向」档，不跑 `testShards`。

### 8.3 遗留

- **V137 runIde 渲染走查**：IDE 插件页对本描述的渲染（`<h3>` 分节观感、`<code>` 与实体 `&middot;`/`&mdash;` 显示、链接可点）待沙盒走查；计划 §四 P5 的兜底（若观感差改粗体段落分组）随该次走查定。
- **P2 MCP 版本口径**：描述暂用「平台 MCP Server（近版 IDE 内置）」规避仓内两说（`plugin.xml:94` 注释「253+/262 内置」vs `mcp-optional.xml` 头注 + `CHANGELOG` 的「2025.2+」）；定论后回填具体版本号。
- **V138 发行后线上核对**：随 `v2026.10.9`（或其后首个版本）上传后取 `GET /api/plugins/33633` 复核（无 `<hr`、`preview` 首句完整、中文节在英文节之上）。
- 本任务改动**未提交**，留给用户先看效果（用户原话「我自己看看效果」）；另生成渲染预览件 `build/desc-preview.html`（gitignored，含预览卡文案与全文渲染两栏）。
