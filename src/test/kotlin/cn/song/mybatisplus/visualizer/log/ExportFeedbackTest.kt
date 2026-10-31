package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel
import cn.song.mybatisplus.visualizer.ui.clear
import cn.song.mybatisplus.visualizer.ui.exportPortableSql
import cn.song.mybatisplus.visualizer.ui.exportReplayScript
import cn.song.mybatisplus.visualizer.ui.exportTraceDoc
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * S1-4④：四条导出出口的「无可导出内容」可见提示（不弹模态框，走平台通知）。
 *
 * 修复前各出口在空内容时直接 `return`——用户点了没反应，分不清「点了没生效」和「本来就空」。
 * 本用例用 [ExportFeedback.sinkForTest] 拦截提示文本（headless 下平台通知不可观测），
 * 断言**每条出口在空内容时确实发出提示**。
 *
 * 判据（README「轻提示走平台通知」）：这里只验证「提示发出且文案正确」，模态弹窗由
 * `NoNewModalDialogGuardTest` 之类的既有守卫兜底（本改动未新增任何 DialogWrapper）。
 *
 * 反向验证：把任一出口的 `ExportFeedback.notifyNothingToExport(...)` 换回裸 `return`，
 * 对应断言必红（sink 收不到文本）。
 */
class ExportFeedbackTest : BasePlatformTestCase() {

    override fun tearDown() {
        ExportFeedback.sinkForTest = null
        super.tearDown()
    }

    /** 纯对象层：hintKey 为空/非空两条文案都送到 sink，且导出标识缺失键不得抛。 */
    fun testNotifyNothingToExport_文案落sink() {
        val captured = mutableListOf<String>()
        ExportFeedback.sinkForTest = { captured += it }
        ExportFeedback.notifyNothingToExport(project)
        ExportFeedback.notifyNothingToExport(project, "export.nothing.history")
        assertEquals(2, captured.size)
        assertTrue(captured[0].isNotBlank())
        assertTrue(
            "带 hint 的文案应包含具体原因",
            captured[1].contains(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("export.nothing.history")),
        )
    }

    /** 历史面板右键导出：空条目集必须发提示。 */
    fun test历史面板导出空集发提示() {
        val captured = mutableListOf<String>()
        ExportFeedback.sinkForTest = { captured += it }
        val panel = HistorySqlLogPanel(project)
        try {
            panel.exportEntries(emptyList())
            assertEquals("空集导出必须发一条「无可导出内容」提示", 1, captured.size)
        } finally {
            panel.dispose()
        }
    }

    /** 日志页导出：无可导出内容（lastRendered 空）必须发提示。 */
    fun test日志页导出空集发提示() {
        val captured = mutableListOf<String>()
        ExportFeedback.sinkForTest = { captured += it }
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            panel.exportVisibleEntries()
            assertEquals("日志页空导出必须发一条提示", 1, captured.size)
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    /** 预览面板「更多」三条导出：无 SQL 时各发提示（不触发文件对话框）。 */
    fun test预览三条导出无SQL发提示() {
        val captured = mutableListOf<String>()
        ExportFeedback.sinkForTest = { captured += it }
        val panel = SqlPreviewPanel(project)
        Disposer.register(testRootDisposable, panel)
        try {
            panel.exportPortableSql()
            panel.exportReplayScript()
            panel.exportTraceDoc()
            assertEquals("三条入口各发一条提示", 3, captured.size)
            assertNotNull(captured.firstOrNull())
        } finally {
            panel.clear()
        }
    }
}
