package cn.song.mybatisplus.visualizer.update;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.RestartDialogImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Window;

/**
 * T-HotUpdateSettingsGuard：热更新（动态装载）后设置页「确定」无反应的自救。
 *
 * <p>现象（用户 2026-11-13 真机）：从本地安装包热更新后打开设置页，点「确定」无反应、点「取消」正常。
 * 归因：动态更新卸载旧 classloader，设置页 apply 链再实例化 configurable 时抛
 * {@code ClassNotFoundException}，异常被平台吞掉 ⇒ 静默失败。包内容没问题，完全退出 IDE 即恢复。</p>
 *
 * <p>拦截平台的 apply 链做不到，所以本类只做两件用户可感的事，且<b>都在卸载前一刻做</b>
 * （beforePluginUnload——此时本代际 classloader 仍完好）：① 关掉陈旧设置窗口（它持有旧 classloader
 * 的页面，点「确定」必死）；② 告知：气泡说明「若确定无反应请重启」，带「重启 IDE」动作。</p>
 *
 * <p>订阅为何落在 beforePluginUnload 而不是 pluginLoaded/pluginUnloaded：243 的
 * {@code pluginLoaded(descriptor)} <b>没有 isUpdate 形参</b>（javap util-8.jar 实证）⇒ 装载回调里
 * 判不出「这次是热更新」；新代际装载时本类还没订阅。beforePluginUnload 是唯一
 * 「本代际还活着 + 明确知道是更新」的时刻。</p>
 *
 * <p>与仓内「不在卸载回调里做 teardown」的纪律的关系（见 ExecDocAutosaveHost、
 * SqlMapperInjectionBarrierHost、BarrierRegistry 三处 KDoc）：那条纪律针对<b>释放自身注册</b>
 * （卸载回调里反手 dispose 自身类会被旧 classloader 时序钉住）。本类<b>不释放任何注册</b>，
 * 只做两个平台动作（关一个平台的对话框、弹一个平台的通知），卸载前一刻做正合适；
 * 服务本身由平台在卸载时 dispose（dispose 里只断订阅连接）。</p>
 */
@Service(Service.Level.APP)
public class HotUpdateSettingsGuard implements Disposable {

    private static final Logger LOG = Logger.getInstance(HotUpdateSettingsGuard.class);

    /** 本插件在平台注册表里的 id（与 plugin.xml {@code <id>} 一字不差）。 */
    public static final String PLUGIN_ID = "cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer";

    /** 服务实例（懒创建；由启动活动取一次即完成订阅）。 */
    public HotUpdateSettingsGuard() {
        try {
            ApplicationManager.getApplication().getMessageBus()
                    .connect(this)
                    .subscribe(DynamicPluginListener.TOPIC, new DynamicPluginListener() {
                        @Override
                        public void beforePluginUnload(@NotNull IdeaPluginDescriptor pluginDescriptor,
                                                       boolean isUpdate) {
                            if (!shouldHandle(pluginDescriptor.getPluginId().getIdString(), isUpdate)) {
                                return;
                            }
                            LOG.info("[hot-update-guard] beforePluginUnload(isUpdate=true) → 关陈旧设置窗口并提示");
                            onHotUpdateImminent();
                        }
                    });
        } catch (Throwable t) {
            LOG.info("[hot-update-guard] 订阅 DynamicPluginListener 失败：" + t.getClass().getSimpleName());
        }
    }

    /** Kotlin object 语义兼容实例（生产经 {@link #getInstance()} 服务定位）。 */
    public static final HotUpdateSettingsGuard INSTANCE = new HotUpdateSettingsGuard();

    /** 服务实例定位（应用级服务；由启动活动取一次即完成订阅）。 */
    public static HotUpdateSettingsGuard getInstance() {
        return ApplicationManager.getApplication().getService(HotUpdateSettingsGuard.class);
    }

    /**
     * 卸载前一刻的动作。卸载由插件管理器在 EDT 上发起，故正常路径就地执行；万一不在 EDT，
     * 退化为 {@code invokeLater}（尽力而为——DialogWrapper.close 要求 EDT）。
     */
    private void onHotUpdateImminent() {
        var app = ApplicationManager.getApplication();
        if (app != null && app.isDispatchThread()) {
            try {
                onHotUpdateApplied();
            } catch (Throwable t) {
                LOG.info("[hot-update-guard] onHotUpdateApplied FAILED (EDT): " + t.getClass().getSimpleName());
            }
        } else if (app != null) {
            app.invokeLater(() -> {
                try {
                    onHotUpdateApplied();
                } catch (Throwable t) {
                    LOG.info("[hot-update-guard] onHotUpdateApplied FAILED (deferred): " + t.getClass().getSimpleName());
                }
            });
        }
    }

    private void onHotUpdateApplied() {
        int closed = closeStaleSettingsWindows();
        notifySettingsMayBeStale(closed);
    }

    /**
     * 关闭所有「设置对话框」窗口，返回关闭个数。
     *
     * <p>取法（243 DialogWrapper.findInstance 字节码实证）：沿 {@code Component.getParent} 上溯，命中
     * {@code DialogWrapperDialog} 即回 {@code getDialogWrapper()}——把 AWT 窗口直接喂进去即可取回
     * wrapper，不必按窗口类名猜。「是不是设置对话框」按 wrapper 类名后缀判定（应用级与项目级设置
     * 都是平台同一个 SettingsDialog）。只关设置窗口——不碰用户的预览面板/其它对话框
     * （可能有未保存内容）。</p>
     */
    public static int closeStaleSettingsWindows() {
        int closed = 0;
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) {
                continue;
            }
            DialogWrapper wrapper;
            try {
                wrapper = DialogWrapper.findInstance(window);
            } catch (Throwable t) {
                wrapper = null;
            }
            if (wrapper == null || !isSettingsDialogClass(wrapper.getClass().getName())) {
                continue;
            }
            boolean ok;
            try {
                wrapper.close(DialogWrapper.CANCEL_EXIT_CODE);
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
            LOG.info("[hot-update-guard] 关闭陈旧设置窗口 " + wrapper.getClass().getSimpleName() + " ok=" + ok);
            if (ok) {
                closed++;
            }
        }
        return closed;
    }

    private static void notifySettingsMayBeStale(int closedWindows) {
        try {
            String content = closedWindows > 0
                    ? MpSqlVisualizerBundle.message("hotupdate.notification.content.closed", closedWindows)
                    : MpSqlVisualizerBundle.message("hotupdate.notification.content");
            var notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(StartupUpdateCheck.NOTIFICATION_GROUP_ID)
                    .createNotification(
                            MpSqlVisualizerBundle.message("hotupdate.notification.title",
                                    MpSqlVisualizerBundle.message("plugin.name")),
                            content,
                            NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimple(
                            MpSqlVisualizerBundle.message("update.notification.restart"),
                            () -> RestartDialogImpl.restartWithConfirmation()));
            notification.notify(null);
        } catch (Throwable t) {
            LOG.info("[hot-update-guard] notify FAILED: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public void dispose() {
        // 平台在卸载时 dispose 本服务；订阅连接随 Disposable 树级联关闭，无需手动 teardown。
    }

    /**
     * 是否该处理这次卸载（纯函数，单测覆盖）：必须是<b>更新</b>（isUpdate）且是<b>本插件</b>。
     * 负门：普通启动/退出（isUpdate=false）、别家插件的更新都不得关窗、不得弹泡。
     */
    public static boolean shouldHandle(@NotNull String pluginId, boolean isUpdate) {
        return isUpdate && PLUGIN_ID.equals(pluginId);
    }

    /**
     * 是否「设置对话框」wrapper（纯函数，单测覆盖）：按类名后缀判定。
     * 覆盖平台 {@code com.intellij.openapi.options.newEditor.SettingsDialog}（应用级与项目级同一个类）。
     */
    public static boolean isSettingsDialogClass(@NotNull String className) {
        return className.endsWith("SettingsDialog");
    }
}
