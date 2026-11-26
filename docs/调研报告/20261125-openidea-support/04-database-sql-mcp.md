# 04 数据库 / SQL / MCP 能力边界

调研对象：`F:\code\Java\Github\other\openidea`（main @ a7b67347c，2026-11-25 11:22 拉取）。
市场数据：本机 `C:\Users\USER\AppData\Roaming\ai.l0ng.openidea\plugins\market-index.json`（263 条记录）。
口径：证据一律 file:line（相对 openidea 仓根），市场插件证据取自该索引与插件仓库 README；推断项标「待验证（推断）」。

结论速览（四档）：

| 档位 | 结论 |
| --- | --- |
| 现成可做 | 方言校验、性能建议、回滚计划、CRUD/反向 SQL 生成、Mapper 静态检查、日志还原等「纯文本分析」全部可在 wasm 内实现，不依赖宿主数据库能力 |
| 变通可做 | 执行 SQL / EXPLAIN / 读结果集：用 `process:` 起自带 sidecar（Java+JDBC 或 Rust），插件侧自建协议；结果与计划用 `views` 的 `table` / `html` body 呈现 |
| 需上游 | 插件直接执行 SQL、读结果集元数据、往「数据库」工具窗口或 Query Plan 标签页里注入内容；把这些暴露成 WIT 函数 |
| 不可行 | 借 `database.connect` 让 IDE 执行后把结果交回插件；借内置 MCP Server 拿到 SQL 执行工具（宿主没有该工具）；插件自身充当 MCP 服务器并注册进内置 agent |

---

## 1 数据库子系统的实现位置

### 1.1 `java/jdbc-bridge` 是什么

`java/jdbc-bridge/src/ai/openidea/jdbc/OpenIdeaJdbc.java` 是一个**由宿主拉起的 JVM 子进程**，不是插件能碰到的服务：

- 二进制帧协议，stdin 收请求、stdout 回响应。请求 = `long id` + `int count` + `count` 个「长度前缀 UTF-8 字段」；`count == 0` 即用该 id 取消（`OpenIdeaJdbc.java:330-366`、`:222-236`）。响应 = `u32 长度` + 载荷；`0` 长度是取消确认（`:324-328`）。
- 首字段是操作码，只有 8 个：`open` / `schemas` / `tables` / `columns` / `schemaColumns` / `namespace` / `valid` / `page` / `query`（`:244-299`）。`query` 就是「执行 SQL」——`Statement.execute` + 流式回行（`:154-195`）。
- 连接在进程内独占；查询跑在一个单线程 worker 上，读线程可 `Statement.cancel()`（`:197-220`、`:330-361`）。
- 结果上限 16 MiB，越限直接抛错；二进制列转 `0x…` 十六进制串（`:13`、`:20-30`、`:182-189`）。
- 保留 `open` 的登录异常细节不给前端，避免厂商 URL 里的凭据外泄（`:312-316`）。

调用方是宿主自己的 `crates/ide-db/src/jdbc/mod.rs`：`Bridge::start` 先找 **JDK 17+**（`installed_jdks()` / `java_runtime(&jdks, 17)`，缺则报「Install a Java 17 or newer runtime to use custom JDBC drivers」，`jdbc/mod.rs:226-232`），jar 由 `ide_sdk::java::jdbc_bridge_jar()` 从宿主二进制里 include_bytes 落地到数据目录（`crates/ide-sdk/src/java.rs:1638-1646`），然后 `java -cp <bridge>:<driver jars> ai.openidea.jdbc.OpenIdeaJdbc`（`jdbc/mod.rs:236-256`）。

也就是说：`jdbc-bridge` **只服务宿主自己的「自定义 JDBC 驱动」档案**（`DbDriver::Jdbc`），而且宿主对它的定位是「自定义驱动不支持厂商特有的 DDL、执行计划或表格编辑」（`docs/zh/guide/database.mdx:34`）。插件层面完全看不到它。

### 1.2 宿主数据库客户端的真实构成

`crates/ide-db` 才是数据库功能本体（`Cargo.toml` 说明行：「data sources, connections, introspection, query execution and table editing」）：

| 驱动 | 实现 | 证据 |
| --- | --- | --- |
| SQLite | `sqlx` | `crates/ide-db/Cargo.toml:25`，`conn.rs:1010` |
| PostgreSQL | `sqlx`（`raw_sql` 简单查询协议，全部值按文本取回） | `conn.rs:1-5`、`:1033` |
| MySQL / MariaDB | `sqlx` | `conn.rs:1083` |
| SQL Server | `tiberius`（TDS） | `Cargo.toml:26`，`conn.rs:1144` |
| ClickHouse | 独立模块 | `crates/ide-db/src/clickhouse.rs` |
| Oracle | `oracledb`（**纯 Rust thin 驱动**，不需要客户端库） | `Cargo.toml:19`，`source.rs:24` |
| Snowflake | 独立模块（keypair / JSON 读结果） | `crates/ide-db/src/snowflake/` |
| 任意 JDBC | 上面的 jdbc-bridge 子进程 | `source.rs:27`、`jdbc/mod.rs` |

`DbDriver` 枚举只有这 8 个值（`crates/ide-db/src/source.rs:16-28`）。

宿主的执行面：`DbManager::execute_from(id, statement, skip, max_rows, cancel, schema)`（`crates/ide-db/src/lib.rs:1455-1470`）、`explain()`（`:1523`）、`table_page()`（`:1696`）、`apply_edits()`（`:1736`）、`completion()`（`:1386`）。这些是宿主内部 API，经 `crates/ide-host/src/ops/db.rs`（`db_execute_stream:441`、`db_explain:325`、`db_table_page:519`）暴露给渲染进程，**没有任何一条走到 WIT**。

### 1.3 EXPLAIN 的方言覆盖

`crates/ide-db/src/plan.rs:36-48` 的 `explain_statement` 只认 7 个方言：

| 驱动 | 生成语句 |
| --- | --- |
| SQLite | `EXPLAIN QUERY PLAN …` |
| PostgreSQL | `EXPLAIN (FORMAT JSON) …`（JSON 解析成节点树，`:73-88`） |
| ClickHouse | `EXPLAIN json = 1 …` |
| MySQL / 自定义 JDBC | `EXPLAIN …`（JDBC 分支解析时直接报「Custom JDBC execution plans are unsupported」，`:95`） |
| SQL Server | `SET SHOWPLAN_TEXT ON; …`（解析恒返回空，`:96`） |
| Oracle | `EXPLAIN PLAN FOR …` + 读 `plan_table`（`:52-62`、`:75`） |
| Snowflake | `EXPLAIN USING TABULAR …` |

与我方八方言（MySQL/PG/Oracle/SQLServer/DM/KingBase/H2）对照：**DM、KingBase、H2 在宿主原生路径里一个都没有**。KingBase 可蹭 PostgreSQL（协议兼容，待验证），H2 只能靠自定义 JDBC（而自定义 JDBC 没有解释计划）。这条差异直接影响第 4 节的三条出路。

### 1.4 文档口径

- `docs/zh/guide/database.mdx`：8 类驱动 + 兼容产品；查询控制台有「执行」「解释计划」（Query Plan 标签页）、取消、500 条历史、行数上限 100–50 000；结果在 **服务** 工具窗口的 **数据库** 标签页（`:115-133`）。
- `docs/zh/plugins/guides/database.mdx`：插件侧只有「建/删自己的数据源 + 写凭据 + 请求连接」。

---

## 2 插件拿不到执行能力，但有五条绕行通路

### 2.1 `database` 接口的实际形状（已核实，复核用）

`database.wit` 全文只有 5 个函数：`upsert-source` / `remove-source` / `sources` / `set-credential` / `connect`（`sdk/rust/openidea-plugin/wit/database.wit:5-33`）。`connect` 的语义是「在你这个窗口里走一遍 IDE 常规的连接/内省流程」（同文件 `:30-33`；`plugins/README.md:1226-1228` 补充「uses the usual Database connection, password prompts and introspection」）。**没有任何读结果、执行语句、读查询历史的函数**；凭据写进去读不回来（`plugins/README.md:1195`）。

驱动白名单在宿主侧硬编码校验，`"jdbc"`、`"snowflake"` 都会被拒：`crates/ide-host/src/plugins/database.rs:35-43`。

### 2.2 五条候选通路逐条判定

#### ① `process:` 起自带程序（主路）

`io.wit` 提供 `run` / `run-command` / `start` / `run-captured` / `start-stream`（`sdk/rust/openidea-plugin/wit/io.wit:196-215`），常驻子进程上限 8 个（`:173-175`），stdout/stderr 单块 1 MiB（`crates/ide-plugin/src/wit_host.rs:300`），stdin 单次写 1 MiB（`:302`），`start-stream` 可设到 128 MiB 总量。

硬约束两条，必须写进方案：

1. 程序必须被用户批准并**钉内容哈希**，「文件被替换需重新批准」（`plugins/README.md:377-380`；宿主测试断言 `crates/ide-host/src/plugins/api.rs:4611` 的 `changed since it was approved`）。把 jar/exe 打进插件包不能自动运行——`plugins/official/container-runner/README.md:3-5` 明确要求「Put this directory on PATH **before installing/granting** the plugin」，或由用户在 设置 › 插件 里手动指向。
2. `child.write(text: string)` 是**文本**通道，`poll` 返回也是 `string`（`io.wit:168-190`）。我方 `OpenIdeaJdbc` 的二进制帧直接搬过来会被 UTF-8 解码破坏。要么改成 JSON-Lines（值本身已是字符串/十六进制，无损），要么改用 ipc/HTTP。

市场实证：`izzyjere.jpa-powertools` 就是用这条路——`process:java` 跑包内 `resources/JdbcBridge.java`，**Java 11+**，只读 `DatabaseMetaData`，「never runs SQL text」，驱动 jar 首连时从 Maven Central 拉并校验 SHA-1（该插件 README「How it connects, given OpenIDEA's plugin sandbox」段与 Permissions 表）。

#### ② `ipc:<path>` 连本地套接字/命名管道

`ipc.connect(name)` 只接受**绝对 Unix socket 或 Windows 命名管道**，明确「never TCP」（`io.wit:221-233`），单插件最多 8 条连接，读/写各 1 MiB 一帧（`io.wit:222-230`；`plugins/README.md:862`）。
优点：**字节流**，我方二进制协议可以原样复用。缺点：Windows 命名管道 Java 侧没有标准 API（需 JNA/JNI 或第三方库），sidecar 要额外实现。**待验证（推断）**：用 Rust sidecar 时 `tokio::net::windows::named_pipe` 可解，Java 侧成本明显更高。

#### ③ `net:` 访问 sidecar 的 loopback HTTP

`net.fetch` 允许 `http://` 到 loopback（「except loopback, which uses `net:<host>`」，`io.wit:58-71`），WebSocket 允许 loopback `ws://`（`:77`），能力名按 host 计（`net:127.0.0.1`），端口不参与匹配。响应体上限 8 MiB，默认超时 30 s（`:65-71`）。`net:*` 是 medium 风险，安装时一次性授权，无每次询问（除 `net:ask`）。

这条路最省事：sidecar 只说 JSON over HTTP，插件侧 `fetch`；不需要处理 stdin 文本/字节差异，跨平台一致，还能被外部工具复用。代价是插件必须自己管端口（随机端口 + 首行回显/写进 plugin-dir 再读）。

#### ④ `lsp:` 问语言服务器

`language-servers.request(project, server, method, params)`（`host.wit` 的 `language-servers` 段）只放行**服务器自有方法**：`textDocument*`、`workspace*`、`window*`、`$/…` 等 LSP 命名空间全部 `invalid`。所以「借 sqls 做补全/执行」这条路被堵死，且 sqls 本来也没有执行能力（`docs/zh/languages/sql.mdx:12`：IDE 不把数据源传给 sqls）。

反过来，插件可以在自己的 `languages/*.toml` 里声明一个**自有名字**的语言服务器（如 `sqlv/rpc`），用自定义方法做 RPC——但这要求程序在 PATH 上（`crates/ide-lsp/src/launch.rs:64-110` 的 PATH 解析），且调用仍受 5 s 预算（见 4.2）。收益不抵复杂度，不推荐。

#### ⑤ `run` 起运行配置

`run.start(config, args, debug)` 必须「来自窗口 command/event」（`sdk/rust/openidea-plugin/wit/run.wit:4-10`；宿主 `wit_host.rs:2626-2642` 里 `require_window()`）。MCP 工具调用不是命令/事件，**拿不到窗口**，所以 MCP 场景不可用；只能用于 UI 命令。且运行配置只解决「起进程」，不解决「拿结构化结果」。

#### ⑥ 借内置 MCP Server 的 SQL 工具

不存在。内置 MCP 工具清单（`docs/zh/guide/mcp.mdx` 的「工具」表）只有文件/搜索/编辑器/代码/问题/运行构建/命令/Git/HTTP 客户端十类，没有数据库。`crates/ide-mcp/src/` 里也没有 db 相关工具。

### 2.3 A. 路径排序表

| # | 路径 | 前置条件 | 支持数据库 | 结果集元数据 | EXPLAIN | 工作量 | 风险 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P1 | `process:java` + 自带 JDBC sidecar（文本 JSON-Lines 协议） | `process:java` 高授权；用户机器有 JDK（我方 jar 目标字节码 52，Java 8+ 足够；jpa-powertools 走了 Java 11+ 单文件执行，说明 ≥11 是市场惯例）；jar 放 plugin-dir，路径靠 `fs.plugin_dir()` 作为参数传（`plugins/README.md:418` 确认 plugin-dir 路径可作为进程参数） | 任意 JDBC：MySQL/MariaDB/PG/Oracle/SQLServer/DM/KingBase/H2…（驱动 jar 需随包或首连下载） | 有（`ResultSetMetaData` / `DatabaseMetaData`） | 有（自己按方言拼） | 中：协议要重写成文本/JSON，其余逻辑可大量复用 | 中：插件每次升级 jar 变→哈希变→用户要重新批准；JDK 缺失要走降级提示 |
| P2 | 同 P1，sidecar 开 127.0.0.1 HTTP | 同上 + `net:127.0.0.1`（medium） | 同 P1 | 同 P1 | 同 P1 | 中低：省掉 stdin/stdout 文本化，改 JSON over HTTP | 低；需自管端口、单次响应 8 MiB 分页 |
| P3 | `ipc:` 连 sidecar 的命名管道/Unix socket（二进制原样协议） | `ipc:<path>` 高授权 | 同 P1 | 同 P1 | 同 P1 | 中高：Windows 命名管道在 Java/Rust 两侧都要额外实现 | 中；协议零改动，但平台差异大 |
| P4 | `database` 建源 + `connect`，让 IDE 自己连、自己执行 | `database.sources`（medium）+ 受信项目 | 白名单 6 类（sqlite/postgres/mysql/sqlServer/clickhouse/oracle；PG 驱动可蹭 KingBase） | **否**（插件拿不到） | **否** | 极低 | 低；但等于「把用户踢回 IDE 界面」，无法支撑 MCP 与预览工作台 |
| P5 | `lsp:<server>` 自定义语言服务器做 RPC | `process:<program>` + `lsp:<server>` + 程序在 PATH | 取决于自己的服务器 | 取决于自己 | 取决于自己 | 高 | 高；受 5 s 预算，收益不抵复杂度 |
| P6 | `run.start` 拉起运行配置再通信 | 窗口命令/事件 + `process:` | 同 P1 | 同 P1 | 同 P1 | 中 | MCP 不可用（无窗口）；只在 UI 侧成立 |

**能否复用我方现有 jdbc 侧代码？** 能，但不是零改：

- 我方（IntelliJ 版）走的是 IDE 的 DBX/JDBC 抽象，源头是 `java.sql`。把它抽成独立 jar（`java.sql` + 驱动 jar）在 OpenIDEA 侧完全成立——`OpenIdeaJdbc` 就是这个形态的现成样板（373 行，零第三方依赖）。
- **Java 8 兼容性不是问题**：字节码 52 在 JDK 17 上照跑；反倒是宿主自己的 bridge 要求 JDK 17（`jdbc/mod.rs:226-232`）。真正要改的是**协议层**：宿主的二进制帧（`long id + int count + 长度前缀字段`）在 `process` 的文本通道上会被破坏 → 必须换成 JSON-Lines/base64，或走 P2/P3。
- 需要自己解决 JDK 发现：宿主有 `installed_jdks()` 但不给插件；插件只能靠 `process:java`（PATH 解析）+ 失败时提示用户去 设置 › 插件 指定可执行文件。

---

## 3 SQL 语言支持深度

| 能力 | 谁实现 | 插件可见性 |
| --- | --- | --- |
| `.sql`/`.ddl`/`.dml`/`.psql`/`.pgsql`/`.mysql` 语言定义 | `languages/sql.toml`（注释符、缩进、`[[language-servers]] name="sqls"`） | 不可读；插件若要接管需自带 `contributes.languages` |
| 语法高亮 | 宿主 | 不可见；插件可声明 `contributes.languages` 覆盖或 `customEditors` 整文件接管 |
| 词法 / 方言关键字 / 格式化 / 语句切分 / 参数 | **TypeScript，跑在渲染进程**：`src/features/database/sql/lexer.ts`（194）、`dialect.ts`（659）、`format.ts`（316）、`params.ts`（66）、`readOnly.ts`（24）、`analyze.ts`（565）、`complete.ts`（618）、`resolve.ts`（338），共约 4.5k 行 | 不可见、不可调用（wasm 里没有这套代码的入口） |
| 语句拆分（含 PG dollar-quote、Oracle PL/SQL） | `crates/ide-db/src/split.rs` | 不可见 |
| 绑定数据源后执行 / 解释计划 / 补全 / 快速文档 | 宿主 | 不可见；只有「不绑定数据源 → 只能补关键字」的既有限制（`docs/zh/languages/sql.mdx:48-51`） |
| 树立 SQL 语法树 | 宿主没有 parser；`Cargo.lock` 里**没有** `sqlparser` / `sqlformat` / `tree-sitter-sql` | —— |

结论：宿主的 SQL 智能（词法/补全/格式化/只读判定）既不是 Rust 也不是可复用的库，**它是一份前端 TS**，插件拿不到也搬不走。我方要在 wasm 里做 SQL 分析，只能自己写或引入 crate（见第 5 节 B）。

`.sql` 文件能否接管：能。`contributes.customEditors` 支持 `patterns`（文件名通配）+ `web` 页面（`plugins/README.md:223` 贡献表、`:2123-2145` Web views 段），页面在 `openidea-plugin://` 沙箱 iframe 里，无网络、只能加载包内文件。代价是接管后宿主原本的「绑定数据源/执行/解释计划」工具栏在自定义编辑器里不复存在。

---

## 4 MCP 能力边界

### 4.1 声明与调用契约

`contributes.mcpTools` 字段：`name` / `description` / `inputSchema` / `readOnly` / `destructive`，`readOnly` 与 `destructive` **都是必填**（宿主 `Declaration` 结构体两个字段均非 `Option`，`crates/ide-host/src/plugins/mcp.rs:12-18`；文档同口径 `docs/zh/plugins/guides/mcp.mdx:43-44`）。

校验（`crates/ide-host/src/plugins/mcp.rs:26-52`）：

| 项 | 规则 |
| --- | --- |
| name | `[a-z0-9_]{1,40}`；注册名 = `插件id 非[a-z0-9_]→_``__``工具名`，总长 ≤ 64 字节 |
| description | 1–4096 字节；列表里会加「(plugin: 插件名 [id])」后缀（`mcp.rs:82-87`） |
| inputSchema | 包内自包含 JSON Schema 文件，必须 `type: object`，≤ 256 KiB；`$ref` 只能本地片段 |
| 需要 wasm | `pkg.manifest.wasm.is_none()` → 「mcpTools requires wasm」（`mcp.rs:27-29`） |
| 重名 | 规范化后撞名则两个都撤下（`mcp.rs:97-100`） |
| 参数 | 调用时按 schema 校验，JSON ≤ 1 MiB（`mcp.rs:113-121`） |
| 返回 | 纯文本，≤ 4 MiB（`crates/ide-plugin/src/runtime.rs:2805-2807`）；无 `outputSchema`（`mcp.rs:88`），结构化只能「JSON 编码成文本」 |

一个插件能声明多少工具：**没有找到数量上限**。`ui.items(POINT)` 只做过滤（`crates/ide-extension/src/ui.rs:117-124`），`manifest.rs` 里只有 id 长度约束，没有条数约束。所以理论上 11 个工具不受限。

### 4.2 预算：5 秒是硬线，`tasks.begin` 抬到 60 秒

`runtime.mcp_call` 用 `self.limits.timeout`（`runtime.rs:2793-2800`），默认值 `timeout = 5s`、`background = 60s`、`fuel = 5_000_000_000`、`memory = 256 MiB`（`crates/ide-plugin/src/types.rs:482-495`）。在调用里 `tasks.begin` 之后立即改为 background 预算，且每次 `task.report` 继续续期（`crates/ide-plugin/src/wit_host.rs:1630-1656`）。

含义：**任何要连库的 MCP 工具，必须在开头 `tasks.begin(标题, 可取消)` 并周期性 `report`**，否则 5 秒必挂。这条同样适用于 UI 侧（views 的 `render` 也用同一个 `limits.timeout`，`runtime.rs:1717-1726`）。

### 4.3 权限与语义

- MCP 调用在插件自己的 actor 里跑，用插件自己的授权，客户端不给额外权限；`readOnly=false` 的工具在受限模式下隐藏（`docs/zh/plugins/guides/mcp.mdx:94-96`）。
- `destructive=true` 每次调用都要项目窗口确认，拒绝/取消/没有窗口都 fail-closed（同文档 `:98`）。
- 「仅限命令和事件」的 API 在 MCP 上下文里仍是 `invalid`（对话框、`run.start`、开终端等）。
- 内置 MCP Server 目前只服务**本地项目**；远程项目调用返回「not supported for remote projects yet」（`guides/mcp.mdx:96-97`）。

### 4.4 插件能否自己当 MCP 服务器

三条都不成立：

1. 插件是 wasm 组件，不能监听 stdio；
2. 没有「把外部 MCP 服务器注册进内置 agent / 外部客户端」的贡献点（全仓 `mcpServers` 只出现在 `crates/ide-agent` 的 ACP/Codex 协议翻译里，且由宿主自己装配，`crates/ide-agent/src/native/codex/translate.rs:968-1001`）；
3. `mcp.stdio-command()` 只反向：让插件学到**IDE 自己**的 stdio 桥 argv（`sdk/rust/openidea-plugin/wit/mcp.wit:4-7`），给插件启动的外部 agent 用，不是给插件当服务器。

变通：插件可用 `process:` 起一个真正的 MCP 服务器（stdio 或 loopback SSE），再靠 `project.write` 往项目里写 `.mcp.json` / `~/.codex/config.toml`——但这属于改用户文件，且不在任何贡献点契约内，不建议产品化（**变通可做，但不推荐**）。

---

## 5 结果集与 EXPLAIN 的展示面

| `views` 的 `body` 变体 | `message` / `tree` / `list` / `form` / `table` / `html`（`sdk/rust/openidea-plugin/wit/views.wit:68-78`） |

| 需求 | 可用形态 | 边界 |
| --- | --- | --- |
| 结果表格 | `table { columns, total-rows, first-row, rows, editable }`（`:60-66`） | 值全部是 `list<string>`，无类型/对齐信息；宿主按 `viewport` 拉行，单次渲染最多 2000 行（`runtime.rs:2051`）；`editable=true` + `cell`/`insert-row`/`delete-rows` 事件可做单元格编辑（`:96-116`） |
| 多标签结果集 | `views.refresh` + 让插件自己切 `body`；或一个 view 对应一个结果集 | 没有「编辑器标签页」语义（那是 `open-content-tab`，只给文本/diff） |
| 树式 EXPLAIN | `tree` + `tree-item{parent, label, detail, expandable}`（`:22-33`）；宿主按 `expand` 事件懒加载子节点（`:108`） | 天然贴合我方 `PlanNode{parent, operation, params, properties}`（`crates/ide-db/src/plan.rs:22-33`）的形状 |
| 图形式 / 复杂布局 EXPLAIN | `html(string)`：沙箱 iframe，无网络、无 IDE API，脚本只能 `openidea.postMessage` / `onMessage`（`views.wit:76-78`）；或 `contributes.views web = "web/index.html"`，页面带 `document.get()/saveFile/openFile` 等（`plugins/README.md:2141-2156`） | `post-message` 单条 ≤ 8 MiB（`wit_host.rs:2404`）；页面必须完全离线（内联 SVG/CSS/JS） |
| 工具栏与状态行 | `toolbar: [{id,label,icon,enabled}]` + `status`（`:83-88`） | 图标只能选内置名（`refresh`/`add`/`run`…），不能任意 SVG |
| 放到哪里 | `contributes.views.location` 可选 `left`/`right`/`bottom`/`services` | `services` 正好是「服务」窗口——宿主自己的数据库结果就在那里（`docs/zh/guide/database.mdx:133`），但**只是同一个容器，不能往宿主的 Database/Query Plan 标签页里写** |

结论：**结果表格与 EXPLAIN 展示，`views` 完全够用，评「现成可做」**；要「像 DataGrip 那样把结果塞进 IDE 自己的结果标签页、共享宿主的编辑器/导出/图表能力」，则「需上游」。

---

## 6 B. 方言校验与性能建议能否纯 wasm 内做

能，且必须自己做。理由：宿主那套 SQL 智能是前端 TS（第 3 节），wasm 拿不到；同时宿主没有 Rust 版 SQL 解析器。

我方现状：19 个 Kotlin 文件依赖 `com.github.jsqlparser:jsqlparser:5.3`（`build.gradle.kts:141`），纯文本分析集中在 `sql/perf/*`、`sql/rollback/*`、`sql/schema/*`、`execute/SqlAstParser.kt`、`UnboundedWriteDetector`、`DmlImpactEstimator`、`mcp/McpSelectGuard.kt`，合计约 3.2k 行（实测行数：PerfRules 4 批 1211 行、RollbackSqlGenerator 635、UnboundedWriteDetector 318、McpSelectGuard 225、SchemaValidator 244 等）。

### Rust 生态替换可行性

| 项 | 事实 | 证据 |
| --- | --- | --- |
| crate | `sqlparser` 0.64.0（Apache DataFusion 社区维护），近 30 天下载约 2000 万 | `https://crates.io/api/v1/crates/sqlparser` |
| 许可证 | **Apache-2.0** | GitHub repo API：`license.spdx_id = apache-2.0` |
| JSqlParser 许可证 | **同样 Apache-2.0** | GitHub repo API：`license.spdx_id = apache-2.0` |
| 我方仓库许可证 | Apache-2.0 | 仓根 `LICENSE` 首行 |
| 方言模块 | ansi / bigquery / clickhouse / databricks / duckdb / generic / hive / mssql / mysql / oracle / postgresql / redshift / snowflake / spark / sqlite / teradata | `src/dialect/` 目录清单 |

- **许可差异：零**（同为 Apache-2.0，无 LGPL/闭源风险）。
- **方言映射**：MySQL→`MySqlDialect`、PG→`PostgreSqlDialect`、Oracle→`OracleDialect`、SQLServer→`MsSqlDialect`、H2→`GenericDialect` 或 `PostgreSqlDialect`（待验证）、**DM/KingBase 没有专用方言**：KingBase 走 PostgreSQL、DM 走 Oracle 或 Generic（待验证（推断）：DM8 的 `EXPLAIN`/函数/类型偏 Oracle；我方的 DM 校验规则若依赖方言特有语法，需要补一层自有补丁）。
- **AST 语义差异**：JSqlParser 的 `ExpressionVisitorAdapter` 与 sqlparser-rs 的 `Visitor`/`VisitMut` 模型不同，19 个文件的遍历要重写；但规则本身（SELECT *、缺索引、前导通配 LIKE、大 IN、`1=1`、缺 WHERE、LIMIT 页码、函数包裹列…）与 AST 库无关。
- **无法覆盖的部分**：我方有些检查基于「SQL 文本 + 元数据」，例如 `sqlv_schema_context` 要真实 schema（必须连库）；`sqlv_check_sql` 的类型校验同理（我方描述里写「Static write-risk and type check」）。纯语法部分可 wasm 内完成，类型/元数据部分需要第 2 节的连接通路。

结论：**B 项「纯文本分析」= 现成可做**（wasm 内，Apache-2.0 无许可差），代价是一次 AST 层迁移 + 规则重写（约 3k 行 Kotlin → Rust，待验证（推断）：2–3 人周）；带元数据的那部分另算。

---

## 7 C. 11 个 MCP 工具映射表

统一约定：注册名 `song_mpsv__sqlv_xxx`（示例插件 id `song.mpsv`；规范化后 25 字节，远低于 64 字节上限）。全部工具都是**只读**（`readOnly = true`、`destructive = false`），因此在受限模式可见、无每次确认弹窗。

| 工具 | 参数个数 | schema 规模（估） | 需要项目上下文 | 需要执行 SQL / 连库 | 阻塞风险与对策 |
| --- | --- | --- | --- | --- | --- |
| `sqlv_render_sql` | 4（filePath/line/xmlSnippet/sourceType） | 小（<2 KB） | 是（`ctx.project()` + `read_file`/`search_text`） | 否 | 低；是 MyBatis XML/Java 文本扫描，无 IO 阻塞。须 `tasks.begin` 以防大文件 |
| `sqlv_render_logs` | 8（logText 等 4 前缀 + mixedPrefixMatching + detail） | 中（2–3 KB） | 否（纯文本入参） | 否 | 低；入参 JSON ≤1 MiB，长日志要分档（我方已有 `detail` 两档输出，正好对齐 4 MiB 返回上限） |
| `sqlv_check_sql` | 1（sql） | 极小（<1 KB） | 否 | **否**（要类型校验才需元数据） | 低；纯分析 + `tasks.begin` |
| `sqlv_perf_advice` | 1（sql） | 极小 | 否 | 否 | 低 |
| `sqlv_rollback_plan` | 2（sql, primaryKeyColumns） | 小 | 否 | 否 | 低；纯文本推导 |
| `sqlv_crud_sql` | 2（filePath, className） | 小 | 是（找实体类） | 否 | 低；依赖 Java 源码文本/符号搜索 |
| `sqlv_reverse_sql` | 3（sql, maps, mapperVariable） | 小 | 否 | 否 | 低 |
| `sqlv_mapper_check` | 3（xmlSnippet, filePath, simulate） | 小 | 可选（filePath 时） | `simulate=true` 需要「渲染」而非连库 | 低 |
| `sqlv_schema_context` | 3（tables, dataSource, maxTables） | 小 | 是 | **是**（真实 schema） | 中高；必须先 `tasks.begin`，且要拿到表/列 |
| `sqlv_explain` | 4（selectSql, dataSource, dialect, maxRows） | 小 | 是 | **是**（连库执行 EXPLAIN） | 高；5 s 默认预算必然不够，必须 `tasks.begin` + `report`；失败要 fail-closed |
| `sqlv_history_search` | 8（keyword…detail） | 中 | 可选（sourceFile 是项目路径时） | 否（读自己的历史库） | 低；历史要自建存储（宿主 `DbManager::history` 不给插件；`storage` 上限 64 MiB，`plugins/README.md:360`） |

结构性差异（必须改的）：

1. **`sqlv_explain` / `sqlv_schema_context` 是本组里唯一「真连库」的两个**。在 OpenIDEA 上它们必须落到第 2 节 P1/P2 的 sidecar，而 sidecar 又要求 `process:` 授权 + JDK/驱动 —— 这决定了产品必须给出「未配置连接时降级为纯分析」的路径。
2. 我方 Kotlin 签名里的 `McpStringArg` / `McpIntArg` / 可空类型（`SqlVisualizerSchemaContextTool.kt:51-55`）在 JSON Schema 里要摊平成普通 object，不能保留自定义类型。
3. 文本输出协议保持一致（含 `-- SQL #N` 段头、`detail` 两档、4 MiB 上限），可让 MCP 客户端行为与我方 IntelliJ 版一致。

---

## 8 D. 与市场现有插件的 DB/SQL 面比较

| 插件 | 版本 | 权限（DB 相关） | DB/SQL 能力 | 判读 |
| --- | --- | --- | --- | --- |
| `izzyjere.jpa-powertools` | 0.2.0 | `process:java`、`net:repo1.maven.org`、`project.write`… | 从 JDBC 读**元数据**（表/列/键/外键/注释）、生成 JPA 实体与 Repository、ER 图、DDL、JPQL 校验/补全；**不执行 SQL、不出 EXplain** | 与本项目最接近的对照物，其存在证明「Java sidecar + JDBC」在 OpenIDEA 上可行 |
| `jiusi.mybatis-log` | 0.2.1 | `editor.read`、`storage` | 日志还原成 SQL、批量、参数回填、高亮、慢查询/影响行数、静态风险提示；只读编辑器文本，不联网不执行 | 与本项目 `render_logs` 高度重叠 |
| `qtvbwfn.mybatis-duo` | 0.3.6 | `project.read`、`project.write`、`editor.read`、`clipboard` | Mapper 双向导航、Statement 生成与补全、`@Param` 快速修复、SQL 日志转换 | 导航面已饱和，SQL 质量面（校验/执行/计划）为空 |
| `openidea.mybatis`（官方） | 0.4.2 | `project.read` | 导航 + `#{}` 补全 | 官方只做导航 |
| `aritti.ddev` / `aritti.symfony` | 0.4.0 / 0.5.0 | `database.sources` | 只是**建数据源**（把容器里的 DB 塞进数据库树），原生凭据 | 证明 P4 通路（建源 + 交还 IDE）是市场惯例 |
| `aritti.flyway` / `aritti.liquibase` | 0.5.0 | `process:flyway` / `process:liquibase` | 迁移文件校验、调用 CLI | 说明「起外部 CLI」也是常见做法 |

**空档（我方可以独占的）**：

1. MyBatis-Plus Wrapper / XML / 注解 / JPA / JdbcTemplate 的**参数填实渲染**——市场没有任何插件做（官方 mybatis 只补 `#{}`，duo 只生成 Statement）。
2. **SQL 方言校验 + 性能建议 + 写操作安全预检 + 回滚计划**——零竞品；jpa-powertools 有 JPQL 校验，但那是 JPQL 不是 SQL，也不覆盖性能/回滚。
3. **EXPLAIN 可视化**——宿主自己的 Query Plan 标签页是「IDE 功能」，没有插件做可视化；市场插件连 EXPLAIN 都不出。
4. **SQL 执行历史检索**——宿主有每数据源 500 条历史（`docs/zh/guide/database.mdx:118-121`），但没给插件的读接口，也没跨项目的「按关键字/正则检索」。我方 `history_search` 在这个形态下是独有功能（代价：要自建存储，见第 7 节）。
5. **DM / KingBase / H2 方言**——宿主原生驱动里没有（第 1.2、1.3 节），市场插件靠「任意 JDBC」也只是罗列，没有方言特化的校验与 EXPLAIN。这一层是我方差异化最硬的地方，也是必须自建 sidecar 的根因。

反面提醒：jpa-powertools 把「连库」做在 Java sidecar 里，但**刻意只读元数据、不执行 SQL**。它的取舍值得抄：**把「执行」与「分析」解耦**，分析走 wasm、执行走 sidecar 且默认关闭。

---

## 9 未解决 / 需上游确认

1. **插件是否能拿到结果集**：目前 WIT 里没有任何读结果/读执行历史/读 Query Plan 的函数。若要让「预览工作台里可执行 SQL」在 OpenIDEA 上成为一等公民（而不是自带 sidecar），需要新增 `database.execute` / `database.history` / `database.plan` 一类函数，并加 `database.query` 这类高风险能力。上游 issue 搜索未发现已有请求（关键词 `sql` / `database` / `execute` / `mcpTools` 均无相关条目），需我方自己提。
2. **`interface database` 白名单缺通用 JDBC**：`database.rs:35-43` 只认 6 个 driver 字符串，`jdbc` 会被拒。DM 这类只能靠通用 JDBC 的库，若想复用宿主的连接/凭据仓，必须上游放开 `jdbc`（这会带来「插件可指定任意驱动类与 URL」的安全面，需上游评估）。待上游。
3. **`process:` 的哈希钉住与插件升级冲突**：每版插件带的 jar 内容都变，每次升级后用户要去设置页重新批准（`plugins/README.md:377-380`）。这对「一个 jar 随版本滚动」的产品形态很不友好，是否可给 `process:` 加「随插件包版本自动重签」的通道，需上游确认。
4. **Windows 命名管道与 Java**：`ipc:` 只谈 Unix socket / 命名管道（`io.wit:221-233`），Java 侧没有标准 API。若要在 Windows 上跑 P3，需评估 JNA 依赖或改走 P2。待验证（推断）。
5. **H2 / DM 的 EXPLAIN 语法**：宿主 `plan.rs` 没有 H2/DM 分支，我方 DM/H2 的 EXPLAIN 生成规则在 OpenIDEA 侧必须自证（H2 的 `EXPLAIN` 返回单行文本；DM8 的 `EXPLAIN` 行为与 `EXPLAIN PLAN FOR`+`PLAN_TABLE` 的差异），需要真机验证。待验证。
6. **MCP 工具的 `outputSchema`**：宿主固定 `output_schema: None`（`crates/ide-host/src/plugins/mcp.rs:91`），我方若想给客户端结构化结果（而不是 JSON-in-text），需上游加字段。
7. **远程项目的 MCP**：内置 server 只服务本地项目（`docs/zh/plugins/guides/mcp.mdx:96-97`），而 `database` 接口本身是支持远程的（`database.wit:4-5`）。我方若主推「远程项目 + 连库」，两个能力在远程场景下不同源，需上游确认路线。
8. **5 秒 → 60 秒的续期是否对 MCP 调用可靠**：`tasks.begin` + `report` 续期在 `wit_host.rs:1636-1654` 是明确的，但「MCP 调用是否允许开 task」在文档里没有正面写出（只说「仅限命令/事件的 API 依然 invalid」，task 不在其列）。**待验证**：用 `ide-plugin/tests/fixtures/data-tools` 那类夹具做一次实测最省事。