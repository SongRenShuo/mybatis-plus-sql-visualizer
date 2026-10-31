package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.ui.diff.SqlDiffer
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailPanel
import cn.song.mybatisplus.visualizer.ui.insight.InsightDetailRow
import cn.song.mybatisplus.visualizer.ui.insight.boundedTextArea
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.*

/** User-supplied one-shot sample, not a runtime capture subscription or automatic execution pairing. */
internal fun SqlPreviewPanel.showObservedSqlDiff(candidate: cn.song.mybatisplus.visualizer.log.insight.SqlExecutionObservation? = null) {
    if (previewDisposed) return
    val doc = insightDocument() ?: return
    if (doc.textLength > 65_536) return
    val baseline = doc.text
    val stamp = doc.modificationStamp
    val preview = current
    val generation = auxiliaryInsightGeneration
    val dialect = preview?.effectiveDialect
    var writeContext: cn.song.mybatisplus.visualizer.ui.insight.WriteImpactContext? = null
    fun isCurrentWriteContext(): Boolean = !previewDisposed && current === preview && insightDocument() === doc &&
        doc.modificationStamp == stamp && auxiliaryInsightGeneration == generation
    fun background(task: () -> Unit) {
        observedBackgroundForTest?.let { it(task); return }
        ApplicationManager.getApplication().executeOnPooledThread(Runnable { task() })
    }
    val sample = boundedTextArea(65_536)
    val results = InsightDetailPanel(listOf(insightText("runtime.diffKind"), insightText("runtime.previewLine"), insightText("runtime.actualLine")), insightText("runtime.diffNotice"))
    val host = JPanel(BorderLayout()).apply {
        add(OnePixelSplitter(false, 0.3f).apply { firstComponent = JBScrollPane(sample); secondComponent = results }, BorderLayout.CENTER)
    }
    val inputGate = cn.song.mybatisplus.visualizer.ui.insight.InsightInputVersion({
        previewTabs.beginRefresh("observed-sql-diff"); results.showRows(emptyList()); results.status.text = insightText("stale")
    }, sample.document)
    val candidateEvidence = candidate?.let { "run=${it.runId}; execution=${it.executionId}; database=${it.databaseIdentity}; request=${it.requestId}" } ?: insightText("runtime.unassociated")
    results.status.text = insightText("runtime.diffNotice") + "\n" + candidateEvidence
    val txChoices = JComboBox<String>()
    var transactions = emptyList<cn.song.mybatisplus.visualizer.log.agent.TransactionEvent>()
    results.actions.add(txChoices)
    results.button(insightText("runtime.loadTransactions")) {
        val source = runtimeInsightSource ?: return@button
        background {
            val loaded = source.snapshot().transactions.take(2000)
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && previewTabs.component("observed-sql-diff") === host) {
                    transactions = loaded
                    txChoices.model = DefaultComboBoxModel(loaded.map { "${it.envelope.runId}/${it.connectionId}/${it.transactionId}/${it.kind}" }.toTypedArray())
                    txChoices.selectedIndex = -1
                }
            }
        }
    }
    results.button(insightText("runtime.associateTransaction")) {
        if (!isCurrentWriteContext()) { results.detail.text = insightText("stale"); return@button }
        val tx = transactions.getOrNull(txChoices.selectedIndex) ?: return@button
        val context = writeContext
        if (context == null || !context.matches(doc.text, doc.modificationStamp, auxiliaryInsightGeneration)) {
            results.detail.text = insightText("loading"); return@button
        }
        results.detail.text = context.describe(tx, candidate, insightText("runtime.writeImpactAssociation")) + "\n$candidateEvidence"
    }
    results.button(insightText("runtime.compareSample")) {
        if (current !== preview || insightDocument() !== doc || doc.modificationStamp != stamp) {
            results.showRows(emptyList()); results.status.text = insightText("stale"); return@button
        }
        val actual = sample.text
        val inputVersion = inputGate.capture()
        results.showRows(emptyList())
        var future: java.util.concurrent.Future<*>? = null
        val ticket = previewTabs.beginRefresh("observed-sql-diff") { future?.cancel(true) } ?: return@button
        future = ApplicationManager.getApplication().executeOnPooledThread {
            if (Thread.currentThread().isInterrupted) return@executeOnPooledThread
            val diff = try { SqlDiffer.computeDiff(baseline, actual) } catch (failure: Exception) {
                if (failure is java.util.concurrent.CancellationException || failure is com.intellij.openapi.progress.ProcessCanceledException) throw failure
                ApplicationManager.getApplication().invokeLater {
                    if (!previewDisposed && inputGate.isCurrent(inputVersion)) previewTabs.applyIfCurrent(ticket) { results.status.text = insightText("failed") }
                }
                return@executeOnPooledThread
            }
            if (Thread.currentThread().isInterrupted) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && inputGate.isCurrent(inputVersion) && current === preview && insightDocument() === doc && doc.modificationStamp == stamp) {
                    previewTabs.applyIfCurrent(ticket) {
                        results.showRows(diff.take(2000).map { InsightDetailRow(listOf(it.type.name, it.oldLineNumber?.toString() ?: "—", it.newLineNumber?.toString() ?: "—"),
                            "${it.oldLine.orEmpty()}\n→\n${it.newLine.orEmpty()}") })
                        results.status.text = insightText("runtime.diffNotice") + "\n$candidateEvidence\n" +
                            "type=${SqlTypeClassifier.getFirstKeyword(baseline)} → ${SqlTypeClassifier.getFirstKeyword(actual)}; placeholders=${cn.song.mybatisplus.visualizer.sql.placeholderPositions(baseline).size} → ${cn.song.mybatisplus.visualizer.sql.placeholderPositions(actual).size}; parameterTypes=UNKNOWN; shapeChanged=${cn.song.mybatisplus.visualizer.log.structureFingerprint(baseline) != cn.song.mybatisplus.visualizer.log.structureFingerprint(actual)}\n" +
                            "diffRows=${diff.size}; displayed=${minOf(2000, diff.size)}"
                    }
                }
            }
        }
    }
    results.button(insightText("close")) { previewTabs.remove("observed-sql-diff") }
    previewTabs.put("observed-sql-diff", insightText("runtime.compareSample"), host, onRemove = { inputGate.close() })
    previewTabs.pane?.selectedComponent = host
    background {
        val context = cn.song.mybatisplus.visualizer.ui.insight.WriteImpactContext.capture(baseline, stamp, generation, dialect)
        ApplicationManager.getApplication().invokeLater {
            if (isCurrentWriteContext() && previewTabs.component("observed-sql-diff") === host) {
                writeContext = context
                results.detail.text = context.describe(null, candidate, insightText("runtime.writeImpactAssociation"))
            }
        }
    }
}
