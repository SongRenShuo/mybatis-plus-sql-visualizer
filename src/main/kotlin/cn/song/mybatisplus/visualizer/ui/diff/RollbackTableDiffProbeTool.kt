package cn.song.mybatisplus.visualizer.ui.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffToolType
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.table.DefaultTableModel

/**
 * T-RollbackSnapshotPreExec P0 探针（V1）：证明「自写 [FrameDiffTool] 注册 `com.intellij.diff.DiffTool`
 * EP 后能被原生 diff 窗口选中并渲染自定义表格组件」这条路线可行。
 *
 * P3 的正式三件套（RollbackTableDiffRequest/Tool/Viewer）会替换本文件的内部渲染，
 * 保留「Request 载 TableSnapshot → Tool canShow → Viewer 双 JBTable」的骨架。
 *
 * 平台签名证据（classfile 常量池提取，javap 24 读不了 262 v69 字节码）：
 * - 243（app-client.jar）与 262（intellij.platform.diff.jar）的 [FrameDiffTool] 都声明
 *   `createComponent(DiffContext, DiffRequest): DiffViewer`（@RequiresEdt @NotNull）；
 *   `getToolType(): DiffToolType` 带默认实现（有 Code 属性）；262 额外 `getIcon()` 也是默认实现。
 * - [FrameDiffTool.DiffViewer]：getComponent(): JComponent（NotNull）、getPreferredFocusedComponent()
 *   （Nullable）、init(): ToolbarComponents（@RequiresEdt）、dispose()（来自 Disposable）。
 * - `FrameDiffTool.ToolbarComponents` 两版本都有公开无参构造 `()V`。
 * - EP 名＝`com.intellij.diff.DiffTool`（DiffTool.EP_NAME 常量池字符串），EP 扩展排在平台内置
 *   DiffTool 之前（DiffManagerImpl.getDiffTools），canShow 命中即被选中。
 */
internal class RollbackTableDiffProbeRequest(
    val diffTitle: String,
    val columnNames: List<String>,
    val beforeRows: List<List<String>>,
    val afterRows: List<List<String>>,
) : DiffRequest() {
    /** DiffRequest 抽象成员（243/262 classfile 实证）：diff 窗口标题。 */
    override fun getTitle(): String? = diffTitle
}

internal class RollbackTableDiffProbeTool : FrameDiffTool {
    override fun getName(): String = "Rollback Snapshot Table (Probe)"

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean =
        request is RollbackTableDiffProbeRequest

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer =
        RollbackTableDiffProbeViewer(request as RollbackTableDiffProbeRequest)

    override fun getToolType(): DiffToolType = DiffToolType.Default
}

internal class RollbackTableDiffProbeViewer(private val request: RollbackTableDiffProbeRequest) :
    FrameDiffTool.DiffViewer {

    private val root: JComponent = buildComponent()

    private fun buildComponent(): JComponent {
        val beforeTable = javax.swing.JTable(toTableModel(request.beforeRows))
        val afterTable = javax.swing.JTable(toTableModel(request.afterRows))
        val beforePane = titledPane(request.diffTitle, javax.swing.JScrollPane(beforeTable))
        val afterPane = titledPane("after", javax.swing.JScrollPane(afterTable))
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, beforePane, afterPane)
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

    private fun toTableModel(rows: List<List<String>>): DefaultTableModel {
        val model = DefaultTableModel(request.columnNames.toTypedArray(), 0)
        for (row in rows) {
            model.addRow(row.map { it ?: "" }.toTypedArray())
        }
        return model
    }

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent? = null

    override fun init(): FrameDiffTool.ToolbarComponents = FrameDiffTool.ToolbarComponents()

    override fun dispose() {
        LOG.debug("RollbackTableDiffProbeViewer disposed")
    }

    companion object {
        private val LOG = Logger.getInstance(RollbackTableDiffProbeViewer::class.java)
    }
}
