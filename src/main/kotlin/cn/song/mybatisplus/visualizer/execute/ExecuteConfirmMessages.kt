package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectCapabilities
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * T-UnboundedWriteGuard：执行确认消息构建（纯函数，零 Swing 依赖可直接测）。
 *
 * 消费方三处确认流共用（LogToSqlPanel.executeStatement / SqlPreviewPanel.executeSql /
 * SqlPreviewPanel.executeAdhocSql）——同一对话框形态只换 message 文案：
 *  - 无危险发现且无能力缺口：文案逐字节走既有 `execute.confirm.dml.message`（频率/形态零变化）；
 *  - 有危险发现：摘要行 + 按类别去重的发现清单（每类一行，文案中性说事实）。
 *
 * R52（方言能力自检）：第三参 [dialect] 非空时追加**方言能力缺口**发现（[DialectCapabilities.gapsFor]）
 * ——SQL 用到了本方言不支持的形态（DELETE LIMIT / UPSERT / identity 包装）时执行前如实提示。
 * [dialect]=null 时逐字节维持既有行为（旧调用点与既有测试零回归）。
 *
 * 不新增弹窗形态、不新增步骤（弹窗克制红线 `docs/规范/设计规范-弹窗克制红线.md`）。
 */
object ExecuteConfirmMessages {

    /**
     * 无任何发现 → 既有文案；有险 → 升级为发现清单。
     *
     * @param dialect           当前生效方言（R52；null = 不做能力自检）
     * @param additionalFindings R46 等上游预检已渲染好的发现行（含 `• ` 前缀；无无界写发现时
     *                           与能力缺口同走 `execute.confirm.precheck.summary` 抬头）
     */
    fun build(
        sql: String,
        keyword: String,
        dialect: Dialect? = null,
        additionalFindings: List<String> = emptyList(),
    ): String {
        val risk = UnboundedWriteDetector.detect(sql)
        val riskLines = if (risk.dangerous) {
            risk.kinds().sorted().map { kind -> "• " + MpSqlVisualizerBundle.message(messageKeyOf(kind)) }
        } else {
            emptyList()
        }
        val gapLines = dialect?.let { d ->
            DialectCapabilities.gapsFor(sql, d).map { gap ->
                "• " + MpSqlVisualizerBundle.message(gap.messageKey, gap.dialectName)
            }
        }.orEmpty()
        // 全部为空 → 既有文案逐字节不变（确认流零变化红线）
        if (riskLines.isEmpty() && gapLines.isEmpty() && additionalFindings.isEmpty()) {
            return MpSqlVisualizerBundle.message("execute.confirm.dml.message", keyword)
        }
        // 无无界写发现（仅能力缺口/预检发现）→ 通用预检抬头（不冒用「无界写特征」）
        if (riskLines.isEmpty()) {
            return MpSqlVisualizerBundle.message(
                "execute.confirm.precheck.summary",
                keyword,
                (gapLines + additionalFindings).joinToString("\n"),
            )
        }
        // 有无界写发现（可同时带能力缺口/预检发现）→ 走既有 unbounded.summary（既有测试逐字节钉住）
        return MpSqlVisualizerBundle.message(
            "execute.confirm.unbounded.summary",
            keyword,
            (riskLines + gapLines + additionalFindings).joinToString("\n"),
        )
    }

    /** 发现类别 → 文案键（单一映射点；键值见 bundle execute.confirm.unbounded.* 段）。 */
    internal fun messageKeyOf(kind: UnboundedWriteDetector.FindingKind): String = when (kind) {
        UnboundedWriteDetector.FindingKind.MISSING_WHERE -> "execute.confirm.unbounded.missingWhere"
        UnboundedWriteDetector.FindingKind.TAUTOLOGY -> "execute.confirm.unbounded.tautology"
        UnboundedWriteDetector.FindingKind.OR_TAUTOLOGY -> "execute.confirm.unbounded.orTautology"
        UnboundedWriteDetector.FindingKind.LIKE_WILDCARD -> "execute.confirm.unbounded.likeWildcard"
        UnboundedWriteDetector.FindingKind.NO_COLUMN_REF -> "execute.confirm.unbounded.noColumnRef"
        UnboundedWriteDetector.FindingKind.SUBQUERY -> "execute.confirm.unbounded.subquery"
        UnboundedWriteDetector.FindingKind.PARSE_FAILED -> "execute.confirm.unbounded.parseFailed"
    }
}

