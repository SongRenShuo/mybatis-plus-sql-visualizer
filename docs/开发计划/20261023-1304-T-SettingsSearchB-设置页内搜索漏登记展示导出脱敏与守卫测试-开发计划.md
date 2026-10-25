# T-SettingsSearchB：设置页内搜索漏登记「展示与导出脱敏」并补防复发守卫

2026-10-23。来源：用户报「插件设置页里搜不到『展示与导出脱敏』这项设置」。本体是设置页自带搜索框（非 IDE 设置搜索，后者另立 T-SettingsSearchA）。

## 一、现象与实测

设置页左上是插件自己的搜索框（`PluginSettingsFolderConfigurable.kt:50` 的 `SearchTextField`），输入关键词后列表显示匹配的「子页面」与「设置项」两类条目（`:110-143` `rebuildList`）。实测在框里输入「脱敏」「展示」「导出」「打码」均无该条目出现，只有「增强捕获-脱敏」那条会命中。

控件本体很正常：`PluginSettingsPanel.kt:253` 定义 `displayMaskCheck`（JBCheckBox），`PluginSettingsPanelSections.kt:597-601` 用 `addRow` 摆进 SQL 日志分组、文本与 tooltip 都从 bundle 取；中英双语键齐备（`MpSqlVisualizerBundle_zh.properties:528-529`、`MpSqlVisualizerBundle.properties:553-554`）。也就是说设置本身能用、能存，只是「搜不到」。

## 二、根因（代码证据）

搜索索引不是扫描出来的，是本文件里一份手写的硬编码清单 `ALL_SEARCHABLE_FIELDS`（`:547`，由 `buildSearchableFields()` 在 `:549` 起逐条 `fields.add` 构建）。命中判定只比 `searchLabel` 与 `searchTooltip` 两个字段（`:131-142`）。

SQL 日志分组那段登记到 `:614` 的 `settings.enhancedCapture.desensitize` 就结束了，紧接着 `:618` 的注释直接跳到「界面与操作」分组——`settings.display.mask` 在整份清单里零登记，所以任何关键词都翻不到它。

这不是偶发遗漏。历史提交 `cb55e546`（T-SettingsTruth P3）刚补录过 13 项真实未索引的设置，本轮 `settings.display.mask` 又漏了一条，属于持续漂移：每次往面板加控件，都得记得在同文件的清单里再加一行，而没有任何机制拦住漏加。

## 三、修法

`searchLabel` / `searchTooltip` / `pageLabelKey` / `ref` 四参照 `:613-614` 两条同构填，`pageLabelKey` 与 `ref` 都用 SQL 日志页的（`:585` 起的 `val l = PAGE_SQL_LOG.ref`，该变量在 `:614` 处仍在作用域内），在 `:614` 之后补一条：

```kotlin
fields.add(SearchableField(
    b.message("settings.display.mask"),
    b.message("settings.display.mask.tip"),
    "settings.section.sqlLog", l))
```

风险很低：`ALL_SEARCHABLE_FIELDS` 只有两处读取——`rebuildList`（`:131`）和 companion object 末尾的 `registeredTargetsForTest()`（供「id 与类指向同一设置页」守卫用），两处都是逐条遍历，对条数没有硬断言；`registeredTargetsForTest()` 里 `(ref.id to ref.cls)` 会 `distinct()`，新增条目的 ref 与既有 SQL 日志条目相同，去重后不增加断言项。`PluginSettingsFolderConfigurableTest.kt` 只断言默认列出 7 个子页面（`:68-72`）与无匹配时清空（`:75-83`），不受影响。

## 四、防复发守卫（本项重点）

漏登记的唯一拦截手段是测试。设计一条源码级守卫，把「面板上有控件」与「搜索索引里有条目」两件事绑起来：

拿 `PluginSettingsPanel(...).getRoot()` 遍历组件树，收集所有 `JCheckBox` 与 `JLabel` 的可见文本，断言每个非纯说明性的文本都能在 `buildSearchableFields()` 的结果里找到同 `searchLabel` 的条目。豁免面（分组说明、页脚链接、价值展示行那类不含输入的文本）走显式白名单，理由写进测试注释。

务必做成成对正反验证，否则又是一条空绿守卫：

- 正向：真实面板逐条跑过，全绿；
- 反向：临时删掉 `buildSearchableFields()` 里任意一条登记（例如 `settings.enhancedCapture.desensitize`），守卫必须变红；恢复后回到全绿、探针零残留。

正反两次读数都要留在实施记录里。合成样本只能证明规则看得见「我造的形态」，证明不了看得见「真实面板的形态」，所以正向这一半必须用真面板，不能用手搓的假组件树。

既有守卫 `SettingsRealityGuardTest` 的断言四是反方向的（bundle 里的 `settings.*` 键必须被源码引用，防幽灵文案），与本守卫互补：它拦「键没人用」，本守卫拦「控件没人搜」。

## 五、待验证前提（实施者先证伪再动手）

1. `focusTarget` 能否定位到该控件。跳转时 `focusHint` 取的是 `field.searchLabel`（`PluginSettingsFolderConfigurable.kt:148`），`findComponent` 对按钮分支判 `comp.text.contains(hint, ignoreCase = true)`（`:279-280`）。该 checkbox 的文本正是 `b.message("settings.display.mask")`（`PluginSettingsPanelSections.kt:598-599`），两串同源，按代码应当命中。但「应当命中」不等于「已命中」，实施时用现成的测试缝 `findHighlightTargetForTest`（`:299-300`）实测一次，别只靠读码下结论。
2. `pageLabelKey` 传 `settings.section.sqlLog` 是否与页面显示名一致。列表次行渲染的是 `MpSqlVisualizerBundle.message(pageLabelKey)`，与 `:613-614` 同参即可，属静态可核对项。
3. 守卫的白名单边界。真面板里的 `JLabel` 包含分组说明、价值展示行（`:94-97` 四行 HTML）、链接等，哪些该豁免需要按实际扫出来的清单逐条定，不能预先拍脑袋写死；豁免过宽就等于守卫失效。

## 六、验收口径

- 手动在设置页搜索框输入「脱敏」「展示」「导出」「打码」，四条都能命中「展示与导出脱敏」；点击/双击该条目能跳到 SQL 日志页并把该 checkbox 高亮。
- 新增守卫用例在真实面板上全绿；反向验证恰 1 红，恢复后全绿。
- 中英两种语言下都验一遍——英文面板的 checkbox 文本是 `Mask sensitive literals in display & export`，`focusHint` 同样取自 bundle，双语路径都要走通。

## 七、风险

- 几乎全在文档与测试层，生产改动一行。唯一可能的副作用是搜索列表多出一条，不影响其它条目排序与既有断言。
- 守卫测试若把面板控制实例化，注意 `createComponent` 的平台依赖。`PluginSettingsPanel` 的面板构建在既有测试里已被实例化过（`PluginSettingsPanelUiTest`、`PluginSettingsPanelBindingTest`），照它们的初始化方式走，别引入新的 EDT 依赖。

## 八、实施记录

2026-10-23 本会话实施完毕，已提交（见 §八.5）。

### 8.1 改动清单

- **生产**（`PluginSettingsFolderConfigurable.kt`，两处）：
  1. `buildSearchableFields()` SQL 日志段 `settings.enhancedCapture.desensitize` 之后补登记 `settings.display.mask`（label+tip 同键，`pageLabelKey=settings.section.sqlLog`、ref 复用同段 `l`，与 §三 修法一致）；
  2. companion 新增测试缝 `registeredSearchLabelsForTest()`（返回全部已登记 label，守卫配对用；紧邻既有 `registeredTargetsForTest()`）。
- **测试**（新增 `SettingsPanelSearchableGuardTest.kt`，4 用例）：
  1. `test面板输入型文本均能在搜索索引命中或列白名单`（守卫主体）：zh/en 两轮拿**真实** `PluginSettingsPanel(settings).getRoot()` 全量面板扫描 JToggleButton/JLabel 文本，剥 HTML+反转义后经**盘上双语 bundle** 反解回 key（语言无关，见 8.4-②），断言 `key ∈ 索引 ∪ 白名单 ∪ 组件豁免`；
  2. `test展示与导出脱敏四关键词可搜索命中`：真实搜索框设词触发 DocumentListener → rebuildList → 渲染器首行判等，「脱敏/展示/导出/打码」四词全命中；
  3. `test展示与导出脱敏hint在SQL日志页真实面板可定位`：zh/en 双语 hint 经 `findHighlightTargetForTest` 在 `SqlLogConfigurable` 真实面板定位到复选框本体；
  4. `test英文文案关键词数据层可命中`：`messageEn` 下 mask/display/export/sensitive 四词在 label/tip 可命中。

### 8.2 待验证前提逐一实证（§五）

1. **focusTarget 定位**：✅ 已实测成立（非仅读码推断）。断言三 zh/en 双语下 hint（=bundle 文案）都定位到 `JToggleButton` 本体，即用户双击条目后「跳转+高亮」链路可命中。
2. **pageLabelKey 一致性**：✅ 与 `:613-614` 同参 `settings.section.sqlLog`；断言二经渲染器断言次行显示页名成立。
3. **白名单边界**：按 §五.3 要求先实扫再定案。首轮实扫红 1 例（11 行违规），其中四类是预先没料到的：`settings.log.tab.sourceCapture` 分组标题漏枚举、R62 两条兜底外链（404 态才可见）、`settings.explain.retention.disclosure` 值含 `<br>` 剥标签后无法反解回键（改按组件身份豁免）、构建号行在无 MPV-Build 时的回退值「—」（纯占位符字形豁免）。定案五类豁免面见 8.3。

### 8.3 白名单定案（守卫测试内，逐条留痕）

- **① 分组标题**（12 键）：TitledSeparator 内嵌 label，纯视觉分节；
- **② 说明/披露/链接行**（9 键）：帧前缀指向说明、存储三说明+边界、EXPLAIN 隐私披露改走组件豁免（`<br>` 反解不通）、更新区 changelog 标题 + Gitee/需求池链接 + 404 态两条兜底外链；
- **③ 组合控件行标签**（10 键）：行内主控件或组已在索引（色板四行、快捷键后三行、执行超时、栈帧数、导入导出行、空参数填充组行标签等），标签不重复登记；
- **④ 既有未索引开关台账**（21 键）：首轮实扫坐实的存量缺口——paginationSim/paginationCount、explain.structured/retention、libraryEntitySweep、log.showDuration/dataSourceBadgeDetail/shardingsphere.enabled/autoInjectInterceptor、gutterGroup 六组、xmlSynHi、onboardingAnchorTip、update.autoCheck、emptyParamFill 三档与 timestampSource 两档单选选项。**登记与否另立任务；注释已写死「后续新增开关不得落此处，必须进 buildSearchableFields()」**；
- **⑤ 动态状态行**（11 个组件身份）：缓存信息/增强捕获状态/导入导出反馈/存储动作反馈/模式预览/推断状态/校验结果行/披露行/版本行/更新状态行/已安装行——文本运行期生成，无静态键可反解。

### 8.4 读数（正反双向 + 防回归，全部本会话实测）

- **正向**：`SettingsPanelSearchableGuardTest` 4/4 绿；同轮相邻类 `PluginSettingsFolderConfigurableTest` 11/11（含 `registeredTargetsForTest` 计数=8 断言——新条目 ref 与 SQL 日志段既有条目相同，distinct 后不加项，§三 预判成立）、`SettingsRealityGuardTest` 4/4（断言四幽灵键不受影响）。
- **反向 A**（§四 指定口径：删任意一条登记）：临时注释掉 `settings.enhancedCapture.desensitize` 登记 → **4 跑恰 1 红**，失败消息精确指向该条目的 zh/en 两条面板文本（`"增强捕获脱敏（对疑似敏感形态打码）"` / `"Enhanced capture desensitization…"`），其余 3 用例不受影响。
- **反向 B**（删本任务目标条目）：临时注释掉 `settings.display.mask` 登记 → **4 跑恰 2 红**：配对守卫精确报出 `"展示与导出脱敏（敏感字面量打码）"`/`"Mask sensitive literals in display & export"` 两条 + `test展示与导出脱敏四关键词可搜索命中` 在「脱敏」一词即红——两层防线都拦得住。
- **恢复**：还原登记后三类 19/19 全绿，`git diff` 确认生产文件探针零残留（仅剩正式改动）。
- §六 的真机手动验收（沙盒四关键词点跳）未做；headless 已覆盖同一代码链路（真实 DocumentListener→rebuildList→渲染器、真实组件树定位），真机走查与 §六 双语手动项留待验收批次。

### 8.5 相邻发现（未动生产，留档）

1. **④类 21 项既有未索引开关是真实缺口**：用户若搜「分页」「ShardingSphere」「gutter 分组」等同样翻不到。本计划范围只修 `display.mask`，台账已写入守卫测试 ④，另立任务时逐条搬进索引即可顺带删白名单。
2. **索引清单语言冻结**：`ALL_SEARCHABLE_FIELDS` 是 companion 一次性求值（类首触达时按当刻语言固化），而子页面条目（PageItem）在 `rebuildList` 里是活解析——用户切语言后 FieldItem 搜索结果停留旧语言直到重启。守卫测试因此改按盘上 bundle 做「文本→键」反解（语言无关）；产品侧是否把清单改成按查询时求值，另立任务。
3. **工作树遗留探针**：`src/test/.../psi/JarInResourcesLibProbeTest.kt`（前会话 W5 scratch，未跟踪）KDoc 里 `resources/lib/**` 的 `/**` 触发 Kotlin 嵌套注释语义 →「Unclosed comment」→ **整个测试源码集编译失败**，本任务首跑即被拦。已做最小修复（`lib/**（` → `lib 目录（`），文件保持未跟踪归还原会话处置；旁证 `JarMountProbeTest.kt` 亦未跟踪，未动。
