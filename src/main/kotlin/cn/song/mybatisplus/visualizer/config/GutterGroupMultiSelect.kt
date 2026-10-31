package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Point
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.border.EmptyBorder

/**
 * 6 个 gutter 来源分组开关的**纯逻辑面**（无 Swing、无平台依赖，可直接单测）。
 *
 * 顺序与 [cn.song.mybatisplus.visualizer.ui.gutter.GutterGroups.GROUPS] 一致：
 * mapperXml / annotation / jpaHql / jdbc / inlineFallback / other。
 *
 * 存储层零改动：面板控件只是这 6 个布尔的可视化聚合，写回仍逐个字段；
 * 本对象是「控件勾选态 ⇄ State 6 字段」的唯一映射点（测试往返与互不影响即直接打这里）。
 */
internal object GutterGroupSwitches {

    /** 分组数（= 6 个 State 布尔）。 */
    const val COUNT = 6

    /** 6 个分组的文案键，顺序同上。 */
    val TEXT_KEYS: List<String> = listOf(
        "settings.gutterGroup.mapperXml",
        "settings.gutterGroup.annotation",
        "settings.gutterGroup.jpaHql",
        "settings.gutterGroup.jdbc",
        "settings.gutterGroup.inlineFallback",
        "settings.gutterGroup.other",
    )

    /** State → 勾选态（读取顺序与 [TEXT_KEYS] 一致）。 */
    fun flagsFrom(s: PluginSettings.State): List<Boolean> = listOf(
        s.gutterGroupMapperXml,
        s.gutterGroupAnnotation,
        s.gutterGroupJpaHql,
        s.gutterGroupJdbc,
        s.gutterGroupInlineFallback,
        s.gutterGroupOther,
    )

    /** 勾选态 → State（逐字段写回，不触碰其余任何字段）。 */
    fun applyTo(s: PluginSettings.State, flags: List<Boolean>) {
        require(flags.size == COUNT) { "gutter 分组勾选态应为 $COUNT 项，实际 ${flags.size}" }
        s.gutterGroupMapperXml = flags[0]
        s.gutterGroupAnnotation = flags[1]
        s.gutterGroupJpaHql = flags[2]
        s.gutterGroupJdbc = flags[3]
        s.gutterGroupInlineFallback = flags[4]
        s.gutterGroupOther = flags[5]
    }

    /** 与 State 不一致即为已修改（apply 按钮启用判据的一部分）。 */
    fun isModified(s: PluginSettings.State, flags: List<Boolean>): Boolean = flagsFrom(s) != flags

    fun allSelected(flags: List<Boolean>): Boolean = flags.all { it }

    fun noneSelected(flags: List<Boolean>): Boolean = flags.none { it }

    /** 摘要文案键：全选 / 全不选 / 部分（后两者带 [summaryArgs]）。 */
    fun summaryKey(flags: List<Boolean>): String = when {
        allSelected(flags) -> "settings.gutterGroup.summary.all"
        noneSelected(flags) -> "settings.gutterGroup.summary.none"
        else -> "settings.gutterGroup.summary.partial"
    }

    /** 摘要文案参数（只在 partial 态有意义：已启用数 / 总数）。 */
    fun summaryArgs(flags: List<Boolean>): Array<Any> = arrayOf<Any>(flags.count { it }, COUNT)

    /** 全选/全不选切换按钮文案键：全勾时提示「全不选」，否则「全选」。 */
    fun toggleKey(flags: List<Boolean>): String =
        if (allSelected(flags)) "settings.gutterGroup.selectNone" else "settings.gutterGroup.selectAll"

    /** 切换目标值：未全勾 → 全勾；已全勾 → 全不勾。 */
    fun toggleTarget(flags: List<Boolean>): Boolean = !allSelected(flags)

    /** 摘要文案（把 key + 参数拼好，供按钮 text/tooltip 与测试共用）。 */
    fun summaryText(flags: List<Boolean>): String {
        val key = summaryKey(flags)
        return if (key == "settings.gutterGroup.summary.partial") {
            MpSqlVisualizerBundle.message(key, *summaryArgs(flags))
        } else {
            MpSqlVisualizerBundle.message(key)
        }
    }
}

/**
 * 设置页单行呈现的「gutter 来源分组」多选控件（T-SettingsConverge 阶段 2）。
 *
 * 形态照抄本仓既有同类多选实现 `log/SqlLogPanelTypeFilter.kt`：
 * 行内一个摘要按钮（显示「全部 / 无 / 已启用 n/6」），点击弹出 **JBPopup** 浮层，
 * 浮层内 6 个 JCheckBox（勾选即时生效、不关闭浮层）+ 分隔线 + 底部「全选/全不选」切换按钮。
 * 浮层非模态（零模态弹窗红线），点击外部即收起。
 *
 * 存储层不动：控件只聚合展示，[GutterGroupSwitches] 负责与 6 个 State 布尔互转。
 */
internal class GutterGroupMultiSelect {

    /** 6 个勾选框（只在浮层里出现，绝不挂到设置页主树上）。 */
    private val checkBoxes: List<JBCheckBox> = GutterGroupSwitches.TEXT_KEYS.map { key ->
        JBCheckBox(MpSqlVisualizerBundle.message(key)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            isFocusable = false
        }
    }

    private val summaryButton = JButton().apply {
        isFocusable = false
        addActionListener { showPopup() }
    }

    /** 行内标签（与既有 switch 行同形；文本进搜索索引，用户搜「gutter / 来源 / 分组」可命中）。 */
    private val rowLabel = JBLabel(MpSqlVisualizerBundle.message("settings.gutterGroup.aggregate"))

    /**
     * 设置页里真正加进行的组件：标签 + 摘要按钮，一行呈现。
     *
     * 标签内嵌在本组件里而非走 [PluginSettingsPanelSections.addRow] 的首列标签位——「界面与操作」
     * 节其余行首列全为空，若这里占出首列标签宽度，`sectionGroup` 的 `columnWidths` 会把这个
     * 宽度套给整节，导致节内所有开关整体右移。
     */
    val component: JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        isOpaque = false
        add(rowLabel)
        add(summaryButton)
    }

    init {
        // 浮层内勾选变化 → 刷新摘要按钮文案（设置页行内即时可见）
        checkBoxes.forEach { cb -> cb.addItemListener { refreshSummary() } }
        refreshTexts()
    }

    /** 当前勾选态（顺序与 [GutterGroupSwitches.TEXT_KEYS] 一致）。 */
    fun selectedFlags(): List<Boolean> = checkBoxes.map { it.isSelected }

    /** 按给定勾选态回填控件（reset 用）。 */
    fun setFlags(flags: List<Boolean>) {
        require(flags.size == checkBoxes.size) { "gutter 分组勾选态应为 ${checkBoxes.size} 项，实际 ${flags.size}" }
        checkBoxes.forEachIndexed { i, cb -> cb.isSelected = flags[i] }
        // 勾选态未变化时 item listener 不会触发，这里显式刷一次
        refreshSummary()
    }

    /** 语言切换后刷新全部文案（勾选框 + 行标签 + 摘要按钮 + tooltip）。 */
    fun refreshTexts() {
        checkBoxes.forEachIndexed { i, cb -> cb.text = MpSqlVisualizerBundle.message(GutterGroupSwitches.TEXT_KEYS[i]) }
        rowLabel.text = MpSqlVisualizerBundle.message("settings.gutterGroup.aggregate")
        refreshSummary()
    }

    private fun refreshSummary() {
        summaryButton.text = GutterGroupSwitches.summaryText(selectedFlags())
        summaryButton.toolTipText = MpSqlVisualizerBundle.message("settings.gutterGroup.aggregate.tip")
    }

    private fun showPopup() {
        val content = buildPopupContent()
        JBPopupFactory.getInstance().createComponentPopupBuilder(content, content).apply {
            setRequestFocus(false)
            setCancelOnClickOutside(true)
        }.createPopup().show(RelativePoint(summaryButton, Point(0, summaryButton.height + 2)))
    }

    /**
     * 构建浮层内容（每次弹出重建；六个勾选框是字段实例，先在旧父容器上摘掉再挂入，
     * 避免 Swing「一个组件只有一个父容器」的约束在第二次弹出时抛错）。
     */
    internal fun buildPopupContent(): JPanel {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = EmptyBorder(8, 12, 8, 12)
        }
        checkBoxes.forEach { cb ->
            cb.parent?.remove(cb)
            content.add(cb)
        }
        content.add(Box.createVerticalStrut(6))
        content.add(JSeparator().apply { alignmentX = Component.LEFT_ALIGNMENT })
        content.add(Box.createVerticalStrut(6))
        val toggle = JButton(MpSqlVisualizerBundle.message(GutterGroupSwitches.toggleKey(selectedFlags()))).apply {
            isFocusable = false
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener {
                val target = GutterGroupSwitches.toggleTarget(selectedFlags())
                setFlags(List(GutterGroupSwitches.COUNT) { target })
                // 切换后按钮文案翻转（全选 ⇄ 全不选）
                text = MpSqlVisualizerBundle.message(GutterGroupSwitches.toggleKey(selectedFlags()))
            }
        }
        content.add(toggle)
        return content
    }
}
