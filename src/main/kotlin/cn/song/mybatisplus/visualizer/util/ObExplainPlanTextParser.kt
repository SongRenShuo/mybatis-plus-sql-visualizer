package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter

/**
 * OceanBase TEXT 计划树 → 结构化行列解析器（T-ObPanelAdvice，2026-11-01 立项）。
 *
 * 背景：OB（TEXT 档）的 EXPLAIN 结果是**单列 `Query Plan`、值为 OB 自有 ASCII 计划树**的
 * 行列（形态依据与真机留证见 [cn.song.mybatisplus.visualizer.sql.ExplainSupport] OceanBase
 * 条目），此前整族 TEXT 档被 `ExplainPanelChannel.STRUCTURED_FORMATS` 白名单挡在面板外，
 * 索引建议（[ExplainIndexAdvisor.adviseObPlanText]，MCP 侧可用）到不了 IDE 面板。
 *
 * 本解析器把该 ASCII 树还原成 [McpExplainFormatter.RawResult]（headers/rows），供
 * `ui/ExplainPlanResultView` 的 TABLE 渲染分支直接消费——视图零改动；解析失败返回 null，
 * 调用方（面板 onPlan 链）据此 fail-closed 回退原生控制台。
 *
 * 表头门与行列切分与 [ExplainIndexAdvisor] 共用本 object 的原语（单一逻辑源，不复制第二套）：
 * 建议器消费原始行列（表头门 `OB_PLAN_HEADER` 命中后按列名取值），面板消费解析后行列，
 * 两者对同一份 OB 输出的读法一致。
 *
 * **真机形态修正（2026-11-01 真机报障）**：OB CE 4.3.5.6 的 `EXPLAIN` 在 IDE 控制台返回的是
 * **单列 × 多行**——计划文本的每一行是一个独立的行（`rows = [["|ID|OPERATOR …|"], [""],
 * ["|0 |TABLE FULL SCAN|t_order|3 |3 |"], …]`），**不是一个 cell 里装整段多行文本**。
 * 首版按 cell 逐个 `lines()` 扫描，于是表头单独命中、其后无行 → 判解析失败（真机面板 toast
 * 「计划文本解析不出结构化行列」）。现改为先把全部 cell **按行序展平**成一份行列表
 * （[planLines]），再走表头门；「单 cell 装整段多行」的形态展平后同样成行列表，两种形态
 * 归一处理（不再有第二套分支）。
 *
 * 纯逻辑层（无 IDE 类型），headless 直测；判定内核 [parseLines] 接受注入的行列表。
 */
object ObExplainPlanTextParser {

    /** 解析内核产物（表头 + 数据行，均已 trim）。 */
    data class ParsedPlan(val headers: List<String>, val rows: List<List<String>>)

    /**
     * 解析 OB ASCII 计划树为结构化行列。
     *
     * 先把 [planLines] 展平成行列表（兼容「单列×多行」与「单 cell 多行」两种真实形态），
     * 再交 [parseLines] 判定；解析不出返回 null（调用方 fail-closed 回退原生控制台）。
     */
    fun parse(raw: McpExplainFormatter.RawResult): McpExplainFormatter.RawResult? {
        val parsed = parseLines(planLines(raw.rows)) ?: return null
        return McpExplainFormatter.RawResult(parsed.headers, parsed.rows, raw.truncated, raw.notices)
    }

    /**
     * 行列 → 行列表（按行序展平）。每个 cell 再按自身换行拆开：真机「一行一个 cell」得到逐行，
     * 合成/其它通道「一个 cell 装整段多行」得到同一份行序列——两种形态归一，**不拼接行内结构**
     * （`|…|` 行保持原样，绝不 join 成一长串）。
     */
    internal fun planLines(rows: List<List<String>>): List<String> =
        rows.flatMap { row -> row.flatMap { it.lines() } }

    /**
     * 判定内核（纯函数，注入行列表即可 headless 直测）。
     *
     * 命中表头后取其后**列数与表头一致**的 `|…|` 行为数据行（空行、`---` 分隔线与
     * `Outputs & filters` 说明段天然不命中，跳过）。**表头命中但零数据行 → null**
     * （计划形态解析不出，不猜）；全无表头 → null（H2/SQLite/MySQL/PG 样本走到这里）。
     * 只按表头声明的列位取值，不猜列位。
     */
    internal fun parseLines(lines: List<String>): ParsedPlan? {
        val headerIdx = lines.indexOfFirst { OB_PLAN_HEADER.containsMatchIn(it) }
        if (headerIdx < 0) return null
        val header = obRowCells(lines[headerIdx]) ?: return null
        val dataRows = mutableListOf<List<String>>()
        for (line in lines.drop(headerIdx + 1)) {
            val cells = obRowCells(line) ?: continue
            if (cells.size != header.size) continue // 列数不符 = 非计划行（分隔线/说明段），不猜
            dataRows += cells
        }
        if (dataRows.isEmpty()) return null // 只有表头没有数据行 = 计划形态解析不出，不猜
        return ParsedPlan(header.map { it.trim() }, dataRows.map { r -> r.map { it.trim() } })
    }

    /**
     * OB ASCII 计划树表头门（真机实证 `|ID|OPERATOR  |NAME|EST.ROWS|EST.TIME(us)|`）——
     * 自 `ExplainIndexAdvisor`（R45 ③）单源化移入，advisor 同步改引用。
     */
    internal val OB_PLAN_HEADER = Regex("""\|\s*ID\s*\|\s*OPERATOR\s*\|""")

    /** OB ASCII 行 → 单元格清单（按 `|` 切，保留原始列数）；非 `|…|` 行（`===` 分隔线、说明段）返回 null。 */
    internal fun obRowCells(line: String): List<String>? {
        val trimmed = line.trim()
        if (trimmed.length < 3 || !trimmed.startsWith("|") || !trimmed.endsWith("|")) return null
        return trimmed.substring(1, trimmed.length - 1).split('|')
    }
}
