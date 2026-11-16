package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 本地安装包目录路径层单测（T-LocalUpdateDir M1，计划 T1）：
 * 空/`~` 展开/环境变量引用/非法字符/相对路径/存在三态/目录与可读性/插件目录守卫/归一化。
 *
 * 纯 JVM（不触 [com.intellij.openapi.application.PathManager]——插件目录守卫经参数注入临时目录）；
 * 平台相关的用例（Windows 非法冒号、POSIX 不可读）按 os.name 门控，三平台 CI 各跑各自能跑的分支。
 */
class LocalUpdateDirTest {

    private fun newTempDir(prefix: String = "mpvs-local-dir-"): Path =
        Files.createTempDirectory(prefix)

    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("windows")
    private val isPosix: Boolean = !isWindows

    // ---------- Disabled ----------

    @Test
    fun parse_blankAndWhitespace_disabled() {
        assertEquals(LocalUpdateDir.DirCheck.Disabled, LocalUpdateDir.parse(""))
        assertEquals(LocalUpdateDir.DirCheck.Disabled, LocalUpdateDir.parse("   "))
    }

    // ---------- ~ 展开 ----------

    @Test
    fun parse_tildeOnly_expandsToUserHome() {
        val home = System.getProperty("user.home")
        val result = LocalUpdateDir.parse("~")
        if (Files.isDirectory(Path.of(home))) {
            assertTrue("整串 ~ 应展开为 user.home 目录", result is LocalUpdateDir.DirCheck.Ok)
        }
    }

    @Test
    fun parse_tildePrefix_expandsUserHomeAndResolves() {
        val home = System.getProperty("user.home")
        // 展开语义：`~/x` 必须拼到 user.home 下（而不是把 `~` 原样丢给文件系统）
        assertEquals(
            "$home/mpvs-not-exist-probe-2026",
            LocalUpdateDir.expandUserHome("~/mpvs-not-exist-probe-2026"),
        )
        // 不存在的家目录子路径 → NotFound（确定不存在）
        val result = LocalUpdateDir.parse("~/mpvs-not-exist-probe-2026")
        assertEquals(LocalUpdateDir.DirCheck.NotFound, result)
    }

    @Test
    fun expandUserHome_midPathTilde_keptVerbatim() {
        // `~user` / 路径中段 `~` 不是家目录语义，原样保留
        assertEquals("C:\\tools~1\\x", LocalUpdateDir.expandUserHome("C:\\tools~1\\x"))
        assertEquals("~someone/docs", LocalUpdateDir.expandUserHome("~someone/docs"))
        assertEquals("/a/~/b", LocalUpdateDir.expandUserHome("/a/~/b"))
    }

    @Test
    fun expandUserHome_backslashAfterTilde_expandsToo() {
        val home = System.getProperty("user.home")
        assertEquals("$home/sub", LocalUpdateDir.expandUserHome("~\\sub"))
    }

    // ---------- 环境变量引用（明确不支持） ----------

    @Test
    fun parse_windowsEnvRef_rejected() {
        val result = LocalUpdateDir.parse("%TEMP%\\plugins")
        assertEquals(LocalUpdateDir.DirCheck.EnvVarUnsupported, result)
    }

    @Test
    fun parse_unixEnvRef_rejected() {
        assertEquals(LocalUpdateDir.DirCheck.EnvVarUnsupported, LocalUpdateDir.parse("\$HOME/plugins"))
        assertEquals(LocalUpdateDir.DirCheck.EnvVarUnsupported, LocalUpdateDir.parse("\${HOME}/plugins"))
    }

    @Test
    fun parse_midPathDollar_notMistakenForEnvRef() {
        // 设计取舍：只在行首 `$VAR` 或 `${VAR}` 判 env 引用，路径中段的 `$` 按字面处理（防误伤）
        assertNotEquals(LocalUpdateDir.DirCheck.EnvVarUnsupported, LocalUpdateDir.parse("/opt/\$PATH/x"))
    }

    @Test
    fun parse_literalDollarInPath_notMistakenForEnvRef() {
        // `C:\$Recycle.Bin` 这类字面 `$` 目录名不该被误伤（不报 EnvVarUnsupported 即可，后续按存在性判）
        val result = LocalUpdateDir.parse("C:\\\$Recycle.Bin\\x")
        assertNotEquals(LocalUpdateDir.DirCheck.EnvVarUnsupported, result)
    }

    // ---------- 非法字符 ----------

    @Test
    fun parse_nulCharacter_invalidPath() {
        // NUL 在 Windows 与 Unix 的 Paths.get 都抛 InvalidPathException——三平台通用的非法样本
        val result = LocalUpdateDir.parse("some\u0000dir")
        assertTrue("NUL 应判非法，实际 $result", result is LocalUpdateDir.DirCheck.InvalidPath)
    }

    @Test
    fun parse_windowsColon_invalidPath() {
        if (!isWindows) return // `C:\a:b\c` 在 Unix 是合法文件名，只在 Windows 上判非法
        val result = LocalUpdateDir.parse("C:\\a:b\\c")
        assertTrue("Windows 错位冒号应判非法，实际 $result", result is LocalUpdateDir.DirCheck.InvalidPath)
    }

    // ---------- 相对路径 ----------

    @Test
    fun parse_relativePath_rejected() {
        assertEquals(LocalUpdateDir.DirCheck.Relative, LocalUpdateDir.parse("plugins"))
        assertEquals(LocalUpdateDir.DirCheck.Relative, LocalUpdateDir.parse(".\\plugins"))
        assertEquals(LocalUpdateDir.DirCheck.Relative, LocalUpdateDir.parse("../plugins"))
    }

    // ---------- 存在性三态 ----------

    @Test
    fun parse_missingDir_notFound() {
        val missing = newTempDir().resolve("definitely-missing-child")
        val result = LocalUpdateDir.parse(missing.toString())
        assertEquals(LocalUpdateDir.DirCheck.NotFound, result)
    }

    @Test
    fun parse_existingFile_notDirectory() {
        val file = Files.createTempFile("mpvs-local-file-", ".txt")
        try {
            val result = LocalUpdateDir.parse(file.toString())
            assertEquals(LocalUpdateDir.DirCheck.NotDirectory, result)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun parse_existingDir_okAndNormalized() {
        val dir = newTempDir()
        try {
            val raw = dir.toString() + (if (isWindows) "\\" else "/")
            val result = LocalUpdateDir.parse(raw)
            assertTrue("存在目录应 Ok，实际 $result", result is LocalUpdateDir.DirCheck.Ok)
            val normalized = (result as LocalUpdateDir.DirCheck.Ok).dir
            assertEquals("尾分隔符应被归一去掉", dir.toAbsolutePath().normalize().toString(), normalized.toString())
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun parse_dotDotSegments_normalized() {
        val dir = newTempDir()
        val inner = dir.resolve("a").resolve("b")
        Files.createDirectories(inner)
        try {
            // dir/a/b/../b 归一 = dir/a/b（真实存在的目录；探针实证 dir/a/b/../a/b 归一为 dir/a/a/b 不存在）
            val raw = inner.resolve("..").resolve("b").toString()
            val result = LocalUpdateDir.parse(raw)
            assertTrue("含 . / .. 的真实路径应归一为 Ok，实际 $result (raw=$raw)", result is LocalUpdateDir.DirCheck.Ok)
            assertEquals(inner.toAbsolutePath().normalize().toString(), (result as LocalUpdateDir.DirCheck.Ok).dir.toString())
        } finally {
            deleteRecursively(dir)
        }
    }

    /** 递归删除临时树（JUnit4 无 @TempDir；deleteIfExists 只删得动空目录）。 */
    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach { p ->
            runCatching { Files.deleteIfExists(p) }
        }
    }

    @Test
    fun parse_chineseAndSpaceDir_ok() {
        val base = newTempDir()
        val dir = base.resolve("插件包 目录")
        Files.createDirectories(dir)
        try {
            val result = LocalUpdateDir.parse(dir.toString())
            assertTrue("中文与空格路径应可用，实际 $result", result is LocalUpdateDir.DirCheck.Ok)
            assertEquals(dir.toAbsolutePath().normalize().toString(), (result as LocalUpdateDir.DirCheck.Ok).dir.toString())
        } finally {
            Files.deleteIfExists(dir)
            Files.deleteIfExists(base)
        }
    }

    // ---------- 可读性（POSIX 门控） ----------

    @Test
    fun parse_unreadableDir_notReadable() {
        if (!isPosix) return // Windows 的 isReadable 语义不同（ACL），不在此测
        val dir = newTempDir()
        try {
            Files.setPosixFilePermissions(dir, java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission::class.java))
            try {
                assertEquals(LocalUpdateDir.DirCheck.NotReadable, LocalUpdateDir.parse(dir.toString()))
            } finally {
                // 恢复可读以便清理（root 下 chmod 000 仍可读的环境里上面那行断言可能不成立——跳过即视为环境受限）
                Files.setPosixFilePermissions(
                    dir,
                    java.util.EnumSet.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
            }
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    // ---------- 插件目录守卫（D-6） ----------

    @Test
    fun parse_insidePluginsDir_rejected() {
        val pluginsDir = newTempDir()
        val inner = pluginsDir.resolve("some-plugin")
        Files.createDirectories(inner)
        try {
            val result = LocalUpdateDir.parse(inner.toString(), pluginsDir = pluginsDir)
            assertEquals(LocalUpdateDir.DirCheck.InsidePluginsDir, result)
            // 目录本身也拒绝（不只是子目录）
            assertEquals(LocalUpdateDir.DirCheck.InsidePluginsDir, LocalUpdateDir.parse(pluginsDir.toString(), pluginsDir = pluginsDir))
        } finally {
            Files.deleteIfExists(inner)
            Files.deleteIfExists(pluginsDir)
        }
    }

    @Test
    fun parse_outsidePluginsDir_ok() {
        val pluginsDir = newTempDir()
        val dir = newTempDir()
        try {
            val result = LocalUpdateDir.parse(dir.toString(), pluginsDir = pluginsDir)
            assertTrue("非插件目录应放行，实际 $result", result is LocalUpdateDir.DirCheck.Ok)
        } finally {
            Files.deleteIfExists(dir)
            Files.deleteIfExists(pluginsDir)
        }
    }

    @Test
    fun parse_pluginsDirGuard_null_skipped() {
        // null = 未配置守卫（纯单测/平台 API 读取失败的兜底），不因缺基准而误判
        val dir = newTempDir()
        try {
            assertTrue(LocalUpdateDir.parse(dir.toString(), pluginsDir = null) is LocalUpdateDir.DirCheck.Ok)
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    // ---------- 大小写（Windows 插件目录守卫语义） ----------

    @Test
    fun isInside_caseInsensitiveOnWindows() {
        val base = Path.of(if (isWindows) "C:\\Base\\Dir" else "/base/dir")
        val probe = Path.of(if (isWindows) "C:\\base\\dir\\child" else "/base/dir/child")
        assertEquals("WindowsPath.startsWith 大小写不敏感、Unix 区分", isWindows, LocalUpdateDir.isInside(probe, base))
    }
}
