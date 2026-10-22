# T-MergeMasterToDev2——master 并入 dev 冲突解决与全量门禁收口 开发计划

> 立项：20261010-1542，用户令。任务名 t-mergemastertodev2，沙盒 mpsv-mmgd2。
> 方向合法性：master→dev 属常规回流同步；dev→master 才须用户明令（在册纪律），本任务不涉及。

## 一、背景与目标

用户令：master 最近更新了大量功能（91 笔），要求切到 dev、把 master 合并进 dev、解决冲突、全量测试并修复问题、确保插件构建校验（verifyGate）通过。先出本计划再动手。

基线实测（20261010-1540）：

| 项 | 值 |
|---|---|
| merge-base | `1e1acf6`（v2026.10.9 发行 tag） |
| dev | `9593053` == origin/dev（worktree `mybatis-plus-sql-visualizer-dev`，工作树干净） |
| master | `599cfa6`（ahead 1 未 push） |
| 分叉 | master..dev = 62 笔（dev 独有）；dev..master = 91 笔（master 独有） |
| 冲突预演 | `git merge-tree --write-tree` 实测：10 文件真冲突，约 300 个双侧改动文件可自动合并 |

master 独有 91 笔=v2026.10.9 发行+批9 走查修复（CountSimToggle/ExportSqlFormat/DataSourceBadgeMode/SearchToggleNativeLook 等）+attach 达梦扩面与去重窗修复+PremainReattach+空态链接（T-SettingsFocusFromLink）+单击复制修复等；dev 独有 62 笔=R53 用户暂停、R60 展示/导出链脱敏（DisplayMasker 为 dev 独有新文件）、R61 EXPLAIN 跨次对比、R62 更新可诊断化、R54 历史耗时回填、阶段二批等。

注意事项：master 主树工作区有未提交改动（CHANGELOG + 一份 20261010 发行说明草稿，疑似发行会话在途）。本任务全程只在 dev worktree 操作，合并基线=master 已提交态 `599cfa6`，不读不碰 master 工作树的未提交内容。

## 二、冲突清单与逐文件裁决策略

预演树 `172a7b9`，冲突块数实测标注。总原则：零语义丢失，双侧特性共存，不做单边取舍。

| # | 文件 | 块数 | 裁决 |
|---|---|---|---|
| 1 | CHANGELOG.md | 2 | union 双保留，按各自时间序归位 |
| 2 | docs/变更记录.md | 1 | union 双保留，「最新在上」惯例插入 |
| 3 | SqlClickPreview.kt | 1（imports） | 双保留：dev 的 `DisplayMasker`（R60）+ master 的 `EditorCopyShortcuts`（单击复制） |
| 4 | SqlExportService.kt | 2 | `format` 合并为「master 四格式（含 SQL 分支）+ dev `mask` 参数」：`fun format(rows, format, mask: Boolean = false)`，先投影脱敏再四格式分发；`formatPortable` 取 master 的 `SQL -> error(...)` 防御分支 + dev 的 mask 参数 |
| 5 | SqlLogPanel.kt | 2 | 搜索条整取 master（nativeSearch/NativeSearchTextField 全套，弃 dev 的 `buildSearchField`）；保留 dev 的 `buildStatusBar`（R53 状态栏，master 从未有）；`syncDiagnostics` 合成=master 的空态链接路径（`applyEmptyTextWithEnhancedLink`）+ dev 的 `syncCaptureStatusBar()` 首行调用 + 定时器门取 dev 的 `manager.capturing`（master 的 `active` 是其子集，保 R53 状态栏捕捉期常刷） |
| 6 | AgentManager.kt | 2 | 块一：`gateNow = online && captureConfirmedActive() && !userPaused`（dev R53 暂停位并入 master 的 gateNow）+ master 通道纪元重置段原样保留；块二：master 首条事件 INFO 日志 + dev 暂停丢弃计数 early-return，先后共存（先日志后丢弃，首条证据不受暂停影响） |
| 7 | META-INF/plugin.xml | 1 | 整取 master（v2026.10.9 后发行描述重写版，dev 侧条目均系其旧措辞子集）；合并后 grep 校验 dev 无独有 EP 丢失 |
| 8 | PluginSettingsPersistenceTest.kt | 1 | 复选框计数合并态=41 基数+displayMask（dev）+paginationCount+badgeDetail（master）=预期 44，注释逐条列来源；以实跑读数为准修正，不预设 |
| 9 | SqlExportServiceTest.kt | 1 | 双保留：dev 脱敏判别组（mask 两态）+ master SQL 格式组 |
| 10 | SqlLogPanelRendererUiTest.kt | 1 | 双保留：dev 列表脱敏两态组 + master 数据源角标门控组 |

自动合并的 ~300 个文件语义面不预设正确，交门禁兜底（编译门→testShards 单路→agent→verifyGate）。

## 三、待验证前提（实施时先证伪，不当作既成事实）

1. **`entries.size()` 存活性**：dev 的 syncDiagnostics 用 `manager.entries.size() == 0`，master 同函数用 `manager.entries.snapshot().isEmpty()`——合并取 master 写法还是保 dev 写法，以合并树上该 API 是否仍存在为准（编译门验证）。
2. **R53 字段链完整性**：dev 的 `userPaused`/`pausedProvider`/`pausedDropped`/`statusBarPanel` 等字段分布在自动合并区，合并后须 grep 在场 + HelpDialogTest 守卫（工具栏组数）与 StatementWeaveWiringGuardTest 不红。
3. **搜索条无残留旧引用**：dev 侧 `top.add(searchField, BorderLayout.SOUTH)` 等引用区与 master 的 searchBar 宿主结构发生自动合并，若出现 `searchField` 重复声明或类型不符（SearchTextField vs NativeSearchTextField），按 master 结构收编 dev 引用，编译门暴露。
4. **SqlExportFormat 枚举**：合并树须含 SQL 成员（master 侧加）且 dev 侧 `formatPortable(..., mask=)` 调用点签名兼容；when 穷尽性编译验证。
5. **复选框计数 44 是推断**：以 PluginSettingsPersistenceTest 实跑读数为准，若自动合并区还有其他开关增员则按实际值修断言并逐条注释来源。
6. **dev 无新 EP/新反射入口**：dev 独有 62 笔均为既有面板行为扩展，无 plugin.xml EP 变更；由 SettingsTruthTest/PlatformSignatureCrossVersionTest 兜底。

### 前提核验结果（20261010 实施时回填）

1. `entries.size()`：**成立**——`SqlLogEntryBuffer.size()` 在场，`syncDiagnostics` 保留 dev 的 O(1) 判定（前提成立，无需改用 master 的 `snapshot().isEmpty()`）。
2. R53 字段链：**成立**——`pausedProvider`/`pausedDropped`/`userPaused`/`statusBarPanel`/`syncCaptureStatusBar` 全部在场。
3. 搜索条无残留旧引用：**部分证伪**——`searchField` 无重复声明（master 侧 `= nativeSearch` 唯一），但发现 master 侧接线缺失（见 6.2，已补回）。原前提只预设「重复声明/类型不符」，实际形态是「接线丢失」，靠 `onSearchTextChanged` 孤儿 + 未用导入 + `hideSearch` 注释三处证据钉死。
4. SqlExportFormat 枚举：**成立**——合并树含 SQL 成员，`formatPortable` 的 SQL→error 防御分支与 dev 的 mask 参数共存，编译门通过。
5. 复选框计数 44：**待定向测试实测**（PluginSettingsPersistenceTest）。
6. dev 无新 EP：**成立**——plugin.xml 取 master 版后无 EP 丢失（三目标 verifyGate 待跑）。

## 四、实施步骤

0. `parallel_tasks.py check`（已做：无活跃任务）→ `register --name t-mergemastertodev2 --sandbox mpsv-mmgd2`。
1. 提交本计划（dev，随批转 CRLF，`-F` 文件提交）。
2. `git merge master`（预期 10 文件冲突）→ 按 §二逐文件裁决 → `grep -rn '<<<<<<<\\|>>>>>>>\\|======='`（限定冲突标记形态）复核零残留。
3. 编译门快跑：`./gradlew compileKotlin compileTestKotlin :sql-visualizer-agent:compileJava`——快速暴露 §三语义缝，不跑测试。
4. 提交合并 commit（消息按上轮 fb60c3d 惯例：冲突裁决逐文件列明，`-F` 文件）。
5. 全量门禁（单路，先 `timeout 10 docker info` 验 daemon 活性）：`./gradlew testShards :sql-visualizer-agent:test` 后台跑，不 sleep 等待；读数目标=testShards N/0/0/9 + agent M/0。
6. 红→定位修复→定向复验；凡修复触及 src/main 或测试源，权威轮 testShards 必复跑后再进 verifyGate。
7. `./gradlew verifyGate` 后台跑（三目标 243/253/262 Compatible）。
8. 收口：变更记录行 + 本计划 §实施记录回填（读数+裁决实录）+ 与 MEMORY/盘点同步评估 + `parallel_tasks.py done --name t-mergemastertodev2`。

## 五、风险与回退

- 资源定例：全量分片只单路收口；R16「空机也可能顶 Commit」处置=清自家 daemon 原样重跑；R17 用户真机在跑时测试被杀=降足迹参数原样重试，禁杀用户进程；R15 两份全量门禁互斥——本任务全程串行单路。
- 合并中途废弃=`git merge --abort`，工作树回到 dev `9593053` 干净态。
- 门禁期间主控不碰 src/main（收口纪律）；修复走独立提交并触发权威轮复跑。
- 不 push：用户未令 push，收口后 dev 领先 origin 若干笔，push 与否待用户令。

## 六、实施记录

### 6.1 合并与冲突裁决（20261010-1550 起）

- 计划提交：`4e23f85`（本文件，随批转 CRLF）。
- `git merge master`：与预演完全一致，10 文件真冲突（CHANGELOG / docs 变更记录 / SqlClickPreview / SqlExportService / SqlLogPanel / AgentManager / plugin.xml / 三个测试文件），其余约 300 双侧改动文件自动合并。
- 逐文件裁决实录见合并提交信息（union 双保留 + 三处语义合并：SqlExportService 四格式+mask、SqlLogPanel 状态栏+空态链接+搜索条、AgentManager 暂停位+通道纪元；plugin.xml 取 master 重写版）。
- 变更记录重排细节：首次 sed 按输入行序输出成「dev 行在上」，已二次调换为 master 10-10/10-09/10-08 行在上（最新在上），492 行零残留标记。
- CHANGELOG 归位：dev 侧 12 Added + 3 Fixed 与 master 侧 1 Added + 3 Changed + 5 Fixed 按 Added/Changed/Fixed 三节归位；第二处 hunk 取 master（[2026.10.7] 不另起 Fixed 小标题）；第一块尾部标记漏删一处已补删。
- 未用导入清理：SqlLogPanel 弃 dev `buildSearchField` 后 `SearchTextField`/`KeyAdapter`/`KeyEvent`/`DocumentEvent`/`DocumentListener` 五个导入成未用，删除（后因 6.2 补接线，后两个导入已按需恢复）。

### 6.2 合并缝发现与处置：日志页搜索接线丢失（master 侧存量缺陷）

master 的 `T-SearchToggleNativeLook`（`2f992b2`）把日志页搜索框迁到平台原生 `SearchTextArea` 时**丢了 DocumentListener 接线**：master 侧 `onSearchTextChanged()`/`searchDebounceTimer` 成为零调用孤儿、`javax.swing.event.DocumentEvent/DocumentListener` 两个导入未用、`hideSearch()` 仍写着「非空时触发 DocumentListener 清过滤并刷新」的注释。dev 侧原接线在 `buildSearchField` 的 DocumentListener 上，本次合并弃该函数后接线一并消失 ⇒ 若不补回，日志页「键入即过滤」「隐藏搜索条即清过滤」两条真实路径失效（测试钩子 `applySearchTextForTest` 直调内部入口，覆盖不到真实键入分派，故 master 自身门禁未暴露）。

处置：在 `SqlLogPanel` 的 `searchField` 声明后补 `init` 接线（`nativeSearch.addDocumentListener`，用 `NativeSearchTextField` 专为兼容暴露的委托 API），并恢复两个导入。属「合并保住 dev 行为」范畴，非新增功能。

### 6.3 编译门（含一次假绿更正）

- **⚠️ 假绿更正**：前两次编译门用 `./gradlew ... 2>&1 | tail -40` 取值，管道退出码取 tail 的 0，实际 gradle 已 FAILED——在册「tail 管道假绿」坑三度复现（PIPESTATUS 定例）。后续一律改为「重定向到文件 + `$?`」取值。
- 真实读数（重定向取值）：`./gradlew classes testClasses :sql-visualizer-agent:classes` → 首轮 FAILED（3 处编译错误），修复后 GRADLE_EXIT=0 / BUILD SUCCESSFUL（2m5s）。
- 首轮暴露并修复的两处编译错误（提交见 §6.5）：
  1. `SqlExportService.formatPortable`——合并编辑把 `SQL -> error(...)` 防御分支落在 `when` 闭合括号外（dev 侧 `}` 被 git 当作公共上下文），闭合括号移到 SQL 分支后。
  2. `SqlPreviewPanel:1118`——`embedConsole(..., previewMode = true)` 撞 1501ebf 的 `embedMode` 归口（该笔漏迁 R42 probe 调用点）⇒ **dev 自身 tip 即编译不过**（1501ebf 晚于 dev 最后一次门禁 27009aa）。按既有映射（执行点一律 EXECUTE）改为 `embedMode = ConsoleEmbedMode.EXECUTE`。

### 6.5 提交链与收口待办

提交链（dev）：`4e23f85`（本计划）→ `c372b3f`（合并提交，10 文件冲突裁决）→ `87c1141`（日志页搜索接线补回）→ `81f53d1`（两处编译错误修复）→ `7dbefba`（PrepareSingleFlightGateTest 守卫计数 5→6 同步）→ `f368752`（二次合并：master `91a2e35` v2026.10.13 发行同步并入）。

收口待办：①变更记录行（拟稿见下，读数待填）；②本计划 §6.4 读数回填；③`parallel_tasks.py done --name t-mergemastertodev2`。

**master 侧同缺陷修复（用户令，2026-10-10）**：派子代理在 master worktree 用 `git cherry-pick -n 87c1141` 零冲突落地（master 侧两 import 本就在，3-way 识别为已应用未重复；仅新增 init 块 13 行），提交 `7e25276`；编译门 exit 0 + 定向 6 类 97 例 0 失败（`SqlLogPanelFilterTest`/`SqlLogPanelLayoutTest`/`SqlLogPanelRendererUiTest`/`NativeSearchTextFieldStructureTest`/`NativeSearchTextFieldProviderContractTest`/`EmptyTextRenderTest`）；未 push（master ahead 4）。主控已独立核验产物：`7e25276` 的 init 块与 dev `87c1141` 逐字相同。

变更记录行拟稿（读数回填后落地）：

```
| 2026-10-10 | **T-MergeMasterToDev2：master 91 笔并入 dev（用户令）**：merge-tree 预演 10 文件真冲突，逐文件裁决（CHANGELOG/变更记录 union 双保留；SqlExportService 合并为「master 四格式 + dev mask 参数」；SqlLogPanel 保留 dev R53 状态栏 + 取 master 原生搜索条/空态链接；AgentManager 合并 dev 暂停位与 master 通道纪元；plugin.xml 取 master 重写版；三测试文件双保留）。合并缝两处实修：①master `T-SearchToggleNativeLook`（2f992b2）迁原生 SearchTextArea 时丢失日志页搜索框 DocumentListener 接线（`onSearchTextChanged`/`searchDebounceTimer` 零调用孤儿 + 两导入未用 + `hideSearch` 注释仍指望监听器），dev 侧接线原在 `buildSearchField` 上、合并弃该函数后一并消失 ⇒ 补 `init` 接线（`87c1141`）；②dev 自身 tip 编译不过——`1501ebf` 的 `embedMode` 归口漏迁 R42 DML 影响面预演 probe 调用点（`previewMode = true` 撞只收 `embedMode` 的函数），按既有映射改 `ConsoleEmbedMode.EXECUTE`（`81f53d1`）。读数：编译门 GRADLE_EXIT=0（前两轮系 tail 管道假绿，已更正）/ 定向 17 类 / testShards / agent / verifyGate 三目标（待回填）。 |
```


### 6.4 全量门禁

- 资源前提：并行登记表实测存在另一会话 `T-Release1013`（master 树跑 `releaseGate`，含全量 testShards）——按 R15「两份全量门禁互斥」定例，本轮 testShards 待其结束再单路启动，期间先跑合并面定向测试。
- **定向第一批（17 类，重定向取值）**：236 例完成 / 34 失败——**全部为 `java.lang.NoSuchMethodError`**（`PluginSettings$State.<init>` / `SqlLogPanelKt.filterBySearch` / `PreviewResult.<init>` 三处构造签名不匹配），在册「第 0 条」陈旧测试类假失败（`:testClasses UP-TO-DATE`：主源 ABI 变了、测试类未随重编译）⇒ 处置 `compileTestKotlin --rerun-tasks`（COMPILE_EXIT=0，2m13s）。
- **定向第二批（24 类，重定向取值）**：`build/test-results/test/*.xml` 聚合 **tests=311 / failures=0 / errors=0 / skipped=0**（TEST_EXIT=0，1m7s）。覆盖：`PluginSettingsPersistenceTest`（**复选框 44 断言成立**，即 41 基数 + displayMask + paginationCount + badgeDetail）、`SqlExportServiceTest`（四格式 + mask 合并）、`SqlLogPanelRendererUiTest`、`SqlLogPanelFilterTest` + `NativeSearchTextField*Test`（搜索接线补回不破坏既有）、`PrepareSingleFlightGateTest`（embedMode 守卫 5→6 同步后绿）、`ExecuteSqlErrorHandlingTest`/`ConsoleHostTest`/`DmlImpactEstimatorTest`/`ExplainPlanResultViewUiTest`/`PluginUpdateRunnerTest`/`UpdateCheckDiagnosisTest`/`HelpDialogTest`/`GuardSelfTestCoverageMetaGuardTest` 等。
- **agent 子项目**：`:sql-visualizer-agent:test` → **30 类 / 157 例 / 0 失败 / 0 错误 / 0 跳过**（BUILD SUCCESSFUL，1m26s）。
- **环境受限登记**：Docker Desktop 未运行（0 进程，npipe 不存在），`mpvs.docker.tests.enabled` 缺省 false ⇒ 容器方言类测试按设计不跑，本轮不构成容器侧验证（按「环境受限未验证」口径登记，不冒充通过）。
- 全量 testShards（诊断轮，基线 `7dbefba`）：分片 1/3/4/5 全绿，**TOTAL 6919 例 / 3 失败 / 0 错误 / 8 跳过**（testShard2 三处红）。三处逐一定性：
  1. `DataSourceJdbcSmokeTest::testAllDemoDataSourcesReachable` + `::testExecuteValidationSqlOnEachSource`——环境门控形态问题：该文件合并后取的是 dev 版（`assumeTrue`），而平台 runner 把 `AssumptionViolatedException` 记为 **failure 红任务**；demo 的 `dataSources.xml` 现含达梦源 `mpj_dm@114.66.55.245`（驱动不在主测试 classpath，`DmJdbcDriver18` 只挂在 agent 子项目）⇒ 逐源 assumption 违规被记红。
  2. `EmptyTextRenderTest::test空态起步引导三径齐全`——中英 bundle 空态链接键大小写：合并版为 dev 的 `Enhanced capture`（小写），测试契约（两侧同文）断言 `Enhanced Capture`。
- **根因共性（重要）**：两处都不是本次冲突裁决的错误，而是**合并基线过旧**——master 在任务执行期间由发行会话推进出 `91a2e35`（release: v2026.10.13 发行八处同步），该笔正是修这两处的提交（`DataSourceJdbcSmokeTest` 改「显式绿跳 + 逐源驱动过滤」；bundle 空态链接键定为 `Enhanced Capture`，同批还记有「空态引导英文链接文案大小写回归修复」条目）。master 侧同环境分片读数 6716/0/0/8 全绿可作对照。
- **处置＝二次合并**（`f368752`，仅 CHANGELOG 一处冲突）：把 `91a2e35` 与 `d337989` 并入 dev，直接带入 master 对这两处的修复（不做手工搬运）。CHANGELOG 裁决＝整取 master 策展版（`[Unreleased]` 收编为 `[2026.10.13]` 发行段，dev 细粒度条目被聚合进对应条目）+ dev 独有 15 条放回 `[Unreleased]`（未发行语义）。
- 全量 testShards（权威轮，基线 `f368752`）：**6919 例 / 0 失败 / 0 错误 / 8 跳过**（分片 1: 1468/0/0/0；2: 1348/0/0/0；3: 1484/0/0/6；4: 1275/0/0/2；5: 1344/0/0/0；COMPILE_EXIT=0、SHARDS_EXIT=0）。诊断轮的三处红全部消除，且无新增红。
- verifyGate 三目标（基线 `f368752`，产物 `mybatis-plus-sql-visualizer-2026.10.13.zip`）：**三目标全部 Compatible**——IU-243.28141.41（5 待移除/25 弃用/95 实验 API）、IU-253.33514.17（6/34/93）、IU-262.10968.63（8/94/93）；VERIFY_EXIT=0，BUILD SUCCESSFUL in 2m19s。唯一告警为 `LightEditCompatible` 实验 API 引用（既有形态，非本次引入）。

### 6.6 后续两件（2026-10-10 用户令）

1. **搜索接线守卫**（dev `cc9e014`）：新增 `log/SqlLogPanelSearchWiringGuardTest`——源码扫描断言 ①`nativeSearch.addDocumentListener(` 在场、②`insertUpdate/removeUpdate/changedUpdate` 三回调都落 `onSearchTextChanged()`、③防空绿（`onSearchTextChanged` 出现 ≥4 次）、④防抖入口与 `searchDebounceTimer` 仍在；并按元守卫要求登记进 `GuardSelfTestCoverageMetaGuardTest.GUARD_CLASSES`。反向验证三段实测：基线绿（GREEN1_EXIT=0）→ 删掉接线 init 块（`addDocumentListener` 计数 0）→ **`SqlLogPanelSearchWiringGuardTest > 日志页搜索框文本变更必须接到防抖搜索链 FAILED`、RED_EXIT=1**（日志无 `^e:` 编译错误行）→ `git checkout --` 还原（计数 1）→ 复绿（GREEN2_EXIT=0）；基线与还原两轮元守卫同绿。
2. **行尾一次性根治**（dev `0fc6db1`）：新增 `.gitattributes`（`* text=auto eol=crlf`、`*.sh`/`*.bash text eol=lf`、常见二进制 binary）+ `LICENSE` 重规范化（本次唯一内容变化）+ 工作树 113 个 LF/mixed 归一为 CRLF。终态：`w/mixed` 归零、`w/lf` 只剩 5 个 `.sh`（符合 `eol=lf`）、`i/crlf` 归零。配方与三个陷阱（`checkout-index` 对等值文件跳过不写、`i/-text` 文件勿转、中文路径勿经 shell）落项目记忆 `project-line-ending-policy`。
3. 两项改动后权威 testShards（基线 `0fc6db1`）：**6989 例 / 0 失败 / 0 错误 / 8 跳过**（分片 1: 1468/0/0/0；2: 1348/0/0/0；3: 1484/0/0/6；4: 1275/0/0/2；5: 1414/0/0/0）。verifyGate 无需复跑（本次仅新增测试源与 `.gitattributes`，插件二进制未变）。master 侧同步（cherry-pick 两笔 + master 工作树行尾归一）：待回填。

### 6.7 附带根治：Git Bash 中文乱码（2026-10-10 用户令）

根因实测：**Windows 控制台代码页 936（GBK）**，而 Git Bash 与各工具输出 UTF-8 字节 → 消费端按 GBK 解码即乱码（文件无损，已用 `git hash-object --path` 比对证明）。处置三层：①git 显示层全局项 `core.quotepath=false` + `i18n.logOutputEncoding/commitEncoding=utf-8` + `gui.encoding=utf-8`（CJK 路径不再被转义——此前 `git add <中文路径>` 失败与 `checkout-index` 匹配不上的元凶）；②终端层新建 `~/.bashrc`（`chcp.com 65001`）+ `~/.bash_profile`（source .bashrc；两文件此前均不存在，删除即回退）；③输出侧纪律（中文验证走计数/哈希/Read 工具，不走 stdout 肉眼；`grep` 对含非 UTF-8 字节的日志要加 `-a`，否则静默返回空）。配方落项目记忆 `git-bash-cjk-mojibake-fix`。

