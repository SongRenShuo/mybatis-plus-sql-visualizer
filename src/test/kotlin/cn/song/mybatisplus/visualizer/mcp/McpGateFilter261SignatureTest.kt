package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URL
import java.net.URLClassLoader

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
 */
class McpGateFilter261SignatureTest {

    private val probeRoot: File? = run {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return@run dir.parentFile?.resolve("_mcp261_probe")
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
            FilterForm.B_GET_LIST,
            detectFilterForm(iface!!),
        )
    }

    @Test
    fun `真实261点24374字节码探测为B形态`() {
        val loader = loaderFor("261.24374.39")
        assumeTrue("261.24374 取证产物缺失，跳过", loader != null)
        assertEquals(FilterForm.B_GET_LIST, detectFilterForm(loadIface(loader!!)!!))
    }

    @Test
    fun `真实261点22158字节码探测为UNKNOWN`() {
        val loader = loaderFor("261.22158.220")
        assumeTrue("261.22158 取证产物缺失，跳过", loader != null)
        assertEquals(
            "261.22158 是 getFilters(Implementation): StateFlow 形态 C——必须归 UNKNOWN（不注册）",
            FilterForm.UNKNOWN,
            detectFilterForm(loadIface(loader!!)!!),
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
