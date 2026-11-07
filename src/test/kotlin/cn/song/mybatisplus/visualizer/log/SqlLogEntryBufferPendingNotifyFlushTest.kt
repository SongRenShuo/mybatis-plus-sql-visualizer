package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SqlListCatchup 券B2：悬挂节流余量的过期冲洗守卫（buffer 侧）。
 *
 * 缺陷形态：pendingContentNotify 只在「通知触发或 clear」归零——风暴末尾末批变更不足
 * CONTENT_NOTIFY_BATCH=20 且发生在距上次通知 200ms 内，之后无新条目则再无触发源，
 * 这几条变更悬挂（UI 不刷新、追赶徽章滞留「落后 N 条」）直到下一条 SQL 或 clear。
 *
 * 时间注入缝（别赌墙钟）：[SqlLogEntryBuffer.nowNanosForTest] 注入固定时钟，节流时间戳与
 * 悬挂判定全部确定性可推演。
 */
class SqlLogEntryBufferPendingNotifyFlushTest {

    private fun entryAt(sql: String, ts: Long) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
    )

    /** 基准时钟（1s 刻度）：first add 的 now-last(0) 必然超窗即时通知，last=BASE。 */
    private val baseNanos = 1_000_000_000L

    @Test
    fun `悬挂态构造_pending不足批量且时间窗未过_hasStale为假`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        b.nowNanosForTest = baseNanos
        b.addEntry(entryAt("SELECT 1", 0L)) // 首条即时通知，pending 归零、last=BASE
        assertEquals(0, b.pendingNotifyCount())
        // 窗内（+50ms < 200ms）再灌 3 条（不足批量阈值 20）→ 全部抑制，pending=3
        b.nowNanosForTest = baseNanos + 50_000_000L
        repeat(3) { b.addEntry(entryAt("SELECT ${it + 2}", it.toLong() + 1L)) }
        assertEquals(3, b.pendingNotifyCount())
        assertFalse("时间窗未过 → 非悬挂态（不得提前冲洗）", b.hasStalePendingNotify())
    }

    @Test
    fun `时间推过窗口_hasStale为真_flush归零且listener恰一次_pending0时no-op`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        var listenerCalls = 0
        b.addListener { listenerCalls++ }
        b.nowNanosForTest = baseNanos
        b.addEntry(entryAt("SELECT 1", 0L)) // 通知 #1
        assertEquals(1, listenerCalls)
        b.nowNanosForTest = baseNanos + 50_000_000L
        repeat(3) { b.addEntry(entryAt("SELECT ${it + 2}", it.toLong() + 1L)) } // 全抑制，pending=3
        // 距上次通知 150ms：仍未过窗
        b.nowNanosForTest = baseNanos + 150_000_000L
        assertFalse(b.hasStalePendingNotify())
        // 累计 210ms > 200ms：悬挂态成立（此后无新条目则永远无人触发）
        b.nowNanosForTest = baseNanos + 210_000_000L
        assertTrue(b.hasStalePendingNotify())
        b.flushPendingNotify()
        assertEquals("listener 恰被冲洗调 1 次", 2, listenerCalls)
        assertEquals("消费即清", 0, b.pendingNotifyCount())
        assertFalse("冲洗后非悬挂态", b.hasStalePendingNotify())
        // pending=0 时冲洗 no-op：不触发 listeners
        b.flushPendingNotify()
        assertEquals(2, listenerCalls)
    }

    @Test
    fun `flush后新条目走既有双阈值路径_窗内抑制不重_超窗即时补发不丢`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        var listenerCalls = 0
        b.addListener { listenerCalls++ }
        b.nowNanosForTest = baseNanos
        b.addEntry(entryAt("SELECT 1", 0L)) // 通知 #1
        b.nowNanosForTest = baseNanos + 50_000_000L
        b.addEntry(entryAt("SELECT 2", 1L)) // 抑制，pending=1
        // 时间推过窗口 → 悬挂态 → 冲洗（通知 #2，时间戳刷新 = last=此刻）
        b.nowNanosForTest = baseNanos + 210_000_000L
        assertTrue(b.hasStalePendingNotify())
        b.flushPendingNotify()
        assertEquals(2, listenerCalls)
        // 冲洗后窗内（+50ms）新条目：抑制，不重复通知
        b.nowNanosForTest = baseNanos + 260_000_000L
        b.addEntry(entryAt("SELECT 3", 2L))
        assertEquals("窗内新条目被抑制，冲洗不造成重复通知", 2, listenerCalls)
        assertEquals(1, b.pendingNotifyCount())
        // 再推过窗口：该条由阈值路径即时补发（悬挂余量一并归零）——不丢
        b.nowNanosForTest = baseNanos + 420_000_000L
        b.addEntry(entryAt("SELECT 4", 3L))
        assertEquals("超窗新条目即时通知，此前悬挂余量一并补发不丢", 3, listenerCalls)
        assertEquals(0, b.pendingNotifyCount())
        assertFalse(b.hasStalePendingNotify())
    }

    @Test
    fun `clear 复位悬挂余量后_hasStale恒假且不误冲洗`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        var listenerCalls = 0
        b.addListener { listenerCalls++ }
        b.nowNanosForTest = baseNanos
        b.addEntry(entryAt("SELECT 1", 0L)) // 通知 #1
        b.nowNanosForTest = baseNanos + 50_000_000L
        b.addEntry(entryAt("SELECT 2", 1L)) // 抑制，pending=1
        b.nowNanosForTest = baseNanos + 210_000_000L
        assertTrue(b.hasStalePendingNotify())
        b.clear() // 既有口径：clear 作废悬挂节流余量
        assertFalse(b.hasStalePendingNotify())
        // clear 自身走 notifyChanged 直通（不走节流）：listener #2 是 clear 的，不是冲洗的
        assertEquals(2, listenerCalls)
        b.flushPendingNotify()
        assertEquals("pending=0 时冲洗 no-op，不触发 listeners", 2, listenerCalls)
    }
}
