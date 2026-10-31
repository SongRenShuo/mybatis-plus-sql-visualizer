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
 * <p><b>线程与时点</b>：ProjectActivity 每项目启动各触发一次，
 * {@link McpGateFilterEpRegistrar#registerOnce} 以原子闸保证全应用只注册一次；EP 层带
 * ExtensionPointListener（字节码实证），晚注册亦会触发清单重算，无需重启 IDE。</p>
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
        // T-McpDynamicToolset：十工具动态壳注册先走（探测 McpToolset 接口，253+ Marketplace 包/
        // 262 内置命中；filter EP 契约分形态，两者独立探测独立降级）。
        McpToolsetDynamicRegistrar.registerOnce();
        McpGateFilterEpRegistrar.registerOnce();
        return Unit.INSTANCE;
    }
}
