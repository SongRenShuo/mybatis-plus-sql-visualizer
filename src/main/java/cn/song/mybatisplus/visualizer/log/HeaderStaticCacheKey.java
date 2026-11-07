package cn.song.mybatisplus.visualizer.log;

/**
 * 券01（T-PerfSweep27-01）：头部行静态段缓存键——全部影响静态片段形态的设置维度。
 * 键含设置值 = 显示格式严格跟随设置（切换即时重算，免失效钩子），
 * 形态对照 {@code timestampFormatterCache}「键=格式串天然跟随设置变更」先例。
 * Kotlin data class 的 Java 等价：record 自带值相等（equals/hashCode），直接做缓存键。
 *
 * <p>T-LogMethodName 起 {@code methodInSource} 也是本键的一维：来源段与方法名段同用
 * {@code cachedHeaderKey}，键缺这一维会让开关切换后已渲染条目一直复用旧片段（表现为「设置改了没反应」）。</p>
 */
public record HeaderStaticCacheKey(
        boolean showDuration,
        boolean tsEnabled,
        String tsFormat,
        String tsSource,
        boolean badgeDetail,
        long slowThresholdMs,
        boolean methodInSource) {
}
