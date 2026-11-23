# setEntity 条件参数键共享致「改一个全变 / 清除不即时」（T-SetEntityParamKeyCollision）-开发计划

- **日期**：2026-11-23 12:30
- **编号声明**：本册新占 **D255**（D254 册 :4 声明「下一全新 D255」；`grep -rn "D255" docs/` 除该指针外零占用）；下一全新 **D256 / V246 / O-164**。
- **来源**：用户 2026-11-23 真机反馈（两张截图）——`LambdaQueryWrapper.setEntity(query)` 场景下：①改左侧参数栏任意一个参数，右侧 SQL 里全部条件一起变；②点「清除」没有立即清干净，必须关掉预览重新打开才正常。用户要求先分析原因、生成开发计划。
- **范围**：`converter/QueryWrapperParser` 的 setEntity 静态展开 + 预览参数面板的 source 同步/清除链 + 同根因的 SET 全列展开点；只改参数键与面板同步，不改 SQL 文本渲染、不改执行链、不改左栏布局。

## 一、现象与复现（按用户截图逐步对照）

| # | 用户动作 | 实际观察 | 期望 |
|---|---|---|---|
| S1 | 打开 `setEntity(query)` 预览 | 左栏 15 个条件行（`id = ?` … `version = ?`），全部输入框显示同一个值（截图 1 全为 `1`） | 每个条件行一个独立输入框，各自独立取值 |
| S2 | 把 `id` 输入框改成 `2` | 右栏 15 个 `?` 全部变成 `2`（`password = '2'`、`age = 2` … 按列类型加引号），左栏其余 14 框仍显示 `1` | 只有 `id = 2`，其余条件仍为 `?` |
| S3 | 点「清除」 | 右栏回退为 `?`（部分场景），左栏仍有旧值残留；观感是「没清干净」 | 左栏 15 框立即全空，右栏全部回 `?`，无需重开 |
| S4 | 关闭预览，第二次打开 | 左栏全空、右栏 `?`，看起来「正常」 | 与 S3 的期望一致，清除不依赖重开 |

复现代码（用户提供）：

```java
public List<User> listByLambdaQuery2(User query) {
    LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.setEntity(query);
    queryWrapper.orderByDesc(User::getId);
    return this.list(queryWrapper);
}
```

## 二、现状核查（源码级实证，非推断）

| # | 事实 | 证据 |
|---|---|---|
| F1 | **setEntity 的静态展开把每列的值都写成匿名占位符 `"?"`**：15 列全部是 `Condition.Simple(col, "=", SqlValue.Placeholder("?"))`，即 15 条条件共享同一个 source 字符串 | `converter/QueryWrapperParser.kt:422-426`（`parseSetEntity`）；调用点 `:136-141` |
| F2 | 条件值来源直接取 `Placeholder.source` 当参数键：`valueSourcesOf(Condition.Simple)` 返回 `(value as? SqlValue.Placeholder)?.source`，因此 15 条条件的 `ConditionToggle.valueSources` 全是 `["?"]` | `converter/QueryWrapperParser.kt:764-776`、`:731-743`（`bindToggle`） |
| F3 | 参数面板为**每个 ConditionView 的每个 source 建一个输入框**，不按 source 去重：15 条条件各建一个 `JBTextField`，全部绑定同一个 `source="?"` | `ui/SqlPreviewPanelParamPanel.kt:1052`（`buildConditionRow`）、`:1099-1110`（`valueSources.forEach { conditionValueField(...) }`） |
| F4 | 输入框文本**只在构建时**从 `paramValues[source]` 读一次；改值时只写 `paramValues[source]` 并重渲染，不回写其他同 source 输入框 | `ui/SqlPreviewPanelParamPanel.kt:1104-1112`（`text = paramValues[source] ?: ""`）、`:1186-1190`（`onConditionValueChanged` 仅 `paramValues[source] = text`） |
| F5 | 参数回填按**位置**遍历 `?`，但取值按 **source 名**查 `paramValues`：`val v = source?.let { paramValues[it] }`。同名字段共享一个值 ⇒ 15 个 `?` 位置全部取到同一个输入值 | `ui/SqlPreviewPanelParamPanel.kt:2523-2546`（`backfillParamsBySource`，核心在 `:2538-2540`） |
| F6 | 「清除」对每个 source 调 `syncParamFieldText`，而该函数用 `firstOrNull` **只写第一个匹配字段**；15 框共享 `"?"` 时只有 `id` 框被清空，其余 14 框保留旧文本 | `ui/SqlPreviewPanelParamPanel.kt:1116-1160`（`clearConditionValues`）、`:2147-2153`（`syncParamFieldText` 的 `paramFields.firstOrNull { ... }?.text = value`） |
| F7 | 打开预览时的初始化顺序是：按 source 播种 `paramValues` → 历史召回 → **最后才重建左栏**。重建时字段文本按 `paramValues[source]` 读（F4），所以 `"?"` 这一个键的值会传播到全部 15 个框（截图 1 的「全部显示 1」= 上次输入值经历史召回写进 `paramValues["?"]`） | `ui/SqlPreviewPanelResultView.kt:194-196`（`putIfAbsent(valueSource, "")`）、`:251-256`（`recallFromHistory()` 后 `rebuildLeftPanel(result)`）、`ui/SqlPreviewPanelParamPanel.kt:2284-2361`（召回按 source 键写回） |
| F8 | 清除后状态已清、可见文本只清一个框 ⇒ 左右分叉；关掉重开时字段按已清空的 `paramValues` 重建，于是「第二次打开才正常」 | F4 + F6 + F7 合成的时序结果（无需额外代码） |
| F9 | **项目里本就有「实体变量.列名」的参数键惯例**：批量/CRUD 链用 `idSource(prefix, columnName) = "$prefix.$columnName"`，prefix 取调用首实参文本；导入参数的末段匹配也专门兼容这种形态 | `psi/BatchOperationConverter.kt:905-912`（`entityPrefix` / `idSource`）；`ui/SqlPreviewPanelParamPanel.kt:2133-2141`（`resolveSource` 第 4 条「末段唯一匹配」注释点名 `order.status`） |
| F10 | **每列的引号/日期提示本来就是独立的**，缺的不是类型信息，只是参数键：`renderValue` 逐位置 `paramSink.add(value.source)` + `paramHint(table, condition.column)` | `sql/SqlGenerator.kt:559-566`（`renderValue`）、`:493`（Between 逐值 hint） |
| F11 | **同根因的同类面**：`updatable(entity)` 全列 SET 与 `setUpdateEntity` 全列 SET 同样逐列生成 `Placeholder("?")`；参数面板对 SET 只出一个 `#?` 普通参数行（`unresolvedParams.distinct()`），一个值会填满所有 SET 列 | `converter/EasyQueryConverter.kt:311-314`、`converter/UpdateJoinConverter.kt:151-153`；面板侧 `ui/SqlPreviewPanelParamPanel.kt:386-397` |
| F12 | 现有测试只钉住 setEntity 的 **SQL 文本**，没有任何用例断言其参数 source；改名不会撞旧断言，但也意味着这条缺陷此前零覆盖 | `test/.../QueryWrapperParserSelectTest.kt:286-310`（`setEntity_展开实体全部列等值条件` 仅 `assertEquals` SQL 文本） |
| F13 | 参数历史缓存按键（含 source）存取，上限 50 条 LRU、单条无 TTL——旧键 `"?"` 改名后成为孤儿，会在 LRU 中自然淘汰，不需要迁移 | `ui/ParamHistoryCache.kt:12`、`:1023-1024` |

### 根因链

1. **键生成层**（F1/F2）：setEntity 只表达了「这里是个 `?`」，没表达「这个 `?` 属于哪一列」。逐列展开却共用匿名键，是链路的源头错误。
2. **面板承载层**（F3/F4）：面板对「同 source 共享值」的设计是**一个 source 一个输入框**，但条件行是按 `ConditionView` 逐行建的，于是出现「15 个框、1 个键」的错配；输入框文本单向（构建期读），运行期不互相同步。
3. **回填层**（F5）：回填按位置遍历、按名字取值，共享键让「一个输入」合法地扩散成「全部条件」——这是用户看到的「右侧全变」，不是渲染错位，而是同一个值被正确地用了 15 次。
4. **收敛层**（F6/F8）：清除/召回/导入都走 `syncParamFieldText`，其 `firstOrNull` 只同步首框，导致可见态与状态分叉；重开预览时 F7 用已清空的状态重建，才让分叉消失（「重开才正常」）。

一句话：**setEntity 给 15 个条件发了同一张工牌，面板按工牌取人，于是改一个人的工牌，15 个人一起换了身份；清除只按工牌改了名册第一行。**

## 三、影响面矩阵

| 消费者 | 读什么 | 现状症状 |
|---|---|---|
| 左栏动态条件行输入框（S1/S2） | `ConditionView.valueSources` + `paramValues[source]` | 15 框共享值，改一个全变 |
| 条件行 tooltip | `conditionValueField` 的 `"#$source"` | 显示 `#?`（无信息量） |
| 「清除」按钮（S3） | `clearConditionValues` → `syncParamFieldText` | 只清首框，其余残留 |
| 历史召回 / 参数预设（S1 初始值） | `paramHistoryCache` 按 source 存取 | 一个 `"?"` 值污染全部 setEntity 条件 |
| 参数导入（CSV/剪贴板） | `resolveSource` + `buildAllSources` | 导入 `id` 无法精确命中，只能靠末段匹配兜底/不命中 |
| SQL 回填 / 复制可执行 SQL | `backfillParamsBySource` | 15 个位置同值（S2 截图） |
| SET 全列展开（同类面） | `EasyQueryConverter` / `UpdateJoinConverter` 的 `Placeholder("?")` | `updatable(entity)`、`setUpdateEntity` 一个值填满所有 SET 列 |
| 执行/EXPLAIN | 取回填后的文本 | 被上述文本错误连带影响（用户会拿错 SQL 去执行） |

## 四、修复方案

### M1 根因修复：setEntity 逐列生成唯一参数键（1 次提交）

`QueryWrapperParser.parseSetEntity()` 改为接收当前 `WrapperCall`，按列生成带前缀的唯一 source：

- 前缀取值：`call.args.firstOrNull()` 为 `WrapperArg.ValueArg(Placeholder(source))` 时取 `source`（即源码里的实体变量，如 `query`）；求值成字面量或取不到时回退 `entity`。
- source 形如 `query.id`、`query.password`，**沿用 `BatchOperationConverter.idSource` 的 `实体变量.列名` 惯例**（F9），不新造风格。
- 同一 wrapper 链里多次 `setEntity`（MP 运行时是后者覆盖前者，静态近似会展开两份）：第二次起前缀加调用序号 `query#2.id`，保证键不撞。
- 条件文本与 hint 不变：`renderValue` 恒输出 `?`，`paramHint(table, column)` 仍按列给引号/日期提示（F10）——**SQL 文本必须逐字节与现状一致**，这是本方案的红线。

改后：15 条条件 15 个 `ConditionView.valueSources`，每行一个独立输入框；改 `id` 只替换 `id` 位置。

### M2 收敛修复：`syncParamFieldText` 同步全部同 source 字段（1 次提交）

`ui/SqlPreviewPanelParamPanel.kt:2147-2153` 的 `firstOrNull` 改为遍历全部匹配字段（`filter` + `forEach`，值相同不重复 setText）：

- 收益：清除（`clearConditionValues`）、历史召回（`recallFromHistory`）、参数导入（`importParams`）、批量清除（`clearBatchValues`）四条链一次收口；即使将来再出现「同 source 多框」，可见态也不会与 `paramValues` 分叉。
- 保留「同 source 共享值」的既有设计语义（同一表达式用于两列时共享一个值，见 `SqlPreviewPanelResultView.kt:194` 注释），只是让每个承载框都显示这个值。
- 条件项（P4）：是否在 `onConditionValueChanged` 里让同 source 的兄弟框实时跟随。M1 修好后正常场景已无同 source 多框，该联动只服务异常/未来场景；默认**不做**，避免光标跳动与事件递归，留作拍板项。

### M3 同类面：SET 全列展开的参数键（默认做，可拆分）

- `EasyQueryConverter.updatePreview`（`updatable(entity)` 全列 SET + 主键 WHERE）：SET 每列改为 `实体实参前缀.列名`，主键 WHERE 保持单条 `?`（或 `prefix.id`）。
- `UpdateJoinConverter` 的 `SET_UPDATE_ENTITY` / `SET_UPDATE_ENTITY_AND_NULL` 同改。
- `Ui/SqlPreviewService.kt:1537`（乐观锁 version 条件）与 `EasyQueryConverter.kt:596`、`SpecificationQueryConverter.kt:647` 的单值 `?` 只做审计：若同一条语句里再无第二个 `?`，键撞车不成立，不强制改。
- 判定标准与 M1 相同：SQL 文本零变化，只让每个 SET 列在参数面板拿到独立键。

### M4 附带确认（不改码）

- 条件行 tooltip `#?` 会随 M1 自动变成 `#query.id`（`:1104` 的 `"#$source"`），实施时用测试钉住即可。
- setEntity 条件行的**标签**保持 `id = ?` 不变（标签由 `SqlGenerator.renderConditionText` 生成，与 source 无关）；改标签属产品口径变更，不在本册。

### 不做

- 不改 SQL 渲染器输出的 `?` 形态与占位符顺序。
- 不给 setEntity 增加「实体级单值输入」的新控件（当前逐列输入是既有 UX，本册只修键撞车）。
- 不改执行链、EXPLAIN 链、MCP 工具 schema。
- 不清理历史缓存里的旧 `"?"` 键（F13，LRU 自然淘汰）。

## 五、待验证前提（实施者先证伪再动手）

| # | 前提 | 证伪/证实方式 |
|---|---|---|
| P1 | `Placeholder.source` 只影响参数键与 tooltip，不参与 SQL 文本渲染 | 改前先跑 `QueryWrapperParserSelectTest` 全类 + `SqlGenerator` 相关断言；M1 后再跑同一组，文本必须逐字节相同（F10、F12） |
| P2 | `setEntity(query)` 的实参在 ArgResolver 里确实落成 `ValueArg(Placeholder("query"))`（变量不可静态求值） | 用 PSI fixture 构造 `setEntity(query)` 打印 `WrapperCall.args`；若实际是 `Literal`，前缀走 `entity` 回退分支并补一条用例 |
| P3 | 新键 `query.<列名>` 不会触发集合命名启发式误提示 | `looksLikeCollectionName` 取末段判断（`ParamPanel.kt:1693-1702`），`status` 在例外词内；用一个「列名以 s 结尾」的夹具确认只是空提示文案，不影响渲染 |
| P4 | 同 source 多框在 M1 后是否只剩异常场景 | `grep` 全仓 `Placeholder("?")` 批量生成点，逐个判断是否会同链共存（M3 清单）；确认无第二条「共享键 + 多框」路径 |
| P5 | 历史缓存旧键孤儿不会无限增长 | `ParamHistoryCache.MAX_HISTORY_SIZE=50` LRU（F13）；无需迁移 |
| P6 | `setEntity` 的解析入口只有 `QueryWrapperParser` 一处 | `grep -rn "setEntity" src/main` 核对（当前仅解析器 + 注释） |

## 六、测试与反向验证

### 新增/补充测试

1. `QueryWrapperParserSetEntityParamKeyTest`（converter 包，纯单测）
   - 15 条 `ConditionView` 的 `valueSources` 两两不同，形如 `query.id`；
   - SQL 文本与修复前逐字节一致（把现值快照写死）；
   - 实参为不可命名形态（Literal）时回退 `entity.<列名>`；
   - 连续两次 `setEntity` 的键不互相覆盖。
2. `SqlPreviewPanelSetEntityParamTest`（ui 包，headless，走 `SqlPreviewService` + 面板测试设施）
   - 条件行输入框数量 = 条件数，`PROP_FIELD_SOURCE` 两两不同；
   - 只往 `query.id` 的框写 `2` → 产物仅 `id = 2`，其余仍 `?`；
   - 15 框都写不同值 → 产物逐列对应；
   - 调 `clearConditionValues()` → 所有 `conditionValueFields` 文本为空、`paramValues` 无 `query.*` 残留、产物全 `?`（不需要重建面板）。
3. `syncParamFieldText` 多框同步单测（不依赖 setEntity）：手工构造两个 clientProperty 相同的字段，断言两框都被写入。
4. 端到端回归：普通 `eq(User::getId, id)`、`between`、`in`、XML `#{}` 预览的清除与召回保持原状。

### 反向验证（用户 2026-11-21 定例：git 补丁方式，patch 放 temp 不进仓）

- 摘 M1（`parseSetEntity` 回到 `Placeholder("?")`）→ 测试 1 与测试 2 的「只换 id」用例必须变红，贴两次读数。
- 摘 M2（`syncParamFieldText` 回到 `firstOrNull`）→ 测试 3 必须变红；**注意**：M1 已修时测试 2 的清除用例不会因 M2 缺失变红（每 source 只剩一个框），所以 M2 的反向证据只能来自测试 3 的多框夹具——不得拿测试 2 的绿当 M2 有效性的证据。
- 反向完成后 `git checkout` 还原并复跑全绿，读数写回本册实施记录。

## 七、验收标准（用户真机，编号步骤）

前置：用用户提供的 `listByLambdaQuery2(User query)` 打开预览。

1. 左栏出现 15 个条件行，所有输入框初始为空（若历史有值，各框独立显示，不再同步成同一个数字）。
2. 只在 `id = ?` 的框输入 `2` → 右栏仅第 1 条变为 `id = 2`，其余 14 条仍是 `?`。
3. 给 `password` 填 `abc`、`age` 填 `18` → 右栏对应列分别为 `password = 'abc'`、`age = 18`，其余不串值。
4. 点「清除」→ **不关闭窗口**，左栏 15 个框立即全空，右栏全部回 `?`。
5. 关闭预览重新打开 → 左栏与右栏与第 4 步一致（不再需要「第二次打开才正常」）。
6. 回归：普通 lambda 查询（`eq(User::getId, id)` 等）与 XML 预览的填值、清除、历史召回行为不变。
7. 同类面抽查（若 M3 同批）：`updatable(entity)` 预览中两个 SET 列填不同值 → 右栏两列各自取自己的值。

## 八、待拍板事项（默认按推荐执行，用户可改）

| # | 事项 | 推荐 | 实施结果 |
|---|---|---|---|
| Q1 | M3（SET 全列同根因）是否同批修 | 同批做（改动小、同根因；若想缩批次可拆成独立册） | 已同批做（`f9aa1b375`） |
| Q2 | source 命名用实参表达式（`query.id`）还是固定 `entity.id` | 用实参表达式，沿用 `BatchOperationConverter` 惯例，且导入参数的末段匹配天然可用 | 按推荐：实参合法标识符/点链原样用，表达式文本回退 `entity` |
| Q3 | 是否让同 source 的兄弟框在输入时实时跟随（M2 条件项） | 不做；M1 后正常场景无此形态，避免光标跳动 | 未做（保留写回函数全量覆盖） |
| Q4 | 是否把条件行标签从 `id = ?` 改成 `query.id = ?` | 不改，保持现状（标签属产品口径，另行评估） | 未改 |

## 九、实施记录（实施期回填）

**落地提交（master，未 push）**

- `33661f779` M1+M2+测试：setEntity 逐列独立参数键、`syncParamFieldText` 全量写回、新增 `ParamSourceNaming` 与三组定向测试。
- `f9aa1b375` M3 同类面：`updatable(entity)` 与 `setUpdateEntity` 全列 SET 改用 `前缀.列名`。
- 本册回填与 `CHANGELOG.md` 随第三个提交落账。

**改动清单**

| 文件 | 改动 |
|---|---|
| `converter/ParamSourceNaming.kt`（新） | 前缀清洗（标识符/点链合法，表达式回退 `entity`）、`source()` 拼接、`uniquePrefix()` 序号去重 |
| `converter/QueryWrapperParser.kt` | `parseSetEntity(call)` 逐列 `Placeholder("${prefix}.${column}")`；`parse()` 重置 `setEntitySeq` |
| `converter/EasyQueryConverter.kt` | `updatable(entity)` 全列 SET 与主键 WHERE 改唯一键，前缀取 entry 首实参文本 |
| `converter/UpdateJoinConverter.kt` | `setUpdateEntity` / `setUpdateEntityAndNull` 全列 SET 改唯一键 |
| `ui/SqlPreviewPanelParamPanel.kt` | `syncParamFieldText` 由 `firstOrNull` 改为遍历全部同 source 字段（值相同不重复写） |

**定向测试读数（实际执行，非空跑）**

| 测试类 | 用例 | 读数 |
|---|---|---|
| `converter.ParamSourceNamingTest` | 6 | 6/0/0 |
| `converter.QueryWrapperParserSetEntityParamKeyTest` | 5 | 5/0/0 |
| `ui.SqlPreviewPanelSetEntityParamKeyTest` | 4 | 4/0/0 |
| 同类面回归 `UpdateJoinDeleteJoinConverterTest` | 11 | 11/0/0 |
| 同类面回归 `ui.EasyQueryConverterTest` | 13 | 13/0/0 |
| 既有 `QueryWrapperParserSelectTest`（setEntity 文本用例） | 18 | 18/0/0 |

**反向验证（git 补丁方式，patch 放 `.local/tmp` 用完即删）**

- M1：把 `parseSetEntity` 的值改回 `Placeholder("?")` → `QueryWrapperParserSetEntityParamKeyTest` 4 红（键唯一/前缀/回填来源三个断言 + SQL 用例的键断言）、`SqlPreviewPanelSetEntityParamKeyTest` 2 红（独立键/单框改值），合计 **9 tests / 6 failed**；还原后 9/0 绿。
- M2：把 `syncParamFieldText` 改回 `firstOrNull` → `SqlPreviewPanelSetEntityParamKeyTest` **4 tests / 1 failed**，命中用例 `testSyncParamFieldTextWritesEveryMatchingField`；还原后 4/0 绿。
- 说明：M2 反向时「清除全空」用例仍绿——M1 已让每个条件行只有自己的框，`firstOrNull` 也够用。M2 的有效性证据只能来自多框夹具，与 §六 的预设一致。

**已审计未改的同族面**

- `ui/SqlPreviewService.kt:1537`（乐观锁 version 条件）、`converter/EasyQueryConverter.kt:596`、`converter/jpa/SpecificationQueryConverter.kt:647`：单值兜底 `Placeholder("?")`，同语句内无第二个 `?` 时键撞车不成立，未改。
- `BatchOperationConverter` / `CrudSqlGenerator` 的批量参数键本来就是 `entity.column`，与本次命名一致，未改。

