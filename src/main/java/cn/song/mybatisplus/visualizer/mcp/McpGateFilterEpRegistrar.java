// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterRegistrar.kt（object McpGateFilterEpRegistrar 声明）；T-SyncK2JMaster180 对齐 master cd1b94562 终态
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.registry.Registry;

import java.lang.reflect.Method;

/**
 * T-McpGateDynamicFilter（O-68）的探测式注册器（master 同文件 {@code object McpGateFilterEpRegistrar}
 * 的 Java 承载，{@code internal} 按本仓惯例升 public）：{@code Class.forName} 探测平台
 * {@code McpToolFilterProvider} 接口（253/252/251 及更早无此类 → 静默跳过），命中再按
 * {@link McpGateFilterEpBridge#detectFilterForm} 契约形态分流（A/B 才注册，UNKNOWN 留日志不注册），
 * 才触达 {@link McpGateFilterEpBridge} 的类型化注册；{@link #sync()} 幂等。
 *
 * <p>T-McpGlobalBreakHardening（20261118）：总开关联动 + Registry 逃生开关
 * {@code sqlvis.mcp.gateFilterEp}（默认 true）+ {@link GateRegistrationState} 状态可读
 * （设置页只读展示「门控生效 / 未识别 / 已关闭 / 无此能力 / 已熔断」）。
 * 背景与加载纪律见 {@link McpGateFilterRegistrar} KDoc。</p>
 */
public final class McpGateFilterEpRegistrar {

    public static final McpGateFilterEpRegistrar INSTANCE = new McpGateFilterEpRegistrar();

    private McpGateFilterEpRegistrar() {
    }

    private static final Logger LOG = Logger.getInstance(McpGateFilterEpRegistrar.class);

    private static final String FILTER_IFACE_FQN = "com.intellij.mcpserver.McpToolFilterProvider";

    /** 逃生开关：false = 不注册过滤 EP（门控不生效，MCP 服务不受影响）。 */
    public static final String ESCAPE_REGISTRY_KEY = "sqlvis.mcp.gateFilterEp";

    private static volatile GateRegistrationState state = GateRegistrationState.PENDING;

    public static GateRegistrationState getState() {
        return state;
    }

    /** 契约探测 + 注册（幂等）。总开关关闭/逃生开关关闭/契约未知时只摘除，不注册。 */
    public static void sync() {
        if (!McpToolGate.isMasterEnabled()) {
            McpGateFilterEpBridge.unregister();
            state = GateRegistrationState.DISABLED;
            return;
        }
        if (!escapeHatchEnabled()) {
            McpGateFilterEpBridge.unregister();
            state = GateRegistrationState.DISABLED;
            LOG.info(
                "O-68: Registry " + ESCAPE_REGISTRY_KEY + "=false，跳过 mcpToolFilterProvider 注册"
                    + "（外部清单门控不生效，MCP 服务不受影响）");
            return;
        }
        Class<?> iface;
        try {
            iface = Class.forName(FILTER_IFACE_FQN);
        } catch (Throwable ignored) {
            McpGateFilterEpBridge.unregister();
            state = GateRegistrationState.UNSUPPORTED;
            LOG.info("O-68: 当前 IDE 无 McpToolFilterProvider（253 及更早），不注册（MCP 服务不受影响）");
            return;
        }
        McpGateFilterEpBridge.FilterForm form;
        try {
            form = McpGateFilterEpBridge.detectFilterForm(iface);
        } catch (Throwable t) {
            form = McpGateFilterEpBridge.FilterForm.UNKNOWN; // runCatching{...}.getOrDefault(UNKNOWN)
        }
        if (form == McpGateFilterEpBridge.FilterForm.UNKNOWN) {
            McpGateFilterEpBridge.unregister();
            state = GateRegistrationState.UNRECOGNIZED;
            LOG.info(
                "O-68: 未识别的 mcpToolFilterProvider 契约，跳过注册（外部清单门控不生效，MCP 服务不受影响）："
                    + describeIfaceMethods(iface)
            );
            return;
        }
        try {
            McpGateFilterEpBridge.register();
            state = McpGateFilterEpBridge.isBreakerTripped()
                ? GateRegistrationState.TRIPPED
                : GateRegistrationState.REGISTERED;
            LOG.info(
                "O-68: mcpToolFilterProvider 注册完成（form=" + McpGateFilterEpBridge.describe(form)
                    + ", build=" + ideBuild() + "）");
        } catch (Throwable t) {
            state = GateRegistrationState.UNRECOGNIZED;
            LOG.warn("O-68: mcpToolFilterProvider EP 注册失败（外部会话门控退化，其余功能不受影响）", t);
        }
    }

    /** Registry 逃生开关读取（服务未注册/键缺失兜底 true）。 */
    public static boolean escapeHatchEnabled() {
        try {
            return Registry.get(ESCAPE_REGISTRY_KEY).asBoolean();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String ideBuild() {
        try {
            return ApplicationInfo.getInstance().getBuild().asString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    /** Kotlin {@code iface.methods.joinToString { signatureOf(it) }} 的等价拼接。 */
    private static String describeIfaceMethods(Class<?> iface) {
        StringBuilder sb = new StringBuilder();
        Method[] methods = iface.getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(McpGateFilterEpBridge.signatureOf(methods[i]));
        }
        return sb.toString();
    }
}
