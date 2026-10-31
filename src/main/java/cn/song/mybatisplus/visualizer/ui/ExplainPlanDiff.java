// 自 master ui/ExplainPlanDiff.kt 移植（R61：EXPLAIN 执行计划跨次对比，纯逻辑）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter;
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * R61：EXPLAIN 执行计划<b>跨次对比</b>（纯逻辑，无 IDE/UI 类型——裸 JUnit 可直测）。
 *
 * <p>输入＝同一 SQL 先后两次结构化 EXPLAIN 结果（{@link McpExplainFormatter.RawResult}，R33 结构化底座），
 * 覆盖结构化档两族（ExplainPanelChannel.STRUCTURED_FORMATS 同口径）：</p>
 * <ul>
 *  <li><b>JSON 档</b>（PG/KingBase/openGauss）：经 {@link ExplainJsonPlanTree#flattenPlanNodes} 取计划节点，
 *      按<b>对齐键</b>（{@code nodeType on relation#出现序}，V113①）配对，比 rows（Plan Rows/Actual Rows）、
 *      cost（Total Cost）与其余标量键；</li>
 *  <li><b>TABLE 档</b>（MySQL/MariaDB；OceanBase 已于 T-OBExplainFormat 按真机实证改登 TEXT 档——
 *      其 {@code EXPLAIN} 返回单列 {@code Query Plan} ASCII 树，不是表格行）：行按 {@code id}+{@code table} 列（缺列退行序）配对，
 *      数值列（rows/filtered/cost）给增量，其余列给文本变化。</li>
 * </ul>
 *
 * <p>诚实降级：格式不同族 / 解析不出计划结构 / 任一侧无行 → null（调用方按无可比处理，不硬比）。
 * Oracle DBMS_XPLAN 文本与 SQL Server XML 不建模（V113③ 裁量在册——文本缩进树需独立解析器，
 * 且无真机回归面，成本高于本期收益）。</p>
 */
public final class ExplainPlanDiff {

    private ExplainPlanDiff() {
    }

    /** 数值度量变化（old→new；泛型保住 Long/Double 各自精度）。 */
    public record MetricDelta<T extends Number>(T old, T newValue) {
    }

    /** 非数值字段/列变化（名，旧值，新值）。 */
    public record FieldChange(@NotNull String name, @NotNull String old, @NotNull String newValue) {
    }

    /** 一个配对节点的变化集（rows/cost 增量 + 字段级变化；全空则不产出）。 */
    public record NodeChange(@NotNull String matchKey, @NotNull String label,
                             @Nullable MetricDelta<Long> rowsDelta,
                             @Nullable MetricDelta<Double> costDelta,
                             @NotNull List<FieldChange> fieldChanges) {
    }

    /** 跨次对比结果（null=无可比；{@link #isEmpty()}=两次计划等价）。 */
    public record PlanDiff(@NotNull List<NodeChange> changed,
                           @NotNull List<String> added,
                           @NotNull List<String> removed) {

        public boolean isEmpty() {
            return changed.isEmpty() && added.isEmpty() && removed.isEmpty();
        }
    }

    /** TABLE 档参与数值增量的列名（小写匹配；缺列自然跳过）。 */
    private static final Set<String> NUMERIC_TABLE_COLUMNS = Set.of("rows", "filtered", "cost");

    /** 对比两次结果。任一侧不可结构化（空行/JSON 解析失败/格式不同族/非结构化档）→ null。 */
    @Nullable
    public static PlanDiff diff(@NotNull McpExplainFormatter.RawResult previous,
                                @NotNull McpExplainFormatter.RawResult current,
                                @NotNull ExplainOutputFormat format) {
        if (previous.rows().isEmpty() || current.rows().isEmpty()) return null;
        return switch (format) {
            case JSON -> diffJson(previous, current);
            case TABLE -> diffTable(previous, current);
            default -> null;
        };
    }

    // ---------- JSON 档 ----------

    @Nullable
    private static PlanDiff diffJson(@NotNull McpExplainFormatter.RawResult previous,
                                     @NotNull McpExplainFormatter.RawResult current) {
        List<ExplainJsonPlanTree.PlanNodeInfo> oldNodes = ExplainJsonPlanTree.flattenPlanNodes(previous.rows());
        List<ExplainJsonPlanTree.PlanNodeInfo> newNodes = ExplainJsonPlanTree.flattenPlanNodes(current.rows());
        if (oldNodes.isEmpty() || newNodes.isEmpty()) return null; // 解析不出计划结构：无可比
        Map<String, ExplainJsonPlanTree.PlanNodeInfo> oldByKey = byKey(oldNodes);
        Map<String, ExplainJsonPlanTree.PlanNodeInfo> newByKey = byKey(newNodes);

        List<NodeChange> changed = new ArrayList<>();
        for (Map.Entry<String, ExplainJsonPlanTree.PlanNodeInfo> e : newByKey.entrySet()) {
            ExplainJsonPlanTree.PlanNodeInfo oldNode = oldByKey.get(e.getKey());
            if (oldNode == null) continue;
            NodeChange nodeChange = nodeChangeOf(e.getKey(), oldNode, e.getValue());
            if (nodeChange == null) continue;
            changed.add(nodeChange);
        }
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, ExplainJsonPlanTree.PlanNodeInfo> e : newByKey.entrySet()) {
            if (!oldByKey.containsKey(e.getKey())) added.add(e.getValue().label());
        }
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, ExplainJsonPlanTree.PlanNodeInfo> e : oldByKey.entrySet()) {
            if (!newByKey.containsKey(e.getKey())) removed.add(e.getValue().label());
        }
        return new PlanDiff(changed, added, removed);
    }

    @NotNull
    private static Map<String, ExplainJsonPlanTree.PlanNodeInfo> byKey(
            @NotNull List<ExplainJsonPlanTree.PlanNodeInfo> nodes) {
        Map<String, ExplainJsonPlanTree.PlanNodeInfo> out = new LinkedHashMap<>();
        for (ExplainJsonPlanTree.PlanNodeInfo node : nodes) {
            out.putIfAbsent(node.matchKey(), node);
        }
        return out;
    }

    @Nullable
    private static NodeChange nodeChangeOf(@NotNull String key,
                                           @NotNull ExplainJsonPlanTree.PlanNodeInfo oldNode,
                                           @NotNull ExplainJsonPlanTree.PlanNodeInfo newNode) {
        MetricDelta<Long> rowsDelta = null;
        if (oldNode.rows() != null && newNode.rows() != null
                && !oldNode.rows().equals(newNode.rows())) {
            rowsDelta = new MetricDelta<>(oldNode.rows(), newNode.rows());
        }
        MetricDelta<Double> costDelta = null;
        if (oldNode.cost() != null && newNode.cost() != null
                && !oldNode.cost().equals(newNode.cost())) {
            costDelta = new MetricDelta<>(oldNode.cost(), newNode.cost());
        }
        List<FieldChange> fieldChanges = new ArrayList<>();
        for (Map.Entry<String, String> e : oldNode.extras().entrySet()) {
            String newValue = newNode.extras().get(e.getKey());
            if (newValue == null) continue;
            if (!e.getValue().equals(newValue)) {
                fieldChanges.add(new FieldChange(e.getKey(), e.getValue(), newValue));
            }
        }
        if (rowsDelta == null && costDelta == null && fieldChanges.isEmpty()) return null;
        return new NodeChange(key, oldNode.label(), rowsDelta, costDelta, fieldChanges);
    }

    // ---------- TABLE 档（MySQL 家族） ----------

    /** TABLE 行的对齐视图（matchKey + 列值表）。 */
    private record TableRow(@NotNull String matchKey, @NotNull String label,
                            @NotNull List<String> values, @NotNull Map<String, Integer> headerIndex) {
    }

    @NotNull
    private static List<TableRow> tableRows(@NotNull McpExplainFormatter.RawResult raw) {
        Map<String, Integer> headers = new HashMap<>();
        List<String> rawHeaders = raw.headers();
        for (int i = 0; i < rawHeaders.size(); i++) {
            headers.putIfAbsent(normalizeHeader(rawHeaders.get(i)), i);
        }
        Integer idIdx = headers.get("id");
        Integer tableIdx = headers.get("table");
        Map<String, Integer> ordinals = new HashMap<>();
        List<TableRow> out = new ArrayList<>();
        List<List<String>> rows = raw.rows();
        for (List<String> row : rows) {
            String id = idIdx != null && idIdx < row.size() && row.get(idIdx) != null
                    ? row.get(idIdx).trim() : "";
            String table = tableIdx != null && tableIdx < row.size() && row.get(tableIdx) != null
                    ? row.get(tableIdx).trim() : "";
            String base = (id.isEmpty() && table.isEmpty())
                    ? "row"
                    : ("id=" + id + " table=" + table).trim();
            int ord = ordinals.merge(base, 1, Integer::sum) - 1;
            StringBuilder label = new StringBuilder(base);
            // 行内自带 rows 展示（供 added/removed 行的上下文）
            Integer rowsIdx = headers.get("rows");
            if (rowsIdx != null && rowsIdx < row.size() && row.get(rowsIdx) != null) {
                String rowsText = row.get(rowsIdx).trim();
                if (!rowsText.isEmpty()) {
                    label.append(" (rows=").append(rowsText).append(')');
                }
            }
            out.add(new TableRow(base + "#" + ord, label.toString(), row, headers));
        }
        return out;
    }

    @Nullable
    private static PlanDiff diffTable(@NotNull McpExplainFormatter.RawResult previous,
                                      @NotNull McpExplainFormatter.RawResult current) {
        List<TableRow> oldRows = tableRows(previous);
        List<TableRow> newRows = tableRows(current);
        if (oldRows.isEmpty() || newRows.isEmpty()) return null;
        Map<String, TableRow> oldByKey = new LinkedHashMap<>();
        for (TableRow row : oldRows) oldByKey.putIfAbsent(row.matchKey(), row);
        Map<String, TableRow> newByKey = new LinkedHashMap<>();
        for (TableRow row : newRows) newByKey.putIfAbsent(row.matchKey(), row);

        List<NodeChange> changed = new ArrayList<>();
        for (Map.Entry<String, TableRow> e : newByKey.entrySet()) {
            TableRow oldRow = oldByKey.get(e.getKey());
            if (oldRow == null) continue;
            NodeChange change = rowChangeOf(e.getKey(), oldRow, e.getValue());
            if (change == null) continue;
            changed.add(change);
        }
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, TableRow> e : newByKey.entrySet()) {
            if (!oldByKey.containsKey(e.getKey())) added.add(e.getValue().label());
        }
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, TableRow> e : oldByKey.entrySet()) {
            if (!newByKey.containsKey(e.getKey())) removed.add(e.getValue().label());
        }
        return new PlanDiff(changed, added, removed);
    }

    @Nullable
    private static NodeChange rowChangeOf(@NotNull String key, @NotNull TableRow oldRow,
                                          @NotNull TableRow newRow) {
        MetricDelta<Long> rowsDelta = null;
        MetricDelta<Double> costDelta = null;
        List<FieldChange> fieldChanges = new ArrayList<>();
        for (Map.Entry<String, Integer> e : oldRow.headerIndex().entrySet()) {
            String header = e.getKey();
            Integer newIdx = newRow.headerIndex().get(header);
            if (newIdx == null) continue;
            String oldValue = cellAt(oldRow.values(), e.getValue());
            String newValue = cellAt(newRow.values(), newIdx);
            if (oldValue.equals(newValue)) continue;
            if ("rows".equals(header)) {
                Long oldNum = toLongOrNull(oldValue);
                Long newNum = toLongOrNull(newValue);
                if (oldNum != null && newNum != null) {
                    rowsDelta = new MetricDelta<>(oldNum, newNum);
                } else {
                    fieldChanges.add(new FieldChange(header, oldValue, newValue));
                }
            } else if ("cost".equals(header)) {
                Double oldNum = toDoubleOrNull(oldValue);
                Double newNum = toDoubleOrNull(newValue);
                if (oldNum != null && newNum != null) {
                    costDelta = new MetricDelta<>(oldNum, newNum);
                } else {
                    fieldChanges.add(new FieldChange(header, oldValue, newValue));
                }
            } else if (NUMERIC_TABLE_COLUMNS.contains(header)) {
                // 数值列的文本变化（无法解析为数字时同走文本变化）
                fieldChanges.add(new FieldChange(header, oldValue, newValue));
            } else {
                fieldChanges.add(new FieldChange(header, oldValue, newValue));
            }
        }
        if (rowsDelta == null && costDelta == null && fieldChanges.isEmpty()) return null;
        return new NodeChange(key, oldRow.label(), rowsDelta, costDelta, fieldChanges);
    }

    /** 列名规范化（小写去空白——MySQL EXPLAIN 列名族大小写稳定，防御性归一）。 */
    @NotNull
    private static String normalizeHeader(@NotNull String header) {
        return header.trim().toLowerCase(Locale.ROOT);
    }

    @NotNull
    private static String cellAt(@NotNull List<String> row, int index) {
        if (index < 0 || index >= row.size() || row.get(index) == null) return "";
        return row.get(index).trim();
    }

    @Nullable
    private static Long toLongOrNull(@NotNull String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Double toDoubleOrNull(@NotNull String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
