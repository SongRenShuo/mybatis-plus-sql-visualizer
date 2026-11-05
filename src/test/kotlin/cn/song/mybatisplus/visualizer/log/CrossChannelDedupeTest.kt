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
 * 守卫、全限定类型名上口径分叉。
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
            url = "jdbc:sqlserver://localhost:1433;databaseName=demo",
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
        // 控制台还原 ANSI 字面量 TIMESTAMP '…'；agent 无日期族规则 → '…' 引号串
        val (buffer, _) = runConsoleThenAgent(
            sql = sql,
            consoleParams = "2026-11-05 11:37:20.0(Timestamp)",
            agentParams = listOf(Triple(1, "2026-11-05 11:37:20.0", "Timestamp")),
        )
        assertFoldedToAgentFacts(buffer, "日期族")
    }

    @Test
    fun `LocalDateTime参数_ISO的T未归一_跨通道折叠为一行`() {
        val sql = "INSERT INTO sys_operation_log (create_time) VALUES (?)"
        // 控制台 normalizeTs 把 ISO 的 'T' 归一为空格并产出 ANSI 字面量；agent 只加引号，'T' 原样保留
        val (buffer, _) = runConsoleThenAgent(
            sql = sql,
            consoleParams = "2026-11-05T11:37:20(LocalDateTime)",
            agentParams = listOf(Triple(1, "2026-11-05T11:37:20", "LocalDateTime")),
        )
        assertFoldedToAgentFacts(buffer, "LocalDateTime")
    }

    /**
     * **贴近现场的 12 参数 INSERT**（复现代理实测形态，2026-11-05）：`sys_operation_log` 一次插入，
     * 两侧 rawSql 完全一致，还原后**只有第 10、11 个参数不同**——`setBoolean` 控制台 `true` / agent
     * `'true'`，`setTimestamp` 控制台 `TIMESTAMP '…'` / agent `'…'`。也就是说「表里有一条 bit 或
     * datetime 列」就必然分叉，与 @Async、SQL Server 方言无关。
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
        assertTrue("agent 布尔加引号", agent.restoredSql.contains("'true', '2026-11-05 11:37:20.0'"))
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
     * **已知残余（计划前提被证伪）**：agent 少捕一个参数时 `logTimeParamSignature` 两侧不等
     * （`1\u00012` vs `1`），crossKey 因此不同，兜底折叠不触发——本形态需要 §4.2（还原器口径收敛 /
     * 参数序号连续性校验）才能解决，本期如实钉成断言，不假装已修。
     */
    @Test
    fun `参数个数不等_签名不一致_兜底不生效_记录已知残余`() {
        val sql = "SELECT * FROM t WHERE a = ? AND b = ?"
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        addConsole(buffer, "==>  Preparing: $sql", "==> Parameters: 1(Long), 2(Long)")
        buffer.addEntry(agentEntry(sql, listOf(Triple(1, "1", "Long"))))
        val rows = buffer.snapshot()
        assertEquals("签名不同 → crossKey 不同 → 仍两行（已知残余，§4.2 解决）", 2, rows.size)
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
     * **计划 §六 表格第 5 行原始形态的前提被证伪**：`MyBatisLogParser.parseParams` 的类型分组是
     * `[A-Za-z_$][\w$]*(?:\[\])*`——**不含点号**，`42(java.lang.Long)` 匹配不上带类型分支，回落逗号
     * 粗分 → `value="42(java.lang.Long)"`、`type=null`。于是参数值签名与 agent 侧（`42`）不等，
     * crossKey 不同，兜底折叠不触发。真实 MyBatis 日志打印的是简单类名，此形态不可达，故作为解析器
     * 边界钉住（真要支持需同时改 `parseParams` 与 §4.2 的还原器收敛）。
     */
    @Test
    fun `全限定类型名_控制台解析器不识别点号类型_兜底不生效_记录已知残余`() {
        val sql = "INSERT INTO t (amount) VALUES (?)"
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        val console = addConsole(buffer, "==>  Preparing: $sql", "==> Parameters: 42(java.lang.Long)")
        val consoleParam = console.single().params.single()
        assertEquals("解析器不识别点号类型：类型标注丢失", null, consoleParam.type)
        assertEquals("整个 `值(类型)` 被当成参数值", "42(java.lang.Long)", consoleParam.value)
        buffer.addEntry(agentEntry(sql, listOf(Triple(1, "42", "java.lang.Long"))))
        assertEquals("参数值签名不同 → crossKey 不同 → 仍两行（已知残余）", 2, buffer.snapshot().size)
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
