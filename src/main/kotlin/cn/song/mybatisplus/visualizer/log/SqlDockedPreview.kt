package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class SqlDockedPreview(
    private val project: Project,
    private val list: JList<LogSqlEntry>,
    private val onClose: () -> Unit,
) : Disposable {
    val component = JPanel(BorderLayout())
    private val status = JLabel(MpSqlVisualizerBundle.message("log.dockedPreview.empty"))
    private val restore = JButton(MpSqlVisualizerBundle.message("log.dockedPreview.restore")).apply {
        isVisible = false
        addActionListener { restoreAndFollow() }
    }
    private var content: SqlClickPreviewContent? = null
    private var displayedEntry: LogSqlEntry? = null
    private var originalSql: String? = null
    private var updating = false
    private var opened = false

    val isOpen: Boolean get() = opened
    internal val displayedSqlForTest: String? get() = content?.editor?.document?.text?.takeIf { displayedEntry != null }
    internal val editorForTest: com.intellij.openapi.editor.Editor? get() = content?.editor
    internal val isPinnedForTest: Boolean get() = isPinned()

    /** T-PerfSweep26-05（F98）测试缝：isPinned() 调用计数（内部含 document.text 全文读）——
     *  同一条目展示中二次事件经同一性短路后不得再增长。 */
    internal var isPinnedCallCountForTest: Int = 0
        private set
    internal fun restoreAndFollowForTest() = restoreAndFollow()

    init {
        component.preferredSize = java.awt.Dimension(200, 220)
        component.add(JPanel(BorderLayout()).apply {
            add(status, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { add(restore) }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        component.add(JLabel(MpSqlVisualizerBundle.message("log.dockedPreview.empty"), SwingConstants.CENTER), BorderLayout.CENTER)
        list.addListSelectionListener { if (!it.valueIsAdjusting) refreshSelection() }
        list.model.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent) = refreshSelection()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent) = refreshSelection()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent) = refreshSelection()
        })
    }

    fun open() {
        opened = true
        refreshSelection()
    }

    fun close() {
        opened = false
    }

    fun resetContext() {
        displayedEntry = null
        originalSql = null
        restore.isVisible = false
        val editor = content?.editor
        if (editor != null && editor.document.text.isNotEmpty()) {
            updating = true
            try {
                WriteCommandAction.runWriteCommandAction(project) { editor.document.setText("") }
            } finally {
                updating = false
            }
        }
        status.text = MpSqlVisualizerBundle.message("log.dockedPreview.empty")
        showCenter(JLabel(status.text, SwingConstants.CENTER))
    }

    fun refreshSelection() {
        if (!opened) return
        // T-PerfSweep26-05（F98）：同一条目已展示且仍是唯一选中 → 走到下方既有 `displayedEntry === entry`
        // 处本来就会原样返回，pin 态重算无任何旁路影响——提前返回，免付 isPinned() 的
        // document.text 全文拷贝（流式灌入每拍 ListDataEvent 都进这里）。两道守卫保证与原语义逐字
        // 等价：displayedEntry==null 不得短路（否则 null===null 命中，空态/多条选中态的状态文案
        // 不再刷新）；多选（selectedIndices.size!=1）不得短路（原路径会把预览清掉改显「已选多条」）。
        if (displayedEntry != null && list.selectedIndices.size == 1 && displayedEntry === list.selectedValue) return
        if (isPinned()) return
        val entry = list.selectedValue?.takeIf { list.selectedIndices.size == 1 }
        if (entry == null) {
            displayedEntry = null
            originalSql = null
            status.text = MpSqlVisualizerBundle.message(
                if (list.selectedIndices.size > 1) "log.dockedPreview.multiple" else "log.dockedPreview.empty",
            )
            showCenter(JLabel(status.text, SwingConstants.CENTER))
            return
        }
        if (displayedEntry === entry) return
        val state = PluginSettings.getInstance().state
        // T-MagpieGems W2：停靠预览按脱敏模式全语义渲染（WARN 原文+警示行、BLOCK 阻断占位）
        val maskMode = PluginSettings.getInstance().displayMaskMode()
        val allowList = PluginSettings.getInstance().displayMaskAllowList()
        val sql = clickPreviewSql(entry.restoredSql, state.logFormatSql, maskMode, allowList)
        val current = content ?: SqlClickPreview.buildContent(project, sql, copyText = {
            // T-MagpieGems W2：复制是离开 IDE 面——mode != OFF 从严按 MASK 打码（WARN 不给原文离开产品）。
            // UI-001 修复：白名单豁免与浮层屏显同口径（屏显已豁免的值复制不再回打）。
            cn.song.mybatisplus.visualizer.util.UiTextClipboard.copy(previewCopySafeText(it, maskMode, allowList))
        }, close = onClose).also { built ->
            content = built
            built.editor.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = updatePinnedStatus()
            })
        }
        if (current.editor.document.text != sql) {
            updating = true
            try {
                WriteCommandAction.runWriteCommandAction(project) { current.editor.document.setText(sql) }
            } finally {
                updating = false
            }
        }
        displayedEntry = entry
        originalSql = sql
        status.text = MpSqlVisualizerBundle.message("log.dockedPreview.title")
        restore.isVisible = false
        showCenter(current.component)
    }

    private fun isPinned(): Boolean {
        isPinnedCallCountForTest++ // F98 测试缝
        return displayedEntry != null &&
            content?.editor?.document?.text != originalSql
    }

    private fun updatePinnedStatus() {
        if (updating || displayedEntry == null) return
        val pinned = isPinned()
        status.text = MpSqlVisualizerBundle.message(
            if (pinned) "log.dockedPreview.pinned" else "log.dockedPreview.title",
        )
        restore.isVisible = pinned
    }

    private fun restoreAndFollow() {
        val original = originalSql ?: return
        val editor = content?.editor ?: return
        updating = true
        try {
            WriteCommandAction.runWriteCommandAction(project) { editor.document.setText(original) }
        } finally {
            updating = false
        }
        restore.isVisible = false
        refreshSelection()
        updatePinnedStatus()
    }

    private fun showCenter(next: java.awt.Component) {
        (component.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)?.let { component.remove(it) }
        component.add(next, BorderLayout.CENTER)
        component.revalidate()
        component.repaint()
    }

    override fun dispose() {
        content?.editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        content = null
    }
}
