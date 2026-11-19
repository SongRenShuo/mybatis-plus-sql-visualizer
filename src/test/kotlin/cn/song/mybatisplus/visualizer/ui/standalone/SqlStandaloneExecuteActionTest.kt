package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * 独立工作台「执行 SQL」入口测试（D233/T-StandaloneExecPhase2）。
 *
 * 覆盖计划册 §五 A1/A2：
 *  - enable 翻转：解析成功 → 工具栏执行动作可用；清空 → 禁用；初始（未解析）→ 禁用；
 *  - 禁用态点执行：不裸跑，状态行给「先解析再执行」引导文案。
 *
 * ## 反向验证口径
 *
 * 把 [SqlStandalonePreviewPanel.ExecuteAction.update] 里的 `e.presentation.isEnabled =
 * previewPanel != null` 摘掉（恒 true），本类第一条用例必须变红；读数记计划册 §六。
 *
 * ## 真实执行链不在本类断言
 *
 * `executeSql` 内部是完整执行链（选数据源/控制台/数据库）——headless 沙盒没有真库，
 * 强断言执行结果只会造假读数。本类只证「入口接线与 enable 语义」；真链行为由既有
 * 执行链测试承接（executeSql 的链路自带测试），真机 GUI 验收由用户承接（计划册 §五 C2）。
 *
 * 桶位：引平台类与工具窗面，属**平台桶**（不进 testFast 快桶）。
 *
 * k2j 移植注：无互操作改写——ExecuteAction/ClearAction 为 Java 内部类经
 * `p.ExecuteAction()` 实例化（与 master 同形），测试缝方法同名在场。
 */
class SqlStandaloneExecuteActionTest : BasePlatformTestCase() {

    private var panel: SqlStandalonePreviewPanel? = null

    override fun tearDown() {
        runCatching { panel?.dispose() }
        panel = null
        super.tearDown()
    }

    private fun newPanel(): SqlStandalonePreviewPanel = SqlStandalonePreviewPanel(project).also { panel = it }

    private fun enabled(p: SqlStandalonePreviewPanel): Boolean {
        val action = p.ExecuteAction()
        val e = AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLBAR, EMPTY_CONTEXT)
        action.update(e)
        return e.presentation.isEnabled
    }

    private fun clickExecute(p: SqlStandalonePreviewPanel) {
        val action = p.ExecuteAction()
        action.actionPerformed(
            AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLBAR, EMPTY_CONTEXT),
        )
    }

    /** 轮询等解析落地：resolveFromInput 走后台线程 + invokeLater（同 SqlStandalonePreviewPanelDialogTest 先例）。 */
    private fun awaitResolved(p: SqlStandalonePreviewPanel, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (p.executeActionEnabledForTest()) return
            Thread.sleep(10)
        }
        fail("解析后执行动作应在超时内变为可用（轮询 ${timeoutMs}ms）")
    }

    fun test初始未解析时执行动作禁用() {
        val p = newPanel()
        assertFalse("未解析前执行动作必须禁用（懒创建未发生）", enabled(p))
    }

    fun test解析成功后执行动作可用_清空后禁用() {
        val p = newPanel()
        p.setInputTextForTest("SELECT * FROM T_USER WHERE ID = ?")
        p.resolveFromInput()
        awaitResolved(p)
        assertTrue("解析成功后执行动作必须可用", enabled(p))
        p.ClearAction().actionPerformed(
            AnActionEvent.createFromAnAction(p.ClearAction(), null, ActionPlaces.TOOLBAR, EMPTY_CONTEXT),
        )
        assertFalse("清空后执行动作必须禁用（内嵌面板已释放）", enabled(p))
    }

    fun test禁用态点执行给引导不裸跑() {
        val p = newPanel()
        clickExecute(p)
        assertEquals(
            "无面板时点执行必须给引导文案（不静默、不裸跑）",
            MpSqlVisualizerBundle.message("standalone.status.execute.noPreview"),
            p.statusTextForTest(),
        )
    }
}
