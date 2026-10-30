# T-MapperNavCoexistYield —— Mapper 双向跳转图标与 MyBatisCodeHelperPro 冲突让位

- 任务编号：T-MapperNavCoexistYield
- 立项时间：2026-10-29 17:45
- 触发：用户真机报障（2026-10-29 17:2x）。截图 `OrderMapper.xml` 的 `select` 标签行（第 6 / 12 / 21 行）出现两个叠加的跳转图标——一个是 v2026.10.28 新增的 Mapper 双向跳转，一个是 MyBatisCodeHelperPro 自带的同一功能。
- 用户口径（原话）：「xml 与 mapper 调整功能的图标和 MyBatisCodeHelperPro 插件的冲突了，之前有规定 我们的插件需要让位给 MyBatisCodeHelperPro」
- 关联：v2026.10.28 发行（Mapper 双向跳转由外部贡献 PR 合入）→ 本缺陷随该功能引入；T-PerfSweep28-01 只补了组闸与 memo，未涉及竞品共存。

---

## 一、现象与根因

### 1.1 现象

- mapper XML 的 `select` / `insert` / `update` / `delete` 标签行与 Mapper 接口方法行各出 gutter 图标（`MpvsIcons.MapperStatement`），点击互相跳转。
- MyBatisCodeHelperPro（下称 MPJ）对同一位置也出跳转箭头。两者在同行叠加，用户看到「同一处两个图标」，分不清点哪个，且与既有「共存让位」纪律相悖。

### 1.2 根因（源码实证）

1. **两个 provider 没有任何竞品共存判定**。`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/MapperNavigationLineMarkerProviders.kt` 的两个 `collectSlowLineMarkers` 只有两道门：
   - `PluginSettings.getInstance().state.showGutterIcon`（全局总闸）
   - `GutterGroups.enabledFor(javaClass, gutterState)`（组显隐，两 provider 都登记在 `mapperXml` 组）
   门形齐全但只回答「用户要不要看」，不回答「别的插件是不是已经在画同一件事」。
2. **全仓目前没有「竞品在位」探测**。`ui/inject/XmlSynHiGate.kt` 的 KDoc 记明：T-XmlGap W1 已把「第三方 MyBatis 插件安装且启用即让位」这一条**同批废止**，改成 per-host 注入**区间级**探测——那是对注入/着色面的正解（在位 ≠ 覆盖），但它只覆盖注入面；gutter 图标不是区间问题，W1 的区间探测对「图标撞车」不可用。
3. **既有纪律确实存在**，只是没落到这个新功能上：`docs/内部/研发记录/20261012-公开仓去措辞-被移除的第三方插件实现细节存档.md` 记明 T-XmlSynHi 决策 1-A 的「双让位」——平台注入在场或第三方 MyBatis 注入插件在位（显式探测 `com.ccnode.codegenerator.MyBatisCodeHelperPro` + Profile `valid=true`）即本插件让位，2026-09-24 用户就共存问题确认过该修正。本次是把该纪律补到 gutter 面。

### 1.3 影响面

- 只影响装了 MPJ 的用户（本插件其余用户看不到差异）。
- 影响的是**跳转图标这一件事**：`MapperXmlToJavaLineMarkerProvider` / `MapperJavaToXmlLineMarkerProvider`。其余 gutter（XML 语句 SQL 预览、注解 SQL、JPA/HQL、JDBC、Wrapper 链）与 MPJ 不撞车，不在本次让位范围。

## 二、目标与非目标

**目标**

- 检测到 MPJ 处于启用状态时，上述两个 provider **不产出图标**；未启用时行为与现在逐字一致。
- 让位可被用户显式关闭（设置页开关，默认开＝让位），关掉后即使 MPJ 在位也显示本插件图标。

**非目标**

- 不动注入/着色链（`XmlSynHiGate` 的区间互补保留原样）。
- 不隐藏本插件的 SQL 预览图标（与 MPJ 不冲突）。
- 不改 MPJ 行为、不做任何跨插件写动作。
- 不做「按图标位置去重 / 探测 MPJ 是否真的画了」这类运行期推断——同行两个图标都合规，纪律就是让位，判据取「对方在位且启用」。

## 三、实施方案

### S1 竞品在位探测（新文件 `ui/gutter/MyBatisCompetitorGate.kt`）

- `object MyBatisCompetitorGate`：
  - `fun isCodeHelperProActive(): Boolean`——判据＝存在插件描述且 `pluginId.idString` 命中候选集合且 `isEnabled`。
  - 候选 id：`com.ccnode.codegenerator.MyBatisCodeHelperPro`；实现时**用前缀匹配 `com.ccnode.codegenerator`** 兜住 Marketplace 版与历史 id 变体，前缀表内联为字面量（源码扫描守卫要能看见）。
  - **禁用 `PluginId` 编译期依赖**：走反射 `Class.forName("com.intellij.ide.plugins.PluginManagerCore")` → `getPlugins()`，再反射 `getPluginId()` / `getIdString()` / `isEnabled()`。理由与 `update/PluginVersion.kt`、`execute/ExecuteSqlSupport.kt` 同款（该组 API 在 2025.3~2026.2 有形态漂移，`PluginId` 静态引用会把 243 线打到 verifier 的 internal/experimental 面上）。
  - **缓存**：插件启停需重启 IDE，故结果在 object 内 `@Volatile` 懒缓存；另留 `internal fun resetForTest()`。
  - **异常语义**：任何反射失败 → `false`（视为不在位，保留本插件功能）。不确定时不删功能，宁可多画一个图标。
  - **测试缝**：`internal var probe: (() -> Boolean)? = null`，测试直注在位/不在位/抛异常三形态。
- 纯判定函数 `internal fun shouldYield(yieldEnabled: Boolean, competitorActive: Boolean): Boolean`——`yieldEnabled && competitorActive`，真值表可单测（避免把平台依赖塞进单测）。

### S2 两个 provider 补门（`MapperNavigationLineMarkerProviders.kt`）

在 `collectSlowLineMarkers` 现有两道门之后补第三道：

```kotlin
if (MyBatisCompetitorGate.shouldYield(state.mapperNavYieldToCodeHelper, MyBatisCompetitorGate.isCodeHelperProActive())) return
```

- 位置在组闸之后、`elements.mapNotNullTo(...)` 之前：门判定是 O(1) 布尔（探测已缓存），不引入新的按元素开销。
- 两个 provider 同形，注释写明「本门是 gutter 面的共存让位，与 XmlSynHiGate 的注入区间互补是两件事」。

### S3 设置项（`config/PluginSettings.kt` + 设置页 + bundle）

- `PluginSettings.State` 增 `var mapperNavYieldToCodeHelper: Boolean = true`（默认开＝让位），KDoc 写清：开启时检测到 MyBatisCodeHelperPro 启用即不显示 Mapper 双向跳转图标；关闭则强制显示。
- 设置页放在 gutter 组开关同一区块（`SettingsConfigurable` 里紧随 `gutterGroup*` 之后），文案：
  - zh：`与同类 MyBatis 导航插件共存时让出 Mapper 跳转图标`（2026-10-29 用户反馈：点名竞品不如按「同类插件」表述，登记面在 `PEER_PLUGIN_ID_PREFIXES`）
  - en：`Yield Mapper jump icons when a peer MyBatis navigation plugin is enabled`
  - 双 bundle 同步（`MpSqlVisualizerBundle.properties` / `_zh.properties`）。
- **注意计数守卫**：设置页复选框总数断言（`PluginSettingsPersistenceTest` 一类）会因新增开关变红，需同步期望值——这不是可选项。

### S4 测试与反向验证

- 新建 `src/test/kotlin/.../ui/gutter/MapperNavCoexistGateTest.kt`：
  - `shouldYield` 真值表四档；
  - 探针三形态（在位/不在位/抛异常 → false）；
  - 反向验证记录：把 `shouldYield` 的竞品项短路成 false → 真值表恰 1 条红，恢复转绿。
- 扩 `MapperNavigationGutterGateTest`：竞品在位 + 让位开关开 → 两 provider 产 0 图标；竞品在位 + 让位关 → 图标数与基线一致；不在位 → 与既有断言逐字一致。
- 若新测试类承担守卫职责（类名含 Guard），须登记进 `convention/GuardSelfTestCoverageMetaGuardTest.kt` 的 `GUARD_CLASSES`——本次 `DialectInferFallbackGuardTest` 漏登记曾让全量门禁红一例，别再犯。

### S5 文档同步

- `docs/架构设计/01-项目架构总览.md` / `02-核心流程详解.md`：在 gutter 相关段落补一句「Mapper 双向跳转受共存让位门控，与 XmlSynHiGate 的注入区间互补分属两面」。
- `docs/索引/开发计划.md` 登记本册与状态。

## 四、待验证前提（实施前先证伪，不成立就改方案）

| # | 前提 | 证伪手段 |
|---|------|---------|
| P-1 | MPJ 的 pluginId 前缀确为 `com.ccnode.codegenerator`（含 Marketplace 版变体） | 读本机 `%APPDATA%\JetBrains\IntelliJIdea*\plugins\*\META-INF\plugin.xml` 与 `disabled_plugins.txt`，把真实 id 落进候选表；前缀匹配只是兜底 |
| P-2 | `PluginManagerCore.getPlugins()` / `PluginDescriptor.isEnabled()` 在 243/253/262 三目标可反射调用且返回 Enumerable | 三平台 jar `javap` 核签名；反射失败按 S1 异常语义降级 |
| P-3 | 用户真机上 MPJ 处于 `isEnabled`=true | 用户截图已证明它在出图标，视为成立；实施后由用户在真机复看一次 |
| P-4 | 冲突图标确为「两家各画一个」，而非本插件单个图标被画两次 | 真机对照：关掉本插件一个组开关看剩下的是不是 MPJ 的箭头；若发现是本插件重复画，改按 anchor 去重，不走让位 |

## 五、验收

- 定向：`MapperNavCoexistGateTest` + `MapperNavigationGutterGateTest` 全绿，反向验证读数贴进本册 §实施记录。
- 真机（用户 demo 已开）：装 MPJ 时 `OrderMapper.xml` 语句行只剩一套跳转图标；关掉让位开关后本插件图标回来。
- 回归红线：未装 MPJ 时图标数量与 2026.10.29 版逐字一致（`MapperNavigationGutterGateTest` 既有基线）。
- 不跑全量门禁（另有发版链门禁）；不占 O/R/D/V 编号（本册待编号）。

## 六、风险

| 风险 | 处置 |
|------|------|
| 反射探测在某个平台线失效 → 误判「不在位」 | 语义上只损失「让位」，不损失功能；日志 WARN 一次（`[T-MapperNavCoexistYield]` 前缀）便于排障 |
| 新增设置项打破设置页计数守卫 | S3 已写明同步期望值，定向跑 `PluginSettingsPersistenceTest` |
| 用户其实希望保留两套图标 | 设置项默认开、可关；文档与设置页文案说明「让位」含义 |
| 反例：真正撞车的是本插件的重复图标（P-4 不成立） | 立即改方案为 anchor 去重，本册 §实施记录写明转向依据 |

## 七、实施记录（2026-10-29）

### 7.1 P-1~P-4 实测结论

| # | 结论 | 读数与出处 |
|---|------|-----------|
| P-1 | **成立** | 本机 `%APPDATA%\JetBrains\IntelliJIdea2026.2\plugins\MyBatisCodeHelper-Pro\lib\instrumented-MyBatisCodeHelper-Pro241-3.6.8+2321.jar!/META-INF/plugin.xml`：`<id>com.ccnode.codegenerator.MyBatisCodeHelperPro</id>`、`<version>3.6.8+2321</version>`。前缀 `com.ccnode.codegenerator` 命中；未发现其它变体 id（2024.1 / 2024.3 / 2026.2 三个配置目录的插件目录同名，均为同一发行包形态） |
| P-2 | **成立（243/262 双端 javap；253 本地无发行物）** | 243（`ideaIU-2024.3.7.1`）：`app-client.jar` 的 `PluginManagerCore.getPlugins()` 返回 `IdeaPluginDescriptor[]`；`util.jar` 的 `com.intellij.openapi.extensions.PluginDescriptor` 有 `getPluginId()` / `isEnabled()`，`PluginId.getIdString()` 在场。262（`IU-262.10968.63`，`lib/intellij.platform.core.impl.jar` + `lib/util.jar`）签名逐字一致。253 本机无安装/缓存，未核；探测按「数组/Iterable 双兼容 + 异常降级 false」兜住 |
| P-3 | **成立** | 2026.2 的 `disabled_plugins.txt` 无 `ccnode` / `MyBatis` 条目，插件目录在场 ⇒ 启用；用户截图亦显示对方在出图标 |
| P-4 | **成立（静态证据；真机视觉复看待用户）** | MPJ `plugin.xml` 注册 `codeInsight.lineMarkerProvider language="XML" implementationClass="com.ccnode.codegenerator.view.J"`（另有 JAVA 两个、UAST 一个）；`javap` 实证 `J extends RelatedItemLineMarkerProvider` 且实现 `collectNavigationMarkers` ⇒ 跳转图标是**对方自己画的**，不是本插件重复绘制。故按原方案走让位，不改 anchor 去重 |

### 7.2 变更清单

- 新增 `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/MyBatisCompetitorGate.kt`：反射探测（`PluginManagerCore.getPlugins()` → `PluginDescriptor.getPluginId()/isEnabled()` → `PluginId.getIdString()`，方法解析在公开接口上避免 package-private 实现的 IllegalAccessException）、前缀候选、`@Volatile` 懒缓存、异常降级 false + WARN 一次、`probe` 测试缝、`resetForTest()`、纯判定 `shouldYield`。
- `ui/gutter/MapperNavigationLineMarkerProviders.kt`：两个 provider 在总闸/组闸之后补第三道共存让位门（O(1)，探针已缓存）。
- `config/PluginSettings.kt`：新增 `mapperNavYieldToCodeHelper: Boolean = true`。
- `config/PluginSettingsPanel.kt`：`mapperNavYieldCheck` 控件 + isModified/apply/reset 三处接线（漏挂 isModified 会复现「勾了但不生效」假开关）。
- `config/PluginSettingsPanelSections.kt`：界面节 gutter 组开关之后新增一行。
- `config/PluginSettingsFolderConfigurable.kt`：搜索索引补登记（新开关禁入白名单台账，按守卫要求进 `buildSearchableFields()`）。
- 中英 bundle：`settings.mapperNavYield` / `settings.mapperNavYield.tip` 双语同步。
- `src/test/.../ui/gutter/MapperNavCoexistGateTest.kt`（新）：真值表四档 + 探针三形态 + 结论缓存 + P-1 前缀回归锚。
- `src/test/.../ui/gutter/MapperNavigationGutterGateTest.kt`：新增「在位+让位开 → 两侧 0 图标」「在位+让位关 → 两侧基线 1」「不在位 → 基线 1」六条；setUp/tearDown 重置探测缓存。
- `src/test/.../config/PluginSettingsPersistenceTest.kt`：设置页复选框总数 46 → 47（新增让位开关）。
- 文档：`docs/架构设计/01-项目架构总览.md` gutter 注册表行、`02-核心流程详解.md` §3.1 后各补一句。

### 7.3 定向测试读数

| 测试类 | tests | failures | errors | skipped |
|---|---|---|---|---|
| `ui.gutter.MapperNavCoexistGateTest`（新） | 6 | 0 | 0 | 0 |
| `ui.gutter.MapperNavigationGutterGateTest`（扩充） | 11 | 0 | 0 | 0 |
| `ui.gutter.MapperNavigationLineMarkerProvidersTest`（回归） | 11 | 0 | 0 | 0 |
| `config.PluginSettingsPersistenceTest` | 18 | 0 | 0 | 0 |
| `config.SettingsPanelSearchableGuardTest` | 4 | 0 | 0 | 0 |
| `config.SettingsRealityGuardTest` | 4 | 0 | 0 | 0 |
| `config.SettingsIdeSearchContributorTest` | 8 | 0 | 0 | 0 |
| `config.SettingsTruthTest` | 10 | 0 | 0 | 0 |
| `config.PluginSettingsPanelBindingTest` | 16 | 0 | 0 | 0 |
| `config.PluginSettingsPanelUiTest` | 10 | 0 | 0 | 0 |
| `config.PluginSettingsFolderConfigurableTest` | 11 | 0 | 0 | 0 |
| `i18n.BundleParityGuardTest` | 5 | 0 | 0 | 0 |
| `convention.GuardSelfTestCoverageMetaGuardTest` | 8 | 0 | 0 | 0 |

首跑 `PluginSettingsPersistenceTest` 18 例全 `NoSuchMethodError`（`State.<init>(..., DefaultConstructorMarker)`）——新增 data class 属性改了合成构造器签名，测试字节码陈旧。`./gradlew compileTestKotlin --rerun-tasks` 后 18/0/0/0，非代码缺陷。

### 7.4 反向验证

- 探针：把 `shouldYield` 临时改成 `yieldEnabled && false`（竞品项短路）→ `MapperNavCoexistGateTest` + `MapperNavigationGutterGateTest` 合计 **17 例 4 红**：真值表 1 条（`(true,true)` 期望让位却返回 false）、探针在位用例 1 条（同时钉「在位 + 默认开关 → 让位」的组合断言）、两个 provider 的「在位+让位开」各 1 条（期望 0 图标实际 1）。
- 还原后复跑：**17 例 0 红**，两文件计数见 7.3。
- 与计划预估的差异：计划写「真值表恰 1 条红」，实际 4 条——多出的 3 条是同一断言的调用方（探针用例的组合断言 + 两个 provider 行为用例），不是守卫灵敏度问题。

### 7.5 未闭环

- **真机视觉复看**（需用户）：装 MPJ 的 IDE 里 `OrderMapper.xml` 语句行应只剩对方一套跳转箭头；关掉设置页新开关后本插件图标回来。
- 253 平台线反射形态未本地核验（无 253 发行物），靠异常降级兜底；若真机日志出现 `[T-MapperNavCoexistYield] competitor probe unavailable` 即说明该线失效，届时补探针形态。
- 全量门禁未跑（按任务约定只跑定向；发版链另有门禁）。

