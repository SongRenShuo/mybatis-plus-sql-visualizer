# T-DsListAddSource：数据源列表末尾的「unknown」改为「添加数据源…」 — 开发计划

- 日期：2026-11-11 16:43
- 任务编号：T-DsListAddSource
- 来源：用户 2026-11-11「目前插件 SQL 预览面板『原生控制台』和『更多 → 切换数据源』这两个地方都有数据源列表，但是最底部多了一个 unknown 数据源。我希望将这个改成『添加新的数据源』，点击直接弹 idea 原生的添加数据源弹框」
- 性质：体验（数据源列表的**条目口径**与**末尾动作**；不新增执行通道，不改任何选源/执行判定）
- 前置：无硬前置。相关既有件＝`ChooseDataSourceTakeover`（控制台工具栏下拉接管）、`DataSourceSelector.createRunnerPopupStep`（Runner 列表弹窗）、`DataSourceSettings`（LocalDataSource 反射封装）
- 红线：整链 `com.intellij.database.*` 一律 `Class.forName` + `getMethod` 反射，Community 版不得因缺类抛错（仓内既有约定）；不新增模态弹窗、不改平台其它控制台行为（非 exec 文件仍逐字委托原生组）

---

## 一、需求拆解

一句话：两处数据源列表都活在插件的 Echo 版装配里，底部那条 `unknown` 是**插件自己的名字解析失败兜底**漏出来的，不是用户配的数据源；把它换成一条「添加数据源…」，点开走 IDE 原生的新建数据源流程。

只做两件事：

1. **不显示解析不出名字的条目**（`unknown` 从此消失，且不再有「点了报『数据源 unknown 不存在』」的死项）；
2. **列表最底部补一条「添加数据源…」**，点开＝原生新建数据源（选驱动 → 原生「数据源和驱动程序」属性对话框）。

不做：不接管数据源创建后的连接/内省、不改选源语义、不动执行闸门、不改「更多」菜单本身的条目。

## 二、现状（含证据）

### 2.1 两个列表不是同一份实现

| 入口 | 装配点 | 数据来源 |
|---|---|---|
| 原生控制台头部下拉（被插件接管） | `execute/ChooseDataSourceTakeover.kt:138`（`getChildren`，工具栏按钮）与 `:239`（`actionPerformed`，自带 popup） | `dataSourcesFor()`（`:169`）＝ `DataSourceSettings.getAllDataSources`（`List<LocalDataSource>`）∪ `DbImplUtil.getAllDataSourcesForExec(psiFile)` |
| 更多 → 切换数据源 | `ui/SqlPreviewPanelExecution.kt` `switchDataSourceInPlace()` → `DataSourceSelector.prepareConsole(forceChoose/forceChoosePopup)` → `DataSourceSelector.createRunnerPopupStep`（`execute/DataSourceSelector.kt:1733`） | `DatabaseRunners.getAttachDataSourceRunners` → `expandGroupRunners` |

两条链**各自独立**装配条目，名字也各自解析（`DataSourceSettings.getName` vs `DataSourceSelector.getRunnerName`），但**回落值同为字面量 `"Unknown"`**（本机 `grep "\"Unknown\"" src/main` 只有三处：`DataSourceSettings.kt:105`、`DataSourceSelector.kt:1704`、以及 `ExecutionDialectGate.kt:122` 的规避写法）——这就是「两个地方都多一条 unknown」的直接同源点。

### 2.2 控制台下拉那条 unknown 的成因（已证到字节码）

`DbImplUtil.getAllDataSourcesForExec` 的返回类型不是 `LocalDataSource`：

```
# 243 完整发行版 plugins/DatabaseTools/lib/database-plugin.jar
javap com.intellij.database.util.DbImplUtil:
  public static JBIterable<DbDataSource> getAllDataSourcesForExec(PsiFile);
  public static JBIterable<DbDataSource> getAppropriateDataSourcesForExec(PsiFile);
  public static DbDataSource getForcedDataSourceForExec(PsiFile);
```

而名字解析走的是 `LocalDataSource.getName`：

```
DataSourceSettings.getName(ds):
  cachedClass("…dataSource.LocalDataSource").getMethod("getName").invoke(ds)   // :105-112
```

对 `DbDataSource` 调 `LocalDataSource` 上的 `Method.invoke` → `IllegalArgumentException`（对象不是该声明类的实例）→ `runCatching` 吞掉 → `getOrDefault("Unknown")`。

合并段按名字去重（`seen` 先装全量源名，再逐个补原生集合里缺的），所以：

- 第一批 `DbDataSource` 全部解析成 `"Unknown"`；
- 第一条 `"Unknown"` 不在 `seen` 里 → 追加；
- 其余全部被「同名」挡掉。

**结果＝列表末尾恰好一条 `unknown`**，与用户「最底部多了一个」的描述逐字吻合。点它 → `switchDataSourceTo("Unknown")` → `preferredRunnerName` 在 runner 集合里不存在 → `blocksPreferredRunnerMiss` 拦截 → 报「数据源 Unknown 不存在」——纯死项。

旁证：`ExecutionDialectGate.kt:122` 早已写过 `getName(ds).takeUnless { it == "Unknown" }`，说明这个回落值历史上就被判定为噪音，只是列表装配处没有跟着收口。

### 2.3 「更多 → 切换数据源」那条 unknown（未取证到具体条目）

该链名字同样取 `getRunnerName`（`:1704`，`getDisplayName` 失败回落 `"Unknown"`），但**是哪个 runner 解析不出名字尚未证**：可能是方言映射异常时平台枚举带出的非数据源 runner，也可能是 stale per-file 关联指向已删源。计划把这一步做成实施第一步（W1 的取证日志），不靠猜。

结论：**名字回落要统一收口到「解析不出就不显示」**，两条链都改，才能保证两个入口同时干净；单纯改 A 侧会让 B 侧继续漏。

## 三、方案

### 3.1 名字解析：可空化 + 统一到 LocalDataSource

在 `DataSourceSettings` 增两个不破坏既有语义的入口（既有 `getName` 的 `"Unknown"` 回落**保持不动**——`DialectMismatchHint`、`ExecutionDialectGate`、last-used 记忆链都依赖它返回非空串）：

```kotlin
/** DbDataSource → LocalDataSource（getDelegate 的桥方法返回 RawDataSource；只有真 LocalDataSource 才认）。 */
fun resolveLocalDataSource(ds: Any): Any?

/** 解析不出名字时返回 null（列表装配处用它，别用 getName）。 */
fun nameOrNull(ds: Any): String?
```

- 解析顺序：`ds` 本身是 `LocalDataSource` → 用它；否则反射 `getDelegate()`（`DbDataSource.getDelegate(): RawDataSource`，javap 实证），结果 instanceof `LocalDataSource` 才认；都不行 → null。
- 身份键：改用 `getUniqueId()`（AbstractDataSource）去重，替代现行的「按显示名去重」——同名双源本来就会被按名去重误合并。

### 3.2 列表装配收口

**A 侧（控制台下拉）**：`dataSourcesFor()` 改成「两边都投影成 `LocalDataSource` → 按 uniqueId 去重 → 解析不出名字的直接丢弃」。
`getChildren` 与 `actionPerformed` 共用这份结果，两处各追加一条「添加数据源…」。

**B 侧（Runner 弹窗）**：`createRunnerPopupStep` 的 `items` 过滤掉 `optionalRunnerName == null` 的 runner；`RunnerItem`（`:1776`）加一个 `kind` 或新增 `addNew: Boolean` 标记，用于承载末尾动作。

> 注：`getRunnerName` 仍返回 `"Unknown"` 不动。它同时承担 `chosenRunnerName` 比对、`blocksPreferredRunnerMiss` 判据与日志，改成可空会波及选源语义；**只在装配列表时用可空版本**，语义面零变化。

### 3.3 末尾动作「添加数据源…」——走平台自己的「新建数据源」工厂

平台（243 字节码实证，`database-plugin.jar`）已有一条现成链路，正是数据库工具窗「+」的实现：

```
DatabaseViewActions.getCreateDataSourceAction(
    DataSourceManager<?> manager, Project project, String title, boolean includeSeparator,
    Consumer<DbDataSource> consumer)
  → manager.getCreateDataSourceAction(consumer')
      // LocalDataSourceManager 实现：new LocalDataSourceManager$NewDataSourceActionGroup(驱动子项).setPopup(true)
      // 子项 actionPerformed → driver.createDataSource(name) → consumer' 调
  → onDataSourceCreated(DbPsiFacade, DataSourceManager, RawDataSource, Consumer)
      → DbPsiFacadeImpl.createDataSourceWrapperElement(raw, manager)
      → DataSourceManagerDialog.showDialog(facade, dsWrapper, null, consumer, runnable)   // 原生「数据源和驱动程序」属性对话框
```

即：**点我们的「添加数据源…」→ 原生驱动列表（含 LRU 排序）→ 选驱动即弹原生属性对话框**，与 IDEA 自己的「+」完全同一条菜单与同一个对话框，插件不造任何 UI。

落地要点：

1. **反射隔离**：`DatabaseViewActions`、`DataSourceManager`、`com.intellij.util.Consumer`、`LocalDataSource` 全部 `Class.forName`；参数类型用 `Class` 对象拼 `getMethod`（`DataSourceManager::class.java` 这类直接符号引用在 Community 会炸）。
2. 取 manager：`DataSourceManager.byDataSource(project, LocalDataSource.class)`；取不到再退 `getManagers(project).firstOrNull()`（P-3）。
3. 得到的对象是 `ActionGroup`（`NewDataSourceActionGroup`，`setPopup(true)`）：在 popup 里作为**二级菜单**展开（`JBPopupFactory.createActionGroupPopup`，锚在当前条目），或者在列表里直接调 `actionPerformed` 让平台自己弹（P-2：默认不弹，见 A 组的既有教训）。
4. `consumer` 收到新 `LocalDataSource` 后：**刷新列表**（下次打开自然重枚举）＋ 按 D-1 决定是否自动切到新源。
5. Community / 平台类缺失 / 反射失败：该项**不显示**（不是显示了再报错），
   与既有「Database 插件缺席则整条执行链静默降级」口径一致。

### 3.4 文案

新增双语键（`MpSqlVisualizerBundle.properties` / `_zh.properties`）：

- `execute.datasource.add` ＝ `Add Data Source…` / `添加数据源…`
- `execute.datasource.add.tip`（可选，悬停说明「打开 IDE 原生新建数据源对话框」）

## 四、决策点（需用户拍板）

| 编号 | 问题 | 建议 |
|---|---|---|
| D-1 | 新建数据源成功后是否**自动切到新源**（复用 `switchDataSourceTo(name)`） | 建议：是。用户点「添加数据源」的动机就是"现在这个源不对"，建完还要再点一次是多余；失败则留在原源并给出提示 |
| D-2 | 该动作只加在「控制台下拉 + 更多→切换数据源」，还是**执行选择器弹窗也加** | 建议：统一加。B 侧只有一处装配点（`createRunnerPopupStep`），分叉反而要新增开关参数；且"没有可用源"时最需要这个入口 |
| D-3 | 平台不可用（Community / 反射失败）时该项：隐藏还是置灰+说明 | 建议：隐藏。置灰项在纯 Community 环境里等于永远灰着，徒增噪音 |
| D-4 | 「添加数据源…」是**二级菜单（驱动列表）**还是直接打开「数据源和驱动程序」窗口 | 建议：二级菜单（对齐原生「+」，少一次点击）。若 P-2 证伪（平台在 popup 里弹不出驱动列表），降级为 `DataSourceManagerDialog.showDialog(project, null, null)` 直开窗口 |

## 五、待验证前提（实施前先证伪）

| 编号 | 前提 | 怎么验 |
|---|---|---|
| P-1 | B 侧那条 unknown 的确切来源 | W1 在 `createRunnerPopupStep` 装配处打一条一次性日志：runner 的 `javaClass.name` + `getDisplayName` 调用结果 + 是否对得上在册 `LocalDataSource` 名单；沙盒点开一次「更多 → 切换数据源」即得读数 |
| P-2 | 把平台驱动组塞进 `BaseListPopupStep` 的分支里能正常展开（`ActionGroup` 默认 `actionPerformed` 什么都不弹，`ChooseDataSourceTakeover` 已被此坑咬过一轮） | 沙盒实测：`createActionGroupPopup` 用一个 `NewDataSourceActionGroup` 实例展开→选驱动→原生对话框是否出现；三版本都要（243 沙盒 / 日常 262 / 263） |
| P-3 | `DataSourceManager.byDataSource(project, LocalDataSource::class.java)` 恒非空；空时 `getManagers()` 里挑哪个 | 沙盒打点两值；`byDataSource` 返回 null 时不显示该项 |
| P-4 | `DbDataSource.getDelegate()` 对 local manager 恒返回 `LocalDataSource` | 用真实项目实测（A 侧日志打印 `delegate.javaClass.name`）；不成立则退回「A 侧只保留 `getAllDataSources` 一路，放弃原生集合的补集」——这条兜底不改变「unknown 消失」的结论 |
| P-5 | 平台在原生对话框 OK 时**自己**完成注册（无需插件再 `LocalDataSourceManager.addDataSource`），且 `consumer` 回调时机在 OK 之后 | 沙盒实测：取消对话框后数据源列表不应多出空源；OK 后 `DataSourceStorage.getDataSources()` 应包含新源 |
| P-6 | `Consumer` 反射入参在 243/262/263 三版都存在且可用（`com.intellij.util.Consumer` 是平台 util，不随 Database 插件走） | javap 三版 `com.intellij.util.Consumer` + 沙盒跑通 |

## 六、实施步骤

| 步骤 | 内容 | 产出 |
|---|---|---|
| W1 | 取证日志（P-1/P-3/P-4）：两处列表装配处各加一条一次性 `[DsList]` 日志（条目 class、名称解析结果、是否在册），沙盒点开两次取读数 | 读数贴进计划「实施记录」；据此定 B 侧过滤条件 |
| W2 | `DataSourceSettings` 增 `resolveLocalDataSource` / `nameOrNull`（反射 + 缓存，沿用既有 `cachedClass`/`cachedMethod` 记忆化） | 两个纯函数 + 单测 |
| W3 | A 侧收口：`dataSourcesFor` 改投影 + uniqueId 去重 + 丢弃无名项；`getChildren` / `actionPerformed` 各自追加末尾动作 | `ChooseDataSourceTakeover.kt` |
| W4 | B 侧收口：`createRunnerPopupStep` 过滤无名 runner；`RunnerItem` 承载末尾动作 | `DataSourceSelector.kt` |
| W5 | 新增 `execute/NewDataSourceEntry.kt`（或并入既有件）：反射装配「添加数据源…」动作，`enabled/available` 判定＋`consumer` 回调（刷新 + 可选切源） | 单一平台入口封装，A/B 两侧共用 |
| W6 | 文案双语 + CHANGELOG `[Unreleased]` 回填 + 架构文档（`docs/架构设计/02-核心流程详解.md` 执行链一节补一句列表口径） | 文档 |

## 七、测试与反向验证

**可测性改造**：把「两路来源 → 干净条目表」抽成纯函数（入参为桩对象或最小接口），否则 `BasePlatformTestCase` 也造不出 `DbDataSource`。

定向用例（新增，放 `execute/` 包）：

1. `DbDataSource 投影`：给一个「只认 LocalDataSource 的桩」，喂 `DbDataSource` 形桩 → 解析成 delegate 的名字；喂既不实现 LocalDataSource 也无 delegate 的桩 → `nameOrNull` 返回 null；
2. `合并去重`：全量 3 源，原生集合给 2 源（1 个重复、1 个新）→ 结果 4 条且无 `"Unknown"`；同名不同 id 的 2 源 → 保留 2 条（按 uniqueId）；
3. `列表末尾动作`：A 侧 `getChildren` 最后一项文本＝`添加数据源…`；B 侧 `RunnerItem` 列表最后一项是 add 项，且无名 runner 已被过滤；
4. `反例`：一次都解析失败时列表为空 → 仍然只出「添加数据源…」一项（不能空列表什么都不给，否则用户没有任何出路）。

反向验证（摘掉修复看必须变红）：

- 摘掉「丢弃无名项」→ 用例 2 必红（列表里重新出现 `Unknown`）；
- 摘掉 A 侧 `resolveLocalDataSource` 的 delegate 解析（退回直接当 LocalDataSource 用）→ 用例 1 必红；
- 摘掉 B 侧滤空 → 用例 3 必红。

## 八、验收（GUI 由用户执行，助手只出步骤与判据）

1. 打开任意预览面板 → 点控制台头部数据源下拉 → 列表里**没有** `unknown`，最底部一条「添加数据源…」；
2. 点「添加数据源…」→ 出现 **IDE 原生驱动列表**（含最近使用的驱动）→ 选 MySQL → 出现**原生「数据源和驱动程序」对话框**（有 Host/Port/User/Password/Test Connection）；
3. 对话框 Cancel → 数据源列表**不新增**空源；再打开下拉仍是原条目；
4. 重复一次并填好连接信息 OK → 下拉列表出现新源；按 D-1 应已自动切到新源（面板右上绑定行/控制台头部同步显示新源名）；
5. 「更多 → 切换数据源」重复 1~4，形态与第 1 条一致（同一条 `unknown` 应一并消失）；
6. 失败面：在 Community 版（或屏蔽 Database 插件）打开预览 → 「添加数据源…」不出现，其余功能不报错、`idea.log` 无 `NoClassDefFoundError`。

## 九、影响面与风险

- **只影响两处列表装配**；`getRunnerName`/`getName` 的既有回落不动，`chosenRunnerName`、`blocksPreferredRunnerMiss`、last-used 记忆、方言闸门的判定输入全部保持原样；
- 风险 R1：A 侧丢掉 `getAllDataSourcesForExec` 的补集后，在「数据源未加载完」窗口期可能少显条目（原实现两路取并集正是为此）。缓解：先投影再并集（P-4 成立时零损失）；只有 P-4 证伪才降级为单路，届时用「列表为空 → 只显示添加数据源」兜底；
- 风险 R2：平台 `DataSourceManagerDialog` 在 262/263 的内部签名变动（263 已有过设置页整包搬家的先例）。缓解：`showDialog(facade, Object, DatabaseCredentials)` 与 `getCreateDataSourceAction(...)` 两处都按「方法名 + 参数类型」拼，失败静默降级并打 warn 日志；
- 风险 R3：二级菜单在 popup 里展开（P-2）在 243 与 263 行为可能不一致 → D-4 的降级路径必须实现，不能只有一条路。

## 十、文档回填

- `CHANGELOG.md` 的 `[Unreleased]`：`Fixed` 一条（列表里的 `unknown` 死项）+ `Added` 一条（列表底部「添加数据源…」直连 IDE 原生新建数据源），带提交哈希；
- `docs/架构设计/02-核心流程详解.md` 执行链：补「数据源列表条目口径＝在册 `LocalDataSource`，名字解析失败不显示；末尾动作为平台新建数据源工厂」；
- `docs/LESSONS.md`：若 P-1 取证揭示新的「反射类型错配被 runCatching 吞成 `"Unknown"`」形态，按规则记一条（规则一句话 + hash 证据 + Seen 计数）。

## 十一、不做（明确排除）

- 不做插件侧自建「新建数据源」表单（用户要的是原生弹框）；
- 不恢复历史上被否决的自建换源下拉/链接（T-ExplainFailUx W11 已 revert，维持否决）；
- 不在「无可用数据源」红条上顺手加动作（属另一议题，本轮只动列表条目）；
- 不做创建后自动连接/内省（交回平台，用户自己点 Test Connection）。

---

## 十二、实施记录（2026-11-12）

- **车道**：分支 `t-ds-list-add-source`（基于 master `34d3af5ec`）＋ 工作树 `.worktrees/t-ds-list-add-source`；主控单干，未派子代理（用户令）。提交：代码＋测试 `c728c8058`，文档 `e6e4d4942`。
- **决策点落法（用户未逐项拍板，按计划 §四建议执行）**：D-1 建源成功自动切到新源（回调先核 `DataSourceStorage` 在册，防未配完即切）；D-2 三处列表装配点统一（执行选择器与切换数据源共用 `buildRunnerItems`）；D-3 平台不可用一律隐藏；D-4 主路＝取平台驱动组子项自渲染二级列表，降级路＝直开 `DataSourceManagerDialog` 管理器窗口。
- **平台前提新证据（实施中 javap 243 定案，进架构文档）**：`NewDataSourceActionGroup.update` 调 `DatabaseViewActions.isDataSourceActionsEnabled`，要求 DataContext 带 `DatabaseView.DATABASE_VIEW_KEY`——**整组直弹必被置灰**（P-2 的具体机理），故驱动子项逐个取出自渲染（子项 `LocalDataSourceManager$2` 的 `actionPerformed` 只读构造期捕获字段，无 DataContext 依赖）。
- **编译迭代**：`ActionGroup.children` 在 Kotlin 不解析（须 `getChildren(null)`，仓内 `delegateChildren` 同款先例）；测试桩 `val delegate` 与 `fun getDelegate()` 撞 JVM 签名（改 `inner`）。
- **定向读数（独立沙盒 `MPSV-DsList`，临时 sandboxContainer 已还原未入库；并行登记 t-dslist）**：`DsListAddSourceTest` 12 例新增 ＋ 同链回归 `ChooseDataSourceTakeoverTest` 3 / `PreviewPreparePickTest` 8 / `DataSourceSelectorPreferredMissTest` 6 / `TakeoverUpdateThreadGuardTest` 2 ＝ **31/0/0**。
- **反向验证两读数（探针逐字还原后复跑）**：三探针（R1 merge 摘「无名丢弃」/ R2 buildRunnerItems 回落 Unknown / R3 摘末尾添加项）→ **12 例 3 失败**，失败点恰为 `test合并_无名条目一律丢弃_不再渲染Unknown`、`testB侧_无名runner不渲染`、`testB侧_末尾添加项在平台可用时追加且不携带runner`，其余 9 例仍绿；逐字还原 → **31/0/0**（`BUILD SUCCESSFUL`）。
- **not run（如实登记）**：① 真机/沙盒 GUI 走查（§八 1~6，含 P-5「consumer 回调时机与取消不落空源」、P-1 的 `[M36][DsList]` 取证日志读数、P-2 驱动弹层在 262/263 的行为、P-6 Consumer 三版可用性）——需 runIde 沙盒连真实数据源，交用户验收或后续派 GUI 车道；② `resolveLocalDataSource` 的 delegate **成功路**真反射（默认 test 沙盒无 Database jar，退化路已验、语义路经注入桩已验）；③ 全量门禁（本轮只定向，收口时按纪律补 `mode=gate`）。
- **build.gradle.kts 的临时 sandboxContainer 未随提交入库**（提交前 diff 已核对只剩文档改动）。
