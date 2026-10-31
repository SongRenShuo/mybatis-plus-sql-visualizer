package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;

import java.awt.Component;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.swing.Timer;

import kotlin.Pair;

/**
 * 运行控制台反射桥（T-M31-03）：`com.intellij.execution.*`（RunContentManager/ConsoleView）
 * 不在插件编译 classpath（IPGP SDK 发行版裁剪 execution 模块），运行期由真实 IDE 提供——
 * 与既有 `execute/` 数据库链路同一隔离策略（build.gradle.kts 注释约定）。
 *
 * 全部调用经反射 + Proxy，任一步失败即优雅降级（返回 false / 静默跳过），不抛异常。
 *
 * T-xx（设置生效性审计）：`open` 开放子类化——[SqlLogManager] 内部测试缝（installBridgeForTest）
 * 注入受控桥（attach 恒成功/恒失败）验证 capturing 状态机，生产不受影响。
 */
public class SqlLogConsoleBridge {

    private final SqlLogEntryBuffer buffer;
    private final Supplier<ParserConfig> parserConfig;
    private final Runnable syncBufferConfig;
    private final Runnable onConsoleAppeared;
    private final BooleanSupplier isCapturing;

    private Object runContentManager;
    /**
     * 已挂载的控制台 → Filter 代理映射。
     * 值从 Boolean 改为 Pair<filter, ifaceClass>，供 detachAllFilters 移除 Filter 使用。
     *
     * T-Perf-22：synchronized 包装保留 IdentityHashMap 的同一性语义——EDT 轮询/事件写
     * （scanComponent/contentAdded）与 Filter 回调读（任意控制台出线程）并发访问普通
     * IdentityHashMap 构成并发修改（死循环/异常风险，窗口窄但真实）。
     */
    public final Map<Object, Pair<Object, Class<?>>> attachedConsoles =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    /** 每个已挂载控制台 → 解析器上下文（含暂存源码位置，F-44-6）。 */
    private final Map<Object, BridgeLineContext> consoleContexts =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    /** T-PS4-02：epActive 短路触发行计数（防误伤可观测——计划 §六：debug 计数日志 + 测试计数探针。
     *  若平台 filter 链时序反转致短路误伤丢行，本计数与对应 debug 日志即排查锚点）。 */
    public final java.util.concurrent.atomic.AtomicLong epActiveShortCircuitCount =
            new java.util.concurrent.atomic.AtomicLong();

    /** T-ConsolePendingFlush：filter 挂载代际号（每次 {@link #attachFilter} 成功挂载 +1，写入 ctx）。 */
    private final java.util.concurrent.atomic.AtomicLong filterGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * T-CaptureTruth T-CT-03：控制台观测行数（每行进入桥 filter 即 +1，短路行也计）。
     * 面板零产出诊断用——「已扫描 N 行 / 产出 0 条」是区分「控制台压根没输出」与
     * 「有输出但没匹配到 SQL 日志」的唯一信号（后者指向日志级别/前缀配置）。
     */
    public final java.util.concurrent.atomic.AtomicLong consoleLinesSeen =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * T-RA-03：重放丢弃行累计（跨全部已挂载控制台求和），SQL 日志面板链路诊断行取数入口。
     *
     * <p>为什么要在桥这层聚合：计数本体按 console 存在 {@link BridgeLineContext}（重放守卫每控制台独立
     * 实例，{@code rehighlight} 重放面也各自独立），而面板只持有桥；逐 console 明细对「零产出排障」
     * 无价值，用户要的是「一共被守卫丢了多少行」。
     *
     * <p>此前该计数只自增、生产侧零读取——守卫误丢行（把正常流式行判成重放）时面板依旧显示
     * 「已扫描 N 行 / 产出 0 条」，用户无从区分「控制台没输出」与「输出被守卫吃掉了」。
     */
    public long getReplayDropCount() {
        synchronized (consoleContexts) {
            long sum = 0;
            for (BridgeLineContext ctx : consoleContexts.values()) {
                sum += ctx.replayDropCount.get();
            }
            return sum;
        }
    }

    /** descriptor → 已解析 console 缓存（T-LogPerf-03：命中且已挂载时跳过组件树遍历）。 */
    private final IdentityHashMap<Object, Object> descriptorConsoleCache = new IdentityHashMap<>();
    /** 捕捉中逐行使用的 timePrefix 缓存（T-LogPerf-04：仅设置变更/挂载时刷新）。
     *  T-PS4-02：EP 通道（SqlConsoleBridgeFilterProvider）逐行同项共用本缓存（单一事实源，
     *  刷新点与桥完全一致：挂载 attachFilter + 设置变更 refreshConsoleContexts）——取代 EP 侧
     *  每行现场读 PluginSettings。EP 首行可早于桥挂载到达（startCapture 先置 capturing 再
     *  scanAll），挂载点刷新未发生时由 {@link #currentTimePrefix()} 惰性补种一次。 */
    private volatile String timePrefixCache = null;

    /** T-PS4-02：补种标记（尽力而为可见性，无同步——最坏并发下多读一次设置，无正确性影响，
     *  同 line0 缓存的尽力而为口径）。 */
    private volatile boolean timePrefixSeeded = false;

    /** Kotlin {@code var timePrefixCache} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
    public String getTimePrefixCache() {
        return timePrefixCache;
    }

    /** T-PS4-02：EP 逐行取 timePrefix 的统一入口——未播种时惰性读一次设置，此后返回缓存值
     *  （挂载/设置变更事件照常覆盖刷新）。 */
    public String currentTimePrefix() {
        if (!timePrefixSeeded) {
            String raw = PluginSettings.getInstance().getState().getLogTimePrefix();
            timePrefixCache = (raw != null && !raw.isBlank()) ? raw : null;
            timePrefixSeeded = true;
        }
        return timePrefixCache;
    }

    private final ContentManagerListener contentListener = new ContentManagerListener() {
        @Override
        public void contentAdded(ContentManagerEvent event) {
            Component component = event.getContent().getComponent();
            LOG.info("contentAdded: " + component.getClass().getName());
            scanComponent(component);
            onConsoleAppeared.run();
        }

        @Override
        public void contentRemoved(ContentManagerEvent event) {
            LOG.info("contentRemoved: " + event.getContent().getComponent().getClass().getName());
            onConsoleRemoved.run();
        }
    };

    private Timer pollTimer;
    /** 高频补捞定时器（attach 后短暂运行，捕获第一条 SQL）。 */
    private Timer catchUpTimer;
    /** 当前快速轮询间隔（T-LogPerf-01：500ms 起步指数退避，上限=常规间隔）。 */
    private int currentFastDelayMs = FAST_POLL_INTERVAL_MS;
    /** 新控制台出现回调（由 manager 设置）。 */
    public Runnable onConsoleRemoved = () -> {
    };

    /** 上一轮是否检测到主运行控制台（ConsoleViewImpl——Spring Boot 等 run 进程）。 */
    private boolean lastHadMainConsole = false;

    /** ContentManagerListener 是否注册成功（243/253=成功，262+=失败→需轮询兜底）。 */
    private boolean contentListenerRegistered = false;

    public SqlLogConsoleBridge(SqlLogEntryBuffer buffer) {
        this(buffer,
                () -> new ParserConfig("Preparing:", "Parameters:", true, 20000),
                () -> {
                },
                () -> {
                },
                () -> true);
    }

    public SqlLogConsoleBridge(SqlLogEntryBuffer buffer, Supplier<ParserConfig> parserConfig,
                               Runnable syncBufferConfig, Runnable onConsoleAppeared, BooleanSupplier isCapturing) {
        this.buffer = buffer;
        this.parserConfig = parserConfig;
        this.syncBufferConfig = syncBufferConfig;
        this.onConsoleAppeared = onConsoleAppeared;
        this.isCapturing = isCapturing;
    }

    /**
     * 反射取 RunContentManager 实例（双包名候选）并挂载现有 console；环境缺 execution API 时返回 false。
     * T-xx：`open` 供测试受控桥覆写（attach 恒成功/恒失败）。
     */
    public boolean attach(Project project) {
        LOG.info("attach: 开始（project=" + project.getName() + ", runContentManager=" +
                (runContentManager != null ? runContentManager.getClass().getName() : "null") + "）");
        if (runContentManager != null) {
            LOG.info("attach: already attached");
            return true;
        }
        Object rcm = runContentManagerOrNull(project);
        if (rcm == null) {
            LOG.warn("attach: no RunContentManager candidate available (execution API missing in this environment)");
            return false;
        }
        runContentManager = rcm;
        attachContentListener(rcm);
        scanAll();
        LOG.info("attach: 成功 RCM=" + rcm.getClass().getName() + ", consolesAttached=" + attachedConsoles.size());
        return true;
    }

    /** 停止快速轮询定时器。 */
    private void stopCatchUpPolling() {
        if (catchUpTimer != null) {
            catchUpTimer.stop();
            catchUpTimer = null;
        }
    }

    /**
     * 启动轮询：无控制台时快速轮询（500ms 起步，每拍无主控制台间隔翻倍至 2s 封顶）
     * → 检测到新控制台后切换常规轮询（2s）。
     */
    public void startPolling() {
        if (catchUpTimer != null || pollTimer != null) {
            return;
        }
        startFastPolling();
        LOG.debug("startPolling: 快速轮询已启动（" + FAST_POLL_INTERVAL_MS + "ms，指数退避至 " + POLL_INTERVAL_MS + "ms）");
    }

    public void stopPolling() {
        if (pollTimer != null) {
            pollTimer.stop();
            pollTimer = null;
        }
        stopCatchUpPolling();
    }

    /** 轮询是否活跃（测试断言用：验证 stop 后轮询已停止）。 */
    public boolean isPollingActive() {
        return pollTimer != null || catchUpTimer != null;
    }

    /** 快速轮询（500ms 起步）—— 项目运行中但未检测到控制台时，尽快捕获第一条 SQL。 */
    private void startFastPolling() {
        stopCatchUpPolling();
        currentFastDelayMs = FAST_POLL_INTERVAL_MS;
        catchUpTimer = new Timer(currentFastDelayMs, e -> onCatchUpTick());
        catchUpTimer.setRepeats(true);
        catchUpTimer.start();
    }

    /** 快速轮询回调：先扫描（可能切走常规轮询），仍在等待且无主控制台则退避放宽下一拍。 */
    private void onCatchUpTick() {
        scanAll();
        if (catchUpTimer != null && !lastHadMainConsole) {
            currentFastDelayMs = nextFastDelay(currentFastDelayMs, true);
            catchUpTimer.setDelay(currentFastDelayMs);
        }
    }

    /** 常规轮询（2s）—— 已检测到控制台后，低频兜底。 */
    private void startNormalPolling() {
        stopCatchUpPolling();
        pollTimer = new Timer(POLL_INTERVAL_MS, e -> scanAll());
        pollTimer.setRepeats(true);
        pollTimer.start();
    }

    /** 移除挂载（项目关闭/面板销毁时）。 */
    public void detach() {
        stopPolling();
        detachAllFilters();
        Object rcm = runContentManager;
        if (rcm == null) {
            return;
        }
        try {
            rcm.getClass().getMethod("removeContentManagerListener", CONTENT_LISTENER_CLASS).invoke(rcm, contentListener);
        } catch (Throwable t) {
            LOG.debug("detach: removeContentManagerListener failed", t);
        }
        runContentManager = null;
    }

    /** ContentManagerListener 是否注册成功。 */
    public boolean isContentListenerRegistered() {
        return contentListenerRegistered;
    }

    private void attachContentListener(Object rcm) {
        try {
            rcm.getClass().getMethod("addContentManagerListener", CONTENT_LISTENER_CLASS).invoke(rcm, contentListener);
            contentListenerRegistered = true;
            LOG.info("attach: ContentManagerListener 注册成功（" + rcm.getClass().getName() + "）");
        } catch (Throwable t) {
            // 2026.2 接口无此方法（不 extends ContentManager）→ 轮询兜底
            contentListenerRegistered = false;
            LOG.warn("attach: ContentManagerListener 不可用（" + rcm.getClass().getName() + "）— 轮询兜底");
        }
    }

    /**
     * 实时查询 RunContentManager 当前是否有**进程存活的**主运行控制台。
     */
    public boolean hasMainConsole() {
        Object rcm = runContentManager;
        if (rcm == null) {
            return false;
        }
        Object[] descriptors = descriptorsOf(rcm);
        if (descriptors == null) {
            return false;
        }
        for (Object descriptor : descriptors) {
            Component component = componentOf(descriptor);
            if (component == null) {
                continue;
            }
            boolean isMain = component.getClass().getName().contains("ConsoleViewImpl") ||
                    !component.getClass().getName().contains("HttpSingleResponseConsole");
            if (!isMain) {
                continue;
            }
            if (isProcessAlive(descriptor)) {
                return true;
            }
        }
        return false;
    }

    /** 反射检查 RunContentDescriptor 关联的进程是否存活。 */
    private boolean isProcessAlive(Object descriptor) {
        if (descriptor == null) {
            return false;
        }
        try {
            Object processHandler = cachedMethod(descriptor.getClass(), "getProcessHandler").invoke(descriptor);
            if (processHandler == null) {
                return false;
            }
            Boolean terminated = (Boolean) cachedMethod(processHandler.getClass(), "isProcessTerminated").invoke(processHandler);
            Boolean terminating = (Boolean) cachedMethod(processHandler.getClass(), "isProcessTerminating").invoke(processHandler);
            boolean alive = (terminated == null || !terminated) && (terminating == null || !terminating);
            LOG.debug("isProcessAlive: alive=" + alive + ", descriptor=" + descriptor.getClass().getSimpleName());
            return alive;
        } catch (Throwable t) {
            LOG.debug("isProcessAlive: 反射失败（" + t.getClass().getSimpleName() + "），默认视为存活");
            return true;
        }
    }

    /**
     * 遍历 RunContentManager 全部运行描述符，对每个 console 挂载 filter（幂等）。
     */
    public void scanAll() {
        boolean hasMain = hasMainConsole();
        LOG.debug("scanAll: hasMainConsole=" + hasMain + ", lastHadMainConsole=" + lastHadMainConsole);
        // T-LPC-05：控制台解析的静默失效计数周期上报（零增量零输出）——解析器内部无 IDE 依赖，
        // 上报挂在既有轮询上，排障时能直接看到「参数被丢/等待态拦下/残句闭合」各发生多少次。
        String diagReport = LogParseDiag.takeDeltaReport();
        if (diagReport != null) {
            LOG.info(diagReport);
        }
        // T-ConsolePendingFlush：待闭合条目静默冲刷——应用执行完 SQL 后一行都不再打时，只有这一拍能把
        // 桶里的待产出条目交出去（见 flushSettledEntries KDoc）。增强接管中（paused）不冲——那期间条目
        // 由 agent 通道产出，桥侧冲刷会造成双路重复。
        // T-CaptureCatchAll 券02：活性回落解封期间照常冲刷——此时 agent 通道已判定为空转，桥是唯一产出方。
        if (isCapturing.getAsBoolean() && !SqlCaptureGate.bridgeSuppressed()) {
            flushSettledEntries();
        }
        if (lastHadMainConsole && !hasMain) {
            LOG.info("scanAll: 主控制台已移除（上轮有、当前无），触发 onConsoleRemoved");
            onConsoleRemoved.run();
        }
        if (!lastHadMainConsole && hasMain) {
            LOG.info("scanAll: 主控制台出现，触发 onConsoleAppeared");
            onConsoleAppeared.run();
            if (catchUpTimer != null) {
                LOG.info("scanAll: 检测到控制台，快速轮询 → 常规轮询（2s）");
                startNormalPolling();
            }
        }
        Object rcm = runContentManager;
        if (rcm == null) {
            return;
        }
        Object[] descriptors = descriptorsOf(rcm);
        if (descriptors == null) {
            return;
        }
        // 挂载 filter（幂等）；已挂载的 console 经缓存短路，不再遍历组件树（T-LogPerf-03）
        for (Object descriptor : descriptors) {
            if (descriptor == null) {
                continue;
            }
            Object cachedConsole = descriptorConsoleCache.get(descriptor);
            // T-Perf-22：keys 视图遍历须持 map 监视器——快照后判定
            List<Object> keysSnapshot;
            synchronized (attachedConsoles) {
                keysSnapshot = new java.util.ArrayList<>(attachedConsoles.keySet());
            }
            if (shouldSkipRescan(cachedConsole, keysSnapshot)) {
                continue;
            }
            Component component = componentOf(descriptor);
            if (component == null) {
                continue;
            }
            scanComponent(component);
            descriptorConsoleCache.put(descriptor, SqlLogConsoleBridgeKt.findConsoleView(component));
            if (descriptorConsoleCache.size() > DESCRIPTOR_CACHE_MAX) {
                descriptorConsoleCache.clear();
            }
        }
        lastHadMainConsole = hasMain;
    }

    /** 组件树中查找 ConsoleView 实现并挂载（供 contentAdded 与扫描复用）。 */
    public void scanComponent(Component component) {
        Object console = SqlLogConsoleBridgeKt.findConsoleView(component);
        if (console == null) {
            LOG.debug("scanComponent: no ConsoleView found under " + (component != null ? component.getClass().getName() : "null"));
            return;
        }
        attachFilter(console);
    }

    /**
     * T-LogPerf-04：设置变更事件驱动刷新——[SqlLogManager] 订阅 SETTINGS_CHANGE_TOPIC 后调用，
     * 一次同步全部已挂载上下文的解析器配置与 timePrefix 缓存，取代逐行 configProvider() 同步。
     */
    void refreshConsoleContexts() {
        ParserConfig cfg = parserConfig.get();
        // T-Perf-22：values 遍历持监视器（快照后应用，避免与 Filter 回调并发修改）
        List<BridgeLineContext> snapshot;
        synchronized (consoleContexts) {
            snapshot = new java.util.ArrayList<>(consoleContexts.values());
        }
        for (BridgeLineContext ctx : snapshot) {
            applyConfig(ctx, cfg);
        }
        String raw = PluginSettings.getInstance().getState().getLogTimePrefix();
        timePrefixCache = (raw != null && !raw.isBlank()) ? raw : null;
    }

    /** 把 [ParserConfig] 快照同步进 [BridgeLineContext]（挂载与设置变更共用）。 */
    private void applyConfig(BridgeLineContext ctx, ParserConfig cfg) {
        ctx.myBatisParser.setSqlPrefix(cfg.sqlPrefix());
        ctx.myBatisParser.setParamPrefix(cfg.paramPrefix());
        ctx.myBatisParser.setMixedPrefix(cfg.mixedPrefix());
        ctx.myBatisParser.setMaxParseLength(cfg.maxParseLength());
        ctx.hibernateParser.setSqlPrefix(cfg.hibernateSqlPrefix());
        ctx.hibernateParser.setParamPrefix(cfg.hibernateParamPrefix());
        ctx.hibernateParser.setMixedPrefix(cfg.mixedPrefix());
        ctx.hibernateParser.setMaxParseLength(cfg.maxParseLength());
        ctx.jdbcParser.setEnabled(cfg.jdbcEnabled());
        ctx.jdbcParser.setMaxParseLength(cfg.maxParseLength());
        ctx.hibernateEnabled = cfg.hibernateEnabled();
        ctx.shardingParser.setMaxParseLength(cfg.maxParseLength());
        ctx.shardingEnabled = cfg.shardingEnabled();
    }

    /** T-PS4-02：internal 测试缝——两时序单路化测试直接对桩控制台挂载桥 filter（scanAll 需真实组件树）。 */
    public void attachFilter(Object console) {
        // T-CaptureTruth T-CT-04：**不再**因 epActive 跳过挂载。原挂载面守卫的收益只是省一次代理
        // 分配，代价却是「EP 未覆盖该控制台时该控制台彻底无解析器」——封口改由 handler 逐行短路承担
        // （epActive 置位后桥仍挂载但不再解析，CPU 收益与原先等价），挂载面则始终保底。
        // BUG FIX（停止后仍捕获）：非捕捉态时不挂载 Filter——守卫轮询/事件回调触发的 scanAll。
        if (!isCapturing.getAsBoolean()) {
            LOG.debug("attachFilter: 非捕捉态，跳过挂载 " + console.getClass().getName());
            return;
        }
        if (attachedConsoles.containsKey(console)) {
            LOG.debug("attachFilter: already attached " + console.getClass().getName());
            return;
        }
        for (String filterIfaceName : FILTER_IFACE_CANDIDATES) {
            try {
                Class<?> filterIface = Class.forName(filterIfaceName, true, platformClassLoader);
                // T-PS5-09：此处原有一组「三解析器 + hibernateEnabled」首建即弃（下方 cfg0 组才是
                // 真正进 BridgeLineContext 的那组，配置源同为 parserConfig()）——8 月既有死分配，
                // 纯删码零语义变化（连带的第二次 parserConfig() 也一并省掉）。
                // 每个 console 持有独立解析器 + 暂存源码位置（避免多控制台相互干扰，F-44-6）
                ParserConfig cfg0 = parserConfig.get();
                BridgeLineContext ctx = new BridgeLineContext(
                        new MyBatisLogParser(new LogSqlRestorer(), cfg0.sqlPrefix(), cfg0.paramPrefix(),
                                cfg0.mixedPrefix(), cfg0.maxParseLength()),
                        new HibernateLogParser(new LogSqlRestorer(), cfg0.hibernateSqlPrefix(), cfg0.hibernateParamPrefix(),
                                cfg0.mixedPrefix(), cfg0.maxParseLength(), true),
                        new JdbcTemplateLogParser(new LogSqlRestorer(), cfg0.jdbcEnabled(), cfg0.maxParseLength()),
                        new ShardingSphereLogParser(cfg0.maxParseLength()),
                        parserConfig,
                        cfg0.hibernateEnabled(),
                        cfg0.shardingEnabled());
                consoleContexts.put(console, ctx);
                // T-PreviewFlash：反射缓存 console.getEditor()（ConsoleView.getEditor 为 public）。
                // 取不到则守卫降级停用（editor 为 null → decide 恒 Process＝现状语义），绝不阻塞捕获。
                attachReplayGuard(console, ctx);
                // T-LogPerf-04：timePrefix 缓存化——设置变更走 refreshConsoleContexts，逐行不再读 PluginSettings
                String rawTimePrefix = PluginSettings.getInstance().getState().getLogTimePrefix();
                timePrefixCache = (rawTimePrefix != null && !rawTimePrefix.isBlank()) ? rawTimePrefix : null;
                // T-ConsolePendingFlush：代际号写入上下文——旧代理凭它识别自身已作废（见 handleConsoleLine）
                long generation = filterGeneration.incrementAndGet();
                ctx.generation = generation;
                Object filter = Proxy.newProxyInstance(
                        filterIface.getClassLoader(),
                        new Class<?>[]{filterIface},
                        new LogFilterProxyHandler((line, endOffset) ->
                                handleConsoleLine(console, generation, line, endOffset)));
                console.getClass().getMethod("addMessageFilter", filterIface).invoke(console, filter);
                attachedConsoles.put(console, new Pair<>(filter, filterIface));
                LOG.info("attachFilter: filter(" + filterIfaceName + ") attached to " + console.getClass().getName());
                return;
            } catch (Throwable t) {
                LOG.debug("attachFilter: candidate " + filterIfaceName + " failed on " + console.getClass().getName(), t);
            }
        }
        LOG.warn("attachFilter: all filter candidates failed on " + console.getClass().getName());
    }

    /**
     * T-CaptureTruth T-CT-03 / T-PS4-02 / T-PreviewFlash / T-ConsolePendingFlush：桥 filter 的逐行处理体。
     *
     * <p>代际守卫（T-ConsolePendingFlush）：{@code generation} 是本代理挂载时的代际号，
     * {@code BridgeLineContext.generation} 是当前有效代际。2026.2 起 {@code ConsoleViewImpl} 只提供
     * {@code clearMessageFilters()}、<b>没有</b> {@code removeMessageFilter}（javap 实证），stop→start 会在
     * 同一个 console 上留下摘不掉的旧代理；旧代理若继续按 console 键取到<b>新</b>上下文，就会与新代理
     * 逐行重复解析、重复计数。代际不符即惰性退出（不解析、不计数）。
     *
     * <p>并发（T-ConsolePendingFlush）：本方法在控制台输出线程执行，而轮询侧 {@link #flushSettledEntries}
     * 在 EDT 冲刷同一套解析器状态 → 以 ctx 为锁互斥（同线程重入无副作用；无 Swing 调用，不构成 EDT 死锁面）。
     */
    private void handleConsoleLine(Object console, long generation, String line, int endOffset) {
        BridgeLineContext context = consoleContexts.get(console);
        if (context == null) {
            return;
        }
        if (context.generation != generation) {
            return; // 旧代际代理：惰性退出
        }
        // T-CaptureTruth T-CT-03：控制台观测行计数（短路行也计——「桥看到过多少行」是面板零产出诊断的输入）
        consoleLinesSeen.incrementAndGet();
        synchronized (context) {
            // T-PS4-02 双路解析封口 / T-CaptureTruth T-CT-04 语义收窄：平台 filter 链中 EP
            // （consoleFilterProvider）先于后挂的桥 filter 执行，且 epActive 只在 EP **实证
            // 产出过条目**后才置位——置位即意味着「EP 在本会话确实在链上且确实能产出」，桥侧
            // 短路重复解析（条目由 EP 单路产出）。EP 不在链上/产不出条目时 epActive 恒 false，
            // 桥照常解析：这正是回落通道的保底（原口径「EP 被调用过就封口」会让未覆盖的控制台
            // 静默零捕获）。防误伤可观测：计数 + debug 日志（计数即测试探针/丢行排查锚点）。
            if (SqlCaptureGate.epActive) {
                long total = epActiveShortCircuitCount.incrementAndGet();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("bridge filter: EP 已生效，短路桥侧重复解析（累计 " + total + " 行）");
                }
                return;
            }
            // T-PS5-07：此处原有第二道 `if (SqlCaptureGate.epActive) return` ——不可达冗余
            // （同条件已在处理函数首行短路并计数/留痕），删除。
            // T-PreviewFlash：重放守卫——rehighlight 全文档重跑 filter 时丢弃历史行（平台契约要求幂等）。
            // 守卫依赖反射取到的编辑器文档（stamp/行0）；取不到（非真 ConsoleViewImpl，如测试桩）
            // 则降级停用，直连现状语义——绝不能因守卫误丢正常流式行。
            Supplier<Long> stampReader = context.docStampReader;
            if (stampReader != null) {
                if (context.replayGuard.decide(
                        endOffset,
                        stampReader.get(),
                        line,
                        // T-PerfSweep F1：行 0 提供者惰性传递——只有混合态分支才求值，
                        // 流式/纯重放路径不再付 O(文档大小) 的全文档拷贝
                        context.line0Reader) == ConsoleReplayGuard.Decision.Replay) {
                    context.replayDropCount.incrementAndGet();
                } else {
                    emitEntries(SqlLogConsoleBridgeKt.processConsoleLine(
                            line, context, timePrefixCache, syncBufferConfig, this::bindConsoleError));
                }
            } else {
                emitEntries(SqlLogConsoleBridgeKt.processConsoleLine(
                        line, context, timePrefixCache, syncBufferConfig, this::bindConsoleError));
            }
        }
    }

    /** 条目入队（桥的两条产出路径共用：逐行与轮询静默冲刷）。 */
    private void emitEntries(List<LogSqlEntry> entries) {
        for (LogSqlEntry entry : entries) {
            // 桥通道打标（EP 通道同款打标在 SqlConsoleBridgeFilterProvider）——同桶融合与归因诊断依赖。
            buffer.addEntry(entry.withCaptureProvenance(CaptureProvenance.CONSOLE_BRIDGE));
        }
    }

    /**
     * T-ConsolePendingFlush：静默冲刷全部已挂载上下文的待产出条目（EDT，随轮询每拍调用一次，见 scanAll）。
     *
     * <p>为什么需要轮询这一拍：processConsoleLine 的逐行冲刷只在「控制台还在出下一行」时被触发；应用执行完
     * 最后一条 SQL 就安静下来（没人再打日志）时，那条语句的待闭合状态没有后继行来触发任何路径——只有定时器
     * 能兜住。没有它，低频工程仍然表现为「已扫描 N 行 / 产出 0 条」。
     *
     * @return 本次真正入队的条目数（测试断言用；生产路径只用于 INFO 留痕）。
     */
    public int flushSettledEntries() {
        return flushSettledEntries(System.currentTimeMillis());
    }

    /** {@link #flushSettledEntries()} 的显式时间形态（默认实参 nowMs = 当前时刻的等价重载）。 */
    public int flushSettledEntries(long nowMs) {
        int produced = 0;
        List<BridgeLineContext> snapshot;
        synchronized (consoleContexts) {
            snapshot = new java.util.ArrayList<>(consoleContexts.values());
        }
        for (BridgeLineContext ctx : snapshot) {
            synchronized (ctx) {
                ctx.jdbcParser.flushSettled(nowMs);
                for (LogSqlEntry entry : ctx.jdbcParser.takeEntries()) {
                    if (buffer.addEntry(entry)) {
                        produced++;
                    }
                }
                // R10：ShardingSphere 同款静默兜底（条目只在下一条 Logic 行闭合，最后一语句无后继行）
                if (ctx.shardingEnabled) {
                    ctx.shardingParser.flushSettled(nowMs);
                    for (LogSqlEntry entry : ctx.shardingParser.takeEntries()) {
                        if (buffer.addEntry(entry)) {
                            produced++;
                        }
                    }
                }
            }
        }
        if (produced > 0) {
            LOG.info("flushSettledEntries: 静默冲刷待闭合条目 " + produced + " 条（语句行之后同线程再无后继行——"
                    + "JdbcTemplate 无 TRACE 参数行 / 线程按请求新建；阈值 "
                    + JdbcTemplateLogParser.DEFAULT_SETTLE_MS + "ms）");
        }
        return produced;
    }

    /**
     * T-M55-02：控制台错误文本反向绑定缓冲最近条目——绑定失败（条目被去重丢弃后缺位/超窗淘汰/
     * 缓冲为空）显式留 debug 痕，不阻塞主链（生产路径 bindErrorToRecent 的丢弃分支不可静默无痕）。
     */
    private void bindConsoleError(String errorText) {
        SqlLogEntryBuffer.ErrorBindResult result = buffer.bindErrorToRecent(errorText);
        if (result != SqlLogEntryBuffer.ErrorBindResult.BOUND) {
            // errorText.lineSequence().firstOrNull()?.take(120) 等价：取首行前 120 字符
            String firstLine = errorText;
            int nl = firstLine.indexOf('\n');
            if (nl >= 0) firstLine = firstLine.substring(0, nl);
            if (firstLine.length() > 120) firstLine = firstLine.substring(0, 120);
            LOG.debug("bindConsoleError: 错误块绑定丢弃（" + result + "）：" + firstLine);
        }
    }

    /**
     * T-PreviewFlash：为重放守卫解析编辑器/文档反射句柄。全部 try/catch 包裹——
     * 任一环失败即守卫降级停用（editor null），行为等同现状语义，绝不阻塞 Filter 挂载。
     * {@code getEditor} 实现类缺失该方法时 docStamp/line0 读取器为 null，守卫 decide 恒 Process。
     * T-PerfSweep F1：反射 Method 在挂载时解析一次（此前逐行 getMethod）；行 0 文本按
     * stamp 失效缓存——重放风暴中同 stamp 连续行只付一次首行读取（缓存为
     * 尽力而为：Filter 回调线程间可见性无同步，最坏退化为多算一次，无正确性影响）。
     * T-PS4-06：行 0 读取改 {@code getCharsSequence} 首行区间读（[0, 首个行分隔)）——Document
     * charsSequence 是免拷贝视图，行区间 toString 只拷贝首行；此前 {@code getText} 全文档拷贝
     * （O(全史)）在 stamp 变化后的首个混合态行仍要付一次，调用面归零（doc 反射句柄中
     * 不再解析 getText——桩文档缺失该方法时守卫照常工作）。
     * T-PS4-06：internal 测试缝——line0 区间读测试直接对反射桩调用（经 attachFilter 须真实组件树）。
     */
    public void attachReplayGuard(Object console, BridgeLineContext ctx) {
        try {
            java.lang.reflect.Method getEditor = console.getClass().getMethod("getEditor");
            Object editor = getEditor.invoke(console);
            if (editor == null) {
                return;
            }
            java.lang.reflect.Method getDoc = editor.getClass().getMethod("getDocument");
            final Object doc = getDoc.invoke(editor);
            if (doc == null) {
                return;
            }
            final java.lang.reflect.Method getStamp = doc.getClass().getMethod("getModificationStamp");
            // T-PS4-06：首行区间读的免拷贝视图入口（Document.getCharsSequence 为平台 Document 接口方法）
            final java.lang.reflect.Method getCharsSequence = doc.getClass().getMethod("getCharsSequence");
            ctx.docStampReader = () -> {
                try {
                    Object stamp = getStamp.invoke(doc);
                    return stamp instanceof Number n ? n.longValue() : Long.MIN_VALUE;
                } catch (Throwable t) {
                    return Long.MIN_VALUE;
                }
            };
            final long[] cachedStamp = {Long.MIN_VALUE};
            final String[] cachedLine0 = {null};
            ctx.line0Reader = () -> {
                try {
                    Object stampObj = getStamp.invoke(doc);
                    long stamp = stampObj instanceof Number n ? n.longValue() : Long.MIN_VALUE;
                    if (stamp != cachedStamp[0]) {
                        Object charsObj = getCharsSequence.invoke(doc);
                        CharSequence chars = (charsObj instanceof CharSequence cs) ? cs : null;
                        cachedLine0[0] = chars != null ? readFirstLineOf(chars) : null;
                        cachedStamp[0] = stamp;
                    }
                    return cachedLine0[0];
                } catch (Throwable t) {
                    return null;
                }
            };
        } catch (Throwable t) {
            // 守卫降级：句柄不可得时 docStampReader 保持 null → decide 恒 Process（现状语义）
        }
    }

    /**
     * T-PS4-06：charsSequence 首行区间读（[0, 首个行分隔)）——逐字复刻既有
     * {@code getText().lineSequence().firstOrNull()} 语义防行为漂移：
     * 行分隔按 lineSequence 口径认 {@code \n} 与 {@code \r}（{@code \r\n}/{@code \r} 同为分隔）；无行分隔取全文
     * （单行文档场景）；空文档为 null。行 0 与守卫 lineText（平台 getLineEndOffset 侧，
     * 不含分隔符）同源同口径，混合态文本比对不失配。
     */
    private static String readFirstLineOf(CharSequence chars) {
        if (chars.length() == 0) return null;
        int nl = indexOfChar(chars, '\n');
        int cr = indexOfChar(chars, '\r');
        int end;
        if (nl < 0) {
            end = cr;
        } else if (cr < 0) {
            end = nl;
        } else {
            end = Math.min(nl, cr);
        }
        return end < 0 ? chars.toString() : chars.subSequence(0, end).toString();
    }

    private static int indexOfChar(CharSequence chars, char target) {
        for (int i = 0; i < chars.length(); i++) {
            if (chars.charAt(i) == target) return i;
        }
        return -1;
    }

    /**
     * BUG FIX（停止后仍捕获）：移除所有已挂载控制台上的 Filter。
     */
    public void detachAllFilters() {
        // T-Perf-22：entries 遍历持监视器快照（迭代体内 remove consoleContexts，不能在遍历
        // 同一 map 时持锁直改——快照后操作）
        List<Map.Entry<Object, Pair<Object, Class<?>>>> entriesSnapshot;
        synchronized (attachedConsoles) {
            entriesSnapshot = new java.util.ArrayList<>(attachedConsoles.entrySet());
        }
        for (Map.Entry<Object, Pair<Object, Class<?>>> e : entriesSnapshot) {
            Object console = e.getKey();
            consoleContexts.remove(console);
            Pair<Object, Class<?>> filterPair = e.getValue();
            Object filter = filterPair.getFirst();
            Class<?> filterIface = filterPair.getSecond();
            boolean removed = false;
            try {
                console.getClass().getMethod("removeMessageFilter", filterIface).invoke(console, filter);
                removed = true;
                LOG.info("detachAllFilters: filter removed from " + console.getClass().getName());
            } catch (Throwable t) {
                for (String candidateName : FILTER_IFACE_CANDIDATES) {
                    try {
                        Class<?> candidate = Class.forName(candidateName, true, platformClassLoader);
                        console.getClass().getMethod("removeMessageFilter", candidate).invoke(console, filter);
                        removed = true;
                        LOG.info("detachAllFilters: filter removed via fallback " + candidateName +
                                " from " + console.getClass().getName());
                        break;
                    } catch (Throwable ignored) {
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
                LOG.info("detachAllFilters: 平台不支持单 filter 移除（" + console.getClass().getSimpleName()
                        + "）——旧 filter 已代际失活");
            }
        }
        attachedConsoles.clear();
        // T-LogPerf-03：挂载记录清空后缓存全部失效，下一轮重新全量扫描
        descriptorConsoleCache.clear();
    }

    /**
     * BUG FIX（停止后仍捕获）：移除 contentManagerListener（243/253 平台）。
     */
    public void detachContentListener() {
        if (!contentListenerRegistered) {
            return;
        }
        Object rcm = runContentManager;
        if (rcm == null) {
            return;
        }
        try {
            rcm.getClass().getMethod("removeContentManagerListener", CONTENT_LISTENER_CLASS).invoke(rcm, contentListener);
            LOG.info("detachContentListener: contentManagerListener removed");
        } catch (Throwable t) {
            LOG.debug("detachContentListener: removeContentManagerListener failed", t);
        }
        contentListenerRegistered = false;
    }

    private static final Logger LOG = Logger.getInstance(SqlLogConsoleBridge.class);

    /** 双包名候选：2024.3/2025.3 在 execution 包，2026.2 移到 execution.ui 包（实证）。 */
    private static final List<String> RCM_CLASS_CANDIDATES = List.of(
            "com.intellij.execution.RunContentManager",
            "com.intellij.execution.ui.RunContentManager");
    /** Filter 接口双候选：243/253 为 ConsoleView 嵌套接口；2026.2 移除嵌套接口，addMessageFilter 参数改为独立接口。 */
    private static final List<String> FILTER_IFACE_CANDIDATES = List.of(
            "com.intellij.execution.ui.ConsoleView$Filter",
            "com.intellij.execution.filters.Filter");
    private static final Class<?> CONTENT_LISTENER_CLASS = ContentManagerListener.class;
    private static final int POLL_INTERVAL_MS = 2000; // 常规轮询间隔
    private static final int FAST_POLL_INTERVAL_MS = 500; // 快速轮询起步间隔——T-LogPerf-01：指数退避至常规间隔，不再 100ms 常驻
    private static final int DESCRIPTOR_CACHE_MAX = 64; // descriptor→console 缓存上限（超限整体失效防死引用滞留）

    /** 平台 classloader（execution API 在此 classloader 中，不在插件 classloader 中）。 */
    private static final ClassLoader platformClassLoader =
            com.intellij.openapi.project.ProjectManager.class.getClassLoader();

    /** 执行体系 API 在当前环境是否可用（测试环境/SDK 发行版为 false）。 */
    public static boolean isConsoleApiAvailable() {
        for (String it : RCM_CLASS_CANDIDATES) {
            try {
                Class.forName(it, true, platformClassLoader);
                return true;
            } catch (Throwable t) {
                // continue
            }
        }
        return false;
    }

    /** T-LogPerf-01：快速轮询指数退避（纯函数便于单测）——无主控制台每拍翻倍，上限=常规间隔；重置回落起步间隔。 */
    static int nextFastDelay(int currentMs, boolean backoff) {
        if (!backoff) {
            return FAST_POLL_INTERVAL_MS;
        }
        return Math.min(currentMs * 2, POLL_INTERVAL_MS);
    }

    /** T-LogPerf-03：短路判定（纯函数便于单测）——已知 console 且已挂载时跳过重新扫描。 */
    static boolean shouldSkipRescan(Object cachedConsole, Collection<?> attachedConsoles) {
        if (cachedConsole == null) {
            return false;
        }
        for (Object c : attachedConsoles) {
            if (c == cachedConsole) {
                return true;
            }
        }
        return false;
    }

    /**
     * T-LogPerf-02：反射 Method 缓存（热路径每拍轮询不再重复 getMethod 查找；找不到也缓存）。
     *
     * <p>F50（T-PerfQuick5）两处修正：
     *  - <b>值改 {@link java.util.Optional}</b>——CHM 禁 null：原值类型 {@code Method} 在负缓存路径
     *    {@code methodCache[key] = null} 运行时 NPE（被 descriptorsOf/componentOf 的
     *    catch(Throwable) 吞掉——负缓存从未生效，每拍重付 getMethod+NPE 构造成本）；
     *  - <b>键含 ClassLoader 身份</b>——同签名类名跨 classloader（平台与插件遮蔽同名类/
     *    动态卸载后的新 loader）不是同一个类，裸类名记忆化会跨 loader 撞键。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Method>> methodCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** F50：记忆化键（纯函数可测）——类名 + ClassLoader 身份 + 方法名三元组。 */
    public static String methodCacheKey(String className, ClassLoader loader, String name) {
        return className + "@" + System.identityHashCode(loader) + "#" + name;
    }

    public static java.lang.reflect.Method cachedMethod(Class<?> clazz, String name) {
        String key = methodCacheKey(clazz.getName(), clazz.getClassLoader(), name);
        java.util.Optional<java.lang.reflect.Method> cached = methodCache.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        java.lang.reflect.Method m;
        try {
            m = clazz.getMethod(name);
        } catch (Throwable t) {
            m = null;
        }
        methodCache.put(key, java.util.Optional.ofNullable(m));
        return m;
    }

    private static Object[] descriptorsOf(Object rcm) {
        try {
            Object raw = cachedMethod(rcm.getClass(), "getAllDescriptors").invoke(rcm);
            if (raw instanceof Object[]) {
                return (Object[]) raw;
            }
            if (raw instanceof Collection) {
                return ((Collection<?>) raw).toArray();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Component componentOf(Object descriptor) {
        if (descriptor == null) {
            return null;
        }
        try {
            Object c = cachedMethod(descriptor.getClass(), "getComponent").invoke(descriptor);
            if (c instanceof Component) {
                return (Component) c;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object runContentManagerOrNull(Project project) {
        LOG.info("runContentManagerOrNull: 尝试解析 RCM（candidates=" + RCM_CLASS_CANDIDATES +
                ", cl=" + platformClassLoader.getClass().getSimpleName() + "）");
        for (String className : RCM_CLASS_CANDIDATES) {
            try {
                Class<?> clazz = Class.forName(className, true, platformClassLoader);
                LOG.info("runContentManagerOrNull: " + className + " 已加载，调用 getInstance");
                Object rcm = clazz.getMethod("getInstance", Project.class).invoke(null, project);
                if (rcm != null) {
                    LOG.info("attach: RCM resolved via " + className);
                    return rcm;
                }
                LOG.warn("runContentManagerOrNull: " + className + ".getInstance 返回 null");
            } catch (Throwable t) {
                LOG.warn("runContentManagerOrNull: " + className + " 不可用（" +
                        t.getClass().getSimpleName() + ": " + t.getMessage() + "）");
            }
        }
        return null;
    }
}
