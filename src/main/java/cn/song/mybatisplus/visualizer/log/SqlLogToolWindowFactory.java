package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlDialectMappingPrewarm;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.NotNull;

/**
 * MyBatis SQL 日志工具窗口（T-M31-04）：三标签页（SQL 日志列表 / 日志转 SQL / SQL → Java），
 * 全部使用 IDEA 原生组件（JBTabbedPane 主题化标签页 + ActionToolbar 扁平图标工具栏 + tooltip）。
 *
 * <p>改写为 Java 以消除 Kotlin 编译器为 ToolWindowFactory 接口全部默认方法生成的 synthetic bridge
 * （getAnchor / getIcon / manage / isDoNotActivateOnStart / isApplicable），
 * Java 编译器只为源码中显式声明的方法生成 override，避免 Plugin Verifier Internal API 违规。
 */
public class SqlLogToolWindowFactory implements ToolWindowFactory {

    private static final Logger LOG = Logger.getInstance(SqlLogToolWindowFactory.class);
    private static final String TITLE_KEY = "log.toolwindow.title";

    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        // F-41：侧边栏按钮 tooltip 显示正确名称（非内部 ID）
        // 根因：ActivateToolWindowAction.updatePresentation() 读取 toolWindow.getStripeTitleProvider()，
        // 空值回退到 tool window ID。工厂的 createToolWindowContent 在 init 后才执行，
        // 导致首次 hover 时 provider 未设置，tooltip 显示 ID。
        // 修复：在 init() 中立即设置 title，早于 createToolWindowContent。
        toolWindow.setStripeTitle(MpSqlVisualizerBundle.message(TITLE_KEY));
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 窗口标题（stripe 标签）：显示 bundle 文案而非窗口 ID
        String title = MpSqlVisualizerBundle.message(TITLE_KEY);
        toolWindow.setStripeTitle(title);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!toolWindow.isDisposed()) {
                toolWindow.setStripeTitle(title);
            }
        });

        // T-SqlDialectPrewarm：顺序是承重墙——SqlLogPanel 构造链上会创建 SQL 文件类型编辑器
        // （LogToSqlPanel 结果区、SqlToJavaPanel 输入区），"取平台 SQL 高亮器/按 SQL FileType 建编辑器"
        // 首查会触发 SqlDialectMappings 的一次性延迟加载（逐条 VirtualFileManager.findFileByUrl），
        // 在 EDT 上即平台慢操作断言（真机栈：LogToSqlPanel.createResultEditor → FSRecordsImpl.update
        // → SlowOperations）。这里先把它挪到 BGT 做完再装面板；有界等待 300ms，超时/失败照原样继续。
        SqlDialectMappingPrewarm.prewarmAndAwait(project);

        SqlLogManager manager = project.getService(SqlLogManager.class);
        SqlLogPanel panel = new SqlLogPanel(project, manager);
        // T-CaptureBack：捕捉开关已回归面板工具栏（T-UN-11 曾挂标题栏，新 UI 下悬停才可见
        // 致入口不可发现），不再用 setTitleActions 挂载，避免标题栏残留重复入口。
        // content 标题留空（标题栏由 stripe 标题唯一承担，避免重复显示）
        var content = toolWindow.getContentManager().getFactory().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);

        // 工具窗口关闭（content 移除）→ 设置开启时停止捕捉（兜底：部分平台把关闭实现为移除 content）
        // T-Perf-15：匿名 listener 挂 content disposer——content 销毁时自动移除（原实现永不移除，
        // 工具窗口反复移出/恢复逐次累积监听器）
        ContentManagerListener contentListener = new ContentManagerListener() {
            @Override
            public void contentRemoved(@NotNull ContentManagerEvent event) {
                if (event.getContent() == content) {
                    PluginSettings settings = PluginSettings.getInstance();
                    if (settings.getState().getLogStopOnToolWindowClose() && manager.getCapturing()) {
                        manager.stopCapture();
                        LOG.info("ToolWindow 关闭（content 移除）：已停止捕捉（project=" + project.getName() + "）");
                    }
                }
            }
        };
        toolWindow.getContentManager().addContentManagerListener(contentListener);
        com.intellij.openapi.util.Disposer.register(content.getDisposer(), () -> {
            try {
                toolWindow.getContentManager().removeContentManagerListener(contentListener);
            } catch (Throwable ignored) {
                // content manager 可能已在窗口关闭链中销毁（销毁顺序不定），忽略
            }
        });

        // 关闭/折叠侧边栏面板（ToolWindow 隐藏，content 仍在）→ 设置开启时停止捕捉。
        // contentRemoved 在新 UI 折叠/隐藏时不触发，须监听 ToolWindow 可见性——沿
        // 「曾经可见 → 当前不可见」转换沿 stop（新建窗口/注册/布局等中间态不误停）。
        // 该监听用 project messageBus 编程注册（plugin.xml 不支持 <listener>），
        // connect(content.disposer) 随窗口真正销毁自动断开。
        project.getMessageBus().connect(content.getDisposer()).subscribe(
                ToolWindowManagerListener.TOPIC,
                new ToolWindowVisibilityListener(project, toolWindow, manager)
        );
    }
}
