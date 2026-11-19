# T-StandaloneExecPhase2：独立工作台执行环与粘贴即体检（D233/D234）开发计划

- **立项**：2026-11-19 08:25，用户拍板 D233/D234 立项（第四十二轮调研册）；本册＝实施计划，分支 `t-standalone-exec-ii`（自 master `0a82f410c` 起），工作树 `.worktrees/t-standalone-exec-ii`。
- **范围声明（先读）**：D233 的「回滚预案」前置是 #5（t-rollback-preexec）合流，本实施**不含回滚接入**——执行环做到「SELECT 出结果表格、UPDATE/DELETE 走确认闸出执行态」，回滚入口的接缝登记在 §六，#5 合流后一批接上。合流顺序：本支与 `t-nplus1-aggregate`（D235）都进 master 时，本支先行（D235 只动 log 面，无交集）。
- **红线**：不动 `update/update.json`；不 push；与 IDEA 原生重叠的显示层不做；MCP 不新增工具。

## §一 关键锚点（20261119 08:20 实测，master）

| 锚点 | 事实 |
|---|---|
| `ui/standalone/SqlStandalonePreviewPanel.kt`（469 行） | 工作台面板；内嵌完整 `SqlPreviewPanel`（懒创建）；工具栏=解析/格式化/清空/在弹框中打开；**无执行入口** |
| `ui/SqlPreviewPanelExecution.kt:1262` | `internal fun SqlPreviewPanel.executeSql(forceChoose: Boolean = false)`——完整执行链：取原生/自研编辑器文本 → `fillRuntimeSlots` → SchemaValidationGate → DML 确认 → `${}` raw 确认门 → 未知参数确认门 → 占位对账 → 快照登记 → 方言闸 → 控制台内嵌（`embedConsole`）。**前提：`project ?: return`、无 SQL 文本静默 return** |
| `ui/SqlPreviewPanel.kt:978` | 「执行 SQL / 复制 SQL 在弹窗底部按钮区」——内嵌（非弹窗）形态没有执行按钮，这正是 D233 的缺口 |
| `execute/SqlExecutor.kt:38` | `execute(..., onConsole: ((JComponent)->Unit)? = null, ...)`——控制台 UI 组件可内嵌 |
| `sql/SqlPerfAnalyzer.kt:65` | `object SqlPerfAnalyzer { fun analyze(sql: String, dialect: Dialect? = null, ansiQuotes: Boolean? = null): List<PerfWarning> }`——纯文本函数，带 LRU 缓存 |

## §二 待验证前提（实施者先证伪再动手）

1. **内嵌形态执行后控制台真的嵌得进去**：`executeSql` 的 `embedConsole` 走面板自己的 `resultPanel`/`embedConsole` 机制——内嵌在工作台 Splitter 下栏时该机制是否正常展示未实测。证法：先跑既有内嵌面板相关的平台测试类（不新增），再真机走查；若嵌入失效，降级方案=工作台自管 `SqlExecutor.execute(onConsole=…)` 把控制台组件放进工作台自建的结果宿主（对齐 LogToSqlPanel 先例的裸链）。
2. **D234 与内嵌面板既有性能建议的去重**：`SqlPreviewServiceEnhancements.kt` 已消费 `SqlPerfAnalyzer`——先确认内嵌面板是否已经在某处展示性能建议；若已展示，D234 的工作台徽章行改为「不重复渲染同一条」的过滤后清单（按规则 id 去重）。
3. `PerfWarning` 对含 `#{}`/`#id#` 占位的模板 SQL 的误报面：resolve 后先 analyze 模板文本，占位形态是否触发假规则未验证——负例集先行。

## §三 实施（D233 执行环）

1. `SqlStandalonePreviewPanel` 工具栏新增 `ExecuteAction`（顺序排在解析之后）：update 里按「内嵌面板已创建且有 SQL 文本」enable，否则 disable + 说明 tooltip；点击调 `ensurePreviewPanel().executeSql()`（internal 扩展，同包可见需在 `ui` 包外导入检查——若 internal 可见性不够，提升锚点函数为 public 或在本文件包内写薄转发，**以编译为准，不改链语义**）。
2. 无 SQL/未解析时点击：状态行给引导文案（「先解析预览，再执行」），不裸跑。
3. 产物含 `{{模拟占位}}` 时：沿用执行链既有确认门行为，不在工作台层另设闸。
4. Bundle 双语 key：`standalone.action.execute` + tooltip + 引导文案（zh/en 两份，BundleParityGuard 过）。
5. 回滚接缝：`executeSql` 内部已有 `registerRollbackSnapshotIfNeeded` 调用（null 条目），本车道**不动它**——#5 合流后其历史对话框自然可用，唯一余项是工作台态的回滚入口按钮，登记不实施。

## §四 实施（D234 粘贴即体检）

1. `applyResolved` 成功路径（`resolved.failure == null` 且 kind 非 failure）后：取 `resolved.result.sql`（或 compactSql，以 analyze 实测形态为准）跑 `SqlPerfAnalyzer.analyze(...)`，方言取 `DialectRegistry.effective(project)`（与渲染链同源，R52 先例）。
2. 呈现：工作台底部新增一行「体检」label（N 条建议/无建议不占行），悬停 tooltip 列出全部 `PerfWarning`（单行 `｜` 分段，JBLabel 不渲染 `\n` 的坑照 [[pitfall-jlabel-no-newline-and-missing-tooltip]]）；点击弹完整列表对话框（可复制，对齐 T-StandaloneHintAndWarning 的完整信息对话框先例）。
3. 与内嵌面板建议去重：按 §二-2 实测结论定过滤形态；同一规则引擎两处消费，结论文案不得双报。
4. 代次守卫：体检跟随 resolve 代次（`resolveGeneration`），晚到结果丢弃；清空即清体检行。
5. 负例纪律：无结构片段（纯 `SELECT 1`）零建议；模板占位形态不触发假规则（§二-3 的负例集钉住）。

## §五 验收清单（可照抄）

| # | 用例 | 期望 |
|---|---|---|
| A1 | 平台测试：工作台解析成功后 ExecuteAction enable；清空后 disable | enabled 状态翻转恰红恰绿 |
| A2 | 平台测试（不弹窗环境）：无 SQL 调 executeSql 静默 return + 状态行引导文案在 bundle | 引导文案 key 在场 + 面板零异常 |
| A3 | 反向验证：摘掉 ExecuteAction 的 enable 判据 → A1 变红（两次读数贴报告） | 反向必红 |
| B1 | 纯逻辑测试：`SELECT * FROM t` analyze 出建议 → 体检行显示 1 条；`SELECT 1` 零建议零占行 | 正例 1、负例 0 |
| B2 | 反向验证：摘掉体检行装配 → B1 变红 | 反向必红 |
| B3 | BundleParityGuard：新增 key zh/en 双份过守卫 | 守卫绿 |
| C1 | 定向清单：`--tests` 覆盖上述新增类 + 既有 standalone 相关测试类全绿（读数贴报告，全量未跑＝not run 声明） | 0 failed |
| C2 | 真机 GUI 验收（用户）：粘贴 SELECT 执行出结果表格；UPDATE 走确认闸；体检行悬停明细 | 用户点头 |

回退开关：执行入口无 Registry 开关（动作 disable 即回退）；体检行加 `sqlvis.preview.standalonePerfCheck`（默认开，关=旧行为），plugin.xml `<registryKey>` 登记。

## §六 实施记录

（随提交回填：每完成一件可提交的就立即提交）
