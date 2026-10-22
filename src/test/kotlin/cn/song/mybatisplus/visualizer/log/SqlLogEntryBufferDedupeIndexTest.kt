package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SqlLogPerf P6（20261006-1020 计划）：去重索引等价性守卫。
 *
 * isDuplicate 从锁内 O(n) 全表扫描改 dedupeKey→最近时间戳的 O(1) 查表（65 条/秒并发灌入下
 * 三通道写线程与 EDT snapshot 抢同一把锁，扫描是热路径大头）。索引必须与旧全表扫描**逐字等价**——
 * 本文件每个用例对应一个索引维护面（入队 upsert / 头部淘汰回扫 / dedupeExisting 重建 /
 * dedupeEnabled 翻 true 重建 / clear 清空 / removeEntry 单键重算），断言全部按**旧语义可推演的
 * 期望值**写：任一维护面漏账，对应用例必红（摘掉修复行 → 必红，反向验证）。
 */
class SqlLogEntryBufferDedupeIndexTest {

    /** 窗口内同键重复必丢且计数；超窗重入必收（索引 upsert 面 + 窗口语义基线）。 */
    @Test
    fun `窗口内重复丢弃计数_超窗重入允许`() {
        // k2j 适配：Java 构造器无具名实参（master: SqlLogEntryBuffer(maxEntries = 10)）
        val b = SqlLogEntryBuffer(10)
        val sql = "SELECT * FROM dup WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        assertFalse("窗口内重复应丢弃", b.addEntry(entry(sql, 30_000)))
        assertEquals("丢弃计数 1", 1, b.dedupeCount(sql))
        assertTrue("超窗（70s > 60s 窗口）重入应允许", b.addEntry(entry(sql, 70_000)))
        assertFalse("重入后再来窗口内重复应丢弃", b.addEntry(entry(sql, 100_000)))
        assertEquals("两次丢弃计数累计 2", 2, b.dedupeCount(sql))
    }

    /** 头部淘汰回扫面：被淘汰者非该键最新时，索引不得误删/误留旧时间戳。 */
    @Test
    fun `淘汰非最新同键条目_索引保持最近时间戳`() {
        val b = SqlLogEntryBuffer(2)
        val sql = "SELECT * FROM evict WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        assertTrue("超窗重入", b.addEntry(entry(sql, 70_000)))
        assertTrue(b.addEntry(entry("SELECT * FROM other WHERE id = 9", 71_000))) // 挤掉 A@0（头部）
        assertEquals("容量 2：A@0 被淘汰", 2, b.snapshot().size)
        // 索引若误留 A@0（未回扫）→ 100-0=100s 超窗被收；正确基线（队内 A@70s）→ 30s < 60s 丢弃
        assertFalse("淘汰后索引应保持队内最近时间戳 A@70s（新条目落窗口内必丢）", b.addEntry(entry(sql, 100_000)))
    }

    /** dedupeExisting 重建面：手动裁剪只留最早一条，索引必须随队重建（不得留被裁掉的最新时间戳）。 */
    @Test
    fun `手动去重裁剪后_索引随队重建`() {
        val b = SqlLogEntryBuffer(10)
        val sql = "SELECT * FROM manual WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        assertTrue("超窗重入", b.addEntry(entry(sql, 70_000)))
        assertEquals("裁剪掉后到者", 1, b.dedupeExisting())
        assertEquals("队内只剩最早一条", 0, b.snapshot().first().timestamp)
        // 索引若误留被裁掉的 A@70s → 100-70=30s < 60s 被丢；正确基线（队内 A@0）→ 100s 超窗必收
        assertTrue("裁剪后索引应回到队内时间戳 A@0（超窗新条目必收）", b.addEntry(entry(sql, 100_000)))
    }

    /** dedupeEnabled 翻 true 重建面：关闭期间入队不维护索引，重开必须全量补账。 */
    @Test
    fun `去重开关重开_索引全量补账`() {
        val b = SqlLogEntryBuffer(10)
        val sql = "SELECT * FROM toggle WHERE id = 1"
        b.dedupeEnabled = true
        assertTrue(b.addEntry(entry(sql, 0)))
        b.dedupeEnabled = false
        assertTrue("关闭期间不去重", b.addEntry(entry(sql, 70_000)))
        b.dedupeEnabled = true
        // 索引若未补账（仍 A@0）→ 80-0=80s 超窗被收；正确基线（队内 A@70s）→ 10s < 60s 必丢
        assertFalse("重开后索引应补账到队内最近 A@70s（窗口内必丢）", b.addEntry(entry(sql, 80_000)))
    }

    /** clear 清空面：索引随队清空，清空后同键立即重入必收。 */
    @Test
    fun `清空后索引随队清空_同键立即重入允许`() {
        val b = SqlLogEntryBuffer(10)
        val sql = "SELECT * FROM wipe WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        b.clear()
        assertTrue("清空后同键重入应允许（索引不得残留旧时间戳）", b.addEntry(entry(sql, 10_000)))
    }

    /** removeEntry 单键重算面：删除该键最新条目后，索引必须回到队内余条的时间戳。 */
    @Test
    fun `删除单条后_索引回扫队内余条`() {
        val b = SqlLogEntryBuffer(10)
        val sql = "SELECT * FROM remove WHERE id = 1"
        b.dedupeEnabled = false // 先关去重装两个同键条目（超窗形态）
        b.addEntry(entry(sql, 0))
        b.addEntry(entry(sql, 70_000))
        b.dedupeEnabled = true
        // removeEntry 按同一性删除（buffer 入库的是带序号 copy，外部须持快照实例——SqlLogEntryBufferTest 同口径）
        val storedSecond = b.snapshot().last { it.timestamp == 70_000L }
        b.removeEntry(storedSecond)
        // 索引若误留被删的 A@70s → 80-70=10s < 60s 被丢；正确基线（队内 A@0）→ 80s 超窗必收
        assertTrue("删除最新同键后索引应回扫到队内 A@0（超窗新条目必收）", b.addEntry(entry(sql, 80_000)))
    }

    /** 等价性基线的锚定断言：窗口语义逐字不变（now - latest < window，边界等值不丢）。 */
    @Test
    fun `窗口边界等值不丢弃`() {
        val b = SqlLogEntryBuffer(10)
        val sql = "SELECT * FROM edge WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        assertTrue("now-latest == window（60s）不小于窗口 → 不丢", b.addEntry(entry(sql, 60_000)))
    }

    /** k2j 适配：Java record 构造器无具名实参，按 4 参便捷构造器（rawSql, restoredSql, params, timestamp）位置传参。 */
    private fun entry(sql: String, ts: Long) = LogSqlEntry(sql, sql, emptyList(), ts)
    // ---------- T-AttachZeroEvent P3：通道纪元重置面（2026-10-09 现场复盘） ----------

    /**
     * 接管沿重置面：桥纪元旧索引不得吞 agent 纪元首批同键事件。
     * 12:54 现场：接管前 60s 内桥刚捕过同端点，接管后同 SQL 的 agent 事件全落窗口内被当重复
     * 丢弃（面板零新条目、「attach 即生效」看似失效）——resetDedupeIndexOnChannelHandoff 后必须收。
     * 反向验证锚点：摘掉方法体的 dedupeIndex.clear()（或摘掉 AgentManager.updateOnline 的接线）
     * 本用例必红。
     */
    @Test
    fun `通道纪元重置_接管沿后同键旧索引不吞首批`() {
        val b = SqlLogEntryBuffer(10)
        val sql = "SELECT * FROM handoff WHERE id = 1"
        assertTrue("桥纪元：接管前同键条目在队", b.addEntry(entry(sql, 0)))
        b.rebuildDedupeIndexAfterHandoff(5_000, 8_000) // 接管沿（SqlCaptureGate.paused false→true，生产由 updateOnline 接线）
        assertTrue("接管沿后同键首批事件不得被旧索引吞（5s << 60s 窗口）", b.addEntry(entry(sql, 5_000)))
    }

    /** 同面守卫之二：agent↔agent 短窗重复仍被折——重置只豁免纪元切换那一瞬，不豁免新纪元内部。 */
    @Test
    fun `通道纪元重置后_新纪元内部短窗重复仍折叠`() {
        val b = SqlLogEntryBuffer(10)
        val sql = "SELECT * FROM handoff2 WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0))) // 桥纪元
        b.rebuildDedupeIndexAfterHandoff(5_000, 8_000)
        assertTrue(b.addEntry(entry(sql, 5_000))) // agent 纪元首批（纪元豁免）
        assertFalse("agent 纪元内部短窗重复照常折叠", b.addEntry(entry(sql, 8_000)))
        assertEquals("折叠计数照常累加", 1, b.dedupeCount(sql))
        assertEquals("条目不被重置波及（清索引不清条目）", 2, b.snapshot().size)
    }


    // ---------- O-147 W2-补：接管沿的通道纪元重建面（冻结 target 用例） ----------

    @Test
    fun `接管沿重建_陈旧桥纪元键清掉_同键首批不吞`() {
        val b = SqlLogEntryBuffer(10)
        val sql = "SELECT * FROM handoffRebuild WHERE id = 1"
        assertTrue("桥纪元：接管前同键条目在队", b.addEntry(entry(sql, 0)))
        // now=100_000、grace=5s → ts=0 属陈旧键，清掉
        b.rebuildDedupeIndexAfterHandoff(5_000, 100_000)
        assertTrue("接管沿后同键首批事件不得被陈旧索引吞（5s << 60s 窗口）", b.addEntry(entry(sql, 100_005)))
    }

    @Test
    fun `接管沿重建后_新纪元内部短窗重复仍折叠`() {
        val b = SqlLogEntryBuffer(10)
        val sql = "SELECT * FROM handoffRebuild2 WHERE id = 1"
        assertTrue(b.addEntry(entry(sql, 0)))
        b.rebuildDedupeIndexAfterHandoff(5_000, 100_000)
        assertTrue(b.addEntry(entry(sql, 100_005)))
        assertFalse("agent 纪元内部短窗重复照常折叠", b.addEntry(entry(sql, 100_008)))
        assertEquals("折叠计数照常累加", 1, b.dedupeCount(sql))
        assertEquals("条目不被重建波及（清索引不清条目）", 2, b.snapshot().size)
    }

    @Test
    fun `接管沿重建_新鲜键保留_同一次执行融合为一行`() {
        val b = SqlLogEntryBuffer(10)
        val sql = "SELECT * FROM handoffFresh WHERE id = 1"
        assertTrue("桥纪元：接管沿前毫秒级入库的控制台副本（无源码）", b.addEntry(entry(sql, 100_000)))
        // now=100_010、grace=5s → ts=100_000 ≥ 95_010，保留
        b.rebuildDedupeIndexAfterHandoff(5_000, 100_010)
        val agentCopy = LogSqlEntry(sql, sql, emptyList(), 100_030, null,
            LogSqlEntry.summarizeSql(sql), SqlSource.UNKNOWN, 3L,
            "a/A.java", 7, emptyList(), emptyList(), null, 0L,
            null, null, null, null, null, null, CaptureProvenance.AGENT)
        assertFalse("同一次执行的 agent 副本应融合（不新开行）", b.addEntry(agentCopy))
        val rows = b.snapshot().filter { it.restoredSql() == sql }
        assertEquals("同一次执行只留一行", 1, rows.size)
        assertEquals("且该行升级为带源码（可跳转）", "a/A.java", rows.single().sourceFile())
    }
}
