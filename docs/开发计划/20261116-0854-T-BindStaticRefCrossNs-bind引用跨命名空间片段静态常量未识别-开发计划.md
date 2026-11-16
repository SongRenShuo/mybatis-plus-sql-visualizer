# T-BindStaticRefCrossNs：bind 引用「跨命名空间 include 片段」里的静态常量识别不出来

- 立项：2026-11-16 08:54
- 任务源：用户反馈（2026-11-16，真机 IDEA 2026.3）——「新问题目前发现 bind 引用的静态字段没有正确识别」
- 复现工程：`F:\code\Java\Gitee\other\cyxny\cyzc-platform`
- 语料：`cyzc-modules/src/main/resources/mapper/biz/BikeOrderRecordMapper.xml`（语句 `selectOrderAggTwoPeriod` / `selectOrderAgg` 等）与 `cyzc-modules/src/main/resources/mapper/common/StationAuthScope.xml`
- 复现截图：顶部红条「静态常量 `com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_STATION_CATEGORY_BIKE` 求值为空（常量类/字段不可解析或未注入，可在预览中填写覆盖值）」，同时参数表 `#scopeCategoryBike` 行为空

## 一、现象与关键差分

| # | 症状 | 证据 |
|---|---|---|
| S1 | 预览顶部红条报 `@…DicConstants@DIC_VALUE_STATION_CATEGORY_BIKE` 求值为空 | 用户截图 |
| S2 | 参数表 `#scopeCategoryBike` 空、未回填 | 用户截图 |
| S3 | **同一条语句**的 `#statusEnd` 已自动回填 `3`（bind `@…DicConstants@DIC_VALUE_BIKE_ORDER_END`），SQL 里渲染成 `dic_status = '3'` | 用户截图 |

S3 是本任务的判据：同一条语句、同一个常量类、同一次预览，一个 bind 成功一个失败 ⇒ 问题不在「静态常量整体不支持」「跨 Maven 模块解析不到」「常量类型不支持」，而在**某一类承载该 bind 的文本压根没进入扫描域**。

用户给出的语料里两条 bind 的真正差别只有一处：

```xml
<!-- BikeOrderRecordMapper.xml，本地 bind（同文件） -->
<bind name="statusEnd" value="@com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE_ORDER_END"/>

<!-- StationAuthScope.xml，跨命名空间 include 片段内的 bind（另一个文件） -->
<sql id="BikeStationScope">
    <bind name="scopeCategoryBike"
          value="@com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_STATION_CATEGORY_BIKE"/>
    ...
</sql>
```

语句通过 `<include refid="com.cyzc.platform.modules.mapper.common.StationAuthScopeSql.BikeStationScope"/>` 把这个片段拉进来（refid 含 `.` ⇒ 跨命名空间 include，`T-IC-02` 能力）。

## 二、静态归因（代码面事实，逐行核实，非推断）

### 2.1 渲染链：片段会被展开，bind 会被执行

| 步 | 代码 | 事实 |
|---|---|---|
| 1 | `XmlPreviewService.kt:99` `XmlCrossNsFragmentResolver.resolve(fileText, project)` | 按当前文件里含 `.` 的 refid，全项目扫 mapper XML 找到目标文件，取出片段**内部 XML 文本**（`CrossNamespaceFragments.fragments: Map<refid, String>`） |
| 2 | `XmlMapperParser().parse(fileText, crossNamespace)` → `XmlIncludeExpander.kt:32/45` | include 在加载期就地展开，片段的子节点（含 `<bind>`）被拷进语句树 ⇒ 片段的 bind 与本地 bind 在模型里**没有区别** |
| 3 | `Ognl.kt:487` `is OgnlExpr.StaticFieldRef -> staticFieldValues["${className}@${fieldName}"]` | 求值只认注入表里的键；键缺席 ⇒ 求值得到 `null`，不报错 |
| 4 | `IfSqlNode.kt:76`（`VarDeclSqlNode`） | bind 结果为 `null` 且表达式含静态引用 ⇒ `context.errors += "静态常量 … 求值为空（…）"` ⇒ 就是用户看到的红条 |
| 5 | `XmlSqlRenderer.kt:81` → `SqlPreviewPanelResultView.kt:235` | 渲染完成后把 `bindValues` 回填到参数行 ⇒ 解析成功的 bind 会像 `#statusEnd` 那样显示 `3`，失败的 `#scopeCategoryBike` 留空 |

### 2.2 断点：注入表的扫描域只有「当前文件文本」

`XmlPreviewService.kt:105`：

```kotlin
val staticValues = resolveStaticFields(fileText, project, warnings)   // 只有当前 mapper 文件的文本
```

`XmlPreviewService.kt:291-306` 的实现：对**传入的那一段文本**跑 `STATIC_REF_REGEX`（`:420`）收 `@类@字段`，再逐个走 `resolveStaticFieldValue`（`:361`：`findClass` → `findFieldByName` → `computeConstantExpression`）。

于是：

- 本地 `<sql>` 片段与本地 `<bind>`：文本都在 `fileText` 里 ⇒ 扫描到 ⇒ 成功（`statusEnd` 就是这条）。
- **跨命名空间 include 目标文件里的 `<bind>`**：文本在**另一个文件**里，只在第 2 步被拼进语句树；注入表的扫描域永远看不到它 ⇒ 键缺席 ⇒ 第 4 步报警。

这是一处纯粹的作用域错配：**渲染域 = 当前文件 + 跨命名空间片段；注入域 = 当前文件**。`T-IC-02` 引入跨命名空间 include 时补了「展开」，没同步补「静态常量扫描」。

### 2.3 已排除的假设（同轮核实，防止按错误前提动手）

| 假设 | 判定 | 反证 |
|---|---|---|
| 常量类型不支持（`Integer` 行、`int` 不行，或反之） | 排除 | 成功的 `DIC_VALUE_BIKE_ORDER_END` 是 `public static final Integer = 3`，失败的 `DIC_VALUE_STATION_CATEGORY_BIKE` 是 `public static final int = 1`；两者都是编译期常量、都对字面量求值，`computeConstantExpression` 一视同仁 |
| 跨 Maven 模块解析不到（常量在 `cyzc-common`，mapper 在 `cyzc-modules`） | 排除 | 成功的那个常量同样在 `cyzc-common`，且 `GlobalSearchScope.allScope(project)` 覆盖全项目 |
| 常量类/字段名写错 | 排除 | 类与字段在 `cyzc-common/.../DicConstants.java` 实存（`:807` / `:1189`） |
| `findFieldByName(fieldName, false)` 不找父类导致失败 | 不是本缺陷 | 两个字段都直接声明在 `DicConstants` 上；该参数是**另一个**潜在缺口（见 §四 S2） |
| 静态常量能力整体失效 | 排除 | `#statusEnd` 回填 `3` 即反证 |

## 三、待验证前提（实施第一步就证伪）

- **P-1（红）**：新增用例「跨命名空间片段内 `<bind value="@…@CONST"/>`」在**修复前**必须红——`result.warning` 含「求值为空」，`xmlModel.renderSql().bindValues` 不含该 bind 名。
- **P-2（绿）**：同一夹具的本地 bind 版本（现有 `AnnotationPreviewServiceTest` 两个用例）保持绿，说明修复没有把失败面扩大到本地 bind。
- **P-3（不回归）**：修复后 `preview()` 新增的 PSI 解析次数 = 片段内**去重后**的静态引用数；`[open-perf]` 的 `total` 在同一语料上无可见变化（±10% 内，单纯文本 + 少量 `findClass`）。
- **P-4（对齐真机）**：用户语料（`cyzc-platform`）预览 `selectOrderAggTwoPeriod`，红条消失且 `#scopeCategoryBike` 回填 `1`。

## 四、修法

### S1（主修，必做）：把扫描域对齐到渲染域

`preview()` 里已经有 `crossNamespace`（`:99`），只需把它带进静态常量扫描：

```kotlin
// 修前
val staticValues = resolveStaticFields(fileText, project, warnings)
// 修后：当前文件 + 所有已解析成功的跨命名空间片段文本
val staticScanTexts = buildList {
    add(fileText)
    addAll(crossNamespace.fragments.values)
}
val staticValues = resolveStaticFields(staticScanTexts, project, warnings)
```

`resolveStaticFields` 改为多文本入口，保留单文本重载（注解链 `AnnotationSqlPreviewService.kt:284` 与既有测试直接调用单文本形态，不动它们的调用点）：

```kotlin
internal fun resolveStaticFields(
    texts: Collection<String>, project: Project?, warnings: MutableList<String>,
): Map<String, Any?> { /* 每段跑 STATIC_REF_REGEX，ref 集合跨段合并去重 */ }

internal fun resolveStaticFields(text: String, project: Project?, warnings: MutableList<String>) =
    resolveStaticFields(listOf(text), project, warnings)
```

要点：

- 扫描域严格取 `crossNamespace.fragments.values`——`XmlCrossNsFragmentResolver` 自带「本文件无含点 refid 就直接返回」的短路与单槽缓存，无跨命名空间引用的文件**零新增开销**。
- `failures`（片段没找到）没有文本可扫，不参与；该情形已由 include 展开器给出自己的警示，不重复报。
- 跨命名空间片段里的 refs 与当前文件里的 refs 走同一 `toSet()` 去重，重复引用只解析一次。
- **不按语句收窄范围**：现行行为就是「全文件 refs 都解析」，本轮不改口径（收窄属 D-2）。

### S2（一并做，一行 + 一例）：继承来的常量要能认

`XmlPreviewService.kt:364` 是 `psiClass.findFieldByName(fieldName, false)`——只找本类声明。MyBatis/OGNL 运行时读的是 `Class.getField`，**含**继承/public 接口常量。改成 `true`（`checkBases`），让 `@com.x.Sub@CONST`（常量声明在父类或接口）与真机一致。风险极低：`true` 是 `false` 的超集，只影响当前解析失败的形态。

### S3（本轮不做，登记为已知边界）

| 边界 | 现状 | 为何不做 |
|---|---|---|
| `static final` 无初始化式、在 `static {}` 块里赋值 | `field.initializer == null` ⇒ 解析失败 ⇒ 红条 + 可手填 | 需扫静态初始化块并求值赋值右值，收益面小；红条已给出「填写覆盖值」的出路 |
| 非常量初始化式（`Integer.valueOf(3)`、`new BigDecimal("1")`、枚举常量、数组） | `computeConstantExpression` 返回 null | 属常量求值器能力边界，扩面会引入「部分求值」的语义分叉，先按手填兜底 |

### 决策点（实施前拍板，默认按括号内）

- **D-1（默认 A）**：返回值继续是 `String`（`resolveStaticFieldValue` 现签名），数值常量因此被引号包裹（`dic_status = '3'`）。保留现状；改成 `Any?` 会改变 OGNL 比较语义（`@A@INT == '3'`）并可能翻转既有预览结果，另立项评估。
- **D-2（默认 A）**：不改「全文件收窄到当前语句」的扫描口径。收窄需在解析后的语句树上收集表达式节点（`VarDeclSqlNode.expression` / `IfSqlNode.test` / `TextSqlNode.content` / `ForeachSqlNode.collectionExpression`），改动面大，且现行口径只是多解析几个 ref。
- **D-3（默认 A）**：红条文案不改。修复后该文案只在真正解析不到时出现，措辞已经指向「可在预览中填写覆盖值」。

## 五、影响面与横向审计

| 入口 | 是否走 `XmlPreviewService.preview` | 修后 |
|---|---|---|
| 预览面板（gutter / Ctrl+Enter / 动作） | 是（`XmlSqlGutterLineMarkerProvider.kt:95`、`ConvertToSqlAction.kt:688`） | 受益 |
| 预览装配预热 | 是（`PreviewAssemblyWarm.kt:144`） | 受益 |
| MCP `sqlv_render_sql`（文件路径形态） | 是（`McpSourceLocator.kt:193`） | 受益 |
| MCP `sqlv_render_sql`（`xmlSnippet` 片段形态） | 是，但无 `project`/无文件上下文（`:141`） | 无跨命名空间可展开，行为不变 |
| 注解 `<script>` 链 | 否，走 `AnnotationSqlPreviewService.kt:284` 单文本入口 | 重载保留，逐字节不变 |
| Pages XML 链（`PagesXmlPreviewService`） | 否，且本来不解析静态常量（无 bind 支持） | 不变，登记为缺口不属本轮 |
| iBatis2 预览（`IbatisXmlPreviewService`） | 否，iBatis2 无 OGNL bind | 不变 |

## 六、测试与反向验证（定向，只跑这两个类）

1. **新增 T1（红）**：`src/test/kotlin/.../psi/XmlCrossNsFragmentResolverTest.kt`（`BasePlatformTestCase` + `addFileToProject`，已有同款先例）

```kotlin
fun test跨文件片段内bind静态常量_按项目PSI求值() {
    myFixture.addFileToProject(
        "src/main/java/com/demo/Consts.java",
        "package com.demo; public class Consts { public static final int IDLE = 7; }",
    )
    addMapper(
        "src/main/resources/mapper/BaseMapper.xml",
        """<mapper namespace="com.demo.BaseMapper">
          <sql id="scope"><bind name="scopeCategory" value="@com.demo.Consts@IDLE"/>AND cat = #{scopeCategory}</sql>
        </mapper>""",
    )
    val result = preview("""
        <mapper namespace="com.demo.OrderMapper">
          <select id="s1">SELECT * FROM t WHERE 1 = 1
            <include refid="com.demo.BaseMapper.scope"/></select>
        </mapper>
    """.trimIndent())
    assertNull("片段内静态常量解析成功不应有警示：${result.warning}", result.warning)
    assertEquals("7", result.xmlModel!!.renderSql().bindValues["scopeCategory"])
}
```

2. **新增 T2（回归）**：同夹具改成片段内引用**不存在的字段**（`@com.demo.Consts@MISSING`）→ 红条仍在（证明 S1 不是靠「不报错」蒙过）。
3. **新增 T3（S2）**：`class Sub extends Base`，常量声明在 `Base`，`@com.demo.Sub@IDLE` ⇒ 解析成功。
4. **回归面**：`AnnotationPreviewServiceTest`（本地 bind 两例）、`XmlPreviewServiceTest`、`XmlIncludeExpanderTest`、`OgnlEvaluatorTest` 全绿。
5. **反向验证**：从 `staticScanTexts` 摘掉 `crossNamespace.fragments.values`（只留 `fileText`）→ T1 必须红、T2 仍绿；贴两次读数。

命令（跑前过并行登记表；单路）：

```
python C:/Users/USER/.zcode/cli/parallel_tasks.py check
./gradlew test --tests "cn.song.mybatisplus.visualizer.psi.XmlCrossNsFragmentResolverTest" \
             --tests "cn.song.mybatisplus.visualizer.ui.AnnotationPreviewServiceTest" \
             --tests "cn.song.mybatisplus.visualizer.ui.XmlPreviewServiceTest" \
             --tests "cn.song.mybatisplus.visualizer.xml.XmlIncludeExpanderTest" \
             --tests "cn.song.mybatisplus.visualizer.xml.OgnlEvaluatorTest"
```

## 七、验收清单（可照抄）

- **A1（用户语料，真机）**：`cyzc-platform` 打开 `BikeOrderRecordMapper.xml` → 预览 `selectOrderAggTwoPeriod`：顶部红条**不含**「静态常量 …求值为空」；参数表 `#scopeCategoryBike` 回填 `1`；SQL 中 `dic_station_biz_type` 的比较值随回填出现。
- **A2（本地 bind 不回归）**：同一预览 `#statusEnd` 仍回填 `3`。
- **A3（无跨命名空间引用的文件）**：任一无 `ns.id` include 的 mapper 预览，`[open-perf]` 日志 `total` 与修复前同量级，且不出现新的索引扫描（`XmlCrossNsFragmentResolver` 短路生效）。
- **A4（真解析不到仍要给信号）**：把某个片段里的引用改成不存在的字段 → 红条照旧出现（不许静默）。
- **A5（读数）**：定向测试 N/0/0/0，反向验证恰红 1 例，两组读数贴进实施记录。

## 八、风险与回退

- 风险面只有一处：`resolveStaticFields` 的入参从 `String` 变成 `Collection<String>`（保留重载），编译期可查；运行期新增开销 = 片段内去重后的 `findClass` 次数（通常 1~3 次）。
- 回退：把 `staticScanTexts` 还原为 `listOf(fileText)` 即回到今日行为，无状态、无缓存、无持久化。

## 九、实施记录

| 项 | 值 |
|---|---|
| 分支 / 提交 | master `ad1deacb0`（代码 + 测试）；本页与 CHANGELOG 随文档提交落账 |
| 改动文件 | `ui/XmlPreviewService.kt`（S1 多文本扫描域 + S2 `checkBases`）、`psi/XmlCrossNsFragmentResolverTest.kt`（T1/T2）、`ui/AnnotationPreviewServiceTest.kt`（T3） |
| 改前读数（P-1 红） | 3 红：T1 `bindValues[scopeCategory]` 为 null（`ComparisonFailure expected:<7> but was:<null>`）、T2 无任何信号（`warning=null`）、T3 继承常量报「无法解析」 |
| 改后定向读数 | **74/0/0/0**（`XmlCrossNsFragmentResolverTest` 17 / `AnnotationPreviewServiceTest` 15 / `XmlPreviewServiceTest` 7 / `XmlIncludeExpanderTest` 16 / `OgnlEvaluatorTest` 19） |
| 反向验证 S1 | 把 `addAll(crossNamespace.fragments.values)` 改成 `addAll(emptyList())` ⇒ `XmlCrossNsFragmentResolverTest` **17 例中恰 2 红**（T1/T2），其余 15 绿 |
| 反向验证 S2 | 把 `findFieldByName(fieldName, true)` 退回 `false` ⇒ `AnnotationPreviewServiceTest` **15 例中恰 1 红**（T3），其余 14 绿 |
| 还原后复跑 | 74/0/0/0（同一命令重跑，确认提交内容即该绿态） |
| P-3 性能 | 未单独计时：跨命名空间引用缺失时 `fragments` 为空表，扫描与改动前逐字节等价；命中时新增的是片段内**去重后**的 `findClass` 次数（本夹具 1 次） |
| A1 真机验收 | **待用户复验**（`cyzc-platform` 预览 `selectOrderAggTwoPeriod`：红条消失、`#scopeCategoryBike` 回填 `1`） |
| S3 未做 | `static {}` 块赋值、`Integer.valueOf(...)` 类非常量初始化式维持「解析不到 + 手填覆盖值」，已写入 `docs/架构设计/02-核心流程详解.md` §3 已知边界 |
