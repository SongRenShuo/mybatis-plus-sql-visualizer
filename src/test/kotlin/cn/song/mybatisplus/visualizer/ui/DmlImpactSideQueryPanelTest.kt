package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * T-PreviewSideQuery P1：预估影响行数走只读旁路查询，不改写预览编辑器/不换入控制台。
 */
class DmlImpactSideQueryPanelTest : BasePlatformTestCase() {

    /** 必须是 public：生产反射按 getUrl 取目标方言，private 嵌套类会让 invoke 失败。 */
    class ImpactTarget {
        @Suppress("unused")
        fun getUrl(): String = "jdbc:mysql://localhost:3306/demo"
    }

    override fun setUp() {
        super.setUp()
        // S2-4 券②：estimateDmlImpact 增 Community 代码侧守卫——本类验 Ultimate 态行为链，
        // headless 无 Database 标记类需覆写探测（RollbackSqlDialogTest 同款）。
        cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.availabilityOverrideForTest = true
    }

    override fun tearDown() {
        cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.availabilityOverrideForTest = null
        super.tearDown()
    }

    private fun panel(sql: String): SqlPreviewPanel = SqlPreviewPanel(project).also {
        Disposer.register(testRootDisposable, it)
        it.showResult(
            PreviewResult(sql, sql, emptyList(), null, null, "t_order", renderedDialect = MySqlDialect),
        )
        it.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
        UIUtil.dispatchAllInvocationEvents()
    }

    fun testEstimateUsesSideQueryAndKeepsEditor() {
        val sql = "UPDATE t_order SET status = 1 WHERE id = 1111"
        val p = panel(sql)
        val beforeDoc = p.nativeEditorDoc?.text
        val beforeConsole = p.embeddedConsole
        dmlImpactTargetForTest = { ImpactTarget() }
        PreviewSideQuery.runnerForTest = { _, _, _, scalar, cb ->
            assertTrue("预估影响行数必须走单值旁路查询", scalar)
            cb(
                PreviewSideQuery.Result.Scalar(
                    7,
                    McpExplainFormatter.RawResult(listOf("COUNT(*)"), listOf(listOf("7"))),
                ),
            )
        }
        try {
            p.estimateDmlImpact()
            assertEquals("只读试算不得改写预览编辑器", beforeDoc, p.nativeEditorDoc?.text)
            assertEquals(sql, p.currentEditorSql())
            assertSame("只读试算不得换入控制台", beforeConsole, p.embeddedConsole)
            val label = p.impactResultLabel!!
            assertTrue("内联结果应显示行数：${label.text}", label.text.contains("7"))
            assertEquals("内联结果应为灰色说明", UIUtil.getContextHelpForeground(), label.foreground)
            assertTrue("内联行 tooltip 应包含试算语句详情：${label.toolTipText}", label.toolTipText.contains("COUNT(*)"))
            // T-DmlImpactProminence：结果同时挂到顶部独立横幅（蓝色加粗），比左下角灰字显眼。
            assertTrue("结果横幅应可见", p.dmlImpactBannerForTest().isVisible)
            assertTrue(
                "横幅应显示结果：${p.dmlImpactBannerTextForTest()}",
                p.dmlImpactBannerTextForTest().contains("7"),
            )
        } finally {
            dmlImpactTargetForTest = null
            PreviewSideQuery.runnerForTest = null
            p.clear()
        }
    }

    fun testNotApplicableReplacesStaleInlineResult() {
        val sql = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(sql)
        try {
            p.estimateDmlImpact()
            assertEquals(sql, p.currentEditorSql())
            val label = p.impactResultLabel!!
            assertTrue("不可试算原因应覆盖旧结果：${label.text}", label.text.contains("UPDATE/DELETE"))
            assertEquals(com.intellij.ui.JBColor.RED, label.foreground)
        } finally {
            p.clear()
        }
    }

    fun testNoBoundDataSourcePromptsSelectorAndRetries() {
        val sql = "UPDATE t_order SET status = 1 WHERE id = 1111"
        val p = panel(sql)
        var target: Any? = null
        var promptedSql: String? = null
        dmlImpactTargetForTest = { target }
        dmlImpactPromptForTest = { _, s, _ -> promptedSql = s }
        dmlImpactAwaitBindForTest = { _, _, onBound, _ ->
            target = ImpactTarget()
            onBound()
        }
        PreviewSideQuery.runnerForTest = { _, _, _, scalar, cb ->
            assertTrue(scalar)
            cb(PreviewSideQuery.Result.Scalar(3, McpExplainFormatter.RawResult(listOf("COUNT(*)"), listOf(listOf("3")))))
        }
        try {
            p.estimateDmlImpact()
            assertEquals("绑定应使用当前预览 SQL", sql, promptedSql)
            assertEquals(sql, p.currentEditorSql())
            assertTrue("绑定后应重试并显示行数：${p.impactResultLabel!!.text}", p.impactResultLabel!!.text.contains("3"))
        } finally {
            dmlImpactTargetForTest = null
            dmlImpactPromptForTest = null
            dmlImpactAwaitBindForTest = null
            PreviewSideQuery.runnerForTest = null
            p.clear()
        }
    }

    fun testNoBoundDataSourceGiveUpShowsInlineReason() {
        val sql = "UPDATE t_order SET status = 1 WHERE id = 1111"
        val p = panel(sql)
        var target: Any? = null
        dmlImpactTargetForTest = { target }
        dmlImpactPromptForTest = { _, _, _ -> }
        dmlImpactAwaitBindForTest = { _, _, _, onGiveUp -> onGiveUp() }
        try {
            p.estimateDmlImpact()
            val label = p.impactResultLabel!!
            assertTrue("取消绑定后应就地提示：${label.text}", label.text.contains("数据源"))
            assertEquals(com.intellij.ui.JBColor.RED, label.foreground)
            assertEquals(sql, p.currentEditorSql())
        } finally {
            dmlImpactTargetForTest = null
            dmlImpactPromptForTest = null
            dmlImpactAwaitBindForTest = null
            p.clear()
        }
    }

    fun testEstimateFailureShowsInlineErrorWithoutTouchingEditor() {
        val sql = "UPDATE t_order SET status = 1 WHERE id = 1111"
        val p = panel(sql)
        dmlImpactTargetForTest = { ImpactTarget() }
        PreviewSideQuery.runnerForTest = { _, _, _, _, cb -> cb(PreviewSideQuery.Result.Failed("boom")) }
        try {
            p.estimateDmlImpact()
            assertEquals(sql, p.currentEditorSql())
            val label = p.impactResultLabel!!
            assertTrue("失败原因应内联展示：${label.text}", label.text.contains("boom"))
            assertEquals(com.intellij.ui.JBColor.RED, label.foreground)
            assertTrue("失败横幅应可见", p.dmlImpactBannerForTest().isVisible)
            assertTrue(
                "失败原因应进横幅：${p.dmlImpactBannerTextForTest()}",
                p.dmlImpactBannerTextForTest().contains("boom"),
            )
        } finally {
            dmlImpactTargetForTest = null
            PreviewSideQuery.runnerForTest = null
            p.clear()
        }
    }

    fun testDeleteLimitShowsCappedInlineRow() {
        // DELETE ... LIMIT 5：试算天然有 5 行上限，内联文案必须走「≤」分支
        val sql = "DELETE FROM t_order WHERE id = 1 LIMIT 5"
        val p = panel(sql)
        dmlImpactTargetForTest = { ImpactTarget() }
        PreviewSideQuery.runnerForTest = { _, _, _, _, cb -> cb(PreviewSideQuery.Result.Scalar(9, raw("9"))) }
        try {
            p.estimateDmlImpact()
            val label = p.impactResultLabel!!
            assertTrue("上限结果应显示 ≤5：${label.text}", label.text.contains("≤") && label.text.contains("5"))
            assertEquals("上限提示不是错误", UIUtil.getContextHelpForeground(), label.foreground)
            assertEquals(sql, p.currentEditorSql())
        } finally {
            dmlImpactTargetForTest = null
            PreviewSideQuery.runnerForTest = null
            p.clear()
        }
    }

    fun testBlockedResultShowsInlineMessage() {
        val sql = "UPDATE t_order SET status = 1 WHERE id = 1111"
        val p = panel(sql)
        dmlImpactTargetForTest = { ImpactTarget() }
        PreviewSideQuery.runnerForTest = { _, _, _, _, cb ->
            cb(PreviewSideQuery.Result.Blocked("dialect mismatch"))
        }
        try {
            p.estimateDmlImpact()
            val label = p.impactResultLabel!!
            assertTrue("方言闸阻断原因应内联展示：${label.text}", label.text.contains("dialect mismatch"))
            assertEquals(com.intellij.ui.JBColor.RED, label.foreground)
        } finally {
            dmlImpactTargetForTest = null
            PreviewSideQuery.runnerForTest = null
            p.clear()
        }
    }

    fun testUnexpectedTabularResultIsReportedAsError() {
        val sql = "UPDATE t_order SET status = 1 WHERE id = 1111"
        val p = panel(sql)
        dmlImpactTargetForTest = { ImpactTarget() }
        PreviewSideQuery.runnerForTest = { _, _, _, _, cb -> cb(PreviewSideQuery.Result.Table(raw("9"))) }
        try {
            p.estimateDmlImpact()
            val label = p.impactResultLabel!!
            assertTrue("单值通道拿到表格必须如实报错：${label.text}", label.text.contains("unexpected"))
            assertEquals(com.intellij.ui.JBColor.RED, label.foreground)
        } finally {
            dmlImpactTargetForTest = null
            PreviewSideQuery.runnerForTest = null
            p.clear()
        }
    }

    fun testNoPromptPathShowsNoDataSourceWithoutOpeningSelector() {
        // 绑定失败后的重试路径：allowDataSourcePrompt=false，只提示不二次弹选择器
        val sql = "UPDATE t_order SET status = 1 WHERE id = 1111"
        val p = panel(sql)
        var promptCalls = 0
        dmlImpactTargetForTest = { null }
        dmlImpactPromptForTest = { _, _, _ -> promptCalls++ }
        try {
            p.estimateDmlImpact(allowDataSourcePrompt = false)
            assertEquals("不得二次弹数据源选择器", 0, promptCalls)
            val label = p.impactResultLabel!!
            assertTrue("无绑定数据源应就地提示：${label.text}", label.text.contains("数据源"))
            assertEquals(com.intellij.ui.JBColor.RED, label.foreground)
            assertEquals(sql, p.currentEditorSql())
        } finally {
            dmlImpactTargetForTest = null
            dmlImpactPromptForTest = null
            p.clear()
        }
    }

    private fun raw(value: String) = McpExplainFormatter.RawResult(listOf("COUNT(*)"), listOf(listOf(value)))
}
