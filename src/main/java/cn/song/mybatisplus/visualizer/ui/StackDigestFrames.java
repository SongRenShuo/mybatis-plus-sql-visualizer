// T-SyncK2JMaster482 波D2 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/StackDigestFrames.kt
// （KOTLIN_TO_JAVA_RULES §2：Kotlin object 仅静态成员 → Java 全 static 承载类；Kotlin 测试树按
//   StackDigestFrames.parse(...) 静态形态直呼）
package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * T-StackHoverUi：调用栈快照串的结构化解析与调试器 Frames 风格展示模型。
 *
 * <p>快照串由 agent 侧 {@code StackDigest.build(StackTraceElement[])} 产出，格式
 * {@code pkg.Class#method:line<-pkg.Class#method:line<-…}（{@code <-} 连接、栈顶在左、行号负数不写、
 * 帧数随配置钳位在 12～64；深栈采样时用独立的中段省略标记）。保留已采集帧与省略段
 * 的原始次序，供 {@link StackFrameHover} 展示与折叠。</p>
 */
public final class StackDigestFrames {

    /** 帧间连接符（agent 侧 StackDigest 逐字契约）。 */
    public static final String FRAME_SEPARATOR = "<-";

    /** agent 侧截断标记（帧数触顶追加在完整末帧之后；长度触顶切在半帧中段，二者均以 `…` 收尾）。 */
    public static final String TRUNCATION_MARK = "…";

    /** agent 自身帧的包前缀（弹层里淡灰降权，模拟调试器「库帧」弱化样式）。 */
    public static final String AGENT_PACKAGE_PREFIX = "cn.song.mybatisplus.sqlvis.";

    private StackDigestFrames() {
    }

    /**
     * 把快照串拆回帧列表（栈顶在左，展示序与串序一致）。解析失败容错到底：
     * 无 `#` 的残段跳过、整串不可解析返回空表（调用方回退纯文本形态），永不抛异常。
     *
     * <p>截断串（结尾 `…`）的末段一律丢弃：触顶末段要么是带标记的完整帧、要么是被 2000 字符
     * 硬截切出的半帧（后者解析后方法名是残缺前缀，展示会造假），丢 1 帧换来「不展示残帧」。</p>
     */
    @NotNull
    public static List<StackFrameEntry> parse(@Nullable String digest) {
        List<StackFrameEntry> frames = new ArrayList<>();
        for (StackDigestItem item : parseItems(digest)) {
            if (item instanceof StackDigestItem.Frame frame) frames.add(frame.entry());
        }
        return frames;
    }

    @NotNull
    public static List<StackDigestItem> parseItems(@Nullable String digest) {
        if (digest == null || digest.isBlank()) return List.of();
        String trimmedDigest = digest.trim();
        if (trimmedDigest.isEmpty()) return List.of();
        boolean truncated = trimmedDigest.endsWith(TRUNCATION_MARK);
        String s = truncated
                ? trimmedDigest.substring(0, trimmedDigest.length() - TRUNCATION_MARK.length())
                : trimmedDigest;
        if (s.isEmpty()) return List.of();
        String[] segments = s.split(FRAME_SEPARATOR, -1);
        // 截断串末段一律丢弃：触顶末段要么是带标记的完整帧（丢 1/16 帧无伤）、要么是被 2000 字符
        // 硬截切出的半帧（解析后方法名是残缺前缀，展示会造假）——两者按「丢弃」统一处理。
        List<String> usable;
        if (truncated && segments.length > 0) {
            usable = new ArrayList<>();
            for (int i = 0; i < segments.length - 1; i++) usable.add(segments[i]);
        } else {
            usable = new ArrayList<>();
            for (String seg : segments) usable.add(seg);
        }
        List<StackDigestItem> items = new ArrayList<>(usable.size() + 1);
        for (String seg : usable) {
            String seg1 = seg.trim();
            if (seg1.isEmpty()) continue;
            java.util.regex.Matcher omitted = OMITTED_SEGMENT.matcher(seg1);
            if (omitted.matches()) {
                items.add(new StackDigestItem.Omitted(parseIntegerOrNull(omitted.group(1))));
            } else {
                StackFrameEntry frame = parseFrame(seg1);
                if (frame != null) items.add(new StackDigestItem.Frame(frame));
            }
        }
        if (truncated) items.add(new StackDigestItem.Omitted(null));
        return items;
    }

    private static final java.util.regex.Pattern OMITTED_SEGMENT =
            java.util.regex.Pattern.compile("^<…中间省略 ([0-9]+) 帧…>$");

    public static boolean isKnownInternal(@NotNull StackFrameEntry frame) {
        String name = frame.fqcn();
        List<String> knownClasses = List.of(
                "org.apache.catalina.core.ApplicationFilterChain",
                "org.springframework.web.servlet.DispatcherServlet",
                "org.springframework.web.servlet.FrameworkServlet",
                "org.springframework.web.filter.OncePerRequestFilter",
                "org.springframework.web.filter.FormContentFilter",
                "org.springframework.web.filter.CharacterEncodingFilter",
                "org.springframework.web.filter.RequestContextFilter",
                "org.springframework.web.filter.DelegatingFilterProxy",
                "org.springframework.security.web.FilterChainProxy");
        if (isAgentFrame(frame)) return true;
        for (String known : knownClasses) {
            if (name.equals(known) || name.startsWith(known + "$")) return true;
        }
        for (String prefix : List.of(
                "java.", "javax.", "jdk.", "sun.", "com.sun.",
                "com.zaxxer.hikari.pool.", "org.apache.ibatis.executor.",
                "org.apache.ibatis.session.", "org.apache.ibatis.plugin.",
                "org.apache.coyote.", "org.apache.tomcat.", "cn.dev33.satoken.",
                // T-StackBizFrame S3：JDBC 驱动族与 MyBatis 日志/绑定/反射/类型族。它们在每条 SQL 的
                // 栈顶恒在场，旧名单不收时弹层里「可见行」反而是驱动帧——项目帧缺席时观感上更像
                // 「看不到项目类」。这些包是纯依赖，误折叠风险低。
                "org.apache.ibatis.logging.", "org.apache.ibatis.binding.",
                "org.apache.ibatis.reflection.", "org.apache.ibatis.type.",
                "org.apache.ibatis.datasource.", "org.apache.ibatis.transaction.",
                "com.mysql.", "org.mariadb.", "org.postgresql.", "org.h2.", "org.hsqldb.",
                "org.sqlite.", "com.microsoft.sqlserver.", "oracle.jdbc.", "dm.jdbc.",
                "com.gbasedbt.", "com.kingbase8.", "com.alibaba.druid.", "com.mchange.",
                "com.huawei.", "org.opengauss.",
                // 信创 PG 系新族：海量 Vastbase / 瀚高 HighGo（金仓 com.kingbase8. 已在上方）
                "cn.com.vastbase.", "com.highgo.",
                // OceanBase（独立实现非 MySQL 分叉）
                "com.oceanbase.",
                "org.apache.commons.dbcp2.", "com.ibm.db2.", "com.teradata.", "org.hibernate.",
                "com.baomidou.", "org.springframework.transaction.", "org.springframework.jdbc.")) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    @Nullable
    private static Integer parseIntegerOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** agent 自身帧判定（采集埋点链，弹层降权用）。 */
    public static boolean isAgentFrame(@NotNull StackFrameEntry frame) {
        return frame.fqcn().startsWith(AGENT_PACKAGE_PREFIX);
    }

    /** 单段解析（供内联摘要取景复用；解析失败返回 null，永不抛）。 */
    @Nullable
    public static StackFrameEntry parseSegment(@NotNull String segment) {
        return parseFrame(segment.trim());
    }

    /**
     * T-StackBizFrame S3：meta 行内联摘要的取景（只取景，不截断——截断仍由调用方按
     * {@code STACK_DIGEST_MAX_CHARS} 处理，宽度预算与文案契约不变）。
     *
     * <p>原实现固定取摘要串前若干字符，而摘要头恒为 agent/驱动/框架帧——长栈下那一行永远不含项目帧
     * （用户报障形态：位置列有项目类，可见行的栈摘要里全是驱动帧）。现规则：头部全为已知内部帧时
     * 跳到首个非内部帧；首个非内部帧本来就在开头（或整条栈都是内部帧）时原样返回，既有契约不变。</p>
     */
    @NotNull
    public static String projectExcerpt(@NotNull String digest) {
        if (digest.isBlank()) {
            return digest;
        }
        String[] segments = digest.split(java.util.regex.Pattern.quote(FRAME_SEPARATOR));
        for (int i = 0; i < segments.length; i++) {
            StackFrameEntry entry = parseSegment(segments[i]);
            if (entry == null) {
                continue; // Kotlin `?: continue` 语义
            }
            if (!isKnownInternal(entry)) {
                if (i == 0) {
                    return digest;
                }
                // Kotlin segments.drop(i).joinToString(FRAME_SEPARATOR)
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < segments.length; j++) {
                    if (j > i) {
                        sb.append(FRAME_SEPARATOR);
                    }
                    sb.append(segments[j]);
                }
                return sb.toString();
            }
        }
        return digest;
    }

    /**
     * 单帧解析：`pkg.Class#method:line`。`#` 定位类名/方法名边界（首个 `#`——方法名不含 `#`）；
     * 行号取方法名内**最后一个** `:` 之后全数字段（lambda 名 `lambda$run$0` 等含 `:` 的情况不存在，
     * 但代理/内嵌类名 `$` 段已在 `#` 前被隔离）。无行号段或非数字 → {@link StackFrameEntry#line} = 0。
     */
    @Nullable
    private static StackFrameEntry parseFrame(@NotNull String segment) {
        int hash = segment.indexOf('#');
        if (hash <= 0 || hash == segment.length() - 1) return null;
        String fqcn = segment.substring(0, hash);
        if (fqcn.isBlank()) return null;
        String rest = segment.substring(hash + 1);
        int colon = rest.lastIndexOf(':');
        if (colon > 0) {
            String linePart = rest.substring(colon + 1);
            if (!linePart.isEmpty() && isAllDigits(linePart)) {
                Integer line = parseIntegerOrNull(linePart);
                if (line != null) return new StackFrameEntry(fqcn, rest.substring(0, colon), line);
            }
        }
        return new StackFrameEntry(fqcn, rest, 0);
    }

    /** Kotlin {@code CharSequence.all { it.isDigit() }} 等价：全部字符为（Unicode）数字。 */
    private static boolean isAllDigits(@NotNull String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) return false;
        }
        return true;
    }
}
