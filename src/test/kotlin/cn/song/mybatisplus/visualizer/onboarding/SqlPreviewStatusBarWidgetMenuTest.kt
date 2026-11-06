package cn.song.mybatisplus.visualizer.onboarding

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * F-10（widget 点击菜单化）判别测试：
 *
 *  1. **菜单纯构建矩阵**（[buildWidgetMenuItems] 零平台依赖）：首项恒为「打开 SQL 日志面板」
 *     （原点击行为的落位）、暂停项随增强通道开关与暂停态出现/消失与改标、设置页恒在；
 *  2. **点击钩子在位**：widget 的 `getClickConsumer()` 不得为 null——摘除点击钩子本用例恰红；
 *  3. 动作执行面（工具窗 show / 暂停位翻转 / 设置页对话框）是真机步骤，登记计划 §实施记录。
 *
 * 反向验证：摘 [SqlPreviewStatusBarWidget.getClickConsumer]（返回 no-op）→ 点击钩子用例红。
 */
class SqlPreviewStatusBarWidgetMenuTest : BasePlatformTestCase() {

    // ---------- 菜单纯构建 ----------

    fun `test菜单首项恒为打开日志面板且设置页恒在`() {
        for (state in listOf(
            WidgetMenuState(enhancedEnabled = true, paused = false),
            WidgetMenuState(enhancedEnabled = false, paused = false),
        )) {
            val items = buildWidgetMenuItems(state)
            assertTrue("菜单不得为空", items.isNotEmpty())
            assertEquals("首项=原点击行为保留", WidgetMenuAction.OPEN_LOG_PANEL, items.first().action)
            assertEquals("末项=设置页", WidgetMenuAction.OPEN_SETTINGS, items.last().action)
        }
    }

    fun `test暂停项随增强通道开关出现与消失`() {
        val on = buildWidgetMenuItems(WidgetMenuState(enhancedEnabled = true, paused = false))
        assertEquals("增强开启：打开日志/暂停/设置三项", 3, on.size)
        assertEquals(WidgetMenuAction.TOGGLE_PAUSE, on[1].action)

        val off = buildWidgetMenuItems(WidgetMenuState(enhancedEnabled = false, paused = false))
        assertEquals("增强关闭：暂停位无意义不出项", 2, off.size)
        assertTrue("关闭态不得出现暂停项", off.none { it.action == WidgetMenuAction.TOGGLE_PAUSE })
    }

    fun `test暂停项标签随暂停态取反`() {
        val paused = buildWidgetMenuItems(WidgetMenuState(enhancedEnabled = true, paused = true))
        val running = buildWidgetMenuItems(WidgetMenuState(enhancedEnabled = true, paused = false))
        assertTrue("暂停中 → 恢复捕获", paused[1].label != running[1].label)
        assertTrue("两条标签都非空", paused[1].label.isNotBlank() && running[1].label.isNotBlank())
    }

    // ---------- 点击钩子在位（反向验证锚点） ----------

    fun `test点击钩子在位且菜单非空`() {
        val widget = SqlPreviewStatusBarWidgetFactory().createWidget(project)
        // 平台 StatusBarWidget 是 Kotlin 声明接口（javap 实证）：getPresentation/getClickConsumer
        // 均为显式函数，不走属性语法
        val presentation = widget.getPresentation()
        assertNotNull(
            "摘除点击钩子本用例恰红：getClickConsumer 不得为 null",
            presentation?.getClickConsumer(),
        )
        widget.dispose()
    }

    fun `test点击处理经菜单构建_摘构建即空集恰红`() {
        // 工厂声明返回 StatusBarWidget，取实现类才能触达内部缝
        val widget = SqlPreviewStatusBarWidgetFactory().createWidget(project) as SqlPreviewStatusBarWidget
        // MouseEvent 的 source 不能为 null（AWT 抛 IllegalArgumentException）；未显示的组件
        // 走 showInFocusCenter 分支，且弹出被 runCatching 包住——headless 只观察菜单项
        val anchor = javax.swing.JLabel()
        val e = java.awt.event.MouseEvent(
            anchor, java.awt.event.MouseEvent.MOUSE_CLICKED, 0L, 0, 0, 0, 1, false,
        )
        val items = widget.handleWidgetClick(e)
        assertTrue("点击必须经过菜单构建（摘 buildWidgetMenuItems 调用本用例恰红）", items.isNotEmpty())
        assertEquals("首项=原点击行为保留", WidgetMenuAction.OPEN_LOG_PANEL, items.first().action)
        widget.dispose()
    }
}
