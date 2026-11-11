package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地安装包（T-LocalUpdateDir）：版本以**包内 plugin.xml** 为权威——用户会把
 * `xxx-2026.11.12.zip` 改名为 `最新.zip` 或复制成 `xxx (1).zip`，文件名只在日志/展示用。
 */
data class LocalPackage(
    val version: String,
    val path: Path,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    /** 包内 `<idea-version since-build>` 主版本段；未声明为 null（D-5 安装闸判据）。 */
    val sinceBuildBaseline: Int?,
)

/** 扫描结果（页面状态行与启动通知的单一来源）。 */
sealed interface ScanResult {
    /** 未配置目录（不算错误）。 */
    data object Disabled : ScanResult

    /** 目录不合法（[check] 的各态文案见 i18n `settings.update.local.*`）。 */
    data class DirInvalid(val check: LocalUpdateDir.DirCheck) : ScanResult

    /** 目录可读但没有 `.zip`。 */
    data class Empty(val dir: Path) : ScanResult

    /** 有 zip 但都不是本插件（或损坏）——归属闸门在工作（G1/G2），不是错误态噪音。 */
    data class NoValidPackage(val dir: Path, val skipped: Int, val skippedSamples: List<String>, val truncated: Boolean) : ScanResult

    /** 目录里最高版本不高于当前——只升不降（D 决策：不降级）。 */
    data class UpToDate(val latest: LocalPackage, val total: Int, val skipped: Int, val truncated: Boolean = false) : ScanResult

    /** 发现可安装的更新。 */
    data class Available(val latest: LocalPackage, val total: Int, val skipped: Int, val truncated: Boolean = false) : ScanResult

    /** 当前插件版本不可读 / 列举目录 IO 异常（诚实失败，不冒充任何其他态）。 */
    data class Failed(val reason: String) : ScanResult
}

/**
 * 本地安装包目录扫描（T-LocalUpdateDir 计划 §3.3）：非递归列 zip → 逐个过归属闸门
 * （G1 id + G2 布局，[PluginPackageOwnership.verify]）→ 选最高版本（[VersionComparator]，
 * 同版本取 mtime 更晚者）→ 与当前版本比较（只升不降）。
 *
 * 扫描规则：候选上限 [MAX_CANDIDATES]（超出按 mtime 降序截断，truncated 标记不静默吞）；
 * 每个候选**全量校验**（不靠文件名猜版本），结论按 `(路径,size,mtime)` 进会话缓存——
 * 页面防抖/重开设置不重复开包。所有文件系统调用同步执行，调用方必须放 BGT
 * （死掉的 UNC 路径上 `Files.exists` 可阻塞数秒）。
 */
object LocalPackageScanner {

    private val LOG = Logger.getInstance(LocalPackageScanner::class.java)

    /** 候选上限：超出按 mtime 降序截断（计划 §3.3 规则 2）。 */
    const val MAX_CANDIDATES = 100

    /** 跳过原因样本上限（tooltip 明细，防长目录刷屏）。 */
    private const val SKIPPED_SAMPLES = 3

    /** 全插件会话共用的校验缓存（页面防抖与启动检测共用，见 [PackageVerificationCache]）。 */
    val sharedCache = PackageVerificationCache()

    /**
     * 扫描目录并判定可用更新。
     *
     * @param dirRaw 设置项原文（空 = Disabled）；内部经 [LocalUpdateDir.parse] 归一与校验。
     * @param currentVersion 当前插件版本（[PluginVersion.current]）；不可读 → [ScanResult.Failed]
     *   （与远端 `PluginUpdateRunner.check` 的 VERSION_UNREADABLE 口径一致：判不了就不许装）。
     * @param cache 校验结论缓存（测试注入新实例；生产传 [sharedCache]）。
     * @param pluginsDir 插件目录守卫基准（纯单测注入临时目录；生产传 null 走平台基准）。
     */
    fun scan(
        dirRaw: String,
        currentVersion: String?,
        cache: PackageVerificationCache = sharedCache,
        pluginsDir: Path? = null,
    ): ScanResult {
        if (dirRaw.isBlank()) return ScanResult.Disabled
        val check = LocalUpdateDir.parse(dirRaw, pluginsDir)
        val dir = when (check) {
            is LocalUpdateDir.DirCheck.Ok -> check.dir
            else -> return ScanResult.DirInvalid(check)
        }
        if (!VersionComparator.isRecognizable(currentVersion)) {
            return ScanResult.Failed("当前插件版本不可读")
        }

        val zips = listZipCandidates(dir) ?: return ScanResult.Failed("目录列举失败（权限或 IO 异常）")
        val truncated = zips.size > MAX_CANDIDATES
        val candidates = if (truncated) zips.sortedByDescending { it.modifiedMs }.take(MAX_CANDIDATES) else zips

        var skipped = 0
        val skippedSamples = mutableListOf<String>()
        val valid = mutableListOf<LocalPackage>()
        for (candidate in candidates) {
            when (val outcome = cache.verify(candidate.path, candidate.sizeBytes, candidate.modifiedMs)) {
                is PackageVerificationCache.Outcome.Valid -> valid.add(
                    LocalPackage(
                        version = outcome.version,
                        path = candidate.path,
                        fileName = candidate.fileName,
                        sizeBytes = candidate.sizeBytes,
                        modifiedMs = candidate.modifiedMs,
                        sinceBuildBaseline = outcome.sinceBuildBaseline,
                    )
                )
                is PackageVerificationCache.Outcome.Skip -> {
                    skipped++
                    if (skippedSamples.size < SKIPPED_SAMPLES) skippedSamples.add(outcome.reason)
                }
            }
        }
        if (truncated) {
            LOG.info("local update dir: $dir has ${zips.size} zips, scanned top-$MAX_CANDIDATES by mtime")
        }
        if (valid.isEmpty()) {
            return if (candidates.isEmpty()) {
                ScanResult.Empty(dir)
            } else {
                ScanResult.NoValidPackage(dir, skipped, skippedSamples, truncated)
            }
        }
        val latest = valid.reduce { acc, pkg ->
            when {
                VersionComparator.compare(pkg.version, acc.version) > 0 -> pkg
                VersionComparator.compare(pkg.version, acc.version) == 0 &&
                    (pkg.modifiedMs > acc.modifiedMs ||
                        (pkg.modifiedMs == acc.modifiedMs && pkg.fileName < acc.fileName)) -> pkg
                else -> acc
            }
        }
        return if (VersionComparator.compare(latest.version, currentVersion) > 0) {
            ScanResult.Available(latest, valid.size, skipped, truncated)
        } else {
            ScanResult.UpToDate(latest, valid.size, skipped, truncated)
        }
    }

    /** 列目录下的 zip（大小写不敏感扩展名、跳过子目录、带 stat）；IO 失败返回 null（转 [ScanResult.Failed]）。 */
    private fun listZipCandidates(dir: Path): List<CandidateFile>? = try {
        Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".zip", ignoreCase = true) }
                .map { it.toCandidate() }
                .collect(java.util.stream.Collectors.toList())
        }
    } catch (e: Exception) {
        LOG.warn("local update dir listing failed: $dir — ${e.message}")
        null
    }

    private fun Path.toCandidate(): CandidateFile {
        val attrs = runCatching { Files.readAttributes(this, java.nio.file.attribute.BasicFileAttributes::class.java) }
        return CandidateFile(
            path = this,
            fileName = fileName.toString(),
            sizeBytes = attrs.getOrNull()?.size() ?: 0L,
            modifiedMs = attrs.getOrNull()?.lastModifiedTime()?.toMillis() ?: 0L,
        )
    }

    private data class CandidateFile(val path: Path, val fileName: String, val sizeBytes: Long, val modifiedMs: Long)
}

/**
 * 会话级校验结论缓存（计划 §3.3 规则 7）：key = `(绝对路径字符串, size, mtime)`，值 = 校验结论。
 * 容量超限整表清空（保守策略，避免引入 LRU 复杂度；目录里 200 个包的极端场景整表重扫可接受）。
 * 线程安全（页面防抖与启动检测可能并发触达同一实例）。
 */
class PackageVerificationCache {

    /** 校验结论：Valid（本插件，带版本与 since-build）/ Skip（别的插件/损坏/夹带，带一条可查原因）。 */
    sealed interface Outcome {
        data class Valid(val version: String, val sinceBuildBaseline: Int?) : Outcome
        data class Skip(val reason: String) : Outcome
    }

    private data class Key(val path: String, val size: Long, val modifiedMs: Long)

    private val map = ConcurrentHashMap<Key, Outcome>()

    /** 结论查询（miss 时由调用方走真校验再 [put]）。 */
    fun get(path: Path, sizeBytes: Long, modifiedMs: Long): Outcome? =
        map[Key(path.toString(), sizeBytes, modifiedMs)]

    fun put(path: Path, sizeBytes: Long, modifiedMs: Long, outcome: Outcome) {
        if (map.size >= MAX_ENTRIES) {
            map.clear()
        }
        map[Key(path.toString(), sizeBytes, modifiedMs)] = outcome
    }

    fun clear() = map.clear()

    /** 便利封装：命中缓存直接用，miss 时跑 [PluginPackageOwnership.verify] 并回填。 */
    fun verify(path: Path, sizeBytes: Long, modifiedMs: Long): Outcome {
        val key = Key(path.toString(), sizeBytes, modifiedMs)
        map[key]?.let { return it }
        val outcome = when (val ownership = PluginPackageOwnership.verify(path)) {
            is Ownership.Ours -> Outcome.Valid(ownership.version, ownership.sinceBuildBaseline)
            is Ownership.NotOurs -> Outcome.Skip(ownership.reason)
            is Ownership.Tainted -> Outcome.Skip(ownership.reason)
        }
        put(path, sizeBytes, modifiedMs, outcome)
        return outcome
    }

    companion object {
        private const val MAX_ENTRIES = 200
    }
}
