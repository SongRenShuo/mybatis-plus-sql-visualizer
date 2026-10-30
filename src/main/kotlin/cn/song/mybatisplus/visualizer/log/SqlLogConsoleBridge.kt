package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.awt.Component
import java.awt.Container
import java.io.File
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import javax.swing.Timer

/**
 * 运行控制台反射桥（T-M31-03）：`com.intellij.execution.*`（RunContentManager/ConsoleView）
 * 不在插件编译 classpath（IPGP SDK 发行版裁剪 execution 模块），运行期由真实 IDE 提供——
 * 与既有 `execute/` 数据库链路同一隔离策略（build.gradle.kts 注释约定）。
 *
 * **T-M31.10 版本差异实证（真实 IDE 2026.2.1 实测失败定位）**：
 * - 2024.3/2025.3：`com.intellij.execution.RunContentManager`（extends ContentManager，有 addContentManagerListener）
 * - **2026.2：接口移到 `com.intellij.execution.ui.RunContentManager`，不再 extends ContentManager**（无 addContentManagerListener）
 * → 双包名候选加载；增量挂载（contentAdded）在 262 无接口方法 → **轮询兜底**（2s scanAll，幂等）。
 *
 * 全部调用经反射 + Proxy，任一步失败即优雅降级（返回 false / 静默跳过），不抛异常。
 *
 * T-xx（设置生效性审计）：`open` 开放子类化——[SqlLogManager] 内部测试缝（installBridgeForTest）
 * 注入受控桥（attach 恒成功/恒失败）验证 capturing 状态机，生产不受影响。
 */
open class SqlLogConsoleBridge(
    private val buffer: SqlLogEntryBuffer,
    private val parserConfig: () -> ParserConfig = {
        ParserConfig("Preparing:", "Parameters:", true, 20000)
    },
    private val syncBufferConfig: () -> Unit = {},
    private val onConsoleAppeared: () -> Unit = {},
    private val isCapturing: () -> Boolean = { true },
) {

    private var runContentManager: Any? = null
    /**
     * 已挂载的控制台 → Filter 代理映射。
     * 值从 Boolean 改为 Pair<filter, ifaceClass>，供 [detachAllFilters] 移除 Filter 使用。
     */
    // T-Perf-22：synchronized 包装保留 IdentityHashMap 的同一性语义——EDT 轮询/事件写
    // （scanComponent/contentAdded）与 Filter 回调读（任意控制台出线程）并发访问普通
    // IdentityHashMap 构成并发修改（死循环/异常风险，窗口窄但真实）。
    val attachedConsoles: MutableMap<Any, Pair<Any, Class<*>>> =
        java.util.Collections.synchronizedMap(IdentityHashMap<Any, Pair<Any, Class<*>>>())
    /** 每个已挂载控制台 → 解析器上下文（含暂存源码位置，F-44-6）。 */
    private val consoleContexts: MutableMap<Any, BridgeLineContext> =
        java.util.Collections.synchronizedMap(IdentityHashMap<Any, BridgeLineContext>())
    private val contentListener = object : ContentManagerListener {
        override fun contentAdded(event: ContentManagerEvent) {
            val component = event.content.component
            LOG.info("contentAdded: ${component.javaClass.name}")
            scanComponent(component)
            onConsoleAppeared()
        }
        override fun contentRemoved(event: ContentManagerEvent) {
            LOG.info("contentRemoved: ${event.content.component.javaClass.name}")
            onConsoleRemoved()
        }
    }

    private var pollTimer: Timer? = null
    /** 高频补捞定时器（attach 后短暂运行，捕获第一条 SQL）。 */
    private var catchUpTimer: Timer? = null
    /** 当前快速轮询间隔（T-LogPerf-01：500ms 起步指数退避，上限=常规间隔）。 */
    private var currentFastDelayMs = FAST_POLL_INTERVAL_MS
    /** 新控制台出现回调（由 manager 设置）。 */
    var onConsoleRemoved: () -> Unit = {}

    /** 反射取 RunContentManager 实例（双包名候选）并挂载现有 console；环境缺 execution API 时返回 false。
     *  T-xx：`open` 供测试受控桥覆写（attach 恒成功/恒失败）。 */
    open fun attach(project: Project): Boolean {
        LOG.info("attach: 开始（project=${project.name}, runContentManager=${runContentManager?.javaClass?.name ?: "null"}）")
        if (runContentManager != null) {
            LOG.info("attach: already attached")
            return true
        }
        val rcm = runContentManagerOrNull(project)
        if (rcm == null) {
            LOG.warn("attach: no RunContentManager candidate available (execution API missing in this environment)")
            return false
        }
        runContentManager = rcm
        attachContentListener(rcm)
        scanAll()
        LOG.info("attach: 成功 RCM=${rcm.javaClass.name}, consolesAttached=${attachedConsoles.size}")
        return true
    }

    /** 停止快速轮询定时器。 */
    private fun stopCatchUpPolling() {
        catchUpTimer?.stop()
        catchUpTimer = null
    }

    /**
     * 启动轮询：无控制台时快速轮询（500ms 起步，每拍无主控制台间隔翻倍至 2s 封顶）
     * → 检测到新控制台后切换常规轮询（2s）。
     * 性能（T-LogPerf-01）：快速轮询不再 100ms 常驻——armed 态全程的 EDT 负载
     * 从每秒 10 拍降至 2~4 拍起步并快速衰减到常规频率。
     */
    fun startPolling() {
        if (catchUpTimer != null || pollTimer != null) return
        startFastPolling()
        LOG.debug("startPolling: 快速轮询已启动（${FAST_POLL_INTERVAL_MS}ms，指数退避至 ${POLL_INTERVAL_MS}ms）")
    }

    fun stopPolling() {
        pollTimer?.stop()
        pollTimer = null
        stopCatchUpPolling()
    }

    /** 轮询是否活跃（测试断言用：验证 stop 后轮询已停止）。 */
    val isPollingActive: Boolean get() = pollTimer != null || catchUpTimer != null

    /** 快速轮询（500ms 起步）—— 项目运行中但未检测到控制台时，尽快捕获第一条 SQL。 */
    private fun startFastPolling() {
        stopCatchUpPolling()
        currentFastDelayMs = FAST_POLL_INTERVAL_MS
        catchUpTimer = Timer(currentFastDelayMs) { onCatchUpTick() }.apply { isRepeats = true; start() }
    }

    /** 快速轮询回调：先扫描（可能切走常规轮询），仍在等待且无主控制台则退避放宽下一拍。 */
    private fun onCatchUpTick() {
        scanAll()
        if (catchUpTimer != null && !lastHadMainConsole) {
            currentFastDelayMs = nextFastDelay(currentFastDelayMs, backoff = true)
            catchUpTimer?.delay = currentFastDelayMs
        }
    }

    /** 常规轮询（2s）—— 已检测到控制台后，低频兜底。 */
    private fun startNormalPolling() {
        stopCatchUpPolling()
        pollTimer = Timer(POLL_INTERVAL_MS) { scanAll() }.apply { isRepeats = true; start() }
    }

    /** 移除挂载（项目关闭/面板销毁时）。 */
    fun detach() {
        stopPolling()
        detachAllFilters()
        val rcm = runContentManager ?: return
        try {
            rcm.javaClass.getMethod("removeContentManagerListener", CONTENT_LISTENER_CLASS)
                .invoke(rcm, contentListener)
        } catch (t: Throwable) {
            LOG.debug("detach: removeContentManagerListener failed", t)
        }
        runContentManager = null
    }

    /** ContentManagerListener 是否注册成功（243/253=成功，262+=失败→需轮询兜底）。 */
    var contentListenerRegistered = false
        private set

    private fun attachContentListener(rcm: Any) {
        try {
            rcm.javaClass.getMethod("addContentManagerListener", CONTENT_LISTENER_CLASS)
                .invoke(rcm, contentListener)
            contentListenerRegistered = true
            LOG.info("attach: ContentManagerListener 注册成功（${rcm.javaClass.name}）")
        } catch (t: Throwable) {
            // 2026.2 接口无此方法（不 extends ContentManager）→ 轮询兜底
            contentListenerRegistered = false
            LOG.warn("attach: ContentManagerListener 不可用（${rcm.javaClass.name}）— 轮询兜底")
        }
    }

    /** 上一轮是否检测到主运行控制台（ConsoleViewImpl——Spring Boot 等 run 进程）。 */
    private var lastHadMainConsole = false

    /**
     * 实时查询 RunContentManager 当前是否有**进程存活的**主运行控制台。
     * 经实证：getAllDescriptors() 在进程终止后仍返回旧 tab（tab 保留），
     * 故须额外检查 ProcessHandler.isProcessTerminated()——进程死即视为「无主控制台」。
     * `open` 供测试受控桥覆写（见 [SqlLogManager] 内部测试缝）。
     */
    open fun hasMainConsole(): Boolean {
        val rcm = runContentManager ?: return false
        val descriptors = descriptorsOf(rcm) ?: return false
        for (descriptor in descriptors) {
            val component = componentOf(descriptor) ?: continue
            val isMain = component.javaClass.name.contains("ConsoleViewImpl") ||
                !component.javaClass.name.contains("HttpSingleResponseConsole")
            if (!isMain) continue
            // 主控制台存在 → 检查进程是否还活着
            if (isProcessAlive(descriptor)) return true
        }
        return false
    }

    /** 反射检查 RunContentDescriptor 关联的进程是否存活（ProcessHandler.isProcessTerminated == false 且 isProcessTerminating == false）。 */
    private fun isProcessAlive(descriptor: Any?): Boolean {
        if (descriptor == null) return false
        return try {
            val processHandler = cachedMethod(descriptor.javaClass, "getProcessHandler")?.invoke(descriptor)
                ?: return false
            val terminated = cachedMethod(processHandler.javaClass, "isProcessTerminated")?.invoke(processHandler) as? Boolean ?: true
            val terminating = cachedMethod(processHandler.javaClass, "isProcessTerminating")?.invoke(processHandler) as? Boolean ?: true
            val alive = !terminated && !terminating
            LOG.debug("isProcessAlive: alive=$alive, descriptor=${descriptor.javaClass.simpleName}")
            alive
        } catch (t: Throwable) {
            LOG.debug("isProcessAlive: 反射失败（${t.javaClass.simpleName}），默认视为存活")
            true // 反射失败时保守视为存活（避免误停）
        }
    }

    /** descriptor → 已解析 console 缓存（T-LogPerf-03：命中且已挂载时跳过组件树遍历）。 */
    private val descriptorConsoleCache = IdentityHashMap<Any, Any?>()

    /**
     * 遍历 RunContentManager 全部运行描述符，对每个 console 挂载 filter（幂等）。
     * 主控制台移除检测：HttpSingleResponseConsole（HTTP 请求工具窗口）常驻，
     * 即使 Spring Boot 停止也不消失——故检测「ConsoleViewImpl 主控制台消失」而非「所有控制台消失」。
     */
    open fun scanAll() {
        val hasMain = hasMainConsole()
        // 每轮状态日志（debug——诊断主控制台移除检测）
        LOG.debug("scanAll: hasMainConsole=$hasMain, lastHadMainConsole=$lastHadMainConsole")
        // T-LPC-05：控制台解析的静默失效计数周期上报（零增量零输出）——解析器内部无 IDE 依赖，
        // 上报挂在既有轮询上，排障时能直接看到「参数被丢/等待态拦下/残句闭合」各发生多少次。
        LogParseDiag.takeDeltaReport()?.let { LOG.info(it) }
        // T-ConsolePendingFlush：待闭合条目静默冲刷——应用执行完 SQL 后一行都不再打时，只有这一拍能把
        // 桶里的待产出条目交出去（见 flushSettledEntries KDoc）。增强接管中（paused）不冲——那期间条目
        // 由 agent 通道产出，桥侧冲刷会造成双路重复。
        // T-CaptureCatchAll 券02：活性回落解封期间照常冲刷——此时 agent 通道已判定为空转，桥是唯一产出方。
        if (isCapturing() && !SqlCaptureGate.bridgeSuppressed()) {
            flushSettledEntries()
        }
        // 主控制台移除：上一轮有、当前轮无 → 项目停止
        if (lastHadMainConsole && !hasMain) {
            LOG.info("scanAll: 主控制台已移除（上轮有、当前无），触发 onConsoleRemoved")
            onConsoleRemoved()
        }
        // 主控制台出现 → 自动重新监听
        if (!lastHadMainConsole && hasMain) {
            LOG.info("scanAll: 主控制台出现，触发 onConsoleAppeared")
            onConsoleAppeared()
            if (catchUpTimer != null) {
                LOG.info("scanAll: 检测到控制台，快速轮询 → 常规轮询（2s）")
                startNormalPolling()
            }
        }
        // 挂载 filter（幂等）；已挂载的 console 经缓存短路，不再遍历组件树（T-LogPerf-03）
        val rcm = runContentManager ?: return
        val descriptors = descriptorsOf(rcm) ?: return
        for (descriptor in descriptors) {
            if (descriptor == null) continue
            val cachedConsole = descriptorConsoleCache[descriptor]
            // T-Perf-22：keys 视图遍历须持 map 监视器——快照后判定
            if (shouldSkipRescan(cachedConsole, synchronized(attachedConsoles) { attachedConsoles.keys.toList() })) continue
            val component = componentOf(descriptor) ?: continue
            scanComponent(component)
            descriptorConsoleCache[descriptor] = findConsoleView(component)
            if (descriptorConsoleCache.size > DESCRIPTOR_CACHE_MAX) descriptorConsoleCache.clear()
        }
        lastHadMainConsole = hasMain
    }

    /** 组件树中查找 ConsoleView 实现并挂载（供 contentAdded 与扫描复用）。 */
    fun scanComponent(component: Component?) {
        val console = findConsoleView(component)
        if (console == null) {
            LOG.debug("scanComponent: no ConsoleView found under ${component?.javaClass?.name}")
            return
        }
        attachFilter(console)
    }

    /** T-PS4-02：epActive 短路触发行计数（防误伤可观测——计划 §六：debug 计数日志 + 测试计数探针。
     *  若平台 filter 链时序反转致短路误伤丢行，本计数与对应 debug 日志即排查锚点）。 */
    val epActiveShortCircuitCount = java.util.concurrent.atomic.AtomicLong()

    /** T-ConsolePendingFlush：filter 挂载代际号（每次 [attachFilter] 成功挂载 +1，写入 ctx）。 */
    private val filterGeneration = java.util.concurrent.atomic.AtomicLong()

    /**
     * T-CaptureTruth T-CT-03：控制台观测行数（每行进入桥 filter 即 +1，短路行也计）。
     * 面板零产出诊断用——「已扫描 N 行 / 产出 0 条」是区分「控制台压根没输出」与
     * 「有输出但没匹配到 SQL 日志」的唯一信号（后者指向日志级别/前缀配置）。
     */
    val consoleLinesSeen = java.util.concurrent.atomic.AtomicLong()

    /**
     * T-RA-03：重放丢弃行累计（跨全部已挂载控制台求和），SQL 日志面板链路诊断行取数入口。
     *
     * 为什么要在桥这层聚合：计数本体按 console 存在 [BridgeLineContext]（重放守卫每控制台独立
     * 实例，`rehighlight` 重放面也各自独立），而面板只持有桥；逐 console 明细对「零产出排障」
     * 无价值，用户要的是「一共被守卫丢了多少行」。
     *
     * 此前该计数只自增、生产侧零读取——守卫误丢行（把正常流式行判成重放）时面板依旧显示
     * 「已扫描 N 行 / 产出 0 条」，用户无从区分「控制台没输出」与「输出被守卫吃掉了」。
     */
    val replayDropCount: Long
        get() = synchronized(consoleContexts) { consoleContexts.values.sumOf { it.replayDropCount.get() } }

    /** T-PS4-02：internal 测试缝——两时序单路化测试直接对桩控制台挂载桥 filter（scanAll 需真实组件树）。 */
    internal fun attachFilter(console: Any) {
        // T-CaptureTruth T-CT-04：**不再**因 epActive 跳过挂载。原挂载面守卫的收益只是省一次代理
        // 分配，代价却是「EP 未覆盖该控制台时该控制台彻底无解析器」——封口改由 handler 逐行短路承担
        // （epActive 置位后桥仍挂载但不再解析，CPU 收益与原先等价），挂载面则始终保底。
        // BUG FIX（停止后仍捕获）：非捕捉态时不挂载 Filter——守卫轮询/事件回调触发的 scanAll。
        if (!isCapturing()) {
            LOG.debug("attachFilter: 非捕捉态，跳过挂载 ${console.javaClass.name}")
            return
        }
        if (attachedConsoles.containsKey(console)) {
            LOG.debug("attachFilter: already attached ${console.javaClass.name}")
            return
        }
        for (filterIfaceName in FILTER_IFACE_CANDIDATES) {
            try {
                val filterIface = Class.forName(filterIfaceName, true, platformClassLoader)
                // T-PS5-09：此处原有一组「三解析器 + hibernateEnabled」首建即弃（下方 cfg0 组才是
                // 真正进 BridgeLineContext 的那组，配置源同为 parserConfig()）——8 月既有死分配，
                // 纯删码零语义变化（连带的第二次 parserConfig() 也一并省掉）。
                // 每个 console 持有独立解析器 + 暂存源码位置（避免多控制台相互干扰，F-44-6）
                val cfg0 = parserConfig()
                val ctx = BridgeLineContext(
                    myBatisParser = MyBatisLogParser(
                        sqlPrefix = cfg0.sqlPrefix,
                        paramPrefix = cfg0.paramPrefix,
                        mixedPrefix = cfg0.mixedPrefix,
                        maxParseLength = cfg0.maxParseLength,
                    ),
                    hibernateParser = HibernateLogParser(
                        sqlPrefix = cfg0.hibernateSqlPrefix,
                        paramPrefix = cfg0.hibernateParamPrefix,
                        mixedPrefix = cfg0.mixedPrefix,
                        maxParseLength = cfg0.maxParseLength,
                    ),
                    jdbcParser = JdbcTemplateLogParser(enabled = cfg0.jdbcEnabled, maxParseLength = cfg0.maxParseLength),
                    shardingParser = ShardingSphereLogParser(maxParseLength = cfg0.maxParseLength),
                    configProvider = parserConfig,
                    hibernateEnabled = cfg0.hibernateEnabled,
                    shardingEnabled = cfg0.shardingEnabled,
                )
                consoleContexts[console] = ctx
                // T-PreviewFlash：反射缓存 console.getEditor()（ConsoleView.getEditor 为 public）。
                // 取不到则守卫降级停用（editor 为 null → decide 恒 Process＝现状语义），绝不阻塞捕获。
                attachReplayGuard(console, ctx)
                // T-LogPerf-04：timePrefix 缓存化——设置变更走 refreshConsoleContexts，逐行不再读 PluginSettings
                timePrefixCache = PluginSettings.getInstance().state.logTimePrefix.takeIf { it.isNotBlank() }
                // T-ConsolePendingFlush：代际号写入上下文——旧代理凭它识别自身已作废（见 handleConsoleLine）
                val generation = filterGeneration.incrementAndGet()
                ctx.generation = generation
                val filter = Proxy.newProxyInstance(
                    filterIface.classLoader,
                    arrayOf(filterIface),
                    LogFilterProxyHandler { line, endOffset -> handleConsoleLine(console, generation, line, endOffset) },
                )
                console.javaClass.getMethod("addMessageFilter", filterIface).invoke(console, filter)
                attachedConsoles[console] = filter to filterIface
                LOG.info("attachFilter: filter($filterIfaceName) attached to ${console.javaClass.name}")
                return
            } catch (t: Throwable) {
                LOG.debug("attachFilter: candidate $filterIfaceName failed on ${console.javaClass.name}", t)
            }
        }
        LOG.warn("attachFilter: all filter candidates failed on ${console.javaClass.name}")
    }

    /**
     * T-M55-02：控制台错误文本反向绑定缓冲最近条目——绑定失败（条目被去重丢弃后缺位/超窗淘汰/
     * 缓冲为空）显式留 debug 痕，不阻塞主链（生产路径 bindErrorToRecent 的丢弃分支不可静默无痕）。
     */
    private fun bindConsoleError(errorText: String) {
        when (val result = buffer.bindErrorToRecent(errorText)) {
            SqlLogEntryBuffer.ErrorBindResult.BOUND -> {}
            else -> LOG.debug("bindConsoleError: 错误块绑定丢弃（$result）：${errorText.lineSequence().firstOrNull()?.take(120)}")
        }
    }

    /**
     * T-CaptureTruth T-CT-03 / T-PS4-02 / T-PreviewFlash / T-ConsolePendingFlush：桥 filter 的逐行处理体。
     *
     * 代际守卫（T-ConsolePendingFlush）：`generation` 是本代理挂载时的代际号，[BridgeLineContext.generation]
     * 是当前有效代际。2026.2 起 `ConsoleViewImpl` 只提供 `clearMessageFilters()`、**没有** `removeMessageFilter`
     * （javap 实证），stop→start 会在同一个 console 上留下摘不掉的旧代理；旧代理若继续按 console 键取到
     * **新**上下文，就会与新代理逐行重复解析、重复计数。代际不符即惰性退出（不解析、不计数）。
     *
     * 并发（T-ConsolePendingFlush）：本方法在控制台输出线程执行，而轮询侧 [flushSettledEntries] 在 EDT
     * 冲刷同一套解析器状态 → 以 ctx 为锁互斥（同线程重入无副作用；无 Swing 调用，不构成 EDT 死锁面）。
     */
    private fun handleConsoleLine(console: Any, generation: Long, line: String, endOffset: Int) {
        val context = consoleContexts[console] ?: return
        if (context.generation != generation) return // 旧代际代理：惰性退出
        // T-CaptureTruth T-CT-03：控制台观测行计数（短路行也计——「桥看到过多少行」是面板零产出诊断的输入）
        consoleLinesSeen.incrementAndGet()
        synchronized(context) {
            // T-PS4-02 双路解析封口 / T-CaptureTruth T-CT-04 语义收窄：平台 filter 链中 EP
            // （consoleFilterProvider）先于后挂的桥 filter 执行，且 epActive 只在 EP **实证
            // 产出过条目**后才置位——置位即意味着「EP 在本会话确实在链上且确实能产出」，桥侧
            // 短路重复解析（条目由 EP 单路产出）。EP 不在链上/产不出条目时 epActive 恒 false，
            // 桥照常解析：这正是回落通道的保底（原口径「EP 被调用过就封口」会让未覆盖的控制台
            // 静默零捕获）。防误伤可观测：计数 + debug 日志（计数即测试探针/丢行排查锚点）。
            if (SqlCaptureGate.epActive) {
                val total = epActiveShortCircuitCount.incrementAndGet()
                if (LOG.isDebugEnabled) LOG.debug("bridge filter: EP 已生效，短路桥侧重复解析（累计 $total 行）")
                return
            }
            // T-PS5-07：此处原有第二道 `if (SqlCaptureGate.epActive) return` ——不可达冗余
            // （同条件已在处理函数首行短路并计数/留痕），删除。
            // T-PreviewFlash：重放守卫——rehighlight 全文档重跑 filter 时丢弃历史行（平台契约要求幂等）。
            // 守卫依赖反射取到的编辑器文档（stamp/行0）；取不到（非真 ConsoleViewImpl，如测试桩）
            // 则降级停用，直连现状语义——绝不能因守卫误丢正常流式行。
            val stampReader = context.docStampReader
            if (stampReader != null) {
                when (context.replayGuard.decide(
                    endOffset,
                    stampReader(),
                    line,
                    // T-PerfSweep F1：行 0 提供者惰性传递——只有混合态分支才求值，
                    // 流式/纯重放路径不再付 O(文档大小) 的全文档拷贝
                    context.line0Reader,
                )) {
                    ConsoleReplayGuard.Decision.Replay -> context.replayDropCount.incrementAndGet()
                    else -> emitEntries(processConsoleLine(line, context, timePrefixCache, syncBufferConfig, onErrorText = ::bindConsoleError))
                }
            } else {
                emitEntries(processConsoleLine(line, context, timePrefixCache, syncBufferConfig, onErrorText = ::bindConsoleError))
            }
        }
    }

    /** 条目入队（桥的两条产出路径共用：逐行与轮询静默冲刷）。 */
    private fun emitEntries(entries: List<LogSqlEntry>) {
        // O-147 W0：桥轮询通道打标（EP 路径同款在 SqlConsoleBridgeFilterProvider）
        entries.forEach { buffer.addEntry(it.copy(captureProvenance = CaptureProvenance.CONSOLE_BRIDGE)) }
    }

    /**
     * T-ConsolePendingFlush：静默冲刷全部已挂载上下文的待产出条目（EDT，随轮询每拍调用一次，见 [scanAll]）。
     *
     * 为什么需要轮询这一拍：[processConsoleLine] 的逐行冲刷只在「控制台还在出下一行」时被触发；应用执行完
     * 最后一条 SQL 就安静下来（没人再打日志）时，那条语句的待闭合状态没有后继行来触发任何路径——只有定时器
     * 能兜住。没有它，低频工程仍然表现为「已扫描 N 行 / 产出 0 条」。
     *
     * @return 本次真正入队的条目数（测试断言用；生产路径只用于 INFO 留痕）。
     */
    internal fun flushSettledEntries(nowMs: Long = System.currentTimeMillis()): Int {
        var produced = 0
        val snapshot = synchronized(consoleContexts) { consoleContexts.values.toList() }
        for (ctx in snapshot) {
            synchronized(ctx) {
                ctx.jdbcParser.flushSettled(nowMs)
                for (entry in ctx.jdbcParser.takeEntries()) {
                    if (buffer.addEntry(entry)) produced++
                }
                // R10：ShardingSphere 同款静默兜底（条目只在下一条 Logic 行闭合，最后一语句无后继行）
                if (ctx.shardingEnabled) {
                    ctx.shardingParser.flushSettled(nowMs)
                    for (entry in ctx.shardingParser.takeEntries()) {
                        if (buffer.addEntry(entry)) produced++
                    }
                }
            }
        }
        if (produced > 0) {
            LOG.info(
                "flushSettledEntries: 静默冲刷待闭合条目 $produced 条（语句行之后同线程再无后继行——" +
                    "JdbcTemplate 无 TRACE 参数行 / 线程按请求新建；阈值 ${JdbcTemplateLogParser.DEFAULT_SETTLE_MS}ms）",
            )
        }
        return produced
    }

    /**
     * T-PreviewFlash：为重放守卫解析编辑器/文档反射句柄。全部 runCatching 包裹——
     * 任一环失败即守卫降级停用（editor null），行为等同现状语义，绝不阻塞 Filter 挂载。
     * `getEditor` 实现类缺失该方法时 docStamp/line0 读取器为 null，守卫 decide 恒 Process。
     * T-PerfSweep F1：反射 Method 在挂载时解析一次（此前逐行 getMethod）；行 0 文本按
     * stamp 失效缓存——重放风暴中同 stamp 连续行只付一次首行读取（缓存为
     * 尽力而为：Filter 回调线程间可见性无同步，最坏退化为多算一次，无正确性影响）。
     * T-PS4-06：行 0 读取改 `getCharsSequence` 首行区间读（[0, 首个行分隔)）——Document
     * charsSequence 是免拷贝视图，行区间 toString 只拷贝首行；此前 `getText` 全文档拷贝
     * （O(全史)）在 stamp 变化后的首个混合态行仍要付一次，调用面归零（doc 反射句柄中
     * 不再解析 getText——桩文档缺失该方法时守卫照常工作）。
     * T-PS4-06：internal 测试缝——line0 区间读测试直接对反射桩调用（经 attachFilter 须真实组件树）。
     */
    internal fun attachReplayGuard(console: Any, ctx: BridgeLineContext) {
        runCatching {
            val getEditor = console.javaClass.getMethod("getEditor")
            val editor = getEditor.invoke(console) ?: return
            val getDoc = editor.javaClass.getMethod("getDocument")
            val doc = getDoc.invoke(editor) ?: return
            val getStamp = doc.javaClass.getMethod("getModificationStamp")
            // T-PS4-06：首行区间读的免拷贝视图入口（Document.getCharsSequence 为平台 Document 接口方法）
            val getCharsSequence = doc.javaClass.getMethod("getCharsSequence")
            ctx.docStampReader = {
                runCatching {
                    (getStamp.invoke(doc) as? Number)?.toLong() ?: Long.MIN_VALUE
                }.getOrDefault(Long.MIN_VALUE)
            }
            var cachedStamp = Long.MIN_VALUE
            var cachedLine0: String? = null
            ctx.line0Reader = {
                runCatching {
                    val stamp = (getStamp.invoke(doc) as? Number)?.toLong() ?: Long.MIN_VALUE
                    if (stamp != cachedStamp) {
                        cachedLine0 = (getCharsSequence.invoke(doc) as? CharSequence)?.let { readFirstLineOf(it) }
                        cachedStamp = stamp
                    }
                    cachedLine0
                }.getOrNull()
            }
        }
    }

    /**
     * T-PS4-06：charsSequence 首行区间读（[0, 首个行分隔)）——逐字复刻既有
     * `getText().lineSequence().firstOrNull()` 语义防行为漂移：
     * 行分隔按 lineSequence 口径认 `\n` 与 `\r`（`\r\n`/`\r` 同为分隔）；无行分隔取全文
     * （单行文档场景）；空文档为 null。行 0 与守卫 lineText（平台 getLineEndOffset 侧，
     * 不含分隔符）同源同口径，混合态文本比对不失配。
     */
    private fun readFirstLineOf(chars: CharSequence): String? {
        if (chars.isEmpty()) return null
        val nl = chars.indexOf('\n')
        val cr = chars.indexOf('\r')
        val end = when {
            nl < 0 -> cr
            cr < 0 -> nl
            else -> minOf(nl, cr)
        }
        return if (end < 0) chars.toString() else chars.subSequence(0, end).toString()
    }

    /** 捕捉中逐行使用的 timePrefix 缓存（T-LogPerf-04：仅设置变更/挂载时刷新）。
     *  T-PS4-02：EP 通道（[SqlConsoleBridgeFilterProvider]）逐行同项共用本缓存（单一事实源，
     *  刷新点与桥完全一致：挂载 attachFilter + 设置变更 refreshConsoleContexts）——取代 EP 侧
     *  每行现场读 PluginSettings。EP 首行可早于桥挂载到达（startCapture 先置 capturing 再
     *  scanAll），挂载点刷新未发生时由 [currentTimePrefix] 惰性补种一次。 */
    @Volatile
    var timePrefixCache: String? = null
        private set

    /** T-PS4-02：补种标记（尽力而为可见性，无同步——最坏并发下多读一次设置，无正确性影响，
     *  同 line0 缓存的尽力而为口径）。 */
    @Volatile
    private var timePrefixSeeded = false

    /** T-PS4-02：EP 逐行取 timePrefix 的统一入口——未播种时惰性读一次设置，此后返回缓存值
     *  （挂载/设置变更事件照常覆盖刷新）。 */
    fun currentTimePrefix(): String? {
        if (!timePrefixSeeded) {
            timePrefixCache = PluginSettings.getInstance().state.logTimePrefix.takeIf { it.isNotBlank() }
            timePrefixSeeded = true
        }
        return timePrefixCache
    }

    /**
     * T-LogPerf-04：设置变更事件驱动刷新——[SqlLogManager] 订阅 SETTINGS_CHANGE_TOPIC 后调用，
     * 一次同步全部已挂载上下文的解析器配置与 timePrefix 缓存，取代逐行 configProvider() 同步。
     */
    fun refreshConsoleContexts() {
        val cfg = parserConfig()
        // T-Perf-22：values 遍历持监视器（快照后应用，避免与 Filter 回调并发修改）
        val snapshot = synchronized(consoleContexts) { consoleContexts.values.toList() }
        for (ctx in snapshot) applyConfig(ctx, cfg)
        timePrefixCache = PluginSettings.getInstance().state.logTimePrefix.takeIf { it.isNotBlank() }
    }

    /** 把 [ParserConfig] 快照同步进 [BridgeLineContext]（挂载与设置变更共用）。 */
    private fun applyConfig(ctx: BridgeLineContext, cfg: ParserConfig) {
        ctx.myBatisParser.sqlPrefix = cfg.sqlPrefix
        ctx.myBatisParser.paramPrefix = cfg.paramPrefix
        ctx.myBatisParser.mixedPrefix = cfg.mixedPrefix
        ctx.myBatisParser.maxParseLength = cfg.maxParseLength
        ctx.hibernateParser.sqlPrefix = cfg.hibernateSqlPrefix
        ctx.hibernateParser.paramPrefix = cfg.hibernateParamPrefix
        ctx.hibernateParser.mixedPrefix = cfg.mixedPrefix
        ctx.hibernateParser.maxParseLength = cfg.maxParseLength
        ctx.jdbcParser.enabled = cfg.jdbcEnabled
        ctx.jdbcParser.maxParseLength = cfg.maxParseLength
        ctx.hibernateEnabled = cfg.hibernateEnabled
        ctx.shardingParser.maxParseLength = cfg.maxParseLength
        ctx.shardingEnabled = cfg.shardingEnabled
    }

    /**
     * BUG FIX（停止后仍捕获）：移除所有已挂载控制台上的 Filter。
     * 遍历 [attachedConsoles]，对每个 console 调用 removeMessageFilter 反射移除 Filter 代理，
     * 然后清空映射——确保 stop 后控制台不再向 buffer 喂 SQL。
     *
     * 改进：尝试所有候选 Filter 接口（不依赖存储的接口），防止因接口不匹配导致移除失败。
     * 失败时输出 WARN 日志（非 DEBUG），便于用户/开发者诊断。
     */
    open fun detachAllFilters() {
        // T-Perf-22：entries 遍历持监视器快照（迭代体内 remove consoleContexts，不能在遍历
        // 同一 map 时持锁直改——快照后操作）
        val entriesSnapshot = synchronized(attachedConsoles) { attachedConsoles.entries.toList() }
        for ((console, filterPair) in entriesSnapshot) {
            consoleContexts.remove(console)
            val (filter, filterIface) = filterPair
            var removed = false
            // 优先尝试存储的接口
            try {
                console.javaClass.getMethod("removeMessageFilter", filterIface).invoke(console, filter)
                removed = true
                LOG.info("detachAllFilters: filter removed from ${console.javaClass.name}")
            } catch (_: Throwable) {
                // 存储的接口失败，尝试所有候选接口
                for (candidateName in FILTER_IFACE_CANDIDATES) {
                    try {
                        val candidate = Class.forName(candidateName, true, platformClassLoader)
                        console.javaClass.getMethod("removeMessageFilter", candidate).invoke(console, filter)
                        removed = true
                        LOG.info("detachAllFilters: filter removed via fallback $candidateName from ${console.javaClass.name}")
                        break
                    } catch (_: Throwable) {
                        // continue trying
                    }
                }
            }
            if (!removed) {
                // T-ConsolePendingFlush：2026.2 起 ConsoleViewImpl 只提供 clearMessageFilters()、无
                // removeMessageFilter（javap 实证），移除失败是**平台事实**而非异常路径——旧代理由
                // 「上下文已从 consoleContexts 摘除 + 代际号不符」双向失活（见 handleConsoleLine），
                // 不会再解析也不会计数。原 WARN「filter will remain active!」把平台限制说成故障，
                // 排查时会把人引向错误方向（本次故障现场就有这 4 条 WARN 做噪音）。
                LOG.info("detachAllFilters: 平台不支持单 filter 移除（${console.javaClass.simpleName}）——旧 filter 已代际失活")
            }
        }
        attachedConsoles.clear()
        // T-LogPerf-03：挂载记录清空后缓存全部失效，下一轮重新全量扫描
        descriptorConsoleCache.clear()
    }

    /**
     * BUG FIX（停止后仍捕获）：移除 contentManagerListener（243/253 平台）。
     * stop 后若不移除，控制台事件（contentAdded）会触发 onConsoleAppeared 重启捕捉。
     */
    open fun detachContentListener() {
        if (!contentListenerRegistered) return
        val rcm = runContentManager ?: return
        try {
            rcm.javaClass.getMethod("removeContentManagerListener", CONTENT_LISTENER_CLASS)
                .invoke(rcm, contentListener)
            LOG.info("detachContentListener: contentManagerListener removed")
        } catch (t: Throwable) {
            LOG.debug("detachContentListener: removeContentManagerListener failed", t)
        }
        contentListenerRegistered = false
    }

    /**
     * F-44-6：实时日志的源码位置不再由本 IDE 线程取栈（取不到应用帧，恒为 null），
     * 改为由运行期 [SqlSourceInterceptor] 在应用线程捕获、打印标记行，
     * 经 [processConsoleLine] 解析为 sourceFile/sourceLine（见 [SqlSourceMarker] / [CallerSourceLocator]）。
     */

    companion object {
        private val LOG = Logger.getInstance(SqlLogConsoleBridge::class.java)

        /** 双包名候选：2024.3/2025.3 在 execution 包，2026.2 移到 execution.ui 包（实证）。 */
        private val RCM_CLASS_CANDIDATES = listOf(
            "com.intellij.execution.RunContentManager",
            "com.intellij.execution.ui.RunContentManager",
        )
        /** Filter 接口双候选：243/253 为 ConsoleView 嵌套接口；2026.2 移除嵌套接口，addMessageFilter 参数改为独立接口（T-M31.12 实证）。 */
        private val FILTER_IFACE_CANDIDATES = listOf(
            "com.intellij.execution.ui.ConsoleView\$Filter",
            "com.intellij.execution.filters.Filter",
        )
        private val CONTENT_LISTENER_CLASS = ContentManagerListener::class.java
        private const val POLL_INTERVAL_MS = 2000 // 常规轮询间隔
        private const val FAST_POLL_INTERVAL_MS = 500 // 快速轮询起步间隔——T-LogPerf-01：指数退避至常规间隔，不再 100ms 常驻
        private const val DESCRIPTOR_CACHE_MAX = 64 // descriptor→console 缓存上限（超限整体失效防死引用滞留）

        /** T-LogPerf-01：快速轮询指数退避（纯函数便于单测）——无主控制台每拍翻倍，上限=常规间隔；重置回落起步间隔。 */
        internal fun nextFastDelay(currentMs: Int, backoff: Boolean): Int =
            if (!backoff) FAST_POLL_INTERVAL_MS
            else (currentMs * 2).coerceAtMost(POLL_INTERVAL_MS)

        /** T-LogPerf-03：短路判定（纯函数便于单测）——已知 console 且已挂载时跳过重新扫描。 */
        internal fun shouldSkipRescan(cachedConsole: Any?, attachedConsoles: Collection<Any>): Boolean =
            cachedConsole != null && attachedConsoles.any { it === cachedConsole }

        /**
         * T-LogPerf-02：反射 Method 缓存（热路径每拍轮询不再重复 getMethod 查找；找不到也缓存）。
         *
         * F50（T-PerfQuick5）两处修正：
         *  - **值改 [Optional]**——CHM 禁 null：原值类型 `Method?` 在负缓存路径
         *    `methodCache[key] = null` 运行时 NPE（被 descriptorsOf/componentOf 的
         *    catch(Throwable) 吞掉——负缓存从未生效，每拍重付 getMethod+NPE 构造成本）；
         *  - **键含 ClassLoader 身份**——同签名类名跨 classloader（平台与插件遮蔽同名类/
         *    动态卸载后的新 loader）不是同一个类，裸类名记忆化会跨 loader 撞键。
         */
        private val methodCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Method>>()

        /** F57（T-PerfSweep18）：methodCache 容量上限——键含 ClassLoader 身份，多 run 配置/动态卸载场景键空间无界，照 DESCRIPTOR_CACHE_MAX 先例超限整体代际失效（重付一次 getMethod 换有界）。 */
        private const val METHOD_CACHE_MAX = 64

        /** F57：测试观察缝——代际失效行为断言用（生产代码勿调用）。 */
        internal fun methodCacheSize(): Int = methodCache.size

        /** F50：记忆化键（纯函数可测）——类名 + ClassLoader 身份 + 方法名三元组。 */
        internal fun methodCacheKey(className: String, loader: ClassLoader?, name: String): String =
            "${className}@${System.identityHashCode(loader)}#$name"

        internal fun cachedMethod(clazz: Class<*>, name: String): java.lang.reflect.Method? {
            val key = methodCacheKey(clazz.name, clazz.classLoader, name)
            methodCache[key]?.let { return it.orElse(null) }
            val method = try {
                clazz.getMethod(name)
            } catch (_: Throwable) {
                null
            }
            methodCache[key] = java.util.Optional.ofNullable(method)
            // F57：超限整体代际失效（与 descriptorConsoleCache 同范式——清后本条目不回填，下次查询重付一次 getMethod）
            if (methodCache.size > METHOD_CACHE_MAX) methodCache.clear()
            return method
        }

        private fun descriptorsOf(rcm: Any): Array<*>? = try {
            val raw = cachedMethod(rcm.javaClass, "getAllDescriptors")?.invoke(rcm)
            when (raw) {
                is Array<*> -> raw
                is Collection<*> -> raw.toTypedArray()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }

        private fun componentOf(descriptor: Any?): Component? {
            if (descriptor == null) return null
            return try {
                cachedMethod(descriptor.javaClass, "getComponent")?.invoke(descriptor) as? Component
            } catch (_: Throwable) {
                null
            }
        }

        /** 平台 classloader（execution API 在此 classloader 中，不在插件 classloader 中）。 */
        private val platformClassLoader: ClassLoader =
            com.intellij.openapi.project.ProjectManager::class.java.classLoader

        /** 执行体系 API 在当前环境是否可用（测试环境/SDK 发行版为 false）。 */
        fun isConsoleApiAvailable(): Boolean = RCM_CLASS_CANDIDATES.any {
            try {
                Class.forName(it, true, platformClassLoader)
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun runContentManagerOrNull(project: Project): Any? {
            LOG.info("runContentManagerOrNull: 尝试解析 RCM（candidates=$RCM_CLASS_CANDIDATES, cl=${platformClassLoader.javaClass.simpleName}）")
            for (className in RCM_CLASS_CANDIDATES) {
                try {
                    val clazz = Class.forName(className, true, platformClassLoader)
                    LOG.info("runContentManagerOrNull: $className 已加载，调用 getInstance")
                    val rcm = clazz.getMethod("getInstance", Project::class.java).invoke(null, project)
                    if (rcm != null) {
                        LOG.info("attach: RCM resolved via $className")
                        return rcm
                    }
                    LOG.warn("runContentManagerOrNull: $className.getInstance 返回 null")
                } catch (t: Throwable) {
                    LOG.warn("runContentManagerOrNull: $className 不可用（${t.javaClass.simpleName}: ${t.message}）")
                }
            }
            return null
        }
    }
}

/**
 * 每个已挂载控制台持有的一组解析器 + 暂存源码位置（F-44-6）。
 * 解析器跨行保持配对状态（MyBatis 的 Preparing/Parameters 跨多行），故须按 console 持久化。
 * T-LogPerf-04：配置快照在挂载/设置变更时经 [SqlLogConsoleBridge.refreshConsoleContexts] 写入，
 * 逐行不再调 configProvider 同步（字段保留供测试直接构造）。
 */
internal class BridgeLineContext(
    val myBatisParser: MyBatisLogParser,
    val hibernateParser: HibernateLogParser,
    val jdbcParser: JdbcTemplateLogParser,
    /**
     * ShardingSphere 第四解析器（R10 T-ShardingSphereCompat，循三解析器并列先例）。
     * 默认值只为既有测试构造点零改动（8 处直构 BridgeLineContext 的用例不传即得新实例）；
     * 生产两处（EP EpLineFilter / 桥 attachFilter）**显式传入**。
     */
    val shardingParser: ShardingSphereLogParser = ShardingSphereLogParser(),
    val configProvider: () -> ParserConfig,
    var pendingSource: SourceMarkerData? = null,
    /** Hibernate 喂行开关（快照同步自 ParserConfig.hibernateEnabled）。 */
    var hibernateEnabled: Boolean = true,
    /** ShardingSphere 喂行开关（快照同步自 ParserConfig.shardingEnabled；R10 D3=A 默认开）。 */
    var shardingEnabled: Boolean = true,
) {
    /**
     * T-M55-02：正在聚合的执行报错文本块（错误行起，堆栈/`###` 续行追加；null = 块外）。
     * 逐行累积、经 processConsoleLine 的 onErrorText 回调实时绑定缓冲最近条目——
     * 最终绑定文本为完整块（首行 + 聚合堆栈），中间态被后一次绑定覆盖。
     */
    var pendingError: StringBuilder? = null

    /**
     * T-ConsolePendingFlush：本上下文当前有效的 filter 挂载代际号（[SqlLogConsoleBridge.attachFilter] 每次
     * 挂载 +1 写入）。旧代际的 filter 代理凭它识别自身已作废——2026.2 无单 filter 移除，旧代理摘不下来。
     */
    var generation: Long = 0L

    /** T-PreviewFlash：重放守卫（每 console 独立；editor 不可得时降级为恒 Process）。 */
    val replayGuard: ConsoleReplayGuard = ConsoleReplayGuard()

    /** 控制台文档 modificationStamp 读取函数（editor 不可得时为 null）。 */
    var docStampReader: (() -> Long)? = null

    /** 控制台首行文本读取函数（混合态判定用；editor 不可得时为 null）。 */
    var line0Reader: (() -> String?)? = null

    /**
     * 重放丢弃行累计计数（T-RA-03：面板链路诊断行经 [SqlLogConsoleBridge.replayDropCount] 读取）。
     *
     * AtomicLong 而非普通 Long：自增在控制台出线程（filter 回调），读取在 EDT（诊断定时器），
     * 普通字段无可见性保证（JIT 可长期持寄存器值）——诊断计数一旦读到陈旧 0 就恰好复现
     * 「守卫误丢行却无证据」的原始故障，故与 [SqlLogConsoleBridge.consoleLinesSeen] 同口径。
     */
    val replayDropCount = java.util.concurrent.atomic.AtomicLong()
}

/**
 * 处理一行控制台文本（F-44-6）：
 *  1. 若为本插件源码标记（[SqlSourceInterceptor] 注入）→ 暂存，不喂解析器；
 *     T-IntrUpg-01：自位污染标记（旧模板 isSelf 常量分叉时拦截器上报自身帧）不暂存，
 *     条目落无位置链路（MissingSource 引导 → 立即注入触发升级）；
 *  1.5) T-M55-02：SQL 执行报错行（[SqlErrorLineRecognizer] 词表命中）→ 旁路聚合错误块并经
 *     [onErrorText] 实时反向绑定缓冲最近条目（不改控制台输出、不干扰解析器主链）；
 *  2. 喂行到三个解析器（配置快照已由设置变更事件同步——T-LogPerf-04），取出已配对 SQL 条目；
 *  2.5) T-ConsolePendingFlush：喂行后冲刷「已静默 ≥1s」桶的待产出条目（见 [JdbcTemplateLogParser.flushSettled]）——
 *     按线程分桶后，语句行之后没有同线程后继行的语句（JdbcTemplate 无 TRACE 参数行 + 线程按请求新建）
 *     否则永远停在桶里；
 *  3. 附加耗时解析结果 + 暂存源码位置，写入 [LogSqlEntry]。
 * 纯函数式、无 IDE 依赖，可在单元测试中直接驱动（[SqlLogConsoleBridgeMarkerTest]）。
 */
private val MARKER_DEFENSE_LOG = Logger.getInstance("cn.song.mybatisplus.visualizer.log.SqlSourceMarkerDefense")

/**
 * M56 T-M56-02：增强捕获接管闸门——agent 在线时置 true，控制台桥的解析入口短路（空转），
 * 面板只呈现 agent 通道条目；连接丢失/开关关闭回落时置 false 恢复。桥链路本身零改动。
 * AtomicBoolean：读侧（逐行热路径）单次 volatile 读，写侧（会话状态切换）罕见。
 */
object SqlCaptureGate {
    @JvmField
    val paused = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * M56 T-M56-09：「EP 首行标志」——consoleFilterProvider EP 的 Filter 处理控制台行时置 true
     * （会话内粘滞）。桥据此短路自身的重复解析（条目由 EP 单路产出）。
     *
     * T-CaptureTruth T-CT-04 **语义收窄**：置位条件由「EP 处理过任意一行」改为「EP 实证产出过条目」。
     * 原口径下 EP 只要被平台调用一次就封死桥侧解析，而 EP 对某类控制台是否真在链上、是否真能产出
     * 从未真机验证（provider KDoc 自述「真机门禁留人工」）——一旦 EP 覆盖不全，桥的回落通道已被
     * 自己封死 = **静默零捕获**（与 T-CaptureTruth 立项现场同类症状）。收窄后：EP 必须先交出一条
     * SQL 条目才获信任；EP 不在链上或对该控制台产不出条目时，桥照常解析（代价仅是「全程无 SQL 的
     * 会话」多跑几道解析门，无条目可建，成本可忽略）。
     */
    @Volatile
    var epActive: Boolean = false

    /**
     * T-CaptureCatchAll 券02：桥的**有效**封停态——`paused` 且活性回落未解封。
     *
     * 逐行热路径只读两个 volatile（[paused] + [CaptureActivityFallback.isActive]），无锁。
     * 桥的所有「是否让位」判定都改用本函数（不再直接读 [paused]）：白名单漏一个驱动时
     * agent 会长期零事件，此时必须让控制台桥回来，否则两条通道同时零产出。
     */
    fun bridgeSuppressed(): Boolean = paused.get() && !CaptureActivityFallback.isActive()
}

/**
 * T-CaptureCatchAll 券02：控制台桥**活性回落**（调研报告 R10「`paused` 假在线」）。
 *
 * 立项动因：`paused` 在「agent 在线且应用侧 ACK 确认在采集」时置真，控制台桥随即短路。
 * 但 ACK 只证明「应用侧打开了采集开关」，不证明「织入真的命中过驱动」——白名单漏一个驱动时，
 * agent 零事件、桥已让位，用户视角就是「插件坏了」。本对象把「agent 沉默但控制台仍有 SQL」
 * 变成可自动恢复的事实：静默超过阈值后解封桥，事件恢复后立即重新让位（滞回）。
 *
 * 判据（全部为可核实的事实，不做因果断言）：
 *  - 让位已持续 ≥ 阈值（默认 10s，`-Dmpsv.capture.activityFallback.thresholdMs=<ms>` 可调）；
 *  - 窗口内**零** agent 事件（[onAgentEvent] 由事件到达打点驱动）；
 *  - 窗口内控制台**有 SQL 行**（由嗅探路径实证产出条目，不是「有任意控制台输出」）。
 *
 * 滞回：agent 事件一到达即解封回落（[onAgentEvent]），且静默计时从该事件重新起算——
 * 事件断断续续时不会在两条通道间反复横跳。
 *
 * 默认开启（`-Dmpsv.capture.activityFallback=false` 可关）：这是纯降级收益，不引入任何新采集面。
 * 选择系统属性而非设置项的理由：它不是功能开关（用户无「想要/不想要」之分），只是排障旋钮；
 * 放进设置页要连带改复选框计数守卫与 UI，收益不抵噪音。解封状态本身在面板空态诊断行与设置页
 * 状态行可见（见 [CaptureDiagnostics.activityFallbackActive]）。
 *
 * 线程：状态迁移由控制台输出线程（逐行）与 agent 读线程（事件）并发触发，统一 `synchronized`；
 * 判定本身 O(1)，只在 paused 期间执行。任何异常不得穿出（调用方在 filter 回调内）。
 */
object CaptureActivityFallback {

    /** 默认静默阈值：10s（调研报告 D4 倾向 A）。 */
    const val DEFAULT_THRESHOLD_MS = 10_000L

    /** 开关系统属性（`false`/`0`/`off` 关闭，其余视为开启）。 */
    const val ENABLED_PROP = "mpsv.capture.activityFallback"

    /** 阈值系统属性（毫秒；非正数/非法回落 [DEFAULT_THRESHOLD_MS]）。 */
    const val THRESHOLD_PROP = "mpsv.capture.activityFallback.thresholdMs"

    private val lock = Any()
    private var pausedSinceMs = 0L
    private var lastAgentEventMs = 0L
    private var lastConsoleSqlMs = 0L

    @Volatile
    private var active = false

    /** 解封次数（诊断/测试读数）。 */
    val engagements = java.util.concurrent.atomic.AtomicLong()

    /** 事件恢复后重新让位次数（诊断/测试读数）。 */
    val releases = java.util.concurrent.atomic.AtomicLong()

    /** 测试注入点：开关/阈值/时钟（null = 走系统属性与墙钟）。 */
    @Volatile
    internal var enabledOverride: Boolean? = null
    @Volatile
    internal var thresholdOverrideMs: Long? = null
    @Volatile
    internal var clockOverride: (() -> Long)? = null

    /** 当前时钟（毫秒）。 */
    fun now(): Long = clockOverride?.invoke() ?: System.currentTimeMillis()

    /** 是否启用（系统属性优先，出厂默认开启）。 */
    fun enabled(): Boolean = enabledOverride ?: when (
        System.getProperty(ENABLED_PROP)?.trim()?.lowercase()
    ) {
        "false", "0", "off", "no" -> false
        else -> true
    }

    /** 静默阈值毫秒。 */
    fun thresholdMs(): Long = thresholdOverrideMs
        ?: System.getProperty(THRESHOLD_PROP)?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        ?: DEFAULT_THRESHOLD_MS

    /** 解封态（false = 桥仍让位；agent 事件恢复即回落为 false）。 */
    fun isActive(): Boolean = active

    /**
     * 闸门迁移回调（[cn.song.mybatisplus.visualizer.log.agent.AgentManager.updateOnline] 调用）：
     * 置真时记录让位起点；置假时清空全部状态（新一次接管从零起算，不继承上一轮的解封态）。
     */
    fun onGateChanged(paused: Boolean, now: Long = now()) {
        runCatching {
            synchronized(lock) {
                if (paused) {
                    if (pausedSinceMs == 0L) pausedSinceMs = now
                } else {
                    resetLocked()
                }
            }
        }
    }

    /**
     * agent 事件到达打点（[cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter.markReceived]
     * 调用）：事件恢复 → 立即重新让位，静默计时从此刻重算（滞回）。
     */
    fun onAgentEvent(now: Long = now()) {
        runCatching {
            val wasActive = synchronized(lock) {
                lastAgentEventMs = now
                val was = active
                if (was) {
                    active = false
                    releases.incrementAndGet()
                }
                was
            }
            if (wasActive) {
                LOG.info("增强捕获活性回落解除：agent 事件恢复，控制台桥重新让位")
            }
        }
    }

    /**
     * 控制台行打点（仅在 `paused` 分支调用）：推进状态机并返回**本行是否应按正常路径解析**。
     *
     * @param sqlSeen 本行是否被嗅探路径实证解析出 SQL 条目（「控制台有 SQL 行」的唯一判据）
     * @return true = 解封态（含本行刚解封），调用方应解析该行；false = 继续让位
     */
    fun onConsoleLine(sqlSeen: Boolean, now: Long = now()): Boolean = runCatching {
        evaluateConsoleLine(sqlSeen, now)
    }.getOrDefault(false)

    private fun evaluateConsoleLine(sqlSeen: Boolean, now: Long): Boolean {
        if (!enabled()) {
            synchronized(lock) { active = false }
            return false
        }
        val threshold = thresholdMs()
        val alreadyActive: Boolean
        val engagedNow: Boolean
        synchronized(lock) {
            if (pausedSinceMs == 0L) pausedSinceMs = now
            if (sqlSeen) lastConsoleSqlMs = now
            alreadyActive = active
            engagedNow = !active && run {
                val silenceStart = maxOf(pausedSinceMs, lastAgentEventMs)
                val silentLongEnough = now - silenceStart >= threshold
                val consoleHasSql = sqlSeen || (lastConsoleSqlMs > 0L && now - lastConsoleSqlMs <= threshold)
                silentLongEnough && consoleHasSql
            }
            if (engagedNow) {
                active = true
                engagements.incrementAndGet()
            }
        }
        if (engagedNow) {
            LOG.info(
                "增强捕获活性回落：agent 已静默 ≥${threshold}ms 而控制台仍有 SQL 行，" +
                    "解封控制台桥恢复解析（本次解封第 ${engagements.get()} 次；agent 事件恢复即重新让位）",
            )
        }
        return alreadyActive || engagedNow
    }

    /** 测试/项目销毁：清空全部状态与计数（不动测试注入点——由 [resetForTest] 一并清）。 */
    fun reset() {
        synchronized(lock) { resetLocked() }
    }

    private fun resetLocked() {
        active = false
        pausedSinceMs = 0L
        lastAgentEventMs = 0L
        lastConsoleSqlMs = 0L
    }

    /** 测试收尾：状态 + 注入点 + 计数全部复位。 */
    internal fun resetForTest() {
        synchronized(lock) { resetLocked() }
        enabledOverride = null
        thresholdOverrideMs = null
        clockOverride = null
        engagements.set(0L)
        releases.set(0L)
    }

    private val LOG = Logger.getInstance("cn.song.mybatisplus.visualizer.log.CaptureActivityFallback")
}

internal fun processConsoleLine(
    line: String,
    ctx: BridgeLineContext,
    timePrefix: String?,
    syncBufferConfig: () -> Unit,
    onErrorText: ((String) -> Unit)? = null,
): List<LogSqlEntry> {
    // M56 T-M56-02：增强接管闸门——agent 在线时桥不产出条目，回落即恢复。
    // T-AgentDialectLoop / T-AgentLogTime：接管期间无条件转入日志时间嗅探——把行喂给既有解析器
    // 提取「日志行原始时间」暂存 LogTimeCorrelator（键=占位符版 SQL 指纹），agent 事件入队前
    // 取回回填，面板「日志时间」在增强模式下不丢失。日志时间是**数据**：采集不再受「时间显示开
    // 关/时间源」这类渲染偏好控制（关掉显示只影响渲染，不影响条目字段），故此处零开关判断。
    //
    // T-CaptureCatchAll 券02：让位不再是二值——[CaptureActivityFallback] 在「agent 静默但控制台
    // 仍有 SQL 行」时自动解封（白名单漏驱动的双哑场景）。解封态下本函数直接走正常解析；让位态下
    // 嗅探产出的条目若恰是解封触发点，直接采用该条目（不重复喂解析器、不丢这一行）。
    if (SqlCaptureGate.paused.get()) {
        if (!CaptureActivityFallback.isActive()) {
            val sniffed = sniffLogTimestamp(line, ctx)
            if (CaptureActivityFallback.onConsoleLine(sniffed.isNotEmpty(), CaptureActivityFallback.now())) {
                return finishEntries(sniffed, line, ctx, timePrefix)
            }
            return emptyList()
        }
    }
    // 1) 实时源码标记 → 暂存（不喂解析器，避免被当成 SQL）
    val marker = SqlSourceMarker.parse(line)
    if (marker != null) {
        if (SqlSourceMarker.isInterceptorSelfPath(marker.file)) {
            // T-IntrUpg-01 防线：标记指向拦截器文件本身 = 存量旧件自位污染，丢弃不暂存。
            // 留痕含原路径/行号，远程排障定位污染拷贝件用。
            MARKER_DEFENSE_LOG.info("SqlLogConsoleBridge: 丢弃拦截器自位污染标记 ${marker.file}:${marker.line}（旧版生成文件，建议设置页重新注入升级）")
            return emptyList()
        }
        ctx.pendingSource = marker
        return emptyList()
    }
    // 1.5) T-M55-02：SQL 执行报错旁路识别——词表廉价前置判定（O(词表×行长)，无正则），
    // 命中即聚合错误块并经 onErrorText 实时反向绑定缓冲最近条目（旁路记录，不改控制台输出、
    // 不影响三解析器主链：错误行随后照常喂解析器，行为与无错误行逐字一致）。
    // 续行判定先行：### /堆栈帧在块内视为续行（MyBatis ### 续行同时命中错误词表，不得重开块丢首行）。
    when {
        ctx.pendingError != null && SqlErrorLineRecognizer.isContinuationLine(line) -> {
            val sb = ctx.pendingError!!
            if (sb.length < SqlErrorLineRecognizer.MAX_ERROR_TEXT_LENGTH &&
                sb.count { it == '\n' } < SqlErrorLineRecognizer.MAX_ERROR_STACK_LINES
            ) {
                sb.append('\n').append(line.trim())
                onErrorText?.invoke(sb.toString())
            }
        }
        SqlErrorLineRecognizer.isErrorLine(line) -> {
            ctx.pendingError = StringBuilder(SqlErrorLineRecognizer.firstLineText(line))
            onErrorText?.invoke(ctx.pendingError.toString())
        }
        // 块外普通行（含空行放行，容忍堆栈间空行）结束当前错误块
        !line.isBlank() -> ctx.pendingError = null
    }
    // 2) 喂行到四个解析器（各自忽略不匹配的行；配置快照随设置变更事件刷新，逐行零同步成本）
    // F58（T-PerfSweep18 压轴）：线程 token 每行只提一次下传——原四解析器各自 bucketFor(line)
    // 重提同一 token（同一行最多 4 次 substring 窗口+级别锚正则+方括号扫描）。
    val threadToken = LogThreadRouter.threadTokenOf(line)
    ctx.myBatisParser.feed(line, threadToken)
    if (ctx.hibernateEnabled) ctx.hibernateParser.feed(line, threadToken)
    ctx.jdbcParser.feed(line, threadToken)
    if (ctx.shardingEnabled) ctx.shardingParser.feed(line, threadToken)
    // 2.5) T-ConsolePendingFlush：喂行后冲刷**已静默**桶的待产出条目。放在喂行之后是有意的——本行刚
    // 触达它自己的桶（活动时间刷新），该桶因此不会出现在冲刷名单里，本行所属语句的参数行不会被截断；
    // 被冲刷的只有「别的线程静默 ≥1s 的语句」（JdbcTemplate 无 TRACE 参数行 + 线程按请求新建时，
    // 那些语句永无同线程后继行）。冲刷出的条目与本次喂行产出的条目由下方同一次 takeEntries() 一并交出。
    // R10：ShardingSphere 解析器同款接入（其条目只在下一条 Logic 行闭合，静默冲刷是低频访问的兜底）。
    ctx.jdbcParser.flushSettled()
    if (ctx.shardingEnabled) ctx.shardingParser.flushSettled()
    val taken = ctx.myBatisParser.takeEntries() + ctx.hibernateParser.takeEntries() +
        ctx.jdbcParser.takeEntries() + ctx.shardingParser.takeEntries()
    if (taken.isEmpty()) return emptyList()
    return finishEntries(taken, line, ctx, timePrefix)
}

/**
 * T-CaptureCatchAll 券02：条目补全（耗时 + 源码位置）——正常解析路径与「解封瞬间采用嗅探条目」
 * 共用同一出口，避免两处各抄一份补全逻辑（两处漂移会让解封首批条目丢耗时/源码）。
 */
private fun finishEntries(
    taken: List<LogSqlEntry>,
    line: String,
    ctx: BridgeLineContext,
    timePrefix: String?,
): List<LogSqlEntry> {
    // 3) 耗时 + 源码位置（标记不可用时 sourceFile/sourceLine 为 null，回退表名搜索）
    // 解析策略：优先从日志行文本解析（ExecutionTimeParser），命中则统一填入；
    // 未命中时对每个条目尝试从 rawSql/restoredSql 解析（LogDurationParser，覆盖 druid/p6spy 格式）
    val lineDuration = ExecutionTimeParser.parse(line, timePrefix)
    val src = ctx.pendingSource
    val result = taken.map { e ->
        // F-44-7：MP 分页 COUNT 标记逐条判定（T-LogDedupeMpMark）——批次是「同一行喂入产出的全部条目」，
        // named 行触发 closePending 时批内常有两条（闭合的 prepared 副本 + 本条 named），批级 any 会把
        // 与 COUNT 同批的数据查询一并误标（且连带被判 NoSource 丢失源码跳转）。源码标记优先级不变。
        val eSrc = src ?: if (e.restoredSql.trimStart().startsWith("SELECT COUNT(", ignoreCase = true)) {
            SourceMarkers.mpPaginationCount()
        } else {
            null
        }
        val duration = lineDuration ?: LogDurationParser.parse(e)
        e.copy(
            sourceFile = eSrc?.file,
            sourceLine = eSrc?.line ?: 0,
            mapperStatement = eSrc?.mapperStatement,
            requestTag = eSrc?.requestTag,
            executionTimeMs = duration ?: e.executionTimeMs,
        )
    }
    if (ctx.pendingSource != null) ctx.pendingSource = null
    return result
}

/**
 * T-AgentDialectLoop / T-AgentLogTime：增强接管期间的日志时间嗅探——复用既有三解析器的配对/时间解析
 * （零新解析逻辑），产出的条目不进面板、仅把「[logTimeKey] → 日志时间」暂存 [LogTimeCorrelator]
 * （参数值序列必须匹配；没有真实日志时间的行不入册）。
 * 成本口径：每行 3 次 contains 门控（解析器既有门），每条 SQL 一次条目构造 + 指纹，
 * 条目即弃零驻留；源码标记行跳过（条目已弃，无需归属）。
 *
 * T-CaptureCatchAll 券02：返回本行解析出的条目——「控制台有 SQL 行」的实证信号由调用方喂给
 * [CaptureActivityFallback]；解封触发的那一行直接采用这些条目（不再重复喂解析器）。
 */
private fun sniffLogTimestamp(line: String, ctx: BridgeLineContext): List<LogSqlEntry> {
    // F58：同款每行一次 token 下传（嗅探路径同喂四解析器）
    val threadToken = LogThreadRouter.threadTokenOf(line)
    ctx.myBatisParser.feed(line, threadToken)
    if (ctx.hibernateEnabled) ctx.hibernateParser.feed(line, threadToken)
    ctx.jdbcParser.feed(line, threadToken)
    if (ctx.shardingEnabled) ctx.shardingParser.feed(line, threadToken)
    val taken = ctx.myBatisParser.takeEntries() + ctx.hibernateParser.takeEntries() +
        ctx.jdbcParser.takeEntries() + ctx.shardingParser.takeEntries()
    for (e in taken) {
        // 无时间 StdOut 的 timestamp 是 IDE 解析时刻，不能冒充应用日志时间。
        val logTime = e.logTimestamp ?: continue
        LogTimeCorrelator.put(logTimeKey(e), logTime, logTimeParamSignature(e.params))
    }
    return taken
}

/** 组件树中查找实现 `com.intellij.execution.ui.ConsoleView` 接口的组件（接口名匹配，无编译期引用）。 */
internal fun findConsoleView(component: Component?): Any? {
    if (component == null) return null
    if (implementsConsoleView(component.javaClass)) return component
    if (component is Container) {
        for (child in component.components) {
            findConsoleView(child)?.let { return it }
        }
    }
    return null
}

/** 类（含父类/父接口链）是否实现 ConsoleView 接口（接口名匹配）。 */
private fun implementsConsoleView(clazz: Class<*>): Boolean {
    if (clazz.interfaces.any { it.name == CONSOLE_VIEW_IFACE_NAME }) return true
    return clazz.interfaces.any { implementsConsoleView(it) } || clazz.superclass?.let { implementsConsoleView(it) } == true
}

private const val CONSOLE_VIEW_IFACE_NAME = "com.intellij.execution.ui.ConsoleView"
