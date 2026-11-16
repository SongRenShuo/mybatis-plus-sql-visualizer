package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.ui.switchDataSourceTo
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import javax.swing.Icon

/**
 * T-DsListAddSource（20261111）：数据源列表末尾的「添加数据源…」——接 **IDE 原生新建数据源流**，
 * 插件不造任何表单。用于替换列表末尾那条解析不出名字的「unknown」死项。
 *
 * **平台链路**（243 `database-plugin.jar` 字节码实证，开发计划 §3.3）：
 * `DatabaseViewActions.getCreateDataSourceAction(manager, project, title, includeSeparator, consumer)`
 * → 内部调 `DataSourceManager.getCreateDataSourceAction(consumer')` 得驱动组
 * （`LocalDataSourceManager$NewDataSourceActionGroup`，LRU 排序）→
 * `DatabaseViewActions.lambda$getCreateDataSourceAction$0`：`AbstractDataSource.setGroupName(title)` +
 * `onDataSourceCreated(...)` → `DbPsiFacadeImpl.createDataSourceWrapperElement` →
 * `DataSourceManagerDialog.showDialog(facade, dsWrapper, null, consumer, runnable)`——
 * 即数据库工具窗「+」同一条菜单、同一个原生属性对话框。
 *
 * **为什么取子项自己渲染而不是直接弹原生组**（P-2 预证）：`NewDataSourceActionGroup.update`
 * 调 `DatabaseViewActions.isDataSourceActionsEnabled`，要求 DataContext 带
 * `DatabaseView.DATABASE_VIEW_KEY`（javap 实证，20261112）——我们的弹层永远没有这个 key，
 * 整组会被置灰/隐藏。驱动子项（`LocalDataSourceManager$2`）自身无 update 门，
 * `actionPerformed` 只读构造期捕获字段（driver + consumer），逐项取出照常可用。
 *
 * **反射隔离**：全部 `com.intellij.database.*` 经 `Class.forName`；Community / 平台类缺席时
 * [isAvailable] 返回 false，调用方按 D-3 隐藏条目（不显示置灰死项，也不报错）。
 */
internal object NewDataSourceEntry {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(NewDataSourceEntry::class.java)

    private const val DB_VIEW_ACTIONS_CLASS = "com.intellij.database.actions.DatabaseViewActions"
    private const val DS_MANAGER_CLASS = "com.intellij.database.psi.DataSourceManager"
    private const val LOCAL_DS_CLASS = "com.intellij.database.dataSource.LocalDataSource"
    private const val DS_MANAGER_DIALOG_CLASS = "com.intellij.database.view.ui.DataSourceManagerDialog"

    /** Class 缓存（F68a 同款：负缓存用 Optional，Community 下 forName 恒抛只付一次）。 */
    private val classCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Class<*>>>()

    private fun cls(fqcn: String): Class<*>? =
        classCache.getOrPut(fqcn) {
            java.util.Optional.ofNullable(runCatching { Class.forName(fqcn) }.getOrNull())
        }.orElse(null)

    /** 驱动子项投影（原生组子项的 名称/图标/动作 三元组）。 */
    internal class DriverItem(val text: String, val icon: Icon?, internal val action: AnAction)

    /**
     * 测试缝：强制覆盖可用性判定（沙盒测试环境没有 Database 插件时默认恒 false，
     * 用例无法走「末尾添加项」断言）。命名照仓内 `unknownTargetQueryConfirmationForTest` 惯例。
     */
    internal var availabilityForTest: ((Project) -> Boolean)? = null

    /** 平台「新建数据源」入口是否可用。决定列表是否显示「添加数据源…」（D-3：隐藏而非置灰）。 */
    internal fun isAvailable(project: Project): Boolean =
        availabilityForTest?.invoke(project) ?: nativeDatabasePresent()

    /**
     * Database 插件是否在位——**只做类可达判定，不展开原生动作组**。
     *
     * D8（真机 2026.11.12 2026.3 日志）：本方法被菜单更新路径（`ChooseDataSourceTakeoverGroup.getChildren`）
     * 调用，原本在里面调 `ActionGroup.getChildren(null)` 展开驱动组；2026.3 平台把这种手写展开记为
     * **插件错误**并抛 `Throwable: Do not call getChildren(null). Do not expand action groups manually.`
     * （`DefaultActionGroup.reportGetChildrenForNullEvent`）。菜单更新是高频路径，不能在这里展开组。
     */
    private fun nativeDatabasePresent(): Boolean =
        cls(LOCAL_DS_CLASS) != null && cls(DS_MANAGER_CLASS) != null && cls(DB_VIEW_ACTIONS_CLASS) != null

    /**
     * 弹平台原生「新建数据源」驱动列表（D-4 主路：二级菜单，对齐原生「+」的形态）。
     *
     * 驱动组不可用（空 / 反射失败）时按计划 §四 D-4 降级：直开「数据源和驱动程序」窗口。
     * 返回 false = 两条路都没走通（只记日志，不抛——列表动作不允许把异常漏给 UI）。
     */
    internal fun openCreatePopup(project: Project, anchor: java.awt.Component?): Boolean {
        val actions = buildNativeDriverActions(project)
        if (!actions.isNullOrEmpty()) {
            runCatching {
                val step = object : BaseListPopupStep<DriverItem>(
                    MpSqlVisualizerBundle.message("execute.datasource.add"),
                    actions,
                ) {
                    override fun getTextFor(value: DriverItem): String = value.text
                    override fun getIconFor(value: DriverItem): Icon? = value.icon
                    override fun onChosen(value: DriverItem, finalChoice: Boolean): PopupStep<*>? = doFinalStep {
                        runCatching {
                            // 子项 actionPerformed 只读捕获字段，事件参数不参与（javap 实证）；
                            // place 随意给一个合法串即可。
                            value.action.actionPerformed(
                                AnActionEvent.createFromAnAction(
                                    value.action, null, "MpSqlVisualizer", DataContext { _ -> null },
                                ),
                            )
                        }.onFailure { e ->
                            LOG.warn("[DsList][AddSource] driver pick FAILED: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
                val popup = JBPopupFactory.getInstance().createListPopup(step)
                if (anchor != null && anchor.isShowing) {
                    popup.showUnderneathOf(anchor)
                } else {
                    popup.showInFocusCenter()
                }
                LOG.info("[DsList][AddSource] native driver popup shown (${actions.size} drivers)")
                return true
            }.onFailure { e ->
                LOG.warn("[DsList][AddSource] driver popup FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return openManagerDialog(project)
    }

    /** D-4 降级路：直开「数据源和驱动程序」窗口（`showDialog(Project, Object, DatabaseCredentials)`）。 */
    private fun openManagerDialog(project: Project): Boolean = runCatching {
        val dialogClass = cls(DS_MANAGER_DIALOG_CLASS) ?: return false
        val method = dialogClass.methods.firstOrNull {
            it.name == "showDialog" && it.parameterTypes.size == 3 && it.parameterTypes[0] == Project::class.java
        } ?: return false
        method.invoke(null, project, null, null)
        LOG.info("[DsList][AddSource] fallback: DataSourceManagerDialog opened")
        true
    }.onFailure { e ->
        LOG.warn("[DsList][AddSource] manager dialog FAILED: ${e.javaClass.simpleName}: ${e.message}")
    }.getOrDefault(false)

    /**
     * 取平台驱动组的子项投影。null = 平台不可用/反射失败（调用方隐藏条目）。
     * 空列表按 null 处理（空组渲染出来也是空弹层）。
     */
    private fun buildNativeDriverActions(project: Project): List<DriverItem>? {
        val localDsClass = cls(LOCAL_DS_CLASS) ?: return null
        val managerClass = cls(DS_MANAGER_CLASS) ?: return null
        val dbActionsClass = cls(DB_VIEW_ACTIONS_CLASS) ?: return null
        val consumer = OnCreatedConsumer(project)
        val group = runCatching {
            val manager = managerClass
                .getMethod("byDataSource", Project::class.java, Class::class.java)
                .invoke(null, project, localDsClass)
            val method = dbActionsClass.getMethod(
                "getCreateDataSourceAction",
                managerClass, Project::class.java, String::class.java, java.lang.Boolean.TYPE,
                com.intellij.util.Consumer::class.java,
            )
            // 第 4 参 false：不附加 Separator 与「from URL/path」等工具窗专属项（javap 实证该开关语义）
            method.invoke(null, manager, project, "", false, consumer) as? AnAction
        }.getOrNull() ?: return null
        val actionGroup = group as? ActionGroup ?: return null
        // D8（2026.3 平台实证）：`getChildren(null)` 已被平台禁止——`DefaultActionGroup.reportGetChildrenForNullEvent`
        // 会抛 `Throwable: Do not call getChildren(null). Do not expand action groups manually.`，
        // IDE 直接记一条插件错误。这里传一个**真实的事件对象**（不是 null），并把这条路径限制在
        // 用户点击「添加数据源…」时执行（`isAvailable` 已改为纯类可达判定，不再展开组）。
        val event = AnActionEvent.createFromDataContext("MpSqlVisualizer", null, DataContext.EMPTY_CONTEXT)
        val children = runCatching { actionGroup.getChildren(event).toList() }.getOrNull() ?: return null
        val items = ArrayList<DriverItem>(children.size)
        for (action in children) {
            val text = runCatching { action.templatePresentation.text }.getOrNull()
                ?: runCatching { action.templateText }.getOrNull()
            if (text.isNullOrBlank()) continue
            items += DriverItem(text, runCatching { action.templatePresentation.icon }.getOrNull(), action)
        }
        return items.ifEmpty { null }
    }

    /**
     * 平台回调：用户在原生属性对话框确认后收到新建的 `DbDataSource`。
     *
     * P-5（未证，真机验收核）：回调时机按「对话框 OK 之后、数据源已注册」对待——
     * 因此自动切换前先核 `DataSourceStorage` 里确实在册，防「对话框一打开就回调」
     * 的口径把预览切到一个还没配完的空源上。
     *
     * D-1（用户未拍板，按计划建议执行）：建源成功自动切到新源——复用换源链
     * （清 pin + 全源枚举 + 只重建控制台，不提交 SQL，执行仍走「执行 SQL」入口过闸）。
     */
    private class OnCreatedConsumer(private val project: Project) : com.intellij.util.Consumer<Any> {
        override fun consume(created: Any) {
            runCatching {
                val local = DataSourceSettings.resolveLocalDataSource(created)
                val name = local?.let { DataSourceSettings.nameOrNull(it) }
                val registered = local?.let { ds ->
                    DataSourceSettings.getUniqueId(ds)?.let { DataSourceSettings.getDataSourceById(project, it) != null }
                } ?: false
                LOG.info(
                    "[DsList][AddSource] platform onCreated: name=$name, registered=$registered, " +
                        "class=${created.javaClass.name}",
                )
                if (name.isNullOrBlank() || !registered) return
                val panel = cn.song.mybatisplus.visualizer.ui.SqlPreviewService.activePreviewPanel(project) ?: return
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    runCatching {
                        panel.switchDataSourceTo(name)
                    }.onFailure { e ->
                        LOG.warn("[DsList][AddSource] auto-switch to '$name' FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }.onFailure { e ->
                LOG.warn("[DsList][AddSource] onCreated handler FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
