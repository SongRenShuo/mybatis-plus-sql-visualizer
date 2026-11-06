package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.util.Jsons;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件总线（§4.3 线程模型）：采集线程只入队（有界 2048，满则丢弃并计数——丢事件不丢线程），
 * 单发送线程消费（连接/重连/心跳/入站 CONFIG）。IO 与业务分离。
 */
public final class EventBus {

    public static final int CMD_EVENT = 1;
    public static final int CMD_HEARTBEAT = 2;
    public static final int CMD_CONFIG = 3;
    public static final int CMD_HELLO = 4;
    /**
     * CONFIG 回执（T-AgentConfigChannel T-02）：应用侧成功应用 CONFIG 后回给 IDE 一帧，
     * body 严格为 {@code {"disabled":…,"desensitize":…,"skipPrefixes":n,"bizJarPrefixes":n}}
     * （键序固定、无空格、布尔小写字面量；bizJarPrefixes=T-ToolSiteBizFrame 追加的 JAR 业务帧
     * 反向名单生效条数）——IDE 侧按同一字面契约解析，字段名与形态不得单侧改动。
     * 旧版 IDE 忽略未知 cmd 天然兼容。
     */
    public static final int CMD_CONFIG_ACK = 5;

    private static final int QUEUE_CAPACITY = 2048;
    private static final BlockingQueue<SqlEvent> QUEUE = new ArrayBlockingQueue<SqlEvent>(QUEUE_CAPACITY);
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong CLEARED = new AtomicLong();

    private static volatile boolean started = false;
    private static volatile int port = 0;

    /** 通道令牌（T-SAF-02）：随 HELLO 帧回带，IDE 侧验令牌通过才提交会话；旧 IDE 不下发 → null。 */
    private static volatile String channelToken = null;

    /** 兼容旧调用方/测试：无令牌启动（HELLO 不带 token 键，与修复前行为逐字一致）。 */
    public static synchronized void start(int serverPort) {
        start(serverPort, null);
    }

    /**
     * T-SAF-02：带通道令牌启动。令牌由 IDE 侧逐通道生成、随 agentArgs 下发（AgentArgs.parse 的
     * {@code token} 键），agent 只做原样回带——验令牌判定位恒在 IDE 侧，agent 侧零状态。
     */
    public static synchronized void start(int serverPort, String token) {
        if (started) {
            return;
        }
        port = serverPort;
        channelToken = token;
        started = true;
        final Thread sender = new Thread(new SenderLoop(serverPort, token), "sqlvis-agent-sender");
        sender.setDaemon(true);
        sender.start();
    }

    /** 采集线程入口：非阻塞入队，满则丢事件（计数）不阻塞业务线程。 */
    public static void offer(SqlEvent event) {
        if (event == null || !QUEUE.offer(event)) {
            DROPPED.incrementAndGet();
        }
    }

    public static long droppedCount() {
        return DROPPED.get();
    }

    /**
     * 停采下降沿清空已排队事件（T-AgentConfigChannel T-01b）：返回本次清空条数，
     * 累计条数经 {@link #clearedCount()} 可观测。
     *
     * <p>为什么必须清：队列在「未连接 / 未收到 CONFIG」期间照常入队，连接建立后
     * {@link SenderLoop} 会先尽出队列——不清就会把开关关闭期间采集的存量事件补投到面板，
     * 表现为「点停之后还冒条目」。
     */
    public static int clearQueued() {
        int cleared = 0;
        while (QUEUE.poll() != null) {
            cleared++;
        }
        if (cleared > 0) {
            CLEARED.addAndGet(cleared);
        }
        return cleared;
    }

    /** 累计被停采下降沿清空的排队事件数（T-AgentConfigChannel T-01b 可观测计数）。 */
    public static long clearedCount() {
        return CLEARED.get();
    }

    /**
     * 当前排队事件数（T-AgentConfigChannel T-01b 测试观测）：用于证明停采期间「队列非空却未写出」，
     * 而不是「队列本来就是空的所以没写出」。
     */
    static int queuedCount() {
        return QUEUE.size();
    }

    static boolean pollEvent(SqlEvent[] out, long timeoutMs) throws InterruptedException {
        final SqlEvent e = QUEUE.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (e == null) {
            return false;
        }
        out[0] = e;
        return true;
    }

    static String encodeEvent(SqlEvent event) {
        return Jsons.writeEvent(event);
    }

    private EventBus() {
    }
}
