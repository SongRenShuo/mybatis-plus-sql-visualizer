# T-PagesHql：maintainPage XML 的 `<datasource>` 识别 HQL 并按实体推断转换（2026-11-05）

> 状态：**已实施并合 master**（`98554a4f`+`d04ffa2d`，泛化件 `8ce33ee4` 删零产出常量；D-1~D-6 拍板与实施记录见本册 §实施记录）
> 触发：用户 2026-11-05 反馈「这种场景下的 hql 没有正常识别」，并建议「通过 entityClass 进行推断」。
> 样本：`mybatis-plus-sql-visualizer-demo/mybatis-plus-demo/src/main/resources/pages/user-maintain.xml`；真实工程截图（`<pages>/<maintainPage entityClass="com.vtradex...BaseLocation">/<datasource>` 内为 HQL，含 `#{SESSION_BASE_ORGAN}` 框架占位符与三张副表）。

---

## 一、问题定位（先说结论）

1. **不是「认不出这个文件」**。`<pages>` 根元素的分派、gutter 图标、维护页（maintainPage）候选、列信息页签全部正常，用户截图里「方言 MySQL / 表 base_location / 列信息」都在，说明入口链是通的。
2. **缺的是「语言判定 + 转换」这一步**。`PagesXmlPreviewService.toResult` 把 `<datasource>` 的 CDATA 原文**直接当原生 SQL** 塞进 `PreviewResult.sql`（`ui/PagesXmlPreviewService.kt:45-67`），不做 HQL→SQL 转换：
   - `FROM BaseLocation location` 原样展示，真正执行会报「表不存在」；
   - `location.updateInfo.beDelete` 这种**实体属性路径**不映射列名；
   - `:name` / `?1` 不做参数替换，`#{...}` 更是完全不识别（只数 `?`，`ui/PagesXmlPreviewService.kt:87-90`）；
   - `LEFT JOIN BaseLocation dpLocation ON ...` 的副表实体不映射成表名。
3. **`entityClass` 现在的用途只是补全上下文**：`resolveEntityContext` 用 MyBatis 版 `EntityResolver` 取表名/列名/注释（`ui/PagesXmlPreviewService.kt:70-84`），不参与「这段是 SQL 还是 HQL」的判定，也不做 HQL 根实体映射。用户截图里「表：base_location」正是这条链的产物，容易让人误以为 HQL 已被识别。
4. **这不是新发现的需求，而是 F-45 的历史欠账**：仓库记忆明确写着「`<datasource>` 内容为 HQL（Hibernate Query Language），非原生 SQL」，并把「HQL → SQL 转换预览（复用 HqlConverter）」列为后续可选项（`docs/memory-archive/bug-fixes/project-pages-xml-preview.md:16、60`），一直没落。
5. **仓库里已有一条成熟的 HQL 管道**，只差接入：`XmlInlineSqlPreviewService` 对带 `type="hql"` 的通用 XML 载体已经在用 `previewHbmNamedQuery` 做同样的转换（`ui/XmlInlineSqlPreviewService.kt:29-45`）——pages 缺的正是这个「显式类型信号」，而 `entityClass` 就是天然信号。

---

## 二、现状证据（源码与行号）

### 2.1 生产入口只有两条，都落在同一个纯函数

| 入口 | 位置 | 传入 |
|---|---|---|
| gutter 图标点击 | `ui/gutter/PagesXmlGutterLineMarkerProvider.kt:58-68` | `file.text` + `maintainPage@id` + project（**不传 PSI 标签**） |
| Action（光标/选区/全文） | `actions/ConvertToSqlAction.kt:87-97` → `:609-655` / `:662-693` → `:696-702` | 同上 |

- Action 的 pages 分派判据是 `file is XmlFile && file.rootTag?.name == "pages"`（`actions/ConvertToSqlAction.kt:87-89`），命中后 `return`，不会落进 mapper 分支。
- gutter 判据是「`maintainPage` 的直接父标签是 `pages`」（`ui/gutter/PagesXmlGutterLineMarkerProvider.kt:35-45`）。
- 两条入口最终都调用 `PagesXmlPreviewService.preview(fileText, id, project)`（`ui/PagesXmlPreviewService.kt:26-42`）——纯文本 + 可空 project，这是本项目里唯一的 pages 预览实现点，改造点集中。

### 2.2 解析层已把 entityClass 取好，缺的是语言字段

`xml/PagesXmlParser.kt:6-13、54-66`：`PagesStatement(id, title, autoQuery, entityClass, sql, columns)`。
- `sql` = `<datasource>` 的 `textContent.trim()`（`:60-61`）；
- `entityClass` 已解析但只有预览服务用；
- `<datasource>` **没有**读 `type` 属性（若框架某些版本带 `type="hql"`，现在是丢掉的）。

### 2.3 参数口径（两种模式共用同一处短板）

| 形态 | 现行为 | 依据 |
|---|---|---|
| `?` | 朴素 `sql.count { it == '?' }` → `param1..N`（引号/注释内也算） | `ui/PagesXmlPreviewService.kt:87-90` |
| `:name` | 不提取，原样展示 | `ui/PagesXmlPreviewService.kt:49` |
| `#{...}` | 不识别，原样展示，无参数行 | 同上 |
| 面板回填 | `placeholderPositions` 是 string-aware 的（跳过引号/注释），与服务端计数**口径不一致** | `sql/PlaceholderPositions.kt:12-43`、`ui/SqlPreviewPanelParamPanel.kt:1862` |

### 2.4 可复用的 HQL 管道（关键结论）

`SqlPreviewService.previewHbmNamedQuery(query, hostMethod, definitionTag, context)`（`ui/SqlPreviewService.kt:1157-1206`）已经是一条完整的「HQL 文本 → PreviewResult」链：

- `HQL` 分支：`extractEntityNameFromHql` → `resolveHqlTable(null, entityName, hql, context)` → `HqlConverter.convert(...)` → `applyJpaMethodEnhancements` → `buildJpaPreviewResult`（`:1188-1203`）；
- `resolveHqlTable` 内部：`EntityAliasSweep.resolve`（支持 `@Entity(name)` 别名，`SCAN_LIMIT=5000`，dumb 态直接 unresolved）→ `JpaEntityResolver().resolve(clazz, hostAnchor)` → 列空时 `HbmXmlEntityResolver` 兜底（`ui/SqlPreviewServiceEnhancements.kt:510-550`）；
- 产物已含：`sql`（已做参数/实体/属性映射）、`columns`、`tableName`、参数名序 `placeholderParamNames`、`paramHints`、方言决策 `dialectDecision`、BlockAttack warning（`ui/SqlPreviewService.kt:1363-1367`）；
- `XmlInlineSqlPreviewService` 已验证「转换管道异常 → 回退原文 + warning，不抛错」这条降级线（`ui/XmlInlineSqlPreviewService.kt:29-45`）。

`HqlConverter` 的能力面（`converter/jpa/HqlConverter.kt:86-94`，`Result` = `sql` / `statement` / `inClauseParams` / `placeholderParamNames`，**无 warning、无 degradation 字段**）：

- 认：命名参数 `:name`、位置参数 `?1`、`IN :ids`、`JOIN FETCH`、`new Xxx(...)`、嵌入属性展平、按别名作用域做属性→列映射；
- 不认：`#{}`（全链零处理）、隐式关联路径（无显式 JOIN 的 `u.dept.name` 只注释）、关联路径 JOIN 的副表别名、逗号分隔 FROM、`MEMBER OF`、CTE/UNION 内的 `SELECT u` 展开。

### 2.5 契约测试（会被本券改动）

`src/test/kotlin/.../ui/PagesXmlPreviewServiceTest.kt:172-191` 用例 `HQL内容_直接展示无参数` 明确断言「HQL 原样展示、无参数」——这是把当前缺陷钉成了契约。本券必须改写它（用户需求本身就是契约变更），并在提交信息里写明。

### 2.6 `entityClass` 不等于「HQL 根实体」（反例先摆出来）

demo 的 `orderMaintain`：`entityClass="cn.song.mybatisplusdemo.entity.Order"`，但 `<datasource>` 是**原生 SQL** `FROM t_order o INNER JOIN user u ...`。所以**不能**用「有 entityClass ⇒ HQL」做判据（会把原生 SQL 页误判）。判据必须是「`entityClass` 与语句根标识符的关系」——详见 §3。

---

## 三、需求裁决：什么算「识别 HQL」

### 3.1 证据等级

| 等级 | 证据 | 说明 |
|---|---|---|
| E1 显式 | `<datasource type="hql\|jpql">` | 无条件按 HQL 处理（当前解析器丢弃该属性，需补） |
| E2 强 | 根标识符 == `entityClass` 的实体名（简单名或 `@Entity(name)`） | 用户样本形态：`FROM BaseLocation` ↔ `...BaseLocation` |
| E3 强 | 根标识符 == `entityClass` 的**表名** | 反向判据：明确是 SQL（如 `FROM t_order`） |
| E4 中 | 根标识符能解析为持久化实体，且其表名 ≠ 根标识符 | 根名不是表名 ⇒ 是实体名 ⇒ HQL |
| E5 中 | 命中 HQL 专属记号（`JOIN FETCH` / `MEMBER OF` / `new X(` / 位置参数 `?1`） | 与 E4 组合提高置信度 |
| E6 弱 | 含 `#{...}` 框架占位符 | 只作加权，不作单独判据（原生 SQL 页也可能用） |

### 3.2 判定顺序（首命中即定，且**默认 SQL**）

1. `type` ∈ {hql, jpql} → **HQL**（证据 `explicit-type`）。
2. `entityClass` 非空：根标识符 == 实体名 → **HQL**（`root-is-entity-class`）；根标识符 == 表名 → **SQL**（`root-is-table`）。
3. `entityClass` 空或上一步不可判（PSI 解析不到）：根标识符解析为持久化实体，且「根名 ≠ 解析出的表名」或「E5 命中」→ **HQL**（`root-resolves-entity`）。
4. 其余 → **SQL**（`default-sql`，零行为变化）。

**默认 SQL 是硬约束**：宁可漏判（用户仍可强制），不可错判——把原生 SQL 当 HQL 转换会产出错误 SQL，比现状更糟。

### 3.3 纯函数优先，PSI 只做兜底

第 1、2 步只需字符串（根标识符 + `entityClass` 简单名），**不依赖索引**；第 3 步才碰 PSI（`EntityAliasSweep`/`JpaEntityResolver`）。这样：
- 纯 JUnit 就能覆盖用户样本形态（`PagesXmlPreviewServiceTest` 无 project 也能判对）；
- dumb 索引期只退化到第 2 步，不会误判。

---

## 四、方案

### 4.1 新增纯函数检测器（约 120 行，独立文件）

`src/main/kotlin/cn/song/mybatisplus/visualizer/xml/PagesSourceLanguage.kt`

```kotlin
enum class PagesSourceLanguage { SQL, HQL }

data class PagesSourceDetection(
    val language: PagesSourceLanguage,
    val evidence: String,          // 稳定 id，用于 i18n 与日志：explicit-type / root-is-entity-class / root-is-table / root-resolves-entity / default-sql
    val rootIdentifier: String?,   // HQL 根标识符（用于 entityAlias 入参）
)

object PagesSourceLanguageDetector {
    fun detect(
        sql: String,
        entityClassName: String?,               // entityClass 原值（FQCN 或简单名）
        declaredType: String?,                  // <datasource type="...">
        rootIdentifierOf: (String) -> String?,  // 注入 HqlEntityNameExtractor::extract（保持 xml 包不依赖 psi）
        probeEntity: (String) -> EntityProbe?,  // 可空：null 时只用 E1/E2/E3
    ): PagesSourceDetection
}

data class EntityProbe(val entityName: String?, val tableName: String?)
```

- 判据实现即 §3.2，纯 Kotlin，无 PSI；
- `xml` 包**不 import** `psi.*`：`rootIdentifierOf` 与 `probeEntity` 由调用方注入（与 `HqlConverter.convertWithEntityResolver` 的注入风格一致，`converter/jpa/HqlConverter.kt:103-110`）；
- 同样在此文件放**框架占位符预处理**（见 4.3），因为它只做文本变换，和语言判定同属「解析前的文本准备」。

### 4.2 转换接入：复用 `previewHbmNamedQuery`，不新造管道

`ui/PagesXmlPreviewService.preview` 改为三段式：

```
parse → detect → (HQL ? previewAsHql : toResult)
```

`previewAsHql` 的实现（新增私有函数，约 40-60 行）：

1. `project ?: return plainResult(... + warning "HQL 需要工程索引才能转换")`；
2. 预替换 `#{...}`（4.3）；
3. `project.getService(SqlPreviewService::class.java).previewHbmNamedQuery(HbmXmlMapping.NamedQuery("", HQL, text), definitionTag = datasourceTag, context = datasourceTag)`；
   - 传 `definitionTag` 而非让它回查 `<named-query>`（`ui/SqlPreviewService.kt:1164-1166`）；
   - `context` 用 `<datasource>`/`<maintainPage>` 的 `XmlTag`，让 `EntityAliasSweep` 按**该 XML 所属模块**收检索范围（`ui/SqlPreviewServiceEnhancements.kt:519`）；
4. 返回值 `null` 或抛异常 → **回退原文直显 + warning**（照抄 `ui/XmlInlineSqlPreviewService.kt:40-43` 的降级语义，不得抛错、不得空白）；
5. 成功后 `.copy(...)`：
   - `pagesColumns = statement.columns.ifEmpty { null }`（列信息页签不能丢，`ui/PagesXmlPreviewService.kt:65`）；
   - `originalSql = rawHql`、`sourceLanguage = HQL`（4.4 新字段）；
   - `warning` 合并：转换降级清单（4.4）+ 原 warning（BlockAttack）。

**签名变更**：`preview(fileText, maintainPageId, project, contextTag: XmlTag? = null)`。两个生产调用点（gutter `:63-64`、Action `:698`）把当前 `tag`/`statement` 传进来即可；`contextTag = null` 时行为等价旧版（纯测试路径不受影响）。

> 不建议另写一条 pages 专用转换：参数名序、`IN` 参数、方言决策、列元数据这些细节，`previewHbmNamedQuery` 已经逐项处理过，重复实现必然分叉（本仓已有「两套 SQL 还原器口径不同源」的教训）。

### 4.3 `#{}` 框架占位符

真实样本里 `location.orgId = #{SESSION_BASE_ORGAN}`、`sh.shareToOrgId = #{SESSION_BASE_ORGAN}`。这是框架在把 HQL 交给 Hibernate **之前**做值替换的占位符（P-3 待证伪），Hibernate 不认识 `#{}`，原样进 SQL 必错。

预处理口径（纯函数，`PagesSourceLanguage.kt` 内）：

| 输入形态 | 输出 | 参数显示名 |
|---|---|---|
| `#{SESSION_BASE_ORGAN}` | `:SESSION_BASE_ORGAN` | `SESSION_BASE_ORGAN` |
| `#{a.b.c}`（含点/非法标识符） | `:__p_1`（序号命名） | `a.b.c`（保留原名供面板显示） |
| `#{}`（空） | 原样保留 | 不产生参数 |
| 字符串字面量内的 `#{...}` | **不替换**（掩码） | — |

- 替换发生在 `previewHbmNamedQuery` **之前**，这样 `:name` 走 HqlConverter 的既有命名参数链（`:name` → `?` + `placeholderParamNames`，`converter/jpa/HqlConverter.kt:136-139`），面板按名回填自动生效；
- 与 HQL 自带 `:x` 同名时：**默认合并为同一参数**（语义相同，值一致）；若担心语义不同，D-3 提供「加前缀隔离」选项；
- 字面量掩码复用 `converter/jpa/JpaSqlLiteralMasking.kt` 的思路（该文件已用于实体名替换前的掩码）。

### 4.4 证据与降级必须可看见（新增两个 `PreviewResult` 字段）

现状：`warning` 是红色风险文案（`ui/SqlPreviewPanelResultView.kt:570-612`），不适合承载「已按 HQL 转换」这种中性信息；转换器把降级说明写在 SQL 注释里（`/* 关联字段，需 JOIN ...*/`），用户不看 SQL 就不知道。

在 `model/PreviewResult.kt` **末尾追加**（带默认值，命名实参调用点零影响）：

```kotlin
/** 源语言：HQL 被转换过时非 SQL，面板 meta 行展示「来源：HQL → SQL」。 */
val sourceLanguage: SourceLanguage = SourceLanguage.SQL,
/** 转换前的原文（仅 sourceLanguage != SQL 时非空），面板「原文」只读页签展示。 */
val originalSql: String? = null,
```

`enum class SourceLanguage { SQL, HQL, JPQL }` 放 `model/`。

- meta 行渲染（`ui/SqlPreviewPanelResultView.kt:562-571` 同一处）：`HQL（已转换）`，中性前景色；
- 「原文」页签：`originalSql != null` 时出现（页签构造点 `ui/SqlPreviewPanelResultView.kt:231-232` 一带），只读、可复制；
- **降级清单**：从转换器产物里收集。首选给 `HqlConverter.Result` 追加 `val degradations: List<String> = emptyList()`（在插入注释的同一处 append，注释文案不再当接口用）；退路是对产物 SQL 扫已知注释标记（脆，只作临时方案）。收集后**压成一行、分号分隔、超长截断**并入 `warning`（P-5 测多行/warning 布局）。

### 4.5 设置与临时覆盖（D-1）

判错方向不对称：漏判＝维持现状；错判＝给出错误 SQL 并可能被执行。因此需要逃生舱：

- 设置页新增三态下拉 `pagesXmlSourceMode`：`自动（默认）/ 仅 SQL / 仅 HQL`；
- 选中「自动」时按 §3.2 判定并显示证据；「仅 SQL」跳第 3 步，「仅 HQL」跳第 1 步；
- 证据串进日志（`[T-PagesHql] page=xxx evidence=root-is-entity-class root=BaseLocation`），排障直接可查。

改动面（沿用 `xmlInlineSqlEnabled` 的注册范式）：`config/PluginSettings.kt`（state 字段）、`config/PluginSettingsPanel.kt`（控件/`isModified`/`apply`/`reset` 四处）、`config/PluginSettingsPanelSections.kt`（布局行）、`config/PluginSettingsFolderConfigurable.kt`（`SearchableField`）、`MpSqlVisualizerBundle.properties`（标题 + 提示 + 选项文案）。**守卫**：设置面板绑定测试、搜索索引可检索测试、复选框计数不受影响（下拉不是复选框，但 `SearchableField` 要登记）。

### 4.6 隐式关联路径与自动 JOIN：本券只提示，不改写

样本里 `location.nodeAddress.postcode`、`parentLocation.nameTree`：
- 若 `nodeAddress` 是 `@Embedded` → 现有展平链可直接映射（`psi/jpa/JpaEntityResolver.kt:313-390`、`converter/jpa/JpaAliasTableResolver.kt:316-328`）；
- 若是 `@ManyToOne` → 三段路径属于「隐式关联路径」，转换器**只保留原文 + 注释**，产物不可执行（`converter/jpa/HqlConverter.kt:370-402`）。

本券把这类降级**显式呈现**（4.4 清单）；自动补 `LEFT JOIN` 需要新写关联 JOIN 推导（选择 @ManyToOne 目标、join column、多段路径级联、与已有别名冲突处理），风险与工作量都不小，列为 D-5 的后续券候选（W6，可选）。

### 4.7 明确不做

- 不引入 Hibernate 运行时/反编译依赖（vTradex 私有框架拿不到，禁止编）；
- 不改 gutter/入口分派判据、不改文件类型注册、不覆盖 `XmlInlineSqlDetector` 的认领规则（`xml/XmlInlineSqlDetector.kt:51-52` 已让位 `<datasource>`）；
- 不做 pages 的 MCP 暴露（`mcp/McpSourceLocator.kt:153-158` 现有显式拒绝保持）；
- 不做 HQL 模式下的实体属性补全（预览面板对新实体列的补全是另一张券）；
- 不改 `HqlConverter` 的转换语义（只追加 `degradations` 字段）。

---

## 五、决策点

| 编号 | 决策 | 建议 |
|---|---|---|
| D-1 | 覆盖开关形态：A 无开关（仅自动）／B 三态下拉（自动/仅 SQL/仅 HQL）／C 每页临时切换按钮 | **B**（判错代价不对称；C 的 UI 成本更高且状态不持久） |
| D-2 | 转换失败/实体不可解析的呈现：A 原文 + warning（现有 inline 语义）／B 空面板 + 错误 | **A**（验收底线：至少能看到原文） |
| D-3 | `#{X}` 与 HQL `:X` 同名：A 合并同值／B 加前缀隔离 | **A**（同一框架内语义一致；B 会让参数面板出现两个近似行） |
| D-4 | 是否加「原文」页签与 `sourceLanguage` 中性标记 | **是**（否则用户无法确认插件到底做了转换，与本次投诉同源） |
| D-5 | 隐式关联路径自动 JOIN：A 本券不做、只提示／B 本券实现 | **A**；B 另开券 |
| D-6 | 顺带修 `?` 计数口径（朴素 vs string-aware） | **是**（HQL 模式下 `?` 来自 `?1` 转换，计数错会直接错配参数行；独立用例钉住） |

---

## 六、波次与写集

| 波次 | 内容 | 写集（生产） | 依赖 |
|---|---|---|---|
| W1 | 检测器 + `#{}` 预处理（纯函数） | `xml/PagesSourceLanguage.kt`（新）、`xml/PagesXmlParser.kt`（读 `datasource@type`、`PagesStatement` 增 `declaredType` 字段） | — |
| W2 | 转换接入 | `ui/PagesXmlPreviewService.kt`、`actions/ConvertToSqlAction.kt`（传 tag）、`ui/gutter/PagesXmlGutterLineMarkerProvider.kt`（传 tag） | W1 |
| W3 | 证据与降级可见 | `model/PreviewResult.kt`、`model/SourceLanguage.kt`（新）、`ui/SqlPreviewPanelResultView.kt`、`converter/jpa/HqlConverter.kt`（追加 `degradations`）、`MpSqlVisualizerBundle.properties` | W2 |
| W4 | 设置三态（若 D-1=B） | `config/PluginSettings.kt`、`config/PluginSettingsPanel.kt`、`config/PluginSettingsPanelSections.kt`、`config/PluginSettingsFolderConfigurable.kt`、bundle | W2 |
| W5 | demo 夹具 + 文档 | demo 仓 `mybatis-plus-demo/src/main/resources/pages/user-maintain.xml`（增一个 HQL 页，保留原两页做回归）、`docs/架构设计/`（pages 链章节）、`docs/索引/开发计划.md` | W2 |
| W6（可选） | 隐式关联路径自动 JOIN | `converter/jpa/*` | D-5=B 才开 |

写集无交集，W1/W5 的夹具与 W3 UI 可并行；W2 必须等 W1 落地（检测器 API 定稿）。

---

## 七、测试与反向验证

### 7.1 定向用例

**纯 JUnit（无工程）** —— `src/test/kotlin/.../xml/PagesSourceLanguageTest.kt`（新）：

| 用例 | 断言 |
|---|---|
| 用户样本形态：`entityClass=...BaseLocation` + `FROM BaseLocation` | HQL，证据 `root-is-entity-class` |
| demo 反例：`entityClass=...Order` + `FROM t_order` | SQL，证据 `root-is-table` |
| `type="hql"` 优先于一切 | HQL，证据 `explicit-type` |
| 无 `entityClass` + 根名不可解析 | SQL（默认） |
| 无 `entityClass` + 根名可解析为实体（注入 probe） | HQL，证据 `root-resolves-entity` |
| 根名可解析但表名==根名（歧义） | SQL |
| `JOIN FETCH` / `?1` 记号只加权不单独定罪 | 构造「记号命中但根名是表名」→ SQL |
| `#{}` 预处理：合法名 / 带点 / 空 / 字面量内 / 多个重复名 | 一一对应上表 |
| `#{}` 与 `:X` 同名合并 | 参数名去重、值同源 |

**平台测试（`BasePlatformTestCase`，需索引）** —— `src/test/kotlin/.../ui/PagesXmlHqlPreviewTest.kt`（新）：

1. 夹具：实体 `Order`（`@Entity`+`@Table(name="t_order")`、`@ManyToOne` 副表、`@Embedded UpdateInfo{beDelete}`）+ maintainPage 页（HQL）；
2. 断言：产物 SQL 含 `t_order`、`be_delete`、副表表名；`tableName == t_order`；`placeholderParamNames` 含 `SESSION_BASE_ORGAN`；`pagesColumns` 仍在；
3. 实体不可解析 → 原文 + warning（不空白、不抛）；
4. dumb 模拟（`DumbServiceImpl.setDumbMode`）→ 原文 + warning；
5. SQL 页回归：demo 原两页产物与改造前逐字一致（金样）。

**改写既有契约**：`ui/PagesXmlPreviewServiceTest.kt:172-191` 由「HQL 原样展示」改为「HQL 走转换或（无 project）原文 + 需索引 warning」，并在注释里写明契约变更依据。

### 7.2 反向验证（本仓纪律，必须贴两次读数）

1. 把检测器第 2 步临时改成直返 `SQL` → 用户样本用例、平台转换用例**必红**；
2. 去掉 `#{}` 预处理 → 参数用例**必红**（且 SQL 里残留 `#{`）；
3. 去掉「转换失败回退原文」→ 降级用例**必红**（出现空面板/异常）；
4. 各贴「改前红 / 改后绿」两次读数，不接受只跑绿。

### 7.3 分层与命令

- 定向（实施者自跑）：

```
./gradlew test --tests "cn.song.mybatisplus.visualizer.xml.PagesSourceLanguageTest" \
  --tests "cn.song.mybatisplus.visualizer.ui.PagesXmlHqlPreviewTest" \
  --tests "cn.song.mybatisplus.visualizer.ui.PagesXmlPreviewServiceTest" \
  --tests "cn.song.mybatisplus.visualizer.xml.PagesXmlParserTest" \
  --tests "cn.song.mybatisplus.visualizer.converter.jpa.HqlAliasColumnMappingTest" \
  --tests "cn.song.mybatisplus.visualizer.converter.jpa.HqlBarePropertyMappingTest"
```

- 全量：所有子任务完成后由只读收口代理跑一次（按登记表独立沙盒），主控并行写文档；
- 构建校验：本券**不碰** `plugin.xml` 的 EP/since-build、不加依赖、不动 `build.gradle.kts`，按「大改动」判据**不触发** `verifyGate/verifyPlugin/buildPlugin`；若 W4 改变了设置面板 EP（不预期），再补跑。

---

## 八、待验证前提（先证伪再动手）

| 编号 | 前提 | 证伪方法 | 被推翻的后果 |
|---|---|---|---|
| P-1 | `entityClass` 指向 HQL 根实体 | 已有一个反例（demo `orderMaintain`）⇒ 只能做**相关性**判据，不可当充分条件 | 本方案已按「相关性」设计，无影响；但若真实工程大量出现「entityClass 与根实体不一致」，检测命中率下降 → 需 E1/E4 补齐 |
| P-2 | 目标工程实体是 `@Entity` 注解或 hbm 映射 | 在用户工程 `Ctrl+Shift+F` 查 `@Entity` / `*.hbm.xml`；`EntityClassResolver` 的「映射实体」判据是否只认 JPA 注解需读源码确认 | 若既非注解也非 hbm（纯 XML 私有映射），E4 失效 → 只能靠 E1/E2 字符串判据，转换仍可能因无 TableInfo 产出 `unknown_table` → 需 D-1「仅 HQL」+ 明确 warning |
| P-3 | `#{}` 在交给 Hibernate 前被框架替换 | 查框架日志（替换后 SQL）/反编译框架 jar；拿不到就按「不替换」也不破坏（列成参数） | 若 `#{}` 原样进 Hibernate（说明是 Hibernate 扩展语法），预处理仍是正确方向（转成 `:name`） |
| P-4 | `nodeAddress` 是 `@Embedded` 还是 `@ManyToOne` | 看用户实体源码 | 若为 `@ManyToOne`，样本页转换后仍不可执行（4.6 只提示）→ 需评估是否升级 D-5 |
| P-5 | 预览面板 warning 能容纳多行/长文本 | 真机或 `SqlPreviewPanelResultView` 布局测试 | 只能单行 → 降级清单要截断 + 「详见日志」，且不宜塞 8 条 |
| P-6 | 设置页三态下拉的守卫约束 | 读 `SettingsPanelSearchableGuardTest` / `PluginSettingsPanelBindingTest` / `SettingsRealityGuardTest` | 成本超预期 → 退回 D-1=A（无开关），用证据串 + 日志兜底 |
| P-7 | 没有别的测试/守卫依赖「pages 不做 HQL 转换」 | `grep -rn "PagesXmlPreviewService\|HQL内容_直接展示" src/test` | 若还有隐藏断言，需一并改并在计划回填 |

---

## 九、风险

| 风险 | 缓解 |
|---|---|
| **误判**把原生 SQL 当 HQL（产物比现状更糟） | 默认 SQL；判据只认「根名与 entityClass/表名的确定关系」或「根名解析为实体且≠表名」；三态开关；证据横幅；反向验证必红 |
| `HqlConverter` 覆盖面不足（隐式关联、关联 JOIN 副表、CTE/UNION）被当成插件 bug | 4.4 降级清单显式列出；文档写明「已知边界」；D-5 后续券 |
| 大工程 `EntityAliasSweep` 首扫约 1s（注释实测值），预览卡顿 | 复用既有缓存与 `SCAN_LIMIT`；预览本就在池线程 ReadAction（`ui/SqlPreviewService.kt:2051-2057`）；打点记录检测+转换耗时 |
| 新增 `PreviewResult` 字段破坏位置实参构造点 | 只追加在末尾并带默认值；改完全量编译；`grep "PreviewResult(" src/test` 逐一核对 |
| 设置项触发一堆守卫（搜索索引/绑定/复选框计数） | 按 P-6 先读守卫再动手；D-1 可降级为 A |
| pages 预览不落历史、不传 `sourceLocation`（既有缺口） | 本券不改；若要做「执行后回看」，另开券（避免范围蔓延） |
| k2j 端口分支需同步 | 合入 master 后按 `k2j-port-sync` 作业法在下一窗翻译（新文件 2 个 + 改动 8 个，属常规量） |

---

## 十、变更记录

| 日期 | 内容 |
|---|---|
| 2026-11-05 | 立项。用户反馈 `<datasource>` 内 HQL 未被识别；本册给出 entityClass 相关性判据、复用 `previewHbmNamedQuery` 的接入方案、`#{}` 预处理、证据/降级可见化与三态开关，并列出 6 个决策点、7 条待验证前提。未动生产代码。 |
| 2026-11-05 | 实施并合入 master（`98554a4f` + 合并 `08fab428`）：检测器/转换接入/`#{}`/原文页签/三态开关/demo 夹具；定向 11 类 118/0/0/0。 |
| 2026-11-05 | 用户追问两处泛化并已实施：① 页面标签名不固定（`maintainPage` / `listPage` / `queryPage` …）→ 口径改为「`<pages>` 直接子级 + 带 `<datasource>`」（无 datasource 的旧 `<maintainPage/>` 保留兼容）；② 无 `type`/无 `entityClass` 的通用载体（`<dataValue>`）→ 通用 XML 内嵌链接入同一检测器，靠「根标识符可解析为实体且表名不同」自动判 HQL。 |
