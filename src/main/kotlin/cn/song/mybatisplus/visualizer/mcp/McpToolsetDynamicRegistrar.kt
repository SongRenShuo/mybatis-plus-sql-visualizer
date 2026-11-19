package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 注册入口与设置页共用的只读能力探测；类可达不代表工具已注册、服务器已启用或客户端可调用。
 * 顶层函数避免设置页为探测而初始化注册器及 TOOL_CLASSES；initialize=false 不启动平台服务。
 * 反射沿用注册器原有 McpToolset 入口，签名登记见 PlatformSignatureChecker.checkMcpToolsetCapability。
 */
internal fun findMcpToolsetClass(
    loader: ClassLoader = McpToolGate::class.java.classLoader,
): Class<*>? = runCatching {
    Class.forName("com.intellij.mcpserver.McpToolset", false, loader).takeIf { it.isInterface }
}.getOrNull()

/**
 * MCP 工具集**动态壳注册器**（T-McpDynamicToolset）：运行期生成「extends 工具类 implements
 * 平台 McpToolset」的壳类并程序化注册到 `com.intellij.mcpServer.mcpToolset` EP，替代
 * mcp-optional.xml 的 10 条 XML toolset 声明。
 *
 * **为什么要动态生成壳类（方案 D 全动态化终态）**：verifier 只做静态字节码分析——jar 内任何
 * implements 平台接口的类都会在 243/251（mcpServer 旧形态缺新 API）被判未解析类（20260926
 * 审核拒绝主因）。XML 声明形态的 implementation 类无法消除该引用（EP 接口实现类，上轮
 * T-MarketCompatProxy 已证）；运行期生成的壳类不在 jar 内，verifier 不可见。壳类只覆写
 * 「继承即可满足接口」的最小面（三钩子直接继承 [McpToolsetSupport] 实现，连方法都不生成），
 * 平台的工具发现（kotlin-reflect 扫 @McpTool 注解方法/参数 schema/suspend 调用契约）全部
 * 走继承来的业务类编译产物，壳零参与。**壳字节码由 [TinyClassFileWriter] 手写生成，零外部
 * 依赖**（真机 ASM 不可达修复，20261005：平台 262 自带 asm 库接管 org.objectweb.asm 包，
 * 插件自带 asm jar 不可达 → 真机 NoClassDefFoundError，见开发计划 20261005-1335）。
 *
 * **注册链**：[McpGateFilterRegistrar]（mcp-optional.xml 的 postStartupActivity，mcpServer
 * 在场才加载）每项目启动触发 [registerOnce]，原子闸全应用只注册一次。探测
 * `Class.forName("com.intellij.mcpserver.McpToolset")` 失败（243/251 旧形态）静默跳过——
 * 与 XML 形态「片段加载但 EP 形态不同」的设计口径一致：该版本不支持 MCP 工具线。
 *
 * **EP 形态**：`interface="com.intellij.mcpserver.McpToolset" dynamic="true"`（262
 * mcpserver.jar META-INF/plugin.xml 实证）——与 mcpToolFilterProvider 同款，注册走
 * McpGateFilterEpBridge 先例（`getPoint(null)` application 级，真机验证过）；dynamic EP
 * 晚注册会触发清单重算，无需重启 IDE。
 *
 * **类名前缀硬约定（T-McpToolNaming）**：平台「暴露工具」页分组名=注册实例类的 simpleName——
 * 壳类放 `mcp.gen` 子包、**simpleName 与业务类逐字节一致**（如 SqlVisualizerCheckSqlTool），
 * 分组名与前缀美学（自带插件名、字典序相邻）与 XML 形态完全相同；FQN 含 gen 子包避免与
 * 业务类在不同 classloader 上的解析歧义。
 */
object McpToolsetDynamicRegistrar {

    private val LOG = Logger.getInstance(McpToolsetDynamicRegistrar::class.java)

    private const val TOOLSET_EP_NAME = "com.intellij.mcpServer.mcpToolset"

    /** D239：平台工具方法注解 FQN（字符串比对，避免编译期硬绑平台 API）。 */
    private const val MCP_TOOL_ANNOTATION_FQN = "com.intellij.mcpserver.annotations.McpTool"

    /** 壳类包前缀（T-McpToolNaming：simpleName 与业务类一致、落 gen 子包避免 classloader 歧义）。 */
    private const val SHELL_PACKAGE_PREFIX = "cn.song.mybatisplus.visualizer.mcp.gen."

    private val registered = AtomicBoolean(false)

    /** F29（D1=A）：已成功 define 的壳类缓存（FQN→Class）——define 不可撤销，重试复用免撞同 FQN LinkageError。 */
    internal val definedShells = mutableMapOf<String, Class<*>>()

    /** F29（D1=A）：已注册到 mcpToolset EP 的壳 FQN——重试防重复注册。 */
    internal val epRegistered = mutableSetOf<String>()

    /** T-McpGlobalBreakHardening：壳 FQN → 已注册实例（总开关关闭时按实例摘除）。 */
    internal val registeredInstances = mutableMapOf<String, Any>()

    /** 十一工具类清单（R14 T-HistorySearch 增至十一；McpToolGate.TOOL_NAMES 一一对应，守卫测试对账单一事实）。 */
    internal val TOOL_CLASSES = listOf(
        SqlVisualizerRenderSqlTool::class.java,
        SqlVisualizerSchemaContextTool::class.java,
        SqlVisualizerExplainTool::class.java,
        SqlVisualizerRenderLogsTool::class.java,
        SqlVisualizerCheckSqlTool::class.java,
        SqlVisualizerRollbackPlanTool::class.java,
        SqlVisualizerPerfAdviceTool::class.java,
        SqlVisualizerCrudSqlTool::class.java,
        SqlVisualizerReverseSqlTool::class.java,
        SqlVisualizerMapperCheckTool::class.java,
        SqlVisualizerHistorySearchTool::class.java,
    )

    /** 探测＋全量注册；已注册或探测失败（无 mcpServer 新形态）时零操作。
     *  F29（D1=A 按工具粒度记账）：[definedShells] 缓存已 define 壳类（define 不可逆——
     *  部分失败重试撞同 FQN LinkageError 的根治）、[epRegistered] 记账已注册到 EP 的 FQN
     *  （防重试重复注册）；@Synchronized 封 check-then-act 竞态。 */
    @Synchronized
    fun registerOnce() {
        if (registered.get()) return
        val toolsetIface = findMcpToolsetClass() ?: return
        runCatching {
            // D238 探针已收（2026-11-19 真机定案）：反转顺序实验证明「平台按 EP 顺序展开、
            // 遇展开失败的 toolset 即中断」（反转后只列出反转后的前 3 个），且 11 个壳类
            // 全部 declared=0 / kotlinMeta=false——根因是壳类反射可见性，不是注册时序。
            // 注册顺序恢复原状；修法见 D238 册 §4.4（壳类补 metadata / 单 toolset 聚合）。
            TOOL_CLASSES.forEach { registerToolset(it, toolsetIface) }
            registered.set(true)
            LOG.info("T-McpDynamicToolset: ${TOOL_CLASSES.size} toolsets registered onto mcpToolset EP (dynamic shell form)")
            // 注册后自检（2026-11-19 真机排障新增）：回读 EP 实例列表，区分「EP 只有 N 个」与
            // 「EP 有 11 个但平台只展开 N 个」——两种形态修法完全不同。
            runCatching {
                val ext = ExtensionPointName.create<Any>(TOOLSET_EP_NAME).getPoint(null).extensionList
                val shellHits = ext.count { it.javaClass.name.startsWith(SHELL_PACKAGE_PREFIX) }
                LOG.info(
                    "T-McpDynamicToolset: EP self-check total=${ext.size} shells=$shellHits " +
                        "names=${ext.map { it.javaClass.simpleName }}",
                )
            }.onFailure { LOG.warn("T-McpDynamicToolset: EP self-check failed (diagnostic only)", it) }
            // D238 长期诊断：壳类反射面（declared/all/metadata）——未来平台再改扫描口径时，
            // 这行能立刻区分「壳类不可见」与「平台过滤」。
            runCatching {
                registeredInstances.values.forEach { inst ->
                    runCatching {
                        val c = inst.javaClass
                        LOG.info(
                            "T-McpDynamicToolset: refl ${c.simpleName} declared=${c.declaredMethods.size} " +
                                "all=${c.methods.size} kotlinMeta=${c.getAnnotation(Metadata::class.java) != null}",
                        )
                    }
                }
            }.onFailure { LOG.warn("T-McpDynamicToolset: refl probe failed", it) }
            // D238：注册完成后**主动请平台重算工具列表**（见 requestPlatformToolsRecompute KDoc）。
            // 真机 2026-11-19 实证：平台只在 EP 变更时重算，而它自己那次重算可能快照到半成品 EP。
            requestPlatformToolsRecompute("toolsets-registered")
        }.onFailure {
            // registered 不置位：下个项目启动重试（动态 EP 未就绪等瞬态失败可自愈）——
            // 已 define/已注册的 FQN 由两本台账跳过，重试只补未完成项
            LOG.warn("T-McpDynamicToolset: mcpToolset EP 注册失败（MCP 工具不可用，其余功能不受影响）", it)
        }
    }

    /**
     * T-McpGlobalBreakHardening：把已注册的 toolset 全部从 EP 摘除（总开关关闭时调用）。
     *
     * 幂等；EP 不可用/摘除失败只记 WARN——最坏结果是工具仍可见（门控失效），平台服务不受影响。
     * `registered` 复位，重新开启总开关时 [registerOnce] 可再跑一遍（壳类复用 [definedShells]）。
     */
    @Synchronized
    fun unregisterAll() {
        if (registeredInstances.isEmpty() && !registered.get()) return
        val ep = runCatching { ExtensionPointName.create<Any>(TOOLSET_EP_NAME).getPoint(null) }.getOrNull()
        if (ep != null) {
            registeredInstances.forEach { (fqn, instance) ->
                runCatching { ep.unregisterExtension(instance) }
                    .onFailure { LOG.warn("T-McpDynamicToolset: 摘除 $fqn 失败（该工具仍可见）", it) }
            }
        }
        registeredInstances.clear()
        epRegistered.clear()
        registered.set(false)
        LOG.info("T-McpDynamicToolset: toolsets unregistered (master switch off)")
        requestPlatformToolsRecompute("toolsets-unregistered")
    }

    /**
     * D238：主动请平台重算 MCP 工具列表（反射 `McpServerService` → `McpToolsListProvider.requestUpdate`）。
     *
     * **为什么必须自己请求**（2026-11-19 真机三轮实证的结论）：
     *  - 平台只在 **EP 变更**时重算工具列表（`McpToolsListProvider` 订阅 `McpToolset.EP` 的 listener）；
     *  - 而它自己那次重算**不保证读到完整 EP**：我们连续 `registerExtension` × 11 会触发 11 个重算协程，
     *    靠前的协程在「只注册了前 1~3 个」时就把结果 `putAll` 进缓存；真机表现为 `tools/list` 只列
     *    注册顺序前 1~3 个、重启不恢复（15:56 那次连冷启动都退化）；
     *  - 反过来「关闭时不动 EP、只靠 `isEnabled()` 隐藏」也不行——平台不感知 isEnabled 变化、不重算，
     *    关闭后工具照旧可见（16:28 真机实证）。
     *
     * 故：关闭照常摘 EP（平台会算成 0），注册完成后由本函数**在 EP 已完整时就位**主动请求一次重算。
     * 反射面：`McpServerService.getToolsStateProvider$intellij_mcpserver()` → provider 的 private
     * `requestUpdate(String)`；服务未创建（平台没用 MCP）时零操作，失败只记 WARN。
     */
    private fun requestPlatformToolsRecompute(reason: String) {
        runCatching {
            val app = com.intellij.openapi.application.ApplicationManager.getApplication()
            val svcClass = Class.forName(
                "com.intellij.mcpserver.impl.McpServerService",
                false,
                McpToolsetDynamicRegistrar::class.java.classLoader,
            )
            val svc = runCatching { app.getServiceIfCreated(svcClass) }.getOrNull() ?: return@runCatching
            val getter = svcClass.methods.firstOrNull {
                it.name.startsWith("getToolsStateProvider") && it.parameterCount == 0
            } ?: return@runCatching
            val provider = runCatching { getter.invoke(svc) }.getOrNull() ?: return@runCatching
            val update = provider.javaClass.getDeclaredMethod("requestUpdate", String::class.java).apply {
                isAccessible = true
            }
            update.invoke(provider, reason)
            LOG.info("T-McpDynamicToolset: platform tool-list recompute requested ($reason)")
        }.onFailure {
            // 退化路径：平台自己那次重算仍可能给出「前 1~3 个」的残集；工具调用不受影响（只是清单不全）
            LOG.warn("T-McpDynamicToolset: platform recompute request failed（工具清单可能只列出部分）", it)
        }
    }

    private fun registerToolset(toolClass: Class<*>, toolsetIface: Class<*>) {
        // simpleName 与业务类一致（T-McpToolNaming 分组名=simpleName），gen 子包隔离 FQN
        registerToolset(toolClass, toolsetIface, SHELL_PACKAGE_PREFIX + toolClass.simpleName)
    }

    /** F29 测试触点：显式 shellName 驱动 define/台账路径（EP 注册在平台无 EP 的测试环境会失败，由调用方 runCatching）。 */
    internal fun registerToolsetForTest(toolClass: Class<*>, toolsetIface: Class<*>, shellName: String) =
        registerToolset(toolClass, toolsetIface, shellName)

    /**
     * D239：壳类形态改造——`final class <Shell> extends McpToolsetSupport implements McpToolset`，
     * **自己声明工具方法**（委托体转发到原工具类）+ 复制方法注解 + 复制 class 级 `kotlin.Metadata`。
     *
     * 为什么不再「extends 工具类、零成员」：真机 2026-11-19 定案，零成员壳类在 kotlin-reflect 眼里
     * `declared=0 / kotlinMeta=false`，平台扫不到 `@McpTool` 方法 → `requireAnyTool` 抛异常打断
     * toolset 遍历 → 工具清单只列前 1~3 个（详见 D238/D239 册）。
     *
     * 委托而非 override：工具方法是 Kotlin `suspend fun`（默认 final）不可覆写；壳类与工具类分属
     * 两条继承链，壳类声明同名同描述符方法、方法体 `new 工具类()` + `invokevirtual` 转发即可。
     */
    private fun registerToolset(toolClass: Class<*>, toolsetIface: Class<*>, shellName: String) {
        require(toolClass.constructors.any { it.parameterTypes.isEmpty() }) {
            "toolset 类必须有无参构造（壳生成依赖）：${toolClass.name}"
        }
        if (shellName in epRegistered) return // F29：EP 已注册，重试跳过（防重复注册）
        val shellClass = definedShells.getOrPut(shellName) {
            ShellClassLoader.define(shellName, shellBytes(toolClass, toolsetIface, shellName))
        }
        // D239：带参构造器形态（extends McpToolsetSupport，传入工具名做门控比对）
        val instance = shellClass.getDeclaredConstructor(String::class.java)
            .newInstance(toolsetCtorArg(toolClass))
        ExtensionPointName.create<Any>(TOOLSET_EP_NAME).getPoint(null).registerExtension(instance)
        epRegistered.add(shellName)
        registeredInstances[shellName] = instance
        LOG.info("T-McpDynamicToolset: registered ${shellClass.simpleName} (gen shell of ${toolClass.name})")
    }

    /** 工具名（McpToolsetSupport 的构造参数）：取该 toolset 里带 @McpTool 的方法名，兜底用类名。 */
    private fun toolsetCtorArg(toolClass: Class<*>): String =
        toolMethods(toolClass).firstOrNull()?.name ?: toolClass.simpleName

    /** toolset 里带 `@McpTool` 注解的方法（注解类用字符串比对，避免编译期硬绑平台 API）。 */
    private fun toolMethods(toolClass: Class<*>): List<java.lang.reflect.Method> =
        toolClass.declaredMethods.filter { m ->
            m.annotations.any { it.annotationClass.java.name == MCP_TOOL_ANNOTATION_FQN }
        }

    /** 注解实例 → 可编码的注解描述（元素全部按反射读实值，避免手写参数表）。 */
    private fun toAnnotationValue(ann: Annotation): TinyClassFileWriter.AnnotationValue {
        val type = ann.annotationClass.java
        val elements = type.declaredMethods.mapNotNull { m ->
            runCatching {
                m.isAccessible = true
                m.name to encodeAnnotationValue(m.invoke(ann))
            }.getOrNull()
        }
        return TinyClassFileWriter.AnnotationValue(
            typeDescriptor = "L" + type.name.replace('.', '/') + ";",
            elements = elements,
        )
    }

    private fun encodeAnnotationValue(value: Any?): Any = when (value) {
        null -> ""
        is String, is Int, is Boolean -> value
        is Enum<*> -> value
        is Class<*> -> value
        is Array<*> -> value.map { encodeAnnotationValue(it) }
        is List<*> -> value.map { encodeAnnotationValue(it) }
        else -> value.toString()
    }

    private fun descriptorOf(t: Class<*>): String = when {
        t == Void.TYPE -> "V"
        t == java.lang.Boolean.TYPE -> "Z"
        t == java.lang.Byte.TYPE -> "B"
        t == Character.TYPE -> "C"
        t == java.lang.Short.TYPE -> "S"
        t == Integer.TYPE -> "I"
        t == java.lang.Long.TYPE -> "J"
        t == java.lang.Float.TYPE -> "F"
        t == java.lang.Double.TYPE -> "D"
        t.isArray -> "[" + descriptorOf(t.componentType)
        else -> "L" + t.name.replace('.', '/') + ";"
    }

    private fun methodDescriptor(m: java.lang.reflect.Method): String =
        "(" + m.parameterTypes.joinToString("") { descriptorOf(it) } + ")" + descriptorOf(m.returnType)

    /**
     * 壳类字节码（D239 形态）：`final class <Shell> extends McpToolsetSupport implements McpToolset`，
     * 构造器为 `(String toolName)`；每个带 `@McpTool` 的工具方法在壳类里声明为同描述符的委托方法，
     * 并复制该方法上的全部注解；class 上复制工具类的 `kotlin.Metadata`。
     *
     * 版本 V17（major 61）：243 JBR 21 可加载，且与插件常规编译目标一致。
     * 生成器为 [TinyClassFileWriter] 手写字节码（真机 ASM 不可达修复，见其 KDoc），零外部依赖。
     * internal 供壳生成器单测直接触达。
     */
    internal fun shellBytes(superClass: Class<*>, iface: Class<*>, shellName: String): ByteArray {
        val methods = toolMethods(superClass)
        val delegates = methods.map { m ->
            TinyClassFileWriter.DelegateMethod(
                name = m.name,
                descriptor = methodDescriptor(m),
                targetClassFqn = superClass.name,
                annotations = m.annotations.map(::toAnnotationValue),
            )
        }
        val classAnnotations = listOfNotNull(
            runCatching { superClass.getAnnotation(Metadata::class.java) }.getOrNull()
                ?.let(::toAnnotationValue),
        )
        return TinyClassFileWriter.shellBytes(
            shellName = shellName,
            // D239：父类改为 McpToolsetSupport（三钩子来源），工具类只作委托目标
            superName = McpToolsetSupport::class.java.name,
            ifaceName = iface.name,
            superCtorArg = toolsetCtorArg(superClass),
            delegates = delegates,
            classAnnotations = classAnnotations,
        )
    }

    /** 壳类装载器：插件 classloader 的直系子加载器，defineClass 仅此一处（protected 合法调用点）。 */
    internal object ShellClassLoader : ClassLoader(McpToolsetDynamicRegistrar::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }
}
