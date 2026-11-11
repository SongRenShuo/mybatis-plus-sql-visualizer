# T-IbatisCallPreview：iBatis DAO 调用行直接预览 SQL（图标 + 快捷键）—— 开发计划

- 日期：2026-11-11 10:43（11:10 补入快捷键与 MCP 同源面）
- 状态：**计划（未实施）**
- 来源：用户 2026-11-11 新需求两连——①「现在希望这类的可以有预览图标并且直接预览 sql」，指向 `mybatis-plus-sql-visualizer-demo/ibatis2-demo/src/main/java/cn/song/ibatis2demo/UserDaoImpl.java`；②「sql 预览快捷键也需要可以正常用」（`Alt+Shift+Q` / `ConvertToSql`）
- 前序：`20261109-1130-T-Ibatis2Support`（券 A 预览 / 券 F 跳转）、`20261109-2252-T-Ibatis2Gap`（券 B 双向箭头）、`T-IbatisFix3`（JdbcTemplate 调用行让位）、`T-IbatisFix4`（方法定义行让位）
- 拍板：**图标形态＝调用行新增预览图标、保留既有跳转箭头**（用户 2026-11-11 选择，D-1）

---

## 一、需求与结论先行

### 1.1 一句话需求

iBatis 2.x 的 DAO 实现类里，`client.queryForList("User.selectAll")` 这类 `SqlMapExecutor` 调用行：

1. 左侧要有**预览图标**，点开**直接**出这条语句渲染后的 SQL（参数/条件/selectKey 与 XML 语句行预览完全一致），不用先跳 XML；
2. 光标停在同一处按 **`Alt+Shift+Q`**（`ConvertToSql` 动作/右键菜单）也要**直接**出同一条 SQL，不能落到「未找到可转换的 Wrapper」这类错源提示。

### 1.2 现状一句话

- **图标**：调用行只有一枚跳转箭头（`IbatisJavaToXmlLineMarkerProvider`）。预览图标在 T-IbatisFix3/Fix4 两轮里被**刻意让位**——当时的预览通道（JdbcTemplate / StringSqlMethod）把 statementId 字面量当 SQL 解析，点开必空。
- **快捷键**：调用行按 `Alt+Shift+Q` 走 `ConvertToSqlAction` 的回落链，`tryJdbcTemplateFallback` 先把 `client.queryForList("User.selectAll")` 当 JDBC 模板调用接住（`JdbcTemplateSqlLocator` **没有** iBatis 让位门），随后 `isLikelySql("User.selectAll")` 拒绝并返回 null，动作已 `return true` ⇒ 链路断在这里，用户拿到「未找到」；光标在方法定义行时走 `tryMethodBodyStringSqlFallback`，同一个缺口。
- **XML 语句行的快捷键已经能用**（`previewXmlTag` 有 iBatis 分支）；本次不动它的行为，只切到共用装配。

### 1.3 不做什么（本期边界）

| 不做 | 理由 |
|---|---|
| 方法定义行（`public List<User> selectAll()`）加**预览图标** | 用户拍板：一条语句只在一处出图标，避免同一条 SQL 两枚预览入口。**但快捷键在方法定义行/方法体内要能用**（§3.4，方法级回落） |
| 撤销 `IbatisCallYieldGate` / 方法定义行让位 | 那是为了消灭「点开必空」的假图标；本次是新增能预览的真通道 |
| 收包类型解析（判接收者是不是 `SqlMapClient`） | 既有 iBatis 通道的既定取舍：真实工程常经 Spring 代理注入、类型不可解析；存在性由目标兜底 |
| 跨文件 `<include refid="其他文件片段">` | `IbatisXmlPreviewService` 是纯文本入口（只解析一份 `fileText`），XML 语句行预览同样不支持；两条链口径一致 |
| Kotlin DAO 注册 | 跳转箭头本身只注册 `language="JAVA"`，预览与跳转保持同一覆盖面 |
| 选中区多候选（选中若干 DAO 调用后按快捷键） | 交互成本高、现有 iBatis 需求未涉及；列为后续（选择区仍落到既有的注解/未找到语义） |
| MCP `renderXmlSnippet` 的 iBatis 片段 | 片段形态无根 `<sqlMap>` 结构，判型不可靠；`renderAtLine` 的 iBatis 面本期补齐（§3.5） |

---

## 二、现状取证（file:line，读码核实）

### 2.1 锚点矩阵三格现状（L26 要求逐格核）

| 锚点格 | 现有 provider | 现状图标 | 判定门 |
|---|---|---|---|
| **XML 语句行**（`<select id="selectAll">`） | `IbatisXmlGutterLineMarkerProvider`（预览，`plugin.xml:269-270`）+ `IbatisXmlToJavaLineMarkerProvider`（箭头，`:636-637`） | **预览 + 箭头，恰 2 枚**（`IbatisNavigationLineMarkerProvidersTest:362` 已钉住） | 根标签 `sqlMap` + 标签名 ∈ 6 类语句 + `ibatisXmlEnabled` |
| **Java 调用行**（`return client.queryForList("User.selectAll");`） | `IbatisJavaToXmlLineMarkerProvider`（`plugin.xml:638-639`） | **只有箭头，1 枚** | 方法名 ∈ `STATEMENT_ENTRY_METHODS` + 首参 String 字面量 + `resolveStatementAnchors` 命中 |
| **Java 方法定义行** | 无 iBatis provider；`StringSqlMethodGutterLineMarkerProvider` 已让位 | **0 枚** | `IbatisCallYieldGate`（`StringSqlMethodGutterLineMarkerProvider.kt:107`） |

`JdbcTemplateSqlGutterLineMarkerProvider` 在调用行上也被同一个让位门挡住（`:67`，判据 `IbatisCallYieldGate` 在 `:166-191`）。

### 2.2 现有调用行 provider 的全部事实（图标改动的基座）

- 类：`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisJavaToXmlLineMarkerProvider.kt:58`
  - 慢趟入口 `collectSlowLineMarkers`（`:64`）：方法头单次取 `PluginSettings.state`，总闸 + `GutterGroups`（`mapperXml` 组）+ 竞品不让位；
  - `createMarker`（`:77`）：锚 `PsiIdentifier`（方法名 leaf）→ 方法名判定（`:82`）→ 首参 `PsiLiteralExpression`（`:83`）→ `DumbService.isDumb` 拦（`:86`）→ `IbatisXmlStatementTargets.resolveStatementAnchors`（`:87`）→ 非空才出箭头（`:89-95`）；
  - 点击 handler（`:113-148`）：重新解析（缓存命中）→ `OpenFileDescriptor` 落语句 `id` 属性值元素。
- 目标解析：`src/main/kotlin/…/ui/nav/IbatisXmlStatementTargets.kt:143`（`ns.id` 按 namespace 找文件；裸 id 全项目找，单槽 memo + 5s TTL），入口方法集在 `:71`。
- 预览服务：`src/main/kotlin/…/ui/IbatisXmlPreviewService.kt:81`（`preview(fileText, statementId, statementTagName, statementOrdinal)`）——**纯文本入口**，返回带 `ibatisParamModel` + `rerender` 闭包的 `PreviewResult`。
- XML 侧点击 handler：`IbatisXmlGutterLineMarkerProvider.kt:72-90`，取 `file.text` + `id` + `tag.name` + `statementOrdinalOf(tag)`（`:123-133`，`private`）。
- 语句模型 id 是**裸 id**：`SqlMapParser.kt:130-132`（`val rawId = attribute(element, "id"); val id = rawId ?: …`），namespace 只用于 `parameterMap` 引用（`:119`、`:264-265`）。
- 预览展示：`SqlPreviewService.previewAsync`（`SqlPreviewService.kt:2067`，后台池线程 + `ReadAction.compute` 包住 compute 块）、`showResult`（`:1985`）。
- 图标：XML 预览图标 `/icons/sqlGutter.svg`（`IbatisXmlGutterLineMarkerProvider.kt:93-94`）；箭头用 `MpvsIcons.MapperMethod`。
- 分组登记：`GutterGroups.kt:35-36`（两个 iBatis 跳转 provider 都归 `mapperXml`）。

### 2.3 快捷键现状（为什么现在「不能用」）

`ConvertToSqlAction.actionPerformed`（`src/main/kotlin/…/actions/ConvertToSqlAction.kt:68`）的光标分支（`:158-200`）：

1. 先 `service.preview(element, context)`（`:179-183`；`SqlPreviewService.kt:265-266` 内部还有一层 JDBC 兜底）→ iBatis 调用返回 null；
2. null 后按序回落：`tryHqlFallback`（`:188`）→ `tryNamedQueryFallback` → **`tryJdbcTemplateFallback`（`:190`，实现 `:247-255`）** → `tryJpaFallback` → **`tryMethodBodyStringSqlFallback`（`:192`，实现 `:264-269`）**；
3. `JdbcTemplateSqlLocator.locate`（`psource/JdbcTemplateSqlLocator.kt:55-60`）只判方法名 ∈ JDBC 白名单，**没有** iBatis 让位门（gutter 侧那道门在 `JdbcTemplateSqlGutterLineMarkerProvider.kt:67`）⇒ `client.queryForList("User.selectAll")` 被定位成 JDBC 候选；
4. `JdbcTemplateSqlPreviewService.preview`（`:71-76`）用 `isLikelySql("User.selectAll")` 拒绝 → null；`tryJdbcTemplateFallback` **已经 return true** ⇒ 链断，用户看到「未找到可转换的 Wrapper」（错源提示），后面的注解候选也不再尝试。

光标在方法定义行（`:27` 的 `public List<User> selectAll()`）：`tryMethodBodyStringSqlFallback` → `previewMethodCandidates`（`StringSqlMethodGutterLineMarkerProvider.kt:152-174`）→ `collectMethodCandidates`（`JdbcTemplateSqlPreviewService.kt:57-63`，同样无 iBatis 门）→ 1 个候选 → 预览 null → 同一个错源提示。**这就是「快捷键不能用」的现场机制**。

XML 语句行的快捷键**已经能用**：`previewXmlTag`（`:580-600`）对 `IbatisXmlGutterLineMarkerProvider.isStatementAnchor(tag)` 走 `IbatisXmlPreviewService.preview(file.text, id, tag.name)`（`:583-590`）；本次只把它切到共用装配（补 ordinal 回落），行为不变。

### 2.4 MCP 同源面现状（`sqlv_render_sql`）

`mcp/McpSourceLocator.kt` 自述是 `ConvertToSqlAction` 光标分支的**服务层复刻**（类注释「13 类触发面」）：

- XML 分支（`:167-200`）：只认 `XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS`，sqlMap 语句会被当 MyBatis 语句渲染（`XmlPreviewService`），iBatis 语法（`#name#`）不识别；
- Java 分支（`:200-222`）：JDBC 定位（无 iBatis 门）与方法体字符串 SQL（同缺口）之后没有 iBatis 分支 ⇒ 与快捷键同一处断开。
- 既有测试：`mcp/McpSourceLocatorRenderAtLineTest`（文件写盘 + 刷新 VFS 的夹具形态，可直接扩 iBatis 用例）。

### 2.5 demo 语料（验收对照）

`ibatis2-demo`：`UserDaoImpl.java` 20 个方法各一条 `client.queryForXxx("User.xxx", …)`（`selectAll` 在 `:27`、`insertUser` 在 `:101-105`）；语句全在同目录 `sqlmap/User.xml`（`namespace="User"`，`useStatementNamespaces="true"`），`<include>` 全部同文件。可直接当真人验收夹具。

---

## 三、方案

### 3.1 决策

| 编号 | 决策 | 依据 |
|---|---|---|
| **D-1** | 预览图标锚**调用行首参 String 字面量**，跳转箭头保持在方法名 identifier 不变；一行两枚图标 | 用户 2026-11-11 拍板；XML 语句行早已是「预览 + 箭头」同线两枚（§2.1），形态对齐 |
| **D-2** | 由**同一个 provider**（`IbatisJavaToXmlLineMarkerProvider`）产出两枚 marker，不新增 provider | 两枚 marker 需要同一份 `resolveStatementAnchors` 结果；同 provider 一次解析、一次 state 读取、一次慢趟遍历，不把项目级语句扫描翻倍 |
| **D-3** | 抽出「语句标签 / statementId → PreviewResult」的共用装配，gutter（Java/XML 两侧）、快捷键、MCP 四处共用 | 预览产物必须逐字节同源；顺带消掉 `statementOrdinalOf` 的潜在第二份拷贝 |
| **D-4** | **图标**受 `ibatisXmlEnabled` 约束（与 XML 语句行一致）；**快捷键/MCP 不受**该开关约束 | 该开关是 gutter 图标开关（现存 XML 语句行快捷键本来就不看它）；显式按键/工具调用＝用户意图，不应被图标显隐开关拦下 |
| **D-5** | 目标不存在 → 两枚图标都不出；快捷键/MCP 命中 iBatis 形状但目标不存在 → 给「语句未找到」**可见失败**，不回落 JDBC | 不制造「点开必空」的假图标；标识符形态首参本来就不是 SQL，回落 JDBC 只会得到错源提示 |
| **D-6** | 快捷键的 iBatis 分支插在 `tryJdbcTemplateFallback` **之前**，形状门复用 `IbatisCallYieldGate.isIbatisStatementIdCall`（与 gutter 让位同一函数） | 同一条「iBatis 优先、JDBC 让位」口径，三处（gutter/JDBC 通道、动作回落链、MCP）用同一个判据，不产生第二份形状正则 |
| **D-7** | 光标在**方法定义行或方法体任意处**时，按方法体内 iBatis 形状调用做候选（0/1/多：0 继续回落、1 直接预览、多走 `SqlSelectionDialog`），排在 `tryMethodBodyStringSqlFallback` 之前 | 用户按快捷键时常把光标停在方法名；不补这一档，定义行仍是「未找到」 |
| **D-8** | MCP `sqlv_render_sql` 同一轮补 iBatis（XML sqlMap 语句 + Java 调用行 + 方法体候选），`sourceType` 新增 `"iBatis …"` | 它是同一套定位链的服务层复刻；只修 IDE 侧等于把缺口留在 MCP 通道（同类面横向审计纪律） |
| **D-9** | 选中区候选本期不做 | 交互形态与候选 UI 需单独设计；当前选择区语义（注解候选/未找到）保持不变 |

### 3.2 共用装配（新增 `ui/gutter/IbatisStatementPreview.kt`）

```kotlin
internal object IbatisStatementPreview {
    /** 语句标签 → 预览产物；XML 语句行 / Java 调用行共用（只读 PSI，调用方须持读锁）。 */
    fun previewTag(tag: XmlTag): PreviewResult =
        IbatisXmlPreviewService.preview(
            fileText = tag.containingFile.text,
            statementId = tag.getAttributeValue("id"),
            statementTagName = tag.name,
            statementOrdinal = statementOrdinalOf(tag),   // 从 IbatisXmlGutterLineMarkerProvider:123 迁入
        )

    /** statementId → 目标语句标签 → 预览；目标缺失返回「语句未找到」可见失败（D-5）。 */
    fun previewResolved(project: Project, statementId: String, ownerNamespace: String?): PreviewResult

    fun statementOrdinalOf(tag: XmlTag): Int
    fun statementNotFound(): PreviewResult
}

/** 光标/调用行 → iBatis 调用目标（三处入口共用的形状门，复用 IbatisCallYieldGate）。 */
internal data class IbatisCallTarget(
    val call: PsiMethodCallExpression,
    val statementId: String,
    val ownerNamespace: String?,
)

internal object IbatisCallTargets {
    /** 最近的外层调用是 iBatis 形状即返回；否则 null（O(1) 语法门，零 resolve）。 */
    fun resolve(element: PsiElement): IbatisCallTarget?

    /** 方法体内全部 iBatis 形状调用（按 offset 序；方法级回落用）。 */
    fun callsInMethod(method: PsiMethod): List<IbatisCallTarget>
}
```

`previewResolved` 内 `anchor.parent?.parent as? XmlTag`（P-3）；`ownerNamespace` 与 L8 handler 同形态（上爬 `PsiClass.qualifiedName`）。

### 3.3 图标改动形态（`IbatisJavaToXmlLineMarkerProvider`）

```kotlin
override fun collectSlowLineMarkers(elements: …, result: …) {
    val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
    if (gutterState != null && !gutterState.showGutterIcon) return
    if (!GutterGroups.enabledFor(javaClass, gutterState)) return
    val previewEnabled = gutterState == null || gutterState.ibatisXmlEnabled   // D-4
    elements.forEach { element -> createMarkers(element, previewEnabled)?.let(result::addAll) }
}

private fun createMarkers(element: PsiElement, previewEnabled: Boolean): List<LineMarkerInfo<*>>? {
    // 原有 O(1) 门与 resolveStatementAnchors 一行不改（:77-88）
    …
    if (anchors.isEmpty()) return null                                   // D-5：两枚同生共死
    return buildList {
        add(LineMarkerInfo(element, element.textRange, MpvsIcons.MapperMethod, …))    // 跳转箭头（现状）
        if (previewEnabled) {
            add(LineMarkerInfo(literal, literal.textRange, IbatisXmlGutterLineMarkerProvider.SQL_PREVIEW_ICON, …))
        }
    }
}
```

预览点击 handler（EDT 只取字符串，解析与文件文本读取全部落在 `previewAsync` 的 ReadAction 内）：

```kotlin
override fun navigate(e: MouseEvent?, elt: PsiElement) {
    val statementId = ((elt as? PsiLiteralExpression)?.value as? String)?.trim() ?: return
    val owner = ownerNamespaceOf(elt)
    SqlPreviewService.previewAsync(project, {
        IbatisStatementPreview.previewResolved(project, statementId, owner)
    }) { result -> SqlPreviewService.showResult(project, result) }
}
```

同时把 XML 侧 handler（`IbatisXmlGutterLineMarkerProvider.kt:72-90`）改为调 `IbatisStatementPreview.previewTag(tag)`，行为逐字节不变（由既有测试守）。

### 3.4 快捷键改动形态（`ConvertToSqlAction`）

在 `:188` 的回落链最前面插入两档（实现放 companion 或同文件 internal 函数，便于定向测试）：

```kotlin
// 回落链：iBatis 优先（D-6/D-7），再 HQL / NamedQuery / JDBC / JPA / 方法体字符串 SQL
} else if (!tryIbatisCallFallback(project, element, sourceLocation)
    && !tryIbatisMethodFallback(project, element, sourceLocation)
    && !tryHqlFallback(service, project, element, sourceLocation)
    && !tryNamedQueryFallback(service, project, element, sourceLocation)
    && !tryJdbcTemplateFallback(project, element, sourceLocation)
    && !tryJpaFallback(service, project, element, sourceLocation)
    && !tryMethodBodyStringSqlFallback(project, element, sourceLocation)) { … }

private fun tryIbatisCallFallback(project: Project, element: PsiElement, sourceLocation: String?): Boolean {
    val target = IbatisCallTargets.resolve(element) ?: return false        // 形状门 O(1)
    SqlPreviewService.previewAsync(project, {
        IbatisStatementPreview.previewResolved(project, target.statementId, target.ownerNamespace)
    }) { result -> SqlPreviewService.showResult(project, result, sourceLocation) }
    return true                                                            // 形状命中即认领（D-5）
}

private fun tryIbatisMethodFallback(project: Project, element: PsiElement, sourceLocation: String?): Boolean {
    val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false) ?: return false
    if (method.body == null) return false
    val targets = IbatisCallTargets.callsInMethod(method)
    if (targets.isEmpty()) return false                                    // 0 → 继续回落
    if (targets.size == 1) {
        val t = targets[0]
        SqlPreviewService.previewAsync(project, {
            IbatisStatementPreview.previewResolved(project, t.statementId, t.ownerNamespace)
        }) { result -> SqlPreviewService.showResult(project, result, sourceLocation) }
        return true
    }
    // 多候选：复用 SqlSelectionDialog（摘要 = `方法名("statementId")（第 N 行）`）
    return true
}
```

两档都**不读 `ibatisXmlEnabled`**（D-4）；`previewResolved` 的目标解析与 `file.text` 全在 `previewAsync` 的 ReadAction 内（EDT 只做 `getParentOfType` + 字面量读）。

`previewXmlTag`（`:583-590`）改调 `IbatisStatementPreview.previewTag(tag)`（同一份产物，多一个无 `id` 语句的 ordinal 回落）。

### 3.5 MCP 同源面（`McpSourceLocator`）

- XML 分支：爬 tag 的同时判 `IbatisXmlGutterLineMarkerProvider.isStatementAnchor(tag)`，命中则 `IbatisStatementPreview.previewTag(tag)`，`sourceType = "iBatis sqlMap (${id})"`，**先于** MyBatis 语句渲染分支（现行会把 sqlMap 交给 `XmlPreviewService`，`#name#` 不识别）。
- Java 分支：在 `service.preview(element)` 之后、JDBC/HQL 之前插 `IbatisCallTargets.resolve(element)` → `previewResolved`，`sourceType = "iBatis (${statementId})"`。
- 方法体候选：`collectMethodCandidates` 之前先试 `IbatisCallTargets.callsInMethod(method)`，取首个（MCP 零弹窗契约：取首候选并在 `degraded` 说明多候选）。
- `renderXmlSnippet` 不做 iBatis（§1.3）。

### 3.6 门与开关（逐条）

1. 图标：总闸 `showGutterIcon` 两枚同灭；`mapperXml` 组两枚同灭；`ibatisXmlEnabled` 只灭预览、箭头保留；`DumbService` 两枚不出；目标不存在两枚不出（D-5）。
2. 快捷键/MCP：不读这三个开关（D-4），只受「文件类型可达 + 形状门 + 目标存在」约束。
3. 竞品 `MyBatisCodeHelperPro`：图标不让位（现状理由不变）；动作不涉及。

### 3.7 预览内容与安全面（复用既有链路，零新造）

- 产物＝`IbatisXmlPreviewService.preview` 的 `PreviewResult`：条件行、参数名与 `paramHints`、`<iterate>` 值列表、`<selectKey>` 块（主语句恒在最前 + 块注释标真实时序）、告警分档（⚠ / ℹ）全部与 XML 语句行预览一致。
- `$x$` 直替与 `${prop}` 原文段 → `hasDollarRawSegments` 照常置位，执行时仍走既有注入确认闸门；**不新开无闸门路径**。
- `rerender` 闭包随结果进面板：三处入口（图标/快捷键/MCP 之外的 UI 面板）看到的都是同一份可交互模型。

---

## 四、改动清单

| # | 文件 | 动作 |
|---|---|---|
| 1 | `src/main/kotlin/…/ui/gutter/IbatisStatementPreview.kt`（新增） | `IbatisStatementPreview`（previewTag / previewResolved / statementOrdinalOf / statementNotFound）+ `IbatisCallTarget` + `IbatisCallTargets`（resolve / callsInMethod） |
| 2 | `src/main/kotlin/…/ui/gutter/IbatisJavaToXmlLineMarkerProvider.kt` | `createMarker` → `createMarkers`（返回 List，两枚）；新增预览点击 handler；KDoc 重写（双 marker、锚点矩阵、误报面） |
| 3 | `src/main/kotlin/…/ui/gutter/IbatisXmlGutterLineMarkerProvider.kt` | 点击 handler 改调共用装配；`SQL_PREVIEW_ICON` 提为 `internal`；`statementOrdinalOf` 迁出 |
| 4 | `src/main/kotlin/…/actions/ConvertToSqlAction.kt` | 回落链插入 `tryIbatisCallFallback` / `tryIbatisMethodFallback`（D-6/D-7）；`previewXmlTag` 的 iBatis 分支改调 `previewTag` |
| 5 | `src/main/kotlin/…/mcp/McpSourceLocator.kt` | XML sqlMap 语句分支 + Java 调用行分支 + 方法体候选分支（D-8） |
| 6 | `src/main/resources/messages/MpSqlVisualizerBundle.properties` / `_zh.properties` | 新增 `gutter.ibatisCallPreview.tooltip` / `.accessible.name`；`settings.ibatisXml.tip` 补「Java DAO 调用行/快捷键同源」；`SqlSelectionDialog` 多候选摘要如需新 key 一并补 |
| 7 | `src/test/kotlin/…/ui/gutter/IbatisNavigationLineMarkerProvidersTest.kt` | 新增用例（§5.1）；修 `test点号id与裸id两种形态都出Java侧箭头`（现断言 marker 偏移**集合相等**，加预览 marker 后必红） |
| 8 | `src/test/kotlin/…/ui/gutter/IbatisStatementPreviewTest.kt`（新增） | 共用装配的取数链用例（跨文件 / 多语句 / ordinal / notFound / 调用目标形状正负例） |
| 9 | `src/test/kotlin/…/ui/gutter/IbatisGutterHotPathTest.kt` | 扫描清单 `navProviderScanMarkers` 的 `private fun createMarker` 改 `createMarkers`（签名漂移会让守卫 error，不是空绿） |
| 10 | `src/test/kotlin/…/actions/ConvertToSqlActionTest.kt` | 新增快捷键回落用例（§5.1 第 11~13 条），走 internal 测试缝 |
| 11 | `src/test/kotlin/…/mcp/McpSourceLocatorRenderAtLineTest.kt` | 新增 iBatis 两条（Java 调用行 / sqlMap 语句行 + sourceType 断言） |
| 12 | `dev-support/reverse-probes/ibatis-callpreview-disable-preview-marker.patch`、`ibatis-callpreview-disable-action-fallback.patch`（新增，可选） | 反向探针：预览 marker 产出点置 `false &&`；动作 iBatis 回落置 `false` |
| 13 | `docs/架构设计/预览触发面清单-说明文档.md` | 入口矩阵新增第 16 行（Java 调用行预览 + 快捷键/MCP 同源），负例边界补三档 |
| 14 | `CHANGELOG.md` `[Unreleased]` | 一句一条 Added 条目（随实施提交哈希） |

**不新增** `plugin.xml` 注册（同 provider 扩展、动作已存在），因此不触发 `verifyPlugin` / EP 解析守卫的新增面。

---

## 五、测试计划

### 5.1 定向用例（新增）

| # | 用例 | 判据 |
|---|---|---|
| 1 | 调用行**恰 2 枚**：箭头锚方法名 identifier（`MpvsIcons.MapperMethod`）+ 预览锚首参 literal（`sqlGutter` 图标），同一行 | `LineMarkerInfo.icon` 与锚点类型双断言；两枚同时存在/同时缺失 |
| 2 | 预览取数链：`queryForObject("User.selectById", id)` → `previewResolved` | `sql` 含 `FROM T_USER`、`unresolvedParams == ["id"]`、`ibatisParamModel != null` |
| 3 | 多语句文件按 id 精确定位 | 文件里两条语句，点第二条的预览出的不是第一条的 SQL |
| 4 | 跨文件 namespace：Java 调 `common.selectX`，语句在另一个 `Common.xml` | 预览取**目标文件**文本 |
| 5 | `<selectKey>` 形态：`insert("User.insertUser")` | 预览含主语句在前 + `-- [selectKey type=pre …]` 块 |
| 6 | 目标不存在（`"User.missing"`） | 预览与箭头都 0 枚（D-5）；`previewResolved` 返回「语句未找到」告警 |
| 7 | `ibatisXmlEnabled=false` | 预览 0 枚、箭头 1 枚；恢复 true 后 2 枚 |
| 8 | `mapperXml` 组关 / `showGutterIcon=false` | 2 枚全灭（既有用例已覆盖，扩断言到预览） |
| 9 | 非 iBatis 的 `jdbcTemplate.queryForList("SELECT …")` | JDBC 预览 1 枚、iBatis 0 枚（`JdbcTemplateIbatisMutualExclusionTest` 既有断言按方法名计数，锚文本不同，预期无需改） |
| 10 | 普通 Java 文件 / `<mapper>` 文件 | 零新图标（既有负例，扩到预览 marker） |
| 11 | **快捷键·调用行**：光标在 `client.queryForList("User.selectAll")` 任意位置 | `IbatisCallTargets.resolve` 命中；`previewResolved` 出 SQL；`tryJdbcTemplateFallback` 不再认领（经 internal 缝断言） |
| 12 | **快捷键·方法定义行**：光标在 `public List<User> selectAll()` | `callsInMethod` 出 1 个目标；预览与调用行同一条 SQL |
| 13 | **快捷键·负例**：普通 JDBC 仓库 `queryForList("SELECT …")`、普通 `service.queryForList("user.list")`（目标不存在） | 前者仍走 JDBC（不受影响）；后者给「语句未找到」可见失败，不弹 JDBC 错源提示 |
| 14 | **MCP**：`sqlv_render_sql` 对 `UserDaoImpl.java:27` 与 `User.xml:55` | 均返回 iBatis SQL，`sourceType` 以 `iBatis` 起头；`renderXmlSnippet` 行为不变 |

### 5.2 反向验证（先红后绿，两组读数入实施记录）

1. 摘预览 marker 产出（`if (previewEnabled)` 改 `if (false && previewEnabled)`）→ 用例 1/2/3/4/5/7 必红；恢复后全绿。
2. 摘 `ibatisXmlEnabled` 门（恒 true）→ 用例 7 必红；恢复后全绿。
3. 摘共用装配里的 `statementOrdinal`/`previewTag` 改回「按文件首条语句」→ 用例 3 必红。
4. 摘动作的 `tryIbatisCallFallback` → 用例 11 必红（回落 JDBC 后得到错源提示/空结果）；摘 `tryIbatisMethodFallback` → 用例 12 必红。
5. 摘 MCP 的 iBatis 分支 → 用例 14 必红（sqlMap 走 MyBatis 渲染器 / Java 走 JDBC 拒绝）。

### 5.3 既有用例影响（逐条点名）

| 用例 | 影响 |
|---|---|
| `IbatisNavigationLineMarkerProvidersTest:176` `test点号id与裸id两种形态都出Java侧箭头` | **必红**——它断言 marker 元素偏移集合与两个 identifier 偏移**相等**；改按 `element is PsiIdentifier` 过滤后再比 |
| `:160` `testJava调用行出箭头_点击落到Xml语句id值` | 按 `element === callName` 取 single，预览锚是 literal，预期仍绿 |
| `:242/:249/:257/:271` 三个负例 | 用 `.contains(offset)` 断言，预期仍绿 |
| `:290` 竞品在位用例 | 按偏移 `.contains`，预期仍绿 |
| `:350` `test同一Java调用行MyBatis与iBatis各归各` | 按 `element === callName` 计数 1，预期仍绿 |
| `:362` XML 语句行共存恰 2 | 不在本次改动面，作为「同线两枚可渲染」的现状证据 |
| `JdbcTemplateIbatisMutualExclusionTest` 全部 | 按 `element.text == 方法名` 计数（预览锚文本是字面量），预期全绿 |
| `IbatisGutterHotPathTest` | 仅扫描清单的方法名需随 `createMarker → createMarkers` 同步 |
| `ConvertToSqlActionTest`（含 `isXmlStatementCandidate` 的 iBatis 三例） | 现状全绿；本批新增用例，既有断言不动 |
| `McpSourceLocatorRenderAtLineTest` | mapper 用例基线不变；新增 iBatis 用例 |
| `SqlPreviewServiceJpaTest` / `JdbcTemplateSqlPreviewServiceTest` | 不触达（新分支在其之前，且形状门互补） |

### 5.4 真机验收（用户执行；助手只出步骤与判据）

前置：构建/安装含本改动的版本，打开 `ibatis2-demo` 项目。

1. 打开 `UserDaoImpl.java`。**预期**：每个 `return client.queryForXxx("User.xxx", …)` 行左侧**两枚**图标（红箭头 + 绿色预览）。
2. 点 `selectAll`（`:27`）那行的绿色预览图标。**预期**：SQL 为 `SELECT ID, USER_NAME, EMAIL, AGE, STATUS FROM T_USER`（含 `<include>` 展开），参数区为空、无 ⚠。
3. 点 `selectById`（`:31`）。**预期**：`WHERE ID = ?`，参数名 `id`，填 `1` 后 SQL 实时变化。
4. 点 `selectByCondition`（`:71`）。**预期**：出现条件行（`id`/`username`/`status`/`age`），勾选后 SQL 追加 WHERE 段。
5. 点 `insertUser`（`:101`）。**预期**：主语句在前 + `-- [selectKey type=pre …]` + `SELECT SEQ_USER_ID.NEXTVAL FROM DUAL`。
6. 点绿色预览后再点同一行红箭头。**预期**：仍跳转到 `User.xml` 对应语句 `id` 属性值（跳转零回归）。
7. 打开 `User.xml`，点语句行绿色预览图标。**预期**：与改动前完全一致。
8. 设置里关掉「Preview iBatis 2.x sqlMap statements」。**预期**：绿色预览消失、红箭头仍在（图标面）；快捷键仍可用（D-4）。
9. 关掉 `mapperXml` 分组或总开关。**预期**：两枚一起消失。
10. **快捷键·调用行**：光标停在 `return client.queryForList("User.selectAll");` 任意位置，按 `Alt+Shift+Q`。**预期**：直接出 `selectAll` 的预览（与点图标同一产物），**不出现**「未找到可转换的 Wrapper」。
11. **快捷键·方法定义行**：光标停在 `public List<User> selectAll() throws SQLException {`，按 `Alt+Shift+Q`。**预期**：同上。
12. **快捷键·对照**：光标停在普通 JDBC 仓库的 `jdbcTemplate.queryForList("SELECT … WHERE ID = ?")`，按快捷键。**预期**：仍是 JDBC 模板预览（零回归）。
13. **快捷键·XML**：光标停在 `User.xml` 的 `<select id="selectAll">` 标签行，按快捷键。**预期**：与改动前一致（iBatis 预览）。
14. **MCP**：对 `ibatis2-demo` 调 `sqlv_render_sql`，分别给 `UserDaoImpl.java:27` 与 `User.xml:55`。**预期**：两次都返回 iBatis 渲染后的 SQL，`sourceType` 以 `iBatis` 起头。

失败时该给的读数：`idea.log` 搜 `ibatis`（含 `IbatisXmlPreviewService` 的 warn）；预览面板来源列；MCP 返回的 `sourceType`/`error` 原文；`File → Invalidate Caches` 后复现一次（排除旧 jar）。

---

## 六、性能与安全红线

- **图标热路径零新增**：`getLineMarkerInfo` 仍恒 `null`；慢趟只在既有的方法名/字面量/存在性判定之后多构造一枚 `LineMarkerInfo`，不新增 resolve、不新增索引查询、不新增项目扫描。
- **不翻倍项目扫描**（D-2）：两枚 marker 共用同一份 `anchors`。
- **点击/按键期解析遵守两段式**：EDT 只做 `PsiTreeUtil.getParentOfType` + 字面量/限定名读取（O(1)），`resolveStatementAnchors` 与 `file.text` 全部在 `previewAsync` 的 `ReadAction.compute` 内；不跨线程读已捕获 PSI。
- **快捷键不引入新的扫描**：`IbatisCallTargets.resolve` 只用已完成的 `getParentOfType` + `IbatisCallYieldGate`（纯语法），目标解析与 gutter 共用同一份 memo/TTL。
- **方法级回落有边界**：`callsInMethod` 只遍历方法体子树的调用表达式（与 `StringSqlMethodGutterLineMarkerProvider` 同量级），不触项目索引。
- **写安全**：预览 SQL 的 `$x$`/`${}` 闸门随 `PreviewResult.hasDollarRawSegments` 继承；多语句（selectKey）组装顺序保持「主语句恒在最前」。
- **零回归面**：不动 `SqlMapParser`/`SqlMapSqlRenderer`/`IbatisParamModel`；MCP 只新增分支，不改既有 `sourceType` 取值。

---

## 七、待验证前提（实施者先证伪再动手）

| # | 前提 | 证伪方式 / 退路 |
|---|---|---|
| P-1 | 同一行两个**不同锚**的 marker 会被平台并排渲染成两枚图标 | 现状证据：XML 语句行预览（tag name token）+ 箭头（`id` 值）已在真机并排显示。若真机不渲染，退路＝两枚都锚方法名 identifier |
| P-2 | 在 `collectSlowLineMarkers` 里为同文件的**另一个元素**（首参 literal）产出 marker 合法 | 平台契约按文件收集；literal 与 identifier 同行同段。用例 1 覆盖 |
| P-3 | `XmlAttributeValue.parent.parent` 是语句 `XmlTag` | 用例钉住（`selectAll` 的 `id` 值 → tag 名 `select`）；不符则改 `PsiTreeUtil.getParentOfType(anchor, XmlTag::class.java)`（点击期，非热路径） |
| P-4 | 语句模型 id 是裸 id，可直接用 `tag.getAttributeValue("id")` 定位 | 已读码实证（`SqlMapParser.kt:130-132`）；用例 3 兼作回归 |
| P-5 | `statementOrdinalOf` 迁出后 XML 侧预览逐字节不变 | `IbatisXmlGutterLineMarkerProviderTest` + `IbatisXmlPreviewServiceTest` 改前改后双跑 |
| P-6 | `IbatisCallYieldGate` / `IbatisXmlStatementTargets`（均 `internal`）可从 `actions`/`mcp` 包访问 | 同模块 internal 可见；编译器即判据 |
| P-7 | `SqlSelectionDialog<T>` 可承载自定义候选类型（现用法有 `PsiMethodCallExpression` / `XmlTag` / `PsiAnnotation` 三类） | 读泛型签名；新增 `IbatisCallTarget` 为第四类，用例 12 的多候选档覆盖 |
| P-8 | 动作的 iBatis 回落放在 `tryJdbcTemplateFallback` 之前，不会抢走真正的 JDBC 调用 | 形状门要求「首参是标识符形态字面量」，SQL 文本必然含空格/括号/`*`/`?`；用例 13 正负例钉住 |
| P-9 | MCP `sourceType` 新增取值不破坏既有消费者 | 该字段是展示/诊断用自由文本（现有 `"mapper XML (id)"` / `"JdbcTemplate"` 等异构值）；只增量、不改既有值 |

---

## 八、风险

| # | 风险 | 处置 |
|---|---|---|
| R-1 | 用户上次反馈过「一行两枚图标」；本次是**刻意**两枚 | 两枚各有功能且与 XML 语句行同形态；验收步骤 1 明写「应有 2 枚」 |
| R-2 | 误报面：非 iBatis 代码里「首参是标识符形态字符串 + 恰好命中某 sqlMap 语句 id + 方法名在入口集」→ 出预览图标/快捷键认领 | 与现有箭头**完全同一误报面**；形状门是 iBatis 认领条件的超集，不扩大 |
| R-3 | 快捷键行为变化：原先给「未找到」，现在给 iBatis 预览或「语句未找到」 | 正是本次修复目标；普通 JDBC 不受影响（R 见 P-8）；`R-2` 形态给可见失败而非错源提示 |
| R-4 | 点击/按键时语句被改名/删除 | `previewResolved` 返回「语句未找到」告警结果（可见失败），不静默 |
| R-5 | 反向探针与既有扫描清单漂移 | §4 第 9/12 条显式列入；`IbatisGutterHotPathTest` 找不到方法体会 error（不是空绿） |
| R-6 | MCP 里 sqlMap 文件的 `sourceType` 从「mapper XML」变成「iBatis sqlMap」 | 只影响 sqlMap 文件（原先那条路径本身就是错渲染）；mapper 文件行为不变，用例 14 与既有 mapper 用例对照 |

---

## 九、实施记录（回填）

- 分支/工作树：
- 提交：
- 定向读数（类/例/失败/错误）：
- 反向验证五轮读数（图标产出 / 图标开关 / 共用装配 / 动作两档 / MCP）：
- 既有用例影响核对（§5.3 逐条）：
- CHANGELOG / 架构文档 / MCP 工具文档回填：
- 真机验收进度（用户）：
