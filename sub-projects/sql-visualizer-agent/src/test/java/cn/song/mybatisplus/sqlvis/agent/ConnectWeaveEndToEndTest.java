package cn.song.mybatisplus.sqlvis.agent;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * [ConnectWeaver] 端到端：织入 FakeDriver 字节码 → 定义类 → connect 出口钩子链
 * → Connection 被 JDK 代理包装（§4.2 #1 主路径单机全链）。
 * 附带 §4.2 #6 classloader 契约测试：驱动 loader 隐藏 agent 类时 connect 安全降级不抛错。
 * T-AgentProxyCL：驱动由系统 loader 之下的子 loader 加载（fat jar / devtools 形态）时，
 * 代理定义 loader 必须跟着驱动走——见「子加载器驱动」用例。
 */
public class ConnectWeaveEndToEndTest {

    /**
     * 本类专测**连接代理通道**（T-ConnCastSafety 起该通道已改为显式 opt-in，
     * 出厂不替换宿主连接对象——见 {@code SqlHookCallbacks#WRAP_CONNECTION_PROP}）。
     * 这里按测试缝打开，语义与改造前一致；「默认不替换」由 {@code ConnectionCastSafetyTest} 钉住。
     */
    @org.junit.Before
    public void enableConnectionProxyForProxyChannelTests() {
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.setWrapConnectionForTest(Boolean.TRUE);
    }

    @org.junit.After
    public void restoreConnectionProxy() {
        cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks.setWrapConnectionForTest(null);
    }

    /** 织入产物落盘，返回目录（供隔离 classloader 加载）。 */
    private File writeWoven(ClassLoader weaveLoader) throws Exception {
        byte[] original = classBytes(FakeDriver.class.getName());
        byte[] woven = ConnectWeaver.weave(weaveLoader, original);
        File dir = Files.createTempDirectory("sqlvis-woven-").toFile();
        writeFixture(dir, "FakeDriver", woven);
        return dir;
    }

    /**
     * 夹具类落盘：FakeDriver 用织入字节，其余同名前缀内部类（含 VendorConnection /
     * VendorStatement 两个驱动自定义接口）原样复制——子优先 loader 只从该目录找
     * {@code FakeDriver*}，缺一个就 NoClassDefFoundError（旧写法手工枚举内部类名，
     * 新增接口即静默漏拷）。按编译产物目录自动枚举，避免再次漏项。
     */
    private void writeFixture(File dir, String simpleName, byte[] driverBytes) throws Exception {
        final String pkg = "cn/song/mybatisplus/sqlvis/agent";
        File cls = new File(dir, pkg + "/" + simpleName + ".class");
        cls.getParentFile().mkdirs();
        Files.write(cls.toPath(), driverBytes);
        File srcDir = new File(FakeDriver.class.getResource("FakeDriver.class").toURI()).getParentFile();
        File[] siblings = srcDir.listFiles();
        assertNotNull("夹具编译产物目录不可读：" + srcDir, siblings);
        for (File f : siblings) {
            final String name = f.getName();
            if (!name.startsWith(simpleName) || !name.endsWith(".class") || name.equals(simpleName + ".class")) {
                continue;
            }
            Files.copy(f.toPath(), new File(dir, pkg + "/" + name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 子优先 loader：FakeDriver* 只从织入目录加载（父加载器测试 classpath 上有同名原始类，
     * 默认父优先会遮蔽织入字节——本文件旧断言因此长期「空真」，T-AgentDialectLoop 修正）；
     * 其余类（SqlHookCallbacks/AgentState 等）照常父委派（回调类须两侧同源可见）。
     */
    private ClassLoader childFirstLoader(File dir) throws Exception {
        return new java.net.URLClassLoader(new java.net.URL[]{dir.toURI().toURL()},
                ClassLoader.getSystemClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("cn.song.mybatisplus.sqlvis.agent.FakeDriver")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name);
                        if (c == null) {
                            c = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(c);
                        }
                        return c;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    @Test
    public void 织入后connect返回被代理的Connection() throws Exception {
        File dir = writeWoven(ClassLoader.getSystemClassLoader());
        ClassLoader loader = childFirstLoader(dir);
        Class<?> driver = Class.forName(FakeDriver.class.getName(), true, loader);
        Object instance = driver.getDeclaredConstructor().newInstance();
        Connection conn = (Connection) driver.getMethod("connect", String.class, Properties.class)
                .invoke(instance, "jdbc:fake://t", new Properties());
        assertNotNull(conn);
        assertTrue("connect 出口应被包装为代理", Proxy.isProxyClass(conn.getClass()));
        assertTrue("conn 应为本 agent 的 ConnectionHandler 包装（织入字节真实生效）",
                Proxy.getInvocationHandler(conn) instanceof cn.song.mybatisplus.sqlvis.agent.hook.ConnectionHandler);
        // 代理仍然可用：走 prepareStatement → executeUpdate 原语义透传
        PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?)");
        assertTrue(Proxy.isProxyClass(ps.getClass()));
        assertEquals(1, ps.executeUpdate());
        conn.close(); // 直透：代理 close 不应抛错
    }

    @Test
    public void 连接url透传到Statement代理() throws Exception {
        // T-AgentDialectLoop：connect 入参 URL 经 ConnectionHandler → StatementHandler 一路携带
        File dir = writeWoven(ClassLoader.getSystemClassLoader());
        ClassLoader loader = childFirstLoader(dir);
        Object instance = Class.forName(FakeDriver.class.getName(), true, loader)
                .getDeclaredConstructor().newInstance();
        Connection conn = (Connection) driverConnect(instance, "jdbc:fake://dialect");
        assertTrue(Proxy.getInvocationHandler(conn) instanceof cn.song.mybatisplus.sqlvis.agent.hook.ConnectionHandler);
        PreparedStatement ps = conn.prepareStatement("SELECT 1");
        Object handler = Proxy.getInvocationHandler(ps);
        java.lang.reflect.Field url = handler.getClass().getDeclaredField("url");
        url.setAccessible(true);
        assertEquals("jdbc:fake://dialect", url.get(handler));
    }

    @Test
    public void null连接路径安全() throws Exception {
        File dir = writeWoven(ClassLoader.getSystemClassLoader());
        ClassLoader loader = childFirstLoader(dir);
        Object instance = Class.forName(FakeDriver.class.getName(), true, loader)
                .getDeclaredConstructor().newInstance();
        Object conn = driverConnect(instance, null);
        assertEquals(null, conn);
    }

    /**
     * T-AgentProxyCL 主用例：驱动在**系统 loader 之下的子 URLClassLoader** 里（Spring Boot fat jar 的
     * LaunchedURLClassLoader / devtools RestartClassLoader 同构），织入后仍须包到代理且 SQL 事件可达。
     *
     * <p>鉴别力来自夹具形态：连接/语句代理实现的是 {@code FakeDriver$VendorConnection} 这类
     * **驱动自定义接口**，子优先 loader 下它的副本只存在于子 loader——系统 loader 解析到的是另一个
     * Class 对象。旧实现固定用 agent loader（=系统）定义代理 → JDK 抛
     * {@code IllegalArgumentException: interface ... is not visible from class loader} →
     * 被回调静默吞掉 → 事件 0 条（真机现象）。断言链：代理身份 → 语句代理身份 → 事件落地。
     */
    @Test
    public void 子加载器驱动_非bootstrap接口_织入后事件可达() throws Exception {
        File dir = writeWoven(ClassLoader.getSystemClassLoader());
        ClassLoader loader = childFirstLoader(dir);
        Class<?> vendorIface = Class.forName(FakeDriver.class.getName() + "$VendorConnection", false, loader);
        // 夹具前提自检：接口集里必须有「只属于子 loader」的接口，否则本用例对 loader 选择无鉴别力
        assertNotSame("夹具前提：VendorConnection 在子 loader 里须是与系统 loader 不同的 Class 对象",
                vendorIface, Class.forName(FakeDriver.class.getName() + "$VendorConnection"));
        assertEquals("夹具前提：该接口须由子 loader 定义", loader, vendorIface.getClassLoader());

        Object instance = Class.forName(FakeDriver.class.getName(), true, loader)
                .getDeclaredConstructor().newInstance();
        Connection conn = (Connection) driverConnect(instance, "jdbc:fake://child");
        assertNotNull(conn);
        assertTrue("子 loader 驱动下 connect 出口仍须被包装为 agent 代理",
                Proxy.getInvocationHandler(conn) instanceof cn.song.mybatisplus.sqlvis.agent.hook.ConnectionHandler);
        assertEquals("原语义透传：驱动自定义接口方法经代理仍可调用", "fake-vendor", invokeVendorTag(conn));

        PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (1)");
        assertTrue("语句侧同样须被包装（接口集里的 VendorStatement 只在子 loader 可见）",
                Proxy.isProxyClass(ps.getClass()));
        assertEquals("cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler",
                Proxy.getInvocationHandler(ps).getClass().getName());

        queue().clear();
        assertEquals(1, ps.executeUpdate());
        cn.song.mybatisplus.sqlvis.agent.model.SqlEvent event = queue().poll();
        assertNotNull("子 loader 驱动下 execute 必须产出事件（旧实现此处 events=0）", event);
        assertEquals("INSERT INTO t VALUES (1)", event.sql);
        conn.close();
    }

    /** 经驱动自定义接口取标签（子 loader 里的接口，测试侧只能反射调）。 */
    private String invokeVendorTag(Connection conn) throws Exception {
        return (String) conn.getClass().getMethod("vendorTag").invoke(conn);
    }

    /** [EventBus] 事件队列（不启动发送线程时无人消费，测试直接读它取本轮事件）。 */
    @SuppressWarnings("unchecked")
    private static java.util.concurrent.BlockingQueue<cn.song.mybatisplus.sqlvis.agent.model.SqlEvent> queue()
            throws Exception {
        java.lang.reflect.Field field = cn.song.mybatisplus.sqlvis.agent.transport.EventBus.class
                .getDeclaredField("QUEUE");
        field.setAccessible(true);
        return (java.util.concurrent.BlockingQueue<cn.song.mybatisplus.sqlvis.agent.model.SqlEvent>) field.get(null);
    }

    @Test
    public void 驱动loader隐藏agent类_connect安全降级不抛错() throws Exception {
        File dir = Files.createTempDirectory("sqlvis-hidden-").toFile();
        // parent = 平台 loader：JDK 可见，测试 classpath（含 agent 类）不可见——§4.2 #6 契约
        ClassLoader hidden = new java.net.URLClassLoader(new java.net.URL[]{dir.toURI().toURL()},
                ClassLoader.getPlatformClassLoader());
        // 以 hidden loader 做织入判定：回调类不可见 → 原字节不织入
        writeFixture(dir, "FakeDriver", ConnectWeaver.weave(hidden, classBytes(FakeDriver.class.getName())));
        Object instance = Class.forName(FakeDriver.class.getName(), true, hidden)
                .getDeclaredConstructor().newInstance();
        Connection conn = (Connection) driverConnect(instance, "jdbc:fake://t");
        assertNotNull("agent 类不可见时仍须返回原连接", conn);
        // 夹具本身是 Proxy——断言「未叠加 agent 代理」（handler 非本 agent 的 ConnectionHandler）
        assertFalse("回调不可达时不得包装（静默降级为无钩子）",
                Proxy.getInvocationHandler(conn) instanceof cn.song.mybatisplus.sqlvis.agent.hook.ConnectionHandler);
    }

    private Object driverConnect(Object driver, String url) throws Exception {
        return driver.getClass().getMethod("connect", String.class, Properties.class)
                .invoke(driver, url, new java.util.Properties());
    }

    private byte[] classBytes(String fqcn) throws Exception {
        String path = fqcn.replace('.', '/') + ".class";
        try (java.io.InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
