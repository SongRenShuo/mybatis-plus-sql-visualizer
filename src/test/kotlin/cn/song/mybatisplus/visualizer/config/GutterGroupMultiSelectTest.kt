package cn.song.mybatisplus.visualizer.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SettingsConverge 阶段 2：6 个 gutter 来源分组开关合成一个多选控件后的**映射契约**测试。
 *
 * 纯逻辑（零平台依赖、进 testFast 快桶）：控件只是 6 个布尔的聚合展示，真正要钉住的是
 * [GutterGroupSwitches] 的映射——勾选态与 [PluginSettings.State] 六字段的往返、默认值保持、
 * 单组变更不影响其余五组、以及摘要/切换按钮的三态文案键。
 *
 * 不触碰 Swing：勾选框的 `isSelected` 与 [GutterGroupSwitches.flagsFrom] 一一对应，
 * 因此这里以 flags 列表代表勾选态（组件层 setFlags 直接写这 6 个布尔，无额外逻辑）。
 */
class GutterGroupMultiSelectTest {

    @Test
    fun `默认值保持_六组默认全开`() {
        val s = PluginSettings.State()
        assertEquals(
            "六组 gutter 分组默认应全开（设置收敛不得改任何默认值）",
            List(GutterGroupSwitches.COUNT) { true },
            GutterGroupSwitches.flagsFrom(s),
        )
        // 逐字段再钉一遍：聚合控件上线不得把其中任何一项的默认值改掉
        assertEquals(true, s.gutterGroupMapperXml)
        assertEquals(true, s.gutterGroupAnnotation)
        assertEquals(true, s.gutterGroupJpaHql)
        assertEquals(true, s.gutterGroupJdbc)
        assertEquals(true, s.gutterGroupInlineFallback)
        assertEquals(true, s.gutterGroupOther)
    }

    @Test
    fun `勾选取消往返_applyTo与flagsFrom互为逆运算`() {
        val s = PluginSettings.State()
        val mixed = listOf(true, false, true, false, false, true)
        GutterGroupSwitches.applyTo(s, mixed)
        assertEquals("写回后各字段应与勾选态逐项一致", mixed, GutterGroupSwitches.flagsFrom(s))
        // 再按相反方向走一遍，验证不是单向恒等
        val flipped = mixed.map { !it }
        GutterGroupSwitches.applyTo(s, flipped)
        assertEquals(flipped, GutterGroupSwitches.flagsFrom(s))
    }

    @Test
    fun `单组取消_其余五组与其他设置字段不受影响`() {
        val s = PluginSettings.State()
        // 哨兵：与本控件无关的设置字段
        s.showGutterIcon = true
        s.formatSql = false
        s.xmlSynHiEnabled = false
        s.editorShowWhitespaces = true

        GutterGroupSwitches.applyTo(s, listOf(true, true, true, false, true, true))

        assertFalse("仅 jdbc 组被取消", s.gutterGroupJdbc)
        assertTrue("mapperXml 组保持原值", s.gutterGroupMapperXml)
        assertTrue("annotation 组保持原值", s.gutterGroupAnnotation)
        assertTrue("jpaHql 组保持原值", s.gutterGroupJpaHql)
        assertTrue("inlineFallback 组保持原值", s.gutterGroupInlineFallback)
        assertTrue("other 组保持原值", s.gutterGroupOther)
        assertTrue("gutter 总开关不受分组写回影响", s.showGutterIcon)
        assertFalse("预览格式化开关不受分组写回影响", s.formatSql)
        assertFalse("语法高亮开关不受分组写回影响", s.xmlSynHiEnabled)
        assertTrue("空白符开关不受分组写回影响", s.editorShowWhitespaces)
    }

    @Test
    fun `isModified_与State一致为false任一不符为true`() {
        val s = PluginSettings.State()
        val defaults = GutterGroupSwitches.flagsFrom(s)
        assertFalse("未改动时不算 modified", GutterGroupSwitches.isModified(s, defaults))

        // 6 个位置逐一翻转，任一处不一致都必须判 modified（防某一路漏挂）
        defaults.indices.forEach { i ->
            val changed = defaults.toMutableList().also { it[i] = !it[i] }
            assertTrue("第 $i 组变化后应判 modified", GutterGroupSwitches.isModified(s, changed))
        }

        GutterGroupSwitches.applyTo(s, defaults.map { !it })
        assertFalse("控件与 State 同为全关时不算 modified", GutterGroupSwitches.isModified(s, defaults.map { !it }))
    }

    @Test
    fun `摘要与切换按钮_三态文案键与切换目标`() {
        val all = List(GutterGroupSwitches.COUNT) { true }
        val none = List(GutterGroupSwitches.COUNT) { false }
        val partial = listOf(true, true, false, false, false, false)

        assertTrue(GutterGroupSwitches.allSelected(all))
        assertTrue(GutterGroupSwitches.noneSelected(none))
        assertEquals("settings.gutterGroup.summary.all", GutterGroupSwitches.summaryKey(all))
        assertEquals("settings.gutterGroup.summary.none", GutterGroupSwitches.summaryKey(none))
        assertEquals("settings.gutterGroup.summary.partial", GutterGroupSwitches.summaryKey(partial))
        assertEquals("部分态摘要参数应为（已启用数, 总数）", listOf(2, 6), GutterGroupSwitches.summaryArgs(partial).toList())

        assertEquals("全勾时按钮提示全不选", "settings.gutterGroup.selectNone", GutterGroupSwitches.toggleKey(all))
        assertEquals("未全勾时按钮提示全选", "settings.gutterGroup.selectAll", GutterGroupSwitches.toggleKey(partial))
        assertFalse("全勾时切换目标应为全关", GutterGroupSwitches.toggleTarget(all))
        assertTrue("未全勾时切换目标应为全开", GutterGroupSwitches.toggleTarget(partial))
    }

    @Test
    fun `文案键清单与六个Boolean字段一一对应`() {
        val keys = GutterGroupSwitches.TEXT_KEYS
        assertEquals("分组数为 6", GutterGroupSwitches.COUNT, keys.size)
        assertEquals("文案键不得重复", keys.size, keys.toSet().size)
        // 顺序即 GutterGroups.GROUPS 的呈现顺序
        assertEquals(
            listOf(
                "settings.gutterGroup.mapperXml", "settings.gutterGroup.annotation",
                "settings.gutterGroup.jpaHql", "settings.gutterGroup.jdbc",
                "settings.gutterGroup.inlineFallback", "settings.gutterGroup.other",
            ),
            keys,
        )
    }
}
