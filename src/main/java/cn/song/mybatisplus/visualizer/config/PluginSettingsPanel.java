package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.actions.ConvertSqlToJavaAction;
import cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction;
import cn.song.mybatisplus.visualizer.actions.ExplainSelectedTableAction;
import cn.song.mybatisplus.visualizer.actions.CountSelectedTableAction;
import cn.song.mybatisplus.visualizer.actions.SampleSelectedTableAction;
import cn.song.mybatisplus.visualizer.log.agent.AgentManager;
import cn.song.mybatisplus.visualizer.log.agent.AgentStatusListener;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.PluginBuildInfo;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import cn.song.mybatisplus.visualizer.log.SourceCaptureGuide;
import cn.song.mybatisplus.visualizer.log.SqlListDisplayMode;
import cn.song.mybatisplus.visualizer.log.SqlListTypeColor;
import cn.song.mybatisplus.visualizer.util.PluginCacheManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.ColorPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentListener;

/**
 * 设置表单面板（T-M5-02，F-16 配置项 UI）。
 *
 * 展示并可编辑全部配置项；{@link #apply()}/{@link #reset()}/{@link #isModified()} 与 {@link PluginSettings} 联动，
 * 由 {@link SectionConfigurable} 子类（SQL 预览/表名映射/SQL 日志/界面与操作/关于）接入 IDE Settings
 * 框架（保存即生效）。
 *
 * M14 增强：
 *  - 「界面语言」下拉（默认简体中文）——保存后经 {@link #rebuild()} 重建表单即时生效；
 *  - 「快捷键」行——{@link ShortcutEditor} **内嵌编辑** Convert to SQL 快捷键，
 *    保存时直接写入 IDE 当前 Keymap（不再跳转）。
 *
 * M14.1（用户反馈）：
 *  - 移除悬停预览相关配置（autoPreview / hoverDelay 已随功能删除）；
 *  - 布局紧凑化（减小行距 + 底部 weighty 占位，消除内容区上方空白）。
 *
 * M16.2 增强：
 *  - 「表前缀」「表后缀」文本输入行（可添加/清除）——统一修饰预览 SQL 中的表名（全局语义）。
 */
public class PluginSettingsPanel implements Disposable {

    public static final String SECTION_PREVIEW = "preview";
    public static final String SECTION_TABLE_MAPPING = "tableMapping";
    public static final String SECTION_CODE_GEN = "codeGen";
    public static final String SECTION_SQL_LOG = "sqlLog";
    public static final String SECTION_UI = "ui";
    public static final String SECTION_UPDATE_DIAGNOSTICS = "updateDiagnostics";
    /** T-ShortcutSettings：插件快捷键统一管理节（5 个动作快捷键内嵌录制编辑）。 */
    public static final String SECTION_SHORTCUTS = "shortcuts";

    public static final Set<String> SECTION_ALL = Set.of(
        SECTION_PREVIEW,
        SECTION_TABLE_MAPPING,
        SECTION_CODE_GEN,
        SECTION_SQL_LOG,
        SECTION_UI,
        SECTION_UPDATE_DIAGNOSTICS,
        SECTION_SHORTCUTS);

    /** 自动保存文件名模板占位符（如 `$yyyy-MM-dd$`）。 */
    private static final Pattern PATTERN_PLACEHOLDER = Pattern.compile("\\$([^$]+)\\$");

    /**
     * 语言选项（值 + 当前语言下的显示文案；rebuild 时按新语言重建）。
     *
     * M19 修复：必须按 value 判等——JDK17 `JComboBox.setSelectedItem` 会以 `equals` 在 model 中查找元素，
     * **找不到则静默 return**（选中不生效、ActionEvent 不触发）；原无 equals 覆写（身份判等）时
     * `reset()`/写穿入口传入的新实例全部匹配失败——选中显示与写穿监听一并失效。
     */
    public static final class LanguageOption {

        final String value;
        final String label;

        LanguageOption(String value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LanguageOption)) return false;
            return Objects.equals(value, ((LanguageOption) o).value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    public static LanguageOption[] languageOptions() {
        return new LanguageOption[]{
            new LanguageOption("zh", MpSqlVisualizerBundle.message("settings.language.zh")),
            new LanguageOption("en", MpSqlVisualizerBundle.message("settings.language.en")),
            new LanguageOption("AUTO", MpSqlVisualizerBundle.message("settings.language.auto")),
        };
    }

    public static LanguageOption languageOptionByValue(String value) {
        for (LanguageOption option : languageOptions()) {
            if (Objects.equals(option.value, value)) return option;
        }
        return null;
    }

    /**
     * 表名推断策略选项（值 + 当前语言下的显示文案；rebuild 时按新语言重建）。
     * 与 {@link LanguageOption} 同理必须按 value 判等（JDK17 `JComboBox.setSelectedItem` 按 equals 匹配）。
     */
    public static final class InferenceOption {

        final String value;
        final String label;

        InferenceOption(String value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InferenceOption)) return false;
            return Objects.equals(value, ((InferenceOption) o).value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    public static InferenceOption[] inferenceOptions() {
        return new InferenceOption[]{
            new InferenceOption(PluginSettings.INFERENCE_SNAKE, MpSqlVisualizerBundle.message("settings.tableNameInference.snake")),
            new InferenceOption(PluginSettings.INFERENCE_CAMEL, MpSqlVisualizerBundle.message("settings.tableNameInference.camel")),
        };
    }

    public static InferenceOption inferenceOptionByValue(String value) {
        for (InferenceOption option : inferenceOptions()) {
            if (Objects.equals(option.value, value)) return option;
        }
        return null;
    }

    /**
     * 反向转换 Wrapper 形态选项（T-M36.3）：值 + 当前语言下的显示文案。
     * 与 {@link LanguageOption} 同理必须按 value 判等（JDK17 `JComboBox.setSelectedItem` 按 equals 匹配）。
     */
    public static final class ReverseFormatOption {

        final String value;
        final String label;

        ReverseFormatOption(String value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReverseFormatOption)) return false;
            return Objects.equals(value, ((ReverseFormatOption) o).value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    public static ReverseFormatOption[] reverseFormatOptions() {
        return new ReverseFormatOption[]{
            new ReverseFormatOption(PluginSettings.REVERSE_AUTO, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.auto")),
            new ReverseFormatOption(PluginSettings.REVERSE_QUERY, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.query")),
            new ReverseFormatOption(PluginSettings.REVERSE_LAMBDA, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.lambda")),
            new ReverseFormatOption(PluginSettings.REVERSE_WRAPPERS, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.wrappers")),
            new ReverseFormatOption(PluginSettings.REVERSE_MPJ_LAMBDA, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.mpjLambda")),
        };
    }

    public static ReverseFormatOption reverseFormatOptionByValue(String value) {
        for (ReverseFormatOption option : reverseFormatOptions()) {
            if (Objects.equals(option.value, value)) return option;
        }
        return null;
    }

    /**
     * T-EmbeddedColPrefix：嵌入列命名约定下拉项（值 + 当前语言显示文案）。
     * 与 {@link LanguageOption} 同理必须按 value 判等（JDK17 `JComboBox.setSelectedItem` 按 equals 匹配）。
     */
    public static final class EmbeddedNamingOption {

        final String value;
        final String label;

        EmbeddedNamingOption(String value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EmbeddedNamingOption)) return false;
            return Objects.equals(value, ((EmbeddedNamingOption) o).value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    /** 嵌入列命名约定选项（T-EmbeddedColPrefix）：AUTO 自动 / NONE 无前缀 / COMPONENT_PATH 组件路径前缀。 */
    public static EmbeddedNamingOption[] embeddedNamingOptions() {
        return new EmbeddedNamingOption[]{
            new EmbeddedNamingOption(PluginSettings.EMBEDDED_NAMING_AUTO, MpSqlVisualizerBundle.message("settings.embeddedColumnNaming.auto")),
            new EmbeddedNamingOption(PluginSettings.EMBEDDED_NAMING_NONE, MpSqlVisualizerBundle.message("settings.embeddedColumnNaming.none")),
            new EmbeddedNamingOption(PluginSettings.EMBEDDED_NAMING_COMPONENT_PATH, MpSqlVisualizerBundle.message("settings.embeddedColumnNaming.componentPath")),
        };
    }

    /** value 匹配选项（k2j 翻译期助手，master 无此函数；形态对齐 {@link #inferenceOptionByValue}）。 */
    public static EmbeddedNamingOption embeddedNamingOptionByValue(String value) {
        for (EmbeddedNamingOption option : embeddedNamingOptions()) {
            if (Objects.equals(option.value, value)) return option;
        }
        return null;
    }

    /**
     * T-CrudColor：CRUD 颜色标记形态下拉项（值 + 当前语言下的显示文案）。
     * 与 {@link LanguageOption} 同理必须按 value 判等（JDK17 `JComboBox.setSelectedItem` 按 equals 匹配）。
     */
    public static final class CrudColorStyleOption {

        final String value;
        final String label;

        CrudColorStyleOption(String value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CrudColorStyleOption)) return false;
            return Objects.equals(value, ((CrudColorStyleOption) o).value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    public static CrudColorStyleOption[] crudColorStyleOptions() {
        return new CrudColorStyleOption[]{
            new CrudColorStyleOption(PluginSettings.CRUD_COLOR_STYLE_BAR, MpSqlVisualizerBundle.message("settings.log.crudColor.style.bar")),
            new CrudColorStyleOption(PluginSettings.CRUD_COLOR_STYLE_BACKGROUND, MpSqlVisualizerBundle.message("settings.log.crudColor.style.background")),
            new CrudColorStyleOption(PluginSettings.CRUD_COLOR_STYLE_OFF, MpSqlVisualizerBundle.message("settings.log.crudColor.style.off")),
        };
    }

    private static CrudColorStyleOption crudColorStyleOptionByValue(String value) {
        for (CrudColorStyleOption option : crudColorStyleOptions()) {
            if (Objects.equals(option.value, value)) return option;
        }
        return null;
    }

    /**
     * T-DisplayModeToSettings：SQL 列表显示模式下拉项（value=SqlListDisplayMode.name）。
     * 与 {@link LanguageOption} 同理必须按 value 判等（JDK17 `JComboBox.setSelectedItem` 按 equals 匹配）。
     */
    public static final class ListDisplayModeOption {

        final String value;
        final String label;

        ListDisplayModeOption(String value, String label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ListDisplayModeOption)) return false;
            return Objects.equals(value, ((ListDisplayModeOption) o).value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    public static ListDisplayModeOption[] listDisplayModeOptions() {
        return new ListDisplayModeOption[]{
            new ListDisplayModeOption(SqlListDisplayMode.SINGLE_LINE.name(), MpSqlVisualizerBundle.message("log.list.displayMode.single")),
            new ListDisplayModeOption(SqlListDisplayMode.KEYWORD_WRAP.name(), MpSqlVisualizerBundle.message("log.list.displayMode.keywordWrap")),
            new ListDisplayModeOption(SqlListDisplayMode.FORMATTED.name(), MpSqlVisualizerBundle.message("log.list.displayMode.formatted")),
        };
    }

    private static ListDisplayModeOption listDisplayModeOptionByValue(String value) {
        for (ListDisplayModeOption option : listDisplayModeOptions()) {
            if (Objects.equals(option.value, value)) return option;
        }
        return null;
    }

    // ---------- T-CrudColor 颜色控件辅助 ----------

    /** T-PT-04 / O-79：当前选中的空参数填充策略（占位档为默认——空选/异常态一律落默认档）。 */
    private String selectedEmptyParamFill() {
        if (emptyParamFillPlaceholderRadio.isSelected()) return PluginSettings.EMPTY_PARAM_FILL_PLACEHOLDER;
        if (emptyParamFillEmptyRadio.isSelected()) return PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING;
        if (emptyParamFillNullRadio.isSelected()) return PluginSettings.EMPTY_PARAM_FILL_NULL;
        return PluginSettings.DEFAULT_EMPTY_PARAM_FILL;
    }

    /** 当前下拉选中的样式值（空选兜默认——与渲染器 styleOf 的兜底保持一致）。 */
    private String selectedCrudColorStyle() {
        Object selected = crudColorStyleCombo.getSelectedItem();
        return selected instanceof CrudColorStyleOption
            ? ((CrudColorStyleOption) selected).value
            : PluginSettings.CRUD_COLOR_STYLE_BAR;
    }

    /** 当前下拉选中的显示模式值（空选兜默认——与 PluginSettings.sqlListDisplayMode 的兜底一致）。 */
    private String selectedListDisplayMode() {
        Object selected = logDisplayModeCombo.getSelectedItem();
        return selected instanceof ListDisplayModeOption
            ? ((ListDisplayModeOption) selected).value
            : PluginSettings.DEFAULT_LOG_LIST_DISPLAY_MODE;
    }

    /** 色板 → `#RRGGBB`（未选色 → 空串，渲染侧按默认色处理）。 */
    private static String colorHexOf(ColorPanel panel) {
        Color c = panel.getSelectedColor();
        if (c == null) return "";
        return String.format(Locale.ENGLISH, "#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** `#RRGGBB` → Color（非法回退默认色，不抛）。 */
    public static Color colorOf(String hex) {
        return SqlListTypeColor.parseHex(hex, new Color(0x4A86E8));
    }

    // T-TenantLine 诊断日志：输出 UI Apply 决策链路。
    private static final Logger LOG = Logger.getInstance(PluginSettingsPanel.class);

    // ------------------------------------------------------------------
    // 表单控件
    // ------------------------------------------------------------------

    public final PluginSettings settings;
    public final Set<String> sections;

    /** 方言下拉（选项来自方言注册表；T-DialectAutoInfer 首项为「智能推断」）。 */
    public final JComboBox<String> dialectCombo = new JComboBox<>(
        buildDialectComboItems());

    /** T-DialectAutoInfer：「智能推断」哨兵项（显示文案随语言变化，写入 state 前须甄别）。 */
    public final String autoDialectItem = MpSqlVisualizerBundle.message("settings.dialect.auto");

    /** T-DialectAutoInfer：推断状态只读行（当前推断结果/依据，或手动指定值）。 */
    public final JLabel dialectInferredLabel = new JLabel();

    /** 方言下拉选项：首项「智能推断」哨兵 + 方言注册表名称。 */
    private static String[] buildDialectComboItems() {
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add(MpSqlVisualizerBundle.message("settings.dialect.auto"));
        for (Dialect d : DialectRegistry.INSTANCE.getDialects()) {
            items.add(d.getName());
        }
        return items.toArray(new String[0]);
    }

    /** 参数替换开关。 */
    public final JBCheckBox replaceParamsCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.replaceParams"));

    /** 格式化开关。 */
    public final JBCheckBox formatCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.formatSql"));

    /** 竖向排列 VALUES 行尾配对列名标注开关（T-VPairAnno，默认开）。 */
    public final JBCheckBox verticalPairAnnotationCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.verticalPairAnnotation"));

    /** SQL 预览字段较多时自动折叠开关（T-AutoFold，默认关）。 */
    public final JBCheckBox autoFoldCheck = new JBCheckBox();

    /** 自动折叠字段数阈值输入框（T-AutoFold）：开关勾选才启用编辑（对齐自动保存联动范式）。 */
    public final JBTextField autoFoldThresholdField = new JBTextField(6);

    /** 字面量参数恢复开关（T-HistParam，默认开）：无 `?` 占位符的日志 SQL 把字符串字面量识别为参数。 */
    public final JBCheckBox literalParamRecoveryCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.literalParamRecovery"));

    // ---------- M56 T-M56-08：增强捕获（agent 字节码通道；对用户不出现 agent 字样） ----------

    /** 增强捕获开关（默认关）：开启时弹注入确认对话框（JEP 451 预告 + 数据留本机说明）。 */
    public final JBCheckBox enhancedCaptureCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.enhancedCapture"));

    /** 脱敏开关（默认关）：对疑似敏感形态打码，agent 采集层执行。 */
    public final JBCheckBox enhancedDesensitizeCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.enhancedCapture.desensitize"));

    /** R60（T-LaneB D96）：展示/导出链脱敏（日志列表/预览浮层/导出文本打码；默认关）。 */
    public final JBCheckBox displayMaskCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.display.mask"));

    /** T-StackHoverUi：调用栈快照总帧数（agent 两端采样预算；CONFIG 帧热下发）。 */
    public final JBTextField stackMaxFramesField = new JBTextField(6);

    /** 增强捕获状态行：已挂载 pid=x / 等待应用启动 / 已回落控制台捕获。 */
    public final JBLabel enhancedStatusLabel = new JBLabel(" ");

    /** T-SettingsTruth P2：AgentManager 状态行 messageBus 连接（{@link #disposeResources()} 断开）。 */
    private Disposable agentStatusConnection = null;

    /**
     * T-SettingsTruth P2 / T-CaptureTruth T-CT-01：聚合各打开项目的 AgentManager 状态写入增强捕获
     * 状态行。无任何活动叙事（各项目均 null）→ 显示空串；多项目以「；」连接。
     *
     * <p>T-EnhancedCaptureDefaultOn（2026-10-19）：重新授权入口已移除，本函数只刷新文本。</p>
     */
    public void refreshEnhancedStatusLabel() {
        java.util.List<AgentManager> agents = new java.util.ArrayList<>();
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            try {
                if (!p.isDisposed()) {
                    agents.add(AgentManager.getInstance(p));
                }
            } catch (Throwable ignored) {
                // runCatching().getOrNull() 等价：服务不可用即跳过
            }
        }
        StringBuilder text = new StringBuilder();
        for (AgentManager a : agents) {
            String summary;
            try {
                summary = a.statusSummary();
            } catch (Throwable e) {
                summary = null;
            }
            if (summary != null) {
                if (text.length() > 0) {
                    text.append('；');
                }
                text.append(summary);
            }
        }
        enhancedStatusLabel.setText(text.length() == 0 ? " " : text.toString());
    }

    /**
     * T-SettingsTruth P2：面板资源清理（messageBus 连接断开）。
     * 由 {@link SectionConfigurable} 的 disposeUIResources 调用；防 IDE 退出 ObjectTree leak 告警。
     */
    public void disposeResources() {
        if (agentStatusConnection != null) {
            try {
                com.intellij.openapi.util.Disposer.dispose(agentStatusConnection);
            } catch (Throwable ignored) {
                // runCatching 等价：清理失败不再抛出
            }
        }
        agentStatusConnection = null;
    }

    @Override
    public void dispose() {
        disposeResources();
    }

    // ── T-PrefixValidate：两栏前缀的校验结果行（有问题才露出；一键修复回写两栏后自动消失） ──
    // 监听器一律挂在字段初始化处：rebuild（语言切换）会重跑 buildSections，挂在 addRow 处会随重建叠加回调

    private final JBLabel prefixValidationLabel = new JBLabel(" ");

    {
        prefixValidationLabel.setIcon(com.intellij.icons.AllIcons.General.Warning);
        prefixValidationLabel.setVisible(false);
    }

    private final JButton prefixFixButton = new JButton(MpSqlVisualizerBundle.message("settings.sourceFramePrefixes.fix"));

    {
        prefixFixButton.setVisible(false);
        prefixFixButton.addActionListener(e -> onPrefixAutoFixClicked());
    }

    public final JPanel prefixValidationRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

    {
        prefixValidationRow.setOpaque(false);
        prefixValidationRow.add(prefixValidationLabel);
        prefixValidationRow.add(prefixFixButton);
    }

    /** 校验两栏并刷新结果行；无问题整行隐藏（GridBag 不可见组件不占位，同 enhancedReauthButton 范式）。 */
    private void refreshPrefixValidationRow() {
        SourceFramePrefixesValidator.Report report = SourceFramePrefixesValidator.validate(
                frameSkipPrefixesField.getText(),
                bizJarPrefixesField.getText());
        java.util.List<String> problems = new java.util.ArrayList<>();
        for (SourceFramePrefixesValidator.InvalidLine line : report.invalidLines()) {
            problems.add(MpSqlVisualizerBundle.message("settings.sourceFramePrefixes.invalidLine", line.lineNo(), line.text()));
        }
        for (SourceFramePrefixesValidator.CrossFieldConflict conflict : report.conflicts()) {
            problems.add(MpSqlVisualizerBundle.message("settings.sourceFramePrefixes.conflict", conflict.prefix()));
        }
        for (SourceFramePrefixesValidator.DuplicatePrefix duplicate : report.duplicates()) {
            problems.add(MpSqlVisualizerBundle.message("settings.sourceFramePrefixes.duplicate", duplicate.prefix()));
        }
        if (problems.isEmpty()) {
            prefixValidationLabel.setVisible(false);
            prefixFixButton.setVisible(false);
            return;
        }
        prefixValidationLabel.setText(problems.size() == 1
                ? problems.get(0)
                : MpSqlVisualizerBundle.message("settings.sourceFramePrefixes.problems", problems.size()) + "　" + problems.get(0));
        prefixValidationLabel.setToolTipText(String.join("\n", problems));
        prefixValidationLabel.setVisible(true);
        prefixFixButton.setVisible(true);
    }

    /** 一键修复：回写两栏（setText 触发 document 监听 → 校验行自动刷新为隐藏）。 */
    private void onPrefixAutoFixClicked() {
        kotlin.Pair<String, String> fixed = SourceFramePrefixesValidator.autoFix(
                frameSkipPrefixesField.getText(),
                bizJarPrefixesField.getText());
        frameSkipPrefixesField.setText(fixed.getFirst());
        bizJarPrefixesField.setText(fixed.getSecond());
    }

    /**
     * T-SourceJumpFunnel P1-3：源码捕获帧过滤前缀（一行一个前缀，如 {@code com.corp.engine.}）。
     * Apply 后经 CONFIG 热下发 agent（即时生效，无需重启应用），IDE 侧候选解析同步过滤。
     */
    public final JBTextArea frameSkipPrefixesField = new JBTextArea(5, 24);

    {
        // T-SourceJumpFunnel：空态灰色占位提示（悬停 tooltip 之外的第一眼引导）
        frameSkipPrefixesField.getEmptyText().setText(MpSqlVisualizerBundle.message("settings.sourceFrameSkipPrefixes.placeholder"));
        frameSkipPrefixesField.setLineWrap(true);
        frameSkipPrefixesField.setWrapStyleWord(true);
        // T-PrefixValidate：格式/冲突校验随输入即时刷新（面板只展示，判定在 SourceFramePrefixesValidator）
        frameSkipPrefixesField.getDocument().addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override
            protected void textChanged(javax.swing.event.DocumentEvent e) {
                refreshPrefixValidationRow();
            }
        });
        // T-FramePrefixVisibility F1：fill=NONE 下多行文本框会塌成一条细线（真机截图实证）——
        // T-FramePrefixResize：默认加大（5 行），首选高保底提到 100px
        java.awt.Dimension framePrefixPref = frameSkipPrefixesField.getPreferredSize();
        framePrefixPref.height = Math.max(framePrefixPref.height, 100);
        frameSkipPrefixesField.setPreferredSize(framePrefixPref);
        // T-FramePrefixResize：最小高保底 60px，作为分栏拖拽下限（拖到极限不塌成细线）
        java.awt.Dimension framePrefixMin = frameSkipPrefixesField.getMinimumSize();
        framePrefixMin.height = Math.max(framePrefixMin.height, 60);
        frameSkipPrefixesField.setMinimumSize(framePrefixMin);
        // T-FramePrefixVisibility F1b：深色主题下 JBTextArea 默认背景与面板几乎同色，输入框
        // 「看不出是个框」（真机截图二轮反馈）——按平台编辑器标准形态补边框+背景区分度
        frameSkipPrefixesField.setBackground(com.intellij.util.ui.UIUtil.getTextFieldBackground());
        frameSkipPrefixesField.setBorder(new CompoundBorder(
            com.intellij.util.ui.UIUtil.getTextFieldBorder(),
            new EmptyBorder(4, 6, 4, 6)));
    }

    /** gutter 预览 SQL 图标开关（M10）。 */
    public final JBCheckBox gutterIconCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.showGutterIcon"));
    // R2（gutter 按来源分组显隐）：六组开关（默认全开=现行为不变）
    public final JBCheckBox gutterGroupMapperXmlCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.gutterGroup.mapperXml"));
    public final JBCheckBox gutterGroupAnnotationCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.gutterGroup.annotation"));
    public final JBCheckBox gutterGroupJpaHqlCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.gutterGroup.jpaHql"));
    public final JBCheckBox gutterGroupJdbcCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.gutterGroup.jdbc"));
    public final JBCheckBox gutterGroupInlineFallbackCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.gutterGroup.inlineFallback"));
    public final JBCheckBox gutterGroupOtherCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.gutterGroup.other"));
    /** T-MapperNavCoexistYield：MyBatisCodeHelperPro 启用时让出 Mapper 双向跳转图标（默认勾选＝让位）。 */
    public final JBCheckBox mapperNavYieldCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.mapperNavYield"));

    /**
     * T-ToolSiteBizFrame：JAR 业务帧反向名单（一行一个前缀，如 {@code com.corp.shared.util.}）。
     * 语义与 {@link #frameSkipPrefixesField} 相反——名单内包名的帧即使来自 JAR 也按业务帧对待；
     * Apply 后随 CONFIG 热下发 agent。
     */
    public final JBTextArea bizJarPrefixesField = new JBTextArea(5, 24);

    {
        bizJarPrefixesField.getEmptyText().setText(MpSqlVisualizerBundle.message("settings.sourceFrameBizJarPrefixes.placeholder"));
        bizJarPrefixesField.setLineWrap(true);
        bizJarPrefixesField.setWrapStyleWord(true);
        // T-PrefixValidate：格式/冲突校验随输入即时刷新（同 skip 栏）
        bizJarPrefixesField.getDocument().addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override
            protected void textChanged(javax.swing.event.DocumentEvent e) {
                refreshPrefixValidationRow();
            }
        });
        // 与帧过滤前缀框同款高度保底、拖拽下限与边框形态（T-FramePrefixVisibility F1/F1b + T-FramePrefixResize）
        java.awt.Dimension bizJarPref = bizJarPrefixesField.getPreferredSize();
        bizJarPref.height = Math.max(bizJarPref.height, 100);
        bizJarPrefixesField.setPreferredSize(bizJarPref);
        java.awt.Dimension bizJarMin = bizJarPrefixesField.getMinimumSize();
        bizJarMin.height = Math.max(bizJarMin.height, 60);
        bizJarPrefixesField.setMinimumSize(bizJarMin);
        bizJarPrefixesField.setBackground(com.intellij.util.ui.UIUtil.getTextFieldBackground());
        bizJarPrefixesField.setBorder(new CompoundBorder(
            com.intellij.util.ui.UIUtil.getTextFieldBorder(),
            new EmptyBorder(4, 6, 4, 6)));
    }

    /**
     * T-FramePrefixResize：两前缀框的垂直分栏——拖拽中缝即重分配两框高度（语义经用户 2026-10-05
     * 确认：互相腾挪、总高固定），比例经 proportionKey 记忆跨重启（同款形态见 SqlPreviewPanel 预览分栏）。
     * 每格 = 标签（左、顶对齐，两格标签同宽对齐）+ 文本框（占满余宽）；rebuild 重建新实例时按同键自动加载记忆比例。
     */
    public com.intellij.ui.OnePixelSplitter buildFramePrefixesSplitter() {
        JBLabel skipLabel = new JBLabel(MpSqlVisualizerBundle.message("settings.sourceFrameSkipPrefixes"));
        JBLabel bizLabel = new JBLabel(MpSqlVisualizerBundle.message("settings.sourceFrameBizJarPrefixes"));
        int labelWidth = Math.max(skipLabel.getPreferredSize().width, bizLabel.getPreferredSize().width);
        for (JBLabel label : List.of(skipLabel, bizLabel)) {
            label.setVerticalAlignment(javax.swing.SwingConstants.TOP);
            label.setPreferredSize(new java.awt.Dimension(labelWidth, label.getPreferredSize().height));
            label.setBorder(new EmptyBorder(0, 0, 0, 8));
        }
        com.intellij.ui.OnePixelSplitter splitter =
            new com.intellij.ui.OnePixelSplitter(true, "mpvs.settings.framePrefixes.split", 0.5f);
        splitter.setFirstComponent(framePrefixCell(skipLabel, frameSkipPrefixesField));
        splitter.setSecondComponent(framePrefixCell(bizLabel, bizJarPrefixesField));
        splitter.setShowDividerControls(false);
        // 拖拽下限跟随组件 minimumSize（两框保底 60px 高）
        splitter.setHonorComponentsMinimumSize(true);
        return splitter;
    }

    /** Kotlin 局部函数 {@code cell} 等价：一格 = 标签（左）+ 文本框（占满余宽）。 */
    private static JPanel framePrefixCell(JBLabel label, JComponent field) {
        JPanel cell = new JPanel(new BorderLayout());
        cell.setOpaque(false);
        // 上下各 4px 呼吸空隙，中缝分隔条不贴框
        cell.setBorder(new EmptyBorder(4, 0, 4, 0));
        cell.add(label, BorderLayout.WEST);
        cell.add(field, BorderLayout.CENTER);
        return cell;
    }

    /** JOIN 图渲染方式（T-Dgm-04）：原生 Diagrams 框架 vs 经典自绘渲染器。 */
    public final JBCheckBox joinDiagramNativeCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.joinDiagramNative"));

    /** 通用 XML 内嵌 SQL 检测开关（O-72 T-XmlInlineSql，默认开）。 */
    public final JBCheckBox xmlInlineSqlCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.xmlInlineSql"));

    /** mapper XML 语法高亮注入开关（T-XmlSynHi D4=T-PerfSweep16 F46 补 UI）：State.xmlSynHiEnabled 字段原有，此前无面板入口。 */
    public final JBCheckBox xmlSynHiCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.xmlSynHi"));

    /** 首用锚点引导通知开关（T-UxOnboard T-UX-01，默认开）：关闭后首用引导全程静默。 */
    public final JBCheckBox onboardingAnchorTipCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.onboardingAnchorTip"));

    /** 空白符可视化开关（T-PreviewWhitespaceToggle O-44a，默认关=不设置该属性、继承 IDE 全局）。 */
    public final JBCheckBox editorShowWhitespacesCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.editorShowWhitespaces"));

    /** 逻辑删除注入开关（T-M15-07）：@TableLogic 实体查询/删除自动追加 `AND deleted = 0`。 */
    public final JBCheckBox injectLogicDeleteCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.injectLogicDelete"));
    public final JBTextField logicDeleteValueField = new JBTextField(6);

    /** 多租户注入开关（T-TenantLine）：SELECT/UPDATE/DELETE 自动追加 `AND tenant_id = ?`、INSERT 列清单追加租户列。 */
    public final JBCheckBox injectTenantLineCheck = new JBCheckBox(MpSqlVisualizerBundle.message("settings.injectTenantLine"));

    /** 租户列名输入框（T-TenantLine）：默认 tenant_id；可按业务调整，如 org_id / shop_id。 */
    public final JBTextField tenantColumnNameField = new JBTextField(10);

    /** 租户列默认值输入框（F-6 Phase A）：UPDATE SET / INSERT 追加租户列时的字面量默认值。 */
    public final JBTextField tenantColumnDefaultField = new JBTextField(6);

    /** INSERT 也追加租户列开关（F-6 Phase A）：INSERT 列清单追加租户列。 */
    public final JBCheckBox injectTenantOnInsertCheck = new JBCheckBox();

    /** 数据权限占位提示开关（F-6 Phase C）：显示 DataPermissionInterceptor 运行时差异提示。 */
    public final JBCheckBox dataPermissionHintCheck = new JBCheckBox();

    /** 数据权限模拟条件输入框（F-6 Phase C）：用户手动填写的 WHERE 片段。 */
    public final JBTextField dataPermissionMockConditionField = new JBTextField(20);

    /** 分页拦截器模拟开关（R12 T-PaginationSim）：COUNT 包裹预览第二语句产出（默认开）。 */
    public final JBCheckBox paginationSimCheck = new JBCheckBox();

    /** 分页 COUNT 查询预览子开关（T-CountSimToggle）：仅控制 COUNT 产出、不影响 LIMIT（父开关优先，默认开）。 */
    public final JBCheckBox paginationCountCheck = new JBCheckBox();

    /** JDBC 模板 SQL 字符串预览开关：queryForList/update 等方法的字符串 SQL 实参显示预览图标。 */
    public final JBCheckBox jdbcSqlPreviewCheck = new JBCheckBox();

    /** EXPLAIN 结构化渲染开关（R33）：面板执行计划按钮的结构化通道（默认开，失败自动回退原生控制台）。 */
    public final JBCheckBox explainStructuredCheck = new JBCheckBox();
    public final JBCheckBox explainPlanRetentionCheck = new JBCheckBox();
    public final JBLabel explainPlanRetentionDisclosure = new JBLabel();

    // ---------- T-M19-StorageClear：三类本地数据的分离动作 ----------

    /** 破坏性动作分类。 */
    public enum StorageClearKind { PREVIEW_HISTORY, PARAM_HISTORY }

    /** 破坏性动作确认缝：生产弹确认；headless 测试注入确定结果，不唤起原生对话框。 */
    public java.util.function.Predicate<StorageClearKind> storageClearConfirmation = kind -> {
        String messageKey = kind == StorageClearKind.PREVIEW_HISTORY
                ? "settings.storage.previewHistory.clear.confirm"
                : "settings.storage.paramHistory.clear.confirm";
        return com.intellij.openapi.ui.Messages.showYesNoDialog(
                com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length > 0
                        ? com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0]
                        : null,
                MpSqlVisualizerBundle.message(messageKey),
                MpSqlVisualizerBundle.message("settings.storage.clear.title"),
                com.intellij.openapi.ui.Messages.getWarningIcon())
                == com.intellij.openapi.ui.Messages.YES;
    };

    /** 捕获目录打开缝：测试只观察目标路径；生产交给平台既有 BrowserUtil.open(file URI)。 */
    public java.util.function.Consumer<java.io.File> captureDirectoryOpener =
            dir -> com.intellij.ide.BrowserUtil.open(dir.toURI().toString());

    /** 默认目录需要项目根；无打开项目时 SqlLogAutoSaver 按既有契约回落 user.home。 */
    public java.util.function.Supplier<String> captureProjectBasePathProvider = () -> {
        for (com.intellij.openapi.project.Project p :
                com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()) {
            if (!p.isDisposed()) {
                return p.getBasePath();
            }
        }
        return null;
    };

    public final JButton openCaptureDirectoryButton = new JButton();
    public final JButton clearPreviewHistoryButton = new JButton();
    public final JButton clearParamHistoryButton = new JButton();
    public final JBLabel storageActionStatusLabel = new JBLabel(" ");

    /** T-ExecTimeoutGuard：执行超时（秒，0=不限）——JBIntSlider 过重，JBTextField + 数字容错。 */
    public final JBTextField executionTimeoutField = new JBTextField(8);

    /** 表前缀输入框（T-M16.2-03）：统一加在所有表名前，全局语义（@TableName 显式表名同样生效）。 */
    public final JBTextField tablePrefixField = new JBTextField(12);

    /** 表后缀输入框（T-M16.2-03）：统一加在所有表名后，全局语义；清空即恢复原样。T-M19.4：多值 `;` 分隔。 */
    public final JBTextField tableSuffixField = new JBTextField(12);

    /** 表名推断策略下拉（T-M19.4）：无 @TableName 时类名→表名的推断方式（SNAKE 默认 / CAMEL 保留驼峰）。 */
    public final JComboBox<InferenceOption> inferenceCombo = new JComboBox<>();

    /** 忽略字段名输入框（T-M19.4）：`;` 分隔的 Java 字段名——命中字段不进 SQL（SELECT 展开/补全/参数对照）。 */
    public final JBTextField ignoredFieldsField = new JBTextField(12);

    /** 移除表名前缀输入框（T-M19.4）：`;` 分隔，最终表名以哪个开头就移除哪个（首个匹配）。 */
    public final JBTextField removeTablePrefixField = new JBTextField(12);

    /** 移除表名后缀输入框（T-M19.4）：`;` 分隔，最终表名以哪个结尾就移除哪个（首个匹配）。 */
    public final JBTextField removeTableSuffixField = new JBTextField(12);

    /** 列名数据源校正开关（O-58）：实体字段名与库真实列名不一致时用数据源真值纠正列名。 */
    public final JBCheckBox columnCalibrationCheck = new JBCheckBox();

    /** 界面语言下拉（M14：默认简体中文；跟随系统 / English 可选）。 */
    public final JComboBox<LanguageOption> languageCombo = new JComboBox<>();

    /** T-PT-04 / O-79：空参数填充策略单选（{@code ?} 占位符 / NULL 字面量 / 空字符串，占位档为默认）。 */
    public final JBRadioButton emptyParamFillPlaceholderRadio = new JBRadioButton();
    public final JBRadioButton emptyParamFillNullRadio = new JBRadioButton();
    public final JBRadioButton emptyParamFillEmptyRadio = new JBRadioButton();
    public final ButtonGroup emptyParamFillGroup = new ButtonGroup();

    /** 反向转换 Wrapper 形态下拉（T-M36.3：AUTO/QUERY/LAMBDA/MPJ_LAMBDA）。 */
    public final JComboBox<ReverseFormatOption> reverseFormatCombo = new JComboBox<>();

    /** 嵌入列命名约定下拉（T-EmbeddedColPrefix：AUTO/NONE/COMPONENT_PATH）。 */
    public final JComboBox<EmbeddedNamingOption> embeddedNamingCombo = new JComboBox<>();

    /** 反向转换手动选择实体类开关（自动推断未命中/多表 JOIN 时弹实体选择弹窗）。 */
    public final JBCheckBox manualEntityCheck = new JBCheckBox();

    /** 后台扫描依赖库以反查实体（T-JarEmbedded 方案 1）。 */
    public final JBCheckBox librarySweepCheck = new JBCheckBox();

    /** 插件缓存信息（统一条目总数——所有打开项目汇总；不细分缓存类型）。 */
    public final JBLabel cacheInfoLabel = new JBLabel(" ");

    /** 设置导入/导出结果反馈（20260907：行内提示替代模态弹窗——原弹窗叠在 Settings 对话框下，须关设置才可见）。 */
    public final JBLabel statusLabel = new JBLabel(" ");

    /** 插件缓存统一清除按钮（清全部缓存，下次使用自动重建）。 */
    public final JButton clearCacheButton = new JButton(MpSqlVisualizerBundle.message("settings.cache.clear"));

    // ---------- T-M31.14 SQL 日志配置（参考 MybatisCodeHelper-Pro「MyBatis Log Setting」） ----------
    public final JBTextField logSqlPrefixField = new JBTextField(12);
    public final JBTextField logParamPrefixField = new JBTextField(12);
    public final JBCheckBox logMixedPrefixCheck = new JBCheckBox();
    public final JBCheckBox logAutoCaptureCheck = new JBCheckBox();
    public final JBCheckBox logAutoOpenCheck = new JBCheckBox();
    public final JBCheckBox logStopOnToolWindowCloseCheck = new JBCheckBox();
    public final JBCheckBox logTimestampCheck = new JBCheckBox();
    public final JBTextField logTimestampFormatField = new JBTextField(12);
    public final JBRadioButton logTimestampSourceLogRadio = new JBRadioButton();
    public final JBRadioButton logTimestampSourceCaptureRadio = new JBRadioButton();
    public final ButtonGroup logTimestampSourceGroup = new ButtonGroup();
    public final JBCheckBox logFormatSqlCheck = new JBCheckBox();
    public final JBTextField logFilterKeywordsField = new JBTextField(12);
    public final JBTextField logDedupeField = new JBTextField(12);
    public final JBTextField logMaxParseField = new JBTextField(12);
    public final JBTextField logMaxCountField = new JBTextField(12);
    public final JBCheckBox logHibernateEnabledCheck = new JBCheckBox();
    public final JBTextField logHibernateSqlPrefixField = new JBTextField(12);
    public final JBTextField logHibernateParamPrefixField = new JBTextField(12);
    public final JBCheckBox logJdbcEnabledCheck = new JBCheckBox();

    // R10 T-ShardingSphereCompat：ShardingSphere 双段日志捕获开关（照抄 Hibernate/Jdbc 开关形态）
    public final JBCheckBox logShardingSphereEnabledCheck = new JBCheckBox();

    // ---------- F-43 自动保存历史 SQL ----------
    public final JBCheckBox logAutoSaveCheck = new JBCheckBox();
    public final JBTextField logAutoSavePathField = new JBTextField(20);
    public final JButton logAutoSaveBrowseButton = new JButton("...");
    public final JBTextField logAutoSavePatternField = new JBTextField(20);
    public final JBLabel logAutoSavePatternPreview = new JBLabel(" ");
    /** T-HistoryFileRetention：历史文件保留上限（0 = 不限/全量）。 */
    public final JBTextField logAutoSaveMaxFilesField = new JBTextField(12);

    // ---------- O-1 日志窗口差异化（执行耗时 / 慢查询） ----------
    public final JBTextField logTimePrefixField = new JBTextField(12);
    public final JBTextField slowQueryThresholdField = new JBTextField(12);

    // ---------- F-44-7 SqlSourceInterceptor 自动注入 ----------
    public final JBCheckBox autoInjectInterceptorCheck = new JBCheckBox();
    public final JBCheckBox logShowDurationCheck = new JBCheckBox();

    // T-DataSourceBadgeMode：数据源角标显示完整上下文（默认关＝仅显示数据库类型）
    public final JBCheckBox dataSourceBadgeDetailCheck = new JBCheckBox();

    // ---------- T-CrudColor：SQL 列表 CRUD 类型颜色标记 ----------
    /** 标记形态下拉（色条 / 整行淡背景 / 关闭）。 */
    public final JComboBox<CrudColorStyleOption> crudColorStyleCombo = new ComboBox<>();

    /** T-DisplayModeToSettings：SQL 列表显示模式（SQL 日志 + SQL 历史两列表共享一键）。 */
    public final JComboBox<ListDisplayModeOption> logDisplayModeCombo = new ComboBox<>();

    /** 四类型色板（平台原生 ColorPanel，点击弹出取色器）。 */
    public final ColorPanel crudColorSelectPanel = new ColorPanel();
    public final ColorPanel crudColorInsertPanel = new ColorPanel();
    public final ColorPanel crudColorUpdatePanel = new ColorPanel();
    public final ColorPanel crudColorDeletePanel = new ColorPanel();
    /** 一键恢复四色默认值。 */
    public final JButton crudColorResetButton = new JButton();

    /**
     * M19.1：语言写穿后「待应用」标记——语言已实时写入配置，但用户尚未点击「应用/确定」。
     * 平台 Apply 按钮启用 = `configurable.isModified()`，无此标记则写穿后 Apply 按钮灰掉
     * （用户实测反馈）；`apply()` 消费后清除。
     */
    private boolean languagePending = false;

    /** 快捷键内嵌编辑（M14.1：录制式修改 Convert to SQL 的 KeyboardShortcut，保存写入 Keymap）。 */
    public final ShortcutEditor shortcutEditor = new ShortcutEditor(ConvertToSqlAction.ACTION_ID);

    /** T-M41：SQL→Java 快捷键内嵌编辑（同款录制式，目标 action = ConvertSqlToJava）。 */
    public final ShortcutEditor reverseShortcutEditor = new ShortcutEditor(ConvertSqlToJavaAction.ACTION_ID);

    /** T-ShortcutSettings：${table} 快捷模板三动作快捷键内嵌编辑（Explain / Count / Sample）。 */
    public final ShortcutEditor explainShortcutEditor = new ShortcutEditor(ExplainSelectedTableAction.ACTION_ID);
    public final ShortcutEditor countShortcutEditor = new ShortcutEditor(CountSelectedTableAction.ACTION_ID);
    public final ShortcutEditor sampleShortcutEditor = new ShortcutEditor(SampleSelectedTableAction.ACTION_ID);

    /** 更新分区（M28：检查更新/下载安装/重启提示/启动自动检查开关）。 */
    public final UpdateSection updateSection;

    // 搜索框已移至主设置页面（PluginSettingsFolderConfigurable）

    private final JPanel rootPanel;

    // ------------------------------------------------------------------
    // 构造与表单布局
    // ------------------------------------------------------------------

    public PluginSettingsPanel(PluginSettings settings) {
        this(settings, SECTION_ALL);
    }

    public PluginSettingsPanel(PluginSettings settings, Set<String> sections) {
        // T-M19-StorageClear：三按钮接线（行构建在 PluginSettingsPanelSections）
        openCaptureDirectoryButton.addActionListener(e -> openCaptureHistoryDirectory());
        clearPreviewHistoryButton.addActionListener(e -> clearPreviewHistory());
        clearParamHistoryButton.addActionListener(e -> clearParamHistory());
        this.settings = settings;
        this.sections = sections;
        this.updateSection = new UpdateSection(settings);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8, 10, 8, 10));
        this.rootPanel = panel;

        buildSections(rootPanel);
        reset();
        refreshCacheInfo();
        // T-SettingsTruth P2：订阅 app 级增强捕获状态 Topic，AgentManager 状态迁移点刷新状态行。
        // T-CaptureTruth T-CT-05：**不得** Disposer.register(this, connection)——
        // 面板自身不在 Disposer 树里，register 会把它挂成 ROOT_DISPOSABLE 子节点，而 Configurable
        // 链路只调 disposeResources() 不 dispose 面板，IDE 退出即报 Memory leak（沙盒实测 6 例，
        // 栈指本行）。连接生命周期由 disposeResources() 显式承担。
        MessageBusConnection connection = ApplicationManager.getApplication().getMessageBus().connect();
        connection.subscribe(AgentManager.AGENT_STATUS_TOPIC, (AgentStatusListener) () ->
            ApplicationManager.getApplication().invokeLater(this::refreshEnhancedStatusLabel));
        agentStatusConnection = connection;
        refreshEnhancedStatusLabel();
        // M19（用户实测：设置切语言确定后重开仍原样——平台 Settings 框架 OK 仅对「当前选中页」且
        // isModified()==true 的 configurable 调用 apply()，改完语言切到其他页再确定即被静默跳过；
        // M16.3 roaming 修复只覆盖磁盘持久化链路）：
        // 语言下拉**写穿式持久化**——选中即写入配置（不依赖 OK→apply 链路），并立即重建表单即时切换。
        languageCombo.addActionListener(e -> {
            Object selected = languageCombo.getSelectedItem();
            String value = selected instanceof LanguageOption ? ((LanguageOption) selected).value : null;
            if (value != null && !value.equals(settings.getState().getLanguage())) {
                settings.getState().setLanguage(value);
                // M19.1：写穿后置「待应用」标记——平台 Apply 按钮启用 = isModified()，无标记则
                // 应用按钮灰掉（用户实测反馈）；标记在 apply() 时清除（应用/确定后恢复灰属正常）
                languagePending = true;
                // M19.1：**invokeLater 调度重建**——ActionEvent 派发中途同步修改组件树时
                // UI 刷新不可靠（用户实测：左侧树即时变、右侧面板不变），延后到派发完成再重建
                SwingUtilities.invokeLater(this::rebuild);
            }
        });
    }

    /**
     * 构建表单布局（T-M31.8：分组 + TitledSeparator 分隔线，对齐 SQL 预览面板左侧分区样式）。
     * 高频组在上、低频组在下、同类相聚；rebuild 时重新调用（语言切换后组标题与文案刷新）。
     *
     * T-M53-II（2026-10-10）：分区构建（含控件装配）已逐字符搬出到同包 {@link PluginSettingsPanelSections}
     * （Kotlin 扩展函数形态的 Java 等价：静态方法接收本面板实例），本方法仅保留调用入口。
     */
    private void buildSections(JPanel panel) {
        PluginSettingsPanelSections.buildSections(this, panel);
    }

    /**
     * 快捷键内嵌编辑行（M14.1）：只读显示当前快捷键 + 「修改」按钮弹出录制对话框，
     * 捕获按键组合后回填（保存时由 {@link PluginSettingsPanel#apply()} 写入 Keymap）。
     * 不依赖 impl 包（KeyboardShortcutPanel 为包私有），全公开 API 实现。
     *
     * T-M41：actionId 参数化——同一组件服务多个 action（Convert to SQL / SQL→Java），
     * 冲突检测与保存写入均以各自 actionId 为准。
     */
    public class ShortcutEditor extends JPanel {

        private final String actionId;

        private KeyboardShortcut shortcut;

        /** M14.4：本次录制是否「强制使用」（冲突时抢占移除占用者；保存时消费后复位）。 */
        private boolean forceOnApply = false;

        private final JBTextField textField = new JBTextField(18);

        private final JButton editButton = new JButton(MpSqlVisualizerBundle.message("settings.shortcut.edit"));

        ShortcutEditor(String actionId) {
            super(new BorderLayout(6, 0));
            this.actionId = actionId;
            textField.setEditable(false);
            textField.setToolTipText(MpSqlVisualizerBundle.message("settings.shortcut.tip"));
            add(textField, BorderLayout.CENTER);
            add(editButton, BorderLayout.EAST);
            editButton.addActionListener(e -> captureShortcut());
        }

        public KeyboardShortcut getShortcut() {
            return shortcut;
        }

        public void setShortcut(KeyboardShortcut value) {
            this.shortcut = value;
            textField.setText(value != null ? KeymapUtil.getShortcutText(value) : "—");
        }

        public boolean isForceOnApply() {
            return forceOnApply;
        }

        public void setForceOnApply(boolean forceOnApply) {
            this.forceOnApply = forceOnApply;
        }

        /** M19.2：语言切换后刷新自身文案（tooltip/按钮；buildRows 每轮调用——字段实例文本构造时固化）。 */
        public void updateTexts() {
            textField.setToolTipText(MpSqlVisualizerBundle.message("settings.shortcut.tip"));
            editButton.setText(MpSqlVisualizerBundle.message("settings.shortcut.edit"));
        }

        /** 弹出模态录制对话框：聚焦输入框后按下组合键即完成，ESC 取消。 */
        private void captureShortcut() {
            JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), Dialog.ModalityType.APPLICATION_MODAL);
            dialog.setTitle(MpSqlVisualizerBundle.message("settings.shortcut.capture"));
            JBTextField capture = new JBTextField(20);
            capture.setFocusable(true);
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            panel.setBorder(JBUI.Borders.empty(10));
            panel.add(new JBLabel(MpSqlVisualizerBundle.message("settings.shortcut.capture")), BorderLayout.NORTH);
            panel.add(capture, BorderLayout.CENTER);
            dialog.getContentPane().add(panel);
            dialog.pack();
            dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
            // M14.2 焦点修复：setVisible(true) 为阻塞调用，必须在显示前调度聚焦，
            // 否则输入框无焦点导致按键捕获不到（第二次修改不生效的根因）
            SwingUtilities.invokeLater(capture::requestFocusInWindow);
            capture.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_ESCAPE -> dialog.dispose();
                        case KeyEvent.VK_ENTER -> dialog.dispose();
                        // 纯修饰键忽略
                        case KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META -> {
                        }
                        default -> {
                            KeyStroke stroke = KeyStroke.getKeyStrokeForEvent(e);
                            KeyboardShortcut newShortcut = new KeyboardShortcut(stroke, null);
                            // M14.2/14.4 冲突处理：仅冲突时弹窗，且「强制使用」为独立按钮（与普通确认区分）——
                            //  使用：绑定但不抢占（保留占用者，可能不生效）
                            //  强制使用：抢占移除占用者，保证始终生效
                            String conflict = conflictActionName(newShortcut);
                            if (conflict != null) {
                                String[] options = {
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.use"),
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.force"),
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.cancel"),
                                };
                                // 与原无 project 行为一致；返回选项索引 int（0=Yes/使用, 1=No/强制使用, 2=Cancel/取消）。
                                // T-M30：跨版本签名差异——showIdeaMessageDialog 已弃用，改用 MessageDialogBuilder.yesNoCancel
                                // （2024.3+ 可用）。guessWindowAndAsk() 返回 int：0=Yes(第一个按钮), 1=No(第二个), 2=Cancel(第三个)。
                                int choice = MessageDialogBuilder.yesNoCancel(
                                        MpSqlVisualizerBundle.message("settings.shortcut.conflict.title"),
                                        MpSqlVisualizerBundle.message("settings.shortcut.conflict", conflict))
                                    .yesText(options[0])
                                    .noText(options[1])
                                    .cancelText(options[2])
                                    .icon(Messages.getWarningIcon())
                                    .guessWindowAndAsk();
                                switch (choice) {
                                    case 0 -> { // 使用（普通）：不抢占
                                        forceOnApply = false;
                                        setShortcut(newShortcut);
                                        dialog.dispose();
                                    }
                                    case 1 -> { // 强制使用：抢占，保证生效
                                        forceOnApply = true;
                                        setShortcut(newShortcut);
                                        dialog.dispose();
                                    }
                                    default -> {
                                        // 取消
                                    }
                                }
                            } else {
                                forceOnApply = false;
                                setShortcut(newShortcut);
                                dialog.dispose();
                            }
                        }
                    }
                }
            });
            dialog.setVisible(true);
        }

        /** 若新快捷键已被其他 action 占用，返回占用者名称（可读 action 文本；环境异常兜底 null）。 */
        private String conflictActionName(KeyboardShortcut newShortcut) {
            try {
                var keymap = KeymapManager.getInstance().getActiveKeymap();
                for (String otherId : keymap.getActionIds()) {
                    if (Objects.equals(otherId, actionId)) continue;
                    for (Shortcut s : keymap.getShortcuts(otherId)) {
                        if (Objects.equals(s, newShortcut)) {
                            AnAction action = ActionManager.getInstance().getAction(otherId);
                            String text = action != null ? action.getTemplatePresentation().getText() : null;
                            return text != null ? text : otherId;
                        }
                    }
                }
                return null;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------
    // 快捷键读写（M14.1：内嵌编辑，直接写当前 Keymap）
    // ------------------------------------------------------------------

    /** 指定 action 的当前键盘快捷键（activeKeymap 中第一个 KeyboardShortcut；环境异常兜底 null）。 */
    private static KeyboardShortcut currentShortcut(String actionId) {
        try {
            var keymap = KeymapManager.getInstance().getActiveKeymap();
            for (Shortcut s : keymap.getShortcuts(actionId)) {
                if (s instanceof KeyboardShortcut) return (KeyboardShortcut) s;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 应用快捷键（M14.3/14.4）：
     *  - 普通「使用」：仅绑定给本插件（移除自身旧绑定，不动其他 action）；
     *  - 「强制使用」：额外从**所有其他 action**（含 IDE 默认 keymap 继承绑定）移除该组合，保证独占始终生效。
     *
     * 抢占原理：IDEA keymap 支持"负绑定"——{@code Keymap.removeShortcut} 对继承自父级 keymap（$default）的
     * 快捷键会在当前（用户级）keymap 写入禁用覆盖，与 IDE Keymap 设置页"绑定冲突键时自动移除旧绑定"一致。
     */
    private static void applyShortcut(ShortcutEditor editor, String actionId) {
        try {
            var keymap = KeymapManager.getInstance().getActiveKeymap();
            // 1) 清理本 action 的旧键盘快捷键
            List<Shortcut> old = new java.util.ArrayList<>();
            for (Shortcut s : keymap.getShortcuts(actionId)) {
                if (s instanceof KeyboardShortcut) old.add(s);
            }
            for (Shortcut s : old) {
                keymap.removeShortcut(actionId, s);
            }
            // 2) 绑定新组合；「强制使用」时先抢占（从所有其他 action 移除，含默认继承绑定）
            KeyboardShortcut newShortcut = editor.getShortcut();
            if (newShortcut != null) {
                if (editor.isForceOnApply()) {
                    for (String other : keymap.getActionIds()) {
                        if (Objects.equals(other, actionId)) continue;
                        List<Shortcut> conflicts = new java.util.ArrayList<>();
                        for (Shortcut s : keymap.getShortcuts(other)) {
                            if (Objects.equals(s, newShortcut)) conflicts.add(s);
                        }
                        for (Shortcut s : conflicts) {
                            keymap.removeShortcut(other, s);
                        }
                    }
                }
                keymap.addShortcut(actionId, newShortcut);
                editor.setForceOnApply(false);
            }
        } catch (Throwable t) {
            LOG.warn("applyShortcut failed: actionId=" + actionId, t);
        }
    }

    /**
     * 语言切换后重建表单（设置页自身文案即时刷新；控件值从配置回读）。
     */
    public void rebuild() {
        gutterGroupMapperXmlCheck.setText(MpSqlVisualizerBundle.message("settings.gutterGroup.mapperXml"));
        gutterGroupAnnotationCheck.setText(MpSqlVisualizerBundle.message("settings.gutterGroup.annotation"));
        gutterGroupJpaHqlCheck.setText(MpSqlVisualizerBundle.message("settings.gutterGroup.jpaHql"));
        gutterGroupJdbcCheck.setText(MpSqlVisualizerBundle.message("settings.gutterGroup.jdbc"));
        gutterGroupInlineFallbackCheck.setText(MpSqlVisualizerBundle.message("settings.gutterGroup.inlineFallback"));
        gutterGroupOtherCheck.setText(MpSqlVisualizerBundle.message("settings.gutterGroup.other"));
        // T-PerfSweep16 F46：字段级实例随 rebuild 复用，语言切换须显式刷文本（同 R2 六组口径）
        xmlSynHiCheck.setText(MpSqlVisualizerBundle.message("settings.xmlSynHi"));
        rootPanel.removeAll();
        buildSections(rootPanel);
        reset();
        refreshCacheInfo();
        rootPanel.revalidate();
        rootPanel.repaint();
    }

    /**
     * T-ShortcutSettings：打开平台 MCP Server 设置（定位到 SQL Visualizer 工具）。
     * 平台 Configurable 类名 = com.intellij.mcpserver.settings.McpServerSettingsConfigurable
     * （262 mcpserver.jar 常量池实证；注意包名 mcpserver 全小写）。
     * MCP Server 插件未安装时（243 / 未启用）提示并返回。
     */
    private void openMcpServerSettings() {
        Project project = null;
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            project = p;
            break;
        }
        if (project == null) {
            return;
        }
        Class<? extends com.intellij.openapi.options.Configurable> configurableClass = null;
        try {
            configurableClass = Class.forName("com.intellij.mcpserver.settings.McpServerSettingsConfigurable")
                .asSubclass(com.intellij.openapi.options.Configurable.class);
        } catch (Throwable ignored) {
            // runCatching().getOrNull() 等价：类缺失 = MCP Server 插件未安装
        }
        if (configurableClass == null) {
            Messages.showWarningDialog(
                project,
                MpSqlVisualizerBundle.message("settings.mcp.unavailable"),
                MpSqlVisualizerBundle.message("settings.mcp.title"));
            return;
        }
        try {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, configurableClass);
        } catch (Throwable ignored) {
            // runCatching<Unit> 等价：打开失败不影响设置页
        }
    }

    /**
     * T-DialectAutoInfer：刷新推断状态行——推断模式显示当前推断结果（含依据 URL），
     * 手动模式显示手动值。惰性调用（reset/选中哨兵项时），不做监听器常驻。
     */
    public void refreshInferredLabel() {
        if (Objects.equals(dialectCombo.getSelectedItem(), autoDialectItem)) {
            // 全局设置不把首个打开项目的方言冒充其他项目；项目唯一时展示决策状态。
            java.util.List<Project> projects = new java.util.ArrayList<>();
            for (Project p : ProjectManager.getInstance().getOpenProjects()) {
                if (!p.isDisposed()) {
                    projects.add(p);
                }
            }
            cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision decision = null;
            if (projects.size() == 1) {
                try {
                    decision = cn.song.mybatisplus.visualizer.sql.DialectRegistry.INSTANCE
                            .decision(projects.get(0), null, null, null);
                } catch (Throwable ignored) {
                    decision = null;
                }
            }
            dialectInferredLabel.setText(
                    DialectSettingsStatusText.describe(projects.size(), decision));
        } else {
            Object manualItem = dialectCombo.getSelectedItem();
            dialectInferredLabel.setText(MpSqlVisualizerBundle.message(
                "settings.dialect.manual", manualItem instanceof String s ? s : ""));
        }
    }

    /**
     * M19：语言写穿入口（测试用）——选中语言值，触发与用户下拉选择**同一**写穿路径
     * （ActionListener → 立即写入配置 + 重建表单）。
     */
    public void selectLanguage(String value) {
        LanguageOption option = languageOptionByValue(value);
        if (option == null) return;
        // selectedItem 变化会同步触发 ActionListener，写穿与 rebuild 由监听统一处理
        languageCombo.setSelectedItem(option);
    }

    /** T-xx：反向转换形态选择入口（测试用）——与下拉选择同一路径（selectedItem 按 equals 匹配）。 */
    public void selectReverseFormat(String value) {
        ReverseFormatOption option = reverseFormatOptionByValue(value);
        if (option == null) return;
        reverseFormatCombo.setSelectedItem(option);
    }

    /** T-PT-04 / O-79：空参数填充策略选择入口（测试用）——与用户点选同一路径（isModified/apply 检测）。 */
    public void selectEmptyParamFill(String value) {
        if (PluginSettings.EMPTY_PARAM_FILL_PLACEHOLDER.equals(value)) {
            emptyParamFillPlaceholderRadio.setSelected(true);
        } else if (PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING.equals(value)) {
            emptyParamFillEmptyRadio.setSelected(true);
        } else {
            emptyParamFillNullRadio.setSelected(true);
        }
    }

    /** T-CrudColor：标记形态下拉选择入口（测试用）——与用户选择同一路径。 */
    public void selectCrudColorStyle(String value) {
        CrudColorStyleOption match = crudColorStyleOptionByValue(value);
        if (match == null) return;
        crudColorStyleCombo.setSelectedItem(match);
    }

    /** T-DisplayModeToSettings：显示模式下拉选择入口（测试用）——与用户选择同一路径。 */
    public void selectListDisplayMode(String value) {
        for (ListDisplayModeOption option : listDisplayModeOptions()) {
            if (Objects.equals(option.value, value)) {
                logDisplayModeCombo.setSelectedItem(option);
                return;
            }
        }
    }

    /** T-DisplayModeToSettings：当前下拉选中值（测试断言 reset 回填用）。 */
    public String currentListDisplayModeForTest() {
        return selectedListDisplayMode();
    }

    /** T-CrudColor：四色色板设置入口（测试用）——与用户取色同一路径。 */
    public void setCrudColor(String which, String hex) {
        Color color = colorOf(hex);
        switch (which) {
            case "select" -> crudColorSelectPanel.setSelectedColor(color);
            case "insert" -> crudColorInsertPanel.setSelectedColor(color);
            case "update" -> crudColorUpdatePanel.setSelectedColor(color);
            case "delete" -> crudColorDeletePanel.setSelectedColor(color);
            default -> {
            }
        }
    }

    /** T-M41 测试访问器：SQL→Java 快捷键编辑行（断言行存在 / 修改检测 / apply 写入 Keymap）。 */
    public ShortcutEditor reverseShortcutEditorForTest() {
        return reverseShortcutEditor;
    }

    /**
     * T-TemplateKeyDrop 测试访问器：${table} 快捷模板三动作的快捷键编辑行。
     *
     * <p>三动作已摘除默认键位（原 Alt+Shift+E/C/T 与平台动作冲突、备选键亦被占），
     * 用例据此钉住「无默认键位」下录制入口仍可用（初始「—」→ 录制 → apply → reset）。
     */
    public ShortcutEditor explainShortcutEditorForTest() {
        return explainShortcutEditor;
    }

    public ShortcutEditor countShortcutEditorForTest() {
        return countShortcutEditor;
    }

    public ShortcutEditor sampleShortcutEditorForTest() {
        return sampleShortcutEditor;
    }

    /** T-xx：启动自动监听复选框设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    public void setLogAutoCaptureCheck(boolean checked) {
        logAutoCaptureCheck.setSelected(checked);
    }

    /** 手动选择实体类复选框设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    public void setManualEntityCheck(boolean checked) {
        manualEntityCheck.setSelected(checked);
    }

    /** 后台扫描依赖库设置入口（测试用），与 UI 的 isModified/apply 同一路径。 */
    public void setLibrarySweepCheck(boolean checked) {
        librarySweepCheck.setSelected(checked);
    }

    /** T-TenantLine：多租户注入开关设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    public void setInjectTenantLineCheck(boolean checked) {
        injectTenantLineCheck.setSelected(checked);
    }

    /** T-UxOnboard T-UX-01：首用锚点引导开关设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    public void setOnboardingAnchorTipCheck(boolean checked) {
        onboardingAnchorTipCheck.setSelected(checked);
    }

    /** T-PreviewWhitespaceToggle O-44a：空白符开关设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    public void setEditorShowWhitespacesCheck(boolean checked) {
        editorShowWhitespacesCheck.setSelected(checked);
    }

    /** T-TenantLine：租户列名输入框设置入口（测试用）——与用户输入同一路径（isModified/apply 检测）。 */
    public void setTenantColumnName(String value) {
        tenantColumnNameField.setText(value);
    }

    /**
     * 刷新插件缓存信息（大小 + 条目数，所有打开项目汇总——含反查/解析/Wrapper 预览三类缓存；
     * 面板构造与清除按钮共用）。
     */
    private void refreshCacheInfo() {
        List<Project> projects = Arrays.asList(ProjectManager.getInstance().getOpenProjects());
        long total = PluginCacheManager.totalSizeBytes(projects);
        cacheInfoLabel.setText(MpSqlVisualizerBundle.message(
                "settings.cache.info",
                PluginCacheManager.formatSize(total),
                PluginCacheManager.entryCount(projects)));
    }

    /** 测试访问器：缓存信息文本（断言展示与清除后刷新）。 */
    public String cacheInfoForTest() {
        return cacheInfoLabel.getText();
    }

    /** 测试访问器：清除缓存按钮（断言存在与点击行为）。 */
    public JButton clearCacheButtonForTest() {
        return clearCacheButton;
    }

    /** 测试访问器：导入/导出行内反馈（20260907——文本/前景色/tooltip 三断言面）。 */
    public JBLabel statusForTest() {
        return statusLabel;
    }

    // ------------------------------------------------------------------
    // F-44-7：SqlSourceInterceptor 自动注入
    // ------------------------------------------------------------------

    /**
     * 触发拦截器注入（用户勾选开关后调用）。
     *
     * <p>T-JumpCapture：注入编排与四类结果弹窗收敛到 {@link SourceCaptureGuide}（与「跳转源码」
     * 引导弹框共用，防重入亦在其中）；面板侧仅保留勾选回滚——仅它持有 checkbox。
     */
    public void triggerInterceptorInjection() {
        Project project = ProjectManager.getInstance().getOpenProjects().length > 0
                ? ProjectManager.getInstance().getOpenProjects()[0]
                : null;
        if (project == null) {
            Messages.showWarningDialog(
                    (Component) null,
                    MpSqlVisualizerBundle.message("log.inject.noProject"),
                    MpSqlVisualizerBundle.message("log.inject.title"));
            autoInjectInterceptorCheck.setSelected(false);
            return;
        }
        SourceCaptureGuide.runInjection(project, result -> {
            SourceCaptureGuide.showInjectionResult(project, result);
            // T-SettingsTruth P1：纯动作开关——无论成败，终态复位未勾选（成功=动作完成即复位）
            autoInjectInterceptorCheck.setSelected(false);
        });
    }

    // ------------------------------------------------------------------
    // 配置读写
    // ------------------------------------------------------------------

    /**
     * 根面板（Configurable.createComponent 返回）。
     */
    public JComponent getRoot() {
        return rootPanel;
    }

    /**
     * 表单值是否与配置不一致（含快捷键编辑状态）。
     *
     * M19.1：语言**写穿**后表单与配置一致，但语言变更尚未「应用」——`languagePending`
     * 使 `isModified()` 保持 true，Apply 按钮在写穿后仍可点击（平台 Apply 启用 = isModified()）。
     */
    public boolean isModified() {
        PluginSettings.State s = settings.getState();
        return languagePending
            || (sections.contains(SECTION_PREVIEW) && isModifiedPreview(s))
            || (sections.contains(SECTION_TABLE_MAPPING) && isModifiedTableMapping(s))
            || (sections.contains(SECTION_CODE_GEN) && isModifiedCodeGen(s))
            || (sections.contains(SECTION_SQL_LOG) && isModifiedSqlLog(s))
            || (sections.contains(SECTION_UI) && isModifiedUi(s))
            || (sections.contains(SECTION_SHORTCUTS) && isModifiedShortcuts())
            || (sections.contains(SECTION_UPDATE_DIAGNOSTICS)
                && updateSection.getAutoCheckCheck().isSelected() != s.getAutoCheckUpdate());
    }

    private boolean isModifiedPreview(PluginSettings.State s) {
        Object dialectItem = dialectCombo.getSelectedItem();
        boolean dialectAutoSelected = Objects.equals(dialectItem, autoDialectItem);
        return dialectAutoSelected != s.getDialectAuto()
            || (!dialectAutoSelected && !Objects.equals(dialectItem, s.getDialect()))
            || replaceParamsCheck.isSelected() != s.getReplaceParams()
            || formatCheck.isSelected() != s.getFormatSql()
            || !Objects.equals(selectedEmptyParamFill(), s.getEmptyParamFill())
            || verticalPairAnnotationCheck.isSelected() != s.getVerticalPairAnnotation()
            || autoFoldCheck.isSelected() != s.getAutoFoldEnabled()
            || !Objects.equals(parseIntOrNull(autoFoldThresholdField.getText().trim()), s.getAutoFoldFieldThreshold())
            || literalParamRecoveryCheck.isSelected() != s.getLiteralParamRecovery()
            || injectLogicDeleteCheck.isSelected() != s.getInjectLogicDelete()
            || !logicDeleteValueField.getText().trim().equals(s.getLogicDeleteValue())
            || injectTenantLineCheck.isSelected() != s.getInjectTenantLine()
            || !tenantColumnNameField.getText().trim().equals(s.getTenantColumnName())
            || !tenantColumnDefaultField.getText().trim().equals(s.getTenantColumnDefault())
            || injectTenantOnInsertCheck.isSelected() != s.getInjectTenantOnInsert()
            || dataPermissionHintCheck.isSelected() != s.getDataPermissionHint()
            || !dataPermissionMockConditionField.getText().trim().equals(s.getDataPermissionMockCondition())
            || paginationSimCheck.isSelected() != s.getPaginationSimEnabled()
            || paginationCountCheck.isSelected() != s.getPaginationCountEnabled()
            || jdbcSqlPreviewCheck.isSelected() != s.getJdbcSqlPreviewEnabled()
            || explainStructuredCheck.isSelected() != s.getExplainStructuredRender()
            || explainPlanRetentionCheck.isSelected() != s.getExplainPlanRetentionEnabled()
            || !Objects.equals(parseIntOrNull(executionTimeoutField.getText().trim()), s.getExecutionTimeoutSeconds());
    }

    private boolean isModifiedTableMapping(PluginSettings.State s) {
        Object inference = inferenceCombo.getSelectedItem();
        return !tablePrefixField.getText().trim().equals(s.getTablePrefix())
            || !tableSuffixField.getText().trim().equals(s.getTableSuffix())
            || !Objects.equals(inference instanceof InferenceOption ? ((InferenceOption) inference).value : null,
                               s.getTableNameInference())
            || !ignoredFieldsField.getText().trim().equals(s.getIgnoredFields())
            || !removeTablePrefixField.getText().trim().equals(s.getRemoveTablePrefix())
            || !removeTableSuffixField.getText().trim().equals(s.getRemoveTableSuffix())
            || columnCalibrationCheck.isSelected() != s.getColumnCalibrationEnabled()
            || !Objects.equals(embeddedNamingCombo.getSelectedItem() instanceof EmbeddedNamingOption
                               ? ((EmbeddedNamingOption) embeddedNamingCombo.getSelectedItem()).value : null,
                               s.getEmbeddedColumnNaming());
    }

    private boolean isModifiedCodeGen(PluginSettings.State s) {
        Object reverse = reverseFormatCombo.getSelectedItem();
        return !Objects.equals(reverse instanceof ReverseFormatOption ? ((ReverseFormatOption) reverse).value : null,
                               s.getReverseWrapperFormat())
            || manualEntityCheck.isSelected() != s.getReverseManualEntitySelection()
            || librarySweepCheck.isSelected() != s.getLibraryEntitySweepEnabled();
    }

    private boolean isModifiedSqlLog(PluginSettings.State s) {
        Integer logAutoSaveMaxFiles = parseIntOrNull(logAutoSaveMaxFilesField.getText().trim());
        return !logSqlPrefixField.getText().trim().equals(s.getLogSqlPrefix())
            || !logParamPrefixField.getText().trim().equals(s.getLogParamPrefix())
            || logMixedPrefixCheck.isSelected() != s.getLogMixedPrefix()
            || logAutoCaptureCheck.isSelected() != s.getLogAutoCaptureOnStart()
            || logAutoOpenCheck.isSelected() != s.getLogAutoOpenOnCapture()
            || logStopOnToolWindowCloseCheck.isSelected() != s.getLogStopOnToolWindowClose()
            || logTimestampCheck.isSelected() != s.getLogTimestampEnabled()
            || !logTimestampFormatField.getText().trim().equals(s.getLogTimestampFormat())
            || !(logTimestampSourceLogRadio.isSelected() ? "log" : "capture").equals(s.getLogTimestampSource())
            || logFormatSqlCheck.isSelected() != s.getLogFormatSql()
            || !logFilterKeywordsField.getText().trim().equals(s.getLogFilterKeywords())
            || !Objects.equals(parseLongOrNull(logDedupeField.getText().trim()), s.getLogDedupeWindowMs())
            || !Objects.equals(parseIntOrNull(logMaxParseField.getText().trim()), s.getLogMaxParseLength())
            || !Objects.equals(parseIntOrNull(logMaxCountField.getText().trim()), s.getLogMaxCount())
            || !logTimePrefixField.getText().trim().equals(s.getLogTimePrefix())
            || !Objects.equals(parseLongOrNull(slowQueryThresholdField.getText().trim()), s.getSlowQueryThresholdMs())
            || logShowDurationCheck.isSelected() != s.getShowDurationInLogList()
            || dataSourceBadgeDetailCheck.isSelected() != s.getDataSourceBadgeDetail()
            // M56 T-M56-08：增强捕获三行归位 SQL 日志区组后，改动判定随 sqlLog 分区
            || enhancedCaptureCheck.isSelected() != s.getEnhancedCaptureEnabled()
            || enhancedDesensitizeCheck.isSelected() != s.getEnhancedCaptureDesensitize()
            || displayMaskCheck.isSelected() != s.getDisplayMaskEnabled()
            || !Objects.equals(parseIntOrNull(stackMaxFramesField.getText().trim()), s.getEnhancedCaptureStackMaxFrames())
            || !frameSkipPrefixesField.getText().trim().equals(s.getSourceFrameSkipPrefixes().trim())
            || !bizJarPrefixesField.getText().trim().equals(s.getSourceFrameBizJarPrefixes().trim())
            || logHibernateEnabledCheck.isSelected() != s.getLogHibernateEnabled()
            || !logHibernateSqlPrefixField.getText().trim().equals(s.getLogHibernateSqlPrefix())
            || !logHibernateParamPrefixField.getText().trim().equals(s.getLogHibernateParamPrefix())
            || logJdbcEnabledCheck.isSelected() != s.getLogJdbcEnabled()
            || logShardingSphereEnabledCheck.isSelected() != s.getLogShardingSphereEnabled()
            || logAutoSaveCheck.isSelected() != s.getLogAutoSaveEnabled()
            || !logAutoSavePathField.getText().trim().equals(s.getLogAutoSavePath())
            || !logAutoSavePatternField.getText().trim().equals(s.getLogAutoSaveFileNamePattern())
            || !Objects.equals(logAutoSaveMaxFiles != null
                    ? logAutoSaveMaxFiles
                    : PluginSettings.DEFAULT_LOG_AUTO_SAVE_MAX_FILES,
                s.getLogAutoSaveMaxFiles())
            // T-CrudColor：仅改样式或仅改某个颜色也必须能启用 Apply
            // （reverseWrapperFormat 漏挂 isModified 导致改动被平台静默丢弃的假开关事故，此处防同类回归）
            || !Objects.equals(selectedCrudColorStyle(), s.getCrudColorStyle())
            || !Objects.equals(selectedListDisplayMode(), s.getLogListSqlDisplayMode())
            || !Objects.equals(colorHexOf(crudColorSelectPanel), s.getCrudColorSelect())
            || !Objects.equals(colorHexOf(crudColorInsertPanel), s.getCrudColorInsert())
            || !Objects.equals(colorHexOf(crudColorUpdatePanel), s.getCrudColorUpdate())
            || !Objects.equals(colorHexOf(crudColorDeletePanel), s.getCrudColorDelete());
    }

    private boolean isModifiedUi(PluginSettings.State s) {
        Object lang = languageCombo.getSelectedItem();
        return gutterIconCheck.isSelected() != s.getShowGutterIcon()
            || gutterGroupMapperXmlCheck.isSelected() != s.getGutterGroupMapperXml()
            || gutterGroupAnnotationCheck.isSelected() != s.getGutterGroupAnnotation()
            || gutterGroupJpaHqlCheck.isSelected() != s.getGutterGroupJpaHql()
            || gutterGroupJdbcCheck.isSelected() != s.getGutterGroupJdbc()
            || gutterGroupInlineFallbackCheck.isSelected() != s.getGutterGroupInlineFallback()
            || gutterGroupOtherCheck.isSelected() != s.getGutterGroupOther()
            // T-MapperNavCoexistYield：让位开关漏挂 isModified 会让勾选后 Apply 不启用（假开关回潮）
            || mapperNavYieldCheck.isSelected() != s.getMapperNavYieldToCodeHelper()
            || xmlInlineSqlCheck.isSelected() != s.getXmlInlineSqlEnabled()
            || xmlSynHiCheck.isSelected() != s.getXmlSynHiEnabled()
            || joinDiagramNativeCheck.isSelected() != s.getJoinDiagramNative()
            // T-PreviewWhitespaceToggle O-44a：空白符开关（漏挂 isModified → 勾选后 Apply 不启用）
            || editorShowWhitespacesCheck.isSelected() != s.getEditorShowWhitespaces()
            || onboardingAnchorTipCheck.isSelected() != s.getOnboardingAnchorTipEnabled()
            || !Objects.equals(lang instanceof LanguageOption ? ((LanguageOption) lang).value : null, s.getLanguage());
    }

    /** T-ShortcutSettings：5 个动作快捷键任一变更即为 modified。 */
    private boolean isModifiedShortcuts() {
        return !Objects.equals(shortcutEditor.getShortcut(), currentShortcut(ConvertToSqlAction.ACTION_ID))
            || !Objects.equals(reverseShortcutEditor.getShortcut(), currentShortcut(ConvertSqlToJavaAction.ACTION_ID))
            || !Objects.equals(explainShortcutEditor.getShortcut(), currentShortcut(ExplainSelectedTableAction.ACTION_ID))
            || !Objects.equals(countShortcutEditor.getShortcut(), currentShortcut(CountSelectedTableAction.ACTION_ID))
            || !Objects.equals(sampleShortcutEditor.getShortcut(), currentShortcut(SampleSelectedTableAction.ACTION_ID));
    }

    /**
     * 将表单值写回配置（Settings 点击 OK/Apply 时调用）；快捷键变化时写入 Keymap。
     *
     * @return 界面语言是否发生变化（true 时调用方应 {@link #rebuild()} 刷新设置页文案）
     */
    public boolean apply() {
        PluginSettings.State s = settings.getState();
        // M19.1：语言变更判定包含「待应用」标记——写穿后（combo 与 state 已一致）仍报告一次变更，
        // 触发调用方 rebuild；消费后清除标记（后续 apply 幂等）
        Object lang = languageCombo.getSelectedItem();
        boolean languageChanged = languagePending
            || !Objects.equals(lang instanceof LanguageOption ? ((LanguageOption) lang).value : null, s.getLanguage());

        if (sections.contains(SECTION_PREVIEW)) {
            // T-DialectAutoInfer：哨兵项=推断模式；具体方言=手动模式（保留原值语义）
            Object dialect = dialectCombo.getSelectedItem();
            if (Objects.equals(dialect, autoDialectItem)) {
                s.setDialectAuto(true);
            } else {
                s.setDialectAuto(false);
                s.setDialect(dialect instanceof String dialectName ? dialectName : PluginSettings.DEFAULT_DIALECT);
            }
            s.setReplaceParams(replaceParamsCheck.isSelected());
            s.setFormatSql(formatCheck.isSelected());
            // T-PT-04：空参数填充策略（NULL 字面量 / 空字符串）
            s.setEmptyParamFill(selectedEmptyParamFill());
            s.setVerticalPairAnnotation(verticalPairAnnotationCheck.isSelected());
            s.setAutoFoldEnabled(autoFoldCheck.isSelected());
            Integer autoFoldThreshold = parseIntOrNull(autoFoldThresholdField.getText().trim());
            s.setAutoFoldFieldThreshold(autoFoldThreshold != null
                ? autoFoldThreshold
                : PluginSettings.DEFAULT_AUTO_FOLD_FIELD_THRESHOLD);
            s.setLiteralParamRecovery(literalParamRecoveryCheck.isSelected());
            s.setInjectLogicDelete(injectLogicDeleteCheck.isSelected());
            s.setLogicDeleteValue(logicDeleteValueField.getText().trim());
            s.setInjectTenantLine(injectTenantLineCheck.isSelected());
            s.setTenantColumnName(tenantColumnNameField.getText().trim());
            s.setTenantColumnDefault(tenantColumnDefaultField.getText().trim());
            s.setInjectTenantOnInsert(injectTenantOnInsertCheck.isSelected());
            s.setDataPermissionHint(dataPermissionHintCheck.isSelected());
            s.setDataPermissionMockCondition(dataPermissionMockConditionField.getText().trim());
            s.setPaginationSimEnabled(paginationSimCheck.isSelected());
            s.setPaginationCountEnabled(paginationCountCheck.isSelected());
            s.setJdbcSqlPreviewEnabled(jdbcSqlPreviewCheck.isSelected());
            s.setExplainStructuredRender(explainStructuredCheck.isSelected());
            s.setExplainPlanRetentionEnabled(explainPlanRetentionCheck.isSelected());
            Integer executionTimeout = parseIntOrNull(executionTimeoutField.getText().trim());
            s.setExecutionTimeoutSeconds(executionTimeout != null
                ? executionTimeout
                : PluginSettings.DEFAULT_EXECUTION_TIMEOUT_SECONDS);
        }
        if (sections.contains(SECTION_TABLE_MAPPING)) {
            s.setTablePrefix(tablePrefixField.getText().trim());
            s.setTableSuffix(tableSuffixField.getText().trim());
            Object inference = inferenceCombo.getSelectedItem();
            s.setTableNameInference(inference instanceof InferenceOption
                ? ((InferenceOption) inference).value
                : PluginSettings.INFERENCE_SNAKE);
            s.setIgnoredFields(ignoredFieldsField.getText().trim());
            s.setRemoveTablePrefix(removeTablePrefixField.getText().trim());
            s.setRemoveTableSuffix(removeTableSuffixField.getText().trim());
            s.setColumnCalibrationEnabled(columnCalibrationCheck.isSelected());
            Object embedded = embeddedNamingCombo.getSelectedItem();
            s.setEmbeddedColumnNaming(embedded instanceof EmbeddedNamingOption
                ? ((EmbeddedNamingOption) embedded).value
                : PluginSettings.EMBEDDED_NAMING_AUTO);
        }
        if (sections.contains(SECTION_CODE_GEN)) {
            Object reverse = reverseFormatCombo.getSelectedItem();
            s.setReverseWrapperFormat(reverse instanceof ReverseFormatOption
                ? ((ReverseFormatOption) reverse).value
                : PluginSettings.REVERSE_AUTO);
            s.setReverseManualEntitySelection(manualEntityCheck.isSelected());
            s.setLibraryEntitySweepEnabled(librarySweepCheck.isSelected());
        }
        if (sections.contains(SECTION_SQL_LOG)) {
            s.setLogSqlPrefix(logSqlPrefixField.getText().trim());
            s.setLogParamPrefix(logParamPrefixField.getText().trim());
            s.setLogMixedPrefix(logMixedPrefixCheck.isSelected());
            s.setLogAutoCaptureOnStart(logAutoCaptureCheck.isSelected());
            s.setLogAutoOpenOnCapture(logAutoOpenCheck.isSelected());
            s.setLogStopOnToolWindowClose(logStopOnToolWindowCloseCheck.isSelected());
            s.setLogTimestampEnabled(logTimestampCheck.isSelected());
            s.setLogTimestampFormat(logTimestampFormatField.getText().trim());
            s.setLogTimestampSource(logTimestampSourceLogRadio.isSelected() ? "log" : "capture");
            s.setLogFormatSql(logFormatSqlCheck.isSelected());
            s.setLogFilterKeywords(logFilterKeywordsField.getText().trim());
            Long dedupe = parseLongOrNull(logDedupeField.getText().trim());
            s.setLogDedupeWindowMs(dedupe != null ? dedupe : PluginSettings.DEFAULT_LOG_DEDUPE_WINDOW_MS);
            Integer maxParse = parseIntOrNull(logMaxParseField.getText().trim());
            s.setLogMaxParseLength(maxParse != null ? maxParse : PluginSettings.DEFAULT_LOG_MAX_PARSE_LENGTH);
            Integer maxCount = parseIntOrNull(logMaxCountField.getText().trim());
            s.setLogMaxCount(maxCount != null ? maxCount : PluginSettings.DEFAULT_LOG_MAX_COUNT);
            s.setLogTimePrefix(logTimePrefixField.getText().trim());
            Long slowQuery = parseLongOrNull(slowQueryThresholdField.getText().trim());
            s.setSlowQueryThresholdMs(slowQuery != null ? slowQuery : PluginSettings.DEFAULT_SLOW_QUERY_THRESHOLD_MS);
            s.setShowDurationInLogList(logShowDurationCheck.isSelected());
            s.setDataSourceBadgeDetail(dataSourceBadgeDetailCheck.isSelected());
            // M56 T-M56-08：增强捕获三行归位 SQL 日志区组后，读写随 sqlLog 分区（preview 分支不再处理）
            s.setEnhancedCaptureEnabled(enhancedCaptureCheck.isSelected());
            s.setEnhancedCaptureDesensitize(enhancedDesensitizeCheck.isSelected());
            s.setDisplayMaskEnabled(displayMaskCheck.isSelected());
            // T-StackHoverUi：非法输入回缺省，越界钳位 [12,64]（agent 侧 setStackMaxFrames 同口径兜底）
            Integer stackMaxFrames = parseIntOrNull(stackMaxFramesField.getText().trim());
            s.setEnhancedCaptureStackMaxFrames(Math.min(
                Math.max(stackMaxFrames != null ? stackMaxFrames : PluginSettings.DEFAULT_ENHANCED_CAPTURE_STACK_MAX_FRAMES,
                    PluginSettings.MIN_ENHANCED_CAPTURE_STACK_MAX_FRAMES),
                PluginSettings.MAX_ENHANCED_CAPTURE_STACK_MAX_FRAMES));
            // T-SourceJumpFunnel P1-3：前缀规范化（逐行 trim、去空行）写回；下发走 SETTINGS_CHANGE_TOPIC
            StringBuilder normalizedPrefixes = new StringBuilder();
            for (String line : frameSkipPrefixesField.getText().lines().toList()) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    if (normalizedPrefixes.length() > 0) {
                        normalizedPrefixes.append('\n');
                    }
                    normalizedPrefixes.append(trimmed);
                }
            }
            s.setSourceFrameSkipPrefixes(normalizedPrefixes.toString());
            // T-ToolSiteBizFrame：反向名单同款规范化写回
            StringBuilder normalizedBizJar = new StringBuilder();
            for (String line : bizJarPrefixesField.getText().lines().toList()) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    if (normalizedBizJar.length() > 0) {
                        normalizedBizJar.append('\n');
                    }
                    normalizedBizJar.append(trimmed);
                }
            }
            s.setSourceFrameBizJarPrefixes(normalizedBizJar.toString());
            s.setLogHibernateEnabled(logHibernateEnabledCheck.isSelected());
            s.setLogHibernateSqlPrefix(logHibernateSqlPrefixField.getText().trim());
            s.setLogHibernateParamPrefix(logHibernateParamPrefixField.getText().trim());
            s.setLogJdbcEnabled(logJdbcEnabledCheck.isSelected());
            s.setLogShardingSphereEnabled(logShardingSphereEnabledCheck.isSelected());
            s.setLogAutoSaveEnabled(logAutoSaveCheck.isSelected());
            s.setLogAutoSavePath(logAutoSavePathField.getText().trim());
            String pattern = logAutoSavePatternField.getText().trim();
            s.setLogAutoSaveFileNamePattern(pattern.isEmpty()
                ? PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN
                : pattern);
            Integer logAutoSaveMaxFiles = parseIntOrNull(logAutoSaveMaxFilesField.getText().trim());
            s.setLogAutoSaveMaxFiles(logAutoSaveMaxFiles != null
                ? logAutoSaveMaxFiles
                : PluginSettings.DEFAULT_LOG_AUTO_SAVE_MAX_FILES); // T-HistoryFileRetention
            // T-CrudColor：CRUD 颜色标记（样式 + 四色）
            s.setCrudColorStyle(selectedCrudColorStyle());
            // T-DisplayModeToSettings：SQL 列表显示模式（apply 后经 SETTINGS_CHANGE_TOPIC 广播，两列表既有监听刷新）
            s.setLogListSqlDisplayMode(selectedListDisplayMode());
            s.setCrudColorSelect(colorHexOf(crudColorSelectPanel));
            s.setCrudColorInsert(colorHexOf(crudColorInsertPanel));
            s.setCrudColorUpdate(colorHexOf(crudColorUpdatePanel));
            s.setCrudColorDelete(colorHexOf(crudColorDeletePanel));
        }
        if (sections.contains(SECTION_UI)) {
            s.setShowGutterIcon(gutterIconCheck.isSelected());
            s.setGutterGroupMapperXml(gutterGroupMapperXmlCheck.isSelected());
            s.setGutterGroupAnnotation(gutterGroupAnnotationCheck.isSelected());
            s.setGutterGroupJpaHql(gutterGroupJpaHqlCheck.isSelected());
            s.setGutterGroupJdbc(gutterGroupJdbcCheck.isSelected());
            s.setGutterGroupInlineFallback(gutterGroupInlineFallbackCheck.isSelected());
            s.setGutterGroupOther(gutterGroupOtherCheck.isSelected());
            s.setMapperNavYieldToCodeHelper(mapperNavYieldCheck.isSelected());
            s.setXmlInlineSqlEnabled(xmlInlineSqlCheck.isSelected());
            // T-PerfSweep16 F46：mapper XML 语法高亮开关写回（随 UI 分区）
            s.setXmlSynHiEnabled(xmlSynHiCheck.isSelected());
            s.setJoinDiagramNative(joinDiagramNativeCheck.isSelected());
            // T-UxOnboard T-UX-01：首用锚点引导开关写回（随 UI 分区，读写判定同归属）
            s.setOnboardingAnchorTipEnabled(onboardingAnchorTipCheck.isSelected());
            // T-PreviewWhitespaceToggle O-44a：空白符开关写回（随 UI 分区）
            s.setEditorShowWhitespaces(editorShowWhitespacesCheck.isSelected());
            s.setLanguage(lang instanceof LanguageOption
                ? ((LanguageOption) lang).value
                : PluginSettings.DEFAULT_LANGUAGE);
        }
        // T-ShortcutSettings：5 个动作快捷键写入 Keymap（仅在变更时 apply）
        if (sections.contains(SECTION_SHORTCUTS)) {
            if (!Objects.equals(shortcutEditor.getShortcut(), currentShortcut(ConvertToSqlAction.ACTION_ID))) {
                applyShortcut(shortcutEditor, ConvertToSqlAction.ACTION_ID);
            }
            if (!Objects.equals(reverseShortcutEditor.getShortcut(), currentShortcut(ConvertSqlToJavaAction.ACTION_ID))) {
                applyShortcut(reverseShortcutEditor, ConvertSqlToJavaAction.ACTION_ID);
            }
            if (!Objects.equals(explainShortcutEditor.getShortcut(), currentShortcut(ExplainSelectedTableAction.ACTION_ID))) {
                applyShortcut(explainShortcutEditor, ExplainSelectedTableAction.ACTION_ID);
            }
            if (!Objects.equals(countShortcutEditor.getShortcut(), currentShortcut(CountSelectedTableAction.ACTION_ID))) {
                applyShortcut(countShortcutEditor, CountSelectedTableAction.ACTION_ID);
            }
            if (!Objects.equals(sampleShortcutEditor.getShortcut(), currentShortcut(SampleSelectedTableAction.ACTION_ID))) {
                applyShortcut(sampleShortcutEditor, SampleSelectedTableAction.ACTION_ID);
            }
        }
        if (sections.contains(SECTION_UPDATE_DIAGNOSTICS)) {
            s.setAutoCheckUpdate(updateSection.getAutoCheckCheck().isSelected());
        }
        languagePending = false;
        // 强制触发持久化：原地修改 state 属性后，框架可能不检测引用变化导致 XML 未写入（实证：
        // injectTenantLine=true 未持久化到 mp-sql-visualizer.xml，仅 showGutterIcon 等部分字段生效）。
        // 替换 state 引用使 XmlSerializer 检测到「状态变更」并在下一次持久化周期写入。
        LOG.info("PluginSettingsPanel.apply: state.after=" + s.getInjectTenantLine()
            + ", tenantColumn=" + s.getTenantColumnName() + ", calling loadState(s.copy())");
        settings.loadState(s.copy());
        // 发布设置变更通知（订阅方如 SqlLogPanel 据此即时刷新 UI）
        ApplicationManager.getApplication().getMessageBus()
            .syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged();
        return languageChanged;
    }

    /**
     * 从配置加载表单值（Settings 打开/Reset 时调用）。
     */
    public void reset() {
        PluginSettings.State s = settings.getState();
        if (sections.contains(SECTION_PREVIEW)) {
            // T-DialectAutoInfer：auto→选中哨兵项；手动→选中方言名（不存在时回落首项哨兵）
            dialectCombo.setSelectedItem(s.getDialectAuto() ? autoDialectItem : s.getDialect());
            if (!Objects.equals(dialectCombo.getSelectedItem(), autoDialectItem)
                    && !Objects.equals(dialectCombo.getSelectedItem(), s.getDialect())) {
                dialectCombo.setSelectedItem(autoDialectItem);
            }
            refreshInferredLabel();
            replaceParamsCheck.setSelected(s.getReplaceParams());
            formatCheck.setSelected(s.getFormatSql());
            // T-PT-04 / O-79：空参数填充策略回填（三档互斥；存量「NULL」持久化值保持原档，
            // 空白/非法值落默认占位档——ButtonGroup 需至少一项选中）
            emptyParamFillPlaceholderRadio.setSelected(PluginSettings.EMPTY_PARAM_FILL_PLACEHOLDER.equals(s.getEmptyParamFill()));
            emptyParamFillNullRadio.setSelected(PluginSettings.EMPTY_PARAM_FILL_NULL.equals(s.getEmptyParamFill()));
            emptyParamFillEmptyRadio.setSelected(PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING.equals(s.getEmptyParamFill()));
            if (!emptyParamFillPlaceholderRadio.isSelected() && !emptyParamFillNullRadio.isSelected()
                    && !emptyParamFillEmptyRadio.isSelected()) {
                emptyParamFillPlaceholderRadio.setSelected(true);
            }
            verticalPairAnnotationCheck.setSelected(s.getVerticalPairAnnotation());
            autoFoldCheck.setSelected(s.getAutoFoldEnabled());
            autoFoldThresholdField.setText(String.valueOf(s.getAutoFoldFieldThreshold()));
            autoFoldThresholdField.setEnabled(s.getAutoFoldEnabled());
            literalParamRecoveryCheck.setSelected(s.getLiteralParamRecovery());
            injectLogicDeleteCheck.setSelected(s.getInjectLogicDelete());
            logicDeleteValueField.setText(s.getLogicDeleteValue());
            injectTenantLineCheck.setSelected(s.getInjectTenantLine());
            tenantColumnNameField.setText(s.getTenantColumnName());
            tenantColumnDefaultField.setText(s.getTenantColumnDefault());
            injectTenantOnInsertCheck.setSelected(s.getInjectTenantOnInsert());
            dataPermissionHintCheck.setSelected(s.getDataPermissionHint());
            dataPermissionMockConditionField.setText(s.getDataPermissionMockCondition());
            paginationSimCheck.setSelected(s.getPaginationSimEnabled());
            paginationCountCheck.setSelected(s.getPaginationCountEnabled());
            jdbcSqlPreviewCheck.setSelected(s.getJdbcSqlPreviewEnabled());
            explainStructuredCheck.setSelected(s.getExplainStructuredRender());
            explainPlanRetentionCheck.setSelected(s.getExplainPlanRetentionEnabled());
            executionTimeoutField.setText(String.valueOf(s.getExecutionTimeoutSeconds()));
        }
        if (sections.contains(SECTION_TABLE_MAPPING)) {
            tablePrefixField.setText(s.getTablePrefix());
            tableSuffixField.setText(s.getTableSuffix());
            InferenceOption inferred = inferenceOptionByValue(s.getTableNameInference());
            inferenceCombo.setSelectedItem(inferred != null ? inferred : inferenceOptions()[0]);
            ignoredFieldsField.setText(s.getIgnoredFields());
            removeTablePrefixField.setText(s.getRemoveTablePrefix());
            removeTableSuffixField.setText(s.getRemoveTableSuffix());
            columnCalibrationCheck.setSelected(s.getColumnCalibrationEnabled());
            EmbeddedNamingOption embedded = embeddedNamingOptionByValue(s.getEmbeddedColumnNaming());
            embeddedNamingCombo.setSelectedItem(embedded != null ? embedded : embeddedNamingOptions()[0]);
        }
        if (sections.contains(SECTION_CODE_GEN)) {
            ReverseFormatOption reverse = reverseFormatOptionByValue(s.getReverseWrapperFormat());
            reverseFormatCombo.setSelectedItem(reverse != null ? reverse : reverseFormatOptions()[0]);
            manualEntityCheck.setSelected(s.getReverseManualEntitySelection());
            librarySweepCheck.setSelected(s.getLibraryEntitySweepEnabled());
        }
        if (sections.contains(SECTION_UI)) {
            gutterIconCheck.setSelected(s.getShowGutterIcon());
            gutterGroupMapperXmlCheck.setSelected(s.getGutterGroupMapperXml());
            gutterGroupAnnotationCheck.setSelected(s.getGutterGroupAnnotation());
            gutterGroupJpaHqlCheck.setSelected(s.getGutterGroupJpaHql());
            gutterGroupJdbcCheck.setSelected(s.getGutterGroupJdbc());
            gutterGroupInlineFallbackCheck.setSelected(s.getGutterGroupInlineFallback());
            gutterGroupOtherCheck.setSelected(s.getGutterGroupOther());
            mapperNavYieldCheck.setSelected(s.getMapperNavYieldToCodeHelper());
            xmlInlineSqlCheck.setSelected(s.getXmlInlineSqlEnabled());
            // T-PerfSweep16 F46：mapper XML 语法高亮开关回填
            xmlSynHiCheck.setSelected(s.getXmlSynHiEnabled());
            joinDiagramNativeCheck.setSelected(s.getJoinDiagramNative());
            onboardingAnchorTipCheck.setSelected(s.getOnboardingAnchorTipEnabled());
            // T-PreviewWhitespaceToggle O-44a：空白符开关回填
            editorShowWhitespacesCheck.setSelected(s.getEditorShowWhitespaces());
            LanguageOption lang = languageOptionByValue(s.getLanguage());
            languageCombo.setSelectedItem(lang != null ? lang : languageOptions()[0]);
        }
        // T-ShortcutSettings：5 个动作快捷键从 Keymap 回读当前值
        if (sections.contains(SECTION_SHORTCUTS)) {
            shortcutEditor.setShortcut(currentShortcut(ConvertToSqlAction.ACTION_ID));
            reverseShortcutEditor.setShortcut(currentShortcut(ConvertSqlToJavaAction.ACTION_ID));
            explainShortcutEditor.setShortcut(currentShortcut(ExplainSelectedTableAction.ACTION_ID));
            countShortcutEditor.setShortcut(currentShortcut(CountSelectedTableAction.ACTION_ID));
            sampleShortcutEditor.setShortcut(currentShortcut(SampleSelectedTableAction.ACTION_ID));
        }
        if (sections.contains(SECTION_UPDATE_DIAGNOSTICS)) {
            updateSection.resetState();
        }
        if (sections.contains(SECTION_SQL_LOG)) {
            logSqlPrefixField.setText(s.getLogSqlPrefix());
            logParamPrefixField.setText(s.getLogParamPrefix());
            logMixedPrefixCheck.setSelected(s.getLogMixedPrefix());
            logAutoCaptureCheck.setSelected(s.getLogAutoCaptureOnStart());
            logAutoOpenCheck.setSelected(s.getLogAutoOpenOnCapture());
            logStopOnToolWindowCloseCheck.setSelected(s.getLogStopOnToolWindowClose());
            logTimestampCheck.setSelected(s.getLogTimestampEnabled());
            logTimestampFormatField.setText(s.getLogTimestampFormat());
            if ("capture".equals(s.getLogTimestampSource())) {
                logTimestampSourceCaptureRadio.setSelected(true);
            } else {
                logTimestampSourceLogRadio.setSelected(true);
            }
            logFormatSqlCheck.setSelected(s.getLogFormatSql());
            logFilterKeywordsField.setText(s.getLogFilterKeywords());
            logDedupeField.setText(String.valueOf(s.getLogDedupeWindowMs()));
            logMaxParseField.setText(String.valueOf(s.getLogMaxParseLength()));
            logMaxCountField.setText(String.valueOf(s.getLogMaxCount()));
            logTimePrefixField.setText(s.getLogTimePrefix());
            slowQueryThresholdField.setText(String.valueOf(s.getSlowQueryThresholdMs()));
            logShowDurationCheck.setSelected(s.getShowDurationInLogList());
            dataSourceBadgeDetailCheck.setSelected(s.getDataSourceBadgeDetail());
            // M56 T-M56-08：增强捕获三行归位 SQL 日志区组后，读写随 sqlLog 分区（preview 分支不再处理）
            enhancedCaptureCheck.setSelected(s.getEnhancedCaptureEnabled());
            enhancedDesensitizeCheck.setSelected(s.getEnhancedCaptureDesensitize());
            displayMaskCheck.setSelected(s.getDisplayMaskEnabled());
            stackMaxFramesField.setText(String.valueOf(s.getEnhancedCaptureStackMaxFrames()));
            frameSkipPrefixesField.setText(s.getSourceFrameSkipPrefixes());
            bizJarPrefixesField.setText(s.getSourceFrameBizJarPrefixes());
            logHibernateEnabledCheck.setSelected(s.getLogHibernateEnabled());
            logHibernateSqlPrefixField.setText(s.getLogHibernateSqlPrefix());
            logHibernateParamPrefixField.setText(s.getLogHibernateParamPrefix());
            logJdbcEnabledCheck.setSelected(s.getLogJdbcEnabled());
            logShardingSphereEnabledCheck.setSelected(s.getLogShardingSphereEnabled());
            logAutoSaveCheck.setSelected(s.getLogAutoSaveEnabled());
            logAutoSavePathField.setText(s.getLogAutoSavePath());
            logAutoSavePatternField.setText(s.getLogAutoSaveFileNamePattern());
            logAutoSaveMaxFilesField.setText(String.valueOf(s.getLogAutoSaveMaxFiles())); // T-HistoryFileRetention
            boolean autoSaveEnabled = s.getLogAutoSaveEnabled();
            logAutoSavePathField.setEnabled(autoSaveEnabled);
            logAutoSaveBrowseButton.setEnabled(autoSaveEnabled);
            logAutoSavePatternField.setEnabled(autoSaveEnabled);
            logAutoSaveMaxFilesField.setEnabled(autoSaveEnabled); // T-HistoryFileRetention
            updateAutoSavePreview();
            // T-CrudColor：CRUD 颜色标记控件回填
            CrudColorStyleOption crudStyle = crudColorStyleOptionByValue(s.getCrudColorStyle());
            crudColorStyleCombo.setSelectedItem(crudStyle != null ? crudStyle : crudColorStyleOptions()[0]);
            // T-DisplayModeToSettings：显示模式下拉回填
            ListDisplayModeOption displayMode = listDisplayModeOptionByValue(s.getLogListSqlDisplayMode());
            logDisplayModeCombo.setSelectedItem(displayMode != null ? displayMode : listDisplayModeOptions()[0]);
            crudColorSelectPanel.setSelectedColor(colorOf(s.getCrudColorSelect()));
            crudColorInsertPanel.setSelectedColor(colorOf(s.getCrudColorInsert()));
            crudColorUpdatePanel.setSelectedColor(colorOf(s.getCrudColorUpdate()));
            crudColorDeletePanel.setSelectedColor(colorOf(s.getCrudColorDelete()));
            // T-SettingsTruth P1：autoInjectInterceptorCheck 为纯动作开关，不回填 state（不保存状态）
        }
        // T-SettingsTruth P2：页面打开/Reset 即见当前增强捕获状态
        if (sections.contains(SECTION_SQL_LOG)) {
            refreshEnhancedStatusLabel();
        }
    }

    // ------------------------------------------------------------------
    // F-43：自动保存历史 SQL 辅助方法
    // ------------------------------------------------------------------

    public void browseAutoSavePath() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle(MpSqlVisualizerBundle.message("settings.log.autoSave.browse.tip"));
        VirtualFile baseDir = LocalFileSystem.getInstance()
            .findFileByIoFile(new File(System.getProperty("user.home")));
        VirtualFile dir = FileChooser.chooseFile(descriptor, null, baseDir);
        if (dir != null) {
            logAutoSavePathField.setText(dir.getPath());
        }
    }

    public void updateAutoSavePreview() {
        String pattern = logAutoSavePatternField.getText().trim();
        if (pattern.isEmpty()) {
            pattern = PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN;
        }
        String resolved = resolvePatternPreview(pattern);
        logAutoSavePatternPreview.setText(MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.preview", resolved));
    }

    /** 解析模板用于预览（复用 SqlLogAutoSaver 的占位符规则）。 */
    private static String resolvePatternPreview(String pattern) {
        long now = System.currentTimeMillis();
        Matcher matcher = PATTERN_PLACEHOLDER.matcher(pattern);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String formatStr = matcher.group(1);
            String replacement;
            try {
                String formatted = new SimpleDateFormat(formatStr, Locale.ENGLISH)
                    .format(new Date(now))
                    .replace(':', '-');
                replacement = formatted.equals(formatStr) ? matcher.group() : formatted;
            } catch (Exception e) {
                replacement = matcher.group();
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // O-5：设置导入/导出
    // ------------------------------------------------------------------

    /**
     * 从 JSON 文件导入设置（文件选择器 → 读取 → 覆盖当前全局配置 → 刷新表单）。
     * 20260907：结果反馈改面板内行内 label（原模态弹窗 parent=rootPanel 叠在 Settings 对话框之下，须关掉设置才可见）。
     */
    public void importSettings() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
            .withFileFilter(file -> "json".equalsIgnoreCase(file.getExtension()))
            .withTitle(MpSqlVisualizerBundle.message("settings.import"));
        VirtualFile file = FileChooser.chooseFile(descriptor, null, null);
        if (file == null) return;
        // T-Perf-14：文件 IO + JSON 解析移后台（EDT 不做磁盘读）；结果回 EDT 刷新表单与行内反馈
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Throwable failure = null;
            try {
                String text = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
                applyImportedText(text);
            } catch (Throwable e) {
                failure = e;
            }
            final Throwable importFailure = failure;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (importFailure == null) {
                    reset();
                    showStatus(MpSqlVisualizerBundle.message("settings.import.success"), false, null);
                } else {
                    showStatus(MpSqlVisualizerBundle.message("settings.import.failed", briefMessage(importFailure)), true, importFailure.getMessage());
                }
                // 默认 modality 在模态 Settings 弹窗存续期不执行（真机实证反馈不出现）——对齐 M36.5.13 惯例
            }, ModalityState.any());
        });
    }

    /** 解析导入文本并覆盖当前全局配置（供测试直调——文件选择器在 headless 下不可用）。 */
    public void applyImportedText(String text) {
        PluginSettings.fromJson(text);
    }

    /**
     * 导出当前设置到 JSON 文件（文件选择器 → 写入）。
     * 20260907：结果反馈改面板内行内 label（同导入）。
     */
    public void exportSettings() {
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
            MpSqlVisualizerBundle.message("settings.export"),
            "JSON (*.json)",
            "json");
        VirtualFile baseDir = LocalFileSystem.getInstance()
            .findFileByIoFile(new File(System.getProperty("user.home")));
        VirtualFileWrapper saved = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, (Project) null)
            .save(baseDir, "mp-sql-visualizer-settings.json");
        if (saved != null) {
            // T-Perf-14：文件写入移后台（EDT 不做磁盘写）；结果回 EDT 行内反馈
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                Throwable failure = null;
                try {
                    writeExportedFile(new File(saved.getFile().getAbsolutePath()));
                } catch (Throwable e) {
                    failure = e;
                }
                final Throwable exportFailure = failure;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (exportFailure == null) {
                        showStatus(MpSqlVisualizerBundle.message("settings.export.success"), false, null);
                    } else {
                        showStatus(MpSqlVisualizerBundle.message("settings.export.failed", briefMessage(exportFailure)), true, exportFailure.getMessage());
                    }
                    // 同导入：模态 Settings 弹窗存续期照常回调
                }, ModalityState.any());
            });
        }
    }

    /** 写出当前设置 JSON（供测试直调）。 */
    public void writeExportedFile(File target) throws java.io.IOException {
        Files.write(target.toPath(), PluginSettings.toJson().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 行内反馈（20260907）：成功=默认前景色，失败=ErrorForeground 红字；完整异常详情放 tooltip。
     * 常驻可见（直到下次操作覆盖），不抢焦点、无遮挡。
     */
    private void showStatus(String text, boolean isError, String fullDetail) {
        statusLabel.setText(text);
        statusLabel.setToolTipText(fullDetail != null ? fullDetail : text);
        statusLabel.setForeground(isError
            ? JBColor.namedColor("ErrorForeground", JBColor.RED)
            : JBColor.foreground());
    }

    /** 异常摘要截断（行内 label 宽度有限，全量详情走 tooltip）。 */
    private static String briefMessage(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return msg.length() > 120 ? msg.substring(0, 120) + "…" : msg;
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static String msg(String key) {
        return MpSqlVisualizerBundle.message(key);
    }

    private static Long parseLongOrNull(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 打开捕获历史目录。只解析/创建并打开目录，绝不枚举或删除其中任何文件；自定义目录与默认目录
     * 走 SqlLogAutoSaver.resolveHistoryDir 同一写端口径。
     */
    private void openCaptureHistoryDirectory() {
        String path = cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.resolveHistoryDir(
                logAutoSavePathField.getText(), captureProjectBasePathProvider.get());
        java.io.File dir = new java.io.File(path);
        try {
            java.nio.file.Files.createDirectories(dir.toPath());
            captureDirectoryOpener.accept(dir);
            storageActionStatusLabel.setText(MpSqlVisualizerBundle.message(
                    "settings.storage.captureFiles.opened", dir.getAbsolutePath()));
            storageActionStatusLabel.setToolTipText(dir.getAbsolutePath());
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            storageActionStatusLabel.setText(
                    MpSqlVisualizerBundle.message("settings.storage.captureFiles.open.failed"));
            storageActionStatusLabel.setToolTipText(t.getMessage());
        }
    }

    /** 清空预览 SQL 历史组件（普通/收藏条目及其标签、参数快照），不触碰共享 XML 的其他组件。 */
    private void clearPreviewHistory() {
        if (!storageClearConfirmation.test(StorageClearKind.PREVIEW_HISTORY)) {
            return;
        }
        try {
            cn.song.mybatisplus.visualizer.history.SqlHistoryManager.getInstance().clear();
            storageActionStatusLabel.setText(
                    MpSqlVisualizerBundle.message("settings.storage.previewHistory.cleared"));
            storageActionStatusLabel.setToolTipText(null);
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            storageActionStatusLabel.setText(
                    MpSqlVisualizerBundle.message("settings.storage.clear.failed"));
            storageActionStatusLabel.setToolTipText(t.getMessage());
        }
    }

    /** 清空默认参数历史、全部命名方案与激活指针，并立即失效所有已打开预览面板的缓存实例。 */
    private void clearParamHistory() {
        if (!storageClearConfirmation.test(StorageClearKind.PARAM_HISTORY)) {
            return;
        }
        try {
            cn.song.mybatisplus.visualizer.ui.ParamHistoryCache.clearPersistedHistory();
            storageActionStatusLabel.setText(
                    MpSqlVisualizerBundle.message("settings.storage.paramHistory.cleared"));
            storageActionStatusLabel.setToolTipText(null);
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            storageActionStatusLabel.setText(
                    MpSqlVisualizerBundle.message("settings.storage.clear.failed"));
            storageActionStatusLabel.setToolTipText(t.getMessage());
        }
    }

    public JButton openCaptureDirectoryButtonForTest() {
        return openCaptureDirectoryButton;
    }

    public JButton clearPreviewHistoryButtonForTest() {
        return clearPreviewHistoryButton;
    }

    public JButton clearParamHistoryButtonForTest() {
        return clearParamHistoryButton;
    }

    public JBLabel storageActionStatusForTest() {
        return storageActionStatusLabel;
    }

}
