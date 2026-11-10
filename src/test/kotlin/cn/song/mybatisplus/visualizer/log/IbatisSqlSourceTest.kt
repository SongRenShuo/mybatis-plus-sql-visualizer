package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * `SqlSource.IBATIS` 枚举兑现与扩散面用例（T-Ibatis2Support 券 H）。
 *
 * 覆盖四件事：
 *  1. **产出点**：`{pstm-N}` 三行组经真实控制台链产出 [SqlSource.IBATIS]（MyBatis 形态仍产 MYBATIS）——
 *     也是 `convention/EnumReachabilityGuardTest`（新枚举值必须有真实产出点）的行为侧对照；
 *  2. **消费点**：预览「未捕获绑定」警告按来源分流到 `log.preview.unresolvedNoBinding.ibatis`
 *     （照通用文案会把 iBatis 用户引去查 Hibernate 的 TRACE 开关）；
 *  3. **持久化**：`s` 键的写入集合未扩张，旧条目里的 `MYBATIS` 名字仍能读回（不迁移、不改旧值语义）；
 *  4. **去重**：`dedupeKey`/跨通道折叠与来源枚举无关——同一次执行在控制台（iBatis 形态）与 agent
 *     通道各捕一条时仍折叠为一行。
 */
class IbatisSqlSourceTest {

    @After
    fun tearDown() {
        SqlCaptureGate.paused.set(false)
        SqlCaptureGate.epActive = false
        LogTimeCorrelator.clear()
    }

    // ---------- 1. 产出点 ----------

    private val insertSql = "INSERT INTO bs_rwd_group_info_his (ORGID, SORT_NO) VALUES (?, ?)"

    /** 用户真机形态的 iBatis 三行组（log4j2 + `java.sql.PreparedStatement` 日志代理）。 */
    private val ibatisThreeLines = listOf(
        "{pstm-100021} Executing Statement: $insertSql",
        "{pstm-100021} Parameters: [00340025002200200006, 7]",
        "{pstm-100021} Types: [java.lang.String, java.lang.Integer]",
    )

    @Test
    fun `iBatis三行组产出IBATIS来源`() {
        val parser = MyBatisLogParser()
        ibatisThreeLines.forEach { parser.feed(it) }
        parser.closePending()
        val entries = parser.takeEntries()
        assertEquals("三行组应闭合为 1 条", 1, entries.size)
        assertEquals(SqlSource.IBATIS, entries.single().source)
        // 与来源标记一并钉住既有还原行为（来源改动不得顺带打断参数还原）
        assertEquals(
            "INSERT INTO bs_rwd_group_info_his (ORGID, SORT_NO) VALUES ('00340025002200200006', 7)",
            entries.single().restoredSql,
        )
    }

    @Test
    fun `MyBatis形态仍产出MYBATIS来源`() {
        val parser = MyBatisLogParser()
        parser.feed("==>  Preparing: SELECT id FROM user WHERE id = ?")
        parser.feed("==> Parameters: 42(Long)")
        parser.closePending()
        val entries = parser.takeEntries()
        assertEquals(1, entries.size)
        assertEquals("MyBatis 桶闭合路径的来源不得被 iBatis 改动带偏", SqlSource.MYBATIS, entries.single().source)
    }

    // ---------- 2. 消费点：预览警告按来源分流 ----------

    private fun unboundEntry(source: SqlSource) = LogSqlEntry(
        rawSql = "SELECT * FROM user WHERE id = ?",
        restoredSql = "SELECT * FROM user WHERE id = ?",
        params = emptyList(),
        source = source,
    )

    @Test
    fun `iBatis条目的未捕获绑定警告走iBatis文案`() {
        val warning = unboundEntry(SqlSource.IBATIS).toPreviewResult(false, false).warning
        assertEquals(
            MpSqlVisualizerBundle.message("log.preview.unresolvedNoBinding.ibatis"),
            warning,
        )
        assertFalse("iBatis 文案不得把用户导向 Hibernate 的 TRACE 开关", warning!!.contains("Hibernate"))
        assertFalse("iBatis 文案不得把用户导向 Hibernate 的 TRACE 开关", warning.contains("TRACE"))
    }

    @Test
    fun `其余来源的警告键不受影响`() {
        val generic = MpSqlVisualizerBundle.message("log.preview.unresolvedNoBinding")
        assertEquals(generic, unboundEntry(SqlSource.MYBATIS).toPreviewResult(false, false).warning)
        assertEquals(generic, unboundEntry(SqlSource.UNKNOWN).toPreviewResult(false, false).warning)
        assertEquals(
            MpSqlVisualizerBundle.message("log.preview.unresolvedNoBinding.jdbc"),
            unboundEntry(SqlSource.JDBC).toPreviewResult(false, false).warning,
        )
    }

    // ---------- 3. 持久化：写入集合未扩张 + 旧值名可读 ----------

    private fun payload(json: String): String =
        Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    @Test
    fun `IBATIS条目落盘不带s键_与MYBATIS同口径读回UNKNOWN`() {
        val entry = LogSqlEntry(
            rawSql = insertSql,
            restoredSql = "INSERT INTO bs_rwd_group_info_his (ORGID, SORT_NO) VALUES ('00340025002200200006', 7)",
            params = listOf(LogSqlParam("00340025002200200006", "java.lang.String"), LogSqlParam("7", "java.lang.Integer")),
            source = SqlSource.IBATIS,
        )
        val encoded = SqlLogParamCodec.encodeEntry(entry)
        val json = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        assertFalse("非 MCP/SHARDINGSPHERE 不落 s 键（写入集合不扩张）: $json", json.contains("\"s\""))
        assertNull("读端回退 null → SqlLogFileReader 沿用 UNKNOWN", SqlLogParamCodec.decode(encoded)!!.source)

        // 真实回环：自动存档 → 历史读回
        val dir = Files.createTempDirectory("ibatis-source-").toFile().also { it.deleteOnExit() }
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        saver.write(entry)
        saver.close()
        val file = dir.listFiles { f -> f.extension == "sql" }!!.single()
        val read = SqlLogFileReader.readFromFile(file)
        assertEquals("回环条数一致", 1, read.size)
        assertEquals("SQL 正文回环一致", entry.restoredSql, read.single().restoredSql)
        assertEquals("来源未落盘 → 读回 UNKNOWN（与 MYBATIS 现状同口径）", SqlSource.UNKNOWN, read.single().source)
    }

    @Test
    fun `旧载荷里的MYBATIS名字仍读得出`() {
        // 旧条目/未来回滚场景：s 键里出现 MYBATIS 时必须仍解析到该枚举值（新增 IBATIS 不得打乱名字空间）
        assertEquals(
            SqlSource.MYBATIS,
            SqlLogParamCodec.decode(payload("""{"r":"SELECT 1","p":[],"s":"MYBATIS"}"""))!!.source,
        )
        assertEquals(
            SqlSource.IBATIS,
            SqlLogParamCodec.decode(payload("""{"r":"SELECT 1","p":[],"s":"IBATIS"}"""))!!.source,
        )
        // 与本版本不认识的未来名字同口径：只丢来源标记，不丢整条
        assertNull(SqlLogParamCodec.decode(payload("""{"r":"SELECT 1","p":[],"s":"SOME_FUTURE_SOURCE"}"""))!!.source)
    }

    // ---------- 4. 去重键与来源枚举无关 ----------

    private fun sameExecution(source: SqlSource) = LogSqlEntry(
        rawSql = insertSql,
        restoredSql = "INSERT INTO bs_rwd_group_info_his (ORGID, SORT_NO) VALUES ('00340025002200200006', 7)",
        params = listOf(LogSqlParam("00340025002200200006", "java.lang.String"), LogSqlParam("7", "java.lang.Integer")),
        source = source,
    )

    @Test
    fun `来源枚举不入去重键`() {
        assertEquals(
            "同一 SQL 的 dedupeKey 不得因来源枚举不同而分叉（键 = 还原后文本指纹）",
            sameExecution(SqlSource.MYBATIS).dedupeKey,
            sameExecution(SqlSource.IBATIS).dedupeKey,
        )
        assertEquals(
            sameExecution(SqlSource.IBATIS).dedupeKey,
            sameExecution(SqlSource.AGENT).dedupeKey,
        )
    }

    @Test
    fun `iBatis控制台条目与agent事件仍有跨通道折叠`() {
        val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000)
        val ctx = BridgeLineContext(
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
        SqlCaptureGate.paused.set(false)
        val buffer = SqlLogEntryBuffer(maxEntries = 20)
        // 布尔参数是两套还原器口径分叉的既有形态（控制台按 java.lang.Boolean 直出 true，agent 侧
        // 非数值型 → 加引号）——取它是为了让折叠走**跨通道兜底**分支（键分叉时才会走到），
        // 从而断言得到「同一次执行两份观测合并、不计 ⨉N 丢弃」这一跨通道语义。
        val sql = "INSERT INTO bs_rwd_group_info_his (DEL_FLAG) VALUES (?)"
        val lines = listOf(
            "{pstm-100031} Executing Statement: $sql",
            "{pstm-100031} Parameters: [true]",
            "{pstm-100031} Types: [java.lang.Boolean]",
        )
        val consoleOut = mutableListOf<LogSqlEntry>()
        lines.forEach { consoleOut += processConsoleLine(it, ctx, null, {}) }
        val console = consoleOut.map { it.copy(captureProvenance = CaptureProvenance.CONSOLE_BRIDGE) }
        assertEquals("控制台侧确实产出一条 iBatis 条目", 1, console.size)
        assertEquals(SqlSource.IBATIS, console.single().source)
        console.forEach { buffer.addEntry(it) }

        // 同一次执行的 agent 侧观测（真实适配链：字段映射 + 参数还原）
        val agent = AgentEventAdapter.toEntry(
            AgentEvent(
                sql = sql,
                timestamp = System.currentTimeMillis(),
                durationNanos = 7_000_000L,
                updateCount = 1L,
                errorText = null,
                sourceFile = "src/main/java/com/example/RwdGroupService.java",
                sourceLine = 42,
                methodKey = "RwdGroupService#save",
                requestTag = null,
                params = listOf(Triple(1, "true", "Boolean")),
                threadName = "main",
                url = "jdbc:mysql://localhost:3306/demo",
            ),
        )
        assertNotEquals(
            "前提：两侧 dedupeKey 确实分叉（否则本用例只测到同键去重、测不到跨通道兜底）",
            console.single().dedupeKey,
            agent.dedupeKey,
        )
        buffer.addEntry(agent)

        val rows = buffer.snapshot()
        assertEquals("同一次执行只留一行（来源枚举不阻断跨通道折叠）", 1, rows.size)
        assertEquals("通道归并 MERGED", CaptureProvenance.MERGED, rows.single().captureProvenance)
        assertEquals("跨通道折叠不计 ⨉N 徽章（不是重复丢弃）", 0, buffer.dedupeCountsSnapshot().size)
    }
}
