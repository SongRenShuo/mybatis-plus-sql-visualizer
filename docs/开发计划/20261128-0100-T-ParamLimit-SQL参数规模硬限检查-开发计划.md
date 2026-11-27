# SQL 参数规模硬限检查（T-ParamLimit）开发计划

- **编号**：本册新占 **D272**（`20261127-2045-第四十六轮全景审计与持续演进` 册声明「下一全新 D271」，本批姊妹册 `T-SelChainUnparsed` 占 D271，故本册取 D272；主控逐号 grep 复核 D272 在 docs/ 零占用）。下一全新 **D273 / V254 / O-170**。
- **立项**：2026-11-28，源自《第四十六轮全景审计》报告 §7.3.2 车道 E 补充候选第 2 项。
- **性质**：实施性计划。**代码改动由子代理在独立分支 `t-param-limit` 上完成后台实施，不合并 master**。
- **基线**：master `f92ca134d`。

---

## 一、问题：数据库有硬上限，我方零检测

### 1.1 事实（代码实证）

`grep -rn "ORA-01795\|01795\|2100" src/main/kotlin` → **零命中**。我方对任何数据库的参数规模上限**完全没有检测**。

而这是信创迁移与 Oracle 系项目的必踩点：

| 数据库 | 硬限 | 后果 |
|---|---|---|
| **Oracle / 达梦 / 人大金仓（Oracle 兼容模式）** | `IN` 列表**最多 1000 个**字面量（`ORA-01795`）；绑定变量上限 **65535**；早期 `IN` 绑参数受 2100 限制 | 超限 → **运行时直接报错**，不是慢 |
| SQL Server | 单语句参数 **2100** 上限 | 同上 |
| PostgreSQL | 单语句参数 **65535** | 同上 |
| MySQL | 受 `max_allowed_packet` 与 `PreparedStatement` 实现约束（无明确参数个数硬限） | 视驱动 |

### 1.2 为什么值得做（三条同时成立）

1. **零命中 = 真实缺口**，不是「可能已覆盖」。
2. **「一眼看不出、上线才炸」**：1001 个 id 的 `IN` 查询在开发环境跑不出问题（数据少），生产批量时才炸。这类缺陷用户无法靠 review 发现。
3. **纯静态可判定**：`IN (?, ?, …)` 的参数个数在渲染产物里是**确定的**，不需要连库、不需要 AST（虽然有 AST 更准）。

### 1.3 与已有护栏的关系

我方已有 `sql/perf/rules/` 4 批 21 条规则，含 `dialect_quote_mismatch` 这条**方言感知**规则（`PerfRulesBatch4`）。参数硬限是同一族问题的自然延伸——**且能复用整套「方言感知规则」范式**。

---

## 二、方案：新增一条 `DialectAwarePerfRule`

### 2.1 现成模板（`PerfRulesBatch4.DialectQuoteMismatchRule`）

该规则已给出完整范式，本册直接照抄结构：

| 环节 | 模板做法 | 本册沿用 |
|---|---|---|
| 接口 | `DialectAwarePerfRule`（`check(ctx: PerfContext)`） | ✅ |
| 无方言上下文 | `check(sql, ast)` 返回 `emptyList()`（宁可不报） | ✅ |
| 判定路径 | AST 优先，`null` 时降级正则 | ✅ |
| 严重度分档 | 无歧义 → ERROR；有前提 → WARN | ✅ |
| 文案 | `messageKey` + `messageArgs` 走 bundle 双语 | ✅ |
| 接入 | 加进 `PerfRulesBatch4.all`（或新建 Batch5） | ✅ |

### 2.2 判定逻辑

**核心是「数出这条 SQL 里最大的单个 IN 列表有多少个参数」，与目标方言的上限比对。**

**AST 路径（首选）**：用 JSqlParser 的 `InExpression`（或 `ExpressionList`）遍历，取每个 `IN (...)` 的右值列表长度，求最大值。

- 优点：**不会把 `IN (SELECT …)` 当参数列表**（那是子查询，不受 1000 限制）
- 优点：字符串字面量里的 `IN (1,2,3)` 不会误伤

**降级路径（AST 为 null）**：正则扫描 `IN\s*\(([^)]*)\)`，按逗号计数。

- **必须先剥离单引号字符串字面量与注释**（照抄 `DialectQuoteMismatchRule` 的 `collectIdentifiersFromText` 前置处理）
- **必须排除 `IN (SELECT`**（子查询）
- 降级档**只报 WARN 不报 ERROR**（正则数不准，误报成本高）

### 2.3 严重度分档（照抄模板的思路）

| 情形 | 严重度 | 理由 |
|---|---|---|
| AST 路径 + 目标方言有明确硬限 + 超出 | **ERROR** | 确定会运行时失败 |
| AST 路径 + 目标方言硬限不确定（如 MySQL） | **不报** | 宁可不报 |
| 降级路径（正则）+ 超出 | **WARN** | 正则可能数错 |
| 无方言上下文 | **不报** | 与模板口径一致 |

### 2.4 方言上限数据落哪

**不塞进 `Dialect` 接口**。理由：`Dialect.kt` 已有 1530 行，且这个上限是**规则私有的知识**，不是渲染差异。

建议放在规则文件内一张私有表。**⚠️ 方言名必须用 `Dialect.name` 的真实字面量**（主控本轮已实测，见下），不能想当然写短名：

| 方言 | `Dialect.name` 真实字面量 | `IN` 列表硬限 |
|---|---|---|
| Oracle | `Oracle` | 1000 |
| 达梦 | **`DM（达梦）`**（含全角括号） | 1000（待真机确认，见 §6.3） |
| 人大金仓 | **`KingBase（人大金仓）`**（含全角括号） | 1000（同上） |
| SQL Server | `SQL Server`（中间是空格） | 2100 |
| PostgreSQL | `PostgreSQL` | 65535 |
| openGauss | `openGauss` | 65535 |
| GaussDB | `GaussDB` | 65535 |

**其余 8 个方言名称（`MySQL` / `MariaDB` / `OceanBase` / `H2` / `SQLite` / `GoldenDB` / `HighGo` / `Vastbase`）无明确 `IN` 列表硬限，不入表。**

```kotlin
/**
 * 各方言 IN 列表参数硬限（null / 不在表 = 无明确硬限，不报）。
 *
 * ⚠️ key 必须是 Dialect.name 的真实字面量——达梦与人大金仓带全角括号，
 * 写成 "DM" / "KingBase" 会静默失效（规则存在但永不触发，且测试若同样写错仍会绿）。
 * 由 ParamLimitDialectNameTest 从 DialectRegistry.dialects 取真名钉住。
 */
private val IN_LIST_LIMIT: Map<String, Int> = mapOf(
    "Oracle" to 1000,
    "DM（达梦）" to 1000,
    "KingBase（人大金仓）" to 1000,
    "SQL Server" to 2100,
    "PostgreSQL" to 65535,
    "openGauss" to 65535,
    "GaussDB" to 65535,
)
```

**方言名以 `Dialect.name` 为准**（`DialectRegistry.dialects` 的 15 项）。**S2 的断言测试必须从 `DialectRegistry.dialects` 遍历取 `it.name` 去比对本表 key，不能手写字符串**——否则表里写错了测试也跟着错，形成空绿。

### 2.5 文案

```
perf.param_limit_exceeded        （英）/ 参数规模超限（中）
"IN list has {0} parameters, exceeds {1}'s limit of {2}"
```
带三层信息：实际个数、目标方言、该方言上限。**只报「超限」不说数字的警示没有行动价值。**

---

## 三、必须守住的三条

1. **无方言上下文一律不报**。与 `DialectQuoteMismatchRule` 口径一致——宁可不报也不误报。
2. **不碰 SQL 文本**。只报告不改写（同 `DialectQuoteMismatchRule` 的设计理由：改写会让预览偏离真机）。
3. **规则内部异常必须捕获返回空列表**（`PerfRule` 接口契约明写「内部异常必须捕获并返回空列表，不得上抛（规则隔离）」），执行耗时 < 5ms。

---

## 四、实施步骤（子代理在 `t-param-limit` 分支做）

| 步 | 内容 | 判据 |
|---|---|---|
| S1 | 读 `PerfRulesBatch4.DialectQuoteMismatchRule` 全文 + `PerfContext` 定义 | 确认 `ansiQuotes` 之类字段是否够用，是否需要给 `PerfContext` 加字段（**尽量不加**） |
| S2 | 核对 `Dialect.name` 的 15 个字面量，与 `IN_LIST_LIMIT` 表的 key 逐个比对 | **零不匹配**（写个断言测试钉住） |
| S3 | 实现规则（AST 路径 + 降级路径 + 分档） | 编译过；纯函数 |
| S4 | 加进 `PerfRulesBatchX.all`；bundle 双语文案 | 中英键数相等 |
| S5 | **反向验证**：Oracle 方言 + 1001 个 `IN` 参数 → 必报 ERROR；1000 个 → 不报；PG 方言 + 1001 个 → 不报（未超 65535） | 移除此规则 → 必须变红 |
| S6 | 降级路径验证：`ast = null` + 1001 个参数 → 报 WARN 而非 ERROR；`IN (SELECT …)` → 不报 | 三条断言 |

**S2 与 S5 是准入门槛**：S2 防「名称对不上静默失效」，S5 防「规则存在但不触发」。

---

## 五、明确不做

| 项 | 理由 |
|---|---|
| 绑定变量总数上限（65535）检查 | 需要数全语句所有 `?`，与 `IN` 列表是两个维度。**本册只做 `IN` 列表**，另一维度另立 |
| MySQL 的 `max_allowed_packet` 检查 | 需要知道包大小与参数值长度，静态不可判定 |
| 自动改写 SQL（拆批 / 改 `IN` 为 `OR`） | 与 `DialectQuoteMismatchRule` 同口径：**只报告不改写**，改写会偏离真机 |
| 塞进 `Dialect` 接口 | 见 §2.4——这是规则私有知识，不是渲染差异 |

---

## 六、待验证前提（实施者先证伪再动手）

**已由主控实测确认的两条（不是待验证，是已知事实）**：

1. **`Dialect.name` 的精确字面量**（见 §2.4 表）：达梦是 `DM（达梦）`、人大金仓是 `KingBase（人大金仓）`，**都带全角括号**；SQL Server 中间是空格。**这是本册最容易出错的地方**——主控写本计划第一版就写成了短名 `"DM"`/`"KingBase"`，那样规则会永不触发且测试若同样写错仍会绿（空绿）。→ S2 的断言测试**必须从 `DialectRegistry.dialects` 遍历取 `it.name`** 去比对本表 key。
2. **`PerfContext` 字段**：`data class PerfContext(sql: String, ast: Statement?, dialect: Dialect?, ansiQuotes: Boolean? = null)` —— **携带的是 `Dialect` 对象不是名字**，故 §2.4 的表用 `ctx.dialect.name` 取值。**不需要给 `PerfContext` 加字段。**

**仍需实施者先证伪的四条**：

3. **JSqlParser 的 `IN` 节点形态未验证**：`InExpression` 在 5.3 里对 `IN (1,2,3)`、`IN (?,?,?)`、`IN (SELECT …)`、`NOT IN` 的 AST 形态各异。**实施前必须写探针确认**——本轮 SQL Transpiler 那一路已证明「源码推理不够，必须探针实测」（我读源码判定的「按方言解析」路径，被探针实测的 7 种 profile 全 PARSE-OK 直接否证）。
4. **达梦/金仓的 1000 限制是否与 Oracle 完全一致未取证**。它们是 Oracle 兼容模式，但具体版本可能有差异。**保守做法**：先只对 `Oracle` 报 ERROR，`DM（达梦）` / `KingBase（人大金仓）` 降为 WARN（文案带「该方言限制未真机验证」），等真机读数再升档。
5. **已有 21 条规则是否有 `ruleId` 撞车**。新 ID 需全仓 `grep` 唯一。
6. **新增规则会打破「规则条数」断言**：现有性能测试里若有 `assertEquals(N, rules.size)` 之类的计数断言，加规则后会红。**这是本册唯一可能撞到既有测试的地方**，实施前先 grep 一遍并在同批更新。

---

## 七、预计工作量与风险

- **复杂度：S**（一条规则 + 一张表 + 双语文案 + 反向验证；若 §6 第 2/3 条前提需要大量探针取证则升 M）
- **风险：低**。纯新增一条规则，不改任何既有逻辑；最坏情况是「规则不触发」（静默），不会破坏现有功能。
- **回归面**：仅 `PerfRulesBatchX.all` 列表 + bundle。**但注意**：新增规则会让所有已有性能测试的「规则条数」断言变化，需同批更新（这是唯一可能撞的地方）。

---

## 八、编号台账

本册占 **D272**。姊妹册 `T-SelChainUnparsed` 占 **D271**。下一全新 **D273 / V254 / O-170**。

---

## 九、实施记录

- 2026-11-28 立项（主控）。代码实施由子代理在独立分支 `t-param-limit` + 独立工作树完成，**不合并 master**。
- 2026-11-28 实施完成（`f999a0daa` 规则 + 接入 + 文案；`1c2790e32` 接入链路测试 + CHANGELOG）。分支 `t-d272-paramlimit`（工作树 `mpvs-d272`），基线 `846e416b1`。

### 9.1 探针读数（§六.3 第一道门，JSqlParser 5.3 实测）

对 `InExpression.rightExpression` 逐形态实测，**推翻了一条仅靠源码推理会踩的坑**：

| SQL 形态 | `rightExpression` 实际类型 | 列表在哪 |
|---|---|---|
| `id IN (1,2,3)` | `ParenthesedExpressionList`（size=3） | 就是右值 |
| `id IN (?,?,?)` | `ParenthesedExpressionList`（size=3） | 就是右值 |
| `id IN (SELECT …)` | `ParenthesedSelect` | **无列表**（子查询） |
| `id NOT IN (1,2)` | `ParenthesedExpressionList` | 就是右值 |
| **`id IN (?,?,?) AND deleted = 0`** | **`AndExpression`** | **藏在左分支** |
| `a = 1 AND id IN (…)` | `ParenthesedExpressionList` | 就是右值（IN 在右） |
| `id IN (…) OR a = 1` | `OrExpression` | 藏在左分支 |
| `(a,b) IN ((1,2),(3,4))` | `ParenthesedExpressionList` | 就是右值（行值） |
| `IN` 在 EXISTS/HAVING/CASE WHEN/子查询内 | 同正常形态 | TablesNamesFinder 能遍历到 |

**关键结论**：只判 `right is ExpressionList` 会让「`IN (...) AND 其它条件`」这个**最常见的真实批量删除/查询形态静默漏报**——而这类形态恰恰是最可能超 1000 项的。实现里沿 `AND`/`OR` **向左**解包到列表（语法上 IN 的列表必然在段的最左，右侧全是后续谓词）。

另两条实测：`'id IN (1,2,3)'` 字符串字面量在 AST 里不是节点（inCount=0，天然不误伤）；`IN ()` 空列表解析成 size=0 的列表（不构成超限）。

### 9.2 方言名对账（§四 S2）

`IN_LIST_LIMIT` 7 个 key 从 `DialectRegistry.dialects.map { it.name }` 遍历比对，**零不匹配**。达梦与金仓按 `Dialect.name` 取回条目，实测字面量确为 `DM（达梦）` / `KingBase（人大金仓）`（带全角括号）；SQL Server 为 `SQL Server`（中间空格）。测试 `S2_限值表的方言名全部来自DialectRegistry的真实name` 与 `S2_达梦与金仓的key带全角括号且能按名取回同一条目` 钉住，不手写字面量。

### 9.3 反向验证双读数（§四 S5）

- **规则失效态**（临时把 `check(ctx)` 首行改成 `if (true) return emptyList()`，模拟「规则存在但永不触发」的空绿形态）：`PerfRulesBatch5Test` **39 例中 16 例红**（S5 三档、保守档、AST 形态、S6 降级、规则条数、文案三层信息均红）。
- **恢复后**：同套件 **39 例全绿**（后续补接入测试后 42 例全绿）。
- **接入链路反向**（临时注释 `SqlPerfAnalyzer` 的 `registerAll(PerfRulesBatch5.all)`）：两条接入测试红（`接入_经SqlPerfAnalyzer_Oracle超限能命中`、`接入_经SqlPerfAnalyzer_方言不同结论不同_缓存不串味`），其余仍绿——证明这两条钉的是「引擎能触达」而不是规则内部。恢复后全绿。

### 9.4 S6 降级路径三条断言

| 断言 | 读数 |
|---|---|
| `ast = null` + 1001 项 → **WARN**（非 ERROR） | 绿，`messageKey = perf.param_limit_exceeded_regex` |
| `ast = null` + `IN (SELECT …)` → **不报** | 绿 |
| `ast = null` + 1000 项 → 不报 | 绿 |

另加三条降级路径的字面量/行注释/块注释误伤防护，与带 AND 尾巴的计数，均绿。

### 9.5 与 §六 遗留项的处置

- **§六.3**（IN 节点形态）：已探针取值，结论见 9.1。
- **§六.4**（达梦/金仓保守）：按计划降为 WARN，文案键 `perf.param_limit_exceeded_unverified` 自曝「未在真实实例上验证」。等真机读数再升档。
- **§六.5**（ruleId 撞车）：`param_limit_exceeded` 全仓唯一；既有 21 条中最近邻是 `in_clause_overflow`（100 项阈值、无方言上下文、WARN），两者不重叠——后者管「经验上偏大」，本册管「方言硬限会炸」。
- **§六.6**（规则条数断言）：仓内无 `assertEquals(N, PerfRuleEngine.ruleCount())` 类断言；仅有 `PerfRulesBatch1.all.size == 6` 与 `PerfRulesBatch2.all.size == 8` 两处**按批**计数，新规则进独立的 `PerfRulesBatch5`，两处零改动。`SqlPerfAnalyzer` / `SqlVisualizerPerfAdviceTool` 注释里的「21 条」同批改为「22 条」。

### 9.6 未做（如实登记）

- **未跑全量**、未跑 `testShards`（按纪律只跑定向：`sql.perf.*` + `SqlPerfAnalyzerTest` + `PerfAdviceToolTest`，138 例全绿）。
- **未做真机验证**：达梦 / 金仓 / Oracle / SQL Server / PG 的 1000 / 2100 / 65535 各档限制均采自信方文档与同类工具共识，**没有一份本次实测读数**——这也是达梦与金仓只报 WARN 的直接原因。
- 未做 `verifyGate` / `verifyPlugin` / `buildPlugin`（无 `plugin.xml` EP 变更、无依赖版本变动、无新平台 API 面）。
- 绑定变量总数 65535 维度、MySQL `max_allowed_packet` 维度按 §五 明确不做。
