# T-DateLiteralBackfill：日期族参数回填未用 ANSI 字面量，Oracle 执行报 ORA-01843（2026-11-07）

- 立项：2026-11-07 13:33
- 状态：**已实施（master 工作树，未提交）**——P0/P1 落地，定向 **211/0/0/0**（fast 174 + platform 37），反向验证见 §12.3；真机验收未执行（环境受限，见 §12.4）
- 目标分支：`master`
- 来源：用户在 `oracle-demo` 真机打开增强捕获条目的「SQL 预览」，点「执行 SQL」报 ORA-01843；附截图与控制台原文
- 取证方式：插件源码静态走查（`master` `b2ccd4f9`）+ demo 源码 + 既有 R3 报告；未跑构建/测试

## 一、结论

根因不是「时间值不对」，是**时间字面量的形态不对**：

1. 捕获侧把 `LocalDateTime` 按 `toString()` 存成 `2026-11-07T13:20:14.417`（ISO `T`）；
2. 预览参数行按类型只折算出一个 `quote=true`（`LogSqlParam.toHint()`），回填时直接包单引号；
3. Oracle 对字符串 → TIMESTAMP 的隐式转换按 `NLS_TIMESTAMP_FORMAT` 解析，`T` 分隔符不被接受 → ORA-01843。

把 `T` 换成空格也不够：R3 缺口矩阵实测 `'2024-01-01 10:00:00.0'` 在 Oracle 11g/23 同样 ORA-01861
（`docs/调研报告/20260929-1500-R3-日期类型缺口矩阵-验证报告.md` §二 A 行）。正确形态是 R3 已验证的
ANSI 类型字面量 `TIMESTAMP '2026-11-07 13:20:14.417'`（`T` → 空格；缺秒补 `:00`）。R3 的「不需要方言
分支」只对矩阵里实测的 8 个引擎成立；SQL Server 不在矩阵内且官方不支持 ANSI 类型字面量，必须单独
门禁（§4.3）。

同一条链路的两条渲染路径今天不一致：控制台还原器 `LogSqlRestorer` 早在 R3 就产出 ANSI 字面量，
但**预览面板的默认路径**（`?` 数 == 参数数 → 参数行）走 `toHint` / `renderSingleValue`，R3 当时把
这条路显式挂起（同上报告 §四 决策点 D1，推荐 A=暂不动）。本次 Oracle 真机读数就是 D1 缺的那次
走查，**D1 定为 B（同步 ANSI 化）**。

## 二、现象与复现

### 2.1 现场

用户执行的 SQL（预览面板回填后）：

```sql
UPDATE DEMO_USER
SET
    NAME = 'alice2',
    AGE = 31,
    UPDATE_TIME = '2026-11-07T13:20:14.417'
WHERE ID = 1
```

Oracle 返回：

```
ORA-01843: 指定的月份无效。
Error position: offset 74, line 5, column 19
```

逐字符复算：前四行（含换行）共 56 个字符，第 5 行 `    UPDATE_TIME = '` 的 `'` 落在 0-based offset 74、
1-based 第 19 列——错误位置正好是这个时间字面量的**开引号**。参数区显示 `#param3` 的值就是
`2026-11-07T13:20:14.417`，没有做任何形态归一。

### 2.2 复现步骤（demo 侧已具备，不需要改 demo）

`oracle-demo` 的链路：

- `ReproRunner.java:46` → `mapper.updateById(new DemoUser(1L, "alice2", 31))`；
- `DemoMetaObjectHandler.updateFill` → `strictUpdateFill(..., "updateTime", LocalDateTime.class, LocalDateTime.now())`；
- 增强捕获条目（agent）：`UPDATE DEMO_USER SET NAME = ?, AGE = ?, UPDATE_TIME = ? WHERE ID = ?`，参数为
  `alice2(String) / 31(Integer) / 2026-11-07T13:20:14.417(LocalDateTime) / 1(Integer)`；
- 双击条目打开「SQL 预览」：`?` 数 == 参数数 → 参数行预填四个值；
- 渲染 SQL 的 `UPDATE_TIME` 段是 `'2026-11-07T13:20:14.417'`；
- 点「执行 SQL」→ ORA-01843。

库：测试服务器 Oracle Free 23（`103.36.220.245:21521/FREEPDB1`，用户 `probe`，见
`oracle-demo/README.md`「连的是哪个库」）。表由 `ReproRunner.resetTable()` 启动时重建，`UPDATE_TIME`
是 `timestamp` 列。

## 三、根因链

| 跳 | 位置 | 现行为 |
|---|---|---|
| 1. 捕获值 | `sub-projects/sql-visualizer-agent/.../ParamCapture.renderValue` | `String.valueOf(LocalDateTime)` → `2026-11-07T13:20:14.417`；类型名 `LocalDateTime`（`simpleTypeName` 已剥包名） |
| 2. 条目 | `log/LogSqlModels.kt:638` `LogSqlParam(value, type)` | 原样保存 ISO `T` |
| 3. 预览识别 | `log/LogEntryPreviewRecognizer.kt:65-66` | `initialValues["param3"] = "2026-11-07T13:20:14.417"`；`paramHints = params.map { it.toHint() }` |
| 4. 类型折算 | `log/LogSqlRestorer.kt:139-143` `toHint()` | 非 `PLAIN_TYPES` 一律 `ParamHint(quote = true)`，**没有日期族信息** |
| 5. 面板回填 | `ui/SqlPreviewPanelParamPanel.kt:1884-1907` → `:1910-1936` → `:1957-1967` | `renderSingleValue` 的 `quote == true` 分支：`"'$escaped'"` |
| 6. 执行 | `ui/SqlPreviewPanelExecution.kt:872` 等 | 直接取编辑器文本执行——第 5 跳的产物就是最终 SQL |

关键点：第 5 跳没有任何「日期族 → ANSI 字面量」分支；`ParamHint` 只有 `quote` / `likePosition` /
`inList` 三个字段，**类型信息在这一跳已经丢了**。

### 3.1 同源缺陷（同一根因，另外三条链）

| 链路 | 位置 | 现行为 | 用户可见后果 |
|---|---|---|---|
| agent 还原（列表显示 / 复制 / 去重键） | `log/agent/AgentEventAdapter.kt:150-154` `renderParam` | 非数值一律 `'…'` | 列表里的 `restoredSql` 就是坏的；从列表复制到客户端同样 ORA-01843 |
| 复制可执行 SQL / 导出 / 分享 | `sql/ParameterReplacer.kt:111-125` `renderValue`、`:180-190` `renderNamedValue` | `hint.quote == true` → `'…'` | 「复制可执行 SQL」「导出回放脚本」「分享」产物同样不可执行 |
| 模型链（MP Wrapper / 批量 / JPA / XML） | `sql/SqlGenerator.kt:559-565` + `:607-610`；`psi/BatchOperationConverter.kt:206/626/877/882`；`converter/jpa/JpaCrudOperationConverter.kt:541/632/648/723/742/769/773`；`converter/jpa/QueryByExampleConverter.kt:486`；`converter/jpa/SpecificationQueryConverter.kt:645`；`generator/CrudSqlGenerator.kt:171`；`ui/XmlParamBackfillHints.kt:31-41`；`ui/SqlPreviewServiceEnhancements.kt:274-284` | 按字段类型只折算 `quote` | 用户手填 `2026-11-07 13:20:14` 后，Oracle 对 TIMESTAMP 列同样隐式转换失败（ORA-01861） |

`xml/ParamKind.kt:31-38` 还写死了一条相反结论：「TEMPORAL 走引号档（日期字面量在 SQL 中须引号包裹），
不做日期特判」。这条 KDoc 是 R3 之前的口径，需要随本单改成「TEMPORAL 走 ANSI 类型字面量，形态不匹配
才回落引号」。

### 3.2 为什么测试没抓到

- R3 的修复只改了 `LogSqlRestorer`（还原链），而**最常见的预览路径**是「`?` 数 == 参数数 → 参数行」，
  它根本不经过 `LogSqlRestorer.renderParam`——`LogEntryPreviewRecognizer.kt:174-190` 识别成功时用的是
  `rawSql` + 参数行，不是 `restoredSql`；
- `SqlPreviewPanelDynamicBatchTest.kt:387-421` 把旧契约钉成了断言：
  `use_time = '2024-01-01 00:00:00'`；
- `CrossChannelDedupeTest.kt:178-210` 把「agent 日期族无 ANSI 规则」当成跨通道分叉的**前提**钉住
  （`:205` 断言 agent 还原串含 `'true', '2026-11-05 11:37:20.0'`，`:206` 断言两侧 `dedupeKey` 不等），
  等于给 agent 侧的错误行为上了锁；
- 没有任何一条用例走「参数行 → 真库执行」的路径，`LogSqlRestorerTest` 只覆盖还原链。

## 四、修复设计

### 4.1 单一渲染器 `sql/SqlDateLiteral.kt`（新增）

把 R3 已经验证过的日期族规则从 `LogSqlRestorer` 里抽出来，成为全链唯一实现：

```kotlin
object SqlDateLiteral {
    /** 类型简单名是否日期族（全限定名 / JDBC 名归一）。用于构造 ParamHint.temporal。 */
    fun isTemporalType(type: String?): Boolean

    /** 按值形态产出 ANSI 字面量；形态不匹配返回 null（调用方回落既有引号，绝不产生非法字面量）。 */
    fun renderByShape(value: String, dialectName: String? = null): String?

    /** 类型 + 值 → ANSI 字面量（R3 的 family 限制，供 LogSqlRestorer / AgentEventAdapter 用）。 */
    fun renderFor(value: String, type: String?, dialectName: String? = null): String?
}
```

映射表（与 R3 `LogSqlRestorer.kt:63-86` 逐条一致）：

| 类型简单名 | 值形态 | 产物 |
|---|---|---|
| `LocalDate` / `DATE` | `yyyy-MM-dd` | `DATE '…'` |
| `Date` | `yyyy-MM-dd` | `DATE '…'` |
| `Date` | `yyyy-MM-dd[ T]HH:mm[:ss][.f…]` | `TIMESTAMP '…'`（`T`→空格） |
| `Timestamp` / `LocalDateTime` / `TIMESTAMP` / `DATETIME` | `yyyy-MM-dd[ T]HH:mm[:ss][.f…]` | `TIMESTAMP '…'`（`T`→空格，缺秒补 `:00`） |
| 其余 / 形态不匹配 | — | `null` → 回落引号 |

刻意不包装（沿用 R3 边界，写进 KDoc）：`LocalTime` / `Time`（列类型相关）、`OffsetDateTime` /
`ZonedDateTime` / `Instant`（ANSI TIMESTAMP 不收时区后缀）、无类型标注的日期形串（无法区分字符串列，
误包装会改语义）。

### 4.2 类型通道：`ParamHint.temporal`

`sql/ParamHint.kt` 增加 `val temporal: Boolean = false`（默认值保证既有构造与 `equals` 不破）。
语义：**调用方已知这个值是日期/时间族**，渲染时先试 ANSI 字面量，失败再走 `quote`。

置位点：

- `LogSqlRestorer.kt:139-143` `LogSqlParam.toHint()`：`temporal = SqlDateLiteral.isTemporalType(type)`；
- `ui/XmlParamBackfillHints.kt:31-41`：`temporal = info?.kind == ParamKind.TEMPORAL`（集合取
  `elementKind == TEMPORAL`）；
- `ui/SqlPreviewService.kt:1340-1356` 旁新增 `isTemporalParam(method, name)`（与 `isNumericParam`
  同一套 PSI 类型解析），`SqlPreviewServiceEnhancements.kt:274-284` 的 JPA 提示一并置位；
- `sql/ParamQuote.kt` 新增 `isTemporal(fieldType)`，`SqlGenerator` / `BatchOperationConverter` /
  `JpaCrudOperationConverter` / `QueryByExampleConverter` / `SpecificationQueryConverter` /
  `CrudSqlGenerator` 的 `ParamHint(quote = ParamQuote.decide(...))` 同步带 `temporal`。

### 4.3 方言门禁（必须做，否则 SQL Server 回归）

SQL Server 的 T-SQL **不支持** ANSI 类型字面量（`DATE '2024-01-01'` 语法错误；Microsoft 官方只给
ODBC 转义 `{d '…'}` / `{ts '…'}` 与隐式转换）。当前 SQL Server 的 `'2026-11-07T13:20:14.417'` 是
ISO 8601 形态，反而能正确隐式转换；若照搬 ANSI 字面量会**把能跑的 SQL 改坏**。

门禁放在渲染器：

```kotlin
private val ANSI_TYPED_LITERAL_UNSUPPORTED = setOf("SQL Server", "SQLite")

fun ansiTypedLiteralSupported(dialectName: String?): Boolean =
    dialectName == null || dialectName !in ANSI_TYPED_LITERAL_UNSUPPORTED
```

- 已知 `SQL Server` / `SQLite` → 返回 null，回落引号（维持现行为）；
- 其余（含方言未知）→ ANSI 字面量。未知默认 ANSI 是有意的：Oracle 条目通常有角标/URL 证据，
  未知方言走 ANSI 才能覆盖「证据缺失的 Oracle 条目」；SQL Server 条目有 `jdbc:sqlserver:` URL，
  正常都能推断出方言。

注意：`LogSqlRestorer` 当前无方言入参（解析器在解析日志行时拿不到 URL），本单**不改它的门禁**，
SQL Server 控制台还原的既有缺口单列（见 §九 D6）。

### 4.4 渲染优先级

统一为 **LIKE > temporal > quote**：

- `hint.likePosition != null`：LIKE 是字符串语义，恒引号 + 拼 `%`，不做日期包装；
- `hint.temporal == true`：先 `SqlDateLiteral.renderByShape(value, dialectName)`，非 null 直接用；
- 否则走既有 `quote` / 数字启发式；
- 空值仍走既有 `?` / `NULL` / `''` 策略；
- `inList`：先按逗号拆分，再对每个元素走上面的单值渲染（日期列表产出
  `IN (TIMESTAMP '…', TIMESTAMP '…')`）。

### 4.5 不改什么

- **不在捕获侧把 `T` 换成空格**：值保持用户/日志原文，参数行仍可编辑；只改渲染产物。只换分隔符
  仍会被 Oracle 的 `NLS_TIMESTAMP_FORMAT` 拒绝（R3 A 行）。
- **不对无类型标注的日期形串做启发式包装**（R3 D2 定案）：`WHERE code = '2025-01-01'` 不能误伤。
- **不动 `LocalTime` / 带时区值**（R3 D3）。
- **不动 `LiteralParamRecovery` 的识别规则**：它无类型信息，维持 `quote=true`；既有 ANSI 字面量被
  它二次占位化的问题单列（§九 P2）。

## 五、改造清单

### P0（本单必做，直接修用户报障）

| # | 文件 / 函数 | 改动 |
|---|---|---|
| 1 | `sql/SqlDateLiteral.kt`（新增） | 抽 R3 规则 + 方言门禁；`LogSqlRestorer.renderDateFamily` / `normalizeTs` 改为委托（既有用例行为不变；D4 的纯日期边界按新规则产 `DATE`） |
| 2 | `sql/ParamHint.kt` | 增加 `temporal: Boolean = false` |
| 3 | `log/LogSqlRestorer.kt:139-143` | `toHint()` 置 `temporal` |
| 4 | `ui/SqlPreviewPanelParamPanel.kt:1957-1967` | `renderSingleValue` 增加 temporal 分支（dialect 取 `current?.effectiveDialect?.name`）；`:1910-1936` 的 LIKE / inList 优先级不变 |
| 5 | `sql/ParameterReplacer.kt:76-125` / `:180-190` / `:324-430` | `buildExecutableSql` 增加 `dialectName` 参数；`renderValue` / `renderNamedValue` 增加 temporal 分支；`buildExecutableSqlWithSlots` 的零占位快捷路径把 `dialectName` 透传下去（现在丢在 `buildExecutableSql` 里） |
| 6 | `log/agent/AgentEventAdapter.kt:79-154` | `restore` 增加 `dialectName`（`toEntry` 从 `event.url` 取）；`renderParam` 改用 `SqlDateLiteral.renderFor` |
| 7 | `ui/components/CopyExecutableSqlAction.kt`、`share/format/ExecutableSqlFormatter.kt` | 确认 `dialectName` 已透传（前者已传，后者已收）；零占位分支靠第 5 项修 |
| 8 | `log/LogEntryPreviewRecognizer.kt` | 无代码改动；识别分支的 `paramHints` 自动带上 temporal |

### P1（同源缺陷，建议同批，避免「捕获链修了、模型链照旧」）

| # | 文件 / 函数 | 改动 |
|---|---|---|
| 9 | `sql/SqlGenerator.kt:559-565` / `:607-610` | `quoteHint` 升级为 `paramHint`（返回 `ParamHint`），`renderValue` 收 `ParamHint`；条件 / SET / BETWEEN / IN 四处提示带上 temporal |
| 10 | `psi/BatchOperationConverter.kt`、`model/BatchModel.kt` | 列级 `ParamHint` 带 temporal |
| 11 | `converter/jpa/JpaCrudOperationConverter.kt`、`QueryByExampleConverter.kt`、`SpecificationQueryConverter.kt`、`generator/CrudSqlGenerator.kt` | 同上 |
| 12 | `ui/XmlParamBackfillHints.kt:31-41` | `ParamKind.TEMPORAL`（含 `elementKind`）置 temporal |
| 13 | `ui/SqlPreviewService.kt` / `SqlPreviewServiceEnhancements.kt` | 新增 `isTemporalParam`，JPA 提示置 temporal |
| 14 | `xml/ParamKind.kt:31-38` | KDoc 定案回写：TEMPORAL 不再「不做日期特判」 |
| 15 | `ui/SqlPreviewPanel.kt` 的 `DATE_REGEX`（:1449-1452） | 保持「只做提示不阻塞」；可选补非补零形态（见 D3） |

### P2（登记，不阻塞）

| # | 事项 | 处置 |
|---|---|---|
| 16 | `LiteralParamRecovery` 会把 `DATE '2024-01-01'` 里的字符串部分恢复成 `?`，产出 `DATE ?` | 本单不改，登记为独立缺陷；修法是识别 ANSI 前缀后整段跳过 |
| 17 | `LogSqlRestorer` 无方言门禁，SQL Server 控制台还原仍产 ANSI 字面量 | 本单不改（解析期无 URL）；等 SQL Server 真机或按需加可选 `dialectName` |
| 18 | 非补零日期（`2026-1-1`）不包装 | R3 正则只认两位；面板 `DATE_REGEX` 认一位。按 D3 决定是否放宽 |
| 19 | `LocalTime` / 带时区值 | 维持 R3 边界 |

## 六、测试计划

### 6.1 新增 / 修改用例

| 用例 | 断言 |
|---|---|
| `sql/SqlDateLiteralTest`（新增） | 类型映射：`LocalDate`/`Date`/`Timestamp`/`LocalDateTime`/`TIMESTAMP`/`DATETIME`；形态：date-only → `DATE`、datetime → `TIMESTAMP`、`T` 归一、缺秒补 `:00`、9 位小数；不匹配回落 null；`LocalTime` / `OffsetDateTime` / 无类型返回 null；`dialectName="SQL Server"` 返回 null |
| `sql/ParameterReplacerTest` | `ParamHint(quote=true, temporal=true)` + `2026-11-07T13:20:14.417` → `TIMESTAMP '2026-11-07 13:20:14.417'`；`dialectName="SQL Server"` → 原引号串；`:name` 同；`LIKE` 恒引号 |
| `log/LogSqlRestorerTest` | 既有日期用例全绿（委托后逐字节一致） |
| `log/LogEntryPreviewRecognizerTest` | `LocalDateTime` 参数 → `paramHints[i].temporal == true`；`String` 日期形值 → `temporal == false` |
| `log/agent/AgentEventAdapterTest` | `restore("UPDATE t SET ts = ?", [LogSqlParam("2026-11-07T13:20:14.417","LocalDateTime")])` → `TIMESTAMP '…'`；数值/字符串用例不变；SQL Server 方言 → 引号 |
| `log/CrossChannelDedupeTest` | 更新 `现场形态_12参数INSERT…`：agent 日期段改为 `TIMESTAMP '…'`，分叉只剩布尔（`'true'` vs `true`）；`assertNotEquals` 仍成立；`日期参数` / `LocalDateTime` 两例注释改为「已同键折叠」并保留布尔/注释 `?` 的兜底用例 |
| `ui/SqlPreviewPanelDynamicBatchTest` | `testDynamicConditionDateTimeColumnValueQuoted` 改名为 `...TemporalLiteral`，断言改为 `use_time = TIMESTAMP '2024-01-01 00:00:00'` |
| `ui/XmlParamBackfillHintsTest` | `ParamKind.TEMPORAL` → `hint.temporal == true`；`NUMBER` / `TEXT` 仍 false |
| `ui/SqlPreviewPanelParamKindTest` | 既有 TEMPORAL 校验警示不变（警示只提示，不阻塞） |

### 6.2 反向验证

- 摘掉 `renderSingleValue` 的 temporal 分支 → `SqlPreviewPanelDynamicBatchTest` 恰好红 1；
- 摘掉 `AgentEventAdapter.renderParam` 的日期族分支 → `AgentEventAdapterTest` 新增用例红；
- 摘掉 `ParameterReplacer.renderValue` 的 temporal 分支 → `ParameterReplacerTest` 新增用例红；
- 恢复后全绿。每步贴「红前 / 绿后」两次读数。

### 6.3 真机验收（oracle-demo）

1. 按 `oracle-demo/README.md` 构建并挂 agent 启动（库 `FREEPDB1@103.36.220.245:21521`，用户 `probe`）；
2. 等 `REPRO 4/4 updateById done`，在 SQL 列表找到 `UPDATE DEMO_USER …`；
3. 打开「SQL 预览」：`UPDATE_TIME` 段应为
   `TIMESTAMP '2026-11-07 13:20:14.417'`（不再是裸引号 + `T`）；
4. 点「执行 SQL」→ 应成功（影响行数 1），控制台无 ORA-01843；
5. 同一面板「复制可执行 SQL」→ 粘到 DB 控制台执行 → 同样成功；
6. 列表里的 `restoredSql`（未打开预览）也应显示 ANSI 字面量。

### 6.4 定向命令（按全局纪律先登记并行任务）

```bash
python C:\Users\USER\.zcode\cli\parallel_tasks.py check
./gradlew testFast --tests "cn.song.mybatisplus.visualizer.sql.SqlDateLiteralTest" \
  --tests "cn.song.mybatisplus.visualizer.sql.ParameterReplacerTest" \
  --tests "cn.song.mybatisplus.visualizer.log.LogSqlRestorerTest" \
  --tests "cn.song.mybatisplus.visualizer.log.LogEntryPreviewRecognizerTest" \
  --tests "cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapterTest" \
  --tests "cn.song.mybatisplus.visualizer.log.CrossChannelDedupeTest"
```

面板用例（`SqlPreviewPanelDynamicBatchTest` 等 `BasePlatformTestCase`）走平台桶：

```bash
./gradlew test --tests "cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelDynamicBatchTest" \
  --tests "cn.song.mybatisplus.visualizer.ui.XmlParamBackfillHintsTest"
```

## 七、影响面与回归风险

- **用户可见文本变化**：所有日期/时间参数的预览、复制、导出产物由 `'2026-11-07T13:20:14.417'`
  变成 `TIMESTAMP '2026-11-07 13:20:14.417'`。这是修复本身，但需要 CHANGELOG 明说。
- **SQL Server / SQLite 必须门禁**：见 §4.3；不门禁会把原本能跑的 ISO 8601 引号串改坏。
- **跨通道去重键变化**：agent 侧日期段从 `'…T…'` 变为 `TIMESTAMP '…'`，与 Oracle/MySQL 等控制台侧
  对齐；SQL Server 因控制台侧未门禁仍可能分叉（P2 第 17 项）。`CrossChannelDedupeTest` 需同步。
- **`ParamHint` equals/hashCode**：新增字段有默认值，既有测试构造（`ParamHint(quote = …)` 形态）不受影响；只有显式置 `temporal=true` 的断言需要新增。
- **执行链**：`SqlPreviewPanelExecution.kt` 取编辑器文本，渲染修好即执行修好；不存在第二条渲染入口。
- **XML/JPA/MP 模型链**：P1 不改的话，用户手填日期仍会踩 ORA-01861；建议同批。

## 八、待验证前提（先证伪再动手）

1. **用户库的 NLS 设置**：ANSI 字面量不依赖 `NLS_TIMESTAMP_FORMAT`，但要用 `SELECT TIMESTAMP
   '2026-11-07 13:20:14.417' FROM dual` 在该实例实测一次，贴读数。
2. **SQL Server 不支持 ANSI 类型字面量**：已由 Microsoft 文档确认（`DATE '…'` 语法错误，官方给
   `{d '…'}` / `{ts '…'}`），但本机无 SQL Server 实例；门禁用例只能做纯单测，真机项登记为「环境受限
   未验证」。
3. **捕获条目一定带方言证据**：`toPreviewResult` 用 `capturedDialect` 或角标推断（`LogEntryPreviewRecognizer.kt:127-141`）；
   若两者都为空，`effectiveDialect` 为 null，按 §4.3 默认走 ANSI——需在真机日志里确认 SQL Server 条目
   不会落到「无方言」分支。
4. **agent 类型名是简单名**：`ParamCapture.simpleTypeName` 已剥包名（`LocalDateTime`）；若某些驱动上报
   `TIMESTAMP` / `java.sql.Timestamp`，`isTemporalType` 的简单名归一会覆盖，但需用真实事件确认。
5. **`LogSqlRestorer` 委托后逐字节一致**：用既有 `LogSqlRestorerTest` / `ParamEdgeCaseRegressionTest` /
   `HibernateLogParserTest` / `JdbcTemplateLogParserTest` 的日期用例做对照；任何差异都要先解释再改期望。
6. **面板执行确实取编辑器文本**：`SqlPreviewPanelExecution.kt:872` / `:1135` / `:1319` / `:1360` 均为
   `nativeEditorDoc?.text`；若存在「执行前重新回填」的旁路，需补测。

## 九、决策点

| # | 决策点 | 推荐 |
|---|---|---|
| D1 | 方言门禁策略 | **A=黑名单**（`SQL Server` / `SQLite` 回落引号，其余含未知走 ANSI）；B=无门禁（照 R3，SQL Server 会回归）；C=白名单（只 R3 实测 8 引擎，未知方言会漏 Oracle） |
| D2 | `ParamHint` 类型通道形态 | **A=`temporal: Boolean`**（最小、单一判定点）；B=`valueType: String?`（更通用但要维护第二套类型表）；C=独立 `DateLiteralHint` |
| D3 | 非补零日期（`2026-1-1`） | A=维持 R3 两位正则（P0 不动）；**B=P1 放宽为 1~2 位**（Oracle 实测接受 `DATE '2024-1-1'`，面板 `DATE_REGEX` 也接受） |
| D4 | TIMESTAMP 族类型填纯日期（`2026-11-07`） | **A=`DATE '…'`**（Oracle 隐式 DATE→TIMESTAMP，语义=当日 0 点）；B=补 `00:00:00` 用 TIMESTAMP；C=回落引号（Oracle 仍会失败） |
| D5 | P1 模型链是否同批 | **A=同批**（否则「捕获链修了、Wrapper/XML/JPA 链照旧」）；B=另立券 |
| D6 | `LogSqlRestorer` 的 SQL Server 门禁 | **A=本单不做，登记 P2**（解析期无方言证据，改动面大）；B=给 `restore` 加可选 `dialectName` 并让调用方在能拿到 URL 时传 |
| D7 | 是否在捕获侧归一值文本 | **A=不归一**（保留原文，渲染期处理）；B=把 `T` 换空格（仍会被 NLS 拒绝，且改坏用户可编辑值） |

## 十、任务拆分

| # | 任务 | 依赖 | 优先级 |
|---|---|---|---|
| R1 | `SqlDateLiteral` + `SqlDateLiteralTest` | — | P0 |
| R2 | `ParamHint.temporal` + `toHint()` | R1 | P0 |
| R3 | 面板 `renderSingleValue` temporal 分支 | R1/R2 | P0 |
| R4 | `ParameterReplacer` + 方言门禁 + 零占位透传 | R1/R2 | P0 |
| R5 | `AgentEventAdapter.restore` + `CrossChannelDedupeTest` 更新 | R1 | P0 |
| R6 | `LogSqlRestorer` 委托（行为不变） | R1 | P0 |
| R7 | 模型链（`SqlGenerator` / 批量 / JPA / XML） | R2 | P1 |
| R8 | CHANGELOG + 索引 + R3 报告 D1 回写 | R3~R7 | P0/P2 |
| R9 | oracle-demo 真机验收（§6.3） | R1~R6 | P0 |

## 十一、变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-07 | 立项：根据用户 oracle-demo 真机 ORA-01843 截图与插件源码走查，确认根因为参数行回填缺日期族 ANSI 字面量；给出 P0/P1/P2 改造清单与 D1~D7 决策点 |
| 2026-11-07 | 实施：P0/P1 全部落地（`SqlDateLiteral` + `ParamHint.temporal` + 四条渲染链 + 模型链）；定向 211/0/0/0，反向验证三处先红后绿；真机验收待用户环境复跑 |

## 十二、实施记录（2026-11-07）

### 12.1 落地范围

- **共享渲染器**：新增 `sql/SqlDateLiteral.kt`——R3 规则上提 + 方言门禁（`SQL Server` / `SQLite` 回落引号）+ D4（TIMESTAMP 族纯日期 → `DATE '…'`）。
- **类型通道**：`ParamHint.temporal`；置位点 = `LogSqlParam.toHint()`、XML `ParamKind.TEMPORAL`（含集合元素）、JPA `isTemporalParam`、MP/批量/CRUD 的 `ParamQuote.hint` / `isTemporal`。
- **四条渲染链**：面板 `renderSingleValue`；`ParameterReplacer`（`?` / `:name` / 零占位快捷路径把 `dialectName` 透传下去）；`AgentEventAdapter.restore`（按事件 URL 的方言门禁）；`LogSqlRestorer`（委托 `SqlDateLiteral.renderFor`；既有用例行为不变，仅 D4 的「TIMESTAMP 族纯日期」边界按新规则产出 `DATE`）。
- **模型链**：`SqlGenerator`（`quoteHint` → `paramHint`）、`BatchOperationConverter` / `BatchModel`、`JpaCrudOperationConverter`、`QueryByExampleConverter`、`SpecificationQueryConverter`、`EasyQueryConverter`、`CrudSqlGenerator` / `CrudColumn`、`DbTypeQuoteHint`、`XmlParamBackfillHints`。
- **文档**：CHANGELOG `[Unreleased]`、R3 报告 D1 定案、计划索引状态、本实施记录。

### 12.2 定向读数

| 批次 | 用例（例数） | 读数 |
|---|---|---|
| fast-1 | SqlDateLiteralTest(13) / ParameterReplacerTest(45) / LogSqlRestorerTest(13) / LogEntryPreviewRecognizerTest(24) / AgentEventAdapterTest(16) / CrossChannelDedupeTest(19) | **130 / 0 / 0 / 0** |
| fast-2 | SqlGeneratorTest(21) / BatchModelTest(13) / XmlParamBackfillHintsTest(10) | **44 / 0 / 0 / 0** |
| platform | SqlPreviewPanelDynamicBatchTest(21) / SqlPreviewPanelParamKindTest(5) / CrudSqlPreviewPanelRenderTest(5) / JpaChainParamBackfillTest(6) | **37 / 0 / 0 / 0** |

（读数口径：tests / failures / errors / skipped。）

### 12.3 反向验证（先红后绿）

| 摘除点 | 红读数 | 恢复后 |
|---|---|---|
| `AgentEventAdapter.renderParam` 日期族分支 | AgentEventAdapterTest **16 例 1 红**（`日期族参数还原为ANSI字面量`）；SQL Server 门禁与字符串日期形两例仍绿 | 16/0/0/0 |
| `ParameterReplacer.renderValue` 日期族分支 | ParameterReplacerTest **45 例 1 红**（`buildExecutableSql_日期族参数产出ANSI字面量`）；命名参数 / SQL Server / LIKE 三例仍绿 | 45/0/0/0 |
| `SqlPreviewPanelParamPanel.renderSingleValue` 日期族分支 | SqlPreviewPanelDynamicBatchTest **21 例 1 红**（`testDynamicConditionDateTimeColumnValueTemporalLiteral`） | 21/0/0/0 |

### 12.4 偏差、边界与环境受限

- **D3 取 A**（维持 R3 两位正则）：非补零日期（`2026-1-1`）仍回落引号。放宽到 1~2 位只在 Oracle 实测过，MySQL/达梦等引擎未验证，故本单不扩；面板 `DATE_REGEX` 仍接受该形态但只做提示、不阻塞。登记为后续可选券。
- **D6 取 A**：`LogSqlRestorer` 无方言入参，SQL Server 控制台还原仍产 ANSI 字面量（P2 既有缺口），本单未动；agent 侧已按 URL 门禁，跨通道在 SQL Server 上仍可能分叉（`CrossChannelDedupeTest` 保留该夹具的兜底断言，另加 Oracle 同键用例）。
- **P2 第 16 项**（`LiteralParamRecovery` 对既有 ANSI 字面量的误识别）未改，维持登记。
- **`JdbcTemplateLogParser` 的 `:name` 命名参数**无类型信息，维持 `renderUntyped` 引号（R3 D2 口径）。
- **真机验收（§6.3）未执行**：本会话没有可驱动的 runIde 沙盒 + `oracle-demo` + `FREEPDB1` 组合，按「环境受限未验证」登记。用户可在自己的 IDE 里按 §6.3 复跑；预期 `UPDATE_TIME` 段显示 `TIMESTAMP '…'`，执行不再 ORA-01843。
- **未提交**：改动留在 master 工作树（32 个文件 + 3 个新文件），按仓库交付流程待用户确认后提交。

## 参考

- `docs/调研报告/20260929-1500-R3-日期类型缺口矩阵-验证报告.md`（§二矩阵、§四 D1）
- `docs/开发计划/20261105-1241-T-CrossChannelDedupe-跨通道去重键失配致同次执行双条目-开发计划.md`（§3.1 还原器口径对照表）
- `oracle-demo/README.md`（库与跑法）
