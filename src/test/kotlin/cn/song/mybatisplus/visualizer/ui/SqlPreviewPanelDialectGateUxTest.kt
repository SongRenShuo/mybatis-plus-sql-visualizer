package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-DialectGateUx 接线：方言闸门阻断态的错误行必须挂出「切换数据源」入口。
 *
 * 沙盒实证过的坑：`DataSourceSelector.allowDialectGate` 在同一次调用栈里先调 `onDialectGate`
 * 再调旧 `onError`（纯文案），后者会把刚挂上的动作清掉——带动作的呈现因此延迟到下一拍 EDT。
 * 本用例必须抽干 EDT 队列后再断言，锁死「延迟呈现确实落地」。
 */
class SqlPreviewPanelDialectGateUxTest : BasePlatformTestCase() {

    fun test闸门阻断_错误行挂出切换数据源入口() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showDialectGateError(
                ExecutionDialectGate.Verdict(
                    ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
                    "PostgreSQL",
                    "MySQL",
                ),
            )
            UIUtil.dispatchAllInvocationEvents()

            val fix = panel.resultPanelForTest().fixActionForTest()
            assertTrue("闸门阻断应挂出修复入口", fix.isVisible)
            assertEquals("切换数据源", fix.text)
        } finally {
            panel.clear()
        }
    }

    fun test闸门阻断_普通showError再投递不得清掉修复入口顺序错误可判() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showDialectGateError(
                ExecutionDialectGate.Verdict(
                    ExecutionDialectGate.Status.MANUAL_CONFLICT,
                    "PostgreSQL",
                    "MySQL",
                ),
            )
            UIUtil.dispatchAllInvocationEvents()
            val fix = panel.resultPanelForTest().fixActionForTest()
            assertTrue("手动方言冲突同样应挂出修复入口", fix.isVisible)
        } finally {
            panel.clear()
        }
    }

    /**
     * T-DialectGatePass（2026-10-26 语义对齐，对齐 commit 8619a3af 用户定案）：预览入口的
     * MANUAL_CONFLICT / RERENDER_UNAVAILABLE 不再走 [showDialectGateError] 阻断（错误态+动作），
     * 改走 showStatus 信息态呈现方言不一致提示，执行继续（与选择器手选路径一致）。
     * 呈现延迟到下一拍 EDT（见 [showDialectGateError] 注释的同栈 onError 覆盖坑），故先抽干队列。
     */
    fun test闸门放行_手动冲突显示状态提示不进错误态() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.onExecutionDialectGate(
                ExecutionDialectGate.Verdict(
                    ExecutionDialectGate.Status.MANUAL_CONFLICT,
                    "PostgreSQL",
                    "MySQL",
                ),
            )
            UIUtil.dispatchAllInvocationEvents()
            val rp = panel.resultPanelForTest()
            assertEquals("放行语义不应进错误态", "EMPTY", rp.stateForTest())
            assertFalse("放行语义不应挂错误文案", rp.currentErrorForTest() != null)
            // T-DialectGatePassDeadChain（2026-11-02 用户实测「点切换数据源 没有看到在哪点」）：
            // 放行态也要挂「切换数据源」入口——文案本身就写着「可点「切换数据源」」。
            assertTrue("放行语义同样要挂换源入口", rp.fixActionForTest().isVisible)
            assertEquals("切换数据源", rp.fixActionForTest().text)
            val text = rp.infoTextForTest()
            assertTrue("状态行应报出渲染方言", text.contains("PostgreSQL"))
            assertTrue("状态行应报出目标方言", text.contains("MySQL"))
            assertFalse("放行语义文案不应再含「已阻止执行」", text.contains("已阻止执行"))
        } finally {
            panel.clear()
        }
    }

    fun test闸门放行_重渲染不可用同样显示状态提示不进错误态() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.onExecutionDialectGate(
                ExecutionDialectGate.Verdict(
                    ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
                    "MySQL",
                    "PostgreSQL",
                ),
            )
            UIUtil.dispatchAllInvocationEvents()
            val rp = panel.resultPanelForTest()
            assertEquals("放行语义不应进错误态", "EMPTY", rp.stateForTest())
            assertFalse("放行语义不应挂错误文案", rp.currentErrorForTest() != null)
            assertTrue("状态行应报出双方方言", rp.infoTextForTest().contains("PostgreSQL"))
            assertTrue("重渲染不可用同样要挂换源入口", rp.fixActionForTest().isVisible)
        } finally {
            panel.clear()
        }
    }

    /**
     * T-DialectGatePassDeadChain：信息态状态行同样挂两个蓝字入口，点击分别触发注入的动作。
     * 直接驱动 [SqlResultPanel.showStatusWithActions]（不经面板换源链，避免真建控制台/会话）。
     */
    fun test状态行带动作_两个蓝字入口可点且各自触发() {
        val panel = SqlPreviewPanel(project)
        try {
            val rp = panel.resultPanelForTest()
            var fixed = 0
            var settings = 0
            rp.showStatusWithActions(
                "方言不一致：当前 SQL 按 GaussDB 渲染，所选数据源是 PostgreSQL。",
                fixLabel = "切换数据源",
                fixTooltip = "tip",
                fix = { fixed++ },
                settingsLabel = "方言设置",
                settingsTooltip = "tip2",
                settings = { settings++ },
            )
            assertEquals("信息态底色不变", "EMPTY", rp.stateForTest())
            assertNull("信息态不挂错误文案", rp.currentErrorForTest())
            val fix = rp.fixActionForTest()
            val set = rp.settingsActionForTest()
            assertTrue("换源入口应可见", fix.isVisible)
            assertEquals("切换数据源", fix.text)
            assertTrue("设置入口应可见", set.isVisible)
            assertEquals("方言设置", set.text)
            fix.doClick()
            set.doClick()
            assertEquals("点击应触发换源动作", 1, fixed)
            assertEquals("点击应触发打开设置动作", 1, settings)
        } finally {
            panel.clear()
        }
    }

    // ------------------------------------------------------------------
    // UI-004：底部方言闸门取值与顶部/实际渲染同源
    // ------------------------------------------------------------------

    private fun mysqlDecision() = cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision(
        cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Status.RESOLVED,
        "MySQL",
        cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Confidence.STRONG,
        cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Source.BOUND_DATASOURCE,
    )

    private fun plainResult(limitLine: String? = null) =
        cn.song.mybatisplus.visualizer.model.PreviewResult(
            sql = "SELECT 1",
            compactSql = "SELECT 1",
            unresolvedParams = emptyList(),
            paramTable = null,
            wrapperType = null,
            tableName = null,
            renderedDialect = cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect,
            paginationLimitLine = limitLine,
        )

    /**
     * 现场矛盾的直接回归：顶部决策已是 MySQL（已绑定数据源），预览结果里固化的渲染方言仍是
     * PostgreSQL（XML 载体无法重渲染）——闸门必须报 MySQL（MATCH），不得报 PostgreSQL。
     */
    fun test闸门取值_方言无关载体取最新决策不得报陈旧方言() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(plainResult())
            panel.currentDialectDecision = mysqlDecision()
            val request = panel.executionDialectGateRequest(ExecutionDialectGate.Intent.QUERY)
            assertEquals("方言无关载体应取最新决策", "MySQL", request.renderedDialectName)
            assertFalse("XML/纯文本载体本就无法无损重渲染", request.canLosslesslyRerender)
        } finally {
            panel.clear()
        }
    }

    /** 方言敏感载体（分页 LIMIT 行按方言生成）仍以渲染事实为准。 */
    fun test闸门取值_分页载体保留渲染事实() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(plainResult(limitLine = "LIMIT ?, ?"))
            panel.currentDialectDecision = mysqlDecision()
            val request = panel.executionDialectGateRequest(ExecutionDialectGate.Intent.QUERY)
            assertEquals("分页 LIMIT 行是方言敏感产物，渲染事实优先", "PostgreSQL", request.renderedDialectName)
        } finally {
            panel.clear()
        }
    }

    /** 证据对齐只对「文本未编辑」的方言无关载体生效。 */
    fun test证据对齐_方言无关且文本未编辑时更新渲染方言() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(plainResult())
            assertEquals("前置：渲染方言应为 PostgreSQL", "PostgreSQL", panel.currentDialect())
            assertTrue("方言无关载体应允许证据对齐", panel.adoptDialectForNeutralCarrier(mysqlDecision()))
            assertEquals("对齐后与顶部决策同源", "MySQL", panel.currentDialect())
        } finally {
            panel.clear()
        }
    }

    fun test证据对齐_方言敏感载体不动() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(plainResult(limitLine = "LIMIT ?, ?"))
            assertFalse("分页载体不得被证据对齐（LIMIT 行按方言生成）",
                panel.adoptDialectForNeutralCarrier(mysqlDecision()))
            assertEquals("渲染事实保持不变", "PostgreSQL", panel.currentDialect())
        } finally {
            panel.clear()
        }
    }
}
