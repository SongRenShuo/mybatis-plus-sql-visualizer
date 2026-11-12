package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 归属与布局校验单测（T-LocalUpdateDir M2，计划 T2b）：这个通道只能装本插件。
 *
 * 反向验证纪律（计划 §八 反向 1/2）：G1 与 G2 的夹具**刻意分离**——「id 不符但布局干净」与
 * 「id 匹配但夹带目录」是两个独立夹具，摘掉任一道闸门时另一个用例仍各自独立变红，
 * 避免两道闸门互相遮蔽出假绿。真实 buildPlugin 产物顶层只有插件目录一个条目（P-9 实证），
 * 严格判定不误杀真包。
 */
class PluginPackageOwnershipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ourId = UpdateSource.PLUGIN_ID

    /** 带 since-build 的描述符（真实 plugin.xml 形态）。 */
    private fun pluginXml(
        id: String = ourId,
        version: String = "2026.11.12",
        sinceBuild: String? = "243.*",
    ): String = """
        <idea-plugin>
            <id>$id</id>
            <name>SQL Visualizer</name>
            <version>$version</version>
            ${if (sinceBuild != null) """<idea-version since-build="$sinceBuild"/>""" else ""}
        </idea-plugin>
    """.trimIndent()

    /** IPGP buildPlugin 布局：顶层 [topDir]/lib/插件 jar（plugin.xml 在 jar 内）+ 可选杂项。 */
    private fun buildIpgpZip(
        target: Path,
        xml: String = pluginXml(),
        topDir: String = UpdateSource.INSTALL_DIR_NAME,
        extraTopEntries: List<String> = emptyList(),
        extraInnerJars: List<Pair<String, String>> = emptyList(), // (相对路径, 内嵌 plugin.xml) 夹带其他插件
    ) {
        ZipOutputStream(target.toFile().outputStream().buffered()).use { zip ->
            val jarBytes = innerJarBytes(xml)
            zip.putNextEntry(ZipEntry("$topDir/lib/mybatis-plus-sql-visualizer-test.jar"))
            zip.write(jarBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$topDir/lib/jsqlparser-5.3.jar"))
            zip.write(ByteArray(0))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$topDir/LICENSE"))
            zip.write("license".toByteArray())
            zip.closeEntry()
            for (rel in extraTopEntries) {
                zip.putNextEntry(ZipEntry(rel))
                zip.write(ByteArray(0))
                zip.closeEntry()
            }
            for ((rel, foreignXml) in extraInnerJars) {
                zip.putNextEntry(ZipEntry(rel))
                zip.write(innerJarBytes(foreignXml))
                zip.closeEntry()
            }
        }
    }

    private fun innerJarBytes(xml: String): ByteArray =
        java.io.ByteArrayOutputStream().also { jarOut ->
            ZipOutputStream(jarOut).use { jar ->
                jar.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                jar.write(xml.toByteArray())
                jar.closeEntry()
            }
        }.toByteArray()

    // ---------- G1+G2 全过：标准与平坦布局 ----------

    @Test
    fun verify_standardIpgpLayout_isOurs() {
        val zip = tmp.root.toPath().resolve("ok.zip")
        buildIpgpZip(zip)
        val result = PluginPackageOwnership.verify(zip)
        assertTrue("标准布局应为 Ours：$result", result is Ownership.Ours)
        val ours = result as Ownership.Ours
        assertEquals("2026.11.12", ours.version)
        assertEquals(243, ours.sinceBuildBaseline)
        assertEquals(UpdateSource.INSTALL_DIR_NAME, ours.topLevelDir)
    }

    @Test
    fun verify_directDescriptorLayout_isOurs() {
        // 平坦布局：META-INF/plugin.xml 直属 zip 根（计划 §3.3.1 允许的唯一例外）
        val zip = tmp.root.toPath().resolve("direct.zip")
        ZipOutputStream(zip.toFile().outputStream().buffered()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("META-INF/plugin.xml"))
            zipOut.write(pluginXml(version = "2026.11.13").toByteArray())
            zipOut.closeEntry()
        }
        val result = PluginPackageOwnership.verify(zip)
        assertTrue("直属布局应为 Ours：$result", result is Ownership.Ours)
        assertEquals("2026.11.13", (result as Ownership.Ours).version)
    }

    @Test
    fun verify_noSinceBuild_sinceBuildNull() {
        val zip = tmp.root.toPath().resolve("nosb.zip")
        buildIpgpZip(zip, xml = pluginXml(sinceBuild = null))
        val result = PluginPackageOwnership.verify(zip)
        assertTrue(result is Ownership.Ours)
        assertNull((result as Ownership.Ours).sinceBuildBaseline)
    }

    // ---------- G1：不是本插件 ----------

    @Test
    fun verify_foreignId_notOurs_layoutClean() {
        // 反向夹具（G1 专用）：id 不符 + 布局干净——摘掉 G2 时本用例仍应独立由 G1 拦下
        val zip = tmp.root.toPath().resolve("foreign.zip")
        buildIpgpZip(zip, xml = pluginXml(id = "com.example.other-plugin"))
        val result = PluginPackageOwnership.verify(zip)
        assertTrue("别的插件应 NotOurs：$result", result is Ownership.NotOurs)
        assertTrue((result as Ownership.NotOurs).reason.contains("com.example.other-plugin"))
    }

    @Test
    fun verify_corruptFile_notOurs() {
        val zip = tmp.root.toPath().resolve("corrupt.zip")
        java.nio.file.Files.write(zip, "not a zip".toByteArray())
        assertTrue(PluginPackageOwnership.verify(zip) is Ownership.NotOurs)
    }

    @Test
    fun verify_missingDescriptor_notOurs() {
        val zip = tmp.root.toPath().resolve("noxml.zip")
        ZipOutputStream(zip.toFile().outputStream().buffered()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("${UpdateSource.INSTALL_DIR_NAME}/lib/random.jar"))
            zipOut.write(ByteArray(0))
            zipOut.closeEntry()
        }
        assertTrue(PluginPackageOwnership.verify(zip) is Ownership.NotOurs)
    }

    // ---------- G2：id 是本插件但包不可信 ----------

    @Test
    fun verify_smuggledSecondPlugin_tainted() {
        // 反向夹具（G2 专用）：id 匹配 + 夹带第二个插件目录——摘掉 G2 时本用例应独立变红
        val zip = tmp.root.toPath().resolve("smuggled.zip")
        buildIpgpZip(
            zip,
            extraInnerJars = listOf(
                "evil-plugin/lib/evil.jar" to pluginXml(id = "com.evil.plugin", version = "1.0"),
            ),
        )
        val result = PluginPackageOwnership.verify(zip)
        assertTrue("夹带插件应 Tainted：$result", result is Ownership.Tainted)
        assertTrue((result as Ownership.Tainted).reason.contains("多个顶层目录"))
    }

    @Test
    fun verify_wrongTopDirName_tainted() {
        // id 对但顶层目录名不是本插件目录：重启路径会装成别的名字/覆盖别的目录
        val zip = tmp.root.toPath().resolve("wrongname.zip")
        buildIpgpZip(zip, topDir = "evil-plugin")
        val result = PluginPackageOwnership.verify(zip)
        assertTrue(result is Ownership.Tainted)
        assertTrue((result as Ownership.Tainted).reason.contains("evil-plugin"))
    }

    @Test
    fun verify_topDirNameCaseVariant_tainted() {
        // 大小写变体：Windows 解包会互相覆盖，zip 条目大小写敏感——拦
        val zip = tmp.root.toPath().resolve("case.zip")
        buildIpgpZip(zip, topDir = UpdateSource.INSTALL_DIR_NAME.uppercase())
        val result = PluginPackageOwnership.verify(zip)
        assertTrue("大小写变体应 Tainted：$result", result is Ownership.Tainted)
    }

    @Test
    fun verify_strayTopLevelFile_tainted() {
        // 插件目录之外多出一个顶层文件：真实产物没有（P-9 实证），出现即不可信
        val zip = tmp.root.toPath().resolve("stray.zip")
        buildIpgpZip(zip, extraTopEntries = listOf("readme.txt"))
        val result = PluginPackageOwnership.verify(zip)
        assertTrue("顶层外来文件应 Tainted：$result", result is Ownership.Tainted)
        assertTrue((result as Ownership.Tainted).reason.contains("readme.txt"))
    }

    // ---------- since-build 解析与 D-5 判定（纯函数） ----------

    @Test
    fun parseSinceBuildBaseline_variants() {
        assertEquals(243, PluginPackageOwnership.parseSinceBuildBaseline("""<idea-version since-build="243.*"/>"""))
        assertEquals(253, PluginPackageOwnership.parseSinceBuildBaseline("""<idea-version since-build="253"/>"""))
        assertEquals(
            263,
            PluginPackageOwnership.parseSinceBuildBaseline(
                """<idea-version since-build="263.*" until-build=""/>""",
            ),
        )
        assertNull(PluginPackageOwnership.parseSinceBuildBaseline("""<idea-plugin/>"""))
        assertNull(PluginPackageOwnership.parseSinceBuildBaseline("""<idea-version since-build="abc"/>"""))
    }

    @Test
    fun isSinceBuildIncompatible_matrix() {
        // (包要求, 当前 baseline)：包要求更高 → 不兼容；任一侧未知 → 不拦（D-5 口径）
        assertTrue(PluginPackageOwnership.isSinceBuildIncompatible(253, 243))
        assertTrue(PluginPackageOwnership.isSinceBuildIncompatible(244, 243))
        assertEquals(false, PluginPackageOwnership.isSinceBuildIncompatible(243, 253))
        assertEquals(false, PluginPackageOwnership.isSinceBuildIncompatible(243, 243))
        assertEquals(false, PluginPackageOwnership.isSinceBuildIncompatible(null, 243))
        assertEquals(false, PluginPackageOwnership.isSinceBuildIncompatible(253, null))
    }
}
