# T-MapperMethodSqlPreview —— Mapper 接口方法行 SQL 预览图标

- 任务编号：T-MapperMethodSqlPreview
- 立项时间：2026-11-25 15:05
- 触发：用户需求（2026-11-25）：「现在需要在 mapper.java 的方法处也有 sql 预览图标，现在分析需求生成开发计划文件」
- 关联：T-MapperNavShortcut（方法行跳转箭头，`MapperJavaToXmlLineMarkerProvider`）、T-M24-04（XML 语句行预览）、T-M25-03（注解行预览）、T-IbatisCallPreview（同 provider 双 marker 先例）、T-MapperNavYieldBothSides（两侧让位口径）

---

## 一、需求与现状

### 1.1 用户要什么

Mapper 接口（`mapper.java`）的**方法声明行**现在只有一枚「跳转到 Mapper XML 语句」的箭头。用户希望这一行也能直接点开 SQL 预览，和 XML 语句行的青色预览图标一样——不必先跳到 XML 再点预览。落点明确：gutter 图标（不是 Alt+Enter 菜单、不是右键动作）。

### 1.2 现状（代码证据）

- **跳转箭头**：`MapperJavaToXmlLineMarkerProvider`（`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/MapperNavigationLineMarkerProviders.kt:200-333`）锚接口方法名 `PsiIdentifier`，慢趟 `collectSlowLineMarkers` 里对每个方法调一次 `MapperXmlLocator.locateOrUnknown(project, "$namespace.${method.name}")`，按五段式三态（dumb 乐观 / Found 入正缓存 / Unknown 命中缓存 / Absent 收回并摘图标）决定出不出箭头。
  - 这条判定**已经知道「这个方法有没有对应 XML 语句」**，但结果只用来画箭头，没有第二次利用。
- **预览链路（成熟，可直接复用）**：`XmlSqlGutterLineMarkerProvider`（`ui/gutter/XmlSqlGutterLineMarkerProvider.kt:66-100`）点击 → `SqlPreviewService.previewAsync` → `XmlPreviewService.resolveSourceContext(file, tag)` + `XmlSqlCompletionContextResolver.resolveXml(..., includeCandidates = false)` + `XmlPreviewService.preview(file.text, tag.getAttributeValue("id"), ...)` → `SqlPreviewService.showResult(...)`。动态 SQL、参数表、`<include>`、`${}` 注入等都在这条链上。
- **方法 → 语句标签的解析已有单一事实源**：`MapperNavigationTargets.statementTagsFor(method)`（`ui/nav/MapperNavigationTargets.kt:43-51`）→ `MybatisXmlTargets.resolveStatementTags`，返回 `<mapper>` 直接子级里 `id == method.name` 的全部语句（含 `databaseId` 方言变体）；非接口 / dumb 返回空。
- **项目内已有「同一 provider 一次解析产出两枚 marker」的先例**：`IbatisJavaToXmlLineMarkerProvider`（预览 marker + 跳转箭头共用一次 `resolveStatementAnchors`，见 `docs/架构设计/预览触发面清单-说明文档.md` 入口矩阵 #16）。差别：那一对锚点不同（预览锚调用首参字面量、箭头锚方法名）；本任务首次尝试**同一 anchor 两枚**。
- **预览图标资源**：各预览 provider 统一用 `IconLoader.getIcon("/icons/sqlGutter.svg")`，与跳转箭头（`MpvsIcons.MapperMethod` / `MapperStatement`）颜色形态不同，同一行可区分。

### 1.3 竞品对照（反编译矩阵，2026-11-25）

| 插件 | Java 侧 gutter | 有 SQL 预览图标吗 |
|---|---|---|
| MyBatisCodeHelperPro 3.7.1 | `UAST view.A`（接口方法/类 → XML），2026.3 实测不产出 | 无（预览在 XML 侧与生成器入口） |
| MyBatisX 1.7.6 | `MapperLineMarkerProvider`（方法名 / 类名 → XML） | 无（Java 侧只有 `Navigation to target in mapper xml` 箭头） |
| MyBatisPlus 1.7.2 | `PlusMapperLineMarkerProvider`（同构 fork） | 无 |

结论：**「接口方法行点开预览」是我方独有能力**，不需要纳入 `MyBatisCompetitorGate` 的让位范围。让位设置项语义仍是「让出 XML 与 Mapper 跳转图标」，预览不是跳转，让位开着也照出。

这一条同时解决一个既有观感问题：只装 MPJ 时开让位，Java 方法行此前会空（MPJ 的 UAST 箭头在 2026.3 不产出）；有了预览图标，该行至少保留一枚我方的可用入口。

---

## 二、目标与非目标

**目标**

1. 有对应 XML 语句的 Mapper 接口方法，方法名行出现 SQL 预览图标；点击弹出预览面板，渲染结果与在 XML 语句行点击逐字节一致（同源函数，不另写一套）。
2. 预览图标与既有跳转箭头共存（一行两枚），hover 各自 tooltip 可区分。
3. 让位开启时只让掉跳转箭头，预览图标保留；类名行的类跳转沿用现有两侧让位口径（一并让掉）。
4. daemon 成本零增量：不新增慢趟遍历、不新增索引查询，判定沿用现有一次 `locateOrUnknown` 的结果。
5. 不新增设置项，复用 `showGutterIcon` 总闸与 `mapperXml` 组开关。

**非目标**

- 注解 SQL 方法不重复出预览：`@Select/@Insert/@Update/@Delete` 已由 `AnnotationSqlGutterLineMarkerProvider` 在 `@` token 行出图标，方法行不再来一枚同样的。
- MP 内置 CRUD 方法（无 XML 语句，如未写 XML 的 `selectById`）本期不出预览——静态渲染内置 SQL 是另一条链，另立项。
- 不做「同名多语句（databaseId 变体）选择器」：一期取第一条，与现有跳转箭头同判据（`MapperXmlLocator` 也是取第一个并 `LOG.info` 留痕）。
- 不改 XML 语句行预览、不改跳转箭头锚点与图标、不动注入/高亮链。

---

## 三、实施方案

### S1 判定与产出：一个方法行出两枚 marker

改 `MapperJavaToXmlLineMarkerProvider`（文件同上），**不新建 provider、不动 plugin.xml**。

- `collectSlowLineMarkers` 的让位门从「整段 `return`」改为「只让跳转」：

```kotlin
val yieldNavigation = gutterState != null &&
    MyBatisCompetitorGate.shouldYield(
        gutterState.mapperNavYieldToCodeHelper,
        MyBatisCompetitorGate.isPeerPluginActive(),
    )
yieldNavigationThisPass = yieldNavigation   // provider 实例字段（见下）
elements.mapNotNullTo(result, ::createMarker)
```

  用**实例字段**而不是给 `createMarker` 加参数：`MapperXmlSlowResolveFailGuardTest` 按源码扫描 `createMarker` 的形态（方法名 + `MapperXmlLocator.locateOrUnknown` 调用 + 五段式顺序），改签名/改名会让守卫红。字段赋值与 `createMarker` 调用在同一趟、同一线程内完成，无并发面（`getLineMarkerInfo` 恒 null，不参与）。
- `createMarker` 内保持现有三态判定与 `MapperXmlLocator.locateOrUnknown` 的调用位置**逐字不变**（守卫扫描面），只在产出处改：

```kotlin
// Found / Unknown+缓存 / dumb 三条产出分支统一走：
buildMethodMarkers(element, includeNavigation = !yieldNavigationThisPass)

private fun buildMethodMarkers(element: PsiElement, includeNavigation: Boolean): List<LineMarkerInfo<*>> =
    buildList {
        add(buildMethodPreviewMarker(element))
        if (includeNavigation) add(buildMethodMarker(element))
    }
```

  `buildMethodMarker` 原样保留（跳转箭头，`MpvsIcons.MapperMethod`，tooltip `gutter.mapperMethod.tooltip`）。
- 新增 `buildMethodPreviewMarker`：锚点同为方法名 `PsiIdentifier`，图标 `/icons/sqlGutter.svg`（与 XML/注解预览同款），`GutterIconRenderer.Alignment.CENTER`，tooltip / accessible name 用新 bundle 键（S4），点击 handler 见 S2。
- 类名分支（`createClassMarker`）不产出预览：类名不是语句，没有可预览的 SQL。

### S2 点击链路：抽公共函数，Java/XML 共用

- 在 `XmlSqlGutterLineMarkerProvider` companion 抽 `internal fun previewStatementTag(...)`，把现有 `XmlGutterNavigationHandler.navigate` 里的「`previewAsync` + `resolveSourceContext` + dumb 跳过实体上下文 + `resolveXml(includeCandidates=false)` + `XmlPreviewService.preview` + `showResult`」整体搬过去，**`[open-perf]` 分段日志与 `sourceContext` 回调保持原样**。XML 侧 handler 改为调用它，行为逐字不变（现有 XML 预览测试即回归网）。
- 签名取「惰性解析」形态，让方法 → 语句标签的 PSI 读取落到后台 ReadAction，而不是 EDT：

```kotlin
internal fun previewStatementTag(
    project: Project,
    sourceLocation: String?,
    resolve: () -> Pair<XmlFile, XmlTag>?,   // 在 previewAsync 的 ReadAction 内执行
)
```

  XML 侧传入 `{ file to tag }`；Java 侧传入 `{ statementTagsFor(method).firstOrNull()?.let { it.containingFile as? XmlFile to it } }`。
- Java 侧 handler：

```kotlin
val method = elt.parent as? PsiMethod ?: return
val snapshot = ReadAction.compute<PsiSnapshot?, RuntimeException> { PsiSnapshot.capture(method) }
val sourceLocation = "${method.containingFile.name}:${行号}"   // EDT 读 document 行号（既有 navigate 同档操作）
XmlSqlGutterLineMarkerProvider.previewStatementTag(project, sourceLocation) {
    if (snapshot?.validate() is PsiSnapshot.Validity.Invalid) return@previewStatementTag null
    MapperNavigationTargets.statementTagsFor(method).firstOrNull()?.let { tag ->
        (tag.containingFile as? XmlFile)?.let { file -> file to tag }
    }
}
```

  `PsiSnapshot` 校验沿用 T-PCP-02 的跨线程 PSI 纪律（`psiCapture` 守卫要求池化块内有快照校验）。失效按「未找到」轻提示，不抛。

### S3 dumb 与多语句边界

- **dumb 期点击**：`statementTagsFor` 在 dumb 返回空（`MapperNavigationTargets.kt:49`），若直接点会弹「未找到」。点击 handler 先判 `DumbService.isDumb(project)`，是则 `DumbService.getInstance(project).runWhenSmart { navigate(...) }` 延后一次，索引就绪自动弹预览（对齐 `previewAsync` 既有的 02d 重试做法）。
- **同名多语句（databaseId 变体）**：`resolveStatementTags` 返回全部，取 `firstOrNull()`；与跳转箭头落点一致。后续增强（选择器）另列，不阻塞本期。
- **图标出现条件**：与跳转箭头完全同生共死（同一次判定、同一个五段式缓存键 `MapperXmlToJavaLineMarkerProvider.cacheKey`）。不存在「图标在但点开必空」的死入口。

### S4 文案与图标

bundle 中英各加两键（`MpSqlVisualizerBundle.properties` / `_zh.properties`，BundleParityGuardTest 会校验对齐）：

| key | 中文 | 英文 |
|---|---|---|
| `gutter.mapperMethodPreview.tooltip` | SQL Visualizer：预览该方法绑定的 SQL | SQL Visualizer: preview the SQL bound to this method |
| `gutter.mapperMethodPreview.accessible.name` | 预览该方法绑定的 SQL | Preview the SQL bound to this method |

图标复用 `/icons/sqlGutter.svg`，不新增资源。

### S5 设置与让位

- 不新增设置项。`MapperJavaToXmlLineMarkerProvider` 已在 `GutterGroups` 登记为 `mapperXml`（`ui/gutter/GutterGroups.kt:33`），预览随组开关与总闸。
- 让位开启后 Java 方法行从 0 枚变 1 枚（预览）。设置项文案「让出 XML 与 Mapper 跳转图标」仍然准确，不改。

### S6 测试

新增用例（并入 `MapperNavigationLineMarkerProvidersTest`；该类的 `markers()` 需默认过滤预览 marker，否则全部方法基线 +1，参照 20261125 类名 marker 的踩坑处理）：

1. 有 XML 语句的接口方法 → 2 枚（预览 + 跳转），两枚 tooltip 分别命中新键与 `gutter.mapperMethod.tooltip`；
2. 无 XML 语句的接口方法 → 0 枚；
3. dumb 窗 → 2 枚（乐观产出）；
4. 让位开 + 竞品在位 → 1 枚，且是预览（tooltip 新键），跳转 0；
5. 非接口类的同名方法 → 0 枚；
6. 接口类名行 → 1 枚（仅类跳转，不含预览）。

基线更新（合并后 Java 行 1→2、让位开 0→1）：

| 测试类 | 改动 |
|---|---|
| `MapperNavigationGutterGateTest` | `javaSideMarkerCount()` 拆成预览/跳转两个计数；基线 1→2、让位开 0→1、类名让位用例不变 |
| `MapperNavigationLineMarkerProvidersTest` | `markers()` 新增 `isMethodPreviewMarker` 过滤；对「方法行两枚」单独断言 |
| `IbatisNavigationLineMarkerProvidersTest` | MyBatis Java 侧对照基线同步（1→2） |
| `MapperNavCoexistUastReproTest` | own 1→2、total 2→3、让位开 own 0→1（预览） |
| `MapperXmlSlowResolveFailGuardTest` | 不改判据；S1 保持 `createMarker` 名字与 `locateOrUnknown` 形态，理论上零改动，实施后必须复跑确认 |
| `GutterProvidersNegativeTest` | 无新 provider 不登记；补「非接口类同名方法零图标」负例（若清单口径要求） |

反向验证两轮（每轮先提交，避免 `git checkout` 连带还原未提交修复）：

- 摘掉 `buildMethodPreviewMarker` 的产出 → 预览相关断言恰红（2~3 处），还原复绿；
- 把让位门改回整段 `return` → 让位开用例（应为 1 枚预览）恰红，还原复绿。

### S7 文档

- `docs/架构设计/预览触发面清单-说明文档.md`：§一 入口矩阵新增一行（#18 `MapperJavaToXmlLineMarkerProvider 的预览 marker`，锚方法名 identifier、判定入口、形态、主测试），并按 §四 三条登记义务补负例与正例。
- `docs/架构设计/02-核心流程详解.md`：Mapper 导航/provider 段落补「方法行预览 marker」。
- `CHANGELOG.md` `[Unreleased]` → `### Added` 一条（含提交哈希）。
- 本计划 §八 回填实施记录。

---

## 四、待验证前提

| # | 前提 | 验证法 | 若不成立 |
|---|---|---|---|
| P-1 | 平台允许**同一 element 两枚** `LineMarkerInfo` 并都渲染（项目内此前无先例，iBatis 那一对锚点不同） | 单测：`collectSlowLineMarkers` 后对同一 `PsiIdentifier` 断言恰 2 枚；沙盒目视一行两枚图标 | 兜底 B：预览锚改到方法名 identifier 前的 leaf（修饰符/返回类型），跳转锚不动；或拆独立 provider（见 §六 D-1） |
| P-2 | 两枚图标在一行内的左右顺序稳定（result 插入顺序是否被平台保留） | 同上单测 + 沙盒截图；顺序不定也可接受，只要求 tooltip 可区分 | 接受任意顺序；必要时用两个 provider 控制注册顺序 |
| P-3 | 点击期 PSI 有效性：方法 → 语句标签在后台读取前可能失效 | 用 `PsiSnapshot.capture` + `validate`，失效按未找到提示（既有 T-PCP-02 模式） | 已含在方案内，无需兜底 |
| P-4 | dumb 期点击不误报「未找到」 | `runWhenSmart` 延后一次；沙盒/测试模拟 dumb | 退化为轻提示 + 下一次点重试 |
| P-5 | `createMarker` 源码扫描守卫（`MapperXmlSlowResolveFailGuardTest`）对签名/结构变化的敏感度 | 实施后先跑该守卫类 | 保持方法名与调用位置不变；若必须改，同步更新扫描面且不弱化判据 |
| P-6 | `resolveStatementTags` 返回同名全部语句（含 databaseId） | 读实现（`MybatisXmlTargets.kt:73-79`，`subTags.filter`）已确认 | — |

---

## 五、性能论证

- **零新增 daemon 成本**：判定复用现有一次 `MapperXmlLocator.locateOrUnknown`（同一趟、同一缓存键），只是多构造一枚 `LineMarkerInfo`。
- 不新增索引查询、不新增 PSI 遍历、不读语句文本（`file.text` 只在点击后台线程读）。
- 预览点击走既有 `previewAsync`（池线程 + ReadAction + perf 埋点），大 SQL 渲染成本与 XML 语句行点击相同。
- 可接受的已知成本：预览图标在 dumb 窗乐观产出（与跳转箭头同口径），点击期由 S3 的 `runWhenSmart` 兜底。

---

## 六、决策点

- **D-1 合并进 `MapperJavaToXmlLineMarkerProvider`，而不是新建独立 provider**（推荐，本期按此实施）。
  理由：① 判定已存在于该 provider，独立 provider 会让每个 mapper 方法多一次索引查询，违背零增量；② 让位门只在该 provider 里，改成「只让跳转」比跨 provider 协调简单；③ 预览与箭头同生共死，语义一致。
  代价：该类职责扩为「预览 + 导航」，需在 KDoc 说明；`MapperXmlSlowResolveFailGuardTest` 的扫描面要守住。
- **D-2 让位只让跳转，预览保留**（推荐）。依据：设置项文案本就限定「跳转图标」；竞品无反编译证据有 Java 侧预览能力；顺带解决只装 MPJ 时 Java 行让位后为空的问题。
- **D-3 多语句取第一条**（推荐，本期）。与现有跳转落点一致；选择器列为后续。
- **D-4 注解方法不重复出预览**（推荐）。注解行已有入口，方法行再来一枚是同一 SQL 的双入口，且会与注解 provider 的组开关（`annotation` vs `mapperXml`）交叉，语义混乱。

---

## 七、验收

定向（实施后必跑，`--tests` 逐类）：

- `MapperNavigationLineMarkerProvidersTest`、`MapperNavigationGutterGateTest`、`MapperNavCoexistUastReproTest`、`IbatisNavigationLineMarkerProvidersTest`、`MapperXmlSlowResolveFailGuardTest`、`GutterProvidersNegativeTest`、`BundleParityGuardTest`，并新增预览用例所在类。

反向：

- 两轮，逐面恰红，贴两次读数（见 S6）。

沙盒 GUI（IU-243 + demo 工程 `cust-big-demo`，配方沿用 `record/` 现成脚本）：

1. `RoleMapper`（有 XML）方法行：两枚图标，左侧预览（青色）、右侧跳转（红色）；hover 各自 tooltip 文案正确；
2. 点预览图标 → 面板弹出，SQL 与在 XML 语句行点预览一致（含参数表）；
3. 开启让位 + 安装 MPJ → 方法行只剩预览 1 枚，类名行无我方图标；
4. 无 XML 语句的接口方法（如 MP 内置方法）→ 0 枚；
5. 注解方法（`@Select`）→ 注解行 1 枚、方法行 0 枚；
6. 设置页切关 `mapperXml` 组 → 两种图标都消失；切回立即生效（复用既有 `DaemonCodeAnalyzer.restart` 重算）。

---

## 八、风险与回退

| 风险 | 处置 |
|---|---|
| P-1 不成立（同 anchor 两枚被平台去重） | 兜底 B（换预览锚点）或独立 provider；不牺牲跳转箭头现有锚点 |
| 基线更新面积大（4 个测试类） | 已逐类列清单；先改断言再跑，避免「红一片」时误判 |
| 慢趟守卫测试因改造而红 | 保持 `createMarker` 名称与 `locateOrUnknown` 形态；若必须改签名，同步扫描面并复跑 |
| 用户预期「让位后 Java 行空」被改变 | 行为变更写进 CHANGELOG 与计划；沙盒验收第 3 步确认为「只剩预览」 |
| 点击时索引未就绪误报 | S3 `runWhenSmart` 兜底 |
| 方法行两枚图标视觉拥挤 | 图标已分色分形（预览青 / 跳转红），沙盒目视确认；拥挤再谈合并成单图标菜单（不在本期） |

---

## 九、实施记录（2026-11-25，分支 `mapper-method-preview`，工作树 `.worktrees/t-mapper-method-preview`；**未合并主分支**）

### 9.1 落地

- master `577f16648`：本计划立项提交。
- 分支 `12f3232b8`：S1~S5 生产改动 + 基线更新 + 文档登记（11 files，+263/−55）。
  - `MapperNavigationLineMarkerProviders.kt`：`createMarker` 保留名字与 `MapperXmlLocator.locateOrUnknown` / 五段式形态（`MapperXmlSlowResolveFailGuardTest` 扫描面零改动），返回值改 `List<LineMarkerInfo<*>>?`；新增 `buildMethodMarkers`（预览在前、跳转在后）、`buildMethodPreviewMarker`、`MapperMethodPreviewNavigationHandler`（`PsiSnapshot` 校验 + dumb `runWhenSmart`）；让位门改为 `yieldNavigation`，只作用于跳转箭头与类名图标。
  - `XmlSqlGutterLineMarkerProvider.kt`：抽 `internal fun previewStatementTag(project, sourceLocation, resolve)`，XML 侧 handler 改为调用（`[open-perf]` 分段日志与 `sourceContext` 回调逐字保留）。
  - bundle 中英新增 `gutter.mapperMethodPreview.tooltip` / `.accessible.name`；图标复用 `/icons/sqlGutter.svg`，不新增资源、不改 `plugin.xml`、不加设置项。

### 9.2 定向读数

- 6 类 **95/0**：`MapperNavigationLineMarkerProvidersTest` 31、`IbatisNavigationLineMarkerProvidersTest` 34、`MapperNavigationGutterGateTest` 15、`MapperNavCoexistUastReproTest` 6、`BundleParityGuardTest` 5、`MapperXmlSlowResolveFailGuardTest` 4。
- 新增 6 条预览用例：双枚且预览在左、无 XML 零枚、dumb 乐观、类名行零预览、组闸同灭、方法名过滤器（既有基线用例同步改造）。

### 9.3 反向验证（两轮，逐面恰红）

- 轮 1（摘掉 `buildMethodMarkers` 里的预览 `add`）：**86 tests / 11 红**——双枚用例、dumb 乐观、让位开与让位关的预览断言、Coexist 三处、Ibatis 一处；跳转相关保持绿。
- 轮 2（让位门改回整段 `return`）：**55 tests / 3 红**——恰为让位开的三处预览断言（GutterGate / Coexist / Ibatis）。
- 还原后复跑 6 类 **95/0**，`git diff` 空（反向改动全部撤销）。

### 9.4 环境与 API 坑（本轮新增）

- 平台 transform `…/9.7.0/transforms/cfd9113040487d86d63d9022472046cb` 是半成品（`lib` 只有 `app.jar` / `app-client.jar`），`:test` 在平台路径校验阶段报「com.intellij core plugin not found」。占用者是用户 IDE 的 Kotlin daemon（PID 27684，Restart Manager 点名），删/改名均 Permission denied；最终用 `modules-2/files-2.1/idea/ideaIU/2024.3.7.1/…/ideaIU-2024.3.7.1-win.zip` **`unzip -n` 就地补齐**（2268 files，跳过被占的两个 jar）后恢复。
- 243 的 `LineMarkerInfo` 没有 `getTooltip()`，tooltip 属性名是 **`getLineMarkerTooltip()`**（javap 实证）。首次批量把 `.tooltip` 换成 `.lineMarkerTooltip` 时连带改坏了 bundle key 字符串（`gutter.mapperMethodPreview.lineMarkerTooltip`），过滤判定恒 false → 16 红；二次修正后全绿。

### 9.5 未做

- 沙盒 GUI 目视验收（一行两枚图标的左右顺序、点击预览面板内容、让位开只留预览）未执行——当前平台缓存被占用、未启动 `runIde`；P-1/P-2 由单测钉住（同一 element 收集到 2 枚、预览在 `result[0]`），真机渲染待验收。
- 多语句（`databaseId` 变体）选择器与 MP 内置 CRUD 预览仍按 §二 非目标搁置。

### 9.6 补漏（2026-11-26，master）：预览快捷键接入

- 用户报障：「测试有问题，预览快捷键没支持」——`Alt+Shift+Q`（`ConvertToSqlAction`）停在 Mapper 接口方法上会落到「未找到可转换的 Wrapper」，只有 gutter 图标能预览。
- 修：fallback 链首位新增 `tryMapperXmlMethodFallback` —— `MapperNavigationTargets.nearestMapperMethod` → `statementTagsFor`（同名 XML 语句，含 `databaseId` 变体）→ `XmlSqlGutterLineMarkerProvider.previewStatementTag`；单条直接预览，多方言变体弹 `SqlSelectionDialog`，无 XML 语句才回落原有链（注解候选 / 通用未找到），不出错源提示。
- 测试：`ConvertToSqlActionTest` 新增 2 条（数据面渲染 + 链序结构守卫），定向 7 类 **110/0**；反向摘掉链上调用 → 恰 1 红（结构守卫），还原后 15/0。
- 测试环境：master 上测试一度无法启动——平台 transform `9.7.0/transforms/cfd9113040487d86d63…` 是半成品，且被用户 IDEA（GUI launcher，PID 4036）持有，Gradle 重建时删除失败（`Unable to delete directory`）。改用独立 Gradle home `F:/gradle-home-pv`（junction 共享 `modules-2`，平台重新解压）绕开；旧 transform 需用户关闭项目/重启 IDE 后让 Gradle 重建一次才能彻底恢复（否则默认 home 下 `:test` 仍不可用）。