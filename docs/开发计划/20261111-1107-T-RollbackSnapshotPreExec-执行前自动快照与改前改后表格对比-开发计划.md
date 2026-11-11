# T-RollbackSnapshotPreExec：执行前自动快照与「改前/改后」表格对比

2026-11-11。来源：用户新需求——「修改和删除语句执行前留一份快照方便回滚，这是可配置的开关，默认关闭；并且可以对比改之前改之后的形式，用 IDEA 原生的对比窗口表格形态体现」。dbx 侧调研见 `docs/内部/调研报告/20261111-1107-SQL回滚快照与改前改后对比-dbx吸收调研报告.md`。

## 一、现状与差距

本仓已有回滚能力（T-RollbackSql 线，已随 v2026.10.23 起多版发行），但与新需求差三处：

| 需求点 | 现状（实读代码） | 差距 |
|---|---|---|
| 执行前留快照 | `registerRollbackSnapshotIfNeeded`（`SqlPreviewPanelExecution.kt:556-583`）只解析文本 + 写内存 Map，**不查库**；条目 `SnapshotEntry` 十个字段无行数据（`RollbackSnapshotStore.kt:58-69`） | 缺「自动、执行前、查库、落盘」的采集 |
| 开关默认关 | 无此开关（`PluginSettings.kt:75-713` 全量开关里无 rollback/snapshot 项），登记对 UPDATE/DELETE 无条件执行 | 需新增设置项并保证默认关 |
| 改前/改后对比 | 只有改前快照的表格/文本双态展示（对话框内瞬时内容，弹窗销毁即丢）；`markRollbackExecuted` 只代表「已交给执行器」（`RollbackSnapshotStore.kt:155-168`） | 缺「改后」采集、行对齐与双侧对比 |
| IDEA 原生 diff 表格 | 仓内只有两处**纯文本** diff（`SqlPreviewPanelSqlView.kt:623-646`、`HistorySqlLogPanel.kt:974-993`）；`ui/diff/SqlDiffer.kt:3-4` 自标无调用方 | 无表格形态 diff |

值得说明：**回滚生成的乐观锁已经存在**——`revertUpdateFromSnapshot` 生成 `SET 旧值 WHERE 主键 AND 被改列 = 新值`（`RollbackSqlGenerator.kt:523-543`），与 dbx 的 CAS 同构。新链路沿用，不重造。

结论：这不是从零新建，而是给现有回滚链补「自动采集 + 持久化 + 改后采集 + 表格对比」四段。

## 二、需求拆解

- **N1 开关**：设置页新增布尔开关，默认 `false`（关闭）。关闭时行为与今天逐字节一致（零新增查询、零新增 IO）。
- **N2 执行前采集**：开关打开时，执行 UPDATE/DELETE 前先用只读旁路把「将被改动的行」查回来存下（改前镜像）。
- **N3 落盘与可检索**：快照要能在执行结束后、IDE 重启后仍拿得到，按项目/连接/表可查，容量与保留期可控。
- **N4 改后采集**：执行成功后采集「改后」状态，与改前按行对齐。
- **N5 对比窗口**：用 IDEA 原生 diff 窗口呈现，形态是**表格**（左右两侧表格 + 单元格级高亮），不是纯文本 TSV。
- **N6 回滚执行**：从快照条目生成并执行回滚 SQL，事务内跑、报影响行数、状态可追踪（回滚过的不再生成新回滚）。

## 三、设计

### 3.1 开关（默认关）

- 位置：`PluginSettings.State` 新增 `rollbackSnapshotBeforeExec: Boolean = false`，落 `mp-sql-visualizer.xml`（APP 级）。
- 为什么 APP 级而不是项目级：执行链三处入口（预览面板 / 日志面板 / 控制台提交通道）都能读到 APP 级设置；项目级要经 `ProjectPluginSettings`，多一层项目上下文，收益不明显。
- 关联子项（同一开关下，不单独暴露成开关）：采集行数上限、保留天数/条数。首版给固定默认值（行上限 500、保留 7 天、条目上限 200、单条目字节上限 2MB），写进设置项说明文案，不做 UI。
- 守卫：新增字段会被 `SettingsRealityGuardTest` 约束（State 每个 var 必须在非 config 包有消费；控件必须进 `apply`/`reset`；`settings.*` bundle 键必须被引用），实现时一次补齐。

### 3.2 采集时机与编排

现状执行链是同步的：确认对话框 → 直接提交给控制台。采集要查库（异步），必须改成「确认 → 异步采集 → 再提交」的编排，且**采集失败不能阻断执行**（默认 fail-open，只提示）。

三个插入点（按覆盖优先级）：

1. `SqlPreviewPanelExecution.executeSql(...)`：已有 `registerRollbackSnapshotIfNeeded` 钩子，把「登记元信息」升级为「登记 + 异步采集」。
2. `SqlPreviewPanelExecution.executeAdhocSql(...)`：`rollbackOfEntry != null`（即回滚执行）不采集；其余 adhoc 执行按同一规则采集。
3. `RollbackSqlDialog.executeRollback()`：回滚动作本身永不采集（N6 的「回滚不可再回滚」）。
4. `LogToSqlPanel.executeSql`：日志还原后的执行路径，目前完全无快照登记，需补。

控制台真实提交收口（`DataSourceSelectorConsole.executeOnConsole`）**首版不接**：它跑在 EDT + `DumbService.runWhenSmart` 内，改成延迟提交要动全链时序，风险远大于收益，登记为后续项。

### 3.3 采集范围与降级（诚实拒绝）

只对**单表、无 JOIN 写目标、WHERE 可解析**的 UPDATE/DELETE 采集。判定复用 `RollbackSqlGenerator.generate`（UPDATE/DELETE 目前返回 `UNABLE` + `snapshotSelectSql` + `targetTable` + `whereText` 的正确部分）。降级矩阵：

| 形态 | 处置 |
|---|---|
| 多表 UPDATE / DELETE ... USING | 不采集，提示「多表写不采集快照」 |
| INSERT / SELECT | 不采集（现有登记逻辑本就只认 UPDATE/DELETE 首关键字） |
| 无 WHERE（全表写） | 采集，但条目上加「全表」标记，行数触上限即截断并标明 |
| WHERE 含子查询 | 采集（SELECT 化后仍可跑），但行定位告警 |
| 参数化 SQL（`?`/`${}`/运行期槽位） | 先用 `ParameterReplacer.buildExecutableSqlWithSlots` 填值再采集；填不全就拒绝并说明缺哪个参数 |
| 多语句脚本 | 只对「当前要执行的那一条」判定；不是单条 UPDATE/DELETE 就不采集 |
| 无绑定数据源 / Community 版 | 开关可见但禁用 + tooltip 说明原因（沿用 `McpDbExecutor.isDatabaseAvailable()` 惯例） |
| 采集超时 / 查询失败 | 不阻断执行，状态栏提示 + 该次执行无快照 |

### 3.4 快照数据模型与持久化

不复用 `RollbackSnapshotStore`（内存 + 100 条 LRU，会话结束即丢）。新建 PROJECT 级存储：

- 落盘目录：`<项目根>/.sql-visualizer/snapshots/`（复用 `DataSourceSelectorResults.projectExecFile` 那套「目录 + 自动 `.gitignore("*")` + document+save」写盘纪律）。
- 单条目 = 一个 JSON：`{id, createdAt, connectionId/name, database, table, dialect, statementSql, executableSnapshotSql, mode(UPDATE/DELETE), primaryKeys[], columns[], beforeRows[][], afterRows[][], beforeCapturedAt, afterCapturedAt, rowLimitHit, warnings[], rollbackState, rollbackExecutedAt, rolledBackBy}`。
- 索引：同目录 `index.json` 只存轻量元数据（id/时间/表/行数/状态），用于列表与剪枝；写索引走同一个写盘工具，避免两份写并发。
- 剪枝：保留 7 天 / 200 条 / 总量 50MB 三者先到先剪；IDE 启动与每次新增时各跑一次（幂等）。
- 状态机：`captured`（只有改前）→ `compared`（有改后）→ `rolledBack`（已回滚）/`stale`（表结构或主键列变化、无法生成可靠回滚）。

### 3.5 改后采集与行对齐

- **UPDATE**：改前行的主键值集合 → 执行成功后按主键查改后（`SELECT * FROM t WHERE pk IN (...)`，分片 IN，每片 ≤500）。这样即使 WHERE 列已被改掉，仍能定位同一批行。
- **DELETE**：改后恒为空，不查库（省一次往返）；对比视图里表现为「全部行被移除」。
- **无主键**：改后按「整行等值」不可靠（值已变），直接不采集改后，条目降级为「仅改前」，并在对比窗口说明。
- **对齐键**：有主键按主键对齐；主键缺失的行标记 `unmatched`，单列成「无法对齐」区。
- **时间戳陷阱**：`UPDATE` 命中 0 行（条件不匹配）时改后与改前相同，条目记 `noRowsAffected`，不生成回滚。

### 3.6 对比窗口（IDEA 原生 + 表格）

目标：`DiffManager.getInstance().showDiff(project, request)` 打开平台 diff 窗口，内容区是左右两个只读 `JBTable`，行按主键对齐，单元格着色（左改前、右改后；新增行绿、删除行红、改动单元格黄）。

**平台事实（262 实测，详见调研报告 §五）**：

- `com.intellij.diff.contents.DiffContent` 在 262 只有 `getContentType() / getNavigatable() / onAssigned()` 三个方法，**没有 `getComponent()`**——「自定义 DiffContent 塞表格」这条路不存在。
- 唯一公开的「自定义视图组件」入口是 `com.intellij.diff.FrameDiffTool$DiffViewer#getComponent(): JComponent`（262 `intellij.platform.diff.jar`）。
- `DiffManagerImpl.getDiffTools()` 把 **EP 扩展排在平台内置 Simple/Unified/Binary/Dir 之前**，`canShow` 命中即被选中；`DefaultState.init()` 把 `viewer.getComponent()` 直接放进原生 diff 内容面板。
- `TableDiffTool` / `TableDiffViewer` / `TableEditorHolder` 是 DataGrip（`intellij.database.impl.jar`）内部实现，绑死 `GridDataHookUp`/`DasTable`，且本仓对 DatabaseTools 是 optional 依赖——**不可复用**。

**定案路线（B）**：

1. `ui/diff/RollbackTableDiffRequest : DiffRequest`——携带两侧 `TableSnapshot`（列名、行、主键列、单元格状态）。
2. `ui/diff/RollbackTableDiffTool : FrameDiffTool`——`canShow(request)` 只认上面的 request 类型；`createViewer(context, request)` 返回下面的 viewer。
3. `ui/diff/RollbackTableDiffViewer : FrameDiffTool$DiffViewer`——`getComponent()` 返回「标题 + `JSplitPane` 两个只读 `JBTable`」；`init()` 返回 `ToolbarComponents`；`dispose()` 释放。
4. `plugin.xml` 注册 `<diff.DiffTool implementation="…RollbackTableDiffTool"/>`（EP 名 `com.intellij.diff.DiffTool`，与 DataGrip 同架构，但只用 public 面，不依赖 `EditorHolder`/`TextEditorHolder` 这类 internal）。
5. 行对齐自写：按主键做行匹配，空串/null/数值归一后逐格比较，产出 `EQUAL/CHANGED/ADDED/REMOVED`；同步滚动用共享 `JScrollBar` 或 viewport listener。
6. 窗口形态：按需传 `DiffDialogHints.NON_MODAL`（`show.diff.as.frame` 未设时平台默认开 diff 标签页）。

**兜底路线（A）**：把两侧行序列化成定宽 TSV，走 `DiffContentFactory.createEditable` + `SimpleDiffRequest` + `showDiff`——仓内已有两处同款用法可直接照抄。**它做不到单元格级高亮**（平台只有行级 + 字符级内联片段，没有单元格语义），仅作 B 探针失败时的降级，且要在文案里说清「这是文本对比」。

**已否决**：路线 C（自建 `DialogWrapper` + 表格）不满足「IDEA 原生对比窗口」；路线 B0（`ComponentDiffRequest` 直塞组件）靠 `ErrorDiffTool` 兜底渲染，会被包 `ScrollPane` 且无 diff 工具栏，只当验证捷径。

**已知边界**：F7 差异导航、工具栏的「接受/回退」这类平台 diff 动作不会自动获得，需要自己给工具栏按钮；这是 B 路线的固有取舍，验收时按「能看、能扫、能关」三条判。

### 3.7 回滚执行

- 从条目生成回滚 SQL：复用 `RollbackSqlGenerator.generateFromSnapshotRows`（`REVERT_UPDATE` / `BACKFILL_INSERT` / `REVERT_INSERT`），**沿用既有的乐观锁谓词**（`revertUpdateFromSnapshot`，`RollbackSqlGenerator.kt:523-543`：`SET 旧值 WHERE 主键 AND 被改列 = 新值`），行被他人改动时命中 0 行而不是覆盖别人。
- 执行：默认走事务（`begin` → 逐条执行 → 报每影响行数 → 用户确认 `commit`/`rollback`）；不支持事务的库（ClickHouse / Salesforce / TDengine 复合键，见调研报告 §2.1）在条目上就标「该引擎不可回滚」，不生成半成品。
- 命中 0 行的语句单独列出（stale 行），不静默跳过。
- 状态机与回执：现有 `markRollbackExecuted` 只代表「已交给执行器」（`RollbackSnapshotStore.kt:155-168`），新条目改为「收到真实提交回执才落 `rolledBack`」，与 UI-020 已确立的口径一致。
- 执行完置 `rolledBack`，且**不再生成新的快照条目**。

### 3.8 落点清单（含 i18n 与守卫）

| 面 | 落点 |
|---|---|
| State | `config/PluginSettings.kt` State 新增字段 |
| 设置 UI | `config/PluginSettingsPanel.kt` 控件 + `PluginSettingsPanelSections.kt` 挂载 + `isModified`/`apply`/`reset` |
| 设置检索 | `config/PluginSettingsFolderConfigurable.ideSearchEntries()` 登记 |
| 文案 | `messages/MpSqlVisualizerBundle.properties` + `_zh.properties`（`settings.rollback.snapshot.beforeExec` + `.tip`，`rollback.snapshot.*` 前缀延续既有命名） |
| 采集 | 新增 `sql/rollback/RollbackSnapshotCapture.kt`（纯逻辑：判定形态、拼改后 SQL、行对齐）+ `RollbackSnapshotFileStore.kt`（落盘/索引/剪枝） |
| 编排 | `ui/SqlPreviewPanelExecution.kt`（预览路径）、`log/LogToSqlPanel.kt`（日志路径） |
| 对比 | 新增 `ui/diff/RollbackTableDiffRequest.kt` + `RollbackTableDiffTool.kt` + `RollbackTableDiffViewer.kt` |
| 平台 EP | `src/main/resources/META-INF/plugin.xml` 注册 `<diff.DiffTool implementation="…RollbackTableDiffTool"/>`（属「新平台 API 面」，按仓内规矩要跑 `verifyGate`/`buildPlugin` 构建校验） |
| 列表入口 | `ui/RollbackSqlDialog.kt` 增「快照历史」区（复用现有表格件），或在日志面板加入口 |
| MCP | `sqlv_rollback_plan` 增可选参数读快照条目（可选，P4） |

## 四、待验证前提

- **V1** 自定义 `diff.DiffTool` EP + `FrameDiffTool.DiffViewer` 在 2026.2 沙盒里能否被 `canShow` 优先选中并正常渲染/关闭（30 行探针：一个假 request + 两个空 `JBTable`）。这是 §3.6 路线 B 的前置；探针不过就整条降级到路线 A，并在计划里改档记录。
- **V2** `PreviewSideQuery.run` 在「确认对话框之后、提交之前」这条路径上能否安全异步回调（EDT 与非 EDT 边界、ProgressIndicator 上下文）。现有快照查询是在对话框里跑的，路径不同。
- **V3** 执行链改成异步后，是否影响既有「执行前 Schema 校验/方言闸/DumbMode」的时序（`DumbService.runWhenSmart` 已在收口处，提前采集不能绕过它）。
- **V4** 参数化 SQL 填值后的 `SELECT` 与库端真实语义是否一致（触发器、默认值、隐式类型转换会导致改后值与预测不同）。
- **V5** `ParameterReplacer` 对 `IN (?,?,?)` / 列表参数 / 时间戳的填值形态能否直接用于 `WHERE pk IN (...)` 反查。
- **V6** `SettingsRealityGuardTest` 对「开关只在采集路径消费」是否满足（需保证消费点在非 config 包且可静态识别）。
- **V7** 项目根 `.sql-visualizer/snapshots/` 在只读项目 / 远程开发 / WSL 场景是否可写；不可写时的降级（内存态 + 提示）。
- **V8** 大字段（LOB/二进制）进快照的体积与耗时——是否在采集阶段就截断（截断值不能用于回滚，需标注）。

## 五、实施拆解

- **P0 平台探针（V1）**：30 行 `diff.DiffTool` EP + 空 `JBTable` 双栏，确认沙盒里被优先选中、渲染、关闭无泄漏。不过就整条降级为路线 A 并回填改档记录。
- **P1 采集与持久化（纯逻辑为主）**：状态字段 + 开关 UI + `RollbackSnapshotCapture` + `RollbackSnapshotFileStore` + 单元测试（可进 `testFast` 快桶）。
- **P2 执行前编排**：预览面板路径接入（含异步与失败不阻断），反向验证「开关关掉时零查询」。
- **P3 改后采集与表格对比**：改后按主键反查 + 行/列对齐 + `RollbackTableDiffTool/Viewer`（走 §3.6 路线 B）。
- **P4 回滚执行增强**：事务、命中 0 行报告、状态机（真实回执才落 `rolledBack`）、快照历史列表入口。
- **P5 日志面板路径 + 文档回填**：`LogToSqlPanel` 接入、`docs/架构设计/02-核心流程详解.md:198,225,230`（明写「内存 LRU，不持久化」）与 `01-项目架构总览.md:297,321`、`03-开发与测试指南.md:127` 同步、CHANGELOG `[Unreleased]`、本计划验证读数回填。

## 六、测试计划（定向）

现有基线（实读，供实施时对照，勿沿用旧计划书里的过时读数）：`RollbackSqlGeneratorTest` 49 例、`RollbackRoundTripTest` 6 例（H2 真库往返）、`SqlPreviewPanelRollbackParseShapeTest` 5 例、`RollbackPlanToolTest` 4 例、`ConfirmRollbackParseShareTest` 5 例（以上纯逻辑可进 `testFast`）；`RollbackSnapshotStoreTest` 9 例、`RollbackSqlDialogTest` 38 例、`RollbackSqlPanelTest` 15 例（平台桶）。

- 纯逻辑（`testFast` 快桶）：形态判定矩阵（单表/多表/无 WHERE/子查询/多语句/参数化填不全）、改后 SQL 生成（UPDATE 按主键 IN、DELETE 不查、无主键降级）、行对齐（主键对齐/未匹配/新增/删除）、CAS 谓词生成、剪枝策略、索引读写。
- UI 类（`test` 桶）：开关关闭时**不发起任何查询**（用假 `PreviewSideQuery` 通道计数）、开关打开时采集失败不阻断执行、无数据源时开关禁用、diff 内容构造（表格列与高亮分类）。
- 守卫：`SettingsRealityGuardTest`、`BundleParityGuardTest`、`BundlePlaceholderArityTest` 必须随新增字段/文案自动绿。
- 反向验证：把「开关判断」临时短路成 `true/false` 两个方向各跑一次，必须各有一组用例变红。

## 七、风险与边界

- **性能**：采集是额外一次 SELECT。默认关 + 行上限 + 超时（2s）三重约束；开关文案里写明「会在执行前多一次查询」。
- **正确性**：改后值只能算「预测/回读」，触发器与并发写的偏差要在 UI 上明示（条目 `warnings`）。
- **安全**：采集只发只读 SELECT，过 `McpSelectGuard` 与方言闸；快照含业务数据，落盘目录自动 `.gitignore("*")`，且不随分享/导出功能外发。
- **不做**：控制台 Ctrl+Enter 原生路径（平台动作）、DDL 快照、跨库/跨连接回滚、日志面板以外的新执行入口。

## 八、验收（交给用户，GUI）

1. 设置页出现开关，默认未勾选；关闭状态下执行 UPDATE，行为与今天一致（无额外查询、无新文件）。
2. 打开开关，执行一条带 WHERE 的 UPDATE → 状态提示「已留快照」，`.sql-visualizer/snapshots/` 出现条目与 `index.json`。
3. 同一表执行 DELETE → 条目类型为 DELETE，改后视图显示全部行被移除。
4. 点「对比」→ IDEA 原生 diff 窗口打开，左右为表格，改动单元格/新增行/删除行着色符合预期。
5. 点「回滚」→ 事务内执行，报告每语句影响行数；条目状态变「已回滚」；再点回滚不可用。
6. 手工把某行改成别的值后再回滚 → 该行命中 0 行并被单独列出（CAS 生效）。
7. 关闭开关并重启 IDE → 采集完全停止，历史条目仍可查看与回滚。
8. Community 版（无 Database 插件）→ 开关禁用且有原因说明，执行链不受影响。
