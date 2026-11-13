package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-SqlListCatchup 券 F2b：事件队列满时的「降精度不丢信号」行为守卫。
 *
 * <p>真机背景：第三轮压测 ~2500 语句/秒下 2048 队列打满，{@code offer()} 满即丢——真机证据是
 * 状态栏「已收事件 2,227 条」在控制台停输出后仍缓慢上涨（≈队列容量+排空尾量）。丢弃非阻塞
 * 本身是对的（红线：不阻塞宿主），但同模板风暴事件被盲丢等于把「执行了 N 次」整段丢掉。
 *
 * <p>本类钉三件事：①队列满 + 同模板 → 聚合（保留最新、计数累加、零丢弃）；
 * ②队列满 + 异模板 → 逐出最旧给新模板腾位（被逐出者计丢弃）；③offer 全程非阻塞（语义断言：
 * 满队列下 offer 立即返回，任何路径不抛异常不挂起——真阻塞会拖死采集线程所在的宿主调用帧）。
 */
public class EventBusAggregateTest {

    /** 与 EventBus.QUEUE_CAPACITY 同值（改容量须同步本类：填满断言依赖确切值）。 */
    private static final int CAPACITY = 2048;

    @Before
    public void setUp() {
        EventBus.clearQueued();
    }

    @After
    public void tearDown() {
        EventBus.clearQueued();
    }

    private static SqlEvent ev(String sql) {
        final SqlEvent e = new SqlEvent();
        e.sql = sql;
        e.timestamp = System.currentTimeMillis();
        return e;
    }

    private static void fillDistinct(int n) {
        for (int i = 0; i < n; i++) {
            EventBus.offer(ev("tpl-" + i));
        }
    }

    private static List<SqlEvent> drainAll() {
        final List<SqlEvent> out = new ArrayList<>();
        final SqlEvent[] box = new SqlEvent[1];
        try {
            while (EventBus.pollEvent(box, 0)) {
                out.add(box[0]);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AssertionError("drainAll 被中断", ie);
        }
        return out;
    }

    /** ①队列满 + 同模板：聚合为一条（保留最新、计数累加），不产生丢弃、队列仍满。 */
    @Test
    public void 队列满_同模板事件聚合不丢执行次数() {
        fillDistinct(CAPACITY);
        assertEquals("前置：队列已满", CAPACITY, EventBus.queuedCount());
        final long droppedBefore = EventBus.droppedCount();

        EventBus.offer(ev("tpl-42")); // 与队内 tpl-42 同模板

        assertEquals("同模板聚合不是丢弃（信号经聚合计数保留）", droppedBefore, EventBus.droppedCount());
        assertEquals("腾出的槽位立即被聚合事件复占（队列仍满）", CAPACITY, EventBus.queuedCount());

        final List<SqlEvent> all = drainAll();
        assertEquals("事件总数不减（2047 异模板 + 1 条聚合代表）", CAPACITY, all.size());
        int tpl42 = 0;
        int aggregatedTotal = 0;
        for (SqlEvent e : all) {
            aggregatedTotal += e.aggregatedCount;
            if ("tpl-42".equals(e.sql)) {
                tpl42++;
                assertEquals("聚合代表条带出累计执行次数（旧事件 1 次 + 新事件 1 次）", 2, e.aggregatedCount);
                assertTrue("保留最新事实（新事件时间戳）", e.timestamp > 0);
            } else {
                assertEquals("异模板事件计数恒 1", 1, e.aggregatedCount);
            }
        }
        assertEquals("同模板在队内只保留最新一条", 1, tpl42);
        assertEquals("执行总次数不丢（2047 + 2 = 2049）", CAPACITY + 1, aggregatedTotal);
    }

    /**
     * ②队列满 + 异模板：逐出最旧给新模板腾位。券 I 起被逐出者**不再计丢弃**——先入溢出账本
     * 保全部数（发送线程补发聚合事件），只有账本也满收不下才计真丢弃（见 OverflowLedger 测试）。
     */
    @Test
    public void 队列满_异模板逐出最旧入账本保全_新模板入队() {
        fillDistinct(CAPACITY);
        final long droppedBefore = EventBus.droppedCount();

        EventBus.offer(ev("brand-new"));

        assertEquals("被逐出的最旧事件入账本保全，不计丢弃（券 I 语义收窄：真丢失只剩账本满）",
                droppedBefore, EventBus.droppedCount());
        assertEquals("被逐者已在账本（模板 tpl-0、1 次执行）", 1L, EventBus.overflowEventTotal());
        assertEquals("逐旧后新模板占位（队列仍满）", CAPACITY, EventBus.queuedCount());

        final List<SqlEvent> all = drainAll();
        boolean sawNew = false;
        boolean sawOldest = false;
        for (SqlEvent e : all) {
            if ("brand-new".equals(e.sql)) sawNew = true;
            if ("tpl-0".equals(e.sql)) sawOldest = true;
        }
        assertTrue("新模板事件已入队（腾位成功）", sawNew);
        assertTrue("被逐出的恰是最旧（队头 tpl-0，已转账本不在队列）", !sawOldest);
    }

    /** ③未满时零聚合零丢弃：同模板事件各自入队、计数恒 1（聚合只在满时发生）。 */
    @Test
    public void 未满时_同模板事件各自入队不聚合() {
        EventBus.offer(ev("same"));
        EventBus.offer(ev("same"));
        assertEquals(2, EventBus.queuedCount());
        final List<SqlEvent> all = drainAll();
        for (SqlEvent e : all) {
            assertEquals("未满路径聚合计数恒 1（常态零语义变化）", 1, e.aggregatedCount);
        }
    }

    /** ③offer 恒非阻塞：null 入参按丢弃计数（既有语义），满队列下连续异模板 offer 不抛不挂。 */
    @Test
    public void offer_恒非阻塞_null计丢弃_满队列连续投递不抛() {
        final long droppedBefore = EventBus.droppedCount();
        EventBus.offer(null);
        assertEquals("null 事件按既有语义计丢弃", droppedBefore + 1, EventBus.droppedCount());
        fillDistinct(CAPACITY);
        // 满队列下连续投递 64 个新模板：逐旧腾位路径反复执行，不抛异常即为非阻塞语义断言
        for (int i = 0; i < 64; i++) {
            EventBus.offer(ev("fresh-" + i));
        }
        assertEquals(CAPACITY, EventBus.queuedCount());
    }

    /** 聚合后 clearQueued 语义不变：整队清空、累计清空条数可观测（T-AgentConfigChannel T-01b 面不回破）。 */
    @Test
    public void 聚合后_clearQueued_语义不变() {
        fillDistinct(CAPACITY);
        EventBus.offer(ev("tpl-7"));
        assertEquals(CAPACITY, EventBus.queuedCount());
        final long clearedBefore = EventBus.clearedCount();
        assertEquals("清空条数=队内现存（聚合代表算一条）", CAPACITY, EventBus.clearQueued());
        assertEquals(0, EventBus.queuedCount());
        assertEquals("清空累计照常累加", clearedBefore + CAPACITY, EventBus.clearedCount());
        final SqlEvent[] box = new SqlEvent[1];
        try {
            assertNull("清空后队列确空", EventBus.pollEvent(box, 0) ? box[0] : null);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AssertionError("pollEvent 被中断", ie);
        }
    }
}
