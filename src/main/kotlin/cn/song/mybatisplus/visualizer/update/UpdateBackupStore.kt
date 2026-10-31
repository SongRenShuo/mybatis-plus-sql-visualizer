package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.util.Properties
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 升级前快照（T-SelfUpdateRollback W1，O-150）：把「即将被升级动作消灭的当前版本」留存为
 * 可校验、可恢复的 zip——DBX 数据网格回滚「保留原态→反向操作」两步的第一步。
 *
 * **时机契约（P-1 证伪后的定案）**：备份必须发生在 `DynamicInstallSupport.install` 调
 * `unloadPlugin` 之前——平台三条路径（动态卸载 `FileUtil.delete(pluginPath)`、`unpackPlugin`
 * 解压前 `deleteRecursively`、重启脚本 `DeleteCommand`）全在安装阶段物理删除旧目录，
 * 「安装成功后再留存」永远来不及。本仓统一收口在 [PluginUpdateRunner.downloadAndInstall]
 * 的 zip 守卫通过后、install seam 调用前（运行期，旧目录必然在场）。
 *
 * 备份形态定案为 **zip 文件**（平台 `CopyCommand` 只收 `isRegularFile`，目录不可拷贝）；
 * 顶层 entry 为插件目录名——恢复用 `DeleteCommand(当前目录) + UnzipCommand(备份, plugins根)`，
 * 与升级脚本（[PlatformPluginInstaller.installRestartScript]）完全同构，复用 T-M28.5
 * 「解压到根」的已验证形态。
 *
 * 失败策略 **fail-open**（与脱敏 fail-closed 成对照，见 code-standards M1）：快照失败只记
 * 日志返回 false，不阻断正常升级——代价是「本轮无回滚源」，由 [W2][currentBackup] 的
 * 入口态如实披露（备份缺失时按钮置灰并说明），绝不静默假装可回滚。
 *
 * headless 可测性：路径解析与命令调度经参数注入（[dirResolver]/[commandRunner]），
 * zip 打包/manifest/sha256 全纯 java.nio。
 */
object UpdateBackupStore {

    private val LOG = Logger.getInstance(UpdateBackupStore::class.java)

    private const val BACKUP_DIR = "update-backup"
    private const val MANIFEST = "manifest.json"
    private const val ZIP_PREFIX = "rollback-"

    /** 备份登记项（磁盘 manifest.json 的内存形态；字段见 [toJson]）。 */
    data class BackupInfo(
        /** 被备份的版本号（升级前的旧版本）。 */
        val fromVersion: String,
        /** 备份 zip 的 sha256（hex），恢复前强校验。 */
        val sha256: String,
        /** 创建时间（epoch millis）。 */
        val createdAt: Long,
        /** 插件目录名（zip 顶层 entry 名，恢复后目录名由此还原）。 */
        val pluginDirName: String,
    )

    /** 路径解析 seam（生产=PathManager；测试注入临时目录）。getPluginTempPath 返回 String、getPluginsDir 返回 Path（243 实测）。 */
    interface DirResolver {
        fun backupDir(): Path
        fun pluginsDir(): Path
        fun currentPluginDir(): Path
    }

    /** 命令调度 seam（生产=StartupActionScriptManager；测试收集断言）。 */
    fun interface RestoreCommandRunner {
        fun scheduleDeleteAndUnzip(deleteTarget: Path, zip: Path, unzipTarget: Path)
    }

    private val productionResolver = object : DirResolver {
        override fun backupDir(): Path = java.nio.file.Paths.get(PathManager.getPluginTempPath()).resolve(BACKUP_DIR)
        override fun pluginsDir(): Path = PathManager.getPluginsDir()
        override fun currentPluginDir(): Path = PlatformPluginInstaller.pluginDirPath()
    }

    private val productionRunner = RestoreCommandRunner { deleteTarget, zip, unzipTarget ->
        com.intellij.ide.startup.StartupActionScriptManager.addActionCommand(
            com.intellij.ide.startup.StartupActionScriptManager.DeleteCommand(deleteTarget),
        )
        com.intellij.ide.startup.StartupActionScriptManager.addActionCommand(
            com.intellij.ide.startup.StartupActionScriptManager.UnzipCommand(zip, unzipTarget),
        )
    }

    // ------------------------------------------------------------------
    // W1：升级前快照
    // ------------------------------------------------------------------

    /**
     * 备份当前安装（幂等）：目录在场且与已登记备份不同版才打包；同版本重复升级重试不重复写。
     *
     * @return true=备份就绪（新打包或既有复用）；false=失败（fail-open：记日志，升级继续）。
     */
    fun backupCurrent(resolver: DirResolver = productionResolver): Boolean = runCatching {
        val pluginDir = resolver.currentPluginDir()
        if (!Files.isDirectory(pluginDir)) {
            LOG.info("UpdateBackupStore: 插件目录不存在，跳过备份（path=$pluginDir）")
            return false
        }
        val version = PluginVersion.current()
        if (version.isNullOrBlank()) {
            LOG.warn("UpdateBackupStore: 当前版本不可读，跳过备份")
            return false
        }
        val dir = resolver.backupDir()
        Files.createDirectories(dir)
        val manifestPath = dir.resolve(MANIFEST)

        // 幂等：同版本且既有 zip 校验通过 → 复用（升级重试场景）
        readManifest(manifestPath)?.let { existing ->
            if (existing.fromVersion == version) {
                val zip = backupZipPath(dir, existing)
                if (Files.isRegularFile(zip) && sha256(zip) == existing.sha256) {
                    LOG.info("UpdateBackupStore: 同版本备份已在位，复用（version=$version）")
                    return true
                }
            }
        }

        // 打包：zip 顶层 = 插件目录名（与发行 zip 同形态，UnzipCommand 到 plugins 根即还原）
        val zip = dir.resolve("$ZIP_PREFIX${pluginDir.fileName}-$version.zip")
        zipDirectory(pluginDir, zip)
        val info = BackupInfo(
            fromVersion = version,
            sha256 = sha256(zip),
            createdAt = System.currentTimeMillis(),
            pluginDirName = pluginDir.fileName.toString(),
        )
        writeManifest(manifestPath, info)

        // 清理：只留这一份（旧 manifest 指向的 zip 一并删）
        cleanupStale(dir, keep = zip)
        LOG.info("UpdateBackupStore: 已备份 version=$version → $zip（sha256=${info.sha256.take(12)}…）")
        true
    }.getOrElse { e ->
        LOG.warn("UpdateBackupStore: 备份失败（fail-open，本轮无回滚源）：$e")
        false
    }

    // ------------------------------------------------------------------
    // W2：回滚
    // ------------------------------------------------------------------

    /** 当前可用备份（manifest 在位 + zip 在场 + sha256 一致）；三者任一不满足返回 null。 */
    fun currentBackup(resolver: DirResolver = productionResolver): BackupInfo? = runCatching {
        val manifestPath = resolver.backupDir().resolve(MANIFEST)
        val info = readManifest(manifestPath) ?: return null
        val zip = backupZipPath(resolver.backupDir(), info)
        if (!Files.isRegularFile(zip)) return null
        if (sha256(zip) != info.sha256) {
            LOG.warn("UpdateBackupStore: 备份 zip 校验失败（version=${info.fromVersion}），视为不可用")
            return null
        }
        info
    }.getOrNull()

    /** 回滚结果（W2 入口呈现用）。 */
    sealed interface RestoreResult {
        /** 已调度恢复命令，重启后生效；[ignoredVersion]=已压入忽略表的版本号。 */
        data class Scheduled(val ignoredVersion: String) : RestoreResult

        /** 无可用回滚源（备份缺失/校验失败/版本不可读）——诚实降级，绝不半恢复。 */
        data class NoBackup(val reason: String) : RestoreResult

        /** 调度失败（IO 异常等）。 */
        data class Failed(val reason: String) : RestoreResult
    }

    /**
     * 调度回滚上一版：强校验备份 → 排队「删当前 + 解备份」（与升级脚本同构，重启执行）→
     * 把当前版本压入忽略表（[VersionIgnore]，防回滚后又被更新提醒拉回）。
     *
     * 调度成功即视为完成（命令在重启时由平台执行）；**不做运行期自动回滚**——插件类加载
     * 失败时本代码没有执行者（计划 W3 边界声明）。
     */
    fun scheduleRestore(
        resolver: DirResolver = productionResolver,
        runner: RestoreCommandRunner = productionRunner,
        currentVersionProvider: () -> String? = { PluginVersion.current() },
        ignoreWriter: (String) -> Unit = { version ->
            val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
            settings.ignoredUpdateVersions = ignoreVersion(version, settings.ignoredUpdateVersions)
        },
    ): RestoreResult = runCatching {
        val info = currentBackup(resolver)
            ?: return RestoreResult.NoBackup("no verified backup available (missing or checksum mismatch)")
        val currentVersion = currentVersionProvider()
        runner.scheduleDeleteAndUnzip(
            deleteTarget = resolver.currentPluginDir(),
            zip = backupZipPath(resolver.backupDir(), info),
            unzipTarget = resolver.pluginsDir(),
        )
        if (currentVersion != null) {
            runCatching { ignoreWriter(currentVersion) }
                .onFailure { LOG.warn("UpdateBackupStore: 忽略版本写入失败（不影响回滚）：${it.message}") }
        }
        LOG.info("UpdateBackupStore: 已调度回滚 ${info.fromVersion}（当前 $currentVersion 已入忽略表）")
        RestoreResult.Scheduled(currentVersion ?: info.fromVersion)
    }.getOrElse { e ->
        RestoreResult.Failed(e.message ?: e.toString())
    }

    // ------------------------------------------------------------------
    // 纯函数与 IO 工具
    // ------------------------------------------------------------------

    internal fun backupZipPath(dir: Path, info: BackupInfo): Path =
        dir.resolve("$ZIP_PREFIX${info.pluginDirName}-${info.fromVersion}.zip")

    /** manifest 用 JDK Properties（仓内无 JSON 库依赖，先例 SettingsIO 为手写解析——4 个标量字段用 Properties 更稳）。 */
    internal fun readManifest(path: Path): BackupInfo? = runCatching {
        if (!Files.isRegularFile(path)) return null
        val props = Properties().apply { Files.newInputStream(path).use { load(it) } }
        BackupInfo(
            fromVersion = props.getProperty("fromVersion") ?: return null,
            sha256 = props.getProperty("sha256") ?: return null,
            createdAt = props.getProperty("createdAt")?.toLongOrNull() ?: return null,
            pluginDirName = props.getProperty("pluginDirName") ?: return null,
        )
    }.getOrNull()

    internal fun writeManifest(path: Path, info: BackupInfo) {
        val props = Properties().apply {
            setProperty("fromVersion", info.fromVersion)
            setProperty("sha256", info.sha256)
            setProperty("createdAt", info.createdAt.toString())
            setProperty("pluginDirName", info.pluginDirName)
        }
        Files.newOutputStream(path).use { props.store(it, "mpsv update rollback backup (T-SelfUpdateRollback O-150)") }
    }

    /** 只留 [keep] 这份备份（zip 与 manifest 均指向它；其余 zip 删除）。 */
    internal fun cleanupStale(dir: Path, keep: Path) {
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".zip") && it != keep }
                .forEach { stale ->
                    runCatching { Files.deleteIfExists(stale) }
                        .onFailure { LOG.warn("UpdateBackupStore: 旧备份清理失败（$stale）：${it.message}") }
                }
        }
    }

    /** 文件 sha256（hex 小写）。 */
    internal fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 目录打包为 zip：顶层 entry 为源目录名（`dirName/...`），与发行 zip 同构——
     * `UnzipCommand` 解到 plugins 根即可还原 `<plugins>/<dirName>/…` 布局。
     * 写临时文件 + 原子 move，防半截备份被当成可用回滚源。
     */
    internal fun zipDirectory(sourceDir: Path, targetZip: Path) {
        Files.createDirectories(targetZip.parent)
        val tmp = targetZip.resolveSibling(targetZip.fileName.toString() + ".tmp")
        Files.deleteIfExists(tmp)
        try {
            ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
                val rootName = sourceDir.fileName.toString()
                Files.walkFileTree(sourceDir, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): FileVisitResult {
                        val entry = rootName + "/" + sourceDir.relativize(dir).toString().replace('\\', '/') + "/"
                        if (dir != sourceDir) zos.putNextEntry(ZipEntry(entry))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): FileVisitResult {
                        zos.putNextEntry(ZipEntry(rootName + "/" + sourceDir.relativize(file).toString().replace('\\', '/')))
                        Files.copy(file, zos)
                        zos.closeEntry()
                        return FileVisitResult.CONTINUE
                    }
                })
            }
            try {
                Files.move(tmp, targetZip, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, targetZip, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
