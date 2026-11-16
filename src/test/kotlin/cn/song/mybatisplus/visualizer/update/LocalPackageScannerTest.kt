package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 本地安装包目录扫描单测（T-LocalUpdateDir M2，计划 T2）：非递归列举、归属闸门过滤、
 * 版本选取（平台比较器）、只升不降、截断、缓存失效。纯 JVM + TemporaryFolder 真目录。
 */
class LocalPackageScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ourId = UpdateSource.PLUGIN_ID
    private val current = "2026.11.11"
    private fun freshCache(): PackageVerificationCache = PackageVerificationCache()

    private fun pluginXml(version: String, id: String = ourId): String = """
        <idea-plugin>
            <id>$id</id>
            <version>$version</version>
        </idea-plugin>
    """.trimIndent()

    /** 写一个标准 IPGP 布局假包（顶层插件目录 + lib 内嵌描述符）。 */
    private fun writeZip(dir: Path, fileName: String, version: String, id: String = ourId): Path {
        val target = dir.resolve(fileName)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            val jarBytes = java.io.ByteArrayOutputStream().also { jarOut ->
                ZipOutputStream(jarOut).use { jar ->
                    jar.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                    jar.write(pluginXml(version, id).toByteArray())
                    jar.closeEntry()
                }
            }.toByteArray()
            zip.putNextEntry(ZipEntry("${UpdateSource.INSTALL_DIR_NAME}/lib/plugin-test.jar"))
            zip.write(jarBytes)
            zip.closeEntry()
        }
        return target
    }

    private fun scan(dir: Path, cache: PackageVerificationCache = freshCache()): ScanResult =
        LocalPackageScanner.scan(dir.toString(), current, cache)

    // ---------- Disabled / DirInvalid ----------

    @Test
    fun scan_blankDir_disabled() {
        assertEquals(ScanResult.Disabled, LocalPackageScanner.scan("", current))
        assertEquals(ScanResult.Disabled, LocalPackageScanner.scan("   ", current))
    }

    @Test
    fun scan_missingDir_dirInvalid() {
        val missing = tmp.root.toPath().resolve("no-such-dir")
        val result = scan(missing)
        assertTrue(result is ScanResult.DirInvalid)
        assertEquals(LocalUpdateDir.DirCheck.NotFound, (result as ScanResult.DirInvalid).check)
    }

    @Test
    fun scan_fileInsteadOfDir_notDirectory() {
        val file = tmp.newFile("plain.txt")
        val result = scan(file.toPath())
        assertEquals(
            LocalUpdateDir.DirCheck.NotDirectory,
            (result as ScanResult.DirInvalid).check,
        )
    }

    // ---------- Empty ----------

    @Test
    fun scan_emptyDir_empty() {
        val result = scan(tmp.root.toPath())
        assertTrue("空目录应 Empty：$result", result is ScanResult.Empty)
    }

    @Test
    fun scan_nonZipFilesOnly_empty() {
        tmp.newFile("readme.md")
        tmp.newFile("setup.exe")
        assertTrue(scan(tmp.root.toPath()) is ScanResult.Empty)
    }

    // ---------- 版本选取 ----------

    @Test
    fun scan_latestAmongMultiple_available() {
        val dir = tmp.root.toPath()
        writeZip(dir, "mybatis-plus-sql-visualizer-2026.11.9.zip", "2026.11.9")
        writeZip(dir, "mybatis-plus-sql-visualizer-2026.11.10.zip", "2026.11.10")
        writeZip(dir, "mybatis-plus-sql-visualizer-2026.11.12.zip", "2026.11.12")
        val result = scan(dir)
        assertTrue(result is ScanResult.Available)
        assertEquals("2026.11.12", (result as ScanResult.Available).latest.version)
        assertEquals(3, result.total)
    }

    @Test
    fun scan_betaVersusFormal_sameDay_formalWins() {
        val dir = tmp.root.toPath()
        writeZip(dir, "a-2026.11.12-beta.1.zip", "2026.11.12-beta.1")
        writeZip(dir, "b-2026.11.12.zip", "2026.11.12")
        val result = scan(dir)
        assertTrue(result is ScanResult.Available)
        assertEquals("同日 beta 应输给正式版（平台比较器语义）", "2026.11.12", (result as ScanResult.Available).latest.version)
    }

    @Test
    fun scan_filenameMismatch_innerVersionAuthoritative() {
        val dir = tmp.root.toPath()
        // 文件名与包内版本完全无关（用户改名/复制副本）
        writeZip(dir, "最新.zip", "2026.11.14")
        writeZip(dir, "mybatis-plus-sql-visualizer-2026.11.9.zip", "2026.11.9")
        val result = scan(dir)
        assertTrue(result is ScanResult.Available)
        assertEquals("2026.11.14", (result as ScanResult.Available).latest.version)
        assertEquals("最新.zip", (result as ScanResult.Available).latest.fileName)
    }

    @Test
    fun scan_sameVersionTie_mtimeDescWins() {
        val dir = tmp.root.toPath()
        val older = writeZip(dir, "first.zip", "2026.11.12")
        val newer = writeZip(dir, "second.zip", "2026.11.12")
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000_000L))
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000_000L))
        val result = scan(dir)
        assertTrue(result is ScanResult.Available)
        assertEquals("同版本取 mtime 更晚者", newer, (result as ScanResult.Available).latest.path)
    }

    // ---------- 归属闸门过滤（G1/G2 在扫描期的表现） ----------

    @Test
    fun scan_foreignId_skipped() {
        val dir = tmp.root.toPath()
        writeZip(dir, "mine-2026.11.12.zip", "2026.11.12")
        writeZip(dir, "other-plugin.zip", "9.9.9", id = "com.example.other")
        val result = scan(dir)
        assertTrue(result is ScanResult.Available)
        assertEquals(1, (result as ScanResult.Available).skipped)
        assertEquals("2026.11.12", result.latest.version)
    }

    @Test
    fun scan_corruptZip_skipped() {
        val dir = tmp.root.toPath()
        writeZip(dir, "mine-2026.11.12.zip", "2026.11.12")
        Files.write(dir.resolve("broken.zip"), "not a zip".toByteArray())
        val result = scan(dir)
        assertTrue(result is ScanResult.Available)
        assertEquals(1, (result as ScanResult.Available).skipped)
    }

    @Test
    fun scan_smuggledPackage_skipped_notOffered() {
        // 夹带包不参与「最新版本」判定（计划 A13）：页面不弹「发现 X」，也不可安装
        val dir = tmp.root.toPath()
        writeZip(dir, "mine-2026.11.12.zip", "2026.11.12")
        ZipOutputStream(Files.newOutputStream(dir.resolve("smuggled.zip"))).use { zip ->
            val ourJar = java.io.ByteArrayOutputStream().also { jarOut ->
                ZipOutputStream(jarOut).use { jar ->
                    jar.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                    jar.write(pluginXml("2026.11.20").toByteArray())
                    jar.closeEntry()
                }
            }.toByteArray()
            zip.putNextEntry(ZipEntry("${UpdateSource.INSTALL_DIR_NAME}/lib/plugin.jar"))
            zip.write(ourJar)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("evil-plugin/lib/evil.jar"))
            zip.write(ByteArray(0))
            zip.closeEntry()
        }
        val result = scan(dir)
        assertTrue("夹带包不得成为候选：$result", result is ScanResult.Available)
        assertEquals("2026.11.12", (result as ScanResult.Available).latest.version)
        assertEquals(1, result.skipped)
    }

    @Test
    fun scan_nothingValid_noValidPackage() {
        val dir = tmp.root.toPath()
        writeZip(dir, "other.zip", "1.0.0", id = "com.example.other")
        Files.write(dir.resolve("broken.zip"), "junk".toByteArray())
        val result = scan(dir)
        assertTrue(result is ScanResult.NoValidPackage)
        assertEquals(2, (result as ScanResult.NoValidPackage).skipped)
        assertTrue(result.skippedSamples.isNotEmpty())
    }

    // ---------- 只升不降 ----------

    @Test
    fun scan_onlyOlderVersions_upToDate() {
        val dir = tmp.root.toPath()
        writeZip(dir, "old-2026.11.10.zip", "2026.11.10")
        val result = scan(dir)
        assertTrue("旧版本不得提示更新：$result", result is ScanResult.UpToDate)
        assertEquals("2026.11.10", (result as ScanResult.UpToDate).latest.version)
    }

    @Test
    fun scan_equalVersion_upToDate() {
        val dir = tmp.root.toPath()
        writeZip(dir, "same-2026.11.11.zip", current)
        assertTrue(scan(dir) is ScanResult.UpToDate)
    }

    // ---------- 截断 ----------

    @Test
    fun scan_overCap_truncatedAndScannedTopByMtime() {
        val dir = tmp.root.toPath()
        repeat(LocalPackageScanner.MAX_CANDIDATES + 20) { i ->
            val zip = writeZip(dir, "old-$i.zip", "2020.1.1")
            Files.setLastModifiedTime(zip, FileTime.fromMillis(1_000_000L + i))
        }
        val result = scan(dir)
        assertTrue(result is ScanResult.UpToDate)
        assertEquals("超出上限按 mtime 降序截断", LocalPackageScanner.MAX_CANDIDATES, (result as ScanResult.UpToDate).total)
        assertTrue((result as ScanResult.UpToDate).truncated)
    }

    // ---------- 当前版本不可读 ----------

    @Test
    fun scan_currentVersionUnreadable_failed() {
        val dir = tmp.root.toPath()
        writeZip(dir, "new-2026.11.12.zip", "2026.11.12")
        val result = LocalPackageScanner.scan(dir.toString(), null, freshCache())
        assertTrue("判不了当前版本就不许装（与远端 VERSION_UNREADABLE 同口径）：$result", result is ScanResult.Failed)
    }

    // ---------- 缓存 ----------

    @Test
    fun cache_hitServesConclusionWithoutReadingFile() {
        val dir = tmp.root.toPath()
        // 内容是垃圾（真校验必 Skip），但预置了 Valid 结论——扫描直接采信缓存即证「不再开包」
        val zip = dir.resolve("new-2026.11.12.zip")
        Files.write(zip, "not a zip at all".toByteArray())
        val cache = freshCache()
        cache.put(zip, Files.size(zip), Files.getLastModifiedTime(zip).toMillis(), PackageVerificationCache.Outcome.Valid("2026.11.12", null))
        val result = scan(dir, cache)
        assertTrue("缓存命中应免真校验：$result", result is ScanResult.Available)
        assertEquals("2026.11.12", (result as ScanResult.Available).latest.version)
    }

    @Test
    fun cache_fingerprintChange_reverified() {
        val dir = tmp.root.toPath()
        val zip = writeZip(dir, "pkg.zip", "2026.11.12")
        val cache = freshCache()
        assertTrue(scan(dir, cache) is ScanResult.Available)
        // 同路径被替换成别的插件 + mtime 变化 → 指纹失配 → 重新校验 → Skip
        Files.write(zip, "replaced by foreign".toByteArray())
        Files.setLastModifiedTime(zip, FileTime.fromMillis(System.currentTimeMillis() + 60_000))
        val result = scan(dir, cache)
        assertTrue("指纹变化必须重验：$result", result is ScanResult.NoValidPackage)
    }

    @Test
    fun cache_clearForcesReread() {
        val dir = tmp.root.toPath()
        writeZip(dir, "new-2026.11.12.zip", "2026.11.12")
        val cache = freshCache()
        assertTrue(scan(dir, cache) is ScanResult.Available)
        cache.clear()
        Files.write(dir.resolve("new-2026.11.12.zip"), "junk".toByteArray())
        assertTrue("清缓存后必须重验", scan(dir, cache) is ScanResult.NoValidPackage)
    }

    // ---------- 中文与空格目录 ----------

    @Test
    fun scan_chineseAndSpaceDir_available() {
        val dir = tmp.root.toPath().resolve("插件包 目录")
        Files.createDirectories(dir)
        writeZip(dir, "mybatis-plus-sql-visualizer-2026.11.12.zip", "2026.11.12")
        val result = scan(dir)
        assertTrue("中文与空格目录应可用：$result", result is ScanResult.Available)
        assertEquals("2026.11.12", (result as ScanResult.Available).latest.version)
    }

    @Test
    fun zipExtensionCaseInsensitive() {
        val dir = tmp.root.toPath()
        writeZip(dir, "lower.zip", "2026.11.12")
        // Windows 常见大写扩展名：改出大写副本
        val upper = dir.resolve("upper.ZIP")
        Files.copy(dir.resolve("lower.zip"), upper)
        val result = scan(dir)
        assertTrue("大写 .ZIP 也应被扫到：$result", result is ScanResult.Available)
        assertEquals(2, (result as ScanResult.Available).total)
    }
}
