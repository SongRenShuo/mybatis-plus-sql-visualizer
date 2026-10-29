package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动保存 → 历史读取 回环测试（F-44-2/F-44-3 真实场景）：
 *
 * 模拟真实链路——日志面板捕获 SQL（带源码位置与耗时）→ buffer 淘汰触发 [SqlLogAutoSaver.write]
 * 写入 `.idea/sql-visualizer/sql-history/` → 用户打开历史面板经 [SqlLogFileReader] 读回。
 * 断言写读两端字段完全一致（SQL 文本、源码位置、耗时、时间戳、条数），防止格式回归。
 */
class SqlLogHistoryRoundTripTest {

    /** 构造带完整元数据的真实条目（对齐 FilterProxyHandler 组装的 entry）。 */
    private fun capturedEntry(
        sql: String,
        sourceFile: String? = "src/main/java/com/example/mapper/UserMapper.java",
        sourceLine: Int = 42,
        executionTimeMs: Long? = 5L,
        timestamp: Long = 1724567890000L,
    ) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = timestamp,
        executionTimeMs = executionTimeMs,
        sourceFile = sourceFile,
        sourceLine = sourceLine,
    )

    private fun tempDir(): File {
        val dir = Files.createTempDirectory("roundtrip-").toFile()
        dir.deleteOnExit()
        return dir
    }

    private fun timestampStr(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH).format(Date(ms))

    @Test
    fun `真实链路_捕获条目自动保存后读取回环_字段全一致`() {
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        // 真实捕获条目：带源码位置 + 5ms 耗时。
        // T-SqlDuration（master）：读回端 removeSuffix(";") 剥除 SQL 结束符——写入侧带分号、
        // 读回侧统一无分号，故本用例输入不带分号；带分号写入的剥除行为由下方独立断言覆盖。
        val captured = capturedEntry(
            sql = "SELECT id, username, password FROM user WHERE id = 1",
            sourceFile = "src/main/java/com/example/mapper/UserMapper.java",
            sourceLine = 42,
            executionTimeMs = 5L,
        )
        saver.write(captured)
        saver.close()

        val files = dir.listFiles { f -> f.extension == "sql" }!!
        assertEquals("应生成 1 个文件", 1, files.size)
        // 文件头部应为标准 SQL 注释 `--`（F-44-2 双横线，非 `---`）
        val firstLine = files[0].readText().lineSequence().first()
        assertEquals("-- source: src/main/java/com/example/mapper/UserMapper.java:42 | time: 5ms | ${timestampStr(captured.timestamp)} --", firstLine)

        // 用户打开历史面板读取
        val entries = SqlLogFileReader.readFromFile(files[0])
        assertEquals("条数一致", 1, entries.size)
        val read = entries[0]
        assertEquals("SQL 文本一致", captured.restoredSql, read.restoredSql)
        assertEquals("源码文件一致", captured.sourceFile, read.sourceFile)
        assertEquals("源码行号一致", captured.sourceLine, read.sourceLine)
        assertEquals("耗时一致", captured.executionTimeMs, read.executionTimeMs)
        assertEquals("时间戳一致", captured.timestamp, read.timestamp)
    }

    @Test
    fun `真实链路_多文件多条目_文件条数统计与读取一致`() {
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        // 模拟两天的历史：今天 3 条、昨天 2 条
        saver.write(capturedEntry("SELECT 1", sourceFile = "A.java", sourceLine = 1, executionTimeMs = 1L))
        saver.write(capturedEntry("SELECT 2", sourceFile = "A.java", sourceLine = 2, executionTimeMs = 2L))
        saver.write(capturedEntry("SELECT 3", sourceFile = "A.java", sourceLine = 3, executionTimeMs = 3L))
        saver.close()

        val files = dir.listFiles { f -> f.extension == "sql" }!!
        assertEquals("同一天应写入 1 个文件", 1, files.size)
        // 一级文件列表显示的条数统计（轻量 countInFile）
        assertEquals("countInFile 条数 = 3", 3, SqlLogFileReader.countInFile(files[0]))
        // 二级读取的完整解析
        assertEquals("readFromFile 条数 = 3", 3, SqlLogFileReader.readFromFile(files[0]).size)

        // 第二个文件（模拟另一天：固定文件名，避免与当天文件同名被覆盖——newBufferedWriter 默认 TRUNCATE）
        val saver2 = SqlLogAutoSaver()
        saver2.configure(dir.absolutePath, "sql-history-2026-08-30.sql", null)
        saver2.write(capturedEntry("SELECT A", sourceFile = "B.java", sourceLine = 10, executionTimeMs = null, timestamp = 1724567000000L))
        saver2.write(capturedEntry("SELECT B", sourceFile = "B.java", sourceLine = 11, executionTimeMs = null, timestamp = 1724567000001L))
        saver2.close()
        assertEquals("两天共 2 个文件", 2, dir.listFiles { f -> f.extension == "sql" }!!.size)

        // 无耗时条目：文件写 `—`，读回 null
        val all = SqlLogFileReader.readAll(dir.absolutePath)
        assertEquals("两文件共 5 条", 5, all.size)
        val noDuration = all.filter { it.restoredSql.startsWith("SELECT A") || it.restoredSql.startsWith("SELECT B") }
        assertEquals(2, noDuration.size)
        noDuration.forEach { assertNull("无耗时条目读回 null", it.executionTimeMs) }

        // T-LogHistoryRowNo：round-trip 读回条目按文件内顺序带序号（写端不持久化，读端派生）
        val today = SqlLogFileReader.readFromFile(files[0])
        assertEquals("今天文件 3 条按顺序号 1..3", listOf(1L, 2L, 3L), today.map { it.sequenceNumber })
    }

    @Test
    fun `真实链路_带分号写入读回剥除结束符`() {
        // T-SqlDuration（master 5fbb3c7）：历史读回统一剥除 SQL 结束符（；耗时展示不受分号干扰）。
        // M38 合并期 feature 侧曾按旧读回器断言「保留分号」，合并后产品行为以剥除为准——
        // 本用例显式固化：带分号写入 → 读回无分号。
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        saver.write(capturedEntry(sql = "SELECT * FROM user WHERE id = 1;"))
        saver.close()

        val files = dir.listFiles { f -> f.extension == "sql" }!!
        val read = SqlLogFileReader.readFromFile(files[0]).single()
        assertEquals("读回剥除结束符", "SELECT * FROM user WHERE id = 1", read.restoredSql)
    }

    @Test
    fun `真实链路_旧F43格式文件仍可读取`() {
        // 升级前遗留的 F-43 文件（无源码位置、无耗时），历史面板应能展示
        val dir = tempDir()
        val legacy = File(dir, "sql-history-2026-08-20.sql")
        legacy.writeText(
            "-- 2026-08-20 10:00:00\nSELECT * FROM user WHERE id = 1\n\n" +
                "-- 2026-08-20 10:00:01\nUPDATE user SET name = 'x' WHERE id = 2\n\n",
        )
        val entries = SqlLogFileReader.readAll(dir.absolutePath)
        assertEquals(2, entries.size)
        assertEquals("SELECT * FROM user WHERE id = 1", entries[0].restoredSql)
        assertEquals("UPDATE user SET name = 'x' WHERE id = 2", entries[1].restoredSql)
        assertNull("旧格式无源码位置", entries[0].sourceFile)
        assertNull("旧格式无耗时", entries[0].executionTimeMs)
        assertEquals("旧格式条数统计一致", 2, SqlLogFileReader.countInFile(legacy))
    }

    @Test
    fun `真实链路_混合格式单文件_新老格式条目共存`() {
        // 升级过渡期：同文件内既有 F-44-2 双横线、又有历史 F-43 时间戳头
        val dir = tempDir()
        val file = File(dir, "sql-history-2026-08-31.sql")
        file.writeText(
            "-- source: UserMapper.java:10 | time: 3ms | 2026-08-31 17:30:00.000 --\n" +
                "SELECT 1\n\n" +
                "-- 2026-08-31 09:00:00\n" +
                "SELECT 2\n\n" +
                "-- source: OrderMapper.java:20 | time: 10ms | 2026-08-31 17:31:00.000 --\n" +
                "SELECT 3\n\n",
        )
        val entries = SqlLogFileReader.readFromFile(file) // 保持文件内顺序（readAll 会按 timestamp 重排）
        assertEquals(3, entries.size)
        // 新格式：源码 + 耗时
        assertEquals("UserMapper.java", entries[0].sourceFile)
        assertEquals(3L, entries[0].executionTimeMs)
        // 旧格式：无源码无耗时
        assertNull(entries[1].sourceFile)
        // 新格式
        assertEquals("OrderMapper.java", entries[2].sourceFile)
        assertEquals(10L, entries[2].executionTimeMs)
        assertEquals("轻量计数与完整解析一致", 3, SqlLogFileReader.countInFile(file))
    }

    @Test
    fun `真实链路_空历史目录_返回空列表`() {
        val dir = tempDir()
        assertEquals(0, SqlLogFileReader.readAll(dir.absolutePath).size)
        assertEquals(0, SqlLogFileReader.countInFile(File(dir, "not-exists.sql")))
    }

    @Test
    fun `真实链路_带参数条目写读回环_历史页参数识别与日志页一致`() {
        // T-HistParam：捕获条目（rawSql + params）→ 自动落盘 → 历史面板读回 →
        // 统一识别通道（toPreviewResult）产出与日志页相同的参数形态
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        saver.write(
            LogSqlEntry(
                rawSql = "SELECT * FROM user WHERE id = ? AND name = ?",
                restoredSql = "SELECT * FROM user WHERE id = 42 AND name = 'neo'",
                params = listOf(LogSqlParam("42", "Long"), LogSqlParam("neo", "String")),
                timestamp = 1724567890000L,
                executionTimeMs = 5L,
                sourceFile = "src/main/java/com/example/mapper/UserMapper.java",
                sourceLine = 42,
            ),
        )
        saver.close()

        val files = dir.listFiles { f -> f.extension == "sql" }!!
        assertEquals(1, files.size)
        val read = SqlLogFileReader.readFromFile(files[0]).single()
        assertEquals("rawSql 回环", "SELECT * FROM user WHERE id = ? AND name = ?", read.rawSql)
        assertEquals("restoredSql 回环", "SELECT * FROM user WHERE id = 42 AND name = 'neo'", read.restoredSql)
        assertEquals(2, read.params.size)

        // 历史页预览构建（formatSql=false 现状）与日志页识别通道同源
        val result = read.toPreviewResult(formatSql = false, literalRecovery = true)
        assertEquals(listOf("param1", "param2"), result.unresolvedParams)
        assertEquals(mapOf("param1" to "42", "param2" to "neo"), result.initialParamValues)
        assertEquals(listOf(false, true), result.paramHints.map { it.quote })
        assertNull(result.warning)
    }

    // ---------- M56 T-M56-00：扩展字段（mapperStatement/errorText/requestTag/updateCount）回环 ----------

    @Test
    fun `真实链路_扩展字段条目写读回环_四字段不丢`() {
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        // agent 通道形态：无参数、无占位符，但扩展字段齐备——pd 行须因扩展字段在场而写入
        val captured = LogSqlEntry(
            rawSql = "UPDATE user SET name = 'x' WHERE id = 7",
            restoredSql = "UPDATE user SET name = 'x' WHERE id = 7",
            params = emptyList(),
            timestamp = 1724567890000L,
            executionTimeMs = 2L,
            mapperStatement = "com.example.mapper.UserMapper.updateName",
            errorText = null,
            requestTag = "req-88",
            updateCount = 1L,
        )
        saver.write(captured)
        saver.close()

        val files = dir.listFiles { f -> f.extension == "sql" }!!
        assertEquals(1, files.size)
        val read = SqlLogFileReader.readFromFile(files[0]).single()
        assertEquals("mapperStatement 回环", captured.mapperStatement, read.mapperStatement)
        assertEquals("requestTag 回环", captured.requestTag, read.requestTag)
        assertEquals("updateCount 回环", captured.updateCount, read.updateCount)
        assertNull("无报错条目回环 null", read.errorText)
    }

    /**
     * T-StackBizFrame S3：调用栈快照与线程名都要经历史文件回环——线程名是弹层标题的一部分，
     * 读回丢失会让历史条目的标题退回纯「调用栈」，用户重新失去「这条 SQL 跑在哪个线程」的判别量。
     */
    @Test
    fun `真实链路_调用栈快照与线程名写读回环_不丢`() {
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        val captured = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            timestamp = 1724567890000L,
            stackDigest = "cn.acme.Svc#run:1<-java.lang.Thread#run:2",
            threadName = "scheduling-1",
        )
        saver.write(captured)
        saver.close()

        val files = dir.listFiles { f -> f.extension == "sql" }!!
        assertEquals(1, files.size)
        val read = SqlLogFileReader.readFromFile(files[0]).single()
        assertEquals("栈快照回环", captured.stackDigest, read.stackDigest)
        assertEquals("线程名回环", captured.threadName, read.threadName)
    }

    @Test
    fun `真实链路_报错条目写读回环_errorText不丢`() {
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        saver.write(
            LogSqlEntry(
                rawSql = "SELECT * FROM no_such_table",
                restoredSql = "SELECT * FROM no_such_table",
                params = emptyList(),
                timestamp = 1724567890001L,
                errorText = "BadSqlGrammarException: Table 'no_such_table' doesn't exist",
            ),
        )
        saver.close()
        val read = SqlLogFileReader.readFromFile(dir.listFiles { f -> f.extension == "sql" }!![0]).single()
        assertEquals(
            "errorText 回环",
            "BadSqlGrammarException: Table 'no_such_table' doesn't exist",
            read.errorText,
        )
        assertNull(read.mapperStatement)
    }

    // ---------- T-McpAuditTrail T-MAT-03 读端回填：来源标记 round-trip ----------
    // 立项背景：写入侧（pd 载荷落 `s` 键）与 pd 载荷层 round-trip 已由 T-McpAuditTrail 交付，
    // 但读端 [SqlLogFileReader.parseBlock] 当时被并行任务占用未同步改动 → 重启后 MCP 条目的
    // 来源退化为 [SqlSource.UNKNOWN]（历史页 MCP 徽标消失）。本组用例钉住读端回填。

    /** MCP 条目写入侧的常态形态：无参数、无占位符、无 mapperStatement——pd 行完全靠 source==MCP 触发。 */
    private fun mcpEntry(sql: String, timestamp: Long = 1724567890000L) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = timestamp,
        executionTimeMs = 7L,
        databaseName = "demo-mysql",
        source = SqlSource.MCP,
    )

    @Test
    fun `真实链路_MCP条目写读回环_来源标记不退化`() {
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        saver.write(mcpEntry("SELECT id FROM user WHERE id = 1"))
        saver.close()

        val file = dir.listFiles { f -> f.extension == "sql" }!!.single()
        // 写入侧前提（T-MAT-03）：无参数无占位符的 MCP SELECT 也必须落 pd 行，否则来源无处可存
        assertTrue("MCP 条目必须写出 pd 载荷行", file.readText().contains("-- pd: "))

        // 读端（重启后历史面板读文件）：来源必须仍是 MCP
        val read = SqlLogFileReader.readFromFile(file).single()
        assertEquals("读端回填来源标记（改前退化为 UNKNOWN → 徽标消失）", SqlSource.MCP, read.source)
        assertEquals("SQL 文本不受来源键影响", "SELECT id FROM user WHERE id = 1", read.restoredSql)
        assertEquals("目标库角标随载荷回环", "demo-mysql", read.databaseName)
        assertNull("pd 载荷不侵占头部 source: 段语义（仍为源码位置）", read.sourceFile)
    }

    @Test
    fun `真实链路_非MCP条目写读回环_来源保持UNKNOWN不扩张`() {
        // T-MAT-03 的读端口径：只有 MCP 落 `s` 键，其余来源不因本次改动改变读回结果
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        saver.write(
            LogSqlEntry(
                rawSql = "SELECT * FROM user WHERE id = ?",
                restoredSql = "SELECT * FROM user WHERE id = 1",
                params = listOf(LogSqlParam("1", "Long")),
                timestamp = 1724567890000L,
                sourceFile = "UserMapper.java",
                sourceLine = 7,
            ),
        )
        saver.close()

        val read = SqlLogFileReader.readFromFile(dir.listFiles { f -> f.extension == "sql" }!![0]).single()
        assertEquals("非 MCP 条目读回来源仍为 UNKNOWN", SqlSource.UNKNOWN, read.source)
        assertEquals("其余字段不受影响", "UserMapper.java", read.sourceFile)
    }

    @Test
    fun `真实链路_旧版F43头部下的pd载荷_来源回填MCP`() {
        // 旧版头部分支（-- yyyy-MM-dd HH:mm:ss）同样要回填——升级过渡期的混合文件里，
        // 头部形态与 pd 行是否在场互相独立，两条分支漏一条即出现「同一文件内徽标时有时无」
        val dir = tempDir()
        val pd = SqlLogParamCodec.encodeEntry(mcpEntry("SELECT 2"))
        File(dir, "sql-history-2026-08-20.sql").writeText(
            "-- 2026-08-20 10:00:00\n-- pd: $pd --\nSELECT 2;\n\n",
        )

        val read = SqlLogFileReader.readAll(dir.absolutePath).single()
        assertEquals("旧版头部分支读端回填来源标记", SqlSource.MCP, read.source)
        assertEquals("SELECT 2", read.restoredSql)
        assertNull("旧版头部无源码位置", read.sourceFile)
    }
}
