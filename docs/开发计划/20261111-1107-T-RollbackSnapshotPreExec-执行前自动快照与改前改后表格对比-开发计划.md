# T-RollbackSnapshotPreExec：执行前自动快照与「改前/改后」表格对比

2026-11-11。来源：用户新需求——「修改和删除语句执行前留一份快照方便回滚，这是可配置的开关，默认关闭；并且可以对比改之前改之后的形式，用 IDEA 原生的对比窗口表格形态体现」。dbx 侧调研见 `docs/内部/调研报告/20261111-1107-SQL回滚快照与改前改后对比-dbx吸收调研报告.md`。

**2026-11-14 追加需求**：SQL 执行前/后允许自定义脚本（前置脚本/后置脚本），参考 CoolRequest 插件的同名功能（HTTP 场景），吸取精华搬到 SQL 上。设计定案见 §3.9（引擎＝纯 Java + javac 内存编译，照 CoolRequest 形态）；其局限（无沙箱、无超时、每次全量重编译）都已计入本计划并列为必补项。

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
- **N7 前置脚本**（2026-11-14 追加）：执行前运行用户脚本，拿到 SQL/目标表/数据源上下文；返回 `false` 可软否决本次执行；失败不静默。
- **N8 后置脚本**（2026-11-14 追加）：执行成功后运行用户脚本，拿到影响行数/结果集摘要/快照条目引用与脚本变量读写（IEnv）；失败不影响已执行结果，仅提示。

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
| 脚本引擎 | 新增 `script/SqlScriptEngine.kt`（javac 内存编译 + MethodHandle + 源码 hash 缓存 + 超时中断）与 `script/api/`（`SqlPreScript`/`SqlPostScript` 模板、`SqlExecContext`/`SqlExecResult`/`IEnv` 上下文对象） |
| 脚本编排 | `ui/SqlPreviewPanelExecution.kt`（前置在确认后、采集前；后置在执行成功回调后）、`log/LogToSqlPanel.kt`（P7 同步接入） |
| 脚本 UI | 设置页/数据源页脚本编辑（`JavaEditorTextField` + `LightVirtualFile` 弹窗大编辑器，CoolRequest 同款）+ 脚本日志区（专属 `PrintStream`，不污染 IDE 控制台） |
| 脚本存储 | 全局/数据源级脚本文本存 `PluginSettings`/项目级 `@State`；脚本变量存项目级 `@State`（作用域见 §3.9 D-2） |

### 3.9 前置/后置脚本子系统（2026-11-14 追加）

**CoolRequest 实现形态（源码实证，`F:\code\Java\Github\other\houxinlin\coolrequest-vip`）**：脚本不是 Groovy/JS/ScriptEngine，而是「纯 Java 源码 + javac 内存编译 + MethodHandle 固定签名调用」——`JavaCodeEngine.java:69-71` 用 `ToolProvider.getSystemJavaCompiler()`，`:161-181` 内存编译，`:210-241` `MethodHandles.lookup().findVirtual` 精确匹配 `handlerRequest(ILog, HTTPRequest, IEnv)` / `handlerResponse(ILog, HTTPResponse, IEnv)` 签名。前置在请求发出前执行、返回 `false` 软否决（`RequestManager.java:247-250`）；后置在响应回来后执行、失败仅弹窗不中断（`JavaCodeEngine.java:103-113`）。三级作用域（请求级+环境级+全局级）按序合并执行（`RequestManager.java:157-173`）。环境变量经 `IEnv.getVar/setVar` 读写并广播刷新 UI（`IEnvImpl.java:107-128`）。

**它欠的债（我们必须补）**：① 无超时无中断——脚本死循环卡死整个请求（`script/` 目录 grep 不到 timeout）；② 每次执行全量重编译、不缓存 Class（`JavaCodeEngine.java:162-165` 注释明说每次新编译器）；③ 无任何沙箱/权限开关——脚本与 IDE 同 JVM，能 `System.exit(0)` 带崩 IDE、能删文件执行进程（模板明说可用任意依赖，`plugin-script-request.java:6`）。

**我们的设计**：

1. **引擎照抄形态**：`SqlScriptEngine` = javac 内存编译（`ToolProvider.getSystemJavaCompiler()`，CoolRequest 生产实证 IDE JVM 可用，仍列 V9）+ 固定签名 `handlerSql(ILog, SqlExecContext, IEnv): boolean` / `handlerResult(ILog, SqlExecResult, IEnv): void` + **按源码 hash 缓存编译产物** + **执行超时（默认 5s）+ 线程中断**。编译参数照抄 `-source/-target` 用 IDE 运行时级别、`-proc:none`。
2. **classpath 只给「插件 API + JDK」，不继承用户项目 classpath**：SQL 场景用户通常不需要引用项目类；收窄 classpath 同时缩小攻击面与编译耗时（CoolRequest 把项目全部库塞进 classpath，`JavaCodeEngine.java:186-205`，不学）。
3. **上下文对象（首版只读）**：`SqlExecContext` = SQL 文本（含填参后的可执行形态）、首关键字、目标表、数据源名、方言；`SqlExecResult` = 影响行数、结果集摘要（截断上限 100 行）、快照条目 id（可联动打开对比）、耗时。**不提供改写 SQL 的 API**（决策点 D-1，见下）。
4. **IEnv 脚本变量**：项目级 `@State` 存储（键值对），后置 `setVar` / 前置 `getVar` 串起「上次结果 → 下次条件」；写 UI 可见的变量列表（CoolRequest 的环境编辑器同款思路，轻量版）。
5. **作用域（D-2，用户 2026-11-14 拍板：全部级都要）**：**全局级 + 数据源级 + 语句级（预览面板级）** 三档，按序合并执行：全局 → 数据源 → 语句级。语句级挂在预览面板/日志面板的条目状态上（与参数预设按方法隔离同族的生命周期语义）；空档位跳过。
6. **失败语义照抄**：前置返回 `false` → 软否决（提示「被前置脚本否决」，不弹模态框，用面板状态行）；前置异常 → 阻断执行 + 脚本日志；后置异常 → 不影响已执行结果，脚本日志 + 轻提示。所有脚本输出只走专属 `ILog`（`PrintStream` 子类），**禁止污染 IDE 控制台**（与 [[feedback-agent-console-quiet-by-default]] 同口径）。
7. **编排顺序**：确认对话框 → **前置脚本（可否决）** → 快照采集（N2，被否决就不采集，省一次查询）→ 提交执行 → **后置脚本** → 改后采集与对比可选化。
8. **红线三道闸**（脚本可执行任意代码，与「不能让用户服务因插件受影响」正面相撞）：开关独立于快照开关、默认关；开启时设置页文案明示「脚本将以当前用户权限执行任意 Java 代码，仅在你完全信任脚本内容时开启」；执行链无静默——每次脚本执行在脚本日志留痕。
9. **UI 照抄形态**：紧凑 `JavaEditorTextField`（Java 高亮）+ 弹窗升级 `LightVirtualFile` 完整 IDE 编辑器、文档变更实时回写（`ScriptEditorDialog.java:44-61`）；编辑区下方挂脚本日志分栏（`ScriptPage.java:33-40` 同款）。空模板脚本用 PSI 判空跳过编译（`EmptyCodeJavaRecursiveElementVisitor.java:35-56` 同款 + LRU）。

**决策点（2026-11-14 拍板结果）**：

- **D-1 脚本能否改写 SQL**：用户未点名改写 → 按计划建议执行：**只读上下文 + 否决权，不给改写 API**。
- **D-2 作用域**：用户拍板「全部级都要」→ 全局级 + 数据源级 + 语句级三档全做（见 §3.9 第 5 条）。
- **D-3 变量可见性**：按计划建议执行：**显式**——设置页给可编辑变量表，脚本 `IEnv.getVar/setVar` 与之双向。

## 四、待验证前提

- **V1** 自定义 `diff.DiffTool` EP + `FrameDiffTool.DiffViewer` 在 2026.2 沙盒里能否被 `canShow` 优先选中并正常渲染/关闭（30 行探针：一个假 request + 两个空 `JBTable`）。这是 §3.6 路线 B 的前置；探针不过就整条降级到路线 A，并在计划里改档记录。
- **V2** `PreviewSideQuery.run` 在「确认对话框之后、提交之前」这条路径上能否安全异步回调（EDT 与非 EDT 边界、ProgressIndicator 上下文）。现有快照查询是在对话框里跑的，路径不同。
- **V3** 执行链改成异步后，是否影响既有「执行前 Schema 校验/方言闸/DumbMode」的时序（`DumbService.runWhenSmart` 已在收口处，提前采集不能绕过它）。
- **V4** 参数化 SQL 填值后的 `SELECT` 与库端真实语义是否一致（触发器、默认值、隐式类型转换会导致改后值与预测不同）。
- **V5** `ParameterReplacer` 对 `IN (?,?,?)` / 列表参数 / 时间戳的填值形态能否直接用于 `WHERE pk IN (...)` 反查。
- **V6** `SettingsRealityGuardTest` 对「开关只在采集路径消费」是否满足（需保证消费点在非 config 包且可静态识别）。
- **V7** 项目根 `.sql-visualizer/snapshots/` 在只读项目 / 远程开发 / WSL 场景是否可写；不可写时的降级（内存态 + 提示）。
- **V8** 大字段（LOB/二进制）进快照的体积与耗时——是否在采集阶段就截断（截断值不能用于回滚，需标注）。
- **V9** `ToolProvider.getSystemJavaCompiler()` 在 IDE JVM（JBR 21）非空——CoolRequest 生产实证（`JavaCompilerUtils.java:31-42` 含 `JavacTool` 反射兜底），但要在本仓 runIde 沙盒实测一次；为 null 时脚本功能整体禁用并明示原因，不能半死。
- **V10** 插件 classloader 内编译的脚本能加载我们 `script/api/` 上下文类——javac 的 classpath 要指向插件 jar/classes（`PluginManagerCore.getPlugin(PluginId).getPluginClassLoader()` 侧路径），运行期加载用插件 classloader 的 `DynamicClassLoader` 形态（CoolRequest `InMemoryJavaCompiler.java:34-48` 同款）。这是脚本子系统最大的平台风险点，先于 UI 验证。
- **V11** 编译耗时与缓存策略：源码 hash → CompiledClass 缓存命中后单次执行只剩 invoke；冷编译实测读数入册（CoolRequest 每次全量重编译约秒级，不学）。
- **V12** 脚本线程模型：脚本跑 pooled 线程、超时中断用 `Thread.interrupt()` + 编译/执行双阶段隔离；验证中断后编译器/类加载器无残留泄漏（Disposer 登记）。
- **V13** 脚本开关与执行链时序：前置脚本失败/否决时不触发快照采集；脚本日志区在面板不存在的路径（日志面板执行）如何呈现。

## 五、实施拆解

- **P0 平台探针（V1）**：30 行 `diff.DiffTool` EP + 空 `JBTable` 双栏，确认沙盒里被优先选中、渲染、关闭无泄漏。不过就整条降级为路线 A 并回填改档记录。
- **P1 采集与持久化（纯逻辑为主）**：状态字段 + 开关 UI + `RollbackSnapshotCapture` + `RollbackSnapshotFileStore` + 单元测试（可进 `testFast` 快桶）。
- **P2 执行前编排**：预览面板路径接入（含异步与失败不阻断），反向验证「开关关掉时零查询」。
- **P3 改后采集与表格对比**：改后按主键反查 + 行/列对齐 + `RollbackTableDiffTool/Viewer`（走 §3.6 路线 B）。
- **P4 回滚执行增强**：事务、命中 0 行报告、状态机（真实回执才落 `rolledBack`）、快照历史列表入口。
- **P5 日志面板路径 + 文档回填**：`LogToSqlPanel` 接入、`docs/架构设计/02-核心流程详解.md:198,225,230`（明写「内存 LRU，不持久化」）与 `01-项目架构总览.md:297,321`、`03-开发与测试指南.md:127` 同步、CHANGELOG `[Unreleased]`、本计划验证读数回填。
- **P6 脚本引擎内核（§3.9，先于一切脚本 UI）**：`SqlScriptEngine`（编译缓存 + MethodHandle 调用 + 超时中断）+ `script/api/` 上下文对象 + 模板与 PSI 判空。全部纯逻辑可进 `testFast`；前置 V9/V10 探针不过则本阶段挂起上报。
- **P7 脚本编排与 UI（D-1/D-2/D-3 已拍板，2026-11-14）**：前置/后置挂执行链（含「否决则不采集」编排）、**全局级 + 数据源级 + 语句级**三档脚本编辑 UI、脚本日志区、显式变量表（D-3）。

## 六、测试计划（定向）

现有基线（实读，供实施时对照，勿沿用旧计划书里的过时读数）：`RollbackSqlGeneratorTest` 49 例、`RollbackRoundTripTest` 6 例（H2 真库往返）、`SqlPreviewPanelRollbackParseShapeTest` 5 例、`RollbackPlanToolTest` 4 例、`ConfirmRollbackParseShareTest` 5 例（以上纯逻辑可进 `testFast`）；`RollbackSnapshotStoreTest` 9 例、`RollbackSqlDialogTest` 38 例、`RollbackSqlPanelTest` 15 例（平台桶）。

- 纯逻辑（`testFast` 快桶）：形态判定矩阵（单表/多表/无 WHERE/子查询/多语句/参数化填不全）、改后 SQL 生成（UPDATE 按主键 IN、DELETE 不查、无主键降级）、行对齐（主键对齐/未匹配/新增/删除）、CAS 谓词生成、剪枝策略、索引读写。
- UI 类（`test` 桶）：开关关闭时**不发起任何查询**（用假 `PreviewSideQuery` 通道计数）、开关打开时采集失败不阻断执行、无数据源时开关禁用、diff 内容构造（表格列与高亮分类）。
- 守卫：`SettingsRealityGuardTest`、`BundleParityGuardTest`、`BundlePlaceholderArityTest` 必须随新增字段/文案自动绿。
- 反向验证：把「开关判断」临时短路成 `true/false` 两个方向各跑一次，必须各有一组用例变红。
- 脚本引擎（P6）：编译缓存命中/失效（同 hash 二次执行不重编译）、固定签名不匹配时报可操作错误、前置返回 `false` 否决语义、前置异常阻断、后置异常不影响结果、超时中断（死循环脚本在 5s 内被中断且线程不残留）、空模板 PSI 判空跳过编译、IEnv 读写与持久化往返。
- 脚本编排（P7）：全局→数据源合并顺序、前置否决时不采集快照（与 N2 联动的关键用例）、开关关闭时脚本链整体跳过（零编译零执行）。

## 七、风险与边界

- **性能**：采集是额外一次 SELECT。默认关 + 行上限 + 超时（2s）三重约束；开关文案里写明「会在执行前多一次查询」。
- **正确性**：改后值只能算「预测/回读」，触发器与并发写的偏差要在 UI 上明示（条目 `warnings`）。
- **安全**：采集只发只读 SELECT，过 `McpSelectGuard` 与方言闸；快照含业务数据，落盘目录自动 `.gitignore("*")`，且不随分享/导出功能外发。
- **脚本任意代码执行（§3.9 独有）**：与 CoolRequest 同样无沙箱——脚本与 IDE 同 JVM，理论上能删文件、起进程、`System.exit`。三道闸：开关独立且默认关、开启时文案明示、执行全留痕。**不继承用户项目 classpath**（CoolRequest 把项目全库塞进编译面，不学），API 面只有 `script/api/` 里我们显式给的对象。脚本子系统整体排在 P6/P7，须 V9/V10 探针先行。
- **不做**：控制台 Ctrl+Enter 原生路径（平台动作）、DDL 快照、跨库/跨连接回滚、日志面板以外的新执行入口；脚本改写 SQL（D-1 默认否决，待用户拍板）、脚本沙箱（超出插件能力面，靠开关+明示兜底，与 CoolRequest/IDE Scripting Console 同风险模型）。

## 八、验收（交给用户，GUI）

1. 设置页出现开关，默认未勾选；关闭状态下执行 UPDATE，行为与今天一致（无额外查询、无新文件）。
2. 打开开关，执行一条带 WHERE 的 UPDATE → 状态提示「已留快照」，`.sql-visualizer/snapshots/` 出现条目与 `index.json`。
3. 同一表执行 DELETE → 条目类型为 DELETE，改后视图显示全部行被移除。
4. 点「对比」→ IDEA 原生 diff 窗口打开，左右为表格，改动单元格/新增行/删除行着色符合预期。
5. 点「回滚」→ 事务内执行，报告每语句影响行数；条目状态变「已回滚」；再点回滚不可用。
6. 手工把某行改成别的值后再回滚 → 该行命中 0 行并被单独列出（CAS 生效）。
7. 关闭开关并重启 IDE → 采集完全停止，历史条目仍可查看与回滚。
8. Community 版（无 Database 插件）→ 开关禁用且有原因说明，执行链不受影响。
9. （脚本，P7 后）设置页脚本开关默认关；开启后写入一段前置脚本返回 `false` → 执行被否决且面板状态行提示，不弹快照查询。
10. （脚本，P7 后）后置脚本里 `log.println("...")` 与 `env.setVar("lastCount", rows)` → 脚本日志区可见输出、变量表出现 `lastCount`；再把前置脚本改为 `log.println(env.getVar("lastCount"))` 后执行 → 能读到上次的值。
11. （脚本，P7 后）死循环脚本 → 5 秒左右被中断，执行链恢复可用，脚本日志有超时记录；关闭脚本开关 → 一切照旧。

## 九、实施记录（2026-11-14，分支 t-rollback-preexec，工作树 .worktrees/t-rollback-preexec）

P0~P7 全部落地（用户令「实施开发计划的所有内容」+「全部由主控来做」，并行代理因并发额度收敛）：

| 阶段 | 提交 | 读数 |
|---|---|---|
| P0 探针 | `3015c193c` | 平台桶 5 例 + testFast javac 探针 2 例；订正两条子代理失真：243/262 `FrameDiffTool` 都是 `createComponent`（非 createViewer）、`DiffManagerEx.getInstance()`（非 getInstanceEx）；**243 实测 EP 工具不在 getDiffTools() 清单最前**（探针下标 8/13），选择正确性改钉「唯一 canShow 命中」 |
| P1+P2 | `d53c35eae` | testFast 13 例（Capture 形态矩阵 + FileStore 落盘/索引/剪枝）+ 平台桶 6 例（编排：开关关零查询/开先查后提交/INSERT 跳过/未填参数跳过/无数据源 fail-open/常量钉子）；反向验证：参数守卫短路 → **恰 2 红**（纯逻辑 + 面板各 1），恢复全绿；关键字判定短路无独立红面（snapshotSelectSql 只在 UPDATE/DELETE AST 分支产出，结构性双重守卫）——读数入册 `17b6a19b7` |
| P6 引擎 | `0b935b205` | testFast 15 例（引擎 13 + javac 探针 2）：正常/否决/后置写变量/异常收敛/编译诊断/500ms 超时中断/缓存命中零重编译/空模板零编译/classpath 隔离/IEnv 广播。实施踩坑四则：javac「public 类须同名 .java」→ 内存 URI 按声明类名动态定；vararg out 投影禁展开 → 显式数组；setLocation File/Path 双重载歧义 → setLocationFromPaths；**testFast 划桶按源码字面含「com.intellij」过滤，注释里写了也被踢出快桶＝空绿**（V9 探针首轮即空绿，靠 No tests found 暴露；已记 pitfall-testfast-bucket-literal-filter-and-config-cache） |
| P3 对比 | `88996453f` | 对齐器纯逻辑 10 例（主键启发式两类命名/单键数字转义/复合键元组/行序降级/引号规则）+ 平台桶 7 例（三件套 EP/唯一 canShow/viewer 生命周期/statusPanel/对齐状态） |
| P4 历史 | `baf12a728` | 回滚生成 5 例（按行 CAS/NULL 局限/无键 UNABLE/DELETE 回填/空输入）+ 「更多」菜单契约守卫九项→十项；v1 改档：从历史直接执行需另起临时执行链，登记为后续项（生成 SQL 文本走用户既有链消费） |
| P5+P7 | `db55aac74` | 编排链 6 例（三档顺序/否决短路/异常硬阻断/空清单/后置失败只记日志/空脚本跳过）+ 面板回归 6 例 + 回滚 SQL 5 例回归。管理对话框 package 错写 ui（编译期暴露即修） |

**verifyGate/buildPlugin（大改动：新增 `diff.DiffTool` EP + 3 个 postStartupActivity 类）**：见 §九末尾读数（收口轮）。
**not run（如实登记）**：runIde 真机全链（V1 diff 窗口实际渲染、V9 JBR javac 真值、V10 插件 classloader 边界、脚本管理对话框/变量表/日志区 GUI、日志面板采集提示）——交用户验收，见 §八 11 步。
**偏离计划已声明**：①语句级脚本 v1 面板生命周期不持久化；②主键为启发式非元数据真值；③历史对话框不做旁路执行；④脚本编辑器 v1 JBTextArea。
