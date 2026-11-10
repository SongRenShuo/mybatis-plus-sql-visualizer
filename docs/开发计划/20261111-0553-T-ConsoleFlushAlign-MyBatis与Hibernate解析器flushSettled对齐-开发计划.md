# T-ConsoleFlushAlign：MyBatis 与 Hibernate 解析器 flushSettled 对齐 —— 开发计划

- 日期：2026-11-11 05:53
- 分支/工作树：`t-consoleflush-align` @ `.worktrees/t-consoleflush-align`（基点 master `d83522d7`，任务书里的 ≈549db837 已被并行会话的 docs 订正顶进一格）
- 上游：用户 2026-11-11 独立立项；对齐 T-ConsolePendingFlush（O-70，commit `efe2c73f`）只为 JdbcTemplateLogParser（后续 R10 又给了 ShardingSphereLogParser）落地的静默冲刷

## 一、现状证据（读码核实，file:line 以 master `d83522d7` 为准）

### 1.1 JdbcTemplate 冲刷触发链（对齐目标）

- **冲刷动作本体**：`JdbcTemplateLogParser.flushSettled(nowMs)`（`src/main/kotlin/cn/song/mybatisplus/visualizer/log/JdbcTemplateLogParser.kt:235`）——遍历 `router.settledBuckets(settleMs, nowMs)`，对每个静默 ≥ 阈值（`DEFAULT_SETTLE_MS=1000L`，:450）的桶调 `closeBucketPending`，产出进 `entries`，由下一次 `takeEntries()` 交出。
- **静默判据**：`LogThreadRouter.settledBuckets`（`LogThreadRouter.kt:71`）——路由器从 T-ConsolePendingFlush 起就为**每个桶**记 `lastActivity`（IdentityHashMap，:52；`touch()` 在路由三出口刷新，`bucketFor`/`bucketForToken` 共用），桶淘汰/清空同步清理（:116、:131）。**三解析器共享同一份实现**——MyBatis/Hibernate 的桶此刻已经在被记活动时间，只是没有解析器侧的冲刷消费它。
- **触发点 ①（逐行）**：`processConsoleLine` 喂四解析器之后（`SqlLogConsoleBridge.kt:1235-1236`）：`ctx.jdbcParser.flushSettled()` + `if (ctx.shardingEnabled) ctx.shardingParser.flushSettled()`。放在喂行之后是有意的：本行刚 `touch()` 它自己的桶，该桶不会出现在冲刷名单里（同线程参数行不被截断）；被冲的只有别的线程静默 ≥1s 的桶。
- **触发点 ②（EDT 轮询）**：`SqlLogConsoleBridge.flushSettledEntries`（:473-526），由 `scanAll` 每拍调用（轮询定时器 :140）——应用安静后一行都不再打时唯一出路。目前只冲 jdbc（:483）与 sharding（:499），逐条走 `SqlCaptureGate.bridgeSuppressed()` 复查 + `CaptureProvenance.CONSOLE_BRIDGE` 打标入队。
- **互斥**：逐行冲刷在控制台出线程、轮询冲刷在 EDT，两侧以 `ctx` 为锁（:481-482 与 `handleConsoleLineBody` :419）。
- **ShardingSphere 先例**：`ShardingSphereLogParser.flushSettled`（`ShardingSphereLogParser.kt:131`）逐字同形，R10 落地——本任务的第三份参照。

### 1.2 两解析器未闭合桶的到达路径核实

结论先行：**前提成立**。两解析器的桶状态经 `LogThreadRouter` 分桶（MyBatis `MyBatisLogParser.kt:85-91`，Hibernate `HibernateLogParser.kt:70-75`），桶的 `lastActivity` 已由路由器统一记录（1.1），settle 信号到得了；缺的只是解析器侧的冲刷函数与两处接线。实时链路（控制台桥）喂行后**从不调 `closePending()`**（只有「日志→SQL」粘贴面板与 MCP render_logs 在输入末尾调），桶内未闭合状态在应用安静后确实无限期滞留。

**MyBatisLogParser**（桶态 `MyBatisLogParser.kt:70-83`：pendingSql/pendingParams/prefixMatched/paramPrefixMatched/continuing）——条目闭合点盘点：

| 场景 | 闭合时机 | 会滞留吗 |
|---|---|---|
| 参数化语句 | `Parameters:` 行到达即闭合（分支 4，:194-207） | 否（正常形态） |
| 无参语句（MyBatis 不打 Parameters 行） | 下一行只终止续行态（分支 6，:225-232），pendingSql 保留；**同线程下一条语句行**（分支 1 `closePending` :150）才闭合 | 是——若这是该线程最后一条语句，实时链路永无闭合点 |
| 无参语句（空 `Parameters:` 前缀行形态） | 等到的下一行是日志行 → 闭合（分支 3 内 T-LogPC-03，:177-192） | 下一行 1ms 量级到达，不滞留 |
| 日志截断尾（`Preparing:` 是最后一条行） | 无 | 是——等一个不存在的 `Parameters:` |

→ 实际待冲刷面 = **「Preparing 已见 / Parameters 未达」窗口（截断尾）+ 无参语句且该线程再无后继行**。与任务书预判一致。

**HibernateLogParser**（桶态 `HibernateLogParser.kt:48-64`：collecting/inComment/multilineBuffer/pendingParams/pendingShadow）——闭合点盘点：

| 场景 | 闭合时机 | 会滞留吗 |
|---|---|---|
| logger 形态 SQL 行（TRACE 参数行开着） | 参数行后遇到非 SQL 行闭合（feedCollecting 分支 4，:211-215） | 否 |
| logger 形态 SQL 行（TRACE 关，常见） | 只等**下一条语句行**（feedCollecting 分支 1/1b/2，:172-201）或注释块重启 | 是——最后一条语句滞留 |
| stdout 双副本零参影子（pendingShadow，:63） | `takeEntries` 对非收集态桶立即冲（:283-288） | 否（每行都在取，不滞留） |
| 日志截断尾（SQL 行是最后一行） | 无 | 是 |

→ 实际待冲刷面 = **「SQL 行已见 / 参数与后继行未达」窗口（截断尾 + TRACE 关闭的末条语句）**。

### 1.3 与 JdbcTemplate 的语义差异（冲刷语义按差异设计，不硬套）

1. **滞留面小一个量级**：JdbcTemplate 的语句行**经常**没有同线程后继行（TRACE 参数行默认不开 + 按请求新建线程），冲刷是常态产出路径；MyBatis/Hibernate 的正常参数化语句在参数行到达时已即时闭合，冲刷只兜「截断尾 + 无参/TRACE 关的末条语句」。语义上仍是同款兜底，产出频率更低。
2. **产出形态**：被冲的条目参数可能缺失——MyBatis 产出 `?` 未还原形态（与 JdbcTemplate 冲刷的「宁交未还原，不整段丢弃」同一口径）；Hibernate 产出零参条目。**不伪造参数**，与两解析器既有「参数行缺位」语义一致。
3. **MyBatis 特有：iBatis 挂起参数组**（`ibatisPending`，:110，键=pstm id，等 `Types:` 并入）**不归桶管**，`LogThreadRouter` 的 lastActivity 覆盖不到它。若在 flushSettled 里无条件 `flushIbatis()`，逐行冲刷会把「Parameters → Types（间隔 1ms）」撕开——类型丢失、纯数字串裸出成数值字面量，直接破坏 T-PanweiInsertCapture 券 A/B 的核心约定。**设计**：给 `IbatisPending` 自带 `lastTouchMs`（Executing/Parameters 到达时刷新），flushSettled 只冲**自身静默 ≥ 阈值**的挂起组——与桶冲刷同一判据、同一阈值，Types 在 1ms 量级内到达时永不被冲。
4. **Hibernate 特有：零参影子**（T-HibDualCapture）——flushSettled 调 `closePending(bucket)` 时零参条目入 `pendingShadow` 且返回 null（:264-274），与既有 `closePending()` 返回语义逐字一致；影子随下一次 `takeEntries()`（逐行路径与轮询路径都紧跟冲刷调用）在非收集态桶上立即交出，不额外设冲刷点。
5. **等待态不复位**：MyBatis 的 prefixMatched/paramPrefixMatched（前缀独占一行极端形态的等待）在 closePending 里本就不动（:404-413 pendingSql==null 即早退），flushSettled 沿用——不改变 closePending 既有行为，等待态桶由既有 evictIdle 淘汰兜底。

### 1.4 待验证前提（实施者先证伪再动手）

- **前提 P1**：MyBatis/Hibernate 桶在实时链路确实滞留——已在 1.2 核实（逐行链路无 closePending 调用点；`grep -n "closePending" SqlLogConsoleBridge.kt` 全量核对，仅粘贴面板/MCP 侧调用）。测试先红后绿即双向实证。
- **前提 P2**：`settledBuckets` 对 MyBatis/Hibernate 桶直接可用（router 已记活动时间）——已核实（1.1），实现零路由器改动。
- **前提 P3**：冲刷阈值内参数行仍可能到达（误伤面）——与 JdbcTemplate 同款接受：Preparing→Parameters / SQL 行→绑定行间隔 1ms 量级，1s 静默足够判别。

## 二、改动清单

1. **MyBatisLogParser**（`src/main/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisLogParser.kt`）
   - 构造参数追加 `private val settleMs: Long = DEFAULT_SETTLE_MS`、`private val clock: () -> Long = System::currentTimeMillis`（尾参追加，既有命名参数构造零改动）；`DEFAULT_SETTLE_MS = 1000L` 入 companion（对齐 JdbcTemplate）。
   - router 传入 `clock = clock`（与 [clock] 同源，同 ShardingSphere 先例）。
   - 新增 `flushSettled(nowMs): Int`：settled 桶逐一 `closePending(bucket)`；随后冲自身静默 ≥ 阈值的 iBatis 挂起组（`IbatisPending.lastTouchMs` 新字段，onIbatisExecuting/onIbatisParameters 刷新）。
   - 类注释「兜底闭合点」清单补 flushSettled。
2. **HibernateLogParser**（`src/main/kotlin/cn/song/mybatisplus/visualizer/log/HibernateLogParser.kt`）
   - 同款构造参数 + router clock 同源 + `flushSettled(nowMs): Int`（`closePending(bucket)` 逐字镜像，零参条目自然走影子槽）。
3. **SqlLogConsoleBridge**（`src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogConsoleBridge.kt`）
   - `processConsoleLine` 步骤 2.5：追加 `ctx.myBatisParser.flushSettled()` 与 `if (ctx.hibernateEnabled) ctx.hibernateParser.flushSettled()`（喂行后、takeEntries 前，与 jdbc/sharding 同批）。
   - `flushSettledEntries`：追加 MyBatis（无开关）与 Hibernate（受 `ctx.hibernateEnabled` 门控）的 flush + takeEntries + 闸门复查 + 打标入队（照抄 sharding 段形态）；INFO 留痕文案微调为四解析器口径。
4. **测试**：新增 `src/test/kotlin/cn/song/mybatisplus/visualizer/log/MyBatisHibernateSettleFlushTest.kt`（构造手法照抄 `ConsoleSettleFlushTest`：注入时钟的纯逻辑/逐行用例 + 桥桩 scanAll 轮询用例），两解析器各覆盖：安静期 settle 产出、阈值判别力（未满阈值不提前产出）、参数行阈值内到达不被截断、冲刷后真实参数行到达不重复闭合、桥轮询产出且不重复、MyBatis 另加 iBatis 挂起组 Types 缺行静默产出。既有测试零改动。
5. **统一抽取待办**：flushSettled 四解析器同形（JdbcTemplate/ShardingSphere/MyBatis/Hibernate 各一份本地实现）——影响面评估后登记「四解析器 settle 冲刷统一抽取」为后续券，本次不抽（最小风险为先）。

## 三、测试读数（实施后回填）

- 反向验证（先红）：禁用两解析器 flushSettled（方法体改为直接 return 0）→ 新用例中 settle 产出类必红，读数贴原文。
- 恢复后转绿：定向 `./gradlew test --tests "cn.song.mybatisplus.visualizer.log.MyBatisHibernateSettleFlushTest"` + 既有相关类（MyBatisLogParserTest / HibernateLogParserTest / LogThreadRouterTest / ConsoleSettleFlushTest / IbatisLogParseTest / IbatisSqlSourceTest / DualParserIntegrationTest / ShardingSphereLogParserTest）。

| 阶段 | 命令 | 读数 |
|---|---|---|
| 先红 | 待回填 | 待回填 |
| 后绿 | 待回填 | 待回填 |

## 四、实施记录（回填位）

- 待回填。
