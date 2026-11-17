# T-SyncK2JMaster179 主分支功能同步 开发计划

> 分支：`feature/kotlin-to-java`（worktree `mybatis-plus-sql-visualizer-kotlin-to-java`）
> 约束：**同步功能，绝不合并分支**——master 的 .kt 一行不进 k2j 生产树（生产 100% Java）。
> 作业法：`.agents/skills/k2j-port-sync`。

## §〇 立项背景（分叉订正，必读）

原 `20261117-…T-SyncK2JMaster178` 计划把整段 `95e107b1..906039f29`（523 提交）当作未同步增量，
但 `origin/feature/kotlin-to-java` 已于 2026-11-14 独立完成 178 轮（计划 `20261113-0007-…`），
把 `95e107b1..183c98b3d`（314 提交）全量移植并全绿收官（锚点已推进到 `183c98b3d`）。
本地那条「178」是与 origin 分叉的旧支线（efd3f9e0a 起，49 笔、未完成），已整体备份到
`backup/k2j-178-local-stale-20261117`，本地分支已硬重置到 `origin/feature/kotlin-to-java`（`1b46c290a`）。
**本轮只同步真正新增的 `183c98b3d..906039f29`（209 提交）**，不重复 origin 已完成的工作。

## §一 范围冻结（开工实测）

- 锚点：`.port-sync` `synced_master = 183c98b3d`（origin 178 收官，2026-11-14）。
- 冻结目标：master `906039f29`（原计划冻结 tip）。**master 现已在 `a4ad7a5bc`（2026.11.17-beta.1），
  多出的 16 提交（多为发行/文档，含 T-IbatisAtShapeGate 修复——另一条活跃任务车道）本轮不追，留下轮**。
- 增量规模：`git rev-list --count 183c98b3d..906039f29` = **209 提交**；
  `git diff --name-status` = 257 文件（121 A + 136 M，零 D/零 R）。
- 开工基线：`compileJava + compileTestKotlin` **BUILD SUCCESSFUL / 0 错**（fresh base，见 §五）。
- 按目录分布：
  - `src/main/kotlin`：58 件（14 A 新类 + 44 M 增量翻译）。
  - `src/test`：57 件（24 A 新测试 + 33 M 增量合并）。
  - `src/main/resources`：3 件（plugin.xml + bundle 双语册）。
  - 构建链：`build.gradle.kts`、`buildSrc/mpv-testing.gradle.kts`、`.github/workflows/build-plugin.yml`、`gradle.properties`。
  - agent 子项目：**无变更**（本轮零增量）。
  - docs / dev-support / record / .agents：大量文档件，直取 + 台账三方合并。

### 功能主题（按提交归组）

1. **索引化与首开提速**（T-IndexScanSweep2 P1 / T-MechanismSweep / T-GutterFirstOpenDelay F1~F2 / T-IbatisGutterPerf P0~P5）：`MapperNamespaceIndex`、`XmlSymbolNameIndexCache`、`SqlMapNamespaceIndex` 三新索引件；`MapperXmlLocator`/`WrapperLocator`/`GutterWarmScheduler` 增量；补全候选名清单项目级服务一次构建；mapper 定位全面索引化删旧扫描回退。
2. **工作区自愈**（T-WorkspaceHeal）：`WorkspaceJsonPropertyRepair`、`WorkspacePropertiesRepairActivity` 两新件。
3. **execute 面**：`DocWriteContext`/`DataSourceSelector`/`DataSourceSelectorResults` 增量、`ExecPsiFailureHint` 形状门、`ExecutionScriptScope`、`NewDataSourceEntry`。
4. **onboarding/方言**：`DialectGapTip` 新件（方言死路提示）。
5. **预览装配/控制台**：`PreviewAssemblyWarm`、`PreviewConsoleEager` 两新件 + `SqlPreviewPanel*` 族增量（T-ParamNameTruncation 标签列宽、T-PreviewOpenPerfM2）。
6. **本地更新通道**：`HotUpdateSettingsGuard` 新件 + `StartupUpdateCheck`/`UpdateSection` 增量。
7. **psi/xml**：`TableNameAnnotations`、`ActionReplaceSilently`、`SqlMapNamespaceIndex` 新件 + `EntityClassFinder`/`WrapperLocator`/`XmlCrossNsFragmentResolver` 增量（T-BindStaticRefCrossNs 跨命名空间 bind 静态常量）。
8. **inlay 让位**：`NativeColumnInlayYieldGate` 新件（原生 SQL 列名 inlay 让位）。
9. **log 面**：`MapperNamespaceIndex` 索引化、`MapperXmlLocator`/`SqlLogManager`/`SqlLogPanel`/`FirstCaptureGuide` 增量。
10. **测试/资源/构建链/CI/文档**：如上分布。

### 排除项（明示）

- `update/update.json` / `update/preview.update.json`：AGENTS.md 规则 7，只有用户本人能改。本轮不动。
- 版本号 `gradle.properties`：随构建链直取（C 波版本面直取）。
- master `906039f29..a4ad7a5bc` 的 16 提交：本轮冻结不追。

## §二 写集划分与波次（主控 solo，串行批次，批内逐件提交）

| 波次 | 写集 | 形态 |
|---|---|---|
| W0 | 本计划立项笔；`.port-sync` 不动（收官才推锚） | 提交 |
| W1 | 生产翻译 A：索引化三新件 + gutter/nav/ref 增量（T-IbatisGutterPerf/IndexScanSweep2/MechanismSweep） | master → Java 手工翻译，逐件提交 |
| W2 | 生产翻译 B：工作区自愈两新件 + execute 面增量 + onboarding DialectGapTip + update 通道 | 逐件提交 |
| W3 | 生产翻译 C：预览装配/控制台两新件 + SqlPreviewPanel 族增量 + psi/xml 新件增量 | 逐件提交 |
| W4 | 生产翻译 D：inlay 让位 + log 面 + model/config/util 余量增量 | 逐件提交 |
| W5 | 中央编译收口：`classes` + `testClasses` + `compileTestKotlin --rerun-tasks` 三目标绿 | 修红提交 |
| W6 | 测试移植：24 A（.kt 直取+签名适配）+ 33 M（增量三方合并） | 分族提交 |
| W7 | 资源与共享面：plugin.xml（新 EP/registryKey 按 k2j 形态插）、bundle 双册键集 union、构建链、CI 按 k2j 分支形态 hunk 移植、docs 直取 + 台账两册三方合并 | 直取 + 选择性合并 |
| W8 | 推送 → CI（push-test → gate）→ 按 CI 读数修红 | CI 车道 |
| W9 | 收官：`.port-sync` 锚点 `183c98b3d→906039f29`、计划 §八回填、台账双行、工作树复核 | — |

## §三 门禁标准

1. **本地 sanity（W5/W6 收口，非门禁）**：`classes` / `testClasses` / `compileTestKotlin --rerun-tasks` 三目标 BUILD SUCCESSFUL（判绿双证：SUCCESSFUL 行 + `echo EXIT=${PIPESTATUS[0]}`）。
2. **CI 门禁（权威）**：push-test 7 腿全绿；gate（build + test×5 + verifyplugin + verify×3〔243/253/262〕+ marketplace + 聚合）全 success。
3. 跑本地 gradle 前 `python parallel_tasks.py check/register/done`（禁裸 done）。
4. testDatabase（Docker 腿）不本地跑；CI 若带 DB 腿按其读数。

## §四 待验证前提（实施者先证伪再动手）

1. **前提**：origin 178 已完成 `95e107b1..183c98b3d`，本轮范围才从 `183c98b3d` 起——已 `git rev-parse .port-sync` 与 fresh 编译双证。
2. **前提**：14 个 master 新类在 k2j 均无同款（已 find 实证全 MISSING），需新建 Java 翻译件。
3. **前提**：44 个 M 件在 k2j 有对应 Java 承载（13 个是 `XxxKt` 门面命名差异），翻译前逐件 `git show <tip>:<path>` 读 master 终态，禁用主仓工作树。
4. **前提**：test A 件直取后需按 k2j Java API 适配（具名实参/默认参/object→INSTANCE/嵌套类），编译期暴露逐个修。
5. **前提**：CI workflow 是 k2j 分支专属形态（verify 243/253/262、无 263、marketplace 聚合 job），master 的 CI 改动按 hunk 移植不整件覆盖。
6. **前提**：bundle 双册键集 union——k2j 若有专属键须保留，先比对再合。

## §五 已知坑预案（沿用历轮沉淀）

1. API 面改动必须 `compileTestKotlin --rerun-tasks` 验真（UP-TO-DATE 增量态掩盖 CI 红）。
2. 提交信息一律 UTF-8 文件 `git commit -F`（反引号陷阱）；pathspec 限定提交（共享工作树 index）。
3. Kotlin→Java 互操作：具名实参→位置、object→INSTANCE、顶层函数→`XxxKt` 门面、record 访问器无 get 前缀、函数类型字段落 `Function0/1`、默认参→双重载、when→switch 表达式 yield。
4. 守卫基线三处联动以实测读数登记，禁行计数预判；新守卫类登记 `GuardSelfTestCoverageMetaGuardTest`；守卫扫描根在 k2j 为 `src/main/java`+`.java`。
5. 直取后全量对账 `git diff 906039f29 --name-only` 逐件判向，抓回被直取误冲的 k2j 专属行。
6. 中文错误乱码 `| iconv -f GBK -t UTF-8`；判绿带 `echo EXIT=${PIPESTATUS[0]}`。
7. **严禁把 master .kt 整件搬进 k2j 生产树**（本轮原始 stale 支线曾因此产生大量翻译缺口，已备份废弃）。

## §六 结构性决策保护清单

1. k2j 生产 100% Java——master .kt 只翻译不搬运。
2. k2j CI workflow 分支专属形态，按 hunk 移植不整件覆盖。
3. `.port-sync` 只在收官笔推进。
4. update 双册不动。
5. k2j 测试面 Kotlin 承载合法，不把存量 .kt 测试翻译成 Java。
6. origin 178 已移植的 `95e107b1..183c98b3d` 属既成事实，本轮不重做、不回退。

## §七 风险与回退

- 单件翻译卡死 >30 分钟：记录证据跳过，先保批次推进，收口统一回补。
- 与另一条活跃任务 `T-IbatisAtShapeGate`（master `164e6a884`）的冲突：本轮冻结在 `906039f29`，不纳该修复，留下轮。
- 本地旧 178 支线备份在 `backup/k2j-178-local-stale-20261117`，如需考古从中取，不合并。

## §八 实施记录（收官）

### 8.1 范围与结论

- 冻结目标 `906039f29`（master tip 现为 `a4ad7a5bc`，多出 16 提交含 T-IbatisAtShapeGate——另一活跃任务车道，本轮不追）。
- 真实增量 `183c98b3d..906039f29`：209 提交 / 257 文件（121 A + 136 M）。
- 提交链 **38 笔**：`6ced8d044`（W0 立项）→ `9fce1f920`（MCP 诊断脚手架清理），双仓已 push。
- 本地旧 178 支线备份在 `backup/k2j-178-local-stale-20261117`（49 笔、未完成），本地分支重置到 origin 178 收官版（`1b46c290a`）。

### 8.2 各波次产出

| 波次 | 内容 | 关键提交 |
|---|---|---|
| W0 | 立项（本文件） | `6ced8d044` |
| W1 | 生产树落地（会话起点自洽翻译，compileJava 绿）+ 删 9 件误加 .kt 孪生件（保 origin .java 承载）+ C 波 docs/dev-support/record 直取 + 台账两册三方合并 | `b029f936f` `c64bb82f2` `045ceb213` `30d4a63aa` |
| W1b/W2 | A/B 波测试编译适配：厚批量具名实参位置化、静态导入、密封变体扁平化；生产 internal helpers 回填 6 波（38 符号：paramLabel/MethodLookup/MethodLookup/EntityClassFinder/DialectPreMapping/XmlPreview 等） | `f543a6a83` `dd325b0f4` `3cdbe5bbb` `acfe45a63` `0d48db9f0` |
| W3/W4 | 生产行为回填：判型依据/方言备忘/gutter 三态/横幅与 iBatis 回填/日志静默冲刷/MBG/EntityClassFinder/EDT 写投递 | `d5e9afa8c` `94953cb45` `110e80c8f` `f7f9b549e` `36efae970` `cfda1c880` |
| CI 修红 | push-test 首轮 93 失败/42 类 → 4 代理并行修红至 4 → 二轮 1（EntityClassFinder 缓存失效 + gutter seam 漏）；MCP 单条 flake 多轮接缝自清/索引 ensureUpToDate/waitForSmartMode（EDT 挂死回退） | `a783c569c` `04bdd638f` `22925d496` `0b73c5406` `0f50a55e1`；根治 `4d523ace7`（测试缝注入锚点免疫索引时序，CI run 44077846390 test(3) 转绿） |

### 8.3 门禁读数

- **本地 sanity**：`compileJava` + `compileTestKotlin --rerun-tasks` **BUILD SUCCESSFUL / 0 错**；45 个曾在 CI 红的测试类本地全绿。
- **CI push-test**（`4d523ace7` 起）：`fast(1/2)`、`test(1/2/3/4)` 均 success，`nightly-nudge` skipped；长期卡住的 `test(3)` 已转绿。
- **`test(5)` 曾被误记为「历轮稳定绿」（20261117 订正）**：实测跑完的四个轮次（`44024907874` / `44037321840` / `44069616993` / `44082606101`）里 `test(5)` **次次 31m14s~31m24s 撞 30 分钟步骤超时**（`##[error]The action '分片 5' has timed out after 30 minutes`），日志零输出；同轮 `test(1~4)` 只需 6~9 分钟，`test(4)` 的 `:testShard4` 实测仅 **92 秒**。基线绿轮 `42904843372`（`efd3f9e0a`，2026-11-10）的 `test(5)` 是 `7m24s` 绿 ⇒ 属本轮引入。已加 `if: always()` 分片进度诊断步骤（结果 XML 清单 + 首末完成时刻），以便区分「某用例挂死」与「用例全跑完但 JVM 不退出」。
- **CI flake 已根治**：`testShard3` 的 `McpSourceLocatorRenderAtLineTest::testiBatis_Java调用行` 早前 CI-only 偶发 `SOURCE_NOT_FOUND`。根因＝长分片下写 sqlMap 触发 dumb mode，`SqlMapNamespaceIndex` 查询抛 `IndexNotReadyException` → 定位返回空锚（生产链与 master 冻结 tip 逐字一致，是测试对索引异步可见性的环境时序依赖；索引正确性另有 `SqlMapNamespaceIndexTest` 专测）。根治＝`addSqlMap()` 用该链自带 `lookupOverrideForTest` 缝从刚建 PSI 直接提供 id 锚点（按 `namespace.id` 取裸 id 匹配），令测试对 dumb/索引时序完全免疫，`tearDown` 复位不污染同 JVM 后续用例。多轮无效尝试（接缝自清/EDT-pump 等 dumb/索引 ensureUpToDate）中 `waitForSmartMode` 曾在 EDT 挂死 17+ 分钟已回退。
- **gate**：push-test 全绿读数确认后派发。

### 8.4 事故与坑沉淀

1. **本地旧 178 支线与 origin 178 分叉**（同任务号两条线）：本地 49 笔未完成，origin 207 笔全绿收官。处置=备份本地支线 + 重置到 origin，只同步其后真实新增部分，避免重复劳动与强推覆盖。
2. **会话起点生产树是自洽翻译，后续会话中编辑引入不一致**：W1 直接采用会话起点生产树（compileJava 绿），而非从 stale 支线末态取（已坏）。
3. **`.kt`/`.java` 孪生件 Redeclaration**：W1b 从 stale 支线带回 9 件 .kt 测试，与 origin 的 .java 承载同名 → 删 .kt 保 .java（k2j-port-sync 结构铁律）。
4. **守卫源码扫描路径 `.kt→.java`**：SettingsRealityGuard/BannedApiScan/EnumReachability/GuardSelfTestCoverage/EditorCommentShortcuts 等约 10 个守卫测试扫的是 master 的 `src/main/kotlin/**/*.kt`，k2j 生产 100% Java；逐处改为扫 `.java` 等价路径/声明形态，被守卫语义逐条保持。
5. **Kotlin 顶层函数/object/密封类 → Java 门面/INSTANCE/枚举常量** 与具名实参→位置参是本次编译适配的两大主形态；`copy()` 在 Java record 上不存在，须用全字段构造器/with* 方法。
6. **跨测试静态缝泄漏**：`IbatisXmlStatementTargets.lookupOverrideForTest` 等 object 级全局缝被前序用例置 Absent/Unknown 后未复位，污染同 JVM 后续用例；在 setUp/tearDown 自清（顺序无关）。
7. **`waitForSmartMode()` 在 EDT 测试线程挂死**：CI job 17+ 分钟无进展，EDT-pump 等 dumb 也无效（dumb 是测试体写文件后才触发）；最终改用测试缝注入锚点、彻底消除对 dumb/索引时序的依赖（对抗时序不如消除时序）。
8. **写 CI-only flake 诊断断言时不要直接调索引查询**：dumb 模式下抛 `IndexNotReadyException`，反把测试红因盖住；诊断信息须在无索引调用前提下采集。

### 8.5 `test(5)` 超时追查与 5 处漏移植补齐（20261117 续轮）

**方法**：CI 冷树无 `build/test-results` 样本 ⇒ 权重只吃入库画像 `dev-support/test-shard-weights.tsv`、缺类取已知中位数，无 S3 拆分/无 handicap，**分片分配是确定性的**。按 `buildSrc/src/main/kotlin/mpv-testing.gradle.kts` 的贪心算法离线复算（`build/shard_replay.py`），打印的 `loads=` 行与 CI 日志**逐字一致**（当轮 `shard1=175.9s/158classes … shard5=175.9s/160classes`，基线轮 `171.9s/143,152,144,145,144` 亦一致），据此拿到 shard5 的 160 个类清单。

**关键发现：`test(5)` 超时掩盖了 15 个潜伏失败**。本地跑 shard5（`-PtestShardIgnoreLocalWeights=true -PtestSplitHeavy=false` 复现同一分片，1900 用例）得 **15 failed / 5 个类**，全部可单独复现（非顺序依赖）：

| 类 | 失败 | 根因（Kotlin 有、Java 端口无） | 修复 |
|---|---|---|---|
| `ui.inject.SqlMapperIbatisDelimiterTest` | 10 | lexer 缺 iBatis 单字符定界符四态（`scanIbatisDelimiter`/`findIbatisParamClose`，master `ac9181463`），`IBATIS_ESCAPE` 只声明不产出 | `69a132e56` |
| `psi.XmlCrossNsFragmentResolverTest` | 2 | `XmlPreviewService.preview()` 只把 `fileText` 送静态常量扫描，缺 `Collection<String>` 多文本入口 ⇒ 跨 ns include 片段里的 `<bind value="@Cls@FIELD"/>` 从不进 `staticFieldValues` | `e145e7f79` |
| `ui.XmlSqlCompletionContextResolverTest` | 1 | 测试移植漏了 master 的 `setUp()` 隔离（候选实体是项目级缓存，同类用例互相污染）；生产侧与 master 一致 | `e145e7f79` |
| `ui.PagesXmlHqlPreviewTest` | 1 | 端口自创 `probe(cheap=false)` 全扫形态，`<pages>` 判定链接了它（实体解析 + 根标识符各扫一次）；Kotlin 只有 `probeCheap` | `8f2668c8a` |
| `config.PluginSettingsPanelBindingTest` | 1 | 面板 `isModified()`/`apply()` 漏了 `localDirField`（本地安装包目录）子句，改目录不被视为变化 | `33ad7ac6e` |

**订正**：8.3 原写「45 个曾在 CI 红的测试类本地全绿」不足以证明全绿——那些类来自 CI 失败清单，而 shard5 从未跑完，其 15 个失败从未进入该清单。验证覆盖面必须按分片齐套，而不是只补 CI 报出来的类。

**本地复跑**：修复后 `:testShard5` **BUILD SUCCESSFUL，160 类 0 失败，3m06s**。

**坑（第 9 条）**：**「任务超时」会把该分片的失败一并吞掉**，红读数只剩超时，且日志零输出；查这类问题先用「权重画像 → 离线复算分片」拿类清单，再本地整片跑，别在 CI 上盲试。
