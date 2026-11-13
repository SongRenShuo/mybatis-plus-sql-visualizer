package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import java.util.concurrent.atomic.AtomicInteger

/**
 * T-WriteSafeCtx：程序性文档写入的「写安全上下文」闸门。
 *
 * 平台契约（243.28141.41 源码逐帧实证，`TransactionGuard.java:73-76` 原文：nonModal 恒 write-safe，
 * any 恒 write-unsafe）：
 *  - **写入合法性不取决于是否包了 `WriteCommandAction`**，而取决于这次 EDT 活动有没有 write-safe
 *    资格：要么是用户输入活动（`IdeEventQueue.kt:1054` 只把输入/Item/Focus 事件算作用户活动，
 *    posted runnable 一律不算），要么该 runnable 是以 write-safe 模态排队的
 *    （`TransactionGuardImpl.wrapLaterInvocation:192-203` 只对 write-safe 模态套 runWithWritingAllowed）。
 *  - `ModalityState.any()` 排出的 runnable 写文档必触发 `Write-unsafe context!`
 *    （`TransactionGuardImpl:141-145` LOG.error）：生产 IDE 只记 SEVERE +「Plugin to blame」+ FUS 上报
 *    （IdeaLogger 不抛，写入照常完成），单测 logger 直接抛 AssertionError。
 *  - `ModalityState.defaultModalityState()` 只在**写安全上下文里创建**时才安全（`enteredModality:188-190`
 *    记录的是进入该模态那一刻的 myWritingAllowed 快照）——别拿它兜底，宁可 nonModal。
 *
 * 两条被真机事故证伪的假设（勿再沿用）：
 *  1. 「走 document 写（非裸写）就安全」——document 写只规避 File Cache Conflict 系问题，与 write-safe
 *     资格无关；`WriteCommandAction { doc.setText(...) }` 在 any() 活动里照样报错（SqlPreviewPanel:1712 事故）。
 *  2. 「`any()` 是为了模态弹窗存续期照常执行」——该惯例只适用**纯 UI** 回调；**改文档**恰好是模态
 *     存续期禁止的行为，写入类回调一律 nonModal（延迟到弹窗关闭是正确语义，非退化）。
 *
 * 第三条纪律（T-StartupReadLockFreeze，2026-11-13 用户 Linux 打开即冻结的栈 2 实证）：**EDT 不得在
 * 写意图上下文里就地升级写锁**——这是「BGT 等 EDT、EDT 永不反向阻塞」那条纪律的对偶。
 *
 * EDT 侧冻结链（用户 threadDump，`AWT-EventQueue-0` TIMED_WAITING）：
 * `TransactionGuardImpl.runWithWritingAllowed` → `ApplicationImpl.runIntendedWriteActionOnCurrentThread`
 * （本拍已持**写意图读锁**）→ 我方 `docWriteOnce` 的 `WriteAction.run` → `runWriteActionBlocking`
 * → `prepareWriteFromWriteIntentBlocking` → `upgradeWritePermit`：升级写锁要**等所有读者退出**。
 * 与此同时后台 `MbgExampleCallResolver.prefetch` 持阻塞读锁跑索引检索（`ReadAction.compute` 在该
 * 平台构建里委托 `computeBlocking`，不可取消），而索引推进又需要写权限 → 互等成环，EDT 永久挂住。
 * 结论：写意图上下文里的就地写 = 把「EDT 等 BGT」这条反方向死锁引进来；派发点一律改为
 * **投递一拍（nonModal）+ 有界等待**（[invokeLaterWriteSafeAndWait]），绝不在调用方活动里就地升级。
 */
object DocWriteContext {

    private val LOG = Logger.getInstance(DocWriteContext::class.java)

    /** 测试钩子：非 null 时优先返回其判定（覆盖单测放行，见 [isWriteAllowedNow]）。 */
    internal var writeAllowedProbe: (() -> Boolean)? = null

    /** 投递计数（测试观察点：断言「没有同步写，而是投递了一拍」）。 */
    internal val deferredCount = AtomicInteger(0)

    /**
     * 测试缝：单测环境也按**生产形态**执行 [invokeLaterWriteSafeAndWait]（投递 + 有界等待 + EDT 自等保护）。
     *
     * 默认单测里走「就地执行」等价路径：单测的线程模型是退化的（`BasePlatformTestCase` 把用例体
     * 直接跑在 EDT 线程上、`isUnitTestMode` 恒放行），而 [invokeLaterWriteSafeAndWait] 在调用线程
     * 被 latch 阻塞期间**没有线程能泵那一拍**，等待必然烧满预算，既有用例（`ExecDocSyncTest` 等）
     * 又依赖「EDT 直调当场落盘」。置 true 后按生产形态执行，供 `DocWriteDispatchSafetyTest`
     * 观测「EDT 分支不再就地写」。
     */
    internal var deferEvenInUnitTestMode = false

    /**
     * 当前上下文是否允许写文档模型。
     *
     * 单测环境直接放行：测试里没有真实的事务/模态语义（测试线程非真实 EDT，`isWritingAllowed` 的
     * 前置断言也未必成立），若按真实判据返回 false，既有面板测试会因「渲染被投递到下一拍」而整片红。
     * 闸门的投递行为由 [writeAllowedProbe] 注入覆盖来测。
     */
    fun isWriteAllowedNow(): Boolean {
        writeAllowedProbe?.let { return it() }
        val app = ApplicationManager.getApplication() ?: return false
        if (app.isUnitTestMode) return true
        if (!app.isDispatchThread) return false
        // isWritingAllowed() 要求写意图锁（BGT/无锁时抛）——保守兜底 false（宁延迟一拍不违规）
        return runCatching { TransactionGuard.getInstance().isWritingAllowed() }.getOrDefault(false)
    }

    /** 以 write-safe 模态（nonModal）投递；[expireWith] 已销毁则丢弃该拍。 */
    fun invokeLaterWriteSafe(expireWith: Disposable?, reason: String, runnable: Runnable) {
        val app = ApplicationManager.getApplication() ?: return
        deferredCount.incrementAndGet()
        LOG.info("[T-WSC] write deferred to nonModal (write-unsafe context): $reason")
        app.invokeLater({
            if (expireWith != null && Disposer.isDisposed(expireWith)) return@invokeLater
            runnable.run()
        }, ModalityState.nonModal())
    }

    /** 就地写：允许写→[block]；不允许→投递到 nonModal。调用方须保证 [block] 幂等（重投递会重跑）。 */
    fun runWriteSafe(expireWith: Disposable?, reason: String, block: () -> Unit) {
        if (isWriteAllowedNow()) {
            block()
            return
        }
        invokeLaterWriteSafe(expireWith, reason, Runnable { block() })
    }

    /**
     * **投递一拍（nonModal，恒 write-safe）并（在非 EDT 线程上）有界等待其结果**——T-StartupReadLockFreeze
     * S4-a 的派发口：调用方永远不在自己的活动里就地写文档，因此不会把「写意图读锁 → 写锁升级」带进
     * 当前这一拍（类头第三条纪律）。
     *
     * 线程分流：
     *  - **BGT**：投递后等 [waitBudgetMs]，超时返回 false 并记 WARN（不自锁、不无限等）；
     *  - **已在 EDT**：投递后**立即交还**——本拍等本拍排出去的事件只能烧满预算（自等），
     *    写体在下一次事件泵执行（FIFO 上先于之后排队的事件，含本拍派生的后续事件）。
     *
     * 写体仍过 [runWriteSafe] 闸：投递拍本身虽已 write-safe，但保留「判为不安全 → 转二次投递」
     * 的既有语义，也让写体始终只在可写上下文落笔。
     *
     * @return true = [block] 已执行；调用方是 EDT 时 = 已受理（下一拍执行）；false = 超时 / owner 已销毁 /
     *         写体判定不可写而转二次投递。调用方不得把返回值当作「写完即可读回」的凭据。
     */
    fun invokeLaterWriteSafeAndWait(
        expireWith: Disposable?,
        reason: String,
        waitBudgetMs: Long,
        block: () -> Boolean,
    ): Boolean {
        val app = ApplicationManager.getApplication() ?: return false
        if (app.isUnitTestMode && !deferEvenInUnitTestMode) {
            // 单测退化形态，见 [deferEvenInUnitTestMode]：就地语义与 [runWriteSafe] 等价。
            var inline = false
            runWriteSafe(expireWith, reason) { inline = block() }
            return inline
        }
        deferredCount.incrementAndGet()
        LOG.info("[T-WSC] write deferred to nonModal + bounded wait ${waitBudgetMs}ms (S4-a): $reason")
        val done = java.util.concurrent.CountDownLatch(1)
        var ok = false
        app.invokeLater({
            if (expireWith != null && Disposer.isDisposed(expireWith)) {
                done.countDown()
                return@invokeLater
            }
            try {
                runWriteSafe(expireWith, reason) { ok = block() }
            } finally {
                done.countDown()
            }
        }, ModalityState.nonModal())
        if (app.isDispatchThread) {
            // 自等保护：EDT 等自己排队的事件 = 保证烧满预算且写体仍未执行。
            LOG.info("[T-WSC] dispatch accepted without waiting (caller is EDT, S4-a): $reason")
            return true
        }
        val signaled = runCatching {
            done.await(waitBudgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!signaled) {
            // 日志口径沿用 exec doc sync 的既有形态（真机排查按 dispatch timeout / EDT busy 检索）
            LOG.warn("[M36] exec doc write nonModal dispatch timeout after ${waitBudgetMs}ms (EDT busy?): $reason")
        }
        return signaled && ok
    }
}
