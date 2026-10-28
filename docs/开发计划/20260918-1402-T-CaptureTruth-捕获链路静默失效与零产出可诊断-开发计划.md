# T-CaptureTruth 捕获链路静默失效与零产出可诊断 — 开发计划

- 日期：2026-09-18 14:02
- 任务编号：T-CaptureTruth（P1；T-SettingsTruth 同族续作——上次收敛了「设置项是否接线」，这次收敛「接线了是否真的在工作」）
- 来源：用户报障「目前 sql 日志不能正常捕获到了，目前是启用了增强，在主分支的沙盒运行」
- 性质：不是解析器缺陷，是**捕获链路的授权态与运行态对外不可见**——开关显示开着、实际一条都没进，界面全程沉默

---

## 一、现象与复现坐标

复现环境（用户所指「主分支沙盒」）：

| 项 | 值 |
|---|---|
| 沙盒容器 | `build/mpsv-stfix/`（`build.gradle.kts` 当前 `sandboxContainer`，工作区在途改动，master 基线 1420673） |
| IDE 日志 | `build/mpsv-stfix/mybatis-plus-sql-visualizer/IU-2024.3.7.1/log/idea.log`（会话 13:38:21–13:46:01） |
| 沙盒插件设置 | `build/mpsv-stfix/.../config/options/mp-sql-visualizer.xml` |
| 被测工程 | `F:/code/Java/Gitee/SongRenShuo/easy-code-demo`（project=easy-code-demo） |

现象：点「捕捉」后捕捉态确实起来了（`capturing=true, consoles=1`），随后发起 3 次 HTTP 请求，SQL 日志面板**一条都没有**。

「一条都没有」有硬证据，不是主观感受：`logAutoOpenOnCapture` 默认 true（`config/PluginSettings.kt:194`），首条条目入队即打 `[M37.3] 首条 SQL 自动展开日志工具窗`（`log/SqlLogManager.kt:185-199`，门控只看「开关 × 未被用户关闭抑制 × 去抖窗」，不看工具窗可见性）。该会话日志里**没有这行**，也没有 `[T-AutoShowSuppress]`（说明自动展开处于武装态），故入队条目数 = 0。

## 二、证据链（全部实测，按时间序）

**1）增强捕获「开着但没授权」——沙盒设置文件只有拒绝时刻**

```
<component name="MpSqlVisualizerSettings">
  <option name="enhancedCaptureRejectedAtMs" value="1789710155256" />
</component>
```

`enhancedCaptureEnabled` 默认 true（`PluginSettings.kt:395`）、`enhancedCaptureConfirmed` 默认 false（`:402`），两者未落盘即取默认。即沙盒里的实际状态是：**开关开着、授权从未达成**。

对照真机 `%APPDATA%\JetBrains\IntelliJIdea2024.3\options\mp-sql-visualizer.xml`：`enhancedCaptureConfirmed=true`（09-17 授权过）。**同一个人、同一份代码，真机捕获正常、沙盒捕获为零，差别就在这一行。**

**2）13:42:24 主配置启动 → 弹确认框 → 13:42:35 被拒绝 → 进 24h 冷却**

```
13:42:24,453 AgentManager - onProcessAppeared: 增强已开启但未确认注入——请求确认后挂载
13:42:35,256 AgentManager - requestConsentThenAttach: 用户拒绝注入——进入 86400000ms 冷却（冷却期内不重复弹，不改同意门槛）
```

拒绝分支只写 `enhancedCaptureRejectedAtMs`（`agent/AgentManager.kt:234-237`），此后 `onProcessAppeared` 在 `isEnhancedConfirmed()` 与 `isInRejectCooldown()` 两道门（`:163-186`、`:194-200`）上直接返回——**24 小时内不弹窗、不排队、不挂载**。

**3）后续 3 次进程启动连弹窗资格都没有**

```
13:43:01,598 RunConfigurationProcessListener - processStarted: 非主配置进程启动（HTTP/脚本/profiler 等），行号不清零
13:43:01,598 AgentManager - onProcessAppeared: 增强已开启但未确认且非主配置进程——静默跳过（不弹窗不入队，T-PS4-01）
13:43:09,495 / 13:43:22,271 同上（另两次 HTTP 请求）
```

即使用户改跑主配置，也只会撞上冷却门——**当次会话增强捕获已彻底无望**，而界面没有任何提示。

**4）控制台回落通道在这个工程里本来就无料可捕**

捕获的另一条路（控制台桥 / EP 解析器）只吃控制台里的 MyBatis 日志行。easy-code-demo 的构建产物实证：

- `target/classes/application.yml`：`spring.profiles.active: 'dev'`（Maven `dev` 档为 `activeByDefault`；Spring Boot run 配置无 profile 覆盖，`.idea/workspace.xml:220-228`）
- `target/classes/application-dev.yml:35`：`log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl`（走 SLF4J，DEBUG 级）
- 同文件 `:45-47`：`logging.level` 整段注释掉 → root 保持 INFO → **DEBUG 被吞**
- `src/main/resources/logxml/logback-spring.xml:189` 那条 `cn.song.dasycodedemo.mapper level=TRACE` 生效不了：文件不在 classpath 根（Spring Boot 只自动加载根下的 `logback-spring.xml`），引用它的 `logging.config` 同样是注释态
- 只有 `prod` 档（`StdOutImpl`）才把 SQL 打到控制台

结论：**这个工程的控制台里没有 SQL 行**，控制台桥与 EP 解析器都无从产出。用户之所以「启用了增强」，正是因为增强是这条链路上唯一有效的通道——而它恰恰是唯一被授权闸门挡住的通道。

**5）界面沉默：状态行在「从未挂载」时是空白**

`AgentManager.statusSummary()`（`agent/AgentManager.kt:415-424`）只有三态：有活会话 → `已挂载 pid=…`；`everAttached` → `连接中断，已回落控制台捕获`；**其余 → `return null`**。面板把 null 过滤后写空串（`config/PluginSettingsPanel.kt:222-232`），状态行渲染成空白。于是用户看到的是：

> ☑ 增强捕获（agent 字节码通道，失败自动回落）　　（状态行：空白）

复选框勾着、状态行空白——「开着且没事」的错觉。更麻烦的是这条错误行为被守卫测试**钉死**了：`test/kotlin/.../config/SettingsTruthTest.kt:44-54` 断言 `statusSummary 无会话应返回 null`。T-SettingsTruth 的守卫覆盖的是「字段有没有接线」，覆盖不到「接线了有没有真的在跑」。

**6）旁证：本会话顺带暴露 6 例设置面板 Disposer 泄漏（T-SettingsTruth P2 引入）**

```
13:46:01,027 SEVERE - #c.i.o.u.ObjectTree - Memory leak detected: 'PluginSettingsPanel@33f77b6f' ... was registered in Disposer as a child of 'ROOT_DISPOSABLE' but wasn't disposed
  at cn.song.mybatisplus.visualizer.config.PluginSettingsPanel.<init>(PluginSettingsPanel.kt:424)
  at cn.song.mybatisplus.visualizer.log.SqlLogPanel$SettingsAction.actionPerformed(SqlLogPanel.kt:982)
```

12 行 SEVERE = 6 个不同实例，全部由面板「设置」按钮创建、全部指向 `PluginSettingsPanel.kt:424`——即 P2 为订阅 `AGENT_STATUS_TOPIC` 而写的 `Disposer.register(this@PluginSettingsPanel, connection)`。父节点（面板自身）此前不在 Disposer 树里，这一句把面板挂成 `ROOT_DISPOSABLE` 子节点，而 Configurable 路径没有任何地方 dispose 面板 → 每次开关设置页泄漏一个面板（连带一条 topic 订阅）。

**7）旁证二：把错误行为钉死的那个守卫，本身从未执行过**

`test/kotlin/.../config/SettingsTruthTest.kt` 的 `testP2_statusSummary三态源码守卫` 断言 `statusSummary 无会话应返回 null`——把本次缺陷（开着但未授权 → 状态行空白）钉成了期望行为。更糟的是：该类**既无 `@Test` 注解也不继承 JUnit3 `TestCase`**，任何 runner 都不会发现它的三个用例；全仓 `build/test-results/**` 里零出现 `SettingsTruthTest`（实测），即**这些守卫从来没有运行过**。T-SettingsTruth 交付时记的「SettingsTruthTest 守卫（testFast 全绿）」是假绿。全仓同类扫描：仅此一例。

## 三、根因

**直接原因（决定本次零捕获）**：沙盒是全新配置目录，`enhancedCaptureConfirmed=false`；首次主配置启动弹授权框被拒（13:42:35，11 秒后关闭），触发 24h 冷却；此后既不再询问、也不挂载。而该工程的控制台不含 SQL 日志，回落通道无料可捕。两条路同时归零。

**放大因素（把「零捕获」变成「用户查不出为什么」）**：

1. `statusSummary()` 把「开着但从未授权」与「开着且正常」都收敛成空白，用户没有任何观测面。
2. 拒绝 → 24h 静默锁死，唯一恢复路径是「设置页取消勾选 → 应用 → 重新勾选 → 应用」（靠 `SETTINGS_CHANGE` 的 false→true 上升沿重置冷却，`AgentManager.kt:131-136`）——而复选框本来就显示勾选态，用户不可能想到去取消再勾。
3. 面板「捕捉中但零条目」时不给链路状态（增强挂没挂、桥挂了几台控制台、控制台行有没有进解析器），用户只能看到一个空列表。

**潜伏风险（本次不是它，但它会独立制造同样的症状）**：T-PS4-02 的「双路解析封口」把控制台桥的解析**无条件让给 EP 通道**——`SqlCaptureGate.epActive` 一旦置位，桥既不再挂新 filter（`log/SqlLogConsoleBridge.kt:276-279`），已挂 filter 也逐行短路（`:346-350`）。而 `epActive` 是 JVM 全局粘滞、无任何复位点（`:722`，全仓仅 EP 首行一处写、桥侧两处读）。真机日志里这个封口**确实生效过**：

```
（build/mpsv-thic-sandbox/.../log/idea.log）
11:10:21,243 scanAll: 主控制台出现，触发 onConsoleAppeared
11:10:21,247 startCapture: 已开始捕捉 SQL（capturing=true, consoles=0）   ← 桥拒绝挂载，且无 attachFilter 行
```

`consoles=0` + 无 `attachFilter` 行 = 桥被 `epActive` 挡下。也就是说，从那一刻起该会话的捕获**完全押在 EP 通道上**，而 EP 生效性至今没有真机门禁结论——`log/SqlConsoleBridgeFilterProvider.kt:18-20` 的 KDoc 自己写着「真机门禁：runIde 观测 EP 生效且轮转停转——留人工」。T-PS4-02 是在这个未验证前提上加的封口：只要 EP 对某类控制台不生效（非 `ConsoleFilterProvider` 装配路径、插件自建控制台等），该控制台就是**永久静默零捕获，且回退路已被自己拆掉**。

## 四、方案

### T-CT-01（P1）增强捕获状态行补全「未授权 / 冷却中」两态，取消 null 空白

- `AgentManager.statusSummary()` 由三态扩为五态（`enabled`/`confirmed`/冷却/活会话/曾挂载交叉判定，文案走 bundle，禁止硬编码中文）：
  1. 有活会话 → `已挂载 pid=…`（现状不变）
  2. `enabled && confirmed && everAttached && 无活会话` → `连接中断，已回落控制台捕获`（现状不变）
  3. `enabled && confirmed && !everAttached` → `已授权，等待应用进程启动后挂载`
  4. `enabled && !confirmed && 冷却中` → `未授权注入（冷却中，剩余约 Xh）——点「重新授权」恢复`
  5. `enabled && !confirmed && 非冷却` → `未授权注入——下次启动主配置时会询问`
- 关闭态（`!enabled`）仍返回 null（此时复选框是未勾选，语义自洽）。
- **必须同步改守卫**：`SettingsTruthTest.kt:44-54` 的「无会话应返回 null」断言改为「关闭态才返回 null；开启未授权态必须非空且含授权提示」，否则修复会被自己的守卫判红。
- 判定逻辑抽纯函数（输入 enabled/confirmed/rejectedAtMs/clock/活会话摘要/everAttached → 文案键与参数），便于单测覆盖五态全分支，避免继续用源码字符串守卫。

### T-CT-02（P1）拒绝冷却给出恢复入口与倒计时

- 设置页增强捕获行加「重新授权」按钮（状态行右侧，仅在第 4/5 态可见）：点击 → 清零 `enhancedCaptureRejectedAtMs`（复用 `AgentManager.kt:131-136` 的重置语义，抽成 `resetRejectCooldown()` 单一入口）→ 立即重走 `requestConsentThenAttach` 的确认流（EDT 弹框）→ 同意即挂载并置 confirmed。
- 冷却文案带剩余时长（小时级取整，避免分钟级刷新压力；状态行本就由 `AGENT_STATUS_TOPIC` 迁移点刷新，不需要新增定时器）。
- 语义澄清：确认框「取消」按钮文案当前是 `取消`（`MpSqlVisualizerBundle_zh.properties:204`），但后果是 24h 静默锁死——改文案或在对话框正文补一句「取消后 24 小时内不再询问，可在设置页重新授权」，让退出路径的代价可见。

### T-CT-03（P1）捕获零产出可诊断：面板给出链路状态

- 在 SQL 日志面板的捕捉态提示区（`log.panel.capture.waiting.desc` 同区域）增加链路状态行，捕捉中且零条目时显示：
  - 增强通道：`未挂载（未授权）` / `未挂载（等待应用进程）` / `已挂载 pid=…` / `已回落`
  - 控制台通道：`已挂载 N 个控制台` + `已扫描 M 行 / 产出 0 条`
  - 若 M 很大而产出为 0，补一句定向提示：`控制台未见 MyBatis SQL 日志——请确认应用已开启 SQL 日志输出，或使用增强捕获`
- 数据来源（均为既有或近零成本新增）：`SqlLogConsoleBridge.attachedConsoles` 规模、`epActiveShortCircuitCount`（`SqlLogConsoleBridge.kt:271` 现成）、新增两个 AtomicLong 行计数/条目计数（在 `processConsoleLine` 出口与 `addEntry` 成功分支各 +1）。
- 空态文案 `log.panel.empty`（`MpSqlVisualizerBundle_zh.properties:335`）补一句条件提示，避免「点了捕捉却什么都没有」时无从下手。
- 该状态行同时是 T-CT-04 真机门禁的观测面（见下）。

### T-CT-04（P2）双路封口回落兜底与真机门禁

- **先证伪再改**：在开启 mapper DEBUG（见 §五）的工程上跑 runIde，验证 EP 通道对 Spring Boot run 控制台确实生效（判据：EP 置位后桥短路计数增长的同时条目仍单路产出）。结论写入本文档 §六，作为 T-PS4-02 前提的正式证据。
- 若 EP 覆盖不全，二选一（实施时按实测结论定）：
  - **A（推荐，改动小）**：`epActive` 由 JVM 全局粘滞改为「per-console 已覆盖」登记——EP filter 实例置位时登记其所属 console（或改为「EP 首次产出条目」才置位，纯解析失败不算覆盖）；桥对**未登记**的控制台照常挂载并解析。去重兜底（`SqlLogEntryBuffer` 同 SQL 同指纹）保持不变。
  - **B（保底）**：撤销桥侧短路，回到 T-PS4-02 前的双路解析（CPU ×2，靠缓冲去重保条目不重），仅保留 EP 置位后的挂载面守卫。
- 无论 A/B，`epActive` 需要可观测（面板状态行暴露短路计数），且**不得**再出现「封口生效而 EP 未验证」这种单向依赖。

### T-CT-05（P2）设置面板 Disposer 泄漏修复

- `PluginSettingsPanel.kt:424` 的 `Disposer.register(this@PluginSettingsPanel, connection)` 把面板自身挂进 Disposer 树（父节点未注册 → 落 `ROOT_DISPOSABLE`），而 Configurable 链路无 dispose 面板的动作 → IDE 退出报 Memory leak（实测 6 例）。
- 修法：连接注册到**有释放路径**的父节点（`SectionConfigurable` 的父 disposable / Configurable 自身），面板只在 `disposeUIResources()` 里断开订阅；不再让面板成为 Disposer 树节点。
- 守卫：新增断言「`PluginSettingsPanel` 不得把自己注册进 Disposer」（源码级，防回潮）；真机/沙盒验收判据 = IDE 退出日志零 `Memory leak detected: PluginSettingsPanel`。

### T-CT-06（P3）验证前提写进清单

- `docs/规范/测试沙盒验证清单.md` 与真机验证清单补一条：**验证控制台捕获链路前，先让被测应用真的把 SQL 打到控制台**（demo 工程：`logging.level.cn.song.dasycodedemo.mapper=debug`，或切 `prod` 档），否则「控制台桥」这条链在任何验证里都是空跑——本次排障绕了远路就是因为这个前提没写在清单里。

### T-CT-07（P1）确认框第三动作「关闭增强捕获并不再询问」根本没渲染出来

**实施期新增（用户提问触发）**：用户问「我加了一个关闭增强，是不是实现到其他分支了」。git 核实：**没跑到别的分支**——`1219d6a`（T-PS4-01）同时在 master 与 dev，沙盒已构建 jar 内含双语文案。真相是**按钮从未渲染**：

- 243 平台 `com/intellij/openapi/ui/DialogWrapper` 字节码直证 `init()` 方法体内调用 `createActions()`（本仓 Python 类文件解析实测，非推断）；
- 而 `EnhancedCaptureConfirmDialog` 把 `private val disableCaptureAction` **声明在 `init {}` 块之后**——Kotlin 属性初始化按文本顺序执行，平台在构造期回调 `createActions()` 时该字段仍是 null（非空类型不拦，编译静默通过），传进动作数组的是 null；
- 平台对 null 动作不抛错（沙盒日志无「确认框异常」为证），而是**静默不渲染该按钮**——用户只能看到「同意并启用 / 取消」，于是以为功能没做或跑到了别的分支。

修复与守卫：

- 属性声明**上移到 `init {}` 块之前**，并把「不得下移」写成 KDoc 硬约束（代码本身看不出这个约束，注释是唯一能留住它的地方）。
- 新增 `DialogWrapperInitOrderGuardTest`（convention 包，纯源码扫描进快桶）：**全仓扫描**所有 `DialogWrapper(` 子类，断言 `createActions()` 体引用的属性不得声明在 `init()` 调用之后——不限单个类，防同类回潮（扫描实测全仓仅此一处命中；`ShareDialog` 的同名命中是局部变量误报，已核对）。运行期测试无法覆盖本陷阱（平台不保存 createActions 的返回数组——243 字节码核实无 `Action[]` 字段，构造后反射读到的永远是已初始化值），只能源码级把关。

## 五、验证与验收

**立即可用的恢复路径（不等实施，先解锁当前沙盒）**

沙盒设置页 → SQL 日志区「增强捕获」取消勾选 → 应用 → 重新勾选（此时会弹授权框）→ 选「同意并启用」→ 应用 → 重启应用进程。或直接清空沙盒设置文件里的拒绝时刻后重启 IDE：

```
build/mpsv-stfix/mybatis-plus-sql-visualizer/IU-2024.3.7.1/config/options/mp-sql-visualizer.xml
→ 删除 <option name="enhancedCaptureRejectedAtMs" .../> 一行
```

**验证控制台链路（T-CT-04 前提）**：demo 工程加 `logging.level.cn.song.dasycodedemo.mapper=debug`（或 `-Dspring.profiles.active=prod`）后运行，控制台应出现 `==>  Preparing:`，此时再验 EP/桥的单路化与兜底。

**自动化**

- 定向：`AgentManager` 状态行纯函数五态单测、`SettingsTruthTest`（断言更新后）、`EpBridgeSinglePathTest`、`SqlConsoleBridgeFilterProviderTest`、`ConsoleReplayGuardTest`、`SqlLogManagerBehaviorTest`、设置面板泄漏守卫。
- 门禁：testShards 全量（口径按项目测试约定：test 前缀计数 / 0 失败）。

**真机/沙盒人工走查（不可省）**

1. 沙盒全新配置 → 启动 demo 应用 → 弹授权框 → 取消 → 设置页状态行应显示「未授权注入（冷却中，剩余约 24h）」而不是空白；点「重新授权」→ 同意 → 状态行转「已挂载 pid=…」。
2. 不点捕捉 / 点捕捉后无 SQL：面板链路状态行应如实反映「增强未挂载 / 控制台已挂载 N 台 / 扫描 M 行产出 0 条」。
3. 控制台有 SQL（开 mapper DEBUG）时：捕捉后条目正常产出，且 EP 与桥**不双份**（比对短路计数与条目数）。
4. IDE 退出：日志零 `Memory leak detected: PluginSettingsPanel`。

## 六、非目标

- 不改增强捕获的默认开启策略（`enhancedCaptureEnabled` 默认 true 是 2026-09-16 定案：控制台通道在相当多工程里无料可捕，agent 才是主通道）。本次只修「开着但没生效却看不出来」，不动默认值。
- 不动 `T-PS4-01` 的确认触发面收窄（未确认态非主配置进程静默跳过）与 24h 冷却窗本身——那是为解决「每进程弹一次模态框」的既有决策；本次只补可见性与恢复入口。
- 不改解析器（MyBatis/Hibernate/JdbcTemplate）与前缀匹配逻辑——本次零捕获与解析器无关。
- 不动 demo 工程的 `application-*.yml` 与 logback 配置（T-CT-06 只写验证前提，不改被测工程）。

## 七、实施记录

实施环境：工作树 `F:/code/Java/Gitee/SongRenShuo/mpsv-capture-truth`，分支 `t-capture-truth`（基线 master 1420673），沙盒容器 `mpsv-ct`（并行会话隔离，用后还原）。

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-CT-01 | 本批 | `statusSummary()` 拆为「取数 + 纯函数 `agentStatusText`（五态）」；新增 `AgentStatusSnapshot`；状态行文案全部 bundle 化（顺带销掉 T-SettingsTruth 遗留 P3「状态行硬编码中文」）。**偏离**：原计划只改断言，实施中发现 `SettingsTruthTest` **三个用例从未执行过**（无 `@Test` 注解也不继承 JUnit3 `TestCase`，全仓 test-results XML 零出现）——那条「无会话应返回 null」的假守卫因此从未发声；已补 `@Test` 使守卫真正生效，并把语义断言改为运行期测试（`AgentStatusTextTest` 八例）。 |
| T-CT-02 | 本批 | 新增 `AgentManager.requestReauthorization()`（清零冷却 + 重走确认流，无进程句柄时只恢复授权不尝试挂载）+ `needsReauthorization()`；设置页加「重新授权」按钮（仅未授权态可见）；确认流三态结果全部补 `publishStatusChanged()`（原先只有 attach/掉线/关闭三处广播，点完同意/拒绝后状态行会停在过期文案）；确认框正文补「取消的代价」一条。**偏离**：按钮监听器在字段初始化时挂一次而非 addRow 处挂（面板 `rebuild()` 会重跑 buildSections，就地挂会随重建叠加重复回调）。 |
| T-CT-03 | 本批 | 新增 `log/CaptureDiagnostics.kt`（纯函数 + `CaptureDiagnosticsTest` 七例）；桥加 `consoleLinesSeen` 计数；面板 `syncDiagnostics()` 写空态第二行，2s 定时器仅在「捕捉中且零条目」时运行（与桥轮询同频），命中条目或停止捕捉即停表，`dispose()` 停表。**偏离**：诊断行改为「等待控制台 / 控制台无输出 / 有输出但没匹配到 SQL」三档提示，比原计划单一提示更能分流现场。 |
| T-CT-04 | 本批 | **未按原计划做 per-console 覆盖**：平台 `Filter` 接口只有 `applyFilter(String,int)`（EP 拿不到控制台身份），per-console 判定需新开 `ConsoleViewImpl` 私有字段反射入口（触发项目「新增反射入口必登记 PlatformCrossVersionTest」的连带成本）。改为**语义收窄**：`epActive` 置位条件由「EP 处理过任意一行」改为「EP 实证产出过条目」，且桥**挂载面不再跳过**（封口只由 handler 逐行短路承担）——EP 未在链上/产不出条目时桥照常捕获，CPU 收益在 EP 真产出后与原先等价。**残留风险（真机门禁须覆盖）**：EP 证明过一次后本 IDE 会话内不再逐控制台重证，若某类控制台不经 provider 路径装配（如插件自建执行控制台、Gradle 构建控制台）仍可能被短路。 |
| T-CT-05 | 本批 | 删 `Disposer.register(this@PluginSettingsPanel, connection)`（ROOT 子节点无释放路径 → 沙盒实测 6 例 leak），连接生命周期回归 `disposeResources()` 显式承担；`SettingsTruthTest` 加「不得自注册 Disposer」断言（扫注释剥离后的源码——守卫扫原文时会被解释性注释误伤，已加 `stripComments`）。 |
| T-CT-06 | 本批 | `docs/规范/测试沙盒验证清单.md` 补「验控制台捕获链路前先让应用真的把 SQL 打到控制台」前提（含 demo 工程两种开法）。 |
| T-CT-07 | 本批 | **实施期新增（用户提问触发）**：确认框第三动作「关闭增强捕获并不再询问」**从未渲染**——`disableCaptureAction` 声明在 `init {}` 之后，平台在构造期（`init()` 内）回调 `createActions()` 时该字段仍为 null，平台容忍 null 静默不渲染按钮（243 平台字节码直证 + 沙盒日志无「确认框异常」双重坐实；git 核实该功能**并未**落到其他分支，master/dev 均有）。修复=属性上移到 init 块之前；新增 `DialogWrapperInitOrderGuardTest`（全仓扫描 `DialogWrapper` 子类的 createActions 后声明引用，纯源码扫描进快桶）。 |

**实施期新发现（附带修复）**

1. `SettingsTruthTest` 三个用例从未执行（见 T-CT-01 偏离）——「守卫存在但从未运行」是比守卫宽松更隐蔽的假绿形态；全仓扫描确认仅此一例。
2. 平台 `DialogWrapper.init()` 在构造期回调 `createActions()`（243 字节码实证），且**不保存**该返回值（无 `Action[]` 字段）——该类陷阱无法用运行期测试覆盖，只能源码级守卫。
3. `testFast` 划桶按**测试源码全文**匹配 `com.intellij`，注释里出现平台 FQCN 也会被划出快桶（本轮实测）。

**验证结果**

- `testFast` 2293 例绿（含新增 `AgentStatusTextTest` 8 例、`CaptureDiagnosticsTest` 7 例、`DialogWrapperInitOrderGuardTest` 2 例、改写后的 `SettingsTruthTest` 4 例）。
- 守卫先红后绿：`DialogWrapperInitOrderGuardTest` 对「临时复刻的属性后声明」形态两条断言均红（21s 实证），还原修复版后转绿。
- 定向平台桶：`EpBridgeSinglePathTest`（含新增 T-CT-04 回归用例）、`SqlConsoleBridgeFilterProviderTest`（改写置位语义）、`SqlCaptureGateSniffTest`、`SqlLogManagerBehaviorTest`、`SqlLogPanelLayoutTest`、`AgentConsentFlowTest`、`AgentManagerFallbackTest` 全绿。
- 全量门禁 `testShards`：**4429 / 0 失败 / 0 错误 / 6 跳过**（367 个套件；对比 master 基线 4406，+23 例 = 新增三类 17 例 + 被唤醒的 `SettingsTruthTest` 4 例 + `EpBridgeSinglePathTest` T-CT-04 回归 1 例 + 改写净增 1 例）。
- **真机/沙盒走查留人工**（T-CT-01/02/03 状态行与诊断行、T-CT-04 单路化与残留风险面、T-CT-05 退出零 leak、T-CT-07 第三按钮可见）。

