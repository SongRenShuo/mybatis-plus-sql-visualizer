---
name: project-dev-batch-20261031
description: 20261031 全景盘点全量实施批——G1 合流(fr45+magpie→dev)、G2~G5 四路并行、D1/D2 文档核对；
  巨文件五件终于全拆、AGENTS.md 与索引缺口归零、update.json 双通道口径订正
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# 20261031 全景盘点全量实施批（G1~G5 + D1/D2）

**用户令**：「实施盘点文件的所有内容；从主分支新建分支和工作树并行开发；G1 只能将 t-fr45-integration / t-magpie-gems 合并到 dev，另一个分支（t-sql-insight）不要动；所有操作都不允许合并到主分支；先把主分支合并到 dev；全部只跑定向测试，最终全量」。

**执行拓扑**：所有产出落 **dev**（master 未合任何功能分支）。dev 起点 `ab40a179` → 快进 master `087fcace` → 合 fr45 `9cad9f52` → 合 magpie `072db331` → 修 plugin.xml `5709bcfd` → 同步 r34 文档 `cd016005` → 合 G2/G4/D1/D2/G5 `6d426d55` → 合 G3 拆分 `ac172b0f` → 收口复检 `7b0f0862`。

**G1 合流**：fr45 3 处文档冲突（CHANGELOG/变更记录/索引，取并集）；magpie 3 处（双语 bundle + 守卫白名单，取并集）。
**踩到的坑（重要）**：`git add -A` 收口把 **plugin.xml 未解冲突标记**一起提交了（git 只检查索引状态不看内容），`patchPluginXml` 报 `JDOMParseException: line 387` 整条构建失败——修 `5709bcfd`。教训：合并收口**不要用 `git add -A`**，逐个 add 冲突文件；合完先跑 `patchPluginXml` 验 XML。

**G2 纪律债**：plugin.xml 注释改引单一事实源（`DialectRegistry.dialects`/`McpToolGate.TOOL_NAMES`）；尾注实际 **6 处**（不是 2 处）+ 孤儿残句 **4 处**（不是 1 处）已接回；AGENTS.md 补「提交与回填」四条（D201 兑现）。
**G3 巨文件（第六次登记终于兑现）**：五件全拆到 2000 以下——SqlLogPanel 2485→1828、SqlPreviewPanelExecution 2409→1552、DataSourceSelector 2314→1989→**2000**（G5 增量后又搬 LastUsedChangeEvents）、DataSourceSelectorResults 2162→1987、SqlPreviewService 2082→1847。预算全部收到 2000 红线。**关键**：拆出代码后**守卫扫描面必须铺开**（按后缀匹配家族全集），否则搬出去的逻辑从守卫视野消失＝假绿。
**G4 R45**：①结构化通道转正默认开（`PluginSettings.kt` false→true，守卫改名 `ExplainStructuredDefaultOnGuardTest`）②**前提证伪**「索引建议只在 MCP 面」不成立（`ExplainPlanResultView.kt:46/99-108/146` 早有 hints 建议条）→ 未实施 ③OB 文本树规则补齐（以 OB 表头为门分派，`TABLE FULL SCAN`→缺索引／`TABLE RANGE SCAN`→高估算）。
**G5 四券**：NEW-18 环境体检（diagnostic 包，P-7 取证 premain/agentmain 心跳键面一致、仅时序差）、换源重渲染（120ms 防抖只刷头部）、Community 执行入口预判置灰、NEW-19 版本亮点通知。

**D1 活文档**：9 文件 7 笔；架构 01 包数 23→24/服务 14→16/EP 计数全面刷新；架构 03 测试口径重算（源文件 852、`*Test.kt` 827、守卫 51/6/63）+ **删掉「5 片严格串行」这条已被 rp9 推翻的旧结论**；README 补 4 处 FR-0045 能力。**报出一处真 bug**：`TraceDocExport.kt` 读迁移源旧布尔 `displayMaskEnabled` 而非 `displayMaskExportOn()` → 用户设 WARN/BLOCK 档时留痕导出**完全不打码**（已修，含在 `7b0f0862`）。
**D2 索引**：补登 3 个孤儿册（`T-RecordSkillConsolidate`/`T-XmlBraceNested`/**`T-SelfUpdateRollback`**，第三个此前从未登记）、删 `T-XcDialects3` 重复行；`docs/MEMORY.md` 六处计数刷新 + 新增「录制与验收」节；索引 **637=637，孤儿 0、死链 0**。

**本轮暴露的真问题（均已修）**：①plugin.xml 冲突标记误提交 ②TraceDocExport 脱敏档位 bug ③G5 增量使 DataSourceSelector 2023 > 2000、PluginSettingsPanel 1887 > 1830（各拆一件；后者预算按实测校准 1830→1840 并注明两笔合法增量来源）④Kotlin 增量编译不跟踪 top-level 函数换 facade / data class 构造器签名 → 测试假红，处置＝删 `build/classes/kotlin/test` 重编（G3 登记，收口再遇一次）。

**遗留（当日已全部解决，`536d3b25`）**：①R45 建议条本地化——`ExplainIndexAdvisor.Hint` 加可选 `metric` 字段（FULL_SCAN/HIGH_ROWS_ESTIMATE/TABLE_FULL_SCAN 填量化值，默认 null 零破坏），`ExplainPlanResultView` 按 `rule` 取 bundle `explain.hint.<rule>`（命中则中文+metric 括注、缺键回落英文 `advice`），**MCP 侧 `advice` 英文口径与单一逻辑源均不变**；bundle 中英各补 7 键。②`DiagnosticReportFormatter` 措辞订正为「本诊断报告导出链取不到」（结构化面板另有 hints 建议条，原文「只挂 MCP 面」被 G4 证伪）。③文档命名规范补 `-说明文档.md` 第四后缀 + 存量豁免 + 根级账本例外（详见 [[project-doc-conventions]]）。

**P2/P3 收口（同日后半段，盘点第 80 版 `0459d682`）**：
- **#14 NEW-15 方言适配矩阵页 ✅**：docs 仓新增 `features/dialect-support.md` + `en/features/dialect-support.md`（各 71 行）+ 侧栏两条。**14 方言 × 三档验证等级**：真机引擎级 11（MySQL 262 词/PostgreSQL 86/Oracle 109/SQL Server 180/H2/SQLite/MariaDB 281/DM 254/KingBase 118/openGauss 186/GaussDB 197「Kernel 503」）、社区镜像级 3（OceanBase 262/瀚高 153/海量 201）、**文档级只有 GaussDB 的 EXPLAIN 一个单元格**。等级口径＝「社区版或社区第三方镜像即社区镜像级，不是厂商官方商业版本」，故 OB CE 归社区级、SQL Server/MariaDB 用官方完整产品镜像归真机级（三条不矛盾）。增强捕获 attach 端到端只有 5 族（MySQL/PG/Oracle/SQL Server/DM）有读数，其余按实际深度写「织入」或「织入+冒烟」。`DialectDocTableGuardTest` 只扫 6 个固定文件，新页不在扫描面内；`npm run build` 通过。
- **#22 docs 结构整理 ✅**：方案五条里三条此前已落（`docs/规范/` 建目录、`docs/调研/`→`调研报告/`、`docs/验收报告/`→`验收文档/`、变更记录拆两套），本次补完两条——`code-standards.md` → `docs/规范/编码与验收标准-规范.md`（git mv + 10 处引用同步）+ 命名规范补根级例外。
- **#20 k2j 第 137 轮跟版**：用户已派闲时任务承接，本册销案。
- **#18 设置收敛**：54 Boolean 分四类、四阶段方案见 [[project-settings-convergence]]（代理实施中）。

**当日最终剩余**（第 80 版口径）：A 进行中＝#18；B 等拍板/授权＝#8 W1+N-24（P0，第二十一轮零稿）、#4 insight 合流（需 dev→master 授权）、#11 D204 脉冲定性、#23/#24 用户驱动；C 环境依赖＝#19 真机攒批、#21b 两张 GIF 重录；D 观察池＝#26。

**盘点外补充（同日晚些，用户逐项追问后的处置）**：
- **#23 R15 场景脚本实际已完成**（`18ebe30b` n02 定稿 + `6e182809` n04 + `26dd5a96` n03/n05，工作区零未跟踪）——盘点里「骨架在途」那行是旧状态，下次刷新应销案。
- **#4 t-sql-insight 改走「先补 GUI 走查」**：用户不复用 A/B 方案，选 C，并令**先 `dev → t-sql-insight`（反向）**做走查基线——与「insight 合入 dev」是两件事，别混。合并细节与串行依赖见 [[project-sql-insight-campaign]]。
- **#21b 两张 GIF 重录**：确认可在**主工作树 master** 做（`build/` 各自独立，与 dev 工作树不冲突），但必须排在 #18 之后、GUI 走查之前（三者都要 gradle / `runIde` / 前台焦点，严格串行）。脚本齐：`log-community.gif` ↔ `scene_n10_community.py`；`code-completion.gif` ↔ `scene_t3_completion.py`（另有 `r07_completion` / `a28_completion_datasource` 候选）。

## 附：补全 Enter 采纳触发的平台断言疑点（20261031 核实，未确认）

**现象**：在 MpSqlMapper 注入片段里用 **Enter** 采纳补全，IDE 弹「IDE error occurred」，日志断言
`CompletionInitializationUtil.setOriginalFile: MpSqlMapper:UserMapper.xml != MpSqlMapper:UserMapper.xml`。Tab 采纳无异常。

**已确证**：①断言是**实例身份**比较（`CompletionInitializationUtil.java:192-206`，`currentOrigin != copy && currentOrigin != origin` 即抛），非内容比较；②唯一调用点 `CodeCompletionHandlerBase.java:376`（异步贡献器线程，日志标签 `AsyncCompletion`）；③**我方从不调用 `setOriginalFile`**；④两侧 toString 相同源于 `SqlMapperParserDefinition.kt:36` 的 `toString = "MpSqlMapper:" + name`；⑤**显式 `Ctrl+Space` 用宿主编辑器、`isInjectedFragment` 为 false、断言不可达——只有键入触发的自动弹出才可达**（`CompletionPhase.java:130-186`，平台注释自述「typing might have destroyed the old one completely」）。

**未确证**：实际不匹配发生在哪一对实例。判读为同一注入文件的两个 PSI 实例（`XmlText` 注入 vs `XmlAttributeValue` 属性注入各一份，或缓存 drop 后重建）。**归属平台侧**，我方提供注入语言让路径可达但无越界。

**headless 复现失败**（三套探针 8 例全绿，分支 `t-fix-completion-original-file` @ `6cfa5d12`）：触发需「注入 PSI 实例在异步窗口内被替换」，243 headless 造不稳；且录制机 IU-262 而测试只能跑 243。

**定性＝代码可判疑点**（非已确认、非证伪）。未修——唯一规避（`SqlMapperFile.getOriginalFile()` 恒返自身）会破坏平台 PSI 原件链语义（导航/高亮映射/`getOriginalElement`），不符合「可单独回滚的补丁」要求。

**真机复现判据**（等 GUI 空闲做）：**只键入触发自动弹出**（不要 Ctrl+Space）→ Enter 采纳 → 重复 5~10 次 → 查 `idea.log` 是否有 `AsyncCompletion` + `setOriginalFile`。若「显式 Ctrl+Space 从不复现、仅键入自动弹出复现」即与静态分析一致。

**用户决策（20261031）：不向 JetBrains 报 issue**——即使真机坐实是平台侧竞态，也只在内部记录为已知限制/自行规避，不提交 upstream。后续若再做此路径的改动，以「不复现即不动」为原则。

**分支与合并状态（20261031 收尾）**：`t-fix-completion-original-file`（@ `6cfa5d12`）**未合入任何分支**——dev 仍 `fa23f5d8`、master 仍 `802fe329`；三套探针测试（`InjectedCompletionAcceptanceTest` / `InjectedCompletionAsyncAcceptanceTest` / `InjectedCompletionMultiPlaceTest`，8 例）**不应原样并入 dev**（它们钉的是「平台断言未触发」这一现状，不是修复），分支与工作树 `mpsv-fix-completion` 保留作证据现场。真机复现排在 GUI 验收之后。

## 附二：docs 站部署待办（20261031 用户令，等这批收口后派子代理）

**用户令**：「部署到 docs 站——等这批收口后，派子智能体去部署」。**不要主控直接 deploy**。

**待部署两笔（实测）**：`847823e` 方言支持矩阵页（中英双页 + 侧栏，已推）+ `a9cc730` 重录 `code-completion` / `log-community` 两张动图（**未推远端**，本地领先 `origin/master` 1 笔）。

**部署代理须做三步**：①**先 push**（`a9cc730` 还在本地）→ ②`npm run deploy`（token 取仓库根 `.cf-token`，导出 `CLOUDFLARE_API_TOKEN`，见 [[reference-docs-deploy-cloudflare-token]]）→ ③**线上四条验证**。

**验收口径（关键）**：**退出码 0 不代表成功**——必须线上验证：中英新页可达、新图 URL 200、页面引用/`<GifPlayer>` 在场、pagefind 索引已更新。只看 wrangler 退出码＝假绿。

**「这批收口」的范围**：GUI 验收（insight）出结果 → 补全 Enter 断言真机复现定性 → 若确认缺陷则修复合 dev → 然后**一次**把 docs 部署、dev 全量门禁、双仓推送做完（避免部署与代码收口各跑一遍）。若验收挖出需改 `src/main` 的缺陷，批次范围扩大、已拿到的绿全部过期须复跑，**先报用户不擅自扩**。

## 附：T-SqlInsight 方案 C 进展（20261031 夜）

用户定案走 C（先补 GUI 走查再决定是否入主干），全程**未合 master**。

1. **dev → insight 合并**（`bef08646`，4 处冲突）：关键难点是 dev 刚把 `SqlPreviewPanelExecution.kt` 拆了（D202），而 insight 在原文件有 3 处真实增量——取 dev 结构后手工回贴：`prepareDefaultConsole` 的准备链代际守卫（`consolePrepareGeneration` + `acceptsDelivery`）、`onEditor`/`onDialectGate` 代际判据、`prepareConsoleForTest` 测试缝（`SqlPreviewPrepareCallbackLifecycleTest` 依赖）、`detachConsole` 的洞察文档解绑。另：菜单断言 18→19 项、两处文档并集。**编译 3m47s 通过**，三个定向测试绿。
2. **走查准备单**（`2e7b934e`）：七项环境检查 + 走查顺序 + 记录模板 + 三条红线。关键环境事实：许可 agent 是 `C:/Users/USER/.7t/patch/qwdn-afb.jar`（**`.qwdn/` 那条不存在**）。
3. **GUI 走查结果**（`4a3bf7b5`）：六项里 **4.4/4.6 通过**、4.5/4.7/4.8/4.9 部分。缺陷 **UI-001（中）**＝洞察面板操作行 FlowLayout 换行后第二行被状态文案压住，4.5 四个按钮与 4.8 第三项勾选不可见不可点（robot 有 bounds、合成点击无效）。UI-002 判为基线差异（insight 缺 dev 的设置收敛批，非缺陷）。
4. **UI-001 修复**：根因是 FlowLayout 的 `preferredSize` 按单行估算 + 宽度依赖布局结果形成循环。修法＝新增 `WrapFlowPanel`（internal），父容器 `doLayout` 注入 `preferredWidthHint`，按真实宽度累计行高；守卫 `InsightDetailPanelLayoutTest` 4 例（窄多行/宽单行/宽度未知不抛/注入宽度），**反向验证 RED PROBE → 红 → 还原 → 绿**。
5. **补 dev 14 笔**（零冲突，31 文件 +2164/−101）：UI-002 随之消失。
6. **验收册订正**（`c4c28789`）：§一 状态表与 20261031 走查结果矛盾，按「历史时点保留、当前状态指向新册」处理（24 轮册 S1-3① 前置）。
7. 九项洞察能力代表类 grep 全部在场（S1-3④ 判据满足）。

**待做**：UI-001 复验（代理在跑）→ 若通过则 insight 具备入主干条件，但仍需用户授权 `master ← insight`（且合前先补 dev 增量）。

## 附：后续三线并行（20261031 深夜）

用户令拆三线：**一致性收口派子代理**、**新能力全部立项主控做**、**#21b 两张图部署派子代理**。

- **留底已推平**：`dev` 双远端零债（origin 17 + github 18 笔）、`t-settings-converge` **首次出现于双远端**、`feature/kotlin-to-java`（T-SyncK2JMaster175 收官后 93 笔）已推平；**只剩 `t-sql-insight` 待复验定稿后推**（当时落后 origin 149 笔）。
- **新能力立项完成**：见 [[project-s2-new-capabilities]]（五券 + 九条待验证前提；核实推翻「判定可直接复用」「有批量渲染 API」两处前提）。
- **一致性收口五项**（24 轮 S1-4 剩余）派代理在 `t-consistency-sweep` 做：双导出入口文案区分 / 脱敏三开关关系写架构 / 静默失败收口（`LogEntryFiltering.kt:323` vs `HistorySqlLogPanel.kt:764` 行为统一）/ 死代码处置（`ComplexityScanner` 387 行、`SqlTableExtractor` 35 行）/ 原生注入链明示（`ProjectTypeDetector.kt:122`）。
- **docs 站部署**派代理：docs 仓待推 1 笔（`a9cc730` 两图）+ `847823e` 矩阵页；前置已核（`.cf-token` 54B、`deploy` 脚本含 build+pagefind+wrangler）；**部署验收必须看线上四条读数**（退出码 0 不代表成功，见 [[reference-docs-deploy-cloudflare-token]]）。
- **并发纪律实测**：GUI 复验（runIde）占 1 路 gradle + GUI，一致性收口占第 2 路 → **构建已到 2 路上限**，新能力的代码实施（含 S1-6 改 description 要跑守卫）必须等其中一个收工。

## 附三：一致性收口 / UI-001 复验 / docs 部署（跨夜收尾）

**S1-4 五项全部完成并合入 dev `f66b83b4`**（`t-consistency-sweep`，无冲突）：
- ②双导出入口文案加「提工单用 / 本机自查用」双标识，parity 5 项绿
- ③三开关关系表写进 `子系统-展示脱敏链.md`，**每行依据逐处代码核实**（屏显 3 + 出口 6 + agent 2 共 11 个消费点带 文件:行）
- ④新增 `log/ExportFeedback.kt`（统一平台通知、零模态框）+ 非法正则单一真源 `invalidRegexQuery()`（`LogEntryFiltering` 定义、`HistorySqlLogPanel` 改用它、日志页新增 `updateSearchErrorFeedback()` 由 `SqlLogPanelRefresh` 末尾调用）；**反向验证两组各 1 红后复绿**
- ⑤删 `sql/ComplexityScorer.kt` 387 行 + `log/SqlTableExtractor.kt` 35 行（grep 零调用双证含 `Class.forName`/字符串路径），连单测与 `dev-support/test-shard-weights.tsv` 两行一起清
- ⑥**前提被推翻**：24 轮册说「非 Spring Boot 注入链未实现」——实际**早已实现**（`log/MyBatisNativeWiring.kt`，R24/`eec85249`：`mybatis-config.xml` 的 `<plugins>` + 薄包装插件），`ProjectTypeDetector.kt:122` 那句是**过期注释**。处理改为订正注释 + 设置页加边界说明行（双语键 `settings.log.autoInjectInterceptor.boundary`，登记进 `SettingsPanelSearchableGuardTest.DISCLOSURE_KEYS`）。**这是「审计报告说没做、实际做了」的又一例，立项/实施前必须核**。

**UI-001 复验通过**（insight 分支）：4.5 四按钮屏幕可见且**逐个点出反应**（扫描→`SqlUsageCoverage(current file,1,0)`+`DYNAMIC_MAPPER_SKIPPED×4`；反查→`DATABASE_IDENTITY_UNKNOWN`；取消→「已取消分析」；关闭→面板关闭回 SQL 页）；4.8 第三项勾选可见可勾（像素 diff 23×23）；**UI-002 随之消失**（gutter 分组变「[全部]」单一摘要控件 + 浮层 6 项 + 全选/全不选）；环境体检真机通过（产出 `sql-visualizer-环境体检.txt` 五项事实）。

**复验订正了一个验收口径（`a4803346`）**：20261022 手册与 20261024 验收册都写「三勾齐备才可批准」，实测**批准是复选框、任何时刻可勾**，「执行已批准比较」按钮未勾/全勾两态**像素完全一致**（`diff bbox None`）、代码里**无任何 `setEnabled`**——门禁在 `ComparisonUiPolicy.safety` + consent 的**执行时**校验。原表述会让走查者把「未勾复选框」误读成「置灰的批准按钮」而误报缺陷，两处已改。

**docs 站部署完成**：`847823e` + `a9cc730` 已推（落后 0），deployment **`eabe8633`**（主域 `sql-visualizer.jvtools.cn` 已返回新内容）。四条线上验证：中英矩阵页 200 且正文命中关键词；两张新图**字节数与本地逐字一致**（556,026 / 911,381，旧图 475,905 / 601,059 已换）；落点页 HTML 实文含对应 alt；pagefind 50 页且新矩阵页**按 fragment URL 级证据**可检索（不是靠页数推断）。

**「方言与数据源不匹配」的实测机制**（用户追问「影响使用吗、能切源吗」→ 见 [[reference-dialect-mismatch-execution-gate]]）：只影响执行、不影响预览/复制；红条只在 `runners=0` 时出现；**方言不匹配的源在 O-136 之后会展开进弹窗可选**；「切换数据源点了就报错」是 W8-hotfix 已修的旧问题（错签名三参 vs 四参）。这次复验遇到的红条是**环境**：新沙盒 `MPSV-Ui001Recheck` 的 `config/options/` 只有 `updates.xml`、**没有 dataSources.xml**，平台枚举不到 MySQL Runner 属预期。**顺带一个待改的体验点**：红条主路径让用户去改「数据源 Dialects 标注」而非「换源」，对普通用户反直觉，且改错后报错更难懂——建议主次调换。

## 附：S2 新能力批四券（20261101 凌晨完成，均合入 dev）

**NEW-26 Inspection**（`faf49ce8`，最大一件）：五条规则全落地（`${}` 注入 / `#{}` 参数缺失 / resultMap 属性 / 方言引号 / OGNL 恒真）+ 两类 QuickFix（`${}`→`#{}`、跳 mapper 方法）。形态＝照抄 `BannedApiInspection` 薄壳：inspection 只取 PSI 文本，判定在纯逻辑层（`SqlInspectionText`）。P-1~P-4 全证伪且有取证用例：
- **P-1 `<include>` 在 PSI 文本里不展开**（`XmlTag.text` 只有 `<include/>`，`XmlMapperParser` DOM 链才展开）→ 扫描面按源文件字面设计；
- **P-3 不调 `mapperCheck`**（它是挂起函数 + audit 包装，daemon 不该走）；
- **P-4 语料误报 0**，且转录 X01 时抓到真实命中（节选丢 bind 导致 `chargeDegreeSum` unknown property）**未当误报掩盖**，单列用例钉住。
- **平台坑（重要）**：`<localInspection language="any">` 会让整条检查**被静默跳过**——平台把 "any" 当语言 ID 去 `Language.findLanguageByID("any")` 得 null，按语言分桶时整条丢掉；表象是「EP 内建实例能跑、`myFixture.enableInspections` 跑不出来」。**必须写空串**（全语言）。
- 边界：Kotlin 注解 SQL 不覆盖（K2 的 `KtAnnotationEntry` 需 Kotlin 插件类型，optional 依赖强引用会炸类加载）；resultMap 判 `property` 非 `column`（列名需 DB 元数据）。

**NEW-21 信创体检**（`edc1d83f`）：`MultiDialectRenderPlan` 508 行纯逻辑（`SqlGenerator` 零改动），14 方言渲染 **1.4ms**；P-5 证伪（不需工程上下文）、P-6 部分成立（隐式转换只落「列类型 vs 非数值字面量」子集，跨库行为相反）。四档差异各配正反例 + 逐条反向验证。

**NEW-23 MCP 写路径**（`258e391e`）：给 `sqlv_check_sql` 加 `chain`/`primaryKeyColumns`/`dialect` 三个**可选参数**（`chain=false` 时输出逐字节不变并有用例钉住——给既有工具加参数的正确姿势）；工具数 11→11；闸门反向验证 3 红后复绿；执行环不新增通道，交 IDE 确认流。

**首用即生效**（`9a12344d`）：**P-7 查到 `SqlDialectMappingPrewarm` 早已存在**（三档 API），缺的只是「项目打开」触发点——只补一行 `prewarm(project)`，不重造、不调 `prewarmAndAwait`、**故意不做索引强预热**（mapper 发现走平台 `FileTypeIndex`，主动预热只有拖慢启动或重复平台工作两条路）。P-8 用平台 EP 源码（243 `PlatformExtensionPoints.xml` 的 `dynamic="true"`）证明 statusBarWidgetFactory 不在 database 插件面 → Community 可用。

**S1-6 描述文案**（dev 工作树未提交，等用户看观感）：只改中英两行摘要，正文 20 条不动；首句 `14 database dialects, EXPLAIN insight, rollback plans.`；预览件 `build/desc-preview.{html,png}`。

## 附：白条修复（`91cc4c6d`）——推翻了先例的机制解释

用户报「SQL 预览窗口白条」。修复代理全仓扫自建原生组件，改了 6 处 `JScrollPane` → `JBScrollPane` + 新增 `NativeSwingThemeGuardTest`（**只认构造点、不认 `is`/`as?`**——平台给的内嵌控制台组件不是白条来源，算进来会导致误报进而逼人加白名单废掉守卫）。

**它推翻了先例（含我任务书里抄的说法）的机制解释**：
- ❌ 旧说法「原生滚动条走 Metal `BasicScrollBarUI` 不跟随 Darcula」——实测 Darcula 注册了 `ScrollBar.UI=JBScrollBar`，原生滚动条本来就用 `DefaultScrollBarUI`、边框也是 `DarculaScrollPaneBorder`；
- ✅ 真实差异在 **viewport 与四角**：原生是裸 `JViewport`、`getCorner(...)==null`；`JBScrollPane` 是 `JBViewport` + 四角 `Corner`；
- ✅ `JSplitPane` 是硬证据：Darcula **只给 `os.mac`** 注册 `SplitPane.UI`，Windows/Linux 仍是 `MetalSplitPaneUI`（`SplitPane.shadow=#B8CFE5`、`dividerSize=10` 全是 Metal 值）。

**但它明确说用户报的位置复现不出来**——SQL 预览窗口链路已全是平台组件，于是给出 5 步定位清单交复验代理，而不是含糊宣称已修好。这是正确姿势：改了 6 处真问题 + 诚实登记「你报的那处我没复现」。

## 附：并行登记 ≠ 在构建（用户纠正，已进 AGENTS.md）

`parallel_tasks.py` 的登记是「意图声明」（防沙盒目录冲突），**不代表真有人在跑 Gradle**。实测常见形态是「3 条登记、1~2 路真在跑」。判据改成看实际 gradle 进程（`Get-Process java` 里 CPU 增长且内存上 GB 的）；**定向测试两路并行可接受，只有全量 `testShards` 必须单路**。已写进仓库 AGENTS.md（`bf7269b2`），因为那是所有会话都会读到的入口。
