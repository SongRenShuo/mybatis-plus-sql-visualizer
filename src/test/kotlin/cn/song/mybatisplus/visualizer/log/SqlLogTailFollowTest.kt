package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.event.AdjustmentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities

class SqlLogTailFollowTest : BasePlatformTestCase() {
    fun testClearActionTemplateHasPlatformClearIcon() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            val action = panel.toolbarGroup.getChildren(null)[2]
            assertTrue("清除动作仍须位于工具栏第三位", action is SqlLogPanel.ClearAction)
            assertSame("首次 update 前模板已有平台清除图标", MpvsIcons.Clear, action.templatePresentation.icon)
            assertTrue(action.templatePresentation.isVisible)
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    fun testClearActionUpdateRestoresPlatformClearIcon() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            val action = panel.toolbarGroup.getChildren(null)[2]
            assertTrue(action is SqlLogPanel.ClearAction)
            val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT)
            // 不让模板继承掩盖 update 漏赋图标；模拟多轮 presentation 刷新。
            repeat(3) { round ->
                if (round == 1) {
                    manager.entries.addEntry(LogSqlEntry("SELECT 1", "SELECT 1", emptyList(), timestamp = 1L))
                } else if (round == 2) {
                    action.actionPerformed(event)
                }
                event.presentation.icon = if (round == 1) AllIcons.Actions.Search else null
                action.update(event)
                assertSame("空列表、有数据、清除后均应恢复清除图标", MpvsIcons.Clear, event.presentation.icon)
                assertEquals(MpSqlVisualizerBundle.message("log.panel.clear"), event.presentation.text)
                assertTrue("清除入口应始终可见", event.presentation.isVisible)
                assertTrue("清除入口应可用", event.presentation.isEnabled)
            }
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    fun testClearActionClearsEntriesAndResumesTailFollow() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.dedupeEnabled = false
            manager.entries.addEntry(LogSqlEntry("SELECT 1", "SELECT 1", emptyList(), timestamp = 1L))
            manager.entries.addEntry(LogSqlEntry("SELECT 2", "SELECT 2", emptyList(), timestamp = 2L))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("夹具须先有两条实际显示的日志", 2, panel.list.model.size)
            val scroll = requireNotNull(panel.listScrollPaneForTest)
            scroll.mouseWheelListeners.forEach {
                it.mouseWheelMoved(MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL, 1L, 0, 5, 5, 0,
                    false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1))
            }
            assertEquals(TailFollowMode.PAUSED, panel.tailFollowModeForTest)

            val action = panel.toolbarGroup.getChildren(null)[2]
            assertTrue(action is SqlLogPanel.ClearAction)
            val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT)
            action.actionPerformed(event)
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertTrue("清除动作须清空实际缓冲", manager.entries.snapshot().isEmpty())
            assertEquals("可见列表同步清空", 0, panel.list.model.size)
            assertEquals("清除后恢复尾随", TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)

            action.actionPerformed(event)
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertTrue("空列表重复清除仍为空", manager.entries.snapshot().isEmpty())
            assertEquals(TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)
            manager.entries.addEntry(LogSqlEntry("SELECT 3", "SELECT 3", emptyList(), timestamp = 3L))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("清除后仍能显示新日志", listOf("SELECT 3"), panel.visibleEntries)
            assertEquals(TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    /**
     * 状态机本身的转换（T-ListMetaRevert 起：滚到底也会恢复，见下一条）。
     *
     * 本条只验纯状态机：手动滚动 → PAUSED；onScrollToEnd → FOLLOWING；clear → FOLLOWING。
     */
    fun test状态机转换_手动滚动暂停_滚底与清除恢复() {
        val state = TailFollowState()
        assertEquals(TailFollowMode.FOLLOWING, state.mode)
        state.onUserScroll()
        assertEquals(TailFollowMode.PAUSED, state.mode)
        assertFalse(state.shouldFollow)
        state.onScrollToEnd()
        assertTrue(state.shouldFollow)
        state.onUserScroll()
        state.onClear()
        assertTrue(state.shouldFollow)
    }

    fun test滚轮滚动条与键盘暂停_滚回底部恢复跟随() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            val scroll = requireNotNull(panel.listScrollPaneForTest)
            val wheel = MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL, 1L, 0, 10, 10, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1)
            scroll.mouseWheelListeners.forEach { it.mouseWheelMoved(wheel) }
            assertEquals("一格上滚也须暂停", TailFollowMode.PAUSED, panel.tailFollowModeForTest)

            panel.resumeTailFollowForTest()
            assertEquals(TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)
            val bar = scroll.verticalScrollBar
            val press = MouseEvent(bar, MouseEvent.MOUSE_PRESSED, 2L, 0, 2, 2, 1, false)
            bar.mouseListeners.forEach { it.mousePressed(press) }
            assertEquals("拖动或点击滚动条须暂停", TailFollowMode.PAUSED, panel.tailFollowModeForTest)
            // T-ListMetaRevert（用户 2026-10-26）：滚回底部必须恢复自动跟随。
            // 此前的实现只有「滚动到底部」按钮能恢复，用户滚回去后还得再点一下按钮，
            // 违反「滚到底就继续跟随」的直觉。
            val maxScrollable = bar.maximum - bar.visibleAmount
            bar.value = maxOf(0, maxScrollable)
            bar.adjustmentListeners.forEach {
                it.adjustmentValueChanged(
                    AdjustmentEvent(bar, AdjustmentEvent.ADJUSTMENT_VALUE_CHANGED, AdjustmentEvent.TRACK, bar.value),
                )
            }
            assertEquals("滚回底部应恢复自动跟随", TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)

            panel.resumeTailFollowForTest()
            val key = KeyEvent(panel.list, KeyEvent.KEY_PRESSED, 3L, 0, KeyEvent.VK_PAGE_UP, KeyEvent.CHAR_UNDEFINED)
            panel.list.keyListeners.forEach { it.keyPressed(key) }
            assertEquals("键盘导航也须暂停", TailFollowMode.PAUSED, panel.tailFollowModeForTest)
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    fun test暂停后新SQL不抢走实际滚动视口() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            panel.setSize(460, 420)
            fun layout(component: java.awt.Component) {
                if (component is java.awt.Container) {
                    component.doLayout()
                    component.components.forEach(::layout)
                }
            }
            layout(panel)
            for (index in 1..80) {
                manager.entries.addEntry(LogSqlEntry("SELECT $index", "SELECT $index", emptyList(), timestamp = index.toLong()))
            }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            layout(panel)
            val scroll = requireNotNull(panel.listScrollPaneForTest)
            val bar = scroll.verticalScrollBar
            assertTrue("夹具必须有真实可滚动内容", bar.maximum > bar.visibleAmount + 100)
            panel.resumeTailFollowForTest()
            bar.value = 80
            val wheel = MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL, 4L, 0, 5, 5, 0,
                false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1)
            scroll.mouseWheelListeners.forEach { it.mouseWheelMoved(wheel) }
            val anchored = bar.value
            assertEquals(TailFollowMode.PAUSED, panel.tailFollowModeForTest)
            manager.entries.addEntry(LogSqlEntry("SELECT 81", "SELECT 81", emptyList(), timestamp = 81L))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("新增条目后用户视口不能被拉回底部", anchored, bar.value)
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    fun test滚底动作对象能从暂停态恢复并真正贴底() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            panel.setSize(460, 420)
            fun layout(component: java.awt.Component) {
                if (component is java.awt.Container) {
                    component.doLayout()
                    component.components.forEach(::layout)
                }
            }
            layout(panel)
            for (index in 1..80) {
                manager.entries.addEntry(LogSqlEntry("SELECT $index", "SELECT $index", emptyList(), timestamp = index.toLong()))
            }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            layout(panel)
            val scroll = requireNotNull(panel.listScrollPaneForTest)
            val bar = scroll.verticalScrollBar
            assertTrue("夹具必须有真实可滚动内容", bar.maximum > bar.visibleAmount + 100)
            bar.value = 0
            scroll.mouseWheelListeners.forEach {
                it.mouseWheelMoved(MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL, 9L, 0, 5, 5, 0,
                    false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1))
            }
            assertEquals(TailFollowMode.PAUSED, panel.tailFollowModeForTest)

            val action = panel.scrollToEndAction
            action.actionPerformed(
                AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT),
            )
            assertEquals("滚底动作应把状态恢复为跟随", TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)
            assertTrue(
                "滚底动作应真正把视口贴到列表底（value=${bar.value} max=${bar.maximum} extent=${bar.visibleAmount}）",
                bar.value + bar.visibleAmount >= bar.maximum - 1,
            )
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    fun test缓冲已满并淘汰头部时仍跟随新SQL() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.maxEntries = 80
            panel.setSize(460, 420)
            fun layout(component: java.awt.Component) {
                if (component is java.awt.Container) {
                    component.doLayout()
                    component.components.forEach(::layout)
                }
            }
            layout(panel)
            for (index in 1..80) {
                manager.entries.addEntry(LogSqlEntry("SELECT $index", "SELECT $index", emptyList(), timestamp = index.toLong()))
            }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            layout(panel)
            val bar = requireNotNull(panel.listScrollPaneForTest).verticalScrollBar
            assertTrue("夹具须有可滚动内容", bar.maximum > bar.visibleAmount + 100)
            assertEquals(80, panel.list.model.size)
            panel.resumeTailFollowForTest()
            bar.value = 0
            assertEquals(TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)

            manager.entries.addEntry(LogSqlEntry("SELECT 81", "SELECT 81", emptyList(), timestamp = 81L))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            layout(panel)
            assertEquals("新 SQL 淘汰旧行后列表长度不变", 80, panel.list.model.size)
            assertTrue("跟随态仍须贴底（value=${bar.value}, max=${bar.maximum}, extent=${bar.visibleAmount}）",
                bar.value + bar.visibleAmount >= bar.maximum - 1)
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    /**
     * UI012 调查保留的完整容器几何回归：完整面板（JBTabs → previewHost → scrollPane → list），不是裸 JList。
     * 不开 JFrame、不驱动 GUI。测试比例不等同于原生 Zoom/LAF；通过仅能说明此夹具未复现。
     * 已知差异：settle 每步递归四次 doLayout，可能主动补齐真机缺失的布局，不能作为现场根因证据。
     * 100%/125% 此前跑出的实际行高均为107，比例设置不等价于原生Zoom的字体/LAF变化。
     * 虽未调用 updateUI/revalidate 或重新设置行高，仍不是零干预观察。
     * 本轮 UI012 已定位为时间关联错序；此几何夹具不是该根因的复现或修复证明，临时生产诊断已移除。
     */
    fun testUi012FullContainerGeometryAt100Percent() = checkUi012Geometry(1.0f)

    fun testUi012FullContainerGeometryAt125Percent() = checkUi012Geometry(1.25f)

    private fun checkUi012Geometry(scale: Float) {
        val settings = PluginSettings.getInstance().state
        val savedMode = settings.logListSqlDisplayMode
        // T-MagpieGems W2：脱敏语义由 displayMaskMode 承载
        val savedMask = settings.displayMaskMode
        val savedScale = JBUIScale.scale(1.0f)
        settings.logListSqlDisplayMode = SqlListDisplayMode.KEYWORD_WRAP.name
        settings.displayMaskMode = "OFF"
        JBUIScale.setUserScaleFactorForTest(scale)
        var manager: SqlLogManager? = null
        var panel: SqlLogPanel? = null
        try {
            val logManager = SqlLogManager(project).also { manager = it }
            logManager.entries.maxEntries = 200
            logManager.entries.dedupeEnabled = false
            logManager.entries.setPaused(false)
            val logPanel = SqlLogPanel(project, logManager).also { panel = it }
            val root = JPanel(BorderLayout()).apply {
                add(logPanel, BorderLayout.CENTER)
                setSize(560, 520)
            }
            val scroll = requireNotNull(logPanel.listScrollPaneForTest)
            val list = logPanel.list
            assertTrue("必须保留完整面板的滚动容器层级", SwingUtilities.isDescendingFrom(scroll, root))
            assertEquals("四行模式必须走可变行高而不是固定高假设", -1, list.fixedCellHeight)
            assertEquals(scale, JBUIScale.scale(1.0f), 0.001f)

            fun settle(stage: String): String {
                // 有界排空+完整树布局，不 sleep、不重装 ListUI、不改 scroll model。
                repeat(4) {
                    UIUtil.dispatchAllInvocationEvents()
                    layoutGeometryTree(root)
                }
                return ui012Geometry(stage, logPanel, logManager).also { println(it) }
            }

            fun addRows(ids: IntRange) {
                ids.forEach { id ->
                    val sql = "SELECT id, name FROM ui012_probe WHERE id = $id"
                    assertTrue("测试条目必须真实入缓冲 id=$id", logManager.entries.addEntry(
                        LogSqlEntry(sql, sql, emptyList(), timestamp = id.toLong()),
                    ))
                }
            }

            fun scrollToEnd(stage: String) {
                val action = logPanel.scrollToEndAction
                action.actionPerformed(AnActionEvent.createFromAnAction(
                    action, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT,
                ))
                val evidence = settle(stage)
                val last = requireNotNull(list.getCellBounds(list.model.size - 1, list.model.size - 1))
                val visible = list.visibleRect
                assertTrue("夹具必须拥有非零视口\n$evidence", visible.width > 0 && visible.height > 0)
                assertTrue("尾条必须真正完整可见，不能只验滚动条等式\n$evidence",
                    last.y >= visible.y && last.y + last.height <= visible.y + visible.height)
                assertTrue("最后一行不能落在列表实际尺寸之外\n$evidence",
                    last.y + last.height <= list.height)
            }

            settle("created")
            addRows(1..468)
            val full = settle("capacity-200-after-468")
            assertEquals(full, 200, list.model.size)
            val clear = logPanel.toolbarGroup.getChildren(null).filterIsInstance<SqlLogPanel.ClearAction>().single()
            clear.actionPerformed(AnActionEvent.createFromAnAction(
                clear, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT,
            ))
            val cleared = settle("cleared")
            assertEquals(cleared, 0, list.model.size)
            addRows(1..8)
            settle("first-eight")
            addRows(9..10)
            val ten = settle("append-nine-ten")
            assertEquals(ten, (1L..10L).toList(), (0 until list.model.size).map {
                list.model.getElementAt(it).sequenceNumber
            })
            // 明确复刻 metadata + SELECT/FROM/WHERE；避免单行样例给出无关绿灯。
            val renderer = LogEntryListCellRenderer()
            renderer.getListCellRendererComponent(list, list.model.getElementAt(0), 0, false, false)
            assertEquals("夹具 SQL 必须渲染成三行，加 metadata 共四行", 3, renderer.sqlLines.size)
            scrollToEnd("ten-explicit-end")

            logPanel.searchFieldForTest.text = "id = 9"
            logPanel.applySearchTextForTest()
            val search = settle("search-nine")
            assertEquals(search, 1, list.model.size)
            assertEquals(search, 9L, list.model.getElementAt(0).sequenceNumber)
            logPanel.searchFieldForTest.text = ""
            logPanel.applySearchTextForTest()
            val restored = settle("clear-search")
            assertEquals(restored, 10, list.model.size)
            scrollToEnd("clear-search-explicit-end")

            root.setSize(560, 720)
            settle("taller-host")
            scrollToEnd("taller-host-explicit-end")
        } finally {
            try {
                panel?.dispose()
            } finally {
                try {
                    manager?.dispose()
                } finally {
                    settings.logListSqlDisplayMode = savedMode
                    settings.displayMaskMode = savedMask
                    JBUIScale.setUserScaleFactorForTest(savedScale)
                }
            }
        }
    }

    private fun layoutGeometryTree(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutGeometryTree)
        }
    }

    private fun ui012Geometry(stage: String, panel: SqlLogPanel, manager: SqlLogManager): String {
        val list = panel.list
        val scroll = requireNotNull(panel.listScrollPaneForTest)
        val viewport = scroll.viewport
        val bar = scroll.verticalScrollBar
        val size = list.model.size
        return buildString {
            appendLine("UI012 stage=$stage scale=${JBUIScale.scale(1.0f)} buffer=${manager.entries.size()} model=$size")
            appendLine("ids=${(0 until size).map { list.model.getElementAt(it).sequenceNumber }}")
            appendLine("ui=${list.ui.javaClass.name} fixed=${list.fixedCellHeight} visibleRows=${list.visibleRowCount}")
            appendLine("first=${if (size == 0) null else list.getCellBounds(0, 0)} last=${if (size == 0) null else list.getCellBounds(size - 1, size - 1)}")
            appendLine("list preferred=${list.preferredSize} size=${list.size} visible=${list.visibleRect}")
            appendLine("viewport view=${viewport.viewSize} extent=${viewport.extentSize} position=${viewport.viewPosition}")
            appendLine("bar value=${bar.value} maximum=${bar.maximum} extent=${bar.visibleAmount}")
            var ancestor: Component? = list
            while (ancestor != null) {
                appendLine("ancestor=${ancestor.javaClass.name} bounds=${ancestor.bounds} visible=${ancestor.isVisible}")
                ancestor = ancestor.parent
            }
        }
    }

    fun test程序性列表刷新不改变用户暂停状态() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            val scroll = requireNotNull(panel.listScrollPaneForTest)
            scroll.mouseWheelListeners.forEach {
                it.mouseWheelMoved(MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL, 1L, 0, 5, 5, 0,
                    false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1))
            }
            assertEquals(TailFollowMode.PAUSED, panel.tailFollowModeForTest)
            for (index in 1..32) {
                manager.entries.addEntry(LogSqlEntry("SELECT $index", "SELECT $index", emptyList(), timestamp = index.toLong()))
            }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("追加和刷新不得改变暂停意图", TailFollowMode.PAUSED, panel.tailFollowModeForTest)
            panel.resumeTailFollowForTest()
            assertEquals(TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }
}
