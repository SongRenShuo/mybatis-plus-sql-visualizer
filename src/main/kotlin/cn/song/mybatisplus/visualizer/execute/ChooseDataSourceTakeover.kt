package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.switchDataSourceTo
import cn.song.mybatisplus.visualizer.update.BarrierRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import javax.swing.Icon

/**
 * T-NativeSwitchTakeover（2026-11-02 用户定案「只换源不执行」）：把控制台工具栏的原生
 * 数据源下拉接管到插件链路。
 *
 * **原生下拉实际做什么**（262 `intellij.database.impl.jar` 常量池实证）：
 * `Console.Jdbc.Right` → `Console.Jdbc.ChooseDataSource` = `com.intellij.database.run.actions
 * .ChooseDataSourceAction`（`ActionGroup`）。它 `getRunners(session)` 用
 * `DatabaseRunners.getSwitchDataSourceRunners(...)`，点选后走
 * `RunQueryInConsoleIntentionAction$Manager.chooseAndRunRunners(...)` → `runner.run()`，其
 * `FileActionContext` 再做 `DataSourcePerFileMappings.setMappingAsync(vf, ds)` +
 * `JdbcConsoleProvider.reattachConsole(project, session, vf)`——即「**换源 + 立即执行 + 重挂
 * 一个新控制台**」。
 *
 * 后果（2026-11-02 真机）：新控制台挂在同一 exec 文件上，但预览面板嵌的仍是旧控制台对象，
 * `preparedConsole`/`sharedConsole` 缓存也指着旧的 → 用户看到「点了没反应」；更要紧的是它
 * 绕过了插件的执行闸门（DML 二次确认、`${}` 原文段确认、未知参数确认、方言闸、留痕）。
 *
 * **接管方式**：`ActionManager.replaceAction("Console.Jdbc.ChooseDataSource", 本包装组)`。
 * 平台自建 header 正是按 id 从 ActionManager 取这两个组（`JdbcConsole.buildHeaderComponent`
 * 的 `Console.Jdbc.Left/Right`），替换在控制台创建之前生效即可被工具栏取到。
 *
 * **语义（用户定案）**：事件文件是本插件的 exec 文件时，子项改成「每个数据源一项」，点选走
 * [cn.song.mybatisplus.visualizer.ui.switchDataSourceTo]（清 pin + 全源枚举 + 只重建控制台，
 * 不提交 SQL，执行仍由「执行 SQL」入口过闸）；其它文件**原样委托**原生动作，不改变 IDE 其它
 * 场景的行为。
 */
/**
 * 接管判定（纯函数，测试直测）：事件文件是本插件 exec 文件时才接管。
 *
 * 与执行链共用同一谓词 [DataSourceSelector.isExecVfPath]，避免 exec 目录改名后接管静默失效
 * （失效后果＝子项悄悄退回原生「换源并执行」，绕过执行闸门而不报错）。
 */
internal fun takeoverAppliesTo(filePath: String?): Boolean =
    filePath != null && DataSourceSelector.isExecVfPath(filePath)

internal class ChooseDataSourceTakeoverGroup(
    private val original: AnAction,
) : ActionGroup(original.templateText ?: "", true) {

    init {
        // 工具栏按钮在**构建时**读模板呈现：
        //  - `setPerformGroup(true)` 是平台把「组」渲染成「图标+文案」按钮的开关（原生 update 里也设它）；
        //  - `setPopup(false)`：弹层由本类 `actionPerformed` 自己弹，不需要平台把它当 popup 组
        //    （popup 组在工具栏里被渲染成「图标+箭头」，文案永远不显示——真机 2026-11-02 现象：
        //    只有图标、悬停才有名字）。
        runCatching {
            // 2026-11-03 回退：`isPopup = false` 会让平台按「普通动作」渲染，实测把控制台 header
            // 的工具栏搞坏（连工具栏都不显示）。恢复 popup 组（图标+箭头），文案问题另解。
            templatePresentation.setPerformGroup(true)
            templatePresentation.setText(original.templateText ?: "", false)
            original.templatePresentation.icon?.let { templatePresentation.icon = it }
        }
    }

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ChooseDataSourceTakeoverGroup::class.java)

    /**
     * T-NativeSwitchTakeover：工具栏「图标 + 数据源名」的开关。
     *
     * 平台在 `AnAction.createTemplatePresentation()` 里按本方法写 `SHOW_TEXT_IN_TOOLBAR`
     * 客户端属性，`ActionToolbarImpl.createToolbarButton()` 再据此选
     * `ActionButtonWithText` 还是 `ActionButton`（243 平台 `app-client.jar` 字节码实证）。
     * 原生 `ChooseDataSourceAction.displayTextInToolbar()` 返回 **true**（javap 实证），
     * 接管组默认继承 `AnAction` 的 false → 工具栏退化成纯图标、文案只进 tooltip
     * （真机 2026-11-03 现象）。
     *
     * **不能用 `isPopup=false` 换文案**：非 popup 组在工具栏里会把子项摊平成一行按钮，
     * 实测把控制台 header 整条工具栏挤没（`31d6db38` 已回退）。文案的正确开关只有本方法。
     *
     * 覆写里不读 [original]：本方法在 `AnAction` 构造期（`createTemplatePresentation`）就会被调用，
     * 此时构造参数属性可能尚未赋值——恒 true 与原生返回值一致，且无初始化顺序风险。
     */
    override fun displayTextInToolbar(): Boolean = true

    /** 与 [displayTextInToolbar] 同源：原生未覆写（默认 false），此处保持默认。 */
    override fun useSmallerFontForTextInToolbar(): Boolean = false

    /**
     * T-ThreadFix A：**update 必须在 BGT 执行**——本组把呈现权委托给原生
     * `ChooseDataSourceAction.update`，而它内部走 `getClientPsiFile(e)` 读 `PSI_FILE`。
     *
     * 缺陷实证（2026-11-04 真机 243 沙盒，一次「预览+执行」）：`embedConsole` 把控制台组件挂进
     * 可见层级 → 原生 header 的工具栏立刻刷新 → 本组 update 在 EDT 被调用 → 原生 update 读 PSI：
     * ```
     * SEVERE - PreCachedDataContext - 'psi.File' is requested on EDT by
     *   ChooseDataSourceTakeoverGroup#presentation@EditorToolbar
     *   at ChooseDataSourceAction.getClientPsiFile → getContext → update
     *   at ChooseDataSourceTakeoverGroup.update(ChooseDataSourceTakeover.kt:170)
     * ```
     * 同一条链还叠 9 条 `SlowOperations`（`PsiManager.findFile` → `FileManagerImpl.findDirectory`
     * → `ProjectFileIndexImpl.isUnderIgnored` → `WorkspaceFileIndexDataImpl.ensureIsUpToDate`）。
     *
     * 为什么必须**显式**声明（243 字节码实证，同 [cn.song.mybatisplus.visualizer.log.SqlLogPanel]
     * 工具栏组的同款结论）：`AnAction.getActionUpdateThread()` 默认实现是「实现
     * `UpdateInBackground` 且为 true → BGT；`ActionClassMetaData.isDefaultUpdate` 为 true → BGT；
     * 否则 EDT」，而 `isDefaultUpdate` 判的是「重写 `update()` 的类是不是 `AnAction` 本身」——
     * 本类覆写了 `update()` 与 `displayTextInToolbar()`，默认落 EDT。**原生
     * `ChooseDataSourceAction` 自己 javap 实证返回 `ActionUpdateThread.BGT`**，即它的 update 本就
     * 按 BGT 设计（PSI 读走平台预缓存 DataContext），本包装组跟随即可。
     *
     * 约束：update 体内只允许「读状态 + 写 `e.presentation`/模板 Presentation」，不得触碰
     * Swing 组件、文档或 EDT-only API。
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** 一次性取证位（update 每次刷新都会走，只留一条避免刷屏）。 */
    private val updateProbed = java.util.concurrent.atomic.AtomicBoolean()

    /**
     * 事件文件解析：工具栏动作的 DataContext 未必带 `VIRTUAL_FILE`（控制台 header 只保证
     * EDITOR/PSI_FILE），因此按 文件 → PSI → 编辑器文档 三级回落；都拿不到才委托。
     */
    private fun resolveFile(e: AnActionEvent?): com.intellij.openapi.vfs.VirtualFile? {
        if (e == null) return null
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { return it }
        e.getData(CommonDataKeys.PSI_FILE)?.virtualFile?.let { return it }
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        return runCatching {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
        }.getOrNull()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project
        val file = resolveFile(e)
        // 非本插件 exec 文件：逐字委托原生组（DataGrip 原生行为不受影响）
        if (project == null || !takeoverAppliesTo(file?.path)) {
            return delegateChildren(e)
        }
        val sources = dataSourcesFor(project, file)
        // 每次点开都记一条：用户手动动作、频率极低；一次性日志在真机会漏掉「这次点开」的读数。
        LOG.info("[M36][Takeover] getChildren: sources=${sources.size}, file=${file?.path}")
        if (sources.isEmpty()) return delegateChildren(e)
        return sources.map { ds ->
            val name = runCatching { DataSourceSettings.getName(ds) }.getOrNull().orEmpty()
            val icon = runCatching { ds.javaClass.getMethod("getIcon").invoke(ds) as? Icon }.getOrNull()
            SwitchToDataSourceAction(project, name, icon)
        }.toTypedArray()
    }

    private fun delegateChildren(e: AnActionEvent?): Array<AnAction> =
        (original as? ActionGroup)?.getChildren(e) ?: AnAction.EMPTY_ARRAY

    /**
     * 数据源枚举——**两条来源取并集**（各有盲区，单用任一条都会退回原生列表）。
     *
     *  - `DbImplUtil.getAllDataSourcesForExec(psiFile)`：原生工具栏「所有」子项的同源集合，
     *    但按 exec 文件方言过滤，方言未映射/未生效时可能为空；
     *  - [DataSourceSettings.getAllDataSources]：全量在册源，但**依赖数据源已加载**——真机
     *    17:17（IDE 启动 86 秒）实测返回 0，而沙盒 18:11 同一函数返回 12。
     *
     * 先取全量、再补原生集合里缺的（按显示名去重），任一来源有数据就能出清单。
     */
    private fun dataSourcesFor(project: Project, vf: com.intellij.openapi.vfs.VirtualFile?): List<Any> {
        val all = runCatching { DataSourceSettings.getAllDataSources(project) }.getOrDefault(emptyList())
        val fromNative = runCatching {
            @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 243 编译目标仅此重载可用
            com.intellij.openapi.application.ReadAction.compute<List<Any>, Throwable> {
                val psi = vf?.let { com.intellij.psi.PsiManager.getInstance(project).findFile(it) }
                    ?: return@compute emptyList()
                val util = Class.forName("com.intellij.database.util.DbImplUtil")
                val method = util.getMethod("getAllDataSourcesForExec", com.intellij.psi.PsiFile::class.java)
                val iterable = method.invoke(null, psi) as? Iterable<*> ?: return@compute emptyList()
                iterable.filterNotNull().toList()
            }
        }.onFailure { e ->
            LOG.warn("[M36][Takeover] native source enumeration FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }.getOrDefault(emptyList())
        if (fromNative.isEmpty()) return all
        if (all.isEmpty()) return fromNative
        val seen = all.mapNotNull { runCatching { DataSourceSettings.getName(it) }.getOrNull() }.toMutableSet()
        val merged = all.toMutableList()
        for (ds in fromNative) {
            val name = runCatching { DataSourceSettings.getName(ds) }.getOrNull().orEmpty()
            if (name.isNotBlank() && seen.add(name)) merged += ds
        }
        return merged
    }

    /**
     * 展示形态（图标/文案/可用性）交给原生动作，并把结果镜像进模板（工具栏按钮读模板）。
     *
     * T-ThreadFix A：本方法现在**跑在 BGT**（见 [getActionUpdateThread]）。因此体内只做
     * `Presentation` 层面的读写：`original.update(e)` 的 PSI 读取走平台预缓存 DataContext（原生
     * 动作自己就声明 BGT），`templatePresentation` 也是普通 Presentation 对象、不是 Swing 组件。
     * **不得**在这里加 `SwingUtilities` / 文档 / `Invoker` 之类 EDT-only 调用。
     */
    override fun update(e: AnActionEvent) {
        original.update(e)
        if (updateProbed.compareAndSet(false, true)) {
            LOG.info(
                "[M36][Takeover] update: text='${e.presentation.text}', visible=${e.presentation.isVisible}, " +
                    "enabled=${e.presentation.isEnabled}, template='${original.templateText}'",
            )
        }
        if (e.presentation.text.isNullOrBlank()) {
            e.presentation.setText(original.templateText ?: "", false)
        }
        // 262 字节码实证：原生 `update` 会 setPopop/setPerformGroup(true) 等——`setPerformGroup`
        // 正是工具栏把这个组渲染成「图标+文案」按钮（而非纯图标组）的开关。本类不能覆写 final 的
        // getTemplatePresentation，于是把原生每次算好的呈现写回模板，按钮才会显示数据源名。
        runCatching {
            e.presentation.setPerformGroup(true)
            templatePresentation.setPerformGroup(true)
            templatePresentation.setText(e.presentation.text ?: "", false)
            templatePresentation.icon = e.presentation.icon
            templatePresentation.setEnabled(e.presentation.isEnabled)
            templatePresentation.setVisible(e.presentation.isVisible)
        }
    }

    /**
     * 点击落点——**命中本插件 exec 文件时自己弹层**。
     *
     * 262 实测两轮：
     *  - 委托给 `original.actionPerformed` → 弹的是原生「分离数据源 / 所有 >」（它自己覆写了
     *    该方法来弹自己的菜单，把我们的子项整个绕过）；
     *  - `super.actionPerformed`（ActionGroup 默认）→ **什么都不弹**（`ActionGroup` 自身不建
     *    弹层，弹层由工具栏的 ActionPopupMenu 负责）。
     *
     * 故这里显式用 [JBPopupFactory.createActionGroupPopup] 以本组 children 建弹层，锚到事件
     * 的组件上；非本插件文件仍原样委托。
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val file = resolveFile(e)
        if (project == null || !takeoverAppliesTo(file?.path)) {
            original.actionPerformed(e)
            return
        }
        val sources = dataSourcesFor(project, file)
        val items = sources.mapNotNull { ds ->
            val name = runCatching { DataSourceSettings.getName(ds) }.getOrNull()
            if (name.isNullOrBlank()) {
                null
            } else {
                name to runCatching { ds.javaClass.getMethod("getIcon").invoke(ds) as? Icon }.getOrNull()
            }
        }
        if (items.isEmpty()) {
            original.actionPerformed(e)
            return
        }
        LOG.info("[M36][Takeover] actionPerformed -> own popup (sources=${items.size})")
        runCatching {
            // 用 BaseListPopupStep 而不是 createActionGroupPopup：后者会把数据源名里的 `_`
            // 当助记符吃掉（真机 `mpj_demo@localhost` 显示成缺少下划线），且带搜索框、位置跟着
            // 焦点组件漂。纯列表 + showUnderneathOf 才与原生控制台那个下拉一致。
            val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<Pair<String, Icon?>>(
                MpSqlVisualizerBundle.message("execute.dialectGate.action.switchDataSource"),
                items,
            ) {
                override fun getTextFor(value: Pair<String, Icon?>): String = value.first
                override fun getIconFor(value: Pair<String, Icon?>): Icon? = value.second
                override fun onChosen(
                    selectedValue: Pair<String, Icon?>,
                    finalChoice: Boolean,
                ): com.intellij.openapi.ui.popup.PopupStep<*>? {
                    val picked = selectedValue.first
                    return doFinalStep {
                        LOG.info("[M36][Takeover] popup picked: $picked")
                        SqlPreviewService.activePreviewPanel(project)?.switchDataSourceTo(picked)
                    }
                }
            }
            val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(step)
            val comp = e.inputEvent?.component
            if (comp != null && comp.isShowing) {
                popup.showUnderneathOf(comp)
            } else {
                popup.showInBestPositionFor(e.dataContext)
            }
        }.onFailure { ex ->
            LOG.warn("[M36][Takeover] own popup FAILED: ${ex.javaClass.simpleName}: ${ex.message}")
            original.actionPerformed(e)
        }
    }

    /** 还原用：被接管的原生动作（dispose 时换回）。 */
    internal fun originalActionForRestore(): AnAction = original

    /** 子项：选中某个数据源 → 走插件换源链（只换源，不提交 SQL）。 */
    private class SwitchToDataSourceAction(
        private val project: Project,
        private val dataSourceName: String,
        icon: Icon?,
    ) : AnAction(dataSourceName, null, icon) {

        override fun actionPerformed(e: AnActionEvent) {
            val panel = SqlPreviewService.activePreviewPanel(project) ?: return
            com.intellij.openapi.diagnostic.Logger.getInstance(ChooseDataSourceTakeoverGroup::class.java)
                .info("[M36][SwitchDs] native toolbar pick: $dataSourceName")
            panel.switchDataSourceTo(dataSourceName)
        }
    }
}

/**
 * 接管件宿主：应用级轻服务（Disposable parent），随插件应用级容器 dispose 级联还原原生动作。
 *
 * 挂靠定案照 `ExecDocAutosaveHost`：动态卸载走平台 `DynamicPlugins.unloadPlugin`，容器级联
 * 释放是插件级编程式注册的惯例摘法；卸载回调里反手 dispose 自身类有 classloader 时序风险，
 * 故不订阅 `DynamicPluginListener`。
 */
@Service(Service.Level.APP)
internal class PreviewDataSourceActionHost : com.intellij.openapi.Disposable {

    companion object {
        private const val ACTION_ID = "Console.Jdbc.ChooseDataSource"

        fun getInstance(): PreviewDataSourceActionHost =
            ApplicationManager.getApplication().getService(PreviewDataSourceActionHost::class.java)

        /** 幂等安装（预览面板展示前调用；Database 插件缺席或动作不存在时静默跳过）。 */
        fun ensureInstalled() {
            runCatching { getInstance().install() }.onFailure { e ->
                com.intellij.openapi.diagnostic.Logger
                    .getInstance(PreviewDataSourceActionHost::class.java)
                    .warn("[M36][Takeover] install FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private var installed: ChooseDataSourceTakeoverGroup? = null

    @Synchronized
    fun install() {
        if (installed != null) return
        val log = com.intellij.openapi.diagnostic.Logger.getInstance(PreviewDataSourceActionHost::class.java)
        val manager = ActionManager.getInstance()
        val original = manager.getAction(ACTION_ID)
        if (original == null) {
            log.warn("[M36][Takeover] install skipped: action not found: $ACTION_ID")
            return
        }
        if (original is ChooseDataSourceTakeoverGroup) return
        val takeover = ChooseDataSourceTakeoverGroup(original)
        manager.replaceAction(ACTION_ID, takeover)
        installed = takeover
        log.info("[M36][Takeover] installed: $ACTION_ID -> original=${original.javaClass.name}")
        BarrierRegistry.register(
            BarrierRegistry.Entry(
                id = "action-takeover:$ACTION_ID",
                component = "PreviewDataSourceActionHost（ActionManager.replaceAction 接管控制台数据源下拉）",
                removal = BarrierRegistry.RemovalPath.DISPOSER_CASCADE_ON_UNLOAD,
                note = "dispose 时若在位的仍是本接管件则换回原动作（照 EditorCommentShortcuts.uninstall 的防覆盖判定）",
            ),
        )
    }

    override fun dispose() {
        val takeover = installed ?: return
        installed = null
        runCatching {
            val manager = ActionManager.getInstance()
            if (manager.getAction(ACTION_ID) === takeover) {
                manager.replaceAction(ACTION_ID, takeover.originalActionForRestore())
            }
        }
    }
}
