package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * T-SqlListCatchup 券B：计数累积器（[CountSnapshotAccumulator]）与「每拍全量重算」的
 * **口径等价性**反向验证（等价性是本券命门——跨通道折叠 / dedupeIndex 重建 / 悬挂节流余量
 * 的历史 bug 家族全在状态机）。
 *
 * 等价性判据：任意操作序列（add / removeEntry / 淘汰 / clear / 去重开关翻转 / 接管沿重建 /
 * 手动裁剪 / 键上限溢出混合）下，「增量累积 map」与「每拍全量 dedupeCountsSnapshot() /
 * structureKeyCounts(snapshot)」**逐键逐值相等**。面板一拍的消费顺序（先 drain 变更键、
 * 后 drain 全量标记）与生产 [cn.song.mybatisplus.visualizer.log.SqlLogPanel.refreshCountMaps] 逐字一致。
 */
class SqlLogCountAccumulatorEquivalenceTest {

    private fun entryAt(sql: String, ts: Long) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
    )

    private fun newAcc(buffer: SqlLogEntryBuffer) = CountSnapshotAccumulator(
        dropCountsSnapshot = { buffer.dedupeCountsSnapshot() },
        dropCountForKey = { buffer.dedupeCountForKey(it) },
        presenceForKey = { buffer.dedupePresenceForKey(it) },
        structureKeyForKey = { buffer.structureKeyForKey(it) },
    )

    /**
     * 面板一拍的模拟：先 drain 变更键、后 drain 全量标记，应用后与全量口径逐键逐值比对。
     * 返回（变更键集, 全量标记）供调用方断言 drain 语义——调用方不得自行预 drain（否则本函数
     * drain 到空集走 no-op，等价性断言失真）。
     */
    private fun tickAndAssert(
        buffer: SqlLogEntryBuffer,
        acc: CountSnapshotAccumulator,
        context: String,
    ): Pair<Set<String>, Boolean> {
        val snapshot = buffer.snapshot()
        val changed = buffer.drainChangedKeys()
        val full = buffer.drainCountRebuildFlag()
        acc.apply(snapshot, full, changed)
        assertEquals("$context ⨉N 徽章累积 ≠ 全量快照", buffer.dedupeCountsSnapshot(), acc.currentDedupeCounts)
        assertEquals("$context 同结构徽章累积 ≠ 全量重算", structureKeyCounts(snapshot), acc.currentTemplateCounts)
        return changed to full
    }

    @Test
    fun `随机操作序列下增量累积与每拍全量重算逐键逐值相等`() {
        val seeds = listOf(1L, 42L, 20261107L)
        val sqlPool = listOf(
            "SELECT * FROM user WHERE id = 1",
            "SELECT * FROM user WHERE id = 2", // 与上一条同结构异 dedupeKey（字面量参数化后同形状）
            "UPDATE orders SET status = 'PAID' WHERE id = 3",
            "DELETE FROM logs WHERE ts < 100",
            "INSERT INTO audit(id, name) VALUES (1, 'a')",
            "SELECT count(*) FROM orders",
            "SELECT count(*) FROM users",
        )
        for (seed in seeds) {
            val rng = Random(seed)
            val buffer = SqlLogEntryBuffer(maxEntries = 8)
            val acc = newAcc(buffer)
            var clock = 0L
            var pendingOps = 0
            repeat(600) { op ->
                clock += rng.nextInt(2).toLong() // 小步进：60s 去重窗内频繁命中（丢弃/融合路径持续被覆盖）
                when (rng.nextInt(100)) {
                    in 0..54 -> buffer.addEntry(entryAt(sqlPool[rng.nextInt(sqlPool.size)], clock))
                    in 55..64 -> {
                        val snap = buffer.snapshot()
                        if (snap.isNotEmpty()) buffer.removeEntry(snap[rng.nextInt(snap.size)])
                    }
                    in 65..69 -> buffer.dedupeEnabled = rng.nextBoolean() // 开关翻转（含同值 no-op）
                    in 70..71 -> buffer.clear()
                    in 72..75 -> buffer.dedupeExisting()
                    in 76..79 -> buffer.maxEntries = 3 + rng.nextInt(8) // 动态上限 → 触发淘汰
                    in 80..81 -> buffer.rebuildDedupeIndexAfterHandoff(nowMs = clock + 10_000)
                    in 82..83 -> buffer.resetDedupeCounts()
                    in 84..85 -> buffer.bindErrorToRecent("java.sql.SQLException: boom") // 计数不变的变更
                    else -> { /* 空转 */ }
                }
                // 每 1~3 拍操作模拟一次面板刷新（节流窗内累积多拍变更后一次 drain）
                if (++pendingOps >= 1 + rng.nextInt(3)) {
                    pendingOps = 0
                    tickAndAssert(buffer, acc, "seed=$seed op#$op")
                }
            }
            tickAndAssert(buffer, acc, "seed=$seed final") // 收尾再对账一次，防悬挂余量
        }
    }

    @Test
    fun `淘汰路径_挤出条目后键计数归零或减一且drain含被淘汰键`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 3)
        val acc = newAcc(buffer)
        val sqls = listOf("SELECT 1", "SELECT 2", "SELECT 3", "SELECT 4", "SELECT 5")
        val drainedKeys = mutableListOf<String>()
        sqls.forEachIndexed { i, sql ->
            assertTrue(buffer.addEntry(entryAt(sql, i.toLong())))
            drainedKeys += tickAndAssert(buffer, acc, "add#$i").first
        }
        // maxEntries=3：SELECT 1 / SELECT 2 已被挤出，其键必须出现在 drain 集合里
        val evictedKeys = sqls.take(2).map { entryAt(it, 0L).dedupeKey }
        assertTrue("drain 集合包含被淘汰键", drainedKeys.containsAll(evictedKeys))
        assertEquals(listOf("SELECT 3", "SELECT 4", "SELECT 5"), buffer.snapshot().map { it.restoredSql })
        // 被淘汰键在队列里的计数归零（presence 出域），全量口径里也不再出现
        evictedKeys.forEach { assertEquals(0, buffer.dedupePresenceForKey(it)) }
    }

    /**
     * 券E（T-SqlListCatchup）：淘汰扫出键域升级为增量核销后的**定向等价性**——真机实态
     * 「buffer 恒满 + 异键风暴」（2026-11-07 主控情报：用户压测时 SQL 去重关闭；每条 SQL
     * 参数还原后指纹各异，每拍都有键被淘汰扫出 presence 键域）。
     *
     * 双判据：①恒满淘汰的随机序列下增量累积与每拍全量重算**逐键逐值相等**（tickAndAssert）；
     * ②**增量路径必须真被走到**——基线锚定后全程不得出现全量重建标记（此前该形态每拍置
     * 全量标记走整表回退；若断言缺失，用例会在全量回退口径下平凡通过，等价性变成空绿）。
     * 反向验证：恢复 buffer 侧扫出键域的 `countFullRebuildPending.set(true)`，本用例必红。
     */
    @Test
    fun `异键风暴_buffer恒满键持续进出_增量核销与全量重算逐键相等且不走全量`() {
        val seeds = listOf(7L, 99L)
        for (seed in seeds) {
            val rng = Random(seed)
            val buffer = SqlLogEntryBuffer(maxEntries = 5)
            val acc = newAcc(buffer)
            var clock = 0L
            var pendingOps = 0
            var baselineDone = false
            repeat(300) { op ->
                clock += 1
                // 恒异键：每条 SQL 指纹各异（对齐真机异键风暴形态——键只进不出域，淘汰持续摘键）
                buffer.addEntry(entryAt("SELECT * FROM t_$clock WHERE id = $clock", clock))
                if (++pendingOps >= 1 + rng.nextInt(3)) {
                    pendingOps = 0
                    val (changed, full) = tickAndAssert(buffer, acc, "seed=$seed op#$op")
                    if (baselineDone) {
                        assertFalse("seed=$seed op#$op 扫出键域必须走增量（不得置全量标记）", full)
                        assertTrue("seed=$seed op#$op 每拍都有键进出，变更键集应非空", changed.isNotEmpty())
                    }
                    baselineDone = true
                }
            }
            tickAndAssert(buffer, acc, "seed=$seed final") // 收尾对账，防悬挂余量
        }
    }

    @Test
    fun `双阈值节流窗内同键多次增减折叠为一次净差且无悬挂余量`() {        val buffer = SqlLogEntryBuffer(maxEntries = 4)
        buffer.dedupeWindowMs = 0
        val acc = newAcc(buffer)
        val aKey = entryAt("SELECT A", 0L).dedupeKey
        repeat(2) { buffer.addEntry(entryAt("SELECT A", it.toLong())) } // presence=2
        tickAndAssert(buffer, acc, "基线")
        // 一拍窗口内：+1（presence 3）、-1（removeEntry → 2）、+1（3）——drain 只见键一次，净差 +1
        buffer.addEntry(entryAt("SELECT A", 10L))
        buffer.removeEntry(buffer.snapshot().first())
        buffer.addEntry(entryAt("SELECT A", 20L))
        val (changed, full) = tickAndAssert(buffer, acc, "净差 +1")
        assertEquals("同键反复变化只记键不记次数", setOf(aKey), changed)
        assertFalse("键未扫出键域 → 增量拍", full)
        assertEquals(3, buffer.dedupePresenceForKey(aKey))
    }

    @Test
    fun `clear 后累积map与buffer双双清空`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 4)
        val acc = newAcc(buffer)
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 1", 60_000L)) // 恰出窗 → 入队（presence=2）
        tickAndAssert(buffer, acc, "基线")
        buffer.clear()
        tickAndAssert(buffer, acc, "clear 后")
        assertTrue(buffer.snapshot().isEmpty())
        assertTrue(buffer.dedupeCountsSnapshot().isEmpty())
        assertTrue(acc.currentDedupeCounts.isEmpty())
        assertTrue(acc.currentTemplateCounts.isEmpty())
    }

    @Test
    fun `首拍无基线即使无全量标记也全量重建`() {
        val buffer = SqlLogEntryBuffer(maxEntries = 4)
        val acc = newAcc(buffer)
        buffer.addEntry(entryAt("SELECT 1", 0L))
        assertFalse(buffer.drainCountRebuildFlag()) // buffer 从未置过标记
        // 累积器尚无基线 → 仍须走全量，不得按增量路径空转
        acc.apply(buffer.snapshot(), fullRebuild = false, changedKeys = buffer.drainChangedKeys())
        assertEquals(buffer.dedupeCountsSnapshot(), acc.currentDedupeCounts)
        assertEquals(structureKeyCounts(buffer.snapshot()), acc.currentTemplateCounts)
    }
}
