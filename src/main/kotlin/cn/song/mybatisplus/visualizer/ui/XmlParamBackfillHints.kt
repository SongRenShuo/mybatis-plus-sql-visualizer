package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.ParameterMapping
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel

/**
 * XML 预览参数回填提示组装（O-81 T-XmlListParamSplit）：面板 XML 分支 [SqlPreviewPanel.renderSql]
 * 每帧对全部 `#{}` source 组装 [ParamHint]（quote / inList），供 `renderParamValue` 回填渲染。
 *
 * 拆分决策双通道（先红读数实证，见计划 §二）：
 *  - **签名通道（R1 主修复）**：`paramTypeFor(source).kind == COLLECTION`（mapper 方法签名
 *    `List<String>` 等，`XmlParamTypeResolver` 产物）→ 逗号输入**无条件**按多元素渲染——
 *    用户输入 `PI,WP` 期望 `IN ('PI', 'WP')`，不应再押注 SQL 形态探测；
 *    引号按元素类别派生（`List<String>`→TEXT→引号、`List<Long>`→NUMBER→裸渲染，
 *    与 O-78 foreach item 派生同口径）——升级前集合恒引号，`List<Long>` 输入 `1,2` 出
 *    `('1','2')`（先红读数实证）；
 *  - **形态通道（升级前行为）**：`?` 被 [SqlPreviewPanel.detectInClauseParams] 判进 `IN (...)`
 *    区间 → 拆分。签名信息缺失（接口不可解析 / Map 入参）时的唯一通道。
 *
 * 显式 `typeHandler` 的集合映射不拆（自定义序列化形态，逗号拆分反而破坏语义——宁缺勿错）。
 *
 * 纯函数零平台依赖（xml/sql 层类型），可独立单测（testFast 桶）。
 */
internal fun xmlBackfillHints(
    xml: XmlPreviewModel,
    sources: List<String>,
    shapeInListIndices: Set<Int>,
    mappings: List<ParameterMapping> = emptyList(),
): List<ParamHint> = sources.mapIndexed { index, source ->
    val info = xml.paramTypeFor(source)
    if (info?.kind == ParamKind.COLLECTION && !hasExplicitTypeHandler(mappings, source)) {
        ParamHint(
            quote = info.elementKind?.quoteRequired ?: xml.quoteFor(source) ?: true,
            inList = true,
            // T-DateLiteralBackfill：List<LocalDate>/List<LocalDateTime> 的每个元素产 ANSI 字面量
            temporal = info.elementKind == ParamKind.TEMPORAL,
        )
    } else {
        ParamHint(
            quote = xml.quoteFor(source) ?: true,
            inList = index in shapeInListIndices,
            temporal = info?.kind == ParamKind.TEMPORAL,
        )
    }
}

/** 该 `#{}` 映射是否显式声明了 typeHandler（`#{p,typeHandler=X}` 形态）。 */
private fun hasExplicitTypeHandler(mappings: List<ParameterMapping>, source: String): Boolean =
    mappings.firstOrNull { it.property == source }?.attributes?.containsKey("typeHandler") == true
