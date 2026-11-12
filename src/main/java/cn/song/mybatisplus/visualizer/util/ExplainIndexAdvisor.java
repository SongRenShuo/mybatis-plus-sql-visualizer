// T-SyncK2JMaster309 自 master util/ExplainIndexAdvisor.kt 移植
// T-SyncK2JMaster184 吸收 O-48（T-ECH-02/T-ECH-03）：分派依据改 ExplainSupport 输出格式表 + PG/KingBase JSON 计划解析
package cn.song.mybatisplus.visualizer.util;

import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat;
import cn.song.mybatisplus.visualizer.sql.ExplainSupport;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EXPLAIN 计划「索引缺失」建议识别器（T-ExplainIndex T-EI-02，全景审计 N-23）。
 *
 * <p>把高频可判定模式提炼为结构化建议（命中规则/涉及对象/建议文案），只提示不执行不改 SQL；
 * 无命中返回空清单（不噪音）；方言不支持静默降级（调用方拿到空清单即与现状一致）。
 *
 * <p>落点定案（T-EI-01，实施核实）：IDE 内嵌原生控制台的结果行<b>无程序化读取面</b>（插件对结果区
 * 仅做页签选择/组件换入，网格数据由 DBN 托管，硬读需侵入平台内部网格模型）；MCP explain 链
 * 自持行列（{@code cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult}）——建议节挂在
 * 该输出（T-NoticeChannel notices 同款承载位）。IDE 面建议条挂账（硬做=备选 B 自渲染，成本
 * 收益不成立）。
 *
 * <p>两方言先行（计划口径）：MySQL 表格式（type=ALL 全表扫 / key=NULL 且 rows 超阈 /
 * Extra 含 Using filesort/temporary）、PG/KingBase JSON（大表 Seq Scan）。
 * 单源约定：规则集在本类，任何未来 plan-text 消费方（IDE 面备选 B、perf 工具扩展）
 * 都从这里取，不复制第二套（与 perf_advice 的静态 SQL 规则集是两个互补面，不互斥）。</p>
 *
 * <p>T-ExplainChainHardening T-ECH-02/T-ECH-03（O-48）修正：分派依据由<b>方言名</b>改为
 * {@link ExplainSupport} 表里的<b>输出格式</b>——此前 PG 分支按 {@code Seq Scan on (\w+)} 文本树正则解析，
 * 而 PG 方言渲染的是 {@code EXPLAIN (FORMAT JSON, VERBOSE)}（返回单列 JSON 文本），两种形态根本不匹配，
 * <b>PG 索引建议实际永不触发</b>（真缺陷，真 PG 16.14 实测：返回列名 {@code QUERY PLAN}，值形如
 * {@code [{"Plan":{"Node Type":"Seq Scan",…,"Relation Name":"bench_items",…,"Plan Rows":10000,…}}]}）。
 * 现按格式分派：JSON → {@link #adviseJsonPlan}，TABLE → {@link #adviseMysql}，TEXT → {@link #advisePlanText}。</p>
 */
public final class ExplainIndexAdvisor {

    private ExplainIndexAdvisor() {}

    /** 全表扫描行数阈值（低于此不提示——小表全扫常是更优计划，阈值一刀切会噪音）。 */
    public static final int ROWS_THRESHOLD = 10_000;

    /** 单条建议（纯数据；advice 为英文短句——MCP 输出口径，不进 bundle）。 */
    public record Hint(
            /** 命中规则名（FULL_SCAN/NO_INDEX_USED/FILESORT/TEMPORARY_TABLE/SEQ_SCAN）。 */
            @NotNull String rule,
            /** 涉及对象（表名）。 */
            @NotNull String target,
            /** 建议文案（英文短句）。 */
            @NotNull String advice
    ) {
    }

    /** PG 文本树：`Seq Scan on big_table  (cost=… rows=…)`。 */
    private static final Pattern SEQ_SCAN = Pattern.compile("Seq Scan on (\\w+)");

    private static final Pattern ROWS_N = Pattern.compile("rows=(\\d+)");

    /** R45 ③：OceanBase ASCII 计划树表头（真机实证 `|ID|OPERATOR  |NAME|EST.ROWS|EST.TIME(us)|`）——OB 规则的门。 */
    private static final Pattern OB_PLAN_HEADER = Pattern.compile("\\|\\s*ID\\s*\\|\\s*OPERATOR\\s*\\|");

    /** R45 ③：OB 全表扫算子（4.x 传统计划 `TABLE SCAN` / `TABLE FULL SCAN`）。 */
    private static final Pattern OB_FULL_SCAN_OP = Pattern.compile("(?i)^TABLE\\s+(?:FULL\\s+)?SCAN$");

    /** R45 ③：OB 索引范围扫算子（`TABLE RANGE SCAN`）——已走索引，不报缺索引。 */
    private static final Pattern OB_RANGE_SCAN_OP = Pattern.compile("(?i)^TABLE\\s+RANGE\\s+SCAN$");

    /**
     * 从 EXPLAIN 结果识别索引缺失类模式。
     *
     * @param dialect 方言显示名（与 {@link cn.song.mybatisplus.visualizer.sql.Dialect#getName()} 同源；
     *                分派读 {@link ExplainSupport#formatFor}，大小写不敏感；未知/不支持方言 → 空清单）
     * @param headers 结果列名（表格式形态；文本/JSON 形态通常单列）
     * @param rows    结果行（每行与 headers 对齐；truncated 之前的部分）
     */
    @NotNull
    public static List<Hint> advise(@NotNull String dialect, @NotNull List<String> headers,
                                    @NotNull List<List<String>> rows) {
        List<Hint> out = new ArrayList<>();
        ExplainOutputFormat format = ExplainSupport.formatFor(dialect);
        if (format == ExplainOutputFormat.TABLE) {
            // MySQL 家族：表格式（id/select_type/table/type/key/rows/Extra…）
            adviseMysql(headers, rows, out);
        } else if (format == ExplainOutputFormat.JSON) {
            // PG/KingBase：单列 JSON 文本（FORMAT JSON）
            adviseJsonPlan(rows, out);
        } else if (format == ExplainOutputFormat.TEXT) {
            // 文本计划树（DM8 #NSET2 / H2 PLAN FOR / SQLite QUERY PLAN / OceanBase ASCII 树）——规则覆盖
            // PG 文本树形态与 OB ASCII 计划树（R45 ③，以 OB 表头为门）；其余方言的文本形态不含这两种
            // 模式，天然零命中（不噪音）；将来新增文本规则在此扩展。
            // R23 Oracle 同为 TEXT 档：本插件通道不产出行（EXPLAIN PLAN FOR 只登记），空行天然零命中
            advisePlanText(rows, out);
        } else if (format == ExplainOutputFormat.XML) {
            // R23 SQL Server XML 档：Showplan XML 是附加结果集（本通道不采集），且 XML 计划
            // 无行内固定可判定模式——不硬解析（勿硬解析红线），真实形态驱动的新规则在此扩展
        } else {
            // 不支持方言/未知方言静默降级——空清单与无命中同形
        }
        return out;
    }

    private static void adviseMysql(@NotNull List<String> headers, @NotNull List<List<String>> rows,
                                    @NotNull List<Hint> out) {
        List<String> idx = new ArrayList<>(headers.size());
        for (String h : headers) {
            idx.add(h.toLowerCase(Locale.ROOT).trim());
        }
        int typeCol = idx.indexOf("type");
        int keyCol = idx.indexOf("key");
        int rowsCol = idx.indexOf("rows");
        int extraCol = idx.indexOf("extra");
        int tableCol = idx.indexOf("table");
        if (typeCol < 0 && keyCol < 0 && extraCol < 0) return; // 列名形态不符（如 FORMAT=TREE 变体）→ 不猜
        for (List<String> row : rows) {
            String table = tableCol >= 0 ? cell(row, tableCol) : "?";
            String type = typeCol >= 0 ? cell(row, typeCol) : "";
            String key = keyCol >= 0 ? cell(row, keyCol) : "";
            Long rowsN = rowsCol >= 0 ? toLongOrNull(cell(row, rowsCol)) : null;
            String extra = extraCol >= 0 ? cell(row, extraCol) : "";
            // type=ALL 全表扫（rows 超阈才提示——小表全扫噪音）
            if (type.equalsIgnoreCase("ALL") && (rowsN == null || rowsN >= ROWS_THRESHOLD)) {
                out.add(new Hint(
                        "FULL_SCAN",
                        table,
                        "type=ALL full table scan"
                                + (rowsN != null ? " over ~" + rowsN + " rows" : "")
                                + " — consider adding an index on the WHERE/JOIN columns"));
            }
            // key=NULL：没有可用索引被选中（与 ALL 互补——rows 不足阈值时不重复提示的兜底弱信号）
            if (key.equalsIgnoreCase("NULL") && !type.equalsIgnoreCase("ALL")) {
                out.add(new Hint(
                        "NO_INDEX_USED",
                        table,
                        "no index was used (key=NULL) — verify the query filters on an indexed column"));
            }
            if (extra.toLowerCase(Locale.ROOT).contains("using filesort")) {
                out.add(new Hint(
                        "FILESORT",
                        table,
                        "Using filesort — ORDER BY cannot use an index; consider a covering index"));
            }
            if (extra.toLowerCase(Locale.ROOT).contains("using temporary")) {
                out.add(new Hint(
                        "TEMPORARY_TABLE",
                        table,
                        "Using temporary — GROUP BY/DISTINCT spills to a temp table; consider an index"));
            }
            // R45（EXPLAIN 体检）：rows 估算独立规则——type 非 ALL（与 FULL_SCAN 互补不重复）但
            // 估算行数仍超阈 → 索引选择性差 / 大范围扫描（命中索引却不窄）。此前 rows 只作
            // FULL_SCAN 的阈值闸门，非 ALL 形态无从触发，体检清单缺「rows 估算」这一维。
            if (rowsN != null && rowsN >= ROWS_THRESHOLD && !"ALL".equalsIgnoreCase(type)) {
                out.add(new Hint(
                        "HIGH_ROWS_ESTIMATE",
                        table,
                        "estimated rows=" + rowsN + " even with an index — poor selectivity / wide range scan; "
                                + "verify the filter columns are covered"));
            }
        }
    }

    /**
     * R45：体检规则清单（呈现层数据源——面板/MCP 的「支持哪些体检规则」一栏都从这里取，
     * 不复制第二套）。每条含规则码与一句话职责说明；顺序稳定（声明序）。
     */
    public record RuleInfo(String rule, String description) {
    }

    private static final List<RuleInfo> RULE_INVENTORY = List.of(
            new RuleInfo("FULL_SCAN", "type=ALL full table scan over the threshold"),
            new RuleInfo("NO_INDEX_USED", "no usable index was selected (key=NULL)"),
            new RuleInfo("HIGH_ROWS_ESTIMATE", "estimated rows still high even with an index (poor selectivity)"),
            new RuleInfo("FILESORT", "ORDER BY cannot use an index (Using filesort)"),
            new RuleInfo("TEMPORARY_TABLE", "GROUP BY/DISTINCT spills to a temp table (Using temporary)"),
            new RuleInfo("SEQ_SCAN", "sequential scan on a large table (PostgreSQL/KingBase)"),
            new RuleInfo("TABLE_FULL_SCAN", "full table scan in the OceanBase ASCII plan tree (TABLE SCAN / TABLE FULL SCAN)")
    );

    /** 体检规则清单（R45；空实现无——清单恒非空，供面板「体检规则」说明栏与文档生成）。 */
    @NotNull
    public static List<RuleInfo> ruleInventory() {
        return RULE_INVENTORY;
    }

    private static void advisePlanText(@NotNull List<List<String>> rows, @NotNull List<Hint> out) {
        for (List<String> row : rows) {
            for (String cell : row) {
                // OceanBase：单列 `Query Plan`，值是 OB 自有 ASCII 计划树（表头 `|ID|OPERATOR|…`）。
                // 以表头为门——其它方言的文本计划（DM8/H2/SQLite/Oracle）不含该表头，天然零命中。
                if (OB_PLAN_HEADER.matcher(cell).find()) {
                    adviseObPlanText(cell, out);
                    continue;
                }
                // PG 文本树：`Seq Scan on big_table  (cost=… rows=…)`
                Matcher m = SEQ_SCAN.matcher(cell);
                if (!m.find()) continue;
                Long rowsN = null;
                Matcher rm = ROWS_N.matcher(cell);
                if (rm.find()) {
                    try {
                        rowsN = Long.parseLong(rm.group(1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (rowsN == null || rowsN >= ROWS_THRESHOLD) {
                    out.add(new Hint(
                            "SEQ_SCAN",
                            m.group(1),
                            "Sequential scan"
                                    + (rowsN != null ? " over ~" + rowsN + " rows" : "")
                                    + " — consider adding an index on the filter columns"));
                }
            }
        }
    }

    // ---------- OceanBase ASCII 计划树（R45 ③） ----------

    /**
     * OceanBase 文本计划解析（T-OBExplainFormat 留账的「OB 专属文本规则」补全）。
     *
     * <p>形态依据（真机实证，提交 58b8b5f3 正文逐字留证；同口径见
     * {@code cn.song.mybatisplus.visualizer.sql.ExplainSupport} 的 OceanBase 条目）：社区镜像
     * {@code oceanbase/oceanbase-ce:4.3.5-lts}（OB CE 4.3.5.6，{@code ob_compatibility_mode=MYSQL}）上
     * {@code EXPLAIN <sql>} 返回<b>单列 {@code Query Plan}</b>，值是 OB 自有 ASCII 表格：</p>
     * <pre>
     * ==========================================
     * |ID|OPERATOR  |NAME|EST.ROWS|EST.TIME(us)|
     * |0 |EXPRESSION|    |1       |1           |
     * ==========================================
     * Outputs &amp; filters:
     *   0 - output([1]), filter(nil) / values({1})
     * </pre>
     * <p>真实表算子是 {@code TABLE RANGE SCAN} / {@code TABLE FULL SCAN}，带 {@code partitions(p0)}、
     * {@code is_index_back}、{@code range_key/range_cond} 等 OB 专有字段（同源留证）。注意：真机留证覆盖到
     * <b>表头 + EXPRESSION 行</b>与<b>算子名</b>，表扫行本身未逐字落盘——本解析只按表头声明的列名取值
     * （OPERATOR/NAME/EST.ROWS），不按固定列位猜，故列宽/补空格差异不影响。</p>
     *
     * <p>规则口径：</p>
     * <ul>
     *  <li>{@code TABLE SCAN} / {@code TABLE FULL SCAN}（全表扫）→ {@code TABLE_FULL_SCAN}，EST.ROWS 超阈才提示；</li>
     *  <li>{@code TABLE RANGE SCAN}（走索引的范围扫）→ 不报「缺索引」，只在 EST.ROWS 超阈时报
     *      {@code HIGH_ROWS_ESTIMATE}（选择性差 / 大范围扫，与 MySQL 档同规则码同语义）；</li>
     *  <li>{@code TABLE GET} / {@code EXPRESSION} 等其它算子不产出建议（点查与常量计划无索引话题）。</li>
     * </ul>
     */
    private static void adviseObPlanText(@NotNull String text, @NotNull List<Hint> out) {
        List<String> lines = List.of(text.split("\n", -1));
        int headerIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (OB_PLAN_HEADER.matcher(lines.get(i)).find()) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return;
        List<String> header = obRowCells(lines.get(headerIdx));
        if (header == null) return;
        int opCol = -1;
        int nameCol = -1;
        int rowsCol = -1;
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim();
            if (h.equalsIgnoreCase("OPERATOR") && opCol < 0) opCol = i;
            else if (h.equalsIgnoreCase("NAME") && nameCol < 0) nameCol = i;
            else if (h.equalsIgnoreCase("EST.ROWS") && rowsCol < 0) rowsCol = i;
        }
        if (opCol < 0) return;
        for (int i = headerIdx + 1; i < lines.size(); i++) {
            List<String> cells = obRowCells(lines.get(i));
            if (cells == null) continue;
            if (cells.size() != header.size()) continue; // 列数不符 = 非计划行（分隔线/说明段），不猜
            String op = cells.get(opCol).trim();
            if (op.isEmpty()) continue;
            String target = nameCol >= 0 ? (cells.get(nameCol).trim().isEmpty() ? "?" : cells.get(nameCol).trim()) : "?";
            Long estRows = null;
            if (rowsCol >= 0) {
                try {
                    estRows = Long.parseLong(cells.get(rowsCol).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (OB_FULL_SCAN_OP.matcher(op).matches()) {
                if (estRows == null || estRows >= ROWS_THRESHOLD) {
                    out.add(new Hint(
                            "TABLE_FULL_SCAN",
                            target,
                            "full table scan (" + op + ")"
                                    + (estRows != null ? " over ~" + estRows + " rows" : "")
                                    + " — consider adding an index on the WHERE/JOIN columns"));
                }
            } else if (OB_RANGE_SCAN_OP.matcher(op).matches() && estRows != null && estRows >= ROWS_THRESHOLD) {
                out.add(new Hint(
                        "HIGH_ROWS_ESTIMATE",
                        target,
                        "estimated rows=" + estRows + " even with a range scan — poor selectivity / "
                                + "wide range scan; verify the filter columns are covered"));
            }
        }
    }

    /** OB ASCII 行 → 单元格清单（按 {@code |} 切，保留原始列数）；非 {@code |…|} 行（{@code ===} 分隔线、说明段）返回 null。 */
    @Nullable
    private static List<String> obRowCells(@NotNull String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 3 || !trimmed.startsWith("|") || !trimmed.endsWith("|")) return null;
        return List.of(trimmed.substring(1, trimmed.length() - 1).split("\\|", -1));
    }

    // ---------- PG/KingBase FORMAT JSON（T-ECH-03） ----------

    /** JSON 键名（PG 计划 JSON 的固定键；实测形态见类 KDoc）。 */
    private static final String KEY_NODE_TYPE = "Node Type";
    private static final String KEY_RELATION = "Relation Name";
    private static final String KEY_ALIAS = "Alias";
    private static final String KEY_PLAN_ROWS = "Plan Rows";
    private static final String KEY_ACTUAL_ROWS = "Actual Rows";

    /**
     * JSON 计划解析（T-ECH-03，真缺陷修复点）：PG/KingBase 的 {@code FORMAT JSON} 返回<b>单列 JSON 文本</b>，
     * 顶层是数组 {@code [{ "Plan": {…} }]}，节点键为 {@code "Node Type"} / {@code "Relation Name"} /
     * {@code "Plan Rows"}，子节点在 {@code "Plans"} 数组里。
     *
     * <p>容错三段（计划 §六「先试 JSON，失败回落」）：</p>
     * <ol>
     *  <li>整段 JSON 解析成功 → 按对象树遍历（正确处理嵌套与转义）；</li>
     *  <li>解析失败（<b>实测最常见的成因</b>是 {@code cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter}
     *      的 2000 字符单元格预算把长计划截断）→ 按「节点分片」正则扫描：截断只影响尾部，
     *      前面的节点对象仍完整，故仍能取到顶层节点；</li>
     *  <li>前两段<b>零命中</b>时回落文本树规则 {@link #advisePlanText}（PG 文本树形态仍可识别；
     *      真 JSON 计划零命中时该回落天然无命中，不产生噪音）。</li>
     * </ol>
     * <p>JSON 两段共用 {@link #emitSeqScanHint}——阈值与文案只有一份。</p>
     */
    private static void adviseJsonPlan(@NotNull List<List<String>> rows, @NotNull List<Hint> out) {
        for (List<String> row : rows) {
            for (String cell : row) {
                String text = cell.trim();
                if (text.isEmpty()) continue;
                int before = out.size();
                JsonElement parsed = null;
                try {
                    parsed = JsonParser.parseString(text);
                } catch (RuntimeException ignored) {
                    // runCatching 等价：解析失败（含截断）走节点分片扫描
                }
                if (parsed != null && (parsed.isJsonArray() || parsed.isJsonObject())) {
                    walkJsonPlan(parsed, out);
                } else {
                    scanJsonNodes(text, out);
                }
                if (out.size() == before) advisePlanText(List.of(List.of(cell)), out);
            }
        }
    }

    /** 对象树遍历：每个带 {@code "Node Type"} 的对象判一次，再递归其所有值（含 {@code "Plans"} 子计划）。 */
    private static void walkJsonPlan(@Nullable JsonElement el, @NotNull List<Hint> out) {
        if (el == null || el.isJsonNull()) {
            // nothing
        } else if (el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                walkJsonPlan(item, out);
            }
        } else if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            String relation = stringOf(obj, KEY_RELATION);
            if (relation == null) relation = stringOf(obj, KEY_ALIAS);
            Long planRows = longOf(obj, KEY_PLAN_ROWS);
            if (planRows == null) planRows = longOf(obj, KEY_ACTUAL_ROWS);
            emitSeqScanHint(stringOf(obj, KEY_NODE_TYPE), relation, planRows, out);
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                walkJsonPlan(entry.getValue(), out);
            }
        }
    }

    @Nullable
    private static String stringOf(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement v = obj.get(key);
        if (v == null || !v.isJsonPrimitive()) return null;
        try {
            return v.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 数值容忍：PG 出数字字面量，某些驱动/版本可能把数字包成字符串。 */
    @Nullable
    private static Long longOf(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement v = obj.get(key);
        if (v == null || !v.isJsonPrimitive()) return null;
        try {
            return v.getAsLong();
        } catch (RuntimeException ignored) {
            // 落字符串解析
        }
        try {
            String s = v.getAsString();
            return toLongOrNull(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 节点分片扫描（JSON 被截断时的回落路径）：每段 = 一个 {@code "Node Type"} 到下一个之前。 */
    private static void scanJsonNodes(@NotNull String text, @NotNull List<Hint> out) {
        Matcher scanner = JSON_NODE_TYPE.matcher(text);
        List<MatchResult> starts = new ArrayList<>();
        while (scanner.find()) {
            starts.add(scanner.toMatchResult());
        }
        for (int i = 0; i < starts.size(); i++) {
            MatchResult m = starts.get(i);
            int segmentEnd = i + 1 < starts.size() ? starts.get(i + 1).start() : text.length();
            String segment = text.substring(m.end(), segmentEnd);
            String relation = null;
            Matcher rm = JSON_RELATION.matcher(segment);
            if (rm.find()) {
                relation = rm.group(1);
            }
            Long planRows = null;
            Matcher pm = JSON_PLAN_ROWS.matcher(segment);
            if (pm.find()) {
                planRows = toLongOrNull(pm.group(1));
            }
            emitSeqScanHint(m.group(1), relation, planRows, out);
        }
    }

    /**
     * 单节点判据（JSON 两段路径共用）：{@code Seq Scan} 且（行数未知或 ≥ 阈值）→ 一条建议。
     * 关系名缺失（PG 对无表节点不输出 {@code "Relation Name"}）时目标记 {@code ?}，不虚报表名。
     */
    private static void emitSeqScanHint(@Nullable String nodeType, @Nullable String relation,
                                        @Nullable Long planRows, @NotNull List<Hint> out) {
        if (!"Seq Scan".equalsIgnoreCase(nodeType)) return;
        if (planRows != null && planRows < ROWS_THRESHOLD) return;
        out.add(new Hint(
                "SEQ_SCAN",
                relation != null && !relation.isBlank() ? relation : "?",
                "Sequential scan"
                        + (planRows != null ? " over ~" + planRows + " rows" : "")
                        + " — consider adding an index on the filter columns"));
    }

    /** 行内取单元格（越界/负列返回空串），trim 后返回。 */
    @NotNull
    private static String cell(@NotNull List<String> row, int col) {
        if (col < 0 || col >= row.size()) return "";
        String v = row.get(col);
        return v == null ? "" : v.trim();
    }

    /** Kotlin toLongOrNull 等价：无法整体解析为 Long 返回 null。 */
    private static Long toLongOrNull(@NotNull String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 建议节渲染（MCP explain 输出承载位；空清单返回空串——输出零差异硬门禁）。 */
    @NotNull
    public static String formatHints(@NotNull List<Hint> hints) {
        if (hints.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nindex hints:");
        for (Hint hint : hints) {
            sb.append("\n- [").append(hint.rule()).append("] ")
                    .append(hint.target()).append(": ").append(hint.advice());
        }
        return sb.toString();
    }

    /** 节点分片扫描用：JSON 键形态（{@code "Node Type":"Seq Scan"}）。 */
    private static final Pattern JSON_NODE_TYPE = Pattern.compile("\"Node Type\"\\s*:\\s*\"([^\"]*)\"");

    /** 关系名/别名（PG 对无表节点不出 {@code Relation Name}，退 {@code Alias}）。 */
    private static final Pattern JSON_RELATION = Pattern.compile("\"(?:Relation Name|Alias)\"\\s*:\\s*\"([^\"]*)\"");

    /** 估算行数（{@code Actual Rows} 仅 EXPLAIN ANALYZE 出现——本插件不产 analyze，容忍读取不改变口径）。 */
    private static final Pattern JSON_PLAN_ROWS = Pattern.compile("\"(?:Plan Rows|Actual Rows)\"\\s*:\\s*(\\d+)");
}
