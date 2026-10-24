package demo;

import java.lang.instrument.Instrumentation;

/**
 * 前置 agent：在 premain 里先加载一个「语句面」类，让后续挂载的插件 agent 必然命中。
 *
 * <p>为什么需要它：插件只在 {@code Instrumentation.getAllLoadedClasses()} 里出现登记表内的
 * 语句/驱动类时，才会调用出问题的那段名单谓词。干净启动的小应用那一刻
 * {@code getAllLoadedClasses()} 里没有这类，插件打印 {@code matched=0} 直接跳过——
 * **崩溃点根本不会被触达**。这正是「同样的插件，有人崩有人不崩」的关键变量之一，也是
 * 本 demo 要显式造出该条件的原因。
 *
 * <p>它做的事与业务无关，仅为把目标类「暖」进 JVM；挂在插件 agent 之前即可
 * （{@code -javaagent} 按出现顺序执行 premain）。
 */
public final class WarmupAgent {

    /** 预热目标：必须逐字命中插件名单里的语句面类。 */
    private static final String TARGET = "com.mysql.cj.jdbc.StatementImpl";

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            Class.forName(TARGET, false, ClassLoader.getSystemClassLoader());
            System.out.println("[Warmup] preloaded " + TARGET);
        } catch (Throwable t) {
            // 预热失败不致命：后续插件会 matched=0，本 demo 的「不预热」对照格正好用到这个形态
            System.out.println("[Warmup] failed to preload " + TARGET + ": " + t);
        }
    }

    private WarmupAgent() {
    }
}
