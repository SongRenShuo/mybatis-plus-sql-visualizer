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
- 波次 A3 完成（31 件 / 32 笔，`89a3870d`…`91135295`）：log/agent 全族（券 A/B/D/E/F2a/G2 全量语义）、config 双新设置项、update 内测双通道状态机与 263 卸载预检能力族。新承载件 2（DevUpdateMode.java、CountSnapshotAccumulator.java）。跨层交班：①bundle 键 ~15 个（log.diag.attachUnregistered、log.statusbar.catchup 等）归 C 波；②**PlatformEpDynamism.kt（master 新件 87 行）系计划漏项**，DynamicInstallSupport 反射探达（refusalExplanation(int) 签名钉死）——归 A4 补译；③k2j 锚点前遗留分歧（refreshList 未接 applySlowOnlyFilter）按不扩面保留，留全量门禁实证。事故一笔：PluginUpdateRunner 补全笔误用 amend 改写他笔，已 reset --soft 重落（e11c2d8b/91135295）；教训＝共享工作树禁 amend 即便带 pathspec。
