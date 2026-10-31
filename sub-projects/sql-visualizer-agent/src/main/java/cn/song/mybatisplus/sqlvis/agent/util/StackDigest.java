package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.AgentState;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用栈快照压缩（T-SourceJumpFunnel P2-2；T-StackHoverUi 两端采样改版）：execute 时把全栈压成
 * 「pkg.Class#method:line」单串（← 连接，栈顶在左），供 IDE 详情面板往上追踪。
 *
 * <p>预算（T-StackHoverUi 用户可配）：总帧数 B=[AgentState#getStackMaxFrames]（默认 24，
 * 钳位 [12,64]）。**栈底锚点先跳过尾部基础设施帧**（线程容器/线程池/JDK，见
 * {@link #isInfraFrame}——用户真机实测：Tomcat 尾巴会把栈底 8 帧占满、Controller 被挤进省略段），
 * 锚定最深业务帧后：深度 ≤B 全链保留（原行为）；&gt;B 两端采样——栈顶取 B-8 帧
 * （引擎/门面侧），栈底取最深业务帧起向上 8 帧（业务/Controller 侧），中段以标记段
 * {@code <…中间省略 N 帧…>} 连接（IDE 新视图展示为省略节点，旧解析器仍可跳过无 {@code #} 的段）。
 * 非白名单尾帧仅在同应用包根的两条调用帧与 Controller 可关联时穿透；
 * 这是 Web 调用链的定向增强，不是对所有业务类的通用身份判定；无法确认则保留原采样兜底。
 *
 * <p>总长硬顶 MAX_LEN=max(2000, B×96)：两端形态下超长先缩栈顶再出标记；
 * 单帧异常超过 200 字符时保留首尾并压缩中间，以防底段本身突破硬顶。
 * 被压缩的极长类名不保证 IDE 侧能解析/跳转；正常长度的栈底业务帧完整保留，
 * 全链形态沿用旧尾截语义。
 *
 * <p>T-PS4-09 一次捕获两用：产线主路径经 locate 同一快照调 {@link #build(StackTraceElement[])}
 * （读 [AgentState] 配置）；显式帧数出口 {@link #build(StackTraceElement[], int)} 为纯函数
 * （单测用）。过滤规则不变：仅跳 Thread#getStackTrace 自身帧，业务帧与 agent 帧均不过滤。
 *
 * <p><b>T-StackBizFrame（2026-10-29 报障：定时任务线程下栈里看不到项目调用类）</b>：栈底锚点改为
 * 三层判定，且判据与定位侧同源——
 * <ol>
 *   <li><b>层一 业务锚点</b>：从栈底往上取首个 {@link StackLocator#isBusinessFrame} 为真的帧
 *       （业务来源 + 真实源文件）。原实现只认本类自己的 infra 白名单，而名单收的是容器/JDK/
 *       Servlet 管道与 spring web/boot，<b>不含</b> {@code org.springframework.scheduling.*} 与
 *       {@code io.micrometer.*}——定时任务/异步池/MQ 线程的栈尾恰是这两族，穿透在栈底第一帧就停住，
 *       锚点=栈底，业务帧全被挤进省略段（省略段是采集期丢弃的，弹层展开也补不回来）。</li>
 *   <li><b>层二 框架穿透兜底</b>：层一未命中（fat jar 部署，业务类也来自 JAR）时，穿透条件扩为
 *       {@code isInfraFrame || StackLocator.isFrameworkFrame}——复用定位侧六十余项前缀表，
 *       不另立第二套会漂移的名单。</li>
 *   <li><b>层三 字面兜底</b>：仍无候选（全框架栈）→ 末帧，行为与旧实现一致。</li>
 * </ol>
 * Controller 补偿（{@link #findBusinessController}）<b>只在层一未命中时</b>应用：层一命中说明已有
 * 更强的业务证据，再改回 Controller 会把更外层的业务帧挤出窗口。
 *
 * <p><b>定位帧必在栈内（S2）</b>：{@link #build(StackTraceElement[], ClassLoader, StackLocator.Frame)}
 * 接收 locate 的结果，若该帧落在顶部窗口与底部窗口之外，则单列一段插在省略标记之前——否则
 * 「meta 行显示项目类、栈里没有」这条直读观感缺陷仍然存在（项目帧分散在长跨度上时就会发生）。
 * 多段省略/插入段对 IDE 侧解析器是既有能力（`StackDigestFrames.parseItems` 按段匹配）。
 */
public final class StackDigest {

    /** 栈底固定保留帧数（业务/Controller 侧，T-StackHoverUi A 案）。 */
    private static final int BOTTOM_RESERVED = 8;

    /** 帧间连接符（IDE 侧 `StackDigestFrames.FRAME_SEPARATOR` 逐字契约，改则两侧同改）。 */
    private static final String FRAME_ARROW = "<-";

    /** 总帧数钳位下限（保证栈顶至少 4 帧）。 */
    private static final int MIN_FRAMES = 12;

    /** 总帧数钳位上限（事件体可控）。 */
    private static final int MAX_FRAMES_CAP = 64;

    /** 无参出口（兼容/测试）：当前线程全栈 + AgentState 配置预算。 */
    public static String build() {
        return build(Thread.currentThread().getStackTrace());
    }

    /** 产线出口：以 AgentState 配置预算压缩给定快照（T-PS4-09 与 locate 共享同一捕获）。 */
    public static String build(StackTraceElement[] stack) {
        return build(stack, AgentState.getStackMaxFrames(), null, null, null);
    }

    /** 纯函数（单测用）：maxFrames 为用户配置的总帧数（内部钳位）。 */
    public static String build(StackTraceElement[] stack, int maxFrames) {
        return build(stack, maxFrames, null, null, null);
    }

    /**
     * T-StackBizFrame 产线出口：除预算外还接收 locate 的 loader hint 与定位结果。
     *
     * <p>hint 是必需的而不是可选的：{@link StackLocator#isBusinessFrame} 按 codeSource 判来源，
     * 类加载不到就退化为「未知」、层一整体失效；而 agent 跑在宿主线程上，上下文 loader 未必
     * 看得见应用类（驱动侧 hint 才是能看见应用类的那个，见调用点注释）。
     */
    public static String build(StackTraceElement[] stack, ClassLoader hint, StackLocator.Frame located) {
        return build(stack, AgentState.getStackMaxFrames(), hint,
                located == null ? null : located.primaryClassName,
                located == null ? null : located.primaryMethod);
    }

    /**
     * 全参出口（单测直达）。
     *
     * @param hint         能看见应用类的 loader；null 退上下文 loader
     * @param anchorClass  定位帧 FQCN（null = 不强制包含）
     * @param anchorMethod 定位帧方法名（null = 不强制包含）
     */
    public static String build(StackTraceElement[] stack, int maxFrames, ClassLoader hint,
                               String anchorClass, String anchorMethod) {
        if (stack == null || stack.length == 0) {
            return null;
        }
        final List<StackTraceElement> frames = new ArrayList<>(stack.length);
        for (StackTraceElement f : stack) {
            // [0]=Thread.getStackTrace 自身：跳过
            if (f.getClassName().equals(Thread.class.getName()) && "getStackTrace".equals(f.getMethodName())) {
                continue;
            }
            frames.add(f);
        }
        if (frames.isEmpty()) {
            return null;
        }
        final ClassLoader loader = resolveLoader(hint);
        final int budget = clamp(maxFrames);
        final long maxLen = Math.max(2000L, (long) budget * 96L);
        final int lastBusiness = resolveBottomAnchor(frames, loader);
        final int anchorAt = indexOfIdentity(frames, anchorClass, anchorMethod);
        // 定位帧在锚点之外（理论形态：锚点由层二选出而定位帧更靠栈底）时一并纳入，避免整链形态下丢帧
        final int depth = Math.max(lastBusiness, anchorAt) + 1;
        if (depth <= budget) {
            return join(frames, 0, depth, maxLen);
        }
        // 两端采样：栈顶 + 中段标记 + 栈底；超长先缩栈顶（缩掉的顶帧计入省略数），业务帧永不因长度被截
        final int topCount = Math.max(1, budget - BOTTOM_RESERVED);
        // 栈底取「最深业务帧起向上」的 BOTTOM_RESERVED 帧（可能含业务链中段夹带的反射/代理帧——
        // 属业务路径上下文，保留）；被跳过的尾部 infra 帧不占省略计数（纯噪音不出现在快照里）
        final int bStart = Math.max(topCount, lastBusiness - (BOTTOM_RESERVED - 1));
        // T-StackBizFrame S2：定位帧落在两个窗口之外时单列一段，插在省略标记之前
        final boolean anchorInWindow = anchorAt < 0 || anchorAt < topCount
                || (anchorAt >= bStart && anchorAt <= lastBusiness);
        final String anchorText = anchorInWindow ? null : join(frames, anchorAt, 1, Long.MAX_VALUE);
        String top = join(frames, 0, topCount, Long.MAX_VALUE);
        String bottom = join(frames, bStart, lastBusiness - bStart + 1, Long.MAX_VALUE);
        int elided = bStart - topCount - (anchorText == null ? 0 : 1);
        String marker = elided > 0 ? elisionMarker(elided) : "";
        int curTop = topCount;
        final int anchorLen = anchorText == null ? 0 : anchorText.length() + FRAME_ARROW.length();
        while (curTop > 1 && top.length() + anchorLen + marker.length() + bottom.length() + 4L > maxLen) {
            curTop--;
            elided++;
            top = join(frames, 0, curTop, Long.MAX_VALUE);
            marker = elided > 0 ? elisionMarker(elided) : "";
        }
        final StringBuilder out = new StringBuilder(
                top.length() + anchorLen + marker.length() + bottom.length() + 3 * FRAME_ARROW.length());
        out.append(top);
        if (anchorText != null) {
            out.append(FRAME_ARROW).append(anchorText);
        }
        if (marker.length() > 0) {
            out.append(FRAME_ARROW).append(marker);
        }
        out.append(FRAME_ARROW).append(bottom);
        return out.toString();
    }

    /**
     * 栈底锚点三层判定（T-StackBizFrame S1）——返回 {@code frames} 下标，恒 ≥ 0。
     *
     * <p>层一必须用与定位侧同源的 {@link StackLocator#isBusinessFrame}：摘要与定位是同一份快照上的
     * 两条判定，各自维护「什么算业务帧」必然漂移，漂移后果就是「位置列有项目类、栈里没有」。
     * 层二只在层一未命中时接管（fat jar 部署：业务类也来自 JAR，来源维度不可辨）。
     */
    private static int resolveBottomAnchor(List<StackTraceElement> frames, ClassLoader loader) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (StackLocator.isBusinessFrame(frames.get(i), loader)) {
                return i;
            }
        }
        int anchor = frames.size() - 1;
        while (anchor >= 0 && isTailInfra(frames.get(anchor))) {
            anchor--;
        }
        if (anchor < 0) {
            // 全 infra 极端形态兜底：退回字面末帧
            return frames.size() - 1;
        }
        // Controller 补偿只在层一未命中时应用（理由见类注释）
        final int controller = findBusinessController(frames, frames.size() - 1, loader);
        return controller >= 0 ? controller : anchor;
    }

    /** 尾部穿透条件：容器/JDK 白名单 或 定位侧框架前缀表（T-StackBizFrame 层二）。 */
    private static boolean isTailInfra(StackTraceElement f) {
        return isInfraFrame(f) || StackLocator.isFrameworkFrame(f.getClassName());
    }

    /** 按 FQCN + 方法名在快照里定位（S2）；任一为空即视为「无需强制包含」，返回 -1。 */
    private static int indexOfIdentity(List<StackTraceElement> frames, String className, String method) {
        if (className == null || method == null) {
            return -1;
        }
        for (int i = 0; i < frames.size(); i++) {
            final StackTraceElement f = frames.get(i);
            if (className.equals(f.getClassName()) && method.equals(f.getMethodName())) {
                return i;
            }
        }
        return -1;
    }

    /** 来源判定用的 loader：显式 hint 优先，其次上下文 loader，最后本类 loader。 */
    private static ClassLoader resolveLoader(ClassLoader hint) {
        if (hint != null) {
            return hint;
        }
        final ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        return ctx != null ? ctx : StackDigest.class.getClassLoader();
    }

    /**
     * 在未知尾帧存在时，选择栈中最后一组同应用包根的调用帧与 Controller；
     * 不把 com、org、cn 等顶层命名空间当成应用身份。框架命名空间里的项目类
     * 仅在目录来源或已登记业务 JAR 包名前缀时采用，无法确认则沿用原采样。
     */
    private static int findBusinessController(List<StackTraceElement> frames, int end, ClassLoader loader) {
        for (int i = end; i >= 0; i--) {
            final String controller = frames.get(i).getClassName();
            if (!simpleName(controller).endsWith("Controller") || !isApplicationCandidate(controller, loader)) {
                continue;
            }
            final String root = applicationRoot(controller);
            if (root == null) {
                continue;
            }
            for (int j = i - 1; j >= Math.max(0, i - 24); j--) {
                final String caller = frames.get(j).getClassName();
                if (!caller.equals(controller) && caller.startsWith(root) && isApplicationCandidate(caller, loader)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isApplicationCandidate(String className, ClassLoader loader) {
        if (StackLocator.isRegisteredBusinessFrame(className) || !StackLocator.isFrameworkFrame(className)) {
            return true;
        }
        return Boolean.FALSE.equals(StackLocator.loadedFromJar(className, loader));
    }

    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String applicationRoot(String className) {
        int dot = className.indexOf('.');
        if (dot < 0) return null;
        int second = className.indexOf('.', dot + 1);
        if (second < 0) return null;
        int third = className.indexOf('.', second + 1);
        if (className.startsWith("cn.song.") && third >= 0) {
            return className.substring(0, third + 1);
        }
        if (className.startsWith("org.springframework.") && third >= 0) {
            int fourth = className.indexOf('.', third + 1);
            return fourth >= 0 ? className.substring(0, fourth + 1) : null;
        }
        return className.substring(0, second + 1);
    }

    /**
     * 尾部基础设施帧判定（只用于栈底锚点，不改栈顶）——线程容器/线程池/JDK/Servlet 过滤器链的
     * 「尾巴」帧。用户链两轮实测：①尾部 8 帧=Thread/ThreadPoolExecutor/NioEndpoint/coyote；
     * ②线程池跳过后锚点又落到 spring 过滤器链（ApplicationFilterChain/OncePerRequestFilter），
     * Controller 仍在省略段。名单收容器/JDK/Servlet 管道与 spring web（filter+servlet MVC 链）+
     * spring boot（devtools 重启线程等）：这些在栈尾永远是管道；**spring 业务中间件不收**
     * （aop/tx/context——业务栈中段的 AOP/代理链属于上下文，跳了会把 Controller 与 Service 拆断；
     * 栈尾方向 com.sun.proxy 的 JDK 代理帧由既有 com.sun. 前缀覆盖，恰好穿透 AOP 壳锚到业务类）。
     */
    private static boolean isInfraFrame(StackTraceElement f) {
        final String cn = f.getClassName();
        return cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("sun.")
                || cn.startsWith("com.sun.") || cn.startsWith("jdk.")
                || cn.startsWith("org.apache.tomcat.") || cn.startsWith("org.apache.catalina.")
                || cn.startsWith("org.apache.coyote.") || cn.startsWith("org.apache.juli.")
                || cn.startsWith("org.eclipse.jetty.") || cn.startsWith("io.undertow.")
                || cn.startsWith("org.springframework.web.")
                || cn.startsWith("org.springframework.security.web.")
                || cn.startsWith("org.springframework.boot.");
    }

    /** 中段省略标记段（无 {@code #}：新 UI 展示省略节点，旧解析器可跳过）。 */
    private static String elisionMarker(int elided) {
        return "<…中间省略 " + elided + " 帧…>";
    }

    private static int clamp(int maxFrames) {
        if (maxFrames < MIN_FRAMES) {
            return MIN_FRAMES;
        }
        if (maxFrames > MAX_FRAMES_CAP) {
            return MAX_FRAMES_CAP;
        }
        return maxFrames;
    }

    /**
     * 把 frames[from, from+count) 逐帧追加为「类#方法:行」（← 连接）；maxLen 为总长硬顶，
     * 超长截断加 … 后缀（语义与旧实现逐字一致）；maxLen 传 Long.MAX_VALUE 表示不截（由调用方统管）。
     */
    private static String join(List<StackTraceElement> frames, int from, int count, long maxLen) {
        final StringBuilder sb = new StringBuilder(256);
        int used = 0;
        for (int i = from; i < frames.size() && used < count; i++) {
            final StackTraceElement f = frames.get(i);
            if (used > 0) {
                sb.append("<-");
            }
            String text = f.getClassName() + '#' + f.getMethodName();
            final int line = f.getLineNumber();
            if (line >= 0) {
                text += ":" + line;
            }
            // 恶意/生成类名或方法名可能单帧超过整串预算。保留两端及方法尾，
            // 保证底段即使 8 个极长帧也有可控的长度上界。
            if (text.length() > 200) {
                text = text.substring(0, 96) + '…' + text.substring(text.length() - 103);
            }
            sb.append(text);
            used++;
            if (sb.length() >= maxLen) {
                sb.append("…");
                break;
            }
        }
        final String s = sb.length() > maxLen ? sb.substring(0, (int) maxLen) + "…" : sb.toString();
        return used == 0 ? "" : s;
    }

    private StackDigest() {
    }
}
