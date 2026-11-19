// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterRegistrar.kt（class McpGateFilterRegistrar 声明）
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;

import org.jetbrains.annotations.NotNull;

import kotlin.Unit;
import kotlin.coroutines.Continuation;

/**
 * T-McpGateDynamicFilter（O-68）注册入口：把 {@code McpToolGate} 的门控决策（动态代理形态，
 * 见 {@link McpGateFilterEpBridge}）接进平台 {@code com.intellij.mcpServer.mcpToolFilterProvider}
 * EP。
 *
 * <p><b>为什么是探测式程序化注册而非 XML 声明</b>：
 * <ol>
 *   <li>该 EP 仅 261+ 存在（253 线 idea-2025.3.5 mcpserver.jar 本地实测无此类）——XML 声明在
 *       253 会按未知 EP 拒载，与十个 toolset 同文件即连带失联；</li>
 *   <li>独立片段方案需要第二条 {@code com.intellij.mcpServer} optional depends，verifier 判
 *       "duplicated dependency" 结构缺陷（2026-09-23 verifyGate 实测）——同插件依赖不可声明两次。</li>
 * </ol>
 * 故挂在本文件于 mcp-optional.xml（mcpServer 在场才加载）的 postStartupActivity：
 * {@link Class#forName} 探测 EP 接口（253 无 → 静默跳过，行为与改动前一致），命中才走
 * {@link McpGateFilterEpBridge} 的类型化注册（桥类含平台过滤类型的方法名，仅在探测通过后被触达）。</p>
 *
 * <p><b>T-Mcp261FilterContract（20261029）订正：探测从「类是否存在」升级为「契约形态」</b>。
 * 261 线自 261.22158 起有这个类、EP 名也相同，但方法契约与 262 完全不同（{@code getFilters} 而非
 * {@code applyFilters}）。旧探测只判类存在 → 按 262 写的代理被挂到 261 上 → {@code getFilters} 落兜底
 * 返回 null → 平台 {@code CollectionsKt.addAll(collection, null)} 抛 NPE（该调用不在 try/catch 内）
 * → <b>内置 MCP Server 对所有客户端 HTTP 500</b>（2026-10-29 用户报障）。现按
 * {@link McpGateFilterEpBridge#detectFilterForm} 分流：
 * <ul>
 *   <li>A（{@code applyFilters} 在场，262/263）→ 注册；</li>
 *   <li>B（{@code getFilters} 双参返回 List，261.24374+/261.25134）→ 注册；</li>
 *   <li>UNKNOWN（261.22158 的 {@code StateFlow} 形态、未来新形态）→ <b>不注册</b>，留一条带方法签名的
 *       INFO 日志（下一条版本线可直接定位），MCP 服务保持可用。</li>
 * </ul></p>
 *
 * <p><b>T-McpGlobalBreakHardening（20261118）：总开关联动 + 逃生开关 + 状态可读</b>。
 * 2026-10-29 报障里「关掉插件 MCP 总开关也无效、只能卸载插件」的根因是注册与开关无关。
 * 现在：
 * <ul>
 *   <li>{@code mcpToolsEnabled=false} 时<b>两个注册面都不挂</b>（toolset 与过滤 EP），关掉功能的用户
 *       完全不触碰平台链；设置页切回开启即重新注册（动态 EP 晚注册会触发清单重算）；</li>
 *   <li>Registry {@code sqlvis.mcp.gateFilterEp}（默认 true）是逃生开关：万一再遇契约漂移，用户可以
 *       只关这一项而不用卸载插件；</li>
 *   <li>{@link GateRegistrationState} 供设置页只读展示「门控生效 / 未识别 / 已关闭 / 无此能力 / 已熔断」。</li>
 * </ul></p>
 *
 * <p><b>线程与时点</b>：ProjectActivity 每项目启动各触发一次，
 * {@link McpGateRegistrationController#sync} 幂等；设置页 Apply 经
 * {@code PluginSettings.SETTINGS_CHANGE_TOPIC} 广播后再同步一次。</p>
 *
 * <p>移植注记：master 同文件的 {@code object McpGateFilterEpRegistrar} 按本仓拆分惯例落为独立文件
 * {@code McpGateFilterEpRegistrar.java}（AcpTransportRegistrar/AcpTransportEpRegistrar 同款）。
 * Kotlin {@code suspend fun execute} 无实际挂起点，按 {@code AcpTransportRegistrar} 先例落为
 * {@code execute(Project, Continuation)} 同步形态、返回 {@code Unit.INSTANCE}。</p>
 */
public class McpGateFilterRegistrar implements ProjectActivity {

    /** Kotlin suspend execute 的 Java 侧等价形态（无实际挂起点，直接同步执行）。 */
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        McpGateRegistrationController.sync();
        return Unit.INSTANCE;
    }
}
