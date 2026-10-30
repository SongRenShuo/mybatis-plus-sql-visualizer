package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * T-McpGateDynamicFilter（O-68）注册桥：把 [McpToolGate] 的门控决策接进平台
 * `com.intellij.mcpServer.mcpToolFilterProvider` EP——**外部会话工具清单路径**（该链不经过
 * `McpToolset.isEnabled()`，字节码取证见 McpToolGate 头注）。
 *
 * **T-MarketCompatProxy（20260923）：全反射 + 动态代理——字节码零 `com.intellij.mcpserver.*`
 * 类型引用**。原形态（实现类 implements 平台接口）在 253/261/252/251 上被 Marketplace
 * verifier 按字节码引用判「未解析类」（每线 4 条；片段级 idea-version 与反射防火墙两轮对照
 * 实验均证伪，见 dev-support/verifier-marketplace-baseline.txt 头注）。本形态的注册链全部
 * 经 `Class.forName` 字符串 + 擦除泛型（`ExtensionPointName.create<Any>` →
 * `registerExtension(Any)`），jar 内不再出现平台过滤 API 的类型引用，旧版读数即消。
 *
 * **T-Mcp261FilterContract（20261029）：平台过滤器契约有三形态，本桥同时支持 A 与 B**
 * （形态判定见 [FilterForm] 与 `McpGateFilterEpRegistrar.detectForm`；javap 权威取证）：
 *  - **形态 A（262/263）** `applyFilters(context, clientInfo, sessionOptions, invocationMode)`：
 *    反射调 `context.updateState(enabled=false, routerOnly=null, filter)`；
 *  - **形态 B（261.24374+ / 261.25134）** `getFilters(clientInfo, sessionOptions): List<McpToolFilter>`
 *    ＋ `McpToolFilter.modify(context)` → `McpToolFilterModification.apply(context)`：平台自建
 *    `context(disallowed=∅, allowed=全量)`，逐过滤器 `context = modify(context).apply(context)`，
 *    最终取 `context.allowedTools`——故 B 路径必须把隐藏工具从 allowed 摘掉并加进 disallowed
 *    （与平台自带 `DisallowMcpTools.apply` 逐语义一致）；
 *  - **形态 C（261.22158，`getFilters` 返回 `StateFlow`）不做**：探测归 UNKNOWN → 不注册。
 *  - `getUpdates(…)`：`emptyFlow`——本插件门控是设置页静态状态，平台每次列清单重走过滤链，
 *    空流即「永不主动推送，下一次列表/调用即生效」。
 *
 * **安全不变量（T-Mcp261FilterContract 券01，结构性）**：任何分发分支都**不返回 null、
 * 不向平台抛异常**。261 的过滤链不在 try/catch 内（`getMcpToolsFiltered` 的 Exception table
 * 只覆盖 `getTools()`），一次 null 或一次抛出就是「会话创建失败 → 内置 MCP Server 对所有
 * 客户端 HTTP 500」（2026-10-29 用户报障，261.25134 实证）。故：
 *  - 未知方法按返回类型兜底（[defaultValueFor]：集合/Map/Flow/原始类型给空值，void 与对象给 null）；
 *  - 各实现体 runCatching，失败降级为「原样返回/空清单」。
 *
 * **加载纪律**：本对象只在 [McpGateFilterEpRegistrar.registerOnce] 探测通过（形态 A 或 B）后
 * 被触达；UNKNOWN 与更早平台（253/252/251、261.17801 及更早无此类）不注册，本类即便随 jar
 * 装载也**无任何平台过滤类型的链接要求**（方法体零平台类型，平台 API 全部经字符串在运行时解析）。
 *
 * **签名权威**：形态 A 的 `updateState(Boolean?, Boolean?, Function1)` 与 `getDescriptor()`/
 * `getName()` 解析自 262.10968.63 mcpserver.jar；形态 B 的 `getFilters`/`modify`/`apply`/
 * `copy(Set, Set)` 解析自 261.25134.203（Marketplace mcpserver 插件）与 261.24374.39
 * （两者调用点字节码逐字相同）。空源性以「运行时实参 + 平台自带实现同源形态」为权威
 * （桩头 20260923 真机 NPE 教训），故 handler 全路径判空。
 */
internal object McpGateFilterEpBridge {

    private val LOG = Logger.getInstance(McpGateFilterEpBridge::class.java)

    private const val EP_NAME = "com.intellij.mcpServer.mcpToolFilterProvider"
    private const val FILTER_IFACE_FQN = "com.intellij.mcpserver.McpToolFilterProvider"
    private const val FUNCTION1_FQN = "kotlin.jvm.functions.Function1"

    /** 261 形态 B 的两个嵌套接口——只以字符串出现，**不得**变成类型引用（verifier 红线）。 */
    private const val TOOL_FILTER_FQN = "com.intellij.mcpserver.McpToolFilterProvider\$McpToolFilter"
    private const val TOOL_FILTER_MODIFICATION_FQN =
        "com.intellij.mcpserver.McpToolFilterProvider\$McpToolFilterModification"

    fun register() {
        val filterIface = Class.forName(FILTER_IFACE_FQN)
        val loader = McpGateFilterEpBridge::class.java.classLoader
        val provider = Proxy.newProxyInstance(loader, arrayOf(filterIface), providerHandler())
        val ep = ExtensionPointName.create<Any>(EP_NAME)
        ep.getPoint(null).registerExtension(provider)
        LOG.info("O-68: sqlv gate filter registered onto mcpToolFilterProvider EP (proxy form)")
    }

    /** 平台对 provider 的全部调用分发（含 Object 基方法：代理不会绕过 handler）。
     *  形态 A/B 的入口共用一个 handler：平台只声明自己那一形态的方法，另一分支是死路。 */
    private fun providerHandler() = InvocationHandler { proxy, method, args ->
        when (method.name) {
            "applyFilters" -> {
                applyFilters(args?.firstOrNull())
                null
            }
            // 形态 B（261）：返回过滤器清单。解析失败退化为空清单——平台只是拿不到门控，
            // 绝不能返回 null（addAll(collection, null) 即 2026-10-29 事故）。
            "getFilters" -> filters261()
            "getUpdates" -> emptyFlow<Any>()
            "toString" -> "SqlVisualizerToolGateFilterProvider(proxy)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> args?.firstOrNull() === proxy
            else -> defaultValueFor(method.returnType)
        }
    }

    /** 原实现 applyFilters 的反射等价体（形态 A）：改写 context 内各工具的 enabled 状态。 */
    private fun applyFilters(context: Any?) {
        if (context == null) return
        runCatching {
            val predicate = Proxy.newProxyInstance(
                McpGateFilterEpBridge::class.java.classLoader,
                arrayOf(Class.forName(FUNCTION1_FQN)),
            ) { _, method, args ->
                if (method.name == "invoke") {
                    // 决策核收非空名（与原实现传 descriptor.name 的口径一致）；名字读不出
                    // （理论不可达）时按平台默认不隐藏处理——宁漏隐藏不误隐藏。
                    readToolName(args?.firstOrNull())
                        ?.let(McpToolGate::shouldHideFromExternalToolsList) ?: false
                } else {
                    defaultValueFor(method.returnType)
                }
            }
            val updateState = context.javaClass.getMethod(
                "updateState",
                Boolean::class.javaObjectType,
                Boolean::class.javaObjectType,
                Class.forName(FUNCTION1_FQN),
            )
            updateState.invoke(context, false, null, predicate)
        }.onFailure {
            LOG.warn("O-68: applyFilters reflection dispatch failed（门控对外部清单退化，其余功能不受影响）", it)
        }
    }

    /** 形态 B（261）过滤器清单：一个过滤器代理，语义与平台自带 DisallowMcpTools 一致。
     *  任何失败都返回空清单（非 null）——空清单只是门控不生效。 */
    private fun filters261(): List<Any> = runCatching {
        val loader = McpGateFilterEpBridge::class.java.classLoader
        val filterIface = Class.forName(TOOL_FILTER_FQN, true, loader)
        val modificationIface = Class.forName(TOOL_FILTER_MODIFICATION_FQN, true, loader)
        listOf(buildFilter261(filterIface, modificationIface))
    }.getOrElse {
        LOG.info("O-68: 261 过滤器接口不可解析，外部清单门控退化（MCP 服务不受影响）", it)
        emptyList()
    }

    /** 构造 `McpToolFilter` 代理（形态 B 第一段）。接口类由调用方给出，测试可注入本地假接口。
     *  `modify(context)` 必须返回非 null——平台紧接 `.apply(context)`，无判空。 */
    internal fun buildFilter261(filterIface: Class<*>, modificationIface: Class<*>): Any =
        Proxy.newProxyInstance(
            McpGateFilterEpBridge::class.java.classLoader,
            arrayOf(filterIface),
        ) { proxy, method, args ->
            when (method.name) {
                "modify" -> buildModification261(modificationIface)
                "toString" -> "SqlVisualizerToolGateFilter(proxy)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args?.firstOrNull() === proxy
                else -> defaultValueFor(method.returnType)
            }
        }

    /** 构造 `McpToolFilterModification` 代理（形态 B 第二段）：`apply(context)` 摘除隐藏工具。 */
    internal fun buildModification261(modificationIface: Class<*>): Any =
        Proxy.newProxyInstance(
            McpGateFilterEpBridge::class.java.classLoader,
            arrayOf(modificationIface),
        ) { proxy, method, args ->
            when (method.name) {
                "apply" -> filterContext261(args?.firstOrNull())
                "toString" -> "SqlVisualizerToolGateModification(proxy)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args?.firstOrNull() === proxy
                else -> defaultValueFor(method.returnType)
            }
        }

    /**
     * 形态 B 的上下文改写：`context.copy(disallowed + hidden, allowed - hidden)`。
     *
     * 平台只消费返回值里的 `allowedTools`（`getMcpToolsFiltered` 末尾 `context.allowedTools.toList()`），
     * disallowed 的维护是为了与平台自带过滤器保持一致语义。
     * 拿不到集合或 `copy`（契约漂移）时**原样返回 context**——等价于不隐藏，绝不抛出。
     */
    internal fun filterContext261(context: Any?): Any? {
        if (context == null) return null
        return runCatching {
            val disallowed = readToolSet(context, "getDisallowedTools")
            val allowed = readToolSet(context, "getAllowedTools")
            if (disallowed == null || allowed == null) {
                context
            } else {
                val hidden = allowed.filter { tool ->
                    readToolName(tool)?.let(McpToolGate::shouldHideFromExternalToolsList) == true
                }
                if (hidden.isEmpty()) {
                    context
                } else {
                    val copy = context.javaClass.getMethod("copy", Set::class.java, Set::class.java)
                    copy.invoke(context, disallowed + hidden, allowed - hidden.toSet())
                }
            }
        }.getOrElse {
            LOG.warn("O-68: 261 filter context 反射失败（门控对外部清单退化，其余功能不受影响）", it)
            context
        }
    }

    /** 平台 `McpToolFilterContext.getDisallowedTools()/getAllowedTools()` 的反射读取。 */
    private fun readToolSet(context: Any, getter: String): Set<Any?>? = runCatching {
        (context.javaClass.getMethod(getter).invoke(context) as? Set<*>)?.toSet()
    }.getOrNull()

    /** 平台 `McpTool.descriptor.name` 的反射读取；任何一步失败返回 null（谓词按不隐藏处理）。 */
    private fun readToolName(tool: Any?): String? {
        val descriptor = runCatching {
            tool?.javaClass?.getMethod("getDescriptor")?.invoke(tool)
        }.getOrNull() ?: return null
        return runCatching {
            descriptor.javaClass.getMethod("getName").invoke(descriptor) as? String
        }.getOrNull()
    }

    /**
     * 未知方法的返回值兜底：**集合/Map/Flow/原始类型一律给空值，绝不返回 null**。
     *
     * 为什么必须这么做：平台对过滤器方法的返回值直接消费（261 的 `getFilters` 结果直接进
     * `CollectionsKt.addAll`，`modify` 的结果直接 `.apply`），一次 null 就会让整条会话创建链
     * 抛 NPE。而 `Proxy` 对原始返回类型返回 null 同样直接抛 NPE。void 与对象类型仍可 null
     * （`getTransport` 之类「null＝不表态」的平台语义不在本桥内）。
     */
    internal fun defaultValueFor(type: Class<*>): Any? = when {
        type == Void.TYPE || type == Void::class.java -> null
        Set::class.java.isAssignableFrom(type) -> emptySet<Any>()
        Collection::class.java.isAssignableFrom(type) -> emptyList<Any>()
        Iterable::class.java.isAssignableFrom(type) -> emptyList<Any>()
        Map::class.java.isAssignableFrom(type) -> emptyMap<Any, Any>()
        Flow::class.java.isAssignableFrom(type) -> emptyFlow<Any>()
        type == java.lang.Boolean.TYPE -> false
        type == java.lang.Integer.TYPE -> 0
        type == java.lang.Long.TYPE -> 0L
        type == java.lang.Double.TYPE -> 0.0
        type == java.lang.Float.TYPE -> 0f
        type == java.lang.Short.TYPE -> 0.toShort()
        type == java.lang.Byte.TYPE -> 0.toByte()
        type == java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}

/** 平台过滤器 EP 的三种契约形态（探测结果；UNKNOWN 一律不注册）。 */
internal enum class FilterForm { A_APPLY, B_GET_LIST, UNKNOWN }

/**
 * 契约感知探测（T-Mcp261FilterContract 券02）：**只按方法签名判形态，不看类是否存在**。
 *
 * 261 上类存在但方法名不同（`getFilters` 而非 `applyFilters`），旧探测「类在即注册」把
 * 一个按 262 签名写的代理挂上去 → `getFilters` 落兜底 → 平台 `addAll(collection, null)`
 * → 内置 MCP Server 对所有客户端 500。故此处：
 *  - `applyFilters` 在场 → A（262/263）；
 *  - `getFilters` 双参且返回 `List` → B（261.24374+ / 261.25134）；
 *  - 其余（含 261.22158 的 `StateFlow` 形态 C、未来新形态）→ UNKNOWN，不注册。
 *
 * 判据只看方法名 + 参数个数 + 返回类型可赋值性——这三项都写在 JVM 描述符里，不依赖 Kotlin
 * 元数据，也不产生任何类型引用（`List` 是 JDK 类型）。
 */
internal fun detectFilterForm(iface: Class<*>): FilterForm = when {
    iface.methods.any { it.name == "applyFilters" } -> FilterForm.A_APPLY
    iface.methods.any {
        it.name == "getFilters" &&
            it.parameterCount == 2 &&
            List::class.java.isAssignableFrom(it.returnType)
    } -> FilterForm.B_GET_LIST
    else -> FilterForm.UNKNOWN
}

/** 形态的日志描述（注册日志里带形态，便于下一条版本线回溯）。 */
internal fun FilterForm.describe(): String = when (this) {
    FilterForm.A_APPLY -> "A(applyFilters, 262/263)"
    FilterForm.B_GET_LIST -> "B(getFilters, 261.24374+)"
    FilterForm.UNKNOWN -> "UNKNOWN"
}
