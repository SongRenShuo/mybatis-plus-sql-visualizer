---
name: project-update-setsql-concat-gap
description: setSql 拼接实参致 UPDATE 整段 SET 丢失（UI-038）+ 片段 `?` 未登记致参数错位（UI-039）——SqlFragment(text+params) 四处载体按序喂 paramSink；片段位 vs 值位置的分界、矩阵式类级护栏（基数+不丢弃缺一不可）与 15 个入口清单
metadata:
  node_type: memory
  type: project
  originSessionId: session_85f51c5de37082b9d79ffa3f58bb93e2
---

# setSql 拼接实参：SET 丢失（UI-038）与参数错位（UI-039）

2026-10-20 用户在 `mybatis-plus-demo`（8081）真机复现 `OrderController#discount`：

```java
updateWrapper.setSql("amount = amount * " + rate);
updateWrapper.eq("status", status);
orderMapper.update(null, updateWrapper);
```

第一轮症状：预览只出 `UPDATE t_order` + `WHERE status = ?`，SET 整段不见（非法 SQL）——**UI-038**。
修完后用户接着测出：参数表把 `status` 的值填到第一个 `?`（`amount = amount * 2`），`rate` 没有输入行——**UI-039**。
计划文档 dev 树 `docs/开发计划/20261020-1013-T-UpdateSetSqlConcat-*`（§11 实施记录、§12 复盘、§13 真机验收），台账 `docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md` 的 UI-038/UI-039 两行。

## 状态（20261020）

dev 已提交 **`f69f75fe`**（修复+测试）、**`29e2f48e`**（真机验收记录）、**`f33141fd`**（异常表述修正）、**`58dd0c23`**（计划 §11.5 遗留收口：select/first/around/UNION/`last("LIMIT "+n)` 全部补齐，11 文件），均未 push。收口轮读数：`SqlFragmentConcatTest` 24/0/0、`MpjFillConverterTest` 12/0/0、定向回归 **1467/0/0/0**（97 类）、全量门禁仍 **7973/3/8**（同样三条既有环境失败）。引用前仍先 `git log --oneline -3` 实测。

## 根因（UI-038）

1. `evaluatePolyadic` 规则是「任一段不可静态求值 → **整段**返回 Placeholder」，静态前缀 `"amount = amount * "` 在此丢弃（`rate` 是方法参数、无初始化式）。
2. `LambdaUpdateConverter` 的 `SET_SQL` 分支只认 `asValue() as? SqlValue.Literal` ⇒ SET 不产生。
3. `SqlGenerator.generateUpdate` 仅 `sets` 非空才输出 `SET` ⇒ 非法 SQL。

排除项：wrapper 识别正常（WHERE 在）；`update(null, wrapper)` 不触发实体展开（`WrapperLocator` 首参 null 明判非实体更新）；链收集正常。

## 根因（UI-039）：位置回填要求每个 `?` 都有来源槽

面板/复制链是**按位置**把 `unresolvedParams[i]` 对到 SQL 里第 i 个 `?`。片段产生的 `?` 若未登记来源，后面的条件**整体前移**。原方案「片段里的 `?` 与 `{i}` 一样不进参数表」是**错的**——`{i}` 绑定值（`setSql("... {0}", allocated)`、`apply`、MPJ join）本来也有同一个 bug 的另一半。

## 关键分界：值位置 vs 片段位置（本任务最易做错处）

- 值位置（`eq("name","a"+b)`、`set(col,"a"+b)`）：MP 把整串当**一个 JDBC 参数**，只能渲染 `?`。
- 片段位置（setSql/setApply/apply/exists/last 尾缀/having/inSql/eqSql 族/first/comment/applyFunc/selectFunc 模板/MPJ 字符串 join/around/select 表达式/UNION/`last("LIMIT "+n)`）：MP 把整串当**原始 SQL** 拼接，静态段必须保留、运行期段用 `?`。
- 标识符位置（`orderByStr`/`groupBy` 的字符串列、`setAlias`、delete 目标表别名）**不换口**，保持 Literal 口径。

所以**不能改 `evaluatePolyadic` 的全局返回值**（值位置会变 `name = a?`）。测试里有专门的值位置护栏用例（含「值位置不得出现片段级中性来源」）。

## 最终形态

- `model/SqlFragment.kt`：`SqlFragment(text, params)`——片段文本 + 与其中 `?` 一一对应的来源（`placeholderPositions` 口径，字符串/注释里的 `?` 不算）。
- `SqlValue.Placeholder(source, fragment: SqlFragment? = null)`。
- `ExpressionEvaluator.fragmentOf`：静态段取 `Literal.unquoted`、嵌套片段复用其来源、纯运行期段写 `?` 并记 `source`。**来源收集必须注释/引号感知**：先记「文本位点→来源」标记，末尾按 `placeholderPositions(text)` 真实位点过滤——否则 `first("/*+ MAX_EXECUTION_TIME(" + ms + ") */")` 的 `?` 在块注释里会凭空多出一个来源（这是会话末尾补修的前置缺陷）。
- **`isStringConcat` 判据取「求值结果」而非 PSI 字面量**：`expr.type` 在 light fixture 下不可靠；改为「任一操作数求值为 Literal 且 `raw` 以 `'` 开头」——静态前缀由变量持有时（`String p = "a = "; p + x`）也能识别。数值算术不给 fragment（免得拼出 `1?`）。
- `converter/WrapperConverter.kt`：`WrapperArg.asSqlFragment(): SqlFragment?` + `renderSqlFragment(fragment, values)`（统一处理 `{i}` 绑定 + **按最终文本顺序**登记来源——`{0}` 在拼接中间时顺序是「先绑定值、后片段值」，实测 `[y, x]`）。MPJ 与单表的 `renderJoinSql` 都改为返回 `SqlFragment`。
- 承载 + 生成器登记点（各渲染顺序即登记顺序）：
  - `SetClause.rawParams`（SET，含 setIncrBy/setDecrBy 的 rawSql）
  - `Condition.Raw.params`（WHERE/ON，`renderCondition`）
  - `UpdateStatement.rawTailParams`（尾缀，WHERE 之后）
  - `JoinClause.rawParams`（JOIN，先于 SET/WHERE）
  - `ColumnRef.rawParams` + `selectColumnArgs`（SELECT 列表，`renderColumnRef`；**只换 select 分支**）
  - `SelectStatement.rawFirstParams` / `rawAroundParams`（`AroundFragment` 携带来源；around 合并 first 时来源同序拼接）
  - `UnionClause.params`（构建改 `SqlGenerator.generateWithParams` 带出子语句来源）
  - `LimitClause` 本就存 `SqlValue`：`parseLimit` 拆成 `parseLimitText`（纯数字口径不变）+ `parseLimitFragment`（`limit ?` / `limit ?, ?` / `limit ? offset ?`，含数字与占位混排）
- **布尔前缀启发式必须排除片段实参**（计划未预见）：`isFlagArgument` 原把「首参是运行期值且 args≥2」当布尔前缀，于是 `setSql("a = " + x, y)` 把模板当 flag、把 `y` 当模板 ⇒ SET 丢失。4 份 `isFlagArgument` 副本 + `parseRawTemplate` 的 `hasFlag` 各加「`Placeholder` 且 `asSqlFragment() != null` ⇒ 非 flag」。
- 安全网：`UpdateStatement.unparsedSets`，转换器放弃解析时记摘要（`setSql(sqlVar)`），`SqlPreviewService.buildWarning` 追加「有 N 处 SET 片段无法静态解析，已省略：…」（与 BlockAttack 警示并列，不互斥）。
- **MPJ fill 显式关闭（不登记）**：fill 段的 `IN (?)` 是运行时收集键集合的**合成占位符**、无来源，且面板在主 SQL 回填**之后**才追加（`appendFillSection`），不可能让主语句来源前移。改由 `MpjFillConverterTest.fill_结合主查询join` 钉住（主语句参数表只有主查询来源、主 SQL 占位符数与来源数相等、fill 段不混入主 SQL）。注意 fill 依赖 leftJoin 的 aliasToTable，无 join 时 `fillClauses` 为 null——UI 层冒烟用例里直接造 fill 会拿到 null。

## 计划勘误

`DeleteJoinConverter` **没有** `last` 尾缀处理（删除联表语法无条件尾位置）；`DeleteJoinConverter:42` 是 `setAlias` 主别名取值。

## 测试：矩阵式类级护栏（本轮最重要的方法论）

`test/ui/SqlFragmentConcatTest.kt` 用 `fragmentFixtures()` 矩阵（**15 个片段入口**）驱动两条断言：

1. `testPlaceholderSourceCardinalityInvariant`——逐条断言「`?` 数 == 来源数」（复用生产的 `PlaceholderCardinality.mismatchMarker`）。
2. `testFragmentEntriesDoNotSilentlyDropRuntimeTemplate`——逐条断言片段文本必须出现。

**两条护栏互补、缺一不可**（反向实测）：造回「渲染了但来源不登记」（注释掉 `SetClause.rawParams` 喂入）→ 24 例 **4 红**（基数+3 条 SET 顺序用例），不丢弃矩阵**仍绿**；造回「片段整段丢弃」（`asSqlFragment` 对 Placeholder 返回 null）→ 24 例 **17 红**，**基数不变式仍绿**（文本和来源一起消失，计数照样相等）。→ 「先写复现用例看到红」只够证明单点，类级不变式必须成对存在。

另有 7 条参数顺序用例（demo `[rate, status]` 且 `buildExecutableSql` 填 `status=2` 只落 WHERE；`{0}` 混排 `[y, x]`；尾缀 `[status, n]`；setIncrBy；MPJ join `[stationId, id]`；值位置单 `?` 护栏）。

## 为什么既有用例没拦住（复盘结论，写进计划 §12）

- setSql 用例全在**转换器层**手工喂 `ValueArg(Literal)`，而「拼接→占位符」的降级在 PSI 求值层——那个 `as? Literal ?: return null` 失败分支从未被执行；PSI 层两条 setSql 用例模板也都是字面量。失败还是静默的。
- 片段入口用例只比 `result.sql`；`SqlPreviewServiceTest` 里的 `unresolvedParams` 断言都针对 `eq` 值/分页/`in` 集合。
- `PlaceholderCardinality` 有单元测试（2/2→null、3/2→标注），但**没有任何用例拿真实预览的 (占位符数, 来源数) 去喂它**——算术测了、管道没测。
- 本质是「类」缺口：同一条错误假设被按入口复制了 12 次。

## 读数

- 复现用例修前 1 红，失败信息逐字 `expected:<UPDATE order_entity [SET amount = amount * ? ]WHERE status = ?> but was:<UPDATE order_entity []WHERE status = ?>`（与真机截图同形）。
- 定向 **34/0/0**（`SqlFragmentConcatTest` 24 + `SqlPreviewServiceUpdateTest` 10）；定向回归（converter/psi/ui 三包）**1464/0/0/0**（97 类，含 `compileTestKotlin --rerun-tasks`）。
- 全量门禁（`testShards --no-configuration-cache --continue`，单路）**7973/3/8**；3 条失败＝`InjectionGateTest`×2 + `UnknownParamGateTest`×1，信息都是「确认后放行（应已进入执行链，实际状态 EMPTY）」，**三条都做了干净基线对照**（`git stash` 在 dev HEAD 上单跑同样失败同一条信息）。
- ⚠️ 这三条**不是只能挂账的环境问题**：机制已查清——UI-035 把 `submitExplainDirect` 的「无绑定数据源」分支改成弹错误框后直接 `return`（不再 `showLoading()`），而这三条用例的 headless 面板没有目标数据源 ⇒ 状态停在 `EMPTY`；用例期望还停在 UI-035 之前。修法＝测试侧挂 `explainTargetForTest`（`ExplainTarget` 带 `getUrl()`，见 `ExplainSideQueryPanelTest`）。详见 [[project-dialect-gate-exit]] 同节。
- 遗留收口后：`MpjFillConverterTest` 12/0/0、`SqlFragmentConcatTest` 24/0/0；定向回归与全量**会话结束时未跑完**。

## 真机沙盒验收（20261020，243 沙盒，由用户要求后我代跑一轮）

- 在役包核验：沙盒 `plugins/.../mybatis-plus-sql-visualizer-2026.10.16.jar` mtime 12:24，`javap`（ms-21）可见 `SqlFragment(String, List)` 与 `Placeholder(String, SqlFragment)`。
- GUI：`SET amount = amount * ?` + 左侧 `#rate` 行；rate=1.5 只改 SET、status=2 只改 WHERE；复制 SQL 两态剪贴板正确（未填保留 `?`）。
- 执行计划：UPDATE 被既有白名单挡下（「语句以 update 开头」），含 `?` 的 SELECT 被「数据源实际 MySQL ≠ 预览 PostgreSQL」方言闸门拦下——**都与 `?` 无关**，不登记缺陷。
- 日志：12:35:54 有 2 条 `TransactionGuardImpl` SEVERE（`Last Action: ConvertToSql`），同沙盒日志 2026-10-18 15:45:50 已有同形两处 ⇒ 既有形态。**不要写成「日志无异常」**。
- 点「复制 SQL」用 pyautogui 直接点坐标**没反应**（按钮密集，易点空），改用 `robot.click_component("//div[@accessiblename='复制 SQL']")` 连点两次才生效；`#rate`/`status` 输入框用区域坐标点击 + `gifkit.paste` 写入。

## 边界

- `?` 只是近似：真实执行时 MP 直接字符串拼接、不绑定参数（MP 的 `{i}` 才是真绑定）。
- 片段拼接在用户代码里是 SQL 注入风险，插件不阻断。
- 用户 20261020 明令：**后续真机验收由用户自己接手**，代理不再代跑。

相关：[[project-pending-plans]]、[[pitfall-regression-false-failure-flakes]]（本次两次命中其第 0 条）、[[pitfall-fake-green-test-assertions]]、[[pitfall-guard-covers-audited-targets]]（「基数不变式对整段丢弃是瞎的」＝守卫看不见目标站点的又一形态）。
