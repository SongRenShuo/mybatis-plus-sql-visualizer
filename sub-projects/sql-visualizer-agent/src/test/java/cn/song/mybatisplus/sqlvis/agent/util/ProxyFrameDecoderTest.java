package cn.song.mybatisplus.sqlvis.agent.util;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Stack;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-HdAbsorb D217（代理帧反解）：JDK 动态代理帧与 Spring CGLIB 增强帧在栈快照里反解为
 * 承载调用身份的真实 FQCN。
 *
 * <p>吸收自 Hibernate Debugger 的 StatementTraceProxyLink（interfaceName#methodName）——
 * 用户在栈弹层看到 {@code com.sun.proxy.$Proxy7#selectById} 与
 * {@code FooServiceImpl$$SpringCGLIB$$0#getById} 不可读；反解后 Class 段是可解析 FQCN，
 * IDE 侧点击直达接口/基类源码。
 *
 * <p>反向验证（摘掉必红）：{@link #stackDigestDecodesProxyFrameToInterface()} 在
 * {@link StackDigest} 摘掉 decodeForDisplay 接线后必红（快照回退到原始 $Proxy 名）。
 * 刻意不测「主位置选择语义」——D217 只改展示面，定位行为由既有 StackLocator* 测试族守卫。
 */
public class ProxyFrameDecoderTest {

    /** 模拟业务 mapper 接口（不在任何框架前缀内）。 */
    interface DemoMapper {
        void selectById();
    }

    /** 框架接口（java. 前缀，须被框架门拦下）。 */
    interface FrameworkFlavored extends Runnable {
    }

    @Test
    public void jdkProxyNameDetection() {
        assertTrue(ProxyFrameDecoder.isJdkProxyName("com.sun.proxy.$Proxy7"));
        assertTrue(ProxyFrameDecoder.isJdkProxyName("$Proxy123"));
        assertTrue(ProxyFrameDecoder.isJdkProxyName("jdk.proxy2.$Proxy45"));
        assertFalse(ProxyFrameDecoder.isJdkProxyName("com.example.Foo"));
        assertFalse(ProxyFrameDecoder.isJdkProxyName("com.example.Foo$ProxyHelper"));
        assertFalse(ProxyFrameDecoder.isJdkProxyName("com.example.Foo$$SpringCGLIB$$0"));
        assertFalse(ProxyFrameDecoder.isJdkProxyName(null));
    }

    @Test
    public void cglibBaseNameStripsEnhancerSuffixes() {
        assertEquals("com.example.FooServiceImpl",
                ProxyFrameDecoder.cglibBaseName("com.example.FooServiceImpl$$SpringCGLIB$$0"));
        assertEquals("com.example.FooServiceImpl",
                ProxyFrameDecoder.cglibBaseName("com.example.FooServiceImpl$$EnhancerBySpringCGLIB$$d1"));
        assertEquals("com.example.Foo",
                ProxyFrameDecoder.cglibBaseName("com.example.Foo$$FastClassBySpringCGLIB$$abc"));
        assertEquals("com.example.Plain", ProxyFrameDecoder.cglibBaseName("com.example.Plain"));
    }

    @Test
    public void decodeJdkProxyIdentityReturnsBusinessInterface() {
        ClassLoader loader = getClass().getClassLoader();
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{DemoMapper.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return null;
                    }
                });
        String proxyName = proxy.getClass().getName();
        assertTrue(ProxyFrameDecoder.isJdkProxyName(proxyName));
        assertEquals(DemoMapper.class.getName(),
                ProxyFrameDecoder.decodeJdkProxyIdentity(proxyName, loader));
    }

    @Test
    public void frameworkOnlyInterfacesStayUndecoded() {
        ClassLoader loader = getClass().getClassLoader();
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{FrameworkFlavored.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return null;
                    }
                });
        String proxyName = proxy.getClass().getName();
        // 全部接口都在框架前缀（java.）内 → 不猜，返回 null（展示名原样保留）
        assertNull(ProxyFrameDecoder.decodeJdkProxyIdentity(proxyName, loader));
        assertEquals(proxyName, ProxyFrameDecoder.decodeForDisplay(proxyName, loader));
    }

    @Test
    public void undecodableProxyKeepsRawName() {
        ClassLoader loader = getClass().getClassLoader();
        // 不存在的代理类名：不猜，原样保留
        assertEquals("com.sun.proxy.$Proxy9999",
                ProxyFrameDecoder.decodeForDisplay("com.sun.proxy.$Proxy9999", loader));
        assertNull(ProxyFrameDecoder.decodeForDisplay(null, loader));
        assertEquals("com.example.Plain", ProxyFrameDecoder.decodeForDisplay("com.example.Plain", loader));
    }

    @Test
    public void stackDigestDecodesProxyFrameToInterface() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        final DemoMapper mapper = (DemoMapper) Proxy.newProxyInstance(loader,
                new Class<?>[]{DemoMapper.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return null;
                    }
                });
        // 用真实代理类名构造栈帧（digest 的 join 按类名反解，不需要真实执行栈）
        Stack<StackTraceElement> stack = new Stack<StackTraceElement>();
        stack.push(new StackTraceElement(mapper.getClass().getName(), "selectById", null, -1));
        stack.push(new StackTraceElement("java.lang.Thread", "run", "Thread.java", -1));
        stack.push(new StackTraceElement("com.example.OrderService", "place", "OrderService.java", 42));
        StackTraceElement[] frames = stack.toArray(new StackTraceElement[0]);
        // hint 必须能看见代理类——传测试类自己的 loader
        String digest = StackDigest.build(frames, 24, loader, null, null);
        assertTrue("快照应含反解后的接口名：" + digest,
                digest.contains(DemoMapper.class.getName() + "#selectById"));
        assertFalse("快照不应保留原始代理类名：" + digest, digest.contains("$Proxy"));
        // CGLIB 帧同批反解为基类
        StackTraceElement[] cglibStack = new StackTraceElement[]{
                new StackTraceElement("com.example.OrderServiceImpl$$SpringCGLIB$$0", "place",
                        "OrderServiceImpl.java", 42),
        };
        String cglibDigest = StackDigest.build(cglibStack, 24, loader, null, null);
        assertTrue("CGLIB 帧应剥后缀为基类：" + cglibDigest,
                cglibDigest.contains("com.example.OrderServiceImpl#place"));
        assertFalse(cglibDigest.contains("$$SpringCGLIB$$"));
    }
}
