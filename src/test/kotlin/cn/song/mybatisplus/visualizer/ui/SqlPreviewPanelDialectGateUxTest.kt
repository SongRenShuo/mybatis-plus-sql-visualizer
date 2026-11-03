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
 *
 * k2j 互操作（T-SyncK2JMaster136 B2 移植）：
 *  - master 的面板成员 showDialectGateError/onExecutionDialectGate/clear 在 k2j 为
 *    SqlPreviewPanelExecutionKt / SqlPreviewPanelSqlViewKt 的静态方法——改静态调用；
 *  - 「切换数据源」动作按钮复用 SqlResultPanel 私有 errorLocateButton（无 fixActionForTest
 *    访问器）——测试反射读取（不触生产码）。
 */
class SqlPreviewPanelDialectGateUxTest : BasePlatformTestCase() {

    /** k2j：修复入口按钮＝SqlResultPanel 私有 errorLocateButton（showErrorWithActions 复用位）。 */
    private fun fixButton(panel: SqlPreviewPanel): javax.swing.JButton {
        val f = SqlResultPanel::class.java.getDeclaredField("errorLocateButton")
        f.isAccessible = true
        return f.get(panel.resultPanelForTest()) as javax.swing.JButton
    }

    /** k2j：设置入口按钮＝SqlResultPanel 私有 errorSettingsButton（同反射形态）。 */
    private fun settingsButton(panel: SqlPreviewPanel): javax.swing.JButton {
        val f = SqlResultPanel::class.java.getDeclaredField("errorSettingsButton")
        f.isAccessible = true
        return f.get(panel.resultPanelForTest()) as javax.swing.JButton
    }

    fun test闸门阻断_错误行挂出切换数据源入口() {
        val panel = SqlPreviewPanel(project)
        try {
            SqlPreviewPanelExecutionKt.showDialectGateError(
                panel,
                ExecutionDialectGate.Verdict(
                    ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
                    "PostgreSQL",
                    "MySQL",
                ),
            )
            UIUtil.dispatchAllInvocationEvents()

            val fix = fixButton(panel)
            assertTrue("闸门阻断应挂出修复入口", fix.isVisible)
            assertEquals("切换数据源", fix.text)
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
        }
    }

    fun test闸门阻断_普通showError再投递不得清掉修复入口顺序错误可判() {
        val panel = SqlPreviewPanel(project)
        try {
            SqlPreviewPanelExecutionKt.showDialectGateError(
                panel,
                ExecutionDialectGate.Verdict(
                    ExecutionDialectGate.Status.MANUAL_CONFLICT,
                    "PostgreSQL",
                    "MySQL",
                ),
            )
            UIUtil.dispatchAllInvocationEvents()
            val fix = fixButton(panel)
            assertTrue("手动方言冲突同样应挂出修复入口", fix.isVisible)
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
        }
    }

    /**
     * T-DialectGatePass（2026-10-26 语义对齐，对齐 commit 8619a3af 用户定案）：预览入口的
     * MANUAL_CONFLICT / RERENDER_UNAVAILABLE 不再走 [SqlPreviewPanelExecutionKt.showDialectGateError]
     * 阻断（错误态+动作），改走 showStatus 信息态呈现方言不一致提示，执行继续（与选择器手选路径一致）。
     * 呈现延迟到下一拍 EDT（见 showDialectGateError 注释的同栈 onError 覆盖坑），故先抽干队列。
     */
    fun test闸门放行_手动冲突显示状态提示不进错误态() {
        val panel = SqlPreviewPanel(project)
        try {
            SqlPreviewPanelExecutionKt.onExecutionDialectGate(
                panel,
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
            assertTrue("放行语义同样要挂换源入口", fixButton(panel).isVisible)
            assertEquals("切换数据源", fixButton(panel).text)
            val text = rp.infoTextForTest()
            assertTrue("状态行应报出渲染方言", text.contains("PostgreSQL"))
            assertTrue("状态行应报出目标方言", text.contains("MySQL"))
            assertFalse("放行语义文案不应再含「已阻止执行」", text.contains("已阻止执行"))
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
        }
    }

    fun test闸门放行_重渲染不可用同样显示状态提示不进错误态() {
        val panel = SqlPreviewPanel(project)
        try {
            SqlPreviewPanelExecutionKt.onExecutionDialectGate(
                panel,
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
            assertTrue("重渲染不可用同样要挂换源入口", fixButton(panel).isVisible)
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
        }
    }

    /**
     * T-DialectGatePassDeadChain：信息态状态行同样挂两个蓝字入口，点击分别触发注入的动作。
     * 直接驱动 [SqlResultPanel.showStatusWithActions]（不经面板换源链，避免真建控制台/会话）。
     * k2j：Java 签名为位置实参（Kotlin 调 Java 不做具名实参），Runnable 落 lambda。
     */
    fun test状态行带动作_两个蓝字入口可点且各自触发() {
        val panel = SqlPreviewPanel(project)
        try {
            val rp = panel.resultPanelForTest()
            var fixed = 0
            var settings = 0
            rp.showStatusWithActions(
                "方言不一致：当前 SQL 按 GaussDB 渲染，所选数据源是 PostgreSQL。",
                "切换数据源",
                "tip",
                { fixed++ },
                "方言设置",
                "tip2",
                { settings++ },
            )
            assertEquals("信息态底色不变", "EMPTY", rp.stateForTest())
            assertNull("信息态不挂错误文案", rp.currentErrorForTest())
            val fix = fixButton(panel)
            val set = settingsButton(panel)
            assertTrue("换源入口应可见", fix.isVisible)
            assertEquals("切换数据源", fix.text)
            assertTrue("设置入口应可见", set.isVisible)
            assertEquals("方言设置", set.text)
            fix.doClick()
            set.doClick()
            assertEquals("点击应触发换源动作", 1, fixed)
            assertEquals("点击应触发打开设置动作", 1, settings)
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
        }
    }
}
