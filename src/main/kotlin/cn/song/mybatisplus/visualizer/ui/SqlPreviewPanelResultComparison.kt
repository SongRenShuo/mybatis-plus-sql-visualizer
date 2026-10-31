package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.*
import cn.song.mybatisplus.visualizer.ui.insight.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.*

internal fun SqlPreviewPanel.showResultComparison() {
    val proj = project ?: return
    if (previewDisposed || proj.isDisposed) return
    val old = previewTabs.component("result-comparison")
    if (old != null) { previewTabs.pane?.selectedComponent = old; return }
    val target = comparisonTargetForTest ?: ComparisonTargetSession(proj)
    val executor = comparisonExecutionForTest ?: ComparisonExecutionPort { request, safety, session, cancel, callback ->
        ProjectResultComparison.executeAsync(proj, request, safety, session, cancellation = cancel, onResult = callback)
    }
    val before = boundedTextArea(65_536).apply { text = insightDocument()?.text.orEmpty().take(65_536) }
    val after = boundedTextArea(65_536)
    val results = InsightDetailPanel(listOf(insightText("comparison.side"), insightText("comparison.rows"), insightText("runtime.status")), insightText("comparison.notice"))
    val choices = JComboBox<ComparisonTargetOption>().apply {
        renderer = object : DefaultListCellRenderer() {
            init { putClientProperty("html.disable", true) }
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, selected, focus)
                text = (value as? ComparisonTargetOption)?.let { "${it.displayName} [${it.identity}] ${it.dialectName ?: "UNKNOWN"}" }.orEmpty()
                return this
            }
        }
    }
    val executeApproval = JCheckBox(insightText("comparison.approvePair"))
    val functions = JCheckBox(insightText("comparison.functions"))
    val bindings = JCheckBox(insightText("comparison.bindings"))
    val raw = JCheckBox(insightText("comparison.raw"))
    val unknown = JCheckBox(insightText("comparison.unknownDialect"))
    var prepared: SqlComparisonRequest? = null
    var consent: SqlComparisonConsent? = null
    var cancellation: ComparisonCancellation? = null
    val gate = InsightInputVersion({
        target.invalidateSql(); cancellation?.cancel(); prepared = null; consent = null
        executeApproval.isSelected = false; results.showRows(emptyList()); results.status.text = insightText("stale")
        previewTabs.beginRefresh("result-comparison")
    }, before.document, after.document)
    comparisonSqlInvalidated = { gate.invalidate() }
    val host = JPanel(BorderLayout()).apply {
        add(OnePixelSplitter(false, 0.35f).apply {
            firstComponent = OnePixelSplitter(true, 0.5f).apply {
                firstComponent = JBScrollPane(before)
                secondComponent = JBScrollPane(after)
            }
            secondComponent = results
        }, BorderLayout.CENTER)
    }
    fun background(task: () -> Unit) {
        comparisonBackgroundForTest?.let { it(task); return }
        ApplicationManager.getApplication().executeOnPooledThread {
            try { task() } catch (failure: Exception) {
                if (failure is java.util.concurrent.CancellationException || failure is com.intellij.openapi.progress.ProcessCanceledException || failure is InterruptedException) throw failure
                ApplicationManager.getApplication().invokeLater { if (!previewDisposed) results.status.text = insightText("failed") }
            }
        }
    }
    results.actions.add(choices)
    results.button(insightText("comparison.targets")) {
        val version = gate.capture()
        background {
            val options = target.listTargets()
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && gate.isCurrent(version)) {
                    choices.model = DefaultComboBoxModel(options.toTypedArray()); choices.selectedIndex = -1
                }
            }
        }
    }
    results.button(insightText("comparison.selectTarget")) {
        val option = choices.selectedItem as? ComparisonTargetOption ?: return@button
        gate.invalidate()
        val version = gate.capture()
        val targetGeneration = target.generationToken()
        background {
            val selected = target.select(option.identity, targetGeneration)
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && gate.isCurrent(version)) results.status.text = insightText("comparison.notice") + "\ntarget=$selected"
            }
        }
    }
    results.button(insightText("comparison.prepare")) {
        prepared = null; consent = null; executeApproval.isSelected = false
        val selected = target.snapshot()
        if (selected == null) { results.status.text = insightText("comparison.targetRequired"); return@button }
        val left = before.text; val right = after.text
        if (!ComparisonUiPolicy.bindingsResolved(left, right)) { results.status.text = insightText("comparison.unresolved"); return@button }
        val version = gate.capture()
        background {
            val preparation = SqlComparisonRequest.prepare(left, right, selected.identity, selected.generation, maxRows = 100, timeoutSeconds = 10)
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && gate.isCurrent(version) && target.snapshot() == selected) {
                    when (preparation) {
                        is SqlComparisonRequest.Preparation.Rejected -> results.status.text = preparation.reason
                        is SqlComparisonRequest.Preparation.Ready -> {
                            prepared = preparation.request; consent = SqlComparisonConsent(preparation.request)
                            results.detail.text = "target=$selected\nmaxRows=100; timeout=10s\nBASELINE:\n$left\nCANDIDATE:\n$right\n" + insightText("comparison.notice")
                        }
                    }
                }
            }
        }
    }
    for (box in listOf(executeApproval, functions, bindings, raw, unknown)) results.actions.add(box)
    results.button(insightText("comparison.execute")) {
        val request = prepared ?: return@button
        val currentTarget = target.snapshot() ?: return@button
        val approvals = ComparisonUiApprovals(executeApproval.isSelected, functions.isSelected, bindings.isSelected, raw.isSelected, unknown.isSelected)
        val safety = ComparisonUiPolicy.safety(request, approvals, current?.hasDollarRawSegments == true || current?.xmlModel?.hasDollarRawSegments == true)
        if (safety == null || !unknown.isSelected) { results.status.text = insightText("comparison.approvalsRequired"); return@button }
        val authorized = consent?.consume(approvals.executePair, before.text, after.text, currentTarget.identity, currentTarget.generation)
        consent = null; prepared = null; executeApproval.isSelected = false
        if (authorized == null) { results.status.text = insightText("stale"); return@button }
        val version = gate.capture()
        cancellation?.cancel()
        val cancel = ComparisonCancellation()
        val ticket = previewTabs.beginRefresh("result-comparison") { cancel.cancel() } ?: return@button
        cancellation = cancel
        results.status.text = insightText("loading")
        try { executor.execute(authorized, safety, target, cancel) { response ->
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && !proj.isDisposed && gate.isCurrent(version)) previewTabs.applyIfCurrent(ticket) {
                    results.showRows(listOf("baseline" to response.baseline, "candidate" to response.candidate).map { (side, snapshot) ->
                        InsightDetailRow(listOf(side, snapshot?.rows?.size?.toString() ?: "UNKNOWN", snapshot?.failure ?: if (snapshot?.truncated == true) "TRUNCATED" else "READ"),
                            "columns=${snapshot?.columns}\n" + snapshot?.rows.orEmpty().take(100).joinToString("\n") { it.toString() })
                    })
                    results.status.text = "${response.comparison}\n${response.consistency}; rows=${response.effectiveMaxRows}\n" + insightText("comparison.notice")
                }
            }
        } } catch (failure: Exception) {
            if (failure is java.util.concurrent.CancellationException || failure is com.intellij.openapi.progress.ProcessCanceledException) throw failure
            results.status.text = insightText("failed")
        }
    }
    results.button(insightText("cancel")) { cancellation?.cancel(); results.status.text = insightText("cancelled") }
    results.button(insightText("close")) { previewTabs.remove("result-comparison") }
    previewTabs.put("result-comparison", insightText("comparison.title"), host, onRemove = {
        cancellation?.cancel(); target.clear(); gate.close(); comparisonSqlInvalidated = null
    })
    previewTabs.pane?.selectedComponent = host
}
