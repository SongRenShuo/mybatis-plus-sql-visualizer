package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 帮助入口测试（T-M39）。
 *
 * 帮助按钮已改为 JBCefBrowser.loadHTML() 在弹窗中打开本地 HTML；
 * 测试聚焦：(1) 工具栏组含帮助入口；(2) 弹窗可构造；(3) 保留 bundle key 存在。
 */
class HelpDialogTest : BasePlatformTestCase() {

    fun testToolbarGroupContainsHelpAction() {
        // 直接构造（对齐 SqlLogManagerBehaviorTest——测试环境 project.getService 不可用）
        val panel = SqlLogPanel(project, SqlLogManager(project))
        try {
            // 用户 2026-10-14：「不要这个更多」——全部动作直达工具栏，帮助也在组内（窄宽由平台溢出折叠）
            val children = panel.toolbarGroup.getChildren(null).toList()
            assertEquals("增强捕获暂停、快速预览与交流群入口合流后共 11 个动作", 11, children.size)
            assertTrue("捕捉动作应位于工具栏首位", children[0] === panel.captureToggleAction)
            assertTrue("增强捕获暂停动作应紧随捕捉动作", children[1] === panel.agentPauseAction)
            assertTrue("快速预览仍应是独立动作", children.contains(panel.previewToggleAction))
            assertTrue("工具栏组应包含交流群入口", children.any { it is SqlLogPanel.CommunityAction })
            // update 由工具栏生命周期驱动（headless 不布局不触发），手动走一次——
            // 图标/文案写入 event.presentation（2024.3 AnAction 无 getPresentation()）
            val events = children.map {
                AnActionEvent.createFromAnAction(it, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT)
            }
            children.forEachIndexed { i, action -> action.update(events[i]) }
            val helpIndex = events.indexOfFirst { it.presentation.icon === MpvsIcons.Help }
            assertEquals("帮助动作应位于工具栏末尾", children.lastIndex, helpIndex)
            assertEquals(
                MpSqlVisualizerBundle.message("log.panel.help"),
                events[helpIndex].presentation.text,
            )
        } finally {
            panel.dispose()
        }
    }

    fun testRetainedBundleKeysPresent() {
        // 保留 key：仍被 HelpAction 引用
        assertTrue(MpSqlVisualizerBundle.message("log.panel.help").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("help.dialog.title").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("help.dialog.close").isNotBlank())
    }

    fun testRemovedBundleKeysAbsent() {
        // 内容 key 已迁移到本地 HTML 文件
        assertFalse(
            "help.content.features 应已移除",
            MpSqlVisualizerBundle.message("help.content.features").contains("<h3>"),
        )
        assertFalse(
            "help.content.usage 应已移除",
            MpSqlVisualizerBundle.message("help.content.usage").contains("<h3>"),
        )
        assertFalse(
            "help.content.shortcuts 应已移除",
            MpSqlVisualizerBundle.message("help.content.shortcuts").contains("<h3>"),
        )
        assertFalse(
            "help.content.other 应已移除",
            MpSqlVisualizerBundle.message("help.content.other").contains("<h3>"),
        )
    }
}
