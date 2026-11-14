# T-SecurityAuditFixes 安全审计 run-1 线索修复 — 开发计划

- 日期：2026-11-06 23:30
- 任务编号：T-SecurityAuditFixes
- 来源：`docs/安全审计/2026-11-06/`（Cloudflare security-audit-skill run-1，standard 档案，25 条 `needs_validation` 线索，双验证器 PASS；优先级表见 REPORT.md §4，逐条轨迹/证据/验证计划见 NEEDS-VALIDATION.md）
- 性质：**复核 + 安全面修复**——审计基线之后 master 已前进 132 个提交（src/main 59 文件、+7974 行），其中 6 条线索的涉案源码已变化，**必须先复核再修**（审计 skill 规则：源变更后历史判定不再默认适用）；另发现 2 个审计后的**新增信任面**需纳入快扫
- 实施分支：自 master 拉任务分支 `t-security-audit-fixes`（批次 2/3/4 可按批拆子分支，合流逐批走用户点名流程）
- 验证基线：审计基线 `30312d461`；当前 master `84829511a`（merge t-dev-update-channel 之后）

---

## 实施记录（2026-11-07 回填，实施已完成）

**分支与区间**：`t-security-audit-fixes`，实施区间 `7389cde46..e8d2a635d`（三车道 worktree 并行 + 记账代理账本闭环，未推送远端）。

**决策定案**：D1=A（禁用内嵌原生 ▶/Ctrl+Enter）｜D2=**用户定案：凭据为测试服务器保留现状，T-SAF-01 全项取消**｜D3=A（只读失败即拒）｜D4=A（Central 置前 + verification 严格校验）｜D5=温和版（多来源信号延迟兜底桶配对）｜D6=A（manifest 并列 sha256 —— 本次未实施，登记 followup）。

**批次完成状态**：

| 批次 | 结果 |
|---|---|
| 0（T-SAF-01） | **取消**（用户豁免：测试服务器凭据保留现状） |
| 1（复核波 R-01/R-02/R-03 + 新面快扫） | 全部「维持」；DevUpdateMode/PlatformEpDynamism 扫描 info 无发现 |
| 2（高价值边界，9 工作项 10 线索） | 全部落地（车道 A/B/C 并行） |
| 3（资源上限统一，7 工作项） | 全部落地（含车道分组遗漏的 T-SAF-18/19 由车道 D 补齐——记账代理核查拦截了虚构处置） |
| 4（构建防线 T-SAF-14/15/16） | 全部落地（仓库序翻转 + 356 组件 verification-metadata + wrapper 官方锚 + pin 精确门禁） |

**合并验证**：testFast 全绿（4528 测试，verification 严格模式下跑通）+ 平台 test 定向组绿（通道认证/Premain 回归/只读执行/UI 转义/项目级设置/zip 守卫/描述符解析/HTML 报告）。合并期修正 9 处（三车道交界编译问题、测试校准、BannedApiScan 豁免登记等，见 e8d2a635d 及各 fix(test) 提交）。

**账本闭环**：`findings.json` 最终 **24 confirmed（已修复）+ 1 needs_validation（#1 凭据，所有者豁免）**，双验证器 PASS；记账提交 `a8236247a`（21 条首轮）+ `80e79f483`（补账 3 条）。CHANGELOG `[Unreleased]` Fixed 段回填 24 条。

**遗留 followups**（登记于审计账本与车道报告，未阻塞收官）：共享骨架出口脱敏、BoundedRegex 步数级预算统一、审计清单外两处 escapeHtml 收敛、项目级设置 UI 内联校验反馈、所有者 runIde 端到端核对（T-SAF-02/03/04）、manifest 并列 sha256（D6）。

### 附：遗留 followups 详解（2026-11-07 补记）

以下内容为各车道实施报告与父会话收口时的原始 followups（此前未持久化，本段补记）；每条含现状/风险/建议口径。均非漏洞、不阻塞收官。

**1. MCP 出口脱敏收进共享骨架**（源自 T-SAF-07）
- 现状：`mcpMaskEnabled` 脱敏由各工具自行调用 `DisplayMasker.maskDisplay`（renderLogs 与 historySearch 各自一处）；共享输出骨架 `McpToolsetSupport.audit` 原样透传。
- 风险：将来新增携带还原 SQL 的 MCP 工具若忘调脱敏，会重演「一条通道脱敏、另一条明文」的分歧。
- 建议：脱敏下沉到共享骨架统一出口，新工具默认被覆盖。

**2. BoundedRegex 步数级预算统一**（源自 T-SAF-24 + T-SAF-25）
- 现状：两套正则求值预算并存——`util/BoundedRegex.kt`（字符读计数，200k 上限，引擎步数紧上界）与 HistorySearchService 内联时间预算（单条目 50ms 记 miss、整查 2s 失败，弱点是超时窗口内线程仍被占）。
- 建议：统一到 BoundedRegex 计数式实现，两处及未来调用点走同一机制。

**3. 审计清单外两处 escapeHtml 收敛**（车道 C 实施时发现）
- 现状：收敛覆盖审计点名的六处变体 + HtmlReportFormatter；清单外还有 `ui/diagram/DiagramRenderer.kt:792`、`share/format/DiagnosticReportFormatter.kt:261` 两处同类逐点实现（未被判定为漏洞，仅未归共享工具）。
- 建议：一并收敛到 `util/HtmlEscape.kt`，防漂移。

**4. 项目级设置 UI 内联校验反馈**（源自 T-SAF-08）
- 现状：非法 SQL 位形设置值在导入路径被整体拒绝、项目级读取路径 warn 后回退全局；但设置页 UI（PluginSettingsPanel*.kt）手输非法值时无内联提示，仅 idea.log 一条 warn——用户以为生效、实际被静默回退。
- 建议：设置面板对相关字段加即时校验反馈（红字/禁用保存）。

**5. 所有者 runIde 端到端核对**（T-SAF-02/03/04 的部署面，回归测试打在接缝层）
- T-SAF-02：开增强捕获跑应用，确认 agent 经令牌认证正常建立会话（合法捕获链路未被令牌误伤）。
- T-SAF-03：对真实数据源跑 sqlv_explain，确认 `setReadOnly(true)` 在平台 243/262 的 RemoteConnection 反射链上真的生效（接缝缺失/失败时应见结构化拒绝而非静默放行）。
- T-SAF-04：内嵌预览面板原生 ▶/Ctrl+Enter 应已置灰，插件按钮链照常工作。
- 另：T-SAF-06 插件侧已先行拒绝恶意条目名，平台解压器行为的取证仍可在 runIde 做一次；T-SAF-05 端到端更新流（UpdateFlowHeavyTest 模式）可顺手验。

**6. manifest 并列 sha256（决策 D6，本次未实施）**
- 更新链的最后一层纵深：发布侧（gitee-release.py / writeUpdateManifest）把产物 sha256 与 downloadUrl 一起写进 `update/update.json`，消费侧（PluginUpdateRunner）安装前比对。未做原因：`update/update.json` 按 AGENTS.md 仅用户本人可改、涉及发布流水线，需用户参与。

**7. T-SAF-11 定案测试实测**（合并验证已跑，此处登记口径）：JSqlParser 5.3 对 CTE/派生表内 INTO 的解析形态由定案测试实测定案；解析失败形态守卫已 fail-closed，无行为缺口。

**已知口径（非改进项）**：
- T-SAF-02 发行批同步：旧 agent+新插件组合 HELLO 无令牌会被拒绝（协议 break 有意为之，随制品同版本分发）；`update/update.json` 仅用户本人可改。
- T-SAF-12：纯 token-less 流首条错配信息论不可判定，自学校信号自第二条参数行起生效；彻底消除需上游（控制台桥）提供行级线程上下文，另行立项。
- T-SAF-13：Windows 符号链接回归测试在无特权机器 skip，CI 应在可建符号链接的 runner 上跑真覆盖。
- BoundedRegex 超预算 warn 为 once-per-JVM 全局；per-pattern attribution 有助诊断（未来增强）。

---

## 一、线索总账与批次映射（25 条）

优先级口径沿用审计报告 §4（所有者可行动性）。每条线索的完整源码轨迹、证据与验证计划**不在本文件重复**，以 `docs/安全审计/2026-11-06/NEEDS-VALIDATION.md` 对应章节为准；本表只登记指纹、修复工作项与批次。

| 审计# | 指纹（缩写） | 工作项 | 批次 | 是否需先复核 |
|---|---|---|---|---|
| 1 | db-env-provisioning.skill.highgo-xc3-password | T-SAF-01 | 0 | 否（凭据事实已 git 核实） |
| 2 | agent-ipc-loopback-unauth-session-trust | T-SAF-02 | 2 | **是（R-03）** |
| 3 | mcp-select-guard.client-side-only-readonly-gap | T-SAF-03 | 2 | 否 |
| 4 | execute.embedded-native-console-exec-bypasses-confirm-chain | T-SAF-04 | 2 | 否 |
| 5 | update.validateArtifact.regex-comment-desync | T-SAF-05 | 2 | **是（R-02）** |
| 6 | update.UpdateZipGuard.entry-name-platform-delegated | T-SAF-06 | 2 | **是（R-02）** |
| 7 | mcp.history_search.masking-not-applied | T-SAF-07 | 2 | 否 |
| 8 | settings-import.sql-fragment-unvalidated | T-SAF-08 | 2 | 否 |
| 9 | share.html-report-export.unescaped-tableName-src | T-SAF-09 | 2 | 否 |
| 10 | ui.swing-html-label.unescaped-lower-trust-text | T-SAF-09 | 2 | 否 |
| 11 | mcp-select-guard.into-clause-non-recursive-cte-gap | T-SAF-11 | 2 | 否（但先跑 AST 形态测试定案） |
| 12 | log.threadrouter.failopen-fallback-cross-thread-param-mixing | T-SAF-12 | 2 | **是（R-01）** |
| 13 | history.autosave.append-open-symlink-follow | T-SAF-13 | 2 | 否 |
| 14 | gradle-deps-aliyun-mirror-first-no-verification | T-SAF-14 | 4 | 否（settings.gradle.kts 未变） |
| 15 | gradle-wrapper-tencent-mirror-no-sha256 | T-SAF-15 | 4 | 否（wrapper 未变） |
| 16 | verifier-cli-gate-jar-first-match-ignores-pin | T-SAF-16 | 4 | 否（mpv-verification.gradle.kts 未变） |
| 17 | mcp-sqlv-text-tools-unbounded-sql-input | T-SAF-17 | 3 | 否 |
| 18 | sql-reverse-parser-unbounded-recursion | T-SAF-18 | 3 | 否 |
| 19 | …/xml/Ognl.kt@parse-exception-confinement | T-SAF-19 | 3 | 否 |
| 20 | log.mybatis.parseparams.unbounded-quadratic-regex | T-SAF-20 | 3 | **是（R-01）** |
| 21 | log.executiontimeparser.trailing-ms-whitespace-backtracking | T-SAF-21 | 3 | 否（ExecutionTimeParser 未变；回归时连带验证） |
| 22 | log.threadrouter.unbounded-nonidle-bucket-retention | T-SAF-22 | 3 | **是（R-01）** |
| 23 | history.reader.unbounded-line-buffering | T-SAF-23 | 3 | 否 |
| 24 | mcp.history_search.agent-regex-unbounded-backtracking | T-SAF-24 | 3 | 否 |
| 25 | dynamic-tablename-rule.regex-unbounded | T-SAF-25 | 3 | 否 |

需复核 6 条（#2、#5、#6、#12、#20、#22）；直接可修 19 条。

## 二、审计基线后的代码变化核查（2026-11-06 拉取后核实）

`30312d461..84829511a` 共 132 提交，src/main 59 文件。与审计线索相关的变化逐项核实：

| 变化 | 核实事实 | 对线索的影响 |
|---|---|---|
| `update/` 大改（DynamicInstallSupport +256 行；新增 DevUpdateMode.kt、PlatformEpDynamism.kt；PluginUpdateRunner/StartupUpdateCheck/UpdateSource 各有改动） | **线索 #5 的病灶未变**：`extractTag` 仍是 `Regex("<$tag>(.*?)</$tag>", DOT_MATCHES_ALL)` 首匹配原始文本（`DynamicInstallSupport.kt:218-219`），`checkDescriptorXml` 仍用它取 id/version（`:206-207`）；`UpdateZipGuard.kt` **不在变更清单**（#6 的插件侧现状不变，但动态解压路径新增 `PlatformEpDynamism` 需纳入复核） | #5/#6 进入 **R-02 复核**；新增面见下 |
| `log/MyBatisLogParser.kt` +225/-13（T-IbatisPanwei：iBatis 2.x 三行组支持） | **线索 #20 的病灶未变**：`parseParams(line.trim())`（`:174`、`:187`）仍无 `take(maxParseLength)`，`PARAM_REGEX.findAll`（`:465`）仍无界；`isIdle` 定义未变（#22 病灶仍在）。**新增** `ibatisPending`（`LinkedHashMap<String, IbatisPending>`，键 = `{pstm-N}`）第二挂起态——`closePending` 冲刷但**无条目数/总量上限**，与 #22 同类问题的新实例 | #20/#22/#12 进入 **R-01 复核**；#22 修复须连带覆盖 `ibatisPending` |
| agent 子项目（SenderLoop.java 62 行、CaptureBudget/CatchAllMatcher/CatchAllRedLine/WeaveSpecs/AgentState/DriverRegistry + 新测试 ConfigApplyEndToEnd/HeartbeatDiagnostics） | 线索 #2 的 agent 侧行为（看门狗 :295-303、快速丢弃 MAX_FAST_DROPS、CONFIG 应用 :429-471）所在文件已改，具体行为偏移未核实 | #2 进入 **R-03 复核** |
| `config/UpdateSection.kt`、`PluginSettingsPanelSections.kt` | 设置页新增开发版通道入口（DevUpdateMode 连点解锁） | #8 的设置注入面不变（SettingsIO 未变）；新面见下 |
| `gradle.properties`（2 行，版本号）、`mpv-testing.gradle.kts`（70 行，测试分片） | `settings.gradle.kts`（镜像顺序）、`gradle/wrapper/gradle-wrapper.properties`（无 sha256）、`mpv-verification.gradle.kts`（walkTopDown 三处）**均未变** | #14/#15/#16 不受影响，可直接修 |
| mcp/、xml/、execute/、config/SettingsIO、SqlLogAutoSaver/SqlLogFileReader、ui 对话框、share/HtmlReportFormatter、HistorySearchService | 均不在变更清单 | 对应 19 条线索直接进入修复 |

**审计后新增信任面（2 个，纳入 R-02 快扫）**：
1. **DevUpdateMode**（`update/DevUpdateMode.kt`）：设置页连点解锁「检测更新（开发版）」，读 `UpdateSource.PREVIEW_MANIFEST_PATH` 预览清单；`-Dmpvs.update.devMode=true` 直通开关与 `mpvs.update.manifest.url` 同口径。快扫点：预览清单 URL 的约束、预览 zip 是否走同一 `UpdateZipGuard`/`validateArtifact` 门（若绕过即新发现）。
2. **PlatformEpDynamism**（`update/PlatformEpDynamism.kt`）：243 上动态安装入口消失的适配。快扫点：反射目标是否恒为编译期常量、失败是否失败关闭。

## 三、目标与非目标

**目标**：①6 条受影响线索复核定案（维持/推翻/口径修正）；②25 条线索全部落地为修复提交 + 回归测试（复核推翻的除外），最终把每条升级为 `confirmed`（修复后本地可复现→修复→复测）或 `rejected`；③审计报告与账本同步更新（双验证器 PASS）；④两个新增信任面完成快扫并登记结论。

**非目标**：不重写审计英文版存档（保留 `d41f44b70` 历史）；不扩大修复面（审计 skill 原则：最小有效修复，不做无边界违背的防御性加固——hardening 清单另行择机）；不动与线索无关的解析/渲染语义。

## 四、决策点（需用户定案，均已在下文给推荐）

| # | 决策 | 选项 | 推荐 |
|---|---|---|---|
| **D1** | 内嵌预览控制台的 native ▶/Ctrl+Enter 处置（#4） | A：移除 UiDataProvider 的 CLIENT_KEY/EDITOR 注入，禁用原生执行入口，只留插件按钮链；B：保留原生入口，把 SqlTypeClassifier 确认链挂到控制台提交路径（需反射拦截平台 action，脆弱） | **A**——禁用是最小可靠修复；原生入口是便利功能，失去后插件按钮仍在 |
| **D2** | 凭据 git 历史清理方案（#1） | A：仅前向清理（删文件中的字面量）+ 轮换凭据；B：`git filter-repo` 重写历史 + force push（影响所有协作者与 fork） | **A + 轮换**——凭据轮换后历史中的旧凭据即失效，重写历史的协作成本远超收益；如用户坚持 B 须单独授权并协调 |
| **D3** | `sqlv_explain` 数据库层只读的降级策略（#3） | A：`setReadOnly(true)`/只读事务设置失败即拒绝执行（fail-closed）；B：设置失败告警放行 | **A**——工具已向 agent 宣称 strictly read-only，做不到就该拒绝；方言差异在 `McpDbExecutor` 已有 channelNote 分支先例 |
| **D4** | 依赖镜像信任策略（#14） | A：mavenCentral 置前 + `verification-metadata.xml` 严格校验（镜像作后备）；B：保留镜像优先 + 仅校验和钉 | **A**——CI 已证明作者不信任腾讯 wrapper 镜像，依赖镜像同理；verification metadata 对镜像偏差失败关闭 |
| **D5** | 兜底桶（无 token 行）处置（#12） | A：参数行与 pendingSql 的 token 无法核验时拒绝配对（丢弃 + 计数提示）；B：维持现状 + 面板警示标记「无线程上下文」 | **A 的温和版**——仅在「同桶内已有多来源混淆风险」时延迟闭合，配合 `LogParseDiag` 计数；具体以 R-01 复核结论为准 |
| **D6** | 更新产物完整性方案（#5/#6 的纵深） | A：`update.json` 并列发布 sha256，安装前比对（gitee-release.py 已在发布侧算过，只差发布与消费两端）；B：平台级签名 jar | **A**——改动面最小（manifest 加字段 + 消费端比对），签名体系另立项 |

## 五、修复方案（按批次）

### 批次 0（立即行动，非代码项）

**T-SAF-01（#1 凭据，D2=A）**：
1. **用户操作**：在测试服务器轮换瀚高实例 xc3 凭据（旧凭据失效后历史中的字面量即无害）；
2. 代码库清理：删除 `.agents/skills/db-env-provisioning/SKILL.md:160` 的口令字面量（改为 `<强口令>` 占位，对齐 instances.example.tsv 口径），同步检查 `:81`/`:8` 的主机/端口是否需要收敛；
3. 防回退守卫：新增源码扫描测试（BannedApiSourceScanTest 模式），断言 `.agents/**`、`docs/**`、`dev-support/**` 无 `PASSWORD '...'` 字面量与未脱敏 jdbc 口令；
4. 验证：守卫测试对清理前内容转红、清理后转绿（NEEDS-VALIDATION #1 local 计划的落地）。

### 批次 1（复核波，先于对应修复；结论回填审计账本）

**R-01（#20/#21/#22/#12，解析器）**：对 `MyBatisLogParser`（iBatis 分支合入后）重走四条线索的源码轨迹：确认 `parseParams` 无截断（:174/:187）、`isIdle` 定义、兜底桶 fail-open、`ibatisPending` 边界；产出「维持/修正」结论并更新账本。顺带把 `ibatisPending` 的无界风险并入 T-SAF-22 修复面。
**R-02（#5/#6 + 新面快扫，更新链）**：对变更后的 `DynamicInstallSupport`（extractTag 现状 :218-219 已核实未变，确认调用链与新增分支）、`PlatformEpDynamism`、`DevUpdateMode`（预览清单与 zip 是否走同一门禁）重走 #5/#6 轨迹；新面结论登记为新账本单元或 hardening。
**R-03（#2，agent 侧）**：对变更后的 `SenderLoop`（看门狗/快速丢弃/CONFIG 应用行号偏移）重走 #2 的 agent 侧轨迹；IDE 侧（AgentManager/AgentSessionSupport 未变）直接沿用。

复核产物：更新 `coverage-ledger.json`（波次三：revalidation 单元）+ `findings.json`（如口径变化则换指纹或补充 evidence）→ 双验证器 PASS 后才进入批次 2 的对应修复。

### 批次 2（高价值边界修复，P1；每项的「验证」= NEEDS-VALIDATION 对应条目 local 计划升级为回归测试）

**T-SAF-02（#2，D5/R-03 后实施）**：agent 通道双向认证。最小修复：`AgentProtocol` 的 HELLO 帧加入启动时生成的通道令牌（IDE 生成 → 通过 `-javaagent` 参数或 attach 参数下发 → agent 回带 → IDE 校验；双向同一秘密）；`attachToPid`/premain 接受会话时校验令牌；`resumeAttachPortIfHit` 重绑定恢复同样先验令牌再服务 CONFIG。参数下发路径（`AgentProtocol.kt:67-68`）本身加令牌字段。回归：既有 `AgentManagerPremainTest`/`AgentManagerIntakeGateTest` 扩展「无令牌连接被拒」用例（即 NEEDS-VALIDATION #2 的两进程变体测试）。
**T-SAF-03（#3，D3=A）**：`McpDbExecutor.executeQueryInternal` 建立连接后立即 `setReadOnly(true)`（方言不支持或抛异常时按 D3 拒绝执行，错误码走 `McpToolErrors` 既有结构）；EXPLAIN 包装路径（Oracle/SQL Server channelNote）同样在只读连接上执行。回归：`McpSelectGuardTest` 补 assertAllowed("SELECT MY_WRITE_FN(1)")；新增 H2 CREATE ALIAS 只读阻断用例（NEEDS-VALIDATION #3 local 计划）。
**T-SAF-04（#4，D1=A）**：`DataSourceSelectorConsole.prepareAfterEditorResolved` 移除 `SessionClientHolder.CLIENT_KEY`（:294-302）与 EDITOR 注入（:283），使原生 ▶/Ctrl+Enter 在内嵌面板不可用；同步更新 `:240-244`/`:397-398` 注释与用户文档；保留插件按钮全门禁链。回归：新增「内嵌面板无原生执行 affordance」的入口点扫描断言（NEEDS-VALIDATION #4 local 计划的第一半）。
**T-SAF-05（#5，R-02 后实施，D6=A 的一部分）**：`checkDescriptorXml` 弃用 `extractTag` 正则，改走 `xml/SecureXml.parse` 解析描述符取真实 `<id>`/`<version>`（1MB 有界读取保留，SecureXml 已是该仓库唯一加固入口）；同步补「注释内假 id 不生效」回归用例（NEEDS-VALIDATION #5 local 计划的 fixture）。
**T-SAF-06（#6）**：`UpdateZipGuard.check` 增加条目名校验：拒绝 `..` 段、绝对/盘符路径、`:`（ADS）、Windows 保留设备名（CON/NUL/...）、尾部点/空格；不依赖平台解压器行为（无论平台是否兜底，插件侧先行拒绝）。回归：`UpdateZipGuardTest` 补 `../zipslip-probe.txt`、`C:\absolute-probe.txt`、`probe:ads.txt`、`CON.txt` 四类 fixture（NEEDS-VALIDATION #6 local 计划）。
**T-SAF-07（#7）**：`SqlVisualizerHistorySearchTool.formatResult` 在返回前按 `mcpMaskEnabled` 对 `sqlPreview` 应用 `DisplayMasker.maskDisplay`；更优落点是 `McpToolsetSupport` 共享骨架统一出口脱敏（一次修复覆盖未来新工具）——若骨架改动风险大则先落工具侧，骨架统一登记 hardening。回归：`HistorySearchToolTest` 补「maskOn 时字面量变 ***」用例，与 `RenderLogsToolTest` 同口径对照。
**T-SAF-08（#8）**：`SettingsIO.importFromJson` 对 SQL 位形字段加值级校验：`tenantColumnName`/`tablePrefix`/`tableSuffix` 走标识符形状白名单（`^[A-Za-z_][A-Za-z0-9_$]*$`）；`logicDeleteValue`/`tenantColumnDefault` 校验为单字面量（禁 `--`/`/*`/`;`/ OR 连接）；`dataPermissionMockCondition` 长度上限 + 含 `;`/`--`/`/*` 时告警；校验失败的字段**拒绝导入并逐字段报告**（不静默丢弃）。项目级 `.idea/mp-sql-visualizer-project.xml` 读取路径同样过该校验。回归：`SettingsIOTest` 注入 `logicDeleteValue='0 OR 1=1'` 等载荷断言被拒（NEEDS-VALIDATION #8 local 计划）。
**T-SAF-09（#9+#10）**：抽共享转义工具（建议 `util/HtmlEscape.kt`：转义 `& < > " '`），替换六处逐点实现（CommunityDialog:137、ExplainPlanResultView:260、ExplainPlanDialog:120、RollbackSqlDialog:1020、SqlResultPanel 内联、SqlPreviewPanelParamPanel:87）+ `HtmlReportFormatter`（tableName:72/dialect:73/source:147 补转义）；`RollbackSqlDialog:626-627`、`SqlPreviewPanelResultView` 元信息链（:622/:629/:650/:719/:728 + DialectStatusRefresh:259-262 tooltip）、`SqlResultPanel:277` tooltip 三处接入；对无需 HTML 的标签统一加 `putClientProperty("html.disable", true)`（对齐 DegradationBanner:57 既有模式）。回归：`HtmlReportFormatterTest` 补 tableName/source 载荷用例（NEEDS-VALIDATION #9 local 计划）；UI 三汇聚各补断言（#10 local 计划）。
**T-SAF-11（#11）**：先落定案测试：`McpSelectGuardTest` 增加 `WITH c AS (SELECT a INTO b FROM s) SELECT * FROM c` 与派生表变体，打印 JSqlParser 5.3 AST 形态（intoTables 落在哪个 PlainSelect）；若守卫放行则把 `hasIntoClause` 改为递归（WithItem/派生表/Join 子查询全遍历）+ 短语扫描补 `into <标识符>` 表单的词法判断（避免误伤普通标识符，采用「INTO 后跟非关键字标识符且位于子查询内」的组合判据）。回归：定案测试转为断言拒绝（NEEDS-VALIDATION #11 local 计划）。
**T-SAF-12（#12，D5 定案后，R-01 后实施）**：兜底桶配对防护。按 D5 推荐落地：兜底桶内 `pendingSql` 存续期间收到 Parameters 行时，若本控制台近期出现过 ≥2 个不同线程来源的无 token 行，则不立即配对、标记 `needsThreadContext` 并入 `LogParseDiag` 计数，超时后按现状闭合；单一流（绝大多数 stdout 场景）行为不变。回归：`LogParseConcurrencyTest` 的 A/B 交错 fixture 断言不再错配、单流 fixture 断言不受影响（NEEDS-VALIDATION #12 local 计划）。
**T-SAF-13（#13）**：`SqlLogAutoSaver.openWriterForCurrent`（:436-451）对齐修订路径：打开前 `fileKey` 同款 NOFOLLOW 属性检查（isRegularFile && !isSymbolicLink），打开时追加 `LinkOption.NOFOLLOW_LINKS`；被拒时记 warn 并跳过本批写入（不丢整批）。回归：`SqlLogAutoSaverTest` 符号链接 fixture（NEEDS-VALIDATION #13 local 计划）。

### 批次 3（资源上限统一，P2；统一原则：与兄弟通道同规）

**T-SAF-17（#17）**：四个 MCP 文本工具（check_sql/rollback_plan/reverse_sql/perf_advice）入口加 `sql.take(maxParseLength)`（复用 `PluginSettings.DEFAULT_LOG_MAX_PARSE_LENGTH=20000` 口径，或新增独立 `mcpMaxSqlLength` 设置默认 20k——取小者）；`SqlAstParser` 保持无状态不重复设限。回归：`CheckSqlToolTest`/`RollbackPlanToolTest`/`PerfAdviceToolTest` 各补超长拒绝用例（NEEDS-VALIDATION #17 local 计划）。
**T-SAF-18（#18）**：`SqlReverseParser` 加递归深度计数（parseConditionInternal/parseSubqueryTable 两个环入口，默认上限 256，超限抛 `SqlReverseException("嵌套过深")`——工具已有该异常通道，fail-closed 语义成立）。回归：`SqlReverseParserTest` 20000 层括号 fixture 断言结构化错误（NEEDS-VALIDATION #18 local 计划）。
**T-SAF-19（#19）**：`Ognl.kt` 三处封闭：①`Lexer.number()` 改 `raw.toLongOrNull() ?: throw OgnlParseException(...)`（20+ 位数字进入既有 Err 通道）；②`parse()`/`evaluate(text)` 外层补 `catch (t: Throwable) → ParseResult.Err/EvalResult.Err`（对齐 KDoc 承诺）；③递归下降加深度计数（与 T-SAF-18 同参数口径）。回归：`OgnlEvaluatorTest` 补 99999999999999999999 / 50000 层括号 / `<bind>` 渲染三用例（NEEDS-VALIDATION #19 local 计划）。
**T-SAF-20（#20，R-01 后实施）**：`MyBatisLogParser` 两处参数文本入口加 `take(maxParseLength)`（:174、:187）；iBatis 分支的参数/类型行同样套用。回归：`MyBatisLogParserTest` 补 `n=1k/10k/100k` 增长断言（NEEDS-VALIDATION #20 local 计划）。
**T-SAF-21（#21）**：`ExecutionTimeParser`：`TRAILING_MS_REGEX` 前导改为锚定形态（`(?:\]\s|\s)(\d+)\s*ms\s*$` 改为从行尾反向匹配或先 `takeLast(N)` 预截断），消除每空白位重试。回归：`ExecutionTimeParserTest` 增长 fixture（NEEDS-VALIDATION #21 local 计划）。
**T-SAF-22（#22，R-01 后实施）**：解析器挂起态老化统一：①给 `LogThreadRouter` 的桶加最后活跃时间戳，`evictIdle` 增加第二种淘汰（非空闲但超时 >60s 的桶强制 closePending 后驱逐）；②新增 `ibatisPending` 同样接入老化上限。回归：`LogThreadRouterTest` N=1k/10k 唯一 token fixture 断言桶数有界（NEEDS-VALIDATION #22 local 计划）。
**T-SAF-23（#23）**：`SqlLogFileReader` 读侧上限：单行累积上限（如 1MB，超限折叠为占位注释）+ 单文件上限（对齐写侧 100MB rollThreshold，超限拒读并告警）；`EntryBlockScanner` 同规。回归：`SqlLogFileReaderTest` 64MB 行 fixture 断言折叠（NEEDS-VALIDATION #23 local 计划）。
**T-SAF-24（#24）**：`HistorySearchService`：①正则模式长度上限 + 复杂度初筛（拒绝嵌套量词等高危形态——保守白名单或简单启发式）；②求值预算（每 entry 匹配步数/时限，超限该 entry 记 miss 继续）；③超预算整体 fail 到 `InvalidQueryException` 结构化错误。回归：`HistorySearchServiceTest` `(a+)+b` + 20k entry fixture 墙钟断言（NEEDS-VALIDATION #24 local 计划）。
**T-SAF-25（#25）**：`DynamicTableNameProcessor`：`regexCache` 改 `ConcurrentHashMap` 并设上限；匹配加步数/时限预算（与 T-SAF-24 同实现，抽共享 `BoundedRegex` 工具）；导入侧（T-SAF-08）已拒绝危险模式，此处兜底运行时预算。回归：`DynamicTableNameProcessorTest` 计时用例（NEEDS-VALIDATION #25 local 计划）。

### 批次 4（构建与文档防线，P2/P3；均为构建配置，不动 src/main）

**T-SAF-14（#14，D4=A）**：`settings.gradle.kts` 仓库顺序改为 mavenCentral 置前（阿里云降为后备）；生成并提交 `gradle/verification-metadata.xml`（`./gradlew --write-verification-metadata sha256`，按当前依赖闭包）；CI 全工作流确认严格校验生效。验证：NEEDS-VALIDATION #14 local 计划（校验和对 Central 一致 + 篡改 fixture 失败关闭）。
**T-SAF-15（#15）**：`gradle/wrapper/gradle-wrapper.properties` 加 `distributionSha256Sum`（取 services.gradle.org 官方 9.7.0-bin.zip 发布校验和）；CI 的镜像→官方重写逻辑保留不动。验证：NEEDS-VALIDATION #15 local 计划。
**T-SAF-16（#16）**：`mpv-verification.gradle.kts` 三个解析点（:204/:357/:481）改为按 pin 精确过滤（`verifier-cli-<pin>-all.jar`），找不到时 fail with 明确错误（不再 firstOrNull 兜底）；`VerifierCliVersionGuardTest` 反转为「断言解析点选中的版本 == pin」。验证：NEEDS-VALIDATION #16 local 计划。

## 六、验收标准

1. **复核定案**：R-01/R-02/R-03 产出结论，账本新增波次三单元，6 条受影响线索每条有明确的「维持/修正/推翻」记录；
2. **修复落地**：批次 2/3/4 全部工作项完成，每项附回归测试（引用 NEEDS-VALIDATION 对应 local 计划的 fixture）；新面快扫（DevUpdateMode/PlatformEpDynamism）有登记结论；
3. **账本闭环**：`findings.json` 中 25 条线索全部转为 `confirmed`（修复后可本地复现→已修复）或 `rejected`（复核推翻），无遗留 `needs_validation`；`validate-findings.cjs` + `validate-coverage-ledger.cjs` PASS；
4. **回归绿**：`testFast` 全绿 + 受影响分片（log/update/mcp/xml/config/reverse）定向 `--tests` 全绿；agent 子项目测试绿；
5. **回填纪律**：每条 src/main 修复按 AGENTS.md「提交即回填」进 CHANGELOG `[Unreleased]`（一句一条带提交哈希）；涉及行为变更的（#4 禁用原生入口、#3 拒绝非只读连接、#8 导入拒绝）在 Changed 段明示；
6. **收口**：缺陷/教训按 AGENTS.md 记入 `docs/LESSONS.md`（若形成复发规则）；可选终局：security-audit run-2 对修复后的源码做增量复核。

## 七、执行纪律

- 每个工作项独立提交，提交信息带审计指纹（如 `fix(log): ... [log.mybatis.parseparams.unbounded-quadratic-regex]`），便于审计账本反向追溯；
- 修复遵循审计 skill 的「最小有效修复」：只封已证实的边界缺口，hardening 清单（如 Actions SHA 固定、更新通知转义等）不混入本任务，另行择机；
- 复核波（批次 1）未出结论前，对应工作项不启动；
- 批次 0 的凭据轮换是用户侧操作，代码清理（守卫测试）可先行落地；
- D1~D6 未定案项在对应工作项启动前向用户确认（本文件已给推荐，可整体认可）。
