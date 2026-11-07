package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import javax.swing.Action

/**
 * S2-4 券②：Community（无 Database 插件）下**全部执行类入口**点击前预判置灰。
 *
 * 本批补齐的三个缺口（前批「新功能小券批 券3」已覆盖弹窗执行 SQL/工具栏执行计划/模板三动作/
 * 日志面板执行，见 SqlPreviewDialogTest 同名用例）：
 *  - 回滚对话框「执行回滚」底部动作（executeAdhocSql → SqlExecutor 控制台执行）
 *  - 回滚对话框「执行快照查询」按钮（PreviewSideQuery 对绑定源执行 SELECT）
 *  - 「更多」菜单「DML 影响面预演」条目（PreviewSideQuery 对绑定源发 COUNT 试算）
 *
 * 测试沙盒无 Database 标记类 → [ExecuteSqlSupport.isAvailable] 恒 false = Community 形态
 * （SqlPreviewDialogTest 置灰用例同口径）；Ultimate 态用 [ExecuteSqlSupport.availabilityOverrideForTest]
 * 覆写（RollbackSqlDialogTest 同款）。
 */
class CommunityExecutionEntriesGrayedTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    override fun tearDown() {
        ExecuteSqlSupport.availabilityOverrideForTest = null
        runCatching { panel?.clear() }
        panel = null
        super.tearDown()
    }

    private fun newPanel(): SqlPreviewPanel {
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        return p
    }

    private fun completeInsertResult() = RollbackSqlGenerator.generate(
        "INSERT INTO user (id, name) VALUES (1, 'Tom')",
        primaryKeyColumns = listOf("id"),
    )

    private fun unableUpdateResult() = RollbackSqlGenerator.generate(
        "UPDATE user SET age = 3 WHERE id = 1",
    )

    private fun newDialog(result: RollbackSqlGenerator.RollbackResult): RollbackSqlDialog {
        val p = newPanel()
        // RollbackSqlDialog 是 DialogWrapper（非 Disposable）：按既有 RollbackSqlDialogTest 口径，
        // 由各用例 finally 里 doDispose() 收尾，不注册 Disposer 树。
        return RollbackSqlDialog(project, p, result, "INSERT", null)
    }

    // ------------------------------------------------------------------
    // 回滚对话框「执行回滚」
    // ------------------------------------------------------------------

    fun testCommunity下执行回滚动作预判置灰并带tooltip() {
        val dialog = newDialog(completeInsertResult())
        try {
            assertFalse("本用例前提=测试沙盒无 Database 插件（Community 形态）", ExecuteSqlSupport.isAvailable())
            val action = dialog.executeActionForTest()
            assertFalse("Community 下「执行回滚」应预判置灰", action.isEnabled)
            assertEquals(
                "置灰必须带说明 tooltip",
                MpSqlVisualizerBundle.message("execute.unavailable.tooltip"),
                action.getValue(Action.SHORT_DESCRIPTION),
            )
            // 程序化触发也必须被守卫拦下（灰置只挡 UI，不挡代码路径）
            action.actionPerformed(java.awt.event.ActionEvent(dialog, 0, ""))
        } finally {
            dialog.doDispose()
        }
    }

    fun testUltimate态下执行回滚动作恢复可用() {
        ExecuteSqlSupport.availabilityOverrideForTest = true
        val dialog = newDialog(completeInsertResult())
        try {
            val action = dialog.executeActionForTest()
            assertTrue("Ultimate 态「执行回滚」应可用", action.isEnabled)
            assertNull(
                "可用时不得挂置灰 tooltip",
                action.getValue(Action.SHORT_DESCRIPTION),
            )
            assertEquals(
                "Complete 评级横幅应仍为可执行",
                MpSqlVisualizerBundle.message("rollback.banner.complete"),
                dialog.bannerTextForTest(),
            )
        } finally {
            dialog.doDispose()
        }
    }

    // ------------------------------------------------------------------
    // 回滚对话框「执行快照查询」按钮
    // ------------------------------------------------------------------

    fun testCommunity下快照查询按钮预判置灰并带tooltip() {
        val dialog = newDialog(unableUpdateResult())
        try {
            assertTrue("前置：UPDATE 应有快照工作流", dialog.snapshotQueryLabelForTest().text.isNotEmpty())
            val button = dialog.snapshotRunButtonForTest()
            assertNotNull("快照工作流应建「执行快照查询」按钮", button)
            assertFalse("Community 下快照查询按钮应预判置灰", button!!.isEnabled)
            assertEquals(
                "置灰 tooltip 必须换成不可用说明（原查询说明对灰按钮无意义）",
                MpSqlVisualizerBundle.message("execute.unavailable.tooltip"),
                button.toolTipText,
            )
        } finally {
            dialog.doDispose()
        }
    }

    fun testUltimate态下快照查询按钮恢复可用且保留查询说明tooltip() {
        ExecuteSqlSupport.availabilityOverrideForTest = true
        val dialog = newDialog(unableUpdateResult())
        try {
            val button = dialog.snapshotRunButtonForTest()
            assertNotNull(button)
            assertTrue("Ultimate 态快照查询按钮应可用", button!!.isEnabled)
            assertEquals(
                "可用时 tooltip 应为查询说明本体",
                MpSqlVisualizerBundle.message("rollback.snapshot.run.tooltip"),
                button.toolTipText,
            )
        } finally {
            dialog.doDispose()
        }
    }

    // ------------------------------------------------------------------
    // 「更多」菜单「DML 影响面预演」条目
    // ------------------------------------------------------------------

    fun testCommunity下更多菜单DML影响面条目不可选() {
        val p = newPanel()
        val step = p.moreActionsStepWithForTest(p.moreActions())
        val dmlLabel = MpSqlVisualizerBundle.message("panel.dmlImpact")
        val rollbackLabel = MpSqlVisualizerBundle.message("panel.rollbackSql")
        assertFalse("Community 下 DML 影响面（执行类）应不可选（平台渲染为置灰）", step.isSelectable(dmlLabel))
        assertTrue(
            "回滚 SQL 条目不算执行类（对话框粘贴生成路径无 DB 也可用），保持可选",
            step.isSelectable(rollbackLabel),
        )
    }

    fun testUltimate态下更多菜单DML影响面条目可选() {
        ExecuteSqlSupport.availabilityOverrideForTest = true
        val p = newPanel()
        val step = p.moreActionsStepWithForTest(p.moreActions())
        assertTrue(
            "Ultimate 态 DML 影响面条目应可选",
            step.isSelectable(MpSqlVisualizerBundle.message("panel.dmlImpact")),
        )
    }
}
