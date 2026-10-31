// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterRegistrar.kt（object McpGateFilterEpRegistrar 声明）
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.openapi.diagnostic.Logger;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T-McpGateDynamicFilter（O-68）的探测式注册器（master 同文件 {@code object McpGateFilterEpRegistrar}
 * 的 Java 承载，{@code internal} 按本仓惯例升 public）：{@code Class.forName} 探测平台
 * {@code McpToolFilterProvider} 接口（253/252/251 及更早无此类 → 静默跳过），命中再按
 * {@link McpGateFilterEpBridge#detectFilterForm} 契约形态分流（A/B 才注册，UNKNOWN 留日志不注册），
 * 才触达 {@link McpGateFilterEpBridge} 的类型化注册；原子闸保证全应用只注册一次。
 * 背景与加载纪律见 {@link McpGateFilterRegistrar} KDoc。
 */
public final class McpGateFilterEpRegistrar {

    public static final McpGateFilterEpRegistrar INSTANCE = new McpGateFilterEpRegistrar();

    private McpGateFilterEpRegistrar() {
    }

    private static final Logger LOG = Logger.getInstance(McpGateFilterEpRegistrar.class);

    private static final AtomicBoolean registered = new AtomicBoolean(false);

    private static final String FILTER_IFACE_FQN = "com.intellij.mcpserver.McpToolFilterProvider";

    public static void registerOnce() {
        if (registered.get()) {
            return;
        }
        Class<?> iface;
        try {
            iface = Class.forName(FILTER_IFACE_FQN);
        } catch (Throwable ignored) {
            return; // runCatching{...}.getOrNull() ?: return（探测失败静默跳过）
        }
        McpGateFilterEpBridge.FilterForm form;
        try {
            form = McpGateFilterEpBridge.detectFilterForm(iface);
        } catch (Throwable t) {
            form = McpGateFilterEpBridge.FilterForm.UNKNOWN; // runCatching{...}.getOrDefault(UNKNOWN)
        }
        if (form == McpGateFilterEpBridge.FilterForm.UNKNOWN) {
            // 已决策（平台版本在进程生命周期内不会变），置位避免逐项目重复刷日志
            registered.set(true);
            LOG.info(
                "O-68: 未识别的 mcpToolFilterProvider 契约，跳过注册（外部清单门控不生效，MCP 服务不受影响）："
                    + describeIfaceMethods(iface)
            );
            return;
        }
        try {
            McpGateFilterEpBridge.register();
            registered.set(true);
            LOG.info("O-68: sqlv gate filter registered onto mcpToolFilterProvider EP（form="
                + McpGateFilterEpBridge.describe(form) + "）");
        } catch (Throwable t) {
            LOG.warn("O-68: mcpToolFilterProvider EP 注册失败（外部会话门控退化，其余功能不受影响）", t);
        }
    }

    /** Kotlin {@code iface.methods.joinToString { "${it.name}/${it.parameterCount}->${it.returnType.simpleName}" }} 的等价拼接。 */
    private static String describeIfaceMethods(Class<?> iface) {
        StringBuilder sb = new StringBuilder();
        Method[] methods = iface.getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(methods[i].getName())
                .append('/')
                .append(methods[i].getParameterCount())
                .append("->")
                .append(methods[i].getReturnType().getSimpleName());
        }
        return sb.toString();
    }
}
