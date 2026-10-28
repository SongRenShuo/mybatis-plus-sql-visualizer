package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 日志列表搜索行号/时间 + 右键复制时间戳（T-LogSearchRowTime）：
 *
 * - [filterBySearch]：行号 `#5`/`5` 精确 equals、时间显示片段 contains（所见即所搜，
 *   显示关闭=片段 null 不参与）、SQL/摘要既有语义不变；时间文本经 [filterBySearch] 第三参
 *   注入 stub 的用例不依赖平台设置（纯逻辑断言）。
 * - [batchCopyTimestamps] / [timestampDisplayText]：跟随真实设置（来源/格式/开关）——
 *   所见即所复制，复制内容为显示片段去方括号的纯文本；开关关闭时空串（菜单项据此置灰）。
 */
class SqlLogSearchRowTimeTest : BasePlatformTestCase() {

    private var savedEnabled: Boolean = true
    private var savedSource: String = ""
    private var savedFormat: String = ""

    override fun setUp() {
        super.setUp()
        val s = PluginSettings.getInstance().state
        savedEnabled = s.logTimestampEnabled
        savedSource = s.logTimestampSource
        savedFormat = s.logTimestampFormat
    }

    override fun tearDown() {
        val s = PluginSettings.getInstance().state
        s.logTimestampEnabled = savedEnabled
        s.logTimestampSource = savedSource
        s.logTimestampFormat = savedFormat
        super.tearDown()
    }

    /** 行号 5 的条目（timestamp 固定：2024-01-15 10:30:45.123 Asia/Shanghai）。 */
    private fun rowEntry(seq: Long): LogSqlEntry = LogSqlEntry(
        rawSql = "SELECT * FROM t WHERE id = ?",
        restoredSql = "SELECT * FROM t WHERE id = 42",
        params = emptyList(),
        timestamp = 1705285845123L,
        logTimestamp = 1705285845123L,
        sequenceNumber = seq,
    )

    // ---------- filterBySearch：行号（注入 stub，纯逻辑） ----------

    fun testFilterBySearch行号Hash形态精确命中() {
        val entries = listOf(rowEntry(5), rowEntry(51), rowEntry(123))
        val hit = filterBySearch(entries, "#5") { null }
        assertEquals(listOf(5L), hit.map { it.sequenceNumber })
    }

    fun testFilterBySearch行号裸数字精确命中() {
        val entries = listOf(rowEntry(5), rowEntry(51), rowEntry(123))
        val hit = filterBySearch(entries, "5") { null }
        assertEquals(listOf(5L), hit.map { it.sequenceNumber })
    }

    fun testFilterBySearch行号1不命中123() {
        val entries = listOf(rowEntry(1), rowEntry(123))
        val hit = filterBySearch(entries, "1") { null }
        assertEquals(listOf(1L), hit.map { it.sequenceNumber })
    }

    fun testFilterBySearch行号0的条目对行号查询不命中() {
        // sequenceNumber == 0（历史文件/round-trip 条目）不渲染行号，不应被搜 "0" 误命中
        val entries = listOf(rowEntry(0))
        assertTrue(filterBySearch(entries, "0") { null }.isEmpty())
    }

    fun testFilterBySearch非数字查询不进行行号匹配() {
        // 非纯数字（含 # 前缀剥离后仍非数字）跳过行号分支，SQL 兜底
        val entries = listOf(rowEntry(5))
        assertTrue(filterBySearch(entries, "abc#1") { null }.isEmpty())
        assertEquals(listOf(5L), filterBySearch(entries, "id = 42") { null }.map { it.sequenceNumber })
    }

    // ---------- filterBySearch：时间（注入 stub） ----------

    fun testFilterBySearch时间片段contains命中() {
        val entries = listOf(rowEntry(5))
        // 显示片段含方括号（所见即所搜）：搜纯时间文本 / 带括号前缀都命中
        val byTime = filterBySearch(entries, "10:30") { "[2024-01-15 10:30:45]" }
        assertEquals(1, byTime.size)
        val byBracket = filterBySearch(entries, "[2024") { "[2024-01-15 10:30:45]" }
        assertEquals(1, byBracket.size)
    }

    fun testFilterBySearch时间片段Null不参与() {
        // 显示开关关闭语义：片段 null → 时间分支不参与，SQL 仍兜底
        val entries = listOf(rowEntry(5))
        assertTrue(filterBySearch(entries, "10:30") { null }.isEmpty())
        assertEquals(1, filterBySearch(entries, "id = 42") { null }.size)
    }

    fun testFilterBySearch空查询透传() {
        val entries = listOf(rowEntry(5), rowEntry(6))
        assertEquals(entries, filterBySearch(entries, "  ") { "[x]" })
    }

    // ---------- timestampDisplayText / batchCopyTimestamps：跟随真实设置 ----------

    fun testTimestampDisplayText与渲染片段一致去尾空格() {
        val text = timestampDisplayText(rowEntry(5))
        // 默认格式 yyyy-MM-dd HH:mm:ss（来源默认 log=日志时间）；与 formatTimestampFragment 同源仅去尾空格
        assertEquals(formatTimestampFragment(rowEntry(5))?.first?.trim(), text)
        assertTrue("应保留显示装饰方括号", text!!.startsWith("[") && text.endsWith("]"))
    }

    fun testTimestampDisplayText开关关闭返回Null() {
        PluginSettings.getInstance().state.logTimestampEnabled = false
        assertNull(timestampDisplayText(rowEntry(5)))
    }

    fun testBatchCopyTimestamps纯文本无方括号() {
        // 用户定调：复制无方括号——显示片段 [2024-01-15 10:30:45] → 2024-01-15 10:30:45
        val text = batchCopyTimestamps(listOf(rowEntry(5)))
        assertEquals("2024-01-15 10:30:45", text)
    }

    fun testBatchCopyTimestamps多选逐行按选择序() {
        PluginSettings.getInstance().state.logTimestampFormat = "HH:mm:ss"
        val text = batchCopyTimestamps(listOf(rowEntry(5), rowEntry(6)))
        assertEquals("10:30:45\n10:30:45", text)
    }

    fun testBatchCopyTimestamps开关关闭返回空串() {
        PluginSettings.getInstance().state.logTimestampEnabled = false
        assertEquals("", batchCopyTimestamps(listOf(rowEntry(5), rowEntry(6))))
    }

    fun testBatchCopyTimestamps来源切换跟随设置() {
        PluginSettings.getInstance().state.logTimestampSource = "capture"
        // capture 来源下 timestamp 与 logTimestamp 同值（测试条目），形态一致仅验证来源分支可达
        assertEquals("2024-01-15 10:30:45", batchCopyTimestamps(listOf(rowEntry(5))))
    }
    // ---------- T-LogListHeader：头部行改版后「所见即所搜」不回归 ----------

    /**
     * 回归守卫：头部行改版（元信息从左侧栏搬到 SQL 上方 + 宽度不足时剥日期）之后，
     * **时间与行号搜索必须仍然可用**。
     *
     * 为什么需要这条：行号/时间搜索依赖「显示文本 == 可搜文本」这一契约。改版动了显示位置
     * 与裁剪策略（窄宽度下走 `clipSqlFragments` 顺序截断），任何一侧漂移都会让
     * 用户「看得见却搜不到」。本用例直接走**真实渲染链路**取头部行文本，再拿去搜索，
     * 闭合两端一致性。
     */
    fun test头部行可见时段可搜_窄宽与宽侧栏一致() {
        val state = PluginSettings.getInstance().state
        val savedOn = state.logTimestampEnabled
        val savedFmt = state.logTimestampFormat
        val savedMode = state.logListSqlDisplayMode
        try {
            state.logTimestampEnabled = true
            state.logTimestampFormat = "yyyy-MM-dd HH:mm:ss"
            state.logListSqlDisplayMode = "KEYWORD_WRAP"
            val entry = LogSqlEntry(
                rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(),
                timestamp = 1_700_000_000_000L, logTimestamp = 1_700_000_000_000L,
                executionTimeMs = 5L, sequenceNumber = 42L,
                sourceFile = "src/main/java/SomeServiceImpl.java", sourceLine = 7,
            )
            val full = requireNotNull(timestampDisplayText(entry)).removeSurrounding("[", "]")
            val timeOnly = full.substringAfterLast(' ')

            for (width in listOf(900, 420, 320, 280)) {
                val list = javax.swing.JList<Any>().apply { setSize(width, 400) }
                val renderer = LogEntryListCellRenderer()
                renderer.getListCellRendererComponent(list, entry, 0, false, false)
                val header = renderer.metaTextForTest
                // 头部行里出现的每一个时间片段都必须是可搜的
                if (header.contains(timeOnly)) {
                    assertEquals(
                        "[$width] 头部行可见时段 $timeOnly 必须可搜（header=$header）",
                        1,
                        filterBySearch(listOf(entry), timeOnly).size,
                    )
                }
                if (header.contains(full)) {
                    assertEquals(
                        "[$width] 头部行可见完整时间 $full 必须可搜",
                        1,
                        filterBySearch(listOf(entry), full).size,
                    )
                }
            }
        } finally {
            state.logTimestampEnabled = savedOn
            state.logTimestampFormat = savedFmt
            state.logListSqlDisplayMode = savedMode
        }
    }

    /** 头部行改版后，行号搜索仍按精确匹配工作（`#42` 与 `42` 都命中、`43` 不命中）。 */
    fun test头部行改版后_行号搜索仍精确匹配() {
        val entry = LogSqlEntry(
            rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(),
            timestamp = 1_700_000_000_000L, sequenceNumber = 42L,
        )
        assertEquals("#42 应命中", 1, filterBySearch(listOf(entry), "#42").size)
        assertEquals("42 应命中", 1, filterBySearch(listOf(entry), "42").size)
        assertEquals("43 不应命中", 0, filterBySearch(listOf(entry), "43").size)
    }

    /** 头部行改版后，时间戳开关关闭时不参与时间匹配（既有语义不回归）。 */
    fun test头部行改版后_时间戳关闭则不参与时间匹配() {
        val state = PluginSettings.getInstance().state
        val saved = state.logTimestampEnabled
        try {
            state.logTimestampEnabled = false
            val entry = LogSqlEntry(
                rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(),
                timestamp = 1_700_000_000_000L,
            )
            assertNull("关闭后无时间片段", timestampDisplayText(entry))
            assertEquals("关闭后时段查询不应命中", 0, filterBySearch(listOf(entry), "06:13:20").size)
        } finally {
            state.logTimestampEnabled = saved
        }
    }

    /** 自定义时间格式下，头部行可见文本同样可搜（防格式漂移）。 */
    fun test自定义时间格式_头部行可见文本可搜() {
        val state = PluginSettings.getInstance().state
        val savedOn = state.logTimestampEnabled
        val savedFmt = state.logTimestampFormat
        try {
            state.logTimestampEnabled = true
            val entry = LogSqlEntry(
                rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(),
                timestamp = 1_700_000_000_000L, logTimestamp = 1_700_000_000_000L,
            )
            for (fmt in listOf("MM/dd HH:mm:ss", "HH:mm:ss", "yyyyMMddHHmmss")) {
                state.logTimestampFormat = fmt
                val display = requireNotNull(timestampDisplayText(entry)).removeSurrounding("[", "]")
                assertEquals(
                    "格式 $fmt 下可见文本 $display 必须可搜",
                    1,
                    filterBySearch(listOf(entry), display).size,
                )
            }
        } finally {
            state.logTimestampEnabled = savedOn
            state.logTimestampFormat = savedFmt
        }
    }
    /**
     * 回归守卫（用户 2026-10-26 定案）：**时间显示格式严格跟随设置，不随宽度变化**。
     *
     * 曾实现过「窄宽度剥掉日期只留时刻」，被用户否决——设置里配的是
     * `yyyy-MM-dd HH:mm:ss`，宽栏窄栏就该都显示它。本用例在多个宽度下渲染同一份配置，
     * 断言可见时间片段逐字节一致。
     */
    fun test头部行时间格式不随宽度变化() {
        val state = PluginSettings.getInstance().state
        val savedOn = state.logTimestampEnabled
        val savedFmt = state.logTimestampFormat
        val savedMode = state.logListSqlDisplayMode
        try {
            state.logTimestampEnabled = true
            state.logListSqlDisplayMode = "KEYWORD_WRAP"
            val entry = LogSqlEntry(
                rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(),
                timestamp = 1_700_000_000_000L, logTimestamp = 1_700_000_000_000L,
                executionTimeMs = 5L, sequenceNumber = 42L,
                sourceFile = "src/main/java/SomeServiceImpl.java", sourceLine = 7,
            )
            for (fmt in listOf("yyyy-MM-dd HH:mm:ss", "MM/dd HH:mm:ss", "HH:mm:ss")) {
                state.logTimestampFormat = fmt
                val renderer = LogEntryListCellRenderer()
                var first: String? = null
                for (width in listOf(900, 420, 320, 280, 240, 200)) {
                    val list = javax.swing.JList<Any>().apply { setSize(width, 400) }
                    renderer.getListCellRendererComponent(list, entry, 0, false, false)
                    val header = renderer.metaTextForTest
                    // 提取头部行中的时间片段（方括号包裹）
                    val m = Regex("""\[[^\]]*]""").find(header)?.value
                    if (first == null) first = m
                    assertEquals(
                        "[$fmt @ ${width}px] 时间片段应与最宽栏一致（格式跟随设置，不随宽度变）: header=$header",
                        first,
                        m,
                    )
                }
            }
        } finally {
            state.logTimestampEnabled = savedOn
            state.logTimestampFormat = savedFmt
            state.logListSqlDisplayMode = savedMode
        }
    }
}
