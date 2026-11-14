package cn.song.mybatisplus.visualizer.ui.diff

import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotAligner
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffToolType
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * T-RollbackSnapshotPreExec P3：改前/改后表格 diff 三件套（P0 探针的正式化，EP 同名替换）。
 *
 * 平台事实（P0 探针实证，见计划 §3.6）：243/262 的 `FrameDiffTool` 抽象方法都是
 * `createComponent(DiffContext, DiffRequest): DiffViewer`；`getToolType()`/`getIcon()` 带
 * 默认实现；EP 工具**不在**内置清单最前（243 实测下标 8/13），选择正确性由「唯一 canShow
 * 命中」保证——内置工具对非 ContentDiffRequest 全部 say-no。
 *
 * 已知取舍（计划 §3.6）：F7 差异导航与「接受/回退」类平台动作不自动获得；本 viewer 给
 * statusPanel 摘要（对齐方式 + 四类行计数），验收按「能看、能扫、能关」三条判。
 */
data class RollbackTableDiffRequest(
    val diffTitle: String,
    val beforeTitle: String,
    val afterTitle: String,
    val columnNames: List<String>,
    val alignedRows: List<RollbackSnapshotAligner.AlignedRow>,
    val alignmentWarnings: List<String> = emptyList(),
) : DiffRequest() {
    /** DiffRequest 抽象成员（243/262 classfile 实证）：diff 窗口标题。 */
    override fun getTitle(): String? = diffTitle
}

internal class RollbackTableDiffTool : FrameDiffTool {
    override fun getName(): String = "Rollback Snapshot Table Diff"

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean =
        request is RollbackTableDiffRequest

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer =
        RollbackTableDiffViewer(request as RollbackTableDiffRequest)

    override fun getToolType(): DiffToolType = DiffToolType.Default
}

internal class RollbackTableDiffViewer(private val request: RollbackTableDiffRequest) :
    FrameDiffTool.DiffViewer {

    private val root: JComponent = buildComponent()

    /** 行模型：左右两侧共用同一份对齐行序列——行对齐在构造时完成，两侧同下标即同一行。 */
    private class AlignedTableModel(
        private val columns: List<String>,
        private val rows: List<RollbackSnapshotAligner.AlignedRow>,
        private val after: Boolean,
    ) : AbstractTableModel() {
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): String {
            val row = rows[rowIndex]
            val cells = if (after) row.afterCells else row.beforeCells
            return cells?.getOrNull(columnIndex) ?: ""
        }

        fun rowStatus(rowIndex: Int): RollbackSnapshotAligner.CellStatus = rows[rowIndex].status
        fun isCellChanged(rowIndex: Int, columnIndex: Int): Boolean =
            rows[rowIndex].changedColumnIndexes.contains(columnIndex)
    }

    private fun buildTable(model: AlignedTableModel, background: (AlignedTableModel, Int, Int) -> Color?): JTable {
        val table = JTable(model)
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.setDefaultRenderer(
            Any::class.java,
            object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable,
                    value: Any?,
                    isSelected: Boolean,
                    hasFocus: Boolean,
                    row: Int,
                    column: Int,
                ): Component {
                    val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    if (!isSelected) {
                        background(model, row, column)?.let { c.background = it }
                    }
                    return c
                }
            },
        )
        return table
    }

    private fun buildComponent(): JComponent {
        val alignedModel = request.alignedRows
        val beforeModel = AlignedTableModel(request.columnNames, alignedModel, after = false)
        val afterModel = AlignedTableModel(request.columnNames, alignedModel, after = true)
        val beforeTable = buildTable(beforeModel) { m, r, c ->
            when (m.rowStatus(r)) {
                RollbackSnapshotAligner.CellStatus.REMOVED -> COLOR_REMOVED
                RollbackSnapshotAligner.CellStatus.CHANGED -> if (m.isCellChanged(r, c)) COLOR_CHANGED else null
                else -> null
            }
        }
        val afterTable = buildTable(afterModel) { m, r, c ->
            when (m.rowStatus(r)) {
                RollbackSnapshotAligner.CellStatus.ADDED -> COLOR_ADDED
                RollbackSnapshotAligner.CellStatus.CHANGED -> if (m.isCellChanged(r, c)) COLOR_CHANGED else null
                else -> null
            }
        }
        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            titledPane(request.beforeTitle, javax.swing.JScrollPane(beforeTable)),
            titledPane(request.afterTitle, javax.swing.JScrollPane(afterTable)),
        )
        split.resizeWeight = 0.5
        val panel = JPanel(BorderLayout())
        panel.add(split, BorderLayout.CENTER)
        panel.border = JBUI.Borders.empty()
        return panel
    }

    private fun titledPane(title: String, inner: JComponent): JPanel {
        val p = JPanel(BorderLayout())
        val label = JLabel(title)
        label.border = JBUI.Borders.empty(4, 8)
        p.add(label, BorderLayout.NORTH)
        p.add(inner, BorderLayout.CENTER)
        return p
    }

    /** statusPanel：对齐方式 + 四类行计数（摘要随 diff 窗口常驻）。 */
    private fun buildStatusPanel(): JComponent {
        val counts = request.alignedRows.groupingBy { it.status }.eachCount()
        val keyless = request.alignmentWarnings.isNotEmpty()
        val text = buildString {
            append("改前/改后：")
            append("相同 ${counts[RollbackSnapshotAligner.CellStatus.EQUAL] ?: 0} · ")
            append("改动 ${counts[RollbackSnapshotAligner.CellStatus.CHANGED] ?: 0} · ")
            append("删除 ${counts[RollbackSnapshotAligner.CellStatus.REMOVED] ?: 0} · ")
            append("新增 ${counts[RollbackSnapshotAligner.CellStatus.ADDED] ?: 0}")
            if (keyless) append("　⚠ ").append(request.alignmentWarnings.first())
        }
        val label = JLabel(text)
        label.horizontalAlignment = SwingConstants.CENTER
        return label
    }

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent? = null

    override fun init(): FrameDiffTool.ToolbarComponents =
        FrameDiffTool.ToolbarComponents().apply { statusPanel = buildStatusPanel() }

    override fun dispose() {
        LOG.debug("RollbackTableDiffViewer disposed")
    }

    companion object {
        private val LOG = Logger.getInstance(RollbackTableDiffViewer::class.java)

        // 单元格着色（浅/暗两值；饱和度压低避免喧宾夺主）
        private val COLOR_REMOVED = JBColor(Color(0xFFE0E0), Color(0x5A2E2E))
        private val COLOR_ADDED = JBColor(Color(0xDCEDC8), Color(0x2E4A2E))
        private val COLOR_CHANGED = JBColor(Color(0xFFF9C4), Color(0x5A4A1E))
    }
}
