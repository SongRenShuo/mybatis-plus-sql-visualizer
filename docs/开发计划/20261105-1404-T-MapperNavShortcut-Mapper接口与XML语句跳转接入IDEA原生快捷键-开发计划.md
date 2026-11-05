# T-MapperNavShortcut：Mapper 接口与 XML 语句跳转接入 IDEA 原生快捷键（2026-11-05）

- 立项：2026-11-05 14:04
- 目标分支：`master`（立项时 tip `8bf489ff`）
- 建议实施分支：`t-mapper-nav-shortcut`
- 需求原文：用户反馈「mapper 和 xml 跳转不支持快捷键，正常应该跟着 IDEA 原生的快捷键支持跳转」
- 需求归类：导航面补全（不改捕获、不改预览、不改落盘；不新增 gutter 图标；不新增 inspection/annotator）
- 关联既有券：T-XmlGap W7（`ui/ref/` 引用导航）、T-MapperNavCoexistYield（竞品共存让位）、T-PerfSweep28-01（Mapper 双跳 gutter 门控）

## 一、问题定位（先说结论）

现状是「鼠标能点、键盘不能按」：

| 场景 | 用户动作 | 现状 | 根因 |
|---|---|---|---|
| Mapper 接口方法 → XML 语句 | Ctrl+B / Ctrl+Click | **无反应** | 只有 gutter 图标（`GutterIconNavigationHandler`，鼠标专用），Java 侧零 `PsiReference`、零 `GotoDeclarationHandler` |
| XML 语句标签名 → 接口方法 | Ctrl+B / Ctrl+Click | **无反应** | 引用只挂在 `id` 属性值元素上，标签名不是引用宿主 |
| XML statement `id` 属性值 → 接口方法 | Ctrl+B | **已可用** | T-XmlGap W7 的 `MapperMethodReference`（soft 引用） |
| 接口方法 → Alt+F7 查找用法 | Alt+F7 | **已可用** | 同上，XML 侧引用反查命中方法 |
| 双向「关联符号」弹窗 | Ctrl+Alt+Home | **无** | 未注册 `GotoRelatedProvider`，且两个 provider 是裸 `LineMarkerProvider` 而非 `RelatedItemLineMarkerProvider` |

即：**XML→Java 的 `id` 值位置本来就跟着原生快捷键走，缺的是 Java→XML 整条方向，以及 XML 标签名这个更自然的落点。**

## 二、现状证据（源码与描述文件）

| 事实 | 位置 |
|---|---|
| XML→Java 跳转只有 gutter 图标，导航闭包是鼠标回调 | `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/MapperNavigationLineMarkerProviders.kt:57-68` |
| Java→XML 跳转同样只有 gutter 图标 | 同文件 `:107-119` |
| 两个 provider 继承 `LineMarkerProvider`（非 `RelatedItemLineMarkerProvider`） | 同文件 `:19`、`:73` |
| 引用贡献器只注册 `XmlAttributeValue`，`language="XML"` | `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/MybatisXmlReferenceContributor.kt:39-42` |
| statement `id` → 接口方法的引用分支 | 同文件 `:67-68`（`MapperMethodReference`） |
| namespace→XML 文件定位（单槽结果 memo + 清单 TTL 5s） | `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/MybatisXmlTargets.kt:94`、清单 `:282` |
| 语句 `id` 属性值查找 | 同文件 `:51` |
| 接口方法解析 | 同文件 `:177`（`resolveInterfaceMethod`） |
| 行号版定位器（日志跳转与 gutter 点击共用） | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/MapperXmlLocator.kt:41`、缓存 `:145` |
| 描述文件只注册了 `psi.referenceContributor language="XML"` 与两个 `codeInsight.lineMarkerProvider` | `src/main/resources/META-INF/plugin.xml:244-247`、`:423-424` |
| 全仓 `gotoDeclarationHandler` / `gotoRelatedProvider` 命中数 | **0**（`grep -rn` 实测） |
| 竞品让位开关（默认开，仅表述为「让出跳转图标」） | `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt:123`；判定 `ui/gutter/MyBatisCompetitorGate.kt:78` |
| gutter 组开关（mapperXml 组）只作用于图标 | `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/GutterGroups.kt:29-30` |

## 三、平台机制取证（双端实测，非推断）

### 3.1 原生快捷键与对应 Action

`$default.xml` 在 **2024.3.7.1（编译基线 243）与 2026.1（本机 261）双端逐字一致**：

| 快捷键 | Action | 说明 |
|---|---|---|
| `Ctrl+B` / `Ctrl+Click` | `GotoDeclaration` | 本次要接的主入口 |
| `Ctrl+Alt+B` / `Ctrl+Alt+Click` | `GotoImplementation` | MyBatis mapper 由动态代理实现，无实现类，语义不适用 |
| `Ctrl+Alt+Home` | `GotoRelated` | 「关联符号」弹窗，对应 `GotoRelatedProvider` |
| `Ctrl+Alt+F7` | `ShowUsages` | 走引用检索，现有 XML 侧引用已覆盖 |

### 3.2 扩展点声明（两端同名同签名）

| EP | 接口 | 声明位置 | dynamic |
|---|---|---|---|
| `gotoDeclarationHandler` | `com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler` | 243 `app-client.jar!/META-INF/Analysis.xml`；261/262 `intellij.platform.analysis.jar!/META-INF/Analysis.xml` | true |
| `gotoRelatedProvider` | `com.intellij.navigation.GotoRelatedProvider` | 243 `app-client.jar!/META-INF/LangExtensionPoints.xml`；261/262 `intellij.platform.ide.impl.jar!/META-INF/LangExtensionPoints.xml` | true |

接口签名（两端 javap 一致）：

```
GotoDeclarationHandler#getGotoDeclarationTargets(PsiElement, int, Editor): PsiElement[]
GotoDeclarationHandler#getActionText(DataContext): String?   // default 方法
GotoRelatedProvider#getItems(PsiElement): List<out GotoRelatedItem>
GotoRelatedProvider#getItems(DataContext): List<out GotoRelatedItem>
```

### 3.3 解析优先级（决定方案形态的关键）

javap 实证 `GotoDeclarationAction.findTargetElementsNoVS` 的顺序：

1. `GotoDeclarationUtil.findTargetElementsFromProviders(editor, offset, file)` —— **只遍历 `gotoDeclarationHandler`，首个返回非空数组的 handler 直接胜出**；
2. 上一步为空才回落 `TargetElementUtil.findTargetElement(...)` —— 这条链里 `PsiReference` 优先，其次才问元素自身。

由此得到三条结论，直接决定设计：

- **handler 优先于 reference**，两者指向同一目标时不会出现「双目标选择弹窗」；
- handler 返回 `null` 才回落原生行为，所以「非 Mapper 位置必须返回 null」是硬约束；
- handler 内部再慢也只是用户按键时的成本，不进入高亮/索引热路径。

## 四、方案

### 4.1 主方案：单个双向 `GotoDeclarationHandler`

新增 `ui/nav/MapperNavigationGotoDeclarationHandler.kt`，注册一条 `<gotoDeclarationHandler>`（EP 不分语言，一个类同时覆盖 Java 与 XML）。

**Java 侧判定链（全部 O(1) 前置，命中才查 XML）**

1. `sourceElement is PsiIdentifier`；
2. `parent is PsiMethod` 且 `method.nameIdentifier === sourceElement`；
3. `method.containingClass?.isInterface == true`（与 gutter 同口径，见 `MapperNavigationLineMarkerProviders.kt:103-104`）；
4. `qualifiedName` 非空 → `MybatisXmlTargets.resolveStatementTags(project, namespace, method.name)`；
5. 命中 → 返回 XML 语句的 `id` 值 token；未命中 → `null`。

**XML 侧判定链**

1. `sourceElement is XmlToken`，类型 ∈ {`XML_NAME`（起始标签名）, `XML_ATTRIBUTE_NAME`（`id` 属性名）, `XML_ATTRIBUTE_VALUE_TOKEN`（`id` 值）}；
2. 宿主标签名 ∈ `select/insert/update/delete`，且是 `<mapper>` 直接子级（复用 `XmlTagUtil.getStartTagNameElement` 排除闭标签，同 `MapperNavigationLineMarkerProviders.kt:51`）；
3. `MybatisXmlTargets.isMapperFile` + `rootNamespace` + `id` 属性值；
4. `resolveInterfaceMethod` 命中 → 返回 `PsiMethod`；否则 `null`。

**目标元素选择**

| 方向 | 返回元素 | 理由 |
|---|---|---|
| Java → XML | 语句 `id` 属性值 token | 光标精确落在语句 id 上；与 gutter 点击落在行首相比更贴近原生 MyBatis 体验 |
| XML → Java | `PsiMethod` | 平台把光标放到方法名，原生一致 |

同名 id 多 `databaseId` 变体时返回全部命中（平台自动弹选择器），单命中时返回单元素。

### 4.2 解析 API：复用为主，只加一个 PSI 版入口

- 新增 `MybatisXmlTargets.resolveStatementTags(project, namespace, id): List<XmlTag>`（返回全部同名标签，含 `databaseId` 变体） —— 复用现有 `findMapperFileByNamespace`（单槽 namespace memo + 清单 TTL 5s）与标签遍历，不新建缓存、不引监听器（红线维持）。
- **不改** `MapperXmlLocator`：它是行号版，服务日志跳转与 gutter 点击，返回 `Location(virtualFile, line)`；两套缓存的重复登记为后续券，本券不合并。
- 不新建索引、不加 `FileBasedIndex` 扩展。

### 4.3 Dumb 态与异常

- 入口 `runCatching { ... }.getOrNull()`；`DumbService.isDumb(project)` 时直接返回 `null`（`resolveInterfaceMethod` 走 `JavaPsiFacade.findClass`，dumb 态抛 `IndexNotReadyException`；本仓已有「dumb 直接抛 → 点了没反应」的既有教训）。
- 任何异常只让本次按键回落原生行为，不弹提示、不写 ERROR（`LOG.debug` 上限）。

### 4.4 让位语义（决策点 D-2）

**建议：键盘导航不参与 `mapperNavYieldToCodeHelper` 让位。**

- 该开关文案与实现语义都限定「让出 Mapper 跳转**图标**」（`PluginSettings.kt:116-123`）；
- handler 是「首个非空即返回」，与 MPJ 的 handler 指向同一目标，不存在同行双图标那样的视觉冲突，也不会产生双目标弹窗；
- 反过来若让位，一旦对方版本不覆盖某个位置（如标签名），快捷键会变成死键。

### 4.5 可选 W3：`Ctrl+Alt+Home`「关联符号」

新增 `ui/nav/MapperNavigationGotoRelatedProvider.kt`：Java 方法 → 返回 XML 语句项；XML 语句 → 返回接口方法项。`GotoRelatedItem(element, groupName)` 构造（javap 实证存在）。成本低，且与用户「跟着原生快捷键」的诉求同向，建议纳入本券。

### 4.6 快捷键归属：完全继承平台 action（用户 2026-11-05 明确要求）

本券**不注册任何 `<keyboard-shortcut>`**，只注册 EP。快捷键由平台 action 持有：

| 我们注册的东西 | 挂靠的平台 action | 用户改键位后 |
|---|---|---|
| `<gotoDeclarationHandler>` | `GotoDeclaration`（Navigate → Declaration or Usages → Declaration） | 跟着变：Ctrl+B 改绑成别的键、加第二快捷键、改 Ctrl+Click 鼠标快捷键，全部自动生效 |
| `<gotoRelatedProvider>` | `GotoRelated`（Navigate → Related Symbol） | 同上 |

判据：`Settings → Keymap` 里搜 Declaration，改完直接在我们的 Mapper 方法上按新键即可跳；插件侧没有任何独立键位需要同步修改，也不应出现在 Keymap 的插件分组里（EP 形态天然如此）。若用户在 Keymap 里解绑该 action，本功能随之不可达——与原生行为一致，不做兜底。

既有反例说明为什么必须写死这条：本插件自己的 `ConvertToSql` / `ConvertSqlToJava` 是自建 action，必须自带 `<keyboard-shortcut>`（`plugin.xml:477-478`、`:489-490`）；本券不走那条路。

### 4.7 明确不做

| 不做项 | 原因 |
|---|---|
| 把两个 gutter provider 改成 `RelatedItemLineMarkerProvider` | 会改变图标合并语义（与 MPJ 同行的图标会被平台合并成一个），回归面大；`Ctrl+Alt+Home` 由 `GotoRelatedProvider` 单独覆盖即可 |
| Rename 联动（改方法名同步改 XML `id`） | 属于重构面，风险与收益另立券 |
| `Ctrl+Alt+B`（实现） | MyBatis 无实现类，语义不成立 |
| 新增设置开关 | 行为是纯增量（无命中即回落原生），且新增布尔要同步搜索索引与复选框计数（47→48）两条硬约束，收益不足 |
| 报红/校验 | 既有红线：只产导航，不做校验器形态 |
| Kotlin mapper 接口 | 与 gutter 同口径只覆盖 Java（`plugin.xml:244-247` 是 `language="JAVA"`）；Kotlin 另立券 |

## 五、决策点

| 编号 | 议题 | 建议 | 备选 |
|---|---|---|---|
| D-1 | 是否把 `Ctrl+Alt+Home` 关联符号纳入本券 | **纳入**（W3，低成本） | 只做 Ctrl+B/Ctrl+Click |
| D-2 | 键盘导航是否跟随竞品让位开关 | **不跟随**（4.4 理由） | 跟随，与图标同门 |
| D-3 | 是否新增「Mapper 跳转」开关 | **不新增** | 新增布尔（须同步搜索索引与计数守卫） |

## 六、波次与写集

| 波次 | 内容 | 写集 | 验收判据 |
|---|---|---|---|
| W0 前提核验（不改码） | 在 243 沙盒与用户 2026.2 真机各做一次现状读数：Java 方法名 Ctrl+B、XML 标签名 Ctrl+B、XML `id` 值 Ctrl+B、Java 方法 Alt+F7 | 无（读数落本册 §八） | 三条现状与 §一 一致；若 `id` 值处 Ctrl+B 实际不可用，W2 的「已可用」前提改写为待修 |
| W1 Java → XML | 新增 handler + `resolveStatementTags` + EP 注册 | `ui/nav/MapperNavigationGotoDeclarationHandler.kt`（新）、`ui/ref/MybatisXmlTargets.kt`、`plugin.xml` | 方法名 Ctrl+B / Ctrl+Click 跳到对应语句 id；非接口/无 XML/非 mapper 一律回落原生 |
| W2 XML → Java | 同一 handler 覆盖标签名与 `id` 属性名/值 | 同 W1 文件 | 标签名 Ctrl+B 跳方法；`id` 值行为与现有引用一致；非 mapper XML 零影响 |
| W3（可选）关联符号 | `GotoRelatedProvider` + EP 注册 | `ui/nav/MapperNavigationGotoRelatedProvider.kt`（新）、`plugin.xml` | Ctrl+Alt+Home 弹出「Mapper 语句 / 接口方法」项并可跳转 |
| W4 收口 | 定向测试、反向验证、构建校验 | 测试目录 | 见 §七 |

W1 与 W2 写集重合，串行；W3 只依赖 W1 的解析 API，可并行。

## 七、测试与反向验证

### 7.1 定向用例（`BasePlatformTestCase`，夹具沿用 `MapperNavigationLineMarkerProvidersTest.kt:34-56` 的 `addFileToProject` 形态）

| 用例 | 断言 |
|---|---|
| Java 方法名 → 命中 | `getGotoDeclarationTargets` 返回 1 个元素，落在 XML 语句 `id` 值上 |
| Java 方法名 → 无对应 XML | 返回 `null`（回落原生） |
| 普通 interface 方法（无 mapper XML） | 返回 `null`，且不触发清单扫描计数（`MybatisXmlTargets.indexScanCount` 不增） |
| 非接口类方法 | 返回 `null` |
| XML 起始标签名 → 命中 | 返回 `PsiMethod` |
| XML 闭标签名 | 返回 `null`（不得把 `</select>` 当入口） |
| XML `id` 属性值 | 返回 `PsiMethod`，与现有 `MapperMethodReference.resolve()` 同目标 |
| 非 mapper 根 XML | 返回 `null` |
| `<sql id>` 片段标签 | 返回 `null`（片段无方法） |
| 同名 id 双 `databaseId` | 返回 2 个目标 |
| `plugin.xml` 接线 | 断言存在 `<gotoDeclarationHandler implementation="…">`（仿 `MybatisXmlReferenceTest.kt:376-387` 的文本守卫） |
| 快捷键不私有 | 断言新增注册段内**不含** `<keyboard-shortcut>` / `<mouse-shortcut>`（快捷键必须由平台 `GotoDeclaration` 持有，见 §4.6） |

### 7.2 反向验证（本仓纪律，必须贴两次读数）

- 摘掉 handler 的 Java 分支 → Java→XML 用例必红；还原 → 复绿。
- 摘掉 XML 分支 → 标签名用例必红。
- 摘掉 dumb 闸 → 在 dumb 夹具下必须观察到异常被吞但返回 `null`（不出现弹窗/ERROR）。

### 7.3 分层

- 实施子代理只跑定向（`--tests "<FQCN>"`），不跑全量、不 `clean`；
- 收口单派只读代理跑一次全量；
- `plugin.xml` 新增 EP 命中「大改动判据」→ 加 `verifyPlugin`；`PluginXmlExtensionPointResolutionTest` 会自动校验新 EP 名可解析（这正是 `syntaxHighlighterFactory` 那次坑的防线）。

## 八、待验证前提

| 编号 | 前提 | 证伪后怎么办 |
|---|---|---|
| P-1 | XML `id` 值处 Ctrl+B 当前可用（W7 引用生效） | 若不可用，先修引用（可能是 `language`/range 问题），W2 顺延 |
| P-2 | Java 方法名处 Ctrl+B 当前无反应 | 若已有其他来源（用户装了 MPJ），W0 读数须区分「插件提供」与「平台原生」 |
| P-3 | handler 优先于 reference（javap 已证，运行时待沙盒复核） | 若实际 reference 优先，XML 侧 `id` 值仍可用，仅标签名依赖 handler，方案不变 |
| P-4 | 首跳清单扫描耗时（大项目数千 XML）在可接受范围 | 若首跳 > 300ms，考虑把 namespace 清单改为独立 TTL 缓存或加「仅当接口名后缀为 Mapper」的弱前置 |
| P-5 | MPJ 在装时两 handler 共存无异常 | 若出现双目标弹窗，改为跟随让位开关（D-2 改判） |

## 九、风险

| 风险 | 缓解 |
|---|---|
| handler 抢占其他插件的 Ctrl+B | 仅在精确位置（方法名 identifier / 语句起始标签的 name 与 id 区）返回目标，其余一律 `null` |
| dumb 态抛 `IndexNotReadyException` | `DumbService.isDumb` 前置 + `runCatching` 兜底，返回 `null` |
| 多模块同名 namespace 取第一个 | 与 gutter/日志跳转同口径（`MapperXmlLocator.kt:74-76` 已留 INFO 痕迹），本券不扩 |
| XML 标签内 `#{}`、`property` 等位置的 Ctrl+B 被劫持 | 判定链只认起始标签 name 与 `id` 属性三个 token，不碰标签体 |
| 大项目首跳卡顿 | 复用 `MybatisXmlTargets` 既有清单缓存（TTL 5s）；P-4 实测兜底 |
| 新 EP 名写错被平台静默忽略 | `PluginXmlExtensionPointResolutionTest` 自动覆盖；W0 读数同时确认「按键有反应」 |

## 十、实施记录（2026-11-05）

### 10.1 落位

| 项 | 内容 |
|---|---|
| 分支 / 工作树 | `t-mapper-nav-shortcut` / `mybatis-plus-sql-visualizer-mns`，自 master `029864bf` 派生 |
| 解析层 | `ui/ref/MybatisXmlTargets.kt` 新增 `resolveStatementTags`（PSI 版，复用 namespace 单槽 memo + 清单 TTL 缓存）；`STATEMENT_TAGS` 提升为单一事实源（`MybatisXmlAttributeReferenceProvider` 里的私有副本删除） |
| 判定层 | `ui/nav/MapperNavigationTargets.kt`：Java→XML / XML→Java / 祖先爬升 / 落点选择 |
| 入口层 | `ui/nav/MapperNavigationGotoDeclarationHandler.kt`（Ctrl+B / Ctrl+Click）、`ui/nav/MapperNavigationGotoRelatedProvider.kt`（Ctrl+Alt+Home） |
| 接线 | `plugin.xml` 两条 EP（无 keymap）；双语 `nav.related.group.*` 两条分组文案 |

### 10.2 与计划的差异（实施期订正）

| 计划 | 实际 | 原因 |
|---|---|---|
| `resolveStatementTag(): XmlTag?` | `resolveStatementTags(): List<XmlTag>` | `databaseId` 多方言变体须全部返回，平台自动弹选择器 |
| 落点取 `id` 值元素 | 取 `XML_ATTRIBUTE_VALUE_TOKEN` | `XmlAttributeValue.firstChild` 是开引号，直接用会落在 `"` 上（定向首轮实测） |

### 10.3 读数

- 定向：`./gradlew test --tests "cn.song.mybatisplus.visualizer.ui.nav.*"` → **21/0/0/0**（Handler 16 + Related 5）。
- 反向验证（两次读数）：
  - 摘 Java 分支 → `testJavaMethod_命中XML语句落点` 红（该过滤组 3 例 1 红；另两例是「无 XML / 无 mapper」负路径，本就不依赖分支）；
  - 摘 XML 分支 → `testXml*` 7 例 4 红（起始标签名 / 闭标签名 / `id` 属性名 / `id` 属性值），3 条负路径仍绿；
  - 还原后全包 21/0/0/0。
- 构建校验：`plugin.xml` 新增 EP 命中「大改动判据」，按用户口径本轮只跑定向，`verifyPlugin` 留发版前。

### 10.4 环境插曲（如实登记）

首轮测试期撞上并行会话的 DB 集成测试（主树 `build/test-sandbox/database/testDbDiagram` 的沙盒 JVM 持有共享 `transforms` 目录），出现两次 `Could not determine the dependencies of task ':test'`（`Unable to delete directory …/cfd911…`）与一次 Gradle daemon 原生内存 OOM（`hs_err_pid9080.log`：`in-process` Kotlin 策略叠加双路构建）。处置＝去掉 `-Pkotlin.compiler.execution.strategy=in-process`、`--max-workers=2`，等对方构建结束后重跑即绿；崩溃产物（`hs_err_pid*.log` / `replay_pid*.log`）已删除，未进提交。

## 十一、变更记录

| 日期 | 记录 |
|---|---|
| 2026-11-05 14:04 | 立项：依据用户反馈做现状定位与平台机制取证（243/261 双端 keymap、EP 声明、javap 优先级链）。产出方案与波次，未动生产代码。 |
| 2026-11-05 14:2x | 用户追加约束：快捷键必须继承 IDEA 原生键位（改 Keymap 后跟随）。补 §4.6 并写死「只注册 EP、不注册 `<keyboard-shortcut>`」，测试表加「快捷键不私有」断言。 |
