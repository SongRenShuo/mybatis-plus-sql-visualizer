# T-MapperNavNamespaceJump —— Mapper 根标签跳转（无方法也能跳）与同类插件让位扩展

- 任务编号：T-MapperNavNamespaceJump
- 立项时间：2026-11-24 22:25
- 触发：用户真机反馈（2026-11-24）：「如果 mapper 没有方法，好像没法跳转，而 mp 插件是支持的，我们需要吸取精华也支持下；顺便看下其他同类产品，热门的排行靠前的，有跳转图标的，我们也支持让位」
- 关联：T-MapperNavCoexistYield（让位门）、T-MapperNavShortcut（Ctrl+B / GotoRelated）、T-MapperNavYieldJavaSideKeep（让位只作用 XML 侧）

---

## 一、调研结论（反编译实证）

### 1.1 竞品矩阵

| 插件（版本） | pluginId | 跳转图标注册 | 锚点 | 与我方冲突 | 让位登记 |
|---|---|---|---|---|---|
| MyBatisCodeHelperPro `3.7.0+2321` | `com.ccnode.codegenerator.MyBatisCodeHelperPro` | `UAST view.A`（Java 接口方法/类）、`XML view.I`（namespace 属性 + 语句 id 属性） | 方法名 identifier、namespace/id **属性值** | 是 | 已登记 |
| MyBatisX `1.7.6` | `com.baomidou.plugin.idea.mybatisx` | `JAVA MapperLineMarkerProvider`（PsiClass / PsiMethod）、`XML StatementLineMarkerProvider` | 类名、方法名、**`mapper` 根标签名**、`select/insert/update/delete` 标签名 | 是（XML 标签名、Java 方法名重叠） | **待新增** |
| MyBatisPlus `1.7.2` | `com.baomidou.plugin.idea.mybatisPlus` | `JAVA PlusMapperLineMarkerProvider`、`XML PlusStatementLinePlusMarkerProvider`（与 MyBatisX 同构，同作者 fork） | 同上 | 是 | **待新增** |
| zMyBatis `26.04.02` | `com.algorist.zMyBatis` | 无 `lineMarkerProvider` / `gotoDeclarationHandler` | — | 否 | 不登记 |
| MyBatis Log / MyBatis Log Free / MybatisLogFormat | — | 无（日志还原） | — | 否 | 不登记 |
| JRebel mybatisPlus | `jr-mp-ide-idea` | 无（热重载；本机未安装） | — | 否 | 不登记 |
| APIfox Helper | — | 无 Mapper gutter（API 文档） | — | 否 | 不登记 |

### 1.2 MyBatisX 的「无方法也能跳」是怎么做的（关键）

`StatementLineMarkerProvider`（反编译，`MybatisX-1.7.6.jar`）：

- `isTheElement`：XmlToken 且 token 文本＝`mapper`（标签名）**或** ∈ `{select,insert,update,delete}` 且父为 XmlTag。
- `apply`：
  - `DomElement instanceof IdDomElement`（语句）→ `JavaUtils.findMethods(project, namespace, id)`，**找不到方法即不出**；
  - 否则（`mapper` 根标签）→ `xmlTag.getAttributeValue("namespace")` → `JavaUtils.findClasses(project, namespace)` → **跳接口类**。

MyBatisCodeHelperPro 的 `view.I` 同构：`namespace` 属性 → mapper class（`navigation to mapper class`），语句 `id` → mapper 方法。

结论：竞品的「无方法可跳」不是语句行退化，而是**根标签/namespace 上另有一枚「跳接口类」的图标**。用户感知到的差异正是这一枚。

### 1.3 我方现状（代码证据）

- `MapperXmlToJavaLineMarkerProvider.createMarker`（`ui/gutter/MapperNavigationLineMarkerProviders.kt:90-121`）：只认语句标签；`lookupInterfaceMethod` 为 `Absent` 即不产出——没有方法就没有图标。
- `MapperNavigationGotoDeclarationHandler.xmlToJava`（`ui/nav/...:60-64`）：`methodFor(tag) ?: return null` → 语句无方法时不接管，Ctrl+B 无动作。
- `MapperNavigationGotoRelatedProvider.itemsFor`：`nearestStatementTag → methodFor`，无方法返回空。
- 全仓**没有** `mapper` 根标签 / `namespace` 属性层的图标与跳转。
- 让位登记只有一条前缀：`MyBatisCompetitorGate.PEER_PLUGIN_ID_PREFIXES = listOf("com.ccnode.codegenerator")`。

---

## 二、目标与非目标

**目标**

- XML `<mapper namespace="...">` 根标签出跳转图标，点击/Ctrl+B/Ctrl+Alt+Home 都能落到对应 Mapper 接口类——**接口里有没有方法都一样能跳**。
- 让位登记扩展到 `MyBatisX`、`MyBatisPlus`：其启用时 XML 侧我方跳转图标让位（与现有纪律一致），Java 侧仍保留我方图标（T-MapperNavYieldJavaSideKeep 的既有结论）。
- 六种同类插件/工具面逐一登记「有无跳转图标、是否登记让位」，避免下次再问。

**非目标**

- 不改竞品行为、不做跨插件写动作。
- 不为「语句 id 找不到方法」新造「退化图标」（竞品都不出；只做 Ctrl+B 兜底，见 D-1）。
- 不动注入/高亮链（`XmlSynHiGate`）。

---

## 三、实施方案

### S1 根标签 → 接口类的 gutter 图标（`MapperXmlToJavaLineMarkerProvider`）

- `createMarker` 增加前置分支：元素是 `mapper` 根标签的**开始标签名 token**（`XmlTagUtil.getStartTagNameElement` 且 `tag.name == "mapper"` 且 `tag.parentTag == null`）。
- `namespace = tag.getAttributeValue("namespace")` → `JavaPsiFacade.findClass(namespace, allScope)`；命中才出图标（不存在不造死图标，与 P-4 一致）。
- 图标复用 `MpvsIcons.MapperStatement`（D-2 拍板；不新增资源）。
- 点击：`PsiClass.navigate(true)`。
- 缓存：新增会话级正缓存 `resolvedClassKeys`（键 `locationHash|namespace`），dumb 窗乐观产出、`Absent` 收回——照 F1/五段式既有口径。
- 让位：同一道 XML 侧让位门（`collectSlowLineMarkers` 首部），与语句图标同进退。

### S2 Ctrl+B / Ctrl+Click（`MapperNavigationGotoDeclarationHandler`）

- `xmlToJava` 增加分支：`mapper` 根标签名 token → namespace → `PsiClass`（命中返回 `arrayOf(cls)`）。
- 语句无方法：`methodFor(tag) ?: return null` 改为（D-1 采纳时）`?: mapperClassOf(containingFile)` —— 跳接口类而不是无动作。

### S3 GotoRelated（`MapperNavigationGotoRelatedProvider`）

- `itemsFor` 增加分支：光标在 `mapper` 根标签 → 返回接口类一项（标签＝接口全限定名，图标 `MpvsIcons.MapperStatement`）。

### S4 让位登记扩展（`MyBatisCompetitorGate` + 设置页文案）

- `PEER_PLUGIN_ID_PREFIXES` 追加：`com.baomidou.plugin.idea.mybatisx`、`com.baomidou.plugin.idea.mybatisPlus`（两者 id 大小写不同，分别列出，不用宽前缀防误伤）。
- `settings.mapperNavYield.tip` 中英文案把「当前登记：MyBatisCodeHelperPro」改为列举三家。
- KDoc 登记面同步（前缀表是单一事实源，注释与实现同批）。

### S5 测试与反向

- `MapperNavigationLineMarkerProvidersTest` 扩：根标签出图标（有方法/无方法两种接口各一）、语句图标不回归。
- 新增/扩 `MapperNavigationTargetsTest`：`mapperClassOf` / `methodFor` 分支（含 namespace 指向不存在）。
- `MapperNavCoexistGateTest`：前缀表三档（MPJ / MyBatisX / MyBatisPlus 各命中；无关前缀不命中）。
- `MapperNavigationGutterGateTest`：竞品在位 + 让位开 → 根标签图标也让位（0 枚）；让位关 → 1 枚。
- 反向：摘根标签分支 → 新用例恰红；摘新前缀 → 真值表恰红。

---

## 四、待验证前提

| # | 前提 | 状态/证伪手段 |
|---|---|---|
| P-1 | MyBatisX / MyBatisPlus 的 pluginId 与 provider 注册如 §1.1 | **已由反编译确认**（`META-INF/plugin.xml` + provider 类字节码） |
| P-2 | 两者 XML 侧锚点与我方语句标签名重叠（同行冲突） | **已确认**（`StatementLineMarkerProvider.isTargetType`） |
| P-3 | 根标签图标不会被预览 provider 抢占 | 预览锚语句标签（`STATEMENT_TAGS`），根标签不在其中；实施后定向用例钉住 |
| P-4 | namespace 指向的接口不存在时不出图标 | 本方案明确要求；用例覆盖拼错 namespace |
| P-5 | 当前平台 `mapper` 根标签名 token 可由 `XmlTagUtil.getStartTagNameElement` 命中 | 现有语句锚同手段，已长期在用 |

---

## 五、决策点

- **D-1**：语句 id 找不到方法时，Ctrl+B 是否退化为跳接口类（推荐做——用户抱怨的「没法跳转」就包含这一格；图标仍不出，避免死图标）。
- **D-2**：根标签图标样式（推荐复用 `MpvsIcons.MapperStatement`，不新增资源；若后续要区分「类/方法」两跳，再议）。
- **D-3**：让位登记是否也纳入 `com.baomidou.plugin.idea.` 宽前缀（不推荐，避免误伤同前缀其它插件）。

---

## 六、验收

- A1：demo 里无方法的 Mapper 接口 → XML 根标签有图标，点击落到接口类；Ctrl+B 同行同落点。
- A2：有方法的接口 → 语句行图标行为不回归；根标签图标同时存在。
- A3：装 MyBatisX（或 MyBatisPlus）且让位开 → XML 侧无我方跳转图标（含根标签那枚）；Java 侧我方图标仍在。
- A4：未装任何竞品 → 行为与当前版本一致（基线回归）。
- A5：定向读数 + 反向读数入计划 §实施记录。

---

## 七、风险

| 风险 | 处置 |
|---|---|
| 根标签图标与竞品同锚，让位后用户仍只看到对方 | 这是让位纪律的预期；关让位即可看到我方 |
| 大工程 `findClass(namespace, allScope)` 成本 | 缓存 namespace→class 结论（会话级）；dumb 期不查索引、乐观产出 |
| MyBatisX 的 provider 是 `LineMarkerProviderDescriptor`（平台可关） | 让位判据仍取插件启停（既有纪律），不读对方设置 |
| 语句无方法退化跳类后，用户误以为该方法存在 | tooltip 写「跳转到 Mapper 接口类（未找到同名方法）」 |

---

## 八、实施记录（2026-11-24，分支 `feature/mapper-nav-namespace-jump`，工作树 `../mybatis-plus-sql-visualizer-mappernav`；**已并入 master（`145381d28`，2026-11-25）**）

### 8.1 拍板结果

- D-1 采纳：语句 `id` 找不到同名方法时，Ctrl+B 退化为跳接口类（不产生图标，无死图标风险）。
- D-2 采纳：根标签图标复用 `MpvsIcons.MapperStatement`，不新增资源。
- D-3 否决：不使用 `com.baomidou.plugin.idea.` 宽前缀，两家 id 分别列出。

### 8.2 变更清单

- `ui/nav/MapperNavigationTargets.kt`：新增 `mapperClassOfRootTag(tag)`、`mapperClassOf(file)`、`mapperClassOf(project, namespace)`、`rootMapperTagOf(token)`（根标签名 / namespace 属性名 / 属性值三落点；dumb 返回 null）。
- `ui/gutter/MapperNavigationLineMarkerProviders.kt`：`createMarker` 前置根标签分支 + `createRootMapperMarker`（五段式：dumb 乐观产出 / 正缓存 `resolvedClassKeys` / `IndexNotReadyException` 命中缓存 / Absent 收回 / namespace 空不出）；新增 `classCacheKey`；`resetResolvedCacheForTest` 一并清缓存。根标签图标走 `collectSlowLineMarkers` 同一道 XML 侧让位门。
- `ui/nav/MapperNavigationGotoDeclarationHandler.kt`：`xmlToJava` 增加根标签分支与「无方法退化跳类」。
- `ui/nav/MapperNavigationGotoRelatedProvider.kt`：`itemsFor` 增加根标签 → 接口类关联项（`rootMapperTag` 只认最近 XmlTag 是 mapper 根，语句标签内不误吞）。
- `ui/gutter/MyBatisCompetitorGate.kt`：`PEER_PLUGIN_ID_PREFIXES` 追加 `com.baomidou.plugin.idea.mybatisx`、`com.baomidou.plugin.idea.mybatisPlus`；KDoc 记录三家反编译依据。
- 中英 bundle：新增 `gutter.mapperClass.tooltip` / `.accessible.name`；`settings.mapperNavYield.tip` 列举三家。
- 测试：`MapperNavigationLineMarkerProvidersTest`（+3；`markers()` 默认过滤根标签，新用例 `includeRoot=true`）、`MapperNavigationGutterGateTest`（+2；`xmlSideMarkerCount()` 过滤根标签保持语句基线）、`MapperNavCoexistGateTest`（+1 前缀覆盖）、`MapperNavigationGotoDeclarationHandlerTest`（+3）、`MapperNavigationGotoRelatedProviderTest`（+2）。

### 8.3 定向读数

| 测试类 | tests | failures |
|---|---|---|
| `ui.gutter.MapperNavigationLineMarkerProvidersTest` | 24 | 0 |
| `ui.gutter.MapperNavigationGutterGateTest` | 13 | 0 |
| `ui.gutter.MapperNavCoexistGateTest` | 7 | 0 |
| `ui.nav.MapperNavigationGotoDeclarationHandlerTest` | 19 | 0 |
| `ui.nav.MapperNavigationGotoRelatedProviderTest` | 7 | 0 |
| `i18n.BundleParityGuardTest` | 5 | 0 |

合计 **75 / 0**。

### 8.4 反向验证（两轮，逐面各恰红）

- 轮 1（摘 gutter 根标签分支）：红 **3** 条 —— `test根标签出图标_接口无方法也可跳`、`test根标签与语句图标共存`、`test竞品不在位_根标签跳类图标保留`。
- 轮 2（同时摘 Ctrl+B 根标签与退化分支 / GotoRelated 根标签 / 前缀表新两条）：红 **4** 条 —— `testXml根标签名_跳接口类`、`testXml语句无同名方法_退化为跳接口类`、`testXml根标签_关联接口类`、`testP1_前缀候选覆盖MyBatisX与MyBatisPlus`。
- 还原后复跑：6 类 **75 / 0**。

### 8.5 沙盒 GUI 验收（2026-11-24，IU-243 沙盒 + demo `cust-big-demo`）

**轮 A（无竞品）** —— 根标签图标与无方法跳转：

| 用例 | 操作 | 读数 | 结论 |
|---|---|---|---|
| 根标签图标 | 打开 `cust-center/.../CustInfoMapper.xml` | 第 3 行 `<mapper namespace=…>` 左侧出现青色图标（`build/MPSV-NSJ-shots/nsj-01-root-tag.png`） | ✅ |
| tooltip | hover 根标签图标 | 「跳转到 Mapper 接口」 | ✅ |
| 点击跳类 | 点击根标签图标 | 跳到 `CustInfoMapper.java`（`nsj-03-root-click-jump.png`） | ✅ |
| 无方法退化 | 打开 `CtAccount0002Mapper.xml`（语句 `selectByTenant` 无同名方法），光标在 id 上按 Ctrl+B | 跳到 `CtAccount0002Mapper.java` 接口类（`nsj-05-nomethod-ctrlb.png`） | ✅ |

**轮 B（装真实 MyBatisX 1.7.6）** —— 让位扩展：

| 用例 | 操作 | 读数 | 结论 |
|---|---|---|---|
| XML 侧让位 | 打开 `CustInfoMapper.xml`，hover 根标签图标 | 图标只剩 MyBatisX 的；tooltip＝`Data access object found - cn.song.custcenter.mapper.CustInfoMapper`（MyBatisX 专属文案） | ✅ 我方 XML 侧（含新增根标签图标）让位生效 |
| 让位探测 | 启动日志 | `Loaded custom plugins: … MyBatisX (1.7.6)`；无 `[T-MapperNavCoexistYield] competitor probe unavailable` WARN | ✅ 新前缀命中 |

**环境坑（沙盒侧，非产品面）**：

- 新工作树首次 `runIde` 被 `build.gradle.kts:203` 拦住（缺 robot-server）：先把主仓 `record/robot-plugins/` 复制过去（`bootstrap-robot-server.py` 走 jetbrains 源，本机直连会卡）。
- 首次探针（hover+点击根标签）之后 IDE 自行退出（`runIde` exit 1，日志无异常、无 hprof）；重启后稳定，未复现。
- 一次外部 `TxBugReport.exe` 崩溃弹窗挡住 IDE，属台面环境噪声，非插件问题。

风险表里「tooltip 写未找到同名方法」一条实际不需要：D-1 的退化只发生在 Ctrl+B，不产生图标，无 tooltip 面。
