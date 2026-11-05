package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T9（T-TestGapSpeed）：五个 AgreesAcrossAllReaders 场景的子 JVM 验证登记表。
 * 原形态=每场景各 fork 一次 `-Xmx768m` 子 JVM（上轮 XML 实测 16.4~16.8s/例，且 8MiB 与
 * 129MiB 夹具耗时几乎相同——大头是 Windows fork+classpath 而非解析），5 fork 合计 80s+。
 * 现场景测试只登记 (文件, 期望指纹, 标签)，由 [O147HistoryUpgradeTest.verifyLargeStringsInOneChildJvm]
 * 在类结束后 **一次 fork 逐文件验证**——断言逐字保留（任何文件指纹不符 ⇒ 子进程退出非 0），
 * 断言映射表见计划册 T9 券。
 */
private val O147_CHILD_VERIFICATIONS = java.util.concurrent.CopyOnWriteArrayList<Triple<File, String, String>>()

/** T9：子验证场景数（场景用例早退导致登记缺失时，@AfterClass 会在此显红，防子验证静默缩水）。 */
private const val O147_EXPECTED_VERIFICATIONS = 5

/** T9：大夹具目录延后到类级清理（子 JVM 验证要用文件，@After 不能先删）。 */
private val O147_DEFERRED_DIRS = java.util.concurrent.CopyOnWriteArrayList<File>()

class O147HistoryUpgradeTest {
    /**
     * 券05（T-PerfSweep28-05，R28-7）：临时目录登记 + @After 统一清理。
     *
     * 此前每个用例 `Files.createTempDirectory` 新建目录后无人删除——全量门禁实测每轮泄漏
     * 130M × N（累计 1292 个 ≈ 35G 吃满 C 盘，放大成磁盘空间类假失败与 Docker 启动被挤）。
     */
    private val tempDirs = mutableListOf<File>()

    @org.junit.After
    fun cleanTempDirs() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun entry(sql: String = "SELECT 'synthetic'", seq: Long = 1) = LogSqlEntry(
        sql, sql, emptyList(), timestamp = 1724567890000L, sequenceNumber = seq,
        captureProvenance = CaptureProvenance.CONSOLE_BRIDGE,
    )
    private fun merged(old: LogSqlEntry) = old.copy(captureProvenance = CaptureProvenance.MERGED,
        sourceFile = "Synthetic.java", sourceLine = 12, executionTimeMs = 3, databaseName = "H2")
    private fun dir(deferToClassEnd: Boolean = false) =
        Files.createTempDirectory("o147-history-").toFile().also {
            if (deferToClassEnd) O147_DEFERRED_DIRS += it else tempDirs += it
        }

    @Test fun upgradesAlreadyFlushedBlockWithoutAddingHistoryRow() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old)
        saver.write(entry("SELECT 'other'", 2))
        saver.submitMerge(old, merged(old)); saver.close()
        val read = SqlLogFileReader.readFromFile(File(dir, "test.sql"))
        assertEquals(2, read.size)
        assertEquals(CaptureProvenance.MERGED, read[0].captureProvenance)
        assertEquals("Synthetic.java", read[0].sourceFile)
        assertEquals(old.rawSql, read[0].rawSql)
        assertEquals("SELECT 'other'", read[1].rawSql)
        assertEquals(2, SqlLogFileReader.countInFile(File(dir, "test.sql")))
    }

    @Test fun queuedEntryUpgradeAndRepeatedMergeStaySingle() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); val new = merged(old)
        synchronized(saver) {
            saver.submit(old); saver.submitMerge(old, new); saver.submitMerge(old, new)
            saver.close()
        }
        val read = SqlLogFileReader.readFromFile(File(dir, "test.sql"))
        assertEquals(1, read.size); assertEquals(CaptureProvenance.MERGED, read.single().captureProvenance)
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
        val dir = dir(); val saver = SqlLogAutoSaver(rollThresholdBytes = 1)
        saver.configure(dir.path, "test.sql", null); val old = entry(); saver.write(old)
        saver.write(entry("SELECT 'next'", 2)); saver.submitMerge(old, merged(old)); saver.close()
        assertEquals(CaptureProvenance.MERGED, SqlLogFileReader.readFromFile(File(dir, "test.sql")).single().captureProvenance)
        assertEquals("SELECT 'next'", SqlLogFileReader.readFromFile(File(dir, "test.2.sql")).single().rawSql)
    }

    @Test fun sameChannelDuplicateDoesNotFireUpgradeOrStoreTwice() {
        val buffer = SqlLogEntryBuffer(10); var stored = 0; var upgrades = 0
        buffer.onEntryStored = { stored++ }; buffer.onEntryMerged = { _, _ -> upgrades++ }
        val e = entry(); buffer.addEntry(e); buffer.addEntry(e)
        assertEquals(1, stored); assertEquals(0, upgrades)
        assertEquals(1, buffer.dedupeCount(e.restoredSql))
        assertEquals(1, buffer.size())
    }

    @Test fun allReaderEntrypointsAgreeAndMalformedRevisionsDoNotCreateRows() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old); saver.submitMerge(old, merged(old)); saver.close()
        val file = File(dir, "test.sql")
        file.appendText("\n-- pd: o147-revision: invalid-id bad-hash !!! --\n" +
            "-- pd: o147-revision: " + "A".repeat(SqlLogFileReader.MAX_REVISION_PAYLOAD + 500) + " --\n")
        val loaded = SqlLogFileReader.readFromFile(file)
        val streamed = mutableListOf<LogSqlEntry>()
        SqlLogFileReader.forEachEntry(file) { streamed += it; true }
        assertEquals(1, SqlLogFileReader.countInFile(file)); assertEquals(1, loaded.size)
        assertEquals(loaded, streamed)
        assertEquals(loaded, SqlLogFileReader.parseContent(file.readText()))
        assertEquals(loaded, SqlLogFileReader.parseContent(file.readText().lineSequence().constrainOnce()))
        assertEquals(CaptureProvenance.MERGED, loaded.single().captureProvenance)
    }

    @Test fun changedOriginalSqlDoesNotReceiveRevisionMetadata() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old); saver.submitMerge(old, merged(old)); saver.close()
        val file = File(dir, "test.sql")
        file.writeText(file.readText().replace("SELECT 'synthetic';", "SELECT 'external';"))
        val loaded = SqlLogFileReader.readFromFile(file).single()
        assertEquals("SELECT 'external'", loaded.restoredSql)
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE, loaded.captureProvenance)
        assertNull(loaded.sourceFile)
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
        val list = SqlLogFileReader.readFromFile(file)
        assertEquals(1, list.size); assertEquals(CaptureProvenance.CONSOLE_BRIDGE, list.single().captureProvenance)
        assertEquals(list, SqlLogFileReader.parseContent(limited))
        val streamed = mutableListOf<LogSqlEntry>(); SqlLogFileReader.forEachEntry(file) { streamed += it; true }
        assertEquals(list, streamed); assertEquals(1, SqlLogFileReader.countInFile(file))
        file.writeText(original + revision.replace(Regex("[0-9a-f]{8}-[0-9a-f-]{27}"), "00000000-0000-0000-0000-000000000000") + "\n")
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE, SqlLogFileReader.readFromFile(file).single().captureProvenance)
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
        saver.submitMerge(old, merged(old).copy(rawSql = "SELECT 'wrong'")); saver.close()
        val read = SqlLogFileReader.readFromFile(File(dir, "test.sql")).single()
        assertEquals(old.rawSql, read.rawSql); assertEquals(CaptureProvenance.CONSOLE_BRIDGE, read.captureProvenance)
    }

    @Test fun mergeCallbackBeforeStoreCallbackStillPersistsOneMergedEntry() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val buffer = SqlLogEntryBuffer(10)
        val storedReached = java.util.concurrent.CountDownLatch(1)
        val releaseStore = java.util.concurrent.CountDownLatch(1)
        buffer.onEntryStored = {
            storedReached.countDown()
            check(releaseStore.await(10, java.util.concurrent.TimeUnit.SECONDS))
            saver.submit(it)
        }
        buffer.onEntryMerged = saver::submitMerge
        val old = entry()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val writer = Thread {
            try { buffer.addEntry(old) } catch (t: Throwable) { failure.set(t) }
        }
        writer.start()
        try {
            assertTrue(storedReached.await(10, java.util.concurrent.TimeUnit.SECONDS))
            buffer.addEntry(merged(old).copy(captureProvenance = CaptureProvenance.AGENT))
            // Deterministic drain without sleep: force the first merge to be consumed before store.
            val drain = SqlLogAutoSaver::class.java.getDeclaredMethod("drainAndWrite")
            drain.isAccessible = true
            drain.invoke(saver)
        } finally { releaseStore.countDown(); writer.join(10000) }
        failure.get()?.let { throw it }
        assertFalse(writer.isAlive)
        saver.close()
        val result = SqlLogFileReader.readFromFile(File(dir, "test.sql"))
        assertEquals(1, result.size)
        assertEquals(CaptureProvenance.MERGED, result.single().captureProvenance)
        assertEquals("Synthetic.java", result.single().sourceFile)
        assertEquals(1, buffer.dedupeCount(old.restoredSql))
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
            SqlLogFileReader.readFromFile(File(dir, "next.sql")).single().captureProvenance)
    }

    @Test fun reverseChainOfTwoUpgradesResolvesAfterOriginalStore() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); val first = merged(old); val last = first.copy(stackDigest = "Synthetic#call:12")
        val drain = SqlLogAutoSaver::class.java.getDeclaredMethod("drainAndWrite").apply { isAccessible = true }
        synchronized(saver) {
            saver.submitMerge(first, last); saver.submitMerge(old, first); drain.invoke(saver)
            saver.submit(old); saver.close()
        }
        val read = SqlLogFileReader.readFromFile(File(dir, "test.sql")).single()
        assertEquals(CaptureProvenance.MERGED, read.captureProvenance)
        assertEquals(last.stackDigest, read.stackDigest)
    }

    @Test fun expiredWaitingMergeDoesNotUpgradeLaterStore() {
        var now = 0L
        val dir = dir(); val saver = SqlLogAutoSaver(clock = { now }); saver.configure(dir.path, "test.sql", null)
        val old = entry()
        val drain = SqlLogAutoSaver::class.java.getDeclaredMethod("drainAndWrite").apply { isAccessible = true }
        synchronized(saver) {
            saver.submitMerge(old, merged(old)); drain.invoke(saver)
            now = 60001; saver.submit(old); saver.close()
        }
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE,
            SqlLogFileReader.readFromFile(File(dir, "test.sql")).single().captureProvenance)
    }

    @Test fun oversizedRevisionBudgetAgreesAcrossAllReaders() {
        val dir = dir(deferToClassEnd = true); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        // 此大预算夹具用已知耗时避免头部破折号触发整串 UTF-16 膨胀；Unicode 另有用例。
        val old = entry().copy(executionTimeMs = 0L); saver.write(old); saver.submitMerge(old, merged(old)); saver.close()
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
        val loaded = SqlLogFileReader.readFromFile(file)
        val streamed = mutableListOf<LogSqlEntry>()
        SqlLogFileReader.forEachEntry(file) { streamed += it; true }
        assertEquals(1, SqlLogFileReader.countInFile(file))
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE, loaded.single().captureProvenance)
        assertEquals(loaded, streamed)
        // Sequence form exercises the same counterexample without materializing a 129 MiB String.
        val sequenceResult = SqlLogFileReader.parseContent(
            original.lineSequence() + (0 until SqlLogFileReader.MAX_REVISIONS).asSequence().map { oversized.trimEnd() } + sequenceOf(revision),
        )
        assertEquals(loaded, sequenceResult)
        // JDK compact UTF-8 read avoids Kotlin readText's full UTF-16 intermediate on this 129 MiB fixture.
        registerLargeStringChildVerification(file, loaded, "oversized")
    }

    @Test fun exactRevisionLimitLfAgreesAcrossAllReaders() = assertRevisionLineBoundary("\n", 0)

    @Test fun exactRevisionLimitCrLfAgreesAcrossAllReaders() = assertRevisionLineBoundary("\r\n", 0)

    @Test fun overRevisionLimitLfAgreesAcrossAllReaders() = assertRevisionLineBoundary("\n", 1)

    @Test fun overRevisionLimitCrLfAgreesAcrossAllReaders() = assertRevisionLineBoundary("\r\n", 1)

    private fun assertRevisionLineBoundary(ending: String, excess: Int) {
        val dir = dir(deferToClassEnd = true); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        // ASCII-only header preserves the coordinator's compact-string fixture correction.
        val old = entry().copy(executionTimeMs = 0L)
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
        val fromSequence = SqlLogFileReader.parseContent(original.asSequence() +
            (0 until SqlLogFileReader.MAX_REVISIONS).asSequence().map { invalid } + sequenceOf(valid))
        val fromFile = SqlLogFileReader.readFromFile(file)
        val streamed = mutableListOf<LogSqlEntry>()
        SqlLogFileReader.forEachEntry(file) { streamed += it; true }
        assertEquals(1, SqlLogFileReader.countInFile(file))
        assertEquals(expected, fromSequence.single().captureProvenance)
        assertEquals(fromSequence, fromFile)
        assertEquals(fromSequence, streamed)
        registerLargeStringChildVerification(
            file, fromSequence,
            "${if (excess == 0) "exact" else "over"}-${if (ending == "\n") "lf" else "crlf"}",
        )
    }

    /**
     * T9：只登记，不 fork——原 [assertLargeStringInChild] 的 fork 逻辑整体迁往
     * [Companion.verifyLargeStringsInOneChildJvm]（一次子 JVM 验证全部登记文件）。
     * 父侧 expected 指纹在此算好，子侧逐文件比对，断言强度逐字不变。
     */
    private fun registerLargeStringChildVerification(file: File, expected: List<LogSqlEntry>, label: String) {
        val root = File(System.getProperty("user.dir"))
        check(File(File(root, "build/classes/kotlin/main"), "cn/song/mybatisplus/visualizer/log/SqlLogFileReader.class").isFile)
        check(File(File(root, "build/classes/kotlin/test"), "cn/song/mybatisplus/visualizer/log/O147LargeStringProbe.class").isFile)
        O147_CHILD_VERIFICATIONS += Triple(file, O147LargeStringProbe.fingerprint(expected), label)
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
        assertTrue(saver.containsExactLine(input, expected))
        assertFalse(saver.containsExactLine((expected + "x\n").reader(), expected))
        assertTrue(saver.containsExactLine(expected.reader(), expected))
    }

    @Test fun hugeMetadataAbortsEncodingBeforeTraversingRemainingPayload() {
        val saver = SqlLogAutoSaver()
        var visited = 0
        val candidates = object : AbstractList<Pair<String, Int>>() {
            override val size = 1_000_000
            override fun get(index: Int): Pair<String, Int> {
                visited++
                check(visited < 5000) { "serialized far beyond revision budget" }
                return "Synthetic".repeat(32) to index
            }
        }
        val output = SqlLogAutoSaver.RevisionOutput(SqlLogFileReader.MAX_REVISION_PAYLOAD)
        try {
            saver.writeRevision(merged(entry()).copy(sourceCandidates = candidates), output)
            fail("huge metadata must exceed bounded output")
        } catch (_: SqlLogAutoSaver.RevisionLimitExceeded) { }
        assertTrue(visited in 1..4999)
        assertTrue(output.size <= SqlLogFileReader.MAX_REVISION_PAYLOAD)
        val dir = dir(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old)
        val file = File(dir, "test.sql"); val original = file.readBytes()
        saver.submitMerge(old, merged(old).copy(stackDigest = "huge".repeat(1_000_000)))
        saver.close()
        assertArrayEquals(original, file.readBytes())
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE, SqlLogFileReader.readFromFile(file).single().captureProvenance)
    }

    @Test fun normalSaveDoesNotTruncateLargeSqlOrMetadata() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val sql = "SELECT '" + "synthetic".repeat(20000) + "'"
        val original = entry(sql).copy(stackDigest = "stack".repeat(20000))
        saver.write(original); saver.close()
        val read = SqlLogFileReader.readFromFile(File(dir, "test.sql")).single()
        assertEquals(sql, read.rawSql); assertEquals(sql, read.restoredSql)
        assertEquals(original.stackDigest, read.stackDigest)
    }

    @Test fun bareProvenanceWithoutParametersIsPersisted() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        saver.write(entry()); saver.close()
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE,
            SqlLogFileReader.readFromFile(File(dir, "test.sql")).single().captureProvenance)
    }

    companion object {
        /**
         * T9：类结束后一次 fork，逐文件验证 [O147_CHILD_VERIFICATIONS] 登记的全部大文件
         * （原五场景各 fork 一次 ≈16.5s，其中 8MiB 与 129MiB 夹具耗时几乎相同 ⇒ 大头是
         * Windows fork+classpath 而非解析；合并后省 4 次 fork）。断言逐字保留：任何文件
         * 指纹不符 ⇒ 子进程退出非 0 ⇒ 本方法失败；登记数必须恰为
         * [O147_EXPECTED_VERIFICATIONS]——场景用例早退（fixture 断言先红）时登记缺失在此
         * 二次显红，防「子验证静默缩水」。注意：单方法过滤运行本类会因登记不全在此显红，
         * 本类按整类跑（分片/门禁口径）。
         */
        @JvmStatic
        @org.junit.AfterClass
        fun verifyLargeStringsInOneChildJvm() {
            val pending = O147_CHILD_VERIFICATIONS.toList()
            O147_CHILD_VERIFICATIONS.clear()
            try {
                assertEquals(
                    "T9: 子 JVM 验证登记数应等于场景数（场景早退=登记缺失，整类运行口径见 KDoc）",
                    O147_EXPECTED_VERIFICATIONS, pending.size,
                )
                if (pending.isEmpty()) return
                val root = File(System.getProperty("user.dir"))
                val main = File(root, "build/classes/kotlin/main")
                val tests = File(root, "build/classes/kotlin/test")
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
                val output = File(root, "build/o147-string-child.log")
                val args = listOf(javaBin.path, "-Xmx768m",
                    // T-CiRelease（20261025 破案）：子进程必须继承父进程时区——头部时间字符串由父进程
                    // 按其默认时区（mpv-testing 钉死 Asia/Shanghai）格式化落盘，子进程按 runner 原生 UTC
                    // 解析回毫秒会整体 +8h（原像实证：timestamp=1724596690000 vs 夹具 1724567890000，
                    // 差恰 28,800,000ms），timestamp 字段不同 → 指纹必不匹配。透传 user.timezone 后
                    // 父子同区，任何机器构造性一致。
                    "-Duser.timezone=" + System.getProperty("user.timezone"),
                    "-cp", (listOf(main, tests) + dependencies).joinToString(File.pathSeparator),
                    O147LargeStringProbe::class.java.name) +
                    pending.flatMap { (file, fingerprint, label) -> listOf(file.path, fingerprint, label) }
                File(root, "build/o147-string-child-command.txt").writeText(args.joinToString("\n"))
                val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output).start()
                try {
                    // 原单文件子进程超时 90s；单 JVM 顺序解析全部登记文件（含 129MiB 大夹具），放宽到 300s。
                    check(process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) { "String child timeout: $output" }
                    val childOutput = output.readText()
                    if (process.exitValue() != 0) {
                        // T-CiRelease（20261024 诊断自曝）：失败时原样打印子进程输出与完整命令行，终结盲猜。
                        println("=== O147 string-child FAILED exit=${process.exitValue()} ===")
                        println(childOutput)
                        println("=== command ===")
                        println(File(root, "build/o147-string-child-command.txt").readText())
                        // T-CiRelease（20261025）：把原始 test.sql 与命令行拷进 build/o147-diag——
                        // 失败工件上传后可拿到 runner 上的真实文件字节，供本地逐字节 diff。
                        runCatching {
                            val diagDir = File(root, "build/o147-diag")
                            diagDir.mkdirs()
                            pending.forEach { (file, _, label) ->
                                file.copyTo(File(diagDir, "$label-${file.name}"), overwrite = true)
                            }
                            File(root, "build/o147-string-child-command.txt")
                                .copyTo(File(diagDir, "string-child-command.txt"), overwrite = true)
                        }.onFailure { println("o147-diag copy failed: $it") }
                        println("=== end O147 string-child diag ===")
                    }
                    assertEquals(childOutput, 0, process.exitValue())
                    assertTrue(childOutput.contains("O147_STRING_OK"))
                } finally { if (process.isAlive) process.destroyForcibly() }
            } finally {
                // 大夹具目录延后到子验证结束再清理（原 @After 时机，T9 改类级；券05 的防泄漏语义不变）。
                O147_DEFERRED_DIRS.forEach { runCatching { it.deleteRecursively() } }
                O147_DEFERRED_DIRS.clear()
            }
        }
    }
}

/** Runs the production String parser in one owned JVM; no platform stubs or weakened budget. */
object O147LargeStringProbe {
    /**
     * T9：参数为 (文件路径, 期望指纹, 标签) 三元组序列——一次 JVM 逐文件验证（原=单文件单 fork，
     * fork 由测试侧合并，见 [O147HistoryUpgradeTest] 顶部 KDoc）。任何文件指纹不符即整体退出非 0。
     */
    @JvmStatic fun main(args: Array<String>) {
        // T-CiRelease（20261024）：与父进程同口径读文件（JVM 默认字符集）——父侧 saver/readLines/
        // bufferedWriter/生产读取器整条链走默认字符集（本机 UTF-8、EN-US runner 为 Cp1252，单机内自洽），
        // 此处原 Files.readString 强制 UTF-8，在默认字符集非 UTF-8 的机器上把文件头的 em dash
        //（time: —）解码成替换符 → 指纹不匹配 → 子进程退出 1（CI 5/5 确定性失败实证）。
        // T-CiRelease（20261025）：字符集/时区组合本地全试尽仍无法复现指纹分歧——失败时把哈希
        // 原像（entries.toString()，count=1 体量很小）完整打出，供与父侧期望原像逐字段对比。
        require(args.size >= 3 && args.size % 3 == 0) {
            "expected (file,fingerprint,label) triplets, got ${args.size} args"
        }
        var failed = 0
        for (i in args.indices step 3) {
            val path = args[i]; val expectedHash = args[i + 1]; val label = args[i + 2]
            val entries = SqlLogFileReader.parseContent(File(path).readText())
            val actual = fingerprint(entries)
            println("O147_STRING_FILE=$label ok=${actual == expectedHash} count=${entries.size}")
            if (actual != expectedHash) {
                failed++
                println("O147_CHILD_PREIMAGE[$label]=" + entries)
                println("O147_CHILD_HASH[$label]=" + actual)
                println("O147_EXPECTED_HASH[$label]=" + expectedHash)
            }
        }
        check(failed == 0) { "String parser differs from parent sequence/file results in $failed/${args.size / 3} file(s)" }
        println("O147_STRING_OK files=${args.size / 3} reader=${SqlLogFileReader::class.java.protectionDomain.codeSource.location}")
    }

    fun fingerprint(entries: List<LogSqlEntry>): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(entries.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
