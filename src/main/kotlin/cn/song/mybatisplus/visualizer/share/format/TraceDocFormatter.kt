package cn.song.mybatisplus.visualizer.share.format

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.ui.ExplainPlanDiff
import cn.song.mybatisplus.visualizer.ui.diff.SqlDiffer
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 单份执行计划快照（留痕文档输入）。
 *
 * 纯值类型：与 [cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot] 解耦（后者带持久化
 * 键/字节预算语义，属历史域）；[rows] 为驱动原始行列文本，[truncated] 是采集期截断标记。
 */
data class TracePlanSnapshot(
    val capturedAt: Long = 0L,
    /** `TABLE` / `TEXT` / `JSON` / `XML`（[ExplainOutputFormat] 名，空=未知）。 */
    val format: String = "",
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val truncated: Boolean = false,
) {
    fun raw(): McpExplainFormatter.RawResult = McpExplainFormatter.RawResult(headers, rows, truncated)
}

/**
 * 留痕文档输入：六段式模板的**事实来源** + 可选人工叙述。
 *
 * 事实字段（sql/dialect/source/capturedAt/executionTimeMs/plans）由导出入口从历史条目或预览
 * 面板取数；叙述字段（symptom/baseline/rootCause/action/conclusion/rollback）由调用方按需提供，
 * 缺失时模板留「（待补充）」，**不代笔、不推断**。
 *
 * [plans] 允许任意顺序：组装器按 [TracePlanSnapshot.capturedAt] 升序解释（最早=基线，最晚=当前）。
 */
data class TraceDocInput(
    val sql: String,
    val dialect: String? = null,
    /** 来源位置（如 `UserMapper.java:42`）；null/空白=未采集。 */
    val source: String? = null,
    /** 捕获时间（epoch ms）；0=未采集。 */
    val capturedAt: Long = 0L,
    /** 执行耗时（ms）；null=未采集。 */
    val executionTimeMs: Long? = null,
    val plans: List<TracePlanSnapshot> = emptyList(),
    val symptom: String? = null,
    val baseline: String? = null,
    val rootCause: String? = null,
    val action: String? = null,
    val conclusion: String? = null,
    val rollback: String? = null,
)

/**
 * 优化留痕文档组装器（FR-0045 第 6 项 / T-TraceDocExport S1）。
 *
 * 纯逻辑（无 PSI/Project/UI 依赖，裸 JUnit 可直测），形态对齐同目录 [MarkdownFormatter]。
 * 输出固定六段式 Markdown：现象 / 基线 / 根因 / 手段 / 前后对比 / 结论与回滚。
 *
 * **fail-closed 红线**：
 *  - 无执行计划 → 明写「无计划快照」，绝不臆造计划行；
 *  - 计划行为空 → 标「空计划快照」；
 *  - 缺耗时/来源/捕获时间 → 标「未采集」；方言未知 → 「未知」；
 *  - 单份计划不做前后对比（明写「没有可比基线」）。
 *
 * 前后对比口径：TABLE/JSON 档走 [ExplainPlanDiff]（rows/cost 增量 + 字段级变化）；
 * TEXT/XML 或结构化解析失败时退化为 [SqlDiffer] 行级差异，并在标题上明示「文本级」——
 * 不把文本行差异包装成代价对比。
 */
object TraceDocFormatter {

    private val TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val FILE_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    private const val PLACEHOLDER = "（待补充）"
    private const val NOT_CAPTURED = "未采集"

    /** 文本级对比最多列出的差异行（防超大计划把文档撑爆）。 */
    private const val MAX_TEXT_DIFF_ROWS = 100

    private val STRUCTURED_FORMATS = setOf(ExplainOutputFormat.TABLE, ExplainOutputFormat.JSON)

    fun format(input: TraceDocInput): String {
        val plans = input.plans.sortedBy { it.capturedAt }
        val sb = StringBuilder(2048)
        sb.append("# 优化留痕\n\n")
        appendSymptom(sb, input)
        appendBaseline(sb, input, plans)
        appendNarrative(sb, "3. 根因", input.rootCause)
        appendNarrative(sb, "4. 手段", input.action)
        appendComparison(sb, plans)
        appendConclusion(sb, input)
        return sb.toString()
    }

    /** 默认文件名：`trace-doc-<yyyyMMdd-HHmmss>.md`（到秒防同日多次导出静默覆盖）。 */
    fun defaultFileName(nowMs: Long = System.currentTimeMillis()): String =
        "trace-doc-" + FILE_TIME_FMT.format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())) + ".md"

    // ---------- 六段 ----------

    private fun appendSymptom(sb: StringBuilder, input: TraceDocInput) {
        sb.append("## 1. 现象\n\n")
        sb.append(narrative(input.symptom)).append("\n\n")
        sb.append("- 方言：").append(input.dialect?.takeIf { it.isNotBlank() } ?: "未知").append('\n')
        sb.append("- 来源：").append(input.source?.takeIf { it.isNotBlank() } ?: NOT_CAPTURED).append('\n')
        sb.append("- 捕获时间：").append(formatTime(input.capturedAt)).append('\n')
        sb.append("- 执行耗时：").append(input.executionTimeMs?.let { "$it ms" } ?: NOT_CAPTURED).append("\n\n")
    }

    private fun appendBaseline(sb: StringBuilder, input: TraceDocInput, plans: List<TracePlanSnapshot>) {
        sb.append("## 2. 基线\n\n")
        sb.append(narrative(input.baseline)).append("\n\n")
        sb.append("### 2.1 SQL 原文\n\n")
        appendFenced(sb, "sql", input.sql)
        sb.append("\n\n### 2.2 执行计划快照\n\n")
        if (plans.isEmpty()) {
            sb.append("无计划快照：本次留痕没有可用的执行计划，不对计划内容做任何推断。\n\n")
            return
        }
        plans.forEachIndexed { index, plan ->
            sb.append("#### 快照 ").append(index + 1).append("（")
                .append(formatTime(plan.capturedAt))
                .append("，").append(plan.format.takeIf { it.isNotBlank() } ?: "未知格式")
                .append("）\n\n")
            appendPlan(sb, plan)
            if (plan.truncated) sb.append("\n\n（采集时已截断，计划可能不完整。）")
            sb.append("\n\n")
        }
    }

    private fun appendNarrative(sb: StringBuilder, title: String, text: String?) {
        sb.append("## ").append(title).append("\n\n").append(narrative(text)).append("\n\n")
    }

    private fun appendComparison(sb: StringBuilder, plans: List<TracePlanSnapshot>) {
        sb.append("## 5. 前后对比\n\n")
        when {
            plans.isEmpty() -> {
                sb.append("无计划快照，无法进行前后对比。\n\n")
            }
            plans.size == 1 -> {
                sb.append("仅一份计划快照（").append(formatTime(plans.first().capturedAt))
                    .append("），没有可比基线。\n\n")
            }
            else -> {
                val before = plans.first()
                val after = plans.last()
                if (plans.size > 2) {
                    sb.append("共 ").append(plans.size).append(" 份快照，取最早与最新两份对比。\n\n")
                }
                val structured = structuredDiff(before, after)
                if (structured != null) {
                    sb.append(structured)
                } else {
                    sb.append(textDiff(before, after))
                }
                sb.append("\n\n")
            }
        }
    }

    private fun appendConclusion(sb: StringBuilder, input: TraceDocInput) {
        sb.append("## 6. 结论与回滚\n\n")
        sb.append("- 结论：").append(narrative(input.conclusion)).append('\n')
        sb.append("- 回滚：").append(narrative(input.rollback)).append('\n')
    }

    // ---------- 计划渲染 ----------

    private fun appendPlan(sb: StringBuilder, plan: TracePlanSnapshot) {
        if (plan.rows.isEmpty()) {
            sb.append("空计划快照：驱动未返回计划行，不做内容推断。")
            return
        }
        when (plan.format.uppercase(Locale.ROOT)) {
            ExplainOutputFormat.TEXT.name, ExplainOutputFormat.XML.name ->
                appendFenced(sb, "text", planText(plan))
            ExplainOutputFormat.JSON.name ->
                appendFenced(sb, "json", planText(plan))
            else -> appendPlanTable(sb, plan)
        }
    }

    private fun appendPlanTable(sb: StringBuilder, plan: TracePlanSnapshot) {
        if (plan.headers.isEmpty()) {
            appendFenced(sb, "text", planText(plan))
            return
        }
        sb.append("| ").append(plan.headers.joinToString(" | ") { escapeCell(it) }).append(" |\n")
        sb.append("| ").append(plan.headers.joinToString(" | ") { "---" }).append(" |\n")
        plan.rows.forEach { row ->
            val cells = plan.headers.indices.map { escapeCell(row.getOrNull(it).orEmpty()) }
            sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
        }
    }

    /** 计划文本化（TEXT/JSON 单列；多列按 ` | ` 平铺——只用于展示与文本级对比）。 */
    private fun planText(plan: TracePlanSnapshot): String =
        plan.rows.joinToString("\n") { row -> row.joinToString(" | ") { it.trimEnd() } }

    // ---------- 前后对比 ----------

    /** 结构化对比（TABLE/JSON 同档）；不可比返回 null，由调用方退化为文本级对比。 */
    private fun structuredDiff(before: TracePlanSnapshot, after: TracePlanSnapshot): String? {
        if (before.format.isBlank() || !before.format.equals(after.format, ignoreCase = true)) return null
        val format = runCatching {
            ExplainOutputFormat.valueOf(before.format.uppercase(Locale.ROOT))
        }.getOrNull() ?: return null
        if (format !in STRUCTURED_FORMATS) return null
        val diff = ExplainPlanDiff.diff(before.raw(), after.raw(), format) ?: return null
        val sb = StringBuilder()
        sb.append("结构化计划对比（早 → 晚）：\n\n")
        if (diff.isEmpty) {
            sb.append("两次计划在结构化字段上等价（无 rows/cost/字段变化）。")
            return sb.toString()
        }
        sb.append("| 节点 | rows 变化 | cost 变化 | 字段变化 |\n")
        sb.append("| --- | --- | --- | --- |\n")
        diff.changed.forEach { change ->
            sb.append("| ").append(escapeCell(change.label)).append(" | ")
                .append(change.rowsDelta?.let { deltaText(it.old, it.new) } ?: "-").append(" | ")
                .append(change.costDelta?.let { deltaText(it.old, it.new) } ?: "-").append(" | ")
                .append(escapeCell(change.fieldChanges.joinToString("; ") { "${it.name}: ${it.old} → ${it.new}" }))
                .append(" |\n")
        }
        if (diff.added.isNotEmpty()) {
            sb.append("\n新增节点：").append(diff.added.joinToString("、") { escapeInline(it) })
        }
        if (diff.removed.isNotEmpty()) {
            sb.append("\n移除节点：").append(diff.removed.joinToString("、") { escapeInline(it) })
        }
        return sb.toString()
    }

    /** 文本级行对比（TEXT/XML/结构化解析失败）；明示不是代价对比。 */
    private fun textDiff(before: TracePlanSnapshot, after: TracePlanSnapshot): String {
        val entries = SqlDiffer.computeDiff(planText(before), planText(after))
        val sb = StringBuilder()
        sb.append("文本级行对比（早 → 晚；TEXT/非结构化计划只做行差异，不推断代价）：\n\n")
        if (entries.isEmpty()) {
            sb.append("两份计划文本一致。")
            return sb.toString()
        }
        sb.append("| 变化 | 旧行号 | 新行号 | 行内容 |\n")
        sb.append("| --- | --- | --- | --- |\n")
        entries.take(MAX_TEXT_DIFF_ROWS).forEach { entry ->
            val kind = if (entry.type == SqlDiffer.DiffType.INSERT) "新增" else "删除"
            sb.append("| ").append(kind).append(" | ")
                .append(entry.oldLineNumber?.toString() ?: "-").append(" | ")
                .append(entry.newLineNumber?.toString() ?: "-").append(" | ")
                .append(escapeCell((entry.oldLine ?: entry.newLine).orEmpty())).append(" |\n")
        }
        if (entries.size > MAX_TEXT_DIFF_ROWS) {
            sb.append("\n（共 ").append(entries.size).append(" 行差异，已列前 ")
                .append(MAX_TEXT_DIFF_ROWS).append(" 行。）\n")
        }
        return sb.toString()
    }

    private fun deltaText(old: Number, new: Number): String {
        val delta = if (old is Long && new is Long) {
            (new - old).toString()
        } else {
            BigDecimal.valueOf(new.toDouble()).subtract(BigDecimal.valueOf(old.toDouble()))
                .stripTrailingZeros().toPlainString()
        }
        val sign = if (!delta.startsWith("-") && delta != "0") "+" else ""
        return "${numberText(old)} → ${numberText(new)} ($sign$delta)"
    }

    private fun numberText(value: Number): String =
        if (value is Double || value is Float) {
            BigDecimal.valueOf(value.toDouble()).stripTrailingZeros().toPlainString()
        } else {
            value.toString()
        }

    // ---------- 转义 / 围栏 ----------

    /**
     * 动态围栏长度：内容里出现 N 个连续反引号时用 N+1 个（至少 3 个）——
     * SQL/计划文本自带 ``` 时不会提前关闭代码块。
     */
    private fun fenceFor(text: String): String {
        var longest = 0
        var run = 0
        for (ch in text) {
            if (ch == '`') {
                run++
                if (run > longest) longest = run
            } else {
                run = 0
            }
        }
        return "`".repeat(maxOf(3, longest + 1))
    }

    private fun appendFenced(sb: StringBuilder, language: String, text: String) {
        val fence = fenceFor(text)
        sb.append(fence).append(language).append('\n')
        sb.append(text.trimEnd()).append('\n')
        sb.append(fence)
    }

    /** Markdown 表格单元格转义（反斜杠优先；竖线防串列；换行降 `<br>`）。 */
    private fun escapeCell(value: String): String = value
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("\r\n", "<br>")
        .replace("\n", "<br>")
        .replace("\r", "<br>")

    /** 段落内联文本（只压换行，不转表格竖线——用于表格外的节点清单）。 */
    private fun escapeInline(value: String): String =
        value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')

    private fun narrative(value: String?): String =
        value?.trim()?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER

    private fun formatTime(ts: Long): String =
        if (ts <= 0L) NOT_CAPTURED
        else TIME_FMT.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
}
