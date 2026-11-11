# T-MbgExamplePreview：MBG Example 动态 SQL 预览失真——开发计划

- 立项：2026-11-11
- 触发：用户真机反馈（IDEA 2026.3）——预览 MBG 生成的 `selectByExample`，产物是
  `WHERE (AND OR (AND … ORDER BY 1` 这种明显不能用的 SQL，顶部横幅同时压着两条互相矛盾的告警。
- 范围（M1）：`xml/TextSqlNode.kt`、`xml/ForeachSqlNode.kt`、`xml/XmlPreviewModel.kt`、
  `ui/XmlPreviewService.kt`、`ui/SqlPreviewPanelParamPanel.kt`、`ui/SqlPreviewPanelResultView.kt`、
  bundle 双语各若干条、新增用例一组。
- 范围（M2，可独立立项）：`psi/` 侧新增 MBG Example 调用点解析。

## 一、现象

用户给的三张图构成一个完整场景（MyBatis Generator 生成的 `CtCustmanagerRel` 三件套）：

1. 预览面板产物：

    ```
    SELECT DISTINCT 'false' AS QUERYID, CUST_ID, STAFF_LOGIN, … FROM CT_CUSTMANAGER_REL
    WHERE (AND
        OR (AND
    ORDER BY 1
    ```

2. Java 调用：`custmanagerRelExample.createCriteria().andCustIdEqualTo(Long.valueOf(cusId))`
   后调 `selectByExample(custmanagerRelExample)`；
3. mapper XML：`<if test="distinct">` / `<include refid="Example_Where_Clause"/>` /
   `<if test="orderByClause != null">order by ${orderByClause}</if>`；
4. Example 类：`protected List<Criteria> oredCriteria;`，内层 `List<Criterion> criteria`；
5. `Example_Where_Clause`：`<where><foreach collection="oredCriteria" item="criteria" separator="or">`
   → `<if test="criteria.valid">` → `<trim prefix="(" prefixOverrides="and" suffix=")">`
   → `<foreach collection="criteria.criteria" item="criterion">` → `<choose>` 四个 `when`
   （`criterion.noValue` / `singleValue` / `betweenValue` / `listValue`），分支体是
   `and ${criterion.condition}` + 若干 `#{}`。

面板左侧同时显示：8 条动态条件全为「真」，三个 foreach 集合输入框留空、`为 null` 均未勾选。

## 二、复现与实测读数

用 `XmlPreviewModel.from(...).renderSql(state)` 直接渲染上面这套 XML（include 同文件展开），
四组面板状态各自产物如下（空白已压缩，便于对照）：

| 面板状态 | 产物 |
| --- | --- |
| 默认（三处集合各展开 2 次、8 条条件全勾） | `select distinct 'false' as QUERYID, CUST_ID, STAFF_LOGIN from CT_CUSTMANAGER_REL WHERE ( and )or( and ) order by 1` |
| 三处集合各展开 1 次、条件全勾 | `… from CT_CUSTMANAGER_REL WHERE ( ) order by 1` |
| 1 次 + choose 只开 `singleValue` | `… WHERE ( ? ) order by 1` |
| 1 次 + choose 只开 `betweenValue` | `… WHERE ( ? and ? ) order by 1` |
| 1 次 + `oredCriteria` 勾「为 null」 | `… from CT_CUSTMANAGER_REL order by 1`，错误行只有 `The expression 'oredCriteria' evaluated to a null value.` |

模型侧读数：

```
conditions      = [distinct, _parameter != null, criteria.valid, criterion.noValue,
                   criterion.singleValue, criterion.betweenValue, criterion.listValue, orderByClause != null]
defaultEnabled  = 8 条全为 true
foreach         = [oredCriteria/criteria, criteria.criteria/criterion, criterion.value/listItem]
variables       = [criterion, orderByClause]
errors（默认）   = [模拟参数下表达式 ${criterion.condition} 求值为空（MP 占位 ${ew...} 等需真实参数值）]
outcomes（默认） = {distinct=true, _parameter != null=true, criteria.valid=true,
                   criterion.noValue=true, orderByClause != null=true}   // 其余 when 未求值
```

第 1 行就是用户截图的来源：`( and )or( and )` 经 SQL 高亮/格式化后即 `(AND` / `OR (AND`。
第 5 行解释了用户横幅里那句英文告警，但它与同帧产物（有 WHERE）**不可能同时成立**——见 R6。

## 三、根因

### R1　`${}` 直替在模拟参数下求值为空，条件整段塌陷（主因）

`${criterion.condition}` 里的 `criterion` 是 foreach 变量，值是 `MockIterable` 的元素
（`Ognl.kt:28-30`，恒为 `MockObject(emptyMap())`）；`MockObject.get("condition")` 取不到即 null，
`TextSqlNode` 对 null 求值输出空串（`TextSqlNode.kt:44-56`，对齐 MyBatis issue #274）。
于是 `and ${criterion.condition}` 退化成 `and `。

同一处还有第二个空值来源：`mockValueOf` 对 `ParamKind.OBJECT` 返回**无属性的** `MockObject()`
（`XmlPreviewModel.kt:214-220`），所以即使 `parameterType` 解析成功、知道参数是个 DTO，
`_parameter.xxx` 与任何对象属性路径仍然一律取不到值。

### R2　foreach 每迭代体被 trim 后首尾相接，trim 的前缀裁剪只做一次

`ForeachSqlNode.FilteredContext.appendSql` 对每轮产物做 `newSql.trim()`
（`ForeachSqlNode.kt:167-170`，T-M25.5 为紧凑展示引入）。两次迭代的 `and` 因此拼成 `andand`，
而 `<trim prefixOverrides="and">` 只删掉首个 `and`（`TrimSqlNode.kt:83-92`，语义与 MyBatis 3.5.19
`TrimSqlNode.FilteredDynamicContext.applyPrefix` 一致，**不是 bug**），残余的 `and` 就留在括号里：

```
内容 "andand" → trim 删 "AND" 三字符 → "and" → 加前后缀 → "( and )"
```

同一来源还造成 separator 粘连：`)or(`（根上下文 `appendSql` 会插空格，但 trim 的内部缓冲是纯 append）。

注意：真实 MyBatis 在同样输入（condition 空）下也会产出 `( and )`——所以本项不是引擎失真，
而是 R1 的放大器；真正要修的是 R1，R2 只需保证不产生 `andand` 这类单词粘连。

### R3　foreach 默认展开 2 次，与 Example 的实际形态不符

`DEFAULT_FOREACH_SIZE = 2`（`XmlPreviewModel.kt:224`），面板输入框留空也回落 2
（`SqlPreviewPanelParamPanel.kt:1260-1266`）。而 Example 一次典型调用是 1 个 `Criteria`、1 个
`Criterion`；2 次展开直接产出 `or` 与重复条件。对照表第 2 行（1 次）与第 3、4 行（1 次 + 单分支）
说明「次数 1」是能否产出合理骨架的前提。

`IN (ids)` 场景默认 2 次是有意设计（展示 separator），不能全局改成 1。

### R4　choose 的互斥 when 默认全开，且默认走信息量最少的分支

`defaultTruth = !test.contains("== null")`（`XmlPreviewModel.kt:239`）→ 四个 `criterion.*Value`
全部为 true。`ChooseSqlNode` 按顺序短路，于是只走第一个 `noValue`——那是唯一**不带 `#{}` 参数**的分支。
对照表第 3、4 行说明：只开 `singleValue` 能得到 `WHERE ( ? )`，只开 `betweenValue` 能得到
`WHERE ( ? and ? )`，都是结构正确的骨架。

### R5　默认启发式把「裸布尔字段」与「`!= null`」一律展开

- `distinct` 是 Example 的 boolean 字段（MBG 默认 false），被展开 → 产物多出 `select distinct`；
- `orderByClause != null` 被展开，`${orderByClause}` 又拿到占位 `"1"` → `order by 1`。
  `order by 1` 是**能执行**的 SQL，语义却从「按调用方给的子句排序」变成「按第一列排序」——
  这比报错更危险。

### R6　告警行混装「基线渲染」与「本轮渲染」两份错误

`XmlPreviewService.toResult` 用默认 state 先渲染一次，把 `rendered.errors` 塞进 `result.warning`
（`XmlPreviewService.kt:264-276`）；面板 `updateMeta` 再把它与本轮 `xmlErrors` 合并
（`SqlPreviewPanelResultView.kt:653-661`）。于是同一行里既有基线（无交互状态）的报错，也有当前勾选状态
的报错——用户截图那两条互相矛盾的信息正是这么来的。附带：`foreach` collection 为 null / 不可迭代的
文案是 MyBatis 英文原文直接透传，中文 UI 里读不懂。

### R7　MBG Example 的 Criterion 无法从调用点反推（能力缺口）

全仓没有 MBG Example 支持（`converter/jpa/QueryByExampleConverter` 只处理 Spring Data 的
`Example`）。`andCustIdEqualTo(...)` 静态可推成 `cust_id = ?`，但那需要调用链解析 + 方法名到列名的映射
（列名可从 `Base_Column_List` 或表元数据反推）——这是让预览从「骨架」升级成「可执行」的唯一路径，
也是 R1 的根治手段（`${criterion.condition}` 能拿到真值，占位就不需要了）。

## 四、方案

### M1　止血：让首屏不再输出会被误读的 SQL

**T1　`${}` 空值占位（R1）**

`TextSqlNode` 增加「占位模式」：求值为 null 且表达式含根变量时，输出 `{{表达式}}` 而不是空串。
由 `XmlSqlRenderer.render(placeholderForEmptyDollar = true)` 开启，**默认 false**——真实 SQL 产物与
既有 5000+ 断言零影响，只有 `XmlPreviewModel.renderSql` 传 true。UI 侧把 `{{…}}` 用占位色标出。

效果：`WHERE ( {{criterion.condition}} )`（单次展开），缺段位置一眼可见，不会被当成插件识别错。

配套（必须同批）：执行 / EXPLAIN 前若产物含 `{{…}}`，走与 `${}` 确认闸门同源的拦截提示——
当前 `( and )` 与 `order by 1` 是**可以**被送去执行的，占位模式反而要把这条路关严。

**T2　迭代分隔（R2）**

`ForeachSqlNode.FilteredContext.appendSql` 在 trim 后，若本轮内容非空且缓冲已有内容，则补一个空格。
消除 `andand` 与 `)or(`。不改 `TrimSqlNode` 的裁剪语义，也不改 `<where>` 的八种前后缀变体。

**T3　foreach 次数提示（R3）**

不改全局默认；在 foreach 行的 tooltip 与 `panel.xml.foreach` 区标题补一句「Example / 集合里再套集合的
场景通常只有 1 项」。真正的默认值交给 T5。

**T4　choose 互斥组（R4）**

`XmlPreviewModel.collect` 里识别 `ChooseSqlNode`，把 `whenNodes` 归为一组（新增
`XmlConditionView.group` 或独立的 `chooseGroups` 表）。默认只启用组内一个分支，优先级：
含 `#{}` 且参数最多者（`singleValue` / `betweenValue` / `listValue`）优先于 `noValue`；
同分取先出现者。面板同组内做成单选（或至少加组标题 + 互斥说明）。

风险：用户手写 choose 时，「默认选中带参数的分支」可能与直觉相反。缓解：组内默认项在 UI 上带说明
（「互斥组，默认选中含参数的分支」），且用户点其他分支即切换。

**T5　MBG Example 形态默认档（R5）**

`XmlPreviewService` 在 `parameterType` 可解析为 PSI 类、且该类满足「有 `oredCriteria` 字段或有
`createCriteria()` 方法」时，产出 `ExampleProfile` 注入 `XmlPreviewModel.from(...)`，一键改四项默认：

- `distinct` 不展开（MBG 默认 false）；
- `orderByClause != null` 不展开（MBG 默认 null）；
- 外层 `*Criteria` 与内层 `criteria.criteria` 各自展开 1 次；
- `choose` 组默认走 `singleValue`。

无 project（纯文本测试链）或类解析不到 → profile 为 null，行为与今天完全一致。

**T6　告警分帧（R6）**

`toResult` 不再把基线渲染 errors 混进 `result.warning`（改为独立字段 `baselineErrors`，或干脆只保留
解析期/静态告警）；`updateMeta` 只显示「本轮交互渲染 errors + 解析期告警」。
`foreach` collection 为 null / 不可迭代两条文案中文化，并给一句可操作指引
（「若该集合在真实调用中必有值，请取消『为 null』勾选」）。

效果：告警永远不会描述一个与当前产物不一致的状态。

### M2　高保真：从调用点还原 Criterion（R7，可独立立项）

从 `mapper.selectByExample(example)` 的实参变量出发，追踪同一方法体内对它的
`createCriteria()` / `or()` / `andXxxYyy(v)` 调用，产出模拟值：

- `criterion.condition` = 列名 + 操作符（`andCustIdEqualTo` → `CUST_ID =`）；
- `criterion.value` / `secondValue` = 调用实参；
- `noValue` / `singleValue` / `betweenValue` / `listValue` 按操作符族定档。

列名来源优先级：`Base_Column_List` 里的列（驼峰化后与方法名匹配）→ 表元数据 → 放弃该条。
操作符族至少覆盖 `EqualTo` / `NotEqualTo` / `GreaterThan` / `LessThan` / `Like` / `NotLike` /
`In` / `NotIn` / `Between` / `IsNull` / `IsNotNull`。

落地后 `{{criterion.condition}}` 会被真实片段替换，预览产物可执行——T1 的占位自然退化成兜底。

## 五、验收判据

- 新增用例（`xml/` 包，纯逻辑，进 testFast 桶）：
  1. MBG Example 金样——`selectByExample` + `Example_Where_Clause` 原样 XML，默认 state 产物断言为
     `… FROM CT_CUSTMANAGER_REL WHERE ( {{criterion.condition}} ? )`（T1+T4+T5 合并后）；
  2. 单次展开 + 单分支的四个分支各一例（回归 R3/R4 的对照读数）；
  3. `{{…}}` 占位默认关：`XmlSqlRenderer().render(...)` 不传参时 `${}` 空值仍输出空串；
  4. 迭代分隔：两次迭代的 `and` 不得出现 `andand`；
  5. 告警分帧：默认 state 的 errors 不得出现在本轮 state 的错误行里。
- 反向验证（逐项）：摘掉 T1 的占位分支 → 第 1 例变红；摘掉 T2 的空格 → 第 4 例变红；
  摘掉 T4 的组默认 → 第 1 例变红；摘掉 T5 的 profile → 第 1 例变红。
- 真机（用户侧）：demo 项目打开 `selectByExample` 预览，首屏应为「结构正确 + 缺段有占位」，
  横幅只报与当前状态一致的错误。
- 既有金样零变化：`TrimSqlNodeTest` / `ForeachSqlNodeTest` / `XmlPreviewModelTest` /
  `fidelity/RealEngineFidelityTest` 全绿（T1 的开关默认关是前提）。

## 六、待验证前提（动手前先证伪）

- **V1**：用户截图那句 `The expression 'oredCriteria' evaluated to a null value.` 的真实来源。按现有代码，
  基线渲染与交互渲染都不会在「有 WHERE」的同一帧报它；最可能是「勾过『为 null』后取消，复选框与渲染
  状态不同步」或「基线告警残留」。需要用户复现步骤或 `idea.log`，否则 R6 只按代码事实修（分帧），
  不追这条状态的同步问题。
- **V2**：`parameterType` → PSI 类在用户项目里能否解析（跨模块、源码 jar、Maven 未导入都会落空）。
  T5 的 profile 命中率需在 demo 项目实测；命中不了时行为退回今天，不报错。
- **V3**：`{{…}}` 占位串会流经参数回填、复制、格式化、执行闸门四条下游。落地前 grep 全部消费者，
  确认没有「把占位当 SQL 片段解析」的路径。
- **V4**：T4 的「带参数分支优先」在非 MBG 的 choose 上是否可接受；建议先在真机上看两个用户自有 mapper
  的 choose 首屏再定。

## 七、边界（本次不做）

- 不改 `TrimSqlNode` / `WhereSqlNode` / `SetSqlNode` 的裁剪语义——实测与 MyBatis 3.5.19 一致，
  `( and )` 的形态在真实引擎的同输入下同样成立。
- 不引入完整 OGNL，不解析 `andXxxYyy` 之外的自定义 Example 扩展。
- 不自动执行 / EXPLAIN 改写后的 SQL。
- 不动 MP 的 `ew.*` 占位链（`XmlEwExpander` / `XmlEwResolver`）。

## 八、建议实施顺序

1. T6（最小、独立、立刻消除误导性告警）
2. T2（单点、零语义变更）
3. T1 + 执行闸门（占位模式 + 拦截）
4. T4（choose 组）
5. T5（Example profile）
6. T3（文案）
7. M2 独立立项，视用户对「可执行预览」的需求强度排期

## 九、实施记录

（待填）
