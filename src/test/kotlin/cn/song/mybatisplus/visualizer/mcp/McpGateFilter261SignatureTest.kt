package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.zip.ZipFile

/**
 * 跨版本契约探测回归：**用真实平台 jar 验 `detectFilterForm` 的判据**，而不是只信本地假接口。
 *
 * 背景（T-Mcp261FilterContract）：同一 EP `com.intellij.mcpServer.mcpToolFilterProvider` 在三条
 * 版本线上有三种接口形态，2026-10-29 的事故正是「按 262 形态写的代理挂到 261 上」。
 * [McpGateFilterFormDetectTest] 用本地假接口钉判据；本类补另一半——把 Marketplace 下载的
 * 各版本线 mcpserver 插件 jar 装进子加载器，直接对**平台自己的字节码**跑探测：
 *
 * | 版本（仓外取证产物） | 期望 |
 * |---|---|
 * | 261.17801.91 | 类不存在（探测失败即不注册＝安全） |
 * | 261.22158.220 | `UNKNOWN`（`getFilters(Implementation): StateFlow` 形态 C） |
 * | 261.24374.39 | `B_GET_LIST` |
 * | 261.25134.203（用户报障版本） | `B_GET_LIST` |
 *
 * **为什么没有 262/263**：这两条线的 mcpserver jar 是 class file major 69（Java 25），测试 JVM
 * 是 243 平台自带的 JBR 21，`Class.forName` 直接 `UnsupportedClassVersionError`（实测）。262/263
 * 的 A 形态由 [McpGateFilterFormDetectTest.FormA] 与计划 §附录 的 javap 取证覆盖。
 *
 * 取证产物在仓外（`_mcp261_probe/`，见计划 §附录），缺失时按 Assume 跳过——与项目既有的
 * 「发行版缺失则跳过对应家族」口径一致（`PlatformSignatureCrossVersionTest`）。仓内不放 jar。
 *
 * 子加载器必须 **child-first**：测试 classpath 里有 mcpStub 桩（262 形态），parent-first 会
 * 让 `Class.forName` 命中桩而不是目标 jar，测出来的是桩不是平台。
 *
 * k2j 互操作注：探测根标记改 src/main/java（100% Java 端口，src/main/kotlin 不存在——照抄
 * master 会恒 skip 成空绿）；detectFilterForm/FilterForm 为 McpGateFilterEpBridge 静态/内嵌枚举。
 */
class McpGateFilter261SignatureTest {

    private val probeRoot: File? = run {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/java").isDirectory) return@run dir.parentFile?.resolve("_mcp261_probe")
            dir = dir.parentFile ?: return@run null
        }
        null
    }

    /** 目标版本的 mcpserver jar 目录（261.25134 早期下载解包为 `mcp261/`，其余按版本号）。 */
    private fun loaderFor(versionDir: String, libDir: String = "mcpserver/lib"): ClassLoader? {
        val lib = probeRoot?.resolve(versionDir)?.resolve(libDir) ?: return null
        if (!lib.isDirectory) return null
        val urls = lib.listFiles { f -> f.extension == "jar" }?.map { it.toURI().toURL() } ?: return null
        if (urls.isEmpty()) return null
        return ChildFirstLoader(urls.toTypedArray(), javaClass.classLoader)
    }

    private fun loadIface(loader: ClassLoader): Class<*>? = runCatching {
        Class.forName("com.intellij.mcpserver.McpToolFilterProvider", false, loader)
    }.getOrNull()

    @Test
    fun `真实261点25134字节码探测为B形态`() {
        val loader = loaderFor("mcp261")
        assumeTrue("261.25134 取证产物缺失（仓外 _mcp261_probe/mcp261），跳过", loader != null)
        val iface = loadIface(loader!!)
        assertTrue("261.25134 必须有 McpToolFilterProvider", iface != null)
        assertEquals(
            "261.25134 的接口是 getFilters(Implementation, SessionOptions): List——探测必须归 B",
            McpGateFilterEpBridge.FilterForm.B_GET_LIST,
            McpGateFilterEpBridge.detectFilterForm(iface!!),
        )
    }

    @Test
    fun `真实261点24374字节码探测为B形态`() {
        val loader = loaderFor("261.24374.39")
        assumeTrue("261.24374 取证产物缺失，跳过", loader != null)
        assertEquals(
            McpGateFilterEpBridge.FilterForm.B_GET_LIST,
            McpGateFilterEpBridge.detectFilterForm(loadIface(loader!!)!!),
        )
    }

    @Test
    fun `真实261点22158字节码探测为UNKNOWN`() {
        val loader = loaderFor("261.22158.220")
        assumeTrue("261.22158 取证产物缺失，跳过", loader != null)
        assertEquals(
            "261.22158 是 getFilters(Implementation): StateFlow 形态 C——必须归 UNKNOWN（不注册）",
            McpGateFilterEpBridge.FilterForm.UNKNOWN,
            McpGateFilterEpBridge.detectFilterForm(loadIface(loader!!)!!),
        )
    }

    @Test
    fun `真实261点17801无此类即不注册`() {
        val loader = loaderFor("261.17801.91")
        assumeTrue("261.17801 取证产物缺失，跳过", loader != null)
        try {
            Class.forName("com.intellij.mcpserver.McpToolFilterProvider", false, loader!!)
            fail("261.17801 不该有 McpToolFilterProvider——探测必须失败（静默跳过）")
        } catch (_: ClassNotFoundException) {
            // 期望路径：注册器 Class.forName 返回 null 即 return
        }
    }

    // ---- T-McpGlobalBreakHardening 券05：262/263 走字节级解析（class major 69 载不进 JBR 21） ----

    @Test
    fun `解析器自证_桩类读成A形态且非suspend`() {
        val bytes = javaClass.classLoader
            .getResourceAsStream("com/intellij/mcpserver/McpToolFilterProvider.class")
            ?.use { it.readBytes() }
        assumeTrue("测试 classpath 无 mcpStub 桩类，跳过", bytes != null)
        val methods = classFileMethods(bytes!!)
        val apply = methods.filter { it.first == "applyFilters" }
        assertTrue("桩类必须有 applyFilters", apply.isNotEmpty())
        assertTrue(
            "applyFilters 必须返回 void（262 字节码实证）",
            apply.any { it.second.endsWith(")V") },
        )
        assertFalse(
            "applyFilters 不得是 suspend（否则代理返回 null 会被当成永不恢复的挂起）",
            apply.any { it.second.contains("Continuation") },
        )
        assertFalse("262 没有 getFilters", methods.any { it.first == "getFilters" })
    }

    @Test
    fun `真实262字节码为A形态且非suspend`() {
        val bytes = jarClassBytes("262.10968.63")
        assumeTrue("262 取证产物缺失，跳过", bytes != null)
        val methods = classFileMethods(bytes!!)
        val apply = methods.filter { it.first == "applyFilters" }
        assertTrue("262 必须有 applyFilters", apply.isNotEmpty())
        assertTrue("262 的 applyFilters 必须返回 void", apply.any { it.second.endsWith(")V") })
        assertFalse("262 的 applyFilters 不得是 suspend", apply.any { it.second.contains("Continuation") })
        assertTrue(
            "262 的 getUpdates 必须返回 Flow",
            methods.filter { it.first == "getUpdates" }.any { it.second.endsWith(")Lkotlinx/coroutines/flow/Flow;") },
        )
    }

    @Test
    fun `真实263字节码为A形态且非suspend`() {
        val bytes = jarClassBytes("263.7307.50")
        assumeTrue("263 取证产物缺失，跳过", bytes != null)
        val methods = classFileMethods(bytes!!)
        val apply = methods.filter { it.first == "applyFilters" }
        assertTrue("263 必须有 applyFilters", apply.isNotEmpty())
        assertTrue("263 的 applyFilters 必须返回 void", apply.any { it.second.endsWith(")V") })
        assertFalse("263 的 applyFilters 不得是 suspend", apply.any { it.second.contains("Continuation") })
    }

    /** 在取证目录里找 `mcpserver.jar` 并取出接口 class 的字节。 */
    private fun jarClassBytes(versionDir: String): ByteArray? {
        val root = probeRoot?.resolve(versionDir) ?: return null
        if (!root.isDirectory) return null
        val jar = root.walkTopDown().maxDepth(4)
            .firstOrNull { it.isFile && it.name == "mcpserver.jar" } ?: return null
        return ZipFile(jar).use { zip ->
            val entry = zip.getEntry("com/intellij/mcpserver/McpToolFilterProvider.class") ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    /**
     * 极简 class 文件解析：只取 method_info 的 name/descriptor。
     *
     * 用它而不是 `Class.forName`，是因为 262/263 的 mcpserver jar 是 class file major 69
     * （Java 25），测试 JVM（JBR 21）载不进去——这曾是旧计划「262/263 未纳入」的原因。
     */
    private fun classFileMethods(bytes: ByteArray): List<Pair<String, String>> {
        var i = 8 // magic(4) + minor(2) + major(2)
        fun u1(): Int = bytes[i++].toInt() and 0xFF
        fun u2(): Int {
            val v = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            i += 2
            return v
        }
        fun u4(): Int = (u2() shl 16) or u2()
        fun skipAttributes() {
            val count = u2()
            repeat(count) {
                i += 2
                val len = u4()
                i += len
            }
        }
        fun skipMember() {
            i += 2
            u2()
            u2()
            skipAttributes()
        }

        val cpCount = u2()
        val utf8 = HashMap<Int, String>()
        var idx = 1
        while (idx < cpCount) {
            when (val tag = u1()) {
                1 -> {
                    val len = u2()
                    utf8[idx] = String(bytes, i, len, Charsets.UTF_8)
                    i += len
                }
                7, 8, 16, 19, 20 -> i += 2
                15 -> i += 3
                3, 4, 9, 10, 11, 12, 17, 18 -> i += 4
                5, 6 -> {
                    i += 8
                    idx++
                }
                else -> error("unsupported constant pool tag $tag")
            }
            idx++
        }
        i += 6 // access_flags / this_class / super_class
        val ifaceCount = u2()
        i += 2 * ifaceCount // interfaces
        repeat(u2()) { skipMember() } // fields
        val methodCount = u2()
        val out = ArrayList<Pair<String, String>>(methodCount)
        repeat(methodCount) {
            i += 2 // access_flags
            val name = utf8[u2()] ?: "?"
            val desc = utf8[u2()] ?: "?"
            skipAttributes()
            out += name to desc
        }
        return out
    }

    /** 平台包 child-first 且**严格**：目标 jar 没有该包就抛 ClassNotFoundException，绝不落回
     *  parent——parent 上有 mcpStub 桩（262 形态），一旦回退就会把「类不存在」测成「A 形态」。 */
    private class ChildFirstLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                if (name.startsWith("com.intellij.mcpserver")) {
                    return findClass(name)
                }
                if (name.startsWith("io.modelcontextprotocol")) {
                    try {
                        return findClass(name)
                    } catch (_: ClassNotFoundException) {
                        // 落回 parent（少数 SDK 类型由测试 classpath 提供）
                    }
                }
                return super.loadClass(name, resolve)
            }
        }
    }
}
