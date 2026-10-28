package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * k2j 互操作注（对齐 master 17be6319 终态）：
 *  - 条目 copy(x = …) 经 copyEntry 私有工厂（record 无 copy，组件逐一回读 + 目标参替换）
 *  - SqlLogFileReader 为单例承载（INSTANCE），静态调用改实例；Sequence 版 parseContent
 *    改 Stream 版（k2j Java 承载是 Stream&lt;String&gt;）
 *  - SqlLogAutoSaver 无单参构造——rollThreshold/clock 注入走 (LongSupplier, long) 两参版
 *  - buffer 回调走 setOnEntryStored/setOnEntryMerged（Java SAM setter）
 *  - 子进程类路径：生产类在 build/classes/java/main（master 是 kotlin/main）
 */
class O147HistoryUpgradeTest {
    private fun entry(sql: String = "SELECT 'synthetic'", seq: Long = 1): LogSqlEntry {
        return LogSqlEntry(sql, sql, emptyList(), 1724567890000L, null, null, SqlSource.UNKNOWN, null,
            null, 0, emptyList(), emptyList(), null, seq, null, null, null, null, null, null,
            CaptureProvenance.CONSOLE_BRIDGE)
    }

    /** master `old.copy(captureProvenance=MERGED, sourceFile=…, sourceLine=…, executionTimeMs=…, databaseName=…)` 的 k2j 等价。 */
    private fun merged(old: LogSqlEntry) = copyEntry(old,
        captureProvenance = CaptureProvenance.MERGED,
        sourceFile = "Synthetic.java", sourceLine = 12, executionTimeMs = 3L, databaseName = "H2")

    /** record 组件逐一回读 + 目标参替换（master data class copy 的 k2j 等价承载）。 */
    private fun copyEntry(
        e: LogSqlEntry,
        captureProvenance: CaptureProvenance = e.getCaptureProvenance(),
        sourceFile: String? = e.sourceFile(),
        sourceLine: Int = e.sourceLine(),
        executionTimeMs: Long? = e.executionTimeMs(),
        databaseName: String? = e.databaseName(),
        rawSql: String = e.rawSql(),
        stackDigest: String? = e.stackDigest(),
        sourceCandidates: List<kotlin.Pair<String, Int>> = e.sourceCandidates(),
    ): LogSqlEntry = LogSqlEntry(rawSql, e.restoredSql(), e.params(), e.timestamp(), e.logTimestamp(),
        e.summary(), e.source(), executionTimeMs, sourceFile, sourceLine,
        sourceCandidates, e.candidateClasses(), stackDigest, e.sequenceNumber(),
        e.mapperStatement(), e.errorText(), e.requestTag(), e.updateCount(), databaseName,
        e.actualSql(), captureProvenance)

    private fun dir() = Files.createTempDirectory("o147-history-").toFile()

    @Test fun upgradesAlreadyFlushedBlockWithoutAddingHistoryRow() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old)
        saver.write(entry("SELECT 'other'", 2))
        saver.submitMerge(old, merged(old)); saver.close()
        val read = SqlLogFileReader.INSTANCE.readFromFile(File(dir, "test.sql"))
        assertEquals(2, read.size)
        assertEquals(CaptureProvenance.MERGED, read[0].captureProvenance())
        assertEquals("Synthetic.java", read[0].sourceFile())
        assertEquals(old.rawSql(), read[0].rawSql())
        assertEquals("SELECT 'other'", read[1].rawSql())
        assertEquals(2, SqlLogFileReader.INSTANCE.countInFile(File(dir, "test.sql")))
    }

    @Test fun queuedEntryUpgradeAndRepeatedMergeStaySingle() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); val new = merged(old)
        synchronized(saver) {
            saver.submit(old); saver.submitMerge(old, new); saver.submitMerge(old, new)
            saver.close()
        }
        val read = SqlLogFileReader.INSTANCE.readFromFile(File(dir, "test.sql"))
        assertEquals(1, read.size); assertEquals(CaptureProvenance.MERGED, read.single().captureProvenance())
    }

    @Test fun externalAppendIsNotOverwritten() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old)
        val file = File(dir, "test.sql"); file.appendText("-- externally-owned-tail\n")
        val original = file.readBytes()
        saver.submitMerge(old, merged(old)); saver.close()
        assertArrayEquals(original, file.readBytes().copyOfRange(0, original.size))
    }

    @Test fun sameLengthExternalEditWithRestoredMtimeIsNotOverwritten() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old)
        val file = File(dir, "test.sql"); val modified = Files.getLastModifiedTime(file.toPath())
        file.writeText(file.readText().replace("synthetic", "EXTERNAL!"))
        Files.setLastModifiedTime(file.toPath(), modified)
        val original = file.readBytes()
        saver.submitMerge(old, merged(old)); saver.close()
        assertArrayEquals(original, file.readBytes().copyOfRange(0, original.size))
    }

    @Test fun deletedFileIsNotRecreatedByMerge() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old); saver.close()
        val file = File(dir, "test.sql"); assertTrue(file.delete())
        saver.submitMerge(old, merged(old)); saver.close()
        assertFalse(file.exists())
    }

    @Test fun configurationChangeDoesNotRewriteOldDirectory() {
        val before = dir(); val after = dir(); val saver = SqlLogAutoSaver()
        saver.configure(before.path, "test.sql", null); val old = entry(); saver.write(old)
        val file = File(before, "test.sql"); val original = file.readBytes()
        saver.configure(after.path, "test.sql", null); saver.submitMerge(old, merged(old)); saver.close()
        assertArrayEquals(original, file.readBytes().copyOfRange(0, original.size)); assertFalse(File(after, "test.sql").exists())
    }

    @Test fun rolledFileUpgradesWithoutLosingNewFile() {
        val dir = dir(); val saver = SqlLogAutoSaver(java.util.function.LongSupplier { System.currentTimeMillis() }, 1L)
        saver.configure(dir.path, "test.sql", null); val old = entry(); saver.write(old)
        saver.write(entry("SELECT 'next'", 2)); saver.submitMerge(old, merged(old)); saver.close()
        assertEquals(CaptureProvenance.MERGED, SqlLogFileReader.INSTANCE.readFromFile(File(dir, "test.sql")).single().captureProvenance())
        assertEquals("SELECT 'next'", SqlLogFileReader.INSTANCE.readFromFile(File(dir, "test.2.sql")).single().rawSql())
    }

    @Test fun sameChannelDuplicateDoesNotFireUpgradeOrStoreTwice() {
        val buffer = SqlLogEntryBuffer(10); var stored = 0; var upgrades = 0
        buffer.setOnEntryStored { stored++ }; buffer.setOnEntryMerged { _, _ -> upgrades++ }
        val e = entry(); buffer.addEntry(e); buffer.addEntry(e)
        assertEquals(1, stored); assertEquals(0, upgrades)
        assertEquals(1, buffer.dedupeCount(e.restoredSql()))
        assertEquals(1, buffer.size())
    }

    @Test fun allReaderEntrypointsAgreeAndMalformedRevisionsDoNotCreateRows() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old); saver.submitMerge(old, merged(old)); saver.close()
        val file = File(dir, "test.sql")
        file.appendText("\n-- pd: o147-revision: invalid-id bad-hash !!! --\n" +
            "-- pd: o147-revision: " + "A".repeat(SqlLogFileReader.MAX_REVISION_PAYLOAD + 500) + " --\n")
        val loaded = SqlLogFileReader.INSTANCE.readFromFile(file)
        val streamed = mutableListOf<LogSqlEntry>()
        SqlLogFileReader.INSTANCE.forEachEntry(file) { streamed += it; true }
        assertEquals(1, SqlLogFileReader.INSTANCE.countInFile(file)); assertEquals(1, loaded.size)
        assertEquals(loaded, streamed)
        assertEquals(loaded, SqlLogFileReader.INSTANCE.parseContent(file.readText()))
        assertEquals(loaded, SqlLogFileReader.INSTANCE.parseContent(file.readText().lines().stream()))
        assertEquals(CaptureProvenance.MERGED, loaded.single().captureProvenance())
    }

    @Test fun changedOriginalSqlDoesNotReceiveRevisionMetadata() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old); saver.submitMerge(old, merged(old)); saver.close()
        val file = File(dir, "test.sql")
        file.writeText(file.readText().replace("SELECT 'synthetic';", "SELECT 'external';"))
        val loaded = SqlLogFileReader.INSTANCE.readFromFile(file).single()
        assertEquals("SELECT 'external'", loaded.restoredSql())
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE, loaded.captureProvenance())
        assertNull(loaded.sourceFile())
    }

    @Test fun revisionCountLimitAndUnknownIdDoNotInventEntries() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old); saver.submitMerge(old, merged(old)); saver.close()
        val file = File(dir, "test.sql")
        val lines = file.readLines()
        val revision = lines.single { it.startsWith("-- pd: o147-revision:") }
        val original = lines.filterNot { it.startsWith("-- pd: o147-revision:") }.joinToString("\n", postfix = "\n")
        val invalid = "-- pd: o147-revision: invalid bad !!! --\n"
        val limited = original + invalid.repeat(SqlLogFileReader.MAX_REVISIONS) + revision + "\n"
        file.writeText(limited)
        val list = SqlLogFileReader.INSTANCE.readFromFile(file)
        assertEquals(1, list.size); assertEquals(CaptureProvenance.CONSOLE_BRIDGE, list.single().captureProvenance())
        assertEquals(list, SqlLogFileReader.INSTANCE.parseContent(limited))
        val streamed = mutableListOf<LogSqlEntry>(); SqlLogFileReader.INSTANCE.forEachEntry(file) { streamed += it; true }
        assertEquals(list, streamed); assertEquals(1, SqlLogFileReader.INSTANCE.countInFile(file))
        file.writeText(original + revision.replace(Regex("[0-9a-f]{8}-[0-9a-f-]{27}"), "00000000-0000-0000-0000-000000000000") + "\n")
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE, SqlLogFileReader.INSTANCE.readFromFile(file).single().captureProvenance())
    }

    @Test fun replacementFileIsNotClaimedAndPermissionFailurePreservesBytes() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old); saver.close()
        val file = File(dir, "test.sql")
        Files.move(file.toPath(), File(dir, "saved.sql").toPath())
        file.writeText("external owner\n")
        saver.submitMerge(old, merged(old)); saver.close()
        assertEquals("external owner\n", file.readText())
        val saver2 = SqlLogAutoSaver(); saver2.configure(dir.path, "locked.sql", null)
        val old2 = entry(); saver2.write(old2); saver2.close()
        val locked = File(dir, "locked.sql"); val original = locked.readBytes()
        val dos = Files.getFileAttributeView(locked.toPath(), java.nio.file.attribute.DosFileAttributeView::class.java)
        org.junit.Assume.assumeNotNull(dos)
        dos.setReadOnly(true)
        try {
            saver2.submitMerge(old2, merged(old2)); saver2.close()
            assertArrayEquals(original, locked.readBytes())
        } finally { dos.setReadOnly(false) }
    }

    @Test fun rawSqlAndSequenceCannotBeChangedByMergeSubmission() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old)
        saver.submitMerge(old, copyEntry(merged(old), rawSql = "SELECT 'wrong'")); saver.close()
        val read = SqlLogFileReader.INSTANCE.readFromFile(File(dir, "test.sql")).single()
        assertEquals(old.rawSql(), read.rawSql()); assertEquals(CaptureProvenance.CONSOLE_BRIDGE, read.captureProvenance())
    }

    @Test fun mergeCallbackBeforeStoreCallbackStillPersistsOneMergedEntry() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val buffer = SqlLogEntryBuffer(10)
        val storedReached = java.util.concurrent.CountDownLatch(1)
        val releaseStore = java.util.concurrent.CountDownLatch(1)
        buffer.setOnEntryStored {
            storedReached.countDown()
            check(releaseStore.await(10, java.util.concurrent.TimeUnit.SECONDS))
            saver.submit(it)
        }
        buffer.setOnEntryMerged { oldE, mergedE -> saver.submitMerge(oldE, mergedE) }
        val old = entry()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val writer = Thread {
            try { buffer.addEntry(old) } catch (t: Throwable) { failure.set(t) }
        }
        writer.start()
        try {
            assertTrue(storedReached.await(10, java.util.concurrent.TimeUnit.SECONDS))
            buffer.addEntry(copyEntry(merged(old), captureProvenance = CaptureProvenance.AGENT))
            // Deterministic drain without sleep: force the first merge to be consumed before store.
            val drain = SqlLogAutoSaver::class.java.getDeclaredMethod("drainAndWrite")
            drain.isAccessible = true
            drain.invoke(saver)
        } finally { releaseStore.countDown(); writer.join(10000) }
        failure.get()?.let { throw it }
        assertFalse(writer.isAlive)
        saver.close()
        val result = SqlLogFileReader.INSTANCE.readFromFile(File(dir, "test.sql"))
        assertEquals(1, result.size)
        assertEquals(CaptureProvenance.MERGED, result.single().captureProvenance())
        assertEquals("Synthetic.java", result.single().sourceFile())
        assertEquals(1, buffer.dedupeCount(old.restoredSql()))
    }

    @Test fun unknownMergeWaitIsBoundedAndCloseOrConfigureDoesNotCreateHistory() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val drain = SqlLogAutoSaver::class.java.getDeclaredMethod("drainAndWrite").apply { isAccessible = true }
        val waits = SqlLogAutoSaver::class.java.getDeclaredField("waitingMerges").apply { isAccessible = true }
        synchronized(saver) {
            repeat(2050) { val old = entry("SELECT $it", it.toLong()); saver.submitMerge(old, merged(old)) }
            drain.invoke(saver)
            assertEquals(2048, (waits.get(saver) as Map<*, *>).size)
            assertFalse(File(dir, "test.sql").exists())
            saver.close()
            assertEquals(0, (waits.get(saver) as Map<*, *>).size)
        }
        val old = entry()
        synchronized(saver) {
            saver.submitMerge(old, merged(old)); drain.invoke(saver)
            saver.configure(dir.path, "next.sql", null)
            assertEquals(0, (waits.get(saver) as Map<*, *>).size)
            saver.submit(old); saver.close()
        }
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE,
            SqlLogFileReader.INSTANCE.readFromFile(File(dir, "next.sql")).single().captureProvenance())
    }

    @Test fun reverseChainOfTwoUpgradesResolvesAfterOriginalStore() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); val first = merged(old); val last = copyEntry(first, stackDigest = "Synthetic#call:12")
        val drain = SqlLogAutoSaver::class.java.getDeclaredMethod("drainAndWrite").apply { isAccessible = true }
        synchronized(saver) {
            saver.submitMerge(first, last); saver.submitMerge(old, first); drain.invoke(saver)
            saver.submit(old); saver.close()
        }
        val read = SqlLogFileReader.INSTANCE.readFromFile(File(dir, "test.sql")).single()
        assertEquals(CaptureProvenance.MERGED, read.captureProvenance())
        assertEquals(last.stackDigest(), read.stackDigest())
    }

    @Test fun expiredWaitingMergeDoesNotUpgradeLaterStore() {
        var now = 0L
        val dir = dir(); val saver = SqlLogAutoSaver(java.util.function.LongSupplier { now }, 100L * 1024 * 1024)
        saver.configure(dir.path, "test.sql", null)
        val old = entry()
        val drain = SqlLogAutoSaver::class.java.getDeclaredMethod("drainAndWrite").apply { isAccessible = true }
        synchronized(saver) {
            saver.submitMerge(old, merged(old)); drain.invoke(saver)
            now = 60001; saver.submit(old); saver.close()
        }
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE,
            SqlLogFileReader.INSTANCE.readFromFile(File(dir, "test.sql")).single().captureProvenance())
    }

    @Test fun oversizedRevisionBudgetAgreesAcrossAllReaders() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        // 此大预算夹具用已知耗时避免头部破折号触发整串 UTF-16 膨胀；Unicode 另有用例。
        val old = copyEntry(entry(), executionTimeMs = 0L); saver.write(old); saver.submitMerge(old, merged(old)); saver.close()
        val file = File(dir, "test.sql")
        val lines = file.readLines()
        val revision = lines.single { it.startsWith("-- pd: o147-revision:") }
        val original = lines.filterNot { it.startsWith("-- pd: o147-revision:") }.joinToString("\n", postfix = "\n")
        val oversized = "-- pd: o147-revision: " + "A".repeat(SqlLogFileReader.MAX_REVISION_PAYLOAD + 500) + "\n"
        file.bufferedWriter().use { out ->
            out.write(original)
            repeat(SqlLogFileReader.MAX_REVISIONS) { out.write(oversized) }
            out.write(revision)
        }
        val loaded = SqlLogFileReader.INSTANCE.readFromFile(file)
        val streamed = mutableListOf<LogSqlEntry>()
        SqlLogFileReader.INSTANCE.forEachEntry(file) { streamed += it; true }
        assertEquals(1, SqlLogFileReader.INSTANCE.countInFile(file))
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE, loaded.single().captureProvenance())
        assertEquals(loaded, streamed)
        // Sequence form exercises the same counterexample without materializing a 129 MiB String.
        // k2j：parseContent 承载为 Stream——Sequence 拼接改 Stream.concat（形态等价）。
        val sequenceResult = SqlLogFileReader.INSTANCE.parseContent(
            java.util.stream.Stream.concat(
                original.lines().stream(),
                java.util.stream.Stream.concat(
                    java.util.stream.IntStream.range(0, SqlLogFileReader.MAX_REVISIONS).mapToObj { oversized.trimEnd() },
                    java.util.stream.Stream.of(revision),
                ),
            ),
        )
        assertEquals(loaded, sequenceResult)
        // JDK compact UTF-8 read avoids Kotlin readText's full UTF-16 intermediate on this 129 MiB fixture.
        assertLargeStringInChild(file, loaded)
    }

    @Test fun exactRevisionLimitLfAgreesAcrossAllReaders() = assertRevisionLineBoundary("\n", 0)

    @Test fun exactRevisionLimitCrLfAgreesAcrossAllReaders() = assertRevisionLineBoundary("\r\n", 0)

    @Test fun overRevisionLimitLfAgreesAcrossAllReaders() = assertRevisionLineBoundary("\n", 1)

    @Test fun overRevisionLimitCrLfAgreesAcrossAllReaders() = assertRevisionLineBoundary("\r\n", 1)

    private fun assertRevisionLineBoundary(ending: String, excess: Int) {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        // ASCII-only header preserves the coordinator's compact-string fixture correction.
        val old = copyEntry(entry(), executionTimeMs = 0L)
        saver.write(old); saver.submitMerge(old, merged(old)); saver.close()
        val file = File(dir, "test.sql")
        val lines = file.readLines()
        val valid = lines.single { it.startsWith("-- pd: o147-revision:") }
        val original = lines.filterNot { it.startsWith("-- pd: o147-revision:") }
        val prefix = "-- pd: o147-revision: "
        val invalid = prefix + "A".repeat(SqlLogFileReader.MAX_REVISION_PAYLOAD + 150 + excess - prefix.length)
        assertEquals(65686 + excess, invalid.length)
        file.bufferedWriter().use { out ->
            original.forEach { out.write(it); out.write(ending) }
            repeat(SqlLogFileReader.MAX_REVISIONS) { out.write(invalid); out.write(ending) }
            out.write(valid); out.write(ending)
        }
        val expected = if (excess == 0) CaptureProvenance.MERGED else CaptureProvenance.CONSOLE_BRIDGE
        // At the exact limit the 8 MiB budget admits only 127 invalid rows, leaving room for
        // the small valid revision; over-limit rows cost a prefix each and exhaust the count.
        // k2j：Sequence 拼接改 Stream.concat（k2j parseContent 承载为 Stream）。
        val fromSequence = SqlLogFileReader.INSTANCE.parseContent(java.util.stream.Stream.concat(
            original.stream(),
            java.util.stream.Stream.concat(
                java.util.stream.IntStream.range(0, SqlLogFileReader.MAX_REVISIONS).mapToObj { invalid },
                java.util.stream.Stream.of(valid),
            ),
        ))
        val fromFile = SqlLogFileReader.INSTANCE.readFromFile(file)
        val streamed = mutableListOf<LogSqlEntry>()
        SqlLogFileReader.INSTANCE.forEachEntry(file) { streamed += it; true }
        assertEquals(1, SqlLogFileReader.INSTANCE.countInFile(file))
        assertEquals(expected, fromSequence.single().captureProvenance())
        assertEquals(fromSequence, fromFile)
        assertEquals(fromSequence, streamed)
        assertLargeStringInChild(file, fromSequence)
    }

    private fun assertLargeStringInChild(file: File, expected: List<LogSqlEntry>) {
        val root = File(System.getProperty("user.dir"))
        // k2j：生产类编译到 build/classes/java/main（master 是 kotlin/main）；测试仍为 kotlin/test。
        val main = File(root, "build/classes/java/main")
        val tests = File(root, "build/classes/kotlin/test")
        check(File(main, "cn/song/mybatisplus/visualizer/log/SqlLogFileReader.class").isFile)
        check(File(tests, "cn/song/mybatisplus/visualizer/log/O147LargeStringProbe.class").isFile)
        val dependencies = listOf(kotlin.Unit::class.java, com.google.gson.Gson::class.java).map { anchor ->
            val resource = requireNotNull(anchor.getResource("/" + anchor.name.replace('.', '/') + ".class"))
            val jar = resource.openConnection() as? java.net.JarURLConnection
            // IntelliJ custom jar handler need not subclass JarURLConnection. Re-open the
            // observed jar URL with the JDK handler; never infer a path from an artifact name.
            val standardJar = if (resource.protocol == "jar") runCatching {
                java.net.URI(resource.toExternalForm()).toURL().openConnection() as? java.net.JarURLConnection
            }.getOrNull() else null
            val resourceFile = if (resource.protocol == "jar" && resource.toExternalForm().contains("!/")) {
                java.net.URI(resource.toExternalForm().removePrefix("jar:").substringBefore("!/")).toURL()
                    .takeIf { it.protocol == "file" }
            } else null
            val url = anchor.protectionDomain?.codeSource?.location?.takeIf { it.protocol == "file" }
                ?: jar?.jarFileURL ?: standardJar?.jarFileURL ?: resourceFile
            val result = File(requireNotNull(url) { "No verified jar location for ${anchor.name}: $resource" }.toURI())
            check(result.isFile)
            java.util.jar.JarFile(result).use { check(it.getJarEntry(anchor.name.replace('.', '/') + ".class") != null) }
            result
        }
        val javaBin = File(System.getProperty("java.home"), "bin/java.exe")
        check(javaBin.isFile)
        val output = File(file.parentFile, "string-child.log")
        val args = listOf(javaBin.path, "-Xmx768m",
            // T-CiRelease（20261025 破案）：子进程必须继承父进程时区——头部时间字符串由父进程
            // 按其默认时区（mpv-testing 钉死 Asia/Shanghai）格式化落盘，子进程按 runner 原生 UTC
            // 解析回毫秒会整体 +8h（原像实证：timestamp=1724596690000 vs 夹具 1724567890000，
            // 差恰 28,800,000ms），timestamp 字段不同 → 指纹必不匹配。透传 user.timezone 后
            // 父子同区，任何机器构造性一致。
            "-Duser.timezone=" + System.getProperty("user.timezone"),
            "-cp", (listOf(main, tests) + dependencies).joinToString(File.pathSeparator),
            O147LargeStringProbe::class.java.name, file.path, O147LargeStringProbe.fingerprint(expected))
        File(file.parentFile, "string-child-command.txt").writeText(args.joinToString("\n"))
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output).start()
        try {
            check(process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) { "String child timeout: $output" }
            val childOutput = output.readText()
            if (process.exitValue() != 0) {
                // T-CiRelease（20261024 诊断自曝）：子进程在 CI 上退出码非 0（本机全绿；字符集对齐
                // 修复后仍复现——em dash/字符集假设被证伪或不充分）。CI 控制台是唯一无需下载工件
                // 即可见的通道：失败时原样打印子进程输出与完整命令行，终结盲猜。
                println("=== O147 string-child FAILED exit=${process.exitValue()} ===")
                println(childOutput)
                println("=== command ===")
                println(File(file.parentFile, "string-child-command.txt").readText())
                println("O147_EXPECTED_PREIMAGE=" + expected)
                // T-CiRelease（20261025）：把 CI 上的原始 test.sql 与命令行拷进 build/o147-diag——
                // 失败工件上传后可拿到 runner 上的真实文件字节，供本地逐字节 diff（本机所有
                // 时区/字符集组合均无法复现分歧，唯余实物对比）。
                runCatching {
                    val diagDir = File(root, "build/o147-diag")
                    diagDir.mkdirs()
                    file.copyTo(File(diagDir, file.name), overwrite = true)
                    File(file.parentFile, "string-child-command.txt").copyTo(File(diagDir, "string-child-command.txt"), overwrite = true)
                }.onFailure { println("o147-diag copy failed: $it") }
                println("=== end O147 string-child diag ===")
            }
            assertEquals(childOutput, 0, process.exitValue())
            assertTrue(childOutput.contains("O147_STRING_OK"))
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    @Test fun uuidSearchUsesFixedMemoryEvenForHugeExternalLine() {
        val saver = SqlLogAutoSaver()
        val expected = "-- pd: o147-id: 00000000-0000-0000-0000-000000000147 --"
        val input = object : java.io.Reader() {
            var remaining = 4_000_000
            val suffix = ("\n" + expected + "\r\n").reader()
            override fun read(): Int = if (remaining-- > 0) 'x'.code else suffix.read()
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                check(len <= 1024) { "unbounded line allocation" }
                val c = read(); if (c < 0) return -1; cbuf[off] = c.toChar(); return 1
            }
            override fun close() = Unit
        }
        assertTrue(SqlLogAutoSaver.containsExactLine(input, expected))
        assertFalse(SqlLogAutoSaver.containsExactLine((expected + "x\n").reader(), expected))
        assertTrue(SqlLogAutoSaver.containsExactLine(expected.reader(), expected))
    }

    @Test fun hugeMetadataAbortsEncodingBeforeTraversingRemainingPayload() {
        val saver = SqlLogAutoSaver()
        var visited = 0
        // k2j：Java 泛型里的 java.lang.Integer 在 Kotlin 映射为 Int——组件经 Int 形态承载。
        val candidates = object : AbstractList<kotlin.Pair<String, Int>>() {
            override val size get() = 1_000_000
            override fun get(index: Int): kotlin.Pair<String, Int> {
                visited++
                check(visited < 5000) { "serialized far beyond revision budget" }
                return kotlin.Pair("Synthetic".repeat(32), index)
            }
        }
        val output = SqlLogAutoSaver.RevisionOutput(SqlLogFileReader.MAX_REVISION_PAYLOAD)
        try {
            SqlLogAutoSaver.writeRevision(copyEntry(merged(entry()), sourceCandidates = candidates), output)
            fail("huge metadata must exceed bounded output")
        } catch (_: SqlLogAutoSaver.RevisionLimitExceeded) { }
        assertTrue(visited in 1..4999)
        assertTrue(output.size() <= SqlLogFileReader.MAX_REVISION_PAYLOAD)
        val dir = dir(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old)
        val file = File(dir, "test.sql"); val original = file.readBytes()
        saver.submitMerge(old, copyEntry(merged(old), stackDigest = "huge".repeat(1_000_000)))
        saver.close()
        assertArrayEquals(original, file.readBytes())
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE, SqlLogFileReader.INSTANCE.readFromFile(file).single().captureProvenance())
    }

    @Test fun normalSaveDoesNotTruncateLargeSqlOrMetadata() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val sql = "SELECT '" + "synthetic".repeat(20000) + "'"
        val original = copyEntry(entry(sql), stackDigest = "stack".repeat(20000))
        saver.write(original); saver.close()
        val read = SqlLogFileReader.INSTANCE.readFromFile(File(dir, "test.sql")).single()
        assertEquals(sql, read.rawSql()); assertEquals(sql, read.restoredSql())
        assertEquals(original.stackDigest(), read.stackDigest())
    }

    @Test fun bareProvenanceWithoutParametersIsPersisted() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        saver.write(entry()); saver.close()
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE,
            SqlLogFileReader.INSTANCE.readFromFile(File(dir, "test.sql")).single().captureProvenance())
    }
}

/** Runs the production String parser in one owned JVM; no platform stubs or weakened budget. */
object O147LargeStringProbe {
    @JvmStatic fun main(args: Array<String>) {
        // T-CiRelease（20261024）：与父进程同口径读文件（JVM 默认字符集）——父侧 saver/readLines/
        // bufferedWriter/生产读取器整条链走默认字符集（本机 UTF-8、EN-US runner 为 Cp1252，单机内自洽），
        // 此处原 Files.readString 强制 UTF-8，在默认字符集非 UTF-8 的机器上把文件头的 em dash
        //（time: —）解码成替换符 → 指纹不匹配 → 子进程退出 1（CI 5/5 确定性失败实证）。
        // T-CiRelease（20261025）：字符集/时区组合本地全试尽仍无法复现指纹分歧——失败时把哈希
        // 原像（entries.toString()，count=1 体量很小）完整打出，供与父侧期望原像逐字段对比。
        // k2j：SqlLogFileReader 为单例承载（INSTANCE）。
        val entries = SqlLogFileReader.INSTANCE.parseContent(File(args[0]).readText())
        if (fingerprint(entries) != args[1]) {
            println("O147_CHILD_PREIMAGE=" + entries)
            println("O147_CHILD_HASH=" + fingerprint(entries))
            println("O147_EXPECTED_HASH=" + args[1])
        }
        check(fingerprint(entries) == args[1]) { "String parser differs from parent sequence/file results" }
        println("O147_STRING_OK count=${entries.size} reader=${SqlLogFileReader::class.java.protectionDomain.codeSource.location}")
    }

    fun fingerprint(entries: List<LogSqlEntry>): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(entries.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
