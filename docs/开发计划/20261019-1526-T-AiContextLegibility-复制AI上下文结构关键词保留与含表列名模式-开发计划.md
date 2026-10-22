# T-AiContextLegibility：复制 AI 上下文的结构可读性与含表列名模式

2026-10-19。来源：用户在 dev 分支实测「复制为 AI 上下文」后反馈，v2 输出块几乎不可读。用户提供的实际产物：

```text
-- SQL Visualizer AI context v2
-- dialect: MySQL
-- identifiers hidden; values masked=1; local-only; no acquisition
-- SQL (strict projection)
SELECT identifier_1.identifier_2, identifier_1.identifier_3, ..., identifier_6.identifier_3 = identifier_1.identifier_2 identifier_14 identifier_1.identifier_2 = ?
-- EXPLAIN: not obtained (no matching structured summary)
-- Static schema: tables=1; columns=4; identifiers hidden; static preview metadata only
```

## 一、现象与问题定性

两个相互独立、但叠加后让功能失去意义的问题：

1. **结构词被匿名化**。除行首 `SELECT` 外，`FROM`、`WHERE`、`AND`、`AS`、`JOIN`、`ORDER BY` 等全部变成 `identifier_N`，与表名/列名共用同一编号空间。接收方无法判断 select 列表在哪结束、有没有 JOIN、WHERE 从哪开始——连「这是几条条件的查询」都要靠数 `=` 猜。根因是 `63f37cdf`（2026-10-16）为了堵住 `COUNT`/`JSON` 这类「可作裸列名的关键词」泄漏，把整张 60 词 `KEYWORDS` 白名单删掉，只保留行首语句种类。
2. **只有「全隐藏」一个出口**。`ShareContentProjection.sql` 早已有 `includeIdentifiers` 参数，但没有任何 UI 调用方传 `true`；v1 `AiContextExport.build`（保留表列名、只把字面量换 `?`）也只剩测试在用。用户想「让 AI 帮我看看这条 SQL」时没有任何可用形态。

结论：不是崩溃型缺陷，是**产品可用性缺陷**——隐私投影做到了，但把 AI 唯一能利用的结构信息也一起牺牲了。推荐两条一起落地：先恢复结构关键词（默认出口不再残废），再补一个显式「含表列名」的第二出口（用户按剪贴板去向自选）。

## 二、现状机制（代码证据）

1. 唯一菜单入口：`SqlPreviewPanelResultView.kt:88` 的 `panel.aiContext` → `copyAsAiContext()`；「更多」菜单当前八项，顺序被测试精确锁定（`SqlPreviewTopRowLayoutTest.kt:161-179`）。
2. v2 组装：`SqlPreviewPanelExecution.kt:203-239`。`AiContextExport.buildV2(sql, dialect, key, plans, schema)` 内 **`ShareContentProjection.sql(sql)` 使用默认参数**（`AiContextExport.kt:43`），即隐藏全部标识符。
3. 头部硬编码：`AiContextExport.kt:64-65` 固定输出 `identifiers hidden`。
4. 单词处理：`ShareContentProjection.kt:96-111`：
   - `VALUE_KEYWORDS = NULL/TRUE/FALSE` → `?`（计数）；
   - 其余单词只有「`out` 为空且属于 `STATEMENT_KINDS`」才原样保留；
   - 其他一律 `identifier(word)`，包括所有结构关键词。
5. `includeIdentifiers` 现状：`ShareContentProjection.kt:16` 参数、`:22-23` 非引号标识符原样输出、`:58-76` 引号内容**无论模式都只输出占位词 `identifier`**（双引号在部分模式下可能表示值，原文永不复制）。
6. 已锁定该行为的测试：
   - `AiContextV2ExportTest.kt:18-23`：`assertFalse(block.contains("secret_"))`，表名列名必须消失；
   - `ShareContentProjectionTest.kt:69-77`：`count/sum/first/next/json` 等「关键词同名裸列」必须继续匿名；
   - `AiContextV2OfflineBoundaryTest.kt`：截取 `copyAsAiContext()` 到 `exportPortableSql()` 之间的源码，要求含 `buildV2(` 与剪贴板写入、且不含任何 SQL 执行/HTTP/JDBC/MCP 调用。
7. `63f37cdf` 的原始理由仍然有效：`COUNT`、`SUM`、`JSON`、`FIRST`、`NEXT` 等词可以合法地作为裸列名出现在真实 SQL 中，按旧白名单会原样泄漏。

## 三、待验证前提（实施前逐条证伪）

1. **结构词白名单的词面安全**：需逐词确认在目标方言（MySQL/PostgreSQL/Oracle/DM 等）中不能作为未加引号列名使用。本计划只收语法保留字（见 §四 A），`COUNT/SUM/AVG/MIN/MAX/JSON/FIRST/NEXT/KEY/TYPE/NAME/VALUE/STATUS` 一律不进白名单。若某方言允许某个词作列名，按「宁可不保留」处理。
2. **引号标识符不恢复是可接受限制**：`includeIdentifiers=true` 时 `"user"` 仍只会输出 `identifier` 占位。PostgreSQL/SQL Server 风格 SQL 的表列名无法显示。建议本期接受，并在含名模式的 toast 与 tooltip 中明说；若要恢复，必须引入方言语法判断，超出本计划范围。
3. **菜单断言是精确列表**：`SqlPreviewTopRowLayoutTest` 断言八项且顺序稳定，新增第九项必须同步修改断言与注释文案；先 grep 是否还有按数量/下标断言「更多」菜单的测试。
4. **离线边界守卫的切片方式**：若把两个入口拆成「`copyAsAiContext(includeIdentifiers)` 单函数 + 两个菜单回调」，`between(copyAsAiContext, exportPortableSql)` 仍覆盖两次 `buildV2(` 与 `UiTextClipboard.copy`；若新增独立函数且写在切片之外，必须同步扩守卫。禁止为了让守卫变绿而删断言。
5. **头部与静态摘要的模式化不破坏既有测试**：`buildV2` 新增参数必须带默认值 `false`，隐藏模式除关键词恢复外输出不变；v1 `build` golden 字节不受影响。
6. **静态摘要列名长度**：`StaticSchema.columns` 可能很多，含名模式需要列数上限与截断标记（建议最多 50 列，超出追加 `…(+N)`），并仍受 16 KiB 整段省略预算约束。
7. **编号空间**：恢复关键词后 `identifier_N` 的编号会整体位移；现有测试只断言 `contains("identifier_")`，不得新增依赖具体编号的断言。

## 四、方案设计

### A. 结构关键词保留（默认 v2 出口生效）

在 `ShareContentProjection` 新增保守白名单 `STRUCTURAL_KEYWORDS`。白名单分两层：

**第一层：确定保留**（MySQL/PostgreSQL/Oracle 等主流方言均为保留字，未加引号不能作列名）：

- 语句：`SELECT`、`INSERT`、`UPDATE`、`DELETE`、`WITH`、`EXPLAIN`（行首与子查询开头都保留，解决子查询同样被铲平的问题）；
- 子句/连接：`FROM`、`WHERE`、`AND`、`OR`、`NOT`、`AS`、`ON`、`IN`、`IS`、`JOIN`、`LEFT`、`RIGHT`、`INNER`、`OUTER`、`FULL`、`CROSS`；
- 集合/分组/排序：`UNION`、`ALL`、`DISTINCT`、`GROUP`、`BY`、`ORDER`、`HAVING`、`ASC`、`DESC`；
- 分页/表达式：`LIMIT`、`OFFSET`、`CASE`、`WHEN`、`THEN`、`ELSE`、`END`、`EXISTS`、`BETWEEN`、`LIKE`；
- DML 结构：`INTO`、`VALUES`、`SET`。

**第二层：待核实**（各方言保留级别不一致，实施时逐词查目标方言；核实前一律按「不保留」处理）：`USING`、`NATURAL`、`LATERAL`、`OVER`、`PARTITION`、`WINDOW`、`FILTER`、`ROWS`、`ROW`、`ONLY`、`FETCH`、`TOP`、`ILIKE`、`ESCAPE`、`RETURNING`、`RECURSIVE`。

值关键字维持现状：`NULL`、`TRUE`、`FALSE` → `?`。

明确不进白名单（继续匿名）：`COUNT`、`SUM`、`AVG`、`MIN`、`MAX`、`COALESCE`、`CAST`、`FORMAT`、`JSON`、`FIRST`、`NEXT`、`KEY`、`INDEX`、`TYPE`、`NAME`、`VALUE`、`STATUS`、`ROLE`、`USER`、`DATE`、`TIME`、`TIMESTAMP`。

实现：`ShareContentProjection.kt:101-110` 的判定改为「行首语句种类 或 属于 `STRUCTURAL_KEYWORDS`」→ 原样输出，否则 `identifier(word)`。头部文案保持 `identifiers hidden`（仍是事实），不改既有 key。

隐私边界：白名单词在目标方言中都是保留字，未加引号时不能作为列名；引号包裹的 `"from"` 仍走引号分支被隐藏。该取舍需在计划验收中写明。

### B. 第二出口：含表列名模式（显式选择，不设默认）

- 新增菜单项 `panel.aiContext.identifiers`，紧跟现有 `panel.aiContext`：
  - zh：`复制为 AI 上下文（含表列名）`
  - en：`Copy as AI Context (include table/column names)`
- `copyAsAiContext` 增加参数 `includeIdentifiers: Boolean = false`，第二个菜单回调传 `true`。默认参数保证既有测试与调用方不变。
- `AiContextExport.buildV2` 增加同名参数（默认 `false`），透传给 `ShareContentProjection.sql(sql, includeIdentifiers)`。
- 头部按模式输出：
  - 隐藏：`-- identifiers hidden; values masked=N; local-only; no acquisition`（现状）
  - 含名：`-- identifiers visible as written (quoted tokens stay hidden); values masked=N; local-only; no acquisition`
- 静态摘要按模式输出：
  - 隐藏：`-- Static schema: tables=1; columns=4; identifiers hidden; static preview metadata only`（现状）
  - 含名：`-- Static schema: table=<name>; columns=<n>: <name1>, <name2>, ...`（最多 50 列，超出 `…(+N)`；表名来自当前 `PreviewResult.tableName`）
- 新增 toast key `ai.context.v2.copied.identifiers`：
  - zh：`AI 上下文 v2（含表列名）已在本地复制（已遮蔽 {0} 个值；含表名/列名，粘贴到外部服务前请确认；未请求数据库或网络）`
  - en：`AI context v2 (with table/column names) copied locally ({0} values masked; includes table/column names - confirm before pasting into an external service; no database or network request)`
- 不新增设置项、不记忆上次选择：每次复制是一次显式隐私决策，避免配置迁移与「默认放宽」争议。

### C. 菜单与文案

- `moreActions()` 第九项插在 `panel.aiContext` 之后、`panel.dmlImpact` 之前，保持「AI 相关相邻」。
- 同步修改 `SqlPreviewTopRowLayoutTest` 的精确列表断言与说明文字。
- `MpSqlVisualizerBundle.properties` / `_zh.properties` 各新增两个 key；既有 key 语义不变。

### D. 明确不做

- 不恢复引号内标识符（`"user"` 仍为占位）。
- 不在 EXPLAIN 摘要中放表名/列名（维持既有白名单）。
- 不改 v1 `AiContextExport.build`。
- 不引入 SQL 语法解析器；投影仍是无方言、按词面判断的保守实现。
- 不把「含表列名」设为默认，也不做自动判断「这条 SQL 敏不敏感」。

## 五、实施步骤（文件级落点）

1. `src/main/kotlin/cn/song/mybatisplus/visualizer/share/ShareContentProjection.kt`
   - 新增 `STRUCTURAL_KEYWORDS`；
   - 修改单词分支判定；
   - 保留 `VALUE_KEYWORDS` 与引号分支语义不变。
2. `src/main/kotlin/cn/song/mybatisplus/visualizer/util/AiContextExport.kt`
   - `buildV2(..., includeIdentifiers: Boolean = false)`；
   - 头部与静态摘要按模式分支；
   - `StaticSchema` 已含 `table`/`columns`，无需改数据类。
3. `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt`
   - `copyAsAiContext(includeIdentifiers: Boolean = false)`；
   - 第二个 toast 分支。
4. `src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelResultView.kt`
   - `moreActions()` 新增第九项，回调 `copyAsAiContext(includeIdentifiers = true)`。
5. `src/main/resources/messages/MpSqlVisualizerBundle.properties` 与 `_zh.properties`
   - `panel.aiContext.identifiers`、`ai.context.v2.copied.identifiers`。
6. 测试：
   - `src/test/kotlin/.../share/ShareContentProjectionTest.kt`：结构词保留、歧义词仍匿名、引号仍隐藏、含名模式；
   - `src/test/kotlin/.../util/AiContextV2ExportTest.kt`：两种头部、静态摘要两种形态、含名模式值仍打码、v1 golden 不变；
   - `src/test/kotlin/.../ui/AiContextV2PanelTest.kt`：新入口真实剪贴板含表列名、不含字面量；旧入口仍隐藏；
   - `src/test/kotlin/.../util/AiContextV2OfflineBoundaryTest.kt`：两个入口都零获取调用；
   - `src/test/kotlin/.../ui/SqlPreviewTopRowLayoutTest.kt`：菜单九项与顺序。

## 六、测试与验收

定向（独立沙盒，跑前 `parallel_tasks.py check` 并按登记表登记；`--no-configuration-cache`）：

```text
./gradlew test --tests "*ShareContentProjectionTest" --tests "*AiContextV2ExportTest" \
  --tests "*AiContextV2PanelTest" --tests "*AiContextV2OfflineBoundaryTest" \
  --tests "*SqlPreviewTopRowLayoutTest"
```

反向验证（缺一不可）：

- 摘掉 `STRUCTURAL_KEYWORDS` 判定（回到只保留行首语句）→ 结构可读性断言必红；
- 摘掉 `includeIdentifiers` 透传（固定传 `false`）→ 含名模式面板测试必红；
- 摘掉含名模式的头部分支（仍打印 `identifiers hidden`）→ 头部断言必红；
- 恢复后全绿。

真机走查（用户原路径）：

1. 同一段 SQL 分别用两个入口复制，粘贴对比：默认出口应能读懂从句结构；含名出口应能看到表名/列名，但字面量仍是 `?`。
2. 含引号的 SQL（如 `SELECT * FROM "user"`）在含名出口中引号内容仍为占位，确认文案已说明。
3. 复制前后确认无数据库连接、HTTP、MCP 请求（离线边界测试 + 真机观察）。

读数口径：只跑上述定向类；不跑全量、不跑构建校验、不 `clean`、不 `--stop`。发版前按三档分层另跑门禁。

## 七、实施记录

2026-10-19 实施完成（本条提交）。

- 结构关键词白名单：`ShareContentProjection.STRUCTURAL_KEYWORDS`，只收主流方言保留字；`COUNT/SUM/JSON/FIRST/NEXT` 等继续匿名，`NULL/TRUE/FALSE` 仍作值打码。
- 含表列名出口：`AiContextExport.buildV2(..., includeIdentifiers = false)`；头部、静态摘要按模式分支；列名上限 50，超出 `…(+N)`；引号内容仍不复制。菜单第九项为「复制为 AI 上下文（含表列名）」，toast 双语提示含表列名。
- 离线边界守卫的切片锚点随 `copyAsAiContext(includeIdentifiers)` 签名更新；两个出口仍零 SQL/HTTP/JDBC/MCP 调用。

定向读数（5 类合计 **33/0/0/0**）：

| 测试类 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `ShareContentProjectionTest` | 8 | 0 | 0 | 0 |
| `AiContextV2ExportTest` | 10 | 0 | 0 | 0 |
| `AiContextV2PanelTest` | 5 | 0 | 0 | 0 |
| `AiContextV2OfflineBoundaryTest` | 1 | 0 | 0 | 0 |
| `SqlPreviewTopRowLayoutTest` | 9 | 0 | 0 | 0 |

反向验证：

- 摘掉结构关键词白名单（仅保留原逻辑）→ **恰 3 红**（`结构关键词保留而同名裸列继续匿名`、既有 `默认省略…` 的 `SELECT` 保留断言、`structuralKeywordsRemainVisibleInDefaultMode`）；
- 忽略 `includeIdentifiers` 透传（投影固定 `false`）→ **恰 3 红**（两个导出用例 + 面板含名用例）。

真机走查未做（需重启 dev 沙盒加载新包）；含引号标识符（如 `"user"`）在含名出口中仍为占位，属本计划明确接受的范围。
