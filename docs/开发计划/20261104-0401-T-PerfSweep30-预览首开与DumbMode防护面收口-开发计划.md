# T-PerfSweep30：预览首开与 DumbMode 防护面收口——开发计划

> 立项：2026-11-04。依据《[20261104-0401-IDEA性能影响-性能分析](../性能分析/20261104-0401-IDEA性能影响-性能分析.md)》（下称「本期报告」）§九建议排序，承 2026-11-02 锁反转修复（`c1b9ed71`，3.2–4.1s→1619ms）后的五项遗留账本。基线 dev `1bf55efa`。实施落在 dev（在役日更形态），沿用 t-perfsweepNN 分支惯例；派活时计划 §实施记录回写**实施者工作树里**的副本，主仓副本只读。

## 一、目标与非目标

**目标**：①预览首开五项账本销账——四项坐实项全部收口，未坐实项按打点口径收口；②DumbMode 防护面 5 处闭环＋首个 dumb 夹具测试钉；③新常驻（状态栏 widget）事件化或明判登记不动（先测后判）；④P3 红线面三件（EDT 导出写盘／逐键全文比较／二次 resolve）；⑤基线补录与卫生。

**非目标**：insight／k2j 在途分支增量走查（合流后另立轮次）；构建门禁提效（归 T-TestGapSpeed）；GUI 走查（用户自做，交付编号步骤＋预期结果＋判读口径）；`update/update.json`（用户唯一动作）。

## 二、券拆解

### 券01 预览首开链收口（P1）

- **01a cache-only 预热**：预热链（SqlPreviewService.kt:1836-1850 → DataSourceSelector.kt:1755-1826 → DataSourceSelectorResults.kt:1363-1423 projectExecFile）加 cache-only 形态——VFS 已有条目且内容一致时跳过写盘，只做 refresh＋方言映射＋findFile；内容不一致才落写。两条不变量必须保留：首建（VFS 无条目）裸写仍在锁内、VFS 已有条目绝不裸写（T-ExecDocSync3，防 File Cache Conflict）。
- **01b 打点口径**：request→console-visible 只在首次嵌入打点（embedConsole 同实例早退之前判；SqlPreviewPanelExecution.kt:1306-1313）；失败请求不复用上一次 lastPreviewRequestMs（消除 10151ms 类失真读数）。
- **01c 不变式钉**：新增计数缝测试——「单次预览打开 exec 文件写 ≤1（无方言重渲染路径）/≤2（方言重渲染路径）」「装配入口 ≤2（降级＋换装）且回填 setText 内容判等早退」。先红后绿，贴两次读数。
- **01d 预算调参**：**不随本券直接改常量**。PREWARM_WAIT_MS=500L（SqlPreviewPanel.kt:1484）的新值或自适应窗口挂 T0 重采样读数之后再动；重采样落地前本券只交付 01a~01c。
- **验收**：计数缝断言全绿；既有语义钉不红（PreviewEditorAssemblyKeepsConsoleTest／SqlPreviewPanelPrewarmConsumeTest／SqlPreviewPanelPrewarmLateRecoverTest／ExecDocSyncTest）；真机首开墙钟对照（有 T0 读数则一并贴）。

### 券02 DumbMode 防护面（P2）

- **02a** MapperXmlLocator：dumb 早退＋dumb 态扫描结果不进 5s TTL 单槽（空表缓存是主害，:157-166）。
- **02b** MybatisXmlTargets：resolveClass 的 isDumb 闸前移到全部 findClass 之前（:130-165）；resolveInterfaceMethod 补闸（:177-183）。
- **02c** EntityClassResolver（:59-62/:115-117）与 EntityClassFinder（:237/:254/:293/:352/:359）补 dumb 闸或 DumbService.runReadActionInSmartMode；只加闸不改扫描结构。
- **02d** previewAsync（SqlPreviewService.kt:1809-1826）与 ConvertToSqlAction fallback（:177-197）：IndexNotReadyException 不再吞成无反馈 null——DumbService.runWhenSmart 重试一次，仍失败给可见归因文案（注意与 runners=0 的 NOT_A_SQL_DATASOURCE 归因分流，防新形态重复计数）。
- **02e 测试钉**：dumb 夹具三链（gutter 双跳／日志跳转／预览 compute）不抛、不静默、不空缓存。注意 BasePlatformTestCase 全程持写意图的既有坑与本面无关（dumb 闸测试走 DumbServiceImpl 显式置 dumb 或纯函数缝，不以写意图判据构造）。
- **验收**：反向验证——临时撤掉任一修复闸必须变红，贴两次读数；dumb 夹具用例 ≥3 链。

### 券03 状态栏 widget 事件化（P2 低紧迫，允许登记不动）

- **先测**：2s 池化 tick 唤醒成本（POOLED_THREAD 共享池占用；场景见本期报告 §十③）。
- **收益成立**：SqlLogManager 加条目变化项目级 Topic（先例 DataSourceSelectorLastUsedEvents.kt:27）＋ widget 订阅＋低频兜底（如 15s）；与工具窗内 CaptureStatusBar 2s 轮询一并收口，防只改一处（待验证前提 6）。
- **收益不成立**：登记不动，把实测读数回填本期报告 §4.2，闭环。

### 券04 红线面小收口（P3）

- **04a** ShareDialog 两导出（:190-211/:214-234）移后台：Task.Backgroundable（对照 ScanBannedApiUsagesAction.kt:94-95 既有形态），完成后 EDT BrowserUtil.open。
- **04b** SqlDockedPreview 逐键比较（:119-121/:138-142）去分配：以 charsSequence 与 originalSql 做无分配比较（长度＋逐段），pinned 判定语义不变（SqlDockedPreviewTest 在册必须全绿）。
- **04c** MissingParameter 登记期二次 resolve（NavigationFixes.kt:31-43）：复用首次 resolve 结果或延迟到 applyFix 才解析。

### 券05 基线补录与卫生（P3）

- **05a** DataSourceSettings classCache/methodCache（:141/:144）与 ExecutionTimeParser customPrefixRegexCache（:39）：上限化（如 256，同族 countCache 形态）或在本系列基线登记（键域天然有界），二选一并写明依据。
- **05b** LogToSqlPanel 双注册（:146/:147）合一或带 Disposable。
- **05c** 系列基线清单补录：状态栏 widget（周期资产）、AgentAttachStats、ExternalTemplateRenderer reader 线程、单参 DocumentListener 3 注册口径——落点本期报告 §六，随下期对账生效。

### T0 真机读数（随批，不阻塞）

- 沿期三项（大项目 daemon 墙钟／Prewarm R1/R2 Slow operations 计数／头部行缓存 JFR）＋预热预算重采样（≥10 次冷启 request→prewarm done，出 P50/P95）。重采样读数到位后，01d 才开窗。

## 三、实施顺序与并行车道

- 依赖：券02 独立可先行；券01 内 01a→01c（先有 cache-only 才有写次数判据）；01d 等 T0；券03 先测后判；券04/05 与上并行。
- 车道：≤2 路并行（构建资源定例：瓶颈是内存提交量不是 CPU）；GUI 验收不占车道；收口单派只读代理跑统一全量，主控并行写文档不空等。
- 分支：t-perfsweep30（基于 dev）。子代理只跑定向测试（`--tests "<FQCN>"`，纯逻辑改动用 testFast），不跑全量、不跑构建校验、不 clean、不 --stop；达标即提交。

## 四、风险与回滚

- **01a 触碰 exec 写锁临界区**——锁反转教训（c1b9ed71）重申：跨线程等待不得进临界区；「首建裸写在锁内／已有条目绝不裸写」两不变量由 ExecDocSyncTest 回归钉住。
- **02d 归因分流**：dumb 重试失败的新文案若与方言冲突/runners=0 归因链共用入口，须防重复计数与文案互斥（02e 夹具覆盖）。
- **04b 语义敏感**：pinned 判定换比较方式必须过既有 SqlDockedPreviewTest；若 charsSequence 比较与用户编辑竞态存疑，退化为「先 identity 后 equals」保守形态。
- **券03 若做 Topic**：新 Topic 属新增事件面，注意与 LastUsedChangeEvents 的登记先例对齐（防抖、订阅方挂 Disposable）。

## 五、待验证前提（实施者先证伪再动手）

1. **IndexNotReadyException 是否真从 findClass/PsiShortNamesCache 抛**：243/262 平台行为是推断，dumb 夹具先复现再修——复现不出则 02b/02c 降级为「闸前移」纯卫生项。
2. **3016ms 是单次冷启样例**：预算调整必须基于重采样分布，禁止直接拍常量（01d 已锁）。
3. **showSql 对 exec 文档是否已内容判等**：两路证据口径不一（nativeDoc 分支 setText 无条件 vs saveDocument 仅 isDocumentUnsaved）——实施者先读现行 SqlPreviewPanelSqlView.kt:678-740 定形态再写 01a/01c 判据。
4. **迟到换装是设计内恢复出口**：去重只能去「重复全文重写」，不得动换装语义（PreviewEditorAssemblyKeepsConsoleTest 是语义红线）。
5. **language="" 使 buildVisitor 对全文件被调**是平台行为推断（本期报告 §4.3）：券03 若做，先实测 highlight pass 频率与成本。
6. **widget 与 CaptureStatusBar 的轮询口径**：事件化须两处一并收口（§4.2），只改状态栏一处会留下口径分叉。
7. **insight 合流增量**：227 笔含 43 行 Thread/EDT 形态粗筛命中（本期报告 §八），合流轮必须重跑三路走查增量面——本计划不预设其结论。

## 六、实施记录

（回填写实施者工作树里的副本；每完成一件可提交的立即提交；掉线恢复先 git status＋搜临时探针/RED PROBE 注释）

- 2026-11-04 立项（随本期报告同批提交）。
- 2026-11-04 实施开工。作业拓扑：集成分支 `t-perfsweep30`（工作树 `mpsv-perfsweep30`，基于 dev `5c4af996`）；三车道各自分支/工作树——A `t-perfsweep30-preview`（`mpsv-perf30a`，券01a/1b/1c+02d）、B `t-perfsweep30-dumb`（`mpsv-perf30b`，券02a/2b/2c/2e）、C `t-perfsweep30-ux`（`mpsv-perf30c`，券03+04+05）。文档批（本计划+第七期报告+索引三笔）已 cherry-pick 进集成分支 `79bb909e`（索引冲突按时间倒序裁决）。gradle 并发按 2 路上限分波：A/B 先行、C 待槽。

### 6.1 车道 B：DumbMode 防护面（券02a/02b/02c/02e，4 提交 `cf00a2d7`，已合集成 `1746ca62`）

**前提 1 证伪结果（推翻了计划与分析报告的推断）**：用 `DumbModeTestUtils.computeInDumbModeSynchronously` 造 dumb 夹具实测——

| API | dumb 态实测行为 |
|---|---|
| `JavaPsiFacade.findClass(FQN)` / `findClass(短名)` | **抛 IndexNotReadyException** |
| `PsiShortNamesCache.getClassesByName` | **抛 IndexNotReadyException** |
| `FileTypeIndex.getFiles(XML)` | **不抛，返回真清单** |

计划 P-1 与报告 §五 推断的「FileTypeIndex 直抛」不成立。MapperXmlLocator 的空表缓存主害因此改为两路：①`runCatching.getOrDefault(emptyList())` 把扫描期竞态/平台回落吞成空表；②dumb 态下逐 vf 的 `PsiManager.findFile` 仍走索引。02a 的「dumb 早退 + IndexNotReady 回落不写槽」双保险因此是必要且正确的。

- **02a**（`b5180c73`）：`currentFileListSlot` 扫描前 `DumbService.isDumb` 早退返回一次性空槽（不赋 `fileListSlot`，TTL 窗口不成立）；IndexNotReadyException 回落同样不写槽。先红（日志跳转用例 1 红）→ 后绿 5/5；反向验证撤闸再红（`1 test completed, 1 failed`）；既有 `MapperXmlLocatorTest` 三个 TTL 用例 11/11 保持绿。
- **02b**（`9845b9b0`）：`resolveClass` 的 isDumb 闸前移到 project 空判之后、全部 findClass 之前（原闸在末段 PsiShortNamesCache，保不住前置查询）；`resolveInterfaceMethod` 补同样闸。先红（gutter 双跳抛 IndexNotReady）→ 后绿；`MybatisXmlReferenceTest` 20/20。
- **02c**（`578abb0d`）：`EntityClassResolver.resolveWithAliasCandidates` 入口早退返回空 Resolution；`EntityClassFinder.find` 入口早退返回空列表（覆盖快路径三处 + 反查全量扫描 + 库扫描回取）、`runLibrarySweepNow` 早退返回 -1（触顶语义不落缓存）、`requestLibrarySweep` 早退不投递。只加闸不改扫描结构。先红（预览链抛 IndexNotReady）→ 后绿；`EntityClassResolverHeavyTest` 13/13、`EntityClassFinderIntegrationTest` 21/21。
- **02e**（`cf00a2d7`）：新增 `DumbModeClickChainGuardTest` 5 例（gutter 双跳 / 日志跳转 / 预览链 / 前提 1 探针）。未修复代码 5 case 4 红 → 修复后 5/5 绿；三处撤闸反向验证均红后恢复，合并验证 70 case 0 失败。

遗留：02d 属车道 A 地盘，B 未碰（已由 A 完成）；02b/02c 的反向验证失败摘要被 tail 截断（仅留 BUILD FAILED 退出码），02a 有完整读数。

### 6.2 车道 A：预览首开链（券01a/01b/01c/02d，1 提交 `ead67d72`，已合集成 `2e786892`）

车道 A 原代理掉线后由主控接手，接管时发现并修正了三处**会导致功能形同虚设**的问题：

1. **RED-PROBE 撤闸残留**：`ExecTempFileFactory` 里 cache-only 判等条件被改成 `existing != null && false && ...`（探针未清），cache-only 恒不生效。
2. **01b 判据恒 false**：原实现把 `lastConsoleEmbeddedId == null` 判在 `embedConsole()` **之后**——而 `embedConsole` 内部（:1487）就赋值了，判据恒 false，打点永不输出。改为嵌入前捕获 + 抽纯函数 `shouldLogConsoleVisible`。
3. **判据选错**：首次反向验证「撤 cache-only → 写次数应红」时**没红**，暴露出真实机制——`projectExecFile:1394` 的 T-PreviewFast P1 差量快路径在内容一致时本来就零写，所以「写次数」证明不了 cache-only 的价值。改判据为「是否进入 `projectExecFile`」（新增 `execProjectFileCallCounter`）——cache-only 真正省的是其后两段 `syncExecVfContentIfNeeded` 收敛（各付一次 invokeLater＋最长 2s 有界等待）。

- **01a**：预热链加 cache-only（`ExecTempFileFactory`）：VFS 已有条目且内容一致即跳过写盘，只做 refresh＋方言映射＋findFile＋填槽；两条不变量保持（首建裸写在锁内原子、已有条目绝不裸写）。渲染回填与方言重渲染预热同步切 `cacheOnly = true`。
- **01b**：打点只首嵌（纯函数 `shouldLogConsoleVisible(prevEmbeddedId, requestMs)`）；`previewAsync` 异常分支把 `lastPreviewRequestMs` 清零，消除跨请求失真读数。
- **01c**：新增 `ExecFileWriteMetrics`（独立小文件——`DataSourceSelector.kt` 因加计数缝一度涨到 2021 行破 GiantFileBudget 预算，抽文件后回 1983 行）＋ `ExecFileWriteCountTest` 七例：cache-only 零写 / 跳过 projectExecFile / 无证据保守回落 / 无重渲染 ≤1 写 / 方言重渲染 ≤2 写 / 判等早退不计数 / 回填计数。**反向验证实测**：撤 cache-only 短路 → `testCacheOnlySkipsProjectExecFile` 恰 1 红（7/1），恢复后 7/0/0。
- **02d**：`previewAsync` 把 IndexNotReadyException 从「吞成 null＋WARN」改为单独分流 → `DumbService.runWhenSmart` 重试一次；成功静默交付，仍失败给可见通知（新增 `action.indexNotReady.message` 中英双语），与 runners=0 的方言/`NOT_A_SQL_DATASOURCE` 归因链互斥不混道。`PreviewDumbModeRetryTest` 4 例。

定向读数：ExecFileWriteCountTest 7/0/0、PreviewDumbModeRetryTest 4/0/0、ConsoleVisibleLogGateTest 3/0/0；语义红线回归 ExecDocSyncTest 14/0/0、ExecDocPrewarmDisposeGuardTest 4/0/0、SqlPreviewPanelPrewarmConsumeTest 4/0/0、SqlPreviewPanelPrewarmLateRecoverTest 5/0/0、PreviewEditorAssemblyKeepsConsoleTest 4/0/0；守卫批（BundlePlaceholderArity/ExecTempFileEdt/GiantFileBudget/HardcodedUiText/LastUsedWriteOnEdt/SyncRefreshWriteIntent）23 例全绿。

另修一处测试基建坑：计数缝从成员函数改为扩展函数后，测试 class 未重编导致 `NoSuchMethodError`（增量编译缓存），`touch` 无效，删 `build/classes/kotlin/test` 后重编才恢复。

### 6.3 车道 C：红线面、缓存上限化与基线卫生（券03/04/05，1 提交 `4b525787`，已合集成 `9869d87d`）

- **04a 导出落盘移出 EDT**：ShareDialog 两处 HTML 导出（诊断报告/普通报告）的 `saveHtmlReport` 从按钮回调（EDT）移进 `Task.Backgroundable.run`，`BrowserUtil.open` 留 `onSuccess`（EDT）；形态对照既有 `ScanBannedApiUsagesAction`。只移落盘——HTML 组装与面板状态读取留 EDT（前者纯字符串拼接，后者必须 EDT 读 Swing 状态，一并下移会把组件访问带进后台线程）。新增 `share.dialog.exportTitle` 中英双语文案。新增 `ShareExportOffEdtGuardTest` 锁形态（落盘只在 run 内、打开只在 onSuccess 后）＋合成缺陷源码反向对照（证明守卫看得见它要防的形态）。
- **04b 逐键比较去分配**：`SqlDockedPreview.isPinned` 原用 `document.text`（每次复制整份文本）逐键比较，改 `charsSequence` 视图——长度先比、相同再逐字符比，零 String 分配，语义与 `text != originalSql` 逐字等价。`SqlDockedPreviewTest` 9/0/0。
- **04c 消除 Inspection 登记期重复 resolve**：`parameterRoots` 内部已 resolve 接口方法却只返回参数名集合，导航 fix 又解析一次，且 fix 在「每个 finding」循环内构造（同语句 N 个缺失参数 = N+1 次索引查询）。新增 `parameterRootsWithMethod` 同一趟带出方法；`forMissingParameter` 接受已解析方法；`InjectionAndParamInspections` 改为循环外一次解析、fix 构造一次共享（LocalQuickFix 无状态、只持 SmartPointer，可被多 descriptor 共享）。`MapperSqlInspectionTest` 15/0/0。
- **05a 两处无显式上限缓存上限化**（同族 countCache 形态——超限整体清空）：`DataSourceSettings` 反射 `classCache`/`methodCache`（`REFLECT_CACHE_MAX = 256`）、`ExecutionTimeParser.customPrefixRegexCache`（`CUSTOM_PREFIX_CACHE_MAX = 64`）。「键域天然有界」是观察不是保证；清空代价 = 下次重付 `forName`/`getMethod` 或重编译一条 Regex（微秒级）。**本系列基线「技术性无上限缓存」条目至此清零，不再是「登记接受」**。
- **05b 单参 DocumentListener 收口**：`LogToSqlPanel` 双注册与 `SqlDockedPreview` 单注册改挂宿主 Disposable（两处宿主本就实现 Disposable，dispose 即平台自动摘除）。存量清零。
- **03 状态栏 widget「先测后判」→ 判定登记不动**：`StatusBarWidgetTickCostTest` 实测 2s 池化 tick **4.61µs**（n=2000，预热 200 次后均值；占 CPU ≈0.0002%），远低于 100µs 判据 → 事件化收益不成立，不引入项目级 Topic（避免为 4.61µs 新增一条事件面与订阅生命周期）。用例另钉「快照读取不得创建 SqlLogManager」的只读入口契约：日后 tick 变贵该用例会红，那时再上 Topic。读数已回填报告 §4.2。

定向读数：ShareExportOffEdtGuardTest 2/0/0、StatusBarWidgetTickCostTest 2/0/0、SqlDockedPreviewTest 9/0/0、MapperSqlInspectionTest 15/0/0、BundlePlaceholderArity 4/0/0、HardcodedUiText 3/0/0、GiantFileBudget 2/0/0（合计 37 例 0 失败）。

C 车道首跑撞一处编译错：`SqlDockedPreview.documentDiffersFromOriginal` 里 `originalSql` 是可空字段，逐字符比较漏了 null 分支（`Only safe calls allowed on nullable receiver`），补 `?: return false` 后过。

### 6.4 汇总

三车道三提交全部合入集成分支：B `cf00a2d7`（4 件）→ A `ead67d72`（4 件）→ C `4b525787`（5 件）；合并点 `1746ca62` / `2e786892` / `9869d87d`。券01d（预热预算调参）按计划**未动**——等 T0 重采样读数；T0 真机四项（沿期三项＋预热预算重采样）仍为移交项。

