package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.SqlResultPanel
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * 「日志转 SQL」tab 面板（T-M38）：粘贴 MyBatis 日志（`Preparing:` / `Parameters:` 行）→
 * 批量还原为可执行 SQL。复用 [MyBatisLogParser] 配对与 [LogSqlRestorer] 还原（与自动捕获同链路，
 * 前缀/混合/截断配置同源读取设置）；一段日志含多组语句时全部转换，组间 `-- SQL #N` 注释分隔。
 *
 * 布局（Splitter 上下分栏 + IDEA 原生 ActionToolbar，对齐「SQL → Java」tab）：
 *  - 上：日志输入区（普通编辑器——日志文本含前缀/时间戳，不设 SQL 词法高亮）；工具栏：转换 / 清空；
 *  - 下：SQL 结果区（可编辑 + 自研 SQL 词法高亮——[cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter]，
 *    与预览面板/日志预览共用同一 token→配色映射，**不查平台方言映射**）；工具栏：复制 / 压缩↔多行 / 清空；
 *  - 底部：状态行（错误红字 / 提示灰字）。
 *
 * 转换是 EDT 纯函数（文本解析无 PSI/实体查找），同步执行无需异步；
 * 参数不足（还原后仍含 `?`）以红字警告提示。
 */
class LogToSqlPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val inputEditor: Editor = createInputEditor()
    private val resultEditor: Editor = createResultEditor()

    /** O-92：「从剪贴板读取」动作（输入工具栏成员引用，测试驱动/断言用）。 */
    private val readClipboardAction = ReadClipboardAction()

    private val statusLabel = JBLabel(" ").apply {
        border = JBUI.Borders.empty(2, 6, 2, 6)
    }

    // 执行失败复用预览结果面板的完整错误/复制动作；日志无 mapper 源锚点，不提供定位动作。
    private val executionErrorPanel = SqlResultPanel()

    // T-PreviewUx-Pack #2：诚实降级横幅（面板内嵌非模态提示，不算弹窗）——还原失败/参数缺失
    // 不再仅靠单行状态红字，横幅承载三要素（哪段没还原/为什么/用户能做什么）
    private val degradationBanner = cn.song.mybatisplus.visualizer.ui.DegradationBanner()

    /**
     * T-UiNative T-UN-14：空态提示接编辑器原生 placeholder（两区锚定物均为 EditorFactory
     * 直建编辑器，实际类型 EditorEx——243 javap 核实有 `setPlaceholder(CharSequence)`），
     * 显隐由编辑器随空文档/焦点原生管理；文案键（"log.panel.hint.input" / "log.panel.hint.result"）沿用不变。
     */
    private val inputPlaceholder: String = MpSqlVisualizerBundle.message("log.panel.hint.input")
    private val resultPlaceholder: String = MpSqlVisualizerBundle.message("log.panel.hint.result")

    // T-LogToSqlHint（20261010 用户优化点）：两区下方恒显灰字提示（对齐 SqlToJavaPanel 形态——
    // 编辑器原生 placeholder 仅聚焦时可见，未聚焦空态观感为「无提示」）
    private val inputHintLabel = JBLabel(MpSqlVisualizerBundle.message("log.panel.hint.input")).apply {
        foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
        border = com.intellij.util.ui.JBUI.Borders.empty(2, 6, 2, 6)
    }

    private val resultHintLabel = JBLabel(MpSqlVisualizerBundle.message("log.panel.hint.result")).apply {
        foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
        border = com.intellij.util.ui.JBUI.Borders.empty(2, 6, 2, 6)
    }

    /** 输出格式切换态：true=多行格式化（默认，用户确认）；false=日志原样压缩。 */
    private var formatToggled = true

    init {
        // 上：日志输入区（工具栏 + 灰字提示 + 编辑器——位置对齐 SqlToJavaPanel：
        // 提示在工具栏下方、编辑器上方，两面板统一）
        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(
            JPanel(BorderLayout()).apply {
                add(buildInputToolbar(), BorderLayout.NORTH)
                add(inputHintLabel, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH,
        )
        inputPanel.add(inputEditor.component, BorderLayout.CENTER)

        // 下：SQL 结果区（工具栏 + 灰字提示 + 编辑器，同构）
        val resultPanel = JPanel(BorderLayout())
        resultPanel.add(
            JPanel(BorderLayout()).apply {
                add(buildResultToolbar(), BorderLayout.NORTH)
                add(resultHintLabel, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH,
        )
        resultPanel.add(resultEditor.component, BorderLayout.CENTER)

        val splitter = Splitter(true, 0.5f).apply {
            firstComponent = inputPanel
            secondComponent = resultPanel
            isShowDividerControls = false
        }
        add(splitter, BorderLayout.CENTER)
        // T-PreviewUx-Pack #2：底部区 = 诚实降级横幅（上）+ 状态行（下）——横幅触发即显示、
        // 恢复即隐藏（不占高度），状态行语义不变
        val southHost = JPanel(BorderLayout())
        southHost.add(degradationBanner, BorderLayout.NORTH)
        southHost.add(executionErrorPanel, BorderLayout.CENTER)
        southHost.add(statusLabel, BorderLayout.SOUTH)
        add(southHost, BorderLayout.SOUTH)

        // 灰字提示随各自内容显隐（空文档显示、有内容隐藏；两区独立监听不互串；
        // 243 平台 DocumentListener 已 Kotlin 化——Kotlin 接口无 SAM，用 object 表达式）
        val inputSync = object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                inputHintLabel.isVisible = event.document.text.isBlank()
            }
        }
        val resultSync = object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                resultHintLabel.isVisible = event.document.text.isBlank()
            }
        }
        inputEditor.document.addDocumentListener(inputSync)
        resultEditor.document.addDocumentListener(resultSync)
        inputHintLabel.isVisible = inputEditor.document.text.isBlank()
        resultHintLabel.isVisible = resultEditor.document.text.isBlank()
    }

    // ------------------------------------------------------------------
    // 工具栏
    // ------------------------------------------------------------------

    private fun buildInputToolbar(): JPanel {
        val group = DefaultActionGroup(
            ConvertAction(),
            // O-92（批次9 波次2，用户定案「剪贴板按钮先行」）：粘贴还原入口低门槛化——
            // V32 实证唯一入口=本 tab 手动 Ctrl+V，DBX #167「贴文本得 SQL」默认期待下降为
            // 一次点击；编辑器右键/意图入口留后续批次评估（不在本件扩面）。
            readClipboardAction,
            ClearInputAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID_INPUT, group, true)
            .apply { targetComponent = this@LogToSqlPanel }
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(toolbar.component)
        }
    }

    private fun buildResultToolbar(): JPanel {
        val group = DefaultActionGroup(
            CopyAction(),
            ToggleFormatAction(),
            // T-PreviewUx-Pack #4：执行还原产物（送原生 Database 控制台链路；多条语句先勾选确认）
            ExecuteResultAction(),
            ClearResultAction(),
        )
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_ID_RESULT, group, true)
            .apply { targetComponent = this@LogToSqlPanel }
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(toolbar.component)
        }
    }

    private inner class ConvertAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Convert
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.convert")
        }

        override fun actionPerformed(e: AnActionEvent) {
            convert()
        }
    }

    /**
     * O-92：从剪贴板读取日志文本填入输入区——「新增 UI 交互须照抄既有同类实现」定例：
     * 同族工具栏动作形态（update 设图标/文案 + actionPerformed 直调面板方法，同
     * [ConvertAction] / [CopyAction]）；填入即转照抄 [loadFromEntry]（列表「发送到日志转 SQL」
     * 的既有同语义链路）。F64（T-PerfSweep18，修法修正＝删 update 探针而非缓存）：
     * 原「有内容才启用」在 update 内探测剪贴板＝每次工具栏刷新都 EDT 读剪贴板——
     * 按钮改为常 enabled，剪贴板空点击走 O-92 既有状态行轻提示（[readFromClipboard]
     * 空文本分支，零写入零弹窗），零 EDT 读剪贴板、少一个缓存失效面。
     * 图标 MenuPaste：243 基线 AllIcons$Actions javap 204 字段核实
     * **无 Paste 字段**（MenuPaste/Menu_paste 在），自家动作同族取用防 NoSuchFieldError。
     */
    private inner class ReadClipboardAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Paste
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.readClipboard")
        }

        override fun actionPerformed(e: AnActionEvent) {
            readFromClipboard()
        }
    }

    private inner class ClearInputAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Clear
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.clearInput")
        }

        override fun actionPerformed(e: AnActionEvent) {
            setDocumentText(inputEditor, "")
        }
    }

    private inner class CopyAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Copy
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.copy")
            e.presentation.isEnabled = resultEditor.document.text.isNotBlank()
        }

        override fun actionPerformed(e: AnActionEvent) {
            UiTextClipboard.copy(resultEditor.document.text)
            status(MpSqlVisualizerBundle.message("logToSql.copy.done"), error = false)
        }
    }

    /** 压缩/多行切换：立即用当前输入重转（空输入不动作）。 */
    private inner class ToggleFormatAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Format
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.toggleFormat")
        }

        override fun actionPerformed(e: AnActionEvent) {
            formatToggled = !formatToggled
            if (inputEditor.document.text.isNotBlank()) convert()
        }
    }

    private inner class ClearResultAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Clear
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.clearResult")
        }

        override fun actionPerformed(e: AnActionEvent) {
            setDocumentText(resultEditor, "")
            // T-AutoFold F4-c：清空后同步清除折叠区域
            cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(resultEditor, "")
            status(" ", error = false)
        }
    }

    /**
     * T-PreviewUx-Pack #4：执行还原产物（吸收 DBX「多语句执行确认」口径）——
     * 产物拆分后**单条直通**（不增加低价值打断）；多条先弹候选勾选浮层
     * （T-CrudSql 原生形态：JBPopup 无按钮浮层，Enter/双击执行选中项、ESC 关闭、打字过滤），
     * 选中项送既有执行链路（execute/DataSourceSelector + SqlExecutor——原生 Database 控制台，
     * 凭证不出 IDE；Community 由 SqlExecutor 提示降级）。
     */
    private inner class ExecuteResultAction : AnAction() {
        override fun update(e: AnActionEvent) {
            e.presentation.icon = MpvsIcons.Execute
            e.presentation.text = MpSqlVisualizerBundle.message("logToSql.panel.execute")
            e.presentation.isEnabled = resultEditor.document.text.isNotBlank()
        }

        override fun actionPerformed(e: AnActionEvent) {
            executeResult()
        }
    }

    /** 执行入口：拆语句 → 单条直通 / 多条勾选浮层。 */
    private fun executeResult() {
        val text = resultEditor.document.text
        if (text.isBlank()) return
        val statements = splitSqlStatements(text)
        if (statements.isEmpty()) {
            status(MpSqlVisualizerBundle.message("logToSql.panel.noEntry"), error = true)
            return
        }
        if (statements.size == 1) {
            executeSql(statements[0])
            return
        }
        // 多条：候选勾选浮层（CrudSqlPreviewFlow.showCandidates 同款范式——无按钮、
        // Enter/双击确认、ESC 关闭、速度搜索过滤；条目 = 摘要行 + 完整语句详情行）
        val rows = statements.mapIndexed { index, sql ->
            StatementCandidate(
                index = index + 1,
                keyword = cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier.getFirstKeyword(sql) ?: "SQL",
                sql = sql,
            )
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(rows)
            .setTitle(MpSqlVisualizerBundle.message("logToSql.execute.pickTitle"))
            .setRenderer(StatementCandidateRenderer())
            .setNamerForFiltering { it.summary() }
            .setItemChosenCallback { selected ->
                // 执行延后到选择浮层关闭之后（T-CrudSql 真机修复同款：不在选择 UI 的事件栈内
                // 同步开 modeless 链路，262 实测会被吞且零异常零日志）
                ApplicationManager.getApplication().invokeLater {
                    executeSql(selected.sql)
                }
            }
            .apply {
                // T-BatchInMerge（O-14）：同表等值批删的合并参考行——浮层原生单行提示位
                // （adText），零新弹窗零新组件形态；执行文本与复制行为零改动
                buildMergeAdText(statements)?.let { setAdText(it) }
            }
            .createPopup()
            .showInFocusCenter()
    }

    /** 执行单条语句：非查询先 DML 二次确认（与预览面板执行同交互），随后送原生控制台链路。
     *  T-UnboundedWriteGuard：确认消息经 ExecuteConfirmMessages——命中无界写特征时同一对话框
     *  列出发现（不新增弹窗形态/步骤），无发现时文案逐字节不变。
     *  弹窗白名单依据（BannedApiSourceScanTest「模态弹窗只允许白名单内既有场景」）：非查询提交前
     *  的 YesNo 确认属「用户必须做选择」场景，与 SqlPreviewPanel 执行确认同类。 */
    private fun executeSql(sql: String) {
        val isQuery = cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier.isQuery(sql)
        if (!isQuery) {
            val keyword = cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier.getFirstKeyword(sql) ?: "SQL"
            val confirm = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages.build(
                    sql,
                    keyword,
                    // R52：日志还原链无预览会话方言，取当前生效方言（与渲染/执行链同源函数）
                    cn.song.mybatisplus.visualizer.sql.DialectRegistry.effective(project),
                ),
                MpSqlVisualizerBundle.message("execute.confirm.dml"),
                com.intellij.openapi.ui.Messages.getWarningIcon(),
            )
            if (confirm != com.intellij.openapi.ui.Messages.YES) return
        }
        val dialectGateRequest = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.rawSqlRequest(sql)
        status(MpSqlVisualizerBundle.message("logToSql.execute.pending"), error = false)
        cn.song.mybatisplus.visualizer.execute.SqlExecutor.execute(
            project, sql, null, isQuery,
            onError = { errorMsg -> showExecutionError(errorMsg) },
            onErrorRich = { info -> showExecutionErrorRich(info) },
            dialectGateRequest = dialectGateRequest,
            onSubmission = ::showSubmission,
        )
    }

    private fun showSubmission(outcome: cn.song.mybatisplus.visualizer.execute.ExecutionSubmission) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater(
                { showSubmission(outcome) }, com.intellij.openapi.application.ModalityState.any(),
            )
            return
        }
        when (outcome) {
            cn.song.mybatisplus.visualizer.execute.ExecutionSubmission.SUBMITTED ->
                status(MpSqlVisualizerBundle.message("logToSql.execute.submitted"), error = false)
            cn.song.mybatisplus.visualizer.execute.ExecutionSubmission.CANCELLED ->
                status(MpSqlVisualizerBundle.message("logToSql.execute.cancelled"), error = false)
            cn.song.mybatisplus.visualizer.execute.ExecutionSubmission.BLOCKED ->
                showExecutionError(MpSqlVisualizerBundle.message("logToSql.execute.blocked"))
        }
    }

    /** T-Perf-14：后台转换进行中标志（防重入）。 */
    private val converting = java.util.concurrent.atomic.AtomicBoolean(false)

    // ------------------------------------------------------------------
    // 转换流程
    // ------------------------------------------------------------------

    /**
     * O-92：剪贴板 → 输入区（EDT）。有文本：填入即转（[loadFromEntry] 同款链路，小输入同步
     * 转换、大输入走 [convert] 既有的后台分频路径）；剪贴板空：状态行轻提示，零写入零弹窗。
     */
    private fun readFromClipboard() {
        val text = UiTextClipboard.readText()
        if (text.isNullOrBlank()) {
            status(MpSqlVisualizerBundle.message("logToSql.clipboard.empty"), error = false)
            return
        }
        setDocumentText(inputEditor, text)
        convert()
    }

    /**
     * 逐行解析输入日志 → 批量还原（每组 withTerminator + 格式化/压缩切换）→ 写结果区。
     *
     * 三解析器并列：MyBatis + Hibernate + JdbcTemplate 同时喂行，各自识别匹配的格式，结果合并输出。
     * 一段混合日志（同时含多种格式）也能正确转换。
     *
     * T-Perf-14：大日志（超过 [HEAVY_INPUT_CHARS] / [HEAVY_INPUT_LINES] 阈值，粘贴数 MB 日志场景）
     * 解析 + 逐条格式化移 [Task.Backgroundable] 后台 + 进度，EDT 不再全量解析冻结；
     * 小输入保持同步（即时反馈）。防重入：后台转换进行中忽略再次触发。
     */
    private fun convert() {
        val text = inputEditor.document.text
        if (text.isBlank()) {
            status(MpSqlVisualizerBundle.message("logToSql.panel.empty"), error = true)
            return
        }
        // 前缀/混合/截断与自动捕获同源（每次转换即时读取，改设置立即生效）
        val parsers = buildParsers()
        val heavy = text.length > HEAVY_INPUT_CHARS || text.count { it == '\n' } >= HEAVY_INPUT_LINES
        if (!heavy) {
            applyConversion(computeConversion(text, parsers))
            return
        }
        if (!converting.compareAndSet(false, true)) return
        status(MpSqlVisualizerBundle.message("logToSql.status.converting"), error = false)
        object : Task.Backgroundable(
            project,
            MpSqlVisualizerBundle.message("logToSql.panel.convertingTask"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val out = computeConversion(text, parsers)
                ApplicationManager.getApplication().invokeLater {
                    converting.set(false)
                    if (project.isDisposed) return@invokeLater
                    applyConversion(out)
                }
            }
        }.queue()
    }

    /** 四解析器装配（转换配置每次转换即时读取；R10 增第四格式 ShardingSphere 双段日志）。 */
    private class LogParsers(
        val myBatis: MyBatisLogParser,
        val hibernate: HibernateLogParser,
        val jdbc: JdbcTemplateLogParser,
        val sharding: ShardingSphereLogParser,
        /** ShardingSphere 喂行开关（与自动捕获同源读取设置）。 */
        val shardingEnabled: Boolean,
    )

    private fun buildParsers(): LogParsers {
        val state = PluginSettings.getInstance().state
        return LogParsers(
            myBatis = MyBatisLogParser(
                sqlPrefix = state.logSqlPrefix,
                paramPrefix = state.logParamPrefix,
                mixedPrefix = state.logMixedPrefix,
                maxParseLength = state.logMaxParseLength,
            ),
            hibernate = HibernateLogParser(
                sqlPrefix = state.logHibernateSqlPrefix,
                paramPrefix = state.logHibernateParamPrefix,
                mixedPrefix = state.logMixedPrefix,
                maxParseLength = state.logMaxParseLength,
            ),
            jdbc = JdbcTemplateLogParser(
                enabled = state.logJdbcEnabled,
                maxParseLength = state.logMaxParseLength,
            ),
            sharding = ShardingSphereLogParser(
                maxParseLength = state.logMaxParseLength,
            ),
            shardingEnabled = state.logShardingSphereEnabled,
        )
    }

    /** 纯计算：逐行喂四解析器 + 逐条还原/格式化（后台线程安全——不触碰 Swing）。 */
    private fun computeConversion(
        text: String,
        parsers: LogParsers,
    ): ConversionOutput {
        text.lineSequence().forEach { line ->
            // F58（T-PerfSweep18 压轴）：同一行喂四解析器——线程 token 每行只提一次下传
            val threadToken = LogThreadRouter.threadTokenOf(line)
            parsers.myBatis.feed(line, threadToken)
            parsers.hibernate.feed(line, threadToken)
            parsers.jdbc.feed(line, threadToken)
            if (parsers.shardingEnabled) parsers.sharding.feed(line, threadToken)
        }
        parsers.myBatis.closePending()
        parsers.hibernate.closePending()
        parsers.jdbc.closePending()
        parsers.sharding.closePending()
        val entries = parsers.myBatis.takeEntries() + parsers.hibernate.takeEntries() +
            parsers.jdbc.takeEntries() + parsers.sharding.takeEntries()
        if (entries.isEmpty()) return ConversionOutput(null, 0, 0)
        val multi = entries.size > 1
        // T-PerfSweep6 T-PS6-08（性能报告发现 16）：未还原计数**并入既有单遍 mapIndexed**。
        // 此前是第二次 `entries.count { countPlaceholders(it.restoredSql) > 0 }`——同一批条目再遍历
        // 一遍列表、再进一遍 lambda（字符串扫描次数不变，省的是第二次列表遍历与闭包开销）。
        // T-HibDualCapture：string-aware 计数，字符串/注释里的 `?` 不再误计。
        var unresolved = 0
        val out = entries.mapIndexed { index, entry ->
            if (countPlaceholders(entry.restoredSql) > 0) unresolved++
            val base = withTerminator(entry.restoredSql)
            val body = if (formatToggled) SqlFormatter.format(base) else base
            if (multi) {
                // T-StatementNaming（O-19）：语句前导注释 `-- Name: x`（或最近行注释）→ 组头附「 [名称]」；
                // 无注释 nameOf=null 后缀空串——组头逐字节维持现状（硬门禁）
                val named = cn.song.mybatisplus.visualizer.util.SqlStatementNamer.nameOf(entry.restoredSql)
                "-- ${MpSqlVisualizerBundle.message("logToSql.status.group", index + 1)}" +
                    (if (named != null) " [$named]" else "") + "\n$body"
            } else body
        }.joinToString("\n\n")
        return ConversionOutput(out, entries.size, unresolved)
    }

    /** 应用转换结果（EDT）：写结果区 + 状态行 + 诚实降级横幅。 */
    private fun applyConversion(out: ConversionOutput) {
        if (out.text == null) {
            // T-PreviewUx-Pack #2：未识别到条目 → 横幅三要素（哪段没还原=整段日志，为什么=前缀不匹配，能做什么）
            degradationBanner.show(
                cn.song.mybatisplus.visualizer.ui.DegradationBanner.Kind.RENDER_WARNING,
                MpSqlVisualizerBundle.message("logToSql.banner.noEntry"),
            )
            status(MpSqlVisualizerBundle.message("logToSql.panel.noEntry"), error = true)
            return
        }
        setDocumentText(resultEditor, out.text)
        // T-AutoFold F4-c：转换产出后按当前设置重建结果区折叠（关闭态零操作）。
        // 随 dev 的计算/应用拆分从 computeConversion（后台线程）移到此处（EDT），写后即折。
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(resultEditor, out.text)
        if (out.unresolved > 0) {
            // T-PreviewUx-Pack #2：还原不完整横幅（为什么=Parameters 缺失/残缺，能做什么=补全参数行）
            degradationBanner.show(
                cn.song.mybatisplus.visualizer.ui.DegradationBanner.Kind.RENDER_WARNING,
                MpSqlVisualizerBundle.message("logToSql.banner.unresolved", out.unresolved),
            )
        } else {
            degradationBanner.clear(cn.song.mybatisplus.visualizer.ui.DegradationBanner.Kind.RENDER_WARNING)
        }
        status(
            if (out.unresolved > 0) {
                MpSqlVisualizerBundle.message("logToSql.status.unresolved", out.unresolved)
            } else {
                MpSqlVisualizerBundle.message("logToSql.status.converted", out.entriesCount)
            },
            error = out.unresolved > 0,
        )
    }

    /** 转换结果（null text = 无条目）。 */
    private data class ConversionOutput(val text: String?, val entriesCount: Int, val unresolved: Int)

    /** T-M38：列表「发送到日志转 SQL」落点——条目渲染回日志文本填入输入区并立即转换。 */
    internal fun loadFromEntry(entry: LogSqlEntry) {
        setDocumentText(inputEditor, entry.toLogText())
        convert()
    }

    /** SQL 末尾分号结束符（显示层统一追加；已以 `;` 结尾或空串不重复）。 */
    private fun withTerminator(sql: String): String =
        if (sql.isEmpty() || sql.endsWith(";")) sql else sql + ";"

    private fun setDocumentText(editor: Editor, text: String) {
        if (editor.document.text == text) return
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText(text)
        }
    }

    /** 日志没有 mapper 来源锚点：保留富错误与复制，但不提供虚假的定位动作。 */
    private fun showExecutionErrorRich(info: cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater({ showExecutionErrorRich(info) },
                com.intellij.openapi.application.ModalityState.any())
            return
        }
        if (disposed || project.isDisposed) return
        val text = cn.song.mybatisplus.visualizer.execute.ExecutionErrorFormatter.format(info)
        status(text, error = true)
        statusLabel.isVisible = false
        executionErrorPanel.showErrorRich(info, null)
    }

    /** 执行链回调有 EDT 与后台两条路径；只在 EDT 更新面板，不弹窗。 */
    private fun showExecutionError(message: String) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater({ showExecutionError(message) },
                com.intellij.openapi.application.ModalityState.any())
            return
        }
        if (disposed || project.isDisposed) return
        status(message, error = true)
        statusLabel.isVisible = false
        executionErrorPanel.showError(message)
    }

    /** 状态行（错误红字 / 提示主题色）；普通状态恢复时撤销上次执行错误动作。 */
    private fun status(message: String, error: Boolean) {
        executionErrorPanel.showEmpty()
        statusLabel.isVisible = true
        statusLabel.foreground = if (error) {
            // IDE 标准错误前景命名色（亮暗主题自适应；243 HighlighterColors 无 ERROR 常量）
            JBColor.namedColor("ErrorForeground", JBColor.RED)
        } else {
            EditorColorsManager.getInstance().globalScheme.getAttributes(HighlighterColors.TEXT).foregroundColor
        }
        statusLabel.text = message
    }

    // ------------------------------------------------------------------
    // 编辑器
    // ------------------------------------------------------------------

    /** 日志输入编辑器：普通文本（日志含前缀/时间戳等噪声，SQL 高亮会误导）；行号开。 */
    private fun createInputEditor(): Editor {
        val editor = EditorFactory.getInstance()
            .createEditor(EditorFactory.getInstance().createDocument(""), project)
        (editor as EditorEx).apply {
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = false
            settings.isIndentGuidesShown = false
            settings.isAdditionalPageAtBottom = false
            // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
            cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings.applyTo(this)
            setPlaceholder(inputPlaceholder) // T-UiNative T-UN-14：空态提示原生件
        }
        return editor
    }

    /** SQL 结果编辑器：可编辑（微调参数后复制）+ 自研 SQL 词法高亮。
     *
     * T-SqlDialectPrewarm：此前这里取平台 SQL FileType 的高亮器
     * （`SyntaxHighlighterFactory.getSyntaxHighlighter(sqlFileType, project, null)`），链路会落到
     * `SqlDialectMappings.getMapping` → `PerFileMappingsBase.ensureStateLoaded` 的**一次性延迟加载**
     * ——逐条 `VirtualFileManager.findFileByUrl` 解析持久化的方言映射，在 EDT 上撞平台慢操作断言
     * （真机栈：本方法 → `FSRecordsImpl.update` → `SlowOperations.assertSlowOperationsAreAllowed`）。
     * 改走自研 [cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter]：不查方言映射、
     * 不依赖 Database 插件（未装时此前该区根本没有高亮），配色与预览面板/日志预览同源。
     */
    private fun createResultEditor(): Editor {
        val editor = EditorFactory.getInstance()
            .createEditor(EditorFactory.getInstance().createDocument(""), project)
        (editor as EditorEx).apply {
            highlighter = LexerEditorHighlighter(
                cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter(),
                EditorColorsManager.getInstance().globalScheme,
            )
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = true
            settings.isIndentGuidesShown = false
            settings.isAdditionalPageAtBottom = false
            // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
            cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings.applyTo(this)
            setPlaceholder(resultPlaceholder) // T-UiNative T-UN-14：空态提示原生件
        }
        return editor
    }

    /** T-UiNative T-UN-14：输入区空态提示（原 hintLabel 测试缝，断言 placeholder 文案接线用）。 */
    internal fun inputPlaceholderForTest(): String = inputPlaceholder

    /** T-UiNative T-UN-14：结果区空态提示。 */
    internal fun resultPlaceholderForTest(): String = resultPlaceholder

    override fun dispose() {
        if (disposed) return
        disposed = true
        EditorFactory.getInstance().releaseEditor(inputEditor)
        EditorFactory.getInstance().releaseEditor(resultEditor)
    }

    private var disposed = false

    // ------------------------------------------------------------------
    // internal 测试访问器（仿 SqlToJavaPanel *ForTest 约定）
    // ------------------------------------------------------------------

    internal fun setInputForTest(text: String) = setDocumentText(inputEditor, text)

    internal fun convertForTest() = convert()

    /** O-92：输入区当前文本（剪贴板填入断言用）。 */
    internal fun inputTextForTest(): String = inputEditor.document.text

    /** O-92：等价点击「从剪贴板读取」。 */
    internal fun readClipboardForTest() = readFromClipboard()

    /** F64：剪贴板动作本体（update 常 enabled 断言用，测试观察缝）。 */
    internal fun readClipboardActionForTest() = readClipboardAction as AnAction

    internal fun toggleFormatForTest() {
        formatToggled = !formatToggled
    }

    internal fun resultTextForTest(): String = resultEditor.document.text

    /** T-AutoFold F4-c：结果区编辑器（折叠区域断言用）。 */
    internal fun resultEditorForTest(): Editor = resultEditor

    internal fun statusForTest(): String = statusLabel.text

    /** T-PreviewUx-Pack #2：诚实降级横幅（显隐/文案断言用）。 */
    internal fun degradationBannerForTest(): cn.song.mybatisplus.visualizer.ui.DegradationBanner = degradationBanner

    // ------------------------------------------------------------------
    // T-PreviewUx-Pack #4：多语句执行确认（候选模型 / 渲染器 / 语句拆分）
    // ------------------------------------------------------------------

    /** 候选项（纯数据，可单测）：序号 + 首关键字摘要 + 完整语句。 */
    internal data class StatementCandidate(val index: Int, val keyword: String, val sql: String) {
        /** 摘要行：`SQL #N · 关键字`（CrudSqlPreviewFlow.summary 同款 `·` 分隔风格）。 */
        fun summary(): String =
            MpSqlVisualizerBundle.message("logToSql.status.group", index) + " · " + keyword
    }

    /**
     * 候选行渲染器：主题色双行（加粗摘要 + 次要色详情），CrudSqlPreviewFlow.CandidateCellRenderer
     * 同款范式；详情行禁 HTML 解析（语句可能以 `<` 开头），按 [SqlSelectionDialog.MAX_LINE_LENGTH] 截断。
     */
    private class StatementCandidateRenderer : JPanel(BorderLayout()), ListCellRenderer<StatementCandidate> {
        private val summaryLabel = JLabel()
        private val detailLabel = JLabel()

        init {
            isOpaque = true
            border = JBUI.Borders.empty(6, 12)
            summaryLabel.font = summaryLabel.font.deriveFont(java.awt.Font.BOLD)
            detailLabel.putClientProperty("html.disable", true)
            detailLabel.foreground = UIUtil.getContextHelpForeground()
            add(summaryLabel, BorderLayout.NORTH)
            add(detailLabel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out StatementCandidate>,
            value: StatementCandidate,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ): Component {
            summaryLabel.text = value.summary()
            val firstLine = value.sql.lineSequence().firstOrNull().orEmpty()
            detailLabel.text =
                if (firstLine.length > cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog.MAX_LINE_LENGTH) {
                    firstLine.take(cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog.MAX_LINE_LENGTH) + "…"
                } else {
                    firstLine
                }
            background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            summaryLabel.foreground = UIUtil.getListForeground()
            detailLabel.foreground = if (selected) UIUtil.getListForeground() else UIUtil.getContextHelpForeground()
            return this
        }
    }

    internal fun statementsForTest(): List<String> = splitSqlStatements(resultEditor.document.text)

    internal fun loadFromEntryForTest(entry: LogSqlEntry) = loadFromEntry(entry)

    companion object {

        /**
         * T-PreviewUx-Pack #4：结果文本 → 语句列表（纯函数）——按顶层 `;` 切分，
         * 引号字符串内的 `;` 不切分；`--` 行注释与块注释内的 `;` 不切分（LogToSqlPanel 产物
         * 组间自带 `-- SQL #N` 注释头，注释随所属语句一并保留——原生控制台接受注释）。
         * 段间空白丢弃，空列表 = 无可执行语句。
         *
         * T-SemicolonGuard（O-13）：实现收敛委托至 util/SqlStatementSplitter
         * （单实现三处历史切分合一）；新增括号深度与 BEGIN/END 块感知两项语义
         * （迁移前后既有样例输出逐段一致，守卫测试锁死）。
         */
        internal fun splitSqlStatements(sql: String): List<String> =
            cn.song.mybatisplus.visualizer.util.SqlStatementSplitter.split(sql)

        /**
         * T-BatchInMerge（O-14）：批量等值谓词合并参考文案（纯函数可测；null = 无合并集）。
         * 异构语句在场时按「N/M 条」披露（不静默丢）；展示预算截值清单时**计数后缀恒可见**
         * （先裁值再拼「…等 N 条」，N = 行数/字节超限 + 展示裁掉的值数之和——绝不无计数截断）。
         */
        internal fun buildMergeAdText(statements: List<String>): String? {
            // T-PS5-11：语句数预检——超上限**跳过逐语句 AST 预检**（500 行上限最坏数百次 JSqlParser
            // 解析，全部发生在浮层打开前的 EDT），改按计数披露：明说未做同构分析 + 执行仍逐条，
            // 绝不静默丢（既有「异构不静默丢」口径的计数版）。上限内维持现状。
            if (statements.size > MERGE_ANALYZE_STATEMENT_LIMIT) {
                return MpSqlVisualizerBundle.message(
                    "logToSql.execute.mergeSkipped",
                    statements.size,
                    MERGE_ANALYZE_STATEMENT_LIMIT,
                )
            }
            val preview = cn.song.mybatisplus.visualizer.util.BatchPredicateMerge.mergePreview(statements) ?: return null
            val prefix = "DELETE FROM ${preview.table} WHERE ${preview.column} IN ("
            // 展示预算：前缀 + 值清单 + 收括号 ≤ 上限；后缀占位先扣（保证计数可见）
            val budget = MERGE_HINT_SQL_DISPLAY_LIMIT - prefix.length - 1
            val joined = ArrayList<String>()
            var used = 0
            var dropped = 0
            for ((index, value) in preview.values.withIndex()) {
                val cost = value.length + if (index > 0) 2 else 0
                if (used > 0 && used + cost > budget - MERGE_MORE_RESERVE) {
                    dropped = preview.values.size - joined.size
                    break
                }
                used += cost
                joined += value
            }
            val hidden = preview.hiddenValues + dropped
            val moreText = if (hidden > 0) MpSqlVisualizerBundle.message("logToSql.execute.mergeMore", hidden) else ""
            val inner = joined.joinToString(", ") + if (moreText.isNotEmpty()) ", $moreText" else ""
            val displaySql = "$prefix$inner)"
            return if (preview.coveredCount == preview.totalStatements) {
                MpSqlVisualizerBundle.message("logToSql.execute.mergeHint", preview.coveredCount, displaySql)
            } else {
                MpSqlVisualizerBundle.message(
                    "logToSql.execute.mergeHintSubset",
                    preview.totalStatements, preview.coveredCount, displaySql,
                )
            }
        }

        /** T-BatchInMerge：合并参考行里的 SQL 展示截断（防浮层单行撑爆）。 */
        private const val MERGE_HINT_SQL_DISPLAY_LIMIT = 160

        /** T-BatchInMerge：「…等 N 条」后缀的展示预算预留（截值不截计数）。 */
        private const val MERGE_MORE_RESERVE = 24

        /** T-PS5-11：合并参考分析的语句数上限（超限跳过逐语句 AST 预检，按计数披露）。 */
        private const val MERGE_ANALYZE_STATEMENT_LIMIT = 100

        /** T-Perf-14：大日志后台转换阈值——字符数（约 1MB）。 */
        private const val HEAVY_INPUT_CHARS = 1_000_000
        /** T-Perf-14：大日志后台转换阈值——行数（约 1000 行）。 */
        private const val HEAVY_INPUT_LINES = 1_000
        private const val TOOLBAR_ID_INPUT = "cn.song.mybatisplus.visualizer.logToSql.toolbar.input"
        private const val TOOLBAR_ID_RESULT = "cn.song.mybatisplus.visualizer.logToSql.toolbar.result"
    }
}
