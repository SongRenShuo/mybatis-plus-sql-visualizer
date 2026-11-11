# T-HiddenBugPerfScan 全方位隐藏缺陷与性能问题扫描修复 — 开发计划

- 日期：2026-11-11 05:50
- 任务编号：T-HiddenBugPerfScan
- 来源：用户指令「全方位扫描分析当前插件的隐藏 bug 或者性能问题……直到所有问题全部修复」
- 性质：**全仓只读审计 → 逐券修复**——四个只读审计代理（UI/动作/历史包、日志/MCP/update/agent 包、SQL/执行/数据库/converter 包、psi/xml/psource/reverse/generator + config/context/model/util 横切）产出 45 条候选，主控逐条读码复核 45/45 确认为真；已排除另一分支（t-security-audit-fixes）待合流的 25 条安全审计线索，零重复。
- 实施分支：自 t-domestic-db-dialects@`19a7146af` 拉任务分支 `t-hiddenbug-perf-scan`（工作树 `.worktrees/hiddenbug-perf`）
- 并行登记：`parallel_tasks.py` 在本机不存在（AGENTS.md 第 2 条所指脚本缺失），以人工纪律替代：构建并发 ≤1 路、每笔提交前过编译（L11）、长命令全量落盘（L22）
- 环境备注：本机代理 127.0.0.1:7890 未开，用户级 `~/.gradle/gradle.properties` 代理行已临时注释（备份 `gradle.properties.bak-20261111-hbp`，代理恢复后还原）；本工作树项目级 gradle.properties 代理段同批注释（不随提交入库）。JDK 17 走 `~/.jdks/corretto-17.0.14`。

---

## 一、发现总账（45 条 → 33 券）

严重度口径：P1=功能错/生成错 SQL/明显卡顿主路径；P2=边角功能错/潜在泄漏/确认闸方向性缺陷/热路径性能；P3=低频边角。全部 45 条均经主控亲读代码确认（b 组 4 条中置信度采信代理的 grep+上下文证据）。

### P1（5 条 → 5 券）

| 券 | 发现 | 位置 |
|---|---|---|
| A | and/or 块体 lambda 内嵌套调用泄漏进外层链 → 生成重复条件（块体分支无 `getParentOfType(PsiLambdaExpression)` 过滤、无链尾过滤，对照同文件 `collectChainFromInitializer` 有） | `psi/ArgResolver.kt:181-185` |
| B | HQL/JPQL 命名参数替换对裸文本全文替换、无字面量掩码——字面量 `'HH:mm'` 内 `:mm` 被换成 `?` 且计入参数表 → 产物污染 + 参数整体错位 | `converter/jpa/HqlConverter.kt:141-143`、`JpqlConverter.kt:105` |
| C | EP 通道解析配置快照固化（`refreshConsoleContexts` 只刷桥侧，EP ctx 构造后无刷新点；T-LogPerf-04 同款回归在主通道复发）＋ EP `applyFilter` 无 `synchronized(ctx)`（桥侧有，平台异步 filter 并发下解析器状态机串台） | `log/SqlConsoleBridgeFilterProvider.kt:43-66,83-100` |
| D | 历史文件读取整文件两遍（`revisionsIn` 全文一遍 + 解析一遍）且逐字符 `reader.read()` 循环——20261006 实测 15~16.6s/19MB 热路径的最慢形态；`parseContent(collectRevisions=true)` 本就支持单遍语义（apply 在行循环后执行） | `log/SqlLogFileReader.kt:99-118,141-143,237-239` |
| E | SQL→Java 批量路径 `executeOnPooledThread { ReadAction.compute }` 无 try/catch——PCE/PSI 异常即静默无反应（对照单语句路径 :87-101 有兜底；违背 XmlSynHiGate PCE 契约） | `actions/ConvertSqlToJavaAction.kt:115-132` |

### P2（19 条 → 14 券；C 兼并 EP 无锁、O 兼并 Scorper 边界）

| 券 | 发现 | 位置 |
|---|---|---|
| F | Action 层 9 处 `catch (Throwable)` 吞 PCE——取消被伪装成「未找到」（S7 同型兄弟一次修） | `actions/ConvertToSqlAction.kt:134,397,442,503,558,659,698`、`GenerateCrudSqlFromEntityAction.kt:61`、`GenerateCrudSqlFromDbTableAction.kt:50` |
| G | SqlHistoryManager 锁口径不一致：写路径 `@Synchronized` 而 `entries/findById/toggleFavorite/togglePin/addTag/removeTag/search/updateNewestParamSnapshot/newestParamSnapshot/allTags/filterByTag/filterFavorites` 裸奔——池线程 `add` 与 EDT 读并发 CME/撕裂 | `history/SqlHistoryManager.kt:176-272` |
| H | ExplainPlanDialog 每次执行向面板 Disposer 树挂一个永久 Disposable（强引用已关闭对话框），反复执行累积泄漏 | `ui/ExplainPlanDialog.kt:54-65` |
| I | ParamImportParser 两缺陷：①嵌套 JSON 值 `skipValue` 返回值被丢弃不推进 → 其后所有顶层键丢失；②多行 key=value 首行含逗号被误分流 CSV → 键值全错位 | `ui/ParamImportParser.kt:29-33,89-92` |
| K | OGNL 两缺陷：①超宽整数字面量 `toLong()` 抛 NumberFormatException 穿透 parse 收敛层（上游 SqlPreviewPanelSqlView 渲染分支无 runCatching）→ 预览整链失败；②`/` 统一 BigDecimal 丢整数除法语义（7/2=3.5，真实 OGNL=2）→ if/choose 分支与运行时分叉 | `xml/Ognl.kt:686-687,133-137,554-557` |
| L | `toWrapperCall` 运算符优先级：`SELECT_FUNC || APPLY_FUNC && index==1` 实为 SELECT_FUNC 任意位——三参 selectFunc 第三参（别名位）被解析成 FuncArg | `psi/ArgResolver.kt:293-294` |
| M | EntityResolver 行注释扫描每字段 `substring(0,offset)+split("\n")` ——缓存未命中时 O(字段数×文件大小) 分配（JpaEntityResolver 同型未计） | `psi/EntityResolver.kt:391-394` |
| N | 字符串版 `orderByAsc("a","b")` 多列整体丢弃（首参误判别名 + 别名分支只收 ColumnArg，字符串列全被过滤） | `converter/QueryWrapperParser.kt:577-590` |
| O | SqlTypeClassifier.maskQuoted 不识别 `\'` 转义——掩码错位可致 `INTO OUTFILE` 写证据消失、只读证明 fail-open（确认闸旁路，方向危险） | `execute/SqlTypeClassifier.kt:136-158` |
| P | SqlGenerator.renderLike 重包 LIKE 字面量丢 `'`→`''` 转义 → 语法错误 SQL 进复制/执行链（对照 renderValue 用 raw） | `sql/SqlGenerator.kt:576-588` |
| Q | 顶层关键字扫描 O(n²)（每字符位 `substring(i)` 分配）——预览/MCP/诊断热路径大 SQL 秒级卡顿；同族 ComplexityScorer 边界判定漏 `_` | `sql/perf/rules/PerfRulesBatch1.kt:227`（+Scorer 同型） |
| R | agent `EventBuilder.UPDATE_COUNT_METHOD` 静态强引用 Method→驱动类加载器（券 04 红线「禁回退」漏网；同文件族其余三处已改 ClassValue） | `agent/hook/EventBuilder.java:11,49-52` |
| S | agent EventBus 队列满时宿主采集线程每条 SQL 付 O(2048) 全队列弱一致扫描 + O(n) remove（风暴/孤儿 agent 场景） | `agent/transport/EventBus.java:116-132` |
| T | SqlLogEntryBuffer.dedupeExisting 锁内 O(n²) 指纹比对（`keep.firstOrNull` 线性扫，`dedupeIndex` 键域现成未复用）——万级缓冲手动去重冻结 EDT | `log/SqlLogEntryBuffer.kt:730-754` |
| V | 设置 JSON 导入后不发布 SETTINGS_CHANGE_TOPIC——日志管线/Agent CONFIG/预览面板静默不刷新（对照 apply() :1495 有发布） | `config/PluginSettings.kt:931-934` |
| W | ProjectPluginSettingsConfigurable 与 McpConfigurable 原地改 state 不走 loadState(copy)（全局面板 :1488-1493 已实证同型缺陷并修复，两页未跟进） | `config/ProjectPluginSettingsConfigurable.kt:440-520`、`McpConfigurable.kt:255-262` |
| X | JpaQueryEnhancer 只判 size 字面量即内联——`PageRequest.of(运行时page, 字面量size)` 丢 OFFSET、参数行缺 pageOffset、note 谎报 offset=0（KDoc 声明双字面量才内联） | `converter/jpa/JpaQueryEnhancer.kt:94-107` |

### P3（21 条 → 14 券，Y 族）

| 券 | 发现 | 位置 |
|---|---|---|
| Y1 | ShareDialog 两处 HTML 导出 EDT 同步写盘（大 SQL 高亮 HTML 可达数 MB，慢盘冻 UI；同面板 exportReplayScript 有池线程先例） | `share/ui/ShareDialog.kt:191-196,215-220` |
| Y2 | DiagramPanel/SqlPreviewPanel 在自身 dispose() 内 `Disposer.dispose(this)` 重入（两实例均挂树、无直调路径，纯冗余重入） | `ui/diagram/DiagramPanel.kt:433-436`、`ui/SqlPreviewPanel.kt:982` |
| Y3 | PerfRuleEngine 空 catch 零日志（KDoc 承诺「记录并跳过」）——性能能力静默退化不可归因 | `sql/perf/PerfRuleEngine.kt:50-53` |
| Y4 | MpjLambdaConverter ON-lambda `apply` 不走片段通道——运行期片段静默丢弃/静态 `?` 不登记来源（对照 WrapperConverter.parseOnLambda 已统一 asSqlFragment） | `converter/MpjLambdaConverter.kt:766-769` |
| Y5 | extractConsoleError `lastOrNull` 找行、`indexOf` 按首现定位——重复错误报文时摘录锚定旧错误 | `execute/DataSourceSelectorResults.kt:2606-2618` |
| Y6 | LambdaUpdateConverter/UpdateJoinConverter setIncrBy/setDecrBy 绕过 project 直读全局方言（T-FS-04 破口；`currentDialect(project)` 变体现成） | `converter/LambdaUpdateConverter.kt:108`、`UpdateJoinConverter.kt:139` |
| Y7 | PlaceholderPositions 不识别 MySQL `#` 行注释——注释内 `?` 计入占位符致回填错位 | `sql/PlaceholderPositions.kt:27-38` |
| Y8 | ExpressionEvaluator Char 分支漏 `'`→`''` 转义（String 分支有）——产物引号失衡 | `psi/ExpressionEvaluator.kt:182` |
| Y9 | JavaCodeGenerator ColumnComparison 用裸 columnName 且右侧不 javaEscape——多表列对列产物歧义/字符串字面量打爆 | `reverse/JavaCodeGenerator.kt:1110-1112` |
| Y10 | BatchOperationConverter 五处 `table.columns.first()` 零列实体抛 NoSuchElementException 穿透 convert | `psi/BatchOperationConverter.kt:586,628,728,798,855` |
| Y11 | NamedParamParser 对聚合 SQL 裸跑 `:name` 正则——字面量/注释内幻影参数行（对照 JdbcTemplateSqlPreviewService 的 string-aware `?` 扫描） | `psource/NamedParamParser.kt:25,54-72` |
| Y12 | SqlReverseParser `OFFSET n ROWS` 无 FETCH 的合法形态被拒（SQL Server 2012+；T-M43 声明支持面内缺口） | `reverse/SqlReverseParser.kt:1287-1296` |
| Y13 | TrimSqlNode prefix 缺席时仍剥 prefixOverrides（MyBatis 不剥）——预览与运行时产物分叉 | `xml/TrimSqlNode.kt:83-92` |
| Y14 | SettingsIO.parseNumber 病态数字抛裸 NumberFormatException（契约=ImportException）+ version<1 未拦 | `config/SettingsIO.kt:279-299,82-86` |
| Y15 | PluginSettingsPanelSections 在字段级控件上重复挂监听器（同文件顶部红线自述「挂 addRow 处会随重建叠加」）——每次 rebuild 线性累积 | `config/PluginSettingsPanelSections.kt:185,533-541,561-565,587-605,708,451-459` |
| Y16 | 非 UI 分区面板 apply() 恒报 languageChanged=true（空 model combo）→ 每次 Apply 无谓全量 rebuild 并放大 Y15 | `config/PluginSettingsPanel.kt:1333` |
| Y17 | QuerydslConverter.booleanBuilderCondition 每次预览裸跑 ReferencesSearch（仓内定性「较重」，WrapperCache 为同 API 建过缓存）——字段变量场景全项目扫 | `converter/QuerydslConverter.kt:415` |

---

## 二、修复方案与判据

- 每券一笔提交（同文件多缺陷同笔、多测试类）；提交信息走 UTF-8 文件 `-F`（S8），body 带定向读数。
- 每券至少一条「摘掉必红」反向验证（S2）：优先 `dev-support/reverse_probe.py --commit <哈希> --tests <锚定测试类>`；性能券以「计数/耗时探针」作正向证据、反向探针证明锚定用例依赖新实现。
- 定向测试跑 `./gradlew test --tests "<FQCN>"`，读数以 XML 类数收口（S3）；agent 子项目跑 `:sql-visualizer-agent:test --tests`。
- **not run 条款（预登记）**：Y15/Y16 触设置面板面（L13 平台 UI 测试域）——定向跑 `PluginSettingsPanelUiTest`/`SectionConfigurable*` 全家族，全量 testShards 不跑、报告中如实登记「not run 待收口」；Y1/Y2 触 UI 生命周期，受影响面板测试类定向列出。
- S7 同型兄弟：每券修复时 grep 同型调用点（F 券 9 处、Y10 五处、B 券两文件、Y6 两文件即按此圈定）。

## 三、实施顺序

A → B → C → D → E（P1）→ F~X（P2，按 F/G/H/I/K/L/M/N/O/P/Q/R/S/T/V/W/X）→ Y1~Y17（P3）。每券：改码 → 定向测试绿 → 提交 → 反向探针红 → 回填本册实施记录。

## 四、实施记录

**全部 45 条发现已处置（38 笔修复提交，2026-11-11~12，分支 t-hiddenbug-perf-scan）**。逐券提交哈希与定向读数见各笔提交 body（`git log t-domestic-db-dialects..HEAD`）与 CHANGELOG [Unreleased] 回填段；要点汇总：

- P1 券 A~E：9241e0430 / 8d4fbdb58 / 016601b60 / e206097b4 / a7ae24f94——每券「回归测试先红后绿 + 同族定向回归」收口（读数见提交 body）。
- P2 券 F~X：ad8f3c54e / b0e5fe69d / 38a9683c6 / d9e337be4 / 500730ffa / e545272ea / 21802789c / e487dc735 / 6237755ba / 718c6ead1 / e3e98fc8a / 72362d4b0 / 00f25d6c3 / 446a6b375 / 1c9831fe3 / f639b7a95 / bf9e9bbbb。
- P3 券 Y1~Y17：a6a6db76f / 09252a5a6 / 2f341f216 / e780838a8 / 268149e37 / 27c69b9a0 / d284620ae / 59ead55f0 / ecc93df6f / f2e00cbde / a88b1020c / 30b238e30 / 72e9ac861 / b5c9464df / d2277ba75（Y15/Y16 同笔）/ c76729072。
- **拒判 2 项**：①券 W 的项目级设置页 loadState(copy)——被 ProjectPluginSettingsConfigurableTest 两例（TFS08/O58）当场打回：该页既有契约是「外部捕获的 state 引用跨多次 apply 存活且持续可变」，换引用使外部引用陈旧。MCP 页照常落地。
- **范围收窄 1 项**：券 Y12「接受 OFFSET n ROWS 单独形态」不可行（LimitClause.count 非空、模型不可表达），落地为可行动报错文案，接受面扩大另立项。
- **拒判补记（testFast 收口轮）**：券 Y7 整体回退（5132ebf9c）——iBatis  占位语法与 MySQL  行注释在本仓不可区分，PlaceholderPositions/SqlCommentStripper 启用 # 注释语义令 IbatisXmlPreviewServiceTest 实证回归（#orgId# 被吞、? 丢失）；占位符扫描全局启用 # 注释语义拒判。
- **首版实现缺陷如实登记 2 项**：券 M 的反向回扫首版 lineEnd 退步写错（元素恰起于行首时死循环，测试 JVM 挂起 30 分钟暴露，修正为 lineStart-1）；券 S 的尾部窗口首版收窄 F2b 语义被既有测试打回（改混合方案全量兜底）。另 L4 复发多轮（heredoc/双引号串吃反斜杠——补丁一律改走 Write 工具落脚本）。
- **not run 条款**：①设置面板面（Y15/Y16，L13 平台 UI 测试域）定向已跑、全量 testShards 未跑待收口；②券 Y10 零列实体 Light 夹具未建；③券 Y6 双项目真机场景未跑；④性能券（D/M/Q/S/T）反向探针不适用（无行为差异），以语义回归 + 结构性证据收口，真机大文件基准待后续可选复测；⑤行为型反向探针（reverse_probe.py）抽样见下。
- **testFast 收口轮（9e009b044）**：首跑 4852 例 6 红——①券 E/F 新增守卫补 FatalErrors.isFatal 上抛；②ConvertToSqlAction（7→0）/GenerateCrudSql（1→0）豁免条目移除、ConvertSqlToJavaAction（1→2）计数更新；③两个新 Guard 命名测试登记 GUARD_CLASSES；④五个新 Light 测试摘无效 @Test（JUnit3 语义）；⑤BrowserUtil 调用面 5→4（券 Y1 合流）；⑥券 Y7 回退（见上）。PruneGuard 全桶并发下计时型偶发超时、单跑 8/0/0 非回归。
- **环境备注**：工作树跑测试须 `-PmpDemoProjectDir=` 指向主仓兄弟 demo 克隆（相对路径在 .worktrees 下不成立，5 个 Demo* 用例曾因此假红）；本机代理未开，用户级 gradle.properties 代理行临时注释（备份在侧）。
