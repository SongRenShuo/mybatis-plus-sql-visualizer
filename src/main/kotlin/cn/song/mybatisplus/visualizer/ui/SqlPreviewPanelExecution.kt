package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.execute.ConsoleEmbedMode
import cn.song.mybatisplus.visualizer.execute.DocWriteContext
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier
import cn.song.mybatisplus.visualizer.execute.SqlExecutor
import cn.song.mybatisplus.visualizer.execute.installOutputHistoryFillLayout
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ExplainGate
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor
import cn.song.mybatisplus.visualizer.util.EditorCommentShortcuts
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.NOTIFICATION_GROUP_ID
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.REHOST_MIN_INTERVAL_MS
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.REHOST_RETRY_DELAY_MS
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.Companion.DOLLAR_TOKEN

// T-M53-II（2026-10-10）：以下为 SqlPreviewPanel 的「预览执行链、EXPLAIN 双通道与控制台嵌入」内聚成员，
// 以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。

internal fun SqlPreviewPanel.showRollbackSql() {
    val sql = previewSqlForReadonly()?.let { SqlCommentStripper.strip(it) }?.takeIf { it.isNotBlank() } ?: return
    val kind = SqlTypeClassifier.getFirstKeyword(sql) ?: "SQL"
    // T-ST-03 核实：本路径是【回滚SQL】按钮的独立动作（工具栏 :687），**不在执行确认流内**——
    // 此处 generate 是该次点击的唯一一次解析（确认流的解析点在 executeSql/executeAdhocSql 的
    // ExecuteConfirmMessages.build 处），故无「确认阶段产物」可显式传入。
    val result = cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.generate(sql)
    val entry = project?.getService(cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore::class.java)
        ?.findBySql(sql)
    if (result.rating == cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.Rating.UNABLE &&
        result.snapshotSelectSql == null
    ) {
        val proj = project ?: return
        val reasons = result.warnings.joinToString("\n") { warning ->
            if (warning.messageArgs.isEmpty()) MpSqlVisualizerBundle.message(warning.messageKey)
            else MpSqlVisualizerBundle.message(warning.messageKey, *warning.messageArgs.toTypedArray())
        }
        Messages.showInfoMessage(
            proj,
            reasons,
            MpSqlVisualizerBundle.message("rollback.dialog.title"),
        )
        return
    }
    RollbackSqlDialog(project, this, result, kind, entry).show()
}

/**
 * O-91（缩范围首版）：当前预览的 CRUD SQL + 回滚链产物串成可移植脚本导出。
 *
 * 语句本体不改写（生成侧方言降级链已保证「标准语法优先」），导出只串联＋标注：
 * 每条语句前 `-- dialect: X | kind: CRUD/ROLLBACK | upsert: 形态或 no` 注释头；
 * 格式走 R4 既有三格式通道（[cn.song.mybatisplus.visualizer.log.SqlExportService.formatPortable]）。
 * 回滚链诚实拒绝（UPDATE/DELETE 无 before-image、UPSERT 三形态等）时导出面不造回滚行
 * （与 [showRollbackSql] 的 UNABLE 弹说明同口径，不降级造半成品）。
 * 写盘在后台线程（对齐 SqlLogPanel R4 导出流程）。
 */

    /**
     * R36（V46 定案）：回放脚本导出——当前预览（或经历史 recall 回落的）SQL（参数已填回）导出为
     * **可直接执行的单个 `.sql`**，方言适配取当前方言（语句本体已是生成链渲染产物，不改写），
     * 事务包裹可选（勾选则 `<方言显式事务开始语句>; … COMMIT;`；方言无该语句时选项禁用并说明）。
     *
     * 与 O-91 [exportPortableSql] 的关系：入口/写盘/通知三处**照抄同款**（仓纪律「新增 UI 交互
     * 须照抄既有同类」），产物形态不同——O-91 是 CSV/TSV/TXT 行集，本件是单个 `.sql`。
     * 脱敏同 R60 口径（`displayMaskEnabled`，投影级）：开启时脚本字面量打码并在注释头标 `masked`。
     */
    internal fun SqlPreviewPanel.exportReplayScript() {
        val sql = previewSqlForReadonly()?.takeIf { it.isNotBlank() } ?: return
        val dialect = current?.effectiveDialect ?: MySqlDialect
        val options = ReplayExportDialog(project, dialect.name, dialect.transactionBegin)
        if (!options.showAndGet()) return
        // T-MagpieGems W2：回放脚本写盘是离开 IDE 面——mode != OFF 从严打码（WARN 不给原文离开产品）
        // UI-001 修复：白名单豁免贯穿重放导出链。
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
        val mask = settings.displayMaskExportOn()
        val allowList = settings.displayMaskAllowList()
        val plan = cn.song.mybatisplus.visualizer.log.ReplayScriptExport
            .plan(sql, dialect, options.wrapTransaction) ?: return
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
            MpSqlVisualizerBundle.message("replay.export.title"),
            "SQL",
            cn.song.mybatisplus.visualizer.log.ReplayScriptExport.EXT,
        )
        val dialog = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
        val saved = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, "replay.sql") ?: return
        val target = saved.file
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                cn.song.mybatisplus.visualizer.log.SqlExportService.exportToFile(
                    target,
                    cn.song.mybatisplus.visualizer.log.ReplayScriptExport.render(
                        plan,
                        mask,
                        cn.song.mybatisplus.visualizer.log.SqlExportService.formatTime(System.currentTimeMillis()),
                        allowList,
                    ),
                )
            }.onSuccess {
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("log.history.export.done", plan.statements.size, target.absolutePath),
                        com.intellij.notification.NotificationType.INFORMATION,
                    ).notify(project)
            }.onFailure {
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                        MpSqlVisualizerBundle.message("log.history.export.failed", it.message ?: ""),
                        com.intellij.notification.NotificationType.ERROR,
                    ).notify(project)
            }
        }
    }

    /** T-PreviewSideQuery P1 测试缝：headless 无原生控制台绑定；生产为 null 走真链。 */
    internal var dmlImpactTargetForTest: (() -> Any?)? = null

    /** T-PreviewSideQuery P1 测试缝：数据源选择器绑定调用（只绑定不执行）。 */
    internal var dmlImpactPromptForTest: ((Project, String, (String) -> Unit) -> Unit)? = null

    /** T-PreviewSideQuery P1 测试缝：绑定等待（生产走 awaitDataSourceBoundThen）。 */
    internal var dmlImpactAwaitBindForTest: ((Project, Int, () -> Unit, () -> Unit) -> Unit)? = null

    /** T-PreviewSideQuery P2 测试缝：headless 无原生控制台绑定；生产为 null 走真链。 */
    internal var explainTargetForTest: (() -> Any?)? = null

    /** T-PreviewSideQuery P2 测试缝：结构化通道的绑定数据源。 */
    internal var explainBoundDsForTest: (() -> Any?)? = null

    /** T-PreviewSideQuery P2 测试缝：headless 下 Database 插件可用性。 */
    internal var explainDatabaseAvailableForTest: (() -> Boolean)? = null

    /**
     * R42：DML 影响面预演。
     *
     * 把当前 UPDATE/DELETE 的 WHERE 谓词改写为等价只读 `COUNT(*)` 试算语句（改写边界与
     * 成本防护见 [cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator]），经**只读旁路
     * 查询**（[PreviewSideQuery]）对当前绑定数据源执行——不进入共享控制台、不替换预览编辑器。
     * 不适用形态（JOIN/多表/无 WHERE/多语句）走轻通知如实说明原因，不执行。
     */
    internal fun SqlPreviewPanel.estimateDmlImpact(allowDataSourcePrompt: Boolean = true) {
        val proj = project ?: return
        val sql = previewSqlForReadonly() ?: return
        val dialect = current?.effectiveDialect ?: MySqlDialect
        when (val plan = cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator.plan(sql, dialect)) {
            is cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator.Plan.NotApplicable -> {
                notifyDmlImpact(
                    proj,
                    MpSqlVisualizerBundle.message("dml.impact.notApplicable.title"),
                    MpSqlVisualizerBundle.message(plan.reasonKey),
                )
                // 覆盖上一次的内联结果，避免 SQL 已不可试算时仍显示旧行数。
                showDmlImpactResult(MpSqlVisualizerBundle.message(plan.reasonKey), true)
            }

            is cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator.Plan.Applicable -> {
                val probe = plan.probe
                val dialectGateRequest = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request(
                    renderMode = executionDialectRenderMode,
                    renderedDialectName = dialect.name,
                    intent = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Intent.QUERY,
                    // COUNT 试算按当前方言生成，目标不一致时 fail-closed；不把另一语法族的 COUNT 送进目标库。
                    canLosslesslyRerender = false,
                    userTextUntouched = true,
                )
                val capLine = if (probe.rowCap != null) {
                    MpSqlVisualizerBundle.message("dml.impact.probe.capped", probe.rowCap)
                } else {
                    MpSqlVisualizerBundle.message("dml.impact.probe.uncapped")
                }
                val probeBody = MpSqlVisualizerBundle.message(
                    "dml.impact.probe.body",
                    probe.probeSql,
                    probe.targetTable,
                    capLine,
                )
                // T-DmlImpactProminence（用户真机反馈）：不再发「试算语句/目标表」预演通知——
                // 气泡讲的是探针而非结果，与内联/横幅结果并存时既冗余又易被误读。
                // 探针详情保留在结果横幅的 tooltip（probeBody）里，需要时悬停可见。
                val target = if (dmlImpactTargetForTest != null) dmlImpactTargetForTest!!.invoke() else runCatching {
                    cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSource()
                }.getOrNull()
                if (target == null) {
                    if (allowDataSourcePrompt) {
                        promptDataSourceThenRetryImpact(proj, sql)
                    } else {
                        showDmlImpactResult(MpSqlVisualizerBundle.message("dml.impact.noDataSource"), true)
                    }
                    return
                }
                showDmlImpactResult(MpSqlVisualizerBundle.message("dml.impact.running"), false)
                PreviewSideQuery.run(
                    proj, target, probe.probeSql, dialectGateRequest, scalar = true,
                ) { result ->
                    when (result) {
                        is PreviewSideQuery.Result.Scalar -> {
                            val cap = probe.rowCap
                            val text = if (cap != null) {
                                MpSqlVisualizerBundle.message("dml.impact.inline.capped", minOf(result.value, cap))
                            } else {
                                MpSqlVisualizerBundle.message("dml.impact.inline", result.value)
                            }
                            showDmlImpactResult(text, false, probeBody)
                        }

                        is PreviewSideQuery.Result.Blocked -> showDmlImpactResult(result.message, true)

                        is PreviewSideQuery.Result.Failed -> showDmlImpactResult(
                            MpSqlVisualizerBundle.message("dml.impact.inline.error", result.message),
                            true,
                        )

                        is PreviewSideQuery.Result.Table -> showDmlImpactResult(
                            MpSqlVisualizerBundle.message("dml.impact.inline.error", "unexpected tabular result"),
                            true,
                        )
                    }
                }
            }
        }
    }

    /**
     * T-PreviewSideQuery P1：无绑定数据源时复用既有数据源选择器（只绑定不执行），
     * 绑定落地后重试一次预估；取消/超时只就地提示，不再二次弹选择器。
     */
    internal fun SqlPreviewPanel.promptDataSourceThenRetryImpact(proj: Project, previewSql: String) {
        showDmlImpactResult(MpSqlVisualizerBundle.message("dml.impact.binding"), false)
        val generation = explainStructuredGeneration
        val errorShown = java.util.concurrent.atomic.AtomicBoolean(false)
        if (cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.shouldPromptForDataSource(
                cn.song.mybatisplus.visualizer.execute.DataSourceSelector.isPrepareInFlight(),
            )
        ) {
            // executeAfterPrepare=false：只准备/绑定，不提交任何语句（DML/DDL 零自动执行）。
            val prompt = dmlImpactPromptForTest ?: { project: Project, sql: String, onError: (String) -> Unit ->
                cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prepareConsole(
                    project, sql, onError = onError,
                )
            }
            prompt(proj, previewSql) { message ->
                errorShown.set(true)
                showDmlImpactResult(message, true)
            }
        }
        val await = dmlImpactAwaitBindForTest ?: { project: Project, gen: Int, onBound: () -> Unit, onGiveUp: () -> Unit ->
            awaitDataSourceBoundThen(project, gen, onBound, onGiveUp)
        }
        await(
            proj,
            generation,
            { estimateDmlImpact(allowDataSourcePrompt = false) },
            {
                if (!errorShown.get()) {
                    showDmlImpactResult(MpSqlVisualizerBundle.message("dml.impact.noDataSource"), true)
                }
            },
        )
    }

    /** R42：影响面预演的轻通知（非模态；单测模式不弹）。 */
    private fun SqlPreviewPanel.notifyDmlImpact(proj: Project, title: String, body: String) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            Notification(NOTIFICATION_GROUP_ID, title, body, NotificationType.INFORMATION).notify(proj)
        }
    }

    /** R91: only reads current editor/model and previously acquired history; never acquires a plan/schema. */
    internal fun SqlPreviewPanel.copyAsAiContext(includeIdentifiers: Boolean = false) {
        val sql = previewSqlForReadonly() ?: return
        val preview = current
        val dialect = preview?.effectiveDialect
        val target = aiContextTarget?.takeIf {
            it.preview === preview && runCatching(it.stillCurrent).getOrDefault(false)
        }
        val wrapped = dialect?.let { ExplainGate.planExplainSubmit(it, sql) as? ExplainGate.SubmitPlan.Submit }
        val format = dialect?.let { cn.song.mybatisplus.visualizer.sql.ExplainSupport.formatFor(it.name) }
        val key = if (target != null && dialect != null && wrapped != null && format != null) {
            cn.song.mybatisplus.visualizer.history.ExplainPlanKey.create(
                cn.song.mybatisplus.visualizer.history.ExplainPlanKey.projectIdentity(project?.basePath),
                target.key.dataSourceUuid, dialect.name, format, wrapped.statement,
            ).takeIf { it == target.key }
        } else null
        val plans = key?.let {
            val history = cn.song.mybatisplus.visualizer.history.SqlHistoryManager.getInstance()
            history.matchingExplainPlans(it) + listOfNotNull(aiContextPlan?.takeIf { acquired ->
                acquired.first == history.explainPlanGeneration() && acquired.second.key() == it
            }?.second)
        }.orEmpty()
        // A manually edited SQL cannot borrow schema facts from the previous preview.
        val schema = preview?.takeIf { sql == it.sql || sql == lastRenderedSql }?.let {
            it.tableName?.let { table -> cn.song.mybatisplus.visualizer.util.AiContextExport.StaticSchema(table, it.columns) }
        }
        val result = cn.song.mybatisplus.visualizer.util.AiContextExport.buildV2(
            sql, dialect?.name, key, plans, schema, includeIdentifiers,
        )
        cn.song.mybatisplus.visualizer.util.UiTextClipboard.copy(result.block)
        val toastKey = if (includeIdentifiers) "ai.context.v2.copied.identifiers" else "ai.context.v2.copied"
        showDiffToast(MpSqlVisualizerBundle.message(toastKey, result.maskedCount))
    }

internal fun SqlPreviewPanel.exportPortableSql() {
    val sql = previewSqlForReadonly()?.let { SqlCommentStripper.strip(it) }?.takeIf { it.isNotBlank() } ?: return
    val dialect = current?.effectiveDialect ?: MySqlDialect
    val rows = cn.song.mybatisplus.visualizer.log.PortableSqlExport
        .collectRows(sql, dialect, current?.batchModel)
    if (rows.isEmpty()) return
    val format = choosePortableExportFormat() ?: return
    val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
        MpSqlVisualizerBundle.message("panel.portableExport"),
        "SQL (${format.ext.uppercase()})",
        format.ext,
    )
    val dialog = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
        .createSaveFileDialog(descriptor, project)
    val saved = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, "sql-portable.${format.ext}")
        ?: return
    val target = saved.file
    ApplicationManager.getApplication().executeOnPooledThread {
        runCatching {
            cn.song.mybatisplus.visualizer.log.SqlExportService
                // R60（T-LaneB D96）→ T-MagpieGems W2：可移植导出同脱敏口径；离开 IDE 面从严
                // （mode != OFF 一律按 MASK 打码）
                    .exportToFile(
                        target,
                        cn.song.mybatisplus.visualizer.log.SqlExportService.formatPortable(
                            rows,
                            format,
                            cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().displayMaskExportOn(),
                            cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().displayMaskAllowList(),
                        ),
                    )
        }.onSuccess {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    MpSqlVisualizerBundle.message("log.history.export.done", rows.size, target.absolutePath),
                    com.intellij.notification.NotificationType.INFORMATION,
                ).notify(project)
        }.onFailure {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    MpSqlVisualizerBundle.message("log.history.export.failed", it.message ?: ""),
                    com.intellij.notification.NotificationType.ERROR,
                ).notify(project)
        }
    }
}

/** O-91：可移植导出格式三选（取消返回 null；形态对齐 SqlLogPanel R4 同款对话框）。 */

internal fun SqlPreviewPanel.choosePortableExportFormat(): cn.song.mybatisplus.visualizer.log.SqlExportFormat? {
    val names = arrayOf("CSV", "TSV", "TXT")
    val idx = com.intellij.openapi.ui.Messages.showChooseDialog(
        project,
        MpSqlVisualizerBundle.message("log.history.export.choose"),
        MpSqlVisualizerBundle.message("panel.portableExport"),
        com.intellij.openapi.ui.Messages.getQuestionIcon(),
        names,
        names[0],
    )
    if (idx < 0) return null
    return when (idx) {
        0 -> cn.song.mybatisplus.visualizer.log.SqlExportFormat.CSV
        1 -> cn.song.mybatisplus.visualizer.log.SqlExportFormat.TSV
        else -> cn.song.mybatisplus.visualizer.log.SqlExportFormat.TXT
    }
}

/**
 * T-RollbackSql F4：临时 SQL 执行（回滚弹窗专用）——复用 [executeSql] 的既有管线
 * （分类 → DML 二次确认 → isPrepared 快路径/全链），T-RefreshDbSteal F4 所有权卫队
 * 对这条路径天然生效；执行完毕结果反馈走既有 [resultPanel]。
 *
 * 刻意不走 [cn.song.mybatisplus.visualizer.execute.SqlExecutor.execute] 从弹窗直调全链：
 * 全链建临时 .sql 并 attachConsole，可能把正嵌在预览面板里的共享控制台偷走。
 *
 * @param dmlConfirmed    DML 二次确认豁免（回滚弹窗「执行回滚」已过用户显式确认链）
 * @param rollbackOfEntry 本次执行本身是回滚语句时指向被回滚的原快照条目（F6 留痕链）
 */

internal fun SqlPreviewPanel.executeAdhocSql(
    sql: String,
    dmlConfirmed: Boolean = false,
    rollbackOfEntry: cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.SnapshotEntry? = null,
    onSubmission: ((cn.song.mybatisplus.visualizer.execute.ExecutionSubmission) -> Unit)? = null,
) {
    val completed = java.util.concurrent.atomic.AtomicBoolean(false)
    val receipt: (cn.song.mybatisplus.visualizer.execute.ExecutionSubmission) -> Unit = { outcome ->
        if (completed.compareAndSet(false, true)) onSubmission?.invoke(outcome)
    }
    val proj = project
    if (proj == null || sql.isBlank()) {
        receipt(cn.song.mybatisplus.visualizer.execute.ExecutionSubmission.BLOCKED)
        return
    }
    val isQuery = SqlTypeClassifier.isQuery(sql)
    val dialectGateRequest = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.rawSqlRequest(sql)
    // O-64/T-RollbackIdentityParse（P19/D2=A）：identity 包装产物整批直跑。识别特征=
    // 含 `SET IDENTITY_INSERT … ON|OFF` 语句（本插件自产回滚批次的封闭形态）；普通多语句
    // 产物保持现态（Statements 选择层默认行为不变），避免行为突变。生产入口仅本函数
    // （回滚弹窗「执行回滚/执行快照查询」），executeSql 的用户编辑器执行链不接旗标。
    val wholeScriptExec = cn.song.mybatisplus.visualizer.sql.IdentityInsertStatements.containsStatement(sql)
    // R46：执行前 Schema 校验（只读已预热元数据缓存——未绑定数据源/未预热自动跳过不阻塞）
    val schemaIssues = when (
        val outcome = cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.check(proj, sql)
    ) {
        is cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.Outcome.Checked -> outcome.issues
        else -> emptyList()
    }
    if (!isQuery && !dmlConfirmed) {
        val keyword = SqlTypeClassifier.getFirstKeyword(sql) ?: "SQL"
        val confirm = Messages.showYesNoDialog(
            proj,
            cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages.build(
                sql,
                keyword,
                current?.effectiveDialect,
                cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.renderLines(schemaIssues),
            ),
            MpSqlVisualizerBundle.message("execute.confirm.dml"),
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) {
            receipt(cn.song.mybatisplus.visualizer.execute.ExecutionSubmission.CANCELLED)
            return
        }
    } else {
        // 查询链无二次确认弹窗——未识别对象走轻通知（非模态、不阻断；与 R52 确认链互补）
        notifySchemaIssues(proj, schemaIssues)
    }
    // P2-F5/F6：经执行按钮执行的 UPDATE/DELETE 执行前登记快照条目（回滚语句登记留痕链）
    registerRollbackSnapshotIfNeeded(sql, rollbackOfEntry)

    resultPanel.showLoading()
    if (SqlExecutor.isPrepared()) {
        if (!consoleAttachedToMe()) {
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                .warn("[TRollbackSql] executeAdhocSql: prepared console detached → recover before execute")
            recoverConsole(sql)
            if (SqlExecutor.isPrepared() && !consoleAttachedToMe()) {
                SqlExecutor.execute(
                    proj, sql, null, isQuery,
                    onConsole = { consoleComponent -> embedConsole(consoleComponent, embedMode = ConsoleEmbedMode.EXECUTE) },
                    onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
                    onError = { errorMsg -> resultPanel.showError(errorMsg) },
                    onErrorRich = { info -> showErrorRichWithLocate(info) },
                    wholeScriptExec = wholeScriptExec,
                    dialectGateRequest = dialectGateRequest,
                    onSubmission = receipt,
                )
                return
            }
        }
        // 快路径同步执行 SQL 进控制台文档（executePrepared 执行的就是控制台文档；
        // 回滚执行后下一次 renderSql 会按预览 SQL 同步回来）
        nativeEditorDoc?.let { doc ->
            if (doc.text != sql) {
                WriteCommandAction.runWriteCommandAction(proj) { doc.setText(sql) }
            }
        }
        val executed = SqlExecutor.executePrepared(
            proj,
            onResultsReady = { fullConsole -> embedConsole(fullConsole, embedMode = ConsoleEmbedMode.EXECUTE) },
            onError = { errorMsg -> resultPanel.showError(errorMsg) },
            onErrorRich = { info -> showErrorRichWithLocate(info) },
            wholeScriptExec = wholeScriptExec,
            dialectGateRequest = dialectGateRequest,
            onSubmission = receipt,
        )
        if (executed) return
    }
    SqlExecutor.execute(
        proj, sql, null, isQuery,
        onConsole = { consoleComponent -> embedConsole(consoleComponent, embedMode = ConsoleEmbedMode.EXECUTE) },
        onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
        onError = { errorMsg -> resultPanel.showError(errorMsg) },
        onErrorRich = { info -> showErrorRichWithLocate(info) },
        wholeScriptExec = wholeScriptExec,
        dialectGateRequest = dialectGateRequest,
        onSubmission = receipt,
    )
}

/**
 * T-RollbackSql P2-F5/F6：UPDATE/DELETE 执行前登记快照条目（内存 store，LogSqlEntry 不动字段）。
 * 回滚语句（rollbackOfEntry 非 null）也登记——parentExecutionId 指向被回滚的原执行，
 * 形成 dbx `rollback_of` 语义的留痕链；仅登记可解析到目标表的语句（解析失败登记无意义）。
 *
 * **T-PS5-02 / T-ST-03 解析复用前提（实测核实，勿默认撤销）**：下面 [RollbackSqlGenerator.generate]
 * 走 `UnboundedWriteDetector.parseStatementsShared` 的 AST 单槽，与确认阶段
 * （[executeSql]/[executeAdhocSql] 的 `ExecuteConfirmMessages.build` → `UnboundedWriteDetector.detect`）
 * 共用同一次解析——命中判据是**同文本内容全等**，故此处**依赖两条前提**：
 * ①确认阶段确实以同一文本走了一遍 `detect`（UPDATE/DELETE 必经 DML 二次确认，成立；
 * **豁免参数路径** `executeAdhocSql(dmlConfirmed = true)` 不走确认弹窗、退化为本链自解析一次——
 * 但该参数**现行生产无调用点**（`RollbackSqlDialog.executeRollback` 未传它，回滚弹窗同样走确认弹窗），
 * 仅由测试覆盖，故此退化目前只存在于测试路径）；
 * ②[SqlCommentStripper.strip] 幂等——此处传入的是 `strip(sql)`，而 `detect` 内部键是 `strip(sql)`、
 * `generate` 内部还会再剥一次（键成 `strip(strip(sql))`），幂等成立才落回同一槽。
 * 失效后果分档：**前提①失效只是多一次解析**（槽未命中即重解析同一文本，结果不变）；
 * **前提②失效则不同**——命中路径用单剥文本的 AST、未命中路径解析双剥文本，两条路径输入文本不同、
 * 结果可能随缓存命中状态而变（故由 `SqlPreviewPanelRollbackParseShapeTest` 的「独立 vs 共享逐字节一致」兜底）。
 * 前提由该测试类逐条钉住（含「槽未命中必重解析」的探针自证）。
 *
 * 面板侧若需脱离该隐式前提，出口是 `RollbackSqlGenerator.generateFromParsedStatements(cleaned, statements, pk)`；
 * **T-ST-03 独立复核订正**：理由原写「真正显式传递须改 `execute/` 包的两个共享 API」——不是唯一路线：
 * 面板侧**自取槽变体**（`parseStatementsShared(cleaned)` → `generateFromParsedStatements`）从同一个 `sql`
 * 变量算槽键，能**整条换掉前提②**（由 `strip` 的纯函数性保证，与幂等无关），且两个 API 都是 `internal`、
 * 与 ui 包同 Gradle 模块，无需改签名。**但结论仍是不实施**：工具栏路径 [showRollbackSql] 无前置 detect、
 * 生产仍按双剥文本出结果，幂等前提**换不掉**；且收益为零性能/零语义（T-PS5-02 的收益已由单槽全数拿到），
 * 属计划明标的 P4 可选件。
 */

internal fun SqlPreviewPanel.registerRollbackSnapshotIfNeeded(
    sql: String,
    rollbackOfEntry: cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.SnapshotEntry?,
) {
    val proj = project ?: return
    val keyword = SqlTypeClassifier.getFirstKeyword(sql) ?: return
    if (keyword != "UPDATE" && keyword != "DELETE") return
    val store = proj.getService(cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore::class.java)
    // O-59 T-IIR-01：把**当前生效方言**随快照登记进去——取的是 [SqlPreviewService.currentDialect]
    // （= `DialectRegistry.effective(project)`，与预览/执行链同源，含 T-DialectAutoInfer 的数据源
    // 推断，不新造解析）。此前两处登记点都不传该参数 → `SnapshotEntry.dialect` 恒 null →
    // `RollbackSqlGenerator.backfillInsert` 的 `wrapIdentity` 恒假 → 回滚侧 identity 包装在
    // **生产链上永不触发**（T-Round10 独立复核 blocker②：用户在 SQL Server 上 DELETE 后粘贴快照
    // 生成回填 INSERT 仍报 544）。取不到时保持 null（= 未知不包装，O-52 D1=A 契约不变）。
    val dialect = proj.getService(SqlPreviewService::class.java).currentDialect()
    if (rollbackOfEntry != null) {
        store.register(
            sql, rollbackOfEntry.targetTable, null,
            parentExecutionId = rollbackOfEntry.executionId,
            dialect = dialect,
        )
        return
    }
    // T-PS5-02：传入已剥注释文本——键对齐靠 strip 幂等（详见本函数 KDoc 的两条前提）
    val analyzed = cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.generate(SqlCommentStripper.strip(sql))
    val table = analyzed.targetTable ?: return
    store.register(sql, table, analyzed.snapshotSelectSql, analyzed.setColumns, dialect = dialect)
}

/** 当前编辑器 SQL 文本（原生编辑器文档优先，兜底自研编辑器；两处皆空 null）。 */

internal fun SqlPreviewPanel.currentEditorSql(): String? =
    nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
        ?: editor?.document?.text?.takeIf { it.isNotBlank() }

/**
 * T-ExplainConsoleFirst W2（2026-10-24）：**只读动作**的取词 —— 执行视图在场时读预览快照。
 *
 * 为什么需要：执行计划走内嵌原生控制台（形态 A）时，EXPLAIN 语句被写进 `mpsql-exec.sql` 的
 * document，而 [nativeEditorDoc] 指向它 —— [currentEditorSql] 于是从「预览 SQL」漂移成
 * 「EXPLAIN …」。回滚 SQL / 导出回放脚本 / 复制 AI 上下文 / 导出可移植 SQL / 预估影响行数
 * 全都读那个函数，在执行视图里点它们会拿到 EXPLAIN 语句（`1a1cf724` 正是因此把默认通道换成
 * 旁路查询的，commit message 原文「会让 currentEditorSql() 漂移到试算/EXPLAIN 语句」）。
 *
 * 判据：[lastRenderedSql] 由 [showSql] 在每次预览渲染后记录，而控制台执行链写 exec 文件
 * **不经过 [showSql]**，故它恰好保存着「最后一次预览的 SQL」。同款判据已在
 * [SqlPreviewPanelExecution.kt] 的 T-PrewarmLateRecover（`currentText == lastRenderedSql`）
 * 与非 DML 影响面估算中使用。
 *
 * 边界：执行视图不在场（含「返回预览」之后）一律返回真实编辑器文本 —— 用户手改必须被看见。
 * **执行链本身不用本函数**（[executeSql]/[explainSql] 执行的就是编辑器里的真实文本）。
 */
internal fun SqlPreviewPanel.previewSqlForReadonly(): String? {
    if (executeViewHeader != null) {
        lastRenderedSql?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return currentEditorSql()
}

/**
 * T-InjectionGate T-IG-02：`${}` 原文拼接段的执行确认闸门（[executeSql]/[explainSql] 共用）。
 *
 * 判定信号取自 [PreviewResult.hasDollarRawSegments]（解析期 `${` token 收集的渲染期事实，
 * 不是最终文本 regex——`${}` 成功替换为模拟值后文本里已无痕迹，regex 会漏掉全部成功场景）。
 * 含 raw 段且本面板未确认过 → 弹确认框（复用 DML 二次确认同款 YesNo 形态与 warning 图标，
 * 标题同 [MpSqlVisualizerBundle] 的 `execute.confirm.dml`，消息说明 raw 段当前填的是模拟值）；
 * 用户选 NO → 中止；选 YES → 记会话级记忆，本面板后续执行不再重复弹。
 *
 * 范围：只在 XML/注解预览链生效（Wrapper/JPA/日志还原链无 `${}` 概念，该字段恒 false）。
 *
 * @return true = 放行（无 raw 段 / 已确认过 / 本次确认通过）
 */

internal fun SqlPreviewPanel.confirmDollarRawSegments(proj: Project): Boolean {
    if (current?.hasDollarRawSegments != true) return true
    if (dollarRawConfirmedInSession) return true
    val confirm = Messages.showYesNoDialog(
        proj,
        MpSqlVisualizerBundle.message("execute.confirm.dollarRaw.message", DOLLAR_TOKEN),
        MpSqlVisualizerBundle.message("execute.confirm.dml"),
        Messages.getWarningIcon(),
    )
    if (confirm != Messages.YES) return false
    dollarRawConfirmedInSession = true
    return true
}

/**
 * T-PC2-04（D1=B 定案）：含「签名类型未能识别」参数的语句执行确认闸门
 * （[executeSql]/[explainSql] 共用，形态与 [confirmDollarRawSegments] 同款）。
 *
 * 判定信号取自 [XmlPreviewModel.unknownParamSources]（`paramTypes` 有条目但类别 UNKNOWN：
 * 枚举 / `boolean` / `byte[]` 等——类型读到了却映射不到类别，绑定层只能以字面 `?` 提交）。
 * **无类型信息不计入**（mapper 接口不可解析时键不在 `paramTypes`）——与 T-PT-02「无类型信息
 * 不提示」同向，也避免「接口解析不到 → 每次执行都提示」的噪音面（口径见该函数 KDoc）。
 *
 * 预览链**保持放行**（D1=B：预览与执行风险面不同），只在执行/EXPLAIN 前提示一次；用户选 NO
 * → 中止；选 YES → 记会话级记忆，本面板后续执行与 EXPLAIN 都不再重复弹（不阻断已确认流程）。
 * 范围：只在 XML 预览链生效（Wrapper/JPA/日志链无 `xmlModel`，恒放行）。
 *
 * @return true = 放行（无 UNKNOWN 参数 / 已确认过 / 本次确认通过）
 */

internal fun SqlPreviewPanel.confirmUnknownParams(proj: Project): Boolean {
    val unknown = current?.xmlModel
        ?.unknownParamSources(current?.unresolvedParams.orEmpty())
        .orEmpty()
    if (unknown.isEmpty() || unknownParamsConfirmedInSession) return true
    val confirm = Messages.showYesNoDialog(
        proj,
        MpSqlVisualizerBundle.message("execute.confirm.unknownParams.message", unknown.size),
        MpSqlVisualizerBundle.message("execute.confirm.dml"),
        Messages.getWarningIcon(),
    )
    if (confirm != Messages.YES) return false
    unknownParamsConfirmedInSession = true
    return true
}

/**
 * T-PSG-04（N-33）：`?` 位点数与参数条数对账的**非阻断**标注。
 *
 * 结论由 [backfillParamsBySource] 在回填时算出（串感知位点数 vs 参数条数），此处排在两道
 * 确认门之后、快照登记/执行之前读走并如实登记日志——不弹窗、不中止（我方既有档位是确认/标注，
 * 不是拒绝）。产物侧的同款对账标注见 `CopyExecutableSqlAction` 的头部标记。
 */

internal fun SqlPreviewPanel.warnOnPlaceholderMismatch() {
    val mismatch = placeholderReconciliation ?: return
    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        .warn("[T-PSG-04] executeSql: $mismatch")
}

/**
 * 执行 SQL（M36）：
 *  1. SQL 类型分类 → 非查询语句二次确认
 *  2. 创建临时上下文文件 → 原生数据源弹窗 → 选中后自动执行
 *  3. 结果在打开的 Database 编辑器下方原生展示
 *
 * Community（无 database 插件）由 [SqlExecutor] 提示降级。
 * M36.5.4：入口/中止日志——沙盒排查执行问题时此前无任何面板侧日志（静默 return 难定位）。
 */

internal fun SqlPreviewPanel.executionDialectGateRequest(
    intent: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Intent,
): cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request {
    val result = current
    val currentText = currentEditorSql()
    val untouched = currentText != null && currentText == lastRenderedSql
    // 只有模型/批量（CRUD）载体能按新方言从结构重新生成；XML/纯文本与分页后处理缺重建载体，
    // 宁可报 RERENDER_UNAVAILABLE，也不做字符串替换冒充无损。
    val canRerender = result != null && result.xmlModel == null && result.paginationLimitLine == null &&
        (result.previewModel != null || result.batchModel != null)
    return cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request(
        renderMode = executionDialectRenderMode,
        // T-ExplainSwitchNative W10（2026-10-24 用户实测「为什么 sql 无法执行」——红条：
        // 「当前 SQL 按 **未知** 渲染，所选数据源是 MySQL」）：
        // `effectiveDialect` 为 null 只发生在「渲染链没记方言」的载体（如 JPA/日志还原等
        // renderedDialect 未赋值的链）。T-DialectInferFix 起，showResult 会先用统一决策链
        // （运行期条目方言 → 数据源 → 项目配置/驱动依赖 → SQL 语法特征 → IDEA 方言设置）
        // 把结果补齐，null 只剩「渲染器兜底」这一种形态——仍按渲染器默认口径（MySQL）报出，
        // 避免闸门把「渲染器自己都没当成未知」的形态判成 RENDER_UNKNOWN_BLOCK 阻断执行。
        renderedDialectName = result?.effectiveDialect?.name
            ?: currentDialectDecision?.compatibilityFallbackName
            ?: cn.song.mybatisplus.visualizer.sql.MySqlDialect.name,
        intent = intent,
        canLosslesslyRerender = canRerender,
        userTextUntouched = untouched,
        moduleKey = sourceModuleKey,
    )
}

internal fun SqlPreviewPanel.onExecutionDialectGate(
    verdict: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Verdict,
) {
    when (verdict.status) {
        cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Status.MATCH,
        cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Status.TARGET_UNKNOWN_QUERY_CONFIRM,
        cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Status.RENDER_UNKNOWN_QUERY_CONFIRM -> Unit
        cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Status.AUTO_RERENDER_REQUIRED -> {
            val target = verdict.targetDialectName
            if (target != null && rerenderForExecutionTarget(target)) {
                resultPanel.showStatus(cn.song.mybatisplus.visualizer.execute.DataSourceSelector.dialectGateMessage(verdict))
            } else {
                showDialectGateError(
                    verdict.copy(status = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Status.RERENDER_UNAVAILABLE),
                )
            }
        }
        // T-DialectGatePass（2026-10-26 语义对齐，对齐 commit 8619a3af 用户定案「方言不能影响
        // 最终的执行」）：这两态在 allowDialectGate 侧本就放行（返回 true），预览入口同步去掉
        // 阻断呈现——提示照旧报出双方方言不一致，但不再用「错误+动作」红条阻断，改走
        // showStatus 信息态；执行继续与 MATCH 分支同一路径（本回调只管呈现，继续与否由
        // allowDialectGate 的返回值决定，这里 Unit 即放行）。
        // 时序坑（同 showDialectGateError 注释）：闸门在同一次调用栈还会经旧 onError 通道再投
        // 一次纯文案（DataSourceSelector.allowDialectGate 先 onDialectGate 再 onError），同步
        // showStatus 会被紧随其后的 showError 覆盖——把最终呈现放到下一拍 EDT，两条通道最终
        // 只留一条提示（若执行随后真报错，真实错误仍会覆盖这条状态，时序上真实错误晚于本拍）。
        cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Status.MANUAL_CONFLICT,
        cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Status.RERENDER_UNAVAILABLE -> {
            val message = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.dialectGateMessage(verdict)
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (previewDisposed) return@invokeLater
                resultPanel.showStatus(message)
            }
        }
        else -> resultPanel.showError(
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.dialectGateMessage(verdict),
        )
    }
}

/**
 * T-DialectGateUx：阻断态错误 + 两个就地出口。
 *
 *  - 「切换数据源」→ [executeSql] 带 forceChoose 重开选择器（跳过已准备会话）；
 *  - 「打开 SQL 方言设置」→ 平台设置页（EP id = "SQL Dialects"，按 id 取避免整棵设置树实例化）。
 *
 * 文案只解释原因，动作直接通向修复点；用户不必自己翻设置或文档。
 *
 * T-DialectGatePass（2026-10-26）：MANUAL_CONFLICT / RERENDER_UNAVAILABLE 已改为放行
 * （见 [onExecutionDialectGate]），不再走本函数——**保留不删**（后续可能复用），当前唯一
 * 生产调用方是 AUTO_RERENDER_REQUIRED 分支的「重渲染失败回落 RERENDER_UNAVAILABLE」路径
 * （该态仍是阻断语义，红条+就地出口照旧）。
 */
internal fun SqlPreviewPanel.showDialectGateError(
    verdict: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Verdict,
) {
    val proj = project
    val message = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.dialectGateMessage(verdict)
    if (proj == null) {
        resultPanel.showError(message)
        return
    }
    val settingsAvailable = runCatching { findSqlDialectConfigurable(proj) != null }.getOrDefault(false)
    // T-DialectGateUx：闸门在**同一次调用栈**里还会经旧 onError 通道再投一次纯文案
    // （DataSourceSelector.allowDialectGate 先 onDialectGate 再 onError），后者会把刚挂上的
    // 动作清掉（沙盒实证：红条只有复制、没有两个入口）。把带动作的最终呈现放到下一拍 EDT，
    // 保证它是这次阻断的最终状态。
    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
        if (previewDisposed) return@invokeLater
        resultPanel.showErrorWithActions(
            message,
            fixLabel = MpSqlVisualizerBundle.message("execute.dialectGate.action.switchDataSource"),
            fixTooltip = MpSqlVisualizerBundle.message("execute.dialectGate.action.switchDataSource.tip"),
            // T-ExplainSwitchNative W12（2026-10-24 用户实测三图定案）：换源一律走**平台原生
            // 工具栏的 Data Sources 菜单**（图三：Detach Data Source + 全部源）——插件侧自建
            // 入口做不到与原生一致（平台 forced 关联无公开清除 API，自建列表第二次起会被短路成
            // 单源）。此处动作改为把用户送到能换源的地方：打开 Database 工具窗口。
            fix = { cn.song.mybatisplus.visualizer.execute.DataSourceSelector.openDatabaseToolWindow(proj) },
            settingsLabel = if (settingsAvailable) {
                MpSqlVisualizerBundle.message("execute.dialectGate.action.openSettings")
            } else null,
            settingsTooltip = MpSqlVisualizerBundle.message("execute.dialectGate.action.openSettings.tip"),
            settings = { openSqlDialectSettings(proj) },
        )
    }
}

/** 方言设置页的 Configurable EP id（Database 插件注册，见 SqlPlugin.xml）。 */
internal const val SQL_DIALECTS_SETTING_ID = "SQL Dialects"

/** T-DialectGateUx：打开「设置 → 语言与框架 → SQL 方言」页并选中该页。 */
internal fun openSqlDialectSettings(proj: Project) {
    // 平台自带的「按 id 打开并选中」入口：它在**同一个** ConfigurableGroup 实例里查找目标
    // configurable 再建对话框。先 findById 再把 wrapper 交给 showSettingsDialog(project, Configurable)
    // 会因两次 group 实例不同而选不中——沙盒实证只打开到设置根页。
    val opened = runCatching {
        com.intellij.ide.actions.ShowSettingsUtilImpl.showSettingsDialog(proj, SQL_DIALECTS_SETTING_ID, null)
    }.isSuccess
    if (!opened) {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(proj, SQL_DIALECTS_SETTING_ID)
    }
}

/**
 * 按 configurable EP **id** 取方言设置页，不实例化整棵设置树。
 *
 * `Settings.find(Class)` / `showSettingsDialog(project, Class)` 会 `ConfigurableVisitor.findByType`
 * 遍历并实例化全部 configurable（Kotlin scripting 页构造器会做文件名索引 → 真机 EDT 慢操作断言）；
 * `findById` 只包一层 wrapper，页面本身在展示时才实例化。
 */
private fun findSqlDialectConfigurable(proj: Project): com.intellij.openapi.options.Configurable? =
    runCatching {
        val group = com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
            .getConfigurableGroup(proj, true)
        com.intellij.openapi.options.ex.ConfigurableVisitor.findById(SQL_DIALECTS_SETTING_ID, listOf(group))
    }.getOrNull()

/** 模型/批量 CRUD 载体的无损目标方言重渲染；成功只改预览，不提交当前点击。 */
internal fun SqlPreviewPanel.rerenderForExecutionTarget(targetDialectName: String): Boolean {
    val old = current ?: return false
    val target = cn.song.mybatisplus.visualizer.sql.DialectRegistry.dialects
        .firstOrNull { it.name.equals(targetDialectName, ignoreCase = true) } ?: return false
    val updated = when {
        old.xmlModel != null || old.paginationLimitLine != null -> return false
        old.previewModel != null -> old.copy(
            previewModel = old.previewModel.copy(dialect = target),
            renderedDialect = target,
        )
        old.batchModel != null -> old.copy(
            batchModel = old.batchModel.copy(dialect = target),
            renderedDialect = target,
        )
        else -> return false
    }
    // R92: this is the same preview with a new dialect, not a fresh parameter session.
    // Retain ownership of existing controls; storage-generation invalidation still applies.
    if (fieldFillPreview === old) fieldFillPreview = updated
    current = updated
    // T-DialectStatusLiveRefresh：目标方言已被方言闸证实，决策同步升级——meta 行否则停留在
    // 旧的「未验证/歧义」态与实际渲染方言自相矛盾（copy() 不会带上新决策）。
    currentDialectDecision = cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision(
        cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Status.RESOLVED,
        target.name,
        cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Confidence.EXACT,
        cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Source.BOUND_DATASOURCE,
    )
    syncPreviewDialectMapping(updated)
    renderSql()
    applyDialectStatusSegment()
    val proj = project
    val sql = lastRenderedSql
    if (proj != null && !sql.isNullOrBlank()) {
        ApplicationManager.getApplication().executeOnPooledThread {
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prewarmExecTempFile(
                proj, sql, target.name,
            )
        }
    }
    return true
}

internal fun SqlPreviewPanel.executeSql(forceChoose: Boolean = false) {
    val proj = project ?: return
    // M36.4：优先取原生编辑器文档，兜底自研编辑器
    val sql = nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
        ?: editor?.document?.text?.takeIf { it.isNotBlank() }
        ?: run {
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                .warn("[M36] executeSql aborted: no SQL text (nativeDoc=${nativeEditorDoc != null}, editor=${editor != null})")
            return
        }
    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        .info("[M36] executeSql: len=${sql.length}, prepared=${SqlExecutor.isPrepared()}")

    // UI023：只读本面板已绑定目标的缓存。首次未绑定/控制台失联/库范围无法证明时跳过，
    // 不猜项目首个数据源，不在 EDT 预热；后续选择、写确认和目标闸仍走原链。
    val schemaTarget = if (consoleAttachedToMe()) {
        cn.song.mybatisplus.visualizer.execute.DataSourceSelector.schemaValidationTarget(nativeEditorDoc)
    } else null
    val schemaIssues = when (val outcome = cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.check(proj, sql, schemaTarget)) {
        is cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.Outcome.Checked -> outcome.issues
        else -> emptyList()
    }
    // 1. SQL 类型分类 → 非查询语句二次确认
    val isQuery = SqlTypeClassifier.isQuery(sql)
    if (!isQuery) {
        val keyword = SqlTypeClassifier.getFirstKeyword(sql) ?: "SQL"
        val confirm = Messages.showYesNoDialog(
            proj,
            cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages.build(
                sql,
                keyword,
                current?.effectiveDialect,
                cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.renderLines(schemaIssues),
            ),
            MpSqlVisualizerBundle.message("execute.confirm.dml"),
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return
    } else {
        notifySchemaIssues(proj, schemaIssues)
    }
    // T-InjectionGate T-IG-02：`${}` raw 拼接段确认门（DML 分类判定之后追加——预览产物里的
    // raw 段带的是模拟值，执行前须用户确认值真实性；NO 直接中止，不落快照不执行）
    if (!confirmDollarRawSegments(proj)) return
    // T-PC2-04（D1=B）：签名类型未识别参数确认门（D1=B 的「执行/EXPLAIN 前提示一次」——
    // 预览链保持放行；排在 `${}` 门之后、快照登记/执行之前，NO 直接中止）
    if (!confirmUnknownParams(proj)) return
    // T-PSG-04（N-33）：`?` 位点数与参数条数对账——与上面两道确认门同址，但**不阻断**
    // （不等时只如实登记日志；产物侧的标注见 CopyExecutableSqlAction 头部标记形态）
    warnOnPlaceholderMismatch()
    // T-RollbackSql P2-F5/F6：UPDATE/DELETE 执行前登记快照条目（回滚弹窗据此提供粘贴回填工作流）
    registerRollbackSnapshotIfNeeded(sql, null)
    val dialectGateRequest = executionDialectGateRequest(
        if (isQuery) cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Intent.QUERY
        else cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Intent.DML,
    )

    // 2. 控制台已就绪（M36.5 默认展示）→ 同步 SQL 到控制台文档后直接执行；
    //    未就绪 → 走准备+执行合一全链（准备完成后控制台内嵌右侧）
    resultPanel.showLoading()
    // T-DialectGateUx：forceChoose（错误行「切换数据源」）必须跳过已准备控制台，
    // 否则会继续复用与原目标同源的会话，闸门再次拦截。
    if (SqlExecutor.isPrepared() && !forceChoose) {
        // T-RefreshDbSteal F4：isPrepared 只证明插件级共享控制台存在，不证明它还挂在本面板——
        // 失联时 executePrepared 会把 SQL 执行到别的弹窗/Database 工具窗的控制台（症状③）。
        // 先走恢复链把控制台重嵌（prepareConsole 复用链同步 SQL + onConsole 重嵌），
        // 仍就绪才执行——恢复链自身失败时 isPrepared 已被全链重建路径覆盖，回退下方
        // selectAndExecute 全链兜底
        if (!consoleAttachedToMe()) {
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                .warn("[M36] executeSql: prepared console detached from this panel → recover before execute")
            recoverConsole(sql)
            if (SqlExecutor.isPrepared() && !consoleAttachedToMe()) {
                // 恢复未落地（异步链未回调）：放弃 executePrepared，落全链（全链会重建/复用
                // 共享控制台并经 onConsole 重嵌本面板），避免打在别人的控制台上
                SqlExecutor.execute(
                    proj, sql, null, isQuery,
                    onConsole = { consoleComponent -> embedConsole(consoleComponent, embedMode = ConsoleEmbedMode.EXECUTE) },
                    onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
                    onError = { errorMsg ->
                        resultPanel.showError(errorMsg)
                    },
                    onErrorRich = { info -> showErrorRichWithLocate(info) },
                    dialectGateRequest = dialectGateRequest,
                    onDialectGate = { verdict -> onExecutionDialectGate(verdict) },
                )
                return
            }
        }
        // 执行前同步当前 SQL（用户可能改过参数/手动编辑）
        nativeEditorDoc?.let { doc ->
            if (doc.text != sql) {
                WriteCommandAction.runWriteCommandAction(proj) { doc.setText(sql) }
            }
        }
        val executed = SqlExecutor.executePrepared(
            proj,
            // M36.5.2：执行触发成功后换入完整控制台（结果区执行后才出现）
            onResultsReady = { fullConsole -> embedConsole(fullConsole, embedMode = ConsoleEmbedMode.EXECUTE) },
            onError = { errorMsg ->
                resultPanel.showError(errorMsg)
            },
            onErrorRich = { info -> showErrorRichWithLocate(info) },
            dialectGateRequest = dialectGateRequest,
            onDialectGate = { verdict -> onExecutionDialectGate(verdict) },
        )
        if (executed) return
    }
    SqlExecutor.execute(
        proj, sql, null, isQuery,
        onConsole = { consoleComponent -> embedConsole(consoleComponent, embedMode = ConsoleEmbedMode.EXECUTE) },
        onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
        // M36.3：错误回调——执行链任何环节失败时从「执行中」状态恢复并显示错误
        onError = { errorMsg ->
            resultPanel.showError(errorMsg)
        },
        onErrorRich = { info -> showErrorRichWithLocate(info) },
        dialectGateRequest = dialectGateRequest,
        onDialectGate = { verdict -> onExecutionDialectGate(verdict) },
        forceChoose = forceChoose,
    )
}

/**
 * T-ErrorLocate T-ELU-01：执行调用点共用的富错误回调——**先解析锚点、再决定按钮显隐**。
 *
 * 诚实降级定案：驱动报错位置（Position/at line）指向**被执行 SQL 文本**的字符/行，
 * 与 mapper 原文行不一一对应（动态 SQL 拼接、参数注入后行号必然错位），v1 只承诺
 * 「语句起始行」跳转 + 按钮 tooltip 如实说明，绝不虚标精确跳转。
 * 锚点来自 [SqlPreviewService.lastPreviewSourceAnchor]（预览触发时登记）。
 */

internal fun SqlPreviewPanel.locateErrorSource(proj: com.intellij.openapi.project.Project, anchor: SqlPreviewService.SourceAnchor) {
    runCatching {
        com.intellij.openapi.fileEditor.OpenFileDescriptor(proj, anchor.file, anchor.line0, 0).navigate(true)
    }
}

/**
 * T-ErrorLocateUsability T-ELU-01（O-49）：**先解析锚点，再决定是否给按钮**。
 *
 * 缺陷（KDoc 与实现不符）：[SqlResultPanel.showErrorRich] 按 `locate != null` 决定按钮显隐，
 * 而唯一调用方此前**恒传非 null lambda**（锚点解析发生在点击时，`project ?: return` /
 * `anchor ?: return` 静默返回）→ **按钮可见，点了什么都不发生**。现把解析提到显隐之前：
 * 锚点解析不出（无项目上下文 / 本会话尚未登记锚点 / 源文件已失效）→ 传 null，按钮不出现。
 *
 * 对齐同仓已做对的先例 [cn.song.mybatisplus.visualizer.log.SqlLogPanel]（先解析、后显隐）。
 * 顺带把「文件已失效」也算作不可解析——跳不动的按钮不给，比给了点不动诚实。
 */

internal fun SqlPreviewPanel.errorLocateAction(): (() -> Unit)? {
    val proj = project ?: return null
    val anchor = runCatching { proj.getService(SqlPreviewService::class.java).lastPreviewSourceAnchor }
        .getOrNull() ?: return null
    if (!anchor.file.isValid) return null
    return { locateErrorSource(proj, anchor) }
}

/** T-ErrorLocate：执行调用点共用的富错误回调（错误呈现 + **锚点可解析时**才附定位动作）。 */

internal fun SqlPreviewPanel.showErrorRichWithLocate(info: cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo) {
    resultPanel.showErrorRich(info, errorLocateAction())
    maybeProbeDdlEffective(info)
}

/**
 * T-DdlRecover（O-15）T-DR-02：「失败不等于未生效」——执行 DDL 撞连接类断开时，数据库侧
 * 可能已隐式提交。判定链：连接类异常（SQLState 08xxx / vendor 码 / 签名兜底）∧ 可能改元数据
 * （MetadataInvalidator 既有预判复用）→ 提取 CREATE 目标表名 → 后台退避探测（500ms/1.5s/3s
 * 三次，等 DBN 自动重连窗口）→ 命中呈现警示条、未命中 tooltip 如实注记。
 * 探测/提取任一失败维持原错误呈现（诚实降级）；警示无新弹窗（弹窗克制红线）。
 */

internal fun SqlPreviewPanel.maybeProbeDdlEffective(info: cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo) {
    val proj = project ?: return
    if (!cn.song.mybatisplus.visualizer.execute.ConnectionErrorClassifier.isConnectionError(info)) return
    val sql = nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
        ?: editor?.document?.text?.takeIf { it.isNotBlank() } ?: return
    if (!cn.song.mybatisplus.visualizer.execute.MetadataInvalidator.mayChangeMetadata(sql)) return
    val target = cn.song.mybatisplus.visualizer.execute.DdlTargetExtractor.extractCreateTarget(sql) ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
        var hit = false
        for (delayMs in longArrayOf(500, 1500, 3000)) {
            Thread.sleep(delayMs)
            val meta = runCatching {
                cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.tableMeta(proj, target)
            }.getOrNull() ?: break
            if (meta.comment != null || meta.columns.isNotEmpty()) {
                hit = true
                break
            }
        }
        val probedAt = java.time.LocalTime.now().withNano(0).toString()
        ApplicationManager.getApplication().invokeLater({
            if (hit) {
                resultPanel.showDdlEffectiveWarning(target, probedAt)
            } else {
                resultPanel.noteDdlProbeMissed()
            }
        }, com.intellij.openapi.application.ModalityState.any())
    }
}

/**
 * M36.5：原生控制台编辑器就绪统一处理——注入补全上下文、记录文档引用、
 * 释放自研编辑器（原生控制台占据右侧）。
 */

internal fun SqlPreviewPanel.onNativeEditorReady(consoleEditor: com.intellij.openapi.editor.Editor) {
    val ctx = buildCompletionContext()
    consoleEditor.putUserData(SqlCompletionContext.KEY, ctx)
    consoleEditor.document.let { SqlCompletionContributor.registerContext(it, ctx) }
    nativeEditorDoc = consoleEditor.document
    // T-DbSchemaCompletion：控制台就绪 = 数据源已 attach/建连，此刻的 schema 预热最容易命中
    //（预览打开常早于建连，那时 getModel() 还是 null）
    current?.let { prefetchSchemaForCompletion(it) }
    // T-DbSchemaCompletion：同一窗口下 JOIN 图也一样——首次富化空手（模型未加载）时补刷一次，
    // 否则那张图会永远停在实体注释上（库里有 COMMENT，用户看到的是"JOIN 图没有字段注释"）。
    // 只补一次：target 命中即置空；富化确实拿到数据时从不登记，不会无谓重建图。
    val retryTarget = current
    if (retryTarget != null && diagramEnrichRetryTarget === retryTarget) {
        diagramEnrichRetryTarget = null
        updateDiagramTab(retryTarget)
    }
    // T-AutoFold F5：登记控制台编辑器引用（折叠应用目标）；就绪即按当前文档内容应用一次
    //（prepareConsole 链可能已同步 SQL 进文档，早于下一次 showSql）
    // T-PreviewLineCommentDash O-134/D136：exec 文件同样被 DialectPreMapping 映射 MySQL（Ctrl+/ 产 `#`），
    // 控制台编辑器一并打标接管；换装先把旧编辑器减计数（install/uninstall 各自幂等）
    nativeConsoleEditor?.takeIf { it !== consoleEditor }?.let(EditorCommentShortcuts::uninstall)
    nativeConsoleEditor = consoleEditor
    EditorCommentShortcuts.install(consoleEditor)
    // UI025：exec 文件在 .idea 下，daemon 不分析 → 编辑器级错列标注通道（与预览编辑器同源）。
    // install 内部首拍延迟 300ms/1500ms，覆盖 preparedEditor 与 schema 缓存晚到的窗口。
    cn.song.mybatisplus.visualizer.ui.annotate.PreviewSchemaHighlighter.install(consoleEditor)
    cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(
        consoleEditor,
        consoleEditor.document.text,
    )
    if (editor != null) {
        releaseEditor()
        editorHolder.removeAll()
    }
    // UI025：控制台编辑器的 ExternalAnnotator 需要「文档 → 已备控制台目标」身份，而
    // preparedEditor 在本回调返回后才由 prepareAfterEditorResolved 赋值。下一拍重启一次 daemon，
    // 让首轮 collect 就能读到目标（否则要等用户下一次输入才出现错列波浪线）。
    // 单元测试直接驱动 doHighlighting，不做全局 daemon 重启（避免拖慢/扰动其他用例）。
    if (!ApplicationManager.getApplication().isUnitTestMode) {
        ApplicationManager.getApplication().invokeLater({
            val proj = project
            if (proj != null && !proj.isDisposed) {
                runCatching {
                    @Suppress("DEPRECATION") // T-M52：243 编译目标仅此重载可用
                    val psi = com.intellij.openapi.application.ReadAction.compute<com.intellij.psi.PsiFile?, Throwable> {
                        com.intellij.psi.PsiDocumentManager.getInstance(proj).getPsiFile(consoleEditor.document)
                    }
                    if (psi != null) {
                        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(proj).restart(psi)
                    }
                }
            }
        }, com.intellij.openapi.application.ModalityState.any())
    }
}

/**
 * M36.5：发起控制台默认准备（面板展示时调用，不执行）——
 * Ultimate 下原生控制台即刻可见（▶ 工具栏/Tx Auto/Playground 等 DBN 原生元素常驻预览面板）；
 * Community 下静默回退自研编辑器；无数据源静默引导（不弹错误打断预览）。
 *
 * M36.5.25g 试过回退"预览即 DBN 控制台"——但用户明确要求**预览阶段也显示 DBN 工具栏**
 *（▶ 工具栏需始终可见），故恢复 prepareConsole 调用。
 */
/**
 * M36.5：发起控制台默认准备（面板展示时调用，不执行）——
 * Ultimate 下原生控制台即刻可见（▶ 工具栏/Tx Auto/Playground 等 DBN 原生元素常驻预览面板）；
 * Community 下静默回退自研编辑器；无数据源静默引导（不弹错误打断预览）。
 *
 * M36.5.25g 试过回退"预览即 DBN 控制台"——但用户明确要求**预览阶段也显示 DBN 工具栏**
 *（▶ 工具栏需始终可见），故恢复 prepareConsole 调用。
 *
 * T-RefreshDbSteal F4：兼作失联恢复入口 [recoverConsole]——回退编辑器在场时以回退编辑器
 * 的文本发起准备（准备链复用共享控制台会同步该 SQL），面板无任何编辑器时静默跳过。
 */

internal fun SqlPreviewPanel.prepareDefaultConsole() {
    val proj = project ?: return
    val sql = editor?.document?.text?.takeIf { it.isNotBlank() } ?: return
    val gateRequest = executionDialectGateRequest(
        if (SqlTypeClassifier.isQuery(sql)) cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Intent.QUERY
        else cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Intent.DML,
    )
    SqlExecutor.prepareConsole(
        proj, sql,
        // M36.5.58：预览换入控制台组件后**清空状态栏**——预览是干净上下文，不应残留上次
        // 执行的结果/错误（用户"执行第二个 SQL 后预览第一个，状态栏还显示之前的错误"）。
        // 嵌入后 showEmpty 清空；执行路径走 onResultsReady（成功）清 / onError（失败）显示，
        // 与本预览回调互不影响。
        onConsole = { consoleComponent ->
            // T-FirstExecResultInvisible F3：预览准备链显式 PREVIEW——旧形态传默认值
            //（布尔 false=execute 语义）靠 DS 侧折叠兜底，语义拧置归口 embedMode 后显式化
            embedConsole(consoleComponent, embedMode = ConsoleEmbedMode.PREVIEW)
            resultPanel.showEmpty()
            // T-PreviewFast P7：请求→控制台可见（第三段锚点，用户感知的「预览快不快」）
            runCatching {
                val req = SqlPreviewService.lastPreviewRequestMs
                if (req > 0) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                        .info("[T-PreviewFast] request→console-visible: ${System.currentTimeMillis() - req}ms")
                }
            }
            // T-PreviewConsoleFmt：控制台挂在这个 exec 文件上（其文档就是该文件文档），而文件
            // 内容由预览链预热写成**预览原文**（裸单行）——挂载后不重渲染，用户看到的就是裸
            // SQL（真机 20260917 日志：showSql createEditor len=274/16 行 → 预热原文 → attach
            // 后控制台显示 1 行）。嵌入完成即按当前渲染文本回填一次；必须排在此处而非
            // onNativeEditorReady —— showSql 的 nativeDoc 分支要求 consoleAttachedToMe()
            // （= embeddedConsole 已进 consoleHost），挂载回调那一刻尚未满足。
            // T-WSC-01：此处回调**会写文档**（renderSql → showSql → document.setText），故必须用
            // write-safe 模态——原 ModalityState.any() 恒 write-unsafe，真机报 Write-unsafe context
            // （见 DocWriteContext KDoc 与计划 20260917-1555 §一）。nonModal 的代价是模态弹窗存续期
            // 回填延后到弹窗关闭——这正是平台对「改文档」要求的语义。
            ApplicationManager.getApplication().invokeLater({
                if (com.intellij.openapi.util.Disposer.isDisposed(this)) return@invokeLater
                runCatching { renderSql() }.onFailure { e ->
                    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                        .warn("[M36] post-attach re-render FAILED: ${e.javaClass.simpleName}: ${e.message}")
                }
            }, com.intellij.openapi.application.ModalityState.nonModal())
        },
        onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
        // 默认准备失败（无数据源等）静默——不打扰预览；方言冲突由专用回调诚实显示
        onError = null,
        dialectGateRequest = gateRequest,
        onDialectGate = { verdict -> onExecutionDialectGate(verdict) },
    )
}

/**
 * M36.4：将拼合的原生控制台 UI 组件内嵌到右侧 SQL 显示栏。
 * M36.5.39-A1：入口身份探针——`sameAsPrev=true` 判定方案 A 路线有效（DBN results refreshed
 * 后传入的是同一对象），false 则需升级 B 路线（见「原生控制台嵌入方案AB-开发计划」决策树）。
 * M36.5.39-A2：嵌入统一走持久 consoleHost（容器只此一份），DBN 拿走组件时只清空 host 子层，
 * editorHolder 显示链不断裂。
 * M36.5.39-A3：组件被 DBN 完全释放（parent==null）后由 hierarchy 监听自动重挂，不等轮询。
 */
/**
 * T-FirstExecResultInvisible F3：入参显式化——旧 `previewMode: Boolean` 一个布尔兼「预览」
 * 与「全链执行」两语义，全链执行换入也传 true 导致执行组件被折叠（首执行结果区空白的
 * 布局侧共犯）。归口 [ConsoleEmbedMode]：EXECUTE 恒展开，PREVIEW 仅结果区无内容时折叠。
 */

internal fun SqlPreviewPanel.embedConsole(consoleComponent: JComponent, embedMode: ConsoleEmbedMode = ConsoleEmbedMode.EXECUTE) {
    val id = System.identityHashCode(consoleComponent)
    val prev = lastConsoleEmbeddedId
    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        .info("[M36] embedConsole identity: id=$id, prevEmbedded=$prev, sameAsPrev=${prev == id}, sameInstance=${consoleComponent === embeddedConsole}, embedMode=$embedMode")
    // O-96fix2：控制台**挂载即刷新**绑定指示行。S3 的刷新通道原本只收口在 renderSql 尾部，
    // 但「执行 SQL」路径（SqlExecutor.execute/executePrepared 的 onConsole → 本方法）挂载后
    // 不重渲染（控制台文档已是执行 SQL，重渲染反而冲掉）——指示行停留在挂载前的记忆态
    // 「关联（上次使用）：X」，须手动点刷新才升级「已绑定：X」（真机两路复现：选择数据源
    // 弹窗路径与 T-SI-02 静默复用路径一致复现）。本方法是全挂载路径的单点钩子，在此补一拍
    // 刷新即覆盖执行/预览/复用三链；调用时序安全——onConsole 回调前 preparedConsole/
    // sharedConsole 与 rememberRunner（T-SI-02 记忆写入）均已赋值（prepareInConsole/
    // reuseSharedConsole 同款顺序）。refreshBindStateRow 为纯文本读（O-95 口径），排
    // invokeLater(any) 与本方法既有 UI-only 调度同模态，无写副作用、重入幂等。
    ApplicationManager.getApplication().invokeLater({
        if (com.intellij.openapi.util.Disposer.isDisposed(this)) return@invokeLater
        runCatching { refreshBindStateRow() }.onFailure { e ->
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                .warn("[O-96fix2] bind-state refresh on embed FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
        // T-DialectStatusLiveRefresh：绑定行升级的同时重算方言决策——执行已证明具体目标
        // （提交闸写过模块学习/活控制台在场），「已绑定 + 方言未验证」并列的陈旧态在此消解。
        // 重算在池线程（URL 探测不进 EDT）、EDT 只做仅升级替换与轻量重组，幂等。
        runCatching { refreshDialectStatusRow() }.onFailure { e ->
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                .warn("[T-DialectStatusLiveRefresh] refresh on embed FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }, com.intellij.openapi.application.ModalityState.any())
    if (consoleComponent === embeddedConsole) {
        if (embedMode == ConsoleEmbedMode.EXECUTE && executeViewHeader == null) {
            val header = executeViewHeader()
            executeViewHeader = header
            consoleHost.add(header, java.awt.BorderLayout.NORTH)
        } else if (embedMode != ConsoleEmbedMode.EXECUTE && executeViewHeader != null) {
            consoleHost.remove(executeViewHeader)
            executeViewHeader = null
        }
        // M36.5.47：同实例防抖时**不清空结果、不重挂**（防闪烁），但必须清除"执行中"
        // loading 态——A 执行成功后 onResultsReady 回调同一实例（复用共享 console），
        // 原实现直接 return 导致 resultPanel 停留 loading，状态栏恒"执行中..."（用户
        // 实测"A 执行后停滞"）。结果区已在 console 组件内显示，清状态条即执行完成标志。
        // M36.5.49：用 showIdle() 而非 showEmpty()——只清 loading/成功态，**保留错误态**，
        // 避免执行失败后 showError 被后续 results refreshed 的同实例回调覆盖（"一闪而过"）。
        resultPanel.showIdle()
        // M36.5.77：execute 路径（embedMode=EXECUTE）即使同实例也要确保 divider 展开——
        // preview 时折叠为 1.0；execute 后必须恢复 0.6 显示结果区。F3 归口：PREVIEW 不触碰
        //（DS 侧折叠点带内容守卫，面板侧不叠加判断避免两侧漂移）。
        if (embedMode == ConsoleEmbedMode.EXECUTE && consoleComponent is javax.swing.JSplitPane) {
            // write-safe: UI-only（纯 Swing/只读，无文档写）
            ApplicationManager.getApplication().invokeLater({
                if (consoleComponent.height > 0) {
                    consoleComponent.setDividerLocation(0.6)
                    consoleComponent.revalidate()
                }
            }, com.intellij.openapi.application.ModalityState.any())
        }
        return
    }
    // T-RefreshDbSteal F3：旧组件的 rehost 监听必须显式解绑——共享控制台场景旧监听残活
    // 会把控制台抢回已废弃面板（真机 6 连抢实锤），detachConsole 内统一处理
    detachConsole()
    embeddedConsole = consoleComponent
    lastConsoleEmbeddedId = id
    // 去边框防白边（内嵌组件/容器可能自带 border）
    consoleComponent.border = JBUI.Borders.empty()
    consoleComponent.isOpaque = false
    // A2：host 挂入 editorHolder（clear/removeAll 后重挂，容器实例保持唯一）
    editorHolder.removeAll()
    editorHolder.add(consoleHost, java.awt.BorderLayout.CENTER)
    consoleHost.removeAll()
    consoleHost.add(consoleComponent, java.awt.BorderLayout.CENTER)
    if (embedMode == ConsoleEmbedMode.EXECUTE) {
        // P3a：执行结果视图明确标识 + 显式「返回预览」，避免用户只能靠「刷新」逃回编辑态。
        val header = executeViewHeader()
        executeViewHeader = header
        consoleHost.add(header, java.awt.BorderLayout.NORTH)
    } else {
        executeViewHeader = null
    }
    attachRehostListener(consoleComponent)
    if (consoleComponent is javax.swing.JSplitPane) {
        val sp = consoleComponent
        // M36.5.51：预览（execute=false）时也强制 divider 60%——一次性 componentListener
        // 的 `if (height<200) return` 在布局初期塌缩时不触发，导致"预览 SQL 编辑器塌缩"，
        // 执行后才被 swap 的 realignSplitDivider 恢复（用户截图：预览布局问题，点执行后恢复）。
        // invokeLater 下一拍组件已在树中，强制 60%（编辑器主导），不依赖 resize 时机。
        // write-safe: UI-only（纯 Swing/只读，无文档写）
        ApplicationManager.getApplication().invokeLater({
            if (sp.height > 0) {
                // M36.5.77：预览折叠底部（divider=1.0）隐藏无内容的 Console output 空框；
                // execute 路径展开底部（divider=0.6）。
                // T-FirstExecResultInvisible F3：折叠归口 embedMode——EXECUTE 恒展开；
                // PREVIEW 仅结果区无内容才折叠（竞态窗口内已有结果不吞）。
                val collapse = embedMode.shouldCollapseDivider(
                    cn.song.mybatisplus.visualizer.execute.DataSourceSelector.currentResultAreaHasContent(),
                )
                sp.setDividerLocation(if (collapse) 1.0 else 0.6)
                sp.revalidate()
                sp.repaint()
            }
            // M36.5.75：预览路径也安装 Output 控制台填充布局（幂等——执行路径 realign
            // 时重复调用直接跳过）。根因与修复见 DataSourceSelector.installOutputHistoryFillLayout。
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector
                .installOutputHistoryFillLayout(sp, com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java))
        }, com.intellij.openapi.application.ModalityState.any())
        sp.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                // M36.6.2：无条件首触强制 60%——旧条件 getDividerLocation()<=0 在首次布局后恒
                // false（divider 已是 preferred 推算的正值，只是落点错误），保险从未触发，
                // 分隔落点随构造时序漂移（用户三张截图实证：塌缩/截断/窄条三种形态）
                if (sp.height < 200) return
                // M36.5.77：preview 路径维持折叠（divider=1.0），execute 路径首触强制 0.6
                // T-FirstExecResultInvisible F3：同上归口 embedMode（触发时重探内容）
                val collapse = embedMode.shouldCollapseDivider(
                    cn.song.mybatisplus.visualizer.execute.DataSourceSelector.currentResultAreaHasContent(),
                )
                sp.setDividerLocation(if (collapse) 1.0 else 0.6)
                sp.removeComponentListener(this)
            }
        })
    } else {
        // M36.5.25：顶部块（非 SplitPane，首次预览 executeAfterPrepare=false）——
        // 编辑器组件可能按 preferred=0 塌缩为 0 高（SQL 被 Console output 占位遮住），
        // 获尺寸后强制重排一次（与 SplitPane divider 修复同机制）
        consoleComponent.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                consoleComponent.revalidate()
                consoleComponent.repaint()
                consoleComponent.removeComponentListener(this)
            }
        })
    }
    // M36.5.49：用 showIdle 而非 showEmpty——换入新控制台组件时若刚显示了 showError
    //（SQL 失败），showEmpty 会清掉错误（用户实测"SQL 错误无具体原因/一闪而过"）。
    // showIdle 只清 loading/成功态，保留 ERROR 态；组件换入成功的结果区已就绪。
    resultPanel.showIdle()
    revalidate()
    repaint()
}

/**
 * T-RefreshDbSteal F4：所有权判定——本面板记录的控制台实例是否仍在自己的 consoleHost 树内。
 * false = 控制台已被其他面板收养/或 DBN 释放后未重挂成功，此时任何"写入 nativeEditorDoc /
 * executePrepared"都是打在别人的控制台上（三症状公共前置条件）。
 */

internal fun SqlPreviewPanel.consoleAttachedToMe(): Boolean {
    val console = embeddedConsole ?: return false
    return console.parent != null && consoleHost.isAncestorOf(console)
}

/**
 * T-RefreshDbSteal F4：失联恢复——共享控制台已不在本面板时重新走准备链把它要回来。
 * `prepareConsole` 的 onConsole 回调经 [embedConsole] 幂等重嵌（M36.5.5 复用链会同步 SQL
 * 进控制台文档），准备链失败静默（与 prepareDefaultConsole 同语义），本面板此时已落在
 * 回退编辑器上，显示不悬空。
 */

internal fun SqlPreviewPanel.recoverConsole(sqlForRecovery: String) {
    if (project == null) return
    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        .info("[M36] recoverConsole triggered: len=${sqlForRecovery.length}, embedded=${System.identityHashCode(embeddedConsole)}")
    prepareDefaultConsole()
}

/**
 * M36.5.39-A3：DBN 释放监听——仅当组件被完全释放（parent==null，DBN 不再持有）且仍是当前
 * 嵌入实例时调度重挂；parent!=null 表示 DBN 临时 reparent（results refreshed 中转），不抢，
 * 避免竞争（方案 A 铁律）。
 *
 * M36.5.44 去抖修正：DBN 在 results refreshed 窗口内会**高频** reparent 整控台 SplitPane
 *（parent 在 null↔consoleHost 间横跳），若每次 parent==null 都立即重挂，会导致
 * "重挂→DBN 拿走→重挂…"无限循环（沙盒 16:40 实证：`console component released by DBN` 在
 * 毫秒级刷屏 2753 次，页面持续闪烁）。加入最小重挂间隔 [REHOST_MIN_INTERVAL_MS]：DBN 正忙于
 * reparent 时（两次释放间隔 < 门槛）跳过，待它稳定后再挂回——真正释放（DBN 不再碰）时
 * 间隔必然 > 门槛，兜底不丢。幂等重试仍由 [rehostConsoleForTest] 保证。
 *
 * T-RefreshDbSteal 两处修正：
 * ① 监听器引用保存到 [rehostListener]，[detachConsole]/[dispose] 显式移除——共享控制台被
 *   新面板接管后，旧面板的监听不再存活抢组件（真机 6 连抢根因）；
 * ② 去抖 skip 不再静默丢弃：DBN dance 收尾恰落在窗口内时旧实现永久悬空（零日志黑洞），
 *   现调度单次延迟复查（[REHOST_RETRY_DELAY_MS] 后仍失联且仍是当前实例 → 幂等重挂）。
 */

internal fun SqlPreviewPanel.attachRehostListener(component: JComponent) {
    rehostListener?.let { old -> component.removeHierarchyListener(old) }
    val listener = java.awt.event.HierarchyListener { e ->
        val parentChanged = (e.changeFlags and java.awt.event.HierarchyEvent.PARENT_CHANGED.toLong()) != 0L
        if (!parentChanged || e.component.parent != null || e.component !== embeddedConsole) return@HierarchyListener
        val now = System.currentTimeMillis()
        val since = now - lastRehostAt
        if (since in 0 until REHOST_MIN_INTERVAL_MS) {
            // T-RefreshDbSteal：去抖 skip 有日志 + 单次延迟复查兜底（旧实现静默 return 是
            // 控制台永久悬空的零日志黑洞——「间隔必然 > 门槛」假设被 DBN dance 收尾证伪）
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                .info("[M36] re-host debounced (since=${since}ms, id=${System.identityHashCode(e.component)}) → retry scheduled")
            scheduleRehostRetry(e.component as? JComponent ?: return@HierarchyListener)
            return@HierarchyListener
        }
        lastRehostAt = now
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] console component released by DBN (id=${System.identityHashCode(e.component)}) → re-host (since=${since}ms)")
        // ModalityState.any()：模态弹窗 pump 期间重挂照常执行（纯 Swing 操作安全，M36.5.13 教训）
        // write-safe: UI-only（纯 Swing/只读，无文档写）
        ApplicationManager.getApplication().invokeLater({
            rehostConsoleForTest(e.component as? JComponent ?: return@invokeLater)
        }, com.intellij.openapi.application.ModalityState.any())
    }
    rehostListener = listener
    component.addHierarchyListener(listener)
}

/** T-RefreshDbSteal：去抖窗口内被跳过的释放 → 延迟复查一次（仍失联则幂等重挂）。
 *  单发 Swing Timer（flashCopied 同款范式）：invokeLater 无延迟，复查会永远落在去抖
 *  窗口内被自己的 `since` 拦截，等于从未重试。 */

internal fun SqlPreviewPanel.scheduleRehostRetry(component: JComponent) {
    if (rehostRetryScheduled) return
    rehostRetryScheduled = true
    javax.swing.Timer(REHOST_RETRY_DELAY_MS.toInt()) {
        rehostRetryScheduled = false
        // 复查条件与监听器同款：仍是当前嵌入实例且仍无 parent（被别的容器收养则不抢）
        if (component !== embeddedConsole || component.parent != null) return@Timer
        val since = System.currentTimeMillis() - lastRehostAt
        if (since in 0 until REHOST_MIN_INTERVAL_MS) return@Timer
        lastRehostAt = System.currentTimeMillis()
        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
            .info("[M36] re-host retry fired (id=${System.identityHashCode(component)}, since=${since}ms)")
        rehostConsoleForTest(component)
    }.apply {
        isRepeats = false
        start()
    }
}

/**
 * M36.5.39-A3：重挂（同步逻辑抽出可单测）。仅接受当前嵌入实例且尚未挂回 host 的组件；
 * 返回 true = 已重挂。
 */

internal fun SqlPreviewPanel.rehostConsoleForTest(component: JComponent): Boolean {
    if (component !== embeddedConsole || component.parent === consoleHost) return false
    consoleHost.removeAll()
    consoleHost.add(component, java.awt.BorderLayout.CENTER)
    consoleHost.revalidate()
    consoleHost.repaint()
    return true
}

/**
 * M36：摘除已内嵌的控制台组件（清空预览时调用；JdbcConsole 生命周期由 IDE 管理）。
 * M36.5.39-A2：只清空 consoleHost 子层，容器保留——再次嵌入复用同一容器。
 * T-RefreshDbSteal：同时移除本面板挂接的 rehost 监听——共享控制台被新面板接管后，
 * 本面板的监听必须随之失效（旧实现不移除，僵尸监听把控制台抢回废弃面板）。
 */

internal fun SqlPreviewPanel.detachConsole() {
    embeddedConsole?.let {
        rehostListener?.let { l -> it.removeHierarchyListener(l) }
        consoleHost.remove(it)
    }
    // P3a：执行视图头部（返回预览）也属于控制台宿主层，摘除时一并清空。
    executeViewHeader = null
    consoleHost.removeAll()
    // T-AutoFold F5：控制台组件摘除即清编辑器引用（防 EditorFactory 泄漏；新组件就绪时经
    // onNativeEditorReady 重新登记）
    // T-PreviewLineCommentDash O-134：摘除先减计数（install 的对称端点之一）
    nativeConsoleEditor?.let(EditorCommentShortcuts::uninstall)
    nativeConsoleEditor = null
    embeddedConsole = null
    rehostListener = null
}

/**
 * 面板按钮字体（T-DevAcceptFix2 ⑨）：整数磅值派生的 UI 字体。**勿改回非整数**（旧 `10.5f`
 * 经 Java2D 非整数度量路径渲染，字形发糊——用户对照工具栏按钮实测）；工具栏与小按钮同源取值。
 */

internal fun SqlPreviewPanel.buttonFont() = font.deriveFont(11f)


internal fun SqlPreviewPanel.smallButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
    font = buttonFont()
    addActionListener { action() }
}

/**
 * T-DmlImpactProminence：主行动按钮样式（蓝色实底）——用于「返回预览」「复制 SQL 并关闭」
 * 这类需要一眼看到的显式出口。
 *
 * UI-043：**必须交给平台绘制**，不能手工涂底色。早期实现是 `isOpaque = true` + `background`
 * 填主题蓝，而 Darcula 按钮边框是**内缩 4px 的圆角**（`DarculaButtonPainter.getOffset() == 4`）：
 * 底色按整个矩形铺满，圆角之外就多出一圈方形蓝边（用户截图所见）。
 *
 * 平台的标准入口是 client property `JButton.styleDefault`（= [DarculaButtonUI.DEFAULT_STYLE_KEY]）：
 * `DarculaButtonPainter` 在 `paintBorder` 里认这个键，走原生 default 样式的圆角填充与配色。
 * 平台自身的 ButtonPanel、OnboardingDialogButtons 都是这么打的标。
 */
internal fun applyPrimaryButtonStyle(button: JButton) {
    button.putClientProperty(
        com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI.DEFAULT_STYLE_KEY,
        java.lang.Boolean.TRUE,
    )
    // 底色/前景交给 LAF；显式清掉，防止外层（如 resultPanel 主题覆盖）残留手工涂色
    button.isOpaque = false
    button.isFocusPainted = false
    button.repaint()
}

internal fun primaryButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
    applyPrimaryButtonStyle(this)
    addActionListener { action() }
}

/** 压缩/多行切换按钮文案：显示"将要切换到的模式"。 */

internal fun SqlPreviewPanel.modeButtonText(): String = MpSqlVisualizerBundle.message(
    if (compactMode) "panel.mode.multiline" else "panel.mode.singleline",
)

// ------------------------------------------------------------------
// 展示与渲染
// ------------------------------------------------------------------

/**
 * 更新展示内容。
 *
 * @param result 预览结果
 */
