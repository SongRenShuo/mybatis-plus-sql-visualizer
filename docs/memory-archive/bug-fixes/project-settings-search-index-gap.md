---
name: project-settings-search-index-gap
description: 插件设置项为何在 IDE 设置搜索与插件页内搜索都搜不到——两道门 + 硬编码清单漏登记
metadata:
  node_type: memory
  type: project
  originSessionId: session_502360ae808bfb35c69ac231233cbe92
---

# 设置搜索索引缺口（20261023 子代理只读调查定案；**B 已实施** `e77dba02`）

两个搜索是两套完全独立的机制，都漏，原因不同。

## B：插件页内搜索漏项（**已实施并提交 `e77dba02`，未 push**）

20261023 T-SettingsSearchB 已落：`buildSearchableFields()` 补 `settings.display.mask` 登记 + companion 测试缝 `registeredSearchLabelsForTest()` + 新守卫 `SettingsPanelSearchableGuardTest`（4 用例：真实面板 zh/en 文本配对索引、四关键词真实搜索链路命中、hint 真面板可定位、英文数据层命中）。正反双向读数在计划 §实施记录：反向删 desensitize 恰 1 红、删 display.mask 恰 2 红、恢复 19/19 全绿。

实施中的三个新发现（详版见计划文档 §八.5）：
1. **守卫白名单里压着 21 项既有未索引开关台账**（paginationSim/分页计数、explain.structured/retention、libraryEntitySweep、showDuration、dataSourceBadgeDetail、shardingsphere、autoInjectInterceptor、gutterGroup 六组、xmlSynHi、onboardingAnchorTip、update.autoCheck、emptyParamFill 三档、timestampSource 两档单选）——搜「分页」「ShardingSphere」等仍翻不到，登记另立任务，登记后可同步删白名单。
2. **索引清单语言冻结**：`ALL_SEARCHABLE_FIELDS` 是 companion 一次性求值，切语言后 FieldItem 搜索结果停留旧语言直到重启（PageItem 是 rebuildList 活解析不受影响）；守卫测试因此按盘上 bundle 做「文本→键」反解。产品侧要不要改成按查询求值另立任务。
3. 遗留未跟踪探针 `psi/JarInResourcesLibProbeTest.kt`（前会话 W5）KDoc 内 `resources/lib/**` 触发 Kotlin **嵌套注释**→Unclosed comment→整个测试源码集编译失败；已最小注释修复使其可编译（文件仍归前会话处置）。

以下为调查原文（背景与 A 部分仍有效）：

页内搜索在 `PluginSettingsFolderConfigurable.kt`，索引来源是**手写硬编码清单** `buildSearchableFields()`（`ALL_SEARCHABLE_FIELDS`），`rebuildList()`（:110-142）只比对 `searchLabel` / `searchTooltip` 两字段。

漏项原因：`settings.display.mask`（展示与导出脱敏）**没登记进清单**——`PluginSettingsFolderConfigurable.kt:613-614` 有 `settings.enhancedCapture` 与 `settings.enhancedCapture.desensitize` 两条，之后直接跳到「界面与操作」，`settings.display.mask` 全列表零登记。控件本体是存在的（`PluginSettingsPanel.kt:253` 定义、`PluginSettingsPanelSections.kt:597-601` addRow），两处 addRow 参数无实质差异，**唯一差异就是少一行 add(SearchableField)**。

「Nothing to show」不是插件文案，是平台 `JBList` 空态（`StatusText` 默认文本，`UIBundle.properties:209`）；落地页的 JBList 没设 emptyText。

## A：IDE 自带设置搜索搜不到（**已实施并提交 `bb44fc73`，未 push**）

20261023 T-SettingsSearchA 已落：新增 `PluginSettingsSearchableOptionContributor` + `plugin.xml` 注册 `<search.optionContributor>` + `PluginSettingsFolderConfigurable.ideSearchEntries()`（与页内搜索共用 `ALL_SEARCHABLE_FIELDS` 单一数据源）。

**最重要的取证结论：9 个 Configurable 一行都没改。** 243 平台 `app-client.jar` 反编译实证：
- `ConfigurableWrapper` 声明 `implements SearchableConfigurable, ...`，`getId()` 首个分支即 `myEp.id != null ? myEp.id : cast(...)` → `instance=` 注册的页面天然满足门二；
- 门二真实实现点是 `SearchableOptionsRegistrarImplKt.filterById`（`instanceof SearchableConfigurable` + `getId()` 比 id 集合），不是 `SearchUtil`；
- 索引来源有两条：jar 内 `search/searchableOptions.xml`（构建期）**与运行期 EP**——`SearchableOptionsRegistrarImpl.doInitialize` 逐个调 `contributor.contribute(processor)`，故运行时 Contributor 完全够用；
- 新 UI 读同一套：`SettingsFilter` 持 `SearchableOptionsRegistrar` + `ConfigurableHit`。

**两处坐标勘误（计划里写错的）**：EP 接口真名 `com.intellij.ide.ui.search.SearchableOptionContributor`（非 `com.intellij.search`）；`addOptions(text, path, hit, configurableId, configurableDisplayName, applyStemming)` 形参名经 `LocalVariableTable` 逐字核对，**path 可 null**（平台自带 `PluginSearchableOptionContributor` 即传 null）。

**读数**：8/8 新增 + 相邻 17/17 = 25/25 全绿；反向摘 EP 恰 1 红、摘条目恰 2 红。**首版 EP 断言曾假绿**（注释里仍留标签字样 → 原文匹配命中），已改剥 XML 注释判定 + 正对照钉住该形态。

**未完成**：①真机 runIde 端到端（点跳转）未验；②计划 §五.1「按名路径为何失效」仍未定位（代码里 `getDisplayName().contains(option)` 确实存在）；③索引按插件语言生成（`message(...)`），英文 IDE + 中文插件语言时英文词不命中插件条目，双语索引留作增强。

以下为调查原文（门一/门二的背景仍有效）：

**不是单一开关**。`buildSearchableOptions=false` 只是两个必要前提之一：

1. 插件 jar 里没有 `search/searchableOptions.xml`——索引由**构建期** fork 一个 IDE 跑 `traverseUI`（`TraverseUIStarter`）生成；本仓 `build.gradle.kts:203-206` 置 false，实测 `build/tmp/buildSearchableOptions/`、`build/tmp/prepareJarSearchableOptions/` 皆空，`find . -name searchableOptions.xml` 全仓 0 命中。
2. **即便开了也搜不到**：平台 `SearchUtil.processConfigurables`（`SearchUtil.java:52-57`）对 `!(configurable instanceof SearchableConfigurable)` 直接 `continue`；`SearchableOptionsRegistrarImpl.filterById` 也会把非 `SearchableConfigurable` 过滤掉。本仓所有设置类都是**裸 `Configurable`**：`PluginSettingsFolderConfigurable.kt:48`、`SectionConfigurable.kt:27`、`McpConfigurable.kt:38`、`ProjectPluginSettingsConfigurable.kt:79`。

**修法 A1（推荐，不碰构建开关）**：新建 `SearchableOptionContributor` 实现（约 30-50 行），`processOptions` 里对每项调 `processor.addOptions(...)`，数据源复用页内搜索那份清单；`plugin.xml` 注册 `<search.optionContributor implementation="..."/>`；**同时**让目标 Configurable 实现 `SearchableConfigurable`（补 `getId()` 返回 plugin.xml 里的 id）——这步是硬要求，否则 `filterById` 仍过滤掉。改 6 个类（Folder + SectionConfigurable 基类覆盖 6 子页 + Mcp + Project）。
**修法 A2（改构建开关）**：`build.gradle.kts:206` 置 true + 同样必须实现 `SearchableConfigurable`。**风险高**：注释记录本机 Windows 中文环境实测会 `"Locale must be default"` 构建失败；且构建期遍历只认 JLabel/JCheckBox/JRadioButton/JButton/TitledBorder/JComboBox 的静态文本，落地页 JBList 内容索引不到。

## 两份计划已立项（20261023，提交 `c01dbc07`，索引登记 `3739a329`）

用户指令「按推荐把 B 和 A 立项生成开发计划文件并提交」：

- **`T-SettingsSearchB`**：`docs/开发计划/20261023-1304-T-SettingsSearchB-设置页内搜索漏登记展示导出脱敏与守卫测试-开发计划.md`（已于 `e77dba02` 实施）
- **`T-SettingsSearchA`**：`docs/开发计划/20261023-1304-T-SettingsSearchA-接入IDE自带设置搜索索引-开发计划.md`（已于 `bb44fc73` 实施；§九 实施记录含四条前提的逐条证伪表与两处坐标勘误）

**子代理实测订正**：bundle 中文键实际在 `MpSqlVisualizerBundle_zh.properties:528-529`（任务书写的 527-528 有误）；配置文件真名是 `ProjectPluginSettingsConfigurable.kt`，**无 `Plugin` 前缀**。

**A 新增的关键待验证前提**（务必先证伪再动手）：`plugin.xml` 用 `instance=` 注册，平台会把实例包进 `ConfigurableWrapper`，那处 `instanceof SearchableConfigurable` 究竟判包装层还是原始类，**直接决定要改 9 个类还是 1 个**——须以平台源码为准核对，别照推断先改 9 处。

**B 的跳转高亮链大概率能通**（子代理补充）：`findComponent` 对按钮分支判 `comp.text.contains(hint)`（`PluginSettingsFolderConfigurable.kt:279-280`），`focusHint` 取 `searchLabel`、checkbox 文本取同一 bundle 键，两串同源；仍要求用现成测试缝 `findHighlightTargetForTest`（`:299-300`）实测一次，不靠读码下结论。

## 待澄清

平台**按名命中**（`getConfigurables` 的 `displayName.contains(option)`）不依赖索引，理论上输「SQL Visualizer」应命中根节点，但实测搜不到 → 说明按名这路也没生效，与索引无关，需另查（设置对话框是否走了新 UI 搜索路径）。**设置项正文命中无论如何都要索引**，这条结论不受影响。

20261023 补：243 反编译确认按名路径的代码确实存在（`getDisplayName().contains(option)` 在 `getConfigurables` 里），且新 UI `SettingsFilter` 会读 `nameHits`/`nameFullHits` —— 所以「按名也不生效」不是路径缺失，根因仍未定位（疑与 `nameFullHits` 需整词命中或树过滤有关），留作独立调查项。

平台侧证据：早期取自 `ideaIC-2024.1-sources.jar`；A 项取证改用项目实际依赖的 243 平台 `ideaIU-2024.3.7.1-win/lib/app-client.jar` 反编译（`javap -p -c -l -v`），结论以字节码为准。

相关：[[project-docsrefresh1023-remaining-recordings]]
