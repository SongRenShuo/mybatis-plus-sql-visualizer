package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.hook.StatementRegistry;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T-CaptureCatchAll 券 09③ 内存侧上限：{@link StatementRegistry} 单桶上限 + 淘汰。
 *
 * <p>动因：接口兜底会把**池包装语句**也登记进来（Hikari 的
 * {@code HikariProxyPreparedStatement} 等），而池会长期持有这些实例——原「弱键即自然清账、
 * 不设上限」的论证前提（键是短命对象）不再成立。本用例钉住「登记条数有硬上界」：
 * 持续登记远超上限的语句后，淘汰计数必须同步增长，且登记表规模不得随插入量线性膨胀。
 */
public class StatementRegistryLimitTest {

    @After
    public void tearDown() {
        StatementRegistry.clearForTest();
    }

    @Test
    public void 单桶上限生效_超出即淘汰() {
        final int perBucket = StatementRegistry.maxPerBucket();
        assertEquals("默认单桶上限 512（≈8192 条进程上界）", 512, perBucket);

        // 16 桶 × 上限 + 余量：无论 identityHashCode 如何分布，都必然触发多桶淘汰
        final int total = perBucket * 16 * 2 + 1000;
        final List<Object> keepAlive = new ArrayList<Object>(total);
        for (int i = 0; i < total; i++) {
            final Object stmt = new Object();
            keepAlive.add(stmt); // 强引用：排除「被 GC 掉」对淘汰计数的干扰
            StatementRegistry.register(stmt, "SELECT " + i, "jdbc:limit://db");
        }

        final long evicted = StatementRegistry.evictedCount();
        assertTrue("持续登记必须触发淘汰（实际 evicted=" + evicted + "，插入 " + total + " 条）",
                evicted > 0L);
        // 保留量上界 = 桶数 × 单桶上限；淘汰量至少 = 插入量 - 上界
        final long maxRetained = (long) perBucket * 16;
        assertTrue("保留条数不得超过硬上界（淘汰不足即内存上限失效）：evicted=" + evicted,
                evicted >= total - maxRetained - 16L);
        // 登记表仍可服务（淘汰不是清表）
        StatementRegistry.register(keepAlive.get(0), "SELECT fresh", "jdbc:limit://db");
        assertTrue("淘汰后登记表仍可写入", StatementRegistry.evictedCount() >= evicted);
    }
}
