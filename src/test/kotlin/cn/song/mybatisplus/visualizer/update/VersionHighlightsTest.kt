package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEW-19：版本亮点通知纯逻辑测试（非沙盒，testFast 桶）。
 *
 * 守卫目标 = 防打扰三重约束的可测部分：
 *  - headless 静默（单测进程不弹、不写记忆）；
 *  - 版本粒度（同版本不重弹，版本变化才弹）；
 *  - 未登记版本静默（不自动摘要 changelog、不编造亮点）；
 *  - 正文转义（changelog 文案可能含 `<`/`&`）。
 */
class VersionHighlightsTest {

    @Test
    fun testHeadless静默() {
        assertFalse(
            "headless 下必须静默（测试进程不弹通知）",
            VersionHighlights.shouldNotify("2026.10.31", "", unitTestMode = true),
        )
    }

    @Test
    fun test版本粒度同版本不重弹() {
        assertFalse(
            "同版本已提示过 → 不再弹",
            VersionHighlights.shouldNotify("2026.10.31", "2026.10.31", unitTestMode = false),
        )
        assertTrue(
            "版本变化 → 允许再提示一次",
            VersionHighlights.shouldNotify("2026.11.01", "2026.10.31", unitTestMode = false),
        )
        assertTrue(
            "首次安装（空记忆）→ 提示一次",
            VersionHighlights.shouldNotify("2026.10.31", "", unitTestMode = false),
        )
    }

    @Test
    fun test当前版本未知时不弹() {
        assertFalse(VersionHighlights.shouldNotify(null, "", unitTestMode = false))
        assertFalse(VersionHighlights.shouldNotify("  ", "", unitTestMode = false))
    }

    @Test
    fun test未登记版本静默() {
        assertTrue(
            "未登记亮点的版本必须静默（宁缺勿滥）",
            VersionHighlights.highlightsFor("2099.01.01").isEmpty(),
        )
        assertTrue(VersionHighlights.highlightsFor(null).isEmpty())
    }

    @Test
    fun test本版登记二到三条且各有直达入口() {
        val highlights = VersionHighlights.highlightsFor("2026.10.31")
        assertTrue("每版 2~3 条用户可感知亮点，实际=${highlights.size}", highlights.size in 2..3)
        assertTrue(
            "每条亮点必须带直达入口",
            highlights.all { it.target in HighlightTarget.values() },
        )
        assertTrue(
            "正文应能解析出非空文案（bundle 键在场）",
            highlights.all { cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message(it.textKey) != it.textKey },
        )
    }

    @Test
    fun test正文渲染为列表并转义() {
        val text = VersionHighlights.contentText(
            "2026.10.31",
            listOf(VersionHighlight("update.highlights.2026_10_31.dialects", HighlightTarget.SETTINGS)),
        )
        assertTrue("应含版本行：$text", text.contains("2026.10.31"))
        assertTrue("应含有序/无序列表：$text", text.contains("<ul>") && text.contains("<li>"))
        assertEquals("HTML 三字符必须转义", "&lt;a&gt;&amp;", VersionHighlights.escapeHtml("<a>&"))
    }

    @Test
    fun test动作文案键齐全() {
        HighlightTarget.values().forEach { target ->
            val key = VersionHighlights.actionLabelKey(target)
            assertTrue(
                "动作文案键缺失：$target → $key",
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message(key) != key,
            )
        }
    }
}
