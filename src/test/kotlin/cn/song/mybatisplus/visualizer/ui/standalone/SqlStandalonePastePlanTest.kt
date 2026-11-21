package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.ui.ExplainJsonPlanTree
import cn.song.mybatisplus.visualizer.ui.ExplainPlanResultView
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import javax.swing.tree.DefaultMutableTreeNode

/**
 * PEV-01「粘贴执行计划」入口测试（渲染判别测试）。
 *
 * 覆盖：JSON 档粘贴走全管线（徽章随粘贴计划生效）；text 档粘贴出视图带建议；
 * 噪声/空输入/非计划 JSON 诚实失败不出视图；工具栏登记。
 * 剪贴板与弹窗都走测试缝（headless 不依赖系统剪贴板、只认登记事实）。
 */
class SqlStandalonePastePlanTest : BasePlatformTestCase() {

    private var panel: SqlStandalonePreviewPanel? = null
    private val openedViews = mutableListOf<ExplainPlanResultView>()

    override fun tearDown() {
        panel = null
        openedViews.clear()
        super.tearDown()
    }

    private fun newPanel(): SqlStandalonePreviewPanel = SqlStandalonePreviewPanel(project).also { p ->
        panel = p
        // 面板持有输入编辑器——不挂 Disposer 会在平台 teardown 抛 Editor not released（全类连坐红）
        Disposer.register(testRootDisposable, p)
        p.pasteDialogInvoker = { view -> openedViews += view }
    }

    private fun clickPaste(p: SqlStandalonePreviewPanel) {
        val action = p.PastePlanAction()
        action.actionPerformed(
            AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLBAR, EMPTY_CONTEXT),
        )
    }

    private fun injectClipboard(text: String?) {
        panel!!.planClipboardForTest = { text }
    }

    fun testToolbarRegistersPastePlanAction() {
        assertTrue(newPanel().inputToolbarActionNamesForTest().contains("PastePlanAction"))
    }

    fun testJson计划粘贴_走全管线出徽章() {
        val p = newPanel()
        injectClipboard(
            """[{"Plan": {"Node Type": "Seq Scan", "Relation Name": "t", """ +
                """"Plan Rows": 100, "Actual Rows": 5000, "Actual Total Time": 10.0}}]""",
        )
        clickPaste(p)
        assertEquals("JSON 计划应打开一个视图", 1, openedViews.size)
        val model = openedViews[0].treeModelForTest()!!
        val root = model.root as DefaultMutableTreeNode
        val node = (root.getChildAt(0) as DefaultMutableTreeNode).userObject as ExplainJsonPlanTree.Node
        assertNotNull("JSON 档粘贴应带估算偏差徽章（build() 全管线）", node.estimationBadge)
    }

    fun test文本计划粘贴_出视图带建议() {
        val p = newPanel()
        injectClipboard("Seq Scan on tenk1  (cost=0.00..333.00 rows=10000 width=148)")
        clickPaste(p)
        assertEquals("text 计划应打开一个视图", 1, openedViews.size)
        assertNotNull("text 档建议（SEQ_SCAN 规则）应随粘贴计划出现", openedViews[0].hintsTextForTest())
        assertNotNull("text 计划应渲染成树", openedViews[0].treeModelForTest())
    }

    fun testJson形但不是计划_失败不出视图() {
        val p = newPanel()
        injectClipboard("""{"foo": {"bar": 1}}""")
        clickPaste(p)
        assertEquals("非计划 JSON 不应开视图", 0, openedViews.size)
        assertTrue("状态行应有失败提示", p.statusTextForTest().isNotBlank())
    }

    fun test噪声文本_失败不出视图() {
        val p = newPanel()
        injectClipboard("hello world, definitely not a plan")
        clickPaste(p)
        assertEquals(0, openedViews.size)
        assertTrue(p.statusTextForTest().isNotBlank())
    }

    fun test空剪贴板_提示不出视图() {
        val p = newPanel()
        injectClipboard(null)
        clickPaste(p)
        assertEquals(0, openedViews.size)
        assertTrue(p.statusTextForTest().isNotBlank())
    }
}
