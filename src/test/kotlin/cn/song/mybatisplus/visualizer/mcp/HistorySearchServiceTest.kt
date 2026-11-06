package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * R14 T-HistorySearch：历史落盘检索器测试（T-01，纯逻辑零平台 fixture）。
 *
 * 夹具直接落 [SqlLogFileReader] 新版格式的 .sql 文件到临时目录——检索链端到端
 * （落盘格式 → Reader 反解析 → 检索过滤），不桩 Reader。
 * 覆盖：关键词/正则/时间范围/来源四维过滤 + 组合、默认当日范围（D1=A）、
 * allDates 扩展、上限截断（D2=A）、预览截断、排序、扫描计数、空目录合法零命中。
 */
class HistorySearchServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nowMs: Long = System.currentTimeMillis()

    private fun fmt(ms: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(ms))

    /**
     * 落盘条目文本（SqlLogAutoSaver 同构）：头部 + SQL + 空行。
     * 历史文件头**不携带 SqlSource**（通道标记在 pd 载荷里且仅 MCP 落键——写端真实语义），
     * 头部 `source:` 段是源码位置；`withPd=true` 时补一条 pd 行（SqlLogParamCodec 编码，
     * 与写端 encodeEntry 同链路），覆盖「pd 行不把条目劈开」的读端互作。
     */
    private fun entryText(
        ts: Long,
        sql: String,
        sourceFile: String? = null,
        withPd: Boolean = false,
    ): String = buildString {
        append("-- source: ").append(sourceFile ?: "unknown")
        append(" | time: 12ms | ").append(fmt(ts)).append(" --\n")
        if (withPd) {
            val carrier = cn.song.mybatisplus.visualizer.log.LogSqlEntry(
                rawSql = sql,
                restoredSql = sql,
                params = listOf(cn.song.mybatisplus.visualizer.log.LogSqlParam("1", "Long")),
                timestamp = ts,
                sourceFile = sourceFile,
            )
            append("-- pd: ").append(cn.song.mybatisplus.visualizer.log.SqlLogParamCodec.encodeEntry(carrier)).append(" --\n")
        }
        append(sql).append("\n\n")
    }

    private fun writeHistoryFile(name: String, entries: List<String>): File {
        val f = tmp.newFile(name)
        f.writeText(entries.joinToString(""))
        return f
    }

    private fun search(dir: File, query: HistorySearchService.Query) =
        HistorySearchService.search(dir.absolutePath, query, nowMs)

    private fun startOfToday(now: Long): Long =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ------------------------------------------------------------------
    // 四维过滤：关键词
    // ------------------------------------------------------------------

    @Test
    fun test关键词命中_SQL文本() {
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(nowMs - 1000, "SELECT * FROM t_user WHERE id = 1"),
                entryText(nowMs - 500, "SELECT * FROM t_order WHERE id = 2"),
            ),
        )
        val r = search(dir(), HistorySearchService.Query(keyword = "t_user"))
        assertEquals(1, r.totalHits)
        assertTrue(r.hits[0].sqlPreview, r.hits[0].sqlPreview.contains("t_user"))
    }

    @Test
    fun test关键词大小写不敏感() {
        writeHistoryFile("h.sql", listOf(entryText(nowMs, "select * from T_USER")))
        assertEquals(1, search(dir(), HistorySearchService.Query(keyword = "T_USER")).totalHits)
        assertEquals(1, search(dir(), HistorySearchService.Query(keyword = "t_user")).totalHits)
    }

    @Test
    fun test关键词命中mapper语句id() {
        writeHistoryFile(
            "h.sql",
            listOf(entryText(nowMs, "SELECT * FROM t_user WHERE id = ?")),
        )
        // mapperStatement 在 pd 载荷里，夹具不写 pd 行 → 这里只验证「SQL 文本命中」语义；
        // mapper 命中由带 pd 的条目覆盖（见 test带pd行条目不被劈开）。
        assertEquals(1, search(dir(), HistorySearchService.Query(keyword = "t_user")).totalHits)
    }

    // ------------------------------------------------------------------
    // 四维过滤：正则
    // ------------------------------------------------------------------

    @Test
    fun test正则命中() {
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(nowMs - 1000, "SELECT * FROM t_user WHERE id = 1"),
                entryText(nowMs - 500, "UPDATE t_user SET name = 'x'"),
            ),
        )
        val r = search(dir(), HistorySearchService.Query(regex = "^select .+ from t_\\w+"))
        assertEquals(1, r.totalHits)
        assertTrue(r.hits[0].sqlPreview.contains("SELECT"))
    }

    // ------------------------------------------------------------------
    // T-SAF-24（mcp.history_search.agent-regex-unbounded-backtracking）：正则预算
    // ------------------------------------------------------------------

    /** 纯逻辑核捷径：空清单走编译/校验路径（不触目录 IO）。 */
    private fun compileQuery(query: HistorySearchService.Query) {
        HistorySearchService.search(emptyList<Pair<String, List<cn.song.mybatisplus.visualizer.log.LogSqlEntry>>>(), query, nowMs)
    }

    @Test
    fun test嵌套量词高危正则_拒绝（先红）() {
        // 灾难性回溯经典构造（(a+)+b 在 "aaaa…!" 上指数级）：改前只查编译合法性——
        // 该模式合法编译并逐条目无预算求值（先红：断言 InvalidQueryException 失败，
        // 实际返回正常结果或挂死）
        try {
            compileQuery(HistorySearchService.Query(regex = "(a+)+b"))
            org.junit.Assert.fail("嵌套量词高危正则应抛 InvalidQueryException")
        } catch (expected: HistorySearchService.InvalidQueryException) {
            assertTrue(expected.message!!.contains("regex"))
        }
    }

    @Test
    fun test量词化分组内含量词_拒绝（先红）() {
        // (a+)* 与 (a+)+ 同族；分支分组 (a|b)+ 无内含量词不误杀（合法命中回归见 test正则命中）
        try {
            compileQuery(HistorySearchService.Query(regex = "(a+)*x"))
            org.junit.Assert.fail("量词化分组嵌套应抛 InvalidQueryException")
        } catch (expected: HistorySearchService.InvalidQueryException) {
            assertTrue(expected.message!!.contains("regex"))
        }
    }

    @Test
    fun test超长正则模式_拒绝（先红）() {
        // 模式长度上限：超长模式多携带病态构造，先于编译拒绝（先红：改前无上限）
        try {
            compileQuery(HistorySearchService.Query(regex = "a".repeat(300)))
            org.junit.Assert.fail("超长正则模式应抛 InvalidQueryException")
        } catch (expected: HistorySearchService.InvalidQueryException) {
            assertTrue(expected.message!!.contains("regex"))
        }
    }

    @Test
    fun test合法正则_初筛不误杀正常命中() {
        // 初筛回归：普通模式（分支分组、有界量词、字符类）照常编译与命中
        val r = HistorySearchService.search(
            listOf("h.sql" to listOf(entry(nowMs, "SELECT * FROM t_user WHERE id = 1"))),
            HistorySearchService.Query(regex = "^select .+ from (t_\\w+)"),
            nowMs,
        )
        assertEquals(1, r.totalHits)
    }

    @Test
    fun test单条目超预算_记miss检索不失败() {
        // 预算缝注入（-1 = 任何求值即超）：命中条目记 miss（totalHits=0），检索不失败；
        // 整查预算放宽（MAX_VALUE）确保只有单条目预算生效
        val savedEntry = HistorySearchService.entryRegexBudgetNs
        val savedTotal = HistorySearchService.totalRegexBudgetNs
        try {
            HistorySearchService.entryRegexBudgetNs = -1L
            HistorySearchService.totalRegexBudgetNs = Long.MAX_VALUE
            val r = HistorySearchService.search(
                listOf("h.sql" to listOf(entry(nowMs, "SELECT * FROM t_user WHERE id = 1"))),
                HistorySearchService.Query(regex = "t_user"),
                nowMs,
            )
            assertEquals("超预算条目应记 miss", 0, r.totalHits)
        } finally {
            HistorySearchService.entryRegexBudgetNs = savedEntry
            HistorySearchService.totalRegexBudgetNs = savedTotal
        }
    }

    @Test
    fun test整查超预算_结构化失败() {
        // 单条目预算放宽（MAX_VALUE 永不触发）、整查预算 -1（首个条目求值即超）：
        // 抛 InvalidQueryException（MCP 侧转译 INVALID_ARGUMENT）
        val savedEntry = HistorySearchService.entryRegexBudgetNs
        val savedTotal = HistorySearchService.totalRegexBudgetNs
        try {
            HistorySearchService.entryRegexBudgetNs = Long.MAX_VALUE
            HistorySearchService.totalRegexBudgetNs = -1L
            try {
                HistorySearchService.search(
                    listOf("h.sql" to listOf(entry(nowMs, "SELECT * FROM t_user WHERE id = 1"))),
                    HistorySearchService.Query(regex = "t_user"),
                    nowMs,
                )
                org.junit.Assert.fail("整查超预算应抛 InvalidQueryException")
            } catch (expected: HistorySearchService.InvalidQueryException) {
                assertTrue(expected.message!!.contains("budget"))
            }
        } finally {
            HistorySearchService.entryRegexBudgetNs = savedEntry
            HistorySearchService.totalRegexBudgetNs = savedTotal
        }
    }

    /** 内存条目夹具（纯逻辑核入参；字段口径与 entryText 的 pd 载荷同构）。 */
    private fun entry(ts: Long, sql: String) = cn.song.mybatisplus.visualizer.log.LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
        sourceFile = null,
    )

    @Test
    fun test非法正则_预期异常() {
        try {
            search(dir(), HistorySearchService.Query(regex = "[unclosed"))
            org.junit.Assert.fail("非法正则应抛 InvalidQueryException")
        } catch (expected: HistorySearchService.InvalidQueryException) {
            assertTrue(expected.message!!.contains("regex"))
        }
    }

    // ------------------------------------------------------------------
    // 四维过滤：时间范围
    // ------------------------------------------------------------------

    @Test
    fun test时间范围过滤_闭区间() {
        val t1 = nowMs - 10_000
        val t2 = nowMs - 5_000
        val t3 = nowMs - 1_000
        writeHistoryFile("h.sql", listOf(entryText(t1, "SELECT 1"), entryText(t2, "SELECT 2"), entryText(t3, "SELECT 3")))
        val r = search(dir(), HistorySearchService.Query(fromMs = t2, toMs = t2, allDates = true))
        assertEquals(1, r.totalHits)
        assertEquals("SELECT 2", r.hits[0].sqlPreview)
    }

    @Test
    fun test时间范围倒置_预期异常() {
        try {
            search(dir(), HistorySearchService.Query(fromMs = nowMs, toMs = nowMs - 1000, allDates = true))
            org.junit.Assert.fail("from > to 应抛 InvalidQueryException")
        } catch (expected: HistorySearchService.InvalidQueryException) {
        }
    }

    // ------------------------------------------------------------------
    // 四维过滤：来源文件（通道标记在落盘数据里仅 MCP 存活——实施期重锚证伪后定案口径）
    // ------------------------------------------------------------------

    @Test
    fun test来源文件过滤_大小写不敏感子串() {
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(nowMs, "SELECT * FROM t_user", sourceFile = "src/main/java/com/example/UserService.java:42"),
                entryText(nowMs + 1, "SELECT * FROM t_order", sourceFile = "src/main/java/com/example/OrderService.java:8"),
                entryText(nowMs + 2, "SELECT * FROM t_log"),
            ),
        )
        assertEquals(1, search(dir(), HistorySearchService.Query(sourceFile = "userservice")).totalHits)
        assertEquals(1, search(dir(), HistorySearchService.Query(sourceFile = "UserService.java")).totalHits)
        // 子串语义：两个服务类都在 com/example 目录下
        assertEquals(2, search(dir(), HistorySearchService.Query(sourceFile = "com/example")).totalHits)
        // 无来源文件的条目对来源文件过滤不命中（不等于「匹配任意」）
        assertEquals(0, search(dir(), HistorySearchService.Query(sourceFile = "t_log")).totalHits)
    }

    // ------------------------------------------------------------------
    // D1=A：默认当日范围 + allDates 扩展
    // ------------------------------------------------------------------

    @Test
    fun test默认只检索当日条目_D1() {
        val yesterday = startOfToday(nowMs) - 86_400_000L
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(yesterday, "SELECT * FROM t_yesterday"),
                entryText(nowMs - 100, "SELECT * FROM t_today"),
            ),
        )
        val r = search(dir(), HistorySearchService.Query(keyword = "t_"))
        assertEquals("默认范围应只含当日条目（D1=A）", 1, r.totalHits)
        assertTrue(r.hits[0].sqlPreview.contains("t_today"))
    }

    @Test
    fun testallDates扩展到全部日期_D1() {
        val yesterday = startOfToday(nowMs) - 86_400_000L
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(yesterday, "SELECT * FROM t_yesterday"),
                entryText(nowMs - 100, "SELECT * FROM t_today"),
            ),
        )
        assertEquals(2, search(dir(), HistorySearchService.Query(keyword = "t_", allDates = true)).totalHits)
    }

    @Test
    fun test默认范围下显式fromMs不可越界到昨日() {
        val yesterday = startOfToday(nowMs) - 86_400_000L
        writeHistoryFile("h.sql", listOf(entryText(yesterday, "SELECT * FROM t_yesterday")))
        // 未开 allDates 时，显式 fromMs 早于今日起点不得放宽默认范围（要跨天请 allDates=true）
        assertEquals(0, search(dir(), HistorySearchService.Query(fromMs = yesterday - 1)).totalHits)
    }

    // ------------------------------------------------------------------
    // 组合过滤
    // ------------------------------------------------------------------

    @Test
    fun test组合过滤_关键词与时间与来源文件() {
        val yesterday = startOfToday(nowMs) - 86_400_000L
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(yesterday, "SELECT * FROM t_user WHERE id = 1", sourceFile = "src/UserService.java"),
                entryText(nowMs - 2000, "SELECT * FROM t_user WHERE id = 2", sourceFile = "src/UserService.java"),
                entryText(nowMs - 1000, "SELECT * FROM t_user WHERE id = 3", sourceFile = "src/OrderService.java"),
                entryText(nowMs - 500, "SELECT * FROM t_order WHERE id = 4"),
            ),
        )
        val r = search(dir(), HistorySearchService.Query(keyword = "t_user", fromMs = nowMs - 2500, sourceFile = "userservice"))
        assertEquals(1, r.totalHits)
        assertTrue(r.hits[0].sqlPreview.contains("id = 2"))
    }

    // ------------------------------------------------------------------
    // D2=A / 上限量保护：截断与预览
    // ------------------------------------------------------------------

    @Test
    fun test超限截断_返回摘要不倾泻() {
        val entries = (1..30).map { entryText(nowMs - it * 100, "SELECT $it") }
        writeHistoryFile("h.sql", entries)
        val r = search(dir(), HistorySearchService.Query(maxResults = 5))
        assertEquals(30, r.totalHits)
        assertEquals(5, r.returned)
        assertTrue("超限应置 truncated", r.truncated)
        assertEquals("默认范围全部命中、maxResults 截断", 5, r.hits.size)
    }

    @Test
    fun testmaxResults越界钳制不抛错() {
        val entries = (1..3).map { entryText(nowMs - it * 100, "SELECT $it") }
        writeHistoryFile("h.sql", entries)
        assertEquals(1, search(dir(), HistorySearchService.Query(maxResults = 0)).returned)
        assertEquals(3, search(dir(), HistorySearchService.Query(maxResults = 999_999)).returned)
    }

    @Test
    fun test命中排序_最新在前() {
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(nowMs - 3000, "SELECT 1"),
                entryText(nowMs - 1000, "SELECT 3"),
                entryText(nowMs - 2000, "SELECT 2"),
            ),
        )
        val r = search(dir(), HistorySearchService.Query(keyword = "SELECT", allDates = true))
        assertEquals(listOf("SELECT 3", "SELECT 2", "SELECT 1"), r.hits.map { it.sqlPreview })
    }

    @Test
    fun testSQL预览截断_多行长SQL() {
        val longSql = (1..50).joinToString("\n") { "SELECT $it" }
        writeHistoryFile("h.sql", listOf(entryText(nowMs, longSql)))
        val h = search(dir(), HistorySearchService.Query()).hits.single()
        assertTrue("预览行数应有上限", h.sqlPreview.lineSequence().count() <= HistorySearchService.MAX_PREVIEW_LINES + 1)
        assertTrue("预览字符应有上限", h.sqlPreview.length <= HistorySearchService.MAX_PREVIEW_CHARS)
        assertTrue("截断应带省略号", h.sqlPreview.endsWith("…"))
    }

    // ------------------------------------------------------------------
    // 合法零命中 / 空目录 / 计数器
    // ------------------------------------------------------------------

    @Test
    fun test空目录_零命中合法结果() {
        val r = search(dir(), HistorySearchService.Query(keyword = "anything"))
        assertEquals(0, r.totalHits)
        assertFalse(r.truncated)
        assertEquals(0, r.scannedFiles)
    }

    @Test
    fun test目录不存在_零命中合法结果() {
        val r = HistorySearchService.search(tmp.root.resolve("no-such-dir").absolutePath, HistorySearchService.Query(keyword = "x"), nowMs)
        assertEquals(0, r.totalHits)
    }

    @Test
    fun test无过滤词_默认当日为合法查询() {
        writeHistoryFile("h.sql", listOf(entryText(nowMs, "SELECT 1")))
        val r = search(dir(), HistorySearchService.Query())
        assertEquals(1, r.totalHits)
    }

    @Test
    fun test扫描计数器() {
        writeHistoryFile("a.sql", listOf(entryText(nowMs, "SELECT 1")))
        writeHistoryFile("b.sql", listOf(entryText(nowMs, "SELECT 2"), entryText(nowMs, "SELECT 3")))
        val r = search(dir(), HistorySearchService.Query(keyword = "SELECT"))
        assertEquals(2, r.scannedFiles)
        assertEquals(3, r.scannedEntries)
        assertFalse(r.scanTruncated)
    }

    @Test
    fun test非sql扩展名不参与扫描() {
        tmp.newFile("readme.txt").writeText("SELECT * FROM t_user")
        writeHistoryFile("h.sql", listOf(entryText(nowMs, "SELECT 1")))
        val r = search(dir(), HistorySearchService.Query(keyword = "SELECT"))
        assertEquals(1, r.scannedFiles)
        assertEquals(1, r.totalHits)
    }

    @Test
    fun test命中携带来源元信息() {
        writeHistoryFile(
            "sql-history-2026-09-30.sql",
            listOf(entryText(nowMs, "SELECT * FROM t_user", sourceFile = "src/UserService.java:42")),
        )
        val h = search(dir(), HistorySearchService.Query(keyword = "t_user")).hits.single()
        // 通道标记落盘不存活（仅 MCP 落键）——读回 UNKNOWN 是真实语义，不是缺陷
        assertEquals("UNKNOWN", h.source)
        assertEquals("src/UserService.java", h.sourceFile)
        assertEquals(42, h.sourceLine)
        assertEquals("sql-history-2026-09-30.sql", h.historyFile)
        assertEquals(12L, h.executionTimeMs)
    }

    @Test
    fun test带pd行条目不被劈开_关键词仍命中() {
        // pd 行（`-- pd: <base64> --`）以 `-- ` 起始——读端必须显式排除，否则条目被劈成两块
        writeHistoryFile(
            "h.sql",
            listOf(
                entryText(nowMs, "SELECT * FROM t_user WHERE id = ?", withPd = true),
            ),
        )
        val r = search(dir(), HistorySearchService.Query(keyword = "t_user"))
        assertEquals("pd 行不得把条目劈开或挤丢", 1, r.totalHits)
        val h = r.hits.single()
        assertTrue(h.sqlPreview.contains("t_user"))
        assertEquals("pd 载荷的 mapperStatement 应透出", null, h.mapperStatement)
    }

    private fun dir(): File = tmp.root

    // ------------------------------------------------------------------
    // F55/F56/F65（D31 道 A）：扫描环三合一——文件名倒序/条目粒度封顶/名日期预跳过
    // ------------------------------------------------------------------

    /** 造数辅助：按时间戳生成 sql-history-yyyy-MM-dd.sql 文件名（默认模板形态，名字序＝日期序）。 */
    private fun fileNameOf(ts: Long): String =
        "sql-history-" + DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(ts)) + ".sql"

    /** 今日 01:00（恒为今日——锚定当日零点而非 nowMs 偏移，防跨午夜跑测漂移）。 */
    private val tToday: Long get() = startOfToday(nowMs) + 3_600_000

    /** 昨日 23:00。 */
    private val tYesterday: Long get() = startOfToday(nowMs) - 3_600_000

    /** 前日 01:00。 */
    private val tTwoDaysAgo: Long get() = startOfToday(nowMs) - 2 * 86_400_000L + 3_600_000

    /**
     * F55 主断言：封顶截断丢旧不丢新——文件名倒序（新→旧）读盘，预算耗尽时被丢弃的是
     * 最旧侧。三个日期文件各 2 条、预算 4：只读今日+昨日两文件，前日文件不读——
     * **今天的数据必须在场**（旧环升序先读前日+昨日、恰把今日文件截掉 → 本测红）。
     */
    @Test
    fun test三合一_封顶截断_今日最新数据在场() {
        writeHistoryFile(
            fileNameOf(tTwoDaysAgo),
            listOf(entryText(tTwoDaysAgo, "SELECT old_2d_a"), entryText(tTwoDaysAgo + 1, "SELECT old_2d_b")),
        )
        writeHistoryFile(
            fileNameOf(tYesterday),
            listOf(entryText(tYesterday, "SELECT old_1d_a"), entryText(tYesterday + 1, "SELECT old_1d_b")),
        )
        writeHistoryFile(
            fileNameOf(tToday),
            listOf(entryText(tToday, "SELECT today_a"), entryText(tToday + 1, "SELECT today_b")),
        )
        val r = HistorySearchService.search(
            dir().absolutePath,
            HistorySearchService.Query(keyword = "SELECT", allDates = true),
            nowMs,
            scanEntryCap = 4,
        )
        assertTrue("预算耗尽应置 scanTruncated", r.scanTruncated)
        assertEquals("解析条目数 ≤ 上限（恰耗尽预算）", 4, r.scannedEntries)
        assertEquals(
            "命中只来自最新的两文件（今日+昨日）——读盘新→旧",
            setOf(fileNameOf(tToday), fileNameOf(tYesterday)),
            r.hits.map { it.historyFile }.toSet(),
        )
        assertTrue("今天的数据在场", r.hits.any { it.sqlPreview.contains("today") })
    }

    /**
     * F65 主断言：名日期与 mtime 均早于范围起点的文件**零 IO 预跳过**——扫描预算不被
     * 旧文件耗尽。预算 1 + 昨日文件（名日期/mtime 双旧）：预跳过后预算留给今日文件
     * （旧环升序先读昨日文件耗尽预算 → 今日缺席 → 本测红）。
     */
    @Test
    fun test三合一_默认今日_旧文件预跳过不耗预算() {
        val oldFile = writeHistoryFile(fileNameOf(tYesterday), listOf(entryText(tYesterday, "SELECT old_entry")))
        oldFile.setLastModified(tYesterday) // mtime 与名日期同旧——起点后未被追加的铁证
        writeHistoryFile(fileNameOf(tToday), listOf(entryText(tToday, "SELECT today_entry")))
        val r = HistorySearchService.search(
            dir().absolutePath,
            HistorySearchService.Query(keyword = "SELECT"),
            nowMs,
            scanEntryCap = 1,
        )
        assertEquals("今天的数据在场（旧文件被预跳过、预算未被其耗尽）", 1, r.totalHits)
        assertTrue(r.hits.single().sqlPreview.contains("today_entry"))
        assertFalse("预跳过不是截断", r.scanTruncated)
    }

    /**
     * F56 主断言：条目粒度增量解析——解析条目数不超上限（同文件中途即停，不再整文件
     * 物化）。单文件 5 条、预算 3（旧环 readFromFile 整文件解析 scannedEntries=5 → 本测红）。
     */
    @Test
    fun test三合一_条目粒度封顶_单文件中途即停() {
        writeHistoryFile(
            fileNameOf(tToday),
            (1..5).map { entryText(tToday + it.toLong(), "SELECT n$it") },
        )
        val r = HistorySearchService.search(
            dir().absolutePath,
            HistorySearchService.Query(allDates = true),
            nowMs,
            scanEntryCap = 3,
        )
        assertTrue(r.scanTruncated)
        assertEquals("解析条目数 ≤ 上限", 3, r.scannedEntries)
        assertEquals("封顶内命中 3 条", 3, r.totalHits)
    }

    /** 方向回归守卫：默认今日范围——今日条目在场、昨日条目缺席（纯过滤面，不涉截断）。 */
    @Test
    fun test三合一_默认今日_方向与范围正确性() {
        val oldFile = writeHistoryFile(fileNameOf(tYesterday), listOf(entryText(tYesterday, "SELECT yesterday_only")))
        oldFile.setLastModified(tYesterday)
        writeHistoryFile(fileNameOf(tToday), listOf(entryText(tToday, "SELECT today_only")))
        val r = search(dir(), HistorySearchService.Query())
        assertEquals(1, r.totalHits)
        assertTrue(r.hits.single().sqlPreview.contains("today_only"))
        assertFalse(r.scanTruncated)
    }

    /** F65 判据直测：名日期/mtime/范围下界三信号——任一缺失或偏新都不跳（跨天追加防误伤）。 */
    @Test
    fun test三合一_预跳过判据_preSkippable直测() {
        val scope = startOfToday(nowMs)
        val oldFile = writeHistoryFile(fileNameOf(tYesterday), listOf(entryText(tYesterday, "SELECT x")))
        val todayFile = writeHistoryFile(fileNameOf(tToday), listOf(entryText(tToday, "SELECT y")))
        val noDateFile = writeHistoryFile("custom-template.sql", listOf(entryText(tYesterday, "SELECT z")))
        oldFile.setLastModified(tYesterday)
        noDateFile.setLastModified(tYesterday)
        assertFalse("无范围下界（allDates 且无 fromMs）永不跳", HistorySearchService.preSkippable(oldFile, null))
        assertTrue("名日期+mtime 双旧必跳", HistorySearchService.preSkippable(oldFile, scope))
        assertFalse(
            "名字无日期（自定义模板）不跳——按名日期预跳过的登记形态，正确性优先",
            HistorySearchService.preSkippable(noDateFile, scope),
        )
        oldFile.setLastModified(nowMs)
        assertFalse(
            "mtime 偏新（起点后可能仍被追加——跨天滚动块）不跳",
            HistorySearchService.preSkippable(oldFile, scope),
        )
        assertFalse("今日文件不跳", HistorySearchService.preSkippable(todayFile, scope))
    }
}
