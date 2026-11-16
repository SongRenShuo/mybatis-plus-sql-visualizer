# T-PerfSweep31：合流前性能余额与 iBatis 别名表收口——开发计划

> 立项依据：《20261117-0021-IDEA性能影响-性能分析》（第八期，基线 master `906039f29`）。本计划承接该报告 §八优化建议，并处理第七期移交的 master 侧余额。

## 一、目标与非目标

**目标**

1. 券01：消灭 IbatisTypeAliasResolver 的模块级 VFS 全走查（第八期唯一新发现 P2）。
2. 券02：master 侧 DumbMode 防护面四处余额收口（主路径=合流继承 dev，发行先行才有 master 小刀）。
3. 券03：上期预览首开 P1 账本 master 余额（主路径=合流继承 dev 券01，随批补不变式钉）。
4. 券04：iBatis 静态缓存族六处卫生（收口或登记）。
5. 券05：观测与登记收口（索引 bump 说明、`#config` 头窗化、HqlGutter 残留）。
6. T0：真机读数四项随批移交（不阻塞）。

**非目标**

- 不重做 dev 已收口的 T-PerfSweep30 实现（合流继承为主，见券02/03 分支预案）。
- 不动 `update/update.json`（用户专属）；不碰合流本身（T-BranchAlignMerge 另有计划，本计划只定义「合流后门禁」）。
- 不做两索引合并评估（第八期已否：独立 bump 各自失效面解耦，代价只是索引期一遍文本扫描）。
- 不引入任何新周期任务（「新增常驻 0」在 master 口径维持；S2 批合流带入的 widget/巡检已登记，不在本计划处理）。

## 二、券拆解

### 券01 IbatisTypeAliasResolver：typeAlias 进标量索引（P2 主菜，master 独立可做）

**现状**（第八期 §4.3）：iBatis 预览点击链 `IbatisParameterClassResolver.findClass` → `IbatisTypeAliasResolver.fqnOf` 首次调用对模块内容根 `VfsUtilCore.iterativeChildrenRecursively` 全走查 + 逐 XML `PsiManager.findFile`，跑在 `previewAsync` 的 `ReadAction.compute`（池线程持读锁）内；会话级缓存、不可取消、无预算。

**主刀（方案 A）**：新增标量索引 `SqlMapConfigAliasIndex`（或并入 `SqlMapNamespaceIndex` 键族，倾向**新开**——对齐 MapperNamespaceIndex/SqlMapNamespaceIndex 各自独立 bump 的既有取舍）：

- 键：`ta:<alias>`（语句文件无关，文件级标记键 `cfg` 可选带出「本项目有哪些 sqlMapConfig 文件」）；值 Void；`getContainingFiles` 出候选文件后 PSI 侧精确匹配（根标签 `sqlMapConfig` + `<typeAlias alias>` 精确相等）——正确性闸门在 PSI 侧，照两索引先例。
- 提取内核纯函数、XmlFileType 专属 filter、`dependsOnFileContent=true`、头窗 + 全文 `<typeAlias` 扫描（sqlMapConfig 文件通常 KB 级，全文可接受）。
- `IbatisTypeAliasResolver.fqnOf` 改为：索引查候选文件（1 次查询）→ 逐文件 PSI 精确匹配 → 组 FQN。会话级缓存与全走查一并退役（索引失效交平台，顺带修掉「config 改动要重启会话」的 v1 已知限制，KDoc 同步改）。
- 键域有界（别名数），版本 bump=1（新增索引装载即建，登记为升级一次性成本）。

**次选（方案 B，仅当方案 A 的索引期成本被证伪否掉）**：保留扫描但改 `ReadAction.nonBlocking + inSmartMode + expireWith + 预算（首扫上限 N 文件/超预算降级短名索引回落）`，禁同步 `computeIfAbsent` 留在点击链。

**验收**

- 大模块夹具（≥500 XML，其中 1 个 sqlMapConfig）上：改造前首次 `fqnOf` 触发全走查（探针计数/日志），改造后零 VFS 递归（`iterateChildrenRecursively` 调用点仅存于索引器或消失）。
- 纯函数内核单测（双引号/属性序/`<typeAlias>` 缺 type 畸形形态少收不误收）；PSI 精确匹配闸门用例（索引多收由 PSI 否掉）。
- 反向验证：临时移除索引查询分支（回落全走查），夹具探针必须变红，贴两次读数。
- 定向测试纪律：只跑 `IbatisTypeAliasResolver*` / `IbatisParameterClassResolver*` / 两索引既有测试类 + `testFast`；不跑全量、不 `clean`、不 `--stop`（照派活模板）。

### 券02 master 侧 DumbMode 余额四处（P2，节奏跟随合流）

**主路径（默认）**：T-BranchAlignMerge 完成后，dev 的 `b5180c73e`/`9845b9b0c`/`578abb0df`/`ead67d725`(券02d) 自动进 master——本券改为**继承验证**：

- 逐处确认合并态保留（resolveClass 闸在入口、resolveInterfaceMethod 有闸、psi 两文件入口闸、previewAsync runWhenSmart 重试）；
- dev 补的 dumb 夹具测试进 master 基线后跑绿；
- 反向验证抽一处（撤 EntityClassResolver 闸必红）即可，不重复实现。

**发行先行预案（仅当发行批赶在合流前）**：master 侧独立小刀，四处等价修复（MybatisXmlTargets.kt:144 闸前移 / :191 加闸 / EntityClassResolver / EntityClassFinder 入口闸）+ previewAsync runWhenSmart 重试一次＋仍失败给可见归因；每处独立提交。

### 券03 上期 P1 账本 master 余额（P1 遗留，节奏跟随合流）

主路径=合流继承 dev `ead67d725`（cache-only 预热 / 打点只首嵌 / 装配与 exec 写次数不变式钉 / DumbMode 重试已并入券02d）；随批把两个不变式钉（「单次打开装配次数」「exec 写次数」）确认进 master 测试基线。发行先行预案=master 侧补同款小刀（照 dev 提交形态移植，不另设计）。

### 券04 iBatis 静态缓存族卫生（P3）

六处（第八期 §4.2/§六）逐个处置：

- `resolvedIbatisKeys` / `resolvedIbatisCallKeys` / `optimisticLoggedKeys` / `perfLoggedKeys`：**键加项目身份**（`project.locationHash|namespace|id`）或迁 project-level 容器（挂既有项目服务）；项目关闭时清空对应键（挂 `Disposable` 或 `ProjectManagerListener`，照仓内既有清理形态）——消除跨项目撞键正缓存串号。
- `PreviewAssemblyWarm.warmedFiles` / `usedPerProject`、`GutterWarmScheduler.smartSincePerProject`：量级小，**登记即可**（键含 URL/locationHash，无撞键面）。
- 全程不动线程形态，纯容器归属调整；每处一行测试缝（项目关闭后重查不再命中旧键）。

### 券05 观测与登记收口（P3）

- 发行说明补一行「升级后首启含一次性索引重建（MapperNamespaceIndex v2 / SqlMapConfigAliasIndex 新增）」。
- `MapperNamespaceIndex.keysForText` 的 `#config` 分支 containsMatchIn 收进头窗（可选，纯函数改造 + 既有单测矩阵扩两例）。
- `HqlGutterLineMarkerProvider:89` resolve 残留顺手收（M36.5.79 同批口径：慢趟内 per-element resolve 挪到命中分支之后或缓存）。
- 两索引「全文正则」与「bump 重建」三条登记项回填 docs/MEMORY.md 性能分析索引（第八期报告一并在案）。

### T0 真机读数（随批，不阻塞）

上期四项移交维持：大项目 daemon 墙钟对照、T-SqlDialectPrewarm R1/R2 Slow operations 计数、券01 头部行缓存 JFR 对照、预热预算重采样（≥10 次冷启 P50/P95，据以定 PREWARM_WAIT_MS，禁盲调）——加第 5 项：券01 改造前后「iBatis 大模块首次预览点击」墙钟对照。

## 三、实施顺序与并行车道

- **车道 A（master 独立，可立即开工）**：券01 → 券04 → 券05（券01 与券04/05 触达面不重叠，可并行）。
- **车道 B（跟随 T-BranchAlignMerge）**：券02/券03 继承验证 + 第八期 §七增量走查清单一轮；发行先行时切换预案小刀。
- 构建并发上限 2 路、跑 gradle 前过并行登记表（`parallel_tasks.py check`）；子代理派活按三档分层纪律（定向随报告、全量归收口代理）。

## 四、风险与回滚

- 券01 新增索引：版本 bump 与键面变化属一次性重建（先例两索引在案）；回滚=删索引 EP + `fqnOf` 回退旧实现（旧实现代码保留一个提交周期以便 revert）。
- 券02/03 合流验证路径：无生产码改动，风险面在「合并冲突裁决丢修复」——按第八期 §三矩阵逐条打勾，缺一条即升级为冲突重裁。
- 券04 缓存键改造：注意 `resolvedIbatisCallKeys` 被 `resetCallKeysCacheForTest` 与慢趟双处触达，改键后测试缝同步改。
- 禁把「config 改动需重启会话」的旧行为写回：索引化后失效交平台，属行为收紧（变新鲜），不是回归。

## 五、待验证前提（实施者先证伪再动手）

1. **「iBatis 预览点击在 previewAsync 的 ReadAction.compute 内」**——依据是 IbatisStatementPreview KDoc 自述；实施前用一次真机/沙盒点击采线程栈核实（若实际已是 nonBlocking，券01 的线程归位项降级为纯索引化）。
2. **「sqlMapConfig 文件的 `<typeAlias>` 提取可头窗化」**——sqlMapConfig 惯例在文件头部，但 DTD 无此约束；先跑语料统计（demo 工程 + 信创样本）确认 `<typeAlias>` 距文件头最大偏移，超窗再定全文扫描。
3. **「新开索引优于并入 SqlMapNamespaceIndex」**——两方案对热更新（EP dynamic）、bump 节奏、键域耦合的影响按 `product.jar` 实测口径复核，勿凭记忆。
4. **「dev 券04 的 ShareDialog 导出已移后台」**——第八期只确认 dev 仍有 2 处 saveHtmlReport 调用点、未核线程上下文；合流验证时先取证再写结论。
5. **「project.locationHash 作缓存键成分不跨会话漂移」**——locationHash 会话间可变，作键仅限会话内去重语义；若实施中发现语义不符，改用 `Disposable` 挂项目清理而不是加键。
6. **「previewAsync runWhenSmart 重试不改变既有返回语义」**——dev 已实现并有测试；master 预案移植前先跑 dev 侧该测试类确认口径，勿重新设计。

## 六、实施记录

实施态：**全部券在本分支完成**（分支 `t-perfsweep31`，工作树 `.worktrees/t-perfsweep31`，基线 master `b79b75aea`）。执行口径说明：本分支按「发行先行预案」实施券02/03——合流尚未获得用户授权（AGENTS.md 规则 9），故把 dev 侧已验证的修复**等价移植**到 master 而不是等待合流；移植方式为 cherry-pick + 手工适配，dev 后续合流时相同 patch 会以同 patch-id 去重、冲突面最小。

### 6.1 提交账本

| 提交 | 券 | 内容 |
|---|---|---|
| `aa13cf33c` | 券02 | MybatisXmlTargets dumb 闸前移 + resolveInterfaceMethod 闸（dev `9845b9b0c` 原样移植，干净落地） |
| `0c3474bca` | 券02 | EntityClassResolver/EntityClassFinder dumb 入口闸（dev `578abb0df` 原样移植，干净落地） |
| `76ca4b5db` | 券03 | 预览首开链券01a/01b/01c/02d（dev `ead67d725` 移植；两处冲突手工解决，见 6.2） |
| `5b98f53b0` | 券03余项 | 04a 导出后台化 / 04b 逐键去分配 / 05a 缓存上限化 / 05b 单参 DocumentListener（dev `4b5257877` 移植；04c 与 widget 测试 master 不适用而丢弃，提交信息已订正范围） |
| `2ec54381f` | 券01 | SqlMapConfigAliasIndex 新索引 + IbatisTypeAliasResolver v2 重写 + 内核/集成/守卫三层测试 |
| `d205beb7c` | 券04 | 四处静态缓存键加 project.locationHash 前缀 + 三处登记 KDoc |
| 本提交 | 券05 | HqlGutter 类型直判收口、`#config` 头窗化语料核销（不改码，见 6.3）、CHANGELOG [Unreleased] 五条、计划与索引文档 |

### 6.2 移植适配点（与 dev 原形态的差异，均为 master 侧结构差异所致）

- **券01a cache-only 核心手工移植**：dev 的 `prewarmExecTempFileInner` 本体在 dev 独有文件 ExecTempFileFactory.kt（master 从未有过、该链在 master 的 DataSourceSelectorResults.kt）——cache-only 判等块、两段收敛跳过按 master 函数位置重写；**落点判据按 master 的 T-ExecPsiGate R6 口径适配**（主落点可能被项目根备用落点接管，dev 原版固定 `.idea` 路径在 master 会判错文件）。
- **预热派发冲突解**：master 的 T-PreviewExecWriteDefer 惰性闸（`PreviewConsoleEager.enabled()`）保留，包住 dev 的 dispatchPrewarm（cache-only=true）。
- **bundle 文案冲突解**：master 现行文案保留，只新增 04a 必需的 `share.dialog.exportTitle` 双语键（dev 的「提工单用」措辞改动不随移植）。

### 6.3 待验证前提核销

1. **预览点击线程语境**——IbatisStatementPreview KDoc 明示「调用方必须持读锁（gutter 点击在 previewAsync 的 ReadAction.compute 内）」，券01 实施照此口径全量索引化（读动作内仅剩一次索引查询 + 候选数个文件的 PSI 精确匹配），v1 的持读锁长走查已不存在，线程语境核实不再影响方案选择。未再单独采线程栈。
2. **头窗化判据（语料实测，demo 根工程 2492 个 XML）**：`<typeAlias>` 最大偏移 818 字节、`<mappers>` 1072 字节——均在 16KB 窗内；**`<properties>` 最大偏移 67085 字节超窗**。结论：`MapperNamespaceIndex` 的 `#config` 判定**保持全文扫描不改**（头窗化会把超窗 config 的键丢掉＝补全清单少收，收益却只是省一次 BGT 正则扫描）；本券标记「证据充分、不实施」。新索引 SqlMapConfigAliasIndex 的 typeAlias 全文扫描同理保留。
3. **新开索引 vs 并入**——新开独立索引落地（对位两前置索引各自独立 bump 的既有取舍）；`fileBasedIndex` EP dynamic=true 已由前两索引实证（KDoc 记录），无新增卸载屏障。
4. **dev 券04 ShareDialog**——已随 `5b98f53b0` 直接移植落地（Task.Backgroundable 形态 + ShareExportOffEdtGuardTest 守卫），本前提由「移植即取证」闭环，不再依赖合流验证。
5. **locationHash 键语义**——按「仅会话内去重」语义使用（四个容器的生命周期本就是会话级；项目关闭残留少量带旧前缀的键，量级=语句数字符串，第八期报告已登记）。未发现漂移反例。
6. **runWhenSmart 重试口径**——直接移植 dev 已验证实现与 PreviewDumbModeRetryTest 四例（未重新设计）；测试接缝（`indexNotReadyNoticeForTest`）随 cherry-pick 在位。

### 6.4 not run 条款（按用户指令测试交 CI）

- **本分支全部测试在 CI 执行，本地零 gradle 运行**（用户 2026-11-17 指令：跑测试交给 CI 减少本地资源占用；本地仅做单次编译验证，读数见 6.5）。
- **not run：全量门禁**——按三档分层归 CI；**not run：定向测试**——计划内应跑的定向类（SqlMapConfigAliasIndexTest / IbatisTypeAliasResolverHotPathTest / IbatisParamClassQuoteHeavyTest / ExecFileWriteCountTest / PreviewDumbModeRetryTest / ConsoleVisibleLogGateTest / ShareExportOffEdtGuardTest / SqlDockedPreviewTest / MapperSqlInspectionTest / PluginXmlStructureGuardTest 族）**全部交 CI，本地无读数**，push 后按 CI run 回填。
- **not run：T0 真机读数五项**——需真机沙盒，随批移交（不阻塞）。
- 券01 反向验证（撤守卫必红）由 `IbatisTypeAliasResolverHotPathTest` 的合成样本正反自证在 CI 兑现；券02 反向验证（撤闸必红）依赖 dev 侧已验证的 dumb 夹具（同 patch 移植），CI 兑现。

### 6.5 编译验证

- 第一轮 `compileKotlin compileTestKotlin`（in-process Kotlin 策略，3m46s）：主源**通过**；测试源 1 处编译错——SqlMapConfigAliasIndexTest 内核用例字符串跨行误用普通字面量（Kotlin 普通串不可跨行），改 raw string + trimIndent。
- 第二轮 `compileTestKotlin`：**通过（exit 0）**。主源 + 测试源全量编译绿；测试执行按 6.4 交 CI。


