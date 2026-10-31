package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.clipTailToWidth
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.GridBagLayout
import java.awt.Point
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.border.EmptyBorder

// 扩展函数看不到接收者类的 companion 成员，显式 import 引入：
import cn.song.mybatisplus.visualizer.log.SqlLogPanel.Companion.DROPDOWN_TEXT_BUDGET_PX

// T-M53-II（2026-10-10）同法，D202 第六次登记：SqlLogPanel 的「SQL 类型多选弹窗 / 类型按钮文案」
// 内聚成员，以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。

/** 类型 → 文案（原 SqlLogPanel companion 私有函数，仅本组链路使用，随拆分落为文件级）。 */
internal fun labelForType(type: StatementType): String = when (type) {
    StatementType.SELECT -> MpSqlVisualizerBundle.message("log.filter.select")
    StatementType.INSERT -> MpSqlVisualizerBundle.message("log.filter.insert")
    StatementType.UPDATE -> MpSqlVisualizerBundle.message("log.filter.update")
    StatementType.DELETE -> MpSqlVisualizerBundle.message("log.filter.delete")
    StatementType.CREATE -> MpSqlVisualizerBundle.message("log.filter.create")
    StatementType.DROP -> MpSqlVisualizerBundle.message("log.filter.drop")
    else -> "OTHER"
}

    // ---------- SQL类型多选弹窗 ----------

    /**
     * 更新 SQL类型按钮文案（全部 / 无 / 类型组合）。
     *
     * T-UiCoord F2：标签控件退役后语义由 tooltip 承载——显示文本按预算截尾省略（多选组合可能很长），
     * tooltip 恒为「SQL类型：」+ 完整状态。
     */
    internal fun SqlLogPanel.updateTypeFilterButton() {
        val state = when {
            activeFilter.containsAll(FILTERABLE_TYPES) -> MpSqlVisualizerBundle.message("log.filter.all")
            activeFilter.isEmpty() -> MpSqlVisualizerBundle.message("log.filter.type.none")
            else -> FILTERABLE_TYPES.filter { it in activeFilter }.joinToString(", ") { labelForType(it) }
        }
        typeFilterButton.text = clipToDropdownBudget(state, typeFilterButton)
        typeFilterButton.toolTipText = MpSqlVisualizerBundle.message("log.filter.type.label") + state
    }

    /**
     * T-UiCoord F2：下拉按钮显示文本按预算截尾省略（复用 meta 行的 [clipTailToWidth] 纯函数）。
     * 预算用 JBUI 缩放表达——文本宽度随缩放变化，写死像素会在高 DPI 下失效。
     * tooltip 恒全文，省略只发生在按钮显示层。
     */
    internal fun SqlLogPanel.clipToDropdownBudget(text: String, target: JComponent): String {
        val metrics = target.getFontMetrics(target.font)
        return clipTailToWidth(text, { metrics.stringWidth(it) }, JBUI.scale(DROPDOWN_TEXT_BUDGET_PX))
    }

    /**
     * T-SelToggle：弹窗切换按钮文案——activeFilter 全勾 → 「全不选」（点击清空），
     * 其余态（含空集）→ 「全选」（点击填满）。判定与外层「全部」文案（updateTypeFilterButton）同口径。
     */
    internal fun SqlLogPanel.typePopupToggleLabel(): String = MpSqlVisualizerBundle.message(
        if (activeFilter.containsAll(FILTERABLE_TYPES)) "log.filter.selectNone" else "log.filter.selectAll",
    )

    /**
     * T-SelToggle：弹窗切换按钮文案随勾选态刷新。弹窗无公共渲染下游可收口
     * （updateTypeFilterButton/refreshList 还被搜索等非弹窗链路调用），三个勾选态变更入口
     * （弹窗构建末尾、applyAllTypes 末尾、单类型复选框 listener）显式调用本函数。
     */
    internal fun SqlLogPanel.syncTypePopupToggleLabel() {
        typePopupToggleButton?.let {
            val label = typePopupToggleLabel()
            if (it.text != label) it.text = label
        }
    }

    /**
     * 弹出类型多选弹窗：勾选即时生效且弹窗不关闭（JCheckBox 非 menu item，
     * 点击不触发 popup 收起，支持连续勾选组合）；底部单个全选/全不选切换按钮（T-SelToggle）
     * 一键设置——同步刷新弹窗内勾选框选中态 + 按钮文案 + 列表（弹窗保持打开，立即可见）。
     */
    internal fun SqlLogPanel.showTypeFilterPopup() {
        // T-UiNative P1a：JPopupMenu 外壳 → 平台 JBPopup（createComponentPopupBuilder 承载 JCheckBox 面板；
        // 触发源是工具栏按钮 ActionListener 无 MouseEvent，锚点沿用原 show 坐标——按钮正下方 2px）
        val contentPanel = buildTypeFilterContent()
        JBPopupFactory.getInstance().createComponentPopupBuilder(contentPanel, contentPanel).apply {
            setRequestFocus(false)
            setCancelOnClickOutside(true)
        }.createPopup().show(RelativePoint(typeFilterButton, Point(0, typeFilterButton.height + 2)))
    }

    /**
     * T-SelToggle：构建弹窗内容（勾选框区 + 分隔线 + 底部切换按钮），赋值 [typePopupCheckBoxes]
     * 与 [typePopupToggleButton]。与 [showTypeFilterPopup] 拆分是纯提取（行为零变更），
     * 使测试可脱离 `popup.show` 直接驱动勾选框/切换按钮（headless 下弹层 show 不可靠）。
     */
    internal fun SqlLogPanel.buildTypeFilterContent(): JPanel {
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        content.border = EmptyBorder(8, 12, 8, 12)
        val checkBoxes = mutableListOf<Pair<StatementType, JCheckBox>>()
        FILTERABLE_TYPES.forEach { type ->
            val cb = JCheckBox(labelForType(type), type in activeFilter).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                isFocusable = false
                addActionListener {
                    if (isSelected) activeFilter.add(type) else activeFilter.remove(type)
                    updateTypeFilterButton()
                    refreshList()
                    // T-SelToggle：单类型勾选即时同步切换按钮文案
                    syncTypePopupToggleLabel()
                }
            }
            checkBoxes += type to cb
            // T-CrudColor：复选框前置色块图例——否则用户得去设置页才能把颜色和类型对上号。
            // 样式为 off（或该类型无标记色）时不加色块，避免出现「有图例却无标记」的错位。
            // 包一层行面板保持 checkBoxes 结构与 applyAllTypes 语义不变。
            val tint = SqlListTypeColor.colorOf(type)
            content.add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, cb.preferredSize.height + 2)
                if (tint != null) add(JLabel(colorSwatchIcon(tint)))
                add(cb)
            })
        }
        typePopupCheckBoxes = checkBoxes
        content.add(Box.createVerticalStrut(6))
        content.add(JSeparator().apply { alignmentX = Component.LEFT_ALIGNMENT })
        content.add(Box.createVerticalStrut(6))
        // 用户 2026-10-14 补：与「慢查询徽章」呼应的快捷筛选——只看慢查询（按当前慢查询阈值判定）。
        // 放在类型弹窗内、与类型勾选同一入口，避免往右侧控制组加控件（控制组有最坏宽预算锁）。
        val slowOnlyBox = JCheckBox(MpSqlVisualizerBundle.message("log.filter.slowOnly"), slowOnly).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            isFocusable = false
            toolTipText = MpSqlVisualizerBundle.message("log.filter.slowOnly.tip")
            addActionListener {
                slowOnly = isSelected
                refreshList()
            }
        }
        slowOnlyCheckBox = slowOnlyBox
        content.add(slowOnlyBox)
        content.add(Box.createVerticalStrut(6))
        val toggleButton = JButton(typePopupToggleLabel()).apply {
            isFocusable = false
            addActionListener { applyAllTypes(!activeFilter.containsAll(FILTERABLE_TYPES)) }
        }
        typePopupToggleButton = toggleButton
        val row = JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(toggleButton)
        }
        content.add(row)
        return content
    }

    /**
     * T-CrudColor：类型色块图例图标（12×12 实心方块）。
     * 用于类型过滤弹窗——纯色块、无边框，与列表中的标记同色，起对照作用。
     */
    internal fun SqlLogPanel.colorSwatchIcon(color: Color): Icon = object : Icon {
        override fun getIconWidth(): Int = 12
        override fun getIconHeight(): Int = 12
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            g.color = color
            g.fillRect(x, y, iconWidth, iconHeight)
        }
    }

    /** 全选/全不选（全选 = 「全部」语义，列表透传含 OTHER）：同步弹窗内勾选框 + 按钮文案 + 列表。 */
    internal fun SqlLogPanel.applyAllTypes(selected: Boolean) {
        activeFilter.clear()
        if (selected) activeFilter.addAll(FILTERABLE_TYPES)
        typePopupCheckBoxes.forEach { (type, cb) -> cb.isSelected = type in activeFilter }
        updateTypeFilterButton()
        refreshList()
        // T-SelToggle：切换按钮点击后文案翻转（此时 activeFilter 已是新态）
        syncTypePopupToggleLabel()
    }
