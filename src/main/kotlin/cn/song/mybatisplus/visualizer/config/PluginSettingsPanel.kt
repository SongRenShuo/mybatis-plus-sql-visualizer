package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.actions.ConvertSqlToJavaAction
import cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction
import cn.song.mybatisplus.visualizer.actions.ExplainSelectedTableAction
import cn.song.mybatisplus.visualizer.actions.CountSelectedTableAction
import cn.song.mybatisplus.visualizer.actions.SampleSelectedTableAction
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager
import cn.song.mybatisplus.visualizer.log.SourceCaptureGuide
import cn.song.mybatisplus.visualizer.log.SqlListTypeColor
import cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver
import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import cn.song.mybatisplus.visualizer.ui.ParamHistoryCache
import cn.song.mybatisplus.visualizer.log.agent.AgentStatusListener
import cn.song.mybatisplus.visualizer.util.PluginCacheManager
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.PluginBuildInfo
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBTextField
import javax.swing.ButtonGroup
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import java.util.Locale
import javax.swing.event.DocumentListener

/**
 * 设置表单面板（T-M5-02，F-16 配置项 UI）。
 *
 * 展示并可编辑全部配置项；[apply]/[reset]/[isModified] 与 [PluginSettings] 联动，
 * 由 [SectionConfigurable] 子类（SQL 预览/表名映射/SQL 日志/界面与操作/关于）接入 IDE Settings
 * 框架（保存即生效）。
 *
 * M14 增强：
 *  - 「界面语言」下拉（默认简体中文）——保存后经 [rebuild] 重建表单即时生效；
 *  - 「快捷键」行——[KeyboardShortcutPanel] **内嵌编辑** Convert to SQL 快捷键，
 *    保存时直接写入 IDE 当前 Keymap（不再跳转）。
 *
 * M14.1（用户反馈）：
 *  - 移除悬停预览相关配置（autoPreview / hoverDelay 已随功能删除）；
 *  - 布局紧凑化（减小行距 + 底部 weighty 占位，消除内容区上方空白）。
 *
 * M16.2 增强：
 *  - 「表前缀」「表后缀」文本输入行（可添加/清除）——统一修饰预览 SQL 中的表名（全局语义）。
 */
class PluginSettingsPanel @JvmOverloads constructor(
    internal val settings: PluginSettings,
    internal val sections: Set<String> = SECTION_ALL,
) : com.intellij.openapi.Disposable {
    companion object {
        const val SECTION_PREVIEW = "preview"
        const val SECTION_TABLE_MAPPING = "tableMapping"
        const val SECTION_CODE_GEN = "codeGen"
        const val SECTION_SQL_LOG = "sqlLog"
        const val SECTION_UI = "ui"
        const val SECTION_UPDATE_DIAGNOSTICS = "updateDiagnostics"
        /** T-ShortcutSettings：插件快捷键统一管理节（5 个动作快捷键内嵌录制编辑）。 */
        const val SECTION_SHORTCUTS = "shortcuts"
        val SECTION_ALL = setOf(SECTION_PREVIEW, SECTION_TABLE_MAPPING, SECTION_CODE_GEN, SECTION_SQL_LOG, SECTION_UI, SECTION_UPDATE_DIAGNOSTICS, SECTION_SHORTCUTS)
    }

    /**
     * 展示脱敏模式下拉选项（枚举本体 + 显示文案；data class 按 mode 判等，对齐 LanguageOption 范式）。
     * W2 守卫批（EnumReachabilityGuardTest）改携带枚举本体：WARN/BLOCK 在实参位有了唯一产出点，
     * 不再是「有消费零产出」的死常量；持久化仍走 [value]（name 串）。
     */
    internal data class MaskModeOption(val label: String, val mode: cn.song.mybatisplus.visualizer.util.DisplayMasker.MaskMode) {
        val value: String get() = mode.name
        override fun toString(): String = label
    }

    /**
     * T-MagpieGems W2：展示脱敏模式下拉的选项（顺序=下拉顺序，首项 OFF=默认）。
     * 实例级初始化（非 companion）——文案随语言 rebuild 重建面板时刷新（语言切换同一 JVM
     * 内重建面板，companion 静态清单会把旧语言冻住）。
     */
    private val maskModeOptions: List<MaskModeOption> = listOf(
        MaskModeOption(MpSqlVisualizerBundle.message("settings.display.mask.mode.off"), cn.song.mybatisplus.visualizer.util.DisplayMasker.MaskMode.OFF),
        MaskModeOption(MpSqlVisualizerBundle.message("settings.display.mask.mode.mask"), cn.song.mybatisplus.visualizer.util.DisplayMasker.MaskMode.MASK),
        MaskModeOption(MpSqlVisualizerBundle.message("settings.display.mask.mode.warn"), cn.song.mybatisplus.visualizer.util.DisplayMasker.MaskMode.WARN),
        MaskModeOption(MpSqlVisualizerBundle.message("settings.display.mask.mode.block"), cn.song.mybatisplus.visualizer.util.DisplayMasker.MaskMode.BLOCK),
    )

    /**
     * 语言选项（值 + 当前语言下的显示文案；rebuild 时按新语言重建）。
     *
     * M19 修复：必须为 data class（按 value 判等）——JDK17 `JComboBox.setSelectedItem` 会以
     * `equals` 在 model 中查找元素，**找不到则静默 return**（选中不生效、ActionEvent 不触发）；
     * 原无 equals 覆写（身份判等）时 `reset()`/写穿入口传入的新实例全部匹配失败——选中显示与
     * 写穿监听一并失效。
     */
    internal data class LanguageOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    internal fun languageOptions(): Array<LanguageOption> = arrayOf(
        LanguageOption("zh", MpSqlVisualizerBundle.message("settings.language.zh")),
        LanguageOption("en", MpSqlVisualizerBundle.message("settings.language.en")),
        LanguageOption("AUTO", MpSqlVisualizerBundle.message("settings.language.auto")),
    )

    /**
     * 表名推断策略选项（值 + 当前语言下的显示文案；rebuild 时按新语言重建）。
     * 与 [LanguageOption] 同理必须为 data class（JDK17 `JComboBox.setSelectedItem` 按 equals 匹配）。
     */
    internal data class InferenceOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    internal fun inferenceOptions(): Array<InferenceOption> = arrayOf(
        InferenceOption(PluginSettings.INFERENCE_SNAKE, MpSqlVisualizerBundle.message("settings.tableNameInference.snake")),
        InferenceOption(PluginSettings.INFERENCE_CAMEL, MpSqlVisualizerBundle.message("settings.tableNameInference.camel")),
    )

    /**
     * 反向转换 Wrapper 形态选项（T-M36.3）：值 + 当前语言下的显示文案。
     * 与 [LanguageOption] 同理必须为 data class（JDK17 `JComboBox.setSelectedItem` 按 equals 匹配）。
     */
    internal data class ReverseFormatOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    /** T-CrudColor：CRUD 颜色标记形态下拉项。 */
    internal data class CrudColorStyleOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    /** T-EmbeddedColPrefix：嵌入列命名约定下拉项（值 + 当前语言显示文案）。 */
    internal data class EmbeddedNamingOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    internal fun crudColorStyleOptions(): Array<CrudColorStyleOption> = arrayOf(
        CrudColorStyleOption(PluginSettings.CRUD_COLOR_STYLE_BAR, MpSqlVisualizerBundle.message("settings.log.crudColor.style.bar")),
        CrudColorStyleOption(PluginSettings.CRUD_COLOR_STYLE_BACKGROUND, MpSqlVisualizerBundle.message("settings.log.crudColor.style.background")),
        CrudColorStyleOption(PluginSettings.CRUD_COLOR_STYLE_OFF, MpSqlVisualizerBundle.message("settings.log.crudColor.style.off")),
    )

    // ---------- T-CrudColor 颜色控件辅助 ----------

    /** T-PT-04 / O-79：当前选中的空参数填充策略（占位档为默认——空选/异常态一律落默认档）。 */
    private fun selectedEmptyParamFill(): String = when {
        emptyParamFillPlaceholderRadio.isSelected -> PluginSettings.EMPTY_PARAM_FILL_PLACEHOLDER
        emptyParamFillEmptyRadio.isSelected -> PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING
        emptyParamFillNullRadio.isSelected -> PluginSettings.EMPTY_PARAM_FILL_NULL
        else -> PluginSettings.DEFAULT_EMPTY_PARAM_FILL
    }

    /** 当前下拉选中的样式值（空选兜默认——与渲染器 styleOf 的兜底保持一致）。 */
    private fun selectedCrudColorStyle(): String =
        (crudColorStyleCombo.selectedItem as? CrudColorStyleOption)?.value
            ?: PluginSettings.CRUD_COLOR_STYLE_BAR

    /** 色板 → `#RRGGBB`（未选色 → 空串，渲染侧按默认色处理）。 */
    private fun colorHexOf(panel: ColorPanel): String {
        val c = panel.selectedColor ?: return ""
        return String.format(Locale.ENGLISH, "#%02X%02X%02X", c.red, c.green, c.blue)
    }

    /** `#RRGGBB` → Color（非法回退默认色，不抛）。 */
    internal fun colorOf(hex: String): Color = SqlListTypeColor.parseHex(hex, Color(0x4A86E8))

    /** T-DisplayModeToSettings：SQL 列表显示模式下拉项（value=SqlListDisplayMode.name）。 */
    internal data class ListDisplayModeOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    internal fun listDisplayModeOptions(): Array<ListDisplayModeOption> = arrayOf(
        ListDisplayModeOption(cn.song.mybatisplus.visualizer.log.SqlListDisplayMode.SINGLE_LINE.name, MpSqlVisualizerBundle.message("log.list.displayMode.single")),
        ListDisplayModeOption(cn.song.mybatisplus.visualizer.log.SqlListDisplayMode.KEYWORD_WRAP.name, MpSqlVisualizerBundle.message("log.list.displayMode.keywordWrap")),
        ListDisplayModeOption(cn.song.mybatisplus.visualizer.log.SqlListDisplayMode.FORMATTED.name, MpSqlVisualizerBundle.message("log.list.displayMode.formatted")),
    )

    /** 当前下拉选中的显示模式值（空选兜默认——与 PluginSettings.sqlListDisplayMode 的兜底一致）。 */
    private fun selectedListDisplayMode(): String =
        (logDisplayModeCombo.selectedItem as? ListDisplayModeOption)?.value
            ?: PluginSettings.DEFAULT_LOG_LIST_DISPLAY_MODE

    internal fun reverseFormatOptions(): Array<ReverseFormatOption> = arrayOf(
        ReverseFormatOption(PluginSettings.REVERSE_AUTO, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.auto")),
        ReverseFormatOption(PluginSettings.REVERSE_QUERY, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.query")),
        ReverseFormatOption(PluginSettings.REVERSE_LAMBDA, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.lambda")),
        ReverseFormatOption(PluginSettings.REVERSE_WRAPPERS, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.wrappers")),
        ReverseFormatOption(PluginSettings.REVERSE_MPJ_LAMBDA, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.mpjLambda")),
    )

    /** 嵌入列命名约定选项（T-EmbeddedColPrefix）：AUTO 自动 / NONE 无前缀 / COMPONENT_PATH 组件路径前缀。 */
    internal fun embeddedNamingOptions(): Array<EmbeddedNamingOption> = arrayOf(
        EmbeddedNamingOption(PluginSettings.EMBEDDED_NAMING_AUTO, MpSqlVisualizerBundle.message("settings.embeddedColumnNaming.auto")),
        EmbeddedNamingOption(PluginSettings.EMBEDDED_NAMING_NONE, MpSqlVisualizerBundle.message("settings.embeddedColumnNaming.none")),
        EmbeddedNamingOption(
            PluginSettings.EMBEDDED_NAMING_COMPONENT_PATH,
            MpSqlVisualizerBundle.message("settings.embeddedColumnNaming.componentPath"),
        ),
    )

    // T-TenantLine 诊断日志：输出 UI Apply 决策链路。
    private val LOG = Logger.getInstance(PluginSettingsPanel::class.java)

    // ------------------------------------------------------------------
    // 表单控件
    // ------------------------------------------------------------------

    /** 方言下拉（选项来自方言注册表；T-DialectAutoInfer 首项为「智能推断」）。 */
    internal val dialectCombo = JComboBox(
        (listOf(MpSqlVisualizerBundle.message("settings.dialect.auto")) + DialectRegistry.dialects.map { it.name }).toTypedArray(),
    )

    /** T-DialectAutoInfer：「智能推断」哨兵项（显示文案随语言变化，写入 state 前须甄别）。 */
    internal val autoDialectItem: String = MpSqlVisualizerBundle.message("settings.dialect.auto")

    /** T-DialectAutoInfer：推断状态只读行（当前推断结果/依据，或手动指定值）。 */
    internal val dialectInferredLabel = javax.swing.JLabel()

    /** 参数替换开关。 */
    internal val replaceParamsCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.replaceParams"))

    /** 格式化开关。 */
    internal val formatCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.formatSql"))

    /** 竖向排列 VALUES 行尾配对列名标注开关（T-VPairAnno，默认开）。 */
    internal val verticalPairAnnotationCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.verticalPairAnnotation"))

    /** SQL 预览字段较多时自动折叠开关（T-AutoFold，默认关）。 */
    internal val autoFoldCheck = JBCheckBox()

    /** 自动折叠字段数阈值输入框（T-AutoFold）：开关勾选才启用编辑（对齐自动保存联动范式）。 */
    internal val autoFoldThresholdField = JBTextField(6)

    /** 字面量参数恢复开关（T-HistParam，默认开）：无 `?` 占位符的日志 SQL 把字符串字面量识别为参数。 */
    internal val literalParamRecoveryCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.literalParamRecovery"))

    // ---------- M56 T-M56-08：增强捕获（agent 字节码通道；对用户不出现 agent 字样） ----------

    /** 增强捕获开关（默认开）：勾选即授权，不再弹注入确认对话框。 */
    internal val enhancedCaptureCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.enhancedCapture"))

    /** 脱敏开关（默认关）：对疑似敏感形态打码，agent 采集层执行。 */
    internal val enhancedDesensitizeCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.enhancedCapture.desensitize"))

    /**
     * R60（T-LaneB D96）→ T-MagpieGems W2：展示/导出链脱敏动作分级下拉
     * （关闭/打码/警示/阻断；默认关闭=既有默认观感不变）。行标签复用 settings.display.mask
     * 键（搜索索引既有条目即本控件的可搜索身份，防「跳了不亮」幽灵条目）。
     */
    internal val displayMaskModeCombo = JComboBox(
        maskModeOptions.map { it.label }.toTypedArray(),
    )

    /** T-MagpieGems W2：脱敏豁免白名单（列名关键词，换行或逗号分隔；3 行小文本域）。 */
    internal val displayMaskAllowListArea = com.intellij.ui.components.JBTextArea(3, 24).apply {
        emptyText.text = MpSqlVisualizerBundle.message("settings.display.mask.allowList.placeholder")
        lineWrap = true
        wrapStyleWord = true
    }

    /** 下拉选中项对应的 mode 串（OFF/MASK/WARN/BLOCK）。 */
    internal fun selectedDisplayMaskMode(): String =
        maskModeOptions.getOrNull(displayMaskModeCombo.selectedIndex)?.value ?: "OFF"

    /** 按 mode 串回填下拉选中项（未知串回落 OFF 项）。 */
    internal fun setDisplayMaskMode(mode: String) {
        displayMaskModeCombo.selectedIndex = maskModeOptions.indexOfFirst { it.value == mode }.coerceAtLeast(0)
    }

    /** T-StackHoverUi：调用栈快照总帧数（agent 两端采样预算；CONFIG 帧热下发）。 */
    internal val stackMaxFramesField = JBTextField(6)

    /** 增强捕获状态行：已挂载 pid=x / 等待应用启动 / 已回落控制台捕获。 */
    internal val enhancedStatusLabel = JBLabel(" ")

    /** T-SettingsTruth P2：AgentManager 状态行 messageBus 连接（disposeResources 断开）。 */
    private var agentStatusConnection: com.intellij.openapi.Disposable? = null

    /**
     * T-SettingsTruth P2 / T-CaptureTruth T-CT-01：聚合各打开项目的 AgentManager 状态写入增强捕获
     * 状态行。无任何活动叙事（各项目均 null）→ 显示空串；多项目以「；」连接。
     *
     * T-EnhancedCaptureDefaultOn（2026-10-19）：重新授权入口已移除，本函数只刷新文本。
     */
    internal fun refreshEnhancedStatusLabel() {
        val agents = ProjectManager.getInstance().openProjects.mapNotNull { p ->
            runCatching { if (p.isDisposed) null else AgentManager.getInstance(p) }.getOrNull()
        }
        val text = agents
            .mapNotNull { a -> runCatching { a.statusSummary() }.getOrNull() }
            .filterNotNull()
            .joinToString("；")
        enhancedStatusLabel.text = text.ifEmpty { " " }
    }

    /**
     * T-SettingsTruth P2：面板资源清理（messageBus 连接断开）。
     * 由 [SectionConfigurable.disposeUIResources] 调用；防 IDE 退出 ObjectTree leak 告警。
     */
    internal fun disposeResources() {
        val connection = agentStatusConnection
        if (connection != null) {
            runCatching { com.intellij.openapi.util.Disposer.dispose(connection) }
        }
        agentStatusConnection = null
    }

    override fun dispose() {
        disposeResources()
    }

    // ── T-PrefixValidate：两栏前缀的校验结果行（有问题才露出；一键修复回写两栏后自动消失） ──
    // 监听器一律挂在字段初始化处：rebuild（语言切换）会重跑 buildSections，挂在 addRow 处会随重建叠加回调

    private val prefixValidationLabel = com.intellij.ui.components.JBLabel(" ").apply {
        icon = com.intellij.icons.AllIcons.General.Warning
        isVisible = false
    }

    private val prefixFixButton = JButton(MpSqlVisualizerBundle.message("settings.sourceFramePrefixes.fix")).apply {
        isVisible = false
        addActionListener { onPrefixAutoFixClicked() }
    }

    internal val prefixValidationRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)).apply {
        isOpaque = false
        add(prefixValidationLabel)
        add(prefixFixButton)
    }

    /** 校验两栏并刷新结果行；无问题整行隐藏（GridBag 不可见组件不占位，同 enhancedReauthButton 范式）。 */
    private fun refreshPrefixValidationRow() {
        val report = SourceFramePrefixesValidator.validate(
            frameSkipPrefixesField.text,
            bizJarPrefixesField.text,
        )
        val problems = buildList {
            report.invalidLines.forEach {
                add(MpSqlVisualizerBundle.message("settings.sourceFramePrefixes.invalidLine", it.lineNo, it.text))
            }
            report.conflicts.forEach {
                add(MpSqlVisualizerBundle.message("settings.sourceFramePrefixes.conflict", it.prefix))
            }
            report.duplicates.forEach {
                add(MpSqlVisualizerBundle.message("settings.sourceFramePrefixes.duplicate", it.prefix))
            }
        }
        if (problems.isEmpty()) {
            prefixValidationLabel.isVisible = false
            prefixFixButton.isVisible = false
            return
        }
        prefixValidationLabel.text = if (problems.size == 1) {
            problems.single()
        } else {
            MpSqlVisualizerBundle.message("settings.sourceFramePrefixes.problems", problems.size) + "　" + problems.first()
        }
        prefixValidationLabel.toolTipText = problems.joinToString("\n")
        prefixValidationLabel.isVisible = true
        prefixFixButton.isVisible = true
    }

    /** 一键修复：回写两栏（setText 触发 document 监听 → 校验行自动刷新为隐藏）。 */
    private fun onPrefixAutoFixClicked() {
        val (skip, biz) = SourceFramePrefixesValidator.autoFix(
            frameSkipPrefixesField.text,
            bizJarPrefixesField.text,
        )
        frameSkipPrefixesField.text = skip
        bizJarPrefixesField.text = biz
    }

    /**
     * T-SourceJumpFunnel P1-3：源码捕获帧过滤前缀（一行一个前缀，如 `com.corp.engine.`）。
     * Apply 后经 CONFIG 热下发 agent（即时生效，无需重启应用），IDE 侧候选解析同步过滤。
     */
    internal val frameSkipPrefixesField = com.intellij.ui.components.JBTextArea(5, 24).apply {
        // UI026：列宽只影响 preferred（实际宽度由分栏 CENTER + fill=HORIZONTAL 拉伸），
        // 40 列在英文 UI 下与「源码捕获帧过滤前缀」标签相加会把设置页撑出横向滚动条；
        // 24 列让 preferred 落回默认内容区，默认窗口下可见宽度不变。
        // T-SourceJumpFunnel：空态灰色占位提示（悬停 tooltip 之外的第一眼引导）
        emptyText.text = MpSqlVisualizerBundle.message("settings.sourceFrameSkipPrefixes.placeholder")
        lineWrap = true
        wrapStyleWord = true
        // T-PrefixValidate：格式/冲突校验随输入即时刷新（面板只展示，判定在 SourceFramePrefixesValidator）
        document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                refreshPrefixValidationRow()
            }
        })
        // T-FramePrefixVisibility F1：fill=NONE 下多行文本框会塌成一条细线（真机截图实证）——
        // T-FramePrefixResize：默认加大（5 行），首选高保底提到 100px
        preferredSize = java.awt.Dimension(preferredSize.width, preferredSize.height.coerceAtLeast(100))
        // T-FramePrefixResize：最小高保底 60px，作为分栏拖拽下限（拖到极限不塌成细线）
        minimumSize = java.awt.Dimension(minimumSize.width, minimumSize.height.coerceAtLeast(60))
        // T-FramePrefixVisibility F1b：深色主题下 JBTextArea 默认背景与面板几乎同色，输入框
        // 「看不出是个框」（真机截图二轮反馈）——按平台编辑器标准形态补边框+背景区分度
        background = com.intellij.util.ui.UIUtil.getTextFieldBackground()
        border = javax.swing.border.CompoundBorder(
            com.intellij.util.ui.UIUtil.getTextFieldBorder(),
            javax.swing.border.EmptyBorder(4, 6, 4, 6),
        )
    }

    /** gutter 预览 SQL 图标开关（M10）。 */
    internal val gutterIconCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.showGutterIcon"))
    // R2（gutter 按来源分组显隐）：六组开关（默认全开=现行为不变）
    internal val gutterGroupMapperXmlCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.gutterGroup.mapperXml"))
    internal val gutterGroupAnnotationCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.gutterGroup.annotation"))
    internal val gutterGroupJpaHqlCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.gutterGroup.jpaHql"))
    internal val gutterGroupJdbcCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.gutterGroup.jdbc"))
    internal val gutterGroupInlineFallbackCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.gutterGroup.inlineFallback"))
    internal val gutterGroupOtherCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.gutterGroup.other"))
    /** T-MapperNavCoexistYield：MyBatisCodeHelperPro 启用时让出 Mapper 双向跳转图标（默认勾选＝让位）。 */
    internal val mapperNavYieldCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.mapperNavYield"))

    /**
     * T-ToolSiteBizFrame：JAR 业务帧反向名单（一行一个前缀，如 `com.corp.shared.util.`）。
     * 语义与 [frameSkipPrefixesField] 相反——名单内包名的帧即使来自 JAR 也按业务帧对待；
     * Apply 后随 CONFIG 热下发 agent。
     */
    internal val bizJarPrefixesField = com.intellij.ui.components.JBTextArea(5, 24).apply {
        // UI026：同 [frameSkipPrefixesField]，24 列仅收敛 preferred，避免英文 UI 横向溢出。
        emptyText.text = MpSqlVisualizerBundle.message("settings.sourceFrameBizJarPrefixes.placeholder")
        lineWrap = true
        wrapStyleWord = true
        // T-PrefixValidate：格式/冲突校验随输入即时刷新（同 skip 栏）
        document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                refreshPrefixValidationRow()
            }
        })
        // 与帧过滤前缀框同款高度保底、拖拽下限与边框形态（T-FramePrefixVisibility F1/F1b + T-FramePrefixResize）
        preferredSize = java.awt.Dimension(preferredSize.width, preferredSize.height.coerceAtLeast(100))
        minimumSize = java.awt.Dimension(minimumSize.width, minimumSize.height.coerceAtLeast(60))
        background = com.intellij.util.ui.UIUtil.getTextFieldBackground()
        border = javax.swing.border.CompoundBorder(
            com.intellij.util.ui.UIUtil.getTextFieldBorder(),
            javax.swing.border.EmptyBorder(4, 6, 4, 6),
        )
    }

    /**
     * T-FramePrefixResize：两前缀框的垂直分栏——拖拽中缝即重分配两框高度（语义经用户 2026-10-05
     * 确认：互相腾挪、总高固定），比例经 proportionKey 记忆跨重启（同款形态见 SqlPreviewPanel 预览分栏）。
     * 每格 = 标签（左、顶对齐，两格标签同宽对齐）+ 文本框（占满余宽）；rebuild 重建新实例时按同键自动加载记忆比例。
     */
    internal fun buildFramePrefixesSplitter(): com.intellij.ui.OnePixelSplitter {
        val skipLabel = JBLabel(MpSqlVisualizerBundle.message("settings.sourceFrameSkipPrefixes"))
        val bizLabel = JBLabel(MpSqlVisualizerBundle.message("settings.sourceFrameBizJarPrefixes"))
        val labelWidth = maxOf(skipLabel.preferredSize.width, bizLabel.preferredSize.width)
        for (label in listOf(skipLabel, bizLabel)) {
            label.verticalAlignment = javax.swing.SwingConstants.TOP
            label.preferredSize = java.awt.Dimension(labelWidth, label.preferredSize.height)
            label.border = javax.swing.border.EmptyBorder(0, 0, 0, 8)
        }
        fun cell(label: JBLabel, field: JComponent): JPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            // 上下各 4px 呼吸空隙，中缝分隔条不贴框
            border = javax.swing.border.EmptyBorder(4, 0, 4, 0)
            add(label, BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }
        return com.intellij.ui.OnePixelSplitter(true, "mpvs.settings.framePrefixes.split", 0.5f).apply {
            firstComponent = cell(skipLabel, frameSkipPrefixesField)
            secondComponent = cell(bizLabel, bizJarPrefixesField)
            isShowDividerControls = false
            // 拖拽下限跟随组件 minimumSize（两框保底 60px 高）
            setHonorComponentsMinimumSize(true)
        }
    }

    internal val joinDiagramNativeCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.joinDiagramNative"))

    /** 通用 XML 内嵌 SQL 检测开关（O-72 T-XmlInlineSql，默认开）。 */
    internal val xmlInlineSqlCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.xmlInlineSql"))

    /** mapper XML 语法高亮注入开关（T-XmlSynHi D4=T-PerfSweep16 F46 补 UI）：State.xmlSynHiEnabled 字段原有，此前无面板入口。 */
    internal val xmlSynHiCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.xmlSynHi"))

    /** 首用锚点引导通知开关（T-UxOnboard T-UX-01，默认开）：关闭后首用引导全程静默。 */
    internal val onboardingAnchorTipCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.onboardingAnchorTip"))

    /** 空白符可视化开关（T-PreviewWhitespaceToggle O-44a，默认关=不设置该属性、继承 IDE 全局）。 */
    internal val editorShowWhitespacesCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.editorShowWhitespaces"))

    /** 逻辑删除注入开关（T-M15-07）：@TableLogic 实体查询/删除自动追加 `AND deleted = 0`。 */
    internal val injectLogicDeleteCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.injectLogicDelete"))
    internal val logicDeleteValueField = JBTextField(6)

    /** 多租户注入开关（T-TenantLine）：SELECT/UPDATE/DELETE 自动追加 `AND tenant_id = ?`、INSERT 列清单追加租户列。 */
    internal val injectTenantLineCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.injectTenantLine"))

    /** 租户列名输入框（T-TenantLine）：默认 tenant_id；可按业务调整，如 org_id / shop_id。 */
    internal val tenantColumnNameField = JBTextField(10)

    /** 租户列默认值输入框（F-6 Phase A）：UPDATE SET / INSERT 追加租户列时的字面量默认值。 */
    internal val tenantColumnDefaultField = JBTextField(6)

    /** INSERT 也追加租户列开关（F-6 Phase A）：INSERT 列清单追加租户列。 */
    internal val injectTenantOnInsertCheck = JBCheckBox()

    /** 数据权限占位提示开关（F-6 Phase C）：显示 DataPermissionInterceptor 运行时差异提示。 */
    internal val dataPermissionHintCheck = JBCheckBox()

    /** 数据权限模拟条件输入框（F-6 Phase C）：用户手动填写的 WHERE 片段。 */
    internal val dataPermissionMockConditionField = JBTextField(20)

    /** 分页拦截器模拟开关（R12 T-PaginationSim）：COUNT 包裹预览第二语句产出（默认开）。 */
    internal val paginationSimCheck = JBCheckBox()

    /** 分页 COUNT 查询预览子开关（T-CountSimToggle）：仅控制 COUNT 产出、不影响 LIMIT（父开关优先，默认开）。 */
    internal val paginationCountCheck = JBCheckBox()

    /** JDBC 模板 SQL 字符串预览开关：queryForList/update 等方法的字符串 SQL 实参显示预览图标。 */
    internal val jdbcSqlPreviewCheck = JBCheckBox()

    /** EXPLAIN 结构化渲染开关（R33）：面板执行计划按钮的结构化通道（默认开，失败自动回退原生控制台）。 */
    internal val explainStructuredCheck = JBCheckBox()
    internal val explainPlanRetentionCheck = JBCheckBox()
    internal val explainPlanRetentionDisclosure = JBLabel()

    /** T-ExecTimeoutGuard：执行超时（秒，0=不限）——JBIntSlider 过重，JBTextField + 数字容错。 */
    internal val executionTimeoutField = JBTextField(8)

    /** 表前缀输入框（T-M16.2-03）：统一加在所有表名前，全局语义（@TableName 显式表名同样生效）。 */
    internal val tablePrefixField = JBTextField(12)

    /** 表后缀输入框（T-M16.2-03）：统一加在所有表名后，全局语义；清空即恢复原样。T-M19.4：多值 `;` 分隔。 */
    internal val tableSuffixField = JBTextField(12)

    /** 表名推断策略下拉（T-M19.4）：无 @TableName 时类名→表名的推断方式（SNAKE 默认 / CAMEL 保留驼峰）。 */
    internal val inferenceCombo = JComboBox<InferenceOption>()

    /** 忽略字段名输入框（T-M19.4）：`;` 分隔的 Java 字段名——命中字段不进 SQL（SELECT 展开/补全/参数对照）。 */
    internal val ignoredFieldsField = JBTextField(12)

    /** 移除表名前缀输入框（T-M19.4）：`;` 分隔，最终表名以哪个开头就移除哪个（首个匹配）。 */
    internal val removeTablePrefixField = JBTextField(12)

    /** 移除表名后缀输入框（T-M19.4）：`;` 分隔，最终表名以哪个结尾就移除哪个（首个匹配）。 */
    internal val removeTableSuffixField = JBTextField(12)

    /** 列名数据源校正开关（O-58）：实体字段名与库真实列名不一致时用数据源真值纠正列名。 */
    internal val columnCalibrationCheck = JBCheckBox()

    /** 界面语言下拉（M14：默认简体中文；跟随系统 / English 可选）。 */
    internal val languageCombo = JComboBox<LanguageOption>()

    /** T-PT-04 / O-79：空参数填充策略单选（`?` 占位符 / NULL 字面量 / 空字符串，占位档为默认）。 */
    internal val emptyParamFillPlaceholderRadio = JBRadioButton()
    internal val emptyParamFillNullRadio = JBRadioButton()
    internal val emptyParamFillEmptyRadio = JBRadioButton()
    internal val emptyParamFillGroup = ButtonGroup()

    /** 反向转换 Wrapper 形态下拉（T-M36.3：AUTO/QUERY/LAMBDA/MPJ_LAMBDA）。 */
    internal val reverseFormatCombo = JComboBox<ReverseFormatOption>()

    /** 嵌入列命名约定下拉（T-EmbeddedColPrefix：AUTO/NONE/COMPONENT_PATH）。 */
    internal val embeddedNamingCombo = JComboBox<EmbeddedNamingOption>()

    /** 反向转换手动选择实体类开关（自动推断未命中/多表 JOIN 时弹实体选择弹窗）。 */
    internal val manualEntityCheck = JBCheckBox()

    /** 后台扫描依赖库反查实体开关（T-JarEmbedded 方案 1）。 */
    internal val librarySweepCheck = JBCheckBox()

    /** 插件缓存信息（统一条目总数——所有打开项目汇总；不细分缓存类型）。 */
    internal val cacheInfoLabel = JBLabel(" ")

    /** 设置导入/导出结果反馈（20260907：行内提示替代模态弹窗——原弹窗叠在 Settings 对话框下，须关设置才可见）。 */
    internal val statusLabel = JBLabel(" ")

    /** 插件缓存统一清除按钮（清全部缓存，下次使用自动重建）。 */
    internal val clearCacheButton = JButton(MpSqlVisualizerBundle.message("settings.cache.clear"))

    // ---------- T-M31.14 SQL 日志配置（参考 MybatisCodeHelper-Pro「MyBatis Log Setting」） ----------
    internal val logSqlPrefixField = JBTextField(12)
    internal val logParamPrefixField = JBTextField(12)
    internal val logMixedPrefixCheck = JBCheckBox()
    internal val logAutoCaptureCheck = JBCheckBox()
    internal val logAutoOpenCheck = JBCheckBox()
    internal val logStopOnToolWindowCloseCheck = JBCheckBox()
    internal val logTimestampCheck = JBCheckBox()
    internal val logTimestampFormatField = JBTextField(12)
    internal val logTimestampSourceLogRadio = JBRadioButton()
    internal val logTimestampSourceCaptureRadio = JBRadioButton()
    internal val logTimestampSourceGroup = ButtonGroup()
    internal val logFormatSqlCheck = JBCheckBox()
    internal val logFilterKeywordsField = JBTextField(12)
    internal val logDedupeField = JBTextField(12)
    internal val logMaxParseField = JBTextField(12)
    internal val logMaxCountField = JBTextField(12)
    internal val logHibernateEnabledCheck = JBCheckBox()
    internal val logHibernateSqlPrefixField = JBTextField(12)
    internal val logHibernateParamPrefixField = JBTextField(12)
    internal val logJdbcEnabledCheck = JBCheckBox()

    // R10 T-ShardingSphereCompat：ShardingSphere 双段日志捕获开关（照抄 Hibernate/Jdbc 开关形态）
    internal val logShardingSphereEnabledCheck = JBCheckBox()

    // ---------- F-43 自动保存历史 SQL ----------
    internal val logAutoSaveCheck = JBCheckBox()
    internal val logAutoSavePathField = JBTextField(20)
    internal val logAutoSaveBrowseButton = JButton("...")
    internal val logAutoSavePatternField = JBTextField(20)
    internal val logAutoSavePatternPreview = JBLabel(" ")
    /** T-HistoryFileRetention：历史文件保留上限（0 = 不限/全量）。 */
    internal val logAutoSaveMaxFilesField = JBTextField(12)

    // ---------- T-M19-StorageClear：三类本地数据的分离动作 ----------

    internal enum class StorageClearKind { PREVIEW_HISTORY, PARAM_HISTORY }

    /** 破坏性动作确认缝：生产弹确认；headless 测试注入确定结果，不唤起原生对话框。 */
    internal var storageClearConfirmation: (StorageClearKind) -> Boolean = { kind ->
        val messageKey = when (kind) {
            StorageClearKind.PREVIEW_HISTORY -> "settings.storage.previewHistory.clear.confirm"
            StorageClearKind.PARAM_HISTORY -> "settings.storage.paramHistory.clear.confirm"
        }
        Messages.showYesNoDialog(
            ProjectManager.getInstance().openProjects.firstOrNull(),
            MpSqlVisualizerBundle.message(messageKey),
            MpSqlVisualizerBundle.message("settings.storage.clear.title"),
            Messages.getWarningIcon(),
        ) == Messages.YES
    }

    /** 捕获目录打开缝：测试只观察目标路径；生产交给平台既有 BrowserUtil.open(file URI)。 */
    internal var captureDirectoryOpener: (File) -> Unit = { dir ->
        com.intellij.ide.BrowserUtil.open(dir.toURI().toString())
    }

    /** 默认目录需要项目根；无打开项目时 SqlLogAutoSaver 按既有契约回落 user.home。 */
    internal var captureProjectBasePathProvider: () -> String? = {
        ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }?.basePath
    }

    internal val openCaptureDirectoryButton = JButton().apply {
        addActionListener { openCaptureHistoryDirectory() }
    }
    internal val clearPreviewHistoryButton = JButton().apply {
        addActionListener { clearPreviewHistory() }
    }
    internal val clearParamHistoryButton = JButton().apply {
        addActionListener { clearParamHistory() }
    }
    internal val storageActionStatusLabel = JBLabel(" ")

    // ---------- F-44-7 SqlSourceInterceptor 自动注入 ----------
    internal val autoInjectInterceptorCheck = JBCheckBox()

    // ---------- O-1 日志窗口差异化（执行耗时 / 慢查询） ----------
    internal val logTimePrefixField = JBTextField(12)
    internal val slowQueryThresholdField = JBTextField(12)
    internal val logShowDurationCheck = JBCheckBox()

    // T-DataSourceBadgeMode：数据源角标显示完整上下文（默认关＝仅显示数据库类型）
    internal val dataSourceBadgeDetailCheck = JBCheckBox()

    // ---------- T-CrudColor：SQL 列表 CRUD 类型颜色标记 ----------
    /** 标记形态下拉（色条 / 整行淡背景 / 关闭）。 */
    internal val crudColorStyleCombo = ComboBox<CrudColorStyleOption>()

    /** T-DisplayModeToSettings：SQL 列表显示模式（SQL 日志 + SQL 历史两列表共享一键）。 */
    internal val logDisplayModeCombo = ComboBox<ListDisplayModeOption>()
    /** 四类型色板（平台原生 ColorPanel，点击弹出取色器）。 */
    internal val crudColorSelectPanel = ColorPanel()
    internal val crudColorInsertPanel = ColorPanel()
    internal val crudColorUpdatePanel = ColorPanel()
    internal val crudColorDeletePanel = ColorPanel()
    /** 一键恢复四色默认值。 */
    internal val crudColorResetButton = JButton()

    /**
     * M19.1：语言写穿后「待应用」标记——语言已实时写入配置，但用户尚未点击「应用/确定」。
     * 平台 Apply 按钮启用 = `configurable.isModified()`，无此标记则写穿后 Apply 按钮灰掉
     * （用户实测反馈）；`apply()` 消费后清除。
     */
    private var languagePending = false

    /** 快捷键内嵌编辑（M14.1：录制式修改 Convert to SQL 的 KeyboardShortcut，保存写入 Keymap）。 */
    internal val shortcutEditor = ShortcutEditor(ConvertToSqlAction.ACTION_ID)

    /** T-M41：SQL→Java 快捷键内嵌编辑（同款录制式，目标 action = ConvertSqlToJava）。 */
    internal val reverseShortcutEditor = ShortcutEditor(ConvertSqlToJavaAction.ACTION_ID)

    /** T-ShortcutSettings：${table} 快捷模板三动作快捷键内嵌编辑（Explain / Count / Sample）。 */
    internal val explainShortcutEditor = ShortcutEditor(ExplainSelectedTableAction.ACTION_ID)
    internal val countShortcutEditor = ShortcutEditor(CountSelectedTableAction.ACTION_ID)
    internal val sampleShortcutEditor = ShortcutEditor(SampleSelectedTableAction.ACTION_ID)

    /** 更新分区（M28：检查更新/下载安装/重启提示/启动自动检查开关）。 */
    internal val updateSection = UpdateSection(settings)

    // 搜索框已移至主设置页面（PluginSettingsFolderConfigurable）

    private val rootPanel: JPanel = JPanel(GridBagLayout()).apply {
        border = JBUI.Borders.empty(8, 10, 8, 10)
    }

    // ------------------------------------------------------------------
    // 构造与表单布局
    // ------------------------------------------------------------------

    init {
        buildSections(rootPanel)
        reset()
        refreshCacheInfo()
        // T-SettingsTruth P2：订阅 app 级增强捕获状态 Topic，AgentManager 状态迁移点刷新状态行。
        // T-CaptureTruth T-CT-05：**不得** Disposer.register(this@PluginSettingsPanel, connection)——
        // 面板自身不在 Disposer 树里，register 会把它挂成 ROOT_DISPOSABLE 子节点，而 Configurable
        // 链路只调 disposeResources() 不 dispose 面板，IDE 退出即报 Memory leak（沙盒实测 6 例，
        // 栈指本行）。连接生命周期由 disposeResources() 显式承担。
        agentStatusConnection = com.intellij.openapi.application.ApplicationManager.getApplication()
            .messageBus.connect()
            .also { connection ->
                connection.subscribe(AgentManager.AGENT_STATUS_TOPIC, object : AgentStatusListener {
                    override fun onStatusChanged() {
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            refreshEnhancedStatusLabel()
                        }
                    }
                })
            }
        refreshEnhancedStatusLabel()
        // M19（用户实测：设置切语言确定后重开仍原样——平台 Settings 框架 OK 仅对「当前选中页」且
        // isModified()==true 的 configurable 调用 apply()，改完语言切到其他页再确定即被静默跳过；
        // M16.3 roaming 修复只覆盖磁盘持久化链路）：
        // 语言下拉**写穿式持久化**——选中即写入配置（不依赖 OK→apply 链路），并立即重建表单即时切换。
        languageCombo.addActionListener {
            val value = (languageCombo.selectedItem as? LanguageOption)?.value
            if (value != null && value != settings.state.language) {
                settings.state.language = value
                // M19.1：写穿后置「待应用」标记——平台 Apply 按钮启用 = isModified()，无标记则
                // 应用按钮灰掉（用户实测反馈）；标记在 apply() 时清除（应用/确定后恢复灰属正常）
                languagePending = true
                // M19.1：**invokeLater 调度重建**——ActionEvent 派发中途同步修改组件树时
                // UI 刷新不可靠（用户实测：左侧树即时变、右侧面板不变），延后到派发完成再重建
                SwingUtilities.invokeLater { rebuild() }
            }
        }
    }

    /**
     * 构建表单布局（T-M31.8：分组 + TitledSeparator 分隔线，对齐 SQL 预览面板左侧分区样式）。
     * 高频组在上、低频组在下、同类相聚；rebuild 时重新调用（语言切换后组标题与文案刷新）。
     */
    internal inner class ShortcutEditor(
        internal val actionId: String,
    ) : JPanel(BorderLayout(6, 0)) {

        var shortcut: KeyboardShortcut? = null
            set(value) {
                field = value
                textField.text = value?.let { KeymapUtil.getShortcutText(it) } ?: "—"
            }

        /** M14.4：本次录制是否「强制使用」（冲突时抢占移除占用者；保存时消费后复位）。 */
        var forceOnApply: Boolean = false

        private val textField = JBTextField(18).apply {
            isEditable = false
            toolTipText = MpSqlVisualizerBundle.message("settings.shortcut.tip")
        }

        private val editButton = JButton(MpSqlVisualizerBundle.message("settings.shortcut.edit"))

        init {
            add(textField, BorderLayout.CENTER)
            add(editButton, BorderLayout.EAST)
            editButton.addActionListener { captureShortcut() }
        }

        /** M19.2：语言切换后刷新自身文案（tooltip/按钮；buildRows 每轮调用——字段实例文本构造时固化）。 */
        fun updateTexts() {
            textField.toolTipText = MpSqlVisualizerBundle.message("settings.shortcut.tip")
            editButton.text = MpSqlVisualizerBundle.message("settings.shortcut.edit")
        }

        /** 弹出模态录制对话框：聚焦输入框后按下组合键即完成，ESC 取消。 */
        private fun captureShortcut() {
            val dialog = JDialog(SwingUtilities.getWindowAncestor(this), Dialog.ModalityType.APPLICATION_MODAL)
            dialog.title = MpSqlVisualizerBundle.message("settings.shortcut.capture")
            val capture = JBTextField(20).apply {
                isFocusable = true
            }
            val panel = JPanel(BorderLayout(8, 8)).apply {
                border = JBUI.Borders.empty(10)
                add(JBLabel(MpSqlVisualizerBundle.message("settings.shortcut.capture")), BorderLayout.NORTH)
                add(capture, BorderLayout.CENTER)
            }
            dialog.contentPane.add(panel)
            dialog.pack()
            dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this))
            // M14.2 焦点修复：isVisible=true 为阻塞调用，必须在显示前调度聚焦，
            // 否则输入框无焦点导致按键捕获不到（第二次修改不生效的根因）
            SwingUtilities.invokeLater { capture.requestFocusInWindow() }
            capture.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_ESCAPE -> dialog.dispose()
                        KeyEvent.VK_ENTER -> dialog.dispose()
                        KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META -> Unit // 纯修饰键忽略
                        else -> {
                            val stroke = KeyStroke.getKeyStrokeForEvent(e)
                            val newShortcut = KeyboardShortcut(stroke, null)
                            // M14.2/14.4 冲突处理：仅冲突时弹窗，且「强制使用」为独立按钮（与普通确认区分）——
                            //  使用：绑定但不抢占（保留占用者，可能不生效）
                            //  强制使用：抢占移除占用者，保证始终生效
                            val conflict = conflictActionName(newShortcut)
                            if (conflict != null) {
                                val options = arrayOf(
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.use"),
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.force"),
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.cancel"),
                                )
                                // 与原无 project 行为一致；返回选项索引 Int（0=Yes/使用, 1=No/强制使用, 2=Cancel/取消）。
                                // T-M30：跨版本签名差异——showIdeaMessageDialog 已弃用，改用 MessageDialogBuilder.yesNoCancel
                                // （2024.3+ 可用）。guessWindowAndAsk() 返回 int：0=Yes(第一个按钮), 1=No(第二个), 2=Cancel(第三个)。
                                val choice = MessageDialogBuilder.yesNoCancel(
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.title"),
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict", conflict)
                                )
                                    .yesText(options[0])
                                    .noText(options[1])
                                    .cancelText(options[2])
                                    .icon(Messages.getWarningIcon())
                                    .guessWindowAndAsk()
                                when (choice) {
                                    0 -> { // 使用（普通）：不抢占
                                        forceOnApply = false
                                        shortcut = newShortcut
                                        dialog.dispose()
                                    }
                                    1 -> { // 强制使用：抢占，保证生效
                                        forceOnApply = true
                                        shortcut = newShortcut
                                        dialog.dispose()
                                    }
                                    else -> Unit // 取消
                                }
                            } else {
                                forceOnApply = false
                                shortcut = newShortcut
                                dialog.dispose()
                            }
                        }
                    }
                }
            })
            dialog.isVisible = true
        }

        /** 若新快捷键已被其他 action 占用，返回占用者名称（可读 action 文本；环境异常兜底 null）。 */
        private fun conflictActionName(newShortcut: KeyboardShortcut): String? = runCatching {
            val keymap = KeymapManager.getInstance().activeKeymap
            keymap.getActionIds()
                .filter { it != actionId }
                .firstOrNull { actionId -> keymap.getShortcuts(actionId).any { it == newShortcut } }
                ?.let { actionId ->
                    ActionManager.getInstance().getAction(actionId)?.templatePresentation?.text ?: actionId
                }
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 快捷键读写（M14.1：内嵌编辑，直接写当前 Keymap）
    // ------------------------------------------------------------------

    /** 指定 action 的当前键盘快捷键（activeKeymap 中第一个 KeyboardShortcut；环境异常兜底 null）。 */
    private fun currentShortcut(actionId: String): KeyboardShortcut? = runCatching {
        KeymapManager.getInstance().activeKeymap
            .getShortcuts(actionId)
            .filterIsInstance<KeyboardShortcut>()
            .firstOrNull()
    }.getOrNull()

    /**
     * 应用快捷键（M14.3/14.4）：
     *  - 普通「使用」：仅绑定给本插件（移除自身旧绑定，不动其他 action）；
     *  - 「强制使用」：额外从**所有其他 action**（含 IDE 默认 keymap 继承绑定）移除该组合，保证独占始终生效。
     *
     * 抢占原理：IDEA keymap 支持"负绑定"——[Keymap.removeShortcut] 对继承自父级 keymap（$default）的
     * 快捷键会在当前（用户级）keymap 写入禁用覆盖，与 IDE Keymap 设置页"绑定冲突键时自动移除旧绑定"一致。
     */
    private fun applyShortcut(editor: ShortcutEditor, actionId: String) {
        runCatching {
            val keymap = KeymapManager.getInstance().activeKeymap
            // 1) 清理本 action 的旧键盘快捷键
            keymap.getShortcuts(actionId).filterIsInstance<KeyboardShortcut>()
                .forEach { keymap.removeShortcut(actionId, it) }
            // 2) 绑定新组合；「强制使用」时先抢占（从所有其他 action 移除，含默认继承绑定）
            val newShortcut = editor.shortcut
            if (newShortcut != null) {
                if (editor.forceOnApply) {
                    keymap.getActionIds().filter { it != actionId }.forEach { other ->
                        keymap.getShortcuts(other).filter { it == newShortcut }
                            .forEach { keymap.removeShortcut(other, it) }
                    }
                }
                keymap.addShortcut(actionId, newShortcut)
                editor.forceOnApply = false
            }
        }
    }

    /**
     * T-ShortcutSettings：打开平台 MCP Server 设置（定位到 SQL Visualizer 工具）。
     * 平台 Configurable 类名 = com.intellij.mcpserver.settings.McpServerSettingsConfigurable
     * （262 mcpserver.jar 常量池实证；注意包名 mcpserver 全小写）。
     * MCP Server 插件未安装时（243 / 未启用）提示并返回。
     */
    private fun openMcpServerSettings() {
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val configurableClass = runCatching {
            Class.forName("com.intellij.mcpserver.settings.McpServerSettingsConfigurable")
                as? Class<out com.intellij.openapi.options.Configurable>
        }.getOrNull()
        if (configurableClass == null) {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                project,
                MpSqlVisualizerBundle.message("settings.mcp.unavailable"),
                MpSqlVisualizerBundle.message("settings.mcp.title"),
            )
            return
        }
        runCatching<Unit> {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, configurableClass)
        }
    }

    /**
     * 语言切换后重建表单（设置页自身文案即时刷新；控件值从配置回读）。
     * 六组 gutter 复选框为字段级实例（reset/apply 引用，随 rebuild 复用不重建）——
     * 须在此显式刷新文本，否则切语言后残留旧语言文案（testShard3
     * PluginSettingsPersistenceTest.testLanguageWriteThroughRefreshesCheckboxTexts 真红实证）。
     */
    fun rebuild() {
        gutterGroupMapperXmlCheck.text = MpSqlVisualizerBundle.message("settings.gutterGroup.mapperXml")
        gutterGroupAnnotationCheck.text = MpSqlVisualizerBundle.message("settings.gutterGroup.annotation")
        gutterGroupJpaHqlCheck.text = MpSqlVisualizerBundle.message("settings.gutterGroup.jpaHql")
        gutterGroupJdbcCheck.text = MpSqlVisualizerBundle.message("settings.gutterGroup.jdbc")
        gutterGroupInlineFallbackCheck.text = MpSqlVisualizerBundle.message("settings.gutterGroup.inlineFallback")
        gutterGroupOtherCheck.text = MpSqlVisualizerBundle.message("settings.gutterGroup.other")
        // T-PerfSweep16 F46：字段级实例随 rebuild 复用，语言切换须显式刷文本（同 R2 六组口径）
        xmlSynHiCheck.text = MpSqlVisualizerBundle.message("settings.xmlSynHi")
        rootPanel.removeAll()
        buildSections(rootPanel)
        reset()
        refreshCacheInfo()
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    /**
     * M19：语言写穿入口（测试用）——选中语言值，触发与用户下拉选择**同一**写穿路径
     * （ActionListener → 立即写入配置 + 重建表单）。
     */
    internal fun selectLanguage(value: String) {
        languageCombo.selectedItem = languageOptions().firstOrNull { it.value == value } ?: return
        // selectedItem 变化会同步触发 ActionListener，写穿与 rebuild 由监听统一处理
    }

    /** T-xx：反向转换形态选择入口（测试用）——与下拉选择同一路径（selectedItem 按 equals 匹配）。 */
    internal fun selectReverseFormat(value: String) {
        reverseFormatCombo.selectedItem = reverseFormatOptions().firstOrNull { it.value == value } ?: return
    }

    /** T-CrudColor：标记形态下拉选择入口（测试用）——与用户选择同一路径。 */
    internal fun selectCrudColorStyle(value: String) {
        crudColorStyleCombo.selectedItem = crudColorStyleOptions().firstOrNull { it.value == value } ?: return
    }

    /** T-DisplayModeToSettings：显示模式下拉选择入口（测试用）——与用户选择同一路径。 */
    internal fun selectListDisplayMode(value: String) {
        logDisplayModeCombo.selectedItem = listDisplayModeOptions().firstOrNull { it.value == value } ?: return
    }

    /** T-DisplayModeToSettings：当前下拉选中值（测试断言 reset 回填用）。 */
    internal fun currentListDisplayModeForTest(): String = selectedListDisplayMode()

    /** T-CrudColor：四色色板设置入口（测试用）——与用户取色同一路径。 */
    internal fun setCrudColor(which: String, hex: String) {
        val color = colorOf(hex)
        when (which) {
            "select" -> crudColorSelectPanel.selectedColor = color
            "insert" -> crudColorInsertPanel.selectedColor = color
            "update" -> crudColorUpdatePanel.selectedColor = color
            "delete" -> crudColorDeletePanel.selectedColor = color
        }
    }

    /** T-M41 测试访问器：SQL→Java 快捷键编辑行（断言行存在 / 修改检测 / apply 写入 Keymap）。 */
    internal fun reverseShortcutEditorForTest(): ShortcutEditor = reverseShortcutEditor

    /**
     * T-TemplateKeyDrop 测试访问器：${table} 快捷模板三动作的快捷键编辑行。
     *
     * 三动作已摘除默认键位（原 Alt+Shift+E/C/T 与平台动作冲突、备选键亦被占），
     * 用例据此钉住「无默认键位」下录制入口仍可用（初始「—」→ 录制 → apply → reset）。
     */
    internal fun explainShortcutEditorForTest(): ShortcutEditor = explainShortcutEditor

    internal fun countShortcutEditorForTest(): ShortcutEditor = countShortcutEditor

    internal fun sampleShortcutEditorForTest(): ShortcutEditor = sampleShortcutEditor

    /** T-xx：启动自动监听复选框设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    internal fun setLogAutoCaptureCheck(checked: Boolean) {
        logAutoCaptureCheck.isSelected = checked
    }

    /** 手动选择实体类复选框设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    internal fun setManualEntityCheck(checked: Boolean) {
        manualEntityCheck.isSelected = checked
    }

    /** 后台扫描依赖库开关设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    internal fun setLibrarySweepCheck(checked: Boolean) {
        librarySweepCheck.isSelected = checked
    }

    /** T-PT-04 / O-79：空参数填充策略选择入口（测试用）——与用户点选同一路径（isModified/apply 检测）。 */
    internal fun selectEmptyParamFill(value: String) {
        when (value) {
            PluginSettings.EMPTY_PARAM_FILL_PLACEHOLDER -> emptyParamFillPlaceholderRadio.isSelected = true
            PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING -> emptyParamFillEmptyRadio.isSelected = true
            else -> emptyParamFillNullRadio.isSelected = true
        }
    }

    /** T-TenantLine：多租户注入开关设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    internal fun setInjectTenantLineCheck(checked: Boolean) {
        injectTenantLineCheck.isSelected = checked
    }

    /** T-UxOnboard T-UX-01：首用锚点引导开关设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    internal fun setOnboardingAnchorTipCheck(checked: Boolean) {
        onboardingAnchorTipCheck.isSelected = checked
    }

    /** T-PreviewWhitespaceToggle O-44a：空白符开关设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    internal fun setEditorShowWhitespacesCheck(checked: Boolean) {
        editorShowWhitespacesCheck.isSelected = checked
    }

    /** T-TenantLine：租户列名输入框设置入口（测试用）——与用户输入同一路径（isModified/apply 检测）。 */
    internal fun setTenantColumnName(value: String) {
        tenantColumnNameField.text = value
    }

    /**
     * 打开捕获历史目录。只解析/创建并打开目录，绝不枚举或删除其中任何文件；自定义目录与默认目录
     * 走 [SqlLogAutoSaver.resolveHistoryDir] 同一写端口径，避免设置页显示路径与真实落盘路径分叉。
     */
    private fun openCaptureHistoryDirectory() {
        val path = SqlLogAutoSaver.resolveHistoryDir(
            logAutoSavePathField.text,
            captureProjectBasePathProvider(),
        )
        val dir = File(path)
        runCatching {
            java.nio.file.Files.createDirectories(dir.toPath())
            captureDirectoryOpener(dir)
        }.onSuccess {
            storageActionStatusLabel.text = MpSqlVisualizerBundle.message(
                "settings.storage.captureFiles.opened",
                dir.absolutePath,
            )
            storageActionStatusLabel.toolTipText = dir.absolutePath
        }.onFailure {
            storageActionStatusLabel.text = MpSqlVisualizerBundle.message("settings.storage.captureFiles.open.failed")
            storageActionStatusLabel.toolTipText = it.message
        }
    }

    /** 清空预览 SQL 历史组件（普通/收藏条目及其标签、参数快照），不触碰共享 XML 的其他组件。 */
    private fun clearPreviewHistory() {
        if (!storageClearConfirmation(StorageClearKind.PREVIEW_HISTORY)) return
        runCatching { SqlHistoryManager.getInstance().clear() }
            .onSuccess {
                storageActionStatusLabel.text = MpSqlVisualizerBundle.message("settings.storage.previewHistory.cleared")
                storageActionStatusLabel.toolTipText = null
            }
            .onFailure {
                storageActionStatusLabel.text = MpSqlVisualizerBundle.message("settings.storage.clear.failed")
                storageActionStatusLabel.toolTipText = it.message
            }
    }

    /** 清空默认参数历史、全部命名方案与激活指针，并立即失效所有已打开预览面板的缓存实例。 */
    private fun clearParamHistory() {
        if (!storageClearConfirmation(StorageClearKind.PARAM_HISTORY)) return
        runCatching { ParamHistoryCache.clearPersistedHistory() }
            .onSuccess {
                storageActionStatusLabel.text = MpSqlVisualizerBundle.message("settings.storage.paramHistory.cleared")
                storageActionStatusLabel.toolTipText = null
            }
            .onFailure {
                storageActionStatusLabel.text = MpSqlVisualizerBundle.message("settings.storage.clear.failed")
                storageActionStatusLabel.toolTipText = it.message
            }
    }

    internal fun openCaptureDirectoryButtonForTest(): JButton = openCaptureDirectoryButton
    internal fun clearPreviewHistoryButtonForTest(): JButton = clearPreviewHistoryButton
    internal fun clearParamHistoryButtonForTest(): JButton = clearParamHistoryButton
    internal fun storageActionStatusForTest(): JBLabel = storageActionStatusLabel

    /**
     * 刷新插件缓存信息（大小 + 条目数，所有打开项目汇总——含反查/解析/Wrapper 预览三类缓存；
     * 面板构造与清除按钮共用）。
     */
    private fun refreshCacheInfo() {
        val projects = ProjectManager.getInstance().openProjects.toList()
        val total = PluginCacheManager.totalSizeBytes(projects)
        cacheInfoLabel.text = MpSqlVisualizerBundle.message(
            "settings.cache.info",
            PluginCacheManager.formatSize(total),
            PluginCacheManager.entryCount(projects),
        )
    }

    /** 测试访问器：缓存信息文本（断言展示与清除后刷新）。 */
    internal fun cacheInfoForTest(): String = cacheInfoLabel.text

    /** 测试访问器：清除缓存按钮（断言存在与点击行为）。 */
    internal fun clearCacheButtonForTest(): JButton = clearCacheButton

    /** 测试访问器：导入/导出行内反馈（20260907——文本/前景色/tooltip 三断言面）。 */
    internal fun statusForTest(): JBLabel = statusLabel

    // ------------------------------------------------------------------
    // F-44-7：SqlSourceInterceptor 自动注入
    // ------------------------------------------------------------------

    /**
     * 触发拦截器注入（用户勾选开关后调用）。
     *
     * T-JumpCapture：注入编排与四类结果弹窗收敛到 [SourceCaptureGuide]（与「跳转源码」
     * 引导弹框共用，防重入亦在其中）；面板侧仅保留勾选回滚——仅它持有 checkbox。
     */
    internal fun triggerInterceptorInjection() {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) {
            Messages.showWarningDialog(
                null,
                MpSqlVisualizerBundle.message("log.inject.noProject"),
                MpSqlVisualizerBundle.message("log.inject.title"),
            )
            autoInjectInterceptorCheck.isSelected = false
            return
        }
        SourceCaptureGuide.runInjection(project) { result ->
            SourceCaptureGuide.showInjectionResult(project, result)
            // T-SettingsTruth P1：纯动作开关——无论成败，终态复位未勾选（成功=动作完成即复位）
            autoInjectInterceptorCheck.isSelected = false
        }
    }

    // ------------------------------------------------------------------
    // 配置读写
    // ------------------------------------------------------------------

    /**
     * 根面板（Configurable.createComponent 返回）。
     */
    fun getRoot(): JComponent = rootPanel

    /**
     * 表单值是否与配置不一致（含快捷键编辑状态）。
     *
     * M19.1：语言**写穿**后表单与配置一致，但语言变更尚未「应用」——`languagePending`
     * 使 `isModified()` 保持 true，Apply 按钮在写穿后仍可点击（平台 Apply 启用 = isModified()）。
     */
    fun isModified(): Boolean {
        val s = settings.state
        return languagePending
            || (SECTION_PREVIEW in sections && isModifiedPreview(s))
            || (SECTION_TABLE_MAPPING in sections && isModifiedTableMapping(s))
            || (SECTION_CODE_GEN in sections && isModifiedCodeGen(s))
            || (SECTION_SQL_LOG in sections && isModifiedSqlLog(s))
            || (SECTION_UI in sections && isModifiedUi(s))
            || (SECTION_SHORTCUTS in sections && isModifiedShortcuts())
            || (SECTION_UPDATE_DIAGNOSTICS in sections && updateSection.autoCheckCheck.isSelected != s.autoCheckUpdate)
    }

    /** 全局设置不把首个打开项目的方言冒充其他项目；项目唯一时展示决策状态。 */
    internal fun refreshInferredLabel() {
        dialectInferredLabel.text = wrapPreviewText(when (dialectCombo.selectedItem as? String) {
            autoDialectItem -> {
                val projects = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
                val decision = projects.singleOrNull()?.let { DialectRegistry.decision(it) }
                DialectSettingsStatusText.describe(projects.size, decision)
            }
            else -> MpSqlVisualizerBundle.message("settings.dialect.manual", dialectCombo.selectedItem as? String ?: "")
        })
    }

    private fun isModifiedPreview(s: PluginSettings.State): Boolean =
        (dialectCombo.selectedItem == autoDialectItem) != s.dialectAuto
            || (dialectCombo.selectedItem != autoDialectItem && dialectCombo.selectedItem != s.dialect)
            || replaceParamsCheck.isSelected != s.replaceParams
            || formatCheck.isSelected != s.formatSql
            || selectedEmptyParamFill() != s.emptyParamFill
            || verticalPairAnnotationCheck.isSelected != s.verticalPairAnnotation
            || autoFoldCheck.isSelected != s.autoFoldEnabled
            || autoFoldThresholdField.text.trim().toIntOrNull() != s.autoFoldFieldThreshold
            || literalParamRecoveryCheck.isSelected != s.literalParamRecovery
            || injectLogicDeleteCheck.isSelected != s.injectLogicDelete
            || logicDeleteValueField.text.trim() != s.logicDeleteValue
            || injectTenantLineCheck.isSelected != s.injectTenantLine
            || tenantColumnNameField.text.trim() != s.tenantColumnName
            || tenantColumnDefaultField.text.trim() != s.tenantColumnDefault
            || injectTenantOnInsertCheck.isSelected != s.injectTenantOnInsert
            || dataPermissionHintCheck.isSelected != s.dataPermissionHint
            || dataPermissionMockConditionField.text.trim() != s.dataPermissionMockCondition
            || paginationSimCheck.isSelected != s.paginationSimEnabled
            || paginationCountCheck.isSelected != s.paginationCountEnabled
            || jdbcSqlPreviewCheck.isSelected != s.jdbcSqlPreviewEnabled
            || explainStructuredCheck.isSelected != s.explainStructuredRender
            || explainPlanRetentionCheck.isSelected != s.explainPlanRetentionEnabled
            || executionTimeoutField.text.trim().toIntOrNull() != s.executionTimeoutSeconds

    private fun isModifiedTableMapping(s: PluginSettings.State): Boolean =
        tablePrefixField.text.trim() != s.tablePrefix
            || tableSuffixField.text.trim() != s.tableSuffix
            || (inferenceCombo.selectedItem as? InferenceOption)?.value != s.tableNameInference
            || ignoredFieldsField.text.trim() != s.ignoredFields
            || removeTablePrefixField.text.trim() != s.removeTablePrefix
            || removeTableSuffixField.text.trim() != s.removeTableSuffix
            || columnCalibrationCheck.isSelected != s.columnCalibrationEnabled
            || (embeddedNamingCombo.selectedItem as? EmbeddedNamingOption)?.value != s.embeddedColumnNaming

    private fun isModifiedCodeGen(s: PluginSettings.State): Boolean =
        (reverseFormatCombo.selectedItem as? ReverseFormatOption)?.value != s.reverseWrapperFormat
            || manualEntityCheck.isSelected != s.reverseManualEntitySelection
            || librarySweepCheck.isSelected != s.libraryEntitySweepEnabled

    private fun isModifiedSqlLog(s: PluginSettings.State): Boolean =
        logSqlPrefixField.text.trim() != s.logSqlPrefix
            || logParamPrefixField.text.trim() != s.logParamPrefix
            || logMixedPrefixCheck.isSelected != s.logMixedPrefix
            || logAutoCaptureCheck.isSelected != s.logAutoCaptureOnStart
            || logAutoOpenCheck.isSelected != s.logAutoOpenOnCapture
            || logStopOnToolWindowCloseCheck.isSelected != s.logStopOnToolWindowClose
            || logTimestampCheck.isSelected != s.logTimestampEnabled
            || logTimestampFormatField.text.trim() != s.logTimestampFormat
            || (if (logTimestampSourceLogRadio.isSelected) "log" else "capture") != s.logTimestampSource
            || logFormatSqlCheck.isSelected != s.logFormatSql
            || logFilterKeywordsField.text.trim() != s.logFilterKeywords
            || logDedupeField.text.trim().toLongOrNull() != s.logDedupeWindowMs
            || logMaxParseField.text.trim().toIntOrNull() != s.logMaxParseLength
            || logMaxCountField.text.trim().toIntOrNull() != s.logMaxCount
            || logTimePrefixField.text.trim() != s.logTimePrefix
            || slowQueryThresholdField.text.trim().toLongOrNull() != s.slowQueryThresholdMs
            || logShowDurationCheck.isSelected != s.showDurationInLogList
            || dataSourceBadgeDetailCheck.isSelected != s.dataSourceBadgeDetail
            // M56 T-M56-08：增强捕获三行归位 SQL 日志区组后，改动判定随 sqlLog 分区
            || enhancedCaptureCheck.isSelected != s.enhancedCaptureEnabled
            || enhancedDesensitizeCheck.isSelected != s.enhancedCaptureDesensitize
            // T-MagpieGems W2：分级下拉 + 白名单文本域替代旧复选框的改动判定
            // （与访问器归一后的有效值比，防空哨兵未迁移态造成常驻假「已修改」）
            || selectedDisplayMaskMode() != PluginSettings.getInstance().displayMaskMode().name
            || displayMaskAllowListArea.text.trim() != s.displayMaskAllowList.trim()
            || stackMaxFramesField.text.trim().toIntOrNull() != s.enhancedCaptureStackMaxFrames
            || frameSkipPrefixesField.text.trim() != s.sourceFrameSkipPrefixes.trim()
            || bizJarPrefixesField.text.trim() != s.sourceFrameBizJarPrefixes.trim()
            || logHibernateEnabledCheck.isSelected != s.logHibernateEnabled
            || logHibernateSqlPrefixField.text.trim() != s.logHibernateSqlPrefix
            || logHibernateParamPrefixField.text.trim() != s.logHibernateParamPrefix
            || logJdbcEnabledCheck.isSelected != s.logJdbcEnabled
            || logShardingSphereEnabledCheck.isSelected != s.logShardingSphereEnabled
            || logAutoSaveCheck.isSelected != s.logAutoSaveEnabled
            || logAutoSavePathField.text.trim() != s.logAutoSavePath
            || logAutoSavePatternField.text.trim() != s.logAutoSaveFileNamePattern
            || (logAutoSaveMaxFilesField.text.trim().toIntOrNull() ?: PluginSettings.DEFAULT_LOG_AUTO_SAVE_MAX_FILES) != s.logAutoSaveMaxFiles
            // T-CrudColor：仅改样式或仅改某个颜色也必须能启用 Apply
            // （reverseWrapperFormat 漏挂 isModified 导致改动被平台静默丢弃的假开关事故，此处防同类回归）
            || selectedCrudColorStyle() != s.crudColorStyle
            || selectedListDisplayMode() != s.logListSqlDisplayMode
            || colorHexOf(crudColorSelectPanel) != s.crudColorSelect
            || colorHexOf(crudColorInsertPanel) != s.crudColorInsert
            || colorHexOf(crudColorUpdatePanel) != s.crudColorUpdate
            || colorHexOf(crudColorDeletePanel) != s.crudColorDelete

    private fun isModifiedUi(s: PluginSettings.State): Boolean =
        gutterIconCheck.isSelected != s.showGutterIcon ||
            gutterGroupMapperXmlCheck.isSelected != s.gutterGroupMapperXml ||
            gutterGroupAnnotationCheck.isSelected != s.gutterGroupAnnotation ||
            gutterGroupJpaHqlCheck.isSelected != s.gutterGroupJpaHql ||
            gutterGroupJdbcCheck.isSelected != s.gutterGroupJdbc ||
            gutterGroupInlineFallbackCheck.isSelected != s.gutterGroupInlineFallback ||
            gutterGroupOtherCheck.isSelected != s.gutterGroupOther
            // T-MapperNavCoexistYield：让位开关漏挂 isModified 会让勾选后 Apply 不启用（假开关回潮）
            || mapperNavYieldCheck.isSelected != s.mapperNavYieldToCodeHelper
            || xmlInlineSqlCheck.isSelected != s.xmlInlineSqlEnabled
            // T-PerfSweep16 F46：mapper XML 语法高亮开关变更即为 modified
            || xmlSynHiCheck.isSelected != s.xmlSynHiEnabled
            || joinDiagramNativeCheck.isSelected != s.joinDiagramNative
            || onboardingAnchorTipCheck.isSelected != s.onboardingAnchorTipEnabled
            // T-PreviewWhitespaceToggle O-44a：空白符开关（漏挂 isModified → 勾选后 Apply 不启用）
            || editorShowWhitespacesCheck.isSelected != s.editorShowWhitespaces
            || (languageCombo.selectedItem as? LanguageOption)?.value != s.language

    /** T-ShortcutSettings：5 个动作快捷键任一变更即为 modified。 */
    private fun isModifiedShortcuts(): Boolean =
        shortcutEditor.shortcut != currentShortcut(ConvertToSqlAction.ACTION_ID)
            || reverseShortcutEditor.shortcut != currentShortcut(ConvertSqlToJavaAction.ACTION_ID)
            || explainShortcutEditor.shortcut != currentShortcut(ExplainSelectedTableAction.ACTION_ID)
            || countShortcutEditor.shortcut != currentShortcut(CountSelectedTableAction.ACTION_ID)
            || sampleShortcutEditor.shortcut != currentShortcut(SampleSelectedTableAction.ACTION_ID)

    /**
     * 将表单值写回配置（Settings 点击 OK/Apply 时调用）；快捷键变化时写入 Keymap。
     *
     * @return 界面语言是否发生变化（true 时调用方应 [rebuild] 刷新设置页文案）
     */
    fun apply(): Boolean {
        val s = settings.state
        // M19.1：语言变更判定包含「待应用」标记——写穿后（combo 与 state 已一致）仍报告一次变更，
        // 触发调用方 rebuild；消费后清除标记（后续 apply 幂等）
        val languageChanged = languagePending || (languageCombo.selectedItem as? LanguageOption)?.value != s.language
        if (SECTION_PREVIEW in sections) {
            // T-DialectAutoInfer：哨兵项=推断模式；具体方言=手动模式（保留原值语义）
            val selected = dialectCombo.selectedItem as? String
            if (selected == autoDialectItem) {
                s.dialectAuto = true
            } else {
                s.dialectAuto = false
                s.dialect = selected ?: PluginSettings.DEFAULT_DIALECT
            }
            s.replaceParams = replaceParamsCheck.isSelected
            s.formatSql = formatCheck.isSelected
            // T-PT-04：空参数填充策略（NULL 字面量 / 空字符串）
            s.emptyParamFill = selectedEmptyParamFill()
            s.verticalPairAnnotation = verticalPairAnnotationCheck.isSelected
            s.autoFoldEnabled = autoFoldCheck.isSelected
            s.autoFoldFieldThreshold = autoFoldThresholdField.text.trim().toIntOrNull()
                ?: PluginSettings.DEFAULT_AUTO_FOLD_FIELD_THRESHOLD
            s.literalParamRecovery = literalParamRecoveryCheck.isSelected
            s.injectLogicDelete = injectLogicDeleteCheck.isSelected
            s.logicDeleteValue = logicDeleteValueField.text.trim()
            s.injectTenantLine = injectTenantLineCheck.isSelected
            s.tenantColumnName = tenantColumnNameField.text.trim()
            s.tenantColumnDefault = tenantColumnDefaultField.text.trim()
            s.injectTenantOnInsert = injectTenantOnInsertCheck.isSelected
            s.dataPermissionHint = dataPermissionHintCheck.isSelected
            s.dataPermissionMockCondition = dataPermissionMockConditionField.text.trim()
            s.paginationSimEnabled = paginationSimCheck.isSelected
            s.paginationCountEnabled = paginationCountCheck.isSelected
            s.jdbcSqlPreviewEnabled = jdbcSqlPreviewCheck.isSelected
            s.explainStructuredRender = explainStructuredCheck.isSelected
            s.explainPlanRetentionEnabled = explainPlanRetentionCheck.isSelected
            s.executionTimeoutSeconds = executionTimeoutField.text.trim().toIntOrNull()
                ?: PluginSettings.DEFAULT_EXECUTION_TIMEOUT_SECONDS
        }
        if (SECTION_TABLE_MAPPING in sections) {
            s.tablePrefix = tablePrefixField.text.trim()
            s.tableSuffix = tableSuffixField.text.trim()
            s.tableNameInference = (inferenceCombo.selectedItem as? InferenceOption)?.value ?: PluginSettings.INFERENCE_SNAKE
            s.ignoredFields = ignoredFieldsField.text.trim()
            s.removeTablePrefix = removeTablePrefixField.text.trim()
            s.removeTableSuffix = removeTableSuffixField.text.trim()
            s.columnCalibrationEnabled = columnCalibrationCheck.isSelected
            s.embeddedColumnNaming = (embeddedNamingCombo.selectedItem as? EmbeddedNamingOption)?.value
                ?: PluginSettings.EMBEDDED_NAMING_AUTO
        }
        if (SECTION_CODE_GEN in sections) {
            s.reverseWrapperFormat = (reverseFormatCombo.selectedItem as? ReverseFormatOption)?.value ?: PluginSettings.REVERSE_AUTO
            s.reverseManualEntitySelection = manualEntityCheck.isSelected
            s.libraryEntitySweepEnabled = librarySweepCheck.isSelected
        }
        if (SECTION_SQL_LOG in sections) {
            s.logSqlPrefix = logSqlPrefixField.text.trim()
            s.logParamPrefix = logParamPrefixField.text.trim()
            s.logMixedPrefix = logMixedPrefixCheck.isSelected
            s.logAutoCaptureOnStart = logAutoCaptureCheck.isSelected
            s.logAutoOpenOnCapture = logAutoOpenCheck.isSelected
            s.logStopOnToolWindowClose = logStopOnToolWindowCloseCheck.isSelected
            s.logTimestampEnabled = logTimestampCheck.isSelected
            s.logTimestampFormat = logTimestampFormatField.text.trim()
            s.logTimestampSource = if (logTimestampSourceLogRadio.isSelected) "log" else "capture"
            s.logFormatSql = logFormatSqlCheck.isSelected
            s.logFilterKeywords = logFilterKeywordsField.text.trim()
            s.logDedupeWindowMs = logDedupeField.text.trim().toLongOrNull() ?: PluginSettings.DEFAULT_LOG_DEDUPE_WINDOW_MS
            s.logMaxParseLength = logMaxParseField.text.trim().toIntOrNull() ?: PluginSettings.DEFAULT_LOG_MAX_PARSE_LENGTH
            s.logMaxCount = logMaxCountField.text.trim().toIntOrNull() ?: PluginSettings.DEFAULT_LOG_MAX_COUNT
            s.logTimePrefix = logTimePrefixField.text.trim()
            s.slowQueryThresholdMs = slowQueryThresholdField.text.trim().toLongOrNull()
                ?: PluginSettings.DEFAULT_SLOW_QUERY_THRESHOLD_MS
            s.showDurationInLogList = logShowDurationCheck.isSelected
            s.dataSourceBadgeDetail = dataSourceBadgeDetailCheck.isSelected
            // M56 T-M56-08：增强捕获三行归位 SQL 日志区组后，读写随 sqlLog 分区（preview 分支不再处理）
            s.enhancedCaptureEnabled = enhancedCaptureCheck.isSelected
            s.enhancedCaptureDesensitize = enhancedDesensitizeCheck.isSelected
            // T-MagpieGems W2：脱敏分级 + 白名单写回。旧开关同步为 mode != OFF——displayMaskEnabled
            // 自此只作迁移源（loadState/displayMaskMode() 归一读取），同步写让旧版本插件读新 XML 观感一致
            // （直取 displayMaskModeCombo 选中项，勿经 helper 中转——SettingsRealityGuardTest 断言二要求
            // 控件本体在 apply/reset 函数体内出现，防「摆而不绑」回归）
            val maskMode = maskModeOptions.getOrNull(displayMaskModeCombo.selectedIndex)?.value ?: "OFF"
            s.displayMaskMode = maskMode
            s.displayMaskAllowList = displayMaskAllowListArea.text.trim()
            s.displayMaskEnabled = maskMode != "OFF"
            // T-StackHoverUi：非法输入回缺省，越界钳位 [12,64]（agent 侧 setStackMaxFrames 同口径兜底）
            s.enhancedCaptureStackMaxFrames = (stackMaxFramesField.text.trim().toIntOrNull()
                ?: PluginSettings.DEFAULT_ENHANCED_CAPTURE_STACK_MAX_FRAMES)
                .coerceIn(
                    PluginSettings.MIN_ENHANCED_CAPTURE_STACK_MAX_FRAMES,
                    PluginSettings.MAX_ENHANCED_CAPTURE_STACK_MAX_FRAMES,
                )
            // T-SourceJumpFunnel P1-3：前缀规范化（逐行 trim、去空行）写回；下发走 SETTINGS_CHANGE_TOPIC
            s.sourceFrameSkipPrefixes = frameSkipPrefixesField.text.lines()
                .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            // T-ToolSiteBizFrame：反向名单同款规范化写回
            s.sourceFrameBizJarPrefixes = bizJarPrefixesField.text.lines()
                .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            s.logHibernateEnabled = logHibernateEnabledCheck.isSelected
            s.logHibernateSqlPrefix = logHibernateSqlPrefixField.text.trim()
            s.logHibernateParamPrefix = logHibernateParamPrefixField.text.trim()
            s.logJdbcEnabled = logJdbcEnabledCheck.isSelected
            s.logShardingSphereEnabled = logShardingSphereEnabledCheck.isSelected
            s.logAutoSaveEnabled = logAutoSaveCheck.isSelected
            s.logAutoSavePath = logAutoSavePathField.text.trim()
            s.logAutoSaveFileNamePattern = logAutoSavePatternField.text.trim()
                .ifEmpty { PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN }
            s.logAutoSaveMaxFiles = logAutoSaveMaxFilesField.text.trim().toIntOrNull()
                ?: PluginSettings.DEFAULT_LOG_AUTO_SAVE_MAX_FILES // T-HistoryFileRetention
            // T-CrudColor：CRUD 颜色标记（样式 + 四色）
            s.crudColorStyle = selectedCrudColorStyle()
            // T-DisplayModeToSettings：SQL 列表显示模式（apply 后经 SETTINGS_CHANGE_TOPIC 广播，两列表既有监听刷新）
            s.logListSqlDisplayMode = selectedListDisplayMode()
            s.crudColorSelect = colorHexOf(crudColorSelectPanel)
            s.crudColorInsert = colorHexOf(crudColorInsertPanel)
            s.crudColorUpdate = colorHexOf(crudColorUpdatePanel)
            s.crudColorDelete = colorHexOf(crudColorDeletePanel)
        }
        if (SECTION_UI in sections) {
            s.showGutterIcon = gutterIconCheck.isSelected
            s.gutterGroupMapperXml = gutterGroupMapperXmlCheck.isSelected
            s.gutterGroupAnnotation = gutterGroupAnnotationCheck.isSelected
            s.gutterGroupJpaHql = gutterGroupJpaHqlCheck.isSelected
            s.gutterGroupJdbc = gutterGroupJdbcCheck.isSelected
            s.gutterGroupInlineFallback = gutterGroupInlineFallbackCheck.isSelected
            s.gutterGroupOther = gutterGroupOtherCheck.isSelected
            s.mapperNavYieldToCodeHelper = mapperNavYieldCheck.isSelected
            s.xmlInlineSqlEnabled = xmlInlineSqlCheck.isSelected
            // T-PerfSweep16 F46：mapper XML 语法高亮开关写回（随 UI 分区）
            s.xmlSynHiEnabled = xmlSynHiCheck.isSelected
            s.joinDiagramNative = joinDiagramNativeCheck.isSelected
            // T-UxOnboard T-UX-01：首用锚点引导开关写回（随 UI 分区，读写判定同归属）
            s.onboardingAnchorTipEnabled = onboardingAnchorTipCheck.isSelected
            // T-PreviewWhitespaceToggle O-44a：空白符开关写回（随 UI 分区）
            s.editorShowWhitespaces = editorShowWhitespacesCheck.isSelected
            s.language = (languageCombo.selectedItem as? LanguageOption)?.value ?: PluginSettings.DEFAULT_LANGUAGE
        }
        // T-ShortcutSettings：5 个动作快捷键写入 Keymap（仅在变更时 apply）
        if (SECTION_SHORTCUTS in sections) {
            if (shortcutEditor.shortcut != currentShortcut(ConvertToSqlAction.ACTION_ID)) {
                applyShortcut(shortcutEditor, ConvertToSqlAction.ACTION_ID)
            }
            if (reverseShortcutEditor.shortcut != currentShortcut(ConvertSqlToJavaAction.ACTION_ID)) {
                applyShortcut(reverseShortcutEditor, ConvertSqlToJavaAction.ACTION_ID)
            }
            if (explainShortcutEditor.shortcut != currentShortcut(ExplainSelectedTableAction.ACTION_ID)) {
                applyShortcut(explainShortcutEditor, ExplainSelectedTableAction.ACTION_ID)
            }
            if (countShortcutEditor.shortcut != currentShortcut(CountSelectedTableAction.ACTION_ID)) {
                applyShortcut(countShortcutEditor, CountSelectedTableAction.ACTION_ID)
            }
            if (sampleShortcutEditor.shortcut != currentShortcut(SampleSelectedTableAction.ACTION_ID)) {
                applyShortcut(sampleShortcutEditor, SampleSelectedTableAction.ACTION_ID)
            }
        }
        if (SECTION_UPDATE_DIAGNOSTICS in sections) {
            s.autoCheckUpdate = updateSection.autoCheckCheck.isSelected
        }
        languagePending = false
        // 强制触发持久化：原地修改 state 属性后，框架可能不检测引用变化导致 XML 未写入（实证：
        // injectTenantLine=true 未持久化到 mp-sql-visualizer.xml，仅 showGutterIcon 等部分字段生效）。
        // 替换 state 引用使 XmlSerializer 检测到「状态变更」并在下一次持久化周期写入。
        LOG.info("PluginSettingsPanel.apply: state.after=${s.injectTenantLine}, tenantColumn=${s.tenantColumnName}, " +
                 "calling loadState(s.copy())")
        settings.loadState(s.copy())
        // 发布设置变更通知（订阅方如 SqlLogPanel 据此即时刷新 UI）
        ApplicationManager.getApplication().messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
        return languageChanged
    }

    /**
     * 从配置加载表单值（Settings 打开/Reset 时调用）。
     */
    fun reset() {
        val s = settings.state
        if (SECTION_PREVIEW in sections) {
            // T-DialectAutoInfer：auto→选中哨兵项；手动→选中方言名（不存在时回落首项哨兵）
            dialectCombo.selectedItem = if (s.dialectAuto) autoDialectItem else s.dialect
            if (dialectCombo.selectedItem != autoDialectItem && dialectCombo.selectedItem != s.dialect) {
                dialectCombo.selectedItem = autoDialectItem
            }
            refreshInferredLabel()
            replaceParamsCheck.isSelected = s.replaceParams
            formatCheck.isSelected = s.formatSql
            // T-PT-04 / O-79：空参数填充策略回填（三档互斥；存量「NULL」持久化值保持原档，
            // 空白/非法值落默认占位档——ButtonGroup 需至少一项选中）
            emptyParamFillPlaceholderRadio.isSelected = s.emptyParamFill == PluginSettings.EMPTY_PARAM_FILL_PLACEHOLDER
            emptyParamFillNullRadio.isSelected = s.emptyParamFill == PluginSettings.EMPTY_PARAM_FILL_NULL
            emptyParamFillEmptyRadio.isSelected = s.emptyParamFill == PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING
            if (!emptyParamFillPlaceholderRadio.isSelected && !emptyParamFillNullRadio.isSelected &&
                !emptyParamFillEmptyRadio.isSelected
            ) {
                emptyParamFillPlaceholderRadio.isSelected = true
            }
            verticalPairAnnotationCheck.isSelected = s.verticalPairAnnotation
            autoFoldCheck.isSelected = s.autoFoldEnabled
            autoFoldThresholdField.text = s.autoFoldFieldThreshold.toString()
            autoFoldThresholdField.isEnabled = s.autoFoldEnabled
            literalParamRecoveryCheck.isSelected = s.literalParamRecovery
            injectLogicDeleteCheck.isSelected = s.injectLogicDelete
            logicDeleteValueField.text = s.logicDeleteValue
            injectTenantLineCheck.isSelected = s.injectTenantLine
            tenantColumnNameField.text = s.tenantColumnName
            tenantColumnDefaultField.text = s.tenantColumnDefault
            injectTenantOnInsertCheck.isSelected = s.injectTenantOnInsert
            dataPermissionHintCheck.isSelected = s.dataPermissionHint
            dataPermissionMockConditionField.text = s.dataPermissionMockCondition
            paginationSimCheck.isSelected = s.paginationSimEnabled
            paginationCountCheck.isSelected = s.paginationCountEnabled
            jdbcSqlPreviewCheck.isSelected = s.jdbcSqlPreviewEnabled
            explainStructuredCheck.isSelected = s.explainStructuredRender
            explainPlanRetentionCheck.isSelected = s.explainPlanRetentionEnabled
            executionTimeoutField.text = s.executionTimeoutSeconds.toString()
        }
        if (SECTION_TABLE_MAPPING in sections) {
            tablePrefixField.text = s.tablePrefix
            tableSuffixField.text = s.tableSuffix
            inferenceCombo.selectedItem = inferenceOptions().firstOrNull { it.value == s.tableNameInference }
                ?: inferenceOptions().first()
            ignoredFieldsField.text = s.ignoredFields
            removeTablePrefixField.text = s.removeTablePrefix
            removeTableSuffixField.text = s.removeTableSuffix
            columnCalibrationCheck.isSelected = s.columnCalibrationEnabled
            embeddedNamingCombo.selectedItem = embeddedNamingOptions().firstOrNull { it.value == s.embeddedColumnNaming }
                ?: embeddedNamingOptions().first()
        }
        if (SECTION_CODE_GEN in sections) {
            reverseFormatCombo.selectedItem = reverseFormatOptions().firstOrNull { it.value == s.reverseWrapperFormat }
                ?: reverseFormatOptions().first()
            manualEntityCheck.isSelected = s.reverseManualEntitySelection
            librarySweepCheck.isSelected = s.libraryEntitySweepEnabled
        }
        if (SECTION_UI in sections) {
            gutterIconCheck.isSelected = s.showGutterIcon
        gutterGroupMapperXmlCheck.isSelected = s.gutterGroupMapperXml
        gutterGroupAnnotationCheck.isSelected = s.gutterGroupAnnotation
        gutterGroupJpaHqlCheck.isSelected = s.gutterGroupJpaHql
        gutterGroupJdbcCheck.isSelected = s.gutterGroupJdbc
        gutterGroupInlineFallbackCheck.isSelected = s.gutterGroupInlineFallback
        gutterGroupOtherCheck.isSelected = s.gutterGroupOther
        mapperNavYieldCheck.isSelected = s.mapperNavYieldToCodeHelper
            xmlInlineSqlCheck.isSelected = s.xmlInlineSqlEnabled
            // T-PerfSweep16 F46：mapper XML 语法高亮开关回填
            xmlSynHiCheck.isSelected = s.xmlSynHiEnabled
            joinDiagramNativeCheck.isSelected = s.joinDiagramNative
            onboardingAnchorTipCheck.isSelected = s.onboardingAnchorTipEnabled
            // T-PreviewWhitespaceToggle O-44a：空白符开关回填
            editorShowWhitespacesCheck.isSelected = s.editorShowWhitespaces
            languageCombo.selectedItem = languageOptions().firstOrNull { it.value == s.language } ?: languageOptions().first()
        }
        // T-ShortcutSettings：5 个动作快捷键从 Keymap 回读当前值
        if (SECTION_SHORTCUTS in sections) {
            shortcutEditor.shortcut = currentShortcut(ConvertToSqlAction.ACTION_ID)
            reverseShortcutEditor.shortcut = currentShortcut(ConvertSqlToJavaAction.ACTION_ID)
            explainShortcutEditor.shortcut = currentShortcut(ExplainSelectedTableAction.ACTION_ID)
            countShortcutEditor.shortcut = currentShortcut(CountSelectedTableAction.ACTION_ID)
            sampleShortcutEditor.shortcut = currentShortcut(SampleSelectedTableAction.ACTION_ID)
        }
        if (SECTION_UPDATE_DIAGNOSTICS in sections) {
            updateSection.resetState()
        }
        if (SECTION_SQL_LOG in sections) {
            logSqlPrefixField.text = s.logSqlPrefix
            logParamPrefixField.text = s.logParamPrefix
            logMixedPrefixCheck.isSelected = s.logMixedPrefix
            logAutoCaptureCheck.isSelected = s.logAutoCaptureOnStart
            logAutoOpenCheck.isSelected = s.logAutoOpenOnCapture
            logStopOnToolWindowCloseCheck.isSelected = s.logStopOnToolWindowClose
            logTimestampCheck.isSelected = s.logTimestampEnabled
            logTimestampFormatField.text = s.logTimestampFormat
            if (s.logTimestampSource == "capture") {
                logTimestampSourceCaptureRadio.isSelected = true
            } else {
                logTimestampSourceLogRadio.isSelected = true
            }
            logFormatSqlCheck.isSelected = s.logFormatSql
            logFilterKeywordsField.text = s.logFilterKeywords
            logDedupeField.text = s.logDedupeWindowMs.toString()
            logMaxParseField.text = s.logMaxParseLength.toString()
            logMaxCountField.text = s.logMaxCount.toString()
            logTimePrefixField.text = s.logTimePrefix
            slowQueryThresholdField.text = s.slowQueryThresholdMs.toString()
            logShowDurationCheck.isSelected = s.showDurationInLogList
            dataSourceBadgeDetailCheck.isSelected = s.dataSourceBadgeDetail
            // M56 T-M56-08：增强捕获三行归位 SQL 日志区组后，读写随 sqlLog 分区（preview 分支不再处理）
            enhancedCaptureCheck.isSelected = s.enhancedCaptureEnabled
            enhancedDesensitizeCheck.isSelected = s.enhancedCaptureDesensitize
            // T-MagpieGems W2：分级下拉 + 白名单文本域回填（模式经访问器归一后的有效值）
            displayMaskModeCombo.selectedIndex = maskModeOptions
                .indexOfFirst { it.value == PluginSettings.getInstance().displayMaskMode().name }
                .coerceAtLeast(0)
            displayMaskAllowListArea.text = s.displayMaskAllowList
            stackMaxFramesField.text = s.enhancedCaptureStackMaxFrames.toString()
            frameSkipPrefixesField.text = s.sourceFrameSkipPrefixes
            bizJarPrefixesField.text = s.sourceFrameBizJarPrefixes
            logHibernateEnabledCheck.isSelected = s.logHibernateEnabled
            logHibernateSqlPrefixField.text = s.logHibernateSqlPrefix
            logHibernateParamPrefixField.text = s.logHibernateParamPrefix
            logJdbcEnabledCheck.isSelected = s.logJdbcEnabled
            logShardingSphereEnabledCheck.isSelected = s.logShardingSphereEnabled
            logAutoSaveCheck.isSelected = s.logAutoSaveEnabled
            logAutoSavePathField.text = s.logAutoSavePath
            logAutoSavePatternField.text = s.logAutoSaveFileNamePattern
            logAutoSaveMaxFilesField.text = s.logAutoSaveMaxFiles.toString() // T-HistoryFileRetention
            val autoSaveEnabled = s.logAutoSaveEnabled
            logAutoSavePathField.isEnabled = autoSaveEnabled
            logAutoSaveBrowseButton.isEnabled = autoSaveEnabled
            logAutoSavePatternField.isEnabled = autoSaveEnabled
            logAutoSaveMaxFilesField.isEnabled = autoSaveEnabled // T-HistoryFileRetention
            updateAutoSavePreview()
            // T-CrudColor：CRUD 颜色标记控件回填
            crudColorStyleCombo.selectedItem = crudColorStyleOptions()
                .firstOrNull { it.value == s.crudColorStyle } ?: crudColorStyleOptions().first()
            // T-DisplayModeToSettings：显示模式下拉回填
            logDisplayModeCombo.selectedItem = listDisplayModeOptions()
                .firstOrNull { it.value == s.logListSqlDisplayMode } ?: listDisplayModeOptions().first()
            crudColorSelectPanel.selectedColor = colorOf(s.crudColorSelect)
            crudColorInsertPanel.selectedColor = colorOf(s.crudColorInsert)
            crudColorUpdatePanel.selectedColor = colorOf(s.crudColorUpdate)
            crudColorDeletePanel.selectedColor = colorOf(s.crudColorDelete)
            // T-SettingsTruth P1：autoInjectInterceptorCheck 为纯动作开关，不回填 state（不保存状态）
        }
        // T-SettingsTruth P2：页面打开/Reset 即见当前增强捕获状态
        if (SECTION_SQL_LOG in sections) refreshEnhancedStatusLabel()
    }

    // ------------------------------------------------------------------
    // F-43：自动保存历史 SQL 辅助方法
    // ------------------------------------------------------------------

    internal fun browseAutoSavePath() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(
            false, true, false, false, false, false
        ).withTitle(MpSqlVisualizerBundle.message("settings.log.autoSave.browse.tip"))
        val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByIoFile(java.io.File(System.getProperty("user.home")))
        val dir = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, null, baseDir)
        if (dir != null) {
            logAutoSavePathField.text = dir.path
        }
    }

    internal fun updateAutoSavePreview() {
        val pattern = logAutoSavePatternField.text.trim()
            .ifEmpty { PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN }
        val resolved = resolvePatternPreview(pattern)
        logAutoSavePatternPreview.text = MpSqlVisualizerBundle.message(
            "settings.log.autoSave.pattern.preview", resolved
        )
    }

    /** 解析模板用于预览（复用 SqlLogAutoSaver 的占位符规则）。 */
    private fun resolvePatternPreview(pattern: String): String {
        val now = System.currentTimeMillis()
        return pattern.replace(Regex("""\$([^$]+)\$""")) { mr ->
            val formatStr = mr.groupValues[1]
            try {
                val formatted = java.text.SimpleDateFormat(formatStr, java.util.Locale.ENGLISH).format(java.util.Date(now)).replace(':', '-')
                if (formatted == formatStr) mr.value else formatted
            } catch (_: Exception) {
                mr.value
            }
        }
    }

    // ------------------------------------------------------------------
    // O-5：设置导入/导出
    // ------------------------------------------------------------------

    /**
     * 从 JSON 文件导入设置（文件选择器 → 读取 → 覆盖当前全局配置 → 刷新表单）。
     * 20260907：结果反馈改面板内行内 label（原模态弹窗 parent=rootPanel 叠在 Settings 对话框之下，须关掉设置才可见）。
     */
    internal fun importSettings() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, false)
            .withFileFilter { it.extension?.lowercase() == "json" }
            .withTitle(MpSqlVisualizerBundle.message("settings.import"))
        val file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, null, null) ?: return
        // T-Perf-14：文件 IO + JSON 解析移后台（EDT 不做磁盘读）；结果回 EDT 刷新表单与行内反馈
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching {
                val text = String(file.contentsToByteArray(), Charsets.UTF_8)
                applyImportedText(text)
            }
            // write-safe: UI-only（纯 Swing/只读，无文档写）
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                outcome.onSuccess {
                    reset()
                    showStatus(MpSqlVisualizerBundle.message("settings.import.success"), isError = false)
                }.onFailure { e ->
                    showStatus(MpSqlVisualizerBundle.message("settings.import.failed", briefMessage(e)), isError = true, fullDetail = e.message)
                }
            }, com.intellij.openapi.application.ModalityState.any()) // 默认 modality 在模态 Settings 弹窗存续期不执行（真机实证反馈不出现）——对齐 M36.5.13 惯例
        }
    }

    /** 解析导入文本并覆盖当前全局配置（internal 供测试直调——文件选择器在 headless 下不可用）。 */
    internal fun applyImportedText(text: String) {
        PluginSettings.fromJson(text)
    }

    /**
     * 导出当前设置到 JSON 文件（文件选择器 → 写入）。
     * 20260907：结果反馈改面板内行内 label（同导入）。
     */
    internal fun exportSettings() {
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
            MpSqlVisualizerBundle.message("settings.export"),
            "JSON (*.json)",
            "json",
        )
        val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByIoFile(java.io.File(System.getProperty("user.home")))
        val wrapper = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance().createSaveFileDialog(descriptor, null)
        val saved = wrapper.save(baseDir, "mp-sql-visualizer-settings.json")
        if (saved != null) {
            // T-Perf-14：文件写入移后台（EDT 不做磁盘写）；结果回 EDT 行内反馈
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                val outcome = runCatching {
                    writeExportedFile(java.io.File(saved.file.absolutePath))
                }
                // write-safe: UI-only（纯 Swing/只读，无文档写）
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                    outcome.onSuccess {
                        showStatus(MpSqlVisualizerBundle.message("settings.export.success"), isError = false)
                    }.onFailure { e ->
                        showStatus(MpSqlVisualizerBundle.message("settings.export.failed", briefMessage(e)), isError = true, fullDetail = e.message)
                    }
                }, com.intellij.openapi.application.ModalityState.any()) // 同导入：模态 Settings 弹窗存续期照常回调
            }
        }
    }

    /** 写出当前设置 JSON（internal 供测试直调）。 */
    internal fun writeExportedFile(target: java.io.File) {
        target.writeText(PluginSettings.toJson(), Charsets.UTF_8)
    }

    /**
     * 行内反馈（20260907）：成功=默认前景色，失败=ErrorForeground 红字；完整异常详情放 tooltip。
     * 常驻可见（直到下次操作覆盖），不抢焦点、无遮挡。
     */
    private fun showStatus(text: String, isError: Boolean, fullDetail: String? = null) {
        statusLabel.text = text
        statusLabel.toolTipText = fullDetail ?: text
        statusLabel.foreground = if (isError) {
            com.intellij.ui.JBColor.namedColor("ErrorForeground", com.intellij.ui.JBColor.RED)
        } else {
            com.intellij.ui.JBColor.foreground()
        }
    }

    /** 异常摘要截断（行内 label 宽度有限，全量详情走 tooltip）。 */
    private fun briefMessage(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return if (msg.length > 120) msg.take(120) + "…" else msg
    }
}

