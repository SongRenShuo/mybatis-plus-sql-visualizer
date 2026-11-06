package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.model.PreviewResult

/**
 * 交互面超大 SQL 护栏（F-09）。
 *
 * 预览面板与参数面板对 SQL 文本做的事分两类：
 *  - **正文**：把文本写进编辑器文档——执行链与导出都读它，**永远不截断**（丢字等于丢执行能力）；
 *  - **装饰**：语法高亮增强、自动折叠、逐行错列标注、参数逐值输入行——成本随文本长度/行数线性增长，
 *    而且只是「看得更舒服」，与能否执行无关。
 *
 * 本对象只给后一类定阈值：超限就把装饰整体关掉（不是截断后只装饰前半段——半截标注比没有更误导），
 * 并在超过渲染上限时要求面板挂一条「原文完整保留，可在导出/执行链使用」的提示。
 *
 * 阈值（按字符数计，不接设置 UI——这是防假死的护栏，调大只会失去保护）：
 *  - 200KB：关闭装饰（折叠/逐行标注/参数逐值渲染）；
 *  - 1MB：追加「已降级」提示（装饰仍是关闭的，只是让用户知道为什么没有标注）。
 *
 * 纯函数，零 IDE 依赖。
 */
object SqlSizeGuard {

    /** 关闭装饰的阈值：200K 字符。 */
    const val DECORATION_LIMIT_CHARS: Int = 200 * 1024

    /** 追加降级提示的阈值：1M 字符。 */
    const val RENDER_LIMIT_CHARS: Int = 1024 * 1024

    /** 护栏档位。 */
    enum class Level {
        /** 预算内：装饰照常。 */
        FULL,

        /** 超过装饰阈值：关装饰，不额外提示（用户仍在正常预览）。 */
        SKIP_DECORATION,

        /** 超过渲染阈值：关装饰 + 提示「原文完整保留」。 */
        NOTICE_ONLY,
    }

    /** 一次判定结果。 */
    data class Plan(val level: Level, val chars: Int) {
        /** 是否允许跑装饰（折叠 / 逐行标注 / 语法增强）。 */
        val decorationsEnabled: Boolean get() = level == Level.FULL

        /** 是否允许参数面板逐值建输入行。 */
        val perValueParamsEnabled: Boolean get() = level == Level.FULL

        /** 是否需要挂「原文完整保留」提示。 */
        val showRetentionNotice: Boolean get() = level == Level.NOTICE_ONLY
    }

    /** 判定一段 SQL 文本的护栏档位（null/空文本恒为 [Level.FULL]）。 */
    fun plan(text: String?): Plan {
        val chars = text?.length ?: 0
        val level = when {
            chars > RENDER_LIMIT_CHARS -> Level.NOTICE_ONLY
            chars > DECORATION_LIMIT_CHARS -> Level.SKIP_DECORATION
            else -> Level.FULL
        }
        return Plan(level, chars)
    }
}

/**
 * F-09：参数条数汇总——超大 SQL 跳过逐值渲染时，用它告诉用户「省掉了多少行」。
 *
 * 取各来源的最大值而非求和：同一批参数在不同模型里重复出现（`?` 位点数 / 条件行 / 批量列 /
 * XML 控件），求和会把一个参数算两遍。
 */
fun paramCountOf(result: PreviewResult): Int = listOf(
    result.unresolvedParams.size,
    result.previewModel?.conditionViews?.size ?: 0,
    (result.xmlModel?.conditionViews?.size ?: 0) + (result.xmlModel?.forEachViews?.size ?: 0),
    result.batchModel?.let { it.toggleColumns.size + it.fixedColumns.size } ?: 0,
).maxOrNull() ?: 0
