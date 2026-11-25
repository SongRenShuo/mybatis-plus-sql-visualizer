# OpenIDEA 全面支持调研（2026-11-25）

本目录存放「把 MyBatis-Plus SQL Visualizer 的功能在 OpenIDEA 上复刻」的调研分册。分册由并行子代理产出，主报告与开发计划在汇总后落盘。

## 分册索引

| 编号 | 文件 | 内容 | 状态 |
| --- | --- | --- | --- |
| 01 | 01-plugin-api.md | 插件 API（WIT 1.16.0）全量测绘、能力门槛、沙箱边界 | 进行中 |
| 02 | 02-official-mybatis-and-plugin-patterns.md | 官方 mybatis 插件与官方示例范式 | 进行中 |
| 03 | 03-java-kotlin-bridge-and-sidecar.md | Java/Kotlin 语义来源、sidecar 方案可行性 | 待启动 |
| 04 | 04-database-sql-mcp.md | 数据库/SQL/MCP 能力边界 | 待启动 |
| 05 | 05-run-debug-console-java-agent.md | 运行控制台、javaAgent、JVM 探针 | 待启动 |
| 06 | 06-ui-surfaces.md | 工具窗口/Web 视图/内嵌提示/设置/双语 | 待启动 |
| 07 | 07-build-pack-publish-license.md | 打包签名、市场发行、许可合规 | 待启动 |
| 08 | 08-our-feature-inventory.md | 我方功能与代码域清点（移植底表） | 进行中 |
| 09 | 09-upstream-architecture.md | 宿主架构、能力实现位置、上游演进成本 | 进行中 |
| 10 | 10-ecosystem-and-community-plugins.md | 社区/市场生态与直系竞品 | 完成 |
| 11 | 11-dev-env.md | 开发环境审计与安装（Rust/wasm32-wasip2/NVM Desktop） | 完成 |
| 12 | 12-sibling-repo-coordination.md | 同级多仓协同、能力复用、经验继承机制 | 完成 |

## 已核实基线事实（主控实测，可复核）

来源：`F:\code\Java\Github\other\openidea`（main @ a7b67347c，2026-11-25 11:22 拉取）。

1. OpenIDEA 不是 IntelliJ 平台分支：Tauri 2 桌面壳（Rust）+ TypeScript 前端 + wasmtime 插件引擎；Java 智能来自 JDT LS，Kotlin 来自 Maven 构建快照桥（`java/kotlin-bridge/README.md`）。本机安装版 0.1.51（`D:\RuanJian\OpenIDEA\openidea.exe`）。
2. 插件是沙箱化 WebAssembly 组件（component model / WASI preview 2）。版本要分三层看：`main` 开发版 **1.16.0**（`wit/plugin.wit` 与 `sdk/rust/openidea-plugin/wit/plugin.wit` 一致，两份 `diff -rq` 无差异）；**已发布** 1.15.0（`sdk/rust/openidea-plugin/tests/api/released/plugin.wit` 的 package 行）；本机 0.1.51（2026-11-24 发布）随附 **API 1.15**（GitHub Release v0.1.51 正文「Plugin API 1.15」段），1.16 仍在 main 上开发（`plugins/README.md:430-432`）。线上文档站 `docs/zh/plugins/index.mdx` 仍写 1.6.0、更新日志只到 0.1.40——**文档站落后仓库约十个 API 版本**，一律以仓库 wit 为准，`engines.openidea` 按 1.15 起声明。
3. 插件取不到 AST/PSI。可用的项目读取面只有：`project.read-file`、`find-files(glob)`、`search-text(regex)`、`symbols(模糊符号搜索，来自宿主内存索引且不含未保存编辑)`（`workspace.wit`）。
4. 导航/装订区有专用通道：`document-indexer` 跨文件关联图（definitions/references/markers/aliases，宿主自己回答 Go to Declaration 与 gutter 图标，不再回调插件，支持 `alias` 前缀继承）（`indexing.wit`）；`providers.definition` + `providers.line-markers` + `code-actions`（`providers.wit`）；`language-features.gutter-actions`（`language-features.wit`）。
5. 编辑路径预算：provider 调用 50 ms 软上限 / 500 ms 硬中断；格式化另给 5 s（`providers.wit`、`language-features.wit` 头注释）。重分析必须自建缓存。
6. `database` 接口只管理「插件自己的项目数据源」与凭据、触发 IDE 自己的连接/内省，**没有执行 SQL 的函数**（`database.wit`）。方言驱动清单只有 sqlite/postgres/mysql/sqlServer/clickhouse/oracle。
7. `mcp` 接口只做「内置 MCP Server 的 stdio 启动参数发现」；工具暴露走 `contributes.mcpTools` + 导出 `mcp-provider.call`（`mcp.wit`）。
8. 外部程序：`process:<program>` 在 PATH 解析并固定内容哈希，文件被替换（如 JDK 小版本更新）需用户重新批准；随包分发的可执行文件必须由用户先加入 PATH 或在设置页手动指向（`plugins/README.md` container-runner 段、`plugins/official/container-runner/README.md`）。单插件最多 8 个常驻子进程，stdout/stderr 各流可流式读取至 128 MiB（`io.wit` process 段）。
9. Java 侧另有 `java.agent` 能力：插件随包带一个 jar，IDE 在 Application / Spring Boot / JAR Application 运行配置的 VM 选项后追加 `-javaagent`，运行与调试都生效；测试、Maven/Gradle 目标、应用服务器、远程 JVM 不支持（`docs/zh/plugins/capabilities.mdx`）。
10. 市场已有直系竞品（读自本机 `market-index.json`，263 条记录）：`openidea.mybatis`（官方 MyBatis Navigator 0.4.2）、`qtvbwfn.mybatis-duo` 0.3.6（双向导航 + SQL 日志转换）、`jiusi.mybatis-log` 0.2.1（日志还原）、`weigram.hotreload` 1.3.2（对运行中 JVM redefine 类并刷新 MyBatis XML）、`ritsu.sqltoy-navigator`、`izzyjere.jpa-powertools`。
11. 宿主硬限额（`docs/zh/contribute/architecture/plugin-host.mdx`，取值源 `crates/ide-plugin/src/types.rs` 的 `Limits`）：单次调用 fuel 5·10⁹；命令/事件/激活/停用墙钟 5 秒；调用 `tasks.begin` 后 60 秒；编辑器 provider 软 50 ms / 硬 500 ms；线性内存 256 MiB；连续 3 次崩溃标记 Failed；空闲 300 秒回收。一个插件一个 actor 线程，**同一实例串行执行、从不并发**。
12. 官方在 `docs/zh/contribute/architecture/code-intelligence.mdx` 明确自陈：**没有 IntelliJ 意义上的 PSI，也没有跨语言语义模型**——语义来自各语言 LSP，IDE 自有索引与 tree-sitter 只是语法层，且「需要同时理解多种语言的重构、按解析后类型匹配的结构化搜索在这套架构上做不到」。这是「MyBatis-Plus Wrapper 类型推断 + Java↔XML 跨语言关联」的一级约束。
13. 远程项目下插件跑在 `openidea-server` 侧（文件、进程、网络、诊断都在远程机），`process:` 在远程机解析固定，密钥留桌面端钥匙串；项目插件（`.openidea/plugins/`）目前不支持远程。`process` / `database.sources` / 运行目标只在受信任项目可用（`docs/zh/contribute/security.mdx`、`remote.mdx`）。
14. **执行 SQL 的能力在 IDE 里有、在插件 API 里没有**：`ide-db` crate 自己做「数据源、连接、元数据、查询、表编辑」（`docs/zh/contribute/architecture/crates.mdx` 工具与集成表），`database.wit` 却没暴露 query。也就是说这是**插件 API 的暴露缺口，不是 IDE 能力缺口**——想补只能给上游加 `database.query` 一类函数，改动落点是 `wit/database.wit` + `crates/ide-db` + `crates/ide-plugin` 宿主端 + `sdk/rust/openidea-plugin` + `tests/api/released` 快照。
15. 新仓的开发环与测试面比主仓轻得多：插件是独立 workspace、`cargo build --release --target wasm32-wasip2`；`openidea-plugin build` 自带 `check`（等价于从磁盘安装的校验：清单、每个贡献、SVG 消毒、组件导入只有插件 API 与 WASI、函数签名逐一对齐）；`openidea-plugin link` 后设置 › 插件 › Reload 即热更；SDK 带 mock host 可 `cargo test` 直接测逻辑，不必起 IDE。OpenIDEA 主仓自己的测试分层（cargo nextest / Vitest / Playwright / 冒烟）不适用于插件仓（`docs/zh/contribute/setup.mdx`、`testing/unit-e2e.mdx`）。
16. **类型解析官方排到 2.0**：`plugins/README.md` 在 `Project::symbols` 章节原话「Type resolution and member queries remain deferred to the asynchronous API 2.0 model.」；配合 `wit/host.wit` 禁止插件向语言服务器发 `textDocument/*`（只允许服务器自有方法），可得结论：**1.x 上插件拿不到 Java 类型/成员解析**。这意味着 Wrapper 链→SQL、实体字段推导、JdbcTemplate 变量推导这些命根子功能，只能走「自建文本解析 + 自建索引」「随包 native sidecar 外挂解析」「推动上游提前开放」三条路，**1:1 全功能复刻在当前 API 下不成立**。
17. 竞品与上游的实证（均已用 GitHub API / raw 抓取核实，2026-11-25）：
    - PR `l0ng-ai/openidea#1300`「feat(mybatis): mapper navigation, generation, @Param support and SQL log conversion」由 `qtvbwfn` 提交，状态 **closed 未合并**；同一作者随后发布 `qtvbwfn.mybatis-duo` 插件——说明**宿主不收领域特性，官方路线是让作者做插件**。
    - PR `#5361`「feat(plugins): console commands — a plugin's item in the Run console's Copy menu」**仍 open**，即「插件读运行控制台输出」尚无 API。
    - `izzyjere.jpa-powertools`（MIT）的 `plugin.toml` 实证了「插件执行 SQL」的工程解：`optional = ["project.write","clipboard","process:java","net:repo1.maven.org"]`，注释写明 `process:java` 是「the JDBC bridge (Java 11+) that reads database metadata」，JDBC 驱动从 Maven Central 下载并校验校验和。
    - `jiusi.mybatis-log`（Apache-2.0，1417 次下载）能力仅 `["editor.read","storage"]`，是**纯编辑器文本还原**，不读项目文件——这正是我方「项目感知的日志还原（读 mapper XML 反推类型）」的差异空档。
18. 许可澄清（实测 pom，不采信口头转述）：我方生产依赖 `com.github.jsqlparser:jsqlparser:5.3`（`build.gradle.kts:141`），其 pom 的 `<licenses>` 同时列 LGPL-2.1 与 Apache-2.0，license-maven-plugin 配置为 `dual_lgpl_ap2`——**双许可，可择一按 Apache-2.0 使用**。因此「Rust 侧换 `sqlparser-rs`（Apache-2.0）」与「Java 引擎做 native sidecar 随包分发」两边许可上均无障碍，但发行物必须附 NOTICE/许可证全文并显式记录所选分支。

## 结论口径

每份分册的结论必须区分四档，不得混写：

- 现成可做（接口已提供，仅需按新形态重写实现）
- 变通可做（缺直接接口，但可用 documentIndexer / process / javaAgent / LSP / mcpTools 组合达成）
- 需上游（必须给 OpenIDEA 提 WIT/host PR）
- 不可行（沙箱与模型上做不到，或代价远超收益）