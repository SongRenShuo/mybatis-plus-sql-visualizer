package cn.song.mybatisplus.sqlvis.agent.util;

/**
 * 代理帧反解（T-HdAbsorb D217，吸收自 Hibernate Debugger 的 StatementTraceProxyLink
 * 「interfaceName#methodName#signature」思路）：
 * 调用栈快照里的动态代理帧（JDK {@code $ProxyN} / Spring CGLIB 增强类）对用户是不可读的——
 * 摘要弹层里看到的是 {@code com.sun.proxy.$Proxy7#selectById}，而不是真正承载调用身份的
 * {@code com.example.UserMapper#selectById}。本类只做<b>展示名反解</b>：
 * <ol>
 *   <li><b>CGLIB 增强类</b>（{@code Base$$SpringCGLIB$$n} / {@code $$EnhancerBySpringCGLIB$$} /
 *       {@code $$FastClassBySpringCGLIB$$} 族）：字符串级剥后缀得基类 FQCN——零反射；</li>
 *   <li><b>JDK 动态代理</b>（{@code com.sun.proxy.$ProxyN} 与默认包 {@code $ProxyN}）：
 *       {@code Class.forName(cn, false, loader).getInterfaces()} 取首个「非框架」接口
 *       （框架门复用 {@link StackLocator#isFrameworkFrame}，与定位侧同源）——
 *       反解失败（类不可加载/无合适接口）原样保留，不猜。</li>
 * </ol>
 *
 * <p><b>刻意不做的事</b>：不改 {@link StackLocator} 的主位置选择语义——代理帧是否参与
 * 「来源定位」是 L1/安全网的职责（{@code isFrameworkFrame} 含 {@code $Proxy} 跳过，改动会
 * 波及全部定位判定与既有守卫）；本类只服务 {@link StackDigest} 快照的展示面（IDE 侧弹层
 * 解析 {@code Class#method:line} 的 Class 段拿到真实 FQCN 后，点击可直达接口源码）。
 */
public final class ProxyFrameDecoder {

    /** Spring CGLIB 增强类标记（含旧版 cglib 与 spring-core 6 的简化形态）。 */
    private static final String[] CGLIB_MARKERS = {
            "$$SpringCGLIB$$", "$$EnhancerBySpringCGLIB$$", "$$FastClassBySpringCGLIB$$",
    };

    /** JDK 代理类名形态：com.sun.proxy.$ProxyN 或默认包 $ProxyN（含 jdk.proxy2.$ProxyN 变体）。 */
    public static boolean isJdkProxyName(String className) {
        if (className == null) {
            return false;
        }
        if (className.contains("$$")) {
            return false;
        }
        final int dollar = className.lastIndexOf('$');
        if (dollar < 0 || !className.regionMatches(dollar + 1, "Proxy", 0, 5)) {
            return false;
        }
        for (int i = dollar + 6; i < className.length(); i++) {
            if (!Character.isDigit(className.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isCglibProxyName(String className) {
        if (className == null) {
            return false;
        }
        for (String m : CGLIB_MARKERS) {
            if (className.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /** CGLIB 增强类 → 基类 FQCN（非 CGLIB 名原样返回）。 */
    public static String cglibBaseName(String className) {
        if (!isCglibProxyName(className)) {
            return className;
        }
        int cut = -1;
        for (String m : CGLIB_MARKERS) {
            final int at = className.indexOf(m);
            if (at >= 0 && (cut < 0 || at < cut)) {
                cut = at;
            }
        }
        return cut > 0 ? className.substring(0, cut) : className;
    }

    /**
     * JDK 代理 → 业务接口 FQCN；解不出返回 null（调用方原样保留）。框架门复用
     * {@link StackLocator#isFrameworkFrame}（{@code initialize=false} 防静态初始化副作用）。
     */
    public static String decodeJdkProxyIdentity(String className, ClassLoader loader) {
        if (!isJdkProxyName(className)) {
            return null;
        }
        try {
            final Class<?> proxy = Class.forName(className, false, loader);
            for (Class<?> it : proxy.getInterfaces()) {
                final String name = it.getName();
                if (!StackLocator.isFrameworkFrame(name)) {
                    return name;
                }
            }
        } catch (Throwable t) {
            // 类不可加载 / loader 看不见 / 反序列化环境受限：原样保留，不猜
        }
        return null;
    }

    /**
     * 展示名反解（StackDigest 快照帧用）：代理帧 → 承载调用身份的真实 FQCN；其余原样返回。
     * 刻意不拼「（代理）」等装饰——Class 段保持可解析 FQCN，IDE 侧点击直达源码。
     */
    public static String decodeForDisplay(String className, ClassLoader loader) {
        if (className == null) {
            return null;
        }
        if (isCglibProxyName(className)) {
            return cglibBaseName(className);
        }
        if (isJdkProxyName(className)) {
            final String it = decodeJdkProxyIdentity(className, loader);
            return it != null ? it : className;
        }
        return className;
    }

    private ProxyFrameDecoder() {
    }
}
