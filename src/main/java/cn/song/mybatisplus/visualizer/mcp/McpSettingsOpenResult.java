// T-McpSettingsJump 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSettingsNavigation.kt 顶层枚举移植（183c98b3d）
package cn.song.mybatisplus.visualizer.mcp;

/**
 * 打开结果：只用于区分「跳成功了」与「该提示用户」，不参与任何门控判断。
 * master 侧为 McpSettingsNavigation.kt 顶层枚举（非嵌套），按 k2j 惯例独立承载
 * （{@code PreviewDegradationReason} 同款先例）。
 */
public enum McpSettingsOpenResult {
    /** 经类名候选链打开（262/263 实测路径）。 */
    OPENED,

    /** 类名全落空，经平台「按页面 id 打开并选中」入口打开。 */
    OPENED_BY_ID,

    /** 两条路都没走通：插件缺失/被禁，或平台又搬了地方——调用方给一次可操作提示。 */
    UNAVAILABLE,
}
