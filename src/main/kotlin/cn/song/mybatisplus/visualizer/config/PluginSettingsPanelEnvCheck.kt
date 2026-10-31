package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages

/**
 * 设置页「一键环境体检报告」动作（NEW-18；D202 拆分自 `PluginSettingsPanel`，行数预算见
 * `GiantFileBudgetTest`）。
 *
 * 线程：方言决策/数据源枚举可能触及 URL 探测，取数一律池线程；EDT 只做展示。
 * 展示：有打开项目 → `LightVirtualFile` 在编辑器打开（不落盘、不弹模态框，用户可直接复制
 * 贴工单）；无打开项目 → 复制到剪贴板并在行内提示（仍不弹框）。
 * 只读：不执行任何 SQL，也不为体检创建 AgentManager/SqlLogManager。
 */
internal fun PluginSettingsPanel.generateEnvironmentCheckReport() {
    environmentCheckButton.isEnabled = false
    environmentCheckStatusLabel.text = MpSqlVisualizerBundle.message("settings.envCheck.generating")
    val project = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
    ApplicationManager.getApplication().executeOnPooledThread {
        val report = runCatching {
            cn.song.mybatisplus.visualizer.diagnostic.EnvironmentCheckCollector.collect(project)
        }.onFailure {
            Logger.getInstance(PluginSettingsPanel::class.java)
                .warn("[New18] environment check collect FAILED: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
        val text = report?.render()
        ApplicationManager.getApplication().invokeLater {
            environmentCheckButton.isEnabled = true
            if (text == null) {
                environmentCheckStatusLabel.text = MpSqlVisualizerBundle.message("settings.envCheck.failed")
                return@invokeLater
            }
            val presenter = environmentReportPresenter
            if (presenter != null) {
                presenter(text)
                environmentCheckStatusLabel.text = MpSqlVisualizerBundle.message("settings.envCheck.ready")
                return@invokeLater
            }
            val opened = project != null && runCatching {
                val file = com.intellij.testFramework.LightVirtualFile(
                    MpSqlVisualizerBundle.message("settings.envCheck.fileName"),
                    text,
                )
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true)
            }.isSuccess
            if (opened) {
                environmentCheckStatusLabel.text = MpSqlVisualizerBundle.message("settings.envCheck.ready")
            } else {
                // 无打开项目/编辑器打开失败：复制到剪贴板（不弹框，行内提示）
                runCatching { cn.song.mybatisplus.visualizer.util.UiTextClipboard.copy(text) }
                environmentCheckStatusLabel.text = MpSqlVisualizerBundle.message("settings.envCheck.copied")
            }
        }
    }
}
