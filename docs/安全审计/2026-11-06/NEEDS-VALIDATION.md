# 待验证线索（NEEDS-VALIDATION）— mybatis-plus-sql-visualizer（run-1）

生成时间 2026-11-06T00:31:45.481Z，由 findings.json 派生（25 条记录，全部为 `needs_validation`，按「所有者可行动性」排序）。每条线索均有源码级轨迹与精确阻塞点，均不带严重级别。本次运行无法执行目标受控代码（审计主机无 OS 级沙箱），因此每条记录都需要一次有界本地检查与 / 或一次由所有者观察的部署检查来定案。

## 2026-11-07 修复落地更新（T-SecurityAuditFixes 分支，账本闭环）

修复批次落地后本文件对应的 `findings.json` 已更新（双验证器 PASS）：**24 条线索转 `confirmed`（已修复）**——lane_a（T-SAF-20/21/22/12/02）、lane_b（T-SAF-05/06/03/07/11/17/24）、lane_c（T-SAF-04/08/09/10/13/23/25）共 19 项 src/main 修复 + 构建防线 T-SAF-15/16（提交 965a15168）+ 车道 D 补齐 T-SAF-18/19（5dba1ac62/6c6fb091a、f07c7d8b7/dcf869d17，合并验证修正 e8d2a635d）+ 构建防线 T-SAF-14（b51f664af：mavenCentral 置前 + verification-metadata.xml 356 组件 sha256 严格校验），每条均以「先红复现测试在修复前转红 → 修复提交落地 → 回归通过」定案（T-SAF-14 为配置型控制，以 write-verification-metadata 实跑生成 + 严格模式 compileKotlin/testFast 全绿背书），并按 report-schema confirmed 分支补齐 intended_behavior / conditions / execution / remediation / severity / confidence。**唯一遗留 `needs_validation`：#1 凭据，为所有者明示豁免**（2026-11-06 定案：测试服务器口径，不轮换、不清理、不加守卫——豁免而非缩水）。

以下正文保留审计原判轨迹（原始 blockers / validation_plan 与逐条记录），供审计追溯，不逐条重写；confirmed 记录的最终口径以 `findings.json` 为准。

图例：**本** = 运行有界本地检查；**部** = 所有者观察部署事实。

| # | 线索 | 边界 | 首步 |
|---|---|---|---|
| 1 | 在 .agents/skills/db-env-provisioning/SKILL.md 中为公共测试服务器实例提交了可用的数据库凭据 | 已提交的手册 | 本+部 |
| 2 | 未认证的 loopback agent 通道：任何本地进程都可以注入伪造的 SQL 事件、夺取捕获控制权、抑制 attach，并窃听 agent 的 SQL 帧 | acceptPremain accept 循环（accept 在 :1001；与 :825 的 attachAndOffer accept 形状相同） | 本+部 |
| 3 | MCP 只读执行守卫仅在客户端：SELECT 调用的服务器端副作用以完整数据源权限执行，而 sqlv_explain 宣称 readOnlyHint=TRUE | sqlv_explain MCP 工具 | 本+部 |
| 4 | 接入插件预览控制台的 native execute 功能绕过了插件 DML 确认 / 无界写入 / 方言门链 | SqlPreviewPanel.executeSql | 本+部 |
| 5 | 更新产物 plugin-id 门使用的正则可能被 XML 注释与平台描述符解析器去同步 | downloadAndInstall | 本+部 |
| 6 | 更新 zip 防护仅限制大小与条目数量，而将条目名称清洗（zip-slip、Windows 保留设备名）完全委托给未经验证的平台解压器 | downloadAndInstall | 本+部 |
| 7 | 即使启用了插件的 MCP 输出脱敏设置，sqlv_history_search 仍返回带有未脱敏字面量参数值的还原 SQL | sqlv_history_search | 本+部 |
| 8 | 导入或与 VCS 共享的设置字段在渲染/执行的 SQL 中未做内容校验便被拼接，使共享设置文件可以改变语句语义 | 设置文件导入 | 本+部 |
| 9 | 导出的 SQL/HTML 报告未转义地插值较低信任的 tableName 和参数来源名称，并自动在浏览器中打开文件 | EntityResolver.resolve / annotationValue | 本+部 |
| 10 | 较低信任字符串被未转义地插值进回滚对话框、预览元信息行和错误工具提示中启用 HTML 的 Swing 标签 | 回滚生成器 | 本+部 |
| 11 | Guard 的 INTO 结构检查只覆盖顶层：CTE 或派生表内的 SELECT ... INTO &lt;table&gt; 同时绕过 hasIntoClause 与短语扫描 | checkSingleSelect | 本+部 |
| 12 | 无 token 的日志行共用一个兜底解析桶，重新引入跨线程 Preparing/Parameters 错配并直达 SQL 执行 | threadTokenOf (invoked per line at SqlLogConsoleBridge.kt:1155 and LogToSqlPanel.kt:490) | 本+部 |
| 13 | 自动保存写入器以 CREATE+APPEND 打开历史文件并跟随符号链接，而修订路径强制不跟随，导致可被植入项目外符号链接目标追加写入 | resolveHistoryDir (write-side configure and read-side HistorySqlLogPanel share it) | 本 |
| 14 | 依赖解析优先使用阿里云镜像而非 Maven Central 且无依赖校验，被替换的镜像字节未经质疑地进入发布的插件 | dependencyResolutionManagement.repositories | 本+部 |
| 15 | Gradle wrapper 分发包从腾讯云镜像拉取且无 distributionSha256Sum，本地构建（含 Gitee beta 发布构建）运行未校验的工具链 | distributionUrl | 本+部 |
| 16 | 市场/兼容性门禁通过首次匹配的缓存遍历解析 verifier-cli jar，当 Gradle 缓存中同时存在多个版本时会静默忽略已固定（pinned）的版本 | 已固定的 verifier-cli 版本 | 本+部 |
| 17 | MCP 文本分析工具（sqlv_check_sql、sqlv_rollback_plan、sqlv_reverse_sql、sqlv_perf_advice）解析长度无上限的 agent 提供 SQL，而同级的 render-logs 路径对同一 sink 类别强制执行 20k 上限 | sqlv_check_sql | 本 |
| 18 | SqlReverseParser 递归下降没有嵌套深度守卫：深度括号或嵌套子查询 SQL 经由 sqlv_reverse_sql 触发 StackOverflowError | sqlv_reverse_sql | 本 |
| 19 | OgnlEvaluator.parse 泄漏 NumberFormatException（整数面值 &gt; Long.MAX_VALUE）和 StackOverflowError（深度嵌套）出其 ParseResult/Err 契约，未捕获地到达 MCP mapper-check 和 UI 预览 | entrypoint | 本+部 |
| 20 | MyBatis Parameters 文本被二次复杂度正则解析且没有现有的 maxParseLength 上限，冻结控制台解析线程 | EpLineFilter.applyFilter / SqlLogConsoleBridge.handleConsoleLine (SqlLogConsoleBridge.kt:391) | 本+部 |
| 21 | ExecutionTimeParser 的 trailing-ms 正则表达式对无界控制台长行中的连续空白段产生平方级回溯 | finishEntries（由 processConsoleLine :1170 与嗅探解封路径 :1114 调用） | 本+部 |
| 22 | MyBatis/Hibernate 解析器的线程桶在 pending 期间永不被淘汰，随每个控制台累积无界保留的 SQL | feedInto 分支 1 | 本+部 |
| 23 | 被植入或超大的 .sql 历史文件在历史读取与 MCP 历史搜索期间于 IDE 内存中无界缓冲非 revision 行与块 | scanHistoryFiles（对每个列出的文件调用 countInFile） | 本+部 |
| 24 | sqlv_history_search 中由代理提供的正则表达式在历史 SQL 上求值时没有超时或成本预算，可在 IDE 进程内造成灾难性回溯的 CPU 耗尽 | sqlv_history_search | 本+部 |
| 25 | 导入的动态表名正则规则在预览渲染路径上被编译和执行，没有复杂度或超时上限 | 设置导入 | 本+部 |

---

## 1. 在 .agents/skills/db-env-provisioning/SKILL.md 中为公共测试服务器实例提交了可用的数据库凭据

- **指纹**：`db-env-provisioning.skill.highgo-xc3-password`
- **边界 / 入口点**：已提交的手册 — 已逐字验证：`CREATE USER xc3 PASSWORD 'Zq7#Pq9m2026';` 位于 passwordcheck 规避说明中；第 164 行说明该凭据只能在策略之外维护，即它持续有效。

### 描述

仓库在一个已提交的操作技能文档中，随维护者测试服务器的公网 IP 以及该实例的 Docker 端口映射，附带了用户 xc3 的字面量 HighGo（瀚高）数据库密码。已只读验证：该字面量同时存在于所引用的行（工作树中）与 git HEAD 中（git show HEAD grep 命中），该文件被 git 跟踪（git ls-files --error-unmatch），且未被 .gitignore 覆盖（git check-ignore 退出码 1）。同一文档明确说明该凭据无法通过引擎的口令策略更改，必须原样维护，因此它很可能仍是该实例上 xc3 账户的现行密码。如果映射端口暴露在互联网上，任何阅读该公开仓库的人都可以 xc3 身份连接到该数据库实例。

### 所称根因

一份操作手册在提交时使用了真实（而非占位）口令字面量，而同一手册中所有同级实例以及示例 TSV 都使用脱敏占位符（<强口令>、change-me）；文档树中不存在针对凭据字面量的 pre-commit 守卫。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `.agents/skills/db-env-provisioning/SKILL.md:160` | 已提交的手册 | 已逐字验证：`CREATE USER xc3 PASSWORD 'Zq7#Pq9m2026';` 位于 passwordcheck 规避说明中；第 164 行说明该凭据只能在策略之外维护，即它持续有效。 |
| propagation | `.agents/skills/db-env-provisioning/SKILL.md:81` | 实例台账 | 已验证：同一文档提交了 HighGo 实例行，其 host-port 映射为 25866→5866，将该凭据与第 8 行提交的 host（103.36.220.245）上的一个网络可达端点绑定。 |
| propagation | `.agents/skills/db-env-provisioning/SKILL.md:8` | 环境头 | 已验证：提交了手册所用的测试服务器身份/IP（103.36.220.245）；同级工作流文档（.github/workflows/nightly-full.yml:308-309、docs/发行说明/T-ReleaseChecklist-发行检查清单.md:74）确认该 host 提供 CI 可达的数据库实例（通过 MPV_DB_* secrets 在 25900 上的 MySQL）。 |
| sink | `.agents/skills/db-env-provisioning/SKILL.md:201` | 外部实例调用 | 已验证：手册指示通过 -Dmpvs.dialect.jdbcUrl 指向公网 host（以 MariaDB 实例 jdbc:mariadb://103.36.220.245:23306/xc3_test 为例）对这些实例运行验证套件，展示了所提交端点的预期远程（非 loopback）使用模式。 |

### 证据

- `.agents/skills/db-env-provisioning/SKILL.md:160` — 已验证：用户 xc3 的明文口令字面量 Zq7#Pq9m2026，同时存在于工作树与 git HEAD 中（文件按 git ls-files 跟踪，按 git check-ignore 未被忽略）。
- `.agents/skills/db-env-provisioning/SKILL.md:164` — 已验证：文档说明任何 ALTER USER … PASSWORD 都会再次被拒绝，因此所提交的凭据仍是 xc3 的现行凭据。
- `.agents/skills/db-env-provisioning/SKILL.md:81` — 已验证：实例表为 HighGo 容器提交了 host 端口映射 25866→5866。
- `.agents/skills/db-env-provisioning/scripts/instances.example.tsv:20` — 已验证：同级实例口令是脱敏占位符（<强口令>），表明 SKILL.md 中的字面量是异常而非常例。

### 阻塞点（精确）

- 此 host 上没有 OS 强制的沙箱可用于目标控制的执行
- 缺少部署观察：测试服务器的 HighGo 监听器（103.36.220.245:25866）是否暴露在维护者网络之外，以及所提交的凭据对 xc3 账户是否仍然有效——本次纯源码运行不允许任何网络接触

### 解决计划

**本地（有界、非破坏性）：** 添加一个源码扫描守卫（现有模式：BannedApiSourceScanTest 风格的守卫），断言 .agents/**、docs/**、dev-support/** 中没有 PASSWORD '...' 字面量或未脱敏的 jdbc 口令属性，然后验证它能在当前 SKILL.md:160 内容上变红。

**所有者观察的部署检查：** 仅由所有者观察：从维护者自己的环境检查 docker 是否在 0.0.0.0 上发布 25866（highgo 容器的 docker port、ss -tlnp），从 host 日志确认 xc3@25866 是否有来自维护者之外的入站连接，然后轮换该实例上的 xc3 凭据，并从文件和 git 历史中清除该字面量。

---

## 2. 未认证的 loopback agent 通道：任何本地进程都可以注入伪造的 SQL 事件、夺取捕获控制权、抑制 attach，并窃听 agent 的 SQL 帧

- **指纹**：`agent-ipc-loopback-unauth-session-trust`
- **边界 / 入口点**：acceptPremain accept 循环（accept 在 :1001；与 :825 的 attachAndOffer accept 形状相同） — Loopback accept 循环接受任何到 premain/attach 端口的本地 TCP 连接；在 accept() 与会话创建之间不存在对端凭据、token 或来源检查（已验证：log/agent/ 中任何地方都没有 getRemoteSocketAddress/对端凭据/远程地址检查）。

### 描述

IDE-agent IPC 通道在两个方向上都没有对端认证。IDE 的 loopback ServerSockets（attach 链 AgentManager.kt:799、premain 通道 :899、重绑定 :1577-1581）接受任何本地连接，并将其转换为完整的 AgentSession，其帧被视为 agent 权威；会话身份是自报的 HELLO pid。对恶意本地进程的后果：(1) 注入伪造的 SQL 事件，这些事件流入与控制台行相同的 sink（面板、带攻击者选定来源头的自动保存文件、跳转源码导航、历史与方言推断）；(2) 伪造 CMD_CONFIG_ACK 来控制 SqlCaptureGate.paused（抑制控制台桥或伪造“agent 已确认活动”），并用选定的 pid 投毒 attachedPids 以抑制对真实目标的 attach；(3) 通过赢得端口竞争，接收 agent 的 EVENT 帧（SQL 文本和字面量参数值），并用伪造的 CONFIG 在 15s 看门狗之后维持流。已验证此路径上最强的、源码可见的控制为：仅 loopback 绑定、1MB 帧上限（AgentProtocol.kt:41、SenderLoop.java:53）、捕获状态/用户暂停门（AgentSessionSupport.kt:513,538）、按 pid 的 HELLO 去重（AgentManager.kt:1156-1160）以及 SenderLoop MAX_FAST_DROPS（SenderLoop.java:112）——它们都是捕获状态、速率或按 pid 的机制，没有一个是身份对端检查；IDE 侧（src/main/kotlin/.../log/agent/）和 agent 侧（sub-projects/sql-visualizer-agent）代码中都不存在 token、共享密钥、HMAC、OS 对端凭据或远程地址检查，并且协商的端口在没有任何 capability secret 的情况下传递给目标 JVM（AgentProtocol.kt:67-68）。决定性的 OS 级事实（适用哪种本地对抗模型、跨用户的 loopback 可达性、端口发现）在本次纯源码运行中无法本地观察。

### 所称根因

AgentManager 绑定 loopback ServerSockets（AgentManager.kt:799 attach、:899 premain、:1577-1581 重绑定），并在没有 OS 对端凭据、共享密钥或通道所有权检查的情况下授予每个被接受的连接完整会话权限；HELLO pid（AgentManager.kt:157、:1196-1210）是自报的，并被信任用于 attachedPids 注册（AgentManager.kt:1162）和 premain 通道 pid；AgentSession.handleFrame（AgentSessionSupport.kt:486-588）将来自任何已连接 socket 的 EVENT/HEARTBEAT/CONFIG_ACK 帧应用于面板/自动保存/导航 sink 和 SqlCaptureGate 桥门；agent 侧 SenderLoop（SenderLoop.java:225）无条件地向任何响应协商端口的对象写入 HELLO/HEARTBEAT/EVENT 帧（SQL + 字面量参数值），并应用来自该对端的入站 CONFIG（SenderLoop.java:429-471）。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt:993` | acceptPremain accept 循环（accept 在 :1001；与 :825 的 attachAndOffer accept 形状相同） | Loopback accept 循环接受任何到 premain/attach 端口的本地 TCP 连接；在 accept() 与会话创建之间不存在对端凭据、token 或来源检查（已验证：log/agent/ 中任何地方都没有 getRemoteSocketAddress/对端凭据/远程地址检查）。 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt:1196` | readHelloPid / HELLO_PID_REGEX (:157) | 会话身份由自报的 HELLO 体建立；仅数字的正则接受任何攻击者选定的非负 pid，而缺失/格式错误的 HELLO 产生 pid=0 仍被容忍（会话在 :1161 提交，仅跳过 :1162 的 attachedPids 注册）；会话在 sessionCommitLock 下提交并注册 attachedPids[pid]（:1150-1163），无论对端实际是谁；:1156-1160 的按 pid 去重仅丢弃已健康的同 pid 会话的重复，因此任何其他选定的 pid 都被接受。 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentSessionSupport.kt:486` | AgentSession.handleFrame | 来自未认证 socket 的 EVENT/HEARTBEAT/CONFIG_ACK 帧被采信；唯一的 sink 前门是捕获状态（listenActiveProvider :513）和用户暂停（:538），而非对端身份；伪造的 EVENT 由 AgentEventAdapter.toEntry (:523) 适配，带有完全由攻击者选定的 sql/params/sourceFile/sourceCandidates/url（AgentEvent.kt:11,21,25,35,39）。 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentSessionSupport.kt:553` | logManager.entries.addEntry | 伪造的 SQL 条目以与控制台捕获行完全相同的方式进入面板/自动保存/跳转源码链；伪造的 sourceFile 被逐字写入自动保存头（SqlLogAutoSaver.kt:488-490），伪造的 sourceCandidates 驱动编辑器导航（EntryJumpResolver.kt:33-53）。 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt:1283` | updateOnline / SqlCaptureGate.paused（以及 AgentSessionSupport.kt:576 configAckDisabled、AgentManager.kt:1351 captureConfirmedActive） | 伪造的 CMD_CONFIG_ACK 设置 configAckDisabled=false（AgentSessionSupport.kt:576），从而驱动 captureConfirmedActive()（AgentManager.kt:1351）和桥门 SqlCaptureGate.paused（:1284）——被注入的对端控制 IDE 信任哪个捕获通道（被注入的会话仅通过发送它同样控制的 HEARTBEAT 帧保持“健康”）；attachedPids 投毒（:1162 + :585 的 putIfAbsent）抑制对选定 pid 的 attach，同时被注入的会话保持健康。 |
| sink | `sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/SenderLoop.java:225` | SenderLoop.run dial/write 循环（HELLO :237、HEARTBEAT :239、EVENT :263；入站 CONFIG 应用 :429-471、看门狗 :295-303） | agent 拨号 127.0.0.1:port 并向任何响应者写入 HELLO/HEARTBEAT/EVENT 帧（SQL 文本和字面量参数值）；赢得端口竞争的本地进程接收它们，用任何 CMD_CONFIG 帧设置 configSeen (:282) 来永久使 15s 看门狗（:295-303）失效，并通过 {"disabled":false} (:430-432) 保持 AgentState disabled=false，使捕获流持续流向抢占者。 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt:799` — attachAndOffer：ServerSocket(0, 1, InetAddress.getLoopbackAddress()) 然后在 :825 server.accept() ——仅 loopback 绑定，accept 时没有对端认证。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt:157` — HELLO_PID_REGEX = Regex("\"pid\"\s*:\s*(\d+)") ——会话 pid 由连接对端自报。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt:1162` — attachedPids[pid] = clockMs() 在 onPremainConnected 中由自报 pid 写入；attachToPid 的 putIfAbsent (:585) 随后跳过对该 pid 的 attach。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentSessionSupport.kt:576` — CMD_CONFIG_ACK 分支从任何已连接 socket 设置 configAckDisabled（:563-584）——是 captureConfirmedActive（AgentManager.kt:1351）和桥门 SqlCaptureGate.paused（AgentManager.kt:1283-1284）的唯一真相来源。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentSessionSupport.kt:553` — 来自未认证会话的 EVENT 帧在仅捕获状态门（:513 listenActiveProvider、:538 pausedProvider）之后被添加到日志条目（面板/自动保存/导航 sink）——两者都不是对端身份检查。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentProtocol.kt:67` — agentArgs = "port=$port;logLevel=$logLevel;stackFrames=$stackMaxFrames" ——协商的端口在没有共享密钥或 capability token 的情况下传递给目标 JVM（并可从其命令行读取）。
- `sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/SenderLoop.java:225` — socket = dialer.dial("127.0.0.1", serverPort) ——agent 信任监听协商端口的任何对象，并向其写入带字面量参数值的 SQL 帧（EVENT 写入在 :263）。
- `sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/SenderLoop.java:429` — handleInbound 通过基于 contains 的解析（:430-436、:472）应用来自同一未认证对端的 CONFIG，使抢占者能够通过发送任何带 "disabled":false 的帧使捕获在超过看门狗（:295-303）后保持启用。

### 阻塞点（精确）

- 此 host 上没有 OS 强制的沙箱可用于目标控制的执行（本次运行仅为源码审查；不允许启动任何本地进程、socket harness、双 JVM 变体或 IDE 实例，因此不存在有界的本地观察结果）
- 本地对抗模型在源码中不可见：适用哪种跨主体攻击者（同一用户的另一个非特权进程 vs 不同的本地 OS 用户），以及决定可达性的 OS 事实——不同用户的进程能否连接到/扫描开发者的 loopback 端口并读取携带协商端口的 -javaagent 命令行（或 attach 链监听器）；Windows loopback ACL 行为无法从仓库源码观察
- 没有有界的本地观察结果：将外部 loopback 连接接受为 agent 会话、注入伪造的面板条目、CONFIG_ACK 门翻转（SqlCaptureGate.paused）、attachedPids 投毒抑制后续 attachToPid，以及端口抢占窃听 agent 的 SQL 帧超过 15s 看门狗，均未在本地演示

### 解决计划

**本地（有界、非破坏性）：** 从仓库根目录非破坏性地运行现有的真实 socket harness：`gradlew.bat test --tests cn.song.mybatisplus.visualizer.log.agent.AgentManagerPremainTest --tests cn.song.mybatisplus.visualizer.log.agent.AgentManagerIntakeGateTest`（两者都存在于 src/test/kotlin/cn/song/mybatisplus/visualizer/log/agent/；它们已经使用普通的无凭据 java.net.Socket 客户端通过真实 loopback socket 驱动 premain/accept/intake 路径——AgentManagerPremainTest.kt:110,198,249 和 AgentManagerIntakeGateTest.kt:93-94——因此绿色运行是进程内基线，表明无凭据的连接被接受且其帧被采信；注意构建需要 IntelliJ 平台发行版和 JDK 17 已在本地 Gradle 缓存中，依据仓库的离线构建说明）。然后是双进程变体：当 harness 持有已接受的 premain/attach 端口时，第二个 JVM 进程连接到它，写入一个 4 字节长度的 HELLO 帧 {"pid":<选定>} 加上 CMD_EVENT 和 CMD_HEARTBEAT 帧，并断言 (a) 伪造的 LogSqlEntry（攻击者选定的 sql/params/sourceFile/sourceCandidates）落入条目缓冲区，且其 sourceFile 逐字出现在自动保存头中，(b) 伪造的 CMD_CONFIG_ACK {"disabled":false} 翻转 configAckDisabled 并通过 updateOnline 驱动 SqlCaptureGate.paused=true，(c) 对声称 pid 的 attachedPids 注册使后续 attachToPid(pid) 在 putIfAbsent（AgentManager.kt:585）处提前返回而不 loadAgent；抢占变体在 agent 拨号之前绑定协商端口，记录接收到的含字面量参数值的 CMD_EVENT 帧，并验证在 15s 看门狗窗口（SenderLoop.java:71）内伪造的 {"disabled":false} CMD_CONFIG 使流无限期存活。

**所有者观察的部署检查：** 在所有者自己的开发者机器上进行仅由所有者观察的检查（自己的机器、自己的插件实例、无第三方）：在启用增强捕获且应用在 IDE 下运行时，启动第二个本地进程，对 loopback 进行连接扫描以找到 premain/attach 端口（或从目标 JVM 的 -javaagent 命令行读取端口），发送 HELLO+EVENT 帧，观察伪造的 SQL 行（带攻击者选定的来源头）出现在插件面板和自动保存文件中；另外，在第二个进程中绑定 agent 的协商端口（在 agent 拨号之前，例如在全新 Run 期间），观察 agent 向该进程写入 SQL 帧（SQL 文本 + 参数字面量）以及伪造 pid 在插件状态/attach 日志中的 attach 抑制效果。

---

## 3. MCP 只读执行守卫仅在客户端：SELECT 调用的服务器端副作用以完整数据源权限执行，而 sqlv_explain 宣称 readOnlyHint=TRUE

- **指纹**：`mcp-select-guard.client-side-only-readonly-gap`
- **边界 / 入口点**：sqlv_explain MCP 工具 — agent 提供的 selectSql 进入；第 70 行的守卫允许任何形状为单条纯 SELECT 的语句，包括 SELECT <有副作用的函数>(...)，并且该工具在第 30 行宣称 readOnlyHint=TRUE。

### 描述

sqlv_explain 告诉 MCP agent 该工具严格只读（SqlVisualizerExplainTool.kt:30 的 @McpToolHints readOnlyHint=TRUE 以及第 26-27 行的描述 'Strictly read-only ... rejections are fail-closed'）。实际的控制是 McpSelectGuard.checkSingleSelect——一个按设计允许任何单条纯 SELECT 的客户端语句形状和关键字过滤器——随后 McpDbExecutor 用其存储的凭据连接到项目数据源，并在没有任何数据库层只读强制的情况下执行该语句（全仓库 grep src/main 未发现 connection.setReadOnly、SET TRANSACTION READ ONLY 和会话只读初始化）。一个作为纯 SELECT 通过守卫的语句仍可能通过 SELECT 调用的函数和存储例程（执行 DML 的用户定义函数；引擎内建如 MySQL load_file()、PostgreSQL pg_read_file/pg_ls_dir/lo_import/dblink/pg_sleep）以及通过计划期函数求值，以数据源账户的完整权限造成服务器端写入和状态改变。守卫自己的 deny-list（McpSelectGuard.kt:37-45，已验证 FORBIDDEN_WORDS）覆盖语句动词，而非函数体，且没有下游层收窄账户的权限。

### 所称根因

McpSelectGuard 将只读作为纯客户端语法属性（单条纯 SELECT）强制执行，而 McpDbExecutor.executeQuery 从不将连接或会话置于只读模式（没有 setReadOnly、没有只读事务启动），因此传达给 agent 的保证（readOnlyHint TRUE）超出了此仓库中任何层所强制的内容；有效权限是数据源账户的完整权限集。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerExplainTool.kt:34` | sqlv_explain MCP 工具 | agent 提供的 selectSql 进入；第 70 行的守卫允许任何形状为单条纯 SELECT 的语句，包括 SELECT &lt;有副作用的函数&gt;(...)，并且该工具在第 30 行宣称 readOnlyHint=TRUE。 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpDbExecutor.kt:295` | executeQueryInternal 连接设置 | 连接通过 DatabaseConnectionManager 以数据源存储的凭据构建；setAskPassword 是设置的唯一 builder 选项；未建立只读会话/连接模式，setQueryTimeout（第 311-316 行）仅是尽力而为。 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpDbExecutor.kt:325` | RemoteStatement 上的 executeQuery(String) | EXPLAIN 包装的语句在该连接上执行；SELECT 调用的函数/存储例程以数据源账户的完整权限在数据库服务器上执行，它执行的任何 DML 都在该账户下提交。 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSelectGuard.kt:37` — FORBIDDEN_WORDS（第 37-45 行）是语句动词 deny list，对 insert/truncate 有标量函数形式豁免；唯一写路径是 SELECT 调用的函数体的语句在结构上是 Select 并被允许。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpDbExecutor.kt:311` — 执行时应用的唯一守卫是尽力而为的 setQueryTimeout；grep src/main 发现此链上没有 connection.setReadOnly、SET TRANSACTION READ ONLY 或会话只读设置。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerExplainTool.kt:30` — 工具元数据宣称 readOnlyHint=TRUE（第 30 行），描述向 agent 声称 'Strictly read-only ... rejections are fail-closed'（第 26-27 行）。

### 阻塞点（精确）

- 此 host 上没有 OS 强制的沙箱可用于目标控制的执行
- 缺少观察：受支持引擎通过 DatabaseConnectionManager/RemoteStatement 反射通道以数据源账户执行 SELECT 调用的有副作用函数/存储例程（例如执行 DML 的 H2 CREATE ALIAS 函数，或 PostgreSQL SELECT pg_read_file()）的实测行为——副作用是否发生并提交在源码中不可见，平台 DBN 连接层是否施加任何自己的只读属性也不可见

### 解决计划

**本地（有界、非破坏性）：** 用 assertAllowed("SELECT MY_WRITE_FN(1)") 和 CREATE-ALIAS 风格的调用形式扩展 McpSelectGuardTest（src/test/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSelectGuardTest.kt）语料，以固定守卫允许函数形式 SELECT 的行为（纯逻辑、普通 JUnit；运行 gradle --tests cn.song.mybatisplus.visualizer.mcp.McpSelectGuardTest）。端到端副作用观察无法在普通 JUnit 中通过 McpDbExecutor 进行（它反射进入 com.intellij.database 平台类），因此引擎级事实由所有者单独观察：在一个一次性的、可丢弃的内存/文件 H2 数据库上通过普通 JDBC，CREATE ALIAS 一个执行 INSERT 的函数，通过裸 Statement 执行 SELECT MY_WRITE_FN(1)（与 RemoteStatement 镜像的相同 JDBC 契约），并在调用后观察插入的行——用虚拟数据建立最小的引擎级边界结果。

**所有者观察的部署检查：** 在所有者的一次性数据源上进行仅由所有者观察的检查：用调用执行 DML 的存储函数的 SELECT 调用 sqlv_explain（例如 PostgreSQL `SELECT touch_audit_log()`），并从数据库侧确认 DML 已提交，而工具返回了只读计划响应；可选地验证在配置账户下 pg_read_file/load_file 的可用性。

---

## 4. 接入插件预览控制台的 native execute 功能绕过了插件 DML 确认 / 无界写入 / 方言门链

- **指纹**：`execute.embedded-native-console-exec-bypasses-confirm-chain`
- **边界 / 入口点**：SqlPreviewPanel.executeSql — 预览 SQL 取自 nativeEditorDoc/editor——从捕获的控制台/日志输出派生并由插件渲染到控制台文档中的文本

### 描述

DataSourceSelector.prepareAfterEditorResolved 在插件预览面板内伪造了完整的 native 执行上下文：它安装了一个暴露 PROJECT、EDITOR、VIRTUAL_FILE、PSI_FILE 和 DBN SessionClientHolder.CLIENT_KEY 的 UiDataProvider（DataSourceSelectorConsole.kt:273-303），并通过 JdbcConsole.buildHeaderComponent (:342-350) 构建 native Console.Jdbc.Left/Right 头，明确地使 native Execute (▶) 工具栏动作和 Ctrl+Enter 在嵌入式控制台编辑器上启用（插件自己的注释记录了没有此接线 ▶ 会被永久禁用，因为 QueryActionBase.update 需要 EDITOR+console，而嵌入式面板缺少 FileEditor/平台 provider）。插件自己在 :397-398 的注释列举了在此控制台上的三条执行路径（'原生 ▶ / 控制台 Ctrl+Enter / 插件按钮'）。只有插件按钮路径运行执行授权链——SqlTypeClassifier DML 确认（带 ExecuteConfirmMessages/SchemaValidationGate 行）、${}-raw 和未知参数确认、ExecutionDialectGate.allowDialectGate。native ▶ / Ctrl+Enter 路径通过平台动作链提交控制台文档，而没有任何这些门，而文档文本是插件从捕获的控制台/日志输出派生的 SQL——确认链旨在防范的较低信任主体。结果：在同一不受信任 SQL 上的两个视觉相邻的 execute 功能具有不同的守卫集（同级控制等价性失败）。

### 所称根因

prepareAfterEditorResolved 故意在插件面板内重建平台执行数据上下文（CLIENT_KEY/PROJECT/EDITOR/VIRTUAL_FILE/PSI_FILE 通过 UiDataProvider，DataSourceSelectorConsole.kt:273-303）和 native 头（:342-350）以使 ▶/Ctrl+Enter 工作，但插件的执行授权门只存在于插件驱动的提交路径中（SqlPreviewPanel.executeSql、executeOnConsole、chooseAndPrepare）；没有任何门附加到控制台编辑器或 native 动作路径。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt:1123` | SqlPreviewPanel.executeSql | 预览 SQL 取自 nativeEditorDoc/editor——从捕获的控制台/日志输出派生并由插件渲染到控制台文档中的文本 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt:282` | UiDataProvider.uiDataSnapshot | provider 提供 PROJECT (:282)、EDITOR (:283)、VIRTUAL_FILE (:284)、惰性 PSI_FILE (:286-293) 和 DBN SessionClientHolder.CLIENT_KEY (:294-302)，在插件嵌入式面板中启用 native QueryActionBase |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt:349` | prepareAfterEditorResolved 头构建 | 反射调用 JdbcConsole.buildHeaderComponent (:349) 在嵌入式编辑器上安装 Console.Jdbc.Left（native ▶ execute）；通过此功能的执行从不经过 allowDialectGate、SqlTypeClassifier 确认或 ${}-raw/未知参数门——这些仅在插件按钮链中运行（SqlPreviewPanelExecution.kt:1143-1166） |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt:245` | native 工具栏执行上下文 | DataProvider 接线使嵌入式面板的动作上下文“等同于 native 工具窗口”（:245），因此平台动作以零插件执行门向用户数据源提交控制台文档（插件可见的 sink 是此接线；提交动作本身位于闭源平台 Database 插件中） |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt:244` — 注释块 :240-244 说明 DataProvider 接线正是为了使 native ▶ execute 动作解析控制台（'原生头部工具栏动作（▶执行等）经 JdbcConsole.findConsole(event) 解析控制台'），并记录了沙箱观察：没有它 ▶ 会被永久禁用
- `src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt:398` — 注释列举“原生 ▶ / 控制台 Ctrl+Enter / 插件按钮”为同一控制台上共存的三条执行路径
- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt:1146` — 插件按钮路径通过 Messages.showYesNoDialog 与 ExecuteConfirmMessages.build（:1148-1153，包括 SchemaValidationGate 行）显示 DML 确认——native 功能跳过的门集
- `src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt:221` — allowDialectGate 在此定义并仅从插件驱动的提交路径调用——executeOnConsole (DataSourceSelectorConsole.kt:559)、chooseAndPrepare 会话消费者 (DataSourceSelectorResults.kt:456) 和 structured-EXPLAIN 预检查 (SqlPreviewPanelExecution.kt:1738)——从不从 native 动作路径调用

### 阻塞点（精确）

- 此 host 上没有 OS 强制的沙箱可用于目标控制的执行
- 缺少观察：嵌入式头中的 native ▶/Ctrl+Enter 是否真的以注入的 UiDataProvider 上下文到达平台 QueryActionBase 并在没有任何插件门的情况下提交——接线是反射的，在某些 IDE/Database 插件版本上可能失败或被 DataValidators 拒绝
- 缺少观察：平台 Database 控制台是否在此嵌入式配置下对危险语句（例如无界 UPDATE/DELETE）施加自己的执行前确认；这样的平台警告会将差距缩小为守卫等价性/措辞问题，而非缺少确认

### 解决计划

**本地（有界、非破坏性）：** runIde-sandbox robot 检查加上无头入口点扫描：扩展 ExecutionDialectGateTest 风格的“未覆盖入口点”扫描，枚举 prepareAfterEditorResolved 接线的每个执行功能（UiDataProvider 键 :277-303、来自 buildHeaderComponent :342-350 的 Console.Jdbc.Left 动作），并断言每个都解析到一个有门的入口；然后在 IDE 沙箱中从日志条目打开无界 DML（UPDATE t SET x=1 无 WHERE）的预览，点击嵌入式 native ▶（使用配置的虚拟 loopback 数据源），观察提交前是否出现任何确认；锚定现有测试：ExecutionDialectGateTest、ExecTempFileEdtGuardTest、RollbackSqlPanelTest（确认路径等价性基线）

**所有者观察的部署检查：** 所有者在本地虚拟 MySQL/PG 数据源上运行 runIde（IntelliJ 2024.3 + Database 插件）：通过插件 Execute 按钮执行一次相同的无界 DML，再通过嵌入式 native ▶/Ctrl+Enter 执行一次，然后比较控制台历史与 affected rows，以确定 native 路径是否在没有无界写入确认的情况下执行

---

## 5. 更新产物 plugin-id 门使用的正则可能被 XML 注释与平台描述符解析器去同步

- **指纹**：`update.validateArtifact.regex-comment-desync`
- **边界 / 入口点**：downloadAndInstall — 更新通道控制的 zip 通过 UpdateZipGuard 边界检查（第 124 行）并到达第 129 行的安装接缝

### 描述

DynamicInstallSupport.validateArtifact 是在安装前将下载的更新 zip 绑定到此插件身份的唯一控制。它在原始 plugin.xml 文本上用正则 <id>(.*?)</id>（DOT_MATCHES_ALL）提取 <id>，这会匹配 XML 注释内的标签内容，而实际安装所用的平台描述符加载器解析真实 XML 并忽略注释。一个伪造的描述符，其唯一位于真实 <id>...</id> 之前的匹配位于注释内，就能通过门，而平台读取到的是外部插件 id；动态安装路径将 zip 和平台解析的描述符直接传递给 PluginInstaller.installAndLoadDynamicPlugin，自身不做任何 id 比较，而重启脚本回退路径将 zip 的（攻击者命名的）顶层目录解包到 plugins 根目录下。这破坏了文档记录的更新服务器到 IDE 边界的“plugin-id 匹配”不变量：被攻陷的更新通道不再局限于替换此插件，而是可以以另一个插件的身份安装内容。前提是控制更新通道（manifest 或 downloadUrl），这已经产生作为此插件的任意代码；额外收益是植入/替换不同的插件身份。

### 所称根因

checkDescriptorXml 验证对原始文本的正则提取（DynamicInstallSupport.kt:182-183），而不是平台消费者使用的解析 XML 树；门产生的保证（id 等于 PLUGIN_ID）不是消费者所假定的保证（平台解析的描述符 id 是 visualizer id）。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/update/PluginUpdateRunner.kt:104` | downloadAndInstall | 更新通道控制的 zip 通过 UpdateZipGuard 边界检查（第 124 行）并到达第 129 行的安装接缝 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/update/DynamicInstallSupport.kt:183` | extractTag | 带 DOT_MATCHES_ALL 的正则 &lt;id&gt;(.*?)&lt;/id&gt; 匹配原始文本中任何地方的第一次出现，包括位于真实 &lt;id&gt; 元素之前的 XML 注释内；readDescriptorBounded（第 152-166 行）执行原始字节到字符串转换，没有注释剥离或 XML 解析，因此管道中没有任何东西移除被注释的内容 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/update/DynamicInstallSupport.kt:174` | checkDescriptorXml | 门将被注释的良性 id 与 UpdateSource.PLUGIN_ID 比较并返回 Ok，尽管解析的 XML 声明了外部 id |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/update/DynamicInstallSupport.kt:88` | loadDescriptorFromArtifact | 平台解析同一产物并产生用于安装的真实（外部 id）描述符 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/update/DynamicInstallSupport.kt:260` | unloadAndInstallOnEdt / installAndLoadDynamicPlugin | 平台以真实 XML 中声明的身份安装/解包产物（动态路径）；重启脚本回退路径将攻击者命名的顶层目录解包到 plugins 根目录下（PlatformPluginInstaller.kt:57-59） |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/update/DynamicInstallSupport.kt:183` — extractTag 构建 Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL) 并在原始描述符文本上取第一个匹配，没有注释/CDATA 感知
- `src/main/kotlin/cn/song/mybatisplus/visualizer/update/DynamicInstallSupport.kt:174` — checkDescriptorXml 在正则提取的 id 等于 PLUGIN_ID 时接受；通过 grep 验证这是安装链中新产物的唯一 id 比较——唯一另一个 PLUGIN_ID 用途（DynamicInstallSupport.kt:76 findPlugin）查找当前已安装的描述符，而 newDescriptor（第 88 行）在没有任何 id 检查的情况下传递给平台安装调用
- `src/main/kotlin/cn/song/mybatisplus/visualizer/update/DynamicInstallSupport.kt:260` — installAndLoadDynamicPlugin 以 (zip, null, newDescriptor) 调用；该方法自身不执行任何 id 检查——类 KDoc（第 28-30 行）记录 newDescriptor 仅用于进度标题/日志/fireState，解包布局源自 zip 顶层目录名（仓库内部反编译声明，非平台源码）
- `src/test/kotlin/cn/song/mybatisplus/visualizer/update/DynamicInstallSupportTest.kt:76` — 现有测试覆盖错误 id（validateArtifactRejectsWrongPluginId，第 72-80 行）和缺失版本拒绝，但没有 XML 注释或解析器去同步形式，因此门的盲点未被测试
- `src/main/kotlin/cn/song/mybatisplus/visualizer/update/PluginUpdateRunner.kt:124` — guard-then-install 顺序确认 validateArtifact（在安装内部）是平台安装之前唯一的身份门

### 阻塞点（精确）

- 此 host 上没有 OS 强制的沙箱可用于目标控制的执行
- 平台对真实 id 不匹配的行为（PluginInstaller.installAndLoadDynamicPlugin 接受、重复 id 冲突处理、从 zip 顶层名派生安装目录，以及外部 id 产物是否替换另一个已安装插件）在本仓库之外的 IntelliJ 平台 jar 中实现，无法从源码观察
- 平台解析器对双形式描述符（被注释的良性 id 加真实外部 id）所见的确切 XML 需要在一个 fixture 上运行平台描述符加载器，这是目标控制的执行

### 解决计划

**本地（有界、非破坏性）：** 扩展 src/test/kotlin/cn/song/mybatisplus/visualizer/update/DynamicInstallSupportTest.kt，加入一个 fixture zip，其 META-INF/plugin.xml 为 '<idea-plugin> <!-- <id>cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer</id> --> <id>com.example.foreign</id> <version>2099.1.1</version> </idea-plugin>'，并断言 validateArtifact 返回 ArtifactCheck.Ok——这在不触及平台的情况下证明门/消费者去同步。然后，在批准的沙箱内，运行现有的 runIde 端到端 harness（src/test/kotlin/cn/song/mybatisplus/visualizer/heavy/UpdateFlowHeavyTest.kt 模式），由本地 manifest 提供同一 fixture，观察平台安装的内容。

**所有者观察的部署检查：** 在所有者的一次性 IDE 上进行仅由所有者观察的检查：将 -Dmpvs.update.manifest.url 设置为一个本地 HTTP 服务器，其 manifest.downloadUrl 指向伪造的 fixture zip，运行更新流程，并记录重启后的插件列表/安装目录——具体是外部 id 插件是否被安装或是否引发重复 id 冲突。

---

## 6. 更新 zip 防护仅限制大小与条目数量，而将条目名称清洗（zip-slip、Windows 保留设备名）完全委托给未经验证的平台解压器

- **指纹**：`update.UpdateZipGuard.entry-name-platform-delegated`
- **边界 / 入口点**：downloadAndInstall — UpdateZipGuard.check 会放行一个条目名称包含遍历或 Windows 恶意组件的 zip，因为该防护只读取大小和数量（UpdateZipGuard.kt:48-104 从不校验 entry.name——entry.name 仅出现在第 61、93、97 行的拒绝消息中）

### 描述

UpdateZipGuard.check 是归档/安装边界上的插件侧控制，仅校验条目数量以及声明与实际大小；它从不检查 entry.name。实际解压由平台代码完成：重启脚本路径上的 StartupActionScriptManager.UnzipCommand，以及动态路径上 PluginInstaller.installAndLoadDynamicPlugin 内部的 unpack。两个解压器的规范化行为（拒绝 ../ 与嵌套 ..、绝对路径或盘符限定的目标、NTFS 冒号/备用数据流名称、CON/NUL 等保留设备名、Windows 上结尾的点/空格、重复条目名）在本仓库中不可见，仓库中也没有任何测试对遍历命名或 Windows 恶意条目名的解压进行验证。如果任一平台解压器按原始名称写入条目，攻击者通道可控的更新 zip 会在安装时把文件写到插件根目录之外，或在开发者机器上写入恶意文件名（重启脚本路径会在下次 IDE 启动时执行，且不再重新校验）。

### 所称根因

负责保证归档边界的组件（UpdateZipGuard）并不校验消费者所依赖的属性（安全条目路径）；本应强制该属性的解压组件属于平台代码，其保证是假设的而非验证的；并且重启脚本路径会在下次启动时重新执行已持久化的 zip，而不重新运行任何防护。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/update/PluginUpdateRunner.kt:124` | downloadAndInstall | UpdateZipGuard.check 会放行一个条目名称包含遍历或 Windows 恶意组件的 zip，因为该防护只读取大小和数量（UpdateZipGuard.kt:48-104 从不校验 entry.name——entry.name 仅出现在第 61、93、97 行的拒绝消息中） |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/update/PlatformPluginInstaller.kt:134` | persistZip | 被接受的 zip 被复制到 system/mpvs-update 供启动脚本使用；重启脚本路径将其保留过安装调用之后，且不再做进一步校验 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/update/PlatformPluginInstaller.kt:58` | installRestartScript | 已调度 UnzipCommand(persistent, pluginsRoot)；解压目标根目录是固定的，但每个条目的目标路径由平台解压器从条目名称派生 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/update/DynamicInstallSupport.kt:260` | installAndLoadDynamicPlugin（动态路径） | 对同一归档的平台 unpack；每个条目的文件写入发生在本仓库之外的平台代码中——这是恶意条目名称的决定性写入位置 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/update/UpdateZipGuard.kt:48` — check() 读取条目、过滤目录，并仅强制 MAX_ENTRIES / 声明的单条目与总量上限以及截断读取；entry.name 仅用于拒绝消息（第 61、93、97 行），从不校验
- `src/main/kotlin/cn/song/mybatisplus/visualizer/update/UpdateZipGuard.kt:11` — KDoc 声明插件的职责是在平台解压器之前拦住异常包，隐含地承认路径安全是平台的职责——这是一次未经验证的交接
- `src/main/kotlin/cn/song/mybatisplus/visualizer/update/PlatformPluginInstaller.kt:58` — StartupActionScriptManager.UnzipCommand 接收已持久化的 zip 和固定的 plugins 根目录；条目名称处理属于平台内部行为
- `src/test/kotlin/cn/song/mybatisplus/visualizer/update/UpdateZipGuardTest.kt:23` — 测试套件（类声明于第 23 行；覆盖形式枚举于 KDoc 第 16-21 行）覆盖撒谎的头部、条目数量和大小上限，但没有遍历名称、绝对路径、冒号/ADS 或保留名称的测试夹具——边界的 zip-slip 那一半未被测试

### 阻塞点（精确）

- 本机上没有可用于目标控制执行的 OS 强制沙箱
- StartupActionScriptManager.UnzipCommand 与 PluginInstaller 动态 unpack 的条目路径规范化行为（包括 Windows 特有的冒号/ADS、保留设备名和结尾点处理）实现在本仓库之外的 IntelliJ 平台 jar 中，在此处不可见源码
- 动态 unpack 路径与启动脚本 UnzipCommand 是否共用同一解压器实现（从而在一方得到阳性结果即可覆盖另一方）是本仓库无法观察到的平台事实

### 解决计划

**本地（有界、非破坏性）：** 在获批的沙箱中，构造一个包含条目 '../zipslip-probe.txt'、'C:\\absolute-probe.txt'、'probe:ads.txt'、'CON.txt' 以及一个正常条目的夹具 zip；对空目标目录运行安装路径所消费的平台 unzip（StartupActionScriptManager 命令执行或 PluginInstaller unpack，使用 PlatformPluginInstallerTest.kt 的 harness 风格），并断言目录之外没有出现任何文件；此外扩展 UpdateZipGuardTest，至少钉住恶意条目名称会被报告。记录所使用的确切 JDK/平台版本。

**所有者观察的部署检查：** 由 owner 在一次性 Windows IDE 上观察检查：通过真实更新流程（本地 manifest，经 -Dmpvs.update.manifest.url）安装精心构造的探针 zip，并在计划的启动脚本运行之后，验证 PathManager.getPluginsDir() 之外不存在探针文件，且没有创建 ADS/保留名称文件。

---

## 7. 即使启用了插件的 MCP 输出脱敏设置，sqlv_history_search 仍返回带有未脱敏字面量参数值的还原 SQL

- **指纹**：`mcp.history_search.masking-not-applied`
- **边界 / 入口点**：sqlv_history_search — 已连接的 agent 调用 sqlv_history_search（仅受总开关/工具级启用开关 McpToolGate 控制），例如 keyword=password；该工具中从不查阅 mcpMaskEnabled 脱敏设置。

### 描述

插件暴露了一个「MCP 输出脱敏」设置（mcpMaskEnabled，PluginSettings.kt:677，已验证：`var mcpMaskEnabled: Boolean = false`）。脱敏是按工具分别应用的：只有 SqlVisualizerRenderLogsTool 对其输出调用 DisplayMasker.maskDisplay（SqlVisualizerRenderLogsTool.kt:112；全仓库 grep 确认这是 mcp 包中唯一的 maskDisplay 调用点）。同类的历史通道 sqlv_history_search 提供相同的数据类——参数已回填为字面量值的 restoredSql 条目——并且每次调用返回最多 100 个预览，每个 8 行/600 字符（allDates=true 会扩大到整个历史目录），而路径上任何地方都没有应用脱敏。因此，已连接的 agent 可以通过用针对敏感列的关键字搜索历史，提取操作员要求插件挡在 AI 上下文之外的敏感字面量。

### 所称根因

输出脱敏并未在共享工具集输出骨架中强制（McpToolsetSupport.audit 返回原始 block 字符串，McpToolsetSupport.kt:127-154）；每个工具必须自行选择启用，而 SqlVisualizerHistorySearchTool.formatResult 在编写时没有加入 maskDisplay 调用，因此 mcpMaskEnabled 不变式在一个输出通道上成立，在另一个通道上静默失效。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerHistorySearchTool.kt:57` | sqlv_history_search | 已连接的 agent 调用 sqlv_history_search（仅受总开关/工具级启用开关 McpToolGate 控制），例如 keyword=password；该工具中从不查阅 mcpMaskEnabled 脱敏设置。 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/HistorySearchService.kt:270` | buildResult | hit.sqlPreview = preview(e.restoredSql)——restoredSql 是参数已回填为字面量值的可执行 SQL（从自动保存的历史中读回；SqlLogParamCodec.encodeEntry 将 restoredSql 作为文件体写入，SqlLogFileReader 原样还原 entry.restoredSql）。 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerHistorySearchTool.kt:120` | formatResult | append('\n').append(hit.sqlPreview)——带有字面量参数值的未脱敏 SQL 文本被返回给 agent；该工具或服务中任何地方都不存在 DisplayMasker.maskDisplay 调用。 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerHistorySearchTool.kt:120` — formatResult 直接追加 hit.sqlPreview；对 mcp 包的 grep 显示 DisplayMasker.maskDisplay 仅在 SqlVisualizerRenderLogsTool.kt:112 被引用。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/HistorySearchService.kt:270` — 预览由 restoredSql（已回填字面量）构建，而不是由参数化或脱敏形式构建；Query 或 Hit 上不存在 mask 参数。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerRenderLogsTool.kt:112` — 对比：render-logs 通道在同一 mcpMaskEnabled 设置下应用 DisplayMasker.maskDisplay(entry.restoredSql, maskOn)（在第 99-101 行读取），确立了预期不变式。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt:677` — mcpMaskEnabled 是一个全局设置（默认关闭），没有会把 history_search 排除在外的按通道作用域；其 KDoc 声明目的是将敏感字面量挡在 AI 上下文之外。

### 阻塞点（精确）

- 本机上没有可用于目标控制执行的 OS 强制沙箱
- 缺失观察：有界本地运行尚未观察到值级输出——即由已保存后还原的历史条目产生的 Hit.sqlPreview 是否确实包含回填的字面量（源码确立了这一往返：SqlLogParamCodec.encodeEntry 将 restoredSql 持久化为文件体，SqlLogFileReader 原样还原 entry.restoredSql，且 McpHistoryIngestTest 夹具显示 restoredSql 可往返，但工具渲染的输出字符串未被观察到）

### 解决计划

**本地（有界、非破坏性）：** 两个使用虚拟数据的纯 JUnit 步骤：(1) 运行 gradle --tests cn.song.mybatisplus.visualizer.mcp.McpHistoryIngestTest，确认 restoredSql 经已保存历史文件的往返；(2) 在 cn.song.mybatisplus.visualizer.mcp.HistorySearchToolTest 中添加一个用例，构造一个 HistorySearchService.Result，其中某个 Hit 的 sqlPreview 内嵌一个虚拟敏感字面量（例如 "password = 'hunter-pw-123'"），调用 formatResult，并断言该字面量原样出现且没有 '***' 脱敏；在同一测试中，对同一字面量调用兄弟工具 SqlVisualizerRenderLogsTool.renderLogs(..., maskLiterals = true) 并断言它确实被脱敏——在相同虚拟输入下钉住两通道的不一致。

**所有者观察的部署检查：** 由 owner 观察、非破坏性：在沙箱 IDE 实例中于插件设置里启用「MCP 输出脱敏」，针对一个临时数据源运行一条带类密码字面量的 SQL，使其落入自动保存的历史；用测试客户端连接内置 MCP 服务器，调用 sqlv_history_search 并以针对该列的关键字搜索，观察响应中是否出现未脱敏的字面量。

---

## 8. 导入或与 VCS 共享的设置字段在渲染/执行的 SQL 中未做内容校验便被拼接，使共享设置文件可以改变语句语义

- **指纹**：`settings-import.sql-fragment-unvalidated`
- **边界 / 入口点**：设置文件导入 — applyImportedText -> PluginSettings.fromJson 将操作员选择的文件（importSettings 跨度约 1692-1716，file.contentsToByteArray 在 1702-1704 的池化线程上读取）合并进实时全局状态（PluginSettings.kt:918-921 将 instance.state.copy() 传入 SettingsIO.importFromJson 再 loadState 返回）；除了通用成功标签之外，没有显示任何影响 SQL 的值的预览或差异

### 描述

SettingsIO.importFromJson 接受 logicDeleteValue、tenantColumnName、tenantColumnDefault、dataPermissionMockCondition 和 dynamicTableNameRules.replacement 的任意字符串（tablePrefix/tableSuffix 也会到达同一标识符汇聚点），并且每个下游注入点都将这些值渲染进 SQL 文本，没有引号、转义或语法位置强制（SettingsIO.kt 或渲染层中任何地方都不存在值级校验）。因此，团队共享的设置文件（或提交的 .idea/mp-sql-visualizer-project.xml）可以翻转渲染语句的含义：logicDeleteValue='0 OR 1=1' 会把注入的守卫变成 'AND deleted = 0 OR 1=1'（按 'x AND a OR b' 计算时对所有行都为真，因此预览的 DELETE 实际上变成未过滤的），tenantColumnName='tenant_id=1 OR 1=1 --' 会替换租户过滤器，dataPermissionMockCondition 会追加任意 WHERE 片段，而动态表名 replacement 可以把 FROM 指向任意 SQL 文本。被改变的语句会显示在预览面板中，并在执行时原样提交给操作员的数据源。操作员可见的控制（白名单合并、版本门）约束的是文件可以设置哪些键，而从不约束这些键可以包含什么。

### 所称根因

设置到 SQL 的边界有键级白名单（SettingsIO.kt:91-113）但没有值级校验，而渲染层（SqlGenerator）按设计是直通的（SqlValue.Literal.raw 和 Condition.Raw.sqlText 原样渲染；标识符仅在保留字冲突时加引号），因此任何较低信任的设置通道（共享导入文件、VCS 共享的项目 XML）都可以把任意 SQL 文本放进渲染和执行语句的 WHERE 字面量、标识符和表名位置。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt:1719` | 设置文件导入 | applyImportedText -&gt; PluginSettings.fromJson 将操作员选择的文件（importSettings 跨度约 1692-1716，file.contentsToByteArray 在 1702-1704 的池化线程上读取）合并进实时全局状态（PluginSettings.kt:918-921 将 instance.state.copy() 传入 SettingsIO.importFromJson 再 loadState 返回）；除了通用成功标签之外，没有显示任何影响 SQL 的值的预览或差异 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/config/SettingsIO.kt:99` | 白名单合并 | importFromJson 直接从 JSON map 赋值 logicDeleteValue（第 99 行）、tenantColumnName（101）、tenantColumnDefault（102）、dataPermissionMockCondition（112），并通过 deserializeRules（113、337-345）赋值 dynamicTableNameRules——字符串原样通过，没有内容校验；版本门（82-86）只检查格式号 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/config/ProjectPluginSettings.kt:120` | 项目级覆盖通道 | 第二条较低信任的通道：.idea/mp-sql-visualizer-project.xml（VCS 共享）携带 logicDeleteValue/tablePrefix/tableSuffix；effectiveLogicDeleteValue()（第 120-122 行）在设置了 useProjectSettings 时覆盖全局值，经由 SqlPreviewService.effectiveLogicDeleteValue() 到达同样的汇聚点 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewServiceEnhancements.kt:393` | 逻辑删除注入 | SqlValue.Literal(logicDeleteValue) 由导入的值构建（第 386 行读取 effectiveLogicDeleteValue()），并作为逻辑删除条件 'AND &lt;logicColumn&gt; = &lt;raw&gt;' 追加给 SELECT（第 393 行）和子连接（403）以及 DELETE（420）；SqlPreviewService.kt:404-406 将 tenantColumnName/tenantColumnDefault 送入 TenantLineEnhancer.enhanceUpdateSet（TenantLineEnhancer.kt:41-46 SetClause value=SqlValue.Literal(tenantDefault)）以及租户 WHERE；DataPermissionPlaceholder.kt:47-49 将 dataPermissionMockCondition 作为 Condition.Raw 追加 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/sql/SqlGenerator.kt:560` | SQL 文本渲染与执行 | renderValue 原样返回 Literal.raw（SqlGenerator.kt:560；SqlValue.kt:21 'val raw: String' 文档说明为渲染进 SQL 的文本），Condition.Raw.sqlText 原样（SqlGenerator.kt:513-521），表和标识符位置原样渲染，除非该值是保留字（renderTableName -&gt; Dialect.quoteIfKeyword，Dialect.kt:72-73）；生成的文本就是从预览面板提交给 SqlExecutor.execute（execute/SqlExecutor.kt:38）的字符串 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/SettingsIO.kt:99` — importFromJson：map.getString("logicDeleteValue")?.let { state.logicDeleteValue = it }——值被原样接受；相邻行 101/102/112/113 对应 tenantColumnName、tenantColumnDefault、dataPermissionMockCondition、dynamicTableNameRules
- `src/main/kotlin/cn/song/mybatisplus/visualizer/model/SqlValue.kt:21` — SqlValue.Literal.raw（'val raw: String'，KDoc 第 15 行：'渲染进 SQL 的文本'）是原样渲染进 SQL 的文本——设置与 SQL 文本之间不存在转义层
- `src/main/kotlin/cn/song/mybatisplus/visualizer/sql/SqlGenerator.kt:560` — renderValue：'is SqlValue.Literal -> value.raw'——导入的 logic-delete/tenant 值原样落入 WHERE 子句
- `src/main/kotlin/cn/song/mybatisplus/visualizer/sql/interceptor/DataPermissionPlaceholder.kt:47` — injectMockCondition 为 Select/Update/Delete 追加 Condition.Raw(condition)——导入的 mock 条件在构造上成为原始 WHERE 片段
- `src/main/kotlin/cn/song/mybatisplus/visualizer/sql/Dialect.kt:72` — quoteIfKeyword 仅在标识符与方言保留字集冲突时加引号——'tenant_id=1 OR 1=1 --' 作为列引用原样通过
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/ProjectPluginSettings.kt:120` — 项目级 logicDeleteValue（持久化于 .idea/mp-sql-visualizer-project.xml，通常 VCS 共享）无校验地覆盖全局值
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt:918` — PluginSettings.fromJson 将导入的 JSON 加上实时实例状态传入 SettingsIO.importFromJson 和 loadState——导入会覆盖后续每次渲染所用到的实时全局状态

### 阻塞点（精确）

- 本机上没有可用于目标控制执行的 OS 强制沙箱
- 缺失观察：本地渲染的 SQL 文本（针对携带载荷值的导入状态，例如 logicDeleteValue='0 OR 1=1'、tenantColumnName='tenant_id=1 OR 1=1 --'、包含 SQL 文本的 dynamicTableNameRules replacement），以确认每个片段都原样落入生成的语句
- 缺失观察：由 owner 在虚拟本地数据源上观察执行，确认操作员执行预览的 DELETE/UPDATE 时改变的行范围（例如 'AND deleted = 0 OR 1=1' 因 AND/OR 优先级而匹配所有行）

### 解决计划

**本地（有界、非破坏性）：** 无需 IDE 的纯函数校验：扩展 src/test/kotlin/cn/song/mybatisplus/visualizer/config/SettingsIOTest.kt，使用一个导入 JSON，其中 logicDeleteValue='0 OR 1=1'、tenantColumnName='tenant_id=1 OR 1=1 --'、dataPermissionMockCondition='1=1 UNION SELECT 1'，以及一个包含 SQL 文本的 dynamicTableNameRules replacement；然后用 SqlGenerator.generate（无 IDE 依赖）渲染得到的语句模型，并断言 WHERE/表文本包含每个注入片段；复用现有 harness PluginSettingsTestSupport（src/test/kotlin/cn/song/mybatisplus/visualizer/testutil/PluginSettingsTestSupport.kt）、DataPermissionPlaceholderTest 和 DynamicTableNameProcessorTest 作为模式。运行：gradle --tests cn.song.mybatisplus.visualizer.config.SettingsIOTest --tests cn.song.mybatisplus.visualizer.sql.interceptor.DynamicTableNameProcessorTest --tests cn.song.mybatisplus.visualizer.sql.interceptor.DataPermissionPlaceholderTest。

**所有者观察的部署检查：** 由 owner 在虚拟本地 MySQL 上观察检查：通过设置页的导入按钮导入精心构造的设置 JSON，预览某个 @TableLogic 实体的 wrapper DELETE/SELECT，通过标准 DML 确认点击 Execute，并将受影响/返回的行与 MyBatis-Plus 运行时实际执行的语句（插件声明的保真目标）进行比较，确认导入文件改变了执行 SQL 的语义，超出了操作员可见配置的范围。

---

## 9. 导出的 SQL/HTML 报告未转义地插值较低信任的 tableName 和参数来源名称，并自动在浏览器中打开文件

- **指纹**：`share.html-report-export.unescaped-tableName-src`
- **边界 / 入口点**：EntityResolver.resolve / annotationValue — 实体的 @TableName（或 @TableField column）注解的原始字符串字面量在没有任何校验的情况下被读入 TableInfo.tableName；attributeString（:464-472）原样返回字面量值（仅做非空检查）

### 描述

F-8「导出 HTML 报告」共享通道会写出一个独立 HTML 文件，并在用户确认保存对话框后用默认浏览器打开它（BrowserUtil.open）。HtmlReportFormatter 会转义 SQL 标记和参数值，但原样插值表名（'<b>$it</b>'，:72）、方言（:73）和参数来源名称（'<code>$src</code>'，:147）。PreviewResult.tableName 和 unresolvedParams 携带来自较低信任项目文件的任意字符串（实体 @TableName/@TableField 注解值、实体列名）——按架构，其信任主体低于操作员。恶意克隆的项目可以在这些字段中放置带标记的字符串；当开发者导出报告时，注入的标记会在被打开报告的 file:// 上下文中被渲染为 HTML。同类诊断 HTML 格式化器会转义相同字段，表明了该格式化器所违反的预期不变式。

### 所称根因

HtmlReportFormatter.format/buildParamsTable 仅对 SQL 文本和参数值应用 escapeHtml，而遗漏了 tableName（HtmlReportFormatter.kt:72）、dialect（:73）和参数来源名称（:147），同时导出流程会自动在浏览器中打开保存的文件；上游对项目文件派生的注解字符串不存在字符校验（EntityResolver.kt:454-472）。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/psi/EntityResolver.kt:101` | EntityResolver.resolve / annotationValue | 实体的 @TableName（或 @TableField column）注解的原始字符串字面量在没有任何校验的情况下被读入 TableInfo.tableName；attributeString（:464-472）原样返回字面量值（仅做非空检查） |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt:239` | SqlPreviewService 预览构建器 | 未校验的 tableName（:239 处 jpaCrud.tableName；另有 specPreview.tableName :467、jpaCrud.tableName :511、table?.tableName :701）和注解派生的参数来源名称（CrudSqlGenerator.kt:167，unresolvedParams = params.map { it.columnName }；实际路径 src/main/kotlin/cn/song/mybatisplus/visualizer/generator/CrudSqlGenerator.kt）被存入 PreviewResult 并显示在 SqlPreviewPanel；面板暴露 currentTableName()（SqlPreviewPanel.kt:1131）和 unresolvedParams()（:1110） |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/share/ui/ShareDialog.kt:206` | ShareDialog.exportHtmlReport | panel.currentTableName()（:206）和 panel.unresolvedParams()（:204）被传入 HtmlReportFormatter.format；相同的值也经由 diagnosticInput（:168/:170）流入诊断 HTML 导出，后者会对它们转义 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/share/format/HtmlReportFormatter.kt:72` | HtmlReportFormatter.format 元信息块 / buildParamsTable | tableName 原样插值为 '&lt;span&gt;Table: &lt;b&gt;$it&lt;/b&gt;&lt;/span&gt;'（:72；dialect 在 :73 原样），来源名称原样插值为 '&lt;code&gt;$src&lt;/code&gt;'（:147）进入 HTML 文档；同一文档中的 SQL 标记和参数值都被转义（:91/:97/:100/:107），证实该遗漏是无意的 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/share/ui/ShareDialog.kt:219` | ShareDialog.exportHtmlReport 交付 | shareManager.saveHtmlReport 写出文件，BrowserUtil.open(file.toURI().toString()) 在 FileSaverDialog 保存后于用户的外部浏览器中启动它，将存储的标记转换为渲染的 HTML，无额外导航 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/share/format/HtmlReportFormatter.kt:72` — tableName 和 dialect（:73）未用 escapeHtml 便插值进 HTML，而同一文件中的 SQL 标记路径使用了 escapeHtml（:91/:97/:100/:107）
- `src/main/kotlin/cn/song/mybatisplus/visualizer/share/format/HtmlReportFormatter.kt:147` — 参数来源名称 '<code>$src</code>' 未转义；只有配对值使用了 escapeHtml
- `src/main/kotlin/cn/song/mybatisplus/visualizer/share/format/DiagnosticReportFormatter.kt:164` — 同类诊断 HTML 导出会转义整个 metaLine——在 :184-190 由 input.source/tableName/dialect 构建——以及所有渲染的小节行（:256），确立了预期不变式
- `src/main/kotlin/cn/song/mybatisplus/visualizer/psi/EntityResolver.kt:101` — tableName 从 @TableName 注解字符串字面量原样取得；attributeString（:464-472）不做字符校验，只做 isNotBlank 检查
- `src/main/kotlin/cn/song/mybatisplus/visualizer/share/ui/ShareDialog.kt:219` — 导出的报告在保存对话框确认后通过 BrowserUtil.open(file.toURI().toString()) 在外部浏览器中打开
- `src/test/kotlin/cn/song/mybatisplus/visualizer/share/format/HtmlReportFormatterTest.kt:72` — 现有转义测试（'format_HTML特殊字符_正确转义'）只覆盖 SQL 文本，不覆盖 tableName 或来源名称——未测试的边界

### 阻塞点（精确）

- 本机上没有可用于目标控制执行的 OS 强制沙箱
- BrowserUtil.open 启动的外部浏览器中 file:// HTML 报告的脚本执行行为是无法从源码观察的浏览器/部署事实（现代浏览器对 file 源脚本限制各不相同）；没有它，观察到的效果是报告中的 HTML 内容注入，而非脚本执行
- 端到端链条（预览携带恶意 @TableName 的项目实体、导出 HTML 报告、观察被打开的报告）未在此纯源码运行中执行

### 解决计划

**本地（有界、非破坏性）：** 扩展 src/test/kotlin/cn/song/mybatisplus/visualizer/share/format/HtmlReportFormatterTest.kt（纯 JUnit，无 IDE）：调用 HtmlReportFormatter.format(sql="SELECT 1", sources=listOf("<img src=x onerror=alert(1)>"), paramValues=mapOf("<img src=x onerror=alert(1)>" to "v"), tableName="<img src=y onerror=alert(2)>", dialect="MySQL")，并断言输出中除了转义实体之外不包含原始 '<img'；目前预期对 tableName、dialect 和来源名称列失败，而 DiagnosticReportFormatter.formatHtml 可通过同一载荷。使用 gradle --tests cn.song.mybatisplus.visualizer.share.format.HtmlReportFormatterTest 运行

**所有者观察的部署检查：** 在 runIde 沙箱中使用一个演示项目，其实体携带 @TableName("<img src=x onerror=console.log(1)>") 且 @TableField 列名携带标记，预览该实体的一条语句，运行 Share > Export HTML Report，观察自动打开的浏览器是否从保存的文件执行注入的处理器（仅使用本地虚拟载荷，无外部调用）

---

## 10. 较低信任字符串被未转义地插值进回滚对话框、预览元信息行和错误工具提示中启用 HTML 的 Swing 标签

- **指纹**：`ui.swing-html-label.unescaped-lower-trust-text`
- **边界 / 入口点**：回滚生成器 — snapshotSql 由 JSqlParser 解析的原始语句构建为 'SELECT * FROM $table WHERE $whereText;'，其文本源自较低信任的来源（来自任意宿主进程的控制台捕获 SQL、粘贴/预览的 SQL、mapper XML）

### 描述

插件通过 Swing HTML 标签渲染较低信任的文本（从控制台捕获还原的 SQL 片段、mapper XML 语句标识符、DB 服务器错误文本）。同类位置一致转义，且若干组件使用 Swing 的 'html.disable' 客户端属性，但有三个汇聚点将此类文本未转义、也未设置 html.disable 地插值进 <html> JBLabel 或工具提示：RollbackSqlDialog 快照查询行（:626，以及 :627 的原始 tooltip）、SqlPreviewPanel 元信息行（tableName / XML statementId / 解析器警告 / 堆栈摘要；当存在 perf 警告段时可见的 <html> 包装生效）、以及 SqlResultPanel 错误工具提示（:277）。Swing HTML 渲染无法执行脚本，且这些 JLabel 没有 HyperlinkListener，因此边界效果限于 HTML 内容/布局注入，外加潜在的 <img src> 资源加载（从 IDE 进程向攻击者选定 URL 发出的请求）；IntelliJ JBLabel/工具提示 HTML 在运行时的实际图片加载行为是缺失的观察。

### 所称根因

各站点各自为政的 HTML 转义，没有共享不变式：若干调用点转义 &<> 且五个标签禁用 HTML，但 RollbackSqlDialog.kt:626、SqlPreviewPanelResultView.kt fullDisplayText/applyMetaTruncation（:719/:728）和 SqlResultPanel.kt:277（toolTipText）在未转义、也未设置 html.disable 客户端属性的情况下将较低信任字符串插值进 HTML 上下文。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/sql/rollback/RollbackSqlGenerator.kt:283` | 回滚生成器 | snapshotSql 由 JSqlParser 解析的原始语句构建为 'SELECT * FROM $table WHERE $whereText;'，其文本源自较低信任的来源（来自任意宿主进程的控制台捕获 SQL、粘贴/预览的 SQL、mapper XML） |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.kt:625` | 回滚对话框 | assembleSnapshotSection（:623-627）取 currentResult.snapshotSelectSql，仅做长度截断（SNAPSHOT_DISPLAY_LIMIT）——不做字符转义 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.kt:626` | 回滚对话框 JBLabel | snapshotQueryLabel.text = "&lt;html&gt;&lt;b&gt;…&lt;/b&gt; &lt;code&gt;$shown&lt;/code&gt;&lt;/html&gt;" 位于普通 JBLabel（:85）上，且 :627 还设置 toolTipText = sql 原始值——未转义的 HTML 汇聚点；该类自己的 escapeHtml（:1020）在此未被使用 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelResultView.kt:728` | 预览面板元信息标签 | fullDisplayText（:725-728）在 metaPerfSegments 非空时（存在 perf 警告；applyMetaTruncation 中 :717-720 有同样的包装）将 metaLineText——其在 :622（result.tableName）和 :629（xmlModel.statementId）未转义地组合，并在 :650 与警告文本连接成 metaCoreText——包装在 '&lt;html&gt;…&lt;/html&gt;' 中，写入 metaLabel（SqlPreviewPanel.kt:145，普通 JBLabel，无 html.disable）；未转义的 meta 内容还经由 metaTooltipBase（DialectStatusRefresh.kt:263）到达 metaLabel.toolTipText |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlResultPanel.kt:277` | 执行结果面板工具提示 | showError 转义可见标签文本（:274）并将其包装在 &lt;html&gt;（:275）中，但将原始错误字符串（远程 DB SQLException 文本）未转义地赋给 infoLabel.toolTipText；Swing 工具提示仅在文本以 '&lt;html&gt;' 开头时才按 HTML 渲染，而恶意的 DB 服务器/代理错误消息可以安排这一点 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.kt:626` — $shown 未转义地插值进 <html> 标签，而同一个类在 :1020 定义了 escapeHtml 并将其用于警告路径（:1014-1018）
- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelResultView.kt:650` — metaCoreText = meta + warningLine，其中 meta 在 <html> 包装（:719/:728）之前原样包含 result.tableName（:622）和 xmlModel.statementId（:629），未做转义
- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt:145` — metaLabel 创建为普通 JBLabel()，没有 html.disable 客户端属性，不同于 dmlImpactBannerLabel 的 putClientProperty("html.disable", true) 位于 :664
- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlResultPanel.kt:277` — infoLabel.toolTipText = error（原始、未转义）直接位于 :274-275 的转义可见标签赋值之下
- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/DegradationBanner.kt:57` — 代码库中针对这一风险的现有控制——putClientProperty("html.disable", true)——应用于 DegradationBanner，但未应用于三个受影响的标签/工具提示
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/CommunityDialog.kt:137` — CommunityDialog 定义了又一个各站点局部的 escapeHtml（:137-138，转义 &<>" 并应用于 :116 的 <html> 描述标签）——一个完整的转义器，但本身是各站点临时变体，没有共享工具，强化了各站点临时转义的根因（各汇聚点之间没有共享不变式）

### 阻塞点（精确）

- 本机上没有可用于目标控制执行的 OS 强制沙箱
- 未观察到 IntelliJ JBLabel/BasicHTML 渲染是否确实从这些特定标签获取 <img src="http(s)://…">（或 file:…）资源——这是一个决定效果是否超出布局/内容扭曲的平台运行时事实
- 未观察到在此平台版本上 ToolTipManager HTML 渲染是否为 SqlResultPanel 错误工具提示加载 <img> 资源（且原始工具提示仅在错误文本本身以 '<html>' 开头时才按 HTML 渲染）
- 未观察到这些 JLabel 是否在 runIde 沙箱中端到端地被攻击者形状的数据触达（控制台捕获与各汇聚点之间的解析/截断步骤可能改变载荷；预览元信息行的 <html> 包装还要求存在 perf 警告段）

### 解决计划

**本地（有界、非破坏性）：** 在 IDE 测试沙箱中（支持 headless），运行现有 UI harness gradle --tests cn.song.mybatisplus.visualizer.ui.RollbackSqlPanelTest（以及 SqlResultPanel UI harness），并扩展一个最小用例：将 snapshotQueryLabel.text / metaLabel.text 设为 '<html><img src="http://127.0.0.1:<ephemeral-loopback-port>/probe.png"></html>'，并将 SqlResultPanel tooltip 设为以 '<html><img src=…>' 开头的字符串，同时用 loopback 监听器记录请求；断言是否有抓取到达（基线：同一字符串被转义）。还要断言当前行为：让包含 '<img src=…>' 的表片段从 RollbackSqlGenerator snapshotSelectSql（:283）流入 snapshotQueryLabel（:626），以确认未转义的传播

**所有者观察的部署检查：** 由 owner 在 runIde 沙箱中观察检查：捕获一条其解析后的表片段包含 '<img src=http://<loopback-listener>/x.png>' 的日志行，打开 SQL 预览（元信息行）和回滚对话框（快照查询行），并悬停在一个消息以 '<html>' 开头的执行错误上；通过 loopback 监听器观察 IDE 是否发出图片抓取，并确认普通 JBLabel 上不可能激活脚本/链接

---

## 11. Guard 的 INTO 结构检查只覆盖顶层：CTE 或派生表内的 SELECT ... INTO <table> 同时绕过 hasIntoClause 与短语扫描

- **指纹**：`mcp-select-guard.into-clause-non-recursive-cte-gap`
- **边界 / 入口点**：checkSingleSelect — Agent 提供的 selectSql 进入 guard；被 CTE 包裹的语句解析为单个 Select，并通过语句形状门禁（ExplainGate 同样允许 WITH 作为首关键字）。

### 描述

hasIntoClause（McpSelectGuard.kt:216-222，已核实）只在顶层 PlainSelect 以及 SetOperationList 的直接分支上检查 intoTables/intoTempTable；它从不访问 CTE（WithItem）定义内部或派生表/join 子查询中的 PlainSelect 主体。紧随其后的关键字/短语扫描（第 124-126 行）仅兜底文件/变量形式的 INTO（FORBIDDEN_PHRASES 第 64-67 行：into outfile、into dumpfile、into var、into @），并不覆盖普通的 `SELECT ... INTO <table>`（SQL Server/H2 建表形式）。第 215 行的注释断言 CTE 中的 INTO 仍会落在 PlainSelect 上，但被检查的 PlainSelect 只是最外层那一个，因此形如 `WITH c AS (SELECT a INTO bak FROM s) SELECT * FROM c` 的语句，只要 JSqlParser 5.3 将其解析为内层 PlainSelect 持有 intoTables、外层为空（ExplainGate 的首关键字检查允许 WITH），就能同时通过 INTO 检查与短语扫描。嵌套的 FOR UPDATE 不受影响（for update / for share / lock in share mode 短语是在整段文本中做子串扫描的）。

### 所称根因

hasIntoClause 没有递归遍历嵌套查询主体（WithItem 的 select 主体、派生表、join），而文本兜底只枚举了文件/变量形式的 INTO，导致建表形式的 SELECT INTO 在嵌套位置无法被检出。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSelectGuard.kt:73` | checkSingleSelect | Agent 提供的 selectSql 进入 guard；被 CTE 包裹的语句解析为单个 Select，并通过语句形状门禁（ExplainGate 同样允许 WITH 作为首关键字）。 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSelectGuard.kt:216` | hasIntoClause | 只检查顶层 PlainSelect 和 SetOperationList 的直接分支；CTE 主体中的 PlainSelect（内层 INTO 会落在此处）从未被访问。 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSelectGuard.kt:124` | stripLiterals + scanWords/scanPhrases | 掩码后的文本仍包含字面量子串 `into bak`，但普通的 INTO &lt;标识符&gt; 不在 FORBIDDEN_PHRASES（第 64-67 行：只有 into outfile/dumpfile/var/@ 以及锁定短语）中，因此扫描不会拒绝它。 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpDbExecutor.kt:325` | executeQuery(String) on RemoteStatement | 被放行的语句经 EXPLAIN 包裹后在项目数据源上执行；若引擎执行了内嵌在 CTE 中的 SELECT INTO，就会以数据源账号的权限创建一张表。 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSelectGuard.kt:217` — hasIntoClause 只在语句级 PlainSelect 和 SetOperationList 直接分支上检查 `select.intoTables`/`intoTempTable`（通过 filterIsInstance<PlainSelect> 遍历 select.selects）；没有递归进入 WithItem 或子查询主体。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSelectGuard.kt:64` — FORBIDDEN_PHRASES 只涵盖 `for update`、`for share`、`lock in share mode`、`into outfile`、`into dumpfile`、`into var`、`into @`、`for browse` —— 刻意不含普通的 `INTO <table>`（裸 `into` 子串会对普通标识符误报），因此扫描无法为这种形式兜底。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSelectGuard.kt:215` — 注释声称 CTE 中的 INTO 仍会落在 PlainSelect 上，但该例程只检查最外层那一个 —— 所述理由与实现覆盖范围不符。

### 阻塞点（精确）

- 本机没有可供目标受控执行的 OS 级强制沙箱
- 缺少观测：实测 JSqlParser 5.3 对 `WITH c AS (SELECT a INTO b FROM s) SELECT * FROM c`（以及派生表形式）的行为 —— 它是解析为单个 Select、内层 PlainSelect 持有 intoTables 且外层 intoTables 为 null，还是解析失败（后者会重新回到 fail-closed）
- 缺少观测：任一受支持引擎（H2、SQL Server、MySQL、PostgreSQL、DM、KingBase）对 CTE/派生表内 SELECT ... INTO <table> 的实测接受度 —— 若引擎不接受，该结构性缺口没有可达影响

### 解决计划

**本地（有界、非破坏性）：** 在 McpSelectGuardTest（src/test/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSelectGuardTest.kt）中加入精确夹具 `WITH c AS (SELECT a INTO b FROM s) SELECT * FROM c`（以及派生表变体 `(SELECT a INTO b FROM s)`），并通过 SqlAstParser.parseStatements 对 JSqlParser 5.3 的 AST 形状做断言 —— 哪个 PlainSelect 持有 intoTables、guard 的判定是否放行 —— 用 gradle --tests cn.song.mybatisplus.visualizer.mcp.McpSelectGuardTest 运行；若 guard 放行，再针对临时内存 H2（支持 SELECT...INTO）加一层引擎级检查，通过普通 JDBC（而非需要平台类的 McpDbExecutor）执行，以确定嵌套形式是否真的会执行。

**所有者观察的部署检查：** 由 owner 观察验证：用 CTE-INTO 语句对临时 SQL Server 或 H2 数据源运行 sqlv_explain，并在数据库侧观察是否创建了表 `b`/`bak`。

---

## 12. 无 token 的日志行共用一个兜底解析桶，重新引入跨线程 Preparing/Parameters 错配并直达 SQL 执行

- **指纹**：`log.threadrouter.failopen-fallback-cross-thread-param-mixing`
- **边界 / 入口点**：threadTokenOf (invoked per line at SqlLogConsoleBridge.kt:1155 and LogToSqlPanel.kt:490) — 没有 DEBUG/TRACE/INFO/WARN/ERROR 关键字锚点（或附近没有方括号段）的行会得到 null token —— 对裸 '==> Preparing: ...' stdout 输出而言很典型

### 描述

线程分桶——并发参数错配的修复手段——在 threadTokenOf 无法提取 token 时会 fail-open：这类行被路由到单个共享兜底桶，该桶持有单槽位的 pendingSql/pendingParams 状态（LogThreadRouter.kt:45,117）。两个线程各自打印裸 '==> Preparing:'/'==> Parameters:' 行（没有日志框架的级别关键字，MyBatis 打到 stdout 的日志即典型如此）时，会在同一个桶内交错。对确定性交错 A-Preparing(a)、B-Preparing(b)、A-Parameters(1)、B-Parameters(9) 做源码推演：B 的 Preparing 以无参数方式关闭 A 的语句（branch-1 的 closePending），随后 A 的 Parameters 与 B 的 pendingSql 配对，产出条目「b 被用 A 的值还原」（MyBatisLogParser.kt:138 的 branch 4 + closePending:194 + 位置还原器 restore），而 B 的 Parameters 被丢弃（paramWithoutContext）。控制台捕获变体会在 SqlLogPanel 以及导出中暴露这条错配的 restoredSql（SqlLogPanelActions.kt:108 是导出行构建处）；执行边界是 LogToSqlPanel，它把粘贴的日志文本送入同一条 MyBatisLogParser/LogThreadRouter fail-open 路径（LogToSqlPanel.kt:490-491），并把转换后的语句发给 SqlExecutor.execute（LogToSqlPanel.kt:302/323 -> executeSql:340 -> SqlExecutor.execute:359），也就是说 SQL 是用来自另一条语句的参数值执行的（DML 确认与方言门禁仍在，但用户无法从已确认的 SQL 文本中发现错配）。

### 所称根因

LogThreadRouter.bucketForToken 在 threadTokenOf(line) 返回 null 时总是返回共享兜底桶（刻意的 fail-open，以免丢失续行），而兜底桶携带的正是分桶修复本要取代的同一套单槽位配对状态机；由于无 token 的行根本没有身份信息，没有任何守卫会拒绝一条 token 与 pendingSql 的 token 不同的 Parameters 行。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogThreadRouter.kt:171` | threadTokenOf (invoked per line at SqlLogConsoleBridge.kt:1155 and LogToSqlPanel.kt:490) | 没有 DEBUG/TRACE/INFO/WARN/ERROR 关键字锚点（或附近没有方括号段）的行会得到 null token —— 对裸 '==&gt; Preparing: ...' stdout 输出而言很典型 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogThreadRouter.kt:117` | bucketForToken | token == null 且没有 token 桶处于 collecting（兜底桶不在 buckets map 中，因此它的 collecting 状态永远不会路由其他行），使每条无 token 的行都落进同一个共享兜底桶 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt:194` | closePending (invoked from branch 1 at :86 and branch 4 at :141) | 共享桶内交错的 Preparing/Parameters 行关闭条目时，把来自一个线程的 SQL 与来自另一个线程的参数配在一起；LogSqlRestorer.restore 再按位置回填 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogToSqlPanel.kt:359` | executeSql -&gt; SqlExecutor.execute (converted statements chosen at :302/:323) | 错配的还原 SQL 从同一条 fail-open 解析路径转换而来（喂入点 :491），并在用户数据源上执行；控制台捕获变体会在 SqlLogPanel 中显示该错配条目并导出（SqlLogPanelActions.kt:108）。执行门禁（DML 确认、方言门禁）是执行单元自身的控制 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogThreadRouter.kt:112` — 有文档记录的 fail-open：'MyBatis/JdbcTemplate：收集态桶优先（其裸行必在等待态桶内），否则回退共享桶' —— 对无 token 的行无法做身份校验
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogThreadRouter.kt:45` — 由控制台/解析器的所有无 token 行共享的单个兜底桶实例
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt:19` — KDoc 记录了该状态机本要修复的原始缺陷（'并发下条目严重不正确'），确认绕过分桶时错配的影响类别（引文在此行，而非 :17）

### 阻塞点（精确）

- 本机没有可供目标受控执行的 OS 级强制沙箱
- 缺少观测：能演示具体错配条目（线程 B 的 Preparing SQL 被用线程 A 的 Parameters 值还原）到达 LogSqlEntry.restoredSql 的确定性交错夹具

### 解决计划

**本地（有界、非破坏性）：** 纯 JVM：扩展 LogParseConcurrencyTest / LogThreadRouterTest（两者均存在；gradle --tests cn.song.mybatisplus.visualizer.log.LogParseConcurrencyTest），用两条交错的无 token 流通过 MyBatisLogParser.feed(line) 喂入：A：'==> Preparing: SELECT * FROM a WHERE id = ?'，B：'==> Preparing: INSERT INTO b VALUES (?)'，A：'==> Parameters: 1(Long)'，B：'==> Parameters: 9(Long)'。在当前源码上预期的错配形状是：条目 1 = A 的 SQL、无参数（被 B 的 Preparing 关闭），条目 2 = B 的 SQL 被用 A 的值 1(Long) 还原，B 的值被丢弃且 LogParseDiag.paramWithoutContext 递增；断言实际产生的跨线程配对，然后用带 token 的行重跑，确认分桶路径配对正确。

**所有者观察的部署检查：** 由 owner 观察：沙箱 demo 应用中两个线程用 MyBatis 日志打到 stdout（无日志前缀）执行不同语句，开启捕获，然后检查面板条目中是否存在交叉配对的参数值；和/或把同一段交错 stdout 文本粘贴进 Log-to-SQL 面板，观察提供给执行的转换后 SQL。

---

## 13. 自动保存写入器以 CREATE+APPEND 打开历史文件并跟随符号链接，而修订路径强制不跟随，导致可被植入项目外符号链接目标追加写入

- **指纹**：`history.autosave.append-open-symlink-follow`
- **边界 / 入口点**：resolveHistoryDir (write-side configure and read-side HistorySqlLogPanel share it) — resolveHistoryDir 原样返回运维设置的 custom path，或 <projectBasePath>/.idea/sql-visualizer/sql-history —— 默认是项目可写目录

### 描述

合并/修订追加路径谨慎地拒绝不配合的文件：fileKey() 以 NOFOLLOW 读取属性并拒绝非常规文件与符号链接（SqlLogAutoSaver.kt:98-102），修订写入还用 LinkOption.NOFOLLOW_LINKS 加文件锁重新打开（:136-141）。主条目写入路径则没有这些防护：openWriterForCurrent 调用 Files.newBufferedWriter(path, CREATE, APPEND) 时既无 LinkOption.NOFOLLOW_LINKS，也无常规文件检查（:436-451，打开在 :441）。默认 saveDir 位于项目内（.idea/sql-visualizer/sql-history，resolveHistoryDir 在 :626-630；运维设置的 custom path 按原样采用），因此任何能在写入器打开文件之前（configure、IDE 启动、日期占位符变化或 roll —— BufferedWriter 句柄在多次打开之间一直持有）在由 pattern 推导出的名字处植入符号链接的东西，都会把该次打开后的每一次追加——头行、源自不可信控制台文本的、受攻击者影响的 restoredSql、以及 Base64 的 pd 行（writeEntryText，restoredSql 写于 :526）——重定向到项目外的目标，例如 user.home 下的某个文件；合并路径的 fileKey CAS 本会拒绝这种确切状态。

### 所称根因

openWriterForCurrent（SqlLogAutoSaver.kt:436-451）缺少修订路径（fileKey，SqlLogAutoSaver.kt:98-102）已经实现的 NOFOLLOW_LINKS 选项与 isRegularFile/!isSymbolicLink 审查 —— 两条写同一文件族的路径在符号链接遏制上不一致。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogAutoSaver.kt:626` | resolveHistoryDir (write-side configure and read-side HistorySqlLogPanel share it) | resolveHistoryDir 原样返回运维设置的 custom path，或 &lt;projectBasePath&gt;/.idea/sql-visualizer/sql-history —— 默认是项目可写目录 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogAutoSaver.kt:436` | openWriterForCurrent (open at :441) | openWriterForCurrent 调用 Files.newBufferedWriter(file.toPath(), CREATE, APPEND) 时既无 LinkOption.NOFOLLOW_LINKS，也无常规文件/符号链接检查，因此在此次打开之前植入于 pattern 推导名处的符号链接会被跟随 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogAutoSaver.kt:526` | writeEntryText entry write | writeEntryText 把头行文本加上 entry.restoredSql（经解析器源自不可信控制台/日志文本）以及 Base64 的 pd 载荷追加到打开时该路径所指向的任何位置，包括项目目录之外的目标；合并路径的 fileKey CAS 本会拒绝这种确切状态 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogAutoSaver.kt:441` — CREATE+APPEND 打开，无 NOFOLLOW_LINKS，无文件类型审查
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogAutoSaver.kt:101` — fileKey 明确把符号链接视为不可信（'a.isRegularFile && !a.isSymbolicLink'），证明代码库自身的威胁模型已考虑这些目录中被植入的链接
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogAutoSaver.kt:137` — 修订追加用 LinkOption.NOFOLLOW_LINKS 重新打开 —— 该控制手段在仓库内已存在，只是没有应用到条目打开路径

### 阻塞点（精确）

- 本机没有可供目标受控执行的 OS 级强制沙箱
- 缺少观测：在 openWriterForCurrent 运行之前植入于 pattern 推导名处的符号链接（写入器在 configure/roll 各次打开之间持有已打开句柄，因此植入必须早于打开）在目标操作系统上是否真被 CREATE+APPEND 打开所跟随，以及真实写入批次中追加的字节是否落入链接目标
- 缺少观测：被符号链接的 .idea/sql-visualizer/sql-history 条目在 owner 平台上是否能挺过 git checkout（checkout 行为在源码之外）

### 解决计划

**本地（有界、非破坏性）：** 扩展 SqlLogAutoSaverTest 测试装置（测试文件已存在；gradle --tests cn.song.mybatisplus.visualizer.log.SqlLogAutoSaverTest）：创建临时 saveDir，在 configure 保存器之前按默认 pattern 创建指向第二个临时文件的符号链接（对应真实的先植入后打开窗口），用时钟注入 configure 保存器，提交一个条目，断言链接目标是否收到追加的字节；然后对 openWriterForCurrent 应用 NOFOLLOW_LINKS/常规文件审查，并补充回归断言：写入被拒绝并记录日志。

---

## 14. 依赖解析优先使用阿里云镜像而非 Maven Central 且无依赖校验，被替换的镜像字节未经质疑地进入发布的插件

- **指纹**：`gradle-deps-aliyun-mirror-first-no-verification`
- **边界 / 入口点**：dependencyResolutionManagement.repositories — 已核实：maven("https://maven.aliyun.com/repository/public") 声明在第 1 位，早于第 52 行的 mavenCentral；Gradle 使用第一个包含各模块的仓库，因此只要镜像存在该模块，镜像字节就胜出。

### 描述

settings.gradle.kts 在 dependencyResolutionManagement 中先于 mavenCentral（第 52 行）声明 https://maven.aliyun.com/repository/public（第 50 行），而 Gradle 对每个模块都从第一个包含它的仓库解析。仓库中没有 gradle/verification-metadata.xml（已核实：gradle/ 只含 libs.versions.toml、plugin-change-notes.html 和 wrapper/），因此整个构建中任何地方都没有强制任何构件校验和。CI 加固步骤（build-plugin.yml 中五处）只剥离代理属性并重写 Gradle wrapper URL，依赖镜像仍处于激活状态。唯一的运行时依赖 com.github.jsqlparser:jsqlparser:5.3 因此经由这条优先级链解析，其字节被打进 build/distributions/*.zip，作为插件构件上传，并发布到 GitHub Releases 与 Gitee beta 自更新通道（update/update.json downloadUrl）。任何能影响该镜像为这些坐标提供内容的人（镜像运营方被攻破、镜像与 Central 漂移）都能触达被执行的插件代码，而没有任何完整性检查会察觉。

### 所称根因

仓库解析顺序让第三方镜像优先于权威 Maven Central，而构建未定义任何依赖校验（校验和/签名），因此预期控制（Central 作为可信根）是被配置绕过，而非被攻破。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `settings.gradle.kts:50` | dependencyResolutionManagement.repositories | 已核实：maven("https://maven.aliyun.com/repository/public") 声明在第 1 位，早于第 52 行的 mavenCentral；Gradle 使用第一个包含各模块的仓库，因此只要镜像存在该模块，镜像字节就胜出。 |
| propagation | `.github/workflows/build-plugin.yml:91` | build job, '配置 CI 下载环境' step (run block at :93) | 已核实：CI 只剥离 systemProp 代理行（:95-98）并重写 wrapper URL（:100-102），并未移除或降权阿里云仓库，因此门禁/发布构建仍经由同一条镜像优先链解析依赖；wrapper 重写恰好说明该镜像未被同等信任。 |
| sink | `build.gradle.kts:130` | implementation dependency com.github.jsqlparser:jsqlparser:5.3 | 已核实：唯一的 implementation（运行时）依赖经由镜像优先链解析，无校验和验证（无 gradle/verification-metadata.xml），被打进发布的插件 zip（buildPlugin），由 build-plugin.yml 上传（'上传插件 ZIP' 在 :165，路径 build/distributions/*.zip）并发布到 GitHub Release（gh release upload 在 :806 / gh release create 在 :821，参数构造于 :813-814），再向 Gitee beta 自更新通道传播（update/update.json downloadUrl）。 |

### 证据

- `settings.gradle.kts:50` — 已核实：阿里云公共镜像声明在 mavenCentral 之前，:47-49 的注释断言该镜像『与 Central 同源同版本』—— 这是未经验证的假设，也正是争议中的信任交接点。
- `gradle/libs.versions.toml:1` — 已核实：gradle/ 目录只含 libs.versions.toml、plugin-change-notes.html 和 wrapper/ —— 仓库任何地方都不存在 verification-metadata.xml（已通过在工作树上排除 .git 的 find 确认），因此整个构建中任何地方都没有强制构件校验和或签名。
- `.github/workflows/build-plugin.yml:102` — 已核实：CI 把 gradle-wrapper.properties 从 mirrors.cloud.tencent.com 重写为 services.gradle.org/distributions（:94 注释『使用 Gradle 官方下载源』），这是仓库内自认第三方镜像未被同等信任的佐证；对依赖镜像并未施加同等的不信任。

### 阻塞点（精确）

- 本机没有可供目标受控执行的 OS 级强制沙箱（禁止对目标仓库运行 gradle 解析）
- 镜像为这些坐标提供的字节（maven.aliyun.com 与 repo1.maven.org）在源码中不可见；两者当前是否不同，只有 owner 观察到的下载/比对才能定论

### 解决计划

**本地（有界、非破坏性）：** 在批准的沙箱内、禁用外网并配置本地 registry：用 `./gradlew --write-verification-metadata sha256 buildPlugin`（或 `help` 加解析任务）生成 verification metadata，对照权威 Central 构件摘要检查 com.github.jsqlparser:jsqlparser:5.3 记录的 sha256；启用严格校验（带 Central 摘要的 verification-metadata.xml）会让任何镜像偏差确定性地构建失败。

**所有者观察的部署检查：** 由 owner 观察验证，除已配置的两个公共构件 URL 外不做任何在线探测：下载 https://maven.aliyun.com/repository/public/com/github/jsqlparser/jsqlparser/5.3/jsqlparser-5.3.jar 与 https://repo1.maven.org/maven2/com/github/jsqlparser/jsqlparser/5.3/jsqlparser-5.3.jar，比对 sha256；然后提交 pin 住摘要的 gradle/verification-metadata.xml（可选把 mavenCentral 调到前面），使替换 fail-closed。

---

## 15. Gradle wrapper 分发包从腾讯云镜像拉取且无 distributionSha256Sum，本地构建（含 Gitee beta 发布构建）运行未校验的工具链

- **指纹**：`gradle-wrapper-tencent-mirror-no-sha256`
- **边界 / 入口点**：distributionUrl — 已核实：distributionUrl 指向 mirrors.cloud.tencent.com/gradle/gradle-9.7.0-bin.zip；该 10 行文件无 distributionSha256Sum，因此 wrapper 下载的 Gradle 未经验证。

### 描述

gradle/wrapper/gradle-wrapper.properties 把 distributionUrl（第 3 行）钉在 mirrors.cloud.tencent.com/gradle/gradle-9.7.0-bin.zip，且不含 distributionSha256Sum（已跨整个 10 行文件核实），因此 wrapper 接受镜像为 Gradle 9.7.0 分发包提供的任何字节。每个 GitHub Actions 工作流都会在运行开始时把该 URL 重写为官方 services.gradle.org 源（build-plugin.yml:102 为五处之一，nightly-full.yml:99 为六处之一，push-test.yml:74 为两处之一）—— 这是仓库内自认该镜像未被同等信任的佐证 —— 但本地开发者构建，包括维护者产出 Gitee beta 通道发布 zip 的本地 buildPlugin + gitee-release.py 流程（update/update.json downloadUrl 指向 gitee.com releases），运行的是未修改的 wrapper。因此被攻破或漂移的镜像由此触达开发者的构建工具链，并进一步触达 beta 发布构件，而没有任何校验和能检测替换。

### 所称根因

Gradle 分发源是第三方镜像，缺少独立完整性钉定（distributionSha256Sum），因此工具链字节在未施加 CI 侧重写 URL 的机器上没有可信根。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `gradle/wrapper/gradle-wrapper.properties:3` | distributionUrl | 已核实：distributionUrl 指向 mirrors.cloud.tencent.com/gradle/gradle-9.7.0-bin.zip；该 10 行文件无 distributionSha256Sum，因此 wrapper 下载的 Gradle 未经验证。 |
| propagation | `.github/workflows/build-plugin.yml:102` | '配置 CI 下载环境' step (repeated in all workflows) | 已核实：CI 把镜像主机替换为 services.gradle.org/distributions（:102，另有 :243/:305/:379/:463；nightly-full.yml:99 等；push-test.yml:74 等），印证作者自身的信任差异；该重写只存在于托管 runner 上，并未进入为本地使用提交的 wrapper properties。 |
| sink | `build.gradle.kts:1` | any local gradlew invocation (buildPlugin / gitee beta release flow) | 未经验证的分发包被解压到 GRADLE_USER_HOME/wrapper/dists 并执行整个构建；生成的 build/distributions zip 由 dev-support/gitee-release.py 上传到 Gitee（upload_zip 在 :94），并被 update/update.json 引用作为插件自更新通道，因此被替换的工具链字节会传播进发布构件。 |

### 证据

- `gradle/wrapper/gradle-wrapper.properties:3` — 已核实：腾讯云镜像 distributionUrl，带 networkTimeout/retries 调优，但无 distributionSha256Sum（缺失已跨整个文件核实）。
- `.github/workflows/push-test.yml:74` — 已核实：每个自动化工作流都有同样的镜像→官方重写，表明镜像明知不是可信根；本地/开发者机器得不到此类保护。
- `dev-support/gitee-release.py:268` — 已核实：Gitee beta 发布路径的 verify_artifact 用 sha256 把本地 zip 与下载的附件比对，这保护的是构建之后的传输，而非产出本地 zip 的工具链。

### 阻塞点（精确）

- 本机没有可供目标受控执行的 OS 级强制沙箱（禁止对目标仓库运行 ./gradlew）
- mirrors.cloud.tencent.com 当前是否为 Gradle 9.7.0 分发包提供与 services.gradle.org 逐字节相同的文件，在源码中不可见，需要 owner 观察到的下载比对

### 解决计划

**本地（有界、非破坏性）：** 在沙箱内、无外网，给 gradle-wrapper.properties 加上 distributionSha256Sum，并把 distributionUrl 指向一个本地夹具目录，该目录提供经修改的分发包 zip 副本 —— `./gradlew --version` 必须未过校验和检查而失败；然后提供未修改的 zip 并观察成功，证明该控制封堵了缺口。

**所有者观察的部署检查：** 由 owner 观察验证：下载 https://mirrors.cloud.tencent.com/gradle/gradle-9.7.0-bin.zip 与 https://services.gradle.org/distributions/gradle-9.7.0-bin.zip，比对 sha256，然后把 distributionSha256Sum（官方分发包公布的校验和）提交进 gradle/wrapper/gradle-wrapper.properties，使本地与 CI 的 wrapper 下载在替换时 fail-closed。

---

## 16. 市场/兼容性门禁通过首次匹配的缓存遍历解析 verifier-cli jar，当 Gradle 缓存中同时存在多个版本时会静默忽略已固定（pinned）的版本

- **指纹**：`verifier-cli-gate-jar-first-match-ignores-pin`
- **边界 / 入口点**：已固定的 verifier-cli 版本 — 已核实：pin 文件包含 1.411；build-plugin.yml:319-331 和 :397-409 将 verifier-cli-1.411-all.jar 种子化到 GRADLE_USER_HOME/caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli/ci-seed/（逐字核实，从 repo1.maven.org 下载），与 setup-gradle 缓存恢复的任何旧版本目录并存。

### 描述

权威发行门禁（verifyGate 以及 build-plugin.yml 的 gate job 在发布前要求的三个 verifyMarketplaceVerdict 分支）通过在 Gradle 缓存模块目录上执行 walkTopDown().maxDepth(4).filter{verifier-cli-*-all.jar}.firstOrNull() 来选择其 verifier-cli jar，且不与 dev-support/verifier-cli-version.txt（pin: 1.411，已在第 1 行核实）进行任何比较。CI 会将已固定的 jar 种子化到同一模块目录下的 ci-seed 子目录中，而 gradle/actions/setup-gradle 缓存会在多次运行间保留旧版本 jar，因此可能同时存在多个版本，被选中的 jar 取决于未指定的遍历顺序。唯一的守卫 VerifierCliVersionGuardTest 只断言已固定版本在缓存版本之中（允许额外版本），并按其自身文档在 CI 和缓存为空时跳过。仓库自身的注释记录了一起真实事件：该遍历曾选中非预期的旧版 1.409，而预期是 1.410，且这两个版本会产生不同的判定结果。后果：门禁可能用无人固定的 verifier 版本计算其阻止发行的判定，而发行仍绿灯通过。

### 所称根因

门禁从版本 pin（dev-support/verifier-cli-version.txt）到实际执行的 verifier 二进制的信任交接未在解析点强制执行：首次匹配的文件系统遍历取代了 pin，而补偿性测试并未断言门禁实际运行的内容。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `dev-support/verifier-cli-version.txt:1` | 已固定的 verifier-cli 版本 | 已核实：pin 文件包含 1.411；build-plugin.yml:319-331 和 :397-409 将 verifier-cli-1.411-all.jar 种子化到 GRADLE_USER_HOME/caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli/ci-seed/（逐字核实，从 repo1.maven.org 下载），与 setup-gradle 缓存恢复的任何旧版本目录并存。 |
| propagation | `buildSrc/src/main/kotlin/mpv-verification.gradle.kts:204` | verifyGate classpath（市场分支与 refresh 任务另见 :357 和 :481） | 在三个站点均已核实：对 verifier-cli 模块目录的 walkTopDown().maxDepth(4) 选取第一个匹配 verifier-cli-.+-all.jar 的文件——ci-seed 和普通版本目录都匹配该正则；三个解析点均不存在与 pin 的版本比较。 |
| propagation | `src/test/kotlin/cn/song/mybatisplus/visualizer/convention/VerifierCliVersionGuardTest.kt:50` | 断言已固定版本在缓存中 | 已核实：该守卫只断言 `pinned in cached`（集合归属；允许额外版本），并在缓存为空或缺失时直接跳过（:38, :46），因此它从不约束门禁执行哪个 jar。 |
| sink | `buildSrc/src/main/kotlin/mpv-verification.gradle.kts:155` | verifyGate / verifyMarketplaceVerdict* JavaExec 判定 | 已核实：verifyGate 是一个 JavaExec（mainClass com.jetbrains.pluginverifier.PluginVerifierMain，:161），其 classpath 是任意选中的 jar；其退出码判定以及市场 MarketVerdictChecker 基线匹配是 build-plugin.yml 的 gate job（gate: :542，需要 build/verifyplugin/marketplace/verify/test、skipped-also-fails 逻辑）在 publish job（:581）上传发行版之前所要求的——过期 verifier 的判定会授权发行。 |

### 证据

- `buildSrc/src/main/kotlin/mpv-verification.gradle.kts:128` — 已核实（注释跨 :127-129）：'verifyGate 走 walkTopDown 字典序实际取 1.409 故独活'——该遍历已经一次选中了非预期的旧 verifier 版本，同一条注释还确认 1.410 和 1.409 在同一 home 配置上产生不同的通过/失败结果。
- `src/test/kotlin/cn/song/mybatisplus/visualizer/convention/VerifierCliVersionGuardTest.kt:18` — 已核实（:18-19）：守卫自身的覆盖声明——它只在本地缓存存在 verifier jar 处运行，在缓存为空时跳过，且 '守的是「本机全量测试」，不是 CI'——CI 门禁运行完全没有版本断言。
- `settings.gradle.kts:38` — 已核实（从 :47 更正为）：settings.gradle.kts 中 :36-40 的 plugins 块注释逐字记录了被接受的失败模式：未同步的 pin 意味着门禁 '静默用旧版 verifier 跑判定（不报错、门禁照绿）'——这正是引入 pin 本想防止的不变量失效。
- `.github/workflows/build-plugin.yml:326` — 已核实（从 :323 更正为）：种子步骤的 run 块将已固定的 jar 写入遍历稍后扫描的缓存路径内的 $dst = .../verifier-cli/ci-seed，但没有任何东西移除或遮蔽 gradle 缓存恢复的其他版本 jar。

### 阻塞点（精确）

- 本机没有可用于目标控制执行的 OS 级强制沙箱（在多版本缓存中观察 verifyGate 选中哪个 jar 需要针对目标运行 gradle）
- walkTopDown 在 CI runner（windows-latest、NTFS）上对双版本缓存的具体选取顺序无法从源码看出；仓库内注释证明了顺序依赖性，但无法证明当前 runner 的行为

### 解决计划

**本地（有界、非破坏性）：** 在沙箱中：在临时 GRADLE_USER_HOME 的 caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli/{1.410/<hash>/,ci-seed/} 下放置两个名为 verifier-cli-1.410-all.jar 和 verifier-cli-1.411-all.jar 的 jar 文件，运行 `./gradlew verifyGate -PverifyOnly=true -PverifyIdes=smoke`（或重现同一 walkTopDown().firstOrNull() 表达式的最小任务）并记录 classpath 解析到的版本；反转创建顺序重复以证明顺序依赖性。回归用例：断言解析到的 jar 版本在三个解析点（:204, :357, :481）均等于 dev-support/verifier-cli-version.txt。

**所有者观察的部署检查：** 所有者在 CI 环境上观察检查：在恢复了 setup-gradle 缓存的 windows-latest runner 上，在分派发行运行之前于 verify 和 marketplace job 中记录解析到的 verifier-cli jar 路径/版本；或者在解析时强制 pin（将遍历过滤为 verifier-cli-<pin>-all.jar 或在多版本匹配时失败），并确认门禁仍能找到已种子的 jar。

---

## 17. MCP 文本分析工具（sqlv_check_sql、sqlv_rollback_plan、sqlv_reverse_sql、sqlv_perf_advice）解析长度无上限的 agent 提供 SQL，而同级的 render-logs 路径对同一 sink 类别强制执行 20k 上限

- **指纹**：`mcp-sqlv-text-tools-unbounded-sql-input`
- **边界 / 入口点**：sqlv_check_sql — suspend fun sqlv_check_sql(sql: String) 在 checkSql 之前接受原始 agent 字符串且无长度验证（SqlVisualizerRollbackPlanTool.kt:37-40、SqlVisualizerReverseSqlTool.kt:44-48、SqlVisualizerPerfAdviceTool.kt:56-57 形状相同）。

### 描述

四个 MCP 文本分析工具接受来自已连接 AI agent 的原始 sql: String 并同步解析（通过共享 SqlAstParser 的 JSqlParser，路径为 UnboundedWriteDetector.parseStatementsShared / RollbackSqlGenerator.generate / SqlPerfAnalyzer，加上自身无长度上限的 SqlStatementSplitter），运行在 IDE 的 MCP 分派路径上，没有输入长度或解析成本上限（已核实：sqlv_check_sql 位于 SqlVisualizerCheckSqlTool.kt:39，sqlv_rollback_plan 位于 SqlVisualizerRollbackPlanTool.kt:37-40，sqlv_reverse_sql 位于 SqlVisualizerReverseSqlTool.kt:44-48，sqlv_perf_advice 位于 SqlVisualizerPerfAdviceTool.kt:56-57——均未验证长度；只有 isBlank 检查）。为同一 JSqlParser sink 提供输入的控制台/render-logs 解析器强制执行 PluginSettings.DEFAULT_LOG_MAX_PARSE_LENGTH=20000（PluginSettings.kt:832，在 SqlVisualizerRenderLogsTool.kt:82 应用），确立了此 sink 类别预期的有界求值不变量。单次携带数 MB SQL 的 MCP 调用（或重复调用）会在 IDE 进程内驱动无界解析工作和 AST 内存放大，而解析器级 StackOverflowError/OutOfMemoryError 会逃逸工具的 Exception 处理器，因为审计边界故意重新抛出 VirtualMachineError（FatalErrors.isFatal，McpToolsetSupport.kt:147）。

### 所称根因

四个 MCP 工具入口点和共享 SqlAstParser 构建时未加入日志解析路径已有的输入长度门（maxParseLength=20000）；解析成本和 AST 分配恰好在 AI agent 提供 SQL 文本的信任边界上无界，而 rollback 流水线唯一的界限（MAX_STATEMENTS=500、MAX_TOTAL_CHARS=256KiB）适用于输出，而非输入。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerCheckSqlTool.kt:39` | sqlv_check_sql | suspend fun sqlv_check_sql(sql: String) 在 checkSql 之前接受原始 agent 字符串且无长度验证（SqlVisualizerRollbackPlanTool.kt:37-40、SqlVisualizerReverseSqlTool.kt:44-48、SqlVisualizerPerfAdviceTool.kt:56-57 形状相同）。 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/execute/UnboundedWriteDetector.kt:104` | parseStatementsShared | check_sql（detect 在 line 161 调用 parseStatementsShared）和 rollback_plan（RollbackSqlGenerator.generate 位于 RollbackSqlGenerator.kt:126）都将无界的规范化文本馈入共享 JSqlParser 入口且无上限；SqlStatementSplitter（check_sql 的语句计数）同样无长度上限。 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/execute/SqlAstParser.kt:36` | parseStatements | CCJSqlParserUtil.parseStatements 在完整的无界字符串上调用；对输入大小或解析深度没有 FeatureConfiguration 上限（parseSingle 位于 line 44，perf_advice 经由 sql/SqlPerfAnalyzer.kt:122 同样如此）。 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpToolsetSupport.kt:147` | audit 致命透传 | 无界解析工作在 IDE 的 MCP 分派路径上执行；解析器 StackOverflowError/OutOfMemoryError 绕过仅捕获 Exception 的 catch（UnboundedWriteDetector.kt:162、RollbackSqlGenerator.kt:133），并因 FatalErrors.isFatal 将 VirtualMachineError 归类为致命而被重新抛给平台。 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt:832` — DEFAULT_LOG_MAX_PARSE_LENGTH = 20000——同一 sink 类别在日志解析路径上已有的同级上限。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerRenderLogsTool.kt:82` — MCP render-logs 工具应用 maxParseLength = state.logMaxParseLength，证明该不变量对一个 MCP 通道强制执行，但对四个文本分析通道未强制执行。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/sql/rollback/RollbackSqlGenerator.kt:101` — MAX_STATEMENTS=500 和 MAX_TOTAL_CHARS=256*1024（line 104）是仅输出的上限（enforceLimits，约 lines ~594-603）；generate（line 126）中不存在输入上限，它解析完整的清理后文本。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/util/FatalErrors.kt:52` — isFatal 对 VirtualMachineError（StackOverflowError/OutOfMemoryError）返回 true，因此解析器 Errors 被 McpToolsetSupport.kt:146-147 的审计 catch 有意不捕获。

### 阻塞点（精确）

- 本机没有可用于目标控制执行的 OS 级强制沙箱
- 未在 JSqlParser 5.3 上对受限对抗性 SQL fixture 本地观测到解析成本/AST 内存增长曲线（源码分析确立了缺失的上限和同级的 20k 上限，而非实测增长）
- IntelliJ 平台 MCP 分派器对未捕获 VirtualMachineError 的遏制（工具调用失败 vs 线程死亡 vs 进程影响）是平台行为，本仓库不可见

### 解决计划

**本地（有界、非破坏性）：** 在现有纯函数核心上的 Plain-JUnit 测试装置（均可在无平台类型下调用）：扩展 cn.song.mybatisplus.visualizer.mcp.CheckSqlToolTest、RollbackPlanToolTest 和 PerfAdviceToolTest（以及 reverse 通道的 cn.song.mybatisplus.visualizer.reverse.SqlReverseParserTest），使用受限对抗性 fixture（例如 'SELECT 1 OR 1 OR ...' 链和多 MB 语句列表）在 JVM -Xmx/-Xss 限制下运行；测量解析时间和峰值内存增长，与等效的 20k 上限同级输入对比，验证解析器 StackOverflowError 逃逸 checkSql/rollbackPlan（UnboundedWriteDetector.kt:162 / RollbackSqlGenerator.kt:133 的仅 Exception catch）并被 FatalErrors.isFatal 重新抛出，然后断言新的输入长度上限（与 DEFAULT_LOG_MAX_PARSE_LENGTH 对齐）拒绝该 fixture。使用 gradle --tests cn.song.mybatisplus.visualizer.mcp.CheckSqlToolTest（及同级测试类）运行。

---

## 18. SqlReverseParser 递归下降没有嵌套深度守卫：深度括号或嵌套子查询 SQL 经由 sqlv_reverse_sql 触发 StackOverflowError

- **指纹**：`sql-reverse-parser-unbounded-recursion`
- **边界 / 入口点**：sqlv_reverse_sql — sql: String 接受时无长度上限且无嵌套验证；reverseSql 只捕获 SqlReverseException（lines 55-65）。

### 描述

SqlReverseParser 是一个手写递归下降解析器，其括号条件分支经由循环 parseConditionInternal（line 856）-> parseGroup（line 962）-> parseConditionList（line 782）-> parseAndSegment（line 801）-> parseCondition（line 842）-> parseConditionInternal 递归，每层嵌套消耗约 4-5 个栈帧，另有第二个递归循环 parseSubqueryTable（line 679）-> parseSubqueryStructure（line 707）-> parse（line 34）对应每个 FROM 子查询层级。两个循环均无深度计数器（经 grep 核实：文件中唯一的 'depth' 变量是括号平衡计数器，不存在递归深度限制），入口工具 sqlv_reverse_sql 接受未设上限的 SQL 字符串且只捕获 SqlReverseException（SqlVisualizerReverseSqlTool.kt:57），因此紧凑的对抗性输入（数千字节的 '('）会驱动 StackOverflowError，MCP 审计边界将其作为 VirtualMachineError（FatalErrors.isFatal）重新抛给平台分派器。与 JSqlParser 不同，这甚至不需要大输入：每字节的嵌套深度是最大的。

### 所称根因

递归下降解析器编写时没有递归深度守卫或输入上限；每层括号的帧成本意味着栈深度随攻击者选择的嵌套线性增长，而没有任何层（工具、解析器或审计）限制该深度。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerReverseSqlTool.kt:44` | sqlv_reverse_sql | sql: String 接受时无长度上限且无嵌套验证；reverseSql 只捕获 SqlReverseException（lines 55-65）。 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/reverse/SqlReverseParser.kt:34` | parse | 完整输入被分词并分派；parseConditionInternal（lines 856-866）的括号条件分支进入递归 group 路径；嵌套 FROM 子查询进入 parseSubqueryTable（line 679）-&gt; parseSubqueryStructure（line 707）-&gt; parse（line 34）。 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/reverse/SqlReverseParser.kt:962` | parseGroup | parseGroup 调用 parseConditionList（line 782，调用在 963）-&gt; parseAndSegment（line 801）-&gt; parseCondition（line 842）-&gt; parseConditionInternal（line 856），文件中任何地方都没有深度参数或计数器。 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpToolsetSupport.kt:147` | audit 致命透传 | StackOverflowError（VirtualMachineError）绕过仅捕获 SqlReverseException 的工具 catch，Throwable 审计 catch 将其重新抛给平台 MCP 分派器。 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/reverse/SqlReverseParser.kt:962` — parseGroup 递归进入 parseConditionList 且无深度守卫；grep 确认文件中不存在递归深度/限制计数器（唯一的 depth 变量是 token 级括号平衡计数器）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerReverseSqlTool.kt:57` — 仅 catch (e: SqlReverseException)——JVM Errors 从工具中传播出去。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/util/FatalErrors.kt:52` — VirtualMachineError 被归类为致命并被审计边界重新抛出（McpToolsetSupport.kt:146-147）。

### 阻塞点（精确）

- 本机没有可用于目标控制执行的 OS 级强制沙箱
- 未在实际运行解析器的 JVM 上对受限 fixture 本地观测到 StackOverflowError 阈值（嵌套深度 x 每层帧数 vs JVM 默认栈）
- IntelliJ 平台 MCP 分派器对重新抛出的 VirtualMachineError 的遏制是本仓库不可见的平台行为

### 解决计划

**本地（有界、非破坏性）：** 在 cn.song.mybatisplus.visualizer.reverse.SqlReverseParserTest 和 cn.song.mybatisplus.visualizer.mcp.ReverseSqlToolTest（两者都存在，是基于纯核心的 plain JUnit）中添加用例：在受限 -Xss 下对 'SELECT * FROM t WHERE ' + '('*20000 + 'a=1' + ')'*20000（以及嵌套 FROM 子查询变体）调用 reverseSql；若无深度守卫，预期 StackOverflowError 逃逸 reverseSql，然后断言在添加深度守卫/输入上限后接受并降级或返回结构化错误。使用 gradle --tests cn.song.mybatisplus.visualizer.reverse.SqlReverseParserTest 运行。

---

## 19. OgnlEvaluator.parse 泄漏 NumberFormatException（整数面值 > Long.MAX_VALUE）和 StackOverflowError（深度嵌套）出其 ParseResult/Err 契约，未捕获地到达 MCP mapper-check 和 UI 预览

- **指纹**：`src/main/kotlin/cn/song/mybatisplus/visualizer/xml/Ognl.kt@parse-exception-confinement`
- **边界 / 入口点**：entrypoint — 较低信任的 mapper XML 文本（AI agent 提供的片段或项目文件）进入工具处理器内的 XmlMapperParser().parse，后者为每个语句构建 XmlPreviewModel.from 并随后 renderSql

### 描述

用于 <if test>、<bind value>、${} 和 foreach collection 表达式的自定义 OGNL 求值器将所有解析失败降级为 ParseResult.Err，所有求值期失败降级为 EvalResult.Err——但 parseUncached（Ognl.kt:133-137）只捕获 OgnlParseException，而 evaluate(text) 在 Throwable 保护的 AST 求值器之外调用 parse()（Ognl.kt:153-157；:152 的 KDoc 将其降级承诺限定在求值期，而 parse() 本身将 ParseResult.Err 呈现为其唯一的面向调用者的失败模式，每个基于 parse 构建的辅助函数都假设它不会抛出）。一个 20+ 位的普通整数面值会使 Lexer.number() 从 raw.toLong() 抛出 java.lang.NumberFormatException（Ognl.kt:686），而深度嵌套的括号会在递归下降解析器中耗尽栈（Ognl.kt:749,771-995），因此未捕获的 NumberFormatException/StackOverflowError 从 XmlPreviewModel.from（XmlPreviewModel.kt:263->297 collect->rootPaths，模型构建期间 :333 classifyDetail；XmlPreviewModel.kt 不含 try/catch）和 XmlPreviewModel.renderSql（经由 TextSqlNode.kt:44、IfSqlNode.kt:69、ForeachSqlNode.kt:61；XmlSqlRenderer.kt 不含 try/catch）传播出去。Mapper XML 是较低信任的输入（项目文件、经由 MCP mapper-check 工具的 AI agent 提供的片段），具体 sink 是 SqlVisualizerMapperCheckTool，它在调用 XmlPreviewModel.from 和 model.renderSql()（SqlVisualizerMapperCheckTool.kt:116-117）之前只捕获 XmlParseException（SqlVisualizerMapperCheckTool.kt:93-99）；审计包装器只重新抛出意外异常（McpToolsetSupport.kt:146-149），ui/XmlPreviewService.toResult（XmlPreviewService.kt:257）同样无保护。预期最小影响：agent 或 mapper 编写的包含 <bind name="x" value="99999999999999999999"/> 或 <if test="ts > 99999999999999999999"/> 的语句会使工具/预览路径抛出异常，而不是返回带错误行的降级输出。

### 所称根因

OgnlEvaluator.parse 中的异常隔离缺口：Lexer.number()（src/main/kotlin/cn/song/mybatisplus/visualizer/xml/Ognl.kt:686）执行 raw.toLong() 时未捕获超长面值的 NumberFormatException，parseUncached/parse/evaluate(text)（Ognl.kt:133-137,124-131,153-157）只捕获 OgnlParseException，因此词法器的运行时异常（以及 Parser 无界递归下降的 StackOverflowError，Ognl.kt:749,771-995）绕过 Err 降级契约，逃逸到假设 parse()/evaluate() 从不抛出的调用者。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerMapperCheckTool.kt:94` | entrypoint | 较低信任的 mapper XML 文本（AI agent 提供的片段或项目文件）进入工具处理器内的 XmlMapperParser().parse，后者为每个语句构建 XmlPreviewModel.from 并随后 renderSql |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/xml/Ognl.kt:686` | propagation | Lexer.number() 在 test/bind/${}/collection 表达式内的 20+ 位整数面值（例如 99999999999999999999）上从 raw.toLong() 抛出未捕获的 NumberFormatException；StackOverflowError 同样从 Ognl.kt:771-995 处无界递归下降（parseOr..parsePrimary）为深度嵌套括号逃逸 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/xml/Ognl.kt:133` | propagation | parseUncached 只捕获 OgnlParseException；parse()（Ognl.kt:124-131）未增加隔离；evaluate(text,...)（Ognl.kt:153-157）在 Throwable 保护的 AST 求值器（Ognl.kt:160-165）之外调用 parse()。:152 的 KDoc 将其 Err 承诺限定在求值期，但 parse() 的面向调用者契约（所有失败返回 ParseResult.Err）是辅助函数 rootPaths（:282-288）、rootVariables（:270-276）、classifyDetail（:368-376）、staticFieldRefs 和 unsupportedPropertyPaths 所依赖的 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/xml/XmlPreviewModel.kt:297` | propagation | XmlPreviewModel.from（line 247）-&gt; collect（263）-&gt; collectExpressionPaths 在模型构建期间于 297（以及 :333 的 classifyDetail）调用 OgnlEvaluator.rootPaths(expression)，因此异常在任何 render 层处理之前触发；renderSql 经由 TextSqlNode.kt:44 / IfSqlNode.kt:69 / ForeachSqlNode.kt:61 到达同一 parse（XmlPreviewModel.kt 和 XmlSqlRenderer.kt 不含 try/catch） |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerMapperCheckTool.kt:117` | sink | model.renderSql()（在 :116 的 XmlPreviewModel.from 之后）在 MCP 工具内执行，其处理器只捕获 XmlParseException（SqlVisualizerMapperCheckTool.kt:93-99），因此 NumberFormatException/StackOverflowError 逃逸工具体；审计包装器重新抛出意外异常（McpToolsetSupport.kt:146-149），UI 路径 ui/XmlPreviewService.kt:257（toResult -&gt; renderSql）同样无保护 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/xml/Ognl.kt:686` — Lexer.number(): 'val value = if (raw.contains('.')) raw.toDouble() else raw.toLong()'——toLong() 对超过 Long.MAX_VALUE 的面值（20+ 位）抛出 NumberFormatException；词法器中任何地方都没有 catch
- `src/main/kotlin/cn/song/mybatisplus/visualizer/xml/Ognl.kt:135` — parseUncached: 'catch (e: OgnlParseException)' 是词法器与 parse()/evaluate(text,...) 的每个调用者之间唯一的 catch
- `src/main/kotlin/cn/song/mybatisplus/visualizer/xml/Ognl.kt:152` — KDoc '求值期异常安全降级为 EvalResult.Err'——Ognl.kt:160-165 的 Throwable 守卫只包装 AST 求值；evaluate(text) 在 :153-157 执行的 parse 步骤（以及 parse() 的直接调用者 rootPaths :282-288 / classifyDetail :368-376）在任何隔离之外
- `src/main/kotlin/cn/song/mybatisplus/visualizer/xml/XmlPreviewModel.kt:297` — XmlPreviewModel.from -> collect（line 263）-> 297 处的 OgnlEvaluator.rootPaths(expression)（:333 处还有 classifyDetail）在模型构建期间解析每个 ${}/bind/test 表达式——mapper 编写文本的第一个无保护入口点；文件不含 try/catch
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerMapperCheckTool.kt:95` — 工具处理器只捕获 XmlParseException（'catch (e: ... XmlParseException)'），同时在 :116 调用 XmlPreviewModel.from、在 :117 调用 model.renderSql()——OGNL 层的运行时异常没有隔离
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpToolsetSupport.kt:146` — 审计包装器捕获 Throwable 仅为记录审计并重新抛出：'if (FatalErrors.isFatal(e)) throw e ... throw gate.onFailure(e)'（KDoc line 106: '非预期异常不在此吞（平台记 Internal error）'）——工具体的意外异常从工具传播出去；只有平台 MCP shell 的处理在其之外
- `src/test/kotlin/cn/song/mybatisplus/visualizer/xml/OgnlEvaluatorTest.kt:218` — 现有测试 '解析失败返回Err_非法语法与未闭合' 只固定语法错误的 Err 降级；没有用例覆盖超长数值面值或病态嵌套，因此该缺口未经测试

### 阻塞点（精确）

- 本机没有可用于目标控制执行的 OS 级强制沙箱
- 缺失观察：对包含 <bind name="x" value="99999999999999999999"/> 的语句进行 OgnlEvaluator.parse("99999999999999999999") / OgnlEvaluator.evaluate 的受限本地运行，以观察实际抛出的 NumberFormatException 与预期的 ParseResult.Err/EvalResult.Err（源码审查确立了逃逸路径但未确立观测到的运行时结果）
- 缺失观察：对深度嵌套括号 test 表达式进行 OgnlEvaluator.parse 的受限本地运行，以观察 StackOverflowError 与优雅 Err，以及目标 JVM 上发生时的递归深度
- 缺失观察：McpToolsetSupport.audit 之上的平台 MCP shell（McpToolsetDynamicRegistrar 生成的工具 shell / IntelliJ MCP server）是否将来自 SqlVisualizerMapperCheckTool 的未捕获 Throwable 转换为结构化工具错误或将其作为工具失败传播——对本单元的源码审查确立了异常既逃逸 XmlParseException 处理器（SqlVisualizerMapperCheckTool.kt:95）又逃逸审计包装器的重新抛出（McpToolsetSupport.kt:146-149），但 registrar 在本单元审查路径之外（由 mcp hunter 拥有）

### 解决计划

**本地（有界、非破坏性）：** 扩展 src/test/kotlin/cn/song/mybatisplus/visualizer/xml/OgnlEvaluatorTest.kt：(1) 断言 OgnlEvaluator.parse("99999999999999999999") 返回 ParseResult.Err 且不抛出；(2) 断言 OgnlEvaluator.evaluate("99999999999999999999 > 1", emptyMap()) 返回 EvalResult.Err；(3) 在 XmlPreviewModelTest/XmlScriptBuilderTest 中的 render 层用例，解析包含 <bind name="x" value="99999999999999999999"/> 和 <if test="ts > 99999999999999999999"> 的 mapper，断言 XmlPreviewModel.from/renderSql 产生降级输出加 errors 行而非抛出；(4) 深度用例馈入深度嵌套括号表达式（例如 50,000 层嵌套括号）并断言有界失败而非 StackOverflowError。使用 gradle --tests cn.song.mybatisplus.visualizer.xml.OgnlEvaluatorTest --tests cn.song.mybatisplus.visualizer.xml.XmlPreviewModelTest 运行。如果断言以抛出的异常失败，则按其观测到的可用性/健壮性影响确认候选。

**所有者观察的部署检查：** 所有者在 IDE 沙箱中观察检查：对包含 <bind name="x" value="99999999999999999999"/> 的 mapper XML 调用 sqlv_mapper_check MCP 工具（或打开预览面板），确认工具返回结构化错误结果还是在 IDE 错误日志中出现未处理异常。

---

## 20. MyBatis Parameters 文本被二次复杂度正则解析且没有现有的 maxParseLength 上限，冻结控制台解析线程

- **指纹**：`log.mybatis.parseparams.unbounded-quadratic-regex`
- **边界 / 入口点**：EpLineFilter.applyFilter / SqlLogConsoleBridge.handleConsoleLine (SqlLogConsoleBridge.kt:391) — 平台 ConsoleView 过滤器将完整控制台行文本无长度上限地交付给解析流水线（已核实：applyFilter、handleConsoleLine 或 processConsoleLine 在解析器之前没有截断）

### 描述

控制台过滤器将整条控制台行无界地交给解析器。MyBatisLogParser 将 Preparing SQL 主体截断到 maxParseLength（line 87），但 Parameters: 余下部分未截断就传给 parseParams（分支 4 line 131/138，以及 line 125 的仅前缀 Parameters 续行分支）。PARAM_REGEX 的 (?:\A|,\s*)(.+?)\(...\)(?=,\s*|\z) 惰性扩展配合逗号交替起始位置，使得对无括号的逗号密集文本 findAll 在行长度上是二次的（在达到 parseParams:307 的二次成本回退 text.split(',') 之前，每个逗号起始位置都惰性重新扫描到输入末尾寻找 '('），因此来自任何托管进程的单条数百 KB 多 '==> Parameters:' 控制台行会在控制台过滤器/EP 线程上驱动超线性 CPU，使捕获和 UI 响应停滞。源码检查确认在 applyFilter（SqlConsoleBridgeFilterProvider.kt:83）和 parseParams 之间没有任何截断——processConsoleLine 和 handleConsoleLine 传递原始行（SqlLogConsoleBridge.kt:1094-1171）。

### 所称根因

现有的每行上限（maxParseLength，有意应用于 MyBatisLogParser.kt:87 的 pendingSql 和所有其他解析器的 SQL 主体）从未在 parseParams 之前应用于 Parameters 文本，且 PARAM_REGEX 在逗号密集无括号输入上具有二次最坏情况回溯。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlConsoleBridgeFilterProvider.kt:83` | EpLineFilter.applyFilter / SqlLogConsoleBridge.handleConsoleLine (SqlLogConsoleBridge.kt:391) | 平台 ConsoleView 过滤器将完整控制台行文本无长度上限地交付给解析流水线（已核实：applyFilter、handleConsoleLine 或 processConsoleLine 在解析器之前没有截断） |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt:131` | feedInto 分支 4 | paramsText = extractAfterKeyword(line, paramPrefix) - 余下部分未用 maxParseLength 截断，与 line 87 的 SQL 主体不同 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt:138` | parseParams(paramsText) | 无界的攻击者影响文本进入正则参数分割器（同样经由 line 125 的分支 3 续行 parseParams(line.trim())） |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt:321` | PARAM_REGEX.findAll（parseParams 循环位于 :298） | 二次回溯：无括号逗号密集文本上 O(commas) 起始位置 x 每位置 O(n) 惰性扩展；控制台输出/EDT 邻近过滤器线程上的 CPU 尖峰 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt:87` — 控制存在并应用于 SQL 主体：bucket.pendingSql = sql.take(maxParseLength)
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt:131` — 同一控制在参数文本路径上缺失：extractAfterKeyword 结果未经 take() 就到 parseParams（line 138）
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt:321` — PARAM_REGEX 交替 '(?:\A|,\s*)' 允许每个逗号作为匹配起点；惰性 '(.+?)' 在每个起点重新扫描到字符串末尾，然后 :307 的线性 split(',') 回退才能运行

### 阻塞点（精确）

- 本机没有可用于目标控制执行的 OS 级强制沙箱
- 缺失观察：实测的挂钟时间增长曲线（1k/10k/100k 字符逗号密集 Parameters 行），证明超线性解析成本和 IDE 宿主中导致的控制台线程停滞

### 解决计划

**本地（有界、非破坏性）：** 纯 JVM 单元测试装置，无需 IDE：扩展现有 MyBatisLogParser 测试集（MyBatisLogParserTest.kt / LogParseConcurrencyTest.kt 都存在，通过 gradle --tests cn.song.mybatisplus.visualizer.log.MyBatisLogParserTest 运行）。对 n = 1_000 / 10_000 / 100_000 馈入 MyBatisLogParser(maxParseLength=20000).feed("==> Parameters: " + "a,".repeat(n)) 并计时 feed()；预期约 10x/100x 超线性增长确认上限失效；然后断言修复方案 paramsText.take(maxParseLength) 在 parseParams 之前恢复线性时间。

**所有者观察的部署检查：** 所有者观察：在沙箱化 runIde 中，从演示应用在启用捕获的情况下打印单条 200k 字符逗号密集的 '==> Parameters:' 行，观察插件诊断中的控制台输出线程停滞/捕获滞后（consoleLinesSeen vs entries produced）。

---

## 21. ExecutionTimeParser 的 trailing-ms 正则表达式对无界控制台长行中的连续空白段产生平方级回溯

- **指纹**：`log.executiontimeparser.trailing-ms-whitespace-backtracking`
- **边界 / 入口点**：finishEntries（由 processConsoleLine :1170 与嗅探解封路径 :1114 调用） — lineDuration = ExecutionTimeParser.parse(line, timePrefix) 接收完整的原始控制台行（仅针对产出条目的行，但行本身无界）

### 描述

finishEntries 对每个产出条目的行的完整、未截断的控制台行运行 ExecutionTimeParser.parse（既包括 SqlLogConsoleBridge.kt:1170 处的正常 processConsoleLine 出口，也包括 :1114 处的嗅探解封出口）。TRAILING_MS_REGEX = [\]\s]\s*(\d+)\s*ms\s*$（MULTILINE，ExecutionTimeParser.kt:36）以一个匹配每个空格的字符类开头，因此在空白密集的行上，引擎会在每个空白索引处重试，先贪婪消费再回溯整个剩余空白段：每个空白段为 O(段长^2)，即与行长成平方关系。源码检查确认，前五个正则的 leading token 都是字面量式的（elapsed/consumed、TimeCount、time、executeMillis、ExecuteTime），在每个不匹配位置都是 O(1) 失败；只有 TRAILING_MS_REGEX 的 leading [\]\s] 让每个空格都成为一次完整尝试的位置。宿主进程的一条约 500KB 填充行（例如 '==> Parameters: 1(Long)' 后跟数百 KB 空格）会让解析线程在每行上付出超线性数量的引擎步骤。

### 所称根因

一个处于热路径的时长正则，其 leading token 是未锚定的、匹配空白的字符类，被应用于完整的无界控制台行；与解析器中受 maxParseLength 限制的 SQL 主体不同，传给 ExecutionTimeParser.parse 的行从未被截断。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogConsoleBridge.kt:1186` | finishEntries（由 processConsoleLine :1170 与嗅探解封路径 :1114 调用） | lineDuration = ExecutionTimeParser.parse(line, timePrefix) 接收完整的原始控制台行（仅针对产出条目的行，但行本身无界） |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/ExecutionTimeParser.kt:78` | parse 第 6 步 | TRAILING_MS_REGEX.find(text) —— 前五个正则的 leading token 是字面量，每个位置 O(1) 失败；只有此模式的 leading [\]\s] 在每个空白索引处都匹配 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/ExecutionTimeParser.kt:36` | TRAILING_MS_REGEX 定义 | 贪婪的 \s* 后跟 (\d+) 会在每个 start index 处对每个空白段逐字符回溯：在空白密集行上为 O(段长^2) 引擎步骤 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/ExecutionTimeParser.kt:36` — leading [\]\s] 字符类让每个空格都成为匹配尝试位置；\s*(\d+) 迫使每次尝试都回溯整个空白段
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogConsoleBridge.kt:1186` — 完整的未截断行就是解析输入；此调用之前没有任何 maxParseLength 截断
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt:87` — 对照：同一管线会把 SQL 主体截断到 maxParseLength，说明预期的每行上限并未扩展到这条路径

### 阻塞点（精确）

- 本机没有可用于目标受控执行的、由操作系统强制的沙箱
- 缺少观测：在 1k/10k/100k 字符的空白段夹具上测得的 CPU 时间增长，用以证明平方曲线以及 IDE 解析路径中可感知的逐行停顿

### 解决计划

**本地（有界、非破坏性）：** 纯 JVM：扩展 ExecutionTimeParserTest（gradle --tests cn.song.mybatisplus.visualizer.log.ExecutionTimeParserTest），加入增长夹具——ExecutionTimeParser.parse("==> Parameters: 1(Long)" + " ".repeat(n))，n 取 1_000 / 10_000 / 100_000，断言修复后 wall-clock 扩展不再是超线性的（预先截断该行或锚定模式）；未修复的构建从 10k 到 100k 应显示约 100 倍增长。

**所有者观察的部署检查：** 由所有者观察：在启用捕获的沙箱化 runIde 中，让演示应用打印一条数百 KB 的填充 Parameters 行，并在控制台输出线程上观察捕获延迟诊断（consoleLinesSeen 的推进与时间的关系）。

---

## 22. MyBatis/Hibernate 解析器的线程桶在 pending 期间永不被淘汰，随每个控制台累积无界保留的 SQL

- **指纹**：`log.threadrouter.unbounded-nonidle-bucket-retention`
- **边界 / 入口点**：feedInto 分支 1 — Preparing 行设置 bucket.pendingSql（<= 20000 字符）与 continuing=true；HibernateLogParser.feedIdle 同样把 collecting 置为 true（HibernateLogParser.kt:135-141，collecting=true 在 :136）

### 描述

LogThreadRouter 将桶数量上限设为 64（DEFAULT_MAX_BUCKETS），但 evictIdle 只移除 isIdle 的桶。一个 MyBatisLogParser 桶如果收到过 Preparing: 或裸 SQL 行、却没有收尾 Parameters 行，就会持有 pendingSql（最多 maxParseLength=20000 字符，外加未截断的 pendingLogLine 原始行）或 prefixMatched/paramPrefixMatched 等待状态，因而永远不会空闲（isIdle 位于 MyBatisLogParser.kt:65，要求 pendingSql == null && !prefixMatched && !paramPrefixMatched）。HibernateLogParser.feedIdle 同样把 collecting 置为 true。与 JdbcTemplate 和 ShardingSphere 解析器不同，MyBatisLogParser 和 HibernateLogParser 没有 flushSettled：全源码 grep 确认 flushSettled/settledBuckets 只针对 jdbcParser 和 shardingParser 调用（SqlLogConsoleBridge.kt:1165-1166 的逐行路径，以及 :453/:467 的 EDT 周期性 flushSettledEntries），且捕获路径从不调用 ctx.myBatisParser.closePending()/ctx.hibernateParser.closePending()（closePending() 的调用方只有 LogToSqlPanel 和 SqlVisualizerRenderLogsTool，且各自作用于自己的解析器实例）。因此，每个打印过一条未闭合语句的唯一线程 token 都会在控制台的生命周期内保留其 SQL 文本：在持续的日志量与不断变化的线程名之下，保留内存会随打印的 SQL 总量线性增长，而 IDE 控制台循环缓冲区会把同样的文本丢弃。

### 所称根因

淘汰资格（isIdle）被定义为「没有 pending 语句」，而本可把 pending 桶转为 idle 的 settled-flush 机制只存在于 JdbcTemplate 和 ShardingSphere 解析器；MyBatis/Hibernate 的 pending 桶永远没有资格被 64 桶上限淘汰。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt:87` | feedInto 分支 1 | Preparing 行设置 bucket.pendingSql（&lt;= 20000 字符）与 continuing=true；HibernateLogParser.feedIdle 同样把 collecting 置为 true（HibernateLogParser.kt:135-141，collecting=true 在 :136） |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogThreadRouter.kt:96` | bucketForToken | 每个唯一线程 token 创建一个桶；当 size &gt;= maxBuckets 时运行 evictIdle |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogThreadRouter.kt:134` | evictIdle | 只有 isIdle 的桶可被移除；isIdle 要求 pendingSql == null（MyBatisLogParser.kt:65），因此每个 pending 桶都是永久的，桶数量会无界地增长并超过 64 上限 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogConsoleBridge.kt:1165` | processConsoleLine / flushSettledEntries (:453/:467) | settle flush 只覆盖 jdbc 与 sharding 解析器；MyBatis/Hibernate 的 pending 状态永远不会被老化淘汰，按控制台保留直到项目关闭 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogThreadRouter.kt:32` — evictIdle 按设计跳过非空闲桶——所引用的理由「淘汰不作为即丢状态，故只淘汰空闲桶」是该行 isIdle 参数的 KDoc（实现它的 evictIdle 主体位于 :134-144）
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogConsoleBridge.kt:1165` — 只调用了 ctx.jdbcParser.flushSettled() 和 ctx.shardingParser.flushSettled()（EDT 周期性 flushSettledEntries :453/:467 同样如此）；捕获路径中任何地方都没有 MyBatis/Hibernate 的对应调用（grep 验证）
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt:65` — isIdle = pendingSql == null && !prefixMatched && !paramPrefixMatched —— 持有 pendingSql 的桶永远无法被淘汰

### 阻塞点（精确）

- 本机没有可用于目标受控执行的、由操作系统强制的沙箱
- 缺少观测：在 N 个唯一 token 的未闭合 Preparing 行（例如 N = 1k/10k/100k）的有界夹具上测得的保留桶数量与堆增长，用以证明超出 64 桶上限的无界保留

### 解决计划

**本地（有界、非破坏性）：** 纯 JVM：扩展 LogThreadRouterTest / LogParseConcurrencyTest（两者都存在；gradle --tests cn.song.mybatisplus.visualizer.log.LogThreadRouterTest）——向 MyBatisLogParser 喂入 N 行 '2026-01-01 [thread-i] DEBUG ==> Preparing: <20k-char SQL>'，使用互不相同的 thread-i，然后断言 router.bucketCount() 保持有界且桶变为可淘汰；在当前源码上，bucketCount() 会增长到 N，每个桶最多保留 20k 字符的 pendingSql 外加原始 pendingLogLine。

**所有者观察的部署检查：** 由所有者观察：在沙箱化 runIde 中运行一个每请求使用线程池、发出无参数语句的演示应用；在控制台循环缓冲区已裁剪掉同样输出之后，观察可归因于解析器桶的 IDE 堆保留。

---

## 23. 被植入或超大的 .sql 历史文件在历史读取与 MCP 历史搜索期间于 IDE 内存中无界缓冲非 revision 行与块

- **指纹**：`history.reader.unbounded-line-buffering`
- **边界 / 入口点**：scanHistoryFiles（对每个列出的文件调用 countInFile） — scanHistoryFiles 列出 resolveHistoryDir 输出中的每个 *.sql（此处的扩展名大小写敏感；HistorySearchService.kt:137 中大小写不敏感）——默认位于项目内——没有任何大小或身份检查；showFileContent:526 随后在后台任务上调用 readFromFile

### 描述

SqlLogFileReader 逐行流式读取文件以避免整文件缓冲（T-SqlHistoryFull），但 boundedLines 只折叠以 revision 前缀 '-- pd: o147-revision: ' 开头的行（折叠条件在 SqlLogFileReader.kt:115）。其余每一行——SQL 主体、头部，或主 '-- pd: <base64> --' 负载行——都在没有任何长度或总大小上限的情况下累积，而且 EntryBlockScanner 还会把一个块的每一行主体保留到下一个头部（bodyLines.add 在 :297，仅在头部行之后）。设计中提到的 100MB 上限只是写入侧的滚动阈值（SqlLogAutoSaver.kt:31 的 rollThresholdBytes）；读取器接受任意大小的文件——readFromFile/forEachEntry/countInFile 没有文件大小闸门（已验证）。任何对项目目录有写权限的对象（例如提交 .idea/sql-visualizer/sql-history/*.sql 的仓库，或任何本地进程）都能植入这样的文件；它会在用户打开历史标签页时被解析（后台任务，HistorySqlLogPanel.kt:526），并经由 MCP 工具 sqlv_history_search，在 AI 代理调用 HistorySearchService.search 时被解析——后者的 MAX_SCAN_ENTRIES=200_000 限制条目数量但不限制每条字节数（HistorySearchService.kt:60）。

### 所称根因

boundedLines（SqlLogFileReader.kt:99-118）的长度折叠只应用于 REVISION_PREFIX 行；非 revision 路径上没有逐行、逐块或逐文件的字节上限（EntryBlockScanner.bodyLines 累积在 SqlLogFileReader.kt:297、:336 的全量复制 joinToString、:322-332 的 Base64 解码 + JSON 解析全量复制），且 readFromFile/forEachEntry/countInFile 中没有文件大小闸门。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/HistorySqlLogPanel.kt:633` | scanHistoryFiles（对每个列出的文件调用 countInFile） | scanHistoryFiles 列出 resolveHistoryDir 输出中的每个 *.sql（此处的扩展名大小写敏感；HistorySearchService.kt:137 中大小写不敏感）——默认位于项目内——没有任何大小或身份检查；showFileContent:526 随后在后台任务上调用 readFromFile |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogFileReader.kt:99` | boundedLines | boundedLines 把每个非 revision 前缀行累积到没有长度上限的 StringBuilder 中；只有长于 MAX_REVISION_PAYLOAD+150 的 '-- pd: o147-revision: ' 行会被折叠（条件在 :115） |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogFileReader.kt:297` | EntryBlockScanner.offer | EntryBlockScanner.offer 把头部之后的每个非头部行追加到 bodyLines，因此一个后面没有头部的块会保留文件的整个剩余部分；parseBlockLines:336 连接第二份全量复制，而 :322-332 的 pd 行还会做 Base64 解码和 JSON 解析的全量复制 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/HistorySearchService.kt:152` | MCP 触发的读取（第二个 sink：HistorySqlLogPanel.kt:526） | forEachEntry 为 AI 代理的历史搜索工具消费同一个无界读取器；尽管有 200000 条扫描上限（限制条目数而非字节数），单条被植入的超大行/条目仍会耗尽或拖住 IDE 进程堆 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogFileReader.kt:115` — 超限折叠以 'revision && logicalLength > MAX_REVISION_PAYLOAD + 150' 为条件——非 revision 行没有上限
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogFileReader.kt:139` — 注释声称流式读取、峰值「约一个 SQL 条目」——但单个条目/行在读取路径上本身就是无界的
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogAutoSaver.kt:31` — rollThresholdBytes 100MB 仅用于写入侧；读取侧没有对应物（readFromFile/forEachEntry/countInFile 从不检查文件长度）
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/HistorySearchService.kt:60` — MAX_SCAN_ENTRIES = 200_000 限制解析出的条目数量而非字节数；单个条目可以任意大
- `src/test/kotlin/cn/song/mybatisplus/visualizer/log/O147HistoryUpgradeTest.kt:133` — 现有测试只针对 REVISION_PREFIX 行验证超限折叠（此处是巨大的 'A'.repeat(MAX_REVISION_PAYLOAD+500) revision 行）；非 revision 的巨大行路径未被测试

### 阻塞点（精确）

- 本机没有可用于目标受控执行的、由操作系统强制的沙箱
- 缺少观测：通过 readFromFile、forEachEntry 和 countInFile 读取有界超大夹具（例如一条 64-128MB 的单个非 revision 行，以及一条 64MB 的 '-- pd: ' 负载行）时，IDE 进程解析路径测得的堆增长/保留（或 OOM）

### 解决计划

**本地（有界、非破坏性）：** gradle --tests cn.song.mybatisplus.visualizer.log.SqlLogFileReaderTest 加上 O147HistoryUpgradeTest 夹具模式（两个测试文件都存在）：写入一个临时 .sql，包含一条 64MB 的非 revision 行和一个带 64MB '-- pd: ' 负载行的块，然后在受限的 JVM 堆（例如 -Xmx256m）下运行 SqlLogFileReader.readFromFile、forEachEntry 和 countInFile，观察堆增长/OOM，并与被折叠的 revision 前缀对照行比较；一旦加入新的逐行/逐文件上限，即断言其生效。还要用相同的夹具和 scanEntryCap，通过 HistorySearchService 测试（HistorySearchFilterTest 存在）验证代理路径。

**所有者观察的部署检查：** 由所有者在真实 IDE 上观察：提交一个包含 .idea/sql-visualizer/sql-history/planted.sql（其中有一行 512MB）的项目，打开 SQL Visualizer 历史标签页和/或从 MCP 客户端调用 sqlv_history_search，并在性能分析器中观察 IDE 内存/挂起。

---

## 24. sqlv_history_search 中由代理提供的正则表达式在历史 SQL 上求值时没有超时或成本预算，可在 IDE 进程内造成灾难性回溯的 CPU 耗尽

- **指纹**：`mcp.history_search.agent-regex-unbounded-backtracking`
- **边界 / 入口点**：sqlv_history_search — 代理提供正则（并可选择先通过 sqlv_explain 向历史植入一条长 token SQL，由 McpToolsetSupport.kt:302-320 的 mcpHistoryEntry 记录）

### 描述

sqlv_history_search 接受代理可控的正则，该正则只做语法检查即被编译（HistorySearchService.kt:231，runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }），并在 IDE 的 MCP 分发路径上对每个扫描到的历史条目的 rawSql/restoredSql 以 containsMatchIn 求值（第 221 行）。扫描上限（MAX_SCAN_ENTRIES = 200_000，第 60 行）限制条目数量，但不限制每次正则求值的成本；控制台捕获的条目受 20k 日志 maxParseLength 限制，而 MCP 植入的条目（mcpHistoryEntry）承载原始代理 selectSql，受 McpSelectGuard.MAX_SQL_LENGTH = 200_000 上限约束。Java 的正则引擎没有内置求值超时，因此病态模式对长 token 求值时可能让调用线程无限期停顿。对抗性输入可在同一工具面内植入：代理可以先通过 sqlv_explain 把任意 selectSql 记录进历史（经由 mcpHistoryEntry 记录到自动保存链），或依赖控制台捕获的 SQL 中已包含的长 token。

### 所称根因

HistorySearchService 编译并应用代理正则时，没有模式复杂度限制、没有依赖输入长度的保护、也没有求值超时；该服务中唯一的预算限制的是结果数量与条目数量，而不是每次正则的成本。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerHistorySearchTool.kt:51` | sqlv_history_search | 代理提供正则（并可选择先通过 sqlv_explain 向历史植入一条长 token SQL，由 McpToolsetSupport.kt:302-320 的 mcpHistoryEntry 记录） |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/HistorySearchService.kt:231` | CompiledFilters.of | Regex(pattern, RegexOption.IGNORE_CASE) 只做语法检查即编译；没有复杂度上限，没有超时；IGNORE_CASE 大约使匹配成本翻倍 |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/HistorySearchService.kt:221` | CompiledFilters.matches | regex.containsMatchIn(e.rawSql)/containsMatchIn(e.restoredSql) 在 IDE 的 MCP 分发线程上对完整条目 SQL（控制台捕获条目为 20k 字符，MCP 植入条目按 McpSelectGuard.MAX_SQL_LENGTH 最多 200k 字符）执行回溯搜索，最多覆盖 200,000 个条目 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/HistorySearchService.kt:231` — 代理正则通过 runCatching { Regex(pattern, IGNORE_CASE) } 编译，只处理语法失败（InvalidQueryException）；没有长度、复杂度或求值预算。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/HistorySearchService.kt:221` — containsMatchIn 在每个条目上对 rawSql 和 restoredSql 都求值；MAX_SCAN_ENTRIES 上限（第 60 行）只限制条目数量。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpToolsetSupport.kt:302` — mcpHistoryEntry 将代理选定的 selectSql 原样记录（rawSql = restoredSql = 去除首尾空白的输入，不减少长度；外部上限为 McpSelectGuard.MAX_SQL_LENGTH = 200_000，McpSelectGuard.kt:24）到 source 为 SqlSource.MCP 的历史链中，给了代理一种自助方式去植入随后会被该正则求值的长对抗性 token。

### 阻塞点（精确）

- 本机没有可用于目标受控执行的、由操作系统强制的沙箱
- 缺少观测：在本代码路径下，用 JDK java.util.regex 对 20k 字符条目求值病态模式（例如 '(a+)+b'）测得的 wall-clock，用以量化每次调用实际停顿多久
- 缺少观测：平台 MCP server 通道是否施加了每次调用超时或序列化上限，从而约束被卡住的分发线程（平台行为在本仓库中不可见）

### 解决计划

**本地（有界、非破坏性）：** 扩展现有的纯逻辑测试 cn.song.mybatisplus.visualizer.mcp.HistorySearchServiceTest（它不涉及平台类型，直接驱动 HistorySearchService.search(perFile, query, nowMs)）：构建 perFile，其中一个条目的 restoredSql 为 20k 个 'a' 字符，以 Query(regex = "(a+)+b") 调用 search，并加 wall-clock 断言（例如必须在 2 秒内返回），观察超时——用假数据确立无界回溯停顿。以 gradle --tests cn.song.mybatisplus.visualizer.mcp.HistorySearchServiceTest 运行。

**所有者观察的部署检查：** 由所有者观察：在沙箱化 IDE 中运行插件的 MCP server，通过 sqlv_explain 植入一条长 token 语句，然后以病态正则调用 sqlv_history_search，并在 IDE 响应性或 idea.log 中观察被卡住的调用。

---

## 25. 导入的动态表名正则规则在预览渲染路径上被编译和执行，没有复杂度或超时上限

- **指纹**：`dynamic-tablename-rule.regex-unbounded`
- **边界 / 入口点**：设置导入 — deserializeRules (337-345) 直接从导入的 JSON 数组构建 TableNameRule(pattern, replacement, enabled)，没有任何模式筛查

### 描述

DynamicTableNameProcessor 用 Regex(pattern) 编译每个规则模式，并在每次 wrapper 预览渲染时运行 regex.find(tableName)（DynamicTableNameProcessor.kt:39），没有超时、输入长度上限或复杂度筛查。dynamicTableNameRules 是导入设置文件可设置的字段之一，因此共享文件可以安装一个灾难性回溯模式（嵌套量词套交替），当操作员预览任何表名触发病态路径的语句时就会挂住预览渲染——其可用性影响波及文件作者之外。正则结果缓存还是非同步的 mutableMapOf（第 26 行），可能在渲染期间被读取，并被另一线程的 PluginCacheManager.clearAll（PluginCacheManager.kt:33）清空。

### 所称根因

来自设置的用户/导入者提供的正则，在渲染路径上执行时没有 ReDoS 上限（没有超时、没有模式筛查、没有输入上限），且编译缓存非同步。

### 源码轨迹

| 类型 | 位置 | 范围 | 说明 |
|---|---|---|---|
| entrypoint | `src/main/kotlin/cn/song/mybatisplus/visualizer/config/SettingsIO.kt:344` | 设置导入 | deserializeRules (337-345) 直接从导入的 JSON 数组构建 TableNameRule(pattern, replacement, enabled)，没有任何模式筛查 |
| propagation | `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewService.kt:1495` | 预览渲染管线 | processDynamicTableName（在语句构建链中于 SqlPreviewService.kt:412 调用）在每次 wrapper 预览渲染时把所有启用的、非空白的规则传给 DynamicTableNameProcessor.process(table.tableName, rules) |
| sink | `src/main/kotlin/cn/song/mybatisplus/visualizer/sql/interceptor/DynamicTableNameProcessor.kt:39` | 正则执行 | regex.find(tableName) 执行导入的模式，没有超时或复杂度上限；compileRegex (48-56) 将 Regex(pattern) 缓存在第 26 行无界、非同步的 mutableMapOf 中，runCatching 只防护编译失败；随后 resolveReplacement (61-68) 字面值展开 $tableName/$n 分组 |

### 证据

- `src/main/kotlin/cn/song/mybatisplus/visualizer/sql/interceptor/DynamicTableNameProcessor.kt:50` — runCatching { Regex(pattern) } 只防护编译失败；第 39 行的执行调用 regex.find(tableName) 没有超时、步数上限或模式复杂度筛查
- `src/main/kotlin/cn/song/mybatisplus/visualizer/sql/interceptor/DynamicTableNameProcessor.kt:26` — regexCache 是一个普通 mutableMapOf<String, Regex?>，在 compileRegex (49-54) 中被修改，并被 PluginCacheManager.clearAll 清空（util/PluginCacheManager.kt:33 调用 DynamicTableNameProcessor.clearCache()）——非同步的跨线程访问
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/SettingsIO.kt:344` — 导入的规则模式原样接受自共享设置文件（'TableNameRule(pattern, replacement, enabled)'）

### 阻塞点（精确）

- 本机没有可用于目标受控执行的、由操作系统强制的沙箱
- 缺少观测：本地计时测量，证明病态的导入模式（嵌套量词形式）对现实表名（约 20-64 字符）在本项目所用 JVM 上会产生长时间运行的 regex.find

### 解决计划

**本地（有界、非破坏性）：** 向 src/test/kotlin/cn/song/mybatisplus/visualizer/sql/interceptor/DynamicTableNameProcessorTest.kt 添加一个计时用例：TableNameRule 使用嵌套量词模式（例如 '(a+)+$' 风格）对抗 30+ 字符的对抗性表名字符串，在本地断言/wall-clock process() 调用；以 gradle --tests cn.song.mybatisplus.visualizer.sql.interceptor.DynamicTableNameProcessorTest 运行，并在有界 wall clock 下记录耗时。

**所有者观察的部署检查：** 由所有者观察：在 runIde 沙箱中导入包含病态规则的设置文件，预览一个表名触发病态回溯路径的 wrapper（现实长度的非匹配输入），观察 IDE 渲染路径停顿——然后确认修复（超时/步数受限的匹配）消除了停顿。

---

