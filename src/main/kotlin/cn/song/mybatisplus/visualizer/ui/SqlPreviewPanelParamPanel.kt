package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.FieldFillSimulation
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.PlaceholderCardinality
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.util.CaseFormatUtil
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.ExprReason
import cn.song.mybatisplus.visualizer.xml.ExprSupport
import cn.song.mybatisplus.visualizer.xml.ConditionOutcome
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql
import cn.song.mybatisplus.visualizer.xml.XmlConditionView
import cn.song.mybatisplus.visualizer.xml.XmlForEachView
import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.HideableTitledPanel
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.FontMetrics
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.NOTIFICATION_GROUP_ID
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.presetSectionCollapsed
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.SCHEME_ROW_NAME
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.BIND_STATE_ROW_NAME
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.BIND_STATE_LABEL_NAME
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.SNAPSHOT_CHK
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.SNAPSHOT_FNULL
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.SNAPSHOT_FEV
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.PROP_TOGGLE_ID
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.PROP_FIELD_SOURCE
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.PROP_FOREACH_NULL
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.NUMBER_REGEX
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.DATE_REGEX
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.PARAM_WARN_MARK
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.PARAM_WARN_FOREGROUND
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.COLLECTION_NAME_EXCEPTIONS
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.MIN_CHECKBOX_TEXT_WIDTH
import cn.song.mybatisplus.visualizer.history.SqlHistoryEntry
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager
import cn.song.mybatisplus.visualizer.model.wrapperFlagOutcome
import cn.song.mybatisplus.visualizer.model.wrapperFlagOutcomeText
import cn.song.mybatisplus.visualizer.model.wrapperFlagOutcomeTooltip

// T-M53-II（2026-10-10）：以下为 SqlPreviewPanel 的「参数面板构建、方案/历史与参数值回填渲染」内聚成员，
// 以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。

/** UI-006: keep the safety explanation visible, including unbroken identifiers in errors. */
private fun fieldFillText(text: String, font: java.awt.Font, limitPx: Int = JBUI.scale(260)): String {
    val metrics = javax.swing.JLabel().getFontMetrics(font)
    val limit = limitPx
    val lines = mutableListOf<String>()
    var line = StringBuilder()
    for (character in text) {
        if (character == '\n' || (line.isNotEmpty() && metrics.stringWidth(line.toString() + character) > limit)) {
            lines += line.toString()
            line = StringBuilder()
        }
        if (character != '\n') line.append(character)
    }
    lines += line.toString()
    return "<html>" + lines.joinToString("<br>") {
        it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    } + "</html>"
}

/** 名称单元：字段名/复选框 + [fill=...] 角标同一行（角标不再单独占一行）。 */
private fun fieldFillNameCell(name: JComponent, fill: String): JBPanel<JBPanel<*>> =
    JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        add(name)
        add(JBLabel("[fill=$fill]").apply {
            toolTipText = MpSqlVisualizerBundle.message("fieldFill.simulation.annotation.tooltip")
        })
    }

/** Wrap the control in the same left-anchored row geometry as the other BoxLayout children. */
private fun fieldFillRow(control: JComponent): JBPanel<JBPanel<*>> =
    JBPanel<JBPanel<*>>(BorderLayout()).apply {
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(4, 16, 4, 8)
        add(control, BorderLayout.WEST)
    }

/** Actual 的无类型裸列表不是 SQL 字面量；展示脱敏非关闭态时隐藏整段，不尝试逐值解析（W2 模式化口径）。 */
private fun shardingRouteDisplayText(actual: String): String =
    if (PluginSettings.getInstance().displayMaskExportOn()) {
        MpSqlVisualizerBundle.message("log.preview.shardingRoutesMasked")
    } else actual

/** 就地替换现有文本，清掉选区；不重建参数区、不改原始条目或预览模型。 */
internal fun SqlPreviewPanel.refreshShardingRouteDisplay() {
    val actual = current?.actualSql ?: return
    val display = shardingRouteDisplayText(actual)
    UIUtil.uiTraverser(paramPanel).filter(javax.swing.JTextArea::class.java).forEach { text ->
        if (text.name == "shardingActualSqlText") {
            text.text = display
            text.caretPosition = 0
            text.toolTipText = null
        }
    }
}

internal fun SqlPreviewPanel.rebuildLeftPanel(result: PreviewResult) {
    resetFieldFillSimulation()
    fieldFillPreview = result
    fieldFillFields.clear()
    fieldFillNormalFields.clear()
    fieldFillSimulationComponents.clear()
    fieldFillCheckBox = null
    fieldFillStatus = null
    impactResultLabel = null
    paramPanel.removeAll()
    paramFields.clear()
    paramWarnLabels.clear()
    conditionCheckBoxes.clear()
        conditionOutcomeLabels.clear()
    xmlConditionOutcomeLabels.clear()
    conditionValueFields.clear()
    batchCheckBoxes.clear()
    xmlForEachNullBoxes.clear()
    xmlForEachValueFields.clear()
    // T-M26.3：按钮随场景重建（标题行右侧）——先清空上次场景的残留引用
    toggleSelectButton = null
    clearButton = null
    batchToggleSelectButton = null
    batchClearButton = null
    val views = result.previewModel?.conditionViews.orEmpty()
    val batchModel = result.batchModel
    // R22 T-04：先算 hasParams——方案行插在首个早退之后，xml 无控件早退场景必须覆盖
    // （xml 无控件时无参数可存召回，不插方案行，避免「方案下拉 + 无参数提示」同屏）
    val xmlModelForSchemeRow = result.xmlModel
    val xmlHasControls = xmlModelForSchemeRow != null &&
        (xmlModelForSchemeRow.conditionViews.isNotEmpty() || xmlModelForSchemeRow.forEachViews.isNotEmpty())

    // UI-008：沿用参数预设的标准折叠件，放在早退之前，无参数或歧义条目也能看路由。
    result.actualSql?.takeIf { it.isNotBlank() }?.let { actual ->
        val display = shardingRouteDisplayText(actual)
        val text = javax.swing.JTextArea(display, display.lineSequence().count().coerceIn(2, 6), 24).apply {
            name = "shardingActualSqlText"
            isEditable = false
            lineWrap = true
            wrapStyleWord = false
            caretPosition = 0
        }
        paramPanel.add(HideableTitledPanel(
            MpSqlVisualizerBundle.message("log.preview.shardingRoutes"),
            false,
            com.intellij.ui.components.JBScrollPane(text),
            false,
        ).apply {
            name = "shardingActualSqlFold"
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        })
    }

    if (views.isEmpty() && result.unresolvedParams.isEmpty() && batchModel == null && !xmlHasControls) {
        // T-DevAcceptFix ①：绑定指示行已迁顶部常驻行——无参数早退不再重复建行
        paramPanel.add(JBLabel(MpSqlVisualizerBundle.message("panel.param.none")).apply {
            border = JBUI.Borders.empty(6, 8)
        })
        paramPanel.revalidate()
        paramPanel.repaint()
        return
    }

    // R22 T-04：方案行（设计定稿位置——首个早退之后、batchModel 分支之前）
    paramPanel.add(buildSchemeRow())

    // T-M23-04：批量列级勾选（toggle 列：复选框 + 值输入框，勾选决定列是否进入 SQL；
    // fixed 列：仅值输入框，恒参与）——与动态条件区互斥（批量预览无 conditionViews）
    // T-M23.2（用户要求）：参数可选场景必须有【全选/全不选】按钮（标题行右侧，T-M26.3 回归）
    if (batchModel != null) {
        paramPanel.add(batchSectionHeader())
        if (batchModel.toggleColumns.any { it.fill != null && it.fill != "DEFAULT" }) {
            fieldFillCheckBox = JCheckBox(MpSqlVisualizerBundle.message("fieldFill.simulation.enabled"), false).apply {
                name = "fieldFillEnabled"
                text = fieldFillText(text, font)
                toolTipText = MpSqlVisualizerBundle.message("fieldFill.simulation.enabled.tooltip")
                addActionListener {
                    fieldFillEnabled = isSelected
                    applyFieldFillSimulationVisibility()
                    renderSql()
                }
            }
            paramPanel.add(fieldFillRow(fieldFillCheckBox!!))
            fieldFillStatus = JBLabel(MpSqlVisualizerBundle.message("fieldFill.simulation.status")).apply {
                name = "fieldFillStatus"
                toolTipText = text
                text = fieldFillText(text, font)
                foreground = UIUtil.getContextHelpForeground()
            }
            paramPanel.add(fieldFillRow(fieldFillStatus!!))
        }
        // T-M26.3：fixed 列标签参与列宽统一（toggle 列复选框文本独立截断上限）
        val labelWidth = labelColumnWidth(batchModel.fixedColumns.map { "#${it.source}" })
        batchModel.toggleColumns.forEach { paramPanel.add(buildBatchColumnRow(it, isToggle = true)) }
        batchModel.fixedColumns.forEach { paramPanel.add(buildBatchColumnRow(it, isToggle = false, labelWidth)) }
        // BoxLayout aligns all direct children on a common X anchor; do not mix centered panels
        // with standalone LEFT-aligned labels/check boxes (the cause of the shifted safety switch).
        paramPanel.components.filterIsInstance<JComponent>().forEach {
            it.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        }
        // showResult 在构建控件前召回普通参数；模拟值须等新预览复位、输入框建好后，
        // 才从参数预设缓存恢复，不从 SQL 历史回落恢复。
        if (paramHistoryCache.isCurrentGeneration()) {
            historyCacheKey()
                ?.let { paramHistoryCache.recall(it) ?: paramHistoryCache.recallRecent(it) }
                ?.forEach { (key, value) ->
                if (key.startsWith(FieldFillSimulation.SNAPSHOT_PREFIX)) {
                    fieldFillFields[key.removePrefix(FieldFillSimulation.SNAPSHOT_PREFIX)]?.text = value
                }
            }
        }
        paramPanel.revalidate()
        paramPanel.repaint()
        return
    }

    // T-M24-04：XML 预览左栏——动态条件开关 + foreach 次数/null + 参数值输入
    // T-M26.4：bind 分区已删除——bind 求值结果由「参数」区回显（输入覆盖、清空恢复自动值）
    val xmlModel = result.xmlModel
    if (xmlModel != null) {
        val hasControls = xmlModel.conditionViews.isNotEmpty() || xmlModel.forEachViews.isNotEmpty()
        if (!hasControls && result.unresolvedParams.isEmpty()) {
            paramPanel.add(JBLabel(MpSqlVisualizerBundle.message("panel.param.none")).apply {
                border = JBUI.Borders.empty(6, 8)
            })
            paramPanel.revalidate()
            paramPanel.repaint()
            return
        }
        // #{} 参数值输入（T-M25.4：foreach 展开项不再单独建行——集合值由 foreach 输入框统一回填）
        val ordinarySources = result.unresolvedParams.distinct().filter { !it.startsWith("__frch_") }
        // T-M26.3：两列行标签列宽统一（foreach/参数按最长可见标签自适应 140~260）
        // T-M26.5：foreach 行标签即集合表达式（分区标题已含「foreach 集合」语义）
        val labelWidth = labelColumnWidth(
            xmlModel.forEachViews.map { it.collectionExpr } +
                ordinarySources.map { "#$it" },
        )
        if (xmlModel.conditionViews.isNotEmpty()) {
            paramPanel.add(conditionSectionHeader())
            xmlModel.conditionViews.forEach { paramPanel.add(buildXmlConditionRow(it)) }
        }
        if (xmlModel.forEachViews.isNotEmpty()) {
            paramPanel.add(sectionHeaderRow(MpSqlVisualizerBundle.message("panel.xml.foreach.section")))
            xmlModel.forEachViews.forEach { paramPanel.add(buildXmlForEachRow(it, labelWidth)) }
        }
        if (ordinarySources.isNotEmpty()) {
            if (hasControls) paramPanel.add(sectionHeaderRow(MpSqlVisualizerBundle.message("panel.param.section")))
            ordinarySources.forEach { paramPanel.add(buildParamRow(it, labelWidth)) }
        }
        paramPanel.revalidate()
        paramPanel.repaint()
        return
    }

    val viewSources = views.flatMap { it.valueSources }.toSet()
    // 普通参数行：未解析参数中不属于任何动态条件值输入框的来源（去重，同一 source 共享一个输入框）
    val ordinarySources = result.unresolvedParams.distinct().filter { it !in viewSources }
    val labelWidth = labelColumnWidth(ordinarySources.map { "#$it" })
    if (views.isNotEmpty()) {
        paramPanel.add(conditionSectionHeader())
        views.forEach { paramPanel.add(buildConditionRow(it)) }
    }
    if (ordinarySources.isNotEmpty()) {
        if (views.isNotEmpty()) paramPanel.add(sectionHeaderRow(MpSqlVisualizerBundle.message("panel.param.section")))
        ordinarySources.forEach { paramPanel.add(buildParamRow(it, labelWidth)) }
    }
    // T-JpaParamOrder F5：同名参数多次 setParameter 轻提示（JPA 覆盖语义，运行时以最后一次
    // 为准）——帮用户发现自身代码的写重；灰字注记行，不发通知、不弹窗
    if (result.duplicateParamNames.isNotEmpty()) {
        paramPanel.add(
            JBLabel(
                MpSqlVisualizerBundle.message(
                    "panel.param.duplicate.note",
                    result.duplicateParamNames.sorted().joinToString(", "),
                ),
            ).apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(0, 8, 2, 8)
            },
        )
    }
    paramPanel.revalidate()
    paramPanel.repaint()
}

// ------------------------------------------------------------------
// R22 T-04：命名方案下拉 + 管理对话框接入（设计定稿：方案行 / 防串桶 / 对话框回调）
// ------------------------------------------------------------------

/**
 * R22 T-04：方案行——「预设: [下拉] [管理预设…]」（compactButton 同字号，非自创形态）。
 * 下拉项为原始方案名（默认方案 = 空串内部名），显示名经 bundle 本地化
 * （默认方案显示名与用户命名可撞名，故渲染器映射而非直接用显示名作条目）。
 * O-88：整行收进平台标准折叠件 [HideableTitledPanel]，标题复用 `panel.scheme.label`，
 * 缺省折叠（20261006 T-DevAcceptFix 验收反馈：预设低频，默认不占竖向空间）；折叠时内容区
 * setVisible(false) 不占竖向空间。折叠态存静态字段（[presetSectionCollapsed]，
 * 跨预览重建且跨面板实例保留、不持久化）。
 */

internal fun SqlPreviewPanel.buildSchemeRow(): JComponent {
    // O-88：懒惰捕获——上一份折叠件实例若被用户点标题折叠过，先回写静态字段再重建
    // （HideableTitledPanel 公共面无监听器；摘除后实例仍持有最后视觉态，clear/rebuild 均不丢）
    presetFold?.let { presetSectionCollapsed = !it.isExpanded() }
    val combo = JComboBox<String>()
    schemeCombo = combo
    combo.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
        label.text = displaySchemeName(value ?: ParamHistoryCache.DEFAULT_SCHEME)
    }
    combo.addActionListener {
        if (schemeSyncingCombo) return@addActionListener
        val raw = combo.selectedItem as? String ?: return@addActionListener
        if (raw != paramHistoryCache.activeSchemeFor(historyCacheKey())) switchToScheme(raw)
    }
    syncSchemeCombo()
    val manageButton = compactButton(MpSqlVisualizerBundle.message("panel.scheme.manage")) {
        openSchemeManagerDialog()
    }
    val schemeRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
        name = SCHEME_ROW_NAME
        add(JBLabel(MpSqlVisualizerBundle.message("panel.scheme.label")))
        add(combo)
        add(manageButton)
        border = JBUI.Borders.empty(0, 8, 2, 8)
    }
    // O-88：adjustWindow=false——预览面板嵌在弹窗/工具窗内容区，折叠/展开不得调整窗口尺寸
    // （源码语义：true 时 adjustWindow 会按内容尺寸改祖先 Window 大小，Run Config 弹窗专用）
    return HideableTitledPanel(
        MpSqlVisualizerBundle.message("panel.scheme.label"),
        false,
        schemeRow,
        !presetSectionCollapsed,
    ).also { presetFold = it }
}

/** R22 T-04：按 cache 当前态重建下拉模型与选中项（程序化填充，抑制切换语义）。
 *  T-DevAcceptFix2 ①（20261006 第二轮验收）：名单与选中项均**按当前方法/SQL 键**取——
 *  每个方法只列自己的预设（`schemeNamesFor(key)`），激活态取该键的指针（`activeSchemeFor(key)`）。 */

internal fun SqlPreviewPanel.syncSchemeCombo() {
    val combo = schemeCombo ?: return
    schemeSyncingCombo = true
    try {
        val key = historyCacheKey()
        val model = DefaultComboBoxModel<String>()
        model.addElement(ParamHistoryCache.DEFAULT_SCHEME)
        paramHistoryCache.schemeNamesFor(key).forEach { model.addElement(it) }
        val active = paramHistoryCache.activeSchemeFor(key)
        // T-PresetMethodBinding：同一方法改了 SQL 后，该方案在新键下没有快照（靠最近键指针回落），
        // 名单按键过滤会漏掉它，而激活态仍是它——补进名单，避免「下拉看不到却显示选中」。
        if (active != ParamHistoryCache.DEFAULT_SCHEME && model.getIndexOf(active) < 0) {
            model.addElement(active)
        }
        combo.model = model
        combo.selectedItem = active
        lastShownActiveScheme = active
    } finally {
        schemeSyncingCombo = false
    }
}

// ------------------------------------------------------------------
// O-95 T-PreviewEdtGuard S3：绑定数据源常驻指示行
//（T-DevAcceptFix ①：迁顶栏「更多」按钮下方右对齐常驻行，原参数区预设行之下位置废止）
// ------------------------------------------------------------------

/**
 * S3 指示行数据源名（null = 未绑定/取不到）。测试缝优先——无 Database 插件的 headless
 * 用例经 [bindStateNameProvider] 构造绑定态；生产走 [cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSourceName]
 * （T-ConsoleDbContext 同源链：共享/已备控制台 + isValid 先探 + DBN 取名，全程 fail-closed）。
 */

internal fun SqlPreviewPanel.boundDataSourceNameForDisplay(): String? =
    bindStateNameProvider?.invoke()
        ?: runCatching {
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSourceName()
        }.getOrNull()

/**
 * O-96 T-PreviewBindAssociation S1：last-used 记忆回退取名（指示行第二优先级——无活控制台时，
 * 经 [cn.song.mybatisplus.visualizer.execute.DataSourceSelector.rememberedDataSourceName] 按
 * T-SI-02 同一口径（UUID→名唯一→歧义弃）解析在籍数据源名；只读，歧义不清记忆不弹窗）。
 * project 为 null（无项目上下文）或解析异常 → null，回落未绑定。
 */

internal fun SqlPreviewPanel.rememberedDataSourceNameForDisplay(): String? {
    val proj = project ?: return null
    return runCatching {
        cn.song.mybatisplus.visualizer.execute.DataSourceSelector
            .rememberedDataSourceName(proj, sourceModuleKey)
    }.getOrNull()
}

/**
 * T-ModuleScopedLastUsed W3：本次显示的记忆名来自**模块级**还是**项目级兜底**。
 *
 * **判据必须是「模块级键本身有没有值」**，不能比较两个解析结果——沙盒验收实测到的缺陷：
 * `lastUsedRunner(proj, moduleKey)` 在模块级为空时会**回落项目级**，于是
 * `scoped == projectLevel` 恒成立，只有项目级记忆时也被误判成「本模块上次使用」
 * （真机实际状态：workspace.xml 只有 `mpsql.visualizer.last.runner`，无模块级键，
 * 灰字却显示「数据源（本模块上次使用）」）。
 *
 * 正确做法：直接读模块级那一层的原始键（[DataSourceSelector.LastUsedKeys]），有值才算模块级。
 * 只读、零副作用；仅用于文案限定词，不参与取源决策。
 */
internal fun SqlPreviewPanel.rememberedIsModuleScoped(): Boolean {
    val proj = project ?: return false
    val moduleKey = sourceModuleKey?.takeIf { it.isNotBlank() } ?: return false
    return runCatching {
        val pc = com.intellij.ide.util.PropertiesComponent.getInstance(proj)
        val keys = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.LastUsedKeys
        val id = pc.getValue(keys.dataSourceIdKey(moduleKey))
        val name = pc.getValue(keys.displayNameKey(moduleKey))
        !id.isNullOrBlank() || !name.isNullOrBlank()
    }.getOrDefault(false)
}

/**
 * S3 指示行文案（O-96 S1 升级为三级）：活控制台绑定=「已绑定：<源名>」（panel.bindstate.bound，
 * 数据源名与 R33 结构化视图头部同源）；无活控制台但 last-used 记忆命中在籍数据源=
 * 「关联（上次使用）：<源名>」（panel.bindstate.associated——S2 证伪定案：EXPLAIN 直送通道
 * 走 selectAndExecute 不做记忆静默复用，与主执行链 prepareConsole 非严格同源，故记忆态
 * 文案区分，不冒称「已绑定」）；两者皆无=「未绑定数据源（在控制台执行一次查询即可绑定）」
 * （panel.bindstate.unbound，与 R33 回退通知同源文案族）。
 */

internal fun SqlPreviewPanel.bindStateText(): String {
    val name = boundDataSourceNameForDisplay()
    if (name != null) return MpSqlVisualizerBundle.message("panel.bindstate.bound", name)
    val remembered = rememberedDataSourceNameForDisplay()
    if (remembered != null) {
        // T-ModuleScopedLastUsed W3：模块级命中与项目级兜底文案区分（只改括号内限定词，
        // 不新增控件——W12 定案自研换源 UI 已全部退役）。
        val key = if (rememberedIsModuleScoped()) {
            "panel.bindstate.associatedModule"
        } else {
            "panel.bindstate.associated"
        }
        return MpSqlVisualizerBundle.message(key, remembered)
    }
    return MpSqlVisualizerBundle.message("panel.bindstate.unbound")
}

/**
 * S3 指示行（灰字文本行，D2=A 定案——轻量形态禁自创图形）：contextHelp 灰字与同面板
 * 既有灰字形态一致（degradation 提示行同款 UIUtil.getContextHelpForeground）。
 *
 * T-DevAcceptFix ①（20261006 验收）：容器改**顶栏「更多」按钮下方右对齐**常驻薄行
 * （FlowLayout.RIGHT，右缘边距与工具栏 8px 对齐）——与右侧内嵌控制台头部数据源显示相邻，
 * 不再挂在参数区预设行之下；init 一次构建，刷新仍走 [refreshBindStateRow] 单点收口。
 */

/**
 * 指示行（S3 三级文案 / O-96）。
 *
 * T-ExplainFailUx W2（2026-10-24）：曾在此升级为自研「数据源下拉」。
 * **T-ExplainSwitchNative W5/W12（同日修订）**：自研下拉被用户否决——「我希望选的优先是 idea
 * 原生的」；后续实测进一步定案（图三）：**平台原生工具栏自带 `Data Sources` 菜单**
 * （Detach Data Source + 全部源）就是理想形态，且平台 `getForcedDataSourceForExec` 的关联
 * 无公开清除 API，插件自建入口第二次起必被短路成单源。故本轮**彻底撤掉自研换源 UI**：
 * 指示行只保留纯文本三级文案（[bindStateText]），换源一律走右侧控制台头部的原生工具栏。
 *
 * **插件不可用（硬约束，W2 纪律沿用）**：社区版无 Database 插件时指示行仍为纯文本、不渲染任何
 * 控件、不报错、不弹提示（本轮撤掉按钮后该约束天然满足）。
 */
internal fun SqlPreviewPanel.buildBindStateRow(): JComponent {
    val label = JBLabel(bindStateText()).apply {
        name = BIND_STATE_LABEL_NAME
        foreground = UIUtil.getContextHelpForeground()
    }
    bindStateLabel = label
    return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 8, 2)).apply {
        name = BIND_STATE_ROW_NAME
        add(label)
        // T-ExplainSwitchNative W12（2026-10-24 用户实测三图）：
        // 图三证明**平台原生工具栏自带 `Data Sources` 菜单**（含 Detach Data Source + 全部源），
        // 那才是「切换数据源」的正确形态。插件侧自建入口（W2 下拉 / W5 链接）已被用户否决；
        // 且平台 `getForcedDataSourceForExec` 的关联无公开清除 API，自建入口做不到与原生一致
        // ——故**撤掉自研入口**，换源一律走原生工具栏（右侧控制台头部）的 Data Sources 菜单。
        border = JBUI.Borders.empty(0, 8, 2, 8)
    }
}

/**
 * S3 绑定态刷新：renderSql 尾部单点收口（循 R30 条件探镜「零监听器」口径——控制台上下文
 * 变更链〔attach/reuse/clearPrepared〕的既有 post-attach 渲染/清空路径都会走 renderSql，
 * 不另立监听器；纯文本读，write-safe 闸重投递亦无副作用）。
 */

internal fun SqlPreviewPanel.refreshBindStateRow() {
    val label = bindStateLabel ?: return
    label.text = bindStateText()
}

/**
 * R22 T-04：切换方案（下拉/对话框回调共用）——清态 → [ParamHistoryCache.switchScheme] →
 * [recallFromHistory]。清场含可见字段文本与勾选框视觉态（新方案快照未覆盖的键不得残留旧值）；
 * 末尾无条件 renderSql：召回未命中时 SQL 也必须反映清空后的参数态。
 */

internal fun SqlPreviewPanel.switchToScheme(name: String) {
    clearParamStateForSchemeSwitch()
    val key = historyCacheKey()
    if (key != null) paramHistoryCache.switchScheme(name, key)
    lastShownActiveScheme = name
    recallFromHistory()
    renderSql()
}

/** R22 T-04：方案切换清场（等价 clearStateForTest 的状态面 + 可见组件视觉态回默认）。
 *  顺序敏感：先清可见字段文本（DocumentListener 回写空值——onParamChanged 无条件 put，
 *  先清 map 会被复写），状态集清场放最后，保证 paramValues 干净、召回「已输入值优先」不误判。 */

internal fun SqlPreviewPanel.clearParamStateForSchemeSwitch() {
    resetFieldFillSimulation()
    paramFields.forEach { it.text = "" }
    xmlForEachValueFields.forEach { it.second.text = "" }
    // 编程式 isSelected 不触发 ActionListener，enabledIds 不受影响（末尾直接清）
    (conditionCheckBoxes + batchCheckBoxes).forEach { it.isSelected = false }
    xmlForEachNullBoxes.forEach { it.isSelected = false }
    enabledIds.clear()
    paramValues.clear()
    xmlForEachValues.clear()
    xmlForEachNulls.clear()
}

/** R22 T-04：管理方案对话框工厂（快照与回调在此接线——面板供给，对话框不知面板）。 */

internal fun SqlPreviewPanel.createSchemeManagerDialog(proj: Project): ParamSchemeManagerDialog =
    ParamSchemeManagerDialog(
        proj,
        paramHistoryCache,
        snapshotProvider = {
            val key = historyCacheKey()
            if (key == null) null else key to buildHistorySnapshot()
        },
        onSchemesChanged = { onSchemesChangedFromDialog() },
    )

/** R22 T-04：管理方案对话框（MODELESS 单实例——可见时置前，不堆叠）。 */

internal fun SqlPreviewPanel.openSchemeManagerDialog() {
    val proj = project ?: return
    schemeManagerDialog?.let { existing ->
        if (existing.isVisible) {
            existing.toFront()
            return
        }
    }
    createSchemeManagerDialog(proj).also {
        schemeManagerDialog = it
        it.show()
    }
}

/** R22 T-04：对话框回调——同步下拉；激活方案被对话框变更（另存为自动切换）时整面重召回。 */

internal fun SqlPreviewPanel.onSchemesChangedFromDialog() {
    val active = paramHistoryCache.activeSchemeFor(historyCacheKey())
    val activeChanged = active != lastShownActiveScheme
    syncSchemeCombo()
    if (activeChanged) switchToScheme(active)
}

/**
 * T-M23-04：批量列行——toggle 列渲染「复选框（列来源，tooltip 列名）+ 值输入框」，
 * 勾选变更重渲染（列名入 [enabledIds]）；fixed 列渲染「#来源 标签 + 值输入框」。
 * T-M26.3：fixed 列标签按统一列宽（labelWidth）构建。
 */

internal fun SqlPreviewPanel.buildBatchColumnRow(col: BatchColumn, isToggle: Boolean, labelWidth: Int = 0): JBPanel<JBPanel<*>> {
    val hasFill = col.fill != null && col.fill != "DEFAULT"
    val row = JBPanel<JBPanel<*>>(if (hasFill) java.awt.GridBagLayout() else FlowLayout(FlowLayout.LEFT, 8, 6))
    fun addCell(component: JComponent, x: Int, y: Int, span: Int = 1) {
        if (!hasFill) {
            row.add(component)
            return
        }
        row.add(component, java.awt.GridBagConstraints().apply {
            gridx = x; gridy = y; gridwidth = span
            anchor = java.awt.GridBagConstraints.WEST
            weightx = if (span == 2 || x == 1) 1.0 else 0.0
            insets = JBUI.insets(3, 8, 3, 0)
        })
    }
    if (isToggle) {
        val checkBox = JCheckBox(checkBoxText(col.source), col.columnName in enabledIds).apply {
            toolTipText = if (checkBoxText(col.source) != col.source) col.source else col.columnName
            putClientProperty(PROP_TOGGLE_ID, col.columnName) // R22 T-02：快照枚举/召回同步的 id 锚点
            addActionListener {
                if (isSelected) enabledIds.add(col.columnName) else enabledIds.remove(col.columnName)
                renderSql()
            }
        }
        if (hasFill) {
            // 角标并入字段名文本：名称、[fill=...]、普通值输入保持同一行；长名称按 200px 换行。
            checkBox.text = fieldFillText("${col.source} [fill=${col.fill}]", checkBox.font, JBUI.scale(200))
            checkBox.toolTipText = listOfNotNull(
                if (checkBoxText(col.source) != col.source) col.source else col.columnName,
                MpSqlVisualizerBundle.message("fieldFill.simulation.annotation.tooltip"),
            ).joinToString("\n")
        }
        batchCheckBoxes += checkBox
        addCell(checkBox, 0, 0)
    } else if (hasFill) {
        addCell(fieldFillNameCell(paramLabel(col.source, labelWidth), col.fill!!), 0, 0)
    } else {
        addCell(paramLabel(col.source, labelWidth), 0, 0)
    }
    val field = JBTextField(8).apply {
        toolTipText = "#${col.source}"
        text = paramValues[col.source] ?: ""
        putClientProperty(PROP_FIELD_SOURCE, col.source) // T-DevAcceptFix ⑤：召回回写的 source 锚点
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onParamChanged(col.source, this@apply.text)
            override fun removeUpdate(e: DocumentEvent?) = onParamChanged(col.source, this@apply.text)
            override fun changedUpdate(e: DocumentEvent?) = onParamChanged(col.source, this@apply.text)
        })
    }
    paramFields += field
    addCell(field, 1, 0)
    if (hasFill && isToggle) {
        fieldFillNormalFields[col.source] = field
        val simulation = JBTextField(8).apply {
            name = "fieldFill:${col.source}"
            toolTipText = MpSqlVisualizerBundle.message("fieldFill.simulation.value.tooltip")
            document.addDocumentListener(object : DocumentListener {
                private fun changed() {
                    if (fieldFillSyncing) return
                    fieldFillValues[col.source] = text
                    syncFieldFillNormalEnabled()
                    renderSql()
                    saveToHistory()
                }
                override fun insertUpdate(e: DocumentEvent?) = changed()
                override fun removeUpdate(e: DocumentEvent?) = changed()
                override fun changedUpdate(e: DocumentEvent?) = changed()
            })
        }
        fieldFillFields[col.source] = simulation
        // 模拟值行默认隐藏，只有勾选「FieldFill 安全模拟」时才出现（用户定案）。
        val simulationRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JBLabel(MpSqlVisualizerBundle.message("fieldFill.simulation.value.label")))
            add(simulation)
            isVisible = fieldFillEnabled
        }
        addCell(simulationRow, 0, 1, 2)
        fieldFillSimulationComponents += simulationRow
    }
    syncFieldFillNormalEnabled()
    row.border = JBUI.Borders.empty(0, 8, 2, 8)
    return row
}

/** T-PreviewSideQuery P1：预估影响行数内联结果；不触碰编辑器/控制台。 */
internal fun SqlPreviewPanel.showDmlImpactResult(text: String, error: Boolean, tooltip: String? = null) {
    val label = impactResultLabel ?: JBLabel().also {
        it.name = "dmlImpactResult"
        it.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        it.border = JBUI.Borders.empty(4, 16, 4, 8)
        paramPanel.add(it)
        impactResultLabel = it
    }
    label.text = fieldFillText(text, label.font, JBUI.scale(260))
    label.toolTipText = tooltip ?: text
    label.foreground = if (error) JBColor.RED else UIUtil.getContextHelpForeground()
    // T-DmlImpactProminence（用户真机反馈：左下角灰字不够明显）：结果同时挂到顶部独立横幅
    // （蓝色加粗；失败红色），与降级/渲染警示各占一行、互不抢位。
    showDmlImpactBanner(text, error)
    paramPanel.revalidate()
    paramPanel.repaint()
}

/** T-DmlImpactProminence：影响行数结果横幅显隐与配色（独立行，见 [SqlPreviewPanel.dmlImpactBanner]）。 */
internal fun SqlPreviewPanel.showDmlImpactBanner(text: String, error: Boolean) {
    dmlImpactBannerLabel.text = text
    dmlImpactBannerLabel.foreground = if (error) JBColor.RED
    else JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
    val base = com.intellij.util.ui.UIUtil.getLabelFont()
    dmlImpactBannerLabel.font = if (error) base else base.deriveFont(java.awt.Font.BOLD)
    dmlImpactBanner.isVisible = true
    dmlImpactBanner.revalidate()
    dmlImpactBanner.repaint()
}

internal fun SqlPreviewPanel.clearDmlImpactBanner() {
    dmlImpactBanner.isVisible = false
    dmlImpactBannerLabel.text = ""
}

/** 模拟值在场时置灰对应的普通参数输入框；清空模拟值后恢复。 */
internal fun SqlPreviewPanel.syncFieldFillNormalEnabled() {
    fieldFillNormalFields.forEach { (source, field) ->
        val overridden = fieldFillEnabled && !fieldFillValues[source].isNullOrBlank()
        field.isEnabled = !overridden
        field.toolTipText = if (overridden) {
            MpSqlVisualizerBundle.message("fieldFill.simulation.overrides")
        } else {
            "#$source"
        }
    }
}

/** 模拟值行显隐：仅「FieldFill 安全模拟」勾选时显示；开关切换后立即重排。 */
internal fun SqlPreviewPanel.applyFieldFillSimulationVisibility() {
    fieldFillSimulationComponents.forEach { it.isVisible = fieldFillEnabled }
    syncFieldFillNormalEnabled()
    paramPanel.revalidate()
    paramPanel.repaint()
}

/** R92：先清可见输入框再清状态表，并抑制文档监听器回写。 */
internal fun SqlPreviewPanel.resetFieldFillSimulation() {
    fieldFillEnabled = false
    fieldFillCheckBox?.isSelected = false
    fieldFillSyncing = true
    try {
        fieldFillFields.values.forEach { if (it.text.isNotEmpty()) it.text = "" }
    } finally {
        fieldFillSyncing = false
    }
    fieldFillValues.clear()
}

internal fun SqlPreviewPanel.fieldFillOverlay(): FieldFillSimulation.Overlay {
    if (fieldFillPreview !== current || !paramHistoryCache.isCurrentGeneration()) {
        // 此处可能在 DocumentListener 通知内执行，不修改输入框文档。
        fieldFillEnabled = false
        fieldFillCheckBox?.isSelected = false
        fieldFillValues.clear()
        syncFieldFillNormalEnabled()
    }
    val model = current?.batchModel ?: return FieldFillSimulation.Overlay(emptyMap(), emptySet())
    // 模拟值优先由模型层统一裁决（非空即覆盖普通参数；普通输入已置灰并给出覆盖提示），
    // 面板不再预剔 explicitValues——旧做法会漏掉 UPDATE 预览里的 [fill=INSERT] 字段。
    val overlay = FieldFillSimulation.overlay(model, enabledIds, fieldFillValues, fieldFillEnabled)
    fieldFillStatus?.apply {
        val message = if (overlay.invalidSources.isEmpty()) {
            MpSqlVisualizerBundle.message("fieldFill.simulation.status")
        } else {
            MpSqlVisualizerBundle.message("fieldFill.simulation.invalid", overlay.invalidSources.joinToString())
        }
        val wrapped = fieldFillText(message, font)
        if (text != wrapped) {
            text = wrapped
            // UI-006: a longer error changes the row's height, not just the label's text.
            // BoxLayout caches child SizeRequirements; JLabel.revalidate() alone does not
            // invalidate that cache when an ancestor is already invalid (e.g. before realization).
            parent?.invalidate()
            paramPanel.invalidate()
            paramPanel.parent?.invalidate()
            paramScroll.revalidate()
            paramScroll.repaint()
        }
        foreground = if (overlay.invalidSources.isEmpty()) UIUtil.getContextHelpForeground() else JBColor.RED
        toolTipText = message
    }
    return overlay
}

/**
 * T-M26.3：分区标题行（IDEA Group Header 惯例）——CENTER 为 TitledSeparator（短标题 + 分隔线），
 * EAST 为可选操作按钮组（全选/全不选回挂此处，T-M23.2 形态回归；T-M26.2 曾固定面板右上角，
 * 长文本截断后按钮不再被推出视口）。
 */

internal fun SqlPreviewPanel.sectionHeaderRow(title: String, buttons: List<JButton> = emptyList()): JBPanel<JBPanel<*>> {
    val row = JBPanel<JBPanel<*>>(BorderLayout())
    row.add(TitledSeparator(title), BorderLayout.CENTER)
    if (buttons.isNotEmpty()) {
        row.add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 2, 2)).apply {
            buttons.forEach { add(it) }
        }, BorderLayout.EAST)
    }
    row.border = JBUI.Borders.empty(0, 8, 2, 8)
    return row
}

/**
 * 批量「参数列」分区标题行（T-M23.2 回归，T-M26.3 TitledSeparator 化）：左侧分区标题 +
 * 右侧单个全选/全不选切换按钮（T-SelToggle；操作 [batchCheckBoxes]，与动态条件区按钮互不影响）。
 */

internal fun SqlPreviewPanel.batchSectionHeader(): JBPanel<JBPanel<*>> {
    batchToggleSelectButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectAll")) {
        setAllBatchColumns(!batchCheckBoxes.all { it.isSelected })
    }
    batchClearButton = compactButton(MpSqlVisualizerBundle.message("panel.param.clear")) { clearBatchValues() }
    return sectionHeaderRow(
        MpSqlVisualizerBundle.message("panel.batch.columns"),
        listOf(batchToggleSelectButton!!, batchClearButton!!),
    )
}

/**
 * 批量列全选/全不选（T-M23.2）：操作 [batchCheckBoxes] 与 [enabledIds]（列名作 id），
 * 同步复选框 UI 与 SQL 重渲染（全不选 → 空列清单形态，文档化边界）。
 * T-SelToggle：由单个切换按钮按当前勾选态取反调用。
 */

internal fun SqlPreviewPanel.setAllBatchColumns(enabled: Boolean) {
    val model = current?.batchModel ?: return
    model.toggleColumns.forEach { col ->
        if (enabled) enabledIds.add(col.columnName) else enabledIds.remove(col.columnName)
    }
    batchCheckBoxes.forEach { it.isSelected = enabled }
    renderSql()
}

/**
 * 动态条件分区标题行（T-M26.3）：TitledSeparator「动态条件」+ 右侧单个全选/全不选切换按钮
 * （T-M23.2 形态回归，IDEA 组操作惯例，T-SelToggle 双按钮合一；XML 条件开关同样复用，见 T-M24-04）。
 */

internal fun SqlPreviewPanel.conditionSectionHeader(): JBPanel<JBPanel<*>> {
    toggleSelectButton = compactButton(MpSqlVisualizerBundle.message("panel.condition.selectAll")) {
        setAllConditions(!conditionCheckBoxes.all { it.isSelected })
    }
    clearButton = compactButton(MpSqlVisualizerBundle.message("panel.param.clear")) { clearConditionValues() }
    return sectionHeaderRow(
        MpSqlVisualizerBundle.message("panel.condition.section"),
        listOf(toggleSelectButton!!, clearButton!!),
    )
}

/** 左栏分区内的紧凑按钮（比工具栏按钮更小，避免挤压复选框行）。
 *  T-DevAcceptFix2 ⑨（20261006 第三轮走查，用户对照工具栏按钮反馈「文字有些糊」）：
 *  字体改由 [buttonFont] 统一取——原 `deriveFont(10.5f)` 是**非整数磅值**，Java2D 走非整数
 *  度量/次像素 hinting 路径，字形边缘发虚；工具栏按钮（11f）则清晰。现与工具栏同源同值。 */

internal fun SqlPreviewPanel.compactButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
    font = buttonFont()
    margin = JBUI.insets(0, 6, 0, 6)
    addActionListener { action() }
}

/**
 * T-SelToggle：切换按钮文案判定——复选框非空且全部勾选 → 「全不选」（点击清空），
 * 其余一切态（空集/部分勾选）→ 「全选」（点击填满）。以复选框 UI 态为基准
 * （enabledIds 跨场景共享可能残留本场景外 id，与用户所见不一致）。
 */

internal fun SqlPreviewPanel.toggleLabel(boxes: List<JCheckBox>): String =
    MpSqlVisualizerBundle.message(
        if (boxes.isNotEmpty() && boxes.all { it.isSelected }) "panel.condition.selectNone"
        else "panel.condition.selectAll",
    )

/**
 * T-SelToggle：切换按钮文案随勾选态刷新——挂在 [renderSql] 末尾单点收口
 * （三个勾选入口——切换按钮自身、批量列行/动态条件行/XML 条件行 listener——均以 renderSql 收尾）。
 * 文案未变化不 setText，避免每次重渲染触发标题行 FlowLayout 无谓重排。
 */

internal fun SqlPreviewPanel.syncToggleSelectLabels() {
    toggleSelectButton?.let {
        val label = toggleLabel(conditionCheckBoxes)
        if (it.text != label) it.text = label
    }
    batchToggleSelectButton?.let {
        val label = toggleLabel(batchCheckBoxes)
        if (it.text != label) it.text = label
    }
}

/**
 * 全选/全不选（M13.1）：批量设置动态条件勾选态（普通参数不受影响），
 * 同步复选框 UI 与 SQL 重渲染。T-M24-04：XML 预览的条件开关（id = test 表达式）一并处理。
 * T-SelToggle：由单个切换按钮按当前勾选态取反调用。
 */

internal fun SqlPreviewPanel.setAllConditions(enabled: Boolean) {
    val views = current?.previewModel?.conditionViews.orEmpty()
    val xmlViews = current?.xmlModel?.conditionViews.orEmpty()
    views.forEach { if (enabled) enabledIds.add(it.id) else enabledIds.remove(it.id) }
    xmlViews.forEach { if (enabled) enabledIds.add(it.id) else enabledIds.remove(it.id) }
    conditionCheckBoxes.forEach { it.isSelected = enabled }
    renderSql()
}

/** 动态条件行：复选框（勾选开关）+（有值来源时）值输入框（M19.5：between 等多值条件每个 source 一个输入框）。 */

internal fun SqlPreviewPanel.buildConditionRow(view: ConditionView): JBPanel<JBPanel<*>> {
    val rawLabel = view.label
        // R49：Wrapper 布尔条件探镜——布尔首参求值结果标注（恒真/恒假/恒真·缺省），纯只读探针、
        // 渲染行为零变更。flagSource 为 null（非布尔重载/无标志）不标注；outcome 静态（flag 不随
        // 勾选/参数变化），构建期定稿即可（照 R30 的「行尾淡色标注」形态）。
        // 先建标注再算复选框文案：**标注宽度必须从复选框标签的截断预算里扣掉**——否则整行比原来
        // 宽出一截，窄视口下会把「动态条件」分区标题行右侧的全选/全不选按钮顶出视口
        //（`SqlPreviewPanelTest.testSelectAllButtonsOnConditionSectionHeader` 实测抓出，500px 分栏下转红）。
        val outcomeLabel = view.flagSource?.let { flag ->
            wrapperFlagOutcome(flag)?.let { outcome ->
                JBLabel(wrapperFlagOutcomeText(outcome)).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    toolTipText = wrapperFlagOutcomeTooltip(flag, outcome)
                }
            }
        }
        val displayLabel = checkBoxText(
            rawLabel,
            (checkBoxMaxWidth() - (outcomeLabel?.preferredSize?.width ?: 0)).coerceAtLeast(MIN_CHECKBOX_TEXT_WIDTH),
        )
    val checkBox = JCheckBox(displayLabel, view.id in enabledIds).apply {
        // T-M26.3：标签被截断时 tooltip 展示表达式原文（否则保留 flagSource 提示）
        toolTipText = if (displayLabel != rawLabel) rawLabel else conditionTooltip(view)
        putClientProperty(PROP_TOGGLE_ID, view.id) // R22 T-02：快照枚举/召回同步的 id 锚点
        addActionListener {
            if (isSelected) enabledIds.add(view.id) else enabledIds.remove(view.id)
            renderSql()
        }
    }
    conditionCheckBoxes += checkBox
        // 不可标注时补 null 占位——本表按 **条件行下标** 与 [conditionCheckBoxes] 对齐，
        // 「跳着加」会让下标错位到别的条件上（前置无标志条件把后面的标注整段前移）。
        conditionOutcomeLabels.add(outcomeLabel)
    return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        add(checkBox)
            outcomeLabel?.let { add(it) }
        // M19.5：多值条件（between 等）每个 source 一个输入框；valueSources 为空时回退单值（旧路径兼容）
        view.valueSources.ifEmpty { listOfNotNull(view.valueSource) }.forEach { source ->
            val field = conditionValueField(view, source)
            add(field)
            paramFields += field
            conditionValueFields += field
        }
        border = JBUI.Borders.empty(0, 8, 2, 8)
    }
}

/** 动态条件值输入框：值变更联动勾选态与 SQL 重渲染（按 source 绑定，多值条件每个来源一个输入框）。 */

internal fun SqlPreviewPanel.conditionValueField(view: ConditionView, source: String): JBTextField = JBTextField(8).apply {        toolTipText = "#$source"
    text = paramValues[source] ?: ""
    putClientProperty(PROP_FIELD_SOURCE, source) // T-DevAcceptFix ⑤：召回回写的 source 锚点
    document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) = onConditionValueChanged(view, source, this@apply.text)
        override fun removeUpdate(e: DocumentEvent?) = onConditionValueChanged(view, source, this@apply.text)
        override fun changedUpdate(e: DocumentEvent?) = onConditionValueChanged(view, source, this@apply.text)
    })
}

/** 清除按钮：清空动态条件分区所有参数值（不影响勾选态）。 */

internal fun SqlPreviewPanel.clearConditionValues() {
    // T-DevAcceptFix2 ⑧（20261006 第三轮走查，用户反馈「清除按钮失效」）：原实现只清
    // `previewModel.conditionViews` 的条件值字段——XML 预览**没有条件值字段**（XML 条件行只有
    // 复选框），其 `#{}` 参数落在「参数 / foreach」分区 ⇒ 在 XML/注解预览下这条路径
    // **无可清对象**（点按钮毫无反应）。现按「清空本预览全部参数输入」语义覆盖：
    // 模型预览的条件值来源 + 普通参数 + foreach 权威值。
    // 边界：批量列由批量分区自己的清除负责；勾选态不在此动（那是「全不选」的职责）；
    // `__frch_` 展开项随 foreach 权威值一并清（与参数行构建的过滤口径一致）。
    val conditionSources = current?.previewModel?.conditionViews.orEmpty()
        .flatMap { view -> view.valueSources.ifEmpty { listOfNotNull(view.valueSource) } }
    val ordinarySources = current?.unresolvedParams.orEmpty()
        .distinct()
        .filter { !it.startsWith("__frch_") }
    (conditionSources + ordinarySources).forEach { source ->
        paramValues[source] = ""
        syncParamFieldText(source, "")
    }
    // foreach 权威值（可见字段文本）；监听链同步权威 map，权威 map 再显式清一遍兜底
    xmlForEachValueFields.forEach { it.second.text = "" }
    xmlForEachValues.clear()
    renderSql()
    saveToHistory()
}

/** 清除按钮：清空批量参数列分区所有参数值（不影响勾选态）。 */

internal fun SqlPreviewPanel.clearBatchValues() {
    resetFieldFillSimulation()
    val model = current?.batchModel ?: return
    (model.toggleColumns + model.fixedColumns).forEach { col ->
        paramValues[col.source] = ""
        syncParamFieldText(col.source, "")
    }
    renderSql()
    saveToHistory()
}

/** 动态条件复选框 tooltip：布尔条件源码提示（M13 起无判空语义提示，复选框纯手动）。 */

internal fun SqlPreviewPanel.conditionTooltip(view: ConditionView): String = view.flagSource?.let {
    MpSqlVisualizerBundle.message("panel.condition.flag", it)
} ?: ""

/**
 * 动态条件值变更（M13：复选框纯手动）：仅更新按 source 绑定的参数值并重渲染，
 * 勾选态只由用户操作决定（值空保留 `?`，不自动取消勾选）。
 * T-Perf-04：击键路径——重渲染防抖（同 [onParamChanged]）。
 */

internal fun SqlPreviewPanel.onConditionValueChanged(view: ConditionView, source: String, text: String) {
    paramValues[source] = text
    scheduleRender { renderSql() }
    saveToHistory()
}

/**
 * T-M24-04：XML 动态条件行——复选框（label = test 表达式，id 进 [enabledIds]），
 * 与 Wrapper 动态条件交互一致（全选/全不选按钮共用）。
 */

internal fun SqlPreviewPanel.buildXmlConditionRow(view: XmlConditionView): JBPanel<JBPanel<*>> {
    val rawLabel = view.label
    val displayLabel = checkBoxText(rawLabel)
    val checkBox = JCheckBox(displayLabel, view.id in enabledIds).apply {
        // T-M26.3：标签被截断时 tooltip 展示 test 表达式原文（否则保留条件提示）
        toolTipText = if (displayLabel != rawLabel) rawLabel else MpSqlVisualizerBundle.message("panel.xml.condition")
        putClientProperty(PROP_TOGGLE_ID, view.id) // R22 T-02：快照枚举/召回同步的 id 锚点（XML 条件行）
        addActionListener {
            if (isSelected) enabledIds.add(view.id) else enabledIds.remove(view.id)
            renderSql()
        }
    }
    conditionCheckBoxes += checkBox
    val outcomeLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }.also { xmlConditionOutcomeLabels[view.id] = it }
    return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        add(checkBox)
        // T-ExprAstPrecheck T-EP-03：表达式超出支持面时补淡色标注（提示，不改展开行为）
        xmlConditionAnnotation(view)?.let { add(it) }
        // R30 探镜：本轮求值结果标注（真/假/未求值；占位空串，renderSql 首轮刷新）
        add(outcomeLabel)
        border = JBUI.Borders.empty(0, 8, 2, 8)
    }
}

/**
 * R30 条件求值探镜：渲染后按本轮判定结果刷新各条件行尾的三态标注（真/假/未求值）。
 * 只读探针——读 [XmlPreviewModel.conditionOutcomes] 判定产物，不参与渲染文本；零监听器
 * （刷新挂在 renderSql 单点，勾选/参数/foreach 变更后的每轮渲染自动跟上）。
 */

internal fun SqlPreviewPanel.syncXmlConditionOutcomeLabels(xml: XmlPreviewModel, rendered: RenderedXmlSql) {
    if (xmlConditionOutcomeLabels.isEmpty()) return
    val outcomes = xml.conditionOutcomes(rendered)
    xmlConditionOutcomeLabels.forEach { (id, label) ->
        val outcome = outcomes[id] ?: return@forEach
        val text = when (outcome) {
            ConditionOutcome.TRUE -> MpSqlVisualizerBundle.message("panel.xml.outcome.true")
            ConditionOutcome.FALSE -> MpSqlVisualizerBundle.message("panel.xml.outcome.false")
            ConditionOutcome.UNEVALUATED -> MpSqlVisualizerBundle.message("panel.xml.outcome.unevaluated")
        }
        label.text = text
        label.toolTipText = MpSqlVisualizerBundle.message("panel.xml.outcome.tooltip", text)
    }
}

/**
 * T-ExprAstPrecheck T-EP-03：条件行的支持面标注（SUPPORTED 返回 null 不占位）。
 * 用上下文提示色（淡）以与条件标签区分；理由进 tooltip，行内只留档位短句。
 */

internal fun SqlPreviewPanel.xmlConditionAnnotation(view: XmlConditionView): JBLabel? {
    val text = when (view.support) {
        ExprSupport.SUPPORTED -> return null
        ExprSupport.UNSUPPORTED_SYNTAX -> MpSqlVisualizerBundle.message("panel.xml.expr.unsupported")
        ExprSupport.EVAL_FALLBACK -> MpSqlVisualizerBundle.message("panel.xml.expr.fallback")
    }
    return JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        toolTipText = xmlConditionAnnotationTooltip(view)
    }
}

/** 标注 tooltip：按理由码取文案，附细节（方法名 / 静态字段引用 / 解析错误消息）。 */

internal fun SqlPreviewPanel.xmlConditionAnnotationTooltip(view: XmlConditionView): String {
    val detail = view.supportDetail.orEmpty()
    return when (view.supportReason) {
        ExprReason.SYNTAX -> MpSqlVisualizerBundle.message("panel.xml.expr.reason.syntax", detail)
        ExprReason.UNKNOWN_METHOD -> MpSqlVisualizerBundle.message("panel.xml.expr.reason.unknownMethod", detail)
        ExprReason.NARROW_CARRIER -> MpSqlVisualizerBundle.message("panel.xml.expr.reason.narrowCarrier", detail)
        ExprReason.STATIC_FIELD -> MpSqlVisualizerBundle.message("panel.xml.expr.reason.staticField", detail)
        null -> view.label
    }
}

/** T-M25.4：foreach 行——**集合表达式标签 + 集合值输入框**（逗号分隔，如 `2,3` → 展开两项并回填）+ null 复选框（T-M26.5：标签即集合表达式，分区标题已含「foreach 集合」语义）。 */

internal fun SqlPreviewPanel.buildXmlForEachRow(view: XmlForEachView, labelWidth: Int): JBPanel<JBPanel<*>> {
    val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 6))
    row.add(columnLabel(view.collectionExpr, labelWidth).apply {
        border = JBUI.Borders.empty(0, 8, 0, 0)
    })
    val valuesField = JBTextField(8).apply {
        // 召回先于参数区重建；注册监听前回填权威值，避免初始文本写入触发保存或改写集合。
        text = xmlForEachValues[view.collectionExpr]?.joinToString(",").orEmpty()
        toolTipText = MpSqlVisualizerBundle.message("preview.xmlForEach.values.tooltip", XmlPreviewModel.DEFAULT_FOREACH_SIZE)
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onXmlForEachValuesChanged(view, this@apply.text)
            override fun removeUpdate(e: DocumentEvent?) = onXmlForEachValuesChanged(view, this@apply.text)
            override fun changedUpdate(e: DocumentEvent?) = onXmlForEachValuesChanged(view, this@apply.text)
        })
    }
    row.add(valuesField)
    paramFields += valuesField
    xmlForEachValueFields += view.collectionExpr to valuesField // R22 T-03：召回时按 expr 同步字段文本
    val nullBox = JCheckBox(MpSqlVisualizerBundle.message("panel.xml.foreach.null"), view.collectionExpr in xmlForEachNulls).apply {
        putClientProperty(PROP_FOREACH_NULL, view.collectionExpr) // R22 T-02：召回同步的 expr 锚点
        addActionListener {
            if (isSelected) xmlForEachNulls.add(view.collectionExpr) else xmlForEachNulls.remove(view.collectionExpr)
            renderSql()
        }
    }
    xmlForEachNullBoxes += nullBox // R22 T-02：召回视觉同步登记
    row.add(nullBox)
    row.border = JBUI.Borders.empty(0, 8, 2, 8)
    return row
}

/**
 * T-M25.4：foreach 集合值变更——逗号分隔值解析为展开项并**自动回填**：
 * `2,3` → 展开 2 项 + `IN ( 2 , 3 )`；留空 → 恢复默认展开次数并清空该 foreach 的参数值（`?` 保持）。
 *
 * O-78 T-ForeachItemRender：值以 collectionExpr 为键存入 [xmlForEachValues]，播种收进
 * [renderSql]（[syncForEachItemValues]）——原「渲染后同步读 xmlMappings 取键」在 write-unsafe
 * （防抖 Timer）上下文里 renderSql 被重投递、键清单滞后一拍，值按旧键播种丢尾巴
 * （真机形态 `IN ('1','2','4',?)`）；状态化后渲染延迟与否都按最新状态出图。
 *
 * T-Perf-04：击键路径防抖（停顿后以最终文本执行一次）语义不变。
 */

internal fun SqlPreviewPanel.onXmlForEachValuesChanged(view: XmlForEachView, text: String) {
    scheduleRender { applyXmlForEachValues(view, text) }
}

/** foreach 值应用（由防抖 Timer 调度）：存状态 + 设展开次数 + 单次渲染（播种在渲染帧内）。 */

internal fun SqlPreviewPanel.applyXmlForEachValues(view: XmlForEachView, text: String) {
    val values = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    xmlForEachValues[view.collectionExpr] = values
    xmlForEachCounts[view.collectionExpr] =
        if (values.isEmpty()) XmlPreviewModel.DEFAULT_FOREACH_SIZE else values.size
    renderSql()
    saveToHistory() // R22 T-03：foreach 权威态变更触发历史保存（原缺口③——权威态仅间接入库且不触发）
}

/**
 * O-78：foreach 值播种——在 renderSql 帧内（[xmlMappings] 刚按当前状态刷新，恒新鲜）按
 * collectionExpr → view.item 反查前缀回填/清除 `__frch_<item>_*` 参数值；多出键清除语义与
 * 原实现一致（留空 → `?` 保持）。
 */

internal fun SqlPreviewPanel.syncForEachItemValues(xml: XmlPreviewModel) {
    if (xmlForEachValues.isEmpty()) return
    xmlForEachValues.forEach { (expr, values) ->
        val item = xml.forEachViews.firstOrNull { it.collectionExpr == expr }?.item ?: return@forEach
        val keys = xmlMappings.map { it.property }.filter { it.startsWith("__frch_${item}_") }
        if (values.isEmpty()) {
            keys.forEach { paramValues.remove(it) }
        } else {
            keys.forEachIndexed { i, key ->
                if (i < values.size) paramValues[key] = values[i] else paramValues.remove(key)
            }
        }
    }
}

/** 普通参数行：`#<source>` 标签 + 值输入框（T-PT-02：按签名类型类别挂占位提示与行尾校验警示）。 */

internal fun SqlPreviewPanel.buildParamRow(source: String, labelWidth: Int): JBPanel<JBPanel<*>> {
    val kind = paramKindOf(source)
    // T-PT-02：行尾淡色警示标（校验是提示不是闸门——值照原样渲染，渲染链 renderValue 行为不动）
    val warnLabel = JBLabel("").apply {
        foreground = PARAM_WARN_FOREGROUND
        isVisible = false
    }
    // T-HibDualCapture：8 列放不下 16 字符日期值（视口滚到尾部被当成「值截断」），下限放宽到 16 列
    val field = JBTextField(16).apply {
        text = paramValues[source] ?: ""
        putClientProperty(PROP_FIELD_SOURCE, source) // T-DevAcceptFix ⑤：召回回写的 source 锚点
        emptyText.text = paramEmptyText(source, kind)
        // 长值超框宽时光标钉回首字符，视口停在头部；完整值以 tooltip 兜底
        if (text.isNotEmpty()) caretPosition = 0
        toolTipText = paramTooltip(source, text)
        updateParamWarning(warnLabel, kind, text)
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onParamFieldText(source, this@apply, warnLabel, kind)
            override fun removeUpdate(e: DocumentEvent?) = onParamFieldText(source, this@apply, warnLabel, kind)
            override fun changedUpdate(e: DocumentEvent?) = onParamFieldText(source, this@apply, warnLabel, kind)
        })
    }
    paramFields += field
    paramWarnLabels += warnLabel
    // T-M19.4-01：FlowLayout 间距 (4,2) → (8,6)——参数名较长时视觉"贴太近"（用户实测）
    return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
        add(paramLabel(source, labelWidth))
        add(field)
        add(warnLabel)
        border = JBUI.Borders.empty(0, 8, 2, 8)
    }
}

// ------------------------------------------------------------------
// T-PT-02：按签名类型类别的占位提示与校验（提示不是闸门——渲染链零改动）
// ------------------------------------------------------------------

/** 参数 source 的类型类别（无类型信息 → UNKNOWN，行为=升级前）。 */

internal fun SqlPreviewPanel.paramKindOf(source: String): ParamKind =
    if (source in current?.paginationParamSources.orEmpty()) ParamKind.NUMBER
    else current?.xmlModel?.kindFor(source) ?: ParamKind.UNKNOWN

/** 占位提示（emptyText）：有类型信息按类别；UNKNOWN 走参数命名启发式（zMyBatis 同款，仅提示）。 */

internal fun SqlPreviewPanel.paramEmptyText(source: String, kind: ParamKind): String = when (kind) {
    ParamKind.NUMBER -> MpSqlVisualizerBundle.message("panel.param.emptyText.number")
    ParamKind.TEMPORAL -> MpSqlVisualizerBundle.message("panel.param.emptyText.temporal")
    ParamKind.COLLECTION -> MpSqlVisualizerBundle.message("panel.param.emptyText.collection")
    ParamKind.OBJECT -> MpSqlVisualizerBundle.message("panel.param.emptyText.object")
    ParamKind.TEXT -> ""
    ParamKind.UNKNOWN -> if (looksLikeCollectionName(source)) {
        MpSqlVisualizerBundle.message("panel.param.emptyText.collection")
    } else {
        ""
    }
}

/**
 * 集合命名启发式（T-PT-02，zMyBatis ParameterInputDialog 同款）：参数名以 `s` 结尾
 * （排除 status/address 等单数词）或含 list/ids/array/collection → 按数组提示。
 * O-111 追加（用户 demo 实测 locationStatus 误配集合占位）：排除词改**后缀匹配**——
 * 复合驼峰词（locationStatus/orderStatus/userAddress）以单数词结尾同样不是集合。
 */

internal fun SqlPreviewPanel.looksLikeCollectionName(source: String): Boolean {
    val name = source.substringAfterLast('.').lowercase()
    if (name in COLLECTION_NAME_EXCEPTIONS || COLLECTION_NAME_EXCEPTIONS.any { name.endsWith(it) }) {
        return false
    }
    if (name.contains("list") || name.contains("ids") || name.contains("array") || name.contains("collection")) {
        return true
    }
    return name.endsWith("s") && !name.endsWith("ss")
}

/** 行尾校验警示（NUMBER 非数字 / TEMPORAL 非可解析日期）：淡色标记 + tooltip 说明，不阻塞渲染。 */

internal fun SqlPreviewPanel.updateParamWarning(label: JBLabel, kind: ParamKind, value: String?) {
    val message = paramWarningOf(kind, value)
    label.text = if (message == null) "" else PARAM_WARN_MARK
    label.toolTipText = message
    label.isVisible = message != null
}


internal fun SqlPreviewPanel.paramWarningOf(kind: ParamKind, value: String?): String? {
    if (value.isNullOrBlank()) return null
    val trimmed = value.trim()
    return when (kind) {
        ParamKind.NUMBER -> if (NUMBER_REGEX.matches(trimmed)) null
        else MpSqlVisualizerBundle.message("panel.param.warn.number")
        ParamKind.TEMPORAL -> if (DATE_REGEX.matches(trimmed)) null
        else MpSqlVisualizerBundle.message("panel.param.warn.temporal")
        else -> null
    }
}

/**
 * 参数行标签（T-M19.4-01 自适应 + T-M26.3 统一列宽）：`#source` 超列宽时中段省略显示、
 * tooltip 全名（IDEA 长文本惯例，用户 T-M19.4-01"长参数名显示不全"诉求由 tooltip 兜底）。
 */

internal fun SqlPreviewPanel.paramLabel(source: String, columnWidth: Int): JBLabel = columnLabel("#$source", columnWidth)

/**
 * T-M26.3：两列行标签——统一列宽（输入框左缘对齐，IDEA 表单惯例），
 * 文本超列宽时中段省略（保留尾部）+ tooltip 全名。
 */

internal fun SqlPreviewPanel.columnLabel(fullText: String, columnWidth: Int): JBLabel = JBLabel(fullText).apply {
    val metrics = getFontMetrics(UIUtil.getLabelFont())
    if (metrics.stringWidth(fullText) > columnWidth) {
        toolTipText = fullText
        text = ellipsize(fullText, metrics, columnWidth)
    }
    preferredSize = Dimension(columnWidth, preferredSize.height)
}

/**
 * T-M26.3：两列行标签列宽——按最长可见标签自适应，**上限跟随左栏视口宽度**
 * （标签列 + 输入框约 90px + 间距边距 38px 不超出视口，保证标题行右侧按钮不被推出；
 * 默认 240 面板 → 上限约 109，宽面板放宽至 260 封顶；未布局/测试环境取 260）。
 */

internal fun SqlPreviewPanel.labelColumnWidth(texts: List<String>): Int {
    if (texts.isEmpty()) return 140
    val metrics = getFontMetrics(UIUtil.getLabelFont())
    val viewport = paramScroll.viewport.width
    val maxWidth = if (viewport <= 0) 260 else (viewport - 128).coerceIn(80, 260)
    return texts.maxOf { metrics.stringWidth(it) }.coerceIn(80, maxWidth)
}

/**
 * T-M26.3：长文本中段省略——保留尾部（bind 字段名/参数名尾部有意义），超宽时显示 `头…尾`。
 *
 * 度量以 `(String) -> Int` 注入（[ellipsizeMiddle] 纯函数），使 meta 行的宽度预算组装
 * （[metaLineWithinBudget]）可脱 FontMetrics 单测。
 */

internal fun SqlPreviewPanel.ellipsize(text: String, metrics: FontMetrics, maxWidth: Int): String =
    ellipsizeMiddle(text, { metrics.stringWidth(it) }, maxWidth)

/**
 * T-M26.3：复选框标签截断上限——跟随视口宽度（文本 + 图标约 20px + 边距 36px 不超出视口，
 * 保证标题行右侧按钮不被推出；默认 240 面板 → 约 181；未布局/测试环境取 280）。
 */

internal fun SqlPreviewPanel.checkBoxMaxWidth(): Int {
    val viewport = paramScroll.viewport.width
    return if (viewport <= 0) 280 else (viewport - 56).coerceIn(100, 280)
}

/** 复选框字体（与渲染一致，截断度量用——UIUtil.getLabelFont 与复选框字体在 headless 下可能不一致）。 */

internal fun SqlPreviewPanel.checkBoxText(raw: String, maxWidth: Int = checkBoxMaxWidth()): String {
    val metrics = getFontMetrics(checkBoxFont)
    return if (metrics.stringWidth(raw) > maxWidth) ellipsize(raw, metrics, maxWidth) else raw
}

/** 普通参数值变更：更新回填表并重渲染 SQL（T-Perf-04：重渲染防抖——逐键全量重生成+格式化
 *  +重分词在大 SQL 下构成卡顿；paramValues 即时更新，渲染合并到停顿后一次）。 */

internal fun SqlPreviewPanel.onParamChanged(source: String, value: String) {
    paramValues[source] = value
    scheduleRender { renderSql() }
    saveToHistory()
}

/**
 * T-HibDualCapture：参数框文本变更（含导入回填等程序化 setText 触发的文档事件）——
 * tooltip 随值同步，其余走既有回填链路。
 * T-PT-02：行尾校验警示随值同步（默认参数无警示标，调用方为 buildParamRow）。
 */

internal fun SqlPreviewPanel.onParamFieldText(
    source: String,
    field: JBTextField,
    warnLabel: JBLabel? = null,
    kind: ParamKind = ParamKind.UNKNOWN,
) {
    field.toolTipText = paramTooltip(source, field.text)
    warnLabel?.let { updateParamWarning(it, kind, field.text) }
    onParamChanged(source, field.text)
}

/** T-HibDualCapture：参数框 tooltip——`#source = 完整值`，框宽放不下的长值以此兜底可见。 */

internal fun SqlPreviewPanel.paramTooltip(source: String, value: String?): String =
    "#$source" + if (value.isNullOrEmpty()) "" else " = $value"


// ------------------------------------------------------------------
// P-05：参数批量导入 / 历史召回
// ------------------------------------------------------------------

/**
 * P-05.1：弹参数导入手动输入弹窗（工具栏按钮触发）。
 * 默认回显当前 SQL 参数（JSON 格式），用户可在此基础上修改后导入。
 * 用户粘贴/编辑文本后走 [ParamImportParser.parse] → [importParams] 回填。
 */

internal fun SqlPreviewPanel.importParamsFromClipboard() {
    val proj = project ?: return
    // 有参数时预填当前 paramValues（JSON 格式），无参数时弹空框 + 占位示例
    val preFill = if (paramValues.isEmpty()) null else serializeParamsToJson()
    ParamImportDialog(proj, preFill) { input ->
        importParams(ParamImportParser.parse(input))
    }.show()
}

/**
 * P-05.1：将当前 paramValues 序列化为 JSON 字符串（自实现，不依赖 Jackson）。
 * 过滤空值参数（未填写的不显示），key 按插入顺序排列。
 */

internal fun SqlPreviewPanel.serializeParamsToJson(): String {
    val allSources = buildAllSources()
    val entries = paramValues.entries
        .filter { it.value.isNotEmpty() }
        .joinToString(",\n") { (key, value) ->
            val outKey = shortestUniqueKey(key, allSources)
            val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            """  "$outKey": "${escaped}""""
        }
    return if (entries.isEmpty()) "" else "{\n$entries\n}"
}

/**
 * T-ParamImportColumnKey：预填键取最短唯一形态——末段在全部 source 中唯一时用列名
 * （`status`），否则保留完整 source（多实体同名列需要前缀消歧）。导入侧
 * [resolveSource] 同时接受两种形态。
 */
private fun shortestUniqueKey(source: String, sources: Set<String>): String {
    val segment = source.substringAfterLast('.')
    if (segment == source || segment.isEmpty()) return source
    return if (sources.count { it.substringAfterLast('.') == segment } == 1) segment else source
}

/**
 * P-05：按 source 模糊匹配导入参数值并回填。
 *
 * 模糊匹配规则：key ≡ source 或 key ≡ `#source`。
 * 回填 paramValues + 更新对应输入框文本 + 触发 renderSql()。
 * 未匹配项计入通知。
 *
 * @param params 导入的参数映射（key → value）
 */

internal fun SqlPreviewPanel.importParams(params: Map<String, String>) {
    if (params.isEmpty()) {
        showImportResult(0, 0, emptyList(), isEmptyClipboard = true)
        return
    }
    val allSources = buildAllSources()
    var matched = 0
    val unmatchedKeys = mutableListOf<String>()
    params.forEach { (key, value) ->
        val source = resolveSource(key, allSources)
        if (source != null) {
            paramValues[source] = value
            syncParamFieldText(source, value)
            matched++
        } else {
            unmatchedKeys += key
        }
    }
    renderSql()
    showImportResult(matched, unmatchedKeys.size, unmatchedKeys, isEmptyClipboard = false)
}

/** P-05：构建当前面板所有参数 source 集合（用于模糊匹配）。 */

internal fun SqlPreviewPanel.buildAllSources(): Set<String> {
    val result = current ?: return emptySet()
    val sources = linkedSetOf<String>()
    sources += result.unresolvedParams
    result.previewModel?.conditionViews?.forEach { view ->
        view.valueSources.ifEmpty { listOfNotNull(view.valueSource) }.forEach { sources += it }
    }
    result.batchModel?.toggleColumns?.forEach { sources += it.source }
    result.batchModel?.fixedColumns?.forEach { sources += it.source }
    result.xmlModel?.forEachViews?.forEach { sources += it.collectionExpr }
    return sources
}

/**
 * P-05.1：模糊匹配 source（三级 fallback）。
 *
 *  1. 精确命中（key ≡ source）
 *  2. 去 `#` 前缀精确命中（key ≡ `#source`）
 *  3. 驼峰/下划线互转命中（`userName` ≡ `user_name`）
 *
 * 精确匹配优先，互转匹配兜底，避免风格差异导致漏匹配。
 */

internal fun SqlPreviewPanel.resolveSource(key: String, sources: Set<String>): String? {
    // 1. 精确命中
    if (sources.contains(key)) return key
    // 2. 去 # 前缀精确命中
    val stripped = if (key.startsWith("#")) key.substring(1) else key
    if (sources.contains(stripped)) return stripped
    // 3. 驼峰/下划线互转命中
    val snake = CaseFormatUtil.camelToSnake(stripped)
    if (sources.contains(snake)) return snake
    val camel = CaseFormatUtil.snakeToCamel(stripped)
    if (sources.contains(camel)) return camel
    // 4. 末段唯一匹配（T-ParamImportColumnKey）：wrapper 预览的 source 带实体变量前缀
    //    （`order.status`——BatchOperationConverter.entityPrefix 取调用首参表达式，不是 SQL 别名），
    //    用户按列名导入（`status`）应唯一命中；多实体同名列（歧义）保持未匹配，不猜。
    val segment = stripped.substringAfterLast('.')
    if (segment.isNotEmpty()) {
        val variants = setOf(segment, CaseFormatUtil.camelToSnake(segment), CaseFormatUtil.snakeToCamel(segment))
        val matches = sources.filter { it.substringAfterLast('.') in variants }
        if (matches.size == 1) return matches.single()
    }
    return null
}

/** P-05：同步参数输入框文本（导入回填时更新 UI 显示）。 */

internal fun SqlPreviewPanel.syncParamFieldText(source: String, value: String) {
    // T-DevAcceptFix ⑤（20261006 验收）：按 source 锚点显式映射——原实现以 paramValues 插入序
    // 对位 paramFields 行序（currentParamFieldIndex），方案切换后 map 重填序=另一方案快照保存序，
    // 与其后的面板行构建序漂移时回写错位（值写进别家输入框）。锚点挂在输入框 clientProperty 上，
    // 与任何遍历序解耦。反向验证读数：退回插入序对位，跨方案键序漂移用例即 ComparisonFailure 红。
    paramFields.firstOrNull { it.getClientProperty(PROP_FIELD_SOURCE) == source }?.text = value
}

/**
 * P-05：显示导入结果通知。
 *
 * @param matched    匹配数
 * @param unmatched  未匹配数
 * @param unmatchedKeys 未匹配的 key 列表
 * @param isEmptyClipboard 是否剪贴板无有效内容（true=空内容提示，false=匹配结果提示）
 */

internal fun SqlPreviewPanel.showImportResult(matched: Int, unmatched: Int, unmatchedKeys: List<String>, isEmptyClipboard: Boolean) {
    // T-Perf-20：空剪贴板提示是**纯行内状态更新**（不弹任何窗），与下面的通知分支性质不同，
    // 故前置到测试模式闸门之前——闸门只为避开 Messages/TestDialog 拦截器，行内提示无此依赖，
    // 前置后该提示态可被 headless 测试驱动（T-SourceJumpFunnel P6 的 tooltip 契约据此断言）。
    if (isEmptyClipboard) {
        showEmptyClipboardHint()
        return
    }
    // 测试环境跳过弹窗（Messages 在 test mode 下触发 TestDialog 拦截器抛异常）
    if (ApplicationManager.getApplication().isUnitTestMode) return
    val title = MpSqlVisualizerBundle.message("panel.param.import.result.title")
    val proj = project ?: return
    when {
        unmatched == 0 -> {
            // T-UiNative T-UN-02：导入结果改轻通知（原模态弹窗——纯告知无需打断）
            runCatching {
                Notification(
                    NOTIFICATION_GROUP_ID,
                    title,
                    MpSqlVisualizerBundle.message("panel.param.import.result.success", matched),
                    NotificationType.INFORMATION,
                ).notify(proj)
            }
        }
        else -> {
            // T-UiNative T-UN-02：部分匹配改轻通知（原模态警示框；WARNING 保留原警示图标语义）
            runCatching {
                Notification(
                    NOTIFICATION_GROUP_ID,
                    title,
                    MpSqlVisualizerBundle.message(
                        "panel.param.import.result.partial", matched, unmatched, unmatchedKeys.joinToString(", "),
                    ),
                    NotificationType.WARNING,
                ).notify(proj)
            }
        }
    }
}

/**
 * T-Perf-20：空剪贴板（未识别到有效参数）的**行内**提示——元信息行红字 + 错误复制按钮可见，
 * 不弹模态框（toast 语义：下次 [updateMeta] 覆盖）。
 *
 * T-SourceJumpFunnel P6 修复：提示态 tooltip 由「覆写」改「拼接」——[updateMeta] 写入的
 * [metaTooltipBase]（T-StackHoverUi 起只含 meta/警示/perf，完整调用链在悬停弹层）不能被提示
 * 覆写丢掉——故提示态按「提示 + 换行 + 基线」拼接，
 * 本字段是拼接的基线来源（也保证提示重复触发时幂等，不会叠加自身）。
 *
 * P10 修复（2026-09-20 真机走查发现）：提示此前**只写** `metaLabel.text`，不进 [metaLineHead] /
 * [metaLineText]。而 `errorCopyButton.isVisible = true` 会改变工具栏理想宽 → topRow 重排 →
 * 紧接着的 [applyMetaTruncation]（预算变了故不短路）用旧的 `metaLineText` 把提示**当场覆盖**——
 * 真机走查实测「提示态根本看不到」（红字与复制按钮都没出现），即本项此前只在单测里成立。
 * 现把提示写进同一套分段缓存并复位 [lastMetaBudget]，可见行统一由 [applyMetaTruncation] 收口渲染
 * （含宽度预算与 perf 段），提示因此参与布局且不再被覆盖；`toast 语义`不变——下次 [updateMeta]
 * 重写分段缓存即覆盖。
 */

internal fun SqlPreviewPanel.showEmptyClipboardHint() {
    val hint = MpSqlVisualizerBundle.message("panel.param.import.result.empty")
    currentWarning = hint
    val hintLine = "⚠ $hint"
    metaLineHead = hintLine
    metaLineDigest = ""
    metaLineText = hintLine
    lastMetaBudget = -1
    metaLabel.toolTipText = if (metaTooltipBase.isEmpty()) hint else "$hint\n$metaTooltipBase"
    metaLabel.foreground = JBColor.namedColor("ErrorForeground", JBColor.RED)
    errorCopyButton?.isVisible = true
    applyMetaTruncation()
}

/** P-05：计算当前预览的缓存键（R22 T-01 起：无表名以 SQL 文本规范化指纹为键，恒非 null）。 */

internal fun SqlPreviewPanel.historyCacheKey(): String? {
    val result = current ?: return null
    val tableName = result.tableName
    // 缺陷②修复：优先用「注入前」稳定文本当指纹种子——逻辑删除/多租户等拦截器模拟会改写
    // result.sql，直接拿它算键会让开关一切换就召回 miss（已回填参数退回占位符）。
    val rawSql = result.historyFingerprintSeed ?: result.sql
    // T-PresetMethodScope：追加声明作用域——同一段 SQL 写在两个方法里不再共用预设与参数历史；
    // 无方法上下文（日志/历史双击）保持旧键。
    return paramHistoryCache.computeKey(tableName, rawSql, result.originScopeKey.ifEmpty { null })
}

/**
 * P-05：从历史缓存召回参数值（showResult 后调用）。
 *
 * 命中时按 source 回填（用户已输入值优先，不覆盖）。
 * 召回成功显示淡入提示。
 *
 * R22 T-02/T-03：快照特殊键一并还原——`__chk__:<id>`=1/0 勾选态（1 加 0 移，可复现
 * 「关掉默认勾选」的方案）、`__fnull__:<expr>`=1 foreach null 勾选、`__fev__:<expr>`=
 * 逗号分隔 foreach 权威值（还原权威 map + 同步可见输入框）。还原后 renderSql 收敛。
 */



/**
 * T-PresetMethodBinding：当前预览实际存在的参数来源（回落白名单——只回填这些行）。
 *
 * 为什么不用 `paramValues.keys`：方案切换路径先 [clearParamStateForSchemeSwitch] 清场，
 * 到召回时 paramValues 已空，白名单会把所有参数都挡掉。这里从结果模型取「当前 SQL 有哪些来源」。
 */
internal fun SqlPreviewPanel.currentParamSources(): Set<String> {
    val result = current ?: return emptySet()
    val sources = linkedSetOf<String>()
    sources += result.unresolvedParams
    result.previewModel?.conditionViews.orEmpty().forEach { view ->
        sources += view.valueSources
        view.valueSource?.let(sources::add)
    }
    result.batchModel?.let { model ->
        model.toggleColumns.forEach { sources += it.source }
        model.fixedColumns.forEach { sources += it.source }
    }
    result.xmlModel?.let { model -> model.forEachViews.forEach { sources += it.collectionExpr } }
    return sources
}

internal fun SqlPreviewPanel.recallFromHistory() {
    val key = historyCacheKey() ?: return
    val cached = paramHistoryCache.recall(key)
    // T-PresetMethodBinding：同一方法里改了 SQL（键的 SQL 指纹段变化）时，精确键与旧键都未命中，
    // 按「该方案在该方法下最近一次保存」回落。回落只恢复参数值与 FieldFill——结构态（勾选/foreach）
    // 不参与，见下方分支；且只回填当前面板存在的 source（白名单），防止旧参数名越积越多。
    val recovered = if (cached == null) paramHistoryCache.recallRecent(key) else null
    val history = cached ?: recovered ?: historySnapshotFallback(key) ?: return
    if (history.isEmpty()) return
    // 回落来源只回填当前预览真实存在的参数（不能用 paramValues.keys：方案切换前刚被清场）
    val allowedSources = if (recovered != null) currentParamSources() else emptySet()
    var applied = 0
    var stateRestored = false
    history.forEach { (source, value) ->
        when {
            source.startsWith(FieldFillSimulation.SNAPSHOT_PREFIX) -> {
                // SQL 历史快照无权恢复模拟值；只接受当前代际的参数预设缓存（精确或同方法回落）
                val fieldSource = source.removePrefix(FieldFillSimulation.SNAPSHOT_PREFIX)
                if ((cached != null || recovered != null) && fieldFillPreview === current &&
                    paramHistoryCache.isCurrentGeneration() &&
                    fieldSource in fieldFillFields && fieldFillValues[fieldSource].isNullOrEmpty()) {
                    fieldFillValues[fieldSource] = value
                    fieldFillFields[fieldSource]?.text = value
                    stateRestored = true
                }
            }
            source.startsWith(SNAPSHOT_CHK) -> {
                // T-PresetMethodBinding：跨 SQL 回落不恢复勾选态——条件 id 是按解析序递增的序号，
                // SQL 增删条件后整体移位，套用旧快照会把勾选落到别的条件上（错位比丢失更难排查）。
                if (recovered == null) {
                    val id = source.removePrefix(SNAPSHOT_CHK)
                    if (value == "1") enabledIds.add(id) else enabledIds.remove(id)
                    stateRestored = true
                }
            }
            source.startsWith(SNAPSHOT_FNULL) -> {
                if (recovered == null) {
                    val expr = source.removePrefix(SNAPSHOT_FNULL)
                    if (value == "1") xmlForEachNulls.add(expr) else xmlForEachNulls.remove(expr)
                    stateRestored = true
                }
            }
            source.startsWith(SNAPSHOT_FEV) -> {
                if (recovered == null) {
                    val expr = source.removePrefix(SNAPSHOT_FEV)
                    val values = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (values.isNotEmpty()) {
                        xmlForEachValues[expr] = values
                        // 首次召回尚无字段监听，必须在渲染映射前同步展开长度，不能依赖文本变更补齐。
                        xmlForEachCounts[expr] = values.size
                        xmlForEachValueFields.firstOrNull { it.first == expr }?.second?.text = value
                        stateRestored = true
                    }
                }
            }
            // 用户已输入值优先（不覆盖）；回落来源只认当前预览存在的参数名
            else -> {
                if (recovered != null && source !in allowedSources) return@forEach
                if (paramValues[source].isNullOrEmpty()) {
                    paramValues[source] = value
                    syncParamFieldText(source, value)
                    applied++
                }
            }
        }
    }
    if (stateRestored) syncToggleChecksFromState()
    if (applied > 0 || stateRestored) {
        renderSql()
    }
}

/** R22 T-02：把当前状态集同步到可见勾选框（召回路径——勾选框初始态在渲染时定型，
 *  召回发生在面板已建之后，须显式回写 isSelected）。 */

internal fun SqlPreviewPanel.syncToggleChecksFromState() {
    (conditionCheckBoxes + batchCheckBoxes).forEach { cb ->
        val id = cb.getClientProperty(PROP_TOGGLE_ID) as? String ?: return@forEach
        cb.isSelected = id in enabledIds
    }
    xmlForEachNullBoxes.forEach { cb ->
        val expr = cb.getClientProperty(PROP_FOREACH_NULL) as? String ?: return@forEach
        cb.isSelected = expr in xmlForEachNulls
    }
}

/** R22 T-02：参数历史快照构建——paramValues 之外并入勾选态与 foreach 权威态：
 *  - `__chk__:<id>` = "1"/"0"：条件/批量勾选**全量 1/0 编码**（可见组件逐个枚举 + enabledIds
 *    残留项补 "1"）——只存 "1" 无法复现「关掉默认勾选」的方案；
 *  - `__fnull__:<expr>` = "1"：foreach null 勾选（默认态=未勾，只存勾选侧）；
 *  - `__fev__:<expr>` = 逗号分隔值：foreach 集合权威值（空值不存=默认展开）。 */

internal fun SqlPreviewPanel.buildHistorySnapshot(): Map<String, String> {
    val snapshot = paramValues.toMutableMap()
    if (paramHistoryCache.isCurrentGeneration()) {
        fieldFillValues.forEach { (source, value) ->
            if (value.isNotEmpty()) snapshot[FieldFillSimulation.SNAPSHOT_PREFIX + source] = value
        }
    }
    val seenToggles = mutableSetOf<String>()
    (conditionCheckBoxes + batchCheckBoxes).forEach { cb ->
        val id = cb.getClientProperty(PROP_TOGGLE_ID) as? String ?: return@forEach
        seenToggles += id
        snapshot["$SNAPSHOT_CHK$id"] = if (id in enabledIds) "1" else "0"
    }
    enabledIds.forEach { if (it !in seenToggles) snapshot["$SNAPSHOT_CHK$it"] = "1" }
    xmlForEachNulls.forEach { snapshot["$SNAPSHOT_FNULL$it"] = "1" }
    xmlForEachValues.forEach { (expr, values) ->
        if (values.isNotEmpty()) snapshot["$SNAPSHOT_FEV$expr"] = values.joinToString(",")
    }
    return snapshot
}

/**
 * P-05：保存当前参数值到历史缓存（debounce 500ms）。
 *
 * 每次击键 restart 计时器（取消前次 + 重新调度），避免频繁序列化。
 */

internal fun SqlPreviewPanel.saveToHistory() {
    historyAlarm.restart()
}

/** P-05：Timer 动作——实际写回缓存（由 historyAlarm 调度）。
 * T-Perf-24：序列化/持久化写挪 pooled 线程（EDT 只做取值快照）。
 * R22 T-04 防串桶：取快照（EDT）与写回（pooled 线程）之间用户可能切换方案——
 * 快照时捕获方案名随行落库（saveForScheme），不再依赖写回时刻的激活态。
 *
 * T-DevAcceptFix2 ⑦（20261006 第三轮走查，用户实测复现）：自动保存**恒写默认（工作区）桶**。
 * 原实现按「当前激活预设」归桶——用户流程「输入 zhangsan → 另存为『zhangsan』 → 改参数为 lisi」
 * 中，第 2 步后激活指针指向 zhangsan 预设，第 3 步的编辑经本例 500ms 防抖直接**就地覆写该预设**
 * （schemes["zhangsan"][key] 变成 lisi），于是「切回 zhangsan 预设仍显示 lisi」。
 * 现语义：**命名预设＝快照**，只经「另存为/管理预设」显式写入；参数编辑只更新工作区（默认桶），
 * 切预设时按目标预设快照召回。防串桶目标（异步写回不落到别的桶）由「恒默认」天然满足。
 */

internal fun SqlPreviewPanel.flushHistoryToCache() {
    val key = historyCacheKey() ?: return
    val snapshot = buildHistorySnapshot()
    // T-M19-StorageClear：与快照同刻捕获存储代际。清理动作若发生在 pooled 写回之前，旧代际
    // saveForScheme 会被拒绝，已清参数不会被已打开面板的迟到任务复活。
    val storageGeneration = paramHistoryCache.generationToken()
    // R51（O-105 余半）：参数快照随 flush 链同步回写最新匹配历史条目（recordHistory 早于
    // 参数输入，快照只能在此补进；历史条目因此携带当次预览实际使用的参数）
    updateHistoryEntrySnapshot(key, snapshot)
    // headless 单测：同步执行保持既有测试语义
    if (ApplicationManager.getApplication().isUnitTestMode) {
        paramHistoryCache.saveForScheme(ParamHistoryCache.DEFAULT_SCHEME, key, snapshot, storageGeneration)
        return
    }
    ApplicationManager.getApplication().executeOnPooledThread {
        paramHistoryCache.saveForScheme(ParamHistoryCache.DEFAULT_SCHEME, key, snapshot, storageGeneration)
    }
}

/**
 * 按出现顺序把 SQL 中的 `?` 替换为用户输入（按 source 定位参数值，取代位置绑定，见 M12-R2）。
 * 值渲染按回填提示 [ParamHint]（M13）：LIKE 位置拼 `%`、String 列恒引号、数值列恒原样；
 * 提示缺失/类型未知 → 数字启发式；空值一律保留 `?`（复选框纯手动，不联动勾选态）。
 */
/**
 * 检测 SQL 中 IN 子句的参数索引。
 *
 * 扫描 `IN (?, ?, ...)` 模式，返回 IN 子句内 `?` 的参数索引集合。
 * 面板回填时这些参数按逗号分割多值渲染。
 *
 * T-Perf-04：单趟 O(N) 扫描——旧实现对每个 `?` 复制并大写化整个前缀（O(L×P)）；
 * 现改为就地回扫（跳过空白 → `(` → 可选一个空格 → `IN`，与旧 trimEnd+endsWith 语义一致），
 * 零分配。
 *
 * O-81 T-XmlListParamSplit：`IN` 与 `(` 之间从「可选一个空格」放宽为**任意空白串**——
 * mapper 排版 `IN\n(`（先红读数实证：升级前漏判 → 集合参数整串单值 `'PI,WP'`）不再漏。
 * 多 `?` IN 列表仍仅首位命中（foreach 已有逐项回填通道，扩面会双拆，语义钉住不变）。
 */

internal fun SqlPreviewPanel.detectInClauseParams(sql: String): Set<Int> {
    val result = mutableSetOf<Int>()
    var paramIndex = 0
    for (i in sql.indices) {
        if (sql[i] != '?') continue
        var j = i - 1
        while (j >= 0 && sql[j].isWhitespace()) j--
        if (j >= 0 && sql[j] == '(') {
            j--
            while (j >= 0 && sql[j].isWhitespace()) j--
            if (j >= 1 && (sql[j] == 'N' || sql[j] == 'n') && (sql[j - 1] == 'I' || sql[j - 1] == 'i')) {
                result.add(paramIndex)
            }
        }
        paramIndex++
    }
    return result
}

/**
 * T-PSG-03（N-33）：按 source 回填 `?` 占位符——位点判定走 [placeholderPositions]
 * （string-aware），字符串字面量与 `--` / 块注释内的 `?` 不再被替换（改前朴素逐字符
 * `c == '?'` 会错位：引号内 `?` 吃掉了本属真占位符的参数值）。
 *
 * 值渲染语义不变（[renderParamValue] / [renderSingleValue] 一行未动）；不含这类 `?` 的
 * SQL 回填结果与改前逐字节一致。
 *
 * T-PSG-04：同一趟记下「位点数 vs 参数条数」对账结论（[placeholderReconciliation]），
 * 执行前由 [warnOnPlaceholderMismatch] 如实标注——不阻断。
 */

internal fun SqlPreviewPanel.backfillParamsBySource(
    sql: String,
    sources: List<String>,
    hints: List<ParamHint> = emptyList(),
    simulatedLiterals: Map<Int, String> = emptyMap(),
): String {
    if (sources.isEmpty()) return sql
    val positions = placeholderPositions(sql)
    placeholderReconciliation = PlaceholderCardinality.mismatchMarker(positions.size, sources.size)
    if (positions.isEmpty()) return sql
    val sb = StringBuilder(sql.length + 32)
    var paramIndex = 0
    var prev = 0
    for (pos in positions) {
        sb.append(sql, prev, pos)
        val source = sources.getOrNull(paramIndex)
        val v = source?.let { paramValues[it] }
        sb.append(simulatedLiterals[paramIndex] ?: renderParamValue(v, hints.getOrNull(paramIndex), source))
        paramIndex++
        prev = pos + 1
    }
    sb.append(sql, prev, sql.length)
    return sb.toString()
}


internal fun SqlPreviewPanel.renderParamValue(v: String?, hint: ParamHint?, source: String? = null): String {
    // 未填写（paramValues 未播种的 source，非标准路径）保留 `?` 兜底提示
    if (v == null) return "?"
    // T-PT-04 / O-79：空输入按设置策略出 ?（默认占位档）/ NULL / ''——**仅对带签名类型信息的
    // 参数**生效；无类型信息（UNKNOWN）恒 `?` 占位提示（宁缺勿错）
    if (v.isBlank()) return if (hasSignatureTypeInfo(source)) emptyParamLiteral() else "?"
    val like = hint?.likePosition
    // M19.6（用户实测）：IN 列表多值回填——逗号分割，每个值独立渲染（引号规则同单值），
    // 输入 `1,2` → `('1','2')`（原实现拼成 `'1,2'` 单值）
    if (hint?.inList == true) {
        val parts = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        // 逗号分割后无有效值（如 `,` 形态）：按空输入档处理
        if (parts.isEmpty()) return if (hasSignatureTypeInfo(source)) emptyParamLiteral() else "?"
        return parts.joinToString(", ") { renderSingleValue(it, hint) }
    }
    val escaped = v.replace("'", "''")
    return when {
        // LIKE 模式：恒引号 + 按位置拼 %（值本身转义）
        like != null -> when (like) {
            LikePosition.ANYWHERE -> "'%$escaped%'"
            LikePosition.LEFT -> "'%$escaped'"
            LikePosition.RIGHT -> "'$escaped%'"
            LikePosition.NONE -> "'$escaped'"
        }
        else -> renderSingleValue(v, hint)
    }
}

/**
 * T-PT-04 / O-79：空参数填充字面量——按设置「空参数填充策略」出 `?`（默认占位档，保留待填
 * 提示，SQL 不臆造语义）/ `NULL` / `''`。作用于 [renderParamValue] 的空值分支，且仅当参数带
 * 签名类型信息（[hasSignatureTypeInfo]）；未知策略值按占位档兜底。
 */

internal fun SqlPreviewPanel.emptyParamLiteral(): String = when (PluginSettings.getInstance().state.emptyParamFill) {
    PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING -> "''"
    PluginSettings.EMPTY_PARAM_FILL_NULL -> "NULL"
    else -> "?"
}

/** T-PT-04：参数是否带签名类型信息（XML/注解 mapper 参数行）——决定空输入是否走填充策略。 */

internal fun SqlPreviewPanel.hasSignatureTypeInfo(source: String?): Boolean =
    source != null && paramKindOf(source) != ParamKind.UNKNOWN

/** 单值渲染（M19.6 抽取，IN 列表各值与普通参数共用）：String 列恒引号、数值恒原样、未知走数字启发式。 */

internal fun SqlPreviewPanel.renderSingleValue(raw: String, hint: ParamHint?): String {
    val escaped = raw.replace("'", "''")
    return when {
        hint?.quote == true -> "'$escaped'"
        hint?.quote == false -> raw.trim()
        else -> {
            val trimmed = raw.trim()
            if (NUMBER_REGEX.matches(trimmed)) trimmed else "'$escaped'"
        }
    }
}

// ------------------------------------------------------------------
// 元信息
// ------------------------------------------------------------------
