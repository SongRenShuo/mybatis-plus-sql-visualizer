package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SqlListCatchup 券B：计数变更键增量出口守卫（buffer 侧）。
 *
 * 每个用例对应一个增量出口维护面：入队登记 / ⨉N 丢弃登记 / 头部淘汰登记（含被淘汰键必须在
 * drain 集合内）/ 键扫出键域走增量核销不置全量（券E 升级）/ 双阈值节流窗内同键反复变化只记键
 * 不记次数 / clear / dedupeEnabled 翻转 / 接管沿重建 / dedupeExisting 裁剪 / 键上限溢出各自置
 * 全量标记。任一维护面漏账，对应用例必红（反向验证：摘掉对应 markCountKeyLocked / set(true) 行必红）。
 */
class SqlLogEntryBufferChangedKeysTest {

    private fun entryAt(sql: String, ts: Long) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
    )

    @Test
    fun `pendingNotifyCount 读未消费内容通知数且只读不消费`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        // 首条距上次通知超时间窗（初值 0）→ 即时通知，pending 归零
        b.addEntry(entryAt("SELECT 1", 0L))
        assertEquals(0, b.pendingNotifyCount())
        // 双阈值节流窗内（<200ms）连灌：第二条被节流抑制 → pending 计 1（券 D 追赶读数的缓冲段数据源）
        b.addEntry(entryAt("SELECT 2", 1L))
        assertEquals(1, b.pendingNotifyCount())
        b.drainChangedKeys()
        b.drainCountRebuildFlag()
        assertEquals("drain 只消费变更键与重建标记，不消费通知计数", 1, b.pendingNotifyCount())
    }

    @Test
    fun `入队登记变更键_drain消费即清`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        b.addEntry(entryAt("SELECT A", 0L))
        b.addEntry(entryAt("SELECT B", 1L))
        val aKey = entryAt("SELECT A", 0L).dedupeKey
        val bKey = entryAt("SELECT B", 1L).dedupeKey
        assertEquals(setOf(aKey, bKey), b.drainChangedKeys())
        assertTrue("消费即清", b.drainChangedKeys().isEmpty())
    }

    @Test
    fun `重复丢弃登记变更键_徽章按键重读`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        val sql = "SELECT * FROM t WHERE id = 1"
        val key = entryAt(sql, 0L).dedupeKey
        b.addEntry(entryAt(sql, 0L))
        b.drainChangedKeys()
        b.drainCountRebuildFlag()
        // 去重窗口（默认 60s）内重复 → 丢弃 + ⨉N
        assertFalse(b.addEntry(entryAt(sql, 1L)))
        assertTrue(b.drainChangedKeys().contains(key))
        assertEquals(1, b.dedupeCountForKey(key))
        assertEquals("未记录键按键读返回 0", 0, b.dedupeCountForKey("no-such-key"))
    }

    @Test
    fun `淘汰登记被淘汰键_唯一键扫出键域走增量不置全量`() {
        val b = SqlLogEntryBuffer(maxEntries = 2)
        b.dedupeWindowMs = 0 // 同键也能反复入队（窗口判定 0<0 恒假）
        val aKey = entryAt("SELECT A", 0L).dedupeKey
        b.addEntry(entryAt("SELECT A", 0L))
        b.addEntry(entryAt("SELECT B", 1L))
        b.drainChangedKeys()
        assertFalse("基线拍无重建标记", b.drainCountRebuildFlag())
        b.addEntry(entryAt("SELECT C", 2L)) // 挤出 SELECT A
        val keys = b.drainChangedKeys()
        assertTrue("drain 集合包含被淘汰键", keys.contains(aKey))
        // 券E：扫出键域升级为增量核销（确定性减账）——不再置全量标记（异键风暴下此前每拍整表回退）
        assertFalse("扫出键域应走变更键净差增量（不置全量标记）", b.drainCountRebuildFlag())
    }

    @Test
    fun `节流窗内同键反复增减_只记键不记次数且未扫出键域不置全量`() {
        val b = SqlLogEntryBuffer(maxEntries = 3)
        b.dedupeWindowMs = 0
        val aKey = entryAt("SELECT A", 0L).dedupeKey
        repeat(3) { b.addEntry(entryAt("SELECT A", it.toLong())) } // presence=3
        b.drainChangedKeys()
        assertFalse(b.drainCountRebuildFlag())
        // 入队路径先 +1（3→4）、淘汰路径随后 -1（4→3）：键仍有余条未扫出域
        b.addEntry(entryAt("SELECT A", 10L))
        val keys = b.drainChangedKeys()
        assertEquals("同键多次变更只记一次", setOf(aKey), keys)
        assertFalse("同键仍有余条 → 增量口径有效，不置全量", b.drainCountRebuildFlag())
        assertEquals(3, b.dedupePresenceForKey(aKey))
        b.drainChangedKeys()
        b.addEntry(entryAt("SELECT A", 20L)) // +1→4，挤最旧 A →3
        b.addEntry(entryAt("SELECT A", 30L)) // +1→4，挤最旧 A →3
        assertEquals(setOf(aKey), b.drainChangedKeys())
        assertEquals("无悬挂余量：净差收口到当下在队计数", 3, b.dedupePresenceForKey(aKey))
        assertFalse(b.drainCountRebuildFlag())
    }

    @Test
    fun `presence与structureKey按键出口与全量口径一致`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        b.addEntry(entryAt("SELECT * FROM t WHERE id = 1", 0L))
        val key = entryAt("SELECT * FROM t WHERE id = 1", 0L).dedupeKey
        val snap = b.snapshot()
        assertEquals(1, b.dedupePresenceForKey(key))
        assertEquals(snap.single().structureKey, b.structureKeyForKey(key))
        assertNull("键不在索引返回 null", b.structureKeyForKey("no-such-key"))
        // 去重关闭期间 presence 冻结（新条目不补账），读数口径不变
        b.dedupeEnabled = false
        b.addEntry(entryAt("SELECT * FROM t WHERE id = 2", 1L))
        val snap2 = b.snapshot()
        assertEquals(2, snap2.size)
        assertEquals("关闭期间 presence 冻结", 1, b.dedupePresenceForKey(key))
        assertEquals("关闭期间未入计的键读 0", 0, b.dedupePresenceForKey(snap2[1].dedupeKey))
    }

    @Test
    fun `开关翻转与接管沿与clear与手动裁剪各置全量标记`() {
        val b = SqlLogEntryBuffer(maxEntries = 10)
        b.addEntry(entryAt("SELECT 1", 0L))
        b.drainCountRebuildFlag()
        b.dedupeEnabled = false
        assertTrue("开关翻 false：presence 冻结，增量口径失效", b.drainCountRebuildFlag())
        b.dedupeEnabled = true
        assertTrue("开关翻 true：索引全量重建", b.drainCountRebuildFlag())
        b.rebuildDedupeIndexAfterHandoff(nowMs = 10_000L)
        assertTrue("接管沿重建：索引键域 grace 截断与 presence 分叉", b.drainCountRebuildFlag())
        b.clear()
        assertTrue("clear：累积 map 必须与 buffer 双双清零", b.drainCountRebuildFlag())
        assertTrue("clear 后悬挂变更键作废", b.drainChangedKeys().isEmpty())

        // dedupeExisting 手动裁剪（rebuildDedupeIndexLocked 路径）
        val b2 = SqlLogEntryBuffer(maxEntries = 10)
        b2.dedupeWindowMs = 0
        b2.addEntry(entryAt("SELECT 1", 0L))
        b2.addEntry(entryAt("SELECT 1", 1L))
        b2.drainCountRebuildFlag()
        assertEquals(1, b2.dedupeExisting())
        assertTrue("裁剪重建 → 全量标记", b2.drainCountRebuildFlag())
    }

    @Test
    fun `dedupeCounts 键上限溢出置全量标记`() {
        val b = SqlLogEntryBuffer(maxEntries = 5_000)
        val keyCount = 4_100 // > MAX_DEDUPE_COUNT_KEYS(4096)
        repeat(keyCount) { i ->
            val sql = "SELECT * FROM t WHERE id = $i"
            assertTrue(b.addEntry(entryAt(sql, i.toLong())))
            // 窗口内重复执行 → 丢弃计数 +1，键域撑过上限触发静默淘汰最旧键
            assertFalse(b.addEntry(entryAt(sql, i.toLong() + 1)))
        }
        assertTrue("键上限溢出静默逐键 → 变更键集无法表达，须全量标记", b.drainCountRebuildFlag())
        // 键域按 MAX_DEDUPE_COUNT_KEYS(4096) 封顶（超限静默淘汰最旧，F3 既有口径）
        assertEquals(4096, b.dedupeCountsSnapshot().size)
    }
}
