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

    public static synchronized void start(int serverPort) {
        if (started) {
            return;
        }
        port = serverPort;
        started = true;
        final Thread sender = new Thread(new SenderLoop(serverPort), "sqlvis-agent-sender");
        sender.setDaemon(true);
        sender.start();
    }

    /**
     * 采集线程入口：非阻塞入队（红线：绝不阻塞宿主线程），满则丢事件（计数）不阻塞业务线程。
     *
     * <p>T-SqlListCatchup 券 F2b：队列满时的「降精度不丢信号」两步——
     * ① **同模板聚合**：同模板（同 sql，PreparedStatement 占位符文本即模板）事件在队内只保留
     * 最新一条并累加聚合计数（{@link SqlEvent#aggregatedCount}），「执行了 N 次」的事实经协议键
     * {@code c} 带给 IDE，不再整段盲丢；
     * ② **异模板逐旧**：无同模板候选时逐出**最旧**事件给新模板腾位（保最新弃最旧——最新事实
     * 价值高于最旧），被逐出者计丢弃。两步任何竞争失败回落原语义：计数丢弃，绝不重试阻塞。
     */
    public static void offer(SqlEvent event) {
        if (event == null) {
            DROPPED.incrementAndGet();
            return;
        }
        if (QUEUE.offer(event)) {
            return;
        }
        if (!tryAggregateOnFull(event) && !evictOldestAndOffer(event)) {
            DROPPED.incrementAndGet();
        }
    }

    /**
     * 队列满时的同模板聚合（F2b 第①步）：在队内找一条同模板事件，移除后把它的聚合计数并入
     * **最新**事件（即本次入队件，时间戳最新——保留最新不丢最新事实）再重投。非阻塞：全程只用
     * {@code offer}/{@code remove}，任何竞争失败立即返回 false 交由逐旧步/丢弃计数兜底。
     *
     * <p>单次 offer 只折叠一对（队列内可能还有多条同模板存量——风暴期同模板持续到店会随
     * 后续 offer 逐次继续折叠收敛）；迭代为弱一致性快照，与消费线程并发安全（无 CME 面）。
     *
     * @return true = 已聚合入队（本次未丢事件）；false = 无同模板候选或竞争失败
     */
    private static boolean tryAggregateOnFull(SqlEvent event) {
        try {
            final String template = event.sql;
            if (template == null) {
                return false;
            }
            for (SqlEvent queued : QUEUE) {
                if (queued != null && template.equals(queued.sql) && QUEUE.remove(queued)) {
                    event.aggregatedCount = queued.aggregatedCount + 1;
                    return QUEUE.offer(event);
                }
            }
        } catch (Throwable t) {
            // 采集线程红线：聚合路径任何异常不外抛（回落逐旧/丢弃计数，不影响宿主）
        }
        return false;
    }

    /**
     * 队列满且无同模板候选时的逐旧腾位（F2b 第②步）：逐出**最旧**事件（队头）给新模板让位，
     * 被逐出者计丢弃（DROPPED 语义不变 = 未能完好到达 IDE 的事件数）。非阻塞：poll/offer 竞争
     * 失败立即重试一轮（poll 到空说明消费线程刚排空，下一轮 offer 必成功），任何异常回落丢弃。
     *
     * @return true = 新事件已入队（最旧者被逐出并计数）；false = 异常兜底（调用方按丢弃计数）
     */
    private static boolean evictOldestAndOffer(SqlEvent event) {
        try {
            while (true) {
                if (QUEUE.offer(event)) {
                    return true;
                }
                final SqlEvent oldest = QUEUE.poll();
                if (oldest != null) {
                    DROPPED.incrementAndGet();
                }
            }
        } catch (Throwable t) {
            return false;
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
