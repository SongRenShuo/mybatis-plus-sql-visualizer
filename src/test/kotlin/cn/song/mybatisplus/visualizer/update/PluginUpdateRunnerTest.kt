package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.HttpRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipOutputStream

/**
 * 更新编排单测（T-M28-02）：check / downloadAndInstall 全分支——fetch/download/install
 * 均为注入 seam（**不触达网络与平台安装**），临时文件清理断言。
 */
class PluginUpdateRunnerTest {

    private val manifest = UpdateManifest(
        version = "2026.8.15",
        downloadUrl = "https://example.com/plugin.zip",
        changeNotes = null,
        publishDate = null,
        sinceBuild = null,
    )

    // ---------------- check ----------------

    @Test
    fun checkCurrentVersionUnavailableIsFailure() {
        val result = PluginUpdateRunner.check(null) { throw AssertionError("fetch must not be called") }
        assertTrue(result is PluginUpdateRunner.CheckResult.Failure)
    }

    @Test
    fun checkFetchFailureIsFailure() {
        val result = PluginUpdateRunner.check("2026.8.14") { UpdateApi.FetchOutcome.NetworkError("network unavailable") }
        assertTrue(result is PluginUpdateRunner.CheckResult.Failure)
    }

    @Test
    fun checkMalformedManifestIsFailure() {
        val result = PluginUpdateRunner.check("2026.8.14") { UpdateApi.FetchOutcome.Success("not json") }
        assertTrue(result is PluginUpdateRunner.CheckResult.Failure)
    }

    @Test
    fun checkNewerVersionIsAvailable() {
        val result = PluginUpdateRunner.check("2026.8.14") {
            UpdateApi.FetchOutcome.Success(
                """{"version":"2026.8.15","downloadUrl":"https://example.com/plugin.zip"}""",
            )
        }
        assertEquals(PluginUpdateRunner.CheckResult.Available(manifest), result)
    }

    @Test
    fun checkSameOrOlderIsUpToDate() {
        val same = PluginUpdateRunner.check("2026.8.15") {
            UpdateApi.FetchOutcome.Success(
                """{"version":"2026.8.15","downloadUrl":"https://example.com/plugin.zip"}""",
            )
        }
        val older = PluginUpdateRunner.check("2026.8.16") {
            UpdateApi.FetchOutcome.Success(
                """{"version":"2026.8.15","downloadUrl":"https://example.com/plugin.zip"}""",
            )
        }
        assertTrue(same is PluginUpdateRunner.CheckResult.UpToDate)
        assertTrue(older is PluginUpdateRunner.CheckResult.UpToDate)
    }

    // ---------- T-DevUpdateChannel：manifestUrl 第三参（带默认值，不破坏既有尾随 lambda 调用） ----------

    @Test
    fun checkUsesInjectedManifestUrl() {
        val preview = "https://example.com/update/preview.update.json"
        var seen: String? = null
        val result = PluginUpdateRunner.check("2026.8.14", preview) { url ->
            seen = url
            UpdateApi.FetchOutcome.Success(
                """{"version":"2026.8.15","downloadUrl":"https://example.com/plugin.zip"}""",
            )
        }
        assertEquals("开发版通道必须读注入的预览清单地址", preview, seen)
        assertEquals(PluginUpdateRunner.CheckResult.Available(manifest), result)
    }

    @Test
    fun checkDefaultManifestUrlIsStableChannel() {
        // 不传第三参时仍读正式清单（回归：分流不得污染正式通道）
        var seen: String? = null
        PluginUpdateRunner.check("2026.8.14") { url ->
            seen = url
            UpdateApi.FetchOutcome.Success("""{"version":"2026.8.14","downloadUrl":"https://example.com/p.zip"}""")
        }
        assertEquals(UpdateSource.manifestUrl(), seen)
        assertFalse("正式通道不得读预览清单", seen!!.contains("preview.update.json"))
    }

    // ---------------- downloadAndInstall ----------------

    @Test
    fun downloadAndInstallHappyPath() {
        var installCalled = false
        val result = runWithTempZip { zip ->
            PluginUpdateRunner.downloadAndInstall(manifest, null,
                download = { _, target -> copyTo(zip, target) },
                install = { installCalled = true; PluginUpdateRunner.InstallResult.InstalledRestartRequired },
            )
        }
        assertEquals(PluginUpdateRunner.InstallResult.InstalledRestartRequired, result)
        assertTrue(installCalled)
    }

    @Test
    fun downloadAndInstallRemovesTempFile() {
        val tempDir = FileUtil.createTempDirectory("mpvs-test-", null, false)
        try {
            val tempFiles = mutableListOf<File>()
            PluginUpdateRunner.downloadAndInstall(manifest, null,
                download = { _, target ->
                    writeZip(target.toFile())
                    tempFiles += target.toFile()
                },
                install = { PluginUpdateRunner.InstallResult.InstalledRestartRequired },
            )
            // 下载/安装成功后临时文件已清理
            assertTrue(tempFiles.all { !it.exists() })
        } finally {
            FileUtil.delete(tempDir)
        }
    }

    @Test
    fun downloadAndInstallDownloadFailureCleansUp() {
        val result = PluginUpdateRunner.downloadAndInstall(manifest, null,
            download = { _, _ -> throw RuntimeException("connection refused") },
            install = { throw AssertionError("install must not be called") },
        )
        assertTrue(result is PluginUpdateRunner.InstallResult.Failed)
        assertTrue((result as PluginUpdateRunner.InstallResult.Failed).message.contains("connection refused"))
    }

    @Test
    fun downloadAndInstallInvalidZipIsFailed() {
        var installCalled = false
        val result = PluginUpdateRunner.downloadAndInstall(manifest, null,
            download = { _, target -> target.toFile().writeText("<html>error page</html>") },
            install = { installCalled = true; PluginUpdateRunner.InstallResult.InstalledRestartRequired },
        )
        assertTrue(result is PluginUpdateRunner.InstallResult.Failed)
        assertFalse(installCalled)
    }

    @Test
    fun downloadAndInstallEmptyFileIsFailed() {
        val result = PluginUpdateRunner.downloadAndInstall(manifest, null,
            download = { _, _ -> /* 不写任何内容 */ },
            install = { PluginUpdateRunner.InstallResult.InstalledRestartRequired },
        )
        assertTrue(result is PluginUpdateRunner.InstallResult.Failed)
    }

    @Test
    fun downloadAndInstallInstallFailurePropagates() {
        val result = PluginUpdateRunner.downloadAndInstall(manifest, null,
            download = { _, target -> writeZip(target.toFile()) },
            install = { PluginUpdateRunner.InstallResult.Failed("install rejected") },
        )
        assertEquals(PluginUpdateRunner.InstallResult.Failed("install rejected"), result)
    }

    @Test
    fun downloadAndInstallInstallExceptionIsFailed() {
        val result = PluginUpdateRunner.downloadAndInstall(manifest, null,
            download = { _, target -> writeZip(target.toFile()) },
            install = { throw RuntimeException("boom") },
        )
        assertTrue(result is PluginUpdateRunner.InstallResult.Failed)
        assertTrue((result as PluginUpdateRunner.InstallResult.Failed).message.contains("boom"))
    }

    // ---------- T-DevUpdateChannel：下载 404/410 = 「包还没发布」，与普通失败分开 ----------

    @Test
    fun downloadAndInstall404IsNotPublished() {
        var installCalled = false
        val result = PluginUpdateRunner.downloadAndInstall(manifest, null,
            download = { url, _ ->
                throw HttpRequests.HttpStatusException("Not Found for url: $url", 404, url)
            },
            install = { installCalled = true; PluginUpdateRunner.InstallResult.InstalledRestartRequired },
        )
        assertEquals(PluginUpdateRunner.InstallResult.NotPublished(404), result)
        assertFalse("包没发布时不得进入安装", installCalled)
    }

    @Test
    fun downloadAndInstall410IsNotPublished() {
        val result = PluginUpdateRunner.downloadAndInstall(manifest, null,
            download = { url, _ -> throw HttpRequests.HttpStatusException("Gone for url: $url", 410, url) },
            install = { throw AssertionError("install must not be called") },
        )
        assertEquals(PluginUpdateRunner.InstallResult.NotPublished(410), result)
    }

    @Test
    fun downloadAndInstallWrapped404IsStillNotPublished() {
        // saveToFile 视版本可能把状态异常包一层 IOException——挖异常链，别漏判
        val result = PluginUpdateRunner.downloadAndInstall(manifest, null,
            download = { url, _ ->
                throw java.io.IOException(
                    "download failed",
                    HttpRequests.HttpStatusException("Not Found for url: $url", 404, url),
                )
            },
            install = { throw AssertionError("install must not be called") },
        )
        assertEquals(PluginUpdateRunner.InstallResult.NotPublished(404), result)
    }

    @Test
    fun downloadAndInstallHttp500IsFailedNotNotPublished() {
        // 反扩散：404 友好化不得吞掉其余失败
        val result = PluginUpdateRunner.downloadAndInstall(manifest, null,
            download = { url, _ -> throw HttpRequests.HttpStatusException("Server Error for url: $url", 500, url) },
            install = { throw AssertionError("install must not be called") },
        )
        assertTrue("HTTP 500 必须仍是 Failed", result is PluginUpdateRunner.InstallResult.Failed)
    }

    @Test
    fun downloadAndInstallIoFailureIsFailedNotNotPublished() {
        val result = PluginUpdateRunner.downloadAndInstall(manifest, null,
            download = { _, _ -> throw java.io.IOException("connection reset") },
            install = { throw AssertionError("install must not be called") },
        )
        assertTrue(result is PluginUpdateRunner.InstallResult.Failed)
        assertTrue((result as PluginUpdateRunner.InstallResult.Failed).message.contains("connection reset"))
    }

    // ---------------- isValidZip ----------------

    @Test
    fun isValidZipAcceptsRealZip() {
        val f = FileUtil.createTempFile("mpvs-test-", ".zip", false)
        try {
            writeZip(f)
            assertTrue(PluginUpdateRunner.isValidZip(f))
        } finally {
            FileUtil.delete(f)
        }
    }

    @Test
    fun isValidZipRejectsGarbage() {
        val f = FileUtil.createTempFile("mpvs-test-", ".zip", false)
        try {
            f.writeText("not a zip")
            assertFalse(PluginUpdateRunner.isValidZip(f))
            assertFalse(PluginUpdateRunner.isValidZip(File("missing.zip")))
        } finally {
            FileUtil.delete(f)
        }
    }

    // ---------------- helpers ----------------

    private fun runWithTempZip(block: (Path) -> PluginUpdateRunner.InstallResult): PluginUpdateRunner.InstallResult {
        val f = FileUtil.createTempFile("mpvs-test-", ".zip", false)
        try {
            writeZip(f)
            return block(f.toPath())
        } finally {
            FileUtil.delete(f)
        }
    }

    private fun copyTo(from: Path, target: Path) {
        target.toFile().writeBytes(from.toFile().readBytes())
    }

    private fun writeZip(file: File) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("lib/plugin.jar"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
    }
}
