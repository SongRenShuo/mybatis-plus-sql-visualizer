// T-LocalUpdateDir 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/update/LocalPackageScanner.kt 顶层 sealed interface 移植（183c98b3d）
package cn.song.mybatisplus.visualizer.update;

import java.nio.file.Path;
import java.util.List;

/**
 * 扫描结果（页面状态行与启动通知的单一来源）。
 *
 * <p>master 侧为 LocalPackageScanner.kt 顶层 sealed interface，按 k2j 惯例独立文件承载；
 * data object 单例落嵌套 final class + INSTANCE，data class 落 record
 * （{@link cn.song.mybatisplus.visualizer.update.UpdateZipGuard.GuardResult} 同款惯例）。</p>
 */
public sealed interface ScanResult {

    /** 未配置目录（不算错误）。 */
    final class Disabled implements ScanResult {
        public static final Disabled INSTANCE = new Disabled();
        private Disabled() {}
    }

    /** 目录不合法（{@code check()} 各态文案见 i18n {@code settings.update.local.*}）。 */
    record DirInvalid(LocalUpdateDir.DirCheck check) implements ScanResult {}

    /** 目录可读但没有 {@code .zip}。 */
    record Empty(Path dir) implements ScanResult {}

    /** 有 zip 但都不是本插件（或损坏）——归属闸门在工作（G1/G2），不是错误态噪音。 */
    record NoValidPackage(Path dir, int skipped, List<String> skippedSamples, boolean truncated) implements ScanResult {}

    /** 目录里最高版本不高于当前——只升不降（D 决策：不降级）。 */
    record UpToDate(LocalPackage latest, int total, int skipped, boolean truncated) implements ScanResult {
        /** master 默认参重载（truncated=false）委托全参构造器。 */
        public UpToDate(LocalPackage latest, int total, int skipped) {
            this(latest, total, skipped, false);
        }
    }

    /** 发现可安装的更新。 */
    record Available(LocalPackage latest, int total, int skipped, boolean truncated) implements ScanResult {
        /** master 默认参重载（truncated=false）委托全参构造器。 */
        public Available(LocalPackage latest, int total, int skipped) {
            this(latest, total, skipped, false);
        }
    }

    /** 当前插件版本不可读 / 列举目录 IO 异常（诚实失败，不冒充任何其他态）。 */
    record Failed(String reason) implements ScanResult {}
}
