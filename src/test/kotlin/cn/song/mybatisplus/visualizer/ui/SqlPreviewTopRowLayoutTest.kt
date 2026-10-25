package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import javax.swing.JButton

/**
 * T-PreviewTopOverlap：预览面板顶部行布局 / 折叠菜单回归测试。
 *
 * 缺陷背景：topRow 曾用 BorderLayout WEST(metaLabel)+EAST(工具栏)，两偏好宽之和超容器宽时
 * 直接互相重叠（用户真机截图：性能建议文字压进按钮行）。修复后 metaLabel 居 CENTER（结构性
 * 零重叠）+ 宽度预算截断 + 低频按钮（导入参数/差异对比/分享）折叠进「更多」JBPopup 菜单。
 *
 * headless 约束：截断依赖布局宽度的部分经 applyMetaTruncationForTest 直调；
 * 「更多」弹层 show 依赖真实屏幕，仅断言菜单项与 step 委托，不真实弹层。
 */
class SqlPreviewTopRowLayoutTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    private fun makeResult(
        warning: String? = null,
        perfWarnings: List<PerfWarning>? = null,
    ) = PreviewResult(
        "SELECT id\nFROM user\nWHERE username = ?\nORDER BY id DESC",
        "SELECT id FROM user WHERE username = ? ORDER BY id DESC",
        listOf("username"),
        null,
        WrapperType.LAMBDA_QUERY,
        "user",
        warning,
        listOf("id", "username"),
        emptyMap(),
        null,
        null,
        emptyList(),
        emptyList(),
        null,
        null,
        emptyMap(),
        perfWarnings,
        null,
        null,
        null,
    )

    private fun newPanel() = SqlPreviewPanel(project).also {
        // T-PreviewFlash 语境下测试默认压缩关闭即可，与本测试无关，仅保持渲染确定性
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true }
        SqlPreviewPanelResultViewKt.showResult(it, 
            makeResult(
                perfWarnings = listOf(
                    PerfWarning("order_by_without_limit", PerfSeverity.WARN, "perf.order_by_without_limit"),
                ),
            ),
        )
        waitForEdt()
    }

    private fun layoutAt(panel: SqlPreviewPanel, width: Int, height: Int = 420) {
        panel.setSize(width, height)
        panel.doLayout()
        // T-PreviewUx-Pack #2：topRow 外包 northHost（诚实降级横幅宿主）——布局链多一层，手动逐层 doLayout
        panel.topRowForTest().parent?.doLayout()
        panel.topRowForTest().doLayout()
        panel.applyMetaTruncationForTest()
        waitForEdt()
    }

    // ------------------------------------------------------------------
    // F1：布局零重叠（核心回归断言）
    // ------------------------------------------------------------------

    fun test窄宽布局_元信息与工具栏零重叠() {
        val panel = newPanel()
        try {
            layoutAt(panel, 640)
            val label = metaLabelOf(panel)
            val toolbar = panel.toolbarForTest()
            assertTrue(
                "CENTER 标签右界(${label.x + label.width})不得越过工具栏左界(${toolbar.x})",
                label.x + label.width <= toolbar.x + 1,
            )
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
            panel.dispose()
        }
    }

    fun test最小宽布局_性能建议在场仍零重叠() {
        val panel = newPanel()
        try {
            layoutAt(panel, 640)
            val label = metaLabelOf(panel)
            assertTrue("窄宽下应有正尺寸", label.width > 0 && panel.toolbarForTest().width > 0)
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
            panel.dispose()
        }
    }

    // ------------------------------------------------------------------
    // F3：宽度预算截断（窄截断 / 宽还原 / tooltip 恒全文）
    // ------------------------------------------------------------------

    fun test窄宽截断生效_性能建议行被省略() {
        val panel = newPanel()
        try {
            layoutAt(panel, 640)
            val text = panel.metaLabelForTest()
            assertTrue("窄宽下应走 HTML 两行渲染", text.startsWith("<html>"))
            // 预算约 640-工具栏(~330)-32 边距 ≈ 270px，长 perf 行必然放不下 → 丢尾段加省略号
            assertTrue("放不下的 perf 行应加省略号", text.contains("\u2026"))
            assertFalse("截断后不得再含完整 perf 文案", text.contains(fullPerfText()))
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
            panel.dispose()
        }
    }

    fun test宽幅还原_全文无省略号() {
        val panel = newPanel()
        try {
            layoutAt(panel, 640)
            layoutAt(panel, 1600)
            val text = panel.metaLabelForTest()
            assertTrue("宽幅应还原全文 perf 文案", text.contains(fullPerfText()))
            assertFalse("宽幅不应残留省略号", text.contains("\u2026"))
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
            panel.dispose()
        }
    }

    fun test截断后tooltip仍为全文() {
        val panel = newPanel()
        try {
            layoutAt(panel, 640)
            val tooltip = panel.topRowForTest().let { metaLabelOf(panel).toolTipText ?: "" }
            assertTrue("tooltip 应含完整 perf 文案", tooltip.contains(fullPerfText()))
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
            panel.dispose()
        }
    }

    // ------------------------------------------------------------------
    // F2：工具栏折叠（直显集合 / 复制错误不折叠 / 菜单项与 step 委托）
    // ------------------------------------------------------------------

    fun test直显按钮集合_高频保留低频折叠() {
        val panel = newPanel()
        try {
            val directTexts = panel.toolbarForTest().components.filterIsInstance<JButton>().map { it.text }.toSet()
            for (key in listOf("panel.more", "panel.format", "panel.keywordWrap", "panel.refresh", "panel.explain")) {
                val expected = MpSqlVisualizerBundle.message(key)
                assertTrue("直显按钮应含 $key($expected)", expected in directTexts)
            }
            // 模式切换按钮文案随 compactMode 二态（压缩/多行），任一在场即视为直显
            val modeTexts = listOf("panel.mode.singleline", "panel.mode.multiline").map { MpSqlVisualizerBundle.message(it) }
            assertTrue("模式切换按钮应直显（压缩或多行任一文案）", modeTexts.any { it in directTexts })
            for (key in listOf("panel.param.import", "panel.compare", "panel.share", "panel.rollbackSql", "panel.portableExport", "panel.replayExport", "panel.aiContext", "panel.aiContext.identifiers", "panel.dmlImpact")) {
                val folded = MpSqlVisualizerBundle.message(key)
                assertFalse("低频按钮 $key($folded) 不应直显", folded in directTexts)
            }
            assertEquals(
                "「更多」菜单应恰为 回滚 SQL/导入参数/差异对比/分享/导出可移植 SQL/导出回放脚本/复制为 AI 上下文/复制为 AI 上下文（含表列名）/DML 影响面 九项且顺序稳定" +
                    "（T-UiCoord F1 起回滚 SQL 居首；O-91 增导出可移植 SQL；R36 增导出回放脚本；R37 增复制为 AI 上下文；T-AiContextLegibility 增含表列名出口；R42 增 DML 影响面）",
                listOf(
                    MpSqlVisualizerBundle.message("panel.rollbackSql"),
                    MpSqlVisualizerBundle.message("panel.param.import"),
                    MpSqlVisualizerBundle.message("panel.compare"),
                    MpSqlVisualizerBundle.message("panel.share"),
                    MpSqlVisualizerBundle.message("panel.portableExport"),
                    MpSqlVisualizerBundle.message("panel.replayExport"),
                    MpSqlVisualizerBundle.message("panel.aiContext"),
                    MpSqlVisualizerBundle.message("panel.aiContext.identifiers"),
                    MpSqlVisualizerBundle.message("panel.dmlImpact"),
                ),
                panel.moreActionsForTest(),
            )
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
            panel.dispose()
        }
    }

    fun test警示时复制错误按钮直显不折叠() {
        val panel = SqlPreviewPanel(project)
        try {
            SqlPreviewPanelResultViewKt.showResult(panel, makeResult(warning = "boom 解析失败"))
            waitForEdt()
            val errorCopy = panel.errorCopyButtonForTest()
            assertNotNull("警示在场应有复制错误按钮", errorCopy)
            assertTrue("复制错误按钮应可见", errorCopy!!.isVisible)
            assertEquals("复制错误按钮应直挂工具栏（永不折叠）", panel.toolbarForTest(), errorCopy.parent)
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
            panel.dispose()
        }
    }

    fun test更多菜单step_命中失败项安全返回FINAL_CHOICE() {
        val panel = newPanel()
        try {
            val step = panel.moreActionsStepForTest()
            // 未命中项不触发任何动作、安全收尾（弹层单选语义）。
            // 真实三项的委托动作（导入弹参数框 / Diff 式对比 / 分享弹窗）会弹模态层，
            // 单测环境禁弹（项目惯例：DialogWrapper.show 留 runIde 真机），故仅验未命中路径。
            assertEquals(PopupStep.FINAL_CHOICE, step.onChosen("no-such-item", true))
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
            panel.dispose()
        }
    }

    /**
     * N4 回归守护（2026-09-21）：命中真实项时**不得在 onChosen 内同步执行动作**。
     *
     * 缺陷形态：`moreActionsStep.onChosen` 此前同步调用动作体，而三项动作都会弹模态对话框 →
     * 弹层尚未销毁就把对话框塞进去，平台记 SEVERE「Showing dialogs in PopupStep.onChosen can
     * result in focus issues…」（原文即修法：put the dialog showing code into invokeLater），
     * **真机后果是第一次点「确定」被弹层收走**（用户感受「点了没反应」）。
     *
     * 单测禁弹模态，故用无副作用替身项只断言「未同步触发」——延后体在不抽干 EDT 队列的前提下
     * 不会执行；抽干队列会真弹对话框，故本用例**刻意不抽队列**。
     *
     * K2J 互操作：`moreActionsStepWithForTest` 是 Java 方法、形参为 `List<kotlin.Pair<String, Runnable>>`——
     * `Pair` 第二元是 Java 函数式接口，Kotlin lambda **字面量**在嵌套泛型推断里不做隐式 SAM 转换，
     * 故显式写 `Runnable { … }`（与 master 的 `{ invoked = true }` 语义逐字等价）。
     */
    fun test更多菜单step_命中真实项不同步触发动作() {
        val panel = newPanel()
        try {
            var invoked = false
            val step = panel.moreActionsStepWithForTest(listOf("only-item" to Runnable { invoked = true }))
            assertEquals("单选语义不变", PopupStep.FINAL_CHOICE, step.onChosen("only-item", true))
            assertFalse("动作不得在 onChosen 内同步执行（N4 回归点）", invoked)
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
            panel.dispose()
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun metaLabelOf(panel: SqlPreviewPanel) =
        panel.topRowForTest().components.filterIsInstance<com.intellij.ui.components.JBLabel>().first()

    private fun fullPerfText(): String = "⚠ " + MpSqlVisualizerBundle.message("perf.order_by_without_limit")
}
