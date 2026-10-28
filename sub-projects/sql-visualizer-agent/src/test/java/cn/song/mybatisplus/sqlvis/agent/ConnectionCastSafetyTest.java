package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.hook.ConnectionHandler;
import cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Properties;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T-ConnCastSafety 红线守卫：**采集不得改变宿主拿到的连接对象身份**。
 *
 * <p>报障现场：宿主（达梦/金仓一类国产库工程）在 {@code connect} 之后写
 * {@code (DmdbConnection) conn}，而 agent 此前把返回值换成了 JDK 动态代理，于是
 * {@code com.sun.proxy.$Proxy129 cannot be cast to dm.jdbc.driver.DmdbConnection} ——
 * 异常抛在宿主栈上，agent 的 {@code catch (Throwable)} 自吞拦不住，应用直接起不来。
 * 根因是构造性的：{@code Proxy.newProxyInstance} 只能实现接口，永远不可能伪装成驱动实现类。
 *
 * <p>本用例钉两件事：① 出厂默认原样返回（同一对象、非代理），实现类强转因此不可能失败；
 * ② 显式 opt-in（{@code -Dsqlvis.agent.wrapConnection=true} 对应的测试缝）时旧代理通道仍在——
 * 供不在 {@code WeaveSpecs} 名单里的驱动兜底，语义未被删掉、只是不再是默认。
 */
public class ConnectionCastSafetyTest {

    private static final String URL = "jdbc:fake://cast-safety";

    @After
    public void restore() {
        SqlHookCallbacks.setWrapConnectionForTest(null);
    }

    @Test
    public void 默认原样返回宿主连接_实现类强转不可能CCE() throws Exception {
        SqlHookCallbacks.setWrapConnectionForTest(null); // 出厂默认
        final Connection raw = new FakeDriver().connect(URL, new Properties());
        final Object out = SqlHookCallbacks.onConnection(raw, URL);

        assertSame("出厂必须原样返回宿主连接——换成 $Proxy 就会让 (DmdbConnection) conn 抛 ClassCastException",
                raw, out);
        // 夹具的连接本身就是接口代理（FakeDriver 自带），故不能拿 isProxyClass 判「是不是我们包的」，
        // 按 handler 归属判定：默认路径下语句绝不能由本 agent 的 StatementHandler 承接。
        final Object stmt = ((Connection) out).prepareStatement("SELECT 1");
        assertFalse("默认路径不得由本 agent 包装语句（语句包装只属于 opt-in 的连接代理通道）",
                isOurStatementHandler(stmt));
    }

    private static boolean isOurStatementHandler(Object stmt) {
        if (!Proxy.isProxyClass(stmt.getClass())) {
            return false;
        }
        return Proxy.getInvocationHandler(stmt).getClass().getName()
                .equals("cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler");
    }

    @Test
    public void 显式optIn仍可恢复连接代理通道() {
        SqlHookCallbacks.setWrapConnectionForTest(Boolean.TRUE);
        final Connection raw = new FakeDriver().connect(URL, new Properties());
        final Object out = SqlHookCallbacks.onConnection(raw, URL);

        assertNotSame("opt-in 时应恢复旧语义（包装成代理）", raw, out);
        assertTrue("opt-in 时连接应由本 agent 的 ConnectionHandler 承接",
                Proxy.getInvocationHandler(out) instanceof ConnectionHandler);
    }
}
