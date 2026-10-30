package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
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
 *  - A（`applyFilters` 在场，262/263）→ 注册；
 *  - B（`getFilters` 双参返回 List，261.24374+/261.25134）→ 注册；
 *  - UNKNOWN（261.22158 的 `StateFlow` 形态、未来新形态）→ **不注册**，留一条带方法签名的
 *    INFO 日志（下一条版本线可直接定位），MCP 服务保持可用。
 *
 * 线程与时点：ProjectActivity 每项目启动各触发一次，[McpGateFilterEpRegistrar.registerOnce]
 * 以原子闸保证全应用只注册一次；EP 层带 ExtensionPointListener（字节码实证），晚注册亦会
 * 触发清单重算，无需重启 IDE。
 */
class McpGateFilterRegistrar : ProjectActivity {

    override suspend fun execute(project: Project) {
        // T-McpDynamicToolset：十工具动态壳注册先走（探测 McpToolset 接口，253+ Marketplace 包/
        // 262 内置命中；filter EP 契约分形态，两者独立探测独立降级）。
        McpToolsetDynamicRegistrar.registerOnce()
        McpGateFilterEpRegistrar.registerOnce()
    }
}

internal object McpGateFilterEpRegistrar {

    private val LOG = Logger.getInstance(McpGateFilterEpRegistrar::class.java)

    private val registered = AtomicBoolean(false)

    fun registerOnce() {
        if (registered.get()) return
        val iface = runCatching { Class.forName("com.intellij.mcpserver.McpToolFilterProvider") }.getOrNull()
            ?: return
        val form = runCatching { detectFilterForm(iface) }.getOrDefault(FilterForm.UNKNOWN)
        if (form == FilterForm.UNKNOWN) {
            // 已决策（平台版本在进程生命周期内不会变），置位避免逐项目重复刷日志
            registered.set(true)
            LOG.info(
                "O-68: 未识别的 mcpToolFilterProvider 契约，跳过注册（外部清单门控不生效，MCP 服务不受影响）：" +
                    iface.methods.joinToString { "${it.name}/${it.parameterCount}->${it.returnType.simpleName}" },
            )
            return
        }
        runCatching { McpGateFilterEpBridge.register() }
            .onSuccess {
                registered.set(true)
                LOG.info("O-68: sqlv gate filter registered onto mcpToolFilterProvider EP（form=${form.describe()}）")
            }
            .onFailure {
                LOG.warn("O-68: mcpToolFilterProvider EP 注册失败（外部会话门控退化，其余功能不受影响）", it)
            }
    }
}
