package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.switchDataSourceTo
import cn.song.mybatisplus.visualizer.update.BarrierRegistry
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
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

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ChooseDataSourceTakeoverGroup::class.java)

    /** 一次性取证位：真机只留一条，避免刷屏。 */
    private val childrenProbed = java.util.concurrent.atomic.AtomicBoolean()
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
        if (childrenProbed.compareAndSet(false, true)) {
            LOG.info(
                "[M36][Takeover] getChildren: project=${project != null}, file=${file?.path}, " +
                    "keys(vf=${e?.getData(CommonDataKeys.VIRTUAL_FILE) != null}, " +
                    "psi=${e?.getData(CommonDataKeys.PSI_FILE) != null}, " +
                    "editor=${e?.getData(CommonDataKeys.EDITOR) != null})",
            )
        }
        // 非本插件 exec 文件：逐字委托原生组（DataGrip 原生行为不受影响）
        if (project == null || !takeoverAppliesTo(file?.path)) {
            return delegateChildren(e)
        }
        val sources = dataSourcesFor(project, file)
        if (childrenProbed.compareAndSet(false, true)) {
            LOG.info("[M36][Takeover] sources=${sources.size}")
        }
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

    /** 展示形态（图标/文案/可用性）交给原生动作；文案为空时兜底回填，避免只剩一个图标。 */
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
    }

    override fun actionPerformed(e: AnActionEvent) {
        original.actionPerformed(e)
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
