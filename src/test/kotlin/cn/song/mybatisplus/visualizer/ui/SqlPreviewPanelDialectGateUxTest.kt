package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            assertFalse("放行语义不应挂修复入口", rp.fixActionForTest().isVisible)
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
        } finally {
            panel.clear()
        }
    }
}
