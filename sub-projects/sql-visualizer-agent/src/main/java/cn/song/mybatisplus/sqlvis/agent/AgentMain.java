package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.transport.EventBus;

import java.lang.instrument.Instrumentation;

/**
 * M56 agent 双入口（premain / agentmain 同类，§4.2 #3）：premain 需求池补位「启动即捕获」，
 * 主路径 attach + agentmain——agentmain 必须挂 transformer（可重转换）并对已加载驱动类
 * retransform（DebugTools 半成品陷阱的硬验收项）。
 *
 * 幂等：system property {@code sqlvis.agent.active} 标记（IDE 侧 pid 表双保险）——
 * 二次 loadAgent 直接返回，不再叠加 transformer/代理。
 *
 * 勘误（T-FirstUsePremainConsent S3，2026-10-05）：「AgentLoadException:0 只出现在 JDK8 二次
 * 挂载」的旧口径不准——243 沙盒（attach 目标为现代 JDK）**首次**挂载也观察到该异常（idea.log
 * 10:15:23 实录），随后握手成功证明 agent 实际装载。该异常只作「结果以握手为准」的容错信号，
 * 不作为幂等判据，IDE 侧容错见 AgentManager。
 *
 * <p><b>T-JRebelPremainCrash（2026-10-22）：premain 全域失败兜底。</b>此前 {@code install} 内部
 * 的 {@code retransformLoadedDrivers} 及其三连谓词（{@code DriverRegistry.isWeavable/
 * isStatementWeavable/isPrepareWeavable}）都在 try 之外——任何 {@code LinkageError} 都会穿出
 * {@code premain}，而 premain 抛异常对 JVM 是**致命**的（{@code -javaagent} 失败 ⇒
 * {@code processJavaStart failed}，目标应用起不来）。用户现场：JRebel 接管 premain 类加载后
 * 重定位 ASM 包不可见，{@code DriverRegistry.isStatementWeavable} 触发 {@code StatementWeaver}
 * 类初始化时抛 {@code NoClassDefFoundError: cn/song/mybatisplus/sqlvis/agent/asm/Opcodes}
 * （另一种加载器形态下为 {@code ClassCircularityError}），应用直接无法启动。
 *
 * <p>取舍：**宁可少采集，也不能杀宿主**。故本类在 premain/agentmain 双入口统一包
 * {@code catch (Throwable)}——含 {@code Error}（{@code ClassCircularityError} 与 NCDFE 只有
 * 共同父类 {@code Throwable}）。降级后**不写** {@code sqlvis.agent.active}，让 IDE 侧的
 * attach 兜底仍可工作（写标记会让 attach 误判「已装」而永不重试）。
 */
public final class AgentMain {

    /** 在线标记 key：值 = "port=<n>"。 */
    public static final String ACTIVE_MARKER = "sqlvis.agent.active";

    private static volatile boolean installed = false;

    /** 降级说明前缀（宿主 stderr 唯一出口；不含 SQL 原文/凭据/全路径）。 */
    static final String DEGRADE_PREFIX = "[SqlVisAgent] install degraded: ";

    // 尽早定义 DriverRegistry（AgentMain 自身 <clinit> 内）——消除 attach 线程与并发
    // transform 调用对同一类的首次定义竞争窗口（实测双 define LinkageError 规避）。
    // T-JRebelPremainCrash：失败不再无声——预定义失败是「本进程内 ASM 可能不可见」的第一现场
    // 证据，此前 catch(Throwable ignore) 把它彻底抹掉，导致现场只能看到更靠外的一帧。
    static {
        try {
            Class.forName(DriverRegistry.class.getName(), true, AgentMain.class.getClassLoader());
        } catch (Throwable t) {
            // 只留一行类型 + 消息，不含宿主路径；不阻断（真正的降级判定在 install）
            System.err.println("[SqlVisAgent] preload DriverRegistry failed: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst, true);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst, false);
    }

    /**
     * 双入口共用安装链。**任何 Throwable 都不得外抛**——premain 路径外抛会让 JVM 判定
     * {@code -javaagent} 失败并中止启动（T-JRebelPremainCrash 用户现场实录）。
     *
     * <p>失败时：{@code installed} 保持 false、不写 {@link #ACTIVE_MARKER}，stderr 留一行
     * {@link #DEGRADE_PREFIX} 说明；后续 attach 兜底照旧可用。
     */
    private static synchronized void install(String agentArgs, Instrumentation inst, boolean premain) {
        try {
            installOrThrow(agentArgs, inst, premain);
        } catch (Throwable t) {
            // 不再触碰任何织入器/注册表类——类初始化已失败的类再次触达可能二次
            // ClassCircularityError / ExceptionInInitializerError（B3 格实测形态）
            degrade(t);
        }
    }

    private static void installOrThrow(String agentArgs, Instrumentation inst, boolean premain) {
        if (installed) {
            return;
        }
        final AgentArgs args = AgentArgs.parse(agentArgs);
        if (args.getPort() <= 0) {
            // 无端口可上报：事件无处可发，直接放弃（IDE 侧 attach 前必下发端口）
            return;
        }
        // 幂等（双保险之 agent 侧）：先于 transformer 安装检查
        final String prev = System.getProperty(ACTIVE_MARKER);
        if (prev != null) {
            return;
        }

        AgentState.setLogLevel(args.getLogLevel());
        // 2026-10-30 用户令「控制台 [SqlVisAgent] 输出只保留必要的」：诊断/进度行默认静默，
        // logLevel=debug 时才打（真实降级/熔断仍走 diagAlways 必报）。级别从本处推进 CaptureBudget，
        // 不让它反向依赖 AgentState（premain 侧类初始化风险，见 CaptureBudget KDoc）。
        CaptureBudget.setVerboseLogging(args.getLogLevel());
        // T-StackHoverUi：attach/premain 参数即初始化调用栈快照帧数预算（运行期可经 CONFIG 帧热更新）
        AgentState.setStackMaxFrames(args.getStackMaxFrames());
        // transformer 一律 canRetransform=true：premain 路径新加载驱动直接织入；
        // agentmain 路径对已加载驱动 retransform（只改方法体，不增删成员——ConnectWeaver 合规）
        inst.addTransformer(new SqlHookTransformer(), true);
        retransformLoadedDrivers(inst);
        // T-CaptureCatchAll：白名单优先 + 接口匹配兜底（D1=A）。顺序不可颠倒——兜底轮先按白名单
        // 谓词排除已处理类。premain 阶段只记候选（券 08）：premain 内联未知字节形态失败会让宿主
        // 起不来，这是红线；真正的接口匹配交给启动后补挂轮（daemon 线程，有限轮数 + 预算）。
        CatchAllMatcher.afterInstall(inst, premain);
        EventBus.start(args.getPort());
        System.setProperty(ACTIVE_MARKER, "port=" + args.getPort());
        installed = true;
    }

    /**
     * 降级出口：一行结构化说明 + 不写 active 标记（让 attach 兜底仍可重试）。
     *
     * <p>只做纯字符串拼接与 System.err，**不解析/不实例化任何业务类**——已在类初始化失败的
     * 进程里，任何额外触达都可能二次抛错。
     */
    private static void degrade(Throwable t) {
        String msg;
        try {
            msg = String.valueOf(t);
        } catch (Throwable ignore) {
            // 连 toString 都炸（畸形 Throwable）时退到类名
            msg = t.getClass().getName();
        }
        try {
            System.err.println(DEGRADE_PREFIX + msg
                    + " | enhanced capture unavailable in this JVM; run-time attach fallback remains active");
        } catch (Throwable ignore) {
            // stderr 不可用（已关闭/被接管）——静默，绝不因诊断出口二次抛错
        }
    }

    /**
     * 对已加载的驱动类执行 retransform（agentmain 门禁：SQL 事件须在应用已运行后到达）。
     *
     * T-StatementWeave S5：扩展为**三面**——驱动 connect 面（原有）+ 语句 execute 面 + prepare
     * 登记面。语句面是「attach 即生效」的直接落点：钩子挂在 execute 族方法体上，retransform 后
     * **存量**语句对象的下一次执行即带钩子，与连接/语句对象的创建时间无关（池化老连接由此覆盖——
     * connect 面做不到：包装只发生在连接创建时，存量连接不可回溯）。
     *
     * T-AttachZeroEvent（2026-10-09）：不再静默——attach 现场零事件时此前无从区分「名单没命中/
     * isModifiableClass 拒绝/retransform 被 JVM 拒/transform 产出原字节」四种失败形态，只能靠行为
     * 反推。现逐类计数 + 汇总一行 stderr（宿主应用控制台可见）：单类失败仍不阻断整体，但**不再无声**。
     * 判读：wove>0 而 transformer wovenCount==0 ⇒ transform 层静默 no-change（织入器/可见性闸门）；
     * failed>0 ⇒ JVM 拒绝（VerifyError 等），失败类名随行列出。
     *
     * T-JRebelPremainCrash：三连谓词移入单类 try——谓词本身会触发织入器类初始化（
     * {@code StatementWeaver.specOf} → {@code <clinit>}），在第三方 agent 干扰 ASM 可见性时
     * **谓词就会抛**，而此前它位于 try 之外，于是一个类的类初始化失败会中止整轮 retransform
     * 并穿出 premain。现在单类失败只计入 {@code predicateFailed} 并继续，附独立计数便于区分
     * 「名单判定炸」与「retransform 炸」。
     */
    private static void retransformLoadedDrivers(Instrumentation inst) {
        Class<?>[] loaded = inst.getAllLoadedClasses();
        int matched = 0;
        int wove = 0;
        int unmodifiable = 0;
        int failed = 0;
        int predicateFailed = 0;
        final StringBuilder failures = new StringBuilder();
        final StringBuilder predicateFailures = new StringBuilder();
        for (Class<?> c : loaded) {
            final String name;
            try {
                name = c.getName();
            } catch (Throwable t) {
                // 取名字失败（极端畸形类）：静默跳过该类，不阻断整体
                continue;
            }
            final boolean weavable;
            try {
                // ServletWeaver implements Opcodes（ASM），不能在此处调用它的 matches——那会触发
                // 织入器类初始化，正是 T-JRebelPremainCrash 要防的崩溃点。改为零 ASM 的字符串判断。
                weavable = DriverRegistry.isWeavable(name)
                        || DriverRegistry.isStatementWeavable(name)
                        || DriverRegistry.isPrepareWeavable(name)
                        || isServletWeavableName(name);
            } catch (Throwable t) {
                // 名单判定炸（典型：织入器类初始化失败 → NCDFE/ClassCircularityError）：
                // 该类跳过，其余继续；单独计数以便与 retransform 失败区分
                predicateFailed++;
                if (predicateFailures.length() < 400) {
                    predicateFailures.append(t.getClass().getSimpleName()).append(' ');
                }
                continue;
            }
            if (!weavable) {
                continue;
            }
            matched++;
            // T-CaptureCatchAll 券 03：名单轮命中进心跳（IDE 侧「本次 attach 命中多少」的另一半）
            CaptureBudget.noteAttachMatched();
            if (!inst.isModifiableClass(c)) {
                unmodifiable++;
                CaptureBudget.noteAttachUnmodifiable();
                continue;
            }
            try {
                inst.retransformClasses(c);
                wove++;
            } catch (Throwable t) {
                // 单类失败不阻断整体（版本漂移/不可重转换场景），该驱动降级为不增强——计数 + 类名留痕
                failed++;
                if (failures.length() < 400) {
                    failures.append(name).append('=').append(t.getClass().getSimpleName()).append(' ');
                }
            }
        }
        // 每次挂载至多一行（premain/agentmain 同走 install），但**只在有问题或 verbose 时打**：
        // 全零的 matched/wove 对用户没有信息量（IDE 侧「本次 attach 命中多少」由心跳的
        // AgentAttachStats 进诊断行承载）。2026-10-30 用户令：控制台输出只保留必要的。
        if (CaptureBudget.verboseLogging || failed > 0 || predicateFailed > 0) {
            System.err.println("[SqlVisAgent] attach retransform: matched=" + matched
                + " wove=" + wove + " unmodifiable=" + unmodifiable + " failed=" + failed
                + (predicateFailed > 0 ? " predicateFailed=" + predicateFailed : "")
                + (failures.length() > 0 ? " [" + failures.toString().trim() + "]" : "")
                + (predicateFailures.length() > 0 ? " [predicate: " + predicateFailures.toString().trim() + "]" : "")
                + " | transformer: weavable=" + SqlHookTransformer.weavableCount()
                + " woven=" + SqlHookTransformer.wovenCount()
                + " noChange=" + SqlHookTransformer.noChangeCount()
                + " transformFailed=" + SqlHookTransformer.failedCount());
        }
    }

    /**
     * Servlet 家族名单判定，零 ASM 依赖。
     *
     * <p>与 {@link ServletWeaver#matches(String)} 同判据，但该类的静态方法所在类 {@code implements Opcodes}，
     * 调用会触发其类初始化；本方法只做字符串比较，供 premain 的名单扫描安全使用。
     */
    private static boolean isServletWeavableName(String name) {
        return "javax.servlet.http.HttpServlet".equals(name) || "jakarta.servlet.http.HttpServlet".equals(name);
    }

    private AgentMain() {
    }
}
