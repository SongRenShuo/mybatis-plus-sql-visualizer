---
name: mcp-tools-live-test-and-open-bugs
description: MCP 十工具真机实测可复用口径——64342 Streamable HTTP 客户端/projectPath 绑定/跨数据源静默解析/[MCP-AUDIT] 审计对账/两层开关（O-68 后）；20261005 S4 真机复验通过（818af1b ASM 修复生效）+三项观察已随 T-McpWalkFindings 收口（70ac7b9）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_627823aa-6b3d-47e5-9dfd-0549f9a8a1e7
---

# MCP 工具真机实测口径（20260923 首轮全量连通，bug 已修后的沉淀；20260926 T-McpDynamicToolset 全动态化后复验全绿）

## 形态变更（20260926，读数仍可复用）

- **MCP 工具线已全动态化**：XML toolset 声明退役，改 McpToolsetDynamicRegistrar 运行期 ASM 壳注册（探测 Class.forName McpToolset→壳 extends 工具类 implements McpToolset→registerExtension）；jar 字节码零 mcpserver 类型引用（243/251 verifier 兼容问题根治）。工具类加 open、audit 改 intrinsics 透传＋AuditGate 包装续体（try 包挂起调用会让 K2 生成 SpillingKt null-out——243 stdlib 缺该类；try-catch/finally 包挂起调用必然触发，catch 内容无关，探针矩阵实证）。
- **沙盒预置两点**：平台 MCP server 开关＝沙盒 config/options/mcpServer.xml（`McpServerSettings.enableMcpServer=true`，从日常 IDE 抄）；**Trust 框信任状态强杀 IDE 会丢**（taskkill 不 flush 配置，重启再问一次）。**工具类必须 open**（壳 extends final 类＝IncompatibleClassChangeError）。
- **测试 JVM 限制（结构性）**：243 测试 JVM 平台 stdlib 2.0.21 遮蔽项目 2.3.20→SpillingKt CNFE——测试代码里 runCatching 包挂起调用同样触发 null-out→**挂起失败路径的 JVM 单测不可行**，语义由沙盒 [MCP-AUDIT] 对账覆盖；成功路径测试可跑。

## 实测方法（可复用）

- 平台 MCP 服务器（`工具 → MCP 服务器`，端口 64342）支持 Streamable HTTP：POST `http://127.0.0.1:64342/stream`，头 `Accept: application/json, text/event-stream` + `Origin`，initialize 后带 `mcp-session-id` 回传；响应可能是 SSE 帧须解析 `data:` 行。可复用客户端脚本**已沉淀正式位置 `dev-support/mcp-test/mpsv-mcp-test.mjs`**（list/call/suite 三子命令，call 支持 `@file` 传参与第 5 参超时毫秒——长执行工具如 execute_run_configuration 必须显式大超时，否则客户端断开后平台清会话并取消执行；另有 `dev-support/market-probe/`（verifier 在线复现口径）与 `dev-support/sandbox-preset/`（沙盒四键预置））。另有 `mpsv-walk/mcp_full.py` 全流程脚本（见归档 reference-acp-session-verification）。
- **每个工具入参都有可选 `projectPath`**（平台注入 schema），用它绑定目标项目；省略时取 `coroutineContext.projectOrNull`。
- 每次调用在真实 IDE 的 `%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2\log\idea.log` 落 `[MCP-AUDIT] tool=… status=OK/EXPECTED_ERROR elapsedMs=… args=…`（McpAuditLog）——实测后可据此对账，成败都记。
- 插件 10 个工具挂平台 MCP 服务器，**没有插件自有端口**（20260926 起经 McpToolsetDynamicRegistrar 运行期 ASM 壳注册，mcp-optional.xml XML 声明已退役）。
- **平台 MCP 内部结构（262.10968 mcpserver.jar 791 类字节码全扫，20260923）**：外部会话工具清单＝`McpServerService.getMcpToolsFiltered`＝基座清单 + `McpToolFilterProvider` EP 过滤层 + 平台「公开的工具」页偏好（与插件门控正交）；该链不调用 `getEnabledToolsets`。过滤 EP＝`com.intellij.mcpServer.mcpToolFilterProvider`（**262+ 才有**，253 线无此类→插件走探测式程序化注册，见 O-68）。跨线字节码对照不用下载：`F:\gradle-home\caches\9.7.0\transforms\*\transformed\idea-{2025.3.5,2026.2.1,2026.2.3}-win\plugins\mcpserver\lib\mcpserver.jar`，配 `%TEMP%\mpsv-classparse.py`。
- **验证口径**：`verifyPlugin`=报告级（不读豁免清单），权威=**verifyGate**（直驱 verifier-cli `-offline + -ignored-problems`，豁免清单 `dev-support/verifier-ignored-problems.txt`，规则行=plugin_id:version:正则 三段、**注释行禁正则元字符**）。

## 实测基线（20260923，10 工具 demo 项目绑定）

9 工具绿（schema_context / render_logs / check_sql / rollback_plan / perf_advice / crud_sql / reverse_sql / mapper_check / render_sql），预期错误路径也对（多数据源不指名报 `[DATASOURCE_NOT_FOUND]` 并列全部数据源名）。抓出的 2 个 bug（render_sql snippet 缺 ReadAction / explain 省略 dialect 必报 INVALID_ARGUMENT）当日由 O-67 修复合并（`24e2306`），修法与红绿读数见 git 与计划 `docs/开发计划/20260923-1147-T-McpRenderDialectFix-…`。

## 使用层注意项（非 bug，写 agent 提示/文档时要知道）

- **filePath 口径（T-McpFilePathUnify `6a9afa3` 统一后）**：render_sql / mapper_check / crud_sql 三工具统一为「项目相对 + 项目内绝对都收，项目外与 `..` 上跳拒绝」（`McpFilePathResolver`，`startsWith` 按 `base/` 边界判防前缀相似误判）。
- **schema_context 不指名 dataSource 时跨数据源静默解析**：demo 实测实际从 `jpa_demo` 解出而意图库是 mp_demo；成功路径不披露来源（建议输出加 `source:` 行，未实施）。诊断行给各源扫描明细。
- **设置两层勿混（O-68 后口径）**：插件设置「启用 SQL Visualizer MCP 工具」经 McpToolGate 双路径门控自家工具（toolset.isEnabled 走 ReflectionToolsProvider 路径 + mcpToolFilterProvider EP 过滤层走外部会话路径，探测式注册 since-build 262 门控）；「启用 MCP 服务器」（64342）是平台级总开关，不受插件设置影响。已连客户端须刷新才见变化。⚠️ O-68 修复前（≤2026.9.23 发行版）关插件开关**不影响**外部会话清单——写文档/答用户疑问以此为准。文档站 features/mcp + guide/configuration 中英四页已按 O-68 后事实改写（未部署）。

## 日常 IDE 实测载体（20260924 起，用户明示「直接用我日常的测」）

- **用户日常 IDE（2026.2）已装代理化形态构建**（本地构建 2026.9.23，jar 解包核对：McpGateFilterEpBridge/AcpTransportRegistrar 在、SqlVisualizerToolGateFilterProvider/AcpIdeaMcpTransportProviderImpl 旧实现类无）；MCP server enable 态持久（64342/64442 监听）；Claude Agent 已配 deepseek 凭据——**ACP 端到端已在其上真实跑通**，日常 IDE 从此可作 MCP 测试载体（用户在用 UI 时勿抢操作）。
- **T-MarketCompatProxy 代理形态回归读数（20260923 晚，262 沙盒 + 日常 IDE 双验）**：tools/list 62 工具 sqlv_* 恰 10 个在册；check_sql 喂 `OR 1=1` 正确报 **OR_TAUTOLOGY**（比 MISSING_WHERE 更细规则）；render_sql XML 片段渲染 + 参数对照；reverse_sql 生成完整 QueryWrapper；schema_context 数据源未内省时报 SCHEMA_NOT_AVAILABLE 并**列出全部数据源与 modelTables=0 状态**（诊断质量高，参数 `tables` 收逗号字符串**非 JSON 数组**）；日志链 `O-68 proxy form` / `T-AMT` 注册行、`Running private IJ MCP session over Sse`、`[MCP-AUDIT] status=OK` 全在册。
- **ACP 端到端（用户日常 IDE 真实跑通）**：用户向 Claude Agent 发「使用sqlv的mcp预览下这个方法的sql cn.song.mpjdemo.service.impl.DeviceInfoServiceImpl.joinRightAlias」→ 日志 `FeatureRegistry[Tool] - Getting Tool: "sqlv_schema_context"` + `[MCP-AUDIT] status=EXPECTED_ERROR`（当时数据源未连，结构化错误路径正确）。agent→ACP→IDE MCP Server→toolset→插件逻辑整链实证。
- **未覆盖（如实）**：门控过滤「关闭→外部清单 sqlv_* 消失」的生效路径真机未验（代理化后只验了注册与开启态）；explain/rollback_plan/crud_sql/mapper_check/render_logs 未逐一真机调用（共享 toolset 通道，check_sql/render_sql/reverse_sql/schema_context 四工具已证通道健康）。

## 🔴 真机回归：T-McpDynamicToolset 动态壳在真机 262 全失（20261005 用户报「mcp server 无本插件工具」）——✅ 已修已复验销案

- **S4 复验通过（20261005 午）**：日常 IDE 装修复版后注册行 `T-McpDynamicToolset: 10 toolsets registered (dynamic shell form)` 无 WARN 在册、十工具端到端全通、18 调用 18 条 `[MCP-AUDIT]` 一一对应（15 OK + 3 EXPECTED_ERROR 全对应结构化错误路径）——四要件全满足。会话内直接可用 `mcp__idea__sqlv_*` 十工具就是活体证明。

- **症状**：用户把 2026.10.6 zip 装进日常 IDE（IU 262.10968.63），平台 MCP 服务器零 `sqlv_*` 工具。
- **根因（idea.log 12:35~13:15 六轮实锤）**：`McpToolsetDynamicRegistrar.shellBytes()`（.kt:94）抛 `NoClassDefFoundError: org/objectweb/asm/ClassWriter`（Caused by CNFE）→ 10 toolset 全未注册。O-68 gate filter（McpGateFilterEpBridge，JDK Proxy 形态）同会话注册成功→证明 mcp-optional 加载/探测/EP 链全通，唯一断点=ASM 壳生成。
- **为什么自带 asm-9.7.1.jar 没用（双落空）**：`org.objectweb.asm` 是**平台已知包**——真机平台 lib 有 `intellij.libraries.asm.jar`+`intellij.libraries.asm.tools.jar`（都含 ClassWriter）→插件 classloader parent-first 接管、插件自带同包类被跳过；而平台 asm 又在 `intellij.libraries.*` 内部库 jar（对插件不可见）→两处都拿不到→CNFE。c493410/build.gradle.kts:120-122「自带 ASM 免平台可达性差异/插件 classloader 自成一体」是**待验证前提证伪**。
- **沙盒为何假绿**：gradle 缓存平台 idea-2026.2.1/2026.2.3 lib **无 asm jar**（不接管）→自带 asm 正常→动态壳注册成功。**同一 262 大版本内沙盒与真机行为不同**——20260926 动态壳形态「262 沙盒十工具端到端绿」是验证盲区假绿，**自上线从未真机验证**（真机最后在册=20260923 的 XML/代理形态）。
- **平台工具发现机制（262 mcpserver.jar javap）**：`ToolsetReflection_utilKt.asTools`＝`toolset.javaClass`→`KClasses.getFunctions`（类层次全成员扫描）→找 `@McpTool`——**必须「继承工具类」的真类实例**，JDK Proxy 不可行（`$ProxyN` 类层次无业务方法），故当初用 ASM 生成壳类。
- **修复方案（计划 `docs/开发计划/20261005-1335-T-McpDynamicToolset真机ASM不可达-开发计划.md`，未实施）**：S1 主修=McpToolsetDynamicRegistrar 去 ASM 依赖，手写极简 class 文件生成器（壳结构固定：`final Shell extends 工具类 implements McpToolset`+唯一构造器，V17），`shellBytes()` 签名不变仅换实现，零外部依赖/不撞平台包名；S2=删 asm 依赖+zip 不再带 asm jar；S3=测试+「mcp 包无 org.objectweb.asm import」守卫（回归即红）+反向验证；S4=真机复验（重装 zip→`T-McpDynamicToolset: 10 toolsets registered` 无 WARN→tools/list 十工具→端到端+[MCP-AUDIT] 对账）为唯一权威。范围外=McpGateFilterEpBridge/AcpTransportRegistrar（Proxy 形态真机已成功）、帧过滤名单里的 asm **字符串前缀**（非字节码引用无害）、F29 幂等性缺口（部分失败重试撞同 FQN LinkageError）登记不修。

## 20261005 T-McpWalkFindings 三项观察收口（70ac7b9 已 push，计划 b7a6ee3）

- **S1 render_sql 行定位**：line 语义原为「行首 leaf 向上爬」，标签行（gutter 行号）落空——现 offset 取**行内首个非空白字符** + 纯空白行顺延 ≤2 行；fixture 坑=`addFileToProject` 落 VFS 虚根 `/src/` 不在 basePath 磁盘，`McpFilePathResolver` 按 basePath 拼路径找不到——platform 测试要**写磁盘 basePath 再 `refreshAndFindFileByIoFile`**。
- **S2 JSqlParser 5.3 平台坑（可复用）**：`CCJSqlParserUtil.parseStatements/parse` 简单模式（allowComplexParsing 默认关）解析失败**静默返回 null 不抛异常**（源码 :395-417/:81-111）——Kotlin 非空声明上落成 `must not be null` NPE。毒点=**真空行**（`\n\n` 行内无空格；带空格空行不触发）——mapper 渲染产物经 SqlCommentStripper 空行恰好被压成真空行。修法=normalizeWhitespace（`\s+` 折叠）接 **parseStatementsShared 槽入口**（勿放 detect 入口：确认流/回滚链两链 key 错位即二次解析，testFast 首跑 SqlPreviewPanelRollbackParseShapeTest 当场抓过）；残余失败由 SqlAstParser null 防御转带输入摘录的 JSQLParserException。mapper_check 输出自带 `simulate risk note:` detail 行（PARSE_FAILED 归因线索）。
- **S3 schema_context 来源披露**：成功输出首行 `source: X`，未指名附 `(auto-resolved; pass 'dataSource' to pin one)`——跨源静默解析的口径从「文档提醒」升级为「输出自证」。collectEntries 返回 CollectResult（原 Pair 解构的测试 3 处已同步）。
- 定向读数：三包（mcp+execute+sql）1070/0 + testFast 全量绿；反向验证五处摘除 6 用例全红对号。文档站 features/mcp.md 中英两页同步（ab40cbf）。

## 20261005 修复后真机复验（用户装修复版后第二轮十工具实测，全通过）

- **三项修复真机闭环**：S1 `render_sql` line=标签行（UserMapper.xml:25，上轮 SOURCE_NOT_FOUND 原位）命中渲染 + Java MPJ Wrapper 链面（DeviceInfoServiceImpl:36）同命中（观察项「非 XML 面」部分覆盖）；S2 `mapper_check` 全文 simulate **10/10 全 bounded、零 PARSE_FAILED**（上轮 6/10 误报）；S3 `schema_context` 不指名输出首行 `source: jpa_demo@localhost (auto-resolved; pass 'dataSource' to pin one)`（再次实证跨源：意图 mp_demo 实际 jpa_demo）。
- **权威 tools/list**：82 工具、`sqlv_*` 恰 10 个；13/13 `[MCP-AUDIT]` 一一对应（12 OK + 1 EXPECTED_ERROR＝被 shell 转义破坏的参数正确报 `[INVALID_ARGUMENT] cannot parse SQL`——错误路径质量顺带验证）。
- **ZCode 侧 `mcp__idea__` 会话在 IDE 重启后失效**（调用全 30s 超时、审计零记录、日志确认 IDE 活着且 MCP server 端口 64342 initialize 正常）——**绕行走项目正规客户端 `dev-support/mcp-test/mpsv-mcp-test.mjs`（node，直连 64342）**，不依赖 ZCode 连接；若要在 ZCode 里恢复 `mcp__idea__` 需重连 MCP。传参一律 `@file`（Git Bash 内联 JSON 的 `\n`/引号会坏，且坏参数的报错行易被 `tail` 截断误判成「工具无响应」）。
