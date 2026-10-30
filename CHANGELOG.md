<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# SQL Visualizer Changelog

## [Unreleased]

### Fixed
- **方言为 GaussDB / 达梦 / 瀚高 / 海量时预览恒降级、表列补全不可用（T-PreviewPsiDialect，用户真机报障）**：预览顶部常驻黄色横幅「预览以简化模式打开——临时文件解析失败；表/列补全暂不可用」，10 次预览 10 次降级。真机日志实证根因是 `ClassCastException: SqlLanguage cannot be cast to SqlLanguageDialect`（当天 9 次，3 次打成 SEVERE 且点名本插件），栈落在 `SqlParserDefinitionBase.createFile:90` ← `SingleRootFileViewProvider.getPsiInner` ← `FileManagerImpl.findFile` ← 预览预热的 `awaitPreviewPsiLanguage`。**成因**：四个自注册方言（DM / GaussDB / HighGo / Vastbase）此前只注册了 `<sql.dialect>`，没有为该语言注册 `<lang.parserDefinition>`——平台查 ParserDefinition 时沿语言链上溯，落到基语言 `SQL` 的那份（其 file node type 绑的是基类 `SqlLanguage`），`createFile` 里的 `(SqlLanguageDialect) getFileNodeType().getLanguage()` 强转必然失败，PSI 建不出来，`PsiManager.findFile` 返回 null，预热按 `PSI_UNAVAILABLE` 降级。平台内置方言 SQL / GenericSQL / SQL92 全是成对注册的，我们这四对少了后一半。**修复**：①新增 `sql/SqlFamilyParserDefinitions.kt`，为四个方言各补一个 ParserDefinition（file node type 绑本方言语言，lexer / parser / elementFactory 委托 `Sql92ParserDefinition`，与方言 `createTokensHelper` 的委托口径一致），注册面在 `database-optional.xml` 成对补齐；②`mapPreviewDialectBeforePsi` 增加映射前探测——目标语言的 ParserDefinition 若不在 SQL 方言族，这次就不映射（补全退 GenericSQL），不再拖垮整条预览；③`PsiManager.findFile` 两处调用包 `runCatching`，平台契约异常降为一条带栈 WARN，不再以 SEVERE 记到插件头上。**守卫**：`SqlDialectParserDefinitionPairingGuardTest` 静态校验「每个 `<sql.dialect>` 必有同名 `lang.parserDefinition`」，`PlatformSqlDialectRegistrationTest` 新增 DB 通道用例真建 PSI（摘掉任一条注册即红）

## [2026.10.31-beta.1] - 2026-10-31

> 内测版（Gitee 抢先体验通道）。范围＝dev 分支 59 笔合流 + master 侧渲染性能一笔；方言总数 11 → 14。

### Added
- **GaussDB 独立方言（T-GaussDbDialect）**：此前 `jdbc:gaussdb://` 被并进 `OpenGaussDialect`（一 object 两前缀），连接能认、SQL 能渲染、EXPLAIN 能出，但设置页下拉、日志库角标、MCP 方言名一律显示 `openGauss`——用户看不到 GaussDB。现拆出 `GaussDbDialect`：渲染形态与 openGauss 同形不同名（六项能力不分叉），保留字按 GaussDB 官方口径单独成表。增强捕获同批补登记华为两族驱动 `com.huawei.gaussdb.jdbc` / `com.huawei.opengauss.jdbc`——此前 connect / 语句 / prepare 三面全未登记，attach 后 `matched=0`、零事件零角标且宿主侧无任何报错；顺带改正仓内三处写反的 openGauss 驱动谱系注释（实为 2.x/3.x `org.opengauss.*`、5.x/7.x `org.postgresql.*`），并撤掉据错误谱系作出的「已被 PG 覆盖」失真认领
- **瀚高 HighGo 与海量 Vastbase 两方言（T-XcDialects3）**：保留字分别 153 词 / 201 词，取「官方附录 ∪ PG 基础 ∪ 引擎 `pg_get_keywords()` 实读」并集（只取单一来源会漏类型名与函数名：瀚高比 PG 多 65 词、海量默认 A 模式比 PG 模式多 `excluded` / `online` / `long` 等 14 词）。URL 与方言链五表登记 `jdbc:highgo:` / `jdbc:vastbase:` / `jdbc:dws:iam:` 三子协议与 MCP 中文别名（瀚高 / 翰高 / 海量）；平台 `sql.dialect` 注册 2 → 4（照 DM 先例）；plugin.xml / README / 架构文档 / SqlMapperLanguage 计数 12 → 14（含 GaussDB 轮漏改的陈旧「十一个」）。真机触发两条计划预留切换条款：海量 `renderUpsert` 由 MySQL 形态切 PG 形态（两种均可用，实测取值）、`supportsDeleteLimit` 翻 true（`DELETE … LIMIT 1` 真删 1 行）
- **人大金仓三处缺口补齐（T-XcDialects3 券 01/02/05）**：①保留字补 `enckey`（官方文档标非保留，引擎 `pg_get_keywords()` 标 R、列引用实测 `syntax error at or near "enckey"`，以引擎为准，117 → 118 词）；②PG 协议版驱动坐标 `postgresql-for-kingbase` 排在 `org.postgresql:postgresql` 之前，纠正「金仓被认成 PostgreSQL」；③增强捕获 `WeaveSpecs` 此前对金仓为零，用户「有库角标零 SQL」，现登记语句面 6 类 + prepare 面 3 类 + connect 面 2 新
- **增强捕获按 JDBC 接口匹配兜底，采集面与方言解耦（T-CaptureCatchAll）**：「未命中白名单」不是少一条通道而是**两条都没有**——agent 在线并 ACK 后 `SqlCaptureGate.paused` 置真、控制台桥随即短路，白名单没命中就整链静默。现于 attach 轮用反射对已加载类做 `isAssignableFrom` 匹配（零字节解析），命中 `Statement` / `PreparedStatement` 即按语句面 / prepare 面织入。配套红线设施：`CaptureBudget` 三道闸（扫描类上限 5000、transformer 兜底累计预算 300ms、连败 3 次整体 kill 退回纯白名单，全可经 `sqlvis.agent.*` 覆盖且非法值回落默认）、`NestGate` 线程深度栈让嵌套链只报最内层（Hikari + MyBatis 双层不再双报）、接口对象引用改 `ClassValue`（防热部署钉住旧 classloader）。premain 阶段**不内联未知形态**（premain 织入失败等于宿主 JVM 起不来），只记候选、attach 后 daemon 补挂；transformer 两段式兜底（裸字节预筛 → ASM header）默认关。兜底面刻意不含 `ADD_BATCH` / `CLEAR_BATCH`——那两个出口读原局部，未知驱动 `chop_frame` 会整类 `VerifyError`（达梦 `DmDriver` 先例），为红线主动放弃批处理文本累积
- **增强捕获活性回落与 attach 自检上报（T-CaptureCatchAll 券 02/03/10）**：IDE 侧 `CaptureActivityFallback`——`paused` 满 10 秒 + 窗口内零 agent 事件 + 控制台确实解析出 SQL → 解封控制台通道并披露，带滞回与去重，出厂开；`AgentAttachStats` 把心跳里的命中 / 织入 / 版本闸跳过 / 兜底熔断计数解析进诊断行，不再靠「面板零条目」反推
- **增强捕获补齐 OceanBase / MariaDB / SQLite（T-CaptureCoverage）**：14 方言对账发现 11 全链路 / 2 只有角标 / 1 三面全空。OceanBase 三面全补（`OceanBaseStatement` 一族 37KB 大类字节层 LINK_OK）；MariaDB 语句面 3 类 + prepare 1 类（3.x 起全部重写为自有类，不复用 MySQL `StatementImpl`）、SQLite 语句面 2 类 + prepare 1 类；删 `VENDOR_NOT_COVERED` 里 MariaDB / SQLite 两条失真的「无本机 jar」认领（认领优先于覆盖，留着等于覆盖白做）；帧跳过前缀五份副本补 `com.oceanbase.`

### Fixed
- **PostgreSQL 系保留字缺项（T-GaussDbDialect 券 01）**：`PG_RESERVED` 缺 `is` / `procedure`（PG 官方附录两词均为 reserved），产物在真机上会因合法列名被判语法错；openGauss 原先直接复用 `PG_RESERVED`，比真机 `pg_get_keywords()` 保留类少 17 词（`rownum` / `minus` / `modify` 等），列名 `rownum` 在真机已确认「cannot be used as an alias」。现补 `PG_RESERVED` 缺项、新增 `OPENGAUSS_RESERVED`（183 词）与 `GAUSSDB_RESERVED`（130 词）
- **OceanBase 执行计划档位错判（改真机实证）**：EXPLAIN 原按表格档位解析，真机返回的其实是文本形态，现改按文本档位呈现
- **SQL Server 无排序分页渲染出非法 T-SQL**：`OFFSET … ROWS FETCH NEXT … ROWS ONLY` 在 SQL Server 上必须跟在 `ORDER BY` 之后，而 `SqlGenerator` 只在 `orderBy` 非空时才输出 `ORDER BY`、`limit` 无条件拼接——「无排序 + 分页」的预览产物在真机直接 `Incorrect syntax near '0'.`。现新增 `Dialect.limitRequiresOrderBy` 能力位（仅 SQL Server 覆写 true），无排序且带分页时补语义中性的 `ORDER BY (SELECT NULL)` 占位，位置在分页子句之前。**旧用例为什么没抓到**：`DialectH2VerificationTest` 用 H2 的 `MODE=MSSQLServer` 跑兼容面，H2 不强制这条规则，与 OceanBase 档位误登同型——兼容模式 ≠ 引擎现实。回归用例三条判据（补占位且位于 OFFSET 之前 / 已有排序不补 / MySQL 与 PostgreSQL 不引入 ORDER BY），反向验证短路兜底分支即红
- **premain 补挂线程永不执行（单位量纲混用）**：`msProp` 统一返回纳秒（预算比较需要），但 premain 首轮延迟与轮间隔也走它，随后被直接当毫秒喂 `Thread.sleep`——默认 10 秒变成约 10^10 毫秒（约 317 年），补挂线程从未执行。白名单驱动不受影响（靠类加载期织入），**未知驱动的 premain 形态 100% 静默**，正是本轮兜底要解决的场景。现拆出 `rawMsProp` 只服务两个延迟字段；行为断言永远绿（`resetForTest()` 会把字段覆盖回正确默认值，这正是缺陷潜伏的原因），故另加源码扫描守卫钉住量纲
- **金仓 PG 协议版驱动被误判为 PostgreSQL**：`postgresql-for-kingbase` 坐标此前排在 `org.postgresql:postgresql` 之后，方言推断与增强捕获双双落错族

### Changed
- **日志列表多行模式取源惰性化（T-PerfSweep29 券 01）**：脱敏开时 `displaySqlForList` 内部付 O(SQL 长) 全量词法打码，而缓存命中路径每帧重取源后整段丢弃——改为只在缓存未命中时取源，命中路径零取源
- **新增 14 方言三面对账守卫 `DialectCaptureCoverageTest`**：反衬出既有厂商对齐守卫是**前缀级判定**，挡得住整族静默、挡不住「只补 prepare 面」的半覆盖；MariaDB / SQLite 驱动固化进 agent 测试 classpath，字节层由可选 skip 升为硬断言（`StatementWeaverVendorCoverageTest` 14/14、`DialectCaptureCoverageTest` 3/3，skip=0）
- **三条真机验证链进测试面**：瀚高 / 海量 / 金仓三族驱动进主项目测试 classpath（仅测试面），`DialectDockerVerificationTest` 外部实例模式让插件渲染的 SQL 套件在真引擎真执行（瀚高 153 词 / 海量 201 词 / 金仓 118 词全 PASS）

## [2026.10.30] - 2026-10-30

### Fixed
- **Mapper 双向跳转图标与同类 MyBatis 导航插件叠在同一行（T-MapperNavCoexistYield）**：装了 MyBatisCodeHelperPro 的工程里，mapper XML 的 `select` / `insert` / `update` / `delete` 标签行与 Mapper 接口方法行会出现两个跳转图标——一个是 v2026.10.28 合入的双向跳转，一个是对方自带的跳转箭头。根因是这两个 provider 只接了全局总闸（`showGutterIcon`）与按来源分组开关（`GutterGroups`），**没有任何同类插件共存判定**；而全仓原有的「第三方插件在位即让位」判定在 T-XmlGap W1 随注入区间互补一起废止了（那是注入/着色面的正解——在位 ≠ 覆盖，必须逐区间问），gutter 面的「同一行两个来源各画一个都合规、纪律是让位」这条一直缺实现。现补上：检测到同类 MyBatis 导航插件（当前登记 `com.ccnode.codegenerator`）处于启用状态时，这两个跳转图标不产出；其它 gutter（SQL 预览、注解 SQL、JPA/HQL、JDBC、Wrapper 链等）不受影响

- **方言冲突时预览头部的候选数据源证据丢失（回归，v2026.10.29 引入）**：结果已带上游算好的方言决策时，`withResolvedDialect` 仍要求「渲染方言也在场」才原样返回，于是 `dialectName == null` 的冲突/不可用决策被重算成兜底决策，按需查看的证据面从「候选标识：uuid-a, uuid-b」退化成「无精确绑定」。现改为**以上游决策为准**（决策在场即不重算，仅在决策知道方言名而渲染方言缺席时用该名字回填），候选证据与来源、置信度一并保留
### Added
- **设置项「与同类 MyBatis 导航插件共存时让出 Mapper 跳转图标」（默认开）**：关闭后即使对方在位也强制显示本插件图标；设置页在 gutter 分组开关同一区块，接入 IDE 设置搜索
- 探测走全反射（`PluginManagerCore.getPlugins()` → `PluginDescriptor.getPluginId()` / `isEnabled()`，`PluginId.getIdString()`），不静态引用 `PluginId` / `PluginManagerCore`（该组 API 在 2025.3~2026.2 有形态漂移）；任何反射失败按「不在位」处理并只 WARN 一次——不确定时不删功能。同类插件前缀登记面为列表，将来收更多同类插件只加前缀

## [2026.10.29] - 2026-10-29

### Added
- **方言推断的配置证据按模块收窄（T-DialectInferSmart）**：多模块项目各自连不同库、或测试资源用另一套库时，项目级全量共识会退化成「无共识」、整条推断落回 IDE 方言设置。现配置证据按来源模块收窄（模块内无结论才回落项目级），扫描排除测试资源目录（主库 PostgreSQL + 测试 H2 不再互相挤成无共识），并优先采用激活 profile（`spring.profiles.active` / `include`，支持 properties / YAML 缩进块 / 逗号与方括号列表；`${ENV:default}` 取默认值，`${ENV}` 与 `@maven@` 视为未知）；拿不到激活 profile 才回到全量共识

### Fixed
- **IDEA 2026.1.x 下插件让内置 MCP Server 对所有客户端不可用（T-Mcp261FilterContract，用户报障 P0）**：261 上启用 MCP Server 后，任何客户端 `initialize` 都拿到 HTTP 500（`/stream` 每次失败、`/sse` 建不起会话），`idea.log` 是同一句 `NullPointerException: Parameter specified as non-null is null ... CollectionsKt.addAll`。受影响的不止本插件的 11 个工具——平台 MCP Server 整体不可用，其他插件注册的工具一并消失，关掉插件里的 MCP 总开关也没用，只能卸载插件。根因是平台的工具过滤器接口在 261 上是另一套契约（`getFilters` 返回过滤器清单 + `modify`/`apply` 改写工具集合），而插件的全反射代理是按 2026.2 的 `applyFilters` 写的：261 调 `getFilters` 时落到兜底返回 null，平台紧接着把 null 当迭代器传给 `addAll`。现改为「先探测契约再注册」：`applyFilters` 形态（2026.2 / 2026.3）与 `getFilters` 形态（261.24374 起）各走对应实现，认不出的形态一律不注册；同时给代理的所有未知方法加按返回类型的空值兜底（集合 / Map / Flow / 原始类型绝不返回 null），实现体全程异常降级——即使下一条版本线再改契约，最坏结果是「门控不生效」，不会再让平台的 MCP 服务整体挂掉
- **定时任务等非 Web 线程下增强捕获的调用栈看不到项目代码（T-StackBizFrame）**：`@Scheduled` 一类调度线程产出的 SQL，日志位置列能指到业务文件，但栈摘要与调用栈弹层里一个项目帧都没有。根因是栈底锚点只认插件自有基础设施白名单，不含 `org.springframework.scheduling.*` / `io.micrometer.*`，非 Web 线程的穿透在第一帧就停住，锚点等于栈底，业务帧被挤出 8 帧窗口；且摘要与定位是两条独立判定，没有任何一致性约束。现锚点改三层判定（业务来源复用 `StackLocator` 判据 → 框架前缀穿透 → 字面兜底），源码定位帧成为摘要必含项（落在窗口外时单列一段，省略计数同步扣减）。展示面同步三处：弹层折叠名单补 JDBC 驱动族与 MyBatis logging/binding/reflection/type 等内部包、日志列表 meta 行的栈摘要先取景（头部全内部帧时跳到首个项目帧）再按原 120 字符契约截断、调用栈弹层标题附线程名（`scheduling-1` / `pool-2-thread-1` 一眼可辨）。顺带把业务帧判定从逐帧拼路径字符串改为直接判文件名，Web 形态省约 1.6µs/SQL。真机对拍（`ThreadPoolTaskScheduler` + 两层 `TransactionTemplate` + MyBatis + H2）：旧包摘要零项目帧，新包剩五个项目帧且锚点落在 `@Scheduled` 方法本身
- **预览的智能方言推断被非 SQL 数据源与日志还原链规避（T-DialectInferFix）**：Redis 一类非 SQL 数据源被当成方言候选、产出「未知」后整项目回落到 MySQL；日志/历史双击预览不携带本次执行方言，渲染与 PSI 映射一并落到 MySQL，PG 的合法列名 `signal` 被 MySQL 保留字表判成语法错。现候选只取 SQL 数据源（按平台 query language → DBMS 显示名 → URL 子协议精确比对）；日志/历史预览携带本次执行方言（旧条目按 `db:` 角标回填）；兜底不再硬编码 MySQL，新增 `PreviewDialectResolver` 统一渲染与 PSI 映射口径，PSI 预映射改为继承平台项目/全局 SQL 方言并清理历史 MySQL 映射；无 IDE 数据源时改按「配置 JDBC URL → 构建驱动坐标 → SQL 方言特征」的唯一共识再退一级到项目/全局设置，状态行显式标注来源
- **日志列表暂停态被新 SQL 顶动、关键字换行退化成单行**：手动滚动暂停后新 SQL 到达仍把正在看的内容往上顶（JList 没有滚动锚定，缓冲满载时每条新 SQL 淘汰头部一条、整屏上移一行）；显示模式为「关键字换行」时多数只显示单行（摘要 160 字内到不了 `FROM`，一个换行点都找不到）。现头部淘汰按被删行高上移视口、全量重建按条目身份复原且条目落在后续批次时逐拍重试；换行模式改为取完整还原 SQL 并按 4096 字符预算在空白边界截断；滚底判据抽成纯函数，程序性滚动与「位置变小」都不算用户手势，只有用户主动往底部滚动且到位才恢复跟随

### Changed
- **Mapper 双向跳转 gutter 判定门控与结果记忆（T-PerfSweep28-01）**：`MapperJavaToXmlLineMarkerProvider` 此前对每个打开 Java 文件的每个方法声明在 daemon 慢趟调一次 `MapperXmlLocator.locate`，无接口前置、无负结果缓存、命中也不早退。现加 `containingClass.isInterface` 前置、语句定位结果级 memo（正负都缓存，与文件清单同代失效），两个 Provider 接入总闸与 mapperXml 组闸
- **两处无上限缓存收口（T-PerfSweep28-03）**：`HibernateNamingConfigProbe` 缓存加 512 上限（写入单一守卫，超限整体清空再写）；`SqlPreviewService.activePreviewDialogs` 以 `Project` 强引用为键、项目关闭不摘除，改弱键映射后随 GC 摘除
- **字符串 SQL 方法 gutter 方法体引号预筛（T-PerfSweep28-04）**：`StringSqlMethodGutterLineMarkerProvider` 原在每个候选方法名叶子做一次方法体全子树遍历，改为先扫方法体文本是否含引号（字符串 SQL 必为字符串字面量，零分配单次扫描），精度 100%、子树遍历归零

## [2026.10.28] - 2026-10-28

### Added
- **Mapper 接口方法与 XML 语句双向跳转**：mapper XML 的 `select`/`insert`/`update`/`delete` 标签行与 Mapper 接口方法行各出 gutter 图标，点击互相跳转（XML→接口方法、接口方法→XML 语句）。按 `namespace` + 标签 `id` 解析目标，解析不到不显示图标，不猜同名方法
- **DDL 语句纳入类型着色与筛选（T-DdlTypeColor）**：`CREATE`/`ALTER`/`RENAME` 与 `DROP`/`TRUNCATE` 各成一档 StatementType，可分别勾选筛选（此前 DDL 无类型，勾「只看插入」等筛选时表现含糊）。着色复用增删色相，视觉区分靠**色条形态**——DDL 画虚线、DML 实心，不新增设置项
- **预览面板执行入口方言不一致改为放行（T-DialectGatePass）**：点「执行 SQL」时方言不一致不再弹阻断错误框，改为状态行信息提示、执行继续（与 2026.10.23 定案的「方言不影响最终执行」对齐）；文案前缀由「已阻止执行：」改为「方言不一致：」；重渲染失败等确实无法继续的路径仍走阻断

### Fixed
- **增强捕获把宿主连接换成 JDK 代理，致驱动实现类强转抛 CCE、应用起不来（T-ConnCastSafety，用户 P0）**：达梦工程报 `com.sun.proxy.$Proxy129 cannot be cast to dm.jdbc.driver.DmdbConnection`——agent 在驱动 `connect` 出口把真实连接替换成 `java.lang.reflect.Proxy`，而动态代理只能实现接口、永远无法伪装成驱动实现类，宿主任何 `(DmdbConnection) conn` 形式的强转必然抛 `ClassCastException`，且异常抛在宿主栈上、agent 的自吞拦不住。根因是构造性的，故采集红线升级为「绝不在宿主侧替换 JDBC 对象」：连接替换改为显式 opt-in（`-Dsqlvis.agent.wrapConnection=true`），出厂只登记连接 URL 并原样返回宿主对象；SQL 采集仍走语句面/prepare 面字节码织入（只插回调、不改对象身份，MySQL 家族/PG 与 openGauss/Oracle/SQL Server/H2/达梦全覆盖，attach 对存量池连接同样生效）。**绑定参数一并搬进织入通道**（`setXxx` 入口插桩 → 语句登记槽），连接代理关闭后参数照常可见、脱敏口径不变；同批修正 `execute()` 的 boolean 返回值被误装箱成 Integer 导致影响行数恒空的问题。并撤出达梦 connect 面织入（该出口在达梦上是已知 `VerifyError` 形态，premain 下会让驱动类定义失败、应用起不来；达梦已有语句面+prepare 面覆盖）。代价：语句实现类不在织入名单的驱动（GBASE 8s、部分 SQLite/HSQLDB 形态）默认不再产出事件，需自行打开上述属性并接受强转风险——「不让程序起不来」优先于覆盖面
- **执行控制台与预览绑定数据源不一致（T-ConsoleBind）**：预览头部显示「已绑定 A 库」、点执行却在 B 库的既有控制台上跑（实测现象是 `Unknown column` 一类只在 A 库存在的列报错），对数据源节点 Close All Sessions 也改不回来。根因两层：平台把 exec 文件的「文件→数据源」per-file 关联写死并短路 runner 枚举，插件侧的控制台复用又只按名字不按实际绑定身份。现复用判定升级为身份制（比对数据源 UUID），全链枚举前把 exec 文件的 forced 关联对齐到绑定目标，用户显式换源时清关联让选择器恢复全源列表
- **日志列表头部信息顺序与类名被截断（T-LogListHeader）**：恢复 2026.10.23 的头部顺序与形态——来源回到最后、显示完整文件名（带方括号与 `.java` 后缀，不剥不截），超宽时按顺序截断而非按优先级丢片段；同时修复「自动滚动关掉后无法恢复」
- **时间显示格式严格跟随设置**：此前头部行超宽会剥掉日期只留时刻，同一份配置在宽栏与窄栏显示成两种形态。现完全按设置里的 `logTimestampFormat` 输出，不再按宽度偷偷改格式
- **设置页切到具体方言后状态行不刷新（T-DialectLabelStale）**：把「数据库方言」从智能推断切到 Oracle 等具体方言时，副标题仍停在旧文案，要等面板重绘或 apply 才追上
- **MCP 审计上下文取消残留（T-PerfSweep27-04）**：工具协程被取消且 continuation 永不 resume 时审计条目滞留。改为写入点顺带同步清扫（过期窗 10 分钟 + 上限 64 条按启动时间移最旧），不采用 try/finally 包裹挂起调用的方案（会踩平台 `SpillingKt` 校验红线）
- **项目打开时的平台慢操作断言（T-SqlDialectPrewarm）**：日志工具窗首帧装配在 EDT 上取 SQL 文件类型高亮器，连带触发平台「文件→方言」映射的一次性延迟加载（逐条解析持久化路径），被平台记为 EDT 慢操作并把本插件归为 blame。现日志转 SQL 的结果区改用插件自研 SQL 高亮器（与预览面板/日志预览同一套配色），并把这处首查整体挪到后台线程、在装配面板之前完成（有界等待 300ms，超时照常装配）。功能与布局零变化；结果区配色随自研词法器变化：关键字按固定词表着色（`WITH`/`INTERVAL` 等方言词降为标识符）、表名与表别名独立成色、逗号点号并入运算符色，且「设置 → 编辑器 → 配色方案 → SQL」段的既有自定义不再作用于该区域

### Changed
- **日志头部行渲染缓存（T-PerfSweep27-01）**：头部静态片段（序号/时间/耗时/库/行数/来源）改为条目级缓存，可见格重绘不再重算，动态徽章（去重计数等）仍每次更新；单格预算 2.0ms → 1.0ms，实测约 0.08ms/格
- **实体解析缓存容量上限化（T-PerfSweep27-02）**：`EntityResolver` / `JpaEntityResolver` 两处静态缓存加 4096 上限（超限整体清空再写），关闭「配置签名或宿主锚点变化后旧键永不命中、也永不淘汰」的无上限累积
- **补全与 gutter 的判定前置（T-PerfSweep27-03）**：补全调试日志加开关门控，HqlGutter 的开关判定提到耗时计算之前


## [2026.10.26] - 2026-10-26

### Fixed
- **JAR/lib 模块实体的嵌入列命名宿主级联（T-EmbeddedIdentify）**：实体打包进依赖 JAR 或 lib 模块、该模块有自己的 resources 但没有命名配置时，嵌入列命名探测按实体所在模块收范围会判不出 → 列名丢嵌入前缀（`base_qty` ≠ 真库 `qty_base_qty`）。现按「实体所在模块 → 查询宿主模块」级联判定（HQL/JPQL 链自动生效），两档都判不出仍不回退工程级扫描——多模块聚合根打开时兄弟模块配置外溢的既有防护不变。另增嵌入列解析诊断日志（`[T-EmbeddedIdentify]` 前缀：命名档来源、嵌入列清单、展平降级 WARN）与 JAR 实体全链回归测试（真实模型 JAR 挂库根走 previewCustomDaoHql）。

## [2026.10.23] - 2026-10-23

### Added
- **DatabaseTools 非 UI 实际链修复（V178）**：纠正 DM 方言扩展的 DBMS 注册与单例实例契约、真实数据源配置的反射类包名。DM语言仍为Generic兼容能力，不宣称原生达梦parser。实际无连接平台fixture验证映射、SqlFile、UUID与session/console；显式scope无法证明缩窄时诚实降级，真实连接与GUI流程另验。
- **捕获融合的历史升级（O147）**：修复内存已 MERGED 但自动历史仍为首次低信息快照的问题；本次真实 JDK17/JDK8+MyBatis/JDBC/H2 对照先红后绿。采用有界追加修订注释，不原位覆盖文件，不改原始 SQL/参数/序号；处理融合回调先于存储回调、未知/删除文件、载荷预算和四读取入口一致。旧版读取器可能显示额外元数据注释；真实 IDE 控制台接管与源码点击仍待UI验收。
- **跨会话 EXPLAIN 计划历史（R90）**：复用应用级预览历史 XML 保存有界结构化快照，按项目、稳定数据源 UUID、方言、格式和实际 SQL 指纹隔离，基线选择只读不重跑查询。原始计划可能含业务值与凭据，保留开关独立默认关闭，设置页明示存储/清理边界，提交和结果回调双重检查授权；清历史阻止迟到回写。
- **AI 上下文 v2（R91）**：真实复制入口输出版本化诊断投影、匹配当前 SQL/实际目标的已取得计划摘要和真实静态表列计数，不主动执行 EXPLAIN、不调用网络/JDBC/MCP。未知或过期计划明确缺失；只保留白名单计划节点与数值，不附原计划条件/NOTICE。默认投影不可执行，也不保证替用户完成机密审查。
- **FieldFill 安全模拟（R92）**：CRUD/MP批量参数区补填充元信息和逐列模拟字面量输入，默认关闭、不执行用户 Handler。仅适配活跃 INSERT/UPDATE 写列，显式参数优先，WHERE条件/UPSERT不猜填；支持命名预设和独立清理，目标方言重渲染不使控件失联。
- **诊断审阅后反馈（R94）**：分享窗口新增诊断反馈草稿，默认隐藏 SQL 值、注释、参数名与标识符，不附参数、路径、原始计划或附件；不支持的引号形式省略 SQL，绝不退回原文。用户可删段、编辑最终正文，在目的地与 16 KiB UTF-8 预算明确后逐次同意提交；编辑或失败重试均需重新同意，仍使用既有官网公开需求池接口。仅验证 fake sender 接线，本轮未实际上传诊断。
- **预览方言来源与执行目标分层校验（O-145，第十九轮续实施）**：注解/mapper XML 在真实 SQL PSI 可用时尝试读取 IDEA provider 与有效方言；真实平台复验表明厂商候选不等于解析作用域，明确配置却无法可靠解析时降级为不可用，不标STRONG、不取第一数据源；预览结果的来源、置信度与候选 UUID 可在面板按需查看。实际选定的数据源在 session attach 前、原生控制台提交前分别核对；手动冲突和未知写/EXPLAIN 阻断，能无损重渲染的自动模式要求先复核再执行。模块绑定只在执行闸通过后学习数据源 UUID，项目页可清除；原始日志查询未知方言须确认。当前只有 headless/跨版签名和定向证据，真实 IDE/数据库行为尚未验收。
- **本地持久数据分离清理（第十九轮 P1）**：设置页可打开捕获历史目录（不自动删除文件），或分别确认清除预览 SQL 历史与参数历史/预设；参数清理会使已打开面板的旧缓存及迟到写回失效，不会连带删除预览历史的参数快照。
- **预览执行按数据源方言出 Runner（O-136）**：预览面板点执行时，草稿 SQL 的方言不再写死 MySQL——按目标数据源（上次使用→显示名→默认）路由平台方言（平台 DAS `findSqlDialect` 优先、插件 URL 推断兜底、皆缺诚实保持 MySQL 零回归）；方言匹配集非全部数据源时平台返回的「All」组 Runner 自动展开子项去重（达梦等此前被死组项挡住不可选）；方言与在册数据源不匹配导致执行不可用时给出二分归因文案（方言 id＋可行动清单）。多语句脚本与预览链（高亮/注释前缀）行为零变更
- **性能快件批二/三道齐落（T-PerfSweep D31）**：**道 A** 历史检索扫描环三合一重写——文件名倒序（截断丢旧不丢新）＋条目粒度封顶边读边滤（内存只驻命中集）＋「名字日期＋mtime 双旧」零 IO 预跳过；为此在 `SqlLogFileReader` 新增 `forEachEntry` 逐条迭代器（V47 前提，旧接口全保）。**道 B** 参数预设缓存改**桶外单把粗锁**保全部 accessOrder LinkedHashMap 桶（禁 CHM/读写锁——读改结构会丢 LRU 语义）＋`activeScheme` 改 `@Volatile`＋方案名列表去除中间分配。**道 C** 杂件包七件（控制台桥 methodCache 容量上限 64／三处现场 Regex 提单例／SqlDiffer LCS 千行护栏超限降级并排（R31「恰两条可用」契约不变）／ShardingSphere 折叠判长改累积长度计数／「日志转 SQL」剪贴板按钮退役 enabled 探针（点击空剪贴板走轻提示）／注册前置 `@Volatile registered` 短路）＋**F58 逐行 token 单次提取**（唯一动逐行热路径件，独立提交可单独回滚）＋搭车九件（绑定指示行取名链 Method 缓存／CriteriaQuery gutter 缓存按 file 键＋体量护栏／WrapperAnchorDetector 行文本一次取／dsName 反射缓存／织入归属链 `getMethod` 按类缓存／StatementRegistry 按 stmt 身份分桶／列表重建补完保留分批／SqlPreviewService 现场 Regex 提常量（附源码守卫）／showFileList 清 pendingEntries）
- **历史耗时回填结构指纹兜底（R54）**：精确匹配优先、零回退；未命中时落「结构指纹同键中位数」——解 O-108「历史耗时回填默认 `?` 空转」（`?` 占位与实值已归一为同一 token）
- **展示/导出链字面量脱敏（R60）**：新增 `DisplayMasker`（规则与增强捕获侧采集脱敏逐条等价）覆盖**四链**——SQL 日志列表（含多行片段缓存键补脱敏位）、单击预览浮层、历史/日志导出（CSV/TSV/TXT）、MCP `sqlv_render_logs`；设置页「展示脱敏」与 MCP 面「脱敏字面量」**双开关默认关**（不改既有用户观感）
- **AI 上下文复制（R37，选择性脱敏）**：预览面板「更多」菜单新增「复制为 AI 上下文」——输出块含来源/方言、按既有规则识别并参数化的敏感字面量与打码计数。非命中规则的普通 SQL 字符串仍可能保留原文（现有测试正对照保留 `name = 'tom'`），因此复制到外部 AI 前必须由用户自行检查和删改；不承诺整个上下文无机密，也不自动上传。
- **更新失败可诊断化（R62）**：检查更新失败区分「网络不通／更新通道 404／已是最新」三态（HTTP 状态码分层，非 404 错误带状态码诚实降级）；404 态给出 Marketplace＋Gitee Releases 兜底链接行
- **历史参数快照回预览（R51）**：历史条目落参数键值快照，从历史召回（recall）时把快照回填参数面板（回落链＝参数缓存优先、历史快照兜底；预览 flush 同步回写最新匹配条目）——旧历史文件零迁移（反序列化缺省空 Map），解 O-105 余半「历史无参数快照/无回预览桥」
- **EXPLAIN 执行计划跨次对比（R61）**：同一会话内两次 EXPLAIN 结果做树对齐 diff（cost/rows 变化标注）；节点稳定标识＝结构路径＋同型节点出现序（兄弟增删不串位）；MySQL/MariaDB 表格族与 PG/KingBase JSON 族可比，Oracle/SQL Server 文本计划诚实降级为不可比
- **回放脚本导出（R36）**：预览「更多」菜单新增「导出回放脚本」——参数已填回的可执行 `.sql`（语句切分感知 BEGIN/END 与引号内分号，逐条补尾分号）；事务包裹可选，新增 `Dialect.transactionBegin` 能力面（H2/SQLite 真引擎实测 `BEGIN` 语义成立，按官方文法逐方言对齐，Oracle/DM 保守判无并禁用勾选）
- **捕获双通道状态可见化＋暂停入口（R53）**：SQL 日志面板常驻状态栏展示两通道状态（已挂载/已收事件数/暂停中/总开关关）＋暂停/恢复动作；暂停语义＝「只停增强 agent 入队、控制台通道照常」（暂停≠关闭，连接与 CONFIG 回执链不动），与 24h 冷却/总开关三态互不干扰
- **Wrapper 布尔条件求值探镜（R49）**：Wrapper 条件行尾标注布尔首参求值三态——字面量 true「恒真」／false「恒假」／运行时占位符「恒真·缺省」（缺省 true 与求值 true 分开，防「缺省当求值」误读）；纯只读探针，渲染零变更
- **阶段二合并批六件**：**R45** EXPLAIN 执行计划体检（rows 估算独立规则＋体检规则清单）／**R48** MetaObjectHandler 自动填充仿真（`@TableField(fill=…)` 经 CRUD 生成链贯通到角标数据面；填充列默认勾选态不变）／**R52** 方言能力矩阵＋执行前自检（矩阵读 `Dialect` 接口真值防伪能力，确认弹窗按缺口追加发现行）／**R46** 执行前 Schema 校验（未识别列＋did-you-mean；表存在性不可零成本判定故只校验已预热表列，fail-open 不误报）／**R42** DML 影响面预演（UPDATE/DELETE 执行前只读 COUNT 试算，MCP 写护栏联动）／**R50** 一键诊断报告（五段：SQL+参数/性能规则/EXPLAIN 建议/无界写/回滚预案＋方言能力，Markdown 复制与自包含 HTML 导出）
- **参数预设与参数历史按声明方法隔离（T-PresetMethodScope）**：预设、参数历史与最近参数按「声明方法的签名」分桶，同名预设不再跨方法互相覆盖；从历史召回只回填到原方法，切到别的方法不再串味。
- **参数预设永久绑定方法（T-PresetMethodBinding）**：预设保存时记住所属方法，之后改写 SQL 文本（换参数名、调整结构）仍按方法命中，不再因文本指纹变化丢预设；同一方法的多条预设互不覆盖。
- **预览只读旁路（T-PreviewSideQuery）**：预估影响行数、EXPLAIN 与回滚快照查询改走只读旁路，不再替换预览编辑器里的 SQL；EXPLAIN 结果改为非模态对话框，执行视图新增「返回预览」，影响行数结果改为顶部横幅并支持「复制 SQL 并关闭」（UI-034）。
- **回滚快照双态与 CSV 编辑器（UI-036/UI-041）**：快照查询结果支持表格/文本双态切换与列宽调整，可在 IDEA 原生 CSV 编辑器中打开；快照查询没有数据源时直接弹选择器并自动重试（UI-037），预览的 INSERT 仍可一键推导逆向 DELETE。
- **方言闸门阻断态就地修复入口（T-DialectGateUx / UI-040）**：预览结果行在方言阻断时给出「切换数据源」「方言设置」两个链接，切源后重试、改完方言后按目标方言重渲染；绑定数据源后自动按新方言重渲染并重试。
- **预览错列 Schema 标注（UI025）**：预览 SQL 中无法解析的列标红波浪线并在悬停时给出建议；日志条目预览回落到面板已确定的数据源目标，不再借用其他连接的表列。
- **执行前限定目标 Schema 预检（UI023）**：预览真实执行入口在提交前校验目标 Schema 与执行目标，拒绝 CTE、`DELETE ... USING` 等歧义形态，缓存按精确目标隔离。
- **历史列表直达末条**：工具栏新增动作，一次跳到当前过滤结果的最后一条，长列表不再靠滚。
- **FieldFill 安全模拟面板**：CRUD/MP 批量参数区展示填充元信息与逐列模拟值，`[fill=…]` 角标与字段名同行，模拟值优先于普通输入框（普通框置灰），模拟值行只在勾选安全模拟时出现。
- **复制 SQL 并关闭（UI-042/UI-043/UI-044）**：预览面板底部新增蓝色主行动按钮，一次复制当前 SQL 并关闭面板；按钮按平台 LAF 绘制，复制后的通知不再附带「关闭 SQL 面板」动作。
- **插件设置接入 IDE 自带设置搜索（T-SettingsSearchA）**：运行期注册 `search.optionContributor` EP，把插件设置项喂进 IDE 的设置搜索索引——在 `Settings` 搜索框里搜「脱敏」「预设」「FieldFill」等关键词可直接定位到对应设置项，不再只能靠逐页翻。索引与页内搜索共用同一份 `ALL_SEARCHABLE_FIELDS` 数据源，两套搜索天然同步；9 个既有设置类一行未改（平台 `ConfigurableWrapper` 本身实现 `SearchableConfigurable` 且 `getId()` 优先返回 EP id）。当前索引按插件语言生成，英文词条不命中中文界面。
- **页内搜索补登记「展示与导出脱敏」（T-SettingsSearchB）**：此前在设置页内搜索「脱敏 / 展示 / 导出 / 打码」都翻不到该开关，已补进索引，并立面板-索引配对守卫（白名单逐条留痕，含既有 21 项未索引开关台账）。

### Changed
- **增强捕获暂停按钮临时隐藏（T-AgentPauseVisibility，方案 B）**：增强捕获已默认开启，暂停/恢复按钮在应用未运行时没有可暂停对象，并与捕捉按钮形成播放/暂停误读；先整体隐藏，动作对象保留在工具栏组内（能力代码不删）。暂停 agent 通道的 UI 入口暂时不可达，关闭总开关仍回落控制台；恢复路径为方案 A（agent 挂载或暂停态才显示）或状态栏/右键菜单入口。
- **增强捕获默认开启且不再弹注入确认（T-EnhancedCaptureDefaultOn）**：总开关默认开启，打开即授权——项目打开、首次 Run、进程出现、设置页勾选、缺源码引导五条路径均不再弹确认框；移除 24 小时拒绝冷却、重新授权按钮与「未授权」状态行。旧配置里的 `confirmed=false` / 拒绝时刻在加载时静默归一，用户显式关闭的开关保持关闭。设置页开关与 tooltip 继续说明采集范围、数据留本机与可随时关闭；JDK 21+ 动态挂载告警仍属预期。
- **Wrapper 与 JPA/HQL 预览按来源文件推断方言（O-145）**：Wrapper 在没有真实 SQL 注入时仍参考宿主文件的 IDEA 有效方言，同一方言贯穿分页 COUNT 与主 SQL；Java JPQL/HQL 命名查询按定义处文件取来源，同名定义歧义不猜；Criteria、native、hbm XML、Specification、QBE 与 Procedure 也以真实宿主文件作不含 URL 的来源快照，hbm 同名异文时拒绝任取第一条 SQL。旧日志/历史不含精确 JDBC URL，不能由脱敏库标反推执行时方言；真实 IDE/数据库验证仍待完成。
- **XML mapper 预览按语句来源判方言（O-145 W1/W2）**：动作与 gutter 把原 XML 文件、当前语句和有效光标交给瞬态来源解析；只采纳真正 DatabaseTools SQL 注入，自研 MpSqlMapper 不冒充。多注入不取首个，按来源作用域的有效方言渲染并模拟分页；纯文本预览及无来源时保持原回退。
- **底部快速预览接入展示脱敏（R60，T-SqlListPreviewInteraction 合流）**：已发布的单击选择、底部连续预览与草稿固定交互保留；dev 的日志与历史底部预览继续服从展示脱敏开关，双击完整预览与多选复制保持原样。
- **工具栏图标语义统一（T-ToolbarIconSemantics）**：四个页签统一为插件自带 SVG 图标（预览/滚底/导出跨页一致），消除 243 与 262 平台的图标形态差异；工具窗窄宽溢出交回平台处理。
- **分发包许可与依赖合规**：LICENSE 与 THIRD-PARTY-NOTICES 随包分发（插件目录与主 jar `META-INF/` 双落位）；排除 JSqlParser 误带的 jmh-core / jopt-simple / commons-math3 三件套，分发 zip 约减 20.8%，运行期 `lib/` 只保留 jsqlparser 与主 jar。

### Fixed
- **方言冲突不再阻断执行（8619a3af）**：用户定案「方言不能影响最终的执行，哪怕方言是错的，手动选了数据源可以提示不一致，但要能成功执行」。`MANUAL_CONFLICT` / `RERENDER_UNAVAILABLE` 从「红字 + 阻断」改为「红字 + 放行」——两边方言都已知时，冲突只是渲染来源与目标库不一致，交给数据库自己报语法错是最准确的反馈。**边界保留阻断**：`TARGET_UNKNOWN_BLOCK` / `RENDER_UNKNOWN_BLOCK`（有一边方言未知，写操作可能把 PG 语法打到 MySQL 库上，属无从判断而非判断出冲突）。另修报错文案：`runners` 为空时此前只从目标源反推方言，出现「预览写着方言 PostgreSQL、文案却说没有与 MySQL 匹配的数据源」的自相矛盾，现优先用渲染方言。
- **Runner 枚举为空时按目标方言重映射再枚举（e7a27700）**：平台按 exec 文件已映射的 SQL 方言筛 runner，数据源在平台侧没建好方言映射时一条都筛不出（`runners=0`），但数据源其实是连着的。现 `runners` 为空时把 exec 文件重映射到目标数据源方言再枚举一次，命中即递归回同一分派逻辑——控制台 / 结果表 / 审计全部复用既有链路，不新建执行通道；重映射后仍为空才落既有红条。
- **执行计划取源与换源十二轮问题收口（T-DialectInferPollution / T-ExplainFailUx / T-ExplainSwitchNative / T-ExplainConsoleFirst）**：方言闸门重渲染后自动重提交、直送链取源接 last-used 回落；按表归属路由选源（表不在候选源即自动改取持有该表的源）；按模块学习归属，冲突不猜，消掉跨模块错源执行；排队执行意图自有有界结算，缓存热零命中时引导选择；选错源不再弹模态，改就地横幅引导换源；换源交还平台原生 `Data Sources` 工具栏。最终形态收回到预览窗内嵌原生控制台，插件不选源不筛不猜，并偿还 `1a1cf724` 的技术债——新增 `previewSqlForReadonly()` 让五处只读动作读预览快照，根治执行视图下取词漂移成 EXPLAIN 语句。另修「启用结构化并重试」推荐通知每次执行计划都弹、点一次即永久点亮开关的问题，改为只提示一次。
- **Unknown column 按跨源证据升格为「选错数据源」提示（27429da6）**：`Unknown column` 此前一律被排除在「选错数据源」之外——对拼错列名成立，对「同名表不同列集」漏判（用户实测 `[42S22][1054] Unknown column 'first_name' in 'field list'`，上下文三处读数全是 `mp_demo`：源没选错，错的是「SQL 与库」这一对）。新增 `CrossSourceColumnEvidence` 纯判定（零 IDE 依赖、零连接），三条同时成立才升格：①错误文本能解析出具体列名；②当前源同名表**确证**没有该列（缓存未热＝无从证明，不升格）；③存在另一在籍源、其同名表带该列。第③条把「拼错列名」（任何源都没有 → 照旧只报错）与「源选错」（另一源确实有 → 提示换源）分开，宁可漏判不可误判。
- **上次使用的数据源按模块隔离（T-ModuleScopedLastUsed）**：三层记忆（活控制台 > 模块级 > 项目级兜底），多模块工程不再串用其他模块的数据源；指示行文案区分「本模块上次使用」；`moduleKey` 缺失必须回落项目级。沙盒验收抓出并修复「用兜底读取的返回值反推命中层级」恒为真的误判——改为直接读模块级键本身。
- **SQL 日志工具栏动作组改走 BGT（T-SqlLogToolbarEdt）**：动作组与 11 个动作逐个声明 BGT，消除每次抢占 EDT 3.1~4.0 秒的告警；`currentSortLabel` 去分配改静态查表，实测 20 万次 38.1s → 5.8s（6.6x）。
- **exec 预热改异步不阻塞 EDT**：执行文档预热不再占用编辑线程。
- **预览降级横幅按原因分型与宽限期静默（T-DegradedPreviewUx）**：首开预热仅超 500ms 预算时就挂红色「本次项目内临时文件不可用」横幅，而延迟产物通常数秒内自动换装——现超时改为 3 秒宽限期静默（期间恢复则横幅一次都不出现），只有硬失败才立即披露并写明原因；降级横幅改提示色、单行正文 + 悬停补充说明。降级态改参数/勾选条件的重渲染已在 headless 验证（UI-022 沙盒复验待做）。
- **Kotlin Wrapper 主动预览不再静默无响应（第十九轮可信预览）**：不可可靠推断实体表、列和值时继续隐藏 gutter 假入口；用户主动使用 Convert to SQL/快捷键压在已识别链上时，改为非模态说明限制并明确不生成猜测 SQL。选择区同样只在实际命中 Kotlin Wrapper 行时提示，文件其他位置的链不会误触发；插件描述同步收窄 Java/Kotlin 通用声明。
- **Wrapper 服务链实体未知时的提示回归（O-145）**：新增 Kotlin 无表降级分支时误把既有服务链的具体说明缩成通用提示；恢复“无法推断实体表”的原有文案，Kotlin 源码单独给出降级说明，不生成猜测 SQL。五片门禁前置 shard3 曾因此失败，修复后相关两类定向 72/0/0；完整五片需重跑。
- **MCP 禁用工具的直接调用门（第十九轮 P1）**：除工具列表过滤外，十一项工具共用的执行入口在运行前重新检查总开关和单工具开关；外部会话即使记住工具名也不能绕过禁用设置。禁用返回可读的 `TOOL_DISABLED` 错误，不执行工具逻辑；headless 直接调用测试已先红后绿，真实外部客户端请求待验。
- **预览方言载体与能力提示（O-145 W4/W5）**：无动态条件的 JPA/XML、普通 CRUD 与批量模型此前已按目标方言生成 SQL，却在面板/EXPLAIN 或临时 PSI 映射时被当成 MySQL；现统一保留生成方言，平台缺对应方言时临时 PSI 退到 GenericSQL。方言能力矩阵原 tooltip 只有无消费的表达式，现实际接入悬停说明；均有定向与反向守卫，真机显示待验。
- **EasyQuery 侧布尔标志语义与 Wrapper 侧统一（R49 收口）**：`eq(true, …)` 这类字面量 true 此前在 EasyQuery 解析链被折叠成「无标志」（`flagSource=null`），而同一条表达式在 QueryWrapper 链携带 `"true"`——两条链渲染与标注不一致（R49 探镜在 EasyQuery 侧不标注）。现两侧统一为携带源码文本，`null` 归还「非布尔重载」单一语义
- **「更多」菜单契约守卫同步（R36/R37/R42 增项）**：预览顶栏菜单契约断言从五项补齐为八项（回滚 SQL/导入参数/差异对比/分享/导出可移植 SQL/导出回放脚本/复制为 AI 上下文/DML 影响面），并把新增项一并纳入「不得直显」的折叠断言
- **源码守卫元登记（F84 配套）**：新增的「函数体内现场 Regex」源码守卫补进守卫元登记表（此前整体脱离自证覆盖率扫描面）
- **UI 走查批次修复 · 预览与执行（UI-001~UI-041）**：设置页列宽污染（UI-001）；EXPLAIN 历史基线改为双语可读来源与本地时间（UI-002）；准备阶段取消与排队替代请求补齐终态、用户交互超时不再被当作失败（UI-014）；原生控制台视图随项目关闭正确释放、取消时致命错误照常透传（UI-016）；INSERT 列名与值对位修复（UI-021）；结构化执行计划不可用时直接弹数据源选择器并自动重试（UI-030）；执行计划对话框取消按钮跟随插件语言、无数据源直达 Database 工具窗口（UI-035）；影响面预演取消数据源选择后解除 loading、不可试算时覆盖旧的内联结果（UI-031/UI-034）
- **UI 走查批次修复 · 日志与历史（UI-004~UI-026）**：历史页首次进入自动加载已有文件（UI-004）；兼容 ShardingSphere 5.5.2 参数日志并保留原始 Logic/Actual 配对、同结构计数与执行去重分开（UI-008/UI-013）；历史搜索非法正则显式反馈、区分无匹配与空文件（UI-009）；手动浏览后的滚底状态与「滚到底部」恢复（UI-012）；跨方法另存同名预设不再被全局查重拒绝（UI-017）；仅真实提交回执才更新回滚留痕与横幅（UI-020）；存储操作按钮宽度与英文设置页横向溢出修复（UI-024/UI-026）；日志时间拒绝跨签名借时，也不给无时间日志伪造时间
- **UI 走查批次修复 · 转换与捕获（UI-005~UI-010）**：SQL 反向生成聚合投影整链回落并合并 select、双引号表名不再进入 Java 泛型类名、无实体时 QueryWrapper 输出保持兼容（UI-005）；FieldFill 参数区左对齐与窄栏多行布局（UI-006）；增强捕获统一排除 IDE 与构建辅助 JVM（UI-007）；SQL 转 Java 淘汰过期异步回调并隔离解析缓存（UI-010）
- **MCP 与路径细节修复**：`sqlv_perf_advice` 的 missing_where 大小写误报修正（T-McpPerfWhereCase）；MCP 设置页区分「声明」与「已注册可调用」；MCP 保留反射驱动异常消息与错误位置；识别 POSIX 绝对路径并按真实路径限制项目范围
- **预览与转换零碎修复**：SQL 片段入口支持字符串拼接实参并按序登记占位符来源（UI-038/UI-039），补齐 select/first/around/UNION/LIMIT 的片段占位符来源；精简 SQL 快捷模板接入右键菜单；首次召回 foreach 快照时同步 SQL 展开长度；日志转 SQL 按提交回执区分取消与阻断，不再提前提示成功；SQL 快捷模板先做单表标识符校验再提交
- **执行链生命周期**：exec 预暖链与文档写全链挂项目 dispose 生命周期守卫，文档写零窗口化，消除门禁冲突与项目关闭后的迟到写入
- **导航工具栏测试访问器绕过 override-only API**：`HistorySqlLogPanel` 的测试缝不再在生产字节码里调用 `ActionGroup.getChildren`，改为只暴露动作组、由测试侧取子项；插件校验报告的 override-only 用法由 1 项归零
- **模块身份解析改用公开 API**：`ModuleIdentityResolver` 不再调用 `Module.getModuleFilePath`（`@Internal`），改走公开的 `ModuleUtilCore.getModuleDirPath`，「目录 + 模块名」作模块身份键（`ModuleKeySource.MODULE_DIR`）；插件校验报告的 internal API 用法由 1 项归零
- **SQL 转 Java 条件组与逻辑删除（T-ReverseGroupLogicDelete）**：`or()` 不再被后续 `and(...)` 静默吞掉，嵌套条件组保留 `NOT`，逻辑删除列不再重复生成 `AND deleted = 0`（此前 MP 注入的默认条件被当成手写条件重复输出）
- **SQL 转 Java 类选择字段（UI-045）**：类名输入框描边与圆角改用平台口径（2px 描边 + 贝塞尔圆角），消除与原生输入框的观感差异
- **增强捕获与第三方 agent 共存导致目标 JVM 无法启动（T-JRebelPremainCrash）**：用户同时开启增强捕获与 JRebel 启动器后，目标应用以 `FATAL ERROR in native method: processing of -javaagent failed, processJavaStart failed` 直接起不来——`retransformLoadedDrivers` 的名单谓词位于 try 之外，第三方 agent 接管 premain 类加载后重定位 ASM 不可见，谓词触发织入器类初始化时抛 `NoClassDefFoundError`（另一种加载器形态为 `ClassCircularityError`），异常穿出 `premain` 即终止宿主启动。现在 premain/agentmain 双入口统一做失败兜底（捕 `Throwable`，含 `Error`），降级后不写在线标记以便运行期 attach 仍可重试，并在 stderr 留一行降级说明；名单判定与 ASM 解耦（登记表上提为独立类），使「查名字」不再要求 ASM 可解析，异常场景下增强能力不丢。插件侧新增 premain 未按时回连的降级感知与一次性提示，回落运行期挂载。已在 Corretto 21.0.12.1 上用真实 agent 产物验证修复前后对照（修复前同形态必然致命，修复后进程正常启动、逐类失败被计数拦下）；未复刻用户 JRebel 的具体转发路径，未在真实 IDE 工程端到端验收。
- **设置页能力矩阵的 MCP 工具数停留十工具**：实际为十一个，落地页文案已修正。
- **预览错列标注会话未注册进 Disposer 树致 IDE 退出内存泄漏告警（ebfaf73f）**：关闭 IDE 时报 24 条 `Memory leak detected`，全部指向 `PreviewSchemaHighlighter$Session`——Session（Disposable）从未注册进 Disposer 树，其内部 Alarm 以它为 parentDisposable，平台把这条孤儿分支挂到 ROOT_DISPOSABLE 下、退出时不 dispose。现 install 以 `editor.project` 为属主 `Disposer.tryRegister`（项目关闭即兜底回收），Session 自带可判定的 disposed 标志（243 的 `Disposable` 无 `isDisposed` 不能 override），uninstall 改走树感知 `Disposer.dispose`。
- **jar 内宿主叠加工程同 FQN 影子实体导致实体不可解析（T-ShadowEntityResolve）**：用户方法、HQL、实体类都在 jar 里，工程下又新增一个同包名同类名的类（字段有差异）时，预览降级为 `task.*` 并提示「实体名有多个同名候选」。现按模块依赖方向与源码形态仲裁：同 FQN 两份都判为工程内容时按源码形态收窄（jar 位于 `resources/lib` 等），跨模块同名按依赖方向选宿主模块，jar 内宿主仍可正常判定；workspace 形态下宿主作用域零候选时不再直接判不可解析。jar 以 sources jar 附带与反编译视图两种形态都覆盖。
- **结构化执行计划的基线下拉被拉伸到整屏宽（d068a5ef）**：EXPLAIN 结果视图里基线下拉在纵向 BoxLayout 下被拉到容器最大宽度，现按内容定宽。
- **结构化执行计划未存密码时交平台原生密码框（T-ExplainPwdPrompt）**：数据源未存密码时点「执行计划」只出红字 `Access denied (using password: NO)`、不弹 IDE 原生密码框——根因是 `McpDbExecutor` 建连硬编码 `setAskPassword(false)`（MCP 零弹窗红线），面板 EXPLAIN 与预估行数旁路切到该通道时被连坐。现 `askPassword` 改为**必填**参数，编译期强迫每个调用点按通道表态：面板执行计划与只读旁路传 `true`（弹原生框），MCP 工具仍 `false`（零弹窗红线原样）。失败按 AUTH / CANCELLED / OTHER 分型：认证失败附「补存密码」指引且不再回退直送，密码框 Esc 取消只轻通知绝不自动重试，其他维持既有回退。
- **EXPLAIN 真绑定缺失时回落上次使用数据源（d72d6f2a）**：结构化执行计划在真绑定缺失时被方言路由的空 Runner 阻断，现回落上次使用的数据源取源。
- **参数未回填不再回退直送（1bed57bc）**：消掉误导性的「未绑定数据源」红字框。
- **认证失败改轻通知（bd9ac4f0）**：Abort 后不再弹红字模态框二次打断。
- **premain 暂停恢复无界 join 挂死（48049395）**：accept 循环改代际让位 + resume 加有界预算兜底，消除 75 分钟挂死。
- **预览执行链三组平台内部告警收口（9809a5b7）**：EDT 读盘慢操作断言、裸池线程缺进度上下文、Disposer 二次摘监听三组 SEVERE 归因修复；用户侧表现为执行计划→选源→关面板的会话不再刷内部错误。

## [2026.10.16] - 2026-10-16

### Added

- **库内「非常规命名」实体的反查：后台扫描 + 显式触发（T-JarEmbedded 优化方案 1+2）**：依赖 JAR 里的实体若类名与表名约定不符（如 `LegacyBooking` ↔ `t_booking`），只能靠 `@TableName` 认出，而这需要扫全库类——直接挂在「按表名找实体」这条路径上会拖慢最常见的「表名没有对应实体」场景。现在该扫描挪到**后台、每个项目结构版本一次**（结果落项目级缓存；改源码正文不作废，依赖/模块变化才失效；本次转换先按字符串列名回落，下一次反查即命中），且**只认 `@TableName`**——不再回落到「按类名推断表名」，因为那对任何类名都能推出非空表名，会把整条 classpath（含 JDK）的类全建成「表」（实测只挂 JDK 就有 2 万条目、单趟 4.4 秒）。扫描按每 500 个短名一片、每片一个独立读动作，**片间真正释放读锁**，因此不会像整趟持锁那样冻住打字（实测写动作最大等待 5.5 秒 → 158~286 毫秒）。设置页「代码生成」组新增开关「后台扫描依赖库以反查实体」（默认开，可关）；关掉后可在实体选择弹窗用新增的「在依赖库中查找」按钮**显式触发**一次（扫完就地刷新各表候选、保留已选）。四道闸（设置开关 / 每个结构版本一次 / 冷却窗口取「配置值与上趟耗时」的较大者 / 全局在途上限）与「按数据源校正列名」的后台预热同款；并新增源码守卫 `FullScanCancelGuardTest`，钉住「全量短名扫描必须可取消、且不得被外层读动作包住」——20261008 真机冻结事故（T-GutterDupFreeze）正是这个形态，此前仓内没有守卫覆盖它
- **SQL 日志与历史列表更易读（T-SqlListNativeVisual，O-146）**：SQL 语法色改为跟随 IDEA 编辑器配色，列表、底部预览和自建 SQL 预览使用同一组高亮键；默认显示仍是关键字换行（与以往一致，最多三行并标注剩余行数，便于扫读 SQL 大致形态）；长来源与 SQL 按窗口宽度省略，列表不再横向滚动。工具栏动作全部直达（不设自建「更多」菜单，窄侧栏由平台自动折叠、悬停即可看到全部动作）；排序方式并入左侧按钮组并以排序图标呈现，窄侧栏下随组一起折叠，当前方式见悬停提示与菜单选中态；格式化模式仍可看完整 SQL，复制和预览不受省略影响。
- **调用栈业务帧优先与摘要入口（T-StackFramesFold）**：预览中的静态调用栈优先呈现可识别的业务帧，相邻内部调用折成可展开的段；采集时省略的帧明确标示，不伪装为可展开内容。改进深栈采样对部分业务入口帧的保留，调用栈摘要也可单击打开弹层；原文复制与逐帧跳转保留。跨线程或原快照未采到的帧不能凭展示恢复。
- **SQL 日志交流群入口（T-SqlLogCommunityDialog）**：工具栏新增「加入交流群」，原生弹框并排展示作者微信和交流群说明；二维码在打开时异步从官网获取，加载失败可从弹框打开官网交流页。

### Changed

- **仓库首页 README 更新**：按官网首页的八模块顺序组织演示（Wrapper 预览、SQL 日志、源码溯源、SQL → Java、CRUD、XML、JPA / Hibernate、MCP），动图与联系二维码改为官网稳定 URL，避免维护两份素材；补充新版日志交互、历史检索与导出、参数预设、XML 编辑辅助及 11 个 MCP 工具，精简重复配置说明并链接在线文档；纠正回滚预案、热挂载、离线通信与外部 AI 数据流的能力边界。
- **SQL 日志与历史列表预览交互（T-SqlListPreviewInteraction）**：单击只选中，不再弹出遮挡列表的轻预览；日志与历史二级列表通过“快速预览”图标开启默认收起的底部预览区，选中下一条即可连续查看；分隔条随 IDE 主题显示，不再出现深色主题白边。临时修改 SQL 时保留当前编辑内容，恢复原文后继续跟随；首次单击会出现一次不抢焦点的入口提示。双击完整预览、列表多选复制保持原样。
- **SQL 日志尾部跟随与排序按钮（T-SqlLogTailFollow，O-148）**：默认仍跟随最新 SQL；手动滚轮、拖动滚动条或键盘浏览后暂停自动滚底；点击「滚动到底部」恢复，清空列表也会开始新一轮跟随。排序并入左侧按钮组（组内最后一个操作类动作，帮助收尾），窄侧栏下随组一起折叠。
- **SQL 日志排序方式与慢查询筛选（O-148）**：排序菜单新增「同结构聚类」（同形状 SQL 相邻，排查 N+1 用）与「按目标库分组」（多数据源/多模块定位用，未知库沉底）；类型过滤弹窗新增「只看慢查询」勾选，只保留耗时达到慢查询阈值的条目（耗时未知的条目不算慢查询）。排序与筛选都在刷新管线里对当前可见条目做一次遍历/排序（上限 200 条），不进入渲染热路径。

### Fixed

- **同名实体跨模块误选：预览改为按查询宿主模块可见性解析根实体（T-EntityNameScope）**：HQL 里的实体名默认就是简单类名，工程里两个模块各有一份同名实体时（如 demo 的 `jpa-embedded-prefix-demo` 与 `jpa-jar-model-lib` 各一份 `WmsInventoryEntity`），此前按短名**取第一个**——预览把实体认成了另一个类：FROM 表名与 SELECT 列清单全按它渲染，而 HQL 里只存在于目标实体的属性路径找不到映射、只能原样透传。现在统一由 `EntityClassResolver` 收口：查询宿主模块已知时**只查该模块及其依赖与库**（不再向全工程扩大范围捞不可见类）；显式 `@Entity(name)` 与默认实体名同处一个命名空间、大小写精确匹配；同全限定名的源码/二进制定义按平台 classpath 顺序取；**同级多候选即判歧义——不选、返回未解析并提示一次**，不再静默挑一个。类型化查询的结果类型与 `@NamedQuery` 注解宿主不再绕过这道门禁。另把转换链原有的强提取算法（字面量掩码 + 括号深度 + 全限定名保留）收口进 `HqlEntityNameExtractor` 供预览共用，修掉「预览的弱提取压过转换链的强提取」这一不一致；已声明但解析失败的副表别名不再借用根表列名。另外把别名慢路径的**触顶结果也落缓存**（空候选）：真实工程的短名空间含 JDK 恒大于 5000 预算，触顶是常态，原实现不缓存会让同一个名字**每次预览都重扫一整段预算**（约 1 秒，且该扫描在预览的读动作里＝持读锁），缓存不改变结果（触顶本就恒为未解析）只去掉重复代价，缓存随 PSI/依赖版本失效。验证：集成定向 19 类 **205 例 0 失败**；反向摘掉三处保护 → **恰 12 红**、单独摘掉「歧义即终态」→ 58 例中**恰 1 红**、单独摘掉「触顶落缓存」→ **恰 1 红**；修前红基线为 32 例中 10 红
- **表名→实体反查在「实体类来自依赖 JAR」时找不到（T-JarEmbedded W2）**：SQL→Java 反向转换按表名找实体（`EntityClassFinder`）的两条路（候选名快路径 + `@TableName` 全类扫描）此前一律只在**项目源码**范围里查，共享模型 JAR 里的实体一个都反查不到。现快路径补**库兜底**：项目范围 0 命中后，再按候选类名在依赖库范围找一遍并按表名精确校验（源码命中时行为逐字不变；代价上界＝4 个候选名 × 一次索引查询，且实体解析本身有三维缓存）。**明确不做**「全库逐类扫描」的慢路径库兜底——那要遍历全库类名并逐类读注解/解析（O(库类数) 的 PSI 访问），会把「表名无对应实体」这条此前反馈过卡顿的路径成本推高。已知边界：库里的实体若类名与表名约定不符、仅靠显式 `@TableName` 反查，仍找不到（与改前一致，无回归）。验证：`EntityClassFinderIntegrationTest` 里挂上本机 JDK 当 SDK（`HeavyPlatformTestCase` 默认不挂 SDK），用真实库类反推表名验库兜底，并断言「快路径命中 / 库命中都不触发慢路径全量扫描」；摘掉库兜底那一档 → 该用例必红（其余 9 条保持绿）
- **实体来自依赖 JAR 时，嵌入列命名约定探测不到（T-JarEmbedded W1）**：`@Embedded` 嵌入列前缀（`qty.baseQty` → `qty_base_qty`）的判定此前只在**实体所在模块**的 `application*.yml/.properties` 里找信号；而实体类常来自共享模型 JAR（无源码，模块根不可知），且命名策略往往**写在 Java 代码里**（`props.put("hibernate.implicit_naming_strategy", ImplicitNamingStrategyComponentPathImpl.class)`）或声明在 `META-INF/persistence.xml` —— 这些位置此前一个都不看，于是即便装上支持前缀的版本，这类工程仍会渲染出丢前缀的列名。现在模块不可知时退到**工程级信号**：配置文件扩到 `persistence.xml` / `orm.xml` / `hibernate.cfg.xml`（含 `META-INF/`），并用词索引在工程源码里找命名策略类名（代码式配置）；模块已知时行为不变（只看该模块，防兄弟模块配置外溢）。另外**自定义**的 `ImplicitNamingStrategy` 实现类也按语义特征识别（文件既参与隐式命名、又用到 `AttributePath` 的父段/全路径 `getParent`/`getFullPath`/`getDepth`——那正是「把父路径并进列名」的写法），只看末端属性名、或只改表名的自定义策略不会被误判为组件路径档
- **SQL 日志与历史列表的正常耗时颜色被灰色覆盖**：列表元信息展示层原先把低于半阈值的耗时片段二次覆盖为灰色，导致正常耗时不再显示绿色。移除该覆盖后恢复正常绿、半阈值以上橙、慢查询红、未知耗时灰和选中白，分档回归测试已补齐。
- **预览准备期写意向锁自锁导致 IDE 卡死（T-PrewarmWilDeadlock）**：后台预热执行临时文件时，原逻辑持写意向锁同步等待 VFS 刷新会话；会话推进又需要该锁，预览停在「正在准备预览...」并拖住 IDE。后台刷新移除写意向锁包装，保留 EDT 冷路径所需保护；投机性执行预热增加 30 秒预算，超时放弃并释放在途标记，不影响后续预热。262 真机冷启动复验仍待完成。
- **插件校验报告中的三项 override-only API 用法**：SQL 日志面板里两处直接调用 `AnAction.update` 和一处直接调用 `DefaultActionGroup.getChildren` 的测试访问器被打入生产包，导致 IDEA 243/253/262 的校验报告各出现三项违规。移除无调用方的访问器，将排序 tooltip 断言所需的动作更新留在测试侧；正常工具栏更新与动作分组不变。
- **SQL 历史首次进入不加载已有文件**：首次真正显示历史页时补幂等扫描保障，不再需要点刷新才能看到已保存的 SQL 文件；后续切入仍按原逻辑刷新。
- **SQL 日志工具窗页签在窄侧栏下折成两行（T-SqlLogTabsSingleRow，O-149）**：页签改用平台 JBTabs，窄侧栏下保持单行，空间不足时由平台压缩或提供溢出入口，不再把四个页签挤成两行。

## 历史版本归档

| 归档分册 | 覆盖版本 |
|---|---|
| [发行-2026-09.md](./docs/变更记录/发行-2026-09.md) | 2026-09 发布的版本 |
| [发行-2026-08.md](./docs/变更记录/发行-2026-08.md) | 2026-08 发布的版本 |

---

## [2026.10.13] - 2026-10-13

### Added

- **SQL 日志捕获通道归因与同桶信息融合（T-AgentCaptureProvenance）**：增强捕获与控制台通道对同一 SQL 的观测可融合：后到的真实源码位置、候选帧、耗时及目标库等按「只补空不覆盖、真实位置优先」补进原条目，保持列表位置与序号；融合后的捕获路径可写入历史，旧历史文件仍可读；控制台回落条目缺源码时提示实际通道原因，不再错误引导用户再次开启捕获。限频诊断记录通道与信息完整性，不含 SQL 原文及参数
- **支持「SQL 由同工程方法拼好再传入」的写法（T-HqlHelperReturn）**：`commonDao.findByQuery(buildHql(), …)` / `session.createQuery(buildHql())` 这类把 SQL 交给一个**源码可见**的方法去拼的调用，此前既不亮预览图标、快捷键预览也失败（判定口径是「调用处首参是否为字符串承载」，方法调用不在其列，`locate` 直接返回 null）。现改为跟随到该方法的 `return` 表达式聚合：深度上限 3 层（防递归/环）、只收本方法自身的 `return`（排除嵌套方法与 lambda 体内的）、**多分支返回内容不一致时不猜**（宁缺不错）、方法返回类型非 String 或没有源码体（JDK/三方库）一律不认领（挡住 `selectList(<wrapper 链>)` 这类误判回归）。demo 复现模块 `jpa-embedded-prefix-demo` 现备齐三种 HQL 承载形态（拼接变量 / StringBuffer + `sb.toString()` / 方法返回），插件侧门禁 `DemoEmbeddedPrefixReproAnchorTest` 断言三者各恰 1 枚图标
- **方言引号不匹配静态告警（T-DialectQuoteWarn，静态规则第 21 条 `dialect_quote_mismatch`）**：SQL 里的标识符用了当前方言不接受的引号字符时给出告警（如 MySQL 方言下出现 `FROM "user" t`）。`@TableName("\"user\"")` 这类达梦风格双引号在 MySQL 真机会报 `[42000][1064]`，插件此前只是如实画出。**只报告不改写**：渲染的仍是 MyBatis-Plus 真实发出的 SQL（改写会让预览偏离真机、把用户代码里的真 bug 静默抹平）。告警同时流到预览面板静态规则区与 MCP `sqlv_perf_advice`；方言从项目设置解析，取不到时不判定（不硬编码 MySQL）。判据按各方言实际宽容度定：SQL Server 的 `QUOTED_IDENTIFIER` 默认 ON（双引号合法）、SQLite/H2 收多种引号，避免误报。**（T-DialectQuoteWarn §十二 调整）**「MySQL 家族（MySQL/MariaDB/OceanBase）+ 双引号」这一档不再一律报 ERROR——双引号是否为标识符取决于连接 `sql_mode` 是否含 `ANSI_QUOTES`，而数据源 URL **只能证明「合法」不能证明「非法」**（URL 未声明该项时服务端全局仍可能开着）：预览按绑定数据源（活控制台 > last-used 记忆）的 URL 取该三态，确知声明了才不报，已知未声明或未知都降级为**带前提的 WARN**（缓存键含该三态，避免串味）；PG/Oracle/DM/KingBase/openGauss 遇反引号、非 SQL Server 遇方括号等无歧义档仍报 ERROR
- **mapper XML 语法高亮注入（T-XmlSynHi）**：mapper XML 内语句文本按 SQL 语法着色（关键字/字符串/注释）、`#{}`/`${}` 参数占位加底色、`test`/`value` 属性表达式着色；输入 `#`/`$` 自动补全 `{}`；resultMap `column` 属性补全列名。让位改为**宿主级探测**（任一会给该宿主上色的其他注入器真 `startInjecting` 才让位——在位≠覆盖），与本机其他 SQL 插件共存不打架
- **mapper XML 高亮/补全/导航差距收敛（T-XmlGap，O-144）**：在 T-XmlSynHi 骨架之上把粒度与覆盖面补齐——`#{}` 与 `${}` 分色、`${}` 内与属性值内 OGNL 细着色、表名 / 列名 / 运算符着色，关键字集统一并读 `Dialect`（11 方言）；补全扩面至 `#{}` 结构属性（jdbcType / javaType / typeHandler / mode…）、属性值全覆盖（id / refid / resultMap / resultType / parameterType / type…）与 resultMap 引用列；新增颜色设置页、mapper 语句动态标签折叠（foldingBuilder）与**引用导航**（跳转与查找用法）；`mybatis-config.xml` 配置项补全；共存让位由「宿主级」收紧为**区间级互斥**（只让出对方真正占用的着色区间，其余照常着色）
- **SQL 高亮分组开关（R2）**：设置页可按语句来源分组控制 gutter SQL 图标显隐——mapper XML / 注解 SQL（@Select 族）/ JPA·HQL 族 / JdbcTemplate 字符串 / 通用 XML 内嵌兜底 / Wrapper 链及其它，默认全开（现行为不变）
- **历史条目钉住与正则检索（R15/R16）**：历史 SQL 条目可钉住防淘汰；二级搜索支持 `regex:` 前缀正则匹配（SQL/摘要/源文件三目标，非法正则回退子串）
- **历史/日志导出（R4）**：历史面板与日志面板右键「导出」CSV / TSV / 纯文本（行集与面板当前可见一致，后台写盘不卡 EDT）
- **导出 SQL 文件格式（T-ExportSqlFormat）**：导出格式由三选扩为四选且默认 SQL——参数已回填、逐条注释头（time/source/duration）＋分号终止的可执行 `.sql` 脚本，可直接在数据库客户端执行；默认名 `sql-export-<时间戳>.sql`
- **抽样 LIMIT 可配置（R5）**：快捷模板「抽样」行数设置页可配（默认 10，钳制 1..10000）
- **分页拦截器模拟（R12 T-PaginationSim，默认开）**：mapper 方法/XML 路径带 `IPage` 形参的查询此前预览不分页——现按方言在 SELECT 尾追 LIMIT（参数取 `(当前页-1)×页大小` 公式，手写 LIMIT 不重复追加），并并列产出 COUNT 包裹语句（`SELECT COUNT(*) FROM (…) TOTAL`）；Wrapper 链 `selectList/selectMaps/selectMapsPage` 的 Page 构造器 `searchCount` 布尔同步识别
- **分页 COUNT 独立开关（T-CountSimToggle）**：COUNT 与 LIMIT 解耦——设置页在「模拟分页拦截器」下一行新增子开关（默认 true 保持现状）；父开关关＝全无（父优先），子开关只关 COUNT、不影响 LIMIT
- **MCP 第十一工具 sqlv_history_search（R14 T-HistorySearch）**：AI agent 可检索本地 SQL 历史落盘——关键词/正则/时间范围/来源文件四维过滤，默认只查当天（`allDates` 跨全量），返回条目摘要（最新在前，maxResults 默认 20 上限 100），只读不执行；agent 使用指南见 `docs/mcp/MCP-Tools-Agent-Guide.md`
- **预览参数预设（R22 T-04）**：参数区新增「预设」行——当前填好的参数可**另存为预设**，下拉一键切换（参数值/OGNL 勾选态/foreach 权威值随预设整组还原），「管理预设…」对话框支持另存为/重命名/删除（默认预设受保护）；**命名预设＝快照语义**（日常编辑只更新工作区，命名预设只在显式写入时改动）；**预设按语句隔离**（下拉只列本条语句的预设，同名预设可在不同语句下各自存值）；预设行可折叠收起、**缺省折叠**（折叠状态跨语句/跨弹窗保留，重启复位）
- **Oracle / SQL Server EXPLAIN 接入（R23，EXPLAIN 支持面 11/11）**：Oracle 走 `EXPLAIN PLAN FOR`（计划登记 PLAN_TABLE，插件内以诚实提示说明查看方式）；SQL Server 走 `SET STATISTICS XML`（官方文法级形态，真机待验如实留账）
- **EXPLAIN 结果结构化渲染（R33，实验特性，默认关）**：MySQL 表格 / PostgreSQL JSON 计划可在预览面板内直接渲染成表格/可折叠树（带方言/数据源/耗时头部与索引建议）；默认关闭——EXPLAIN 默认交原生数据库控制台执行与呈现，设置页显式开启即恢复结构化通道，执行失败或环境不满足自动回退
- **原生 MyBatis 拦截器自动注入补完（R24）**：设置页「注入」对 `mybatis-config.xml` 工程此前只给手动指引——现自动检测配置文件 `<plugins>` 装配同一拦截器（无 config/多处 config/结构不符一律不注入维持引导，fail-closed）
- **动态 SQL 条件求值探镜（R30）**：XML 条件行（`<if>`）旁标注本轮预览的求值结果三态（真/假/未求值）；纯只读探针，渲染行为零变更
- **历史/版本 SQL 对比（R31）**：历史面板多选恰两条右键「对比选中两条」——逐行差异＋结构指纹判定（同指纹提示「结构等价，仅参数不同」），IDEA 原生 Diff 窗口呈现
- **数据源上下文角标（R32）**：SQL 条目角标从纯方言显示名升级为「方言 · 库名@host」（从 JDBC URL 解析；支持 Oracle service_name/SID 双形态与 SQL Server 分号参数；凭据信息不入任何展示）
- **N+1 同模板计数徽章（O-90）**：日志/历史面板对同结构模板的 SQL 计数，≥3 条显示「×N 同模板」琥珀徽章；右键可聚焦该模板全部条目（不做实时 profiler）
- **CRUD/回滚 SQL 可移植导出（O-91 缩范围首版）**：预览面板「更多」菜单新增「导出可移植 SQL」——当前 CRUD 语句与回滚语句串联输出，每条前注释标注方言/种类/UPSERT 形态（回滚链对无法生成的形态诚实跳过不造半成品）
- **日志转 SQL 剪贴板读取按钮（O-92）**：工具窗「日志转 SQL」输入栏新增「从剪贴板读取」——一键贴入并立即转换（有内容才启用，空剪贴板轻提示）
- **设置页与面板可发现性三处（P1-3/P1-4）**：MCP 设置页顶部新增工具面就绪横幅（工具数与门控状态一眼可见）；SQL 日志空面板引导补「增强捕获」第三径；帧过滤前缀入口在 SQL 日志设置子页顶部加指向说明
- **落地页价值展示（R20）**：设置首页新增三行能力矩阵与官网链接
- **引导通知动作化（R21）**：首次引导通知新增「立即试用」按钮，一键直达预览
- **预览列信息表恢复 Ctrl+A 全选（O-85）**
- **ShardingSphere 双段日志捕获（R10，解析器骨架默认开）**：控制台通道新增第四解析器——`Logic SQL:` 作主条目、`Actual SQL:` 逐行折叠为附注（数据源路由可见）
- **语句级织入 attach 即生效（T-StatementWeave）**：钩子挂在驱动语句实现类 execute 族方法体（入口+出口双插桩），attach 时对已加载语句类 retransform——**存量对象的下一次执行即带钩子**，覆盖池化场景启动期已建好的连接；`prepareStatement` 出口登记「PS 实例 → SQL + URL」弱映射；已知边界：attach 前已 prepare 且未再执行的 PS 不上报、织入通道无 setXxx 拦截故存量连接语句参数一期为空
- **日志→SQL 页灰字提示与搜索历史（T-LogToSqlHint）**：日志→SQL 页恒显灰字占位提示；搜索历史接管为面板自有会话历史
- **空态/引导链接点击定位设置项（T-SettingsFocusFromLink）**：日志页空态与引导链接点击后自动定位并高亮目标设置项（复用既有 focusTarget 机制）

- **历史检索正则/大小写开关（T-HistorySearchToggles）**：历史面板搜索框旁新增 Regex / Aa 两枚开关（与 IDE 原生查找同图标同语义，会话级、默认均关）——Regex 开启后整串按正则匹配（`regex:` 前缀保留，或语义不回归），Aa 开启后区分大小写（默认关=忽略大小写）；日志页 Ctrl+F 搜索条同步新增单枚 Aa 开关（随搜索条显隐）；历史一级文件名检索随 Aa 开关（正则不参与），行号 `#N` 精确匹配不受影响。行为变更声明：正则路径从恒大小写敏感改为随开关（默认忽略大小写，与子串路径/日志页/MCP 链三链拉齐）；顺带修掉正则编译 memo 键不含大小写旗的存量隐患（切开关后复用错编译结果）

### Changed

- **增强捕获宿主诊断收敛**：移除 Spring 代理健康调用也会反复打印的「入口键非业务帧」摘要；首条织入事件日志由 SQL 片段改为仅记录 SQL 长度，不再把业务文本写入宿主诊断行。需要栈帧详情时仍可开启 agent 的 info 日志
- **SQL 快捷模板三动作改为「光标压在表名上也能用」（T-SqlTemplateCaret）**：EXPLAIN 选中表 / COUNT 选中表 / 抽样 10 行此前必须先划出非空文本选区才可用，光标停在表名上（不划选区）时动作是灰的。现启用条件放宽为「非空选区 或 光标处标识符」：有非空选区时逐字节原样取选区文本（既有行为不变），无选区或选区为空白时退化为取光标处的标识符（表名/片段），两路都取不到才禁用。光标退化走 `Document.getCharsSequence()` 的词边界扫描，不依赖具体语言与 SQL 注入是否生效，故在 mapper XML、`.sql`、Java、预览弹窗文本区等任意编辑器里都成立；标识符带反引号/双引号/方括号时连引号一并取（与选区路径「原样当表名」一致），`db.tbl` 这类限定名跨点取全，单引号字符串不带单引号（`FROM 'x'` 非法）。不改动作语义（仍不做表名校验）、不改 P2 的失败通知接线、不动执行链
- **描述与文案口径统一：运行期以增强捕获为主、控制台为回落**（配套文档站首页描述改版）：`plugin.xml` 描述把原先并排的两条（「运行期捕获 · 控制台 SQL 日志」＋「增强捕获」）合并为一条「**运行期捕获（默认开启）**」，补入慢查询标红与静态性能建议、调用栈悬停弹层逐帧跳转，控制台三族日志降为「不改工程的回落通道」（中英同段同改；数字类声明未动，结构守卫对账面不变）；README 顶部一句话简介改为「写代码时就看见最终 SQL……跑起来后真实参数与耗时自动捕获，慢查询标红、EXPLAIN 看计划、一键跳回源码……不启服务、不翻日志、不手拼参数」，「运行期」段落同步改为增强捕获前置；Marketplace 自定义页**两版同步**（`src/main/resources/html/marketplace-custom-page.html` 供 IDE 帮助按钮 JCEF 渲染、`docs/发行说明/marketplace-custom-page.md` 供后台粘贴）——「为什么选它」的增强捕获条前置为「运行期捕获（默认开启）」、控制台降为「控制台日志（回落通道）」，标语盒「运行期日志还原」改「运行期捕获真实参数与耗时」。**md 版需在 Marketplace 后台重新粘贴**（两版唯一权威在本仓，见发布流程 §10.2/§11）。
- **README 的 EXPLAIN 方言计数勘正**：原文「EXPLAIN 执行计划（9 种方言，8 种真机 / 引擎实证、OceanBase 文档级验证）」为过期读数——`ExplainSupport` 实为 **11 条**（新增 Oracle 真机实证 Free 23 / XE 11g、SQL Server 按 Microsoft Learn 文法落地·真机待验），改为「覆盖 11 种方言（9 种真机 / 引擎级实证、OceanBase 文档级验证、SQL Server 按官方文法落地待真机）」。文档站 `wrapper-preview` 与 `faq` 中英原写「Oracle / SQL Server 暂不支持 EXPLAIN」同步勘正（该表述自两方言接入后即失真）。

- **数据源上下文角标默认仅显示数据库类型（T-DataSourceBadgeMode）**：SQL 日志/历史两页的目标库角标此前恒显全量「方言 · 库名@host:port」，条目元信息行信息密度偏高。现默认仅显示数据库类型（如 `[MySQL]`）；设置页「SQL 日志 → 数据源角标显示完整上下文（库名@主机:端口）」打开后恢复全量形态。门控做在显示侧——生产/落盘/历史读回/MCP 检索链零改动，开关即时生效（含已读回的历史条目），后开开关不丢任何信息；方言解析失败仅存连接事实的条目关闭态原样保留

### Fixed
- **增强捕获接管瞬间同一次执行留下两行（T-AgentCaptureProvenance）**：旧接管沿清空整个去重索引，使接管前几毫秒入库的控制台条目与随后到达的 agent 观测无法融合，出现一行可跳转、一行缺源码。现仅清除陈旧键，保留近五秒内的新鲜键用于字段级融合；首批 agent 事件仍不会被旧记录吞掉
- **历史与 XML 编辑性能收敛（T-PerfSweep25）**：历史日志面板由「打开即后台扫描历史文件」改为首次切入「SQL 历史」页签才触发首扫；空查询的同模板聚合只随条目快照换代重算；resultMap 列补全加 100K 熔断及双槽 memo、XML 高亮共存探针加宿主弱身份／PSI 修改戳／注入器清单 stamped memo（非 mapper 宿主探测前置跳过，F79）、SQL 切分器前视加 10K 窗上限（F90），resultMap 贡献器补 DumbAware（F93），避免大文件 / 无配对 BEGIN 等形态拖慢编辑响应

- **`@Embedded` 展平列的预览列名丢了嵌入段前缀，与真库列名不符（T-EmbeddedColPrefix）**：实体 `@Embedded private WmsInventoryQty qty;` + HQL `inv.qty.baseQty` 被预览成 `AND inv.base_qty > 0`，而真库列是 `qty_base_qty`（同句 `inv.moveTool.palletNo` 同样被预览成 `inv.pallet_no`）。根因是展平嵌入列时把「列名 = 内层字段名」写死（`@AttributeOverride` > 内层 `@Column(name)` > 驼峰转下划线），而列名怎么拼其实由工程的 Hibernate 隐式命名策略决定——`ImplicitNamingStrategyComponentPathImpl`（配置关键字 `component-path`，Hibernate 4 的 `DefaultComponentSafeNamingStrategy` 同源）会把属性路径并进列名。现已按 hibernate-core 源码核实的语义支持这一约定：默认自动读工程 `application*.yml/properties` 判定（判不出按 JPA 默认「无前缀」），设置页「嵌入列命名」亦可显式指定「无前缀 / 组件路径前缀」；两档都只作用于兜底档，显式 `@AttributeOverride` 与内层 `@Column(name)` 依旧优先。同一处列名推导同时喂 WHERE 段属性映射与 SELECT 子句展开，故一处修正、两处产物同时正确。另补一条不依赖配置的兜底：既有的「按数据源校正列名」此前对嵌入列失效（它按归一化比对，`base_qty` 与 `qty_base_qty` 差的是一整段前缀，必然 0 候选），现对嵌入列在 0 命中后追加「用嵌入段路径的 snake 形态唯一命中」这一档——绑定了数据源时这类场景零设置自愈，0 候选/歧义/当前列名本就是库中真名时一律不改写；嵌入列命名的配置探测按**实体所在模块**取范围（命名策略是每个持久化单元各自的事），多模块工程里兄弟模块的配置不会外溢
- **用 `StringBuffer`·`StringBuilder` 逐句 `append` 拼出的 SQL 传给自定义 DAO 时无法预览（T-HqlBufConcat）**：把 HQL 累积在 `StringBuffer sb` 里（`sb.append("SELECT …")` …）再经 `sb.toString()` 传给自研 DAO（如 `commonDao.findByQueryMaxNum(sb.toString(), new String[]{}, new Object[]{}, 0, 10)`）时，该行既不亮预览图标、手动触发动作也只弹「未找到可预览 SQL 源」。根因是定位器只认字面量 / `+` 拼接 / 变量引用三种承载，而 `sb.toString()` 是方法调用表达式、落进「无法静态取得字符串」的兜底返回 null，内容启发失败即不认领（与 DAO 实现是否在 jar 里无关——认领走 qualifier 后缀 + 方法名关键字 + 内容正则，全程不解析 DAO 实现体）。现把 JdbcTemplate 通道已在用的 StringBuilder 聚合器 `SqlStringConcatenator` 提为共享入口（并补 `.toString()` / `.append(x).toString()` 解包），自定义 DAO（`CustomDaoHqlLocator`）与 Hibernate `session.createQuery(...)`（`HqlQueryLocator`）两条通道共用；拼接中嵌入的运行时值（`param.get(...)`）沿用既有 `/*运行时值*/` 占位降级。顺带收敛两处同族口径：`HqlQueryLocator` 的兜底不再把表达式源码文本当 HQL（对齐 T-GutterDupFreeze），`handleNewExpression` 只认大写 `NEW` 的问题改为大小写不敏感的单一实现 `NewExpressionStripper`（小写 `new Map(...)` 此前会被原样留在产物里产出非法 SQL，「`RENEW(`」之类不再误命中）
- **SQL 快捷模板三动作执行失败只剩一条纯文本气泡，预览面板同款错误入口缺失（T-SqlTemplateUx P2）**：从编辑器右键「SQL 快捷模板」触发 EXPLAIN / COUNT / 抽样失败时用户只拿到一条警告通知，而预览面板「执行 SQL」失败会给出「定位到出错语句」「复制错误信息」两个可操作项——同一条执行链、同一次失败，两条入口的反馈档次不一致。现给快捷模板失败气泡挂上同款两个动作，文案复用面板既有 key 保证措辞一致；定位锚点取触发编辑器选区起始行（即语句来源），解析不出时不挂定位、退化为只给复制，不虚标可点的定位。不改执行链、不改动作启用条件（仍只认编辑器非空选区）、不改「选中文本不做表名校验」的口径
- **同一会话首次点「执行 SQL」时预览窗内看不到结果、再点一次才有（★A48）**：DBN 新建结果页签时会把结果区组件重新挂回原生宿主 `ServiceViewComponentWrapper`，同一实例、仅 parent 变；而结果换入去重只比较组件身份与页签名称集合，re-parent 两者都不变，判据恒 false，SplitPane 底部滞留空组件，结果只落到 IDE 底部原生控制台。复用旧页签路径不新建内容、不触发挂父，这正是「第一次坏、第二次好」的成因。修法：换入判定补第四判据「组件已不在本 SplitPane 底部」，每拍按仍在位做幂等回收重挂，强制换入时并从外部宿主显式摘出而不再依赖 Swing 隐式改父；既有三条判据、错误上报、dumb 宽窗口与 swapGeneration 语义均不变
- **无嵌入宿主执行路径不再摘取原生控制台的结果区组件（★A49，顺带收敛，非症状修复）**：SQL 快捷模板（EXPLAIN / COUNT / 抽样）与「日志转 SQL」的「执行」都走 `SqlExecutor.execute(onConsole = null)`——只把语句交给原生 Database 控制台执行，控制台由 DBN 在 IDE 底部 Services 视图自行展示。此前两处组入点都会把 DBN 结果区组件（`ui.getComponent()`）从原生宿主 `ServiceViewComponentWrapper` 摘进一个**永不展示**的容器（无 `onConsole` 回调、`pendingOnConsole` 也为 null 故 `refreshResultsComponent` 直接早退、无人回收）：准备链 `prepareAfterEditorResolved` 的 `buildConsoleSplitPane`、复用链 `reuseSharedConsole` 的 `swapResultIntoPersistentConsole`（强制换入会 remove 外部宿主 + 设 bottomComponent）。这两处摘取对本路径**无任何收益**，只扰动 DBN 自己的组件树（属 ★A48 同族现象）。现组入判定抽出纯函数 `shouldComposeEmbedSplitPane`，两处组入点同判据 gate——**只在存在嵌入宿主时**才组/换入，无宿主路径不组不摘、保持原生渲染；嵌入/预览路径（`onConsole != null`）判据化简后与改前逐字等价。**归因更正**：本项立项时曾据「录制里原生控制台结果网格停在 `0 rows`」怀疑是插件摘取所致，后经沙盒控制台 **Output 页原文**查明该现象真因是**调用方传了不存在的表名**（录制脚本右键锚点按单宽估算 CJK 列宽、落到 `schema.sql` 注释里的中文词上，动作据其拼出 `SELECT … FROM 对齐插件测试`，DBN 正确报 `[42S02][1146] Table 'mpj_demo.对齐插件测试' doesn't exist`，网格 0 rows 是该报错的正常表现，**与插件行为无关**；锚点 x 610→740 修正后重录一次通过、网格 4 行）。故本项是排查该现象时顺带发现并收敛的隐患，**不是该现象的修复**；保留理由＝无宿主路径摘取原生组件无收益、有扰动风险，去掉后更安全。另顺带修正 `persistent console bottom swapped` 取证日志（旧实现在设 bottomComponent **之后**才读 parent，恒打印本 SplitPane，证明不了「有没有摘走」；改为改动前取真实宿主全类名并加 `fromExternalHost` 分量），并新增两条**只读排障日志**（`[M36][A49] result host at submit: …`、`[M36][A49] submit diag: newTab/execInside/execOutside/execSelection + viewShowing + editorCompParent`）——排障工具、非功能改动
- 调用栈悬停弹层视口按全量帧数放开（JList 默认 8 行把链尾业务帧折叠进滚动区，「默认锚定最深业务帧、一眼看到 Controller → Service」落空）
- 调用栈弹层锚点跟随悬停落点（原只在 mouseEntered 赋值边缘穿越点，弹层位置随进入方向漂移）
- 实体选择弹窗（SQL→Java / CRUD 预览的「选择要预览的 SQL」）候选副标题改为完整限定类名——原先只显示文件名，不同包/模块的同名实体（如多模块工程的两处 User.java）无法区分
- 「未在光标处找到可预览 SQL 源」提示文案动态化——旧文案只提 MyBatis-Plus Wrapper/IService，未覆盖插件现已支持的 mapper XML / 注解 SQL / MPJ / JPA / Hibernate / JdbcTemplate 等类型面，指引失真
- 帮助按钮打开的在线文档地址改用 canonical 域名与无后缀路径（原 `mybatis-plus-sql-visualizer.jvtools.cn/…/wrapper-preview.html` 实测要走两次跳转：旧域名 301 → 新域名、`.html` 308 → 无后缀；canonical 地址直接 200）
- JOIN 图标签页在**无动态条件的联表链**上不出现（MPJ `selectAll + leftJoin + fill` 这类链没有 `eq/like`，`previewModel` 为 null 而图判据原先只认它——预览出 SQL 却没有「JOIN 图」页签；带条件的同结构链才有）——新增 `PreviewResult.diagramStatement` 独立载体，图判据与「是否走模型级重渲染」解耦，旧渲染路径零改动（JPA 联表派生查询同款缺口一并覆盖）
- 参数回填在**切换逻辑删除 / 多租户注入开关**后丢失（历史缓存键原为 `表名:SHA-256(SQL 全文)`，拦截器模拟会改写 SQL 文本——键一变召回 miss，已回填的实值退回占位符，防抖后新键还会以空值覆盖落库）——新增 `PreviewResult.historyFingerprintSeed` 承载**注入前**语句文本，面板算键优先用它
- `deleteAllInBatch()` 这类 bulk 写路径在 **Repository 接口声明行**光标下无法预览（方法名不含 `By` 且 `*All` 词干白名单不放行 `InBatch` 结尾 → 派生查询线不认；无 `PsiMethodCallExpression` → CRUD 转换线不认；模板本身是完整的，只是两条通道都够不着）——新增声明行入口 `JpaCrudOperationConverter.convertRepositoryDeclaration` + `SqlPreviewService.previewRepositoryDeclaration`，动作回落链补第 5 步
- JOIN 图主表节点字段没带数据库 COMMENT（多数据源下跨库同名表：`@TableName("\"user\"")` 的主表被注册序在前的 `jpa_demo.user` 撞上——那张同名表 9 列且注释全空，而副表只有本库有、解析正常）——整图富化改为**句内同来源**批量取数：先逐表按名解析，再按「各来源持有本句表的数量」投票选主来源，少数来源持有的表若主来源也有则改判给主来源（主来源没有的表仍逐表回落，跨库联接不受影响）
- **MPJ 联表 ON 两侧列的别名改为按列自己的实体类解析（★A47）**：`rightJoin(AreaDO.class, AreaDO::getId, AddressDO::getAreaId)` 此前渲染成 `ON areaDO.id = t.area_id`（右侧列一律当主表），而 MyBatis-Plus-Join 真机发出的是 `ON areaDO.id = addressDO.area_id`——**预览 SQL 与真实执行的 SQL 不一致**，该句在真库上还直接报 `[42S22][1054] Unknown column 't.area_id' in 'on clause'`（录制演示动图时实测）。已对齐 MPJ 1.5.8 语义（`JoinAbstractLambdaWrapper.columnToString` 先取 `LambdaUtils.getEntityClass(column)` 再按该类查前缀，与「是不是主表」无关）：三参 / ext / 别名+ext / 子表 MConsumer / `on(col1, col2)` / lambda ON 六种形态的 ON 两侧列统一按列自身实体类在链内别名表中解析，**识别不出接收者实体时逐字节回落到原行为**，故常见写法（ON 右侧列就是主表字段）渲染结果不变；显式右别名 `leftJoin(Class, 左列, "右别名", 右列)` 仍以显式值为准
- **空态引导英文链接文案大小写回归修复**：`log.panel.empty.link` 英文由 `Open Settings (jump to Enhanced capture)` 回归为 `Open Settings (jump to Enhanced Capture)`（与同 bundle 其余三处 EN 文案一致，修复 `EmptyTextRenderTest` 三径守卫红）。同批修 `DataSourceJdbcSmokeTest` 两处环境门控失效——`assumeTrue` 在 JUnit3 基类 `BasePlatformTestCase` 下抛出的 `AssumptionViolatedException` 被平台 runner 记为 failure 红任务（`DbConsoleIntegrationTest` 已有同款定论），改为「显式绿跳」（`println` + `return`），并把驱动不在测试 classpath 的数据源（demo 的 DM 源）逐源跳过；对驱动可加载的数据源保留连通性与可执行性断言
- **增强捕获织入改写致宿主应用启动失败（P0）**：连接/语句织入器原先用 ASM 默认 `COMPUTE_FRAMES` 重算原方法体栈图帧，其类型公共父类解析按 `Class.forName` 加载类型，而织入回调正运行在**被织类自身 defineClass 进行中**，同线程重入 `loadClass` 时 `findLoadedClass` 看不到进行中定义 → 二次 defineClass → JVM 判重复定义，宿主启动即抛 `LinkageError: attempted duplicate class definition`（Spring Boot 2.6.3 / JDK 8u181 / mysql-connector 8.0.28 现场实录）。修复：两织入器改仅 `COMPUTE_MAXS`（注入块为零分支直线，无新帧合并点）+ `EXPAND_FRAMES` 读入并给织入成功者每帧 locals 尾部补 token 槽 + 新增 `ZeroLoadClassWriter` 零类加载保守实现（防未来重挂 `COMPUTE_FRAMES` 复发）；后续二次修复按**槽数**（long/double 占 2）补齐 TOP 到 token 槽位再追加，并新增「织入产物必须在隔离 ClassLoader 里 define+initialize」回归（旧补丁 3/3 必红）
- **mapper XML 补全 dumb 态整体丢弃（T-XmlCmpl2）**：`XmlSqlCompletionContributor` 未实现 `DumbAware`，索引期（数据库方言 push / 项目初扫等 dumb 窗口）平台 `DumbService.filterByDumbAwareness` 把一切非 DumbAware 贡献器整体丢弃，按补全零响应；同包两兄弟贡献器均已实现 DumbAware。修复＝实现 DumbAware（索引不可用时降级为「只补关键字」）；另修沙盒失效双根因（派发劫持双语言注册 + 宿主还原生产形态）
- **mapper XML 补全：`where` 内裸列零候选 / 别名.列名插入重复（T-XmlCmpl3）**：`where` 内裸列补全零候选（动态标签语义替身 `stripXmlTags`）；别名.列名补全插入重复别名（`o.o.user_id`）
- **attach 跨通道去重吞掉 agent 首批事件（T-AttachZeroEvent）**：增强通道与控制台通道的去重索引未随通道切换重置，agent 挂载后的首批事件被跨通道去重吞掉；现接管时按通道纪元重置去重索引
- **日志 DDL 预览两缺陷（T-DdlLogPreview）**：①字面量恢复此前对零 `?` 的 DDL 也全量改写单引号、占位符化致 DDL 不可执行（`COMMENT '主键'` → `COMMENT ?`）——现加语句类型门禁（只对 `QUERY`/`DML` 恢复，DDL 与 UNKNOWN fail-closed）；②DDL 格式化此前无换行点、点「格式化」毫无变化——现按首关键字分派 DDL 排版（**DML 输出逐字节不变**）；③格式化零变化时给 INFORMATION 轻提示
- **增强捕获重开对 premain 进程 attach 握手失败（T-PremainReattach）**：关增强后 claimed premain 通道转暂停待重连（监听保留、重连 backlog 停泊）；attach 装载进程同病 → 端口登记 pid，恢复时按上升沿在原端口 re-bind（`SO_REUSEADDR`）接住回连
- **日期/时间类型参数还原改 ANSI 类型字面量（R3）**：日志还原链对带日期族类型标注的参数此前渲染裸单引号字符串，在 Oracle 上对日期/时间戳列隐式转换恒失败（ORA-01861/01843）。现按类型标注渲染 `DATE '…'` / `TIMESTAMP '…'`（ISO `T` 归一为空格、缺秒补 `:00`、不匹配回落引号），经多引擎真库逐格实测通用，无需方言分支
- **沙盒验收批缺陷修复（T-DevAcceptFix / T-DevAcceptFix2）**：分页预览两条语句补分号（LIMIT 与 COUNT 粘连）；预设切换参数回写错位（改按输入框 source 锚点显式映射）；预设按语句（方法）隔离；SQL 屏蔽管理对话框打开即 NPE（`createdAtFormat` ThreadLocal 声明位置排在 `init` 之后）；EXPLAIN 直送通道在 EDT 读盘命中慢操作断言；捕获诊断行补「已收事件 N 条」与「屏蔽规则命中 N 条」；XML 预览下参数区「清除」按钮失效；左栏分区按钮文字发虚（非整数磅值派生字体）；MCP 设置页就绪横幅；mapper XML 高亮在 Ultimate / 三方插件环境零着色（让位改宿主级探测）；注入器模板 javac8 编译；历史徽章渲染（`ExpandedItemListCellRendererWrapper` 包装渲染器）
- **性能修缮五件（F47~F51，批次 9 快件批）**：①语法高亮注入器喂入税消解（每元素一次服务查找改快照直读，实测 85→14ns/元素，喂入位查找 3002→0）；②mapper 解析器编程式注册挂应用级 Disposer 宿主（插件动态卸载容器级联摘除）；③参数预设切换只写激活指针不再全量序列化＋方案数上限 20；④两处并发缓存修缮（参数方案顶层结构改并发安全容器；控制台桥方法缓存负缓存此前因 null 值被吞从未生效，改 Optional 承载并补 ClassLoader 身份键）；⑤XML 内嵌 SQL 行标记两道闸并一（每元素查找 2→1，语义逐值等价）

- **增强捕获对运行中的达梦（DM8）应用 attach 后零事件（T-DmWeave）**：用户现场应用（mybatis-plus-join-demo）实际跑 dm profile（`spring.profiles.active: dm` → `jdbc:dm://…`，driver `dm.jdbc.driver.DmDriver`），开启增强捕获后面板持续零条目。根因同 PG 一族但多一层：`StatementWeaver.SPECS` 语句面/prepare 面**完全没有达梦类**（connect 面虽登记 `DmDriver`，attach 路径下存量池连接无法回溯 connect 包装），retransform 名单命中 0、无钩子可落。补登时又暴露织入器一处硬约束：达梦 javac 在出口处对已死局部发 `chop_frame`（`DmdbStatement.execute(String)`、`DmdbPreparedStatement.execute()`、`addBatch(String)`、`clearBatch()` 的出口帧 `locals[0/1]=top`），旧织入形态（出口块读原局部 `this`/首参）字节级 `VerifyError: Bad local variable type`——即使登记也登不上。现改造定时面织入为**入口捕获、出口读自有槽**：方法头把 `this`（EXEC_PARAM 再加首参 SQL）存进超出原 maxLocals 的新槽，出口块一律读自有槽，栈图帧补丁按槽位追加 token(long)/本类类型/String；由此达梦 `DmdbStatement`/`DmdbPreparedStatement` 的 timed 面（EXEC_PARAM/EXEC_CTX/BATCH）与 `DmdbConnection` 的 prepare 面（PREPARE）通过 JVM 校验并落钩，`attach retransform` 由 `matched=1 wove=0 failed=1 [dm.jdbc.driver.DmDriver=VerifyError]` 变为 `matched=4 wove=3`，真库 E2E 事件到达（`sqlHead=SELECT 1 AS attachpool_dm`，`registryUnresolved=0`）。达梦 `ADD_BATCH`/`CLEAR_BATCH` 面出口非 timed（仍读原局部）本轮不登记（同 chop_frame 根因）；达梦 connect 面 `DmDriver.connect` 出口命中同一 chop_frame（残留 `failed=1 [DmDriver=VerifyError]`），因存量连接场景已由语句面/prepare 面覆盖，本轮未改 ConnectWeaver、如实留痕。同批加固：语句面/prepare 面字节级回归守卫扩达梦三面 + 新增**厂商对齐守卫**（连接面已登记的每个厂商，要么在 SPECS 有语句面/prepare 面条目，要么在显式「已知不覆盖」白名单带原因），把「看着支持、attach 后一条不出」的静默缺口钉成红灯

- **增强捕获对运行中的 PostgreSQL 应用 attach 后零条目（T-PgWeave）**：对已在跑的 PG 应用挂载增强捕获后，面板持续零条目（去重窗远未到、事件从未到达 IDE）。根因是语句级织入的登记名单（`StatementWeaver.SPECS`）只覆盖 MySQL 与 H2——attach（agentmain）路径下连接池的存量连接无法回溯 connect 包装（connect 面只包新建连接），语句面又没有 PG 实现类可 retransform，钩子无处落地即零事件（premain 路径因连接建立时已被包装、走代理面，此前 PG 能捕获，掩盖了该缺陷）。现按 javap 实测 + 真实驱动类字节织入/JVM 校验批量扩面：PostgreSQL 语句面 `PgStatement`/`PgPreparedStatement`/`PgCallableStatement` + prepare 面 `PgConnection`（openGauss 2.x 走 PG 驱动同享）；同批评估后一并登记 Oracle（`oracle.jdbc.driver.OracleStatement`/`OraclePreparedStatement`/`OracleCallableStatement`，prepare 面 `PhysicalConnection`）与 SQL Server（`SQLServerStatement`/`SQLServerPreparedStatement`，prepare 面 `SQLServerConnection`）。新增驱动类字节级回归守卫（登记谓词命中 + 织入 changed + 产物过 JVM 校验，含非法帧负向自证）钉住形态，mysql/h2 原用例零漂移。达梦经同一探针暴露织入器帧-局部变量既有局限（出口 `chop_frame` 与出口块读局部变量冲突，VerifyError），本轮如实不登记；该局限已由后续 T-DmWeave 以「入口捕获、出口读自有槽」改造解决并登记达梦 timed/prepare 面

- **预览首次点「执行 SQL」结果区空白、二击正常（T-FirstExecResultInvisible）**：打开预览的准备链（远程数据源需数秒）未落地时点执行，旧代码会再建第二条临时文件/会话/控制台链，两条链的执行代际递增与提交/取消判定交错——提交被取消或结果组件被预览链换掉并折叠，用户见「无 loading 无报错下方空白」。现准备链单飞：在飞时执行意图只排队（不再建第二链），准备完成即按 executePrepared 口径自动消费执行（onResultsReady 齐、错误回调归执行意图自身，排队执行失败不再静默卡死）；另把控制台内嵌的折叠语义从 previewMode 布尔归口为显式 embedMode（EXECUTE 恒展开，PREVIEW 仅结果区无内容时折叠）——旧形态全链执行的换入也带 previewMode=true 被折叠，是同一空白现象的布局侧共犯。单飞位悬挂超 60s 自动接管自愈，执行按钮不会因历史挂死族永久排队

- **个别增强捕获条目源码位置显示 `[<generated>]`（T-SrcGenFrame）**：增强捕获下经 Spring 代理调用 MyBatis-Plus `ServiceImpl` 继承方法（`list`/`getOne`/`saveBatch` 等，真实方法体所在类被框架前缀跳过）时，栈上最深非框架帧是 CGLIB 增强类帧（`$$EnhancerBySpringCGLIB$$` 族）——其 SourceFile 属性是 Enhancer 字节码硬编码的 `<generated>`（spring-core javap 实证）、行号恒 -1，而类名前缀即业务包名、codeSource 随宿主类落业务目录，agent 侧 `StackLocator` 的 L1 来源判据与上顶收敛都把它当业务帧选中，面板遂渲染 `[<generated>]`（真机实证：同批条目直调 mapper 接口的拿到真实业务行、经代理调 Service 继承方法的全部中招）。现主位置选择（L1/上顶收敛/判定缓存命中深帧探测/候选列）一律要求真实源文件，生成类帧让位给池中真实业务帧（=触发本次调用的调用方行）；仅池内无任何真实源文件帧（调度器直发/框架回调等真无主形态）时安全网原样采用，`[<generated>]` 保留作真无主信号

- **预览编辑器行注释前缀统一为 `--` 双横线（T-PreviewLineCommentDash）**：预览/控制台编辑器内 Ctrl+/ 此前插入 `#` 前缀——预览文件被方言预映射强制为 MySQL 方言，平台注释动作经 commenter 链取到 MySQL 的 `#`，对 PostgreSQL 执行报 `[42601] syntax error`。现对预览自建编辑器与原生控制台编辑器接管行注释快捷键：插入一律 `-- `、再按一次剥除，修复前残留的 `#` 行同样可剥（缩进保留、多行选区整段切换、一步撤销）；未打标编辑器直通平台原行为零变化，块注释 Ctrl+Shift+/ 不接管（MySQL/PG 语义本就一致）
- **原生控制台结果组件无收益摘取收敛（★A49）**：SQL 快捷模板及「日志转 SQL」的执行没有嵌入宿主，此前仍从 DBN 原生宿主摘取结果组件；现只在有嵌入宿主时组装及换入，避免扰动原生控制台。录制中出现的「0 rows」实为调用方传了不存在的表名，与该改动无关
- **SQL 快捷模板失败反馈偶发静默（T-SqlTemplateUx P2）**：EXPLAIN / COUNT / 抽样三动作的晚检失败会被宿主门控挡住，气泡与「定位到出错语句 / 复制错误信息」两个反馈入口都不出现；现失败链在门控后正确送达反馈面板
- **增强捕获「已挂载」气泡重复出现**：同一项目会话内多次触发挂载提示会反复打扰，现收敛为每项目会话一次

## [2026.10.9] - 2026-10-09

### Added

- **SQL 历史标签页常驻（T-HistoryTabAlways）**：历史标签页此前是条件页签——只有「开启自动保存」或默认历史目录下已有 `.sql` 文件时才挂载，且设置变更时会**把已挂载的页签摘掉**，于是未开启自动保存的干净项目里用户根本看不到历史入口。现双门禁整体拆除、构造期无条件挂载，空态沿用既有文案、零新增 UI；顺带修一处既有缺陷：历史文件的读端只认默认 `.idea` 目录，用户改过「保存目录」后写进去的历史文件在历史页里看不到——现目录解析单点化、读写同源，且保存目录变更即触发重扫

- **「最近执行记录」右侧面板可编辑（T-RecentEditable）**：与「SQL 日志单击列表弹出的预览浮窗」同效果——右侧 SQL 面板改为可编辑，可临时改字段值再复制（复制按钮与 Ctrl+C 都取改动后的文本）；自动折叠跟随设置。行号保持开启、内容恒为格式化（原拍板口径不变）
- **「最近执行记录」支持多选（T-RecentMulti）**：与 SQL 日志页同一套语义——Ctrl/Shift 多选、Ctrl+C 批量复制（逐行拼接纯 SQL）、多选时右侧编辑器按行序拼接显示（所见即复制）、状态行显示「已选 N 条」；「打开预览」取最后交互行，右键「复制时间戳」在多选下批量复制。单选行为不变

- **预览面板调用栈悬停调试器风格弹层（T-StackHoverUi）**：预览面板的调用栈区域悬停时弹出调试器风格弹层——结构化解析调用栈帧（`StackDigestFrames`），跳过尾部基础设施帧（Tomcat/线程池尾巴），锚定最深业务帧（Controller→Service 链）；帧数可配置（设置页「调用栈快照帧数」+ agent 侧双通道下发，默认 24 帧，范围 [12,64]）；尾部穿透补 spring web/boot 前缀；验收阻塞修复（日志预览识别/字面量恢复分支丢调用栈快照）
- **SQL → Java 反向转换能力扩展（T-SqlToJavaPromote）**：`GROUP BY` 建模 `.groupBy(...)`；`HAVING` 建模 `.having("...")`（聚合表达式原样透传）；`SELECT DISTINCT` 建模 `.select("DISTINCT ...")`；`FOR UPDATE` 建模 `.last("FOR UPDATE")`（与 LIMIT 并存合并单 last）；聚合/函数列（`COUNT(*) AS cnt`、`MAX(price)` 等）收集原文回落 `.select("expr AS alias")`（此前整体放弃致往返语义丢失）；`INSERT ... NULL` 值建模生成 Java `null`（此前报错）；`UNION / UNION ALL` 子查询解析（SQL 层往返经渲染还原，Java 生成层因 MP core 无 union() 方法降级为原文注释提示）；条件值参数化——`DATE_FORMAT(...) = '2024'` 形态生成 `apply("... = {0}", "2024")`（值/表达式分离）。
- **SQL → Java 方言宽容**：双引号标识符（`"member"`）、`0x` 十六进制字面量、反斜杠字符串转义（`'a\'b'`）、`DATE / TIMESTAMP '...'` ANSI 类型字面量；算术表达式 / `<=>` 空安全比较 / `ILIKE` 等无法静态映射的形态一律降级 `apply()` 透传（「任何条件都不挡路」）。
- **SQL → Java 返回类型自由输入（T-MapResultVo）**：「返回类型」字段（原「返回 VO」）改为可编辑组合框——自由键入任意返回声明（`List<Map<String, Object>>` / `List<HashMap<...>>` / `List<Object>` / VO 类名），一个控件承载全部返回形态语义；生成器按声明派生调用族：含 Map → `selectJoinMaps(wrapper)`/分页 `selectJoinMapsPage(page, wrapper)`（JOIN，MPJ）/`selectMaps`·`listMaps`（单表）族；含 Object → `selectObjs`/`listObjs` 族（单表；JOIN 场景无 `selectJoinObjs` 报错指路）；类名 → VO 语义（`selectJoinList(Vo.class, wrapper)` + `selectAs` 别名驱动）。Maps/Objs 族不带 Class 参数（1.5.8/MP 3.5.17 javap 实证）；正向识别 `selectJoinMaps` 调用与分页 Page 实参；MCP `sqlv_reverse_sql` 加可选 `maps`+`mapperVariable` 参数（默认不传零回归）。返回类型选择器补 Maps 分页声明 `Page<Map<String, Object>>`；返回类型声明项补类图标；返回类型固定候选并入「选择类」弹框前插；返回类型字段改照「调用类」同款形态（灰字占位标签＋点击弹选择）；返回类型字段去下拉（纯输入框＋选类按钮）。

### Changed

- **「最近执行记录」弹框化（T-RecentExecDialog）**：gutter 图标右键菜单「最近执行记录」由二级列表换代为浏览对话框——左侧列表直接复用 SQL 日志面板的条目渲染器（两行形态/类型色条/耗时与来源角标与日志页完全一致），右侧高亮编辑器展示参数还原后的可执行 SQL（后由 T-RecentEditable 由只读改为可编辑），选中切换/双击打开预览（预览后对话框保持在场，便于连续对比多条）；交互对齐「SQL 屏蔽规则管理」对话框（非模态/可拖拽/尺寸位置记忆）。空命中也弹对话框（状态行显示空态文案，原一闪而过的轻通知退役）；右键菜单、锚点快照校验与后台匹配链路不变
- **「最近执行记录」右侧编辑器恒为格式化 SQL（D91，取代 D88 的「切换原文」）**：编辑器显示只剩格式化一种形态（与预览「格式化还原 SQL」同源 formatter，主子句各占一行），长 SQL 不再挤成软换行长条；「查看原文」切换已按用户要求移除；编辑器上方两枚按钮改为**图标按钮**（图标取「日志转 SQL」结果区同款：格式化 `ReformatCode`、复制 `Copy`，文案走悬停），格式化按钮=幂等重排（格式化降级原文后可手动重试），复制取当前显示文本（经平台复制通道进 IDE 复制历史）
- **「最近执行记录」弹框列表右键菜单（T-RecentExecMenu）**：与 SQL 日志页右键同源六件——复制时间戳（受「显示时间戳」开关门禁，关闭时置灰）、屏蔽此 SQL（规则入库后该行即刻从弹框移除，气泡附「管理屏蔽规则…」直达）、发送到 SQL → Java、发送到日志转 SQL（工具窗口未创建时状态行提示）、跳转到 Java、跳转到 Mapper XML（定位不到即隐藏）；**不含**删除（弹框是浏览视图，不越权改全局日志缓冲）、方法级聚焦（锚点本身就是那个方法）、按请求聚焦、复制 SQL（按钮已有）。跳转/屏蔽/复制/送页签一律走日志页同一套实现（新增 `LogEntryJumpOps` 共享跳转原语，顺带消除实时页与历史页既有的一份重复实现）

### Fixed

- **SQL 复制快捷键按界面统一（T-SqlCopyShortcuts）**：此前只有单击预览浮层的 Ctrl+C 能复制，其余带 SQL 编辑器的界面（主预览面板、最近执行记录、屏蔽规则、回滚 SQL、SQL → Java 输入区、参数导入）以及「最近执行记录」的列表都无法用键盘复制——编辑器上按 Ctrl+C 走不到平台的复制动作、列表复制出的是渲染文本。现按同一套语义统一接线：编辑器内**无选中复制全文、圈选后只复制片段**（Ctrl+C 与 Ctrl+Insert 同义键）；「最近执行记录」列表的 Ctrl+C 改为复制纯 SQL（与 SQL 日志列表一致）。各界面原有的复制按钮/菜单不变
- **SQL 日志单击预览浮层 Ctrl+C 复制失效修复（T-ClickPreviewCopyRestore）**：单击 SQL 条目弹出预览浮层后按 Ctrl+C 复制不到 SQL、多选批量复制也被浮层打断。根因两条：①浮层为「弹出即可编辑」抢走键盘焦点（既有拍板，不回退）；②焦点落到浮层编辑器后，平台的复制动作在编辑器上取不到复制能力提供者，按键事件最终落回编辑器的默认复制——而无选中时它是空操作，于是「按了 Ctrl+C 毫无反应」（列表自身的复制链路本身正常，只是焦点已不在列表）。修复：带 Ctrl/Shift 修饰键的单击从第一击起不再弹预览（多选全程焦点不离列表，批量复制恢复）；浮层内 Ctrl+C / Ctrl+Insert 改为在编辑器自身的按键绑定层接管——无选中复制整条 SQL（与头部复制按钮同源同反馈），圈选片段时只复制片段；其余编辑器不受影响

- **单条错误上报「提交」没反应（T-ErrReportSubmitNoOp）**：从 IDE 的错误/冻结通知进入「报告到需求池」对话框后点「提交」，按钮不转「提交中…」、状态行空白、窗也不关——收集批次条目时对空容器做了裸索引（单条事件按设计不出批次分节，容器里没有复选框），抛出的越界异常被点击处理器外层吞掉，只在 IDE 日志里留一条 SEVERE（缺陷自 2026-09-15 起存在于此后的全部发行版）。现在容器访问全部带界，并且请求组装环节整体纳入兜底：万一组装失败，原因会显示在对话框状态行里（可原地改内容重试或转浏览器填写），不再「点了没反应」
- **重复的 SQL 预览图标与点击后弹框卡住（T-GutterDupFreeze）**：MP 链上出现假 HQL 锚点，同一行挂两枚预览图标，点开后弹框卡住关不掉。根因两条：①链内嵌 `.exists("select 1 from ...")` 令整条 wrapper 链源码被兜底当作 HQL 文本、被 `SELECT ... FROM` 正则命中（缺「首参须是字符串」门控）；②批量注册路径与链首 mapper 变量两条收录路径在同一行各挂一枚。修复：补字符串首参门控、源码文本兜底改空、批量注册路径让位；并把「按实体名找类」的全项目短名扫描改为索引构建期降级 + 有界扫描（上限 5000、可取消、命中即止），消除点击时持读锁连续 70 秒把 EDT 卡住、覆盖层窗口关不掉的机制

- **SqlToJavaPanel 两处 Throwable 捕获补 fatal 透传**：`SqlToJavaPanel` 两处 `catch (Throwable)` 未透传 `Error` 子类，守卫由红转绿（整段收口）
- **工厂链裸实参下带参 `exists` 被误判为链终结点，`EXISTS` 子查询条件整条丢失（T-ExistsChainTerminal）**：`exists` 在 MP 里同时是**条件方法**（`Join.exists(String, Object...) / Join.exists(boolean, String, Object...)`）与**链终结点**（`ChainQuery.exists()` 无参），PSI 定位层的终结点判定只按方法名匹配，二者又落在链尾时结构完全同形。于是 `service.list(Wrappers.<T>lambdaQuery().eq(...).exists("SELECT 1 FROM ..."))` 这类**裸工厂链直接作方法实参**（链内无 one/list/count/page 等真终结点）会把带参 `exists` 当终结点剔除——子查询条件从预览 SQL 里整条消失，且整条查询被误包成 `SELECT EXISTS (...)`，复制到数据库工具执行语义完全不同。现按实参个数区分（javap 实证 3.5.7/3.5.15/3.5.17 的 core 侧只有带参形态、extension 的 `ChainQuery.exists()` 才无参）：无参 `exists()` 仍是链终结点，带参一律作条件解析（布尔前缀照旧剥离为动态条件开关）；同一判据以 `isExistsConditionCall` 单点收口，供终结点筛选与 wrapper 变量接受门共用。`var w = Wrappers.<T>lambdaQuery()...exists(sql)` 声明形态同属此修复面（此前被同一名单拒为 wrapper 变量）。变量/分步/`new` 三种写法与 `notExists` 本就不受影响，各有回归用例钉住
- **调用类 MPJ 能力误报「无 MPJ 能力」**（真机实证：`DeviceInfoMapper extends MPJBaseMapper` 仍被误判）：「先贴 SQL 后选调用类」路径拿 stale 的能力判定（此前仅 SQL 解析链反查、选类回调不刷新）——现选类后后台重查 MPJ 能力与方法集再重生成。
- **列名→字段映射驼峰归一 + 多候选实体静默取首明示**（真机实证：`role_id` 等列大列表回落字符串）：列名查询双向归一（snake_case↔camelCase，含 `@TableField` 显式驼峰列）；同表匹配到多个实体时静默取首个现于状态栏列出全部候选与已选项（如不符可开启「手动选择实体」切换）。

## [2026.10.7] - 2026-10-07

### Added

- **参数字面量与枚举常量自动预填（T-JpaParamOrder F6）**：`setParameter("status", OrderStatus.PAID)` 这类字面量/枚举常量调用自动预填参数框，用户追问「为什么这些常量没有回显」后补齐
- **SQL 日志「滚动到底部」+ 尾部跟随（T-SqlLogPerf）**：SQL 日志工具栏新增「滚动到底部」动作——一键跳列表底并恢复跟随；跟随态下新 SQL 到达自动贴底，向上滚动阅读即自动停跟随（滚回底部/点动作恢复，IDE 控制台同款习惯）。此前 log 包无任何滚动控制，并发灌入时新条目只进模型、视口原地不动（用户体感「监听加载有延迟」的主形态）

### Fixed

- **JPA 链式 `setParameter` 参数在 SQL 预览中整体错位修复（T-JpaParamOrder）**：链式 `em.createQuery(...).setParameter(a).setParameter(b)...` 写法下预览渲染的参数值整体串位（用户实测 `setParameter("locationStatus",…)` 写重后五个条件值全部错位、尾参被丢弃，出现 `warehouse_id='TC'` 这类张冠李戴），参数面板行值却显示正确。双根因：①链式调用的 PSI 是嵌套结构，参数收集按前序遍历先外后内，收集顺序＝书写顺序的**逆序**；②渲染/复制时第 i 个 `?` 配第 i 个参数名的纯位置配对，重复参数名把参数序列撑到多于 SQL 占位符数，错切后尾参直接落空。修复三刀：收集后按参数表偏移归位为文档序（非链式分语句写法本就文档序，零影响）；同名命名参数去重保首现序（JPA 覆盖语义）；渲染与「复制可执行 SQL」改按名回填（HQL `:name` 占位符名序，同名占位复用同值），与 setParameter 书写顺序彻底解耦——位置参数混入时保守回退按序回填。附带修复「表： long」：`createQuery(hql, Long.class)` 的结果类型 Class 此前被当实体解析、面板表名误显 `long`，现非 `@Entity` 的结果类型回落语句头实体名解析真表；同名写重时参数区尾部新增灰字提示（运行时以最后一次为准）。测试：先红三例（链式收集序实测逆序实锤、写重 6 set/5 名序列与用户截图反推逐字节一致、乱序 set 渲染错位）＋摘修复复红反向验证，定向 88 例全绿
- **参数占位启发式复合词误报修复（O-111 用户 demo 实测跟进）**：`locationStatus` 这类复合词此前被参数占位启发式误配为集合占位，现不再误配
- **并发大量 SQL 日志下监听与历史加载性能修缮（T-SqlLogPerf，20261006-1020 开发计划）**：日常 IDE 实测并发压测约 65 条/秒持续灌入、8708 条/19MB 历史文件加载 15~16.6s（超线性劣化）后的五处修复——①历史二级列表改**滚动追加**（首屏 30 条 + 滚动逼近底部才续写；原 invokeLater 链对 8708 条排 290 个 EDT 脉冲全量排空，无论看多少全部进模型）；②历史页浏览态 refresh 不再每次**全量重读当前文件**（(lastModified,length) 快照判定，未变直接复用已解析条目——实测同一浏览窗口 19MB 被解析 3 遍）；③历史解析**单遍化**（每块 joinToString→lines()→joinToString 三份拷贝砍到一份，解析语义逐字一致）；④去重判定锁内 O(n) 全表扫描改 dedupeKey→最近时间戳 **O(1) 索引**（并发下三通道写线程与 EDT snapshot 抢锁的大头；入队/淘汰/裁剪/开关/清空/删单六维护面等价性守卫钉死，摘修复必红）；⑤实时列表全量重建链**防抢占**——风暴期 200ms 一次的新刷新此前反复重启重建链致列表卡首屏 30 条，现进差分前同步补完在途链。另热路径新增阈值触发耗时探针（idea.log 检索 `[T-SqlLogPerf]`，单次 >100ms 才打，常态零噪音）供真机对账。agent 接管期桥侧嗅探最小化一项经复核被 T-AgentLogTime 既有定案（日志时间无条件采集）推翻，维持不动

### Changed

- **帮助页 / Marketplace 自定义页文案精简**（`html/marketplace-custom-page.html`，用户令「文字内容多」优化）：功能矩阵 27 个「说明」单元格压缩到一行级——MCP 工具行由十工具逐名枚举＋理念句（约 200 字）收敛为能力面概述（渲染 SQL / 表结构 / EXPLAIN / 日志还原 / 回滚预案等 + agent 兼容与逐工具门控），增强捕获行剥离注入确认 / 24h 冷却细节（FAQ 第 8 条已承载），多租户 / 自动折叠 / 历史定位等长单元格同步瘦身；「为什么选它」要点去与矩阵重复的细则、标语盒三行合两行。全部功能名、New / Ultimate 标签、触发方式、FAQ 八条、快捷键与链接零删减，页面可见文字 5005 → 3973 字符（-21%）；`HelpUiTest` 仅断言资源可加载与非空（无文本断言），零影响。另新增 **Markdown 版 `docs/发行说明/marketplace-custom-page.md`**（整页复制进 Marketplace → Documentation 自定义页编辑器直接使用）——该编辑器仅渲染 Markdown（`<style>` / class / 内联 CSS / 纯 CSS 手风琴均被剥除），原 HTML 版保持不动、专供 IDE 内帮助按钮 JCEF 渲染；New / Ultimate 徽章以官网托管的同款配色 SVG 药丸图内联承载（`sql-visualizer.jvtools.cn/badges/tag-new.svg` / `tag-ultimate.svg`，Markdown 无 CSS 无法做药丸，图片徽章与 HTML 版观感一致），FAQ 由手风琴改为小节标题。发行流程文档（`docs/发行说明/Marketplace发布流程-说明文档.md`）§10.2 / §11 已同步登记自定义页更新步骤与「md + html 两版同步维护」约定，HTML 版头部加配套注释。两版各内联三张演示动图（Wrapper 静态预览 / SQL 日志 / MCP agent 调用），以官网 `/demo/*.gif` URL 引用——插件 zip 体积零变化，帮助页离线时动图不显示、页面其余内容照常（html 侧 lazy 加载 + 暗色边框适配）

## [2026.10.6] - 2026-10-06

> 预先整理于 2026-09-30，发行日 2026-10-06（版本号按「版本号即发行当日日期」规则预定）。范围核算基点＝**v2026.9.23 发行包的打包点 `0ebf3c4`**（用户已装 zip 所含内容），而非标签 `2026.9.23` 当前指向——该标签其后曾为源码包一致性两度前移，标签树里已含本版部分内容但发行包不含。

### Added

- **`@Embedded` 嵌入值对象识别补全（T-Embedded）**：用户报障「插件不能识别 @Embedded」，调研定缺口三处全量闭合——①**嵌套点路径覆写（G2）**：JPA 规范要求嵌套嵌入的列名覆写用 `name="zipcode.zip"` 点路径，此前 `@AttributeOverride` 只按裸名查找、点路径覆写静默丢失——现按「实体根点路径（`address.zipcode.zip`）> 嵌入相对点路径（规范例 2 形态 `zipcode.zip`）> 裸名兜底」三级查找、点路径优先，类级覆写键随嵌套链传递、字段级相对键逐层合并；②**Spring Data 形态（G1）**：`@Embedded.Nullable` / `@Embedded.Empty` 捷径注解此前落成伪单列、裸 `@Embedded` 的 `prefix` 丢失——现双轨判定（注解可解析按全名、不可解析按注解文本三形态）接通识别，`prefix` 按官方语义拼在嵌入对象每一列之前（同一值对象多处嵌入防撞车）；③**MP 混合注解实体（G3）**：`@TableName` 与 jakarta `@Embedded`/`@Embeddable`/`@EmbeddedId` 共存的实体此前在 Wrapper 预览、批量模板与 CRUD 生成里产出 `update_info` 伪列——现值对象字段展平为宿主表列。真机走查再抓出派生查询缺口并随轮修复：`findByUpdateInfoCreatedTimeAfter` 这类嵌入段驼峰路径此前在派生查询 WHERE / ORDER BY（含 `Sort.by` 字面量属性）被映射成伪列 `update_info_created_time`（SELECT 全列展开与 `@Query` JPQL 链本就正确，唯派生查询中招）——`TableInfo` 新增 `columnByEmbeddedPath` 按「嵌入字段名 + 首字母大写内层字段名」拼接匹配展平列，`DerivedQueryConverter` / `JpaQueryEnhancer` 两处消费接入，非嵌入的拼接属性仍走蛇形兜底不误判。测试：实体解析五形态 + 点路径覆写 + SD 形态 + 派生查询 WHERE/ORDER BY 映射与反例，先红后绿、反向验证读数在案；沙盒 runIde 真机两用例闭环（`@Query` JPQL 一次通过、派生查询缺陷抓出即修复复测）

- **自研框架场景跳转落点双修复与 JAR 业务帧反向名单（T-ToolSiteBizFrame）**：SQL 执行点在业务自研工具类里的两个「跳转上顶到上层调用方」机制一并收口——①判定缓存「身份在场」粒度不足：同一 Service 方法先直发一条 SQL 后再经业务工具类发第二条时，缓存的主位置身份（该方法）在后续栈上作为祖先帧必然在场，校验照过、主位置被钉在「调用工具类」那一行；现缓存命中后在比缓存身份更深的调用链段里找首个业务来源帧，找到即改用并刷新判定（直发形态快路径零反射，健康调用零成本）。②业务工具类以依赖 JAR 形态运行时被来源判定当第三方帧、主位置上顶到第一个目录帧；现设置页新增**「JAR 业务帧包名前缀」**——语义与「源码捕获帧过滤前缀」相反（名单内包名的帧即使来自 JAR 也按业务帧对待），随 CONFIG 热下发（`bizJarPrefixes` 键 + 回执扩严格四键），agent 侧 L1 与缓存命中路径共用统一业务来源判据；跳过前缀照旧只跳框架帧，把 JAR 业务包登进跳过栏仍会使跳转失效（该语义不变）。复现载体＝自研语料工程 E28~E34（先红后绿），真机走查与语料 HTTP 冒烟待 MySQL 走查轮
- **前缀登记格式校验与一键修复（T-PrefixValidate）**：设置页两栏前缀（源码捕获帧过滤前缀 / JAR 业务帧包名前缀）随输入即时校验——非法行（只允许字母、数字、下划线、$ 与点，建议以「.」结尾）、同栏重复条目、同一前缀跨栏冲突（运行时跳过栏静默优先、业务帧登记不生效）在输入框下方汇总提示，多问题时显示问题数与首条、悬停看全部；**一键修复**一次完成逐行 trim、丢空行与非法行、同栏去重、跨栏冲突按跳过栏优先从业务帧栏剔除，无问题时提示行自动隐藏。判定逻辑独立纯函数（单测覆盖），面板只做展示与回写
- **信创方言补全族：OceanBase 与 openGauss（T-DialectXcFamily，O-63）**：方言注册表 9 → 11——新增 `OceanBaseDialect`（MySQL 模式：backtick 引号、LIMIT、NONE 折叠）与 `OpenGaussDialect`（PG 族：双引号、LOWER 折叠），EXPLAIN 支持面表与 URL 推断三处（IDE 推断 / agent 角标 / MCP 链）同步登记 `jdbc:oceanbase://`、`jdbc:opengauss://`、`jdbc:gaussdb://` 三个前缀（各自官方文档证实）。openGauss 形态经在册真库引擎级验证——并据此推翻「PG 族 = ON CONFLICT」推定：openGauss 的 upsert 实为 `ON DUPLICATE KEY UPDATE` MySQL 形态，`renderUpsert` 按真机证据落地；`LIMIT 1,2` MySQL 形态在 openGauss 被 PG 语义静默错解（0 行）一并钉进用例。OceanBase 全形态为文档级已验、环境受限未验证（在册无 OB 真库），如实留账
- **SQL 日志屏蔽（T-SqlSuppress，O-69）**：保活心跳类 SQL（如 `SELECT 1`）持续刷屏日志列表——现可在日志列表右键【屏蔽此 SQL】一键屏蔽，支持多选（不同语句各成一条规则、已存在自动跳过）；工具栏新增【管理屏蔽规则】（漏斗图标）与右键【管理屏蔽规则…】双入口打开同一管理对话框：规则列表（模式摘要/创建时间/已拦截 N 次计数）+ 右侧 SQL 语法高亮只读预览（带行号），新增/删除/清空即时生效、关闭即生效。匹配口径＝还原 SQL 空白规范化后精确匹配（复用去重指纹），`SELECT 1` 与 `select  1` 命中同一条规则、大小写敏感与既有规范化一致；规则应用级全局生效、独立存储不进设置同步，上限 500 条超限拒新增；屏蔽动作三层反馈——列表存量行立即消失、balloon 可直达管理对话框、管理对话框实时命中计数；删除规则后仍在缓冲内的条目恢复显示（已入队丢弃的不可恢复，界面文案明示）。后续走查返修两段（2026-09-24）：管理对话框分栏与规则行对齐日志列表形态、匹配口径追加尾分号剥离（规则粘贴态带 `;` 与日志还原 SQL 互命中）；并新增可选**结构匹配**（默认关、按规则手动开启或一键全部开启）——开启后按语句形状匹配，参数值不同的同形状 SQL 一并屏蔽（字符串字面量与裸数字参数化为 `?`，引号标识符与标识符内数字保留）。管理对话框支持拖拽缩放与尺寸记忆（O-80，`DialogWrapper` 默认固定尺寸放开 + `DimensionServiceKey` 记忆，最小 560×360 兜底）
- **无别名 HQL 属性映射盲区修复（T-HqlNoAliasProp，O-71）**：预览无别名形态 HQL 时，`WHERE stationCode = ?` 这类裸属性与 `updateInfo.BE_DELETE = 0` 这类嵌入段占限定符位此前残留属性名不转列名（O-57 别名作用域映射只覆盖带别名形态）——现收敛在 `JpaAliasTableResolver` 单文件：新增 `mapBareProperties` 裸词映射（字面量分支前置 + 函数调用负前瞻 + 子句关键字/已知限定符/TRUE·FALSE·MEMBER 等排除），嵌入段占限定符位按 `column.embeddedParent == qualifier` 判据剥离产出裸列名（带别名三段形态逐字不变）。**契约变更（显式登记）**：O-57「裸属性透传不回归」保守契约推翻——透传产物本就不可执行，属修复非回归。测试：探针转正 `HqlBarePropertyMappingTest` 10 例 + `HqlAliasColumnMappingTest` 契约改造 3 例，RED 与反向验证 8 红同形态、实施后定向 31 条全绿；用户场景产物与 Hibernate 真实语义一致（`WHERE STATION_CODE = ? AND BE_DELETE = 0`）。
- **通用 XML 内嵌 SQL 识别（T-XmlInlineSql，O-72）**：RFListPage 这类自研框架 XML（根元素名不固定、SQL/HQL 写在自定义标签的 CDATA/文本里）此前被 mapper/pages/hbm 三套「标签名+父标签名」白名单全部挡下——无 gutter 图标、右键/Alt+Shift+Q 弹「未找到」。现新增作为三套白名单之后的**兜底第四通道，锚定内容不锚定标签名**：叶子标签首段文本以 SELECT/INSERT/UPDATE/DELETE/WITH/REPLACE 开头（大小写不敏感）即认领，任意根元素/任意框架生效；载体标签自身或祖先的 `type="hql"/"jpql"` 走 HQL 转换管道（复用 hbm 命名查询链，别名作用域属性列名映射自动生效），转换失败或实体不可解析**回退原文直显不抛错**，原生 SQL 直显带 `?` 占位符参数表；右键/Alt+Shift+Q 的光标定位、全文件候选与选择区候选同步追加载体兜底，多语句弹选择栏（与既有 XML 候选交互一致）；既有 mapper/pages/hbm 文件由排除集合保证零双图标；设置页「界面与操作」新增总开关（默认开）。附带判别性实测：本平台 `XmlText.text` 对 CDATA 返回带 `<![CDATA[` 包裹的字面全文而非展开内容（首跑 11 例红实证），检测器统一剥包裹；同口径缺口经延伸修复收口——抽共享 `xml/XmlDirectText.kt`（`directTextOf` 直属文本拼接 + CDATA 剥包裹 + trim），`HbmXmlEntityResolver` 与 `previewHbmNamedQueryTag` 两处 hbm 命名查询提取点改走共享口径，旧「CDATA 展开」非判别性断言改判别式（钉死包裹不得残留）。测试：新增 26 例（RF 真实样例/根元素改名/CDATA 与纯文本/排除集合防双图标/HQL 实体转换与回退）+ 既有回归 51 例全绿；反向验证三条读数达标（摘认领→RF 图标红、摘排除→mapper/pages 双图标红、摘 HQL 管道→转换红）；hbm CDATA 延伸修复反向验证 8 例红、定向 5 类全绿。
- **XML 预览 List 参数按签名拆分多值渲染（O-81，T-XmlListParamSplit）**：`List<String>` 签名的集合参数在值框输入 `PI,WP` 此前被渲染成 `IN ('PI,WP')` 整串单值——两个根因：IN 形态探测对 `IN` 与 `(` 之间只容忍恰一个空格，mapper 排版换行即漏判；签名 COLLECTION 信息未参与回填组装，拆分后被集合恒引号档再包一层。现新增 `XmlParamBackfillHints` 纯函数：签名 COLLECTION 无条件按逗号多值渲染，引号按元素类别派生（文本加引号、数值裸渲染，与 O-78 foreach 元素同口径），显式 typeHandler 豁免，无类型信息维持原形态；IN 探测放宽为任意空白串（多 `?` 列表仅首位命中的语义钉住不变）。端到端复现 `IN ('PI,WP')` 先红 3 例，改后定向全绿

### Changed

- **空参数填充策略三态化，默认改为占位符 `?`（T-EmptyParamFillTernary，O-79）**：未填写的参数（含 foreach 集合元素）默认保留 `?` 待填提示，不再默认渲染 `NULL`——NULL 会静默过滤行（如 `amount >= NULL` 恒不匹配），不易察觉有参数未填。需要可直接执行的 SQL 时在 设置 → 空参数填充策略 切换 NULL / 空字符串档；已在设置里持久化过 NULL / 空字符串的用户保持原档不受影响。
- **注入识别不了项目类型的弹窗改引导开启增强捕获（20260924 定案）**：拦截器自动注入无法识别项目类型（非 Spring Boot + MyBatis-Plus 工程）时，结果弹窗此前只给一个「确定」并让用户参考手动配置示例——现改为引导弹框：默认按钮「开启增强捕获」走与缺位置引导（O-66）同一编排（置位开关 + 授权确认 + 重启生效提示一条龙；agent 通道不依赖项目类型、不改工程代码），取消静默返回；已开启已授权的用户维持普通警告。双语基础文案同步去掉手动配置指引（docs/instrumentation/SqlSourceInterceptor.kt 示例文件保留，手工配置路线仍在，只是不再主动指路）。
- **MCP 工具线全动态化，兼容性验证五目标全绿（T-McpDynamicToolset）**：v2026.9.23 的 MCP 工具类以静态类型引用平台 `com.intellij.mcpserver` API，Marketplace 静态验证在 243/251 平台报兼容问题（该通道此前未上架此形态）。现整线改为 jar 字节码零 mcpServer 类型引用——基类反射化桥接、工具集声明退役改运行期 ASM 壳注册（simpleName 保持 `SqlVisualizer` 前缀=分组名不变）、audit 改 Kotlin intrinsics 透传绕开 243 stdlib 缺失的 SpillingKt；243/251/253/262/263 五目标验证全 Compatible，262 沙盒十工具端到端读数与改造前逐字一致。对用户行为零变化（MCP 能力仍需 IDEA 2025.2+，十工具清单与门控不变）
- **源码捕获两前缀输入框可拖拽分栏与默认加大（T-FramePrefixResize）**：设置页「源码捕获帧过滤前缀」与「JAR 业务帧包名前缀」两框由焊死等高改为垂直分栏——拖拽中缝重分配两框高度、比例跨重启记忆；默认 3 行 24 列加大为 5 行 40 列（首选高保底 100px、分栏拖拽下限 60px），宽度随设置对话框拉伸、占位文案不再截断；「自动注入源码捕获拦截器」标签补「（推荐使用增强捕获）」后缀，术语对齐相邻的增强捕获开关

### Fixed

- **MCP 工具真机全失修复：动态壳字节码生成去 ASM 依赖（T-McpDynamicToolset 真机 ASM 不可达）**：20261005 用户把打包插件装入日常 IDEA 2026.2 后，MCP 服务器里本插件十工具全失——真机日志实锤唯一断点＝工具集动态注册生成壳类时 `NoClassDefFoundError: org/objectweb/asm/ClassWriter`（六轮复现；同会话门控过滤注册成功，证明 MCP 插件探测与注册链全通）。根因：平台 262 自带 asm 库，`org.objectweb.asm` 因此是平台已知包，插件 classloader 对该包 parent-first 接管下**插件自带的 asm jar 不可达**、平台 asm 又位于对插件不可见的内部库 jar——「自带 asm 免平台可达性差异」前提被真机证伪；而开发沙盒用的平台不含该库，故动态化上线时的沙盒十工具端到端绿是假绿，该形态此前从未真机验证。修复：壳类字节码（`extends 工具类 implements McpToolset` + 唯一构造器，major 61 与原版逐语义一致）改由自研 `TinyClassFileWriter` 按 class 文件规范手写生成，零外部依赖、不撞平台包名；asm 依赖移除、发行包不再携带 asm jar（−118KB）。工具注册链/「暴露工具」分组名/设置页门控/审计全不变。新增守卫「mcp 包零 `org.objectweb.asm` 编译期引用」（ASM 引用回归即红；先红后绿两读数在案）；真机复验在用户日常 IDE 重装后对账（注册行 + tools/list 十工具 + 端到端调用）
- **premain 注入不再落入 Gradle 测试与 IDE 任务的 fork JVM（T-GradleTaskInjectScope，O-73）**：开启增强捕获后，`-javaagent` 此前会经 init script 传播进 Gradle/Maven 任务 fork 出的全部 JVM——从 IDE 工具窗跑 `test`/`runIde` 等任务的 fork 进程也被挂上 agent。现按任务语义收窄：测试族（test/check/verification/surefire 等）与 IDE 族（runIde/buildSearchableOptions/prepareSandbox 等）不再注入，`bootRun`/`run`/`quarkusDev` 等真实应用运行任务照常注入，非 Gradle 的普通 Java/Spring Boot 运行配置行为完全不变；跳过时提前退出，不落盘 agent jar、不开协商端口，判定异常时按原行为注入兜底（绝不影响任务本身）
- **T-SQL 控制流批次切分两处误合并（T-SqlSplitterTsqlGuard，O-60）**：①`BEGIN TRAN/TRANSACTION/DIALOG/CONVERSATION/DISTRIBUTED` 等非块形态此后方文本中任意位置的 `END` 词（如 CASE 表达式）误武装块计数、后续所有顶层 `;` 不再切分——整段脚本并成一条送执行；此类 BEGIN 不再武装；②`IF @x=1 BEGIN SELECT 1; END` 换行后接下一条语句（T-SQL 惯例 END 后分号可省）此前被并成一条——块深度归零的顶层 `END` 现产生隐式语句边界（声明段内例程体的 END 不受影响，Oracle 匿名块/DELIMITER 存储过程语义逐字节不变）。定向 81 例（先红 5）全绿，切分消费方回归 159 例全绿
- **回滚产物含 `SET IDENTITY_INSERT` 时静态检查误报与默认执行不完整（T-RollbackIdentityParse，O-64，P19 收口）**：①含 `SET IDENTITY_INSERT [user] ON; INSERT …; SET IDENTITY_INSERT [user] OFF;` 的回滚产物走无界写静态检查此前报「无法解析语句结构」（JSqlParser 对 T-SQL `SET` 语句解析期抛错，五种表名形态实证全败）——现检查前按精确形态剥离 identity 包装语句再解析（单表引用 + ON/OFF + 干净边界；该检查面同时服务用户手写输入故不宽剥，剥不到维持 fail-closed），SQL Server 与 DM8 双方言受益；②执行对话框 Statements 选择此前默认只勾第一条（＝第一条 `SET … ON`），不逐项勾选直接执行时只切了开关、INSERT 不跑——identity 包装产物现默认整批直跑（执行期副本覆写，不弹选择层、不改全局设置与缓存），普通多语句产物行为不变
- **MCP SELECT 白名单误杀同名标量函数（T-SelectGuardScalarExempt，O-61）**：`SELECT INSERT(name,1,2,'x') FROM t`（MySQL 串函数）此前被只读闸门粗扫按 `insert` 关键字误拒；`SELECT TRUNCATE(price,2)`（MySQL 数学函数，8.0 手册 12.6.2）同形误拒——现对「写动词后随可选空白 + `(`」的标量函数形态豁免（DBX 同款判据 + 逐词出处登记：insert/replace/truncate 三词，replace 此前已豁免），豁免安全性由结构闸保证（能到达粗扫的输入必已解析为单条 SELECT，真实写语句到不了这一步），`INSERT INTO`/`UPDATE (t)` 等真实写形态回归用例钉住不变
- **SQL Server 方括号 SQL 被 MCP 只读闸门整类拒绝（T-SelectGuardScalarExempt §八解析收拢，用户确认载体）**：产品方言层为 SQL Server 自产的方括号形态（`SELECT * FROM [user]`）此前在 `sqlv_explain` 等只读链路上因解析器不支持方括号而 fail-closed 拒绝——现 MCP 闸门解析入口改走共享 `SqlAstParser`（开方括号支持），方括号 SELECT 可进入既有结构判定（单语句 / 非 INTO / 非锁读 / 粗扫四道闸一条不少，多语句走私等负样本四组判据全数保留），fail-closed 兜底路径原样不动
- **方括号 SQL 在性能建议与批量合并链静默降级（T-PerfSweep7 T-PS7-03）**：`SqlPerfAnalyzer` 与 `BatchPredicateMerge` 的解析入口此前对方括号语句解析失败即降级（AST 性能规则整体不命中 / 合并预览判不可合并）——现统一改走共享 `SqlAstParser.parseSingle`，方括号语句的 AST 规则与合并预览正常生效；连接错误分类器的 DDL 目标提取同步收拢（行为等价）
- **agent 通道慢拨号被误判为正常掉线（T-PerfSweep7 T-PS7-02）**：agent 发送循环的退避复位与 fastDrops 判据此前从拨号开始时刻起算——拨号耗时超阈值才建连的场景（连上即被对端关闭）被误判为「存活超阈值正常掉线」而复位退避；现改按建连成功时刻起算，慢拨号 + 立即关闭正确计入 fastDrops 并触发退避换端口
- **foreach 集合元素渲染双缺陷修复（T-ForeachItemRender，O-78）**：`List<Integer>` 等数值集合在 foreach 值框输入 `1,2,4,5` 后，预览渲染 `IN ('1','2','4',?)`——数值元素被误加引号成字符串，且部分值丢失留裸 `?`。现元素值按集合元素类型渲染（数值裸渲染、字符串/日期保持引号），且任意输入节奏下所填值全部完整回填（渲染帧内播种，不再受界面防抖重投递影响）。连带效果：foreach 元素与普通参数一样纳入「空参数填充策略」（未填写且有签名类型信息时按设置渲染 NULL/''）。
- **XML 预览对无 ew 参数语句误报「无 Wrapper 参数」红色警示修复（T-EwStatementScope，O-77）**：同一 mapper 文件里其他语句带 `${ew.customSqlSegment}` 时，预览普通语句（如 demo 的 selectOrdersByCondition）会误报「方法 … 无 Wrapper 参数（ew），无法展开 MP 占位」——占位识别域从整个文件收窄为当前语句原文（含 include 展开后内容），同文件他语句的占位不再记账到本语句；顺带收敛跨语句不同变量名占位污染与跨命名空间 include 占位漏检，语句外注释里的占位不再记账。
- **控制台捕获在「只开 JdbcTemplate DEBUG、无参数行」的工程里零产出修复（T-ConsolePendingFlush，O-70）**：关闭增强捕获后仅靠控制台通道时，语句行之后没有同线程后续行的语句（未开 TRACE 参数行、Tomcat 按请求新建线程）此前会一直停在解析桶里不产出（面板显示「已扫描 N 行 / 产出 0 条」）——现按「静默 1 秒」判定语句已完整并交出，轮询每拍兜底一次；同时修掉 2026.2 上停止后重启捕捉时旧 filter 摘不掉导致的重复解析。
- **MCP 真机走查三项观察收口（T-McpWalkFindings，真机十工具端到端走查全绿后收口）**：①`sqlv_render_sql` 的 line 参数指向语句标签行（即编辑器 gutter 标注的行号）时报「找不到语句」——定位锚从行首改为**行内首个非空白字符**（标签行行首是缩进空白，语法树沿父级爬不到语句节点），纯空白行顺延最多 2 行，错误文案补操作提示；②`sqlv_mapper_check` 空参模拟对含动态分支的语句 6/10 误报 `DANGEROUS PARSE_FAILED`——真因链＝mapper 渲染产物保留源 XML 换行缩进、注释剥离后空行变「真空行」，JSqlParser 5.3 简单模式解析失败时**静默返回 null 而非抛异常**，落成无归因价值的空指针——共享解析槽入口空白规范化治本（确认流与回滚链同时获得真空行免疫）＋解析入口对 null 响亮防御（残余失败带输入摘录），输出补 `simulate risk note:` 归因线索行；③`sqlv_schema_context` 成功路径首行披露实际命中的数据源（`source: …`），未指名时标注 `auto-resolved` 并附 pin 提示（跨源静默解析两次真机实证：schema 可能来自非意图库）。反向验证＝五处摘除六用例全红；mcp+execute+sql 三包定向 1070/0 绿，testFast 全量绿
- **增强捕获确认去重窗两属性的初始化顺序陷阱（初始化顺序守卫抓出）**：`AgentManager` 的 `consentAskGuardMs` / `lastConsentAskAtMs`（R2 去重窗）声明在 init 块之后，而 init 注册的 SETTINGS_CHANGE 监听上升沿回调链会同步读它们——若设置变更事件在构造期同步到达，去重窗宽度读到 JVM 默认值 0（去重失效、可能双弹）且弹窗时刻表为 null（NPE）。两属性声明上移到 init 块之前（测试缝区块内），初值与语义零变化；守卫回归绿。
- **首次启用增强捕获首轮运行零捕获修复（T-FirstUsePremainConsent，用户报障）**：第一次使用插件、在提示开启增强捕获弹窗点「确定」后 SQL 捕获并未生效，重启项目才正常——两层根因叠加：①时序上注入确认出厂未达成，首轮 Run 启动前 `-javaagent` 注入被跳过；②能力上 agent 只在驱动 `connect` 时包装连接，attach 兜底不能回溯包装 attach 前已存在的连接——Spring Boot/Hikari 池化连接全部建在此之前，该轮所有 SQL 都走旧连接（沙盒复现日志实证：CONFIG 启用三次回执后 4 个 HTTP 请求期间零 SQL 事件）。最终形态为**确认前置到「开启」时刻**：设置页勾选开启（上升沿）与引导弹框「开启增强捕获」在开启那一刻同步弹一次注入确认，授权持久化先于任何 Run——此后每轮启动即带 premain、连接从建池起就被包装，首轮即完整捕获（去重窗吸收引导链「置位+发布+请求确认」毫秒级连发的第二次请求，只弹一个框）。两道兜底：订阅执行监听后**补扫已在跑的主配置进程**（堵「项目刚打开就 Run、事件早于订阅」的静默竞态）；EDT 前提不成立的边缘路径回落 attach 链确认，attach 兜底挂载成功后新增能力边界通知（如实告知「挂载前已创建的连接不在捕获范围，重新运行项目后完整生效」，每轮至多一次）。拒绝/关闭增强语义不变（24h 冷却只管弹窗频率）。**出厂态首启静默修复（R3）**：出厂即「已开启但未授权」的形态不会产生设置变更上升沿，「开启时刻确认」在该形态永不触发——现**项目打开即弹一次授权确认**（先于任何 Run，同意后首轮启动即带 premain、捕获完整）；补扫主配置判据同步勘误（243 平台 `RunContentDescriptor` 无 `getRunProfile`，旧判据致补扫结构性空转零诊断——改 JVM 进程判据 + 逐描述符跳过原因日志与汇总读数）。反向验证：摘上升沿确认 → 行为用例红、摘补扫接线 → 守卫红；agent+config 全包回归 335/0 绿。
- **增强捕获确认流测试隔离缺口修复（发行门禁暴露）**：`AgentManager` 是项目级单例（跨测试类/用例复用同一实例），`AgentManagerFallbackTest` 与 `SourceCaptureGuideEnableFlowTest` 的 setUp 漏复位确认去重窗——T-FirstUsePremainConsent R2 引入去重窗（`consentAskGuardMs` 默认 1500ms）后，前序用例设过的 `lastConsentAskAtMs` 会让本类的确认请求被去重窗吞掉、`consentProvider` 回调不触发（断言收到 null）；`AgentConsentFlowTest` / `AgentPremainConsentTest` 早已按此口径复位，这两个类漏改，R3「项目打开即确认」入口上线后污染路径激活，全量 testShards 首次暴露（此前的 testFast 快测桶不含这两个引用平台类的用例，未覆盖）。补 setUp 复位（`consentAskGuardMs = 0` + `lastConsentAskAtMs.set(0)`）与既有口径一致；**单方法隔离跑通过**坐实为测试隔离缺陷而非生产缺陷（生产单项目单实例，无跨项目污染）。定向四类复跑 26s 绿。
- **JPA/HQL session 通道 UPDATE 实体名提取缺失与嵌入列映射修复（T-HqlUpdateEntityMap，用户报障）**：无 FROM 子句的 HQL UPDATE（`session.createQuery("UPDATE …")` 自定义 DAO 形态）此前表名与 `booking.updateInfo.createdTime` 这类嵌入属性路径原样透传——三处 FROM-only 实体名提取正则全不命中，表名 / 属性映射整链跳过只剩参数替换。现实体名提取收敛单点 `HqlEntityNameExtractor`（语句头优先＝UPDATE/DELETE/INSERT 头 > FROM，规避 UPDATE 带子查询时误命中子查询实体，五份拷贝收敛一处）；单表 UPDATE 渲染剥别名（对齐 Hibernate 真实产物 `UPDATE t SET col = ? WHERE id = ?`；raw 透传文本不剥别名登记为已知边界）
- **JPA 通道 DELETE 全表删除误报修复、UPDATE 警示补齐与字面量保护（T-JpaDeleteWhereGuard 及同构普查批，用户报障 + 「其他功能类似问题一起修复」）**：JPA/HQL/JPQL 链构造 DELETE 时模型条件树恒空、全表删除警示按模型而非 SQL 文本判定——任何带 WHERE 的 JPA DELETE 都误报 BlockAttack。现 `JpaUpdateParser` 新增 DELETE 头解析（顶层 WHERE 白名单解析、列引用剥别名；解析失败维持空透传不抛异常，真无 WHERE 时警示照常触发）。同构普查另修两族：①警示通道不对称——JPA 链无 WHERE 的 bulk UPDATE 此前静默不警示，EasyQuery / MDS / Querydsl 的 update 分支同样缺位，统一补齐（含 EasyQuery `updatable(entity)` 无主键时 notes 失实修正）；②语句头 / 字面量假设类缺陷四处——实体名替换无词边界与字面量保护（`OwnerInfo` 误命中 `OwnerInfoHelper` 前缀成 `unknown_tableHelper`、`like '%UserEntity%'` 被吃成 `'%user%'`、FROM 实体引用扫描吃字面量致驱动错表映射），新增 `JpaSqlLiteralMasking` 按「掩码→替换→还原」统一防护
