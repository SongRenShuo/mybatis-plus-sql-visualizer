# T-RuntimeSlotFill：运行期拼接的参数可填值、SQL 随值变（2026-11-06 立项）

> 用户需求（2026-11-06）：「运行期 sql 还得优化，最好是能让用户自己填参数，然后 sql 动态变」。
> 复现夹具＝demo 仓 `mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/controller/RuntimeConcatReproController.java`
> 的三个方法（`listByLoopTernary` / `listByTernaryBranch` / `listByOptionalIf`）。
> 本册只做需求分析与方案；实施另派。

## 一、现状（链路上每一环都读过，位置准确）

**用户看到的样子**：β.2 之后，运行期拼接的片段被收口成一条合法块注释占位，例如

```sql
SELECT id, order_no, user_id, amount, create_time FROM t_order
WHERE ( /* 运行时值: 循环内追加 id=’…’ / or id=’…’（运行时重复） */ ) and create_time >= '/* 运行时值: startTime */'
```

括号与引号不再被吃掉（β.2 的修复），但**用户无法把它变成一条能跑的 SQL**：占位里的值没有任何输入口。

**现有可复用的三块基建**（这是本券成本不高的原因）

| 组件 | 能力 | 位置 |
|---|---|---|
| `ParamHint(quote, likePosition, inList)` | 已有「要不要加引号 / LIKE 在左中右 / 是否 IN 列表」三件语义 | `sql/ParamHint.kt:15` |
| `ParameterReplacer` | `replaceNamedParams`（按名回填，`'` → `''`，数值启发式）、`buildExecutableSql`（`?` 顺序回填）、`buildParamTable` | `sql/ParameterReplacer.kt:71,133` |
| 预览面板参数区 | `paramValues: MutableMap<String,String>` + 按 `unresolvedParams` / `conditionViews.valueSource` 建行 + `initialParamValues` 预填 + 导入/导出/差异对比/回放脚本导出 | `ui/SqlPreviewPanel.kt:618`、`SqlPreviewPanelParamPanel.kt`、`SqlPreviewPanelResultView.kt:186-220` |

**缺口（为什么今天填不了）**

1. 占位是**纯注释文本**，没有任何身份：`ConcatenationResult(sql, complete)` 只有文本，没有 id / span / 表达式；
2. 文本可能重复（两个方法里都出现 `/* 运行时值: startTime */`），按文本第 N 次出现替换，格式化了就错位；
3. 占位不在 `unresolvedParams` 里，面板不会为它建输入行；
4. 三种占位的**语义不同**，不能用同一个文本框了事：
   - `VALUE`（单值）→ 渲染一个字面量；
   - `LOOP`（循环体，运行期重复 N 次）→ 用户给一组值，展开成 `id='1' or id='2' …`；
   - `TERNARY`（三元两支）→ 让用户挑走哪支；
   - `OPTIONAL`（可选 if 片段）→ 让用户勾「这段在不在」。
5. `ParameterReplacer` 目前只认 `:name` 与 `?`，不认识占位 token。

## 二、目标 / 非目标

**目标**

1. 预览面板为每个运行期占位生成**一行输入**，按 kind 给对应控件（单值 / 值列表 / 分支选择 / 包含开关）。
2. 用户填完点「应用」→ 预览 SQL 里该占位被替换成**该方言安全的字面量**（字符串转义、数值裸出、布尔/NULL 按方言）；再点能改回空（留空 = 恢复占位，不猜测）。
3. 同一条链路上，**复制可执行 SQL / 导出回放脚本 / 直接执行**拿到的都是同一份已替换文本（不允许只改显示层）。
4. 覆盖夹具三方法：循环展开、三元挑支、可选片段开关，各有一条可读的证据。

**非目标**

- 不做「运行期求值」：不解析 Java 表达式、不执行用户代码（只做值填充与模板展开）。
- 不做跨会话持久化：填的值只活在当前预览面板（不写配置、不写 `.idea`）。
- 不改 Wrapper / 注解 / XML 三条既有参数链路的行为（它们走 `unresolvedParams`，本券只加「运行期占位」这一路）。
- 不动 `update/update.json`。

## 三、方案

### 3.1 模型：给占位一个身份（本券的地基）

新增 `sql/RuntimeSlot.kt`：

```kotlin
enum class SlotKind { VALUE, LOOP, TERNARY, OPTIONAL }

data class RuntimeSlot(
    val id: String,          // 稳定且唯一，如 "rt3"（按产物中首次出现顺序分配）
    val kind: SlotKind,
    val expression: String,  // 清洗后的表达式摘要（面板显示用，已是安全文本）
    val template: String?,   // LOOP/TERNARY 的结构化原文模板（见 3.2）
    val hint: ParamHint,     // 上下文推断出的 quote / inList 语义
    val token: String,       // 产物里那段**精确的**占位文本（用于定位）
)
```

**身份写在注释文本里**（不是 char range）：产物里占位统一变成

```
/* 运行时值: <表达式摘要> · <id> */
```

理由：`SqlFormatter` 的输出不变式是「只动空白与行尾注释，不改语义 token」（`ui/highlight/SqlFormatter.kt:435` 自述），冒号/括号会被重排，**char range 会在格式化后失效**，而 token 文本不会。定位时按「token 精确匹配 + 出现序号」兜底，替换一律**从后往前**做（见 P-2）。

> 可见性代价：id 会出现在屏幕上。可接受——它本来就在一条注释里；「复制可执行 SQL」路径会先替换掉整段注释，所以导出物干净（P-6 验证）。

### 3.2 拼接器：产出结构化模板，而不只是摘要

`SqlStringConcatenator` 现在只为 `LOOP` 生成一句人读摘要（β.2 polish 的 P1）。本券需要**可拼装的模板**：

- `LOOP`：把循环体里各 `append` 的**字符串字面量片段**按源码顺序拼成模板，非字面量位置留 `%s`，例如
  `id='%s' ` / `or id='%s' `（两支互斥时取「除首个外」的形态）；展开时按用户值的个数循环拼接，首个用第一支、其余用第二支——这正是 `listByLoopTernary` 的语义。
- `TERNARY`：两支各自的字面量片段，交给用户挑（另一支丢弃）。
- `OPTIONAL`：片段本身的字面量模板 + 「包含/不包含」开关。
- `VALUE`：无模板，直接渲染单个字面量。

字段以新增方式挂在 `ConcatenationResult` 上（默认 null / `SlotKind.VALUE`），既有构造点零改动。

### 3.3 回填引擎

新增 `ParameterReplacer.replaceRuntimeSlots(sql, slots, values)`：

1. 按 `slots` 的 `token` + 出现序号，在 sql 里定位每一处（从后往前替换，避免位移错乱）；
2. 值渲染复用 `renderNamedValue` 的引号/转义口径；`ValueType` 由用户在面板选或在 3.4 推导：
   - 字符串 → `'…'`（`'`→`''`）
   - 数字 → 原样（非数字输入 → 回退字符串并提示）
   - 布尔 → 按方言（Oracle 无 boolean 字面量 → 提示改用 `1/0`）
   - NULL → 裸 `NULL`（并在面板提示「`= NULL` 恒不成立，若要判空请用 `IS NULL`」）
   - 列表（LOOP/`inList`）→ 逐值渲染后按模板拼接
3. **串联顺序**：先 `replaceRuntimeSlots`，再 `buildExecutableSql`/`replaceNamedParams`。若用户值里含 `:x`/`?` 会被后一步误伤 —— 因此第 1 步的产物要按「已替换区间」记账，后两步跳过这些区间（P-2 的做法；做不到就退到「值里出现 `?`/`:` 时提示并禁止」）。

### 3.4 面板：一行一占位，控件按 kind

`PreviewResult` 新增 `runtimeSlots: List<RuntimeSlot> = emptyList()`；参数区在既有行模型上加一类行（`source = slot.id`，显示名 `运行时值 #n · <表达式摘要>`）：

| kind | 控件 |
|---|---|
| VALUE | 文本框 + 类型下拉（自动/字符串/数字/布尔/NULL） |
| LOOP | 值列表（逗号或换行分隔，支持「每行一个」）+ 展开预览（右侧灰字显示前 3 项拼接结果） |
| TERNARY | 分支下拉（两支各自的字面量预览）+ 值输入 |
| OPTIONAL | 复选框「包含这段」+ 值输入 |

留空 = 保留占位（与原 β.2 行为完全一致，因此**默认行为零变化**）。

### 3.5 「可执行」链路一致性

`ExecutableSqlFormatter`、`CopyExecutableSqlAction`、`ExecuteSqlSupport` 三处必须都在 slot 替换**之后**取文本（或在取文本前统一调一次）。任一漏掉，用户会「看到替换了、复制出来还是注释」——验收里有专门的对照用例。

## 四、决策点（推荐值）

| 编号 | 事项 | 推荐 | 理由 |
|---|---|---|---|
| D-1 | 身份载体 | **注释文本内嵌 id** | 抗格式化；spans 会被重排打散 |
| D-2 | LOOP 展开连接符 | **从循环体字面量模板提取**（首项/其余两支） | 不猜连接符；模板来自用户自己的代码 |
| D-3 | TERNARY 是否允许「自定义表达式」 | **不允许**，只选两支 | 允许自定义就又变成「当活 SQL 用」，退回 β.2 前的坑 |
| D-4 | 值类型默认 | **自动推导**：占位落在引号内 → 字符串；与数字比较 → 数字；`IN (...)` 内 → 列表 | 少一次点击；推导不出就按字符串 |
| D-5 | 留空语义 | **保留占位**（不是 NULL） | 与 β.2 行为一致，不会因为「手一抖点了应用」就把 SQL 变成 `= NULL` |
| D-6 | 值持久化 | **不落盘**（面板会话级） | 与「不回填不直送」口径一致；也避免把生产值写进 `.idea` |
| D-7 | 值数量/长度上限 | LOOP 展开 ≤ 200 项，单值 ≤ 2000 字符，超出提示 | 防手滑粘一坨日志进去把面板卡死 |
| D-8 | 是否同时改 demo 仓夹具 | **改**：三个方法各补一段「填值后期望 SQL」注释 | 用户按注释就能复验；demo 是这套能力的活文档 |

## 五、待验证前提（实施者先证伪再动手，报告里逐条给结论）

| 编号 | 前提 | 不成立时的退路 |
|---|---|---|
| P-1 | `SqlFormatter` 不会改写**块注释内容**（只动空白/行尾注释） | 若会改，则 id 内嵌失效 → 改为「替换只发生在未格式化文本上」，面板显示层用另一份带标记文本；需在报告里给出格式化前后对照 |
| P-2 | 「从后往前按 token 定位」在重复 token 下仍正确；且替换区间可记账，避免二次扫描误伤 | 退到「值里含 `?` / `:name` 形态时提示并拒绝」，并在 UI 写明 |
| P-3 | 循环模板可从 `append` 的字面量片段稳定抽取（现有 `summarizeExpression` 已能拿到片段，需扩展成模板而不是摘要） | 拿不到模板时 LOOP 退化为「整段替换为值列表用 `,` 连接」，并在面板标注「展开形态未知」 |
| P-4 | 三元两支的文本能分别拿到（现有只保留 `A / B` 摘要） | 拿不到就只允许「选一支后按 `VALUE` 填值」 |
| P-5 | 面板参数区能容纳 kind 特定控件（现有行构造按 `source` 生成文本框） | 退到「统一文本框 + 类型前缀语法」（如 `[1,2,3]` 表示列表），但要在 UI 写明语法 |
| P-6 | 三处可执行链路（复制/导出/执行）都能拿到替换后的文本 | 任一处拿不到就先在那一处禁用并提示，不允许默默输出带注释的「可执行 SQL」 |
| P-7 | 方言布尔/NULL 字面量差异可控（Oracle 无 boolean） | 本期只支持字符串/数字/NULL，布尔在 Oracle 上给提示 |
| P-8 | 与既有 `?`/`:name` 参数混用时互不干扰（同一 SQL 既有 `?` 又有运行期占位） | 按 P-2 的区间记账处理；做不到就分两步让用户确认 |

## 六、写集与实施顺序

**券 A（地基，必须先做）**：`RuntimeSlot` 模型 + `SqlStringConcatenator` 产出结构化 slot（含 id 与模板）+ `PreviewResult.runtimeSlots` + `JdbcTemplateSqlPreviewService` 填充 + 单测（**不碰 UI**）。
**券 B（回填引擎）**：`ParameterReplacer.replaceRuntimeSlots` + 值与类型渲染 + 三处链路串联 + 单测（含 `?`/`:name` 混用）。
**券 C（面板）**：参数区按 kind 建行与控件、应用/清空、展开预览、提示文案（bundle 中英）+ UI 测试（`SqlPreviewPanel*Test`）+ 真机走查步骤。

券 A→B→C 串行（B 依赖 A 的模型，C 依赖 B 的渲染）。每券各自分支与工作树，合规后再合。

**写集（预计 12~16 文件）**：`sql/RuntimeSlot.kt`(新)、`sql/ParameterReplacer.kt`、`psource/SqlStringConcatenator.kt`、`psource/JdbcTemplateSqlPreviewService.kt`、`model/PreviewResult.kt`、`ui/SqlPreviewPanelParamPanel.kt`、`ui/SqlPreviewPanelSqlView.kt`、`ui/SqlPreviewPanelResultView.kt`、`share/format/ExecutableSqlFormatter.kt`、`ui/components/CopyExecutableSqlAction.kt`、`execute/ExecuteSqlSupport.kt`、`messages/MpSqlVisualizerBundle{,_zh}.properties`、`CHANGELOG.md`、测试 4~6 个类。

## 七、验收

| 项 | 判据 |
|---|---|
| 夹具 1（循环） | `listByLoopTernary` 填 `ids=1,2,3`、`startTime=2026-01-01 00:00:00` → 预览与可执行 SQL 都出现 `(id='1' or id='2' or id='3')` 与 `create_time >= '2026-01-01 00:00:00'`，无残留注释 |
| 夹具 2（三元） | `listByTernaryBranch` 选「包含」支 → 出现 `AND status = 1`；选「不包含」支 → 该段消失且 `ORDER BY` 仍在 |
| 夹具 3（可选 if） | `listByOptionalIf` 勾 keyword 与 minAmount → 两个片段都在；都取消 → SQL 只剩必选片段与 `ORDER BY` |
| 留空回归 | 全留空 → 与 β.2 输出**逐字一致**（默认行为零变化，用现有 `JdbcTemplateSqlPreviewServiceTest` 的断言复核） |
| 转义 | 值 `O'Brien` → `'O''Brien'`；值 `1' OR '1'='1` → 整体作为字符串字面量，不产生第二个条件 |
| 链路一致 | 预览 / 复制可执行 / 导出回放脚本三处文本一致（同值同输入），且都不含 `运行时值` 注释 |
| 反向验证 1 | 把「留空=保留占位」改成「留空=NULL」→ 留空回归用例必红 |
| 反向验证 2 | 去掉 LOOP 的模板展开（退化成单值）→ 夹具 1 必红 |
| 反向验证 3 | 让「复制可执行」跳过 slot 替换 → 链路一致用例必红 |

## 八、风险

- **值里带 SQL**：用户填 `1' OR '1'='1` 时若转义漏掉，就是本地注入自己；验收里钉了转义用例，且**只做字面量渲染、绝不拼裸文本**。
- **模板猜错**：循环体形态复杂（多 append 交错）时模板可能拼不出可执行片段；此时必须**明确标注「展开形态未知」并回退成列表连接**，不许假装正确。
- **与 `?`/`:name` 混用**：P-2 的区间记账若做不干净，会出现「值被二次当作占位」；宁可拒绝也不出错。
- **面板复杂度**：参数区已有导入/导出/差异对比/回放导出等动作，新增 kind 控件要保证这些动作对 slot 行也有合理行为（至少不崩、导出里带上）。

## 九、实施记录

### 券 A（地基）— 完成（分支 `t-runtime-slot-a`，提交 `745cfe3c`）

**§五 待验证前提的实测结论**

- **P-1 成立（不改写块注释内容）**。实测：把 `/* 运行时值: 循环内追加 id=’…’ / or id=’…’（运行时重复） · rt1 */`
  与落在引号内的 `'/* 运行时值: startTime · rt2 */'` 一起喂给 `SqlFormatter.format`，产物
  `P1-KEEP-COMMENT=true`、`P1-KEEP-QUOTED=true`、注释符 open=2/close=2；再测带源码形态的
  `/* 运行时值: k == 0 ? x : y · rt3 */` → `P1-KEEP2=true`、open=1/close=1。
  格式化器**只把注释挪到独立行**（`SELECT a FROM t WHERE b =\n/* … */\nAND c = 1`），文本逐字保留。
  结论：身份内嵌注释文本的方案可用；char range 确认不可用（同一输入被重排成 6 行）。
  **副作用**：注释被挪行意味着实际产物里占位与前后 token 之间的空白会变——这与 β.2 现状一致，非本券引入。
- **P-3 成立（循环模板可稳定抽取）**。`loopContextArguments` 已能按锚点拿到循环体内全部 append 实参；
  把「字面量取原值、非字面量位置填 `%s`」的结构遍历（`shapeTemplate`）接到同一批实参上，
  夹具 1 得到首项 `id='%s' ` / 其余 `or id='%s' `（断言实测，见 `RuntimeSlotTest:101-102`）。
  多 append 交错形态（夹具 3）得到单形态 `AND order_no LIKE '%%s%' ` / `AND amount >= %s `。
- **P-4 成立（三元两支分别可拿）**。`concatConditional` 内部本来就分别算出 `thenResult` / `elseResult`
  （`SqlStringConcatenator.kt:411-412` 处两支文本比较），只是过去只留摘要；现在同一处把两支各跑一遍
  `shapeTemplate`，夹具 2 得到 then=`""`（空支）、else=` AND status = %s`。
  附带发现并修掉一个前提漏洞：**括号包住的三元在原实现里根本没被当三元**（`add` 的操作数是
  `PsiParenthesizedExpression`，`concat` 对它返回 null，于是整段按「普通运行时值」降级成一条 VALUE 占位）。
  现在 `concatExpression` 先 `PsiUtil.skipParenthesizedExprDown`：聚合交给内层，占位文本仍用外层原文，
  既有产物因此逐字不变。

**落点（文件:行）**

| 内容 | 位置 |
|---|---|
| `SlotKind` / `RuntimeSlot` / 两支编码 `joinBranches` / `templateBranches`·`firstTemplate`·`restTemplate` | `sql/RuntimeSlot.kt:18,61,123,77-101` |
| `ConcatenationResult.runtimeSlots`（默认空表） | `psource/SqlStringConcatenator.kt:65` |
| 会话与草稿（id 顶层出口统一分配） | 同上 `:144` `:166`，`concat` 入口 `:184-195` |
| 占位登记 `slotPlaceholder` / id 写回 `finalizeSlots` / `withSlotId` | 同上 `:254` `:292` `:321` |
| hint 推断 `inferHint`（引号内）/ `insideInList`（IN 括号内） | 同上 `:336` `:343` |
| 展开模板 `repetitionTemplate` / `twoBranchTemplate` / `shapeTemplate` | 同上 `:954` `:980` `:1000` |
| 可选片段上下文 `withOptionalContext` | 同上 `:928` |
| `PreviewResult.runtimeSlots`（默认空表） | `model/PreviewResult.kt:174` |
| 服务层填充 `collectRuntimeSlots` | `psource/JdbcTemplateSqlPreviewService.kt:125,142` |

**产物形态**：占位统一为 `/* 运行时值: <摘要> · rtN */`，id 按产物首次出现顺序 `rt1..rtN`，
同一次预览内唯一；说明性注释（`条件追加，可能不出现` / `互斥分支追加…` / `回调内追加…`）不是可填值
对象，**不带 id、不进 slot**（否则夹具 3 会多出两条无法填的 slot，与「两个 OPTIONAL slot」的验收冲突）。

**定向测试读数**（`./gradlew test --tests … -Pkotlin.compiler.execution.strategy=in-process`）

| 类 | XML 读数 |
|---|---|
| `RuntimeSlotTest`（新增，10 例） | `tests="10" failures="0" errors="0"` |
| `SqlStringConcatenatorTest`（既有 19 例，未改） | `tests="19" failures="0" errors="0"` |
| `JdbcTemplateSqlPreviewServiceTest`（既有 10 例，未改） | `tests="10" failures="0" errors="0"` |
| 附加（剥括号改动波及的另一路消费者）：`CustomDaoHqlLocatorTest` / `HqlQueryLocatorTest` | `tests="27" failures="0"` / `tests="24" failures="0"` |

**留空回归**：夹具 1/2/3 各一条用例，把 `result.compactSql` 里的 ` · rtN` 剥掉后与**券 A 改动前实测产物**
逐字比较（基线来自改动前 probe 读数，原样写进测试）。三处 diff 形态均为「仅多 ` · rtN`」：

```
- WHERE (/* 运行时值: 循环内追加 id=’…’ / or id=’…’（运行时重复） */) and create_time >= '/* 运行时值: startTime */';
+ WHERE (/* 运行时值: 循环内追加 id=’…’ / or id=’…’（运行时重复） · rt1 */) and create_time >= '/* 运行时值: startTime · rt2 */';
```

**反向验证（两次，红/绿对）**

| 反向改动 | 读数 |
|---|---|
| id 分配改成常量 `rt1` | `RuntimeSlotTest tests="10" failures="2"`；失败信息 `expected:<[rt1, rt2]> but was:<[rt1, rt1]>`（`RuntimeSlotTest.kt:95` 循环夹具、`:145` 三元夹具） |
| LOOP 模板改成 `null` | `RuntimeSlotTest tests="10" failures="1"`；失败信息 `expected:<id='%s' > but was:<null>`（`RuntimeSlotTest.kt:101`） |
| 两处均回退后复跑 | `tests="10" failures="0"`、`tests="19" failures="0"`、`tests="10" failures="0"` |

**本券未做 / 留给后续券**

- 不生成可执行 SQL、不动 `ParameterReplacer`、不动面板（按券切分）。
- `PAIRED`（if/else 互斥两支都追加）仍只有说明性注释：两支内容都已丢弃，没有可填的值对象，
  券 B/C 若要支持得先在拼接器里保留两支原文——**当前没有**，不要假装能填。
- 模板里 `%s` 是字面标记：用户源码若本来就有 `%s`（如 `LIKE '%s%'`），回填解析会多认一个占位。
  券 B 渲染时须按「模板里 `%s` 个数 = 预期值个数」校验，或改用更罕见的标记（本券按计划册 §3.2 用 `%s`）。
- `LOOP`/`TERNARY` 两支编码用 NUL 分隔（`RuntimeSlot.BRANCH_SEPARATOR`）：分支文本含 NUL 时整条模板
  回落 `null`（不编造）；正常情况下 SQL 字面量不会有 NUL。
- `PreviewResult.sql`（格式化文本）与 `compactSql` 都带 id；券 C 面板展示与券 B 替换都应以 `token` 为准。

### 券 B（回填引擎）— 完成（分支 `t-runtime-slot-b`，提交 `15de3ede`）

**§五 待验证前提的实测结论**

- **P-2 选「区间记账」，而且比计划册要求的更紧：占位 + `?` + `:name` 收成一次前向扫描。**
  实测把三步串起来跑（R2 反向验证）暴露了两处真实二次误伤，都不是 `?` 而是 `:name`：
  `NAMED_PARAM_PATTERN` 不是串/注释感知的正则，会把**用户值里的 `:nm`** 与**未回填占位注释里的
  `: y`** 一起替换（读数见下方反向验证表）。`?` 侧的二次误伤在结构上不会发生（值要么被引号包住、
  要么是裸数字，`placeholderPositions` 本就串感知），但 `:name` 侧必须靠「原子产出 + 不再扫描」解决。
  因此 `buildExecutableSqlWithSlots` 单次扫描里每种替换整段产出、产出不再被扫描；同时替换区间仍
  记账在 `RuntimeSlotRender.protectedSpans`，并给 `buildExecutableSql` / `replaceNamedParams`
  加了 `protectedSpans` 形参（默认空表 = 既有行为逐字节不变），供后续步骤跳过。
- **新增前提（计划册未列）：占位可能落在字符串字面量内。** 夹具 1 的
  `create_time >= '/* 运行时值: startTime · rt2 */'` 与夹具 2 的 `'%/* 运行时值: keyword */%'`
  都在引号里，券 A 用 `hint.quote=true` 标了出来。回填时**只能做转义、不能再加外层引号**，否则产物
  变成 `''值''`。落点：`ParameterReplacer.literal()` 的 `inQuoted` 分支（由 `stringMask()` 逐字符
  标记引号态得出）。模板里的 `%s` 同口径：模板自带 `id='%s' ` 时值在引号内，模板写 `amount >= %s`
  时值在引号外，按**模板自己的引号态**判断，不按占位 token 的。
- **新增前提：复制链会先剥注释。** `CopyExecutableSqlAction` 原先是
  `strip(raw)` → `buildExecutableSql`，而占位本身是一条块注释——先剥就再也定位不到（产物留
  `WHERE ( )`）。复制链改成「占位 → `?` → 剥注释」。`?` 位点口径不变（`placeholderPositions`
  在剥注释前后计数相同：注释里的 `?` 本来就不算）。
- **P-7 成立，且取更严的一档**：Oracle / 达梦的布尔值**拒绝回填并提示改 1/0**（不造 `TRUE`）；
  其余方言 `true/false` → `TRUE/FALSE`，`1/0` 原样（它们本来就是数字）。

**落点（文件:行）**

| 内容 | 位置 |
|---|---|
| `ValueType` / `SlotValue`（含 `isUnset` 留空判定）/ `RuntimeSlotValues` | `sql/RuntimeSlotValues.kt:9,48,103` |
| 静态校验 `validate`（未知 id / 循环空值超限 / 三元未选支 / 超长 / 数字类型非法） | 同上 `:128` |
| 多值拆分 `splitList`（逗号 / 换行 / 中文逗号） | 同上 `:194` |
| `RuntimeSlotRender`（产物 + 已填 id + 警告 + 替换区间） | `sql/ParameterReplacer.kt:221` |
| `replaceRuntimeSlots`（只填占位，供导出 / 预览 / 复制前段） | 同上 `:247` |
| `buildExecutableSqlWithSlots`（占位 → `?` → `:name` 单次扫描） | 同上 `:324` |
| 四类渲染 `fillValueSlot` / `fillLoopSlot` / `fillTernarySlot` / `fillOptionalSlot` | 同上 `:468,496,528,543` |
| `%s` 计数校验 + 模板填充 `fillTemplate` | 同上 `:566` |
| 字面量渲染 `literal`（引号内只转义 / 数字校验 / NULL / 布尔） | 同上 `:598` |
| 引号态标记 `stringMask` | 同上 `:686` |
| `buildExecutableSql` / `replaceNamedParams` 的 `protectedSpans` | 同上 `:81,150` |
| 可执行 SQL 唯一入口 `Product` / `format` / `formatWithDiagnostics` | `share/format/ExecutableSqlFormatter.kt:27,48,74` |
| 复制链产物 `Product` / `assembleProduct`（纯函数）/ `execute(panel, slotValues)` | `ui/components/CopyExecutableSqlAction.kt:54,68,128` |
| 执行链 `renderForExecution` / `executeWithRuntimeSlots` | `execute/ExecuteSqlSupport.kt:60,81` |

**定向测试读数**（`./gradlew test --tests … -Pkotlin.compiler.execution.strategy=in-process`）

| 类 | XML 读数 |
|---|---|
| `ParameterReplacerTest`（既有 18 例 + 新增 22 例） | `tests="40" skipped="0" failures="0" errors="0"` |
| `RuntimeSlotValuesTest`（新增 10 例） | `tests="10" skipped="0" failures="0" errors="0"` |
| `RuntimeSlotChainConsistencyTest`（新增 3 例） | `tests="3" skipped="0" failures="0" errors="0"` |
| `ExecuteSqlSupportTest`（既有 3 例 + 新增 2 例） | `tests="5" skipped="0" failures="0" errors="0"` |
| `ParameterReplacerNamedTest`（既有，未改） | `tests="17" skipped="0" failures="0" errors="0"` |
| `ExecutableSqlFormatterTest`（既有，未改） | `tests="8" skipped="0" failures="0" errors="0"` |
| `InjectionGateTest`（既有，覆盖复制链标记） | `tests="18" skipped="0" failures="0" errors="0"` |

**留空逐字一致（D-5）的对照形态**

三条断言同向，都要求**逐字节相等**：

1. `buildExecutableSqlWithSlots(sql, slots, RuntimeSlotValues.EMPTY, sources, paramValues, hints)`
   == 改前的 `buildExecutableSql(sql, sources, paramValues, hints)`；
2. 登记了 `SlotValue.UNSET`（有行、没动作）时同上；
3. `replaceRuntimeSlots(sql, slots, RuntimeSlotValues.EMPTY).sql` == 入参 `sql` 本身，
   且产物不含 `NULL`（不是把空值转成 NULL）。

链路上同理：`ExecutableSqlFormatter.format(sql, …, slotValues = EMPTY)` == 入参 `sql`；
复制链留空时只剩「剥注释」这一既有动作（引号内的占位原样保留，块注释形态的占位被剥掉），
`slotsApplied` 为空表。

**反向验证（三条，红/绿对）**

| 反向改动 | 读数 |
|---|---|
| R1 把占位替换摘掉（一律原样输出 token） | `RuntimeSlotChainConsistencyTest tests="3" failures="2"`；回退后 `tests="3" failures="0"` |
| R2 改成「占位 → `:name` → `?`」三步串行且不做区间记账 | `ParameterReplacerTest tests="40" failures="2"`，两条失败信息分别为 `expected:<… 'a?b [:nm]'> but was:<… 'a?b ['O''Brien']'>` 与 `expected:<… k == 0 ? x [: y] · rt1 …> but was:<… k == 0 ? x ['V'] · rt1 …>`；回退后 `tests="40" failures="0"` |
| R3 把留空改成 `NULL` | `ParameterReplacerTest tests="40" failures="2"` + `RuntimeSlotChainConsistencyTest tests="3" failures="2"`；回退后 `tests="40" failures="0"` / `tests="3" failures="0"` |

**本券未做 / 留给后续券**

- **面板接线是券 C 的活**：面板还没有 `slotValues` 状态，`CopyExecutableSqlAction.execute(panel, slotValues)`、
  `ExecuteSqlSupport.executeWithRuntimeSlots(...)` 的 slot 参数目前由调用方传（默认空表 = 零变化）。
  预览渲染路径（`SqlPreviewPanelSqlView` 里那次 `replaceNamedParams`）与导出
  （`previewSqlForReadonly()` → 回放脚本）也必须接到
  `ExecutableSqlFormatter.formatWithDiagnostics` / `ParameterReplacer.replaceRuntimeSlots` 上，
  否则仍会「预览填了、导出没填」。这是 P-6 唯一还开着的口子。
- `PAIRED`（if/else 互斥两支）仍不可填——券 A 已声明两支原文被丢弃，没有可填对象。
- **限制（需写进面板文案）**：`OPTIONAL` 的「包含这段」默认勾选，与「用户没动」不可区分，
  因此必须**带值**或**显式取消勾选**才算一次动作；纯字面量片段（模板里没有 `%s`）请在值里填
  任意非空文本用于「已动作」判定，不影响产物。
- **限制**：`LOOP` / `LIST` 的拆分口径是「逗号与换行都是分隔符」，项本身含逗号会被拆成两项
  （`SlotValue.list` 的 KDoc 已写明；这种值请改用 `STRING` 单值）。
- **既有行为带过来的细节**：复制链在占位之后仍要剥注释，多行用户值会被逐行 trim 尾部空白
  （`SqlCommentStripper` 既有行为，不是本券引入）。
- 未跑全量 / 分片 / `verifyGate` / `buildPlugin`（按券纪律，只跑定向）。

### 券 C（面板接线）— 完成（分支 `t-runtime-slot-c`，提交 `36dc8da3`）

**补掉的口子**：券 B 报告的 P-6 唯一开口——面板没有 `slotValues` 状态，预览渲染与导出回放各走各的，
会出现「预览填了、复制/导出没填」。

**四条链路的接线点**

| 链路 | 接线点 | 做法 |
|---|---|---|
| 预览 | `ui/SqlPreviewPanelSqlView.kt:176` `applyRuntimeSlotsAndNamedParams` | 占位 + `:name` 走 `buildExecutableSqlWithSlots` **一次扫描**（防值里的 `:nm` 被二次替换）；`?` 仍走既有 `backfillParamsBySource`（LIKE / 空值策略 / 模拟字面量口径不动） |
| 复制 | `ui/SqlPreviewPanelSqlView.kt:559` `copyExecutableSql` | `CopyExecutableSqlAction.execute(this, slotValuesSnapshot())`（先填占位再剥注释，券 B 定的顺序） |
| 导出回放 | `ui/SqlPreviewPanelExecution.kt:147` `previewSqlForExport` | `previewSqlForReadonly()` 之后补一次 `fillRuntimeSlots`（编辑器文本已是填好形态时为无操作） |
| 执行 | `ui/SqlPreviewPanelExecution.kt:1145` `executeSql` | 顶部 `val sql = fillRuntimeSlots(rawSql)`，之后分类 / 闸门 / 快照 / 执行全用这份；写回文档仍用 `rawSql`（不回写已填值，避免污染预览文本） |

**落点（文件:行）**

| 内容 | 位置 |
|---|---|
| `slotValues` / `slotRenderWarnings` / `runtimeSlotRows` / 诊断行引用 | `ui/SqlPreviewPanel.kt:628,636,639,642` |
| 占位值快照 / 填占位（导出/执行取词口） | `ui/SqlPreviewPanelSqlView.kt:197,206` |
| 随新预览整表清空（D-6） | `ui/SqlPreviewPanelResultView.kt:193` |
| 分区标题 / 行控件组 / 建行 / LOOP 展开预览 / 诊断发布 | `ui/SqlPreviewPanelParamPanel.kt:2003,2020,2052,2180,2208` |
| 分区插入点（仅在 `runtimeSlots` 非空时） | 同上 `:324` |
| 文案 `panel.slot.*`（中英各 24 键） | `messages/MpSqlVisualizerBundle{,_zh}.properties:91` 起 |

**控件按 kind**：VALUE = 类型下拉（自动/字符串/数字/布尔/NULL）+ 文本框；LOOP = 文本框 + 右侧灰字展开
预览（前 3 项，**直接喂真实回填引擎**算，不另写拼接）；TERNARY = 分支下拉（两支模板预览）+ 文本框；
OPTIONAL = 「包含这段」复选框 + 文本框。控件变更只更新本占位的那一项（`SlotValue.copy`），再走既有
`scheduleRender` 防抖链。

**券 B 声明的限制在面板上的落实**

- 引号内占位只转义不重复加引号：由引擎负责，面板不加壳；用例 `create_time >= '2026-01-01 00:00:00'` 钉住。
- 复制链先填占位再剥注释：面板只传值，顺序在 `CopyExecutableSqlAction.assembleProduct`（券 B 已定）。
- `OPTIONAL`「默认勾选 = 未动作」：行内复选框 tooltip 写明「必须填了值或显式取消勾选才算一次动作；
  片段没有可填位置时填任意非空文本（不影响产物）」。
- `LOOP` 逗号/换行都是分隔符：文本框 tooltip 写明「项内含逗号会被拆成两项，这类值请每行一个」。
- 模板 `%s` 计数不符 / 展开形态未知 / 方言无布尔字面量等拒绝：渲染后写入 `slotRenderWarnings`，
  分区下方灰字诊断行显示「首条 + 条数」，完整清单进 tooltip——**不静默**（用例钉住）。

**定向测试读数**（`./gradlew test --tests … -Pkotlin.compiler.execution.strategy=in-process`）

| 类 | XML 读数 |
|---|---|
| `SqlPreviewPanelRuntimeSlotTest`（新增 8 例） | `tests="8" skipped="0" failures="0" errors="0"` |
| `RuntimeSlotChainConsistencyTest`（券 B，未改） | `tests="3" skipped="0" failures="0" errors="0"` |
| `SqlPreviewPanelTest`（既有，未改） | `tests="31" skipped="0" failures="0" errors="0"` |
| `SqlPreviewPanelImportTest`（既有，未改） | `tests="22" skipped="0" failures="0" errors="0"` |
| `PreviewReadonlySnapshotTest`（既有，未改） | `tests="4" skipped="0" failures="0" errors="0"` |
| `SqlPreviewPanelParamFieldTest` / `SqlPreviewPanelEmptyHintTest`（既有，未改） | `tests="2" failures="0"` / `tests="1" failures="0"` |

**六组用例覆盖**：①无占位 → 不建任何占位行、参数区零变化；②行数 = 占位数且 kind ↔ 控件类型正确；
③填值后预览变化（含 `(id='1' or id='2' or id='3' )`、引号内时间值、无 `运行时值` 残留）；④留空时四条
链路逐字节一致；⑤切语言（zh→en 重建）后占位行仍在且标签更新；⑥券 B 的 `%s` 计数拒绝在面板上可见。
另加一条正向链路一致用例（填值后 预览 / 复制 / 导出回放 / 执行 同一份文本）。

**反向验证（两条，红/绿对）**

| 反向改动 | 读数 |
|---|---|
| R1 预览渲染不传 slot（`applyRuntimeSlotsAndNamedParams` 直接 `applyNamedParams`） | `SqlPreviewPanelRuntimeSlotTest tests="8" failures="4"`，失败信息为「循环应展开成模板形态: SELECT … `(/* 运行时值: … · rt1 */)`…」「预览里不许残留占位」「模板 %s 计数不符必须报出来: []」；回退后 `tests="8" failures="0"` |
| R2 导出取词口不传 slot（`previewSqlForExport` 退回裸 `previewSqlForReadonly`） | `tests="8" failures="1"`，失败信息 `expected:<…create_time >= '[2026-01-01 00:00:00]'…> but was:<…'[/* 运行时值: startTime · rt2 */]'…>`；回退后 `tests="8" failures="0"` |

**留空逐字一致的对照形态**：`result.sql`（未回填）与「预览编辑器文本 / 复制产物 / 导出取词 / 执行取词」
四处**逐字节相等**（用例 `testEmptySlotValuesKeepFourChainsByteIdentical`，无 `NULL` 转换）；零占位的预览
（Wrapper/XML/批量/JPA 全链）走 `applyNamedParams` 旧路径，产物与改动前一致。

**本券未做 / 遗留**

- 占位值不进参数历史与参数预设（`buildHistorySnapshot` / `serializeParamsToJson` 仍只收 `paramValues`）：
  与 D-6「不跨会话持久化」同向，但用户切预设时占位值会丢。
- 「导入参数」按 `paramValues` 的 source 匹配，占位 id（`rtN`）不在匹配集合内——导入 JSON 里的 `rtN`
  会被报成未匹配（预期，不静默失败）。
- 「差异对比」读的是编辑器文本（已含填值），填值本身会显示成与上次预览的差异（正常）。
- EXPLAIN 链未接 `fillRuntimeSlots`（本券口径是复制 / 导出 / 执行三处）；预览文本已填时 EXPLAIN 与预览一致，
  用户手改回占位形态时仍会把块注释送进 EXPLAIN。
- 分享对话框里的「可执行 SQL」走 `SqlShareManager.copyExecutableSql`（读编辑器文本，已填）——不另传值，
  与预览一致；它不在本券写集内，未改。
- 未跑全量 / 分片 / `verifyGate` / `buildPlugin`（按券纪律，只跑定向）。

