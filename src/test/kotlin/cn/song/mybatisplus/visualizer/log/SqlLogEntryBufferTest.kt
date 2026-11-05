package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 日志条目缓冲单元测试（T-M31-03，纯逻辑）。
 */
class SqlLogEntryBufferTest {

    private val buffer = SqlLogEntryBuffer(maxEntries = 3)

    private fun entry(sql: String = "SELECT 1") = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = 0L,
    )

    @Test
    fun `新增条目最早在前`() {
        buffer.addEntry(entry("SELECT 1"))
        buffer.addEntry(entry("SELECT 2"))
        val snap = buffer.snapshot()
        assertEquals(2, snap.size)
        assertEquals("SELECT 1", snap[0].restoredSql)
        assertEquals("SELECT 2", snap[1].restoredSql)
    }

    @Test
    fun `超过上限裁掉最旧`() {
        buffer.addEntry(entry("SELECT 1"))
        buffer.addEntry(entry("SELECT 2"))
        buffer.addEntry(entry("SELECT 3"))
        buffer.addEntry(entry("SELECT 4"))
        val snap = buffer.snapshot()
        assertEquals(3, snap.size)
        assertEquals("SELECT 2", snap[0].restoredSql)
        assertFalse(snap.any { it.restoredSql == "SELECT 1" })
    }

    @Test
    fun `暂停态丢弃新增`() {
        buffer.setPaused(true)
        assertFalse(buffer.addEntry(entry()))
        assertTrue(buffer.snapshot().isEmpty())
        buffer.setPaused(false)
        assertTrue(buffer.addEntry(entry()))
    }

    @Test
    fun `清空与删除单条`() {
        val buf = SqlLogEntryBuffer(maxEntries = 3)
        buf.dedupeEnabled = false // 本用例验证删除语义（与去重开关态隔离）
        val e1 = entry("SELECT 1")
        val e2 = entry("SELECT 2")
        buf.addEntry(e1)
        buf.addEntry(e2)
        // T-LogRowNumber：面板从列表模型拿到的是入库实例（带行号 copy），与 e1 同一
        buf.removeEntry(buf.snapshot().first { it.restoredSql == "SELECT 1" })
        assertEquals(listOf("SELECT 2"), buf.snapshot().map { it.restoredSql })
        buf.clear()
        assertTrue(buf.snapshot().isEmpty())
    }

    @Test
    fun `removeEntry 按同一性删除_带行号实例可删_无号原型不误删`() {
        // T-LogRowNumber：入库为带号 copy；面板引用=入库实例（同一性成立可删）
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = false
        buf.addEntry(entryAt("SELECT 1", 0L))
        val stored = buf.snapshot().single()
        assertEquals(1L, stored.sequenceNumber)
        buf.removeEntry(stored)
        assertTrue(buf.snapshot().isEmpty())
        // 无号原型（外部构造的等值条目）不应误删列表中的真身
        val proto = entryAt("SELECT 2", 100L)
        buf.addEntry(proto)
        buf.removeEntry(proto)
        assertEquals(1, buf.snapshot().size)
        assertEquals("SELECT 2", buf.snapshot().single().restoredSql)
    }

    @Test
    fun `监听器分层_内容通知节流与即时出口`() {
        // T-PerfBatch 分层契约：内容监听器走「数量+时间」节流（时间窗内首条即时）；
        // onEntryAccepted 每次成功入队即时回调（自动展开语义）；被抑制条目由兜底出口承接。
        var contentNotified = 0
        var suppressed = 0
        var accepted = 0
        val listener: () -> Unit = { contentNotified += 1 }
        buffer.addListener(listener)
        buffer.onContentNotifySuppressed = { suppressed += 1 }
        buffer.onEntryAccepted = { accepted += 1 }

        buffer.addEntry(entry("SELECT 1")) // 首条：距上次通知超时间窗 → 即时
        buffer.addEntry(entry("SELECT 2")) // 窗口内：节流抑制，兜底出口接管
        assertEquals("时间窗内首条即时通知", 1, contentNotified)
        assertEquals("被抑制条目由兜底出口承接", 1, suppressed)
        assertEquals("每次成功入队即时回调", 2, accepted)

        // 超过时间窗（200ms）后恢复即时通知——低流量等价即时
        Thread.sleep(210)
        buffer.addEntry(entry("SELECT 3"))
        assertEquals("超窗后恢复即时通知", 2, contentNotified)

        buffer.removeListener(listener)
        buffer.onContentNotifySuppressed = null
        buffer.onEntryAccepted = null
        buffer.addEntry(entry("SELECT 4"))
        assertEquals("移除监听后内容通知停止", 2, contentNotified)
    }

    @Test
    fun `监听器分层_去重丢弃只走徽章通道不触内容`() {
        buffer.dedupeWindowMs = 60000L
        var contentNotified = 0
        var badgeNotified = 0
        var accepted = 0
        buffer.addListener { contentNotified += 1 }
        buffer.onDedupeCountsChanged = { badgeNotified += 1 }
        buffer.onEntryAccepted = { accepted += 1 }

        buffer.addEntry(entry("SELECT 1")) // 成功：内容（即时）+ 接受回调
        buffer.addEntry(entry("SELECT 1")) // 窗口内重复：丢弃 → 仅徽章通道
        buffer.addEntry(entry("SELECT 1")) // 同上
        assertEquals("首条即时内容通知", 1, contentNotified)
        assertEquals("两次丢弃两次徽章通知（⨉N 即时）", 2, badgeNotified)
        assertEquals("重复 SQL 不是新 SQL，接受回调不触发", 1, accepted)
        assertEquals(1, buffer.snapshot().size)
    }

    // ---------- F-43：onEvicted 回调 ----------

    @Test
    fun `onEvicted 回调在淘汰时触发`() {
        val buf = SqlLogEntryBuffer(maxEntries = 2)
        val evicted = mutableListOf<String>()
        buf.onEvicted = { e -> evicted.add(e.restoredSql) }
        buf.addEntry(entry("SELECT 1"))
        buf.addEntry(entry("SELECT 2"))
        buf.addEntry(entry("SELECT 3")) // 淘汰 SELECT 1
        assertEquals(listOf("SELECT 1"), evicted)
    }

    // ---------- T-SqlHistoryFull：onEntryStored 全量写入回调 ----------

    @Test
    fun `onEntryStored 每次成功入队触发`() {
        val buf = SqlLogEntryBuffer(maxEntries = 5)
        val stored = mutableListOf<String>()
        buf.onEntryStored = { e -> stored.add(e.restoredSql) }
        buf.addEntry(entry("A"))
        buf.addEntry(entry("B"))
        buf.addEntry(entry("C"))
        assertEquals(listOf("A", "B", "C"), stored)
    }

    @Test
    fun `onEntryStored 覆盖被淘汰条目(全量)且不含去重丢弃`() {
        val buf = SqlLogEntryBuffer(maxEntries = 2)
        val stored = mutableListOf<String>()
        buf.onEntryStored = { e -> stored.add(e.restoredSql) }
        buf.addEntry(entry("A"))
        buf.addEntry(entry("B"))
        buf.addEntry(entry("A")) // 去重窗口内重复（A 仍在列表第一）→ 丢弃，不入 stored
        buf.addEntry(entry("C")) // 淘汰 A，但 A 已入队成功 → 已在 stored
        assertEquals("被淘汰条目也在全量历史中，去重丢弃不在", listOf("A", "B", "C"), stored)
    }

    @Test
    fun `onEntryStored 在暂停和过滤时不触发`() {
        val buf = SqlLogEntryBuffer(maxEntries = 5)
        val stored = mutableListOf<String>()
        buf.onEntryStored = { e -> stored.add(e.restoredSql) }
        buf.filterKeywords = listOf("DROP")
        buf.paused = true
        buf.addEntry(entry("DROP TABLE x")) // 过滤命中去重判定前被拦 → 不入
        buf.addEntry(entry("SELECT 1")) // 暂停态 → 不入
        assertEquals("暂停/过滤条目不进全量历史", 0, stored.size)
    }

    @Test
    fun `onEntryStored null 时无异常`() {
        val buf = SqlLogEntryBuffer(maxEntries = 1)
        buf.onEntryStored = null
        buf.addEntry(entry("SELECT 1"))
        buf.addEntry(entry("SELECT 2"))
        assertEquals(1, buf.snapshot().size)
    }

    @Test
    fun `onEvicted=null 时无异常`() {
        val buf = SqlLogEntryBuffer(maxEntries = 1)
        buf.onEvicted = null
        buf.addEntry(entry("SELECT 1"))
        buf.addEntry(entry("SELECT 2")) // 淘汰 SELECT 1，无回调
        assertEquals(1, buf.snapshot().size)
        assertEquals("SELECT 2", buf.snapshot()[0].restoredSql)
    }

    @Test
    fun `多条目连续淘汰触发多次回调`() {
        val buf = SqlLogEntryBuffer(maxEntries = 1)
        val evicted = mutableListOf<String>()
        buf.onEvicted = { e -> evicted.add(e.restoredSql) }
        buf.addEntry(entry("SELECT 1"))
        buf.addEntry(entry("SELECT 2"))
        buf.addEntry(entry("SELECT 3"))
        buf.addEntry(entry("SELECT 4"))
        assertEquals(listOf("SELECT 1", "SELECT 2", "SELECT 3"), evicted)
    }

    @Test
    fun `onEvicted 回调顺序为最早到最新`() {
        val buf = SqlLogEntryBuffer(maxEntries = 2)
        val evicted = mutableListOf<String>()
        buf.onEvicted = { e -> evicted.add(e.restoredSql) }
        buf.addEntry(entry("A"))
        buf.addEntry(entry("B"))
        buf.addEntry(entry("C")) // 淘汰 A
        buf.addEntry(entry("D")) // 淘汰 B
        buf.addEntry(entry("E")) // 淘汰 C
        assertEquals(listOf("A", "B", "C"), evicted)
    }

    // ---------- 去重（对齐 MybatisCodeHelper-Pro「移除重复的sql」） ----------

    // ---------- T-SqlSuppress（O-69）：屏蔽闸门 ----------

    /** 构造屏蔽指纹（与 SqlSuppressRuleManager 发布口径同源：suppressFingerprint = 去重口径 + 尾分号剥离）。 */
    private fun suppressKeyOf(sql: String): String = suppressFingerprint(sql)

    @Test
    fun `屏蔽规则命中_入队前丢弃`() {
        buffer.dedupeEnabled = false // 与去重开关隔离，纯验屏蔽闸门
        buffer.suppressKeys = setOf(suppressKeyOf("SELECT 1"))
        assertFalse("命中规则 → 入队返回 false（从未捕获过）", buffer.addEntry(entry("SELECT 1")))
        assertTrue("列表不含该条目", buffer.snapshot().isEmpty())
        // 空白差异的变体天然命中（规范化口径与去重一致）
        assertFalse("多空白/首尾空白变体同样被拦", buffer.addEntry(entry("  SELECT   1  ")))
        assertTrue(buffer.snapshot().isEmpty())
        // 大小写不同不命中（与既有规范化口径一致：大小写与分号不动）
        assertTrue("大小写变体不被拦", buffer.addEntry(entry("select 1")))
        assertEquals(1, buffer.snapshot().size)
    }

    @Test
    fun `结构匹配规则开启后_参数值不同的同形状SQL被拦_精确规则不误拦`() {
        // T-SqlSuppressUx 二段：结构匹配 opt-in——精确口径规则不拦不同参数值；结构规则拦
        buffer.dedupeEnabled = false
        buffer.suppressKeys = setOf(suppressKeyOf("SELECT * FROM t WHERE p = 'a'"))
        assertTrue("精确口径规则不拦不同参数值（opt-in 前置）", buffer.addEntry(entry("SELECT * FROM t WHERE p = 'b'")))
        assertEquals("仅精确未拦的那条在列", 1, buffer.snapshot().size)
        // 结构规则：同形状不同参数值被拦（闸门走 entry.structureKey）；被拦条目不入列
        buffer.suppressKeys = setOf(structureFingerprint("SELECT * FROM t WHERE p = 'a'"))
        buffer.hasStructureKeys = true // F20：结构键在集（生产由 SqlLogManager 同步 manager 标志）
        assertFalse("结构规则拦同形状不同参数值", buffer.addEntry(entry("SELECT * FROM t WHERE p = 'c'")))
        assertEquals("列表仍只有先前一条", 1, buffer.snapshot().size)
        // 不同形状不拦
        assertTrue(buffer.addEntry(entry("SELECT * FROM t WHERE q = 'a'")))
    }

    @Test
    fun `屏蔽规则带尾分号_命中不带分号的日志还原SQL`() {
        // T-SqlSuppressUx（20260924 走查返修）：规则粘贴态带 `;`、日志还原链不带——尾分号剥离后互命中
        buffer.dedupeEnabled = false
        buffer.suppressKeys = setOf(suppressKeyOf("SELECT 1;")) // 规则侧带分号
        assertFalse("不带分号的日志形态同样被拦", buffer.addEntry(entry("SELECT 1")))
        assertTrue(buffer.snapshot().isEmpty())
        buffer.suppressKeys = setOf(suppressKeyOf("SELECT 1")) // 规则侧不带分号
        assertFalse("带分号的日志形态同样被拦（对称）", buffer.addEntry(entry("SELECT 1;")))
        assertTrue(buffer.snapshot().isEmpty())
        // 剥离只作用收尾终结符：字面量内分号不构成跨语句误吞
        buffer.suppressKeys = setOf(suppressKeyOf("SELECT * FROM t WHERE s = 'a;b'"))
        assertTrue("形状不同的语句不被误吞", buffer.addEntry(entry("SELECT * FROM t WHERE s = 'a'")))
    }

    @Test
    fun `审计入队同样被屏蔽闸门拦截`() {
        buffer.suppressKeys = setOf(suppressKeyOf("SELECT 1"))
        assertFalse("addAuditEntry 走同一私有门序，屏蔽一并生效", buffer.addAuditEntry(entry("SELECT 1")))
        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun `被拦条目不占捕获序号不产生去重计数`() {
        buffer.dedupeEnabled = true
        buffer.suppressKeys = setOf(suppressKeyOf("SELECT dup"))
        buffer.addEntry(entryAt("SELECT dup", 0L))
        buffer.addEntry(entryAt("SELECT dup", 1L)) // 若未被屏蔽，第二条会进去重计数
        assertTrue("被拦条目无去重计数", buffer.dedupeCountsSnapshot().isEmpty())
        assertTrue(buffer.snapshot().isEmpty())
        // 序号：屏蔽条目不占号——解除屏蔽后首条成功入队从 #1 起
        buffer.suppressKeys = emptySet()
        assertTrue(buffer.addEntry(entryAt("SELECT 2", 2L)))
        val numbered = buffer.snapshot().single()
        assertEquals("屏蔽不占捕获序号", 1L, numbered.sequenceNumber)
    }

    @Test
    fun `移除规则后恢复入队`() {
        buffer.dedupeEnabled = false
        val keys = mutableSetOf(suppressKeyOf("SELECT 1"))
        buffer.suppressKeys = keys
        assertFalse(buffer.addEntry(entry("SELECT 1")))
        keys.clear() // 订阅者（SqlLogManager）整体替换/清空键集
        buffer.suppressKeys = keys
        assertTrue("规则移除后恢复入队", buffer.addEntry(entry("SELECT 1")))
        assertEquals(1, buffer.snapshot().size)
    }

    @Test
    fun `屏蔽命中走专用计数出口_不触内容与入队回调`() {
        buffer.dedupeEnabled = false
        buffer.suppressKeys = setOf(suppressKeyOf("SELECT 1"))
        val hitKeys = mutableListOf<String>()
        var accepted = 0
        var storedCount = 0
        var contentNotified = 0
        buffer.onSuppressedHit = { key -> hitKeys.add(key) }
        buffer.onEntryAccepted = { accepted += 1 }
        buffer.onEntryStored = { storedCount += 1 }
        buffer.addListener { contentNotified += 1 }
        buffer.addEntry(entry("SELECT 1"))
        assertEquals("命中指纹回调（管理对话框「已拦截 N 次」数据源）", listOf(suppressKeyOf("SELECT 1")), hitKeys)
        assertEquals("从未捕获过：接受回调不触发", 0, accepted)
        assertEquals("全量历史不写", 0, storedCount)
        assertEquals("内容通知不触发", 0, contentNotified)
    }

    companion object {
        /** 与 SqlLogEntryBuffer 默认窗口（60000ms，T-M31.17）同步——超窗口用例时间差须大于它。 */
        private const val DEFAULT_WINDOW = 60000L
    }

    private fun entryAt(sql: String, ts: Long) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
    )

    @Test
    fun `去重窗口内相同 SQL 丢弃且通知徽章`() {
        // F-44-2：用户实测「重复值计数更新的也不及时」——徽章数据变了必须即时通知 UI。
        // T-PerfBatch 分层：丢弃走徽章通道（即时），内容通道时间窗内首条入列即时通知。
        buffer.dedupeEnabled = true
        var contentNotified = 0
        var badgeNotified = 0
        buffer.addListener { contentNotified++ }
        buffer.onDedupeCountsChanged = { badgeNotified++ }
        assertTrue(buffer.addEntry(entryAt("SELECT 1", 0L)))
        assertFalse(buffer.addEntry(entryAt("SELECT 1", 500L))) // 窗口 1000ms 内
        assertEquals(1, contentNotified) // 首条入列内容通知
        assertEquals(1, badgeNotified) // 丢弃即时走徽章通道
        assertEquals(1, buffer.snapshot().size)
        assertEquals(1, buffer.dedupeCount("SELECT 1"))
    }

    @Test
    fun `去重窗口外相同 SQL 保留`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        assertTrue(buffer.addEntry(entryAt("SELECT 1", DEFAULT_WINDOW + 100L))) // 超窗口
        assertEquals(2, buffer.snapshot().size)
    }

    @Test
    fun `不同 SQL 不去重`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        assertTrue(buffer.addEntry(entryAt("SELECT 2", 100L)))
        assertEquals(2, buffer.snapshot().size)
    }

    // ---------- T-LogDedupeMpMark：跨格式副本（仅差首尾空白）判重 ----------

    @Test
    fun `尾随空白差异判重命中`() {
        // JdbcTemplate 双路日志：named 副本 trim、prepared 副本尾随空白原样保留
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        assertFalse(buffer.addEntry(entryAt("SELECT 1  ", 100L)))
        assertEquals(1, buffer.snapshot().size)
    }

    @Test
    fun `首部与内部连续空白差异判重命中`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT * FROM t WHERE id = 1", 0L))
        assertFalse(buffer.addEntry(entryAt("  SELECT *  FROM t WHERE id = 1", 100L)))
        assertEquals(1, buffer.snapshot().size)
    }

    @Test
    fun `空白差异副本丢弃计数并入同一徽章键`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 1 ", 100L))
        buffer.addEntry(entryAt("  SELECT 1", 200L))
        assertEquals(2, buffer.dedupeCount("SELECT 1"))
    }

    @Test
    fun `空白规范化不改大小写与内容_不同SQL仍不去重`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("select 1", 0L))
        assertTrue(buffer.addEntry(entryAt("SELECT 1", 100L)))
        assertTrue(buffer.addEntry(entryAt("SELECT 1 WHERE", 200L)))
        assertEquals(3, buffer.snapshot().size)
    }

    @Test
    fun `去重开关默认开启`() {
        assertTrue(buffer.dedupeEnabled) // 默认开（T-M31.13 用户实测后要求）
        buffer.addEntry(entryAt("SELECT 1", 0L))
        assertFalse(buffer.addEntry(entryAt("SELECT 1", 100L))) // 窗口内重复丢弃
    }

    @Test
    fun `过滤关键字命中时丢弃`() {
        buffer.filterKeywords = listOf("from_dual", "TMP_")
        assertFalse(buffer.addEntry(entryAt("SELECT * FROM from_dual", 0L)))
        assertFalse(buffer.addEntry(entryAt("SELECT * FROM TMP_LOG", 100L)))
        assertTrue(buffer.addEntry(entryAt("SELECT * FROM user", 200L)))
        assertEquals(1, buffer.snapshot().size)
    }

    @Test
    fun `过滤关键字为空时全部保留`() {
        buffer.filterKeywords = emptyList()
        assertTrue(buffer.addEntry(entryAt("SELECT * FROM user", 0L)))
        assertEquals(1, buffer.snapshot().size)
    }

    @Test
    fun `去重开关显式关闭时全部保留`() {
        buffer.dedupeEnabled = false
        buffer.addEntry(entryAt("SELECT 1", 0L))
        assertTrue(buffer.addEntry(entryAt("SELECT 1", 100L)))
        assertEquals(2, buffer.snapshot().size)
    }

    // ---------- 去重丢弃计数（列表 ⨉N 徽章数据源） ----------

    @Test
    fun `去重丢弃计数随丢弃递增`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 1", 100L)) // 丢弃 1 次
        buffer.addEntry(entryAt("SELECT 1", 200L)) // 丢弃 2 次
        assertEquals(2, buffer.dedupeCount("SELECT 1"))
        // T-Perf-19：快照键为 SQL 指纹（非原文）——经 sqlFingerprint 查键
        assertEquals(2, buffer.dedupeCountsSnapshot()[sqlFingerprint("SELECT 1")])
    }

    @Test
    fun `去重计数键为还原SQL原文_不同SQL互不干扰`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 2", 0L))
        buffer.addEntry(entryAt("SELECT 1", 100L)) // 丢弃
        assertEquals(1, buffer.dedupeCount("SELECT 1"))
        assertEquals(0, buffer.dedupeCount("SELECT 2"))
        assertEquals(0, buffer.dedupeCount("SELECT 3")) // 无记录返回 0
    }

    @Test
    fun `clear 归零去重计数`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 1", 100L)) // 丢弃 1 次
        assertEquals(1, buffer.dedupeCount("SELECT 1"))
        buffer.clear()
        assertEquals(0, buffer.dedupeCount("SELECT 1"))
    }

    @Test
    fun `resetDedupeCounts 归零而不清列表`() {
        buffer.dedupeEnabled = true
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 1", 100L)) // 丢弃 1 次
        buffer.resetDedupeCounts()
        assertEquals(0, buffer.dedupeCount("SELECT 1"))
        assertEquals(1, buffer.snapshot().size) // 列表条目保留
    }

    @Test
    fun `去重关闭期间不累计计数`() {
        buffer.dedupeEnabled = false
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 1", 100L)) // 去重关闭，正常入列
        assertEquals(0, buffer.dedupeCount("SELECT 1"))
    }

    @Test
    fun `去重丢弃触发监听器`() {
        // F-44-2：徽章数据变了必须通知 UI（实测反馈「不及时」是缺陷）。
        // T-PerfBatch 分层：徽章走独立通道，丢弃不再触发内容监听器。
        buffer.dedupeEnabled = true
        var contentNotified = 0
        var badgeNotified = 0
        buffer.addListener { contentNotified++ }
        buffer.onDedupeCountsChanged = { badgeNotified++ }
        buffer.addEntry(entryAt("SELECT 1", 0L))
        buffer.addEntry(entryAt("SELECT 1", 100L)) // 丢弃
        assertEquals(1, contentNotified) // 第一条入列
        assertEquals(1, badgeNotified) // 第二条丢弃 → 徽章通道即时
    }

    @Test
    fun `去重计数有界化_超限淘汰最旧键`() {
        // T-PerfSweep F3：计数键超上限淘汰最旧——内存有界，最新徽章语义保持。
        // 计数键仅在「丢弃」时产生：每个键构造「入队一条 + 重复丢弃一条」。
        val buf = SqlLogEntryBuffer(maxEntries = 10000)
        buf.dedupeWindowMs = 60000L
        buf.addEntry(entryAt("SELECT key-0", 0L))
        buf.addEntry(entryAt("SELECT key-0", 1L)) // 丢弃 → 键 key-0 计数（最旧，将被淘汰）
        for (i in 1..4096) {
            buf.addEntry(entryAt("SELECT key-$i", 0L))
            buf.addEntry(entryAt("SELECT key-$i", 1L)) // 丢弃 → 键 key-$i 计数
        }
        assertEquals("最新键计数保留", 1, buf.dedupeCount("SELECT key-4096"))
        assertEquals("最旧键已淘汰", 0, buf.dedupeCount("SELECT key-0"))
        assertEquals("计数键总量封顶", 4096, buf.dedupeCountsSnapshot().size)
    }

    // ---------- F-44-2：dedupeExisting / disableDedupe ----------

    @Test
    fun `dedupeExisting 按窗口裁剪既有条目`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        // 先关闭去重以便添加多条重复条目（验证 dedupeExisting 自身的去重逻辑，而非 addEntry 的）
        buf.dedupeEnabled = false
        buf.dedupeWindowMs = 1000L
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.addEntry(entryAt("SELECT 1", 200L))
        buf.addEntry(entryAt("SELECT 1", 400L))
        buf.addEntry(entryAt("SELECT 2", 800L))
        // 不动缓冲，先确认现有 4 条都在
        assertEquals(4, buf.snapshot().size)
        // 用户勾选「移除重复的sql」→ 启用 dedupeEnabled 后调用 dedupeExisting
        buf.dedupeEnabled = true
        val removed = buf.dedupeExisting()
        // SELECT 1 三个 → 留首条、丢 2 个；SELECT 2 不重复 → 0 个；总移除 2
        assertEquals(2, removed)
        assertEquals(2, buf.snapshot().size)
        assertEquals(0L, buf.snapshot()[0].timestamp) // 首条保留
        assertEquals("SELECT 2", buf.snapshot()[1].restoredSql)
        assertEquals(2, buf.dedupeCount("SELECT 1"))
    }

    @Test
    fun `dedupeExisting 关闭时不生效`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = false
        buf.dedupeWindowMs = 1000L
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.addEntry(entryAt("SELECT 1", 100L))
        assertEquals(0, buf.dedupeExisting())
        assertEquals(2, buf.snapshot().size)
    }

    @Test
    fun `dedupeExisting 空白差异副本按同键裁剪`() {
        // T-LogDedupeMpMark：勾选去重对既有跨格式副本（named trim / prepared 尾随空白）同样生效
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = false
        buf.dedupeWindowMs = 1000L
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.addEntry(entryAt("SELECT 1  ", 200L))
        buf.dedupeEnabled = true
        assertEquals(1, buf.dedupeExisting())
        assertEquals(1, buf.snapshot().size)
        assertEquals("SELECT 1", buf.snapshot()[0].restoredSql)
        assertEquals(1, buf.dedupeCount("SELECT 1"))
    }

    @Test
    fun `dedupeExisting 单条不触发`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = true
        buf.addEntry(entryAt("SELECT 1", 0L))
        assertEquals(0, buf.dedupeExisting())
        assertEquals(1, buf.snapshot().size)
    }

    @Test
    fun `dedupeExisting 跨窗口重复也裁剪`() {
        // T-LogRowNumber 追加：手动全量裁剪不看时间窗口——跨窗口（>dedupeWindowMs）的重复也必须裁掉，
        // 否则用户点击「移除重复」后重复项仍在列表（用户反馈「显示不正确」根因）。
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = false
        buf.dedupeWindowMs = 1000L
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.addEntry(entryAt("SELECT 1", 2000L)) // 超 1s 窗口
        assertEquals(2, buf.snapshot().size)
        buf.dedupeEnabled = true
        assertEquals(1, buf.dedupeExisting()) // 跨窗口仍裁 1 条
        assertEquals(1, buf.snapshot().size)
        assertEquals(1, buf.dedupeCount("SELECT 1"))
    }

    @Test
    fun `disableDedupe 清空累计计数`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = false
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.addEntry(entryAt("SELECT 1", 100L))
        // 关闭态 → 入列，不累计计数
        assertEquals(0, buf.dedupeCount("SELECT 1"))
        buf.dedupeEnabled = true
        buf.addEntry(entryAt("SELECT 1", 200L)) // 现在窗口内，去重 +1
        assertEquals(1, buf.dedupeCount("SELECT 1"))
        buf.disableDedupe()
        assertEquals(0, buf.dedupeCount("SELECT 1"))
        assertFalse(buf.dedupeEnabled)
    }

    @Test
    fun `disableDedupe 重复调用幂等`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = true
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.disableDedupe()
        // 二次调用不应抛异常/重置状态报错
        buf.disableDedupe()
        assertEquals(0, buf.dedupeCount("SELECT 1"))
    }

    // ---------- T-LogRowNumber：捕获序号（列表行号） ----------

    @Test
    fun `行号连续入队单调递增`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.addEntry(entryAt("SELECT 2", 100L))
        buf.addEntry(entryAt("SELECT 3", 200L))
        val snap = buf.snapshot()
        assertEquals(listOf(1L, 2L, 3L), snap.map { it.sequenceNumber })
    }

    @Test
    fun `去重丢弃不占号`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = true
        buf.addEntry(entryAt("SELECT 1", 0L))          // #1
        assertFalse(buf.addEntry(entryAt("SELECT 1", 100L))) // 丢弃
        buf.addEntry(entryAt("SELECT 2", 200L))        // 应为 #2（不跳号）
        val snap = buf.snapshot()
        assertEquals(listOf(1L, 2L), snap.map { it.sequenceNumber })
    }

    @Test
    fun `暂停态与过滤关键字不占号`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = false // 本用例专验暂停/过滤分支不占号（与既有去重用例隔离）
        buf.addEntry(entryAt("SELECT 1", 0L)) // #1
        buf.setPaused(true)
        assertFalse(buf.addEntry(entryAt("SELECT X", 100L)))
        buf.setPaused(false)
        buf.filterKeywords = listOf("TMP_")
        assertFalse(buf.addEntry(entryAt("SELECT TMP_LOG", 200L)))
        buf.filterKeywords = emptyList()
        buf.addEntry(entryAt("SELECT 2", 300L)) // 应为 #2
        assertEquals(listOf(1L, 2L), buf.snapshot().map { it.sequenceNumber })
    }

    @Test
    fun `clear 后行号重新从 1 计数`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.addEntry(entryAt("SELECT 1", 0L))
        buf.addEntry(entryAt("SELECT 2", 100L))
        buf.clear()
        buf.addEntry(entryAt("SELECT 3", 200L))
        val snap = buf.snapshot()
        assertEquals(1, snap.size)
        assertEquals(1L, snap[0].sequenceNumber)
    }

    @Test
    fun `resetEntrySequence 后新条目从 1 起旧条目行号保留`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.addEntry(entryAt("SELECT 1", 0L)) // #1
        buf.addEntry(entryAt("SELECT 2", 100L)) // #2
        buf.resetEntrySequence()
        // 旧条目不清、行号保留原值
        assertEquals(listOf(1L, 2L), buf.snapshot().map { it.sequenceNumber })
        buf.addEntry(entryAt("SELECT 3", 200L)) // 新一轮 #1
        assertEquals(1L, buf.snapshot().last().sequenceNumber)
    }

    @Test
    fun `dedupeExisting 裁剪后剩余条目行号保留原值`() {
        // T-LogRowNumber 用户确认：裁剪只删条目不重新编号，跳号表达「重复被移除」
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeEnabled = false
        buf.dedupeWindowMs = 1000L
        buf.addEntry(entryAt("SELECT 1", 0L))   // #1
        buf.addEntry(entryAt("SELECT 1", 200L)) // #2（重复副本）
        buf.addEntry(entryAt("SELECT 2", 400L)) // #3
        buf.dedupeEnabled = true
        assertEquals(1, buf.dedupeExisting()) // 裁掉 #2
        val snap = buf.snapshot()
        assertEquals(listOf("SELECT 1", "SELECT 2"), snap.map { it.restoredSql })
        assertEquals(listOf(1L, 3L), snap.map { it.sequenceNumber }) // 跳号：#2 不复用
    }

    @Test
    fun `头部淘汰后剩余条目行号保留原值`() {
        val buf = SqlLogEntryBuffer(maxEntries = 2)
        buf.addEntry(entryAt("SELECT 1", 0L)) // #1，后被淘汰
        buf.addEntry(entryAt("SELECT 2", 100L)) // #2
        buf.addEntry(entryAt("SELECT 3", 200L)) // #3，触发淘汰 #1
        val snap = buf.snapshot()
        assertEquals(listOf("SELECT 2", "SELECT 3"), snap.map { it.restoredSql })
        assertEquals(listOf(2L, 3L), snap.map { it.sequenceNumber })
    }

    // ---------- T-AgentCaptureProvenance（O-147）W1：同去重桶字段级融合 ----------

    /** 控制台回落形态条目（无源码/无耗时/无库标——20261014 现场截图的「—」行形态）。 */
    private fun consoleProvEntry(sql: String, ts: Long) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
        source = SqlSource.MYBATIS,
        captureProvenance = CaptureProvenance.CONSOLE_BRIDGE,
    )

    /** agent 通道形态条目（源码 + 候选 + 耗时 + 库标齐备）。 */
    private fun agentProvEntry(
        sql: String,
        ts: Long,
        sourceFile: String? = "src/main/java/com/example/RefundOrderService.java",
        durationMs: Long? = 20L,
    ) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = ts,
        source = SqlSource.AGENT,
        executionTimeMs = durationMs,
        sourceFile = sourceFile,
        sourceLine = if (sourceFile == null) 0 else 67,
        sourceCandidates = if (sourceFile == null) emptyList() else listOf(sourceFile!! to 67),
        databaseName = "PostgreSQL · cyzc@localhost:5432",
        captureProvenance = CaptureProvenance.AGENT,
    )

    @Test
    fun `O147 控制台先到 agent后到 融合升级为可跳转`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeWindowMs = 60_000L
        val sql = "SELECT COUNT(*) AS total FROM t WHERE station_id IN (250,205)"
        assertTrue(buf.addEntry(consoleProvEntry(sql, 1_000L)))
        // 融合仍视为重复观测：返回 false、不入新行（onEntryAccepted 语义 = 新捕获，不触发）
        assertFalse(buf.addEntry(agentProvEntry(sql, 2_000L)))
        val snap = buf.snapshot()
        assertEquals("同桶仍只保留一条", 1, snap.size)
        val merged = snap.single()
        assertEquals("列表序号稳定（保留首条行号）", 1L, merged.sequenceNumber)
        assertEquals("捕获时间保留首条", 1_000L, merged.timestamp)
        assertEquals("源码位置升级为真实文件", "src/main/java/com/example/RefundOrderService.java", merged.sourceFile)
        assertEquals(67, merged.sourceLine)
        assertEquals("耗时补齐", 20L, merged.executionTimeMs)
        assertEquals("库标补齐", "PostgreSQL · cyzc@localhost:5432", merged.databaseName)
        assertEquals(
            "候选帧补齐",
            listOf("src/main/java/com/example/RefundOrderService.java" to 67),
            merged.sourceCandidates,
        )
        assertEquals("跨通道融合标 MERGED", CaptureProvenance.MERGED, merged.captureProvenance)
        assertEquals("升级计一次折叠", 1, buf.dedupeCount(sql))
    }

    @Test
    fun `O147 agent先到 控制台后到 不降级`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeWindowMs = 60_000L
        val sql = "SELECT * FROM t WHERE id = 7"
        assertTrue(buf.addEntry(agentProvEntry(sql, 1_000L)))
        assertFalse(buf.addEntry(consoleProvEntry(sql, 2_000L)))
        val merged = buf.snapshot().single()
        assertEquals("真实源码不被空值覆盖", "src/main/java/com/example/RefundOrderService.java", merged.sourceFile)
        assertEquals("耗时保留 agent 事实", 20L, merged.executionTimeMs)
        assertEquals(CaptureProvenance.MERGED, merged.captureProvenance)
    }

    @Test
    fun `O147 融合只补空不覆盖既有值`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeWindowMs = 60_000L
        assertTrue(buf.addEntry(agentProvEntry("SELECT 1", 1_000L, durationMs = 20L)))
        // 同为 agent 通道的重复：无任何空位可补 → 与保留条目全等 → 不替换（同通道不制造假融合）
        assertFalse(buf.addEntry(agentProvEntry("SELECT 1", 2_000L, durationMs = 50L)))
        val merged = buf.snapshot().single()
        assertEquals("已有耗时不被后到值改写", 20L, merged.executionTimeMs)
        assertEquals("同通道重复保持原通道标记", CaptureProvenance.AGENT, merged.captureProvenance)
    }

    @Test
    fun `O147 MP分页伪标记被真实源码升级`() {
        val buf = SqlLogEntryBuffer(maxEntries = 10)
        buf.dedupeWindowMs = 60_000L
        val sql = "SELECT COUNT(*) FROM biz"
        assertTrue(
            buf.addEntry(
                LogSqlEntry(
                    rawSql = sql,
                    restoredSql = sql,
                    params = emptyList(),
                    timestamp = 1_000L,
                    sourceFile = SourceMarkers.MP_PAGINATION_COUNT,
                    captureProvenance = CaptureProvenance.CONSOLE_EP,
                ),
            ),
        )
        assertFalse(buf.addEntry(agentProvEntry(sql, 2_000L)))
        val merged = buf.snapshot().single()
        assertEquals("分页 COUNT 的伪标记让位于真实调用点", "src/main/java/com/example/RefundOrderService.java", merged.sourceFile)
    }

    @Test
    fun `O147 融合条目随淘汰回收后同键新观测正常入库`() {
        val buf = SqlLogEntryBuffer(maxEntries = 2)
        buf.dedupeWindowMs = 60_000L
        buf.addEntry(consoleProvEntry("A", 1_000L))
        buf.addEntry(agentProvEntry("A", 2_000L)) // 融合
        buf.addEntry(consoleProvEntry("B", 3_000L))
        buf.addEntry(consoleProvEntry("C", 4_000L)) // 触发淘汰 A 融合条目
        assertEquals(listOf("B", "C"), buf.snapshot().map { it.restoredSql })
        // 融合条目被淘汰后索引已随队回收：窗口内 A 再次到达不得被旧索引误判重复。
        // （maxEntries=2 下 A 入库会把队首 B 挤出——上限语义照旧，本用例判别点在 A 是否入库。）
        assertTrue(buf.addEntry(consoleProvEntry("A", 5_000L)))
        assertEquals(listOf("C", "A"), buf.snapshot().map { it.restoredSql })
    }

    /**
     * T-CaptureGateStability P3：跨通道诊断与「捕获归因」**各自限频**，互不吞窗口。
     *
     * 改之前三条诊断共用一个 10s 窗口：控制台条目入队必然先打「捕获归因」（缺源码），
     * 紧随其后的折叠日志被同一窗口吃掉——真机 4 次折叠、日志 0 条，现场无法确认折叠发生。
     * 拆通道后两者各自计时，本用例钉住「折叠那一次真的输出了」。
     *
     * 反向验证：把 `logCrossChannelThrottled` 改回 `logAttributionThrottled`，第二段断言必红
     * （折叠的 CAS 落在同一窗口内，跨通道通道时刻保持不变）。
     */
    @Test
    fun `跨通道诊断与捕获归因各自限频_互不吞`() {
        val buf = SqlLogEntryBuffer(maxEntries = 20)
        val never = Long.MIN_VALUE / 2
        assertEquals("前置：捕获归因通道未输出过", never, buf.attributionDiagStampForTest())
        assertEquals("前置：跨通道通道未输出过", never, buf.crossChannelDiagStampForTest())

        val now = System.currentTimeMillis()
        // 1) 缺源码的控制台条目 → 触发「捕获归因」
        val console = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE f = ?",
            restoredSql = "SELECT * FROM t WHERE f = true",
            params = listOf(LogSqlParam("true", "Boolean")),
            timestamp = now,
            source = SqlSource.MYBATIS,
            captureProvenance = CaptureProvenance.CONSOLE_BRIDGE,
        )
        assertTrue("控制台条目正常入队", buf.addEntry(console))
        assertTrue("捕获归因通道应被消耗", buf.attributionDiagStampForTest() != never)
        assertEquals("此时跨通道通道仍未被消耗", never, buf.crossChannelDiagStampForTest())

        // 2) 同一次执行由 agent 在同窗内再观测一次（dedupeKey 不同、crossKey 相同）→ 兜底折叠
        val agent = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE f = ?",
            restoredSql = "SELECT * FROM t WHERE f = 'true'",
            params = listOf(LogSqlParam("true", "Boolean")),
            timestamp = now + 50,
            source = SqlSource.AGENT,
            executionTimeMs = 5L,
            sourceFile = "src/main/java/com/example/Mapper.java",
            sourceLine = 10,
            captureProvenance = CaptureProvenance.AGENT,
        )
        assertFalse("跨通道兜底折叠（不是新捕获）", buf.addEntry(agent))
        assertTrue(
            "跨通道通道必须被独立消耗（未被捕获归因吃掉窗口）",
            buf.crossChannelDiagStampForTest() != never,
        )
        assertEquals("折叠后只剩一行", 1, buf.size())
    }
}
