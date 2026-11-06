# Architecture — mybatis-plus-sql-visualizer (run-1, standard profile)

## Product, principals, protected resources
IntelliJ IDEA plugin (Kotlin/Java, plugin id `cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer`) that captures MyBatis/ Hibernate/JDBC SQL from user applications, restores executable SQL with parameters back-filled, renders/explains/executes it, and converts SQL back to Wrapper code. Single-user desktop trust model: the operator is the developer; lower-trust principals are (a) untrusted console/log text produced by any process the IDE hosts, (b) local processes on the loopback agent channel, (c) AI agents connected through the platform MCP server, (d) mapper XML / project files, (e) clipboard and imported settings/history files, (f) the remote update/feedback servers' responses. Protected resources: user JVMs (agent attach), project datasources and their credentials (JDBC execution), files on disk (autosave, export, dynamic install into the IDE plugins dir), captured SQL history containing literal parameter values, and the IDE process itself (dynamic plugin install, reflection into platform internals).

## Comparable baseline
MyBatis log-restore plugins (MybatisCodeHelperPro, MyBatis Log Free, Mybatis Log Ultra — named in repo docs) parse the same "Preparing/Parameters" formats but generally stay read-only renderers. This plugin goes further: it executes SQL, attaches a bytecode agent to user JVMs, and self-installs update ZIPs — accepting more trust-sensitive risk than the comparable baseline. Use this to calibrate severity, not to dismiss findings.

## Stack and deployment
Kotlin 2.3 / Java (agent targets Java 8), IntelliJ Platform 2024.3 (IPGP 2.18.1), Gradle 9.7. Runtime deps: JSqlParser 5.3; relocated ASM in agent. Deployment: JetBrains Marketplace ZIP; Gitee release ZIPs + in-plugin self-update manifest (`update/UpdateSource.kt`); no shipped server. Offline builds are cache-dependent (platform distro, JDK 17); sibling demo repo needed for full tests.

## Entry surfaces and source-to-sink paths
1. **Console capture** (core): every run-console line via `consoleFilterProvider` EP (`plugin.xml:141`) or reflection bridge (`SqlLogConsoleBridge.kt:304-430`) → 4 parsers (`MyBatisLogParser`, `HibernateLogParser`, `JdbcTemplateLogParser`, `ShardingSphereLogParser`, length cap 20k) → panel, autosave files, jump-to-source markers, re-execution.
2. **Loopback agent channel**: IDE `ServerSocket` on loopback (`AgentManager.kt:799,893-914`) ↔ agent `SenderLoop` (`SenderLoop.java:148-157`); 4-byte-length frames ≤1MB; **no peer authentication either direction**; HELLO pid self-reported.
3. **Agent attach/premain**: `com.sun.tools.attach` into user JVMs (`AgentManager.kt:798-838`), target vetting `AgentAttachTarget.kt:40-154`, consent dialog removed by design (`PremainInjection.kt:14`), master switch default-on (`PluginSettings.kt:637`).
4. **MCP tools**: 11 `sqlv_*` tools (`McpToolsetDynamicRegistrar.kt:63-130`, runtime-generated shell classes); file reads confined by `McpFilePathResolver.kt:41-71`; DB exec via `McpSelectGuard` single-SELECT guard → `McpDbExecutor.kt:206-232`; history search exposes captured SQL + parameter values.
5. **Mapper XML**: all parsing funnels through hardened `xml/SecureXml.kt` (XXE features off, expansion pre-scan, residual boundary documented `:44-51`); custom OGNL subset evaluator `xml/Ognl.kt:124-165`.
6. **SQL execution (UI)**: `SqlExecutor` → `DataSourceSelector` (any IDE datasource) with dialect gate confirm, DML confirms, `UnboundedWriteDetector`, schema/timeout gates.
7. **Self-update**: Gitee raw manifest (URL overridable via system property) → download → `UpdateZipGuard` bounds → dynamic install / restart script; **no artifact signature**.
8. **History autosave**: `SqlLogAutoSaver.kt` writes files under a user-configurable name pattern honoring separators (`:454-550`); `SqlLogFileReader` re-imports them into the execute path.
9. **Settings/config**: `SettingsIO.importFromJson` merges shared settings that later flow into rendered SQL fragments (tenant column, logic-delete values, table-name rules).
10. **Feedback/HTTP**: POST to fixed `sql-visualizer.jvtools.cn` endpoints, bounded 2MB reads, server message shown to user.
11. **Swing HTML labels**: per-dialog local `escapeHtml` variants of inconsistent completeness (`CommunityDialog.kt:137`, `ExplainPlanResultView.kt:102,214`).
12. **Supply chain**: GitHub Actions workflows (inputs passed via env), buildSrc convention plugins, dev-support/release scripts, embedded agent jar.

## Trust boundaries and strongest source-visible controls
| Boundary | Strongest source-visible control |
|---|---|
| Console text → executable SQL / navigation | length caps + replay guard (`ConsoleReplayGuard`) |
| Local process ↔ IDE agent channel | loopback-only bind + 1MB frame cap (no auth) |
| Plugin → user JVMs | `AgentAttachTarget` deny-list + settings master switch |
| AI agent → files/DB/history | `McpFilePathResolver` root confinement; `McpSelectGuard` fail-closed SELECT-only; tool gates |
| Mapper XML → SQL | `SecureXml` feature-off + expansion pre-scan |
| Untrusted SQL → user DB (UI) | confirm dialogs + write detector (client-side only) |
| Update server → IDE code | HTTPS + zip bounds + plugin-id match (no signature) |
| Settings file → SQL fragments | whitelist merge, version gate |

## Repository-relative starting paths
`src/main/kotlin/cn/song/mybatisplus/visualizer/{log,log/agent,mcp,xml,execute,update,config,history,feedback,ui,actions,share}`, `src/main/java/cn/song/mybatisplus/visualizer`, `sub-projects/sql-visualizer-agent/src/main/java`, `src/main/resources/META-INF/plugin.xml`, `.github/workflows`, `buildSrc/src/main/kotlin`, `dev-support`, `update/update.json`.

## Prior coverage
No prior runs exist for this repo; this is the first audit pass. No prior confirmed exclusions.

## Companion selection summary
Selected because recon found the matching trust-sensitive boundary: **AI-AND-LLM.md** (MCP tool server, prompt-injected agents driving tools), **DESKTOP-MOBILE-AND-LOCAL-IPC.md** (unauthenticated loopback daemon/IPC), **PROTOCOLS-RPC-AND-MESSAGING.md** (custom length-prefixed TCP protocol between IDE and agent), **SUPPLY-CHAIN-AND-RELEASE.md** (self-update ZIP install, CI, release pipeline), **RESOURCE-EXHAUSTION-AND-AVAILABILITY.md** (zip bombs, regex on untrusted text, unbounded capture/history growth). Excluded: MEMORY-SAFETY-AND-BINARY (pure JVM), CLIENT-SIDE (no browser SPA; JCEF loads only a static bundled page; Swing HTML handled by Injection), WEB-PROTOCOL-AND-AUTH (no HTTP server, no sessions/auth protocols; outbound HTTP is fixed-URL with bounded reads, covered by ordinary classes), DATA-ISOLATION-AND-LIFECYCLE (single-user local model; history export/import covered by ordinary Feature-abuse and file-handling classes), CLOUD-AND-DEPLOYMENT (no IaC/k8s; CI covered by SUPPLY-CHAIN).

## Execution-safety note for this run
Recon (Agent 1d) established that this Windows host cannot enforce the required OS sandbox controls (empty allowlisted environment, no external network, read-only target, scratch-only writes, resource limits) or the scratch-promotion procedure. Therefore this run is **source-review only**: no target-controlled execution. Any candidate whose verdict requires a bounded local observed result will be recorded `needs_validation` with that exact blocker and a concrete local validation plan (existing unit tests, e.g. `SecureXmlTest`, `McpSelectGuardTest`, `DynamicInstallSupportTest`).
