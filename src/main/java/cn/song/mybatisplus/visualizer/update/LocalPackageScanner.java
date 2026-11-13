// T-LocalUpdateDir 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/update/LocalPackageScanner.kt object 移植（183c98b3d）
package cn.song.mybatisplus.visualizer.update;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 本地安装包目录扫描（T-LocalUpdateDir 计划 §3.3）：非递归列 zip → 逐个过归属闸门
 * （G1 id + G2 布局，{@link PluginPackageOwnership#verify}）→ 选最高版本（{@link VersionComparator}，
 * 同版本取 mtime 更晚者）→ 与当前版本比较（只升不降）。
 *
 * <p>扫描规则：候选上限 {@link #MAX_CANDIDATES}（超出按 mtime 降序截断，truncated 标记不静默吞）；
 * 每个候选<b>全量校验</b>（不靠文件名猜版本），结论按 {@code (路径,size,mtime)} 进会话缓存——
 * 页面防抖/重开设置不重复开包。所有文件系统调用同步执行，调用方必须放 BGT
 * （死掉的 UNC 路径上 {@code Files.exists} 可阻塞数秒）。</p>
 */
public final class LocalPackageScanner {

    public static final LocalPackageScanner INSTANCE = new LocalPackageScanner();
    private LocalPackageScanner() {}

    private static final Logger LOG = Logger.getInstance(LocalPackageScanner.class);

    /** 候选上限：超出按 mtime 降序截断（计划 §3.3 规则 2）。 */
    public static final int MAX_CANDIDATES = 100;

    /** 跳过原因样本上限（tooltip 明细，防长目录刷屏）。 */
    private static final int SKIPPED_SAMPLES = 3;

    /** 全插件会话共用的校验缓存（页面防抖与启动检测共用，见 {@link PackageVerificationCache}）。 */
    public static final PackageVerificationCache sharedCache = new PackageVerificationCache();

    /**
     * 扫描目录并判定可用更新（master 默认参重载：cache=sharedCache、pluginsDir=null，委托全参）。
     *
     * @param dirRaw 设置项原文（空 = Disabled）；内部经 {@link LocalUpdateDir#parse} 归一与校验。
     * @param currentVersion 当前插件版本（{@code PluginVersion.current}）；不可读 → {@link ScanResult.Failed}
     *   （与远端 {@code PluginUpdateRunner.check} 的 VERSION_UNREADABLE 口径一致：判不了就不许装）。
     */
    public static ScanResult scan(String dirRaw, String currentVersion) {
        return scan(dirRaw, currentVersion, sharedCache, null);
    }

    /** master 默认参重载（pluginsDir=null）委托全参。 */
    public static ScanResult scan(String dirRaw, String currentVersion, PackageVerificationCache cache) {
        return scan(dirRaw, currentVersion, cache, null);
    }

    /**
     * 扫描目录并判定可用更新（全参形态）。
     *
     * @param cache 校验结论缓存（测试注入新实例；生产传 {@link #sharedCache}）。
     * @param pluginsDir 插件目录守卫基准（纯单测注入临时目录；生产传 null 走平台基准）。
     */
    public static ScanResult scan(String dirRaw, String currentVersion, PackageVerificationCache cache, Path pluginsDir) {
        if (dirRaw == null || dirRaw.isBlank()) return ScanResult.Disabled.INSTANCE;
        LocalUpdateDir.DirCheck check = LocalUpdateDir.INSTANCE.parse(dirRaw, pluginsDir);
        if (!(check instanceof LocalUpdateDir.DirCheck.Ok ok)) {
            return new ScanResult.DirInvalid(check);
        }
        Path dir = ok.dir();
        if (!VersionComparator.INSTANCE.isRecognizable(currentVersion)) {
            return new ScanResult.Failed("当前插件版本不可读");
        }

        List<CandidateFile> zips = listZipCandidates(dir);
        if (zips == null) return new ScanResult.Failed("目录列举失败（权限或 IO 异常）");
        boolean truncated = zips.size() > MAX_CANDIDATES;
        List<CandidateFile> candidates;
        if (truncated) {
            candidates = zips.stream()
                .sorted(Comparator.comparingLong(CandidateFile::modifiedMs).reversed())
                .limit(MAX_CANDIDATES)
                .collect(Collectors.toList());
        } else {
            candidates = zips;
        }

        int skipped = 0;
        List<String> skippedSamples = new ArrayList<>();
        List<LocalPackage> valid = new ArrayList<>();
        for (CandidateFile candidate : candidates) {
            PackageVerificationCache.Outcome outcome = cache.verify(candidate.path(), candidate.sizeBytes(), candidate.modifiedMs());
            if (outcome instanceof PackageVerificationCache.Outcome.Valid v) {
                valid.add(new LocalPackage(
                    v.version(),
                    candidate.path(),
                    candidate.fileName(),
                    candidate.sizeBytes(),
                    candidate.modifiedMs(),
                    v.sinceBuildBaseline()
                ));
            } else {
                PackageVerificationCache.Outcome.Skip skip = (PackageVerificationCache.Outcome.Skip) outcome;
                skipped++;
                if (skippedSamples.size() < SKIPPED_SAMPLES) skippedSamples.add(skip.reason());
            }
        }
        if (truncated) {
            LOG.info("local update dir: " + dir + " has " + zips.size() + " zips, scanned top-" + MAX_CANDIDATES + " by mtime");
        }
        if (valid.isEmpty()) {
            return candidates.isEmpty()
                ? new ScanResult.Empty(dir)
                : new ScanResult.NoValidPackage(dir, skipped, skippedSamples, truncated);
        }
        LocalPackage latest = valid.get(0);
        for (int i = 1; i < valid.size(); i++) {
            LocalPackage pkg = valid.get(i);
            int cmp = VersionComparator.INSTANCE.compare(pkg.version(), latest.version());
            if (cmp > 0) {
                latest = pkg;
            } else if (cmp == 0
                && (pkg.modifiedMs() > latest.modifiedMs()
                    || (pkg.modifiedMs() == latest.modifiedMs() && pkg.fileName().compareTo(latest.fileName()) < 0))) {
                latest = pkg;
            }
        }
        return VersionComparator.INSTANCE.compare(latest.version(), currentVersion) > 0
            ? new ScanResult.Available(latest, valid.size(), skipped, truncated)
            : new ScanResult.UpToDate(latest, valid.size(), skipped, truncated);
    }

    /**
     * 列目录下的 zip（大小写不敏感扩展名、跳过子目录、带 stat）；IO 失败返回 null（转 {@link ScanResult.Failed}）。
     */
    private static List<CandidateFile> listZipCandidates(Path dir) {
        try {
            try (var stream = Files.list(dir)) {
                return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip"))
                    .map(LocalPackageScanner::toCandidate)
                    .collect(Collectors.toList());
            }
        } catch (Exception e) { // master catch(Exception) 原形——仅 IO 面，不吞 fatal
            LOG.warn("local update dir listing failed: " + dir + " — " + e.getMessage());
            return null;
        }
    }

    private static CandidateFile toCandidate(Path path) {
        BasicFileAttributes attrs;
        try { // master runCatching 语义
            attrs = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (Throwable t) {
            attrs = null;
        }
        return new CandidateFile(
            path,
            path.getFileName().toString(),
            attrs != null ? attrs.size() : 0L,
            attrs != null ? attrs.lastModifiedTime().toMillis() : 0L
        );
    }

    private record CandidateFile(Path path, String fileName, long sizeBytes, long modifiedMs) {}
}
