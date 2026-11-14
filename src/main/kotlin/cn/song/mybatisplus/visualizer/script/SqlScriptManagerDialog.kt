package cn.song.mybatisplus.visualizer.script

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.mcp.McpDbExecutor
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.TableModelEvent
import javax.swing.event.TableModelListener
import javax.swing.table.DefaultTableModel

/**
 * T-RollbackSnapshotPreExec P7：脚本管理对话框（设置页「脚本管理」按钮入口；语句级 tab 仅在
 * 携带预览面板上下文时可用）。
 *
 * 三档作用域（D-2 全部级）：全局级（APP，PluginSettings）/ 数据源级（项目，SqlScriptProjectState）
 * / 语句级（预览面板生命周期，SqlScriptStatementScope 不持久化——改档已入计划）。
 * 变量表（D-3 显式）与脚本日志区在第四/五 tab。
 *
 * 编辑器 v1 用 JBTextArea（等宽字体）；CoolRequest 的「紧凑 JavaEditorTextField + 弹窗大编辑器」
 * 形态登记为后续打磨项（计划 §3.8），不影响功能面。
 */
class SqlScriptManagerDialog private constructor(
    private val project: Project,
    private val panelKey: Any?,
) : DialogWrapper(project) {

    private val scriptState = project.getService(SqlScriptProjectState::class.java)
    private val settings: cn.song.mybatisplus.visualizer.config.PluginSettings.State =
        PluginSettings.getInstance().state

    private val dataSources: List<String> = runCatching {
        McpDbExecutor.listDataSourceNames(project)
    }.getOrDefault(emptyList())

    private val preEditors = HashMap<String, javax.swing.JTextArea>()
    private val postEditors = HashMap<String, javax.swing.JTextArea>()
    private val logArea = com.intellij.ui.components.JBTextArea().apply {
        isEditable = false
        rows = 8
    }

    /** 脚本日志汇聚点（编排层经 [appendLog] 写入）。 */
    companion object {
        /** 语句级作用域键：当前对话框服务的预览面板（null = 无面板上下文）。 */
        internal var lastPanelKeyForTest: Any? = null

        fun show(project: Project, panelKey: Any? = null) {
            SqlScriptManagerDialog(project, panelKey).show()
        }

        /** 脚本日志的进程内汇聚（P7 简化：环形缓冲，管理对话框打开时可见；不落盘）。 */
        internal val scriptLogBuffer = ArrayDeque<String>()

        internal fun appendLog(line: String) {
            synchronized(scriptLogBuffer) {
                scriptLogBuffer.addLast(line)
                while (scriptLogBuffer.size > 200) scriptLogBuffer.removeFirst()
            }
        }

        private const val SCOPE_GLOBAL = "__global__"
        private const val SCOPE_STATEMENT = "__statement__"
    }

    init {
        title = MpSqlVisualizerBundle.message("script.manager.title")
        isModal = false
        init()
    }

    private fun editor(text: String): javax.swing.JTextArea =
        com.intellij.ui.components.JBTextArea(text).apply {
            rows = 10
            font = java.awt.Font("monospaced", java.awt.Font.PLAIN, 13)
        }

    private fun scopePanel(pre: javax.swing.JTextArea, post: javax.swing.JTextArea): JComponent {
        val splitter = JBSplitter(true, 0.5f)
        splitter.firstComponent = labeledPane(MpSqlVisualizerBundle.message("script.manager.pre"), pre)
        splitter.secondComponent = labeledPane(MpSqlVisualizerBundle.message("script.manager.post"), post)
        return splitter
    }

    private fun labeledPane(title: String, inner: JComponent): JPanel {
        val p = JPanel(BorderLayout())
        val label = javax.swing.JLabel(title)
        label.border = JBUI.Borders.empty(4, 8)
        p.add(label, BorderLayout.NORTH)
        p.add(javax.swing.JScrollPane(inner), BorderLayout.CENTER)
        return p
    }

    override fun createCenterPanel(): JComponent {
        val tabs = JBTabbedPane()

        // 全局级（PluginSettings 直存——apply 时写回）
        val preGlobal = editor(settings.sqlScriptPreGlobal)
        val postGlobal = editor(settings.sqlScriptPostGlobal)
        preEditors[SCOPE_GLOBAL] = preGlobal
        postEditors[SCOPE_GLOBAL] = postGlobal
        tabs.addTab(MpSqlVisualizerBundle.message("script.manager.scope.global"), scopePanel(preGlobal, postGlobal))

        // 数据源级（SqlScriptProjectState）
        val dsTabs = JBTabbedPane()
        for (ds in dataSources) {
            val pre = editor(scriptState.preScriptFor(ds))
            val post = editor(scriptState.postScriptFor(ds))
            preEditors[ds] = pre
            postEditors[ds] = post
            dsTabs.addTab(ds, scopePanel(pre, post))
        }
        if (dataSources.isEmpty()) {
            dsTabs.addTab(MpSqlVisualizerBundle.message("script.manager.noDataSource"), JPanel())
        }
        tabs.addTab(MpSqlVisualizerBundle.message("script.manager.scope.dataSource"), dsTabs)

        // 语句级（预览面板生命周期；无面板上下文时禁用）
        if (panelKey != null) {
            val pre = editor(SqlScriptStatementScope.preScriptFor(panelKey))
            val post = editor(SqlScriptStatementScope.postScriptFor(panelKey))
            preEditors[SCOPE_STATEMENT] = pre
            postEditors[SCOPE_STATEMENT] = post
            tabs.addTab(
                MpSqlVisualizerBundle.message("script.manager.scope.statement"),
                scopePanel(pre, post),
            )
        } else {
            val disabled = JPanel(BorderLayout()).apply {
                add(javax.swing.JLabel(MpSqlVisualizerBundle.message("script.manager.statementNeedsPanel")), BorderLayout.CENTER)
            }
            tabs.addTab(MpSqlVisualizerBundle.message("script.manager.scope.statement"), disabled)
        }

        // 变量表（D-3 显式）
        tabs.addTab(MpSqlVisualizerBundle.message("script.manager.scope.vars"), varsPanel())

        // 脚本日志区
        logArea.text = synchronized(scriptLogBuffer) { scriptLogBuffer.joinToString("\n") }
        tabs.addTab(MpSqlVisualizerBundle.message("script.manager.scope.logs"), javax.swing.JScrollPane(logArea))

        return tabs.apply { preferredSize = Dimension(820, 560) }
    }

    private fun varsPanel(): JComponent {
        val vars = scriptState.vars().toList().sortedBy { it.first }
        val model = object : DefaultTableModel(vars.map { arrayOf(it.first, it.second) }.toTypedArray(),
            arrayOf(MpSqlVisualizerBundle.message("script.manager.col.name"), MpSqlVisualizerBundle.message("script.manager.col.value")),
        ) {
            override fun isCellEditable(row: Int, column: Int): Boolean = true
        }
        model.addTableModelListener { e: TableModelEvent ->
            if (e.type == TableModelEvent.UPDATE || e.type == TableModelEvent.INSERT || e.type == TableModelEvent.DELETE) {
                val map = (0 until model.rowCount).mapNotNull { r ->
                    val k = model.getValueAt(r, 0)?.toString()?.trim().orEmpty()
                    val v = model.getValueAt(r, 1)?.toString() ?: ""
                    if (k.isEmpty()) null else k to v
                }.toMap()
                scriptState.setVars(map)
            }
        }
        val table = com.intellij.ui.table.JBTable(model)
        val panel = JPanel(BorderLayout())
        panel.add(javax.swing.JScrollPane(table), BorderLayout.CENTER)
        panel.add(
            javax.swing.JLabel(MpSqlVisualizerBundle.message("script.manager.vars.hint")),
            BorderLayout.SOUTH,
        )
        return panel
    }

    override fun doOKAction() {
        // 全局级写回 PluginSettings（对话框直写 State，设置面板只管总开关）
        settings.sqlScriptPreGlobal = preEditors[SCOPE_GLOBAL]?.text ?: ""
        settings.sqlScriptPostGlobal = postEditors[SCOPE_GLOBAL]?.text ?: ""
        PluginSettings.getInstance().loadState(settings.copy())
        // 数据源级写回项目态
        for (ds in dataSources) {
            scriptState.setPreScriptFor(ds, preEditors[ds]?.text ?: "")
            scriptState.setPostScriptFor(ds, postEditors[ds]?.text ?: "")
        }
        // 语句级写回面板作用域
        if (panelKey != null) {
            SqlScriptStatementScope.setPreScript(panelKey, preEditors[SCOPE_STATEMENT]?.text ?: "")
            SqlScriptStatementScope.setPostScript(panelKey, postEditors[SCOPE_STATEMENT]?.text ?: "")
        }
        super.doOKAction()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)
}
