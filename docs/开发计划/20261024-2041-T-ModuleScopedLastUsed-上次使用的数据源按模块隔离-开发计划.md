# T-ModuleScopedLastUsed：「上次使用的数据源」按模块隔离（模块优先、项目级兜底）

> **创建日期**：2026-10-24 20:41
> **触发**：用户提问「sql 预览窗口右上角灰色的『上次使用』的数据源应该是全局的吧，是否应该按照模块隔离更合适？」
> **相关**：`T-DialectInferPollution`（W8~W12 跨模块错源系列）、`T-ExplainConsoleFirst` W7（跨源列证据）

---

## 一、先纠正前提：它不是全局，是**项目级**

`SqlPreviewPanelExecution.kt` / `DataSourceSelector.kt` 的 last-used 记忆落在：

```kotlin
// DataSourceSelector.kt:1293 / 1304
val pc = com.intellij.ide.util.PropertiesComponent.getInstance(project)
pc.setValue("mpsql.visualizer.last.runner", name)      // LAST_RUNNER_KEY
pc.setValue("mpsql.visualizer.last.runner.id", uuid)   // LAST_RUNNER_ID_KEY
```

`PropertiesComponent.getInstance(project)` = **项目作用域**（随 `.idea/workspace.xml`），同一项目内所有模块共享一份；
不同项目之间不共享。所以是「项目级全局」，不是「IDE 全局」。

## 二、现状：两套取源记忆并存，粒度不一致

| 机制 | 存储位置 | 粒度 | 消费方 |
|---|---|---|---|
| last-used 双键 | `PropertiesComponent(project)` | **项目级（一份）** | 指示行灰字（`bindStateText` 第二级）、执行/EXPLAIN 静默复用 |
| `ModuleDataSourceBindingStore` | `mp-sql-visualizer-datasource.xml` | **模块级**（稳定键 → UUID） | **只喂方言决策**（`Dialect.kt:964` `learnedCandidate`） |

关键事实：**模块级学习早就在跑**——`DataSourceSelectorConsole.kt:540` 在执行成功后调
`ModuleDataSourceBindingStore.remember(moduleKey, dataSourceId)`。但它只服务方言推断，
**没有参与「上次使用」这条更靠前的取源链路**，所以指示行灰字永远显示项目级那一份。

这就是用户观察到的现象：在 `jpa_demo` 模块的实体上执行并选了 `jpa_demo@localhost`，切到 `mp_demo`
模块的预览，灰字仍显示同一个源。

## 三、结论：按模块隔离更合适

三条理由：

1. **多模块工程的源本来就是按模块分的**。demo 仓即典型：`mp_demo` / `jpa_demo` / `mpj_demo`
   各连各库，跨模块共用一个 last-used 必然错配。
2. **这是本轮 W8~W12 那批错源的根因之一**。用户此前报的
   `mp_demo.wms_inventory_jar_demo doesn't exist`、`Unknown column 'first_name'`
   （`first_name` 属 `jpa_demo` 的 `user` 表），本质都是「模块 A 的 SQL 拿了模块 B 的源」。
   W7 的跨源列证据是**事后**纠错，模块隔离是**事前**不犯错。
3. **基建已就位**，不需要新造存储结构，只需把 last-used 从「项目级单键」升级为
   「按 moduleKey 分桶 + 项目级兜底」。

## 四、方案

### 4.1 分层记忆（三层，优先级从高到低）

```
① 活控制台绑定（boundConsoleDataSource）          —— 现状不变，最高优先
② 模块级 last-used（新）  mpsql.visualizer.last.runner.<moduleKey>
③ 项目级 last-used（现状） mpsql.visualizer.last.runner
```

读取：②命中即用；②未命中回落③（**不回落到「无」**）。
写入：执行/选源成功时**同时**写②（有 moduleKey 时）和③（保持既有语义，供无模块上下文时兜底）。

### 4.2 硬约束：moduleKey 缺失必须回落，不能硬隔离

`sourceModuleKey` 可能为 null（纯 `.sql` 文件、非 Java 源触发预览等入口）。这类入口若按模块硬隔离，
会退化成「每次都未绑定」——比现在更糟。故：

- moduleKey 为 null/blank → **只读写项目级**，行为与改前逐字一致；
- moduleKey 存在 → 读写模块级，**同时**更新项目级（作为其它入口的兜底）。

### 4.3 指示行文案要能看出层级

现在二级文案是「数据源（上次使用）：X」。模块化之后建议区分：

- 模块级命中 → 「数据源（本模块上次使用）：X」
- 项目级兜底 → 保留「数据源（上次使用）：X」

差异只在括号内的限定词，不新增控件（W12 定案：自研换源 UI 已全部退役，这里只改文案）。

### 4.4 失效清理

现有 `forgetLastRunner(project)` 要扩成 `forgetLastRunner(project, moduleKey)`：只清对应层级的键。
`ProjectPluginSettingsConfigurable.kt:345` 的「清空模块学习」入口要连带清 last-used（否则用户点了
「清空」，灰字仍显示旧源）。

## 五、工作项

| # | 内容 | 落点 |
|---|---|---|
| W1 | 新增 `LastUsedKey` 纯函数：`(moduleKey, tier) -> propertyKey`，含 null 回落的键选择 | `execute/DataSourceSelector.kt`（新增 internal object） |
| W2 | `lastUsedRunner` / `rememberRunner` / `forgetLastRunner` 加 moduleKey 参数，按 4.1 分层读写 | 同上 |
| W3 | 指示行 `bindStateText` 区分模块级/项目级文案（两处 i18n） | `ui/SqlPreviewPanelParamPanel.kt` + bundle |
| W4 | 「清空模块学习」连带清 last-used 双层级 | `config/ProjectPluginSettingsConfigurable.kt` |
| W5 | 守卫 + 纯逻辑表驱动测试（模块命中/回落/无模块/失效清理四个形态） | 新增测试类 |

## 六、待验证前提（先证伪再动码）

| # | 前提 | 证伪方式 |
|---|---|---|
| P-1 | `sourceModuleKey` 在预览打开时就已确定（不是渲染后才填） | 读 `SqlPreviewPanel.kt:1077` 赋值时序；确认 `bindStateText` 调用点晚于它 |
| P-2 | 执行链实际写入时能拿到同一个 moduleKey（不能是 null 而指示行有值，否则读写错位） | 追 `rememberRunner` 的所有调用点，确认 moduleKey 透传路径 |
| P-3 | 模块级键数量有界（不会因模块重命名/移动无限膨胀） | 确认 moduleKey 是稳定键（canonicalModuleKey）而非路径 |
| P-4 | 无模块上下文入口（纯 .sql）的现有行为不被改变 | 跑既有 bind-state 相关测试；对比改前后行为 |

## 七、验收判据

1. 模块 A 选源后，模块 B 的指示行不显示 A 的源（定向用例）。
2. moduleKey 为 null 时行为与改前逐字一致（回归用例）。
3. 三层优先级正确：活控制台 > 模块级 > 项目级。
4. 反向探针：删掉模块级读取分支 → 必须红。

---

## 八、实施记录

**2026-10-24 已实施，提交 `12755669`（未推送）。五个工作项全部落地。**

### 前提核验结果

| # | 结论 | 依据 |
|---|---|---|
| P-1 | **成立** | `setSourceContext` 在 `SqlPreviewDialog:59` 构造面板时调用，早于任何渲染/取源；`sourceModuleKey` 在 `SqlPreviewPanel.kt:1077` 赋值 |
| P-2 | **成立且无需新开参数** | `moduleKey` 已随 `ExecutionDialectGate.Request`（`:28` 字段）贯穿执行链；`DataSourceSelectorConsole.kt:536` 的模块学习写入点本来就从 `dialectGateRequest.moduleKey` 取 |
| P-3 | **成立** | `canonicalModuleKey` 来自 `ModuleIdentityResolution.Unique.identity.canonicalKey`（模块身份键，非路径） |
| P-4 | **成立** | moduleKey 为 null/blank 时 `LastUsedKeys` 返回原键，行为逐字不变；`testNoModuleKeyKeepsLegacyBehavior` 钉住 |

### 实施细节与两处计划外发现

1. **`PropertiesComponent` 没有「列举全部键」的 API**（`javap` 实证：只有 `unsetValue/getValue/setValue/getValues/setValues`）。计划里原打算用前缀扫描清全部模块桶，实际不可行 ⇒ 改由插件**自己登记模块键清单**（`mpsql.visualizer.last.runner.modules`，逗号分隔，写入时去重登记），`forgetAllLastUsed` 按清单精确 `unsetValue`。

2. **`bindStateText` 的「是否模块级」判定不能只靠读取返回值**：模块级写入时同时写项目级，两者值相同，光看名字分不出层级。故新增 `rememberedIsModuleScoped()`——比较「分层读取结果」与「纯项目级读取结果」，两者都命中且相同即判为模块级。只用于文案限定词，不参与任何取源决策。

### 工作项落地

| # | 状态 | 落点 |
|---|---|---|
| W1 | 完成 | `DataSourceSelector.LastUsedKeys`（`key/displayNameKey/dataSourceIdKey`，纯函数） |
| W2 | 完成 | `lastUsedRunner` / `rememberRunner` / `forgetLastRunner` / `lastRunnerName` / `rememberRunnerName` 全部加 `moduleKey` 参数；新增 `forgetAllLastUsed` / `registeredLastUsedModuleKeys`；读写调用点接线（`DataSourceSelectorConsole:420`、`DataSourceSelectorResults` 的静默复用/歧义清理/preferredRunner/popup 选择四处） |
| W3 | 完成 | `bindStateText` 分流 + `panel.bindstate.associatedModule` 中英双语 |
| W4 | 完成 | `ProjectPluginSettingsConfigurable.clearModuleBindings` 连带 `forgetAllLastUsed` |
| W5 | 完成 | `ModuleScopedLastUsedTest` 9 例（键构造/模块隔离/项目级兜底/模块优先/分层清理/全清/清单登记/取名入口/无模块键兼容） |

### 读数

- 与另一项合并跑定向 **6 类 65 / 0 / 0 / 0**（本项 9 例 + 既有 `SqlPreviewPanelBindStateTest` 9 例 + 回归 4 类）。
- 反向探针：把 `lastUsedRunner` 的分层读取退化成「只读项目级」→ **恰 3 红**（模块隔离、取名入口、分层清理三条）。已恢复。

### 遗留

- **沙盒 GUI 未走查**：需真机确认「jpa_demo 模块选源后，mp_demo 模块的灰字显示自己那条（或回落项目级）」。headless 只能证分层逻辑，证不了指示行在真实面板的刷新时序（`refreshBindStateRow` 的触达点未变）。
- 旧 jar 里已有的项目级记忆不受影响（键未改名），模块级桶从 0 开始积累。

---

## 九、沙盒 243 验收结果（2026-10-24 23:20 jar）

### 通过的项

灰字正确显示「数据源（**本模块**上次使用）：jpa_demo@localhost」——模块级文案生效。
执行计划选源弹出 **5 个源**（`jpa_demo` / `mp_demo`×2 / `kotlin_demo` / `selfdev_demo`），
不再恒 1 个，证明「选中即关联」短路问题在本路径上不存在。

### 验收抓出的缺陷：`rememberedIsModuleScoped` 误判（已修）

**症状**：真机 workspace.xml 里**只有**项目级键
`mpsql.visualizer.last.runner = jpa_demo@localhost`，**没有任何** `...runner.<moduleKey>` 键，
灰字却显示「数据源（本模块上次使用）」——把项目级兜底冒称成了模块级命中。

**根因**：首版判据是
`rememberedDataSourceName(proj, moduleKey) == rememberedDataSourceName(proj, null)`。
而前者在模块级为空时会**回落项目级**，于是两边恒相等 ⇒ 纯兜底被误判成模块级。

**修法**：改为**直接读模块级键本身是否有值**
（`pc.getValue(LastUsedKeys.dataSourceIdKey(moduleKey))` / `displayNameKey` 任一非空即为模块级）。

**护栏**：新增两条用例 `test仅有项目级记忆时不得冒称本模块` /
`test有模块级记忆时显示本模块文案`。反向验证：把判据还原成缺陷版 → **恰 1 红**（已实测）。

### 验收抓出的缺陷：last-used 清理不彻底（已修）

**症状**（测试暴露）：`SqlPreviewPanelBindStateTest` 的 `setUp/tearDown` 只清项目级双键，
上一条用例写入的**模块级键串到下一条**，导致「本条只有项目级记忆却 `isModule=true`」。

**根因**：`forgetLastRunner(project)` 不传 `moduleKey` 时只清项目级；`runRunner` 内部的两处
失败清理调用压根没有 moduleKey 可用。

**修法**：
1. `runRunner` 增加 `moduleKey` 可选参数，四处调用点（静默复用 / preferredRunner / 唯一 runner /
   弹窗选择）全部从 `dialectGateRequest?.moduleKey` 透传；静默失败清理改 `forgetLastRunner(project, moduleKey)`。
2. 复用身份被拒（`identity?.dsInFacade == false`）处同样带 moduleKey。
3. 测试 `setUp/tearDown` 改用 `forgetAllLastUsed`（进态也清一次，防上次运行残留）。

### 读数

- 定向 **10 类 102 / 0 / 0 / 0**（本项 9 例 + `SqlPreviewPanelBindStateTest` 11 例 + 回归 8 类）。
- 反向探针：模块隔离退化恰 3 红、模块级误判缺陷版恰 1 红。


