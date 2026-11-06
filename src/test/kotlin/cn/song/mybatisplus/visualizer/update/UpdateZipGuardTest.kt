package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.util.io.FileUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 自更新包解压有界化守卫表驱动测试（T-UpdateZipBounds，O-29）。
 *
 * 四类形态（计划 §四.1）：正常包通过 / 头谎报（声明小实际大）拦截 / 条目数超限拦截 /
 * 累计超限拦截 + 编排层接线（守卫拒绝不触 install）。全部程序化构造 zip，不依赖真实
 * 发版产物；谎报形态经手工构造 zip 字节（CEN 头 usize 字段写小、压缩数据保持真量，
 * JVM zip 读取器不按声明截断——正是被防御的攻击面）。
 */
class UpdateZipGuardTest {

    // ---------------- 正常包 ----------------

    @Test
    fun normalPackagePasses() {
        // 真实发行包布局：lib/ 内插件 jar + 说明物（beta.4 实测 5 条目 7.3MB）
        withTempZip { zip ->
            ZipOutputStream(zip.toFile().outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("mybatis-plus-sql-visualizer/lib/plugin.jar"))
                z.write(ByteArray(64 * 1024) { (it % 251).toByte() })
                z.closeEntry()
                z.putNextEntry(ZipEntry("mybatis-plus-sql-visualizer/lib/README.txt"))
                z.write("update notes".toByteArray())
                z.closeEntry()
            }
            assertEquals(UpdateZipGuard.GuardResult.Ok, UpdateZipGuard.check(zip))
        }
    }

    // ---------------- 头谎报（声明小实际大） ----------------

    @Test
    fun lyingHeaderIsRejected() {
        // CEN usize 声明 16 字节，实际 deflate 流解出 64KB——本机 JBR17 实测以
        // 「读超声明+1」命中截断读取（部分 JDK 以读取异常暴露），守卫两条路都判拒
        withTempZip { zip ->
            zip.toFile().writeBytes(
                manualZip("bomb.bin", actualBytes = 64 * 1024, declaredUncompressed = 16),
            )
            val result = UpdateZipGuard.check(zip)
            assertTrue("谎报头必须拦截: $result", result is UpdateZipGuard.GuardResult.Reject)
            assertTrue(
                "原因含头谎报语义: ${(result as UpdateZipGuard.GuardResult.Reject).reason}",
                (result as UpdateZipGuard.GuardResult.Reject).reason.contains("lies about size"),
            )
        }
    }

    // ---------------- 条目数超限 ----------------

    @Test
    fun tooManyEntriesIsRejected() {
        withTempZip { zip ->
            ZipOutputStream(zip.toFile().outputStream().buffered()).use { z ->
                repeat(UpdateZipGuard.MAX_ENTRIES + 1) { i ->
                    z.putNextEntry(ZipEntry("e$i.txt"))
                    z.write(byteArrayOf(i.toByte()))
                    z.closeEntry()
                }
            }
            val result = UpdateZipGuard.check(zip)
            assertTrue(result is UpdateZipGuard.GuardResult.Reject)
            assertTrue(
                "原因含条目数与实际值",
                (result as UpdateZipGuard.GuardResult.Reject).reason.contains("too many entries"),
            )
        }
    }

    // ---------------- 单条目 / 累计超限（CEN 声明量拦截，未读流先拒） ----------------

    @Test
    fun declaredSingleEntryOverCapIsRejected() {
        withTempZip { zip ->
            zip.toFile().writeBytes(
                manualZip("huge.bin", actualBytes = 16, declaredUncompressed = UpdateZipGuard.MAX_SINGLE_UNCOMPRESSED_BYTES + 1),
            )
            val result = UpdateZipGuard.check(zip)
            assertTrue(result is UpdateZipGuard.GuardResult.Reject)
            assertTrue((result as UpdateZipGuard.GuardResult.Reject).reason.contains("single-entry cap"))
        }
    }

    @Test
    fun declaredTotalOverCapIsRejected() {
        // 三条目各声明 45MB（均低于单条目上限 64MB），累计 135MB > 128MB——中央目录阶段即拒
        withTempZip { zip ->
            zip.toFile().writeBytes(
                concatManualZips(
                    "a.bin" to 45L * 1024 * 1024,
                    "b.bin" to 45L * 1024 * 1024,
                    "c.bin" to 45L * 1024 * 1024,
                ),
            )
            val result = UpdateZipGuard.check(zip)
            assertTrue("累计声明超限必须拦截: $result", result is UpdateZipGuard.GuardResult.Reject)
            assertTrue((result as UpdateZipGuard.GuardResult.Reject).reason.contains("total cap"))
        }
    }

    // ---------------- T-SAF-06（update.UpdateZipGuard.entry-name-platform-delegated） ----------------
    // 条目名恶意形态（zip-slip / 盘符绝对路径 / NTFS ADS / Windows 保留设备名 / 尾点尾空格）：
    // 改前守卫只读大小与数量、从不校验 entry.name——这些包全部放行（先红：断言 Reject 失败，
    // 实际返回 Ok），清洗被完全委托给未验证的平台解压器。修复后插件侧先行拒绝，与平台行为无关。

    @Test
    fun traversalEntryNameIsRejected() {
        withTempZip { zip ->
            ZipOutputStream(zip.toFile().outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("../zipslip-probe.txt"))
                z.write("probe".toByteArray())
                z.closeEntry()
            }
            val result = UpdateZipGuard.check(zip)
            assertTrue(".. 遍历条目必须拦截: $result", result is UpdateZipGuard.GuardResult.Reject)
            assertTrue((result as UpdateZipGuard.GuardResult.Reject).reason.contains(".."))
        }
    }

    @Test
    fun driveQualifiedAbsoluteEntryNameIsRejected() {
        withTempZip { zip ->
            ZipOutputStream(zip.toFile().outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("C:\\absolute-probe.txt"))
                z.write("probe".toByteArray())
                z.closeEntry()
            }
            val result = UpdateZipGuard.check(zip)
            assertTrue("盘符绝对路径条目必须拦截: $result", result is UpdateZipGuard.GuardResult.Reject)
        }
    }

    @Test
    fun alternateDataStreamColonEntryNameIsRejected() {
        withTempZip { zip ->
            ZipOutputStream(zip.toFile().outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("probe:ads.txt"))
                z.write("probe".toByteArray())
                z.closeEntry()
            }
            val result = UpdateZipGuard.check(zip)
            assertTrue("NTFS ADS（冒号）条目必须拦截: $result", result is UpdateZipGuard.GuardResult.Reject)
        }
    }

    @Test
    fun windowsReservedDeviceNameEntryIsRejected() {
        withTempZip { zip ->
            ZipOutputStream(zip.toFile().outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("CON.txt"))
                z.write("probe".toByteArray())
                z.closeEntry()
            }
            val result = UpdateZipGuard.check(zip)
            assertTrue("Windows 保留设备名条目必须拦截: $result", result is UpdateZipGuard.GuardResult.Reject)
            assertTrue(
                (result as UpdateZipGuard.GuardResult.Reject).reason.contains("reserved"),
            )
        }
    }

    @Test
    fun trailingDotOrSpaceEntryNameIsRejected() {
        // Windows 会剥掉路径段尾部的点/空格（写盘时名字被改写/重定向）
        withTempZip { zip ->
            ZipOutputStream(zip.toFile().outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("probe.txt."))
                z.write("probe".toByteArray())
                z.closeEntry()
            }
            val result = UpdateZipGuard.check(zip)
            assertTrue("尾部点条目必须拦截: $result", result is UpdateZipGuard.GuardResult.Reject)
        }
        withTempZip { zip ->
            ZipOutputStream(zip.toFile().outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("probe.txt "))
                z.write("probe".toByteArray())
                z.closeEntry()
            }
            val result = UpdateZipGuard.check(zip)
            assertTrue("尾部空格条目必须拦截: $result", result is UpdateZipGuard.GuardResult.Reject)
        }
    }

    @Test
    fun nestedTraversalSegmentIsRejected() {
        // 深层目录内的 .. 段同样拒绝（不局限于首段）
        withTempZip { zip ->
            ZipOutputStream(zip.toFile().outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("lib/ok/../../escape.txt"))
                z.write("probe".toByteArray())
                z.closeEntry()
            }
            val result = UpdateZipGuard.check(zip)
            assertTrue("嵌套 .. 段必须拦截: $result", result is UpdateZipGuard.GuardResult.Reject)
        }
    }

    // ---------------- 编排层接线：守卫拒绝不触 install ----------------

    @Test
    fun downloadAndInstallRejectsGuardFailureWithoutInstall() {
        var installCalled = false
        val result = PluginUpdateRunner.downloadAndInstall(
            UpdateManifest(
                version = "2026.9.19",
                downloadUrl = "https://example.com/p.zip",
                changeNotes = null, publishDate = null, sinceBuild = null,
            ),
            null,
            download = { _, target ->
                target.toFile().writeBytes(
                    manualZip("bomb.bin", actualBytes = 64 * 1024, declaredUncompressed = 16),
                )
            },
            install = { installCalled = true; PluginUpdateRunner.InstallResult.InstalledRestartRequired },
        )
        assertTrue(result is PluginUpdateRunner.InstallResult.Failed)
        assertFalse("守卫拒绝不得触达 install", installCalled)
        assertTrue(
            "失败信息含拒绝原因",
            (result as PluginUpdateRunner.InstallResult.Failed).message.contains("update package rejected"),
        )
    }

    // ---------------- helpers ----------------

    private fun withTempZip(block: (Path) -> Unit) {
        val f = FileUtil.createTempFile("mpvs-zipguard-", ".zip", false)
        try {
            block(f.toPath())
        } finally {
            FileUtil.delete(f)
        }
    }

    /**
     * 手工构造单条目 zip（最小 LOC/CEN/EOCD）：压缩数据真实（[actualBytes] 个字节 deflate），
     * CEN 的 usize 字段写 [declaredUncompressed]（谎报形态）；CRC/压缩尺寸保持真值
     * （谎报只动 usize——JVM 读取器按压缩流实际解压输出，不按声明截断）。
     */
    private fun manualZip(name: String, actualBytes: Int, declaredUncompressed: Long): ByteArray {
        val raw = ByteArray(actualBytes) { (it % 251).toByte() }
        // nowrap=true：ZIP 条目存 raw deflate（默认 Deflater() 是 zlib 包装流，zip 内非法）
        val deflater = Deflater(6, true)
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            compressed.write(buf, 0, n)
        }
        val data = compressed.toByteArray()
        val crc = java.util.zip.CRC32().apply { update(raw) }.value
        val nameBytes = name.toByteArray(Charsets.US_ASCII)

        val out = ByteArrayOutputStream()
        // LOC（usize 同步谎报，读取以 CEN 为准）
        out.writeLE(0x04034b50, 4)
        out.writeLE(20, 2)          // version needed
        out.writeLE(0, 2)           // flags
        out.writeLE(8, 2)           // method = deflate
        out.writeLE(0, 2); out.writeLE(0x21, 2) // time/date
        out.writeLE(crc, 4)
        out.writeLE(data.size, 4)   // csize（真值）
        out.writeLE(declaredUncompressed, 4) // usize（谎报）
        out.writeLE(nameBytes.size, 2)
        out.writeLE(0, 2)
        out.write(nameBytes)
        out.write(data)
        val cenOffset = out.size()
        // CEN
        out.writeLE(0x02014b50, 4)
        out.writeLE(20, 2)          // version made
        out.writeLE(20, 2)          // version needed
        out.writeLE(0, 2)           // flags
        out.writeLE(8, 2)           // method
        out.writeLE(0, 2); out.writeLE(0x21, 2)
        out.writeLE(crc, 4)
        out.writeLE(data.size, 4)   // csize（真值）
        out.writeLE(declaredUncompressed, 4) // usize（谎报）
        out.writeLE(nameBytes.size, 2)
        out.writeLE(0, 2); out.writeLE(0, 2); out.writeLE(0, 2); out.writeLE(0, 2)
        out.writeLE(0, 4)
        out.writeLE(0, 4)
        out.write(nameBytes)
        val cenSize = out.size() - cenOffset
        // EOCD
        out.writeLE(0x06054b50, 4)
        out.writeLE(0, 2); out.writeLE(0, 2)
        out.writeLE(1, 2); out.writeLE(1, 2)
        out.writeLE(cenSize, 4)
        out.writeLE(cenOffset, 4)
        out.writeLE(0, 2)
        return out.toByteArray()
    }

    /** 多条目谎报总量形态：N 个 CEN 顺序排在 N 段数据后（EOCD 计 N）。 */
    private fun concatManualZips(vararg entries: Pair<String, Long>): ByteArray {
        // 复用 manualZip 的单条目构造做字节拼装过于繁琐，直接内联双条目构造
        val raw = ByteArray(16) { it.toByte() }
        // nowrap 同上：raw deflate
        val deflater = Deflater(6, true)
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(256)
        while (!deflater.finished()) {
            compressed.write(buf, 0, deflater.deflate(buf))
        }
        val data = compressed.toByteArray()
        val crc = java.util.zip.CRC32().apply { update(raw) }.value

        fun entryBlock(name: String, declared: Long, locOffset: Int): ByteArray {
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            val b = ByteArrayOutputStream()
            b.writeLE(0x02014b50, 4)
            b.writeLE(20, 2); b.writeLE(20, 2); b.writeLE(0, 2); b.writeLE(8, 2)
            b.writeLE(0, 2); b.writeLE(0x21, 2)
            b.writeLE(crc, 4)
            b.writeLE(data.size, 4)
            b.writeLE(declared, 4)
            b.writeLE(nameBytes.size, 2)
            b.writeLE(0, 2); b.writeLE(0, 2); b.writeLE(0, 2); b.writeLE(0, 2)
            b.writeLE(0, 4)
            b.writeLE(locOffset, 4)
            b.write(nameBytes)
            return b.toByteArray()
        }

        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        for ((name, _) in entries) {
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            offsets += out.size()
            out.writeLE(0x04034b50, 4)
            out.writeLE(20, 2); out.writeLE(0, 2); out.writeLE(8, 2)
            out.writeLE(0, 2); out.writeLE(0x21, 2)
            out.writeLE(crc, 4)
            out.writeLE(data.size, 4)
            out.writeLE(16, 4) // LOC usize 真值（CEN 才是谎报面）
            out.writeLE(nameBytes.size, 2)
            out.writeLE(0, 2)
            out.write(nameBytes)
            out.write(data)
        }
        val cenOffset = out.size()
        val cenBlocks = entries.mapIndexed { i, e -> entryBlock(e.first, e.second, offsets[i]) }
        cenBlocks.forEach { out.write(it) }
        val cenSize = cenBlocks.sumOf { it.size }
        out.writeLE(0x06054b50, 4)
        out.writeLE(0, 2); out.writeLE(0, 2)
        out.writeLE(entries.size, 2); out.writeLE(entries.size, 2)
        out.writeLE(cenSize, 4)
        out.writeLE(cenOffset, 4)
        out.writeLE(0, 2)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLE(value: Number, bytes: Int) {
        var v = value.toLong()
        repeat(bytes) {
            write((v and 0xFF).toInt())
            v = v shr 8
        }
    }
}
