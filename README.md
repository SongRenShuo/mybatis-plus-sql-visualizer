# SQL Visualizer

> 服务未启，SQL 先知。

SQL Visualizer 是 IntelliJ IDEA 的 SQL 调试插件。写代码时，不启动应用就能预览 Wrapper、XML、注解、JPA / Hibernate 可静态推导的 SQL；运行应用后，捕获真实参数、耗时与调用位置，从慢查询直接跳回源码。预览结果可以继续执行、查看执行计划，也可以反向生成 MyBatis-Plus Java 代码。

[官网与在线文档](https://sql-visualizer.jvtools.cn) · [插件市场](https://plugins.jetbrains.com/plugin/33633-sql-visualizer) · [快速开始](https://sql-visualizer.jvtools.cn/guide/getting-started) · [更新记录](CHANGELOG.md) · [问题反馈](https://sql-visualizer.jvtools.cn/about/feedback) · [GitHub](https://github.com/SongRenShuo/mybatis-plus-sql-visualizer)

支持 IntelliJ IDEA 2024.3 及以上版本。Community 版可使用静态预览、日志捕获、代码转换等核心能力；内嵌执行控制台与数据库表入口需要 Ultimate 的 Database Tools。MCP 接入需要 IDEA 2025.2+ 及可用的 MCP Server。

## 能解决什么问题

| 场景 | 用法 |
|---|---|
| Wrapper 或动态条件太复杂，不确定最终 SQL | 在代码左侧点击预览图标，勾选条件、填写参数，直接查看 SQL |
| 日志全是占位符，难以复现问题 | 使用增强捕获获取真实参数、耗时、影响行数与错误信息；也可还原控制台日志 |
| 找到了慢 SQL，却不知道是谁调用的 | 从日志跳转 Java / Mapper XML，展开调用栈逐帧定位，按方法或请求聚焦 |
| 想立即验证生成的 SQL | 在预览中选数据源执行，查看 EXPLAIN、性能建议和 JOIN 关系图 |
| SQL 已调好，还要重新写 Wrapper | 将 SQL 转为 QueryWrapper、LambdaQueryWrapper、Wrappers 或 MPJ 代码 |
| 让 AI 帮忙排查 SQL，但不能只靠猜 | 通过 MCP 提供源码渲染、表结构、执行计划、历史检索与静态核查结果 |

## 功能演示

从静态预览、运行期排查，到代码生成与 AI 接入，下面展示主要使用场景。

<!-- 与官网 FeatureAlternate.vue 的八个主模块同序；图片使用稳定 URL，不加版本查询串。 -->

### 1. Wrapper → SQL：不启动服务，也能看见最终语句

支持 MyBatis-Plus 查询、更新、删除与服务链，以及 MyBatis-Plus-Join 联表查询。动态条件可勾选，参数可回填，并可保存为按语句隔离的参数预设。

<img src="https://sql-visualizer.jvtools.cn/demo/wrapper-preview.gif" width="800" alt="Wrapper SQL 预览：勾选动态条件并回填参数"/>

[查看 Wrapper 预览说明](https://sql-visualizer.jvtools.cn/features/wrapper-preview)

### 2. SQL 日志：真实参数、耗时与错误放在一起看

增强捕获是默认通道，授权后在应用运行时采集 JDBC 执行信息，不要求应用开启 SQL 日志。列表支持慢查询筛选、同结构聚类、按目标库分组、去重和屏蔽；单击选中，工具栏「快速预览」打开底部可编辑 SQL，双击进入完整预览。

<img src="https://sql-visualizer.jvtools.cn/demo/sql-log.gif" width="800" alt="新版 SQL 日志列表：筛选、选中条目与底部快速预览"/>

[查看 SQL 日志说明](https://sql-visualizer.jvtools.cn/features/sql-log) · [查看历史检索演示](https://sql-visualizer.jvtools.cn/demo/sql-history.gif)

### 3. 从 SQL 跳回源码：沿调用栈找到业务入口

日志条目可跳转 Java 或 Mapper XML；预览中的调用栈支持悬停或单击摘要展开、点击帧跳转。业务帧优先显示，相邻内部帧折叠；代码左侧图标的右键菜单还可反查该位置的「最近执行记录」。

<img src="https://sql-visualizer.jvtools.cn/demo/sql-log-jump-source.gif" width="800" alt="从 SQL 日志条目定位源码并查看调用位置"/>

[查看源码定位与调用栈说明](https://sql-visualizer.jvtools.cn/features/sql-log)

### 4. SQL → Java：把调好的 SQL 带回代码

选中 SQL 后按 `Alt+Shift+J`，或在工具窗口的「SQL → Java」页签粘贴。支持增删改查、JOIN、GROUP BY、HAVING、DISTINCT 与聚合表达式，可选择调用类和返回类型；不能直接映射的结构会保留透传或降级提示，不冒充完整支持。

<img src="https://sql-visualizer.jvtools.cn/demo/sql-to-java.gif" width="800" alt="SQL 转 MyBatis-Plus Java 代码，选择调用类与返回类型"/>

[查看 SQL → Java 说明](https://sql-visualizer.jvtools.cn/features/sql-to-java)

### 5. CRUD SQL：从实体或数据库表直接生成

在实体类、项目视图实体节点或 Database 表节点右键，选择 INSERT / UPDATE / SELECT / DELETE 后进入预览。候选语句支持打字过滤，列可勾选；自增主键默认不选，逻辑删除与乐观锁字段按实体映射处理。

<img src="https://sql-visualizer.jvtools.cn/demo/crud-sql.gif" width="800" alt="从实体或数据库表生成 CRUD SQL 并选择参与列"/>

[查看 CRUD SQL 说明](https://sql-visualizer.jvtools.cn/features/crud-sql)

### 6. XML 动态 SQL：条件、集合与参数一起预览

支持 `if`、`choose`、`where`、`set`、`foreach`、`bind` 与跨文件 `include`，可查看条件求值结果。注解 SQL、自研框架 XML 中的 SQL / HQL 也有对应入口；MPJ 联表则沿 Wrapper 入口预览。

<img src="https://sql-visualizer.jvtools.cn/demo/xml-preview.gif" width="800" alt="MyBatis XML 动态 SQL 预览：条件切换、集合展开与参数回填"/>

[查看 XML 预览说明](https://sql-visualizer.jvtools.cn/features/xml-preview) · [查看注解 SQL 演示](https://sql-visualizer.jvtools.cn/demo/annotation-preview.gif)

### 7. JPA / Hibernate：从实体查询到数据库 SQL

支持 Spring Data 派生方法、Repository CRUD、JPQL、Criteria、Specification、Query by Example、HQL 与 hbm 命名查询。嵌入实体字段按映射展开，支持属性覆写与组件路径列名前缀。

<img src="https://sql-visualizer.jvtools.cn/demo/jpa-preview.gif" width="800" alt="JPA 与 Hibernate 查询静态转换为数据库 SQL"/>

[查看 JPA / Hibernate 说明](https://sql-visualizer.jvtools.cn/features/jpa-preview)

### 8. MCP：让 AI 调用有依据的 SQL 工具

Claude Code、Cursor、JetBrains AI Assistant 等客户端可经 IDEA MCP Server 调用插件的 11 个工具，获取源码渲染结果、表列结构、执行计划、历史记录和静态检查结果。插件侧支持总开关与逐工具控制。

<img src="https://sql-visualizer.jvtools.cn/demo/mcp-agent.gif" width="800" alt="AI Assistant 中的 agent 经 MCP 调用 SQL Visualizer 工具"/>

[查看 MCP 工具与接入说明](https://sql-visualizer.jvtools.cn/features/mcp)

更多演示：[SQL 代码补全](https://sql-visualizer.jvtools.cn/demo/code-completion.gif) · [批量操作参数面板](https://sql-visualizer.jvtools.cn/demo/batch-operation.gif) · [执行 SQL](https://sql-visualizer.jvtools.cn/demo/execute-sql.gif) · [EXPLAIN 执行计划](https://sql-visualizer.jvtools.cn/demo/explain-plan.gif) · [JOIN 关系图](https://sql-visualizer.jvtools.cn/demo/join-diagram.gif)

## 能力与边界

### 编码期：静态预览与编辑辅助

- **MyBatis-Plus / MPJ**：Lambda 与字符串 Wrapper、服务链、联表、批量操作；Java 支持静态预览；Kotlin Wrapper 尚不能可靠还原表和列/值，因此不显示预览图标，主动使用 Convert to SQL/快捷键时说明限制且不生成猜测 SQL。支持逻辑删除、乐观锁、多租户 / 数据权限模拟，以及分页 LIMIT 与可独立关闭的 COUNT 模拟。
- **MyBatis XML / 注解**：动态标签、OGNL 条件、集合参数、跨命名空间 SQL 片段、`@Select` / `@Update` / `@Delete` / `@Insert` 与 Provider。自研 XML 的自定义标签内嵌 SQL / HQL 可按内容识别。
- **JPA / Hibernate**：派生查询、JPQL / 原生 `@Query`、Criteria、Specification、QBE、HQL、hbm 映射；支持 `@Embedded` / `@EmbeddedId`、嵌套 `@AttributeOverride`、Spring Data 嵌入前缀。组件路径命名可从工程配置探测或手动指定；实体所在模块无法确定时，可从工程配置与源码信号探测组件路径命名约定。
- **其他 SQL 来源**：JdbcTemplate、命名参数、自研 DAO 字符串 SQL，以及 xbatis、Easy-Query、MyBatis Dynamic SQL、Querydsl。支持可静态分析的字符串拼接、StringBuilder / StringBuffer 和源码可见的辅助方法返回值；多分支结果不一致或无法求值时不猜运行结果。
- **参数与预设**：按类型回填标量、集合与日期；未填写的参数默认保留 `?`，也可切换为 NULL / 空字符串。参数值、条件勾选与 foreach 值可保存为本条语句的命名预设。
- **XML 编辑辅助**：SQL 语法高亮、`#{}` / `${}` 区分、表列与参数补全、resultMap 属性补全、引用导航、查找用法和动态标签折叠；与其他 SQL 注入器按实际着色区间让位。

静态预览不等于运行结果：运行时分支、数据库内容、无法静态求值的变量需要手动提供参数。代码中的常量可能直接内联，而 ORM 实际执行时仍使用占位符绑定；请用运行期捕获核对真实执行。

### 运行期：捕获、筛选、溯源与历史

- **增强捕获**：默认开启，无需确认即向应用 JVM 接入 agent；可在设置页关闭并回落控制台捕获。正常随应用启动的通道可采集启动期 SQL；对已运行应用的热挂载能力受驱动与语句创建时机限制，必要时重启应用完整采集。默认不脱敏，可在设置中开启参数打码。
- **控制台回落**：支持 MyBatis、Hibernate、JdbcTemplate 日志；按线程分桶还原并发日志。ShardingSphere 的 Logic SQL 可作为主语句，Actual SQL 作为路由附注；「日志 → SQL」页签支持粘贴整段日志或主动点击「从剪贴板读取」。
- **新版列表**：SQL 高亮跟随 IDE 编辑器配色；单击只选中，底部快速预览按需打开，双击打开完整弹窗。支持类型筛选、搜索与大小写开关、多选复制、慢查询筛选、同结构聚类和按目标库分组。手动向上浏览暂停跟随，点击「滚动到底部」恢复。
- **降噪与定位**：精确 / 结构匹配屏蔽刷屏 SQL；同模板计数辅助排查 N+1。可按调用方法、请求或模板聚焦；增强捕获使用真实调用栈定位，JAR 业务包可单独登记，避免跳转停在框架入口。
- **最近执行记录**：从代码左侧图标右键打开浏览对话框，左侧可多选记录，右侧 SQL 可编辑、复制，双击继续进入完整预览。
- **历史与导出**：开启自动保存后全量落盘，按文件与条目两级浏览；支持正则 / 大小写检索、条目钉住防淘汰、两条 SQL 差异对比。日志与历史均可导出 SQL / CSV / TSV / 文本；SQL 格式包含回填参数、时间 / 来源 / 耗时注释与语句分号。

调用栈展示不会恢复原先未采到的帧，也不能凭静态快照补出跨线程调用链。控制台缺少参数、耗时或源码信息时，只展示已有证据，不伪造这些信息。

### 验证与生成：执行计划、关系图、风险提示

- **执行 SQL**：预览内嵌 IDEA 原生数据库控制台，可选择数据源并查看结果表格与报错。需要 Ultimate + Database Tools；无此环境仍可预览、复制 SQL。
- **EXPLAIN 与性能建议**：覆盖 11 种方言的计划入口；验证情况分别为 9 种真机 / 引擎级实证、OceanBase 文档级验证、SQL Server 官方文法级落地待真机。默认由原生控制台呈现；MySQL / PostgreSQL 结构化结果是默认关闭的实验选项。静态建议不是实际执行计划，也不能替代真实索引与数据分布验证。
- **SQL 快捷模板**：选中表名，或将光标停在表名上，通过右键运行 EXPLAIN / COUNT / 抽样查询；抽样默认 10 行，可在设置中调整。
- **JOIN 关系图**：查看联表与列信息，可导出 PNG / SVG；Community 环境使用降级绘图。
- **写入风险与回滚预案**：`${}` 直接替换有注入提示与确认，无界 UPDATE / DELETE 有风险清单，MCP 直连执行有超时护栏，IDE 原生控制台不受插件该护栏保护。INSERT 可推导逆向 DELETE；UPDATE / DELETE 缺少修改前数据时不会凭空生成恢复 SQL，需先查询快照、再按实际数据回填。回滚预案不是事务回滚，也不会自动执行。
- **SQL → Java**：支持单表与 MPJ JOIN、分组聚合、HAVING、DISTINCT、FOR UPDATE 及 Map / Object / VO 返回形态。复杂条件可用 `apply()` 透传；UNION 在 Java 生成层有降级提示，不能承诺所有 SQL 都一键变成等价 Wrapper。
- **CRUD 与分享**：实体 / 数据库表生成 CRUD，按映射处理自增、逻辑删除、乐观锁；支持可移植 SQL 导出、与上次预览对比，以及 Markdown / HTML 分享。

### MCP 工具

| 用途 | 工具 |
|---|---|
| 从源码与日志还原 SQL | `sqlv_render_sql`、`sqlv_render_logs` |
| 获取表结构与执行计划 | `sqlv_schema_context`、`sqlv_explain` |
| 检查风险、性能与 Mapper 契约 | `sqlv_check_sql`、`sqlv_perf_advice`、`sqlv_mapper_check` |
| 生成 CRUD、Java 代码与回滚预案 | `sqlv_crud_sql`、`sqlv_reverse_sql`、`sqlv_rollback_plan` |
| 检索本地 SQL 历史 | `sqlv_history_search` |

工具不提供任意写 SQL 执行入口。`sqlv_explain` 会访问配置的数据源，并对输入做只读查询校验；其他生成 / 预案工具不会自动执行生成结果。多数据源场景建议显式传 `dataSource`，表结构输出会披露实际使用的数据源。接入步骤与参数见 [MCP 文档](https://sql-visualizer.jvtools.cn/features/mcp) 和 [Agent 使用指南](docs/mcp/MCP-Tools-Agent-Guide.md)。

## 安装与开始使用

1. 在 `Settings → Plugins → Marketplace` 搜索 **SQL Visualizer**，或打开 [插件市场页面](https://plugins.jetbrains.com/plugin/33633-sql-visualizer) 安装。
2. 打开业务工程，在可识别的 Wrapper、Mapper XML、注解或 JPA 查询旁点击预览图标；也可按 `Alt+Shift+Q`（macOS 为 `Option+Shift+Q`）。
3. 在预览左侧切换条件、填写参数，右侧查看或复制 SQL。需要执行时先在 Database 工具窗口配置数据源。
4. 要查看真实执行，直接运行应用即可（增强捕获默认开启）；在「SQL Visualizer」工具窗口查看日志。若在设置中关闭了增强捕获，可开启应用 SQL 日志后使用控制台通道。

SQL → Java 的快捷键为 `Alt+Shift+J`。快捷键可在 Keymap 或插件设置页修改；预览图标可按 Wrapper、XML、注解、JPA / HQL 等来源分组开关。

全局设置在 `Settings → Tools → SQL Visualizer`；项目级设置 `SQL Visualizer（当前项目）` 可覆盖方言、增强捕获、逻辑删除、表名映射等，未覆盖项沿用全局。大多数设置无需重启 IDE；agent 接入及已有应用的采集边界请按实际提示处理。

详细步骤与配置项见 [快速开始](https://sql-visualizer.jvtools.cn/guide/getting-started) 和 [在线文档](https://sql-visualizer.jvtools.cn)。

### 兼容范围

| 环境 | 支持情况 |
|---|---|
| IntelliJ IDEA 2024.3+ | 最低构建号 243，兼容验证覆盖 243 / 253 / 262 平台 |
| Community | 静态预览、日志、代码转换等核心功能可用；数据库相关入口按环境降级 |
| Ultimate + Database Tools | 支持内嵌执行、数据源元数据、数据库表入口等能力 |
| IDEA 2025.2+ + MCP Server | 可向 AI 客户端暴露插件 MCP 工具 |

数据库方言：MySQL、PostgreSQL、Oracle、SQL Server、H2、SQLite、MariaDB、达梦、人大金仓、OceanBase（MySQL 模式）、openGauss。渲染方言支持不等于所有数据库、驱动与热挂载组合均已验证，具体边界见对应功能文档。

### 更新与安装问题

稳定版通过 JetBrains Marketplace 更新。需要抢先体验时，可在插件设置的「更新与诊断」中启用 Gitee 更新通道（默认关闭），或手动检查更新；支持忽略某个版本，热替换不可用时会提示重启。

- **市场下载出现 TLS 握手错误**：先重试并检查 IDE 的 HTTP Proxy / 网络设置；仍失败时下载插件 ZIP，使用 `Install Plugin from Disk` 安装。
- **提示缺少 Database Tools**：它不是静态预览、日志、SQL → Java 的必需条件；数据库执行相关能力才依赖该模块。旧版安装提示请结合当前插件版本判断。
- **数据库驱动无法下载**：这是 IDE 数据源配置的依赖准备步骤。内网环境可在 Database 数据源属性里指定本地驱动 JAR，或配置内网 Maven 私服。

## 内网、离线与数据边界

SQL 静态预览、代码转换、日志还原、关系图和 CRUD 生成在本地完成，不需要把源码或 SQL 上传到外部服务。执行与 EXPLAIN 需要连接你选择的数据库；增强捕获会监听本机回环端口，用于与应用 JVM 通信。

可选的外部访问包括：检查 / 下载插件更新、主动提交问题反馈、打开官网文档，以及帮助页演示图和交流群二维码的加载。无网时这些在线内容可能不可用，不影响本地静态分析。插件不采集使用遥测；主动反馈时请检查所选附加信息。若启用 MCP 并让外部 AI 客户端调用，返回的 SQL、表结构与历史内容会交给该客户端，其后续数据处理取决于你的客户端与模型服务配置。

本地数据不止捕获日志文件：预览 SQL 历史默认随预览持久化（可含参数快照），参数历史及命名方案在使用后可能以明文 JSON 保存参数值；两者均在 IDE 应用配置目录的 `mp-sql-visualizer.xml` 中，独立于自动保存捕获日志开关。「清除缓存」仅清运行缓存，不清这三类持久数据；设置页可分别打开捕获历史目录、确认清除预览 SQL 历史（含收藏和参数快照）、确认清除参数历史与命名方案。参数清理使旧面板和迟到写回失效，但不连带清除预览历史里的快照。

### 内网更新源

可通过 IDE JVM 系统属性将更新清单替换为内网地址，修改后需重启 IDE：

```text
-Dmpvs.update.manifest.url=http://nexus.intranet.local/plugins/update.json
```

清单中 `version` 与 `downloadUrl` 必填，以下仅为格式示例：

```json
{
  "version": "2026.10.16",
  "downloadUrl": "http://nexus.intranet.local/plugins/mybatis-plus-sql-visualizer-2026.10.16.zip",
  "changeNotes": "本次更新说明",
  "publishDate": "2026-10-16",
  "sinceBuild": "243"
}
```

内网构建还需要预置 Maven 依赖与 JetBrains 平台依赖，和插件安装后的离线使用是两回事。

## 开发与项目文档

常规构建使用 JDK 17、Gradle Wrapper 与 IntelliJ IDEA Ultimate 2024.3.7.1 编译基线。

| 命令 | 用途 |
|---|---|
| `./gradlew testFast` | 纯逻辑快速测试 |
| `./gradlew test --tests "完整测试类名"` | 指定测试 |
| `./gradlew testShards` | 分片全量测试，包含 agent 子项目测试 |
| `./gradlew runIde` | 启动开发沙盒 |
| `./gradlew buildPlugin` | 生成插件 ZIP |
| `./gradlew verifyGate` | 构建与平台兼容性门禁 |

日常修改先跑相关定向测试，收口统一跑全量；涉及构建依赖、平台 API 或发行时再执行构建与兼容性门禁。并行开发时各分支使用独立工作树与沙盒，具体约定见 [CONTRIBUTING.md](CONTRIBUTING.md#并行开发与工作树)。

- [功能规格](docs/需求文档/20260812-1058-MP-SQL可视化-需求文档.md)
- [开发计划](docs/开发计划/20260812-1058-MP-SQL可视化-开发计划.md)
- [架构设计](docs/架构设计/01-项目架构总览.md)
- [Marketplace 发布流程](docs/发行说明/Marketplace发布流程-说明文档.md)
- [变更记录](CHANGELOG.md)

## 参与贡献

欢迎提交 Issue 与 Pull Request。完整流程、分支模型、测试门槛与代码风格见 [CONTRIBUTING.md](CONTRIBUTING.md)。

- 新功能或行为变更请先开 Issue 讨论；拼写、文档和小范围修复可直接提 PR。
- 外部 PR 以 `dev` 为基分支，`master` 为发布分支。
- 提交前至少跑与改动相关的定向测试，并在 PR 中附命令与读数；全量门禁由维护者收口。
- 全量测试需要测试 demo 与本地 MySQL：先运行 `dev-support/test-demo/setup-test-env.ps1`（或 `.sh`），详见 [CONTRIBUTING.md](CONTRIBUTING.md#全量测试前置测试-demo-与本地数据库)。
- 开发环境、构建/测试命令与扩展指南见[开发与测试指南](docs/架构设计/03-开发与测试指南.md)。
- 提交即表示同意以 [Apache-2.0](LICENSE) 许可发布。

## 参考与致谢

开发中参考了以下插件的公开资料与交互设计，感谢各位作者：

- [MyBatisX](https://plugins.jetbrains.com/plugin/10119)（[源码](https://github.com/baomidou/MybatisX)）：Mapper / XML 导航与 SQL 补全。
- [MyBatisCodeHelperPro](https://plugins.jetbrains.com/plugin/9837)：日志转 SQL 与参数还原。
- [MyBatis Log Free](https://plugins.jetbrains.com/plugin/17898)（[源码](https://github.com/starxg/mybatis-log-plugin-free)）：控制台日志还原。
- [Mybatis Log Ultra](https://plugins.jetbrains.com/plugin/26550)：方法级分组与捕获通道。
- [JTracker](https://plugins.jetbrains.com/plugin/24694)：日志调用链定位。
- [Cool Request](https://www.coolrequest.dev)（[源码](https://github.com/houxinlin/cool-request)）：Java agent 字节码增强与 API 调试。

如果还需要在 IDEA 内调试 HTTP 接口，可以试试 [Cool Request](https://www.coolrequest.dev)。

## 联系作者

扫码添加作者微信或加入交流群。交流群二维码失效时，可从 [官网](https://sql-visualizer.jvtools.cn) 查看最新入口，或添加作者微信。

| 作者微信 | 交流群 |
|---|---|
| ![作者微信](https://sql-visualizer.jvtools.cn/wechat-qr.png) | ![交流群](https://sql-visualizer.jvtools.cn/group-qr.png) |

问题与建议请提交到 [官网需求池](https://sql-visualizer.jvtools.cn/about/feedback)。
