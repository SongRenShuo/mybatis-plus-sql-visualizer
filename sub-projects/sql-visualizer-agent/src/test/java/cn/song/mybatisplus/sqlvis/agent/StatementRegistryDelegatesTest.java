package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.hook.StatementRegistry;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * T-OracleWrapperRegistry 守卫：prepare 面登记必须**解一层包装**。
 *
 * <p>真机形态（2026-11-04，Oracle Free 23 + ojdbc11 23.3）：`prepareStatement(String)` 返回
 * {@code oracle.jdbc.driver.OraclePreparedStatementWrapper}，SQL 登记挂在包装器上；而
 * `executeQuery()` 的织入点落在包内物理语句 `T4CPreparedStatement` 上，回调拿到的是内层对象
 * → 查表落空、整条 PS 事件被当作盲区丢弃（只剩 plain Statement 的连接校验 SQL 能看见）。
 *
 * <p>本类只钉登记表这一环（不做织入）：合成包装器持有 Statement 类型的内层字段，
 * 一次登记后包装器与内层都必须在表里；无内层、内层为空、空入参三种形态都不得外抛。
 */
public class StatementRegistryDelegatesTest {

    @After
    public void tearDown() {
        StatementRegistry.clearForTest();
    }

    @Test
    public void 包装器登记_内层物理语句同时进表() {
        final Wrapper wrapper = new Wrapper();

        StatementRegistry.registerWithDelegates(wrapper, "SELECT name FROM t WHERE id = ?", "jdbc:fake://db");

        final StatementRegistry.Ctx outer = StatementRegistry.get(wrapper);
        assertNotNull("包装器自身必须登记（execute 钩子若落在包装器上也要能取到）", outer);
        assertEquals("SELECT name FROM t WHERE id = ?", outer.sql);
        final StatementRegistry.Ctx inner = StatementRegistry.get(wrapper.preparedStatement);
        assertNotNull("内层物理语句必须登记——Oracle 的 execute 钩子落在这层", inner);
        assertEquals("SELECT name FROM t WHERE id = ?", inner.sql);
        assertEquals("URL 归属必须一起带过去", "jdbc:fake://db", inner.url);
    }

    @Test
    public void 无内层字段_只登记自身() {
        final NoInner holder = new NoInner();

        StatementRegistry.registerWithDelegates(holder, "UPDATE t SET x = 1", null);

        assertNotNull("无内层可解包时行为与 register 完全一致", StatementRegistry.get(holder));
        assertEquals("UPDATE t SET x = 1", StatementRegistry.get(holder).sql);
    }

    @Test
    public void 内层字段为空_不抛且只登记自身() {
        final NullInner holder = new NullInner();

        StatementRegistry.registerWithDelegates(holder, "DELETE FROM t", null);

        assertNotNull("内层为 null 时跳过内层登记，但包装器自身照常登记", StatementRegistry.get(holder));
        assertNull("空内层不得被当成有效登记", StatementRegistry.get(new FakeVendorFixtures.VendorPs()));
    }

    @Test
    public void 空入参_安全直返() {
        StatementRegistry.registerWithDelegates(null, "SELECT 1", null);
        StatementRegistry.registerWithDelegates(new Wrapper(), null, null);
        StatementRegistry.registerWithDelegates(new Wrapper(), "", null);

        assertNull("空 SQL 不建账", StatementRegistry.get(new Wrapper()));
    }

    /** Oracle 包装器等价形态：对内句柄 + 内层物理语句挂在 Statement 类型字段上。 */
    static final class Wrapper {
        private final FakeVendorFixtures.VendorPs preparedStatement = new FakeVendorFixtures.VendorPs();
    }

    /** 无内层字段的普通语句。 */
    static final class NoInner {
        private int counter;
    }

    /** 内层字段声明了但为 null（关闭后置空等形态）。 */
    static final class NullInner {
        private FakeVendorFixtures.VendorPs statement;
    }
}
