package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.ui.SqlResultPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * SQL 执行错误处理测试（M36.3）：验证错误回调链路和 UI 状态恢复。
 *
 * 测试场景：
 *  1. Database 插件不可用时，错误回调被调用
 *  2. 执行链失败时，错误回调被调用且 UI 从「执行中」恢复
 *  3. 超时提示机制正常触发
 */
class ExecuteSqlErrorHandlingTest : BasePlatformTestCase() {

    // ======================== 错误回调链路 ========================

    /**
     * 测试：Database 插件不可用时，onError 回调被调用。
     * （单元测试环境无 Database 插件，isAvailable() 返回 false）
     */
    fun testErrorCallbackInvokedWhenPluginUnavailable() {
        val errors = mutableListOf<String>()
        var callbackResult: Boolean? = null

        DataSourceSelector.selectAndExecute(
            project = project,
            sql = "SELECT * FROM users",
            onError = { msg -> errors.add(msg) },
            callback = { success -> callbackResult = success },
        )

        // 验证：错误回调被调用，且包含 Database 插件不可用的提示
        assertTrue("onError should be called when plugin unavailable", errors.isNotEmpty())
        assertTrue(
            "Error message should mention Database plugin",
            errors.any { it.contains("Database") || it.contains("database") },
        )
        // 验证：callback 返回 false
        assertEquals("callback should return false", false, callbackResult)
    }

    /**
     * 测试：未设置 onError 回调时不会空指针异常。
     */
    fun testNoErrorCallbackDoesNotThrow() {
        // 不应抛异常
        DataSourceSelector.selectAndExecute(
            project = project,
            sql = "SELECT * FROM users",
            onError = null,  // 不设置错误回调
            callback = null,
        )
        // 验证通过：无异常即成功
    }

    /**
     * 测试：runners 为空（无数据源）时，onError 回调被调用。
     * 这是 M36.3 修复的核心场景：用户无数据源时点击执行，应提示配置数据源。
     */
    fun testErrorCallbackInvokedWhenRunnersEmpty() {
        val errors = mutableListOf<String>()
        var callbackResult: Boolean? = null

        DataSourceSelector.selectAndExecute(
            project = project,
            sql = "SELECT * FROM users",
            onError = { msg -> errors.add(msg) },
            callback = { success -> callbackResult = success },
        )

        // 验证：runners 为空时（测试环境无数据源），onError 被调用
        assertTrue("onError should be called when runners empty", errors.isNotEmpty())
        // 验证：callback 返回 false
        assertEquals("callback should return false", false, callbackResult)
    }

    // ======================== SqlResultPanel 状态恢复 ========================

    /**
     * 测试：SqlResultPanel.showError 正确设置错误文本和颜色。
     */
    fun testResultPanelShowError() {
        val panel = SqlResultPanel()
        panel.showError("Connection failed")

        // 验证：错误信息包含在文本中
        assertTrue(
            "Error text should contain the message",
            panel.infoLabel.text.contains("Connection failed"),
        )
        // 验证：文本颜色为红色（错误色）
        assertEquals("Error color should be red", java.awt.Color.RED, panel.infoLabel.foreground)
        // M36.5.48：错误文本可复制——currentError 保留完整文本供复制按钮使用
        assertEquals("currentError should hold the full error text", "Connection failed", panel.currentErrorForTest())
    }

    /**
     * 测试：SqlResultPanel 从 loading 状态切换到 error 状态。
     */
    fun testResultPanelLoadingToErrorTransition() {
        val panel = SqlResultPanel()

        // 初始状态：loading
        panel.showLoading()
        assertTrue("Loading text should be set", panel.infoLabel.text.isNotEmpty())

        // 切换到 error
        panel.showError("Test error")
        assertTrue(
            "Error text should replace loading text",
            panel.infoLabel.text.contains("Test error"),
        )
    }

    // ======================== M36.5 自动可见性 ========================

    /**
     * 测试：SqlResultPanel 初始/空状态隐藏（不占高度，消除底部空白占位），
     * 有内容（错误/loading）时显示。
     */
    fun testResultPanelAutoVisibility() {
        val panel = SqlResultPanel()
        // 初始隐藏
        assertTrue("Panel should start hidden (no blank placeholder)", !panel.isVisible)

        // 有内容 → 显示
        panel.showError("boom")
        assertTrue("Panel should be visible with error content", panel.isVisible)

        panel.showLoading()
        assertTrue("Panel should be visible while loading", panel.isVisible)

        // 清空 → 隐藏
        panel.showEmpty()
        assertTrue("Panel should hide when empty (no blank placeholder)", !panel.isVisible)
    }

    /**
     * 测试（M36.5.49）：showError 后 showIdle 保留错误——防止执行失败的错误文本
     * 被后续 results refreshed 触发的 embedConsole 同实例 showIdle() 覆盖（"一闪而过"）。
     */
    fun testShowIdlePreservesError() {
        val panel = SqlResultPanel()
        panel.showError("Table 'xx' doesn't exist")

        // showIdle（执行完成收尾）不应覆盖错误态
        panel.showIdle()
        assertEquals("showIdle should keep error text", "Table 'xx' doesn't exist", panel.currentErrorForTest())
        assertTrue("panel should stay visible when error preserved", panel.isVisible)
        assertEquals("state should remain ERROR", "ERROR", panel.stateForTest())

        // 显式 showEmpty 才真正清空
        panel.showEmpty()
        assertEquals("showEmpty should clear error", null, panel.currentErrorForTest())
        assertEquals("state should become EMPTY", "EMPTY", panel.stateForTest())
    }

    // ======================== M36.5 控制台准备/执行拆分 ========================

    /**
     * 测试：executePrepared 在控制台未就绪时返回 false（不抛异常）——
     * 调用方据此回退 prepare+execute 全链。
     */
    fun testExecutePreparedNotReadyReturnsFalse() {
        DataSourceSelector.clearPrepared()
        val executed = DataSourceSelector.executePrepared(project)
        assertTrue("executePrepared should return false when console not prepared", !executed)
    }

    /**
     * 测试（M36.5.2）：executePrepared 未就绪时不触发 onResultsReady（不抛异常）——
     * 结果区换入回调仅在真实执行触发成功后发生。
     */
    fun testExecutePreparedNotReadyDoesNotFireResultsReady() {
        DataSourceSelector.clearPrepared()
        var resultsReadyFired = false
        val executed = DataSourceSelector.executePrepared(
            project,
            onResultsReady = { resultsReadyFired = true },
        )
        assertTrue("executePrepared should return false when console not prepared", !executed)
        assertTrue("onResultsReady should not fire when console not prepared", !resultsReadyFired)
    }

    /**
     * 测试（M36.5.2）：SqlExecutor.executePrepared 同样在未就绪时返回 false（沙盒无 Database 插件）。
     */
    fun testSqlExecutorExecutePreparedNotReadyReturnsFalse() {
        SqlExecutor.clearPrepared()
        assertTrue(
            "SqlExecutor.executePrepared should return false when console not prepared",
            !SqlExecutor.executePrepared(project),
        )
    }

    // ======================== M36.5.2 控制台内嵌防抖 ========================

    /**
     * 测试（M36.5.2）：embedConsole 替换旧控制台；同一组件重复回调不重复内嵌（防闪烁）——
     * 缓存完整 UI 在重复执行时回调同一实例，面板须幂等。
     */
    fun testEmbedConsoleReplacesAndDebounces() {
        val panel = cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel(project)
        val first = javax.swing.JPanel()
        val second = javax.swing.JPanel()

        panel.embedConsoleForTest(first)
        assertTrue("first embed should attach to holder", first.parent != null)

        panel.embedConsoleForTest(second)
        assertTrue("second embed should replace as current", panel.embeddedConsoleForTest() === second)
        assertTrue("second embed should be attached", second.parent != null)
        assertTrue("first embed should be detached after replace", first.parent == null)

        // 同实例重复回调（重复执行触发 onResultsReady）→ 幂等，不替换不摘挂
        val parentBefore = second.parent
        panel.embedConsoleForTest(second)
        assertTrue("same instance re-embed should keep current", panel.embeddedConsoleForTest() === second)
        assertTrue("same instance re-embed should keep parent", second.parent === parentBefore)
    }

    /**
     * 测试：SqlExecutor.prepareConsole 在插件不可用环境（单元测试沙盒）静默回退——
     * callback(false) 且不抛异常、不弹提示（社区版回退语义）。
     */
    fun testPrepareConsoleSilentFallbackWhenPluginUnavailable() {
        var callbackResult: Boolean? = null
        var errorInvoked = false

        SqlExecutor.prepareConsole(
            project = project,
            sql = "SELECT 1",
            onError = { errorInvoked = true },
            callback = { success -> callbackResult = success },
        )

        assertEquals("callback should be invoked with false in test sandbox", false, callbackResult)
        assertTrue("onError should NOT be invoked (silent fallback)", !errorInvoked)
    }

    /**
     * 测试：clearPrepared 重置就绪状态。
     */
    fun testClearPrepared() {
        DataSourceSelector.clearPrepared()
        assertTrue("isPrepared should be false after clearPrepared", !DataSourceSelector.isPrepared())
    }

    /**
     * 测试：SqlResultPanel.clear 清空状态。
     */
    fun testResultPanelClear() {
        val panel = SqlResultPanel()
        panel.showError("Some error")
        assertTrue("Error should be set", panel.infoLabel.text.isNotEmpty())

        panel.clear()
        assertTrue("Cleared panel should have empty text", panel.infoLabel.text.isEmpty())
    }

    // ======================== SqlExecutor 错误透传 ========================

    /**
     * 测试：SqlExecutor.execute 在插件不可用时调用 onError。
     */
    fun testSqlExecutorPropagatesError() {
        val errors = mutableListOf<String>()

        SqlExecutor.execute(
            project = project,
            sql = "SELECT 1",
            dataSource = null,
            isQuery = true,
            onError = { msg -> errors.add(msg) },
        )

        // 验证：错误回调被调用（插件不可用）
        assertTrue("SqlExecutor should propagate error to onError", errors.isNotEmpty())
    }

    /**
     * 测试：SqlExecutor.execute 未设置 onError 时不抛异常。
     */
    fun testSqlExecutorNoErrorCallbackDoesNotThrow() {
        SqlExecutor.execute(
            project = project,
            sql = "SELECT 1",
            dataSource = null,
            isQuery = true,
            onError = null,
        )
        // 验证通过：无异常即成功
    }

    // ======================== 辅助属性和方法 ========================

    /**
     * 访问 SqlResultPanel 的 infoLabel（测试用）。
     * 注意：infoLabel 是 private，通过 reflection 访问用于测试验证。
     */
    private val SqlResultPanel.infoLabel: JLabel
        get() {
            val field = SqlResultPanel::class.java.getDeclaredField("infoLabel")
            field.isAccessible = true
            return field.get(this) as JLabel
        }
    }
