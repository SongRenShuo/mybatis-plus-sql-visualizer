package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.database.DbTableSpecAdapter
import cn.song.mybatisplus.visualizer.generator.CrudStatement
import cn.song.mybatisplus.visualizer.generator.CrudSqlGenerator
import com.intellij.database.psi.DbTable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project

/**
 * Database 工具窗口表节点右键生成 CRUD SQL（T-CrudSql-03）。
 *
 * 注册：database-optional.xml `<actions>` 段 → `DatabaseViewPopupMenu`（anchor=last；该组为
 * DatabaseTools 内部组，2024.3.7.1 plugin.xml:713 实证对第三方开放，无专门视图 action EP）。
 * 仅在 com.intellij.database 插件在场时加载——本类与 DbTableSpecAdapter 的 database 类引用
 * 全部直引编译期类型，无反射入口。
 *
 * update 判定：选中 PSI_ELEMENT_ARRAY 含 [DbTable]（Database 视图树选中项以 PSI 暴露，
 * DbElement extends PsiFileSystemItem）。多选表支持——后台 ReadAction 逐表适配自省元数据，
 * 生成后 EDT 上 [CrudSqlPreviewFlow.showCandidates] 合并展示（标签带表名前缀）。
 */
class GenerateCrudSqlFromDbTableAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val hasTable = e.project != null && selectedTables(e).isNotEmpty()
        e.presentation.isEnabledAndVisible = hasTable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val tables = selectedTables(e)
        if (tables.isEmpty()) return
        @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
        val payload = actionPayloadGuarded(emptyList<Pair<String, List<CrudStatement>>>()) {
            ReadAction.compute<List<Pair<String, List<CrudStatement>>>, Throwable> {
                tables.mapNotNull { table ->
                    val spec = DbTableSpecAdapter.toSpec(table)
                    val statements = CrudSqlGenerator.generate(spec)
                    if (statements.isEmpty()) null else spec.tableName to statements
                }
            }
        }
        ApplicationManager.getApplication().invokeLater {
            if (payload.isEmpty()) {
                CrudSqlPreviewFlow.showNoneFound(project)
            } else {
                CrudSqlPreviewFlow.showCandidates(project, payload)
            }
        }
    }

    private fun selectedTables(e: AnActionEvent): List<DbTable> =
        e.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY)?.filterIsInstance<DbTable>().orEmpty()
}
