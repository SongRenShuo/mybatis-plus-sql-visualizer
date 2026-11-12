package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import kotlin.jvm.functions.Function1;

import javax.swing.Icon;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T-DsListAddSource（20261111）：数据源列表末尾的「添加数据源…」——接 <b>IDE 原生新建数据源流</b>，
 * 插件不造任何表单。用于替换列表末尾那条解析不出名字的「unknown」死项。
 *
 * <p><b>平台链路</b>（243 {@code database-plugin.jar} 字节码实证，开发计划 §3.3）：
 * {@code DatabaseViewActions.getCreateDataSourceAction(manager, project, title, includeSeparator, consumer)}
 * → 内部调 {@code DataSourceManager.getCreateDataSourceAction(consumer')} 得驱动组
 * （{@code LocalDataSourceManager$NewDataSourceActionGroup}，LRU 排序）→
 * {@code DatabaseViewActions.lambda$getCreateDataSourceAction$0}：{@code AbstractDataSource.setGroupName(title)} +
 * {@code onDataSourceCreated(...)} → {@code DbPsiFacadeImpl.createDataSourceWrapperElement} →
 * {@code DataSourceManagerDialog.showDialog(facade, dsWrapper, null, consumer, runnable)}——
 * 即数据库工具窗「+」同一条菜单、同一个原生属性对话框。</p>
 *
 * <p><b>为什么取子项自己渲染而不是直接弹原生组</b>（P-2 预证）：{@code NewDataSourceActionGroup.update}
 * 调 {@code DatabaseViewActions.isDataSourceActionsEnabled}，要求 DataContext 带
 * {@code DatabaseView.DATABASE_VIEW_KEY}（javap 实证，20261112）——我们的弹层永远没有这个 key，
 * 整组会被置灰/隐藏。驱动子项（{@code LocalDataSourceManager$2}）自身无 update 门，
 * {@code actionPerformed} 只读构造期捕获字段（driver + consumer），逐项取出照常可用。</p>
 *
 * <p><b>反射隔离</b>：全部 {@code com.intellij.database.*} 经 {@code Class.forName}；Community / 平台类缺席时
 * {@link #isAvailable} 返回 false，调用方按 D-3 隐藏条目（不显示置灰死项，也不报错）。</p>
 *
 * <p>k2j 移植说明：master 侧为 {@code execute/NewDataSourceEntry.kt}（internal object）——object 落
 * 静态形态 final 类；{@code availabilityForTest} 函数类型落 {@code Function1<Project, Boolean>}
 * （禁 Supplier/Predicate，Kotlin 调用方不做 SAM 转换）。</p>
 */
public final class NewDataSourceEntry {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(NewDataSourceEntry.class);

    private static final String DB_VIEW_ACTIONS_CLASS = "com.intellij.database.actions.DatabaseViewActions";
    private static final String DS_MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager";
    private static final String LOCAL_DS_CLASS = "com.intellij.database.dataSource.LocalDataSource";
    private static final String DS_MANAGER_DIALOG_CLASS = "com.intellij.database.view.ui.DataSourceManagerDialog";

    /** Class 缓存（F68a 同款：负缓存用 Optional，Community 下 forName 恒抛只付一次）。 */
    private static final ConcurrentHashMap<String, Optional<Class<?>>> classCache = new ConcurrentHashMap<>();

    private NewDataSourceEntry() {
    }

    private static Class<?> cls(String fqcn) {
        return classCache.computeIfAbsent(fqcn, key -> {
            try {
                return Optional.of(Class.forName(key));
            } catch (Throwable ignored) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** 驱动子项投影（原生组子项的 名称/图标/动作 三元组）。 */
    public static final class DriverItem {
        public final String text;
        public final Icon icon;
        final AnAction action;

        public DriverItem(String text, Icon icon, AnAction action) {
            this.text = text;
            this.icon = icon;
            this.action = action;
        }
    }

    /**
     * 测试缝：强制覆盖可用性判定（沙盒测试环境没有 Database 插件时默认恒 false，
     * 用例无法走「末尾添加项」断言）。命名照仓内 {@code unknownTargetQueryConfirmationForTest} 惯例。
     */
    public static volatile Function1<Project, Boolean> availabilityForTest = null;

    /** 平台「新建数据源」入口是否可用。决定列表是否显示「添加数据源…」（D-3：隐藏而非置灰）。 */
    public static boolean isAvailable(Project project) {
        Function1<Project, Boolean> override = availabilityForTest;
        if (override != null) {
            Boolean result = override.invoke(project);
            if (result != null) return result;
        }
        return nativeDatabasePresent();
    }

    /**
     * Database 插件是否在位——<b>只做类可达判定，不展开原生动作组</b>。
     *
     * <p>D8（真机 2026.11.12 2026.3 日志）：本方法被菜单更新路径（{@code ChooseDataSourceTakeoverGroup.getChildren}）
     * 调用，原本在里面调 {@code ActionGroup.getChildren(null)} 展开驱动组；2026.3 平台把这种手写展开记为
     * <b>插件错误</b>并抛 {@code Throwable: Do not call getChildren(null). Do not expand action groups manually.}
     * （{@code DefaultActionGroup.reportGetChildrenForNullEvent}）。菜单更新是高频路径，不能在这里展开组。</p>
     */
    private static boolean nativeDatabasePresent() {
        return cls(LOCAL_DS_CLASS) != null && cls(DS_MANAGER_CLASS) != null && cls(DB_VIEW_ACTIONS_CLASS) != null;
    }

    /**
     * 弹平台原生「新建数据源」驱动列表（D-4 主路：二级菜单，对齐原生「+」的形态）。
     *
     * <p>驱动组不可用（空 / 反射失败）时按计划 §四 D-4 降级：直开「数据源和驱动程序」窗口。
     * 返回 false = 两条路都没走通（只记日志，不抛——列表动作不允许把异常漏给 UI）。</p>
     */
    public static boolean openCreatePopup(Project project, java.awt.Component anchor) {
        List<DriverItem> actions = buildNativeDriverActions(project);
        if (actions != null && !actions.isEmpty()) {
            try {
                BaseListPopupStep<DriverItem> step = new BaseListPopupStep<DriverItem>(
                        MpSqlVisualizerBundle.message("execute.datasource.add"), actions) {
                    @Override
                    public String getTextFor(DriverItem value) {
                        return value.text;
                    }

                    @Override
                    public Icon getIconFor(DriverItem value) {
                        return value.icon;
                    }

                    @Override
                    public PopupStep<?> onChosen(DriverItem value, boolean finalChoice) {
                        return doFinalStep(() -> {
                            try {
                                // 子项 actionPerformed 只读捕获字段，事件参数不参与（javap 实证）；
                                // place 随意给一个合法串即可。
                                value.action.actionPerformed(AnActionEvent.createFromAnAction(
                                        value.action, null, "MpSqlVisualizer",
                                        (DataContext) dataId -> null));
                            } catch (Throwable e) {
                                LOG.warn("[DsList][AddSource] driver pick FAILED: "
                                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                            }
                        });
                    }
                };
                com.intellij.openapi.ui.popup.JBPopup popup =
                        JBPopupFactory.getInstance().createListPopup(step);
                if (anchor != null && anchor.isShowing()) {
                    popup.showUnderneathOf(anchor);
                } else {
                    popup.showInFocusCenter();
                }
                LOG.info("[DsList][AddSource] native driver popup shown (" + actions.size() + " drivers)");
                return true;
            } catch (Throwable e) {
                LOG.warn("[DsList][AddSource] driver popup FAILED: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return openManagerDialog(project);
    }

    /** D-4 降级路：直开「数据源和驱动程序」窗口（{@code showDialog(Project, Object, DatabaseCredentials)}）。 */
    private static boolean openManagerDialog(Project project) {
        try {
            Class<?> dialogClass = cls(DS_MANAGER_DIALOG_CLASS);
            if (dialogClass == null) return false;
            Method method = null;
            for (Method m : dialogClass.getMethods()) {
                if (m.getName().equals("showDialog") && m.getParameterTypes().length == 3
                        && m.getParameterTypes()[0] == Project.class) {
                    method = m;
                    break;
                }
            }
            if (method == null) return false;
            method.invoke(null, project, null, null);
            LOG.info("[DsList][AddSource] fallback: DataSourceManagerDialog opened");
            return true;
        } catch (Throwable e) {
            LOG.warn("[DsList][AddSource] manager dialog FAILED: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 取平台驱动组的子项投影。null = 平台不可用/反射失败（调用方隐藏条目）。
     * 空列表按 null 处理（空组渲染出来也是空弹层）。
     */
    private static List<DriverItem> buildNativeDriverActions(Project project) {
        Class<?> localDsClass = cls(LOCAL_DS_CLASS);
        if (localDsClass == null) return null;
        Class<?> managerClass = cls(DS_MANAGER_CLASS);
        if (managerClass == null) return null;
        Class<?> dbActionsClass = cls(DB_VIEW_ACTIONS_CLASS);
        if (dbActionsClass == null) return null;
        com.intellij.util.Consumer<Object> consumer = new OnCreatedConsumer(project);
        AnAction group;
        try {
            Object manager = managerClass
                    .getMethod("byDataSource", Project.class, Class.class)
                    .invoke(null, project, localDsClass);
            Method method = dbActionsClass.getMethod(
                    "getCreateDataSourceAction",
                    managerClass, Project.class, String.class, Boolean.TYPE,
                    com.intellij.util.Consumer.class);
            // 第 4 参 false：不附加 Separator 与「from URL/path」等工具窗专属项（javap 实证该开关语义）
            group = (AnAction) method.invoke(null, manager, project, "", false, consumer);
        } catch (Throwable ignored) {
            return null;
        }
        if (!(group instanceof ActionGroup)) return null;
        ActionGroup actionGroup = (ActionGroup) group;
        // D8（2026.3 平台实证）：`getChildren(null)` 已被平台禁止——`DefaultActionGroup.reportGetChildrenForNullEvent`
        // 会抛 `Throwable: Do not call getChildren(null). Do not expand action groups manually.`，
        // IDE 直接记一条插件错误。这里传一个**真实的事件对象**（不是 null），并把这条路径限制在
        // 用户点击「添加数据源…」时执行（`isAvailable` 已改为纯类可达判定，不再展开组）。
        AnAction[] children;
        try {
            AnActionEvent event = AnActionEvent.createFromDataContext("MpSqlVisualizer", null, DataContext.EMPTY_CONTEXT);
            children = actionGroup.getChildren(event);
        } catch (Throwable ignored) {
            return null;
        }
        if (children == null) return null;
        List<DriverItem> items = new ArrayList<>(children.length);
        for (AnAction action : children) {
            String text = null;
            try {
                text = action.getTemplatePresentation().getText();
            } catch (Throwable ignored) {
                // getOrNull 语义
            }
            if (text == null) {
                try {
                    text = action.getTemplateText();
                } catch (Throwable ignored) {
                    // getOrNull 语义
                }
            }
            if (text == null || text.isBlank()) continue;
            Icon icon = null;
            try {
                icon = action.getTemplatePresentation().getIcon();
            } catch (Throwable ignored) {
                // getOrNull 语义
            }
            items.add(new DriverItem(text, icon, action));
        }
        return items.isEmpty() ? null : items;
    }

    /**
     * 平台回调：用户在原生属性对话框确认后收到新建的 {@code DbDataSource}。
     *
     * <p>P-5（未证，真机验收核）：回调时机按「对话框 OK 之后、数据源已注册」对待——
     * 因此自动切换前先核 {@code DataSourceStorage} 里确实在册，防「对话框一打开就回调」
     * 的口径把预览切到一个还没配完的空源上。</p>
     *
     * <p>D-1（用户未拍板，按计划建议执行）：建源成功自动切到新源——复用换源链
     * （清 pin + 全源枚举 + 只重建控制台，不提交 SQL，执行仍走「执行 SQL」入口过闸）。</p>
     */
    private static final class OnCreatedConsumer implements com.intellij.util.Consumer<Object> {
        private final Project project;

        OnCreatedConsumer(Project project) {
            this.project = project;
        }

        @Override
        public void consume(Object created) {
            try {
                Object local = DataSourceSettings.resolveLocalDataSource(created);
                String name = local != null ? DataSourceSettings.nameOrNull(local) : null;
                boolean registered = false;
                if (local != null) {
                    String uid = DataSourceSettings.getUniqueId(local);
                    registered = uid != null && DataSourceSettings.getDataSourceById(project, uid) != null;
                }
                LOG.info("[DsList][AddSource] platform onCreated: name=" + name + ", registered=" + registered
                        + ", class=" + created.getClass().getName());
                if (name == null || name.isBlank() || !registered) return;
                cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel panel =
                        cn.song.mybatisplus.visualizer.ui.SqlPreviewService.activePreviewPanel(project);
                if (panel == null) return;
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) return;
                    try {
                        cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelExecutionKt.switchDataSourceTo(panel, name);
                    } catch (Throwable e) {
                        LOG.warn("[DsList][AddSource] auto-switch to '" + name + "' FAILED: "
                                + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                });
            } catch (Throwable e) {
                LOG.warn("[DsList][AddSource] onCreated handler FAILED: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }
}
