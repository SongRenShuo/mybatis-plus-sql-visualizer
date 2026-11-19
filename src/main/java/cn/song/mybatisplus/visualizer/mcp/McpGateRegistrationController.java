// T-SyncK2JMaster180 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterRegistrar.kt（object McpGateRegistrationController）
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 注册同步器：把「总开关 + 平台契约 + 逃生开关」三件事收成一个幂等入口。
 *
 * <p>所有注册面都从这里进出，避免出现「toolset 注册了、过滤 EP 没注册」或反过来的半开状态。</p>
 */
public final class McpGateRegistrationController {

    private McpGateRegistrationController() {
    }

    private static final Logger LOG = Logger.getInstance(McpGateRegistrationController.class);

    private static final AtomicBoolean listening = new AtomicBoolean(false);

    /** 订阅设置变更（应用级、进程内一次）。设置页 Apply 后重新同步注册状态。 */
    public static void ensureListening() {
        if (!listening.compareAndSet(false, true)) {
            return;
        }
        try {
            ApplicationManager.getApplication().getMessageBus().connect()
                .subscribe(PluginSettings.SETTINGS_CHANGE_TOPIC, McpGateRegistrationController::sync);
        } catch (Throwable t) {
            listening.set(false);
            LOG.warn("O-68: 设置变更监听订阅失败（总开关切换后需重启 IDE 生效）", t);
        }
    }

    /** 按当前总开关/逃生开关/平台契约同步两个注册面（幂等，可反复调用）。 */
    public static void sync() {
        ensureListening();
        if (McpToolGate.isMasterEnabled()) {
            McpToolsetDynamicRegistrar.registerOnce();
        } else {
            McpToolsetDynamicRegistrar.unregisterAll();
        }
        McpGateFilterEpRegistrar.sync();
    }
}
