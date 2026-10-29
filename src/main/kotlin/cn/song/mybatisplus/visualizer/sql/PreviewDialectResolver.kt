package cn.song.mybatisplus.visualizer.sql

import com.intellij.openapi.project.Project

/**
 * T-DialectInferFix：预览链路唯一的方言决策入口。
 *
 * 为什么需要这一层：预览有多条来源（PSI/Wrapper、XML、JPA、SQL 日志、历史、MCP），每条来源能拿到的
 * 证据不同。此前渲染端各自 `result.effectiveDialect ?: MySqlDialect`，临时文件映射端又在
 * [cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.mapPreviewDialectBeforePsi] 里独立落 MySQL 兜底，
 * 两侧可以指向不同方言——用户看到的「文本是一种方言、报错是另一种方言」就是这么来的。
 *
 * 统一优先级（强→弱），与 [DialectRegistry.decision] 内部一致：
 *  1. 本次执行连接的精确方言（日志/历史条目捕获的 JDBC URL，`EXACT`）；
 *  2. 面板已绑定 / 上次使用的数据源（`STRONG`）；
 *  3. 项目数据源候选（`CONSENSUS`/`STRONG`）；
 *  4. 项目配置文件 JDBC URL → 构建文件驱动坐标（`FALLBACK`，无数据源时的主力证据）；
 *  5. SQL 文本方言独占特征（`FALLBACK`）；
 *  6. 平台为该文件配置/继承的 SQL 方言（[inheritedDialect]，仅在映射侧可用）；
 *  7. 仍未判定 → 保持 null，由调用方**继承平台设置**，不再硬编码 MySQL。
 */
internal object PreviewDialectResolver {

    /** 归一化到注册方言名；未知/空 → null（绝不回落到 MySQL）。 */
    fun knownDialect(name: String?): String? {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DialectRegistry.dialects.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.name
    }

    /**
     * 从日志条目的目标库角标（`"PostgreSQL · cyzc_db1@host:5432"`）里取方言名。
     *
     * 兜底用途：旧条目没有 pd 载荷里的 `dl` 键，但角标随 `-- … | db: …` 头持久化过。
     * 角标里的显示名与注册名在 MySQL/PostgreSQL/Oracle/SQL Server/H2/SQLite/MariaDB/DM/KingBase/
     * OceanBase/openGauss 上同名；`HSQL`/`GBASE` 无注册方言 → null（不猜近似方言）。
     */
    fun dialectFromBadge(badge: String?): String? {
        val head = badge?.substringBefore('·')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return knownDialect(head)
    }

    /** 「本次执行连接的方言」决策（EXACT 证据，不参与候选投票）。 */
    fun capturedDecision(dialectName: String): DialectDecisionEngine.Decision =
        DialectDecisionEngine.Decision(
            status = DialectDecisionEngine.Status.RESOLVED,
            dialectName = dialectName,
            confidence = DialectDecisionEngine.Confidence.EXACT,
            source = DialectDecisionEngine.Source.CAPTURED_URL,
        )

    /**
     * 预览结果 → 方言决策。
     *
     * @param capturedDialect 该预览来源自带的精确方言（日志/历史条目）；
     * @param boundDataSourceDialect 面板已绑定/上次使用数据源方言（调用方须在后台线程取好再传）；
     * @param sqlText 本次 SQL 文本（最后一层语法特征证据）。
     */
    fun resolve(
        project: Project?,
        capturedDialect: String? = null,
        boundDataSourceDialect: String? = null,
        sqlText: String? = null,
    ): DialectDecisionEngine.Decision = DialectRegistry.decision(
        project = project,
        boundDataSourceDialect = knownDialect(boundDataSourceDialect),
        sqlText = sqlText,
        capturedDialect = knownDialect(capturedDialect),
    )
}

/**
 * 给预览结果补齐方言（幂等）：
 *  - 已有 `dialectDecision` 且 `renderedDialect` 在场 → 原样返回；
 *  - 否则按 [PreviewDialectResolver.resolve] 求解；判出来就同时写**渲染方言**与**决策**，
 *    保证渲染端与临时文件映射端读到同一个值。
 */
internal fun cn.song.mybatisplus.visualizer.model.PreviewResult.withResolvedDialect(
    project: Project?,
    boundDataSourceDialect: String? = null,
): cn.song.mybatisplus.visualizer.model.PreviewResult {
    val captured = dialectDecision?.dialectName ?: renderedDialect?.name
    if (renderedDialect != null && dialectDecision != null) return this
    val decision = PreviewDialectResolver.resolve(
        project = project,
        capturedDialect = captured,
        boundDataSourceDialect = boundDataSourceDialect,
        sqlText = sql,
    )
    val name = decision.dialectName ?: return copy(dialectDecision = decision)
    return copy(renderedDialect = DialectRegistry.byName(name), dialectDecision = decision)
}
