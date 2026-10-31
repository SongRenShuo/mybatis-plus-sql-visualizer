package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.sql.ExplainSupport

/**
 * EXPLAIN 计划「索引缺失」建议识别器（T-ExplainIndex T-EI-02，全景审计 N-23）。
 *
 * 把高频可判定模式提炼为结构化建议（命中规则/涉及对象/建议文案），只提示不执行不改 SQL；
 * 无命中返回空清单（不噪音）；方言不支持静默降级（调用方拿到空清单即与现状一致）。
 *
 * 落点定案（T-EI-01，实施核实）：IDE 内嵌原生控制台的结果行**无程序化读取面**（插件对结果区
 * 仅做页签选择/组件换入，网格数据由 DBN 托管，硬读需侵入平台内部网格模型）；MCP explain 链
 * 自持行列（[cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult]）——建议节挂在
 * 该输出（T-NoticeChannel notices 同款承载位）。IDE 面建议条挂账（硬做=备选 B 自渲染，成本
 * 收益不成立）。
 *
 * 两方言先行（计划口径）：MySQL 表格式（type=ALL 全表扫 / key=NULL 且 rows 超阈 /
 * Extra 含 Using filesort/temporary）、PG/KingBase JSON（大表 Seq Scan）。
 * 单源约定：规则集在本 object，任何未来 plan-text 消费方（IDE 面备选 B、perf 工具扩展）
 * 都从这里取，不复制第二套（与 perf_advice 的静态 SQL 规则集是两个互补面，不互斥）。
 *
 * T-ExplainChainHardening T-ECH-02/T-ECH-03（O-48）修正：分派依据由**方言名**改为
 * [cn.song.mybatisplus.visualizer.sql.ExplainSupport] 表里的**输出格式**——
 * 此前 PG 分支按 `Seq Scan on (\w+)` 文本树正则解析，而 PG 方言渲染的是
 * `EXPLAIN (FORMAT JSON, VERBOSE)`（返回单列 JSON 文本），两种形态根本不匹配，
 * **PG 索引建议实际永不触发**（真缺陷，真 PG 16.14 实测：返回列名 `QUERY PLAN`，值形如
 * `[{"Plan":{"Node Type":"Seq Scan",…,"Relation Name":"bench_items",…,"Plan Rows":10000,…}}]`）。
 * 现按格式分派：JSON → [adviseJsonPlan]，TABLE → [adviseMysql]，TEXT → [advisePlanText]；
 * R23 增 XML（SQL Server）→ 有意不解析（计划 XML 是附加结果集，本通道不采集，勿硬解析）。
 */
object ExplainIndexAdvisor {

    /** 全表扫描行数阈值（低于此不提示——小表全扫常是更优计划，阈值一刀切会噪音）。 */
    const val ROWS_THRESHOLD = 10_000

    /**
     * 单条建议（纯数据）。
     *
     * @param rule   规则码（`FULL_SCAN` / `NO_INDEX_USED` / `FILESORT` / `TEMPORARY_TABLE` /
     *               `HIGH_ROWS_ESTIMATE` / `TABLE_FULL_SCAN` / `SEQ_SCAN`）——同时是 IDE 面本地化
     *               文案的 bundle 键后缀（`explain.hint.<rule>`）。
     * @param target 命中对象（表名 / 索引名）。
     * @param advice 英文短句——**MCP 输出口径**（agent 文案恒英文，不进 bundle），MCP/结构化通道
     *               继续用它；IDE 面板则按 [rule] 取 bundle 本地化文案（缺失时回落本字段）。
     * @param metric 可选量化信息（如 `~12000 rows`、`12000`）；MCP 侧不用，IDE 面本地化文案
     *               以 `{1}` 参数消费（2026-10-31 R45 面板本地化时新增，默认 null 零破坏）。
     */
    data class Hint(
        val rule: String,
        val target: String,
        val advice: String,
        val metric: String? = null,
    )

    /**
     * 从 EXPLAIN 结果识别索引缺失类模式。
     *
     * @param dialect   方言显示名（与 [cn.song.mybatisplus.visualizer.sql.Dialect.name] 同源；
     *                  分派读 [cn.song.mybatisplus.visualizer.sql.ExplainSupport.formatFor]，
     *                  大小写不敏感；未知/不支持方言 → 空清单）
     * @param headers   结果列名（表格式形态；文本/JSON 形态通常单列）
     * @param rows      结果行（每行与 headers 对齐；truncated 之前的部分）
     */
    fun advise(dialect: String, headers: List<String>, rows: List<List<String>>): List<Hint> {
        val out = mutableListOf<Hint>()
        when (ExplainSupport.formatFor(dialect)) {
            // TABLE 档含 DM8（T-Fr45DmGuard：`EXPLAIN FOR` 返回 19 列计划表）——DM 无 MySQL 的
            // type/key/Extra 列，adviseMysql 列名不符时提前返回，天然零命中（不噪音）。
            ExplainOutputFormat.TABLE -> adviseMysql(headers, rows, out)
            // PG/KingBase：单列 JSON 文本（FORMAT JSON）
            ExplainOutputFormat.JSON -> adviseJsonPlan(rows, out)
            // 文本计划树（H2 PLAN FOR / SQLite QUERY PLAN / OceanBase ASCII 树）——规则覆盖 PG 文本树
            // 形态与 OB ASCII 计划树（R45 ③，以 OB 表头为门）；其余方言的文本形态不含这两种模式，
            // 天然零命中（不噪音）；将来新增文本规则在此扩展。
            // R23 Oracle 同为 TEXT 档：本插件通道不产出行（EXPLAIN PLAN FOR 只登记），空行天然零命中
            ExplainOutputFormat.TEXT -> advisePlanText(rows, out)
            // R23 SQL Server XML 档：Showplan XML 是**附加结果集**（本通道不采集），且 XML 计划
            // 无行内固定可判定模式——**不硬解析**（勿硬解析红线），真实形态驱动的新规则在此扩展
            ExplainOutputFormat.XML -> Unit
            // 不支持方言/未知方言静默降级——空清单与无命中同形
            else -> Unit
        }
        return out
    }

    private fun adviseMysql(headers: List<String>, rows: List<List<String>>, out: MutableList<Hint>) {
        val idx = headers.map { it.lowercase().trim() }
        val typeCol = idx.indexOf("type")
        val keyCol = idx.indexOf("key")
        val rowsCol = idx.indexOf("rows")
        val extraCol = idx.indexOf("extra")
        val tableCol = idx.indexOf("table")
        if (typeCol < 0 && keyCol < 0 && extraCol < 0) return // 列名形态不符（如 FORMAT=TREE 变体）→ 不猜
        for (row in rows) {
            fun cell(col: Int): String = row.getOrNull(col)?.trim().orEmpty()
            val table = if (tableCol >= 0) cell(tableCol) else "?"
            val type = if (typeCol >= 0) cell(typeCol) else ""
            val key = if (keyCol >= 0) cell(keyCol) else ""
            val rowsN = if (rowsCol >= 0) cell(rowsCol).toLongOrNull() else null
            val extra = if (extraCol >= 0) cell(extraCol) else ""
            // type=ALL 全表扫（rows 超阈才提示——小表全扫噪音）
            if (type.equals("ALL", ignoreCase = true) && (rowsN == null || rowsN >= ROWS_THRESHOLD)) {
                out += Hint(
                    rule = "FULL_SCAN",
                    target = table,
                    advice = "type=ALL full table scan" +
                        (rowsN?.let { " over ~$it rows" } ?: "") +
                        " — consider adding an index on the WHERE/JOIN columns",
                    metric = rowsN?.let { "~$it rows" },
                )
            }
            // key=NULL：没有可用索引被选中（与 ALL 互补——rows 不足阈值时不重复提示的兜底弱信号）
            if (key.equals("NULL", ignoreCase = true) && !type.equals("ALL", ignoreCase = true)) {
                out += Hint(
                    rule = "NO_INDEX_USED",
                    target = table,
                    advice = "no index was used (key=NULL) — verify the query filters on an indexed column",
                )
            }
            if (extra.contains("Using filesort", ignoreCase = true)) {
                out += Hint(
                    rule = "FILESORT",
                    target = table,
                    advice = "Using filesort — ORDER BY cannot use an index; consider a covering index",
                )
            }
            if (extra.contains("Using temporary", ignoreCase = true)) {
                out += Hint(
                    rule = "TEMPORARY_TABLE",
                    target = table,
                    advice = "Using temporary — GROUP BY/DISTINCT spills to a temp table; consider an index",
                )
            }
            // R45（EXPLAIN 体检）：rows 估算独立规则——type 非 ALL（与 FULL_SCAN 互补不重复）但
            // 估算行数仍超阈 → 索引选择性差 / 大范围扫描（命中索引却不窄）。此前 rows 只作
            // FULL_SCAN 的阈值闸门，非 ALL 形态无从触发，体检清单缺「rows 估算」这一维。
            if (rowsN != null && rowsN >= ROWS_THRESHOLD && !type.equals("ALL", ignoreCase = true)) {
                out += Hint(
                    rule = "HIGH_ROWS_ESTIMATE",
                    target = table,
                    advice = "estimated rows=$rowsN even with an index — poor selectivity / wide range scan; " +
                        "verify the filter columns are covered",
                    metric = rowsN.toString(),
                )
            }
        }
    }

    /**
     * R45：体检规则清单（呈现层数据源——面板/MCP 的「支持哪些体检规则」一栏都从这里取，
     * 不复制第二套）。每条含规则码与一句话职责说明；顺序稳定（声明序）。
     */
    data class RuleInfo(val rule: String, val description: String)

    private val RULE_INVENTORY: List<RuleInfo> = listOf(
        RuleInfo("FULL_SCAN", "type=ALL full table scan over the threshold"),
        RuleInfo("NO_INDEX_USED", "no usable index was selected (key=NULL)"),
        RuleInfo("HIGH_ROWS_ESTIMATE", "estimated rows still high even with an index (poor selectivity)"),
        RuleInfo("FILESORT", "ORDER BY cannot use an index (Using filesort)"),
        RuleInfo("TEMPORARY_TABLE", "GROUP BY/DISTINCT spills to a temp table (Using temporary)"),
        RuleInfo("SEQ_SCAN", "sequential scan on a large table (PostgreSQL/KingBase)"),
        RuleInfo("TABLE_FULL_SCAN", "full table scan in the OceanBase ASCII plan tree (TABLE SCAN / TABLE FULL SCAN)"),
    )

    /** 体检规则清单（R45；空实现无——清单恒非空，供面板「体检规则」说明栏与文档生成）。 */
    fun ruleInventory(): List<RuleInfo> = RULE_INVENTORY

    private fun advisePlanText(rows: List<List<String>>, out: MutableList<Hint>) {
        for (row in rows) {
            for (cell in row) {
                // OceanBase：单列 `Query Plan`，值是 OB 自有 ASCII 计划树（表头 `|ID|OPERATOR|…`）。
                // 以表头为门——其它方言的文本计划（H2/SQLite/Oracle）不含该表头，天然零命中。
                if (OB_PLAN_HEADER.containsMatchIn(cell)) {
                    adviseObPlanText(cell, out)
                    continue
                }
                // PG 文本树：`Seq Scan on big_table  (cost=… rows=…)`
                val m = SEQ_SCAN_ON.find(cell) ?: continue
                val rowsN = PLAN_TEXT_ROWS.find(cell)?.groupValues?.get(1)?.toLongOrNull()
                if (rowsN == null || rowsN >= ROWS_THRESHOLD) {
                    out += Hint(
                        rule = "SEQ_SCAN",
                        target = m.groupValues[1],
                        advice = "Sequential scan" +
                            (rowsN?.let { " over ~$it rows" } ?: "") +
                            " — consider adding an index on the filter columns",
                    )
                }
            }
        }
    }

    // ---------- OceanBase ASCII 计划树（R45 ③） ----------

    /**
     * OceanBase 文本计划解析（T-OBExplainFormat 留账的「OB 专属文本规则」补全）。
     *
     * 形态依据（真机实证，提交 58b8b5f3 正文逐字留证；同口径见 [ExplainSupport] OceanBase 条目）：
     * 社区镜像 `oceanbase/oceanbase-ce:4.3.5-lts`（OB CE 4.3.5.6，`ob_compatibility_mode=MYSQL`）
     * 上 `EXPLAIN <sql>` 返回**单列 `Query Plan`**，值是 OB 自有 ASCII 表格：
     * ```
     * ==========================================
     * |ID|OPERATOR  |NAME|EST.ROWS|EST.TIME(us)|
     * |0 |EXPRESSION|    |1       |1           |
     * ==========================================
     * Outputs & filters:
     *   0 - output([1]), filter(nil) / values({1})
     * ```
     * 真实表算子是 `TABLE RANGE SCAN` / `TABLE FULL SCAN`，带 `partitions(p0)`、`is_index_back`、
     * `range_key/range_cond` 等 OB 专有字段（同源留证）。注意：真机留证覆盖到**表头 + EXPRESSION 行**
     * 与**算子名**，表扫行本身未逐字落盘——本解析只按表头声明的列名取值（OPERATOR/NAME/EST.ROWS），
     * 不按固定列位猜，故列宽/补空格差异不影响。
     *
     * 规则口径：
     *  - `TABLE SCAN` / `TABLE FULL SCAN`（全表扫）→ [Hint.rule] `TABLE_FULL_SCAN`，EST.ROWS 超阈才提示；
     *  - `TABLE RANGE SCAN`（走索引的范围扫）→ 不报「缺索引」，只在 EST.ROWS 超阈时报
     *    `HIGH_ROWS_ESTIMATE`（选择性差 / 大范围扫，与 MySQL 档同规则码同语义）；
     *  - `TABLE GET` / `EXPRESSION` 等其它算子不产出建议（点查与常量计划无索引话题）。
     */
    private fun adviseObPlanText(text: String, out: MutableList<Hint>) {
        val lines = text.lines()
        val headerIdx = lines.indexOfFirst { OB_PLAN_HEADER.containsMatchIn(it) }
        if (headerIdx < 0) return
        val header = obRowCells(lines[headerIdx]) ?: return
        val opCol = header.indexOfFirst { it.trim().equals("OPERATOR", ignoreCase = true) }
        val nameCol = header.indexOfFirst { it.trim().equals("NAME", ignoreCase = true) }
        val rowsCol = header.indexOfFirst { it.trim().equals("EST.ROWS", ignoreCase = true) }
        if (opCol < 0) return
        for (line in lines.drop(headerIdx + 1)) {
            val cells = obRowCells(line) ?: continue
            if (cells.size != header.size) continue // 列数不符 = 非计划行（分隔线/说明段），不猜
            val op = cells[opCol].trim()
            if (op.isEmpty()) continue
            val target = if (nameCol >= 0) cells[nameCol].trim().ifEmpty { "?" } else "?"
            val estRows = if (rowsCol >= 0) cells[rowsCol].trim().toLongOrNull() else null
            when {
                OB_FULL_SCAN_OP.matches(op) -> {
                    if (estRows == null || estRows >= ROWS_THRESHOLD) {
                        out += Hint(
                            rule = "TABLE_FULL_SCAN",
                            target = target,
                            advice = "full table scan ($op)" +
                                (estRows?.let { " over ~$it rows" } ?: "") +
                                " — consider adding an index on the WHERE/JOIN columns",
                            metric = estRows?.let { "~$it rows" },
                        )
                    }
                }
                OB_RANGE_SCAN_OP.matches(op) && estRows != null && estRows >= ROWS_THRESHOLD -> {
                    out += Hint(
                        rule = "HIGH_ROWS_ESTIMATE",
                        target = target,
                        advice = "estimated rows=$estRows even with a range scan — poor selectivity / " +
                            "wide range scan; verify the filter columns are covered",
                        metric = estRows.toString(),
                    )
                }
            }
        }
    }

    /** OB ASCII 行 → 单元格清单（按 `|` 切，保留原始列数）；非 `|…|` 行（`===` 分隔线、说明段）返回 null。 */
    private fun obRowCells(line: String): List<String>? {
        val trimmed = line.trim()
        if (trimmed.length < 3 || !trimmed.startsWith("|") || !trimmed.endsWith("|")) return null
        return trimmed.substring(1, trimmed.length - 1).split('|')
    }

    // ---------- PG/KingBase FORMAT JSON（T-ECH-03） ----------

    /** JSON 键名（PG 计划 JSON 的固定键；实测形态见类 KDoc）。 */
    private const val KEY_NODE_TYPE = "Node Type"
    private const val KEY_RELATION = "Relation Name"
    private const val KEY_ALIAS = "Alias"
    private const val KEY_PLAN_ROWS = "Plan Rows"
    private const val KEY_ACTUAL_ROWS = "Actual Rows"

    /**
     * JSON 计划解析（T-ECH-03，真缺陷修复点）：PG/KingBase 的 `FORMAT JSON` 返回**单列 JSON 文本**，
     * 顶层是数组 `[{ "Plan": {…} }]`，节点键为 `"Node Type"` / `"Relation Name"` / `"Plan Rows"`，
     * 子节点在 `"Plans"` 数组里。
     *
     * 容错三段（计划 §六「先试 JSON，失败回落」）：
     *  1. 整段 JSON 解析成功 → 按对象树遍历（正确处理嵌套与转义）；
     *  2. 解析失败（**实测最常见的成因**是 [cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.MAX_CELL_CHARS]
     *     的 2000 字符单元格预算把长计划截断）→ 按「节点分片」正则扫描：截断只影响尾部，
     *     前面的节点对象仍完整，故仍能取到顶层节点；
     *  3. 前两段**零命中**时回落文本树规则 [advisePlanText]（PG 文本树形态仍可识别；
     *     真 JSON 计划零命中时该回落天然无命中，不产生噪音）。
     * JSON 两段共用 [emitSeqScanHint]——阈值与文案只有一份。
     */
    private fun adviseJsonPlan(rows: List<List<String>>, out: MutableList<Hint>) {
        for (row in rows) {
            for (cell in row) {
                val text = cell.trim()
                if (text.isEmpty()) continue
                val before = out.size
                val parsed = runCatching { com.google.gson.JsonParser.parseString(text) }.getOrNull()
                if (parsed != null && (parsed.isJsonArray || parsed.isJsonObject)) {
                    walkJsonPlan(parsed, out)
                } else {
                    scanJsonNodes(text, out)
                }
                if (out.size == before) advisePlanText(listOf(listOf(cell)), out)
            }
        }
    }

    /** 对象树遍历：每个带 `"Node Type"` 的对象判一次，再递归其所有值（含 `"Plans"` 子计划）。 */
    private fun walkJsonPlan(el: com.google.gson.JsonElement?, out: MutableList<Hint>) {
        when {
            el == null || el.isJsonNull -> Unit
            el.isJsonArray -> el.asJsonArray.forEach { walkJsonPlan(it, out) }
            el.isJsonObject -> {
                val obj = el.asJsonObject
                val relation = stringOf(obj, KEY_RELATION) ?: stringOf(obj, KEY_ALIAS)
                val planRows = longOf(obj, KEY_PLAN_ROWS) ?: longOf(obj, KEY_ACTUAL_ROWS)
                emitSeqScanHint(stringOf(obj, KEY_NODE_TYPE), relation, planRows, out)
                obj.entrySet().forEach { walkJsonPlan(it.value, out) }
            }
            else -> Unit
        }
    }

    private fun stringOf(obj: com.google.gson.JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    /** 数值容忍：PG 出数字字面量，某些驱动/版本可能把数字包成字符串。 */
    private fun longOf(obj: com.google.gson.JsonObject, key: String): Long? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asLong }.getOrNull() ?: runCatching { it.asString.trim().toLongOrNull() }.getOrNull()
        }

    /** 节点分片扫描（JSON 被截断时的回落路径）：每段 = 一个 `"Node Type"` 到下一个之前。 */
    private fun scanJsonNodes(text: String, out: MutableList<Hint>) {
        val starts = JSON_NODE_TYPE.findAll(text).toList()
        for ((i, m) in starts.withIndex()) {
            val segmentEnd = starts.getOrNull(i + 1)?.range?.first ?: text.length
            val segment = text.substring(m.range.last + 1, segmentEnd)
            emitSeqScanHint(
                m.groupValues[1],
                JSON_RELATION.find(segment)?.groupValues?.get(1),
                JSON_PLAN_ROWS.find(segment)?.groupValues?.get(1)?.toLongOrNull(),
                out,
            )
        }
    }

    /**
     * 单节点判据（JSON 两段路径共用）：`Seq Scan` 且（行数未知或 ≥ 阈值）→ 一条建议。
     * 关系名缺失（PG 对无表节点不输出 `"Relation Name"`）时目标记 `?`，不虚报表名。
     */
    private fun emitSeqScanHint(nodeType: String?, relation: String?, planRows: Long?, out: MutableList<Hint>) {
        if (!nodeType.equals("Seq Scan", ignoreCase = true)) return
        if (planRows != null && planRows < ROWS_THRESHOLD) return
        out += Hint(
            rule = "SEQ_SCAN",
            target = relation?.takeIf { it.isNotBlank() } ?: "?",
            advice = "Sequential scan" +
                (planRows?.let { " over ~$it rows" } ?: "") +
                " — consider adding an index on the filter columns",
        )
    }

    /** 建议节渲染（MCP explain 输出承载位；空清单返回空串——输出零差异硬门禁）。 */
    fun formatHints(hints: List<Hint>): String {
        if (hints.isEmpty()) return ""
        return buildString {
            append("\n\nindex hints:")
            hints.forEach { append("\n- [").append(it.rule).append("] ").append(it.target).append(": ").append(it.advice) }
        }
    }

    /** 节点分片扫描用：JSON 键形态（`"Node Type":"Seq Scan"`）。 */
    private val JSON_NODE_TYPE = Regex(""""Node Type"\s*:\s*"([^"]*)"""")
    /** 关系名/别名（PG 对无表节点不出 `Relation Name`，退 `Alias`）。 */
    private val JSON_RELATION = Regex(""""(?:Relation Name|Alias)"\s*:\s*"([^"]*)"""")

    /** 估算行数（`Actual Rows` 仅 EXPLAIN ANALYZE 出现——本插件不产 analyze，容忍读取不改变口径）。 */
    private val JSON_PLAN_ROWS = Regex(""""(?:Plan Rows|Actual Rows)"\s*:\s*(\d+)""")

    /** F59（T-PerfSweep18）：文本树形态——`Seq Scan on <table>`（原 advisePlanText 函数体内现场 Regex，提 object 单例；internal＝同一 Pattern 实例断言观察缝）。 */
    internal val SEQ_SCAN_ON = Regex("""Seq Scan on (\w+)""")

    /** F59（T-PerfSweep18）：文本树形态——`rows=N` 估算行数（同上提单例）。 */
    internal val PLAN_TEXT_ROWS = Regex("""rows=(\d+)""")

    /** R45 ③：OceanBase ASCII 计划树表头（真机实证 `|ID|OPERATOR  |NAME|EST.ROWS|EST.TIME(us)|`）——OB 规则的门。 */
    private val OB_PLAN_HEADER = Regex("""\|\s*ID\s*\|\s*OPERATOR\s*\|""")

    /** R45 ③：OB 全表扫算子（4.x 传统计划 `TABLE SCAN` / `TABLE FULL SCAN`）。 */
    private val OB_FULL_SCAN_OP = Regex("""(?i)^TABLE\s+(?:FULL\s+)?SCAN$""")

    /** R45 ③：OB 索引范围扫算子（`TABLE RANGE SCAN`）——已走索引，不报缺索引。 */
    private val OB_RANGE_SCAN_OP = Regex("""(?i)^TABLE\s+RANGE\s+SCAN$""")
}
