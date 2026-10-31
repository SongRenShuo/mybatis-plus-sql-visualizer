package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.share.SqlScenarioRegressionPackage
import cn.song.mybatisplus.visualizer.sql.insight.SqlInsightDialect
import cn.song.mybatisplus.visualizer.ui.insight.ScenarioInsightPresentation
import cn.song.mybatisplus.visualizer.ui.insight.ScenarioInsightView
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import cn.song.mybatisplus.visualizer.xml.XmlScenarioMatrix
import com.intellij.openapi.application.ApplicationManager
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicBoolean
import com.intellij.ui.components.JBScrollPane
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

internal fun insightText(key: String): String = MpSqlVisualizerBundle.message("insight.$key")
internal fun SqlPreviewPanel.insightDialect(): SqlInsightDialect = when (current?.effectiveDialect?.name) {
    "MySQL" -> SqlInsightDialect.MYSQL
    "PostgreSQL" -> SqlInsightDialect.POSTGRESQL
    else -> SqlInsightDialect.UNKNOWN
}

internal fun SqlPreviewPanel.showParameterScenarios() {
    if (previewDisposed || project?.isDisposed == true) return
    var view = scenarioInsightView
    if (view == null) {
        view = ScenarioInsightView(::insightText, { refreshParameterScenarios() }, {
            previewTabs.beginRefresh("scenarios")
            scenarioInsightView?.clear(insightText("cancelled"))
        }, { previewTabs.remove("scenarios") }, { exportScenariosInBackground(it) })
        scenarioInsightView = view
        previewTabs.put("scenarios", insightText("scenario.title"), view.panel, onRemove = { scenarioInsightView = null })
    }
    previewTabs.pane?.selectedComponent = view.panel
    refreshParameterScenarios()
}

internal fun SqlPreviewPanel.refreshParameterScenarios() {
    val view = scenarioInsightView ?: return
    view.clear(insightText("loading"))
    val result = current
    val model = result?.xmlModel
    if (model == null) { view.clear(insightText("scenario.xmlOnly")); return }
    if (!paramHistoryCache.isCurrentGeneration()) { view.clear(insightText("scenario.storageCleared")); return }
    val key = historyCacheKey() ?: return
    // EDT snapshot only; neither selecting a scheme nor rendering into the current editor is allowed.
    val stored = paramHistoryCache.snapshotSchemesFor(key, 20)
    val values = buildHistorySnapshot().toMap()
    val doc = insightDocument()
    val inputVersion = ScenarioInputVersion(auxiliaryInsightGeneration, doc, doc?.modificationStamp, values)
    val selected = stored.schemes.take(19)
    val inputs = ScenarioInsightPresentation.inputs(values, selected.map { it.name to it.values })
    val labels = listOf(insightText("scenario.current")) + selected.map { it.name.ifEmpty { insightText("scenario.default") } }
    val dialect = insightDialect()
    val stopped = AtomicBoolean()
    var future: java.util.concurrent.Future<*>? = null
    val ticket = previewTabs.beginRefresh("scenarios") { stopped.set(true); future?.cancel(true) } ?: return
    val started = System.nanoTime()
    future = ApplicationManager.getApplication().executeOnPooledThread {
        try {
            val rendered = XmlScenarioMatrix.render(model, inputs, dialect, sourceVersion = stored.storageGeneration.toString(),
                isCancelled = { stopped.get() || Thread.currentThread().isInterrupted || System.nanoTime() - started > 5_000_000_000L })
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed && project?.isDisposed != true && current === result && paramHistoryCache.isCurrentGeneration() &&
                    paramHistoryCache.generationToken() == stored.storageGeneration &&
                    inputVersion.matches(auxiliaryInsightGeneration, insightDocument(), buildHistorySnapshot())) {
                    previewTabs.applyIfCurrent(ticket) {
                        view.present(rendered, labels, insightText("scenario.notice") + if (stored.schemes.size > 19) "\n${insightText("scenario.limit")}" else "")
                    }
                }
            }
        } catch (_: java.util.concurrent.CancellationException) {
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed) previewTabs.applyIfCurrent(ticket) { view.clear(insightText("timeLimit")) }
            }
        } catch (_: Exception) {
            ApplicationManager.getApplication().invokeLater {
                if (!previewDisposed) previewTabs.applyIfCurrent(ticket) { view.clear(insightText("failed")) }
            }
        }
    }
}

/** Preview first; copy requires a second explicit click, and only the projection is copied. */
internal fun SqlPreviewPanel.showScenarioExportPreview(json: String) {
    if (previewDisposed || json.toByteArray(Charsets.UTF_8).size > SqlScenarioRegressionPackage.MAX_BYTES) return
    val area = JTextArea(json).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    val host = JPanel(BorderLayout()).apply {
        add(JTextArea(insightText("scenario.exportNotice")).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }, BorderLayout.NORTH)
        add(JBScrollPane(area), BorderLayout.CENTER)
        add(JPanel().apply {
            add(JButton(insightText("scenario.copy")).apply { addActionListener { UiTextClipboard.copy(json) } })
            add(JButton(insightText("close")).apply { addActionListener { previewTabs.remove("scenario-export") } })
        }, BorderLayout.SOUTH)
    }
    previewTabs.put("scenario-export", insightText("scenario.export"), host)
    previewTabs.pane?.selectedComponent = host
}
