package cn.song.mybatisplus.sqlvis.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

/**
 * T-OldJvmPremainGuard 券02：agent 的入口引导类（manifest 的 Premain-Class / Agent-Class 同指本类）。
 *
 * <p><b>为什么要有它。</b>此前两条入口直接指向 {@link AgentMain}，而 AgentMain 及其依赖是 Java 8
 * 字节码（major 52）。JDK 6/7 的 JVM 上限是 50/51，**连入口类都定义不了**——`-javaagent` 会把
 * 这种情况判为 agent 处理失败并直接终止 JVM（用户现场：JDK 1.6 项目开启增强捕获后起不来，
 * 报 {@code UnsupportedClassVersionError: … AgentMain … Unsupported major.minor version 52.0}）。
 * {@link AgentMain} 内部的 {@code catch (Throwable)} 在安装链内部，管不到「入口类不加载」。
 *
 * <p><b>做法。</b>本类刻意只用 Java 6 及更早的 API（无 lambda、无 try-with-resources、
 * 无 java.util.function / java.util.Objects 等 Java 7+ 类），打包时由 {@code RelocateAsmMain}
 * 把它的类文件版本降到 major 50（{@code Opcodes.V1_6}，见 {@code AgentShimClassGuardTest} 守卫）。
 * 于是任何 JVM 都能加载它，再由它决定要不要加载真正的 AgentMain：
 * <ul>
 *   <li>运行期类版本上限（{@code java.class.version}，JDK 6 上是 {@code 50.0}）低于
 *       {@link #MIN_RUNTIME_MAJOR} → stderr 留一行说明后 return，宿主照常启动；</li>
 *   <li>否则反射调用 {@link AgentMain#premain} / {@link AgentMain#agentmain}，行为与改造前一致；</li>
 *   <li>整段 {@code catch (Throwable)}：任何失败（类版本、NCDFE、JRebel 之类接管了 premain 类加载的
 *       现场）都只留一行说明，**绝不外抛**——premain 外抛对 JVM 是致命的。</li>
 * </ul>
 *
 * <p><b>与 AgentMain 的分工不变</b>：幂等标记、transformer 安装、降级口径全在 AgentMain 里，
 * 本类只做「能不能加载」的前置判定与加载这一跳。
 */
public final class AgentShim {

    /** agent 产物（AgentMain 及其依赖 + 重定位 ASM 之外的自有类）的类文件 major：Java 8。 */
    static final int MIN_RUNTIME_MAJOR = 52;

    /** 跳过/降级说明前缀（宿主 stderr 唯一出口；与 AgentMain 的 DEGRADE_PREFIX 同风格）。 */
    static final String SKIP_PREFIX = "[SqlVisAgent] skipped: ";
    static final String FAIL_PREFIX = "[SqlVisAgent] bootstrap failed: ";

    private static final String AGENT_MAIN = "cn.song.mybatisplus.sqlvis.agent.AgentMain";

    private AgentShim() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        dispatch(agentArgs, inst, true);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        dispatch(agentArgs, inst, false);
    }

    /**
     * 双入口共用分派。任何 Throwable 都不得外抛：premain 路径外抛会让 JVM 判定 `-javaagent` 失败
     * 并中止启动（与 AgentMain 的 T-JRebelPremainCrash 同一条红线）。
     */
    private static void dispatch(String agentArgs, Instrumentation inst, boolean premain) {
        try {
            final int major = runtimeClassMajor();
            if (major > 0 && major < MIN_RUNTIME_MAJOR) {
                System.err.println(SKIP_PREFIX + "jvm=" + System.getProperty("java.version")
                        + " (class version " + System.getProperty("java.class.version")
                        + ") does not support Java " + (MIN_RUNTIME_MAJOR - 44)
                        + "+ bytecode; enhanced capture disabled for this run");
                return;
            }
            final Class<?> main = Class.forName(AGENT_MAIN, true, AgentShim.class.getClassLoader());
            final Method entry = main.getMethod(premain ? "premain" : "agentmain",
                    String.class, Instrumentation.class);
            entry.invoke(null, agentArgs, inst);
        } catch (Throwable t) {
            // 只留类型 + 消息，不含宿主路径；不阻断（真正的降级判定仍在 AgentMain.install）
            System.err.println(FAIL_PREFIX + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /**
     * 当前 JVM 能加载的最高类文件 major（{@code java.class.version} 即该值：50.0 / 52.0 / 65.0）。
     * 取不到返回 -1（调用方按未知处理，交给 catch(Throwable) 兜）。
     */
    static int runtimeClassMajor() {
        try {
            final String value = System.getProperty("java.class.version");
            if (value == null) {
                return -1;
            }
            final int dot = value.indexOf('.');
            return Integer.parseInt(dot < 0 ? value : value.substring(0, dot));
        } catch (Throwable t) {
            return -1;
        }
    }
}
