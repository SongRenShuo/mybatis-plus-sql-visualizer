package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T-HiddenBugPerfScan 券 D：历史文件读取单遍化回归。
 *
 * readFromFile 曾对同一文件开两遍流（revisionsIn 全文一遍提修订 + parseContent 再一遍），
 * 且 boundedLines 逐字符 reader.read() 推进——100MB 滚动阈值文件 ≈ 2 亿次逐字符虚调用，
 * 是 20261006 实测 15~16.6s/19MB 历史加载热路径的最慢形态。修复后：修订收集并入解析
 * 单遍（parseContent(collectRevisions=true) 的 apply 在行循环后才执行，与双遍同语义），
 * boundedLines 改块级读。语义由既有 SqlLogFileReaderTest（31 例）/ SqlLogHistoryRoundTripTest /
 * O147HistoryUpgradeTest（含超限折叠）钉住；本类只断言「打开次数」结构缝（反向探锚：
 * 摘掉单遍化修复退回双遍即红）。
 */
class SqlLogFileReaderSinglePassTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeTwoEntryFile(): java.io.File {
        val file = tmp.newFile("history.sql")
        file.writeText(
            "-- source: Demo.java | time: 12ms | 2026-11-11 01:02:03.456 --\n" +
                "SELECT * FROM user WHERE id = 1\n" +
                "-- source: Demo.java | time: 5ms | 2026-11-11 01:02:04.456 --\n" +
                "UPDATE user SET name = 'a' WHERE id = 2\n",
        )
        return file
    }

    @Test
    fun `readFromFile 单遍——文件只打开一次`() {
        val file = writeTwoEntryFile()
        val before = SqlLogFileReader.fileOpenCount.get()
        val entries = SqlLogFileReader.readFromFile(file)
        val delta = SqlLogFileReader.fileOpenCount.get() - before
        assertEquals("两个条目都应解析出来", 2, entries.size)
        assertEquals("readFromFile 应只打开文件一次（双遍形态为 2）", 1, delta)
    }

    @Test
    fun `readFromFile 与 forEachEntry 条目等价_语义不随单遍化漂移`() {
        val file = writeTwoEntryFile()
        val entries = SqlLogFileReader.readFromFile(file)
        val streamed = mutableListOf<LogSqlEntry>()
        SqlLogFileReader.forEachEntry(file) { e ->
            streamed += e
            true
        }
        assertEquals("条目数一致", entries.size, streamed.size)
        entries.forEachIndexed { i, e ->
            assertEquals("rawSql 逐条一致", e.rawSql, streamed[i].rawSql)
            assertEquals("序号逐条一致", e.sequenceNumber, streamed[i].sequenceNumber)
        }
        assertTrue(entries.isNotEmpty())
    }
}
