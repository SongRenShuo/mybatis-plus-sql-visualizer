package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HdAbsorb D216 第二刀收口：`SqlLogEntryBuffer.windowSuspectKeys` 窗口嫌疑键集（生产接线）。
 *
 * 口径：同 structureKey 的条目按**线程**分桶，窗内任一线程桶计数 ≥ 阈值即嫌疑；
 * null 线程（控制台回落通道）单桶参与（会话级回落口径）；窗外戳读取时修剪；clear 全量复位。
 * 戳登记只走「去重开启 + 可去重」成功路径——同模板不同字面量（N+1 形态）dedupeKey 互异、
 * structureKey 相同，恰是嫌疑面；完全同文重复走去重丢弃不重复埋戳（⨉N 徽章职责）。
 *
 * 反向验证锚点：**摘掉 addEntryInternal 成功路径的 markWindowStampLocked(added) 一行，
 * [test同线程窗内重复_判嫌疑] 与 [testnull线程回落口径] 必红**（windowSuspectKeys 恒空集）。
 */
class NplusOneWindowBufferTest {

    private fun entryAt(template: String, n: Int, ts: Long, thread: String?) = LogSqlEntry(
        rawSql = template.replace("?", "$n"),
        restoredSql = template.replace("?", "$n"),
        params = emptyList(),
        timestamp = ts,
        threadName = thread,
    )

    private val TEMPLATE = "SELECT * FROM t_user WHERE id = ?"

    @Test
    fun `同线程窗内重复_判嫌疑`() {
        val b = SqlLogEntryBuffer(maxEntries = 100)
        // 同线程 5 条同模板异字面量（N+1 形态：dedupeKey 互异、structureKey 相同）
        for (i in 1..5) b.addEntry(entryAt(TEMPLATE, i, 1_000L + i, "http-nio"))
        val keys = b.windowSuspectKeys(nowMs = 2_000L, windowMs = 60_000L, threshold = 5)
        assertTrue(
            "同线程窗内 5 条同模板应判嫌疑：$keys",
            keys.contains(entryAt(TEMPLATE, 1, 1_000L, "http-nio").structureKey),
        )
    }

    @Test
    fun `异线程分桶_各自不足阈值不嫌疑`() {
        val b = SqlLogEntryBuffer(maxEntries = 100)
        // 5 条同模板分散在 5 个线程（每桶 1 条）——会话级累计 5 达第一刀阈值，窗口判定必须不嫌疑
        for (i in 1..5) b.addEntry(entryAt(TEMPLATE, i, 1_000L + i, "thread-$i"))
        val keys = b.windowSuspectKeys(nowMs = 2_000L, windowMs = 60_000L, threshold = 5)
        assertFalse(
            "异线程分桶后任一桶都不足阈值（这正是第二刀收紧误报的价值）：$keys",
            keys.contains(entryAt(TEMPLATE, 1, 1_000L, "thread-1").structureKey),
        )
    }

    @Test
    fun `窗外条目_修剪后不嫌疑`() {
        val b = SqlLogEntryBuffer(maxEntries = 100)
        // 5 条 10 分钟前的旧戳（窗口 60s）→ 修剪后为空
        for (i in 1..5) b.addEntry(entryAt(TEMPLATE, i, 1_000L + i, "http-nio"))
        val keys = b.windowSuspectKeys(nowMs = 1_000_000L, windowMs = 60_000L, threshold = 5)
        assertFalse("窗外戳应被修剪：$keys", keys.isNotEmpty())
    }

    @Test
    fun `null线程回落口径_达阈值判嫌疑`() {
        val b = SqlLogEntryBuffer(maxEntries = 100)
        // 控制台回落通道条目无线程名——单桶参与（会话级回落），达阈值判嫌疑
        for (i in 1..5) b.addEntry(entryAt(TEMPLATE, i, 1_000L + i, null))
        val keys = b.windowSuspectKeys(nowMs = 2_000L, windowMs = 60_000L, threshold = 5)
        assertTrue(
            "null 线程单桶应参与判定（回落口径）：$keys",
            keys.contains(entryAt(TEMPLATE, 1, 1_000L, null).structureKey),
        )
    }

    @Test
    fun `clear_复位窗口嫌疑`() {
        val b = SqlLogEntryBuffer(maxEntries = 100)
        for (i in 1..5) b.addEntry(entryAt(TEMPLATE, i, 1_000L + i, "http-nio"))
        b.clear()
        val keys = b.windowSuspectKeys(nowMs = 2_000L, windowMs = 60_000L, threshold = 5)
        assertFalse("clear 后嫌疑集应复位：$keys", keys.isNotEmpty())
    }

    @Test
    fun `同文重复_走去重丢弃不重复埋戳`() {
        val b = SqlLogEntryBuffer(maxEntries = 100)
        // 完全同文 5 条（dedupeKey 相同）：首条埋戳、其余走去重丢弃——单桶计数 1，不嫌疑
        // （同文重复的计数职责在 ⨉N 徽章；N+1 嫌疑面是「同模板异字面量」）
        for (i in 1..5) b.addEntry(LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            timestamp = 1_000L + i,
            threadName = "http-nio",
        ))
        val keys = b.windowSuspectKeys(nowMs = 2_000L, windowMs = 60_000L, threshold = 5)
        assertFalse("同文重复不应灌爆窗口桶（去重丢弃路径不埋戳）：$keys", keys.isNotEmpty())
    }
}
