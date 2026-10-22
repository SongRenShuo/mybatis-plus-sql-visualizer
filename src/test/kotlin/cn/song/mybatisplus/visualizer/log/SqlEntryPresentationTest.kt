package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** T-Community 邻域：列表条目共同元信息、耗时五档分档与子句概览裁切（对齐 master 冻结用例）。 */
class SqlEntryPresentationTest : BasePlatformTestCase() {

    @Test
    fun test共同元信息顺序与日志独有字段() {
        val entry = LogSqlEntry("SELECT 1", "SELECT 1", emptyList(), 1L,
            null, LogSqlEntry.summarizeSql("SELECT 1"), SqlSource.UNKNOWN, 21L,
            "src/main/OrderService.java", 42, emptyList(), emptyList(), null, 17L,
            null, null, null, 2L, "PostgreSQL")
        val settings = PluginSettings.getInstance()
            val old = settings.getState().getLogTimestampEnabled()
        try {
            settings.getState().setLogTimestampEnabled(true)
            val common = SqlEntryPresentation.metadata(entry, false, true, emptyMap())
            val log = SqlEntryPresentation.metadata(entry, false, true, emptyMap(), java.util.Map.of(), true)
            val commonTexts = common.map { it.text() }
            assertEquals("#17 ", commonTexts.first())
            assertTrue(commonTexts.indexOfFirst { it.contains("21ms") } < commonTexts.indexOfFirst { it.contains("PostgreSQL") })
            assertTrue(commonTexts.last().contains("OrderService.java:42"))
            assertEquals("共同片段顺序不得随日志独有字段改变",
                commonTexts.dropLast(1), log.map { it.text() }.take(commonTexts.size - 1))
            assertTrue(log.any { it.text().contains("2 rows") })
        } finally {
            settings.getState().setLogTimestampEnabled(old)
        }
    }

    @Test
    fun test列表耗时按阈值分档且无耗时保持灰色() {
        val settings = PluginSettings.getInstance()
        val oldThreshold = settings.getState().getSlowQueryThresholdMs()
        try {
            settings.getState().setSlowQueryThresholdMs(1000)
            fun duration(ms: Long?, selected: Boolean = false): SqlTextFragment {
                val entry = LogSqlEntry("SELECT 1", "SELECT 1", emptyList(), System.currentTimeMillis(),
                    null, LogSqlEntry.summarizeSql("SELECT 1"), SqlSource.UNKNOWN, ms,
                    null, 0, emptyList(), emptyList(), null, 0L,
                    null, null, null, null, null)
                return SqlEntryPresentation.metadata(entry, selected, true, emptyMap())
                    .single { it.text() == (if (ms != null) "${ms}ms " else "— ") }
            }
            assertSame(MpvsUiColors.DURATION_OK, duration(1).attributes().fgColor)
            assertSame(MpvsUiColors.DURATION_OK, duration(499).attributes().fgColor)
            assertSame(MpvsUiColors.DURATION_WARN, duration(500).attributes().fgColor)
            assertSame(MpvsUiColors.DURATION_SLOW, duration(1000).attributes().fgColor)
            assertSame(MpvsUiColors.DURATION_NONE, duration(null).attributes().fgColor)
            assertSame(MpvsUiColors.DURATION_SELECTED, duration(1, selected = true).attributes().fgColor)
        } finally {
            settings.getState().setSlowQueryThresholdMs(oldThreshold)
        }
    }

    @Test
    fun test子句概览最多三行且标记剩余行数() {
        val lines = (1..5).map { listOf(SqlTextFragment("line $it", SimpleTextAttributes.REGULAR_ATTRIBUTES)) }
        val preview = SqlEntryPresentation.visibleSqlLines(lines, SqlListDisplayMode.KEYWORD_WRAP)
        assertEquals(3, preview.size)
        assertEquals("line 1", preview[0].single().text())
        assertEquals("line 2", preview[1].single().text())
        assertEquals("… (+3)", preview[2].single().text())
        assertSame(lines, SqlEntryPresentation.visibleSqlLines(lines, SqlListDisplayMode.FORMATTED))
    }

    @Test
    fun test宽度裁切保留片段属性与完整模型内容() {
        val attrs = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Color.BLUE)
        val entry = LogSqlEntry("SELECT 😀 FROM t", "SELECT 😀 FROM t", emptyList())
        val fragments = listOf(SqlTextFragment("SELECT ", attrs), SqlTextFragment("😀 FROM t", attrs))
        val clipped = SqlEntryPresentation.clipSqlFragments(fragments, 90) { text, _ ->
            text.codePointCount(0, text.length) * 10
        }
        val visible = clipped.joinToString("") { it.text() }
        assertTrue(visible.endsWith("…"))
        assertFalse(visible.any { Character.isSurrogate(it) && !visible.contains("😀") })
        assertTrue(clipped.all { it.attributes() === attrs })
        assertEquals("SELECT 😀 FROM t", entry.restoredSql())
    }
}
