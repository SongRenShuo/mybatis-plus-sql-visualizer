// T-M53-II（2026-10-10）：自 master PluginSettingsPanelSections.kt 移植——PluginSettingsPanel 的
// 「设置页各分区构建」内聚成员，以同包静态方法形式承载（Kotlin 扩展函数形态的 Java 等价）。
// 方法体逐字符搬运，被触及的私有成员已按需放宽为 public（Kotlin internal → Java public）。
package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.PluginBuildInfo;
import cn.song.mybatisplus.visualizer.util.PluginCacheManager;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.BiConsumer;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * 设置页各分区构建（T-M53-II D2：自 {@link PluginSettingsPanel} 逐字符搬出的内聚成员）。
 *
 * <p>Kotlin 原形态为同包扩展函数 {@code internal fun PluginSettingsPanel.buildSections(panel)}；
 * Java 无扩展函数，等价为静态方法，首参接收面板实例。控件字段与辅助方法经 {@code internal}
 * 放宽后（Java public）由本类访问。</p>
 */
public final class PluginSettingsPanelSections {

    private PluginSettingsPanelSections() {
    }

    public static void buildSections(PluginSettingsPanel self, JPanel panel) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 6, 4, 6);

        // 组 1：SQL 预览（默认展开）
        if (self.sections.contains(PluginSettingsPanel.SECTION_PREVIEW)) {
            sectionGroup(panel, gbc, "settings.section.preview", (p, g) -> {
                addRow(p, g, MpSqlVisualizerBundle.message("settings.dialect"), self.dialectCombo);
                addRow(p, g, "", self.dialectInferredLabel);
                // T-DialectAutoInfer：下拉变化一律刷新推断状态行。
                // 2026-10-27 修（T-DialectLabelStale）：原实现只在「切回哨兵项」时刷新，
                // 切到具体方言时状态行不动——表现为下拉已变 Oracle、副标题仍停在
                // 「项目候选方言不一致 / 手动指定: MySQL」的旧文案，直到面板重绘或
                // apply 后的 reset() 才追上（用户真机复现）。状态行内容完全由
                // selectedItem 决定（见 refreshInferredLabel），故任何变化都应重算。
                self.dialectCombo.addActionListener(e -> self.refreshInferredLabel());
                self.replaceParamsCheck.setText(MpSqlVisualizerBundle.message("settings.replaceParams"));
                addRow(p, g, "", self.replaceParamsCheck);
                // T-PT-04 / O-79：空参数填充策略（参数值为空时保留 ? 占位，还是回填 NULL/空字符串）
                self.emptyParamFillPlaceholderRadio.setText(MpSqlVisualizerBundle.message("settings.emptyParamFill.placeholder"));
                self.emptyParamFillPlaceholderRadio.setToolTipText(MpSqlVisualizerBundle.message("settings.emptyParamFill.placeholder.tip"));
                self.emptyParamFillNullRadio.setText(MpSqlVisualizerBundle.message("settings.emptyParamFill.null"));
                self.emptyParamFillNullRadio.setToolTipText(MpSqlVisualizerBundle.message("settings.emptyParamFill.null.tip"));
                self.emptyParamFillEmptyRadio.setText(MpSqlVisualizerBundle.message("settings.emptyParamFill.empty"));
                self.emptyParamFillEmptyRadio.setToolTipText(MpSqlVisualizerBundle.message("settings.emptyParamFill.empty.tip"));
                self.emptyParamFillGroup.add(self.emptyParamFillPlaceholderRadio);
                self.emptyParamFillGroup.add(self.emptyParamFillNullRadio);
                self.emptyParamFillGroup.add(self.emptyParamFillEmptyRadio);
                JPanel emptyParamFillPanel = new JPanel();
                emptyParamFillPanel.setOpaque(false);
                emptyParamFillPanel.add(self.emptyParamFillPlaceholderRadio);
                emptyParamFillPanel.add(self.emptyParamFillNullRadio);
                emptyParamFillPanel.add(self.emptyParamFillEmptyRadio);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.emptyParamFill"), emptyParamFillPanel);
                self.formatCheck.setText(MpSqlVisualizerBundle.message("settings.formatSql"));
                addRow(p, g, "", self.formatCheck);
                self.verticalPairAnnotationCheck.setText(MpSqlVisualizerBundle.message("settings.verticalPairAnnotation"));
                addRow(p, g, "", self.verticalPairAnnotationCheck);
                self.autoFoldCheck.setText(MpSqlVisualizerBundle.message("settings.autoFold.enabled"));
                self.autoFoldCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.autoFold.enabled.tip"));
                self.autoFoldCheck.addActionListener(e -> self.autoFoldThresholdField.setEnabled(self.autoFoldCheck.isSelected()));
                addRow(p, g, "", self.autoFoldCheck);
                self.autoFoldThresholdField.setToolTipText(MpSqlVisualizerBundle.message("settings.autoFold.threshold.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.autoFold.threshold"), self.autoFoldThresholdField);
                self.literalParamRecoveryCheck.setText(MpSqlVisualizerBundle.message("settings.literalParamRecovery"));
                addRow(p, g, "", self.literalParamRecoveryCheck);
                self.injectLogicDeleteCheck.setText(MpSqlVisualizerBundle.message("settings.injectLogicDelete"));
                addRow(p, g, "", self.injectLogicDeleteCheck);
                self.logicDeleteValueField.setToolTipText(MpSqlVisualizerBundle.message("settings.logicDeleteValue.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.logicDeleteValue"), self.logicDeleteValueField);
                self.injectTenantLineCheck.setText(MpSqlVisualizerBundle.message("settings.injectTenantLine"));
                addRow(p, g, "", self.injectTenantLineCheck);
                self.tenantColumnNameField.setToolTipText(MpSqlVisualizerBundle.message("settings.tenantColumnName.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.tenantColumnName"), self.tenantColumnNameField);
                self.tenantColumnDefaultField.setToolTipText(MpSqlVisualizerBundle.message("settings.tenantColumnDefault.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.tenantColumnDefault"), self.tenantColumnDefaultField);
                self.injectTenantOnInsertCheck.setText(MpSqlVisualizerBundle.message("settings.injectTenantOnInsert"));
                self.injectTenantOnInsertCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.injectTenantOnInsert.tip"));
                addRow(p, g, "", self.injectTenantOnInsertCheck);
                self.dataPermissionHintCheck.setText(MpSqlVisualizerBundle.message("settings.dataPermissionHint"));
                self.dataPermissionHintCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.dataPermissionHint.tip"));
                addRow(p, g, "", self.dataPermissionHintCheck);
                self.dataPermissionMockConditionField.setToolTipText(MpSqlVisualizerBundle.message("settings.dataPermissionMockCondition.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.dataPermissionMockCondition"), self.dataPermissionMockConditionField);
                self.paginationSimCheck.setText(MpSqlVisualizerBundle.message("settings.paginationSim"));
                self.paginationSimCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.paginationSim.tip"));
                addRow(p, g, "", self.paginationSimCheck);
                self.paginationCountCheck.setText(MpSqlVisualizerBundle.message("settings.paginationCount"));
                self.paginationCountCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.paginationCount.tip"));
                addRow(p, g, "", self.paginationCountCheck);
                self.jdbcSqlPreviewCheck.setText(MpSqlVisualizerBundle.message("settings.preview.jdbc"));
                self.jdbcSqlPreviewCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.preview.jdbc.tip"));
                addRow(p, g, "", self.jdbcSqlPreviewCheck);
                self.explainStructuredCheck.setText(MpSqlVisualizerBundle.message("settings.explain.structured"));
                self.explainStructuredCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.explain.structured.tip"));
                addRow(p, g, "", self.explainStructuredCheck);
                // R90：EXPLAIN 原始计划保留开关 + 只读隐私披露
                self.explainPlanRetentionCheck.setText(MpSqlVisualizerBundle.message("settings.explain.retention"));
                addRow(p, g, "", self.explainPlanRetentionCheck);
                self.explainPlanRetentionDisclosure.setText(
                        MpSqlVisualizerBundle.message("settings.explain.retention.disclosure"));
                addRow(p, g, "", self.explainPlanRetentionDisclosure);
                self.executionTimeoutField.setToolTipText(MpSqlVisualizerBundle.message("settings.preview.executionTimeout.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.preview.executionTimeout"), self.executionTimeoutField);
            });
        }

        // 组 2：表名映射（默认折叠）
        if (self.sections.contains(PluginSettingsPanel.SECTION_TABLE_MAPPING)) {
            sectionGroup(panel, gbc, "settings.section.tableMapping", (p, g) -> {
                self.tablePrefixField.setToolTipText(MpSqlVisualizerBundle.message("settings.tablePrefix.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.tablePrefix"), self.tablePrefixField);
                self.tableSuffixField.setToolTipText(MpSqlVisualizerBundle.message("settings.tableSuffix.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.tableSuffix"), self.tableSuffixField);
                self.removeTablePrefixField.setToolTipText(MpSqlVisualizerBundle.message("settings.removeTablePrefix.tip"));
                self.removeTablePrefixField.getEmptyText().setText(MpSqlVisualizerBundle.message("settings.separatorHint"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.removeTablePrefix"), self.removeTablePrefixField);
                self.removeTableSuffixField.setToolTipText(MpSqlVisualizerBundle.message("settings.removeTableSuffix.tip"));
                self.removeTableSuffixField.getEmptyText().setText(MpSqlVisualizerBundle.message("settings.separatorHint"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.removeTableSuffix"), self.removeTableSuffixField);
                self.ignoredFieldsField.setToolTipText(MpSqlVisualizerBundle.message("settings.ignoredFields.tip"));
                self.ignoredFieldsField.getEmptyText().setText(MpSqlVisualizerBundle.message("settings.separatorHint"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.ignoredFields"), self.ignoredFieldsField);
                self.inferenceCombo.setModel(new DefaultComboBoxModel<>(self.inferenceOptions()));
                self.inferenceCombo.setToolTipText(MpSqlVisualizerBundle.message("settings.tableNameInference.tip"));
                Object inferred = self.inferenceOptionByValue(self.settings.getState().getTableNameInference());
                self.inferenceCombo.setSelectedItem(inferred != null ? inferred : self.inferenceOptions()[0]);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.tableNameInference"), self.inferenceCombo);
                self.columnCalibrationCheck.setText(MpSqlVisualizerBundle.message("settings.columnCalibration"));
                self.columnCalibrationCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.columnCalibration.tip"));
                addRow(p, g, "", self.columnCalibrationCheck);
                self.embeddedNamingCombo.setModel(new DefaultComboBoxModel<>(self.embeddedNamingOptions()));
                self.embeddedNamingCombo.setToolTipText(MpSqlVisualizerBundle.message("settings.embeddedColumnNaming.tip"));
                Object embedded = self.embeddedNamingOptionByValue(self.settings.getState().getEmbeddedColumnNaming());
                self.embeddedNamingCombo.setSelectedItem(embedded != null ? embedded : self.embeddedNamingOptions()[0]);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.embeddedColumnNaming"), self.embeddedNamingCombo);
            });
        }

        // 组 3：代码生成（默认折叠）
        if (self.sections.contains(PluginSettingsPanel.SECTION_CODE_GEN)) {
            sectionGroup(panel, gbc, "settings.section.codeGen", (p, g) -> {
                self.reverseFormatCombo.setModel(new DefaultComboBoxModel<>(self.reverseFormatOptions()));
                self.reverseFormatCombo.setToolTipText(MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.tip"));
                Object reverse = self.reverseFormatOptionByValue(self.settings.getState().getReverseWrapperFormat());
                self.reverseFormatCombo.setSelectedItem(reverse != null ? reverse : self.reverseFormatOptions()[0]);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat"), self.reverseFormatCombo);
                self.manualEntityCheck.setText(MpSqlVisualizerBundle.message("settings.reverseManualEntitySelection"));
                self.manualEntityCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.reverseManualEntitySelection.tip"));
                addRow(p, g, "", self.manualEntityCheck);
                self.librarySweepCheck.setText(MpSqlVisualizerBundle.message("settings.libraryEntitySweep"));
                self.librarySweepCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.libraryEntitySweep.tip"));
                addRow(p, g, "", self.librarySweepCheck);
            });
        }

        // 组 4：SQL 日志（分割线分隔 3 个子分组）
        if (self.sections.contains(PluginSettingsPanel.SECTION_SQL_LOG)) {
            sectionGroup(panel, gbc, "settings.log.tab.mybatis", (p, g) -> {
                // P1-4 摩擦 #3（批次9 波次2）：帧过滤前缀入口指向说明——设置项本体留在页末
                // 「源码捕获」组（与 JAR 业务帧反向名单同域成对、SearchableField 已按域索引，
                // 搬动即拆散配对语义），按任务定案取侵入最小形态：本页首组顶加一行零交互指向
                // 说明（R20 落地页提示形态，零监听器），让从上往下扫的用户在第一屏即得知该
                // 能力在场。说明行跨两列直挂（不经 addRow 的首列标签位），且 JBLabel 必须包一
                // 层面板——sectionGroup 的 maxLabelWidth 列宽计算扫描全部直接 JBLabel 子件，
                // 长说明行入列会把组内首列撑到整行宽、其余行字段列被挤没。
                g.gridx = 0;
                g.gridwidth = 2;
                g.weightx = 1.0;
                g.fill = GridBagConstraints.HORIZONTAL;
                JPanel hintPanel = new JPanel(new BorderLayout());
                hintPanel.setOpaque(false);
                JBLabel hintLabel = new JBLabel(
                    "<html>" + MpSqlVisualizerBundle.message("settings.sourceFrameSkipPrefixes.hint") + "</html>");
                hintLabel.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
                hintPanel.add(hintLabel, BorderLayout.CENTER);
                p.add(hintPanel, g);
                g.gridwidth = 1;
                g.gridy++;
                self.logSqlPrefixField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.sqlPrefix.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.sqlPrefix"), self.logSqlPrefixField);
                self.logParamPrefixField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.paramPrefix.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.paramPrefix"), self.logParamPrefixField);
                self.logMixedPrefixCheck.setText(MpSqlVisualizerBundle.message("settings.log.mixedPrefix"));
                self.logMixedPrefixCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.log.mixedPrefix.tip"));
                addRow(p, g, "", self.logMixedPrefixCheck);
                self.logAutoCaptureCheck.setText(MpSqlVisualizerBundle.message("settings.log.autoCapture"));
                addRow(p, g, "", self.logAutoCaptureCheck);
                self.logAutoOpenCheck.setText(MpSqlVisualizerBundle.message("settings.log.autoOpen"));
                addRow(p, g, "", self.logAutoOpenCheck);
                self.logStopOnToolWindowCloseCheck.setText(MpSqlVisualizerBundle.message("settings.log.stopOnToolWindowClose"));
                addRow(p, g, "", self.logStopOnToolWindowCloseCheck);
                self.logTimestampCheck.setText(MpSqlVisualizerBundle.message("settings.log.timestamp"));
                addRow(p, g, "", self.logTimestampCheck);
                self.logTimestampFormatField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.timestampFormat.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.timestampFormat"), self.logTimestampFormatField);
                // 时间来源单选组（日志时间 / 捕获时间）
                self.logTimestampSourceLogRadio.setText(MpSqlVisualizerBundle.message("settings.log.timestampSource.log"));
                self.logTimestampSourceLogRadio.setToolTipText(MpSqlVisualizerBundle.message("settings.log.timestampSource.log.tip"));
                self.logTimestampSourceCaptureRadio.setText(MpSqlVisualizerBundle.message("settings.log.timestampSource.capture"));
                self.logTimestampSourceCaptureRadio.setToolTipText(MpSqlVisualizerBundle.message("settings.log.timestampSource.capture.tip"));
                self.logTimestampSourceGroup.add(self.logTimestampSourceLogRadio);
                self.logTimestampSourceGroup.add(self.logTimestampSourceCaptureRadio);
                JPanel timestampSourcePanel = new JPanel();
                timestampSourcePanel.setOpaque(false);
                timestampSourcePanel.add(self.logTimestampSourceLogRadio);
                timestampSourcePanel.add(self.logTimestampSourceCaptureRadio);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.timestampSource"), timestampSourcePanel);
                self.logFormatSqlCheck.setText(MpSqlVisualizerBundle.message("settings.log.formatSql"));
                addRow(p, g, "", self.logFormatSqlCheck);
                self.logFilterKeywordsField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.filterKeywords.tip"));
                self.logFilterKeywordsField.getEmptyText().setText(MpSqlVisualizerBundle.message("settings.separatorHint"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.filterKeywords"), self.logFilterKeywordsField);
                self.logDedupeField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.dedupeWindow.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.dedupeWindow"), self.logDedupeField);
                self.logMaxParseField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.maxParseLength.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.maxParseLength"), self.logMaxParseField);
                self.logMaxCountField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.maxCount.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.maxCount"), self.logMaxCountField);
                self.logTimePrefixField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.timePrefix.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.timePrefix"), self.logTimePrefixField);
                self.slowQueryThresholdField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.slowQueryThreshold.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.slowQueryThreshold"), self.slowQueryThresholdField);
                self.logShowDurationCheck.setText(MpSqlVisualizerBundle.message("settings.log.showDuration"));
                self.logShowDurationCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.log.showDuration.tip"));
                addRow(p, g, "", self.logShowDurationCheck);
                // T-DataSourceBadgeMode：数据源角标显示粒度（默认仅数据库类型，打开后带连接事实）
                self.dataSourceBadgeDetailCheck.setText(MpSqlVisualizerBundle.message("settings.log.dataSourceBadgeDetail"));
                self.dataSourceBadgeDetailCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.log.dataSourceBadgeDetail.tip"));
                addRow(p, g, "", self.dataSourceBadgeDetailCheck);
            });

            // T-DisplayModeToSettings：SQL 列表显示模式（SQL 日志 + SQL 历史 两列表共享一键，设置页唯一入口）
            sectionGroup(panel, gbc, "settings.log.tab.list", (p, g) -> {
                self.logDisplayModeCombo.setModel(new DefaultComboBoxModel<>(self.listDisplayModeOptions()));
                self.logDisplayModeCombo.setToolTipText(MpSqlVisualizerBundle.message("log.list.displayMode.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("log.list.displayMode"), self.logDisplayModeCombo);
            });

            // T-CrudColor：SQL 列表 CRUD 类型颜色标记（样式 + 四色；不新增复选框，保持复选框台账不变）
            sectionGroup(panel, gbc, "settings.log.tab.crudColor", (p, g) -> {
                self.crudColorStyleCombo.setModel(new DefaultComboBoxModel<>(self.crudColorStyleOptions()));
                self.crudColorStyleCombo.setToolTipText(MpSqlVisualizerBundle.message("settings.log.crudColor.style.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.style"), self.crudColorStyleCombo);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.select"), self.crudColorSelectPanel);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.insert"), self.crudColorInsertPanel);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.update"), self.crudColorUpdatePanel);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.delete"), self.crudColorDeletePanel);
                self.crudColorResetButton.setText(MpSqlVisualizerBundle.message("settings.log.crudColor.reset"));
                self.crudColorResetButton.setToolTipText(MpSqlVisualizerBundle.message("settings.log.crudColor.reset.tip"));
                self.crudColorResetButton.addActionListener(e -> {
                    self.crudColorSelectPanel.setSelectedColor(self.colorOf(PluginSettings.CRUD_COLOR_DEFAULT_SELECT));
                    self.crudColorInsertPanel.setSelectedColor(self.colorOf(PluginSettings.CRUD_COLOR_DEFAULT_INSERT));
                    self.crudColorUpdatePanel.setSelectedColor(self.colorOf(PluginSettings.CRUD_COLOR_DEFAULT_UPDATE));
                    self.crudColorDeletePanel.setSelectedColor(self.colorOf(PluginSettings.CRUD_COLOR_DEFAULT_DELETE));
                });
                addRow(p, g, "", self.crudColorResetButton);
            });

            sectionGroup(panel, gbc, "settings.log.tab.orm", (p, g) -> {
                self.logHibernateEnabledCheck.setText(MpSqlVisualizerBundle.message("settings.log.hibernate.enabled"));
                self.logHibernateEnabledCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.log.hibernate.enabled.tip"));
                addRow(p, g, "", self.logHibernateEnabledCheck);
                self.logHibernateSqlPrefixField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.hibernate.sqlPrefix.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.hibernate.sqlPrefix"), self.logHibernateSqlPrefixField);
                self.logHibernateParamPrefixField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.hibernate.paramPrefix.tip"));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.hibernate.paramPrefix"), self.logHibernateParamPrefixField);
                self.logJdbcEnabledCheck.setText(MpSqlVisualizerBundle.message("settings.log.jdbc"));
                self.logJdbcEnabledCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.log.jdbc.tip"));
                addRow(p, g, "", self.logJdbcEnabledCheck);
                // R10：ShardingSphere 双段日志捕获（Logic 主条目 + Actual 折叠附注；默认开）
                self.logShardingSphereEnabledCheck.setText(MpSqlVisualizerBundle.message("settings.log.shardingsphere.enabled"));
                self.logShardingSphereEnabledCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.log.shardingsphere.enabled.tip"));
                addRow(p, g, "", self.logShardingSphereEnabledCheck);
            });

            sectionGroup(panel, gbc, "settings.log.tab.autoSave", (p, g) -> {
                // T-M19-StorageClear：三类本地数据的分离动作（打开目录 / 清空预览历史 / 清空参数历史）
                self.openCaptureDirectoryButton.setText(MpSqlVisualizerBundle.message("settings.storage.captureFiles.open"));
                self.openCaptureDirectoryButton.setToolTipText(
                        MpSqlVisualizerBundle.message("settings.storage.captureFiles.open.tip"));
                addDisclosure(p, g, MpSqlVisualizerBundle.message("settings.storage.captureFiles"),
                        self.openCaptureDirectoryButton);
                self.clearPreviewHistoryButton.setText(
                        MpSqlVisualizerBundle.message("settings.storage.previewHistory.clear"));
                self.clearPreviewHistoryButton.setToolTipText(
                        MpSqlVisualizerBundle.message("settings.storage.previewHistory.clear.tip"));
                addDisclosure(p, g, MpSqlVisualizerBundle.message("settings.storage.previewHistory"),
                        self.clearPreviewHistoryButton);
                self.clearParamHistoryButton.setText(
                        MpSqlVisualizerBundle.message("settings.storage.paramHistory.clear"));
                self.clearParamHistoryButton.setToolTipText(
                        MpSqlVisualizerBundle.message("settings.storage.paramHistory.clear.tip"));
                addDisclosure(p, g, MpSqlVisualizerBundle.message("settings.storage.paramHistory"),
                        self.clearParamHistoryButton);
                addDisclosure(p, g, MpSqlVisualizerBundle.message("settings.storage.clearBoundary"), null);
                addRow(p, g, "", self.storageActionStatusLabel);
                self.logAutoSaveCheck.setText(MpSqlVisualizerBundle.message("settings.log.autoSave"));
                self.logAutoSaveCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.log.autoSave.tip"));
                self.logAutoSaveCheck.addActionListener(e -> {
                    boolean enabled = self.logAutoSaveCheck.isSelected();
                    self.logAutoSavePathField.setEnabled(enabled);
                    self.logAutoSaveBrowseButton.setEnabled(enabled);
                    self.logAutoSavePatternField.setEnabled(enabled);
                    self.logAutoSaveMaxFilesField.setEnabled(enabled); // T-HistoryFileRetention
                    self.updateAutoSavePreview();
                });
                addRow(p, g, "", self.logAutoSaveCheck);

                JPanel pathPanel = new JPanel(new BorderLayout(4, 0));
                pathPanel.setOpaque(false);
                self.logAutoSavePathField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.autoSave.path.tip"));
                self.logAutoSavePathField.getEmptyText().setText(MpSqlVisualizerBundle.message("settings.log.autoSave.path.empty"));
                pathPanel.add(self.logAutoSavePathField, BorderLayout.CENTER);
                self.logAutoSaveBrowseButton.setToolTipText(MpSqlVisualizerBundle.message("settings.log.autoSave.browse.tip"));
                self.logAutoSaveBrowseButton.addActionListener(e -> self.browseAutoSavePath());
                pathPanel.add(self.logAutoSaveBrowseButton, BorderLayout.EAST);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.autoSave.path"), pathPanel);

                JPanel patternPanel = new JPanel(new BorderLayout(4, 0));
                patternPanel.setOpaque(false);
                self.logAutoSavePatternField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.tip"));
                self.logAutoSavePatternField.getEmptyText().setText(PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN);
                javax.swing.event.DocumentListener patternListener = new javax.swing.event.DocumentListener() {
                    @Override
                    public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        self.updateAutoSavePreview();
                    }

                    @Override
                    public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        self.updateAutoSavePreview();
                    }

                    @Override
                    public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        self.updateAutoSavePreview();
                    }
                };
                self.logAutoSavePatternField.getDocument().addDocumentListener(patternListener);
                patternPanel.add(self.logAutoSavePatternField, BorderLayout.CENTER);
                JButton resetButton = new JButton(MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.reset"));
                resetButton.setToolTipText(MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.reset.tip"));
                resetButton.addActionListener(e ->
                    self.logAutoSavePatternField.setText(PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN));
                patternPanel.add(resetButton, BorderLayout.EAST);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.autoSave.pattern"), patternPanel);

                self.logAutoSavePatternPreview.setToolTipText(MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.preview.tip"));
                addRow(p, g, "", self.logAutoSavePatternPreview);
                // T-HistoryFileRetention：历史文件保留上限（防磁盘爆炸；0 = 全量保留）
                self.logAutoSaveMaxFilesField.setToolTipText(MpSqlVisualizerBundle.message("settings.log.autoSave.maxFiles.tip"));
                self.logAutoSaveMaxFilesField.getEmptyText().setText(String.valueOf(PluginSettings.DEFAULT_LOG_AUTO_SAVE_MAX_FILES));
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.autoSave.maxFiles"), self.logAutoSaveMaxFilesField);
            });

            // F-44-7：SqlSourceInterceptor 自动注入（源码位置捕获）
            sectionGroup(panel, gbc, "settings.log.tab.sourceCapture", (p2, g2) -> {
                self.autoInjectInterceptorCheck.setText(MpSqlVisualizerBundle.message("settings.log.autoInjectInterceptor"));
                self.autoInjectInterceptorCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.log.autoInjectInterceptor.tip"));
                self.autoInjectInterceptorCheck.addActionListener(e -> {
                    if (self.autoInjectInterceptorCheck.isSelected()) {
                        // 触发注入
                        self.triggerInterceptorInjection();
                    }
                });
                addRow(p2, g2, "", self.autoInjectInterceptorCheck);
                // M56 T-M56-08：增强捕获设置（捕获通道类设置与源码位置捕获同组）
                self.enhancedCaptureCheck.setText(MpSqlVisualizerBundle.message("settings.enhancedCapture"));
                self.enhancedCaptureCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.enhancedCapture.tip"));
                self.enhancedCaptureCheck.addActionListener(e -> {
                    // T-EnhancedCaptureDefaultOn：勾选即授权，不再弹注入确认；只刷新状态行。
                    self.refreshEnhancedStatusLabel();
                });
                addRow(p2, g2, "", self.enhancedCaptureCheck);
                self.enhancedDesensitizeCheck.setText(MpSqlVisualizerBundle.message("settings.enhancedCapture.desensitize"));
                addRow(p2, g2, "", self.enhancedDesensitizeCheck);
                // R60（T-LaneB D96）：展示/导出链脱敏（与采集开关解耦——管「文本怎么出去」）
                self.displayMaskCheck.setText(MpSqlVisualizerBundle.message("settings.display.mask"));
                self.displayMaskCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.display.mask.tip"));
                addRow(p2, g2, "", self.displayMaskCheck);
                // T-StackHoverUi：调用栈快照总帧数（栈顶 B-8 帧＋栈底固定 8 帧；CONFIG 帧热下发 agent）
                self.stackMaxFramesField.setToolTipText(MpSqlVisualizerBundle.message("settings.enhancedCapture.stackMaxFrames.tip"));
                addRow(p2, g2, MpSqlVisualizerBundle.message("settings.enhancedCapture.stackMaxFrames"), self.stackMaxFramesField);
                addRow(p2, g2, "", self.enhancedStatusLabel);
                // T-SourceJumpFunnel P1-3：帧过滤前缀（自研框架包名登记，热下发 agent）
                self.frameSkipPrefixesField.setText(PluginSettings.getInstance().getState().getSourceFrameSkipPrefixes());
                self.frameSkipPrefixesField.setToolTipText(MpSqlVisualizerBundle.message("settings.sourceFrameSkipPrefixes.tip"));
                // T-ToolSiteBizFrame：JAR 业务帧反向名单（与跳过前缀语义相反，热下发 agent）
                self.bizJarPrefixesField.setText(PluginSettings.getInstance().getState().getSourceFrameBizJarPrefixes());
                self.bizJarPrefixesField.setToolTipText(MpSqlVisualizerBundle.message("settings.sourceFrameBizJarPrefixes.tip"));
                // T-FramePrefixResize：两框置入垂直分栏（拖拽中缝重分配高度 + 比例记忆）；
                // 行 fill=HORIZONTAL，宽度随设置对话框拉伸，占位文案不再截断
                addRow(p2, g2, "", self.buildFramePrefixesSplitter(), GridBagConstraints.HORIZONTAL);
                // T-PrefixValidate：校验结果行（问题数 + 首条，悬停看全部；一键修复按钮同行）
                addRow(p2, g2, "", self.prefixValidationRow);
            });
        }

        // 组 5：界面与操作（默认展开）
        if (self.sections.contains(PluginSettingsPanel.SECTION_UI)) {
            sectionGroup(panel, gbc, "settings.section.ui", (p, g) -> {
                self.gutterIconCheck.setText(MpSqlVisualizerBundle.message("settings.showGutterIcon"));
                addRow(p, g, "", self.gutterIconCheck);
                addRow(p, g, "", self.gutterGroupMapperXmlCheck);
                addRow(p, g, "", self.gutterGroupAnnotationCheck);
                addRow(p, g, "", self.gutterGroupJpaHqlCheck);
                addRow(p, g, "", self.gutterGroupJdbcCheck);
                addRow(p, g, "", self.gutterGroupInlineFallbackCheck);
                addRow(p, g, "", self.gutterGroupOtherCheck);
                self.xmlInlineSqlCheck.setText(MpSqlVisualizerBundle.message("settings.xmlInlineSql"));
                self.xmlInlineSqlCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.xmlInlineSql.tip"));
                addRow(p, g, "", self.xmlInlineSqlCheck);
                // T-PerfSweep16 F46（D4=A 定案）：mapper XML 语法高亮开关行（照 R2 六组复选框形态）
                addRow(p, g, "", self.xmlSynHiCheck);
                self.joinDiagramNativeCheck.setText(MpSqlVisualizerBundle.message("settings.joinDiagramNative"));
                self.joinDiagramNativeCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.joinDiagramNative.tip"));
                addRow(p, g, "", self.joinDiagramNativeCheck);
                // T-UxOnboard T-UX-01：首用锚点引导通知开关（一次性语义见 OnboardingAnchorTip）
                self.onboardingAnchorTipCheck.setText(MpSqlVisualizerBundle.message("settings.onboardingAnchorTip"));
                self.onboardingAnchorTipCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.onboardingAnchorTip.tip"));
                addRow(p, g, "", self.onboardingAnchorTipCheck);
                // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
                self.editorShowWhitespacesCheck.setText(MpSqlVisualizerBundle.message("settings.editorShowWhitespaces"));
                self.editorShowWhitespacesCheck.setToolTipText(MpSqlVisualizerBundle.message("settings.editorShowWhitespaces.tip"));
                addRow(p, g, "", self.editorShowWhitespacesCheck);
                self.languageCombo.setModel(new DefaultComboBoxModel<>(self.languageOptions()));
                Object lang = self.languageOptionByValue(self.settings.getState().getLanguage());
                self.languageCombo.setSelectedItem(lang != null ? lang : self.languageOptions()[0]);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.language"), self.languageCombo);
            });
        }

        // 组 5.5：快捷键（T-ShortcutSettings：插件 5 个动作快捷键统一管理；独立节便于搜索定位）
        if (self.sections.contains(PluginSettingsPanel.SECTION_SHORTCUTS)) {
            sectionGroup(panel, gbc, "settings.section.shortcuts", (p, g) -> {
                self.shortcutEditor.updateTexts();
                addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut"), self.shortcutEditor);
                self.reverseShortcutEditor.updateTexts();
                addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut.sqlToJava"), self.reverseShortcutEditor);
                self.explainShortcutEditor.updateTexts();
                addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut.explain"), self.explainShortcutEditor);
                self.countShortcutEditor.updateTexts();
                addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut.count"), self.countShortcutEditor);
                self.sampleShortcutEditor.updateTexts();
                addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut.sample"), self.sampleShortcutEditor);
            });
        }

        // 组 6：更新与诊断（默认折叠）
        if (self.sections.contains(PluginSettingsPanel.SECTION_UPDATE_DIAGNOSTICS)) {
            sectionGroup(panel, gbc, "settings.section.updateDiagnostics", (p, g) -> {
                JPanel cachePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                cachePanel.setOpaque(false);
                cachePanel.add(self.cacheInfoLabel);
                self.clearCacheButton.setToolTipText(MpSqlVisualizerBundle.message("settings.cache.tip"));
                self.clearCacheButton.addActionListener(e -> {
                    for (com.intellij.openapi.project.Project project : ProjectManager.getInstance().getOpenProjects()) {
                        PluginCacheManager.clearAll(project);
                    }
                    self.cacheInfoLabel.setText(MpSqlVisualizerBundle.message("settings.cache.cleared"));
                });
                cachePanel.add(self.clearCacheButton);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.cache.label"), cachePanel);

                g.anchor = GridBagConstraints.NORTHWEST;
                self.updateSection.updateTexts();
                addRow(p, g, MpSqlVisualizerBundle.message("settings.update.versionInfo"), self.updateSection);
                g.anchor = GridBagConstraints.WEST;

                JPanel buildPanel = new JPanel(new BorderLayout(8, 0));
                JBLabel buildLabel = new JBLabel();
                buildLabel.setText(PluginBuildInfo.displayText());
                buildLabel.setToolTipText(MpSqlVisualizerBundle.message("settings.buildNumber.tip"));
                buildPanel.add(buildLabel, BorderLayout.CENTER);
                JButton copyButton = new JButton(MpSqlVisualizerBundle.message("settings.buildNumber.copy"));
                copyButton.setEnabled(PluginBuildInfo.mpvBuild() != null);
                copyButton.addActionListener(e -> {
                    UiTextClipboard.copy(PluginBuildInfo.displayText());
                    String original = copyButton.getText();
                    copyButton.setText(MpSqlVisualizerBundle.message("settings.buildNumber.copied"));
                    Timer restore = new Timer(1200, ev -> copyButton.setText(original));
                    restore.setRepeats(false);
                    restore.start();
                });
                buildPanel.add(copyButton, BorderLayout.EAST);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.buildNumber"), buildPanel);

                JPanel ioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
                ioPanel.setOpaque(false);
                JButton importButton = new JButton(MpSqlVisualizerBundle.message("settings.import"));
                importButton.setToolTipText(MpSqlVisualizerBundle.message("settings.import.tip"));
                importButton.addActionListener(e -> self.importSettings());
                ioPanel.add(importButton);
                JButton exportButton = new JButton(MpSqlVisualizerBundle.message("settings.export"));
                exportButton.setToolTipText(MpSqlVisualizerBundle.message("settings.export.tip"));
                exportButton.addActionListener(e -> self.exportSettings());
                ioPanel.add(exportButton);
                JPanel ioRow = new JPanel(new BorderLayout(8, 0));
                ioRow.setOpaque(false);
                ioRow.add(ioPanel, BorderLayout.CENTER);
                ioRow.add(self.statusLabel, BorderLayout.EAST);
                addRow(p, g, MpSqlVisualizerBundle.message("settings.pluginSettings"), ioRow);
            });
        }

        // 底部 weighty 占位（内容顶置）
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JPanel(), gbc);
    }

    private static void addRow(JPanel panel, GridBagConstraints gbc, String label, JComponent component) {
        addRow(panel, gbc, label, component, GridBagConstraints.NONE);
    }

    /** Kotlin 默认参数等价重载：{@code fill} 缺省 NONE（T-FramePrefixResize 分栏行用 HORIZONTAL）。 */
    private static void addRow(JPanel panel, GridBagConstraints gbc, String label, JComponent component, int fill) {
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        panel.add(new JBLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = fill;
        panel.add(component, gbc);
        gbc.fill = GridBagConstraints.NONE;
        gbc.gridy++;
    }

    /**
     * T-M19-StorageClear 披露行（master {@code disclosure(key, action)} 的 Java 等价）：
     * 左侧说明文案；action 非空时挂 EAST（打开目录 / 清空按钮），null 为纯边界说明行。
     */
    private static void addDisclosure(JPanel panel, GridBagConstraints gbc, String label, JButton action) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.add(new JBLabel(label), BorderLayout.CENTER);
        if (action != null) {
            JPanel east = new JPanel(new BorderLayout());
            east.setOpaque(false);
            east.add(action, BorderLayout.EAST);
            row.add(east, BorderLayout.EAST);
        }
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        panel.add(row, gbc);
        gbc.gridwidth = 1;
        gbc.weightx = 0.0;
        gbc.gridy++;
    }

    /** 分隔线 + 内容面板（无边框、无折叠）。 */
    private static void sectionGroup(JPanel panel,
                                     GridBagConstraints gbc,
                                     String titleKey,
                                     BiConsumer<JPanel, GridBagConstraints> content) {
        JPanel contentPanel = new JPanel(new GridBagLayout());
        GridBagConstraints inner = new GridBagConstraints();
        inner.gridx = 0;
        inner.gridy = 0;
        inner.anchor = GridBagConstraints.WEST;
        inner.insets = new Insets(2, 6, 2, 6);
        content.accept(contentPanel, inner);

        // 计算内容区标签最大宽度，统一列宽
        GridBagLayout layout = (GridBagLayout) contentPanel.getLayout();
        int maxLabelWidth = 0;
        for (Component comp : contentPanel.getComponents()) {
            if (comp instanceof JBLabel) {
                maxLabelWidth = Math.max(maxLabelWidth, comp.getPreferredSize().width);
            }
        }
        if (maxLabelWidth > 0) {
            layout.columnWidths = new int[]{maxLabelWidth + 8, 0};
        }

        // 分隔线（仅横线，无边框）
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new TitledSeparator(MpSqlVisualizerBundle.message(titleKey)), gbc);
        gbc.gridy++;

        // 内容面板
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(contentPanel, gbc);
        gbc.gridy++;
    }
}
