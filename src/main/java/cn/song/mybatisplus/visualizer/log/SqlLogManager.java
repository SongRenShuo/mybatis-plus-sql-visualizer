package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener;
import cn.song.mybatisplus.visualizer.log.agent.AgentManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
// T-AutoShowMaster：wm.ex 包的 ToolWindowManagerListener——非 ex 包在本机裁剪发行版中不存在
// （历轮发行实证），两参 stateChanged 是公开 API，三参重载为 @Experimental 会触发 Marketplace 违规
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import kotlin.Pair;

/**
 * 日志 SQL 条目管理（T-M31-03）：project 级轻服务——平台壳。
 *
 * 职责：持有纯逻辑缓冲 [SqlLogEntryBuffer]（条目/暂停/去重/过滤，可纯 JUnit 测试），
 * 经 [SqlLogConsoleBridge] 反射挂载运行控制台（execution API 不在编译 classpath，见桥 KDoc），
 * 并把缓冲变更 [notifyUi] 调度到 EDT 供面板刷新。
 *
 * T-M31.14：配置装配——parser 前缀/混合/最大长度、去重窗口、过滤关键字均来自 [PluginSettings]。
 *
 * F-44：不再自动打开独立窗口（SQL 日志已合并到主侧边栏 tab 中）；实时捕获仍运行，
 * 驱动 F-43 自动保存 + 主侧边栏 Tab 0 实时展示。
 */
@Service(Service.Level.PROJECT)
public final class SqlLogManager implements Disposable {

    private final SqlLogEntryBuffer buffer = new SqlLogEntryBuffer(
            PluginSettings.getInstance().getState().getLogMaxCount());
    private SqlLogConsoleBridge bridge = new SqlLogConsoleBridge(
            buffer, this::parserConfig, this::applySettings, this::onConsoleAppeared, this::isCapturing);
    private final CopyOnWriteArrayList<Runnable> uiListeners = new CopyOnWriteArrayList<>();

    /** 自动保存历史 SQL（F-43）：buffer 淘汰回调的写入目标。 */
    private final SqlLogAutoSaver autoSaver = new SqlLogAutoSaver();

    /** 缓冲变更 → 面板刷新（EDT 调度）；仅面板订阅时登记（命名引用——可被 removeListener 精确移除）。 */
    private final Runnable uiNotifyListener = this::notifyUi;

    /** T-SqlSuppress（O-69）：应用级屏蔽规则管理器（APP 级单例，与 PluginSettings 同获取形态）。 */
    private final SqlSuppressRuleManager suppressRuleManager = SqlSuppressRuleManager.getInstance();

    /**
     * T-SqlSuppress：规则变更 → 重算缓冲屏蔽键集 + 显式触发存量刷新。
     *
     * <p>订阅挂本服务生命周期（{@link #dispose()} 注销）——规则是 APP 级、缓冲键集是项目级：两个项目
     * 窗口各持独立 {@link SqlLogManager}，应用级监听器列表逐项目各记一条，改规则时每个窗口的
     * 实例都会收到通知并各自同步（多项目并存的保证在结构上：注册即监听、close 即注销）。
     * notifyUi 必要性：面板差分刷新只认缓冲快照变化，规则变更对它不可感知——必须显式触发。
     */
    private final Runnable suppressRuleListener = () -> {
        buffer.setSuppressKeys(suppressRuleManager.getSuppressedKeys());
        buffer.setHasStructureKeys(suppressRuleManager.getSuppressedKeysContainStructure());
        notifyUi();
    };

    /**
     * T-RecentExecMenu O-119：面板外「把条目送进工具窗页签」通道（弹框右键「发送到 SQL → Java /
     * 日志转 SQL」用）。两块页签面板（{@code sqlToJavaPanel} / {@code logToSqlPanel}）是 {@link SqlLogPanel} 的私有
     * 字段，面板外不可达——回调形态照抄 {@link #onAutoShowRequest}（面板 init 注册、dispose 注销；
     * 未注册 = 工具窗未创建，调用方按「未就绪」轻提示降级，不静默）。
     */
    volatile BiConsumer<LogSqlEntry, LogEntryTab> onOpenEntryInTab = null;

    private final Project project;

    /**
     * 捕捉状态（默认关——用户点击「开始捕捉」后才挂载监听，对齐 MybatisCodeHelper-Pro 手动监听）。
     *
     * <p><b>不变量（T-AgentConfigChannel T-04）</b>：{@code capturing == false} ⇒ {@code buffer.paused == true}。
     * 三条关闭路径都必须同时满足：手动 {@link #stopCapture()}、项目停止的自动分支（{@link #onConsoleRemoved()}
     * 的 else 支）、项目关闭（{@link #dispose()}）。此前后两条只置 {@code capturing=false}、不置 {@code paused}——
     * 于是出现「按钮 ▶（未捕获）但缓冲区仍收条目」这唯一一种自相矛盾的状态：控制台 Filter 已被
     * 摘掉不产条目，而 agent 通道（当时无入队闸门）照旧投递，面板照旧显示。
     * 反向（{@code paused == true} 而 {@code capturing == true}）不存在——{@link #startCapture()}
     * 进入捕捉态前先清 paused。
     */
    private volatile boolean capturing;

    /** M56 T-M56-09：EP 双轨单测缝——直设捕捉态（生产路径经 startCapture 状态机）。 */
    public void setCapturingForTest(boolean value) {
        capturing = value;
    }

    /**
     * T-TestIsolation：捕获态三元（capturing / userArmed / 等待态）<b>原样复位</b>测试缝。
     *
     * <p>为什么需要专用缝：{@link #userArmed} 与 {@link #isWaitingForConsole} 只有 {@code private set}，
     * 测试类经公开路径（startCapture/stopCapture）改不到初值；而 light 测试项目在<b>同一分片
     * JVM 内跨测试类复用同一个 ProjectImpl</b>，{@code project.getService(SqlLogManager::class.java)}
     * 拿到的是全片共享实例——任何测试类改捕获态而不复位都会串台到同片后续测试类
     * （是否发作取决于分片时长权重算出的邻居，见 testutil/SqlLogManagerProjectState KDoc）。
     */
    public void restoreCaptureStateForTest(boolean capturing, boolean userArmed, boolean waitingForConsole) {
        this.capturing = capturing;
        this.userArmed = userArmed;
        this.isWaitingForConsole = waitingForConsole;
    }

    /** 用户主动开启 SQL 监听标志——点击「捕捉」后置 true，点击「停止」或 dispose 时清零。 */
    private volatile boolean userArmed;

    /** 是否处于「等待主控制台出现」中间态（测试断言）。 */
    private volatile boolean isWaitingForConsole;

    /**
     * 进程级启动监听是否订阅成功（20260907-2045 第四轮）。
     * true=被动模式：「项目启动时自动开始监听」开启后不预 armed——设置开启/项目打开时按钮保持
     * 「捕捉」原状，Run 项目那一刻由 onProjectProcessStarted 驱动捕捉（用户语义：设置本身
     * 不等于"监听已开启"）；false=降级模式（execution API 缺失）：维持原 armed 行为
     * （RCM 轮询是唯一检测通道）。
     */
    private volatile boolean processListenerActive = false;

    /** 进程级启动监听是否订阅成功（被动模式判定，LogStartupCapture 读）。 */
    public boolean isProcessListenerActive() {
        return processListenerActive;
    }

    /** 测试缝（同包）：注入订阅状态模拟被动/降级模式（对齐 Kotlin 侧 internal set 的测试可见性）。 */
    void setProcessListenerActiveForTest(boolean active) {
        this.processListenerActive = active;
    }

    /** capturing 变化回调（面板注册 → 刷新工具栏按钮图标/文案）。 */
    private final CopyOnWriteArrayList<Runnable> capturingListeners = new CopyOnWriteArrayList<>();

    /** M37.3：上次自动展开时间戳（防抖基准；测试缝——master 为 internal，跨包 testutil 须 public）。 */
    public volatile long lastAutoShowAt = 0L;

    /** T-AutoShowSuppress：用户手动关闭日志工具窗后置位——本会话内不再自动展开。
     *  恢复途径：设置开关重新开启（显式表达诉求）或重启 IDE/重开项目（会话自然重置）。 */
    public volatile boolean autoShowSuppressedByUserClose = false;

    /** T-AutoShowSuppress：日志窗可见性沿检测基准（只在 可见→不可见 沿认定手动关闭；
     *  初值 false——启动布局恢复等中间态不误判）。 */
    public volatile boolean logWindowWasVisible = false;

    /** T-AutoShowSuppress：设置开关上次取值——检测 false→true 重启用沿，清除会话抑制。 */
    volatile boolean lastSeenAutoOpenOnCapture = PluginSettings.getInstance().getState().getLogAutoOpenOnCapture();

    /** M37.3：自动展开请求处理器（测试缝——默认查 ToolWindowManager 并 show，不 activate）。 */
    volatile Runnable onAutoShowRequest = this::defaultAutoShowRequest;

    private void defaultAutoShowRequest() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
            if (tw == null) return;
            if (!tw.isVisible()) tw.show(); // show 不 activate——不抢编辑器焦点
        });
    }

    private final Runnable autoShowBufferListener = this::maybeAutoShow;

    public SqlLogManager(Project project) {
        this.project = project;
        // T-SqlSuppress：初始同步一次屏蔽键集 + 订阅规则变更（dispose 注销）；
        // 命中计数归账同挂专用出口（被拦条目不触发其他任何回调）。
        buffer.setSuppressKeys(suppressRuleManager.getSuppressedKeys());
        buffer.setHasStructureKeys(suppressRuleManager.getSuppressedKeysContainStructure());
        suppressRuleManager.addListener(suppressRuleListener);
        buffer.setOnSuppressedHit(suppressRuleManager::recordHit);
        this.bridge.onConsoleRemoved = this::onConsoleRemoved;
        // M37.3：恢复 logAutoOpenOnCapture 行为——F-44 移除独立窗口时一并丢了解接线（设置项
        // 与设置页勾选保留至今，形同虚设）。挂**无条件**回调（面板未创建/未订阅时
        // 首条捕获也触发，修 D2 老问题语义），目标从独立窗口改为主工具窗 show（不抢焦点）。
        // T-PerfBatch：自动展开挂 onEntryAccepted（每次成功入队即时回调，与列表内容节流解耦——
        // 既有语义「每条都判 maybeAutoShow、60s 防抖收敛」不变）。
        // T-UxOnboard T-UX-03：首次捕获成功指路同挂本出口（会话级一次性、历史非空跳过、
        // headless 静默）——零新增监听链，headless 下决策即短路。
        buffer.setOnEntryAccepted(() -> {
            autoShowBufferListener.run();
            FirstCaptureGuide.onEntryAccepted(project, buffer);
        });
        // T-LogPerf-04：设置变更事件驱动——取代逐行 configProvider/syncBufferConfig 同步
        // （捕捉中改前缀/去重/过滤仍即时生效）；连接绑定服务 Disposable，dispose 自动注销
        project.getMessageBus().connect(this).subscribe(
                PluginSettings.SETTINGS_CHANGE_TOPIC,
                (SettingsChangeListener) () -> {
                    applySettings();
                    bridge.refreshConsoleContexts();
                    // T-AutoShowSuppress：设置开关 false→true 重启用沿——用户显式重新表达诉求，解除会话抑制
                    boolean autoOpen = PluginSettings.getInstance().getState().getLogAutoOpenOnCapture();
                    if (autoOpen && !lastSeenAutoOpenOnCapture && autoShowSuppressedByUserClose) {
                        autoShowSuppressedByUserClose = false;
                        LOG.info("[T-AutoShowSuppress] logAutoOpenOnCapture 重新开启——解除本会话抑制");
                    }
                    lastSeenAutoOpenOnCapture = autoOpen;
                });

        // T-AutoShowSuppress：日志窗手动关闭检测——两参 stateChanged（公开 API；三参重载为
        // @Experimental+@Internal 会触发 Marketplace 违规，且新 UI 下 ContentManagerListener
        // 的 contentRemoved 不触发，见全局 pitfall-toolwindow-manager-listener）。任何 ToolWindow
        // 状态变化都会回调，内部过滤目标窗口并只沿「可见→不可见」转换沿置抑制。
        project.getMessageBus().connect(this).subscribe(
                ToolWindowManagerListener.TOPIC,
                new ToolWindowManagerListener() {
                    @Override
                    public void stateChanged(ToolWindowManager toolWindowManager) {
                        if (project.isDisposed()) return;
                        ToolWindow tw = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);
                        if (tw == null) return;
                        boolean visible = tw.isVisible();
                        if (isUserCloseTransition(logWindowWasVisible, visible)) {
                            autoShowSuppressedByUserClose = true;
                            LOG.info("[T-AutoShowSuppress] 日志工具窗被关闭——本会话内不再自动展开");
                        }
                        logWindowWasVisible = visible;
                    }
                });

        // 20260907-2045：运行配置进程启动监听——autoStart=true 时项目 Run 即自动捕捉
        // （清 suppressed + startCapture，见 onProjectProcessStarted）；execution API 缺失时
        // 内部降级为 false，RCM 轮询兜底仍在
        processListenerActive = RunConfigurationProcessListener.subscribe(project, this);
    }

    /**
     * T-LogRowNumber：捕获序号归零（项目启动时调用，列表行号从 #1 重新计数）。
     * 由 RunConfigurationProcessListener processStarted 分支<b>无条件</b>调用——
     * 不接进 onProjectProcessStarted（其 {@code !autoStart} 守卫会跳过手动捕捉模式的清零，
     * 违背「每次启动项目时清 0」的无条件语义）。
     */
    public void resetEntrySequence() {
        buffer.resetEntrySequence();
        LOG.info("resetEntrySequence: 项目启动——捕获序号归零（列表行号重新从 #1 计数，project=" + project.getName() + "）");
    }

    /** 首条 SQL 自动展开（logAutoOpenOnCapture 开启、未被用户关闭抑制、且距上次展开超过防抖窗口）。 */
    private void maybeAutoShow() {
        long now = System.currentTimeMillis();
        if (!shouldAutoShow(
                PluginSettings.getInstance().getState().getLogAutoOpenOnCapture(),
                now - lastAutoShowAt,
                autoShowSuppressedByUserClose)) {
            return;
        }
        lastAutoShowAt = now;
        LOG.info("[M37.3] 首条 SQL 自动展开日志工具窗（logAutoOpenOnCapture=true）");
        onAutoShowRequest.run();
    }

    /** T-xx：内部测试缝——注入受控桥（attach 恒成功/恒失败）验证 capturing 状态机；生产路径不调用。 */
    void installBridgeForTest(SqlLogConsoleBridge bridge) {
        this.bridge = bridge;
    }

    /** 仅挂载桥+轮询（不进入 armed 态）——suppressed=true 时检测项目启动用。 */
    public void startListeningOnly() {
        if (bridge.attach(project)) {
            bridge.startPolling();
            LOG.info("startListeningOnly: 桥挂载+轮询已启动（suppressed 模式，不进入 armed 态）");
        }
    }

    public void startCapture() {
        startCapture(false);
    }

    /**
     * 开始捕捉：挂载控制台监听 + 轮询兜底（幂等）。
     * 自动启动路径守卫：项目尚无运行中的主控制台时不进入捕捉态。
     */
    public void startCapture(boolean manual) {
        LOG.info("startCapture: 调用开始（capturing=" + capturing + ", waiting=" + isWaitingForConsole +
                ", manual=" + manual + ", project=" + project.getName() + "）");
        if (capturing && !isWaitingForConsole) {
            return; // 已在捕捉态（非等待）则幂等
        }
        if (manual) {
            PluginSettings.getInstance().getState().setLogAutoCaptureSuppressed(false);
        }
        buffer.setPaused(false);
        // T-AgentListenSync：联动 agent 恢复采集（后续两分支 capturing 均置 true，统一在此下发）。
        // T-AgentConfigChannel T-06：这里只是下发恢复采集指令——应用侧是否真恢复以 CMD_CONFIG_ACK
        // 为准（修复前应用侧从未收到过 CONFIG，「应用侧零开销」这类说法与事实相反）。
        AgentManager.getInstance(project).pushCaptureActive(true);
        if (!hasMainConsole()) {
            capturing = true;
            if (manual) {
                userArmed = true;
            }
            isWaitingForConsole = true;
            LOG.info("startCapture: 无运行中的主控制台——进入 armed 态（已开启，等待项目运行, armed=" + userArmed + "）");
            if (bridge.attach(project)) {
                bridge.startPolling();
            }
            refreshToolbar();
            return;
        }
        boolean wasWaiting = isWaitingForConsole;
        isWaitingForConsole = false;
        if (!capturing) {
            capturing = true;
        }
        applySettings();
        if (bridge.attach(project)) {
            if (!wasWaiting) {
                bridge.startPolling();
            }
            bridge.scanAll();
            LOG.info("startCapture: 已开始捕捉 SQL（capturing=true, consoles=" + bridge.attachedConsoles.size() + "）");
            refreshToolbar();
        } else {
            LOG.warn("startCapture: 控制台监听挂载失败（execution API 不可用）——保持 armed 态，可稍后手动重试");
        }
    }

    /** 是否有活跃控制台（用于 apply() 判断是否立即启动监听）。 */
    public boolean hasConsoles() {
        if (!bridge.attachedConsoles.isEmpty()) {
            return true;
        }
        Object rcm = SqlLogConsoleBridge.runContentManagerOrNull(project);
        if (rcm == null) {
            return false;
        }
        try {
            Object raw = rcm.getClass().getMethod("getAllDescriptors").invoke(rcm);
            int count;
            if (raw instanceof Object[]) {
                count = ((Object[]) raw).length;
            } else if (raw instanceof Collection) {
                count = ((Collection<?>) raw).size();
            } else {
                count = 0;
            }
            return count > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否有进程存活的**主**运行控制台（ConsoleViewImpl；含进程存活检查，见桥实现）。 */
    public boolean hasMainConsole() {
        return bridge.hasMainConsole();
    }

    /**
     * 停止捕捉：重置 capturing 标志 + 移除所有控制台上的 Filter + 停止轮询 + 清空已挂载 console 记录。
     */
    public void stopCapture() {
        LOG.info("stopCapture: 调用开始（capturing=" + capturing + ", armed=" + userArmed +
                ", waiting=" + isWaitingForConsole + ", paused=" + buffer.isPaused() + "）");
        if (!capturing && !userArmed) {
            return; // armed 态也须幂等守卫
        }
        capturing = false;
        userArmed = false;
        isWaitingForConsole = false;
        buffer.setPaused(true);
        // T-AgentListenSync：联动 agent 下发停采（CONFIG disabled=true）。
        // T-AgentConfigChannel T-06 文案订正：此前写「应用侧零开销」「真停采」——修复前应用侧根本
        // 收不到这条指令（入站帧解析错位），"应用侧零开销" 与事实相反；现在只保证指令已下发，
        // 生效以 CMD_CONFIG_ACK 为准（未获确认会在 AgentSession 侧 WARN）。
        AgentManager.getInstance(project).pushCaptureActive(false);
        if (PluginSettings.getInstance().getState().getLogAutoCaptureOnStart()) {
            PluginSettings.getInstance().getState().setLogAutoCaptureSuppressed(true);
        }
        bridge.detachAllFilters();
        bridge.stopPolling();
        bridge.detachContentListener();
        refreshToolbar();
    }

    /** 注册 capturing 变化监听（面板调用）。 */
    public void addCapturingListener(Runnable listener) {
        capturingListeners.add(listener);
    }

    /** T-Perf-15：注销 capturing 变化监听（面板 dispose 调用——防面板随 tool window content
     *  销毁重建逐次累积，每条新 SQL 仍回调已销毁面板）。 */
    public void removeCapturingListener(Runnable listener) {
        capturingListeners.remove(listener);
    }

    /** 强制刷新工具栏 action 状态（capturing 变化时按钮图标/文案同步）。 */
    private void refreshToolbar() {
        LOG.info("refreshToolbar: 通知 " + capturingListeners.size() + " 个监听器");
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Runnable it : capturingListeners) {
                it.run();
            }
        });
    }

    /** 新控制台出现（contentAdded / 轮询检测）时回调——armed 态时自动附着 filter 开始捕捉 SQL。 */
    public void onConsoleAppeared() {
        if (capturing && !isWaitingForConsole) {
            return; // 已在捕捉态（非等待）则幂等
        }
        boolean autoStart = PluginSettings.getInstance().getState().getLogAutoCaptureOnStart();
        boolean suppressed = PluginSettings.getInstance().getState().getLogAutoCaptureSuppressed();
        if (!autoStart && !userArmed) {
            return;
        }
        if (suppressed && !userArmed) {
            return;
        }
        LOG.info("onConsoleAppeared: 检测到新控制台，自动开始捕捉（autoStart=" + autoStart + ", suppressed=" + suppressed +
                ", armed=" + userArmed + ", waiting=" + isWaitingForConsole + ", project=" + project.getName() + "）");
        startCapture();
    }

    /**
     * 项目启动（运行配置进程启动）事件——autoStart=true 时无条件自动捕捉。
     *
     * 20260907-2045 沙盒日志实证（IU-243）：手动停止置 suppressed=true 持久化后，
     * onConsoleAppeared 被 suppressed && !userArmed 拦截、LogStartupCapture 只在项目打开
     * 时触发一次——「设置开启 + 用户曾停止 → 再运行项目」从此没有任何自动重启入口，
     * 与踩坑第十一轮「设置优先」结论冲突：用户显式"停止"只应抑制项目未运行期间的 armed 态，
     * 项目真正启动时 logAutoCaptureOnStart=true 应优先。
     *
     * 接线：RunConfigurationProcessListener（ExecutionListener 反射桥，243/262 execution API 兼容）。
     * 与 onConsoleAppeared 的分工：本方法在进程级事件可靠触发（不依赖 RCM tab 扫描），
     * 清 suppressed 后走 startCapture 常规链路（armed 态下为幂等 no-op）。
     */
    public void onProjectProcessStarted() {
        boolean autoStart = PluginSettings.getInstance().getState().getLogAutoCaptureOnStart();
        if (!autoStart) return;
        boolean suppressed = PluginSettings.getInstance().getState().getLogAutoCaptureSuppressed();
        if (suppressed) {
            // 项目启动 = 用户对「自动监听」的再次确认（设置优先，对齐踩坑第十一轮）
            PluginSettings.getInstance().getState().setLogAutoCaptureSuppressed(false);
            LOG.info("onProjectProcessStarted: 项目启动清除 suppressed（autoStart 优先）——project=" + project.getName());
        }
        // T-AutoShowSuppress：项目启动同样视为对「自动展开」的再次确认——用户真机复测实证
        // （2026-09-09）：关窗抑制置位后启动项目仍不弹，与用户预期相悖；启动 = 本轮开发要重新看日志
        if (autoShowSuppressedByUserClose) {
            autoShowSuppressedByUserClose = false;
            LOG.info("onProjectProcessStarted: 项目启动清除自动展开会话抑制——project=" + project.getName());
        }
        if (capturing && !isWaitingForConsole) {
            LOG.debug("onProjectProcessStarted: 已在捕捉态，startCapture 幂等跳过（project=" + project.getName() + "）");
            return;
        }
        if (capturing && isWaitingForConsole && bridge.isPollingActive()) {
            // armed 态已在轮询等待控制台——进程级回调无需重复 attach（测试 T25 实证 attach 会重复）
            LOG.info("onProjectProcessStarted: 已在 armed 轮询等待，无需重复挂载（project=" + project.getName() + "）");
            return;
        }
        LOG.info("onProjectProcessStarted: 项目启动自动捕捉（armed=" + userArmed + ", waiting=" + isWaitingForConsole
                + ", project=" + project.getName() + "）");
        startCapture();
    }

    /**
     * 「项目启动时自动开始监听」设置开启（设置页关→开边沿）——按模式分流（20260907-2045 第四轮）：
     *  - 进程级监听可用（processListenerActive=true）→ 被动模式：清 suppressed 后不预 armed，
     *    按钮保持「捕捉」原状；Run 项目那一刻由 onProjectProcessStarted 驱动捕捉
     *    （用户语义：开启设置 ≠ 监听已开启，08:33 会话反馈"还没启动项目却自动开启"实证）；
     *  - 降级模式（execution API 缺失，RCM 轮询是唯一检测通道）→ 维持原行为进入 armed 态。
     */
    public void onAutoStartSettingEnabled() {
        PluginSettings.getInstance().getState().setLogAutoCaptureSuppressed(false);
        if (processListenerActive) {
            LOG.info("onAutoStartSettingEnabled: 进程级监听已接管——被动等待项目 Run（不预 armed，project=" + project.getName() + "）");
            return;
        }
        LOG.info("onAutoStartSettingEnabled: 进程级监听不可用——降级 armed 模式（project=" + project.getName() + "）");
        startCapture();
    }

    /** 已挂载的 console 集合（bridge 访问）。 */
    public Map<Object, Pair<Object, Class<?>>> getAttachedConsoles() {
        return bridge.attachedConsoles;
    }

    /** 桥访问（真实环境测试缝：scanComponent 挂载桩控制台用，对齐 installBridgeForTest 惯例；
     *  master 为 internal，跨包 testutil 须 public）。 */
    public SqlLogConsoleBridge getConsoleBridge() {
        return bridge;
    }

    /** 控制台被移除（项目停止 / 用户关闭 tab）时回调。 */
    public void onConsoleRemoved() {
        // T-AgentReconnect T-03：本次运行结束 → 关闭该轮 premain 通道（通道生命周期 = 绑定到本次运行，
        // 不再 accept 一次即关，否则会话被摘除后 agent 无限重连撞在已关端口上 = 该次运行终局）。
        // 放在下方 capturing 守卫之前：通道归属「运行」而非「捕捉态」，捕捉态不该决定通道回收；
        // 用 getServiceIfCreated 取服务——本回调不得为「关通道」而实例化从未用过的 AgentManager。
        AgentManager createdAgent = project.getServiceIfCreated(AgentManager.class);
        if (createdAgent != null) {
            createdAgent.onRunEnded();
        }
        if (!capturing || isWaitingForConsole) {
            LOG.debug("onConsoleRemoved: 非捕捉态或仍在等待（capturing=" + capturing + ", waiting=" + isWaitingForConsole + "），跳过");
            return;
        }
        boolean autoStart = PluginSettings.getInstance().getState().getLogAutoCaptureOnStart();
        if (userArmed && !autoStart) {
            // 手动开启 + 未启用自动启动：回到 armed 态（capturing=true, isWaitingForConsole=true）
            // 项目再次启动时 onConsoleAppeared 自动恢复捕捉
            // （armed 态 capturing 仍为 true，故 buffer.paused 保持 false——不变量只约束 capturing=false）
            LOG.info("onConsoleRemoved: 控制台被移除，回到 armed 态（手动开启，等待项目重新运行）");
            isWaitingForConsole = true;
            bridge.detachAllFilters();
        } else {
            LOG.info("onConsoleRemoved: 控制台被移除，停止捕捉（autoStart=" + autoStart + ", armed=" + userArmed + "）");
            capturing = false;
            // T-AgentConfigChannel T-04 状态机对齐：此前这里只置 capturing=false——不下发停采、不置
            // buffer.paused，制造出「按钮 ▶ 但缓冲区仍收条目」这唯一自相矛盾的状态（见 capturing KDoc
            // 的不变量）。两条都补上：paused 走 stopCapture 同款安全网（即便有残留 Filter 也入不了队），
            // 停采指令走窄入口（getServiceIfCreated——本回调不得为「停采」而实例化从未用过的 AgentManager；
            // 服务未创建即无会话，无需下发）。
            buffer.setPaused(true);
            AgentManager stopAgent = project.getServiceIfCreated(AgentManager.class);
            if (stopAgent != null) {
                stopAgent.pushCaptureActive(false);
            }
            bridge.detachAllFilters();
        }
        refreshToolbar();
    }

    /** 应用设置（前缀/去重窗口/过滤——捕捉中修改设置后重新装配生效）。 */
    public void applySettings() {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        buffer.setDedupeWindowMs(state.getLogDedupeWindowMs());
        List<String> kws = Arrays.stream(state.getLogFilterKeywords().split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        buffer.setFilterKeywords(kws);
        buffer.setMaxEntries(state.getLogMaxCount());
        // F-43：自动保存历史 SQL（T-Perf-12：submit 异步批量写——回调只投递无锁队列，
        // 磁盘 IO 全部在专用写线程，控制台输出热路径不再同步 flush）
        // T-SqlHistoryFull：写入来源从「仅淘汰写」改为「入队成功即写」——历史记录全量 SQL；
        // onEvicted 置空防重复（成功入队已写过，被淘汰条目不再二次写盘）
        if (state.getLogAutoSaveEnabled()) {
            // T-HistoryFileRetention：历史文件保留上限
            autoSaver.configure(state.getLogAutoSavePath(), state.getLogAutoSaveFileNamePattern(),
                    project.getBasePath(), state.getLogAutoSaveMaxFiles());
            buffer.setOnEntryStored(autoSaver::submit);
            // O-147：合并是元数据升级不是新捕获——历史落盘链据此追加修订注释
            buffer.setOnEntryMerged(autoSaver::submitMerge);
            buffer.setOnEvicted(null);
        } else {
            buffer.setOnEntryStored(null);
            buffer.setOnEntryMerged(null);
            buffer.setOnEvicted(null);
        }
    }

    /**
     * UI 订阅刷新回调（回调在 EDT 执行；首个订阅者开始桥接缓冲变更）。
     * T-PerfBatch：同一回调挂三个出口——内容监听器（节流后的正式通知）、徽章变更、
     * 内容节流抑制兜底——全部汇入 notifyUi 的置脏+幂等 flush，面板读当下快照不丢条目。
     */
    public void addUiListener(Runnable listener) {
        if (uiListeners.isEmpty()) {
            buffer.addListener(uiNotifyListener);
            buffer.setOnDedupeCountsChanged(uiNotifyListener);
            buffer.setOnContentNotifySuppressed(uiNotifyListener);
        }
        uiListeners.add(listener);
    }

    public void removeUiListener(Runnable listener) {
        uiListeners.remove(listener);
        if (uiListeners.isEmpty()) {
            // 券A：最后一个订阅者退订即取消挂起的补刷拍（面板销毁链；无订阅者时 Alarm 触发只会空转）
            uiCatchUpAlarm.cancelAllRequests();
            catchUpScheduled = false;
            buffer.removeListener(uiNotifyListener);
            buffer.setOnDedupeCountsChanged(null);
            buffer.setOnContentNotifySuppressed(null);
        }
    }

    /** F-43 测试访问器：当前自动保存文件。 */
    File autoSaverCurrentFile() {
        return autoSaver.currentFile();
    }

    /** F-43 测试访问器：解析文件名模板。 */
    String autoSaverResolveFileName() {
        return autoSaver.resolveFileName();
    }

    /** 条目缓冲（面板与测试访问）。 */
    public SqlLogEntryBuffer getEntries() {
        return buffer;
    }

    public boolean isCapturing() {
        return capturing;
    }

    /** Kotlin 属性访问器等价物（master 侧 Kotlin `capturing` 属性编译为 getCapturing——直取的 master Java 文件按此调用）。 */
    public boolean getCapturing() {
        return capturing;
    }

    public boolean isUserArmed() {
        return userArmed;
    }

    public boolean isWaitingForConsole() {
        return isWaitingForConsole;
    }

    /**
     * T-Perf-03：UI 刷新合并——流式灌入时每条 SQL 一次 invokeLater 的调度风暴改为
     * 「置脏 + 单个幂等 flush」：首个变更投递一个 invokeLater（pending 置位），后续变更
     * 在 flush 执行前只置脏不重复投递；flush 读的是当下缓冲快照，不丢任何条目。
     *
     * <p>T-SqlListCatchup 券A：时间窗下限——T-Perf-03 的在途合拍只压「上一拍没跑完」的形态，
     * EDT 一空立刻刷下一拍，风暴期（EDT 快、通知密）退化为每秒几十拍高频小刷新
     * （前提探针实测：5 条通知各成一拍）。追加 {@link #MIN_FLUSH_INTERVAL_MS} 窗口：距上次
     * flush 完成不足窗口时不再立即刷，挂 {@code uiCatchUpAlarm} 在剩余延迟后补刷一拍——
     * 无通知时零开销（Alarm 单发，非常驻 Timer），风暴期拍距自然收敛到窗口值。
     * <b>窗口外（含首条/从未刷过）仍立即刷</b>——保住「单条 SQL 秒显」的日常体验，只压合并拍。</p>
     */
    volatile boolean uiFlushPending = false;

    /** 券A 测试缝：直置在途标志（模拟「invokeLater 已排队、EDT 尚未执行」的竞态窗，Alarm 竞态用例用）。 */
    void setUiFlushPendingForTest(boolean value) {
        uiFlushPending = value;
    }

    /** 券A：上次 flush 完成时刻（{@code System.nanoTime}；0L = 从未刷过的哨兵——视为窗口外立即刷）。internal 测试缝。 */
    volatile long lastFlushAtNanos = 0L;

    /** 券A：补刷拍已挂标志——窗口内后续通知不重复挂（Alarm 单飞）。notifyUi 可从 BGT 调、动作在池化线程清零，volatile 兜并发。 */
    private volatile boolean catchUpScheduled = false;

    /**
     * 券A：补刷 Alarm。parent 挂本服务（Disposable）——项目关闭随 Disposer 树注销并取消在途请求，无孤儿 Alarm 泄漏。
     * POOLED_THREAD：243 平台 SWING_THREAD 已废弃（反编译实证，Deprecation "Use POOLED_THREAD instead"）；
     * 动作只碰 volatile 标志 + invokeLater（任意线程安全），无需 EDT 直跑。
     */
    private final com.intellij.util.Alarm uiCatchUpAlarm = new com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.POOLED_THREAD, this);

    /**
     * 券A 测试缝：补刷调度器（默认真 Alarm；测试注入记录器手动触发，钉住调度值而非墙钟——L18 口径）。
     * Kotlin 函数类型字段（Kotlin 测试以 lambda 直赋，禁 Java SAM 接口）。
     */
    private volatile kotlin.jvm.functions.Function2<Long, kotlin.jvm.functions.Function0<kotlin.Unit>, kotlin.Unit> catchUpScheduler =
        (delayMs, action) -> {
            uiCatchUpAlarm.addRequest(action::invoke, delayMs);
            return kotlin.Unit.INSTANCE;
        };

    /**
     * 测试缝访问器（177 轮随生产面同步补）：master 侧该字段是 Kotlin {@code var}（Kotlin 测试可直赋），
     * Java 承载形态为私有 volatile 字段——补同包可见 setter 供 Kotlin 测试注入同步调度器
     * （{@code manager.catchUpScheduler = { _, action -> action() }} 在 Java 端口需走此入口）。
     */
    void setCatchUpSchedulerForTest(
            kotlin.jvm.functions.Function2<Long, kotlin.jvm.functions.Function0<kotlin.Unit>, kotlin.Unit> scheduler) {
        this.catchUpScheduler = scheduler;
    }

    private void notifyUi() {
        if (uiListeners.isEmpty()) return;
        if (uiFlushPending) return; // 已有 flush 在途——本次变更由该 flush 的当下快照覆盖
        // 券A：时间窗判定。窗口内不立即刷，挂一个补刷拍在剩余延迟后补（已在挂则复用该拍）。
        long last = lastFlushAtNanos;
        if (last != 0L) {
            long elapsedNanos = System.nanoTime() - last;
            if (elapsedNanos < MIN_FLUSH_INTERVAL_NANOS) {
                if (!catchUpScheduled) {
                    catchUpScheduled = true;
                    long remainingMs = (MIN_FLUSH_INTERVAL_NANOS - elapsedNanos) / 1_000_000L;
                    catchUpScheduler.invoke(Math.max(remainingMs, 1L), () -> {
                        catchUpScheduled = false;
                        // Alarm 到点时已有更新在途——跳过，不重复刷；订阅者已退订（detach 链）同样跳过
                        if (!uiFlushPending && !uiListeners.isEmpty()) {
                            startFlush();
                        }
                        return kotlin.Unit.INSTANCE;
                    });
                }
                return;
            }
        }
        startFlush();
    }

    /** 券A：发起一拍 flush（invokeLater 到 EDT；完成时刻记入 {@code lastFlushAtNanos} 开新窗口）。 */
    private void startFlush() {
        uiFlushPending = true;
        ApplicationManager.getApplication().invokeLater(() -> {
            uiFlushPending = false;
            for (Runnable it : uiListeners) {
                it.run();
            }
            lastFlushAtNanos = System.nanoTime();
        });
    }

    private ParserConfig parserConfig() {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        return new ParserConfig(
                state.getLogSqlPrefix(),
                state.getLogParamPrefix(),
                state.getLogMixedPrefix(),
                state.getLogMaxParseLength(),
                state.getLogHibernateSqlPrefix(),
                state.getLogHibernateParamPrefix(),
                state.getLogHibernateEnabled(),
                state.getLogJdbcEnabled(),
                state.getLogShardingSphereEnabled());
    }

    /** 面板销毁/项目关闭时移除挂载（capturing 同步重置——状态机闭环）。 */
    @Override
    public void dispose() {
        // 券A：项目关闭路径清掉挂起的补刷拍（Alarm 亦以本服务为 parentDisposable 挂 Disposer 树，
        // 此处显式取消是双保险——先断请求再走树销毁，杜绝销毁竞态下动作再入队）
        uiCatchUpAlarm.cancelAllRequests();
        catchUpScheduled = false;
        userArmed = false;
        isWaitingForConsole = false;
        capturing = false;
        buffer.setPaused(true);
        // T-AgentConfigChannel T-04 状态机对齐：capturing=false 必须伴随一次停采下发（不变量见 capturing KDoc）。
        // 本场景服务可能正在销毁（AgentManager 与 SqlLogManager 同属项目级，dispose 顺序无保证）：
        //  - 服务尚在 → pushCaptureActive 真下发 CONFIG disabled=true（应用侧随之停采）；
        //  - 服务已被销毁 / project 已 disposed → isEnhancedActive 判假成 no-op，会话由 AgentManager.dispose
        //    关闭 socket 回收（应用侧见连接断开；是否随之停采取决于对端断连处理，属应用侧 T-07 看门狗范围）。
        // 故这里只记一行事实，不假装「一定停采成功」；同样用 getServiceIfCreated，不为停采实例化服务。
        AgentManager agent = project.getServiceIfCreated(AgentManager.class);
        if (agent != null) {
            agent.pushCaptureActive(false);
        } else {
            LOG.info("dispose: AgentManager 未创建——无会话可下发停采（capturing=false + buffer.paused=true 已对齐状态机）");
        }
        bridge.detach();
        buffer.removeListener(uiNotifyListener);
        // T-SqlSuppress：注销应用级规则订阅（project close 断开——应用级监听器列表不残留本项目条目），
        // 键集清零防 disposed 缓冲再被本项目残留订阅污染
        suppressRuleManager.removeListener(suppressRuleListener);
        buffer.setOnSuppressedHit(null);
        buffer.setSuppressKeys(java.util.Set.of());
        buffer.setHasStructureKeys(false);
        buffer.setOnEntryAccepted(null);
        buffer.setOnEntryStored(null);
        buffer.setOnEntryMerged(null);
        buffer.setOnDedupeCountsChanged(null);
        buffer.setOnContentNotifySuppressed(null);
        uiListeners.clear();
        autoSaver.close();
    }

    private static final Logger LOG = Logger.getInstance(SqlLogManager.class);

    /** 主工具窗 id（plugin.xml toolWindow 注册；F-44 后历史 Tab 合并进本窗口）。 */
    public static final String TOOL_WINDOW_ID = "cn.song.mybatisplus.visualizer.log";

    /** M37.3：自动展开防抖窗口（ms）——多条 SQL 连续到达时只展开一次。 */
    static final long AUTO_SHOW_DEBOUNCE_MS = 60_000L;

    /**
     * T-SqlListCatchup 券A：UI 刷新时间窗下限（ms）。
     *
     * <p>为什么 150ms：计划 §四定例——人眼对列表滚动/追尾无感知差异的下限附近，
     * 再大伤「单条 SQL 秒显」体感，再小压不住风暴期高频小拍（探针实证 5 通知 5 拍）。
     * 风暴期（~60 SQL/s）拍距收敛到 150ms ≈ 每秒 6~7 拍上限，EDT 占用显著下降；
     * 日常低流量（两条间隔 &gt; 150ms）完全不受影响。</p>
     */
    static final long MIN_FLUSH_INTERVAL_MS = 150L;

    /** {@link #MIN_FLUSH_INTERVAL_MS} 的纳秒口径（notifyUi 热路径与 {@code System.nanoTime} 同单位比较）。 */
    private static final long MIN_FLUSH_INTERVAL_NANOS = MIN_FLUSH_INTERVAL_MS * 1_000_000L;

    /** M37.3：自动展开决策（纯函数，测试缝）——设置开启、未被用户关闭抑制、且距上次展开超过防抖窗口。 */
    static boolean shouldAutoShow(boolean enabled, long elapsedMs, boolean suppressedByUserClose) {
        return enabled && !suppressedByUserClose && elapsedMs >= AUTO_SHOW_DEBOUNCE_MS;
    }

    /** T-AutoShowSuppress：手动关闭判定（纯函数，测试缝）——只在「可见→不可见」转换沿认定。
     *  自动展开自身只产生 不可见→可见 沿，不会误判；初值 false 时启动/布局中间态不触发。 */
    static boolean isUserCloseTransition(boolean wasVisible, boolean isVisible) {
        return wasVisible && !isVisible;
    }
}
