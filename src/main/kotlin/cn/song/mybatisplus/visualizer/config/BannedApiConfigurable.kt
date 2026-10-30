package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.inspection.BannedApiMatcher
import cn.song.mybatisplus.visualizer.inspection.BannedApiRule
import cn.song.mybatisplus.visualizer.inspection.BannedApiRuleManager
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder

/**
 * 「禁用 API」设置子页（FR-0045 第 2 项 T-BannedApiCheck S2）：左规则列表 + 右规则编辑器。
 *
 * 形态照抄仓内既有同类实现 [cn.song.mybatisplus.visualizer.log.SqlSuppressManagerDialog]
 * （「列表 + 编辑器、关闭即生效」，仓内无 JBTable 行编辑先例故不自创表格），差别只在宿主：
 * 本页是设置对话框里的 Configurable（不是 DialogWrapper），故列表/表单直接成为页面内容，
 * **不弹二级对话框**。
 *
 * **关闭即生效**：所有增删改直接写 [BannedApiRuleManager]（持久化服务单一真相源），
 * [isModified]/[apply]/[reset] 全部空实现——避免「页面缓存 + 服务状态」双源。
 *
 * 页面只做表单装配与文案；匹配语义、判重、上限与校验全部在
 * [BannedApiMatcher] / [BannedApiRuleManager]（纯逻辑，可用普通 JUnit 直测）。
 */
class BannedApiConfigurable internal constructor(
    private val ruleManager: () -> BannedApiRuleManager,
) : Configurable {

    /** EP 实例化入口（plugin.xml `instance=`）。 */
    constructor() : this({ BannedApiRuleManager.getInstance() })

    private var panel: JPanel? = null

    private val listModel = CollectionListModel<BannedApiRule>()
    private val ruleList = JBList<BannedApiRule>(listModel)

    private val classField = JBTextField()
    private val methodField = JBTextField()
    private val modeCombo = ComboBox<ModeOption>()
    private val levelCombo = ComboBox<LevelOption>()
    private val descriptionArea = JTextArea(3, 30).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private val saveButton = JButton()
    private val cancelAddButton = JButton().apply { isVisible = false }
    private val statusLabel = JBLabel().apply { foreground = UIUtil.getInactiveTextColor() }

    /** 新增模式：右侧表单可编辑且提交走 addRule；false 时提交走 updateRule（需选中规则）。 */
    private var adding = false

    /** 当前编辑的规则 id（新增模式为 null）。 */
    private var editingId: String? = null

    /** 变更监听器（需在 [disposeUIResources] 注销）。 */
    private var managerListener: (() -> Unit)? = null

    /** 列表行渲染的时间格式化（ThreadLocal 复用，照 SqlSuppressManagerDialog 范式）。 */
    private val createdAtFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm") }

    /** ComboBox 选项必须按值判等（JDK17 `setSelectedItem` 以 equals 查找，身份判等会静默不选中）。 */
    internal data class ModeOption(val regex: Boolean) {
        override fun toString(): String = MpSqlVisualizerBundle.message(
            if (regex) "settings.bannedApi.mode.regex" else "settings.bannedApi.mode.exact",
        )
    }

    internal data class LevelOption(val value: String) {
        override fun toString(): String = MpSqlVisualizerBundle.message(
            if (value == BannedApiRule.LEVEL_ERROR) "settings.bannedApi.level.error"
            else "settings.bannedApi.level.warning",
        )
    }

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("settings.section.bannedApi")

    override fun createComponent(): JComponent {
        val cached = panel
        if (cached != null) return cached

        ruleList.cellRenderer = RuleRowRenderer()
        ruleList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        ruleList.addListSelectionListener { event ->
            if (event.valueIsAdjusting || adding) return@addListSelectionListener
            loadSelectedRule()
        }

        modeCombo.model = javax.swing.DefaultComboBoxModel(arrayOf(ModeOption(false), ModeOption(true)))
        levelCombo.model = javax.swing.DefaultComboBoxModel(arrayOf(LevelOption(BannedApiRule.LEVEL_WARNING), LevelOption(BannedApiRule.LEVEL_ERROR)))

        saveButton.addActionListener { onSaveClicked() }
        cancelAddButton.text = MpSqlVisualizerBundle.message("settings.bannedApi.cancelAdd")
        cancelAddButton.addActionListener { exitAddMode(restoreSelection = true) }

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            add(JButton(MpSqlVisualizerBundle.message("settings.bannedApi.add")).apply {
                isFocusable = false
                addActionListener { startAdd() }
            })
            add(JButton(MpSqlVisualizerBundle.message("settings.bannedApi.delete")).apply {
                isFocusable = false
                addActionListener { deleteSelected() }
            })
            add(JButton(MpSqlVisualizerBundle.message("settings.bannedApi.clear")).apply {
                isFocusable = false
                addActionListener { clearAll() }
            })
        }
        val leftPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(ruleList), BorderLayout.CENTER)
            add(buttonRow, BorderLayout.SOUTH)
        }

        val descriptionScroll = JBScrollPane(descriptionArea).apply {
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(90))
        }
        val formPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel(MpSqlVisualizerBundle.message("settings.bannedApi.field.className")),
                classField,
            )
            .addComponentToRightColumn(
                JBLabel(MpSqlVisualizerBundle.message("settings.bannedApi.field.className.tip")).apply {
                    foreground = MpvsUiColors.META_GRAY
                },
                1,
            )
            .addLabeledComponent(
                JBLabel(MpSqlVisualizerBundle.message("settings.bannedApi.field.mode")),
                modeCombo,
            )
            .addLabeledComponent(
                JBLabel(MpSqlVisualizerBundle.message("settings.bannedApi.field.methodName")),
                methodField,
            )
            .addComponentToRightColumn(
                JBLabel(MpSqlVisualizerBundle.message("settings.bannedApi.field.methodName.tip")).apply {
                    foreground = MpvsUiColors.META_GRAY
                },
                1,
            )
            .addLabeledComponent(
                JBLabel(MpSqlVisualizerBundle.message("settings.bannedApi.field.level")),
                levelCombo,
            )
            .addLabeledComponent(
                JBLabel(MpSqlVisualizerBundle.message("settings.bannedApi.field.description")),
                descriptionScroll,
            )
            .addComponentToRightColumn(
                JBLabel(MpSqlVisualizerBundle.message("settings.bannedApi.field.description.tip")).apply {
                    foreground = MpvsUiColors.META_GRAY
                },
                1,
            )
            .addComponent(
                JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    add(saveButton)
                    add(cancelAddButton)
                },
            )
            .panel

        val rightPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(formPanel), BorderLayout.CENTER)
        }

        val splitter = Splitter(false, 0.42f).apply {
            firstComponent = leftPanel
            secondComponent = rightPanel
            minimumSize = Dimension(JBUI.scale(360), JBUI.scale(220))
        }

        val intro = JBLabel(
            "<html><table width='${JBUI.scale(560)}'><tr><td>" +
                MpSqlVisualizerBundle.message("settings.bannedApi.intro") +
                "</td></tr></table></html>",
        ).apply { border = JBUI.Borders.empty(0, 0, 6, 0) }

        val root = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 0)
            add(intro, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        panel = root

        // 变更监听：规则被别处（另一设置窗口 / 测试）改动时刷新列表
        val listener: () -> Unit = { refreshList(selectId = editingId) }
        ruleManager().addListener(listener)
        managerListener = listener

        refreshList(selectId = null)
        return root
    }

    // ---------- 列表 ----------

    /**
     * 规则行渲染器：两行形态（目标行在上、级别 + 说明在下），与日志页/屏蔽规则对话框同款
     * （SimpleColoredComponent 片段通道 + 行底分隔线 + 列表选中态着色）。
     */
    private inner class RuleRowRenderer :
        JPanel(BorderLayout()),
        ListCellRenderer<BannedApiRule> {

        private val targetLine = SimpleColoredComponent()
        private val metaLine = SimpleColoredComponent()
        private val linesPanel = JPanel(GridLayout(0, 1)).apply { isOpaque = false }

        init {
            isOpaque = true
            targetLine.isOpaque = false
            metaLine.isOpaque = false
            linesPanel.add(targetLine)
            linesPanel.add(metaLine)
            add(linesPanel, BorderLayout.CENTER)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, javax.swing.UIManager.getColor("Component.borderColor") ?: java.awt.Color.LIGHT_GRAY),
                EmptyBorder(4, 8, 4, 8),
            )
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out BannedApiRule>,
            value: BannedApiRule,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ): Component {
            background = if (selected) list.selectionBackground else list.background
            val fg = if (selected) list.selectionForeground else list.foreground
            targetLine.foreground = fg
            targetLine.clear()
            targetLine.append(value.targetSummary(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)

            metaLine.foreground = if (selected) fg else UIUtil.getInactiveTextColor()
            metaLine.clear()
            metaLine.append(
                MpSqlVisualizerBundle.message(
                    if (value.isError()) "settings.bannedApi.level.error" else "settings.bannedApi.level.warning",
                ),
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            )
            if (value.createdAt > 0) {
                metaLine.append("  ·  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                metaLine.append(createdAtText(value.createdAt), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            metaLine.append("  ·  " + value.description, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            return this
        }
    }

    private fun createdAtText(createdAt: Long): String =
        createdAtFormat.get()?.format(Date(createdAt)) ?: ""

    /** 列表按 manager 当前规则重建；[selectId] 非 null 时按 id 选中。 */
    private fun refreshList(selectId: String?) {
        val rules = ruleManager().rules
        val previousId = selectId ?: editingId
        listModel.replaceAll(rules)
        val target = previousId?.let { id -> rules.firstOrNull { it.id == id } }
        if (target != null) {
            ruleList.setSelectedValue(target, false)
        } else {
            ruleList.clearSelection()
            if (!adding) clearForm()
        }
        if (rules.isEmpty() && !adding) {
            statusLabel.text = MpSqlVisualizerBundle.message("settings.bannedApi.empty")
        } else if (rules.isEmpty()) {
            statusLabel.text = MpSqlVisualizerBundle.message("settings.bannedApi.status.addHint")
        }
    }

    // ---------- 表单 ----------

    private fun loadSelectedRule() {
        val rule = ruleList.selectedValue ?: run { clearForm(); return }
        editingId = rule.id
        classField.text = rule.className
        methodField.text = rule.methodName
        modeCombo.selectedItem = ModeOption(rule.classRegex)
        levelCombo.selectedItem = LevelOption(if (rule.level in BannedApiRule.LEVELS) rule.level else BannedApiRule.LEVEL_WARNING)
        descriptionArea.text = rule.description
        saveButton.text = MpSqlVisualizerBundle.message("settings.bannedApi.save")
        cancelAddButton.isVisible = false
        statusLabel.text = ""
    }

    private fun clearForm() {
        editingId = null
        classField.text = ""
        methodField.text = ""
        modeCombo.selectedItem = ModeOption(false)
        levelCombo.selectedItem = LevelOption(BannedApiRule.LEVEL_WARNING)
        descriptionArea.text = ""
        saveButton.text = MpSqlVisualizerBundle.message("settings.bannedApi.save")
        cancelAddButton.isVisible = false
    }

    private fun startAdd() {
        adding = true
        ruleList.clearSelection()
        clearForm()
        saveButton.text = MpSqlVisualizerBundle.message("settings.bannedApi.addConfirm")
        cancelAddButton.isVisible = true
        statusLabel.text = MpSqlVisualizerBundle.message("settings.bannedApi.status.addHint")
        classField.requestFocusInWindow()
    }

    private fun exitAddMode(restoreSelection: Boolean) {
        adding = false
        cancelAddButton.isVisible = false
        saveButton.text = MpSqlVisualizerBundle.message("settings.bannedApi.save")
        if (restoreSelection) {
            refreshList(selectId = editingId)
            loadSelectedRule()
        } else {
            clearForm()
        }
    }

    private fun buildRuleFromForm(): BannedApiRule = BannedApiRule(
        id = editingId.orEmpty(),
        className = classField.text,
        classRegex = (modeCombo.selectedItem as? ModeOption)?.regex == true,
        methodName = methodField.text,
        description = descriptionArea.text,
        level = (levelCombo.selectedItem as? LevelOption)?.value ?: BannedApiRule.LEVEL_WARNING,
    )

    private fun onSaveClicked() {
        val rule = buildRuleFromForm()
        val validation = BannedApiMatcher.validate(rule)
        if (validation != null) {
            statusLabel.text = validationMessage(validation)
            return
        }
        if (adding || editingId == null) {
            when (ruleManager().addRule(rule)) {
                BannedApiRuleManager.AddResult.ADDED -> {
                    adding = false
                    cancelAddButton.isVisible = false
                    saveButton.text = MpSqlVisualizerBundle.message("settings.bannedApi.save")
                    val created = ruleManager().rules.lastOrNull {
                        it.className == rule.className.trim() &&
                            it.classRegex == rule.classRegex &&
                            it.methodName == rule.methodName.trim()
                    }
                    refreshList(selectId = created?.id)
                    statusLabel.text = MpSqlVisualizerBundle.message("settings.bannedApi.status.added")
                }
                BannedApiRuleManager.AddResult.DUPLICATE ->
                    statusLabel.text = MpSqlVisualizerBundle.message("settings.bannedApi.status.duplicate")
                BannedApiRuleManager.AddResult.INVALID ->
                    statusLabel.text = MpSqlVisualizerBundle.message("settings.bannedApi.status.invalid")
                BannedApiRuleManager.AddResult.LIMIT_REACHED ->
                    statusLabel.text = MpSqlVisualizerBundle.message(
                        "settings.bannedApi.status.limit",
                        BannedApiRuleManager.MAX_RULES.toString(),
                    )
            }
            return
        }
        when (ruleManager().updateRule(rule)) {
            BannedApiRuleManager.UpdateResult.UPDATED -> {
                refreshList(selectId = rule.id)
                statusLabel.text = MpSqlVisualizerBundle.message("settings.bannedApi.status.updated")
            }
            BannedApiRuleManager.UpdateResult.NOT_FOUND -> {
                refreshList(selectId = null)
                statusLabel.text = MpSqlVisualizerBundle.message("settings.bannedApi.status.notFound")
            }
            BannedApiRuleManager.UpdateResult.DUPLICATE ->
                statusLabel.text = MpSqlVisualizerBundle.message("settings.bannedApi.status.duplicate")
            BannedApiRuleManager.UpdateResult.INVALID ->
                statusLabel.text = MpSqlVisualizerBundle.message("settings.bannedApi.status.invalid")
        }
    }

    private fun validationMessage(error: BannedApiMatcher.ValidationError): String =
        MpSqlVisualizerBundle.message(
            when (error) {
                BannedApiMatcher.ValidationError.CLASS_NAME_BLANK -> "settings.bannedApi.status.classBlank"
                BannedApiMatcher.ValidationError.DESCRIPTION_BLANK -> "settings.bannedApi.status.descriptionBlank"
                BannedApiMatcher.ValidationError.INVALID_CLASS_REGEX -> "settings.bannedApi.status.classRegex"
                BannedApiMatcher.ValidationError.INVALID_METHOD_REGEX -> "settings.bannedApi.status.methodRegex"
                BannedApiMatcher.ValidationError.INVALID_LEVEL -> "settings.bannedApi.status.invalid"
            },
        )

    private fun deleteSelected() {
        val rule = ruleList.selectedValue ?: return
        ruleManager().removeRule(rule.id)
        adding = false
        refreshList(selectId = null)
        statusLabel.text = MpSqlVisualizerBundle.message("settings.bannedApi.status.deleted")
    }

    private fun clearAll() {
        val count = ruleManager().rules.size
        if (count == 0) return
        val confirmed = Messages.showYesNoDialog(
            MpSqlVisualizerBundle.message("settings.bannedApi.clear.confirm"),
            MpSqlVisualizerBundle.message("settings.bannedApi.clear.title"),
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!confirmed) return
        ruleManager().clearRules()
        adding = false
        refreshList(selectId = null)
        statusLabel.text = MpSqlVisualizerBundle.message("settings.bannedApi.status.cleared")
    }

    // ---------- Configurable（即时生效，无缓存层） ----------

    override fun isModified(): Boolean = false

    override fun apply() {}

    override fun reset() {
        if (panel != null) refreshList(selectId = editingId)
    }

    override fun disposeUIResources() {
        managerListener?.let { ruleManager().removeListener(it) }
        managerListener = null
        panel = null
    }

    /** 测试缝：当前列表条目（headless 断言用）。 */
    internal fun rulesForTest(): List<BannedApiRule> = List(listModel.size) { listModel.getElementAt(it) }
}
