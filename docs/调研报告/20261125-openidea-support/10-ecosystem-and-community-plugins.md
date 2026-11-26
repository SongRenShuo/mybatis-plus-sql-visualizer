# 10 · OpenIDEA 生态与社区插件调研（MyBatis / SQL / 数据库）

> 调研目标：在我方「用 OpenIDEA 1.x 插件体系复刻 201 条功能」时，用「竞品在同样约束下怎么做事」作为「哪些功能真能做」的最硬证据，并给出可立住的差异化定位。
> 结论档位沿用《00-README》：**现成可做 / 变通可做 / 需上游 / 不可行**。

## 0. 数据来源与口径

| 来源 | 位置 | 关键读数 |
|---|---|---|
| 市场索引 | `C:\Users\USER\AppData\Roaming\ai.l0ng.openidea\plugins\market-index.json`（OpenIDEA 0.1.51） | **263 条版本记录 / 去重 137 个插件 id**；每条含 `capabilities`、`downloads`（插件累计、官方随附为 null）、`engine`、`homepage` |
| 社区注册表 | `F:\code\Java\Github\other\openidea-plugins` | `plugins/<publisher>.<name>.toml`（`repository`+`[[versions]]`+`sha256`）、`publishers/<owner>.toml`（owner+公钥） |
| 官方内置源码 | `F:\code\Java\Github\other\openidea\plugins\official\mybatis` | `plugin.toml` + `src/lib.rs`（1700 行，~30 单测） |
| 外部仓库 | GitHub API（metadata + git tree）+ raw 读 README/plugin.toml/关键源码 | 逐插件见 §2 |
| 宿主 API 事实 | 本机 openidea 仓 `wit/`、`plugins/README.md`、`docs/zh/plugins/*`（main @ a7b67347c，2026-11-25） | 文内 `file:line` |

口径：技术形态判断以 `plugin.toml` 的 `[capabilities]` 与 `[[contributes.*]]` 为权威，不采信 README 自述。基线约束（`00-README.md:26-39`）：1.x 无 Java 类型/AST（类型解析推迟到 API 2.0）、无跨语言语义模型、`database` 接口无 query/EXPLAIN、provider 调用 50ms 软/500ms 硬预算。**本生态几乎全部诞生于 2026-11-10~19（两周内），GitHub star 普遍 0~1、issue 多为 0，故「竞品怎么解约束」比「口碑」更可信。**

### 0.1 全市场能力申请分布（按 263 条记录计，`market-index.json` 直接统计）

| 能力前缀 | 记录数 | 风险档（`docs/zh/plugins/capabilities.mdx:36-57`） |
|---|---:|---|
| `process:` | 217 | 高 |
| `project.read` | 205 | 低 |
| `net:` | 180 | 中 |
| `events` | 145 | 低 |
| `editor.read` | 131 | 低 |
| `storage` | 123 | 低 |
| `clipboard` | 81 | 中 |
| `editor.write` | 53 | 中 |
| `project.write` | 43 | 高 |
| `secrets` | 33 | 中 |
| `vcs.read` | 31 | 低 |
| `ipc:` | 17 | 高 |
| `fs.read:` | 15 | 高 |
| `database.sources` | 14 | 中 |
| `vcs.write` | 12 | 中 |
| `ide.commands` | 5 | 高 |

读法：`process:*` 覆盖八成记录（多为 `process:git`27 / `process:java`17 / `process:mvn`14 / `process:gradle`5 这类构建与工具链），说明**市场对「插件起外部程序」已形成预期**；`java.agent` 不在 `capabilities` 里（由 `[[contributes.javaAgents]]` 派生，见 §6.5），索引统计看不到，但 `weigram.hotreload` 实证用户会批准。**关键发现：`database.sources` 的 14 条记录全部来自 aritti 的 spring/ddev/laravel/symfony，无一是 MyBatis/数据库插件——真正的连库插件（jpa-powertools）宁可走 `process:java` 也不用 `database.sources`，因为后者根本不能查询。**

---

## A. 生态现状表

### A.1 直系/相邻竞品

| 插件 | 定位 | 技术形态（capabilities / 贡献点） | 关键能力 | 明确做不到的 | 版本 / 热度 |
|---|---|---|---|---|---|
| `openidea.mybatis` MyBatis Navigator（官方内置） | MyBatis 3 mapper↔XML 导航 | 纯 Rust wasm；`providers`=definition/line-markers/completion（java+xml）；`capabilities`=**project.read**；无 web view、无 process、**无 documentIndexer** | 双向 gutter + Go to Definition（namespace/方法/statement id/`#{}`/resultMap/extends/include/注解 SQL/@ResultMap）；补全 statement id、`<sql>` refid、`#{}`、`@ResultMap` id | 不做语句生成、`@Param` quickfix、SQL 日志；**无 Find Usages/Rename**（provider 路线不建图）；大项目冷搜索可能超 500ms（靠「先按文件名找 `XxxMapper.xml`」+30s TTL 缓存缓解） | 0.4.2；内置捆绑，downloads=null |
| `qtvbwfn.mybatis-duo` MyBatis Duo | 官方 Navigator 的「超集替代」 | 纯 Rust wasm；**`documentIndexers`=[java,xml]** + providers(code-actions/java、completion/java+xml) + `markerIcons` + `consoleCommands`(**待上游 PR#5361**) + `locales/zh-CN.json`；`capabilities`=project.read/project.write/editor.read/clipboard | 双向导航（含 `#{}`参数/resultMap/include/注解 SQL/`@ResultMap`）；statement 生成 + Create Mapper XML；`@Param` quickfix；SQL 日志转换（BatsLog 式） | 无 iBatis 2.x；**无 SQL 面板/历史**（日志转换只把选中文本转成剪贴板）；project **workspace(窗口组) 下找不到成员项目 XML**；`consoleCommands` 依赖尚未合并的上游 PR #5361 | 注册表 0.3.2~0.3.6（仓内 dist 已到 0.3.8）；downloads=545；star 1、issue 2；created 2026-11-10 |
| `jiusi.mybatis-log` MyBatis Log | 日志→SQL「只做一件事」 | 纯 Rust wasm + **`views`(web/index.html, location=right)** + `settings.schema` + `statusBar`；`capabilities`=**editor.read + storage**（仅两个 Low） | MyBatis/MyBatis-Plus/Hibernate 日志还原；批量(≤300 条/512KiB)、类型化回填、参数对照表、耗时与 `Total:/Updates:`、按类型/关键字筛选、静态风险提示、语法高亮、历史 | **不读项目文件**（无「条目→源码」跳转）；不执行 SQL；**不拦截原生 Run/Debug 控制台输出**（自述「插件 API 没有 console-output 事件」，等 PR #5361）；无类型/AST | 0.2.1；downloads=**1417**（本组最高）；star 0；created 2026-11-11；restore.rs 27 单测 + lib.rs 6 单测 |
| `weigram.hotreload` Hot Reload | Java/MyBatis XML 热重载 | 纯 Rust wasm + **`javaAgents`(agent/hotreload-agent.jar, vmOptions `-XX:+AllowEnhancedClassRedefinition`, jdk=jetbrains)** + `events(files-changed)` + 可选 `process:mvn` + menus + settings + statusBar；required=project.read/events/ide.commands（+optional clipboard/process:mvn） | 保存即编译 + 对运行中 JVM redefine 类并刷新 mapper XML（增删改 statement）；合并 HotSwapAgent 2.0.5（Spring bean/controller/AOP reprocess） | **仅 Application/Spring Boot/JAR Application 运行配置**生效；Maven/Gradle goal、JUnit、Tomcat、远程 JVM 不加载 agent（`src/lib.rs:389`）；需 JBR/DCEVM | 1.1.0/1.2.0/1.3.2；downloads=297；star 0；created 2026-11-19；**GPL-2.0-only** |
| `ritsu.sqltoy-navigator` SqlToy Navigator | SqlToy `sqlId`↔`*.sql.xml` 跳转 | 纯 Rust wasm + **`documentIndexers`=[java,xml]** + providers(definition/line-markers/completion/diagnostics, language `*`) + menus；`capabilities`=**project.read + editor.read** | Java `findBySql("id")`↔XML `<sql id>` Go to Definition/Ctrl+Click/gutter/补全；引用不存在的 sqlId 红色报错；**Find Usages/Rename 由宿主从索引图提供（API 1.3+）** | 仅 sqlId↔sql.xml，无 SQL 转换/生成/执行；无面板 UI | 单版 0.1.0；downloads=691；star 0；created 2026-11-12 |
| `izzyjere.jpa-powertools` JPA Power Tools | JPA/JPQL + 数据库浏览 + 实体生成 + ER 图 | Rust wasm + **`process:java`(resources/JdbcBridge.java 走 JDBC 读元数据)** + **`net:repo1.maven.org`(下驱动)** + `customEditors`(*.erd.json, web) + `views`(databases 左/connection 右 web/generator 底 web) + providers(java)；required 全 low/medium，`process:java`/`project.write` 为 **optional 高权限** | JPQL 诊断/补全/跳转/quickfix；ER 图；连 PG/MySQL/MariaDB/SQLServer/Oracle/Db2 读 schema 元数据；由表生成 JPA 实体/仓库 | **只读元数据，不执行 SQL 文本**（JdbcBridge 仅 `DatabaseMetaData`）；密码存私有目录不可回读；SQLite 路径手输；MySQL ENUM/SET 只给 TODO | 0.1.0→0.2.0；downloads=458；star 0；created 2026-11-15 |
| `jiusi.everytools` EveryTools | 中文开发者纯文本工具合集 | Rust wasm + **`views`(web/index.html, right)** + settings；`capabilities`=**editor.read/editor.write/clipboard/storage** | 16 个一次性文本工具，含 **SQL→Mapper XML**、JSON↔POJO、Setter/Builder 链、Cron 中文、乱码修复等 | 纯文本一次性转换，无项目级索引/导航/执行；不读项目文件 | 0.1.0/0.1.1；downloads=902；star 0（fork 1）；created 2026-11-11 |

### A.2 旁证（非直系，验证「高风险能力市场接受度」与生态广度）

| 插件 | 定位 | 技术形态要点 | 热度 |
|---|---|---|---|
| `aritti.flyway` 0.5.0 | Flyway 迁移管理 | `process:flyway`/`process:mvn`/`process:java` + terminal；project.read/events/clipboard | downloads=1618，5 版本 |
| `aritti.liquibase` 0.5.0 | Liquibase changelog | `process:liquibase`/`process:git` + editor.*/project.write/storage | downloads=1571，5 版本 |
| `alivop.p3c` 0.1.7 | 阿里 P3C Java 规范 | **纯 WebAssembly（明示「no PMD/JDK」）**；project.read/events/editor.read，**零 process** | downloads=484，3 版本 |
| `fsantomero.maven-helper` 0.1.2 | Maven 依赖助手 | `process:mvn` + build.read + ide.commands | downloads=823 |
| `allen.arthas` 0.2.1 | Arthas 命令构造器 | `process:java` + `process:jps` + editor.read + clipboard | downloads=598 |

生态广度：`market-index.json` 263 条 / 137 唯一 id；按 `sql|database|jdbc|mybatis|hibernate|jpa|flyway|liquibase|orm` 过滤仅 **13 个唯一 id**，真正「MyBatis/SQL/数据库核心」的只有上表 7 个直系 + flyway/liquibase。**市场里没有第二梯队，先发优势仍在。**

### A.3 技术形态分布（对「我方能不能照做」最有用的一张表）

| 形态 | 代表 | 需要的权限 | 我方可用性 |
|---|---|---|---|
| A 纯 wasm 计算（文档内） | navigator、sqltoy、duo、p3c | project.read + editor.read | 高：日志还原、SQL 生成、校验、回滚计划都属此类 |
| B wasm + Web 视图 | mybatis-log、everytools、jpa(ER/生成器) | +storage | 高：SQL 预览面板、参数面板、历史列表可用 `views.web` |
| C wasm + documentIndexer | duo、sqltoy | project.read | **高：Mapper↔XML 图 + 白拿 Find Usages/Rename**（见 §6.1） |
| D wasm + `process`（外部 CLI） | flyway、liquibase、maven-helper、hotreload(编译) | process:<prog>（高） | 中：需 PATH 上有程序并批准 |
| E wasm + `process:java` + 自带 jar | jpa-powertools | process:java + net: | 中高：JDBC 桥、Java 解析 sidecar 都可行 |
| F wasm + `javaAgents` | hotreload | java.agent（高，由贡献点派生） | 中：只进三种运行配置、远程不加载、无宿主 IPC 通道 |

---

## B. 两条正面对照

### B.1 我方 Mapper 导航 vs `qtvbwfn.mybatis-duo`

我方相关功能（`08-our-feature-inventory.md`）：F05/F06 双向箭头、F19/F20 iBatis 2.x、F21/F59 MBG Example 还原、F124 条目→源码三态、F188 三个 `fileBasedIndex`、F189 数据源 schema 补全、F190 mapper XML 内 SQL 注入高亮、F191 双向导航。

| 能力 | 我方（IntelliJ 现状） | mybatis-duo | OpenIDEA 下是否仍成立 |
|---|---|---|---|
| 双向 gutter + Go to Definition（namespace/方法/statement/`#{}`/resultMap/include/注解 SQL/@ResultMap） | 有（PSI+索引） | **有**（已从 provider 迁到 documentIndexer） | **不构成差异**：duo 已用 indexer 实现（`src/nav.rs:1293 impl DocumentIndexer`） |
| Find Usages / Rename 跨 Java↔XML | 靠 `ReferencesSearch` | **有**：宿主从 indexer 图免费提供（`plugins/README.md:260`「API 1.3 also uses this graph for Find Usages and Rename」） | **不构成差异**：我方同样可用 documentIndexer 白拿 |
| iBatis 2.x（`<sqlMap>`）导航/Example/日志 | 有（F19/F20/F115） | **无**（只认 MyBatis 3 XML） | **仍成立**：纯文本解析，duo/sqltoy/官方全不覆盖 |
| MBG Example 条件按 mapper 调用点还原 | 有（F21/F59，`ReferencesSearch`+调用点） | 无 | **不成立/退化**：需按类型找调用点，1.x 无类型，最多文本启发 |
| resultMap column / `#{}` / OGNL 补全基于**数据库 schema**（F189） | 有（`DatabaseSchemaResolver`） | 只做文本内补全 | **部分成立**：需 schema，只能走 `process:java` JDBC 桥（高权限） |
| mapper XML 内 SQL 注入高亮/折叠/`#{}` 自动闭合（F190） | 有（`multiHostInjector`+lexer） | 无 | **部分成立**：`injections` API 1.4 可标范围（`language-features.mdx:248-257`），实际高亮取决于宿主 SQL 支持，**需实测** |
| `@Param` quickfix / statement 生成 / Create Mapper XML | 暂无完全对等件 | **有** | 反向差异（duo 领先） |

**B.1 结论**：Mapper 导航在 OpenIDEA 上是**拼平局(parity)**，不是差异化——duo 已用「documentIndexer 建图」做全并免费拿到 Find Usages/Rename。我方只有 **iBatis 2.x 全链路**是干净真差异；`schema 感知补全`、`XML 内 SQL 高亮` 属「有条件成立」。**不要把「PSI 级类型推断 / MBG 调用点还原 / 跨语言重构」写进卖点**——官方 `code-intelligence.mdx` 自陈做不到，duo/sqltoy 也没做，说明是「做不到」不是「没想到」。

### B.2 我方日志还原 vs `jiusi.mybatis-log`

我方相关（`08`）：F115 MyBatis(含 iBatis 2.x 三行组)、F116 Hibernate、F117 JdbcTemplate、F118 ShardingSphere；F76 类型感知回填（LIKE 自动百分号）、F89 日期族→ANSI 字面量、F94 方言能力矩阵、F96 格式化、F124 条目→源码、F167 写风险静态检查、F166 `sqlv_render_logs` MCP。

| 能力 | 我方 | jiusi.mybatis-log | OpenIDEA 下是否仍成立 |
|---|---|---|---|
| 日志源覆盖 | MyBatis/MPJ + Hibernate + **JdbcTemplate** + **ShardingSphere** + iBatis 2.x | MyBatis/MPJ + Hibernate + **P6Spy** + **log4j2 JSON** | **仍成立（互有胜负）**：纯文本零门槛；我方独有 JdbcTemplate/ShardingSphere/iBatis2，jiusi 独有 P6Spy/log4j2，补齐即拉开 |
| 类型化参数回填（引号/null/日期/布尔/枚举转义） | 有（F76），另含 LIKE 自动百分号 | 有（0.2.0 完整规则表） | 基本持平，我方 LIKE 略强 |
| 参数对照表 / 耗时 / 影响行数 / 高亮 / 历史 | 有（F33/F35） | **全部有** | 不构成差异 |
| 静态风险提示 | 有（F167 无界写检测 + F94 方言矩阵：DELETE LIMIT/UPSERT/IDENTITY） | 有（无 WHERE、TRUNCATE/DROP/ALTER、`SELECT *`） | **仍成立（略强）**：方言能力矩阵更硬，但差距小 |
| 与项目源码联动（条目→mapper XML/Java 三态） | 有（F124，PSI+索引） | **无**（明确不读项目） | **变通可做**：退化为 project.read+documentIndexer+文本搜索，非精确 |
| 作为 MCP 工具暴露给 AI | 有（`sqlv_render_logs`/`sqlv_check_sql`） | 无 | **仍成立**：可经 `contributes.mcpTools` 落地（§6.7），竞品全未用 |

**B.2 结论**：日志还原是**我方主战场**，但护城河不在「核心还原体验」（jiusi 已做到位、1417 下载的口碑），而在三处：**更广日志源覆盖**（JdbcTemplate/ShardingSphere/iBatis2）+**更强的写安全/方言能力体检**+**与源码联动 + MCP 工具化**。三者都是纯逻辑/低权限，OpenIDEA 约束下不受损。

---

## C. 差异化定位建议（1.x 无类型解析下，5~8 个能立住的卖点，按「可立住 × 差异度」排序）

| 排序 | 卖点 | 为什么能立住（API 支撑） | 差异度 | 档位 |
|---|---|---|---|---|
| 1 | **四族+日志还原与 SQL 重放面板**（MyBatis/MPJ、Hibernate、JdbcTemplate、ShardingSphere、iBatis2；类型化回填+参数对照+耗时/影响行数+历史） | 纯文本，`editor.read`+`storage`+`views.web`（jiusi 已证低权限可行） | 高（JdbcTemplate/ShardingSphere/iBatis2 市面无对应） | 现成可做 |
| 2 | **SQL 写安全闸门 / 静态体检**（无 WHERE 的 UPDATE/DELETE、恒真条件、纯通配 LIKE、常量 WHERE、方言 DELETE LIMIT/UPSERT/IDENTITY 能力矩阵、写风险分级） | 纯逻辑规则引擎（F167/F94） | 中高（方言矩阵强于 jiusi 基础提示；对应上游 open PR #1496，尚未有人做到插件里） | 现成可做 |
| 3 | **iBatis 2.x（`<sqlMap>`）全链路**：导航+Example 文本级还原+日志解析 | 纯文本（F19/F20/F115） | 高（duo/sqltoy/官方全不覆盖） | 现成可做 |
| 4 | **MyBatis/iBatis 图索引**：`documentIndexers` 建 namespace↔statement↔`#{}`↔resultMap 图，白拿 gutter+Go to Definition+**Find Usages+Rename** | `documentIndexers`（`plugins/README.md:255-266`）；duo/sqltoy 实证；绕开 provider 500ms 预算 | 中（导航本身 parity，但起点/覆盖可超官方） | 现成可做（与 duo 同法） |
| 5 | **SQL→Java 反向离线生成**（QueryWrapper/LambdaQueryWrapper/Wrappers）+可配置「实体列↔字段」映射表 | 解析+生成纯逻辑（F151），无类型时退化为命名推断/配置映射 | 中高（duo/jiusi 都没有；正好绕开「无类型」） | 变通可做（实体映射需退化） |
| 6 | **mapper XML 内 SQL 注入**：`injections`(API 1.4) 标范围+注入范围定向补全/高亮/折叠/`#{}` 自动闭合 | `injections`+providers 过滤（F190） | 中（质量取决于宿主 SQL 支持，需实测） | 变通可做 |
| 7 | **数据库 schema 桥（可选高权限）**：`process:java` JDBC 桥做 schema 浏览、列补全数据源；按需只读 EXPLAIN/执行 | jpa-powertools 已证 process:java+net:maven 可读元数据；执行需自扩展（§6.4） | 中（补足「连库/执行」现实路径，默认关闭） | 变通可做（高权限，可选） |
| 8 | **产品化打磨**：右侧 web 面板、settings schema、状态栏计数、中文双语、Suggested for This Project | `views.web`+`settings.schema`+`statusBar`+`locales`+注册表 `suggest`（jiusi/duo 已示范） | 低但低成本，显著影响完成度观感 | 现成可做 |

明确**不建议**列卖点（1.x 做不到或代价过大）：Wrapper 链→SQL 的 PSI 级类型推断、按解析后类型精确匹配重载、跨语言重构、MBG 调用点精确还原、经 IDE `database` API 直接 EXPLAIN（无 query）、原生控制台输出拦截（无事件，等 PR #5361）。

补充「我方独有清单」（逐条可对照竞品源码；`08` 里竞品全覆盖度）：

| 我方能力 | 对应 MCP 工具 | 竞品覆盖 |
|---|---|---|
| Wrapper/QueryWrapper/LambdaQueryWrapper 链静态渲染 | `sqlv_render_sql`、`sqlv_reverse_sql` | 无 |
| Mapper XML 语句渲染（动态标签求值 + `#{}` 绑参） | `sqlv_render_sql`、`sqlv_mapper_check` | 官方只做「折算文本用于解析」，不渲染不绑参（PR #3641 已合并部分相关） |
| 方言校验与性能建议 | `sqlv_perf_advice`、`sqlv_check_sql` | 无（PR #1496 只做「不安全查询警告」，且 open） |
| EXPLAIN / 计划可视化 | `sqlv_explain` | 无（IDE 自身有 Explain Plan，插件拿不到） |
| 写安全预检 + 回滚计划 | `sqlv_check_sql`、`sqlv_rollback_plan` | 无（#1496 open，且无回滚计划） |
| 表/列上下文（真实库元数据） | `sqlv_schema_context` | IDE 有、插件无（需 database.query 或 JDBC 桥） |
| MyBatis-Plus 实体/CRUD 语句生成 | `sqlv_crud_sql` | 无（duo 生成 XML 骨架，无 MP 语义） |
| inlay 列名/类型提示 | — | 无（竞品未用 inlay-hints） |
| 11 个 MCP 工具 | 全部 | 无（五家 plugin.toml 均无 `[[contributes.mcpTools]]`） |

---

## D. 定价 / 分发 / 遥测 / 高风险能力接受度

### D.1 定价
覆盖插件**全部免费开源**：官方 Navigator 内置（Apache-2.0）；社区许可 Apache-2.0（duo/jiusi/sqltoy/everytools/flyway/liquibase/p3c/maven-helper）、MIT（jpa-powertools）、GPL-2.0（hotreload）。`market-index.json` 无价格/许可证/评分字段，**市场当前无付费通道**。建议沿用开源免费分发。

### D.2 遥测
无插件声明遥测或联网后端。jiusi 两件套明示「不联网、没有账号和后端」；sqltoy/duo/official/p3c 只读本机。唯一联网是 jpa-powertools 的 `net:repo1.maven.org`，仅首次连接下载 JDBC 驱动并校验 SHA-1。**市场默认零遥测**；我方若要联网须显式声明 `net:<host>` 并在描述说明。

### D.3 高风险能力接受度（市场怎么体现）
风险分级（`capabilities.mdx:36-57`）：`project.read`/`editor.read`/`storage`=**低**；`clipboard`/`clipboard.read`/`net:<host>`/`net:ask`/`database.sources`=**中**；`process:<program>`/`java.agent`=**高**。市场证据：
- **process 被广泛接受**：flyway(1618)/liquibase(1571)/maven-helper(823)/arthas(598) 都在用 `process:*`，下载量不低；全市场 217 条记录含 process。
- **共识是「optional + 按需 + 逐条解释」**：jpa-powertools 把 `process:java`/`project.write`（高）与 `net`/`clipboard`（中）全设 `optional`，README 权限表逐条写「不给该权限时，除数据库功能外其他都能用」。
- **反向也有用户**：p3c 把「纯 WebAssembly，无需 JDK/PMD」当卖点，说明零进程也是差异化诉求。
- **`java.agent` 已被采用**：hotreload 用 `[[contributes.javaAgents]]` 注入 jar，`process:mvn` 设 optional。
- **`database.sources` 到不了查询**：能力表只到「管理数据源/凭据」（`capabilities.mdx:52`），DB 插件只能改走 `process:java`。

### D.4 分发形态
统一路径：源码 GitHub → `.oiplugin` 发 GitHub Releases → 在社区注册表 `l0ng-ai/openidea-plugins` 提 PR 加 `plugins/<id>.toml`（repository+versions+sha256）与 `publishers/<owner>.toml`（owner+公钥）；打包 `openidea-plugin pack --key --publisher` 签名、`verify` 校验；条目 `suggest` 让插件出现在含 `pom.xml`/`logback-spring.xml` 等项目下「Suggested for This Project」（jiusi 的 `publish/` 是完整模板）。官方内置随 IDE release 捆绑。

### D.5 对我方建议
日志/SQL 面板类走**低权限**（editor.read/storage/project.read/clipboard）对齐 jiusi「能力即权限」叙事；数据库执行/EXPLAIN 类走 **optional `process:java` + `net:<maven host>`**，默认关闭、设置页可撤销、README 逐条解释——jpa-powertools 验证过、市场接受。

---

## E. 竞品用了我们没想到的 API 组合（可借鉴点）

1. **`documentIndexers` 建 MyBatis/SqlToy 关系图（最值得抄）**
   - 证据：duo `plugin.toml` `[[contributes.documentIndexers]] extensions=["java","xml"]`；`src/nav.rs:1293 impl DocumentIndexer`，用字符串 key（`mapper:<FQN>#<method>`、`::param::`、`::rm::`、`::sql::`）把 Java 方法与 XML statement/param/resultMap 共键，`triple()`（`nav.rs:1284`）同发 definitions/references/markers；sqltoy 同法，README 明说「Find Usages / Rename 由宿主从索引图提供」。
   - 上游支撑：`plugins/README.md:255-266`——indexer 后台批次跑、**API 1.3 起还用它做 Find Usages 和 Rename**，只需 `project.read`，不加新 WIT/能力。
   - 关键动机：duo 注释（`nav.rs:1271-1281`）写明——原 provider 路线在 1000+ XML 项目上冷 `search_text` 超 500ms 编辑预算，触发「#2505 超时崩溃→重建 Wasm 实例→丢 TTL 缓存」螺旋；改 indexer 后箭头/跳转从内存答、从不回调插件。**我方 Mapper 导航应以 documentIndexer 为起点，而非 provider。**

2. **`contributes.views` + `web = "web/index.html"` 做右侧面板（日志/SQL 面板标准形态）**
   - 证据：jiusi.mybatis-log `[[contributes.views]] id="mybatis-log" location="right" web="web/index.html"`，逻辑在 `src/lib.rs`（`host::post_view_message`/`host::storage`/`host::set_status_item`）+ `web/index.html` 渲染；everytools 同法。
   - 借鉴：我方「SQL 日志/日志→SQL/历史」多 tab 应做成**一个右侧 web view**（wasm 只做解析与状态），而非 Swing 多 tab。

3. **`contributes.customEditors` + web 做图形/表格编辑器**
   - 证据：jpa-powertools `[[contributes.customEditors]] id="erd" patterns=["*.erd.json"] web="web/erd/index.html"`；官方 csv-editor 用 `customEditors` 声明式表格。
   - 借鉴：SQL 预览结果/ER/计划 Diff 可用 `customEditors` 或 `views.web` 承载。

4. **`process:java` + 随包 `resources/*.java` JDBC 桥 + `net:` 下驱动（`database.wit` 无 query 时的「连库/执行」现实路径）**
   - 证据：jpa-powertools optional `process:java`/`net:repo1.maven.org`；`resources/JdbcBridge.java`(19KB) 由 Java 11+ 跑，**只调 `DatabaseMetaData`、从不执行 SQL 文本**；驱动从 Maven Central 下并 SHA-1 校验；密码存插件私有目录（不可回读）。
   - 关键：这是绕开「database 接口无 query」的**唯一已验证路径**。EXPLAIN/只读执行要落地，须把 JdbcBridge 从「只读元数据」扩到「执行 SQL」，并用 optional 高权限+用户确认。**结论：执行能力是「变通可做(高权限)」而非「不可行」，但绝不能默认开启。**

5. **`javaAgents` + `events(files-changed)` + optional `process:mvn`（保存即热更）**
   - 证据：hotreload `[[contributes.javaAgents]]` 注入 jar，订阅 `EventKind::FilesChanged`，`host::execute_command("build.buildModule")` 或 `host::open_terminal_session` 编译，再让 agent 刷新 mapper XML。
   - 借鉴：可做「保存 mapper XML→重新解析/重索引/校验」的轻量版。

6. **`injections`（API 1.4）宿主文档内嵌语言**
   - 证据：`wit/injections.wit`（`injections: func(doc)->list<injection{range,language}>`），`plugins/README.md:1743-1764`（≤1000 区间/32 内嵌语言/单文件≤2MiB）。
   - 借鉴：mapper XML 内 SQL 段落声明为 `sql`，宿主路由 SQL 高亮/补全到注入区间——对标 F190。注意上游 PR #3641「feat(database): SQL in MyBatis mapper XML」**已合并**，说明「XML 内 SQL」是上游正在补齐的方向，值得盯。

7. **`contributes.mcpTools` + `mcp-provider.call` 把纯逻辑能力暴露给 AI agent**
   - 证据：`00-README.md:30`——`mcp` 接口做内置 MCP Server 的 stdio 发现，工具暴露走 `contributes.mcpTools` + 导出 `mcp-provider.call`。
   - 借鉴：我方 `sqlv_*` 11 个 MCP 工具可落成 `contributes.mcpTools`。**竞品全都没用这条**——潜在差异点。工具名会被命名空间化为 `{plugin_id}__{name}`；单名须匹配 `[a-z0-9_]{1,40}`、命名空间化后整名 ≤64 字节（`crates/ide-host/src/plugins/mcp.rs:31-39`），故插件 id 需控制在约 20 字节内。

8. **`consoleCommands`（duo 0.3.8 声明，对应上游 open PR #5361）**
   - 证据：duo `plugin.toml` `[[contributes.consoleCommands]] command="convert-sql-log" label="Copy MyBatis SQL" selectionContains="==>  Preparing"`，change-notes 写「需要认识该贡献点的宿主」；该贡献点由 **PR #5361「feat(plugins): console commands — a plugin's item in the Run console's Copy menu」**（作者 qtvbwfn，**state=open，未合并**）提出。本机 openidea main（@a7b67347c）全仓 grep `consoleCommands`/`selectionContains` **零命中**，唯一 console 相关是 1.12 的 `ui.set-console-rules`（控制台**高亮规则**，`crates/ide-plugin/src/runtime.rs:278`），不是「控制台选区触发插件命令」。
   - **结论：duo 这条是「等上游 PR #5361 合并」的前瞻声明，不可计入 1.x 可得能力**。这也印证 jiusi「插件 API 没有 console-output 事件」判断在当前上游成立。

---

## 对我方复刻的直接含义（衔接其它分册）

- **可 1:1 迁移、不受损**：日志还原（四族+）、SQL 格式化、参数类型化回填、SQL→Java 反向生成、写风险静态体检、SQL→Mapper XML 一次式工具——即 `08` 里「纯逻辑」占 63% 行数的部分，用 `editor.read/storage/project.read + views.web + settings` 即可。
- **换实现形态但能达成**：Mapper/iBatis 导航→`documentIndexers`；mapper XML SQL 高亮→`injections`；面板→`views.web`；设置→`settings.schema`。
- **受限或退化**：Wrapper 链类型推断、MBG 调用点还原、跨语言重构、精确「条目→源码」三态跳转（退化为文本匹配）、schema 感知补全（需 JDBC 桥）。
- **需上游/不可行**：经 `database` API 直接 EXPLAIN/执行、原生控制台输出拦截、`consoleCommands`（PR #5361 未合并）。

## 进入策略（建议）

1. **命名与身份**：id 用 `mybatisplus.sqlvisualizer` 之类（前缀须 publisher 名，签名时强制）；市场同域命名 `openidea.*`/`qtvbwfn.*`/`jiusi.*`，我方用独立 publisher。
2. **避免重复箭头**：duo README 已把「两个导航插件同装会画两遍」写成用户须知。我方应默认**不**声明 `line-markers`（或开关默认关）+ 文档写「与 Navigator/Duo 二选一」；`extensions` 接口只能读自己声明的扩展点（`wit/extensions.wit`），**无法自动探测别家插件是否启用**，只能靠用户设置。
3. **权限收敛叙事**：`process:java`/`java.agent` 设 `optional`，拒绝时降级到「只做静态渲染与校验」——jiusi「零新能力升级」是成功套路。
4. **许可**：我方 Apache-2.0；官方与 duo 也 Apache-2.0（duo 明说解析层移植自官方，可反向参考）；**hotreload GPL-2.0-only，禁抄代码**。
5. **发布门槛**：注册表 PR（`plugins/<publisher>/<id>.toml` + `publishers/<publisher>.toml`，jiusi `publish/` 为样本）+ `openidea-plugin pack --key --publisher` Ed25519 签名；未签名安装会警告。

## 未解决 / 需上游确认

1. **`database.query` / EXPLAIN 缺口**：插件 API 无执行函数（`database.wit`），只能 `process:java` 自起 JDBC 桥。是否向上游提 `database.query` WIT/宿主 PR，需在 04 分册与上游确认。
2. **`consoleCommands`（PR #5361）合并后语义**：插件拿到的是**选区文本**还是**可订阅输出流**？后者才决定能否做「实时 SQL 捕获面板」。当前 PR open，duo 已先行声明，我方不宜依赖。
3. **`views.web` / `injections` 下 SQL 渲染质量**：`injections` 只标语言范围，实际 SQL 高亮/补全取决于宿主是否内置 SQL 支持，**需真机 runIde/安装版实测**（本轮未跑 GUI，标为环境受限未验证）。上游 PR #3641「SQL in MyBatis mapper XML」已合并，需读其落点确认。
4. **`process:java` 桥执行任意 SQL 的可行性与安全边界**：jpa 只验到「读元数据」，执行 SQL 需自扩展；高权限、需用户确认，安全评估待 04/05 分册。
5. **口碑证据薄弱**：`market-index.json` 只有累计 `downloads`（非安装量/评分/评论），GitHub star 普遍 0~1；生态整体诞生于 2026-11-10~19，无法给出稳定口碑排序。`openidea.mybatis` 的 downloads=null（内置捆绑无统计）。
6. **版本领先/滞后**：duo 仓内 dist 已到 0.3.8，市场索引与注册表仍 0.3.6；jiusi 本地 0.2.1 已发但 README 自述「市场条目仍 0.1.0」。引用版本号以注册表/市场为准，仓内 dist 仅功能核对。
7. **许可风险**：hotreload GPL-2.0-only（合并 HotSwapAgent 2.0.5）。借鉴其 agent 实现需评估 GPL 传染；duo 解析层移植自官方 Apache-2.0 并注明，属合规示范。
8. **未获取项**：`alivop.p3c`/`fsantomero.maven-helper`/`allen.arthas` 仅核 `plugin.toml` 能力与 README，未逐行读源码（旁证用途已够）；`aritti` 系列源码未读（市场条目+registry 已够定性）。

## 附：核对命令（可复现）

```bash
# 市场索引统计（只读）
python -c "import json;d=json.load(open(r'C:\Users\USER\AppData\Roaming\ai.l0ng.openidea\plugins\market-index.json',encoding='utf-8'));print(len(d['plugins']),len({p['id'] for p in d['plugins']}))"
# 竞品 plugin.toml（令牌只放请求头）
curl -s -x http://127.0.0.1:7890 -H "Authorization: Bearer $TOKEN" \
  https://raw.githubusercontent.com/qtvbwfn/openidea-mybatis-duo/main/plugin.toml
# 上游 PR 状态
curl -s -x http://127.0.0.1:7890 -H "Authorization: Bearer $TOKEN" \
  https://api.github.com/repos/l0ng-ai/openidea/pulls/5361
```
