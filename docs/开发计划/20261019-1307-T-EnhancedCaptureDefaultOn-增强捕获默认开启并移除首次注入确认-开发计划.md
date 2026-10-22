# T-EnhancedCaptureDefaultOn 增强捕获默认开启并移除首次注入确认——开发计划

- 立项时间：2026-10-19 13:07
- 基线：`dev` `087c336d`（工作树 `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev`）
- 状态：已实施（2026-10-19，dev 工作树，提交 `5b4fb4e0` + 守卫修复 `c00b8827`/`a1a4da47`）。定向 17 类 **148/0/0/0**；反向三组各恰 1 红（G2 恢复 confirmed 闸门 / G1 恢复 `requestReauthorization` 符号 / G3 摘 `loadState` 归一）。隔离工作树 `a1a4da47` 权威 `testShards` **7892/0/0/9** + testExternal **31/0/0/1** + agent **164/0/0/0**，parity 720 类全覆盖；shard2 有 +40% 权重告警（画像过期/新类缺权，非失败）。**沙盒真机验收已过（2026-10-19 晚，243 沙盒）**：项目打开/首次 Run 全程无注入确认弹窗（当日会话日志 consent 轨迹 0 条），premain 首轮即注入并建会话（`premain 会话建立：pid=24136`），SQL 执行产生条目。未合并 master、未推送。
- 编号：不预占 O/R/D/V，实施前按台账登记。
- 关联：`T-FirstUsePremainConsent`、`T-FirstUseFreshInstallConsent`、`T-ConsentTruth`、`T-CaptureTruth`、`T-PS4-01`；`docs/设计规范-弹窗克制红线.md`；`20261015-T-Jdk8RedisAgentStartupCrash-JDK8接入Redis后增强捕获启动崩溃排查-开发计划.md`。

## 一、需求与结论

用户要求：增强捕获默认开启，不再在首次开启时弹窗询问。

代码事实与需求字面有一处偏差，先说清楚，避免实施时改错地方：

- `PluginSettings.State.enhancedCaptureEnabled` 的默认值**已经是 `true`**（`config/PluginSettings.kt:593`，2026-09-16 定案）。
- 真正让用户看到首次弹窗的是 `enhancedCaptureConfirmed` 默认 `false`（`config/PluginSettings.kt:600`），以及围绕它的五处确认入口和 24 小时拒绝冷却。
- 因此本任务不是「把默认值从关改成开」，而是**拆掉知情同意闸门**：把「开关打开」作为唯一授权语义，删除模态确认、拒绝冷却、重新授权入口与「未授权」状态叙事。

结论：默认开启已经在代码里成立；本次改造的目标是让它**直接生效、零弹窗**，同时保留用户随时关闭的开关和如实反映挂载状态的设置页状态行。

## 二、现状盘点（dev@087c336d）

### 2.1 确认入口五处

| 触发时机 | 代码位置 | 当前行为 |
|---|---|---|
| 项目打开 | `log/LogStartupCapture.kt:21-28` → `AgentManager.maybeAskConsentAtStartup()`（`log/agent/AgentManager.kt:740-760`） | 出厂未确认态在项目打开期 `invokeLater` 弹一次 |
| 首次 Run 的准备期 | `log/agent/PremainRunConfigurationExtension.kt:52-60` → `AgentManager.ensureConfirmedBeforePremain()`（`AgentManager.kt:694-715`） | 未确认且 EDT/非 headless 时同步弹模态框，同意后本轮才带 `-javaagent` |
| 应用进程出现（attach 链） | `AgentManager.onProcessAppeared`（`AgentManager.kt:538-565`）→ `requestConsentThenAttach`（`:618-645`） | 主配置启动且未确认时弹框，同意后挂载 |
| 设置页勾选开关 | `config/PluginSettingsPanelSections.kt:588-603` | 勾选且未确认时弹框，取消则把复选框回滚为未选 |
| 设置页「重新授权」/ 缺源码引导 | `config/PluginSettingsPanel.kt:263-310`；`log/SourceCaptureGuide.kt:39-42, 120-140` | 清冷却后重走确认流；引导链「开启增强捕获」也复用该确认流 |

### 2.2 确认状态与冷却

| 字段/机制 | 位置 | 语义 |
|---|---|---|
| `enhancedCaptureConfirmed: Boolean = false` | `PluginSettings.kt:600` | 同意记忆；false 时上述五处会弹框 |
| `enhancedCaptureRejectedAtMs: Long = 0L` | `PluginSettings.kt:609` | 拒绝时刻；24 小时冷却 |
| `DEFAULT_REJECT_COOLDOWN_MS` / `rejectCooldownMs` | `AgentManager.kt:68-77, 262` | 冷却窗口 |
| `DEFAULT_CONSENT_ASK_GUARD_MS` / `consentAskGuardMs` / `lastConsentAskAtMs` | `AgentManager.kt:74-77, 273-277` | 上升沿与引导链连发时的去重窗 |
| 三态结果 `EnhancedCaptureConsent` | `config/EnhancedCaptureConfirmDialog.kt:22-26` | ACCEPTED / DISABLE_CAPTURE / REJECTED |

### 2.3 状态行与引导分支

- `AgentStatusSnapshot` 含 `confirmed` 与 `rejectCooldownRemainingMs`（`log/agent/AgentSessionSupport.kt:43-56`），`agentStatusText` 的「未授权 + 冷却中」「未授权 + 待询问」两态直接来自它们（`:78-114`）。
- `SourceCaptureGuide.decideActions(enabled, confirmed)` 用 `confirmed` 决定引导分支（`log/SourceCaptureGuide.kt:39-42`）。
- `PremainInjection.decide` 的第二道闸门就是 `enhancedConfirmed`（`log/agent/PremainInjection.kt:16-20, 44-56`），对应 `REASON_UNCONFIRMED`。

### 2.4 对外文案与文档

- 中英文 bundle 的 `settings.enhancedCapture.confirm.*`、`settings.enhancedCapture.reauth*`、`settings.enhancedCapture.status.unauthorized*`（`MpSqlVisualizerBundle_zh.properties:508-517`、`MpSqlVisualizerBundle.properties:533-540`）。
- `log.jump.guide.message.enhanceFirst` 与 `log.inject.unsupported.enhanceFirst` 仍写着「同意授权并重启应用后生效」。
- `README.md:120`「启用或首次打开未授权项目时先确认」、`README.md:156`「在增强捕获提示中确认接入」。
- `docs/架构设计/01-项目架构总览.md:377`、`docs/架构设计/02-核心流程详解.md:455/532/548/567/713` 描述确认流与未授权态。
- `docs/测试沙盒验证清单.md:24` 仍在检查「未授权/冷却中/重新授权」。

## 三、目标与非目标

### 目标

1. 项目打开、首次 Run、应用进程出现、设置页勾选、缺源码引导五条路径都**不再出现注入确认弹窗**。
2. 授权语义收敛为「增强捕获开关打开」；关闭开关仍立即停采、关闭会话、回落控制台捕获。
3. 既有用户升级后静默可用：旧 XML 里的 `confirmed=false`、`rejectedAtMs>0` 不得继续拦挂载；旧 `enabled=false` 必须保持关闭。
4. 设置页状态行只保留可核实的运行态（已挂载 / 等待应用启动 / 连接中断回落 / 心跳类回落），不再出现「未授权」「冷却中」死态。
5. 不新增任何插件主动弹窗或通知，包括一次性气球提示；披露面放在设置页开关说明与文档里。

### 非目标

- 不改 agent 织入面、`DriverRegistry` 驱动名单、事件协议、premain/attach 的选择逻辑。
- 不改脱敏默认值（采集侧与展示侧仍默认关）与脱敏链。
- 不解决 JDK 8 接入 Redis 后的启动崩溃；该事故另行跟踪，但本计划在风险节登记「默认开启会扩大 agent 注入人群」。
- 不删除持久化字段，不做需要用户重装或清配置的破坏性迁移。
- 不改变「测试/IDE 任务 fork JVM 不注入」的范围闸门（`PremainTaskScope`）。

## 四、实施方案

### W1 状态模型与兼容迁移

1. `PluginSettings.State.enhancedCaptureConfirmed` 默认值改为 `true`，加 `@Deprecated` 注释：字段仅为旧版本回退与 XML 兼容保留，**运行期不再参与任何判定**。
2. `enhancedCaptureRejectedAtMs` 同样标记 `@Deprecated`，不再被读取。
3. `PluginSettings.loadState` 做一次幂等归一：把 `enhancedCaptureConfirmed` 置 `true`、`enhancedCaptureRejectedAtMs` 置 `0`；**不得触碰 `enhancedCaptureEnabled`**，用户显式关闭必须保留。
4. 归一化只改这两个字段，不发布 `SETTINGS_CHANGE_TOPIC`，避免 JSON 导入或设置页 Apply 时触发额外挂载/扫描。
5. 不引入版本号迁移。理由：字段保留且默认 `true`，旧 XML 读到 `false` 也会在 `loadState` 被覆盖；新 XML 被旧版本读取时字段为 `true`，旧版本不会弹框，回退体验也更好。

### W2 五处入口收口

| 工作项 | 改动 |
|---|---|
| W2.1 项目打开 | `LogStartupCapture.execute` 删除 `maybeAskConsentAtStartup()` 调用及整段注释（`LogStartupCapture.kt:21-28`）；`AgentManager.maybeAskConsentAtStartup` 删除。自动监听逻辑不动。 |
| W2.2 premain 准备期 | `PremainRunConfigurationExtension.updateJavaParameters` 删除 `manager.ensureConfirmedBeforePremain()`（`:57`），类注释中关于「未确认态前置确认」的段落同步改写；`preparePremainVmOption` 只保留 `enabled` 闸门。 |
| W2.3 attach 链 | `AgentManager.onProcessAppeared` 删除 `!isEnhancedConfirmed()` 整段分支（`:550-561`），在目标探测通过后直接 `doAttachAsync(processHandler)`。保留非主配置与目标探测闸门——它们与用户授权无关，属于防误注入。 |
| W2.4 设置变更上升沿 | `AgentManager` 的 `SETTINGS_CHANGE` 监听（`:340-390`）删除 `needsReauthorization() && premainConsentGate()` 确认块与 `consentedNow` 变量；保留「重置用户暂停」与「扫描在跑进程补挂」；补挂改为无条件执行。 |
| W2.5 设置页勾选 | `PluginSettingsPanelSections` 的 `enhancedCaptureCheck` 监听（`:588-603`）删除弹窗、`confirmed` 写入与取消回滚；勾选/取消只做状态变更，保留 `refreshEnhancedStatusLabel()`。 |

### W3 删除确认流、冷却与死代码

`AgentManager` 删除以下成员（含常量、字段、函数）：

- 常量与字段：`DEFAULT_REJECT_COOLDOWN_MS`、`DEFAULT_CONSENT_ASK_GUARD_MS`、`rejectCooldownMs`、`consentAskGuardMs`、`lastConsentAskAtMs`。
- 函数：`isInRejectCooldown`、`remainingRejectCooldownMs`、`requestReauthorization`、`requestConsentThenAttach`、`confirmEnhancedCapture`、`ensureConfirmedBeforePremain`、`premainConsentGate`、`maybeAskConsentAtStartup`、`requestConsentOnce`、`consentProvider`、`needsReauthorization`、`isEnhancedConfirmed`。
- `isEnhancedActive()` 折叠为 `isEnhancedEnabled()`。
- **不要删** `onCaptureConfirmationChanged`：它指 agent 的 CONFIG 回执，不是用户授权，二者只是命名相近。

其他文件：

- `PremainInjection` 删除 `REASON_UNCONFIRMED` 与 `enhancedConfirmed` 参数，签名改为 `decide(enhancedEnabled, agentJarAvailable, port)`；调用方与表驱动测试同步。
- 删除 `config/EnhancedCaptureConfirmDialog.kt` 整个文件（含 `EnhancedCaptureConsent` 枚举）。
- `SourceCaptureGuide`：
  - `decideActions(enabled, confirmed)` 改为 `decideActions(enabled)`；`EnhanceFirst` 仅在开关关闭时成立。
  - `enableEnhancedCapture` 去掉三态回调：置 `enabled=true`（原本已开则不重复发布）→ 发布 `SETTINGS_CHANGE_TOPIC` → 展示「已开启，重启应用后生效」。
  - `showEnhanceDone` 标题改用新键 `settings.enhancedCapture.enableDone.title`，不再复用确认框标题键。
  - `showUnsupportedWithEnhanceGuide` 的文案与分支同步。

### W4 UI、状态行与文案

1. `PluginSettingsPanel`：删除 `enhancedReauthButton` 字段、`onReauthorizeClicked` 方法与 `addRow` 中的按钮行（`PluginSettingsPanelSections.kt:617-620`）；`refreshEnhancedStatusLabel` 只写文本，不再控制按钮可见性。
2. `AgentStatusSnapshot` 删除 `confirmed` 与 `rejectCooldownRemainingMs`；`agentStatusText` 删除未授权两态，`statusSummary()` 的构造同步。
3. 状态行文案：
   - `settings.enhancedCapture.status.authorizedWaiting` 改为「已开启，等待应用进程启动后挂载」。
   - 删除 `settings.enhancedCapture.status.unauthorizedCooldown`、`settings.enhancedCapture.status.unauthorizedPending`。
4. Bundle 删除：`settings.enhancedCapture.confirm.title/ok/cancel/disable/body`、`settings.enhancedCapture.reauth`、`settings.enhancedCapture.reauth.tip`；新增 `settings.enhancedCapture.enableDone.title`。中英双份同步。
5. 引导文案去掉「同意授权」：
   - `log.jump.guide.message.enhanceFirst` 改为「开启增强捕获并重启应用后生效」。
   - `log.inject.unsupported.enhanceFirst` 同上。
6. 给设置页开关补 tooltip（建议做，不是硬性）：说明「默认开启；在应用 JVM 内采集 SQL 参数、耗时与调用位置；数据只留在本机；可随时关闭并自动回落控制台捕获；JDK 21+ 可能出现一条动态挂载告警，属预期」。新增键 `settings.enhancedCapture.tip`，中英双份。
7. `PluginSettingsPanel.kt:246` 的「默认关」注释是陈旧描述，改为「默认开」。

### W5 测试与守卫改造

删除或重写以下测试：

| 文件 | 处理 |
|---|---|
| `config/EnhancedCaptureConsentDialogTest.kt` | 随对话框删除 |
| `log/agent/AgentConsentFlowTest.kt` | 删除；其「三态落盘/冷却」语义已不存在 |
| `log/agent/AgentPremainConsentTest.kt` | 重写为「premain 只看 enabled」；删除确认/冷却/去重用例 |
| `log/SourceCaptureGuideEnableFlowTest.kt` | 重写为「开启即置位并发布，不再要求 confirmed」 |
| `convention/DialogWrapperInitOrderGuardTest.kt` | 删除两条 `EnhancedCaptureConfirmDialog` 专属用例（`:347-390`）；保留通用扫描器自证用例 |
| `config/SettingsTruthTest.kt` | 删除 `requestReauthorization`/`confirmed` 相关断言（`:175, 185-193, 209`），改判「不得再存在确认入口」 |
| `config/PluginSettingsPersistenceTest.kt`、`config/SettingsIOTest.kt` | 保留字段持久化断言，但改为验证归一后的 `true/0` |

需要逐条核对并调整的既有用例（把 `confirmed=false` 当闸门用的）：

- `log/agent/AgentAttachChainTest.kt:29-43`
- `log/agent/AgentAttachTargetGateTest.kt:30-41`
- `log/agent/AgentHeartbeatWindowTest.kt:39-58`
- `log/agent/AgentManagerFallbackTest.kt:24-92`
- `log/agent/AgentManagerPauseGateTest.kt:171-178`
- `log/agent/AgentManagerPremainReattachTest.kt:62, 396`
- `log/agent/AgentManagerPremainTest.kt:34-66, 398`
- `log/EmptyTextRenderTest.kt:98` 的注释引用（不涉及断言，顺带校正）

新增守卫：

- **G1 源码扫描**：`src/main` 不得再出现 `EnhancedCaptureConfirmDialog`、`requestConsentOnce`、`requestReauthorization`、`needsReauthorization`、`settings.enhancedCapture.confirm`。反向：临时恢复任一符号，守卫必红。
- **G2 行为守卫**：`enabled=true` + `confirmed=false`（模拟旧 XML）时，`onProcessAppeared` 仍进入挂载链、`preparePremainVmOption` 仍产出 `-javaagent`。反向：临时恢复 `!isEnhancedConfirmed()` 闸门，用例必红。
- **G3 持久化守卫**：加载含 `confirmed=false`、`rejectedAtMs>0` 的旧状态后，两字段归一为 `true/0`；`enabled=false` 保持 `false`。

### W6 文档同步

- `README.md:120`、`README.md:156`：改为「增强捕获默认开启，无需确认；可在设置中关闭」。
- `docs/架构设计/01-项目架构总览.md:377`：删除「首次挂载确认」描述。
- `docs/架构设计/02-核心流程详解.md:455/532/548/567/713`：删除确认流、未授权分支与「重新授权」措辞，改为开关态与运行态两分。
- `docs/测试沙盒验证清单.md:24`：删除「未授权/冷却中/重新授权」检查项，改为「首次 Run 无弹窗、默认挂载、关闭开关后回落」。
- `docs/设计规范-弹窗克制红线.md`：重跑文档给出的 grep 基线，更新触点清单。本计划不新增主动触点，只移除一个用户触发的确认框。
- `docs/索引/开发计划.md`：登记本计划。
- `CHANGELOG.md` / `docs/变更记录`：实施批次落地时补一行；本计划阶段不写。

## 五、实施顺序与写集

建议顺序：W1 → W2 + W3（同一笔或紧邻两笔，避免中间态编译不过）→ W4 → W5 → W6。

写集（以 dev@087c336d 为准）：

```
src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt
src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt
src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanelSections.kt
src/main/kotlin/cn/song/mybatisplus/visualizer/config/EnhancedCaptureConfirmDialog.kt   （删除）
src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogStartupCapture.kt
src/main/kotlin/cn/song/mybatisplus/visualizer/log/SourceCaptureGuide.kt
src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt
src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentSessionSupport.kt
src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/PremainInjection.kt
src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/PremainRunConfigurationExtension.kt
src/main/resources/messages/MpSqlVisualizerBundle_zh.properties
src/main/resources/messages/MpSqlVisualizerBundle.properties
src/test/kotlin/...（见 W5 清单）
README.md
docs/架构设计/01-项目架构总览.md
docs/架构设计/02-核心流程详解.md
docs/测试沙盒验证清单.md
docs/设计规范-弹窗克制红线.md
docs/索引/开发计划.md
```

实施纪律：

- 每完成一件可提交的改动立即提交，避免掉线丢成果。
- 只跑本任务最相关的定向测试；不跑全量、不跑构建校验、不 `clean`、不 `--stop`。
- 全部实施完成后，由独立只读收口代理单跑一次 `testShards --no-configuration-cache`，主控并行写文档，不空等。
- 提交信息用中文，写 UTF-8 消息文件后 `git commit -F`。

## 六、待验证前提

1. **默认值基线**：`enhancedCaptureEnabled=true` 在 dev 成立已核实（`PluginSettings.kt:593`）。实施前仍需用 `git show <发行 tag>:<路径>` 核对在售版本是否同值；若在售版本是 `false`，本任务要同时补默认值修改，不能只删确认流。
2. **`loadState` 归一化的副作用**：设置页面板的 `isModified` 对比读同一 `state`，归一化发生在加载期，理论上不会制造假脏态；但需在定向测试里显式覆盖「打开设置页 → 不做任何修改 → Apply 按钮不可用」。若出现假脏态，改用「`getState` 读取时忽略字段」或在 `PluginSettings` 初始化时归一，不动 `loadState`。
3. **非 EDT 的 premain 路径**：原确认有 `premainConsentGate()`（EDT 且非 headless）守卫。删除弹窗后，`updateJavaParameters` 在任意线程都应能提取 jar 与协商端口；需确认 `extractPremainAgentJar` / `openPremainChannel` 没有隐藏的 EDT 假设。理论上删除弹窗后更安全，但必须用测试或沙盒日志证实。
4. **旧用户显式关闭不被误开**：归一化只碰 `confirmed`/`rejectedAtMs`，不碰 `enabled`。需覆盖 `enabled=false` 的旧 XML。
5. **多项目并发**：项目打开期不再有确认请求，`maybeAskConsentAtStartup` 的去重窗也随之删除；需确认多项目同时打开不会引入新的挂载竞态（挂载入口仍有 `attachedPids` 幂等表与 attach 去抖）。
6. **JDK 21+ 告警观感**：无确认框后，用户首次看到应用控制台的动态挂载告警可能困惑。设置页 tooltip 与 README 必须写明这是 JEP 451 预期行为，不自动改用户配置。
7. **旧版本回退**：旧版本读取新 XML 时 `confirmed` 为 `true`，理论上不再弹框；但旧版本 `State` 的默认值是否为 `false` 需以对应 tag 源码核实。即使旧版再弹一次，也属可接受的回退行为。

## 七、风险与回退

- **隐私与合规**：不再有显式知情同意，这是用户明确的产品决策。披露面改为设置页开关说明、tooltip、README 与文档；数据仍只留在本机、不上传，脱敏开关保持原默认。
- **JDK 8 + Redis 启动崩溃未定**：该事故（`20261015-T-Jdk8RedisAgentStartupCrash`）尚未取得原始 `hs_err` 与稳定复现。默认开启会扩大被注入 agent 的用户范围，尤其是此前点「取消」的人。建议：默认开启先落 dev 验证；发 master 或发版前，至少把该事故推进到「是否与 agent 相关」的结论。不得把本计划当作该崩溃的修复，也不得用「默认开启后没崩」当作根因结论。
- **用户已被拒绝过（旧 `rejectedAtMs>0`）升级后会被开启采集**：这是本需求的直接结果。设置页开关可一键关闭，关闭后立即停采并回落控制台。
- **回退路径**：保留 `enhancedCaptureConfirmed` 与 `enhancedCaptureRejectedAtMs` 字段，恢复确认流只需回滚本批提交；字段仍在，旧逻辑可直接读。不需要数据修复脚本。

## 八、验证与验收

### 定向测试

至少覆盖：

- `PluginSettingsPersistenceTest`、`SettingsIOTest`：旧状态归一、`enabled=false` 保持、`enabled=true + confirmed=false` 直接可用。
- `PremainInjection` 表驱动：`enabled=false` 跳过；`enabled=true` 且 jar/port 可用时注入；不再有 unconfirmed 行。
- `AgentManager` 挂载链：`confirmed=false` 不拦 `onProcessAppeared`；`preparePremainVmOption` 产出选项。
- `AgentSessionSupport` 状态行纯函数：新状态集全覆盖，未授权两态不再存在。
- `SourceCaptureGuide`：`decideActions(enabled)` 两分支；`enableEnhancedCapture` 置位并发布。
- 新增 G1/G2/G3 守卫。

### 反向验证

1. 临时恢复 `if (!isEnhancedConfirmed()) { requestConsentThenAttach(...) }` → G2 与挂载链用例必红。
2. 临时恢复 `maybeAskConsentAtStartup()` 调用 → G1 源码扫描必红。
3. 临时移除 `loadState` 归一 → G3 必红（旧 XML 加载后仍为 false/非 0）。

两次读数都要贴出来，不能只写「已反向验证」。

### 真机/沙盒验收

在 runIde 沙盒 + demo 应用（`mybatis-plus-demo`，8081）走一遍：

1. 首次启动 IDE、首次 Run 应用：**无任何注入确认弹窗**。
2. 应用启动后 SQL 日志面板出现 agent 条目（有耗时、库角标、`文件:行号`），设置页状态行显示「已挂载 pid=…」。
3. 关闭增强捕获开关：应用侧停采、面板回落控制台捕获、状态行不再显示已挂载。
4. 重新打开开关：已在跑的进程被补挂（上升沿扫描），无需重启应用。
5. 重启 IDE：仍默认开启，不弹窗。
6. JDK 21+ 目标：控制台出现动态挂载告警属预期，插件不改用户 VM 配置。

真机走查留用户或按项目 GUI 验收流程执行；本轮计划不声称已验。

## 九、决策点

已按用户需求定下、实施时不再回头讨论的：

- 不新增一次性通知或气球提示；「不再提示」按字面执行，披露面放设置页与文档。
- 保留 `confirmed` / `rejectedAtMs` 字段做兼容，不删字段、不做破坏性迁移。
- 旧 `enabled=false` 不覆盖，尊重用户显式关闭。

实施前建议确认的两项（不阻塞写码，但影响发布节奏）：

1. 默认开启是否随下一版直接发布，还是先在 dev 观察一轮再合 master。计划建议后者，并与 JDK 8 + Redis 事故的结论对齐。
2. 是否顺带把采集侧脱敏默认改为开。计划建议**不改**：本任务只动授权语义，扩大默认脱敏会改变既有用户看到的参数值，属另一个产品决策。
