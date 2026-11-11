package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.sql.perf.PerfContext
import cn.song.mybatisplus.visualizer.sql.perf.PerfRuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 Q/T（not run 补测）：修复后实现的大体量基准读数。
 *
 * 耗时仅打印供计划册登记（L18）；正确性断言为判据。
 * 历史对照：券 Q 修复前=50KB 单规则秒级（substring(i) O(n²)）；券 T 修复前=万级缓冲
 * dedupeExisting 10⁷~10⁸ 次 dedupeKey 比较（EDT 冻结）。
 */
class TopLevelKeywordBenchmarkTest {

    @Test
    fun `券Q基准_50KB大SQL性能分析`() {
        // 分页长 IN 清单形态：50KB + ORDER BY + UPDATE（MissingWhere/OrderByWithoutLimit 走顶格扫描）
        val inList = (1..1200).joinToString(",") { "'value-$it-/**/pad/**/to/extend/length-xxxxxxxxxxxxxxxx'" }
        val sql = "UPDATE t SET a = 1 WHERE id IN ($inList) ORDER BY id"
        assertTrue("fixture 应达 50KB 量级：${sql.length}", sql.length > 45_000)

        // 预热一次（JIT），再计时三轮取均值口径
        val t0 = System.nanoTime()
        repeat(3) { PerfRuleEngine.apply(PerfContext(sql = sql, ast = null, dialect = null)) }
        val ms = (System.nanoTime() - t0) / 1_000_000 / 3

        println("[bench][券Q] 50KB SQL 顶格关键字规则三轮均值：${ms}ms（修复前口径=单规则秒级）")
        assertTrue("修复后须显著优于秒级口径（<1s，含 JIT 冗余）", ms < 1_000)
    }

    @Test
    fun `券T基准_12000条缓冲手动去重`() {
        val buf = cn.song.mybatisplus.visualizer.log.SqlLogEntryBuffer(maxEntries = 100_000)
        var ts = 0L
        for (round in 0 until 3) {
            val base = round * 120_000L
            for (k in 0 until 4000) {
                buf.addEntry(
                    cn.song.mybatisplus.visualizer.log.LogSqlEntry(
                        rawSql = "SELECT * FROM big_table_$k WHERE id = ?",
                        restoredSql = "SELECT * FROM big_table_$k WHERE id = ?",
                        params = emptyList(),
                        timestamp = base + k,
                    ),
                )
            }
        }
        buf.dedupeEnabled = true
        val t0 = System.nanoTime()
        val removed = buf.dedupeExisting()
        val ms = (System.nanoTime() - t0) / 1_000_000

        assertEquals("跨窗重复应裁掉 8000", 8000, removed)
        println("[bench][券T] 12000 条 dedupeExisting：${ms}ms（修复前口径=10⁷~10⁸ 次字符串比较）")
        assertTrue("修复后须显著优于 O(n²) 口径（<1s，含 JIT 冗余）", ms < 1_000)
    }
}
