package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.ExecutionSubmission
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.atomic.AtomicBoolean
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 回滚 SQL 视图（T-RollbackSql P1-F3 / P2-F5，dbx 精华融合）。
 *
 * MODELESS 独立弹窗（仿 SqlPreviewDialog 惯例）——刻意不写进预览面板 native 文档、
 * 不切显示模式：`showSql` 三分支管线（T-RefreshDbSteal 治理后）零侵入。
 *
 * 形态按评级分层（dbx「评级不足阻断执行」的对应物）：
 *  - Complete：绿色横幅 + 只读回滚 SQL 编辑器 + 底部「执行回滚 / 复制 / 关闭」；
 *  - Partial：橙色横幅（风险警示）+ COUNT 自查模板展示，其余同上；
 *  - Unable（可解析的 UPDATE/DELETE）：红色横幅 + 人工恢复指引 + **快照工作流**——
 *    快照查询模板一键执行（T-PreviewSideQuery 只读旁路：不替换预览编辑器/不换入控制台，
 *    结果自动填入下方并生成回滚；失败/空结果在对话框内提示，仍可手工复制粘贴）+
 *    粘贴结果生成完整回滚（dbx「有 before-image 才回滚」的半自动落地，P2-F5 降级方案；
 *    262 连接协议不可程序化快照的定夺见 [RollbackSnapshotStore] KDoc）——粘贴生成的回填 INSERT
 *    在快照条目持有 identity 信息（O-52：[RollbackSnapshotStore.SnapshotEntry.dialect] /
 *    `identityColumns`）时按方言包装，否则不包装；
 *  - Unable（完全不可解析/MERGE/DDL）：不进本视图，面板入口直接消息框（计划 §2.2）。
 *
 * 「执行回滚」不走 [cn.song.mybatisplus.visualizer.execute.SqlExecutor.execute] 全链弹数据源
 * 选择——持预览面板引用走 [SqlPreviewPanel.executeAdhocSql]，T-RefreshDbSteal 所有权卫队天然生效；
 * 执行提交后快照条目置「已执行」（F6 留痕）+ 横幅更新。
 */
class RollbackSqlDialog(
    private val project: Project?,
    /** 预览面板引用——「执行回滚/执行快照查询」复用 executeAdhocSql 管线（计划 §2.4）。 */
    private val panel: SqlPreviewPanel?,
    /** 生成结果（面板入口已按「Unable 且无快照工作流」过滤）。 */
    internal val result: RollbackSqlGenerator.RollbackResult,
    /** 原语句关键字（INSERT/UPDATE/DELETE）——快照回填模式分派。 */
    private val statementKind: String,
    /** 经执行按钮执行过的 UPDATE/DELETE 登记条目（F6 留痕；非执行路径入口为 null）。 */
    internal val snapshotEntry: RollbackSnapshotStore.SnapshotEntry? = null,
) : DialogWrapper(project, false, DialogWrapper.IdeModalityType.MODELESS) {

    private val bannerLabel = JBLabel()
    private val warningLabel = JBLabel()
    private val countCheckLabel = JBLabel()
    private val snapshotQueryLabel = JBLabel()

    /** 「执行快照查询」按钮引用（S2-4 券②：Community 置灰态断言用；buildSnapshotBlock 时赋值）。 */
    private var snapshotRunButton: JButton? = null
    private val pasteArea = JBTextArea(4, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val pkField = JBTextField(20)
    private var editor: Editor? = null

    // T-RollbackSnapshotView：快照双态展示——表格视图（只读，列值一一对应）与文本视图
    //（保留手工粘贴/编辑）。自动查询结果默认表格；文本始终是生成回滚的权威来源。
    // T-RollbackSnapshotCsvView：表格增强——可排序/可选中/复制/右键菜单/行号/列宽自适应。
    private val snapshotTableModel = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val snapshotSorter = TableRowSorter(snapshotTableModel)
    private val snapshotRowHeader = JBList<String>().apply {
        border = JBUI.Borders.empty()
    }
    private val snapshotTable = object : JBTable(snapshotTableModel) {
        override fun getToolTipText(event: java.awt.event.MouseEvent): String? {
            val viewColumn = columnAtPoint(event.point)
            if (viewColumn >= 0) {
                val modelColumn = convertColumnIndexToModel(viewColumn)
                if (modelColumn in 0 until columnCount) return snapshotTableModel.getColumnName(modelColumn)
            }
            return super.getToolTipText(event)
        }
    }.apply {
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        rowSorter = snapshotSorter
        cellSelectionEnabled = true
        rowSelectionAllowed = true
        fillsViewportHeight = true
        preferredScrollableViewportSize = Dimension(DIALOG_WIDTH - 48, 92)
        installSnapshotCopyBindings(this)
        addMouseListener(snapshotPopupListener())
    }
    private val snapshotCards = JBPanel<JBPanel<*>>(CardLayout())
    private val snapshotSummary = JBLabel()
    private val snapshotToggle = JButton(MpSqlVisualizerBundle.message("rollback.snapshot.view.text")).apply {
        toolTipText = MpSqlVisualizerBundle.message("rollback.snapshot.view.tooltip")
        addActionListener { toggleSnapshotView() }
    }
    private val snapshotOpenCsv = JButton(MpSqlVisualizerBundle.message("rollback.snapshot.openCsv")).apply {
        toolTipText = MpSqlVisualizerBundle.message("rollback.snapshot.openCsv.tooltip")
        addActionListener { openSnapshotInCsvEditor() }
    }
    private val snapshotAutofit = JButton(MpSqlVisualizerBundle.message("rollback.snapshot.autofit")).apply {
        toolTipText = MpSqlVisualizerBundle.message("rollback.snapshot.autofit.tooltip")
        addActionListener { autoFitSnapshotColumns() }
    }
    private var snapshotTableShown = false

    /** T-RollbackSnapshotCsvView：「在 CSV 编辑器中打开」生成的临时文件（dispose 时清理未打开的）。 */
    private val snapshotTempFiles = mutableListOf<java.io.File>()

    /** 测试缝：打开临时文件（生产走 FileEditorManager.openFile）。 */
    @Volatile
    internal var snapshotOpenInEditorForTest: ((com.intellij.openapi.vfs.VirtualFile, java.io.File) -> Unit)? = null

    init {
        // 排序后行号需按视图顺序重排；数据装载后同样刷新（showSnapshotTable 内调用）。
        snapshotSorter.addRowSorterListener { updateSnapshotRowHeader() }
        snapshotTable.tableHeader.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val viewColumn = snapshotTable.columnAtPoint(e.point)
                    if (viewColumn >= 0) autoFitSnapshotColumn(viewColumn)
                }
            }
        })
    }

    /** 测试缝：headless 无真库/绑定数据源；生产为 null 走 [DataSourceSelector.boundConsoleDataSource]。 */
    @Volatile
    internal var snapshotQueryTargetForTest: (() -> Any?)? = null

    /** 测试缝：数据源选择器弹出（生产走 [DataSourceSelector.prepareConsole]）。 */
    @Volatile
    internal var snapshotPromptForTest: ((Project, String, (String) -> Unit) -> Unit)? = null

    /** 测试缝：绑定等待（生产走 [SqlPreviewPanel.awaitDataSourceBoundThen]）。 */
    @Volatile
    internal var snapshotAwaitBindForTest: ((Project, Int, () -> Unit, () -> Unit) -> Unit)? = null

    /** 测试缝：方言闸请求（生产走 [SqlPreviewPanel.executionDialectGateRequest]，由模型能力决定可重渲染位）。 */
    @Volatile
    internal var snapshotGateRequestForTest: (() -> cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request)? = null

    /** 测试缝：按目标方言重渲染（生产走 [SqlPreviewPanel.rerenderForExecutionTarget]）。 */
    @Volatile
    internal var snapshotRerenderForTest: ((String) -> Boolean)? = null

    /** T-DialectGateExit：方言闸阻断态的出口行（默认隐藏，仅方言冲突时出现）。 */
    private val gateExitRow = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
        isOpaque = false
        isVisible = false
    }

    /** 最近一次方言阻断的目标方言（「按目标方言重渲染」按钮的动作输入）。 */
    private var blockedTargetDialect: String? = null

    private val rerenderToTargetButton = JButton(
        MpSqlVisualizerBundle.message("rollback.snapshot.rerender"),
    ).apply {
        toolTipText = MpSqlVisualizerBundle.message("rollback.snapshot.rerender.tooltip")
        addActionListener { rerenderToTargetAndRetry() }
    }

    private val switchDataSourceButton = JButton(
        MpSqlVisualizerBundle.message("rollback.snapshot.switchSource"),
    ).apply {
        toolTipText = MpSqlVisualizerBundle.message("rollback.snapshot.switchSource.tooltip")
        addActionListener {
            val sql = currentResult.snapshotSelectSql ?: return@addActionListener
            val proj = panel?.project ?: return@addActionListener
            promptDataSourceThenRetrySnapshot(proj, sql, forceChoose = true)
        }
    }

    // 底部动作以字段持有——DialogWrapper 无公开 getActions()（javap 实证），粘贴生成后
    // 启用态只能经自身引用刷新；字段初始化先于 init()（createActions 在 init() 内调用）
    private val executeAction = object : DialogWrapperAction(MpSqlVisualizerBundle.message("rollback.action.execute")) {
        init {
            // UX Community 执行入口预判置灰（S2-4 券②）：执行回滚送原生控制台执行，无 Database
            // 插件时构造期即灰置 + tooltip 说明（与 SqlPreviewDialog 执行 SQL 按钮同款），
            // 不再「点了才报错」。doAction 保留二次守卫（防程序化触发）。
            cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.unavailableHint()?.let { hint ->
                putValue(Action.SHORT_DESCRIPTION, hint)
            }
        }

        override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
            if (!cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable()) return
            executeRollback()
        }
    }
    private val copyAction = object : DialogWrapperAction(MpSqlVisualizerBundle.message("rollback.action.copy")) {
        override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
            copyRollback()
        }
    }

    /** 粘贴生成后的最新回滚结果（执行回滚/复制按钮取此值）。 */
    internal var currentResult: RollbackSqlGenerator.RollbackResult = result
        private set

    /** 仅快照派生产物受输入编辑影响；原始 INSERT 等非快照回滚保持原有执行路径。 */
    private var snapshotGenerationStarted = false

    init {
        title = MpSqlVisualizerBundle.message("rollback.dialog.title")
        setCancelButtonText(MpSqlVisualizerBundle.message("rollback.action.close"))
        init()
        assembleBanner()
        assembleSnapshotSection()
        val inputListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = snapshotInputChanged()
            override fun removeUpdate(e: DocumentEvent) = snapshotInputChanged()
            override fun changedUpdate(e: DocumentEvent) = snapshotInputChanged()
        }
        pasteArea.document.addDocumentListener(inputListener)
        pkField.document.addDocumentListener(inputListener)
    }

    /** 测试访问用：DialogWrapper.init() 创建的同一个中心面板（不重复建编辑器）。 */
    private var snapshotCenterPanel: JComponent? = null

    override fun createCenterPanel(): JComponent {
        val body = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(buildTopBlock(), BorderLayout.NORTH)
            add(buildEditorBlock(), BorderLayout.CENTER)
            if (hasSnapshotWorkflow()) add(buildSnapshotBlock(), BorderLayout.SOUTH)
        }
        body.preferredSize = Dimension(DIALOG_WIDTH, DIALOG_HEIGHT)
        snapshotCenterPanel = body
        return body
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    private fun buildTopBlock(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(6, 10, 2, 10)
        add(bannerLabel, BorderLayout.NORTH)
        add(warningLabel, BorderLayout.CENTER)
        if (currentResult.countCheckSql != null) {
            countCheckLabel.text = countCheckHtml()
            countCheckLabel.toolTipText = currentResult.countCheckSql
            add(countCheckLabel, BorderLayout.SOUTH)
        }
    }

    private fun buildEditorBlock(): JComponent {
        val sqlText = currentResult.statements.joinToString("\n")
        val doc = EditorFactory.getInstance().createDocument(sqlText)
        val created = if (project != null) {
            EditorFactory.getInstance().createEditor(doc, project, sqlFileType(), true)
        } else {
            EditorFactory.getInstance().createViewer(doc)
        }
        // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）。
        // 本编辑器此前零外观设置（只读回滚 SQL），故是唯一不落在 EditorEx.apply 块内的接线点。
        EditorWhitespaceSettings.applyTo(created)
        // T-SqlCopyShortcuts O-122：无选中 Ctrl+C 复制全文、有选中只复制片段
        //（层位与理由见 EditorCopyShortcuts 的 KDoc；本处此前无任何键盘复制绑定）
        EditorCopyShortcuts.install(created, UiTextClipboard::copy)
        editor = created
        // 编辑器随弹窗 disposable 树级联释放（Disposer 树铁律）
        Disposer.register(disposable) { releaseEditor() }
        return JBScrollPane(created.component).apply {
            preferredSize = Dimension(DIALOG_WIDTH, EDITOR_HEIGHT)
        }
    }

    private fun hasSnapshotWorkflow(): Boolean = result.snapshotSelectSql != null && panel != null

    private fun buildSnapshotBlock(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 10, 6, 10)
        add(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                // T-RollbackSnapshotCsvView C：第一行 SQL 标签（CENTER 可压缩）+ 摘要/视图切换
                //（EAST 固定）——旧实现把切换按钮放在 FlowLayout 行尾，窄宽换行后被裁掉。
                add(
                    JBPanel<JBPanel<*>>(BorderLayout()).apply {
                        isOpaque = false
                        add(snapshotQueryLabel, BorderLayout.CENTER)
                        snapshotSummary.isVisible = false
                        add(
                            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                                isOpaque = false
                                add(snapshotSummary)
                                add(snapshotToggle)
                            },
                            BorderLayout.EAST,
                        )
                    },
                    BorderLayout.NORTH,
                )
                // 第二行：动作按钮（执行快照查询 / 复制快照 SQL / 在 CSV 编辑器中打开 / 自适应列宽）
                add(
                    JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                        isOpaque = false
                        add(JButton(MpSqlVisualizerBundle.message("rollback.snapshot.run")).also { btn -> snapshotRunButton = btn }.apply {
                            // UX Community 执行入口预判置灰（S2-4 券②）：快照查询走只读旁路对
                            // 数据源执行，无 Database 插件时构造期灰置 + tooltip（不可用时原
                            // 「查询说明」tooltip 被置灰说明替换——灰按钮悬停先答「为什么不可点」）
                            toolTipText = MpSqlVisualizerBundle.message("rollback.snapshot.run.tooltip")
                            cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.unavailableHint()?.let { hint ->
                                isEnabled = false
                                toolTipText = hint
                            }
                            addActionListener {
                                if (cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable()) {
                                    runSnapshotQuery()
                                }
                            }
                        })
                        add(JButton(MpSqlVisualizerBundle.message("rollback.snapshot.copy")).apply {
                            addActionListener {
                                val sql = currentResult.snapshotSelectSql ?: return@addActionListener
                                copyToClipboard(sql)
                            }
                        })
                        add(snapshotOpenCsv)
                        add(snapshotAutofit)
                    },
                    BorderLayout.CENTER,
                )
                // T-DialectGateExit：方言冲突时的两个出口（默认隐藏）
                gateExitRow.apply {
                    add(rerenderToTargetButton)
                    add(switchDataSourceButton)
                }
                add(gateExitRow, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH,
        )
        snapshotCards.isOpaque = false
        snapshotCards.add(
            JBScrollPane(snapshotTable).apply { setRowHeaderView(snapshotRowHeader) },
            SNAPSHOT_CARD_TABLE,
        )
        snapshotCards.add(JBScrollPane(pasteArea), SNAPSHOT_CARD_TEXT)
        (snapshotCards.layout as CardLayout).show(snapshotCards, SNAPSHOT_CARD_TEXT)
        add(
            JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
                isOpaque = false
                add(snapshotCards, BorderLayout.CENTER)
                add(
                    JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                        isOpaque = false
                        add(JBLabel(MpSqlVisualizerBundle.message("rollback.snapshot.pk")))
                        add(pkField)
                        add(JButton(MpSqlVisualizerBundle.message("rollback.snapshot.generate")).apply {
                            addActionListener { generateFromPaste() }
                        })
                    },
                    BorderLayout.SOUTH,
                )
            },
            BorderLayout.CENTER,
        )
    }

    /** T-RollbackSnapshotView：把快照数据（列名 + 行）装进只读表格并切到表格视图。 */
    private fun showSnapshotTable(columns: List<String>, rows: List<List<String?>>) {
        snapshotTableModel.setDataVector(
            rows.map { row -> row.map { it ?: "" }.toTypedArray() }.toTypedArray(),
            columns.toTypedArray(),
        )
        // 新数据回到原始顺序；行号与列宽随内容刷新。
        snapshotSorter.sortKeys = emptyList()
        updateSnapshotRowHeader()
        autoFitSnapshotColumns()
        snapshotSummary.text = MpSqlVisualizerBundle.message(
            "rollback.snapshot.summary", rows.size, columns.size,
        )
        snapshotSummary.isVisible = true
        snapshotTableShown = true
        (snapshotCards.layout as CardLayout).show(snapshotCards, SNAPSHOT_CARD_TABLE)
        snapshotToggle.text = MpSqlVisualizerBundle.message("rollback.snapshot.view.text")
    }

    /** T-RollbackSnapshotCsvView A：行号列按视图顺序重排（排序后 1..N 连续）。 */
    private fun updateSnapshotRowHeader() {
        val model = javax.swing.DefaultListModel<String>()
        for (i in 1..snapshotTable.rowCount) model.addElement(i.toString())
        snapshotRowHeader.model = model
        snapshotRowHeader.fixedCellHeight = snapshotTable.rowHeight
    }

    /** T-RollbackSnapshotCsvView A：Ctrl+C / Ctrl+Insert 复制当前选择（TSV）。 */
    private fun installSnapshotCopyBindings(table: JTable) {
        val action = object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                copySnapshotSelection()
            }
        }
        table.inputMap.put(KeyStroke.getKeyStroke("control C"), "snapshotCopy")
        table.inputMap.put(KeyStroke.getKeyStroke("control INSERT"), "snapshotCopy")
        table.actionMap.put("snapshotCopy", action)
    }

    /** T-RollbackSnapshotCsvView A：右键菜单（复制单元格/行/列、全选、自适应列宽）。 */
    private fun snapshotPopupListener(): java.awt.event.MouseAdapter =
        object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = maybeShow(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = maybeShow(e)

            private fun maybeShow(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val menu = JPopupMenu()
                menu.add(snapshotMenuItem("rollback.snapshot.copyCell") { copySnapshotSelection() })
                menu.add(snapshotMenuItem("rollback.snapshot.copyRow") { copySnapshotRows() })
                menu.add(snapshotMenuItem("rollback.snapshot.copyColumn") { copySnapshotColumn() })
                menu.add(snapshotMenuItem("rollback.snapshot.selectAll") { snapshotTable.selectAll() })
                menu.addSeparator()
                menu.add(snapshotMenuItem("rollback.snapshot.autofit") { autoFitSnapshotColumns() })
                menu.show(e.component, e.x, e.y)
            }
        }

    private fun snapshotMenuItem(key: String, action: () -> Unit): javax.swing.JMenuItem =
        javax.swing.JMenuItem(MpSqlVisualizerBundle.message(key)).apply {
            addActionListener { action() }
        }

    /** 复制当前选择（无选择时复制全表）；TSV 与结果网格复制口径一致。 */
    internal fun copySnapshotSelection(): Boolean {
        val rows = snapshotTable.selectedRows.toList()
            .ifEmpty { (0 until snapshotTable.rowCount).toList() }
        val columns = snapshotTable.selectedColumns.toList()
            .ifEmpty { (0 until snapshotTable.columnCount).toList() }
        return copySnapshotCells(rows, columns)
    }

    private fun copySnapshotRows(): Boolean =
        copySnapshotCells(
            snapshotTable.selectedRows.toList().ifEmpty { (0 until snapshotTable.rowCount).toList() },
            (0 until snapshotTable.columnCount).toList(),
        )

    private fun copySnapshotColumn(): Boolean {
        val viewColumn = snapshotTable.selectedColumn.takeIf { it >= 0 } ?: 0
        val modelColumn = snapshotTable.convertColumnIndexToModel(viewColumn)
        val header = snapshotTableModel.getColumnName(modelColumn)
        val body = (0 until snapshotTable.rowCount).joinToString("\n") { viewRow ->
            snapshotTableModel.getValueAt(
                snapshotTable.convertRowIndexToModel(viewRow), modelColumn,
            )?.toString().orEmpty()
        }
        val text = if (body.isEmpty()) header else "$header\n$body"
        UiTextClipboard.copy(text)
        return true
    }

    private fun copySnapshotCells(viewRows: List<Int>, viewColumns: List<Int>): Boolean {
        if (viewRows.isEmpty() || viewColumns.isEmpty()) return false
        val text = viewRows.joinToString("\n") { viewRow ->
            viewColumns.joinToString("\t") { viewColumn ->
                snapshotTableModel.getValueAt(
                    snapshotTable.convertRowIndexToModel(viewRow),
                    snapshotTable.convertColumnIndexToModel(viewColumn),
                )?.toString().orEmpty()
            }
        }
        if (text.isEmpty()) return false
        UiTextClipboard.copy(text)
        return true
    }

    /** 按内容自适应列宽（双击列头 = 单列；按钮 = 全部），上限 360px。 */
    internal fun autoFitSnapshotColumns() {
        for (viewColumn in 0 until snapshotTable.columnCount) autoFitSnapshotColumn(viewColumn)
    }

    private fun autoFitSnapshotColumn(viewColumn: Int) {
        if (viewColumn !in 0 until snapshotTable.columnCount) return
        val modelColumn = snapshotTable.convertColumnIndexToModel(viewColumn)
        val headerMetrics = snapshotTable.tableHeader.getFontMetrics(snapshotTable.tableHeader.font)
        val cellMetrics = snapshotTable.getFontMetrics(snapshotTable.font)
        var width = headerMetrics.stringWidth(snapshotTableModel.getColumnName(modelColumn)) + 24
        val rows = minOf(snapshotTable.rowCount, 500)
        for (viewRow in 0 until rows) {
            val value = snapshotTableModel.getValueAt(
                snapshotTable.convertRowIndexToModel(viewRow), modelColumn,
            )?.toString().orEmpty()
            width = maxOf(width, cellMetrics.stringWidth(value) + 16)
        }
        snapshotTable.columnModel.getColumn(viewColumn).preferredWidth = width.coerceIn(48, 360)
    }

    /**
     * T-RollbackSnapshotCsvView B：「在 CSV 编辑器中打开」——快照写临时 .tsv 后用
     * [FileEditorManager] 打开。Ultimate 由 grid 插件的 `csv-data-editor` 自动进入原生表格
     *（可切文本）；Community 降级为文本编辑器。写盘+refresh 在池线程（VFS refresh 属慢操作），
     * 打开在 EDT。
     */
    internal fun openSnapshotInCsvEditor() {
        val proj = project ?: return
        val text = pasteArea.text
        if (text.isBlank()) {
            warningLabel.text = "<html>${escapeHtml(
                MpSqlVisualizerBundle.message("rollback.snapshot.emptyInput"))}</html>"
            return
        }
        // 落在 JVM 系统临时目录（java.io.tmpdir）：不在 IDE 的 VFS 允许根内时也能被
        // 测试沙盒放行（沙盒只允许 C:/Users/USER/AppData/Local/Temp 等少数根），
        // 且与 IDE 配置目录解耦——临时文件由本对话框显式回收。
        val file = java.io.File(
            System.getProperty("java.io.tmpdir") ?: PathManager.getTempPath(),
            "mpsql-rollback-snapshot-${System.currentTimeMillis()}.tsv",
        )
        // T-RollbackSnapshotCsvView：写盘+VFS refresh 属写操作——生产走池线程
        // （WriteIntentReadAction，对齐 DataSourceSelectorResults 的 refreshVfs 段）；
        // 单测模式同步执行（池线程在 headless 用例里不派发 EDT 回调，测试无法确定性地观察）。
        if (ApplicationManager.getApplication().isUnitTestMode) {
            val vf = writeSnapshotTempFile(file, text)
            if (vf == null) {
                warningLabel.text = "<html>${escapeHtml(
                    MpSqlVisualizerBundle.message("rollback.snapshot.openCsv.failed"))}</html>"
                return
            }
            snapshotTempFiles += file
            val sink = snapshotOpenInEditorForTest
            if (sink != null) sink(vf, file) else FileEditorManager.getInstance(proj).openFile(vf, true)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val vf = writeSnapshotTempFile(file, text)
            ApplicationManager.getApplication().invokeLater({
                if (vf == null) {
                    warningLabel.text = "<html>${escapeHtml(
                        MpSqlVisualizerBundle.message("rollback.snapshot.openCsv.failed"))}</html>"
                    return@invokeLater
                }
                snapshotTempFiles += file
                val sink = snapshotOpenInEditorForTest
                if (sink != null) {
                    sink(vf, file)
                } else {
                    FileEditorManager.getInstance(proj).openFile(vf, true)
                }
            }, ModalityState.any())
        }
    }

    /** 写临时 TSV 并让 VFS 可见（写操作走 WriteIntentReadAction）。 */
    /**
     * 写临时 TSV 并让 VFS 可见。
     *
     * 写盘在池线程（`WriteIntentReadAction`），随后用 [com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh]
     * 触发**异步**刷新：同步 `refreshAndFindFileByIoFile` 会在测试沙盒触发 VFS 根访问限制
     * （VfsRootAccessNotAllowedError），异步标记不触碰根校验。文件内容用文档 API 兜底写入，
     * 保证 CSV 编辑器打开时是完整内容。
     */
    private fun writeSnapshotTempFile(file: java.io.File, text: String): VirtualFile? = runCatching {
        com.intellij.openapi.application.WriteIntentReadAction.compute<VirtualFile?> {
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
            com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, false, file)
            LocalFileSystem.getInstance().findFileByIoFile(file)
        }
    }.getOrNull()

    /** dispose 清理：仍被编辑器打开的临时文件保留（避免删正在编辑的文件），其余删除。 */
    private fun cleanupSnapshotTempFiles() {
        val proj = project ?: return
        val manager = runCatching { FileEditorManager.getInstance(proj) }.getOrNull()
        snapshotTempFiles.forEach { file ->
            val open = runCatching {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(file)
                vf != null && manager?.isFileOpen(vf) == true
            }.getOrDefault(false)
            if (!open) runCatching { file.delete() }
        }
        snapshotTempFiles.clear()
    }

    /** 表格 ↔ 文本视图切换；从文本切表格时重新解析，解析失败留在文本视图并提示。 */
    private fun toggleSnapshotView() {
        if (snapshotTableShown) {
            snapshotTableShown = false
            (snapshotCards.layout as CardLayout).show(snapshotCards, SNAPSHOT_CARD_TEXT)
            snapshotToggle.text = MpSqlVisualizerBundle.message("rollback.snapshot.view.table")
            return
        }
        val parsed = RollbackSqlGenerator.parseSnapshotTsv(pasteArea.text ?: "")
        if (parsed == null) {
            warningLabel.text = "<html>${escapeHtml(
                MpSqlVisualizerBundle.message("rollback.snapshot.parseFailed"))}</html>"
            return
        }
        val (columns, rows) = parsed
        showSnapshotTable(columns, rows)
    }

    private fun assembleBanner() {
        val (textKey, color) = when {
            snapshotEntry?.status == RollbackSnapshotStore.Status.ROLLBACK_EXECUTED ->
                "rollback.banner.executed" to SUCCESS_COLOR
            currentResult.rating == RollbackSqlGenerator.Rating.COMPLETE ->
                "rollback.banner.complete" to SUCCESS_COLOR
            currentResult.rating == RollbackSqlGenerator.Rating.PARTIAL ->
                "rollback.banner.partial" to WARNING_COLOR
            else -> "rollback.banner.unable" to ERROR_COLOR
        }
        bannerLabel.text = MpSqlVisualizerBundle.message(textKey)
        bannerLabel.foreground = color
        warningLabel.text = warningsHtml(currentResult)
        warningLabel.foreground = JBColor.foreground()
    }

    private fun assembleSnapshotSection() {
        val sql = currentResult.snapshotSelectSql ?: return
        val shown = if (sql.length > SNAPSHOT_DISPLAY_LIMIT) sql.take(SNAPSHOT_DISPLAY_LIMIT) + "…" else sql
        snapshotQueryLabel.text = "<html><b>${MpSqlVisualizerBundle.message("rollback.snapshot.section")}</b> <code>$shown</code></html>"
        snapshotQueryLabel.toolTipText = sql
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    override fun createActions(): Array<Action> {
        val hasStatements = currentResult.statements.isNotEmpty()
        // UX Community 预判置灰（S2-4 券②）：启用态 = 有语句 && Database 插件在场
        executeAction.isEnabled = hasStatements && cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable()
        copyAction.isEnabled = hasStatements
        return arrayOf(executeAction, copyAction, cancelAction)
    }

    /** 「执行回滚」：经预览面板 executeAdhocSql 复用执行管线（DML 二次确认内置）。 */
    internal fun executeRollback() {
        val sql = currentResult.statements.joinToString("\n")
        if (sql.isBlank()) return
        panel?.executeAdhocSql(sql, rollbackOfEntry = snapshotEntry, onSubmission = rollbackSubmissionReceipt())
    }

    /** 每次请求仅结算一次；真实提交后保留业务留痕，关闭弹窗仅抑制迟到的 UI 更新。 */
    internal fun rollbackSubmissionReceipt(): (ExecutionSubmission) -> Unit {
        val completed = AtomicBoolean(false)
        val submittedResult = currentResult
        val store = project?.getService(RollbackSnapshotStore::class.java)
        return receipt@{ outcome ->
            if (!completed.compareAndSet(false, true) || outcome != ExecutionSubmission.SUBMITTED) return@receipt
            // SUBMITTED 来自底层 afterSubmitted，不代表数据库执行成功或事务已提交。
            snapshotEntry?.let { store?.markRollbackExecuted(it) }
            val updateBanner = {
                if (!isDisposed && currentResult === submittedResult) {
                    bannerLabel.text = MpSqlVisualizerBundle.message("rollback.banner.executed")
                    bannerLabel.foreground = SUCCESS_COLOR
                }
            }
            val app = ApplicationManager.getApplication()
            if (app.isDispatchThread) updateBanner()
            else app.invokeLater({ updateBanner() }, ModalityState.any())
        }
    }

    /** 「复制」：回滚 SQL 入剪贴板。 */
    internal fun copyRollback(): Boolean {
        val sql = currentResult.statements.joinToString("\n")
        if (sql.isBlank()) return false
        copyToClipboard(sql)
        return true
    }

    /**
     * 「执行快照查询」：只读旁路查询（T-PreviewSideQuery 通道，[McpDbExecutor] 只读链）——
     * **不调用** `executeAdhocSql`，因此不替换预览编辑器、不换入控制台（旧实现会把预览窗口
     * 切成执行视图且结果只在控制台网格，用户须手工复制粘贴，快照框看起来「没填充」）。
     * 结果自动填入快照框并按粘贴结果生成回滚；阻断/失败/空结果在对话框内提示，
     * 「复制快照 SQL」+ 手工粘贴路径保留为降级通道。
     */
    internal fun runSnapshotQuery() = runSnapshotQuery(allowRerenderRetry = true)

    /**
     * @param allowRerenderRetry 方言冲突时是否允许「按目标方言重渲染后重试」——重试只做一次，
     *        防止「渲染→仍不匹配→再渲染」循环。
     * @param sqlOverride 重渲染后按新预览文本重算出的快照 SELECT（提交用它；显示区回滚文本不动）
     */
    private fun runSnapshotQuery(allowRerenderRetry: Boolean, sqlOverride: String? = null) {
        val sql = sqlOverride ?: currentResult.snapshotSelectSql ?: return
        val p = panel ?: return
        val proj = p.project ?: return
        val target = snapshotQueryTargetForTest?.invoke() ?: runCatching {
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSource()
        }.getOrNull()
        if (target == null) {
            // 用户真机反馈：无数据源时不该只报「请先绑定」，应直接弹数据源选择器，绑定后自动重试
            //（与 DML 影响面预演/EXPLAIN 的 T-UI030 链路同款；只准备/绑定，不提交任何语句）。
            promptDataSourceThenRetrySnapshot(proj, sql)
            return
        }
        warningLabel.text = ""
        hideGateExitRow()
        // T-DialectGateExit：改用面板自己的闸请求——`canLosslesslyRerender` 由模型能力决定
        //（原实现写死 false，把本来能无损重渲染的路径直接判成阻断）。
        // 面板无当前预览时（headless 夹具/异常态）沿用旧口径的 MySQL 兜底，不因拿不到方言而误判冲突。
        val request = snapshotGateRequestForTest?.invoke()
            ?: p.current?.let {
                p.executionDialectGateRequest(cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Intent.QUERY)
            }
            ?: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request(
                renderMode = p.executionDialectRenderMode,
                renderedDialectName = cn.song.mybatisplus.visualizer.sql.MySqlDialect.name,
                intent = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Intent.QUERY,
                canLosslesslyRerender = false,
                userTextUntouched = true,
            )
        PreviewSideQuery.run(proj, target, sql, request, scalar = false, requireSelectGuard = true) { result ->
            when (result) {
                is PreviewSideQuery.Result.Table -> {
                    if (result.raw.rows.isEmpty()) {
                        warningLabel.text = "<html>${escapeHtml(
                            MpSqlVisualizerBundle.message("rollback.snapshot.emptyResult"))}</html>"
                        return@run
                    }
                    pasteArea.text = toSnapshotTsv(result.raw)
                    showSnapshotTable(result.raw.headers, result.raw.rows)
                    generateFromPaste()
                }

                is PreviewSideQuery.Result.Blocked -> {
                    warningLabel.text = "<html>${escapeHtml(result.message)}</html>"
                    showGateExitRowIfDialectConflict(result.verdict, allowRerenderRetry)
                }

                is PreviewSideQuery.Result.Failed ->
                    warningLabel.text = "<html>${escapeHtml(result.message)}</html>"

                is PreviewSideQuery.Result.Scalar ->
                    warningLabel.text = "<html>${escapeHtml(
                        MpSqlVisualizerBundle.message("rollback.snapshot.parseFailed"))}</html>"
            }
        }
    }

    /**
     * T-DialectGateExit：方言冲突时露出两个出口。
     *
     * - 「按目标方言重新渲染并重试」：手动选方言（MANUAL_CONFLICT）时不提供——闸门语义是插件不覆盖手动选择；
     *   其余冲突都提供，能否成功由 [SqlPreviewPanel.rerenderForExecutionTarget] 的模型能力决定。
     * - 「切换数据源」：任何方言冲突都提供（强制弹选择器，见 P0-3）。
     *
     * 只读护栏/目标未知等阻断不显示（那是另一类原因，重渲染与换目标都解决不了）。
     */
    private fun showGateExitRowIfDialectConflict(
        verdict: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Verdict?,
        allowRerenderRetry: Boolean,
    ) {
        if (verdict == null) return
        val conflict = when (verdict.status) {
            cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Status.AUTO_RERENDER_REQUIRED,
            cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
            cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Status.MANUAL_CONFLICT -> true
            else -> false
        }
        if (!conflict) return
        val targetDialect = verdict.targetDialectName
        blockedTargetDialect = targetDialect
        val canRerender = verdict.status !=
            cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Status.MANUAL_CONFLICT &&
            targetDialect != null && allowRerenderRetry
        rerenderToTargetButton.isVisible = canRerender
        rerenderToTargetButton.isEnabled = canRerender
        switchDataSourceButton.isVisible = true
        switchDataSourceButton.isEnabled = true
        gateExitRow.isVisible = true
        gateExitRow.revalidate()
        gateExitRow.repaint()
    }

    private fun hideGateExitRow() {
        blockedTargetDialect = null
        gateExitRow.isVisible = false
    }

    /** 「按目标方言重新渲染并重试」：成功则重建请求重跑一次；失败就地说明原因（不冒充成功）。 */
    internal fun rerenderToTargetAndRetry() {
        val targetDialect = blockedTargetDialect ?: return
        val p = panel ?: return
        val rerender = snapshotRerenderForTest ?: { name -> p.rerenderForExecutionTarget(name) }
        val ok = runCatching { rerender(targetDialect) }.getOrDefault(false)
        if (!ok) {
            warningLabel.text = "<html>${escapeHtml(
                MpSqlVisualizerBundle.message("rollback.snapshot.rerender.failed"))}</html>"
            return
        }
        hideGateExitRow()
        // 重渲染会改写预览文本，快照 SELECT 可能带旧方言的标识符引用 → 按新文本重算后再提交。
        runSnapshotQuery(allowRerenderRetry = false, sqlOverride = refreshedSnapshotSql())
    }

    /**
     * 按重渲染后的预览文本重算快照 SELECT（主键列沿用对话框当前输入）。
     *
     * 快照 SELECT 由 [RollbackSqlGenerator] 从语句 AST 拼出，标识符引用跟随输入文本的方言——
     * 不重算就可能把旧方言的引用样式提交给新目标。重算失败（无法解析）时回退原文本，不阻断重试。
     */
    private fun refreshedSnapshotSql(): String? {
        val p = panel ?: return currentResult.snapshotSelectSql
        val newSql = p.currentEditorSql() ?: return currentResult.snapshotSelectSql
        val pkColumns = pkField.text.orEmpty()
            .split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
        val regenerated = runCatching {
            RollbackSqlGenerator.generate(newSql, primaryKeyColumns = pkColumns)
        }.getOrNull()
        return regenerated?.snapshotSelectSql ?: currentResult.snapshotSelectSql
    }

    /** 测试访问器：出口行当前是否可见（方言冲突时应为 true）。 */
    internal fun gateExitRowVisibleForTest(): Boolean = gateExitRow.isVisible

    /** 测试访问器：两个出口按钮（可见性/启用态断言用）。 */
    internal fun rerenderButtonForTest(): JButton = rerenderToTargetButton

    internal fun switchSourceButtonForTest(): JButton = switchDataSourceButton

    /** 结果网格 → TSV（首行列名；单元格内制表/换行折叠为空格，与手工复制口径一致）。 */
    private fun toSnapshotTsv(
        raw: cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult,
    ): String = buildString {
        append(raw.headers.joinToString("\t"))
        raw.rows.forEach { row ->
            append('\n')
            append(row.joinToString("\t") { cell ->
                cell.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
            })
        }
    }

    /**
     * 无绑定数据源：弹数据源选择器（只准备/绑定，不提交任何语句），绑定成功后自动重试快照查询；
     * 取消/超时收口为原有说明文案。链路与 DML 影响面预演 / EXPLAIN 的 T-UI030 同款，
     * 代际沿用面板 `explainStructuredGeneration`（预览切换即停表，避免晚到重试打到新预览）。
     */
    /**
     * @param forceChoose T-DialectGateExit：已绑定但方言冲突时，强制弹选择器（跳过 last-used 静默命中
     *                    与共享控制台复用）——否则「切换数据源」点了等于没点。
     */
    private fun promptDataSourceThenRetrySnapshot(proj: Project, sql: String, forceChoose: Boolean = false) {
        val p = panel ?: return
        warningLabel.text = ""
        val errorShown = java.util.concurrent.atomic.AtomicBoolean(false)
        if (cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.shouldPromptForDataSource(
                cn.song.mybatisplus.visualizer.execute.DataSourceSelector.isPrepareInFlight(),
            )
        ) {
            val prompt = snapshotPromptForTest
                ?: { project: Project, s: String, onError: (String) -> Unit ->
                    cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prepareConsole(
                        project, s, onError = onError, forceChoose = forceChoose,
                    )
                }
            runCatching {
                prompt(proj, sql) { message ->
                    errorShown.set(true)
                    warningLabel.text = "<html>${escapeHtml(message)}</html>"
                }
            }.onFailure { e ->
                errorShown.set(true)
                warningLabel.text = "<html>${escapeHtml(e.message ?: e.javaClass.simpleName)}</html>"
            }
        }
        val await = snapshotAwaitBindForTest
            ?: { project: Project, gen: Int, onBound: () -> Unit, onGiveUp: () -> Unit ->
                p.awaitDataSourceBoundThen(project, gen, onBound, onGiveUp)
            }
        await(
            proj,
            p.explainStructuredGeneration,
            { runSnapshotQuery() },
            {
                if (!errorShown.get()) {
                    warningLabel.text = "<html>${escapeHtml(
                        MpSqlVisualizerBundle.message("rollback.snapshot.noDataSource"))}</html>"
                }
            },
        )
    }

    private fun snapshotInputChanged() {
        if (snapshotGenerationStarted) invalidateSnapshotResult()
    }

    /** 清空执行/复制共用的模型和编辑器，不能仅禁用按钮或退回可能可执行的原始结果。 */
    private fun invalidateSnapshotResult() {
        currentResult = result.copy(
            rating = RollbackSqlGenerator.Rating.UNABLE,
            statements = emptyList(),
            countCheckSql = null,
        )
        refreshAfterPaste()
    }

    /** 「生成回滚」：粘贴 TSV → 快照回填（按原语句类型分派）→ 刷新编辑器与横幅。 */
    internal fun generateFromPaste() {
        if (!hasSnapshotWorkflow()) return
        snapshotGenerationStarted = true
        // 每次尝试先撤销上次产物；解析失败、生成失败乃至异常都不得保留旧 SQL。
        invalidateSnapshotResult()
        val table = result.targetTable ?: return
        val parsed = RollbackSqlGenerator.parseSnapshotTsv(pasteArea.text ?: "")
        if (parsed == null) {
            warningLabel.text = "<html>${escapeHtml(MpSqlVisualizerBundle.message("rollback.snapshot.parseFailed"))}</html>"
            return
        }
        val (columns, rows) = parsed
        // T-RollbackSnapshotView：手动粘贴成功后也切到表格视图（列值对应一目了然）。
        showSnapshotTable(columns, rows)
        val pk = pkField.text?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
        val mode = when (statementKind.uppercase()) {
            "UPDATE" -> RollbackSqlGenerator.SnapshotMode.REVERT_UPDATE
            "DELETE" -> RollbackSqlGenerator.SnapshotMode.BACKFILL_INSERT
            else -> RollbackSqlGenerator.SnapshotMode.REVERT_INSERT
        }
        val generated = RollbackSqlGenerator.generateFromSnapshotRows(
            table = table,
            columns = columns,
            rows = rows,
            mode = mode,
            primaryKeyColumns = pk,
            changedColumns = result.setColumns,
            newValuesByColumn = result.setValues,
            // O-52（D1=A）：identity 知识随快照条目留存，此处取回传给生成器——条目未持有
            // （旧条目/未传参的登记点）时保持 null/空 → 不包装，与改前逐字节相同
            dialect = snapshotEntry?.dialect,
            // O-59 T-IIR-02：identity 列**在消费点解析**（而非登记点）。登记点位于执行前调用链上，
            // 在那里同步走 DAS 模型遍历有 EDT 慢操作断言风险（O-59 计划 §六 风险表首条即点名）；
            // 本对话框由用户点击动作打开，且 [DatabaseSchemaResolver] 本身是缓存优先 + 有界扫描的
            // UI 面，在此解析既避开执行链、又复用既有缓存。条目已带非空清单时优先用条目。
            identityColumns = snapshotEntry?.identityColumns?.takeIf { it.isNotEmpty() }
                ?: autoGeneratedColumnsOf(table),
        )
        if (generated.rating == RollbackSqlGenerator.Rating.UNABLE) {
            warningLabel.text = warningsHtml(generated)
            return
        }
        // 生成结果不携带原语句的快照模板/SET 元信息；保留它们以支持再次生成与快照查询。
        currentResult = generated.copy(
            whereText = result.whereText,
            setColumns = result.setColumns,
            setValues = result.setValues,
            snapshotSelectSql = result.snapshotSelectSql,
        )
        refreshAfterPaste()
    }

    /**
     * O-59 T-IIR-02：按表名解析该表的**自增列**（`DasColumn.Attribute.AUTO_GENERATED`）。
     *
     * 取不到（无数据源 / 模型未内省 / 表不在模型里 / 反射链异常）一律返回**空清单 = 不包装**——
     * 与 O-52 D1=A 的「未知不臆造」契约一致（宁可少包装，也不误包装）。
     * 解析走 [DatabaseSchemaResolver.columnMeta]（缓存优先；扫描预算与负缓存口径见该类 KDoc）。
     */
    private fun autoGeneratedColumnsOf(table: String?): List<String> {
        val name = table?.takeIf { it.isNotBlank() } ?: return emptyList()
        val proj = project ?: return emptyList()
        return runCatching {
            DatabaseSchemaResolver.columnMeta(proj, name)
                .filterValues { it.autoGenerated == true }
                .keys.toList()
        }.getOrElse { emptyList() }
    }

    /** 粘贴生成成功后的视图刷新（编辑器内容 + 横幅 + 底部动作启用态）。 */
    private fun refreshAfterPaste() {
        val text = currentResult.statements.joinToString("\n")
        editor?.document?.let { doc ->
            if (doc.text != text) {
                // 文档写入须写操作语境（测试实证：裸 setText 在平台断言下硬抛）
                val proj = project
                if (proj != null) {
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(proj) { doc.setText(text) }
                } else {
                    doc.setText(text)
                }
            }
        }
        assembleBanner()
        val hasStatements = currentResult.statements.isNotEmpty()
        // UX Community 预判置灰（S2-4 券②）：与 createActions 同判据
        executeAction.isEnabled = hasStatements && cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable()
        copyAction.isEnabled = hasStatements
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun countCheckHtml(): String =
        "<html><span style='color:gray'>${escapeHtml(MpSqlVisualizerBundle.message("rollback.countcheck.hint"))}</span> " +
            "<code>${escapeHtml(currentResult.countCheckSql ?: "")}</code></html>"

    private fun warningsHtml(r: RollbackSqlGenerator.RollbackResult): String {
        if (r.warnings.isEmpty()) return ""
        val items = r.warnings.joinToString("") { warning ->
            val text = if (warning.messageArgs.isEmpty()) {
                MpSqlVisualizerBundle.message(warning.messageKey)
            } else {
                MpSqlVisualizerBundle.message(warning.messageKey, *warning.messageArgs.toTypedArray())
            }
            "<li>${escapeHtml(text)}</li>"
        }
        return "<html><ul style='margin-left:12px'>$items</ul></html>"
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    private fun releaseEditor() {
        editor?.let {
            EditorFactory.getInstance().releaseEditor(it)
            editor = null
        }
    }

    private fun sqlFileType(): com.intellij.openapi.fileTypes.FileType =
        com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL")

    override fun getDimensionServiceKey(): String = DIMENSION_KEY

    /** T-RollbackSql：程序化关闭（同 SqlPreviewDialog.doDispose 惯例；测试 tearDown 用）。 */
    fun doDispose() {
        dispose()
    }

    /** 释放自建编辑器（DialogWrapper 不感知 EditorFactory 产物，须自行兜底）。 */
    override fun dispose() {
        releaseEditor()
        // T-RollbackSnapshotCsvView B：临时 TSV 随弹窗回收（仍被编辑器打开的保留）。
        // 放在 dispose() 而非 Disposer 回调——dispose 是确定的收口点，便于测试断言。
        cleanupSnapshotTempFiles()
        super.dispose()
    }

    // ------------------------------------------------------------------
    // 测试访问（同模块测试使用，仿 SqlPreviewPanel 访问器惯例）
    // ------------------------------------------------------------------

    internal fun bannerTextForTest(): String = bannerLabel.text
    internal fun warningsTextForTest(): String = warningLabel.text
    internal fun sqlTextForTest(): String = editor?.document?.text ?: ""

    /** 测试访问器：编辑器实例（T-SqlCopyShortcuts：键盘复制接线断言用）。 */
    internal fun editorForTest(): com.intellij.openapi.editor.Editor? = editor
    internal fun pasteAreaForTest(): JBTextArea = pasteArea
    internal fun snapshotTableForTest(): JBTable = snapshotTable
    internal fun snapshotSummaryTextForTest(): String = snapshotSummary.text
    internal fun snapshotTableShownForTest(): Boolean = snapshotTableShown
    internal fun snapshotToggleForTest(): JButton = snapshotToggle
    internal fun snapshotOpenCsvForTest(): JButton = snapshotOpenCsv
    internal fun snapshotAutofitForTest(): JButton = snapshotAutofit
    internal fun copySnapshotSelectionForTest(): Boolean = copySnapshotSelection()
    internal fun autoFitSnapshotColumnsForTest() = autoFitSnapshotColumns()
    internal fun openSnapshotInCsvEditorForTest() = openSnapshotInCsvEditor()
    internal fun snapshotTempFilesForTest(): List<java.io.File> = snapshotTempFiles.toList()
    internal fun snapshotRowHeaderForTest(): JBList<String> = snapshotRowHeader
    internal fun centerPanelForTest(): JComponent = snapshotCenterPanel ?: createCenterPanel()
    internal fun pkFieldForTest(): JBTextField = pkField
    internal fun executeActionForTest(): Action = executeAction
    internal fun copyActionForTest(): Action = copyAction
    internal fun snapshotQueryLabelForTest(): JBLabel = snapshotQueryLabel

    /** 测试访问器：「执行快照查询」按钮（S2-4 券② Community 置灰断言；无快照工作流时为 null）。 */
    internal fun snapshotRunButtonForTest(): JButton? = snapshotRunButton

    companion object {
        /** 尺寸记忆 key。 */
        const val DIMENSION_KEY = "cn.song.mybatisplus.visualizer.RollbackSqlDialog"

        private const val SNAPSHOT_CARD_TABLE = "snapshot-table"
        private const val SNAPSHOT_CARD_TEXT = "snapshot-text"

        private const val DIALOG_WIDTH = 720
        private const val DIALOG_HEIGHT = 460
        private const val EDITOR_HEIGHT = 180

        /** 快照 SQL 行内展示截断长度（全文在 tooltip）。 */
        private const val SNAPSHOT_DISPLAY_LIMIT = 80

        private val SUCCESS_COLOR = JBColor.namedColor("RollbackSql.complete", java.awt.Color(0x2E7D32))
        private val WARNING_COLOR = JBColor.namedColor("RollbackSql.partial", java.awt.Color(0xB26A00))
        private val ERROR_COLOR = JBColor.namedColor("ErrorForeground", JBColor.RED)
    }
}
