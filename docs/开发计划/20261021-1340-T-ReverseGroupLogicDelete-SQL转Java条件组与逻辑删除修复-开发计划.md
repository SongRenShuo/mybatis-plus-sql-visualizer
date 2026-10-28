# T-ReverseGroupLogicDelete SQL→Java 反向生成的条件组与逻辑删除修复 — 开发计划

- 日期：2026-10-21 13:40
- 任务编号：T-ReverseGroupLogicDelete
- 来源：用户 2026-10-21 真实项目报障（cyzc-platform `CarOccupyOrderService#buildBaseWrapper`）。预览 SQL 转 Java 后生成结果与原代码不一致，用户点出两处：「getDeleted 是否多余」与「nested vs and」。
- 性质：缺陷修复（反向链路生成语义错误，不涉及正向渲染）
- 实施分支：自 master 新建工作树 `mpsv-reverse-group-ld` / 分支 `t-reverse-group-ld`

---

## 一、现状与取证

用户在 MP 3.5.15（其 pom `mybatis-plus.version=3.5.15`）下预览 `LambdaQueryWrapper` 链，得到 SQL 后反解为 Java，产物与原始代码不符。用本地 MP 3.5.15 jar 直跑 `QueryWrapper.getSqlSegment()` 独立探针取证，确认三处：

### 缺陷 1：OR 组的子组渲染丢失 OR（语义错误，最高优先级）

`JavaCodeGenerator.renderGroupChildren`（`JavaCodeGenerator.kt:1104-1120`）在 OR 组内遇到子组时，先发 `.or()` 分隔符，再按子组连接符发 `.and(w2 -> ...)`。MP 的 `NormalSegmentList.transformList` 会把「上一个分隔符是 OR、新分隔符是 AND」判定为需替换——**OR 被静默改成 AND**。

实测（MP 3.5.15）：

| 写法 | `getSqlSegment()` |
|---|---|
| 生成器现状 `.or().and(w2 -> …)` | `(a = ? AND (b = ? AND c = ?))` ← OR 丢失 |
| 正确 `.or(w2 -> …)` | `(a = ? OR (b = ? AND c = ?))` |
| 改用 `.or().nested(w2 -> …)` | `(a = ? OR (b = ? AND c = ?))` ← 亦正确 |

最小复现 SQL：`SELECT * FROM t WHERE a = 1 OR (b = 2)`（带括号的 OR 分支即触发）。

未拦住的缘由：`JavaCodeGeneratorTest.kt:295` 的 OR 组内 AND 子组恰在索引 0，链首 AND 被丢弃故侥幸正确；`ReverseRoundTripCorpus` 仅有 `(a = 1 OR b = 2) AND c = 3` 这种组在前形态，缺 `X OR (Y …)`。

### 缺陷 2：反向链路不认识 `@TableLogic`，把 MP 注入条件当手写条件再生成一遍

`BaseEntity.deleted` 标 `@TableLogic`，MP 的 `SelectList` 走 `sqlWhereEntityWrapper` 时由 `table.getLogicDeleteSql(false, true)` 自动追加 `AND deleted = 0`（`AbstractMethod.java:243`）。反向生成器把这条注入条件当作手写条件，原样产出 `.eq(Entity::getDeleted, 0)` 与 `.select(…getDeleted)`。

根因：`JavaCodeGenerator.EntityTarget`（`JavaCodeGenerator.kt:34-38`）只有 `className`/`columnToField`/`subEntities`，**没有逻辑删除列字段**；而正向侧 `EntityResolver` 早已算出 `logicDeleteColumn`（`EntityResolver.kt:115-118`），只是没传给反向侧。

后果：`WHERE deleted = 0 AND deleted = 0` 语义无害但冗余；若项目把 `logic-not-delete-value` 改成非 0，硬编码的 `= 0` 会过滤掉全部数据。`.select(…getDeleted)` 同样冗余（MP 默认全列 select 本就含逻辑删除列）。

### 缺陷 3：子组的 `negated` 标记被丢弃

`renderGroupChildren` 对 `Condition.Group` 分支只看 `connector`，从不读 `negated`——`NOT (…)` 子组被渲染成普通括号组。

实测：目标 `a = 1 AND NOT (b = 2 OR c = 3)`，生成 `.nested(w2 -> …)` 得 `(a = ? AND (b = ? OR c = ?))`，**NOT 消失**；正确应 `.not(w2 -> …)` 得 `(a = ? AND NOT (b = ? OR c = ?))`。

## 二、方案

### 1. 子组渲染改为「按语义选方法 + 合并分隔符」

`renderGroupChildren` 子组分支：

- 子组 `negated` → `.not(wN -> …)`（NOT 必须保留，优先级高于连接词）。
- 子组未取反且父组连接符为 OR → 用 `.or(wN -> …)` 直接承载 OR 连接，**不再先发 `.or()` 分隔符**（避免 `.or()` + `.and()` 触发 MP 的 OR→AND 替换）。
- 子组未取反且父组连接符为 AND → 保持 `.nested(wN -> …)`（等价于原 `.and(wN -> …)`，且不依赖「链首 AND 被丢弃」这一巧合）。

即方法选择改为：`negated → "not"`，`父组 OR → "or"`，其余 `"nested"`；同时把「是否预发 `.or()` 分隔符」的条件改为「本子项不是 OR 组」。

### 2. 反向链路携带逻辑删除列并跳过注入条件

- `EntityTarget` 增加 `logicDeleteColumn: String? = null`（默认 null，全部既有构造点无需改动）。
- `SqlToJavaConverter.generateCode` 从 `EntityCandidate.tableInfo.logicDeleteColumn` 传入；批量与副实体路径同步（`generateBatchCode` / `findSubEntities`）。
- `JavaCodeGenerator` 新增过滤：条件列命中逻辑删除列且值为逻辑删除「未删除」值 → 跳过该条件；`selectColumns` 中的同名列一并跳过（MP 默认全列已含，显式再列无意义）。
- 跳过时在注释头追加一行说明，交代「该条件由 MP 依据 `@TableLogic` 自动注入，无需手写」，避免用户误以为生成器漏了条件。
- 逻辑删除值取设置页 `logicDeleteValue`（默认 `0`，与正向注入同源），不硬编码。

### 3. 不改什么

- 不放松 `nested` 的既有正确用法（用户当前那段 `… AND (x OR y) AND …` 本就等价，实测逐字节相同）。
- 不臆造逻辑删除列：实体无 `@TableLogic` 时行为完全不变。
- 不把「预览入参互斥」当缺陷修——`.eq(stationId,1)` 与 `.in(stationId,1)` 并存是预览时两个 DTO 字段都给了值，生成器忠实反映，不属本次范围。

## 三、改动面

| 文件 | 改动 |
|---|---|
| `reverse/JavaCodeGenerator.kt` | `EntityTarget` 加 `logicDeleteColumn`；`renderGroupChildren` 方法选择与分隔符合并；逻辑删除条件/列过滤；注释头说明 |
| `reverse/SqlToJavaConverter.kt` | 三处 `EntityTarget` 构造传入 `logicDeleteColumn` |
| `reverse/JavaCodeGeneratorTest.kt` | 组渲染用例改判据 + 新增 OR 子组/negated 子组用例 |
| `reverse/ReverseRoundTripCorpus.kt` | 补 `X OR (Y AND Z)`、`X AND NOT (Y OR Z)` 语料 |
| 新增/扩展 MP 真实编译断言 | 用 `GeneratedProjectionCompiler` 对上述形态做 `getSqlSegment()` 逐字断言（不经文本匹配） |

## 四、验收

- 自动化：`JavaCodeGeneratorTest`、`JavaCodeGeneratorJoinTest`、`JavaCodeGeneratorMapResultTest`、`SqlToJavaConverterTest`、`ReverseRoundTripGateTest`、`SqlReverseParserTest` 定向全绿。
- **反向判据（必做）**：逐条把修复分支临时改回旧形态 → 对应用例必须变红；恢复后复绿。三处缺陷各自单独验一次。
- 真实 MP 语义：新增用例用 `GeneratedProjectionCompiler` 编译生成代码并在 MP 3.5.15 上取 `getSqlSegment()`，断言 OR/NOT 保留、`deleted` 不再重复。
- 真机：用户在 cyzc-platform 复现同一 SQL，确认产物无 `.eq(getDeleted, 0)`、无 `.or().and(` 组合。

## 五、待验证前提

以下为实施前需先证伪的推断：

1. **`logicDeleteValue` 设置项在反向链路的可读性**——反向生成器目前是纯函数（不依赖 PSI/IDE），若直接读 `PluginSettings` 会破坏其可测性。若确认不便，退化为「硬编码 `0` 且仅在值确为 0 时跳过」，并在注释中标注。
2. **`@TableLogic(delval=…)` 自定义值**——实体注解上的自定义未删除值是否需要覆盖全局设置。先按全局设置实现，若 `TableInfo` 未携带注解级值则记为残留。
3. **副表（JOIN）逻辑删除**——`isSubLogicalDelete`（`JavaCodeGenerator.kt:389`）已有副表 ON 条件的逻辑删除容忍逻辑；本次主表过滤是否与它口径一致需实测确认，避免两处规则打架。

## 六、实施记录

| 日期 | 事项 |
|---|---|
| 2026-10-21 | 立项。用户真实项目报障，MP 3.5.15 独立探针取证三处缺陷。 |
| 2026-10-21 | 工作树 `mpsv-reverse-group-ld` / 分支 `t-reverse-group-ld`，自 master `6f3d0e83`。 |
| 2026-10-21 | 修复 1+3 落地：`renderGroupChildren` 子组方法选择改为 `negated → not` / `父组 OR → or` / 其余 `nested`；OR 组内非取反子组不再预发 `.or()` 分隔符（由 `or(wN -> …)` 直接承载连接）。取反子组仍预发 `.or()`——实测 `.or()` 后接 `.not()` 保留 OR，无需合并。 |
| 2026-10-21 | 修复 2 落地：`EntityTarget` 增 `logicDeleteColumn` / `logicDeleteValue`；`filterConditions` 增 entity 参数并过滤注入条件（`=` 且值为未删除值）；select 投影同步过滤；注释头补一行说明。逻辑删除值由 UI 层读 `ProjectPluginSettings.effectiveLogicDeleteValue()` 传入，`SqlToJavaConverter` 保持纯函数（前提 1 按退化方案处理，未在生成器内读设置）。 |
| 2026-10-21 | 定向：`reverse.*` 306 例 + `log.SqlToJavaPanel*` + `actions.*` **全绿**。 |
| 2026-10-21 | **反向验证（三探针，各自单独验）**：①撤掉组渲染修复（回旧 `.or()`+`.and()`）→ **恰 5 红**（OR 两条 + 嵌套组 + NOT + AND 组）；②仅撤 NOT 分支 → **恰 1 红**（子组取反用例）；③仅撤条件侧逻辑删除过滤 → **恰 2 红**（注入条件跳过 + 自定义值识别）。恢复后全部复绿。 |
| 2026-10-21 | 端到端复核（用户原始 SQL 同形态）：产物不再出现 `getDeleted` 与 `deleted = 0`，注释头出现「逻辑删除列 deleted 由 MP 依据 @TableLogic 自动追加」；`nested(w -> w.likeRight(...).or().likeRight(...))` 结构与原代码一致。 |
| 2026-10-21 | 残留（不在本次范围）：`between(getCreateTime, "1", "1")` 仍是字符串字面量——SQL 里只有 `'1'`，生成器无从推断 `LocalDateTime`，需手工替换；`@TableLogic(delval=…)` 实体级自定义值未覆盖（`TableInfo` 未携带，前提 2 记为残留）；JOIN 副表逻辑删除仍走既有 `isSubLogicalDelete`（ON 条件容忍），与本次主表过滤口径独立但互不冲突。 |

## 七、复盘：三个缺陷为什么没被测试拦住

用户 2026-10-21 追问「为什么这些问题没有通过测试用例覆盖」。逐条取证结论如下（全部以合并前 `6f3d0e83` 的测试代码为据）。

### 缺口 1：组渲染只验代码文本，不验 MP 语义

`JavaCodeGeneratorTest.嵌套组_渲染递归lambda` 断言的是**生成出来的 Java 字符串**长什么样：

```kotlin
code.contains(".nested(w -> w\n        .and(w2 -> w2\n        .eq(\"a\", 1)\n        .eq(\"b\", 2))\n        .or()\n        .eq(\"c\", 3))")
```

它把「`.or()` 后面跟 `.and(w2 -> …)`」当成**期望**写进了断言。缺陷被固化成规格——只要生成器保持这个形状，测试就绿。而 MP 在这串调用上实际渲染出的是 `a AND (b AND c)`，OR 消失了。文本断言对「MP 如何解释这串调用」完全无感。

同一用例还叠加了位置巧合：AND 子组恰在索引 0，链首的 AND 分隔符被 MP 丢弃，所以连「OR 被替换」这个现象都没显现。

### 缺口 2：往返门禁整条链没经过 Java 生成器

`ReverseRoundTripGateTest.ROUND_TRIP档_全语料往返规范化等价` 的链路是：

```
SQL → SqlReverseParser → toSqlStatement() → SqlGenerator → 与输入比对
```

`JavaCodeGenerator` 不在其中。另一个门禁 `全语料_生成代码不抛异常且非空` 只检查「非空、不抛异常」——它连 `getSqlSegment()` 都没取，自然看不见语义错。

两个门禁叠起来的效果是：**生成器可以任意错误地翻译条件树，只要产出非空字符串就算通过**。

### 缺口 3：语料里没有触发形态

合并前 `ReverseRoundTripCorpus` 的组形态只有 `(a = 1 OR b = 2)`、`(a = 1 OR b = 2) AND c = 3`、`NOT (a = 1 OR b = 2)`、`((a = 1 AND b = 2) OR c = 3)` 四条，**没有一条是「顶层 OR 后接括号组」**——而 `X OR (Y AND Z)` 正是 OR 被吞的触发形态。缺这条语料，即使门禁改对了也照样漏。

### 缺口 4：取反子组零覆盖

合并前 `JavaCodeGeneratorTest` 里 `negated = true` 只出现在**顶层** `NOT组_渲染not`（`Condition.Group(..., negated = true)` 作为 `conditions` 的直接元素）和 `In`/`Like` 的 negated 参数上，**没有任何一条用例把取反组放在父组内部**。`renderGroupChildren` 的子组分支从不读 `negated`，这个漏洞因此从未被触碰。

### 缺口 5：反向链路的 `@TableLogic` 覆盖为零

合并前 reverse 包测试里 `logicDelete` / `TableLogic` 出现次数为 **0**——`EntityTarget` 连字段都没有，测试自然也写不出相关断言。逻辑删除的测试全在正向链路（`SqlPreviewServiceLogicDeleteTest` 等），反向侧是空白区。跨链路的同一概念，只在一条链上被测，另一条链裸奔。

### 共同根因

三类断言强度依次递减，而这次三条缺陷恰好都落在最弱的一档：

| 断言方式 | 能发现什么 | 本次缺陷 |
|---|---|---|
| 文本 `contains` | 生成器输出形状变了 | 缺陷被写进期望值，永久绿 |
| 编译通过 | 代码能编译 | `deleted = 0` 重复条件可编译，绿 |
| 真实 MP 渲染语义 | 生成代码在 MP 上的实际行为 | 旧用例**完全未使用** |

结论：**「能编译」和「形状对」都不等于「语义对」**。凡涉及 Wrapper 条件树的生成，必须以真实 MP 的 `getSqlSegment()` 为准。

## 八、补测与治理（T-ReverseGroupCoverage）

| 补测项 | 覆盖缺口 | 位置 |
|---|---|---|
| `条件组语义矩阵_四维组合在真实MP上语义正确` | 缺口 1/3/4：父组 AND/OR × 子组 AND/OR × 取反 × 子组位置 = **16 组合**，每条编译并在 MP 上断言连接词与操作数 | `JavaCodeGeneratorTest` |
| `结构性守卫_生成代码不得出现or紧跟and组的反模式` | 缺口 1：把反模式钉成文本守卫（纯文本、快），与语义矩阵互补 | `JavaCodeGeneratorTest` |
| `逻辑删除_UPDATE与DELETE同样不重复生成条件` | 缺口 5：跨语句类型 | `JavaCodeGeneratorTest` |
| `逻辑删除_嵌套组内注入条件同样跳过` | 缺口 5：组内递归过滤 | `JavaCodeGeneratorTest` |
| `逻辑删除_IN单值形态同样识别` / `多值IN不是注入条件_必须保留` | 缺口 5：识别边界正反两侧 | `JavaCodeGeneratorTest` |
| `ANSI类型字面量_渲染为Java字符串而非裸拼` | 新发现：`DATE '…'` 裸拼致编译失败 | `JavaCodeGeneratorTest` |
| `派生表无实体上下文_明确报错` / `有实体上下文_正常生成` | 新发现：`FROM (SELECT …)` 生成不可编译类名 | `JavaCodeGeneratorTest` |
| `Java生成闭环_条件段连接词签名与原SQL一致` | **缺口 2 根治**：SQL → 反解 → 生成 Java → 真实 MP 渲染 → 连接词签名与原 SQL 比对，全语料 35 条 SELECT 逐条验证 | `ReverseRoundTripGateTest` + `GeneratedProjectionCompiler.sqlSegmentOf/connectorSignature` |
| 语料补 4 条（`X OR (Y AND Z)` / `X OR (Y)` / `X AND NOT (Y OR Z)` / `X OR NOT (Y OR Z)`） | 缺口 3 | `ReverseRoundTripCorpus`（ROUND_TRIP 52 → 56） |

补测过程中新门禁直接抓出两处**此前未知**的产品缺陷，均已修复：

- `javaValue` 对 ANSI 类型字面量（`DATE '2024-01-01'` / `TIMESTAMP '…'`）原样裸拼进 Java，产生未闭合字符字面量，编译失败。修：识别前缀，只取引号内值渲染为 Java 字符串。
- `FROM (SELECT …) t` 派生表无法推断实体类名，生成 `QueryWrapper<(SELECT …)>`，编译失败。修：`generate` 入口 `requireConcreteTable` 明确抛 `SqlReverseException`（有实体上下文时不受影响）。

反向验证（每条新用例单独撤修复验红）：

| 探针 | 结果 |
|---|---|
| 撤组渲染修复 + NOT 分支 | **7 红**（含语义矩阵、Java 生成闭环门禁） |
| 撤 ANSI 字面量处理 | **2 红**（ANSI 用例 + 闭环门禁） |
| 撤条件侧逻辑删除过滤（Simple+In） | **5 红**（注入跳过 / UPDATE / DELETE / IN 单值 / 组内） |

定向终局：`reverse.*` + `log.SqlToJavaPanel*` + `actions.*` **全绿**。
