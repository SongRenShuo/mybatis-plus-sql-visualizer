package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.transport.EventBus;

import java.util.concurrent.atomic.AtomicLong;

/**
 * agent 全局态（采集红线 §4.2 #5）：禁用开关 = volatile 短路（纳秒级，全局禁用不卸载语义）。
 * CONFIG 命令（cmd=3）下发时由 EventSender 线程更新。
 *
 * T-AgentConfigChannel T-01b：`disabled` 的**下降沿**（false → true）顺带清空
 * {@link EventBus} 已排队事件——否则停采指令生效瞬间，连接建立前的存量事件仍会被补投到面板
 * （应用侧「真停」就只停住了后续采集，停不住已经排队的）。
 * T-AgentConfigChannel T-07：看门狗在会话建立后迟迟收不到 CONFIG 时会走 {@link #setDisabledByWatchdog()}
 * ——与显式停采同一停采动作，只是额外记下自停归因（T-AgentSilentAfter16s：随心跳 `gate` 键带出）。
 */
public final class AgentState {

    /** 全局禁用开关：true = 采集短路（钩子零开销直返），已 retransform 的类不还原（Instrumentation 不可逆）。 */
    private static volatile boolean disabled = false;

    // ---------- 自停归因（T-AgentSilentAfter16s） ----------

    /**
     * 闸门码：无自停——停采（若发生）是用户在 CONFIG 里显式要求的，不是 agent 自己停的。
     *
     * <p>为什么需要「归因」而不只是一个 boolean：现场（磐维 + GaussDB 验收轮）看到的形态是
     * 「HELLO 之后约 15s 起一条事件都没有，业务请求照旧 200、心跳照发」——它与「钩子坏了」
     * 「SQL 归属丢了」「连接代理没挂上」在面板/探针侧**完全同形**，只能读宿主 stderr 那一条
     * 告警才能归因，于是被当成采集链缺陷查了四轮。把停采原因写进心跳后，对端一条帧就能分开
     * 「agent 自停」与「agent 在采但产不出」。
     */
    public static final int GATE_NONE = 0;

    /**
     * 闸门码：CONFIG 看门狗自停（{@code SenderLoop} 的 T-07：会话建立后窗口内从未收到任何 CONFIG）。
     *
     * <p>语义是**保守停采**（不知道用户是否要求采集 → 不采），不是故障；恢复走既有 CONFIG 通道
     * ——对端一旦下发 CONFIG（含 {@code disabled:false}），{@link #setDisabled(boolean)} 立刻清归因。
     */
    public static final int GATE_CONFIG_WATCHDOG = 1;

    /** 当前自停归因（只在 {@link #disabled} 为真时有意义；{@link #gateCode()} 已做这门控）。 */
    private static volatile int gate = GATE_NONE;

    /** CONFIG 下发的脱敏开关：true = 打码（默认开，数据不出应用 JVM 前已处理）。 */
    private static volatile boolean desensitize = true;

    private static volatile String logLevel = "warn";

    /**
     * 调用栈快照总帧数预算（T-StackHoverUi 用户可配）：StackDigest 两端采样用——栈顶取 B-8 帧
     * （引擎侧）、栈底固定保留 8 帧（业务/Controller 侧）。默认 24，写入即钳位 [12,64]
     * （与 StackDigest.clamp 同口径，读侧再钳一次兜底）。随 attach 参数（stackFrames=k）
     * 与 CONFIG 帧（"stackFrames":k）双通道下发。
     */
    private static volatile int stackMaxFrames = 24;

    /**
     * 已成功应用的 CONFIG 帧数（T-AgentConfigChannel T-02 可观测性）：0 = 控制通道从未通过。
     * 排障时「IDE 说下发了、应用侧说没收到」这类争议，读这个计数即可判定（无需行为反推）。
     */
    private static final AtomicLong CONFIG_APPLIED = new AtomicLong();

    /**
     * 已放弃的端口数与其最后取值（T-Round10 复核 minor㉚，性能报告 T-PS6-05）：连续「连上即被关」达上限后
     * SenderLoop 会**永久放弃**该端口（IDE 侧监听端口是 `ServerSocket(0)` 取的临时端口，同端口不会再出现
     * 本插件监听者，故放弃在功能上是对的）。但放弃是单向的，且通道此刻已死、IDE 侧拿不到任何信号——
     * 唯一可观测面就是本进程：故在此记账，排障时读它即可回答「agent 是不是已经放弃上报了、放弃的是哪个端口」，
     * 不必靠「面板没条目」反推。
     */
    private static final AtomicLong PORT_ABANDONED = new AtomicLong();
    private static volatile int lastAbandonedPort = -1;

    public static boolean isDisabled() {
        return disabled;
    }

    public static void setDisabled(boolean value) {
        applyDisabled(value);
        // 显式开关（CONFIG 下发 / 测试缝）一律清归因：闸门只描述「agent 自己停的那一次」，
        // 用户/IDE 主动停采不属于自停（心跳里报成自停会把排查引向错误方向）。
        gate = GATE_NONE;
    }

    /**
     * 看门狗停采（T-07 的停采动作入口）：语义与 {@link #setDisabled(boolean)} 相同，额外记下归因
     * {@link #GATE_CONFIG_WATCHDOG}，随心跳带出（T-AgentSilentAfter16s）。
     *
     * <p>写入顺序：**先置归因再停采**——心跳线程按 {@link #gateCode()} 读（disabled 门控），
     * 这个顺序保证它不会观察到「已停采但归因为空」的窗口。
     */
    public static void setDisabledByWatchdog() {
        gate = GATE_CONFIG_WATCHDOG;
        applyDisabled(true);
    }

    /**
     * 停采动作本体（不动归因；调用方负责归因写入顺序）。
     *（车道5 合并注：t-sql-insight 侧原在 setDisabled 内以 CaptureLedger 类锁串行开关沿并记
     * captureBoundary——dev 重构出 applyDisabled 后，把该临界区整体移进本体的同一同步块，语义不变。）
     */
    private static void applyDisabled(boolean value) {
        synchronized (cn.song.mybatisplus.sqlvis.agent.transport.CaptureLedger.class) {
            final boolean prev = disabled;
            disabled = value;
            if (prev != value) cn.song.mybatisplus.sqlvis.agent.transport.CaptureLedger.captureBoundary();
            if (!prev && value) {
                // Serialize with identity allocation; in-flight calls retain their entry epoch.
                EventBus.clearQueued();
            }
        }
    }

    /**
     * 当前自停归因码（心跳 {@code gate} 键的取值面）：{@link #GATE_NONE} /
     * {@link #GATE_CONFIG_WATCHDOG}。未停采时恒 0——归因只在「确实停了」时有意义，
     * 这样读取侧不会看到「还在采却自称自停」的中间态。
     */
    public static int gateCode() {
        return disabled ? gate : GATE_NONE;
    }

    public static boolean isDesensitize() {
        return desensitize;
    }

    public static void setDesensitize(boolean value) {
        desensitize = value;
    }

    /** CONFIG 帧应用成功（含 ACK 回执）时调用：计数供排障与测试观测。 */
    public static void markConfigApplied() {
        CONFIG_APPLIED.incrementAndGet();
    }

    /** 已成功应用的 CONFIG 帧数（T-AgentConfigChannel T-02）。 */
    public static long configAppliedCount() {
        return CONFIG_APPLIED.get();
    }

    public static String getLogLevel() {
        return logLevel;
    }

    public static int getStackMaxFrames() {
        return stackMaxFrames;
    }

    public static void setStackMaxFrames(int value) {
        // 钳位与 StackDigest.clamp 同口径（配置入口先行归一，读侧再钳一次兜底）
        stackMaxFrames = Math.max(12, Math.min(64, value));
    }

    /** SenderLoop 放弃某端口时调用（复核 minor㉚）：计数 + 记下最后放弃的端口，供排障与测试观测。 */
    public static void markPortAbandoned(int port) {
        lastAbandonedPort = port;
        PORT_ABANDONED.incrementAndGet();
    }

    /** 已放弃的端口数（0 = 从未放弃过上报通道）。 */
    public static long abandonedPortCount() {
        return PORT_ABANDONED.get();
    }

    /** 最后放弃的端口；从未放弃时为 -1。 */
    public static int lastAbandonedPort() {
        return lastAbandonedPort;
    }

    /** 仅测试用：复位放弃记账，避免同 JVM 内多用例互相污染（与 inScanBudgetForTest 同款测试缝）。 */
    public static void resetPortAbandonedForTest() {
        lastAbandonedPort = -1;
        PORT_ABANDONED.set(0L);
    }

    public static void setLogLevel(String value) {
        logLevel = value == null ? "warn" : value;
    }

    private AgentState() {
    }
}
