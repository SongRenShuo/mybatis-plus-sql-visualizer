package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.util.Jsons;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件总线（§4.3 线程模型）：采集线程只入队（有界 2048，满则降精度保全、账本兜底——丢线程不丢事件），
 * 单发送线程消费（连接/重连/心跳/入站 CONFIG）。IO 与业务分离。
 *
 * <p>T-SqlListCatchup 券 I（溢出聚合账本）：队列满时被逐出的事件不再直接计丢弃——其
 * 「模板 → 累计执行次数」先记进一个**有界溢出账本**（{@link #OVERFLOW}，键数封顶
 * {@link #OVERFLOW_MAP_CAPACITY}），发送线程每轮排空队列后把账本转成聚合事件发出
 * （复用协议键 {@code c}，协议零改动）。真丢失只剩「账本也满了收不下新模板」这一种，
 * {@link #droppedCount()} 语义随之收窄为「连计数都未能保全的真丢失」（见该方法 KDoc）。
 *
 * <p>账本淘汰策略取舍（二选一定案）：**不再收新键**（账本满后新模板直接计真丢失，
 * 已有键照常合并）——不选「随机逐一键给新键让位」的近似 LRU：让位需要在宿主采集线程上
 * 做驱逐决策并可能把「热模板」换成「冷模板」，省下的几个冷模板计数抵不上复杂度与
 * 误逐风险；且 512 键 × 模板串长度的内存上界 &lt;1MB，宁可少量真丢失也不加宿主线程成本。
 *
 * <p>宿主线程红线（与 {@code offer()} 同级）：入账路径只允许
 * {@link ConcurrentHashMap#compute}（分段锁、纳秒级、无 sleep/无显式锁等待），
 * 任何异常回落丢弃计数——绝不外抛、绝不阻塞宿主调用帧。
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
    /** Optional v2 quality/control snapshot, emitted directly by sender (never SQL queued). */
    public static final int CMD_CAPTURE_STATE = 6;

    private static final int QUEUE_CAPACITY = 2048;
    private static final BlockingQueue<SqlEvent> QUEUE = new ArrayBlockingQueue<SqlEvent>(QUEUE_CAPACITY);
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong CLEARED = new AtomicLong();

    /**
     * 溢出账本容量（模板键数封顶，券 I）：超限时不再收新键（策略取舍见类 KDoc）。
     * 内存上界 ≈512 × 模板串长度（典型 SQL 模板 &lt;2KB）&lt;1MB；同模板事件无论账本是否满
     * 永远合并进已有键（满只挡新键，不挡合并）。
     */
    static final int OVERFLOW_MAP_CAPACITY = 512;

    /**
     * 溢出账本（券 I）：键 = 模板 sql，值 = 该模板「被逐出且尚未发出」的累计执行次数。
     * 写入方 = 宿主采集线程（{@link #recordEvicted}，仅 {@code compute} 非阻塞）；
     * 移除方 = 唯一发送线程（{@link #drainOverflow}，出账成功才真正离开账本——失败回账下轮重试）。
     */
    private static final ConcurrentHashMap<String, AtomicLong> OVERFLOW =
            new ConcurrentHashMap<String, AtomicLong>();

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
     * 价值高于最旧），被逐出者经 {@link #recordEvicted} 入溢出账本保全部数（券 I），发送线程
     * 排空队列后补发聚合事件；只有账本也满收不下时才计真丢弃（{@link #droppedCount()} 语义）。
     * 两步任何竞争失败回落原语义：计数丢弃，绝不重试阻塞。
     *（车道5 合并注：t-sql-insight 侧的 epoch 门控——停采/换 epoch 后旧事件计 CLEARED 不入队——
     * 与 [SenderWakeup] 唤醒包在外层 offerTracked；两步降级作为「队列满」分支收编，语义并集。）
     */
    public static void offer(SqlEvent event) { offerTracked(event); }

    public static boolean offerTracked(SqlEvent event) {
        //（车道5 合并注：原 t-sql-insight 形态以 CaptureLedger 类锁包本方法——
        // 与 T-SqlListCatchup 入账路径红线「宿主线程绝不阻塞」（EventBusOverflowLedgerTest
        // 静态扫描阻塞原语）相撞；去锁后 epoch 门控改 volatile 无锁读
        // （CaptureLedger.currentEpoch），AgentState.isDisabled 本为 volatile 读，入队/计数/
        // 唤醒（SenderWakeup 的释放原语）均为非阻塞操作。已知取舍：极小概率的「epoch 检查通过后、
        // 入队前恰逢停采沿清队列」窗口会让一条旧 epoch 事件残留队列——SenderLoop 停采短路
        //（T-01b）保证停采期间不外发，恢复后发出时事件自带 captureEpoch 字段可辨旧代。）
        if (event != null && event.captureEpoch != null
                && (cn.song.mybatisplus.sqlvis.agent.AgentState.isDisabled()
                || event.captureEpoch.longValue() != CaptureLedger.currentEpoch())) {
            CLEARED.incrementAndGet();
            return false;
        }
        if (event == null) {
            DROPPED.incrementAndGet();
            return false;
        }
        if (QUEUE.offer(event)) {
            SenderWakeup.signal();
            return true;
        }
        if (!tryAggregateOnFull(event) && !evictOldestAndOffer(event)) {
            DROPPED.incrementAndGet();
            return false;
        }
        SenderWakeup.signal();
        return true;
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
     * 队列满且无同模板候选时的逐旧腾位（F2b 第②步）：逐出**最旧**事件（队头）给新模板让位。
     * 券 I 起被逐出者不再直接计丢弃——先经 {@link #recordEvicted} 入溢出账本（模板计数保全，
     * 发送线程后续补发聚合事件），只有「账本满收不下新模板」或入账异常才转 {@code DROPPED}
     * （真丢失，语义收窄见 {@link #droppedCount()}）。非阻塞：poll/offer 竞争失败立即重试一轮
     * （poll 到空说明消费线程刚排空，下一轮 offer 必成功），任何异常回落丢弃。
     *
     * @return true = 新事件已入队（最旧者被逐出并入账本）；false = 异常兜底（调用方按丢弃计数）
     */
    private static boolean evictOldestAndOffer(SqlEvent event) {
        try {
            while (true) {
                if (QUEUE.offer(event)) {
                    return true;
                }
                final SqlEvent oldest = QUEUE.poll();
                if (oldest != null) {
                    recordEvicted(oldest);
                }
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 被逐事件入账（券 I，宿主采集线程调用——红线内）：把被逐者的累计执行次数
     * （{@code aggregatedCount} ≥1，含聚合代表自身）按模板累入 {@link #OVERFLOW}。
     * 同模板优先级：账本已有同模板键 → 直接合并（同一模板的次数永远合并，无论队列里有没有它）；
     * 新键仅在账本未满时创建，满则计真丢失。全程只有 {@code compute}（分段锁、非阻塞、
     * 无 sleep/无锁等待），任何异常回落丢弃计数——绝不外抛、绝不阻塞宿主。
     */
    private static void recordEvicted(SqlEvent evicted) {
        try {
            final String template = evicted.sql;
            if (template == null) {
                DROPPED.incrementAndGet();
                return;
            }
            final long n = Math.max(1L, (long) evicted.aggregatedCount);
            OVERFLOW.compute(template, (k, v) -> {
                if (v != null) {
                    v.addAndGet(n);
                    return v;
                }
                if (OVERFLOW.size() >= OVERFLOW_MAP_CAPACITY) {
                    DROPPED.incrementAndGet();
                    return null;
                }
                return new AtomicLong(n);
            });
        } catch (Throwable t) {
            // 宿主线程红线：入账任何异常不外抛（该事件转真丢失计数，绝不影响宿主调用帧）
            DROPPED.incrementAndGet();
        }
    }

    /** 账本出账口（券 I）：发送线程注入写帧函数；IOException = 连接已死，账本条目回账待重连后重试。 */
    interface OverflowSink {
        void accept(SqlEvent aggregatedEvent) throws IOException;
    }

    /**
     * 账本出账（券 I，发送线程每轮排空队列后调用）：逐条把「模板 → 累计次数」转成聚合事件
     * 写出。时间戳取**出账时刻**（不记首入时刻——省每键第二份状态；这些本是风暴期溢出事件，
     * 时间精度降级到「出账批次」可接受，换取宿主线程入账路径最简）。出账事件不走队列
     * （直接写帧，不再受 2048 容量约束），协议零改动（聚合计数经既有键 {@code c} 带出）。
     *
     * <p>竞争安全：出账 = 先按键原子摘除（{@code compute} 置 null 捕获最终值——与宿主
     * {@code compute} 入账在同一键上分段锁串行，无丢失更新），后写帧；写帧 IOException =
     * 连接死，条目**回账**（{@link #restoreCount} 无条件并入，下轮重试）后上抛走重连——
     * 「发出成功才离开账本」的语义以「失败必回账」等价达成。非 IO 异常（编码缺陷）该帧按
     * 帧语义计真丢失并继续，不让毒条目卡死出账循环。键集迭代为弱一致快照：出账期间新入账
     * 的键本轮漏发、下轮补发，无丢失面。
     *
     * @return 本轮成功写出的聚合事件帧数（0 = 账本空）
     */
    static int drainOverflow(OverflowSink sink) throws IOException {
        if (OVERFLOW.isEmpty()) {
            return 0; // 空闲热路径（发送循环每轮都调）：账本空时零分配直接返回
        }
        int flushed = 0;
        final String[] keys = OVERFLOW.keySet().toArray(new String[0]);
        for (String key : keys) {
            final long n = removeCount(key);
            if (n <= 0L) {
                continue;
            }
            final SqlEvent agg = new SqlEvent();
            agg.sql = key;
            agg.timestamp = System.currentTimeMillis();
            agg.aggregatedCount = (int) Math.min(n, (long) Integer.MAX_VALUE);
            try {
                sink.accept(agg);
                flushed++;
            } catch (IOException io) {
                restoreCount(key, n);
                throw io;
            } catch (Throwable t) {
                // 编码层缺陷：该聚合帧转真丢失（按帧计），继续出账不让毒条目卡循环
                DROPPED.incrementAndGet();
            }
        }
        return flushed;
    }

    /** 按键原子摘除账本条目并返回最终计数值（与宿主 compute 入账同键串行，无丢失更新）。 */
    private static long removeCount(String template) {
        final AtomicLong[] box = new AtomicLong[1];
        OVERFLOW.compute(template, (k, v) -> {
            box[0] = v;
            return null;
        });
        return box[0] == null ? 0L : box[0].get();
    }

    /** 出账失败回账（发送线程）：无条件并入（不受容量闸限制——这是复原不是新入账）。 */
    private static void restoreCount(String template, long n) {
        try {
            OVERFLOW.compute(template, (k, v) -> {
                if (v != null) {
                    v.addAndGet(n);
                    return v;
                }
                return new AtomicLong(n);
            });
        } catch (Throwable t) {
            // 回账失败（理论边界）：计数放弃（发送线程非宿主，宁可少记不多记）
        }
    }

    /**
     * 累计**真丢失**事件数（券 I 语义收窄）。
     *
     * <p>券 I 前＝「未能完好到达 IDE 的事件数」（队列满即丢、逐旧即丢）；券 I 起队列满时的
     * 被逐事件先入溢出账本保全部数（{@link #drainOverflow} 补发聚合事件），只剩两种情况才计
     * 丢弃：①账本满（512 模板键）收不下**新模板**；②入账/出账路径异常兜底。即「连计数都
     * 未能保全的真丢失」。IDE 状态栏「已丢弃 N 条」（心跳键 {@code dropped}）读数含义随之收窄：
     * 读数 &gt;0 = 风暴已超出「聚合 + 账本」两级保全容量，属于强告警；读数 = 0 不再意味着
     * 「队列从未满过」（满过但全部保全时读数也为 0）。键本身与上报链路不变。
     */
    public static long droppedCount() {
        return DROPPED.get();
    }

    /**
     * 停采下降沿清空已排队事件与溢出账本（T-AgentConfigChannel T-01b）：返回本次清空条数
     * （队列条数 + 账本代表的执行次数），累计条数经 {@link #clearedCount()} 可观测。
     *
     * <p>为什么必须清：队列在「未连接 / 未收到 CONFIG」期间照常入队，连接建立后
     * {@link SenderLoop} 会先尽出队列——不清就会把开关关闭期间采集的存量事件补投到面板，
     * 表现为「点停之后还冒条目」。券 I 起账本同语义：停采后不补投，账本条目**不发出**、
     * 其代表的执行次数一并转入 CLEARED 计数（停采语义不回破）。
     */
    public static int clearQueued() {
        int cleared = 0;
        while (QUEUE.poll() != null) {
            cleared++;
        }
        cleared += clearOverflowLedger();
        if (cleared > 0) {
            CLEARED.addAndGet(cleared);
        }
        return cleared;
    }

    /** 清空溢出账本（停采下降沿）：条目不发出，返回账本代表的执行次数（转入 CLEARED 口径）。 */
    private static int clearOverflowLedger() {
        int events = 0;
        final String[] keys = OVERFLOW.keySet().toArray(new String[0]);
        for (String key : keys) {
            final long n = removeCount(key);
            if (n > 0L) {
                events += (int) Math.min(n, (long) Integer.MAX_VALUE);
            }
        }
        return events;
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

    /** 溢出账本当前模板键数（券 I 测试观测；生产可观测面仍走心跳 dropped 键，不加新键）。 */
    static int overflowTemplateCount() {
        return OVERFLOW.size();
    }

    /** 溢出账本当前代表的累计执行次数（券 I 测试观测：保全总量断言用）。 */
    static long overflowEventTotal() {
        long total = 0L;
        for (AtomicLong v : OVERFLOW.values()) {
            total += v.get();
        }
        return total;
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
