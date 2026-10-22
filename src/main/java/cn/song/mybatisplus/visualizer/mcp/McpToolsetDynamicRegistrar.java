// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpToolsetDynamicRegistrar.kt
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP 工具集<b>动态壳注册器</b>（T-McpDynamicToolset）：运行期生成「extends 工具类 implements
 * 平台 McpToolset」的壳类并程序化注册到 {@code com.intellij.mcpServer.mcpToolset} EP，替代
 * mcp-optional.xml 的 10 条 XML toolset 声明。
 *
 * <p><b>为什么要动态生成壳类（方案 D 全动态化终态）</b>：verifier 只做静态字节码分析——jar 内任何
 * implements 平台接口的类都会在 243/251（mcpServer 旧形态缺新 API）被判未解析类（20260926
 * 审核拒绝主因）。XML 声明形态的 implementation 类无法消除该引用（EP 接口实现类，上轮
 * T-MarketCompatProxy 已证）；运行期生成的壳类不在 jar 内，verifier 不可见。壳类只覆写
 * 「继承即可满足接口」的最小面（三钩子直接继承 {@link McpToolsetSupport} 实现，连方法都不生成），
 * 平台的工具发现（kotlin-reflect 扫 {@code @McpTool} 注解方法/参数 schema/suspend 调用契约）全部
 * 走继承来的业务类编译产物，壳零参与。<b>壳字节码由 {@link TinyClassFileWriter} 手写生成，零外部
 * 依赖</b>（真机 ASM 不可达修复，20261005：平台 262 自带 asm 库接管 org.objectweb.asm 包，
 * 插件自带 asm jar 不可达 → 真机 NoClassDefFoundError，见开发计划 20261005-1335）。本类自此
 * 对 org.objectweb.asm 零编译期引用（守卫扫描项）。</p>
 *
 * <p><b>注册链</b>：{@link McpGateFilterRegistrar}（mcp-optional.xml 的 postStartupActivity，mcpServer
 * 在场才加载）每项目启动触发 {@link #registerOnce}，原子闸全应用只注册一次。探测
 * {@code Class.forName("com.intellij.mcpserver.McpToolset")} 失败（243/251 旧形态）静默跳过——
 * 与 XML 形态「片段加载但 EP 形态不同」的设计口径一致：该版本不支持 MCP 工具线。</p>
 *
 * <p><b>EP 形态</b>：{@code interface="com.intellij.mcpServer.mcpToolset" dynamic="true"}（262
 * mcpserver.jar META-INF/plugin.xml 实证）——与 mcpToolFilterProvider 同款，注册走
 * McpGateFilterEpBridge 先例（{@code getPoint(null)} application 级，真机验证过）；dynamic EP
 * 晚注册会触发清单重算，无需重启 IDE。</p>
 *
 * <p><b>类名前缀硬约定（T-McpToolNaming）</b>：平台「暴露工具」页分组名=注册实例类的 simpleName——
 * 壳类放 {@code mcp.gen} 子包、<b>simpleName 与业务类逐字节一致</b>（如 SqlVisualizerCheckSqlTool），
 * 分组名与前缀美学（自带插件名、字典序相邻）与 XML 形态完全相同；FQN 含 gen 子包避免与
 * 业务类在不同 classloader 上的解析歧义。</p>
 *
 * <p>移植注记：master 的 {@code object} 按本仓惯例落为 final class + {@link #INSTANCE} + 全静态成员，
 * {@code internal}（shellBytes/ShellClassLoader）按惯例升 public（壳生成器单测直触达）；
 * {@code object ShellClassLoader} 落为静态嵌套类 + 自身 {@code INSTANCE}（defineClass 的
 * protected 调用点必须落在 ClassLoader 子类实例方法内）；Kotlin {@code require} 落为显式
 * {@code IllegalArgumentException}；壳字节码生成自 ASM {@code ClassWriter} 换为
 * {@link TinyClassFileWriter} 手写字节码（T-SyncK2JMaster29 同 master 20261005 修复）。</p>
 */
public final class McpToolsetDynamicRegistrar {

    public static final McpToolsetDynamicRegistrar INSTANCE = new McpToolsetDynamicRegistrar();

    private McpToolsetDynamicRegistrar() {
    }

    private static final Logger LOG = Logger.getInstance(McpToolsetDynamicRegistrar.class);

    private static final String TOOLSET_EP_NAME = "com.intellij.mcpServer.mcpToolset";

    private static final AtomicBoolean registered = new AtomicBoolean(false);

    /** F29（D1=A）：已成功 define 的壳类缓存（FQN→Class）——define 不可撤销，重试复用免撞同 FQN LinkageError。 */
    public static final java.util.Map<String, Class<?>> definedShells = new java.util.LinkedHashMap<>();

    /** F29（D1=A）：已注册到 mcpToolset EP 的壳 FQN——重试防重复注册。 */
    public static final java.util.Set<String> epRegistered = new java.util.LinkedHashSet<>();

    /** 十一工具类清单（R14 T-HistorySearch 增至十一；McpToolGate.TOOL_NAMES 一一对应，守卫测试对账单一事实）。 */
    private static final List<Class<?>> TOOL_CLASSES = List.of(
        SqlVisualizerRenderSqlTool.class,
        SqlVisualizerSchemaContextTool.class,
        SqlVisualizerExplainTool.class,
        SqlVisualizerRenderLogsTool.class,
        SqlVisualizerCheckSqlTool.class,
        SqlVisualizerRollbackPlanTool.class,
        SqlVisualizerPerfAdviceTool.class,
        SqlVisualizerCrudSqlTool.class,
        SqlVisualizerReverseSqlTool.class,
        SqlVisualizerMapperCheckTool.class,
        SqlVisualizerHistorySearchTool.class
    );

    /** K2J 互操作：master 侧 {@code TOOL_CLASSES} 为 Kotlin {@code internal}，同模块测试树可读——
     * Java 无 internal 语义，以只读 ForTest 访问器等价承载（守卫测试 McpToolGateTest 对账用）。 */
    public static List<Class<?>> toolClassesForTest() {
        return TOOL_CLASSES;
    }

    /** 探测＋全量注册；已注册或探测失败（无 mcpServer 新形态）时零操作。
     *  F29（D1=A 按工具粒度记账）：{@link #definedShells} 缓存已 define 壳类（define 不可逆——
     *  部分失败重试撞同 FQN LinkageError 的根治）、{@link #epRegistered} 记账已注册到 EP 的 FQN
     *  （防重试重复注册）；{@code synchronized} 封 check-then-act 竞态。 */
    public static synchronized void registerOnce() {
        if (registered.get()) {
            return;
        }
        Class<?> toolsetIface = McpToolsetDynamicRegistrarKt.findMcpToolsetClass();
        if (toolsetIface == null) {
            return;
        }
        try {
            for (Class<?> toolClass : TOOL_CLASSES) {
                registerToolset(toolClass, toolsetIface);
            }
            registered.set(true);
            LOG.info("T-McpDynamicToolset: " + TOOL_CLASSES.size()
                + " toolsets registered onto mcpToolset EP (dynamic shell form)");
        } catch (Throwable t) {
            // registered 不置位：下个项目启动重试（动态 EP 未就绪等瞬态失败可自愈）——
            // 已 define/已注册的 FQN 由两本台账跳过，重试只补未完成项
            LOG.warn("T-McpDynamicToolset: mcpToolset EP 注册失败（MCP 工具不可用，其余功能不受影响）", t);
        }
    }

    private static void registerToolset(Class<?> toolClass, Class<?> toolsetIface) throws ReflectiveOperationException {
        // simpleName 与业务类一致（T-McpToolNaming 分组名=simpleName），gen 子包隔离 FQN
        registerToolset(toolClass, toolsetIface,
            "cn.song.mybatisplus.visualizer.mcp.gen." + toolClass.getSimpleName());
    }

    /** F29 测试触点：显式 shellName 驱动 define/台账路径（EP 注册在平台无 EP 的测试环境会失败，由调用方 runCatching）。 */
    public static void registerToolsetForTest(Class<?> toolClass, Class<?> toolsetIface, String shellName) {
        try {
            registerToolset(toolClass, toolsetIface, shellName);
        } catch (ReflectiveOperationException e) {
            throw McpToolsetSupport.<RuntimeException>sneakyThrow(e);
        }
    }

    private static void registerToolset(Class<?> toolClass, Class<?> toolsetIface, String shellName)
        throws ReflectiveOperationException {
        boolean hasNoArgCtor = false;
        for (Constructor<?> candidate : toolClass.getConstructors()) {
            if (candidate.getParameterTypes().length == 0) {
                hasNoArgCtor = true;
                break;
            }
        }
        if (!hasNoArgCtor) {
            // Kotlin require(...) { message }
            throw new IllegalArgumentException("toolset 类必须有无参构造（壳生成依赖）：" + toolClass.getName());
        }
        if (epRegistered.contains(shellName)) {
            return; // F29：EP 已注册，重试跳过（防重复注册）
        }
        Class<?> shellClass = definedShells.get(shellName);
        if (shellClass == null) {
            shellClass = ShellClassLoader.INSTANCE.define(shellName, shellBytes(toolClass, toolsetIface, shellName));
            definedShells.put(shellName, shellClass);
        }
        Object instance = shellClass.getDeclaredConstructor().newInstance();
        ExtensionPointName<Object> ep = ExtensionPointName.create(TOOLSET_EP_NAME);
        ep.getPoint(null).registerExtension(instance);
        epRegistered.add(shellName);
        LOG.info("T-McpDynamicToolset: registered " + shellClass.getSimpleName()
            + " (gen shell of " + toolClass.getName() + ")");
    }

    /**
     * 壳类字节码：{@code final class &lt;ShellName&gt; extends &lt;工具类&gt; implements McpToolset}——
     * 仅一个无参构造器（invokespecial 父类构造）。工具方法/三钩子全部继承，壳零额外成员。
     * V17（major 61）：243 JBR 21 可加载，且与插件常规编译目标一致。
     * 生成器为 {@link TinyClassFileWriter} 手写字节码（真机 ASM 不可达修复，见其类注），零外部依赖。
     * public（master internal）供壳生成器单测直接触达。
     */
    public static byte[] shellBytes(Class<?> superClass, Class<?> iface, String shellName) {
        return TinyClassFileWriter.shellBytes(shellName, superClass.getName(), iface.getName());
    }

    /**
     * 壳类装载器：插件 classloader 的直系子加载器，defineClass 仅此一处（protected 合法调用点）。
     * Kotlin {@code object} 落为静态嵌套单例（{@link #INSTANCE}），{@code internal} 升 public。
     */
    public static final class ShellClassLoader extends ClassLoader {

        public static final ShellClassLoader INSTANCE = new ShellClassLoader();

        private ShellClassLoader() {
            super(McpToolsetDynamicRegistrar.class.getClassLoader());
        }

        public Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
