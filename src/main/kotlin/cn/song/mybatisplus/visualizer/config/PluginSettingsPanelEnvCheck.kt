package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.diagnostic.EnvironmentCheckCollector
import cn.song.mybatisplus.visualizer.diagnostic.EnvironmentCheckReport
import cn.song.mybatisplus.visualizer.util.FatalErrors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

private val ENV_CHECK_LOG = Logger.getInstance(PluginSettingsPanel::class.java)

/**
 * 设置页「一键环境体检报告」动作（NEW-18；D202 拆分自 `PluginSettingsPanel`，行数预算见
 * `GiantFileBudgetTest`）。
 *
 * 线程：方言决策/数据源枚举可能触及 URL 探测与文件索引，取数一律池线程；EDT 只做展示。
 * 展示：有打开项目 → `LightVirtualFile` 在编辑器打开（不落盘、不弹模态框，用户可直接复制
 * 贴工单）；无打开项目 → 复制到剪贴板并在行内提示（仍不弹框）。
 * 只读：不执行任何 SQL，也不为体检创建 AgentManager/SqlLogManager。
 *
 * **UI-005（真机缺陷：按钮永久灰、文案永久停在「体检中…」）两条修法：**
 *  1. 回 EDT 必须显式 `ModalityState.any()`——入口在**模态** Settings 弹窗里，BGT 用默认 modality
 *     投递的 runnable 在弹窗存续期**不会执行**（同文件 `importSettings`/`exportSettings` 早有同款
 *     真机实证与注释）。旧实现正是漏了这一步：体检单其实已生成，只是永远没人把它贴回来。
 *  2. 按钮/文案恢复走 `try/finally` 不变式（见 [finishEnvironmentCheck]）：取数抛 Error、渲染抛异常、
 *     展示抛异常都必须恢复可点，绝不允许「灰按钮 + 体检中」成为终态。
 *
 * 取数本身另有时间上界（`EnvironmentCheckBounded`）：单项 3s、整单 9s，超时项降级为「未采集」，
 * 不会再出现「一项卡死拖死整单」。
 */
internal fun PluginSettingsPanel.generateEnvironmentCheckReport() {
    environmentCheckButton.isEnabled = false
    environmentCheckStatusLabel.text = MpSqlVisualizerBundle.message("settings.envCheck.generating")
    val project = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
    var dispatched = false
    try {
        ApplicationManager.getApplication().executeOnPooledThread {
            var report: EnvironmentCheckReport? = null
            try {
                report = EnvironmentCheckCollector.collect(project)
            } catch (t: Throwable) {
                // 体检是旁路诊断：非致命失败降级为「体检失败」；JVM 级致命错误照旧透传（T-FatalPassthrough）。
                if (FatalErrors.isFatal(t)) throw t
                ENV_CHECK_LOG.warn(
                    "[New18] environment check collect FAILED: ${t.javaClass.simpleName}: ${t.message}",
                    t,
                )
            } finally {
                // 无论成败（含 fatal 重抛前的这一拍）都要回 EDT 收尾——按钮不许停在灰色（UI-005）。
                // write-safe: UI-only（只改 Swing 控件、开只读虚拟文件、写剪贴板，无文档写）
                // ModalityState.any() 是 UI-005 的核心：模态 Settings 弹窗存续期默认 modality 不执行。
                val finalReport = report
                ApplicationManager.getApplication().invokeLater(
                    { finishEnvironmentCheck(project, finalReport) },
                    ModalityState.any(),
                )
            }
        }
        dispatched = true
    } catch (t: Throwable) {
        if (FatalErrors.isFatal(t)) throw t
        ENV_CHECK_LOG.warn("[New18] environment check dispatch FAILED", t)
    } finally {
        // 派发本身失败（应用已 dispose 等）：就地收尾，绝不把按钮留在灰色。
        if (!dispatched) finishEnvironmentCheck(project, null)
    }
}

/**
 * EDT 收尾：展示体检单 + 恢复按钮。**不变式**——无论 [report] 是否为 null、渲染/展示是否抛异常，
 * 退出时按钮必须可点（`finally` 里恢复）。
 */
private fun PluginSettingsPanel.finishEnvironmentCheck(project: Project?, report: EnvironmentCheckReport?) {
    try {
        if (report == null) {
            environmentCheckStatusLabel.text = MpSqlVisualizerBundle.message("settings.envCheck.failed")
            return
        }
        val text = report.render()
        val presenter = environmentReportPresenter
        if (presenter != null) {
            presenter(text)
            environmentCheckStatusLabel.text = MpSqlVisualizerBundle.message("settings.envCheck.ready")
            return
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
    } catch (t: Throwable) {
        // 展示链异常仍必须恢复按钮——fatal 透传（T-FatalPassthrough），恢复动作在 finally。
        if (FatalErrors.isFatal(t)) throw t
        ENV_CHECK_LOG.warn("[New18] environment check UI delivery FAILED", t)
        runCatching {
            environmentCheckStatusLabel.text = MpSqlVisualizerBundle.message("settings.envCheck.failed")
        }
    } finally {
        environmentCheckButton.isEnabled = true
    }
}
