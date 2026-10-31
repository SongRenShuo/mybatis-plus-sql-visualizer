package cn.song.mybatisplus.visualizer.update

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * NEW-19：版本亮点登记表（纯逻辑，零平台依赖）。
 *
 * 目的：升级后一次性告诉用户「这一版你能感知到什么」，而不是让用户自己去翻 changelog。
 * 内容**由 CHANGELOG 的已发布段驱动**——每版只登记 2~3 条用户可感知的增量，并在登记项上
 * 标注直达入口（[HighlightTarget]），通知动作据此生成。
 *
 * 防打扰三重约束（与首捕通知 [cn.song.mybatisplus.visualizer.log.FirstCaptureGuide] 同口径）：
 *  - **一次性**：`PluginSettings.lastHighlightedVersion` 持久化，同版本永不重弹；
 *  - **版本粒度**：只有当前版本 ≠ 上次提示版本才弹（同日多次构建不重复，版本号变化才再提示）；
 *  - **headless 静默**：`isUnitTestMode` 直接不弹（也不写记忆——测试不污染持久化状态）。
 *
 * **未登记版本不弹**：亮点必须来自本表（changelog 的人工提炼），绝不按 changelog 原文自动摘要
 * 或编造——宁缺勿滥。
 */
internal enum class HighlightTarget {
    /** 设置页（方言/捕获开关等）。 */
    SETTINGS,

    /** SQL 日志工具窗。 */
    SQL_LOG,

    /** 更新日志（Gitee 发布页）。 */
    RELEASES,
}

/** 单条亮点：文案 key + 直达入口。 */
internal data class VersionHighlight(val textKey: String, val target: HighlightTarget)

internal object VersionHighlights {

    /**
     * 版本 → 亮点（2~3 条）。版本号与 `gradle.properties` 的对外版本一致。
     *
     * 登记纪律：只写用户可感知的行为变化（新方言、能采到 SQL、生成的 SQL 变正确），
     * 不写内部重构/测试/性能微调。
     */
    internal val HIGHLIGHTS_BY_VERSION: Map<String, List<VersionHighlight>> = mapOf(
        "2026.10.31" to listOf(
            VersionHighlight("update.highlights.2026_10_31.dialects", HighlightTarget.SETTINGS),
            VersionHighlight("update.highlights.2026_10_31.capture", HighlightTarget.SQL_LOG),
            VersionHighlight("update.highlights.2026_10_31.sqlServer", HighlightTarget.RELEASES),
        ),
    )

    /** 该版本登记的亮点（未登记返回空列表 → 调用方静默）。 */
    fun highlightsFor(version: String?): List<VersionHighlight> =
        version?.let { HIGHLIGHTS_BY_VERSION[it] }.orEmpty()

    /**
     * 是否应提示（纯函数，测试缝）：非 headless + 当前版本已知 + 与上次提示版本不同。
     * 未登记亮点的版本由调用方在取到空列表时静默（本函数只答「版本变了没有」）。
     */
    fun shouldNotify(
        currentVersion: String?,
        lastNotifiedVersion: String?,
        unitTestMode: Boolean,
    ): Boolean = !unitTestMode &&
        !currentVersion.isNullOrBlank() &&
        currentVersion != lastNotifiedVersion

    /** 通知正文（HTML）：版本行 + 有序列表；逐项 HTML 转义（changelog 文案可能含 `<`/`&`）。 */
    fun contentText(version: String, highlights: List<VersionHighlight>): String {
        val items = highlights.joinToString("") { highlight ->
            "<li>" + escapeHtml(MpSqlVisualizerBundle.message(highlight.textKey)) + "</li>"
        }
        return MpSqlVisualizerBundle.message("update.highlights.versionLine", version) +
            (if (items.isEmpty()) "" else "<ul>$items</ul>")
    }

    /** 动作文案 key（按直达入口）。 */
    fun actionLabelKey(target: HighlightTarget): String = when (target) {
        HighlightTarget.SETTINGS -> "update.highlights.action.settings"
        HighlightTarget.SQL_LOG -> "update.highlights.action.sqlLog"
        HighlightTarget.RELEASES -> "update.highlights.action.releases"
    }

    /** HTML 转义（只转三字符；通知正文按 HTML 渲染）。 */
    internal fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
