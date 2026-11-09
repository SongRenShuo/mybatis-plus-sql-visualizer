package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.LikePosition

/**
 * 单个 `?` 的回填提示（M13）：与 [GeneratedSql.unresolvedParams] 并行，
 * 在生成时根据列类型与 LIKE 位置计算，供面板按用户输入值回填渲染（引号 / 百分号）。
 *
 * @param quote        该值是否必须加引号：true=String 列恒引号；false=数值等列恒原样；
 *                     null=列类型未知（面板走现有 NUMBER_REGEX 启发式）
 * @param likePosition 非 null 表示该 `?` 属于 LIKE 模式（恒引号，并按位置拼 `%`）
 * @param inList       M19.6（2026-08-13 用户实测）：该 `?` 属于 IN 列表（`col IN (?, ?)`）——
 *                     面板回填时按逗号分割多值，每个值独立渲染（如输入 `1,2` → `('1','2')`）
 * @param temporal     T-DateLiteralBackfill：该值已知是日期/时间族——渲染时先试 ANSI 类型字面量
 *                     （`DATE '…'` / `TIMESTAMP '…'`，见 [SqlDateLiteral]），形态不匹配再走 [quote]。
 *                     默认 false = 既有行为（引号/启发式），保证既有构造与 `equals` 不破。
 */
data class ParamHint(
    val quote: Boolean? = null,
    val likePosition: LikePosition? = null,
    val inList: Boolean = false,
    val temporal: Boolean = false,
)
