package cn.song.mybatisplus.visualizer.share.format

import cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages
import cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.DialectCapabilities
import cn.song.mybatisplus.visualizer.sql.MultiDialectRenderPlan
import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * R50 一键诊断报告（诊断面聚合进既有分享通道；V74 已证伪降级路径）。
 *
 * 七环诊断产物散在五处（SQL+参数 / 20 条 perf 规则 / EXPLAIN 索引建议 / 无界写检测 / 回滚预案 /
 * 方言能力），发 MR 或缺陷单要手动拼。本对象把它们聚合成一份自包含 Markdown / HTML 报告，
 * 经既有 [cn.song.mybatisplus.visualizer.share.ui.ShareDialog] 分享通道导出（不新增 MCP 工具、不新增弹窗形态）。
 *
 * ## V74 诚实降级（IDE 侧诊断报告取不到 EXPLAIN 建议数据）
 * T-EI-01 定案：IDE 内**嵌原生控制台的结果行**无程序化读取面（网格由 DBN 托管），本格式化器
 * （经 [cn.song.mybatisplus.visualizer.share.ui.ShareDialog] 导出）因此**取不到**建议数据。故 IDE 侧
 * 诊断报告的「EXPLAIN 索引建议」段**如实说明不可得 + 给出获取路径**（不臆造、不静默留白）；
 * 回滚预案在无快照路径时（`Rating.UNABLE`）输出 [RollbackSqlGenerator] 的原始降级警示。
 *
 * 2026-10-31 订正：原文写作「[ExplainIndexAdvisor] 的建议节只挂在 MCP 面」，措辞过宽——**结构化
 * EXPLAIN 面板另有建议条**（`ui/ExplainPlanResultView` 的 hintsLabel，onPlan 回调里由
 * `ExplainIndexAdvisor.advise` 产出）。准确边界是「**本诊断报告这条导出链**取不到」，不是「建议只存在于 MCP」。
 *
 * 纯逻辑层：只依赖分析器（无 IDE 类型），普通 JUnit 可直接覆盖格式与聚合。
 */
object DiagnosticReportFormatter {

    private val TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * 报告输入（各诊断段为**已渲染文本行**——聚合层不重复分析，只组装）。
     */
    data class Input(
        val sql: String,
        val sources: List<String> = emptyList(),
        val paramValues: Map<String, String> = emptyMap(),
        val tableName: String? = null,
        val dialect: String? = null,
        val source: String? = null,
        /** 性能建议行（空 = 未发现问题）。 */
        val perfLines: List<String> = emptyList(),
        /** EXPLAIN 索引建议行；**null = IDE 面不可得**（输出诚实说明段）。 */
        val explainLines: List<String>? = null,
        /** 无界写检测行（空 = 未发现）。 */
        val unboundedLines: List<String> = emptyList(),
        /** 回滚预案行（含降级说明）。 */
        val rollbackLines: List<String> = emptyList(),
        /** 方言能力矩阵行（R52 呈现层触点二）。 */
        val capabilityLines: List<String> = emptyList(),
        /**
         * NEW-21 信创兼容性体检行（一次渲染全部注册方言 + 差异分类）。
         *
         * 空列表 = **本输入没有渲染源**（既无 SQL 文本也无语句模型），段内输出诚实说明；
         * 非空 = 每方言一行（`方言名: 差异摘要 / 无差异`），首行为总耗时摘要。
         */
        val compatLines: List<String> = emptyList(),
    )

    /** 从 SQL + 方言名聚合各诊断段（纯逻辑；IDE 侧与测试共用）。 */
    fun buildInput(
        sql: String,
        dialectName: String?,
        sources: List<String> = emptyList(),
        paramValues: Map<String, String> = emptyMap(),
        tableName: String? = null,
        source: String? = null,
        /** NEW-21：预览链已有的话句模型（Wrapper/XML）——非 null 时体检段逐方言**真渲染**。 */
        statement: SqlStatement? = null,
        /** NEW-21：体检参与方言（默认全部注册方言）。 */
        compatDialects: List<cn.song.mybatisplus.visualizer.sql.Dialect> =
            DialectRegistry.dialects,
    ): Input {
        val perfLines = SqlPerfAnalyzer.analyze(sql).map { w ->
            "[" + w.severity.name + "] " + runCatching {
                MpSqlVisualizerBundle.message(w.messageKey, *w.messageArgs.toTypedArray())
            }.getOrDefault(w.messageKey)
        }
        val unboundedLines = UnboundedWriteDetector.detect(sql).kinds().sorted().map { kind ->
            runCatching { MpSqlVisualizerBundle.message(ExecuteConfirmMessages.messageKeyOf(kind)) }
                .getOrDefault(kind.name)
        }
        val rollback = RollbackSqlGenerator.generate(sql)
        val rollbackLines = buildRollbackLines(rollback)
        val capabilityLines = DialectRegistry.byName(dialectName).let { dialect ->
            DialectCapabilities.summaryText(dialect).lines()
        }
        val compatLines = buildCompatLines(sql, statement, compatDialects)
        return Input(
            sql = sql,
            sources = sources,
            paramValues = paramValues,
            tableName = tableName,
            dialect = dialectName,
            source = source,
            perfLines = perfLines,
            // IDE 面无 EXPLAIN 结果可读（V74）→ null 触发诚实说明段
            explainLines = null,
            unboundedLines = unboundedLines,
            rollbackLines = rollbackLines,
            capabilityLines = capabilityLines,
            compatLines = compatLines,
        )
    }

    /**
     * NEW-21：体检段行（每方言一行 + 首行耗时摘要）。
     *
     * 有语句模型 → [MultiDialectRenderPlan.Source.Model]（逐方言真渲染，差异含分页/引用形态）；
     * 只有 SQL 文本 → [MultiDialectRenderPlan.Source.Text]（产物即原文，差异按文本证据判定，
     * 分页/函数/保留字三档仍可判，隐式转换档如实标注不可判）。
     * 两者都没有（SQL 为空）→ 空列表（段内输出诚实说明）。
     */
    private fun buildCompatLines(
        sql: String,
        statement: SqlStatement?,
        dialects: List<cn.song.mybatisplus.visualizer.sql.Dialect>,
    ): List<String> {
        val source = statement?.let { MultiDialectRenderPlan.Source.Model(it) }
            ?: sql.takeIf { it.isNotBlank() }?.let { MultiDialectRenderPlan.Source.Text(it) }
            ?: return emptyList()
        val result = MultiDialectRenderPlan.plan(source, dialects)
        val lines = mutableListOf<String>()
        lines += MpSqlVisualizerBundle.message("diag.report.compat.summary", result.rows.size, result.differenceCount, result.elapsedMillis)
        for (note in result.notes) {
            lines += runCatching { MpSqlVisualizerBundle.message(note) }.getOrDefault(note)
        }
        for (row in result.rows) {
            if (row.differences.isEmpty()) {
                lines += MpSqlVisualizerBundle.message("diag.report.compat.rowOk", row.dialectName)
            } else {
                val details = row.differences.joinToString("; ") { diff -> renderDifference(diff) }
                lines += MpSqlVisualizerBundle.message("diag.report.compat.rowDiff", row.dialectName, details)
            }
        }
        return lines
    }

    /** 单条差异文案（分类前缀 + 本地化正文）。 */
    private fun renderDifference(diff: MultiDialectRenderPlan.Difference): String {
        val text = runCatching {
            MpSqlVisualizerBundle.message(diff.messageKey, *diff.args.toTypedArray())
        }.getOrDefault(diff.messageKey)
        val label = runCatching {
            MpSqlVisualizerBundle.message("compat.category.${diff.category.name.lowercase()}")
        }.getOrDefault(diff.category.name)
        return "[$label] $text"
    }

    /** 回滚预案段：评级 + 自查模板 + 语句条数；UNABLE 时输出原始降级警示（V74 回滚降级文案）。 */
    private fun buildRollbackLines(result: RollbackSqlGenerator.RollbackResult): List<String> {
        val lines = mutableListOf<String>()
        lines += MpSqlVisualizerBundle.message("diag.report.rollback.rating", result.rating.name)
        result.targetTable?.let { lines += MpSqlVisualizerBundle.message("diag.report.rollback.table", it) }
        result.snapshotSelectSql?.let { lines += MpSqlVisualizerBundle.message("diag.report.rollback.snapshot", it) }
        result.countCheckSql?.let { lines += MpSqlVisualizerBundle.message("diag.report.rollback.countCheck", it) }
        if (result.statements.isEmpty()) {
            lines += MpSqlVisualizerBundle.message("diag.report.rollback.none")
        } else {
            lines += MpSqlVisualizerBundle.message("diag.report.rollback.statementCount", result.statements.size)
        }
        for (warning in result.warnings) {
            lines += runCatching {
                MpSqlVisualizerBundle.message(warning.messageKey, *warning.messageArgs.toTypedArray())
            }.getOrDefault(warning.messageKey)
        }
        return lines
    }

    /**
     * 生成 Markdown 诊断报告（五段聚合 + 方言能力段）。
     */
    fun format(input: Input): String {
        val timestamp = LocalDateTime.now().format(TIMESTAMP_FMT)
        val sb = StringBuilder(2048)
        sb.append("# ").append(MpSqlVisualizerBundle.message("diag.report.title")).append("\n\n")
        sb.append("> ").append(metaLine(input, timestamp)).append("\n\n")

        sb.append("## ").append(MpSqlVisualizerBundle.message("diag.report.section.sql")).append("\n")
        sb.append("```sql\n").append(input.sql.trim()).append("\n```\n\n")

        sb.append("## ").append(MpSqlVisualizerBundle.message("diag.report.section.params")).append("\n")
        appendParams(sb, input)

        sb.append("\n## ").append(MpSqlVisualizerBundle.message("diag.report.section.perf")).append("\n")
        appendLines(sb, input.perfLines, MpSqlVisualizerBundle.message("diag.report.perf.none"))

        sb.append("\n## ").append(MpSqlVisualizerBundle.message("diag.report.section.explain")).append("\n")
        appendExplain(sb, input)

        sb.append("\n## ").append(MpSqlVisualizerBundle.message("diag.report.section.unbounded")).append("\n")
        appendLines(sb, input.unboundedLines, MpSqlVisualizerBundle.message("diag.report.unbounded.none"))

        sb.append("\n## ").append(MpSqlVisualizerBundle.message("diag.report.section.rollback")).append("\n")
        appendLines(sb, input.rollbackLines, MpSqlVisualizerBundle.message("diag.report.rollback.none"))

        sb.append("\n## ").append(MpSqlVisualizerBundle.message("diag.report.section.capabilities")).append("\n")
        appendLines(sb, input.capabilityLines, MpSqlVisualizerBundle.message("diag.report.none"))

        sb.append("\n## ").append(MpSqlVisualizerBundle.message("diag.report.section.compat")).append("\n")
        appendLines(sb, input.compatLines, MpSqlVisualizerBundle.message("diag.report.compat.none"))

        return sb.toString()
    }

    /**
     * 生成自包含 HTML 诊断报告（同段聚合，供浏览器查看/附件发送）。
     */
    fun formatHtml(input: Input): String {
        val timestamp = LocalDateTime.now().format(TIMESTAMP_FMT)
        val sb = StringBuilder(4096)
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">\n")
        sb.append("<title>").append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.title"))).append("</title>\n")
        sb.append("<style>\n")
        sb.append("body{font-family:Consolas,Monaco,'Courier New',monospace;margin:20px;background:#282c34;color:#abb2bf}\n")
        sb.append("h1{color:#61afef;font-size:18px;margin-bottom:8px}\n")
        sb.append("h2{color:#61afef;font-size:14px;margin-top:20px}\n")
        sb.append(".meta{color:#7f848e;font-size:12px;margin-bottom:16px}\n")
        sb.append("pre{background:#1e1e1e;border-radius:6px;padding:16px;overflow-x:auto}\n")
        sb.append("ul{margin:4px 0 0 0;padding-left:20px}\nli{font-size:13px;line-height:1.6}\n")
        sb.append(".none{color:#7f848e;font-style:italic}\n")
        sb.append("</style></head><body>\n")
        sb.append("<h1>").append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.title"))).append("</h1>\n")
        sb.append("<div class=\"meta\">").append(escapeHtml(metaLine(input, timestamp))).append("</div>\n")

        sb.append("<h2>").append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.section.sql"))).append("</h2>\n")
        sb.append("<pre><code>").append(escapeHtml(input.sql.trim())).append("</code></pre>\n")

        sb.append("<h2>").append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.section.params"))).append("</h2>\n")
        appendParamsHtml(sb, input)

        appendSectionHtml(sb, "diag.report.section.perf", input.perfLines, "diag.report.perf.none")
        appendExplainHtml(sb, input)
        appendSectionHtml(sb, "diag.report.section.unbounded", input.unboundedLines, "diag.report.unbounded.none")
        appendSectionHtml(sb, "diag.report.section.rollback", input.rollbackLines, "diag.report.rollback.none")
        appendSectionHtml(sb, "diag.report.section.capabilities", input.capabilityLines, "diag.report.none")
        appendSectionHtml(sb, "diag.report.section.compat", input.compatLines, "diag.report.compat.none")

        sb.append("</body></html>")
        return sb.toString()
    }

    // ---------- 段组装辅助 ----------

    private fun metaLine(input: Input, timestamp: String): String {
        val parts = mutableListOf<String>()
        input.source?.let { parts += MpSqlVisualizerBundle.message("diag.report.source", it) }
        input.tableName?.let { parts += MpSqlVisualizerBundle.message("diag.report.table", it) }
        input.dialect?.let { parts += MpSqlVisualizerBundle.message("diag.report.dialect", it) }
        parts += MpSqlVisualizerBundle.message("diag.report.generated", timestamp)
        return parts.joinToString(" | ")
    }

    private fun appendParams(sb: StringBuilder, input: Input) {
        if (input.sources.isEmpty() && input.paramValues.isEmpty()) {
            sb.append(MpSqlVisualizerBundle.message("diag.report.params.none")).append("\n")
            return
        }
        sb.append("| ").append(MpSqlVisualizerBundle.message("diag.report.params.name")).append(" | ")
            .append(MpSqlVisualizerBundle.message("diag.report.params.value")).append(" |\n")
        sb.append("| --- | --- |\n")
        val names = input.sources.ifEmpty { input.paramValues.keys.toList() }
        for (name in names) {
            val value = input.paramValues[name].orEmpty()
            sb.append("| ").append(name).append(" | ").append(if (value.isEmpty()) "?" else value).append(" |\n")
        }
    }

    private fun appendParamsHtml(sb: StringBuilder, input: Input) {
        if (input.sources.isEmpty() && input.paramValues.isEmpty()) {
            sb.append("<p class=\"none\">").append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.params.none"))).append("</p>\n")
            return
        }
        sb.append("<ul>\n")
        val names = input.sources.ifEmpty { input.paramValues.keys.toList() }
        for (name in names) {
            val value = input.paramValues[name].orEmpty().ifEmpty { "?" }
            sb.append("<li>").append(escapeHtml(name)).append(" = ").append(escapeHtml(value)).append("</li>\n")
        }
        sb.append("</ul>\n")
    }

    private fun appendExplain(sb: StringBuilder, input: Input) {
        if (input.explainLines == null) {
            sb.append(MpSqlVisualizerBundle.message("diag.report.explain.unavailable")).append("\n")
            return
        }
        appendLines(sb, input.explainLines, MpSqlVisualizerBundle.message("diag.report.none"))
    }

    private fun appendExplainHtml(sb: StringBuilder, input: Input) {
        if (input.explainLines == null) {
            sb.append("<h2>").append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.section.explain"))).append("</h2>\n")
            sb.append("<p class=\"none\">")
                .append(escapeHtml(MpSqlVisualizerBundle.message("diag.report.explain.unavailable")))
                .append("</p>\n")
            return
        }
        appendSectionHtml(sb, "diag.report.section.explain", input.explainLines, "diag.report.none")
    }

    private fun appendLines(sb: StringBuilder, lines: List<String>, noneText: String) {
        if (lines.isEmpty()) {
            sb.append(noneText).append("\n")
            return
        }
        for (line in lines) sb.append("- ").append(line).append("\n")
    }

    private fun appendSectionHtml(sb: StringBuilder, titleKey: String, lines: List<String>, noneKey: String) {
        sb.append("<h2>").append(escapeHtml(MpSqlVisualizerBundle.message(titleKey))).append("</h2>\n")
        if (lines.isEmpty()) {
            sb.append("<p class=\"none\">").append(escapeHtml(MpSqlVisualizerBundle.message(noneKey))).append("</p>\n")
            return
        }
        sb.append("<ul>\n")
        for (line in lines) sb.append("<li>").append(escapeHtml(line)).append("</li>\n")
        sb.append("</ul>\n")
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
