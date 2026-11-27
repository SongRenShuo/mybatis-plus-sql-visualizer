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

> **R7 的断言口径错了（车道 C 实施期实测推翻，我认的错）**：我最初写「R7 期望 `[eq, ne]`」是把两个语义层混在一起看了。`and(x -> {...})` 的块内条件按既有契约落在 `and` 的 `NestedArg` 里，**外层链只有 `[eq, and]`**——证据是同族既有用例 `WrapperLocatorTest.testAndOrNestedLambda:152` 断言外层为 `["eq","and","orderByAsc"]`。所以：
> - 修复前的真实读数不是「块内 `ne` 丢了」，而是 `NestedArg` 里 `[ne, trim]`——**`ne` 本来就在，`trim` 才是脏项**。
> - 真实缺陷是**一个方向的**：非 wrapper 调用被无差别收进链（`trim`），撞上 `select`/`last`/`apply` 这类 MP 条件名时会真的渲染成列/片段。
> - 修正后 R7 改为断言「摊平后的有效条件集 + 最终 SQL」，并新增 R12b 钉住「撞名工具调用不得进链」。
> 这条属于「计划前提被实测推翻」的第四例（前三例：L9 影响面、撞名形态、`selectJoinList` 锚点契约）。

> **⚠ 与 `t-hiddenbug-perf-scan` 的 `9241e0430` 重叠（合并必读）**：该分支上有个早于本轮的同处修复——「and/or 块体 lambda 内嵌套调用泄漏进外层链」，做法是给块体收集补「最近外围 lambda 归属 + 只取链尾」双过滤。**它不在 master 上**，所以车道 C 是基于 master 旧版（无差别收集）实施的。
> 核实结论：**C 的新实现已等价覆盖那两条过滤**（`collectBlockBodyCalls` 里 `child is PsiLambdaExpression -> Unit` 跳过嵌套、`isChainTail` 只取链尾），但语义来源不同——C 是「按形参筛选」的副产品，`9241e0430` 是「按 lambda 归属筛选」的主目标。
> **合并注意事项**：两者不可叠加。若日后把 `t-hiddenbug-perf-scan` 合进来，需以 C 的实现为准并补跑 `9241e0430` 自带的回归用例；若该分支的用例在 C 分支上不绿，说明有 C 未覆盖的形态，必须补。



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

`filterCallsToAnchorSegment` 的语义是「按条件生命周期分段」，本身是对的；问题在于 `w = decorate(w)` 这种**自引用赋值**被当成了「生命周期重置」。

**修法边界（极重要）**：只放行「RHS 里引用了 LHS **同一个变量**」的自引用赋值，**不能放宽到所有赋值**。原因：`PreviewStmtTypeScopeTest` 有两处硬断言钉住了分段语义——
- `:168` 断言 `allConsumptions` 精确等于 `["update","delete"]`；
- `:218` / `:226` 用 `assertFalse` 断言「delete 段不带 status 条件」「声明段不带 order_no 条件」。

若把「所有赋值都不切段」，复用 wrapper 的语料（`w = new LambdaQueryWrapper<>()` 后继续追加）会把两段条件合并，这两条 `assertFalse` 立刻变红，且用户侧会出现**错误 SQL**（DELETE 带上 UPDATE 段的条件）。

同时必须修**跨文件偏移比较**：展开调用的 `textRange.startOffset` 来自 helper 文件，却被拿去与调用方的 `assignmentOffsets` 比大小 → 段过滤结果随机（`WrapperLocator.kt:1049` vs `:1351`）。需引入 `(文件, offset)` 复合键，并保留 `second`（逻辑锚点）到分段之后。


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
| `expandHelperCalls` | 实参型 helper 单层 | 必须改（修 1） | **已修**（车道 A）：递归 + 深度 5 + `PsiMethod` 身份 visited 门 + 别名追踪 |
| `expandFactoryCalls` / `findFactoryReturnChain` | initializer 工厂链 | 必须改（修 2） | **已修**（车道 A）：多 return + 裸变量 return + 深度/环防护 |
| `resolveFactoryArgumentChain` | 工厂方法参数链递归 | 已有递归（`expandFactoryCalls` 自调），但同样无深度/环防护 | **已收口**（车道 A）：depth+1 与 visited 透传给 `expandFactoryCalls` |
| `filterCallsToAnchorSegment` | 重新赋值分段 | 必须改（修 3） | **已修**（车道 A）：自引用赋值不切段 + `ChainCallEntry.orderOffset` 逻辑坐标 |
| `QueryWrapperParser` 未知方法分支（`:252`） | 解析层静默丢弃 | 必须改（修 5，只加可见性） | **未修**（范围外，其他车道）。P5/R13 实测现状：`statusEq` 方法体内的 `eq` 已能落条件，故该形态不丢 |
| `isWrapperFactoryCall` | 工厂调用判定 | 不含工具方法 → R5 伪条件来源 | **未改**（按硬约束 2）。R5 改由 修 4 的 `isPseudoConditionCall` 在收集侧剔除 |
| `collectExtendingChain` | 链式延续收集 | P1 绿，天然正确 | **未改**，回归确认仍绿（P1 `testReproHelperInternalChainedCall` PASS） |
| `resolveNestedLambda` 块体分支（`ArgResolver.kt:181-185`） | 无差别全收 | **实测已红**（`[eq, and]`，内层 `ne` 丢失） | **未修**（`ArgResolver.kt` 属其他车道）。R7 仍红，实测读数不变 |
| `WrapperAnchorDetector` 锚点集合 | gutter 图标 | 锚点侧完全不调 `buildChain` | **未动**（一行未改）；`CustomWrapperServicePreviewTest` 6 例全绿 |
| `SqlPreviewService` 批量路径 | 批量模板 | 不共用本逻辑 | **未动**（范围外）。修 0 的「无 WHERE 拦截」未做 |
| `ArgResolver.toWrapperCall` | 单个调用 → 条件 | 收到非条件方法会静默降级 | **未改**（其他车道）。修 4 已在收集侧拦住伪条件，`toWrapperCall` 不再收到 `buildQuery` |
| `ExpressionEvaluator` 三元/方法调用（`:50`、`:202-214`） | 值级降级为 `?` | 本轮不改 | **未改**，登记为已知 |
| if 分支去重（`WrapperLocator.kt:1116-1122`） | 同 if 只留首个 | 同分支多调用被误杀 | **已修**（车道 A）：`dedupeIfBranches` 按锚点分支选 + 同分支全留 |
| 多个 return（`WrapperLocator.kt:1621`） | 只取第一个 | 分支 return 丢失 | **部分修**（车道 A）：全收 + 文本不一致即放弃；「按锚点可达性选分支」未做 |
| Kotlin 侧（`KtWrapperDetector`） | .kt 文件 | `locateWithTable` 对 kt 直接 return null | **不在本次范围**（未改，`WrapperLocator.kt:81-82` 原样） |
| xbatis / EasyQuery / JPA / Querydsl / MDS | 各自链模型 | 不共用本逻辑 | **范围外**（未改） |


## 六之二、破损风险清单（改这个文件会影响谁）

> **先纠正一条我自己写错的前提**：§六 初稿写「`selectJoinList` 期望只有变量声明 1 个锚点」——**错**。该契约已被 `ab6c7110a`（D256 M2）翻转，实测 `WrapperAnchorDetectorJpaAndWholeFileTest.kt:355-359` 期望 `setOf("wrapper", "selectJoinList")`（2 枚）。做风险评估时若沿用旧契约，会误判「锚点侧天然不受影响」。

### 高

| 风险 | 受影响对象 | 破约方式 |
|---|---|---|
| **递归入口前移破坏类型门** | `WrapperLocatorTest.testPlainMethodChainNotMisdetected`（`:863`，断言 `:877`） | 一旦把递归展开的入口放到 `isWrapperVariable` 类型门**之前**，或改用「实参是引用 / 方法名像 build/get」这类**语法启发**，`helper.applyFilter(w)`（`helper` 不可 resolve、`w` 是 `Object`）就不再返回 null，防误伤契约直接破。**纯 resolve 版递归不破**——这条用例真正钉住的是「展开必须挂在类型门之后」 |
| **候选判定走语法启发** | `WrapperLocatorTest.testSqlCandidatesInRangeCollectsAndDeduplicates`（`:880`，断言 `:911`、顺序 `:913-915`） | 为返回值形态扩展 `isSqlCandidate`（`:156-158`）或 `isMpjJoinConsumerCall`（`:178-192`）时若退化成语法判定，第 4 个调用 `helper.applyFilter(w)` 会被计入 → 恰好 3 变 4；或 `sqlCandidatesInRange` 的 `distinctBy{startOffset}` 被顺手改成路径去重 → 超过 3 |
| **去重结构 + 锚点丢失** | 本任务 R1/R4/R8（`:114-117`、`:153-156`、`:523-526`） | 沿用「`distinctBy(endOffset)` + 排序后 `.map{it.first}` 丢掉 `second`」结构时，**同一调用被多条路径展开会重复/漏/乱序**；且 `second`（逻辑锚点）在 `:1103` 就被丢掉，导致 `filterCallsToAnchorSegment` 拿跨文件偏移去比 |
| **join 消费族锚点集合** | `WrapperAnchorDetectorJpaAndWholeFileTest`（`:322`、`:357`；另有 `:215/:250/:284`） | 任何「顺手统一锚点/让位」的改动都会增删调用行锚点，集合断言是硬约束。**正确做法：只改 `buildChain` 内部，不碰 `WrapperAnchorDetector`**（锚点侧只调 `detectWrapperTypeDeep`/`isWrapperVariable`/`isMpjJoinConsumerCall`，完全不调 `buildChain`） |
| **自引用赋值不切段会破既有分段契约** | `PreviewStmtTypeScopeTest`（`:168` 精确 `["update","delete"]`；`:218`/`:226` 的 `assertFalse`） | 为支持 `w = helper(w)` 而改分段语义时，`:218/:226` 断言「delete 段不带 status / 声明段不带 order_no」会破。**修 3 必须只放行「RHS 引用 LHS 同一变量的自引用赋值」，不能放宽到所有赋值** |

### 中

| 风险 | 受影响对象 | 破约方式 |
|---|---|---|
| 链上多一个调用 | `WrapperLocatorTest` 共 **11 处**精确方法名列表（`:61/:106/:152/:367/:413/:441/:524/:685/:727/:761/:833`） | 任何「额外调用入链」（消费调用、伪工厂名、helper 内非条件调用）都会让列表多一元素 |
| 消费调用被当 helper 展开 | `CustomWrapperServicePreviewTest`（`:101`/`:123` 期望 `["eq","in"]`） | `expandHelperCalls` 的触发条件 `call.argumentList.expressions.any { it === ref }`（`:1092`）**对消费调用同样成立**；若改造把消费调用也入链，2 元素变 3 元素（多出 `selectJoinList`） |
| helper 展开条数/顺序 | `SqlPreviewServiceOfficialApiTest`（`:660`/`:702`/`:1051`） | WHERE 子句文本直接由条数与顺序决定，多重展开会多出 `AND ...` |
| 规则 4 被卷入 | `WrapperAnchorDetectorTest`（8 处集合）、`WrapperAnchorDetectorNegativeTest`（5 处空集） | 若改动碰到 `isWrapperVariable` 规则 4（`ReferencesSearch`+链方法名闭集），负例可能亮图标 |
| 真实 demo 行号普查 | `ui/gutter/DemoMpChainAnchorCensusTest.kt:86` | 锚点面任何漂移都红（且依赖 demo 目录存在） |
| 缓存跨文件失效缺口 | `WrapperCache.kt:76` + `LocatedWrapper.kt:63` | 现无任何用例覆盖，改完不红但**用户可见**：改了工具类文件仍出旧 SQL |

### 低

| 风险 | 说明 |
|---|---|
| `PsiSnapshotTest` / `WrapperCacheTest` / `WrapperCacheLruTest` | 与链展开无耦合（`PsiSnapshotTest:3-10` 根本不 import `WrapperLocator`；缓存用例用合成 compute）。只有改 `WrapperCache` 键/容量（`:101` 钉 512）才会红 |
| `convention` 包守卫 / gutter 热路径守卫 | 扫描面**不含** `WrapperLocator.kt`——不会因递归变红。代价：**性能红线失去自动守卫**，只能靠 review 保证「递归只留在 `buildChain` 的预览路径」 |
| `EdgeCaseTest:100`（1000 次 < 500ms） | 只测 `SqlGenerator`，与收集层无关 |
| `WrapperTypeSuperChainTest:228-272`（深度上限 8） | 仅当复用 `WRAPPER_SUPER_CHAIN_MAX_DEPTH` 作 helper 递归上限时会语义混淆 → **必须新增独立常量**，我已在修 1 里写明用 `WRAPPER_CROSS_METHOD_MAX_DEPTH = 5` |
| 行数预算 | master 无 `GiantFileBudgetTest`，`WrapperLocator.kt` 现 2029 行，新增递归不触发预算门 |

### 最小破约面建议（实施者的硬约束）

1. 递归与返回值形态**只改 `buildChain` 内部**（`expandHelperCalls:966-981`、`expandFactoryCalls:1578-1594`、`findFactoryReturnChain:1619-1624`、`filterCallsToAnchorSegment:1343-1356`），**不碰** `isWrapperVariable` / `isSqlCandidate` / `isMpjJoinConsumerCall` / `WrapperAnchorDetector`。这样能保住 §六之二 里全部锚点类与候选类断言。
2. 去重键换成 **`containingFile` + 调用表达式身份 + 展开路径** 三元组，并**保留 `second`（逻辑锚点）直到 `filterCallsToAnchorSegment` 之后**——否则跨文件坐标错位无解。
3. `expandFactoryCalls → resolveFactoryArgumentChain`（`:1602-1613`）**当前已有自发递归且无深度/环防护**，是一条现成的栈溢出通道，修 1 必须一并收口。

### 性能红线的现状（必须知道）

**没有任何测试断言 `WrapperLocator` 的 resolve 次数/调用次数/耗时。** M10 §2.4 的红线只是文档约束（`docs/开发计划/20260812-1844-代码左侧预览SQL图标-开发计划.md:92-102`），源码侧只有 KDoc 说明（`WrapperAnchorDetector.kt:24-27`、`SqlGutterLineMarkerProvider.kt:34-39`）。

真实的性能风险点是：`isWrapperVariable` 已在 gutter 热路径上被调用 4 处（`WrapperAnchorDetector.kt:113/228/309/335`），其内部 `detectWrapperTypeDeep` 会 resolve、规则 4 会 `ReferencesSearch` + `hasMpCrudReceiver`。**若递归展开被植入这条链而不是只留在 `buildChain`，gutter daemon 成本会结构性上升，且没有任何测试会告诉你。** 这是「只改 `buildChain`」这条约束的真正理由。

### 性能硬约束（用户 2026-11-27 明确要求「不要出现性能问题」）

实施时必须逐条满足，并在报告里给出**定量对照**：

| # | 约束 | 为什么 |
|---|---|---|
| P1 | 递归/ReferencesSearch 新增**只许留在 `buildChain` 预览路径**，绝不可进入 `isWrapperVariable` / `detectWrapperTypeDeep` / `WrapperAnchorDetector` 调用链 | 后者在 gutter 每次重绘的 daemon 里执行 |
| P2 | 深度上限判断必须**早于** resolve：先看 `depth >= MAX` 再决定是否处理节点 | 避免为注定要剪掉的节点付索引代价 |
| P3 | visited 集合必须**早于**任何 resolve 建立 | 避免同一方法被反复 resolve |
| P4 | 去重的语法前门（qualifier 是否裸引用等）必须零成本，**命中后**才 resolve；同一作用域内 resolve 结果需缓存 | 把 O(n × resolve) 拉回 O(n) |
| P5 | 禁止「先 `collectElementsOfType` 全量收集、再逐个 resolve 过滤」的写法 | 递归化后会放大成 O(n²)。现有 `expandHelperCalls:973` 与 `resolveNestedLambda:182` 都是这种写法，改造时必须一并纠正 |
| P6 | `ReferencesSearch` 是索引级操作，是最大风险源：新增前必须论证「无法用 resolve/parent 链替代」 | 索引级操作在 daemon 里代价高 |
| P7 | 报告必须贴整类耗时对照（修复前 vs 修复后） | 性能无自动门禁，只能定量核对 |

**验收阈值**：任一相关测试类整类耗时**上升超过 30%** → 视为引入性能问题，必须优化后重提。

**取舍原则**：若为性能必须收窄覆盖面，**选择「少展开一层」而不是「变慢」**，并在报告里明确说明取舍。



---

## 七、实施顺序建议（风险从低到高）

> **交付纪律（用户 2026-11-27 明确要求）**：本轮所有实施成果**不得合并到 master**。
> 三条车道分支 `t-d264-a` / `t-d264-b` / `t-d264-c` 各自独立提交，收口验证在
> 集成分支 `t-d264-integrate` 上进行（仅验合并可行性，不落地 master）。
> **文档与测试可以进 master**（用户确认），实施代码不进。
> 分支文件归属：A 独占 `WrapperLocator.kt`；B 独占 `BatchOperationConverter` +
> `QueryWrapperParser` + `model/` + `SqlPreviewService`；C 独占 `ArgResolver.kt`。

> **`WrapperChain` 加字段的撞车面（实施前必读）**：全仓 `WrapperChain(...)` 构造点共 **192 处**
> （`grep -rn "WrapperChain(" src/` 计数），main 侧至少 11 处用位置参数构造
> （`DeleteJoinConverter:60`、`LambdaUpdateConverter:42`、`MpjLambdaConverter:93`、
> `MpjQueryConverter:46`、`UpdateJoinConverter:73`、`XbatisConverter:66`、
> `ArgResolver:174/190`、`KtWrapperDetector:86/98`、`WrapperLocator:517`）。
> 它是 `data class`，`equals`/`copy`/`componentN` 被全仓依赖。**加字段必须带默认值且置于参数列表末尾**；
> 更优先的做法是**不动它**——修 5 的 `unknownMethods` 若能通过 `PreviewResult` 或
> `SqlPreviewService` 侧独立扫描透出，就不必碰这个模型。

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
| `(文件, offset)` 复合去重/分段键 | 跨文件展开后 `distinctBy(endOffset)` 与 `startOffset` 比较全部失效，条件会在去重/分段两处悄悄消失。**且 `second`（逻辑锚点）在 `:1103` 就被 `.map{it.first}` 丢掉**，必须保留到分段之后 | `WrapperLocator.kt:1101`、`:496`、`:609`、`:1103`、`:1049` vs `:1351` |
| `WrapperCache` 失效维度加 helper 文件 | 缓存键只有锚点文件的 `modificationStamp`，改了工具类仍出旧 SQL；用户会以为修复没生效。现无任何用例覆盖，改完不红但用户可见 | `WrapperCache.kt:76`；可仿 `LocatedWrapper.kt:63` 的单文件校验扩成多文件 |
| 入口识别四处对齐 | 若新增「工具方法返回 wrapper = 链首」，`isChainHeadCall`/`isSqlCandidate`/`initializerHeadIsWrapper`/`hasWrapperArgument` 必须一起认，否则「能预览没图标」或「图标点开走另一条路」。⚠ 但这四处一动就会破 `testPlainMethodChainNotMisdetected` 与 `testSqlCandidatesInRangeCollectsAndDeduplicates`（见 §六之二 高区）——**若本轮不做修 2，就别动这四处** | `WrapperAnchorDetector.kt:131-135`、`:156-158`、`:268-285`、`:300-315` |

> **第六条硬约束（来自第四轮破约核查）**：递归与返回值形态**只改 `buildChain` 内部**。`isWrapperVariable` 已在 gutter 热路径被调用 4 处（`WrapperAnchorDetector.kt:113/228/309/335`），且**没有任何测试会告诉你它的成本上升了**——因为 `convention` 包下所有热路径守卫的扫描面都不含 `WrapperLocator.kt`。

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
| 第 4 轮（破约风险） | 11 处精确方法名列表、候选数断言、锚点集合断言、热路径守卫扫描面不含 `WrapperLocator` | **纠正了我 §六 初稿的一处错**：`selectJoinList` 锚点契约不是 1 枚，实测 `WrapperAnchorDetectorJpaAndWholeFileTest.kt:355-359` 期望 `setOf("wrapper","selectJoinList")`；另确认 `PreviewStmtTypeScopeTest:168/218/226` 会因「放宽分段」而红 |

### 修复阶段

#### 车道 C：修 7（L11 / R7）嵌套 lambda 代码块体收集 —— 已完成

范围：只改 `psi/ArgResolver.kt` 的 `resolveNestedLambda`（未动 `WrapperLocator.kt` /
`BatchOperationConverter.kt` / `QueryWrapperParser.kt` / `SqlPreviewService.kt`；
`collectCallChain` / `collectChainFromInitializer` / `toWrapperCall` 对外语义未变）。

**实测推翻的一条计划前提（重要，影响 R7 的验收口径）**

计划 §三修 7 与 §二 L11 都写「块内 `x.ne(...)` 的语义没落成条件，实测只剩 `[eq, and]`」。
实施前用临时探针逐层 dump 证明**这条不成立**：

| 层 | 实测（修复前） |
|---|---|
| 外层链 | `[eq, and]` —— `and` 是分组调用，块内条件按设计在它的 `NestedArg` 里 |
| `and` 的嵌套链 | `[ne, trim]` —— **`ne` 已经在，`trim` 是混进来的脏项** |
| 解析产物 | `Group(AND, [Simple(status <> 1, op=<>)])` —— `ne` **确实**落成了条件 |
| SQL 产物 | `WHERE id = 1 AND (status <> 1)` —— 条件完整 |

结论：R7 的 `[eq, and]` 不是「`ne` 丢失」，而是**外层链按设计不含嵌套条件**（同族契约见
`WrapperLocatorTest.testAndOrNestedLambda:124` 期望外层 `[eq, and, orderByAsc]`）——
R7 原断言 `callsAtConsumer().containsAll(["eq","ne"])` **测错了层**，永远不可能在外层链上成立。
真实缺陷只有一半：**非 wrapper 调用被无差别收进嵌套链**。

**修法（§性能硬约束 P1~P7 逐条落地）**

1. 单遍 PSI 子节点遍历 `collectBlockBodyCalls`，边遍历边筛，不做「全量收集 → 逐个 resolve → 过滤」（P1）。
2. 零成本语法前门 `isParamReference`：qualifier 必须是**裸** `PsiReferenceExpression`
   （带 `this.`/`holder.` 的引用直接否，**不 resolve**），且 `referenceName` 命中本 lambda 形参名
   或直接别名（**字符串比较**，无索引查询）；不命中即返回，**不触发任何 resolve**（P1）。
   效果：`trim()`（无 qualifier）与 `log.info(...)`（名字 `log` 非形参）零 resolve 被挡。
3. resolve 结论按形参名缓存 `HashMap<String, Boolean>`，同一 lambda 内一个名字最多 resolve 一次（P2）。
4. 深度防护 `NESTED_LAMBDA_MAX_DEPTH = 5` 在遍历入口先判（`lambdaAncestorDepth`），
   **早于任何 resolve**；超限直接返回空嵌套链（P4）。
5. 遍历时**整棵跳过嵌套 lambda 子树**（其条件由 `resolveNestedLambda` 递归为独立 `NestedArg`），
   避免重复解析与重复计条件（P4）。
6. 未新增 `ReferencesSearch`（P5）。只取链尾再 `collectCallChain` 回溯整链，链中段不重复计。
7. 形参直接别名（`Wrapper t = x;`）为纯语法判定（右值是裸引用且名命中形参名），不入 resolve 热路径。

**定向读数**

| 项 | 修复前 | 修复后 |
|---|---|---|
| R7 摊平后方法名 | `[eq, and, ne, trim]`（trim 在外层链**和**嵌套链各一份） | `[eq, and, ne]`（trim 不在任何层） |
| R7 SQL 产物 | `WHERE id = 1 AND (status <> 1)` | `WHERE id = 1 AND (status <> 1)`（不变，本就完整） |
| 撞名形态 `select("status")` | 进链 → 被当列选择渲染 | 不进链（语法前门挡掉） |
| `WrapperLocatorTest` | 31 例 0 失败 | 31 例 0 失败 |
| `SqlPreviewSpecMatrixTest` | 50 例 0 失败 | 50 例 0 失败 |
| `WrapperToolClassReproTest` | 16 例 9 失败 | 17 例 8 失败（R7 转绿；新增 12b 绿；剩 8 例属车道 A/B 范围） |

整类耗时（同机跑两遍取第二遍，`-Pkotlin.compiler.execution.strategy=in-process`）：

| 类 | 修复前 | 修复后 |
|---|---|---|
| `WrapperToolClassReproTest` | 10.594s / 16 例 | 10.278s / 17 例 |
| `SqlPreviewSpecMatrixTest` | 8.801s / 50 例 | 9.398s / 50 例 |
| `WrapperLocatorTest` | —（未单记） | 5.182s / 31 例 |

耗时差（+0.6s，同批 98 例整体口径）在噪声量级：断言类本身不触发新的 resolve，
新增护栏用例只是多跑一次既有链路；后续如需严格证明，可用 `WrapperLocator` 的
resolve 计数插桩对比，但本轮未做（无现成装置，见 §六之二「性能红线现状」）。

**反向验证（两次，各自贴读数）**

1. 恢复无差别收集 → 临时哨兵 `nested=[ne, trim, like]`，断言 `[ne, like]` **变红**；
   还原修复后 `nested=[ne, like]` **转绿**。
2. 恢复无差别收集 → `WrapperToolClassReproTest` 17 例 **10 失败**（R7 与新增 12b 同时变红）：
   - `testReproNestedLambdaBlockWithHelperCall`：`[eq, and, ne, trim]`，断言 trim 不在链上 → 红
   - `testReproNestedLambdaBlockCallCollidingWithMpMethod`：`[eq, and, ne, select]` → 红
   还原修复后回到 17 例 8 失败（且 8 例全是车道 A/B 范围的跨方法形态）。

**新增护栏用例（放在 `WrapperToolClassReproTest`，无临时探针残留）**

- `testReproNestedLambdaBlockWithHelperCall`（改写口径）：摊平嵌套链后断言、并断言 SQL 含 `status <> 1`。
  配套新增 `flattenedCallsAtConsumer()` / `flatten()` 装置——只看外层链测不出块体收集完整性。
- `testReproNestedLambdaBlockCallCollidingWithMpMethod`（新增）：块内非 wrapper 调用撞 MP 条件方法名
  `select("status")`——这是无差别收集**最坏**的一面（被误收的调用真的被渲染成列），修复前必红。

**同类排查表（§六）本车道条目结论**

| 入口 | 结论 |
|---|---|
| `resolveNestedLambda` 块体分支 | **已修**（本次） |
| `collectCallChain` / `collectChainFromInitializer` / `toWrapperCall` | **必须不改**（被 `WrapperLocator` 共用），本次确未改 |
| `WrapperLocator` / `BatchOperationConverter` / `QueryWrapperParser` / `SqlPreviewService` | 车道 A/B 独占，本车道**未触碰** |
#### 车道 A（D264-A，分支 `t-d264-a`，基线 `d857d066a`，提交 `f058c285a`）

范围：只改 `psi/WrapperLocator.kt`（+ `WrapperToolClassReproTest` 加一条护栏用例）。实施 修 1/2/3/4/6。

| # | 落地内容 | 关键实现 |
|---|---|---|
| 修 1 | `expandHelperCalls` 递归化 | 新增 `WRAPPER_CROSS_METHOD_MAX_DEPTH = 5`（独立常量）；`collectWrapperFlow` 用 `visitedMethods: MutableSet<PsiMethod>` + `if (!visitedMethods.add(method)) return` 作门，**先判深度上限再 resolve**；`directAliasesOf` 追 `target = w` 直接别名（只一层）；去重键 `Triple(containingFile, startOffset, endOffset)` |
| 修 2 | 返回值形态 | `findFactoryReturnChain` 改多 return + `collectReturnChain` 支持 `return q` 裸变量（`ReferencesSearch` 仅此形态触发）；多 return 按方法名序列比对，不一致即放弃；排除嵌套 lambda/内部类 return；`isWrapperReturningToolCall` 按**返回类型**认伪工厂链首（R6）；`expandFactoryCalls`/`resolveFactoryArgumentChain` 补 depth+visited |
| 修 3 | 自引用不切段 | `assignmentRhsReferences` 只放行 RHS 引用 LHS 同一变量的赋值；新增 `ChainCallEntry(call, orderOffset, subOrder, path)`，分段一律用 `orderOffset`（锚点文件逻辑坐标），不再拿 helper 文件 `textRange` 与调用方 assignmentOffsets 比大小 |
| 修 4 | 伪条件守卫 | `isPseudoConditionCall`：不在 `CONDITION_CHAIN_METHOD_NAMES` / `JOIN_CHAIN_METHOD_NAMES` / `wrapperFactoryMethodNames` / `MPJ_STRUCTURE_METHOD_NAMES`，且 qualifier 类型不是 Wrapper → 剔除 + `LOG.debug` 留痕 |
| 修 6 | if 分支 | `dedupeIfBranches` 改为按锚点所在分支选，同分支多调用全保留；锚点不在该 if 内沿用源码序第一分支旧口径 |

定向读数（5 类 78 例，`--tests` 定向，不 clean 不 --stop）：

| 类 | 修复前 | 修复后 |
|---|---|---|
| `WrapperToolClassReproTest` | 16 例 9 红 | 17 例 1 红（新增护栏 1 例绿） |
| `WrapperLocatorTest` | 31 例 0 红 | 31 例 0 红 |
| `WrapperTypeSuperChainTest` | 9 例 0 红 | 9 例 0 红 |
| `CustomWrapperServicePreviewTest` | 6 例 0 红 | 6 例 0 红 |
| `PreviewStmtTypeScopeTest` | 15 例 0 红 | 15 例 0 红 |

车道 A 范围内转绿：**R1**（`testReproNestedHelperTwoLevels`）、**R2**（`testReproHelperDelegatesToAnotherAndReturns`）、**R3**（`testReproHelperLocalAlias`）、**R4**（`testReproHelperReturnsWrapperAndReassigned`）、**R5**（`testReproHelperBuildsAndReturnsWrapper`）、**R6**（`testReproHelperResultConsumedInline`）、**R8**（`testReproOrderDependenceProbe`）、**R10**（`testReproBareVariableReturnProducesConditionlessSql`）。

**未修（不在车道 A 范围）**：R7 `testReproNestedLambdaBlockWithHelperCall`（实测 `[eq, and]`，内层 `ne` 丢）——根因在 `ArgResolver.resolveNestedLambda` 的代码块分支（`ArgResolver.kt:181-185`），属修 7 / 其他车道。

反向验证（两次读数）：

| 关闭的分支 | 结果 |
|---|---|
| 修 3 自引用放行（`assignmentOffsets` 不过滤 selfRef） | `testReproHelperReturnsWrapperAndReassigned` + `testReproOrderDependenceProbe` + R7 变红（17 例 3 红） |
| 修 1 递归分支 ②（`argIndex = -1`） | `testReproNestedHelperTwoLevels` + `testReproHelperDelegatesToAnotherAndReturns` + R7 变红（17 例 3 红） |

两处关掉后均恢复原状并复跑全绿（除 R7）。

性能实测（同机连续两遍，取第二遍；`--rerun-tasks` 保证真跑）：

| 类 | 修复前（基线 `d857d066a`） | 修复后 | 变化 |
|---|---|---|---|
| `WrapperLocatorTest`（31 例） | 6.627 s | 4.959 s | **-25%** |
| `WrapperToolClassReproTest`（17 例） | 15.275 s（含 9 条红例的失败路径） | 13.098 s | **-14%** |

性能口径说明：
- 递归只落在 `buildChain` 预览路径；`expandHelperCalls` / `collectWrapperFlow` / `expandFactoryCalls` / `findFactoryReturnChain` / `isWrapperReturningToolCall` **全部 private**，`isWrapperVariable` / `detectWrapperTypeDeep` / `WrapperAnchorDetector` 一行未动。
- `visitedMethods` 的 `add()` 返回值直接当门，方法体遍历次数上界 = 不同方法数；深度上限在 `resolveMethod()` **之前**判。
- 未新增 `ReferencesSearch` 调用点：`collectReturnChain` 的裸变量分支复用了「变量引用扫描」，仅在 `return q` 形态下触发（原实现在该形态下走的是空链，本来就无产出）。

互递归护栏：新增 `testReproMutualRecursionTerminates`（`a(){w.ne();b(w);} b(){w.isNotNull();a(w);}`），实测 **PASS / 0.218 s**——无防护会栈溢出，有 visited 门后有限步终止。

已知未覆盖（本车道范围外，需其他车道或后续批次）：
- 修 0（批量兜底拦截，`SqlPreviewService` / `BatchOperationConverter`）
- 修 5（`QueryWrapperParser` 未知方法可见性）
- 修 7（`ArgResolver` 嵌套 lambda 代码块体）
- `WrapperCache` 失效维度加 helper 文件（`WrapperCache.kt:76`）
- 入口识别四处对齐（`isChainHeadCall` / `isSqlCandidate` / `initializerHeadIsWrapper` / `hasWrapperArgument`）——按 §七 的提醒，本轮未做修 2 的入口侧扩展即不动这四处
- L15 多 return「按锚点/调用点可达性选」只做到「文本不一致即放弃」，分支标注未做

#### 车道 B（修 0 + 修 5，分支 `t-d264-b`，基线 `d857d066a`）

只改四个文件范围内：`psi/BatchOperationConverter.kt`、`converter/QueryWrapperParser.kt`、
`model/SelectStatement.kt`（`WrapperChain` **未动**）、`ui/SqlPreviewService.kt`。

**修 0（批量兜底拦截 + wrapper 路径零条件守卫）**

| 项 | 落点 | 判据 | 成本 |
|---|---|---|---|
| 批量模板拒绝 | `BatchOperationConverter.isWrapperArgumentUnsupportedBatch`（`convert` 注册表命中后即 return null） | 方法名在 `BATCH_OPERATIONS` ∧ 非 join 族 ∧ 实参有 `WrapperLocator.detectWrapperTypeDeep(expr.type) != null` | 类型级；`detectWrapperTypeDeep` 的名字前门 + 父链类级缓存；**无 `ReferencesSearch`** |
| 预览提示 | `SqlPreviewService.preview` 批量分支前 | 同一判据（单一来源） | 同上，每次预览只对一个调用做（≤实参数个）类型文本判定 |
| wrapper 路径零条件 | `SqlPreviewService.preview` 的 `else -> ConverterRegistry.convert(...)` 之后 | `SelectStatement.conditions.isEmpty() && unknownMethods.isNotEmpty()` | 纯字段读取 |

产物对照（R10 形态，`SELECT id, status, device_name FROM device_info_entity`）：

- 修复前：批量模板接手 → `SELECT id, status, device_name FROM device_info_entity`（**无 WHERE、无提示**）。
- 修复后：`sql = ""` + warning
  「⚠ 检测到 Wrapper 实参，但无法解析其条件链：selectList(...) 的过滤条件（含工具类/跨方法追加的条件）静态不可得。为避免输出**缺少 WHERE 的错误 SQL**，此处不提供批量模板预览。」

**修 5（解析层未知方法可见化）**

- `QueryWrapperParser.parseInternal` 的 `when` 补 `else` 分支：`unknownMethodNames += call.methodName`
  （`LinkedHashSet` 去重；`SILENT_METHOD_NAMES` 白名单排除 MPJ 类型互转/结构方法与写族 set 方法，
  避免噪音）。**每个未识别调用一次集合 add，不额外遍历调用列表。**
- 透出通道：`SelectStatement.unknownMethods: List<String> = emptyList()`（**末尾参数 + 默认值**，
  既有 main 17 / test 93 个构造点零改动；`WrapperChain` 的 data class 契约完全未动）。
- 渲染：`SqlPreviewService.buildWarning` 增加与 `unparsedSets` 同款的红字：
  「⚠ [预览] 有 N 个链方法无法静态解析，其条件未出现在 SQL 中：`statusEq`」
- 条件条数**未改**：`QueryWrapperParserTest.未知方法_安全忽略` 保持绿（仅补一条 `unknownMethods` 断言）。

**契约翻转（1 处，已登记）**

`BatchOperationJoinGuardTest.testSelectListWithKnownWrapperStillProducesSql`（原断言「selectList
不受守卫影响 → 产全列查询」）改为 `testSelectListWithKnownWrapperIsRejectedByBatchTemplate`
（断言 null）。理由：那条断言固化的正是本次要消除的最坏面——批量模板产出无 WHERE 全列 SQL。
另补两条：内联 `new` 形态同样拒绝、非 Wrapper 实参（`Object`）仍走批量模板（正向回归）。

**未做（按范围划归其他车道）**

- 跨方法展开（修 1/2/3/7）：`WrapperLocator.kt` / `ArgResolver.kt` 属车道 A/C，本轮未改。
  因此 R1/R2/R3/R4/R5/R6/R7/R8/R11 九条 REPRO 仍红（它们断言的是「条件被收集到链上」，
  属收集层）；本轮只保证它们**不再产出静默错误 SQL**。
- `WrapperChain` 未加字段（192 个构造点 + 车道 A 在 `WrapperLocator:517` 同点构造，撞车面过大）。

**反向验证（两次，均贴读数）**

修 0：在 `isWrapperArgumentUnsupportedBatch` 首行插 `if (true) return false`（RED PROBE）：

```
BatchOperationJoinGuardTest > testSelectListWithKnownWrapperIsRejectedByBatchTemplate FAILED
BatchOperationJoinGuardTest > testSelectListWithInlineNewWrapperIsRejectedByBatchTemplate FAILED
SqlPreviewServicePreviewTest > testD264WrapperArgButChainUnresolvedRefusesBatchTemplate FAILED
    ComparisonFailure: SQL 必须为空（拒绝产出） expected:<[]> but was:<[SELECT id, status, device_name
    FROM device_info_entity]>
```

第三条即 **R10 的原始产物被原样复现**（`SELECT id, status, device_name FROM device_info_entity`，
无 WHERE），证明该断言真的钉住了这个最坏面；还原后三例复绿。

修 5：把 `else` 分支改成 `if (false && …) unknownMethodNames += …`（RED PROBE）：

```
QueryWrapperParserTest > 未知方法_安全忽略 FAILED
QueryWrapperParserTest > 修5_未知方法名去重收集且不改条件条数 FAILED
SqlPreviewServicePreviewTest > testD264UnknownChainMethodSurfacesInWarning FAILED
SqlPreviewServicePreviewTest > testD264ConditionlessChainWithUnknownMethodRefusesPreview FAILED
50 tests completed, 4 failed
```

正好是「可见性」这一面的四条，其余 46 条不受影响；还原后全绿。

**反向验证复现（第 2 轮实测，独立确认上面两次读数）**

修 0 的 RED PROBE 重跑一次，`29 tests completed, 3 failed`：

```
BatchOperationJoinGuardTest > testSelectListWithKnownWrapperIsRejectedByBatchTemplate FAILED (…:148)
SqlPreviewServicePreviewTest > testD264WrapperArgButChainUnresolvedRefusesBatchTemplate FAILED (…:604)
    ComparisonFailure: SQL 必须为空（拒绝产出）
      expected:<[]> but was:<[SELECT id, status, device_name
    FROM device_info_entity]>
```

第二行即 R10 原始产物逐字复现；探针已移除（`grep "RED PROBE"` 为 0 处），工作树干净。

**收尾复跑未取得读数（如实登记，不当作绿）**

还原探针后的最后一轮同命令复跑，`77 tests completed, 38 failed`，但失败全部由
`com.intellij.util.indexing.StorageException` / `java.io.EOFException` 触发，随后
`NoSuchFileException: build/test-results/test/binary/in-progress-results-generic.bin`——
是另一路会话在同一 build 目录并发跑测试把索引/结果文件踩坏，不是断言失败。
同机无并发压力的那一轮（四类 77 例 / 0 失败 / `BUILD SUCCESSFUL`）是权威读数；
**本车道结论以那一轮为准，最后一轮交回收口阶段在无并发条件下复跑**。

**复查修正（第 2 轮，`9f98d7d9e`）：静默白名单漏了 MPJ 逻辑删除控制与 `on`**

修 5 首版的 `SILENT_METHOD_NAMES` 只枚举了「类型互转 / 列结构 / 联表 / 写族 set」，漏掉两类
**语义已知但不在解析器分派表里**的方法，会把正常预览挂在红字下：

| 遗漏 | 为什么会进 else | 误报后果 |
|---|---|---|
| `disableLogicDel` / `enableLogicDel` / `disableSubLogicDel` / `enableSubLogicDel` / `logicDelToOn` / `logicDelToWhere` | MPJ 官方逻辑删除六方法，语义由 `SqlPreviewService.evaluateLogicDeleteState` 消费（决定 deleted 注入），不是条件方法 | 用户看到「无法静态解析 disableLogicDel」——一条完全正常的 SQL 被标成可能丢条件 |
| `on` | MPJ join 的 ON 构造器，实参在 `MpjLambdaConverter.parseJoin` 按 `OnConditionArg` 解析 | 同上 |

已把七个名字并入白名单，并新增 `testD264LogicDeleteControlMethodsProduceNoUnknownMethodWarning`
钉住（同时断言 `id = 1001` 条件仍正常产出）。同类候选已排查：`selectByMap` / `removeByMap` 等
Map 条件族实参是 `Map` 不是 Wrapper，不进 `hasWrapperTypedArgument`，修 0 不误伤。

**定向读数（同机两遍，取第二遍；白名单补全后的最终一轮，`BUILD SUCCESSFUL`）**

| 测试类 | 例数 | 失败 | 耗时 |
|---|---|---|---|
| `QueryWrapperParserTest` | 30 | 0 | 0.125s |
| `BatchOperationJoinGuardTest` | 8 | 0 | 11.353s |
| `SqlPreviewServicePreviewTest` | 21 | 0 | 3.827s |
| `SqlPreviewServiceLogicDeleteAndChainTest`（白名单反向守卫） | 18 | 0 | 3.949s |

合计 77 例 / 0 失败。

> 首轮（修 5 白名单补全前）曾出现 3 例失败 + `EOFException` / `ServiceNotReadyException`：
> 当时另有 D264-A 会话在同一份 build 目录跑测试，属并发串扰；单独复跑全绿。
> 另有两轮因并发争用失败在构建阶段（`engine-jar/compileKotlin` 产物被删、`prepareTestSandbox`
> 拷贝 `panweidb-jdbc-2.0.0.jar` 撞文件锁），均与代码无关，重试即过。如实登记，不当作绿。

### 教训预登记

- **用例首版三处假红**：跨包 `addClass` 静态导入、`MyWrapper` 未造 MP 基类桩、`addClass` API 在该测试 SDK 不存在——都被我误当成插件缺陷。教训：新语料先确认「装置本身能解析」，否则红的是自己。
- **侦察结论必须用实测校验**：侦察报告断言「`QueryWrapperParser:252` 未知方法必丢」，P5 实测 `statusEq` 能出 `status` 条件——机制对、影响面推断错。已在 §一 就地修正，未删原文。
- **旧契约会被当成前提写进计划**（第四次复发的同类错）：我凭 D256 计划册的旧描述把「`selectJoinList` 只有 1 个锚点」当成现状，实际 `ab6c7110a` 已翻成 2 枚。教训：**引用契约前先读测试断言本身，不读别人（包括过去的自己）的计划册描述**。
- **破约风险清单必须由独立的一路专门核查**：前四轮侦察里，只有专门指派「查破约」的那一轮发现了 11 处精确方法名列表与热路径守卫扫描面的缺口——主线侦察天然倾向于查「缺陷在哪」，不查「改了会破什么」。

---

## 九、验收口径

- `WrapperToolClassReproTest` 16 例：9 条 REPRO 转绿（尤其 **R10 产物级**必须从「无 WHERE」变为含完整条件），7 条 PASS 保持绿。
- `WrapperLocatorTest` / `WrapperAnchorDetector*Test` / `CustomWrapperServicePreviewTest` / `WrapperTypeSuperChainTest` / `QueryWrapperParserTest` 全绿（无回归）。
- demo 项目 `custom-wrapper-demo` 七形态在 runIde 沙盒逐个点验，预览 SQL 条件完整。
- **反向验证**：任取一条 REPRO 用例，临时关掉对应修复分支，必须变红，贴两次读数。
- **最坏面专项**：确认 R10 形态在修复前/后各拍一次产物（`SELECT ... FROM ... [无 WHERE]` vs `... WHERE ...`），作为「静默错误 SQL 已消除」的证据。

---

## 十、集成状态（集成分支 `t-d264-integrate`）

> 本节只记录集成分支的合并事实，`master` 全程未动。

| 时间 | 动作 | 结果 |
|---|---|---|
| 2026-11-27 23:0x | 合入 `t-d264-c`（修 7） | 自动合并，文档无冲突 |
| 2026-11-27 23:1x | 合入 `t-d264-a`（修 1/2/3/4/6） | `WrapperLocator.kt` 与复现测试**自动合并成功**；计划册 §八 文档冲突已手工合并（两段实施记录并存：C 在 :398、A 在 :482） |

**无代码语义冲突**：`collectWrapperFlow`（A，跨方法边界）与 `collectBlockBodyCalls`（C，lambda 内部）分属两个文件、无交叉调用，职责正交。

**合并后待验**：A 的定向读数是「R7 仍红（属 C 范围）」，C 修的正是 R7。合并后预期 17 例全绿，需在集成分支上跑一次定向实测确认——**这是本轮唯一还没验证的合并效果**。

### 集成期发现的 A×B 交互缺陷（本轮最有价值的产出）

三车道各自全绿、代码零冲突合并，但**叠加后产生了一个新的错误 SQL 形态**——只有做集成验证才能发现。

**形态**：`mapper.selectList(util.buildQuery(1))`，且 `buildQuery` 是**无 body 的抽象方法**（条件在实现类里，静态不可得）。

**叠加失配过程**：
1. 车道 A 的修 4（伪条件守卫）把 `buildQuery` 当作「非条件方法」从链上**剔除**；
2. 于是 `WrapperChain.calls` 变空、`SelectStatement.conditions` 空；
3. 车道 B 的修 0 守卫条件是 `conditions.isEmpty() && unknownMethods.isNotEmpty()`——
   而 `unknownMethods` 也空（A 已经把那唯一的方法名剔走了）；
4. 守卫前提消失 → 放行 → 产出 `SELECT id, status, device_name FROM device_info_entity`（**无 WHERE**）。

**为什么单体测试发现不了**：A 的测试里 `buildQuery` 有 body（能被展开），所以链不空；B 的测试里没有修 4，所以 `buildQuery` 留在链上、`unknownMethods` 非空、守卫正常触发。两边各自都绿。

**修法（集成侧补洞）**：`SqlPreviewService` 的零条件守卫加第二条判据 `conditionlessEmptyChain`——
`conditions.isEmpty() && chain.calls.isEmpty() && 消费调用实参是 Wrapper 类型`。
语义：链上空无一物 + 实参仍是 Wrapper = 条件确实全在工具类/被调方法体内，与「有未识别方法」等价危险。
只在该形态触发；`new LambdaQueryWrapper<>()` 这类用户显式不写条件的空链不受影响（其实参是 new 表达式，非方法调用）。

**验证**：
- 新增用例 `testD264WrapperFromBodylessMethodNeverProducesConditionlessSql`（`SqlPreviewServicePreviewTest`）；
- 反向验证：临时关掉 `conditionlessEmptyChain` 判据 → 该用例恰红 1 例、其余 21 例仍绿；恢复后 22 例全绿。

**教训**：多路并行车道的验收不能只看「各自全绿」。本轮的教训是——
**任何涉及同一语义链（收集 → 解析 → 判定）的多处修复，必须在合并后重新验证该链的端点行为**，
因为每处修复都可能改变另一处所依赖的前提。
