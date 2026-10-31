package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.share.SqlSchemaSnapshotCodec
import cn.song.mybatisplus.visualizer.sql.insight.SqlSchemaDiffer
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailPanel
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailRow
import cn.song.mybatisplus.visualizer.ui.insight.boundedTextArea
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.*

internal fun SqlPreviewPanel.showSchemaComparison() {
    if (previewDisposed) return
    val existing = previewTabs.component("schema-comparison")
    if (existing != null) { previewTabs.pane?.selectedComponent = existing; return }
    val before = boundedTextArea(1024 * 1024)
    val after = boundedTextArea(1024 * 1024)
    val results = InsightDetailPanel(listOf(insightText("schema.change"), insightText("schema.table"), insightText("schema.column")), insightText("schema.notice"))
    val host = JPanel(BorderLayout()).apply {
        add(OnePixelSplitter(false, 0.3f).apply {
            firstComponent = OnePixelSplitter(true, 0.5f).apply {
                firstComponent = JBScrollPane(before)
                secondComponent = JBScrollPane(after)
            }
            secondComponent = results
        }, BorderLayout.CENTER)
    }
    val useUsage = JCheckBox(insightText("schema.useUsage"))
    val affectedChoice = JComboBox<String>()
    var changes = emptyList<cn.song.mybatisplus.visualizer.sql.insight.SqlSchemaChange>()
    var affected = emptyList<cn.song.mybatisplus.visualizer.sql.insight.SqlUsageReference>()
    results.actions.add(useUsage)
    results.actions.add(affectedChoice)
    results.table.selectionModel.addListSelectionListener {
        if (!it.valueIsAdjusting) {
            affected = changes.getOrNull(results.selectedRow())?.affected.orEmpty()
            affectedChoice.model = DefaultComboBoxModel(affected.map { ref -> "${ref.location.filePath}:${ref.location.startOffset}" }.toTypedArray())
        }
    }
    results.button(insightText("usage.navigate")) {
        affected.getOrNull(affectedChoice.selectedIndex)?.let { navigateUsage(it.location) { message -> results.status.text = message } }
    }
    val inputGate = cn.song.mybatisplus.visualizer.ui.insight.InsightInputVersion({
        previewTabs.beginRefresh("schema-comparison")
        changes = emptyList(); affected = emptyList(); affectedChoice.removeAllItems()
        results.showRows(emptyList()); results.status.text = insightText("stale")
    }, before.document, after.document)
    fun bindMetadata(input: javax.swing.JTextArea) {
        val json = input.text
        val version = inputGate.capture()
        ApplicationManager.getApplication().executeOnPooledThread {
            val parsed = SqlSchemaSnapshotCodec.parse(json)
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && inputGate.isCurrent(version)) {
                    if (parsed is SqlSchemaSnapshotCodec.Result.Accepted) {
                        try {
                            analysisMetadataBinding.select(parsed.snapshot)
                            insightController?.invalidate()
                            results.status.text = insightText("metadata.selected") + " ${parsed.snapshot.databaseContext}/${parsed.snapshot.version}"
                            showSqlInsight()
                        } catch (_: IllegalArgumentException) { results.status.text = insightText("metadata.rejected") }
                    } else results.status.text = (parsed as SqlSchemaSnapshotCodec.Result.Rejected).reason
                }
            }
        }
    }
    results.button(insightText("metadata.useBefore")) { bindMetadata(before) }
    results.button(insightText("metadata.useAfter")) { bindMetadata(after) }
    results.button(insightText("schema.compare")) {
        val left = before.text; val right = after.text
        val usage = if (useUsage.isSelected) lastInsightUsageReport else null
        changes = emptyList(); affected = emptyList(); results.showRows(emptyList())
        val inputVersion = inputGate.capture()
        var future: java.util.concurrent.Future<*>? = null
        val ticket = previewTabs.beginRefresh("schema-comparison") { future?.cancel(true) }
        if (ticket != null) future = ApplicationManager.getApplication().executeOnPooledThread {
            val a = SqlSchemaSnapshotCodec.parse(left)
            val b = SqlSchemaSnapshotCodec.parse(right)
            val report = if (a is SqlSchemaSnapshotCodec.Result.Accepted && b is SqlSchemaSnapshotCodec.Result.Accepted)
                SqlSchemaDiffer.compare(a.snapshot, b.snapshot, usage, isCancelled = { Thread.currentThread().isInterrupted }) else null
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && inputGate.isCurrent(inputVersion)) previewTabs.applyIfCurrent(ticket) {
                    if (report == null) {
                        results.showRows(emptyList())
                        results.status.text = listOfNotNull((a as? SqlSchemaSnapshotCodec.Result.Rejected)?.reason, (b as? SqlSchemaSnapshotCodec.Result.Rejected)?.reason).joinToString("\n")
                    } else {
                        changes = report.changes.take(2000)
                        results.showRows(changes.map { change -> InsightDetailRow(
                            listOf(change.kind.name, change.tableNameParts.joinToString("."), change.column ?: "—"),
                            "before=${change.beforeType ?: "UNKNOWN"}\nafter=${change.afterType ?: "UNKNOWN"}\nnullable=${change.beforeNullable ?: "UNKNOWN"} → ${change.afterNullable ?: "UNKNOWN"}\naffected=${change.affected}") })
                        results.status.text = insightText("schema.notice") + "\ndatabase=${report.databaseContext}; ${report.beforeVersion} → ${report.afterVersion}; comparable=${report.comparable}; impactComplete=${report.impactComplete}\n" + report.diagnostics.joinToString("\n") { "${it.code}: ${it.message}" }
                    }
                }
            }
        }
    }
    results.button(insightText("cancel")) { previewTabs.beginRefresh("schema-comparison"); results.status.text = insightText("cancelled") }
    results.button(insightText("close")) { previewTabs.remove("schema-comparison") }
    previewTabs.put("schema-comparison", insightText("schema.title"), host, onRemove = { inputGate.close() })
    previewTabs.pane?.selectedComponent = host
}
