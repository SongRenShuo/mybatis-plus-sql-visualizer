package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/**
 * 多 SQL 选择弹窗（T-M23.7/8；T-M25-01 泛化支持 XML 语句候选）。
 *
 * 选择代码块后右键、选择区内含**多个** SQL 候选时，弹出列表让用户选择要预览哪一个。
 * 泛型 [T]：Wrapper 链首（[PsiMethodCallExpression]）与 XML mapper 语句（XmlTag）均可用。
 *
 * 列表每项两行展示——第一行摘要（`行号 + Wrapper 类型/方法名 + 表名`），第二行代码行内容；
 * 选中确定 → [onSelect] 回调（调用方负责编辑器定位跳转 + 预览 SQL）。
 *
 * T-CrudSql 原生化改造（用户反馈"样式丑"）：弃用 HTML 字符串渲染，改为 [RowRenderer]
 * 主题色原生双行渲染（JBList + 加粗摘要 + 次要色详情 + 行内边距，明暗主题自适应）+
 * IDEA 原生速度搜索（弹窗内直接打字过滤定位）；双击候选直接确认。
 *
 * [rows] 由调用方在**后台 ReadAction** 中预计算（摘要/行文本含 PSI 解析，EDT 上会被
 * 2025.3+ SlowOperations 守卫禁止）——构造只做纯 UI 渲染，不再触碰 PSI。
 */
class SqlSelectionDialog<T>(
    private val project: Project,
    candidates: List<T>,
    rows: List<Row>,
    private val onSelect: (T) -> Unit,
) : DialogWrapper(project, true) {

    /** 列表行（纯数据，可后台预计算与单测）：加粗摘要行 + 次要色详情行。 */
    data class Row(val summary: String, val detail: String) {
        companion object {
            /** 工厂：详情超 [SqlSelectionDialog.MAX_LINE_LENGTH] 截断加省略号（纯文本渲染，无需 HTML 转义）。 */
            fun of(summary: String, detail: String): Row =
                Row(summary, if (detail.length > MAX_LINE_LENGTH) detail.take(MAX_LINE_LENGTH) + "…" else detail)
        }
    }

    /** 候选 SQL（与 [rows] 索引序一一对应）。 */
    private val candidates: List<T> = candidates

    private val list = JBList(rows).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RowRenderer()
        // IDEA 原生速度搜索：弹窗内直接打字按摘要/详情过滤定位（Recent Files 同款体验）
        TreeUIHelper.getInstance().installListSpeedSearch(this)
        // 双击候选直接确认（对齐 IDEA 原生弹窗交互），不必移步 OK
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val idx = locationToIndex(e.point)
                    if (idx >= 0) {
                        selectedIndex = idx
                        doOKAction()
                    }
                }
            }
        })
    }

    init {
        title = MpSqlVisualizerBundle.message("dialog.selectSql.title")
        list.selectedIndex = 0
        init()
    }

    // T-DarkTheme：滚动容器必须用平台 JBScrollPane（同 T-InsightDarkThemeFix 先例）。
    // 自建原生 JScrollPane 的 viewport 是裸 JViewport、四角没有 Corner 组件（横竖滚动条交汇处
    // 无人绘制）；平台 JBScrollPane 用 JBViewport（背景取所载 view）+ 四角 Corner，
    // 深色主题下容器边缘/角落不会留浅色条带。
    override fun createCenterPanel(): JComponent = JBScrollPane(list).apply {
        border = JBUI.Borders.empty()
        preferredSize = Dimension(DIALOG_WIDTH, DIALOG_HEIGHT)
        minimumSize = Dimension(DIALOG_MIN_WIDTH, DIALOG_MIN_HEIGHT)
    }

    public override fun doOKAction() {
        val selected = list.selectedIndex
        if (selected in candidates.indices) {
            onSelect(candidates[selected])
        }
        super.doOKAction()
    }

    /** 测试访问器（断言渲染模型与预计算行一致，仿 panelForTest）。 */
    internal fun listForTest(): JList<Row> = list

    /**
     * 列表行渲染器：原生主题色双行（选中态随平台列表配色，明暗主题自适应）。
     * 详情行禁用 HTML 解析（[BasicHTML.propertyKey]）——代码行可能以 `<` 开头（如 XML 片段）。
     */
    private class RowRenderer : JPanel(BorderLayout()), ListCellRenderer<Row> {
        private val summaryLabel = JLabel()
        private val detailLabel = JLabel()

        init {
            isOpaque = true
            border = JBUI.Borders.empty(6, 12)
            summaryLabel.font = summaryLabel.font.deriveFont(Font.BOLD)
            // 详情行禁用 HTML 解析（Swing 约定键 "html.disable"，BasicHTML.propertyKey 是 View 缓存键勿用）——
            // 代码行可能以 `<` 开头（如 XML 片段），纯文本渲染
            detailLabel.putClientProperty("html.disable", true)
            detailLabel.foreground = UIUtil.getContextHelpForeground()
            add(summaryLabel, BorderLayout.NORTH)
            add(detailLabel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out Row>,
            value: Row,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ): Component {
            summaryLabel.text = value.summary
            detailLabel.text = value.detail
            background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            summaryLabel.foreground = UIUtil.getListForeground()
            detailLabel.foreground = if (selected) UIUtil.getListForeground() else UIUtil.getContextHelpForeground()
            return this
        }
    }

    companion object {
        /** 弹窗默认尺寸。 */
        const val DIALOG_WIDTH = 560
        const val DIALOG_HEIGHT = 280
        const val DIALOG_MIN_WIDTH = 420
        const val DIALOG_MIN_HEIGHT = 160

        /** 代码行预览最大长度（字符；超出截断加省略号，防撑爆弹窗宽度）。 */
        const val MAX_LINE_LENGTH = 80
    }
}


/**
 * 实体候选副标题：完整限定类名——副标题只放文件名时，不同包/模块的同名实体
 * （如多模块 demo 里两处 `User.java`）完全无法区分（20261011 录制实测）。
 * qualifiedName 不可得时回退 `包路径/文件名`，再不行退文件名。
 */
internal fun entityCandidateSubtitle(psiClass: PsiClass): String {
    psiClass.qualifiedName?.takeIf { it.isNotBlank() }?.let { return it }
    val file = psiClass.containingFile ?: return ""
    val pkg = (file as? PsiJavaFile)?.packageName?.takeIf { it.isNotBlank() }?.let { "$it." } ?: ""
    return pkg + file.name
}
