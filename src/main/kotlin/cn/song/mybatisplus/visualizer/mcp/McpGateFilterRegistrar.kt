package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.registry.Registry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T-McpGateDynamicFilter（O-68）注册入口：把 [McpToolGate] 的门控决策接进平台
 * `com.intellij.mcpServer.mcpToolFilterProvider` EP。
 *
 * **为什么是探测式程序化注册而非 XML 声明**：
 *  1. 该 EP 仅 261+ 存在（253 线 idea-2025.3.5 mcpserver.jar 本地实测无此类）——XML 声明在
 *     253 会按未知 EP 拒载，与十个 toolset 同文件即连带失联；
 *  2. 独立片段方案需要第二条 `com.intellij.mcpServer` optional depends，verifier 判
 *     "duplicated dependency" 结构缺陷（2026-09-23 verifyGate 实测）——同插件依赖不可声明两次。
 * 故挂在本文件于 mcp-optional.xml（mcpServer 在场才加载）的 postStartupActivity：
 * [Class.forName] 探测 EP 接口（253 无 → 静默跳过，行为与改动前一致），命中才走
 * [McpGateFilterEpBridge] 的类型化注册（桥类含平台过滤类型的方法名，仅在探测通过后被触达）。
 *
 * **T-Mcp261FilterContract（20261029）订正：探测从「类是否存在」升级为「契约形态」**。
 * 261 线自 261.22158 起有这个类、EP 名也相同，但方法契约与 262 完全不同（`getFilters` 而非
 * `applyFilters`）。旧探测只判类存在 → 按 262 写的代理被挂到 261 上 → `getFilters` 落兜底
 * 返回 null → 平台 `CollectionsKt.addAll(collection, null)` 抛 NPE（该调用不在 try/catch 内）
 * → **内置 MCP Server 对所有客户端 HTTP 500**（2026-10-29 用户报障）。现按
 * [detectFilterForm] 分流：
 *  - A（`applyFilters` 四参返回 void，262/263）→ 注册；
 *  - B（`getFilters` 双参返回 List，261.24374+/261.25134）→ 注册；
 *  - UNKNOWN（261.22158 的 `StateFlow` 形态、未来新形态、以及任何白名单外的方法）→ **不注册**，
 *    留一条带方法签名的 INFO 日志（下一条版本线可直接定位），MCP 服务保持可用。
 *
 * **T-McpGlobalBreakHardening（20261118）：总开关联动 + 逃生开关 + 状态可读**。
 * 2026-10-29 报障里「关掉插件 MCP 总开关也无效、只能卸载插件」的根因是注册与开关无关。
 * 现在：
 *  - `mcpToolsEnabled=false` 时**两个注册面都不挂**（toolset 与过滤 EP），关掉功能的用户
 *    完全不触碰平台链；设置页切回开启即重新注册（动态 EP 晚注册会触发清单重算）；
 *  - Registry `sqlvis.mcp.gateFilterEp`（默认 true）是逃生开关：万一再遇契约漂移，用户可以
 *    只关这一项而不用卸载插件；
 *  - [GateRegistrationState] 供设置页只读展示「门控生效 / 未识别 / 已关闭 / 无此能力 / 已熔断」。
 *
 * 线程与时点：ProjectActivity 每项目启动各触发一次，[McpGateRegistrationController.sync]
 * 幂等；设置页 Apply 经 [PluginSettings.SETTINGS_CHANGE_TOPIC] 广播后再同步一次。
 */
class McpGateFilterRegistrar : ProjectActivity {

    override suspend fun execute(project: Project) {
        McpGateRegistrationController.sync()
    }
}

/** 外部会话门控的注册状态（设置页只读展示 + 日志；不表示工具是否可调用）。 */
internal enum class GateRegistrationState {
    /** 尚未探测（项目还没打开过）。 */
    PENDING,

    /** 已挂到平台过滤 EP，门控生效。 */
    REGISTERED,

    /** 平台契约不在白名单内（含形态 C），不注册——门控不生效，MCP 服务不受影响。 */
    UNRECOGNIZED,

    /** 总开关关闭或 Registry 逃生开关关闭，不注册。 */
    DISABLED,

    /** 当前 IDE 没有该平台能力（253 及更早）。 */
    UNSUPPORTED,

    /** 运行期发现契约漂移，已自动摘除注册——门控不生效，MCP 服务不受影响。 */
    TRIPPED,
}

/**
 * 注册同步器：把「总开关 + 平台契约 + 逃生开关」三件事收成一个幂等入口。
 *
 * 所有注册面都从这里进出，避免出现「toolset 注册了、过滤 EP 没注册」或反过来的半开状态。
 */
internal object McpGateRegistrationController {

    private val LOG = Logger.getInstance(McpGateRegistrationController::class.java)

    private val listening = AtomicBoolean(false)

    /** 订阅设置变更（应用级、进程内一次）。设置页 Apply 后重新同步注册状态。 */
    fun ensureListening() {
        if (!listening.compareAndSet(false, true)) return
        runCatching {
            ApplicationManager.getApplication().messageBus.connect()
                .subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, SettingsChangeListener { sync() })
        }.onFailure {
            listening.set(false)
            LOG.warn("O-68: 设置变更监听订阅失败（总开关切换后需重启 IDE 生效）", it)
        }
    }

    /** 按当前总开关/逃生开关/平台契约同步两个注册面（幂等，可反复调用）。 */
    fun sync() {
        ensureListening()
        if (McpToolGate.isMasterEnabled()) {
            McpToolsetDynamicRegistrar.registerOnce()
        } else {
            McpToolsetDynamicRegistrar.unregisterAll()
        }
        McpGateFilterEpRegistrar.sync()
    }
}

internal object McpGateFilterEpRegistrar {

    private val LOG = Logger.getInstance(McpGateFilterEpRegistrar::class.java)

    private const val FILTER_IFACE_FQN = "com.intellij.mcpserver.McpToolFilterProvider"

    /** 逃生开关：false = 不注册过滤 EP（门控不生效，MCP 服务不受影响）。 */
    internal const val ESCAPE_REGISTRY_KEY = "sqlvis.mcp.gateFilterEp"

    @Volatile
    var state: GateRegistrationState = GateRegistrationState.PENDING
        private set

    /** 契约探测 + 注册（幂等）。总开关关闭/逃生开关关闭/契约未知时只摘除，不注册。 */
    fun sync() {
        if (!McpToolGate.isMasterEnabled()) {
            McpGateFilterEpBridge.unregister()
            state = GateRegistrationState.DISABLED
            return
        }
        if (!escapeHatchEnabled()) {
            McpGateFilterEpBridge.unregister()
            state = GateRegistrationState.DISABLED
            LOG.info(
                "O-68: Registry $ESCAPE_REGISTRY_KEY=false，跳过 mcpToolFilterProvider 注册" +
                    "（外部清单门控不生效，MCP 服务不受影响）",
            )
            return
        }
        val iface = runCatching { Class.forName(FILTER_IFACE_FQN) }.getOrNull()
        if (iface == null) {
            McpGateFilterEpBridge.unregister()
            state = GateRegistrationState.UNSUPPORTED
            LOG.info("O-68: 当前 IDE 无 McpToolFilterProvider（253 及更早），不注册（MCP 服务不受影响）")
            return
        }
        val form = runCatching { detectFilterForm(iface) }.getOrDefault(FilterForm.UNKNOWN)
        if (form == FilterForm.UNKNOWN) {
            McpGateFilterEpBridge.unregister()
            state = GateRegistrationState.UNRECOGNIZED
            LOG.info(
                "O-68: 未识别的 mcpToolFilterProvider 契约，跳过注册（外部清单门控不生效，MCP 服务不受影响）：" +
                    iface.methods.joinToString { signatureOf(it) },
            )
            return
        }
        runCatching { McpGateFilterEpBridge.register() }
            .onSuccess {
                state = if (McpGateFilterEpBridge.isBreakerTripped()) {
                    GateRegistrationState.TRIPPED
                } else {
                    GateRegistrationState.REGISTERED
                }
                LOG.info(
                    "O-68: mcpToolFilterProvider 注册完成（form=${form.describe()}, build=${ideBuild()}）",
                )
            }
            .onFailure {
                state = GateRegistrationState.UNRECOGNIZED
                LOG.warn("O-68: mcpToolFilterProvider EP 注册失败（外部会话门控退化，其余功能不受影响）", it)
            }
    }

    internal fun escapeHatchEnabled(): Boolean =
        runCatching { Registry.get(ESCAPE_REGISTRY_KEY).asBoolean() }.getOrDefault(true)

    private fun ideBuild(): String =
        runCatching { ApplicationInfo.getInstance().build.asString() }.getOrDefault("unknown")
}
