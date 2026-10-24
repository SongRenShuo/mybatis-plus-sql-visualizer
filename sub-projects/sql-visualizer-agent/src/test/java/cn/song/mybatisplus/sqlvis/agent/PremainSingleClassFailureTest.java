package cn.song.mybatisplus.sqlvis.agent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-JRebelPremainCrash §5.2「单类失败不中断」守卫。
 *
 * <p>计划表原文要求：*注入「某驱动类 retransform 抛异常」夹具 → 其余类仍被 retransform，
 * {@code failed} 计数 +1*。此前的 {@code FakeInstrumentation}（demo 仓）与
 * {@link PremainFailsafeBehaviorTest} 的桩里，{@code retransformClasses} 都是空实现，
 * 因此「单类抛异常后其余类仍继续」这条**从未被真正验过**——本类补上这个缺口。
 *
 * <p>做法：{@code Instrumentation} 桩对**指定类名**抛 {@code UnmodifiableClassException}，
 * 其余类正常计数。断言三件事：
 * <ol>
 *   <li>被注入失败的类之后仍继续处理（其余登记类达到 {@code wove}）；</li>
 *   <li>汇总行里 {@code failed=1} 且失败类名随行；</li>
 *   <li>整个 premain 仍正常返回（单类失败不升级成整体失败）。</li>
 * </ol>
 */
public class PremainSingleClassFailureTest {

    /** 两个都会命中 WeaveSpecs 的语句面类名（H2 在测试 classpath 内）。 */
    /**
     * 两个「名字命中 WeaveSpecs 语句面」的类名。
     *
     * <p><b>为什么必须用隔离加载器取 Class 对象</b>：{@code ZeroLoadClassWriterWeaveTest} 以
     * {@code findLoadedClass} 断言 {@code org.h2.jdbc.JdbcPreparedStatement} 与
     * {@code org.h2.jdbc.JdbcConnection} 尚未被**系统加载器**装载（零类加载红线）。本类若直接
     * {@code Class.forName("org.h2.jdbc.JdbcPreparedStatement")}，就会提前装载它、破坏该守卫的
     * 前置条件并令其误红（本次实测过一次，2 例）。
     *
     * <p>改为在独立 {@link URLClassLoader} 里加载同名类：类名依旧逐字命中登记表（谓词只按名字
     * 判定），而系统加载器的 {@code findLoadedClass} 仍为 null，守卫鉴别力得以保留。
     */
    private static final String[] FACE_CLASSES = {
            "org.h2.jdbc.JdbcStatement",
            "org.h2.jdbc.JdbcPreparedStatement",
    };

    private PrintStream originalErr;
    private ByteArrayOutputStream capturedErr;

    /**
     * 隔离加载器：只含 H2 所在 code source。
     *
     * <p><b>不得用 {@code Class.forName("org.h2.Driver")} 取 code source</b>：那会把
     * {@code org.h2.Driver} 装进系统加载器，直接破坏 {@code ZeroLoadClassWriterWeaveTest}
     * 「h2.Driver 未被装载」的前置断言（20261022 全量实测：同 JVM 内本类先跑即令该守卫 3 例误红，
     * 单类跑全绿——正是这条 {@code Class.forName} 造成的）。改为按资源定位 jar：
     * {@code getResource} 只解析 URL，不加载也不初始化任何 H2 类。
     */
    private static URL[] isolatedClasspath() {
        try {
            final ClassLoader cl = PremainSingleClassFailureTest.class.getClassLoader();
            final URL resource = cl.getResource("org/h2/Driver.class");
            if (resource == null) {
                return new URL[0];
            }
            final String url = resource.toString();
            final int sep = url.indexOf("!/");
            return new URL[]{new URL(sep >= 0 ? url.substring(0, sep + 2) : url)};
        } catch (Throwable t) {
            return new URL[0];
        }
    }

    /** 在隔离加载器里取同名 Class（{@code initialize=false}）；取不到返回 null。 */
    private static Class<?> isolatedClass(URL[] cp, String name) {
        if (cp.length == 0) {
            return null;
        }
        try (URLClassLoader l = new URLClassLoader(cp, ClassLoader.getPlatformClassLoader())) {
            return Class.forName(name, false, l);
        } catch (Throwable t) {
            return null;
        }
    }

    @Before
    public void setUp() {
        originalErr = System.err;
        capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr, true));
        System.clearProperty(AgentMain.ACTIVE_MARKER);
    }

    @After
    public void tearDown() {
        System.setErr(originalErr);
        System.clearProperty(AgentMain.ACTIVE_MARKER);
    }

    private String stderr() {
        return capturedErr.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Instrumentation 桩：对 {@code failingClassName} 的 retransform 抛
     * {@link java.lang.instrument.UnmodifiableClassException}，其余正常统计。
     */
    private static Instrumentation stubFailing(String failingClassName, int[] successCalls,
                                               URL[] isolatedCp) {
        return (Instrumentation) Proxy.newProxyInstance(
                PremainSingleClassFailureTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAllLoadedClasses":
                            // 只喂隔离加载器里的同名类：系统加载器不会因此装载它们，
                            // ZeroLoadClassWriterWeaveTest 的 findLoadedClass 前置条件得以保留
                            final java.util.List<Class<?>> acc = new java.util.ArrayList<Class<?>>();
                            for (String n : FACE_CLASSES) {
                                final Class<?> c = isolatedClass(isolatedCp, n);
                                if (c != null) {
                                    acc.add(c);
                                }
                            }
                            return acc.toArray(new Class<?>[0]);
                        case "isModifiableClass":
                            return true;
                        case "retransformClasses": {
                            final Object[] classes = (Object[]) args[0];
                            final String name = ((Class<?>) classes[0]).getName();
                            if (name.equals(failingClassName)) {
                                throw new java.lang.instrument.UnmodifiableClassException(
                                        "fixture: forced retransform failure for " + name);
                            }
                            successCalls[0] += classes.length;
                            return null;
                        }
                        case "getObjectSize":
                            return 0L;
                        case "isRetransformClassesSupported":
                        case "isRedefineClassesSupported":
                        case "isNativeMethodPrefixSupported":
                            return true;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "StubInstrumentation(failing=" + failingClassName + ")";
                        default:
                            return null;
                    }
                });
    }

    private URL[] agentJarUrl() {
        final File jar = new File("build/dist/mps-sql-agent.jar");
        assertTrue("找不到 agent fat jar：" + jar.getAbsolutePath(), jar.isFile());
        try {
            return new URL[]{jar.toURI().toURL()};
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 反射调 premain，返回外抛的 cause（正常返回为 null）。 */
    private Throwable invokePremain(Instrumentation inst) throws Exception {
        final URLClassLoader loader = new URLClassLoader(agentJarUrl(), ClassLoader.getPlatformClassLoader());
        final Class<?> main = Class.forName(AgentMain.class.getName(), true, loader);
        final Method premain = main.getMethod("premain", String.class, Instrumentation.class);
        try {
            premain.invoke(null, "port=47802;logLevel=warn", inst);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    /**
     * 核心：第一个登记类 retransform 抛异常后，第二个仍被处理；{@code failed=1}；
     * premain 不外抛。
     */
    @Test
    public void 单类retransform失败不中断其余类() throws Exception {
        final int[] successCalls = {0};
        // 让**第一个**语句面类失败，验证循环没有整体中止
        final Throwable thrown = invokePremain(stubFailing(FACE_CLASSES[0], successCalls, isolatedClasspath()));

        assertNull("单类 retransform 失败不得外抛（否则 premain 会中止宿主启动）；实际：" + thrown,
                thrown);
        assertTrue("被注入失败的那个类之后，其余登记类必须仍被 retransform；"
                        + "实际成功调用数=" + successCalls[0] + "（期望 >=1）",
                successCalls[0] >= 1);

        final String err = stderr();
        assertTrue("汇总行必须报告 failed=1（单类失败计数 +1），实际 stderr=" + err,
                err.matches("(?s).*failed=1(\\D|$).*"));
        assertTrue("失败类名必须随行留痕（便于定位是哪个驱动版本漂移），实际 stderr=" + err,
                err.contains("JdbcStatement"));
        assertNotNull("premain 正常走完应写 active 标记（单类失败不算整体降级）",
                System.getProperty(AgentMain.ACTIVE_MARKER));
    }

    /** 两个类都不失败时 failed=0、wove=2——作为上一条的负对照（证明 failed=1 不是常量）。 */
    @Test
    public void 负对照_无失败时failed为零且全部织入() throws Exception {
        final int[] successCalls = {0};
        final Throwable thrown = invokePremain(stubFailing("no.such.Class", successCalls, isolatedClasspath()));

        assertNull("无失败时不得外抛；实际：" + thrown, thrown);
        final String err = stderr();
        assertTrue("负对照：不得出现 failed=1（说明上面那条的 failed=1 是失败注入带来的，不是常量）。stderr=" + err,
                !err.matches("(?s).*failed=1(\\D|$).*"));
        assertTrue("负对照：两个登记类都应被 retransform（wove=2）。stderr=" + err,
                successCalls[0] >= 2);
    }
}
