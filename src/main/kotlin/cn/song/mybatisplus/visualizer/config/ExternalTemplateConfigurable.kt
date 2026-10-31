package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * T-TemplateHook（FR-0045 第 3 项 MVP）：外部 SQL 模板渲染器设置页。
 *
 * 普通 [Configurable]（**不**继承 [SectionConfigurable]——那个基类绑定 [PluginSettingsPanel] 的
 * sections 模型，本页的字段属于独立存储 [ExternalTemplateSettings]）。字段＝开关 / 语句 tag 列表 /
 * 命令模板（多行）/ 超时 / 工作目录；apply 通过 [ExternalTemplateSettings.update] 写回状态，
 * 平台在设置对话框确定后落盘（与 [McpConfigurable] 的「改 state 即生效」同款）。
 *
 * 安全口径写在页内说明里（用户可见）：命令由用户自己配置、插件不校验内容；命令以参数列表执行
 * （不经 shell），占位符之外的字符串不会被解释。
 */
class ExternalTemplateConfigurable : Configurable {

    private var panel: JPanel? = null
    private var enabledCheckBox: JBCheckBox? = null
    private var statementTagsField: JBTextField? = null
    private var commandArea: JBTextArea? = null
    private var timeoutField: JBTextField? = null
    private var workingDirectoryField: JBTextField? = null

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("settings.section.externalTemplate")

    override fun createComponent(): JComponent {
        if (panel == null) panel = buildPanel()
        return panel!!
    }

    private fun buildPanel(): JPanel {
        val settings = runCatching { ExternalTemplateSettings.getInstance() }.getOrNull()

        val enabledCheckBox = JBCheckBox(
            MpSqlVisualizerBundle.message("settings.externalTemplate.enabled"),
            settings?.enabled ?: false,
        ).apply {
            toolTipText = MpSqlVisualizerBundle.message("settings.externalTemplate.enabled.tip")
        }
        this.enabledCheckBox = enabledCheckBox

        val statementTagsField = JBTextField(settings?.statementTags ?: ExternalTemplateSettings.DEFAULT_STATEMENT_TAGS).apply {
            toolTipText = MpSqlVisualizerBundle.message("settings.externalTemplate.statementTags.tip")
        }
        this.statementTagsField = statementTagsField

        val commandArea = JBTextArea(settings?.commandTemplate ?: "", 5, 48).apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = MpSqlVisualizerBundle.message("settings.externalTemplate.command.tip")
        }
        this.commandArea = commandArea

        val timeoutField = JBTextField((settings?.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS).toString(), 6).apply {
            toolTipText = MpSqlVisualizerBundle.message("settings.externalTemplate.timeout.tip")
        }
        this.timeoutField = timeoutField

        val workingDirectoryField = JBTextField(settings?.workingDirectory ?: "", 32).apply {
            toolTipText = MpSqlVisualizerBundle.message("settings.externalTemplate.workingDirectory.tip")
        }
        this.workingDirectoryField = workingDirectoryField

        val help = JBLabel(MpSqlVisualizerBundle.message("settings.externalTemplate.placeholders")).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10)
            add(
                FormBuilder.createFormBuilder()
                    .addComponent(enabledCheckBox)
                    .addLabeledComponent(
                        MpSqlVisualizerBundle.message("settings.externalTemplate.statementTags"),
                        statementTagsField,
                    )
                    .addLabeledComponent(
                        MpSqlVisualizerBundle.message("settings.externalTemplate.command"),
                        commandArea,
                    )
                    .addComponentToRightColumn(help, 2)
                    .addLabeledComponent(
                        MpSqlVisualizerBundle.message("settings.externalTemplate.timeout"),
                        timeoutField,
                    )
                    .addLabeledComponent(
                        MpSqlVisualizerBundle.message("settings.externalTemplate.workingDirectory"),
                        workingDirectoryField,
                    )
                    .addComponentFillVertically(JPanel(), 0)
                    .panel,
                BorderLayout.NORTH,
            )
        }
    }

    private fun timeoutOrNull(): Int? = timeoutField?.text?.trim()?.toIntOrNull()

    override fun isModified(): Boolean {
        val settings = runCatching { ExternalTemplateSettings.getInstance() }.getOrNull() ?: return false
        return (enabledCheckBox?.isSelected ?: settings.enabled) != settings.enabled ||
            (statementTagsField?.text ?: settings.statementTags) != settings.statementTags ||
            (commandArea?.text ?: settings.commandTemplate) != settings.commandTemplate ||
            (timeoutOrNull() ?: settings.timeoutSeconds) != settings.timeoutSeconds ||
            (workingDirectoryField?.text ?: settings.workingDirectory) != settings.workingDirectory
    }

    override fun apply() {
        val settings = runCatching { ExternalTemplateSettings.getInstance() }.getOrNull() ?: return
        settings.update(
            enabled = enabledCheckBox?.isSelected ?: false,
            statementTags = statementTagsField?.text ?: ExternalTemplateSettings.DEFAULT_STATEMENT_TAGS,
            commandTemplate = commandArea?.text ?: "",
            // 非法数字回落当前值（不在 apply 里弹窗——超时钳位由 update 负责）
            timeoutSeconds = timeoutOrNull() ?: settings.timeoutSeconds,
            workingDirectory = workingDirectoryField?.text ?: "",
        )
    }

    override fun reset() {
        val settings = runCatching { ExternalTemplateSettings.getInstance() }.getOrNull() ?: return
        enabledCheckBox?.isSelected = settings.enabled
        statementTagsField?.text = settings.statementTags
        commandArea?.text = settings.commandTemplate
        timeoutField?.text = settings.timeoutSeconds.toString()
        workingDirectoryField?.text = settings.workingDirectory
    }

    override fun disposeUIResources() {
        panel = null
        enabledCheckBox = null
        statementTagsField = null
        commandArea = null
        timeoutField = null
        workingDirectoryField = null
    }

    // ------------------------------------------------------------------
    // 测试访问器（同模块 internal）
    // ------------------------------------------------------------------

    internal fun enabledCheckBoxForTest(): JBCheckBox? = enabledCheckBox

    internal fun statementTagsFieldForTest(): JBTextField? = statementTagsField

    internal fun commandAreaForTest(): JBTextArea? = commandArea

    internal fun timeoutFieldForTest(): JBTextField? = timeoutField

    internal fun workingDirectoryFieldForTest(): JBTextField? = workingDirectoryField

    private companion object {
        /** 页内默认超时（无服务环境下的展示值，与 [ExternalTemplateSettings] 出厂值一致）。 */
        const val DEFAULT_TIMEOUT_SECONDS = 10
    }
}
