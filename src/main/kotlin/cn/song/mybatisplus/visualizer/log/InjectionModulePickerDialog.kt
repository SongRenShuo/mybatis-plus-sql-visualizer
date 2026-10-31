package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * T-ModPick：多模块工程注入前的模块勾选弹框。
 *
 * 检测到多个支持模块时不再全量注入（用户反馈：应让用户选择而非全部生成）——
 * 逐模块列勾选框（模块名 + 基础包 + 旧版待升级标注），默认全选，附全选/全不选；
 * 未勾选任何模块时确定按钮禁用（[setOKActionEnabled]，243 javap 实证 API）。
 *
 * 仅承载纯展示与选择态：候选清单由调用方（检测阶段产物）预组装，本弹框不触碰 PSI/VFS
 * （家规「UI 只做编排 + 纯数据渲染」，同 [EntitySelectionDialog] 原则）。
 */
class InjectionModulePickerDialog(
    project: Project,
    /** 候选行：[key]（模块名，过滤键）+ 展示文本 + 是否旧版待升级。 */
    private val rows: List<Row>,
) : DialogWrapper(project, true) {

    /** 一个候选模块行。 */
    data class Row(val key: String, val label: String, val upgradable: Boolean)

    private val checkBoxes = mutableMapOf<String, JBCheckBox>()

    init {
        title = MpSqlVisualizerBundle.message("log.inject.pick.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        for (row in rows) {
            val cb = JBCheckBox(row.label, true)
            cb.addItemListener { setOKActionEnabled(selectedKeys().isNotEmpty()) }
            checkBoxes[row.key] = cb
            val line = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 4)
                add(cb, BorderLayout.WEST)
                if (row.upgradable) {
                    add(
                        JBLabel(MpSqlVisualizerBundle.message("log.inject.pick.upgradeTag")).apply {
                            foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                            border = JBUI.Borders.emptyLeft(8)
                        },
                        BorderLayout.EAST,
                    )
                }
            }
            list.add(line)
        }
        // T-DarkTheme：滚动容器用平台 JBScrollPane。自建原生 JScrollPane 的 viewport 是裸 JViewport、
        // 四角无 Corner 组件；平台 JBScrollPane 用 JBViewport（背景取所载 view）+ 四角 Corner。
        val scroll = JBScrollPane(list).apply {
            preferredSize = Dimension(DIALOG_WIDTH, (rows.size * ROW_HEIGHT + 16).coerceAtMost(MAX_HEIGHT))
            border = JBUI.Borders.empty()
        }
        val selectAll = JButton(MpSqlVisualizerBundle.message("log.inject.pick.selectAll"))
        val selectNone = JButton(MpSqlVisualizerBundle.message("log.inject.pick.selectNone"))
        selectAll.addActionListener { setAllSelected(true) }
        selectNone.addActionListener { setAllSelected(false) }
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(selectAll)
            add(selectNone)
            add(Box.createHorizontalGlue())
        }
        return JPanel(BorderLayout()).apply {
            add(JBLabel(MpSqlVisualizerBundle.message("log.inject.pick.message")), BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            add(toolbar, BorderLayout.SOUTH)
            border = JBUI.Borders.empty(4)
        }
    }

    private fun setAllSelected(selected: Boolean) {
        checkBoxes.values.forEach { it.isSelected = selected }
    }

    /** 勾选的模块键集合（测试缝与 doOKAction 共用）。 */
    internal fun selectedKeys(): Set<String> = checkBoxes.filterValues { it.isSelected }.keys

    /** 测试缝：不经 show() 直取中心面板（同 ShareDialog.centerPanelForTest 惯例）。 */
    internal fun centerPanelForTest(): JComponent = createCenterPanel()

    companion object {
        private const val DIALOG_WIDTH = 480
        private const val ROW_HEIGHT = 28
        private const val MAX_HEIGHT = 320
    }
}
