# T-UpdateSetSqlConcat：setSql 拼接实参导致 SET 子句整段丢失

2026-10-20。来源：用户在 `mybatis-plus-demo`（8081）真机复现 `OrderController#discount`，预览只剩 `UPDATE t_order` 和 `WHERE`，SET 整段不见。验收台账登记 UI-038。

## 一、现象与复现

demo 源码（`mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/controller/OrderController.java:56-62`）：

```java
@GetMapping("/discount")
public int discount(BigDecimal rate, Integer status) {
    UpdateWrapper<Order> updateWrapper = new UpdateWrapper<>();
    updateWrapper.setSql("amount = amount * " + rate);
    updateWrapper.eq("status", status);
    return orderMapper.update(null, updateWrapper);
}
```

现状产出（与截图一致）：

```sql
UPDATE t_order
WHERE status = ?
```

期望产出：

```sql
UPDATE t_order
SET amount = amount * ?
WHERE status = ?
```

`UPDATE` 没有 `SET` 是非法 SQL，不是「少显示一段」的观感问题，粘出去执行会直接报语法错。

## 二、根因

一句话：`setSql` 的实参是字符串拼接表达式，求值器只能把它整体判成「运行期未知值」，而 `setSql` 分支只接受完全静态的字面量，于是整段 SET 被丢弃。

逐跳链路（dev 树，行号可核）：

1. `ArgResolver.resolve` 把 `setSql` 实参交给 `ExpressionEvaluator.evaluate`（`psi/ArgResolver.kt:88`，非字面量走 `resolveValueArg`）。
2. 实参是 `PsiPolyadicExpression`（`+` 拼接）→ `evaluatePolyadic`（`psi/ExpressionEvaluator.kt:73`）。左操作数是字符串字面量；右操作数 `rate` 是方法参数，`evaluateReference`（`:156`）取不到初始化式 → `SqlValue.Placeholder("rate")`。
3. `evaluatePolyadic` 的规则是「任一段不可静态求值 → 整段返回 Placeholder」（`:79`），拼接里的静态前缀 `"amount = amount * "` 在这一步就丢了。
4. `LambdaUpdateConverter.parseSet` 的 `SET_SQL` 分支要求 `asValue() as? SqlValue.Literal`（`converter/LambdaUpdateConverter.kt:72`），拿到 Placeholder → `return null`，这次调用不产生任何 `SetClause`。
5. `SqlGenerator.generateUpdate` 只有 `sets` 非空才输出 `SET`（`sql/SqlGenerator.kt:235`），SET 整段消失，语句退化成非法形态。

已排除的三个相邻解释：

- 不是 wrapper 识别问题：`UPDATE` 类型和表名 `t_order` 都正确，`WHERE status` 也在。
- 不是 `update(null, wrapper)` 触发的实体字段展开：`WrapperLocator.isEntityUpdateArgument` 明确把首参 `null` 判为非实体更新（`psi/WrapperLocator.kt:1051`），走 `ConverterRegistry` → `LambdaUpdateConverter`，SET 的唯一来源就是 `setSql`。
- 不是链收集问题：既有用例 `testUpdateLastRawTailPassthrough`（`ui/SqlPreviewServiceUpdateTest.kt`）用的是同样的「独立语句 `wrapper.xxx(...)`」写法，`setSql` 能进链并正常渲染成 `SET last_total = last_total - 1`。

## 三、关键区分：值位置与片段位置不能用同一套渲染

这是本方案最容易做错的地方，先讲清楚。

- 值位置（`eq("name", "a" + b)`、`set(col, "a" + b)`）：MP 把整个字符串当作一个 JDBC 参数绑定，运行时 SQL 是 `name = ?`，拼接结果整体进参数。预览只能是 `?`。
- 片段位置（`setSql`、`apply`、`last` 尾缀、`having`、`inSql`、`first`、`comment`、MPJ 字符串 join）：MP 把整个字符串当作原始 SQL 拼进语句，不做参数绑定。运行期段落在预览里用 `?` 近似，静态段必须保留。

所以不能去改 `evaluatePolyadic` 的全局返回值。若让它返回拼好的 `Literal`，值位置就会渲染成 `name = a?`，那是把一个正确的能力改坏。本方案的做法是：仍是 `Placeholder`，但额外带上「这段表达式若当原始 SQL 用，长什么样」的信息，由片段位置显式取用。

## 四、同族缺口普查

同一个「只认 `SqlValue.Literal`」的写法散布在所有 SQL 片段入口，凡实参含运行期段就整段丢弃，症状一致（静默少一段 SQL）：

| 入口 | 位置 | 现状 | 本次处理 |
| --- | --- | --- | --- |
| `setSql`（单表更新） | `LambdaUpdateConverter.kt:72` | 本次报障 | P0 修 |
| `setSql` / `setApply`（联表更新） | `UpdateJoinConverter.kt:101` | 同上 | P0 修 |
| `last` 非 limit 尾缀 | `LambdaUpdateConverter.kt:46`、`UpdateJoinConverter.kt:77` | 含运行期段即丢尾缀 | P1 修 |
| `apply` / `exists` 模板 | `QueryWrapperParser.kt:775`（`parseRawTemplate`） | 同上 | P1 修 |
| `having(String)` | `QueryWrapperParser.kt:553` | 同上 | P1 修 |
| `inSql` / `notInSql` | `QueryWrapperParser.kt:461` | 同上 | P1 修 |
| `eqSql` / `gtSql` / `geSql` / `ltSql` / `leSql` | `QueryWrapperParser.kt:469` | 同上 | P1 修 |
| `first` / `comment` | `QueryWrapperParser.kt:194-195` | 同上 | P1 修 |
| `applyFunc` 模板 | `QueryWrapperParser.kt:818` | 同上 | P1 修 |
| `selectFunc` 字符串模板 | `QueryWrapperParser.kt:300` | 同上 | P1 修 |
| MPJ 字符串 join | `MpjLambdaConverter.kt:506`、`WrapperConverter.kt:163-179`（`MpjJoinSupport` 共享实现） | 同上（首参判定层也依赖 Literal，join 整段丢失） | P1 修 |
| MPJ `around` 片段 | `MpjLambdaConverter.kt:203-206` | 同上 | P1 修 |
| `last("LIMIT " + n)` | `QueryWrapperParser.kt:589`（`parseLimit`） | 正则只吃数字，`LIMIT ?` 无法建模 | 本次不修，登记遗留 |

勘误（实施期实测）：初版表格把 `DeleteJoinConverter.kt:42` 记为 `last` 尾缀，实际那行是 `setAlias` 主别名的取值，且 DeleteJoinConverter **没有** `last` 尾缀处理（删除联表语法无条件尾位置）。该行属值/标识符位置，不参与片段换口。同理 `DeleteJoinConverter.kt:92`（delete 目标表别名）与各 converter 的 `setAlias` 取值都是值位置，保持 `Literal` 口径。

顺带说明 `evaluatePolyadic` 的 KDoc 里举的例子正是 `last("LIMIT " + N)`，但下游 `parseLimit` 需要具体数字，`LimitClause` 也只能存 `Long`。这条不改模型就修不了，单独登记。

## 五、方案

### 5.1 片段求值（推荐）

1. `SqlValue.Placeholder` 增加默认参数 `fragment: String? = null`，含义是「该运行期表达式作为原始 SQL 片段时的文本形态」。默认值保证 143 处现有构造点与全部 `when` 分支不受影响（`Placeholder` 无 Java 调用方、无序列化注解，已核实）。
2. `ExpressionEvaluator.evaluatePolyadic` 在全静态路径不变的前提下，构造 `Placeholder` 时尽量算出 `fragment`：
   - 操作数求值为 `Literal` → 取 `unquoted`；
   - 操作数为已带 `fragment` 的 `Placeholder` → 递归取（覆盖括号嵌套与多级拼接）；
   - 其余 → 写 `?`。
3. 只在「整体是字符串拼接」时才给 `fragment`：优先看 `expression.type` 是否 String，类型解析不到时回落到「存在字符串/字符字面量操作数」。数值算术（`1 + n`）不给，避免拼出 `1?` 这种无意义片段。
4. `converter/WrapperConverter.kt` 增加统一取值口：

```kotlin
fun WrapperArg?.asSqlFragment(): String? = when (val v = (this as? WrapperArg.ValueArg)?.value) {
    is SqlValue.Literal -> v.unquoted
    is SqlValue.Placeholder -> v.fragment
    else -> null
}
```

5. 第四节表里的片段位置，把 `asValue() as? SqlValue.Literal` 换成 `asSqlFragment()`。布尔前缀判定（`isFlagArgument` / `parseRawTemplate` 的 `hasFlag`）一律不动——`Placeholder` 仍是 `Placeholder`，前缀逻辑零改动。

这样 `"amount = amount * " + rate` 得到 `fragment = "amount = amount * ?"`，`setSql` 分支正常产出 `SetClause(rawSql = ...)`，渲染为 `SET amount = amount * ?`。

### 5.2 被否方案及理由

- 改 `evaluatePolyadic` 全局返回 `Literal`：值位置退化成 `name = a?`，把正确能力改坏。
- 转换器层把 `Placeholder` 直接渲染成 `?`：产出 `SET ?`，仍是非法 SQL，且丢掉静态段。
- 在 `ArgResolver` 里按方法名分派片段求值：需要在那里复制一遍「布尔前缀在第几参」的判定规则，与转换器里的两份规则必然漂移，且 `setSql`/`apply`/`join` 各方法形态不同。用 `fragment` 字段让消费方自己决定要不要用，不制造第二份规则。

### 5.3 安全网：解析失败不再静默

修完 5.1 后仍有解析不了的形态，典型是 `setSql(sqlVar)`（整段运行时，拼不出任何静态段）。此时省略是唯一正确选择，但不能静默产出非法 SQL：

- `UpdateStatement` 增加 `unparsedSets: List<String> = emptyList()`，两个更新转换器在 `parseSet` 放弃时记录表达式原文（`Placeholder.source`）。
- `SqlPreviewService.buildWarning` 在 UPDATE 且 `unparsedSets` 非空时追加提示，形如：
  `⚠ [预览] 有 1 处 SET 片段无法静态解析，已省略：sqlVar`
- 现有「无 WHERE 全表更新」警示不动。

成本是两个字段加一处 `listOfNotNull`，收益是同类问题下次能自己暴露，而不是靠用户盯着截图发现 SQL 少了半句。

## 六、改动清单

生产代码：

- `model/SqlValue.kt`：`Placeholder` 增 `fragment`。
- `psi/ExpressionEvaluator.kt`：`evaluatePolyadic` 计算 `fragment`（新增私有辅助函数）。
- `converter/WrapperConverter.kt`：新增 `asSqlFragment()`。
- `converter/LambdaUpdateConverter.kt`：`setSql`、`last` 两处。
- `converter/UpdateJoinConverter.kt`：`setSql`/`setApply`、`last` 两处。
- `converter/DeleteJoinConverter.kt`：`last`。
- `converter/QueryWrapperParser.kt`：`parseRawTemplate`、`parseHaving`、`inSql`、`compareSql`、`first`/`comment`、`parseApplyFunc`、`parseSelectFunc` 模板。
- `converter/MpjLambdaConverter.kt`：字符串 join 片段。
- `model/UpdateDeleteStatement.kt`：`UpdateStatement.unparsedSets`。
- `ui/SqlPreviewService.kt`：`buildWarning` 追加提示。

两个模型字段都带默认值：`Placeholder(...)` 现有 143 处构造、`UpdateStatement(...)` 现有 12 处 main + 12 处 test 构造，全部为位置参数且无 Java 调用方、无序列化注解，新增尾部默认参数是零改动。

测试：

- `ui/SqlPreviewServiceUpdateTest.kt`：复现用例 + 形态矩阵（见第七节）。
- `converter/UpdateWrapperConverterTest.kt`、`UpdateJoinDeleteJoinConverterTest.kt`：模型层补 `fragment` 形态。
- `ui/SqlPreviewServiceTest.kt`：`apply`/`having`/`inSql` 同族入口各一条。

## 七、测试方案

全部走 PSI 端到端（`myFixture.configureByText` + `SqlPreviewService.preview`），因为缺口恰好出在「PSI 求值 → 转换器」这一段；转换器层的既有测试都是手工构造 `SqlValue.Literal`，天然绕过了缺陷。

正向用例：

1. demo 原样形态 → `SET amount = amount * ?`。
2. 多段拼接：`setSql("a = " + x + " + " + y)` → `SET a = ? + ?`。
3. 静态变量前缀：`String p = "amount = amount * "; setSql(p + rate)` → `SET amount = amount * ?`（验证变量追踪）。
4. 布尔前缀 + 拼接：`setSql(flag, "a = " + x)` → 前缀剥离正确，`SET a = ?`。
5. 与 `{i}` 共存：`setSql("a = " + x + " + {0}", y)` → `SET a = ? + ?`。
6. 联表更新 `setApply("t.a = " + x)` → `SET t.a = ?`。
7. 同族入口：`apply("amount > " + x)`、`having("count(*) > " + n)`、`inSql("id", "SELECT id FROM t WHERE x = " + x)`。

护栏与反向用例（缺一不可）：

8. 值位置必须仍是单个 `?`：`set(col, "a" + x)` 与 `eq("name", "pre" + x)` 断言渲染为 `?`，不是 `pre?`。这条是本方案的核心反向验证——证明没有把 `fragment` 泄漏到值位置。
9. 全静态拼接不回归：`setSql("a = b + " + 1)` 仍走原 `Literal` 路径，产出 `a = b + 1`。
10. 纯运行时片段仍省略且有警示：`setSql(sqlVar)` → 无 SET，`warning` 含「无法静态解析」。
11. 反向验证：把 `asSqlFragment()` 回退成 `Literal`-only → 用例 1 必红；把 `fragment` 计算整段回退 → 用例 1/2/3 必红；把警示分支移除 → 用例 10 的 warning 断言必红。三次都要贴读数。

回归范围：`setSql`/`apply`/`last`/`having`/`inSql` 相关既有用例全绿；按 AGENTS.md 三档分层，定向由实施者跑，全量由只读收口代理单路跑一次。

## 八、风险与边界

- `?` 是近似：真实执行时 MP 直接做字符串拼接、不绑定参数，实际 SQL 里是具体数值。预览用 `?` 表示「运行期未知值」，与插件既有约定一致（`{i}` 也一样）。
- `rawSql` 里的 `?` 必须按序进参数表（实施期修正，见 11.6）：原计划写「与 `{i}` 一样不带参数」是**错的**——
  面板按位置把 `sources[i]` 对应到第 i 个 `?`，片段里的 `?` 不登记就会让后续条件整体错位（实测：用户填 `status`，
  值落到了 SET 的 `rate` 占位符上）。
- `setSql(sqlVar)`、`String.format(...)` 之类的整段运行时片段仍然拼不出来，靠 5.3 的警示兜底，不假装能还原。
- `last("LIMIT " + n)` 不在本次范围：`LimitClause` 只有 `Long`，`LIMIT ?` 无处安放。登记为遗留项。
- 片段拼接本身在用户代码里是 SQL 注入风险（把请求参数直接拼进 SQL），但这属于被预览代码的质量问题，插件不阻断，最多在提示文案里点一句。
- `evaluatePolyadic` 的 `fragment` 计算会多走一遍操作数，仅在 `PsiPolyadicExpression` 上触发，不影响热路径。

## 九、验收标准

- demo 用例产出 `UPDATE t_order` / `SET amount = amount * ?` / `WHERE status = ?` 三行。
- 第七节 1~7 全部通过，8~10 作为护栏常绿，11 的三次反向验证各恰按预期变红。
- 定向测试全绿、全量门禁无新增失败。
- 真机复验：在 `mybatis-plus-demo` 的 `discount` 上点预览，SET 行出现；再点一次右侧「执行计划」与「复制 SQL」不因 `?` 报错（`?` 作为文本复制是预期行为）。

## 十、待验证前提（实施者先证伪再动手）

1. 运行期复现未跑（本轮有并行会话占用 dev 工作树，没有插入临时测试文件污染对方编译）。上面的链路是代码级可判定结论，但实施第一步仍应先补用例看到红，确认现状就是截图形态。
2. `expression.type` 在 light fixture 下能否把 `"..." + 方法参数` 解析成 String 需要实测；解不出时靠「存在字符串字面量操作数」的启发式，两条路任一成立即可，但启发式要单独补一条用例（纯数值算术不得产生 `fragment`）。
3. `setSql` 在「独立语句」形态下确实进链：`testUpdateLastRawTailPassthrough` 间接证明 `wrapper.setSql(...)` 能进链并渲染，但该用例同时有 `last`，建议补一条只有 `setSql` 的纯静态用例把前提钉死。
4. 现有测试没有针对拼接实参的相等性断言（已 grep：转换器测试全部直接喂 `SqlValue.Literal`），因此 `Placeholder` 加字段不会造成假红；实施时再复核一遍 `assertEquals` 中是否出现 `Placeholder(...)` 构造。

## 十一、实施记录（2026-10-20，dev 分支）

### 11.1 落地清单

| 文件 | 改动 |
| --- | --- |
| `model/SqlValue.kt` | `Placeholder(source, fragment)`，`fragment` 默认 null |
| `psi/ExpressionEvaluator.kt` | `evaluatePolyadic` 在运行期分支构造 `Placeholder(text, fragmentOf(expr))`；新增 `fragmentOf` / `isStringConcat` |
| `converter/WrapperConverter.kt` | 新增 `asSqlFragment()`；`MpjJoinSupport.parseJoin` 的 `join(...)` 模板与字符串 join（首参判定 + 模板）换口；`isFlagArgument` 排除片段实参 |
| `converter/LambdaUpdateConverter.kt` | `setSql` / `last` 换口；`unparsedSets` 收集；`isFlagArgument` 同上 |
| `converter/UpdateJoinConverter.kt` | `setSql`/`setApply` / `last` 换口；`unparsedSets` 收集；`isFlagArgument` 同上 |
| `converter/QueryWrapperParser.kt` | `parseRawTemplate`(apply/exists)、`parseHaving`、`inSql`、`compareSql`、`first`、`comment`、`parseApplyFunc`、`parseSelectFunc` 换口；`hasFlag` 排除片段实参 |
| `converter/MpjLambdaConverter.kt` | 字符串 join（判定 + 模板）、`parseAround` 换口；`isFlagArgument` 同上 |
| `model/UpdateDeleteStatement.kt` | `UpdateStatement.unparsedSets` |
| `ui/SqlPreviewService.kt` | `buildWarning` 追加 SET 解析失败提示 |
| `test/ui/SqlPreviewServiceUpdateTest.kt` | 复现用例 `testUpdateSetSqlConcatRuntimeValue` |
| `test/ui/SqlFragmentConcatTest.kt` | 新增 15 例：4 种拼接形态 + `{i}` 共存 + 联表 `setApply` + `last` 尾缀 + 全静态护栏 + 纯运行期警示 + 值位置反向护栏 + `apply`/`having`/`inSql`/`first`/`comment`/MPJ 字符串 join |

`DeleteJoinConverter` 未改：它没有 `last` 尾缀处理（见第四节勘误），`setAlias` 与 delete 目标都是值位置。

### 11.2 与计划的四处偏差

1. **`isStringConcat` 的判据改成「求值结果」而不是「PSI 字面量」**。计划假定的 `expr.type` 在 light fixture 下不可靠（实测拿不到 String），而静态前缀经变量追踪得到时表达式里没有字面量节点（`String p = "a = "; p + x`）。改为「任一操作数求值为 `Literal` 且 `raw` 带单引号」，变量追踪天然覆盖。
2. **布尔前缀启发式新增排除项**（计划没预见到）。`isFlagArgument` 把「首参是运行期值 且 args≥2」当布尔前缀，于是 `setSql("a = " + x, y)` 会把模板当 flag、把 `y` 当模板，SET 整段丢失。现在「带 fragment 的实参」不再算 flag。该规则在 4 份 `isFlagArgument` 副本与 `parseRawTemplate` 各改一处。
3. **同族范围比计划多两处**：MPJ 共享实现 `MpjJoinSupport.parseJoin`（`UpdateJoin`/`DeleteJoin` 走它，字符串 join 的首参判定层也依赖 Literal，join 会整段丢失）与 `parseAround` 片段。
4. **`DeleteJoinConverter.kt:42` 勘误**（见第四节）：那行是 `setAlias`，不是 `last`。

### 11.3 验证读数

复现（实现前，红）：`SqlPreviewServiceUpdateTest` 10 例 1 失败，失败信息逐字为
`expected:<UPDATE order_entity [SET amount = amount * ? ]WHERE status = ?> but was:<UPDATE order_entity []WHERE status = ?>`
——与真机截图同形，证明确实是「SET 整段消失」而不是渲染差异。

定向（实现后）：`SqlFragmentConcatTest` 15/0/0 + `SqlPreviewServiceUpdateTest` 10/0/0，合计 25/0/0。

反向验证三组（探针均带 `RED PROBE` 标记，验证后已全部还原，`grep -rn "RED PROBE" src/` 零命中）：

| 回退点 | 读数 |
| --- | --- |
| `asSqlFragment()` 的运行期分支改成 null（消费端失效） | 24 例 12 失败：11 个拼接用例 + 复现用例；值位置护栏、全静态护栏、警示用例仍绿 |
| `fragmentOf` 首行直接 return null（求值端失效） | 25 例 13 失败：12 个拼接用例 + 复现用例 |
| `buildWarning` 的 SET 提示分支置空 | 15 例 1 失败：恰为 `testSetSqlPureRuntimeFragmentWarns` |

### 11.4 实施期命中的历史坑

改 `Placeholder` 写法后，**未重编译的旧测试类成片 `NoSuchMethodError: Placeholder.<init>(String)`**（19 例，集中在 converter/psi 包）。这是「回归假失败清单第 0 条」的第四次命中，按定例 `./gradlew compileTestKotlin --rerun-tasks` 后复跑即恢复。以后凡是给带默认参数的数据类加字段，收口前必须先做这一步。

### 11.5 后续收口（原遗留项已关闭）

原列的五项在本轮全部处理，`fragmentFixtures()` 矩阵各补一行：

| 入口 | 做法 |
| --- | --- |
| `select(String...)` 表达式 | `ColumnRef.rawParams` + `selectColumnArgs`（**只换 SELECT 列表**；条件/排序/分组仍是标识符位置，保持 `Literal` 口径）+ `renderColumnRef` 在列列表渲染点按序登记 |
| `rawFirst` | `SelectStatement.rawFirstParams`，生成器在 SELECT 之前登记（`around` 合并 first 时来源同序拼接） |
| `rawAround` | `SelectStatement.rawAroundParams`；`parseAround` 改为携带来源的 `AroundFragment`（双字面量形态分别带，模板形态走 `renderSqlFragment`） |
| UNION 子查询 | `UnionClause.params`；构建改用 `SqlGenerator.generateWithParams` 带出子语句来源，生成器在 UNION 渲染点登记 |
| `last("LIMIT " + n)` | `parseLimit` 支持占位形态（`limit ?` / `limit ?, ?` / `limit ? offset ?`，含数字与占位混排）；`LimitClause` 本就持有 `SqlValue`，limit 渲染点已登记来源 |
| MPJ fill 子句 | **显式关闭（不登记）**：fill 段的 `IN (?)` 是运行时收集键集合的**合成占位符**，没有可命名来源；且它由面板在主 SQL 回填**之后**追加（`appendFillSection`），不可能让主语句来源前移。改为在 `MpjFillConverterTest.fill_结合主查询join` 钉住两条性质：主语句参数表只有主查询来源、主 SQL 占位符数与来源数相等、fill 段不混入主 SQL |

顺带修了一个前置缺陷：片段来源必须**注释/引号感知**地收集——`first("/*+ MAX_EXECUTION_TIME(" + ms + ") */")` 的 `?` 落在块注释里，旧实现会凭空多出一个来源。`fragmentOf` 与 `renderSqlFragment` 改为「先记标记位点、再按真实占位符位点过滤」。

仍未换口（位置本身不是片段，不该换）：`orderByStr` / `groupBy` 的字符串列（标识符位置）、xbatis/easy-query 等第三方链的片段位（各自语义待评估）。

真机复验由用户承接（本轮已跑过一轮 243 沙盒验收，见第十三节）。

读数（本轮收口）：`SqlFragmentConcatTest` 24/0/0（矩阵 15 个入口）+ `MpjFillConverterTest` 12/0/0；定向回归（converter/psi/ui 三包）**1467/0/0/0**（97 个类）；全量门禁（`testShards --no-configuration-cache --continue`，单路）**7973/3/8**，三条失败仍是 `InjectionGateTest`×2 与 `UnknownParamGateTest`×1（已在干净 dev HEAD 上基线复现，环境相关既有失败，见 11.6）。

### 11.6 用户验收追加：参数按位置错位（UI-039）

第一版交到用户手上后立刻暴露一个副作用：`SET amount = amount * ?` 的 `?` 没进参数表，面板按位置把用户输入的 `status` 值填到了第 1 个 `?` 上（预览成 `amount = amount * 2`、`WHERE status = ?` 反而填不上），且 `rate` 没有输入行。原计划把这当成「已知差距」（与 `{i}` 一样不登记）是判断错误——位置回填的语义要求 **SQL 里每个 `?` 都要有一个来源槽**，缺一个就会让后面全体前移。

修正做法：

1. 新增 `SqlFragment(text, params)`：片段不再只有文本，还带「`?` 按出现顺序的来源」。
2. `Placeholder.fragment` 由 `String?` 换成 `SqlFragment?`；`ExpressionEvaluator.fragmentOf` 在拼接时同步收集来源（嵌套片段复用其来源，字面量里用户自写的 `?` 记中性来源 `?`）。
3. `asSqlFragment()` 返回 `SqlFragment`；新增 `renderSqlFragment(fragment, values)` 统一处理 `{i}` 绑定 + 按**最终文本顺序**登记来源（`{0}` 在拼接中间时顺序是「先绑定值、后片段值」）。
4. 四个承载位：`SetClause.rawParams`、`Condition.Raw.params`、`UpdateStatement.rawTailParams`、`JoinClause.rawParams`；`SqlGenerator` 在 SET/WHERE/尾缀/JOIN 四处渲染点按序喂 `paramSink` + `paramHints`。
5. 副作用修正：`{i}` 绑定值（`setSql("... {0}", allocated)`、`apply("... {0}", allocated)`、MPJ join `{0}`）也从此进参数表——这是同一 bug 的另一半，之前同样会错位。

新增 6 条用例：demo 形态的 `unresolvedParams` 必须为 `[rate, status]` 且 `buildExecutableSql` 回填 `status=2` 只落 WHERE；`apply` 片段的顺序；`{0}` 与拼接混排的顺序 `[y, x]`；尾缀顺序 `[status, n]`；`setIncrBy` 运行期值的来源；值位置护栏（不得出现片段级中性来源）。

第二轮读数（片段求值 + 参数对齐 + 类级护栏三轮改动合起来）：`SqlFragmentConcatTest` 24/0/0 + `SqlPreviewServiceUpdateTest` 10/0/0 = **34/0/0**；全量门禁（`testShards --no-configuration-cache --continue`，单路）**7973/3/8**，3 条失败均为上段已证的环境相关既有失败。

环境相关既有失败（非本改动）：全量门禁 **7973 tests / 3 failed / 0 errors / 8 skipped**，三条失败全部是同一条断言的 EXPLAIN 执行链用例——`InjectionGateTest.testEXPLAIN无raw段_不弹门`、`InjectionGateTest.testEXPLAIN含raw段_同门控`、`UnknownParamGateTest.testEXPLAIN含UNKNOWN参数_同门控`，信息都是「确认后放行（应已进入执行链，实际状态 EMPTY）」。三条都做了基线对照：`git stash` 隐去本轮改动后在干净 dev HEAD 上单跑同类，**同样失败、同一条信息**（`InjectionGateTest` 18 例 2 红；`UnknownParamGateTest` 6 例 1 红），故判为环境/既有状态相关，不计入本任务的回归面。本轮新增的两个测试类在全量分片内分别 **24/0/0**、**10/0/0**。

## 十二、复盘：为什么既有用例没拦住这两个缺陷

### 12.1 缺陷一（SET 整段丢失）的漏测机制

三条证据叠在一起，让这个分支「看起来被覆盖、实际从未走到」：

1. **既有 setSql 用例全在转换器层，绕过了 psi 求值层**：`UpdateWrapperConverterTest.kt:35`、`UpdateDeleteMpjConverterTest.kt:93`、`UpdateJoinDeleteJoinConverterTest.kt:92` 都是手工构造 `WrapperArg.ValueArg(SqlValue.Literal(...))` 直接喂转换器。「拼接 → 占位符」的降级发生在 `ExpressionEvaluator`，转换器层永远拿到 `Literal`，于是 `as? SqlValue.Literal ?: return null` 的**失败分支一次都没被执行过**。
2. **psi 层的 setSql 用例模板全是字面量**：`testUpdateSetSqlPlaceholderParams`（`{0}` 那条）与 `testUpdateLastRawTailPassthrough` 的模板都是静态字符串，没有任何用例把运行期值拼进片段模板。
3. **失败是静默的**：该分支不抛异常、不产警示，套件里也没有「片段必须出现」这类否定性断言，所以没有任何既有断言会因此变红。

### 12.2 缺陷二（参数位置错位）的漏测机制

1. **片段入口的用例只断言 SQL 文本**：`SqlPreviewServiceTest` 里确有 `unresolvedParams` 断言，但都针对 `eq` 值、分页 offset/size、`in` 集合这些**普通参数**（如 `:89`、`:372`、`:583`）；`setSql`/`apply`/`having`/`inSql`/`join` 这些片段入口的用例一律只比 `result.sql`。SQL 文本对了、来源槽错了，测试看不出来。
2. **不变式测了「算术」，没测「管道」**：`PlaceholderCardinality.mismatchMarker`（`sql/PlaceholderPositions.kt:56`）本身有单元测试（`PlaceholderPositionsTest.kt:59-72`，2/2→null、3/2→标注），但它只是参数面板与复制链上的**软提示**，且**没有任何用例拿真实预览的 `(占位符数, 来源数)` 去喂它**。算得对、用得没覆盖，等于没防。
3. **值位置与片段位置的语义差异从未被固定**：没有用例断言 `eq("name", "pre" + x)` 只能渲染成单个 `?`，所以「修片段位」时把值位置改坏也没有护栏。

### 12.3 这是「类」缺口，不是「个」缺口

两个缺陷都源自同一条被复制的错误假设：片段位只接受完全静态字面量、片段里的 `?` 不需要来源槽。既有用例按**入口**逐个覆盖（setSql 一个、apply 一个、having 一个……），每个入口只测「字面量模板」这一条路径——同一条假设被复制了 12 次，也就漏了 12 次。

另外要注意两个缺陷需要**不同形态**的断言：SET 整段消失时「`?` 数 == 来源数」仍然成立（文本和来源一起没了），基数不变式拦不住；只有加上「片段文本必须出现」才拦得住。反过来，「片段出现了但来源没登记」时文本断言是通过的，只有基数不变式拦得住。

### 12.4 本次补的类级护栏

| 用例 | 拦什么 | 对原缺陷的判别力 |
| --- | --- | --- |
| `testPlaceholderSourceCardinalityInvariant` | 片段入口矩阵逐条断言「`?` 个数 == 来源个数」（复用生产的 `PlaceholderCardinality.mismatchMarker`，与本仓既有软检查同口径，两个方向都拦） | 修好片段但没登记来源（UI-039 原形态）必红 |
| `testFragmentEntriesDoNotSilentlyDropRuntimeTemplate` | 同一矩阵逐条断言「片段文本必须出现」 | 片段整段丢弃（UI-038 原形态）必红 |
| 6 条参数顺序用例 + `testMpjStringJoinIndexedParamOrder` | 来源顺序 == SQL 文本顺序（含 `{0}` 插在拼接中间、尾缀在 WHERE 之后、join 在 WHERE 之前） | 顺序错必红 |
| `testSetValuePositionConcatStaysSinglePlaceholder` | 值位置只能一个 `?` | 修片段位时改坏值位置必红 |
| `fragmentFixtures()` 矩阵夹具 | 新增片段入口只需加一行 | 降低下次复制整套断言的成本 |

矩阵当前覆盖 **15 个片段入口**：setSql 拼接 / setSql `{0}` / setIncrBy / last 尾缀 / apply / having / inSql / eqSql / 值位置（反向）/ MPJ 字符串 join / select 表达式 / first / `last("LIMIT "+n)` 占位 / MPJ around / UNION 子查询。`fill` 不在矩阵内（不是片段位，改由 `MpjFillConverterTest` 钉住合成占位符的性质，见 11.5）。

### 12.5 两条护栏的判别力实测（反向验证）

把两个缺陷的原始形态分别造回去，看哪条护栏会红——结果恰好互补，说明两条都必须留：

| 造回的形态 | 做法（探针，验证后已删） | 读数 |
| --- | --- | --- |
| UI-039：片段渲染了但来源不登记 | `SqlGenerator` 里注释掉 `SetClause.rawParams` 的喂入 | 24 例 **4 红**：`testPlaceholderSourceCardinalityInvariant` + 3 条 SET 侧顺序用例；「不静默丢弃」矩阵**仍绿** |
| UI-038：片段整段丢弃 | `asSqlFragment()` 对 `Placeholder` 返回 null（退回只认字面量） | 24 例 **17 红**：含 `testFragmentEntriesDoNotSilentlyDropRuntimeTemplate`；**`testPlaceholderSourceCardinalityInvariant` 仍绿** |

第二行是本轮最值得记住的一点：**片段整段消失时「`?` 数 == 来源数」依然成立**（文本和来源一起去掉），基数不变式对 UI-038 原形态完全瞎。反过来，片段渲染了但来源没登记时，文本断言全绿、只有基数不变式能看见。所以「先写复现用例看到红」只够证明单点，类级护栏必须成对存在。

### 12.6 计划本身的问题与后续约定

本计划第七节列了 11 条用例，但都是围绕「这次报的那一个场景」写的，没有一条站在不变式上——所以第一版修好了 SET 渲染却漏了参数槽，用户一试就中。约定两条：

1. **片段入口必须进矩阵**：以后新增/修改任何「原样拼进语句」的入口，`fragmentFixtures()` 加一行即可同时获得「不静默丢弃」与「参数基数」两条护栏；只写场景用例不算收口。
2. **报障用例之外必配类级断言**：单点复现用例只证明这一形态修好了；能不能防住同族，取决于有没有把那条被违反的不变式写成断言（本次是「`?` 数 == 来源数」与「片段文本必须出现」）。

## 十三、真机沙盒验收（2026-10-20，243 沙盒）

环境：dev 树 `runIde`（`.local/start-ui243.ps1`，sandbox `build/MPSV-MasterMerge-UI243`，`-PgifRobotServer=true`），打开 demo 根工程，`OrderController#discount` 光标落在 `setSql` 行，`Alt+Shift+Q` 开预览。证据帧在 `build/MPSV-MasterMerge-UI243-shots/`。

在役包核验（先做，防旧包误诊）：沙盒 `plugins/mybatis-plus-sql-visualizer/lib/mybatis-plus-sql-visualizer-2026.10.16.jar` mtime **12:24**（晚于 `f69f75fe`），`javap`（ms-21）可见 `SqlFragment(String, List)` 与 `Placeholder(String, SqlFragment)` 两个新符号。

| 判别点 | 结果 | 帧 |
| --- | --- | --- |
| SET 行出现 | `UPDATE t_order` / `SET` / `amount = amount * ?` / `WHERE status = ?` —— 与修前截图（无 SET）对照成立 | `img11_preview2.png` |
| 左侧 `rate` 输入行 | 「参数」区出现 `#rate` 输入框；`status` 仍在「动态条件」区 | `img11_preview2.png` |
| 填 `rate` 只改 SET | `#rate`=1.5 → `amount = amount * 1.5`，`WHERE status = ?` 不变 | `img12_rate_filled.png` |
| 填 `status` 只改 WHERE | `status`=2 → `WHERE status = 2`，SET 保持 `amount * 1.5` | `img13_status_filled.png` |
| 复制 SQL（已填值） | 剪贴板＝`UPDATE t_order⏎SET⏎    amount = amount * 1.5⏎WHERE status = 2` | `img15_copy_sql2.png` |
| 复制 SQL（rate 清空） | 剪贴板＝`... amount = amount * ? ...` —— 未填参数就是文本里的 `?`，无报错 | 剪贴板回读 |
| 执行计划（本方法为 UPDATE） | 通知「未执行 EXPLAIN：语句以 update 开头，不是可解释的查询」——既有闸门，与 `?` 无关 | `img16_explain.png` |
| 执行计划（含 `?` 的 SELECT，`list` 方法） | 方言闸门提示「所选数据源实际使用 MySQL，不是 PostgreSQL……本次点击未提交任何语句」——拦截理由是方言，不是占位符 | `img20_explain_select.png` |
| 验收期间异常 | 无 `?`/片段相关的报错或异常。需如实登记：12:35:54 有 **2 条 `TransactionGuardImpl` SEVERE**（`Last Action: ConvertToSql`，来自第二次 EXPLAIN 提交）；同一沙盒日志在 **2026-10-18 15:45:50 已有同形两处**（更早的构建/会话），属既有形态，与本改动无关，未登记为本任务缺陷 | 日志检查 |

三点说明：①`执行计划` 在本方法上必然不可执行（UPDATE 不在 EXPLAIN 白名单），所以补跑了同文件里带 `?` 的 SELECT（`list`）证明 EXPLAIN 链路对占位符无感；②那次 SELECT 的执行被「数据源实际方言与预览方言不一致」的既有闸门拦下（demo 数据源是 MySQL、模块推断为 PostgreSQL），属既有环境现象，与本改动无关，未登记为缺陷；③`?` 在复制产物里保留是设计行为（未填参数就是文本占位），不是错误。
