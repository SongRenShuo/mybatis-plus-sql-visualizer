// T-LocalUpdateDir 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/update/LocalPackageScanner.kt 顶层 class 移植（183c98b3d）
package cn.song.mybatisplus.visualizer.update;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级校验结论缓存（计划 §3.3 规则 7）：key = {@code (绝对路径字符串, size, mtime)}，值 = 校验结论。
 * 容量超限整表清空（保守策略，避免引入 LRU 复杂度；目录里 200 个包的极端场景整表重扫可接受）。
 * 线程安全（页面防抖与启动检测可能并发触达同一实例）。
 *
 * <p>master 侧为 LocalPackageScanner.kt 顶层 class，按 k2j 惯例独立文件承载。</p>
 */
public final class PackageVerificationCache {

    /** 校验结论：Valid（本插件，带版本与 since-build）/ Skip（别的插件/损坏/夹带，带一条可查原因）。 */
    public sealed interface Outcome {

        record Valid(String version, Integer sinceBuildBaseline) implements Outcome {}

        record Skip(String reason) implements Outcome {}
    }

    private record Key(String path, long size, long modifiedMs) {}

    private static final int MAX_ENTRIES = 200;

    private final ConcurrentHashMap<Key, Outcome> map = new ConcurrentHashMap<>();

    /** 结论查询（miss 时由调用方走真校验再 {@link #put}）。 */
    public Outcome get(Path path, long sizeBytes, long modifiedMs) {
        return map.get(new Key(path.toString(), sizeBytes, modifiedMs));
    }

    public void put(Path path, long sizeBytes, long modifiedMs, Outcome outcome) {
        if (map.size() >= MAX_ENTRIES) {
            map.clear();
        }
        map.put(new Key(path.toString(), sizeBytes, modifiedMs), outcome);
    }

    public void clear() {
        map.clear();
    }

    /** 便利封装：命中缓存直接用，miss 时跑 {@link PluginPackageOwnership#verify} 并回填。 */
    public Outcome verify(Path path, long sizeBytes, long modifiedMs) {
        Key key = new Key(path.toString(), sizeBytes, modifiedMs);
        Outcome cached = map.get(key);
        if (cached != null) return cached;
        PluginPackageOwnership.Ownership ownership = PluginPackageOwnership.INSTANCE.verify(path);
        Outcome outcome;
        if (ownership instanceof PluginPackageOwnership.Ownership.Ours ours) {
            outcome = new Outcome.Valid(ours.version(), ours.sinceBuildBaseline());
        } else if (ownership instanceof PluginPackageOwnership.Ownership.NotOurs notOurs) {
            outcome = new Outcome.Skip(notOurs.reason());
        } else {
            PluginPackageOwnership.Ownership.Tainted tainted = (PluginPackageOwnership.Ownership.Tainted) ownership;
            outcome = new Outcome.Skip(tainted.reason());
        }
        put(path, sizeBytes, modifiedMs, outcome);
        return outcome;
    }
}
