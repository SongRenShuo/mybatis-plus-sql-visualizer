# T-RuntimeSlotOptionalFill：可选片段回填错位与幻影 `#param1`（2026-11-07）

- 立项：2026-11-07 08:51
- 状态：**已实施（master）**——R1~R6 落地（`f8adf101`），定向 6 类 **99/0/0/0**，反向验证见 §十二
- 目标分支：`master`
- 来源：用户在沙盒验收 `mybatis-plus-demo` 的 `RuntimeConcatReproController` 两处方法
  1. `listByTernaryBranch`：面板出现 `#param1`（预设 2222），填了没效果；SQL 看着也不对；
  2. `listByOptionalIf`：填了 `keyword` / `minAmount` 后 SQL 出现 `AND amount >=` 悬空行 + `AND amount >= 111` 重复行，`keyword` 占位仍留在字符串里没被替换。
- 取证方式：demo 源码 + 插件源码静态走查（`master` `39b15394`）+ 用户两张截图；未跑构建/测试

## 一、结论

两个问题都不是「参数值本身」的问题，而是**占位模型与 `?` 计数口径**的问题：

1. `listByTernaryBranch` 的 `#param1` 是**幻影参数**：`JdbcTemplateSqlPreviewService` 用 `sqlText.count { it == '?' }` 数 JDBC 占位符，把三元占位注释里的 **Java 三元问号**也数成了 `?`。真实 SQL 里没有 `?`，所以 `#param1` 填什么都不生效。SQL 文本本身（选了假分支、值 1）是对的。
2. `listByOptionalIf` 的错位是**可选片段的 token 与 template 覆盖面不一致**：slot 的 token 只覆盖「值」的位置，template 却覆盖**整段 if 片段**。回填时把整段模板塞进值位置，于是静态前缀重复（`AND amount >=` 两行）；`keyword` 的 token 落在字符串字面量内，`fillOptionalSlot` 直接拒绝（「片段无法直接填入」）；取消「包含这段」又只删 token，静态前后缀残留。
3. 这两类问题都没被现有测试抓到：单测里的 OPTIONAL 夹具把 token 写成**整段片段**，而拼接器真实产物是「静态片段在 token 外 + 值位置一个 token」——测试和实现用的不是同一个形态。

## 二、问题一：`listByTernaryBranch` 的幻影 `#param1`

### 2.1 复现代码

`mybatis-plus-demo/.../RuntimeConcatReproController.java:73-81`：

```java
String sql = "SELECT id, order_no, status FROM t_order WHERE 1 = 1 " +
        (status == null ? "" : " AND status = " + status) +
        " AND order_no LIKE '%" + keyword + "%' " +
        " ORDER BY id DESC";
```

### 2.2 根因

`JdbcTemplateSqlPreviewService.preview`（`psource/JdbcTemplateSqlPreviewService.kt:88`）：

```kotlin
val placeholderCount = sqlText.count { it == '?' }
val placeholderSources = if (placeholderCount > 0) (1..placeholderCount).map { "param$it" } else emptyList()
```

聚合后的 SQL 里有一条三元占位注释，正文含 Java 的 `?`：

```
/* 运行时值: status == null ? ”” : ” AND status = ” + status · rt1 */
```

`count { it == '?' }` 把它算成 1 个 JDBC 占位符 → 面板建出 `#param1`。但这条 `?` 在块注释里，`buildExecutableSqlWithSlots` 的单次扫描会整段跳过注释（`ParameterReplacer.kt:423-429`），所以 `#param1` 的值没有任何落点——「填了没效果」。

SQL 文本本身：用户选了「假分支」并填 `1`，产物是 `AND status = 1`；`keyword` 填 `1121321132`，产物是 `AND order_no LIKE '%1121321132%'`。与运行期语义一致，问题只在多出来的 `#param1` 行（以及它带来的「SQL 不太对」的观感）。

### 2.3 影响面

同类口径的现状（逐一核实）：

- `PagesXmlPreviewService.extractPlaceholders`（`ui/PagesXmlPreviewService.kt:239`）与 `XmlInlineSqlPreviewService.extractPlaceholders`（`ui/XmlInlineSqlPreviewService.kt:103`）**已经**改用 `placeholderPositions`，KDoc 还专门写明「旧实现 `sql.count { it == '?' }` 会把字符串字面量与注释里的 `?` 也算成参数」——这两个是本次修法的现成先例；
- `CopyExecutableSqlAction.countReplaced`（`ui/components/CopyExecutableSqlAction.kt:115-116`）：入参已剥注释，但字符串字面量里的 `?` 仍会被计数；
- `SqlPreviewService.extractPlaceholders`（`:1375`）是**私有且无调用点**的死代码，可顺手删除，避免后续误用；
- `usableNamedPlaceholderSources`（`:755`）的 KDoc 已注明「朴素 count（判定用途，非替换位点）」，需确认占位注释里的 `?` 是否会让它误判「全部来自命名参数」。

问题一的修法就是**照抄 XML 两处的口径**：`placeholderPositions(sql).size`。

## 三、问题二：`listByOptionalIf` 的 token / template 错位

### 3.1 复现代码

`RuntimeConcatReproController.java:90-103`：

```java
StringBuilder sql = new StringBuilder();
sql.append("SELECT id, order_no, amount FROM t_order WHERE deleted = 0 ");
if (keyword != null && !keyword.isEmpty()) {
    sql.append("AND order_no LIKE '%").append(keyword).append("%' ");
}
if (minAmount != null) {
    sql.append("AND amount >= ").append(minAmount).append(" ");
}
sql.append("ORDER BY id DESC");
```

### 3.2 拼接器产出的形态

`SqlStringConcatenator.collectAppendCalls` 的 OPTIONAL 分支（`psource/SqlStringConcatenator.kt:680-697`）对**每个 append 分别**处理：

- 静态 append（`"AND order_no LIKE '%"` / `"%' "` / `"AND amount >= "` / `" "`）→ 原样字面量；
- 运行时 append（`keyword` / `minAmount`）→ `slotPlaceholder(...)`，`template = repetitionTemplate(contextArguments(...))`，而 `repetitionTemplate` 拼的是**该 if 分支全部 append** 的形态：

```
AND order_no LIKE '%%s%' 
AND amount >= %s 
```

也就是说：**token 只覆盖值位置，template 覆盖整段片段**。未填值时的 SQL 是：

```
/* 运行时值: 条件追加，可能不出现 */AND order_no LIKE '%/* 运行时值: keyword · rt1 */%'
/* 运行时值: 条件追加，可能不出现 */AND amount >= /* 运行时值: minAmount · rt2 */
```

### 3.3 回填引擎按 template 展开 → 三种错位

`ParameterReplacer.fillOptionalSlot`（`sql/ParameterReplacer.kt:543-557`）：

```kotlin
if (!value.include) return Filled("")                 // 只删 token
val template = slot.firstTemplate ?: ...
if (inQuoted) return Filled(null, "占位落在字符串字面量内，片段无法直接填入")
if (countMarks(template) == 0) return Filled(template)
return fillTemplate(template, value.text, ...)
```

| 场景 | 当前行为 | 结果 |
|---|---|---|
| `keyword` 填 111 | token 在 `'%…%'` 内，`inQuoted=true` → 直接拒绝 | 占位原样保留，用户输入被忽略（截图里的「rt1: 占位落在字符串字面量内，片段无法直…」） |
| `minAmount` 填 111 | `inQuoted=false` → 把 token 换成整段模板 `AND amount >= 111` | 静态前缀 `AND amount >= ` 已在 token 外 → 悬空行 + 重复行 |
| 取消「包含这段」 | `Filled("")` 只删 token | `AND amount >= ` / `'%%'` 等静态前后缀残留，SQL 结构断裂 |

### 3.4 为什么测试没抓到

- `RuntimeSlotTest` 的 OPTIONAL 夹具（`src/test/.../psource/RuntimeSlotTest.kt:187-192`）只断言「2 个 OPTIONAL slot + 两个 template」，**没有断言填值后的最终 SQL**；
- `ParameterReplacerTest` 的 OPTIONAL 用例（`sql/ParameterReplacerTest.kt:486-513`）和面板用例（`ui/SqlPreviewPanelRuntimeSlotTest.kt:85-86`）里的 token **本身就是整段片段**，没有「静态前缀在 token 外」的形态；
- 真实拼接器产物是「静态片段在 token 外 + 值位置一个 token」，与单测夹具不同源。**这是本轮要补的端到端夹具。**

## 四、修复方案

### R1 幻影 `#param1`：`?` 计数改用 string-aware 扫描（P0）

`JdbcTemplateSqlPreviewService.preview` 的 `placeholderCount` 改用 `placeholderPositions(sqlText).size`（`sql/PlaceholderPositions.kt` 已有，`internal` 同模块可见）。语义不变，只把注释/字符串里的 `?` 排除。

### R2 可选片段改为「整段一个 slot」（P0）

让 token 与 template 的覆盖面一致：**token 覆盖整段 if 片段**，template 也是整段片段。这样 `fillOptionalSlot` 的三种路径都成立：

- 填值 → token 换成渲染后的整段（无重复、无字符串内拒绝）；
- 取消包含 → token 删除 = 整段消失；
- 留空 → token 原样保留（与「不回填逐字一致」口径一致）。

实现落点（`SqlStringConcatenator.collectAppendCalls`）：

1. 先按控制流 anchor 把 append 分组（`analyzeAppendPosition` 已给出 anchor）；
2. 对每个 OPTIONAL 上下文取 `args = contextArguments(...)` 与 `fragmentTemplate = repetitionTemplate(args)`；
3. 按模板里的 `%s` 个数分档：
   - **0 个**（全静态）→ 维持现状：字面量 + 说明注释，不造 slot；
   - **1 个** → **只造一个 slot**：`kind=OPTIONAL`、`template=fragmentTemplate`、`rawComment="条件追加: <运行时表达式>"`、`expression=<运行时表达式>`；该上下文其余 append 全部跳过（不再单独发字面量/说明注释，说明并入 slot 文案）；
   - **>1 个**（一个片段里多个运行期值）→ 本券先回退到「每个值一个 slot + `template=null`」的值位置模式，并在面板提示「该片段含多个运行期值，暂不支持整段开关」；整段 slot 的多值模型另券（D-2）。
4. `fillOptionalSlot` 增加 `template == null` 的值位置兜底：`include=false` 只删该值 token（文档写明静态前后缀会保留）；`include=true` 用 `literal(value.text, value.type, slot.hint.quote, inQuoted, context)` 就地填值（`inQuoted` 由 token 位置判定），从而兼容多值回退形态。

### R3 说明注释与 slot 文案

单值整段 slot 的 `rawComment` 建议写成 `条件追加: keyword`（或 `条件追加: AND order_no LIKE '%keyword%'` 的摘要），让未填值时的预览仍能看出「这段可能不出现」。不要再单独发 `/* 运行时值: 条件追加，可能不出现 */` 注释，否则取消包含后注释残留。

### R4 其他 `?` 计数审计（P1）

- XML 两处（`PagesXmlPreviewService` / `XmlInlineSqlPreviewService`）**已是**位点计数，不动；
- `CopyExecutableSqlAction.countReplaced`：入参已剥注释，但字符串字面量里的 `?` 仍会计数——改用位点计数，避免「替换了几个参数」的提示虚高；
- `SqlPreviewService.extractPlaceholders`（`:1375`）无调用点，直接删除；
- `usableNamedPlaceholderSources`（`:755`）：确认占位注释里的 `?` 是否会让命名参数判定误判，必要时改位点计数。

### R5 测试：补端到端夹具（P0）

在既有测试类里加用例，不新建类：

| 类 | 用例 |
|---|---|
| `RuntimeSlotTest` | ① 用户 `listByTernaryBranch` 样本：`unresolvedParams` 不含 `param1`；② `listByOptionalIf` 样本：填 `keyword=111` / `minAmount=111` 后 SQL 为 `AND order_no LIKE '%111%'` 与 `AND amount >= 111`，无重复 `AND amount >=`、无残留占位；③ 取消两个「包含」→ 两个片段都消失，`ORDER BY` 仍在 |
| `ParameterReplacerTest` | 新增「静态前缀在 token 外」的 OPTIONAL 用例（当前实现必红，作为反向验证） |
| `JdbcTemplateSqlPreviewServiceTest` | 三元占位含 `?` 的 SQL → `unresolvedParams` 为空（或只有真实 `?`） |
| `SqlPreviewPanelRuntimeSlotTest` | 用真实拼接器产物形态（静态前缀 + token）驱动面板：填值、取消、留空三态 |
| `RuntimeSlotValuesTest` | 多值片段回退形态的提示（如纳入 R2 回退） |

### R6 文档与 CHANGELOG

- `CHANGELOG.md` `[Unreleased]` 补一条修复（幻影 `#param1` + 可选片段回填错位）；
- demo 仓 `RuntimeConcatReproController` 三个方法补「填值后期望 SQL」注释（T-RuntimeSlotFill 计划 D-8 的遗留项）；
- 若本券改变了未填值时的预览形态（整段 slot 替代静态片段），在计划/CHANGELOG 写明。

## 五、影响面

| 改动 | 用户可见变化 | 风险 |
|---|---|---|
| R1 | 三元/含 `?` 注释的 JDBC 字符串 SQL 不再出现幻影 `#param1` | 真实 `?` 的计数不变；既有断言 `param1` 的用例需核对 |
| R2 | 可选 if 片段未填值时显示为**一个整段占位**（静态片段进 slot 文案），不再显示「静态片段 + 值占位」 | 预览形态变化；`RuntimeSlotTest` 的 slot 数量/模板断言需同步；多值片段回退为值位置模式 |
| R3 | 取消包含后不再残留说明注释 | 无 |
| R4 | 其他链路的参数行/提示更准 | 低 |

## 六、定向测试与反向验证

定向类：

```
./gradlew test \
  --tests "cn.song.mybatisplus.visualizer.psource.RuntimeSlotTest" \
  --tests "cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewServiceTest" \
  --tests "cn.song.mybatisplus.visualizer.sql.ParameterReplacerTest" \
  --tests "cn.song.mybatisplus.visualizer.sql.RuntimeSlotValuesTest" \
  --tests "cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelRuntimeSlotTest" \
  -Pkotlin.compiler.execution.strategy=in-process
```

反向验证（每项两次读数）：

1. `placeholderCount` 改回 `sqlText.count { it == '?' }` → 幻影 `#param1` 用例必红；
2. OPTIONAL 回退为「静态片段 + 值位置 token」→ `listByOptionalIf` 填值用例必红（重复/字符串内拒绝）；
3. `include=false` 只删 token → 取消包含用例必红（静态前后缀残留）。

## 七、任务拆分

| 券 | 内容 | 依赖 |
|---|---|---|
| W1 | R1 + R4 的 `?` 位点计数与审计 | 无 |
| W2 | R2/R3 整段 slot（拼接器分组 + 回填兜底）+ R5 夹具 | W1（可并行，写集不同） |
| W3 | demo 注释、CHANGELOG、定向收口与反向验证 | W1/W2 |

## 八、待验证前提（先证伪再动手）

| 编号 | 前提 | 证伪方法 | 被推翻的后果 |
|---|---|---|---|
| P-1 | 三元占位注释里的 `?` 确实是 `#param1` 的唯一来源 | 用 `listByTernaryBranch` 样本跑 `JdbcTemplateSqlPreviewService.preview`，断言 `unresolvedParams` 含 `param1`；改 `placeholderPositions` 后为空 | 若还有其他来源，需继续排查 `extractPlaceholders` / 面板预置 |
| P-2 | 拼接器对 OPTIONAL 上下文确实能拿到整段模板（含静态前后缀） | `RuntimeSlotTest:191-192` 已断言两个 template 值；再补一条「token 位置与 template 覆盖面对齐」的断言 | 若模板取不全，整段 slot 方案退化为值位置 + 文档限制 |
| P-3 | `finalizeSlots` 对「整段一个 slot」仍能按 baseComment 顺序匹配 | 用 `listByOptionalIf` 样本断言两个 slot 的 id 为 `rt1`/`rt2`，token 各出现一次 | 若匹配错位，改为按 anchor 顺序直接分配 id |
| P-4 | 面板 `inQuoted` 判定对「整段 slot」为 false（token 在字符串外） | 面板用例断言填值后 SQL 为 `'%111%'` 且无 `''111''` | 若判定错，需在 `renderSlot` 用模板的 `stringMask` 而非 token 位置 |
| P-5 | 多值片段（一个 if 里两个运行期值）在目标用户代码里是否存在 | grep 目标工程 / demo 的 `if` 内 append 形态 | 若大量存在，D-2 的「整段多值」需本券一并做 |

## 九、风险

| 风险 | 缓解 |
|---|---|
| 未填值时的预览形态变化被当成回归 | 用例逐条核对；CHANGELOG 写明「可选片段改为整段占位」；面板保留模板/展开预览 |
| 多值片段回退后取消包含仍残留静态前后缀 | 面板提示「暂不支持整段开关」；D-2 另券 |
| `?` 计数改口径影响既有参数行断言 | 全仓 grep `param1` 断言，逐条核对；只影响注释/字符串里的 `?` |
| `finalizeSlots` 的 id 匹配在整段 slot 下错位 | P-3 用例 + 按 anchor 顺序分配 id 的兜底 |
| 与 R3 的 `条件追加` 注释重复 | R3 明确：单值整段 slot 不再单独发说明注释 |

## 十、决策点

| 编号 | 问题 | 建议 |
|---|---|---|
| D-1 | 可选片段用「整段一个 slot」还是「值位置 slot + 片段范围标记」 | 整段一个 slot。与 `LOOP` 的「整段一个 slot」同构，回填/取消/留空三条路径天然一致 |
| D-2 | 一个片段多个运行期值怎么办 | 本券回退为值位置 slot + 提示；整段多值（一个 slot 多值输入）另券 |
| D-3 | 未填值时是否保留静态片段可见 | 不保留（整段进 slot），slot 文案带片段摘要；若用户更看重静态骨架，可保留「静态片段 + 整段 slot」双份，但会有重复风险，不推荐 |
| D-4 | 是否顺手把占位摘要里的 `?` 换成安全字符 | 不做。显示原文有价值；正确性由位点扫描保证 |
| D-5 | `extractPlaceholders` / `countReplaced` 是否本券一并改 | 一并改（P1），避免同类幻影参数在 XML/注解/复制链路复现 |

## 十一、变更记录

| 日期 | 内容 |
|---|---|
| 2026-11-07 | 立项。两处沙盒验收问题的源码级根因（幻影 `#param1`、OPTIONAL token/template 覆盖面错位）、R1~R6 方案、定向与反向验证、5 条待验证前提、5 个决策点。未改生产代码。 |
| 2026-11-07 | 实施并提交 `bd641efe`（插件）/ demo `320bef5`；定向 6 类 99/0/0/0；反向验证两条均取得红/绿读数（见 §十二）。 |

## 十二、实施记录

### 12.1 提交

| 哈希 | 内容 |
|---|---|
| `bd641efe` | `fix(预览): 运行期占位回填两处错位（T-RuntimeSlotOptionalFill）`——R1~R4 生产改动 + R5 测试 + CHANGELOG |
| demo `320bef5` | `docs(demo): 运行期拼接复现用例补填值后期望 SQL（T-RuntimeSlotOptionalFill）` |

### 12.2 改动点

生产（6 文件）：

- `JdbcTemplateSqlPreviewService`：`placeholderCount` 改用 `placeholderPositions(sqlText).size`（注释/字符串里的 `?` 不计）；
- `CopyExecutableSqlAction.countReplaced`、`SqlPreviewService.usableNamedPlaceholderSources` 同口径；删除无调用点的 `SqlPreviewService.extractPlaceholders`；
- `SqlStringConcatenator`：OPTIONAL 分支改为「整段一个 slot」——按 anchor 分组、`templateMarkCount` 分档；单值片段造一个 `kind=OPTIONAL`、`template=整段模板` 的 slot（说明注释并入文案），多值/全静态/模板未知走逐 append 回退（运行期值 `template=null`）；删除 `withOptionalContext` 与 `SlotSession.flowKind/flowTemplate`；`inferHint` 增加模板感知的 `quote` 判定；
- `ParameterReplacer.fillOptionalSlot`：`template=null` 的值位置模式就地填值（含字符串内只转义），取消包含给「静态片段会保留」提示。

测试（2 文件）：`RuntimeSlotTest`（`#param1` 断言、可选片段填值/取消端到端、留空回归同步）、`ParameterReplacerTest`（值位置模式就地填 + 取消提示）。

文档：`CHANGELOG.md` `[Unreleased]` 一条；demo 三个方法补「填值后期望 SQL」。

### 12.3 定向读数

| 类 | tests | fail | err | skip |
|---|---|---|---|---|
| `RuntimeSlotTest` | 11 | 0 | 0 | 0 |
| `SqlStringConcatenatorTest` | 19 | 0 | 0 | 0 |
| `JdbcTemplateSqlPreviewServiceTest` | 10 | 0 | 0 | 0 |
| `ParameterReplacerTest` | 41 | 0 | 0 | 0 |
| `RuntimeSlotValuesTest` | 10 | 0 | 0 | 0 |
| `SqlPreviewPanelRuntimeSlotTest` | 8 | 0 | 0 | 0 |
| 合计 | 99 | 0 | 0 | 0 |

### 12.4 反向验证（改前红 / 改后绿）

| # | 临时改动 | 红读数 | 还原后 |
|---|---|---|---|
| ① | `placeholderCount` 改回 `sqlText.count { it == '?' }` | `RuntimeSlotTest.testTernaryBranch_producesTernarySlotWithTwoBranchTemplates`：`三元注释里的 ? 不应算 JDBC 占位符: [param1]` | 还原后 6 类 99/0/0/0 |
| ② | 关闭整段 slot 并让值 slot 带整段 `template`（旧形态） | `testOptionalIf_fillAndExclude_endToEnd`：`keyword 应填进引号内: … AND order_no LIKE '%/* 运行时值: keyword · rt1 */%' … AND amount >= AND amount >= 111 …`；同批 `testOptionalIf_produces…` / `testEmptyValues_regressionOptionalIf` 共 3 红 | 还原后 6 类 99/0/0/0 |

两次临时改动均 `git checkout --` 还原，还原后复跑 6 类全绿。

### 12.5 偏差与说明

1. **多值片段**（一个 if 里多个运行期值）回退为值位置 slot（`template=null`）：取消包含只移除当前值、静态前后缀保留，回填引擎给提示；整段多值模型另券（D-2）。
2. **未填值预览形态变化**：可选片段从「静态片段 + 值占位」变为「一条整段占位」；`RuntimeSlotTest` 的 metadata / 留空回归断言已同步。
3. `inferHint` 的模板感知：token 在字符串外、但模板值位在引号内时仍标 `quote=true`（面板 auto 类型更准）。
4. 未跑全量 / `testShards` / 门禁（按「只跑定向测试」口径）；本次读数只指上表 6 类 99 例。
