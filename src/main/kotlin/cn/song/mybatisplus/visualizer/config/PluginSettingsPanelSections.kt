package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.actions.ConvertSqlToJavaAction
import cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction
import cn.song.mybatisplus.visualizer.actions.ExplainSelectedTableAction
import cn.song.mybatisplus.visualizer.actions.CountSelectedTableAction
import cn.song.mybatisplus.visualizer.actions.SampleSelectedTableAction
import cn.song.mybatisplus.visualizer.log.SourceCaptureGuide
import cn.song.mybatisplus.visualizer.log.SqlListTypeColor
import cn.song.mybatisplus.visualizer.log.agent.AgentManager
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

// 扩展函数看不到接收者类的 companion 成员，显式 import 引入：
import cn.song.mybatisplus.visualizer.config.PluginSettingsPanel.Companion.SECTION_CODE_GEN
import cn.song.mybatisplus.visualizer.config.PluginSettingsPanel.Companion.SECTION_PREVIEW
import cn.song.mybatisplus.visualizer.config.PluginSettingsPanel.Companion.SECTION_SHORTCUTS
import cn.song.mybatisplus.visualizer.config.PluginSettingsPanel.Companion.SECTION_SQL_LOG
import cn.song.mybatisplus.visualizer.config.PluginSettingsPanel.Companion.SECTION_TABLE_MAPPING
import cn.song.mybatisplus.visualizer.config.PluginSettingsPanel.Companion.SECTION_UI
import cn.song.mybatisplus.visualizer.config.PluginSettingsPanel.Companion.SECTION_UPDATE_DIAGNOSTICS

// T-M53-II（2026-10-10）：以下为 PluginSettingsPanel 的「设置页各分区构建」内聚成员，
// 以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。

/**
 * 使用 Swing 原生 HTML 换行，保留完整文案（尤其是 R90 的事前隐私披露）。
 * 480 个逻辑像素给默认 Settings 约 700px 内容区留出边框、复选图标和滚动条；
 * 更窄窗口仍交给 Settings 自带的滚动容器，不通过裁字或 tooltip 隐藏说明。
 */
internal fun wrapPreviewText(text: String): String {
    val body = if (text.startsWith("<html>", ignoreCase = true)) {
        text.removePrefix("<html>").removeSuffix("</html>")
    } else {
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
    return "<html><table width='${JBUI.scale(480)}' cellpadding='0' cellspacing='0'><tr><td>$body</td></tr></table></html>"
}

internal fun PluginSettingsPanel.buildSections(panel: JPanel) {
    val gbc = GridBagConstraints().apply {
        gridx = 0
        gridy = 0
        anchor = GridBagConstraints.WEST
        insets = Insets(4, 6, 4, 6)
    }

    fun addRow(panel: JPanel, gbc: GridBagConstraints, label: String, component: JComponent, fill: Int = GridBagConstraints.NONE) {
        // 独立开关和事前披露不是字段值，不应占据第二列、继承标签列的缩进。
        // 仅 SQL Preview / Code Generation 使用整行；其余页保留原有排布。
        if (component === reverseFormatCombo) {
            gbc.gridx = 0
            gbc.gridwidth = 2
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(JBLabel(label), gbc)
            gbc.gridy++
            panel.add(component, gbc)
            gbc.gridy++
            gbc.gridwidth = 1
            gbc.fill = GridBagConstraints.NONE
            return
        }
        if (label.isEmpty() && panel.getClientProperty("previewFullWidthRows") == true) {
            when (component) {
                is JBCheckBox -> component.text = wrapPreviewText(component.text)
                is JBLabel -> component.text = wrapPreviewText(component.text)
            }
            gbc.gridx = 0
            gbc.gridwidth = 2
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(component, gbc)
            gbc.gridwidth = 1
            gbc.fill = GridBagConstraints.NONE
            gbc.gridy++
            return
        }
        gbc.gridx = 0
        gbc.weightx = 0.0
        panel.add(JBLabel(label), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = fill
        panel.add(component, gbc)
        gbc.fill = GridBagConstraints.NONE
        gbc.gridy++
    }

    /** 分隔线 + 内容面板（无边框、无折叠）。 */
    fun sectionGroup(
        titleKey: String,
        content: (JPanel, GridBagConstraints) -> Unit,
    ) {
        val contentPanel = JPanel(GridBagLayout()).apply {
            putClientProperty("previewFullWidthRows", titleKey in setOf("settings.section.preview", "settings.section.codeGen"))
        }
        val inner = GridBagConstraints().apply {
            gridx = 0; gridy = 0; anchor = GridBagConstraints.WEST; insets = Insets(2, 6, 2, 6)
        }
        content(contentPanel, inner)

        // 只统计真正的首列标签。第二列状态/披露及跨列说明绝不能反过来撑大首列。
        val layout = contentPanel.layout as GridBagLayout
        var maxLabelWidth = 0
        for (comp in contentPanel.components) {
            val constraints = layout.getConstraints(comp)
            if (comp is JBLabel && constraints.gridx == 0 && constraints.gridwidth == 1) {
                maxLabelWidth = maxOf(maxLabelWidth, comp.preferredSize.width)
            }
        }
        if (maxLabelWidth > 0) {
            layout.columnWidths = intArrayOf(maxLabelWidth + 8, 0)
        }

        // 分隔线（仅横线，无边框）
        gbc.gridx = 0; gbc.gridwidth = 1; gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(TitledSeparator(MpSqlVisualizerBundle.message(titleKey)), gbc)
        gbc.gridy++

        // 内容面板
        gbc.gridx = 0; gbc.gridwidth = 1; gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(contentPanel, gbc)
        gbc.gridy++
    }

    // 组 1：SQL 预览（默认展开）
    if (SECTION_PREVIEW in sections) {
        sectionGroup("settings.section.preview") { p, g ->
            addRow(p, g, MpSqlVisualizerBundle.message("settings.dialect"), dialectCombo)
            addRow(p, g, "", dialectInferredLabel)
            // T-DialectAutoInfer：下拉变化一律刷新推断状态行。
            // 2026-10-27 修（T-DialectLabelStale）：原实现只在「切回哨兵项」时刷新，
            // 切到具体方言时状态行不动——表现为下拉已变 Oracle、副标题仍停在
            // 「项目候选方言不一致 / 手动指定: MySQL」的旧文案，直到面板重绘或
            // apply 后的 reset() 才追上（用户真机复现）。状态行内容完全由
            // selectedItem 决定（见 refreshInferredLabel），故任何变化都应重算。
            dialectCombo.addActionListener { refreshInferredLabel() }
            addRow(p, g, "", replaceParamsCheck.apply { text = MpSqlVisualizerBundle.message("settings.replaceParams") })
            // T-PT-04 / O-79：空参数填充策略（参数值为空时保留 ? 占位，还是回填 NULL/空字符串）
            emptyParamFillPlaceholderRadio.apply {
                text = MpSqlVisualizerBundle.message("settings.emptyParamFill.placeholder")
                toolTipText = MpSqlVisualizerBundle.message("settings.emptyParamFill.placeholder.tip")
            }
            emptyParamFillNullRadio.apply {
                text = MpSqlVisualizerBundle.message("settings.emptyParamFill.null")
                toolTipText = MpSqlVisualizerBundle.message("settings.emptyParamFill.null.tip")
            }
            emptyParamFillEmptyRadio.apply {
                text = MpSqlVisualizerBundle.message("settings.emptyParamFill.empty")
                toolTipText = MpSqlVisualizerBundle.message("settings.emptyParamFill.empty.tip")
            }
            emptyParamFillGroup.add(emptyParamFillPlaceholderRadio)
            emptyParamFillGroup.add(emptyParamFillNullRadio)
            emptyParamFillGroup.add(emptyParamFillEmptyRadio)
            addRow(p, g, MpSqlVisualizerBundle.message("settings.emptyParamFill"), JPanel(java.awt.GridLayout(0, 1)).apply {
                add(emptyParamFillPlaceholderRadio)
                add(emptyParamFillNullRadio)
                add(emptyParamFillEmptyRadio)
            })
            addRow(p, g, "", formatCheck.apply { text = MpSqlVisualizerBundle.message("settings.formatSql") })
            addRow(p, g, "", verticalPairAnnotationCheck.apply { text = MpSqlVisualizerBundle.message("settings.verticalPairAnnotation") })
            addRow(p, g, "", autoFoldCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.autoFold.enabled")
                toolTipText = MpSqlVisualizerBundle.message("settings.autoFold.enabled.tip")
                addActionListener { autoFoldThresholdField.isEnabled = isSelected }
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.autoFold.threshold"), autoFoldThresholdField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.autoFold.threshold.tip")
            })
            addRow(p, g, "", literalParamRecoveryCheck.apply { text = MpSqlVisualizerBundle.message("settings.literalParamRecovery") })
            addRow(p, g, "", injectLogicDeleteCheck.apply { text = MpSqlVisualizerBundle.message("settings.injectLogicDelete") })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.logicDeleteValue"), logicDeleteValueField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.logicDeleteValue.tip")
            })
            addRow(p, g, "", injectTenantLineCheck.apply { text = MpSqlVisualizerBundle.message("settings.injectTenantLine") })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.tenantColumnName"), tenantColumnNameField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.tenantColumnName.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.tenantColumnDefault"), tenantColumnDefaultField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.tenantColumnDefault.tip")
            })
            addRow(p, g, "", injectTenantOnInsertCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.injectTenantOnInsert")
                toolTipText = MpSqlVisualizerBundle.message("settings.injectTenantOnInsert.tip")
            })
            addRow(p, g, "", dataPermissionHintCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.dataPermissionHint")
                toolTipText = MpSqlVisualizerBundle.message("settings.dataPermissionHint.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.dataPermissionMockCondition"), dataPermissionMockConditionField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.dataPermissionMockCondition.tip")
            })
            addRow(p, g, "", paginationSimCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.paginationSim")
                toolTipText = MpSqlVisualizerBundle.message("settings.paginationSim.tip")
            })
            addRow(p, g, "", paginationCountCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.paginationCount")
                toolTipText = MpSqlVisualizerBundle.message("settings.paginationCount.tip")
            })
            addRow(p, g, "", jdbcSqlPreviewCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.preview.jdbc")
                toolTipText = MpSqlVisualizerBundle.message("settings.preview.jdbc.tip")
            })
            addRow(p, g, "", explainStructuredCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.explain.structured")
                toolTipText = MpSqlVisualizerBundle.message("settings.explain.structured.tip")
            })
            addRow(p, g, "", explainPlanRetentionCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.explain.retention")
            })
            addRow(p, g, "", explainPlanRetentionDisclosure.apply {
                text = MpSqlVisualizerBundle.message("settings.explain.retention.disclosure")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.preview.executionTimeout"), executionTimeoutField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.preview.executionTimeout.tip")
            })
        }
    }

    // 组 2：表名映射（默认折叠）
    if (SECTION_TABLE_MAPPING in sections) {
        sectionGroup("settings.section.tableMapping") { p, g ->
            addRow(p, g, MpSqlVisualizerBundle.message("settings.tablePrefix"), tablePrefixField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.tablePrefix.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.tableSuffix"), tableSuffixField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.tableSuffix.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.removeTablePrefix"), removeTablePrefixField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.removeTablePrefix.tip")
                emptyText.text = MpSqlVisualizerBundle.message("settings.separatorHint")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.removeTableSuffix"), removeTableSuffixField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.removeTableSuffix.tip")
                emptyText.text = MpSqlVisualizerBundle.message("settings.separatorHint")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.ignoredFields"), ignoredFieldsField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.ignoredFields.tip")
                emptyText.text = MpSqlVisualizerBundle.message("settings.separatorHint")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.tableNameInference"), inferenceCombo.apply {
                model = DefaultComboBoxModel(inferenceOptions())
                toolTipText = MpSqlVisualizerBundle.message("settings.tableNameInference.tip")
                selectedItem = inferenceOptions().firstOrNull { it.value == settings.state.tableNameInference }
                    ?: inferenceOptions().first()
            })
            addRow(p, g, "", columnCalibrationCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.columnCalibration")
                toolTipText = MpSqlVisualizerBundle.message("settings.columnCalibration.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.embeddedColumnNaming"), embeddedNamingCombo.apply {
                model = DefaultComboBoxModel(embeddedNamingOptions())
                toolTipText = MpSqlVisualizerBundle.message("settings.embeddedColumnNaming.tip")
                selectedItem = embeddedNamingOptions().firstOrNull { it.value == settings.state.embeddedColumnNaming }
                    ?: embeddedNamingOptions().first()
            })
        }
    }

    // 组 3：代码生成（默认折叠）
    if (SECTION_CODE_GEN in sections) {
        sectionGroup("settings.section.codeGen") { p, g ->
            addRow(p, g, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat"), reverseFormatCombo.apply {
                model = DefaultComboBoxModel(reverseFormatOptions())
                // 选项包含完整解释，使用同一多行 renderer 保留选中值和下拉列表的全文。
                renderer = object : javax.swing.DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
                    ): Component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
                        text = wrapPreviewText(value?.toString().orEmpty())
                    }
                }
                toolTipText = MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.tip")
                selectedItem = reverseFormatOptions().firstOrNull { it.value == settings.state.reverseWrapperFormat }
                    ?: reverseFormatOptions().first()
            })
            addRow(p, g, "", manualEntityCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.reverseManualEntitySelection")
                toolTipText = MpSqlVisualizerBundle.message("settings.reverseManualEntitySelection.tip")
            })
            addRow(p, g, "", librarySweepCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.libraryEntitySweep")
                toolTipText = MpSqlVisualizerBundle.message("settings.libraryEntitySweep.tip")
            })
        }
    }

    // 组 4：SQL 日志（分割线分隔 3 个子分组）
    if (SECTION_SQL_LOG in sections) {
        sectionGroup("settings.log.tab.mybatis") { p, g ->
            // P1-4 摩擦 #3（批次9 波次2）：帧过滤前缀入口指向说明——设置项本体留在页末
            // 「源码捕获」组（与 JAR 业务帧反向名单同域成对、SearchableField 已按域索引，
            // 搬动即拆散配对语义），按任务定案取侵入最小形态：本页首组顶加一行零交互指向
            // 说明（R20 落地页提示形态，零监听器），让从上往下扫的用户在第一屏即得知该
            // 能力在场。说明行跨两列直挂（不经 addRow 的首列标签位），且 JBLabel 必须包一
            // 层面板——sectionGroup 的 maxLabelWidth 列宽计算扫描全部直接 JBLabel 子件，
            // 长说明行入列会把组内首列撑到整行宽、其余行字段列被挤没。
            g.gridx = 0
            g.gridwidth = 2
            g.weightx = 1.0
            g.fill = GridBagConstraints.HORIZONTAL
            p.add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(
                        JBLabel(
                            wrapPreviewText(MpSqlVisualizerBundle.message("settings.sourceFrameSkipPrefixes.hint")),
                        ).apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND },
                        BorderLayout.CENTER,
                    )
                },
                g,
            )
            g.gridwidth = 1
            g.gridy++
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.sqlPrefix"), logSqlPrefixField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.log.sqlPrefix.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.paramPrefix"), logParamPrefixField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.log.paramPrefix.tip")
            })
            addRow(p, g, "", logMixedPrefixCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.log.mixedPrefix")
                toolTipText = MpSqlVisualizerBundle.message("settings.log.mixedPrefix.tip")
            })
            addRow(p, g, "", logAutoCaptureCheck.apply { text = MpSqlVisualizerBundle.message("settings.log.autoCapture") })
            addRow(p, g, "", logAutoOpenCheck.apply { text = MpSqlVisualizerBundle.message("settings.log.autoOpen") })
            addRow(p, g, "", logStopOnToolWindowCloseCheck.apply { text = MpSqlVisualizerBundle.message("settings.log.stopOnToolWindowClose") })
            addRow(p, g, "", logTimestampCheck.apply { text = MpSqlVisualizerBundle.message("settings.log.timestamp") })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.timestampFormat"), logTimestampFormatField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.log.timestampFormat.tip")
            })
            // 时间来源单选组（日志时间 / 捕获时间）
            logTimestampSourceLogRadio.apply {
                text = MpSqlVisualizerBundle.message("settings.log.timestampSource.log")
                toolTipText = MpSqlVisualizerBundle.message("settings.log.timestampSource.log.tip")
            }
            logTimestampSourceCaptureRadio.apply {
                text = MpSqlVisualizerBundle.message("settings.log.timestampSource.capture")
                toolTipText = MpSqlVisualizerBundle.message("settings.log.timestampSource.capture.tip")
            }
            logTimestampSourceGroup.add(logTimestampSourceLogRadio)
            logTimestampSourceGroup.add(logTimestampSourceCaptureRadio)
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.timestampSource"), JPanel().apply {
                add(logTimestampSourceLogRadio)
                add(logTimestampSourceCaptureRadio)
            })
            addRow(p, g, "", logFormatSqlCheck.apply { text = MpSqlVisualizerBundle.message("settings.log.formatSql") })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.filterKeywords"), logFilterKeywordsField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.log.filterKeywords.tip")
                emptyText.text = MpSqlVisualizerBundle.message("settings.separatorHint")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.dedupeWindow"), logDedupeField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.log.dedupeWindow.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.maxParseLength"), logMaxParseField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.log.maxParseLength.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.maxCount"), logMaxCountField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.log.maxCount.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.timePrefix"), logTimePrefixField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.log.timePrefix.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.slowQueryThreshold"), slowQueryThresholdField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.log.slowQueryThreshold.tip")
            })
            addRow(p, g, "", logShowDurationCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.log.showDuration")
                toolTipText = MpSqlVisualizerBundle.message("settings.log.showDuration.tip")
            })
            // T-DataSourceBadgeMode：数据源角标显示粒度（默认仅数据库类型，打开后带连接事实）
            addRow(p, g, "", dataSourceBadgeDetailCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.log.dataSourceBadgeDetail")
                toolTipText = MpSqlVisualizerBundle.message("settings.log.dataSourceBadgeDetail.tip")
            })
        }

        // T-DisplayModeToSettings：SQL 列表显示模式（SQL 日志 + SQL 历史 两列表共享一键，设置页唯一入口）
        sectionGroup("settings.log.tab.list") { p, g ->
            addRow(p, g, MpSqlVisualizerBundle.message("log.list.displayMode"), logDisplayModeCombo.apply {
                model = DefaultComboBoxModel(listDisplayModeOptions())
                toolTipText = MpSqlVisualizerBundle.message("log.list.displayMode.tip")
            })
        }

        // T-CrudColor：SQL 列表 CRUD 类型颜色标记（样式 + 四色；不新增复选框，保持复选框台账不变）
        sectionGroup("settings.log.tab.crudColor") { p, g ->
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.style"), crudColorStyleCombo.apply {
                model = DefaultComboBoxModel(crudColorStyleOptions())
                toolTipText = MpSqlVisualizerBundle.message("settings.log.crudColor.style.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.select"), crudColorSelectPanel)
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.insert"), crudColorInsertPanel)
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.update"), crudColorUpdatePanel)
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.delete"), crudColorDeletePanel)
            addRow(p, g, "", crudColorResetButton.apply {
                text = MpSqlVisualizerBundle.message("settings.log.crudColor.reset")
                toolTipText = MpSqlVisualizerBundle.message("settings.log.crudColor.reset.tip")
                addActionListener {
                    crudColorSelectPanel.selectedColor = colorOf(PluginSettings.CRUD_COLOR_DEFAULT_SELECT)
                    crudColorInsertPanel.selectedColor = colorOf(PluginSettings.CRUD_COLOR_DEFAULT_INSERT)
                    crudColorUpdatePanel.selectedColor = colorOf(PluginSettings.CRUD_COLOR_DEFAULT_UPDATE)
                    crudColorDeletePanel.selectedColor = colorOf(PluginSettings.CRUD_COLOR_DEFAULT_DELETE)
                }
            })
        }

        sectionGroup("settings.log.tab.orm") { p, g ->
            addRow(p, g, "", logHibernateEnabledCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.log.hibernate.enabled")
                toolTipText = MpSqlVisualizerBundle.message("settings.log.hibernate.enabled.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.hibernate.sqlPrefix"), logHibernateSqlPrefixField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.log.hibernate.sqlPrefix.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.hibernate.paramPrefix"), logHibernateParamPrefixField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.log.hibernate.paramPrefix.tip")
            })
            addRow(p, g, "", logJdbcEnabledCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.log.jdbc")
                toolTipText = MpSqlVisualizerBundle.message("settings.log.jdbc.tip")
            })
            // R10：ShardingSphere 双段日志捕获（Logic 主条目 + Actual 折叠附注；默认开）
            addRow(p, g, "", logShardingSphereEnabledCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.log.shardingsphere.enabled")
                toolTipText = MpSqlVisualizerBundle.message("settings.log.shardingsphere.enabled.tip")
            })
        }

        sectionGroup("settings.log.tab.autoSave") { p, g ->
            // 三处本地持久化彼此独立；说明始终可见，不随文件自动保存开关禁用。
            // 包一层面板避免 sectionGroup 将长说明误当首列标签撑宽整个设置页。
            fun disclosure(key: String, action: JButton? = null) {
                g.gridx = 0
                g.gridwidth = 2
                g.weightx = 1.0
                // UI024：本行不随整页拉宽。页面其他分区（源码捕获分栏等）的 preferred 宽度仍可能
                // 大于视口；若本行 fill=HORIZONTAL，右对齐按钮会被推到页面右缘之外而裁切。
                g.fill = GridBagConstraints.NONE
                // UI024：说明与操作纵向排列（操作右对齐换到下一行）。原先说明固定 500px、操作在
                // EAST 同行，两者宽度相加把「自动保存」组 preferred 宽度撑到 848px（整页 894px），
                // 默认设置窗口内容区下三个按钮被右缘裁切并出现横向滚动条；换行后行宽只取两者
                // 最大值（约 650px），且不随页面溢出宽度移动，按钮始终落在可视区内。
                p.add(JPanel(BorderLayout(0, 4)).apply {
                    isOpaque = false
                    add(JBLabel("<html><body style='width: 500px'>" +
                        MpSqlVisualizerBundle.message(key) + "</body></html>").apply {
                        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                    }, BorderLayout.CENTER)
                    if (action != null) {
                        add(JPanel(BorderLayout()).apply {
                            isOpaque = false
                            add(action, BorderLayout.EAST)
                        }, BorderLayout.SOUTH)
                    }
                }, g)
                g.gridwidth = 1
                g.fill = GridBagConstraints.NONE
                g.gridy++
            }
            disclosure("settings.storage.captureFiles", openCaptureDirectoryButton.apply {
                text = MpSqlVisualizerBundle.message("settings.storage.captureFiles.open")
                toolTipText = MpSqlVisualizerBundle.message("settings.storage.captureFiles.open.tip")
            })
            disclosure("settings.storage.previewHistory", clearPreviewHistoryButton.apply {
                text = MpSqlVisualizerBundle.message("settings.storage.previewHistory.clear")
                toolTipText = MpSqlVisualizerBundle.message("settings.storage.previewHistory.clear.tip")
            })
            disclosure("settings.storage.paramHistory", clearParamHistoryButton.apply {
                text = MpSqlVisualizerBundle.message("settings.storage.paramHistory.clear")
                toolTipText = MpSqlVisualizerBundle.message("settings.storage.paramHistory.clear.tip")
            })
            disclosure("settings.storage.clearBoundary")
            addRow(p, g, "", storageActionStatusLabel)
            addRow(p, g, "", logAutoSaveCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.log.autoSave")
                toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.tip")
                addActionListener {
                    val enabled = isSelected
                    logAutoSavePathField.isEnabled = enabled
                    logAutoSaveBrowseButton.isEnabled = enabled
                    logAutoSavePatternField.isEnabled = enabled
                    logAutoSaveMaxFilesField.isEnabled = enabled // T-HistoryFileRetention
                    updateAutoSavePreview()
                }
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.autoSave.path"),
                JPanel(BorderLayout(4, 0)).apply {
                    isOpaque = false
                    add(logAutoSavePathField.apply {
                        toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.path.tip")
                        emptyText.text = MpSqlVisualizerBundle.message("settings.log.autoSave.path.empty")
                    }, BorderLayout.CENTER)
                    add(logAutoSaveBrowseButton.apply {
                        toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.browse.tip")
                        addActionListener { browseAutoSavePath() }
                    }, BorderLayout.EAST)
                }
            )
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.autoSave.pattern"),
                JPanel(BorderLayout(4, 0)).apply {
                    isOpaque = false
                    add(logAutoSavePatternField.apply {
                        toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.tip")
                        emptyText.text = PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN
                        document.addDocumentListener(object : javax.swing.event.DocumentListener {
                            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateAutoSavePreview()
                            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateAutoSavePreview()
                            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateAutoSavePreview()
                        })
                    }, BorderLayout.CENTER)
                    add(JButton(MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.reset")).apply {
                        toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.reset.tip")
                        addActionListener { logAutoSavePatternField.text = PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN }
                    }, BorderLayout.EAST)
                }
            )
            addRow(p, g, "", logAutoSavePatternPreview.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.preview.tip")
            })
            // T-HistoryFileRetention：历史文件保留上限（防磁盘爆炸；0 = 全量保留）
            addRow(p, g, MpSqlVisualizerBundle.message("settings.log.autoSave.maxFiles"),
                logAutoSaveMaxFilesField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.maxFiles.tip")
                    emptyText.text = PluginSettings.DEFAULT_LOG_AUTO_SAVE_MAX_FILES.toString()
                }
            )
        }

        // F-44-7：SqlSourceInterceptor 自动注入（源码位置捕获）
        sectionGroup("settings.log.tab.sourceCapture") { p, g ->
            addRow(p, g, "", autoInjectInterceptorCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.log.autoInjectInterceptor")
                toolTipText = MpSqlVisualizerBundle.message("settings.log.autoInjectInterceptor.tip")
                addActionListener {
                    if (isSelected) {
                        // 触发注入
                        triggerInterceptorInjection()
                    }
                }
            })
            // M56 T-M56-08：增强捕获设置（捕获通道类设置与源码位置捕获同组）
            addRow(p, g, "", enhancedCaptureCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.enhancedCapture")
                toolTipText = MpSqlVisualizerBundle.message("settings.enhancedCapture.tip")
                addActionListener {
                    // T-EnhancedCaptureDefaultOn：勾选即授权，不再弹注入确认；只刷新状态行。
                    refreshEnhancedStatusLabel()
                }
            })
            addRow(p, g, "", enhancedDesensitizeCheck.apply { text = MpSqlVisualizerBundle.message("settings.enhancedCapture.desensitize") })
            // R60（T-LaneB D96）→ T-MagpieGems W2：展示/导出链脱敏分级下拉（与采集开关解耦——管
            // 「文本怎么出去」）。行标签复用 settings.display.mask（该键即本控件的可搜索身份）
            addRow(p, g, MpSqlVisualizerBundle.message("settings.display.mask"), displayMaskModeCombo.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.display.mask.tip")
            })
            // T-MagpieGems W2：豁免白名单（3 行小文本域，滚动包裹防撑高）
            addRow(
                p, g, MpSqlVisualizerBundle.message("settings.display.mask.allowList"),
                javax.swing.JScrollPane(displayMaskAllowListArea).apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.display.mask.allowList.tip")
                },
                fill = GridBagConstraints.HORIZONTAL,
            )
            // T-StackHoverUi：调用栈快照总帧数（栈顶 B-8 帧＋栈底固定 8 帧；CONFIG 帧热下发 agent）
            addRow(p, g, MpSqlVisualizerBundle.message("settings.enhancedCapture.stackMaxFrames"), stackMaxFramesField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.enhancedCapture.stackMaxFrames.tip")
            })
            addRow(p, g, "", enhancedStatusLabel)
            // T-SourceJumpFunnel P1-3：帧过滤前缀（自研框架包名登记，热下发 agent）
            frameSkipPrefixesField.apply {
                text = PluginSettings.getInstance().state.sourceFrameSkipPrefixes
                toolTipText = MpSqlVisualizerBundle.message("settings.sourceFrameSkipPrefixes.tip")
            }
            // T-ToolSiteBizFrame：JAR 业务帧反向名单（与跳过前缀语义相反，热下发 agent）
            bizJarPrefixesField.apply {
                text = PluginSettings.getInstance().state.sourceFrameBizJarPrefixes
                toolTipText = MpSqlVisualizerBundle.message("settings.sourceFrameBizJarPrefixes.tip")
            }
            // T-FramePrefixResize：两框置入垂直分栏（拖拽中缝重分配高度 + 比例记忆）；
            // 行 fill=HORIZONTAL，宽度随设置对话框拉伸，占位文案不再截断
            addRow(p, g, "", buildFramePrefixesSplitter(), fill = GridBagConstraints.HORIZONTAL)
            // T-PrefixValidate：校验结果行（问题数 + 首条，悬停看全部；一键修复按钮同行）
            addRow(p, g, "", prefixValidationRow)
        }
    }

    // 组 5：界面与操作（默认展开）
    if (SECTION_UI in sections) {
        sectionGroup("settings.section.ui") { p, g ->
            addRow(p, g, "", gutterIconCheck.apply { text = MpSqlVisualizerBundle.message("settings.showGutterIcon") })
            addRow(p, g, "", gutterGroupMapperXmlCheck)
            addRow(p, g, "", gutterGroupAnnotationCheck)
            addRow(p, g, "", gutterGroupJpaHqlCheck)
            addRow(p, g, "", gutterGroupJdbcCheck)
            addRow(p, g, "", gutterGroupInlineFallbackCheck)
            addRow(p, g, "", gutterGroupOtherCheck)
            // T-MapperNavCoexistYield：共存让位开关（默认勾选＝MyBatisCodeHelperPro 启用时让出跳转图标）
            addRow(p, g, "", mapperNavYieldCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.mapperNavYield")
                toolTipText = MpSqlVisualizerBundle.message("settings.mapperNavYield.tip")
            })
            addRow(p, g, "", xmlInlineSqlCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.xmlInlineSql")
                toolTipText = MpSqlVisualizerBundle.message("settings.xmlInlineSql.tip")
            })
            // T-PerfSweep16 F46（D4=A 定案）：mapper XML 语法高亮开关行（照 R2 六组复选框形态）
            addRow(p, g, "", xmlSynHiCheck)
            addRow(p, g, "", joinDiagramNativeCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.joinDiagramNative")
                toolTipText = MpSqlVisualizerBundle.message("settings.joinDiagramNative.tip")
            })
            // T-UxOnboard T-UX-01：首用锚点引导通知开关（一次性语义见 OnboardingAnchorTip）
            addRow(p, g, "", onboardingAnchorTipCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.onboardingAnchorTip")
                toolTipText = MpSqlVisualizerBundle.message("settings.onboardingAnchorTip.tip")
            })
            // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
            addRow(p, g, "", editorShowWhitespacesCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.editorShowWhitespaces")
                toolTipText = MpSqlVisualizerBundle.message("settings.editorShowWhitespaces.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.language"), languageCombo.apply {
                model = DefaultComboBoxModel(languageOptions())
                selectedItem = languageOptions().firstOrNull { it.value == settings.state.language }
                    ?: languageOptions().first()
            })
        }
    }

    // 组 5.5：快捷键（T-ShortcutSettings：插件 5 个动作快捷键统一管理；独立节便于搜索定位）
    if (SECTION_SHORTCUTS in sections) {
        sectionGroup("settings.section.shortcuts") { p, g ->
            addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut"), shortcutEditor.apply { updateTexts() })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut.sqlToJava"), reverseShortcutEditor.apply { updateTexts() })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut.explain"), explainShortcutEditor.apply { updateTexts() })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut.count"), countShortcutEditor.apply { updateTexts() })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut.sample"), sampleShortcutEditor.apply { updateTexts() })
        }
    }

    // 组 6：更新与诊断（默认折叠）
    if (SECTION_UPDATE_DIAGNOSTICS in sections) {
        sectionGroup("settings.section.updateDiagnostics") { p, g ->
            // NEW-18：一键环境体检（数据源/方言/agent attach/捕获覆盖/Community 五项只读体检，
            // 结果以 LightVirtualFile 在编辑器打开，可整段复制贴工单）
            addRow(p, g, MpSqlVisualizerBundle.message("settings.envCheck.label"),
                JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    add(environmentCheckButton)
                    add(environmentCheckStatusLabel)
                })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.cache.label"),
                JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    add(cacheInfoLabel)
                    add(clearCacheButton.apply {
                        toolTipText = MpSqlVisualizerBundle.message("settings.cache.tip")
                        addActionListener {
                            ProjectManager.getInstance().openProjects.forEach { PluginCacheManager.clearAll(it) }
                            cacheInfoLabel.text = MpSqlVisualizerBundle.message("settings.cache.cleared")
                        }
                    })
                })
            g.anchor = GridBagConstraints.NORTHWEST
            addRow(p, g, MpSqlVisualizerBundle.message("settings.update.versionInfo"), updateSection.apply { updateTexts() })
            g.anchor = GridBagConstraints.WEST
            addRow(p, g, MpSqlVisualizerBundle.message("settings.buildNumber"), JPanel(BorderLayout(8, 0)).apply {
                add(JBLabel().apply {
                    text = PluginBuildInfo.displayText()
                    toolTipText = MpSqlVisualizerBundle.message("settings.buildNumber.tip")
                }, BorderLayout.CENTER)
                add(JButton(MpSqlVisualizerBundle.message("settings.buildNumber.copy")).apply {
                    isEnabled = PluginBuildInfo.mpvBuild() != null
                    addActionListener {
                        UiTextClipboard.copy(PluginBuildInfo.displayText())
                        val original = text
                        text = MpSqlVisualizerBundle.message("settings.buildNumber.copied")
                        javax.swing.Timer(1200) { text = original }.apply { isRepeats = false; start() }
                    }
                }, BorderLayout.EAST)
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.pluginSettings"), JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    add(JButton(MpSqlVisualizerBundle.message("settings.import")).apply {
                        toolTipText = MpSqlVisualizerBundle.message("settings.import.tip")
                        addActionListener { importSettings() }
                    })
                    add(JButton(MpSqlVisualizerBundle.message("settings.export")).apply {
                        toolTipText = MpSqlVisualizerBundle.message("settings.export.tip")
                        addActionListener { exportSettings() }
                    })
                }, BorderLayout.CENTER)
                add(statusLabel, BorderLayout.EAST)
            })
        }
    }

    // 底部 weighty 占位（内容顶置）
    gbc.gridx = 0; gbc.gridy++; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
    panel.add(JPanel(), gbc)
}

/**
 * Phase 3：实时过滤配置行。
 *
 * - 空搜索框：恢复全部行可见 + 恢复折叠状态。
 * - 非空搜索框：隐藏不匹配的行，展开包含匹配行的折叠面板。
 * 匹配范围：行标签文本 + 组件 tooltip。
 */
/**
 * 快捷键内嵌编辑行（M14.1）：只读显示当前快捷键 + 「修改」按钮弹出录制对话框，
 * 捕获按键组合后回填（保存时由 [PluginSettingsPanel.apply] 写入 Keymap）。
 * 不依赖 impl 包（KeyboardShortcutPanel 为包私有），全公开 API 实现。
 *
 * T-M41：actionId 参数化——同一组件服务多个 action（Convert to SQL / SQL→Java），
 * 冲突检测与保存写入均以各自 actionId 为准。internal：测试访问器返回类型（行存在/修改检测）。
 */
