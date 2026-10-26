package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color

class SqlEntryPresentationTest : BasePlatformTestCase() {
    fun testUI013同结构计数保留但Sharding不借用其他来源去重徽章() {
        val entry = LogSqlEntry(rawSql = "SELECT * FROM t WHERE id = ?",
            restoredSql = "SELECT * FROM t WHERE id = ?", params = emptyList(), source = SqlSource.SHARDINGSPHERE)
        val templates = structureKeyCounts(List(TEMPLATE_BADGE_MIN_COUNT) { entry.copy(actualSql = "ds_$it ::: SELECT ? ::: [$it]") })
        val drops = mapOf(entry.dedupeKey to 7)
        val sharding = SqlEntryPresentation.metadata(entry, false, false, templates, drops, true)
        val normal = SqlEntryPresentation.metadata(entry.copy(source = SqlSource.MYBATIS), false, false, templates, drops, true)
        assertFalse(sharding.any { it.text == "⨉7 " })
        assertTrue(normal.any { it.text == "⨉7 " })
        val template = formatTemplateCountFragment(entry, templates, false)
        assertNotNull(template)
        assertTrue(sharding.any { it.text == template!!.first })
        assertEquals(TEMPLATE_BADGE_MIN_COUNT, templates[entry.structureKey])
    }

    fun test共同元信息顺序与日志独有字段() {
        val entry = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            timestamp = 1L,
            sequenceNumber = 17,
            executionTimeMs = 21,
            databaseName = "PostgreSQL",
            sourceFile = "src/main/OrderService.java",
            sourceLine = 42,
            updateCount = 2,
        )
        val settings = PluginSettings.getInstance()
        val old = settings.state.logTimestampEnabled
        try {
            settings.state.logTimestampEnabled = true
            val common = SqlEntryPresentation.metadata(entry, false, true, emptyMap())
            val log = SqlEntryPresentation.metadata(entry, false, true, emptyMap(), showLogDetails = true)
            val commonTexts = common.map { it.text }
            assertEquals("#17 ", commonTexts.first())
            assertTrue(commonTexts.indexOfFirst { it.contains("21ms") } < commonTexts.indexOfFirst { it.contains("PostgreSQL") })
            assertTrue(commonTexts.last().contains("OrderService.java:42"))
            assertEquals("共同片段顺序不得随日志独有字段改变", commonTexts.dropLast(1), log.map { it.text }.take(commonTexts.size - 1))
            assertTrue(log.any { it.text.contains("2 rows") })
        } finally {
            settings.state.logTimestampEnabled = old
        }
    }

    fun test列表耗时按阈值分档且无耗时保持灰色() {
        val settings = PluginSettings.getInstance()
        val oldThreshold = settings.state.slowQueryThresholdMs
        try {
            settings.state.slowQueryThresholdMs = 1000
            fun duration(ms: Long?, selected: Boolean = false): SqlTextFragment {
                val entry = LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(), executionTimeMs = ms)
                return SqlEntryPresentation.metadata(entry, selected, true, emptyMap())
                    .single { it.text == (ms?.let { value -> "${value}ms " } ?: "— ") }
            }
            assertSame(MpvsUiColors.DURATION_OK, duration(1).attributes.fgColor)
            assertSame(MpvsUiColors.DURATION_OK, duration(499).attributes.fgColor)
            assertSame(MpvsUiColors.DURATION_WARN, duration(500).attributes.fgColor)
            assertSame(MpvsUiColors.DURATION_SLOW, duration(1000).attributes.fgColor)
            assertSame(MpvsUiColors.DURATION_NONE, duration(null).attributes.fgColor)
            assertSame(MpvsUiColors.DURATION_SELECTED, duration(1, selected = true).attributes.fgColor)
        } finally {
            settings.state.slowQueryThresholdMs = oldThreshold
        }
    }

    fun test子句概览三行实内容_剩余计数作末行尾巴() {
        // T-LogListRail 改判：原「前 2 行 + 第三行整行占位符」只读到 2 个子句，
        // 用户实测反馈 WHERE 之后全不可见。现改为 3 个子句全渲染，剩余计数附在末行行尾。
        val lines = (1..5).map { listOf(SqlTextFragment("line $it", SimpleTextAttributes.REGULAR_ATTRIBUTES)) }
        val preview = visibleSqlLines(lines, SqlListDisplayMode.KEYWORD_WRAP)
        assertEquals("仍为 3 行（行高不变）", 3, preview.size)
        assertEquals("第 1 个子句", "line 1", preview[0].single().text)
        assertEquals("第 2 个子句", "line 2", preview[1].single().text)
        assertEquals("第 3 个子句 + 剩余计数尾巴", "line 3 … (+2)", preview[2].joinToString("") { it.text })
        assertSame("格式化模式不封顶", lines, visibleSqlLines(lines, SqlListDisplayMode.FORMATTED))
    }

    fun test子句概览_恰三行时不加尾巴() {
        val lines = (1..3).map { listOf(SqlTextFragment("line $it", SimpleTextAttributes.REGULAR_ATTRIBUTES)) }
        val preview = visibleSqlLines(lines, SqlListDisplayMode.KEYWORD_WRAP)
        assertEquals(3, preview.size)
        assertTrue("无隐藏行时不出现尾巴", preview.none { it.any { f -> f.text.contains("(+") } })
    }

    fun test子句概览_三行尾巴不再是整行占位符() {
        // 反向守卫：禁止退回「第三行只剩 … (+N)、实内容全丢」的旧形态
        val lines = (1..7).map { listOf(SqlTextFragment("clause$it", SimpleTextAttributes.REGULAR_ATTRIBUTES)) }
        val preview = visibleSqlLines(lines, SqlListDisplayMode.KEYWORD_WRAP)
        assertEquals("末行必须含第三个子句实内容", "clause3 … (+4)", preview[2].joinToString("") { it.text })
    }

    fun test宽度裁切保留片段属性与完整模型内容() {
        val attrs = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Color.BLUE)
        val entry = LogSqlEntry(rawSql = "SELECT 😀 FROM t", restoredSql = "SELECT 😀 FROM t", params = emptyList())
        val fragments = listOf(SqlTextFragment("SELECT ", attrs), SqlTextFragment("😀 FROM t", attrs))
        val clipped = clipSqlFragments(fragments, 90) { text, _ -> text.codePointCount(0, text.length) * 10 }
        val visible = clipped.joinToString("") { it.text }
        assertTrue(visible.endsWith("…"))
        assertFalse(visible.any { Character.isSurrogate(it) && !visible.contains("😀") })
        assertTrue(clipped.all { it.attributes === attrs })
        assertEquals("SELECT 😀 FROM t", entry.restoredSql)
    }
}
