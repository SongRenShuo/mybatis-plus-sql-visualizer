package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SQL 日志文件读取器单元测试（F-44，纯 JUnit，无平台依赖）。
 */
class SqlLogFileReaderTest {

    @Test
    fun `解析新版格式`() {
        val content = "--- source: src/main/java/com/example/mapper/UserMapper.java:42 | time: 5ms | 2026-08-25 10:00:00.123 ---\nSELECT * FROM user WHERE id = 1\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("SELECT * FROM user WHERE id = 1", entry.restoredSql)
        assertEquals("src/main/java/com/example/mapper/UserMapper.java", entry.sourceFile)
        assertEquals(42, entry.sourceLine)
        assertEquals(5L, entry.executionTimeMs)
        // 时区无关：仅验证 timestamp > 0
        assertTrue("Timestamp should be positive: ${entry.timestamp}", entry.timestamp > 0)
    }

    @Test
    fun `解析新版格式_logTime读回回归`() {
        // T-AgentDialectLoop 回归锁：毫秒分数曾为嵌套捕获组占位第 4 组，logTime 永远读丢
        val content = "--- source: a/A.java:9 | time: 5ms | 2026-08-25 10:00:00.123 | logTime: 1724567890123 ---\nSELECT 1\n\n"
        val entry = SqlLogFileReader.parseContent(content)[0]
        assertEquals(1724567890123L, entry.logTimestamp!!)
    }

    @Test
    fun `解析新版格式_logTime缺省为null`() {
        val content = "--- source: a/A.java:9 | time: 5ms | 2026-08-25 10:00:00.123 ---\nSELECT 1\n\n"
        val entry = SqlLogFileReader.parseContent(content)[0]
        assertNull(entry.logTimestamp)
    }

    @Test
    fun `解析新版格式_db读回_T-AgentDialectLoop`() {
        val content = "--- source: unknown | time: — | 2026-08-25 10:00:00.123 | logTime: 1724567890123 | db: DM（达梦） ---\nSELECT 1\n\n"
        val entry = SqlLogFileReader.parseContent(content)[0]
        assertEquals(1724567890123L, entry.logTimestamp!!)
        assertEquals("DM（达梦）", entry.databaseName)
    }

    @Test
    fun `解析新版格式_db缺省为null`() {
        val content = "--- source: unknown | time: — | 2026-08-25 10:00:00.123 ---\nSELECT 1\n\n"
        val entry = SqlLogFileReader.parseContent(content)[0]
        assertNull(entry.databaseName)
    }

    @Test
    fun `解析新版格式_unknown source`() {
        val content = "--- source: unknown | time: — | 2026-08-25 10:00:00.123 ---\nSELECT 1\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertNull(entry.sourceFile)
        assertEquals(0, entry.sourceLine)
        assertNull(entry.executionTimeMs)
    }

    @Test
    fun `解析旧版格式`() {
        val content = "-- 2026-08-25 10:00:00\nSELECT * FROM user\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("SELECT * FROM user", entry.restoredSql)
        assertNull(entry.sourceFile)
        assertEquals(0, entry.sourceLine)
    }

    @Test
    fun `解析混合格式`() {
        val content = """
            --- source: UserMapper.java:10 | time: 3ms | 2026-08-25 10:00:00.000 ---
            SELECT 1

            -- 2026-08-25 09:00:00
            SELECT 2

            --- source: OrderMapper.java:20 | time: 10ms | 2026-08-25 11:00:00.000 ---
            SELECT 3

        """.trimIndent()
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(3, entries.size)
        // 按文件中出现顺序：新版 → 旧版 → 新版
        assertEquals("SELECT 1", entries[0].restoredSql)
        assertEquals("UserMapper.java", entries[0].sourceFile)
        assertEquals("SELECT 2", entries[1].restoredSql)
        assertNull(entries[1].sourceFile)
        assertEquals("SELECT 3", entries[2].restoredSql)
        assertEquals("OrderMapper.java", entries[2].sourceFile)
    }

    @Test
    fun `解析多条新版格式`() {
        val content = """
            --- source: A.java:1 | time: 1ms | 2026-08-25 10:00:00.000 ---
            SELECT 1

            --- source: B.java:2 | time: 2ms | 2026-08-25 10:00:01.000 ---
            SELECT 2

            --- source: C.java:3 | time: 3ms | 2026-08-25 10:00:02.000 ---
            SELECT 3

        """.trimIndent()
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(3, entries.size)
        assertEquals("SELECT 1", entries[0].restoredSql)
        assertEquals("SELECT 2", entries[1].restoredSql)
        assertEquals("SELECT 3", entries[2].restoredSql)
    }

    @Test
    fun `空内容返回空列表`() {
        assertTrue(SqlLogFileReader.parseContent("").isEmpty())
        assertTrue(SqlLogFileReader.parseContent("   \n\n   ").isEmpty())
    }

    @Test
    fun `损坏格式返回空列表`() {
        val content = "这不是有效的格式\nSELECT 1\n"
        assertTrue(SqlLogFileReader.parseContent(content).isEmpty())
    }

    @Test
    fun `读取文件并排序`() {
        val dir = Files.createTempDirectory("f44-reader-test-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "test.sql")
        file.writeText("""
            --- source: A.java:1 | time: 1ms | 2026-08-25 10:00:02.000 ---
            SELECT 3

            --- source: B.java:2 | time: 2ms | 2026-08-25 10:00:00.000 ---
            SELECT 1

            --- source: C.java:3 | time: 3ms | 2026-08-25 10:00:01.000 ---
            SELECT 2

        """.trimIndent())
        val entries = SqlLogFileReader.readFromFile(file).sortedBy { it.timestamp }
        assertEquals(3, entries.size)
        // 按时间戳排序
        assertEquals("SELECT 1", entries[0].restoredSql)
        assertEquals("SELECT 2", entries[1].restoredSql)
        assertEquals("SELECT 3", entries[2].restoredSql)
    }

    @Test
    fun `读取目录`() {
        val dir = Files.createTempDirectory("f44-reader-dir-").toFile()
        dir.deleteOnExit()
        File(dir, "a.sql").writeText("--- source: A.java:1 | time: 1ms | 2026-08-25 10:00:00.000 ---\nSELECT 1\n\n")
        File(dir, "b.sql").writeText("--- source: B.java:2 | time: 2ms | 2026-08-25 10:00:01.000 ---\nSELECT 2\n\n")
        // 非 .sql 文件应被忽略
        File(dir, "ignore.txt").writeText("ignore me")

        val entries = SqlLogFileReader.readAll(dir.absolutePath)
        assertEquals(2, entries.size)
    }

    @Test
    fun `解析双横线新版格式`() {
        // F-44-2：默认写作 `-- source: ... --`（两个 `-` 起结），Reader 必须正确解析
        val content = "-- source: src/main/java/UserMapper.java:42 | time: 5ms | 2026-08-31 17:30:00.123 --\nSELECT * FROM user WHERE id = 1\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("SELECT * FROM user WHERE id = 1", entry.restoredSql)
        assertEquals("src/main/java/UserMapper.java", entry.sourceFile)
        assertEquals(42, entry.sourceLine)
        assertEquals(5L, entry.executionTimeMs)
    }

    @Test
    fun `兼容三横线历史新版格式`() {
        // F-44-2 已停止生产三横线，但历史文件仍可能存在——Reader 必须向后兼容
        val content = "--- source: A.java:1 | time: 3ms | 2026-08-31 17:30:00.000 ---\nSELECT 1\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        assertEquals("SELECT 1", entries[0].restoredSql)
        assertEquals("A.java", entries[0].sourceFile)
        assertEquals(3L, entries[0].executionTimeMs)
    }

    @Test
    fun `解析混合格式_F44-2与F43共存`() {
        // 验证新版本启用的双横线格式能与旧版时间戳格式共存
        val content = """
            -- source: UserMapper.java:10 | time: 3ms | 2026-08-31 17:30:00.000 --
            SELECT 1

            -- 2026-08-31 09:00:00
            SELECT 2

            -- source: OrderMapper.java:20 | time: 10ms | 2026-08-31 17:30:01.000 --
            SELECT 3

        """.trimIndent()
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(3, entries.size)
        assertEquals("UserMapper.java", entries[0].sourceFile)
        assertEquals(3L, entries[0].executionTimeMs)
        assertNull(entries[1].sourceFile)
        assertEquals("OrderMapper.java", entries[2].sourceFile)
        assertEquals(10L, entries[2].executionTimeMs)
    }

    @Test
    fun `解析无源码行号时只返回文件`() {
        val content = "--- source: UserMapper.java | time: 5ms | 2026-08-25 10:00:00.123 ---\nSELECT 1\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        assertEquals("UserMapper.java", entries[0].sourceFile)
        assertEquals(0, entries[0].sourceLine)
    }

    // ---------- F-44-3：countInFile 轻量条数统计（一级文件列表数据源） ----------

    @Test
    fun `countInFile 统计新版双横线格式`() {
        val dir = Files.createTempDirectory("f443-count-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "a.sql")
        file.writeText("""
            -- source: A.java:1 | time: 1ms | 2026-08-31 17:30:00.000 --
            SELECT 1

            -- source: B.java:2 | time: 2ms | 2026-08-31 17:30:01.000 --
            SELECT 2

            -- source: C.java:3 | time: 3ms | 2026-08-31 17:30:02.000 --
            SELECT 3

        """.trimIndent())
        assertEquals(3, SqlLogFileReader.countInFile(file))
        // 与 readFromFile 完整解析一致
        assertEquals(3, SqlLogFileReader.readFromFile(file).size)
    }

    @Test
    fun `countInFile 兼容三横线与旧版格式混合`() {
        val dir = Files.createTempDirectory("f443-count-mix-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "mix.sql")
        file.writeText("""
            --- source: A.java:1 | time: 3ms | 2026-08-31 17:30:00.000 ---
            SELECT 1

            -- 2026-08-25 09:00:00
            SELECT 2

            -- source: B.java:2 | time: 5ms | 2026-08-31 17:30:01.000 --
            SELECT 3

        """.trimIndent())
        assertEquals(3, SqlLogFileReader.countInFile(file))
        // 与完整解析结果一致
        assertEquals(3, SqlLogFileReader.readFromFile(file).size)
    }

    @Test
    fun `countInFile 空文件与不存在文件返回0`() {
        val dir = Files.createTempDirectory("f443-count-empty-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "empty.sql")
        file.writeText("")
        assertEquals(0, SqlLogFileReader.countInFile(file))
        assertEquals(0, SqlLogFileReader.countInFile(File(dir, "missing.sql")))
    }

    // ------------------------------------------------------------------
    // 格式 v2：参数持久化行（T-HistParam）
    // ------------------------------------------------------------------

    @Test
    fun `v2带参数行读回_rawSql与params还原_restoredSql为正文`() {
        val payload = SqlLogParamCodec.encode(
            "SELECT * FROM t WHERE a = ? AND b = ?",
            listOf(LogSqlParam("x", "String"), LogSqlParam(null, "BIGINT")),
        )
        val dir = Files.createTempDirectory("histparam-v2-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "v2.sql")
        file.writeText(
            "-- source: A.java:1 | time: 1ms | 2026-09-11 09:00:00.000 --\n" +
                "-- pd: $payload --\n" +
                "SELECT * FROM t WHERE a = 'x' AND b = NULL\n\n",
        )
        val entries = SqlLogFileReader.readFromFile(file)
        assertEquals(1, entries.size)
        assertEquals("rawSql 从载荷还原（含 ?）", "SELECT * FROM t WHERE a = ? AND b = ?", entries[0].rawSql)
        assertEquals("restoredSql 为正文（剥结尾分号）", "SELECT * FROM t WHERE a = 'x' AND b = NULL", entries[0].restoredSql)
        assertEquals(
            "params 从载荷还原",
            listOf(LogSqlParam("x", "String"), LogSqlParam(null, "BIGINT")),
            entries[0].params,
        )
        // 读回条目可直接走统一识别通道（与日志 tab 同参）
        assertTrue(entries[0].toRecognizedPreview().recognized)
    }

    @Test
    fun `v2损坏参数行降级为正文注释行`() {
        val dir = Files.createTempDirectory("histparam-bad-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "bad.sql")
        file.writeText(
            "-- source: A.java:1 | time: 1ms | 2026-09-11 09:00:00.000 --\n" +
                "-- pd: !!!not-base64!!! --\n" +
                "SELECT 1\n\n",
        )
        val entries = SqlLogFileReader.readFromFile(file)
        assertEquals(1, entries.size)
        assertEquals("解码失败 pd 行留在正文（多一行注释无害）", "-- pd: !!!not-base64!!! --\nSELECT 1", entries[0].restoredSql)
        assertEquals("-- pd: !!!not-base64!!! --\nSELECT 1", entries[0].rawSql)
        assertTrue(entries[0].params.isEmpty())
    }

    @Test
    fun `v2参数行不参与头部计数与块切分`() {
        val payload = SqlLogParamCodec.encode("SELECT ?", listOf(LogSqlParam("1", "Long")))
        val dir = Files.createTempDirectory("histparam-count-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "mixed.sql")
        file.writeText(
            "-- source: A.java:1 | time: 1ms | 2026-09-11 09:00:00.000 --\n" +
                "-- pd: $payload --\n" +
                "SELECT '1'\n\n" +
                "-- 2026-09-11 08:00:00\n" +
                "SELECT 2\n\n",
        )
        // pd 行以 `-- ` 起始且无 `source:`——不排除会被旧头部判据误判，计数翻倍且块被劈开
        assertEquals("countInFile 不计 pd 行", 2, SqlLogFileReader.countInFile(file))
        val entries = SqlLogFileReader.readFromFile(file)
        assertEquals("pd 行不劈块：v2 条目 + F-43 条目", 2, entries.size)
        assertEquals("SELECT '1'", entries[0].restoredSql)
        assertEquals("SELECT 2", entries[1].restoredSql)
    }

    // ---------- T-LogHistoryRowNo：文件内条目顺序号 ----------

    @Test
    fun `parseContent 注入文件内顺序号_连续`() {
        val content = """
            --- source: A.java:1 | time: 1ms | 2026-08-25 10:00:00.000 ---
            SELECT 1

            --- source: B.java:2 | time: 2ms | 2026-08-25 10:00:01.000 ---
            SELECT 2

            --- source: C.java:3 | time: 3ms | 2026-08-25 10:00:02.000 ---
            SELECT 3

        """.trimIndent()
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(listOf(1L, 2L, 3L), entries.map { it.sequenceNumber })
    }

    @Test
    fun `parseContent 混合格式顺序号按文件解析顺序`() {
        // 新旧格式混合：序号按文件出现顺序连续（不按格式分组）
        val content = """
            -- source: UserMapper.java:10 | time: 3ms | 2026-08-31 17:30:00.000 --
            SELECT 1

            -- 2026-08-31 09:00:00
            SELECT 2

            --- source: OrderMapper.java:20 | time: 10ms | 2026-08-31 17:30:01.000 ---
            SELECT 3

        """.trimIndent()
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(3, entries.size)
        assertEquals(listOf(1L, 2L, 3L), entries.map { it.sequenceNumber })
    }

    @Test
    fun `readAll 多文件每文件内序号独立从1起`() {
        val dir = Files.createTempDirectory("loghistrow-").toFile()
        dir.deleteOnExit()
        File(dir, "a.sql").writeText(
            "--- source: A.java:1 | time: 1ms | 2026-08-25 10:00:00.000 ---\nSELECT 1\n\n" +
                "--- source: B.java:2 | time: 2ms | 2026-08-25 10:00:01.000 ---\nSELECT 2\n\n",
        )
        File(dir, "b.sql").writeText(
            "--- source: C.java:1 | time: 3ms | 2026-08-25 10:00:02.000 ---\nSELECT 3\n\n",
        )
        // 历史面板按单文件展示：每文件内序号各自 1..N（不跨文件连续）
        assertEquals(listOf(1L, 2L), SqlLogFileReader.readFromFile(File(dir, "a.sql")).map { it.sequenceNumber })
        assertEquals(listOf(1L), SqlLogFileReader.readFromFile(File(dir, "b.sql")).map { it.sequenceNumber })
    }

    @Test
    fun `空内容序号无影响`() {
        assertTrue(SqlLogFileReader.parseContent("").isEmpty())
    }

    @Test
    fun `同格式两次解析只建一次formatter_缓存命中不重建`() {
        // T-PerfSweep7 4a（先红后绿主判据）：逐次新建形态下第二次解析仍计数 +1；缓存化后 +0。
        // 刻意用独立新线程保证冷启动：测试类此前用例可能已在本线程预热过缓存，复用当前线程
        // 会让绿态读数退化成 +0/+0，无法与「缝失效空绿」区分；deltas[0] >= 1 即缝活性自检。
        val content = "-- 2026-08-25 10:00:00\nSELECT 1\n"
        val deltas = IntArray(2)
        val t = Thread {
            val before = SqlLogFileReader.formatterCreateCount.get()
            SqlLogFileReader.parseContent(content)
            deltas[0] = SqlLogFileReader.formatterCreateCount.get() - before
            SqlLogFileReader.parseContent(content)
            deltas[1] = SqlLogFileReader.formatterCreateCount.get() - before - deltas[0]
        }
        t.isDaemon = true
        t.start()
        t.join(10_000L)
        assertTrue("计数缝必须可用（冷线程首次解析应记录到 formatter 构造），实际 ${deltas[0]}", deltas[0] >= 1)
        assertEquals("同格式第二次解析必须命中缓存（不新建 formatter），实际计数 +${deltas[1]}", 0, deltas[1])
    }

    // ------------------------------------------------------------------
    // V47（D31 道 A 硬前置）：forEachEntry 逐条迭代器
    // ------------------------------------------------------------------

    /** V47：逐条迭代与 readFromFile 全量解析逐条等价（含 sequenceNumber 注入序）。 */
    @Test
    fun `forEachEntry 与 readFromFile 逐条等价_含序号`() {
        val dir = Files.createTempDirectory("v47-equiv-").toFile()
        dir.deleteOnExit()
        val content = """
            --- source: A.java:1 | time: 1ms | 2026-08-25 10:00:00.000 ---
            SELECT 1

            -- 2026-08-25 09:00:00
            SELECT 2

            --- source: B.java:2 | time: 2ms | 2026-08-25 10:00:01.000 ---
            SELECT 3

        """.trimIndent()
        val file = File(dir, "mixed.sql")
        file.writeText(content)
        val streamed = mutableListOf<LogSqlEntry>()
        SqlLogFileReader.forEachEntry(file) { streamed.add(it); true }
        val bulk = SqlLogFileReader.readFromFile(file)
        assertEquals("条目数一致", bulk.size, streamed.size)
        assertEquals("逐条等价（含序号）——共享同一状态机的单一实现源", bulk, streamed)
    }

    /** V47：回调返回 false 即停止（条目粒度早停），且文件句柄随停随闭（Windows 删除即探针）。 */
    @Test
    fun `forEachEntry 早停即闭_不再消费后续条目`() {
        val dir = Files.createTempDirectory("v47-stop-").toFile()
        dir.deleteOnExit()
        val content = buildString {
            repeat(5) { i ->
                append("--- source: A.java:").append(i + 1).append(" | time: 1ms | 2026-08-25 10:00:0").append(i).append(".000 ---\n")
                append("SELECT ").append(i + 1).append("\n\n")
            }
        }
        val file = File(dir, "five.sql")
        file.writeText(content)
        var seen = 0
        SqlLogFileReader.forEachEntry(file) {
            seen++
            seen < 2 // 第 2 条后返回 false → 停
        }
        assertEquals("早停后不再消费后续条目", 2, seen)
        assertTrue("中途停止也须关闭文件句柄（Windows 上未闭句柄删除必败）", file.delete())
    }

    /** V47：文件不存在零回调（与 readFromFile 的 exists 守卫语义一致）。 */
    @Test
    fun `forEachEntry 文件不存在零回调`() {
        var seen = 0
        SqlLogFileReader.forEachEntry(File("no-such-v47-file.sql")) { seen++; true }
        assertEquals(0, seen)
    }

    // ======================== T-SAF-23：读侧逐行/逐文件上限 ========================

    /**
     * T-SAF-23（history.reader.unbounded-line-buffering）回归：boundedLines 的超限折叠
     * 曾只对 revision 前缀行生效（SqlLogFileReader.kt:115），非 revision 行（SQL 正文 /
     * 头部 / `-- pd: ` 载荷行）无任何长度上限累积——被植入或超大的 .sql 历史文件会在
     * 历史读取与 MCP 历史搜索（HistorySearchService → forEachEntry）期间把整行缓冲进
     * IDE 内存。修复语义：非 revision 单行累积上限 1MB，超限折叠为占位注释
     * 「sql-visualizer: 超长行已折叠（单行超 1MB 上限）」（SQL 块注释形态，不判为头部，
     * 块连续性保持；逐字文本见下方断言）。
     *
     * **修复前失败断言**：`超长行内容不得全量保留`（占位断言同样失败）——修复前 1MB+1KB
     * 的植入行被全量读入并作为条目正文返回。
     */
    @Test
    fun `超长非revision行折叠为占位注释_T-SAF-23`() {
        val dir = Files.createTempDirectory("tsaf23-line-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "planted.sql")
        val header = "-- source: unknown | time: 1ms | 2026-11-06 10:00:00.000 --\n"
        val giant = "A".repeat(1024 * 1024 + 1024)
        file.writeText(header + giant + "\n\n")
        val entries = SqlLogFileReader.readFromFile(file)
        assertEquals("条目本身仍应解析出来（折叠不劈块）", 1, entries.size)
        val sql = entries[0].restoredSql
        assertTrue(
            "超长行应折叠为占位注释: ${sql.take(120)}",
            sql.contains("/* sql-visualizer: 超长行已折叠（单行超 1MB 上限） */"),
        )
        assertFalse("超长行内容不得全量保留", sql.contains(giant))
        assertTrue("折叠后条目体量应有界", sql.length < 4096)
    }

    /**
     * T-SAF-23 回归（占位不劈块）：超长行折叠后，同一块内的已有正文行仍归属同一头部
     * （占位注释不满足任何头部判据），块内已有内容不因折叠丢失。
     */
    @Test
    fun `超长行折叠不劈块_已有正文仍归属同一条目_T-SAF-23`() {
        val dir = Files.createTempDirectory("tsaf23-block-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "planted.sql")
        file.writeText(
            "-- source: A.java:9 | time: 1ms | 2026-11-06 10:00:00.000 --\n" +
                "SELECT 1\n" +
                "B".repeat(1024 * 1024 + 512) + "\n" +
                "\n",
        )
        val entries = SqlLogFileReader.readFromFile(file)
        assertEquals(1, entries.size)
        val sql = entries[0].restoredSql
        assertTrue("折叠前的正文行应保留", sql.contains("SELECT 1"))
        assertTrue("折叠占位应在场", sql.contains("/* sql-visualizer: 超长行已折叠（单行超 1MB 上限） */"))
        assertFalse("超长行内容不得全量保留", sql.contains("B".repeat(1024)))
    }
}
