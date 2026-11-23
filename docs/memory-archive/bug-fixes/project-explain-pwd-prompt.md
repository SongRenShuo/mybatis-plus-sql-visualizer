---
name: project-explain-pwd-prompt
description: T-ExplainPwdPrompt（20261024 已实施 4bccd331，未 push；W4 回落后被 W9~W12 取代）——结构化执行计划/预估行数未存密码不弹原生密码框；修法=必填 askPassword 按通道表态+失败分型；**V1 已由用户真机实测通过**（平台原生 Change Credentials/Abort 对话框形态，非预弹密码框）；后续取源/呈现问题见 project-dialect-infer-pollution
metadata:
  node_type: memory
  type: project
  originSessionId: session_b380d49669e298cff6e310c72afca927
---

# T-ExplainPwdPrompt：结构化执行计划未存密码不弹原生密码框（20261024 已实施 `4bccd331`，未 push）

用户沙盒验收 [[project-preview-chain-noise]] 时实测截图：数据源未存密码时点「执行计划」，`ExplainPlanDialog`
只出红字 `Access denied for user 'root'@'localhost' (using password: NO)`，**没有** IDE 原生密码框
（原生控制台链会弹）。计划册 `docs/开发计划/20261024-0030-T-ExplainPwdPrompt-结构化执行计划未存密码不弹原生密码框-开发计划.md`
（含 §九 实施记录、§十 沙盒验收记录）。

**根因（实码+字节码双证）**：
- `McpDbExecutor.executeQueryInternal:281` 无条件 `setAskPassword(false)`——2026-09-15 T-McpToolset
  （`49b75752`）为 MCP 通道立的「零弹窗红线」（fail-closed）；R33/T-PreviewSideQuery 把面板 EXPLAIN 与
  预估行数旁路切到该通道时**继承了这个 false 没重新表态**。git -S 实证**非 `9809a5b7` 回归**（那笔只包取消上下文）。
- 平台事实（243 DatabaseTools jar javap，corretto-21）：`DatabaseConnectionManager$Builder` 默认
  **`askPassword=true`**（构造器 `iconst_1; putfield`），原生控制台的弹框就是这个默认；询问/重试链在
  `DatabaseConnectionEstablisher.Companion`（suspend 形态，`handleCredentialsFailure`/`resolvePasswordIssues`）。
- 日常真机不暴露＝数据源都存了密码；沙盒干净环境首次踩中。

## 已实施（`4bccd331`，9 文件 +296/−6）

- **W1**：`executeQuery`/`executeQueryInternal` 增**必填** `askPassword` 参数（无默认值，编译器强迫
  每个调用点表态——两个默认都是坑：默认 false 静默不弹、默认 true 击穿 MCP 红线）。
- **W1-c 三调用点表态**：`ExplainPanelChannel`/`PreviewSideQuery` 传 true；`SqlVisualizerExplainTool`
  传 false（MCP mpsv_explain 零弹窗红线原样）。
- **W2**：分型纯函数 `classifyExplainFailure`（AUTH/CANCELLED/OTHER）+ `isAuthFailureText`（五签名：
  access denied / password authentication failed / invalid username/password / ora-01017 / login failed
  for user）。AUTH 就地红字+补存密码指引**不再回退直送**（同通道必同样失败）；CANCELLED（密码框 Esc）
  轻通知 `notifyExplainCancelled` 绝不自动重试；OTHER 维持回退。**坑＝取消词根必须写 `"cancell"`**——
  `CancellationException` 是双 L，只写 `"canceled"` 漏平台取消形态（首跑 ExplainFailureClassifierTest 实证）；
  超时消息常含 "cancelled" 须排除（超时是执行错误非用户取消）。双语键 `panel.explain.authFailureHint`/
  `panel.explain.cancelled`；`PreviewSideQuery.failureMessage` 对认证失败附同一指引（预估行数内联区同文案）。
- **W3**：`ExplainAskPasswordChannelGuardTest`（BannedApiScanSupport 剥注释快照对账三文件表态）。
- **读数**：定向 3 类 **21/0/0/0**（Classifier 8 + Guard 3 + 既有 PreviewSideQueryTest 10，XML 判例数已核）；
  反向恰 3 红（UI 表态改 false / MCP 改 true / 摘 access denied 签名各红其一），还原后全绿。
- 提交链：`f36b556e`（计划）→ `4bccd331`（代码，含 PreviewSideQuery 注释订正「真机池线程有上下文」被推翻）
  → `9a0c1ba2`（实施记录）→ `384b8484`（沙盒验收记录）。

## 沙盒 GUI 验收（20261024）：**未闭环——环境阻塞，非产品缺陷**

- 在役包核验通过（javap 沙盒 jar：askPassword 符号+分型函数在役，mtime 晚于修复提交）。
- 走到最深点：预览→执行计划→「启用结构化并重试」通知动作**点击生效**（开关启用+重试链运行）→
  绑定链在 **runner 枚举=0**（registered=7，方言路由无匹配——数据源从未连接/学过方言）处失败，
  **结构化通道未被执行到**，askPassword 行为无法在此形态检验。V1~V4 未验；V5（MCP 零弹窗）已由守卫收口。
- 两道环境阻塞：①沙盒 MySQL 驱动拷入 `config/jdbc-drivers` 后未生效（须 IDE 启动前就位或重启）；
  ②数据源方言路由空（无 Dialects 映射+从未连接）。**均为沙盒装配问题，非产品缺陷。**
- **闭环路径（用户真机，日常 IDE 驱动/数据源/方言齐备）**：①数据源不存密码→预览→执行计划→**原生密码框**（V1
  承重墙）；②Esc→轻通知（V2）；③输错密码→红字+补存密码指引（V3）；④预估行数→弹框（V4）。

## 两层「绑定」概念（用户追问实证，复用价值高）

用户在 Database 视图连上 mp_demo 后仍报「未绑定数据源」——**插件读的「绑定」不是 Database 工具窗
连接状态**：`boundConsoleDataSource()`（`DataSourceSelector.kt:311-320`）读的是**预览面板 prepareConsole
链建出的控制台**（`sharedConsole ?: preparedConsole` + `isValid` + `ExecConsoleContext.boundDataSource`），
O-145 设计＝「预览用哪条数据源，执行计划就在同一条上跑」。Database 视图连接≠预览会话绑定。
正确操作路径：执行计划→通知「启用结构化并重试」链接（点通知其它区域只会关掉它）→数据源选择器→
选源→密码框。另：结构化开关运行时置位后**强杀 IDE 不落盘**（PluginSettings apply 链未走完）→ 重启回默认关。

**临时出路（已告知用户）**：错误框「在控制台打开」（控制台链正常弹框）；或 Database 工具窗口连接一次
数据源勾选保存密码。`ConnectionErrorClassifier` 的排除表（access denied/password 不算连接类错误）是
DDL 已生效性探测的口径，**不要**为认证分型去改它——分型在 UI 呈现层做。

## W4 回落（20261024 `d72d6f2a`）：runner=0 阻断的产品侧修复

验证者裁定「有问题则修复」未闭合 ⇒ 不再等用户配方言，直接实施取源回落：

- **根因定性**：数据源无 Dialects 映射时平台 `DatabaseRunners.getAttachDataSourceRunners` 返回空
  （`DbImplUtil.getForcedDataSourceForExec` 首跳也救不了——强杀文件锁没查到关联写入口，放弃该路线）。
- **修法**：`submitExplainOrStructured` 取源扩两级——`boundConsoleDataSource()`（真绑定）null 时回落
  `DataSourceSelector.lastUsedExplainDataSource(project)`＝读面板「数据源（上次使用）」记忆
  （PropertiesComponent 键 `mpsql.visualizer.last.runner(.id)`，持久化在 demo 工程 workspace.xml，
  实测 `mp_demo@localhost` 已在）→ `DataSourceSettings.getAllDataSources` 按名解析在籍源。
  **防错源纪律＝按名歧义即弃**（`resolveLastUsedExplainFallback` 纯函数 singleOrNull）；方言不符由
  既有 `allowDialectGate` 闸拦。结构化通道（askPassword=true）从此不依赖绑定链即可到达。
- **测试**：`ExplainLastUsedFallbackTest` 4 例（唯一命中/歧义弃/缺失弃/空名单弃，testFast 快桶）+
  通道守卫加「提交链必须带 last-used 回落」例；定向 4 类 **26/0/0/0**；反向摘回落段恰 1 红。
- **坑**：Kotlin `List<Pair<String,Any>>` 不能赋给 `List<Pair<String,Any?>>` 形参（不型变）——
  纯函数形参写 `List<Pair<String, Any?>>?`（可空+可逆变元）即兼容；首版编译错实证。
- **诊断缝**：`lastUsedExplainDataSource` 临时加了 `[M37.2] lastUsedExplainDataSource: remembered=…,
  roster=…, resolved=…` 日志行（**含此行+泛型修正尚在工作树未提交**）——用户复验仍失败时读这行定位断点，
  闭环后要么提交要么删。
- **验收现状**：用户首次复验（W4 包但无诊断）仍出「未绑定数据源」红字框 ⇒ 取源环节断（记忆在但解析链
  某环空）；已重打包带诊断日志重启沙盒（jar 09:24:50 在役），用户复验 V1 进行中。
  dev 启动脚本已按用户令拷入主仓 `.local/`（`explain-pwd-ui.init.gradle` + `start-explain-pwd-ui.ps1`，
  独立沙盒 MPSV-ExplainPwd-UI3）。
