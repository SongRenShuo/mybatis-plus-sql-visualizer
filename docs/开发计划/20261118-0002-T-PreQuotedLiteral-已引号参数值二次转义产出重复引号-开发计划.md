# T-PreQuotedLiteral：已引号参数值二次转义产出重复引号（预览 `''''` bug）——开发计划

- 日期：2026-11-18
- 来源：用户 bug 反馈「xml 预览 sql，int 类型输入非数值会出现重复的 `''''`」
- 状态：已实施（同批完成）
- 域归属：`docs/架构设计/02-核心流程详解.md` 预览参数回填节

## 一、现象与复现（先红读数，headless 面板链实测）

XML 预览语句 `SELECT * FROM t_order WHERE id = #{id}`，面板参数行输入后渲染产物：

| 输入值 | int 有类型（paramTypes=id→NUMBER） | 类型未知（无 paramTypes，quote 兜底 true） |
|---|---|---|
| `abc` | `abc`（裸渲染，符合设计） | `'abc'`（兜底引号，符合 T-M29.4 设计） |
| `'abc'` | `'abc'`（raw 透传） | **`'''abc'''`（引号翻倍）** |
| `''` | `''`（raw 透传） | `''''''`（翻倍） |
| `'` | `'`（raw 透传） | **`''''`（正好四引号，与报障形态吻合）** |

复现测试：`SqlPreviewPanelIntParamQuotingReproTest`（收口后改名为行为断言用）。

## 二、根因

渲染链对**用户输入已含引号**的值一律再做 `replace("'", "''")` 转义 + 外层包引号：

1. **面板回填链** `SqlPreviewPanel.renderSingleValue`（`ui/SqlPreviewPanelParamPanel.kt:2595-2610`）：
   `quote=true` 分支与数字启发式未命中分支都是 `"'$escaped'"`。int 参数在**类型信息缺失**时
   （mapper 接口不可解析 / Map 入参 / namespace 失配）`xmlBackfillHints` 走 `quote ?: true`
   兜底引号档（`ui/XmlParamBackfillHints.kt:42`），命中该分支——用户为非数值手工补的引号被
   当成值内容再转义 → `'''abc'''` / `''''`。
2. **可执行 SQL 链** `ParameterReplacer.literal`（`sql/ParameterReplacer.kt:625-671`）：
   STRING 与「NUMBER 收到非数字回落」支同样 `"'$escaped'"`——复制/执行产物与预览翻倍形态一致。

引号翻倍不是新现象：`RollbackSqlGenerator.kt:578` 注释已留痕「再转义产出 `''''` 四引号」，
但当时只修了回滚链，回填/可执行链没有同步口径。

## 三、修复方案：预引号字面量识别（T-PreQuotedLiteral）

新增 `sql/PreQuotedLiteral.kt`（全链唯一实现，与 `SqlDateLiteral` 同位）：

- `isComplete(value)`：trim 后长度 ≥ 2 且以 `'` 开头、以 `'` 结尾 → 用户已手工 quoting；
- `stripQuotes(value)`：完整时剥一层外引号（供 LIKE / inQuoted 上下文重拼）。

应用点（同批修复，语义对齐；实施中比初案多补三处同构副本）：

1. `renderSingleValue`：`quote=true` 与启发式分支，完整引号值**原样渲染**（不转义不包裹）；
   `quote=false` 分支本来就是 raw 透传，不动；temporal 分支在前且带引号值不匹配日期形态会
   自然落回，不冲突。
2. `renderParamValue` LIKE 分支：完整引号值 → 剥外引号 + 内层按既有语义转义 → 拼 `%`
   （`'abc'` → `'%abc%'`；`O'Brien` 行为不变 `'%O''Brien%'`）。
3. `ParameterReplacer.renderValue`（`?` 可执行链）：同 renderSingleValue 口径。
4. `ParameterReplacer.renderNamedValue`（`:name` 命名参数链）：同上。
5. `ParameterReplacer.literal`（运行期占位链）：STRING 与 NUMBER 回落支同口径——非 inQuoted
   完整引号值原样；inQuoted（占位落在用户自己的引号串内）剥外引号再转义。
6. `FieldFillSimulation.literal`（批量列级模拟值链）：预引号原样，**置于安全闸门之后**——
   分号 / 反斜杠 / 控制符拒绝语义不变（`'; DROP TABLE t; --'` 仍拒绝）。

### 保留行为（登记不修）

- 孤引号 `'`（长度 1）：不视为完整字面量，维持现行为 `''''`——产出是**合法 SQL**（含一个
  引号字符的字面量），改成原样 `'` 反而产出断引号残 SQL；键入完整 `'abc'` 后即走原样通道。
- `quote=false`（NUMBER/数值列）值内引号 raw 透传：与「数值列恒原样」语义一致。

## 四、待验证前提（实施者先证伪再动手）

- [x] 「翻倍来自 `renderSingleValue` 的转义+包裹」——已由复现矩阵实证（§一 后两列）。
- [x] 修复不影响 `O'Brien` 类真实撇号转义：`O'Brien` 不以 `'` 开头，不判完整 → 仍转义
  `'O''Brien'`（测试钉住）。
- [x] `SqlDateLiteral.renderByShape` 对带引号值返回 null（形态正则不含引号）→ temporal
  先行分支不与修复抢路。

## 五、实施记录

- `sql/PreQuotedLiteral.kt`：新增（纯函数，零平台依赖）。
- `ui/SqlPreviewPanelParamPanel.kt`：`renderSingleValue` / `renderParamValue` LIKE 分支接入。
- `sql/ParameterReplacer.kt`：`renderValue` / `renderNamedValue` / `literal` 三处接入。
- `model/FieldFillSimulation.kt`：`literal` 接入（闸门后透传）。
- 实施中发现：XML 面板链 `xmlBackfillHints` 对未知类型参数恒兜底 `quote=true`
  （`?: true`，T-M29.4 设计），`renderSingleValue` 的数字启发式分支在该链不命中——
  首版面板测试「未知参数裸数字裸渲染」断言按此修正为兜底引号档（设计行为钉住，非实现缺陷）。
- 测试：`SqlPreviewPanelPreQuotedLiteralTest`（面板链 8 场景）+
  `ParameterReplacerTest` 追加 5 断言 + `FieldFillSimulationTest` 追加预引号/闸门 3 断言。
- 收口读数：定向 `./gradlew test --tests <三链测试类>` 全绿
  （修复前 §一 矩阵即红读数；`O'Brien` 转义 / 注入拒绝 / 空值占位等既有断言不改绿）。

## 六、验证

- 定向：面板链 8 场景 + replacer 链 5 断言全绿；
- 反向验证：`O'Brien` / 裸数字 / 空值占位等既有行为断言不改绿（防「全改原样」的过度修复）。
