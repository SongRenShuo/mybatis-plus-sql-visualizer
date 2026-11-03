# T-TestGapSpeed：踩坑盲区补测与全量门禁提效

- 立项：2026-11-04 00:53，主分支 master（HEAD `1cbbf2b6`）
- 工作树：`F:/code/Java/Gitee/SongRenShuo/mpsv-testgap-speed`，分支 `t-testgap-speed`（扁平名）
- 背景：用户令——插件开发中「单测绿但真机废」的问题反复出现（UI 交互等），要求①分析踩坑记录与记忆，全方面补充一次测试用例；②缩短全量测试运行时间提效。

## 一、侦察结论（先证伪后的余量）

对本仓 24 份最相关踩坑复盘 + 60+ 条记忆逐条对账「是否已有测试」，结论：**大部分历史坑已事后补测**（这是多轮复盘纪律的成果）。已确认有覆盖、本轮不动的：

- 20261103 五层真因（折叠/清场/工具栏文案）→ `PreviewResultIsolationTest` 6 例在场
- editorHolder 单槽位 → `PreviewEditorAssemblyKeepsConsoleTest` / `ConsoleHostTest` 在场
- agent 心跳窗口 → `AgentHeartbeatWindowTest` 在场
- JDBC execute 四态、XML 膨胀诱饵、fatal 透传、SpeedSearch、暂停显隐矩阵、DBN scanBailed、控制台签名覆盖、零产出冲刷等 → 各自在册用例（出处见各 pitfall 档 C 栏）

**真缺口**（本轮要补的）与**真机-only**（自动化永远复现不了，只能制度化）分列如下。

### 真缺口（可自动化）

| # | 缺口 | 证据 | 出处 |
|---|---|---|---|
| G1 | `ReplayExportDialog` 全仓 **0 个测试文件引用** | grep 实测（同包 Dialog 有 3~18 处引用） | `src/main/.../ui/ReplayExportDialog.kt` |
| G2 | `BrowserUtil.browse` 泄漏修复只有点状 `isUnitTestMode` 收敛，**无扫描守卫防复发**（新增降级出口不会被抓） | pitfall-test-sandbox-browser-leak 修复靠三处收敛；convention 守卫族无此规则 | `docs/memory-archive/pitfalls/pitfall-test-sandbox-browser-leak.md` |
| G3 | 权重画像陈旧：入库 TSV 619 类 vs 实际测试类 812 个（2026-10-10 画像），新重类被中位数插值成轻类 → 临界片失衡长尾（该形态已有实证：批次8 PluginSettingsPanelUiTest 4 例 19.5s 被按 ~1.5s 插值致 +13.7% 失衡） | 画像头部注记 `generated=2026-10-10 files=619`；失衡史 jsonl | `dev-support/test-shard-weights.tsv` |
| G4 | 负载敏感性能用例（LogEntryRailPerfTest / LogPipelinePerfTest）混在并发分片，负载波动假红 → 整轮重跑税（32m57s 级） | v2026.11.2 发行说明 flake 处置记录 | `docs/发行说明/20261102-1100-*.md:83` |
| G5 | `testShardsProgress.ps1` 硬编码 5 片；收口轮 SOP 无「自适应限流替代单路」口径（单路 33min vs 限流 5 路 5m02s 读数在案） | 脚本 `$shardCount = 5`；T-ReleasePerf9 读数 | dev-support 脚本 |

### 真机-only（自动化复现不了 → 制度化补进走查清单）

模态冻结默认 modality 的 invokeLater（M36.5.13，48 秒零重试铁证）、`ModalityState.any()` write-unsafe 快照、ToolWindow 标题动作悬停渲染、JOIN 图像素级重叠、设置页内导航聚焦、262 read-access 运行期读数（文档自认只有上报栈）、控制台 `use` 成功语句落 timeout hint、键位冲突通知必然出现、平台「选中即关联」持久化副作用。这些补进 `docs/规范/测试沙盒验证清单.md`，把「靠用户报障发现」变成「走查清单逐项确认」。

### 登记不修（本任务边界，防范围失控）

- ~~`use` 语句 timeout hint~~：**20261104 用户令改实施**（升 T8 券，见下），从本边界移出。
- 键位冲突结构性死路：待用户拍板，不动。
- 回滚 identity T-IIR-02：O-59 在册，不动。
- `releaseGate` stagger 240s 静态错峰改动态让路：列为可选 T7，前六券收口且有余量才做。

**T8-useToolStatementDone（20261104 用户令追加）**
- 缺陷：`use`/DML 等无结果 Tab 的语句**成功**执行后走满 40 拍窗口落 `execute.timeout.hint`（把成功说成密码/驱动超时）——pitfall-console-error-signature-coverage 顺带观察在案。
- 修法：完成证据检测（Output 里的 `affected`/`completed in`/`query ok` 等完成态回显，取既有状态词表的完成语义子集；`executing`/`fetching` 等进度词不算完成，长查询挂死不误报）+ `onInfo` 中性回调穿层（面板 `showStatus` 灰字，不进红条错误通道）。onInfo 缺席的调用方（LogToSqlPanel 等）保持既有行为。
- 待验证前提：①`use` 语句 DBN 是否写完成回显**未知**（无真机形态样本）——若 DBN 对 `use` 只写回显不写摘要，则文本判据无法与挂死区分，该场景维持旧提示，如实标注证据边界；真机验收步骤交用户。②链路 11 处签名穿层（SqlExecutor→selectAndExecute/executePrepared→chooseAndPrepare→prepareInConsole→prepareAfterEditorResolved→executeOnConsole→afterSubmitted→startResultTabRetry + 面板三调用点），全部默认参数向后兼容。

## 二、券清单

### 线一：测试盲区补测

**T1-ReplayExportDialog 补测**
- 新建 `src/test/kotlin/cn/song/mybatisplus/visualizer/ui/ReplayExportDialogTest.kt`，不碰生产代码。
- 用例面：DialogWrapper 结构（标题/按钮行动作与禁用态）、导出内容断言（快照字段→导出文本的映射）、非法输入（空快照/缺字段）不抛异常给兜底文案。
- 范式照抄同包 `RollbackSqlDialogTest`（DialogWrapper headless 构造先例）。

**T2-BrowserUtil 泄漏扫描守卫**
- 在 convention 守卫族新增扫描规则：`src/main` 中 `BrowserUtil.browse` / `BrowserUtil.open` 调用点必须处于 `isUnitTestMode` 收敛分支，或在守卫内显式豁免清单（写明理由）。
- **反向验证必做**：临时摘除收敛分支 → 守卫必须红，贴两次读数。
- 陷阱（前车之鉴）：豁免前缀表字面量必须留在被扫数组里，不得提成常量；守卫期望计数与生产调用点同步。

**T3-真机走查清单补齐**
- `docs/规范/测试沙盒验证清单.md` 增补上节九项真机-only 判据，每项带：触发路径、预期表现、失败时该抓的日志行。措辞照清单现有条目风格，不写 AI 味条目。

### 线二：全量门禁提效

**T4-权重画像刷新与保鲜告警**
- 用最近 clean 轮 XML 刷新 `dev-support/test-shard-weights.tsv`（619 → ~800 类）。
- `mpv-testing.gradle.kts` 权重段加陈旧告警：`discoveredTestClasses` 中既无本地样本又无画像覆盖的类占比 >15% 时 WARN 提示跑 `weight-refresh.ps1`（WARN 不挡构建）。

**T5-perf 用例串行尾道**
- 先实测两类 perf 用例在最近轮的 exec 时间，评估摘出代价。
- 摘出并发分片（`testShardExcludeClasses` 同机制），testShards 聚合新增固定串行尾任务单跑（独立 JVM），执行完再出聚合报告。
- 目的：负载敏感假红不再打翻整轮分片，重试面收敛到单个尾任务。

**T6-脚本参数化与收口 SOP**
- `testShardsProgress.ps1` 片数参数化（读 `-PtestShardCount` 或参数，缺省 5）。
- CONTRIBUTING 或规范补收口轮口径：机器空载收口用 `-PtestShardMaxLane` 自适应限流（5m02s 在案），不再单路 33min；禁降片红线不变。

**T7（可选）-releaseGate 动态让路**：stagger 静态 sleep 改「verifier 结束即放行」，须 A/B 双轮读数才定案；余量不足则登记待办不实施。

## 三、实施波次与并行约束

- W1（三路并行，≤3 上限）：T1、T2、T4 各一路子代理，在 t-testgap-speed 工作树内做，**每完成一件可提交的就立即提交**，计划 §实施记录回填写各自任务书指定段落。
- W2（W1 收口后）：T5（碰 `mpv-testing.gradle.kts` 聚合段，与 T4 同文件故错波）、T6 并行；T3 主控自己写文档。
- 收口：单派只读代理跑定向复核；主控并行更新本册实施记录；全量 testShards 后台跑（带进度显示，`dev-support/testShardsProgress.ps1`），禁阻塞等待。
- 纪律：跑 gradlew 前 `python C:/Users/USER/.zcode/cli/parallel_tasks.py check` 并登记；子代理只跑定向测试（`--tests "<FQCN>"`），不跑全量、不 `clean`、不 `--stop`；构建并发 ≤2 路。

## 四、待验证前提（实施者先证伪再动手）

1. **ReplayExportDialog 可 headless 构造**：若它依赖平台 DataContext/文件系统态超出 DialogWrapper 常规，则降级为「结构守卫 + 纯函数提取」路径，先证伪再写。
2. **weight-refresh.ps1 污染闸**：脚本带 `execSum > 900s 拒写` 闸，20261103 轮 5 片合计 961.8s（并发膨胀口径）——确认闸的统计口径，若被闸拒需用干净单路样本或明确调闸并留痕，不得绕闸静默写。
3. **两类 perf 用例确属负载敏感**：以发行说明 flake 记录 + 最近轮该类时间分布为准；若实测它们在 1 lane 也稳定红，按回归处理不摘道。
4. **buildSrc WARN 段不破坏 configuration-cache**：照既有 providers 范式写，改完跑一次 `testFast` + 一次分片配置期（`./gradlew :tasks` 级别）验证。
5. **T2 守卫扫描口径**：确认 `BrowserUtil` 的调用面（可能不止 browse/open 两名），扫描器与豁免清单同源单遍。
6. master 与 dev 存在分叉（dev 18 笔未合），本任务全部改动落在 master 线；合流时 T1/T2 新文件零冲突，T4/T5 的 `mpv-testing.gradle.kts` 段可能与 dev 在途线撞——合流前重跑预演。

## 五、实施记录回填

（各券完成后在此回填：提交号、定向读数、反例读数、与计划的偏差。）

- T1：**完成（20261104，提交 `b2a85b3e`）**。原派子代理并发限额掉线，主控按「接手掉线代理」纪律接管（工作树仅剩一个在途测试文件、无探针残留）。11 例 headless 用例（DialogWrapper 结构/禁用态诚实降级双保险/导出映射/空输入与缺字段兜底），定向 `test --tests ReplayExportDialogTest` 读数 11/0/0/0。偏差：计划写「不碰 src/main」，实际按仓内惯例给弹窗补 public `doDispose()`（SqlPreviewDialog/RollbackSqlDialog 同款——平台 DialogWrapper.dispose 为 protected，兄弟弹窗全靠该惯例口给测试 tearDown；T4 观察到的「protected okAction/doDispose 约 10 处编译错」即源于测试误摸 protected，已改为经 public wrapCheck/doDispose 访问）。前提1 证实：headless 构造无障碍。
- T2：**完成（20261104，提交 `1fdd35e2`）**。
  ①规则口径：新增 `convention/BrowserUtilConvergenceGuardTest`——src/main 每个 `BrowserUtil.browse(`/`BrowserUtil.open(` 调用点（掩码后计数，注释/字符串片段不计）必须满足其一：最小围合函数体可见收敛标记（`isUnitTestMode` 或 fail-safe 助手 `isTestEnvironment`），或在守卫内 `exemptFiles` 豁免清单逐条登记理由。判定内核按「调用点粒度」，补上既有文件级守卫（BannedApiSourceScanTest 只认 `browse(`、含守卫字样即放行全文件）的两处缺口：PluginSettingsPanel.kt:616 与 ShareDialog.kt:195/:219 的 `BrowserUtil.open(` 此前完全在覆盖外。
  ②前提5证实：调用面=browse/open 两名（grep src/main 全集 5 个调用点），无第三名；扫描器与豁免清单同类同文件（豁免表字面量留在被扫类源码内，未提常量——按陷阱项执行）。
  ③豁免清单现状（2 文件逐条）：PluginSettingsPanel（捕获目录打开缝 `captureDirectoryOpener` 默认 lambda——目录 URI 属文件管理器行为且为 internal 测试缝，UiTest 注入替代不触达平台调用；属性 lambda 无 fun 围合无法就地收敛）；ShareDialog（导出后打开刚保存的本地 HTML 报告——html 文件 URI 确会拉默认浏览器，但仅在用户经 FileSaverDialog 手动保存后触发，ShareDialogTest 0 触达；注释写明未来测试触达导出链前须改走 SafeBrowser，不得沿用豁免）。豁免反腐烂：登记文件无调用点在场即红。
  ④期望计数=5 与生产调用点同步（SqlLogPanel 1 + SafeBrowser 1 + PluginSettingsPanel 1 + ShareDialog 2），防扫面失明空绿；SafeBrowser 收口本体的 fail-safe 形态仍由既有语义锚点用例钉死。
  ⑤反向验证两次读数：探针（SqlLogPanel.kt 尾部临时未收敛调用点）→ 红，断言原文「期望计数漂移……实际 […、SqlLogPanel.kt:2489 BrowserUtil.browse(、…]」（计数 5→6 双证）；探针还原（git restore，src/main 零残留）→ 全桶 testFast 绿（363 类 4331 例 0 失败 0 错误，含元守卫 8 例与 T2 守卫 3 例）。
  ⑥联动改动：元守卫 GUARD_CLASSES 登记本类（SITES/VIOLATIONS 逐条 + Boolean 谓词 headerOpensBody 按 HELPER 登记 via）；BannedApiSourceScanTest 的 src/test 禁直调扫描补本守卫文件自豁免（扫描模式与自证语料含被扫片段，同 BannedApiSourceScanTest 自豁免惯例）。偏差：无（计划三项全落地；自证语料六组合成样本双向判别属超出计划的加严）。
- T3：**完成（20261104，提交 `bfc906b7`）**。清单新增「headless 单测不可验判据」一节：线程与模态 3 项（模态冻结 invokeLater / ModalityState.any write-unsafe / 262 读锁硬抛）、UI 形态 4 项（ToolWindow 悬停渲染 / 接管组 displayTextInToolbar / JOIN 像素 / 设置页导航）、控制台与键位 2 项（`use` 语句超时提示与键位冲突必现，均注明在册缺陷/待拍板现状），每项带触发路径、判据、出处复盘。
- T4：**完成（20261104，提交 86f16ace + 3a5fb604）**。
  ①画像刷新：weight-refresh.ps1 从主仓 20261103 轮 XML（test+testShard1..5，804 文件）重生成 TSV，612→802 类（新增 195、移除 5 个已删类），头部注记 execSum 846.0→961.9s；新增头部含 O147HistoryUpgradeTest 87.07s（此前被中位数插值的最大失准源）。
  ②前提2证伪：闸口径=30 分钟窗内逐类 suite time 求和，本轮 961.9s>900s 实测被拒（报错原文留痕：`pollution gate: latest-round execSum=961.9s > threshold=900.0s over 804 files`）；结果目录仅此一轮、无干净轮可聚合，按脚本自有 `-Force` 显式放行并在 TSV 头部留注记（两路并发轮、相对量级回填语义），未改脚本默认阈值、未静默绕闸。公共 607 类中位比值 0.71（轮次刻度整体偏移，贪心装箱只吃相对量级，另 PluginSettingsPanelUiTest 热轮 1.895s vs 冷轮 ~19.5s 的冷启动偏差属已知局限）。
  ③WARN（3a5fb604）：durationWeights 段未覆盖类占比 >15% 时 logger.warn 提示跑 weight-refresh.ps1，CC 存储/复用均正常。反向验证：摘除 TSV `--no-configuration-cache` help → WARN 触发（803/803=100%）；还原 → 静默；CC 复用态无异常。注：TSV 读取非 CC 输入（weightProfile 段既有语义），摘文件后须 `--no-configuration-cache` 才能触发重配置。
  ④testFast 被 T1 券在途文件 ReplayExportDialogTest.kt 编译错阻塞（protected okAction/doDispose 等约 10 处，全落该文件），与 T4 改动无关；buildSrc 编译与配置期已由 help 两向验证覆盖，testFast 留收口复跑。
- T5：**完成（20261104，提交 `1d623628`）**。前提3 证实后按计划摘道：两类实测 exec 合计 7.9s（17 例 + 14 例，20261103 轮 XML），摘道代价 ≈ 8s + 一次尾 JVM bootstrap，换整轮重试面收敛。落地：buildSrc `serialTailClasses` 摘出分片计划 + `testShardTail` 尾任务（mustRunAfter 全部分片保证分片全停才起、单跑不拖分片）；聚合报告计进 SHARD TOTAL、不参与失衡/缺权告警（shardExec 只收分片本体，片数经局部 val 捕获防 CC 炸）；根脚本 e2eFakeIde mustRunAfter 追加（任务名不匹配既有 `[0-9]+` 正则）；并行运行器路径走 test-shard-serial-classes.txt 同清单。验证：`help` 绿（buildSrc 编译+CC 存储正常）+ `testShardTail` 实跑绿（XML 17/0/0/0 + 14/0/0/0，laneAtEnd=0）。待收口复跑确认：testShards 全量轮分片计数应不含两类（预计 8503 + 尾道 31）。
- T6：**完成（20261104，提交 `507bc705`）**。testShardsProgress.ps1 新增 `-ShardCount`（缺省 5 与 mpv-testing 默认对齐，注释写明调片数两边一致）；PS 解析器校验 PARSE OK（PS5.1 中文脚本雷区自查）。CONTRIBUTING 收口轮口径两条：`-PtestShardMaxLane=5` 自适应限流（5m02s 在案）替代单路 33min、禁降片红线不变；全量后台跑必须带进度显示。
- T7：**未实施**（按计划边界：前六券收口后余量评估不做；releaseGate 动态让路留待办，须 A/B 双轮读数才定案）。
- T8：**完成（20261104，提交 `3c55f11f`，用户令追加券）**。缺陷=use/DML 等无结果 Tab 语句成功后落 `execute.timeout.hint`（错向归因）。修法两层：①`hasConsoleCompletionEvidence` 完成证据检测（Output 完成态回显 `affected`/`completed in`/`query ok`/`fetched`/`took `/`elapsed` + 行数词正则，取既有状态词表**完成语义子集**；`executing`/`fetching`/`connected` 进度词不算——长查询挂死不误报，漏报退化=现状，误报=把挂死说成完成，故宁少勿错）；②`onInfo` 中性回调穿层（面板 `showStatus` 灰字，不进红条错误通道）——SqlExecutor.execute/executePrepared → selectAndExecute/executePrepared → chooseAndPrepare → prepareInConsole → prepareAfterEditorResolved → executeOnConsole → afterSubmitted → startResultTabRetry 共 11 处签名，全部默认参数向后兼容，onInfo 缺席的调用方（LogToSqlPanel/模板动作等）行为逐字不变；面板三调用点（回退全链/executePrepared/全链）接 `resultPanel.showStatus`。读数：`ConsoleErrorExtractTest` 21/0/0/0（新增 4 例：DML 完成证据检出/纯回显不判/进度词不判/控制台不可读不判）；**反向验证**：判据摘除探针 → 恰好 1 红（testCompletionEvidenceDetectedForSuccessfulDml），还原回绿 21/0/0/0；受影响链定向回归 ExecuteSqlErrorHandlingTest 16 + PreviewResultIsolationTest 6 + ConnectionErrorClassifierTest 10 全绿。**证据边界（前提①）**：`use` 语句 DBN 是否写完成回显无真机样本——若 DBN 对 `use` 只写回显不写摘要行，文本判据无法与挂死区分，该场景维持旧提示；DML 完成回显形态（`1 row affected`/`completed in …`）有真机留证（pitfall 文档原文）。踩坑备注：chooseAndPrepare 递归调用点是位置传参，插参后错位编译红——改具名传参解。
- 收口：**完成（20261104 02:07 收口轮，工作树 mpsv-testgap-speed）**。全量 `testShards` 后台带进度跑（testShardsProgress.ps1，10 秒一行），退出码 0、墙钟 20:30（自适应限流）：分片本体 **8517/0/0/13**（shard1 1711 / shard2 1668+2 跳 / shard3 1502+4 跳 / shard4 1904+7 跳 / shard5 1732，0 失败 0 错误 0 WARN 失衡告警）+ 尾道 testShardTail 31/0/0/0（本轮 02:07 新鲜 XML 实证）+ testExternal 6 套件全绿。分片计数与预期吻合（8534 基线 − perf 摘出 31 + T1 新增 11 ≈ 8514，13 跳过为 Docker/Assume 常态构成）。全六券 T1~T6 提交齐、工作树干净；未推远端（欠推待用户令）。**T8 收口轮（0247 起）：见下方补记。**
