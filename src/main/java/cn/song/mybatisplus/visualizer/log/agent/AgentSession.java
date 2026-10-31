package cn.song.mybatisplus.visualizer.log.agent;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.log.LogSqlEntry;
import cn.song.mybatisplus.visualizer.log.LogSqlModelsKt;
import cn.song.mybatisplus.visualizer.log.LogTimeCorrelator;
import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 单个 agent 会话：读线程循环解帧（EVENT → 适配 → buffer；HEARTBEAT 刷新在线窗口；
 * HELLO 记录 agent 侧 pid；CONFIG_ACK 记录生效值并清挂起），CONFIG 下发。alive = socket 未断。
 *
 * <p>T-AgentConfigChannel T-02/T-03 两条新增语义：
 * <ul>
 *   <li><b>回执可观测</b>：每次 {@link #sendConfig} 记一次挂起，窗口内未收到 {@link AgentProtocol#CMD_CONFIG_ACK}
 *       即 WARN「CONFIG 未获确认——应用侧可能未生效」（排定复用管理器的 monitor，不新开线程）；</li>
 *   <li><b>入队闸门</b>：EVENT 帧在 SQL 监听关闭（{@code SqlLogManager.capturing} == false）时<b>不入队</b>并计数
 *       ——即便应用侧再次失控投递，面板显示也不说谎（只闸入队点，MCP 审计/历史回读不经此处）。</li>
 * </ul>
 */
public class AgentSession {

    private static final Logger LOG = Logger.getInstance(AgentSession.class);

    /** 未命中逐条打印上限（前 N 条够看清两侧 rawSql 差异）。 */
    private static final long MISS_LOG_LIMIT = 3L;

    /** 逐条打印用尽后的累计打印步长。 */
    private static final long MISS_LOG_STEP = 200L;

    private final Project project;
    private final long pid;
    private final Socket socket;
    private final Runnable onStateChange;

    /**
     * T-ReachAudit T-RA-01：时间源（默认系统时钟）。与 {@code AgentManager.clockMs} 同源注入——心跳窗口判定
     * 跨「会话记 lastSeen」与「管理器算过期」两侧，两侧时钟不同源则测试无法推进时间（假时钟一注入，
     * 真实时间戳的 lastSeen 会让会话永远健康），真机也无差异。
     */
    private final LongSupplier clockMs;

    /**
     * T-PS5-04：连接 URL 首次捕获/变化回调（默认空操作）——管理器据此把推断 URL 回写
     * {@link AgentUrlRegistry}。只在 URL <b>变化</b>时触发（每个 SQL 事件都带 URL，逐事件回调无意义）。
     */
    private final Runnable onUrlCaptured;

    /**
     * T-AgentConfigChannel T-02：CONFIG 回执超时巡检的排定入口（默认空操作 = 测试不排定）。
     * 生产链路注入 {@code AgentManager} 的 monitor 排定（见 {@code AgentManager.scheduleConfigAckCheck}）——
     * 不新开常驻线程；测试可直接持有任务引用同步驱动，无需真等 5s。
     */
    private final BiConsumer<Long, Runnable> scheduleConfigAckCheck;

    /**
     * 追加 P-5（计划 §五 P-5）：应用侧采集确认态变化通知（收到回执时触发，默认空操作）。
     * 生产链路由 {@code AgentManager} 注入 → 重算 {@code SqlCaptureGate.paused}（见
     * {@code AgentManager.captureConfirmedActive}）。
     */
    private final Runnable onCaptureConfirmationChanged;

    /**
     * R53：用户暂停闸门（默认 false = 不暂停）。生产由 {@code AgentManager} 注入 {@code () -> userPaused}——
     * 暂停只停 agent 通道（EVENT 在入队点丢弃），连接/心跳/CONFIG ACK 链不动（暂停≠关闭）。
     * 包私有可注入为同模块守护测试缝（照 {@link #listenActiveProvider} 先例）。
     */
    BooleanSupplier pausedProvider = () -> false;

    private volatile boolean alive = true;

    private volatile long lastSeen;

    private final DataOutputStream output;
    private final AtomicLong eventDropped = new AtomicLong();

    /**
     * T-AgentConfigChannel T-03：入队闸门丢弃计数（监听关闭状态下投递的 EVENT 条数）。
     * 单调递增——它是「控制通道可能失效」的量化证据（应用侧仍在采集投递），不只丢弃了事。
     */
    public final AtomicLong intakeDropped = new AtomicLong();

    /**
     * R53：用户暂停态丢弃计数（暂停期间投递的 EVENT 条数）。单调递增——「暂停中」不是「通道断了」：
     * 计数在涨 = agent 通道仍活着、只是被用户停掉展示；结合 {@code AgentEventAdapter.receivedEvents}
     * （两者同帧同增）可区分「暂停拦截」与「通道失效」。
     */
    public final AtomicLong pausedDropped = new AtomicLong();

    /** T-AgentConfigChannel T-03：入队闸门判据（可注入测试缝；默认读 SqlLogManager.capturing）。 */
    private BooleanSupplier listenActiveProvider = this::defaultListenActive;

    /**
     * {@link #listenActiveProvider} 的默认判据。经方法引用挂接：字段初始化器里直接写 lambda 会
     * 在构造期捕获尚未赋值的 {@code project}（javac「可能尚未初始化」），方法体执行时它已就绪。
     */
    private boolean defaultListenActive() {
        // 服务未创建/项目已销毁按 false —— 宁丢弃勿谎报（与 AgentManager.isListenActive 同口径）；
        // T-ST-02b2：读路径经 sqlLogManagerIfCreated（永不发起创建 → 不会毒化服务容器）
        SqlLogManager manager = cn.song.mybatisplus.visualizer.log.SqlLogManagerLookupKt.sqlLogManagerIfCreated(project);
        return manager != null && manager.isCapturing();
    }

    /** {@link #listenActiveProvider} 读取入口（Kotlin var 属性访问器等价）。 */
    public BooleanSupplier getListenActiveProvider() {
        return listenActiveProvider;
    }

    /** {@link #listenActiveProvider} 注入入口（测试缝）。 */
    public void setListenActiveProvider(BooleanSupplier provider) {
        this.listenActiveProvider = provider;
    }

    /**
     * T-AgentConfigChannel T-02：已收到的 CONFIG 回执数（可观测/测试断言）。
     * 收到回执 = 应用侧确实解析并应用了控制指令，与「已下发」是两件事。
     */
    public final AtomicLong configAckCount = new AtomicLong();

    /** T-AgentConfigChannel T-02：回执超时告警数（每会话；>0 即「控制通道可能失效」）。 */
    public final AtomicLong configAckTimeoutCount = new AtomicLong();

    /** 挂起中的 CONFIG 回执（seq 区分「同一次下发的等待」，见 {@link #onConfigAckTimeout}）。 */
    private static final class PendingConfigAck {

        final long seq;
        final boolean disabled;
        final long sentAtMs;

        /** 下发那一刻的 {@link #inboundFrameCount}——超时文案据此判「下发之后是否还收到过入站帧」。 */
        final long framesAtSend;

        PendingConfigAck(long seq, boolean disabled, long sentAtMs, long framesAtSend) {
            this.seq = seq;
            this.disabled = disabled;
            this.sentAtMs = sentAtMs;
            this.framesAtSend = framesAtSend;
        }
    }

    private final AtomicReference<PendingConfigAck> pendingConfigAck = new AtomicReference<>(null);
    private final AtomicLong configAckSeq = new AtomicLong();

    /**
     * 入站帧计数（单调递增，含 HELLO 之外的<b>一切</b>帧）。
     *
     * <p>为什么不用 {@code lastSeen} 判「下发后是否还有入站」：{@code lastSeen} 在会话构造时就取了当前时刻，
     * 紧接其后的 CONFIG 下发会得到 {@code lastSeen == sentAtMs}，时钟粒度下<b>分不清</b>「刚建连、什么都没来」
     * 与「刚来过一帧」——正是超时文案要区分的那两种形态。计数器没有这个问题（严格比较大小）。
     */
    private final AtomicLong inboundFrameCount = new AtomicLong();

    /**
     * T-AgentConfigChannel T-02：记录一次 CONFIG 下发并排定回执超时巡检。
     *
     * <p>只在下发<b>成功写出</b>后调用（写失败走既有降级，无回执可等）。同会话可连续下发（脱敏开关、
     * 监听开关），故用 seq 标记本次挂起：巡检到点时若挂起已被更新（更新的下发）或已被清（回执到达、
     * 会话关闭）则静默退出——<b>不会</b>为一次已被取代的下发误报「未获确认」。
     *
     * <p>同时记下发时刻（T-AgentResiduals R4-①）：超时文案要区分「连接已断」「应用侧在跑但没回执」
     * 「应用侧静默」三态，判据是「下发之后是否还收到过入站帧」。
     */
    private void markConfigSent(boolean disabled) {
        long seq = configAckSeq.incrementAndGet();
        pendingConfigAck.set(new PendingConfigAck(seq, disabled, clockMs.getAsLong(), inboundFrameCount.get()));
        scheduleConfigAckCheck.accept(AgentManager.CONFIG_ACK_TIMEOUT_MS, () -> onConfigAckTimeout(seq));
    }

    /**
     * T-AgentConfigChannel T-02：回执超时巡检体（public 供测试同步驱动，生产由 monitor 排定）。
     *
     * <p>到点时仍挂着<b>同一次</b>下发 → 计一次并 WARN：这是本轮缺陷从「只能靠行为反推」变成
     * 一行日志的直接判据。
     *
     * <p><b>T-AgentResiduals R4-①：文案分会话健康（复核 F3）</b>。原实现只有一句「应用侧可能未生效」，
     * 而三种成因对用户的处置完全不同、原句把它们混成一种：
     * <ul>
     *   <li>① <b>连接已断</b>（{@code alive == false}：socket 断 / 读循环已退出）——此时 CONFIG 是否曾生效
     *       <b>无从确认</b>，说「应用侧未生效」是<b>错叙事</b>（连接都不在了，与应用侧应用与否无关）；
     *       正常路径下掉线会清挂起（见 {@link #clearPendingConfigAck}），本分支覆盖的是
     *       「清挂起与巡检并发」的竞态窗口，以及半死 socket（TCP 未察觉、读循环尚未退出）；</li>
     *   <li>② <b>应用侧在跑却没回执</b>（下发后仍收到存活帧）——应用侧确实活着，典型是<b>旧版 agent
     *       不支持回执</b>或入站帧解析错位；此时若条目仍在产出，属<b>混合版本下的正常双通道</b>（见
     *       {@code configAckDisabled} 的边界说明），不该让用户以为「开关没生效」；</li>
     *   <li>③ <b>应用侧静默</b>（下发后一条入站帧都没有）——这才是原句真正要说的形态。</li>
     * </ul>
     *
     * <p>三态<b>共用同一个计数器</b>（{@link #configAckTimeoutCount}：它记的是「回执超时」这一事实，与成因无关），
     * 只有文案分叉；测试按文案分态断言（见 {@code AgentManagerIntakeGateTest}）。
     */
    public void onConfigAckTimeout(long seq) {
        PendingConfigAck pending = pendingConfigAck.get();
        if (pending == null) {
            return;
        }
        if (pending.seq != seq) {
            return; // 已被更新的下发取代或已收到回执
        }
        if (!pendingConfigAck.compareAndSet(pending, null)) {
            return;
        }
        configAckTimeoutCount.incrementAndGet();
        // 健康判据一律取「到点那一刻」的实况：alive 为 volatile、帧计数单调递增
        boolean aliveNow = alive;
        boolean frameAfterSend = inboundFrameCount.get() > pending.framesAtSend;
        long sinceLastFrameMs = Math.max(0L, clockMs.getAsLong() - lastSeen);
        String message = AgentManagerKt.configAckTimeoutMessage(pid, pending.disabled, aliveNow, frameAfterSend, sinceLastFrameMs);
        lastConfigAckTimeoutMessage = message; // 测试缝：文案分态靠它断言（日志本身在单测里读不到）
        LOG.warn(message);
    }

    /** T-AgentConfigChannel T-02：清挂起（收到回执 / 会话关闭 / 掉线——挂起状态按会话，不得跨会话残留）。 */
    private void clearPendingConfigAck() {
        pendingConfigAck.set(null);
    }

    /** T-AgentConfigChannel T-02：当前是否有挂起中的 CONFIG 下发（测试/诊断）。 */
    public boolean hasPendingConfigAck() {
        return pendingConfigAck.get() != null;
    }

    /**
     * T-AgentResiduals R4-① 测试缝：最近一次回执超时文案（{@code null} = 尚未发生过超时）。
     *
     * <p>为什么留这条缝：三态文案的分叉是本次交付的<b>主体</b>，而日志在单测里读不到（平台 Logger 无
     * 内存捕获面）——若只测纯函数 {@code configAckTimeoutMessage}，就漏了「巡检体是否把实况传对了」
     * 这一半（恰好是本轮最容易被改坏的一半：alive/帧计数快照）。故巡检体把成品文案记在这里，
     * 由 {@code AgentManagerIntakeGateTest} 端到端断言（真 socket 写帧 → 驱动巡检 → 读文案）。
     * 生产链路零读取，仅一个 volatile 写。
     */
    private volatile String lastConfigAckTimeoutMessage = null;

    /** {@link #lastConfigAckTimeoutMessage} 读取入口（Kotlin 属性访问器等价）。 */
    public String getLastConfigAckTimeoutMessage() {
        return lastConfigAckTimeoutMessage;
    }

    /**
     * 追加 P-5：应用侧<b>已确认</b>的采集开关值（{@code null} = 未确认/待确认）。
     *
     * <p>与「已下发」严格区分：本字段只由 {@link AgentProtocol#CMD_CONFIG_ACK} 写入，每次 {@link #sendConfig}
     * 下发即清回 {@code null}（下发成功 ≠ 应用侧生效——本轮缺陷正是这两件事被混为一谈）。
     * 读侧见 {@code AgentManager.captureConfirmedActive}（桥闸门依据）。
     *
     * <p>清空而非保留旧值：控制通道再出问题时，闸门必须回到「不确定就不封桥」这一侧——
     * 宁可短暂双通道产出（列表去重窗口内折叠），也不接受「封着桥 + 应用侧静默 = 零捕获且无信号」。
     *
     * <p><b>已知边界（T-AgentResiduals R4-③，事实登记，行为不变）</b>：不含回执能力的<b>旧版 agent</b>
     * 恒不回 ACK → 本字段恒 {@code null} → 桥永久放行 → 双通道共存（同 {@code captureConfirmedActive} 的边界段）。
     * 用户可见结果由列表去重窗口保证正确；该形态不修，理由同上（改判据=回到有害的「按在线封桥」）。
     */
    private volatile Boolean configAckDisabled = null;

    /** {@link #configAckDisabled} 读取入口（Kotlin 属性访问器等价）。 */
    public Boolean getConfigAckDisabled() {
        return configAckDisabled;
    }

    /** T-DialectAutoInfer：最近一次事件携带的连接 URL（connect 时捕获，会话内记忆；null=旧版 agent/未捕获）。 */
    private volatile String lastConnectionUrl = null;

    /**
     * T-AgentHeartbeat T-05：是否收到过 <b>HELLO 之外的存活帧</b>（HEARTBEAT 或 EVENT）。
     *
     * <p>语义只服务<b>状态行叙事</b>，不参与协议判定：读循环对<b>所有</b>帧都刷新 {@code lastSeen}，
     * 于是「连接刚建立、首条心跳还没到」与「曾有心跳、之后停摆」在 {@code lastSeen} 上完全同形，
     * 摘除时区分不出来——而两者对用户的处置完全不同（前者等一等，后者去看应用侧）。
     * HELLO 是连接首帧、只证明 socket 建起来了，不证明应用侧越过启动期，故不计入存活帧。
     *
     * <p>读线程写、巡检线程（{@code AgentManager.sweepOffline}）读，故 volatile。
     */
    private volatile boolean livenessFrameSeen = false;

    /** {@link #livenessFrameSeen} 读取入口（Kotlin 属性访问器等价）。 */
    public boolean getLivenessFrameSeen() {
        return livenessFrameSeen;
    }

    /** T-AgentLogTime：日志时间未命中计数（诊断——键不等 vs 时序赶不上，真机排障一眼分辨）。 */
    private final AtomicLong timeMiss = new AtomicLong();

    /** 便捷构造器：clockMs 缺省系统时钟（Kotlin 默认参数）。 */
    public AgentSession(Project project, long pid, Socket socket, Runnable onStateChange) throws IOException {
        this(project, pid, socket, onStateChange, () -> System.currentTimeMillis());
    }

    /** 便捷构造器：onUrlCaptured 缺省空操作（Kotlin 默认参数）。 */
    public AgentSession(Project project, long pid, Socket socket, Runnable onStateChange, LongSupplier clockMs)
            throws IOException {
        this(project, pid, socket, onStateChange, clockMs, () -> { });
    }

    /** 便捷构造器：scheduleConfigAckCheck / onCaptureConfirmationChanged 缺省空操作（Kotlin 默认参数）。 */
    public AgentSession(Project project, long pid, Socket socket, Runnable onStateChange, LongSupplier clockMs,
                        Runnable onUrlCaptured) throws IOException {
        this(project, pid, socket, onStateChange, clockMs, onUrlCaptured,
            (delay, task) -> { }, () -> { });
    }

    /** 便捷构造器：onCaptureConfirmationChanged 缺省空操作（Kotlin 默认参数）。 */
    public AgentSession(Project project, long pid, Socket socket, Runnable onStateChange, LongSupplier clockMs,
                        Runnable onUrlCaptured, BiConsumer<Long, Runnable> scheduleConfigAckCheck) throws IOException {
        this(project, pid, socket, onStateChange, clockMs, onUrlCaptured, scheduleConfigAckCheck, () -> { });
    }

    /** 便捷构造器：pausedProvider 缺省 false（Kotlin 默认参数——R53 暂停闸门）。 */
    public AgentSession(Project project, long pid, Socket socket, Runnable onStateChange, LongSupplier clockMs,
                        Runnable onUrlCaptured, BiConsumer<Long, Runnable> scheduleConfigAckCheck,
                        Runnable onCaptureConfirmationChanged) throws IOException {
        this(project, pid, socket, onStateChange, clockMs, onUrlCaptured, scheduleConfigAckCheck,
            onCaptureConfirmationChanged, () -> false);
    }

    public AgentSession(Project project, long pid, Socket socket, Runnable onStateChange, LongSupplier clockMs,
                        Runnable onUrlCaptured, BiConsumer<Long, Runnable> scheduleConfigAckCheck,
                        Runnable onCaptureConfirmationChanged, BooleanSupplier pausedProvider) throws IOException {
        this.project = project;
        this.pid = pid;
        this.socket = socket;
        this.onStateChange = onStateChange;
        this.clockMs = clockMs;
        this.onUrlCaptured = onUrlCaptured;
        this.scheduleConfigAckCheck = scheduleConfigAckCheck;
        this.onCaptureConfirmationChanged = onCaptureConfirmationChanged;
        this.pausedProvider = pausedProvider;
        this.output = new DataOutputStream(socket.getOutputStream());
        this.lastSeen = clockMs.getAsLong();
        socket.setSoTimeout(0);
        Thread reader = new Thread(this::readLoop, "mps-agent-session-" + pid);
        reader.setDaemon(true);
        reader.start();
    }

    public boolean isAlive() {
        return alive;
    }

    /** Kotlin {@code var alive} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
    public boolean getAlive() {
        return alive;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public long getPid() {
        return pid;
    }

    /** T-DialectAutoInfer：最近一次事件携带的连接 URL（会话内记忆；null=旧版 agent/未捕获）。 */
    public String getLastConnectionUrl() {
        return lastConnectionUrl;
    }

    private void readLoop() {
        try {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            while (true) {
                int len = input.readInt();
                if (len < 0 || len > AgentProtocol.MAX_FRAME_LEN) {
                    // 超限帧容错：读掉 body 丢弃继续，不杀会话（一次大事件不应永久掉线）
                    LOG.warn("frame len=" + len + " 超限（MAX=" + AgentProtocol.MAX_FRAME_LEN + "）——丢弃该帧继续");
                    if (len > 0) {
                        input.skipNBytes((long) len);
                    }
                    continue;
                }
                int cmd = input.readUnsignedByte();
                byte[] body = new byte[len];
                input.readFully(body);
                lastSeen = clockMs.getAsLong();
                inboundFrameCount.incrementAndGet(); // R4-①：超时文案判「下发后是否还有入站」的依据
                String bodyText = new String(body, StandardCharsets.UTF_8);
                // T-CaptureCatchAll 券03：attach 覆盖自检计数（可选键，旧版 agent 无 → 零动作）。
                // 放在 handleFrame 之前：计数上报帧可能是任何命令（C1 路当前挂在心跳里），
                // 与主链分发解耦；解析失败在内部吞掉，绝不影响事件处理（红线）。
                AgentAttachReport.updateFromFrame(bodyText);
                handleFrame(cmd, bodyText);
            }
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传（读线程：透传即线程终止，平台记录现场）
            LOG.warn("会话读循环退出（pid=" + pid + ", alive 前=" + alive + "）——掉线原因如下", t);
            alive = false;
            // T-AgentConfigChannel T-02：挂起状态按会话——掉线即清（重连是新会话，旧挂起不得残留）
            clearPendingConfigAck();
            onStateChange.run();
        }
    }

    private void handleFrame(int cmd, String body) {
        if (cmd == AgentProtocol.CMD_EVENT) {
            // T-AgentHeartbeat T-05：EVENT 与 HEARTBEAT 同证「应用侧活着且已越过启动期」
            livenessFrameSeen = true;
            AgentEvent event = AgentEvent.parse(body);
            if (event == null) {
                LOG.warn("EVENT 帧解析失败丢弃（pid=" + pid + ", dropped=" + eventDropped.incrementAndGet()
                    + ", bodyLen=" + body.length() + ", head=" + take(body, 120) + "）");
                return;
            }
            try {
                // T-DialectAutoInfer：connect URL 会话内记忆（推断依据；null 容错——旧版 agent 无此键）
                // T-PS5-04：URL 变化才回调（回写推断持有者；逐事件回调无意义）
                String url = event.url();
                if (url != null && !url.isBlank()) {
                    if (!url.equals(lastConnectionUrl)) {
                        lastConnectionUrl = url;
                        onUrlCaptured.run();
                    }
                }
                // T-AgentConfigChannel T-03：**入队闸门**——监听关闭时条目不得进列表。
                //
                // 放在此处（URL 记忆之后、stampLogTime 之前）的两个理由：
                //  ① 闸的是「入队点」本身（本任务边界：只作用于 agent 通道入队点，MCP 审计与
                //     历史回读不经这里，行为不变）；URL 记忆是**方言推断依据**，与列表显示无关，
                //     且既有用例 AgentManagerLazyMonitorTest 以非捕获态验证 URL 回写，闸在它之前会误伤；
                //  ② stampLogTime 会**消费**日志时间相关器的暂存匹配（未命中才回落），先丢弃再
                //     调用会把本该留给控制台通道的匹配白白取走。
                if (!listenActiveProvider.getAsBoolean()) {
                    long n = intakeDropped.incrementAndGet();
                    if (n == 1L || n % AgentManager.INTAKE_DROP_WARN_STEP == 0L) {
                        LOG.warn("增强通道在监听关闭状态下投递了 " + n + " 条——控制通道可能失效"
                            + "（pid=" + pid + ", 已丢弃不入队；首条/每 " + AgentManager.INTAKE_DROP_WARN_STEP + " 条记一次）");
                    }
                    return;
                }
                LogSqlEntry entry = AgentEventAdapter.toEntry(event);
                // T-DevAcceptFix2 ⑤（20261006 第二轮验收）：事件到达打点——收到即计数
                //（含后续被去重/屏蔽/展示链丢弃的）。面板零产出时据此分流「事件到没到」：
                // N=0 → 查 agent 侧（织入/门禁/传输）；N>0 → 查消费侧。
                AgentEventAdapter.markReceived();
                // T-AttachZeroEvent（2026-10-09 现场复盘）：receivedCount 此前零出口——「计数在涨」
                // 与「面板零产出」并存时无法判「事件没到」还是「消费链丢了」。首条到达打一行 INFO
                //（与宿主侧 WovenEvents 首条 stderr 行配对），三分故障域：宿主无首条=agent 侧；
                // 宿主有、IDE 无=传输；两侧都有而面板空=消费链（去重/屏蔽/过滤/暂停）。
                if (AgentEventAdapter.receivedEvents() == 1L) {
                    LOG.info("增强通道首条事件已到达并进入消费链（pid=" + pid + "）——若面板仍零产出，查消费链（去重窗口/屏蔽/过滤），不再疑 agent 侧");
                }
                // R53：用户暂停闸门（在收到计数**之后**——暂停中「已收事件 N」照常增长，
                // 状态栏据此显示「暂停中且通道活着」而非「通道断了」）。只丢入列表，
                // 连接/心跳/CONFIG ACK 链一概不动（暂停≠关闭，V80② 自洽）。
                if (pausedProvider.getAsBoolean()) {
                    long n = pausedDropped.incrementAndGet();
                    if (n == 1L || n % AgentManager.INTAKE_DROP_WARN_STEP == 0L) {
                        LOG.info("增强通道在用户暂停态收到 " + n + " 条事件——丢弃不入队（暂停只停 agent 通道，"
                            + "控制台通道照常；恢复即续采）");
                    }
                    return;
                }
                // T-AgentDialectLoop / T-AgentLogTime：接管期间控制台嗅探暂存的「日志行原始时间」
                // 按 logTimeKey（占位符版 SQL 指纹）取回，参数值序列作次级判别（stampLogTime）。
                // 未命中回落捕获时间（= execute 开始时刻，与日志行只差毫秒级）并留痕 logTimeMiss。
                // T-ST-02b2：读路径经 sqlLogManagerIfCreated（永不发起创建）；取不到即丢弃该事件，不斩断流
                SqlLogManager logManager = cn.song.mybatisplus.visualizer.log.SqlLogManagerLookupKt.sqlLogManagerIfCreated(project);
                if (logManager == null) {
                    return;
                }
                LogSqlEntry stamped = LogSqlModelsKt.stampLogTime(entry);
                LogSqlEntry effective = stamped != null ? stamped : logTimeMiss(entry);
                logManager.getEntries().addEntry(effective);
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传
                LOG.warn("EVENT 入队失败（不斩断流）", t);
            }
        } else if (cmd == AgentProtocol.CMD_HEARTBEAT) {
            // T-AgentHeartbeat T-05：HEARTBEAT 是存活帧（EVENT 分支已在上面置位）
            livenessFrameSeen = true;
        } else if (cmd == AgentProtocol.CMD_CONFIG_ACK) {
            // T-AgentConfigChannel T-02：CONFIG 回执——应用侧「已生效」的唯一直接证据。
            // 与 HEARTBEAT/EVENT 同属存活帧（应用侧真在跑才会发）。
            livenessFrameSeen = true;
            AgentProtocol.ConfigAck ack = AgentProtocol.parseConfigAck(body);
            if (ack == null) {
                LOG.warn("CONFIG 回执解析失败（pid=" + pid + ", 累计失败=" + AgentProtocol.configAckParseFailures.get()
                    + ", bodyLen=" + body.length() + ", head=" + take(body, 120)
                    + "）——两侧回执形态漂移，按未确认处理");
                return;
            }
            configAckCount.incrementAndGet();
            clearPendingConfigAck();
            // 追加 P-5：确认值写入（桥闸门唯一真值来源）——disabled=false 才意味着「应用侧真在采集」
            configAckDisabled = ack.disabled();
            LOG.info("CONFIG 已生效（pid=" + pid + ", ACK#" + configAckCount.get() + "）：disabled=" + ack.disabled()
                + ", desensitize=" + ack.desensitize() + ", skipPrefixes=" + ack.skipPrefixes()
                + ", bizJarPrefixes=" + ack.bizJarPrefixes());
            // 追加 P-5：回执是桥闸门的唯一依据——收到即同步（不能在等下一次 updateOnline 才刷）
            onCaptureConfirmationChanged.run();
        }
        // HELLO/未知命令：lastSeen 已刷新；HELLO 只是连接首帧、不算存活帧；未知命令忽略（协议向前兼容）
    }

    /**
     * T-AgentLogTime：日志时间未命中留痕——前 {@link #MISS_LOG_LIMIT} 条逐条打印键与 SQL 头部
     * （判「两侧 rawSql 不等」），其后每 {@link #MISS_LOG_STEP} 条打一次累计（判「时序赶不上」）。
     * 未命中只影响时间字段（回落 execute 开始时刻），不影响条目本身，故只记日志不动流程。
     */
    private LogSqlEntry logTimeMiss(LogSqlEntry entry) {
        long n = timeMiss.incrementAndGet();
        if (n <= MISS_LOG_LIMIT) {
            LOG.info(
                "日志时间未命中 #" + n + "（回落捕获时间）：key=" + LogSqlModelsKt.logTimeKey(entry)
                    + " params=" + take(LogSqlModelsKt.logTimeParamSignature(entry.params()), 60)
                    + " sql=" + take(entry.rawSql(), 80));
        } else if (n % MISS_LOG_STEP == 0L) {
            LOG.info("日志时间未命中累计 " + n + " 条（最近：key=" + LogSqlModelsKt.logTimeKey(entry)
                + " 相关器存量=" + LogTimeCorrelator.size() + "）");
        }
        return entry;
    }

    public void sendConfig(boolean disabled) {
        sendConfig(disabled, PluginSettings.getInstance().getState().getEnhancedCaptureDesensitize());
    }

    public void sendConfig(boolean disabled, boolean desensitize) {
        try {
            // T-SourceJumpFunnel P1-3：随 CONFIG 附带用户登记的帧过滤前缀（热下发；旧 agent 忽略该键）
            List<String> prefixes = new ArrayList<>();
            for (String line : PluginSettings.getInstance().getState().getSourceFrameSkipPrefixes().lines().toList()) {
                if (!line.isBlank()) {
                    prefixes.add(line);
                }
            }
            // T-ToolSiteBizFrame：随 CONFIG 附带 JAR 业务帧反向名单（语义与跳过前缀相反，热下发）
            List<String> bizPrefixes = new ArrayList<>();
            for (String line : PluginSettings.getInstance().getState().getSourceFrameBizJarPrefixes().lines().toList()) {
                if (!line.isBlank()) {
                    bizPrefixes.add(line);
                }
            }
            // T-StackHoverUi：随 CONFIG 热下发调用栈快照帧数预算（设置改动即生效，无需重附 agent）
            int stackMaxFrames = PluginSettings.getInstance().getState().getEnhancedCaptureStackMaxFrames();
            byte[] body = AgentProtocol.configBody(disabled, desensitize, prefixes, bizPrefixes, stackMaxFrames)
                .getBytes(StandardCharsets.UTF_8);
            synchronized (output) {
                output.writeInt(body.length);
                output.write(AgentProtocol.CMD_CONFIG);
                output.write(body);
                output.flush();
            }
            // 追加 P-5：下发即转「待确认」——应用侧是否真的应用了这次 CONFIG，只有回执说了算。
            // 随后立即重算桥闸门：待确认（null）与「确认停采」在闸门上一视同仁，都不封桥——
            // 这是有意的取侧：宁可在一个 ACK 往返（毫秒级、且列表去重窗口会折叠同指纹条目）里
            // 短暂双通道产出，也不接受「桥封着 + 应用侧静默 = 零捕获且无任何可见信号」。
            // 注意次序：先清确认态再排超时巡检（巡检到点时读的是清空后的挂起，语义一致）。
            configAckDisabled = null;
            onCaptureConfirmationChanged.run();
            markConfigSent(disabled);
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t; // T-FP-02：fatal 透传（原状：静默置 alive=false，致命错误会丢现场）
            alive = false;
            // T-AgentConfigChannel T-02：写失败即掉线——挂起与确认态一并清（无回执可等，也不该拿旧确认当真）
            clearPendingConfigAck();
            configAckDisabled = null;
            onStateChange.run();
        }
    }

    public void close() {
        alive = false;
        // T-AgentConfigChannel T-02：挂起/确认态按会话——关闭即清，不残留到重连后的新会话
        clearPendingConfigAck();
        configAckDisabled = null;
        try {
            socket.close();
        } catch (Throwable ignored) {
            // 对齐 Kotlin runCatching：关闭失败不影响状态翻转
        }
        onStateChange.run();
    }

    /** Kotlin String.take(n) 等价：取前 n 个字符（不足则全量）。 */
    private static String take(String s, int n) {
        return s.substring(0, Math.min(n, s.length()));
    }
}
