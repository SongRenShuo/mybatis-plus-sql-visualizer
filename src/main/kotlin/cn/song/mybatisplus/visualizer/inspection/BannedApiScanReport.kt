package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * 禁用 API 存量扫描的**单条命中**（平台无关的纯数据）。
 *
 * @param classFqn    命中方法的声明类全限定名
 * @param methodName  方法名
 * @param level       规则级别（error / warning）
 * @param description 规则说明
 * @param filePath    相对项目根的展示路径（扫描器裁剪）
 * @param line        行号（1 起；取不到为 0）
 */
data class BannedApiScanHit(
    val classFqn: String,
    val methodName: String,
    val level: String,
    val description: String,
    val filePath: String,
    val line: Int,
) {
    /** 报告行里的目标展示：`类#方法`。 */
    fun targetSummary(): String = "$classFqn#$methodName"
}

/**
 * 存量扫描报告组装（纯逻辑，零平台依赖）：命中列表 → 人读 Markdown-ish 文本报告。
 *
 * 单独抽出的理由：条数上限/截断标注/分组统计都能用普通 JUnit 直测；扫描器只负责产出
 * [BannedApiScanHit] 列表，报告排版与文案与 PSI 无关。
 */
object BannedApiScanReport {

    /** 明细行硬上限（防超大项目把报告字符串撑爆内存；总数统计不受影响）。 */
    const val MAX_DETAIL_ENTRIES = 500

    /**
     * 组装报告文本。
     *
     * @param projectName  项目名
     * @param scannedFiles 实际扫描的 Java 文件数
     * @param totalHits    命中总数（可大于 `hits.size`——明细可能已被上限截断）
     * @param hits         明细（按扫描顺序；超 [MAX_DETAIL_ENTRIES] 的尾部不再进入报告）
     * @param canceled     扫描是否被用户取消（结果不完整，报告首部标注）
     */
    fun build(
        projectName: String,
        scannedFiles: Int,
        totalHits: Int,
        hits: List<BannedApiScanHit>,
        canceled: Boolean,
    ): String {
        val detail = if (hits.size > MAX_DETAIL_ENTRIES) hits.subList(0, MAX_DETAIL_ENTRIES) else hits
        val truncated = totalHits > detail.size
        val sb = StringBuilder(512 + detail.size * 96)
        sb.appendLine(MpSqlVisualizerBundle.message("scan.bannedApi.report.title"))
        sb.appendLine(MpSqlVisualizerBundle.message("scan.bannedApi.report.project", projectName))
        sb.appendLine(MpSqlVisualizerBundle.message("scan.bannedApi.report.scanned", scannedFiles.toString()))
        sb.appendLine(MpSqlVisualizerBundle.message("scan.bannedApi.report.total", totalHits.toString()))
        if (canceled) sb.appendLine(MpSqlVisualizerBundle.message("scan.bannedApi.report.canceled"))
        sb.appendLine()

        if (totalHits > 0) {
            sb.appendLine(MpSqlVisualizerBundle.message("scan.bannedApi.report.byRule"))
            groupedCounts(detail).forEach { group ->
                sb.appendLine(
                    MpSqlVisualizerBundle.message(
                        "scan.bannedApi.report.ruleEntry",
                        levelLabel(group.level),
                        group.target,
                        group.count.toString(),
                        group.description,
                    ),
                )
            }
            sb.appendLine()
            sb.appendLine(MpSqlVisualizerBundle.message("scan.bannedApi.report.details"))
            detail.forEach { hit ->
                sb.appendLine(
                    MpSqlVisualizerBundle.message(
                        "scan.bannedApi.report.hitEntry",
                        hit.filePath,
                        hit.line.toString(),
                        hit.targetSummary(),
                        hit.description,
                    ),
                )
            }
        }

        if (truncated) {
            sb.appendLine()
            sb.appendLine(
                MpSqlVisualizerBundle.message(
                    "scan.bannedApi.report.truncated",
                    MAX_DETAIL_ENTRIES.toString(),
                ),
            )
        }
        return sb.toString()
    }

    /** 单条规则的统计行（报告「按规则统计」段）。 */
    internal data class RuleGroup(
        val target: String,
        val level: String,
        val description: String,
        val count: Int,
    )

    /** 按「目标 + 级别 + 说明」聚合（同规则不同说明不会混行；保持首次出现顺序）。 */
    internal fun groupedCounts(hits: List<BannedApiScanHit>): List<RuleGroup> {
        val order = LinkedHashMap<String, RuleGroup>()
        for (hit in hits) {
            val key = "${hit.classFqn}#${hit.methodName}\u0000${hit.level}\u0000${hit.description}"
            val existing = order[key]
            order[key] = if (existing == null) {
                RuleGroup(hit.targetSummary(), hit.level, hit.description, 1)
            } else {
                existing.copy(count = existing.count + 1)
            }
        }
        return order.values.toList()
    }

    private fun levelLabel(level: String): String = MpSqlVisualizerBundle.message(
        if (level == BannedApiRule.LEVEL_ERROR) "settings.bannedApi.level.error"
        else "settings.bannedApi.level.warning",
    )
}
