# T-PreviewOpenPerfM2：预览装配性能（M2 检索缓存 + 冷热态归因）——开发计划

- 立项：2026-11-12
- 触发：用户报「点击弹出预览窗口耗时较长」；T-MbgPreviewParityAndOpenPerf 的 P5 埋点已给出第一份读数
- 前序：[20261112-0904-T-MbgPreviewParityAndOpenPerf](20261112-0904-T-MbgPreviewParityAndOpenPerf-MBG预览多条件粘连与预览打开耗时-开发计划.md)（同批的 P5 埋点即本任务的数据源）

## 一、已有读数（P5 埋点首采，2026-11-12 10:24）

```
[open-perf] total=266ms parse+ctx=38 profile=2 m2=207 model=19
```

同一次打开的渲染时间线：

```
10:24:55,930  [M36] prewarm done in 191ms
10:24:56,871  [M36] showSql (首次渲染)      ← 间隔 0.94s
```

对照上午同一台机器另一次（冷态）：

```
[prewarm done] → [首次 showSql] = 5.67s（其间插件零日志，平台在存 JPS 模型）
```

**两条结论**：

1. 5.67s 大概率是**冷态**（首次索引 / 平台 JPS 全局模型保存），热态只有 0.94s。要下结论必须先分清
   冷热，否则会把一次性成本当常态去优化。
2. 热态里 **M2 检索占 207ms / 266ms ≈ 78%**，`profile`（FQN 类解析）只 2ms。

## 二、根因与边界

`MbgExampleCallResolver.resolve()` 在主预览路径上同步执行：

```kotlin
ReferencesSearch.search(mapperMethod, GlobalSearchScope.projectScope(project)).findAll()
```

即**全项目索引级搜索**，且：

- 无缓存——每次 `XmlPreviewService.preview()` 都重跑（面板刷新、切换语句、重开窗口各一次）；
- 在装配路径上同步，占用打开耗时的绝大部分；
- 用户工程很大时，命中集与索引规模成比例放大——207ms 是最小规模 demo 的读数。

**已被数据否掉的猜想**：`typeAlias` 未识别导致慢——对 MBG 不成立（`parameterType` 是 FQN，
`resolveExampleProfile` 走 `findClass` 一次查询，读数里 profile=2ms 印证）；对 iBatis 侧另议（已由
前序 P6 覆盖）。

## 三、方案

| 编号 | 内容 | 优先级 |
| --- | --- | --- |
| M1 | **M2 结果缓存**：键＝`statementId + mapper 文件 modificationStamp(or mtime) + Example 类名`，值＝`collectionElements`；命中直接跳过 `ReferencesSearch` | 高 |
| M2 | 缓存失效口径：mapper 文件改动 / 调用点所在 java 文件改动（两者任一 mtime 变则失效；不引入文件监听，按需比 stamp） | 高 |
| M3 | 冷热态分离取证：在 `logPreviewPerf` 里带上「首次/重开」标记与 `DumbService.isDumb`，把 0.94s 与 5.67s 的差别钉死 | 中 |
| M4 | 若 M1 后仍有余量，再看 S2（`DatabaseSchemaResolver` 按表 × 全部数据源轮询，日志无耗时）与 S3（JPS 模型保存触发者） | 低（视读数） |
| M5 | `findCallSite` 用 `findFirst()` 提前终止（原来 `findAll()` 先把全项目引用收成 List 再取第一个） | 高（已实施） |
| M7 | 执行链 `revalidateOnEdt` 的 PSI 校验移出 EDT —— 修「Plugin to blame: SQL Visualizer」平台慢操作告警 | 高（已实施） |
| M8 | `DumbService.isDumb` 时跳过 M2 检索，退回占位骨架 | 高（已实施） |
| M9 | 索引就绪后**自动重算一次**（`PreviewResult.retryWhenIndexReady` + `runWhenSmart`）——冷态秒开骨架 + 稍后补齐 | 高（已实施，用户定案） |

## 三之二、真机读数（2026-11-12，`[T-PreviewFast] request→dialog` vs `[open-perf]`）

| 时刻 | request→dialog | 其中 xml 装配 | 状态 |
| --- | --- | --- | --- |
| 11:08 | **7802ms** | 2053ms（m2=1950） | 冷启动 + JPS 在存模型 |
| 11:21 | **4499ms** | **417ms**（m2=378） | 冷启动（索引重建中） |
| 11:23 | **170ms** | — | 热态 |

⇒ 「打开慢」= 冷态首次；插件自身装配 417ms（M5 后从 1950ms 降下来），其余在等平台索引。
`11:08:53 SEVERE - SlowOperations … Plugin to blame: SQL Visualizer` 的栈是
`revalidateOnEdt → psi.isValid → WorkspaceFileIndexDataImpl.ensureIsUpToDate`（M7 修）。

## 四、验证

- 纯逻辑：缓存键生成（同 statement 不同 mtime 必 miss）、命中后 `ReferencesSearch` 不被调用（用可注入
  的检索缝计数）。
- 真机：同一语句**连续打开两次**，第二次的 `[open-perf] m2=` 应接近 0；改一行调用点代码后再打开应 miss。
- 反向：摘掉缓存命中分支 → 第二次仍应打满检索耗时（用例断言计数）。

## 五、边界（不做）

- 不引入后台线程/异步渲染改变交互（预览是同步弹窗，异步会让「点开即见 SQL」退化）。
- 不加文件监听器（常驻成本大于本收益；按 mtime 比 stamp 即可）。
- 不动 `DatabaseSchemaResolver` / 平台模型保存（M4 仅在有读数支撑时才碰）。

## 六、待验证前提

- **V1** 用户大工程里 `ReferencesSearch` 的实际耗时（需一次真机 `[open-perf]`，最好给「重开」读数）。
- **V2** 调用点所在 java 文件与 mapper 文件的 mtime 是否足够作失效判据（跨模块引用、生成代码场景）。
- **V3** 冷态 5.67s 是否真与首次索引绑定（M3 的 `isDumb` 标记能直接回答）。

## 七、实施记录

### M5 / M7 / M8 / M9（真机读数驱动）

- **M5（`919d2806f`）** `findCallSite` 改 `findFirst()` 提前终止。真机 `m2` 由 **1950ms → 378ms**。
- **M7（`0f9b850e2`）** 执行链 `revalidateOnEdt` → `revalidate`：`psi.isValid` 会触发
  `WorkspaceFileIndexDataImpl.ensureIsUpToDate`（平台慢操作），在 EDT 上调即被记
  「Plugin to blame: SQL Visualizer」（真机 11:08:53 SEVERE 栈实证）。改后台校验，EDT 只投递回调。
- **M8（`f09cba088`）** `DumbService.isDumb` 时跳过 M2，退回占位骨架（冷态不再同步等索引）。
- **M9（`305ae0d08`）** `PreviewResult.retryWhenIndexReady` + 面板 `runWhenSmart`：dumb 期先出骨架，
  smart mode 后 BGT 重算并自动重放 `showResult`（新结果闭包为 null 故不自激；dispose 后放弃）。
- 定向回归：`XmlPreviewServiceTest` / `XmlEwResolverTest` / `MbgExamplePreviewTest` /
  `SqlPreviewPanelXmlDefaultsTest` / `MbgExampleCallCacheTest` / `SqlPreviewPanelParamSchemesTest` 全绿。
- 踩坑记录：改 `PreviewResult` 构造签名后测试侧旧字节码报 `NoSuchMethodError`（LESSONS L21/L33）——
  用 `compileTestKotlin --rerun-tasks` 重编解决，不要手删 `build/classes`。

### M1（`9e62f922a`）缓存落地

- key＝`project.locationHash#namespace#statementId`（本 object 是进程级，多项目窗口防撞车）；
  失效判据＝**解析时实际读过的文件**的 `modificationStamp`（mapper 接口 / Example 方法 / 调用点文件），
  不引文件监听；
- `compute` 返回 null（解析不到）**不写缓存**——避免把「当时索引未就绪 / 找不到调用点」永久固化；
- 缓存外壳抽成纯逻辑 `resolveCached` + `searchRuns` / `cacheHits` 计数，PSI 解析与缓存解耦；
- 用例 3 例（`MbgExampleCallCacheTest`）：第二次命中只跑一次 compute / 不同 key 互不命中 / null 不写缓存；
- 反向：摘掉命中分支 → 「第二次命中缓存_不再执行检索」恰 1 红。

**not run（测试环境结构性限制）**：`ReferencesSearch` 在 Heavy 测试项目里命中 0——诊断读数

```
mapper=com.example.UserMapper method=selectByExample **refs=0**
example=com.example.UserExample criteria=com.example.UserExample.Criteria
andMethod=andCustIdEqualTo firstCall=addCriterion
```

fixture 其余五段全对，只有引用索引未建（同族：`pitfall-filetypeindex-vs-disk-test-files`）。
⇒「依赖文件改动后失效」「真机收益」两条只能真机验，判据：

1. 同一语句**连续打开两次**，第二次 `[open-perf] m2=` 应接近 0（首次仍约 200ms）；
2. 改一行调用点代码后再打开，应重新出现较大的 `m2=`（失效生效）。

### M2/M3/M4

未实施：M2 的失效路径待真机 V2 结论；M3（冷热标记）视是否还需要解释 5.67s；M4 视 M1 后余量。

## 八、端到端验收（2026-11-12，`mybatis-plus-sql-visualizer-demo/cust-big-demo`）

沙盒：`build/MPSV-E2EAccept`（IU-2024.3.7.1，`runIde -PgifRobotServer=true`），在役 jar 先按 L35
用 javap/grep 核过字节（`MbgExampleCallResolver.cache/cacheHits/searchRuns/resolveCached`、
`DataSourceSelectorResultsKt.revalidate`（`revalidateOnEdt` 已无）、`PreviewResult.retryWhenIndexReady`、
`XmlPreviewService` 里的 `DumbService.isDumb` + `m2 skipped: dumb mode`）——**被测树＝已推树**。

### 已验证（读数来自 `idea.log`）

| 项 | 读数 | 结论 |
| --- | --- | --- |
| 首次打开 `selectByExample`（多模块大工程） | `[open-perf] total=204ms parse+ctx=54 profile=1 m2=134 model=15`；`request→dialog: 607ms` | 装配 204ms，其余在面板/控制台预热 |
| 同语句再开（第 2/3/4 次） | `request→dialog: 172ms / 175ms / 34ms` | 热态稳定在 200ms 级 |
| 慢操作告警 | 整场 `grep -c SEVERE` = **0**、`Plugin to blame` = **0** | M7 生效（原 `revalidateOnEdt` 栈不再出现） |
| 默认勾选档（P7） | `distinct` 未勾、`orderByClause != null` 未勾、`criterion.noValue/betweenValue/listValue` 未勾、仅 `criterion.singleValue` 勾 | 与 `MBG_EXAMPLE` 档一致，无全勾 |
| 粘连 | 产物 `WHERE ( {{ criterion.condition }} ? )`，多 Criterion 时无 `andand` / `)or(` | T1/Trim 分隔修复在真机成立 |

> 说明：第 2 次起没有 `[open-perf]` 行，是 `PERF_LOG_THRESHOLD_MS=200` 把 <200ms 的读数吞掉了——
> 而「第二次 m2 应接近 0」正是 M1 唯一可证伪的读数。已改为 **MBG 档恒记**（`logPreviewPerf(always=profile!=null)`）。

### 缺陷 D1（已修，待真机复验）：真机上 M2 恒不命中

埋点读数（本轮新增 `[m2]` 诊断）：

```
[m2] trace cn.song.custmetadata.mapper.CustAddressMapper#selectByExample methods=1 noCallSite  -> criteria=0
[m2] unresolved（不写缓存） 5c317272#cn.song.custmetadata.mapper.CustAddressMapper#selectByExample
[open-perf] total=166ms parse+ctx=50 profile=1 m2=101 model=14
```

- 失败环节＝`findCallSite`：`ReferencesSearch.search(method, projectScope).findFirst()` 返回 null。
- 反向证据：同刻在 IDE 里对同一方法 `Alt+F7` 得 **253 处引用**，索引健康检查也干净 ⇒ 索引没坏，
  是 **M5 换上的 `Query.findFirst()` 在这条链上不可靠**（M5 只在 Heavy 测试里跑过，而那里
  `refs=0`——从未被真机证过）。
- 修法：改 `forEach { hit = it; false }` 取首个命中（语义等价、仍提前终止），空结果时再打
  `totalRefs=` 诊断，把「索引没建」与「检索语义不同」分开。
- 影响面：所有 MBG Example 语句的 Criterion 反推；不修则真机上永远停在占位骨架。

### 缺陷 D2（已修，待真机复验）：选区落在语句内部时按快捷键报「未找到」

复现路径：`Ctrl+F` 命中 `selectByExample`（命中即选中）→ `Alt+Shift+Q` → 气泡「未在光标处找到可预览的 SQL 源」；
把光标换成**点击**（无选区）到同一行/语句体内再按快捷键即正常。

- 根因：`previewXmlSelection` 按「语句 startOffset 落在选区内」收候选，选中语句 id 时 0 命中 →
  旧行为直接 `showResult(null)`。
- 修法：0 候选时回落 `previewXmlStatement`（光标所在语句），多候选逻辑不变。

### 缺陷 D3（已修）：M7 改了线程契约，源码守卫没跟上 → CI 红

CI `43149573766`（推送测试 @ `6775ba22b`）唯一红 job 是 fast(1)：

```
ExecTempFileEdtGuardTest > 执行临时文件构建必须离开EDT且续接收口齐备 FAILED
AssertionError at ExecTempFileEdtGuardTest.kt:143
```

M7 把 `revalidateOnEdt` 改成后台执行的 `revalidate`，而守卫仍按旧名定位函数体 → 定位失败即点名
「PSI 失效兜底消失」。按「改名/搬家后守卫必须同步跟进」的原设计更新契约：`revalidate(` 必须在
`invokeLater(` **之前**（放进回调就是在 EDT 上判 `psi.isValid`，正是那条 `Plugin to blame` 的栈），
`revalidate` 体内仍是两条 `ReadAction.compute` + `isValid`；自证语料加一条「挪回 invokeLater 必红」反例。
反向：把生产 `revalidate(project, pair)` 换成 `pair` → 恰 1 红，还原后回绿（`511ad990a`）。

### 缺陷 D4（已修）：同一方法里两个 Example 的条件被并成一条 WHERE

用户真机验收（2026-11-12 12:57，沙盒 `MPSV-E2EAccept`，jar 12:49 含 D1 修复）：

```
[m2] trace …CustAddressMapper#selectByExample methods=1 (and/or=5 noResolve=0 notExample=0 notRooted=0 noCond=0) [example06A:5] -> criteria=5
[m2] resolved criteria=5 stamps=2
[open-perf] total=356ms parse+ctx=35 profile=1 m2=306 model=14
[T-PreviewFast] request→dialog: 747ms
```

`[example06A:5]` 就是病灶：MBG 生成的服务实现一个方法里有两条链（`example06A.createCriteria().andX()` …
`selectByExample(example06A)`；`example06B.createCriteria().andY()` … `selectByExample(example06B)`），
旧判定 `rootedAtExample` 的兜底是「变量**类型名**以 Example / Criteria 结尾」，于是 06A 的检索把 06B 的
两条也收了进去，产物拼成

```sql
WHERE (status = ? AND cust_name LIKE ? AND tenant_id = ? AND create_time BETWEEN ? AND tenant_id = ?)
```

——两个 Example 的 AND 条件合成一条永不成立的谓词（`tenant_id` 出现两次）。

- 修法：只认**同一对象**——根引用名等于实参变量名，或根变量是局部别名且其**初始化表达式**追溯后落在
  同一个 example 变量上（覆盖 `Criteria c = example.createCriteria(); c.andX()`）；类型名兜底整条删除。
- 用例：`MbgExampleRootMatchTest`（3 例）——同名链收（含 alias 派生）、别家链弃、未知名全弃。
- 反向：把兜底恢复成「按类型名收」→ 3 例全红；还原后回绿。

### 真机验收读数（用户本人，2026-11-12 12:57~12:58）

| 次数 | `[m2]` | `[open-perf]` | `request→dialog` |
| --- | --- | --- | --- |
| 首次 | `resolved criteria=5` | `total=356ms … m2=306` | 747ms |
| 第 2 次 | `cache hit criteria=5` | `total=7ms … m2=0` | 104ms |
| 第 3 次 | `cache hit` | `total=5ms … m2=0` | 198ms |

⇒ **M1 缓存命中在真机成立**（m2 306ms → 0，装配 356ms → 7ms），「打开慢」的来源从「每次全项目检索」
变成「首次全项目检索 + 之后 0 成本」。用户原话「效果还不错 比之前快」。

### 缺陷 D5（已修）：混 kind 时 between 条件被单值模板渲染，丢掉第二个占位

用户第二次复验（2026-11-12 13:11，`[m2] resolved criteria=2`）产物是：

```sql
WHERE (create_time BETWEEN '11' AND tenant_id = ?)
```

模型层三路探针（`MbgExamplePreviewTest`，同一 XML 模板 + 手工注入 mock）：

| 注入 | 产物 | 占位数 |
| --- | --- | --- |
| 只有 `create_time between` | `create_time between ? and ?` | 2 ✓ |
| `tenant_id =` + `between` | `tenant_id = ? and create_time between ?` | 2 ✗（应 3） |
| `between` + `tenant_id =` | `create_time between ? and tenant_id = ?` | 2 ✗（应 3） |

⇒ 只要**同批混了两种 kind**，between 就少一个占位（`and #{criterion.secondValue}` 整段消失），
真机上那个 `'11'` 就是「一个占位 + 面板回填」的残骸。

- 根因：`defaultState()` 把 choose 组按「组内只开一个」强制成**单一分支**（多数票），而
  `renderSql` 又把 `enabledIds` 之外的 id 全部覆盖成 false——于是 between 条件被 `singleValue`
  模板渲染（`${condition} #{value}`），第二个占位连同中间的字面 `and` 一起丢。
- 修法（数据优先）：M2 注入了元素级 kind 时，`KIND_FLAGS` 那四个分支**不再接受开关强制**，交给表达式
  按每条 criterion 自己的标志求值（`kindDrivenConditionIds()`）；其它形态的 choose 不受影响。
- 用例：`M2混kind_每条criterion按自身分支渲染`（17 例类内新增 1 例，三种注入形态 + 占位数 3/2）。
- 反向：让 `kindDrivenConditionIds()` 返回空集 → 恰 1 红（17 tests, 1 failed）；还原后回绿。
- 同链定向：`xml.*` + `MbgExampleRootMatchTest` / `MbgExampleCallCacheTest` /
  `SqlPreviewPanelXmlDefaultsTest` / `XmlPreviewServiceTest` / `SqlPreviewPanelParamSchemesTest` 全绿。

### 未验证 / 交回用户

- D1/D2 修复后的真机复验（本轮改完只跑了定向测试，沙盒已按用户要求停手）。
- M1 缓存命中读数（依赖 D1 修好——`unresolved` 不写缓存，所以本轮第二次打开必然重跑检索）。
- 用户真机口径：Maven 已导入 + 有 JDK 的工程（本沙盒没点「Load Maven Project」，`Project JDK is not defined`）。
