package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.log.sqlLogManagerIfCreated
import cn.song.mybatisplus.visualizer.log.sqlLogManagerOrNull
import cn.song.mybatisplus.visualizer.ui.standalone.StandaloneSqlSourceResolver
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 打开 SQL 预览工作台（T-SqlPreviewStandalone D-3/D-4）：独立于语句入口的预览入口——
 * 主侧边栏切到「SQL 预览」tab 并聚焦输入框；剪贴板文本疑似 SQL / mapper XML 时预填并
 * 直接解析（D-4，判定复用 [StandaloneSqlSourceResolver.isClipboardCandidate]——与解析
 * 链同源，遵循 dbx「执行器认什么检测器就认什么」纪律）。
 *
 * 工具窗未创建（本会话从未打开过侧边栏）时先 show 工具窗触发工厂建面板（面板 init 注册
 * 通道回调），再于一拍后重走通道；registry `sqlvis.preview.standaloneTab` 关闭时由
 * [cn.song.mybatisplus.visualizer.log.SqlLogPanel.openStandalonePreview] 轻通知指路。
 */
class OpenSqlPreviewAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = sqlLogManagerOrNull(project) ?: return
        val prefill = clipboardPrefillCandidate()
        val bridge = manager.onOpenStandalonePreview
        if (bridge != null) {
            bridge(prefill)
            return
        }
        // 工具窗未创建过：show 触发工厂建面板（面板 init 注册回调），随后一拍再走通道
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(SqlLogManager.TOOL_WINDOW_ID) ?: return
        toolWindow.show(null)
        ApplicationManager.getApplication().invokeLater(
            {
                if (project.isDisposed) return@invokeLater
                sqlLogManagerIfCreated(project)?.onOpenStandalonePreview?.invoke(prefill)
            },
            ModalityState.nonModal(),
        )
    }

    /** D-4：剪贴板候选判定（解析器同源）；异常降级为 null（不因剪贴板不可读阻断入口）。 */
    private fun clipboardPrefillCandidate(): String? {
        val text = runCatching { UiTextClipboard.readText() }.getOrNull() ?: return null
        return if (StandaloneSqlSourceResolver.isClipboardCandidate(text)) text else null
    }

    companion object {
        /** bundle 键（plugin.xml text/description 引用，约定 `action.<id>.<prop>`）。 */
        @JvmStatic
        fun text(): String = MpSqlVisualizerBundle.message("action.cn.song.mybatisplus.visualizer.OpenSqlPreview.text")

        @JvmStatic
        fun description(): String = MpSqlVisualizerBundle.message(
            "action.cn.song.mybatisplus.visualizer.OpenSqlPreview.description",
        )
    }
}
