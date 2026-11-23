package cn.song.mybatisplus.visualizer.util

import com.intellij.openapi.application.ModalityState
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T-UserFreezeTriage：有界 EDT 派发的三态（EDT 自等保护 / 后台按期完成 / 后台超时脱身）。
 *
 * 证据边界（如实标注）：headless 造不出「第三方持长读锁数分钟」的真实时序，本类只钉派发器自身的
 * 三条分支；真实冻结场景的证据是用户 dump（`SqlPreviewPanel` / `DynamicInstallSupport` 两处调用点
 * 不再出现无超时 `invokeAndWait`，由 `convention.EdtBoundedWaitGuardTest` 守卫）。
 */
class EdtDispatchTest : BasePlatformTestCase() {

    private var savedOverride: ((ModalityState, Runnable) -> Unit)? = null

    override fun setUp() {
        super.setUp()
        savedOverride = EdtDispatch.invokeLaterOverride
    }

    override fun tearDown() {
        EdtDispatch.invokeLaterOverride = savedOverride
        super.tearDown()
    }

    /** EDT 就地执行：不得再投递（等自己排队的事件只会烧满预算）。 */
    fun testEdtCallerRunsInlineWithoutDispatching() {
        val dispatched = AtomicBoolean(false)
        EdtDispatch.invokeLaterOverride = { _, _ -> dispatched.set(true) }
        var ran = false
        val ok = EdtDispatch.invokeLaterAndWaitBounded(reason = "test-edt-inline") { ran = true }
        assertTrue("EDT 调用必须就地执行并返回 true", ok && ran)
        assertFalse("EDT 调用不得再走 invokeLater 投递（自等）", dispatched.get())
    }

    /** 后台调用 + EDT 按期执行：返回 true，回调体已跑。 */
    fun testBackgroundCallerReturnsTrueWhenDelivered() {
        EdtDispatch.invokeLaterOverride = { _, r -> r.run() }
        var ran = false
        val ok = onOtherThread(2_000L) {
            EdtDispatch.invokeLaterAndWaitBounded(reason = "test-delivered", budgetMs = 1_000L) { ran = true }
        }
        assertEquals("按期完成必须返回 true", true, ok)
        assertTrue("按期完成回调体必须已执行", ran)
    }

    /** 后台调用 + EDT 不执行（模拟被长读锁占住）：预算内脱身并返回 false，不自锁。 */
    fun testBackgroundCallerGivesUpWithinBudget() {
        EdtDispatch.invokeLaterOverride = { _, _ -> /* 永不执行：模拟 EDT 被占 */ }
        val before = EdtDispatch.timeouts.get()
        var ran = false
        val started = System.nanoTime()
        val ok = onOtherThread(5_000L) {
            EdtDispatch.invokeLaterAndWaitBounded(reason = "test-timeout", budgetMs = 120L) { ran = true }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("超时必须返回 false（不得假装成功）", false, ok)
        assertFalse("超时路径不得执行回调体", ran)
        assertTrue("必须在预算附近脱身，实际 ${elapsedMs}ms", elapsedMs < 2_000)
        assertEquals("超时计数须 +1", before + 1, EdtDispatch.timeouts.get())
    }

    /** 已销毁的 owner：既不入队也不执行，直接 false。 */
    fun testDisposedOwnerIsDropped() {
        val disposable = com.intellij.openapi.util.Disposer.newDisposable()
        com.intellij.openapi.util.Disposer.dispose(disposable)
        var ran = false
        val ok = onOtherThread(2_000L) {
            EdtDispatch.invokeLaterAndWaitBounded(
                reason = "test-disposed",
                expireWith = disposable,
                budgetMs = 200L,
            ) { ran = true }
        }
        assertFalse("owner 已销毁必须返回 false", ran)
        assertEquals("owner 已销毁不得报成功", false, ok)
    }

    private fun onOtherThread(joinMs: Long, body: () -> Boolean): Boolean? {
        var result: Boolean? = null
        val t = Thread { result = body() }
        t.start()
        t.join(joinMs)
        assertFalse("后台线程未在 ${joinMs}ms 内结束（等 EDT 变成无界了？）", t.isAlive)
        return result
    }
}
