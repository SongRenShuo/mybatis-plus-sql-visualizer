# T-StackBizFrame：增强捕获调用栈项目帧丢失收口开发计划

- 日期：2026-10-29 10:12；分析基线：`master@a8e330bd`。
- 状态：**已实施（2026-10-29）**——S1 三层锚点、S2 定位帧必在栈内、S3 三条（驱动族折叠、内联摘要取景、弹层标题带线程名）均已落码；全量门禁读数见 §8.3/§8.10，P-5 压测与真机 @Scheduled 对拍见 §8.8/§8.9。遗留见 §8.7。本计划不预占 O/R/D/V 编号。
- 关联：`20261007-0829-T-StackHoverUi-预览面板调用栈悬停UI调试器风格化-开发计划.md`（两端采样与帧数可配的来源）、`20261015-T-StackFramesFold-调用栈业务帧优先与原生折叠视图-开发计划.md`（弹层折叠视图与 Controller 锚点补偿）。本计划处理它们遗留的**非 Web 线程入口**形态，不重复立项已实现部分。

## 一、报障与复现

用户 2026-10-29 在 IDEA 2026.2 实测（PostgreSQL + Hikari + MyBatis-Plus，业务为定时任务），同批条目的位置列正常显示 `[MerchantWalletRechargeServiceImpl.java:32]`、`[MerchantWalletRechargeServiceImpl.java:43]`、`[BizTaskRecordServiceImpl.java:40]`，但复制出的栈快照里**一个项目类都没有**：

```
cn.song.mybatisplus.sqlvis.agent.hook.WovenEvents#emit:70
<-cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks#onExecuteExit:161
<-org.postgresql.jdbc.PgPreparedStatement#execute:182
<-com.zaxxer.hikari.pool.ProxyPreparedStatement#execute:44
…（驱动/池/反射/MyBatis 插件链）
<-<…中间省略 50 帧…>
<-jdk.internal.reflect.DelegatingMethodAccessorImpl#invoke:43
<-org.springframework.scheduling.support.ScheduledMethodRunnable#runInternal:130
<-io.micrometer.observation.Observation#observe:498
<-org.springframework.scheduling.config.Task$OutcomeTrackingRunnable#run:87
<-org.springframework.scheduling.support.DelegatingErrorHandlingRunnable#run:54
```

复现方式（隔离目录，`javac -sourcepath` 直接编译 agent 的 `StackDigest`/`StackLocator` 两个生产类，不碰仓内源码，不起 Gradle）：

1. 按报障串原样造 69 帧：头两帧 agent 自身 + 驱动/池/反射/MyBatis 14 帧 + 中段 44 帧（MyBatis 执行器链、MP `ServiceImpl`、事务模板）+ 项目帧 3 帧（`MerchantWalletRechargeServiceImpl#countExpiring:32`、`MerchantWalletRechargeTask#run:45`、`BizTaskRecordServiceImpl#record:40`）+ 尾部 8 帧 Spring 调度壳；
2. 同一份栈分别喂 `StackLocator.locate(stack, loader)` 与 `StackDigest.build(stack, 24)`。

实测读数：

```
STACK_LEN=69
LOCATE=cn/song/wallet/service/impl/MerchantWalletRechargeServiceImpl.java:32 via=SAFETY key=MerchantWalletRechargeServiceImpl#countExpiring
DIGEST 与报障串同形：头两帧 agent 自身 → 驱动/池/反射/MyBatis → <…中间省略 44 帧…> → 尾部 8 帧 Spring 调度壳
HAS_PROJECT_FRAME=false
```

结论：**位置采到了，摘要又把它丢了**。既不是「项目类不在当前线程栈」（三个项目帧都在同一快照里），也不是「agent 没产事件」或「IDE 消费链丢事件」。用户说的「有时候」也有明确边界——Web 同步请求正常，定时任务/异步池/MQ 监听这类非 Web 入口不正常（原因见 R1、R2）。

## 二、根因

| 编号 | 机制 | 证据位置 | 影响 |
|---|---|---|---|
| R1 | 栈底锚点只认 `StackDigest` 自己的基础设施白名单，不认通用框架前缀 | `StackDigest.java:71-74`（`while (isInfraFrame)`）、`StackDigest.java:170-180`（名单只有容器/JDK/Servlet 管道 + spring web/boot） | `org.springframework.scheduling.*`、`io.micrometer.*` 不在名单，循环在 `DelegatingErrorHandlingRunnable#run:54` 就停住，锚点=栈底 |
| R2 | 锚点语义是「最外层非 infra 帧」，不是「最外层业务帧」 | `StackDigest.java:71-78`；业务补偿只有 `findBusinessController`（`StackDigest.java:81-84`、`113-131`），要求类名以 `Controller` 结尾且有同根调用帧 | 服务层/任务类入口没有等价补偿；未知第三方包装帧（自研 pipeline、非白名单 SDK）同样能抢走锚点 |
| R3 | 摘要与定位是两条独立判定，没有任何一致性约束 | 定位走 `StackLocator.locate` 的 L1 来源过滤（`StackLocator.java:279-303`、`353-379`）；摘要在 `WovenEvents.java:84`、`StatementHandler.java:150` 另行调 `StackDigest.build(stack)`，完全不看定位结果 | 同一次执行可以「头部有项目类、栈里没有」，本次报障就是这个形态 |
| R4 | 弹层折叠白名单不含驱动族与 `org.apache.ibatis.logging./binding.` | `StackDigestFrames.kt:125-144` | 项目帧缺席时，「可见」行反而是 `PgPreparedStatement#execute`、`PreparedStatementLogger#invoke` 这类驱动帧，观感上更像「看不到项目类」 |
| R5 | meta 行内联摘要固定取摘要串前 120 字符 | `SqlPreviewPanel.kt:1534`（`STACK_DIGEST_MAX_CHARS=120`）、`SqlPreviewPanelText.kt:126-135` | 摘要头恒为 agent/驱动帧，长栈下内联那行永远不含项目帧 |

R1、R2 是根因，R3 是「头部与栈不一致」的直接成因，R4、R5 是同一现象的观感放大。

为什么「有时候」：Web 同步请求的栈尾是 Tomcat/Servlet 管道（R1 名单已收）且 Controller 补偿生效（R2 的缺口被 `findBusinessController` 兜住）；定时任务/异步/MQ 入口两条都不成立，于是窗口退化为「顶部 16 帧引擎 + 底部 8 帧调度壳」，业务帧全部落进省略段，且省略段是**采集期丢弃**的，弹层再怎么展开也补不回来（`panel.stackPopup.omitted` 文案已如实说明「不可展开」）。

## 三、修复方案

### S1 尾部锚点分层（P0，改 `StackDigest`）

把 `lastBusiness` 的三层判定按「证据强度」重排，判据全部复用 `StackLocator` 既有实现，不新造第二套名单（否则又是一处会漂移的白名单）：

1. 层一（业务锚点）：从栈底往上找首个「业务来源帧」——非框架前缀（`StackLocator.isFrameworkFrame`）+ 带真实源文件（排除 `<generated>`）+（命中登记业务 JAR 前缀 或 `loadedFromJar` 为 false）。
2. 层二（框架穿透兜底）：层一无命中时，把穿透条件由 `isInfraFrame` 扩为 `isInfraFrame || StackLocator.isFrameworkFrame`，直接覆盖调度/异步/MQ/Netty 等尾部；这是修好本次报障的最小改动（实测见下）。
3. 层三（现状兜底）：仍无 → 字面末帧；`findBusinessController` 补偿**只在层一未命中时**应用——层一命中说明已有更强的业务证据，再改回 Controller 会把更外层的业务帧挤出窗口。

`StackDigest.build` 增加 loader 入参，与 `locate` 用同一个 hint（`WovenEvents.java:63-71`、`StatementHandler.java:127-137` 已有）。理由：`StackDigest.isApplicationCandidate`（`StackDigest.java:133-139`）当前取上下文 loader，agent 跑在宿主线程上时上下文 loader 未必看得见应用类，来源判定会恒为 null 导致层一失效。

实测验证（同一夹具，只改这一处）：

- 只做层二（穿透条件加 `isFrameworkFrame`）：`HAS_PROJECT_FRAME=true`，窗口变成「顶部 16 帧 + 省略 30 帧 + 5 帧事务模板 + 3 个项目帧」。
- 做完整分层（层一业务锚点命中 `BizTaskRecordServiceImpl`）：结果同上，且 `locate` 同步变成 `via=L1`，头部与栈指向同一批业务帧。

### S2 摘要必含定位帧（P0）

S1 保证「最外层业务帧及其 7 帧邻域」在栈内，但项目帧可能分散在很长的跨度上（异步/线程池交接、自研汇聚门面），此时**头部显示的项目类仍可能不在窗口里**——这正是本次报障的直读观感，必须有硬约束而不是「多数情况能对上」。

- `StackLocator.Frame` 增 `primaryClassName`/`primaryMethod`（FQCN；现有 `methodKey` 是简名，不能做身份，`StackLocator.java:402-403`）。
- `StackDigest` 在 S1 得到窗口后校验定位帧是否在窗口内；不在则把该帧作为独立一段插在省略标记之前（`pkg.Class#method:line<-<…中间省略 N 帧…><-…`）。IDE 侧解析器已支持多段省略（`StackDigestFrames.kt:97-122`、`StackFramesProjection.build:46-73`），协议字段不变。
- 省略计数须重算（插入段本身要从省略段扣除），并保证段数仍受帧数预算与长度硬顶约束。

### S3 展示面收口（P1）

- `StackDigestFrames.isKnownInternal` 增驱动族（`org.postgresql.`/`com.mysql.`/`org.mariadb.`/`org.h2.`/`org.hsqldb.`/`org.sqlite.`/`com.microsoft.sqlserver.`/`oracle.jdbc.`/`dm.jdbc.`/`com.kingbase8.`/`com.gbasedbt.`）与 `org.apache.ibatis.logging./binding./reflection./type.`。这些是纯依赖，误折叠风险低，且能直接消除「可见行全是驱动帧」的观感。
- meta 行内联摘要改为「首个项目帧及其邻域」取向，而不是固定取栈顶 120 字符（R5）。
- 弹层标题带上 `threadName`（事件里已有），让「调度线程/异步线程」一眼可辨，减少同类误报。

### S4 多段压缩（P2，下一轮候选，本轮不做）

用「保留全部项目帧 + 顶/尾各留少量框架帧 + 逐段省略标记」替代两端采样，项目帧就永不因深度丢失。IDE 侧解析与折叠模型已支持多段，收益明确；代价是 `StackDigest` 全部既有断言要重写、`enhancedCaptureStackMaxFrames` 的设置语义变化（现在是「总帧数」，改造后是「项目帧之外的框架帧预算」）。本轮先不动，列为下一轮候选并单独拍板。

明确不做：不新增设置项（已有 `enhancedCaptureStackMaxFrames`，`PluginSettings.kt:647`）；不改 agent↔IDE 协议字段（`sd` 格式与省略标记语法保持兼容）；不伪造或重排栈序；不承诺跨线程还原（异步切线程后原请求 Controller 本来不在本线程栈，这是事实边界不是缺陷）。

## 四、实施顺序与待验证前提

1. 先落夹具（见 §五），跑出「旧实现必红」的读数，再动生产代码。
2. S1（层一 + 层二 + loader 入参），两处调用点（`WovenEvents`、`StatementHandler`）同步传参。
3. S2（`Frame` 增字段 + 摘要校验插入）。
4. S3（IDE 侧折叠分类、内联摘要取向、弹层标题）。
5. 定向测试 → 单路只读收口跑全量 → 真机（`runIde` 沙盒 + demo 应用里的 `@Scheduled` 任务）。

待验证前提（实施者先证伪再动手）：

- P-1：「目录来源」判据在 IDE 内运行的应用上是否稳定为 false。本次复现用 `StackLocator.jarOriginProbe` 模拟，**没有取真机 loader 读数**；若实际业务类被判成 JAR 来源，层一会整体失效、只剩层二。
- P-2：`loadedFromJar` 在 `@Scheduled` 线程的上下文 loader 下能否解析业务类。不能则 S1 的 loader 入参不是可选项而是必要条件。
- P-3：层二用 `isFrameworkFrame` 是否会误穿透用户自研入口——入口类若落在 `com.alibaba.`/`com.github.`/`org.apache.` 等已收前缀下会被当成框架帧跳过。属既有前缀表的已知取舍（`StackLocator.java:97-110` 有同族记录），不新增名单，只在计划里登记。
- P-4：S2 插入段对旧版 IDE / 历史回放解析器的兼容面。新版 agent 产出、旧 IDE 读取时，多段省略必须仍能跳过（`StackDigestFrames.parseItems` 按段正则匹配，预期兼容，但需造旧格式串验证）。
- P-5：每 SQL 新增的来源判定成本。`ORIGIN_MEMO`（`StackLocator.java:144-151`）是 per-class 记忆化，热身后是 CHM 查询；但栈底扫描是 O(帧数)，须在性能压测里给读数，不能凭推断放过。

## 五、测试计划

agent 侧（`:sql-visualizer-agent:test`，定向类）：

- 新夹具 `StackDigestBizTailTest`，四组形态：
  1. **用户形态**：定时任务链（本文 §一的 69 帧），断言摘要含三个项目帧、不含 `DelegatingErrorHandlingRunnable`；
  2. **Web 形态**：Controller + Tomcat/Servlet 尾巴，断言 Controller 仍在（回归 `StackDigestTwoEndedTest` 既有 20 例）；
  3. **fat jar 形态**：来源全判 JAR，断言层一不命中、层二接管、不抛异常、长度有界；
  4. **多段业务帧形态**：项目帧分居 20 与 60 位，断言 S2 后头部定位帧在栈内。
- 反向验证：临时只回退层一 → 形态 1 必红；只回退层二 → 形态 1 必红；回退 S2 → 形态 4 必红。三次都要贴读数，不接受「合成样本能过」当精度证据。
- `StackDigestTwoEndedTest` 中依赖「字面末帧为锚点」的断言随层二调整（`全基础设施链_兜底退回字面两端` 等），改动须逐条说明是语义变化还是缺陷修复。

IDE 侧（`testFast` + 定向）：

- `StackDigestFramesTest` 增驱动族折叠与多段省略解析用例；`StackDigestSingleCaptureTest` 的「一次捕获两用」守卫不变（头帧仍是 `emitEvent`，S1/S2 不改捕获点）。
- `SqlPreviewPanelStackDigestTest` 增内联摘要取向用例（长栈下内联段应出现项目帧）。

真机：`runIde` 沙盒 + demo 应用的 `@Scheduled` 任务，验证悬停弹层里不展开也能看到项目类；同时复测一次 Web 请求形态与一次异步池形态。

## 六、验收边界

- 定时任务/异步/MQ 入口：弹层不展开即可看到至少一个项目帧，且与 meta 行头部位置一致（S2 保证）。
- Web 同步请求：不劣化，Controller 仍在摘要内。
- fat jar 部署：不承诺识别项目帧（来源不可辨），但不得抛异常、不得因来源判定失败而丢整条摘要。
- 采集省略段仍如实标注「不可展开」，不把没采到的帧伪装成可展开。
- 本次改动只动调用栈采集/展示及其测试；SQL 内容、参数、执行计划、日志排序、历史文件格式不变。

## 七、风险与回退

- 层二复用 `isFrameworkFrame` 会连带改变「哪些帧算框架」的语义，与 `StackLocator` 的定位判定同源——好处是不会漂移，代价是前缀表任何改动都会同时影响定位与摘要（本来就该如此）。回退方式：只改 `StackDigest` 一处判定，不动前缀表。
- S2 的插入段是摘要格式的增量形态，旧解析器按「无 `#` 段跳过」兜底；若真机发现旧版 IDE 展示异常，回退 S2 即可，S1 独立生效。
- 性能：来源判定的热路径成本需 P-5 读数支撑；若超标，改用「复用 `StackLocator` 已建 pool」的形态（`locate` 每 SQL 已经遍历一次全栈，见 `StackLocator.java:226-236`），避免第二次扫描。

## 附：复现与验证命令（不启 Gradle）

```bash
# 1) 准备夹具：一个 main 类，按 §一 的 69 帧顺序构造 StackTraceElement[]
# 2) 直接编译 agent 生产类（源路径指向 sub-projects/sql-visualizer-agent/src/main/java）
javac -encoding UTF-8 -d out -sourcepath "sub-projects/sql-visualizer-agent/src/main/java" Repro.java
java -cp out Repro
# 判据：旧实现 HAS_PROJECT_FRAME=false；修 S1 后 true
```

改动前先用它取一次基线读数并贴进实施记录，避免把「合成样本能过」当成修复证据。

## 八、实施记录（2026-10-29）

状态：S1、S2、S3 三条全部实施；S4 按计划留待下一轮。

### 8.1 改动清单

| 文件 | 改动 |
|---|---|
| `StackLocator.java` | `Frame` 增 `primaryClassName`/`primaryMethod`（S2 身份判定用 FQCN，`methodKey` 是简名不能做身份）；新增公开判据 `isBusinessFrame(StackTraceElement, ClassLoader)`——复用 `isBusinessOrigin`，自身包恒 false（首版试过把跳过表里的自身包字面量提成常量，被 `LocatorSkipPrefixSyncTest` 的源码提取判红，已回退为字面量）；P-5 压测后把该判据从 `isSelectableBusinessFrame(toRelativePath(...))` 改为直接判 `getFileName()`，省掉每帧一次路径拼接（见 §8.8） |
| `StackDigest.java` | 新增 `build(stack, hint, located)` 与 `build(stack, maxFrames, hint, anchorClass, anchorMethod)`；栈底锚点改三层（`resolveBottomAnchor`）；尾部穿透条件 `isTailInfra` = `isInfraFrame \|\| StackLocator.isFrameworkFrame`；S2 定位帧不在窗口内时单列插入并扣减省略数；`findBusinessController`/`isApplicationCandidate` 接收 loader hint |
| `WovenEvents.java:84` | `StackDigest.build(stack, hint, frame)` |
| `StatementHandler.java:150` | 同上（代理层通道同源受益） |
| `StackDigestFrames.kt` | `isKnownInternal` 补驱动族 / `org.apache.ibatis.logging./binding./reflection./type.` 等；新增 `parseSegment` 与 `projectExcerpt`（内联摘要取景） |
| `SqlPreviewPanelText.kt` | 内联摘要先取景再按原 120 字符契约截断 |
| `AgentEventAdapter.kt` | `threadName = event.threadName` 透传（S3 第三条） |
| `LogSqlModels.kt` | `LogSqlEntry` 增 `threadName`（默认 null，既有构造点零影响） |
| `SqlLogParamCodec.kt` / `SqlLogFileReader.kt` | 历史载荷新增 `tn` 键的写/读 + `DecodedPayload.threadName`（旧 payload 无键 → null） |
| `SqlLogAutoSaver.kt` | `pd` 行落盘条件补 `threadName != null`（否则「只有线程名」的条目读回即丢） |
| `PreviewResult.kt` / `LogEntryPreviewRecognizer.kt` | 预览模型与四个构造分支透传 `threadName` |
| `StackFrameHover.kt` / `SqlPreviewPanel.kt` / `SqlPreviewPanelResultView.kt` | `updateDigest(digest, threadName)`；新增顶层纯函数 `buildStackPopupTitle`；面板测试访问器 `stackThreadNameForTest()` |
| 双语文案 | `panel.stackPopup.titleWithThread=调用栈 · {0}` / `Call stack · {0}` |
| 新增 `StackDigestBizTailTest.java` | 9 例：用户形态 / 无 hint 层二 / Web 不劣化 / fat jar 层二 / 多段业务帧 S2 / Frame 出口一致 / 身份不在场 / 空栈 |
| `StackDigestFramesTest.kt` / `SqlPreviewPanelStackDigestTest.kt` | 各补 4 例 / 1 例：驱动族折叠、取景三态、内联摘要跳项目帧 |

### 8.2 反向验证（三次，均先临时移除再复跑）

| 移除项 | 读数 | 说明 |
|---|---|---|
| 层一（业务锚点） | `StackDigestBizTailTest` 9 例 **1 红**：`web链_Controller仍在摘要内_过滤器与未知尾帧不进快照` | 该形态的尾帧是未知第三方 `com.vendor.runtime.CustomPipeline`，只有「业务来源」判据能跳过它；层二（框架前缀）看不见 |
| 层二（框架前缀穿透） | 9 例 **2 红**：`fatJar形态_层一不命中时层二接管`、`定时任务链_无loader提示时也应由框架前缀穿透接管` | 印证两层各覆盖一种来源形态，互不替代 |
| S2（定位帧插入） | 9 例 **2 红**：`项目帧分散_定位帧在窗口外时单列插入且省略数扣减`、`定位帧经Frame出口传入_与全参出口一致` | 插入段与省略数扣减确实被断言钉住 |

说明：用户形态（定时任务链）在**只回退层一**时仍绿——因为层二单独也能修好该形态（这也是 §三 S1 里「最小改动」那段的依据）。故层一的判别样本只能是「未知第三方尾帧」那类，不能拿用户形态冒充。

### 8.3 测试读数

- `:sql-visualizer-agent:test` 全模块：**190 tests / 0 failures / 0 errors / 1 skipped**（35 个 XML）。
- IDE 侧定向：`StackDigestFramesTest` **22/0/0**、`SqlPreviewPanelStackDigestTest` **18/0/0**、`SqlLogHistoryRoundTripTest` **13/0/0**、`AgentEventAdapterTest` **13/0/0**、`BundleParityGuardTest` 5/0 + `BundlePlaceholderArityTest` 4/0。
- 全量门禁：`testShards --no-configuration-cache` **8374 tests / 0 failures / 0 errors / 9 skipped**，parity「计划类全集 778 个均有结果 XML」，墙钟 17m19s（Docker daemon 离线，DB 链测试按跳过理解）。首轮曾因把 `cn.song.mybatisplus.sqlvis.` 字面量换成常量而红 2 例（`LocatorSkipPrefixSyncTest` 按源码扫描数组提取前缀，常量提取不到），已改回字面量并复跑通过——这条守卫的提取面就是「数组里必须是字面量」，改法已写进 `StackLocator.isBusinessFrame` 注释。

### 8.4 S3 第三条（弹层标题带线程名）补实施记录

首轮曾按「跨四层补字段、属观感增强」延后，复核后补齐。落点与实测：

1. `AgentEventAdapter` 透传 `event.threadName` → `LogSqlEntry.threadName`（默认 null，既有构造点零影响）。
2. 历史文件同步：`SqlLogParamCodec` 增 `tn` 键（写/读都做了），`DecodedPayload` 与 `SqlLogFileReader` 两处映射补齐——只透传不落盘的话，历史条目重开即丢，标题退回纯「调用栈」。
3. `SqlLogAutoSaver` 的 `pd` 行落盘条件补 `threadName != null`：否则一条「只有线程名、无栈无参」的条目不会写扩展行，读回必然丢（与 MCP 来源标记同一类坑）。
4. `PreviewResult` + `LogEntryPreviewRecognizer` 四个构造分支全部透传；`SqlPreviewPanelResultView` 喂 `stackHover.updateDigest(result.stackDigest, result.threadName)`。
5. 标题文案抽成顶层纯函数 `buildStackPopupTitle(threadName)`（弹层本体 headless 弹不出来，纯函数才能单测）：空白/缺省退回 `panel.stackPopup.title`，有值走 `panel.stackPopup.titleWithThread`（中英双语同批加键，`BundleParityGuardTest`/`BundlePlaceholderArityTest` 均绿）。

测试：`SqlPreviewPanelStackDigestTest` 14→**18**（标题两态 + 面板接线两态）、`SqlLogHistoryRoundTripTest` 12→**13**（栈快照与线程名写读回环）、`AgentEventAdapterTest` 13/0、两个 bundle 守卫 9/0。

### 8.5 P-1/P-2 真机读数（真实 JVM + 目录来源业务类 + JAR 依赖 + 线程池受限 TCCL）

装置：JDK 17（`ms-17.0.19`）+ MyBatis 3.5.19 + H2 2.2.224，探针类 `probe.biz.AgentStackProbe` 编译到目录（模拟 IDE 内运行的应用），SQL 由 `probe-scheduled-1` 线程执行，该线程 TCCL 换成只放行 JDK 类的受限 loader；agent 以 `-javaagent:...=port=47612;logLevel=info` 挂载，IDE 侧用最小接收端（协议同 `fake_receiver.py`）落盘 EVENT。

探针自述读数：

```
PROBE codeSource(self)=file:/C:/Users/USER/AppData/Local/Temp/stackprobe/out/
PROBE codeSource(h2)  =file:/C:/Users/USER/.m2/.../h2-2.2.224.jar
PROBE origin(probe.biz.AgentStackProbe, app)=false
PROBE origin(org.h2.Driver, app)=true
PROBE origin(biz, tccl=受限loader)=null
PROBE origin(biz, hint=app)=false
PROBE isBusinessFrame(self, tccl)=false
PROBE isBusinessFrame(self, hint=app)=true
```

生产路径（agent 自身 `logLevel=info` 诊断行，真实 loader）：

```
[T-SourceJumpFunnel] primary=probe/biz/AgentStackProbe.java:42 via=L1 entry=probe.biz.AgentStackProbe#main pool=1 | #0 probe.biz.AgentStackProbe#main:42 jar=false
[T-SourceJumpFunnel] primary=probe/biz/AgentStackProbe.java:96 via=L1 entry=probe.biz.AgentStackProbe#lambda$main$1 pool=1 | #0 probe.biz.AgentStackProbe#lambda$main$1:96 jar=false
```

端到端（池线程 + 受限 TCCL，EVENT 的 `sf`/`sl`/`sd` 三者一致）：

```
EVENT#3 sf=probe/biz/AgentStackProbe.java sl=96 mk=AgentStackProbe#lambda$main$1 th=probe-scheduled-1
  sd=…WovenEvents#emit:70<-SqlHookCallbacks#onExecuteExit:161<-org.h2.jdbc.JdbcPreparedStatement#execute:280
     <-…MyBatis 执行器/会话/绑定链…<-jdk.proxy2.$Proxy5#count<-probe.biz.AgentStackProbe#lambda$main$1:96
```

结论（P-1 成立、P-2 得到反证）：

1. 目录来源判据在真实 JVM 上稳定为 `false`（codeSource 是目录），JAR 依赖稳定为 `true`——判据本身可靠。
2. 线程池线程 TCCL 看不见应用类时，来源判据会退化成 `null`（P-2 的风险是真实的，不是假想）；显式 hint 能救回，所以 S1 的 loader 入参是必要条件而不是可选优化。
3. agent 生产路径上 `via=L1` + `jar=false`，说明摘要侧现在与定位侧走同一判据、同一 loader。

**证据边界（如实登记）**：这次端到端栈只有 4 帧业务侧深度、尾部是 `java.*` 线程帧（旧实现也能跳过），因此**它证明的是「新路径在真实 JVM 上不劣化且与定位一致」，不能证明「旧实现在这条链上会丢帧」**。旧实现的丢帧判别由单测里按用户粘贴原样构造的 69 帧夹具承担（§8.2）。要拿真机 Spring 栈复现「调度壳占满栈底」，需要带 `org.springframework.scheduling.*` 尾巴且业务帧与尾巴之间隔 ≥8 帧的应用——该复现已于 §8.9 用真实 Spring `@Scheduled` 应用补齐（HEAD 版 jar 在该应用上一个项目帧都不剩，本版剩五个）。

### 8.8 P-5 性能读数（三层锚点的每 SQL 开销）

装置：隔离 JVM（JDK 17），同一份栈、同一进程内对拍「HEAD 版 StackDigest」与「本版 StackDigest」。
旧实现从 `git show HEAD:` 取出、只改包名后与新实现同编译单元并存；`StackLocator.locate` 逐字节未动，
只给参考读数。栈用真实类名构造（agent / MyBatis / H2 / Spring / JDK 都能被 `Class.forName` 解析），
业务帧用编译在目录里的 `bench.biz.BizN`，因此 `ORIGIN_MEMO` 的记忆化语义与产线一致。
3 轮各 warm=5 万 / iters=20 万，取最小值（下表为三次独立进程的稳定区间）。

| 栈形态 | 帧数 | locate（参考） | digest 旧 | digest 新 | 增量 |
|---|---|---|---|---|---|
| scheduled（用户报障形态，业务帧 8 + 调度尾） | 35 | 3.8~4.2 µs | 2.9~3.3 µs | 3.1~3.3 µs | **+8~+238 ns**（约 0%） |
| web（业务 1 + MVC 20 + 未知第三方尾 5） | 41 | 5.5~5.7 µs | 3.0~3.2 µs | 4.0~4.1 µs | **+883~+1040 ns（约 +30%）** |
| shallow（12 帧，预算内不采样） | 13 | 1.6~1.7 µs | 0.94 µs | 0.94 µs | **−28~+9 ns（约 0%）** |

结论：新增开销与「栈底方向需要逐帧判来源的帧数」成正比，最坏形态（尾部 25 帧框架/未知帧）约 **1 µs/SQL**，
相对一次 SQL 执行（本机 demo 量级 0.1~10 ms）可忽略；`locate` 侧零变化。

**压测中发现并已修的一处**：首版 `isBusinessFrame` 走 `isSelectableBusinessFrame(toRelativePath(...))`，
每帧拼一次 `pkg/File.java` 字符串——首轮读数是 scheduled **+928 ns**、web **+2630 ns**、shallow +196 ns。
改为直接判 `getFileName()`（`<generated>` 是 SourceFile 裸值，不需要先拼相对路径）后降到上表，
`web` 形态约省 1.6 µs/SQL。冷缓存首调（含 `Class.forName` 解析）约 5.9 µs/op，属每类一次的一次性成本。

### 8.9 真实 @Scheduled 应用的新旧对拍

装置：Spring 6.2.17 + MyBatis 3.5.19 + H2 + micrometer-observation，`ThreadPoolTaskScheduler`
（线程 `sched-1`）→ `@Scheduled` 方法 → 业务 Service（两层 `TransactionTemplate`，把中段撑到真实量级）
→ MyBatis → H2。业务类全部编译在目录里。两个 agent jar 同源构建：`/tmp/mps-sql-agent-old.jar`
（HEAD 版四个文件）与 `mps-sql-agent-new.jar`（本版），同一份应用、同一事件位置。

实测调度栈（应用侧一次性 dump，`tick` 之下 10 帧全是框架）：

```
SchedTask#tick ← NativeMethodAccessorImpl#invoke0 ← NativeMethodAccessorImpl#invoke
← DelegatingMethodAccessorImpl#invoke ← Method#invoke ← ScheduledMethodRunnable#runInternal
← ScheduledMethodRunnable#lambda$run$2 ← io.micrometer.observation.Observation#observe
← ScheduledMethodRunnable#run ← Task$OutcomeTrackingRunnable#run ← DelegatingErrorHandlingRunnable#run
← Executors$RunnableAdapter#call ← FutureTask#runAndReset ← … ← Thread#run
```

同一事件（`sf=sched/biz/SchedApp.java`、`sl=112`、`th=sched-1`）的 `sd`：

| jar | `sd` 栈底窗口 |
|---|---|
| HEAD 版 | `<…中间省略 11 帧…><-…DelegatingMethodAccessorImpl#invoke:43<-Method#invoke:569<-ScheduledMethodRunnable#runInternal:130<-lambda$run$2:124<-Observation#observe:498<-ScheduledMethodRunnable#run:124<-Task$OutcomeTrackingRunnable#run:87<-DelegatingErrorHandlingRunnable#run:54` —— **一个项目帧都没有** |
| 本版 | `<…中间省略 1 帧…><-jdk.proxy2.$Proxy17#count<-TaskService#lambda$query$1:112<-TransactionTemplate#execute:140<-TaskService#query:110<-TaskService#lambda$count$0:105<-TransactionTemplate#execute:140<-TaskService#count:105<-SchedTask#tick:137` —— 五个项目帧，锚点落在 `@Scheduled` 方法本身 |

两版 `logLevel=info` 的定位诊断逐字相同（`pool=5`，五帧全 `jar=false`，`via=L1`），
证明差异只来自摘要侧、定位侧零变化——这正是本次要修的那条不一致。

**证据边界**：这次是真实 Spring 调度栈（含 `Observation#observe`，与用户粘贴的尾部同形），
但它是我为复现搭的最小应用；用户的原始应用栈（74 帧、MP `ServiceImpl` 与 CGLIB 代理层）没有取到，
两者共享的是「调度壳 8+ 帧占满栈底窗口」这一机制，而不是逐帧同构。

**复现配方（复跑用）**：两个装置都是临时目录里的独立 Java 程序，不进仓、不参与构建。
压测＝把 `git show HEAD:` 的 `StackDigest.java` 改包名为 `bench.old`、与新实现同 `javac` 编译，
两个 jar/类路径共用一份栈构造；真机对拍＝同源构建两个 agent jar（HEAD 版四个文件 vs 本版），
同一份 Spring 应用分别挂 `-javaagent` + 最小 TCP 接收端（协议同 `dev-support/agent-capture-repro/fake_receiver.py`），
比 `EVENT` 帧里的 `sd`。两个装置都要求业务类编译在目录里（来源判据走目录分支）。

### 8.10 全量门禁

`./gradlew testShards --no-configuration-cache`：**BUILD SUCCESSFUL**，17m19s（P-5 优化后复跑；优化前同读数 18m38s），
`SHARD TOTAL: tests=8374 failures=0 errors=0 skipped=9`，`testShards parity: 计划类全集 778 个均有结果 XML`。

上一轮（S3 第三条落码前）曾出现 `testShards WARN: 临界片失衡 +26%`——shard3 249.7s 高于片均值，
提示新类零权重插值或权重画像过期；本轮复跑该告警不再出现（权重画像已收敛）。如后续再出现，可在
clean 轮后跑 `dev-support/weight-refresh.ps1` 刷新画像，不阻塞收口。

### 8.7 遗留与后续

1. **S4 多段压缩**按计划留待下一轮拍板（用「保留全部项目帧 + 顶/尾少量框架帧 + 逐段省略」替代两端采样）。
2. **P-5 若后续报出热点**：本轮最坏形态约 +1 µs/SQL，已可接受；若某些工程栈更深（栈尾 40+ 帧未知包装），落点是「复用 `StackLocator` 已建 pool」把两次全栈扫描合成一次，而不是再加缓存层。
3. 计划内的验证项（P-1/P-2 真机 loader、P-5 成本、真机 `@Scheduled` 复现）已全部补齐，读数见 §8.5、§8.8、§8.9。
