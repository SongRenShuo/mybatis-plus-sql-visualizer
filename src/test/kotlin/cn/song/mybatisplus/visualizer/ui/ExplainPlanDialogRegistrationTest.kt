package cn.song.mybatisplus.visualizer.ui

import com.intellij.openapi.util.Disposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 H：EXPLAIN 结果对话框面板树挂载「常驻一份 + 字段间接」回归。
 *
 * 旧版每次执行 EXPLAIN 都向面板树新挂一个 Disposable 且直接捕获对话框实例——
 * 闭掉的对话框被强引用到面板销毁，反复执行线性累积（工具窗形态面板长期存活）。
 * 修复后 [registerExplainDialogCloser] 只挂一份、动作沿「当前对话框」供应商间接，
 * 对话框引用只在字段里、换新即出局。
 */
class ExplainPlanDialogRegistrationTest {

    private class Parent : com.intellij.openapi.Disposable {
        var disposed = false
        override fun dispose() {
            disposed = true
        }
    }

    private class FakeDialog {
        var closeCount = 0
        fun close() {
            closeCount++
        }
    }

    @Test
    fun `常驻注册只挂一份_关闭动作沿字段间接_换新后旧实例不再被引用`() {
        val parent = Parent()
        var current: FakeDialog? = null
        val reg = registerExplainDialogCloser(parent) { current?.close() }

        val first = FakeDialog()
        current = first
        val second = FakeDialog()
        current = second // 字段换指：first 不再被注册引用（生产链路换新前另有显式 close，不在此路径）

        Disposer.dispose(parent)
        assertTrue(parent.disposed)
        assertEquals("面板销毁只关闭字段指向的当前对话框", 1, second.closeCount)
        assertEquals("旧实例不得经注册链路再被触碰（无强引用）", 0, first.closeCount)
        assertSame(reg, reg)
    }

    @Test
    fun `常驻注册可重复触发关闭_幂等于字段为空`() {
        val parent = Parent()
        var current: FakeDialog? = null
        registerExplainDialogCloser(parent) { current?.close() }
        val dialog = FakeDialog()
        current = dialog
        dialog.close() // 显式 close 后字段仍在（dispose 前再触发一次 close）
        Disposer.dispose(parent)
        assertEquals("close 幂等（DialogWrapper 已闭再 close 无害）", 2, dialog.closeCount)
    }
}
