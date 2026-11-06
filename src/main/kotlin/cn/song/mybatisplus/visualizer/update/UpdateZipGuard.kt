package cn.song.mybatisplus.visualizer.update

import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 自更新包解压有界化守卫（T-UpdateZipBounds，O-29，DBX b0d30e6 有界解压吸收）。
 *
 * 解压面的两个经典风险与「来源可信」正交：①zip bomb（高压缩比撑爆磁盘/内存）；②条目头
 * 声明大小与实际不符（谎报小头解出巨量字节——JVM zip 读取器不按声明大小截断输出）。
 * 本插件更新 zip 的真解压由平台执行（动态装载/重启安装），插件侧职责是**把异常包拦在
 * 平台解压器之前**：中央目录三重上限 + 逐条目「头声明值 +1 字节」截断读取（读到第
 * declared+1 字节即判定头谎报，快速失败）。
 *
 * 常量按实际包体量级 ×10 余量（2026-09-17 实测 beta.4 包：5 条目 / 解压 7.3MB / 最大
 * 条目插件 jar 3.4MB），超限错误信息含实际值便于调参；未来包体自然增长触发误杀时
 * 调常量即可（单一定义点）。
 */
object UpdateZipGuard {

    /** 条目数上限（实测 5 → ×10 余量取 64）。 */
    const val MAX_ENTRIES = 64

    /** 单条目解压字节上限（实测最大 3.4MB → 64MB）。 */
    const val MAX_SINGLE_UNCOMPRESSED_BYTES = 64L * 1024 * 1024

    /** 全包解压字节上限（实测 7.3MB → 128MB）。 */
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 128L * 1024 * 1024

    /** 守卫结论：[Ok] 放行；[Reject] 携带面向日志与用户的原因（含实际值）。 */
    sealed interface GuardResult {
        data object Ok : GuardResult
        data class Reject(val reason: String) : GuardResult
    }

    /**
     * 有界校验更新包：条目名恶意形态校验（T-SAF-06，见 [entryNameIssue]）+ 中央目录计数/
     * 总量/单条目三重上限 + 逐条目有界读取防头谎报。
     *
     * 头谎报机制说明（java.util.zip 实现差异）：「声明小实际大」的暴露形态因 JDK 而异——
     * 本机 JBR17 实测为读超声明字节（截断读取命中），部分实现按声明截断+CRC 校验以读取
     * 异常暴露——故逐条目读取的**异常**与**读超声明+1**两条路都判拒；声明缺失（-1）的
     * 罕见形态按单条目上限截断读（实际读取仍有界）。全程解压量有界
     * （≤ [MAX_TOTAL_UNCOMPRESSED_BYTES] + 每条目 1 字节探针），恶意包最坏代价 = 有界的
     * CPU/内存；正常包一次全量解压（更新链一次性动作，~10MB 级）。目录条目不读流。
     *
     * @return [GuardResult.Ok] 或 [GuardResult.Reject]（原因可读，含实际值）
     */
    fun check(zip: Path): GuardResult = runCatching {
        ZipFile(zip.toFile()).use { zf ->
            val all = zf.entries().asSequence().toList()
            // T-SAF-06（update.UpdateZipGuard.entry-name-platform-delegated）：条目名插件侧先行校验
            // （目录条目同样过名检——平台解压器会按名建目录）。此前 entry.name 只出现在拒绝消息里，
            // zip-slip/盘符/ADS/保留设备名等清洗被完全委托给未验证的平台解压器；现无论平台行为
            // 如何，恶意名包都拦在解压之前（fail closed）
            for (entry in all) {
                entryNameIssue(entry.name)?.let {
                    return@runCatching GuardResult.Reject("entry '${entry.name}' $it")
                }
            }
            val entries = all.filter { !it.isDirectory }
            if (entries.size > MAX_ENTRIES) {
                return@runCatching GuardResult.Reject(
                    "too many entries: ${entries.size} > $MAX_ENTRIES",
                )
            }
            var total = 0L
            for (entry in entries) {
                val declared = entry.size
                if (declared > MAX_SINGLE_UNCOMPRESSED_BYTES) {
                    return@runCatching GuardResult.Reject(
                        "entry '${entry.name}' declares $declared bytes > single-entry cap " +
                            "$MAX_SINGLE_UNCOMPRESSED_BYTES",
                    )
                }
                total += declared.coerceAtLeast(0)
                if (total > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    return@runCatching GuardResult.Reject(
                        "declared total $total bytes > total cap $MAX_TOTAL_UNCOMPRESSED_BYTES",
                    )
                }
            }
            // 逐条目有界读取：读超「声明值+1」判谎报；读取异常（size/CRC 不一致——ZipFile
            // 按声明截断+CRC 校验，谎报头的实际暴露形态）同样判拒，绝不让异常包过闸
            for (entry in entries) {
                val cap = if (entry.size >= 0) entry.size + 1 else MAX_SINGLE_UNCOMPRESSED_BYTES + 1
                val outcome = runCatching {
                    var read = 0L
                    zf.getInputStream(entry).use { stream ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            read += n
                            if (read > cap) {
                                return@runCatching "lies about size: declared ${entry.size} bytes " +
                                    "but more data follows"
                            }
                        }
                    }
                    null as String?
                }
                outcome.getOrNull()?.let {
                    return@runCatching GuardResult.Reject("entry '${entry.name}' $it")
                }
                outcome.exceptionOrNull()?.let {
                    return@runCatching GuardResult.Reject(
                        "entry '${entry.name}' unreadable (size/CRC mismatch — likely lying " +
                            "header or truncated data): ${it.message}",
                    )
                }
            }
            GuardResult.Ok
        }
    }.getOrElse { GuardResult.Reject("zip guard read failed: ${it.message}") }

    /** Windows 保留设备名（大小写不敏感；含带扩展名形态 CON.txt——取首个点前的基名判定）。 */
    private val WINDOWS_RESERVED_DEVICE_NAMES: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }

    /**
     * 条目名恶意形态判定（T-SAF-06）：null=安全；非 null=原因短语（拼进拒绝消息）。
     *
     * 统一分隔符后逐段检查（zip 条目名可混用 / 与 \）：`..` 遍历段、绝对/盘符限定路径、
     * `:`（NTFS 备用数据流，同时覆盖盘符冒号）、Windows 保留设备名（CON/PRN/AUX/NUL/
     * COM1-9/LPT1-9，取段内首个点前基名，大小写不敏感）、段尾点/空格（Windows 写盘时
     * 会剥掉，名字被改写/重定向）。空段容忍（连续分隔符无路径语义）；`.` 段无害放行。
     */
    internal fun entryNameIssue(name: String): String? {
        if (name.isBlank()) return "has an empty entry name"
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("/")) return "uses an absolute path"
        if (normalized.contains(':')) {
            return "contains ':' (drive-qualified path or NTFS alternate data stream)"
        }
        for (segment in normalized.split('/')) {
            if (segment.isEmpty()) continue
            if (segment == "..") return "contains a '..' traversal segment"
            if (segment.substringBefore('.').uppercase() in WINDOWS_RESERVED_DEVICE_NAMES) {
                return "uses a reserved Windows device name ('${segment.substringBefore('.')}')"
            }
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                return "has a segment ending with a trailing dot/space (Windows strips it)"
            }
        }
        return null
    }
}
