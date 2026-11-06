# T-IbatisPanwei：iBatis 2.x + 磐维（Panwei）的增强捕获缺失与参数方括号误入 SQL（2026-11-06）

- 立项：2026-11-06 10:33
- 状态：**已实施（2026-11-06，四路后台并行后合入 `t-ibatis-panwei-integration`）**：券 A/B/D/E/F 落地，券 C 阻塞于公网无驱动 jar（见 §十二），券 G 文档随本批回填
- 目标分支：`master`（券 A/B 可独立先落；券 C 需驱动 jar 才能开工）
- 来源：用户真机报障——国产库「磐维」（Oracle 模式，驱动 `org.panweidb.Driver`）①增强捕获抓不到；②最终 SELECT 把中括号带进了 SQL；用户补充：项目是 iBatis（非 MyBatis 3）、日志框架 log4j2
- 取证材料：用户控制台原文（`{pstm-N}` 三行一组，见 §2.1）、两张预览截图（INSERT 全 NULL / SELECT 带中括号）

## 一、结论

这是**三个独立缺陷叠加**，不是一条链上的同一个问题：

| 编号 | 症状 | 根因一句话 |
|---|---|---|
| A | 增强捕获（agent）对磐维零产出 | 磐维驱动三面（connect / 语句 / prepare）**均未登记**；未知驱动只能走接口兜底，而兜底有 **5000 类扫描上限 + attach 只跑一轮**，大型遗留应用大概率漏扫 |
| B | SELECT 变 `'[00340025002200200006]'`；INSERT 参数大面积变 `NULL` | 控制台参数行是 **iBatis 2.x 的 `Parameters: [...]` 形态**（Java 数组 `toString`），`parseParams` 只认 MyBatis 的 `值(类型)` 语法：单值→整段括号被当字面量；多值含 `null`→只提取到 null 项、其余值被静默丢弃并**错位**填充 |
| C | 角标不显示库名、预览「临时文件解析失败」、方言疑似回落 MySQL | `jdbc:panweidb:` 未进 URL→方言映射（`JdbcUrlDialects` / `UrlSubprotocolDialects`），Oracle 语法（`SYSDATE` / `dual` / `to_date`）走了 MySQL 渲染 |

**修复顺序有一条硬约束**：B 不能只修「剥方括号」。`00340025002200200006` 是 20 位纯数字串，剥掉括号后它在 `LogSqlRestorer.renderUntyped` 里会命中数字正则而**裸出成数值字面量**——`WHERE ORGID = 00340025002200200006` 在 Oracle 模式里是数值比较，比现状（多一对括号）语义更错。必须同批接入 iBatis 紧随其后的 `Types: [...]` 行，拿到 `java.lang.String` 才会按字符串引号渲染。

## 二、证据

### 2.1 用户日志原文（iBatis 2.x 形态，节选）

```
2026-11-06 10:14:23.506 DEBUG 8984 --- [main] j.s.PreparedStatement : {pstm-100021} Executing Statement: INSERT INTO bs_rwd_group_info_his (...) VALUES (?,?,...,to_date(?,'YYYYMMDDHH24MISS'),...)
2026-11-06 10:14:23.506 DEBUG 8984 --- [main] j.s.PreparedStatement : {pstm-100021} Parameters: [00340025002200200006, null, 测试OrgManager, 市场经营部, OA, 1, 3, 0, ..., 0000]
2026-11-06 10:14:23.506 DEBUG 8984 --- [main] j.s.PreparedStatement : {pstm-100021} Types: [java.lang.String, null, java.lang.String, ..., 0000]
2026-11-06 10:14:23.517 DEBUG 8984 --- [main] j.s.Connection        : {conn-100022} Preparing Statement: select ... from bs_rwd_group_info where ORGID = ?
2026-11-06 10:14:23.518 DEBUG 8984 --- [main] j.s.PreparedStatement : {pstm-100023} Executing Statement: select ... from bs_rwd_group_info where ORGID = ?
2026-11-06 10:14:23.518 DEBUG 8984 --- [main] j.s.PreparedStatement : {pstm-100023} Parameters: [00340025002200200006]
```

判读要点：

1. **logger 名是 `java.sql.Connection` / `java.sql.PreparedStatement`**（`%-40.40logger{39}` 一类的缩写 pattern 把段首字母缩成 `j.s.*`；用户用 log4j2 后端，pattern 同形），`{conn-N}` / `{pstm-N}` 是 iBatis 2.x JDBC 日志代理的实例计数器。这不是 MyBatis 3 的 `==> Preparing:` / `==> Parameters:` 形态。
2. 参数与类型是**两条独立行、按位置对应**；`Parameters:` 是 Java 集合 `toString`（外层 `[ ]` 是结构，不是值的一部分）。
3. 用户已把设置里的「SQL 前缀」从默认 `Preparing:` 改成了 `Executing Statement:`（截图可见），参数前缀仍是默认 `Parameters:`——所以 SQL 正文抓到了，参数按 MyBatis 语法解析，才出现 B。

### 2.2 解析器实测（用仓库现有正则回放三种输入）

`PARAM_REGEX`（`MyBatisLogParser.kt:321`）是「`值(类型)` 或 `null`」语法；用该正则跑用户日志的真实参数段：

| 输入 | 匹配结果 | 后续行为 |
|---|---|---|
| `[00340025002200200006]` | 无匹配 | `result.isEmpty()` → 回落 `text.split(',')` → 单值 `[00340025002200200006]`（带括号）→ `renderUntyped` 数字正则不匹配（首字符 `[`）→ 引号 → **`'[00340025002200200006]'`** |
| `[00340025002200200006, null, 测试OrgManager, …]`（36 项、13 个 `null`） | **只匹配到 13 个 `null`**，其余 23 个非空值全部丢弃 | 参数表长度 13 ≠ 占位符 36 → `restore` 按序把**前 13 个占位符**填成 `NULL`，其余保留 `?` → 用户截图里的「INSERT 一片 NULL」 |
| `00340025002200200006(String), null`（MyBatis 正常行） | 两项都匹配 | 现有行为，零影响 |

这条实测同时解释了两张截图：**SELECT 带括号**与**INSERT 全 NULL**是同一个根因的两种表现（有 `null` 项 / 无 `null` 项）。

### 2.3 代码读数

- `src/main/kotlin/.../log/MyBatisLogParser.kt:296` `parseParams`、`:321` `PARAM_REGEX`——只认 `值(类型)` / `null`；`result` 非空时**不再回落**逗号粗分（数据丢失即发生在这里）。
- `src/main/kotlin/.../log/LogSqlRestorer.kt:88` `renderUntyped`——无类型时「纯数字/true/false 裸出，其余引号」。数字串会裸出，这是 §一 那条硬约束的出处。
- `src/main/kotlin/.../log/ListParamRenderer.kt:11-18`——上一次同类修复（T-ListParamCollect）的**边界写死为「只在 `IN (...)` 区间内剥方括号」**；本次是 `WHERE ORGID = ?` 的单值位置，所以那次修复不覆盖。
- `src/main/kotlin/.../config/PluginSettings.kt:825-826`——SQL/参数前缀是用户可配项，默认 `Preparing:` / `Parameters:`。
- `sub-projects/sql-visualizer-agent/.../DriverRegistry.java`——连接面名单无 `org.panweidb.*`。
- `sub-projects/sql-visualizer-agent/.../WeaveSpecs.java:44-188`——语句面/prepare 面名单无 `org.panweidb.*`。
- `sub-projects/.../CaptureBudget.java:65` `DEFAULT_SCAN_LIMIT = 5000`；`:79` 字节级兜底默认关。
- `sub-projects/.../CatchAllMatcher.java:177-192`——**attach 路径只跑一轮** `runPass`；只有 premain 路径才排「启动后补挂轮」。
- `sub-projects/.../CatchAllMatcher.java:331` `PROCESSED.add(c)`——同一类按失败/成功都只处理一次（失败不重试）。
- `sub-projects/.../SqlHookTransformer.java`——transform 只认「白名单 + attach 扫描接受表 + 默认关的字节兜底」三种来源；attach 之后才加载的新类不在其中。
- `src/main/kotlin/.../log/agent/JdbcUrlDialects.kt:15-42`、`src/main/kotlin/.../sql/DialectInference.kt:16-42`——子协议表无 `panwei` / `panweidb`。

## 三、根因细分

### 3.1 参数还原（B，可直接复现）

iBatis 2.x 的 JDBC 日志代理每次执行打三行：`Executing Statement` → `Parameters: [...]` → `Types: [...]`。控制台桥把它们按「SQL 前缀 + 参数前缀」两段式喂给 `MyBatisLogParser`：

- `Parameters:` 行命中分支 4（`MyBatisLogParser.kt:130-143`），`parseParams` 按 MyBatis 语法解析；
- `Types:` 行不命中任何分支，作为噪声丢弃——**类型信息在这一步就没了**；
- 含 `null` 的列表因为正则的 `null` 分支命中而 `result.isNotEmpty()`，既不报错也不回落，坏数据被当成正常参数表继续往下走。

两个次生影响：
1. `restoredSql` 写错 → 预览/复制/执行拿到的是**不可执行或语义错误**的 SQL；
2. `params` 数量不对 → 预览参数面板与占位符对不上（截图里「无解析参数」即此链的末端表现之一）。

### 3.2 增强捕获（A，候选链 + 分流判据）

磐维未登记 → `DriverRegistry`/`WeaveSpecs` 三谓词全 false → 只能走接口兜底。兜底要真正产出事件，需要**同时**满足：

1. attach 时 `getAllLoadedClasses()` 的前 5000 个类里**同时**扫到连接类与语句类（`PgConnection` / `PgPreparedStatement` 同族）；
2. 两个类都 `isModifiableClass == true` 且 `retransformClasses` 不抛；
3. 织入形态是兜底面（`EXEC_PARAM` / `EXEC_CTX` / `BATCH` / `PARAM`）——**无参 `executeQuery()` 属 `EXEC_CTX`，SQL 文本来自 prepare 面登记表**；只命中语句类、没命中连接类时，钩子在了但 SQL 取不到，事件同样不出。

三条任一不成立就是「零条目」。5000 对大型遗留应用是什么概念：Spring + iBatis + 中间件的常规应用轻松 2~4 万类，`getAllLoadedClasses()` 顺序不保证，单类被扫到的概率约 12%~25%，连接类与语句类**同时**被扫到的概率更低。

另一条独立成因（需现场读数排除）：应用在 attach **之后**才懒加载数据源 → 驱动类在扫描之后才加载 → 白名单未登记 + 兜底接受表无此名 + 字节兜底默认关 → 永远不织。

> 说明：URL 缺失**不是**事件被丢弃的原因。`AgentEventAdapter` 对未知/空 URL 只把角标置 null，不做门禁（`AgentEventAdapter.kt:64-69`）；非白名单类的 URL 还能由 `UrlResolver` 反射 `getURL()` 兜到。

### 3.3 方言与预览（C）

`jdbc:panweidb:` 不在两张子协议表里 → 角标 `null`、`DialectInference` 无结论 → 渲染方言回落到 MySQL。用户 SQL 是 Oracle 语法（`SYSDATE`、`dual`、`to_date(?, 'YYYYMMDDHH24MISS')`），MySQL 方言下预热建临时 PSI 文件失败，即截图里的「预览以简化模式打开 — 临时文件解析失败」（`PreviewDegradationReason.PREWARM_ERROR` / `PSI_UNAVAILABLE` 二选一，需日志确认）。

## 四、影响面

- 磐维 / 任何未登记驱动的用户：增强捕获静默零产出（面板有控制台产出时，连空态诊断行都不显示，见 §5 券 D）。
- iBatis 2.x 用户：只要配置了 `Executing Statement:` 前缀就会踩 B；不配则一条也抓不到（默认前缀 `Preparing:` 匹配不上）。
- 参数写错的条目会感染：复制 SQL、执行 SQL、预览、历史、跨通道去重键（参数签名不同 → 与控制台/agent 同一次执行折不拢，出现双条目）。

## 五、修复方案（券）

### 券 A（P0）参数行支持 iBatis 列表形态 + `Types:` 行

在 `MyBatisLogParser` 内新增 **iBatis 分支**（不新造并行解析器，避免同一条语句被两个解析器各产一条）：

1. **行级识别**：`\{pstm-\d+\}\s*Executing Statement:` / `\{pstm-\d+\}\s*Parameters:` / `\{pstm-\d+\}\s*Types:`。命中即由 iBatis 分支消费该行并 `return`，不再落到用户配置的前缀分支（用户现在配的 `Executing Statement:` 不会造成双条目）。
2. **列表解析**：参数段若整段是 `[ ... ]`（剥一层，元素按逗号切分、`null` 记为 null），走新分支；**MyBatis 既有语法一行不改**（现有 `值(类型)` 与 `null` 分支保持原样，零回归）。
3. **类型接入**：`Parameters:` 行不立即闭合，先挂起（键 = `pstm id`）；下一行是同 id 的 `Types:` 就按位置并入 `LogSqlParam.type` 再闭合。兜底闭合点：同 id 的新 `Executing Statement:`、其它语句行、`closePending()`、静默冲刷。
4. **边界**（写进类注释，不猜）：
   - 元素内逗号/嵌套方括号（`List.toString` 不可逆）按逗号切分，登记为已知边界（与 `ListParamRenderer` 同口径）；
   - `Types:` 缺行时按无类型启发式渲染（回到现状口径），不伪造类型；
   - 批处理（iBatis batch 一次性 prepare、多次 `Parameters:`）一期不支持，登记缺口。

**为什么必须同批接 Types**：见 §一 的硬约束；`00340025002200200006` 无类型会裸出成数字字面量。

### 券 B（P0）iBatis 行识别：免手配前缀

- `{pstm-N} Executing Statement:` 作为唯一 SQL 入口（执行态），**`{conn-N} Preparing Statement:` 不产条目**——iBatis 每条语句都 prepare 一次、紧接 execute，两条都收会翻倍（D-2）。
- 配对键优先用 `pstm id`，线程桶兜底；这样即便控制台多线程交错、`Parameters:` 与 `Types:` 之间夹了别的行，也按 id 归位。
- 不改变现有「用户自定义前缀」语义：iBatis 分支只认带 `{pstm-N}` 标记的行，其它行仍走原路径。

### 券 C（P0）磐维驱动登记（增强捕获主修）

前置（缺一不可，禁猜）：
1. 拿到 `panwei-jdbc*.jar`（用户提供或从应用 `WEB-INF/lib` 取）；
2. `javap -p` 列出 `org.panweidb.jdbc.*` 的类名 + `execute` 族 / `setXxx` 族描述符；
3. 用真实 jar 字节跑现有 `StatementWeaverVendorCoverageTest` 的「织入 → 隔离子 loader 定义 → 初始化过 JVM 校验」流程。

落地：
- `DriverRegistry` 加 `org.panweidb.Driver`（连接面）；
- `WeaveSpecs` 加语句面（`PgStatement` / `PgPreparedStatement`，是否含 Callable 按 javap 定）+ prepare 面（`PgConnection`）；
- `StatementWeaverVendorCoverageTest` 的 `STATEMENT_FACE_CLASSES` 与守卫同步加组（厂商前缀 `org.panweidb.` 两段即可，无需进 `THREE_SEGMENT_VENDORS`）；
- 若该类族与 pgjdbc 同形（`org.panweidb.util.PSQLException` 与 `org.postgresql.util.PSQLException` 同名结构，高度疑似重定位 pgjdbc），形态可参照 openGauss 族登记，但仍以 javap 实测为准。

### 券 D（P1）未知驱动覆盖诊断（防下一次静默）

动因：本次「增强零产出」全靠人工猜；而且控制台有产出时，空态诊断行（`captureDiagnosticsText` 要求 `entryCount == 0`）根本不显示，用户看不到 attach 覆盖读数。

- agent 侧：兜底扫描时把「接口命中但不在白名单」的类名记入**有界列表**（最多 N 个，只到类名）；
- 心跳带出 → `AgentAttachStats` 增字段 → 状态栏/诊断行显示「发现未登记 JDBC 实现：`org.panweidb.jdbc.PgPreparedStatement`（未增强）」；
- 这条对下一个国产库直接复用，也是券 C 拿不到 jar 时的取证替代路径。

### 券 E（P1）兜底扫描上限与 attach 补挂

- 现状：`DEFAULT_SCAN_LIMIT = 5000`、attach 单轮、失败不重试。
- 方案（二选一，以实测耗时为准，红线=不拖慢宿主）：
  - E1：扫描前先按**类名快筛**（`.jdbc.` / `Statement` / `Connection` / `Stmt`），只对候选做 `isAssignableFrom`，然后把上限提到全量；
  - E2：保留上限，但 attach 路径加 2~3 轮补挂（复用 `PROCESSED` 去重），覆盖懒加载数据源。
- 需要 `CaptureBudgetTest` 新增：上限边界、补挂去重、kill switch 不被绕过。

### 券 F（P2）URL 方言映射

- `JdbcUrlDialects` 加 `panwei` / `panweidb` → 角标 `Panwei（磐维）`；
- `UrlSubprotocolDialects` 同键 → 渲染方言（D-1 决策：推荐 `Oracle`，因为用户现网是 Oracle 模式且 SQL 全 Oracle 语法；PG 模式用户可手动选 openGauss）；
- 子协议串以实际数据源 URL 为准（P-2），两个都登记即可覆盖。

### 券 G（P2）文档与指引

- `docs/架构设计/` 归属文档补 iBatis 日志格式与解析分支；
- 用户指引：「iBatis 2.x 项目无需改前缀」+「磐维（Oracle 模式）支持的方言选择」；
- 方言矩阵页若方言映射有变，同步等级说明。

## 六、验证

### 6.1 定向测试（实施子代理随报告交出）

- `parseParams` / `MyBatisLogParser`：用户日志原文三组（含 36 参数 INSERT、单参数 SELECT、`Types:` 行）→ 断言 `restoredSql` 无方括号、`0034...` 带引号、`null` 位置正确、占位符数与参数数一致。
- 反向验证：
  - 摘掉 iBatis 列表分支 → 必须变红；
  - 摘掉 `Types:` 并入 → 「`'00340025002200200006'`」必须变红（裸数字出现）；
  - 摘掉「iBatis 行优先消费」 → 同一语句双条目必须变红。
- agent：`StatementWeaverVendorCoverageTest` 增 Panwei 组（jar 到位才跑字节层，否则显式 skip）；`CaptureBudgetTest` / `CatchAllMatcherTest` 覆盖券 E。

### 6.2 真机复验（用户环境）

1. 控制台有 iBatis 日志时，SQL 列表条目的 restoredSql 无 `[`/`]`，参数数与占位符数一致；
2. 增强通道：状态栏「已挂载 pid / 已收事件 N」N>0，且 agent 条目数与 iBatis 执行次数对得上；
3. 角标显示 `Panwei（磐维） · 库名@host:port`，预览不再降级。

## 七、待验证前提（P）

- **P-1** 磐维驱动的类名与包形（是否 `org.panweidb.jdbc.Pg*`）——需 jar 或 `jcmd <pid> VM.class_hierarchy`；未证实前**不登记**（禁猜类名）。
- **P-2** ~~实际 JDBC URL 子协议~~ **已收口（2026-11-06）**：`jdbc:panweidb:`（nacos-plugins 配置原文 `jdbc:panweidb://localhost:15432/dbname?currentSchema=nacos&reWriteBatchedInserts=true`，URL 参数为 pgjdbc 风格）。券 F 已按 `panwei` / `panweidb` 双键登记（后者为真值，前者为容错）。
- **P-3** iBatis 版本与日志代理实现（`com.ibatis.sqlmap.engine.log.*` 具体类名）——需项目依赖树确认；行为相关的只有格式，已由日志原文钉住。
- **P-4** 增强捕获是否真的零事件（区别于「有事件但没显示」）——看状态栏「已收事件 N」，N=0 才落 §3.2。
- **P-5** 全量扫描耗时（券 E 的取舍依据）——需在 2~4 万类的进程上实测。
- **P-6** 磐维「Oracle 模式」是否由数据库初始化参数决定（同一套驱动可能同时服务 PG 模式）——决定券 F 默认方言是 Oracle 还是 openGauss。

## 八、决策点（D）

- **D-1** 方言映射：推荐 `jdbc:panweidb` → **Oracle**（用户现网 Oracle 模式，SQL 全 Oracle 语法）；备选 openGauss（内核族）或新增第 15 方言「Panwei」（成本最高，需官方文档 + 真机读数，另立项）。
- **D-2** `{conn-N} Preparing Statement:` 是否产条目：推荐**不产**（与 execute 重复）；若用户有「只 prepare 不 execute」的场景再议。
- **D-3** `SqlSource` 是否新增 `IBATIS` 枚举：推荐一期复用 `MYBATIS`（控制台通道，枚举值扩散面 30 处、还牵扯筛选与历史序列化）；二期再拆。

## 九、风险

- 券 A 改动 `parseParams` 附近是高频路径，必须保住 MyBatis 既有语法零回归（现有测试全绿 + 新增反向验证）。
- 券 C 无 jar 不能开工；无 jar 时先落券 D，把「未登记」变成可见事实。
- 券 E 触碰采集预算红线（用户令：不能让被观测应用受影响），任何上限调整都要有实测耗时与新预算证据。
- 方言映射到 Oracle 会影响 EXPLAIN 支持面（磐维 Oracle 模式是否支持 `EXPLAIN PLAN` 未验证），本期只改渲染推断，不扩 EXPLAIN。

## 十、取证清单（给用户，按顺序）

1. SQL 日志页签**底部状态栏**：控制台段「已挂载 N / 已扫描 M 行」+ 增强段「已挂载 pid / 已收事件 N」——先判 A 是「没挂上」还是「挂上零事件」。
2. 应用控制台里 `[SqlVisAgent]` 开头的行（`attach retransform: matched=… wove=…`、`catch-all: attach interface-match: …`）；若没有，把设置里的 agent 日志级别调成 debug 再复现一次。
3. `idea.log` 中 `AgentManager` / `attach` / `pid=` 相关行（IDE 侧挂载状态）。
4. 磐维驱动 jar（或 `jcmd <pid> VM.class_hierarchy | grep panwei` 输出）——券 C 的开工前提。
5. 数据源 URL（可脱敏 host，保留 `jdbc:` 子协议段）——券 F 的键名依据。

> 本券只做分析，不改 `update/update.json`、不推送、不合流；实施与合流按仓库既有纪律另行授权。

## 十一、实施分工（2026-11-06 派工）

用户拍板：全部内容后台并行实施，允许新建分支/工作树防冲突，主控统一合并；只跑定向测试；决策点按本文推荐项执行（D-1 Oracle / D-2 prepare 行不产条目 / D-3 一期复用 MYBATIS）。同日晚用户进一步放开**本地并发限制**（构建位不再受 2 路上限约束），`parallel_tasks.py` 登记与「只跑定向」纪律保留。

| 车道 | 分支 / 工作树 | 券 | 写集（互斥） |
|---|---|---|---|
| A | `t-ibatis-parse` / `.worktrees/ibatis-parse` | A、B | `MyBatisLogParser.kt` 及其测试（控制台解析链） |
| C | `t-catchall-diag` / `.worktrees/catchall-diag` | D、E | agent `CaptureBudget`/`CatchAllMatcher` + `AgentAttachStats`/`CaptureDiagnostics`/`CaptureStatusBar` + i18n |
| B | `t-panwei-driver` / `.worktrees/panwei-driver` | C（取证，jar 到位才登记） | `DriverRegistry` / `WeaveSpecs` / `StatementWeaverVendorCoverageTest` / 驱动取证报告 |
| F | `t-panwei-dialect` / `.worktrees/panwei-dialect` | F | `JdbcUrlDialects` / `UrlSubprotocolDialects` / `PreviewDialectResolver.dialectFromBadge` + 测试 |

收口约定：各车道自提交、不推送、不合流、不建 tag；主控在集成分支上合并并按 §六 重跑权威定向，再由用户决定推送与发行节奏。

## 十二、券 C 取证结论（2026-11-06 11:15）

公网**拿不到**磐维 JDBC 驱动 jar，券 C 阶段 2（登记）阻塞；阶段 1 取证有实质产出（报告：`docs/调研报告/20261106-1115-磐维PanweiJDBC驱动取证-调研报告.md`，分支 `t-panwei-driver` 提交 `20651b6d`）。

已钉死的事实：

1. 驱动类 `org.panweidb.Driver` 四源互证（用户栈 + bboss `DBFactory` / `DBPanwei` + nacos-plugins 配置 + DBeaver/Smartbi 文档）；**V2 文档写 `cn.com.panweidb.Driver`**，存在多代命名分裂。
2. URL 子协议 `jdbc:panweidb:` 证实（P-2 收口，见 §七）。
3. 谱系：官方《PanWeiDB V2.0-S3.0.0_Beta 应用开发指南》§4.1.1 明载基于 **openGauss-connector-jdbc**；bboss `DBPanwei extends DBPostgres`。用户栈的 `org.panweidb.util.PSQLException` 说明是**整包重命名**（pgjdbc 为 `org.postgresql.util.*`、openGauss 2.x/3.x 为 `org.opengauss.util.*`）。
4. 无 jar 时**不登记**的理由：登记范围取决于一个二选一形态判别——jar 内 `org/panweidb/jdbc/*` 有类且 `org/postgresql/jdbc/*` 为 0（整包重命名，需登记语句面 + prepare 面），还是只多一个 Driver 别名（语句面已被 `org.postgresql.*` 覆盖，只需 connect 面）。手册 §4.2 示例仍写 `org.postgresql.Driver`（照抄 openGauss 文档），照文档登记会得出「已覆盖」的错误结论。

**待用户提供（二选一）**：
- 应用 `WEB-INF/lib/` 下的驱动 jar 本体（`panweidb-jdbc-*` / `panwei-client-*` / `panweidb_jdbc_*`），放本机任意路径，测试用 `-Dpanwei.jar=<path>` 读取、不入库；
- 或 `jcmd <pid> VM.class_hierarchy | grep -i -E "panwei|panweidb"` 输出（够登记，字节层校验只能标「未做」）。

**墨天轮渠道实测（2026-11-06 11:38）**：用户给出的两个文件页 `https://www.modb.pro/doc/123758`（`panweidb-jdbc-2.0.0.jar`，约 869K）与 `/doc/123759`（`panweidb-jdbc-postgresql-2.0.0.jar`，约 871K）**匿名访问均 404**，下载接口 `/api/doc/download/<id>` 返回 `401 {"operateMessage":"请登录后操作"}`——需要用户登录后自行下载。两个制品的并存本身是有用线索：基名 jar 大概率是**整包重命名**（需登记语句面 + prepare 面），`-postgresql` 后缀那个大概率是 pgjdbc 兼容别名（只需 connect 面）；仍以 jar 内 `org/panweidb/jdbc/*` 与 `org/postgresql/jdbc/*` 的条目数为准。

在 jar 到位前，磐维用户的采集由**接口兜底 + 券 D 的未登记诊断**承接：兜底命中即可采（无参 `executeQuery` 仍需连接类同时命中），零命中时状态栏会直接报出 `org.panweidb.jdbc.*` 未增强，不再静默。
## 十三、实施记录：券 D + 券 E（2026-11-06，分支 `t-catchall-diag`）

提交：`47a28ef1`（agent 侧 D+E）、`6efd2e48`（IDE 侧 D 展示）、`7510dc79`（名字快筛门控）。

### 13.1 券 D 落地形态

- agent：`CaptureBudget.noteUnregisteredDriver` 有界快照（≤8、去重、只点分类名、首次发现顺序），
  `CatchAllMatcher.evaluate` 在「接口命中 + 不在白名单」处记账；心跳 body 追加 `"unreg":"a.b.C,d.e.F"`
  （恒出现，无则空串；既有数值键一个不动）。
- IDE：`AgentAttachStats.unregisteredImplementations` 宽容解析（值域只收类名字符 + 逗号 + 空格，
  逐项 trim、去空项、去重、截 8）；`attachCoverageNotes` 出「发现未登记 JDBC 实现：…」；
  同一事实挂到 `captureStatusLineText`（状态栏），因为 `captureDiagnosticsText` 在 `entryCount > 0`
  时整条返回 null——「控制台有产出、增强零产出」的现场恰好看不见空态诊断行。
- i18n：`log.diag.attachUnregistered` / `log.statusbar.agent.unregistered`，中英双份。
  `BundleParityGuardTest`（键集合 + 占位符形态）通过；两键不在 `RollbackWarning` 静态核对面内，
  无需登记 `BundlePlaceholderArityTest` 的站点表。
- **与派单文案的偏离（有意）**：派单写「（未增强）」，实现写「（未纳入驱动白名单，增强仅靠接口兜底）」。
  理由：这些类往往正是靠接口兜底织上的，说成「未增强」是假事实；要回答的问题是「哪个驱动没登记」。

### 13.2 券 E 落地形态

- attach 路径补挂 2 轮（`catchAllAttachDelayMs`/`RepeatMs`/`Passes`，默认 5000/20000/2），
  与 premain 共用排程方法；每轮开跑前重查 `attachMatchEnabled()`（kill switch 不得被绕过），
  复用 `PROCESSED` 去重；premain 既有链节奏不变。
- 预算语义：`catchAllScanLimit` 只计**候选类**（跑过接口判定的类）；接口/枚举/数组/基本型/
  bootstrap/JDK 代理/JDK 前缀名不占预算；`limitHit` 保留并追加 `total=`（本轮枚举数）。
- 名字快筛**只在 `loaded.length > limit`（可能截断）时启用**：中小应用单轮扫得完，排序零收益
  却要多付一遍名字扫描。
- 默认上限仍为 5000（未调大）：语义修正已消除「数组顺序决定谁被截断」，调大上限对覆盖的边际
  收益不足以为它付宿主成本。

### 13.3 实测数字（临时探针，跑完已删；20k 类真实 Class 对象数组，5 预热 + 10 轮，3 路并行构建下）

| 场景 | best | avg |
|---|---|---|
| 两轮优先扫描（limit 不生效，20k） | 4.6~6.2 ms | 5.9~6.3 ms |
| 旧语义等价物（单循环，每类都判定，20k） | 3.6~6.2 ms | 5.1~7.2 ms |
| 默认预算档（5000 候选，20k 数组） | 4.1~4.2 ms | 4.95~5.2 ms |
| 仅枚举 + 取类名基线（20k） | 0.11~0.21 ms | — |
| 4000 类（不触发排序） | 新 1.98 / 旧 1.25 ms | — |

结论：单轮扫描仍是毫秒级；attach 全链（首轮 + 2 轮补挂）一次性约 10~20ms，补挂分布在 5s/20s
之后，不构成宿主可感开销。噪声来源：同机 3 路并行构建（读数已在报告里标明区间而非单值）。

### 13.4 定向测试读数

- agent：`CaptureBudgetTest` 10、`CatchAllMatcherTest` 12、`HeartbeatDiagnosticsTest` 3、
  `CatchAllRedLineTest` 5 —— 30 项 0 失败；改门控后连同 `CatchAllTransformTest` /
  `CatchAllStructureTest` 复跑仍全绿。
- 主仓：`log.*` 包 1873 项跑完仅 `AgentSniffDecoupleTest.test时间显示关闭时嗅探仍采集` 失败，
  单跑该类转绿（500ms 时间窗用例在并行构建下超窗，与本次改动无关，已记入风险）；
  本次三组 `AgentAttachStatsTest` 13 / `CaptureDiagnosticsTest` 27 / `CaptureStatusBarTest` 9 全绿，
  `BundleParityGuardTest` 5 全绿。

### 13.5 反向验证读数

| 摘除项 | 变红用例 | 还原后 |
|---|---|---|
| attach 补挂排程 | `attach补挂轮_迟到类在后续轮被补扫并织入`、`attach补挂轮_kill开关触发后不再补扫`（2 failed / 12） | 12 项全绿 |
| 心跳 `unreg` 段 | `心跳body_单行JSON且字段齐全`、`心跳body_未登记实现按类名逗号分隔带出且有界`（2 failed / 3） | 3 项全绿 |
| 诊断行 unreg 分支 | `test未登记JDBC实现必须具名可见`（1 failed / 27） | 27 项全绿 |
| 状态栏 unreg 拼接 | `test未登记JDBC实现挂到增强段且保留既有叙事`（1 failed / 9） | 9 项全绿 |

### 13.6 未完成 / 风险

- 真机复验（磐维应用上的「已收事件 N>0」与未登记类名）未做——需要用户环境；本券只到单测 + 实测耗时。
- 名字快筛是**优先级**而非过滤器（漏命中退回单轮尽力而为），所以「名字里没有任何 JDBC 线索」的
  未知驱动在超 5000 候选的巨型应用里仍可能被预算截断（与修复前同等，只是不再被数组顺序坑）。
- `AgentSniffDecoupleTest` 的 500ms 时间窗用例在并行构建下会抖（既有用例，未改）。

## 十四、实施结果与权威复核（2026-11-06）

四路分支全部合入 `t-ibatis-panwei-integration`：`t-ibatis-parse`（券 A/B）、`t-catchall-diag`（券 D/E）、`t-panwei-dialect`（券 F）、`t-panwei-driver`（券 C 取证报告）。合并时两处冲突（CHANGELOG 与本文）均按「保留双方」解开，本文章节顺序＝§十一 分工 → §十二 券 C 取证 → §十三 D+E 记录 → §十四 结果。未推送、未建 tag。

**权威定向读数**（集成树单次跑，不是各支自报之和）：

| 范围 | 类数 | tests | failures | errors | skipped |
|---|---|---|---|---|---|
| 根项目 `log.*` + `DialectInferenceTest` + `PreviewDialectResolverTest` + `DialectInferFallbackGuardTest` + `BundleParityGuardTest` | 173 | **1907** | 0 | 0 | 0 |
| agent `CaptureBudgetTest` / `CatchAllMatcherTest` / `CatchAllRedLineTest` / `HeartbeatDiagnosticsTest` / `StatementWeaverVendorCoverageTest` | 5 | **44** | 0 | 0 | 0 |

**反向验证汇总**（每项均「摘除必红、还原回绿」，两次读数由各车道报告）：

- 券 A/B：摘 iBatis 分支 → 7 红（7 个 iBatis 用例全红、两条 MyBatis 回归保绿）；摘 Types 并入 → 4 红（含裸数字 `ORGID = 0034…` 与类型缺失两条）。
- 券 A/B 追加守卫（用户追问「值自带方括号会不会被吞」）：把 `parseIbatisParams` 改成 `trim('[', ']')` 全剥 → **恰 1 红**（`[[abc], [], a[b]c]` 用例），其余 10 例保绿。
- 券 D/E：摘 attach 补挂轮 → 2 红；摘心跳 `unreg` 段 → 2 红；摘诊断行分支 → 1 红；摘状态栏拼接 → 1 红。
- 券 F：一次摘掉三处映射（两张子协议表 + 角标别名表）→ 4 红，红点与三条链一一对应。

**未闭环**：

- 券 C 登记：待驱动 jar（§十二；公网无、墨天轮需登录）。
- 真机复验：磐维应用上的「已收事件 N>0 / 未登记类名可见 / 角标显示 / 预览不再降级」需用户环境。
- 按用户「只跑定向」口径未跑：`testShards` 全量、`verifyGate` / `verifyPlugin` / `buildPlugin`、runIde 沙盒。

**顺带发现（非本券改动）**：`docs/LESSONS.md` 只在 `dev` 分支存在，`docs/开发流程-派活与验收清单.md` 在 master/dev 都不存在，而仓库 `AGENTS.md` 第 1、3 条引用了这两份文件——在 master 上开工的代理会读不到。
