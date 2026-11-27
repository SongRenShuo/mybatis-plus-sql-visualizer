# SELECT 链未建模构造留痕（T-SelChainUnparsed）开发计划

- **编号**：本册新占 **D271**（`20261127-2045-第四十六轮全景审计与持续演进` 册 :218 声明「下一全新 D271 / V253 / O-169」，主控本轮逐号 grep 复核 D271 在 docs/ 零占用）。下一全新 **D273 / V254 / O-170**（D272 被同批姊妹册 `T-ParamLimit` 占用）。
- **立项**：2026-11-28，源自《第四十六轮全景审计》报告 §7.3.2 车道 E 补充候选第 1 项。
- **性质**：实施性计划。**代码改动由子代理在独立分支 `t-selchain-guard` 上完成后台实施，不合并 master**（合流等用户点名）。
- **基线**：master `f92ca134d`。

---

## 一、问题：SELECT 链静默吞掉不认识的方法，直接产出错误 SQL

### 1.1 现状（代码实证）

`converter/QueryWrapperParser.kt:252` 的链式方法分发末尾：

```kotlin
// 其他方法（not/嵌套条件等）暂忽略，保证健壮性
```

这是 `when (call.methodName)` 的兜底分支——**任何未在 64 个已识别方法之列的 Wrapper 调用，既不报错也不提示，直接丢弃**。

### 1.2 后果：静默产出少条件的 SQL

用户写：

```java
wrapper.eq("status", 1).allEqStr(map);   // allEqStr 未建模
```

预览得到 `WHERE status = ?` —— **`allEqStr` 的条件整段消失**。用户复制出去执行，命中的行数比预期多得多。这正是《第四十六轮》判定的**第一卸载因「结果不可信」**（B 类 79 条），也是 r45 已明令「静默给出错误结果比不出图标严重得多」的同一类别。

### 1.3 已知的真实缺口清单（不是假设）

`docs/架构设计/MP-MPJ官方API覆盖矩阵.md` 已标注为未支持、且**确实不在** `QueryWrapperParser` 的 64 个已识别方法之列：

| 方法 | 矩阵标注 | 说明 |
|---|---|---|
| **`allEqStr`** | L232 ⬜ | MP 1.5.8 起；`JoinAbstractWrapper`/`JoinAbstractLambdaWrapper` 均有；可复用已有 `MapArg` 模型 |
| `@OrderBy`（MP 注解） | L123 ⬜ | 注解形态，非链式；本册不处理（另立） |
| `select(Class, Predicate)` 谓词 | L42 🟡 | 已识别但只做静态近似；**属「已留痕」范畴，不在本册** |

→ **本册的直接目标不是「补齐 allEqStr」**（那是另一次功能交付），而是**让所有未建模方法都不再静默**。`allEqStr` 作为第一个被照亮的样本。

---

## 二、方案：照抄 UPDATE 链已验证的 `unparsedSets` 护栏

### 2.1 现成参照（这是本册最重要的设计依据）

`LambdaUpdateConverter` 已经解决了同一个问题，形态完整可抄：

| 层 | 落点 | 做法 |
|---|---|---|
| 模型 | `model/UpdateDeleteStatement.kt:25` | `val unparsedSets: List<String> = emptyList()` |
| 收集 | `LambdaUpdateConverter.kt:34/76/89/103` | `unparsedSets += setCallSummary(call)` —— 记**调用原文摘要**而非只记方法名 |
| UI | `ui/SqlPreviewService.kt:1783-1786` | 生成 `⚠ [预览] 有 N 处 SET 片段无法静态解析，已省略：…` |

**为什么不能只记方法名**：`setCallSummary(call)` 记的是 `setSql(sqlVar)` 这种**带实参的完整调用**，用户才能定位是哪一行。只记 `"setSql"` 对多段 SQL 的文件毫无帮助。

### 2.2 本册要做三层

**层 1：模型加字段**
`model/SelectStatement.kt`（74 行起）增加：

```kotlin
/** 解析失败的链式调用原文（T-SelChainUnparsed）：未建模方法静默丢弃会产出少条件的 SQL。 */
val unparsedCalls: List<String> = emptyList(),
```

**层 2：解析器收集而非丢弃**
`QueryWrapperParser.kt:252` 的兜底分支改为收集：

```kotlin
else -> {
    // T-SelChainUnparsed：未建模方法不得静默丢弃——丢弃即产出少条件 SQL。
    unparsedCalls += callSummary(call)
}
```

需要保留的**白名单例外**（这些在 SELECT 链上确实无 SQL 语义，不报）：
- `comment(…)` —— 已识别，走 comment 字段
- 纯链式配置类调用（需逐个枚举确认，**这是本册唯一需要判断的地方**）

**层 3：UI 警示**
`SqlPreviewService.kt` 在 `setWarning` 旁边加同款：

```kotlin
val selectChainWarning = (statement as? SelectStatement)
    ?.takeIf { it.unparsedCalls.isNotEmpty() }
    ?.let {
        "⚠ [预览] 有 ${it.unparsedCalls.size} 处链式方法无法静态解析，已省略：" +
            it.unparsedCalls.joinToString("；")
    }
```

并把 `selectChainWarning` 并入 `listOfNotNull(...)` 的 join。

---

## 三、必须守住的三条

1. **不得改变已识别方法的产出**。仅动兜底分支，64 个已识别方法的解析结果逐字节不变（验收用现有测试 + 一个「全方法快照」对比）。
2. **i18n 双语**。文案进 `MpSqlVisualizerBundle.properties` 与 `_zh`，两 bundle 键数相等（`BundlePlaceholderArityTest` 会拦）。
3. **不得在 EDT 上做重活**。收集与拼接都是内存操作，但 UI 警示拼接在已有的 `buildWarning` 路径里，**不要新增 PSI 访问**。

---

## 四、实施步骤（子代理在 `t-selchain-guard` 分支做）

| 步 | 内容 | 判据 |
|---|---|---|
| S1 | 读 `LambdaUpdateConverter` 的 `setCallSummary` 实现，抽出可复用形态 | 确认能否直接复用还是需要 Select 侧同类 |
| S2 | `SelectStatement` 加 `unparsedCalls` 字段 | 编译过；默认空列表不影响既有构造点 |
| S3 | `QueryWrapperParser` 兜底分支改为收集 | 需先枚举白名单例外（见 §2.2 层 2） |
| S4 | `SqlPreviewService` 加 UI 警示 + bundle 双语 | 中英键数相等 |
| S5 | **反向验证**：造一条 `allEqStr` 语料，断言预览出现警示且 SQL 与「去掉 allEqStr」时一致 | 移除 S3 的收集分支 → 必须变红 |
| S6 | 全方法快照：64 个已识别方法各一条语料，产出与改动前逐字节相同 | 差异为 0 |

**S5 是本册的准入门槛**：没有反向验证的护栏等于没有护栏（r45 教训「守卫/扫描类改动必做反向验证」）。

---

## 五、明确不做

| 项 | 理由 |
|---|---|
| 补 `allEqStr` 的实现 | 那是功能交付，不是护栏。本册只保证「不静默」。补完仍是另一个号 |
| 处理 `@OrderBy` 等注解形态 | SELECT 链之外的问题，另立 |
| 把「已识别但近似」（`select(Predicate)` / `selectFilter`）也报出来 | 它们**已有**留痕（矩阵 🟡 且面板有说明），重复报会造成警示疲劳 |
| 全项目扫描 | r45 红线。本册只处理「用户当前正在预览的这一条链」 |

---

## 六、待验证前提（实施者先证伪再动手）

**已由主控实测确认的两条**：

1. **`setCallSummary` 是 `private`，且已重复两份**：
   - `LambdaUpdateConverter.kt:119` `private fun setCallSummary(call: WrapperCall): String`
   - `UpdateJoinConverter.kt:166` 同名 private 方法（重复实现）

   → **不能直接复用**。这恰好说明本册应顺手把它抽成**共享工具**（如 `converter/CallSummary.kt` 的 `internal fun wrapperCallSummary(call)`），三处（两个 Converter + 新的 Select 侧）共用。**这会让本册从「纯新增」变成「小重构」，但与「消除重复实现」方向一致，符合第四十六轮报告 §5.3 列出的重复实现债。** 若不想扩大范围，也可在 Select 侧写第三份——**但那是主动增加重复，不建议**。

2. **`SelectStatement` 有 17 个显式构造点**（`grep -c "SelectStatement(" src/main`）。加带默认值的字段对这些构造点**全部安全**（Kotlin 命名参数 + 默认值）。但需确认：**没有解构声明**（`val (a, b) = stmt`）与**没有位置参数调用**——若有，加字段会破坏。→ 实施前 `grep "SelectStatement" 的解构用法` 一遍。

**仍需实施者自行判断的三条**：

3. **白名单例外的边界未定**：SELECT 链上哪些未识别调用是「真的无 SQL 语义」（如纯配置类），哪些是「有语义但没建模」。**先枚举后写码**——枚举错会造成两类误报：把纯配置当缺失报（噪音），把真缺失当配置放过（回归）。已知的候选：`comment(…)`（已识别，走 comment 字段）、可能存在的 `last(…)`/`first(…)`（已识别）。

4. **`unparsedCalls` 是否要在「缺片段补齐」链里也透传**：`ui/standalone/MissingSqlFragmentSupport` 那条链有自己的装配点。**本册只做 Wrapper SELECT 链**，若那条链也需要，另开。（先确认 `QueryWrapperParser` 是否被 standalone 复用——若复用则自动覆盖。）

5. **UI 警示位置未定**：`setWarning` 与 `dataPermissionWarning` 共处一个 `joinToString("\n")`。多一条警示是否会让弹窗高度/换行超出预期，需确认字符串拼接无异常（本册不强制 runIde）。

---

## 七、预计工作量与风险

- **复杂度：S**（三层改动 + 一次反向验证）。**若采纳 §6.1 的「抽共享工具」建议，则升 M**（要动两个已有 Converter 的引用点，回归面扩大）。
- **风险：低**。不动已识别逻辑，不动执行链，不改模型语义（只加带默认值的字段）。
- **回归面**：仅 `QueryWrapperParser` 的兜底分支 + `SelectStatement` 构造 + `SqlPreviewService` 警示行。**若抽共享工具**，还要覆盖 `LambdaUpdateConverter` / `UpdateJoinConverter` 的既有测试（它们现有测试应当全绿，因为行为不变）。

**给子代理的建议**：先按「不抽共享工具、Select 侧写第三份」做最小实现拿到绿，**再**决定是否顺手抽取（抽取作为同分支第二个提交，可独立回退）。

---

## 八、编号台账

本册占 **D271**。姊妹册 `T-ParamLimit` 占 **D272**。下一全新 **D273 / V254 / O-170**。

---

## 九、实施记录

- 2026-11-28 立项（主控）。代码实施派子代理在独立分支 `t-selchain-guard` + 独立工作树。

- **2026-11-28 销号：本册目标已被 D264 修5 先行实现，不再另行实施。**

  发现过程：派出实施后核查 master CI，发现 D264 车队提交 `d0f904420 feat(preview): D264 修0 拦住 Wrapper 实参的批量兜底 + **修5 未知链方法可见化**`——**同一目标、同一落点**（`QueryWrapperParser` / `SelectStatement` / `SqlPreviewService` 三处），且其实现含本册 §6.3 要求「先枚举后写码」的白名单（`SILENT_METHOD_NAMES`）。子代理已叫停，**零改动**（HEAD 停在基线，无提交）。

  **本册未覆盖 D264 的差异点（唯一一条，值得记）**：D264 记 `unknownMethods: List<String>`（**只记方法名**、按序去重），本册 §2.1 主张记**带实参的调用原文**。后果：`w.statusEq("1").statusEq("2")` 在 D264 下只产出 `["statusEq"]`，面板显示「1 个链方法」，而实际少了两条条件——**计数与内容都不准**。预览按单条链做，严重度低于 UPDATE 的跨段场景，但这是本册唯一未被覆盖的设计点。

  **D264 修5 的四个残留缺口（本册复核后移交，不属本册范围）**：
  | # | 缺口 | 证据 |
  |---|---|---|
  | B | **子查询链的未知方法全丢**：`QueryWrapperParser.kt:510` `new QueryWrapperParser(subTable, …)` 是独立实例，收集器不互通；`and`/`or` 的递归用的是同一实例故会累积，只有子查询这一处丢失 | 主控复核 `:510` 成立 |
  | C | **UPDATE/DELETE 链未覆盖**：`LambdaUpdateConverter` / `UpdateJoinConverter` / `LambdaDeleteConverter` / `DeleteJoinConverter` 四个转换器**全部零引用** `unknownMethods`；`buildWarning` 守卫是 `(statement as? SelectStatement)`。少条件更新命中行数更多，危险性不亚于 SELECT | 主控 grep 四文件均 0 命中 |
  | D | **白名单按名全局共享致假阴性**：`MpjQueryConverter.kt:110` 的 `JOIN_METHODS` 只有 `leftJoin`/`innerJoin`/`rightJoin`，但白名单含 `fullJoin`/`union`/`unionAll`/`around`/`from`/`fill` → 这些调用真从 SQL 消失却不报警 | 主控复核 `:110` 成立 |
  | E | **按「是否识别」而非「是否解析成功」判定**：`last("AND flag = 1")` 走 `parseLimit` 返回 null 被静默丢弃，既不改 SQL 也不进 `unknownMethods` | 主控复核 `:200`/`:623-642` 成立 |

  缺口 A（六个逻辑删除方法 `disableLogicDel`/`enableLogicDel`/`disableSubLogicDel`/`enableSubLogicDel`/`logicDelToOn`/`logicDelToWhere` 会误报）**D264 车道 B 已自行修复**：`9f98d7d9e fix(parser): D264 修5 补静默白名单——逻辑删除控制与 on 不报假警告`（加六个名字 + `on`）。

  → B/C/D/E 四条已整理为交接清单，交由 D264 车队在其集成分支收口；本册不再占实施坑位。
