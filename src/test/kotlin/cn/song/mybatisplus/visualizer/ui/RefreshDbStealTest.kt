package cn.song.mybatisplus.visualizer.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import javax.swing.JPanel

/**
 * T-RefreshDbSteal 回归测试：预览刷新后内嵌 Database 控制台被抢（真机 6 连抢实锤）的
 * 四组防线——F3 监听生命周期、F4 所有权卫队、F2 弹窗单例化、去抖复查。
 *
 * 复现模型：多个 SqlPreviewPanel（多弹窗残留）+ 插件级共享控制台组件（单实例）。
 * 旧缺陷：面板 A 嵌入控制台后弹窗关闭/新弹窗 B 接管，A 的 rehost HierarchyListener
 * 仍挂在共享组件上，parent==null 时把控制台抢回已废弃面板 → 三症状
 * （DBN 工具栏消失 / 参数不再同步 / 执行打到别处）。
 */
class RefreshDbStealTest : BasePlatformTestCase() {

    private fun newPanel() = SqlPreviewPanel(project)

    /** 泵 EDT：执行排队的 invokeLater / 到期的一次性 Timer（仿 CopyFeedbackUiTest 同款）。 */
    private fun waitForEdt() = com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

    // ======================== F3：监听生命周期 ========================

    /** embedConsole 挂接的监听在 detachConsole 时显式移除——旧实现不移除（僵尸监听根因）。 */
    fun testDetachRemovesRehostListener() {
        val panel = newPanel()
        val console = JPanel()
        panel.embedConsoleForTest(console)
        assertTrue("embed 后监听应已挂接", panel.rehostListenerAttachedForTest())

        panel.detachConsoleForTest()

        assertFalse("detach 后监听必须已移除（共享组件上的僵尸监听 = 6 连抢根因）", panel.rehostListenerAttachedForTest())
        assertNull(panel.embeddedConsoleForTest())
    }

    /** 换入新控制台时旧组件上的本面板监听同步移除（旧组件不被本面板任何监听残挂）。 */
    fun testReembedRemovesListenerFromOldConsole() {
        val panel = newPanel()
        val first = JPanel()
        val second = JPanel()
        panel.embedConsoleForTest(first)
        assertTrue(panel.rehostListenerAttachedForTest())

        panel.embedConsoleForTest(second)

        // first 上已无本面板监听（detachConsole 移除），second 上挂接新监听
        assertTrue("新组件应挂有本面板监听", panel.rehostListenerAttachedForTest())
        assertSame(second, panel.embeddedConsoleForTest())
        assertNull("旧组件应已被摘除", first.parent)
    }

    /** dispose 显式 detach——弹窗关闭后面板监听不残活。 */
    fun testDisposeDetachesConsole() {
        val panel = newPanel()
        val console = JPanel()
        panel.embedConsoleForTest(console)

        panel.dispose()

        assertFalse("dispose 后监听必须已移除", panel.rehostListenerAttachedForTest())
        assertNull("dispose 后面板不得仍持有控制台引用", panel.embeddedConsoleForTest())
    }

    // ======================== F4：所有权卫队 ========================

    /** consoleAttachedToMe：挂在自己 host 内 true；被别人收养/完全释放 false。 */
    fun testConsoleAttachedToMeOwnershipMatrix() {
        val panelA = newPanel()
        val panelB = newPanel()
        val console = JPanel()
        panelA.embedConsoleForTest(console)
        assertTrue("嵌入后应判定归属本面板", panelA.consoleAttachedToMeForTest())
        assertFalse("未嵌入的面板应判定无归属", panelB.consoleAttachedToMeForTest())

        // 共享控制台被 B 收养（DBN results refreshed / 僵尸监听抢夺的等价形态）
        panelB.embedConsoleForTest(console)
        assertFalse("被 B 收养后 A 应判定失联（写 A 的 nativeDoc 即症状②）", panelA.consoleAttachedToMeForTest())
        assertTrue(panelB.consoleAttachedToMeForTest())

        // DBN 完全释放（parent==null）也判失联
        (console.parent as? JPanel)?.remove(console)
        assertFalse("完全释放后 B 也应判定失联", panelB.consoleAttachedToMeForTest())
    }

    /** 失联组件经 rehost 重挂后归属恢复（兜底链有效，卫队不误锁死）。 */
    fun testOwnershipRecoversAfterRehost() {
        val panel = newPanel()
        val console = JPanel()
        panel.embedConsoleForTest(console)
        val host = panel.consoleHostForTest()
        host.remove(console)
        assertFalse(panel.consoleAttachedToMeForTest())

        panel.rehostConsoleForTest(console)

        assertTrue("重挂后归属应恢复", panel.consoleAttachedToMeForTest())
    }

    // ======================== F3：去抖复查 ========================

    /** 去抖窗口内的释放不丢弃：debounced skip 后延迟复查仍能把失联组件重挂回来。 */
    fun testDebounceSkipStillRehostsAfterRetry() {
        val panel = newPanel()
        val console = JPanel()
        panel.embedConsoleForTest(console)
        val host = panel.consoleHostForTest()

        // 第一次释放：监听立即重挂路径（invokeLater 排队）；先泵 EDT 让 lastRehostAt 落定
        host.remove(console)
        waitForEdt()
        panel.rehostConsoleForTest(console)
        assertTrue(panel.consoleAttachedToMeForTest())

        // 去抖窗口内（< REHOST_MIN_INTERVAL_MS）再次释放——旧实现静默 return（零日志黑洞），
        // 现调度单次延迟复查；复查在 REHOST_RETRY_DELAY_MS 后仍失联则幂等重挂
        host.remove(console)
        waitForEdt()
        assertNull("窗口内第二次释放后组件失联", console.parent)

        // 复查 Timer 也在 EDT 队列——仿 CopyFeedbackUiTest.testFlashRestoresOriginalTextAfterTimer：
        // 轮询泵 EDT，把到期的 Timer 事件执行掉
        val deadline = System.currentTimeMillis() + 3000
        while (console.parent !== host && System.currentTimeMillis() < deadline) {
            waitForEdt()
            Thread.sleep(50)
        }
        assertTrue(
            "去抖 skip 后延迟复查应把失联组件重挂（T-RefreshDbSteal F3 兜底）",
            console.parent === host,
        )
    }

    // ======================== F2：弹窗单例化 ========================

    /** 弹窗单例化辅助：面板 clear 收尾（EditorFactory 释放编辑器，防 "hasn't been released"）。 */
    private fun previewResult() = cn.song.mybatisplus.visualizer.model.PreviewResult(
        sql = "SELECT 1",
        compactSql = "SELECT 1",
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = cn.song.mybatisplus.visualizer.model.WrapperType.LAMBDA_QUERY,
        tableName = "t",
        columns = listOf("id"),
    )

    /** 二次 showResult：旧弹窗被关闭、登记表中只剩新弹窗（多弹窗僵尸源头消灭）。 */
    fun testShowResultSingletonizesPreviousDialog() {
        val project = this.project
        val result = previewResult()

        SqlPreviewService.showResult(project, result)
        val first = SqlPreviewService.activePreviewDialogForTest(project)
        assertNotNull("首次 showResult 应登记弹窗", first)
        waitForEdt()

        SqlPreviewService.showResult(project, result)
        val second = SqlPreviewService.activePreviewDialogForTest(project)
        assertNotNull(second)
        waitForEdt()
        assertTrue("旧弹窗必须已被关闭（单例化）", first!!.isDisposed)
        assertTrue("登记表应指向新弹窗", second !== first)

        // 清理：仿 SqlPreviewPanelUiTest 收尾惯例——clear 释放编辑器（防 EditorImpl 泄漏），
        // dispose 级联释放面板（rehost 监听随之移除）；first 已被单例化关闭，同样补 clear
        runCatching { first.panelForTest()!!.clear() }
        runCatching { second!!.panelForTest()!!.clear() }
        runCatching { second!!.doDispose() }
    }

    /** 单例关闭幂等：重复 doDispose 不抛异常（单例化路径 + 用户手动关闭的竞态兜底）。 */
    fun testDisposeIdempotentAfterSingletonClose() {
        val project = this.project
        SqlPreviewService.showResult(project, previewResult())
        val dialog = SqlPreviewService.activePreviewDialogForTest(project)
        assertNotNull(dialog)
        waitForEdt()

        // 重复关闭不抛异常；面板 clear 收尾防编辑器泄漏
        runCatching { dialog!!.panelForTest()!!.clear() }
        runCatching { dialog!!.doDispose() }
        runCatching { dialog!!.doDispose() }
        assertTrue(dialog!!.isDisposed)
    }
}
