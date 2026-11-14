package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.mcp.McpSettingsOpenResult
import cn.song.mybatisplus.visualizer.mcp.McpToolGate
import cn.song.mybatisplus.visualizer.mcp.findMcpToolsetClass
import cn.song.mybatisplus.visualizer.mcp.openMcpServerSettingsPage
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

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
class McpConfigurable internal constructor(
    private val platformCapability: () -> Boolean,
) : Configurable {

    constructor() : this({ findMcpToolsetClass() != null })

    private var panel: JPanel? = null

    /** 总开关复选框（apply 时写 state.mcpToolsEnabled）。 */
    private var masterCheckBox: JBCheckBox? = null

    /** R60（T-LaneB D96）：MCP 输出脱敏复选框（apply 时写 state.mcpMaskEnabled；默认关）。 */
    private var maskLiteralsCheckBox: JBCheckBox? = null

    /** 工具级复选框（工具名 → 勾选态=启用；apply 时反向写 mcpDisabledTools）。 */
    private val toolCheckBoxes = LinkedHashMap<String, JBCheckBox>()

    /** 全部启用/全部禁用状态切换单按钮（全勾→「全部禁用」点击清空，否则「全部启用」补满）。 */
    private var toggleAllButton: JButton? = null

    /** 工具声明数量，不表示运行时注册数量。 */
    private var bannerHeadline: JBLabel? = null

    /** 只读平台能力提示，与插件开关独立展示。 */
    private var bannerPlatformLine: JBLabel? = null

    /** 页内开关选择状态，应用后生效。 */
    private var bannerGateLine: JBLabel? = null

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("settings.mcp.title")

    override fun createComponent(): JComponent {
        if (panel == null) {
            panel = buildPanel()
        }
        return panel!!
    }

    private fun buildPanel(): JPanel {
        val openButton = JButton(MpSqlVisualizerBundle.message("settings.mcp.open")).apply {
            toolTipText = MpSqlVisualizerBundle.message("settings.mcp.desc")
            addActionListener { openMcpServerSettings() }
        }

        val jumpRow = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                MpSqlVisualizerBundle.message("settings.mcp.label"),
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(openButton)
                },
            )
            .addComponentToRightColumn(
                JBLabel(MpSqlVisualizerBundle.message("settings.mcp.desc")).apply {
                    foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                },
                1,
            )
            .panel

        // T-MTS2-00：门控区——总开关 + 工具级复选框（测试环境服务未注册 → state=null 默认全启用）
        val state = runCatching { PluginSettings.getInstance().state }.getOrNull()

        // UI011：声明数、平台能力、插件开关是三个独立事实；不从工具清单推断注册或可调用。
        // 沿用现有 JBLabel 横幅样式，仅只读探测接口可达性，不注册工具、不启动 MCP Server。
        bannerHeadline = JBLabel(
            MpSqlVisualizerBundle.message(
                "settings.mcp.banner.declared",
                McpToolGate.TOOL_NAMES.size.toString(),
            ),
        )
        bannerPlatformLine = JBLabel(
            MpSqlVisualizerBundle.message(
                if (platformCapability()) "settings.mcp.banner.platformPresent"
                else "settings.mcp.banner.platformUnavailable",
            ),
        ).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }
        bannerGateLine = JBLabel().apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }
        val banner = JPanel(GridLayout(0, 1)).apply {
            border = JBUI.Borders.emptyBottom(10)
            add(bannerHeadline)
            add(bannerPlatformLine)
            add(bannerGateLine)
        }

        val masterCheckBox = JBCheckBox(
            MpSqlVisualizerBundle.message("settings.mcp.enable.master"),
            state?.mcpToolsEnabled ?: true,
        ).apply {
            toolTipText = MpSqlVisualizerBundle.message("settings.mcp.enable.master.desc")
        }
        this.masterCheckBox = masterCheckBox

        // R60（T-LaneB D96）：MCP 输出脱敏（sqlv_render_logs 还原语句打码；默认关——脱敏降低
        // AI 诊断价值，承审计册口径，工具描述已声明）
        val maskLiteralsCheckBox = JBCheckBox(
            MpSqlVisualizerBundle.message("settings.mcp.maskLiterals"),
            state?.mcpMaskEnabled ?: false,
        ).apply {
            toolTipText = MpSqlVisualizerBundle.message("settings.mcp.maskLiterals.desc")
        }
        this.maskLiteralsCheckBox = maskLiteralsCheckBox

        val builder = FormBuilder.createFormBuilder()
            .addComponent(banner)
            .addComponent(jumpRow)
            .addComponent(masterCheckBox)
            .addComponent(maskLiteralsCheckBox)
        McpToolGate.TOOL_NAMES.forEach { toolName ->
            // 复选框只表达「工具级未禁用」，与总开关正交（否则 master off 时复选框全不勾，
            // apply 会把全部工具误写进禁用清单）
            val checkBox = JBCheckBox(checkBoxText(toolName), isToolListedInState(state, toolName))
            toolCheckBoxes[toolName] = checkBox
            builder.addComponent(checkBox, 20)
        }
        toggleAllButton = JButton().apply {
            addActionListener { toggleAll() }
        }
        builder.addComponent(
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(toggleAllButton!!)
            },
            10,
        )
        syncToggleAllLabel()

        // UI011：单项、总开关、全选和 reset 均按页内选择刷新；不将未 Apply 的选择说成已生效。
        refreshBannerGateLine()
        masterCheckBox.addItemListener { refreshBannerGateLine() }
        toolCheckBoxes.values.forEach { checkBox ->
            checkBox.addItemListener {
                refreshBannerGateLine()
                syncToggleAllLabel()
            }
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10)
            add(builder.addComponentFillVertically(JPanel(), 0).panel, BorderLayout.NORTH)
        }
    }

    /** 复选框文案：工具名 + 简述（双语，键按工具名尾段派生）。 */
    private fun checkBoxText(toolName: String): String {
        val descKey = "settings.mcp.tool.desc." + toolName.removePrefix("sqlv_").replace("_", "")
        return toolName + " — " + MpSqlVisualizerBundle.message(descKey)
    }

    /** 页内门控选择三态：总开关关 > 工具级有禁用 > 全启用，不代表平台调用状态。 */
    private fun refreshBannerGateLine() {
        val gate = bannerGateLine ?: return
        val masterOn = masterCheckBox?.isSelected != false
        val disabledCount = toolCheckBoxes.values.count { !it.isSelected }
        gate.text = when {
            !masterOn -> MpSqlVisualizerBundle.message("settings.mcp.banner.masterOff")
            disabledCount == 0 -> MpSqlVisualizerBundle.message("settings.mcp.banner.allEnabled")
            else -> MpSqlVisualizerBundle.message("settings.mcp.banner.someDisabled", disabledCount.toString())
        }
    }

    /** 工具级禁用判定（仅禁用清单，不含总开关——总开关是独立维度）。 */
    private fun isToolListedInState(state: PluginSettings.State?, toolName: String): Boolean {
        if (state == null) return true
        return state.mcpDisabledTools.none { it.equals(toolName, ignoreCase = true) }
    }

    /** T-SelToggle 同款：全勾显示「全部禁用」点击清空，否则显示「全部启用」点击补满。 */
    private fun toggleAll() {
        val target = toolCheckBoxes.values.any { !it.isSelected }
        toolCheckBoxes.values.forEach { it.isSelected = target }
        syncToggleAllLabel()
    }

    private fun syncToggleAllLabel() {
        val allSelected = toolCheckBoxes.values.all { it.isSelected }
        toggleAllButton?.text = MpSqlVisualizerBundle.message(
            if (allSelected) "settings.mcp.tools.none" else "settings.mcp.tools.all",
        )
    }

    /** 跳转平台 MCP Server 设置页（寻址与回退见 [openMcpServerSettingsPage]，单一真相源）。
     *
     *  弹窗白名单依据（BannedApiSourceScanTest「模态弹窗只允许白名单内既有场景」）：平台设置页
     *  确实打不开（插件缺失/被禁，或平台把页面搬走后两条路都不通）属「配置不可用、用户必须知情」
     *  场景，与 PluginSettingsPanel 配置异常 warning 同类——按钮点了无反应不可接受。
     *  T-McpSettingsJump：文案不再断言版本（「需要 IDEA 2025.2+」在 2026.3 上不成立），
     *  改为可操作指引（确认插件启用 / 设置里搜 MCP Server）。 */
    private fun openMcpServerSettings() {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        if (openMcpServerSettingsPage(project) != McpSettingsOpenResult.UNAVAILABLE) return
        Messages.showWarningDialog(
            project,
            MpSqlVisualizerBundle.message("settings.mcp.unavailable"),
            MpSqlVisualizerBundle.message("settings.mcp.title"),
        )
    }

    override fun isModified(): Boolean {
        val state = runCatching { PluginSettings.getInstance().state }.getOrNull() ?: return false
        val masterDirty = (masterCheckBox?.isSelected ?: state.mcpToolsEnabled) != state.mcpToolsEnabled
        val maskDirty = (maskLiteralsCheckBox?.isSelected ?: state.mcpMaskEnabled) != state.mcpMaskEnabled
        val toolsDirty = McpToolGate.TOOL_NAMES.any { toolName ->
            (toolCheckBoxes[toolName]?.isSelected ?: isToolListedInState(state, toolName)) != isToolListedInState(state, toolName)
        }
        return masterDirty || maskDirty || toolsDirty
    }

    override fun apply() {
        val state = PluginSettings.getInstance().state
        state.mcpToolsEnabled = masterCheckBox?.isSelected ?: true
        state.mcpMaskEnabled = maskLiteralsCheckBox?.isSelected ?: false
        state.mcpDisabledTools = McpToolGate.TOOL_NAMES
            .filter { toolCheckBoxes[it]?.isSelected == false }
            .toMutableList()
        // T-HiddenBugPerfScan 券 W：强制触发持久化——与 PluginSettingsPanel.apply() 已实证的
        // 同型缺陷同口径（原地改 state 引用不变，XML 可能不写入）。
        PluginSettings.getInstance().loadState(state.copy())
    }

    override fun reset() {
        val state = runCatching { PluginSettings.getInstance().state }.getOrNull() ?: return
        masterCheckBox?.isSelected = state.mcpToolsEnabled
        maskLiteralsCheckBox?.isSelected = state.mcpMaskEnabled
        McpToolGate.TOOL_NAMES.forEach { toolName ->
            toolCheckBoxes[toolName]?.isSelected = isToolListedInState(state, toolName)
        }
        syncToggleAllLabel()
    }

    override fun disposeUIResources() {
        panel = null
        masterCheckBox = null
        maskLiteralsCheckBox = null
        toggleAllButton = null
        bannerHeadline = null
        bannerPlatformLine = null
        bannerGateLine = null
        toolCheckBoxes.clear()
    }

    // ------------------------------------------------------------------
    // 测试访问器（McpConfigurableGateTest；internal 同模块）
    // ------------------------------------------------------------------

    internal fun masterForTest(): JBCheckBox? = masterCheckBox

    internal fun toolCheckBoxForTest(toolName: String): JBCheckBox? = toolCheckBoxes[toolName]

    internal fun toggleAllButtonForTest(): JButton? = toggleAllButton

    /** 声明数量文案（null = 横幅未构建/已释放）。 */
    internal fun bannerHeadlineForTest(): String? = bannerHeadline?.text

    internal fun bannerPlatformLineForTest(): String? = bannerPlatformLine?.text

    /** 页内门控选择文案。 */
    internal fun bannerGateLineForTest(): String? = bannerGateLine?.text
}
