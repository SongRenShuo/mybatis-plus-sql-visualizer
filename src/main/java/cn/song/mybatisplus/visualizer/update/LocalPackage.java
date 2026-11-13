// T-LocalUpdateDir 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/update/LocalPackageScanner.kt 顶层 data class 移植（183c98b3d）
package cn.song.mybatisplus.visualizer.update;

import java.nio.file.Path;

/**
 * 本地安装包（T-LocalUpdateDir）：版本以<b>包内 plugin.xml</b> 为权威——用户会把
 * {@code xxx-2026.11.12.zip} 改名为 {@code 最新.zip} 或复制成 {@code xxx (1).zip}，文件名只在日志/展示用。
 *
 * <p>master 侧为 LocalPackageScanner.kt 顶层 data class，按 k2j 惯例独立文件承载（record）。</p>
 */
public record LocalPackage(
    String version,
    Path path,
    String fileName,
    long sizeBytes,
    long modifiedMs,
    /** 包内 {@code <idea-version since-build>} 主版本段；未声明为 null（D-5 安装闸判据）。 */
    Integer sinceBuildBaseline
) {
}
