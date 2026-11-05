# T-CrossChannelDedupe：跨通道去重键失配导致同一次执行出现两条（2026-11-05）

- 立项：2026-11-05 12:41
- 目标分支：`master`（当前 tip `6491b4b4`）
- 用户报障：部分 INSERT 语句没有捕获到耗时和执行位置

## 一、现象

用户截图（`sys_operation_log` 的一次插入，应用侧 `@Async("LogExecutor") saveAsync` → `logMapper.insert(entity)`）：

```
#8  [11:37:20]  7ms [SQL Server] x1 [UserService...]   SELECT COUNT(*) ...
#9  [11:37:20]  1ms [SQL Server] x1 [UserService...]   SELECT id,username,... FROM sys_user ORDER BY id ASC OFFSET ...
#10 [11:37:20]  2ms [SQL Server] x1 [UserService...]   SELECT ur.user_id AS userId,... FROM sys_user_role ur JOIN sys_role r ...
#11 [11:37:20]  —                                      INSERT INTO sys_operation_log (...) VALUES ('admin', '用户管理', ...)
#13 [11:37:20]  8ms [SQL Server] (1 rows) [Opera...]   INSERT INTO sys_operation_log (...) VALUES ('admin', '用户管理', ...)
```

`#11` 与 `#13` 是同一条 INSERT、同一组参数、同一秒。区别只在头部元信息：

| 条目 | 耗时 | 库角标 | 影响行数 | 执行位置 |
|---|---|---|---|---|
| #11 | `—`（null） | 无 | 无 | 无 |
| #13 | `8ms` | `[SQL Server]` | `(1 rows)` | `[Opera…]` |

## 二、两个问题分开回答

### 2.1 信息有没有捕获到——捕获到了，没有丢

同一次执行的耗时（8ms）、影响行数（1 rows）、源码位置、目标库都在 `#13` 里。`#13` 是 agent 增强通道产出的条目：耗时来自 `AgentEvent.durationNanos`，行数来自 `getUpdateCount`，位置来自应用内调用栈（`AgentEventAdapter.toEntry`，`AgentEventAdapter.kt:36-73`）。

所以「没有捕获到耗时和执行位置」这个描述只对 `#11` 成立，对这次执行整体不成立。

### 2.2 是不是控制台与增强捕获重复了——是，而且正是根因

`#11` 是控制台通道（EP 过滤器或桥轮询）解析 MyBatis 日志得到的同一条 SQL：

- 耗时恒为 null：MyBatis 对 DML 只打印 `<==    Updates: N`，不打印耗时行；控制台条目的耗时来自 `ExecutionTimeParser.parse(line, timePrefix)`（`SqlLogConsoleBridge.kt:1154`），对 INSERT 的日志行取不到任何耗时形态（`ExecutionTimeParser.kt:54-88` 的七种形态全部要求行内有 `elapsed/TimeCount/ExecuteTime/executeMillis/尾部 Nms`）。
- 位置恒为 null：控制台条目的位置只来自拦截器注入的源码标记（`finishEntries` 的 `ctx.pendingSource`，`SqlLogConsoleBridge.kt:1155`）。未注入 `SqlSourceInterceptor` 时没有标记，就没有位置。
- 库角标恒为 null：`databaseName` 只有 agent 通道从连接 URL 解析（`AgentEventAdapter.kt:65`）。

三条同时为 null，是控制台条目的固有形态，不是「捕获失败」。

**结论：不是「没捕获到」，是「捕获到了两份，其中一份天然缺字段，而且两份没有被折叠成一份」。**

## 三、根因

### 3.1 折叠为什么没发生

去重折叠只有一个入口：`SqlLogEntryBuffer.addEntryInternal` 的去重分支（`SqlLogEntryBuffer.kt:294-313`）。判定用 `dedupeKey`：

```kotlin
// SqlLogEntryBuffer.kt:457-461
private fun isDuplicate(entry: LogSqlEntry): Boolean {
    if (!canDedupe(entry)) return false
    val latest = dedupeIndex[entry.dedupeKey] ?: return false
    return entry.timestamp - latest.timestamp < dedupeWindowMs   // 默认 60s
}
```

两条同秒入库的条目只要 `dedupeKey` 相同，后到者必然走融合分支（`mergeDuplicateEntry`，`LogSqlModels.kt:446-474`）：先到的是控制台条目、后到的是 agent 条目时，耗时会由 `existing.executionTimeMs ?: incoming.executionTimeMs` 补齐，位置按「真实文件优先」升级。

**现在两条并存，说明两个 `dedupeKey` 不相等。** 这是唯一能让同一次执行留下两行的路径（同键只会折叠，异键才会各占一行）。

`dedupeKey` = `sqlFingerprint(normalizeSqlWhitespace(restoredSql))`（`LogSqlModels.kt:214`），即「参数还原后的 SQL 文本」指纹。两个通道各有一套还原器，口径不同源：

| | 控制台通道 | agent 通道 |
|---|---|---|
| 实现 | `LogSqlRestorer`（`LogSqlRestorer.kt:26-49`） | `AgentEventAdapter.restore`（`AgentEventAdapter.kt:79-122`） |
| 数值类型 | `PLAIN_TYPES` 含 `Long/Integer/...` 与全限定 `java.lang.Long`（`LogSqlRestorer.kt:105-118`） | `NUMERIC_TYPE_REGEX.matches(type)` 全串匹配，**认不出 `java.lang.Long`**（`AgentEventAdapter.kt:156-162`） |
| 布尔 | `PLAIN_TYPES` 含 `Boolean/boolean` → `true` | 不在数值表 → `'true'` |
| 日期族 | `DATE '…'` / `TIMESTAMP '…'` ANSI 字面量（`LogSqlRestorer.kt:63-79`） | 无此规则 → `'2026-11-05 11:37:20.0'` |
| 默认 toString 降级 | 用日志原文（`[B@1a2b`） | 降级为 `<byte[]>` / `<Blob>`（`ParamCapture.java:141-160`） |
| 占位符计数 | `placeholderPositions` 跳过 `--` 与 `/* */` 注释（`PlaceholderPositions.kt:27-38`） | `countPlaceholders` 不跳注释（`AgentEventAdapter.kt:125-148`） |
| 参数个数守卫 | 宽容：多退少补（`LogSqlRestorer.kt:33-34`） | 严格：`countPlaceholders != params.size` 就整句不还原（`AgentEventAdapter.kt:82`） |

上面每一行都是一个能让两个 `dedupeKey` 分叉的点。这不是理论推演——仓库里已有实测记录：

- `AgentLogTimeParityTest` 的类注释（`src/test/kotlin/.../log/AgentLogTimeParityTest.kt:16-19`）写明：旧口径用还原后 SQL 指纹做跨通道关联键，「在布尔参数、注释内 `?`、参数个数不等、多行 SQL 四类形态下静默失配」。

  **2026-11-05 本地复现订正**：四类里「多行 SQL」在**去重口径**下不分叉——`normalizeSqlWhitespace` 折叠空白后同键（实测 `buffer.size == 1`），它只在叠加日期/布尔参数时才跟着分叉。日志时间口径当时把多行算进去是因为它用 `logTimeKey` 之外的旧键，与去重面不是同一判据。真正影响 `dedupeKey` 的是布尔、日期族、注释内 `?`、参数个数守卫四类。
- `AgentEventAdapterTest.dedupeKey与桥通道一致`（`.../agent/AgentEventAdapterTest.kt:180-196`）自己注明：该用例是「手写一条 restoredSql 与自己比对（自证）」，「不是跨通道集成守卫」，发现不了布尔参数渲染、注释内 `?`、参数个数守卫三类失配。
- 其中「块注释内含问号」用例（`AgentLogTimeParityTest.kt:176-190`）直接把分叉钉成断言：agent 侧因注释里的 `?` 触发数量守卫、整句不还原，控制台侧正常还原。

T-AgentLogTime 当时把**日志时间配对**的键换成了 `logTimeKey`（占位符版 rawSql 指纹，`LogSqlModels.kt:408`），但**面板去重仍然用 `dedupeKey`**。分叉在去重面从来没有被修过，也没有守卫。

### 3.2 为什么这次偏偏是 INSERT

INSERT 的参数多（截图里 12 个以上），任一参数命中上表任一行就分叉。`sys_operation_log` 这类表通常带 `create_time`（`LocalDateTime`/`Timestamp`）——日期族规则直接分叉；SELECT 语句参数少、且多为数值/字符串，两侧口径恰好一致，所以折叠正常。这解释了用户说的「部分 INSERT」而不是「所有语句」。

### 3.3 为什么桥没有让位（三条可能，需日志裁决）

增强捕获在线时桥应当短路（`SqlCaptureGate.paused`，`SqlLogConsoleBridge.kt:854-880`），此时控制台条目根本不该产生。实际产生了，只有三种可能：

1. **旧版 agent 无 CONFIG 回执**：`captureConfirmedActive()` 要求 `configAckDisabled == false`（`AgentManager.kt:1351`），旧 agent 永远不回执 → 闸门恒 false → 桥永久放行。这是 KDoc 里登记的已知边界（`AgentManager.kt:1342-1349`），原文写着「两条通道同时产出同一条 SQL……靠列表去重窗口折叠」——而去重窗口正好在 3.1 失效。
2. **活性回落解封窗**：agent 静默 ≥10s 且控制台有 SQL 行时桥临时解封（`CaptureActivityFallback`，`SqlLogConsoleBridge.kt:906-1060`）。解封触发的那一行直接产出条目（`SqlLogConsoleBridge.kt:1081-1082`），随后同一条 SQL 的 agent 事件到达又立即重新让位——两条落在同一秒。
3. **CONFIG 下发→回执窗口抖动**：`configAckDisabled` 每次下发即清回「待确认」（`AgentManager.kt:1323-1326` 注释自述），窗口内闸门瞬时放开。

三条都只会造成「同一秒两条」，与截图吻合。判定靠日志（见 §五）。

### 3.4 附带发现：静默冲刷路径没打通道标记

`flushSettledEntries`（`SqlLogConsoleBridge.kt:447-472`）对 JdbcTemplate/ShardingSphere 的待闭合条目直接 `buffer.addEntry(entry)`，既没走 `emitEntries` 的 `captureProvenance` 打标，也没在循环内复查闸门（闸门只在 `scanAll` 外层判一次，`SqlLogConsoleBridge.kt:233`）。结果这类条目在归因诊断里显示「通道=UNKNOWN」，会干扰本次的现场裁决。与主症状无关，一并修。

## 四、修法

### 4.1 主修：跨通道等价键兜底折叠（不改既有去重语义）

在 `SqlLogEntryBuffer` 增加一个**只服务跨通道**的等价索引，键取两通道同源、不经过参数还原的原材料：

```
crossKey = logTimeKey(entry) + '\u0002' + logTimeParamSignature(entry.params)
```

`logTimeKey` = 占位符版 rawSql 指纹；`logTimeParamSignature` = 参数值序列（截断 256 字符）。两者就是 T-AgentLogTime 已经验证过能跨通道命中的口径（`LogSqlModels.kt:408-418`），`AgentLogTimeParityTest` 的四类失配形态下都命中。

入队流程：

1. `dedupeKey` 命中 → 走现有融合分支，一行不改。
2. `dedupeKey` 未命中 → 查 `crossChannelIndex`；命中且满足全部条件时走融合：
   - 两侧 `captureProvenance` 不同（`UNKNOWN` 视为可折叠）；
   - `|entry.timestamp - hit.timestamp| <= CROSS_CHANNEL_WINDOW_MS`（建议 5000，与 `HANDOFF_MERGE_GRACE_MS` 同量级，远小于 60s 去重窗）；
   - `canDedupe(entry)` 为真（ShardingSphere 维持不折叠，UI013 的既有裁决不动）。
3. 融合复用 `mergeDuplicateEntry`（补空不覆盖、通道归并 MERGED），**原位替换**，不新增行。
4. 跨通道融合**不计入 ⨉N 徽章**（`recordDedupeDrop` 只在同键去重分支调用）——它不是「重复 SQL 被丢弃」，是同一次执行的两份观测合并。单独计数供诊断行使用。

维护面与 `dedupeIndex` 完全同锁同生命周期：入队 upsert / 头部淘汰回扫 / `removeEntry` / `clear` / `rebuildDedupeIndexLocked` / `rebuildDedupeIndexAfterHandoff` / `dedupeExisting`。有界性照 `dedupeKeyPresence` 的做法：键域随索引同步增删；另加「时间窗惰性清理」（每次入队清掉超窗项），防长会话驻留。

### 4.2 辅修：参数渲染口径收敛（降低兜底触发率）

主修是兜底，能让键相同更好——同键直接命中，语义最干净，也不依赖 rawSql 两侧逐字一致。

做法：把 `AgentEventAdapter.renderParam` 改为调用共享的渲染函数（从 `LogSqlRestorer.renderParam` 抽出），保留 agent 侧两个安全护栏：

- 严格数量守卫（`countPlaceholders != params.size` → 不还原）**保留**，并补一条「参数序号必须 1..N 连续」校验——`ParamCapture` 用 `TreeMap` 按 index 存，某个 setter 不在白名单时会跳号，`sortedBy { it.first }` 排序后缺口被抹平，直接还原就是错位替换。
- 数值类型 + 值非数值形态（`type=Long, value="abc"`）→ 仍然加引号（`LogSqlRestorer` 目前只看类型不看形态，直出会产出非法 SQL）。这条要加进共享函数，会改变控制台侧的边缘行为，须先跑 `LogSqlRestorer` 既有测试确认无回归。

这一项改动面比主修大（触达控制台还原的全部既有用例），可独立成券、后置实施。主修先上就能消掉用户可见症状。

### 4.3 诊断：入队期跨通道失配留痕

现在只有「融合成功」有日志（`重复观测跨通道融合`，`SqlLogEntryBuffer.kt:365-369`），融合失败（键不同、各占一行）完全静默——这次现场就是靠截图反推的。

增加一行限频诊断：入队时若 `crossChannelIndex` 里存在**同 crossKey 但异 dedupeKey** 的条目，输出

```
跨通道同源未折叠: crossKey=xxxxxxxx dedupeA=yyyyyyyy(通道=CONSOLE_EP) dedupeB=zzzzzzzz(通道=AGENT)
```

只含指纹前缀与通道，不含 SQL 原文与参数值（沿用 `provenanceDiagText` 的隐私红线，`LogSqlModels.kt:497-505`）。修完之后这行仍要保留——它是「还原器又分叉了」的长期哨兵。

### 4.4 二期（可选）：控制台 DML 耗时估算

纯控制台模式（agent 不在线）下，INSERT/UPDATE/DELETE 永远没有耗时——这是日志源本身不含该信息，不是缺陷。若日志行带时间戳前缀，可以把 DML 的闭合时机从 `Parameters:` 行推迟到 `<==    Updates:` 行，用两行时间差估算耗时。

风险明确，故不进本期：`MyBatisLogParser` 在 `Parameters:` 行立即闭合是刻意的（`MyBatisLogParser.kt:139-141` 注释：避免去重窗口时序问题），延后会影响去重窗口、日志时间嗅探（`sniffLogTimestamp`）、静默冲刷三处时序。要做需要单独设计并补三类时序用例。

### 4.5 附带：静默冲刷补通道标记

`flushSettledEntries` 入队前补 `captureProvenance = CONSOLE_BRIDGE`，与 `emitEntries` 同款；循环内复查 `SqlCaptureGate.bridgeSuppressed()`（外层闸门只覆盖调用时刻，EDT 冲刷与 agent 接管沿之间仍有窗口）。

## 五、待验证前提（实施者先证伪）

**2026-11-05 本地复现已证实第 3 条**（复现分支 `t-cross-channel-dedupe-repro` 提交 `7642a00b`，只加测试文件）：

用本地 SQL Server 2022（`127.0.0.1:14333`，容器 `mpsv-mssql` 原为 Exited，`docker start` 后可用）真跑了一条 12 参数 INSERT，两侧 rawSql 完全一致，还原后**只有第 10、11 个参数不同**：

```
10: setBoolean(java.lang.Boolean)   = true            console `true`        agent `'true'`
11: setTimestamp(java.sql.Timestamp)= 2026-11-05 11:37:20.0
                                     console `TIMESTAMP '2026-11-05 11:37:20.0'`
                                     agent   `'2026-11-05 11:37:20.0'`
```

`buffer.size == 2`，两键 `8674ba1dc8d427f4` / `55f81f92dfdeae7e`。所以「表里有 `bit` 或 `datetime` 列」就必然分叉，与 `@Async`、SQL Server 方言都无关。复现覆盖的其余形态：布尔、Timestamp、LocalDateTime（agent 还留着 ISO 的 `T`）、块注释内 `?`、参数个数不等，全部实测分叉；纯 String+Long 与纯多行 SQL 不分叉（对照组）。

第 1、2 条仍待用户现场日志裁决（没有 `idea.log`，暂不能定论走的是哪条让位失效路径）。第 4、5 条在实施时按 KDoc 记录即可。

1. **现场 #11 的通道归属**：让用户在 `idea.log` 里 grep
   ```
   捕获归因|重复观测跨通道融合|增强捕获闸门回落|增强捕获活性回落|增强捕获接管沿|CONFIG 已生效
   ```
   - 出现 `捕获归因: 通道=CONSOLE_EP` 或 `CONSOLE_BRIDGE` → 确认 #11 是控制台条目；
   - 出现 `增强捕获闸门回落（桥恢复解析）` → 双通道并存窗口（§3.3 第 1/3 条）；
   - 出现 `增强捕获活性回落` → 解封窗（§3.3 第 2 条）；
   - 三者都没有却仍出现控制台条目 → 说明还有第四条让位失效路径，先查再修。
2. **两侧 agent jar 版本是否一致**（§3.3 第 1 条的前提）。`CONFIG 已生效` 行是否出现在该时段。
3. ~~那条 INSERT 的完整参数清单~~ **已由本地复现给出**：12 参数，命中布尔与日期族两类，见上。
4. **`crossChannelIndex` 的误配面**：同一 mapper 语句 5s 内执行多次且参数相同时，跨通道配对按 FIFO 可能错位。需确认融合只补空（不覆盖已有非空值）时错位影响可接受——耗时为毫秒级差异，位置来自同一条语句的同一栈帧，判定可接受并写进 KDoc。
5. **`canDedupe` 的 ShardingSphere 例外**：跨通道折叠是否要覆盖 SHARDINGSPHERE 与 agent 的并存形态。UI013 的既有裁决是「Logic 未绑定，文本相同不能证明同一次执行」，建议维持不折叠，但需确认这不影响本症状（本症状是 MyBatis）。

## 六、验收

### 6.1 新增用例（`src/test/kotlin/.../log/CrossChannelDedupeTest.kt`）

每个用例都走真实链路：`processConsoleLine`（控制台侧）→ `AgentEventAdapter.toEntry`（agent 侧）→ 同一 `SqlLogEntryBuffer` 入队，断言最终条数与字段。

| # | 形态 | 修复前 | 修复后期望 |
|---|---|---|---|
| 1 | 布尔参数 `true(Boolean)` | 2 条 | 1 条，耗时/位置/行数取 agent 侧 |
| 2 | 日期参数 `Timestamp` | 2 条 | 1 条 |
| 3 | 块注释内含 `?`（agent 数量守卫不还原） | 2 条 | 1 条 |
| 4 | 参数个数不等（agent 少捕一个） | 2 条 | **2 条**（设计边界，见 §9.9；捕获面已补齐，现场不再产生此形态） |
| 5 | 全限定类型名 `java.lang.Long` | 2 条 | 1 条 |
| 6 | 控制台先到、agent 后到 | 2 条 | 1 条，`captureProvenance=MERGED` |
| 7 | agent 先到、控制台后到 | 2 条 | 1 条，字段不被降级 |
| 8 | 两条真正不同的 SQL（rawSql 不同） | 2 条 | 2 条（不误折叠） |
| 9 | 同通道两条同 SQL | 1 条（既有去重） | 1 条（语义不变） |

用例 1~5 的「修复前 = 2 条」是断言基线，实施时先让它们红，再上修法转绿。

### 6.2 反向验证

临时注释掉 §4.1 的跨通道分支（照 `SqlLogEntryBuffer.kt:296-298` 既有探针注释的写法留锚点），用例 1~7 必须变红；恢复后转绿。两次读数都贴进实施记录。

### 6.3 回归面

- 定向：`SqlLogEntryBufferTest`、`SqlLogEntryBufferDedupeIndexTest`、`AgentLogTimeParityTest`、`AgentEventAdapterTest`、`CaptureActivityFallbackTest`、`SqlCaptureGateSniffTest`、`SqlLogPanelFilterTest`、`LogSqlModelsTest`。
- 若动 §4.2（共享渲染函数）：追加 `LogSqlRestorer` 相关全部用例 + `SqlLogConsoleBridgeMarkerTest`。
- 只跑定向，不跑全量、不跑构建校验、不 `clean`（收口由单独只读代理执行）。

### 6.4 真机复验

同一应用、同一 `saveAsync` 路径连续跑 20 次插入，要求：

- SQL 日志列表里 `sys_operation_log` 的 INSERT **不再出现无耗时/无位置的副本**；
- 每条都带 `(1 rows)` 与 `[XxxService.java:NN]`；
- `idea.log` 里 `跨通道同源未折叠` 计数为 0（若不为 0，把该行原文交出，说明还有新的分叉形态）。

## 七、风险与回归面

| 风险 | 说明 | 处置 |
|---|---|---|
| 误折叠 | 同一模板 5s 内多次执行，跨通道配对错位 | 融合只补空不覆盖；窗口取 5s（远小于 60s 去重窗）；用例 8/9 钉住不误折叠 |
| 性能 | 入队热路径多一次 `logTimeKey` + 签名计算 | `logTimeKey` 与 `dedupeKey` 同为 O(n) 指纹；只在 `dedupeKey` 未命中时计算（命中即返回，零额外成本）；索引 O(1) |
| 内存 | `crossChannelIndex` 无界增长 | 与 `dedupeIndex` 同生命周期 + 时间窗惰性清理 + 键域随条目淘汰同步 |
| ShardingSphere | UI013 裁决是「文本相同不能证明同一次执行」 | `canDedupe` 例外原样保留，跨通道折叠不覆盖 |
| 隐私 | 诊断行可能带 SQL 原文 | 只输出指纹前缀与通道，沿用 `provenanceDiagText` 红线 |
| 徽章语义 | 跨通道融合是否计入 ⨉N | 不计入；徽章只统计同键重复丢弃 |

## 八、实施顺序建议

1. §4.3 诊断先行（低风险，先把现场证据钉死，也为 §五 的裁决提供读数）。
2. §4.1 主修 + §6.1 用例 1~9 + §6.2 反向验证。
3. §4.5 附带修（一行级别）。
4. §4.2 辅修（改动面大，单独券，需先跑还原器既有用例）。
5. §4.4 二期评估（不承诺本期）。

## 九、实施记录（2026-11-05，分支 `t-cross-channel-dedupe`）

### 9.1 改动文件

| 文件 | 改动 |
|---|---|
| `src/main/kotlin/.../log/SqlLogEntryBuffer.kt` | 主修：`crossChannelIndex`（crossKey = `logTimeKey` + `'\u0002'` + `logTimeParamSignature`）、`canFoldAcrossChannels`（两侧通道不同 + 5s 窗）、`purgeExpiredCrossChannelLocked`、`replaceEntryLocked` / `repointCrossChannelLocked`；入队未命中 dedupeKey 时查跨通道索引并复用 `mergeDuplicateEntry` 原位替换；维护面与 `dedupeIndex` 同锁同生命周期（入队 upsert / 淘汰摘键 / 融合重指 / `removeEntry` 单键重算 / `clear` / `rebuildDedupeIndexLocked` / `rebuildDedupeIndexAfterHandoff`，后者同 grace 截断）；`bindErrorToRecent` 原位替换后同步重指两个索引（此前 dedupeIndex 也留陈旧实例） |
| `src/main/kotlin/.../log/LogSqlModels.kt` | §4.3 诊断文本：`crossChannelUnfoldedDiagText` / `crossChannelMergeDiagText`（只有指纹前缀、通道与升级面，沿用 `provenanceDiagText` 红线） |
| `src/main/kotlin/.../log/SqlLogConsoleBridge.kt` | §4.5：`flushSettledEntries` 入队前补 `captureProvenance = CONSOLE_BRIDGE`，循环内复查 `SqlCaptureGate.bridgeSuppressed()`（被丢弃条目计数留痕） |
| `src/test/kotlin/.../log/CrossChannelDedupeTest.kt` | 新增 12 例，全部走 `processConsoleLine` → `buffer.addEntry(通道=CONSOLE_BRIDGE)` + `AgentEventAdapter.toEntry` → 同一 buffer |

### 9.2 与计划的差异

1. **诊断行拆成两条**：`跨通道同源未折叠` 只在「同 crossKey + 异 dedupeKey + 同窗内、但折叠条件不满足（同通道已知标记）」时输出；折叠成功另打 `跨通道等价键融合`。这样 §6.4「`跨通道同源未折叠` 计数为 0」仍是可判定的验收口径——若把折叠成功也打成「未折叠」，该计数永远不为 0。两条共用 `logAttributionThrottled` 限频。
2. **折叠判据加了时间窗**（计划 §4.3 字面判据只有「同 crossKey + 异 dedupeKey」）：不加窗时，同一模板同一参数在 60s 外的两次**独立执行**（同键已被窗口去重放行、跨通道渲染又分叉）会被误报为「未折叠」。窗口取 `CROSS_CHANNEL_WINDOW_MS = 5000`。
3. **淘汰路径不反扫跨通道索引**：索引存该键**最后入队**实例、淘汰取**最旧**，故「索引命中被淘汰者」即等价于「队内已无同键余条」，直接摘键（唯一键稳态零额外 O(n)）；`removeEntry` 可能拿走最新实例，保留反扫。
4. 计划 §6.1 表格第 5 行（全限定类型名）与第 4 行（参数个数不等）**未能按「修复后 1 条」验收**，原因见 9.4。

### 9.3 测试读数

| 任务 | 用例类 | 读数 |
|---|---|---|
| `testFast --tests ...CrossChannelDedupeTest` | CrossChannelDedupeTest | `12 tests completed`，XML 汇总 tests=12 / skipped=0 / failures=0 / errors=0 |
| `testFast`（§6.3 定向回归，9 类） | SqlLogEntryBufferTest(59) / SqlLogEntryBufferDedupeIndexTest(18) / AgentLogTimeParityTest(14) / AgentEventAdapterTest(13) / CaptureActivityFallbackTest(8) / SqlCaptureGateSniffTest(4) / LogSqlModelsTest(12) / SqlErrorBindBufferTest(9) / O147HistoryUpgradeTest(26) | 合计 163 例，0 失败（`BUILD SUCCESSFUL in 1m 43s`） |
| `test --tests ...`（平台桶，`testFast` 按「源码含 com.intellij 即排除」不收） | ConsoleSettleFlushTest(5) / SqlLogPanelFilterTest(36) | 合计 41 例，0 失败（`BUILD SUCCESSFUL in 1m 25s`） |

### 9.4 反向验证（§6.2）两次读数

临时把 `canFoldAcrossChannels` 首行改为 `if (true) return false`（等价摘除跨通道折叠；直接注释 `if` 条件会丢 `crossHit` 的智能转换导致编译失败，故改在判据函数上摘）：

- **红**：`12 tests completed, 8 failed` —— 恰为 8 个依赖兜底的用例（布尔 / 日期 / LocalDateTime / 现场 12 参数 / 块注释 `?` / agent 侧点号类型 / 控制台先到 / agent 先到）；4 个非兜底用例（参数个数不等、点号类型名、不同 SQL、同通道同 SQL）保持绿。
- **绿**：恢复后 `BUILD SUCCESSFUL`，XML 汇总 tests=12 / failures=0。

### 9.5 计划前提被证伪（2 条）

> **2026-11-05 后续：两条均已在 `t-cross-channel-residual` 清掉，见 §9.7。**

1. **§六 表格第 4 行「参数个数不等 → 修复后 1 条」不成立**。crossKey 含参数值签名，agent 少捕一个参数时签名是另一串（`1\u00012` vs `1`），索引根本查不到。当时钉成「已知残余」用例（断言仍 2 行）。**2026-11-05 复验后的准确表述**：§9.7 修的是**捕获面**（现场不再产出「少捕」形态），不是让这种形态能折——手工构造一个缺参数的 agent 事件，今天仍留 2 行，这是**设计边界**而非缺陷，见 §9.9。
2. **§六 表格第 5 行「全限定类型名」的原始形态不可达**。`MyBatisLogParser.parseParams` 的类型分组是 `[A-Za-z_$][\w$]*(?:\[\])*`（**不含点号**），`42(java.lang.Long)` 匹配不上带类型分支 → 回落逗号粗分 → `value="42(java.lang.Long)"`、`type=null`，签名因此与 agent 侧不同；且 agent 侧 `ParamCapture.simpleTypeName` 本来就会剥包名。真实 MyBatis 日志打印简单类名，故此形态不构成现场风险。§9.7 已把类型分组放宽到允许点号，该形态现在能折。

另：复现代理实测（2026-11-05）确认现场是 12 参数 INSERT，**只有第 10（setBoolean）、第 11（setTimestamp）个参数分叉**，与 @Async / SQL Server 方言无关；`LocalDateTime` 还多一处差异（控制台 `TIMESTAMP '…'` / agent `'…T…'` 的 ISO `T` 未归一）。已按此补「现场 12 参数」与「LocalDateTime」两条用例。

### 9.6 遗留

- ~~§4.2（参数渲染口径收敛，含参数序号连续性校验）~~ —— §9.7 用「修捕获面 + 放宽解析器」替代，未做还原器收敛；剩余未覆盖面见 §9.7 末条。
- §4.4（控制台 DML 耗时估算）——二期，未动。
- 真机复验（§6.4）未做：需用户应用 + 20 次 `saveAsync` 插入，且要看 `idea.log` 里 `跨通道同源未折叠` 计数。

### 9.7 残余清理（2026-11-05，分支 `t-cross-channel-residual`，合并 `2cfd509c`）

两条残余都没有走「给去重加二级匹配」，而是回到源头修：

1. **agent 捕获面缺口**（`ParamCapture.java`）：
   - 白名单补 `setNClob` / `setRowId` / `setSQLXML` / `setRef` 四族——此前不织入，参数整段缺失；
   - `tryCapture` 的 `args.length > 3` 放宽到 `> 4`——`setObject(int, Object, int, int)` 四参重载此前**织入器按 ≥2 参织了钩子、捕获层却拒绝**，形成「织了不捕」。
   两侧参数个数因此对齐，跨通道参数值签名相同，一级兜底直接命中，去重逻辑一行未改。
2. **解析器类型分组**（`MyBatisLogParser.parseParams`）：`[A-Za-z_$][\w$]*` → `[A-Za-z_$][\w$.]*`，允许 `java.lang.Long` 形态，使 `LogSqlRestorer.PLAIN_TYPES` 里既有的全限定名映射真正生效（此前那段映射是死代码）。

测试与读数：
- 新增 `ParamCaptureTest`（agent 子项目，5 例）：四参 `setObject`、四族 setter、三参回归、非参数 setter 拒绝。
- `CrossChannelDedupeTest` 两个残余用例改为「已折叠」与「签名不等时不误折」的安全边界。
- 绿：`CrossChannelDedupeTest` 12/0/0、`ParamCaptureTest` 5/0/0、`ParamEdgeCaseRegressionTest` 20/0/0、`DualParserIntegrationTest` 11/0/0、`SqlLogCaptureRealtimeScenarioTest` 9/0/0、`AgentLogTimeParityTest` 14/0/0、`CaptureRulesTest` 15/0/0。
- 反向验证：还原捕获面 → `ParamCaptureTest` 恰 2 红（四参 setObject、四族 setter）；还原正则 → 点号类型用例恰 1 红。恢复后转绿。

**仍未覆盖**：`byte[]` / `Blob` / `Clob` 类参数的**值文本**两侧天然不同（控制台日志是 `[B@1a2b`，agent 是 `<byte[]>`），签名无法对齐，兜底接不住。这类参数在普通业务里少见，且没有稳定可比的文本形态；若现场出现，正解是让跨通道匹配退到「rawSql + 参数个数 + 参数位置类型」，属独立券。**→ 已于 §9.8 用签名归一解决。**

### 9.8 二进制参数签名归一（2026-11-05，分支 `t-binary-param-signature`，合并 `83e2520f` 之后）

§9.7 末条登记的未覆盖面。它的危险不在频率，而在**完全静默**：控制台日志对二进制参数打对象地址
（`[B@1a2b3c`、`com.mysql.cj.jdbc.Blob@7f3a`），agent 侧 `ParamCapture.renderValue` 检测到默认
toString 后降级为 `<byte[]>` / `<Blob>`——两侧值文本不可比 → 签名不等 → crossKey 查不到 → 不折叠；
而 `跨通道同源未折叠` 哨兵只在 crossKey **相同**时才触发，于是既不会折叠也不会留痕，真机上只能靠
截图反推，与最初报障同一处境。

修法（`LogSqlModels.kt`）：

- `paramSignatureToken`：二进制族归一为 `<binary>`——**只影响匹配，不影响展示**（列表仍显示各自原文）。
- `isBinaryParam` 三条判据任一成立：类型标注命中二进制族（`byte[]` / `[B` / `Blob` / `Clob` / `NClob` /
  `InputStream` / `Reader` 及全限定名）；值是对象默认 toString（`[B@hex`）；值是 agent 降级形态
  （`<byte[]>` / `<Blob>`）且内层类型名命中同一集合。普通字符串（如 `deadbeef`）不会被误判。
- `logTimeParamSignature` 改走该函数；null token 仍是 `"\u0000"`，与原口径逐字一致，既有日志时间配对语义不变。

测试与读数：`CrossChannelDedupeTest` 补 3 例（`byte[]`、`Blob`、不误伤普通字符串）；绿读数
`CrossChannelDedupeTest` 15/0/0、`AgentLogTimeParityTest` 14/0/0、`SqlLogEntryBufferTest` 59/0/0。
反向验证：摘掉归一 → 恰 2 红（`byte[]`、`Blob`），普通字符串仍绿；恢复后转绿。

副作用边界（已写进 KDoc）：归一后「同一条 SQL 连续两次执行且参数是不同的二进制内容」签名相同——
但二进制值本来就不可比，且跨通道折叠还有 5s 窗 + 异通道双重限制，影响限于毫秒级耗时归属。

### 9.9 修复后复验（2026-11-05，复用原复现夹具）

用**最初那个复现代理**（同一工作树、同一份夹具，先 `git merge master` 再跑），不是新写验证。
分支 `t-cross-channel-dedupe-repro`：`e3c93e4a`（merge master）+ `70d44bf4`（断言更新）。

**先原样跑旧断言（一字未改）**，6 例 `size == 2` 变红、实际均为 1：布尔 / Timestamp / LocalDateTime /
块注释内 `?` / 多行+datetime / 现场 12 参数。这组红本身就是修复生效的证据。

一个值得留档的细节：红的只是**后一条** size 断言，它前面的 `assertNotEquals(consoleKey, agentKey)`
**通过**——说明折叠不是「两套还原器终于对齐」，而是兜底键在起作用。与 §9.1 的定性一致。

真库复验（本地 SQL Server `127.0.0.1:14333`）：

```
console.dedupeKey: 8674ba1dc8d427f4     ← 与修复前逐字相同
agent.dedupeKey  : 55f81f92dfdeae7e     ← 与修复前逐字相同
buffer.size      : 1
merged.provenance: MERGED
merged.duration  : 8ms
merged.source    : OperationLogService.java:42
merged.update    : 1
merged.database  : SQL Server · master@127.0.0.1:14333
```

两键未变、结果从 2 行变 1 行——还原器口径没动，动的只有判重路径，符合设计。

九形态逐项结论：

| 形态 | 结果 | 键相等 | 通道 |
|---|---|---|---|
| String+Long（对照） | 同键折叠 | 是 | MERGED |
| Boolean | 兜底折叠 | 否 | MERGED |
| Timestamp | 兜底折叠 | 否 | MERGED |
| LocalDateTime | 兜底折叠 | 否（agent 仍留 ISO `T`） | MERGED |
| 块注释内 `?` | 兜底折叠 | 否 | MERGED |
| 多行 SQL | 同键折叠 | 是 | MERGED |
| 多行 + Timestamp | 兜底折叠 | 否 | MERGED |
| 12 参数现场 | 兜底折叠 | 否 | MERGED |
| 点号类型名 | 兜底折叠 | 否 | MERGED |
| 二进制 | 兜底折叠 | 否 | MERGED |
| **参数个数不等** | **仍 2 行** | 否 | — |

**唯一剩余形态的定性（决定不修）**：`crossChannelKey` 的签名是逐参数值 join，控制台 2 个、agent 1 个
时长度就不同，结构上折不上——不是数值碰巧不匹配。不修的两条理由：

1. §9.7 已把捕获面补齐（白名单四族 + 四参 `setObject`），现场不再产出「少捕」形态；这个用例是手工
   构造缺参数的 agent 事件，模拟的是「捕获再次出缺口」。
2. 签名不等即无法证明是同一次执行的两份观测，此时留两行是**保守正确**。硬折要靠「共同参数前缀匹配」，
   会在同模板多次执行时引入错配风险，收益只在捕获再次出缺口时才兑现。

两个附带发现：

- 点号类型名那条同时验证了 §9.7 的解析器改动：控制台 restoredSql 是 `TIMESTAMP '…'`，说明
  `java.sql.Timestamp` 真被 `parseParams` 吃进去了；且它的两个 key 与简单名 `Timestamp` 那条**完全相同**，
  等于附证「点号名与简单名等同」。
- agent 侧 `byte[]` 的实际降级形态是 `<[B>`（`simpleTypeName` 对数组返回 JVM 名），不是 §9.8 正文写的
  `<byte[]>`。两种形态都在 `AGENT_DEGRADED_REGEX` + `BINARY_TYPE_MARKERS` 覆盖内，行为一致，措辞订正于此。

### 9.10 与「SQL 去重」开关解耦（2026-11-05 真机第二次报障，`67520e95`）

用户沙盒复验仍出现无字段副本。现场证据（截图）：列表里同一条语句一条带 `96ms [MySQL] [UserServiceImpl.java:50]`、
另一条只有 `—`；底部状态栏「控制台: 已捕获 8 个 / 增强: 已收事件 14 条」——**两通道都在产出**（桥没让位，
属 §3.3 已知形态）；右上角「SQL 去重」复选框**未勾选**。

根因是跨通道兜底被绑在 `dedupeEnabled` 上，两处：

1. `crossKey` 的构造（`val crossKey = if (dedupeEnabled && canDedupe(entry)) …`）与 `crossChannelIndex`
   的**全部维护面**（入队 upsert / 淘汰回收 / `removeEntry` 重算 / `bindErrorToRecent` 重指 /
   handoff 重建）都在 `if (dedupeEnabled)` 里。用户一关「SQL 去重」，兜底整段失效，索引压根没维护，
   连 `跨通道同源未折叠` 诊断行都不会留——现场只能靠截图反推。
2. 折叠条件还要求 `crossDivergent`（`dedupeKey` 不同）。原判据的前提是「同键副本由上面的同键去重分支
   处理」，但那条分支同样受 `dedupeEnabled` 门控——去重一关，**键相同的副本两边都没人管**。

修法：跨通道折叠只依赖 `canDedupe`（ShardingSphere 例外）+ 5s 窗 + 异通道，与用户去重偏好无关；
折叠条件去掉 `crossDivergent`（去重开启时同键分支先命中、走不到这里，故不改变既有路径）；
`crossChannelIndex` 的五个维护面全部移出 `dedupeEnabled` 判断；`rebuildDedupeIndexAfterHandoff` 里
把「去重关闭就 return」改成只在 dedupe 部分条件化，跨通道索引照常重建。

语义依据：去重开关管的是「重复执行要不要折叠」，跨通道兜底管的是「同一次执行的两份观测要不要合并」
——后者不是用户可选的偏好，留两条只会让用户以为发生过两次。

测试：`CrossChannelDedupeTest` 新增 3 例（去重关闭时仍折叠 / 两次不同执行不误折 / 开关来回切换后仍折叠）；
绿读数 18 + 59 + 18 + 14 全绿。反向验证分两次：还原开关依赖 → 恰 2 红；还原 `crossDivergent` → 恰 1 红。

