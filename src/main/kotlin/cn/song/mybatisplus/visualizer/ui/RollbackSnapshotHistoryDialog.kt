package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotAligner
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotProjectStore
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotRollbackSql
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.diff.DiffManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * T-RollbackSnapshotPreExec P4：快照历史对话框（「更多」菜单「回滚快照历史」入口）。
 *
 * 功能面（v1）：列表（时间/类型/表/行数/状态）+ 查看对比（有改后数据的条目开原生 diff
 * 窗口）+ 生成回滚 SQL（只读展示 + 复制；执行仍走用户既有链——把 SQL 贴进预览/控制台，
 * 那里有完整的确认弹窗/方言闸/写安全门，本对话框不做旁路执行）+ 删除。
 *
 * 刻意不做：无面板上下文的旁路执行链（executeAdhocSql 挂在预览面板上）——从历史直接执行
 * 需要另起临时执行链，登记为后续项（计划 §3.7 的「事务内跑/报影响行数」由既有回滚执行链
 * 承载，v1 生成文本后由该链消费）。
 *
 * UI 形态照抄既有先例：DialogWrapperAction = 平台 `DialogWrapper.DialogWrapperAction`
 * 内嵌类（RollbackSqlDialog 同款），禁自造抽象基类。
 */
class RollbackSnapshotHistoryDialog(private val project: Project) : DialogWrapper(project) {

    private val store: RollbackSnapshotProjectStore = project.getService(RollbackSnapshotProjectStore::class.java)
    private val table: JTable
    private val model: DefaultTableModel

    init {
        title = MpSqlVisualizerBundle.message("rollback.history.title")
        isModal = false
        model = object : DefaultTableModel(
            arrayOf(
                MpSqlVisualizerBundle.message("rollback.history.col.time"),
                MpSqlVisualizerBundle.message("rollback.history.col.mode"),
                MpSqlVisualizerBundle.message("rollback.history.col.table"),
                MpSqlVisualizerBundle.message("rollback.history.col.rows"),
                MpSqlVisualizerBundle.message("rollback.history.col.status"),
            ),
            0,
        ) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        table = JTable(model)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        reload()
        init()
    }

    private fun selectedId(): String? {
        val row = table.selectedRow
        if (row < 0) return null
        return model.getValueAt(table.convertRowIndexToModel(row), 0) as? String
    }

    private fun reload() {
        model.rowCount = 0
        for (entry in store.listEntries()) {
            model.addRow(
                arrayOf(
                    formatTime(entry.createdAtMs),
                    entry.mode,
                    entry.table,
                    entry.rowCount.toString(),
                    entry.status,
                ),
            )
        }
    }

    private fun formatTime(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(ms))

    /** 查看对比：有改后数据才开 diff 窗口；仅改前给出状态说明。 */
    private fun openDiff() {
        val id = selectedId() ?: return
        val record = store.load(id) ?: return
        val afterRows = record.afterRows
        if (afterRows == null) {
            showInfo(MpSqlVisualizerBundle.message("rollback.history.noAfterImage"))
            return
        }
        val keyIndexes = RollbackSnapshotAligner.guessKeyColumnIndexes(record.columns, record.table)
        val aligned = RollbackSnapshotAligner.align(record.columns, record.beforeRows, afterRows, keyIndexes)
        val request = cn.song.mybatisplus.visualizer.ui.diff.RollbackTableDiffRequest(
            diffTitle = MpSqlVisualizerBundle.message("rollback.history.diffTitle", record.table),
            beforeTitle = MpSqlVisualizerBundle.message("rollback.history.beforeTitle"),
            afterTitle = MpSqlVisualizerBundle.message("rollback.history.afterTitle"),
            columnNames = record.columns,
            alignedRows = aligned.rows,
            alignmentWarnings = aligned.warnings,
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    /** 生成回滚 SQL：只读对话框展示 + 复制按钮（执行走用户既有链，见类 KDoc）。 */
    private fun generateRollbackSql() {
        val id = selectedId() ?: return
        val record = store.load(id) ?: return
        val keyIndexes = RollbackSnapshotAligner.guessKeyColumnIndexes(record.columns, record.table)
        val built = when (record.mode) {
            "DELETE" -> RollbackSnapshotRollbackSql.buildDeleteRollback(
                record.table, record.columns, record.beforeRows,
                keyIndexes.mapNotNull { record.columns.getOrNull(it) },
            )
            else -> {
                val afterRows = record.afterRows
                if (afterRows == null) {
                    showInfo(MpSqlVisualizerBundle.message("rollback.history.noAfterImage"))
                    return
                }
                val aligned = RollbackSnapshotAligner.align(record.columns, record.beforeRows, afterRows, keyIndexes)
                RollbackSnapshotRollbackSql.buildUpdateRollback(record.table, record.columns, keyIndexes, aligned.rows)
            }
        }
        if (built.sqlText.isBlank()) {
            showInfo(MpSqlVisualizerBundle.message("rollback.history.noRollbackSql"))
            return
        }
        RollbackSqlTextDialog(project, built.sqlText).show()
    }

    private fun deleteSelected() {
        val id = selectedId() ?: return
        store.deleteEntry(id)
        reload()
    }

    private fun showInfo(message: String) {
        com.intellij.openapi.ui.Messages.showInfoMessage(project, message, title)
    }

    override fun createCenterPanel(): JComponent {
        val scroll = javax.swing.JScrollPane(table)
        scroll.preferredSize = JBUI.size(640, 380)
        return JPanel(BorderLayout()).apply { add(scroll, BorderLayout.CENTER) }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    /** 历史对话框的左侧动作组（刷新/对比/回滚 SQL/删除）。 */
    override fun createLeftSideActions(): Array<Action> = arrayOf(
        action("rollback.history.action.refresh") { reload() },
        action("rollback.history.action.diff") { openDiff() },
        action("rollback.history.action.rollbackSql") { generateRollbackSql() },
        action("rollback.history.action.delete") { deleteSelected() },
    )

    private fun action(textKey: String, body: () -> Unit): Action =
        object : DialogWrapper.DialogWrapperAction(MpSqlVisualizerBundle.message(textKey)) {
            override fun doAction(e: ActionEvent?) {
                body()
            }
        }

    /** 只读 SQL 展示 + 复制（轻量形态；不接面板执行链）。 */
    private class RollbackSqlTextDialog(project: Project, sqlText: String) : DialogWrapper(project) {
        private val textArea = com.intellij.ui.components.JBTextArea(sqlText).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }

        init {
            title = MpSqlVisualizerBundle.message("rollback.history.sqlTitle")
            isModal = true
            init()
        }

        override fun createCenterPanel(): JComponent {
            val scroll = javax.swing.JScrollPane(textArea)
            scroll.preferredSize = JBUI.size(720, 420)
            return scroll
        }

        override fun createActions(): Array<Action> = arrayOf(
            object : DialogWrapper.DialogWrapperAction(MpSqlVisualizerBundle.message("rollback.history.action.copy")) {
                override fun doAction(e: ActionEvent?) {
                    java.awt.datatransfer.StringSelection(textArea.text).let {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(it, it)
                    }
                    close(OK_EXIT_CODE)
                }
            },
            okAction,
        )
    }
}
