package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-CrossChannelDedupe（20261105）跨通道等价键兜底折叠的端到端守卫。
 *
 * 立项现场：同一条 `INSERT INTO sys_operation_log` 在面板出现两行——一行 `8ms [SQL Server] (1 rows)
 * [Opera…]`（agent 通道），一行耗时/库标/行数/位置全 `—`（控制台通道）。两条同秒、同参数，只是
 * `dedupeKey`（参数还原后的 SQL 指纹）不相等：两套还原器在布尔参数、日期族、块注释内 `?`、参数个数
 * 守卫、全限定类型名上口径分叉。日期族口径已由 T-DateLiteralBackfill 收敛（两侧同产 ANSI 类型字面量），
 * 本文件保留其余分叉的兜底守卫。
 *
 * 本文件每个用例都走**真实链路**（不手写 restoredSql 自证——`AgentEventAdapterTest` 的自证用例正是
 * 当年漏掉跨通道失配的原因）：
 *  控制台行 → [processConsoleLine]（真实解析 + 还原）→ `buffer.addEntry(entry.copy(通道=CONSOLE_BRIDGE))`
 *  同一 SQL → [AgentEventAdapter.toEntry]（真实 agent 适配 + 还原）→ `buffer.addEntry`
 *  → 断言同一个 [SqlLogEntryBuffer] 里的最终行数与字段。
 */
class CrossChannelDedupeTest {

    @After
    fun tearDown() {
        SqlCaptureGate.paused.set(false)
        SqlCaptureGate.epActive = false
        LogTimeCorrelator.clear()
    }

    // ---------- 真实链路夹具 ----------

    private fun newCtx(): BridgeLineContext {
        val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000)
        return BridgeLineContext(
            myBatisParser = MyBatisLogParser(
                sqlPrefix = cfg.sqlPrefix,
                paramPrefix = cfg.paramPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
            ),
            hibernateParser = HibernateLogParser(
                sqlPrefix = cfg.hibernateSqlPrefix,
                paramPrefix = cfg.hibernateParamPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
            ),
            jdbcParser = JdbcTemplateLogParser(enabled = cfg.jdbcEnabled, maxParseLength = cfg.maxParseLength),
            configProvider = { cfg },
            hibernateEnabled = cfg.hibernateEnabled,
        )
    }

    /** 控制台行 → 条目（真实解析链；`captureProvenance` 与生产 `emitEntries` 同款打标）。 */
    private fun consoleEntries(vararg lines: String): List<LogSqlEntry> {
        SqlCaptureGate.paused.set(false)
        val ctx = newCtx()
        val out = mutableListOf<LogSqlEntry>()
        lines.forEach { out += processConsoleLine(it, ctx, null, {}) }
        return out.map { it.copy(captureProvenance = CaptureProvenance.CONSOLE_BRIDGE) }
    }

    /** 控制台先到：真实解析 → 入队（返回入队前的原始条目，供断言 dedupeKey 分叉）。 */
    private fun addConsole(buffer: SqlLogEntryBuffer, vararg lines: String): List<LogSqlEntry> {
        val entries = consoleEntries(*lines)
        entries.forEach { buffer.addEntry(it) }
        return entries
    }

    /** agent 侧条目（真实适配链：字段映射 + 参数还原 + 通道打标）。 */
    private fun agentEntry(
        sql: String,
        params: List<Triple<Int, String?, String?>>,
        timestamp: Long = System.currentTimeMillis(),
        durationMs: Long = 7L,
        updateCount: Long? = 1L,
        sourceFile: String? = "src/main/java/com/example/UserService.java",
        sourceLine: Int = 42,
        url: String = "jdbc:sqlserver://localhost:1433;databaseName=demo",
    ): LogSqlEntry = AgentEventAdapter.toEntry(
        AgentEvent(
            sql = sql,
            timestamp = timestamp,
            durationNanos = durationMs * 1_000_000L,
            updateCount = updateCount,
            errorText = null,
            sourceFile = sourceFile,
            sourceLine = sourceLine,
            methodKey = "UserService#save",
            requestTag = null,
            params = params,
            threadName = "main",
            url = url,
        ),
    )

    /** 控制台先到、agent 后到（用户报障的顺序：控制台条目先落列表，agent 事件随后到）。 */
    private fun runConsoleThenAgent(
        sql: String,
        consoleParams: String,
        agentParams: List<Triple<Int, String?, String?>>,
    ): Pair<SqlLogEntryBuffer, List<LogSqlEntry>> {
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        val console = addConsole(
            buffer,
            "==>  Preparing: $sql",
            "==> Parameters: $consoleParams",
        )
        buffer.addEntry(agentEntry(sql, agentParams))
        return buffer to console
    }

    private fun assertFoldedToAgentFacts(buffer: SqlLogEntryBuffer, label: String) {
        val rows = buffer.snapshot()
        assertEquals("$label：同一次执行只留一行", 1, rows.size)
        val row = rows.single()
        assertEquals("$label：耗时取 agent 侧", 7L, row.executionTimeMs)
        assertEquals("$label：位置取 agent 侧", "src/main/java/com/example/UserService.java", row.sourceFile)
        assertEquals("$label：行数取 agent 侧", 1L, row.updateCount)
        assertTrue("$label：库标取 agent 侧", row.databaseName != null)
        assertEquals("$label：通道归并 MERGED", CaptureProvenance.MERGED, row.captureProvenance)
        assertEquals("$label：跨通道折叠不计 ⨉N 徽章（不是重复丢弃）", 0, buffer.dedupeCountsSnapshot().size)
    }

    // ---------- 1~5：两套还原器口径分叉的五类形态 ----------

    @Test
    fun `布尔参数_跨通道折叠为一行并取agent字段`() {
        val sql = "INSERT INTO t (flag) VALUES (?)"
        // 控制台还原 true（Boolean 在 PLAIN_TYPES 直出）；agent 还原 'true'（不在数值表 → 加引号）
        val (buffer, console) = runConsoleThenAgent(
            sql = sql,
            consoleParams = "true(Boolean)",
            agentParams = listOf(Triple(1, "true", "Boolean")),
        )
        assertNotEquals("前提：两侧 dedupeKey 确实分叉（否则本用例不测兜底）",
            console.single().dedupeKey, agentEntry(sql, listOf(Triple(1, "true", "Boolean"))).dedupeKey)
        assertFoldedToAgentFacts(buffer, "布尔参数")
    }

    @Test
    fun `日期参数_跨通道折叠为一行并取agent字段`() {
        val sql = "INSERT INTO sys_operation_log (create_time) VALUES (?)"
        // T-DateLiteralBackfill：控制台产 ANSI 字面量；agent 侧本夹具是 SQL Server（门禁回落引号），
        // 分叉由兜底键折叠——Oracle/PG 等方言的同口径断言见下一用例
        val (buffer, _) = runConsoleThenAgent(
            sql = sql,
            consoleParams = "2026-11-05 11:37:20.0(Timestamp)",
            agentParams = listOf(Triple(1, "2026-11-05 11:37:20.0", "Timestamp")),
        )
        assertFoldedToAgentFacts(buffer, "日期族")
    }

    @Test
    fun `LocalDateTime参数_SQLServer门禁回落引号_跨通道折叠为一行`() {
        val sql = "INSERT INTO sys_operation_log (create_time) VALUES (?)"
        // 控制台 normalizeTs 把 ISO 的 'T' 归一为空格并产出 ANSI 字面量；agent 侧 SQL Server 门禁回落引号
        val (buffer, _) = runConsoleThenAgent(
            sql = sql,
            consoleParams = "2026-11-05T11:37:20(LocalDateTime)",
            agentParams = listOf(Triple(1, "2026-11-05T11:37:20", "LocalDateTime")),
        )
        assertFoldedToAgentFacts(buffer, "LocalDateTime")
    }

    @Test
    fun `Oracle方言_日期族两侧同键`() {
        // T-DateLiteralBackfill：Oracle 不走门禁，agent 还原与 LogSqlRestorer 同产 ANSI 字面量，
        // 两侧 dedupeKey 直接相等（不再依赖兜底键）
        val sql = "INSERT INTO sys_operation_log (create_time) VALUES (?)"
        val console = consoleEntries(
            "==>  Preparing: $sql",
            "==> Parameters: 2026-11-05 11:37:20.0(Timestamp)",
        ).single()
        val agent = agentEntry(
            sql,
            listOf(Triple(1, "2026-11-05 11:37:20.0", "Timestamp")),
            url = "jdbc:oracle:thin:@localhost:1521/FREEPDB1",
        )
        assertTrue("两侧都是 ANSI 字面量", agent.restoredSql.contains("TIMESTAMP '2026-11-05 11:37:20.0'"))
        assertEquals("两侧 dedupeKey 同键", console.dedupeKey, agent.dedupeKey)
    }

    /**
     * **贴近现场的 12 参数 INSERT**（复现代理实测形态，2026-11-05）：`sys_operation_log` 一次插入，
     * 两侧 rawSql 完全一致，还原后**只有第 10、11 个参数不同**——`setBoolean` 控制台 `true` / agent
     * `'true'`，`setTimestamp` 控制台 `TIMESTAMP '…'` / agent `'…'`（本夹具 URL 是 SQL Server，
     * T-DateLiteralBackfill 的方言门禁让 agent 回落引号；控制台侧无门禁是 P2 既有缺口）。
     * 也就是说「表里有一条 bit 或 datetime 列」就必然分叉，与 @Async 无关。
     *
     * 本用例先把两处分叉钉成断言（防日后还原器口径收敛后此用例变成空转），再断言兜底折成一行。
     */
    @Test
    fun `现场形态_12参数INSERT_布尔与时间戳同时分叉_折叠为一行`() {
        val sql = "INSERT INTO sys_operation_log (user_name, title, business_type, method, " +
            "request_method, operator_type, dept_name, oper_url, oper_ip, status, oper_time, json_result) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val params = listOf(
            Triple(1, "admin", "String"),
            Triple(2, "用户管理", "String"),
            Triple(3, "1", "Integer"),
            Triple(4, "UserService.save()", "String"),
            Triple(5, "POST", "String"),
            Triple(6, "1", "Integer"),
            Triple(7, "研发部", "String"),
            Triple(8, "/user/save", "String"),
            Triple(9, "127.0.0.1", "String"),
            Triple(10, "true", "Boolean"),
            Triple(11, "2026-11-05 11:37:20.0", "Timestamp"),
            Triple(12, "{\"success\":true}", "String"),
        )
        val consoleLine = params.joinToString(", ") { (_, v, t) -> "$v($t)" }
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        val console = consoleEntries("==>  Preparing: $sql", "==> Parameters: $consoleLine").single()
        val agent = agentEntry(sql, params)
        // 前提：rawSql 一致（同一次执行），分叉只发生在参数还原
        assertEquals("控制台解析出 12 个参数", 12, console.params.size)
        assertEquals("两侧 rawSql 必须一致", console.rawSql, agent.rawSql)
        assertTrue("控制台布尔直出", console.restoredSql.contains("true, TIMESTAMP"))
        assertTrue("控制台日期族 ANSI 字面量", console.restoredSql.contains("TIMESTAMP '2026-11-05 11:37:20.0'"))
        assertTrue("agent 布尔加引号（SQL Server 门禁：日期族回落引号）", agent.restoredSql.contains("'true', '2026-11-05 11:37:20.0'"))
        assertNotEquals("两侧 dedupeKey 分叉", console.dedupeKey, agent.dedupeKey)

        buffer.addEntry(console)
        assertFalse("兜底折叠：不新增行", buffer.addEntry(agent))
        assertFoldedToAgentFacts(buffer, "现场 12 参数 INSERT")
    }

    @Test
    fun `块注释内含问号_跨通道折叠为一行并取agent字段`() {
        val sql = "SELECT * FROM t /* hint ? */ WHERE id = ?"
        // 控制台 placeholderPositions 跳过注释 → 正常还原；agent countPlaceholders 把注释里的 ? 算进去
        // → 数量守卫触发 → 整句不还原（T-AgentLogTime 已钉住的形态）
        val (buffer, _) = runConsoleThenAgent(
            sql = sql,
            consoleParams = "42(Long)",
            agentParams = listOf(Triple(1, "42", "Long")),
        )
        assertFoldedToAgentFacts(buffer, "注释内 ?")
    }

    /**
     * 二进制参数：控制台日志打的是对象地址 `[B@1a2b3c(byte[])`，agent 侧 `ParamCapture.renderValue`
     * 检测到默认 toString 后降级为 `<byte[]>`——两侧值文本天然不可比，签名对不上、兜底不折，
     * 症状与用户报障完全一致（一条带耗时位置、一条全空），而且因为签名不同，`跨通道同源未折叠`
     * 哨兵也不会触发（它只在 crossKey 相同时才报）。
     *
     * 修复：签名函数把二进制形态归一为同一占位——只影响匹配，不影响展示（列表仍显示各自原文）。
     */
    @Test
    fun `二进制参数_值文本不可比_归一后折叠为一行`() {
        val sql = "INSERT INTO t (avatar) VALUES (?)"
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        addConsole(buffer, "==>  Preparing: $sql", "==> Parameters: [B@1a2b3c(byte[])")
        buffer.addEntry(agentEntry(sql, listOf(Triple(1, "<byte[]>", "[B"))))
        assertFoldedToAgentFacts(buffer, "二进制参数")
    }

    /** Blob/Clob 同属二进制族：控制台侧多为 `类型@hash(Blob)` 形态，agent 侧降级为 `<Blob>`。 */
    @Test
    fun `Blob参数_值文本不可比_归一后折叠为一行`() {
        val sql = "INSERT INTO t (attach) VALUES (?)"
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        addConsole(buffer, "==>  Preparing: $sql", "==> Parameters: com.mysql.cj.jdbc.Blob@7f3a(Blob)")
        buffer.addEntry(agentEntry(sql, listOf(Triple(1, "<Blob>", "Blob"))))
        assertFoldedToAgentFacts(buffer, "Blob 参数")
    }

    /** 归一不能误伤：普通字符串参数恰好长得像十六进制也不该被当成二进制。 */
    @Test
    fun `普通字符串参数_不被二进制归一误伤`() {
        val sql = "SELECT * FROM t WHERE code = ?"
        val (buffer, _) = runConsoleThenAgent(
            sql = sql,
            consoleParams = "deadbeef(String)",
            agentParams = listOf(Triple(1, "deadbeef", "String")),
        )
        // 两侧还原后同键，走的是既有同键去重分支（会计 ⨉N 徽章），故不用 assertFoldedToAgentFacts
        val rows = buffer.snapshot()
        assertEquals("同键折叠为一行", 1, rows.size)
        assertEquals("值未被二进制归一改写", "SELECT * FROM t WHERE code = 'deadbeef'", rows.single().restoredSql)
    }

    // ---------- T-DedupeDecouple：跨通道折叠与「SQL 去重」开关解耦 ----------

    /**
     * T-DedupeDecouple（20261105 真机报障）：用户取消「SQL 去重」后，跨通道兜底必须仍然生效。
     *
     * 此前 `crossKey` 的构造与 `crossChannelIndex` 的维护都挂在 `dedupeEnabled` 上——去重一关，
     * 同一次执行立刻退化成两条（控制台一条无耗时/位置、agent 一条完整），且因为索引压根没维护，
     * 连「跨通道同源未折叠」诊断行都不会留（真机只能靠截图反推）。
     *
     * 语义依据：去重开关管的是「重复执行要不要折叠」，本兜底管的是「同一次执行的两份观测要不要合并」
     * ——后者不是用户可选的偏好，留两条只会让用户以为发生了两次。
     */
    @Test
    fun `去重关闭时_跨通道兜底仍折叠`() {
        val sql = "SELECT * FROM t WHERE flag = ?"
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        buffer.disableDedupe()
        addConsole(buffer, "==>  Preparing: $sql", "==> Parameters: true(Boolean)")
        buffer.addEntry(agentEntry(sql, listOf(Triple(1, "true", "Boolean"))))
        assertFoldedToAgentFacts(buffer, "去重关闭 + 跨通道折叠")
    }

    /** 去重关闭时，「两次不同执行」不得被跨通道兜底误折——兜底只合并同一次执行的两份观测。 */
    @Test
    fun `去重关闭时_两次不同执行不误折`() {
        val sql = "SELECT * FROM t WHERE id = ?"
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        buffer.disableDedupe()
        // 第一次执行：控制台 + agent 各一份（同参数 → 折叠成一条）
        addConsole(buffer, "==>  Preparing: $sql", "==> Parameters: 1(Long)")
        buffer.addEntry(agentEntry(sql, listOf(Triple(1, "1", "Long"))))
        // 第二次执行：参数不同 → 签名不同 → 不折
        addConsole(buffer, "==>  Preparing: $sql", "==> Parameters: 2(Long)")
        buffer.addEntry(agentEntry(sql, listOf(Triple(1, "2", "Long"))))
        assertEquals("两次不同执行各留一条（共 2 条）", 2, buffer.snapshot().size)
    }

    /** 去重关闭→再开启后，兜底不得失效（索引生命周期与开关无关）。 */
    @Test
    fun `去重开关来回切换_兜底仍折叠`() {
        val sql = "SELECT * FROM t WHERE flag = ?"
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        buffer.disableDedupe()
        buffer.dedupeEnabled = true
        addConsole(buffer, "==>  Preparing: $sql", "==> Parameters: false(Boolean)")
        buffer.addEntry(agentEntry(sql, listOf(Triple(1, "false", "Boolean"))))
        assertFoldedToAgentFacts(buffer, "去重开关切换后")
    }

    /**
     * **安全边界**：agent 侧参数确实少于控制台侧时，两侧 `logTimeParamSignature` 不等
     * （`1\u00012` vs `1`）、crossKey 不同，兜底**不折叠**。
     *
     * 这是兜底应有的保守性——签名不等就无法证明是同一次执行的两份观测，宁可留两行也不误配。
     * 真正的修复点在**捕获面**：`ParamCapture` 白名单补齐 `setNClob`/`setRowId`/`setSQLXML`/`setRef`、
     * 四参 `setObject` 上限由 3 放宽到 4（T-CrossChannelResidual），现场不再产出「少捕」形态；
     * 守卫见 agent 子项目 `ParamCaptureTest`。
     */
    @Test
    fun `参数个数不等_兜底不误折叠_安全边界`() {
        val sql = "SELECT * FROM t WHERE a = ? AND b = ?"
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        addConsole(buffer, "==>  Preparing: $sql", "==> Parameters: 1(Long), 2(Long)")
        buffer.addEntry(agentEntry(sql, listOf(Triple(1, "1", "Long"))))
        val rows = buffer.snapshot()
        assertEquals("签名不同 → crossKey 不同 → 不折叠（保守，避免误配）", 2, rows.size)
        assertEquals("控制台行", CaptureProvenance.CONSOLE_BRIDGE, rows[0].captureProvenance)
        assertEquals("agent 行", CaptureProvenance.AGENT, rows[1].captureProvenance)
    }

    /**
     * 全限定类型名（`java.lang.Long`）：控制台 `PLAIN_TYPES` 含点号形态（原样直出 `42`），agent 的
     * `NUMERIC_TYPE_REGEX` 是全串匹配、认不出点号 → `'42'`。两侧 dedupeKey 因此分叉，参数值签名相同
     * （都是 `42`）→ 兜底折叠生效。
     *
     * 注：**今天的 agent 侧 `ParamCapture.simpleTypeName` 会剥掉包名**，所以「agent 发出点号类型」是
     * 协议层允许、当前版本不会产出的形态（计划 §六 表格第 5 行的原始形态见下一个用例：控制台解析器
     * 根本不吃点号，因此那一侧不可达）。本用例守的是兜底对「类型标注口径分叉」的容错本身。
     */
    @Test
    fun `全限定类型名_agent侧类型带包名_跨通道折叠为一行`() {
        val sql = "INSERT INTO t (amount) VALUES (?)"
        val (buffer, _) = runConsoleThenAgent(
            sql = sql,
            consoleParams = "42(Long)",
            agentParams = listOf(Triple(1, "42", "java.lang.Long")),
        )
        assertFoldedToAgentFacts(buffer, "全限定类型名（agent 侧）")
    }

    /**
     * 全限定类型名（`java.lang.Long`）走**控制台解析器**：T-CrossChannelResidual 放宽
     * `parseParams` 的类型分组到 `[A-Za-z_$][\w$.]*` 后，`42(java.lang.Long)` 解析出
     * `type=java.lang.Long` / `value=42`，`LogSqlRestorer.PLAIN_TYPES` 里本就有 `java.lang.Long`
     * 条目（此前解析器从不产出该形态，那段映射等于死代码），于是控制台还原 `42`；
     * agent 侧 `NUMERIC_TYPE_REGEX` 认不出点号 → `'42'`。两侧 dedupeKey 分叉，但**参数值签名相同**
     * （都是 `42`）→ 跨通道兜底折叠生效。
     */
    @Test
    fun `全限定类型名_控制台解析器识别点号类型_跨通道折叠为一行`() {
        val sql = "INSERT INTO t (amount) VALUES (?)"
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        val console = addConsole(buffer, "==>  Preparing: $sql", "==> Parameters: 42(java.lang.Long)")
        val consoleParam = console.single().params.single()
        assertEquals("类型标注完整保留（含包名）", "java.lang.Long", consoleParam.type)
        assertEquals("参数值不再吞掉类型标注", "42", consoleParam.value)
        buffer.addEntry(agentEntry(sql, listOf(Triple(1, "42", "java.lang.Long"))))
        assertFoldedToAgentFacts(buffer, "全限定类型名（控制台解析器）")
    }

    // ---------- 6~7：两条到达顺序 ----------

    @Test
    fun `控制台先到_agent后到_折叠为一行且保留首条行号与捕获时间`() {
        // 参数含布尔位（控制台 true / agent 'true'）→ dedupeKey 分叉，必须由跨通道兜底折
        val sql = "INSERT INTO sys_operation_log (user_name, status) VALUES (?, ?)"
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        val console = addConsole(
            buffer,
            "==>  Preparing: $sql",
            "==> Parameters: admin(String), true(Boolean)",
        )
        val consoleRow = buffer.snapshot().single()
        assertEquals("控制台条目先入队占行号 #1", 1L, consoleRow.sequenceNumber)
        assertFalse("跨通道折叠不算新捕获（onEntryAccepted 语义）",
            buffer.addEntry(agentEntry(sql, listOf(Triple(1, "admin", "String"), Triple(2, "true", "Boolean")))))
        val merged = buffer.snapshot().single()
        assertEquals("行号稳定（列表不跳行）", 1L, merged.sequenceNumber)
        assertEquals("捕获时间保留首条", consoleRow.timestamp, merged.timestamp)
        assertEquals(CaptureProvenance.MERGED, merged.captureProvenance)
        assertEquals(7L, merged.executionTimeMs)
        assertEquals("src/main/java/com/example/UserService.java", merged.sourceFile)
    }

    @Test
    fun `agent先到_控制台后到_字段不被降级`() {
        val sql = "INSERT INTO t (flag) VALUES (?)"
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        assertTrue(buffer.addEntry(agentEntry(sql, listOf(Triple(1, "true", "Boolean")))))
        assertFalse("同一次执行的控制台副本折叠（不新开行）",
            buffer.addEntry(consoleEntries("==>  Preparing: $sql", "==> Parameters: true(Boolean)").single()))
        val row = buffer.snapshot().single()
        assertEquals("耗时保留 agent 事实", 7L, row.executionTimeMs)
        assertEquals("真实源码不被空值覆盖", "src/main/java/com/example/UserService.java", row.sourceFile)
        assertEquals("行数保留 agent 事实", 1L, row.updateCount)
        assertNotNull("库标保留 agent 事实", row.databaseName)
        assertEquals(CaptureProvenance.MERGED, row.captureProvenance)
    }

    // ---------- 8~9：不误折叠 ----------

    @Test
    fun `两条真正不同的SQL_不误折叠`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        addConsole(buffer, "==>  Preparing: SELECT * FROM t WHERE id = ?", "==> Parameters: 1(Long)")
        assertTrue("rawSql 不同 → crossKey 不同 → 各占一行",
            buffer.addEntry(agentEntry("SELECT * FROM t WHERE code = ?", listOf(Triple(1, "1", "Long")))))
        assertEquals(2, buffer.snapshot().size)
    }

    @Test
    fun `同通道同SQL_既有去重语义不变`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        val sql = "INSERT INTO t (flag) VALUES (?)"
        assertTrue(addConsole(buffer, "==>  Preparing: $sql", "==> Parameters: true(Boolean)").isNotEmpty())
        assertFalse("同通道同 dedupeKey：既有窗口去重照旧丢弃",
            buffer.addEntry(consoleEntries("==>  Preparing: $sql", "==> Parameters: true(Boolean)").single()))
        assertEquals(1, buffer.snapshot().size)
        assertEquals("同键重复照常计 ⨉N（与跨通道折叠区分）", 1, buffer.dedupeCount(buffer.snapshot().single().restoredSql))
    }
}
