// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterEpBridge.kt
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;

/**
 * T-McpGateDynamicFilter（O-68）注册桥：把 {@link McpToolGate} 的门控决策接进平台
 * {@code com.intellij.mcpServer.mcpToolFilterProvider} EP——<b>外部会话工具清单路径</b>（该链不经过
 * {@code McpToolset.isEnabled()}，字节码取证见 McpToolGate 头注）。
 *
 * <p><b>T-MarketCompatProxy（20260923）：全反射 + 动态代理——字节码零 {@code com.intellij.mcpserver.*}
 * 类型引用</b>。原形态（实现类 implements 平台接口）在 253/261/252/251 上被 Marketplace
 * verifier 按字节码引用判「未解析类」（每线 4 条；片段级 idea-version 与反射防火墙两轮对照
 * 实验均证伪，见 dev-support/verifier-marketplace-baseline.txt 头注）。本形态的注册链全部
 * 经 {@code Class.forName} 字符串 + 擦除泛型（{@code ExtensionPointName.create&lt;Any&gt;} →
 * {@code registerExtension(Any)}），jar 内不再出现平台过滤 API 的类型引用，旧版读数即消。</p>
 *
 * <p><b>T-Mcp261FilterContract（20261029）：平台过滤器契约有三形态，本桥同时支持 A 与 B</b>
 * （形态判定见 {@link FilterForm} 与 {@code McpGateFilterEpRegistrar.detectForm}；javap 权威取证）：
 * <ul>
 *   <li><b>形态 A（262/263）</b> {@code applyFilters(context, clientInfo, sessionOptions, invocationMode)}：
 *       反射调 {@code context.updateState(enabled=false, routerOnly=null, filter)}；</li>
 *   <li><b>形态 B（261.24374+ / 261.25134）</b> {@code getFilters(clientInfo, sessionOptions): List&lt;McpToolFilter&gt;}
 *       ＋ {@code McpToolFilter.modify(context)} → {@code McpToolFilterModification.apply(context)}：平台自建
 *       {@code context(disallowed=∅, allowed=全量)}，逐过滤器 {@code context = modify(context).apply(context)}，
 *       最终取 {@code context.allowedTools}——故 B 路径必须把隐藏工具从 allowed 摘掉并加进 disallowed
 *       （与平台自带 {@code DisallowMcpTools.apply} 逐语义一致）；</li>
 *   <li><b>形态 C（261.22158，{@code getFilters} 返回 {@code StateFlow}）不做</b>：探测归 UNKNOWN → 不注册。</li>
 *   <li>{@code getUpdates(…)}：{@code emptyFlow}——本插件门控是设置页静态状态，平台每次列清单重走过滤链，
 *       空流即「永不主动推送，下一次列表/调用即生效」。</li>
 * </ul></p>
 *
 * <p><b>安全不变量（T-Mcp261FilterContract 券01，结构性）</b>：任何分发分支都<b>不返回 null、
 * 不向平台抛异常</b>。261 的过滤链不在 try/catch 内（{@code getMcpToolsFiltered} 的 Exception table
 * 只覆盖 {@code getTools()}），一次 null 或一次抛出就是「会话创建失败 → 内置 MCP Server 对所有
 * 客户端 HTTP 500」（2026-10-29 用户报障，261.25134 实证）。故：
 * <ul>
 *   <li>未知方法按返回类型兜底（{@link #defaultValueFor}：集合/Map/Flow/原始类型给空值，void 与对象给 null）；</li>
 *   <li>各实现体 runCatching，失败降级为「原样返回/空清单」。</li>
 * </ul></p>
 *
 * <p><b>加载纪律</b>：本对象只在 {@code McpGateFilterEpRegistrar.registerOnce} 探测通过（形态 A 或 B）后
 * 被触达；UNKNOWN 与更早平台（253/252/251、261.17801 及更早无此类）不注册，本类即便随 jar
 * 装载也<b>无任何平台过滤类型的链接要求</b>（方法体零平台类型，平台 API 全部经字符串在运行时解析）。</p>
 *
 * <p><b>签名权威</b>：形态 A 的 {@code updateState(Boolean?, Boolean?, Function1)} 与 {@code getDescriptor()}/{@code getName()}
 * 解析自 262.10968.63 mcpserver.jar；形态 B 的 {@code getFilters}/{@code modify}/{@code apply}/
 * {@code copy(Set, Set)} 解析自 261.25134.203（Marketplace mcpserver 插件）与 261.24374.39
 * （两者调用点字节码逐字相同）。空源性以「运行时实参 + 平台自带实现同源形态」为权威
 * （桩头 20260923 真机 NPE 教训），故 handler 全路径判空。</p>
 *
 * <p>移植注记：master 的 {@code internal object} 按本仓惯例落为 final class + {@link #INSTANCE}
 * + 全静态成员，{@code internal} 升 public；{@code emptyFlow} 经 javap 权威落为
 * {@code kotlinx.coroutines.flow.FlowKt.emptyFlow()}（1.5.0/1.8.0 双版本门面一致）；
 * {@code register} 的 {@code Class.forName} 受检异常按 Java 规则显式声明（master 无受检概念，
 * 调用方 {@code McpGateFilterEpRegistrar} 以 catch Throwable 承接）。</p>
 *
 * <p>移植注记（T-SyncK2JMaster175）：master 本轮新增的同文件顶层声明（{@code enum FilterForm}、
 * 顶层函数 {@code detectFilterForm}、扩展函数 {@code FilterForm.describe}）按本仓单文件承载惯例
 * 落为内嵌公共枚举 + 静态方法（{@code describe} 首参收接收者），不另建 Kt 门面；
 * {@code filters261}/{@code buildFilter261}/{@code buildModification261}/{@code filterContext261}/
 * {@code defaultValueFor} 为 object 成员，落静态方法（{@code internal} 升 public 供测试注入假接口）。
 * Kotlin 集合算式（{@code +}/{@code -}/{@code filter}/{@code toSet}）以 LinkedHashSet/ArrayList
 * 保序重建，语义与 master 逐条对齐。</p>
 */
public final class McpGateFilterEpBridge {

    public static final McpGateFilterEpBridge INSTANCE = new McpGateFilterEpBridge();

    private McpGateFilterEpBridge() {
    }

    private static final Logger LOG = Logger.getInstance(McpGateFilterEpBridge.class);

    private static final String EP_NAME = "com.intellij.mcpServer.mcpToolFilterProvider";
    private static final String FILTER_IFACE_FQN = "com.intellij.mcpserver.McpToolFilterProvider";
    private static final String FUNCTION1_FQN = "kotlin.jvm.functions.Function1";

    /** 261 形态 B 的两个嵌套接口——只以字符串出现，<b>不得</b>变成类型引用（verifier 红线）。 */
    private static final String TOOL_FILTER_FQN = "com.intellij.mcpserver.McpToolFilterProvider$McpToolFilter";
    private static final String TOOL_FILTER_MODIFICATION_FQN =
        "com.intellij.mcpserver.McpToolFilterProvider$McpToolFilterModification";

    public static void register() throws ClassNotFoundException {
        Class<?> filterIface = Class.forName(FILTER_IFACE_FQN);
        ClassLoader loader = McpGateFilterEpBridge.class.getClassLoader();
        Object provider = Proxy.newProxyInstance(loader, new Class<?>[] {filterIface}, providerHandler());
        ExtensionPointName<Object> ep = ExtensionPointName.create(EP_NAME);
        ep.getPoint(null).registerExtension(provider);
        LOG.info("O-68: sqlv gate filter registered onto mcpToolFilterProvider EP (proxy form)");
    }

    /**
     * 平台对 provider 的全部调用分发（含 Object 基方法：代理不会绕过 handler）。
     * 形态 A/B 的入口共用一个 handler：平台只声明自己那一形态的方法，另一分支是死路。
     */
    private static InvocationHandler providerHandler() {
        return (proxy, method, args) -> {
            switch (method.getName()) {
                case "applyFilters": {
                    applyFilters(args != null && args.length > 0 ? args[0] : null);
                    return null;
                }
                // 形态 B（261）：返回过滤器清单。解析失败退化为空清单——平台只是拿不到门控，
                // 绝不能返回 null（addAll(collection, null) 即 2026-10-29 事故）。
                case "getFilters":
                    return filters261();
                case "getUpdates":
                    // emptyFlow：永不主动推送，下一次列表/调用即生效
                    return FlowKt.emptyFlow();
                case "toString":
                    return "SqlVisualizerToolGateFilterProvider(proxy)";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && args.length > 0 && args[0] == proxy;
                default:
                    return defaultValueFor(method.getReturnType());
            }
        };
    }

    /** 原实现 applyFilters 的反射等价体（形态 A）：改写 context 内各工具的 enabled 状态。 */
    private static void applyFilters(Object context) {
        if (context == null) {
            return;
        }
        try {
            InvocationHandler predicateHandler = (predicateProxy, predicateMethod, predicateArgs) -> {
                if (predicateMethod.getName().equals("invoke")) {
                    // 决策核收非空名（与原实现传 descriptor.name 的口径一致）；名字读不出
                    // （理论不可达）时按平台默认不隐藏处理——宁漏隐藏不误隐藏。
                    String toolName = readToolName(
                        predicateArgs != null && predicateArgs.length > 0 ? predicateArgs[0] : null
                    );
                    return toolName != null ? McpToolGate.shouldHideFromExternalToolsList(toolName) : Boolean.FALSE;
                }
                return defaultValueFor(predicateMethod.getReturnType());
            };
            Object predicate = Proxy.newProxyInstance(
                McpGateFilterEpBridge.class.getClassLoader(),
                new Class<?>[] {Class.forName(FUNCTION1_FQN)},
                predicateHandler
            );
            // Kotlin Boolean::class.javaObjectType = 装箱 Boolean（updateState 形参为可空装箱值）
            Method updateState = context.getClass().getMethod(
                "updateState",
                Boolean.class,
                Boolean.class,
                Class.forName(FUNCTION1_FQN)
            );
            updateState.invoke(context, Boolean.FALSE, null, predicate);
        } catch (Throwable t) {
            LOG.warn("O-68: applyFilters reflection dispatch failed（门控对外部清单退化，其余功能不受影响）", t);
        }
    }

    /**
     * 形态 B（261）过滤器清单：一个过滤器代理，语义与平台自带 DisallowMcpTools 一致。
     * 任何失败都返回空清单（非 null）——空清单只是门控不生效。
     */
    private static List<Object> filters261() {
        try {
            ClassLoader loader = McpGateFilterEpBridge.class.getClassLoader();
            Class<?> filterIface = Class.forName(TOOL_FILTER_FQN, true, loader);
            Class<?> modificationIface = Class.forName(TOOL_FILTER_MODIFICATION_FQN, true, loader);
            return List.of(buildFilter261(filterIface, modificationIface));
        } catch (Throwable t) {
            LOG.info("O-68: 261 过滤器接口不可解析，外部清单门控退化（MCP 服务不受影响）", t);
            return List.of();
        }
    }

    /**
     * 构造 {@code McpToolFilter} 代理（形态 B 第一段）。接口类由调用方给出，测试可注入本地假接口。
     * {@code modify(context)} 必须返回非 null——平台紧接 {@code .apply(context)}，无判空。
     */
    public static Object buildFilter261(Class<?> filterIface, Class<?> modificationIface) {
        return Proxy.newProxyInstance(
            McpGateFilterEpBridge.class.getClassLoader(),
            new Class<?>[] {filterIface},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "modify":
                        return buildModification261(modificationIface);
                    case "toString":
                        return "SqlVisualizerToolGateFilter(proxy)";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return args != null && args.length > 0 && args[0] == proxy;
                    default:
                        return defaultValueFor(method.getReturnType());
                }
            }
        );
    }

    /** 构造 {@code McpToolFilterModification} 代理（形态 B 第二段）：{@code apply(context)} 摘除隐藏工具。 */
    public static Object buildModification261(Class<?> modificationIface) {
        return Proxy.newProxyInstance(
            McpGateFilterEpBridge.class.getClassLoader(),
            new Class<?>[] {modificationIface},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "apply":
                        return filterContext261(args != null && args.length > 0 ? args[0] : null);
                    case "toString":
                        return "SqlVisualizerToolGateModification(proxy)";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return args != null && args.length > 0 && args[0] == proxy;
                    default:
                        return defaultValueFor(method.getReturnType());
                }
            }
        );
    }

    /**
     * 形态 B 的上下文改写：{@code context.copy(disallowed + hidden, allowed - hidden)}。
     *
     * <p>平台只消费返回值里的 {@code allowedTools}（{@code getMcpToolsFiltered} 末尾
     * {@code context.allowedTools.toList()}），disallowed 的维护是为了与平台自带过滤器保持一致语义。
     * 拿不到集合或 {@code copy}（契约漂移）时<b>原样返回 context</b>——等价于不隐藏，绝不抛出。</p>
     */
    public static Object filterContext261(Object context) {
        if (context == null) {
            return null;
        }
        try {
            Set<Object> disallowed = readToolSet(context, "getDisallowedTools");
            Set<Object> allowed = readToolSet(context, "getAllowedTools");
            if (disallowed == null || allowed == null) {
                return context;
            }
            // Kotlin `allowed.filter { ... }`：决策核收非空名，名字读不出按不隐藏处理
            List<Object> hidden = new ArrayList<>();
            for (Object tool : allowed) {
                String toolName = readToolName(tool);
                if (toolName != null && McpToolGate.shouldHideFromExternalToolsList(toolName)) {
                    hidden.add(tool);
                }
            }
            if (hidden.isEmpty()) {
                return context;
            }
            // `disallowed + hidden` / `allowed - hidden.toSet()`：LinkedHashSet 保序合集/差集
            Set<Object> newDisallowed = new LinkedHashSet<>(disallowed);
            newDisallowed.addAll(hidden);
            Set<Object> newAllowed = new LinkedHashSet<>(allowed);
            newAllowed.removeAll(hidden);
            Method copy = context.getClass().getMethod("copy", Set.class, Set.class);
            return copy.invoke(context, newDisallowed, newAllowed);
        } catch (Throwable t) {
            LOG.warn("O-68: 261 filter context 反射失败（门控对外部清单退化，其余功能不受影响）", t);
            return context;
        }
    }

    /** 平台 {@code McpToolFilterContext.getDisallowedTools()/getAllowedTools()} 的反射读取。 */
    private static Set<Object> readToolSet(Object context, String getter) {
        try {
            Object result = context.getClass().getMethod(getter).invoke(context);
            if (result instanceof Set) {
                return new LinkedHashSet<>((Set<?>) result); // `as? Set<*>`?.toSet() 拷贝语义
            }
            return null;
        } catch (Throwable ignored) {
            return null; // runCatching{...}.getOrNull() 语义
        }
    }

    /** 平台 {@code McpTool.descriptor.name} 的反射读取；任何一步失败返回 null（谓词按不隐藏处理）。 */
    private static String readToolName(Object tool) {
        if (tool == null) {
            return null;
        }
        Object descriptor;
        try {
            descriptor = tool.getClass().getMethod("getDescriptor").invoke(tool);
        } catch (Throwable ignored) {
            return null; // runCatching{...}.getOrNull() 语义
        }
        if (descriptor == null) {
            return null;
        }
        try {
            Object name = descriptor.getClass().getMethod("getName").invoke(descriptor);
            return name instanceof String ? (String) name : null; // Kotlin `as? String` 安全转译
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 未知方法的返回值兜底：<b>集合/Map/Flow/原始类型一律给空值，绝不返回 null</b>。
     *
     * <p>为什么必须这么做：平台对过滤器方法的返回值直接消费（261 的 {@code getFilters} 结果直接进
     * {@code CollectionsKt.addAll}，{@code modify} 的结果直接 {@code .apply}），一次 null 就会让整条会话创建链
     * 抛 NPE。而 {@code Proxy} 对原始返回类型返回 null 同样直接抛 NPE。void 与对象类型仍可 null
     * （{@code getTransport} 之类「null＝不表态」的平台语义不在本桥内）。</p>
     */
    public static Object defaultValueFor(Class<?> type) {
        if (type == Void.TYPE || type == Void.class) {
            return null;
        }
        if (Set.class.isAssignableFrom(type)) {
            return Set.of();
        }
        if (Collection.class.isAssignableFrom(type)) {
            return List.of();
        }
        if (Iterable.class.isAssignableFrom(type)) {
            return List.of();
        }
        if (Map.class.isAssignableFrom(type)) {
            return Map.of();
        }
        if (Flow.class.isAssignableFrom(type)) {
            return FlowKt.emptyFlow();
        }
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Integer.TYPE) {
            return 0;
        }
        if (type == Long.TYPE) {
            return 0L;
        }
        if (type == Double.TYPE) {
            return 0.0;
        }
        if (type == Float.TYPE) {
            return 0f;
        }
        if (type == Short.TYPE) {
            return (short) 0;
        }
        if (type == Byte.TYPE) {
            return (byte) 0;
        }
        if (type == Character.TYPE) {
            return '\u0000';
        }
        return null;
    }

    /** 平台过滤器 EP 的三种契约形态（探测结果；UNKNOWN 一律不注册）。master 同文件顶层枚举的内嵌承载。 */
    public enum FilterForm {
        A_APPLY,
        B_GET_LIST,
        UNKNOWN
    }

    /**
     * 契约感知探测（T-Mcp261FilterContract 券02）：<b>只按方法签名判形态，不看类是否存在</b>。
     *
     * <p>261 上类存在但方法名不同（{@code getFilters} 而非 {@code applyFilters}），旧探测「类在即注册」把
     * 一个按 262 签名写的代理挂上去 → {@code getFilters} 落兜底 → 平台 {@code addAll(collection, null)}
     * → 内置 MCP Server 对所有客户端 500。故此处：
     * <ul>
     *   <li>{@code applyFilters} 在场 → A（262/263）；</li>
     *   <li>{@code getFilters} 双参且返回 {@code List} → B（261.24374+ / 261.25134）；</li>
     *   <li>其余（含 261.22158 的 {@code StateFlow} 形态 C、未来新形态）→ UNKNOWN，不注册。</li>
     * </ul></p>
     *
     * <p>判据只看方法名 + 参数个数 + 返回类型可赋值性——这三项都写在 JVM 描述符里，不依赖 Kotlin
     * 元数据，也不产生任何类型引用（{@code List} 是 JDK 类型）。</p>
     */
    public static FilterForm detectFilterForm(Class<?> iface) {
        for (Method method : iface.getMethods()) {
            if (method.getName().equals("applyFilters")) {
                return FilterForm.A_APPLY;
            }
        }
        for (Method method : iface.getMethods()) {
            if (method.getName().equals("getFilters")
                && method.getParameterCount() == 2
                && List.class.isAssignableFrom(method.getReturnType())) {
                return FilterForm.B_GET_LIST;
            }
        }
        return FilterForm.UNKNOWN;
    }

    /** 形态的日志描述（注册日志里带形态，便于下一条版本线回溯）。master 顶层扩展函数的静态承载。 */
    public static String describe(FilterForm form) {
        switch (form) {
            case A_APPLY:
                return "A(applyFilters, 262/263)";
            case B_GET_LIST:
                return "B(getFilters, 261.24374+)";
            case UNKNOWN:
                return "UNKNOWN";
            default:
                throw new AssertionError("Unexpected FilterForm: " + form);
        }
    }
}
