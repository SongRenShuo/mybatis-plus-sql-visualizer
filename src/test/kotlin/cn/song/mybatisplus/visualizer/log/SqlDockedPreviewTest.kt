package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JScrollPane

class SqlDockedPreviewTest : BasePlatformTestCase() {
    private fun entry(sql: String) = LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList())

    private class Fixture(val list: JList<LogSqlEntry>, val preview: SqlDockedPreview, val host: SqlListPreviewHost)

    private fun fixture(vararg sql: String): Fixture {
        val model = DefaultListModel<LogSqlEntry>()
        sql.forEach { model.addElement(entry(it)) }
        val list = JList(model).apply {
            fixedCellHeight = 20
            setSize(320, 200)
        }
        lateinit var host: SqlListPreviewHost
        val preview = SqlDockedPreview(project, list) { host.toggle() }
        host = SqlListPreviewHost(list, JScrollPane(list), preview)
        return Fixture(list, preview, host)
    }

    private fun click(list: JList<*>, row: Int, count: Int = 1, modifiers: Int = 0): MouseEvent =
        MouseEvent(list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), modifiers,
            5, row * 20 + 10, count, false, MouseEvent.BUTTON1)

    fun test原生分隔条无白色默认皮肤_收合再开启仍挂着列表() {
        val f = fixture("SELECT 1")
        try {
            f.list.selectedIndex = 0
            f.host.toggle()
            val split = f.host.component.components.filterIsInstance<com.intellij.ui.OnePixelSplitter>().single()
            assertEquals(com.intellij.util.ui.UIUtil.getPanelBackground(), split.background)
            assertTrue("展开后列表应仍挂在原生分隔组件中",
                javax.swing.SwingUtilities.isDescendingFrom(f.list, split))
            f.host.toggle()
            f.host.toggle()
            assertSame("重新展开须复用同一分隔条，保留用户拖拽的高度比例", split,
                f.host.component.components.filterIsInstance<com.intellij.ui.OnePixelSplitter>().single())
            assertTrue("重新展开不能只显示空白上半区",
                javax.swing.SwingUtilities.isDescendingFrom(f.list, split))
        } finally {
            f.host.hideHint()
            f.preview.dispose()
        }
    }

    fun test单击只选中_显式开启后随列表选中更新() {
        val f = fixture("SELECT 1", "SELECT 2", "SELECT 3")
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            settings.loadState(before.copy(sqlListPreviewHintSeen = true, logFormatSql = false))
            f.list.selectedIndex = 0
            f.host.onRowClick(click(f.list, 0))
            assertFalse(f.host.previewOpenForTest)
            assertNull(f.preview.editorForTest)
            f.host.toggle()
            assertTrue(f.host.previewOpenForTest)
            assertEquals("SELECT 1;", f.preview.displayedSqlForTest)
            val editor = f.preview.editorForTest
            f.list.selectedIndex = 1
            assertSame("浏览下一行应复用编辑器", editor, f.preview.editorForTest)
            assertEquals("SELECT 2;", f.preview.displayedSqlForTest)
            f.host.toggle()
            assertFalse(f.host.previewOpenForTest)
            f.list.selectedIndex = 2
            f.host.toggle()
            assertEquals("SELECT 3;", f.preview.displayedSqlForTest)
        } finally {
            f.host.hideHint()
            f.preview.dispose()
            settings.loadState(before)
        }
    }

    fun test编辑后切换不覆盖草稿_恢复后跟随当前选中() {
        val f = fixture("SELECT 1", "SELECT 2")
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            settings.loadState(before.copy(logFormatSql = false))
            f.list.selectedIndex = 0
            f.host.toggle()
            WriteCommandAction.runWriteCommandAction(project) {
                f.preview.editorForTest!!.document.setText("SELECT changed;")
            }
            assertTrue(f.preview.isPinnedForTest)
            f.list.selectedIndex = 1
            assertEquals("SELECT changed;", f.preview.displayedSqlForTest)
            f.preview.restoreAndFollowForTest()
            assertFalse(f.preview.isPinnedForTest)
            assertEquals("SELECT 2;", f.preview.displayedSqlForTest)
            f.list.selectedIndices = intArrayOf(0, 1)
            assertNull("多选不应展示任意单条 SQL", f.preview.displayedSqlForTest)
        } finally {
            f.preview.dispose()
            settings.loadState(before)
        }
    }

    fun test日志面板真实鼠标监听只提示不弹窗_开启后显示当前选中() {
        val manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
        val panel = SqlLogPanel(project, manager)
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            settings.loadState(before.copy(sqlListPreviewHintSeen = false, logFormatSql = false))
            manager.entries.addEntry(entry("SELECT 1"))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            panel.list.fixedCellHeight = 20
            panel.list.setSize(320, 200)
            panel.list.selectedIndex = 0
            val event = click(panel.list, 0)
            panel.list.mouseListeners.forEach { it.mouseClicked(event) }
            assertFalse(panel.previewHostForTest.previewOpenForTest)
            assertNull(panel.dockedPreviewForTest.editorForTest)
            assertTrue("真实面板 MouseAdapter 应接入一次性引导", panel.previewHostForTest.hintScheduledForTest)
            panel.previewHostForTest.showPendingHintForTest()
            assertTrue(panel.previewHostForTest.hintVisibleForTest)
            panel.previewHostForTest.toggle()
            assertTrue("引导入口开启后预览图标应显选中态",
                com.intellij.openapi.actionSystem.Toggleable.isSelected(panel.previewToggleAction.templatePresentation))
            assertEquals("SELECT 1;", panel.dockedPreviewForTest.displayedSqlForTest)
            val actionEvent = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                panel.previewToggleAction, null, com.intellij.openapi.actionSystem.ActionPlaces.TOOLBAR,
                com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
            )
            panel.previewToggleAction.setSelected(actionEvent, false)
            assertFalse("左侧预览图标应可关闭底部预览", panel.previewHostForTest.previewOpenForTest)
            assertFalse(com.intellij.openapi.actionSystem.Toggleable.isSelected(panel.previewToggleAction.templatePresentation))
            panel.previewToggleAction.setSelected(actionEvent, true)
            assertTrue("左侧预览图标应可重新开启", panel.previewHostForTest.previewOpenForTest)
        } finally {
            panel.dispose()
            manager.dispose()
            settings.loadState(before)
        }
    }

    fun test停靠预览跟随展示脱敏开关且不改原条目() {
        val original = "SELECT * FROM t WHERE phone = '13812345678'"
        val f = fixture(original)
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            // T-MagpieGems W2：脱敏语义由 displayMaskMode 承载（loadState 归一只认新字段）
            settings.loadState(before.copy(logFormatSql = false, displayMaskMode = "MASK"))
            f.list.selectedIndex = 0
            f.host.toggle()
            val displayed = f.preview.displayedSqlForTest.orEmpty()
            assertTrue("脱敏开时停靠预览应打码", displayed.contains("'***'"))
            assertFalse("停靠预览不得泄漏原手机号", displayed.contains("13812345678"))
            assertEquals("展示脱敏不能改写条目数据", original, f.list.selectedValue.restoredSql)
        } finally {
            f.preview.dispose()
            settings.loadState(before)
        }
    }

    fun test停靠预览WARN模式原文加警示行且复制从严() {
        // contact 非关键词列 → 手机号走形态兜底（警示行才含「手机号」类别）
        val original = "SELECT * FROM t WHERE contact = '13812345678' AND pwd = 'p@ss'"
        val f = fixture(original)
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            settings.loadState(before.copy(logFormatSql = false, displayMaskMode = "WARN"))
            f.list.selectedIndex = 0
            f.host.toggle()
            val displayed = f.preview.displayedSqlForTest.orEmpty()
            assertTrue("WARN 屏显应保留原文", displayed.contains("13812345678"))
            assertTrue("头部应追加警示行（含手机号类别）", displayed.substringBefore("\n")
                .contains(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("mask.category.mobile")))
            assertEquals("展示脱敏不能改写条目数据", original, f.list.selectedValue.restoredSql)
        } finally {
            f.host.hideHint()
            f.preview.dispose()
            settings.loadState(before)
        }
    }

    fun test停靠预览BLOCK模式整段阻断占位() {
        val original = "SELECT * FROM t WHERE phone = '13812345678'"
        val f = fixture(original)
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            settings.loadState(before.copy(logFormatSql = false, displayMaskMode = "BLOCK"))
            f.list.selectedIndex = 0
            f.host.toggle()
            val displayed = f.preview.displayedSqlForTest.orEmpty()
            assertTrue("BLOCK 应输出阻断占位（含命中总数 1）", displayed.contains("1"))
            assertFalse("原文手机号不得出现", displayed.contains("13812345678"))
        } finally {
            f.host.hideHint()
            f.preview.dispose()
            settings.loadState(before)
        }
    }

    /**
     * T-PerfSweep26-05（F98，修复前必红）：同一性短路前置——同一条目展示中再来一次内容事件
     * （流式灌入每 30 条一拍的 ListDataEvent 同路径），不得再付 isPinned() 的全文 getText；
     * 断言走 [SqlDockedPreview.isPinnedCallCountForTest] 调用计数缝。
     */
    fun test同一性短路前置_同条目二次事件不再调用isPinned全文读() {
        val f = fixture("SELECT 1")
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            settings.loadState(before.copy(logFormatSql = false))
            f.list.selectedIndex = 0
            f.host.toggle()
            assertEquals("SELECT 1;", f.preview.displayedSqlForTest)
            val afterFirst = f.preview.isPinnedCallCountForTest
            assertTrue("首次展示应付过一次 isPinned 全文读（基线）", afterFirst >= 1)
            f.preview.refreshSelection() // 同一 displayedEntry 的二次事件（ListDataEvent 同入口）
            assertEquals("同条目二次事件不得再付 isPinned 全文读（F98 短路）",
                afterFirst, f.preview.isPinnedCallCountForTest)
        } finally {
            f.host.hideHint()
            f.preview.dispose()
            settings.loadState(before)
        }
    }

    fun test首次单击就地提示只出现一次_双击取消_两页共用标志() {
        val first = fixture("SELECT 1")
        val second = fixture("SELECT 2")
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            settings.loadState(before.copy(sqlListPreviewHintSeen = false))
            first.list.selectedIndex = 0
            first.host.onRowClick(click(first.list, 0))
            assertTrue(first.host.hintScheduledForTest)
            first.host.onRowClick(click(first.list, 0, count = 2))
            assertFalse(first.host.hintScheduledForTest)
            assertFalse(first.host.hintVisibleForTest)
            first.host.onRowClick(click(first.list, 0))
            first.host.showPendingHintForTest()
            assertTrue(first.host.hintVisibleForTest)
            assertTrue(settings.state.sqlListPreviewHintSeen)
            first.host.toggle()
            assertFalse(first.host.hintVisibleForTest)
            assertTrue(first.host.previewOpenForTest)
            second.list.selectedIndex = 0
            second.host.onRowClick(click(second.list, 0))
            assertFalse(second.host.hintScheduledForTest)
            assertFalse(second.host.hintVisibleForTest)
        } finally {
            first.host.hideHint()
            second.host.hideHint()
            first.preview.dispose()
            second.preview.dispose()
            settings.loadState(before)
        }
    }
}
