package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.table.TableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeModel

/**
 * R33：EXPLAIN 结构化渲染视图（自建执行链的结果面，双通道并存——不替换原生控制台通道）。
 *
 * 布局（循仓内既有形态）：
 *  - NORTH 头部：`EXPLAIN · 方言 · 数据源 · 耗时`（数据源/耗时段缺省省略，不虚标）＋
 *    「回到原生控制台」入口（[onBackToConsole] 非 null 才出现——双通道并存的手动入口，
 *    自动回退走调用方的 fail-closed 链，不经此处）；
 *  - 索引建议条：[hints] 非空才占位（ExplainIndexAdvisor 规则集的面板侧消费位——
 *    T-EI-01 定案的「IDE 面建议条」挂账由本条落地；只提示不执行）；
 *  - R61 跨次对比条：[diff] 非 null 才占位（同一 SQL 会话级上一次结构化结果的对比——
 *    节点增删 + rows/cost 变化标注；形态照抄索引建议条，仅提示不执行）；
 *  - CENTER：TABLE 格式 → 只读 [JBTable]（循 buildColumnInfoPanel 既有表格形态）；
 *    JSON 格式 → 可折叠 [JTree]（[ExplainJsonPlanTree] 纯逻辑构建）；其余格式调用方不应到达
 *    （ExplainPanelChannel 判定已挡，兜底给如实占位文案）。
 *  - SOUTH 尾部：notices（驱动 WARNING/NOTICE，T-NoticeChannel 同源数据）非空时灰字逐条透出。
 */
class ExplainPlanResultView(
    dialectName: String,
    dataSourceName: String?,
    elapsedMs: Long,
    raw: McpExplainFormatter.RawResult,
    format: ExplainOutputFormat,
    hints: List<ExplainIndexAdvisor.Hint> = emptyList(),
    onBackToConsole: (() -> Unit)? = null,
    /** R61：与上一次结构化执行的对比（null=无基线/无可比——条不占位；isEmpty=计划无变化，显式告知）。 */
    diff: ExplainPlanDiff.PlanDiff? = null,
    baselines: List<cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Baseline> = emptyList(),
    historyStatus: cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult? = null,
) : JBPanel<ExplainPlanResultView>(BorderLayout()) {

    /** TABLE 格式的表格模型（其他格式 null）。 */
    private val tableModel: TableModel?

    /** JSON 格式的树模型（其他格式 null）。 */
    private val treeModel: TreeModel?

    private val hintsLabel: JBLabel
    private val compareLabel: JBLabel?
    private val backButton: JButton
    private val headerLabel: JBLabel
    private val baselineSelector = javax.swing.JComboBox<String>()
    private val historyStatusLabel = JBLabel()

    init {
        border = JBUI.Borders.empty()
        isOpaque = false

        // 头部：EXPLAIN · 方言 · 数据源 · 耗时（＋截断标记——诚实呈现行预算边界）
        headerLabel = JBLabel(
            buildString {
                append("<html><b>").append(MpSqlVisualizerBundle.message("panel.explain.structured.title"))
                append("</b> · ").append(dialectName)
                dataSourceName?.let { append(" · ").append(it) }
                if (elapsedMs >= 0) append(" · ").append(elapsedMs).append(" ms")
                if (raw.truncated) append(" · ").append(MpSqlVisualizerBundle.message("panel.explain.structured.truncated"))
                append("</html>")
            },
        ).apply { border = JBUI.Borders.empty(2, 4, 2, 4) }

        backButton = JButton(MpSqlVisualizerBundle.message("panel.explain.structured.backToConsole")).apply {
            isOpaque = false
            isContentAreaFilled = false
            border = JBUI.Borders.empty(1, 6)
            font = font.deriveFont(11f)
            foreground = JBColor.namedColor("Link.activeForeground", JBColor.GRAY)
            margin = JBUI.insets(0, 6, 0, 6)
            toolTipText = MpSqlVisualizerBundle.message("panel.explain.structured.backToConsole.tip")
            val action = onBackToConsole
            if (action == null) {
                isVisible = false
            } else {
                addActionListener { action() }
            }
        }

        // 索引建议条（只提示不执行；空清单不占位——与 MCP formatHints 的零追加同口径）
        // 2026-10-31 R45 面板本地化：文案按 rule 取 bundle（`explain.hint.<rule>`），
        // 命中则显示本地化文案（含 metric 括注），缺失回落 Hint.advice 英文原文。
        // MCP / 结构化通道仍消费 advice 字段，英文口径不变（单一逻辑源不分裂）。
        hintsLabel = JBLabel(
            if (hints.isEmpty()) "" else "<html>" + hints.joinToString("<br>") { hint ->
                "[${hint.rule}] <b>${escapeHtml(hint.target)}</b>: ${escapeHtml(localizedHint(hint))}"
            } + "</html>",
        ).apply {
            foreground = JBColor.namedColor("WarningForeground", JBColor.ORANGE)
            border = JBUI.Borders.empty(1, 4)
            isVisible = hints.isNotEmpty()
        }

        // R61 跨次对比条（照抄索引建议条形态；null=无基线不占位——isEmpty 也要显式告知「计划无变化」）
        compareLabel = if (diff != null || baselines.isNotEmpty()) JBLabel().apply {
            foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
            border = JBUI.Borders.empty(1, 4)
        } else null
        fun showComparison(value: ExplainPlanDiff.PlanDiff?) {
            compareLabel?.text = "<html><b>" + MpSqlVisualizerBundle.message("panel.explain.compare.title") + "</b><br>" +
                (value?.let { renderCompareLines(it).joinToString("<br>") { line -> escapeHtml(line) } }
                    ?: MpSqlVisualizerBundle.message("panel.explain.history.notComparable")) + "</html>"
        }
        showComparison(diff)
        baselines.forEach { baselineSelector.addItem(it.label) }
        baselineSelector.isVisible = baselines.isNotEmpty()
        baselineSelector.toolTipText = MpSqlVisualizerBundle.message("panel.explain.history.baseline")
        baselineSelector.addActionListener {
            baselines.getOrNull(baselineSelector.selectedIndex)?.let {
                showComparison(ExplainPlanDiff.diff(it.raw, raw, format))
            }
        }
        if (baselines.isNotEmpty()) showComparison(ExplainPlanDiff.diff(baselines.first().raw, raw, format))
        historyStatusLabel.text = historyStatus?.let {
            when (it) {
                cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult.SAVED -> MpSqlVisualizerBundle.message("panel.explain.history.SAVED")
                cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult.SESSION_ONLY -> MpSqlVisualizerBundle.message("panel.explain.history.SESSION_ONLY")
                cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult.TOO_LARGE -> MpSqlVisualizerBundle.message("panel.explain.history.TOO_LARGE")
                cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult.CLEARED_OR_REMOVED -> MpSqlVisualizerBundle.message("panel.explain.history.CLEARED_OR_REMOVED")
                cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult.RETENTION_DISABLED -> MpSqlVisualizerBundle.message("panel.explain.history.RETENTION_DISABLED")
            }
        }.orEmpty()
        historyStatusLabel.isVisible = historyStatus != null
        historyStatusLabel.border = JBUI.Borders.empty(1, 4)

        val north = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            add(headerLabel, BorderLayout.NORTH)
            add(hintsLabel, BorderLayout.CENTER)
            add(backButton, BorderLayout.EAST)
        }
        // R61：对比条叠在头部块之下（纵向栈——头部/建议条/对比条自上而下）
        val northStrip = JBPanel<JBPanel<*>>().apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(north)
            if (baselines.isNotEmpty()) {
                // 基线选择行：标签与下拉横向并排，整行左对齐且宽度受限于弹窗；
                // 下拉自身固定首选宽度——BoxLayout(Y_AXIS) 下 JComboBox 会被撑到容器
                // 最大宽度，弹窗内容够宽时弹出层会横跨整屏（20261023 实测）。
                baselineSelector.preferredSize = Dimension(
                    JBUI.scale(220), baselineSelector.preferredSize.height,
                )
                // 关键：maximumSize 也要钉住。FlowLayout 本身不会拉伸子组件，但控件若被
                // 放进纵向 BoxLayout 的祖先里，BoxLayout 读的是 maximumSize——只设
                // preferredSize 仍会被撑满（20261023 反向验证实证：仅设 preferred 时
                // 「宽度有界」断言可通过，实际渲染仍横跨整屏）。
                baselineSelector.maximumSize = Dimension(JBUI.scale(240), baselineSelector.preferredSize.height)
                val baselineRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
                    isOpaque = false
                    add(JBLabel(MpSqlVisualizerBundle.message("panel.explain.history.baseline")))
                    add(baselineSelector)
                }
                baselineRow.maximumSize = Dimension(Int.MAX_VALUE, baselineRow.preferredSize.height)
                add(baselineRow)
            }
            add(historyStatusLabel)
            compareLabel?.let { add(it) }
        }
        add(northStrip, BorderLayout.NORTH)

        // 中央内容：按格式分派（结构化档只有 TABLE/JSON 会到达——ExplainPanelChannel 判定已挡）
        var table: TableModel? = null
        var tree: TreeModel? = null
        val content: JComponent = when (format) {
            ExplainOutputFormat.TABLE -> {
                val model = object : javax.swing.table.DefaultTableModel(
                    raw.rows.map { it.toTypedArray() }.toTypedArray(),
                    raw.headers.toTypedArray(),
                ) {
                    override fun isCellEditable(row: Int, column: Int): Boolean = false
                }
                table = model
                JBTable(model).apply {
                    autoResizeMode = JBTable.AUTO_RESIZE_ALL_COLUMNS
                    tableHeader.reorderingAllowed = false
                }
            }
            ExplainOutputFormat.JSON -> {
                val roots = ExplainJsonPlanTree.build(raw.rows).orEmpty()
                val rootNode = DefaultMutableTreeNode("EXPLAIN")
                roots.forEach { r -> rootNode.add(toSwingNode(r)) }
                tree = DefaultTreeModel(rootNode)
                JTree(tree).apply {
                    isRootVisible = false
                    showsRootHandles = true
                }
            }
            else -> JBLabel(MpSqlVisualizerBundle.message("panel.explain.structured.unsupportedFormat"))
        }
        add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)

        // 驱动 notices 尾部透出（T-NoticeChannel 同源数据；空清单零追加）
        if (raw.notices.isNotEmpty()) {
            add(
                JBLabel("<html>" + raw.notices.joinToString("<br>") { escapeHtml(it) } + "</html>").apply {
                    foreground = JBColor.GRAY
                    border = JBUI.Borders.empty(1, 4)
                },
                BorderLayout.SOUTH,
            )
        }

        tableModel = table
        treeModel = tree
    }

    private fun toSwingNode(node: ExplainJsonPlanTree.Node): DefaultMutableTreeNode {
        val swing = DefaultMutableTreeNode(node.label)
        node.children.forEach { swing.add(toSwingNode(it)) }
        return swing
    }

    /**
     * R61：对比结果 → 人读行清单（bundle 双语；数值走 [java.util.Locale.ROOT] 格式化——
     * 防本机 locale 把小数点渲染成逗号污染 SQL 语境）。行序：变更（节点标签 + rows/cost/字段
     * 变化）→ 新增 → 移除；无任何变化 → 单行「计划无变化」。
     */
    internal fun renderCompareLines(d: ExplainPlanDiff.PlanDiff): List<String> {
        if (d.isEmpty) return listOf(MpSqlVisualizerBundle.message("panel.explain.compare.unchanged"))
        val lines = mutableListOf<String>()
        for (change in d.changed) {
            val parts = mutableListOf<String>()
            change.rowsDelta?.let { parts += MpSqlVisualizerBundle.message("panel.explain.compare.rows", it.old, it.new) }
            change.costDelta?.let {
                parts += MpSqlVisualizerBundle.message(
                    "panel.explain.compare.cost",
                    String.format(java.util.Locale.ROOT, "%.1f", it.old),
                    String.format(java.util.Locale.ROOT, "%.1f", it.new),
                )
            }
            change.fieldChanges.forEach {
                parts += MpSqlVisualizerBundle.message("panel.explain.compare.field", it.name, it.old, it.new)
            }
            if (parts.isNotEmpty()) lines += "${change.label}: ${parts.joinToString(", ")}"
        }
        d.added.forEach { lines += MpSqlVisualizerBundle.message("panel.explain.compare.added", it) }
        d.removed.forEach { lines += MpSqlVisualizerBundle.message("panel.explain.compare.removed", it) }
        return lines
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * 索引建议的 IDE 面文案：按规则码取 `explain.hint.<rule>` 本地化串，命中则附 metric 括注；
     * bundle 缺键（`message` 缺键时原样返回 key）→ 回落 [ExplainIndexAdvisor.Hint.advice] 英文原文。
     *
     * 判据用「返回值 == key」而非异常：`MpSqlVisualizerBundle.message` 的设计就是缺键返回 key 本身。
     */
    private fun localizedHint(hint: ExplainIndexAdvisor.Hint): String {
        val key = "explain.hint.${hint.rule}"
        val localized = MpSqlVisualizerBundle.message(key)
        if (localized == key) return hint.advice
        val metric = hint.metric
        return if (metric.isNullOrBlank()) localized else "$localized ($metric)"
    }

    // ---------- 测试探针（internal，同 SqlResultPanel.currentErrorForTest 惯例） ----------

    internal fun tableModelForTest(): TableModel? = tableModel

    internal fun treeModelForTest(): TreeModel? = treeModel

    internal fun hintsTextForTest(): String? = if (hintsLabel.isVisible) hintsLabel.text else null

    internal fun hintsComponentForTest(): JComponent = hintsLabel

    internal fun backButtonForTest(): JButton = backButton

    internal fun headerTextForTest(): String = headerLabel.text

    /** R61 测试缝：对比条文本（null=无对比条——无基线不占位）。 */
    internal fun compareTextForTest(): String? = compareLabel?.text
    internal fun baselineSelectorForTest(): javax.swing.JComboBox<String> = baselineSelector
    internal fun historyStatusForTest(): String = historyStatusLabel.text
}
