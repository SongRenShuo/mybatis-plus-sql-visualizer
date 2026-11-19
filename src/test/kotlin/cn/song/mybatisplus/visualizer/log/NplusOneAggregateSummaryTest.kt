// T-SyncK2JMaster180 测试移植自 master src/test/kotlin/cn/song/mybatisplus/visualizer/log/NplusOneAggregateSummaryTest.kt
// 互操作适配：LogSqlEntry 具名实参→23 参位置全参（threadName 第 14 位）；SqlLogEntryBuffer(maxEntries=100)→
// 位置构造；windowSuspectKeys 具名实参→位置实参；顶层函数→NplusOneAggregateKt/FocusTab 门面静态；
// record 访问器加括号；`key in suspects`→contains。
package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-Nplus1Aggregate D235：N+1 聚合视图纯逻辑面（摘要构建器 + 口径一致性，计划册 §四 A1/A2/A3）。
 *
 * 口径生命线（计划册 §二.2）：过滤键复用 D216 判定的同一 structureKey；**聚合组内条目数必须
 * 等于完整列表中该键条目数**——buildNplusOneAggregateSummary 与 filterByFocusTabs 都对
 * 完整列表按 entry.structureKey 精确匹配，同源同值，本类用断言钉死构造一致性。
 *
 * 负例集复用 D216（NplusOneWindowBufferTest）：异线程分散 / 阈值调高两路负例在聚合模型层重钉。
 */
class NplusOneAggregateSummaryTest : BasePlatformTestCase() {

    private val TEMPLATE = "SELECT * FROM t_user WHERE id = ?"

    /** k2j 适配：LogSqlEntry 无带 threadName 的小构造——走 23 参旧序便捷构造器（第 14 位 threadName）。 */
    private fun entryAt(n: Int, ts: Long, thread: String?): LogSqlEntry {
        val sql = TEMPLATE.replace("?", "$n")
        return LogSqlEntry(
            sql, sql, emptyList(), ts, null, LogSqlEntry.summarizeSql(sql), SqlSource.UNKNOWN,
            null, null, 0, emptyList(), emptyList(), null, thread, 0L,
            null, null, null, null, null, null, null, null,
        )
    }

    /** A1：同线程 60s 内 6 条同模板异字面量 → 窗口判嫌疑真 + 过滤组恰好 6 条 == 完整列表该键条目数。 */
    fun testA1同线程窗内六条_过滤组条目数恰等完整列表() {
        val entries = (1..6).map { entryAt(it, 1_000L + it * 1_000, "http-nio") }
        val b = SqlLogEntryBuffer(100)
        entries.forEach { b.addEntry(it) }
        val key = entries.first().structureKey()
        val suspects = b.windowSuspectKeys(
            10_000L,
            NplusOneSuspect.DEFAULT_WINDOW_MS,
            NplusOneSuspect.thresholdProvider.invoke(),
        )
        assertTrue("徽章判定应为真（同线程 6 条 ≥ 默认阈值 5）：$suspects", suspects.contains(key))
        // 过滤态组 = 既有 FocusTab.Template 过滤器（右键「只看同结构」同一实现）
        val group = FocusTab.filterByFocusTabs(entries, listOf(FocusTab.Template(key, 6)))
        val summary = NplusOneAggregateKt.buildNplusOneAggregateSummary(entries, key)!!
        assertEquals("过滤组恰好 6 条", 6, group.size)
        assertEquals("聚合组条目数 == 完整列表该键条目数（口径生命线）", 6, summary.count())
        assertEquals(group.size, summary.count())
    }

    /** A2 负例一：同模板分散多线程各一条 → 无徽章（窗口判定负）→ 无过滤组、无摘要。 */
    fun testA2负例_异线程分散无徽章无过滤组() {
        val entries = (1..6).map { entryAt(it, 1_000L + it * 1_000, "thread-$it") }
        val b = SqlLogEntryBuffer(100)
        entries.forEach { b.addEntry(it) }
        val key = entries.first().structureKey()
        val suspects = b.windowSuspectKeys(10_000L, NplusOneSuspect.DEFAULT_WINDOW_MS, 5)
        assertFalse("异线程分桶后任一桶不足阈值（D216 负例语义复用）：$suspects", suspects.contains(key))
        val summary = NplusOneAggregateKt.buildNplusOneAggregateSummary(entries, key)
        // 组存在性由条目决定（6 条同键仍在完整列表）——嫌疑缺失时面板层不进过滤态，摘要不会以
        // 「N+1 聚合」形态呈现；此处钉构建器事实：组本身可构建但条目数仍=完整列表口径
        assertEquals(6, summary!!.count())
    }

    /** A2 负例二：阈值调 10 后 6 条不再出徽章（显式阈值注入，与 D216 测试缝同规）。 */
    fun testA2负例_阈值调高后不出嫌疑() {
        val entries = (1..6).map { entryAt(it, 1_000L + it * 1_000, "http-nio") }
        val b = SqlLogEntryBuffer(100)
        entries.forEach { b.addEntry(it) }
        val key = entries.first().structureKey()
        val suspects = b.windowSuspectKeys(10_000L, NplusOneSuspect.DEFAULT_WINDOW_MS, 10)
        assertFalse("阈值 10 > 6 条：$suspects", suspects.contains(key))
    }

    /** A3：徽章桶数 == 过滤态组数——两个嫌疑模板并存时逐键断言恰等。 */
    fun testA3一致性_每个嫌疑键的桶数恰等过滤组数() {
        val a = (1..6).map { entryAt(it, 1_000L + it * 1_000, "http-nio") }
        val bEntries = (1..5).map {
            val sql = "DELETE FROM t_order WHERE id = $it"
            LogSqlEntry(
                sql, sql, emptyList(), 2_000L + it * 1_000, null, LogSqlEntry.summarizeSql(sql), SqlSource.UNKNOWN,
                null, null, 0, emptyList(), emptyList(), null, "worker-1", 0L,
                null, null, null, null, null, null, null, null,
            )
        }
        val entries = a + bEntries
        val b = SqlLogEntryBuffer(100)
        entries.forEach { b.addEntry(it) }
        val suspects = b.windowSuspectKeys(10_000L, NplusOneSuspect.DEFAULT_WINDOW_MS, 5)
        assertEquals("两个模板都应判嫌疑", 2, suspects.size)
        for (key in suspects) {
            val fullCount = entries.count { it.structureKey() == key }
            val group = FocusTab.filterByFocusTabs(entries, listOf(FocusTab.Template(key, fullCount)))
            val summary = NplusOneAggregateKt.buildNplusOneAggregateSummary(entries, key)
            assertEquals("键 $key：徽章桶数 == 过滤态组数（恰等）", fullCount, group.size)
            assertEquals("键 $key：摘要条数 == 徽章桶数", fullCount, summary!!.count())
        }
    }

    /** 摘要构建：首末时间取组内极值；单线程直报名；跨线程如实标注并截断名单。 */
    fun test摘要_首末时间与线程标注() {
        val entries = (1..6).map { entryAt(it, 10_000L + it * 1_000, "http-nio") }
        val key = entries.first().structureKey()
        val summary = NplusOneAggregateKt.buildNplusOneAggregateSummary(entries, key)!!
        assertEquals(11_000L, summary.firstTimestampMs())
        assertEquals(16_000L, summary.lastTimestampMs())
        assertFalse(summary.crossThread())
        val single = NplusOneAggregateKt.formatNplusOneAggregateSummary(summary)
        assertFalse("单行纪律：不出现 \\n", single.contains("\n"))
        // 文案随语言走 bundle——断言用同源 message 构造期望，不硬编码英文（测试 JVM locale 不定）
        val expectedSingle = MpSqlVisualizerBundle.message("log.nplusOneAggregate.thread", "http-nio")
        assertTrue("单线程直报名（期望含「$expectedSingle」）：$single", single.contains(expectedSingle))
        assertFalse("单线程不该出现跨线程标注：$single", single.contains(crossThreadPrefix()))
    }

    /** 跨线程段参数前导文案（bundle 同源：非参数部分，语言无关地做前缀判据）。 */
    private fun crossThreadPrefix(): String =
        MpSqlVisualizerBundle.message("log.nplusOneAggregate.threads", "\u00004", "x")
            .substringBefore("\u00004")
            .ifBlank { "x" }

    fun test摘要_跨线程如实标注() {
        val entries = (1..6).map { entryAt(it, 1_000L + it * 1_000, if (it <= 3) "http-nio" else "worker-$it") }
        val key = entries.first().structureKey()
        val summary = NplusOneAggregateKt.buildNplusOneAggregateSummary(entries, key)!!
        assertTrue(summary.crossThread())
        val text = NplusOneAggregateKt.formatNplusOneAggregateSummary(summary)
        assertTrue("跨线程段应在场（前缀「${crossThreadPrefix()}」）：$text", text.contains(crossThreadPrefix()))
        assertTrue("跨线程标注分散数 4：$text", text.contains("4"))
        assertTrue("名单截断到 3 个加省略号：$text", text.endsWith("…"))
    }

    fun test摘要_无线程名缺省线程段() {
        val entries = (1..6).map { entryAt(it, 1_000L + it * 1_000, null) }
        val key = entries.first().structureKey()
        val text = NplusOneAggregateKt.formatNplusOneAggregateSummary(
            NplusOneAggregateKt.buildNplusOneAggregateSummary(entries, key)!!)
        assertFalse("无线程名条目不进名单", text.contains(crossThreadPrefix()))
    }

    fun test组空或键空_无摘要() {
        val entries = (1..6).map { entryAt(it, 1_000L + it * 1_000, "http-nio") }
        assertNull("不存在的键无摘要", NplusOneAggregateKt.buildNplusOneAggregateSummary(entries, "missing-key"))
        assertNull("空键无摘要", NplusOneAggregateKt.buildNplusOneAggregateSummary(entries, ""))
    }
}
