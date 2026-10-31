package cn.song.mybatisplus.visualizer.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * T-SelfUpdateRollback（O-150）W1/W2 核心逻辑测试：升级前快照（zip 打包/manifest/sha256/
 * 幂等/清理）、回滚调度（命令三元组/忽略联动/诚实拒绝）、接线守卫（备份发生在 install 之前）。
 *
 * 反向探针约定（S2）：摘掉 sha256 校验 → 篡改用例红；摘掉 ignoreWriter 联动 → 忽略联动用例红；
 * 摘掉 downloadAndInstall 的 backupCurrent 接线 → 源码守卫用例红。
 */
class UpdateBackupStoreTest {

    private lateinit var tmp: Path
    private lateinit var pluginsDir: Path
    private lateinit var pluginDir: Path
    private lateinit var backupDir: Path

    private val resolver = object : UpdateBackupStore.DirResolver {
        override fun backupDir(): Path = backupDir
        override fun pluginsDir(): Path = pluginsDir
        override fun currentPluginDir(): Path = pluginDir
    }

    /** 命令收集 runner（断言 deleteTarget/zip/unzipTarget 三元组与次数）。 */
    private val scheduled = ArrayList<Triple<Path, Path, Path>>()
    private val runner = UpdateBackupStore.RestoreCommandRunner { d, z, u -> scheduled.add(Triple(d, z, u)) }

    private val ignoredVersions = ArrayList<String>()
    private val ignoreWriter: (String) -> Unit = { ignoredVersions.add(it) }

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("mpsv-rollback-test")
        pluginsDir = tmp.resolve("plugins")
        pluginDir = pluginsDir.resolve("mybatis-plus-sql-visualizer")
        backupDir = tmp.resolve("backup")
        Files.createDirectories(pluginDir.resolve("lib"))
        Files.write(pluginDir.resolve("lib/plugin.jar"), byteArrayOf(1, 2, 3, 4, 5))
        Files.write(pluginDir.resolve("plugin.xml"), "<idea-plugin><id>x</id></idea-plugin>".toByteArray())
        scheduled.clear()
        ignoredVersions.clear()
    }

    @After
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ------------------------------------------------------------------
    // W1：快照
    // ------------------------------------------------------------------

    @Test
    fun test备份打包_顶层目录名与manifest与sha256() {
        assertTrue("目录在场应备份成功", UpdateBackupStore.backupCurrent(resolver))
        val info = UpdateBackupStore.currentBackup(resolver)
        assertNotNull("备份应可用", info)
        assertEquals("mybatis-plus-sql-visualizer", info!!.pluginDirName)
        assertEquals(PluginVersion.current(), info.fromVersion)
        val zip = UpdateBackupStore.backupZipPath(backupDir, info)
        assertTrue(Files.isRegularFile(zip))
        assertEquals(UpdateBackupStore.sha256(zip), info.sha256)
        // zip 顶层 entry = 插件目录名（UnzipCommand 到 plugins 根即还原——T-M28.5 同构形态）
        ZipFile(zip.toFile()).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toList()
            assertTrue(names.any { it.startsWith("mybatis-plus-sql-visualizer/") })
            assertTrue(names.any { it.endsWith("plugin.jar") })
        }
    }

    @Test
    fun test同版本幂等_二次调用不重写() {
        assertTrue(UpdateBackupStore.backupCurrent(resolver))
        val zip = UpdateBackupStore.backupZipPath(
            backupDir,
            UpdateBackupStore.currentBackup(resolver)!!,
        )
        val mtime1 = Files.getLastModifiedTime(zip)
        Thread.sleep(30)
        assertTrue("幂等复用应返回 true", UpdateBackupStore.backupCurrent(resolver))
        assertEquals("同版本不重写 zip", mtime1, Files.getLastModifiedTime(zip))
    }

    @Test
    fun test插件目录不在场_跳过备份返回false() {
        Files.walk(pluginDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        assertFalse(UpdateBackupStore.backupCurrent(resolver))
        assertNull(UpdateBackupStore.currentBackup(resolver))
    }

    // ------------------------------------------------------------------
    // 可用性判定（sha256 强校验——反向探针点）
    // ------------------------------------------------------------------

    @Test
    fun test备份zip被篡改_校验失败视为不可用() {
        assertTrue(UpdateBackupStore.backupCurrent(resolver))
        val zip = UpdateBackupStore.backupZipPath(
            backupDir,
            UpdateBackupStore.currentBackup(resolver)!!,
        )
        // 篡改一个字节（保 zip 结构合法，仅内容变——sha256 必变）。
        // 先读完并关闭 ZipFile 句柄再重写——Windows 上句柄未释放时 Files.move 必被锁
        // （testFast 全量首跑即此形态翻车，单跑侥幸绿不可作为判据）
        val swapped = ZipFile(zip.toFile()).use { zf ->
            val entry = zf.getEntry("mybatis-plus-sql-visualizer/plugin.xml")
            zf.getInputStream(entry).readBytes().copyOf().also { it[0] = 'X'.code.toByte() }
        }
        rewriteEntry(zip, "mybatis-plus-sql-visualizer/plugin.xml", swapped)
        assertNull("sha256 不符必须判不可用", UpdateBackupStore.currentBackup(resolver))
    }

    @Test
    fun testmanifest缺失_不可用() {
        assertTrue(UpdateBackupStore.backupCurrent(resolver))
        Files.delete(backupDir.resolve("manifest.json"))
        assertNull(UpdateBackupStore.currentBackup(resolver))
    }

    // ------------------------------------------------------------------
    // W2：回滚调度
    // ------------------------------------------------------------------

    @Test
    fun test回滚调度_命令三元组与忽略联动() {
        assertTrue(UpdateBackupStore.backupCurrent(resolver))
        val result = UpdateBackupStore.scheduleRestore(
            resolver = resolver,
            runner = runner,
            currentVersionProvider = { "9.9.9-test" },
            ignoreWriter = ignoreWriter,
        )
        assertTrue("备份在场应调度成功", result is UpdateBackupStore.RestoreResult.Scheduled)
        assertEquals("9.9.9-test", (result as UpdateBackupStore.RestoreResult.Scheduled).ignoredVersion)
        assertEquals("恰调度一次", 1, scheduled.size)
        val (deleteTarget, zip, unzipTarget) = scheduled[0]
        assertEquals("删除目标=当前插件目录", pluginDir, deleteTarget)
        assertEquals("解压目标=plugins 根（与升级脚本同构）", pluginsDir, unzipTarget)
        assertTrue(zip.fileName.toString().endsWith(".zip"))
        assertEquals("当前版本必须入忽略表（防回滚后被拉回）", listOf("9.9.9-test"), ignoredVersions)
    }

    @Test
    fun test无备份回滚_诚实拒绝不调度() {
        val result = UpdateBackupStore.scheduleRestore(
            resolver = resolver,
            runner = runner,
            currentVersionProvider = { "9.9.9-test" },
            ignoreWriter = ignoreWriter,
        )
        assertTrue(result is UpdateBackupStore.RestoreResult.NoBackup)
        assertTrue("绝半恢复：无备份零调度", scheduled.isEmpty())
        assertTrue("无备份不写忽略表", ignoredVersions.isEmpty())
    }

    // ------------------------------------------------------------------
    // zip 往返
    // ------------------------------------------------------------------

    @Test
    fun testzipDirectory往返一致() {
        val zip = backupDir.resolve("rt.zip")
        Files.createDirectories(backupDir)
        UpdateBackupStore.zipDirectory(pluginDir, zip)
        val out = tmp.resolve("unzipped")
        Files.createDirectories(out)
        ZipFile(zip.toFile()).use { zf ->
            zf.entries().asSequence().filter { !it.isDirectory }.forEach { e: ZipEntry ->
                val target = out.resolve(e.name)
                Files.createDirectories(target.parent)
                zf.getInputStream(e).use { input -> Files.copy(input, target) }
            }
        }
        assertEquals(
            Files.readAllBytes(pluginDir.resolve("lib/plugin.jar")).toList(),
            Files.readAllBytes(out.resolve("mybatis-plus-sql-visualizer/lib/plugin.jar")).toList(),
        )
        assertEquals(
            Files.readAllBytes(pluginDir.resolve("plugin.xml")).toList(),
            Files.readAllBytes(out.resolve("mybatis-plus-sql-visualizer/plugin.xml")).toList(),
        )
    }

    // ------------------------------------------------------------------
    // 接线守卫（源码级，对齐 ErrorPositionWiringGuardTest 先例）
    // ------------------------------------------------------------------

    @Test
    fun test升级前备份接线守卫_backupCurrent必须先于install() {
        val source = Path.of(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/update/PluginUpdateRunner.kt",
        )
        assertTrue("源码文件应在（cwd=项目根）: $source", Files.isRegularFile(source))
        val text = Files.readString(source)
        val backupAt = text.indexOf("UpdateBackupStore.backupCurrent()")
        val installAt = text.indexOf("install(temp.toPath())")
        assertTrue("downloadAndInstall 必须调用 UpdateBackupStore.backupCurrent()（W1 接线）", backupAt >= 0)
        assertTrue("install seam 调用应在场", installAt >= 0)
        assertTrue(
            "备份必须发生在 install 之前（平台安装阶段会物理删旧目录，晚了就没了——P-1 定案）",
            backupAt < installAt,
        )
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 重写 zip 内单个条目内容（保持其余条目原样——制造 sha256 漂移的合法 zip）。 */
    private fun rewriteEntry(zip: Path, entryName: String, newBytes: ByteArray) {
        val tmpZip = zip.resolveSibling(zip.fileName.toString() + ".rt")
        java.util.zip.ZipOutputStream(Files.newOutputStream(tmpZip)).use { zos ->
            ZipFile(zip.toFile()).use { zf ->
                zf.entries().asSequence().forEach { e ->
                    zos.putNextEntry(ZipEntry(e.name))
                    if (e.name == entryName) {
                        zos.write(newBytes)
                    } else if (!e.isDirectory) {
                        zf.getInputStream(e).use { it.copyTo(zos) }
                    }
                    zos.closeEntry()
                }
            }
        }
        Files.move(tmpZip, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
