# Wrapper 经工具类处理后条件丢失（T-WrapperToolCrossMethod，D264）

- **日期**：2026-11-27 20:50
- **编号声明**：本册新占 **D264**（`grep -rn "D264" docs/` 只有 `20261127-1339-PluginDescSlimAndQrValign` 册 :9 的指针字「下一全新 D264」，无真实占用）；下一全新 **D265 / V252 / O-169**。
- **来源**：用户 2026-11-27 20:24 在知乎评论区反馈（截图存档于本次会话）。原话：
  > 「安装插件了，但在项目里，我写了一个工具类，通过工具类对 mp 的 QueryWrapper 处理之后，最终的 sql 里少了这一部分。貌似，这个插件只处理简单的 if 判断，对于跳转到其他方法、稍微复杂一点的逻辑，就被舍弃掉了。」
- **定位**：这是 **D256 之后的下一层缺口**。D256 解决的是「认不认得出这是 Wrapper」（类型识别，父链/名字启发）；本次是「认出来了，但链上的条件在跨方法边界被丢掉」（链收敛）。两者修复面不重叠，D256 的成果是本次的前置条件。
- **范围**：`psi/WrapperLocator` 的跨方法链收敛（`expandHelperCalls` / `expandFactoryCalls` / 重新赋值分段）+ 消费行锚点可达性。**不改** converter 渲染语义、不改执行链、不改面板布局、不改类型识别（D256 面）。

---

## 一、非 GUI 复现（已完成，读数可复现）

复现装置：`src/test/kotlin/cn/song/mybatisplus/visualizer/psi/WrapperToolClassReproTest.kt`（513 行，11 例）。
链路：LightPlatform 真实 Java PSI → `WrapperLocator.locateWithTable` → 断言链上实际收集到的方法名序列。全程不起 IDE GUI。

定向命令（约 2 分钟）：

```bash
./gradlew test --tests "cn.song.mybatisplus.visualizer.psi.WrapperToolClassReproTest" \
  -Pkotlin.compiler.execution.strategy=in-process
```

### 权威读数（2026-11-27 21:32 实测，16 tests / 9 failed）

| # | 用例 | 用户形态 | 期望 | 实测 | 判定 |
|---|---|---|---|---|---|
| B1 | `testBaselineSingleLayerHelperArgument` | 工具类接实参就地追加（单层） | `[eq, ne]` | `[eq, ne]` | **绿（基线，装置有效）** |
| R1 | `testReproNestedHelperTwoLevels` | 工具类内部再传一层（helper→helper） | `[eq, isNotNull, ne]` | `[eq, ne]` | **红，丢第二层 `isNotNull`** |
| R2 | `testReproHelperDelegatesToAnotherAndReturns` | 工具类纯透传到另一方法 | `[eq, ne]` | `[eq]` | **红，透传层整段丢** |
| R3 | `testReproHelperLocalAlias` | 工具类内局部别名 `target = w` 再追加 | `[eq, ne]` | `[eq]` | **红，别名上的条件丢** |
| R4 | `testReproHelperReturnsWrapperAndReassigned` | 工具类返回 wrapper，调用方 `w = decorate(w)` | `[eq, ne]` | **`[ne]`** | **红，且 `eq` 反被切掉（比丢条件更糟）** |
| R5 | `testReproHelperBuildsAndReturnsWrapper` | 工具类构造并返回（`return q` 裸变量） | `[eq, isNotNull]` | **`[buildQuery]`** | **红，把工具方法名当成条件方法** |
| R6 | `testReproHelperResultConsumedInline` | `mapper.selectList(FilterUtil.buildQuery(1))` 内联消费 | `[eq]` | **定位不到链（null）** | **红，整条链不可预览** |
| R7 | `testReproNestedLambdaBlockWithHelperCall` | 嵌套 lambda **代码块体**内追加条件 | 含 `eq`+`ne`，不含 `trim` | **`[eq, and]`** | **红，块内 `ne` 完全丢失** |
| R8 | `testReproOrderDependenceProbe` | 与 R4 同语料，仅调整类声明顺序 | `[eq, ne]` | **`[]`** | **红，整条链全空**——位置机制冲突的直接证据 |
| **R10** | **`testReproBareVariableReturnProducesConditionlessSql`** | **产物级**：`return q` 形态下用户实际看到的 SQL | 含 `WHERE` 与 `status` | **`SELECT id, status, device_name FROM device_info_entity`（无 WHERE）** | **红——不是「少了条件」，是整条 WHERE 消失** |
| P1 | `testReproHelperInternalChainedCall` | 工具类内单句链式 `w.ne().like()` | `[eq, ne, like]` | 同期望 | 绿 |
| P2 | `testReproHelperCalledTwiceWithDifferentArgs` | 两个不同工具类各调一次 | `[ne, like]` | 同期望 | 绿 |
| P3 | `testReproHelperAbstractWrapperParameter` | 形参声明为 `Wrapper<T>` 基类型 | `[eq, ne]` | 同期望 | 绿（**证明与形参类型无关**） |
| P4 | `testReproStaticImportHelper` | 静态导入裸调 | `[eq, ne]` | 同期望 | 绿（装置限制已排除，见 V3） |
| P5 | `testReproCustomChainMethodDroppedByParser` | 自研子类新增链方法 `w.statusEq(1)` | SQL 含 `status` | SQL 含 `status` | **绿——自研链方法实际能正确解析**（修正侦察 L9 的影响面推断） |
| P6 | `testReproToolMethodNameCollidesWithMpCondition` | 工具方法名与 `select` 撞名 | 工具方法不进链，内部 `ne` 要进 | 同期望 | 绿（`FilterUtil.select(w)` 的 qualifier 是 `FilterUtil` 非 `w`，撞名风险在该形态下不成立） |

> **R10 是本次最重要的单条读数**：它把问题从「少了一部分条件」升级为「整条 WHERE 消失」，且**无任何提示**。用户看到 `SELECT id, status, device_name FROM device_info_entity` 会以为这条查询本来就没有过滤条件——这是「静默错误 SQL」，比报错严重得多。

> **对侦察结论 L9 的修正（重要）**：侦察报告称「`QueryWrapperParser:252` 无 else 分支，未知方法静默丢弃」，机制描述正确，但**不能推论「自研链方法一定丢」**。P5 实测显示 `statusEq` 能让 SQL 产出 `status` 条件——说明链方法在其方法体内对 `eq` 的调用被收集进了链。因此 L9 是**真实存在但影响面待定**的机制，不是「所有自定义方法必丢」。实施时以 P5 为界：**能解析的不改，确认丢的才补可见性**。

> **对侦察结论「撞名会渲染成错误条件」的修正**：侦察担心 `select`/`last`/`apply` 撞名会导致工具调用被渲染成列/片段。P6 实测该形态下不成立（qualifier 不同即可区分）。但**若工具方法被调用在 wrapper 自身（`w.last(...)` 形式）**，风险仍在——这属待验证前提 V9。

### 三条最有价值的判据

1. **P3 绿 = 类型不是门槛**。形参写 `Wrapper<T>` 与写 `LambdaQueryWrapper<T>` 结果一致，说明失败与「声明类型认不出」无关，纯粹是**调用形态**没被跟进。这条把本缺陷与 D256 彻底分开。
2. **R5 产出伪条件比丢条件更危险**。`[buildQuery]` 意味着 `buildQuery` 被当成一个 wrapper 条件方法交给了 `ArgResolver.toWrapperCall`。这是「宁可不识别，不可出错误 SQL」原则的现行违例，优先级应高于纯丢条件。
3. **R4 的 `[ne]` 与 R8 的 `[]` 互为对照**：同一段语料，只因类声明顺序不同，收集结果从 `[ne]` 变成 `[]`。这证明**结果依赖源码物理位置而非语义**——根因是重新赋值分段（`filterCallsToAnchorSegment` 按 `textRange.startOffset` 过滤）与展开调用「锚定在 helper 调用点」两套位置机制互相抵消。这是最不该出现的一类不确定性，也是修 3 的直接依据。

---

## 二、根因分层（源码级，逐条带出处）

| 层 | 机制 | 现状 | 证据 |
|---|---|---|---|
| L1 | **实参型 helper 只展开一层** | `expandHelperCalls` 在 helper 方法体里找 `qualifier.resolve() === param` 的调用，**不递归**；注释明写「只展开一层（helper 内不再递归传参）」 | `psi/WrapperLocator.kt:966-981`（注释在 :964） |
| L2 | **展开只认「qualifier 直接 resolve 到形参」** | 局部别名 `target = w; target.ne(...)` 的 qualifier resolve 到 `target`（局部变量），不等于形参 → 整段不收集 | `psi/WrapperLocator.kt:977-978` |
| L3 | **透传层不被跟进** | `applyStatus(w)` 体内只有 `inner(w)`，没有对 `w` 的 qualifier 调用 → `expandHelperCalls` 返回空；`inner` 从未被访问 | `psi/WrapperLocator.kt:973-979` |
| L4 | **返回值形态完全无覆盖** | `expandFactoryCalls` 只在**变量 initializer 的链首是工厂调用**时触发；`w = decorate(w)` 是赋值表达式不是 initializer；`return q` 是裸引用不是链（`findFactoryReturnChain` 只收 `collectChainFromInitializer`） | `psi/WrapperLocator.kt:1578-1594`、`:1619-1624` |
| L5 | **`return q` 裸变量收不到** | `findFactoryReturnChain` 用 `collectChainFromInitializer(expr)`，`expr` 是 `PsiReferenceExpression` 时返回空 | `psi/WrapperLocator.kt:1619-1624` |
| L6 | **重新赋值分段把前段切掉** | `filterCallsToAnchorSegment` 取「锚点之前最后一个赋值点」作段首，只保留 `startOffset > segmentStart` 的调用；`w = decorate(w)` 这个赋值点本身把此前 `eq` 全滤掉，而赋值右值里的展开又没建立 | `psi/WrapperLocator.kt:1343-1356`、`:1032`、`:1126` |
| L7 | **非条件方法被当条件收进链** | R5 的 `[buildQuery]`：`initializer` 链首不是 `isWrapperFactoryCall`（`buildQuery` 不在工厂方法名集合），但也未被剔除，直接进了 `callElements` | `psi/WrapperLocator.kt:1046-1049`、`:1634-1638`（工厂名集合不含工具方法） |
| L8 | **消费行内联形态定位不到链** | `findWrapperVariable` 找不到变量；`findWrapperNewExpression` 找不到 new；`findWrapperFactoryCall` 不认 `buildQuery` → `locateWithTable` 返回 null | `psi/WrapperLocator.kt:76-90`、`:540-550` |

**一句话根因**：插件把「Wrapper 条件链」建模成**单方法内的线性语句序列**，而用户的 Wrapper 实际上在**方法之间流动**（实参传入、返回值传出、别名中转）。模型与现实的形状不匹配，所以每跨一次方法边界就掉一段条件。

### 补充：还有**第二条**独立丢条件路径（务必区分）

上面的 L1~L8 全在**收集层**（`WrapperLocator`）。侦察确认还存在一条**解析层**的独立路径：

| 层 | 机制 | 现状 | 证据 |
|---|---|---|---|
| L9 | **解析器 `when` 无 else 分支，未知链方法静默丢弃**（机制成立，**影响面待定**） | `QueryWrapperParser.parseInternal` 的巨型 `when` 只枚举约 60 个官方方法名，`:252` 只留一行注释「其他方法（not/嵌套条件等）暂忽略，保证健壮性」，**无日志、无标记、无降级提示**。行为已被单测固化为契约：`QueryWrapperParserTest.kt:520-531`。但 P5 实测显示「自研链方法 `statusEq` 能让 SQL 出 status 条件」——因为其方法体内的 `eq` 已被收集进链，**并非所有自定义方法都丢**。实施时须逐个取证哪些形态真丢 | `converter/QueryWrapperParser.kt:252` |
| L10 | **空实参条件方法二次丢弃** | `if (call.args.isEmpty() && call.methodName in CONDITION_METHODS) continue`——实参解析失败（如 lambda 解析不出）等价于空，条件整体消失 | `converter/QueryWrapperParser.kt:92`、条件集合 `:690-698` |
| L11 | **嵌套 lambda 代码块体过度收集（反向问题）** | 代码块体**无差别**收集块内所有 `PsiMethodCallExpression`，不判是否 wrapper 条件调用；`log.info(...)` / `trim()` 都会被收成 `WrapperCall`（再被 L9 静默丢掉） | `psi/ArgResolver.kt:181-185` |
| L12 | **三元/循环/switch 在 Wrapper 链路径 0 支持** | 三元实参落到 `ExpressionEvaluator` 的 `else` → 整段变 `?`；循环与 switch 在 `psi/`+`converter/` 包内 0 处 PSI 引用（只有 JDBC 字符串链 `SqlStringConcatenator` 有 LOOP/OPTIONAL 降级，是另一条链） | `psi/ExpressionEvaluator.kt:50`；反例 `psource/SqlStringConcatenator.kt:777-815` |
| L13 | **if 分支规则会误杀同分支多调用** | 「同一 `PsiIfStatement` 内只保留源码序第一个调用」——`if (x) { w.eq(a); w.ne(b); }` 里 `ne` 被杀；且不区分 then/else、不按锚点选分支 | `psi/WrapperLocator.kt:1116-1122`；单测固化 `SqlPreviewServiceUpdateTest.kt:252-280` |
| L14 | **工厂展开只认 `qualifier == null`** | `this.buildW()` / `factory.buildW()` 这类有限定名的工厂调用**直接放弃展开** | `psi/WrapperLocator.kt:1582-1584` |
| L15 | **只取方法体第一个 return** | `PsiTreeUtil.findChildOfType(body, PsiReturnStatement)`——多 return / if-else 分支 return / 提前 return 全部丢弃 | `psi/WrapperLocator.kt:1621` |
| L16 | **降级静默是系统性的** | `PreviewResult.degradations` 只由 JPA/HQL 链写入，**Wrapper 链从不写**；`unparsedSets` 只服务 UPDATE 的 SET；核心四文件（`WrapperLocator` / `ArgResolver` / `ExpressionEvaluator` / `QueryWrapperParser`）**零 Logger** | `model/PreviewResult.kt:200`、`psi/LocatedWrapper.kt:55-60`、四文件 `grep -c Logger` 均为 0 |

**这一点改变了修复优先级**：用户抱怨的「少了这一部分」如果发生在 L9，那么即使我们把 L1~L8 的跨方法展开全部修好，条件仍然会在解析器里消失——**必须同时给解析层加「未知方法的可见降级」**，否则修完收集层用户看到的还是「少条件」。L16 是 L9 的放大器：连日志都没有，用户和我们都拿不到线索。


---

## 三、修法方向（供实施者取舍，非最终方案）

### 修 0（最高优先，最坏面）：拦住「实参是 Wrapper 但链定位失败」的批量兜底

R10 实测产物 `SELECT id, status, device_name FROM device_info_entity`（**无 WHERE**）证明：链定位失败后落到批量模板，会产出**静默的错误 SQL**。这比丢条件严重——用户会误以为该查询本就没有过滤条件。

- 位置：`SqlPreviewService.preview` 的批量分支之前（约 `:603` 处），以及 `BatchOperationConverter.convert` 的注册表分支（`:240`/`:249`）。
- 判据：实参表达式**静态可见是 Wrapper 类型**（复用 `detectWrapperTypeDeep` 或形参类型判定），但 `WrapperLocator` 四顺位全失败 → **拒绝产出**，改为明确提示「检测到 Wrapper，但无法解析其条件链」。
- 同型先例可照抄：`BatchOperationConverter.isJoinFamilyRejected`（`:321-322`，在 `convert:240` 最前一律 return null）——「宁可无预览，不可输出错误 SQL」的既有口径。
- 注意：`selectList/selectMaps/selectObjs` 目前**没有**等价守卫，join 族才有。这是本修要补的缺口。

### 修 1：把「单层展开」升级为「带深度与循环防护的递归展开」

- 位置：`expandHelperCalls`。
- 语义：helper 体内对形参的调用展开后，若该调用本身又是「把 wrapper 传给另一个方法」，继续展开，直到无新调用或命中深度上限。
- 防护（必须同时具备，缺一不可）：
  - **深度上限**：新增 `WRAPPER_CROSS_METHOD_MAX_DEPTH = 5`（与既有 `ExpressionEvaluator.MAX_TRACE_DEPTH=5`、`SqlStringConcatenator.MAX_METHOD_FOLLOW_DEPTH=3` 同量级）。**不要复用** `WRAPPER_SUPER_CHAIN_MAX_DEPTH=8`——那是类型链语义，混用会互相牵制。
  - **visited 防环**：按 `PsiMethod`（被展开的 helper）与 `PsiVariable`（return 裸变量时）身份建 `MutableSet`，照 `collectSuperChain:1884-1885` 的写法（先判上限再 `visited.add`）。**现状 `expandFactoryCalls:1612` 的自递归无任何防护**（靠「实参嵌套有限」侥幸不爆），一旦扩成返回链递归，`a(){return b(w);} b(){return a(w);}` 会栈溢出。
  - **去重**：现有 `distinctBy { textRange.endOffset }` 在递归后不够——必须改成 `(文件, offset)` 复合键，否则跨文件时两条不同调用可能因 endOffset 相同被误去重（条件永久消失）。
- 别名（L2）：展开时把 helper 体内的**局部变量**也纳入追踪——`target = w` 视为 `w` 的别名（仅限直接赋值，不追多层别名变换，避免过度推断）。

### 修 2：让「返回值形态」进入链构建

两个子形态，修法不同：

- **R2/R5/R10（工具类返回 wrapper 裸变量）**：`findFactoryReturnChain` 增加「return 表达式是 `PsiReferenceExpression` 且 resolve 到**方法体内的局部 wrapper 变量**」分支——递归走该局部变量的 `buildChain` 收集逻辑（同样需要深度+环防护）。
- **R6（内联消费）**：`findWrapperFactoryCall` 增加「实参里的方法调用返回 Wrapper 类型」识别，把该调用当作伪工厂链首。
- **多 return 规则**：照 `SqlStringConcatenator.kt:480-492`——多分支文本不一致即放弃（不猜分支），并排除嵌套 lambda/内部类的 return（`:483-484` 有现成写法）。现状 `findChildOfType(body, PsiReturnStatement)` 取第一个 return 且无任何过滤。

### 修 3：重新赋值不切断前段（R4/R8）

`filterCallsToAnchorSegment` 的语义是「按条件生命周期分段」，本身是对的；问题在于 `w = decorate(w)` 这种**自引用赋值**被当成了「生命周期重置」。修法：自引用赋值（RHS 里引用了 LHS 同一个变量）不记为分段点，而是视为**链的延续**（RHS 内部的条件并入当前段）。

同时必须修**跨文件偏移比较**：展开调用的 `textRange.startOffset` 来自 helper 文件，却被拿去与调用方的 `assignmentOffsets` 比大小 → 段过滤结果随机（`WrapperLocator.kt:1049` vs `:1351`）。需引入 `(文件, offset)` 复合键。


### 修 4：非条件方法不得静默入链（R5 的伪条件面）

这条应作为**独立守卫**先落地（风险最低、收益最直接）：链内收集到的调用，若既不是已知条件方法（`CONDITION_CHAIN_METHOD_NAMES`）、也不是结构方法（join/select 族），且不在工厂名集合内，则不入链并留痕。宁可少条件，也不产出伪条件。

### 修 5（L9/L16）：解析层的未知方法必须可见，不能静默吞

这是本次**最重要的新增项**，独立于跨方法展开：

- `QueryWrapperParser.parseInternal` 的 `when` 增加 `else` 分支：把未识别的方法名收进一个 `unknownMethods: List<String>`。
- 该集合随 `PreviewResult` 透出，由 `SqlPreviewService` 渲染成**与 `unparsedSets` 同款的红字警告**（「以下条件方法无法静态解析：`statusEq`」）。
- 注意**不能直接展开自定义方法体**（那需要跨方法调用链，属修 2 范畴）；本修只解决「静默」问题，让用户知道少了什么。
- 风险点：`QueryWrapperParserTest.kt:520-531` 现有契约断言「未知方法安全忽略」且产出 1 条条件——本修**只增可见性不改条件条数**，该用例应保持绿；若变红说明改到了渲染行为，需回头看。

### 修 6（L13/L15）：if 分支与多 return 的语义

这两条不是「跨方法」问题，而是**同一机制下的分支语义缺失**，建议同批处理：

- L13：`if` 去重应改为「按锚点所在分支选，而不是源码序第一个」；同分支内多个调用必须全部保留（现有实现会误杀）。
- L15：`findFactoryReturnChain` 应支持多 return（按锚点/调用点可达性选，或全部收集后标注分支）。
- 若本次范围控制不住，至少要在计划里登记为「已知未修」，不能默认已解决。

### 修 7（L11 / R7）：嵌套 lambda 代码块体不得无差别全收，且必须收全

R7 实测 `[eq, and]`——块内 `ne` 丢了、`and` 只剩空壳。当前实现（`ArgResolver.kt:181-185`）是「代码块体里所有方法调用全收」，方向错了但结果也没对：

- **该收的没收到**：块内 `x.ne(...)` 的 `x` 是 lambda 形参，`toWrapperCall` 把它当普通调用和方法名，语义没落成条件。
- **不该收的收了**：`log.info(...)` / `trim()` 这种非 wrapper 调用也会进链（P 系用例里 `trim` 已用断言钉住）。

修法：代码块体按「qualifier resolve 到 lambda 形参（或其别名）」筛选，与 `expandHelperCalls` 的判据同源；同时套用修 1 的深度/环防护。



---

## 四、影响面矩阵

| 消费者 | 读什么 | 本次症状 | 是否需同步改 |
|---|---|---|---|
| gutter 变量声明锚点 | `WrapperAnchorDetector.anchorElement` | 声明行图标可能正常（锚点只看声明），但点开预览少条件 | **需部分同步**：锚点不产出条件，但若新增「工具方法返回 wrapper = 链首」，`isChainHeadCall`、`isSqlCandidate`、`initializerHeadIsWrapper`、`hasWrapperArgument` 四处都要一起认，否则「能预览没图标」或「图标点开是另一条路」 |
| `WrapperLocator.locateWithTable` | `buildChain` 三顺位 | 主战场 | **改**（修 1/2/3） |
| `SqlPreviewService.preview` | 路径 1（wrapper 链）→ 路径 2（批量） | 路径 1 少条件；链定位失败时**落到批量兜底产出无 WHERE SQL** | **需加拦截**：在批量分支前拦住「实参是 Wrapper 但链定位失败」，否则上游修好前会静默产出错误 SQL |
| `BatchOperationConverter` | 批量方法模板 | D1/B1 形态下出 `SELECT 全列 FROM 表`（可能带 `deleted=0`，但无业务条件） | **需加守卫，不需同步展开**。join 族已有 `isJoinFamilyRejected:321` 先例可照抄；`selectList/selectMaps/selectObjs` 目前**无等价守卫** |
| `ArgResolver.resolveNestedLambda` | lambda 体内链 | **旁路**：lambda 体内跨方法调用完全不经 `WrapperLocator` 的展开 | **需改**（修 7），否则修好 `expandHelperCalls` 后 lambda 内仍丢 |
| `QueryWrapperParser` | `WrapperChain.calls` | 未知方法落 `else` 静默 | **不在 converter 补展开**（无 PSI 上下文），但要补可见性（修 5） |
| `XmlEwResolver` | `${ew.customSqlSegment}` | 失败时 `?: return emptyMap()` **无 warning**，片段静默变空 | 跟随上游，建议同时补 warning |
| `actions/ConvertToSqlAction` | `sqlCandidatesInRange` | D1 形态当前不入候选 | 随 `isSqlCandidate` 变化自动生效，需补用例 |
| `ChainStateDetector` / `WrapperCompletionContributor` | 补全用链首识别 | **另有 2~3 份重复实现**（与 `factoryWrapperType` 平行） | 仅影响补全；规则变更需对齐，否则「能补全不能预览」 |
| EasyQuery / Mds / Querydsl converter | 各自 `collectSegments` | **三份同构拷贝**，注释自称与 `collectExtendingChain` 同构 | 不共用 MP 链，本次不改；但其 `MAX_CHAIN_DEPTH=32` 是「跨层回溯要有上限」的现成范式 |
| `KtWrapperDetector` | .kt 行级正则 | `locateWithTable` 对 kt 直接 return null | **范围外**，计划须写明 |
| `WrapperCache` | 缓存 `locateWithTable` 结果 | **键只有锚点文件的 `modificationStamp`**；helper 文件被改不会失效 | **必须补**：跨方法展开后，被展开方法所在文件也要进失效维度，否则改了工具类仍出旧 SQL |

### 三个此前从未踩过的新维度（第三路侦察补充）

| 维度 | 问题 | 证据 |
|---|---|---|
| **跨文件偏移比较** | 展开调用的 `textRange.startOffset` 来自 helper 文件，却拿去与调用方的 `assignmentOffsets` 比大小 → 段过滤结果随机 | `WrapperLocator.kt:1049` vs `:1351`、`:1349-1355` |
| **跨文件去重** | `distinctBy { textRange.endOffset }` **不含文件判定**；helper 在别文件时，两条不同调用的 endOffset 可能相同 → 误去重，条件永久消失 | `WrapperLocator.kt:1101`、`:496`、`:609` |
| **多 return 无一致性校验** | `findChildOfType(body, PsiReturnStatement)` 只取第一个 return，且**不排除嵌套 lambda/内部类的 return**；多分支文本不一致时也不放弃，直接取第一个 | `WrapperLocator.kt:1619-1624`。对照 `SqlStringConcatenator.kt:480-492` 有「多 return 文本不一致即放弃」+ `:483-484` 排除嵌套的现成规则 |

**这三条决定了修 1/2 不能只改「展开逻辑」**：必须同时引入 `(文件, offset)` 复合键、把 helper 文件纳入缓存失效维度、并按 `SqlStringConcatenator` 的规则处理多 return。否则主逻辑改对了，条件仍会在去重/分段/缓存三处悄悄消失。


---

## 五、待验证前提（实施者先证伪再动手）

| # | 前提 | 证伪方式 | 若不成立的后果 |
|---|---|---|---|
| V1 | 用户现场的主形态是「实参传入 + 返回值传出」之一，而非我们没枚举到的第三种 | 等用户提供用例/截图；同时用 demo 的七形态逐个点验 | 修完仍不解决用户问题 |
| V2 | `buildQuery` 这类工具方法名不会被 `BatchOperationConverter` 当成批量方法处理 | **已核实成立**：`BATCH_OPERATIONS` 是闭集映射（`BatchOperationConverter.kt:78`），`buildQuery` 不在其中 | — |
| V3 | 静态导入形态在**真实工程**里能被 `resolveMethod` 解析（测试装置的限制不代表真机） | 同文件嵌套类写法实测已绿（P4）；跨包 `addClass` 写法在 LightPlatform 下不解析属装置限制 | 若真机也不解析，需另立「静态导入」子项 |
| V4 | 递归展开的深度上限 5 足以覆盖现实封装（用户四层自研 wrapper + 两层工具类是极端值） | demo 语料 + 竞品对照 | 上限过低会在深层封装上仍丢条件 |
| V5 | 「自引用赋值不切段」不会破坏 `WrapperReuseRepro` 语料的既有语义（`w = new LambdaQueryWrapper<>()` 必须仍然切段） | 跑 `PreviewStmtTypeScopeTest:177` 引用的 `WrapperReuseRepro` 语料及既有用例 | 复用 wrapper 的场景会把两段条件合并，产出错误 SQL |
| V6 | `distinctBy(endOffset)` 换成身份去重后，不改变 P1/P2 两条已绿用例的收集结果 | 直接跑本类 P1/P2 | 去重口径改动引入重复条件 |
| **V7** | **demo 的钉定快照里有本次语料** | **已核实不成立**：`dev-support/test-demo/demo-revision.txt` 钉 `fcb1faf`，而 `custom-wrapper-demo` 模块是 `946fa5d0` 才引入的，快照里没有 | runIde 点验前必须先推 demo 仓 + 更新 `demo-revision.txt`，否则全新克隆看不到用例 |
| **V8** | **R8（类顺序探针）的红不是测试装置噪声** | 已两次实测（`[ne]` vs `[]`），差异只来自类声明顺序 | 若为噪声，修 3 的动机需重新评估 |
| **V9** | **工具方法名在 wrapper 自身上撞名（`w.last(...)` / `w.select(...)`）时是否被渲染成错误条件** | P6 只测了 `FilterUtil.select(w)` 形态（不成立）；`w.last(...)` 形态未测 | 若成立，修 4 需覆盖「wrapper 自身调用的未知方法」而不只是「实参位置的工具调用」 |
| **V10** | **批量兜底在 D1 形态下真的产出无 WHERE SQL** | 侦察推断 + R10 产物实测（`return q` 形态）已间接证明；但 `mapper.selectList(FilterUtil.buildQuery(1))` 走的是**批量分支**而非 wrapper 链分支，路径不同 | 若是，`SqlPreviewService` 必须加「实参是 Wrapper 但链定位失败」的拦截，优先级等同修 4 |

## 五之二、复现装置的位置与边界（实施者必读）

| 项 | 结论 | 出处 |
|---|---|---|
| 本任务复现类 | `src/test/kotlin/cn/song/mybatisplus/visualizer/psi/WrapperToolClassReproTest.kt`（14 例） | 已提交 |
| 断言粒度 | 只断言「链上收集到的方法名序列」+ 个别断言 SQL 片段；不进 GUI | `callsAtConsumer()` / `sqlAtConsumer()` |
| 金样矩阵（更贴近真实产物的装置，后续转绿建议迁过去） | `sql/spec/SqlPreviewSpecMatrixTest`（S11~，40 例）、`SqlPreviewSpecMatrixAdvancedTest`（S51+，44 例） | `docs/架构设计/03-开发与测试指南.md:134` |
| 跑哪个桶 | 本类 import `com.intellij.*` → **不进 `testFast`**，属平台桶；定向一律用 `./gradlew test --tests` | `mpv-testing.gradle.kts:411-424` |
| ⚠ 禁用 | **平台型类禁用 `testFast --tests`**——桶规则与 `--tests` 取交集，静默不跑且不改退出码 | `docs/LESSONS.md:971-977`（L86） |
| ⚠ 单跑分片 | 单跑 `testShardN` 会重写时长权重表、改变后续分片组成 | `03-开发与测试指南.md:37` |
| Kotlin 范围 | `locateWithTable` 对 `.kt/.kts` 直接 `return null` | `WrapperLocator.kt:81-82` |

---

## 六、同类排查表（按机制枚举全部入口，逐条给结论）

按「跨方法/跨语句边界」这个机制，把全仓同类入口列全，每条要给出「已修 / 天然正确 / 必须不改」的结论。**这是本次的强制交付物**，不能只修 R1~R6 六条。

| 入口 | 机制 | 初始判断 | 实施时确认 |
|---|---|---|---|
| `expandHelperCalls` | 实参型 helper 单层 | 必须改（修 1） | 待实施 |
| `expandFactoryCalls` / `findFactoryReturnChain` | initializer 工厂链 | 必须改（修 2） | 待实施 |
| `resolveFactoryArgumentChain` | 工厂方法参数链递归 | 已有递归（`expandFactoryCalls` 自调），但同样无深度/环防护 | 待核查 |
| `filterCallsToAnchorSegment` | 重新赋值分段 | 必须改（修 3） | 待实施 |
| `QueryWrapperParser` 未知方法分支（`:252`） | 解析层静默丢弃 | 必须改（修 5，只加可见性） | 待实施 |
| `isWrapperFactoryCall` | 工厂调用判定 | 不含工具方法 → R5 伪条件来源 | 修 4 的门 |
| `collectExtendingChain` | 链式延续收集 | P1 绿，天然正确 | 待回归确认 |
| `resolveNestedLambda` 块体分支（`ArgResolver.kt:181-185`） | 无差别全收 | **实测已红**（`[eq, and]`，内层 `ne` 丢失） | 必须改 |
| `WrapperAnchorDetector` 锚点集合 | gutter 图标 | 工具类内链首不在用户文件 → 天然不受影响 | 待确认 |
| `SqlPreviewService` 批量路径 | 批量模板 | 不共用本逻辑 | V2 已核实：`BATCH_OPERATIONS` 是闭集映射，`buildQuery` 不在其中 |
| `ArgResolver.toWrapperCall` | 单个调用 → 条件 | 收到非条件方法会静默降级 | 修 4 |
| `ExpressionEvaluator` 三元/方法调用（`:50`、`:202-214`） | 值级降级为 `?` | 本轮不改（改值不影响「少条件」主诉） | 登记为已知 |
| if 分支去重（`WrapperLocator.kt:1116-1122`） | 同 if 只留首个 | 同分支多调用被误杀 | 修 6 |
| 多个 return（`WrapperLocator.kt:1621`） | 只取第一个 | 分支 return 丢失 | 修 6 |
| Kotlin 侧（`KtWrapperDetector`） | .kt 文件 | `locateWithTable` 对 kt 直接 return null（`:81-82`） | **必须说明「Kotlin 不在本次范围」** |
| xbatis / EasyQuery / JPA / Querydsl / MDS | 各自链模型 | 不共用本逻辑 | **必须说明范围外** |

## 六之二、破损风险清单（改这个文件会影响谁）

| 风险 | 受影响对象 | 破约方式 | 等级 |
|---|---|---|---|
| 递归展开无深度/环防护 | `WrapperLocator` 全链 | 互递归 helper 导致栈溢出 / 无限循环（热路径在 EDT 上会直接卡死 IDE） | **高** |
| 递归展开使条件重复 | 所有 Wrapper 预览 | `distinctBy(endOffset)` 在递归后不够，同一 `ne` 可能渲染两遍 | **高** |
| 自引用赋值不切段改动过宽 | `WrapperReuseRepro` 语料 | `w = new LambdaQueryWrapper<>()` 也被当成「延续」，复用场景两段条件合并 → 错误 SQL | **高** |
| 修 5 改到渲染行为 | `QueryWrapperParserTest.未知方法_安全忽略`（`:520-532`） | 该用例断言「未知方法不影响条件条数」；只加可见性则保持绿，若顺带改了渲染就变红 | 中 |
| 防误伤用例被破坏 | `WrapperLocatorTest.testPlainMethodChainNotMisdetected`（`:863-878`） | 递归展开若无类型门，`helper.applyFilter(Object w)` 会被当成 wrapper 链 → 该用例变红 | 中 |
| 选择区候选数变化 | `WrapperLocatorTest.testSqlCandidatesInRangeCollectsAndDeduplicates`（`:880-916`） | 断言**恰好 3 个**候选；修 2 让工具类返回形态成为候选后可能变 4 | 中 |
| 锚点数量契约 | `WrapperAnchorDetectorJpaAndWholeFileTest`（`:191-337`） | 断言 `selectJoinList` 等「只有变量声明 1 个锚点」；本轮若扩锚点集合会破约 | 中 |
| 性能红线 | `WrapperLocator` 热路径（gutter 每次重绘） | `looksLikeWrapperName` 前门是为 O(1) 设计的；递归展开引入 resolve 成本，可能违反既有限制 | 中 |
| 缓存键不含展开深度 | `WrapperCache` | 缓存按「文件戳 + resolveVariables + 配置签名」失效；若展开行为随设置变化而未入键，会读到旧结果 | 低 |
| 深度上限值选择 | 用户四层封装 + 两层工具类 | 上限过低仍在深层丢条件；过高则性能与误收风险上升 | 低 |


---

## 七、实施顺序建议（风险从低到高）

1. **修 0 先行**（批量兜底拦截）：消除「无 WHERE 错误 SQL」这个最坏面。R10 已实测证明该产物存在。
2. **修 4**（伪条件守卫）：独立、风险低，消除「工具方法名被当条件」。
3. **修 5**（解析层可见性）：只加 `unknownMethods` 与红字提示，不改条件条数，`QueryWrapperParserTest.未知方法_安全忽略` 应保持绿。用户能立刻看到「少了什么」，也给我们取证线索。
4. **修 3**（自引用赋值不切段 + 跨文件偏移复合键）：修 R4/R8 那一对位置冲突。
5. **修 7**（嵌套 lambda 块体）：R7 已红，判据与修 1 同源，可在修 1 之后顺手做。
6. **修 1**（递归展开 + 别名追踪 + 深度/环防护 + 复合去重键）：主战场。
7. **修 2**（返回值形态 + 多 return 规则）：面最大，最后做。
8. **修 6**（if 分支 / 多 return）：可独立，也可登记为已知未修。
9. 九条 REPRO 用例逐条转绿，作为回归护栏留在仓里。

> 顺序理由：修 0/4/5 都是「让问题可见或不再更坏」，不引入递归风险；修 3 是单点语义修正；修 7/1/2 才涉及递归与位置模型，风险递增。

### 必须与主逻辑同批交付的三件事（否则修了也白修）

| 项 | 为什么不能拖 | 出处 |
|---|---|---|
| `(文件, offset)` 复合去重/分段键 | 跨文件展开后 `distinctBy(endOffset)` 与 `startOffset` 比较全部失效，条件会在去重/分段两处悄悄消失 | `WrapperLocator.kt:1101`、`:496`、`:609`、`:1049` vs `:1351` |
| `WrapperCache` 失效维度加 helper 文件 | 缓存键只有锚点文件的 `modificationStamp`，改了工具类仍出旧 SQL；用户会以为修复没生效 | `WrapperCache.kt:82-84`；可仿 `LocatedWrapper.kt:56-64` 的单文件校验扩成多文件 |
| 入口识别四处对齐 | 若新增「工具方法返回 wrapper = 链首」，`isChainHeadCall`/`isSqlCandidate`/`initializerHeadIsWrapper`/`hasWrapperArgument` 必须一起认，否则「能预览没图标」或「图标点开走另一条路」 | `WrapperAnchorDetector.kt:131-135`、`:156-158`、`:268-285`、`:300-315` |

---

## 八、实施记录

### 复现阶段（已完成，2026-11-27）

| 项 | 读数 |
|---|---|
| 插件侧复现类 | `WrapperToolClassReproTest`，16 例，**7 绿 / 9 红**（2026-11-27 21:32） |
| demo 侧语料 | `custom-wrapper-demo` 新增 `WrapperFilters.java` + `WrapperToolClassQueryService.java`（七形态），`mvnd -o -DskipTests clean compile` 通过（18 源文件） |
| demo 提交 | `ee14d44`「test(demo): Wrapper 经工具类处理复现语料（D264）」 |
| 定向命令 | `./gradlew test --tests "cn.song.mybatisplus.visualizer.psi.WrapperToolClassReproTest" -Pkotlin.compiler.execution.strategy=in-process`（约 1~2 分钟） |

### 三轮侦察的关键增量（都已用实测校验）

| 轮次 | 侦察给出 | 我的实测校验结果 |
|---|---|---|
| 第 1 轮（解析链） | 完整职责地图；`QueryWrapperParser:252` 静默丢弃；if/三元/循环/switch 能力边界；核心四文件零 Logger | L9 机制成立但影响面推断过宽 → P5 修正 |
| 第 2 轮（测试基座） | 金样矩阵装置（`SqlPreviewSpecMatrixTest`）；平台类禁用 `testFast --tests`；demo 钉定快照 `fcb1faf` 不含 `custom-wrapper-demo` | 装置边界写入 §五之二；V7 记为已证伪 |
| 第 3 轮（穷尽落点） | 跨文件偏移比较 / 跨文件去重 / 多 return 无过滤 / 缓存失效维度 / 批量兜底无守卫 / 入口识别三处重复实现 | 全部写入 §四 与 §七；其中「无 WHERE 错误 SQL」用 R10 产物实测确认 |

### 修复阶段

（待实施后回填：每步的定向读数、转绿情况、反向验证结果、以及 §六 排查表的逐条结论。）

### 教训预登记

- **用例首版三处假红**：跨包 `addClass` 静态导入、`MyWrapper` 未造 MP 基类桩、`addClass` API 在该测试 SDK 不存在——都被我误当成插件缺陷。教训：新语料先确认「装置本身能解析」，否则红的是自己。
- **侦察结论必须用实测校验**：侦察报告断言「`QueryWrapperParser:252` 未知方法必丢」，P5 实测 `statusEq` 能出 `status` 条件——机制对、影响面推断错。已在 §一 就地修正，未删原文。

---

## 九、验收口径

- `WrapperToolClassReproTest` 16 例：9 条 REPRO 转绿（尤其 **R10 产物级**必须从「无 WHERE」变为含完整条件），7 条 PASS 保持绿。
- `WrapperLocatorTest` / `WrapperAnchorDetector*Test` / `CustomWrapperServicePreviewTest` / `WrapperTypeSuperChainTest` / `QueryWrapperParserTest` 全绿（无回归）。
- demo 项目 `custom-wrapper-demo` 七形态在 runIde 沙盒逐个点验，预览 SQL 条件完整。
- **反向验证**：任取一条 REPRO 用例，临时关掉对应修复分支，必须变红，贴两次读数。
- **最坏面专项**：确认 R10 形态在修复前/后各拍一次产物（`SELECT ... FROM ... [无 WHERE]` vs `... WHERE ...`），作为「静默错误 SQL 已消除」的证据。
