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
 * **D237（2026-11-19）**：戳登记已与去重开关**解耦**——此前只在「去重开启 + 可去重」路径，
 * 关掉去重即让 N+1 徽标与 D235 聚合全链静默失效（用户真机验收发现）。现无条件登记，
 * [test去重关闭_同线程窗内重复仍判嫌疑] 与 [test不可去重来源_仍判嫌疑] 钉住这条。
 *
 * 反向验证锚点：**把 addEntryInternal 的 markWindowStampLocked(added) 移回
 * `if (dedupeEnabled && canDedupe(added))` 分支内**，上面两条新用例必红（去重关闭与
 * ShardingSphere 两条路径戳为空 → windowSuspectKeys 恒空集），而原有同线程用例仍绿
 * （它走去重开启路径）——探针精度可据此判读。
 *
 * **T-PerfSweep33 券02（2026-11-20）**：markWindowStampLocked 加双帽兜底（单键 64 / 键数 1024，
 * 面板长期不开时防无界累积）。反向锚点＝**摘掉双帽**：[test同键戳超帽_只留最近段_窗内检测不丢]
 * （戳数 100 > 64 必红）与 [test键数超帽_整体清空_新戳照常登记]（键数 1100 > 1024 必红）。
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

    /** D237 U1：去重**关闭**时窗口戳照常登记——N+1 是诊断能力，不该被展示偏好关掉。 */
    @Test
    fun `去重关闭_同线程窗内重复仍判嫌疑`() {
        val b = SqlLogEntryBuffer(maxEntries = 100)
        b.disableDedupe()
        assertFalse("前置：去重应已关闭", b.dedupeEnabled)
        for (i in 1..5) b.addEntry(entryAt(TEMPLATE, i, 1_000L + i, "http-nio"))
        val keys = b.windowSuspectKeys(nowMs = 2_000L, windowMs = 60_000L, threshold = 5)
        assertTrue(
            "去重关闭后同线程窗内 5 条同模板仍应判嫌疑（D237 解耦）：$keys",
            keys.contains(entryAt(TEMPLATE, 1, 1_000L, "http-nio").structureKey),
        )
    }

    /** D237 U4：ShardingSphere 来源（canDedupe=false）同样纳入 N+1 判定。 */
    @Test
    fun `不可去重来源_仍判嫌疑`() {
        val b = SqlLogEntryBuffer(maxEntries = 100)
        for (i in 1..5) {
            b.addEntry(
                LogSqlEntry(
                    rawSql = TEMPLATE.replace("?", "$i"),
                    restoredSql = TEMPLATE.replace("?", "$i"),
                    params = emptyList(),
                    timestamp = 1_000L + i,
                    threadName = "http-nio",
                    source = SqlSource.SHARDINGSPHERE,
                ),
            )
        }
        val keys = b.windowSuspectKeys(nowMs = 2_000L, windowMs = 60_000L, threshold = 5)
        assertTrue(
            "ShardingSphere 来源条目也应参与 N+1 判定（判定与去重能力无关）：$keys",
            keys.contains(entryAt(TEMPLATE, 1, 1_000L, "http-nio").structureKey),
        )
    }

    // ======================== T-PerfSweep33 券02：双帽兜底 ========================

    /** 单键戳超帽（64）：丢最旧保最近段——窗内检测能力不丢（阈值 5 ≪ 帽），且戳数被帽住。 */
    @Test
    fun `同键戳超帽_只留最近段_窗内检测不丢`() {
        val b = SqlLogEntryBuffer(maxEntries = 200)
        // 100 条同模板异字面量（dedupeKey 互异不走去重丢弃；structureKey 相同 → 单键 100 枚 > 帽 64）
        for (i in 1..100) b.addEntry(entryAt(TEMPLATE, i, 1_000L + i, "http-nio"))
        val shape = b.windowStampShapeForTest()
        assertTrue("单键戳队列应被帽住（≤64，现 ${shape.second}）", shape.second <= 64)
        val keys = b.windowSuspectKeys(nowMs = 2_000L, windowMs = 60_000L, threshold = 5)
        assertTrue(
            "帽内保留的是最近段——同线程窗内远超阈值仍应判嫌疑：$keys",
            keys.contains(entryAt(TEMPLATE, 1, 1_000L, "http-nio").structureKey),
        )
    }

    /** 键数超帽（1024）：整体清空一次（当窗精度损失可接受），清空后新戳照常登记参与判定。 */
    @Test
    fun `键数超帽_整体清空_新戳照常登记`() {
        val b = SqlLogEntryBuffer(maxEntries = 2000)
        // 1100 个不同模板（structureKey 各异 → 1100 键 > 键帽 1024）
        for (i in 1..1100) b.addEntry(entryAt("SELECT * FROM t_$i WHERE id = ?", 1, 1_000L + i, "http-nio"))
        val shape = b.windowStampShapeForTest()
        assertTrue("键数应被帽住（≤1024，现 ${shape.first}）", shape.first <= 1024)
        // 清空后的新戳照常登记：窗内 5 条同模板仍判嫌疑
        for (i in 1..5) b.addEntry(entryAt(TEMPLATE, i, 1_100_000L + i, "http-nio"))
        val keys = b.windowSuspectKeys(nowMs = 1_101_000L, windowMs = 60_000L, threshold = 5)
        assertTrue(
            "键数帽清空后新戳应照常参与嫌疑判定：$keys",
            keys.contains(entryAt(TEMPLATE, 1, 1_100_000L, "http-nio").structureKey),
        )
    }
}
