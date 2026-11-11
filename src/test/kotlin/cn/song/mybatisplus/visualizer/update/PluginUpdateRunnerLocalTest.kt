package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 本地包安装编排单测（T-LocalUpdateDir M3，计划 T3）：installFromLocal 全分支——
 * 归属/布局/兼容性三闸门都拦在安装之前（install seam 零调用）、复制失败、坏包、
 * 守卫拒绝、三态透传、**源包不动**、临时副本清理。
 *
 * 纯 JUnit（不触平台安装与 EDT 编排，install/currentIdeBaseline 走 seam 注入）。
 */
class PluginUpdateRunnerLocalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ourId = UpdateSource.PLUGIN_ID
    private val currentBaseline = 243

    private fun pluginXml(version: String, id: String = ourId, sinceBuild: String? = "243.*"): String = """
        <idea-plugin>
            <id>$id</id>
            <version>$version</version>
            ${if (sinceBuild != null) """<idea-version since-build="$sinceBuild"/>""" else ""}
        </idea-plugin>
    """.trimIndent()

    private fun writeIpgpZip(target: Path, xml: String, extraEntries: Int = 0): Path {
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            val jarBytes = java.io.ByteArrayOutputStream().also { jarOut ->
                ZipOutputStream(jarOut).use { jar ->
                    jar.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                    jar.write(xml.toByteArray())
                    jar.closeEntry()
                }
            }.toByteArray()
            zip.putNextEntry(ZipEntry("${UpdateSource.INSTALL_DIR_NAME}/lib/plugin-test.jar"))
            zip.write(jarBytes)
            zip.closeEntry()
            repeat(extraEntries) { i ->
                zip.putNextEntry(ZipEntry("${UpdateSource.INSTALL_DIR_NAME}/lib/filler-$i.txt"))
                zip.write(ByteArray(0))
                zip.closeEntry()
            }
        }
        return target
    }

    /** 记录型 install seam：捕获收到的临时路径与调用次数。 */
    private class RecordingInstall : (Path) -> PluginUpdateRunner.InstallResult {
        val calls = mutableListOf<Path>()
        override fun invoke(p: Path): PluginUpdateRunner.InstallResult {
            calls.add(p)
            return PluginUpdateRunner.InstallResult.InstalledDynamic("2026.11.12")
        }
    }

    private fun localPkg(zip: Path, version: String = "2026.11.12", sinceBuild: Int? = 243): LocalPackage {
        val attrs = Files.readAttributes(zip, java.nio.file.attribute.BasicFileAttributes::class.java)
        return LocalPackage(
            version = version,
            path = zip,
            fileName = zip.fileName.toString(),
            sizeBytes = attrs.size(),
            modifiedMs = attrs.lastModifiedTime().toMillis(),
            sinceBuildBaseline = sinceBuild,
        )
    }

    // ---------- 主路径 ----------

    @Test
    fun installFromLocal_happyPath_tempUsedAndCleaned_sourceUntouched() {
        val zip = writeIpgpZip(tmp.root.toPath().resolve("new-2026.11.12.zip"), pluginXml("2026.11.12"))
        val sourceBytes = Files.readAllBytes(zip)
        val install = RecordingInstall()
        val result = PluginUpdateRunner.installFromLocal(
            localPkg(zip),
            install = install,
            currentIdeBaseline = { currentBaseline },
        )
        assertTrue("应透传安装结果：$result", result is PluginUpdateRunner.InstallResult.InstalledDynamic)
        assertEquals("install seam 恰好一次", 1, install.calls.size)
        val tempPath = install.calls.single()
        assertTrue("install 收到的应是临时副本而非源文件", tempPath != zip && !tempPath.startsWith(tmp.root.toPath()))
        // 临时副本已清理；源包原样
        assertFalse("临时副本必须删除（finally）：$tempPath", Files.exists(tempPath))
        assertTrue("源包必须仍在", Files.exists(zip))
        assertTrue("源包内容不得被改动", sourceBytes.contentEquals(Files.readAllBytes(zip)))
    }

    // ---------- G1：冒名包拦在安装之前 ----------

    @Test
    fun installFromLocal_foreignId_rejectedBeforeInstall() {
        val zip = writeIpgpZip(tmp.root.toPath().resolve("foreign.zip"), pluginXml("9.9.9", id = "com.evil.other"))
        val sourceBytes = Files.readAllBytes(zip)
        val install = RecordingInstall()
        val result = PluginUpdateRunner.installFromLocal(
            localPkg(zip, version = "9.9.9"),
            install = install,
            currentIdeBaseline = { currentBaseline },
        )
        assertTrue(result is PluginUpdateRunner.InstallResult.Failed)
        assertTrue((result as PluginUpdateRunner.InstallResult.Failed).message.contains("不是本插件"))
        assertEquals("冒名包不得触达安装", 0, install.calls.size)
        assertTrue(sourceBytes.contentEquals(Files.readAllBytes(zip)))
    }

    // ---------- G2：夹带包拦在安装之前 ----------

    @Test
    fun installFromLocal_taintedPackage_rejectedBeforeInstall() {
        val zip = tmp.root.toPath().resolve("smuggled.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            val jarBytes = java.io.ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { jar ->
                    jar.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                    jar.write(pluginXml("2026.11.20").toByteArray())
                    jar.closeEntry()
                }
            }.toByteArray()
            z.putNextEntry(ZipEntry("${UpdateSource.INSTALL_DIR_NAME}/lib/plugin.jar"))
            z.write(jarBytes)
            z.closeEntry()
            z.putNextEntry(ZipEntry("evil-plugin/lib/evil.jar"))
            z.write(ByteArray(0))
            z.closeEntry()
        }
        val install = RecordingInstall()
        val result = PluginUpdateRunner.installFromLocal(
            localPkg(zip, version = "2026.11.20"),
            install = install,
            currentIdeBaseline = { currentBaseline },
        )
        assertTrue(result is PluginUpdateRunner.InstallResult.Failed)
        assertTrue((result as PluginUpdateRunner.InstallResult.Failed).message.contains("已拒绝安装"))
        assertEquals("夹带包不得触达安装", 0, install.calls.size)
    }

    // ---------- D-5：since-build 高于当前 IDE ----------

    @Test
    fun installFromLocal_incompatibleSinceBuild_rejected() {
        val zip = writeIpgpZip(tmp.root.toPath().resolve("newer-ide.zip"), pluginXml("2026.11.12", sinceBuild = "263.*"))
        val install = RecordingInstall()
        val result = PluginUpdateRunner.installFromLocal(
            localPkg(zip, sinceBuild = 263),
            install = install,
            currentIdeBaseline = { currentBaseline },
        )
        assertTrue(result is PluginUpdateRunner.InstallResult.Failed)
        assertTrue(
            "文案应给出两侧版本：$result",
            (result as PluginUpdateRunner.InstallResult.Failed).message.contains("263"),
        )
        assertEquals(0, install.calls.size)
    }

    @Test
    fun installFromLocal_unknownSinceBuild_notBlocked() {
        val zip = writeIpgpZip(tmp.root.toPath().resolve("nosb.zip"), pluginXml("2026.11.12", sinceBuild = null))
        val install = RecordingInstall()
        val result = PluginUpdateRunner.installFromLocal(
            localPkg(zip, sinceBuild = null),
            install = install,
            currentIdeBaseline = { currentBaseline },
        )
        assertTrue("包未声明 since-build 不拦（D-5 口径）：$result", result is PluginUpdateRunner.InstallResult.InstalledDynamic)
    }

    // ---------- 复制与校验失败 ----------

    @Test
    fun installFromLocal_sourceVanishedAfterGate_copyFailure() {
        // verify seam 放行，但文件在复制前消失（共享盘掉线形态）；包元数据手工构造（源不存在，无法 stat）
        val zip = tmp.root.toPath().resolve("ghost.zip")
        val pkg = LocalPackage(
            version = "2026.11.12",
            path = zip,
            fileName = "ghost.zip",
            sizeBytes = 1,
            modifiedMs = 1,
            sinceBuildBaseline = 243,
        )
        val install = RecordingInstall()
        val result = PluginUpdateRunner.installFromLocal(
            pkg,
            verify = { Ownership.Ours("2026.11.12", 243, UpdateSource.INSTALL_DIR_NAME) },
            install = install,
            currentIdeBaseline = { currentBaseline },
        )
        assertTrue(result is PluginUpdateRunner.InstallResult.Failed)
        assertTrue((result as PluginUpdateRunner.InstallResult.Failed).message.contains("无法读取本地安装包"))
        assertEquals(0, install.calls.size)
    }

    @Test
    fun installFromLocal_garbageCopy_invalidZip() {
        val zip = tmp.root.toPath().resolve("junk.zip")
        Files.write(zip, "not a zip".toByteArray())
        val install = RecordingInstall()
        val result = PluginUpdateRunner.installFromLocal(
            localPkg(zip),
            verify = { Ownership.Ours("2026.11.12", 243, UpdateSource.INSTALL_DIR_NAME) },
            install = install,
            currentIdeBaseline = { currentBaseline },
        )
        assertTrue(result is PluginUpdateRunner.InstallResult.Failed)
        assertTrue((result as PluginUpdateRunner.InstallResult.Failed).message.contains("不是有效的插件压缩包"))
        assertEquals(0, install.calls.size)
    }

    @Test
    fun installFromLocal_guardReject_entriesOverLimit() {
        // 65 个非目录条目 > UpdateZipGuard.MAX_ENTRIES(64)：G3 有界守卫拦在安装之前
        val zip = writeIpgpZip(tmp.root.toPath().resolve("bomb.zip"), pluginXml("2026.11.12"), extraEntries = 65)
        val install = RecordingInstall()
        val result = PluginUpdateRunner.installFromLocal(
            localPkg(zip),
            install = install,
            currentIdeBaseline = { currentBaseline },
        )
        assertTrue("超限应被守卫拒绝：$result", result is PluginUpdateRunner.InstallResult.Failed)
        assertTrue((result as PluginUpdateRunner.InstallResult.Failed).message.contains("未通过安全校验"))
        assertEquals(0, install.calls.size)
    }

    // ---------- 三态透传 ----------

    @Test
    fun installFromLocal_restartRequired_transparent() {
        val zip = writeIpgpZip(tmp.root.toPath().resolve("new.zip"), pluginXml("2026.11.12"))
        val result = PluginUpdateRunner.installFromLocal(
            localPkg(zip),
            install = { PluginUpdateRunner.InstallResult.InstalledRestartRequired },
            currentIdeBaseline = { currentBaseline },
        )
        assertEquals(PluginUpdateRunner.InstallResult.InstalledRestartRequired, result)
    }

    @Test
    fun installFromLocal_installFailure_transparent() {
        val zip = writeIpgpZip(tmp.root.toPath().resolve("new.zip"), pluginXml("2026.11.12"))
        val result = PluginUpdateRunner.installFromLocal(
            localPkg(zip),
            install = { PluginUpdateRunner.InstallResult.Failed("platform refused") },
            currentIdeBaseline = { currentBaseline },
        )
        assertTrue(result is PluginUpdateRunner.InstallResult.Failed)
        assertEquals("platform refused", (result as PluginUpdateRunner.InstallResult.Failed).message)
    }
}
