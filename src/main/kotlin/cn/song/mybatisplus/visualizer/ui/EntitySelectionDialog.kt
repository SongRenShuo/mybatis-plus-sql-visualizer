package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.psi.EntityClassFinder
import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent
import com.intellij.ide.util.gotoByName.GotoClassModel2
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiClass
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import cn.song.mybatisplus.visualizer.util.IconScaler
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 实体类选择弹窗（SQL→Java 反向转换手动指定实体）。
 *
 * 自动推断（类名推断 + @TableName 反查）未命中或多表 JOIN 时，按 SQL 涉及的表逐行列出
 * （主表 + JOIN 副表），每行可从项目实体中指定一个实体（借助实体做准确的列→字段 lambda 映射）：
 *  - 自动推断候选非空 → 预选第一个（用户直接确认即用自动结果）；
 *  - 候选为空 → 行内空置，点「选择…」弹 Goto Class 选择器从项目全部 Java 类中挑选；
 *  - 每行可独立清除（该表回落字符串列名）；
 *  - 确定 → [onConfirm] 回调（键 = 表行 [key] → 选中实体，未指定为 null）；
 *    取消 → 不回调（调用方自行回落）。
 *
 * 构造只做纯 UI 渲染（表行 label/候选类名均为纯数据），不触碰 PSI（T-M23.8 原则）。
 */
class EntitySelectionDialog(
    private val project: Project,
    private val rows: List<TableRow>,
    private val onConfirm: (Map<String, PsiClass?>) -> Unit,
) : DialogWrapper(project, true) {

    /** 一行 = SQL 涉及的一张表（主表或 JOIN 副表）。 */
    data class TableRow(
        /** 结果键：主表固定 [MAIN_KEY]，副表为 join 别名。 */
        val key: String,
        /** 行首展示文本（表名/别名，调用方预组装，如「主表 sys_account_role_rel」）。 */
        val label: String,
        /** 自动推断候选（可能为空——行内空置，用户手动挑选）。 */
        val candidates: List<PsiClass>,
        /**
         * SQL 表名（T-JarEmbedded 方案 2 用）：点「在依赖库中查找」后按它重新解析候选；
         * null = 该行不参与重解析（纯展示行）。
         */
        val tableName: String? = null,
    )

    /** 行集合（「在依赖库中查找」后就地替换并重建 UI）。 */
    private val currentRows: MutableList<TableRow> = rows.toMutableList()

    /** 行容器（重建 UI 用）。 */
    private var rowsPanel: JPanel? = null

    /** 「在依赖库中查找」按钮（扫描期禁用，防重复投递）。 */
    private var scanButton: JButton? = null

    /** 每行当前选择（key → 实体或 null=未指定）。 */
    private val selections = mutableMapOf<String, PsiClass?>()

    /** 行 → 已选显示 label（刷新用；测试访问器）。 */
    private val selectionLabels = mutableMapOf<String, JBLabel>()

    init {
        title = MpSqlVisualizerBundle.message("dialog.selectEntity.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        rowsPanel = panel
        for (row in currentRows) {
            panel.add(buildRow(row))
            selections[row.key] = row.candidates.firstOrNull()
        }
        val content = JPanel(BorderLayout())
        // T-DarkTheme：滚动容器用平台 JBScrollPane。自建原生 JScrollPane 的 viewport 是裸 JViewport、
        // 四角无 Corner 组件；平台 JBScrollPane 用 JBViewport（背景取所载 view）+ 四角 Corner。
        content.add(
            JBScrollPane(panel).apply {
                preferredSize = Dimension(
                    DIALOG_WIDTH,
                    (currentRows.size * ROW_HEIGHT + DIALOG_PADDING).coerceAtMost(DIALOG_MAX_HEIGHT),
                )
            },
            BorderLayout.CENTER,
        )
        content.add(buildScanBar(), BorderLayout.SOUTH)
        return content
    }

    /**
     * 底部「在依赖库中查找」栏（T-JarEmbedded 优化方案 2）。
     *
     * 为什么要有它：按表名找实体时，**依赖库**里的实体若类名与表名约定不符（`LegacyBooking` ↔
     * `t_booking`），只能靠 `@TableName` 认出——那需要扫全库类。插件默认把这次扫描放到后台
     * （每项目版本一次，见设置项「后台扫描依赖库以反查实体」）；关掉后台扫描、或想立刻要结果时，
     * 用户在这里点一下**显式触发**同一趟扫描，扫完就地刷新各表候选。
     */
    private fun buildScanBar(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        isOpaque = false
        val button = JButton(MpSqlVisualizerBundle.message("dialog.selectEntity.scanLibraries")).apply {
            toolTipText = MpSqlVisualizerBundle.message("dialog.selectEntity.scanLibraries.tip")
            addActionListener { scanLibrariesAndRefresh() }
        }
        scanButton = button
        add(button)
    }

    /** 显式触发一次「依赖库全库扫描」并刷新各表候选（BGT 扫描 → EDT 重建 UI）。 */
    private fun scanLibrariesAndRefresh() {
        if (currentRows.none { it.tableName != null }) return
        scanButton?.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val refreshed = runCatching { refreshCandidatesFromLibraries() }.getOrNull()
            ApplicationManager.getApplication().invokeLater(
                {
                    scanButton?.isEnabled = true
                    if (refreshed != null) {
                        currentRows.clear()
                        currentRows.addAll(refreshed)
                        rebuildRows()
                    }
                },
                ModalityState.any(),
            )
        }
    }

    /**
     * 「扫描依赖库 + 按新候选重解析各表」的**同步内核**（按钮路径与测试共用）。
     *
     * 抽出来是为了可测：按钮那条路是 BGT + EDT 异步，测试直接调本方法就能确定性断言「扫完之后
     * 候选确实变了」。**本方法不要在外面包 `ReadAction`**——`runLibrarySweepNow` 自带分片读动作
     * （外层再包一层会把读锁变回整趟持有，把打字冻住，见其 KDoc）；只有重解析那步需要读动作。
     */
    internal fun refreshCandidatesFromLibraries(): List<TableRow> {
        EntityClassFinder(project).runLibrarySweepNow()
        return ReadAction.compute<List<TableRow>, RuntimeException> { resolveCandidatesFromFinder() }
    }

    /** 按**当前**实体反查结果（含库扫描缓存）重解析各表候选（须在 ReadAction 内调用）。 */
    internal fun resolveCandidatesFromFinder(): List<TableRow> = currentRows.map { row ->
        val table = row.tableName ?: return@map row
        row.copy(candidates = SqlToJavaConverter.findCandidates(project, table).map { it.psiClass })
    }

    /** 重建行 UI（保留用户已选：`selections` 不重置，由 [buildRow] → [refreshRow] 按既有选择渲染）。 */
    private fun rebuildRows() {
        val panel = rowsPanel ?: return
        panel.removeAll()
        for (row in currentRows) {
            panel.add(buildRow(row))
        }
        panel.revalidate()
        panel.repaint()
    }

    /** 单行：表名 label + 已选实体显示 + 选择… + 清除。 */
    private fun buildRow(row: TableRow): JPanel {
        val selectedLabel = JBLabel(" ")
        selectionLabels[row.key] = selectedLabel
        val rowPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply { isOpaque = false }
        rowPanel.add(JBLabel(row.label).apply { toolTipText = row.label })
        rowPanel.add(selectedLabel)
        rowPanel.add(JButton(MpSqlVisualizerBundle.message("dialog.selectEntity.browse")).apply {
            addActionListener { pickClass(row, selectedLabel) }
        })
        rowPanel.add(JButton(CANCEL_ICON).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.empty()
            iconTextGap = 0
            preferredSize = Dimension(12, 12)
            minimumSize = Dimension(12, 12)
            maximumSize = Dimension(12, 12)
            toolTipText = MpSqlVisualizerBundle.message("reverse.panel.clearClass")
            addActionListener { clearSelection(row, selectedLabel) }
        })
        refreshRow(row, selectedLabel)
        return rowPanel
    }

    /** 弹 Goto Class 选择器（IDE 标准类搜索弹窗，候选 = 项目内全部 Java 类）。 */
    private fun pickClass(row: TableRow, selectedLabel: JBLabel) {
        val popup = ChooseByNamePopup.createPopup(project, GotoClassModel2(project), null)
        popup.invoke(object : ChooseByNamePopupComponent.Callback() {
            override fun elementChosen(element: Any?) {
                val cls = element as? PsiClass ?: return
                selections[row.key] = cls
                refreshRow(row, selectedLabel)
            }

            override fun onClose() {}
        }, ModalityState.current(), false)
    }

    private fun clearSelection(row: TableRow, selectedLabel: JBLabel) {
        selections[row.key] = null
        refreshRow(row, selectedLabel)
    }

    /** 行显示刷新：有选中 → 类名（tooltip 全限定名）；无 → 灰字占位提示。 */
    private fun refreshRow(row: TableRow, selectedLabel: JBLabel) {
        val selected = selections[row.key]
        if (selected != null) {
            val info = resolveQualifiedNameForTest(selected)
            selectedLabel.text = info?.first ?: "?"
            selectedLabel.toolTipText = info?.second
            selectedLabel.foreground = UIUtil.getLabelForeground()
        } else {
            selectedLabel.text = MpSqlVisualizerBundle.message("dialog.selectEntity.none")
            selectedLabel.toolTipText = null
            selectedLabel.foreground = UIUtil.getInactiveTextColor()
        }
    }

    /**
     * 解析 PsiClass 的显示名与全限定名（测试缝）：抽取 [refreshRow] 中的 PSI 读取逻辑为独立方法，
     * 供测试直接调用（绕过 EDT 回调 + UI 渲染）。
     *
     * T-M44 审计兜底：name/qualifiedName 读取（触发 stub 访问）包在 ReadAction 内。
     *
     * @param psiClass 目标 PsiClass
     * @return Pair(显示名, 全合格名)；name 为 null 时返回 null
     */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    internal fun resolveQualifiedNameForTest(psiClass: PsiClass): Pair<String, String?>? {
        return ReadAction.compute<Pair<String, String?>?, Throwable> {
            val name = psiClass.name ?: return@compute null
            name to psiClass.qualifiedName
        }
    }

    public override fun doOKAction() {
        val result = selections.toMap()
        super.doOKAction()
        onConfirm(result)
    }

    /** 测试访问器：某行当前选中实体（无 → null）。 */
    internal fun selectedForTest(key: String): PsiClass? = selections[key]

    /** 测试访问器：清除某行选择（与 ✕ 按钮同一路径）。 */
    internal fun clearSelectionForTest(key: String) {
        val row = currentRows.firstOrNull { it.key == key } ?: return
        clearSelection(row, selectionLabels[key] ?: JBLabel(" "))
    }

    companion object {
        /** 主表行键（副表行键 = join 别名）。 */
        const val MAIN_KEY = ""

        /** 清除按钮图标（12px 缩放——与面板类选择字段一致）。 */
        private val CANCEL_ICON: Icon = IconScaler.scale(AllIcons.Actions.Cancel, 12f / 16f)

        private const val DIALOG_WIDTH = 560
        private const val ROW_HEIGHT = 40
        private const val DIALOG_PADDING = 20
        private const val DIALOG_MAX_HEIGHT = 360
    }
}
