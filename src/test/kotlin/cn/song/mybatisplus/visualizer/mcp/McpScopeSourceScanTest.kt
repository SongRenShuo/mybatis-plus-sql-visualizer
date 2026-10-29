package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * mcpserver 引用作用域守卫（T-McpToolset，verifyPlugin 三目标门禁的配套护栏）。
 *
 * 背景：`com.intellij.mcpserver.*` 来自平台可选插件（mcp-optional.xml 仅在 com.intellij.mcpServer
 * 存在时加载，253+/262 内置；243 明确不支持）。verifyPlugin 对 243 目标按外部前缀豁免该包缺类
 * （build.gradle.kts verifyPlugin.externalPrefixes，理由=optional 文件不加载则类永不可达）。
 * 该豁免成立的前提是 **mcpserver 引用严格限定在 mcp 适配包内**——若未来任何非 mcp 代码
 * （如面板/服务层）直接引用 mcpserver 类型，243 上就会真正走到缺类路径，豁免即从「诚实」
 * 变成「掩盖」。本守卫把作用域钉死：
 *  1. src/main/kotlin 中出现 `com.intellij.mcpserver` 的文件必须位于 `visualizer/mcp/` 包；
 *  2. META-INF 下引用 mcpserver 的描述文件只允许 mcp-optional.xml（plugin.xml 主文件零引用，
 *     主文件引用会让 243 在解析阶段就尝试加载适配类）。
 * 扫描前剥离注释（BannedApiSourceScanTest 同款，注释里的历史记录不产生误报/漏报影响）。
 */
class McpScopeSourceScanTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 剥离 block 注释与行注释（与 BannedApiSourceScanTest 同款）。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    /**
     * 剥离字符串字面量（T-McpToolset2 收口 fe11c97 合并回归）：设置页跳转经
     * `Class.forName("com.intellij.mcpserver.settings.McpServerSettingsConfigurable")`
     * 运行时反射定位平台 Configurable（McpConfigurable/PluginSettingsPanel，T-ShortcutSettings
     * 设计如此）——字符串引用不是编译期类型引用（不会让 243 编译期解析缺类），未装环境
     * 已走「不可用提示」兜底；守卫关心的是 import/类型位置的真引用，故字符串一律豁免。
     */
    private fun stripStrings(source: String): String =
        source.replace(Regex("\"[^\"]*\""), "")

    @Test
    fun testMcpServerReferencesConfinedToMcpPackage() {
        val offenders = repoRoot().resolve("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { stripStrings(stripComments(it.readText())).contains("com.intellij.mcpserver") }
            .filter { !(it.path.replace('\\', '/')).contains("/visualizer/mcp/") }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "src/main/kotlin 出现 mcp 包之外的 com.intellij.mcpserver 引用：$offenders。" +
                "mcpserver 类型仅允许在 mcp/ 适配包内出现（243 目标经 externalPrefixes 豁免的前提" +
                "=引用永不可达，出包即破坏该前提，见 McpScopeSourceScanTest KDoc）",
            offenders.isEmpty(),
        )
    }

    @Test
    fun testMcpCoreApiReferencesAreAnnotationOrStringOnly() {
        // T-McpDynamicToolset（20260926 审核拒绝轮）：MCP 核心 API（McpToolset/McpExpectedError/
        // McpCallInfoKt）在 243/251 的 mcpServer 旧形态中不存在——任何编译期类型引用都会成为
        // verifier compatibility problems（拒绝主因）。核心 API 只允许以 Class.forName 字符串
        // （stripStrings 剥除）或 annotations 子包（@McpTool 等，243/251 实证可解析）存在。
        val offenders = repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/mcp")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to stripStrings(stripComments(it.readText())) }
            .filter { (_, text) -> coreApiRefMarker.containsMatchIn(text) }
            .map { (name, _) -> name }
            .toList()
        assertTrue(
            "mcp 包内出现核心 API 编译期引用：$offenders。只允许 annotations 子包注解与" +
                "字符串反射（McpExpectedBridge/McpToolsetDynamicRegistrar 模式）——真类型引用会" +
                "重新引入 243/251 兼容问题（见 dev-support/verifier-marketplace-baseline.txt）",
            offenders.isEmpty(),
        )
    }

    /** 核心 API 真引用标记：annotations 子包之外的 com.intellij.mcpserver.* 类型引用。 */
    private val coreApiRefMarker = Regex("""com\.intellij\.mcpserver\.(?!annotations)""")

    @Test
    fun testMcpStubCarriesNo261OnlyFilterTypes() {
        // T-Mcp261FilterContract 券07：261 形态 B 的 McpToolFilter / McpToolFilterModification
        // 只允许以字符串 FQN 出现在 main（Class.forName），**不得**做成 mcpStub 桩类型再被 import——
        // 桩产物不进 jar，但 main 一旦 import，字节码里就留下真引用，正是 20260923 审核拒绝的形态。
        // 注意：McpToolFilterContext / McpToolState 属 262 形态 A 的合法桩（applyFilters 签名需要），
        // 故只禁两个 261-only 类型（`\b` 保证不会误伤 McpToolFilterContext）。
        val offenders = repoRoot().resolve("src/mcpStub/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { stub261OnlyFilterType.containsMatchIn(stripStrings(stripComments(it.readText()))) }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') }
            .toList()
        assertTrue(
            "mcpStub 出现 261-only 过滤器类型桩：$offenders。B 形态必须全反射（字符串 FQN），" +
                "加桩并被 main import 会让 jar 留下 com.intellij.mcpserver.* 真引用（见券07 红线）",
            offenders.isEmpty(),
        )
    }

    private val stub261OnlyFilterType =
        Regex("""(interface|class)\s+McpToolFilter\b|(interface|class)\s+McpToolFilterModification\b""")

    @Test
    fun testNoOrgObjectwebAsmReferenceInMcpPackage() {
        // T-McpDynamicToolset 真机 ASM 不可达修复（20261005）：org.objectweb.asm 是平台已知包
        // （262 lib 自带 intellij.libraries.asm.jar），插件 classloader 对该包 parent-first 接管、
        // 自带 asm jar 不可达 → 真机 NoClassDefFoundError（日常 IDE 262.10968 六轮日志实锤）。
        // mcp 包绝不允许 reintroduce org.objectweb.asm 编译期引用——壳类字节码必须走
        // TinyClassFileWriter 手写（零外部依赖）。字符串字面量（帧过滤名单等）经 stripStrings
        // 豁免，只盯 import/类型位置真引用。
        val offenders = repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/mcp")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to stripStrings(stripComments(it.readText())) }
            .filter { (_, text) -> text.contains("org.objectweb.asm") }
            .map { (name, _) -> name }
            .toList()
        assertTrue(
            "mcp 包出现 org.objectweb.asm 编译期引用：$offenders。平台 262 自带 asm 库→插件" +
                "classloader 对该包 parent-first 接管、自带 asm jar 不可达（真机 CNFE），壳类生成" +
                "必须走 TinyClassFileWriter 手写字节码（见 20261005-1335 开发计划）",
            offenders.isEmpty(),
        )
    }

    @Test
    fun testMcpServerPluginXmlReferencesOnlyInOptionalFile() {
        val offenders = repoRoot().resolve("src/main/resources/META-INF").walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.name != "mcp-optional.xml" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to stripXmlComments(it.readText()) }
            .filter { (_, text) ->
                // 禁两类：适配类名直接出现；小写包名 com.intellij.mcpserver（类包名，非插件 ID——
                // 插件 ID 是 com.intellij.mcpServer 大写 S，optional <depends> 声明合法且必需）。
                // T-McpToolNaming：toolset 类名统一 `SqlVisualizer` 前缀（T-MTN-01），标记同步扩为
                // 「SqlVisualizer + 以 Tool 结尾」形态，并保留一期门面类名（历史留档，防旧引用回流）——
                // 旧标记只有 SqlVisualizerToolset 一字面量，改名后若只留旧标记，真引用会整片漏网。
                mcpAdapterClassMarkers.containsMatchIn(text) || text.contains("com.intellij.mcpserver")
            }
            .map { (path, _) -> path }
            .toList()
        assertTrue(
            "META-INF 描述文件在 mcp-optional.xml 之外出现 mcpserver 类引用：$offenders。" +
                "主 plugin.xml 引用 mcpserver 类型会让 243 在解析阶段尝试加载缺类（可选依赖必须" +
                "整体留在 mcp-optional.xml，未装 MCP Server 的环境整文件不加载；<depends> 插件 ID " +
                "声明 com.intellij.mcpServer 为合法机制不在此列）",
            offenders.isEmpty(),
        )
    }

    /** 适配类名标记（T-McpToolNaming）：十 toolset 类统一 `SqlVisualizer<Xxx>Tool` 命名，
     *  故「SqlVisualizer + 以 Tool 结尾」即覆盖全部当前与未来 toolset 类；一期门面类名
     *  `SqlVisualizerToolset` 保留为附加标记（历史形态，防旧引用回流）。 */
    private val mcpAdapterClassMarkers = Regex("SqlVisualizer\\w*Tool\\b|SqlVisualizerToolset")

    /** XML 注释剥离（`<!-- -->`）+ 行注释兜底——plugin.xml 的 depends 注释提到实现类名属文档性
     *  记载，不构成解析期类引用；与 BannedApiSourceScanTest.stripComments 同理只可能漏报不误报。 */
    private fun stripXmlComments(source: String): String =
        source
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
}
