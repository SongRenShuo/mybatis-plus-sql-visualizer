# T-PerfSweep34：Repository 兜底 EDT 链与让位重算——第十一期余额收口

> 来源：《[20261126-1700-插件IDEA性能影响深度分析-调研报告](../调研报告/20261126-1700-插件IDEA性能影响深度分析-调研报告.md)》§四/§七。
> 第十期三张券已全部兑现（本计划 §五 做销账记录，不实施）；本计划收口第十一期新发现的 4 项，全部为 master 独立可做。
> **券01 优先级最高**：它与已确认真机 SEVERE（L110）同链同型，是那条修复漏掉的第二条分支。

## 一、券清单

### 券01（P2，最高优先）：Repository 声明行兜底的 EDT 索引查询 + 读盘

**现状**：`ConvertToSqlAction` 的预览回调在 EDT（`previewAsync(...) { result -> ... }`，`actions/ConvertToSqlAction.kt:196-214`），回调里用 `&&` 串起九条兜底分支。`c5685aee8`（T-PreviewFallbackEdtSlowOps，L110）把第一条 `tryMapperXmlMethodFallback` 的锚点解析前置到了 compute 块，**但第 8 条 `tryJpaFallback` 的第 5 步没一起修**。

`tryRepositoryDeclarationFallback`（`ConvertToSqlAction.kt:511-524`）**同步调用** `service.previewRepositoryDeclaration(method)`。这个函数是普通同步函数，签名里没有 `previewAsync`（`ui/SqlPreviewService.kt:230`）——与同一个 `tryJpaFallback` 内其他分支（`:477`/`:484`/`:491` 都走 `previewAsync`）不同构，这就是它被漏掉的原因。

调用链与 EDT 上的操作：

```
previewRepositoryDeclaration (EDT)
  └ JpaCrudOperationConverter.convertRepositoryDeclaration
      └ JpaEntityResolver().resolve → EmbeddedColumnNaming.resolveDetailed
          └ HibernateNamingConfigProbe.projectUsesComponentPath (psi/jpa/EmbeddedColumnNaming.kt:67)
              └ scanProjectConfigs (psi/jpa/HibernateNamingConfigProbe.kt:196)
                  ├ FilenameIndex.getAllFilesByExt(project, ext, projectScope)   ← 索引查询
                  └ readText(it)                                                 ← 读盘
```

**为什么必须修**：这与 L110 那两条真机 SEVERE 是同一条回调、同一类操作（索引 + 文件读）。`HibernateNamingConfigProbe.cached`（`:163-171`）按 `PsiModificationTracker.modificationCount` 做戳，**缓存命中时是 O(1)**，但**首击**（或任何 PSI 修改后首次）必付完整一轮索引 + 读盘。平台在真机上会重报同类 SlowOperations。

**触发条件**：光标停在 Repository 接口**声明行**、方法名在 `REPOSITORY_METHODS` 名单内、非 `@Query`，且前七条兜底全未命中。低频但确定可达。

**修法**：照 L110 的形态——

1. 在 `previewAsync` 的 compute 块里预解析：把 `previewRepositoryDeclaration` 的结果（`PreviewResult?`）算出来装进 holder，与 `mapperXmlTags` 同款（`AtomicReference` 或局部 var 捕获）；
2. compute 块内需先判定「该不该走这条兜底」——`method?.containingClass?.isInterface` + `REPOSITORY_METHODS` 名单 + 非 `@Query` 的前置门，把判定与解析一起前置（门不通过就不解析，省掉无谓工作）；
3. **异常分支也要备料**：照 `c5685aee8` 的写法，compute 抛错时兜底链仍会走（既有语义），异常分支同样要把结果准备好，否则「预览计算失败」这条路径上 Repository 档会静默失效；
4. 回调里 `tryRepositoryDeclarationFallback` 改成纯消费预解析结果的形态，不再碰 PSI/索引/读盘。

**顺带审计要求**：同一 `&&` 链上还有 `tryHqlFallback` / `tryNamedQueryFallback` / `tryJdbcTemplateFallback` / `tryIbatisCallFallback` / `tryIbatisMethodFallback`（无 XML 档）/ `tryMethodBodyStringSqlFallback` 等分支。它们多数内部走 `previewAsync`（正确），但**逐个确认一遍**并把结论写进提交信息——本轮只修确有 EDT 重活的那条，不要顺手改动形态正确的分支。

**验收**：
- 行为：光标在 Repository 接口声明行按 `Alt+Shift+Q`，预览行为与修前逐字一致（既有 JPA 声明行预览的行为测试必须全绿）；
- 结构守卫（照 `ConvertToSqlActionTest.testMapperXml兜底锚点解析不在EDT回调链` 的形态）：源码扫描钉「`previewRepositoryDeclaration` 只出现在 compute 块内、回调只消费 holder」；
- 反向验证：把解析移回回调直调，守卫必红，贴两次读数后还原；
- 真机（T0，不阻塞）：Repository 声明行首击，`idea.log` 无新的 `SEVERE - #c.i.u.SlowOperations`（`Plugin to blame` 为我方）。

**定向测试**：`ConvertToSqlActionTest` + JPA 声明行预览相关类 + 新守卫类 + `testFast`；不跑全量、不跑构建校验、不 `clean`、不 `--stop`。

### 券02（优先）：让位开关重算从「全项目全量 restart」收窄到文件级

**现状**：`PluginSettingsPanel.refreshGutterLineMarkers()`（`config/PluginSettingsPanel.kt:1380-1392`）在用户切换「同类插件共存让位」后，对 `ProjectManager.getInstance().openProjects` 里**每个**未 dispose 的项目调 `DaemonCodeAnalyzer.getInstance(proj).restart()`——不带 PSI 参数即全项目全量重分析，而触发它的只是一个 gutter 显示开关。

**修法**（两档，实施时先取 a，实测不够再退 b）：

- **a. 按文件 restart**：遍历该项目已打开的文件，逐个 `restart(psi)`。同仓既有形态可照抄——`ui/SqlPreviewPanelExecution.kt:2460` 即 `DaemonCodeAnalyzer.getInstance(proj).restart(psi)`。取文件集用 `FileEditorManager.getInstance(proj).openFiles`（只覆盖真正打开着的文件，不扫全项目）。
- **b. 退化为仅当前项目**：若 a 实测仍偏重，至少把 `openProjects` 收敛到「当前项目」——用户改设置时只关心眼前的编辑器。

**待验证前提（P-1，先证伪再动手）**：`restart(psi)` 平台契约上仍会重跑该文件的全部 daemon 阶段（不只是 line markers）。若实测单文件 restart 也过重，再看平台有没有「只重绘 gutter」的公开 API——**禁臆造 API 名**，查不到就留在 a 档并在 KDoc 记明取舍。

**验收**：
- 行为：切换让位开关后，已打开的 mapper XML 与方法行图标在秒级内按新值刷新（与现状行为等价）；
- 反向验证：把 `restart(psi)` 改回无参 `restart()`，新增的源码扫描守卫必须变红，贴两次读数后还原；
- 结构守卫：扫描 `refreshGutterLineMarkers` 生产段，钉「不得出现无参 `restart()`」，允许 `restart(` 后带实参的形态。

**定向测试**：该设置页面的既有行为测试类 + 新守卫类 + `testFast`；不跑全量、不跑构建校验、不 `clean`、不 `--stop`。

### 券03：补全上下文预热补每项目额度

**现状**：`ui/gutter/XmlSqlGutterLineMarkerProvider.kt:167-191` 的 `warmCompletionContextOnce` 只有「每文件一次」去重（`warmedContextFiles`，`ConcurrentHashMap.newKeySet`，会话级无清理），**没有任何项目级预算**。同一信号踢出的另一条链 `PreviewAssemblyWarm.warmOnce` 却有 `WARM_FILES_PER_PROJECT = 2` 的额度（`ui/PreviewAssemblyWarm.kt:55`，超限即退并回滚去重标记）。两条同源链两套口径。

冷态历史读数：该链对应 `pre-assembly:completion=13280ms`（`:146` 注释自带，2026-11 沙盒实测）。

**修法**：照 `PreviewAssemblyWarm` 的形态加每项目额度——

1. `GutterWarmScheduler` 或 `XmlSqlGutterLineMarkerProvider.companion` 增 `usedPerProject: MutableMap<String, AtomicInteger>`（键 `project.locationHash`，与装配预热同口径）；
2. `warmCompletionContextOnce` 里在去重判定之后、提交任务之前取额度，超限则 `warmedContextFiles.remove(fileUrl)` 回滚并直接返回；
3. 失败/取消分支同样回滚额度（照装配预热 `onError` 的 `used.decrementAndGet()` 形态），保证「踢没成功不占额度」。

**额度取值（P-2，禁照抄）**：13.3s 是**冷态首次**读数，暖态成本未实测。实施时先在沙盒量一次暖态（同一工程、已 smart、第二次打开新 mapper 的 `ctx warm done in Tms`），再按「额度 × 单次成本可接受」定值。建议起点取 2（与装配预热对齐），实测偏差再调。

**验收**：
- 单测：同一项目连开 N+1 个不同 mapper 文件，断言预热执行次数 ≤ 额度（经 `runCount` 同形态的内部可见缝）；
- 反向：摘掉额度判定，该用例必红；
- 额度回滚：注入失败形态，断言失败不占额度（下一个文件仍能预热）。

**关联提醒**：`warmedContextFiles` 本身是会话级无上限容器（每文件一条 URL，量级=打开过的 XML 数），**本券不改它**——调研报告 §5.4 判定为可忽略，维持既有登记口径。

### 券04（顺手）：4 个 provider 的判定顺序重排

**现状**：13 个 JAVA 语言级 `lineMarkerProvider` 对 Java 文件每个 leaf 回调 `getLineMarkerInfo`。标准形态是「先类型早退、再取 settings」，但有 4 个顺序相反：

| provider | settings 行 | 类型早退行 |
|---|---|---|
| `AnnotationSqlGutterLineMarkerProvider` | 5 | 9 |
| `JpqlGutterLineMarkerProvider` | 5 | 9 |
| `NamedQueryGutterLineMarkerProvider` | 5 | 9 |
| `StringSqlMethodGutterLineMarkerProvider` | 5 | 8 |

`PluginSettings.getInstance()`（`config/PluginSettings.kt:977-980`）= `getService` 查找 + 可空探针 + 外层 `runCatching`；而绝大多数 leaf 过不了类型早退。

**修法**：把 `val gutterState = ...` / `if (gutterState != null && !gutterState.showGutterIcon) return null` / `if (!GutterGroups.enabledFor(...)) return null` 三行整体移到元素类型早退之后。

**语义等价性论证（必须写进提交信息）**：类型早退只判断「这个 leaf 是不是本 provider 的锚点」，与设置值无关；设置判定放在它后面，等价于「非锚点 leaf 不读设置」——两者输出逐值相同（非锚点 leaf 两种顺序都返回 null）。注意 `AnnotationSql`/`Jpql`/`NamedQuery` 三处的类型早退是 `PsiJavaToken` + `tokenType != AT`，`StringSqlMethod` 是另一形态，逐处看。

**注意（P-3）**：项目内有源码扫描类守卫（`PluginXmlStructureGuardTest` 族、各 provider 的接线守卫），重排前先跑这 4 个 provider 的既有定向测试，确认没有守卫依赖「settings 在前」的行序或计数口径。

**补充事实**：`refreshGutterLineMarkers` 当前**零测试覆盖**——`grep` 全量 `src/test` 只在 `MapperNavCoexistUastReproTest.kt` 里命中 `MapperNavYield` 字样（那是让位语义的用例，不是重算链）。券02 的守卫是本链第一条测试装置，属新增覆盖而非回归保护。

**验收**：4 个 provider 的既有定向测试全绿（语义零变更的首要证据）；有条件的补一条「非锚点 leaf 不读设置」的计数用例（若这四个 provider 已有 settings 读取计数缝则直接用，没有就不新造——收益不抵装置成本）。

### 券05（P2 功能缺陷）：缺失片段框「从文件选择」的回填必然丢失

**现状**：`ui/standalone/MissingFragmentDialog.kt:78-97` 的 `pickFromFile`——本类是模态框（`DialogWrapper(project, true)`，`:29`），池线程读完文件后用 `ModalityState.nonModal()` 回填。`nonModal` 的语义是「没有模态对话框时才执行」，所以这个 runnable 会一直排队到对话框关闭、模态退栈才被泵到；届时 `isDisposed` 已为 true，被 `:91` 的守卫丢弃。**回填永远不发生。**

用户感知：在「缺失片段」框里点「从文件选择」、选完文件，文本框没有反应。

**修法**：把 `ModalityState.nonModal()` 改为 `ModalityState.any()`。同仓正确先例：`feedback/CommunityDialog.kt:86` 在完全相同的「池线程取结果 → 回填模态框内组件」场景用的就是 `any()`。回填目标是 `JBTextArea.text`（纯 Swing，不改 Document/PSI），无 write-safe 需求，模态期执行安全。

**不要波及的两处**：`SqlStandalonePreviewPanel.kt:424-432`（工作台工具窗内无模态）与 `SqlPreviewDialog`（MODELESS 弹窗）的 `nonModal` 都是正确的，不动。

**验收**：
- 单测（headless 覆盖不了模态语义）：`extractFragment` 的纯逻辑用例沿用既有；
- 真机（必须）：打开含未解析 `<include refid>` 的预览 → 点「从文件选择」→ 选一个 XML → 文本框出现内容；
- 反向验证：改回 `nonModal()` 应复现「选了没反应」。

## 二、排序与分批

| 批次 | 券 | 理由 |
|---|---|---|
| 第 1 批 | **券01** | 唯一与已确认真机 SEVERE 同链同型的（L110 漏掉的第二条分支），且首击必触发索引查询 |
| 第 2 批 | **券05** | 功能性缺陷（用户点了没反应），改动最小（一个枚举值），可随手带上 |
| 第 3 批 | 券02 | 「成本与意图明显错配」，用户主动操作可感（改开关后要缓一下） |
| 第 4 批 | 券04 | 纯重排、零语义变更，可与券02 同批提交但独立成笔，便于回退 |
| 第 5 批 | 券03 | 需要先取暖态读数（P-2），依赖一次沙盒测量，排最后 |

五券互不触碰同一文件：券01 = `ConvertToSqlAction.kt`（+ 可能 `SqlPreviewService.kt`）；券02 = `PluginSettingsPanel.kt`；券03 = `XmlSqlGutterLineMarkerProvider.kt`（+ 可能 `GutterWarmScheduler.kt`）；券04 = 4 个独立 provider 文件；券05 = `MissingFragmentDialog.kt`。可安全并行。

D256 链方法启发缓存（调研报告 §4.5）列为**观察项不进本计划**：它跑在 daemon 后台线程（项目内 `PreviewReceiverResolver.kt:20` 明确记载 `getLineMarkerInfo` 在后台 daemon 线程，**不是 EDT**——子代理初判「EDT 上 ReferencesSearch」已被此证据推翻），触发面窄（需自研 `*Wrapper` 子类且父链接不到 MP），补缓存属优化而非修缺陷；若后续出现该链的真机耗时读数再立项。

## 三、维持登记、不立项

以下沿用第十期口径，本计划不展开：

- `pasteReparseTimer`（`SqlPreviewDialog.kt:167`）弹窗待命期 400ms 轮询——生命周期受弹窗约束，单拍微秒级；
- EXPLAIN 数据源 bind 轮询（`SqlPreviewPanelExecution.kt:2277`）——250ms × 有界拍数自终止；
- MCP 壳类一次性注册成本——无周期、无每调用开销；
- `warmedContextFiles` / `warmedFiles` 会话级不清理——每文件一条，量级可忽略；
- F47 注入喂入面入口收敛——需平台 API 面，成本收益不成比例。

## 四、真机读数（T0，随批移交，不阻塞）

本期真机硬证据只有 L110 一条（已修）。建议下一轮采样以下几项，用于验证本期推断：

1. **券01 复测（最重要）**：光标停在 Repository 接口声明行按 `Alt+Shift+Q`，确认 `idea.log` 无新的 `SEVERE - #c.i.u.SlowOperations`（`Plugin to blame` 为我方）——修前应能复现（首次必触发索引查询），修后应为零；
2. **券02 前后对照**：改让位开关，量「设置页 Apply 到 gutter 图标刷新完成」的墙钟（当前 = 全项目 restart，改后 = 单文件 restart）；
3. **券03 暖态单次成本**：同一工程已 smart 后，连开 5 个不同 mapper，取 `[open-perf] ctx warm done in Tms` 的 P50/P95——这是 P-2 定额度的依据；
4. **L110 复测**：`Alt+Shift+Q` 落在无 Wrapper 命中的 Mapper 接口方法上（非 Repository），确认同一条无 SEVERE。

## 五、第十期三券销账记录（已兑现，本计划不实施）

| 券 | 修复提交 | master 现状核对 |
|---|---|---|
| 券01 弹窗链 EDT 解析（P2） | `b64e71e43` + `516acc09d` + `f20a79205` + `ed081cbdd` | 解析链收敛唯一后台出口、读盘移出 EDT、补齐片段重解析同步收口 |
| 券02 `windowStamps` 双帽（P3） | `7b318a5d1` | `SqlLogEntryBuffer.kt:898-899` 单键帽 + 键数帽，超限整体 clear |
| 券03 EXPLAIN 前热 cacheOnly（P3） | `ca1a38a3b` | 预热补传 cacheOnly，与预览主链对齐 |

券01 的修复横跨四个提交，本身说明了本项目 EDT 链的复发形态：**同一能力多条入口，改了一处还有下一处**。本期把这条写进了方法论（调研报告 §七.6）：新增 gutter/预览入口须自查是否落在 `previewAsync` 的 EDT 回调里。
