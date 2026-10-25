package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * T-FirstExecResultInvisible F1：准备链单飞 + 执行意图排队门（纯逻辑，JDK 原子类，可单测）。
 *
 * <p>根因（计划 §一 H1 主嫌）：打开预览发起的准备链（远程 PG 会话需数秒）未落地时点「执行 SQL」，
 * 旧代码在 {@code chooseAndPrepare} 无任何去重——会再建第二条临时文件/会话/控制台链。两条链并发时，
 * 准备链的 {@code executionGeneration++} 落进执行链代际捕获与取消判定之间 → 提交被取消
 * （{@code [M36] execution submit cancelled}）；即使已提交，准备链 onConsole 也会换入从未执行的
 * 全新控制台并折叠——用户见「无 loading 无报错下方空白」，第二次点击两链已收敛才正常。</p>
 *
 * <p>消除方式（结构性）：执行意图到达而已有准备链在飞时，<b>只排队不建链</b>——准备链完成时消费
 * 排队意图按 executePrepared 口径执行。无论 H1 是否终审定案都是纯增益（消除重复会话/连接）。</p>
 *
 * <p>门状态转换串行化；取消/失败通知沿请求回调交付，UI 消费者负责归位 EDT。
 * 每请求终态一次性，后到覆盖、超时和准备失败均不丢弃未结算请求。</p>
 */
public final class PrepareSingleFlightGate {

    /**
     * 单飞位悬挂逃生窗口：远程 PG 会话「数秒」量级（计划 §一），60s 内仍未终结视作死链
     * （runner 失败无 session 回调等历史挂死族，见 sharedConsole 字段注释的 M36.5.5 实证）。
     */
    public static final long STUCK_ESCAPE_MS = 60_000L;

    private final LongSupplier clock;
    private final Supplier<String> timeoutMessage;

    public PrepareSingleFlightGate() {
        this(() -> System.nanoTime() / 1_000_000,
                () -> MpSqlVisualizerBundle.message("execute.prepare.timeout"));
    }

    public PrepareSingleFlightGate(LongSupplier clock, Supplier<String> timeoutMessage) {
        this.clock = clock;
        this.timeoutMessage = timeoutMessage;
    }

    /** 门持有链终结（完成/失败/复用收敛）即复位；复位后新链可重新持有。 */
    public final AtomicBoolean prepareInFlight = new AtomicBoolean(false);

    /** 排队执行意图在位标记（消费一次性；与 {@link #pendingIntent} 同步读写）。 */
    public final AtomicBoolean pendingExecute = new AtomicBoolean(false);

    /** 最后一个排队执行意图（同窗口连点执行时后到覆盖先到——最新用户意图优先）。 */
    public volatile QueuedExecuteIntent pendingIntent = null;

    private QueuedExecuteIntent owner = null;
    private boolean submissionStarted = false;
    private int interactionDepth = 0;

    /** 当前门持有链的起始时刻（卡死逃生判定基准；仅 PROCEED/接管时写）。 */
    private volatile long inFlightSinceMs = 0L;

    public synchronized boolean beginInteraction(@Nullable QueuedExecuteIntent expectedOwner) {
        if (!matchesOwner(expectedOwner)) {
            return false;
        }
        interactionDepth++;
        return true;
    }

    /** 用户结束选择/确认后重新给后台完整预算；旧 timer tick 必须重新检查时间。 */
    public synchronized void endInteraction(@Nullable QueuedExecuteIntent expectedOwner) {
        if (!matchesOwner(expectedOwner) || interactionDepth == 0) {
            return;
        }
        interactionDepth--;
        if (interactionDepth == 0) {
            inFlightSinceMs = clock.getAsLong();
        }
    }

    public <T> T duringInteraction(@Nullable QueuedExecuteIntent expectedOwner, Supplier<T> action) {
        boolean paused = beginInteraction(expectedOwner);
        try {
            return action.get();
        } finally {
            if (paused) {
                endInteraction(expectedOwner);
            }
        }
    }

    private boolean backgroundExpired(long nowMs) {
        return !submissionStarted && interactionDepth == 0 && nowMs - inFlightSinceMs >= STUCK_ESCAPE_MS;
    }

    /** 提交确认已接管回执，不再由准备超时伪报取消。 */
    public synchronized boolean beginSubmission(@Nullable QueuedExecuteIntent expectedOwner) {
        if (!matchesOwner(expectedOwner)) {
            return false;
        }
        submissionStarted = true;
        return true;
    }

    private boolean matchesOwner(@Nullable QueuedExecuteIntent expectedOwner) {
        return expectedOwner != null && prepareInFlight.get() && owner == expectedOwner;
    }

    public synchronized boolean isOwner(@NotNull QueuedExecuteIntent intent) {
        return prepareInFlight.get() && owner == intent;
    }

    /** 超时只结算所属链，迟到的旧回调不得清除新链。 */
    public synchronized boolean expire(@NotNull QueuedExecuteIntent intent) {
        return expire(intent, clock.getAsLong());
    }

    public synchronized boolean expire(@NotNull QueuedExecuteIntent intent, long nowMs) {
        if (!matchesOwner(intent)) {
            return true; // 旧 timer 不操作新 owner 或新队列
        }
        if (submissionStarted) {
            return true;
        }
        if (!backgroundExpired(nowMs)) {
            return false;
        }
        abort(intent, ExecutionSubmission.BLOCKED, timeoutMessage.get());
        return true;
    }

    /** 先清空门和队列再通知，避免回调重入把旧请求遗留给下一链。 */
    public synchronized boolean abort(@Nullable QueuedExecuteIntent expectedOwner,
                                      ExecutionSubmission outcome, @Nullable String message) {
        if (!matchesOwner(expectedOwner)) {
            return false;
        }
        QueuedExecuteIntent current = owner;
        QueuedExecuteIntent queued = takePendingExecute();
        owner = null;
        prepareInFlight.set(false);
        if (current != null) {
            current.complete(outcome, message);
        }
        if (queued != null) {
            queued.complete(outcome, message);
        }
        return true;
    }

    /**
     * 入口判定（chooseAndPrepare 调用，先于共享复用检查）。判定序：
     * ① 空闲 → PROCEED；② 在飞但悬挂超时 → 接管放行；③ 在飞且预览准备 → PASS_THROUGH；
     * ④ 在飞且显式执行 → 排队 QUEUE_ONLY。
     */
    public PrepareGateDecision tryEnter(boolean executeAfterPrepare) {
        return tryEnter(executeAfterPrepare, null);
    }

    public PrepareGateDecision tryEnter(boolean executeAfterPrepare, @Nullable QueuedExecuteIntent intent) {
        return tryEnter(executeAfterPrepare, intent, clock.getAsLong());
    }

    public synchronized PrepareGateDecision tryEnter(boolean executeAfterPrepare, @Nullable QueuedExecuteIntent intent,
                                                     long nowMs) {
        if (prepareInFlight.compareAndSet(false, true)) {
            inFlightSinceMs = nowMs;
            owner = intent;
            submissionStarted = false;
            interactionDepth = 0;
            return PrepareGateDecision.PROCEED;
        }
        if (backgroundExpired(nowMs)) {
            // 卡死逃生：接管所有权（CompareAndSet 已被旧链持有，直接 set 覆盖）并清陈旧排队
            QueuedExecuteIntent expiredOwner = owner;
            QueuedExecuteIntent expiredQueued = takePendingExecute();
            prepareInFlight.set(true);
            inFlightSinceMs = nowMs;
            owner = intent;
            submissionStarted = false;
            interactionDepth = 0;
            String message = timeoutMessage.get();
            if (expiredOwner != null) {
                expiredOwner.complete(ExecutionSubmission.BLOCKED, message);
            }
            if (expiredQueued != null) {
                expiredQueued.complete(ExecutionSubmission.BLOCKED, message);
            }
            return PrepareGateDecision.PROCEED;
        }
        if (!executeAfterPrepare) {
            return PrepareGateDecision.PASS_THROUGH;
        }
        // F1 核心：已 in-flight 且本次 executeAfterPrepare=true → 只排队，不建第二链
        QueuedExecuteIntent replaced = pendingIntent;
        pendingIntent = intent;
        pendingExecute.set(true);
        if (replaced != null) {
            replaced.complete(ExecutionSubmission.CANCELLED);
        }
        return PrepareGateDecision.QUEUE_ONLY;
    }

    /**
     * 消费排队执行意图（一次性；无排队返回 null）。由门持有链在完成准备时调用
     * （prepareAfterEditorResolved 缓存落地后）；PASS_THROUGH 链不得调用。
     */
    @Nullable
    public synchronized QueuedExecuteIntent consumePendingExecute(@Nullable QueuedExecuteIntent expectedOwner) {
        if (!matchesOwner(expectedOwner)) {
            return null;
        }
        return takePendingExecute();
    }

    /** 仅在门内同步临界区调用，不向链暴露无身份消费入口。 */
    private QueuedExecuteIntent takePendingExecute() {
        if (!pendingExecute.getAndSet(false)) {
            return null;
        }
        QueuedExecuteIntent intent = pendingIntent;
        pendingIntent = null;
        return intent;
    }

    /**
     * T-DialectInferPollution W11（2026-10-24）：<b>排队意图自己的有界结算</b>——排队者原本只能
     * 等 owner 的 60s 逃生被下一链接管，期间 UI 永远「执行中…」（用户真机卡死实测）。排队
     * 超时只结算排队意图本身（BLOCKED 解除加载态），<b>不动门与 owner</b>——owner 链照常推进，
     * 用户稍后重试即可。owner 已变更/意图已被消费 → 幂等返回 false。
     */
    public synchronized boolean expirePendingExecute(@NotNull QueuedExecuteIntent intent, @NotNull String message) {
        if (owner != null && owner == intent) return false;
        if (pendingIntent != intent || !pendingExecute.get()) return false;
        takePendingExecute();
        intent.complete(ExecutionSubmission.BLOCKED, message);
        return true;
    }

    /** 门持有链终结复位（成功/失败/复用收敛均须调用；PASS_THROUGH 链不得调用）。 */
    public synchronized boolean finishPrepare(@Nullable QueuedExecuteIntent expectedOwner) {
        if (!matchesOwner(expectedOwner)) {
            return false;
        }
        QueuedExecuteIntent leftover = takePendingExecute();
        owner = null;
        prepareInFlight.set(false);
        if (leftover != null) {
            leftover.complete(ExecutionSubmission.CANCELLED);
        }
        return true;
    }
}
