# T-SyncK2JMaster177：主分支功能同步（闲时车道 6）

- 日期：2026-11-08（闲时任务 offpeak-6a4afce2 阶段 1）
- 冻结范围：master `95e107b1849e58fdeb62d1e3927c424ac75319fc`（运行期间 master 再前进不追，留 178 轮）
- 锚点：`.port-sync` synced_master = `102795a0`（176 轮，2026-11-03）→ 本轮增量 **434 commits**
- 增量规模实测：src/main 112 件、src/test 166 件、agent 子项目 35 件、docs/skills/根级直取面若干
- **本轮特例（任务书 20261108 无人值守口径，优先于 skill §5 本地门禁）**：全量测试与构建校验全部走 CI（push-test 推送即跑；gate 走 master 的 build-plugin.yml dispatch `-f mode=gate -f branch=feature/kotlin-to-java`）；本地仅做中央编译 + 定向测试；推送已获用户授权（push-all.ps1，禁裸 push）。
- 并发约束：写集互不相交；子代理禁跑 gradle（编译统一主控中央收口）；全局活跃子代理含车道 4 在内按 4 起步、见 1302 限流即降。

## 一、写集划分表（生产翻译，波次内按依赖序）

| 批 | 写集（k2j 侧承载件） | 件数 | 依赖说明 |
|---|---|---|---|
| A1 | sql/ 参数与方言族：ParamHint、ParamQuote、ParameterReplacer、Dialect、DialectInference、ExplainSupport、PreviewDialectResolver、SqlFamilyParserDefinitions、SqlGenerator、SqlKeywords + 新件 RuntimeSlot、RuntimeSlotValues、SqlDateLiteral、goldendb/GoldenDbSqlDialect | 14 | 基础层先行（T-RuntimeSlotFill、T-DateLiteralBackfill、GoldenDB 第 15 方言都落在这层） |
| A2 | execute/ 全族（ChooseDataSourceTakeover、DataSourceSelector×3、DialectPreMapping、ExecDialectRouting、ExecFileBinding、ExecuteSqlSupport、PrepareSingleFlightGate、SqlExecutor + 新件 DialectMismatchHint、PreviewPreparePick）+ ui/ 非面板件（DialectGateRenderDialect(新)、DialectStatusRefresh、PagesEntityProbeResolver(新)、PagesXmlPreviewService、ReplayExportDialog、StackDigestFrames、nav/×3(新)、ref/×2、gutter/PagesXmlGutterLineMarkerProvider、components/CopyExecutableSqlAction、XmlInlineSqlPreviewService、XmlParamBackfillHints） | ~26 | 依赖 A1 的新 API |
| A3 | log/ 全族（SqlLogPanel、SqlLogManager、LogSqlModels、LogSqlRestorer、MyBatisLogParser、ConsoleReplayGuard、LogEntryRendering、LogPerfProbe、SqlEntryPresentation、SqlLogConsoleBridge、SqlLogEntryBuffer、CallerSourceLocator、CaptureDiagnostics、CaptureStatusBar、RailCellRendererBase、SourceInterceptorTemplates、agent/×5）+ config/×5 + update/（DevUpdateMode(新)、DynamicInstallSupport、PluginUpdateRunner、StartupUpdateCheck、UpdateSource） | ~29 | 与 A2 并行（写集不相交，依赖 A1） |
| A4 | ui/ 面板族（SqlPreviewPanel×5、SqlPreviewService×2、SqlResultPanel）+ model/（SourceLanguage(新)、BatchModel、BatchPreview、PreviewResult）+ 杂项（actions/×2、converter/×6、generator/×3、psi/×2、psource/×2、mcp/、share/、database/DbTableSpecAdapter）+ agent 子项目 Java 直移（35 件，master 侧本就是 .java，diff 实证后直取/移植） | ~55 | 依赖 A2/A3 落位后收口 |
| C | 资源与共享件（主控）：plugin.xml、database-optional.xml、bundle 双册（先比对键集合，无 k2j 专属键则整册直取）、buildSrc 2 件（mpv-testing 系 Kotlin 共享文件，三方合并，fastBucketSliceCount 切分支持要带进来）、.gitignore、AGENTS.md(master 新增)、.agents/skills/、docs/ 直取刷新、两册三方合并（docs/MEMORY.md + docs/变更记录.md，base 取锚点 blob）、dev-support/test-demo 直取刷新 | — | 波次 C 主控统一处理 |

**.github/workflows 特裁**：车道 3（20261108 提交 `1b3d1833`）已落 k2j 专属形态——push-test.yml branches=[feature/kotlin-to-java] + build-plugin.yml 用 243/253/262 与单 marketplace 聚合腿。本轮同步**不直取** master 的 push-test.yml/build-plugin.yml/nightly-full.yml；master 侧 workflow 增量不搬，留 k2j 专属。

## 二、波次表

- 波次 0（主控）：本计划立项笔（本文件）。
- 波次 A1 →（A2 ∥ A3）→ A4：生产翻译，每完成一件立即提交（pathspec 限定 + -F 文件，禁 add -A）；一律以 `git show 95e107b1:<path>` 读 master 终态（禁用主仓工作树）。
- 中央编译（主控，每波次后）：`classes testClasses` 双证（BUILD SUCCESSFUL + COMPILE_EXIT=0）。
- 波次 B：测试移植（166 件按功能族对齐生产批次），每件定向 `--tests "<FQCN>"`；跑 gradle 前走 parallel_tasks 登记。
- 波次 C：资源 union + 共享件 + 文档直取 + 两册三方合并 + 直取后全量对账（`git diff 95e107b1 --name-only` 逐件判向）。
- 波次 D：复推让 CI 跑全量 + 派发 gate；红只报不修（除本车道引入且 3 轮内可修）。

## 三、门禁标准（本轮全走 CI）

1. push-test（feature/kotlin-to-java）：5 片 + fast 全量，读数记 run id 与总数/失败/跳过。
2. gate：master 的 build-plugin.yml dispatch mode=gate branch=feature/kotlin-to-java（与 push-test 错开）。
3. 本地仅：中央编译 + 波次 B 定向测试 + 守卫基线重登的定向复跑。

## 四、守卫与基线注意

- fatal 基线三处联动（BannedApiSourceScanTest 总量 + BannedApiScanRuleTest 镜像/全量副本），以守卫实测读数登记，禁行计数预判。
- 形态锚点型守卫遇「Kotlin 默认参落 Java 双重载」须把锚点迁到全参签名。
- 新守卫类登记 GuardSelfTestCoverageMetaGuardTest 的 GUARD_CLASSES。
- 扫描根硬编码 .kt 的按 k2j 既定 .java 形态（勿改回）。

## 五、待验证前提（实施者先证伪再动手）

1. 假设 k2j 的 src/main/java 与 master 的 kotlin 包结构一一对应（同名类承载）；每件先定位 k2j 侧承载 .java，不存在才新建。
2. 假设 master 新件（RuntimeSlot 族、SqlDateLiteral、GoldenDbSqlDialect、nav/ 族、DevUpdateMode、SourceLanguage、DialectMismatchHint、PreviewPreparePick、PagesEntityProbeResolver、DialectGateRenderDialect）在 k2j 无既有形态。
3. 假设 buildSrc 的 fastBucketSliceCount 切分支持可三方合并进 k2j 的 mpv-testing.gradle.kts（k2j 版本 262 世系可能有分叉）。
4. 假设 bundle 双册 k2j 无专属键（若有，走 union 保键）。

## 六、结构性决策保护清单（勿当缺陷改回）

- k2j 生产 100% Java（禁引入 .kt 生产文件）；Function0/1/… 函数类型形态、静态门面（XxxKt→门面类）、record 无 get 前缀访问器、双重载默认参、sneakyThrow、守卫扫描根 .java 化——均历轮定案。
- .github/workflows 的 k2j 专属形态（车道 3）。
- agent 子项目两侧都是 Java：按 master diff 直移，不重造。

## 七、待决项

- 434 commits 一轮吃不完时：按「A1→A2∥A3→A4」依赖序推进，未完成批次留 178 轮续跑；`.port-sync` 锚点只在最终收口时推进到实际完成覆盖的 master 提交（若只完成部分翻译，锚点不推进、计划文件记进度）。
- gate 派发时机避开 push-test 在跑窗口（CI 并发纪律：同一时刻最多两路）。

## 实施记录

- 波次 0：本计划 `8cf96ef3`。
- 波次 A1（14/14 件，14 笔提交 `9f74a0bb`…`188f3f2f`）：ParamHint/ParamQuote temporal 族、ParameterReplacer 运行期占位+日期字面量（+728 行）、GoldenDbDialect/GoldenDbParserDefinition/GoldenDbSqlDialect 三件、SqlKeywords 384 词（逐词比对 384/384）、RuntimeSlot/SlotKind/ValueType/SlotValue/RuntimeSlotValues/SlotWarning/SqlDateLiteral 七新件、SqlGenerator quoteHint→paramHint、UrlSubprotocolDialects/PreviewDialectResolver/ExplainSupport/DialectInference 方言桥接。子代理自检 javac 隔离解析 0 错。
- 中央编译（A1 后）：BUILD SUCCESSFUL in 18s，COMPILE_EXIT=0。
- 推送 `68514340`（A1 14 笔 + docs 3 笔）→ CI run 42405958799（failure，中段预期红）：test(1)(2) 绿；新增红=方言族普查滞后（DialectTest 14→15、DialectVerifySupportUpsertMode、DialectXcFamily3、CrudSqlGenerator 大小写折叠族）+ EnumReachabilityGuard（SlotKind/ValueType 未登记）+ PluginXmlStructureGuard（GoldenDB EP 未进资源面）+ ReplayScriptExport——全部归波次 B（测试/守卫基线）与波次 C（plugin.xml/database-optional.xml）收口；另有既有潜藏红 ParamHistoryCache 19 + ExecutionDialectGate 2。
- 波次 A2（execute 12 件 + ui 非面板 14 件）与 A3（log 21 + config 5 + update 5 件）已并行派出。
- 波次 A3 完成（31 件 / 32 笔，`89a3870d`…`91135295`）：log/agent 全族（券 A/B/D/E/F2a/G2 全量语义）、config 双新设置项、update 内测双通道状态机与 263 卸载预检能力族。新承载件 2（DevUpdateMode.java、CountSnapshotAccumulator.java）。跨层交班：①bundle 键 ~15 个（log.diag.attachUnregistered、log.statusbar.catchup 等）归 C 波；②**PlatformEpDynamism.kt（master 新件 87 行）系计划漏项**，DynamicInstallSupport 反射探达（refusalExplanation(int) 签名钉死）——归 A4c 补译；③k2j 锚点前遗留分歧（refreshList 未接 applySlowOnlyFilter）按不扩面保留，留全量门禁实证。事故一笔：PluginUpdateRunner 补全笔误用 amend 改写他笔，已 reset --soft 重落（e11c2d8b/91135295）；教训＝共享工作树禁 amend 即便带 pathspec。
- 波次 A2 完成（27 件 / 24 笔，`a5eb99f1`…`055099a0`）：execute 全族（T-ConsoleBind263 策略链 S1→S4、UI-049 结构化提示、UI-003 预览分派）+ ui 非面板全族（T-MapperNavShortcut nav/ 三件、T-PagesHql 泛化、T-ThreadFix A/B/C）。新件 8（nav/ 目录新建）。交班：PreviewResult 37 参、xml/ 四件、ExecutableSqlFormatter 8 参是面板件硬前置。
- 中央编译（A2+A3 后）**BUILD FAILED：114 错 / 12 文件**——分型：①两族翻译期真 bug＝ApplicationManager 类型笔误 ×3（DataSourceSelectorConsoleKt:1190 / DataSourceSelectorResultsKt:1862 / ExecFileBinding:723）+ AgentAttachStats 缺 import java.util.List；②其余全为预期依赖缺口级联（PagesXmlPreviewService 50 错集中 xml//model/ 未落位）。外层命令退出码被管道吃掉（BUILD FAILED 但 shell exit 0）——判绿必须看 BUILD SUCCESSFUL 行，此坑再次实证。
- 波次 A4b 完成（15 件，`bd667296`…`0f082e7e`）：agent 子项目生产面直移。判定方法＝三 blob 比对（锚点/k2j HEAD/master tip）逐件实证 k2j HEAD≡锚点（无任何 k2j 专属行）→ 整件直取数学上零丢失；收口三证（15 件 blob 全等、diff 95e107b1 为空、每笔 diffstat=1 file）。要点：NestGate 64371dd8 结构修复、StatementRegistry 解包装登记（Oracle 错位修复）、EventBus F2b 聚合+券 I 溢出账本、SenderLoop 券 H burst 探测+drain 出账、ParamCapture setObject 四参「织了不捕」修复、GoldenDB/磐维 驱动三面登记。**波次 B 注意**：agent 测试 20 件须同步移植才能编译（BackoffSleeper/lastDeferredThreadForTest 等新测试缝）。
- 波次 A4a 完成（15 笔，`7699d6c9`…`24879c47`）：两族编译 bug 修复（ApplicationManager 类型 ×3、AgentAttachStats 缺 import）+ 地基 13 件/21 承载文件（ExecutableSqlFormatter 8 参、PreviewResult 39 参规范+37 参重载、SourceLanguage 新件、xml/ 六新件、SqlStringConcatenator 整件重写 332→1087 行 R1/R2 占位会话、JdbcTemplateSqlPreviewService 占位计数 string-aware）。A4a 后中央编译 114 错→**16 错/4 文件**（BUILD FAILED in 9s）：DbTableSpecAdapter:73（generator 前置）、DialectStatusRefreshKt ×4 行（面板前置）、MapperNavigationTargets:86（STATEMENT_TAGS 包私有跨包）、ChooseDataSourceTakeover ×2 为 [removal] 注记非错。
- 波次 A4c 完成（24 件 / 24 笔 + 随件补登 bundle 24 键与 STATEMENT_TAGS 可见性）：generator temporal 族、actions pages 上爬、converter temporal 全链、psi T-WriteFamily R4 三重门、PlatformEpDynamism 新件（refusalExplanation(int) 静态签名逐字）、ui 面板族 9 件（券 C 运行期占位行、UI-049 反射提示、T-PreviewConsoleOwnership 复用回收、T-PreviewBottomCollapse）。**生产面（src/main 112 件增量）至此全量收官**。
- 主控中央编译收口五轮（`354a2426`，114→16→14→10→6→1→0）：嵌套类 import、类可见性、39 参构造 threadName 槽位、sneakyThrow 透传 ×2、终态副本 ×4（intendedDsId + 三处 *DiagKey）、countAccumulator 构造器赋值序、UpdateSectionTest 补 url 形参。终态读数：**classes testClasses BUILD SUCCESSFUL in 10s / COMPILE_EXIT=0**——旧测试面靠委托重载纪律几乎零适配（仅 1 处）。
- 推送 `354a2426`（A2 24+A3 32+A4b 15+A4a 15+A4c 24+修复 1+docs 6 ≈ 117 笔），CI 待读数。**波次 B（测试 166 件）/波次 C（资源 union/两册三方合并/.port-sync 锚点推进）未做——留 178 轮续跑**；锚点维持 102795a0 不动（B/C 未完不推进）。

## 实施记录（178 轮：CI 红面收敛与资源面收口，2026-11-09）

- **入口读数**：run 42416634577 @ `4e9c78eb` 七腿全红，但已过编译——红面为 23 个测试类（fast 腿各 31 红、分片 2~10 红）。**关键教训**：本地 `classes testClasses` 绿是 UP-TO-DATE 增量态假绿（`compileTestKotlin` 被跳过），CI 从头编译才暴露 20 错；已改为 `compileTestKotlin --rerun-tasks` 验真并记入端口记忆坑 39。
- **测试编译收口**（`4e9c78eb`）：PrepareSingleFlightGateTest 补 QueuedExecuteIntent 第 9 参 onDialectMismatchHint（19 错全为该构造不匹配级联）、DynamicUnloadWriteSafeTest 的 PlatformApi 第 2 参改 UnloadGate(LEGACY, 哑方法)。
- **资源面与守卫基线**（`32178193`）：plugin.xml / database-optional.xml / bundle 双册自 master 整取（k2j 零专属键，键集 1374/1374）——GoldenDB 第五对 EP、MapperNavigation 两 EP、描述方言数 15；LocatorSkipPrefix 两处前缀表与 docs 示例模板补 `com.goldendb.`；SettingsRealityGuard 登记 pagesSourceCombo 别名；BannedApi 基线按守卫实测重登（ResultsKt 15→17、SqlLogConsoleBridge 15→16、ConvertToSqlAction 3→2、全量 957→998、未判定 529→559）。七类守卫 81 例 0 败。
- **波次 B 第一批**（`42401459`）：DialectTest 补 GoldenDB；SqlLogTailFollowTest 13 处构造走 newManager() 注入同步 catchUpScheduler（SqlLogManager 补同包 setCatchUpSchedulerForTest——master 侧是 Kotlin var）；PrepareSingleFlightGate 取消路径判据改 prepareOwner != null；PagesXmlPreviewServiceTest 补 `datasource type="sql"` 与 HQL 三断言；ExecutionDialectGate 两处普查止锚重锚。五类 98 例 0 败。
- **波次 C·buildSrc**（`c949106b`）：mpv-testing 移植 `-PtestShardExcludeClasses` 单一解析点 + `-PfastBucketSliceCount/-PfastBucketSlice` LPT 切片（并集 require 兜底）；mpv-verification 补单目标 243/253/262（**保持 k2j 目标集，不跟 master 263**）；带入 `dev-support/test-shard-weights.tsv`（822 行画像，此前缺失致切片退化均分）。验证：切片 164/168 类、权重和 118.6s/118.6s。
- **仍待办**：ParamHistoryCacheTest 19 例既有潜藏红（同步前即有，待定性）；docs 直取与两册三方合并；`.port-sync` 锚点推进（B/C 全完才推进）；lane-3 gate 模式派发验证。

### 178 轮续（CI 红面逐轮收敛，2026-11-09）

| 轮次 | 提交 | 红类数 | 关键动作 |
|---|---|---|---|
| 起点 | `4e9c78eb` | 23 类 | 编译通过但测试面全红 |
| R1 | `32178193` | — | 资源面（GoldenDB EP / bundle 1374 / 前缀表 / 示例模板）+ 守卫基线按实测重登（BannedApi 998/559、ResultsKt 17、SqlLogConsoleBridge 16、ConvertToSql 2） |
| R2 | `42401459` | 14 类 | DialectTest 15、SqlLogTailFollow 刷新窗注入（+SqlLogManager 测试缝）、PrepareSingleFlight 判据、PagesXml 三断言、ExecutionDialect 止锚 |
| R3 | `c949106b` | — | buildSrc：快桶切片 + 单目标 verifyIdes + 权重画像 → **fast 腿 1878+2194（原各 4072）** |
| R4 | `a5a65781` | 11 类 | **CRLF 敏感锚点**（多行 `\n` 锚在 CI 必红）改单行 + 设置项 47→48 |
| R5 | `9ad5e33c` | 10 类 | DialectXcFamily 15 方言 + SqlLogPanelFilter 刷新窗注入 |
| R6 | `65c81b72` | 10 类 | **ParamHistoryCache 19 例 NPE 根因＝端口把 `app != null && !isUnitTestMode` 写成 `app == null \|\| !isUnitTestMode()`（语义反转）**；另删 testFast 的 doFirst（破坏配置缓存） |
| R7 | `ee2736b8` | 待读数 | ExecDialectRouting 锚点预算 3400→6000（实测间隔 4199） |
| R8 | `cbbf8a40`+`d320f8e2` | 待读数 | **整片复现法**（本地 testShard1-4 带 CI 同款排除参数）拿到全部真实断言：方言黄金表/非折叠族/原生 upsert 族各补 GoldenDB、ExplainPanelWiring 判据放宽为「走快照链」、CustomDaoHql 占位判据放宽、GaussDbWiring 4→5 |

**剩余 3 类行为红（CI 红 / 隔离跑绿）**：SqlCaptureGateSniffTest（嗅探态仍产出条目）、SqlListV179HeadlessTest、SqlPreviewPanelDynamicBatchTest。**本地独有**：DialectDocTableGuard ×4——文档站在独立兄弟仓 `mybatis-plus-sql-visualizer-docs`，缺 goldendb 行（CI 无该仓故不计；属该仓自己的改动，需单独授权）。

**整片复现法（本轮新增作业法）**：CI 只给「类名 + 行号」，隔离跑又不复现时，本地按 CI 同款参数跑整片——
`./gradlew testShardN -PgifRecord=false -PtestShardExcludeClasses=<CI 同款> -PagentTestExclude=<CI 同款>`，
XML 报告里有完整断言原文。顺序/并发相关的红只有整片能复现。

**本轮两条硬教训**：① 本地 `testClasses` 的 UP-TO-DATE 会掩盖 CI 从头编译红，API 面改动必须 `compileTestKotlin --rerun-tasks`；② `.gitattributes` 是 `eol=crlf`，CI 检出 CRLF、本地常为 LF——内嵌 `\n` 的多行源码锚点必然「本地绿 / CI 红」，判据是 CI 报 `missing source marker`。
