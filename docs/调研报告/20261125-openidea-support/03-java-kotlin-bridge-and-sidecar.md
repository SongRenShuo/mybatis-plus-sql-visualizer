# 03 Java/Kotlin 语义来源与 Sidecar 可行性

> 调研日期：2026-11-25。源码基线：`F:\code\Java\Github\other\openidea`，main `a7b67347c`（2026-11-25 11:22）。OpenIDEA 已发布 API 1.15（0.1.51），main 上 1.16 开发中。
> 本文只回答一件事：在没有 PSI、也没有跨语言语义模型的 OpenIDEA 上，我方「Wrapper→SQL / 实体字段 / JPA / XML 参数」所需的 Java、Kotlin 语义从哪里来，以及 engine sidecar 怎么落地。
> 结论分级：现成可做 / 变通可做 / 需上游 / 不可行。

## 0. 结论摘要

| 结论 | 内容 | 依据 |
|---|---|---|
| 硬约束 | 插件发不出 `textDocument/hover`、`textDocument/definition`、`workspace/executeCommand`。`lsp:<server>` 只允许「服务器自己的方法」，任何首段是 LSP 命名空间的方法一律 `invalid` | `crates/ide-lsp/src/plugin_requests.rs:11-38`；`wit/host.wit:229-243`；`plugins/README.md:979-990` |
| 硬约束 | `ide-index` 的符号只有「名字、kind、容器名、位置」；Java 无字段类型、无方法返回/参数类型、无泛型、无继承基类（`bases` 只对 Python 填） | `crates/ide-index/src/data.rs:104-122`；`crates/ide-language/queries/java/outline.scm`；`crates/ide-language/src/syntax/outline.rs:226-232` |
| 上游自陈 | 「类型解析与成员查询留给异步 API 2.0 模型」 | `plugins/README.md:914-916` |
| 推荐组合 | 即时路径（gutter/预览）= wasm 内 tree-sitter + `read-file`（含未保存内容）+ `symbols()` 补跨文件位置 + 缓存；精化路径（保存/打开后的后台分析、SQL 渲染）= JVM sidecar（jar + JDK）；全项目扫描 = 文本/索引，不进 provider | 本文 §6、§8 |
| sidecar 推荐 | 首选「插件 jar + 宿主已探测到的 JDK 17+」；上游若提供 JVM sidecar 能力则最稳。GraalVM native-image 次选（LGPL 与分发/授权代价高）。纯 Rust 重写是长期路线，不是首版 | 本文 §8、§10 |
| 复用率 | 2.6 万行核心引擎：63%（26,168 行）可零改动复用；替换 `Project` 形参后上限约 72-80%；约 11.6k 行（`psi/` 与 3 个 PSI 直连 converter）必须重写 | 08 分册 §0.2、§D；本文 §9 |
| Kotlin | 文本 + 编译产物 class 文件可覆盖常见 Wrapper/实体元数据；精确源码导航、未保存分析、跨语言重构「不可行」 | `java/kotlin-bridge/README.md:20-25`；本文 §7 |

对 00-README 基线事实的两处修正：

| 原表述 | 实际情况 | 证据 |
|---|---|---|
| 「Kotlin 来自 Maven 构建快照桥」 | OpenIDEA 的 Kotlin 代码智能来自 Kotlin LSP（`kotlin-lsp`，备选 fwcd `kotlin-language-server`、`kmp-lsp`）；`kotlin-bridge` 只是把编译后的 Kotlin 类放进 Java classpath，供 Java 侧看见 Kotlin 类 | `languages/kotlin.toml:15-44`；`docs/zh/languages/java.mdx:6,13,99` |
| 「`process:` 随包可执行必须用户加 PATH 或手动指向」 | 准确说是：`process:<name>` 在授权时解析绝对路径（绝对名直接采用，否则 PATH 查找），找不到就没有 pin；用户可在设置页为该 grant 手动指定可执行文件（全局或项目级），指定后按路径+内容哈希固定。包内相对路径不是解析来源 | `crates/ide-extension/src/ledger.rs:395-433`；`plugins/README.md:1897-1902` |

## 1. 事实基线

### 1.1 没有 PSI，也没有跨语言语义模型

OpenIDEA 文档写明：语义知识（类型、解析、引用）来自各语言自己的服务器；IDE 自己的索引与 tree-sitter 都是语法层；需要同时理解多种语言、按解析后类型匹配的结构化搜索「在这套架构上做不到」（`docs/zh/contribute/architecture/code-intelligence.mdx:83-89`）。Java 的深度由 JDT LS 加应用自有扩展包补（同文件 `:53`），Kotlin 由 Kotlin LSP 补。

这意味着我方的两条传统命脉在宿主侧都不存在：

- 没有 `PsiType` / `PsiMethod.resolve()` / `PsiClass.getSuperTypes()` 这类同步、可重入的类型查询。
- 没有 Java↔XML 的统一语义图；跨语言关联只能靠文本、索引、LSP 服务器各自回答。

### 1.2 插件能碰到的取数面

| 面 | 能做什么 | 不能做什么 | 证据 |
|---|---|---|---|
| `project.read-file` / `read-bytes` | 读取项目内文件；文本读取以未保存编辑器内容为准；单文件文本 16 MiB、字节 16 MiB | 不解析、不给类型 | `wit/workspace.wit:65-74` |
| `find-files(glob)` | 项目内 glob，遵守 `.gitignore` | 构建产物常被 ignore（`target/`、`build/`） | `wit/workspace.wit:94-95` |
| `search-text(regex)` | 项目内文本检索 | 纯文本，无语义 | `wit/workspace.wit` |
| `symbols(query, limit)` | 宿主内存索引的模糊符号搜索（CamelHump、容器限定）；名、kind、容器名、位置；不含未保存编辑；上限 1000 | 无类型、无签名、无继承、不能做语义解析 | `wit/workspace.wit:34-40,96-105`；`plugins/README.md:906-918` |
| `lsp:<server>` | 向正在运行的服务器发它自己的方法（如 `java/searchSymbols`、`java-analyzer/projectClasspath`） | 标准 LSP 方法全部被拦；不会为查询启动服务器 | `wit/host.wit:222-250`；`crates/ide-lsp/src/plugin_requests.rs:11-38` |
| `process:` / `process.start` | 启动已批准的可执行文件；stdin/stdout 流式；最多 8 个常驻；`run-captured`/`start-stream` 每流可到 128 MiB（普通 `run` 每流 4 MiB） | 程序需授权、pin、可信项目；stdin/stdout 只走 UTF-8 文本 | `wit/io.wit:129-207`；`plugins/README.md:363-382` |
| `java.agent` | 插件包内 jar 以 `-javaagent` 进入用户启动的 Application/Spring Boot/JAR | 只在这些运行配置；测试/Maven/Gradle/应用服务器/远程都不加载 | `docs/zh/plugins/capabilities.mdx:72-78` |
| `build.read` | Maven/Gradle 已解析依赖图（坐标、scope、status） | 不给 jar 绝对路径；Gradle 侧只有已读取配置 | `wit/workspace.wit:300-306` |

### 1.3 预算与生命周期

| 项 | 值 | 对设计的影响 | 证据 |
|---|---|---|---|
| 命令/事件/激活/停用 | 5 s 墙钟 | 不能同步等 JVM 冷启动或 JDT 导入 | `crates/ide-plugin/src/types.rs:470-492` |
| `tasks.begin` 后 | 60 s，每次 `report`/`is-cancelled` 续期 | 后台分析/全项目扫描只能走这里 | `wit/events.wit:60-72` |
| 编辑器 provider | 软 50 ms / 硬 500 ms | gutter、inlay、补全不能等 sidecar | `docs/zh/contribute/architecture/plugin-host.mdx`（另见 01 分册） |
| 线性内存 | 256 MiB | 不能把整个工程读进 wasm；只做单文件/小窗口 | 同上 |
| 实例空闲 | 300 s 回收 | 常驻 sidecar 会被杀掉，需可重启 | `crates/ide-plugin/src/types.rs:490` |
| 进程句柄 | 最多 8 个 | sidecar + 终端 + 其它工具共享预算 | `wit/io.wit:172-175` |
| 进程输出 | `poll` 每次 ≤1 MiB；未读保留最后 1 MiB | 大响应必须分块或落文件 | `wit/io.wit:160-170` |

## 2. `java/` 四个组件精读

### 2.1 共同形态：都是 JDT LS 的 OSGi delegate，只有宿主能发

四个组件不是插件，也不是 LSP 标准实现，而是挂在 JDT LS 上的 Eclipse OSGi bundle：

| 组件 | 加载方式 | 对外形态 | 谁调用 |
|---|---|---|---|
| `java-usages` | `org.eclipse.jdt.ls.core.delegateCommandHandler`；ide-sdk `include_bytes!` 安装为 `java-usages-1.18.0.jar` 并加入 JDT LS initialization bundles | `workspace/executeCommand` 的 `openidea.java.*` 命令 | 宿主 Rust（`ide-lsp`） |
| `kotlin-bridge` | 同上；jar 内 `KotlinLibraries`，另含 Maven lifecycle participant `IsolatedOutput` | `workspace/executeCommand` 的 `openidea.kotlin.replace` | 宿主 Rust（`ide-host/kotlin_bridge.rs`） |
| `jvm-inspect` | 同 bundle 机制，加载在 java-debug 旁边 | `openidea.jvm.*`（JDI、流追踪、异常断点） | 宿主 Rust（`ide-lsp/jdtls/*`） |
| `jdbc-bridge` | 不是 OSGi；独立 Java 17 jar，由 `ide-db` 以 `java -cp ... ai.openidea.jdbc.OpenIdeaJdbc` 启动 | 二进制帧协议（u64 id + u32 字段数 + 长度前缀 UTF-8） | 宿主 Rust（`ide-db`） |

证据：`java/java-usages/plugin.xml:3-35`；`java/java-usages/README.md:5,88-93`；`crates/ide-sdk/src/java.rs:1627-1635`；`java/kotlin-bridge/plugin.xml:3-6`；`crates/ide-host/src/kotlin_bridge.rs:22`；`java/jvm-inspect/plugin.xml:3-27`；`crates/ide-db/src/jdbc/mod.rs:225-270`。

关键点：这些 delegate 命令都只走 `workspace/executeCommand`（`crates/ide-lsp/src/jdtls.rs:836-857`），而插件发这条方法会被 `plugin_requests.rs` 判 `invalid`。因此四个组件对第三方插件全部不可达，除非上游另开口子。

### 2.2 `java-usages`：能力清单里没有「取类型/取签名」

`java-usages` 当前命令（`java/java-usages/plugin.xml:5-35`）：

| 命令 | 返回 | 是否可用于类型解析 |
|---|---|---|
| `openidea.java.usages` | `{location, kind, access}`；可选 `"sources"` 只搜源码 | 否，只给用法位置与读写分类 |
| `openidea.workspace.coveredFiles` / `coveredRoots` | 搜索范围内的候选 URI / 源根 | 否，范围判定 |
| `openidea.java.configuration` / `.warmup` | Spring YAML/properties ↔ Java 成员关联 | 否，且索引构建后丢弃 AST 与 binding（`CONFIGURATION.md:83-85`） |
| `openidea.java.implementations` / `implementationLocations` | 实现位置 | 否 |
| `openidea.java.hierarchy.members` / `.refactor` | 类的可移动成员标签、目标父/子类型；执行 Pull Up/Push Down | 只给成员标签与 kind，无类型、无签名；且绑定重构语义 |
| `openidea.java.exceptionType` | 光标处异常类的 `{qualifiedName, displayName}` | 否 |
| `openidea.java.refreshArchives` / `updateProjectConfigurations` / `generatedSources` | 类路径维护 | 否 |

`CONFIGURATION.md` 是 Spring 配置导航说明，明确「用 JDT binding 解析注解/方法身份」，但结果是导航关联，不对外提供类型查询（`java/java-usages/CONFIGURATION.md:3-8,291-320`）。它自己也写「snapshot 失效，不是全量增量 Java 语义索引」，AST/binding 在索引后丢弃（同文件 `:83-85`）。

因此，「给 `java-usages` 加一个取字段类型/方法签名的命令」是可行的上游方向（见 §10 E4），但今天不存在。

### 2.3 `kotlin-bridge`：Maven 快照桥，边界写得很清楚

- 只支持 Maven；每次保存后由宿主编排项目自己的 Maven 构建，把 Kotlin 输出目录作为 library 交给 JDT LS。
- 只发布 `SourceFile` 属性以 `.kt` 结尾的 class，以及 Kotlin module metadata；Java 输出不桥接。
- 覆盖主/测试 Kotlin API 与同 reactor 消费者。
- 明确声明：精确 Kotlin 源码导航、未保存分析、重构「不在这个桥里」。

证据：`java/kotlin-bridge/README.md:14-25`；`docs/zh/languages/java.mdx:99,149`。

注意，「Kotlin 代码智能」另有 Kotlin LSP（`languages/kotlin.toml:15-44`），但这个 LSP 对插件同样只能走 `lsp:kotlin-lsp` 的服务器自有方法；标准 hover/definition 被拦。

### 2.4 `jvm-inspect` / `jdbc-bridge`：运行时与数据库，不是静态语义

- `jvm-inspect` 在 java-debug 的 JDI 连接上工作，命令是实例列表、字段、referrer、流追踪、异常断点（`java/jvm-inspect/README.md:1-40`）。它要求 VM 暂停，且只对调试会话；不能用来解析源码。
- `jdbc-bridge` 是一个 JDBC 驱动代理：宿主用探测到的 JDK 17+ 启动 JVM，按二进制帧传 SQL/结果（`crates/ide-db/src/jdbc/mod.rs:225-270`；`crates/ide-sdk/src/java.rs:1637-1645`）。它对本章的价值不是能力，而是「宿主内部已经有一个成熟的 JVM sidecar 范式」——见 §8.3 与 §10 E1。

### 2.5 Rust 侧调用点

| 命令/能力 | Rust 调用点 |
|---|---|
| `openidea.java.usages` | `crates/ide-lsp/src/lib.rs:2153,2187` |
| `openidea.java.implementations` | `crates/ide-lsp/src/lib.rs:468,1824` |
| `openidea.java.configuration(.warmup)` | `crates/ide-lsp/src/jdtls/configuration.rs:15`；`crates/ide-lsp/src/jdtls.rs:645` |
| `openidea.java.hierarchy.*` | `crates/ide-lsp/src/jdtls/hierarchy.rs:13-14` |
| `openidea.jvm.*` / `stream.*` / `exceptions.set` | `crates/ide-lsp/src/jdtls/{jvm_inspect,stream_trace,exception_breakpoints}.rs` |
| `openidea.kotlin.replace` | `crates/ide-host/src/kotlin_bridge.rs:22` |
| `java-analyzer/projectClasspath` | `crates/ide-lsp/src/java_project.rs:12` |
| JDBC bridge 启动 | `crates/ide-db/src/jdbc/mod.rs:239` |

## 3. `lsp:<server>` 能力核实

### 3.1 白名单是「黑名单式」硬拦

`plugin_method_allowed` 用 14 个 LSP 命名空间做首段黑名单，再排除生命周期与 `$/`：

```text
textDocument workspace window notebookDocument callHierarchy typeHierarchy
completionItem codeAction codeLens documentLink inlayHint workspaceSymbol client telemetry
initialize initialized shutdown exit 以及 $/ 前缀
```

`crates/ide-lsp/src/plugin_requests.rs:11-38`；单元测试逐条钉死 `textDocument/hover`、`workspace/executeCommand` 等必须为 false（同文件 `:102-127`）。宿主入口还有二次校验（`crates/ide-host/src/plugins/api.rs:2472-2495`）。

此外：

- 方法名 ≤256 字节、params ≤1 MiB（`wit/host.wit:241-243`）。
- 需要 `lsp:<server>` + 受信任项目（`api.rs:2482-2488`）。
- 只为「已运行的服务器」服务，不会为查询启动服务器（`plugin_requests.rs:41-58`）。

### 3.2 那还能发什么

只有服务器自有命名空间。对 Java 而言：

| 服务器 | 插件可发的自有方法 | 能拿到什么 | 够不够做类型解析 |
|---|---|---|---|
| `jdtls` | `java/classFileContents`、`java/searchSymbols`、`java/getMoveDestinations`、`java/getRefactorEdit`、`java/buildWorkspace`、`java/projectConfigurations*` 等 | 反编译文本、源类型符号（名字/路径）、重构目标/编辑 | 不够。没有 hover/typeOf/成员类型；`java/searchSymbols` 在宿主侧只用于移动重构的源类型检索（`crates/ide-lsp/src/jdtls/move_refactoring.rs:56-64`） |
| `java-analyzer` | `java-analyzer/projectClasspath`、`java-analyzer/classFile` | 模块运行/测试 classpath + JDK home + 输出目录；按二进制名/归档条目定位源码文件 | 不够。但它是 Rust 原生、MIT、可扩展的一条捷径（见 §5、§10 E5） |
| `kmp-lsp` / `kotlin-lsp` | 未文档化 | 未知 | 待验证（推断）；即便有也不应作为产品依赖 |

### 3.3 结论

在现有 API 下，「借宿主正在跑的 JDT LS 做类型解析」是死的。唯一合法的 LSP 用途是：

- `java/searchSymbols` 做「按名字找源类型」的补充（与 `symbols()` 重叠，精度略高：JDT 的符号模型能区分类型 kind、给包名/容器名）。
- `java-analyzer/projectClasspath` 拿模块 classpath 与 JDK home（若用户把 java-analyzer 设为该项目的 Java 服务器）。

要拿 `textDocument/hover` 或 `workspace/executeCommand`，必须上游改 `plugin_requests.rs`（§10 E2）或提供专用的类型查询能力（§10 E4）。

## 4. `ide-index` 的符号模型纵深

### 4.1 数据管线

- 符号来自 tree-sitter outline 查询（`docs/zh/contribute/architecture/code-intelligence.mdx:57`）。
- 持久化在 redb；内存里用 `SymTable`/`HotFiles`/`HotDirs` 紧凑表；nucleo 做模糊匹配（`crates/ide-index/src/data.rs:1-9`；`store.rs:16`）。
- 查询是内存扫描，未保存编辑不更新索引（`plugins/README.md:906-912`）。

### 4.2 Java 的捕获面（全量）

`crates/ide-language/queries/java/outline.scm` 只捕获这些节点：

| 节点 | 捕获 | 存进索引的字段 |
|---|---|---|
| `class_declaration` / `record_declaration` | 类名 | name, kind, container, range |
| `interface_declaration` / `annotation_type_declaration` | 接口/注解名 | 同上 |
| `enum_declaration` / `enum_constant` | 枚举/枚举常量 | 同上 |
| `method_declaration` | 方法名 + `formal_parameters` 作为 `@detail` | name, kind, container, range；`@detail` 只用于大纲显示，索引不存 |
| `constructor_declaration` | 构造器名 + 参数 | 同上 |
| `field_declaration` / `constant_declaration` | 字段/常量名 | 同上；类型节点完全没捕获 |

落到索引结构体：

```rust
pub struct StoredSymbol {
    pub name: String,
    pub kind: SymbolKind,
    pub container: Option<String>,
    pub range: Range,
    pub bases: Vec<String>,   // 只有 Python 的 class 会填
}
```

`crates/ide-index/src/data.rs:104-122`。`container` 是外层类/对象的简单名；`bases` 的填充逻辑写死为 Python（`crates/ide-language/src/syntax/outline.rs:226-232`；`syntax/inheritance.rs:19-26` 明确「Only Python is supported」）。

`detail`（参数文本、类型文本）在大纲里存在（`syntax/outline.rs:141,223`），但 `SymbolTag`→`StoredSymbol` 的落库路径没有这个字段（`syntax/outline.rs:373-382`；`data.rs:104-122`）。

### 4.3 `symbols()` 的查询语义

- 输入 ≤256 UTF-8 字节，limit 上限 1000（`crates/ide-host/src/plugins/api.rs:2460-2469`）。
- 支持容器限定：`Foo.bar`、`Foo::bar`、`Foo#bar` 会被拆成（container=Foo, name=bar）（`crates/ide-index/src/matching.rs:519-529`）。
- 名字为空时只按容器过滤：`symbols("User.")` 会走 container=User、name 为空的分支，理论上可列出 `User` 的全部成员（名字、kind、位置），上限 1000。

这是本文里最有用的一条「索引能当成员查询用」的路径，但它是实现副产品，不是文档承诺的 API。标记：待验证（推断）。验证方法：在一个含 `class User { String name; int age; String getName(); }` 的夹具项目里调用 `symbols("User.", 100)`，看返回是否包含 name/age/getName 三条；再调用 `symbols("User.get", 100)` 看方法名过滤。若上游改 `split_symbol_query` 或空 needle 处理，此路径会失效，需要回归。

### 4.4 精度上限

| 问题 | 索引能否回答 |
|---|---|
| 这个类叫什么、在哪 | 能 |
| 这个类有哪些成员（名字/kind） | 能（容器限定查询，待验证） |
| `User::getName` 对应哪个字段 | 不能直接回答；只能给 `getName` 方法名，字段映射靠文本/命名规则 |
| 字段类型（`Long` / `List<Order>`） | 不能 |
| 方法返回类型、参数类型、重载选择 | 不能 |
| 父类/接口是谁、继承来的字段 | 不能（Java 不存 bases） |
| 泛型实参、类型变量替换 | 不能 |
| 注解及其属性（`@TableName`/`@TableField`） | 不能；要读源码文本 |
| Kotlin 属性/扩展函数 | 只能给名字与容器 |

所以索引的定位是：导航、去重、快速定位、成员清单；不是类型系统。

## 5. `java-analyzer`：OpenIDEA 的第二条 Java 语言服务器

### 5.1 是什么

- GitHub：`https://github.com/l0ng-ai/java-analyzer`，README 自述「A Java language server written in Rust. No JVM inside.」，MIT（README badge；仓库根目录没有 LICENSE 文件，GitHub API `license` 为 null，法律上待确认）。
- 能力：自己解析 Maven/Gradle 工程，自己做 parser、名称解析、类型推断、流分析；Java 8-25；Lombok；Spring；JDK/依赖类以数据形式读取，不执行；自带 `java-analyzer dap`（JDWP）调试器。
- 体积：release 资产 7.1-8.2 MB（`v0.3.7`，2026-11-24），六个平台（linux/macos/windows × x86_64/aarch64）。
- 冷启动：README 给的整工程检查时间 0.42-1.73 s（spring-petclinic / RuoYi 等，8 核 Linux）；OpenIDEA 侧为它留了 120 s 的导入等待（`crates/ide-lsp/src/java_project.rs:14-15`）。

### 5.2 OpenIDEA 怎么用它

- 作为 `jdtls` 之后的**可选/回退** Java 语言服务器（`languages/java.toml:75-82`；`docs/zh/languages/java.mdx:13,19`）。
- 0.1.49 引入（Release `v0.1.49`，2026-11-19，https://github.com/l0ng-ai/openidea/releases/tag/v0.1.49 ；PR #4420）。
- OpenIDEA 宿主目前只调用它的 `java-analyzer/projectClasspath`（运行/调试 classpath 与 JDK）；服务器另外实现了自有请求 `java-analyzer/classFile`（按 class 位置/二进制名找源码），宿主未用，但插件在 `lsp:java-analyzer` 下可直接发。

### 5.3 对插件意味着什么

两条完全不同的用法：

| 用法 | 前提 | 能拿到什么 | 代价 |
|---|---|---|---|
| A. 向「正在运行的」java-analyzer 发自有方法 | 用户把 java-analyzer 设为该项目 Java 服务器，且该进程在跑；`lsp:java-analyzer` | 今天只有 classpath/classFile；要类型必须上游加自有方法（§10 E5） | 无额外进程；但默认用户装的是 jdtls，此路经常 not-found |
| B. 插件自己把它当 sidecar 拉起，直接说标准 LSP | `process:java-analyzer` 授权 + 用户指定二进制；或上游提供捆绑可执行能力 | 完整 hover/definition/documentSymbol/typeHierarchy（因为此时**我们是 LSP 客户端**，不受宿主 `lsp:` 白名单约束） | 8 MB 二进制、重复导入工程、Maven 调用、进程授权与版本更新 |

B 是「变通可做」，但有两个硬伤：

1. 与 IDE 自己的 Java 服务器重复：如果用户用 jdtls，再起一个 java-analyzer 会重复导入、重复跑 Maven/Gradle，内存与 CPU 叠加。
2. 授权与分发：二进制不在插件包内时需用户自行安装并指定；在包内时受 §8.2 的 install 版本化与哈希重批约束。

因此 B 适合作为「用户显式开启的高精度模式」，不适合做默认路径。

## 6. 四条取语义路径（A）

### 6.1 总表

| 路径 | 能拿到什么 | 精度 | 延迟量级（假设） | 权限/体验代价 | 适用功能面 | 主要风险 | 档位 |
|---|---|---|---|---|---|---|---|
| 1. 宿主 LSP 查询 | 今天：`java/searchSymbols` 的源类型名/位置；`java-analyzer/projectClasspath` | 低（无类型） | 10-200 ms 热；冷导入 5-120 s | `lsp:<server>` 高风险 + 可信项目 + 服务器须在跑 | 找类、拿 classpath | 标准方法被拦；服务器未跑即 not-found | 不可行（做类型解析）；现成可做（找类/classpath） |
| 2. `ide-index` 符号 | 名字、kind、容器名、位置；可容器限定列成员 | 语法级，无类型 | 1-30 ms（内存扫描，1k-10k 文件） | `project.read`，低风险 | 导航、成员清单、跨文件定位 | 未保存编辑滞后；无类型/继承；重名歧义 | 现成可做 |
| 3. 自带 sidecar（JVM 或 native） | 取决于 sidecar：SQL 渲染 100%；Java 语义可达 JDT 级（JavaParser/JDT core） | 高（可做到类型/泛型/继承） | 冷 250-600 ms（JVM）/20-60 ms（native）；热 2-20 ms | `process:` 高风险 + pin + 用户指定/重批；可信项目 | 后台分析、预览命令、日志/XML 渲染、全项目扫描 | 分发/授权/JDK 依赖/LGPL/生命周期 | 变通可做 |
| 4. 纯文本 + wasm 内 tree-sitter（含 class 文件） | 语法树、链形状、方法引用文本、注解字面量、字段类型文本、编译产物里的签名/泛型/注解 | 中：结构对，语义靠命名与文本；class 文件可到「编译期真值」 | 单文件 1-10 ms；class 文件 1-5 ms/个 | `project.read`（+ 可选 `fs.read` 读依赖 jar） | gutter、即时预览、XML、PartTree/JPQL、实体注解 | 静默猜错；常量/变量/泛型/继承边界 | 现成可做 |

### 6.2 路径 1：LSP 查询

能做的两件小事：

- `java/searchSymbols`：params 形如 `{"query":"*","projectName":...,"sourceOnly":true}`（宿主用法见 `crates/ide-lsp/src/jdtls/move_refactoring.rs:56-64`）。返回源类型符号，用于「按名字找类」。它不是成员查询，也不返回类型。
- `java-analyzer/projectClasspath`：`{uri, scope}` → `{module, projectRoot, classPaths, outputDirs, jdkHome, languageLevel, encoding}`（实现见 java-analyzer 的 `crates/java-analyzer/src/project_classpath.rs`）。这对「拿到实体所在模块的编译输出目录/依赖 jar」很有用。

不能做的：hover、definition、typeDefinition、documentSymbol、prepareTypeHierarchy、typeHierarchy/*、workspace/symbol、executeCommand。全部在 `plugin_requests.rs:11-38` 被拦。

延迟：JDT 的 `COMMAND_TIMEOUT` 是 120 s（`crates/ide-lsp/src/jdtls.rs:43`），但插件的命令只有 5 s；所以即便上游开口，也必须给插件请求单独的小超时（例如 2-3 s），并在缓存里兜底。

### 6.3 路径 2：`ide-index` 符号

适用：

- 「`User` 这个类在哪几个文件」：`symbols("User", 50)`，按 kind=class 过滤，结合当前文件的 import/package 文本消歧。
- 「`User` 有哪些成员」：`symbols("User.", 1000)`（推断可用，见 §4.3）。
- 「这个项目里有没有 `BaseEntity`」：`symbols("BaseEntity", 20)`，用于跨文件继承的近似解析。

不适用：

- 字段/方法类型、泛型、注解值、继承链、重载。
- 未保存内容（索引滞后）；gutter 场景要另读当前文件。

延迟：内存扫描，预计 1-30 ms；但 `symbols` 是同步宿主调用，provider 里可用，前提是查询足够窄（不要 `symbols("a")` 这种宽查询）。

### 6.4 路径 3：自带 sidecar

这是唯一能把 Java 语义做到「接近 IntelliJ」的路径。两种引擎选型：

| 引擎 | 语言/许可 | 能给的语义 | 体积/启动 | 备注 |
|---|---|---|---|---|
| 我方现有 JVM 引擎（Kotlin + JSqlParser） | Kotlin / 我方；JSqlParser LGPL-2.1 | SQL 渲染、XML 动态 SQL、日志还原、反向、生成、性能规则；**不含** Java 语义 | jar 约 4-7 MB；冷 250-600 ms | 复用率最高（§9） |
| JavaParser + symbol solver | Java / LGPL-3.0 | Java 语法 + 名称/类型解析（需 classpath）；无 Lombok 生成成员 | jar 约 5-8 MB（core 1.44 MB + symbol solver + guava/javassist） | 可作为实体/字段语义引擎；LGPL 只适合独立 jar 进程 |
| java-analyzer | Rust / MIT（LICENSE 文件缺失，待确认） | 完整 Java 类型推断、继承、Lombok、Spring、调试 | 8 MB native；0.4-1.7 s 全工程检查 | 现成 LSP，自己拉起即可用标准方法 |
| Eclipse JDT core（非 LS） | Java / EPL-2.0 | 编译器级 AST/binding | 数十 MB；需 JDK | 与 JDT LS 同源，内部 API 兼容风险高 |

推荐组合见 §6.7。

### 6.5 路径 4：纯文本 + tree-sitter（含编译产物）

这是首版的主力，不是「凑合」。理由：

- 官方 MyBatis Navigator 就是纯文本实现：`parse_java`（手写扫描器，跳过注释/字符串/注解参数）与 `parse_xml`，配合 `find_files`/`search_text`/`read_file`，1,700 行覆盖 mapper↔XML 双向导航、gutter、id 与 `#{}` 补全，能力只需 `project.read`（`plugins/official/mybatis/src/lib.rs:147,592`；`plugin.toml:15-16`）。
- 插件可以在 wasm 内自带 tree-sitter 语法：`tree-sitter-java`（0.23.5，1,490 万下载）、`tree-sitter-kotlin`（0.3.8，44.9 万下载）都是可编译依赖；Rust 侧是 API，运行时是 C，wasm32-wasip2 下需要 clang/cc 工具链（待验证；OpenIDEA 的 wasm 插件能否编 C 依赖尚未见先例）。
- `read-file` 取到的是未保存内容（`wit/workspace.wit:65-68`），所以「边打字边出 gutter」在文本路径上是可行的；索引做不到这一点。

四个可落地的子能力：

1. **链形状**：tree-sitter 找 `new LambdaQueryWrapper<User>()`、`Wrappers.<User>lambdaQuery()`、局部变量声明与后续 `.eq(...)` 调用；参数表达式保留文本与范围。
2. **方法引用→字段**：`User::getName` / `User::name` 文本直接给类名与方法/属性名；按 `get`/`is` 前缀 + `@Accessors(fluent/prefix)` 规则映射到字段名；读该类源码确认字段存在与注解。
3. **实体注解**：读实体源文件，解析 `@TableName`、`@TableField`、`@TableId`、`@TableLogic`、`@Version`、`@OrderBy`、`@Accessors` 以及 `mybatis-plus.global-config.db-config.*`（application.yml/properties）。
4. **继承**：文本读 `class User extends BaseEntity` / `implements`，用 `symbols("BaseEntity")` 定位父类文件，递归收集字段；跨 jar 的父类走 class 文件（下条）。

补充子路径：编译产物 class 文件。

- `project.read-bytes` 可以读项目内 `target/classes/**/*.class`、`build/classes/{java,kotlin}/main/**/*.class`（16 MiB 上限，`wit/workspace.wit:69-74`）；`find-files` 因 `.gitignore` 可能看不到它们，所以按「源码路径 + package → 输出目录」推导候选，再直接 `read-bytes`。
- wasm 内用 `classfile-parser`（0.3.8，3.3 万下载）或 `cafebabe`（0.9.0，7.2 万下载）解析常量池、`Signature`、`RuntimeVisibleAnnotations`、`super_class`/`interfaces`，可拿到字段真实类型、泛型签名、注解（含 Kotlin 属性注解落到 field 的情形）、父类/接口。
- 这是 Kotlin 实体元数据最靠谱的静态来源（§7.3），也是 Java 跨 jar 父类的来源。
- 代价：构建产物可能缺失或过期；Gradle 输出目录需读 `build.gradle(.kts)` 推断；依赖 jar 在 `~/.m2`/`~/.gradle`，要读需 `fs.read` 授权（`fs.read` 支持 `~/`，`wit/fs.wit:6-12`）。

### 6.6 一条不推荐的野路子：借 IDE 动作读 LSP 结果

`command-runner.execute("GotoDeclaration")` + 读当前编辑器/选区，理论上可以把一次 LSP 导航的结果「问」出来（`wit/host.wit:204-213`；`ide.commands` 高风险）。问题：

- 会移动用户光标、打开文件、抢焦点，只能在窗口命令/事件里跑。
- 每次一个符号，批量分析不可行。
- 依赖 IDE 动作 id 稳定，属于未文档化耦合。

结论：仅可作为用户显式点击「从光标解析」的一次性兜底，不作为架构。

### 6.7 推荐组合（分工矩阵）

| 功能面 | 首选 | 兜底 | 说明 |
|---|---|---|---|
| gutter / 行标记 / inlay | wasm tree-sitter 解析当前文件 + 实体元数据缓存 | 解析失败则不出标记 | 50 ms 软预算；缓存键 = 文件路径 + 内容哈希 + 实体哈希 |
| 光标处预览命令（5 s） | 缓存命中直接用；未命中先文本解析 + 索引/源码读实体；必要时同一次命令内唤醒 sidecar 并等 1-2 s | 超时则给「结构预览 + 未解析参数占位」 | 不让用户看到空白 |
| 实体/字段/表名 | 源码文本（含未保存）+ `symbols` 跨文件定位 + 继承递归 | class 文件（编译产物）/ sidecar | Java 与 Kotlin 同一套 |
| Wrapper 参数值类型 | 字面量与 `new` 表达式文本 | 未知则 `?` 占位（沿用现有行为） | 不猜类型 |
| XML 动态 SQL | 现有 `xml/` 纯逻辑（sidecar 或移植） | 无 | 与 PSI 无关 |
| mapper↔XML 关联 | 文本（参照官方 MyBatis Navigator） | `documentIndexer` 注册定义/别名 | 不重造导航 |
| JPA 派生查询/JPQL/HQL/@Query | 文本 + 实体元数据 | sidecar（别名解析） | 见 §9.2 |
| JPA Criteria/Specification/QBE | sidecar 精确类型 | 降级为「不支持，明确提示」 | 无类型推断必错 |
| 全项目扫描 / 批量 | `tasks.begin`（60 s）+ 索引 + 文本 | sidecar 后台 | 绝不进 provider |
| SQL 渲染 / 日志还原 / 反向 / 性能 | JVM sidecar（现成引擎） | 纯 Rust 重写（长期） | §8 |

一句话：导航交给宿主与索引，结构交给 wasm 文本解析，真值交给 sidecar，缓存负责把三者拼起来。

## 7. Kotlin 能做到什么（B）

### 7.1 先修正事实

- Kotlin 代码智能在 OpenIDEA 是有的：`kotlin-lsp`（JetBrains）优先，`kotlin-language-server`（fwcd）与 `kmp-lsp` 回退（`languages/kotlin.toml:15-44`）。
- 但插件问不到它的标准方法（同 §3 的黑名单）。JetBrains Kotlin LSP 是否提供可用的自有命名空间方法，未文档化；即使有，也不适合作为产品依赖（待验证）。
- `kotlin-bridge` 解决的是反方向：让 **Java 代码看见编译后的 Kotlin 类**，Maven-only，且明确不做源码导航/未保存分析/重构（`java/kotlin-bridge/README.md:20-25`）。

### 7.2 Kotlin 的支持矩阵

| 能力 | 能否做到 | 做法 | 做不到的部分 |
|---|---|---|---|
| 定位 Kotlin 里的 Wrapper 链 | 能 | tree-sitter-kotlin 解析 `LambdaQueryWrapper<User>()` / `KtQueryWrapper` / `Wrappers.lambdaQuery<User>()` / 扩展函数 `lambdaQuery()` 的调用形状；`read-file` 拿未保存内容 | 扩展函数来自哪个库、接收者真实类型（需要语义） |
| `User::getName` / `User::name` → 字段 | 大部分能 | 文本取类名与引用名；Kotlin 属性名直接是字段名；`getX` 规则兼容 Java getter | 计算属性、委托属性、`@JvmName`、重载 getter |
| 实体类注解 | 能 | 读源码 `@TableName` / `@TableField` / `@Accessors`；注意 Kotlin use-site target（`@field:` / `@get:`） | 注解落到 getter 而非 field 时，文本规则需按注解 `@Target` 判断；复杂场景要 class 文件 |
| 字段类型/泛型 | 编译产物能做到 | 读 `target/classes`/`build/classes/kotlin/main` 的 class 文件，解析 `Signature` 与注解 | 未构建时没有；构建产物过期会给出旧类型；Gradle 输出路径需推断 |
| 继承字段 | 能近似 | 文本 `: BaseEntity()` + `symbols` 找父类 + 递归；或 class 文件 `super_class` | 跨模块/跨 jar 的父类要读依赖 jar（`fs.read`）；Kotlin 委托/接口默认实现不覆盖 |
| Kotlin 类型别名 / 内联类 | 不能 | — | `typealias` 展开、value class 拆箱需要语义 |
| 未保存的 Kotlin 语义分析 | 不能 | — | 文本可以看未保存内容，但没有类型检查 |
| 从 Java 跳到 Kotlin 源码、跨语言重构 | 不能 | — | kotlin-bridge 明确排除（`README.md:24-25`） |
| JPA 注解在 Kotlin 上的继承/覆盖 | 部分 | 文本 + class 文件 | Kotlin 属性覆盖、open/final、`@MappedSuperclass` 组合需要类型 |

### 7.3 Kotlin 编译产物路线（推荐加入首版）

1. 由源码路径推出 class 路径：`src/main/kotlin/a/b/C.kt` → `build/classes/kotlin/main/a/b/C.class`（Gradle）或 `target/classes/a/b/C.class`（Maven）；输出目录从 `build.gradle(.kts)` / `pom.xml` 文本读取覆盖。
2. `project.read-bytes` 读 class；wasm 内解析：
   - `this_class` / `super_class` / `interfaces` → 继承；
   - 每个 `field_info` 的 descriptor + `Signature` → 字段类型与泛型；
   - `RuntimeVisibleAnnotations` → `@TableField` 等（Kotlin 默认注解目标可能落在 field 或 getter，两个都扫）。
3. 结果缓存键加入 class 文件 mtime/size；源码保存后 class 未更新时，用源码文本的「疑似变更」降级并提示「类型来自上次构建」。

限制要写进产品文案：Kotlin 精确语义依赖一次成功构建；未构建/构建失败的模块只给文本推断。

## 8. 三条 sidecar 方案对比（C）

### 8.1 对比表

假设：单机 4 核 / 16 GB / JDK 21；工程 50 模块、2 万 Java 文件；单次请求为一次 Wrapper/XML 渲染（输入 <200 KB）；发布覆盖 Windows x64、macOS arm64/x64、Linux x64（arm64 另算）。

| 维度 | (a) GraalVM native-image 单文件 | (b) jar + JDK（`process:java`） | (c) 纯 Rust 重写到 wasm |
|---|---|---|---|
| 分发物体积 | 每平台 25-45 MB × 4-6 平台（引擎类 3-5 MB + JSqlParser 1.26 MB + Kotlin stdlib 1.69 MB，native image 通常为 jar 的 1.5-3 倍） | 一套 4-7 MB（引擎 2-4 MB + JSqlParser 1.26 MB + Kotlin stdlib 1.69 MB），平台无关 | wasm 模块增加 2-5 MB（tree-sitter 语法 + 解析器/渲染器代码）；无外部文件 |
| 构建链 | 每平台各自构建（Windows 需 MSVC；GraalVM 不支持交叉编译）；反射/资源需配置；CI 6 条腿，单腿 5-15 min | Gradle 一次产出；CI 1 条腿 | 现有 Rust wasm 构建；无新链 |
| 冷启动 | 20-60 ms | 250-600 ms（JVM + 类加载；加 CDS/AppCDS 可降到 150-300 ms，待验证） | 随插件实例化一次，之后 0 |
| 单请求延迟 | 1-10 ms | 2-20 ms（热） | <1-5 ms |
| 内存 | 40-120 MB | 150-350 MB（JVM 基线；`-Xmx` 可控） | 受 256 MiB 插件上限约束；单文件分析可行，全工程不可行 |
| JDK 依赖 | 无 | 需要 Java 17+（当前 `jvmToolchain(17)`，`build.gradle.kts:245`；JSqlParser 5.3 自身只需 11） | 无 |
| 权限与授权 | `process:` 高风险；可执行必须用户指定（PATH 或设置页选文件）；install 目录按版本分层，路径每次更新变化，哈希每次发版变化 → 每次更新都要重新批准 | 只需 `process:java`：pin 的是 `java`，插件版本更新不触发重批；JDK 小版本升级可能触发一次重批；若 PATH 上的 java 是 8/11，需要用户指定 17+ 的 java.exe | 只需 `project.read`，无进程授权 |
| 用户体验 | 最差：找文件、选路径、每次更新重批 | 中：一次授权；java 不在 PATH/版本太旧时要指导 | 最好：装插件即用 |
| 许可 | JSqlParser LGPL-2.1 静态链接进单文件，LGPL §6 的「可重新链接」难以满足，需法律意见或换解析器 | 独立 jar + classpath 动态链接，LGPL 合规简单（保留 jar、可替换、附许可与来源） | sqlparser-rs Apache-2.0；tree-sitter MIT；无 copyleft |
| 方言覆盖 | 与 jar 相同（JSqlParser） | 与 jar 相同（JSqlParser） | `sqlparser-rs` 有 MySQL/PostgreSQL/MsSQL/Oracle/ANSI/Generic 等 16 个方言（`src/dialect/*.rs`，另含 `mod.rs`），**无 DM/达梦、OceanBase、Kingbase、HighGo、PanWeiDB**；Oracle 有 `CONNECT BY`，但分页 `ROWNUM`、DM 专有语法需实测 |
| 动态 SQL | 现有 `xml/`（6.2k 纯行） | 同左 | 无成熟 crate：`batis-xml` 0.1.1（60 下载，许可未标）、`xbatis2sql` 0.2.8（1.7 万下载，许可未标）；都要自研或重写 |
| 代码复用 | 63-80% | 63-80% | 0（测试当规格） |
| 工期（粗估） | 2-4 人周（构建/配置/授权/CI/测试） | 2-4 人周（拆模块/协议/打包/错误处理） | 3-6 人月（sql 9.4k + xml 6.2k + converters + 测试迁移） |
| 档位 | 变通可做（代价高） | 现成可做（工程最稳） | 变通可做（长期） |

已否决的两个「编译到 wasm」念头：

- TeaVM/Bytecoder 把 Java 字节码转 wasm：JSqlParser 的 JavaCC 生成解析器与反射用法风险高，Kotlin 运行时也不完整。不可行。
- Kotlin/Native 复用 JVM 引擎：Kotlin/Native 不能用 JVM 库（JSqlParser）。不可行。

### 8.2 授权体验的源码级核实

这是三条方案里最容易低估的部分，逐条钉死：

1. `process:` 的 pin 是「文件路径 + 内容哈希」，每次启动前重查；文件变了就拒绝，直到用户重新批准（`plugins/README.md:374-382`；`crates/ide-extension/src/ledger.rs:356-382`）。
2. 授权时的解析：`resolve_program` 对绝对路径直接采用（存在即可），对相对名走 `PATH`，找不到就报错；`approve_program` 要求绝对路径且可执行（Unix 有 x 位；Windows 只要求是文件）（`ledger.rs:395-433`）。
3. 用户可以在 设置 › 插件 里为某个 `process:` grant 指定可执行文件，全局或项目级；指定后同样按路径+哈希 pin（`plugins/README.md:1897-1902`；`crates/ide-host/src/plugins/mod.rs:1231-1260`）。
4. 安装目录是版本化的：`<plugins>/<id>/<version>/` + `active` 指针（`crates/ide-host/src/plugins/install.rs:2-6`）。所以：
   - 指向 install 目录的 pin，每次插件更新路径就变了；
   - 指向 `plugin_dir()`（`<app-data>/<id>/files`，稳定）的 pin，路径不变，但二进制内容变了仍要重批；
   - 官方没有「包内可执行」声明，唯一相近的先例是 `[[contributes.javaAgents]]`：manifest 里写包内相对路径，宿主任 install 目录解析、记哈希、拒绝 `..`/绝对路径/符号链接逃逸，更新后显示「Approve new jar」（`plugins/README.md:2504-2530`；`crates/ide-host/src/plugins/java_agents.rs:186-192`；`crates/ide-host/src/plugins/mod.rs:3179-3184`）。
5. `process.start` 未命名项目时，宿主把 cwd 设为插件数据目录（`crates/ide-host/src/plugins/api.rs:1432-1438`），所以 sidecar 的 cwd 可控，不必污染项目根。
6. 插件与 sidecar 之间只能走 UTF-8 文本：`child.write(text: string)`、`poll` 返回 `string`，不完整字符会等待下一次读取（`wit/io.wit:160-189`）。宿主自己的 JDBC bridge 用的是二进制长度前缀帧（`crates/ide-db/src/jdbc/mod.rs:288-300`），插件 API 复刻不了。所以协议必须是 JSONL 或 base64。

### 8.3 推荐

推荐 b（jar + JDK）作为默认，理由按权重：

| 权重 | 理由 |
|---|---|
| 复用 | 现有引擎原样打包即用，63-80% 复用；native-image 只是同一套代码的另一种打包，收益主要是启动 |
| 授权 | 只 pin `java`，插件每次发版不需要用户重新批准；这是与 native 方案的决定性差异 |
| 许可 | LGPL 的 JSqlParser 在独立 jar + classpath 下合规成本最低 |
| 分发 | 一套产物覆盖三平台；不需要 MSVC/GraalVM/交叉编译 |
| 风险 | JDK 版本是唯一硬依赖；用启动握手 + `java -version` 检查 + 明确错误文案可把失败变得可解释 |

落地形态：JSONL over stdin/stdout；`process.start` 常驻；空闲 300 s 被宿主回收后下次请求自动重启；首次请求失败重试一次并给出「需要 Java 17+」的提示。

协议草案（不写实现）：

```json
{"id":1,"method":"hello","params":{"client":"mpsv","protocol":1}}
{"id":2,"method":"render","params":{"model":{...},"dialect":"mysql","params":{"1":"x"}}}
{"id":3,"method":"renderXml","params":{"xml":"<mapper...>","params":{"id":1},"dialect":"postgresql"}}
{"id":4,"method":"renderLogs","params":{"text":"...","format":"mybatis"}}
{"id":5,"method":"reverse","params":{"sql":"select ...","dialect":"dm"}}
{"id":6,"method":"generate","params":{"spec":{...}}}
```

大响应（日志还原、超大 XML）走文件：sidecar 写 `plugin_dir` 的临时文件，stdout 只回 `{ "file": "...", "bytes": n }`，插件再 `fs.read-file`（plugin_dir 免授权，`wit/fs.wit:17-20`）。

演进顺序：

1. 先做 b，覆盖 SQL/XML/日志/反向/生成；Java 语义仍走文本。
2. 若用户调研显示「没有 JDK / JDK 太旧」比例高，再做 a；同时处理 LGPL 或把解析器换成 sqlparser-rs。
3. 长期评估 c：只重写 `sql/` 渲染 + `xml/` 动态 SQL，解析类功能（perf/schema/rollback）可继续留在 sidecar，或换 sqlparser-rs（注意 DM 缺口）。

## 9. 我方 2.6 万行核心引擎的复用率（D）

### 9.1 包级矩阵

数据来自 08 分册 §0.2（纯逻辑按 `import com.intellij.*` 判定）；本文按推荐方案 b 重新归类。

| 包 | 纯/总行数 | 处置 | 说明 |
|---|---:|---|---|
| `model` | 1,780 / 1,780 | 原样 | 纯 DTO；JSON 化即跨进程契约 |
| `sql`（含 `extract`/`interceptor`/`perf`/`rollback`/`schema`） | 9,370 / 11,365 | 原样（82% 纯；子包 perf/extract/rollback/schema 接近全纯） | 渲染/方言/性能/回滚/校验；只依赖 JSqlParser 与 JDK |
| `xml` | 3,634 / 4,388 | 原样（20/23 文件） | 3 个文件用 PSI XML（`XmlInlineSqlDetector`、`XmlDirectText`、`SqlMapNamespaceIndex`），用 DOM/text 重写 |
| `xml/ibatis` | 2,601 / 3,158 | 原样（9/12） | 同上 |
| `reverse` | 3,213 / 3,525 | 原样 | 6/7 纯 |
| `generator` | 352 / 352 | 原样 | |
| `util` | 1,953 / 2,909 | 原样（12/23） | 纯工具；其余按需切分 |
| `converter` | 1,044 / 5,317 | 大部分机械改 | 15 个文件里 12 个只 import `Project`（表名设置）；3 个直连 PSI（`EasyQueryConverter` 851、`MdsConverter` 490、`QuerydslConverter` 595）需重写 |
| `converter/jpa` | 2,013 / 7,417 | 一半机械改、一半重写 | 7 个 PSI 直连文件合计 3,543 行（`JpaAliasTableResolver`、`JpaCrudOperationConverter`、`JpaFromEntityMapper`、`JpaJoinOnResolver`、`JpaQueryEnhancer`、`QueryByExampleConverter`、`SpecificationQueryConverter`）；其余是 PartTree/JPQL/HQL 等纯解析 |
| `log`（纯文件） | 8,566 / 25,806 | 原样 | 需按类切分，UI 相邻类不搬 |
| `mcp`（纯文件） | 1,635 / 6,203 | 业务函数原样、壳重写 | OpenIDEA 用 `contributes.mcpTools` 暴露工具，不搬 MCP 协议壳 |
| `agent`（子项目 Java） | 8,054 | 原样 | 独立 jar；宿主换成 `java.agent` 注入，协议不变 |
| `psi` + `psi/jpa` | 497 / 14,206 | 重写 | 输入是 `PsiElement`；必须换成「源码/编译产物 → `WrapperChain`/`TableInfo`」前端 |
| `ui`（含全部子包） | 3,902 / 47,465 | 不移植 | 平台 UI/EP；判定逻辑可抽纯函数搬 |
| `execute` | 2,875 / 14,195 | 不移植 | IntelliJ Database 控制台、EDT/写意图；DB 能力见 04 分册 |
| `config` / `actions` / `update` / `onboarding` / `feedback` / `context` | 纯逻辑占比很低，合计约 3 万行 | 不移植 | 设置页/Action/更新/引导/通知/Project 模型全部按 OpenIDEA 重写 |

### 9.2 复用数字

- 核心 16 包 41,583 行：26,168 行（63%）零改动可编译进 sidecar。
- 替换 `Project` 形参为一个小型 `TableNameConfig` 接口后，`converter` 与 `converter/jpa` 中非 PSI 的约 7,238 行可复用（其中一部分仍调用 PSI 辅助类，需逐文件确认）。
- 上限约 33.4k / 41.6k = 80%；实际按 72-80% 估。
- 必须重写：`psi` 14,206 + 3 个 converter 1,936 + JPA 7 个 PSI 文件 3,543 ≈ 19.7k 行；其中 `EntityResolver`（719）与 `JpaEntityResolver`（846）的规则可直接照抄，只是输入从 `PsiClass` 换成源码模型。
- 删除/不搬：`ui` 47.5k、`execute` 14.2k、`config/actions/update/onboarding/feedback/context` 约 30k。

结论一句话：渲染与 SQL 侧几乎白拿；真正要重写的是「源码 → 中间模型」的 2 万行前端；UI 全部按 OpenIDEA 重做。

## 10. 给上游的 PR 建议（E）

按性价比排序（收益/改动面/被接受概率）：

| 序 | 建议 | 落点 | 收益 | 成本 | 风险 |
|---|---|---|---|---|---|
| 1 | 插件 JVM sidecar 能力（声明式 jar + 宿主选 JDK，复用 JDBC bridge 范式） | `wit/host.wit`（新 interface）、`crates/ide-host/src/plugins/api.rs`、`crates/ide-sdk/src/java.rs`、`crates/ide-db/src/jdbc/mod.rs` 抽公共 Bridge、`crates/ide-extension/src/{manifest,capability}.rs`、`plugins/README.md` | 彻底解决 JDK 发现、进程授权、每次发版重批；我方 jar 直接跑 | 中（宿主侧 300-600 行 + WIT + 测试） | 安全评审：等于给插件一个 JVM；需明确「jar 来自包内、哈希固定、不能带外部 classpath」 |
| 2 | 只读 LSP 方法白名单 | `crates/ide-lsp/src/plugin_requests.rs`（把命名空间黑名单换成 allowlist）、测试同文件、`wit/host.wit:229-243` 文案、`plugins/README.md:979-990` | 一行改动打开 hover/definition/typeDefinition/documentSymbol/typeHierarchy；对全部语言的插件都有价值 | 小（1 个函数 + 文档 + 测试） | 被拒概率高：担心插件滥用服务器、拖慢、读未保存内容；需要请求级超时与并发限制 |
| 3 | 索引符号带语法类型（`detail`） | `crates/ide-language/queries/java/outline.scm`（补 `type:` 捕获）、`crates/ide-language/src/syntax/outline.rs`（detail 已有）、`crates/ide-index/src/data.rs`（StoredSymbol 加 detail + SCHEMA 递增）、`store.rs`、`matching.rs`/`lib.rs`、`crates/ide-plugin/src/types.rs`、`wit/workspace.wit:34-40`、SDK、released 快照 | 字段类型/方法返回/参数表以文本形式可得，覆盖 60-70% 实体字段场景，无需进程 | 小-中（WIT record 加字段是破坏性变更，需按 API 版本策略发 1.17/2.0） | 只是语法文本，不解析 FQN/泛型；需在文档里避免让插件误以为是类型系统 |
| 4 | `java-usages` 增加窄接口语义命令 + 宿主暴露 | `java/java-usages/src/.../SemanticCommand.java`（新）、`plugin.xml`、`MANIFEST.MF`（版本号递增）、`crates/ide-lsp/src/jdtls/semantic.rs`（新，仿 `hierarchy.rs`）、`crates/ide-host/src/plugins/api.rs`、`wit/host.wit`（新 interface `java-semantics`）、`crates/ide-plugin/src/{runtime,wit_host,types}.rs`、`crates/ide-sdk`、`tests/api/released` | 对 jdtls 用户给出真正的类型/成员查询；安全面窄（只读、固定返回形状） | 大（OSGi + Rust + WIT + SDK 全链路） | JDT 内部 API 随版本漂移；需要像 `java-usages` 那样钉 fingerprint 与回退 |
| 5 | java-analyzer 增加自有语义请求 | `crates/java-analyzer/src/lsp_ext.rs`（请求类型）、新 `semantic.rs`（复用 `crates/ide/src/{hover,navigation,structure}.rs`）、`global_state.rs`（分发）、文档 | 不改 OpenIDEA；对使用 java-analyzer 的用户即插即用；Rust 侧类型信息完整 | 中（一个 crate 内） | 只覆盖把 java-analyzer 设为 Java 服务器的用户；许可文件缺失需先补 |
| 6 | 允许插件按需启动语言服务器 | `crates/ide-host/src/plugins/api.rs`、`crates/ide-lsp`（启动/等待就绪）、`wit/host.wit` | 让 `lsp:<server>` 不依赖「用户已打开 Java 文件」 | 中 | 资源与信任模型：插件可拉起重型服务器 |

### 10.1 接口草图

建议 1（JVM sidecar，最优）：

```toml
# 插件清单
engines.openidea = "^1.17"

[[contributes.jvmTools]]
id = "engine"
main-class = "cn.song.mpsv.sidecar.Main"
classpath = ["engine/mpsv-engine.jar", "engine/jsqlparser-5.3.jar"]
min-java = 17
```

```wit
interface jvm-tools {
    use types.{error};
    use workspace.{project};
    use process.{child};
    /// 启动声明的 JVM 工具；jar 必须全在插件包内，宿主自行选 JDK 17+。
    start: func(project: option<borrow<project>>, id: string) -> result<child, error>;
}
```

实现落点：`ide-sdk::java::installed_jdks()` / `java_runtime(&jdks, 17)`（`crates/ide-sdk/src/java.rs:90-92,257-263`）、`install_bundle`（`crates/ide-sdk/src/java.rs:1637-1645` 的 `jdbc_bridge_jar` 即调用它）、`ide-db` 的 Bridge 子进程/帧循环（`crates/ide-db/src/jdbc/mod.rs:225-300`）抽成公共模块 `crates/ide-host/src/plugins/jvm_tool.rs`。

建议 2（LSP 只读白名单）：

```rust
/// 只放行只读、不改变项目、不参与文档同步/生命周期的标准方法。
const READ_ONLY: &[&str] = &[
    "textDocument/hover",
    "textDocument/definition",
    "textDocument/typeDefinition",
    "textDocument/declaration",
    "textDocument/documentSymbol",
    "textDocument/prepareTypeHierarchy",
    "typeHierarchy/supertypes",
    "typeHierarchy/subtypes",
    "workspace/symbol",
];
pub fn plugin_method_allowed(method: &str) -> bool { /* 原自有方法 + READ_ONLY */ }
```

必须同时给插件请求一个短超时（例如 3 s），并保持「永不启动服务器」的语义。

建议 3（索引 detail）：

```wit
record symbol-info {
    name: string,
    kind: string,
    container: option<string>,
    detail: option<string>,   // 新增：字段类型 / 方法签名 / 参数表，原文
    location: location,
}
```

Java query 增加：

```scheme
(field_declaration type: (_) @detail declarator: (variable_declarator name: (identifier) @name)) @def.field
(method_declaration type: (_) @return_type name: (identifier) @name parameters: (formal_parameters) @params) @def.method
```

索引只需存拼接后的 `detail` 文本，不做解析。

建议 4（jdtls 语义命令）最小接口：

```text
openidea.java.semantic.typeOf(uri, line, column)
  -> {type: "java.util.List<com.acme.User>", erasure: "java.util.List", resolved: true}

openidea.java.semantic.members(fqcn)
  -> [{name, kind, type, signature, annotations: [{name, values}], declaringType, range}]

openidea.java.semantic.resolveMethod(uri, line, column)
  -> {declaringType, name, returnType, parameterTypes: [...], typeArguments: {...}}
```

只读；不返回 AST/binding 对象；每一次请求都带 `IProgressMonitor` 与取消。

建议 5（java-analyzer）：在 `lsp_ext.rs` 加 `SemanticQuery` 请求，参数为 `{uri,line,character,kind}` 或 `{fqcn}`，返回与建议 4 同构的 JSON；handler 复用 `crates/ide/src/hover.rs` 的 signature 与 `navigation.rs` 的解析结果。

## 11. 未解决 / 需上游确认

1. `symbols("User.")` 列成员是否稳定可用：代码路径支持（空 name + container 过滤），但没有文档与测试承诺。需在 0.1.51 上实测，并把结果作为「可用则用、失效则降级」的能力开关。
2. 上游是否愿意开放只读 LSP 方法（建议 2），或以窄接口形式提供（建议 4）。这是「jdtls 用户能否零 sidecar 拿到类型」的分水岭。
3. 上游是否接受「插件声明包内可执行 / JVM 工具」（建议 1、10.1）。若接受，native 与 jar 两种 sidecar 的授权体验都会质变；若不接受，jar + `process:java` 是唯一稳妥默认。
4. `process:java` 在 Windows 上的 pin 稳定性：`java.exe` 常是厂商 shim（如 Oracle `javapath`），JDK 升级是否必然改变哈希、是否触发一次重批，需要在 0.1.51 上实测（记录 pin 路径与哈希变化）。
5. PATH 上的默认 `java` 版本分布。本机默认 java 是 JDK 8（全局 AGENTS.md 已记录），说明「有 Java 就能跑」不成立；产品需要启动自检与明确指引，或依赖建议 1 的宿主选 JDK。
6. Kotlin LSP（`kotlin-lsp`）是否有可用的自有命名空间方法、是否稳定；若有，能否作为 Kotlin 语义查询的零 sidecar 路径。待验证。
7. Kotlin 编译产物路线的工程覆盖：Gradle 输出目录自定义、KMP 多目标、`build/` 被 clean 后 class 缺失时的降级体验；需要在真实工程上做一轮夹具（Maven/Gradle、Java/Kotlin、单/多模块）。
8. `sqlparser-rs` 对达梦/OceanBase/Kingbase/HighGo/PanWeiDB 的实际解析通过率：需要拿我方 15 方言的黄金 SQL 集跑一遍（不构建，可用其在线 playground 或本地只读示例），量化失败率后再决定纯 Rust 重写的范围。
9. JSqlParser LGPL-2.1 在 jar sidecar 形态下的合规文本（许可与来源随包、jar 不 shade、可替换），以及若走 native-image 的合规定论。需法务确认。
10. `java-analyzer` 的许可：README 标 MIT，但仓库没有 LICENSE 文件、GitHub API 未识别；在把它作为 sidecar 分发或依赖前必须确认。