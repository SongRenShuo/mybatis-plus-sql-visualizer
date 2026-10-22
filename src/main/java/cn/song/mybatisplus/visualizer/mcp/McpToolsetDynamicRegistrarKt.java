// T-SyncK2JMaster496 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpToolsetDynamicRegistrar.kt 顶层函数段移植
package cn.song.mybatisplus.visualizer.mcp;

/**
 * master 文件门面类 {@code McpToolsetDynamicRegistrarKt} 顶层函数的 Java 等价承载
 * （同 {@link McpToolsetSupportKt} 先例）。
 *
 * <p>注册入口与设置页共用的只读能力探测；类可达不代表工具已注册、服务器已启用或客户端可调用。
 * 顶层函数避免设置页为探测而初始化注册器及 TOOL_CLASSES；{@code initialize=false} 不启动平台服务。
 * 反射沿用注册器原有 McpToolset 入口，签名登记见 PlatformSignatureChecker.checkMcpToolsetCapability。</p>
 */
public final class McpToolsetDynamicRegistrarKt {

    private McpToolsetDynamicRegistrarKt() {
    }

    /** Kotlin 默认参数重载：loader 缺省取 McpToolGate 的 classloader。 */
    public static Class<?> findMcpToolsetClass() {
        return findMcpToolsetClass(McpToolGate.class.getClassLoader());
    }

    /** 探测 {@code com.intellij.mcpserver.McpToolset}（initialize=false，仅接口形态才算命中）。 */
    public static Class<?> findMcpToolsetClass(ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName("com.intellij.mcpserver.McpToolset", false, loader);
            return clazz.isInterface() ? clazz : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
