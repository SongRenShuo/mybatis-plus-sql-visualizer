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
 * @param typeText     F-01 类型感知字面量：类型标注（Java 类型名 / JDBC 类型名 /
 *                     [cn.song.mybatisplus.visualizer.xml.ParamKind] 枚举名，三来源归一由
 *                     [TypedLiteralRenderer.classify] 承担）；null = 无类型信息 → 渲染链
 *                     走既有 quote/启发式回退，行为与升级前逐字节一致
 */
data class ParamHint(
    val quote: Boolean? = null,
    val likePosition: LikePosition? = null,
    val inList: Boolean = false,
    val typeText: String? = null,
)
