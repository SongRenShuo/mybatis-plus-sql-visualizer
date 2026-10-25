// 自 master ui/ExplainPlanResultView.kt 移植（R33 结构化渲染视图；R61 跨次对比；R90 基线选择；
// T-ExplainConsoleFirst 基线行修复：FlowLayout 横排 + preferredSize/maximumSize 双钉防横跨整屏）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession;
import cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult;
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter;
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat;
import cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;

/**
 * R33：EXPLAIN 结构化渲染视图（自建执行链的结果面，双通道并存——不替换原生控制台通道）。
 *
 * <p>布局（循仓内既有形态）：</p>
 * <ul>
 *  <li>NORTH 头部：{@code EXPLAIN · 方言 · 数据源 · 耗时}（数据源/耗时段缺省省略，不虚标）＋
 *    「回到原生控制台」入口（{@code onBackToConsole} 非 null 才出现——双通道并存的手动入口，
 *    自动回退走调用方的 fail-closed 链，不经此处）；</li>
 *  <li>索引建议条：{@code hints} 非空才占位（ExplainIndexAdvisor 规则集的面板侧消费位——
 *    T-EI-01 定案的「IDE 面建议条」挂账由本条落地；只提示不执行）；</li>
 *  <li>R61 跨次对比条：{@code diff} 非 null 才占位（同一 SQL 会话级上一次结构化结果的对比——
 *    节点增删 + rows/cost 变化标注；形态照抄索引建议条，仅提示不执行）；</li>
 *  <li>R90 基线选择行：{@code baselines} 非空才占位（标签与下拉横向并排——下拉 preferredSize/
 *    maximumSize 双钉防 BoxLayout 祖先撑满横跨整屏，20261023 实测）；</li>
 *  <li>CENTER：TABLE 格式 → 只读 {@link JBTable}（循 buildColumnInfoPanel 既有表格形态）；
 *    JSON 格式 → 可折叠 {@link JTree}（{@link ExplainJsonPlanTree} 纯逻辑构建）；其余格式调用方不应到达
 *    （ExplainPanelChannel 判定已挡，兜底给如实占位文案）。</li>
 *  <li>SOUTH 尾部：notices（驱动 WARNING/NOTICE，T-NoticeChannel 同源数据）非空时灰字逐条透出。</li>
 * </ul>
 */
public class ExplainPlanResultView extends JBPanel<ExplainPlanResultView> {

    /** TABLE 格式的表格模型（其他格式 null）。 */
    private final TableModel tableModel;

    /** JSON 格式的树模型（其他格式 null）。 */
    private final TreeModel treeModel;

    private final JBLabel hintsLabel;
    @Nullable
    private final JBLabel compareLabel;
    private final JButton backButton;
    private final JBLabel headerLabel;
    private final javax.swing.JComboBox<String> baselineSelector = new javax.swing.JComboBox<>();
    private final JBLabel historyStatusLabel;

    /** Kotlin 默认参数全缺省重载：{@code hints=emptyList(), onBackToConsole=null, diff/baselines/historyStatus=null}。 */
    public ExplainPlanResultView(@NotNull String dialectName,
                                 @Nullable String dataSourceName,
                                 long elapsedMs,
                                 @NotNull McpExplainFormatter.RawResult raw,
                                 @NotNull ExplainOutputFormat format) {
        this(dialectName, dataSourceName, elapsedMs, raw, format, List.of(), null, null, List.of(), null);
    }

    /** Kotlin 默认参数等价重载：{@code onBackToConsole=null, diff/baselines/historyStatus 缺省}。 */
    public ExplainPlanResultView(@NotNull String dialectName,
                                 @Nullable String dataSourceName,
                                 long elapsedMs,
                                 @NotNull McpExplainFormatter.RawResult raw,
                                 @NotNull ExplainOutputFormat format,
                                 @NotNull List<ExplainIndexAdvisor.Hint> hints) {
        this(dialectName, dataSourceName, elapsedMs, raw, format, hints, null, null, List.of(), null);
    }

    /** Kotlin 默认参数等价重载：{@code diff/baselines/historyStatus 缺省}。 */
    public ExplainPlanResultView(@NotNull String dialectName,
                                 @Nullable String dataSourceName,
                                 long elapsedMs,
                                 @NotNull McpExplainFormatter.RawResult raw,
                                 @NotNull ExplainOutputFormat format,
                                 @NotNull List<ExplainIndexAdvisor.Hint> hints,
                                 @Nullable Runnable onBackToConsole) {
        this(dialectName, dataSourceName, elapsedMs, raw, format, hints, onBackToConsole, null, List.of(), null);
    }

    /** 全参形态（R61 对比 + R90 基线选择 + 持久化状态行）。 */
    public ExplainPlanResultView(@NotNull String dialectName,
                                 @Nullable String dataSourceName,
                                 long elapsedMs,
                                 @NotNull McpExplainFormatter.RawResult raw,
                                 @NotNull ExplainOutputFormat format,
                                 @NotNull List<ExplainIndexAdvisor.Hint> hints,
                                 @Nullable Runnable onBackToConsole,
                                 @Nullable ExplainPlanDiff.PlanDiff diff,
                                 @NotNull List<ExplainPlanHistorySession.Baseline> baselines,
                                 @Nullable ExplainPlanSaveResult historyStatus) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.empty());
        setOpaque(false);

        // 头部：EXPLAIN · 方言 · 数据源 · 耗时（＋截断标记——诚实呈现行预算边界）
        StringBuilder header = new StringBuilder();
        header.append("<html><b>").append(MpSqlVisualizerBundle.message("panel.explain.structured.title"));
        header.append("</b> · ").append(dialectName);
        if (dataSourceName != null) header.append(" · ").append(dataSourceName);
        if (elapsedMs >= 0) header.append(" · ").append(elapsedMs).append(" ms");
        if (raw.truncated()) header.append(" · ").append(MpSqlVisualizerBundle.message("panel.explain.structured.truncated"));
        header.append("</html>");
        headerLabel = new JBLabel(header.toString());
        headerLabel.setBorder(JBUI.Borders.empty(2, 4, 2, 4));

        backButton = new JButton(MpSqlVisualizerBundle.message("panel.explain.structured.backToConsole"));
        backButton.setOpaque(false);
        backButton.setContentAreaFilled(false);
        backButton.setBorder(JBUI.Borders.empty(1, 6));
        backButton.setFont(backButton.getFont().deriveFont(11f));
        backButton.setForeground(JBColor.namedColor("Link.activeForeground", JBColor.GRAY));
        backButton.setMargin(JBUI.insets(0, 6, 0, 6));
        backButton.setToolTipText(MpSqlVisualizerBundle.message("panel.explain.structured.backToConsole.tip"));
        if (onBackToConsole == null) {
            backButton.setVisible(false);
        } else {
            backButton.addActionListener(e -> onBackToConsole.run());
        }

        // 索引建议条（只提示不执行；空清单不占位——与 MCP formatHints 的零追加同口径）
        String hintsText = "";
        if (!hints.isEmpty()) {
            StringBuilder hintsHtml = new StringBuilder("<html>");
            for (int i = 0; i < hints.size(); i++) {
                ExplainIndexAdvisor.Hint hint = hints.get(i);
                if (i > 0) hintsHtml.append("<br>");
                hintsHtml.append("[").append(hint.rule()).append("] <b>")
                        .append(escapeHtml(hint.target())).append("</b>: ")
                        .append(escapeHtml(hint.advice()));
            }
            hintsHtml.append("</html>");
            hintsText = hintsHtml.toString();
        }
        hintsLabel = new JBLabel(hintsText);
        hintsLabel.setForeground(JBColor.namedColor("WarningForeground", JBColor.ORANGE));
        hintsLabel.setBorder(JBUI.Borders.empty(1, 4));
        hintsLabel.setVisible(!hints.isEmpty());

        // R61 跨次对比条（照抄索引建议条形态；null=无基线不占位——isEmpty 也要显式告知「计划无变化」）
        if (diff != null || !baselines.isEmpty()) {
            compareLabel = new JBLabel();
            compareLabel.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
            compareLabel.setBorder(JBUI.Borders.empty(1, 4));
        } else {
            compareLabel = null;
        }
        showComparison(diff);
        for (ExplainPlanHistorySession.Baseline baseline : baselines) {
            baselineSelector.addItem(baseline.label());
        }
        baselineSelector.setVisible(!baselines.isEmpty());
        baselineSelector.setToolTipText(MpSqlVisualizerBundle.message("panel.explain.history.baseline"));
        baselineSelector.addActionListener(e -> {
            int idx = baselineSelector.getSelectedIndex();
            if (idx >= 0 && idx < baselines.size()) {
                showComparison(ExplainPlanDiff.diff(baselines.get(idx).raw(), raw, format));
            }
        });
        if (!baselines.isEmpty()) {
            showComparison(ExplainPlanDiff.diff(baselines.get(0).raw(), raw, format));
        }
        if (historyStatus != null) {
            historyStatusLabel.setText(historyStatusLabelText(historyStatus));
            historyStatusLabel.setVisible(true);
        } else {
            historyStatusLabel.setText("");
            historyStatusLabel.setVisible(false);
        }
        historyStatusLabel.setBorder(JBUI.Borders.empty(1, 4));

        JBPanel<JBPanel<?>> north = new JBPanel<JBPanel<?>>(new BorderLayout());
        north.setOpaque(false);
        north.setBorder(JBUI.Borders.empty());
        north.add(headerLabel, BorderLayout.NORTH);
        north.add(hintsLabel, BorderLayout.CENTER);
        north.add(backButton, BorderLayout.EAST);

        // R61：对比条叠在头部块之下（纵向栈——头部/建议条/基线行/状态行/对比条自上而下）
        JBPanel<JBPanel<?>> northStrip = new JBPanel<JBPanel<?>>();
        northStrip.setOpaque(false);
        northStrip.setBorder(JBUI.Borders.empty());
        northStrip.setLayout(new javax.swing.BoxLayout(northStrip, javax.swing.BoxLayout.Y_AXIS));
        northStrip.add(north);
        if (!baselines.isEmpty()) {
            // 基线选择行：标签与下拉横向并排，整行左对齐且宽度受限于弹窗；
            // 下拉自身固定首选宽度——BoxLayout(Y_AXIS) 下 JComboBox 会被撑到容器
            // 最大宽度，弹窗内容够宽时弹出层会横跨整屏（20261023 实测）。
            baselineSelector.setPreferredSize(new Dimension(
                    JBUI.scale(220), baselineSelector.getPreferredSize().height));
            // 关键：maximumSize 也要钉住。FlowLayout 本身不会拉伸子组件，但控件若被
            // 放进纵向 BoxLayout 的祖先里，BoxLayout 读的是 maximumSize——只设
            // preferredSize 仍会被撑满（20261023 反向验证实证：仅设 preferred 时
            // 「宽度有界」断言可通过，实际渲染仍横跨整屏）。
            baselineSelector.setMaximumSize(new Dimension(
                    JBUI.scale(240), baselineSelector.getPreferredSize().height));
            JBPanel<JBPanel<?>> baselineRow = new JBPanel<JBPanel<?>>(
                    new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2)));
            baselineRow.setOpaque(false);
            baselineRow.add(new JBLabel(MpSqlVisualizerBundle.message("panel.explain.history.baseline")));
            baselineRow.add(baselineSelector);
            baselineRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, baselineRow.getPreferredSize().height));
            northStrip.add(baselineRow);
        }
        northStrip.add(historyStatusLabel);
        if (compareLabel != null) {
            northStrip.add(compareLabel);
        }
        add(northStrip, BorderLayout.NORTH);

        // 中央内容：按格式分派（结构化档只有 TABLE/JSON 会到达——ExplainPanelChannel 判定已挡）
        TableModel table = null;
        TreeModel tree = null;
        JComponent content;
        if (format == ExplainOutputFormat.TABLE) {
            Object[][] data = new Object[raw.rows().size()][];
            for (int i = 0; i < raw.rows().size(); i++) {
                data[i] = raw.rows().get(i).toArray();
            }
            DefaultTableModel model = new DefaultTableModel(data, raw.headers().toArray()) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            table = model;
            JBTable jbTable = new JBTable(model);
            jbTable.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
            jbTable.getTableHeader().setReorderingAllowed(false);
            content = jbTable;
        } else if (format == ExplainOutputFormat.JSON) {
            List<ExplainJsonPlanTree.Node> roots = ExplainJsonPlanTree.build(raw.rows());
            if (roots == null) roots = List.of();
            DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("EXPLAIN");
            for (ExplainJsonPlanTree.Node r : roots) rootNode.add(toSwingNode(r));
            tree = new DefaultTreeModel(rootNode);
            JTree jTree = new JTree(tree);
            jTree.setRootVisible(false);
            jTree.setShowsRootHandles(true);
            content = jTree;
        } else {
            content = new JBLabel(MpSqlVisualizerBundle.message("panel.explain.structured.unsupportedFormat"));
        }
        JBScrollPane scroll = new JBScrollPane(content);
        scroll.setBorder(JBUI.Borders.empty());
        add(scroll, BorderLayout.CENTER);

        // 驱动 notices 尾部透出（T-NoticeChannel 同源数据；空清单零追加）
        if (!raw.notices().isEmpty()) {
            StringBuilder noticesHtml = new StringBuilder("<html>");
            for (int i = 0; i < raw.notices().size(); i++) {
                if (i > 0) noticesHtml.append("<br>");
                noticesHtml.append(escapeHtml(raw.notices().get(i)));
            }
            noticesHtml.append("</html>");
            JBLabel noticesLabel = new JBLabel(noticesHtml.toString());
            noticesLabel.setForeground(JBColor.GRAY);
            noticesLabel.setBorder(JBUI.Borders.empty(1, 4));
            add(noticesLabel, BorderLayout.SOUTH);
        }

        tableModel = table;
        treeModel = tree;
    }

    private static String historyStatusLabelText(@NotNull ExplainPlanSaveResult status) {
        switch (status) {
            case SAVED:
                return MpSqlVisualizerBundle.message("panel.explain.history.SAVED");
            case SESSION_ONLY:
                return MpSqlVisualizerBundle.message("panel.explain.history.SESSION_ONLY");
            case TOO_LARGE:
                return MpSqlVisualizerBundle.message("panel.explain.history.TOO_LARGE");
            case CLEARED_OR_REMOVED:
                return MpSqlVisualizerBundle.message("panel.explain.history.CLEARED_OR_REMOVED");
            case RETENTION_DISABLED:
                return MpSqlVisualizerBundle.message("panel.explain.history.RETENTION_DISABLED");
            default:
                return "";
        }
    }

    private void showComparison(@Nullable ExplainPlanDiff.PlanDiff value) {
        if (compareLabel == null) {
            return;
        }
        StringBuilder text = new StringBuilder("<html><b>")
                .append(MpSqlVisualizerBundle.message("panel.explain.compare.title"))
                .append("</b><br>");
        if (value != null) {
            List<String> lines = renderCompareLines(value);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) text.append("<br>");
                text.append(escapeHtml(lines.get(i)));
            }
        } else {
            text.append(MpSqlVisualizerBundle.message("panel.explain.history.notComparable"));
        }
        text.append("</html>");
        compareLabel.setText(text.toString());
    }

    private DefaultMutableTreeNode toSwingNode(ExplainJsonPlanTree.Node node) {
        DefaultMutableTreeNode swing = new DefaultMutableTreeNode(node.label());
        for (ExplainJsonPlanTree.Node child : node.children()) {
            swing.add(toSwingNode(child));
        }
        return swing;
    }

    /**
     * R61：对比结果 → 人读行清单（bundle 双语；数值走 {@link java.util.Locale#ROOT} 格式化——
     * 防本机 locale 把小数点渲染成逗号污染 SQL 语境）。行序：变更（节点标签 + rows/cost/字段
     * 变化）→ 新增 → 移除；无任何变化 → 单行「计划无变化」。
     */
    @NotNull
    public List<String> renderCompareLines(@NotNull ExplainPlanDiff.PlanDiff d) {
        if (d.isEmpty()) {
            return List.of(MpSqlVisualizerBundle.message("panel.explain.compare.unchanged"));
        }
        List<String> lines = new java.util.ArrayList<>();
        for (ExplainPlanDiff.NodeChange change : d.changed()) {
            List<String> parts = new java.util.ArrayList<>();
            if (change.rowsDelta() != null) {
                parts.add(MpSqlVisualizerBundle.message("panel.explain.compare.rows",
                        change.rowsDelta().old(), change.rowsDelta().newValue()));
            }
            if (change.costDelta() != null) {
                parts.add(MpSqlVisualizerBundle.message("panel.explain.compare.cost",
                        String.format(java.util.Locale.ROOT, "%.1f", change.costDelta().old()),
                        String.format(java.util.Locale.ROOT, "%.1f", change.costDelta().newValue())));
            }
            for (ExplainPlanDiff.FieldChange fc : change.fieldChanges()) {
                parts.add(MpSqlVisualizerBundle.message("panel.explain.compare.field",
                        fc.name(), fc.old(), fc.newValue()));
            }
            if (!parts.isEmpty()) {
                lines.add(change.label() + ": " + String.join(", ", parts));
            }
        }
        for (String added : d.added()) {
            lines.add(MpSqlVisualizerBundle.message("panel.explain.compare.added", added));
        }
        for (String removed : d.removed()) {
            lines.add(MpSqlVisualizerBundle.message("panel.explain.compare.removed", removed));
        }
        return lines;
    }

    private static String escapeHtml(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- 测试探针（internal，同 SqlResultPanel.currentErrorForTest 惯例） ----------

    @Nullable
    public TableModel tableModelForTest() {
        return tableModel;
    }

    @Nullable
    public TreeModel treeModelForTest() {
        return treeModel;
    }

    @Nullable
    public String hintsTextForTest() {
        return hintsLabel.isVisible() ? hintsLabel.getText() : null;
    }

    @NotNull
    public JComponent hintsComponentForTest() {
        return hintsLabel;
    }

    @NotNull
    public JButton backButtonForTest() {
        return backButton;
    }

    @NotNull
    public String headerTextForTest() {
        return headerLabel.getText();
    }

    /** R61 测试缝：对比条文本（null=无对比条——无基线不占位）。 */
    @Nullable
    public String compareTextForTest() {
        return compareLabel != null ? compareLabel.getText() : null;
    }

    @NotNull
    public javax.swing.JComboBox<String> baselineSelectorForTest() {
        return baselineSelector;
    }

    @NotNull
    public String historyStatusForTest() {
        return historyStatusLabel.getText();
    }
}
