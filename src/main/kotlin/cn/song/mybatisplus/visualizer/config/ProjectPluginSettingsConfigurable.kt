package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * 项目级设置页（O-5 Phase B；T-SettingsTruth P4 接线补完）。
 *
 * 已注册 plugin.xml `<projectConfigurable parentId="tools">`（项目级 configurable 不得挂在
 * 应用级 configurable 之下——平台限制，挂 tools 顶层与全局页平级），Settings → Tools →
 * SQL Visualizer（Current Project）。
 *
 * 展示「使用项目设置」总开关 + 十项可覆盖字段（State 十字段一一对应）：
 * 方言 / 参数替换 / 格式化 / 逻辑删除注入 / 逻辑删除值 / 表前缀 / 表后缀 / 推断策略 / 忽略字段 /
 * 列名数据源校正。
 * 开关类字段 tri-state 语义：null = 使用全局（控件初始灌全局值，apply 时写实际勾选态）；
 * 字符串字段空 = 使用全局（tooltip 说明，[settings.project.field.tip]）。
 *
 * O-58：列名数据源校正（`columnCalibration`）与 `replaceParams` / `formatSql` / `injectLogicDelete`
 * 语义完全同类（都是 `Boolean?`，null=跟随全局），故控件形态、显示与回写链路**逐条照抄
 * `replaceParamsCheck`**（`checkBox` + [shownBool] 灌值 + [boolDiffers] 比对/写回），不自创形态。
 * 已知取舍：`null`（跟随全局）与 `false`（显式禁用）在复选框上不可区分——这对既有三个布尔覆盖
 * 同样成立，是本页的既有整体设计（null 的复位由「重置为跟随全局」承担），不给单个新项开特例。
 *
 * T-UiNative T-UN-15（试点）：GridBag 手摆布局迁 Kotlin UI DSL v2（`panel { }`）。
 * apply/isModified/reset 手写逻辑保留（面板存取仍走 [ProjectPluginSettings] 单例）。
 *
 * T-RA-04 判定：原 `modified` 标志位已删除。T-SettingsTruth 认定该标志位不可信后
 * isModified() 改纯逐项比对，标志位只剩 13 处写入、零读取——保留等于在类里留一份
 * 「写了却没人信」的假状态（后续读者会误以为它参与判定）；控件监听器也不再为置位而挂。
 * isModified() 逐项比对控件与 state（null 语义：checkbox 勾选态与 state 值按
 * 「null 视为全局值」比较）。
 *
 * T-DialectAutoInfer：方言下拉首项为「智能推断」哨兵——选中即项目级 dialectAuto=true，
 * 选具体方言名即 dialectAuto=false 并把值写进 dialect（state.dialectAuto=null 表示跟随全局，
 * 显示项按全局模式推导，见 [expectedDialectItem]）。哨兵项不复活 T-RA-04 删除的 modified
 * 标志位：apply 只按控件显示值甄别两种语义写 state，isModified 仍走纯逐项比对。
 *
 * T-ProjectSettingsScope P1（2026-09-18）：总开关关闭时 10 项**置灰并显示全局生效值**——
 * 原实现无条件灌「state 残值」而运行时按全局取值，页面所见与运行时所用不一致（用户看不出真实生效值）。
 * 四处必须联动（缺一处即回到原缺陷）：[refreshControls]（灌值 + 可用性）、[apply]（关闭态不写 10 项，
 * 否则把显示的全局值当成项目自定义值钉进 state）、[isModified]（关闭态只比总开关，否则显示全局值
 * vs state 残值恒报已修改）。
 *
 * T-ProjectSettingsScope P2：「重置为跟随全局」按钮清除 10 项残值（总开关不动，见 [resetToGlobal]）。
 *
 * T-TS-FixG7（P1 同类缺陷的第二处出口，独立复核抓出）：开启态 [apply] **只写回「控件显示值 !=
 * state 推导值」的项**。原实现无条件写回 10 项，会把「空/null = 跟随全局」的项钉成当时的全局快照
 * （重置 → 改一项 → apply 即触发），全局值日后变更本项目不再跟随而 UI 数值不变。
 * 语义定案：**「重置后没碰过的项继续跟随全局，碰过的项才成为覆盖」**——「碰过」以
 * 「控件显示值是否仍等于 state 推导值」判定，与 [isModified] 同源，不引入额外的跟随标记/抑制标志。
 *
 * T-FS-08 语义定案：**开启态清空文本项 = 回到「跟随全局」**（不是「显式空值」）。依据两条：
 *  ① UI 侧项目页**没有逐字段的「跟随全局」复选框**，单字段回到跟随全局的唯一表达方式就是清空该字段
 *     （整页「重置为跟随全局」按钮只覆盖全部 10 项，表达不了单字段）；
 *  ② state 侧文本项「空串 = 使用全局」是既有口径（[ProjectPluginSettings.State] 各文本字段 KDoc、
 *     `effectiveXxx()` 的 `isNotBlank()` 回退、字段 tooltip），与 3 个布尔项的 `null = 跟随全局` 同构；
 *     改成「显式空值」需 String→String? 并连带持久化格式与全部 effective 取值口径。
 * 落地（三处同源）：显示侧一律由 [shownText] / [shownBool] / [expectedDialectItem] 从 state 推导；
 * 写回侧文本项一律走 [stateTextOf]（trim，空串即跟随全局）；[apply] 写回后调用 [refreshControls]
 * 重新推导控件显示值——即 apply 之后「控件显示值 ≡ 派生(state)」恒成立，[isModified] 随之复位。
 * 缺最后这一步时，「清空文本框 → 应用」会把 state 写成空串（跟随全局）而控件停在空串，
 * 与「state 推导值 = 全局值」错位 → isModified 恒报已修改（应用按钮点不灭、重开设置页显示又跳回全局值）。
 *
 * T-FS-09：设置搜索的「跳转并高亮」由 [PluginSettingsFolderConfigurable] 自绘（平台无对应入口，
 * 见该文件 findComponent 的 KDoc）。本页走 DSL v2，行标签是普通 JLabel——`row(label)` 的产物
 * 与全局页手摆的 JBLabel 不同型，曾致 6 行定位不到高亮目标（已在该文件的匹配分支中收编）。
 */
class ProjectPluginSettingsConfigurable(private val project: Project) : Configurable {

    private val projectSettings = ProjectPluginSettings.getInstance(project)
    private var useProjectCheck: JBCheckBox? = null
    private var scopeStatusLabel: JLabel? = null
    private var dialectCombo: ComboBox<String>? = null
    private var replaceParamsCheck: JBCheckBox? = null
    private var formatSqlCheck: JBCheckBox? = null
    private var injectLogicDeleteCheck: JBCheckBox? = null
    private var logicDeleteValueField: JBTextField? = null
    private var tablePrefixField: JBTextField? = null
    private var tableSuffixField: JBTextField? = null
    private var inferenceCombo: ComboBox<InferenceOption>? = null
    private var ignoredFieldsField: JBTextField? = null
    private var moduleBindingLabel: JLabel? = null
    private var clearModuleBindingsButton: JButton? = null

    /** O-58：列名数据源校正开关（null = 跟随全局，与 [replaceParamsCheck] 同范式）。 */
    private var columnCalibrationCheck: JBCheckBox? = null

    /**
     * T-ProjectSettingsScope P1：10 项可覆盖字段的 Cell——总开关关闭时统一置灰。
     * 只用于「可用性」联动（值刷新走上面各控件字段，dispose 后为空安全）；dispose 时清空避免持有组件。
     */
    private val projectValueCells = mutableListOf<Cell<JComponent>>()

    /**
     * 表名推断策略下拉项：值 + 当前语言下的显示文案（复用全局面板同一组 bundle 键，
     * 禁用枚举原值直接当显示项——否则中文界面出现 `SNAKE`/`CAMEL` 裸值，与全局页文案不一致）。
     * 必须为 data class：JDK17 `JComboBox.setSelectedItem` 按 equals 匹配（见 [PluginSettingsPanel] 同类教训）。
     */
    private data class InferenceOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    private fun inferenceOptions(): Array<InferenceOption> = arrayOf(
        InferenceOption(PluginSettings.INFERENCE_SNAKE, MpSqlVisualizerBundle.message("settings.tableNameInference.snake")),
        InferenceOption(PluginSettings.INFERENCE_CAMEL, MpSqlVisualizerBundle.message("settings.tableNameInference.camel")),
    )

    /** 全局设置（测试环境未注册 service 时兜底默认 State）。 */
    private val globalState: PluginSettings.State
        get() = runCatching { PluginSettings.getInstance().state }.getOrDefault(PluginSettings.State())

    private fun fieldTip(): String = MpSqlVisualizerBundle.message("settings.project.field.tip")

    /** T-DialectAutoInfer：「智能推断」哨兵项（createComponent 时按当前语言初始化）。 */
    private val autoItem: String = MpSqlVisualizerBundle.message("settings.dialect.auto")

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("plugin.name")

    override fun createComponent(): JComponent {
        val st = projectSettings.state
        // T-UiNative T-UN-15：布局层迁 DSL v2；控件初始值统一经 [refreshControls] 灌入
        // （灌值分支只此一处，防「四处联动」漏改）。
        // T-RA-04：原先每个控件都挂 addActionListener { modified = true } 只为置标志位，
        // 标志位删除后这些监听器一并移除（isModified 逐项比对控件显示值，无需变更通知）。
        // T-ProjectSettingsScope P1：总开关的监听器有真实职责（关闭态刷新 10 项可用性与显示值），
        // 与 T-RA-04 删除的「只为置假标志位」监听器不同，重新引入不违该定案。
        // T-DialectAutoInfer：auto 哨兵项=「智能推断」；项目级 dialectAuto null=跟随全局，
        // 显示项由 [expectedDialectItem] 按有效模式推导（与 reset/isModified 同源）。
        lateinit var checkCell: Cell<JBCheckBox>
        lateinit var scopeStatusCell: Cell<JLabel>
        lateinit var comboCell: Cell<ComboBox<String>>
        lateinit var replaceParamsCell: Cell<JBCheckBox>
        lateinit var formatSqlCell: Cell<JBCheckBox>
        lateinit var injectLogicDeleteCell: Cell<JBCheckBox>
        lateinit var logicDeleteValueCell: Cell<JBTextField>
        lateinit var tablePrefixCell: Cell<JBTextField>
        lateinit var tableSuffixCell: Cell<JBTextField>
        lateinit var inferenceCell: Cell<ComboBox<InferenceOption>>
        lateinit var ignoredFieldsCell: Cell<JBTextField>
        lateinit var columnCalibrationCell: Cell<JBCheckBox>
        lateinit var moduleBindingLabelCell: Cell<JLabel>
        lateinit var clearModuleBindingsCell: Cell<JButton>
        lateinit var resetCell: Cell<JButton>
        projectValueCells.clear()
        val panel = panel {
            row {
                checkCell = checkBox(MpSqlVisualizerBundle.message("settings.project.useProjectSettings"))
                    .applyToComponent {
                        toolTipText = MpSqlVisualizerBundle.message("settings.project.useProjectSettings.tip")
                        addActionListener { refreshControls(isSelected) }
                    }
            }
            row {
                // P1 可选增强：关闭态范围提示行（既有状态行形态：文案空时留一个空格占位，避免行高跳动）
                scopeStatusCell = label(" ")
            }
            row(MpSqlVisualizerBundle.message("settings.dialect")) {
                comboCell = comboBox(listOf(autoItem) + DialectRegistry.dialects.map { it.name })
            }
            row {
                replaceParamsCell = checkBox(MpSqlVisualizerBundle.message("settings.replaceParams"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row {
                formatSqlCell = checkBox(MpSqlVisualizerBundle.message("settings.formatSql"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row {
                injectLogicDeleteCell = checkBox(MpSqlVisualizerBundle.message("settings.injectLogicDelete"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.logicDeleteValue")) {
                logicDeleteValueCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                        // T-RA-04：此处原装自定义 PlainDocument 仅用于置 modified 标志位，
                        // 标志位删除后一并移除（默认文档已满足灌值/编辑需求）
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.tablePrefix")) {
                tablePrefixCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.tableSuffix")) {
                tableSuffixCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.tableNameInference")) {
                inferenceCell = comboBox(inferenceOptions().toList())
            }
            row(MpSqlVisualizerBundle.message("settings.ignoredFields")) {
                ignoredFieldsCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row {
                // O-58：形态与 tooltip 口径照抄 replaceParams / formatSql / injectLogicDelete
                columnCalibrationCell = checkBox(MpSqlVisualizerBundle.message("settings.columnCalibration"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.project.moduleBindings")) {
                moduleBindingLabelCell = label(moduleBindingSummary())
                clearModuleBindingsCell = button(MpSqlVisualizerBundle.message("settings.project.moduleBindings.clear")) {
                    clearModuleBindings()
                }
            }
            row {
                // T-ProjectSettingsScope P2：形态照抄 PluginSettingsPanel 的「恢复默认颜色」
                // （JButton + bundle 文案 + tooltip + addActionListener，无双确认弹窗），
                // 置于所重置的 10 行之后。始终可用：关闭态正是残值在 UI 上唯一不可见的时刻。
                resetCell = button(MpSqlVisualizerBundle.message("settings.project.reset")) { resetToGlobal() }
                    .applyToComponent {
                        toolTipText = MpSqlVisualizerBundle.message("settings.project.reset.tip")
                    }
            }
        }
        useProjectCheck = checkCell.component
        scopeStatusLabel = scopeStatusCell.component
        dialectCombo = comboCell.component
        replaceParamsCheck = replaceParamsCell.component
        formatSqlCheck = formatSqlCell.component
        injectLogicDeleteCheck = injectLogicDeleteCell.component
        logicDeleteValueField = logicDeleteValueCell.component
        tablePrefixField = tablePrefixCell.component
        tableSuffixField = tableSuffixCell.component
        inferenceCombo = inferenceCell.component
        ignoredFieldsField = ignoredFieldsCell.component
        columnCalibrationCheck = columnCalibrationCell.component
        moduleBindingLabel = moduleBindingLabelCell.component
        clearModuleBindingsButton = clearModuleBindingsCell.component
        projectValueCells += listOf(
            comboCell,
            replaceParamsCell,
            formatSqlCell,
            injectLogicDeleteCell,
            logicDeleteValueCell,
            tablePrefixCell,
            tableSuffixCell,
            inferenceCell,
            ignoredFieldsCell,
            columnCalibrationCell,
        )
        refreshControls(st.useProjectSettings)
        return panel
    }

    /** 当前页面按「项目自定义值」还是「全局生效值」呈现：以总开关控件为准（控件未建时回落 state）。 */
    private fun showProjectNow(): Boolean =
        useProjectCheck?.isSelected ?: projectSettings.state.useProjectSettings

    /** 三态布尔项当前应显示的值（关闭态一律显示全局生效值）。 */
    private fun shownBool(stateValue: Boolean?, globalValue: Boolean, showProject: Boolean): Boolean =
        if (showProject) stateValue ?: globalValue else globalValue

    /** 文本项当前应显示的值（关闭态一律显示全局生效值）。 */
    private fun shownText(stateValue: String, globalValue: String, showProject: Boolean): String =
        if (showProject) stateValue.ifBlank { globalValue } else globalValue

    /**
     * 文本项「控件显示值 → state 值」的唯一写回函数（T-FS-08 三处同源里的写回侧）：
     * trim 后写回；**空串即「跟随全局」**（与 [ProjectPluginSettings.State] 文本字段空语义、
     * [shownText] 的 `ifBlank` 推导互逆）。[apply] 的 4 个文本项一律经此函数，不再各处自判 trim/空。
     */
    private fun stateTextOf(field: JBTextField): String = field.text.trim()

    /**
     * T-ProjectSettingsScope P1：把「当前应显示的值 + 可编辑性」灌进控件。
     *
     * createComponent（初值）/ [reset] / 总开关监听器 / [apply]（T-FS-08：写回后重新推导）四处共用
     * 同一份表达式，防规格所点「四处联动只修一处」回潮。
     *
     * @param showProject true=按项目自定义值呈现；false=一律呈现全局生效值（页面所见即运行时所用）
     */
    private fun refreshControls(showProject: Boolean) {
        val st = projectSettings.state
        val global = globalState
        useProjectCheck?.isSelected = showProject
        scopeStatusLabel?.text = if (showProject) " " else MpSqlVisualizerBundle.message("settings.project.scope.global")
        dialectCombo?.selectedItem = expectedDialectItem(st, global, showProject)
        replaceParamsCheck?.isSelected = shownBool(st.replaceParams, global.replaceParams, showProject)
        formatSqlCheck?.isSelected = shownBool(st.formatSql, global.formatSql, showProject)
        injectLogicDeleteCheck?.isSelected = shownBool(st.injectLogicDelete, global.injectLogicDelete, showProject)
        logicDeleteValueField?.text = shownText(st.logicDeleteValue, global.logicDeleteValue, showProject)
        tablePrefixField?.text = shownText(st.tablePrefix, global.tablePrefix, showProject)
        tableSuffixField?.text = shownText(st.tableSuffix, global.tableSuffix, showProject)
        // 回灌按 data class 选项 equals 匹配（传裸值 setSelectedItem 静默 return，选中不生效）
        val inferenceShown = shownText(st.tableNameInference, global.tableNameInference, showProject)
        inferenceCombo?.selectedItem = inferenceOptions().firstOrNull { it.value == inferenceShown }
            ?: inferenceOptions().first()
        ignoredFieldsField?.text = shownText(st.ignoredFields, global.ignoredFields, showProject)
        // O-58：null 视为全局值（与 [shownBool] 的既有三态口径同源）
        columnCalibrationCheck?.isSelected = shownBool(st.columnCalibration, global.columnCalibrationEnabled, showProject)
        // 关闭态置灰：这 10 行显示的是全局生效值，编辑它们没有落点（apply 不写回，见 [apply]）
        projectValueCells.forEach { it.enabled(showProject) }
    }

    /**
     * T-ProjectSettingsScope P2：「重置为跟随全局」——10 项归零/null（[ProjectPluginSettings.State.dialectAuto]
     * 一并归 null），**总开关保持不动**（按钮语义是「清除本项目自定义值」，不是「停用项目设置」）。
     *
     * 直接改 state 而非只改控件显示值：关闭态 [apply] 不写这 10 项，只改控件则残值永远清不掉；
     * 开启态把「显示的全局值」写进 state 又会变成硬覆盖值，与「跟随全局」语义相反。
     *
     * T-TS-FixG7：重置后控件按 P1 回灌全局值，但 [apply] 只写回「被用户动过」的项，
     * 故回灌本身不会再把这些项钉成当时的全局快照（详见 [apply] 的 T-TS-FixG7 注释）。
     */
    private fun moduleBindingSummary(): String {
        val count = cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore.getInstance(project)
            .state.bindings.size
        return MpSqlVisualizerBundle.message("settings.project.moduleBindings.count", count)
    }

    private fun clearModuleBindings() {
        val confirmed = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            MpSqlVisualizerBundle.message("settings.project.moduleBindings.clear.confirm"),
            MpSqlVisualizerBundle.message("settings.project.moduleBindings.clear.title"),
            com.intellij.openapi.ui.Messages.getWarningIcon(),
        ) == com.intellij.openapi.ui.Messages.YES
        if (!confirmed) return
        cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore.getInstance(project).clear()
        // T-ModuleScopedLastUsed W4：连带清「上次使用」的**全部层级**（项目级 + 所有模块分桶）。
        // 否则用户点了「清空模块学习」，指示行灰字仍显示旧源——清空语义名不副实。
        runCatching {
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.forgetAllLastUsed(project)
        }
        moduleBindingLabel?.text = moduleBindingSummary()
    }

    internal fun clearModuleBindingsButtonForTest(): JButton? = clearModuleBindingsButton
    internal fun moduleBindingLabelForTest(): JLabel? = moduleBindingLabel

    private fun resetToGlobal() {
        val st = projectSettings.state
        st.dialect = ""
        st.dialectAuto = null
        st.replaceParams = null
        st.formatSql = null
        st.injectLogicDelete = null
        st.logicDeleteValue = ""
        st.tablePrefix = ""
        st.tableSuffix = ""
        st.tableNameInference = ""
        st.ignoredFields = ""
        st.columnCalibration = null
        refreshControls(showProjectNow())
    }

    /**
     * T-DialectAutoInfer + T-ProjectSettingsScope P1：方言下拉应显示的项。
     *
     * 开启态：有效模式为推断时显示哨兵项「智能推断」，否则显示项目手动值（空则回退全局手动值）。
     * 关闭态（`showProject=false`）：一律按全局推导（全局推断开则哨兵项，否则全局方言名），
     * 与运行时 [ProjectPluginSettings.effectiveDialectAuto] / [ProjectPluginSettings.effectiveDialect]
     * 的取值口径一致——页面所见即运行时所用。
     *
     * createComponent / reset / 总开关联动 / isModified 四处共用同一推导：T-RA-04 起 isModified 是
     * 「控件显示值 vs state」的逐项比对，哨兵项使显示值与 `state.dialect` 不再一一对应（auto 模式下
     * state.dialect 是保留的手动值、并非显示值），故必须由同一函数把 state 反推成显示项，否则哨兵项
     * 会让未改动的页面每次打开都误报已修改。
     */
    private fun expectedDialectItem(
        st: ProjectPluginSettings.State,
        global: PluginSettings.State,
        showProject: Boolean,
    ): String {
        val auto = if (showProject) st.dialectAuto ?: global.dialectAuto else global.dialectAuto
        if (auto) return autoItem
        return if (showProject) st.dialect.ifBlank { global.dialect } else global.dialect
    }

    /** 三态布尔比对：state 为 null 视为全局值，与勾选态比较（只在总开关开启态比对时调用）。 */
    private fun boolDiffers(check: JBCheckBox?, stateValue: Boolean?, globalValue: Boolean): Boolean {
        check ?: return false
        return check.isSelected != shownBool(stateValue, globalValue, showProject = true)
    }

    /** 文本比对：state 空视为全局值（只在总开关开启态比对时调用）。 */
    private fun stringDiffers(field: JBTextField?, stateValue: String, globalValue: String): Boolean {
        field ?: return false
        return field.text.trim() != shownText(stateValue, globalValue, showProject = true)
    }

    override fun isModified(): Boolean {
        val st = projectSettings.state
        val global = globalState
        val showProject = showProjectNow()
        // 平台 ComboBox 对同值 setSelectedItem 也派发 ActionEvent（262 实证）——
        // createComponent/reset 初始灌值即会误报「已修改」；故本方法一律逐项比对
        // （控件显示值 vs state+全局兜底语义），不依赖任何标志位（T-RA-04 已删除原标志位）。
        if (showProject != st.useProjectSettings) return true
        // T-ProjectSettingsScope P1：关闭态 10 项显示的是全局生效值、apply 也不写这 10 项，
        // 故不参与比对——若仍比对，「控件显示全局值 vs state 残值」恒报已修改
        // （误报形态同 T-SettingsTruth 6.3：页面一打开就是「已修改」）。
        if (!showProject) return false
        // 方言控件显示值回灌全局兜底（st.dialect 空时显示 global.dialect），比对须对齐同语义，
        // 否则「项目级方言未配置」在每次打开设置页都误报已修改（实证：reset 前未改动 isModified=true）
        // T-DialectAutoInfer：期望显示项含哨兵项分支，与 createComponent/reset 同源推导
        val dialectShown = dialectCombo?.selectedItem as? String ?: expectedDialectItem(st, global, true)
        if (dialectShown != expectedDialectItem(st, global, true)) return true
        if (boolDiffers(replaceParamsCheck, st.replaceParams, global.replaceParams)) return true
        if (boolDiffers(formatSqlCheck, st.formatSql, global.formatSql)) return true
        if (boolDiffers(injectLogicDeleteCheck, st.injectLogicDelete, global.injectLogicDelete)) return true
        if (stringDiffers(logicDeleteValueField, st.logicDeleteValue, global.logicDeleteValue)) return true
        if (stringDiffers(tablePrefixField, st.tablePrefix, global.tablePrefix)) return true
        if (stringDiffers(tableSuffixField, st.tableSuffix, global.tableSuffix)) return true
        // 推断策略下拉按 data class 选项取值（控件未建时回落 state 值 → 恒等不误报）
        val inferenceShown = (inferenceCombo?.selectedItem as? InferenceOption)?.value
            ?: st.tableNameInference.ifBlank { global.tableNameInference }
        if (inferenceShown != st.tableNameInference.ifBlank { global.tableNameInference }) return true
        if (stringDiffers(ignoredFieldsField, st.ignoredFields, global.ignoredFields)) return true
        if (boolDiffers(columnCalibrationCheck, st.columnCalibration, global.columnCalibrationEnabled)) return true
        return false
    }

    override fun apply() {
        val st = projectSettings.state
        val global = globalState
        val showProject = showProjectNow()
        st.useProjectSettings = showProject
        // T-ProjectSettingsScope P1：关闭态不写 10 项——此时控件显示的是**全局生效值**，
        // 无条件写回会把全局值（以及空/null 字段的全局兜底值）当成项目自定义值钉进 state：
        // 用户重开总开关后发现自定义值已被覆盖，或「跟随全局」被悄悄改成硬覆盖值。
        if (showProject) {
            // T-TS-FixG7：开启态**只写回「控件显示值 != state 推导值」的项**——判据与 [isModified] 的
            // 逐项比对同源（boolDiffers/stringDiffers/expectedDialectItem），即「用户确实动过这一项」。
            //
            // 原实现无条件写回全部 10 项，会把「空/null = 跟随全局」的项钉成**当时的全局快照**：
            // 开启态点「重置为跟随全局」（10 项置空 → [refreshControls] 按 P1 回灌全局值）→ 用户再改
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
            (dialectCombo?.selectedItem as? String)?.let { selected ->
                if (selected != expectedDialectItem(st, global, showProject)) {
                    // T-DialectAutoInfer：哨兵项=项目级推断（true）；具体方言=手动（false + 值）。
                    // auto 分支不改 st.dialect——手动值作为记忆保留，切回手动时不必重选
                    if (selected == autoItem) {
                        st.dialectAuto = true
                    } else {
                        st.dialectAuto = false
                        st.dialect = selected
                    }
                }
            }
            replaceParamsCheck?.let {
                if (boolDiffers(it, st.replaceParams, global.replaceParams)) st.replaceParams = it.isSelected
            }
            formatSqlCheck?.let {
                if (boolDiffers(it, st.formatSql, global.formatSql)) st.formatSql = it.isSelected
            }
            injectLogicDeleteCheck?.let {
                if (boolDiffers(it, st.injectLogicDelete, global.injectLogicDelete)) {
                    st.injectLogicDelete = it.isSelected
                }
            }
            logicDeleteValueField?.let {
                if (stringDiffers(it, st.logicDeleteValue, global.logicDeleteValue)) {
                    st.logicDeleteValue = stateTextOf(it)
                }
            }
            tablePrefixField?.let {
                if (stringDiffers(it, st.tablePrefix, global.tablePrefix)) st.tablePrefix = stateTextOf(it)
            }
            tableSuffixField?.let {
                if (stringDiffers(it, st.tableSuffix, global.tableSuffix)) st.tableSuffix = stateTextOf(it)
            }
            inferenceCombo?.let { combo ->
                val selected = (combo.selectedItem as? InferenceOption)?.value ?: ""
                if (selected != st.tableNameInference.ifBlank { global.tableNameInference }) {
                    st.tableNameInference = selected
                }
            }
            ignoredFieldsField?.let {
                if (stringDiffers(it, st.ignoredFields, global.ignoredFields)) st.ignoredFields = stateTextOf(it)
            }
            columnCalibrationCheck?.let {
                if (boolDiffers(it, st.columnCalibration, global.columnCalibrationEnabled)) {
                    st.columnCalibration = it.isSelected
                }
            }
        }
        // T-FS-08：写回后按同一批取值函数（shownBool/shownText/expectedDialectItem，经 [refreshControls]）
        // 重新推导控件显示值——apply 之后「控件显示值 ≡ 派生(state)」恒成立，[isModified] 随之复位。
        // 缺这一步时「开启态清空文本项 → 应用」留下错位：state 已按语义写回空串（= 跟随全局，运行时
        // 有效值回落到全局值），控件却停在空串 → 逐项比对恒报已修改（应用按钮点不灭，重开设置页显示
        // 又跳回全局值，用户无从判断自己那次清空到底生效没有）。
        // 关闭态同样走这里（与 P1「页面所见即运行时所用」同款）：此时控件本就显示全局生效值，回灌无变化。
        refreshControls(showProject)
    }

    override fun reset() {
        // 回灌统一走 refreshControls（与 createComponent/总开关联动同源表达式）
        refreshControls(projectSettings.state.useProjectSettings)
    }

    override fun disposeUIResources() {
        useProjectCheck = null
        scopeStatusLabel = null
        dialectCombo = null
        replaceParamsCheck = null
        formatSqlCheck = null
        injectLogicDeleteCheck = null
        logicDeleteValueField = null
        tablePrefixField = null
        tableSuffixField = null
        inferenceCombo = null
        ignoredFieldsField = null
        columnCalibrationCheck = null
        moduleBindingLabel = null
        clearModuleBindingsButton = null
        projectValueCells.clear()
    }
}
