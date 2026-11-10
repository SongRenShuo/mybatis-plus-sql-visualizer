package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 T：dedupeExisting O(n²)→O(n) 回归。
 *
 * 手动裁剪曾对每条在队条目 firstOrNull 线性扫 keep（n 条则 n²/2 次 dedupeKey
 * 字符串比较），全程持有 entries 监视器——万级缓冲点「移除重复的sql」冻结 EDT
 * 并阻塞采集线程。修复后用本地键集 O(1) 判重，语义等价（键集 = keep 中可判重
 * 条目的 dedupeKey 集合，与 firstOrNull 存在性判定等价）。
 */
class SqlLogEntryBufferDedupeExistingScaleTest {

    private fun entry(sql: String, ts: Long) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
        source = SqlSource.MYBATIS,
    )

    @Test
    fun `混合重复裁剪_语义等价_保留最早一条`() {
        val buf = SqlLogEntryBuffer(maxEntries = 100_000)
        // 1000 键 × 3 副本 = 3000 条；各轮时间戳拉开 120s（超出运行期去重窗口 60s，
        // add 时不折叠——手动裁剪正是跨窗语义）；同键保留最早，去重后应剩 1000
        for (round in 0 until 3) {
            val base = round * 120_000L
            for (k in 0 until 1000) {
                buf.addEntry(entry("SELECT * FROM t$k WHERE id = ?", base + k))
            }
        }
        buf.dedupeEnabled = true
        val removed = buf.dedupeExisting()
        assertEquals("应移除 2000 条重复", 2000, removed)
        assertEquals("应保留 1000 条", 1000, buf.snapshot().size)
    }

    @Test
    fun `无重复时零移除`() {
        val buf = SqlLogEntryBuffer(maxEntries = 100_000)
        var ts = 0L
        for (k in 0 until 500) {
            buf.addEntry(entry("SELECT DISTINCT_SQL_$k", ts++))
        }
        buf.dedupeEnabled = true
        assertEquals(0, buf.dedupeExisting())
        assertEquals(500, buf.snapshot().size)
    }
}
