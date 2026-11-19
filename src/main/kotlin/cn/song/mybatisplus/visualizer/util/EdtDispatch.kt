package cn.song.mybatisplus.visualizer.util

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台线程 → EDT 的**有界**派发（T-UserFreezeTriage，2026-11-19 用户线程转储实证）。
 *
 * 为什么需要它：新锁模型下，EDT 上的写动作要等**所有非 EDT 线程的读锁**释放。第三方插件或 IDE
 * 自带检查持读锁数秒到数分钟时（本次实证＝`Duplicated code fragment` 在检查器读动作里做全工程
 * 词条搜索），无超时的 `invokeAndWait` 会把**调用方线程**一起挂死——用户报障的那份 dump 里，
 * Cool Request 的发送任务就停在 `SwingUtilities.invokeAndWait`，请求根本没发出去。
 *
 * 纪律：
 *  - 我方后台线程等 EDT 一律有界（默认 [DEFAULT_BUDGET_MS]），超时只记一条 WARN 并继续；
 *    第三方的长读锁时长不得传导成我方功能不可用。
 *  - 调用方是 EDT 时就地执行——等自己排队的事件只会烧满预算（自等）。
 *  - 回调体不得假设「返回即已执行完」：超时返回 `false` 时，回调体仍可能在稍后被 EDT 执行。
 *
 * 守卫：`convention/EdtBoundedWaitGuardTest` 禁止 `ui/execute/log/mcp/psi` 包内出现裸
 * `invokeAndWait`（本文件与一次性安装流程 `update/DynamicInstallSupport.kt` 除外）。
 */
object EdtDispatch {

    /** 默认等待预算。参考值：正常 EDT 派发是毫秒级，这里留三个数量级余量。 */
    const val DEFAULT_BUDGET_MS: Long = 2_000L

    private val LOG = Logger.getInstance(EdtDispatch::class.java)

    /** 超时计数（测试观察点）。 */
    internal val timeouts = AtomicInteger(0)

    /** 测试缝：替换平台的 `invokeLater`（默认 null = 走 `Application.invokeLater`）。 */
    internal var invokeLaterOverride: ((ModalityState, Runnable) -> Unit)? = null

    /**
     * 投递 [block] 到 EDT 并**最多等** [budgetMs]。
     *
     * @return true = 调用返回时 [block] 已执行完（EDT 就地执行或按期完成）；
     *         false = 超时 / [expireWith] 已销毁。false 不代表 [block] 一定没执行。
     */
    fun invokeLaterAndWaitBounded(
        reason: String,
        expireWith: Disposable? = null,
        budgetMs: Long = DEFAULT_BUDGET_MS,
        modality: ModalityState = ModalityState.nonModal(),
        block: () -> Unit,
    ): Boolean {
        val app = ApplicationManager.getApplication()
        if (app == null || app.isDispatchThread) {
            if (expireWith != null && Disposer.isDisposed(expireWith)) return false
            block()
            return true
        }
        val done = CountDownLatch(1)
        val task = Runnable {
            try {
                if (expireWith == null || !Disposer.isDisposed(expireWith)) block()
            } catch (t: Throwable) {
                // JVM 级致命错误原样上抛（FatalErrors 口径：崩溃现场不能被吞成一条 WARN）
                if (FatalErrors.isFatal(t)) throw t
                LOG.warn("[EdtDispatch] EDT body failed: $reason", t)
            } finally {
                done.countDown()
            }
        }
        val dispatch = invokeLaterOverride
            ?: { m: ModalityState, r: Runnable -> app.invokeLater(r, m) }
        dispatch(modality, task)
        val signaled = runCatching { done.await(budgetMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!signaled) {
            timeouts.incrementAndGet()
            LOG.warn("[EdtDispatch] EDT busy, gave up after ${budgetMs}ms: $reason")
        }
        return signaled
    }
}
