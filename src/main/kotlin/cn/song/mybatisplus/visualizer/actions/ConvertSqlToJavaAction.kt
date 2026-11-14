package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator
import cn.song.mybatisplus.visualizer.reverse.ReverseWrapperFormat
import cn.song.mybatisplus.visualizer.reverse.SqlReverseException
import cn.song.mybatisplus.visualizer.reverse.SqlReverseResult
import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter
import cn.song.mybatisplus.visualizer.ui.EntitySelectionDialog
import cn.song.mybatisplus.visualizer.ui.JavaCodePreviewDialog
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog
import cn.song.mybatisplus.visualizer.ui.entityCandidateSubtitle
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project

/**
 * SQL → MyBatis-Plus Java 代码 转换 Action（SQL 反向链路，P0 + T-M36.2/3 + T-M36.4 共享编排）。
 *
 * 触发方式（plugin.xml 注册）：编辑器右键菜单 "Convert SQL to MP Java Code" + 快捷键
 * `Alt+Shift+J`（Windows/Linux）/ `Cmd+Shift+J`（macOS）。
 *
 * 行为：取编辑器选择区文本 → [SqlToJavaConverter] 共享编排（T-M36.4 抽取，与工具窗口
 * 「SQL → Java」tab 同一链路）——解析（纯函数，失败弹错误对话框含行号）→ 按设置形态
 * （[PluginSettings.REVERSE_QUERY] 跳过实体查找）后台 ReadAction 查找实体 → 分发
 * （无候选回落 QueryWrapper / 唯一候选直接生成 / 多候选 [SqlSelectionDialog] 选择）→
 * [JavaCodePreviewDialog] 预览（复制/关闭）。
 *
 * T-BatchSql：多语句（顶层分号拆分）走批量路径（错误隔离 + 单次 ReadAction 批量查找 + 拼接输出）。
 *
 * 与 [ConvertToSqlAction]（Wrapper→SQL 正向）互补。
 */
class ConvertSqlToJavaAction : AnAction() {

    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            showNoSelection(project)
            return
        }
        val text = selectionModel.selectedText ?: return
        if (text.isBlank()) {
            showNoSelection(project)
            return
        }

        // T-BatchSql：多语句走批量路径（顶层分号拆分）
        val statements = SqlToJavaConverter.splitStatements(text)
        if (statements.size > 1) {
            convertBatch(project, statements)
            return
        }

        // 1. SQL 解析（纯函数，EDT 安全）；T-Perf-20：失败改 WARNING 通知（非模态、非红色）——
        // 用户误选非 SQL 文本即触发，模态框过重；失败信息（含行号）仍完整可见
        val result = try {
            SqlToJavaConverter.parse(text)
        } catch (ex: SqlReverseException) {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("cn.song.mybatisplus.visualizer.preview")
                .createNotification(
                    MpSqlVisualizerBundle.message("reverse.error.title"),
                    MpSqlVisualizerBundle.message(ex.key, ex.detail) + "（第 ${SqlToJavaConverter.errorLine(text, ex.offset)} 行）",
                    com.intellij.notification.NotificationType.WARNING,
                )
                .notify(project)
            return
        }

        // 2. 实体查找（PSI 索引访问 → 后台 ReadAction）；QUERY 形态无需实体（跳过查找）
        val format = reverseFormat()
        if (format == ReverseWrapperFormat.QUERY) {
            showResult(project, result, emptyList(), format, emptyMap())
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val payload = try {
                ReadAction.compute<Pair<List<SqlToJavaConverter.EntityCandidate>, Map<String, List<SqlToJavaConverter.EntityCandidate>>>, Throwable> {
                    val candidates = SqlToJavaConverter.findCandidates(project, result.tableName)
                    // 副表候选（JOIN 手动多选弹窗用）
                    val subCandidates = SqlToJavaConverter.findSubEntityCandidates(project, result.joins)
                    candidates to subCandidates
                }
            } catch (t: Throwable) {
                emptyList<SqlToJavaConverter.EntityCandidate>() to emptyMap()
            }
            ApplicationManager.getApplication().invokeLater {
                showResult(project, result, payload.first, format, payload.second)
            }
        }
    }

    /**
     * T-BatchSql：批量转换（编辑器 Action 入口）。
     *
     * 多语句时不弹实体选择弹窗（交互复杂度过高），统一走自动推断 + 字符串列名回落；
     * 输出代码块到 [JavaCodePreviewDialog]（复制/关闭）。
     */
    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
    private fun convertBatch(project: Project, statements: List<String>) {
        // 1. 批量解析（EDT 纯函数；错误隔离）
        val parseResults = SqlToJavaConverter.parseBatch(statements)
        // 2. 后台单次 ReadAction 实体查找（所有语句共享）
        ApplicationManager.getApplication().executeOnPooledThread {
            // T-HiddenBugPerfScan 券 E：批量路径补兜底——原裸跑 ReadAction.compute，PCE/PSI 失效等
            // 异常直接终止池任务，invokeLater 永不投递 → 多语句 Alt+Shift+J 静默无反应（对照
            // 单语句路径有 try/catch 兜底）。取消协议原样上抛（XmlSynHiGate 同款契约）；
            // 其余失败 WARN 留痕 + EDT 给用户可见反馈。
            val payload = runBatchLookupGuarded {
                ReadAction.compute<Pair<Map<Int, List<SqlToJavaConverter.EntityCandidate>>, Map<Int, Map<String, List<SqlToJavaConverter.EntityCandidate>>>>, Throwable> {
                    SqlToJavaConverter.findCandidatesBatch(project, parseResults)
                }
            }
            // 3. EDT 生成 + 拼接 + 弹窗（T-Perf-21：主路径补 disposed 守卫——后台查找期间项目可能已关闭）
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (payload == null) {
                    (batchLookupFailureSinkForTest ?: ::notifyBatchLookupFailed)(project)
                    return@invokeLater
                }
                val (mainCandidates, subCandidates) = payload
                val format = reverseFormat()
                val code = SqlToJavaConverter.generateBatchCode(
                    parseResults,
                    mainCandidates,
                    subCandidates,
                    format,
                    logicDeleteValue = reverseLogicDeleteValue(project),
                )
                JavaCodePreviewDialog(project, code).show()
            }
        }
    }

    /** 批量实体查找失败的 EDT 用户可见反馈（券 E：原失败路径零弹窗零通知零日志）。 */
    private fun notifyBatchLookupFailed(project: Project) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("cn.song.mybatisplus.visualizer.preview")
            .createNotification(
                MpSqlVisualizerBundle.message("reverse.error.title"),
                MpSqlVisualizerBundle.message("reverse.error.batchLookup"),
                NotificationType.WARNING,
            )
            .notify(project)
    }

    /** 反向转换配置形态（测试环境 settings 未注册时默认 AUTO）。 */
    private fun reverseFormat(): ReverseWrapperFormat = runCatching {
        ReverseWrapperFormat.fromPersisted(PluginSettings.getInstance().state.reverseWrapperFormat)
    }.getOrDefault(ReverseWrapperFormat.AUTO)

    /**
     * T-ReverseGroupLogicDelete：逻辑删除「未删除」值（项目级优先 → 全局 → 兜底 "0"）——
     * 生成器据此识别 MP 依据 `@TableLogic` 自动注入的 `AND <列> = <值>` 并跳过。
     * 与正向注入 `effectiveLogicDeleteValue().ifBlank { "0" }` 同口径。
     */
    private fun reverseLogicDeleteValue(project: Project): String = runCatching {
        ProjectPluginSettings.getInstance(project).effectiveLogicDeleteValue()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: SqlToJavaConverter.DEFAULT_LOGIC_DELETE_VALUE


    /** 手动选择实体类开关（测试环境 settings 未注册时默认开）。 */
    private fun manualEntitySelectionEnabled(): Boolean = runCatching {
        PluginSettings.getInstance().state.reverseManualEntitySelection
    }.getOrDefault(true)

    /**
     * 结果分发：0 候选且开关开 → 手动选择弹窗（单行主表兜底）；
     * JOIN 且开关开 → 手动选择弹窗（主表 + 副表一次多选）；
     * 否则：无候选回落 QueryWrapper；唯一候选直接生成；多候选选择后生成。
     */
    private fun showResult(
        project: Project,
        result: SqlReverseResult,
        candidates: List<SqlToJavaConverter.EntityCandidate>,
        format: ReverseWrapperFormat,
        subCandidates: Map<String, List<SqlToJavaConverter.EntityCandidate>>,
    ) {
        if (manualEntitySelectionEnabled() && (result.joins.isNotEmpty() || candidates.isEmpty())) {
            val rows = buildList {
                add(
                    EntitySelectionDialog.TableRow(
                        key = EntitySelectionDialog.MAIN_KEY,
                        label = "${MpSqlVisualizerBundle.message("dialog.selectEntity.mainTable")} ${result.tableName}",
                        candidates = candidates.map { it.psiClass },
                        tableName = result.tableName,
                    ),
                )
                result.joins.forEach { join ->
                    join.alias?.let { alias ->
                        add(
                            EntitySelectionDialog.TableRow(
                                key = alias,
                                label = "${MpSqlVisualizerBundle.message("dialog.selectEntity.joinTable")} $alias ${join.tableName}",
                                candidates = subCandidates[alias].orEmpty().map { it.psiClass },
                                tableName = join.tableName,
                            ),
                        )
                    }
                }
            }
            EntitySelectionDialog(project, rows) { selections ->
                // T-Perf-08：实体确认回调解析移后台——candidateOf 的 EntityResolver.resolve 遍历
                // 继承链全部字段 + 注解 resolve，大实体耗时随字段数线性，EDT 直解析构成确认停顿；
                // 后台 ReadAction 解析后回 EDT 展示代码预览（对齐上方 convert 的既有异步模式）。
                ApplicationManager.getApplication().executeOnPooledThread {
                    @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
                    val resolved = ReadAction.compute<Pair<SqlToJavaConverter.EntityCandidate?, Map<String, JavaCodeGenerator.EntityTarget>>, Throwable> {
                        val main = selections[EntitySelectionDialog.MAIN_KEY]?.let { SqlToJavaConverter.candidateOf(it) }
                        val subEntities = mutableMapOf<String, JavaCodeGenerator.EntityTarget>()
                        result.joins.forEach { join ->
                            val alias = join.alias ?: return@forEach
                            selections[alias]?.let { psi ->
                                toSubEntity(
                                    SqlToJavaConverter.candidateOf(psi),
                                    reverseLogicDeleteValue(project),
                                )?.let { subEntities[alias] = it }
                            }
                        }
                        main to (subEntities as Map<String, JavaCodeGenerator.EntityTarget>)
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        val (main, subEntities) = resolved
                        if (main != null) {
                            showCodeDialog(project, result, main, format, subEntities)
                        } else {
                            // 主表未指定实体 → 回落字符串列名形态
                            JavaCodePreviewDialog(project, SqlToJavaConverter.generateCode(result, null, format)).show()
                        }
                    }
                }
            }.show()
            return
        }
        when (candidates.size) {
            0 -> JavaCodePreviewDialog(
                project,
                SqlToJavaConverter.generateCode(result, null, format),
            ).show()
            1 -> showCodeDialog(project, result, candidates[0], format)
            else -> {
                // 摘要/行文本为纯数据（PSI 已在后台解析），EDT 直接构造
                val displayTexts = candidates.map { c ->
                    SqlSelectionDialog.Row.of(
                        "${c.psiClass.name}（表 ${c.tableInfo.tableName}）",
                        entityCandidateSubtitle(c.psiClass),
                    )
                }
                SqlSelectionDialog(project, candidates, displayTexts) { selected ->
                    showCodeDialog(project, result, selected, format)
                }.show()
            }
        }
    }

    /** 手动选择实体 → 生成用副实体目标（类名缺失跳过）。 */
    private fun toSubEntity(
        candidate: SqlToJavaConverter.EntityCandidate,
        logicDeleteValue: String = SqlToJavaConverter.DEFAULT_LOGIC_DELETE_VALUE,
    ): JavaCodeGenerator.EntityTarget? {
        val className = candidate.psiClass.name ?: return null
        return JavaCodeGenerator.EntityTarget(
            className = className,
            columnToField = candidate.tableInfo.columns.associate { it.columnName to it.fieldName },
            logicDeleteColumn = candidate.tableInfo.logicDeleteColumn,
            logicDeleteValue = logicDeleteValue,
        )
    }

    /** 生成并展示代码（形态由配置分发，T-M36.3）。 */
    private fun showCodeDialog(
        project: Project,
        result: SqlReverseResult,
        candidate: SqlToJavaConverter.EntityCandidate,
        format: ReverseWrapperFormat,
        subEntities: Map<String, JavaCodeGenerator.EntityTarget> = emptyMap(),
    ) {
        val code = SqlToJavaConverter.generateCode(
            result, candidate, format,
            subEntities = subEntities,
            logicDeleteValue = reverseLogicDeleteValue(project),
        )
        JavaCodePreviewDialog(project, code).show()
    }

    private fun showNoSelection(project: Project) {
        // T-UiNative T-UN-02：未选中文本提示改轻通知（原模态弹窗——纯告知无需打断）；
        // update() 已拦可见性，本路径为竞态兜底。测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）。
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            Notification(
                NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("plugin.name"),
                MpSqlVisualizerBundle.message("reverse.error.noSelection"),
                NotificationType.INFORMATION,
            ).notify(project)
        }
    }

    // 仅访问编辑器选择区（UI 状态，非 PSI）→ EDT 更新线程安全且无慢操作风险
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selection = editor?.selectionModel
        e.presentation.isEnabledAndVisible =
            selection != null && selection.hasSelection() && !selection.selectedText.isNullOrBlank()
    }

    companion object {
        /** 插件动作 ID（plugin.xml 中注册）。 */
        const val ACTION_ID = "cn.song.mybatisplus.visualizer.ConvertSqlToJava"

        /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"
    }
}

/**
 * T-HiddenBugPerfScan 券 E：批量实体查找守卫——取消协议（PCE/Cancellation）原样上抛，
 * 其余 Throwable WARN 留痕并返回 null（调用方在 EDT 给用户可见反馈，不再静默无反应）。
 * 顶层函数便于纯 JUnit 直测守卫语义。
 */
internal fun <T> runBatchLookupGuarded(lookup: () -> T): T? = try {
    lookup()
} catch (t: Throwable) {
    if (t is com.intellij.openapi.progress.ProcessCanceledException || t is java.util.concurrent.CancellationException) throw t
    if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) throw t // T-FatalPassthrough T-FP-03 收口
    com.intellij.openapi.diagnostic.Logger.getInstance(ConvertSqlToJavaAction::class.java)
        .warn("convertBatch: 实体查找失败", t)
    null
}

/** 测试缝：批量实体查找失败的 EDT 反馈（断言收到反馈而非静默；headless 不弹真通知）。 */
internal var batchLookupFailureSinkForTest: ((Project) -> Unit)? = null
