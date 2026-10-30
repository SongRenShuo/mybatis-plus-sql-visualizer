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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-JRebelPremainCrash 行为守卫：**premain 在任何失败形态下都不得外抛**。
 *
 * <p>与 {@link PremainFailsafeGuardTest}（源码结构）互补：本类真跑 {@code AgentMain.premain}，
 * 用敌意 {@link URLClassLoader} 复刻「第三方 agent 接管 premain 类加载后某组类不可见」。
 *
 * <p>三档判据，分别锚住 A 案与 B 案：
 * <ol>
 *   <li><b>B 案（ASM 不可见 → 照常工作）</b>：名单查询已移入零 ASM 依赖的 {@link WeaveSpecs}，
 *       谓词路径不再要求 ASM，因此 premain **既不抛也不降级**——这是 B 案的期望终态，
 *       比「降级」更强（能力不丢）。此档同时是 A 案的鉴别力基准。</li>
 *   <li><b>A 案（真正失败 → 降级不抛）</b>：让 agent 运行期真正需要的类不可见
 *       （{@code transport.EventBus}），install 必然失败；此时必须
 *       <b>不外抛 + stderr 留降级行 + 不写 active 标记</b>。</li>
 *   <li><b>Error 形态</b>：同上但抛 {@code ClassCircularityError}（`Error` 而非 `LinkageError`），
 *       证明兜底是 {@code catch (Throwable)} 而非只捕链接错误。</li>
 * </ol>
 *
 * <p><b>判据有效性前提（曾踩假绿）</b>：{@code getAllLoadedClasses()} 必须返回至少一个登记表内的
 * 语句面类，否则循环一次都不进、谓词根本不被调用，任何「不抛」都无鉴别力。正对照断言
 * {@code matched>=1} 钉住这点。
 */
public class PremainFailsafeBehaviorTest {

    /** 会被 {@code WeaveSpecs} 判定为语句面的真实类名（H2 在测试 classpath 内）。 */
    private static final String STATEMENT_FACE_CLASS = "org.h2.jdbc.JdbcStatement";

    /** agent 运行期真正需要的类（A 案降级触发点：让它在加载器里不可见）。 */
    private static final String ESSENTIAL_CLASS = "cn.song.mybatisplus.sqlvis.agent.transport.EventBus";

    private PrintStream originalErr;
    private ByteArrayOutputStream capturedErr;

    @Before
    public void setUp() {
        originalErr = System.err;
        capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr, true));
        System.clearProperty(AgentMain.ACTIVE_MARKER);
        // T-CaptureCatchAll 券 08：关掉 premain 启动后补挂线程——本类用桩 Instrumentation 调 premain，
        // 真实补挂线程会在用例结束 10s 后触发桩并写 stderr，污染后续用例的断言窗口。
        CaptureBudget.setPremainScheduleForTest(0L, 0L, 1);
        // 2026-10-30：attach 汇总行默认静默（用户令「控制台只保留必要的」），本类靠它判 matched>=1，
        // 故显式开 verbose——测试要的是判据，不是产品的默认静音行为。
        CaptureBudget.setVerboseLogging("debug");
    }

    @After
    public void tearDown() {
        System.setErr(originalErr);
        System.clearProperty(AgentMain.ACTIVE_MARKER);
        CaptureBudget.resetForTest();
    }

    /**
     * 敌意加载器：agent jar 作类路径，拒绝指定前缀的类。
     *
     * @param hideAsm      拒绝 {@code cn.song.mybatisplus.sqlvis.agent.asm.*}（复刻用户现场必要条件）
     * @param hideEssential 拒绝 {@code transport.EventBus}（用于逼出 A 案降级路径）
     * @param circular     拒绝时抛 {@link ClassCircularityError} 而非 {@link ClassNotFoundException}
     */
    private static final class HostileLoader extends URLClassLoader {
        private final boolean hideAsm;
        private final boolean hideEssential;
        private final boolean circular;

        HostileLoader(URL[] urls, boolean hideAsm, boolean hideEssential, boolean circular) {
            super(urls, ClassLoader.getPlatformClassLoader());
            this.hideAsm = hideAsm;
            this.hideEssential = hideEssential;
            this.circular = circular;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            final boolean denied = (hideAsm && name.startsWith("cn.song.mybatisplus.sqlvis.agent.asm."))
                    || (hideEssential && name.equals(ESSENTIAL_CLASS));
            if (denied) {
                if (circular) {
                    // 真正的 Error 子类：只捕 LinkageError / RuntimeException 都兜不住，
                    // 这正是「必须 catch (Throwable)」的实证依据（demo 仓 B3 格同形态）
                    throw new ClassCircularityError(name);
                }
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }

    /** Instrumentation 动态代理桩：getAllLoadedClasses 返回受控数组，其余安全空实现。 */
    private static Instrumentation stubInstrumentation(Class<?>... loaded) {
        return (Instrumentation) Proxy.newProxyInstance(
                PremainFailsafeBehaviorTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAllLoadedClasses":
                            return loaded;
                        case "getObjectSize":
                            return 0L;
                        case "isModifiableClass":
                            return true;
                        case "isRetransformClassesSupported":
                        case "isRedefineClassesSupported":
                        case "isNativeMethodPrefixSupported":
                            return true;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "StubInstrumentation";
                        default:
                            return null;
                    }
                });
    }

    /** 被测 agent jar（test 任务已 dependsOn agentFatJar）。 */
    private URL[] agentJarUrl() {
        final File jar = new File("build/dist/mps-sql-agent.jar");
        assertTrue("找不到 agent fat jar：" + jar.getAbsolutePath(), jar.isFile());
        try {
            return new URL[]{jar.toURI().toURL()};
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 语句面桩类（必须能被 WeaveSpecs 命中，否则循环不进谓词）。 */
    private Class<?> statementFaceClass() {
        try {
            return Class.forName(STATEMENT_FACE_CLASS);
        } catch (Throwable t) {
            throw new AssertionError("测试 classpath 缺 " + STATEMENT_FACE_CLASS
                    + "——本档判据失效（循环不会走到谓词）", t);
        }
    }

    /**
     * 在指定加载器下反射调用 premain。
     *
     * @return 外抛的 cause；未外抛返回 null
     */
    private Throwable invokePremain(ClassLoader loader, Class<?>[] loaded) throws Exception {
        final Class<?> main = Class.forName(AgentMain.class.getName(), true, loader);
        final Method premain = main.getMethod("premain", String.class, Instrumentation.class);
        try {
            // 端口需 >0 才走到 retransformLoadedDrivers（install 的早退闸门）。
            // logLevel=debug：attach 汇总行自 2026-10-30 起默认静默（用户令「控制台只保留必要的」），
            // 而本类要拿这一行的 matched=N 当判据，故由 harness 显式开 verbose。
            premain.invoke(null, "port=47801;logLevel=debug", stubInstrumentation(loaded));
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    private String stderr() {
        return capturedErr.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 档 1：B 案 —— ASM 不可见时照常工作（既不抛，也不该降级）
    // ------------------------------------------------------------------

    /**
     * ASM 不可见时 premain 不得外抛，且**不得降级**——名单查询已与 ASM 解耦，
     * 该场景下增强能力不该丢。
     */
    @Test
    public void asm不可见时premain照常工作不降级() throws Exception {
        final Throwable thrown = invokePremain(
                new HostileLoader(agentJarUrl(), true, false, false),
                new Class<?>[]{statementFaceClass(), String.class});

        assertNull("ASM 不可见时 premain 不得外抛（会中止目标 JVM 启动）；实际外抛：" + thrown, thrown);
        final String err = stderr();
        assertFalse("B 案生效时不该降级——名单查询已不依赖 ASM；实际 stderr=" + err,
                err.contains(AgentMain.DEGRADE_PREFIX));
        // harness 传 logLevel=debug（见 invokePremain），汇总行在场，判据仍是那一行的 matched=N
        assertTrue("正对照：必须命中至少一个登记类（matched>=1），否则本档无鉴别力。stderr=" + err,
                err.matches("(?s).*matched=[1-9]\\d*.*"));
        assertNotNull("install 成功应写 active 标记", System.getProperty(AgentMain.ACTIVE_MARKER));
    }

    // ------------------------------------------------------------------
    // 档 2：A 案 —— 真正失败时降级不抛、留痕、不写 active
    // ------------------------------------------------------------------

    /** agent 必需类不可见 → premain 不得外抛，且必须留降级行。 */
    @Test
    public void 必需类不可见时premain降级不抛并留痕() throws Exception {
        final Throwable thrown = invokePremain(
                new HostileLoader(agentJarUrl(), false, true, false),
                new Class<?>[]{statementFaceClass(), String.class});

        assertNull("必需类不可见时 premain 也不得外抛（premain 外抛 = 目标 JVM 启动失败）；实际："
                + thrown, thrown);
        assertTrue("降级必须在 stderr 留一行说明（" + AgentMain.DEGRADE_PREFIX + "），实际 stderr=" + stderr(),
                stderr().contains(AgentMain.DEGRADE_PREFIX));
    }

    /** 降级后**不得**写 active 标记——否则 IDE 侧 attach 会误判「已装」而永不重试。 */
    @Test
    public void 降级后不得写active标记() throws Exception {
        invokePremain(new HostileLoader(agentJarUrl(), false, true, false),
                new Class<?>[]{statementFaceClass(), String.class});

        assertNull("降级态不得留下 sqlvis.agent.active（会让 attach 永久不重试）",
                System.getProperty(AgentMain.ACTIVE_MARKER));
    }

    // ------------------------------------------------------------------
    // 档 3：Error 形态 —— 证明兜底是 catch(Throwable)
    // ------------------------------------------------------------------

    /** 类初始化环（`Error` 而非 `LinkageError`）同样必须兜住。 */
    @Test
    public void 类初始化环形态也必须兜住() throws Exception {
        final Throwable thrown = invokePremain(
                new HostileLoader(agentJarUrl(), false, true, true),
                new Class<?>[]{statementFaceClass(), String.class});

        assertFalse("类初始化环形态（Error 而非 NCDFE）不得外抛——只捕 LinkageError 会漏；实际："
                + thrown, thrown != null);
        assertTrue("降级必须在 stderr 留痕，实际 stderr=" + stderr(),
                stderr().contains(AgentMain.DEGRADE_PREFIX));
    }

    /** ASM 不可见 + Error 形态：B 案下不该走到 ASM，故同样不得外抛。 */
    @Test
    public void asm不可见且Error形态也不得外抛() throws Exception {
        final Throwable thrown = invokePremain(
                new HostileLoader(agentJarUrl(), true, false, true),
                new Class<?>[]{statementFaceClass(), String.class});

        assertNull("ASM 被拒（Error 形态）时 premain 不得外抛；实际：" + thrown, thrown);
    }

    // ------------------------------------------------------------------
    // 档 4：正对照
    // ------------------------------------------------------------------

    /** 无干扰时 premain 正常返回、不降级、写 active 标记。 */
    @Test
    public void 正对照_无干扰时正常安装() throws Exception {
        final ClassLoader sane = new URLClassLoader(agentJarUrl(), ClassLoader.getPlatformClassLoader());
        final Throwable thrown = invokePremain(sane, new Class<?>[]{statementFaceClass(), String.class});

        assertNull("无干扰时不得外抛；实际：" + thrown, thrown);
        assertFalse("无干扰时不得降级，实际 stderr=" + stderr(),
                stderr().contains(AgentMain.DEGRADE_PREFIX));
        // 同上一处：harness 传 logLevel=debug，判据仍是汇总行的 matched=N
        assertTrue("无干扰时必须命中至少一个登记类（matched>=1），否则判据无鉴别力。stderr=" + stderr(),
                stderr().matches("(?s).*matched=[1-9]\\d*.*"));
        assertNotNull("正常安装应写 active 标记", System.getProperty(AgentMain.ACTIVE_MARKER));
    }
}
