package cn.song.mybatisplus.sqlvis.agent.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * F78（T-PerfSweep23）：StatementRegistry 分桶（16 桶条带锁，identityHashCode 条带）正确性。
 *
 * 单语句恒同桶是全部语义的前提——若 bucketFor 不稳定（同语句两次调用落不同桶），
 * register 的登记会与 get/batchCtxOf/clearBatchOf 互相错开（丢登记/重复建账），
 * 并发锤击即红。原单表全局 monitor 的行为等价面：登记/取账/惰性建账/清账四操作语义不变。
 */
public class StatementRegistryBucketingTest {

    @After
    public void tearDown() {
        StatementRegistry.clearForTest();
    }

    @Test
    public void 同语句恒同桶_四操作语义不变() {
        final Object stmt = new Object();
        assertNull(StatementRegistry.get(stmt));

        StatementRegistry.register(stmt, "SELECT 1", "jdbc:test://a");
        final StatementRegistry.Ctx registered = StatementRegistry.get(stmt);
        assertNotNull(registered);
        assertEquals("SELECT 1", registered.sql);
        assertEquals("jdbc:test://a", registered.url);
        // 再取同实例（同桶命中）
        assertSame(registered, StatementRegistry.get(stmt));

        // batchCtxOf：已有登记直接复用（不重复建账）
        assertSame(registered, StatementRegistry.batchCtxOf(stmt));
        registered.batch.append("x");
        // clearBatchOf：清批串不清登记
        StatementRegistry.clearBatchOf(stmt);
        assertEquals(0, registered.batch.length());
        assertSame(registered, StatementRegistry.get(stmt));

        // 惰性建账（无登记语句）
        final Object bare = new Object();
        final StatementRegistry.Ctx lazy = StatementRegistry.batchCtxOf(bare);
        assertNull(lazy.sql);
        assertSame(lazy, StatementRegistry.get(bare));

        // 无语句入参守卫
        assertNull(StatementRegistry.get(null));
        StatementRegistry.clearBatchOf(null); // 不抛
    }

    @Test
    public void 并发锤击_分桶不丢登记不串账() throws InterruptedException {
        final int threads = 8;
        final int stmtsPerThread = 200;
        final AtomicBoolean failed = new AtomicBoolean(false);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final List<Thread> pool = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            final List<Object> stmts = new ArrayList<Object>();
            for (int i = 0; i < stmtsPerThread; i++) {
                stmts.add(new Object());
            }
            final String url = "jdbc:hammer://" + tid;
            final String sql = "SELECT " + tid;
            pool.add(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        // 登记轮
                        for (Object stmt : stmts) {
                            StatementRegistry.register(stmt, sql, url);
                        }
                        // 锤击轮：建账/追加/清账交错（同语句反复跨操作）
                        for (int round = 0; round < 3; round++) {
                            for (Object stmt : stmts) {
                                final StatementRegistry.Ctx ctx = StatementRegistry.batchCtxOf(stmt);
                                ctx.batch.append("b");
                                if (!sql.equals(ctx.sql) || !url.equals(ctx.url)) {
                                    failed.compareAndSet(false, true);
                                    return;
                                }
                            }
                            for (Object stmt : stmts) {
                                StatementRegistry.clearBatchOf(stmt);
                            }
                        }
                        // 终检轮：全部登记在场、内容未被串账
                        for (Object stmt : stmts) {
                            final StatementRegistry.Ctx ctx = StatementRegistry.get(stmt);
                            if (ctx == null || !sql.equals(ctx.sql) || !url.equals(ctx.url)) {
                                failed.compareAndSet(false, true);
                                return;
                            }
                        }
                    } catch (Throwable t) {
                        failed.compareAndSet(false, true);
                    } finally {
                        done.countDown();
                    }
                }
            }, "hammer-" + tid));
        }
        for (Thread th : pool) {
            th.start();
        }
        start.countDown();
        done.await();
        assertEquals("并发锤击不得丢登记/串账/抛异常", false, failed.get());
    }
}
