package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
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
 * **T-McpGlobalBreakHardening（20261118）：契约白名单 + 运行期熔断**。上面的兜底只解决
 * 「已知形态的方法名不同」，解决不了「平台给接口加了新方法」：`applyFilters` 仍在场时探测
 * 仍判 A，新方法落兜底、对象返回类型给 null，事故原样复发。故补两层：
 *  - **注册前**：接口上出现任何不认识的方法（不含 Object 基方法与编译器合成的 `$default`）
 *    即判 UNKNOWN、不注册；已知方法的参数个数/返回类型/是否 suspend 也一并收窄
 *    （[unknownContractMethods] / [detectFilterForm]）；
 *  - **注册后**：平台真的调到不认识的方法时，记一条 SEVERE 并**立刻把本 provider 从 EP 摘掉**
 *    （[onUnknownContract]），让后续清单请求不再经过本代理——门控失效，但平台 MCP 服务
 *    保持可用。摘除后本次运行期不再重新注册（契约已漂移，重挂只会再炸一次）。
 *
 * **加载纪律**：本对象只在 [McpGateFilterEpRegistrar.sync] 探测通过（形态 A 或 B）后
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

    /** 已注册的 EP 与 provider 实例（摘除/熔断用）。null = 未注册。 */
    private var registeredEp: ExtensionPoint<Any>? = null
    private var registeredProvider: Any? = null

    /** 熔断闸：平台调到未知契约方法后置位，本次运行期不再重新注册。 */
    private val breakerTripped = AtomicBoolean(false)

    /** 注册（幂等）。熔断后拒绝重挂——契约已漂移，重挂只会再炸一次。 */
    @Synchronized
    fun register() {
        if (registeredProvider != null) return
        if (breakerTripped.get()) {
            LOG.info("O-68: 熔断已置位，跳过 mcpToolFilterProvider 注册")
            return
        }
        val filterIface = Class.forName(FILTER_IFACE_FQN)
        val loader = McpGateFilterEpBridge::class.java.classLoader
        val provider = Proxy.newProxyInstance(
            loader,
            arrayOf(filterIface),
            providerHandler { signature -> tripBreaker(signature) },
        )
        val ep = ExtensionPointName.create<Any>(EP_NAME).getPoint(null)
        ep.registerExtension(provider)
        registeredEp = ep
        registeredProvider = provider
        LOG.info("O-68: sqlv gate filter registered onto mcpToolFilterProvider EP (proxy form)")
    }

    /** 从 EP 摘除（幂等）。总开关关闭 / Registry 逃生开关 / 熔断三处共用。 */
    @Synchronized
    fun unregister() {
        val ep = registeredEp ?: return
        val provider = registeredProvider ?: return
        registeredEp = null
        registeredProvider = null
        runCatching { ep.unregisterExtension(provider) }
            .onSuccess { LOG.info("O-68: sqlv gate filter unregistered from mcpToolFilterProvider EP") }
            .onFailure { LOG.warn("O-68: 摘除 mcpToolFilterProvider 注册失败（门控仍生效，MCP 不受影响）", it) }
    }

    internal fun isRegistered(): Boolean = registeredProvider != null

    internal fun isBreakerTripped(): Boolean = breakerTripped.get()

    /**
     * 置熔断位并摘除注册；只在首次返回 true（记 SEVERE + 摘除），后续调用静默。
     * 测试直接调本方法验「只触发一次」的语义（[handlerForTest] 的回调是每次未知调用都触发）。
     */
    internal fun tripBreaker(signature: String): Boolean {
        if (!breakerTripped.compareAndSet(false, true)) return false
        // 用 WARN 而不是 ERROR：平台测试框架把 LOG.error 当测试失败，且这条不是插件内部错误
        // （是平台契约变了、我们主动退让）。文案带 O-68 便于现场 grep。
        LOG.warn(
            "O-68: 平台调用了未识别的过滤器契约方法 $signature；已摘除 mcpToolFilterProvider 注册" +
                "（门控失效，内置 MCP Server 不受影响）",
        )
        unregister()
        return true
    }

    /** 测试缝：清空注册/熔断状态（应用级单例跨用例存活）。 */
    internal fun resetForTest() {
        breakerTripped.set(false)
        registeredEp = null
        registeredProvider = null
    }

    /** 平台对 provider 的全部调用分发（含 Object 基方法：代理不会绕过 handler）。
     *  形态 A/B 的入口共用一个 handler：平台只声明自己那一形态的方法，另一分支是死路。 */
    private fun providerHandler(): InvocationHandler =
        providerHandler { signature -> tripBreaker(signature) }

    /** 可注入「未知契约方法」回调的 handler（测试缝；生产回调＝[tripBreaker]）。 */
    internal fun handlerForTest(onUnknown: (String) -> Unit): InvocationHandler = providerHandler(onUnknown)

    private fun providerHandler(onUnknown: (String) -> Unit) = InvocationHandler { proxy, method, args ->
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
            // 未知方法：先按返回类型给安全值（本次调用不炸），同时通知熔断层摘除注册
            // （后续清单请求不再经过本代理）。顺序不能反——摘除只影响下一次调用。
            else -> {
                onUnknown(signatureOf(method))
                defaultValueFor(method.returnType)
            }
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
 * 契约白名单（T-McpGlobalBreakHardening）：本桥认识的平台方法名。
 *
 * 白名单的意义是**否决未知**，而不是「找到已知就注册」。261 的事故形态是「方法名不同」，
 * 而更危险的形态是「方法名都在、平台又加了一个」——只看有没有 `applyFilters` 会判 A 并注册，
 * 新方法落兜底、对象返回类型给 null，事故原样复发。故接口上出现任何白名单外的方法（Object
 * 基方法与编译器合成的 `$default` 桥除外）一律判 UNKNOWN、不注册。
 */
private val KNOWN_CONTRACT_METHODS = setOf("applyFilters", "getFilters", "getUpdates")

/** 接口上所有「不认识」的方法（日志与测试用；非空即契约未知）。 */
internal fun unknownContractMethods(iface: Class<*>): List<String> =
    iface.methods.filter { method ->
        !method.isSynthetic && !method.isBridge &&
            method.declaringClass != Any::class.java &&
            method.name !in KNOWN_CONTRACT_METHODS
    }.map { signatureOf(it) }

/** 方法签名摘要（日志/守卫共用）。 */
internal fun signatureOf(method: Method): String =
    "${method.name}/${method.parameterCount}->${method.returnType.simpleName}"

/**
 * Kotlin suspend 方法在 JVM 上表现为「末参 Continuation、返回 Object」。动态代理若对它返回
 * null，平台会当成「协程已挂起且永不恢复」——比抛异常更糟（会话请求一直挂着）。探测期一律
 * 按不可识别处理，不注册。
 */
private fun Method.isSuspendLike(): Boolean =
    parameterTypes.any { Continuation::class.java.isAssignableFrom(it) }

/**
 * 契约感知探测（T-Mcp261FilterContract 券02 + T-McpGlobalBreakHardening 券01）：
 * **只按方法签名判形态，不看类是否存在；未知方法直接否决**。
 *
 * 261 上类存在但方法名不同（`getFilters` 而非 `applyFilters`），旧探测「类在即注册」把
 * 一个按 262 签名写的代理挂上去 → `getFilters` 落兜底 → 平台 `addAll(collection, null)`
 * → 内置 MCP Server 对所有客户端 500。故此处：
 *  - 白名单外出现任何方法 → UNKNOWN；
 *  - `applyFilters` 四参、返回 void、非 suspend → A（262/263）；
 *  - `getFilters` 双参且返回 `List`、非 suspend → B（261.24374+ / 261.25134）；
 *  - `getUpdates` 若存在必须返回 `Flow` 且非 suspend；
 *  - 其余（含 261.22158 的 `StateFlow` 形态 C、未来新形态）→ UNKNOWN，不注册。
 *
 * 判据只看方法名 + 参数个数 + 返回类型可赋值性 + 是否 suspend——这些都写在 JVM 描述符里，
 * 不依赖 Kotlin 元数据，也不产生任何平台类型引用（`List`/`Flow`/`Continuation` 都来自
 * JDK 或 kotlin 标准库）。
 */
internal fun detectFilterForm(iface: Class<*>): FilterForm {
    if (unknownContractMethods(iface).isNotEmpty()) return FilterForm.UNKNOWN
    val methods = iface.methods
    val updates = methods.filter { it.name == "getUpdates" }
    if (updates.isNotEmpty() && updates.none {
            Flow::class.java.isAssignableFrom(it.returnType) && !it.isSuspendLike()
        }
    ) {
        return FilterForm.UNKNOWN
    }
    val apply = methods.filter { it.name == "applyFilters" }
    if (apply.isNotEmpty()) {
        val ok = apply.any {
            it.parameterCount == 4 && it.returnType == Void.TYPE && !it.isSuspendLike()
        }
        return if (ok) FilterForm.A_APPLY else FilterForm.UNKNOWN
    }
    val getFilters = methods.filter { it.name == "getFilters" }
    if (getFilters.isNotEmpty()) {
        val ok = getFilters.any {
            it.parameterCount == 2 &&
                List::class.java.isAssignableFrom(it.returnType) &&
                !it.isSuspendLike()
        }
        return if (ok) FilterForm.B_GET_LIST else FilterForm.UNKNOWN
    }
    return FilterForm.UNKNOWN
}

/** 形态的日志描述（注册日志里带形态，便于下一条版本线回溯）。 */
internal fun FilterForm.describe(): String = when (this) {
    FilterForm.A_APPLY -> "A(applyFilters, 262/263)"
    FilterForm.B_GET_LIST -> "B(getFilters, 261.24374+)"
    FilterForm.UNKNOWN -> "UNKNOWN"
}
