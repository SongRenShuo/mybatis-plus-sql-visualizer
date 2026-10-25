package cn.song.mybatisplus.visualizer.execute

/**
 * T-FirstExecResultInvisible F1：准备链单飞 + 执行意图排队门（纯逻辑，JDK 原子类，可单测）。
 *
 * 根因（计划 §一 H1 主嫌）：打开预览发起的准备链（远程 PG 会话需数秒）未落地时点「执行 SQL」，
 * 旧代码在 `chooseAndPrepare` 无任何去重——会再建第二条临时文件/会话/控制台链。两条链并发时，
 * 准备链的 `executionGeneration++` 落进执行链代际捕获与取消判定之间 → 提交被取消
 * （`[M36] execution submit cancelled`）；即使已提交，准备链 onConsole 也会换入从未执行的
 * 全新控制台并折叠——用户见「无 loading 无报错下方空白」，第二次点击两链已收敛才正常。
 *
 * 消除方式（结构性）：执行意图到达而已有准备链在飞时，**只排队不建链**——准备链完成时消费
 * 排队意图按 executePrepared 口径执行。无论 H1 是否终审定案都是纯增益（消除重复会话/连接）。
 *
 * 门状态转换串行化；取消/失败通知沿请求回调交付，UI 消费者负责归位 EDT。
 * 每请求终态一次性，后到覆盖、超时和准备失败均不丢弃未结算请求。
 */
internal class PrepareSingleFlightGate(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timeoutMessage: () -> String = {
        cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("execute.prepare.timeout")
    },
) {

    /** 门持有链终结（完成/失败/复用收敛）即复位；复位后新链可重新持有。 */
    val prepareInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 排队执行意图在位标记（消费一次性；与 [pendingIntent] 同步读写）。 */
    val pendingExecute = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 最后一个排队执行意图（同窗口连点执行时后到覆盖先到——最新用户意图优先）。 */
    @Volatile
    internal var pendingIntent: QueuedExecuteIntent? = null

    private var owner: QueuedExecuteIntent? = null
    private var submissionStarted = false
    private var interactionDepth = 0

    @Synchronized
    fun beginInteraction(expectedOwner: QueuedExecuteIntent?): Boolean {
        if (!matchesOwner(expectedOwner)) return false
        interactionDepth++
        return true
    }

    /** 用户结束选择/确认后重新给后台完整预算；旧 timer tick 必须重新检查时间。 */
    @Synchronized
    fun endInteraction(expectedOwner: QueuedExecuteIntent?) {
        if (!matchesOwner(expectedOwner) || interactionDepth == 0) return
        interactionDepth--
        if (interactionDepth == 0) inFlightSinceMs = clock()
    }

    fun <T> duringInteraction(expectedOwner: QueuedExecuteIntent?, action: () -> T): T {
        val paused = beginInteraction(expectedOwner)
        return try { action() } finally { if (paused) endInteraction(expectedOwner) }
    }

    private fun backgroundExpired(nowMs: Long): Boolean =
        !submissionStarted && interactionDepth == 0 && nowMs - inFlightSinceMs >= STUCK_ESCAPE_MS

    /** 提交确认已接管回执，不再由准备超时伪报取消。 */
    @Synchronized
    fun beginSubmission(expectedOwner: QueuedExecuteIntent?): Boolean {
        if (!matchesOwner(expectedOwner)) return false
        submissionStarted = true
        return true
    }

    private fun matchesOwner(expectedOwner: QueuedExecuteIntent?): Boolean =
        expectedOwner != null && prepareInFlight.get() && owner === expectedOwner

    @Synchronized
    fun isOwner(intent: QueuedExecuteIntent): Boolean = prepareInFlight.get() && owner === intent

    /** 超时只结算所属链，迟到的旧回调不得清除新链。 */
    @Synchronized
    fun expire(intent: QueuedExecuteIntent, nowMs: Long = clock()): Boolean {
        if (!matchesOwner(intent)) return true // 旧 timer 不操作新 owner 或新队列
        if (submissionStarted) return true
        if (!backgroundExpired(nowMs)) return false
        abort(intent, ExecutionSubmission.BLOCKED, timeoutMessage())
        return true
    }

    /** 先清空门和队列再通知，避免回调重入把旧请求遗留给下一链。 */
    @Synchronized
    fun abort(expectedOwner: QueuedExecuteIntent?, outcome: ExecutionSubmission, message: String? = null): Boolean {
        if (!matchesOwner(expectedOwner)) return false
        val current = owner
        val queued = takePendingExecute()
        owner = null
        prepareInFlight.set(false)
        current?.complete(outcome, message)
        queued?.complete(outcome, message)
        return true
    }

    /** 当前门持有链的起始时刻（卡死逃生判定基准；仅 PROCEED/接管时写）。 */
    @Volatile
    private var inFlightSinceMs: Long = 0L

    /**
     * 入口判定（chooseAndPrepare 调用，先于共享复用检查——冷启时 sharedConsole 尚未落地，
     * 复用必不命中，单飞是唯一闸门）。
     *
     * 判定序：① 空闲 → PROCEED（本链持有门，终结时必须 [finishPrepare]）；
     * ② 在飞但悬挂超过 [STUCK_ESCAPE_MS]（准备链消亡未复位，如 runner 失败无 session 回调）
     * → 接管放行新链自愈（清陈旧排队意图；否则执行按钮从此永久排队）；
     * ③ 在飞且本链为预览准备（executeAfterPrepare=false）→ PASS_THROUGH 直通不取所有权
     *（保持既有预览并发形态——拦截预览会让切换预览条目的 SQL 同步丢失）；
     * ④ 在飞且本链为显式执行 → 排队意图、QUEUE_ONLY（调用方 callback(true) 后返回，
     * 不再建第二条临时文件/会话/控制台）。
     */
    @Synchronized
    fun tryEnter(
        executeAfterPrepare: Boolean,
        intent: QueuedExecuteIntent? = null,
        nowMs: Long = clock(),
    ): PrepareGateDecision {
        if (prepareInFlight.compareAndSet(false, true)) {
            inFlightSinceMs = nowMs
            owner = intent
            submissionStarted = false
            interactionDepth = 0
            return PrepareGateDecision.PROCEED
        }
        if (backgroundExpired(nowMs)) {
            // 卡死逃生：接管所有权（CompareAndSet 已被旧链持有，直接 set 覆盖）并清陈旧排队
            val expiredOwner = owner
            val expiredQueued = takePendingExecute()
            prepareInFlight.set(true)
            inFlightSinceMs = nowMs
            owner = intent
            submissionStarted = false
            interactionDepth = 0
            val message = timeoutMessage()
            expiredOwner?.complete(ExecutionSubmission.BLOCKED, message)
            expiredQueued?.complete(ExecutionSubmission.BLOCKED, message)
            return PrepareGateDecision.PROCEED
        }
        if (!executeAfterPrepare) return PrepareGateDecision.PASS_THROUGH
        // F1 核心：已 in-flight 且本次 executeAfterPrepare=true → 只排队，不建第二链
        val replaced = pendingIntent
        pendingIntent = intent
        pendingExecute.set(true)
        replaced?.complete(ExecutionSubmission.CANCELLED)
        return PrepareGateDecision.QUEUE_ONLY
    }

    /**
     * 消费排队执行意图（一次性；无排队返回 null）。由门持有链在完成准备时调用
     * （prepareAfterEditorResolved 缓存落地后）；PASS_THROUGH 链不得调用。
     */
    @Synchronized
    fun consumePendingExecute(expectedOwner: QueuedExecuteIntent?): QueuedExecuteIntent? {
        if (!matchesOwner(expectedOwner)) return null
        return takePendingExecute()
    }

    /** 仅在门内同步临界区调用，不向链暴露无身份消费入口。 */
    private fun takePendingExecute(): QueuedExecuteIntent? {
        if (!pendingExecute.getAndSet(false)) return null
        val intent = pendingIntent
        pendingIntent = null
        return intent
    }

    /** 门持有链终结复位（成功/失败/复用收敛均须调用；PASS_THROUGH 链不得调用）。 */
    @Synchronized
    fun finishPrepare(expectedOwner: QueuedExecuteIntent?): Boolean {
        if (!matchesOwner(expectedOwner)) return false
        val leftover = takePendingExecute()
        owner = null
        prepareInFlight.set(false)
        leftover?.complete(ExecutionSubmission.CANCELLED)
        return true
    }

    /**
     * T-DialectInferPollution W11（2026-10-24）：**排队意图自己的有界结算**——排队者原本只能
     * 等 owner 的 60s 逃生被下一链接管，期间 UI 永远「执行中…」（用户真机卡死实测）。排队
     * 超时只结算排队意图本身（BLOCKED 解除加载态），**不动门与 owner**——owner 链照常推进，
     * 用户稍后重试即可。owner 已变更/意图已被消费 → 幂等返回 false。
     */
    @Synchronized
    fun expirePendingExecute(intent: QueuedExecuteIntent, message: String): Boolean {
        if (owner != null && owner === intent) return false
        if (pendingIntent !== intent || !pendingExecute.get()) return false
        takePendingExecute()
        intent.complete(ExecutionSubmission.BLOCKED, message)
        return true
    }

    companion object {
        /**
         * 单飞位悬挂逃生窗口：远程 PG 会话「数秒」量级（计划 §一），60s 内仍未终结视作死链
         * （runner 失败无 session 回调等历史挂死族，见 sharedConsole 字段注释的 M36.5.5 实证）。
         */
        internal const val STUCK_ESCAPE_MS = 60_000L
    }
}

/** 入口判定结果（三类可区分——所有权与终结义务不同，见 [PrepareSingleFlightGate.tryEnter]）。 */
internal enum class PrepareGateDecision {
    /** 本链持有单飞位——终结时必须 finishPrepare（含失败路径）。 */
    PROCEED,

    /** 已有他链在飞且本链为预览准备——直通不取所有权（不 finishPrepare、不消费排队意图）。 */
    PASS_THROUGH,

    /** 执行意图已排队——调用方 callback(true) 后直接返回，不建第二链。 */
    QUEUE_ONLY,
}

/**
 * 排队执行意图（F1 拦截点的回调快照）。拦截时执行链的回调不可丢弃——面板在 executeSql 里
 * 已 showLoading 并拿到 callback(true)，消费点必须用意图自身的 onError/onErrorRich 解除
 * 加载态、用 onConsole（面板 EXECUTE 内嵌）换入结果，否则排队执行失败即静默卡死。
 */
internal class QueuedExecuteIntent(
    val onConsole: ((javax.swing.JComponent) -> Unit)?,
    val onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)?,
    val onError: ((String) -> Unit)?,
    val onErrorRich: ((ExecutionErrorInfo) -> Unit)?,
    val wholeScriptExec: Boolean,
    val dialectGateRequest: ExecutionDialectGate.Request? = null,
    val onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)? = null,
    private val submissionSink: ((ExecutionSubmission) -> Unit)? = null,
) {
    private val completed = java.util.concurrent.atomic.AtomicBoolean(false)
    val isCompleted: Boolean get() = completed.get()
    val onSubmission: (ExecutionSubmission) -> Unit = { complete(it) }

    fun complete(outcome: ExecutionSubmission, message: String? = null) {
        if (!completed.compareAndSet(false, true)) return
        submissionSink?.invoke(outcome)
        if (message != null) onError?.invoke(message)
    }
}

/** 准备失败可能发生在消费排队请求之前或之后，两种形态都结算当前和排队请求。 */
internal fun failPrepareRequests(
    gate: PrepareSingleFlightGate,
    expectedOwner: QueuedExecuteIntent?,
    current: ((ExecutionSubmission) -> Unit)?,
    currentError: ((String) -> Unit)?,
    consumed: QueuedExecuteIntent?,
    message: String,
) {
    if (expectedOwner != null) {
        gate.abort(expectedOwner, ExecutionSubmission.BLOCKED, message)
    } else {
        current?.invoke(ExecutionSubmission.BLOCKED)
        currentError?.invoke(message)
    }
    consumed?.complete(ExecutionSubmission.BLOCKED, message)
}

/** 后到请求替代当前显式执行，当前不能借用后到请求的 submitted。 */
internal fun selectPrepareSubmission(
    queued: QueuedExecuteIntent?, current: ((ExecutionSubmission) -> Unit)?,
): ((ExecutionSubmission) -> Unit)? {
    if (queued == null) return current
    current?.invoke(ExecutionSubmission.CANCELLED)
    return queued.onSubmission
}

/**
 * T-FirstExecResultInvisible F3：控制台内嵌模式的折叠判定（纯函数，可单测）。
 *
 * 旧形态（previewMode 布尔）一个参数兼「预览」与「全链执行」两语义：全链执行换入也传
 * previewMode=true 导致执行组件被折叠——执行链三处折叠点（DataSourceSelector
 * prepareAfterEditorResolved/reuseSharedConsole + 面板 embedConsole）归口本枚举后，
 * **EXECUTE 恒展开**，PREVIEW 仅在结果区无内容时折叠（有内容不吞用户可见的结果——
 * 覆盖「执行中快速切换预览条目」的竞态窗口）。
 */
internal enum class ConsoleEmbedMode {
    /** 预览准备链（prepareDefaultConsole/recoverConsole）：无内容折叠隐藏空占位，有内容保持。 */
    PREVIEW,

    /** 执行链（executeSql 全链/executePrepared/EXPLAIN 直送）：恒展开（divider=0.6）。 */
    EXECUTE,
    ;

    /** 折叠判定：EXECUTE 恒 false；PREVIEW 仅结果区无内容时 true。 */
    fun shouldCollapseDivider(resultAreaHasContent: Boolean): Boolean =
        this == PREVIEW && !resultAreaHasContent
}
