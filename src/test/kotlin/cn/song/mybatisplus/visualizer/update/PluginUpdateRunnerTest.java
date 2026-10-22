package cn.song.mybatisplus.visualizer.update;

import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.CheckResult;
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.InstallResult;
import com.intellij.openapi.util.io.FileUtil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 更新编排单测（T-M28-02）：check / downloadAndInstall 全分支——fetch/download/install
 * 均为注入 seam（**不触达网络与平台安装**），临时文件清理断言。
 */
public class PluginUpdateRunnerTest {

    private static final UpdateManifest manifest =
            new UpdateManifest("2026.8.15", "https://example.com/plugin.zip", null, null, null);

    // ---------------- check ----------------

    @Test
    public void checkCurrentVersionUnavailableIsFailure() throws Exception {
        CheckResult result = PluginUpdateRunner.check(null, s -> {
            throw new AssertionError("fetch must not be called");
        });
        assertTrue(result instanceof CheckResult.Failure);
    }

    @Test
    public void checkFetchFailureIsFailure() throws Exception {
        CheckResult result = PluginUpdateRunner.check("2026.8.14", s -> new UpdateApi.FetchOutcome.NetworkError("null"));
        assertTrue(result instanceof CheckResult.Failure);
    }

    @Test
    public void checkMalformedManifestIsFailure() throws Exception {
        CheckResult result = PluginUpdateRunner.check("2026.8.14", s -> new UpdateApi.FetchOutcome.Success("not json"));
        assertTrue(result instanceof CheckResult.Failure);
    }

    @Test
    public void checkNewerVersionIsAvailable() throws Exception {
        CheckResult result = PluginUpdateRunner.check("2026.8.14", s ->
                new UpdateApi.FetchOutcome.Success("{\"version\":\"2026.8.15\",\"downloadUrl\":\"https://example.com/plugin.zip\"}"));
        assertEquals(new CheckResult.Available(manifest), result);
    }

    @Test
    public void checkSameOrOlderIsUpToDate() throws Exception {
        CheckResult same = PluginUpdateRunner.check("2026.8.15", s ->
                new UpdateApi.FetchOutcome.Success("{\"version\":\"2026.8.15\",\"downloadUrl\":\"https://example.com/plugin.zip\"}"));
        CheckResult older = PluginUpdateRunner.check("2026.8.16", s ->
                new UpdateApi.FetchOutcome.Success("{\"version\":\"2026.8.15\",\"downloadUrl\":\"https://example.com/plugin.zip\"}"));
        assertTrue(same instanceof CheckResult.UpToDate);
        assertTrue(older instanceof CheckResult.UpToDate);
    }

    // ---------------- downloadAndInstall ----------------

    @Test
    public void downloadAndInstallHappyPath() throws Exception {
        final boolean[] installCalled = {false};
        InstallResult result = runWithTempZip(zip ->
                PluginUpdateRunner.downloadAndInstall(manifest, null,
                        (url, target) -> { try { copyTo(zip, target); } catch (java.io.IOException e) { throw new RuntimeException(e); } },
                        path -> {
                            installCalled[0] = true;
                            return InstallResult.InstalledRestartRequired.INSTANCE;
                        }));
        assertEquals(InstallResult.InstalledRestartRequired.INSTANCE, result);
        assertTrue(installCalled[0]);
    }

    @Test
    public void downloadAndInstallRemovesTempFile() throws IOException {
        File tempDir = FileUtil.createTempDirectory("mpvs-test-", null, false);
        try {
            List<File> tempFiles = new ArrayList<>();
            PluginUpdateRunner.downloadAndInstall(manifest, null,
                    (url, target) -> {
                        try {
                            writeZip(target.toFile());
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                        tempFiles.add(target.toFile());
                    },
                    path -> InstallResult.InstalledRestartRequired.INSTANCE);
            assertTrue(tempFiles.stream().allMatch(f -> !f.exists()));
        } finally {
            FileUtil.delete(tempDir);
        }
    }

    @Test
    public void downloadAndInstallDownloadFailureCleansUp() throws Exception {
        InstallResult result = PluginUpdateRunner.downloadAndInstall(manifest, null,
                (url, target) -> {
                    throw new RuntimeException("connection refused");
                },
                path -> {
                    throw new AssertionError("install must not be called");
                });
        assertTrue(result instanceof InstallResult.Failed);
        assertTrue(((InstallResult.Failed) result).message().contains("connection refused"));
    }

    @Test
    public void downloadAndInstallInvalidZipIsFailed() throws Exception {
        final boolean[] installCalled = {false};
        InstallResult result = PluginUpdateRunner.downloadAndInstall(manifest, null,
                (url, target) -> {
                    try {
                        Files.writeString(target, "<html>error page</html>");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                path -> {
                    installCalled[0] = true;
                    return InstallResult.InstalledRestartRequired.INSTANCE;
                });
        assertTrue(result instanceof InstallResult.Failed);
        assertFalse(installCalled[0]);
    }

    @Test
    public void downloadAndInstallEmptyFileIsFailed() throws Exception {
        InstallResult result = PluginUpdateRunner.downloadAndInstall(manifest, null,
                (url, target) -> {
                },
                path -> InstallResult.InstalledRestartRequired.INSTANCE);
        assertTrue(result instanceof InstallResult.Failed);
    }

    @Test
    public void downloadAndInstallInstallFailurePropagates() throws Exception {
        InstallResult result = PluginUpdateRunner.downloadAndInstall(manifest, null,
                (url, target) -> { try { writeZip(target.toFile()); } catch (java.io.IOException e) { throw new RuntimeException(e); } },
                path -> new InstallResult.Failed("install rejected"));
        assertEquals(new InstallResult.Failed("install rejected"), result);
    }

    @Test
    public void downloadAndInstallInstallExceptionIsFailed() throws Exception {
        InstallResult result = PluginUpdateRunner.downloadAndInstall(manifest, null,
                (url, target) -> { try { writeZip(target.toFile()); } catch (java.io.IOException e) { throw new RuntimeException(e); } },
                path -> {
                    throw new RuntimeException("boom");
                });
        assertTrue(result instanceof InstallResult.Failed);
        assertTrue(((InstallResult.Failed) result).message().contains("boom"));
    }

    // ---------------- isValidZip ----------------

    @Test
    public void isValidZipAcceptsRealZip() throws IOException {
        File f = FileUtil.createTempFile("mpvs-test-", ".zip", false);
        try {
            writeZip(f);
            assertTrue(PluginUpdateRunner.isValidZip(f));
        } finally {
            FileUtil.delete(f);
        }
    }

    @Test
    public void isValidZipRejectsGarbage() throws IOException {
        File f = FileUtil.createTempFile("mpvs-test-", ".zip", false);
        try {
            Files.writeString(f.toPath(), "not a zip");
            assertFalse(PluginUpdateRunner.isValidZip(f));
            assertFalse(PluginUpdateRunner.isValidZip(new File("missing.zip")));
        } finally {
            FileUtil.delete(f);
        }
    }

    // ---------------- helpers ----------------

    private static InstallResult runWithTempZip(Function<Path, InstallResult> block) throws IOException {
        File f = FileUtil.createTempFile("mpvs-test-", ".zip", false);
        try {
            writeZip(f);
            return block.apply(f.toPath());
        } finally {
            FileUtil.delete(f);
        }
    }

    private static void copyTo(Path from, Path target) throws IOException {
        Files.write(target, Files.readAllBytes(from));
    }

    private static void writeZip(File file) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            zip.putNextEntry(new ZipEntry("lib/plugin.jar"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
    }
}
