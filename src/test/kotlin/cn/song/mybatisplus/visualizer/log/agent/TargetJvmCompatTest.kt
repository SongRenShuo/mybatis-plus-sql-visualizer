package cn.song.mybatisplus.visualizer.log.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * T-OldJvmPremainGuard 券01：目标 JVM 类版本上限探测（纯函数，零平台依赖）。
 *
 * 覆盖三种事实来源：`release` 文件（JDK 7+ 常态）、`rt.jar` 兜底（老 JDK 形态）、都拿不到（未知）。
 * 反向验证：把 [TargetJvmCompat.maxSupportedMajor] 的 rt.jar 分支摘掉 → 「只有 rt.jar」两条用例必红。
 */
class TargetJvmCompatTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDir(): File = Files.createTempDirectory("mpsv-jvm-probe").toFile().also { tempDirs += it }

    // ---------- 版本串映射 ----------

    @Test
    fun 版本串到类版本major的映射() {
        val rows = mapOf(
            "1.6.0_45" to 50,
            "1.6" to 50,
            "1.7.0_80" to 51,
            "1.8.0_181" to 52,
            "9" to 53,
            "11.0.2" to 55,
            "17.0.1" to 61,
            "25" to 69,
            "\"1.6.0_45\"" to 50,
        )
        rows.forEach { (version, expect) ->
            assertEquals("version=$version", expect, TargetJvmCompat.majorOfVersion(version))
        }
    }

    @Test
    fun 无法识别的版本串返回null() {
        listOf("", "  ", "abc", "1", "1.x", "2.1", "-1").forEach { raw ->
            assertNull("raw=[$raw]", TargetJvmCompat.majorOfVersion(raw))
        }
    }

    // ---------- release 文件 ----------

    @Test
    fun release文件给出major() {
        val jdk6 = tempDir()
        File(jdk6, "release").writeText(
            """
            IMPLEMENTOR="Zulu"
            JAVA_VERSION="1.6.0_119"
            OS_NAME="Windows"
            """.trimIndent(),
        )
        assertEquals(50, TargetJvmCompat.maxSupportedMajor(jdk6.absolutePath))

        val jdk17 = tempDir()
        File(jdk17, "release").writeText("JAVA_VERSION=\"17.0.1\"\n")
        assertEquals(61, TargetJvmCompat.maxSupportedMajor(jdk17.absolutePath))
    }

    // ---------- rt.jar 兜底 ----------

    @Test
    fun 无release时读rtjar的类版本_新布局lib() {
        val home = tempDir()
        writeRtJar(File(home, "lib/rt.jar"), major = 50)
        assertEquals(50, TargetJvmCompat.maxSupportedMajor(home.absolutePath))
    }

    @Test
    fun 无release时读rtjar的类版本_老布局jre_lib() {
        val home = tempDir()
        writeRtJar(File(home, "jre/lib/rt.jar"), major = 51)
        assertEquals(51, TargetJvmCompat.maxSupportedMajor(home.absolutePath))
    }

    /**
     * 真机实测形态：Zulu JDK 1.6.0_119 无 release 文件，自带 rt.jar 全部是 major 49（按 1.5 编译）。
     * 该值低于真实上限 50，但闸门只问「>= 52」，所以判定依旧正确——这条用例把「兜底值是下限」
     * 这个前提钉住，防止有人拿它去报具体版本号。
     */
    @Test
    fun rtjar实测形态_major49同样被判定为不支持() {
        val home = tempDir()
        writeRtJar(File(home, "jre/lib/rt.jar"), major = 49)
        assertEquals(49, TargetJvmCompat.maxSupportedMajor(home.absolutePath))
        assertFalse("49 < 52，闸门应判不支持", PremainInjection.targetJvmSupported(49))
    }

    // ---------- 未知 ----------

    @Test
    fun 空目录与不存在的路径都返回null() {
        val empty = tempDir()
        assertNull("空目录应判未知", TargetJvmCompat.maxSupportedMajor(empty.absolutePath))
        assertNull("不存在的路径应判未知", TargetJvmCompat.maxSupportedMajor(File(empty, "nope").absolutePath))
        assertNull("空串应判未知", TargetJvmCompat.maxSupportedMajor(""))
        assertNull("null 应判未知", TargetJvmCompat.maxSupportedMajor(null))
    }

    @Test
    fun 缓存对同一路径返回同一结果() {
        val home = tempDir()
        File(home, "release").writeText("JAVA_VERSION=\"1.8.0_181\"\n")
        assertEquals(52, TargetJvmCompat.maxSupportedMajor(home.absolutePath))
        // 删掉事实来源后仍取缓存值（同一 Run 会话内不反复读盘）
        File(home, "release").delete()
        assertEquals(52, TargetJvmCompat.maxSupportedMajor(home.absolutePath))
    }

    /** 造一个只含一个 class 条目的 rt.jar（major 决定「该 JVM 的上限」）。 */
    private fun writeRtJar(target: File, major: Int) {
        target.parentFile.mkdirs()
        val header = byteArrayOf(
            0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
            0x00, 0x00, (major shr 8).toByte(), (major and 0xFF).toByte(),
        )
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("java/lang/Object.class"))
            zip.write(header)
            zip.closeEntry()
        }
    }
}
