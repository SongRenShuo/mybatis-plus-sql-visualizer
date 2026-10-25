package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目级设置页（O-5 Phase B；T-SettingsTruth P4 接线补完）。
 *
 * 已注册 plugin.xml {@code <projectConfigurable parentId="tools">}（项目级 configurable 不得挂在
 * 应用级 configurable 之下——平台限制，挂 tools 顶层与全局页平级），Settings → Tools →
 * SQL Visualizer（Current Project）。
 *
 * 展示「使用项目设置」总开关 + 十项可覆盖字段（State 十字段一一对应）：
 * 方言 / 参数替换 / 格式化 / 逻辑删除注入 / 逻辑删除值 / 表前缀 / 表后缀 / 推断策略 / 忽略字段 /
 * 列名数据源校正。
 * 开关类字段 tri-state 语义：null = 使用全局（控件初始灌全局值，apply 时写实际勾选态）；
 * 字符串字段空 = 使用全局（tooltip 说明，{@code settings.project.field.tip}）。
 *
 * O-58：列名数据源校正（{@code columnCalibration}）与 {@code replaceParams} / {@code formatSql} / {@code injectLogicDelete}
 * 语义完全同类（都是 {@code Boolean} 可空，null=跟随全局），故控件形态、显示与回写链路<b>逐条照抄
 * {@code replaceParamsCheck}</b>（{@code JBCheckBox} + {@link #shownBool} 灌值 + {@link #boolDiffers} 比对/写回），不自创形态。
 * 已知取舍：{@code null}（跟随全局）与 {@code false}（显式禁用）在复选框上不可区分——这对既有三个布尔覆盖
 * 同样成立，是本页的既有整体设计（null 的复位由「重置为跟随全局」承担），不给单个新项开特例。
 *
 * T-UiNative T-UN-15（试点）：布局迁 Kotlin UI DSL v2（k2j 侧以 GridBag 手摆等价呈现，
 * 控件语义与联动逐条对齐）。apply/isModified/reset 手写逻辑保留（面板存取仍走 {@link ProjectPluginSettings} 单例）。
 *
 * T-RA-04 判定：原 {@code modified} 标志位已删除。T-SettingsTruth 认定该标志位不可信后
 * isModified() 改纯逐项比对，标志位只剩 13 处写入、零读取——保留等于在类里留一份
 * 「写了却没人信」的假状态（后续读者会误以为它参与判定）；控件监听器也不再为置位而挂。
 * isModified() 逐项比对控件与 state（null 语义：checkbox 勾选态与 state 值按
 * 「null 视为全局值」比较）。
 *
 * T-DialectAutoInfer：方言下拉首项为「智能推断」哨兵——选中即项目级 dialectAuto=true，
 * 选具体方言名即 dialectAuto=false 并把值写进 dialect（state.dialectAuto=null 表示跟随全局，
 * 显示项按全局模式推导，见 {@link #expectedDialectItem}）。哨兵项不复活 T-RA-04 删除的 modified
 * 标志位：apply 只按控件显示值甄别两种语义写 state，isModified 仍走纯逐项比对。
 *
 * T-ProjectSettingsScope P1（2026-09-18）：总开关关闭时 10 项<b>置灰并显示全局生效值</b>——
 * 原实现无条件灌「state 残值」而运行时按全局取值，页面所见与运行时所用不一致（用户看不出真实生效值）。
 * 四处必须联动（缺一处即回到原缺陷）：{@link #refreshControls}（灌值 + 可用性）、{@link #apply}（关闭态不写 10 项，
 * 否则把显示的全局值当成项目自定义值钉进 state）、{@link #isModified}（关闭态只比总开关，否则显示全局值
 * vs state 残值恒报已修改）。
 *
 * T-ProjectSettingsScope P2：「重置为跟随全局」按钮清除 10 项残值（总开关不动，见 {@link #resetToGlobal}）。
 *
 * T-TS-FixG7（P1 同类缺陷的第二处出口，独立复核抓出）：开启态 {@link #apply} <b>只写回「控件显示值 !=
 * state 推导值」的项</b>。原实现无条件写回 10 项，会把「空/null = 跟随全局」的项钉成当时的全局快照
 * （重置 → 改一项 → apply 即触发），全局值日后变更本项目不再跟随而 UI 数值不变。
 * 语义定案：<b>「重置后没碰过的项继续跟随全局，碰过的项才成为覆盖」</b>——「碰过」以
 * 「控件显示值是否仍等于 state 推导值」判定，与 {@link #isModified} 同源，不引入额外的跟随标记/抑制标志。
 */
public class ProjectPluginSettingsConfigurable implements Configurable {

    private final Project project;
    private final ProjectPluginSettings projectSettings;
    private JBCheckBox useProjectCheck = null;
    private JLabel scopeStatusLabel = null;

    /** R51/T-ModuleBinding：模块→数据源绑定摘要标签与清空按钮（master ProjectPluginSettingsConfigurable 同款）。 */
    private JLabel moduleBindingLabel = null;
    private JButton clearModuleBindingsButton = null;
    private ComboBox<String> dialectCombo = null;
    private JBCheckBox replaceParamsCheck = null;
    private JBCheckBox formatSqlCheck = null;
    private JBCheckBox injectLogicDeleteCheck = null;
    private JBTextField logicDeleteValueField = null;
    private JBTextField tablePrefixField = null;
    private JBTextField tableSuffixField = null;
    private ComboBox<InferenceOption> inferenceCombo = null;
    private JBTextField ignoredFieldsField = null;

    /** O-58：列名数据源校正开关（null = 跟随全局，与 {@link #replaceParamsCheck} 同范式）。 */
    private JBCheckBox columnCalibrationCheck = null;

    /**
     * T-ProjectSettingsScope P1：10 项可覆盖字段的控件引用——总开关关闭时统一置灰。
     * 只用于「可用性」联动（值刷新走上面各控件字段，dispose 后为空安全）；dispose 时清空避免持有组件。
     */
    private final List<JComponent> projectValueCells = new ArrayList<>();

    /**
     * 表名推断策略下拉项：值 + 当前语言下的显示文案（复用全局面板同一组 bundle 键，
     * 禁用枚举原值直接当显示项——否则中文界面出现 {@code SNAKE}/`CAMEL` 裸值，与全局页文案不一致）。
     * 必须自定义 equals：JDK17 {@code JComboBox.setSelectedItem} 按 equals 匹配
     * （见 PluginSettingsPanel 同类教训）；record 组件 equals 覆盖 value+label，与 Kotlin data class 一致。
     */
    private record InferenceOption(
        /** 策略值（INFERENCE_SNAKE / INFERENCE_CAMEL，持久化值）。 */
        String value,
        /** 当前语言下的显示文案。 */
        String label
    ) {
        @Override
        public String toString() {
            return label;
        }
    }

    private InferenceOption[] inferenceOptions() {
        return new InferenceOption[]{
            new InferenceOption(PluginSettings.INFERENCE_SNAKE, MpSqlVisualizerBundle.message("settings.tableNameInference.snake")),
            new InferenceOption(PluginSettings.INFERENCE_CAMEL, MpSqlVisualizerBundle.message("settings.tableNameInference.camel")),
        };
    }

    /** 全局设置（测试环境未注册 service 时兜底默认 State）。 */
    private PluginSettings.State globalState() {
        try {
            return PluginSettings.getInstance().getState();
        } catch (Throwable e) {
            return new PluginSettings.State();
        }
    }

    private String fieldTip() {
        return MpSqlVisualizerBundle.message("settings.project.field.tip");
    }

    /** T-DialectAutoInfer：「智能推断」哨兵项（createComponent 时按当前语言初始化）。 */
    private final String autoItem = MpSqlVisualizerBundle.message("settings.dialect.auto");

    public ProjectPluginSettingsConfigurable(Project project) {
        this.project = project;
        this.projectSettings = ProjectPluginSettings.getInstance(project);
    }

    @Override
    public String getDisplayName() {
        return MpSqlVisualizerBundle.message("plugin.name");
    }

    @NotNull
    @Override
    public JComponent createComponent() {
        ProjectPluginSettings.State st = projectSettings.getState();
        // T-UiNative T-UN-15：布局层等价呈现；控件初始值统一经 refreshControls 灌入
        // （灌值分支只此一处，防「四处联动」漏改）。
        // T-RA-04：原先每个控件都挂 addActionListener { modified = true } 只为置标志位，
        // 标志位删除后这些监听器一并移除（isModified 逐项比对控件显示值，无需变更通知）。
        // T-ProjectSettingsScope P1：总开关的监听器有真实职责（关闭态刷新 10 项可用性与显示值），
        // 与 T-RA-04 删除的「只为置假标志位」监听器不同，重新引入不违该定案。
        // T-DialectAutoInfer：auto 哨兵项=「智能推断」；项目级 dialectAuto null=跟随全局，
        // 显示项由 expectedDialectItem 按有效模式推导（与 reset/isModified 同源）。
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        JBCheckBox check = new JBCheckBox(MpSqlVisualizerBundle.message("settings.project.useProjectSettings"));
        check.setToolTipText(MpSqlVisualizerBundle.message("settings.project.useProjectSettings.tip"));
        check.addActionListener(e -> refreshControls(check.isSelected()));
        useProjectCheck = check;
        gbc.gridwidth = 2;
        panel.add(check, gbc);

        // P1 可选增强：关闭态范围提示行（既有状态行形态：文案空时留一个空格占位，避免行高跳动）
        gbc.gridy++;
        JLabel scopeLabel = new JLabel(" ");
        scopeStatusLabel = scopeLabel;
        panel.add(scopeLabel, gbc);
        gbc.gridwidth = 1;

        // 方言控件字段须在此落地（master 侧 DSL 为 `comboCell = comboBox(...)` + `dialectCombo =
        // comboCell.component`）：本行原先直传 buildDialectCombo() 返回值而不赋值，dialectCombo
        // 恒为 null → 下方 projectValueCells 收进一个 null 元素，refreshControls 的置灰循环 NPE，
        // 方言项本身也永远灌不进值（master 侧列表元素皆为非空 Cell）。
        dialectCombo = buildDialectCombo();
        addLabeledRow(panel, gbc, MpSqlVisualizerBundle.message("settings.dialect"), dialectCombo);
        replaceParamsCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.replaceParams"));
        replaceParamsCheck.setToolTipText(fieldTip());
        addFullRow(panel, gbc, replaceParamsCheck);
        formatSqlCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.formatSql"));
        formatSqlCheck.setToolTipText(fieldTip());
        addFullRow(panel, gbc, formatSqlCheck);
        injectLogicDeleteCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.injectLogicDelete"));
        injectLogicDeleteCheck.setToolTipText(fieldTip());
        addFullRow(panel, gbc, injectLogicDeleteCheck);
        logicDeleteValueField = new JBTextField();
        logicDeleteValueField.setToolTipText(fieldTip());
        addLabeledRow(panel, gbc, MpSqlVisualizerBundle.message("settings.logicDeleteValue"), logicDeleteValueField);
        tablePrefixField = new JBTextField();
        tablePrefixField.setToolTipText(fieldTip());
        addLabeledRow(panel, gbc, MpSqlVisualizerBundle.message("settings.tablePrefix"), tablePrefixField);
        tableSuffixField = new JBTextField();
        tableSuffixField.setToolTipText(fieldTip());
        addLabeledRow(panel, gbc, MpSqlVisualizerBundle.message("settings.tableSuffix"), tableSuffixField);
        inferenceCombo = new ComboBox<>(inferenceOptions());
        addLabeledRow(panel, gbc, MpSqlVisualizerBundle.message("settings.tableNameInference"), inferenceCombo);
        ignoredFieldsField = new JBTextField();
        ignoredFieldsField.setToolTipText(fieldTip());
        addLabeledRow(panel, gbc, MpSqlVisualizerBundle.message("settings.ignoredFields"), ignoredFieldsField);
        // O-58：形态与 tooltip 口径照抄 replaceParams / formatSql / injectLogicDelete
        columnCalibrationCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.columnCalibration"));
        columnCalibrationCheck.setToolTipText(fieldTip());
        addFullRow(panel, gbc, columnCalibrationCheck);

        // T-ProjectSettingsScope P2：形态照抄 PluginSettingsPanel 的「恢复默认颜色」
        // （JButton + bundle 文案 + tooltip + addActionListener，无双确认弹窗），
        // 置于所重置的 10 行之后。始终可用：关闭态正是残值在 UI 上唯一不可见的时刻。
        JButton resetButton = new JButton(MpSqlVisualizerBundle.message("settings.project.reset"));
        resetButton.setToolTipText(MpSqlVisualizerBundle.message("settings.project.reset.tip"));
        resetButton.addActionListener(e -> resetToGlobal());
        gbc.gridy++;
        gbc.gridwidth = 2;
        JPanel resetRow = new JPanel(new BorderLayout(8, 0));
        resetRow.add(resetButton, BorderLayout.WEST);
        panel.add(resetRow, gbc);
        gbc.gridwidth = 1;

        // R51/T-ModuleBinding：模块→数据源绑定摘要 + 清空入口（走 ModuleDataSourceBindingStore）
        moduleBindingLabel = new JBLabel(moduleBindingSummary());
        clearModuleBindingsButton = new JButton(
                MpSqlVisualizerBundle.message("settings.project.moduleBindings.clear"));
        clearModuleBindingsButton.addActionListener(e -> clearModuleBindings());
        gbc.gridy++;
        gbc.gridwidth = 2;
        JPanel bindingRow = new JPanel(new BorderLayout(8, 0));
        bindingRow.add(new JBLabel(MpSqlVisualizerBundle.message("settings.project.moduleBindings")),
                BorderLayout.WEST);
        bindingRow.add(moduleBindingLabel, BorderLayout.CENTER);
        bindingRow.add(clearModuleBindingsButton, BorderLayout.EAST);
        panel.add(bindingRow, gbc);
        gbc.gridwidth = 1;

        projectValueCells.clear();
        projectValueCells.add(dialectCombo);
        projectValueCells.add(replaceParamsCheck);
        projectValueCells.add(formatSqlCheck);
        projectValueCells.add(injectLogicDeleteCheck);
        projectValueCells.add(logicDeleteValueField);
        projectValueCells.add(tablePrefixField);
        projectValueCells.add(tableSuffixField);
        projectValueCells.add(inferenceCombo);
        projectValueCells.add(ignoredFieldsField);
        projectValueCells.add(columnCalibrationCheck);
        refreshControls(st.getUseProjectSettings());
        return panel;
    }

    /** 方言下拉：首项「智能推断」哨兵 + 注册表方言名。 */
    private ComboBox<String> buildDialectCombo() {
        List<String> items = new ArrayList<>();
        items.add(autoItem);
        for (Dialect d : DialectRegistry.INSTANCE.getDialects()) {
            items.add(d.getName());
        }
        return new ComboBox<>(items.toArray(new String[0]));
    }

    /** 标签 + 控件行。 */
    private void addLabeledRow(JPanel panel, GridBagConstraints gbc, String label, JComponent field) {
        gbc.gridy++;
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JBLabel(label), gbc);
        gbc.gridx = 1;
        gbc.insets = new Insets(4, 24, 4, 6); // 缩进
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, gbc);
        gbc.gridx = 0;
    }

    /** 整行控件（checkbox 行）。 */
    private void addFullRow(JPanel panel, GridBagConstraints gbc, JComponent field) {
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.NONE;
        panel.add(field, gbc);
        gbc.gridwidth = 1;
    }

    /** 当前页面按「项目自定义值」还是「全局生效值」呈现：以总开关控件为准（控件未建时回落 state）。 */
    private boolean showProjectNow() {
        if (useProjectCheck != null) {
            return useProjectCheck.isSelected();
        }
        return projectSettings.getState().getUseProjectSettings();
    }

    /** 三态布尔项当前应显示的值（关闭态一律显示全局生效值）。 */
    private boolean shownBool(Boolean stateValue, boolean globalValue, boolean showProject) {
        return showProject ? (stateValue != null ? stateValue : globalValue) : globalValue;
    }

    /** 文本项当前应显示的值（关闭态一律显示全局生效值）。 */
    private String shownText(String stateValue, String globalValue, boolean showProject) {
        return showProject ? (stateValue.isBlank() ? globalValue : stateValue) : globalValue;
    }

    /**
     * T-ProjectSettingsScope P1：把「当前应显示的值 + 可编辑性」灌进控件。
     *
     * createComponent（初值）/ {@link #reset} / 总开关监听器三处共用同一份表达式，防规格所点
     * 「四处联动只修一处」回潮。
     *
     * @param showProject true=按项目自定义值呈现；false=一律呈现全局生效值（页面所见即运行时所用）
     */
    private void refreshControls(boolean showProject) {
        ProjectPluginSettings.State st = projectSettings.getState();
        PluginSettings.State global = globalState();
        if (useProjectCheck != null) {
            useProjectCheck.setSelected(showProject);
        }
        if (scopeStatusLabel != null) {
            scopeStatusLabel.setText(showProject ? " " : MpSqlVisualizerBundle.message("settings.project.scope.global"));
        }
        if (dialectCombo != null) {
            dialectCombo.setSelectedItem(expectedDialectItem(st, global, showProject));
        }
        if (replaceParamsCheck != null) {
            replaceParamsCheck.setSelected(shownBool(st.getReplaceParams(), global.getReplaceParams(), showProject));
        }
        if (formatSqlCheck != null) {
            formatSqlCheck.setSelected(shownBool(st.getFormatSql(), global.getFormatSql(), showProject));
        }
        if (injectLogicDeleteCheck != null) {
            injectLogicDeleteCheck.setSelected(shownBool(st.getInjectLogicDelete(), global.getInjectLogicDelete(), showProject));
        }
        if (logicDeleteValueField != null) {
            logicDeleteValueField.setText(shownText(st.getLogicDeleteValue(), global.getLogicDeleteValue(), showProject));
        }
        if (tablePrefixField != null) {
            tablePrefixField.setText(shownText(st.getTablePrefix(), global.getTablePrefix(), showProject));
        }
        if (tableSuffixField != null) {
            tableSuffixField.setText(shownText(st.getTableSuffix(), global.getTableSuffix(), showProject));
        }
        // 回灌按 data class 选项 equals 匹配（传裸值 setSelectedItem 静默 return，选中不生效）
        if (inferenceCombo != null) {
            String inferenceShown = shownText(st.getTableNameInference(), global.getTableNameInference(), showProject);
            InferenceOption matched = null;
            for (InferenceOption option : inferenceOptions()) {
                if (option.value().equals(inferenceShown)) {
                    matched = option;
                    break;
                }
            }
            inferenceCombo.setSelectedItem(matched != null ? matched : inferenceOptions()[0]);
        }
        if (ignoredFieldsField != null) {
            ignoredFieldsField.setText(shownText(st.getIgnoredFields(), global.getIgnoredFields(), showProject));
        }
        // O-58：null 视为全局值（与 shownBool 的既有三态口径同源）
        if (columnCalibrationCheck != null) {
            columnCalibrationCheck.setSelected(shownBool(st.getColumnCalibration(), global.getColumnCalibrationEnabled(), showProject));
        }
        // 关闭态置灰：这 10 行显示的是全局生效值，编辑它们没有落点（apply 不写回，见 apply）
        for (JComponent cell : projectValueCells) {
            cell.setEnabled(showProject);
        }
    }

    /**
     * T-ProjectSettingsScope P2：「重置为跟随全局」——10 项归零/null（dialectAuto 一并归 null），
     * <b>总开关保持不动</b>（按钮语义是「清除本项目自定义值」，不是「停用项目设置」）。
     *
     * 直接改 state 而非只改控件显示值：关闭态 apply 不写这 10 项，只改控件则残值永远清不掉；
     * 开启态把「显示的全局值」写进 state 又会变成硬覆盖值，与「跟随全局」语义相反。
     *
     * T-TS-FixG7：重置后控件按 P1 回灌全局值，但 apply 只写回「被用户动过」的项，
     * 故回灌本身不会再把这些项钉成当时的全局快照（详见 apply 的 T-TS-FixG7 注释）。
     */
    private void resetToGlobal() {
        ProjectPluginSettings.State st = projectSettings.getState();
        st.setDialect("");
        st.setDialectAuto(null);
        st.setReplaceParams(null);
        st.setFormatSql(null);
        st.setInjectLogicDelete(null);
        st.setLogicDeleteValue("");
        st.setTablePrefix("");
        st.setTableSuffix("");
        st.setTableNameInference("");
        st.setIgnoredFields("");
        st.setColumnCalibration(null);
        refreshControls(showProjectNow());
    }

    /**
     * T-DialectAutoInfer + T-ProjectSettingsScope P1：方言下拉应显示的项。
     *
     * 开启态：有效模式为推断时显示哨兵项「智能推断」，否则显示项目手动值（空则回退全局手动值）。
     * 关闭态（showProject=false）：一律按全局推导（全局推断开则哨兵项，否则全局方言名），
     * 与运行时 {@link ProjectPluginSettings#effectiveDialectAuto} / {@link ProjectPluginSettings#effectiveDialect}
     * 的取值口径一致——页面所见即运行时所用。
     *
     * createComponent / reset / 总开关联动 / isModified 四处共用同一推导：T-RA-04 起 isModified 是
     * 「控件显示值 vs state」的逐项比对，哨兵项使显示值与 {@code state.dialect} 不再一一对应（auto 模式下
     * state.dialect 是保留的手动值、并非显示值），故必须由同一函数把 state 反推成显示项，否则哨兵项
     * 会让未改动的页面每次打开都误报已修改。
     */
    private String expectedDialectItem(ProjectPluginSettings.State st, PluginSettings.State global, boolean showProject) {
        boolean auto = showProject
            ? (st.getDialectAuto() != null ? st.getDialectAuto() : global.getDialectAuto())
            : global.getDialectAuto();
        if (auto) {
            return autoItem;
        }
        return showProject
            ? (st.getDialect().isBlank() ? global.getDialect() : st.getDialect())
            : global.getDialect();
    }

    /** 三态布尔比对：state 为 null 视为全局值，与勾选态比较（只在总开关开启态比对时调用）。 */
    private boolean boolDiffers(JBCheckBox check, Boolean stateValue, boolean globalValue) {
        if (check == null) {
            return false;
        }
        return check.isSelected() != shownBool(stateValue, globalValue, true);
    }

    /** 文本比对：state 空视为全局值（只在总开关开启态比对时调用）。 */
    private boolean stringDiffers(JBTextField field, String stateValue, String globalValue) {
        if (field == null) {
            return false;
        }
        return !field.getText().trim().equals(shownText(stateValue, globalValue, true));
    }

    @Override
    public boolean isModified() {
        ProjectPluginSettings.State st = projectSettings.getState();
        PluginSettings.State global = globalState();
        boolean showProject = showProjectNow();
        // 平台 ComboBox 对同值 setSelectedItem 也派发 ActionEvent（262 实证）——
        // createComponent/reset 初始灌值即会误报「已修改」；故本方法一律逐项比对
        // （控件显示值 vs state+全局兜底语义），不依赖任何标志位（T-RA-04 已删除原标志位）。
        if (showProject != st.getUseProjectSettings()) {
            return true;
        }
        // T-ProjectSettingsScope P1：关闭态 10 项显示的是全局生效值、apply 也不写这 10 项，
        // 故不参与比对——若仍比对，「控件显示全局值 vs state 残值」恒报已修改
        // （误报形态同 T-SettingsTruth 6.3：页面一打开就是「已修改」）。
        if (!showProject) {
            return false;
        }
        // 方言控件显示值回灌全局兜底（st.dialect 空时显示 global.dialect），比对须对齐同语义，
        // 否则「项目级方言未配置」在每次打开设置页都误报已修改（实证：reset 前未改动 isModified=true）
        // T-DialectAutoInfer：期望显示项含哨兵项分支，与 createComponent/reset 同源推导
        Object selectedDialect = dialectCombo != null ? dialectCombo.getSelectedItem() : null;
        String dialectShown = selectedDialect instanceof String s ? s : expectedDialectItem(st, global, true);
        if (!dialectShown.equals(expectedDialectItem(st, global, true))) {
            return true;
        }
        if (boolDiffers(replaceParamsCheck, st.getReplaceParams(), global.getReplaceParams())) {
            return true;
        }
        if (boolDiffers(formatSqlCheck, st.getFormatSql(), global.getFormatSql())) {
            return true;
        }
        if (boolDiffers(injectLogicDeleteCheck, st.getInjectLogicDelete(), global.getInjectLogicDelete())) {
            return true;
        }
        if (stringDiffers(logicDeleteValueField, st.getLogicDeleteValue(), global.getLogicDeleteValue())) {
            return true;
        }
        if (stringDiffers(tablePrefixField, st.getTablePrefix(), global.getTablePrefix())) {
            return true;
        }
        if (stringDiffers(tableSuffixField, st.getTableSuffix(), global.getTableSuffix())) {
            return true;
        }
        // 推断策略下拉按 data class 选项取值（控件未建时回落 state 值 → 恒等不误报）
        String inferenceSelected = inferenceCombo != null && inferenceCombo.getSelectedItem() instanceof InferenceOption opt
            ? opt.value()
            : null;
        String inferenceStateShown = st.getTableNameInference().isBlank() ? global.getTableNameInference() : st.getTableNameInference();
        String inferenceShown = inferenceSelected != null ? inferenceSelected : inferenceStateShown;
        if (!inferenceShown.equals(inferenceStateShown)) {
            return true;
        }
        if (stringDiffers(ignoredFieldsField, st.getIgnoredFields(), global.getIgnoredFields())) {
            return true;
        }
        if (boolDiffers(columnCalibrationCheck, st.getColumnCalibration(), global.getColumnCalibrationEnabled())) {
            return true;
        }
        return false;
    }

    @Override
    public void apply() {
        ProjectPluginSettings.State st = projectSettings.getState();
        PluginSettings.State global = globalState();
        boolean showProject = showProjectNow();
        st.setUseProjectSettings(showProject);
        // T-ProjectSettingsScope P1：关闭态不写 10 项——此时控件显示的是<b>全局生效值</b>，
        // 无条件写回会把全局值（以及空/null 字段的全局兜底值）当成项目自定义值钉进 state：
        // 用户重开总开关后发现自定义值已被覆盖，或「跟随全局」被悄悄改成硬覆盖值。
        if (!showProject) {
            // T-FS-08：关闭态同样回灌（与 P1「页面所见即运行时所用」同款——此时控件本就显示全局生效值，
            // 回灌无变化），保证 apply 之后「控件显示值 ≡ 派生(state)」恒成立。
            refreshControls(false);
            return;
        }
        // T-TS-FixG7：开启态<b>只写回「控件显示值 != state 推导值」的项</b>——判据与 isModified 的
        // 逐项比对同源（boolDiffers/stringDiffers/expectedDialectItem），即「用户确实动过这一项」。
        //
        // 原实现无条件写回全部 10 项，会把「空/null = 跟随全局」的项钉成<b>当时的全局快照</b>：
        // 开启态点「重置为跟随全局」（10 项置空 → refreshControls 按 P1 回灌全局值）→ 用户再改
        // 任意一项使 isModified()==true → apply 把 10 个控件的显示值（= 全局值）写进 state →
        // 「跟随全局」被静默改成硬覆盖：全局值日后变更本项目不再跟随，而 UI 数值与重置瞬间逐字相同，
        // 用户看不出差别。这与 P1 要消灭的是同一类缺陷（页面语义与运行时语义不一致），只是出口在 apply。
        //
        // 跳过「未编辑项」不会丢用户值：非空/非 null 的 state 值经 shownBool/shownText 推导后
        // 恒等于控件显示值，故真正改过的项（含把字段清空以回到「跟随全局」）一律仍写回。
        // 不采用「resetToGlobal 打跟随标记 + 编辑时逐项清标记」方案：该标记只覆盖 reset 一条路径
        // （对话框取消/重开后标记随实例丢弃，且初始打开时 state 为空的项同样会被钉成全局快照），
        // 还要额外维护监听器与灌值抑制标志——能在同一处用纯比对表达，就不再添一份需人工同步的状态
        // （T-RA-04 判定：标志位类状态一旦无人可信即应删除，此处无必要引入）。
        Object selectedDialectRaw = dialectCombo != null ? dialectCombo.getSelectedItem() : null;
        if (selectedDialectRaw instanceof String selected) {
            if (!selected.equals(expectedDialectItem(st, global, showProject))) {
                // T-DialectAutoInfer：哨兵项=项目级推断（true）；具体方言=手动（false + 值）。
                // auto 分支不改 st.dialect——手动值作为记忆保留，切回手动时不必重选
                if (selected.equals(autoItem)) {
                    st.setDialectAuto(true);
                } else {
                    st.setDialectAuto(false);
                    st.setDialect(selected);
                }
            }
        }
        if (replaceParamsCheck != null && boolDiffers(replaceParamsCheck, st.getReplaceParams(), global.getReplaceParams())) {
            st.setReplaceParams(replaceParamsCheck.isSelected());
        }
        if (formatSqlCheck != null && boolDiffers(formatSqlCheck, st.getFormatSql(), global.getFormatSql())) {
            st.setFormatSql(formatSqlCheck.isSelected());
        }
        if (injectLogicDeleteCheck != null && boolDiffers(injectLogicDeleteCheck, st.getInjectLogicDelete(), global.getInjectLogicDelete())) {
            st.setInjectLogicDelete(injectLogicDeleteCheck.isSelected());
        }
        if (logicDeleteValueField != null && stringDiffers(logicDeleteValueField, st.getLogicDeleteValue(), global.getLogicDeleteValue())) {
            st.setLogicDeleteValue(logicDeleteValueField.getText().trim());
        }
        if (tablePrefixField != null && stringDiffers(tablePrefixField, st.getTablePrefix(), global.getTablePrefix())) {
            st.setTablePrefix(tablePrefixField.getText().trim());
        }
        if (tableSuffixField != null && stringDiffers(tableSuffixField, st.getTableSuffix(), global.getTableSuffix())) {
            st.setTableSuffix(tableSuffixField.getText().trim());
        }
        if (inferenceCombo != null) {
            String selected = inferenceCombo.getSelectedItem() instanceof InferenceOption opt ? opt.value() : "";
            String stateShown = st.getTableNameInference().isBlank() ? global.getTableNameInference() : st.getTableNameInference();
            if (!selected.equals(stateShown)) {
                st.setTableNameInference(selected);
            }
        }
        if (ignoredFieldsField != null && stringDiffers(ignoredFieldsField, st.getIgnoredFields(), global.getIgnoredFields())) {
            st.setIgnoredFields(ignoredFieldsField.getText().trim());
        }
        if (columnCalibrationCheck != null && boolDiffers(columnCalibrationCheck, st.getColumnCalibration(), global.getColumnCalibrationEnabled())) {
            st.setColumnCalibration(columnCalibrationCheck.isSelected());
        }
        // T-FS-08：写回后按同一批取值函数（shownBool/shownText/expectedDialectItem，经 refreshControls）
        // 重新推导控件显示值——apply 之后「控件显示值 ≡ 派生(state)」恒成立，isModified 随之复位。
        // 缺这一步时「开启态清空文本项 → 应用」留下错位：state 已按语义写回空串（= 跟随全局，运行时
        // 有效值回落到全局值），控件却停在空串 → 逐项比对恒报已修改（应用按钮点不灭，重开设置页显示
        // 又跳回全局值，用户无从判断自己那次清空到底生效没有）。
        refreshControls(showProject);
    }

    @Override
    public void reset() {
        // 回灌统一走 refreshControls（与 createComponent/总开关联动同源表达式）
        refreshControls(projectSettings.getState().getUseProjectSettings());
    }

    /** 绑定条数摘要（仅计数，不外泄数据源名/模块名）。 */
    private String moduleBindingSummary() {
        int count = project != null
                ? cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore.getInstance(project)
                        .getState().bindings.size()
                : 0;
        return MpSqlVisualizerBundle.message("settings.project.moduleBindings.count", count);
    }

    /** 清空全部模块绑定（先确认，与设置页其余破坏性动作同款）。 */
    private void clearModuleBindings() {
        if (project == null) {
            return;
        }
        int answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                MpSqlVisualizerBundle.message("settings.project.moduleBindings.clear.confirm"),
                MpSqlVisualizerBundle.message("settings.project.moduleBindings.clear.title"),
                com.intellij.icons.AllIcons.General.QuestionDialog);
        if (answer == com.intellij.openapi.ui.Messages.YES) {
            cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore.getInstance(project).clear();
            // T-ModuleScopedLastUsed W4：连带清「上次使用」的**全部层级**（项目级 + 所有模块分桶）。
            // 否则用户点了「清空模块学习」，指示行灰字仍显示旧源——清空语义名不副实。
            // （master 为 runCatching 降级形态；依赖 B 波 B1 批的 DataSourceSelector.forgetAllLastUsed，
            //  中央编译收口验证。）
            try {
                cn.song.mybatisplus.visualizer.execute.DataSourceSelector.forgetAllLastUsed(project);
            } catch (Throwable e) {
                // 清空失败不阻断绑定清理收尾（降级与 master runCatching 同源）
            }
            if (moduleBindingLabel != null) {
                moduleBindingLabel.setText(moduleBindingSummary());
            }
        }
    }

    /** 测试缝：绑定摘要标签（R51 设置页可达性断言）。 */
    public JLabel moduleBindingLabelForTest() {
        return moduleBindingLabel;
    }

    /** 测试缝：清空绑定按钮。 */
    public JButton clearModuleBindingsButtonForTest() {
        return clearModuleBindingsButton;
    }

    @Override
    public void disposeUIResources() {
        useProjectCheck = null;
        scopeStatusLabel = null;
        dialectCombo = null;
        replaceParamsCheck = null;
        formatSqlCheck = null;
        injectLogicDeleteCheck = null;
        logicDeleteValueField = null;
        tablePrefixField = null;
        tableSuffixField = null;
        inferenceCombo = null;
        ignoredFieldsField = null;
        columnCalibrationCheck = null;
        moduleBindingLabel = null;
        clearModuleBindingsButton = null;
        projectValueCells.clear();
    }
}
