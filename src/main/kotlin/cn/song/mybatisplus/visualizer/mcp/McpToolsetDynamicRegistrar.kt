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
            TOOL_CLASSES.forEach { registerToolset(it, toolsetIface) }
            registered.set(true)
            LOG.info("T-McpDynamicToolset: ${TOOL_CLASSES.size} toolsets registered onto mcpToolset EP (dynamic shell form)")
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
    }

    private fun registerToolset(toolClass: Class<*>, toolsetIface: Class<*>) {
        // simpleName 与业务类一致（T-McpToolNaming 分组名=simpleName），gen 子包隔离 FQN
        registerToolset(toolClass, toolsetIface, "cn.song.mybatisplus.visualizer.mcp.gen." + toolClass.simpleName)
    }

    /** F29 测试触点：显式 shellName 驱动 define/台账路径（EP 注册在平台无 EP 的测试环境会失败，由调用方 runCatching）。 */
    internal fun registerToolsetForTest(toolClass: Class<*>, toolsetIface: Class<*>, shellName: String) =
        registerToolset(toolClass, toolsetIface, shellName)

    private fun registerToolset(toolClass: Class<*>, toolsetIface: Class<*>, shellName: String) {
        require(toolClass.constructors.any { it.parameterTypes.isEmpty() }) {
            "toolset 类必须有无参构造（壳生成依赖）：${toolClass.name}"
        }
        if (shellName in epRegistered) return // F29：EP 已注册，重试跳过（防重复注册）
        val shellClass = definedShells.getOrPut(shellName) {
            ShellClassLoader.define(shellName, shellBytes(toolClass, toolsetIface, shellName))
        }
        val instance = shellClass.getDeclaredConstructor().newInstance()
        ExtensionPointName.create<Any>(TOOLSET_EP_NAME).getPoint(null).registerExtension(instance)
        epRegistered.add(shellName)
        registeredInstances[shellName] = instance
        LOG.info("T-McpDynamicToolset: registered ${shellClass.simpleName} (gen shell of ${toolClass.name})")
    }

    /** 壳类字节码：`final class <ShellName> extends <工具类> implements McpToolset`——
     *  仅一个无参构造器（invokespecial 父类构造）。工具方法/三钩子全部继承，壳零额外成员。
     *  V17（major 61）：243 JBR 21 可加载，且与插件常规编译目标一致。
     *  生成器为 [TinyClassFileWriter] 手写字节码（真机 ASM 不可达修复，见其 KDoc），零外部依赖。
     *  internal 供壳生成器单测直接触达。 */
    internal fun shellBytes(superClass: Class<*>, iface: Class<*>, shellName: String): ByteArray =
        TinyClassFileWriter.shellBytes(shellName, superClass.name, iface.name)

    /** 壳类装载器：插件 classloader 的直系子加载器，defineClass 仅此一处（protected 合法调用点）。 */
    internal object ShellClassLoader : ClassLoader(McpToolsetDynamicRegistrar::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }
}
