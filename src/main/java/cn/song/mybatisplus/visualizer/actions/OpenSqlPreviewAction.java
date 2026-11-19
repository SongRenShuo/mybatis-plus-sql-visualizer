// T-SyncK2JMaster180 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/actions/OpenSqlPreviewAction.kt 移植
package cn.song.mybatisplus.visualizer.actions;

import cn.song.mybatisplus.visualizer.log.SqlLogManager;
import cn.song.mybatisplus.visualizer.log.SqlLogManagerLookupKt;
import cn.song.mybatisplus.visualizer.ui.standalone.StandaloneSqlSourceResolver;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * 打开 SQL 预览工作台（T-SqlPreviewStandalone D-3/D-4）：独立于语句入口的预览入口——
 * 主侧边栏切到「SQL 预览」tab 并聚焦输入框；剪贴板文本疑似 SQL / mapper XML 时预填并
 * 直接解析（D-4，判定复用 {@link StandaloneSqlSourceResolver#isClipboardCandidate}——与解析
 * 链同源，遵循 dbx「执行器认什么检测器就认什么」纪律）。
 *
 * <p>工具窗未创建（本会话从未打开过侧边栏）时先 show 工具窗触发工厂建面板（面板 init 注册
 * 通道回调），再于一拍后重走通道；registry {@code sqlvis.preview.standaloneTab} 关闭时由
 * {@code cn.song.mybatisplus.visualizer.log.SqlLogPanel.openStandalonePreview} 轻通知指路。</p>
 */
public class OpenSqlPreviewAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        SqlLogManager manager = SqlLogManagerLookupKt.sqlLogManagerOrNull(project);
        if (manager == null) {
            return;
        }
        String prefill = clipboardPrefillCandidate();
        Consumer<String> bridge = manager.onOpenStandalonePreview;
        if (bridge != null) {
            bridge.accept(prefill);
            return;
        }
        // 工具窗未创建过：show 触发工厂建面板（面板 init 注册回调），随后一拍再走通道
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(SqlLogManager.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return;
        }
        toolWindow.show(null);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            SqlLogManager created = SqlLogManagerLookupKt.sqlLogManagerIfCreated(project);
            if (created != null && created.onOpenStandalonePreview != null) {
                created.onOpenStandalonePreview.accept(prefill);
            }
        }, ModalityState.nonModal());
    }

    /** D-4：剪贴板候选判定（解析器同源）；异常降级为 null（不因剪贴板不可读阻断入口）。 */
    @Nullable
    private String clipboardPrefillCandidate() {
        String text;
        try {
            text = UiTextClipboard.readText();
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            return null;
        }
        if (text == null) {
            return null;
        }
        return StandaloneSqlSourceResolver.isClipboardCandidate(text) ? text : null;
    }

    // ---------------------------------------------------------------------------
    // companion object（@JvmStatic 落类级静态方法；plugin.xml text/description 引用）
    // ---------------------------------------------------------------------------

    /** bundle 键（plugin.xml text/description 引用，约定 {@code action.<id>.<prop>}）。 */
    public static String text() {
        return MpSqlVisualizerBundle.message("action.cn.song.mybatisplus.visualizer.OpenSqlPreview.text");
    }

    /** bundle 键（plugin.xml text/description 引用，约定 {@code action.<id>.<prop>}）。 */
    public static String description() {
        return MpSqlVisualizerBundle.message("action.cn.song.mybatisplus.visualizer.OpenSqlPreview.description");
    }
}
