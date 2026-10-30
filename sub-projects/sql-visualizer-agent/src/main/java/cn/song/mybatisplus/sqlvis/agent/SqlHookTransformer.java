package cn.song.mybatisplus.sqlvis.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * 织入 transformer（§4.2 #1）：只改方法体（钩子调用插桩），不增删方法/字段——
 * retransformClasses 合规（agentmain 已加载驱动路径的前提约束）。
 * §4.2 #6 classloader 契约在织入期收口：定义类 loader 解析不到回调类 → 整类不织入
 * （安全降级为无钩子）；可解析 → 注入零分支直线 invokestatic（回调自吞一切 Throwable）。
 *
 * <p>T-StatementWeave：三面路由——驱动 connect 面（ConnectWeaver，原有）／语句 execute 面／
 * prepare 登记面（StatementWeaver）。登记表三面不相交，同一类只落一面。
 *
 * <p><b>T-CaptureCatchAll：白名单优先 + 接口兜底（D1=A）。</b>分派顺序与代价阶梯（从便宜到贵）：
 * <ol>
 *   <li><b>白名单</b>（既有）：类名哈希表命中，走原通道。语义＝「已验证字节形态」，premain 安全集；</li>
 *   <li><b>attach 接口匹配接受表</b>（券 01）：{@link CatchAllMatcher} 在 attach 轮用反射判定
 *       {@code java.sql.Statement/Connection.isAssignableFrom}，把命中类名登记进接受表；本类据表
 *       走**同一** {@link StatementWeaver#weaveSpec} 通道（形态取保守兜底面，见
 *       {@link WeaveSpecs#catchAllStatementFace()}）。查表成本＝一次 CHM get；</li>
 *   <li><b>字节级兜底分派</b>（券 06，**默认关**）：裸字节子串 {@code java/sql/} 预筛
 *       （2–5µs/类）→ 命中才读类头（8–11µs/类）→ 确认才织。禁止对全部类做全树解析
 *       （200–515µs/类，2 万类应用多花约 0.6 秒，红线）。</li>
 * </ol>
 *
 * <p><b>券 05 版本闸</b>：任何 ASM 解析之前先读 {@code bytes[6..7]} 的 major，超 ASM 支持上限
 * 即原字节返回（返回 null）+ 计数。动因：ASM 9.4 读 {@code h2-2.3.232.jar} 的
 * {@code META-INF/versions/21} 类（major 65）抛 {@code Unsupported class file major version 65}。
 *
 * <p><b>红线</b>：本类的任何分支失败都只影响「该类不增强」——返回 null 让类按原字节装载，
 * 异常全吞并计数（不改变宿主返回值/对象身份）。
 */
public class SqlHookTransformer implements ClassFileTransformer {

    // ---------- attach 取证计数（T-AttachZeroEvent，2026-10-09） ----------
    //
    // 现场复盘：用户对运行中应用 attach 后「面板零新条目」，而 transform 层两种静默形态
    // （织入异常吞掉返回 null / 名单命中但织入器产出原字节）此前**零观测出口**——retransform
    // 「成功」与「真的织上了」无法区分，排障只能靠行为反推。四个计数均进程累计：
    // matched = 名单命中进入织入分派的类数；woven = 产出与原字节不同的类数（真织上）；
    // noChange = 名单命中但产出原字节（方法形态不匹配/可见性闸门降级）；failed = 织入抛异常
    // （返回 null，类按原字节装载 = 无钩子）。判读：matched>0 且 woven==0 即「attach 无钩子」，
    // 指向织入器/可见性闸门；failed>0 指向具体异常形态。
    private static final java.util.concurrent.atomic.AtomicLong COUNT_WEAVABLE =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong COUNT_WOVEN =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong COUNT_NO_CHANGE =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong COUNT_FAILED =
            new java.util.concurrent.atomic.AtomicLong();

    /** 裸字节预筛目标（券 06 第一段）：常量池里出现该子串才值得读类头。 */
    private static final byte[] JAVA_SQL_BYTES = {
            'j', 'a', 'v', 'a', '/', 's', 'q', 'l', '/'};

    /** 名单命中进入织入分派的累计类数（attach 取证读数）。 */
    public static long weavableCount() {
        return COUNT_WEAVABLE.get();
    }

    /** 产出与原字节不同（真织入落地）的累计类数。 */
    public static long wovenCount() {
        return COUNT_WOVEN.get();
    }

    /** 名单命中但产出原字节（无钩子）的累计类数。 */
    public static long noChangeCount() {
        return COUNT_NO_CHANGE.get();
    }

    /** 织入抛异常（返回 null = 无钩子）的累计类数。 */
    public static long failedCount() {
        return COUNT_FAILED.get();
    }

    /**
     * 类文件 major 超上限、原字节放行的累计类数（券 05）。
     *
     * <p>计数单一真相在 {@link CaptureBudget}（心跳 body 也读那里）——本方法只做转发，
     * 避免两处各自累加导致「日志读数与心跳读数不一致」这类排障陷阱。
     */
    public static long skippedByVersionCount() {
        return CaptureBudget.skippedByVersionCount();
    }

    /** 仅测试用：计数清零（用例间隔离）。版本闸计数归 {@link CaptureBudget} 管（心跳同源）。 */
    static void resetCountersForTest() {
        COUNT_WEAVABLE.set(0L);
        COUNT_WOVEN.set(0L);
        COUNT_NO_CHANGE.set(0L);
        COUNT_FAILED.set(0L);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        // 券 05：major 版本闸必须排在**任何 ASM 解析之前**——ASM 对更新的类文件直接抛
        // IllegalArgumentException（Unsupported class file major version N），虽被下面 catch 吞掉，
        // 但每类白付一次异常构造，且污染 failed 计数、掩盖真实失败形态。
        if (!ClassFileVersion.supported(classfileBuffer)) {
            CaptureBudget.noteSkippedByVersion();
            return null;
        }
        // instrument API 传内部名（斜杠）；登记表为点分名——归一化
        final String dotted = className.replace('/', '.');
        try {
            if (DriverRegistry.isWeavable(dotted)) {
                COUNT_WEAVABLE.incrementAndGet();
                final byte[] out = ConnectWeaver.weave(loader, classfileBuffer);
                noteOutcome(out, classfileBuffer);
                return out;
            }
            if (DriverRegistry.isStatementWeavable(dotted) || DriverRegistry.isPrepareWeavable(dotted)) {
                COUNT_WEAVABLE.incrementAndGet();
                final byte[] out = StatementWeaver.weave(loader, dotted, classfileBuffer);
                noteOutcome(out, classfileBuffer);
                return out;
            }
            // 券 01：attach 接口匹配轮接受过的类——同一织入通道，形态取保守兜底面。
            // kill switch 触发后连这条也不走（「退回纯白名单」的完整语义：接受表是兜底产物，
            // 熔断后即便残留条目也不得继续生效）。
            final StatementWeaver.Kind[] accepted =
                    CaptureBudget.attachMatchEnabled() ? CatchAllMatcher.acceptedSpec(dotted) : null;
            if (accepted != null) {
                COUNT_WEAVABLE.incrementAndGet();
                final byte[] out = StatementWeaver.weaveSpec(accepted, loader, classfileBuffer);
                noteOutcome(out, classfileBuffer);
                return out;
            }
            // 券 06：字节级兜底分派（默认关；受耗时预算与 kill switch 约束）
            if (CaptureBudget.transformMatchEnabled()) {
                return transformCatchAll(loader, dotted, classfileBuffer);
            }
            return null;
        } catch (Throwable t) {
            // 织入失败 = 该驱动不增强，绝不影响宿主应用启动/运行——不再无声：计数可观测
            // （T-AttachZeroEvent：单类失败此前完全静默，retransform「成功」掩盖「零钩子」）
            COUNT_FAILED.incrementAndGet();
            return null;
        }
    }

    /**
     * 券 06 兜底分派：两段式预筛 + 类头判定，确认才织。
     *
     * <p>成本阶梯（调研 §2.2 实测）：裸字节子串 2–5µs/类（命中率 11–15%）→ 类头 8–11µs/类。
     * 只在第一段命中后才做第二段，禁止对全部类直接全树解析。整段耗时记入
     * {@link CaptureBudget#noteTransformNanos}，超预算即关闭本分支（白名单不受影响）。
     */
    private static byte[] transformCatchAll(ClassLoader loader, String dotted, byte[] classfileBuffer) {
        final long startNanos = System.nanoTime();
        boolean matched = false;
        try {
            if (CatchAllMatcher.excludedName(dotted)) {
                return null;
            }
            if (!containsJavaSql(classfileBuffer)) {
                return null;
            }
            // 第二段：只读类头（access + interfaces），不做全树解析。
            // 用 ClassReader 的直接读口而非 accept(visitor)：两者都只解析常量池与类头，
            // 前者少一次 visitor 分派（严格更便宜），语义与计划 §券06 的
            // SKIP_CODE|SKIP_DEBUG|SKIP_FRAMES 头解析一致——都不会触碰方法体。
            final org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(classfileBuffer);
            final StatementWeaver.Kind[] spec =
                    CatchAllMatcher.specOfHeader(dotted, reader.getAccess(), reader.getInterfaces());
            if (spec == null) {
                return null;
            }
            matched = true;
            // 与白名单路径同口径：确认命中即计 weavable（「进入织入分派」），产出分类由 noteOutcome 记账
            COUNT_WEAVABLE.incrementAndGet();
            CaptureBudget.noteTransformMatched();
            final byte[] out = StatementWeaver.weaveSpec(spec, loader, classfileBuffer);
            noteOutcome(out, classfileBuffer);
            if (out != null && out != classfileBuffer && !java.util.Arrays.equals(out, classfileBuffer)) {
                CaptureBudget.noteTransformWoven();
            }
            CaptureBudget.noteCatchAllSuccess();
            return out;
        } catch (Throwable t) {
            // 兜底分支失败：该类原字节装载（返回 null）。只有「已确认匹配」后的失败才计入
            // 连败（kill switch 判据），纯预筛/解析失败不算——否则无关类的畸形字节会误熔断。
            if (matched) {
                COUNT_FAILED.incrementAndGet();
                CaptureBudget.noteCatchAllFailure(dotted + "=" + t.getClass().getSimpleName());
            }
            return null;
        } finally {
            CaptureBudget.noteTransformNanos(System.nanoTime() - startNanos);
        }
    }

    /**
     * 裸字节子串预筛：类常量池里是否出现 {@code java/sql/}。
     *
     * <p>朴素扫描而非 {@code new String(bytes)}：后者要为每个类分配 2×长度字符，2 万类应用的
     * 分配量在启动路径上不可接受。首字节不同即跳过，平均比较次数接近 1。
     */
    static boolean containsJavaSql(byte[] bytes) {
        if (bytes == null || bytes.length < JAVA_SQL_BYTES.length) {
            return false;
        }
        final int last = bytes.length - JAVA_SQL_BYTES.length;
        for (int i = 0; i <= last; i++) {
            if (bytes[i] != JAVA_SQL_BYTES[0]) {
                continue;
            }
            int j = 1;
            while (j < JAVA_SQL_BYTES.length && bytes[i + j] == JAVA_SQL_BYTES[j]) {
                j++;
            }
            if (j == JAVA_SQL_BYTES.length) {
                return true;
            }
        }
        return false;
    }

    /** 织入产出分类记账：与原字节不同 = 真织上；同字节/null = 无钩子（noChange）。 */
    private static void noteOutcome(byte[] out, byte[] original) {
        if (out != null && out != original && !java.util.Arrays.equals(out, original)) {
            COUNT_WOVEN.incrementAndGet();
        } else {
            COUNT_NO_CHANGE.incrementAndGet();
        }
    }
}
