# T-MapperPreviewDedupAndAnnotationShortcut —— Mapper 预览图标重复与注解快捷键定位

- 任务编号：T-MapperPreviewDedupAndAnnotationShortcut
- 立项时间：2026-11-26 14:10
- 触发：用户真机反馈（2026-11-26 两张截图）：「sql 预览图标出现重复了 应是 Wrapper 导致的」「注解 sql 我用快捷键预览 没有预览我选中的 sql 而是弹了列表 可能是没定位到」
- 关联：T-MapperMethodSqlPreview（方法行预览图标 + `Alt+Shift+Q` 回落）、T-MapperNavShortcut、T-DaoSql（Wrapper 锚点体系）、T-M25-03（注解预览）

---

## 一、现象

### 1.1 预览图标重复（demo `OrderMapper`）

截图 1（`OrderMapper`，方法参数带 `Wrapper`）：

```java
public interface OrderMapper extends BaseMapper<Order> {          // 行 17：左侧两枚图标
    List<Order> selectByCustomSqlSegment(@Param("ew") Wrapper<Order> ew);        // 行 22
    List<Map<String,Object>> selectOrderWithUserByEw(@Param("ew") Wrapper<Order> ew);  // 行 27
    List<Order> selectOrdersByCondition(@Param("orderNo") String orderNo, ...);  // 行 32
    List<Map<String,Object>> statOrderAmountByUser(...);                          // 行 39
}
```

每行左侧两枚图标，其中至少一枚是 SQL 预览图标（`/icons/sqlGutter.svg`）。用户判断「Wrapper 导致的」。

### 1.2 注解 SQL 快捷键弹列表

截图 2（`UserMapper`，光标停在 `createDemoTable()` 方法/其 JavaDoc 区域，按 `Alt+Shift+Q`）：弹出「选择要预览的 SQL」列表，列出整个文件的全部注解 SQL（`createDemoTable`、`dropDemoTable`、`selectByAnnotationId`、`selectInvalidForRecording`…），而不是直接预览光标所在方法的 `@Update("CREATE TABLE IF NOT EXISTS …")`。

---

## 二、根因分析（代码证据）

### 2.1 Bug 1：Wrapper 锚点把「Mapper 接口方法参数」也当链起点

`psi/WrapperAnchorDetector.kt:87-99`（场景 B 分步变量）：

```kotlin
if (element is PsiIdentifier && element.parent is PsiVariable) {
    val variable = element.parent as PsiVariable
    if (variable is PsiParameter && (variable.parent?.parent) is PsiLambdaExpression) return null
    if (WrapperLocator.detectWrapperTypeDeep(variable.type) != null) return element   // ← 命中
    ...
}
```

判据只看「变量类型是不是 Wrapper」，唯一排除的是 lambda 参数。`@Param("ew") Wrapper<Order> ew` 是 `PsiParameter`、类型 `Wrapper<Order>` → **参数名 `ew` 上出 Wrapper 预览图标**（`SqlGutterLineMarkerProvider`，`/icons/sqlGutter.svg`）。

与 T-MapperMethodSqlPreview 的方法行预览图标（同为 `/icons/sqlGutter.svg`）落在**同一行** → 用户看到两枚同款预览图标（方法名一枚 + 参数名一枚），加跳转箭头是该行第 3 枚。Wrapper 参数在 Mapper 接口里只是「SQL 片段占位符」（`${ew.customSqlSegment}`），不是可预览的链起点——预览这条方法的 SQL 应走方法行那枚。

**待取证**：截图中第 17 行（类声明行）也有两枚图标，`BaseMapper<Order>` 不含 Wrapper 类型，第二枚来源未定——实施第一步必须枚举该行全部 `LineMarkerInfo` 的 icon/tooltip/element，确认是否还有其它通道（类跳转 + ？）。

### 2.2 Bug 2：注解快捷键只在光标「落在注解 PSI 内」时直预览

`actions/ConvertToSqlAction.kt` 分支 B：

```kotlin
val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java, false)
if (annotation != null && AnnotationSqlPreviewService.isSqlAnnotation(annotation)) {
    … 直接预览 …
    return
}
```

光标停在 `int createDemoTable();` 这一行（注解在上一行）时，`element` 是方法名/返回类型，**不落在 `PsiAnnotation` 内** → 分支不命中。随后 fallback 链（2026-11-26 新增的 `tryMapperXmlMethodFallback` 因无 XML 语句返回 false，iBatis/JDBC/HQL 均不命中）走到终点 `showAnnotationCandidates`（`ConvertToSqlAction.kt:506`）——它的语义是「文件级注解候选列表」，于是弹出整个文件的注解 SQL 列表。

用户预期：光标在某个方法上，快捷键应预览**该方法自己的** SQL 注解，而不是让他从全文件列表里挑。

---

## 三、目标与非目标

**目标**

1. Mapper 接口方法的 `Wrapper` 参数不再出 Wrapper 预览图标（参数声明不是链起点），方法行只保留「预览 + 跳转」两枚。
2. `Alt+Shift+Q` 在带 SQL 注解的方法声明处（方法名、参数、返回类型、方法 JavaDoc 区）直接预览该方法的注解 SQL，不再弹全文件列表。
3. 两处修复都不改既有已验收行为：实现类/普通方法的 Wrapper 参数照旧出图标；光标落在注解 PSI 内时的直预览不变；文件级注解列表保留为「光标不在任何方法上」的最后兜底。

**非目标**

- 不改 Wrapper 判定对局部变量/字段的既有覆盖（场景 B 其它分支）。
- 不改注解预览的渲染（`AnnotationSqlPreviewService`）。
- 不改 `showAnnotationCandidates` 的列表交互本身。
- 不动 T-MapperMethodSqlPreview 的双图标布局与让位语义。

---

## 四、实施方案

### S0 取证（先做，决定后续判据）

写临时探针测试（`MapperPreviewDedupReproTest`，落地时保留为回归用例）：

- 夹具照抄 demo：`OrderMapper`（`extends BaseMapper<Order>`、四个方法，其中两个带 `@Param("ew") Wrapper<Order> ew`）+ 对应 XML 语句。
- 收集该文件全部 `LineMarkerInfo`，按行打印 `element.text / icon / lineMarkerTooltip`，断言：
  - `selectByCustomSqlSegment` 行：预览（新 tooltip）+ 跳转（`gutter.mapperMethod.tooltip`）**各恰 1 枚**，Wrapper 预览 0 枚；
  - 类声明行：只 1 枚类跳转（若取证发现第 2 枚来自其它 provider，按实际来源补判据/修法，并回填本节）。

### S1 Wrapper 锚点排除「无方法体的参数声明」

`WrapperAnchorDetector.anchorElement` 场景 B 的 `PsiParameter` 分支增加一条 O(1) 判据：

```kotlin
if (variable is PsiParameter) {
    // lambda 参数（原有）
    if ((variable.parent?.parent) is PsiLambdaExpression) return null
    // T-MapperPreviewDedup：抽象/接口方法（无 body）的参数不是链起点——
    // Mapper 接口的 Wrapper 参数只是 ${ew.customSqlSegment} 占位符，预览归方法行图标。
    val owner = variable.parent?.parent as? PsiMethod
    if (owner != null && owner.body == null) return null
}
```

- 保留：有 body 的方法（实现类/service/default 方法）参数照旧出图标——链可能在方法体内使用该参数。
- 纯语法判定，零 resolve、零索引。

### S2 注解快捷键按方法定位

`ConvertToSqlAction` 新增 `tryMapperAnnotationMethodFallback`，插在 `tryMapperXmlMethodFallback` 之后：

```kotlin
private fun tryMapperAnnotationMethodFallback(project: Project, element: PsiElement, sourceLocation: String?): Boolean {
    val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false) ?: return false
    val annotation = method.modifierList.annotations.firstOrNull {
        AnnotationSqlPreviewService.isSqlAnnotation(it)
    } ?: return false
    previewSqlAnnotation(project, annotation, sourceLocation)
    return true
}
```

- 抽 `previewSqlAnnotation(project, annotation, sourceLocation)`，现有「光标在注解内」分支改为调用它（渲染链一份）。
- `PsiTreeUtil.getParentOfType(element, PsiMethod)` 天然覆盖：方法名、参数、返回类型、方法 JavaDoc（`PsiDocComment` 是方法子元素）。
- 光标在方法外（类体空白/import/字段）→ false → 仍走文件级列表兜底。
- 顺序：XML 绑定 → 注解绑定 → 其它家族；一个方法两种都有的情况 MyBatis 自身也不允许，先 XML 不改变现状。

### S3 测试

新增/更新：

1. `MapperPreviewDedupReproTest`（S0 的探针转正）：接口 Wrapper 参数零 Wrapper 图标、方法行恰两枚；实现类 Wrapper 参数仍出图标（对照）。
2. `WrapperAnchorDetectorTest`：补「接口/抽象方法参数零锚点」「实现类方法参数仍锚定」「default 方法参数仍锚定」三条。
3. `ConvertToSqlActionTest`：
   - 光标在注解方法声明行 → 直接命中该方法注解（不弹列表）；
   - 光标在方法 JavaDoc 区 → 同样命中；
   - 光标在类体空白 → 仍返回文件级候选（列表兜底语义不变）；
   - 链序守卫：注解档在 XML 档之后、文件级列表之前。
4. `GutterProvidersNegativeTest` 若登记了 Wrapper provider 的负例集合，补「Mapper 接口 Wrapper 参数」一条。

### S4 文档与 CHANGELOG

- `docs/架构设计/预览触发面清单-说明文档.md`：§二 场景 B 行补「参数形态仅限有方法体的方法（Mapper 接口参数不锚）」；#18 行补「快捷键：注解方法按方法定位」。
- `CHANGELOG.md [Unreleased]`：`Fixed` 两条（重复图标、注解快捷键定位）。

---

## 五、待验证前提

| # | 前提 | 验证法 | 不成立时 |
|---|---|---|---|
| P-1 | 截图类声明行的第二枚图标来自 Wrapper 参数以外（`BaseMapper<Order>` 无 Wrapper 类型） | S0 探针枚举 icon/tooltip/element | 按实际来源补修法，并在本计划回填 |
| P-2 | `PsiParameter.parent?.parent` 恒为 `PsiMethod`（含 `@Param` 注解时链不变） | 单测断言（带注解/不带注解两种形态） | 改用 `PsiTreeUtil.getParentOfType(variable, PsiMethod)` |
| P-3 | 光标在方法 JavaDoc 区时 `getParentOfType(element, PsiMethod)` 命中 | 单测（光标放 `/**` 内） | 放宽到「方法前一行」的文本锚点 |
| P-4 | Wrapper provider 之外没有其它 provider 在接口参数上出同类图标 | S0 全量枚举 | 逐个收紧 |

---

## 六、验收

- 定向：`MapperPreviewDedupReproTest`、`WrapperAnchorDetectorTest`、`ConvertToSqlActionTest`、`MapperNavigationLineMarkerProvidersTest`、`MapperNavigationGutterGateTest`、`BundleParityGuardTest` 全绿。
- 反向两轮：
  1. 摘掉 S1 的「无 body 排除」→ 重复用例恰红；还原复绿。
  2. 摘掉 S2 的链上调用 → 注解快捷键用例恰红（含链序守卫）；还原复绿。
- 真机（用户）：`OrderMapper` 方法行只剩「预览 + 跳转」两枚；`UserMapper.createDemoTable()` 光标在方法行按 `Alt+Shift+Q` 直接出 `CREATE TABLE …` 预览。

## 七、风险

| 风险 | 处置 |
|---|---|
| 排除接口参数后，某些用户依赖「参数上的 Wrapper 图标点开看链」 | 该形态在接口里没有链（无 body），点开只会是空；预览归方法行更准确。CHANGELOG 记明 |
| `owner.body == null` 对抽象类抽象方法同样排除 | 与接口语义一致（无 body 无链），负例测试钉住 |
| 注解档抢在 XML 档之前导致双绑定方法预览错源 | 顺序固定 XML → 注解，与 MyBatis 绑定优先级一致 |
| daemon 侧 `WrapperAnchorDetector` 被多处复用（预览/补全） | 本次只改锚点判定，不动 `WrapperLocator`；定向测试覆盖调用方 |

---

## 八、实施记录（2026-11-26，master）

### 8.1 落地

- `WrapperAnchorDetector.anchorElement` 场景 B 的 `PsiParameter` 分支增加「宿主方法无 body → 不锚」：抽象/接口方法参数不是链起点，实现类与 `default` 方法参数照旧。
- `ConvertToSqlAction`：抽 `previewSqlAnnotation`（注解内光标与方法声明处共用一条渲染链）+ 新增 `tryMapperAnnotationMethodFallback`，位于 `tryMapperXmlMethodFallback` 之后、iBatis 档之前；判定抽成 `internal fun sqlAnnotationForMethod` 供测试直调（无 UI 副作用）。

### 8.2 取证与测试夹具修正（本轮两个坑）

- **首轮反向只红 1 条**：接口用例用 `Wrapper<Object>`，但测试项目没有 MP 依赖——`Wrapper` 基类走 `looksLikeWrapperName`（`*Wrapper` 前缀）→ `resolve + superChain` 分类，resolve 落空返回 null，用例变成空绿。补 `myFixture.addFileToProject("com/baomidou/mybatisplus/core/conditions/Wrapper.java", …)` 让该 FQN 可解析后，摘掉实现恰 2 红（接口 + 抽象）。
- `PsiDocComment` 在 243 位于 `com.intellij.psi.javadoc`（不在 `com.intellij.psi`），测试改用 `PsiComment` + `/**` 前缀定位方法 JavaDoc。

### 8.3 定向读数

5 类 **109/0**：`WrapperAnchorDetectorTest` 39（新增 3 条）、`ConvertToSqlActionTest` 19（新增 4 条）、`MapperNavigationLineMarkerProvidersTest` 31、`MapperNavigationGutterGateTest` 15、`BundleParityGuardTest` 5。

### 8.4 反向验证（两轮，逐面恰红）

- 轮 1（摘掉「无 body 排除」）：`WrapperAnchorDetectorTest` **39/2 红**——接口参数、抽象方法参数两条。
- 轮 2（摘掉注解链调用）：`ConvertToSqlActionTest` **19/1 红**——链序守卫。
- 还原后复跑 **109/0**。

### 8.5 观察（需真机确认的部分）

- 截图类声明行（`public interface OrderMapper extends BaseMapper<Order>`）的第二枚图标：我方在该行只出 1 枚类跳转（`MpvsIcons.MapperMethod`），S1 修的是方法参数行。若真机类名行仍显示两枚，第二枚来自同类导航插件（MyBatisX/MPJ 的类级箭头），hover tooltip 可区分；如需连这一枚也处理，属于让位/同类图标叠加话题，另立项。
- 真机夹具 `OrderMapper` 的 `Wrapper<Order>` 参数修复前后对照：装包后方法行只应剩「预览 + 跳转」两枚，`ew` 参数行不再有预览图标。

---

## 九、补：方法行图标左右序（T-MapperPreviewOrder，2026-11-26 同日）

用户追加反馈：「mapper 的预览图标首次渲染时老是在右边，过一会出现在左边了，希望保持左边，对齐 xml」。

### 9.1 机制

平台慢趟按 **provider 顺序**收集（`LineMarkersPass.queryProviders` 外层遍历 providers，每个 provider 的 `collectSlowLineMarkers` 结果按 add 顺序消费），同一行多枚图标的左右序由此决定。此前预览与跳转都在 `MapperJavaToXmlLineMarkerProvider` 一个 provider 内，首次 daemon pass 与后续重算的收集/合并差异让顺序翻转；XML 侧因为「预览 provider 在前、跳转 provider 在后」两个类而稳定——用户说的「对齐 xml」正是这个结构。

### 9.2 修法

- 预览图标拆成独立 provider `MapperMethodPreviewLineMarkerProvider`，`plugin.xml` 注册在 `MapperJavaToXmlLineMarkerProvider` **之前**；跳转 provider 只产箭头（让位门语义不变）。
- 预览侧判定用新增的 `MapperXmlLocator.existsOrUnknown`（索引候选 + PSI 子树匹配，**不建 document**）——跳转侧仍用 `locateOrUnknown`（要行号）；两边各付一次查询，但预览侧不碰 document，成本低于复用。
- 顺序守卫 `MapperPreviewProviderOrderGuardTest`：① 平台 `LineMarkersPass.getMarkerProviders(Java)` 里预览在跳转之前；② `plugin.xml` 注册段顺序。

### 9.3 读数

- 定向 9 类 **157/0**（含新守卫 2 条；4 个既有测试类按「两 provider 各收集一次」同步）。
- 反向：把 `plugin.xml` 两个注册元素对调 → 守卫 **2/2 红**；还原后 2/0。
- 一处测试坑：守卫最初用裸类名 `indexOf` 定位注册点，结果先命中**注释里的类名说明**（注释也在注册元素之前），假红一次；改用 `implementationClass="<全名>"` 片段定位。
