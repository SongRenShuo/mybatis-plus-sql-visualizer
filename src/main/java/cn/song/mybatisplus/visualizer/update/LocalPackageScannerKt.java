package cn.song.mybatisplus.visualizer.update;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 本地安装包目录扫描（T-LocalUpdateDir 计划 §3.3）：非递归列 zip → 逐个过归属闸门
 * （G1 id + G2 布局，{@link PluginPackageOwnershipKt#verify}）→ 选最高版本（VersionComparator，
 * 同版本取 mtime 更晚者）→ 与当前版本比较（只升不降）。
 *
 * <p>扫描规则：候选上限 {@link #MAX_CANDIDATES}（超出按 mtime 降序截断，truncated 标记不静默吞）；
 * 每个候选<b>全量校验</b>（不靠文件名猜版本），结论按 {@code (路径,size,mtime)} 进会话缓存——
 * 页面防抖/重开设置不重复开包。所有文件系统调用同步执行，调用方必须放 BGT
 * （死掉的 UNC 路径上 {@code Files.exists} 可阻塞数秒）。</p>
 *
 * <p>本类包体：{@link LocalPackage}（版本以<b>包内 plugin.xml</b> 为权威——文件名只在日志/展示用）、
 * {@link ScanResult}（页面状态行与启动通知的单一来源）、{@link PackageVerificationCache}
 * （会话级校验结论缓存）。master 侧为顶层声明集合，Java 侧按 k2j 惯例落门面类。</p>
 */
public final class LocalPackageScannerKt {

    private static final Logger LOG = Logger.getInstance(LocalPackageScannerKt.class);

    /** 候选上限：超出按 mtime 降序截断（计划 §3.3 规则 2）。 */
    public static final int MAX_CANDIDATES = 100;

    /** 跳过原因样本上限（tooltip 明细，防长目录刷屏）。 */
    private static final int SKIPPED_SAMPLES = 3;

    /** 全插件会话共用的校验缓存（页面防抖与启动检测共用）。 */
    public static final PackageVerificationCache SHARED_CACHE = new PackageVerificationCache();

    /** Kotlin object 语义兼容实例。 */
    public static final LocalPackageScannerKt INSTANCE = new LocalPackageScannerKt();

    private LocalPackageScannerKt() {
    }

    /** 本地安装包（T-LocalUpdateDir）：版本以包内 plugin.xml 为权威，文件名只在日志/展示用。 */
    public record LocalPackage(
            @NotNull String version,
            @NotNull Path path,
            @NotNull String fileName,
            long sizeBytes,
            long modifiedMs,
            /** 包内 {@code <idea-version since-build>} 主版本段；未声明为 null（D-5 安装闸判据）。 */
            @Nullable Integer sinceBuildBaseline) {
    }

    /** 扫描结果（页面状态行与启动通知的单一来源；Kotlin sealed 的 Java 等价形态）。 */
    public static final class ScanResult {

        @NotNull
        private final String kind;
        @Nullable
        private final LocalUpdateDirKt.DirCheck check;
        @Nullable
        private final Path dir;
        @Nullable
        private final LocalPackage latest;
        private final int total;
        private final int skipped;
        @Nullable
        private final List<String> skippedSamples;
        private final boolean truncated;
        @Nullable
        private final String reason;

        private ScanResult(@NotNull String kind,
                           @Nullable LocalUpdateDirKt.DirCheck check,
                           @Nullable Path dir,
                           @Nullable LocalPackage latest,
                           int total,
                           int skipped,
                           @Nullable List<String> skippedSamples,
                           boolean truncated,
                           @Nullable String reason) {
            this.kind = kind;
            this.check = check;
            this.dir = dir;
            this.latest = latest;
            this.total = total;
            this.skipped = skipped;
            this.skippedSamples = skippedSamples;
            this.truncated = truncated;
            this.reason = reason;
        }

        public static final ScanResult DISABLED = new ScanResult("Disabled", null, null, null, 0, 0, null, false, null);

        public static ScanResult dirInvalid(@NotNull LocalUpdateDirKt.DirCheck check) {
            return new ScanResult("DirInvalid", check, null, null, 0, 0, null, false, null);
        }

        public static ScanResult empty(@NotNull Path dir) {
            return new ScanResult("Empty", null, dir, null, 0, 0, null, false, null);
        }

        /** 有 zip 但都不是本插件（或损坏）——归属闸门在工作（G1/G2），不是错误态噪音。 */
        public static ScanResult noValidPackage(@NotNull Path dir, int skipped,
                                                @NotNull List<String> skippedSamples, boolean truncated) {
            return new ScanResult("NoValidPackage", null, dir, null, 0, skipped, skippedSamples, truncated, null);
        }

        /** 目录里最高版本不高于当前——只升不降（D 决策：不降级）。 */
        public static ScanResult upToDate(@NotNull LocalPackage latest, int total, int skipped, boolean truncated) {
            return new ScanResult("UpToDate", null, null, latest, total, skipped, null, truncated, null);
        }

        /** 发现可安装的更新。 */
        public static ScanResult available(@NotNull LocalPackage latest, int total, int skipped, boolean truncated) {
            return new ScanResult("Available", null, null, latest, total, skipped, null, truncated, null);
        }

        /** 当前插件版本不可读 / 列举目录 IO 异常（诚实失败，不冒充任何其他态）。 */
        public static ScanResult failed(@NotNull String reason) {
            return new ScanResult("Failed", null, null, null, 0, 0, null, false, reason);
        }

        @NotNull
        public String getKind() {
            return kind;
        }

        public boolean isDisabled() {
            return "Disabled".equals(kind);
        }

        public boolean isDirInvalid() {
            return "DirInvalid".equals(kind);
        }

        public boolean isEmpty() {
            return "Empty".equals(kind);
        }

        public boolean isNoValidPackage() {
            return "NoValidPackage".equals(kind);
        }

        public boolean isUpToDate() {
            return "UpToDate".equals(kind);
        }

        public boolean isAvailable() {
            return "Available".equals(kind);
        }

        public boolean isFailed() {
            return "Failed".equals(kind);
        }

        @Nullable
        public LocalUpdateDirKt.DirCheck getCheck() {
            return check;
        }

        @Nullable
        public Path getDir() {
            return dir;
        }

        @Nullable
        public LocalPackage getLatest() {
            return latest;
        }

        public int getTotal() {
            return total;
        }

        public int getSkipped() {
            return skipped;
        }

        @NotNull
        public List<String> getSkippedSamples() {
            return skippedSamples != null ? skippedSamples : List.of();
        }

        public boolean isTruncated() {
            return truncated;
        }

        @Nullable
        public String getReason() {
            return reason;
        }
    }

    /** 候选文件（stat 快照）。 */
    private record CandidateFile(@NotNull Path path, @NotNull String fileName, long sizeBytes, long modifiedMs) {
    }

    /**
     * 扫描目录并判定可用更新。
     *
     * @param dirRaw         设置项原文（空 = Disabled）；内部经 {@link LocalUpdateDirKt#parse} 归一与校验
     * @param currentVersion 当前插件版本（PluginVersion.current）；不可读 → Failed
     *                       （与远端 PluginUpdateRunner.check 的 VERSION_UNREADABLE 口径一致：判不了就不许装）
     * @param cache          校验结论缓存（测试注入新实例；生产传 {@link #SHARED_CACHE}）
     * @param pluginsDir     插件目录守卫基准（纯单测注入临时目录；生产传 null 走平台基准）
     */
    @NotNull
    public static ScanResult scan(@NotNull String dirRaw,
                                  @Nullable String currentVersion,
                                  @NotNull PackageVerificationCache cache,
                                  @Nullable Path pluginsDir) {
        if (dirRaw.isBlank()) {
            return ScanResult.DISABLED;
        }
        LocalUpdateDirKt.DirCheck check = LocalUpdateDirKt.parse(dirRaw, pluginsDir);
        if (!check.isOk()) {
            return ScanResult.dirInvalid(check);
        }
        Path dir = check.getDir();
        if (!VersionComparator.INSTANCE.isRecognizable(currentVersion)) {
            return ScanResult.failed("当前插件版本不可读");
        }

        List<CandidateFile> zips = listZipCandidates(dir);
        if (zips == null) {
            return ScanResult.failed("目录列举失败（权限或 IO 异常）");
        }
        boolean truncated = zips.size() > MAX_CANDIDATES;
        List<CandidateFile> candidates = zips;
        if (truncated) {
            candidates = new ArrayList<>(zips);
            candidates.sort((a, b) -> Long.compare(b.modifiedMs(), a.modifiedMs()));
            candidates = candidates.subList(0, MAX_CANDIDATES);
        }

        int skipped = 0;
        List<String> skippedSamples = new ArrayList<>();
        List<LocalPackage> valid = new ArrayList<>();
        for (CandidateFile candidate : candidates) {
            PackageVerificationCache.Outcome outcome =
                    cache.verify(candidate.path(), candidate.sizeBytes(), candidate.modifiedMs());
            if (outcome.isValid()) {
                valid.add(new LocalPackage(outcome.getVersion(), candidate.path(), candidate.fileName(),
                        candidate.sizeBytes(), candidate.modifiedMs(), outcome.getSinceBuildBaseline()));
            } else if (outcome.isSkip()) {
                skipped++;
                if (skippedSamples.size() < SKIPPED_SAMPLES) {
                    skippedSamples.add(outcome.getReason());
                }
            }
        }
        if (truncated) {
            LOG.info("local update dir: " + dir + " has " + zips.size() + " zips, scanned top-"
                    + MAX_CANDIDATES + " by mtime");
        }
        if (valid.isEmpty()) {
            return candidates.isEmpty() ? ScanResult.empty(dir)
                    : ScanResult.noValidPackage(dir, skipped, skippedSamples, truncated);
        }
        LocalPackage latest = valid.get(0);
        for (LocalPackage pkg : valid.subList(1, valid.size())) {
            int cmp = VersionComparator.INSTANCE.compare(pkg.version(), latest.version());
            boolean newer = cmp > 0
                    || (cmp == 0 && (pkg.modifiedMs() > latest.modifiedMs()
                        || (pkg.modifiedMs() == latest.modifiedMs()
                            && pkg.fileName().compareTo(latest.fileName()) < 0)));
            if (newer) {
                latest = pkg;
            }
        }
        return VersionComparator.INSTANCE.compare(latest.version(), currentVersion) > 0
                ? ScanResult.available(latest, valid.size(), skipped, truncated)
                : ScanResult.upToDate(latest, valid.size(), skipped, truncated);
    }

    /** cache/pluginsDir 缺省形态（Kotlin 默认参等价）。 */
    @NotNull
    public static ScanResult scan(@NotNull String dirRaw, @Nullable String currentVersion) {
        return scan(dirRaw, currentVersion, SHARED_CACHE, null);
    }

    @NotNull
    public static ScanResult scan(@NotNull String dirRaw, @Nullable String currentVersion,
                                  @NotNull PackageVerificationCache cache) {
        return scan(dirRaw, currentVersion, cache, null);
    }

    /** 列目录下的 zip（大小写不敏感扩展名、跳过子目录、带 stat）；IO 失败返回 null（转 Failed）。 */
    @Nullable
    private static List<CandidateFile> listZipCandidates(@NotNull Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".zip"))
                    .map(LocalPackageScannerKt::toCandidate)
                    .collect(Collectors.toList());
        } catch (IOException | RuntimeException e) {
            LOG.warn("local update dir listing failed: " + dir + " — " + e.getMessage());
            return null;
        }
    }

    @NotNull
    private static CandidateFile toCandidate(@NotNull Path path) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException | RuntimeException e) {
            attrs = null;
        }
        return new CandidateFile(
                path,
                path.getFileName().toString(),
                attrs != null ? attrs.size() : 0L,
                attrs != null ? attrs.lastModifiedTime().toMillis() : 0L);
    }

    /**
     * 会话级校验结论缓存（计划 §3.3 规则 7）：key = {@code (绝对路径字符串, size, mtime)}，
     * 值 = 校验结论。容量超限整表清空（保守策略，避免引入 LRU 复杂度；目录里 200 个包的
     * 极端场景整表重扫可接受）。线程安全（页面防抖与启动检测可能并发触达同一实例）。
     */
    public static final class PackageVerificationCache {

        /** 校验结论：Valid（本插件，带版本与 since-build）/ Skip（别的插件/损坏/夹带，带一条可查原因）。 */
        public static final class Outcome {

            @NotNull
            private final String kind;
            @Nullable
            private final String version;
            @Nullable
            private final Integer sinceBuildBaseline;
            @Nullable
            private final String reason;

            private Outcome(@NotNull String kind, @Nullable String version,
                            @Nullable Integer sinceBuildBaseline, @Nullable String reason) {
                this.kind = kind;
                this.version = version;
                this.sinceBuildBaseline = sinceBuildBaseline;
                this.reason = reason;
            }

            public static Outcome valid(@NotNull String version, @Nullable Integer sinceBuildBaseline) {
                return new Outcome("Valid", version, sinceBuildBaseline, null);
            }

            public static Outcome skip(@NotNull String reason) {
                return new Outcome("Skip", null, null, reason);
            }

            public boolean isValid() {
                return "Valid".equals(kind);
            }

            public boolean isSkip() {
                return "Skip".equals(kind);
            }

            @Nullable
            public String getVersion() {
                return version;
            }

            @Nullable
            public Integer getSinceBuildBaseline() {
                return sinceBuildBaseline;
            }

            @Nullable
            public String getReason() {
                return reason;
            }
        }

        private record Key(@NotNull String path, long size, long modifiedMs) {
        }

        private static final int MAX_ENTRIES = 200;

        private final ConcurrentHashMap<Key, Outcome> map = new ConcurrentHashMap<>();

        /** 结论查询（miss 时由调用方走真校验再 {@link #put}）。 */
        @Nullable
        public Outcome get(@NotNull Path path, long sizeBytes, long modifiedMs) {
            return map.get(new Key(path.toString(), sizeBytes, modifiedMs));
        }

        public void put(@NotNull Path path, long sizeBytes, long modifiedMs, @NotNull Outcome outcome) {
            if (map.size() >= MAX_ENTRIES) {
                map.clear();
            }
            map.put(new Key(path.toString(), sizeBytes, modifiedMs), outcome);
        }

        public void clear() {
            map.clear();
        }

        /** 便利封装：命中缓存直接用，miss 时跑 {@link PluginPackageOwnershipKt#verify} 并回填。 */
        @NotNull
        public Outcome verify(@NotNull Path path, long sizeBytes, long modifiedMs) {
            Key key = new Key(path.toString(), sizeBytes, modifiedMs);
            Outcome cached = map.get(key);
            if (cached != null) {
                return cached;
            }
            Outcome outcome;
            PluginPackageOwnershipKt.Ownership ownership = PluginPackageOwnershipKt.verify(path);
            if (ownership.isOurs()) {
                outcome = Outcome.valid(ownership.getVersion(), ownership.getSinceBuildBaseline());
            } else {
                outcome = Outcome.skip(ownership.getReason() != null ? ownership.getReason() : "skip");
            }
            put(path, sizeBytes, modifiedMs, outcome);
            return outcome;
        }
    }
}
