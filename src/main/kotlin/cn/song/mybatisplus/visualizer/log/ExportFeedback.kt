package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * S1-4④（2026-10-31）：导出出口的「无可导出内容」可见提示。
 *
 * 四条导出出口（历史面板右键 / 预览「更多」/ 日志页 / 诊断分享）此前在**没有内容**时直接
 * `return` ——用户点了菜单什么都没发生，分不清「点了没生效」和「本来就没东西可导」。
 * 本对象把这条反馈收成一处：走平台通知（**不弹模态框**，仓纪律「轻提示走平台通知」），
 * 文案统一走 bundle，双语 parity 由既有守卫覆盖。
 *
 * 用户主动取消（文件保存对话框按取消 / 格式选择取消）**不**经本通道——那是明确意图，不需要噪音。
 */
object ExportFeedback {

    /** 复用预览/日志/历史三链既有的通知组（plugin.xml 已注册，不新增组）。 */
    private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

    /**
     * 测试缝：非空时提示文本只送到这里（headless 下平台通知不可观测，且 [isUnitTestMode]
     * 会让生产通道短路）。测试用完必须复位为 null，否则后续用例互相污染。
     */
    internal var sinkForTest: ((String) -> Unit)? = null

    /** 提示「当前没有可导出的内容」；[project] 为空（无打开项目）时降级为静默（无宿主可挂通知）。 */
    fun notifyNothingToExport(project: Project?, hintKey: String? = null) {
        val body = if (hintKey == null) {
            MpSqlVisualizerBundle.message("export.nothing.body")
        } else {
            MpSqlVisualizerBundle.message("export.nothing.bodyWithHint", MpSqlVisualizerBundle.message(hintKey))
        }
        sinkForTest?.let { sink ->
            sink(body)
            return
        }
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val proj = project ?: return
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(body, NotificationType.INFORMATION)
                .notify(proj)
        }
    }
}
