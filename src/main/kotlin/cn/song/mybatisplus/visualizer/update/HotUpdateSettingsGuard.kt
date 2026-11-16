package cn.song.mybatisplus.visualizer.update

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.RestartDialogImpl
import java.awt.Window

/**
 * T-HotUpdateSettingsGuard：热更新（动态装载）后设置页「确定」无反应的自救。
 *
 * 现象（用户 2026-11-13 真机）：从本地安装包热更新后打开设置页，点「确定」无反应、点「取消」正常。
 * 归因：动态更新卸载旧 classloader，设置页 apply 链再实例化 configurable 时抛
 * `ClassNotFoundException`，异常被平台吞掉 ⇒ 静默失败。包内容没问题（tag 源码 / plugin.xml /
 * jar 三处核过），完全退出 IDE 即恢复。
 *
 * 拦截平台的 apply 链做不到，所以本类只做两件用户可感的事，且**都在卸载前一刻做**
 * （[beforePluginUnload]——此时本代际 classloader 仍完好）：
 *  1. **关掉陈旧设置窗口**：热更新发生时若设置页正开着，它持有的是旧 classloader 的页面，
 *     点「确定」必死；先关掉，用户重新打开即是新代际；
 *  2. **告知**：气泡说明「若确定无反应请重启」，带「重启 IDE」动作（走平台原生确认框）。
 *
 * 订阅为何落在 `beforePluginUnload` 而不是 `pluginLoaded`/`pluginUnloaded`：
 *  - 243 的 `pluginLoaded(descriptor)` **没有 `isUpdate` 形参**（javap `util-8.jar` 实证：5 个方法里
 *    只有 `beforePluginUnload`/`pluginUnloaded` 带 `boolean`）⇒ 装载回调里判不出「这次是热更新」；
 *  - 新代际装载时本类**还没订阅**（订阅发生在启动活动/服务首次创建，晚于 `pluginLoaded`）⇒ 那时
 *    也来不及。`beforePluginUnload` 是唯一「本代际还活着 + 明确知道是更新」的时刻。
 *
 * 与仓内「不在卸载回调里做 teardown」的纪律的关系（见 `ExecDocAutosaveHost`、
 * `SqlMapperInjectionBarrierHost`、[BarrierRegistry] 三处 KDoc）：那条纪律针对**释放自身注册**
 * （卸载回调里反手 dispose 自身类会被旧 classloader 时序钉住）。本类**不释放任何注册**，只做两个
 * 平台动作（关一个平台的对话框、弹一个平台的通知），卸载前一刻做正合适；服务本身由平台在卸载时
 * dispose（[dispose] 里只断订阅连接）。
 */
@Service(Service.Level.APP)
internal class HotUpdateSettingsGuard : Disposable {

    init {
        runCatching {
            ApplicationManager.getApplication().messageBus
                .connect(this)
                .subscribe(
                    DynamicPluginListener.TOPIC,
                    object : DynamicPluginListener {
                        override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
                            if (!shouldHandle(pluginDescriptor.pluginId.idString, isUpdate)) return
                            LOG.info("[hot-update-guard] beforePluginUnload(isUpdate=true) → 关陈旧设置窗口并提示")
                            onHotUpdateImminent()
                        }
                    },
                )
        }.onFailure { LOG.info("[hot-update-guard] 订阅 DynamicPluginListener 失败：${it.javaClass.simpleName}") }
    }

    /**
     * 卸载前一刻的动作。卸载由插件管理器在 EDT 上发起，故正常路径就地执行；万一不在 EDT，
     * 退化为 `invokeLater`（尽力而为——`DialogWrapper.close` 要求 EDT）。
     */
    private fun onHotUpdateImminent() {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            runCatching { onHotUpdateApplied() }
        } else {
            app.invokeLater { runCatching { onHotUpdateApplied() } }
        }
    }

    private fun onHotUpdateApplied() {
        val closed = closeStaleSettingsWindows()
        notifySettingsMayBeStale(closed)
    }

    /**
     * 关闭所有「设置对话框」窗口，返回关闭个数。
     *
     * 取法（243 `DialogWrapper.findInstance` 字节码实证）：沿 `Component.getParent` 上溯，命中
     * `DialogWrapperDialog` 即回 `getDialogWrapper()`——把 AWT 窗口直接喂进去即可取回 wrapper，
     * 不必按窗口类名猜（`DialogWrapperPeerImpl` 造的窗口类名各版本不同）。
     * 「是不是设置对话框」按 wrapper 类名后缀判定（[isSettingsDialogClass]）：应用级与项目级设置
     * 都是平台同一个 `SettingsDialog`，按后缀收口对版本改名也更稳。
     *
     * 只关设置窗口——不碰用户的预览面板/其它对话框（可能有未保存内容）。
     */
    internal fun closeStaleSettingsWindows(): Int {
        var closed = 0
        for (window in Window.getWindows()) {
            if (!window.isShowing) continue
            val wrapper = runCatching { DialogWrapper.findInstance(window) }.getOrNull() ?: continue
            if (!isSettingsDialogClass(wrapper.javaClass.name)) continue
            val ok = runCatching { wrapper.close(DialogWrapper.CANCEL_EXIT_CODE) }.isSuccess
            LOG.info("[hot-update-guard] 关闭陈旧设置窗口 ${wrapper.javaClass.simpleName} ok=$ok")
            if (ok) closed++
        }
        return closed
    }

    private fun notifySettingsMayBeStale(closedWindows: Int) {
        val content = if (closedWindows > 0) {
            MpSqlVisualizerBundle.message("hotupdate.notification.content.closed", closedWindows)
        } else {
            MpSqlVisualizerBundle.message("hotupdate.notification.content")
        }
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(StartupUpdateCheck.NOTIFICATION_GROUP_ID)
            .createNotification(
                MpSqlVisualizerBundle.message(
                    "hotupdate.notification.title",
                    MpSqlVisualizerBundle.message("plugin.name"),
                ),
                content,
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple(MpSqlVisualizerBundle.message("update.notification.restart")) {
                RestartDialogImpl.restartWithConfirmation()
            })
        runCatching { notification.notify(null) }
    }

    override fun dispose() = Unit

    companion object {
        private val LOG = Logger.getInstance(HotUpdateSettingsGuard::class.java)

        /** 本插件在平台注册表里的 id（与 plugin.xml `<id>` 一字不差）。 */
        const val PLUGIN_ID: String = "cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer"

        /** 服务实例（懒创建；由启动活动取一次即完成订阅）。 */
        fun getInstance(): HotUpdateSettingsGuard =
            ApplicationManager.getApplication().getService(HotUpdateSettingsGuard::class.java)

        /**
         * 是否该处理这次卸载（纯函数，单测覆盖）：必须是**更新**（`isUpdate`）且是**本插件**。
         * 负门：普通启动/退出（isUpdate=false）、别家插件的更新都不得关窗、不得弹泡。
         */
        internal fun shouldHandle(pluginId: String, isUpdate: Boolean): Boolean =
            isUpdate && pluginId == PLUGIN_ID

        /**
         * 是否「设置对话框」wrapper（纯函数，单测覆盖）：按类名后缀判定。
         * 覆盖平台 `com.intellij.openapi.options.newEditor.SettingsDialog`（应用级与项目级同一个类）。
         */
        internal fun isSettingsDialogClass(className: String): Boolean =
            className.endsWith("SettingsDialog")
    }
}
