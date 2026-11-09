# 架构摘要 — mybatis-plus-sql-visualizer（run-1，standard 档案）

## 产品、主体与受保护资源
IntelliJ IDEA 插件（Kotlin/Java，插件 id `cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer`），把 MyBatis-Plus / JPA / JdbcTemplate / MyBatis-XML 查询还原为回填参数后可执行的 SQL，在服务启动前于 IDE 内渲染 / EXPLAIN / 执行，并支持 SQL 反向生成 Wrapper 代码。单用户桌面信任模型：操作者即开发者本人；低信任主体包括 (a) IDE 所托管的任何进程产生的不可信控制台 / 日志文本，(b) 环回 agent 通道上的本地进程，(c) 通过平台 MCP 服务接入的 AI 代理，(d) mapper XML / 项目文件，(e) 剪贴板与导入的设置 / 历史文件，(f) 远端更新 / 反馈服务的响应。受保护资源：用户 JVM（agent attach）、项目数据源及其凭据（JDBC 执行）、磁盘文件（自动保存、导出、动态安装到 IDE 插件目录）、包含字面量参数值的 SQL 历史，以及 IDE 进程本身（动态安装插件、反射访问平台内部）。

## 可比基线
MyBatis 日志还原类插件（MybatisCodeHelperPro、MyBatis Log Free、Mybatis Log Ultra——仓库文档中有点名）解析相同的 "Preparing/Parameters" 格式，但通常停留在只读渲染。本插件走得更远：它会执行 SQL、向用户 JVM 挂载字节码 agent、并自安装更新 ZIP——相比可比基线承担了更多信任敏感风险。用它来校准严重度，而不是用来否定已证实的发现。

## 技术栈与部署
Kotlin 2.3 / Java（agent 目标 Java 8），IntelliJ Platform 2024.3（IPGP 2.18.1），Gradle 9.7。运行时依赖：JSqlParser 5.3；agent 内置重定位 ASM。部署：JetBrains Marketplace ZIP；Gitee 发布 ZIP + 插件内自更新清单（`update/UpdateSource.kt`）；无随插件发布的服务端。离线构建依赖本地缓存（平台发行版、JDK 17）；完整测试需要兄弟演示仓库。

## 入口面与源码到汇聚点路径
1. **控制台捕获**（核心）：经由 `consoleFilterProvider` EP（`plugin.xml:141`）或反射桥（`SqlLogConsoleBridge.kt:304-430`）进入的每条运行控制台行 → 4 个解析器（`MyBatisLogParser`、`HibernateLogParser`、`JdbcTemplateLogParser`、`ShardingSphereLogParser`，长度上限 20k）→ 面板、自动保存文件、跳转源码标记、重新执行。
2. **环回 agent 通道**：IDE `ServerSocket` 仅绑定环回（`AgentManager.kt:799,893-914`）↔ agent `SenderLoop`（`SenderLoop.java:148-157`）；4 字节长度前缀帧 ≤1MB；**双向均无对端认证**；HELLO pid 为自报。
3. **Agent attach / premain**：通过 `com.sun.tools.attach` 注入用户 JVM（`AgentManager.kt:798-838`），目标审查见 `AgentAttachTarget.kt:40-154`，确认对话框已按设计移除（`PremainInjection.kt:14`），总开关默认开启（`PluginSettings.kt:637`）。
4. **MCP 工具**：11 个 `sqlv_*` 工具（`McpToolsetDynamicRegistrar.kt:63-130`，运行时生成外壳类）；文件读取由 `McpFilePathResolver.kt:41-71` 限制在项目内；DB 执行经由 `McpSelectGuard` 单 SELECT 守卫 → `McpDbExecutor.kt:206-232`；历史搜索向代理暴露带字面量参数值的捕获 SQL。
5. **Mapper XML**：所有解析统一走加固的 `xml/SecureXml.kt`（XXE 特性关闭、展开预扫描、文档化残余边界 `:44-51`）；自研 OGNL 子集求值器 `xml/Ognl.kt:124-165`。
6. **SQL 执行（UI）**：`SqlExecutor` → `DataSourceSelector`（任意 IDE 数据源），前置方言门确认、DML 确认、`UnboundedWriteDetector`、模式 / 超时守卫。
7. **自更新**：Gitee raw 清单（URL 可被系统属性覆盖）→ 下载 → `UpdateZipGuard` 边界检查 → 动态安装 / 重启脚本；**无产物签名**。
8. **历史自动保存**：`SqlLogAutoSaver.kt` 按用户可配置的文件名模式写盘（`:454-550`，模式中的分隔符会被采纳）；`SqlLogFileReader` 会将其重新导入执行链路。
9. **设置 / 配置**：`SettingsIO.importFromJson` 合并共享设置，其中的字段随后进入渲染 SQL 片段（租户列、逻辑删除值、表名规则）。
10. **反馈 / HTTP**：向固定的 `sql-visualizer.jvtools.cn` 端点 POST，读取有 2MB 上限，服务端消息直接展示给用户。
11. **Swing HTML 标签**：各对话框各自实现的 `escapeHtml` 完整度不一致（`CommunityDialog.kt:137`、`ExplainPlanResultView.kt:102,214`）。
12. **供应链**：GitHub Actions 工作流（输入经 env 传递）、buildSrc 约定插件、dev-support / 发布脚本、内嵌 agent jar。

## 信任边界与最强源码可见控制
| 边界 | 最强源码可见控制 |
|---|---|
| 控制台文本 → 可执行 SQL / 导航 | 长度上限 + 重放守卫（`ConsoleReplayGuard`） |
| 本地进程 ↔ IDE agent 通道 | 仅环回绑定 + 1MB 帧上限（无认证） |
| 插件 → 用户 JVM | `AgentAttachTarget` 拒绝名单 + 设置总开关 |
| AI 代理 → 文件 / DB / 历史 | `McpFilePathResolver` 项目根限制；`McpSelectGuard` 失败即拒的单 SELECT 守卫；工具门控 |
| Mapper XML → SQL | `SecureXml` 特性关闭 + 展开预扫描 |
| 不可信 SQL → 用户 DB（UI） | 确认对话框 + 写入检测器（仅客户端） |
| 更新服务 → IDE 代码 | HTTPS + ZIP 边界 + 插件 id 匹配（无签名） |
| 设置文件 → SQL 片段 | 白名单合并、版本门 |

## 仓库相对起始路径
`src/main/kotlin/cn/song/mybatisplus/visualizer/{log,log/agent,mcp,xml,execute,update,config,history,feedback,ui,actions,share}`、`src/main/java/cn/song/mybatisplus/visualizer`、`sub-projects/sql-visualizer-agent/src/main/java`、`src/main/resources/META-INF/plugin.xml`、`.github/workflows`、`buildSrc/src/main/kotlin`、`dev-support`、`update/update.json`。

## 历史覆盖情况
本仓库无历史运行记录；这是首次审计。无历史确认排除项。

## Companion 选择摘要
因侦察发现了与之匹配的信任敏感边界而选择：**AI-AND-LLM.md**（MCP 工具服务、提示注入代理驱动工具）、**DESKTOP-MOBILE-AND-LOCAL-IPC.md**（无认证的环回守护进程 / IPC）、**PROTOCOLS-RPC-AND-MESSAGING.md**（IDE 与 agent 之间的自定义定长 TCP 协议）、**SUPPLY-CHAIN-AND-RELEASE.md**（自更新 ZIP 安装、CI、发布流水线）、**RESOURCE-EXHAUSTION-AND-AVAILABILITY.md**（ZIP 炸弹、不可信文本上的正则、无界捕获 / 历史增长）。排除：MEMORY-SAFETY-AND-BINARY（纯 JVM）、CLIENT-SIDE（无浏览器 SPA；JCEF 仅加载静态内置页面；Swing HTML 由 Injection 类覆盖）、WEB-PROTOCOL-AND-AUTH（无 HTTP 服务端、无会话 / 认证协议；出站 HTTP 为固定 URL 且有读取上限，由普通类覆盖）、DATA-ISOLATION-AND-LIFECYCLE（单用户本地模型；历史导出 / 导入由普通 Feature-abuse 与文件处理类覆盖）、CLOUD-AND-DEPLOYMENT（无 IaC / k8s；CI 由 SUPPLY-CHAIN 覆盖）。

## 本次运行的执行安全说明
侦察（Agent 1d）确认本 Windows 主机无法强制执行所需的 OS 沙箱控制（空环境白名单、无外网、只读目标 / 工具链、仅草稿可写、显式资源上限）与草稿提升流程。因此本次运行为**纯源码审查**：不执行任何目标受控代码。任何需要「有界本地观测结果」才能定级的候选都记为 `needs_validation`，并附上精确的阻塞点与可执行的本地验证计划（现有单元测试，如 `SecureXmlTest`、`McpSelectGuardTest`、`DynamicInstallSupportTest`）。
