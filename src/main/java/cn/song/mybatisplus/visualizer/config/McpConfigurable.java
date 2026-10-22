// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/config/McpConfigurable.kt 移植
// T-SyncK2JMaster496 对齐 master 当前形态（R60 T-LaneB D96 MCP 输出脱敏开关 + UI011 三行能力横幅）
package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.mcp.McpToolGate;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * T-ShortcutSettings：MCP 工具独立子设置页；T-McpToolset2 / T-MTS2-00 增补门控区——
 * 总开关（插件 MCP 功能启停）+ 工具级启用/禁用复选框（全部启用/全部禁用状态切换单按钮，
 * T-SelToggle 同款范式）。
 *
 * 工具注册进平台 MCP Server（mcp-optional.xml `mcpServer.mcpToolset` EP，一工具一 toolset 类），
 * 门控经 McpToolGate 读本页写入的设置（总开关 off / 工具被禁用 → 对应 toolset `isEnabled()=false`，
 * 平台 getEnabledToolsets 每次列表/调用重新求值——262 mcpserver.jar 字节码实证，切换即时生效）。
 * 平台侧工具过滤（McpServerSettingsConfigurable）仍是另一独立维度，本页只管插件自有工具；
 * 「点击前往」跳转入口保留（平台级 disallow list 与本页互补，各自单一真相源）。
 *
 * T-McpGateDynamicFilter（O-68）订正与增补：getEnabledToolsets 过滤只覆盖 ReflectionToolsProvider
 * 路径，agent 真正看到的外部会话清单走 getMcpToolsFiltered 过滤层——本页门控经
 * SqlVisualizerToolGateFilterProvider（mcpServer.mcpToolFilterProvider EP，经 McpGateFilterRegistrar
 * 程序化注册，平台 mcpServer 类可达性门控）同步驱动外部路径，两条路径均出自 McpToolGate 单一真相源。
 */
public class McpConfigurable implements Configurable {

    private final java.util.function.BooleanSupplier platformCapability;

    /** 平台 MCP toolset API 可达性探测（生产默认形态——与 McpToolsetDynamicRegistrar 同一探测口径）。 */
    public McpConfigurable() {
        this(() -> cn.song.mybatisplus.visualizer.mcp.McpToolsetDynamicRegistrarKt.findMcpToolsetClass() != null);
    }

    /** internal 构造缝（测试注入 platformCapability 探测结果；同模块可见）。 */
    McpConfigurable(java.util.function.BooleanSupplier platformCapability) {
        this.platformCapability = platformCapability;
    }

    private JPanel panel = null;

    /** 总开关复选框（apply 时写 state.mcpToolsEnabled）。 */
    private JBCheckBox masterCheckBox = null;

    /** R60（T-LaneB D96）：MCP 输出脱敏复选框（apply 时写 state.mcpMaskEnabled；默认关）。 */
    private JBCheckBox maskLiteralsCheckBox = null;

    /** 工具级复选框（工具名 → 勾选态=启用；apply 时反向写 mcpDisabledTools）。 */
    private final Map<String, JBCheckBox> toolCheckBoxes = new LinkedHashMap<>();

    /** 全部启用/全部禁用状态切换单按钮（全勾→「全部禁用」点击清空，否则「全部启用」点击补满）。 */
    private JButton toggleAllButton = null;

    /** 工具声明数量，不表示运行时注册数量。 */
    private JBLabel bannerHeadline = null;

    /** 只读平台能力提示，与插件开关独立展示。 */
    private JBLabel bannerPlatformLine = null;

    /** 页内开关选择状态，应用后生效。 */
    private JBLabel bannerGateLine = null;

    @Override
    public String getDisplayName() {
        return MpSqlVisualizerBundle.message("settings.mcp.title");
    }

    @Override
    public JComponent createComponent() {
        if (panel == null) {
            panel = buildPanel();
        }
        return panel;
    }

    private JPanel buildPanel() {
        JButton openButton = new JButton(MpSqlVisualizerBundle.message("settings.mcp.open"));
        openButton.setToolTipText(MpSqlVisualizerBundle.message("settings.mcp.desc"));
        openButton.addActionListener(event -> openMcpServerSettings());

        JPanel jumpContent = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        jumpContent.setOpaque(false);
        jumpContent.add(openButton);
        FormBuilder jumpForm = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                MpSqlVisualizerBundle.message("settings.mcp.label"),
                jumpContent
            );
        JBLabel helpLabel = new JBLabel(MpSqlVisualizerBundle.message("settings.mcp.desc"));
        helpLabel.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        JPanel jumpRow = jumpForm.addComponentToRightColumn(helpLabel, 1).getPanel();

        // T-MTS2-00：门控区——总开关 + 工具级复选框（测试环境服务未注册 → state=null 默认全启用）
        PluginSettings.State state = null;
        try {
            state = PluginSettings.getInstance().getState();
        } catch (Throwable ignored) {
            // 服务未注册 → 保持 null
        }

        // UI011：声明数、平台能力、插件开关是三个独立事实；不从工具清单推断注册或可调用。
        // 沿用现有 JBLabel 横幅样式，仅只读探测接口可达性，不注册工具、不启动 MCP Server。
        bannerHeadline = new JBLabel(
            MpSqlVisualizerBundle.message(
                "settings.mcp.banner.declared",
                String.valueOf(McpToolGate.TOOL_NAMES.size())
            )
        );
        bannerPlatformLine = new JBLabel(
            MpSqlVisualizerBundle.message(
                platformCapability.getAsBoolean()
                        ? "settings.mcp.banner.platformPresent"
                        : "settings.mcp.banner.platformUnavailable"
            )
        );
        bannerPlatformLine.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        bannerGateLine = new JBLabel();
        bannerGateLine.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        JPanel banner = new JPanel(new GridLayout(0, 1));
        banner.setBorder(JBUI.Borders.emptyBottom(10));
        banner.add(bannerHeadline);
        banner.add(bannerPlatformLine);
        banner.add(bannerGateLine);

        masterCheckBox = new JBCheckBox(
            MpSqlVisualizerBundle.message("settings.mcp.enable.master"),
            state != null ? state.getMcpToolsEnabled() : true
        );
        masterCheckBox.setToolTipText(MpSqlVisualizerBundle.message("settings.mcp.enable.master.desc"));

        // R60（T-LaneB D96）：MCP 输出脱敏（sqlv_render_logs 还原语句打码；默认关——脱敏降低
        // AI 诊断价值，承审计册口径，工具描述已声明）
        maskLiteralsCheckBox = new JBCheckBox(
            MpSqlVisualizerBundle.message("settings.mcp.maskLiterals"),
            state != null ? state.getMcpMaskEnabled() : false
        );
        maskLiteralsCheckBox.setToolTipText(MpSqlVisualizerBundle.message("settings.mcp.maskLiterals.desc"));

        FormBuilder builder = FormBuilder.createFormBuilder()
            .addComponent(banner)
            .addComponent(jumpRow)
            .addComponent(masterCheckBox)
            .addComponent(maskLiteralsCheckBox);
        for (String toolName : McpToolGate.TOOL_NAMES) {
            // 复选框只表达「工具级未禁用」，与总开关正交（否则 master off 时复选框全不勾，
            // apply 会把全部工具误写进禁用清单）
            JBCheckBox checkBox = new JBCheckBox(checkBoxText(toolName), isToolListedInState(state, toolName));
            toolCheckBoxes.put(toolName, checkBox);
            builder.addComponent(checkBox, 20);
        }
        toggleAllButton = new JButton();
        toggleAllButton.addActionListener(event -> toggleAll());
        JPanel toggleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        toggleRow.setOpaque(false);
        toggleRow.add(toggleAllButton);
        builder.addComponent(toggleRow, 10);
        syncToggleAllLabel();

        // UI011：单项、总开关、全选和 reset 均按页内选择刷新；不将未 Apply 的选择说成已生效。
        refreshBannerGateLine();

        // 20261008 dev-bak 验收（缺陷 #2）：横幅按 R20 落地页「零监听器」定例构建后不再更新——
        // 勾掉工具 / 关总开关乃至 Apply，横幅在同会话内恒显构建时快照（重开整个设置对话框才刷新）。
        // 验收文档 3.21 预期「横幅切到部分禁用/总开关关闭态」。门控复选框是页内唯一写入方，
        // 对它们挂 ActionListener 即时重算门控行（读复选框现状而非持久化 state，三态口径与
        // refreshBannerGateLine 同源；headline 的声明数文案保持静态——工具声明数不随开关变化）。
        Runnable bannerRefresher = this::refreshBannerGateLine;
        if (masterCheckBox != null) masterCheckBox.addActionListener(event -> bannerRefresher.run());
        toolCheckBoxes.values().forEach(cb -> cb.addActionListener(event -> bannerRefresher.run()));

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(JBUI.Borders.empty(8, 10));
        root.add(builder.addComponentFillVertically(new JPanel(), 0).getPanel(), BorderLayout.NORTH);
        return root;
    }

    /** 复选框文案：工具名 + 简述（双语，键按工具名尾段派生）。 */
    private static String checkBoxText(String toolName) {
        String stem = toolName.startsWith("sqlv_") ? toolName.substring("sqlv_".length()) : toolName;
        String descKey = "settings.mcp.tool.desc." + stem.replace("_", "");
        return toolName + " — " + MpSqlVisualizerBundle.message(descKey);
    }

    /**
     * 页内门控选择三态（总开关关 &gt; 工具级有禁用 &gt; 全启用，不代表平台调用状态）——按复选框
     * **现状**即时重算（数据源为页内复选框，不将未 Apply 的选择说成已生效）。
     */
    private void refreshBannerGateLine() {
        JBLabel gate = bannerGateLine;
        if (gate == null) return;
        boolean masterOn = masterCheckBox == null || masterCheckBox.isSelected();
        int disabledCount = 0;
        for (JBCheckBox checkBox : toolCheckBoxes.values()) {
            if (!checkBox.isSelected()) disabledCount++;
        }
        if (!masterOn) {
            gate.setText(MpSqlVisualizerBundle.message("settings.mcp.banner.masterOff"));
        } else if (disabledCount == 0) {
            gate.setText(MpSqlVisualizerBundle.message("settings.mcp.banner.allEnabled"));
        } else {
            gate.setText(MpSqlVisualizerBundle.message("settings.mcp.banner.someDisabled", String.valueOf(disabledCount)));
        }
    }

    /** 工具级禁用判定（仅禁用清单，不含总开关——总开关是独立维度）。 */
    private static boolean isToolListedInState(PluginSettings.State state, String toolName) {        if (state == null) {
            return true;
        }
        for (String disabled : state.getMcpDisabledTools()) {
            if (disabled.equalsIgnoreCase(toolName)) {
                return false;
            }
        }
        return true;
    }

    /** T-SelToggle 同款：全勾显示「全部禁用」点击清空，否则显示「全部启用」点击补满。 */
    private void toggleAll() {
        boolean anyUnselected = false;
        for (JBCheckBox checkBox : toolCheckBoxes.values()) {
            if (!checkBox.isSelected()) {
                anyUnselected = true;
                break;
            }
        }
        boolean target = anyUnselected;
        toolCheckBoxes.values().forEach(checkBox -> checkBox.setSelected(target));
        syncToggleAllLabel();
    }

    private void syncToggleAllLabel() {
        boolean allSelected = true;
        for (JBCheckBox checkBox : toolCheckBoxes.values()) {
            if (!checkBox.isSelected()) {
                allSelected = false;
                break;
            }
        }
        if (toggleAllButton != null) {
            toggleAllButton.setText(MpSqlVisualizerBundle.message(
                allSelected ? "settings.mcp.tools.none" : "settings.mcp.tools.all"
            ));
        }
    }

    /** 跳转平台 MCP Server 设置页。弹窗白名单依据（BannedApiSourceScanTest「模态弹窗只允许
     *  白名单内既有场景」）：目标 configurable 类不可用（IDEA &lt; 2025.2 或未装 MCP Server 插件）
     *  时警告属「配置不可用、用户必须知情」场景，与 PluginSettingsPanel 配置异常 warning 同类
     *  ——按钮点了无反应不可接受，需明确告知前置条件。 */
    private void openMcpServerSettings() {
        ProjectManager projectManager = ProjectManager.getInstance();
        var openProjects = projectManager.getOpenProjects();
        if (openProjects.length == 0) {
            return;
        }
        var project = openProjects[0];
        Class<? extends Configurable> configurableClass = null;
        try {
            Class<?> loaded = Class.forName("com.intellij.mcpserver.settings.McpServerSettingsConfigurable");
            if (Configurable.class.isAssignableFrom(loaded)) {
                configurableClass = loaded.asSubclass(Configurable.class);
            }
        } catch (Throwable ignored) {
            // 目标类不可用 → 保持 null
        }
        if (configurableClass == null) {
            Messages.showWarningDialog(
                project,
                MpSqlVisualizerBundle.message("settings.mcp.unavailable"),
                MpSqlVisualizerBundle.message("settings.mcp.title")
            );
            return;
        }
        try {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, configurableClass);
        } catch (Throwable ignored) {
            // 打开失败不打断用户
        }
    }

    @Override
    public boolean isModified() {
        PluginSettings.State state = null;
        try {
            state = PluginSettings.getInstance().getState();
        } catch (Throwable ignored) {
            return false;
        }
        if (state == null) {
            return false;
        }
        boolean masterSelected = masterCheckBox != null ? masterCheckBox.isSelected() : state.getMcpToolsEnabled();
        boolean masterDirty = masterSelected != state.getMcpToolsEnabled();
        boolean maskSelected = maskLiteralsCheckBox != null ? maskLiteralsCheckBox.isSelected() : state.getMcpMaskEnabled();
        boolean maskDirty = maskSelected != state.getMcpMaskEnabled();
        boolean toolsDirty = false;
        for (String toolName : McpToolGate.TOOL_NAMES) {
            JBCheckBox checkBox = toolCheckBoxes.get(toolName);
            boolean listed = isToolListedInState(state, toolName);
            boolean selected = checkBox != null ? checkBox.isSelected() : listed;
            if (selected != listed) {
                toolsDirty = true;
                break;
            }
        }
        return masterDirty || maskDirty || toolsDirty;
    }

    @Override
    public void apply() {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        state.setMcpToolsEnabled(masterCheckBox != null ? masterCheckBox.isSelected() : true);
        state.setMcpMaskEnabled(maskLiteralsCheckBox != null ? maskLiteralsCheckBox.isSelected() : false);
        java.util.List<String> disabled = new java.util.ArrayList<>();
        for (String toolName : McpToolGate.TOOL_NAMES) {
            JBCheckBox checkBox = toolCheckBoxes.get(toolName);
            if (checkBox != null && !checkBox.isSelected()) {
                disabled.add(toolName);
            }
        }
        state.setMcpDisabledTools(disabled);
    }

    @Override
    public void reset() {
        PluginSettings.State state = null;
        try {
            state = PluginSettings.getInstance().getState();
        } catch (Throwable ignored) {
            return;
        }
        if (state == null) {
            return;
        }
        if (masterCheckBox != null) {
            masterCheckBox.setSelected(state.getMcpToolsEnabled());
        }
        if (maskLiteralsCheckBox != null) {
            maskLiteralsCheckBox.setSelected(state.getMcpMaskEnabled());
        }
        for (String toolName : McpToolGate.TOOL_NAMES) {
            JBCheckBox checkBox = toolCheckBoxes.get(toolName);
            if (checkBox != null) {
                checkBox.setSelected(isToolListedInState(state, toolName));
            }
        }
        syncToggleAllLabel();
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        masterCheckBox = null;
        maskLiteralsCheckBox = null;
        toggleAllButton = null;
        bannerHeadline = null;
        bannerPlatformLine = null;
        bannerGateLine = null;
        toolCheckBoxes.clear();
    }

    // ------------------------------------------------------------------
    // 测试访问器（McpConfigurableGateTest；internal 同模块 → public）
    // ------------------------------------------------------------------

    public JBCheckBox masterForTest() {
        return masterCheckBox;
    }

    public JBCheckBox toolCheckBoxForTest(String toolName) {
        return toolCheckBoxes.get(toolName);
    }

    public JButton toggleAllButtonForTest() {
        return toggleAllButton;
    }

    /** 声明数量文案（null = 横幅未构建/已释放）。 */
    public String bannerHeadlineForTest() {
        return bannerHeadline != null ? bannerHeadline.getText() : null;
    }

    /** 只读平台能力提示行文案（UI011）。 */
    public String bannerPlatformLineForTest() {
        return bannerPlatformLine != null ? bannerPlatformLine.getText() : null;
    }

    /** 页内门控选择文案。 */
    public String bannerGateLineForTest() {
        return bannerGateLine != null ? bannerGateLine.getText() : null;
    }
}
