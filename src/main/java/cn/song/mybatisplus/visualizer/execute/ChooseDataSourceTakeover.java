package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelExecutionKt;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.update.BarrierRegistry;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.Component;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T-NativeSwitchTakeover（2026-11-02 用户定案「只换源不执行」）：把控制台工具栏的原生
 * 数据源下拉接管到插件链路。
 *
 * <p><b>原生下拉实际做什么</b>（262 {@code intellij.database.impl.jar} 常量池实证）：
 * {@code Console.Jdbc.Right} → {@code Console.Jdbc.ChooseDataSource} = {@code com.intellij.database.run.actions
 * .ChooseDataSourceAction}（{@link ActionGroup}）。它 {@code getRunners(session)} 用
 * {@code DatabaseRunners.getSwitchDataSourceRunners(...)}，点选后走
 * {@code RunQueryInConsoleIntentionAction$Manager.chooseAndRunRunners(...)} → {@code runner.run()}，其
 * {@code FileActionContext} 再做 {@code DataSourcePerFileMappings.setMappingAsync(vf, ds)} +
 * {@code JdbcConsoleProvider.reattachConsole(project, session, vf)}——即「<b>换源 + 立即执行 + 重挂
 * 一个新控制台</b>」。</p>
 *
 * <p>后果（2026-11-02 真机）：新控制台挂在同一 exec 文件上，但预览面板嵌的仍是旧控制台对象，
 * {@code preparedConsole}/{@code sharedConsole} 缓存也指着旧的 → 用户看到「点了没反应」；更要紧的是它
 * 绕过了插件的执行闸门（DML 二次确认、{@code ${}} 原文段确认、未知参数确认、方言闸、留痕）。</p>
 *
 * <p><b>接管方式</b>：{@code ActionManager.replaceAction("Console.Jdbc.ChooseDataSource", 本包装组)}。
 * 平台自建 header 正是按 id 从 ActionManager 取这两个组（{@code JdbcConsole.buildHeaderComponent}
 * 的 {@code Console.Jdbc.Left/Right}），替换在控制台创建之前生效即可被工具栏取到。</p>
 *
 * <p><b>语义（用户定案）</b>：事件文件是本插件的 exec 文件时，子项改成「每个数据源一项」，点选走
 * {@link SqlPreviewPanelExecutionKt#switchDataSourceTo}（清 pin + 全源枚举 + 只重建控制台，
 * 不提交 SQL，执行仍由「执行 SQL」入口过闸）；其它文件<b>原样委托</b>原生动作，不改变 IDE 其它
 * 场景的行为。</p>
 *
 * <p>移植注记：master 的 ChooseDataSourceTakeover.kt 为「顶层函数 + internal class + Light Service」
 * 三合一文件；Java 侧按门面类承载（顶层函数→静态方法，两个类→嵌套静态类），仅形态不同、逐行为保真。</p>
 */
public final class ChooseDataSourceTakeover {

    private ChooseDataSourceTakeover() {}

    /**
     * 接管判定（纯函数，测试直测）：事件文件是本插件 exec 文件时才接管。
     *
     * <p>与执行链共用同一谓词 {@link DataSourceSelector#isExecVfPath}，避免 exec 目录改名后接管静默失效
     * （失效后果＝子项悄悄退回原生「换源并执行」，绕过执行闸门而不报错）。</p>
     */
    public static boolean takeoverAppliesTo(@Nullable String filePath) {
        return filePath != null && DataSourceSelector.isExecVfPath(filePath);
    }

    /**
     * 接管包装组：事件文件是本插件 exec 文件时子项改为「每数据源一项」，其它场景逐字委托原生组。
     */
    public static final class ChooseDataSourceTakeoverGroup extends ActionGroup {

        private final AnAction original;

        private final Logger LOG = Logger.getInstance(ChooseDataSourceTakeoverGroup.class);

        /** 一次性取证位（update 每次刷新都会走，只留一条避免刷屏）。 */
        private final AtomicBoolean updateProbed = new AtomicBoolean();

        public ChooseDataSourceTakeoverGroup(AnAction original) {
            super(original.getTemplateText() != null ? original.getTemplateText() : "", true);
            this.original = original;
            // 工具栏按钮在**构建时**读模板呈现：
            //  - `setPerformGroup(true)` 是平台把「组」渲染成「图标+文案」按钮的开关（原生 update 里也设它）；
            //  - `setPopup(false)`：弹层由本类 `actionPerformed` 自己弹，不需要平台把它当 popup 组
            //    （popup 组在工具栏里被渲染成「图标+箭头」，文案永远不显示——真机 2026-11-02 现象：
            //    只有图标、悬停才有名字）。
            // master 侧 runCatching 降级：呈现兜底失败不阻断接管（结构性决策，勿当缺陷加日志/重抛）。
            try {
                setPopup(false);
                getTemplatePresentation().setPerformGroup(true);
                getTemplatePresentation().setText(original.getTemplateText() != null ? original.getTemplateText() : "", false);
                Icon icon = original.getTemplatePresentation().getIcon();
                if (icon != null) {
                    getTemplatePresentation().setIcon(icon);
                }
            } catch (Throwable ignored) {
            }
        }

        /**
         * 事件文件解析：工具栏动作的 DataContext 未必带 {@code VIRTUAL_FILE}（控制台 header 只保证
         * EDITOR/PSI_FILE），因此按 文件 → PSI → 编辑器文档 三级回落；都拿不到才委托。
         */
        @Nullable
        private static VirtualFile resolveFile(@Nullable AnActionEvent e) {
            if (e == null) return null;
            VirtualFile direct = e.getData(CommonDataKeys.VIRTUAL_FILE);
            if (direct != null) return direct;
            PsiFile psi = e.getData(CommonDataKeys.PSI_FILE);
            if (psi != null && psi.getVirtualFile() != null) return psi.getVirtualFile();
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (editor == null) return null;
            // master 侧 runCatching 降级（FileDocumentManager 查询失败按拿不到处理）。
            try {
                return FileDocumentManager.getInstance().getFile(editor.getDocument());
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public AnAction @NotNull [] getChildren(@Nullable AnActionEvent e) {
            Project project = e != null ? e.getProject() : null;
            VirtualFile file = resolveFile(e);
            // 非本插件 exec 文件：逐字委托原生组（DataGrip 原生行为不受影响）
            if (project == null || !takeoverAppliesTo(file != null ? file.getPath() : null)) {
                return delegateChildren(e);
            }
            List<Object> sources = dataSourcesFor(project, file);
            // 每次点开都记一条：用户手动动作、频率极低；一次性日志在真机会漏掉「这次点开」的读数。
            LOG.info("[M36][Takeover] getChildren: sources=" + sources.size() + ", file=" + (file != null ? file.getPath() : null));
            if (sources.isEmpty()) return delegateChildren(e);
            List<AnAction> items = new ArrayList<>();
            for (Object ds : sources) {
                String name = "";
                try {
                    name = DataSourceSettings.getName(ds);
                    if (name == null) name = "";
                } catch (Throwable ignored) {
                    name = "";
                }
                Icon icon = null;
                try {
                    Object result = ds.getClass().getMethod("getIcon").invoke(ds);
                    icon = result instanceof Icon ? (Icon) result : null;
                } catch (Throwable ignored) {
                }
                items.add(new SwitchToDataSourceAction(project, name, icon));
            }
            return items.toArray(new AnAction[0]);
        }

        private AnAction @NotNull [] delegateChildren(@Nullable AnActionEvent e) {
            if (original instanceof ActionGroup) {
                AnAction[] children = ((ActionGroup) original).getChildren(e);
                return children != null ? children : AnAction.EMPTY_ARRAY;
            }
            return AnAction.EMPTY_ARRAY;
        }

        /**
         * 数据源枚举——<b>两条来源取并集</b>（各有盲区，单用任一条都会退回原生列表）。
         *
         * <ul>
         * <li>{@code DbImplUtil.getAllDataSourcesForExec(psiFile)}：原生工具栏「所有」子项的同源集合，
         * 但按 exec 文件方言过滤，方言未映射/未生效时可能为空；</li>
         * <li>{@link DataSourceSettings#getAllDataSources}：全量在册源，但<b>依赖数据源已加载</b>——真机
         * 17:17（IDE 启动 86 秒）实测返回 0，而沙盒 18:11 同一函数返回 12。</li>
         * </ul>
         *
         * 先取全量、再补原生集合里缺的（按显示名去重），任一来源有数据就能出清单。
         */
        private List<Object> dataSourcesFor(Project project, @Nullable VirtualFile vf) {
            List<Object> all;
            try {
                List<Object> fetched = DataSourceSettings.getAllDataSources(project);
                all = fetched != null ? fetched : new ArrayList<>();
            } catch (Throwable ignored) {
                all = new ArrayList<>();
            }
            List<Object> fromNative;
            try {
                // T-M52：ReadAction.compute(ThrowableComputable) 243 编译目标仅此重载可用（262 标 deprecated，非移除）
                fromNative = com.intellij.openapi.application.ReadAction.compute(() -> {
                    PsiFile psi = vf != null ? PsiManager.getInstance(project).findFile(vf) : null;
                    if (psi == null) return List.of();
                    Class<?> util = Class.forName("com.intellij.database.util.DbImplUtil");
                    Method method = util.getMethod("getAllDataSourcesForExec", PsiFile.class);
                    Object iterableObj = method.invoke(null, psi);
                    if (!(iterableObj instanceof Iterable)) return List.of();
                    List<Object> list = new ArrayList<>();
                    for (Object o : (Iterable<?>) iterableObj) {
                        if (o != null) list.add(o);
                    }
                    return list;
                });
            } catch (Throwable e) {
                LOG.warn("[M36][Takeover] native source enumeration FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                fromNative = List.of();
            }
            if (fromNative.isEmpty()) return all;
            if (all.isEmpty()) return fromNative;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Object ds : all) {
                try {
                    String name = DataSourceSettings.getName(ds);
                    if (name != null) seen.add(name);
                } catch (Throwable ignored) {
                }
            }
            List<Object> merged = new ArrayList<>(all);
            for (Object ds : fromNative) {
                String name = "";
                try {
                    name = DataSourceSettings.getName(ds);
                } catch (Throwable ignored) {
                }
                if (name != null && !name.isBlank() && seen.add(name)) merged.add(ds);
            }
            return merged;
        }

        /** 展示形态（图标/文案/可用性）交给原生动作，并把结果镜像进模板（工具栏按钮读模板）。 */
        @Override
        public void update(@NotNull AnActionEvent e) {
            original.update(e);
            if (updateProbed.compareAndSet(false, true)) {
                LOG.info("[M36][Takeover] update: text='" + e.getPresentation().getText() + "', visible=" + e.getPresentation().isVisible()
                        + ", enabled=" + e.getPresentation().isEnabled() + ", template='" + original.getTemplateText() + "'");
            }
            if (e.getPresentation().getText() == null || e.getPresentation().getText().isBlank()) {
                e.getPresentation().setText(original.getTemplateText() != null ? original.getTemplateText() : "", false);
            }
            // 262 字节码实证：原生 `update` 会 setPopop/setPerformGroup(true) 等——`setPerformGroup`
            // 正是工具栏把这个组渲染成「图标+文案」按钮（而非纯图标组）的开关。本类不能覆写 final 的
            // getTemplatePresentation，于是把原生每次算好的呈现写回模板，按钮才会显示数据源名。
            // master 侧 runCatching 降级：模板镜像失败不阻断 update 主流程。
            try {
                e.getPresentation().setPerformGroup(true);
                getTemplatePresentation().setPerformGroup(true);
                getTemplatePresentation().setText(e.getPresentation().getText() != null ? e.getPresentation().getText() : "", false);
                getTemplatePresentation().setIcon(e.getPresentation().getIcon());
                getTemplatePresentation().setEnabled(e.getPresentation().isEnabled());
                getTemplatePresentation().setVisible(e.getPresentation().isVisible());
            } catch (Throwable ignored) {
            }
        }

        /**
         * 点击落点——<b>命中本插件 exec 文件时自己弹层</b>。
         *
         * <p>262 实测两轮：</p>
         * <ul>
         * <li>委托给 {@code original.actionPerformed} → 弹的是原生「分离数据源 / 所有 &gt;」（它自己覆写了
         * 该方法来弹自己的菜单，把我们的子项整个绕过）；</li>
         * <li>{@code super.actionPerformed}（ActionGroup 默认）→ <b>什么都不弹</b>（{@code ActionGroup} 自身不建
         * 弹层，弹层由工具栏的 ActionPopupMenu 负责）。</li>
         * </ul>
         *
         * <p>故这里显式用 {@code JBPopupFactory.createListPopup} 以本组 children 建弹层，锚到事件
         * 的组件上；非本插件文件仍原样委托。</p>
         */
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            VirtualFile file = resolveFile(e);
            if (project == null || !takeoverAppliesTo(file != null ? file.getPath() : null)) {
                original.actionPerformed(e);
                return;
            }
            List<Object> sources = dataSourcesFor(project, file);
            List<com.intellij.openapi.util.Pair<String, Icon>> items = new ArrayList<>();
            for (Object ds : sources) {
                String name = null;
                try {
                    name = DataSourceSettings.getName(ds);
                } catch (Throwable ignored) {
                }
                if (name == null || name.isBlank()) continue;
                Icon icon = null;
                try {
                    Object result = ds.getClass().getMethod("getIcon").invoke(ds);
                    icon = result instanceof Icon ? (Icon) result : null;
                } catch (Throwable ignored) {
                }
                items.add(com.intellij.openapi.util.Pair.create(name, icon));
            }
            if (items.isEmpty()) {
                original.actionPerformed(e);
                return;
            }
            LOG.info("[M36][Takeover] actionPerformed -> own popup (sources=" + items.size() + ")");
            try {
                // 用 BaseListPopupStep 而不是 createActionGroupPopup：后者会把数据源名里的 `_`
                // 当助记符吃掉（真机 `mpj_demo@localhost` 显示成缺少下划线），且带搜索框、位置跟着
                // 焦点组件漂。纯列表 + showUnderneathOf 才与原生控制台那个下拉一致。
                com.intellij.openapi.ui.popup.util.BaseListPopupStep<com.intellij.openapi.util.Pair<String, Icon>> step =
                        new com.intellij.openapi.ui.popup.util.BaseListPopupStep<>(
                                MpSqlVisualizerBundle.message("execute.dialectGate.action.switchDataSource"),
                                items) {
                            @Override
                            public String getTextFor(com.intellij.openapi.util.Pair<String, Icon> value) {
                                return value.first;
                            }

                            @Override
                            public Icon getIconFor(com.intellij.openapi.util.Pair<String, Icon> value) {
                                return value.second;
                            }

                            @Override
                            public com.intellij.openapi.ui.popup.PopupStep<?> onChosen(
                                    com.intellij.openapi.util.Pair<String, Icon> selectedValue, boolean finalChoice) {
                                String picked = selectedValue.first;
                                return doFinalStep((Runnable) () -> {
                                    LOG.info("[M36][Takeover] popup picked: " + picked);
                                    SqlPreviewPanel panel = SqlPreviewService.activePreviewPanel(project);
                                    if (panel != null) {
                                        SqlPreviewPanelExecutionKt.switchDataSourceTo(panel, picked);
                                    }
                                });
                            }
                        };
                com.intellij.openapi.ui.popup.JBPopup popup =
                        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(step);
                Component comp = e.getInputEvent() != null ? e.getInputEvent().getComponent() : null;
                if (comp != null && comp.isShowing()) {
                    popup.showUnderneathOf(comp);
                } else {
                    popup.showInBestPositionFor(e.getDataContext());
                }
            } catch (Throwable ex) {
                LOG.warn("[M36][Takeover] own popup FAILED: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                original.actionPerformed(e);
            }
        }

        /** 还原用：被接管的原生动作（dispose 时换回）。 */
        public AnAction originalActionForRestore() {
            return original;
        }

        /** 子项：选中某个数据源 → 走插件换源链（只换源，不提交 SQL）。 */
        private static final class SwitchToDataSourceAction extends AnAction {

            private final Project project;
            private final String dataSourceName;

            SwitchToDataSourceAction(Project project, String dataSourceName, @Nullable Icon icon) {
                super(dataSourceName, null, icon);
                this.project = project;
                this.dataSourceName = dataSourceName;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                SqlPreviewPanel panel = SqlPreviewService.activePreviewPanel(project);
                if (panel == null) return;
                Logger.getInstance(ChooseDataSourceTakeoverGroup.class)
                        .info("[M36][SwitchDs] native toolbar pick: " + dataSourceName);
                SqlPreviewPanelExecutionKt.switchDataSourceTo(panel, dataSourceName);
            }
        }
    }

    /**
     * 接管件宿主：应用级轻服务（Disposable parent），随插件应用级容器 dispose 级联还原原生动作。
     *
     * <p>挂靠定案照 {@link ExecDocAutosaveHost}：动态卸载走平台 {@code DynamicPlugins.unloadPlugin}，容器级联
     * 释放是插件级编程式注册的惯例摘法；卸载回调里反手 dispose 自身类有 classloader 时序风险，
     * 故不订阅 {@code DynamicPluginListener}。</p>
     */
    @Service(Service.Level.APP)
    public static final class PreviewDataSourceActionHost implements com.intellij.openapi.Disposable {

        private static final String ACTION_ID = "Console.Jdbc.ChooseDataSource";

        private ChooseDataSourceTakeoverGroup installed;

        public static PreviewDataSourceActionHost getInstance() {
            return ApplicationManager.getApplication().getService(PreviewDataSourceActionHost.class);
        }

        /** 幂等安装（预览面板展示前调用；Database 插件缺席或动作不存在时静默跳过）。 */
        public static void ensureInstalled() {
            // master 侧 runCatching 降级：安装失败记日志不外抛。
            try {
                getInstance().install();
            } catch (Throwable e) {
                Logger.getInstance(PreviewDataSourceActionHost.class)
                        .warn("[M36][Takeover] install FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        public synchronized void install() {
            if (installed != null) return;
            Logger log = Logger.getInstance(PreviewDataSourceActionHost.class);
            ActionManager manager = ActionManager.getInstance();
            AnAction originalAction = manager.getAction(ACTION_ID);
            if (originalAction == null) {
                log.warn("[M36][Takeover] install skipped: action not found: " + ACTION_ID);
                return;
            }
            if (originalAction instanceof ChooseDataSourceTakeoverGroup) return;
            ChooseDataSourceTakeoverGroup takeover = new ChooseDataSourceTakeoverGroup(originalAction);
            manager.replaceAction(ACTION_ID, takeover);
            installed = takeover;
            log.info("[M36][Takeover] installed: " + ACTION_ID + " -> original=" + originalAction.getClass().getName());
            BarrierRegistry.register(new BarrierRegistry.Entry(
                    "action-takeover:" + ACTION_ID,
                    "PreviewDataSourceActionHost（ActionManager.replaceAction 接管控制台数据源下拉）",
                    BarrierRegistry.RemovalPath.DISPOSER_CASCADE_ON_UNLOAD,
                    "dispose 时若在位的仍是本接管件则换回原动作（照 EditorCommentShortcuts.uninstall 的防覆盖判定）"));
        }

        @Override
        public void dispose() {
            ChooseDataSourceTakeoverGroup takeover = installed;
            if (takeover == null) return;
            installed = null;
            // master 侧 runCatching 降级：还原失败不外抛（dispose 链路）。
            try {
                ActionManager manager = ActionManager.getInstance();
                if (manager.getAction(ACTION_ID) == takeover) {
                    manager.replaceAction(ACTION_ID, takeover.originalActionForRestore());
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
