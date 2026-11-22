package cn.song.mybatisplus.visualizer.log.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.registry.Registry

/**
 * T-HdAbsorb D215（吸收自 Hibernate Debugger 的 `CaptureSuspensionLedger` 停驻账本 +
 * `StatementDurationCorrelator` 停驻补偿思路）：调试器**挂起时段账本**与 agent 耗时补偿。
 *
 * 问题形态：用户在调试会话里打了断点，被监控应用的 JDBC 执行线程在断点处挂起——agent 的
 * `nanoTime` 差值把**挂起时长**一并计进耗时，条目呈现虚假的超慢查询（墙钟膨胀）。对方插件
 * 按线程累计停驻时长并在耗时里扣减；本仓的补偿语义（吸收时按捕获通路差异收敛）：
 *
 *  1. **时段账本**：订阅平台调试器事件（`DebuggerManagerListener` 会话挂接 +
 *     `DebugProcessListener.processSuspended/processResumed`），按**挂起时间墙钟区间**记账
 *     （环窗有界，容量 [MAX_INTERVALS]）。对方按线程累计，本仓按全局区间——agent 侧默认
 *     全线程挂起（suspend-all 策略），全局区间是忠实近似；线程级精确口径登记为已知边界；
 *  2. **补偿**：[compensatedDurationMs] 把事件的测量窗 `[end-duration, end)` 与账本区间求交，
 *     交集时长从耗时中扣除（下限 0）；无调试会话/账本空/交不起 → 原值返回（诚实降级，不猜）；
 *  3. **回退通道**：Registry `sqlvis.log.durationSuspendCompensation`（默认开）关掉即不补偿。
 *
 * 失败面纪律：订阅/监听全程 `runCatching`——调试器 API 在 Community/无 debugger 插件环境
 * 缺类时静默降级为「不补偿」（与 [DataSourceUrlProbe] 的 DatabaseTools 反射纪律同款）。
 */
object DebuggerSuspendTracker {

    private val LOG = Logger.getInstance(DebuggerSuspendTracker::class.java)

    /** Registry 总开关（plugin.xml `<registryKey>` 登记）。 */
    internal const val REGISTRY_KEY = "sqlvis.log.durationSuspendCompensation"

    /** 挂起区间环窗容量（条）：一次调试会话的断点命中量级远小于此，溢出丢最旧。 */
    private const val MAX_INTERVALS = 512

    /** 测试缝：时钟（毫秒）。 */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    /** 测试缝：总开关（生产＝`Registry.is(REGISTRY_KEY, true)`）。 */
    internal var enabledProvider: () -> Boolean = {
        runCatching { Registry.`is`(REGISTRY_KEY, true) }.getOrDefault(true)
    }

    /** 已挂接的 DebugProcess 引用（detach 时移除监听；弱身份防泄漏用简单集合即可，会话数极小）。 */
    private val attached = ArrayList<Any>(4)

    /** 挂起区间账本（毫秒墙钟；同步面 = messageBus 调度线程 + 适配链读，synchronized 双护）。 */
    private val intervals = ArrayDeque<LongRange>()

    /** 当前挂起起点（null = 未挂起）。 */
    private var suspendStartedAt: Long? = null

    /** 安装次数（守卫读数）。 */
    internal var installCount: Int = 0
        private set

    /**
     * 安装调试会话监听（幂等）：经应用级 `DebuggerManagerListener` topic 挂接——每个新调试
     * 会话挂 [processListener]，suspend/resume 记账。反射加载 debugger 面（Community/无
     * debugger 插件环境缺类 → 静默降级，[onSuspended]/[onResumed] 无人调用即零账本）。
     */
    fun install() {
        val already = synchronized(this) { if (installCount > 0) true else { installCount++; false } }
        if (already) return
        runCatching {
            val topicClass = Class.forName("com.intellij.debugger.impl.DebuggerManagerListener")
            val managerClass = Class.forName("com.intellij.debugger.DebuggerManager")
            val topicField = topicClass.getField("TOPIC")
            val topic = topicField.get(null)
            val listenerClass = Class.forName("com.intellij.debugger.impl.DebuggerManagerListener")
            val getProcessMethod = managerClass
                .getMethod("getProcess", com.intellij.execution.process.ProcessHandler::class.java)
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                topicClass.classLoader,
                arrayOf(topicClass),
                { _, method, args ->
                    when (method.name) {
                        "sessionCreated" -> runCatching {
                            val process = args?.getOrNull(0)
                            if (process != null) attachProcessListener(process)
                        }.onFailure { LOG.debug("DebuggerSuspendTracker attach failed", it) }
                        "sessionRemoved" -> synchronized(this) { attached.clear() }
                    }
                    null
                },
            )
            val connection = com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect()
            @Suppress("UNCHECKED_CAST")
            val subscribe = connection.javaClass.methods.firstOrNull { it.name == "subscribe" && it.parameterCount == 2 }
            subscribe?.invoke(connection, topic, listener)
            // getProcess 仅用于类型校验面（未调用）；防误报未使用告警
            runCatching { getProcessMethod } // NO-OP 校验类可达
            LOG.info("DebuggerSuspendTracker installed")
        }.onFailure {
            LOG.debug("DebuggerSuspendTracker install degraded (debugger classes unavailable)", it)
        }
    }

    /** 给单个 DebugProcess 挂 suspend/resume 监听（反射；失败只丢本会话的账本能力）。 */
    private fun attachProcessListener(process: Any) {
        runCatching {
            val listenerClass = Class.forName("com.intellij.debugger.impl.DebugProcessListener")
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
                { _, method, args ->
                    when (method.name) {
                        "processSuspended" -> onSuspended()
                        "processResumed" -> onResumed()
                    }
                    null
                },
            )
            process.javaClass.methods
                .firstOrNull { it.name == "addDebugProcessListener" && it.parameterCount == 1 }
                ?.invoke(process, listener)
            synchronized(this) {
                if (attached.size > 16) attached.removeAt(0)
                attached.add(process)
            }
        }.onFailure { LOG.debug("DebuggerSuspendTracker process attach failed", it) }
    }

    /** 挂起开始（DebugProcessListener.processSuspended 回调落点）。 */
    internal fun onSuspended() {
        synchronized(this) {
            if (suspendStartedAt == null) suspendStartedAt = clockMs()
        }
    }

    /** 恢复（DebugProcessListener.processResumed 回调落点）：闭合区间入账。 */
    internal fun onResumed() {
        synchronized(this) {
            val started = suspendStartedAt ?: return
            suspendStartedAt = null
            val end = clockMs()
            if (end > started) {
                if (intervals.size >= MAX_INTERVALS) intervals.removeFirst()
                intervals.addLast(started..end)
            }
        }
    }

    /** 测试缝：清账本（用例隔离）。 */
    internal fun clearForTest() {
        synchronized(this) {
            intervals.clear()
            suspendStartedAt = null
        }
    }

    /** 测试缝：直接注入区间（单测构造账本，不造真调试会话）。 */
    internal fun plantIntervalsForTest(ranges: List<LongRange>) {
        synchronized(this) {
            intervals.clear()
            ranges.forEach { intervals.addLast(it) }
        }
    }

    /**
     * 停驻补偿（纯函数，headless 直测）：测量窗 `[end - durationMs, end)` 与挂起区间求交，
     * 交集时长从耗时中扣除；结果下限 0。总开关关闭 / 账本空 / 交集为 0 → 原值返回。
     */
    fun compensatedDurationMs(endMs: Long, measuredDurationMs: Long): Long {
        if (measuredDurationMs <= 0) return measuredDurationMs
        if (!enabledProvider()) return measuredDurationMs
        val from = endMs - measuredDurationMs
        val overlap = synchronized(this) {
            intervals.sumOf { range ->
                val lo = maxOf(range.first, from)
                val hi = minOf(range.last, endMs)
                if (hi > lo) hi - lo else 0L
            }
        }
        if (overlap <= 0) return measuredDurationMs
        return (measuredDurationMs - overlap).coerceAtLeast(0L)
    }

    /** 账本是否可能非空（诊断行用；进程级粗判即可）。 */
    val hasSuspendHistory: Boolean
        get() = synchronized(this) { intervals.isNotEmpty() || suspendStartedAt != null }
}
