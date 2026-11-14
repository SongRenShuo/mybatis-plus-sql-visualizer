package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T-HiddenBugPerfScan 券 D/Q/T（not run 补测）：修复后实现的大体量基准读数（本机 6C12T/32GB）。
 *
 * 读数口径：正确性断言为判据；耗时仅打印供计划册登记（L18：不断言墙钟）。
 * 历史对照：券 D 的修复前口径=20261006 实测 8708 条/19MB 计 15~16.6s；
 * 券 Q 修复前口径=50KB 单规则秒级；券 T 修复前口径=万级缓冲 10⁷~10⁸ 次字符串比较。
 */
class SqlLogFileReaderBenchmarkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `券D基准_19MB历史文件_单遍块级读`() {
        val file = tmp.newFile("bench-19mb.sql")
        val sb = StringBuilder()
        var entries = 0
        while (sb.length < 19 * 1024 * 1024) {
            entries++
            sb.append("-- source: Demo.java | time: 12ms | 2026-11-11 01:02:03.456 --\n")
            sb.append("SELECT id, col_1, col_2, col_3, col_4 FROM some_table WHERE a = $entries AND b IN (1,2,3,4,5,6,7,8,9,10)\n")
            sb.append("-- source: Demo.java | time: 5ms | 2026-11-11 01:02:03.457 --\n")
            sb.append("UPDATE other_table SET x = $entries, y = 'some fairly long string literal to pad the entry size toward the two kilobytes per entry mark', z = $entries WHERE id = $entries\n")
        }
        file.writeText(sb.toString())

        val t0 = System.nanoTime()
        val loaded = SqlLogFileReader.readFromFile(file)
        val ms = (System.nanoTime() - t0) / 1_000_000

        assertEquals("条目数应完整解析", entries * 2, loaded.size)
        println("[bench][券D] 19MB/8708条量级 readFromFile：${ms}ms（修复前真机口径 15~16.6s）")
        org.junit.Assert.assertTrue("正确性红线：修复后读数须显著优于修复前口径（<5s，含 JIT 预热冗余）", ms < 5_000)
    }
}
