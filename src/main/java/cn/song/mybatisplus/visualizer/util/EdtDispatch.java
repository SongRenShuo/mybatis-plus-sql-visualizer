package cn.song.mybatisplus.visualizer.util;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台线程 → EDT 的<b>有界</b>派发（T-UserFreezeTriage，2026-11-19 用户线程转储实证）。
 *
 * <p>为什么需要它：新锁模型下，EDT 上的写动作要等<b>所有非 EDT 线程的读锁</b>释放。第三方插件或 IDE
 * 自带检查持读锁数秒到数分钟时（本次实证＝{@code Duplicated code fragment} 在检查器读动作里做全工程
 * 词条搜索），无超时的 {@code invokeAndWait} 会把<b>调用方线程</b>一起挂死——用户报障的那份 dump 里，
 * Cool Request 的发送任务就停在 {@code SwingUtilities.invokeAndWait}，请求根本没发出去。
 *
 * <p>纪律：
 * <ul>
 *   <li>我方后台线程等 EDT 一律有界（默认 {@link #DEFAULT_BUDGET_MS}），超时只记一条 WARN 并继续；
 *       第三方的长读锁时长不得传导成我方功能不可用。</li>
 *   <li>调用方是 EDT 时就地执行——等自己排队的事件只会烧满预算（自等）。</li>
 *   <li>回调体不得假设「返回即已执行完」：超时返回 {@code false} 时，回调体仍可能在稍后被 EDT 执行。</li>
 * </ul>
 *
 * <p>守卫：{@code convention/EdtBoundedWaitGuardTest} 禁止 {@code ui/execute/log/mcp/psi} 包内出现裸
 * {@code invokeAndWait}（本文件与一次性安装流程 update/DynamicInstallSupport 除外）。
 */
public final class EdtDispatch {

    private EdtDispatch() {}

    /** 默认等待预算。参考值：正常 EDT 派发是毫秒级，这里留三个数量级余量。 */
    public static final long DEFAULT_BUDGET_MS = 2_000L;

    private static final Logger LOG = Logger.getInstance(EdtDispatch.class);

    /** 超时计数（测试观察点）。 */
    public static final AtomicInteger timeouts = new AtomicInteger(0);

    /** 测试缝：替换平台的 {@code invokeLater}（默认 null = 走 {@code Application.invokeLater}）。 */
    public static volatile Function2<ModalityState, Runnable, Unit> invokeLaterOverride;

    /**
     * 投递 {@code block} 到 EDT 并<b>最多等</b> {@link #DEFAULT_BUDGET_MS}。
     *
     * @return true = 调用返回时 {@code block} 已执行完（EDT 就地执行或按期完成）；
     *         false = 超时 / {@code expireWith} 已销毁。false 不代表 {@code block} 一定没执行。
     */
    public static boolean invokeLaterAndWaitBounded(String reason, Runnable block) {
        return invokeLaterAndWaitBounded(reason, null, DEFAULT_BUDGET_MS, ModalityState.nonModal(), block);
    }

    /**
     * 投递 {@code block} 到 EDT 并<b>最多等</b> {@code budgetMs}。
     *
     * @return true = 调用返回时 {@code block} 已执行完（EDT 就地执行或按期完成）；
     *         false = 超时 / {@code expireWith} 已销毁。false 不代表 {@code block} 一定没执行。
     */
    public static boolean invokeLaterAndWaitBounded(String reason, long budgetMs, Runnable block) {
        return invokeLaterAndWaitBounded(reason, null, budgetMs, ModalityState.nonModal(), block);
    }

    /**
     * 投递 {@code block} 到 EDT（指定模态）并<b>最多等</b> {@link #DEFAULT_BUDGET_MS}。
     *
     * @return true = 调用返回时 {@code block} 已执行完（EDT 就地执行或按期完成）；
     *         false = 超时 / {@code expireWith} 已销毁。false 不代表 {@code block} 一定没执行。
     */
    public static boolean invokeLaterAndWaitBounded(String reason, ModalityState modality, Runnable block) {
        return invokeLaterAndWaitBounded(reason, null, DEFAULT_BUDGET_MS, modality, block);
    }

    /**
     * 投递 {@code block} 到 EDT 并<b>最多等</b> {@code budgetMs}（owner 销毁即丢弃）。
     *
     * @return true = 调用返回时 {@code block} 已执行完（EDT 就地执行或按期完成）；
     *         false = 超时 / {@code expireWith} 已销毁。false 不代表 {@code block} 一定没执行。
     */
    public static boolean invokeLaterAndWaitBounded(String reason, Disposable expireWith, long budgetMs,
                                                    Runnable block) {
        return invokeLaterAndWaitBounded(reason, expireWith, budgetMs, ModalityState.nonModal(), block);
    }

    /**
     * 投递 {@code block} 到 EDT 并<b>最多等</b> {@code budgetMs}。
     *
     * @return true = 调用返回时 {@code block} 已执行完（EDT 就地执行或按期完成）；
     *         false = 超时 / {@code expireWith} 已销毁。false 不代表 {@code block} 一定没执行。
     */
    public static boolean invokeLaterAndWaitBounded(String reason, Disposable expireWith, long budgetMs,
                                                    ModalityState modality, Runnable block) {
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        if (app == null || app.isDispatchThread()) {
            if (expireWith != null && Disposer.isDisposed(expireWith)) return false;
            block.run();
            return true;
        }
        CountDownLatch done = new CountDownLatch(1);
        Runnable task = () -> {
            try {
                if (expireWith == null || !Disposer.isDisposed(expireWith)) block.run();
            } catch (Throwable t) {
                // JVM 级致命错误原样上抛（FatalErrors 口径：崩溃现场不能被吞成一条 WARN）
                if (FatalErrors.isFatal(t)) throw (Error) t;
                LOG.warn("[EdtDispatch] EDT body failed: " + reason, t);
            } finally {
                done.countDown();
            }
        };
        Function2<ModalityState, Runnable, Unit> dispatch = invokeLaterOverride;
        if (dispatch == null) {
            dispatch = (m, r) -> {
                app.invokeLater(r, m);
                return Unit.INSTANCE;
            };
        }
        dispatch.invoke(modality, task);
        boolean signaled;
        try {
            signaled = done.await(budgetMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            signaled = false;
        }
        if (!signaled) {
            timeouts.incrementAndGet();
            LOG.warn("[EdtDispatch] EDT busy, gave up after " + budgetMs + "ms: " + reason);
        }
        return signaled;
    }
}
