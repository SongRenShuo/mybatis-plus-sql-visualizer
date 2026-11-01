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
 * 建议器消费原始单列文本（表头门 `OB_PLAN_HEADER` 命中后按列名取值），面板消费解析后行列，
 * 两者对同一份 OB 输出的读法一致。
 *
 * 纯逻辑层（无 IDE 类型），headless 直测。
 */
object ObExplainPlanTextParser {

    /**
     * 解析 OB ASCII 计划树为结构化行列。
     *
     * 逐 cell 扫描（OB 实际形态是 1 行 1 列，防御性容忍多 cell），命中表头后取其后
     * 列数与表头一致的 `|…|` 行为数据行（`===` 分隔线与 `Outputs & filters` 说明段
     * 天然不命中，跳过）。**表头命中但零数据行 → null**（解析失败，调用方回退原生）；
     * 全 cell 不命中 → null（非 OB 形态，H2/SQLite/MySQL/PG 样本走到这里）。
     */
    fun parse(raw: McpExplainFormatter.RawResult): McpExplainFormatter.RawResult? {
        for (row in raw.rows) {
            for (cell in row) {
                val lines = cell.lines()
                val headerIdx = lines.indexOfFirst { OB_PLAN_HEADER.containsMatchIn(it) }
                if (headerIdx < 0) continue
                val header = obRowCells(lines[headerIdx]) ?: continue
                val dataRows = mutableListOf<List<String>>()
                for (line in lines.drop(headerIdx + 1)) {
                    val cells = obRowCells(line) ?: continue
                    if (cells.size != header.size) continue // 列数不符 = 非计划行（分隔线/说明段），不猜
                    dataRows += cells
                }
                if (dataRows.isEmpty()) return null // 只有表头没有数据行 = 计划形态解析不出，不猜
                return McpExplainFormatter.RawResult(
                    header.map { it.trim() },
                    dataRows.map { r -> r.map { it.trim() } },
                    raw.truncated,
                    raw.notices,
                )
            }
        }
        return null
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
