package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.acp.SignatureDistroDiscovery
import java.io.File
import kotlin.jvm.kotlin
import kotlin.reflect.full.declaredFunctions
import java.net.JarURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.JarFile
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 工具集动态壳生成器单测（T-McpDynamicToolset）：不依赖平台/EP，用假接口+假基类直测
 * [McpToolsetDynamicRegistrar.shellBytes] 的字节码生成与 [McpToolsetDynamicRegistrar.ShellClassLoader]
 * 装载——真机端到端（平台工具发现/注解扫描/suspend 契约）在 262 沙盒 runIde 批次验证。
 */
class McpToolsetShellFactoryTest {

    @Test
    fun `能力探测_接口缺席或依赖损坏时降级为不可用`() {
        URLClassLoader(emptyArray(), null).use { loader ->
            assertNull(findMcpToolsetClass(loader))
        }
        val brokenLoader = object : ClassLoader(null) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.intellij.mcpserver.McpToolset") throw NoClassDefFoundError("missing dependency")
                return super.loadClass(name, resolve)
            }
        }
        assertNull(findMcpToolsetClass(brokenLoader))
    }

    @Test
    fun `能力探测_真实本地发行物正反对照且不按版本号判断`() {
        val distros = SignatureDistroDiscovery.ideaDistros
        assumeTrue("没有本地发行物，不能冒充跨平台验证", distros.isNotEmpty())
        val checkerHome = locateArtifact(PlatformSignatureChecker::class.java)
        val productionHome = locateArtifact(
            Class.forName("cn.song.mybatisplus.visualizer.mcp.McpToolsetDynamicRegistrarKt", false, McpToolGate::class.java.classLoader),
        )
        val kotlinHome = locateArtifact(kotlin.Unit::class.java)
        var presentCount = 0
        var absentCount = 0
        for ((_, root) in distros) {
            val jars = root.resolve("plugins").listFiles().orEmpty()
                .filter { it.isDirectory && it.name.contains("mcp", ignoreCase = true) }
                .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "jar" }.toList() }
            val containsInterface = jars.any { jar ->
                JarFile(jar).use { it.getJarEntry("com/intellij/mcpserver/McpToolset.class") != null }
            }
            // 只把生产探测函数和 stdlib 交给目标 JBR，不加载整个插件，也不调用注册入口。
            // 不使用当前测试 JVM：262 的 McpToolset 为 major 69，243 的 JBR 21 无法加载。
            val java = sequenceOf(root.resolve("jbr/bin/java.exe"), root.resolve("jbr/bin/java"))
                .firstOrNull { it.isFile }
            assertTrue("发行物必须提供匹配的 JBR：$root", java != null)
            val process = ProcessBuilder(
                java!!.absolutePath, "-cp", checkerHome.absolutePath, "PlatformSignatureChecker",
                root.resolve("lib").absolutePath, "--mcp-capability-probe",
                productionHome.absolutePath, kotlinHome.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals("目标 JBR 能力探测失败：$root\n$output", 0, process.waitFor())
            val expected = if (containsInterface) "PRESENT" else "ABSENT"
            assertEquals(
                "生产探测必须对应发行物内容：$root\n$output",
                listOf("MCP_CAPABILITY=$expected"),
                output.lineSequence().filter { it.startsWith("MCP_CAPABILITY=") }.toList(),
            )
            if (containsInterface) presentCount++ else absentCount++
        }
        println("UI011 real distro capability: present=$presentCount absent=$absentCount")
        assumeTrue("本机未同时提供能力正反发行物，双向验证未完成", presentCount > 0 && absentCount > 0)
    }

    /** 沿 GeneratedProjectionCompiler 的资源候选＋字节对账策略，另支持编译输出目录。
     * PathClassLoader 的 CodeSource.location 可为空；IDE jar 连接也未必继承 JDK JarURLConnection。
     * 不猜 build/cache 路径，不凭同名文件接受候选，不将解析失败降级成测试跳过。
     */
    private fun locateArtifact(anchor: Class<*>): File {
        val entry = anchor.name.replace('.', '/') + ".class"
        val resource = requireNotNull(anchor.getResource("/$entry")) { "Missing class resource: $entry" }
        return locateArtifact(entry, resource, runCatching { anchor.protectionDomain?.codeSource?.location }.getOrNull())
    }

    private fun locateArtifact(entry: String, resource: URL, codeSource: URL?): File {
        val candidates = linkedSetOf<File>()
        fun candidate(url: URL?) {
            if (url?.protocol == "file") {
                runCatching { File(url.toURI()).canonicalFile }.getOrNull()?.let(candidates::add)
            }
        }
        candidate(codeSource)
        val connection = resource.openConnection().apply { useCaches = false }
        (connection as? JarURLConnection)?.let { candidate(it.jarFileURL) }
        if (resource.protocol == "jar") {
            // 不反射 IDE 私有连接类：标准 jar URL 文本也能定位真实 archive，包含空格的 URI 保留转义。
            candidate(URL(resource.toExternalForm().substringAfter("jar:").substringBefore("!/")))
        } else if (resource.protocol == "file") {
            var root = File(resource.toURI())
            repeat(entry.split('/').size) { root = root.parentFile ?: error("Invalid class resource: $resource") }
            candidates.add(root.canonicalFile)
        }
        val expected = connection.getInputStream().use { it.readBytes() }
        return candidates.firstOrNull { file ->
            runCatching {
                if (file.isDirectory) {
                    val classFile = file.resolve(entry)
                    classFile.isFile && classFile.readBytes().contentEquals(expected)
                } else if (file.isFile) {
                    JarFile(file).use { jar ->
                        val classEntry = jar.getJarEntry(entry) ?: return@use false
                        jar.getInputStream(classEntry).use { it.readBytes().contentEquals(expected) }
                    }
                } else false
            }.getOrDefault(false)
        } ?: error("Verified artifact unavailable: $entry; resource=$resource; candidates=$candidates")
    }

    @Test
    fun `资源定位_空CodeSource支持目录与非JDK的jar连接且拒绝字节不符`() {
        val root = Files.createTempDirectory("mpsv capability resource ").toFile()
        try {
            val entry = "sample/nested/Probe.class"
            val bytes = byteArrayOf(1, 2, 3, 4)
            val classFile = root.resolve(entry).apply { parentFile.mkdirs(); writeBytes(bytes) }
            assertEquals(root.canonicalFile, locateArtifact(entry, classFile.toURI().toURL(), null))

            val jar = root.resolve("actual artifact.jar")
            JarOutputStream(jar.outputStream()).use { output ->
                output.putNextEntry(JarEntry(entry))
                output.write(bytes)
                output.closeEntry()
            }
            fun customResource(content: ByteArray): URL = URL(
                null, "jar:${jar.toURI().toURL()}!/$entry", object : URLStreamHandler() {
                    override fun openConnection(url: URL): URLConnection = object : URLConnection(url) {
                        override fun connect() = Unit
                        override fun getInputStream() = content.inputStream()
                    }
                },
            )
            val resource = customResource(bytes)
            assertTrue("覆盖 IDE 非 JDK JarURLConnection 形态", resource.openConnection() !is JarURLConnection)
            assertEquals(jar.canonicalFile, locateArtifact(entry, resource, null))
            val mismatch = runCatching { locateArtifact(entry, customResource(byteArrayOf(9)), root.toURI().toURL()) }
            assertTrue("同名目录及 JAR 字节不符时均必须拒绝", mismatch.exceptionOrNull() is IllegalStateException)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 模拟平台 McpToolset 接口（空标记接口即可，壳 implements 的类型解析只要求可达）。 */
    interface FakeToolset

    /**
     * D239：真实工具类（带 `@McpTool` 方法）作为委托源时，壳类必须
     * ①自己声明同名方法 ②复制方法注解 ③复制 class 级 `kotlin.Metadata`。
     *
     * 这是真机「只列前 1~3 个工具」的修复点：旧零成员壳类在 kotlin-reflect 眼里
     * `declared=0 / kotlinMeta=false`，平台扫不到工具方法 → 展开失败 → 遍历中断。
     */
    @Test
    fun `带注解的工具方法生成委托并复制注解与元数据`() {
        val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.Probe" + System.nanoTime()
        val bytes = McpToolsetDynamicRegistrar.shellBytes(
            SqlVisualizerCheckSqlTool::class.java,
            FakeToolset::class.java,
            shellName,
        )
        val shell = McpToolsetDynamicRegistrar.ShellClassLoader.define(shellName, bytes)
        val declared = shell.declaredMethods.map { it.name }
        assertTrue("壳类应声明 sqlv_check_sql，实际：$declared", "sqlv_check_sql" in declared)
        val m = shell.declaredMethods.first { it.name == "sqlv_check_sql" }
        val ann = m.getAnnotation(com.intellij.mcpserver.annotations.McpTool::class.java)
        assertNotNull("委托方法应复制 @McpTool", ann)
        assertEquals("sqlv_check_sql", ann!!.name)
        // D239 三轮：必须复制 Metadata——没有它 kotlin-reflect 走 Java 视图，委托方法的
        // Continuation 会当成普通参数，平台 CallableBridge 直接判不可序列化。
        assertNotNull("壳类应复制 kotlin.Metadata（suspend 语义来源）", shell.getAnnotation(Metadata::class.java))
        // 委托目标可达：new 实例 + 调用转发方法（suspend 形态直接传 null Continuation 会 NPE，
        // 故只断言方法存在与注解正确，不做真实调用）
    }

    /**
     * D239 三轮：壳类在 kotlin-reflect 眼里必须保留 **suspend 语义**。
     *
     * 真机 2026-11-19 17:37 实证：无 `kotlin.Metadata` 时 kotlin-reflect 走 Java 视图，
     * 委托方法的 `Continuation` 被当成普通参数（`arg4`），平台 `CallableBridge.ensureSerializable`
     * 直接抛 `Type kotlin.coroutines.Continuation<...> is not serializable` → toolset 加载失败。
     * 本用例把那条真机失败条件钉在单测层。
     */
    @Test
    fun `壳类工具方法在 kotlin-reflect 里保留 suspend 语义`() {
        val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.ProbeKt" + System.nanoTime()
        val bytes = McpToolsetDynamicRegistrar.shellBytes(
            SqlVisualizerCheckSqlTool::class.java,
            FakeToolset::class.java,
            shellName,
        )
        val shell = McpToolsetDynamicRegistrar.ShellClassLoader.define(shellName, bytes)
        val fn = shell.kotlin.declaredFunctions.firstOrNull { it.name == "sqlv_check_sql" }
        assertNotNull("kotlin-reflect 应能看到壳类自己声明的工具方法", fn)
        assertTrue(
            "工具方法必须被识别为 suspend（否则 Continuation 进参数表、平台序列化失败）",
            fn!!.isSuspend,
        )
        assertTrue(
            "参数表不得含 Continuation，实际：${fn.parameters.map { it.name }}",
            fn.parameters.none { it.type.classifier == kotlin.coroutines.Continuation::class },
        )
    }

    /**
     * D239 三轮：**全部 11 个**壳类的工具方法都必须被 kotlin-reflect 认成 suspend。
     *
     * 真机 2026-11-19 17:47 实测：只有第一个 toolset 出工具，其余 10 个仍报
     * `Continuation ... of parameter 'argN' is not serializable`——说明部分壳类的
     * `kotlin.Metadata` 没能把委托方法解析成 Kotlin 函数。本用例逐个跑，失败时直接点名是哪个。
     */
    @Test
    fun `全部壳类的工具方法都保留 suspend 语义`() {
        val failures = mutableListOf<String>()
        McpToolsetDynamicRegistrar.TOOL_CLASSES.forEachIndexed { index, toolClass ->
            val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.ProbeAll${index}_" + System.nanoTime()
            val bytes = McpToolsetDynamicRegistrar.shellBytes(toolClass, FakeToolset::class.java, shellName)
            val shell = McpToolsetDynamicRegistrar.ShellClassLoader.define(shellName, bytes)
            val meta = shell.getAnnotation(Metadata::class.java)
            val fn = runCatching { shell.kotlin.declaredFunctions }.getOrElse { e ->
                failures.add("${toolClass.simpleName}: kotlin-reflect 抛 ${e.javaClass.simpleName}: ${e.message}")
                return@forEachIndexed
            }.firstOrNull { it.annotations.any { a -> a.annotationClass.simpleName == "McpTool" } }
            if (meta == null) {
                failures.add("${toolClass.simpleName}: 壳类没有 Metadata")
            } else if (fn == null) {
                failures.add(
                    "${toolClass.simpleName}: kotlin-reflect 里找不到带 @McpTool 的函数，" +
                        "declared=${runCatching { shell.kotlin.declaredFunctions.map { it.name } }.getOrNull()}",
                )
            } else if (!fn.isSuspend) {
                failures.add(
                    "${toolClass.simpleName}: ${fn.name} 未被识别为 suspend，" +
                        "参数=${fn.parameters.map { it.name }}",
                )
            }
        }
        assertTrue("以下壳类的 suspend 语义丢失：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * D239 四轮：壳类必须覆盖业务类的**全部**方法签名。
     *
     * 复制的 metadata 描述了业务类全部成员，kotlin-reflect 展开函数表时逐个按签名找 Java 方法，
     * 缺一个就抛 `KotlinReflectionInternalError: Could not compute caller for function`，
     * 整个 toolset 归零（真机 2026-11-19 18:01 实证）。本用例按签名对账。
     */
    @Test
    fun `壳类覆盖业务类全部方法签名`() {
        val problems = mutableListOf<String>()
        McpToolsetDynamicRegistrar.TOOL_CLASSES.forEachIndexed { index, toolClass ->
            val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.ProbeSig${index}_" + System.nanoTime()
            val bytes = McpToolsetDynamicRegistrar.shellBytes(toolClass, FakeToolset::class.java, shellName)
            val shell = McpToolsetDynamicRegistrar.ShellClassLoader.define(shellName, bytes)
            val shellSigs = shell.declaredMethods.mapTo(HashSet()) { signatureOf(it) }
            toolClass.declaredMethods
                .filterNot { it.isSynthetic }
                .filterNot { signatureOf(it) in shellSigs }
                .forEach { problems.add("${toolClass.simpleName}: 缺 ${signatureOf(it)}") }
        }
        assertTrue("壳类缺少业务类方法签名：\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun signatureOf(m: java.lang.reflect.Method): String =
        m.name + "(" + m.parameterTypes.joinToString(",") { it.name } + ")"

    /** 模拟工具基类：三钩子与业务方法全在此层（壳继承、不生成）。 */    open class FakeTool {
        fun isEnabled(): Boolean = true
        fun isExperimental(): Boolean = false
        fun alwaysIncluded(): Boolean = false
        fun business(): String = "ok"
    }

    @Test
    fun `壳类生成_装载_实例化与继承语义`() {
        val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.FakeTool" + System.nanoTime()
        val bytes = McpToolsetDynamicRegistrar.shellBytes(
            FakeTool::class.java,
            FakeToolset::class.java,
            shellName,
        )

        // 类版本 V17（major 61，offset 6-7；魔数 0xCAFEBABE 之后 2 字节 minor + 2 字节 major）
        val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals("壳类字节码版本应为 major 61（Java 17）", 61, major)

        val shell = McpToolsetDynamicRegistrar.ShellClassLoader.define(shellName, bytes)
        // D239：壳类父类改为 McpToolsetSupport（三钩子来源）；工具类只作委托目标，不再被继承
        assertEquals("壳 extends McpToolsetSupport（D239 形态）", McpToolsetSupport::class.java, shell.superclass)
        assertTrue(
            "壳 implements 指定接口",
            shell.interfaces.any { it.name == FakeToolset::class.java.name },
        )
        assertTrue("simpleName 应以业务类名开头（唯一后缀为防重 define），实际 ${shell.simpleName}", shell.simpleName.startsWith("FakeTool"))

        val instance = shell.getDeclaredConstructor(String::class.java).newInstance("fake_tool")
        val tool = instance as McpToolsetSupport
        assertTrue("三钩子继承可调（McpToolsetSupport 提供）", tool.isEnabled())
        assertTrue(!tool.isExperimental())
        assertTrue(!tool.alwaysIncluded())
        // FakeTool 无 @McpTool 方法 → 不生成委托；但业务方法要留占位（D239 四轮：复制的
        // metadata 描述全部成员，缺一个 kotlin-reflect 就抛 Could not compute caller）。
        // FakeTool 只声明了 business()（isEnabled 等三个钩子与基类同签名，被跳过）。
        val declared = shell.declaredMethods.map { it.name }
        assertTrue("无 @McpTool 时不应生成工具委托，实际：$declared", "sqlv_check_sql" !in declared)
        assertTrue("业务方法应有占位，实际：$declared", "business" in declared)
    }

    /** F29 反向验证探针：同 FQN 二次 define 必 LinkageError（ShellClassLoader 单例、define 不可逆）——
     *  台账（definedShells/epRegistered）防的就是这个：无台账的部分失败重试必撞此形态。 */
    @Test
    fun testF29_同FQN二次define必LinkageError() {
        val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.FakeToolF29" + System.nanoTime()
        val bytes = McpToolsetDynamicRegistrar.shellBytes(
            FakeTool::class.java,
            FakeToolset::class.java,
            shellName,
        )
        McpToolsetDynamicRegistrar.ShellClassLoader.define(shellName, bytes)
        try {
            McpToolsetDynamicRegistrar.ShellClassLoader.define(shellName, bytes)
            fail("同 FQN 二次 define 应抛 LinkageError——该形态即 F29 台账要防的重试炸点")
        } catch (expected: LinkageError) {
            // 预期
        }
    }

    /** F29 台账语义：registerToolset 经 define 缓存复用——同 FQN 两次调用不炸、Class 实例同一（无重 define）。 */
    @Test
    fun testF29_台账复用_二次调用不重define() {
        val fqn = "cn.song.mybatisplus.visualizer.mcp.gen.FakeToolLedger" + System.nanoTime()
        val iface = FakeToolset::class.java
        // 平台 EP 不存在时注册步会失败（runCatching 于 registerOnce 兜底）——本测试直接驱动 registerToolset
        // 的 define/台账面：两次调用第二次必须走缓存（assertSame 钉死无重 define，LinkageError 即红）。
        val first = runCatching { McpToolsetDynamicRegistrar.registerToolsetForTest(FakeTool::class.java, iface, fqn) }
        val cls1 = McpToolsetDynamicRegistrar.definedShells[fqn]
        assertTrue("首次调用应完成 define 入台账", cls1 != null)
        runCatching { McpToolsetDynamicRegistrar.registerToolsetForTest(FakeTool::class.java, iface, fqn) }
        val cls2 = McpToolsetDynamicRegistrar.definedShells[fqn]
        assertSame("二次调用必须复用缓存实例（重 define = LinkageError = F29 形态）", cls1, cls2)
    }

    // ---- T-McpGlobalBreakHardening（20261119）：263 新增三钩子必须由基类补齐 --------------

    /**
     * 263（IU-263.7307.200）给 `McpToolset` 加了 `isUserConfigurable` / `displayName` /
     * `displayDescription`（三个都有接口默认体，动态壳继承即可满足）。动态壳 implements 平台接口、
     * 钩子全部继承 [McpToolsetSupport]——基类不补齐时，263 真机 tools/list 里 11 个 sqlv 工具只剩
     * 注册清单第一个（20261119 实测：92 个工具仅 sqlv_render_sql 在列）。本用例钉住「钩子在基类、
     * 语义正确」，防下次接口增长再静默丢工具。
     */
    @Test
    fun `263新增三钩子在基类补齐且displayName按类名分组`() {
        val base = McpToolsetSupport::class.java
        listOf("isUserConfigurable", "displayName", "displayDescription").forEach { hook ->
            assertTrue("263 新钩子必须在 McpToolsetSupport 补齐（缺失 = 263 上工具静默消失）: $hook",
                base.methods.any { it.name == hook })
        }
        val sample = object : McpToolsetSupport("sqlv_render_sql") {}
        assertTrue("isUserConfigurable 必须 true（false 的 toolset 不进外部会话清单）", sample.isUserConfigurable())
        assertEquals(
            "displayName 按运行时类 simpleName 分组（T-McpToolNaming 与 262 category shortName 同源）",
            sample.javaClass.simpleName,
            sample.displayName(),
        )
        assertEquals("工具集描述无独立文案，给空串", "", sample.displayDescription())
        assertEquals("带兜底的重载必须透传平台 fallback", "fb", sample.displayDescription("fb"))
    }
}
