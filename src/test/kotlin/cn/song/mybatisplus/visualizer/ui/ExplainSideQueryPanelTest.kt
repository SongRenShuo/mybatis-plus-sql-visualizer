// 自 master src/test/kotlin/.../ui/ExplainSideQueryPanelTest.kt 移植（T-ExplainConsoleFirst W3/W8 契约反转）
// （k2j 适配：submitExplainDirect/previewSqlForReadonly 为 SqlPreviewPanelExecutionKt 静态承载；
//   测试缝赋值走显式 SAM 构造；showResult/clear 走 k2j 门面）
package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import cn.song.mybatisplus.visualizer.execute.SqlExecutor
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-ExplainConsoleFirst W3（2026-10-24）：执行计划直送路径**回到内嵌原生控制台**。
 *
 * 契约反转说明：本类原属 T-PreviewSideQuery P2，断言「EXPLAIN 不得换入控制台、必须经只读
 * 对话框展示」。十二轮换源拉锯后该形态被判定为系统性代价来源（插件自建 JDBC 通道 ⇒ 取源/
 * 密码/方言/报错全落插件，而平台「选中即关联」让插件侧选源列表第二次起恒为单源），故
 * T-ExplainConsoleFirst 主动把它改回初版的控制台形态。原断言随之反转：
 *
 *  - 旧：`submitExplainDirect` 走 `PreviewSideQuery` + `ExplainPlanDialog`，编辑器不变；
 *  - 新：`submitExplainDirect` 把 EXPLAIN 交给 `SqlExecutor.execute`（内嵌原生控制台，
 *        由 `embedConsole(EXECUTE)` 挂「返回预览」头部）。
 *
 * 仍然成立并保留的：对话框取消按钮跟随插件语言（`ExplainPlanDialog` 本体未变）、零源时
 * 引导按钮直达 Database 工具窗口（`showExplainErrorDialog` 未变）。
 */
class ExplainSideQueryPanelTest : BasePlatformTestCase() {

    /** 必须是 public：生产反射按 getUrl 取目标方言，private 嵌套类会让 invoke 失败。 */
    class ExplainTarget {
        @Suppress("unused")
        fun getUrl(): String = "jdbc:mysql://localhost:3306/demo"
    }

    private var panel: SqlPreviewPanel? = null

    private fun buildResult(sql: String): PreviewResult = PreviewResult(
        sql,
        sql,
        emptyList(),
        null,
        null,
        null,
        null,
        emptyList(),
        emptyMap(),
        null,
        null,
        emptyList(),
        emptyList(),
        null,
        null,
        emptyMap(),
        null,
        null,
        null,
        null,
    )

    private fun panel(sql: String): SqlPreviewPanel {
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        SqlPreviewPanelResultViewKt.showResult(p, buildResult(sql))
        p.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
        UIUtil.dispatchAllInvocationEvents()
        return p
    }

    private fun explainRequest() = ExecutionDialectGate.Request(
        ExecutionDialectGate.RenderMode.AUTO,
        "MySQL",
        ExecutionDialectGate.Intent.EXPLAIN,
        false,
        true,
        null,
    )

    override fun tearDown() {
        runCatching { panel?.let { SqlPreviewPanelSqlViewKt.clear(it) } }
        panel = null
        super.tearDown()
    }

    /**
     * W1 核心契约：有源时直送链把 EXPLAIN 交给执行链（内嵌原生控制台），
     * 且**不再**走旁路查询 + 结果对话框。
     */
    fun testExplainDirectGoesToEmbeddedNativeConsole() {
        val sql = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(sql)
        var attempted: String? = null
        var dialogShown = false
        SqlExecutor.executionAttemptForTest = SqlExecutor.ExecutionAttempt { attempted = it }
        SqlPreviewPanelExecutionKt.explainTargetForTest = java.util.function.Supplier { ExplainTarget() }
        ExplainPlanDialog.explainDialogSinkForTest = java.util.function.Consumer { dialogShown = true }
        try {
            SqlPreviewPanelExecutionKt.submitExplainDirect(p, project, "EXPLAIN $sql", explainRequest())
            // T-ExplainConsoleFirst W8-fix：submitExplainDirectConsole 改成「后台预热 → invokeLater
            // 续接执行链」的异步形态（避免 EDT 阻塞等预热）。故本断言必须等异步链落地，
            // 不能在 submitExplainDirect 返回后立刻读——那是首版同步实现才成立的时序。
            // 有界等待：轮询 + 调度 EDT 队列，最多约 10s（预热本身毫秒级，留足余量降 flaky）。
            val deadline = System.currentTimeMillis() + 10_000
            while (attempted == null && System.currentTimeMillis() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(20)
            }
            assertEquals(
                "执行计划必须交给内嵌原生控制台（形态 A），实际交给执行链的文本：$attempted",
                "EXPLAIN $sql",
                attempted,
            )
            assertFalse("不得再弹只读结果对话框（形态 B 已退役）", dialogShown)
        } finally {
            SqlExecutor.executionAttemptForTest = null
            SqlPreviewPanelExecutionKt.explainTargetForTest = null
            ExplainPlanDialog.explainDialogSinkForTest = null
        }
    }

    /**
     * 零源：不进执行链，直接给「去 Database 工具窗口」引导（指引语义不变，只是判定从
     * 「无绑定数据源」放宽为「一条在籍源都没有」——单条源的选源归平台）。
     */
    fun testExplainNoDataSourceOffersDatabaseToolWindow() {
        val sql = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(sql)
        var captured: ExplainPlanDialog? = null
        var attempted: String? = null
        SqlExecutor.executionAttemptForTest = SqlExecutor.ExecutionAttempt { attempted = it }
        // 测试缝返回 null 且工程无在籍数据源 → 零源形态
        SqlPreviewPanelExecutionKt.explainTargetForTest = java.util.function.Supplier { null }
        ExplainPlanDialog.explainDialogSinkForTest = java.util.function.Consumer { captured = it }
        try {
            SqlPreviewPanelExecutionKt.submitExplainDirect(p, project, "EXPLAIN $sql", explainRequest())
            if (cn.song.mybatisplus.visualizer.execute.DataSourceSelector.registeredDataSourceCount(project) == 0) {
                assertNotNull("零源时必须给出引导对话框", captured)
                val content = captured!!.contentForTest()
                assertEquals(
                    "无源时按钮应直达 Database 工具窗口",
                    MpSqlVisualizerBundle.message("panel.explain.openDatabase"),
                    buttons(content).single().text,
                )
                assertTrue(
                    "原因应说明去 Database 工具窗口选源：${texts(content)}",
                    texts(content).any { it.contains("Database") },
                )
                assertEquals("零源不得进执行链", null, attempted)
            } else {
                // headless 工程里若存在隐式在籍源，零源引导不适用——此处只断言「进了执行链」
                assertNotNull("有源时零源引导不应触发", attempted)
            }
            assertEquals("只读动作取词不受影响", sql, SqlPreviewPanelExecutionKt.previewSqlForReadonly(p))
        } finally {
            SqlExecutor.executionAttemptForTest = null
            SqlPreviewPanelExecutionKt.explainTargetForTest = null
            ExplainPlanDialog.explainDialogSinkForTest = null
        }
    }

    fun testExplainDialogCancelButtonFollowsPluginLanguage() {
        // 用户真机反馈：英文 IDE 下取消按钮显示 Cancel——必须按插件 bundle 覆盖。
        val dialog = ExplainPlanDialog(project, com.intellij.ui.components.JBPanel<com.intellij.ui.components.JBPanel<?>>())
        try {
            assertEquals(
                "取消按钮必须跟插件语言（不受 IDE 语言影响）",
                MpSqlVisualizerBundle.message("panel.explain.close"),
                dialog.cancelButtonTextForTest(),
            )
        } finally {
            dialog.close(0)
        }
    }

    private fun buttons(component: java.awt.Component): List<javax.swing.JButton> =
        (if (component is javax.swing.JButton) listOf(component) else emptyList()) +
            (if (component is java.awt.Container) component.components.flatMap { buttons(it) } else emptyList())

    /** 递归收集标签与表格单元格文本（对话框内容无公开访问器，按组件树取证）。 */
    private fun texts(component: java.awt.Component): List<String> =
        (if (component is javax.swing.JLabel) listOf(component.text ?: "") else emptyList()) +
            (if (component is javax.swing.JTable) {
                (0 until component.rowCount).flatMap { row ->
                    (0 until component.columnCount).map { col ->
                        component.getValueAt(row, col)?.toString() ?: ""
                    }
                }
            } else emptyList()) +
            (if (component is java.awt.Container) component.components.flatMap { texts(it) } else emptyList())
}
