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
