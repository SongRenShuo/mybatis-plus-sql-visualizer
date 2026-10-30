package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.util.StackLocator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 发送线程主循环（§4.3）：loopback TCP 长度前缀帧（4 字节 len + 1 字节 cmd + JSON body）。
 * 连接后先发 HELLO（pid 标识）并**补发一条 HEARTBEAT**（IDE 在线态自连接起即新鲜）；
 * 事件即时 flush；出站静默达心跳间隔（默认 10s）发 HEARTBEAT（IDE 在线态判定）；
 * 入站 CONFIG（禁用/脱敏开关）由同连接读取线程下发 AgentState。断线退避重连（1s→5s 封顶）。
 *
 * T-AgentHeartbeat T-01：心跳节奏改按**真实流逝时间**计。此前用「假定每轮 200ms」累加计时
 * （`idleMs += 200`），而每轮真实耗时≈1.2s（入站 CONFIG 读超时 1s + 轮询睡眠 200ms），
 * 于是「静默 10s 发心跳」实际要 50 轮 ≈ 60s——首条心跳永远赶不上 IDE 侧 30s 在线窗口，
 * 增强捕获会话建立后 30 秒必被摘除（实测：HELLO +17ms，首条 HEARTBEAT +60633ms）。
 * 现以 [lastOutboundMs]（最近一次出站帧的时刻）为准，每轮比较墙钟。
 *
 * T-PerfSweep6 T-PS6-05：退避复位点由「连上即复位」后移到「连接存活超过 [BACKOFF_RESET_AFTER_MS]
 * 才复位」，并对连续「连上即被关」计满 [MAX_FAST_DROPS] 次后放弃该端口（性能报告发现 5 / 发现 9）。
 *
 * T-PerfSweep7 T-PS7-02：上述「存活时长」由拨号发起前（attemptStartMs）改按**建连成功时刻**
 * （connectedAtMs）起算——new Socket 无 connect 超时，慢拨号（远端不可达时握手挂数秒）会把
 * 「连上即被关」撑过复位阈值，被误判成正常掉线而每轮复位退避与 fastDrops；拨号提为包内可注入
 * 函数缝 [SocketDialer]（默认真拨号，不新增生产可配置面）。帧协议不动。
 *
 * T-AgentConfigChannel T-01：入站读序对齐为 `[4 字节 len][1 字节 cmd][body]`（与出站、与 IDE 侧
 * 读写完全同形）。此前入站先读 1 字节当命令、再读 4 字节当长度——IDE 下发的 CONFIG 被解析成
 * cmd=0 丢弃、随后按错位长度（实测 9475）阻塞读超时被静默吞掉，控制通道等于完全不通，
 * AgentState.disabled 永远停在 fail-open 初值。畸形长度现在**抛异常走重连**并 WARN 记长度与首字节。
 * T-02：CONFIG 应用成功回一帧 {@link EventBus#CMD_CONFIG_ACK}（「生效」不再靠推断）。
 * T-07：会话建立后 X 毫秒内从未收到任何 CONFIG → 主动停采 + 一次性 WARN（每连接一次，不新开线程）。
 */
final class SenderLoop implements Runnable {

    /**
     * 默认心跳间隔（§4.3 静默 10s）。
     * IDE 侧 `AgentProtocol.ONLINE_WINDOW_MS` 由同值推导（3 倍余量）——两侧常量由
     * `AgentProtocolSyncTest` 源码扫描锁定（`window >= 3 * interval`），任一侧漂移即转红。
     */
    static final long DEFAULT_HEARTBEAT_MS = 10_000L;

    /** 心跳间隔注入点（毫秒；缺省/非法/非正数回落 [DEFAULT_HEARTBEAT_MS]，生产行为不变）。 */
    static final String HEARTBEAT_MS_PROP = "sqlvis.agent.heartbeatMs";

    /** 入站帧 body 长度上限（1MB）：超限即视为流错位/协议漂移，抛异常走重连而非阻塞读。 */
    static final int MAX_FRAME_LEN = 1 << 20;

    /** 未知入站命令累计数（T-01：错位解析成 cmd=0 正是这种形态，须可观测；计数不封顶）。 */
    private static final AtomicLong UNKNOWN_INBOUND = new AtomicLong();

    /** 未知命令告警一次性标志（防流错位/未来新命令刷屏宿主日志；计数照常累加）。 */
    private static final AtomicBoolean UNKNOWN_WARNED = new AtomicBoolean();

    /** CONFIG 看门狗窗口注入点（毫秒；缺省/非法/非正数回落 [DEFAULT_CONFIG_WATCHDOG_MS]）。 */
    static final String CONFIG_WATCHDOG_MS_PROP = "sqlvis.agent.configWatchdogMs";

    /**
     * CONFIG 看门狗默认窗口（T-AgentConfigChannel T-07，设计定案 D2）。
     * 语义：会话建立后这么久**从未收到任何 CONFIG** → 主动停采。取值权衡：
     * 保留「启动即捕获」的启动期收益（IDE 侧 CONFIG 在下发时机上从不是「秒级必达」，
     * 会话校正/心跳都可能晚几秒），同时把「控制通道再出任何问题」的损害从
     * **无界**（本轮现场：整场会话静默采集）压到 X 秒。
     */
    static final long DEFAULT_CONFIG_WATCHDOG_MS = 15_000L;

    /** 空转轮询节奏（决定事件轮询响应性）：每轮最多睡这么久，且不得跨过下一次心跳时刻。 */
    private static final long POLL_IDLE_MS = 200L;

    /** 入站 CONFIG 读超时上限（§4.3 轮询式短超时；默认心跳间隔下即 1s，行为与修复前一致）。 */
    private static final int CONFIG_READ_TIMEOUT_MAX_MS = 1000;

    /** 入站读超时下限（防注入极小间隔时退化为忙轮询）。 */
    private static final int CONFIG_READ_TIMEOUT_MIN_MS = 50;

    /** 初始退避（毫秒；与修复前逐值一致）。 */
    private static final long BACKOFF_INITIAL_MS = 1_000L;

    /** 退避上限（毫秒；与修复前逐值一致）。 */
    private static final long BACKOFF_MAX_MS = 5_000L;

    /**
     * 退避复位阈值（毫秒，T-PerfSweep6 T-PS6-05）。
     *
     * **复位点后移**：此前 `backoffMs = 1000` 打在「socket 连上」处——只要 TCP 握手成功就算成功，
     * 于是「连上即被关」（attach 端口 accept 后即释放，或端口被无关进程复用）这种**必然失败**的
     * 连接每轮都把退避打回 1s，agent 以固定短节奏对着一个不存在的对端写帧（性能报告发现 9）。
     * 现在改为：连接**存活**超过本阈值才算「一次正常掉线」，才复位退避与快速断开计数
     * （T-PerfSweep7 T-PS7-02：存活自建连成功时刻起算，不含拨号耗时）。
     * 取值 2s 远小于任何真实会话寿命（IDE 侧在线窗口 30s），故正常路径的复位时机体感不变
     * （正常连接掉线时阈值必已满足，退避照旧回到 1s）。
     */
    private static final long BACKOFF_RESET_AFTER_MS = 2_000L;

    /**
     * 连续「连上即被关」次数上限（T-PerfSweep6 T-PS6-05 / 性能报告发现 9）。
     *
     * 达到即**放弃该端口**并退出重连循环：连续这么多次「握手成功、随即断开」已不是「IDE 侧还没起来」
     * 而是「这个端口上坐的不是本插件的监听者」（端口复用）——继续按 1s→5s 退避写帧，轻则空转，
     * 重则向无关进程投递帧。仅统计**已建连后快速断开**者；`Connection refused` 一类不算（那是
     * 「IDE 侧端口尚未就绪」的正常早期形态，须继续重试）。
     *
     * 放弃重试是安全的：通道一旦关闭（运行结束 / 上限 / 端口被复用），同端口不会再出现本插件的
     * 监听者，重试本就无意义（新一次 Run 会前置协商**新**端口并重新注入 agent）。
     */
    private static final int MAX_FAST_DROPS = 10;

    private final int serverPort;
    private final long backoffInitialMs;
    private final long backoffMaxMs;
    private final long backoffResetAfterMs;
    private final int maxFastDrops;
    /** 拨号函数缝（T-PerfSweep7 T-PS7-02）：默认真拨号，仅同包测试构造器可达（不新增生产可配置面）。 */
    private final SocketDialer dialer;

    SenderLoop(int serverPort) {
        this(serverPort, BACKOFF_INITIAL_MS, BACKOFF_MAX_MS, BACKOFF_RESET_AFTER_MS, MAX_FAST_DROPS);
    }

    /**
     * 退避参数注入构造器（同包测试缝）：生产路径一律走单参构造器 + 上列常量。
     * 退避节奏是秒级，靠系统属性压时间会给生产侧多出一个可配置面，故改为构造器注入。
     */
    SenderLoop(int serverPort, long backoffInitialMs, long backoffMaxMs, long backoffResetAfterMs, int maxFastDrops) {
        this(serverPort, backoffInitialMs, backoffMaxMs, backoffResetAfterMs, maxFastDrops, DEFAULT_DIAL);
    }

    /**
     * 拨号注入构造器（T-PerfSweep7 T-PS7-02，同包测试缝）：在退避参数之上可注入慢拨号，
     * 用于证明「拨号耗时不得计入连接存活时长」；生产路径一律走单参构造器 + [DEFAULT_DIAL]。
     */
    SenderLoop(int serverPort, long backoffInitialMs, long backoffMaxMs, long backoffResetAfterMs, int maxFastDrops,
            SocketDialer dialer) {
        this.serverPort = serverPort;
        this.backoffInitialMs = backoffInitialMs;
        this.backoffMaxMs = backoffMaxMs;
        this.backoffResetAfterMs = backoffResetAfterMs;
        this.maxFastDrops = maxFastDrops;
        this.dialer = dialer;
    }

    /** 拨号函数缝：与被替换的 {@code new Socket(host, port)} 逐字同形（生产缺省实现）。 */
    interface SocketDialer {
        Socket dial(String host, int port) throws IOException;
    }

    /** 默认拨号：真拨号（与 T-PS7-02 改造前同一行代码，零行为变化）。 */
    private static final SocketDialer DEFAULT_DIAL = new SocketDialer() {
        @Override
        public Socket dial(String host, int port) throws IOException {
            return new Socket(host, port);
        }
    };

    /** 心跳间隔：系统属性 [HEARTBEAT_MS_PROP] 优先，解析失败/非正数回落 [DEFAULT_HEARTBEAT_MS]。 */
    static long resolveHeartbeatIntervalMs() {
        try {
            final String raw = System.getProperty(HEARTBEAT_MS_PROP);
            if (raw != null) {
                final long v = Long.parseLong(raw.trim());
                if (v > 0L) {
                    return v;
                }
            }
        } catch (Throwable ignore) {
            // 非法值回落默认（不因测试注入失误影响生产节奏）
        }
        return DEFAULT_HEARTBEAT_MS;
    }

    /**
     * CONFIG 看门狗窗口：系统属性 [CONFIG_WATCHDOG_MS_PROP] 优先，解析失败/非正数回落
     * [DEFAULT_CONFIG_WATCHDOG_MS]（注入失误不得改变生产行为）。
     */
    static long resolveConfigWatchdogMs() {
        try {
            final String raw = System.getProperty(CONFIG_WATCHDOG_MS_PROP);
            if (raw != null) {
                final long v = Long.parseLong(raw.trim());
                if (v > 0L) {
                    return v;
                }
            }
        } catch (Throwable ignore) {
            // 非法值回落默认
        }
        return DEFAULT_CONFIG_WATCHDOG_MS;
    }

    /**
     * 入站轮询读超时：取心跳间隔的 1/3（上下限 [CONFIG_READ_TIMEOUT_MIN_MS]~[CONFIG_READ_TIMEOUT_MAX_MS]）。
     * 读阻塞会把心跳判定推迟一整轮，故读超时必须显著小于心跳间隔；默认间隔下 10000/3 被上限钳到
     * 1000ms——与修复前逐值一致，生产行为不变。
     */
    static int configReadTimeoutMs(long heartbeatIntervalMs) {
        long t = heartbeatIntervalMs / 3L;
        if (t > CONFIG_READ_TIMEOUT_MAX_MS) {
            t = CONFIG_READ_TIMEOUT_MAX_MS;
        }
        if (t < CONFIG_READ_TIMEOUT_MIN_MS) {
            t = CONFIG_READ_TIMEOUT_MIN_MS;
        }
        return (int) t;
    }

    @Override
    public void run() {
        long backoffMs = backoffInitialMs;
        int fastDrops = 0;
        while (true) {
            Socket socket = null;
            boolean connected = false;
            // T-PerfSweep7 T-PS7-02：连接成功那一刻另记（拨号返回后），复位与 fastDrops 的
            // 「存活时长」判据改按本时刻起算——attemptStartMs 把**拨号耗时**也算进存活，
            // 而 new Socket 无 connect 超时，远端网络不可达时握手可挂数秒：慢拨号 + 连上即被关
            // 会被误判成「存活过阈值的正常掉线」而每轮复位退避与 fastDrops（永不放弃端口）。
            long connectedAtMs = 0L;
            try {
                socket = dialer.dial("127.0.0.1", serverPort);
                connected = true;
                connectedAtMs = System.currentTimeMillis();
                final long heartbeatIntervalMs = resolveHeartbeatIntervalMs();
                final long watchdogMs = resolveConfigWatchdogMs();
                socket.setSoTimeout(configReadTimeoutMs(heartbeatIntervalMs));
                socket.setTcpNoDelay(true);
                // T-PerfSweep6 T-PS6-05：此处**不再**复位退避——「连上」不等于「连对」，
                // 复位点后移到 catch 块的「存活超过 [backoffResetAfterMs]」分支。

                final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                final DataInputStream in = new DataInputStream(socket.getInputStream());
                writeFrame(out, EventBus.CMD_HELLO, "{\"pid\":" + pid() + "}");
                // 连接建立即补发一条心跳：即便首轮迭代被 CONFIG 读超时拖住，IDE 侧 lastSeen 也从头新鲜
                writeFrame(out, EventBus.CMD_HEARTBEAT, heartbeatBody());
                long lastOutboundMs = System.currentTimeMillis();
                // T-07 看门狗：本连接是否收到过任何 CONFIG / 本连接是否已触发（每条连接只触发一次）。
                // T-PS7-02 起与复位判据共用 [connectedAtMs]（同一时刻：拨号返回即连接成功，
                // 与写 HELLO 后的差是毫秒级，对 15s 窗口语义无差）。
                boolean configSeen = false;
                boolean watchdogFired = false;

                final cn.song.mybatisplus.sqlvis.agent.model.SqlEvent[] slot =
                        new cn.song.mybatisplus.sqlvis.agent.model.SqlEvent[1];
                while (true) {
                    // 1) 采集事件尽出（每次最多 64 条防饿死心跳）；单事件编码/写失败只丢该事件
                    //    （IO 异常外抛走重连；编码异常跳过不断连接）
                    //    T-01b：停采态短路——即便队列里还有残留（下降沿清空与并发入队之间的竞态窗口），
                    //    也不在停采期间往外投递（否则面板会在「已停采」后冒条目）
                    //
                    //    已知边界（有意保持现状，勿顺手「修」）：尽出排在读帧之前，故**停采帧到达的那一轮**
                    //    仍可能先写出 ≤64 条存量事件。这些事件发生在用户点停之前，语义上合法；且 IDE 侧
                    //    入队闸门（T-03，capturing=false 丢弃）会兜住，用户可见结果正确。反过来把读帧提到
                    //    尽出之前，会让每次事件投递都多等一个读超时（默认 1s），得不偿失。
                    int burst = 0;
                    if (!AgentState.isDisabled()) {
                        while (burst < 64 && EventBus.pollEvent(slot, 0)) {
                            try {
                                writeFrame(out, EventBus.CMD_EVENT, EventBus.encodeEvent(slot[0]));
                                burst++;
                                lastOutboundMs = System.currentTimeMillis();
                            } catch (java.io.IOException io) {
                                throw io;
                            } catch (Throwable t) {
                                // 编码失败：丢弃该事件继续（EventBus.droppedCount 可观测）
                            }
                        }
                    }
                    // 2) 心跳：按真实流逝时间判（每轮都执行，不被 burst 分支包住——事件突发期间也按节奏发）
                    if (System.currentTimeMillis() - lastOutboundMs >= heartbeatIntervalMs) {
                        writeFrame(out, EventBus.CMD_HEARTBEAT, heartbeatBody());
                        lastOutboundMs = System.currentTimeMillis();
                    }
                    // 3) 入站帧（非阻塞轮询：超时=无数据）——[len][cmd][body]，畸形帧抛异常走重连
                    try {
                        final InboundFrame frame = readInboundFrame(in);
                        if (frame.cmd == EventBus.CMD_CONFIG) {
                            configSeen = true;
                        }
                        final String ack = handleInbound(frame.cmd, frame.payload);
                        if (ack != null) {
                            // T-02：CONFIG 生效回执（IDE 侧据此判「应用侧确已收到并生效」）
                            writeFrame(out, EventBus.CMD_CONFIG_ACK, ack);
                            lastOutboundMs = System.currentTimeMillis();
                        }
                    } catch (java.net.SocketTimeoutException ignore) {
                        // 无入站数据
                    }
                    // 3.5) T-07 看门狗：会话建立后 X ms 内从未收到任何 CONFIG → 主动停采（每连接一次）
                    //      用既有循环判，不新开线程；停采走 AgentState.setDisabled（顺带清队列，T-01b）
                    if (!configSeen && !watchdogFired
                            && System.currentTimeMillis() - connectedAtMs >= watchdogMs) {
                        watchdogFired = true;
                        System.err.println("[T-AgentConfigChannel] 会话建立后 " + watchdogMs
                                + "ms 内未收到任何 CONFIG，按保守策略主动停采（fail-open 看门狗，"
                                + "本连接仅此一条告警；若为控制通道失效，IDE 侧会同时表现为"
                                + "「已下发但无 CONFIG ACK」）");
                        AgentState.setDisabled(true);
                    }
                    // 4) 空转轮询节奏（有事件突发时不睡，保持事件吞吐）；
                    //    睡眠不得跨过下一次心跳时刻，否则读阻塞会把心跳推后一整轮
                    if (burst == 0) {
                        final long remainMs = heartbeatIntervalMs
                                - (System.currentTimeMillis() - lastOutboundMs);
                        final long sleepMs = Math.max(0L, Math.min(POLL_IDLE_MS, remainMs));
                        if (sleepMs > 0L) {
                            Thread.sleep(sleepMs);
                        }
                    }
                }
            } catch (Throwable t) {
                closeQuietly(socket);
                // T-PerfSweep6 T-PS6-05：退避复位点后移 + 「连上即被关」连续计数。
                // 判据只看「是否建连成功」与「存活时长」，与异常类型无关（断开形态多样：
                // EOFException / SocketException / 读超时后写失败…，逐类型分支必漏）。
                // T-PerfSweep7 T-PS7-02：存活自 [connectedAtMs]（建连成功时刻）起算，
                // 不含拨号耗时——退回 attemptStartMs（拨号发起前）起算即把慢拨号误判成正常掉线。
                final long aliveMs = System.currentTimeMillis() - connectedAtMs;
                if (connected && aliveMs < backoffResetAfterMs) {
                    // 连上即被关：**不复位**退避（下一轮等待翻倍），并累计快速断开计数
                    fastDrops++;
                    if (fastDrops >= maxFastDrops) {
                        // 复核 minor㉚：放弃是**单向且不可逆**的，而通道此刻已死、IDE 侧拿不到任何信号，
                        // 故把「已放弃」变成可观测事实——① 稳定签名行（支持排障按 PORT_ABANDONED 直接 grep
                        // 宿主应用日志）；② AgentState 记账（本进程内可读，不必靠「面板没条目」反推）。
                        AgentState.markPortAbandoned(serverPort);
                        System.err.println("[SqlVisAgent] SenderLoop: PORT_ABANDONED port=" + serverPort
                                + " fastDrops=" + fastDrops
                                + " —— 连续「连上即被关」达上限，放弃该端口重试"
                                + "（该端口上的监听者已不是本插件，继续写帧只会空转或投错对端；"
                                + "IDE 侧监听端口是临时端口，放弃后本会话不会再上报）");
                        return;
                    }
                } else if (connected) {
                    // 连接存活过阈值 = 一次正常掉线：退避与快速断开计数一并复位
                    backoffMs = backoffInitialMs;
                    fastDrops = 0;
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, backoffMaxMs);
            } finally {
                closeQuietly(socket);
            }
        }
    }

    /**
     * 心跳 body（T-CaptureCatchAll 券 03 的 agent 侧出口）：带出织入/接口匹配的进程累计计数。
     *
     * <p><b>向后兼容</b>：此前心跳 body 恒为 {@code "{}"}，IDE 侧
     * （{@code AgentSessionSupport}）对 HEARTBEAT 只做「存活帧」判定、**不解析 body**，故追加键
     * 不改变任何既有语义；C2 的覆盖自检上报可按同一字面取值，无需新增命令号（新命令要动两侧
     * 协议常量与 {@code AgentProtocolSyncTest} 守卫）。
     *
     * <p>字段语义（与 IDE 侧 {@code AgentAttachStats} 的宽容扫描口径逐键对齐）：
     * {@code weavable/woven/failed} 与 attach 日志同源（transformer 四计数中的三个），
     * {@code matched} = 白名单轮命中 + 接口兜底命中（「本次 attach 命中多少」的唯一完整口径，
     * 旧口径只在宿主 stderr 有），{@code unmodifiable} = 两轮合计不可重转换数，
     * {@code matchedByInterface/interfaceWoven} 为接口兜底轮读数，{@code skippedByVersion} 为
     * major 版本闸挡下的类数，{@code catchAll} 为兜底状态（0=只白名单，1=transformer 兜底开，
     * 2=已熔断）。键序固定、无空格——与 CONFIG ACK body 同一书写纪律。
     *
     * <p>任何异常回落 {@code "{}"}：心跳是 IDE 侧在线判定的唯一凭据，诊断字段的失败绝不能让
     * 心跳帧本身丢掉。
     */
    public static String heartbeatBody() {
        try {
            return "{\"weavable\":" + cn.song.mybatisplus.sqlvis.agent.SqlHookTransformer.weavableCount()
                    + ",\"woven\":" + cn.song.mybatisplus.sqlvis.agent.SqlHookTransformer.wovenCount()
                    + ",\"failed\":" + cn.song.mybatisplus.sqlvis.agent.SqlHookTransformer.failedCount()
                    + ",\"matched\":" + cn.song.mybatisplus.sqlvis.agent.CaptureBudget.attachMatchedTotal()
                    + ",\"unmodifiable\":"
                    + (cn.song.mybatisplus.sqlvis.agent.CaptureBudget.attachUnmodifiableCount()
                        + cn.song.mybatisplus.sqlvis.agent.CaptureBudget.interfaceSkippedCount())
                    + ",\"matchedByInterface\":"
                    + cn.song.mybatisplus.sqlvis.agent.CaptureBudget.interfaceMatchedCount()
                    + ",\"interfaceWoven\":"
                    + cn.song.mybatisplus.sqlvis.agent.CaptureBudget.interfaceWovenCount()
                    + ",\"skippedByVersion\":"
                    + cn.song.mybatisplus.sqlvis.agent.SqlHookTransformer.skippedByVersionCount()
                    + ",\"catchAll\":" + catchAllState()
                    + "}";
        } catch (Throwable t) {
            return "{}";
        }
    }

    /** 兜底状态码：0=纯白名单（默认），1=transformer 兜底已启用，2=已触发 kill switch。 */
    private static int catchAllState() {
        try {
            if (cn.song.mybatisplus.sqlvis.agent.CaptureBudget.killed()) {
                return 2;
            }
            return cn.song.mybatisplus.sqlvis.agent.CaptureBudget.transformMatchEnabled() ? 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 处理一帧入站命令，返回需回给 IDE 的 ACK body（无需回执返回 null）。
     * 包内可见：帧格式测试据此把「解析 → 应用 → 回执」整链一次走通（不绕过拆帧层）。
     */
    String handleInbound(int cmd, String payload) {
        if (cmd != EventBus.CMD_CONFIG) {
            // T-01：未知命令不再静默 return——本轮缺陷正是「命令被错位解析成 0 后无声丢弃」。
            // 告警只出一条（照 SqlHookCallbacks 的 WARNED_ONCE 范式）：流错位或未来新增命令
            // 会逐帧命中这里，逐帧打印等于刷屏宿主应用日志（比故障本身更糟）；计数不封顶，
            // 读 unknownInboundCount 即可判「偶发一条还是持续」。
            UNKNOWN_INBOUND.incrementAndGet();
            if (UNKNOWN_WARNED.compareAndSet(false, true)) {
                System.err.println("[T-AgentConfigChannel] 忽略未知入站命令 cmd=" + cmd
                        + "（期望 CMD_CONFIG=" + EventBus.CMD_CONFIG + "；仅此一条告警，"
                        + "累计次数见 unknownInboundCount）");
            }
            return null;
        }
        if (payload == null) {
            return null;
        }
        // 极简解析（仅两个布尔键）：{"disabled":true,"desensitize":false}
        if (payload.contains("\"disabled\"")) {
            AgentState.setDisabled(payload.contains("\"disabled\":true")
                    || payload.contains("\"disabled\": true"));
        }
        if (payload.contains("\"desensitize\"")) {
            AgentState.setDesensitize(payload.contains("\"desensitize\":false")
                    || payload.contains("\"desensitize\": false") ? false : true);
        }
        // T-SourceJumpFunnel P1-3：用户登记的帧过滤前缀（"skipPrefixes":["a.","b."]；缺省键=清空）。
        // 极简提取：定位键名后逐项扫描双引号对，支持 \" 转义（IDE 侧同规则转义）。
        if (payload.contains("\"skipPrefixes\"")) {
            StackLocator.setExtraSkipPrefixes(extractStringArray(payload, "\"skipPrefixes\":"));
        } else {
            StackLocator.setExtraSkipPrefixes(new String[0]);
        }
        // T-ToolSiteBizFrame（机制 A）：JAR 业务帧反向名单（"bizJarPrefixes":["a.","b."]；缺省键=清空，
        // 与 skipPrefixes 同语义——IDE 侧空列表省略键）。
        if (payload.contains("\"bizJarPrefixes\"")) {
            StackLocator.setExtraBizJarPrefixes(extractStringArray(payload, "\"bizJarPrefixes\":"));
        } else {
            StackLocator.setExtraBizJarPrefixes(new String[0]);
        }
        // T-StackHoverUi：调用栈快照总帧数（"stackFrames":<整数>）。缺省键=**保持现值**——帧数是
        // 用户显式配置，与名单「缺省键=清空」的语义不同；IDE 侧恒带该键（无空格冒号形态），
        // 非数字/越界值由 AgentState.setStackMaxFrames 钳位兜底。
        if (payload.contains("\"stackFrames\"")) {
            final int keyAt = payload.indexOf("\"stackFrames\":");
            if (keyAt >= 0) {
                int from = keyAt + "\"stackFrames\":".length();
                int to = from;
                while (to < payload.length() && payload.charAt(to) >= '0' && payload.charAt(to) <= '9') {
                    to++;
                }
                if (to > from) {
                    try {
                        AgentState.setStackMaxFrames(Integer.parseInt(payload.substring(from, to)));
                    } catch (NumberFormatException ignore) {
                        // 非法值保持现值（钳位入口亦兜底）
                    }
                }
            }
        }
        AgentState.markConfigApplied();
        // T-02：CONFIG 应用成功回执（取值取**应用后**的生效态，含本次生效的前缀条数）
        return configAckBody();
    }

    /** 未知入站命令累计数（T-01 排障观测；告警只出一条，计数逐条累加）。 */
    static long unknownInboundCount() {
        return UNKNOWN_INBOUND.get();
    }

    /**
     * 重置一次性告警标志（仅测试用）：一次性告警是**进程级**状态，用例若不能重置起点，
     * 「只出一条」就无法断言（前序用例一旦触发过，本用例窗口内恒为 0 条）。
     */
    static void resetUnknownCmdWarnOnceForTest() {
        UNKNOWN_WARNED.set(false);
    }

    /**
     * CONFIG 回执 body（T-02 跨侧字面契约，IDE 侧按同一字面解析）：
     * {@code {"disabled":<true|false>,"desensitize":<true|false>,"skipPrefixes":<整数>,
     * "bizJarPrefixes":<整数>}} ——键序固定、无空格、布尔小写字面量；skipPrefixes/bizJarPrefixes
     * 分别为本次生效的帧过滤前缀与 JAR 业务帧反向名单条数（T-ToolSiteBizFrame 追加后段）。
     */
    static String configAckBody() {
        return "{\"disabled\":" + AgentState.isDisabled()
                + ",\"desensitize\":" + AgentState.isDesensitize()
                + ",\"skipPrefixes\":" + StackLocator.getExtraSkipPrefixes().length
                + ",\"bizJarPrefixes\":" + StackLocator.getExtraBizJarPrefixes().length + "}";
    }

    /** 从 CONFIG payload 提取指定字符串数组键的项（无该键/畸形返回空组）。 */
    static String[] extractStringArray(String payload, String key) {
        final int k = payload.indexOf(key);
        if (k < 0) {
            return new String[0];
        }
        int i = k + key.length();
        while (i < payload.length() && payload.charAt(i) != '[') {
            i++;
        }
        if (i >= payload.length()) {
            return new String[0];
        }
        i++; // past '['
        final java.util.List<String> out = new java.util.ArrayList<String>(4);
        final StringBuilder cur = new StringBuilder(32);
        boolean inStr = false;
        boolean escaped = false;
        for (; i < payload.length(); i++) {
            final char c = payload.charAt(i);
            if (inStr) {
                if (escaped) {
                    cur.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inStr = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inStr = true;
            } else if (c == ']') {
                break;
            }
        }
        return out.toArray(new String[0]);
    }

    /** 一帧入站解析结果：命令号 + 已按 UTF-8 解码的 body。 */
    static final class InboundFrame {
        final int cmd;
        final String payload;

        InboundFrame(int cmd, String payload) {
            this.cmd = cmd;
            this.payload = payload;
        }
    }

    /**
     * 读一帧入站：`[4 字节 len][1 字节 cmd][len 字节 body]`（T-AgentConfigChannel T-01）。
     *
     * <p>与出站 {@link #writeFrame} 及 IDE 侧 {@code AgentSession} 读写**同形**——此前本方法
     * 的调用点先读 1 字节当命令再读 4 字节当长度，IDE 下发的 CONFIG 因此被解析成 cmd=0 丢弃、
     * 并按错位长度阻塞读超时后静默吞掉（真机实测 len=9475，body 实为 37 字节）。
     *
     * <p>帧头逐字节读（与 {@code DataInputStream.readInt} 同为最多 4 次 read，不多一次系统调用），
     * 换来的鉴别力是「完全无数据」与「帧读到一半」必须分开处理：前者是空闲轮询（读超时上抛，
     * 调用方按无入站数据处理），后者说明流已错位——**必须抛非超时 IOException 走重连**，
     * 否则每一帧都会按错位长度继续解析下去（旧实现在这里把两者混为一谈，正是静默吞的入口）。
     *
     * <p>长度非法（≤0 或 > [MAX_FRAME_LEN]）与半截帧都 WARN 记长度与首字节后抛 IOException。
     */
    static InboundFrame readInboundFrame(DataInputStream in) throws IOException {
        int len = 0;
        int firstByte = -1;
        for (int i = 0; i < 4; i++) {
            final int b;
            try {
                b = in.readUnsignedByte();
            } catch (java.net.SocketTimeoutException idle) {
                if (i == 0) {
                    throw idle; // 一个字节都没到：空闲轮询，调用方按「无入站数据」处理
                }
                throw partialFrame("帧头不完整（读到 " + i + "/4 字节后超时）", len, firstByte, idle);
            }
            if (i == 0) {
                firstByte = b;
            }
            len = (len << 8) | b;
        }
        if (len <= 0 || len > MAX_FRAME_LEN) {
            System.err.println("[T-AgentConfigChannel] 入站帧长度非法，会话将重连（不再按读超时静默吞）: len="
                    + len + " (0x" + Integer.toHexString(len) + "), 首字节=0x"
                    + Integer.toHexString(firstByte) + ", 上限=" + MAX_FRAME_LEN);
            throw new IOException("bad inbound frame len " + len + " (first byte 0x"
                    + Integer.toHexString(firstByte) + ")");
        }
        final int cmd;
        try {
            cmd = in.readUnsignedByte();
        } catch (java.net.SocketTimeoutException truncated) {
            throw partialFrame("命令字节未到（帧头声称 body " + len + " 字节）", len, firstByte, truncated);
        }
        final byte[] body = new byte[len];
        try {
            in.readFully(body);
        } catch (java.io.IOException truncated) {
            throw partialFrame("body 未读完（帧头声称 " + len + " 字节）", len, firstByte, truncated);
        }
        return new InboundFrame(cmd, new String(body, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 半截帧统一出口：WARN（含长度与首字节）+ 包装成**非超时** IOException。
     * 包装是语义要求而非形式：SocketTimeoutException 在外层被当作「无入站数据」吞掉，
     * 半截帧若原样上抛就等于回到旧实现在读超时里静默吞掉故障的老路。
     */
    private static IOException partialFrame(String what, int len, int firstByte, IOException cause) {
        System.err.println("[T-AgentConfigChannel] 入站帧不完整，会话将重连（不再按读超时静默吞）: " + what
                + ", len=" + len + " (0x" + Integer.toHexString(len) + "), 首字节=0x"
                + Integer.toHexString(firstByte) + ", 原因=" + cause);
        return new IOException("partial inbound frame: " + what, cause);
    }

    static void writeFrame(DataOutputStream out, int cmd, String payload) throws IOException {
        final byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(body.length);
        out.writeByte(cmd);
        out.write(body);
        out.flush();
    }

    private static long pid() {
        try {
            final String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            final int at = name.indexOf('@');
            return at < 0 ? 0 : Long.parseLong(name.substring(0, at));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignore) {
            }
        }
    }
}
