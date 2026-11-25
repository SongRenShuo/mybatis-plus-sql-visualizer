# 05 运行/调试控制台、Java Agent 与 JVM 探针

调研对象：OpenIDEA 源码仓 `F:\code\Java\Github\other\openidea`，main @ `a7b67347c`（2026-11-25）。仓库 `wit/` 是 API 1.16.0 开发版；本次只使用在已发布 API 1.15 中已存在的接口（`websocket-connect` 1.6、`java.agent` 1.10、`set-console-rules` 1.12、`output` 1.4、`ipc` 1.4），1.16 仅用于读实现。另读社区插件 `weigram/plugin-hotreload` 1.3.2 源码。

本文所有结论按四档标注：现成可做 / 变通可做 / 需上游 / 不可行。

## 结论速览

| 问题 | 结论 | 档位 |
| --- | --- | --- |
| 捕获用户应用里的 JDBC SQL | 用 `[[contributes.javaAgents]]` 注入既有 Java 8 agent，agent 在 127.0.0.1 自建 WebSocket 服务，插件以 `net:127.0.0.1` 连接并消费 | 现成可做（覆盖仅 Application / Spring Boot / JAR Application） |
| 补齐测试、Maven/Gradle、Tomcat 等场景 | 插件自带 attach 驱动，用 `VirtualMachine.loadAgent` 把同一 agent 挂到已运行 JVM；`process:java` / `process:jps` 高权限，工程与授权成本高 | 变通可做（建议二期） |
| 直接读取 IDE 运行控制台的文本 | 没有任何插件接口能读到 Run/Debug 控制台输出；高亮规则只着色，插件“never sees the output” | 不可行 |
| 间接拿到控制台里打印的 SQL 日志 | 让用户开启运行配置的 “Save console output to file”，插件用 `project.read` 轮询该文件 | 变通可做 |
| `ipc:<path>` 命名管道做 agent 回传 | 插件只能做客户端；Java agent 侧在 Windows 没有 JDK 命名管道 API，Unix 要 JDK 16+ 的 AF_UNIX | 变通可做（仅 Unix）；Windows 不可行 |
| 复用 `java/jvm-inspect` 做 SQL 探针 | 它是 JDT LS 内的 OSGi bundle，走 java-debug 的 JDI 链路，只在挂起时读实例/字段，且不对插件暴露 | 不可行（需上游另开能力） |

一句话：能拿到 SQL 的可靠通道是“IDE 注入 agent + agent 反向开 WebSocket”，控制台文本只能靠“保存到文件”绕；控制台高亮、Output 标签页、log 标签页都只负责显示，不提供回读。

---

## 1 运行与控制台 API 的事实边界

### 1.1 插件只能启动自己的配置，只能拿到退出码

`wit/run.wit:6-8`：

```wit
/// Starts an owned configuration, appending args. Debug requires a debugger.
/// Only from a window command/event. The returned id receives run-exited.
start: func(config: string, args: list<string>, debug: bool) -> result<string, error>;
```

`run.start` 的 `config` 被宿主强制加 `plugin:<插件id>:` 前缀，只在本插件通过 `run-configurations` / `launch-configurations` 或 `runDetectors` 声明过的集合里查（`crates/ide-host/src/plugins/api.rs:3449-3461`）。用户自己建的 Application 配置不能由插件直接启动；要启动它只能借 `ide.commands` 执行 `run.runContext` 一类 IDE 动作（weigram 的做法，见 §5）。

运行结束只有一个事件，没有输出：

| 事件 | 载荷 | 证据 |
| --- | --- | --- |
| `run-exited` | `{id, config, status, killed}` | `plugins/README.md:2478`，`crates/ide-plugin/src/execution.rs:109-116`，`types.rs:204` |
| `terminal-exited` | `{id, key, status, closed}` | `plugins/README.md:2479` |

`status` 是有符号退出码或 null，`killed` 表示 IDE 停止。整条链路没有 stdout/stderr 字段。

### 1.2 控制台高亮规则：只着色，不回读

`wit/host.wit:122-132`：

```wit
/// Replaces the plugin's console highlight rules (API 1.12); an empty
/// list removes them. Every window applies them to the stdout and stderr
/// lines of its Run and Debug consoles; the plugin never sees the output.
set-console-rules: func(rules: list<console-rule>) -> result<_, error>;
```

`console-rule` 的语义（`wit/host.wit:58-81`）：正则最长 512 字节、最多 100 条；调色板前景/背景色、`bold/italic/underline/strikethrough/dim`；`whole-line` 或仅匹配段；规则只看一行前 2000 个字符；首个命中的整行规则决定整行样式，匹配型规则叠加。文档同样写明“The plugin never reads the console”（`plugins/README.md:1120-1125`）。

所以 0.1.49 加入的控制台高亮规则是“Grep Console 式显示增强”，不是输出订阅。它能用于给 SQL 行上色，不能用于采集。

### 1.3 Output 标签页、log 标签页、终端：写或显示，不读

| 接口 | 能力 | 证据 |
| --- | --- | --- |
| `output.append/clear/reveal` | 每插件 32 个具名通道，各 1 MiB UTF-8 环形缓冲；只写 | `wit/host.wit:248-256` |
| `views.open-log-tab(key,title,process-log)` | 插件授权一个程序，IDE 起进程并把输出流进只读编辑器标签页；保留 5000 行 / 1 Mi UTF-16 | `wit/views.wit:180-187`，`plugins/README.md:2309-2360` |
| `views.open-terminal` / `open-terminal-session` | 终端显示；`write-terminal` 只写 | `wit/views.wit:146-181` |
| `details.process-log` | Services 面板里的日志页，最后 5000 行 | `wit/details.wit:13-20` |

这些接口的共同点是“输出终点”：内容由 IDE 渲染给用户，没有 drain/read 方法。log 标签页甚至连 stdout 都不回传插件，只在标签页里显示。

### 1.4 插件自己起的进程可以读 stdout/stderr

`process.run` / `run-command` 返回 `{status, stdout, stderr}`（各 4 MiB）；`process.start` 返回 `child`，`poll(max-bytes)` 分块读 stdout/stderr，`wait` 等到结束，单流最多 128 MiB，单插件最多 8 个常驻 child（`wit/io.wit:132-215`）。这条能力只适用于插件自己启动的程序，不适用于 IDE 的 Run 配置。

### 1.5 本地网络与 IPC

| 接口 | 规则 | 证据 |
| --- | --- | --- |
| `net.fetch` | HTTPS 要 `net:<host>`；明文 HTTP 要 `net.http:<host>`，回环除外——回环 HTTP 用 `net:<host>` | `wit/io.wit:55-75`，`plugins/README.md:1840-1844` |
| `websocket-connect` | `wss://` 要 `net:<host>`；回环 `ws://` 允许；最多 8 条/插件；入站帧有界 FIFO，溢出即拒帧并关连接 | `wit/io.wit:93-110`，`crates/ide-host/src/plugins/websocket.rs:262-270` |
| `ipc.connect` | 只连绝对 Unix socket 或 Windows 命名管道 `\\.\pipe\name`，绝不 TCP；最多 8 条；要高风险 `ipc:<path>` | `wit/io.wit:218-230`，`plugins/README.md:1870-1875` |

回环能力的归一化在 `crates/ide-plugin/src/types.rs:614-636`：`http://127.0.0.1:port` 映射为 `Capability::Net("127.0.0.1")`；`ws://127.0.0.1` 先转成 http 再走同一映射。也就是说，“agent 在本机回环开 WebSocket、插件连上去”只需要中风险的 `net:127.0.0.1`。

注意一处文档与实现的偏差：`wit/io.wit:93` 的注释写“loopback `ws://` is allowed for tests”，而宿主实现 `websocket.rs:267` 是无条件允许 `ws + loopback`，README `:1412` 也表述为“WSS outside loopback”。按实现可用，但建议上游确认“生产回环 WS”是否长期支持（见 §11）。

### 1.6 官方插件里的输出/终端范式

| 插件 | 用法 | 对我们的意义 |
| --- | --- | --- |
| `plugins/official/container-runner/src/lib.rs:22-40` | `open_terminal_session` 开键控终端；`output_append` 写具名 Output 通道；`terminal-exited` 只用来更新状态条 | 证明“终端/Output 是显示终点”，事件只带退出码；可作为我们状态栏/在线态的范式 |
| `plugins/official/git-toolbox/src/lib.rs:1-90` | `on-schedule` 周期刷新 + 状态条 + `vcs.read` | 60 秒周期是插件唯一的内建定时器（`docs/zh/plugins/manifest.mdx:83`），适合做“WS 断线重连/发现文件刷新”的兜底 |
| `plugins/official/license-header` | `editor.read/write` + provider | 与运行链路无关，但说明官方插件同样没有控制台读取面 |

`plugins/README.md` 的 “Streaming log editor tabs”“Output”“Terminals” 三节（`:2090-2400`）与 `container-runner` 一致：全部是“插件给程序、IDE 显示”，没有反向读取。

---

## 2 运行输出在宿主内部怎么走

`crates/ide-run/src/process.rs:40-62` 定义 `OutputChunk::{Stdout,Stderr,System}` 与 `RunOutputState`：每个 run 持有 256 KiB 回放缓冲，`emit` 时调用 `on_output` 回调。回调由窗口层安装，用于 Tauri 事件推给前端 Run 控制台；`RunAttach` 也只用于“窗口重连后补历史”（`crates/ide-run/src/lib.rs:220-251`）。

插件宿主没有接到这条回调上。`crates/ide-plugin/src/wit_host.rs` 里只有 `output_write` / `output_reveal`，没有 run output 的订阅入口。因此从宿主内部看，“控制台输出”是 IDE 前端与用户之间的私有通道。

唯一的旁路是运行配置的 “Save console output to file”：

- 字段：`RunExtras.output_file`（`crates/ide-run/src/lib.rs:142-145`，`crates/ide-run/src/config.rs:100-101`）
- 落盘：`tee_output` 把每个 chunk 同时写文件，使用 `LineWriter`，遇到换行即 flush（`crates/ide-run/src/process.rs:358-373`）
- 启动时安装：`process.rs:430-433`
- UI：运行配置“修改选项 › 日志 › Save console output to file”，对普通配置（含 Java Application / Maven / Gradle）可见（`src/features/run/ConfigForm.tsx:124-138,752-765`；`docs/zh/guide/run.mdx:131`）
- 语义：每次运行 `File::create` 覆盖（`docs/zh/guide/run.mdx` 与 `ConfigForm` 文案）

这构成一条“IDE 写、插件读”的旁路：文件在项目内时用 `project.read-file`（`wit/workspace.wit:68`），配合 `files-changed` 事件或 60 秒 `on-schedule` 轮询。

---

## 3 `java.agent` 通道的工程细节

### 3.1 声明与注入时机

```toml
[[contributes.javaAgents]]
path = "agent/mps-sql-agent.jar"
args = "port=0,mode=ws"
vmOptions = ["-XX:+AllowEnhancedClassRedefinition"]  # 可选，API 1.14
jdk = "jetbrains"                                    # 可选
```

规则（`plugins/README.md:2498-2543`，`docs/zh/plugins/capabilities.mdx:74-78`，`crates/ide-run/src/java_agent.rs:1-20`）：

| 项 | 事实 |
| --- | --- |
| 每插件 agent 数 | 1 个；第二个声明、包外路径、缺失文件在读取清单时拒绝 |
| 授权 | 声明自动派生高风险可选能力 `java.agent`；不能写进 `[capabilities]` |
| 完整性 | 授权时记录 jar 内容哈希；每次启动前复核，jar 变了就跳过并在控制台提示，直到用户重新批准 |
| 注入位置 | 配置自身 VM options 之后、主类 / `-jar` 之前；`vmOptions` 在 `-javaagent` 之前 |
| 生效配置 | 仅 `application`、`spring-boot`、`jar-application` 三种 kind（`java_agent.rs:17`） |
| Run/Debug | 两者都注入（Debug 时加进 java-debug 的 `vmArgs` 或 Kotlin 调试器列表） |
| 不生效 | JUnit、Maven/Gradle 目标（含 “Build and run using: Gradle”）、Tomcat 等应用服务器、远程 JVM 调试、运行目标、远程项目 |

一个容易忽略的点：插件自己声明的运行配置 kind 是 `plugin`（`crates/ide-host/src/plugins/run_detectors.rs:161,187`），不在 `KINDS` 里。所以“插件声明一个 `program=java` 的配置再指望 IDE 自动加 agent”不成立；插件若自己拼 `-javaagent`，又拿不到模块 classpath（构建模型不向插件开放）。

### 3.2 多模块 / 并行运行

- agent 加载在用户应用 JVM 内，不是插件的子进程，不占“8 个常驻 child”名额。
- 每个被启动的 Application JVM 各有一份 agent；多模块并行启动 = 多个 agent 实例 = 多条回环 WebSocket。插件侧 WS 上限是 8 条/插件（`wit/io.wit:97`），超过就需要合并或退回文件通道。
- `run-exited` 只带配置 id 和退出码，不带 pid；agent 需要在握手/发现文件里自报 pid，插件才能把会话与应用对应起来。
- 插件被空闲回收（300 秒）时实例句柄被丢弃，WebSocket 资源随之关闭；`on-schedule` 只负责重新激活、不保活（`docs/zh/plugins/lifecycle.mdx:24`）。可靠性设计必须允许“插件休眠 5 分钟后重连 + agent 端补发”。

### 3.3 参数是静态一行，端口与令牌只能靠发现文件

`args` 在清单里写死（一行），插件运行时改不了；因此不能让插件“启动前告诉 agent 端口”。

推荐 rendezvous 方案：

1. agent 在 `premain` 从 `user.dir` 向上找项目根（`.openidea`/`.git`/`pom.xml`），在 `<root>/.openidea/sqlvis/agents.json` 维护一个注册表：用 `FileChannel.lock` 串行化读-改-写，写入 `{pid, port, token, protocol, appName, startedAt, lastSeen}`；端口用 `ServerSocket(0)` 分配。多 JVM 共享一个固定文件，避免插件需要列目录。
2. 插件在激活/`on-schedule` 时用 `project.read-file(".openidea/sqlvis/agents.json")` 读注册表（固定路径，不依赖目录枚举）；`files-changed` 只作为加速信号，不作为唯一唤醒源。
3. 插件连 `ws://127.0.0.1:<port>/events?token=…`，token 防本机其它进程伪造事件。
4. agent 周期性更新 `lastSeen`，退出时从注册表移除；插件按 `lastSeen` 清理僵尸条目。

不要用 `find-files(".openidea/sqlvis/agents/*.json")` 做发现：`project.find-files` 明确跳过 ignored/excluded 文件（`wit/workspace.wit:99-103`），而 `.openidea/sqlvis` 很可能被用户加进 `.gitignore`。固定路径的 `read-file` 没有这个限制。

### 3.4 agent 侧 WebSocket 服务端的实现代价

agent 目前是 Java 8 字节码（`sub-projects/sql-visualizer-agent/build.gradle.kts:2`），目标是 JDK 8–26 的用户应用。JDK 8 没有 WebSocket 服务端，也没有命名管道；可选实现：

| 方案 | 依赖 | 代价 | 结论 |
| --- | --- | --- | --- |
| 手写 RFC 6455（`ServerSocket` + 握手 + 帧解析） | 纯 JDK | 约 350–600 行；只需文本/二进制、ping/pong、掩码，不需要 TLS 与扩展 | 推荐 |
| 内嵌 Jetty/Netty/Tomcat | 数 MB 依赖 | fat jar 体积、类加载冲突、许可 | 不推荐 |
| `com.sun.net.httpserver` + 长轮询 | 纯 JDK | 简单，但插件没有秒级定时器（`on-schedule` 最短 60 秒，`docs/zh/plugins/manifest.mdx:83`），只能靠 WS 事件或命令内循环，不能持续采集 | 仅一次性导入可用 |
| 由 Web 视图直连 agent HTTP | 纯 JDK + 前端 fetch | 绕过 wasm actor，但受 CSP/混合内容/面板关闭影响，且解析逻辑要在 JS 重写 | 备选（待验证） |

现有 agent 的发送端是“agent 主动连 IDE 的 loopback TCP”（`sub-projects/sql-visualizer-agent/src/main/java/.../transport/SenderLoop.java:14`），OpenIDEA 下必须翻转成“agent 监听、插件连接”，因为插件没有监听接口。协议帧（4 字节长度 + 1 字节命令 + JSON）可以原样复用，只是传输层换成 WS 二进制帧。

---

## 4 JVM 探针：`jvm-inspect` 与 attach 能力

### 4.1 `java/jvm-inspect` 是什么

它是加载进 JDT LS、与 microsoft java-debug 并排的 OSGi bundle，借助 java-debug 自己的 JDI 连接工作，不另连调试器（`java/jvm-inspect/README.md:1-10`）。能力：

- `openidea.jvm.vms/classes/instances/fields/referrers`：列已加载类、实例、字段、引用者；
- `openidea.jvm.stream.*`：在程序运行时跟踪 Stream 链；
- 异常断点。

限制：数据只在某个线程挂起时可读（`README.md:32`），不能 invoke（无 `toString`），对象 id 只在当前 stop 的 generation 有效。它没有 JDBC/SQL 拦截能力。

插件能否调用？`language-servers.request` 只允许“语言服务器自己的方法”，任何以 LSP 命名空间开头的方法（`workspace`、`textDocument`…）都被拒绝（`wit/host.wit:232-236`）。`workspace/executeCommand` 正属于被拒集合，所以插件调不到 jvm-inspect。即使调到，它也不是 SQL 探针。

### 4.2 `ide-jvm-attach` 与 jcmd

`crates/ide-jvm-attach` 自己实现了 HotSpot attach 协议：`.attach_pid<pid>` 触发文件、Unix socket / Windows 命名管道、`1\0<op>\0…` 的 v1 请求（`crates/ide-jvm-attach/src/socket.rs:92,157`；`listener.rs:38-43`），用于调试器的 JVM Memory 视图（jcmd 命令、heap dump、线程栈）。它同样不对插件暴露。

一个需要纠正的传言：`jcmd <pid> JVMTI.agent_load` 只能加载 JVMTI 原生库（`.so`/`.dll`），不能加载带 `Agent-Class` 的 Java agent jar（Oracle jcmd 文档原文：“Loads JVMTI native agent”，参数是 library path）。Java agent jar 的 attach 入口是 JDK attach API 的 `VirtualMachine.loadAgent`（内部走 attach 协议的 `load` + `instrument`），或第三方 `jattach load instrument`。weigram 源码 `HotReloadPremain.java:19` 的注释“for running jcmd <pid> JVMTI.agent_load”是不准确的，不足以作为方案依据。

### 4.3 结论

`jvm-inspect` / `ide-jvm-attach` 证明 OpenIDEA 内部有 JDI 与 attach 能力，但都不在插件 API 面上。想在 OpenIDEA 里 attach 到已运行 JVM，插件必须自己带一个 Java 驱动（`VirtualMachine.loadAgent`）或用第三方 jattach，再用 `process:java` / `process:jcmd` 启动它（见 §7.4）。

---

## 5 `weigram.hotreload` 手法拆解

来源：`https://github.com/weigram/plugin-hotreload`，1.3.2，注册表条目 `openidea-plugins/plugins/weigram.hotreload.toml`。

| 环节 | 做法 | 证据 |
| --- | --- | --- |
| 注入 | `[[contributes.javaAgents]]` 带 `path = "agent/hotreload-agent.jar"`，`vmOptions = ["-XX:+AllowEnhancedClassRedefinition"]`、`jdk = "jetbrains"` | `plugin.toml:22-27` |
| 入口 | `premain` 与 `agentmain` 都调 `Agent.start` | `HotReloadPremain.java:15-20` |
| 兼容 | agent 用 `--release 8` 编译，jar 可复现打包（排序条目、固定时间戳），保证 `java.agent` 哈希稳定 | `build.sh:4,41` |
| 类重定义 | 合并 HotSwapAgent 2.0.5，直接反射调用其 `premain`；失败回退自研 `ClassRedefiner` | `Agent.java:29-100` |
| MyBatis XML | 自研 `MyBatisConfigTransformer`（ASM 注入 `addMappedStatement`）+ 命名空间级刷新/回滚 | `Agent.java:72`, `MyBatisXmlReloader.java` |
| 触发构建 | 订阅 `files-changed`，执行 IDE 动作 `build.buildModule`；不自己跑 Maven | `src/lib.rs:88-120,160-180` |
| 手动构建 | `process:mvn` + `open_terminal_session`，避免 5 秒调用预算 | `src/lib.rs:220-250` |
| 启动入口 | 右键 Run/Debug 时执行 `run.runContext` / `run.debugContext`，确保是 Application 类配置，从而吃到 java agent | `src/lib.rs:123-150` |

它对我们最有价值的三点：

1. `java.agent` 注入 + Java 8 字节码 + 可复现 jar 是已经被市场验证的组合。
2. 它没有“agent 向插件回传”的需求——agent 自己看文件系统；我们的 SQL 采集必须补这一层。
3. 它把 agent 的运行日志打在应用控制台，只用于人看；这反证控制台不是插件可读的数据通道。

不能照搬的点：`ide.commands` 的 `run.runContext` 依赖当前编辑器/选中文件，无法精确指定“某个模块的某个配置”；`jdk = "jetbrains"` 只对 JBR 生效；`agentmain` 注释里的 jcmd 手法不成立（见 §4.2）。

---

## 6 A. 捕获 JDBC SQL 的路径排序

评分维度：覆盖面、数据通道、时延、可靠性、工作量、授权/风险。

| # | 路径 | 前置 | 覆盖 | 数据通道 | 时延 | 可靠性 | 工作量 | 档位 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| P1 | `java.agent` + agent 自建回环 WebSocket | 授予 `java.agent`、`net:127.0.0.1`；本地项目；Application/Spring Boot/JAR Application | 三种 Java 配置，Run+Debug；不含测试/Maven/Gradle/Tomcat/远程 | agent→插件 WS，发现文件给端口/令牌 | 毫秒级推送 | 高；需处理 8 连接、8 MiB/256 帧队列、空闲回收重连 | agent +400–600 行 Java8；插件 +400–700 行 Rust | 现成可做 |
| P2 | `java.agent` + agent 写 NDJSON 文件 | 授予 `java.agent`、`project.read`；项目根可写 | 同 P1 | agent 写 `.openidea/sqlvis/events/*.ndjson`，插件尾读 | 事件触发约百毫秒到 1 秒（推断，待实测）；最坏 60 秒 | 中；16 MiB 读上限、需轮转、ignored 目录事件待验证 | agent +150 行；插件 +250 行 | 变通可做 |
| P3 | `java.agent` + agent 打 stdout + 运行配置 “Save console output to file” | `java.agent`；用户为配置开启 outputFile | 同 P1，且仅当用户开启；也可用于其它配置类型 | IDE 写文件，插件读 | 换行即 flush，事件/轮询决定延迟 | 中；日志混流、ANSI、16 MiB、每次运行覆盖 | agent +50 行；插件 +200 行 | 变通可做 |
| P4 | attach：`process:java` 驱动 `VirtualMachine.loadAgent` | 本机 JDK（驱动需 11+）、`process:java`/`process:jps`、agent jar 落盘 | 任意本机 JVM：测试、Maven/Gradle fork、Tomcat、手工 java；不含远程 | 同 P1（agent 起来后走 WS） | attach 秒级 + WS 毫秒 | 中高；pid 发现与匹配是难点；attach 可被禁用 | 驱动 +200 行 Java；插件 +300 行；UX 一套 | 变通可做（二期） |
| P5 | 插件自起 `java`/`mvn` 并读 stdout | `process:java`/`process:mvn`；classpath 可解析 | 仅插件自己启动的进程；无 IDE 调试/构建/运行窗口 | `process.start` 的 stdout/stderr | 毫秒到秒 | 中；classpath、构建、端口、进程回收都要自理 | 高 | 变通可做（窄场景） |
| P6 | `ipc:<path>` | 高风险 `ipc`；agent 侧命名管道/AF_UNIX 服务端 | 同 P1 | 本地字节流 | 毫秒 | Unix 可以，Windows 不行 | 中高 | 变通可做（仅 Unix）；Windows 不可行 |

### 6.1 P1：推荐主路径

前置条件：

- 用户在 Settings › Plugins 授予 `java.agent`；插件清单声明一个包内 agent jar。
- 用户用 Application / Spring Boot / JAR Application 三种配置之一启动；运行或调试都行。
- 声明可选 `net:127.0.0.1`（中风险）。不需要 `ipc`、不需要 `process`。

数据通道：

```text
OpenIDEA ──(-javaagent:...jar=...)──> 用户应用 JVM (agent premain)
                                          │ ServerSocket(0) + WS
                                          │ 更新 .openidea/sqlvis/agents.json（固定路径）
插件 wasm ── project.read-file（固定路径）──┘
      └──── ws://127.0.0.1:<port>/events?token=…（net:127.0.0.1）
```

覆盖：

| 场景 | 是否覆盖 | 说明 |
| --- | --- | --- |
| Application 主类 | 是 | `KINDS` 含 `application` |
| Spring Boot（直接 java 启动） | 是 | `applicationConfig` 会给带 `@SpringBootApplication` 的主类生成 `spring-boot` kind（`src/features/run/application.ts:26-44`） |
| JAR Application | 是 | `KINDS` 含 `jar-application` |
| Debug | 是 | agent 同时加进调试启动参数 |
| Maven `spring-boot:run` | 否 | 配置 kind 是 `maven`；虽然 IDE 支持把 VM 参数透传给 fork 的 JVM（`src/features/run/javaDebug.ts:82`），但不会自动加 agent |
| Gradle `bootRun` | 否 | 同上；`bootRun` fork 的 JVM 不在进程树内 |
| JUnit / Surefire | 否 | 文档明确不加载 |
| Tomcat 服务器 | 否 | 文档明确不加载 |
| 远程 JVM 调试 / 运行目标 | 否 | 文档明确不加载 |
| 远程项目 | 否 | agent 源在 `openidea-server` 不存在（`java_agent.rs:7-8`），运行控制台会提示跳过 |

时延与可靠性：

- WS 推送毫秒级；入站帧由宿主排队，插件在 `websocket-ready` 里 `drain`。宿主单消息上限 1 MiB、队列 8 MiB/256 条，溢出会拒绝该帧并关闭连接（`plugins/README.md:1412-1418`）。agent 必须沿用现有 EventBus 的有界队列 + 溢出聚合，并对断线重连做退避。
- 插件空闲 300 秒被回收时 WS 会断；建议声明 `on-schedule:60`，每次激活尝试重连，agent 侧保留最近 N 条/游标补发。SQL 持续产生时 `websocket-ready` 本身就是调用，不会空闲。
- 多个应用 JVM 同时运行受 8 条 WS 限制；超过 8 个模块并行时应合并（例如一个 agent 代为转发）或让部分模块走文件通道。

工作量与风险：

- agent 侧：在现有发送端之外增加一个极小的 WS 服务端与发现文件，复用 `EventBus` / `SenderLoop` 的帧格式与断线策略。参考量级 400–600 行 Java 8。
- 插件侧：WS 连接管理、发现文件扫描、`run-exited` 清理、状态栏在线态、设置项（脱敏/停采）下发。参考量级 400–700 行 Rust。
- 风险：`java.agent` 授权在每次 agent jar 变化后要重新批准（开发期高频）；回环 WS 的“仅测试”注释需上游确认；发现文件要求应用工作目录在项目内。

### 6.2 P2：文件通道

agent 把每条 SQL 事件追加为一行 JSON 到 `<root>/.openidea/sqlvis/events/<pid>.ndjson`，按 8 MiB 轮转；插件在 `files-changed` 时读新增部分。`project.read-file` 一次读整个文件，`workspace.wit:68` 未写上限（`fs.read-file` 同类接口是 16 MiB，按同量级预留），所以插件必须自己记 offset，且文件不能无限增长。

优点：不需要网络能力，离线/防火墙环境稳；缺点：写项目目录、事件是否覆盖 ignored 路径待验证、频繁整文件重读有成本。适合作为 P1 的降级通道。

### 6.3 P3：stdout + outputFile

让 agent 把事件打印成单行前缀（如 `[MPSV] {...}`），用户为运行配置打开 “Save console output to file”。IDE 用 `LineWriter` 逐行 flush，插件读文件解析。优点：复用现有控制台解析器；缺点：污染应用日志、需要用户配置、日志与业务输出混流、16 MiB 上限。适合“没有网络能力或 WS 被上游收紧”时的兜底。

### 6.4 P4：attach 补齐非 Application 场景

覆盖测试、Maven/Gradle fork、Tomcat、手工 java 等所有本机 JVM。实现要点：

1. 插件把 attach 驱动 jar 与 agent jar 嵌入 wasm（`include_bytes!`），首次运行写到 `fs.plugin_dir()`；WIT 没有暴露“安装目录路径”的函数（`fs.wit` 只有 `plugin-dir`），所以插件不能直接引用包内 jar 的绝对路径。
2. 用 `process.run("jps", ["-lv"])` 或 `jcmd -l` 列 JVM，按主类/命令行/项目路径匹配目标 pid；也可以让用户从列表里选。
3. `process.run("java", ["-cp", driver, "AttachMain", pid, agentJar, "mode=ws"])`；驱动调用 `VirtualMachine.loadAgent(agentJar, args)`，之后 agent 仍走 P1 的 WS 通道。
4. 授权：`process:java` 与 `process:jps` 都是高风险；`java` 的路径/哈希被钉住，JDK 升级后要重新批准。驱动 jar 若用 `--release 11` 编译，目标机必须有 11+ 的 java 可执行文件；要支持只有 JDK 8 的机器，需要另编 8 兼容驱动（`tools.jar`）或改用 jattach。
5. 限制：`-XX:+DisableAttachMechanism` 的 JVM 无法 attach；远程项目里 `process` 在服务器侧执行，若应用也在同一服务器则可 attach，跨机不行。

这条路的工程与授权成本明显高于 P1，但对“测试/Maven/Gradle 不注入”的缺口是唯一自动化的补法。建议二期再做，一期用“引导改用 Application 配置 + 手动粘贴”表达。

### 6.5 P5：插件自起进程

用 `process.start` 起 `java -javaagent:<jar> -cp … Main`，或起 `mvn spring-boot:run` 并追加 `MAVEN_OPTS=-javaagent:…`（`env` 只能新增，若系统已有 `MAVEN_OPTS` 会被拒绝）。然后 `child.poll` 读 stdout/stderr，直接跑控制台解析器。

代价：拿不到 IDE 的模块 classpath（`build.read` 只给依赖图，不给 classpath 字符串）、没有 Before launch 构建、没有调试器、没有 Run 工具窗口；8 个 child 上限与 300 秒回收都会杀进程。只适合“无 IDE 运行配置的演示/无头采集”，不建议作为产品主路径。

### 6.6 P6：ipc 通道

插件侧 `ipc.connect` 是客户端；agent 必须做服务端。Unix 上 JDK 16+ 可用 `UnixDomainSocketAddress`，JDK 8–15 不行；Windows 命名管道没有 JDK API，需要 JNA/JNI。授权是高风险 `ipc:<path>`，且路径在清单里静态声明（末段可用 `*`）。结论：仅 Unix + 新 JDK 的窄场景可行，跨平台不可行；若上游未来提供“插件监听本地 socket”的能力，可重新评估。

---

## 7 B. 捕获控制台 SQL 日志文本

### 7.1 直接结论

不可行。证据链：

1. `run.start` 只回 `run-exited`，没有 stdout/stderr（`wit/run.wit:6-8`，`plugins/README.md:2478`）。
2. 控制台高亮规则明确“the plugin never sees the output”（`wit/host.wit:122-131`，`plugins/README.md:1124`）。
3. `output` 是只写通道（`wit/host.wit:248-256`）；log 标签页只显示（`wit/views.wit:180-187`）。
4. 运行输出在 `ide-run` 内只走窗口回调（`process.rs:40-62`），插件宿主没有订阅入口。
5. MCP 的 `read_run_output` 只认 MCP 自己 `run_command` 起的 `AgentRun`（`crates/ide-host/src/mcp/run/agent_tools.rs:47-70,263`），读不到用户 Run 窗口的配置；插件也没有调用 MCP 工具的能力（`wit/mcp.wit` 只给 stdio 启动参数）。

### 7.2 可行替代

| 方案 | 能否拿到文本 | 证据 | 代价 |
| --- | --- | --- | --- |
| 运行配置 outputFile + `project.read-file` | 能 | `crates/ide-run/src/process.rs:358-373,430-433`；`config.rs:100`；`ConfigForm.tsx:752` | 用户需开启；项目内路径；单次读取上限待验证（按 16 MiB 量级设计）；每次运行覆盖 |
| 插件 `process.start` 自起进程 | 能 | `wit/io.wit:168-215` | 只限自起进程；无 IDE 运行/调试 |
| 插件自有测试配置的 `test-results.parse` | 能（仅测试输出） | `wit/run.wit:67-93`；`crates/ide-host/src/plugins/execution.rs:334-336,366-375` | 只对插件声明的 `test=true` 且 `framework="plugin"` 的配置生效；输出按 64 KiB 批回调；还要自己产出测试树 |
| MCP `execute_run_configuration` 的 `output` / `fullOutputPath` | 能（有限） | `crates/ide-host/src/mcp/run/configs.rs:737-740,815-824` | 每次调用要用户确认；不进 Run 工具窗口；临时文件在项目外，插件读不到；长跑只在超时后返回路径 |
| 插件自带 Web 视图直连 agent HTTP | 能（待验证） | 浏览器 fetch 不受 wasm 预算 | CSP/混合内容/面板关闭即停；解析逻辑在 JS |
| 上游新增 `run.output` 订阅或可读 log 标签页 | 能 | 无 | 需改 `wit/run.wit` + `crates/ide-run` + `crates/ide-plugin` + SDK + 发布快照 |

### 7.3 产品上的明确结论

- “控制台日志捕获”在 OpenIDEA 上只能作为降级能力：首次使用时引导用户为运行配置勾选 “Save console output to file”，文件放在项目内（如 `.openidea/sqlvis/console.log`），插件解析。
- 插件不能可靠地自动替用户打开这个开关（要改 `.openidea/run.toml`，`project.write` 是高危，且格式/并发编辑有风险）；可以做“一键写入示例配置 + 提示重启”，但必须显式征求同意。
- 读取侧限制要写进产品说明：单次读取上限待验证（按 16 MiB 量级设计）、每次运行覆盖、ANSI 需剥离、多模块需各自配置。

---

## 8 C. 通道选择、跨平台与授权代价

### 8.1 通道对比

| 通道 | 方向 | Windows | macOS/Linux | JDK 8 agent | 授权 | 评价 |
| --- | --- | --- | --- | --- | --- | --- |
| 回环 WebSocket | agent 服务端，插件客户端 | 可以（ServerSocket） | 可以 | 可以（手写服务端） | `net:127.0.0.1` 中 | 首选；推送、低时延 |
| 回环 HTTP 长轮询 | 同上 | 可以 | 可以 | 可以 | `net:127.0.0.1` 中 | 插件缺秒级定时器，只适合命令内一次性导入 |
| 项目文件 + 事件 | agent 写，插件读 | 可以 | 可以 | 可以 | `project.read` 低 + `events` 低 | 降级通道；有 16 MiB/轮转/事件覆盖问题 |
| 控制台 outputFile | IDE 写，插件读 | 可以 | 可以 | 不涉及 | 无新增（project.read） | 唯一能拿控制台文本的通道；需用户开启 |
| `ipc` 命名管道/Unix socket | agent 服务端，插件客户端 | 不行（无 JDK API） | JDK 16+ | 不行 | `ipc` 高 | 不推荐 |
| `process` stdio | 仅自起进程 | 可以 | 可以 | 不涉及 | `process` 高 | 窄场景 |

### 8.2 授权代价

| 能力 | 风险 | 何时需要 | 说明 |
| --- | --- | --- | --- |
| `java.agent` | 高（可选） | P1/P2/P3/P4 | 由 `[[contributes.javaAgents]]` 派生；jar 哈希变更需重新批准 |
| `net:127.0.0.1` | 中 | P1 | 只覆盖回环；不覆盖其它主机 |
| `project.read` | 低 | P1/P2/P3 | 读项目内发现文件/事件文件/控制台文件 |
| `events` | 低 | P1/P2 | `files-changed`、`websocket-ready`（后者还需要连接） |
| `project.write` | 高 | 可选（写 agent jar/示例配置） | 能改构建脚本，安装时会被重点提示 |
| `process:java` / `process:jps` | 高 | P4/P5 | 程序路径与哈希被钉住，JDK 升级要重新批准 |
| `ipc:<path>` | 高 | P6 | 仅本地字节流，绝不 TCP |

### 8.3 8 个子进程上限与空闲回收

- agent 在用户 JVM 内，不占 8 个 child；占的是 8 条 WebSocket。
- attach 驱动/`jps` 用 `process.run` 是一次性等待，不占常驻名额；用 `process.start` 才占。
- 插件空闲 300 秒被回收会丢弃实例句柄并关闭 WS/子进程（`crates/ide-host/src/plugins/mod.rs:2847-2860`；WS 资源随实例销毁属推断，见 §11 #2），所以“长时间挂机采集”必须设计重连 + agent 端补发，不能假设连接永存。

---

## 9 D. 从 weigram 能借鉴什么

1. `java.agent` + Java 8 字节码 + 可复现 jar：直接照搬。我们的 agent 已经是 `--release 8`（`sub-projects/sql-visualizer-agent/build.gradle.kts:2`），只需保证打包确定性与哈希稳定。
2. 用 `ide.commands` 的 `run.runContext` / `run.debugContext` 把用户引到 Application 类配置，是“让 agent 一定被注入”的可行交互；但不能精确指定模块/配置，只能作为右键入口。
3. 构建触发用 `build.buildModule`，手动 Maven 用 `process:mvn` + 终端，避免 5 秒预算；这套“IDE 动作优先、外部进程兜底”的分层可直接用于我们的“重跑/刷新”。
4. 它的诊断输出只打控制台，说明“控制台是给人看的”，不要在产品里承诺“自动读控制台”。
5. 不要借鉴 `agentmain` 注释里的 jcmd 说法；attach 必须走 `VirtualMachine.loadAgent` 或 jattach。

---

## 10 E. 与 IntelliJ 版的差距清单

| 能力 | IntelliJ 版 | OpenIDEA | 降级表达 |
| --- | --- | --- | --- |
| agent 注入范围 | premain + attach，覆盖运行/调试/测试/Maven/Gradle 等（现有实现有 attach 兜底） | 仅 Application / Spring Boot / JAR Application 的 premain；测试、Maven/Gradle、Tomcat、远程 JVM、运行目标、远程项目都没有 | 首次启用展示支持矩阵；状态栏显示“增强捕获：仅 Java 应用配置” |
| 已运行 JVM 接管 | `VirtualMachine.attach` + `loadAgent` | 无 attach API；需自带驱动 + `process:java` | 标记 Beta；引导重启为 Application 配置；二期做 attach |
| 控制台文本解析 | 反射 RunContentManager/ConsoleView 挂 filter，解析 MyBatis/Hibernate/ShardingSphere/iBatis2 | 完全读不到控制台；高亮规则只着色 | 明确“控制台捕获需开启 Save console output to file”；提供手动粘贴/文件导入 |
| 多控制台并行 | 每个 console 独立解析器 + agent 在线让位；多模块按日志行 pid 归属（`SqlLogConsoleBridge.kt:955-1016,1232-1239`） | 没有控制台文本，就没有“让位”问题；多 JVM 变成多条 agent 会话 | 若同时用 consoleFile 降级，需要按 SQL 指纹/pid 去重；否则重复行 |
| 测试场景 | 控制台解析可用；增强 agent 可 attach | 两者都没有；但插件自有 `framework="plugin"` 的测试配置能通过 `test-results.parse` 收到输出块 | 明确“测试默认仅手动粘贴/文件导入”；若投入，可把测试收编为插件自己的 test 配置 |
| Maven/Gradle Spring Boot | 通常由 IDE 的 Spring Boot 配置启动，agent 在 | `maven:spring-boot:run` / `gradle:bootRun` 是 Maven/Gradle kind，agent 不在 | 提供“转换为 Application 配置”指南；或引导在 VM options 手加 `-javaagent`（需插件把 jar 放到项目内） |
| 远程项目 | 插件与 agent 都在本地/目标机，按实现而定 | agent 在 server 侧不存在；consoleFile/attach 视 server 端能力 | 标注“远程项目不支持增强捕获” |
| 状态与排障 | 状态栏在线态、通道计数、suppressed 计数 | 可用 `set-status-item` 复刻在线态；无控制台计数 | 状态栏显示 agent 会话数/最后事件时间/降级原因 |
| 手动粘贴入口 | 已有日志还原工具（MCP `render_logs` 等） | 插件可用 `ui.input-multiline` + 自研解析器复刻 | 作为所有场景的保底入口，常驻面板 |

产品表达建议：

1. 不要承诺“所有运行方式自动捕获”。按“增强捕获（Application/Spring Boot/JAR）”与“日志导入（手动/文件）”两档呈现。
2. 首次启用向导检查：是否有 Application 类配置、是否授予 `java.agent`、是否开启 outputFile；按结果给出下一步。
3. Maven/Gradle 项目默认提示“当前配置类型不会注入 agent，是否改用 Application 配置/开启日志文件”。
4. 对测试场景只承诺“导入/粘贴”，把 attach 作为明确标注的实验能力。
5. 所有降级都要在状态栏可见，不要让用户以为“开了插件就一定会捕获”。

---

## 11 未解决 / 需上游确认

| # | 问题 | 影响 | 怎么验 |
| --- | --- | --- | --- |
| 1 | `wit/io.wit:93` 写回环 `ws://` “for tests”，实现 `websocket.rs:267` 却无条件允许；生产是否长期支持回环 WS | P1 主通道的合法性 | 向上游提 issue 确认；或在 0.1.51 真机做一次非测试回环 WS 连接并观察是否告警 |
| 2 | 插件空闲回收后 WebSocket 资源是否保留、入站帧能否重新激活插件 | 长挂机采集的连续性 | 真机：连 WS 后静默 6 分钟，再让对端发帧，看是否收到 `websocket-ready`；若收不到，确认 `on-schedule:60` 重连方案 |
| 3 | `files-changed` 是否覆盖 `.openidea/` 这类 ignored/隐藏目录 | P2/P3 的唤醒时延 | 真机在 `.openidea/sqlvis/` 写文件，观察事件；不覆盖则改用 60 秒轮询 |
| 4 | `project.read-file` 对正在增长文件的确切上限与一致性（16 MiB？读到半行？） | P2/P3 的尾读设计 | 真机写 20 MiB 文件读一次；写半行读一次 |
| 5 | 回环 WS 的 `net:127.0.0.1` 在安装对话框的实际措辞与用户接受度 | 授权转化率 | 打包最小插件实测安装页 |
| 6 | attach 驱动在只有 JDK 8 的机器上的可行路径（tools.jar / jattach / 原生二进制） | P4 覆盖面 | 在 JDK 8 目标机跑 `VirtualMachine.loadAgent` 驱动与 jattach 各一次 |
| 7 | 远程项目的运行输出文件、agent 跳过提示、server 侧 attach 可行性 | 远程产品口径 | 用 openidea-server 起一个远程项目，观察 run 控制台与 `.openidea/run.toml` 解析 |
| 8 | 上游是否计划提供 `run.output` 订阅或可读 log 标签页 | 能否去掉 outputFile 的用户手工步骤 | 查 `wit/run.wit` 后续版本与 release notes；提 feature request |

---

## 附录 证据索引

OpenIDEA 仓（`F:\code\Java\Github\other\openidea` @ `a7b67347c`）：

- `wit/run.wit:6-8`：`run.start` 只启动自有配置，回 `run-exited`
- `wit/events.wit:18-50`：事件订阅框架；`run-exited` 是宿主扩展事件，见 `crates/ide-plugin/src/types.rs:204`
- `wit/host.wit:58-81,122-132`：console-rule 与 `set-console-rules`；“plugin never sees the output”
- `wit/host.wit:232-236`：`language-servers.request` 拒绝 LSP 命名空间方法
- `wit/host.wit:248-256`：`output` 只写
- `wit/views.wit:146-187`：终端、log 标签页
- `wit/details.wit:13-20`：`process-log`
- `wit/io.wit:93-110,132-215,218-230`：WS、process、ipc
- `wit/workspace.wit:68,82,95,99-103`：`read-file`、`write-file`、`find-files`（跳过 ignored/excluded）
- `crates/ide-run/src/java_agent.rs:1-20`、`:17`：agent 注入范围
- `crates/ide-run/src/process.rs:40-62,358-373,430-433`：输出只走回调；outputFile tee
- `crates/ide-run/src/lib.rs:142-145,220-251`：outputFile 字段、OutputChunk/RunAttach
- `crates/ide-host/src/plugins/run_detectors.rs:161,187`：插件配置 kind = `plugin`
- `crates/ide-host/src/plugins/api.rs:3449-3461`：`run_configuration` 只认 `plugin:<id>:` 前缀
- `crates/ide-host/src/plugins/api.rs:2632-2664`：WS 连接入口
- `crates/ide-host/src/plugins/websocket.rs:262-270`：回环 ws 允许
- `crates/ide-plugin/src/types.rs:614-636`：回环 URL → `Net("127.0.0.1")`
- `crates/ide-host/src/mcp/run/agent_tools.rs:1-5,47-70,263`：`read_run_output` 只认 MCP AgentRun
- `crates/ide-host/src/mcp/run/configs.rs:737-740,815-824`：`execute_run_configuration` 的 output/fullOutputPath
- `java/jvm-inspect/README.md:1-10,30-34`：JDI 内省、挂起时可读
- `crates/ide-jvm-attach/src/socket.rs:92,157`；`listener.rs:38-43`：attach 协议内部实现
- `plugins/README.md:1120-1170,2309-2360,2478,2498-2543,1840-1875`
- `docs/zh/plugins/capabilities.mdx:49-58,74-78`
- `docs/zh/plugins/lifecycle.mdx:24`
- `docs/zh/guide/run.mdx:95,131`
- `src/features/run/ConfigForm.tsx:124-138,752-765`
- `src/features/run/application.ts:26-44`
- `src/features/run/javaDebug.ts:82`

weigram（`https://github.com/weigram/plugin-hotreload` @ 1.3.2）：

- `plugin.toml:22-27`：`[[contributes.javaAgents]]`、`vmOptions`、`jdk`
- `agent/src/com/hotreload/agent/HotReloadPremain.java:15-20`：premain/agentmain
- `agent/src/com/hotreload/agent/Agent.java:29-100`：HotSwapAgent + 自研回退
- `build.sh:4,41`：可复现打包、`--release 8`
- `src/lib.rs:123-150`：`run.runContext` / `run.debugContext`
- `src/lib.rs:160-250`：`build.buildModule`、`process:mvn` + 终端

我方现状（`F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer`）：

- `sub-projects/sql-visualizer-agent/build.gradle.kts:2,164`：Java 8 字节码、ASM shade
- `sub-projects/sql-visualizer-agent/src/main/java/.../transport/SenderLoop.java:14`：现有 loopback TCP 客户端
- `sub-projects/sql-visualizer-agent/src/main/java/.../AgentArgs.java:3-9`：`port=` 等参数
- `src/main/kotlin/.../log/agent/AgentManager.kt:45-46,826-841`：IntelliJ 版 `VirtualMachine.attach` + `loadAgent`
- `src/main/kotlin/.../log/SqlLogConsoleBridge.kt:950-1016,1232-1239`：控制台桥与多模块让位/pid 归属
