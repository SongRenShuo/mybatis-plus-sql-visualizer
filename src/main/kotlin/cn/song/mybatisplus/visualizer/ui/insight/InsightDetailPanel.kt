package cn.song.mybatisplus.visualizer.ui.insight

import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

internal data class InsightDetailRow(val cells: List<String>, val detail: String)

/**
 * 操作行容器（UI-001 修复，2026-10-31 GUI 走查发现）。
 *
 * 原生 `FlowLayout` 的 `preferredSize` 在**未布局时按单行**估算：按钮在窄宽度下换行后高度仍按一行报，
 * 外层 `BorderLayout` 的 NORTH 高度随之偏小，第二行按钮被下面的状态文案压住——实测 4.5 的
 * 「反查对象引用/打开选中引用/取消/关闭分析」四个按钮与 4.8 的第三个勾选**屏幕不可见且点不动**
 * （robot 有 bounds、合成点击无效）。
 *
 * 这里按父容器**实际宽度**累计行高，让 NORTH 拿到真实高度；宽度未知（首次布局）时退回原生估算，
 * 父容器 resize 会经 validate 链重新询问。
 */
internal class WrapFlowPanel : JPanel(FlowLayout(FlowLayout.LEADING)) {
    /**
     * 父容器在 `doLayout` 时注入的可用宽度。`preferredSize` 依赖宽度、宽度又来自父容器的布局结果，
     * 直接读 `parent.width` 在首次布局时为 0（会退回单行估算，回到原缺陷），故由父容器显式喂入。
     */
    var preferredWidthHint: Int = 0

    override fun getPreferredSize(): Dimension {
        val flow = layout as? FlowLayout ?: return super.getPreferredSize()
        val targetWidth = preferredWidthHint.takeIf { it > 0 }
            ?: (parent?.width ?: 0).takeIf { it > 0 }
            ?: return super.getPreferredSize()
        val ins = insets
        val available = targetWidth - ins.left - ins.right
        if (available <= 0) return super.getPreferredSize()
        var rowWidth = 0
        var rowHeight = 0
        var totalHeight = 0
        for (component in components) {
            if (!component.isVisible) continue
            val d = component.preferredSize
            if (rowWidth > 0 && rowWidth + flow.hgap + d.width > available) {
                totalHeight += rowHeight + flow.vgap
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += (if (rowWidth > 0) flow.hgap else 0) + d.width
            rowHeight = maxOf(rowHeight, d.height)
        }
        totalHeight += rowHeight + ins.top + ins.bottom
        return Dimension(targetWidth, totalHeight)
    }
}

/** Reusable local snapshot viewer: cells and details are always plain text, never HTML. */
internal class InsightDetailPanel(headers: List<String>, notice: String) : JPanel(BorderLayout()) {
    val actions = WrapFlowPanel()
    val status = JTextArea(notice).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 3 }
    val detail = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    val table = JTable().apply {
        setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            init { putClientProperty("html.disable", true) }
        })
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    }
    private val headers = headers.toTypedArray()
    private var rows = emptyList<InsightDetailRow>()
    init {
        // T-InsightDarkThemeFix：分隔条/滚动条必须用平台组件——原生 JSplitPane 的 Windows LAF
        // 分隔条与 JScrollPane 滚动条不跟随 Darcula，深色主题下在明细表底部呈现为一条浅色白条
        //（2026-10-25 沙盒实测）。OnePixelSplitter 的 1px 分隔条取主题色，JBScrollPane 同理。
        val split = OnePixelSplitter(true, 0.45f).apply {
            firstComponent = JBScrollPane(table)
            secondComponent = JBScrollPane(detail)
        }
        add(JPanel(BorderLayout()).apply { add(actions, BorderLayout.NORTH); add(status, BorderLayout.CENTER) }, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) detail.text = rows.getOrNull(table.selectedRow)?.detail.orEmpty()
        }
        showRows(emptyList())
    }
    fun button(label: String, action: () -> Unit): JButton = JButton(label).also { b -> b.addActionListener { action() }; actions.add(b) }

    /**
     * UI-001 修复的收口环节：布局前把可用宽度喂给操作行，让它按真实宽度报多行高度；
     * 宽度变化时 revalidate 一次，使父容器重新询问 preferredSize（有值变化才触发，收敛无死循环）。
     */
    override fun doLayout() {
        val available = width - insets.left - insets.right
        if (available > 0 && actions.preferredWidthHint != available) {
            actions.preferredWidthHint = available
            revalidate()
        }
        super.doLayout()
    }
    fun showRows(values: List<InsightDetailRow>) {
        rows = values.take(2000)
        table.model = object : DefaultTableModel(rows.map { it.cells.toTypedArray() }.toTypedArray(), headers) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        detail.text = ""
        if (rows.isNotEmpty()) table.setRowSelectionInterval(0, 0)
    }
    fun selectedRow(): Int = table.selectedRow
}
