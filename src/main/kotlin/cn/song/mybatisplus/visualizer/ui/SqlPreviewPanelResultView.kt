package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanel
import cn.song.mybatisplus.visualizer.ui.diagram.DiagramModel
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramSupport
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import cn.song.mybatisplus.visualizer.xml.XmlPreviewState
import cn.song.mybatisplus.visualizer.xml.PagesColumn
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FontMetrics
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.COLUMN_INFO_TAB_TITLE
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.MIN_META_BUDGET_PX
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.dollarMarkDismissedSession
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.DOLLAR_TOKEN
// T-M53-II：扩展函数看不见接收者类的**嵌套类型**（与 companion 同规则），须显式导入。
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.MetaPerfSegment

// T-M53-II（2026-10-10）：以下为 SqlPreviewPanel 的「结果区/图表与列信息 Tab 装配、工具栏与元信息渲染」内聚成员，
// 以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。

internal fun SqlPreviewPanel.toolbar(): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>().apply {
    layout = BoxLayout(this, BoxLayout.X_AXIS)
    errorCopyButton = smallButton(MpSqlVisualizerBundle.message("panel.copyError")) {
        if (copyError()) {
            // T-ExecCopyFix：复制成功反馈（对齐 SqlClickPreview.flashCopied 对号语义）
            errorCopyButton?.let { cn.song.mybatisplus.visualizer.ui.flashCopied(it) }
        }
    }.apply { isVisible = false }
    add(errorCopyButton)
    modeButton = smallButton(modeButtonText()) {
        toggleCompact()
        modeButton.text = modeButtonText()
    }
    add(modeButton)
    add(smallButton(MpSqlVisualizerBundle.message("panel.format")) { formatSql() })
    // M-KeywordWrap：关键字换行（子句顶格换行、同行内容压一行）
    add(smallButton(MpSqlVisualizerBundle.message("panel.keywordWrap")) { formatClauseLine() })
    // T-VStack：竖向排列（INSERT 列清单/VALUES 逐行竖排 + 值行尾列名配对标注）
    add(smallButton(MpSqlVisualizerBundle.message("panel.vertical")) { formatVertical() })
    // M37.2：EXPLAIN（真实执行计划，静态规则标注同屏对照）。
    // T-MergeFollowup-01：master T-PreviewTopOverlap 重构按钮行时把 dev 的 add 行删掉而注释残留，
    // explainSql() 全树零调用（合并语义丢失无冲突标记实锤）——补回，保持内联高频位（对齐 M37M38 文档 §2.3）。
    add(smallButton(MpSqlVisualizerBundle.message("panel.explain")) { explainSql() })
    // T-UiCoord F1（2026-09-21 用户要求）：回滚 SQL 折叠进「更多」——低频动作（只对
    // INSERT/UPDATE/DELETE 有意义）不再与格式化/刷新同列直显。**推翻** T-RollbackSql F3 与
    // T-MergeR2IntoMaster §3.2 的「一键直达 / 执行计划之后刷新之前」定案，改判依据见
    // docs/开发计划/20260921-1338-T-UiCoord-…-开发计划.md §三 F1；动作体 showRollbackSql() 一字未改。
    add(smallButton(MpSqlVisualizerBundle.message("panel.refresh")) { refreshView() })
    // T-PreviewTopOverlap F2：「更多」折叠菜单入口（低频动作收纳）
    add(smallButton(MpSqlVisualizerBundle.message("panel.more")) { showMoreActionsPopup() })
}

/**
 * 「更多」折叠菜单的四项低频动作（文案键沿用原工具栏按钮，执行体一字不差）。
 *
 * T-UiCoord F1：回滚 SQL 置于**首位**——四项里它最常用，而折叠会降低可发现性，
 * 首项是成本最低的补偿（对齐「菜单按使用频率排序」）。
 */

internal fun SqlPreviewPanel.moreActions(): List<Pair<String, () -> Unit>> = listOf(
    MpSqlVisualizerBundle.message("panel.rollbackSql") to { showRollbackSql() },
    MpSqlVisualizerBundle.message("panel.param.import") to { importParamsFromClipboard() },
    MpSqlVisualizerBundle.message("panel.compare") to { showSqlDiff() },
    MpSqlVisualizerBundle.message("panel.share") to { showShareDialog() },
    MpSqlVisualizerBundle.message("panel.portableExport") to { exportPortableSql() },
        // R36：回放脚本导出（参数已填回的可执行 .sql；沿用 O-91 同款入口与写盘/通知链）
        MpSqlVisualizerBundle.message("panel.replayExport") to { exportReplayScript() },
        // R37（T-LaneB D95）：安全 AI 上下文交递——脱敏参数化块进剪贴板（未接 MCP 用户的安全出口）
        MpSqlVisualizerBundle.message("panel.aiContext") to { copyAsAiContext() },
        // T-AiContextLegibility B：显式含表列名出口（值仍打码，引号内容不复制）
        MpSqlVisualizerBundle.message("panel.aiContext.identifiers") to { copyAsAiContext(includeIdentifiers = true) },
        // R42（阶段二合并批）：DML 影响面预演——UPDATE/DELETE 执行前的只读 COUNT 试算
        MpSqlVisualizerBundle.message("panel.dmlImpact") to { estimateDmlImpact() },
)

/**
 * F2：弹出「更多」折叠菜单（锚定按钮下方；JBPopupFactory + BaseListPopupStep 路线为本项目
 * 现网先例：SqlClickPreview / DataSourceSelector）。show 依赖真实屏幕，测试只构造 step 不弹层。
 */

    /**
     * R52：当前方言能力矩阵的 meta 悬停段（呈现层触点一）。
     *
     * 悬停而非可见行：meta 可见行受 F3 宽度预算中段省略（[applyMetaTruncation]），把四行矩阵
     * 塞进可见行会挤掉表名/调用栈摘要——矩阵信息密度高、按需查看即可，故落 tooltip（同仓
     * 惯例：完整信息进悬停、可见行留摘要）。方言取预览结果的实际生成方言，
     * 未记录方言时返回空串（不加段落）。
     */
    private fun dialectCapabilityTooltipSection(result: PreviewResult): String {
        val dialect = result.effectiveDialect ?: return ""
        return "\n\n" + MpSqlVisualizerBundle.message(
            "panel.meta.dialectCapabilities",
            dialect.name,
            cn.song.mybatisplus.visualizer.sql.DialectCapabilities.summaryText(dialect),
        )
    }

internal fun SqlPreviewPanel.showMoreActionsPopup() {
    val actions = moreActions()
    val anchor = toolbarPanel.components.filterIsInstance<JButton>().lastOrNull() ?: return
    com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
        .createListPopup(moreActionsStep(actions))
        .show(com.intellij.ui.awt.RelativePoint(anchor, java.awt.Point(0, anchor.height)))
}

/** F2：构造折叠菜单 step（独立可测——items 与 onChosen 委托可直接断言，无需真实弹层）。 */

internal fun SqlPreviewPanel.moreActionsStep(actions: List<Pair<String, () -> Unit>>): com.intellij.openapi.ui.popup.util.BaseListPopupStep<String> =
    object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<String>(null, actions.map { it.first }) {
        override fun onChosen(
            selectedValue: String?,
            finalChoice: Boolean,
        ): com.intellij.openapi.ui.popup.PopupStep<*>? {
            // N4 修复（2026-09-21）：动作体**一律延后到弹层销毁之后**执行。
            // 三项动作（导入参数 / 差异对比 / 分享）都会弹**模态对话框**，此前在 onChosen 内同步弹，
            // 平台判为焦点风险并记 SEVERE——原文即修法：「Showing dialogs in PopupStep.onChosen can
            // result in focus issues. Please put the dialog showing code into invokeLater…」
            // （`#c.i.u.p.u.PopupImplUtil`，栈指向本方法 :891 → importParamsFromClipboard:3482）。
            // **真机后果比日志噪音严重**：第一次点「确定」被弹层收走，用户感受是「点了没反应」。
            // 本方法返回 FINAL_CHOICE 后弹层即销毁，故 invokeLater 的体在弹层已销毁时才跑。
            val action = actions.firstOrNull { it.first == selectedValue }?.second
            if (action != null) {
                ApplicationManager.getApplication().invokeLater { action() }
            }
            return com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE
        }
    }

/**
 * T-RollbackSql：回滚 SQL 视图入口（面板【回滚SQL】按钮）。
 *
 * 生成结果分层（计划 §2.2）：Complete/Partial 打开 [RollbackSqlDialog]；可解析的
 * UPDATE/DELETE（Unable 但有快照工作流）同样进视图——人工恢复指引 + 粘贴快照生成回滚；
 * 完全不可推导（解析失败/MERGE/DDL/SELECT）不弹视图，仅消息框说明原因与人工恢复路径。
 */

internal fun SqlPreviewPanel.showResult(result: PreviewResult) {
    current = result
    // O-145 W7：在结果/渲染时冻结 AUTO/MANUAL，执行点击时只读取此快照；设置随后变化不改写历史事实。
    executionDialectRenderMode = project?.let {
        if (cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.getInstance(it).effectiveDialectAuto()) {
            cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.RenderMode.AUTO
        } else {
            cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.RenderMode.MANUAL
        }
    } ?: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.RenderMode.UNKNOWN
    // T-M19-StorageClear：新预览会从结果模型重新初始化全部参数态，此刻才允许旧面板接纳清理后的
    // 新存储代际；停留在原预览的面板始终保持旧代际，其防抖/管理方案写回全部被拒。
    paramHistoryCache.adoptCurrentGenerationForFreshPreview()
    // O-145 W5：渲染方言与临时 PSI 使用同一事实；必须先于 renderSql/createEditor。
    syncPreviewDialectMapping(result)
    // 参数值按 source 初始化（T-M42：优先取预填初始值——日志识别 `param1` → 日志参数值；
    // 其余空字符串；多条件复用同一 source 共享一个值）
    paramValues.clear()
    result.unresolvedParams.forEach { source -> paramValues.putIfAbsent(source, result.initialParamValues[source] ?: "") }
    result.previewModel?.conditionViews.orEmpty().forEach { view -> view.valueSource?.let { paramValues.putIfAbsent(it, "") } }
    // 动态条件启用集合初始化（M13：复选框纯手动）——仅按 defaultEnabled，值空不联动勾选态
    enabledIds.clear()
    result.previewModel?.conditionViews.orEmpty().forEach { view ->
        if (view.defaultEnabled) enabledIds.add(view.id)
    }
    // T-M23-04：批量列级勾选初始化——按 BatchModel 默认勾选态（列名作 id，与条件 toggle 互斥）
    result.batchModel?.toggleColumns?.forEach { col ->
        if (col.checked) enabledIds.add(col.columnName)
    }
    // T-M24-04：XML 预览状态初始化（条件开关走 enabledIds，按启发式默认勾选；foreach/bind 独立状态）
    result.xmlModel?.let { model ->
        model.conditionViews.forEach { view ->
            if (view.defaultEnabled) enabledIds.add(view.id)
        }
        xmlForEachCounts.clear()
        xmlForEachCounts.putAll(model.defaultState().forEachCounts)
        xmlForEachValues.clear()
        xmlForEachNulls.clear()
        xmlErrors = emptyList()
        xmlDollarSubstitutions = 0
        // T-M25.5：预渲染提取 bind 自动求值结果（参数行初始显示求值后的值，用户可覆盖）
        val initial = model.renderSql(XmlPreviewState(
            enabledIds = enabledIds.toSet(),
            forEachCounts = xmlForEachCounts.toMap(),
        ))
        initial.bindValues.forEach { (name, value) ->
            if (paramValues[name].isNullOrEmpty()) paramValues[name] = value
        }
    }
    // P-05：历史召回（在 initialParamValues 之后，用户输入之前）
    recallFromHistory()
    // 模型级重生成路径：初始压缩/多行与格式化设置对齐（旧文本路径保持默认多行）
    result.previewModel?.let { compactMode = !it.formatSql }
    modeButton.text = modeButtonText()
    rebuildLeftPanel(result)
    refreshCompletionContext()
    // T-DbSchemaCompletion：后台预热本文档涉及表的数据源 schema，完成后再刷一次上下文
    //（数据源列进补全候选；不依赖 JOIN 图，落 T-PreviewExecDs 遗留的 P1-5）
    prefetchSchemaForCompletion(result)
    renderSql()
    updateMeta(result)
    // F-1：更新 JOIN 图标签页（仅 SELECT 含 JOIN 时显示）
    updateDiagramTab(result)
    // F-45：更新列信息标签页（仅 <pages> 预览时显示）
    updateColumnInfoTab(result)
    // O-4：缓存当前 SQL 供下次 Compare
    // UI-023：首渲染链上 editor 可能尚未创建（控制台/预热抢占），只读 editor 会恒 null——
    // 「差异对比」于是永远报「无上次预览可对比」。editor 缺失时用本次预览原文兜底（同一语义：
    // 缓存本次 SQL 供下次对比）。
    previousSql = editor?.document?.text ?: result.sql.takeIf { it.isNotBlank() }
    // M36.5：默认发起原生控制台准备（Ultimate 即刻展示原生控制台；Community 静默回退）
    prepareDefaultConsole()
}

/**
 * F-1：根据当前预览结果更新 JOIN 图标签页。
 *
 * 仅当 previewModel.statement 为 [SelectStatement] 且含非空 joins 时显示"JOIN 图"标签页；
 * 否则移除标签页（如有）。PlantUML 文本由 [JoinDiagramGenerator] 生成，渲染由 [DiagramPanel] 后台完成。
 */

internal fun SqlPreviewPanel.updateDiagramTab(result: PreviewResult) {
    // F-1 修复：previewModel 仅在「有动态条件」时才构造（无条件联表链为 null），故图判据先看它、
    // 缺失时回落到独立的 diagramStatement 载体——否则无条件的 JOIN 链拿不到「JOIN 图」标签页。
    val statement = result.previewModel?.statement ?: result.diagramStatement
    val hasJoins = statement is SelectStatement && statement.joins.isNotEmpty()
    if (hasJoins) {
        // 确保内容区已切换为标签页模式
        ensureTabbedContent()
        // T-Perf-09：schema 富化（数据源全量遍历）移后台预取——数千表冷缓存时 EDT 直跑冻结秒级；
        // 富化完成回 EDT 再展示。当前 PreviewResult 被替换（新预览）时作废迟到的应用。
        // T-Embed-02：数据源可用时把列类型/注释升级为 DB 真实值（int(11)/DB COMMENT），缺席静默回落
        val stmt = statement as SelectStatement
        val proj = project
        // T-F8：加载态包住「后台富化 + 回 EDT 换页」整段（showJoinDiagram 本体与 locateDbTables
        // 都在 EDT 同步跑，同一 EDT 事件内 startLoading 画不出来，故起止点必须跨事件）。
        // 这里只「进」；「出」的主收口点是 showJoinDiagram 末尾（正常路径两条分支都过它）。
        // T-TS-FixA：主收口点只在 showJoinDiagram **被进入**时生效——建模型抛在调用之前时
        // 它不会执行，故两条分支各自的 catch 内还必须显式摘圈（异常路径的收口点）。
        diagramPanel.showLoading()
        if (proj == null) {
            // T-TS-05：buildDiagramModel 移入 try——抛在 showJoinDiagram 调用**之前**时，
            // showJoinDiagram 的 finally 根本没机会执行，转圈会留在宿主层。
            try {
                showJoinDiagram(JoinDiagramGenerator.buildDiagramModel(stmt))
            } catch (t: Throwable) {
                // T-TS-FixA：本 catch 是这条分支的**唯一**收尾方——buildDiagramModel 是
                // showJoinDiagram 的**实参**，实参求值期抛出时方法根本没进入，其 finally
                // （唯一收口点）不会执行；此处不摘圈，上面 showLoading() 打开的转圈就永留宿主层，
                // 直到下一次预览或整图卸载（对照后台富化分支：那边同样在异常时显式摘圈，
                // 两条分支对同一异常的处理必须对称）。
                // 线程：updateDiagramTab 由 showResult 在 EDT 上同步调用，本 catch 亦在 EDT，
                // 直接摘即可——invokeLater 归位只服务于跨线程的后台富化分支。
                diagramPanel.hideLoading()
                com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                    .warn("[T-F8] join diagram build failed (no project), loading cleared here", t)
            }
            return
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            // T-TS-05：建模型同样在 try 内——本拍一旦抛，pooled 线程终止、下面的 invokeLater
            // 永不投递，转圈就永远留在宿主层（收尾方只剩 showJoinDiagram，而它不会被调用）。
            val base = try {
                JoinDiagramGenerator.buildDiagramModel(stmt)
            } catch (t: Throwable) {
                com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                    .warn("[T-F8] join diagram model build failed (background)", t)
                // 回 EDT 摘转圈：作废判定同下面那拍——转圈属于「当前加载」，不能摘后一次预览的
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    if (current === result && project?.isDisposed != true) diagramPanel.hideLoading()
                }
                return@executeOnPooledThread
            }
            val enriched = try {
                cn.song.mybatisplus.visualizer.ui.diagram.DiagramSchemaEnricher.enrich(proj, base)
            } catch (t: Throwable) {
                com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                    .warn("[T-Perf-09] diagram schema enrich failed, showing un-enriched model", t)
                base
            }
            // T-DbSchemaCompletion：本次富化是否一点数据源信息都没拿到（结构相等 = 没改动）。
            // 命中就说明模型未加载（预览打开常早于数据源建连）——记下来供数据源就绪后的补刷。
            val contributed = enriched != base
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                // T-F8：作废分支**不得**摘转圈——迟到的作废回调若停加载态，会把后一次预览
                // 刚打开的转圈一起摘掉（转圈是「当前加载」的状态，不是本次回调的状态）。
                // 此时加载态的归属方是后一次预览：它要么自己收尾（showJoinDiagram 末尾），
                // 要么走无 JOIN 分支（unloadJoinDiagram → diagramPanel.clear() 复位）。
                if (current !== result || project?.isDisposed == true) return@invokeLater
                if (!contributed) diagramEnrichRetryTarget = result
                // T-TS-05：建图异常不逃逸到平台事件泵——逃逸只会让平台记一条无上下文的错，
                // 而转圈已由 showJoinDiagram 的 finally 摘下；这里补一条带上下文的 warn 便于定位。
                try {
                    showJoinDiagram(enriched)
                } catch (t: Throwable) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                        .warn("[T-F8] join diagram build failed (post-enrich), loading cleared by finally", t)
                }
            }
        }
    } else {
        // 无 JOIN：整图卸载（移除 diagram 标签页 + 释放原生图 + 回收虚拟外键登记）
        removeDiagramTab()
        unloadJoinDiagram()
    }
}

/**
 * T-Dgm-04：按设置与 Diagrams 插件可用性渲染 JOIN 图。
 *
 * 原生路径：快照登记 → UmlEditorProvider.createEditor → 组件嵌入 [diagramTabHost]
 * （缩放/布局/折叠/导出全框架托管）；Diagrams 插件缺席或框架异常（buildTabComponent 返回 null）
 * 自动降级自绘 [DiagramPanel]，与 Community 行为一致。
 *
 * T-F8：本方法末尾是加载态的**主收口点**——两条调用分支（[updateDiagramTab] 的无 project
 * 同步分支与后台富化回 EDT 分支）正常路径都经此，故「摘转圈」写在这里而不是各调用点：结构上
 * 保证转圈一定晚于渲染被摘（原生图建图/自绘测量都在本方法内同步完成），调用方也不会漏收尾。
 * T-TS-FixA：主收口点只覆盖「进得来」的情形——建模型抛在**调用之前**（实参求值期）时本方法
 * 不会被进入，故两条分支的 catch 内各自还有一层异常收口（见 [updateDiagramTab]）。
 *
 * T-TS-05：收尾改成 `try { … } finally`——原生框架建图（`buildTabComponent` 反射链/文件编辑器
 * 创建）与自绘测量都可能抛，原先的「顺序执行 + 末尾摘转圈」在异常逃逸时直接跳过摘转圈那行，
 * 用户看到的是转圈永远留在宿主层（且异常还会顺着 EDT 事件泵往上冒）。finally 保证「进过加载态
 * 就一定出」，异常照旧向上抛给调用方的带日志兜底。
 */

internal fun SqlPreviewPanel.showJoinDiagram(model: DiagramModel) {
    try {
        // T-TS-05 测试缝：注入一个会抛的 render 复现建图异常路径（正常为 null）。
        // 为什么需要缝：结构合法的模型在测试沙盒里几乎不抛（同 DiagramPanel.renderGuarded 的
        // 单独成函数理由），异常兜底路径否则只能靠人工改线上代码才验得出来。
        val injectedRender = diagramRenderForTest
        if (injectedRender != null) {
            injectedRender(model)
            return
        }
        val nativeEnabled = PluginSettings.getInstance().state.joinDiagramNative
        // T-EmbedFkOrder F1：**必须先清旧图、再建新图**——旧图 dispose 会清空 JOIN 虚拟外键注册表
        //（JoinFkRegistry），顺序颠倒会把新图刚登记的 ON 条件整批抹掉：Database 原生图于是只剩节点
        // 没有连线（真机实证：同一面板里"一张图有连线、另一张没有"；含子查询的走 wrapper 路径不读
        // 注册表，故不受影响）。清理集中在此处（建图之前），登记之后不再有人清它。
        clearNativeDiagram()
        val native = if (nativeEnabled) {
            // T-Embed-03 降级链：Database 原生图（真实 DbTable + 虚拟外键 JOIN 线）优先，
            // 数据源缺席/表未定位/框架异常回落 A 路径自实现 Provider，再回落自绘渲染器
            (project?.let {
                cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport
                    .buildTabComponent(it, model, MpSqlVisualizerBundle.message("diagram.tab.title"), this)
            })
                ?: NativeJoinDiagramSupport.buildTabComponent(project, model, MpSqlVisualizerBundle.message("diagram.tab.title"), this)
        } else {
            null
        }
        diagramTabHost.removeAll()
        if (native != null) {
            currentNativeDiagram = native
            diagramTabHost.add(native, java.awt.BorderLayout.CENTER)
        } else {
            diagramTabHost.add(diagramPanel, java.awt.BorderLayout.CENTER)
            diagramPanel.showDiagram(model)
        }
        diagramTabHost.revalidate()
        diagramTabHost.repaint()
    } finally {
        // T-F8：图已换入 → 退出加载态（渲染失败时 showDiagram 内部已落错误页并复位，此处幂等）。
        // T-TS-05：异常路径同样必须走到这里——否则转圈永留宿主层。
        diagramPanel.hideLoading()
    }
}

/**
 * T-TS-05 异常兜底测试缝：非 null 时 [showJoinDiagram] 的换图动作改走它（注入会抛的 render）。
 *
 * 只在测试里赋值；生产恒 null（多一次空判，无语义影响）。
 */

internal fun SqlPreviewPanel.clearNativeDiagram() {
    currentNativeDiagram?.let {
        // 两条原生路径的 clientProperty key 不同，各 dispose 一次（未命中为 no-op）
        NativeJoinDiagramSupport.disposeNativeEditor(it)
        cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport.disposeNativeEditor(it)
        diagramTabHost.remove(it)
    }
    currentNativeDiagram = null
}

/** T-EmbedFkOrder F2：整图卸载（无 JOIN/面板清空）——释放原生图并回收虚拟外键登记。 */

internal fun SqlPreviewPanel.unloadJoinDiagram() {
    clearNativeDiagram()
    diagramPanel.clear()
    cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.NativeDatabaseDiagramSupport.clearRegisteredFk()
}

/** 确保内容区为标签页模式（含 SQL + 可选的 JOIN 图）。 */

internal fun SqlPreviewPanel.ensureTabbedContent(addDiagramTab: Boolean = true) {
    val tabPane = contentTabPane
    if (tabPane != null) return // 已切换
    // 从当前布局中取出 split（SQL 视图）
    val center = (layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
    if (center != null) remove(center)
    // 构建标签页
    val pane = JTabbedPane().apply {
        addTab(MpSqlVisualizerBundle.message("panel.tab.sql"), center)
        if (addDiagramTab) {
            // T-F8：标签页内容是加载面板（内含 diagramTabHost）——转圈盖住整页，
            // 原生/自绘两条路径共用同一个加载面
            addTab(MpSqlVisualizerBundle.message("diagram.tab.title"), diagramLoadingHost)
        }
    }
    // F-42 修复：渲染完成时组件可能处于 hidden（标签页未被选中），swapContent/revalidate
    // 对 hidden 组件无效——切到"JOIN 图"标签页时强制刷新，使图像真正显示
    if (addDiagramTab) {
        pane.addChangeListener {
            if (pane.selectedIndex == 1 && currentNativeDiagram == null) {
                diagramPanel.refreshAfterVisible()
            }
        }
    }
    add(pane, BorderLayout.CENTER)
    contentTabPane = pane
    revalidate()
    repaint()
}

/** 移除 JOIN 图标签页（恢复纯 SQL 视图）。 */

internal fun SqlPreviewPanel.removeDiagramTab() {
    val tabPane = contentTabPane ?: return
    val sqlContent = tabPane.getComponentAt(0)
    remove(tabPane)
    add(sqlContent, BorderLayout.CENTER)
    contentTabPane = null
    revalidate()
    repaint()
}

// ------------------------------------------------------------------
// F-45：maintainPage 列信息标签页
// ------------------------------------------------------------------

/**
 * F-45：根据当前预览结果更新列信息标签页。
 * 仅当 result.pagesColumns 非 null 时显示"列信息"标签页；否则移除标签页（如有）。
 */

internal fun SqlPreviewPanel.updateColumnInfoTab(result: PreviewResult) {
    if (result.pagesColumns != null) {
        ensureTabbedContent(addDiagramTab = false)
        val tabPane = contentTabPane ?: return
        // 已添加则不重复添加
        if (!hasTabTitle(tabPane, COLUMN_INFO_TAB_TITLE)) {
            val panel = buildColumnInfoPanel(result.pagesColumns)
            tabPane.addTab(COLUMN_INFO_TAB_TITLE, panel)
        }
    } else {
        removeColumnInfoTab()
    }
}

/** 构建列信息面板（列标识/标题/类型/可见性 表格）。 */

internal fun SqlPreviewPanel.buildColumnInfoPanel(columns: List<PagesColumn>): JComponent {
    val header = arrayOf(
        MpSqlVisualizerBundle.message("pages.columns.id"),
        MpSqlVisualizerBundle.message("pages.columns.title"),
        MpSqlVisualizerBundle.message("pages.columns.dataType"),
        MpSqlVisualizerBundle.message("pages.columns.visible"),
    )
    val data = columns.map {
        arrayOf(it.id, it.title, it.dataType, if (it.visible) "✅" else "❌")
    }.toTypedArray()
    val table = com.intellij.ui.table.JBTable(object : javax.swing.table.DefaultTableModel(data, header) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }).apply {
        autoResizeMode = com.intellij.ui.table.JBTable.AUTO_RESIZE_ALL_COLUMNS
        tableHeader.reorderingAllowed = false
    }
    // O-85：列信息表恢复 Ctrl+A 全选（IDEA 全局 Ctrl+A 被「选中文本」语义抢占、
    // JBTable 默认 selectAll 绑定在此场景不生效——显式绑 InputMap/ActionMap）。
    val selectAllKey = javax.swing.KeyStroke.getKeyStroke("control pressed A")
    table.setInputMap(javax.swing.JComponent.WHEN_FOCUSED, javax.swing.InputMap().apply {
        put(selectAllKey, "mpsv.selectAll")
    })
    table.actionMap.put("mpsv.selectAll", object : javax.swing.AbstractAction() {
        override fun actionPerformed(e: java.awt.event.ActionEvent) {
            table.selectAll()
        }
    })
    return JBScrollPane(table)
}

/** 移除列信息标签页。 */

internal fun SqlPreviewPanel.removeColumnInfoTab() {
    val tabPane = contentTabPane ?: return
    // 从末尾开始找列信息标签页（可能在 JOIN 图之后）
    for (i in tabPane.tabCount - 1 downTo 0) {
        if (tabPane.getTitleAt(i) == COLUMN_INFO_TAB_TITLE) {
            tabPane.removeTabAt(i)
            break
        }
    }
    // 只剩 SQL 标签页时恢复纯 SQL 视图
    if (tabPane.tabCount == 1) {
        val sqlContent = tabPane.getComponentAt(0)
        remove(tabPane)
        add(sqlContent, BorderLayout.CENTER)
        contentTabPane = null
        revalidate()
        repaint()
    }
}

/** 检查标签页是否已存在指定标题。 */

internal fun SqlPreviewPanel.hasTabTitle(tabPane: JTabbedPane, title: String): Boolean {
    for (i in 0 until tabPane.tabCount) {
        if (tabPane.getTitleAt(i) == title) return true
    }
    return false
}

/** 刷新补全上下文（表名/列名，可能随新预览变化）——M16.2 双载体：Editor userData 优先 + PSI 兜底。
 *  M34 回归修复：原生 SQL 高亮迁移后 Editor/PSI 对象在预览切换间复用（文档不变），
 *  三级回退的 Document→Context 映射需随上下文刷新同步更新，否则落入旧预览的过期表/列。 */

internal fun mergePreviewWarnings(initial: String?, current: List<String>): String? =
    (listOfNotNull(initial?.takeIf { it.isNotBlank() }) + current.filter { it.isNotBlank() })
        .distinct().joinToString("；").ifEmpty { null }

internal fun SqlPreviewPanel.updateMeta(result: PreviewResult) {
    val meta = buildList {
        result.wrapperType?.let { add("${MpSqlVisualizerBundle.message("panel.meta.type")}: ${it.name}") }
        result.tableName?.let { add("${MpSqlVisualizerBundle.message("panel.meta.table")}: $it") }
        // T-M24-04：XML 语句元信息（类型 + parameterType）
        result.xmlModel?.let { xml ->
            add("XML ${xml.kind.name.lowercase()}: ${xml.statementId}")
            xml.parameterType?.let { add("parameterType: $it") }
        }
    }.joinToString("   ")
    currentWarning = mergePreviewWarnings(result.warning, if (result.xmlModel != null) xmlErrors else emptyList())
    val warningLine = currentWarning?.let { "   ⚠ $it" } ?: ""
    // T-SourceJumpFunnel P2-2：调用栈快照并入 meta 提示行（截断展示；T-StackHoverUi 起
    // 完整链归悬停弹层）。
    // 文案组装抽为纯函数 [buildStackDigestInlineLine]（同文件顶层）——§3.3 验收项
    // 单测直断截断长度/截断标记，无需真机目视。
    val stackInline = buildStackDigestInlineLine(result.stackDigest)
    // T-SqlPerf：性能建议文案单独一行（HTML 换行）。
    // T-PreviewTopOverlap F3：原文分段缓存，label 文本按宽度预算重拼（applyMetaTruncation）
    // T-SourceJumpFunnel P5 修复：调用栈摘要并入**可见行**——验收项前半句「meta 行显示调用栈」
    // 在可见行上成立。该行受 F3 宽度预算约束，故按**主体 / 摘要**两段缓存（见 metaLineHead /
    // metaLineDigest）：预算不足时先截摘要、主体仅在自身超预算时才中段省略（复核 20260920 修正
    // ——把摘要直接拼进单段文本会让 F3 的中段省略在窄窗下连主体一起挤掉，只剩「…」+ 栈底帧，
    // 优先级倒挂）。
    // T-DialectStatusLiveRefresh：方言决策与其余分段分开缓存——执行/绑定后只重组方言段
    // （recomposeDialectStatusText），不重跑本函数的警示/横幅/错误按钮全链。
    currentDialectDecision = result.dialectDecision
    metaCoreText = meta + warningLine
    metaLineDigest = stackInline
    metaPerfSegments = buildPerfSegments(result)
    metaTooltipCore = metaCoreText + metaPerfSegments.joinToString("；") { it.text }
    metaTooltipSuffix = dialectCapabilityTooltipSection(result)
    applyDialectStatusSegment()
    // T-StackBizFrame S3：线程名随快照一起喂弹层（标题展示；null 时标题退回纯「调用栈」）
    stackHover.updateDigest(result.stackDigest, result.threadName)
    metaLabel.foreground = if (currentWarning != null) {
        // T-DarkTheme：IDE 标准错误前景命名色（亮暗主题自适应；暗色下非亮红不刺眼）
        JBColor.namedColor("ErrorForeground", JBColor.RED)
    } else {
        JBUI.CurrentTheme.Label.foreground()
    }
    // T-M25.3：错误复制按钮随警示可见
    errorCopyButton?.isVisible = currentWarning != null
    // T-PreviewUx-Pack #2：渲染警示横幅（三要素：哪段没还原=渲染产物，为什么=warning 原文，
    // 能做什么=指引）；警示存在即显示，恢复即清除（降级链横幅 DEGRADED 不受本清除影响）
    if (currentWarning != null) {
        degradationBanner.show(
            DegradationBanner.Kind.RENDER_WARNING,
            MpSqlVisualizerBundle.message("panel.banner.renderFailed", currentWarning ?: ""),
        )
    } else {
        degradationBanner.clear(DegradationBanner.Kind.RENDER_WARNING)
    }
    // T-DollarInjectMark：`${}` 直替信息提示（最低优先——警示/降级挂起时让位，
    // 清除后下次 updateMeta 回位；✕ 关闭后会话级记忆不再弹）。只加元数据不改渲染文本。
    if (currentWarning == null && result.xmlModel != null && xmlDollarSubstitutions > 0 &&
        !dollarMarkDismissedSession
    ) {
        degradationBanner.show(
            DegradationBanner.Kind.DOLLAR_INJECT,
            MpSqlVisualizerBundle.message("panel.banner.dollarInject", DOLLAR_TOKEN, xmlDollarSubstitutions),
        )
    } else {
        degradationBanner.clear(DegradationBanner.Kind.DOLLAR_INJECT)
    }
    // F3：复制错误按钮显隐改变工具栏理想宽，预算随显隐重算（lastMetaBudget 复位后应用）
    lastMetaBudget = -1
    applyMetaTruncation()
}

/**
 * T-PreviewTopOverlap F3：元信息宽度预算截断。
 *
 * 预算 = topRow 宽 − 工具栏理想宽 − topRow 边距 − metaLabel 左右边距；未布局（width<=0）跳过，
 * 等组件首显/拉伸时由 ComponentListener 触发。超预算时 meta 行中段省略（复用 T-M26.3 ellipsize），
 * perf 行丢尾段加省略号（HTML 标签外截断）；tooltip 恒全文。宽度未变短路防重算抖动。
 */

internal fun SqlPreviewPanel.applyMetaTruncation() {
    if (metaLineText.isEmpty() && metaPerfSegments.isEmpty()) return
    val width = topRow.width
    if (width <= 0) {
        // 未布局（showResult 先于首帧布局 / headless 测试）：先展示全文，布局后由 ComponentListener 按预算重截
        metaLabel.text = fullDisplayText()
        return
    }
    val budget = (width - toolbarPanel.preferredSize.width - topRow.insets.let { it.left + it.right } - 16)
        .coerceAtLeast(MIN_META_BUDGET_PX)
    if (budget == lastMetaBudget) return
    lastMetaBudget = budget
    val metrics = metaLabel.getFontMetrics(metaLabel.font)
    val widthOf = { s: String -> metrics.stringWidth(s) }
    val line1 = metaLineWithinBudget(metaLineText, metaLineHead, metaLineDigest, widthOf, budget)
    metaLabel.text = if (metaPerfSegments.isEmpty()) {
        line1
    } else {
        "<html>$line1<br>${perfLineHtml(budget, metrics)}</html>"
    }
}

/** F3：全文展示文本（未布局/预算充裕时）——meta 行 + perf 行原着色 markup，与 T-SqlPerf 原渲染一致。 */

internal fun SqlPreviewPanel.fullDisplayText(): String {
    if (metaPerfSegments.isEmpty()) return metaLineText
    val markup = metaPerfSegments.joinToString("；") { "<font color=\"${it.color}\">${it.text}</font>" }
    return "<html>$metaLineText<br>   $markup</html>"
}

/** F3：perf 行预算内重拼——逐段（含「；」分隔宽）按像素累计，放得下保留原着色；放不下丢尾段并加省略号。 */

internal fun SqlPreviewPanel.perfLineHtml(budget: Int, metrics: FontMetrics): String {
    val segments = metaPerfSegments
    if (segments.isEmpty()) return ""
    val indent = "   "
    var used = metrics.stringWidth(indent)
    val rendered = StringBuilder(indent)
    var kept = 0
    segments.forEachIndexed { index, segment ->
        val piece = (if (index > 0) "；" else "") + segment.text
        val pieceWidth = metrics.stringWidth(piece)
        if (kept > 0 && used + pieceWidth > budget) return@forEachIndexed
        if (kept == 0 && used + pieceWidth > budget) {
            // 首段放不下：首段文本尾部省略至预算，后续段全弃（HTML 标签外截断，tooltip 恒全文）
            val text = ellipsize(segment.text, metrics, (budget - used).coerceAtLeast(MIN_META_BUDGET_PX / 2))
            rendered.append("<font color=\"").append(segment.color).append("\">").append(text).append("</font>")
            return rendered.toString()
        }
        rendered.append(if (index > 0) "；" else "")
            .append("<font color=\"").append(segment.color).append("\">").append(segment.text).append("</font>")
        used += pieceWidth
        kept++
    }
    if (kept < segments.size) {
        rendered.append("<font color=\"").append(segments[kept - 1].color).append("\">…</font>")
    }
    return rendered.toString()
}

/**
 * F2/T-SqlPerf：构建性能建议分段（可见文本 + 颜色）。ERROR/WARN 红前缀 ⚠，INFO 蓝前缀 ℹ。
 */

internal fun SqlPreviewPanel.buildPerfSegments(result: PreviewResult): List<MetaPerfSegment> {
    val warnings = result.perfWarnings ?: return emptyList()
    if (warnings.isEmpty()) return emptyList()
    return warnings.map { warning ->
        val isError = warning.severity == PerfSeverity.ERROR || warning.severity == PerfSeverity.WARN
        val prefix = if (isError) "⚠ " else "ℹ "
        MetaPerfSegment(
            text = prefix + localizeWarning(warning),
            // T-UN-13（T-UiNative）：HTML 段色写死 hex → namedColor 取主题色再注入（亮值不变，
            // HTML 结构与截断逻辑不动）
            color = MpvsUiColors.htmlHex(if (isError) MpvsUiColors.PERF_ERROR_TEXT else MpvsUiColors.PERF_INFO_TEXT),
        )
    }
}

/**
 * F-2：本地化单条性能警告（取 i18n 文案 + 参数替换）。
 */

internal fun SqlPreviewPanel.localizeWarning(warning: PerfWarning): String {
    val args = warning.messageArgs.toTypedArray()
    return if (args.isNotEmpty()) {
        MpSqlVisualizerBundle.message(warning.messageKey, *args)
    } else {
        MpSqlVisualizerBundle.message(warning.messageKey)
    }
}

/**
 * T-M25.3：一键复制完整错误信息（用户实测：弹窗顶部 ⚠ 警示文本难以选中复制；
 * 复制内容为完整 warning——解析/渲染错误合并文本，非显示截断）。
 */

internal fun SqlPreviewPanel.copyError(): Boolean {
    val warning = currentWarning ?: return false
    UiTextClipboard.copy(warning)
    return true
}
