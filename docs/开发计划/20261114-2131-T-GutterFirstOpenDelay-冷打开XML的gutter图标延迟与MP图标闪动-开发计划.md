# T-GutterFirstOpenDelay：冷打开 XML 的 gutter 图标延迟（5s）与 MP 图标消失重现（3s）

- 立项：2026-11-14 21:31
- 任务源：用户反馈（2026.11.14 正式版，大工程实测）——性能面全好评，但冷打开 XML 时预览图标晚到约 5 秒，且图标出现后 MP（MyBatisCodeHelper-Pro）图标消失、约 3 秒后重现；IDE 重启、初始化完成后再打开无此现象，用户判断疑似「刚打开工程」的缘故

## 一、用户反馈原话与范围界定

> 经测试大型项目已不再卡顿，Example 也能正确解析，对应的预览窗口打开也不再卡顿，但是打开 XML 文件后，得有个 5 秒左右才会出现预览图标，预览图标出现后 mp 的图标消失，再过 3 秒左右 mp 的图标再出现，不知道是不是我这个大工程刚打开的缘故。idea 重启一次等 idea 初始化完成后再打开 xml 没有再出现这个问题，貌似可以忽略。

两个现象、三个观察条件：

| # | 现象 | 观察条件 |
|---|---|---|
| S1 | 预览图标在文件打开后约 5s 才出现 | 冷打开（工程刚开、初始化未完成） |
| S2 | 预览图标出现后 MP 图标消失，约 3s 后重现 | 同上；初始化完成后打开无此现象 |

用户已自证：S1/S2 只在「工程刚打开的窗口期」出现，初始化完成后消失 ⇒ 本任务定性为**首开窗口期问题**，优先级 P3（不影响功能，无性能回归），目标是归因 + 收窄我方贡献面，不承诺消灭平台多波次渲染。

## 二、静态归因（代码面事实，已核实；2026-11-14 21:4x 用户纠正后修订）

**图标身份（用户 21:4x 纠正：「MP 图标是我们插件的」）**：

| 图标 | 来源 | pass 类别 | 依赖 |
|---|---|---|---|
| 预览图标 | `XmlSqlGutterLineMarkerProvider`（`getLineMarkerInfo`） | 快趟 | O(1) 语法判定，零 resolve |
| MP 图标（跳转） | `MapperXmlToJavaLineMarkerProvider`（`collectSlowLineMarkers`，`MpvsIcons.MapperStatement`） | 慢趟 | `resolveInterfaceMethod` = `JavaPsiFacade.findClass(namespace, allScope)` + `findMethodsByName` |

- **S1 归因**：预览图标 5s 晚到 = 快趟 pass 在冷打开窗口被 dumb/索引/首开编译压后（平台多波次行为）；provider 本体 O(1) 不是原因。初始化完成后快趟即时落地（与用户自证一致）。
- **S2 归因（我方，可复现）**：MP 图标是 slow provider——冷窗口里 `findClass` 在 Java 索引未就绪/重建中**返回 null 或读被撤销**，`createMarker` 返回 null ⇒ 该趟慢 pass 换装时**把先前已渲染的图标摘掉**；几秒后 Java 索引就绪、下一趟慢 pass 解析成功 ⇒ 图标重现。「消失→3s 重现」与该机制完全同构，且**不需要竞品在场即可复现**（修正此前按 MyBatisCodeHelper-Pro 的归因）。
- **首渲染踢三条后台预热**（`getLineMarkerInfo` 内，全部异步、无写动作）：`MbgExampleCallResolver.prefetch`、`warmCompletionContextOnce`（→ `XmlSqlCompletionContextResolver.resolveXml` → `EntityClassFinder.find` → 冷建注解索引，812 类扫描，BGT 上最重的一块）、`PreviewAssemblyWarm.warmOnce`。它们与慢趟 pass 的 Java 解析抢 CPU/读锁，可能放大 S2 的重现延迟（P-2）。
- **我方文件打开路径没有 restart**：生产代码唯一的 `DaemonCodeAnalyzer.restart` 在预览面板链（`SqlPreviewPanelExecution.kt:2414`）⇒ 摘图标不是我方主动重启高亮，是慢趟 pass 的正常换装语义 + 解析瞬时失败。
- `MyBatisCompetitorGate` 让位门与本现象无关（用户环境 MP 图标在位说明让位未生效或 MPJ 未装；让位是懒缓存 O(1)）。

## 三、复现与取证（2026-11-14 沙盒）

场景脚本 `record/perf_scene7_guttercold.py`（v2，被动口径）：runIde（IU-2024.3.7.1，cust-big-demo）窗口出现后**零交互**——工程自动恢复上次打开的 mapper 标签页，那就是冷打开本身；逐秒截图 45s（冷）；90s 初始化完成后 Ctrl+F4 关 tab 再经 Ctrl+Shift+N 重开同一文件，逐秒截图 15s（暖对照）。（v1 曾尝试启动早期立即 Ctrl+Shift+N 主动开文件：弹层在 IDE 初始化完成前不响应，60s 全部落空——这条本身就佐证了「冷窗口期 UI 反馈延迟」的存在。）

日志锚点（对应关系）：

- `[open-perf] ctx warm done in Tms file=…` 的时间戳 − T = **预览图标首渲染时刻**（warm 在首个 `getLineMarkerInfo` 里踢出）；
- `enter/exit dumb mode` 时间窗 = pass 被压后的平台证据；
- `[open-perf] assembly warm done`、`resolve=` 辅助。

**读数（2026-11-14 21:40~21:51 沙盒实测，cust-big-demo，两段会话）**：

会话 2（21:43，v2 被动口径，`build/MPSV-E2EAccept-shots/scene7_crops/line88_sheet.png`）：

| 时刻 | 事件 | 证据 |
|---|---|---|
| 21:43:08 | exit dumb（本进程首个 dumb 窗 0→6.3s） | DumbServiceImpl |
| 21:43:11~12 | 编辑器可见、**gutter 无图标**（cold_00/01） | 截图 |
| 21:43:12.5 | **首渲染**（`ctx warm done in 2346ms` 反推）→ **预览图标 +2s 落地**（cold_02） | 日志+截图 |
| 21:43:14~15 | **MP 图标（mapperStatement）+3~4s 落地**（cold_04），此后 36s 双图标稳定、**无消失重现** | 截图（cold_04~39） |
| 21:51:06 暖重开 | **<1s 双图标齐**（warm_00） | 截图（warm_sheet） |

会话 1（21:40，v1 交互式）的首开重活清单（首渲染踢出的 BGT 负载，P-2/F2 的量化依据）：

- `反查索引一次建成 tables=1012 耗时=3337ms`（注解索引源）；
- **`跨 ORM 回补完成 tables=8688 耗时=15017ms`**（全项目推断回补——首开最重的一块，15 秒 BGT 扫描）；
- `assembly warm done in 17095ms`（大头在等 smart mode）、`resolve=1736ms`；
- dumb 双窗（21:40:33 exit → 21:40:47 第二窗 → 47.7 exit）：扫描 72287 文件触发二次 dumb。

**S1 判读**：预览图标晚到 = dumb/扫描窗 + 快趟 pass 排程（P-1 成立的沙盒版；用户机 5s 与本沙盒 2s 同构，差异在工程状态）。**S2 判读**：本轮双图标一次落齐、未见消失重现——机制（慢趟 `findClass` 冷窗失败→换装摘图标）仍是唯一自洽解释且属我方，但需「慢趟 pass 落在 Java 解析不可用窗」才会显形（本沙盒 dumb 在首渲染前已退出，未给出演示窗；用户机的大工程首开更长，撞窗概率高）。**暖重开即时**证实 P-3。

**环境限制（修订）**：S2 的 MP 图标是我方 `MapperXmlToJavaLineMarkerProvider` 的图标 ⇒ **沙盒可完整复现 S1+S2**（无需竞品在场）；竞品让位面（`MyBatisCompetitorGate`）与本现象无关。§六的真机取证步骤保留为「若沙盒时间线与用户观察不一致时」的对照手段。

## 四、待验证前提（实施即证伪）

- **P-1（已证实，沙盒）**：S1 的晚到 = 冷打开 dumb/扫描窗 + 快趟 pass 排程（21:43 会话：dumb 6.3s 窗 → 首渲染 21:43:12.5 → 预览图标 +2s）；provider 本体 O(1)、暖重开 <1s。
- **P-2（机制自洽，显形窗未在沙盒捕获）**：S2 的摘图标 = 慢趟 pass 落在 `findClass` 不可用窗（Java 索引未就绪/二次 dumb 窗，21:40 会话实测有二次 dumb 窗 21:40:47）+ 平台整趟换装语义。实施 F1 前先在真机/复跑中抓一次「消失时刻 ∈ dumb/重建窗」的日志对齐；抓不到再回头怀疑别的。
- **P-3（已证实，沙盒+用户）**：初始化完成后无此现象（暖重开 <1s 双图标齐；用户自证）。
- **P-4（量化，首开 BGT 重活清单）**：首渲染踢出的预热在冷窗贡献 3.3s（索引）+15s（跨 ORM 回补）的 BGT 负载——F2 的收益空间。

## 五、候选修法（分诊后定档）

- **F1（主候选，S2）解析失败不摘图标**：`MapperXmlToJavaLineMarkerProvider.createMarker` 现在把「`resolveInterfaceMethod` 是否解析到」当图标开关——冷窗瞬时失败即被整趟换装摘掉。两个候选形态（实施时按 243 平台 API 实测定，禁臆造）：
  - **F1-a 会话级正缓存**：namespace+id 一旦解析成功过（本会话），后续 pass 解析失败时仍照常产出 marker（点击导航本就 null-safe 再解析一次，`MapperNavigationLineMarkerProviders.kt:64` 现状即如此）；「真没有」的语句依旧不出图标（首次判定失败的仍不出，与现状一致）。
  - **F1-b 不可用窗乐观产出**：`DumbService.isDumb` 或解析中遇 `ProcessCanceledException` 时照常产出 marker（dumb 结束后下一趟会重新正确判定）。两形态可叠加；共同不变式＝**图标只在「确认真没有」时缺席，不在「还没查到」时缺席**。
- **F2（S2/S1 缓解，可选）首渲染预热延迟化**：三条预热改「smart 且 daemon 空闲后延迟 N 秒再踢」——首开 BGT 重活实测 3.3s（索引）+15s（跨 ORM 回补），是冷窗竞争的大头；延迟化同时缩小 S2 的撞窗概率。单独小刀，可与 F1 并行。
- **F3（S1）**：快趟压后属平台多波次行为，不修；F2 落地后冷窗 CPU 竞争下降，S1 有望自然缩短，不单独立项。
- **红线**：不碰 `MyBatisCompetitorGate` 让位语义；不在我方路径加任何 `DaemonCodeAnalyzer.restart`；修 F1 时图标导航点击行为不变（resolve 成功语义与现在一致）。

## 六、真机取证步骤（S2 显形窗复核，实施 F1 前可选）

沙盒本轮未给出 S2 显形窗（dumb 在首渲染前已退出）。实施前想先抓实证的话：

1. 用户机复现冷打开：逐秒录屏（对准 gutter 区）；
2. `idea.log` 对齐：MP 图标消失时刻 vs `enter/exit dumb mode` 窗 vs `ctx warm done`；
3. 判据：消失时刻落在 dumb/索引重建窗内 ⇒ P-2 实证，F1 直接收口；
4. A/B：Registry 关 `sqlvis.preview.assemblyWarm` 复测「消失→重现」时长是否缩短（F2 收益验证）。

## 七、验收

- **A1（已完成，见 §三读数）**：冷/暖两组时间线——冷开预览图标 +2s、MP 图标 +3~4s；暖重开 <1s 双图标齐。
- **A2**（若 F1 实施）：慢趟在解析瞬时失败时不再摘掉已渲染图标——沙盒冷窗复跑无「双图标→单图标」回退；「真没有」的语句依旧无图标（负例不回归）。
- **A3**（若 F2 实施）：`ctx warm done` / `assembly warm done` 仍在（时刻后移），首点预览/补全仍命中热缓存；定向测试全绿。
- **A4**：开关可回退——F2 的延迟开关与 `sqlvis.preview.assemblyWarm` 均可关回旧行为。

## 八、实施记录

### F1 实施记录（主分支）

实施：2026-11-14，主分支 master 工作树直改，提交 `d3eb65079`（fix(gutter)），docs 回填另笔。

**改动点**

- `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/MapperNavigationLineMarkerProviders.kt`——`MapperXmlToJavaLineMarkerProvider.createMarker` 判定改四段（①成功入缓存 ②失败+缓存命中仍产出 ③失败+dumb 乐观产出（F1-b）④失败+其余不产出），F1-a 正缓存＝companion `resolvedStatementKeys`（ConcurrentHashMap.newKeySet，键 `namespace#id`，会话级无失效，取舍写进类头注释）；F1-b dumb 判定走 `isDumbProvider` 测试缝（承 MbgExampleCallResolver 定例）；marker 构造抽 `buildStatementMarker` 消除双份；不 catch PCE。`MapperJavaToXmlLineMarkerProvider`（Java→XML 方向）本轮不动——横向审计：其依赖 `MapperXmlLocator.locate`，无「消失重现」实证面，且其 resolve 语义（定位不到就不出图标）与 XML 侧冷窗失败形态不同源，留观察。
- `resolveInterfaceMethod` 本体未动（只读工具别处在用）。
- 测试：`MapperNavigationLineMarkerProvidersTest` 加 F1 判定矩阵 4 例（setUp/tearDown 复位正缓存防跨用例污染）；新增 `MapperXmlSlowResolveFailGuardTest` 接线守卫 2 例（源码扫描钉「判定在 createMarker 解析失败分支上」，含修复前/先缓存后解析/无解析调用三种漂移自证）。

**定向测试读数**（`./gradlew test --tests <两 FQCN> -Pkotlin.compiler.execution.strategy=in-process`，非全量）

- `MapperNavigationLineMarkerProvidersTest`：tests=15 failures=0 errors=0（含 F1 矩阵 4 例：成功入缓存 / 失败+命中仍产出 / 失败+未命中不产出且不污染缓存 / dumb 乐观产出不入缓存）；
- `MapperXmlSlowResolveFailGuardTest`：tests=2 failures=0 errors=0。

**反向验证**（摘掉「失败但缓存命中仍产出」分支复跑，中间态未提交）

- 第一读数（摘分支后）：17 例 **2 红**——`testF1ResolveFailureWithCacheHitStillProducesMarker` NoSuchElementException（`.single()` 空＝图标被摘形态，即缺陷本体复现）＋ `MapperXmlSlowResolveFailGuardTest` 接线守卫 AssertionError（wiring=false）；
- 第二读数（Edit 恢复分支后复跑）：17 例 **0 红**，BUILD SUCCESSFUL。

**实施期订正（计划未预见的夹具事实）**：BasePlatformTestCase 夹具方法体跑在读操作内，`DumbService.waitForSmartMode` 断言「Don't invoke waitForSmartMode from inside read action in dumb mode」——矩阵用例不能依赖夹具真实 dumb 态，已改走 `isDumbProvider` 测试缝（原设想 assertSmartMode 前置删除）。

**无 CHANGELOG**（主控合并期补账，本刀不回填）。未碰 `update/update.json`，未 push。
### F2 实施记录（分支 t-gutter-warm-delay）

- 分支/工作树：`t-gutter-warm-delay`（自 master `a45427770` 切出，独立工作树 `.worktrees/t-gutter-warm-delay`）；**留在分支上未合并**。实施期间跑测试时工作树干净（唯一提交 `7ad1d1a5d` 即全部实施内容，反向验证的红探针在提交后做、改回后 `git diff` 为空）。

**落点与形态**：

- 新增 `ui/gutter/GutterWarmScheduler.kt`（internal object）：`getLineMarkerInfo` 原三处直踢收拢为 `GutterWarmScheduler.onGutterFirstRender(tag)` 一个调度点；判定内核抽纯函数 `shouldKickNow(dumb, msSinceProjectSmart, delayMs)`（dumb ⇒ 不踢；delay≤0 ⇒ 立即踢；到点 ⇒ 立即踢；未到 ⇒ 延迟派发）。延迟派发走 `AppExecutorUtil.getAppScheduledExecutorService().schedule(...)`（243 API 实证：同仓 `MbgExampleCallResolver.kt:554` 在用 + IU-243 sources jar 反查 `getAppScheduledExecutorService(): ScheduledExecutorService`；整数 Registry 读数用 `Registry.intValue(key, default)`，util-8.jar javap 实证）。
- 调度语义（KDoc 写清，见源文件）：延迟锚点＝本会话内最近一次观察到工程 smart 的时刻（per project，渲染遇 dumb 即清锚点重起算——锚点不早于真实 smart 进入时刻，实际延迟 ≥ 配置值，方向保守）；到点复查「工程未关、已 smart、文件仍在」，到点仍 dumb 放弃本轮等下次渲染（与三链既有 dumb 跳出口径一致）。每文件一次的**调度**去重（按 VirtualFile.url，踢出/放弃即撤销）；三链自身每文件去重（`warmedContextFiles`、`PreviewAssemblyWarm.warmedFiles`）不动，不产生重复踢。
- `warmedContextFiles.add` 时机随踢出后移（到点才记）的边角：延迟窗内文件被关闭 ⇒ 到点 `tag.isValid=false` 放弃本轮且三链未记去重 ⇒ 重开时重新调度，等价「没预热过」；预热已落地后关闭重开 ⇒ 三链按 URL 去重早退。
- 三链统一踢出点 `XmlSqlGutterLineMarkerProvider.kickFileWarmers`（companion，顺序与原直踢一致）；`prefetchMbgExample` 随之从实例方法挪进 companion（companion 调不到实例方法，语义不变）。
- Registry：`sqlvis.gutter.warmDelayMs`，默认 5000；**0 = 旧行为立即踢**（回退通道，且不做 dumb 预检——三链自带 dumb 检查保持原语义）。plugin.xml `<registryKey>` 纯英文 description 登记，BundleParityGuard 对 registryKey 族整元素豁免（未引入 bundle 键）。
- 既有用例零改动：`PreviewAssemblyWarmTest` 7 例原样绿（其接线守卫断言 `PreviewAssemblyWarm.warmOnce(` 在 provider 源码内——`kickFileWarmers` 保住了这一点）。

**提交清单**：

- `7ad1d1a5d` feat(gutter): F2 首渲染预热延迟化——三链合并为 GutterWarmScheduler 单调度点（调度器 + 接线 + registry + 单测，一笔过编译过定向；反向验证前先提交，符合 LESSONS L32）

**定向读数**（`.worktrees/t-gutter-warm-delay`，`./gradlew test --tests ... -Pkotlin.compiler.execution.strategy=in-process`，2026-11-14 22:35）：

- `GutterWarmSchedulerTest` 7/7 绿（纯函数矩阵 4 例 + 接线守卫 3 例）；
- `PreviewAssemblyWarmTest` 7/7 绿（零改动）。

**反向验证**（接线守卫红/绿两读数）：

- 红（临时把 `getLineMarkerInfo` 改回直踢绕过调度器）：`GutterWarmSchedulerTest > 首渲染预热必须经调度器 FAILED — java.lang.AssertionError: getLineMarkerInfo 必须经 GutterWarmScheduler.onGutterFirstRender 调度（F2：三链合并为一个调度点）`（GutterWarmSchedulerTest.kt:90）；
- 绿（还原后 `git diff` 为空再跑）：`GutterWarmSchedulerTest` 7/7、`PreviewAssemblyWarmTest` 7/7，BUILD SUCCESSFUL。

**验收对照**：

- A3：`ctx warm done` / `assembly warm done` 日志锚点与链路未动（只是踢出时刻后移/或暖场立即踢）；定向测试全绿。沙盒冷窗复跑（观察 `ctx warm done` 时刻是否后移 ≈5s）属 runIde 腿，本刀未跑——留给主控合并后的验收复跑或 A/B（§六第 4 步）。
- A4：回退通道双开关在场——`sqlvis.gutter.warmDelayMs=0` 回首渲染立即踢；`sqlvis.preview.assemblyWarm=false` 关装配预热（原开关未动）。

**红线遵守**：未触碰 `update/update.json`、`CHANGELOG.md`、`MapperNavigationLineMarkerProviders.kt`；未 push；未跑全量/未 clean/未 --stop。
