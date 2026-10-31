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

// T-M53-II（2026-10-10）同法，D202 第六次登记：SqlPreviewPanel 的「EXPLAIN 双通道执行链
//（含结构化 EXPLAIN 与降级通知）」内聚成员，原样搬出到本文件——方法体逐字符搬运，
// 同包 top-level internal 可见性不变，零行为改动。

/**
 * M37.2：真实 EXPLAIN——按当前方言把预览 SQL 包装为 EXPLAIN 语句，走执行链提交原生控制台。
 * 结果展示与普通执行一致（内嵌控制台结果 Tab）；静态性能规则标注保留在顶部 meta 区，同屏对照。
 * EXPLAIN 归免确认类（[SqlTypeClassifier]），无 DML 二次确认；方言不支持（null）→ 提示降级。
 *
 * T-ExplainChainHardening T-ECH-01（O-48，D1=A 严格判定）：包装前先判**被包语句**的首关键字
 * 是否落在 [ExplainGate] 白名单——此前对编辑器里的任意文本加前缀（`DELETE FROM t`、
 * `CREATE TABLE …`、标识符 `SELECTED` 一律被包成 EXPLAIN 送进库），现改为明确提示、不执行。
 * 判据只看「未包装」的文本：已以 EXPLAIN 开头的文本是本插件上一次的包装产物（T-ExplainDbl
 * 幂等场景），不适用白名单，否则二次点击会被误拒。
 */

internal fun SqlPreviewPanel.explainSql() {
    val proj = project ?: return
    val sql = nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
        ?: editor?.document?.text?.takeIf { it.isNotBlank() } ?: return
    val dialect = current?.effectiveDialect ?: MySqlDialect
    // T-ECH-01：提交判定（幂等 → 首关键字白名单 → 方言支持面），两类拒绝理由分别提示
    val explainSql = when (val plan = ExplainGate.planExplainSubmit(dialect, sql)) {
        is ExplainGate.SubmitPlan.NotExplainable -> {
            notifyExplainNotExplainable(proj, plan.firstKeyword)
            return
        }
        is ExplainGate.SubmitPlan.DialectUnsupported -> {
            // T-UiNative T-UN-02：方言不支持提示改轻通知（原模态弹窗——纯告知无需打断）
            if (!ApplicationManager.getApplication().isUnitTestMode) {
                runCatching {
                    Notification(
                        NOTIFICATION_GROUP_ID,
                        MpSqlVisualizerBundle.message("panel.explain"),
                        MpSqlVisualizerBundle.message("panel.explain.unsupported", plan.dialectName),
                        NotificationType.INFORMATION,
                    ).notify(proj)
                }
            }
            return
        }
        is ExplainGate.SubmitPlan.Submit -> {
            // R23：二段式方言（Oracle）的计划不在本次语句的返回行里——提交前轻通知告知另查路径
            //（描述的是方言固有形态，不预设执行成败；SQL Server 无 postPlanQuery，控制台多结果页签自明）
            cn.song.mybatisplus.visualizer.sql.ExplainSupport.entryFor(dialect.name)?.postPlanQuery?.let { followUp ->
                if (!ApplicationManager.getApplication().isUnitTestMode) {
                    runCatching {
                        Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("panel.explain"),
                            MpSqlVisualizerBundle.message("panel.explain.planRegistered", followUp),
                            NotificationType.INFORMATION,
                        ).notify(proj)
                    }
                }
            }
            plan.statement
        }
    }
    com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
        .info("[M37.2] explainSql: dialect=${dialect.name}, len=${explainSql.length}")
    // T-InjectionGate T-IG-03：EXPLAIN 本就真实执行——`${}` raw 段同门控（旁路不得绕开执行闸门）；
    // 方言不支持（上方已 return，未执行）不弹
    if (!confirmDollarRawSegments(proj)) return
    // T-PC2-04（D1=B）：EXPLAIN 同门控（旁路不得绕开执行闸门，与 T-IG-03 同款姿态）
    if (!confirmUnknownParams(proj)) return
    // T-PSG-04（N-33）：对账非阻断标注与 executeSql 同址同款（EXPLAIN 也是执行旁路）
    warnOnPlaceholderMismatch()
    // R33：面板 EXPLAIN 双通道分派（结构化渲染为增量——判定/执行/渲染任一环不成立都
    // fail-closed 回退直送通道，双通道并存不替换既有行为，见 submitExplainOrStructured）
    // 代际先递增（所有路径）——作废前一次提交的在途结构化回调
    explainStructuredGeneration++
    submitExplainOrStructured(
        proj, dialect, explainSql,
        executionDialectGateRequest(cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Intent.EXPLAIN),
    )
}

/**
 * R33：EXPLAIN **直送通道**（既有行为原样收敛）——包装语句交原生 Database 控制台执行，
 * 结果区内嵌控制台（embedConsole）。结构化通道的初始回退与「回到原生控制台」入口都复用
 * 本函数：直送链只此一份，绝不新增第二套执行链。
 */

    /** UI023：仅观察最终通知对象，不替代校验、渲染或执行分支。 */
    internal var schemaNotificationForTest: ((Notification) -> Unit)? = null

    /**
     * R46：查询链的未识别对象轻通知（非模态、不阻断执行——与 DML 确认弹窗同源同文案）。
     * 无发现或单测模式不弹（弹窗/通知克制红线；真机才提示）。
     */
    internal fun SqlPreviewPanel.notifySchemaIssues(
        proj: Project,
        issues: List<cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator.Issue>,
    ) {
        val body = cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.renderNotification(issues) ?: return
        if (ApplicationManager.getApplication().isUnitTestMode && schemaNotificationForTest == null) return
        runCatching {
            val notification = Notification(
                NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("schema.check.notification.title"),
                body,
                NotificationType.WARNING,
            )
            val observer = schemaNotificationForTest
            if (observer != null) observer(notification) else notification.notify(proj)
        }
    }

/**
 * T-PreviewSideQuery P2：显式「在控制台打开」路径（保留原有 SqlExecutor.execute 行为）。
 * 仅由用户在结果对话框里明确选择时调用；默认 EXPLAIN/回退不再走这里。
 *
 * T-ExplainConsoleFirst W8（2026-10-24 沙盒验收实测）：**进执行链前先预热 exec 临时文件**。
 *
 * 实测症状：点「执行计划」刷 9 条 `SEVERE - SlowOperations ... Plugin to blame: SQL Visualizer`，
 * 栈为 `explainSql → submitExplainDirectConsole → SqlExecutor.execute → chooseAndPrepare →
 * createTempPsiFile → PsiManagerImpl.findFile`——即 `createTempPsiFile` 的**冷路径**
 * （VFS refresh + `PsiManager.findFile`）在 EDT 上跑。
 *
 * 为什么现在才暴露：`1a1cf724` 之后 EXPLAIN 默认走旁路查询（`PreviewSideQuery`），不经过
 * 控制台链；本轮 W1 把它切回控制台后，这条既有冷路径重新进入热路径。既有预热
 * （`prewarmExecTempFile`）只服务「预览 SQL」，且仅在方言重渲染路径触发，EXPLAIN 文本从未预热过。
 *
 * 修法：执行前把**本条 EXPLAIN 文本**交给后台预热（`prewarmExecTempFile` 自带 EDT 拒绝、
 * 预算等待、并发去重），命中缓存后 `createTempPsiFile` 走热路径，不再在 EDT 上做 VFS/PSI 慢操作。
 * 预热写的是 EXPLAIN 文本本身，与随后执行的内容一致，不会污染（执行前仍有
 * `syncExecVfContentIfNeeded` 兜底同步）。预热失败只是回落到既有冷路径，不阻断执行。
 */
internal fun SqlPreviewPanel.submitExplainDirectConsole(
    proj: Project,
    explainSql: String,
    dialectGateRequest: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request,
) {
        // R46：EXPLAIN 是执行旁路，同样过 Schema 校验（未识别对象走轻通知，不阻断）
        when (val outcome = cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.check(proj, explainSql)) {
            is cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.Outcome.Checked ->
                notifySchemaIssues(proj, outcome.issues)

            else -> Unit
        }
    // W8：后台预热 exec 文件，避免 createTempPsiFile 冷路径落 EDT（`PsiManager.findFile` 慢操作）。
    //
    // W8-fix（同日复验修订）：**不得在 EDT 上阻塞等待预热**。首版写成
    // `future.get(8s)` 直接在 EDT 上等——沙盒预热带 1s 侥幸通过，但慢盘/索引期会把 UI 冻住 8s，
    // 且与「池线程内又 submit 一个池线程再 get(30s)」的既有 prewarm 形成**双层嵌套等待**。
    //
    // 现形态：后台线程里**直接调 inner**（不嵌套 `prewarmExecTempFile` 的预算等待，那条是给
    // 「后台预热」这种非交互场景用的），完成后回 EDT 续接执行链。预热失败/超时都照常续接
    // （回落既有冷路径，不阻断用户点击）。
    val projForPrewarm = proj
    val explainSqlForPrewarm = explainSql
    val requestForPrewarm = dialectGateRequest
    val prewarmDone = java.util.concurrent.atomic.AtomicBoolean(false)
    val continueChain = {
        if (prewarmDone.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater({
                if (Disposer.isDisposed(this) || proj.isDisposed) return@invokeLater
                resultPanel.showLoading()
                SqlExecutor.execute(
                    proj, explainSql, null, true,
                    onConsole = { consoleComponent ->
                        embedConsole(consoleComponent, embedMode = ConsoleEmbedMode.EXECUTE)
                    },
                    onEditor = { consoleEditor -> onNativeEditorReady(consoleEditor) },
                    onError = { errorMsg -> resultPanel.showError(errorMsg) },
                    onErrorRich = { info -> showErrorRichWithLocate(info) },
                    dialectGateRequest = dialectGateRequest,
                    onDialectGate = { verdict -> onExecutionDialectGate(verdict) },
                )
            }, com.intellij.openapi.application.ModalityState.any())
        }
    }
    ApplicationManager.getApplication().executeOnPooledThread {
        runCatching {
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prewarmExecTempFileOnBackgroundThread(
                projForPrewarm,
                explainSqlForPrewarm,
                requestForPrewarm.renderedDialectName,
                com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java),
            )
        }.onFailure { e ->
            com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel::class.java)
                .info("[W8] exec prewarm before explain failed (fallback to cold path): ${e.javaClass.simpleName}: ${e.message}")
        }
        // 预热完成（含失败）都续接——预热只是优化，不是前置条件。
        ApplicationManager.getApplication().invokeLater({ continueChain() },
            com.intellij.openapi.application.ModalityState.any())
    }
}

/**
 * T-ExplainConsoleFirst W1（2026-10-24）：执行计划默认/回退**统一走内嵌原生控制台**。
 *
 * 形态渊源：这是初版实现（`1a1cf724` 前的 `submitExplainDirect`），`1a1cf724` 为了「不顶掉
 * 预览编辑器」把默认改成旁路查询（形态 B）。十二轮换源拉锯（T-DialectInferPollution /
 * T-ExplainFailUx / T-ExplainSwitchNative，合计 W1~W12）暴露出形态 B / 结构化通道的系统性代价：
 * 它们是插件自建 JDBC 通道，于是「用哪条源、密码从哪来、方言对不对、失败怎么呈现」全落在插件
 * 身上；而平台「选中即关联」（`getForcedDataSourceForExec` 命中即 `singletonList` 短路枚举、
 * 无公开清除 API）让插件侧选源列表第二次起恒为单源 —— 这是那十二轮全部拉锯的根因。
 *
 * 形态 A 把这些**整类**交回平台：换源走内嵌控制台自带的原生 `Data Sources` 菜单（Detach +
 * 全部源），密码走平台凭据链，方言与报错在控制台原生呈现。`1a1cf724` 当初真正的顾虑
 * （执行视图里只读动作拿到 EXPLAIN 语句）已由 [previewSqlForReadonly] 根治（W2）。
 *
 * 前置只判「一条源都没有」→ 引导去 Database 工具窗口；**不在插件侧做任何选源**
 * （不重演 W1~W12）。结构化形态（`explainStructuredRender`）不受影响，仍在设置项后。
 */
internal fun SqlPreviewPanel.submitExplainDirect(
    proj: Project,
    explainSql: String,
    dialectGateRequest: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request,
) {
    // R46：EXPLAIN 是执行旁路，同样过 Schema 校验（未识别对象走轻通知，不阻断）
    when (val outcome = cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.check(proj, explainSql)) {
        is cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.Outcome.Checked ->
            notifySchemaIssues(proj, outcome.issues)

        else -> Unit
    }
    // 零源判定：只要有一条在籍源就交给平台（平台自己决定用哪条、按需弹选择器），
    // 插件不枚举、不筛选、不猜。测试缝非 null 即视为「有可用源」。
    val hasAnySource = explainTargetForTest?.let { it() != null } ?: runCatching {
        cn.song.mybatisplus.visualizer.execute.DataSourceSelector.registeredDataSourceCount(proj) > 0
    }.getOrDefault(true)
    if (!hasAnySource) {
        // 一条源都没有：按钮直达 Database 工具窗口（按提示去配置/连接），不空跑一次必然失败的执行。
        showExplainErrorDialog(
            MpSqlVisualizerBundle.message("panel.explain.noDataSource"),
            openDatabase = true,
        )
        return
    }
    submitExplainDirectConsole(proj, explainSql, dialectGateRequest)
}

/**
 * T-ExplainFailUx W1（2026-10-24 用户沙盒实测）：**选错数据源**失败的就地引导。
 *
 * 为什么不用模态：`Table 'mp_demo.x' doesn't exist` 是最典型的「该换源」信号，模态框会先遮住
 * 预览、还要求用户先关掉再点「在控制台打开」两步才见到换源入口（用户原话：「这种情况就不应该
 * 弹窗了吧」）。改为结果区就地横幅（[SqlResultPanel.showErrorWithActions] 双动作槽）。
 *
 * T-ExplainSwitchNative W6（同日修订）：动作语义改为「**弹平台原生选择器**」且**不自动重试**
 * ——用户实测「点切换数据源却重试执行了」：旧实现走 `prepareConsole(forceChoose=true)`，
 * 而该参数只跳过 last-used 静默，runner 唯一时仍会直选执行，用户看不到列表。
 */
private fun SqlPreviewPanel.showExplainWrongTarget(
    proj: Project,
    message: String,
    explainSql: String,
    dialectGateRequest: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request,
    openConsole: () -> Unit,
) {
    resultPanel.showIdle()
    val hint = MpSqlVisualizerBundle.message("panel.explain.wrongTargetHint")
    resultPanel.showErrorWithActions(
        message + "　" + hint,
        fixLabel = MpSqlVisualizerBundle.message("panel.explain.action.switchDataSource"),
        fixTooltip = MpSqlVisualizerBundle.message("panel.explain.action.switchDataSource.tip"),
        // W12：换源走平台原生工具栏 Data Sources 菜单（见上方 showDialectGateError 同款说明）
        fix = { cn.song.mybatisplus.visualizer.execute.DataSourceSelector.openDatabaseToolWindow(proj) },
        settingsLabel = MpSqlVisualizerBundle.message("panel.explain.openConsole"),
        settingsTooltip = null,
        settings = openConsole,
    )
}

/**
 * T-ExplainPwdPrompt W6（2026-10-24）：认证失败轻通知——平台凭据链（askPassword=true 的
 * 「Change Credentials / Abort」对话框）已完成失败呈现与两个出口，本通知只做 Abort 后的
 * 收口反馈（含补存密码指引），绝不弹红字模态框二次打断。单测模式不弹（notifySchemaIssues
 * 同款守卫）。
 */
private fun SqlPreviewPanel.notifyExplainAuthFailure(proj: Project) {
    if (ApplicationManager.getApplication().isUnitTestMode && schemaNotificationForTest == null) return
    runCatching {
        Notification(
            NOTIFICATION_GROUP_ID,
            MpSqlVisualizerBundle.message("panel.explain"),
            MpSqlVisualizerBundle.message("panel.explain.authFailureHint"),
            NotificationType.WARNING,
        ).notify(proj)
    }
}

/**
 * T-ExplainPwdPrompt（2026-10-24）：用户取消（原生密码框 Esc 等）——轻通知收口，不弹红字
 * 模态框（取消是用户自己的决定，模态报错等于打断两次），也**绝不自动重试**（重试会再弹一次
 * 密码框）。单测模式不弹（弹窗/通知克制红线，notifySchemaIssues 同款守卫）。
 */
private fun SqlPreviewPanel.notifyExplainCancelled(proj: Project) {
    if (ApplicationManager.getApplication().isUnitTestMode && schemaNotificationForTest == null) return
    runCatching {
        Notification(
            NOTIFICATION_GROUP_ID,
            MpSqlVisualizerBundle.message("panel.explain"),
            MpSqlVisualizerBundle.message("panel.explain.cancelled"),
            NotificationType.INFORMATION,
        ).notify(proj)
    }
}

/**
 * R33：面板 EXPLAIN 双通道分派（自建执行链路线，批次 9 核实报告 §三 V41 定案）。
 *
 * 判定（[ExplainPanelChannel.decide]，纯逻辑）：开关开 ∧ 输出格式 ∈ {TABLE, JSON}
 * ∧ database 插件在场 ∧ 预览会话已绑数据源 → 结构化通道；开关关/其余方言（TEXT/XML，
 * 含 R23 Oracle 二段式与 SQL Server XML——不硬解析红线）→ 设计内直送（静默）；无数据源/
 * 无插件 → fail-closed 回退直送＋一条轻提示（不弹窗）。执行/渲染异常同样回退直送——
 * **结构化通道绝不成为 EXPLAIN 的唯一路径**。
 *
 * 数据源取当前预览会话已绑数据源（循 T-ConsoleDbContext 既有选择形态：预览用哪条
 * 数据源，EXPLAIN 就在同一条上跑）；JDBC 全程 pooled 线程（禁 EDT），结果经 EDT 回调
 * 换入结构化视图，代际计数防过期回调覆盖新预览。
 */

internal fun SqlPreviewPanel.captureExplainPlanHistory(
    history: cn.song.mybatisplus.visualizer.history.SqlHistoryManager,
    planKey: cn.song.mybatisplus.visualizer.history.ExplainPlanKey,
    target: Any,
): cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Request = captureExplainPlanHistory(history, planKey, target) {
    // Pure bound-object lookup, not roster/last-used lookup and not a connection operation.
    val bound = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSource()
    bound === target && cn.song.mybatisplus.visualizer.execute.DataSourceSettings.getUniqueId(bound)
        ?.equals(planKey.dataSourceUuid, ignoreCase = true) == true
}

internal fun SqlPreviewPanel.captureExplainPlanHistory(
    history: cn.song.mybatisplus.visualizer.history.SqlHistoryManager,
    planKey: cn.song.mybatisplus.visualizer.history.ExplainPlanKey,
    target: Any,
    targetStillCurrent: () -> Boolean,
): cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Request {
    val originalPreview = current
    val retentionEnabled = PluginSettings.getInstance().state.explainPlanRetentionEnabled
    aiContextTarget = SqlPreviewPanel.AiContextTarget(originalPreview, planKey, targetStillCurrent)
    // XML/日志纯文本预览没有经过 recordHistory；首次 EXPLAIN 复用同一历史域建条目。
    // 已有 ID 即使被用户删除也绝不补建，防删除后在途结果复活。
    if (retentionEnabled && originalPreview != null && originalPreview.historyEntryId.isEmpty() && planKey.persistable) {
        originalPreview.historyEntryId = history.add(cn.song.mybatisplus.visualizer.history.SqlHistoryEntry(
            sql = originalPreview.sql, compactSql = originalPreview.compactSql,
            timestamp = System.currentTimeMillis(), tableName = originalPreview.tableName.orEmpty(),
            projectIdentity = planKey.projectIdentity,
            // T-PresetMethodScope：补建条目沿用当前预览的作用域，参数快照回落口径一致。
            originScopeKey = originalPreview.originScopeKey,
        )).id
    }
    return explainPlanHistorySession.begin(
        history, originalPreview?.sql.orEmpty(), planKey, target, originalPreview?.historyEntryId.orEmpty(), retentionEnabled,
    )
}

/** 成功回调重新读取当前设置，不能使用提交前的 State 引用（期间可能 apply/loadState）。 */
internal fun SqlPreviewPanel.completeExplainPlanHistory(
    history: cn.song.mybatisplus.visualizer.history.SqlHistoryManager,
    request: cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Request,
    raw: cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult,
): cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Completion =
    explainPlanHistorySession.complete(history, request, raw, PluginSettings.getInstance().state.explainPlanRetentionEnabled)

/** R91: retain an already obtained value snapshot in memory, never write history from the copy action. */
internal fun SqlPreviewPanel.captureAiContextPlan(
    history: cn.song.mybatisplus.visualizer.history.SqlHistoryManager,
    key: cn.song.mybatisplus.visualizer.history.ExplainPlanKey,
    raw: cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult,
) {
    aiContextPlan = history.explainPlanGeneration() to
        cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot.create(key, raw)
}

internal fun SqlPreviewPanel.submitExplainOrStructured(
    proj: Project,
    dialect: cn.song.mybatisplus.visualizer.sql.Dialect,
    explainSql: String,
    dialectGateRequest: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request,
    /** T-UI030：重试路径禁止再弹数据源选择器（防「取消 → 再弹 → 再取消」循环）。 */
    allowDataSourcePrompt: Boolean = true,
    /** T-DialectInferPollution W7：本链已自动重渲染过一次（防闸门重提交循环）。 */
    rerendered: Boolean = false,
) {
    val settings = PluginSettings.getInstance().state
    val boundDs = explainBoundDsForTest?.invoke() ?: runCatching {
        cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSource()
    }.getOrNull()
        // T-ExplainPwdPrompt W4（2026-10-24）：真绑定缺失且绑定链 runner 枚举被方言路由卡死
        // （数据源无 Dialects 映射时平台返回空，沙盒实测 Database 视图已连接仍 runners=0）时，
        // 回落到面板「数据源（上次使用）」记忆取源——结构化通道（askPassword=true）不依赖
        // 绑定链即可到达；歧义即弃，方言不符由既有 allowDialectGate 闸拦。
        ?: runCatching {
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.explainFallbackDataSource(
                proj, sourceModuleKey, explainSql,
            )
        }.getOrNull()
    // O-145 W7：结构化 EXPLAIN 不经过 SqlExecutor，必须在 JDBC 直连前用同一实际数据源闸门。
    if (boundDs != null && !cn.song.mybatisplus.visualizer.execute.DataSourceSelector.allowDialectGate(
            proj,
            dialectGateRequest,
            cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.fromDataSource(boundDs),
            { verdict ->
                // T-DialectInferPollution W7（2026-10-24 用户真机报障）：渲染方言与目标数据源
                // 不一致（如 last-used 在竞品测试后残留 dbx_test/PG，MySQL 预览被按 PG 渲染）时
                // 闸门 AUTO_RERENDER_REQUIRED 重渲染成功——**自动重提交一次**，不再让用户重按
                // 「执行计划」；重渲染失败或已重渲染过（防循环）回落既有红条提示。
                if (verdict.status == cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Status.AUTO_RERENDER_REQUIRED &&
                    !rerendered &&
                    verdict.targetDialectName != null &&
                    rerenderForExecutionTarget(verdict.targetDialectName!!)
                ) {
                    submitExplainOrStructured(
                        proj, dialect, explainSql, dialectGateRequest,
                        allowDataSourcePrompt = false, rerendered = true,
                    )
                } else {
                    onExecutionDialectGate(verdict)
                }
            },
            { message -> resultPanel.showError(message) },
        )) return
    val format = cn.song.mybatisplus.visualizer.sql.ExplainSupport.formatFor(dialect.name)
    val databaseAvailable = explainDatabaseAvailableForTest?.invoke()
        ?: cn.song.mybatisplus.visualizer.mcp.McpDbExecutor.isDatabaseAvailable()
    // R33 真机走查修复：编辑器文本就是回填链的终点产物（参数击键防抖重渲染），此处残留
    // `?`（串感知位点，字符串/注释里的 `?` 不计）即真未填——不下发结构化探测（字面 `?`
    // 送 JDBC = 语法错），按响亮失败回退并明示「存在未回填参数」
    val hasUnfilledParams = placeholderPositions(explainSql).isNotEmpty()
    when (
        val verdict = cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.decide(
            enabled = settings.explainStructuredRender,
            format = format,
            databaseAvailable = databaseAvailable,
            hasBoundDataSource = boundDs != null,
            hasUnfilledParams = hasUnfilledParams,
        )
    ) {
        cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.Decision.DirectSilent -> {
            // T-UI030：开关关闭时不再只是「静默直送」——该方言确有结构化面且 Database 插件
            // 在场时给一条带「启用结构化并重试」动作的通知；TEXT/XML/Community 不提示（开启
            // 也没用，只会变成噪音）。不点动作时行为与改前逐字一致（仍直送原生控制台）。
            //
            // T-ExplainConsoleFirst W6（2026-10-24 用户实测「为什么我点击执行计划还是弹窗了」）：
            // 该推荐**只提示一次**。此前每次点执行计划都弹，用户顺手点过一次「启用结构化」后
            // 开关被永久写成 true，界面上再无任何「现在是实验形态」的提示——下次看到弹窗时
            // 完全无从归因。沙盒实测该配置确为 true、而代码默认是 false，差异只可能来自这里。
            if (cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.shouldOfferStructuredEnable(
                    settings.explainStructuredRender, format, databaseAvailable,
                ) && !settings.explainStructuredOfferedShown
            ) {
                settings.explainStructuredOfferedShown = true
                notifyStructuredExplainDisabled(proj) {
                    enableStructuredExplainAndRetry(proj, dialect, explainSql, dialectGateRequest)
                }
            }
            submitExplainDirect(proj, explainSql, dialectGateRequest)
        }

        is cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.Decision.DirectFallback -> {
            if (verdict.reason == cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.NO_DATA_SOURCE) {
                if (allowDataSourcePrompt) {
                    // T-UI030：真机反馈这条「已回退原生控制台」只是告知、没有下一步。改为直接弹
                    // 既有数据源选择器（与执行 SQL 同一个 runner 选择器，只绑定不执行任何 SQL），
                    // 选中后自动重试结构化；用户取消则只给说明，绝不再弹第二次。
                    promptDataSourceThenRetryStructured(proj, dialect, explainSql, dialectGateRequest)
                } else {
                    // 重试仍未绑定：只给现有回退说明，不再触发直送链（直送链会再次弹选择器）。
                    notifyExplainStructuredFallback(proj, verdict.reason, detail = null)
                }
            } else if (verdict.reason ==
                cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.UNFILLED_PARAMS
            ) {
                // T-ExplainPwdPrompt W5（2026-10-24 用户沙盒实测 bug）：参数未回填**不回退直送**。
                // 回退直送在无绑定形态下必然失败且再弹一个误导性「未绑定数据源」红字框（用户明明
                // 有 last-used 数据源）；有绑定形态把字面 `?` 送 JDBC 同样是语法错（R33 同款结论）。
                // 通知已含原因与动作（填参数后重试），就地收口即可。
                notifyExplainStructuredFallback(proj, verdict.reason, detail = null)
            } else {
                notifyExplainStructuredFallback(proj, verdict.reason, detail = null)
                submitExplainDirect(proj, explainSql, dialectGateRequest)
            }
        }

        cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.Decision.Structured -> {
            val ds = boundDs
            val outFormat = format
            // 防御：判定与取值之间环境可能变化（数据源失效等）——缺一即回退，不硬解包
            if (ds == null || outFormat == null) {
                submitExplainDirect(proj, explainSql, dialectGateRequest)
                return
            }
            resultPanel.showLoading()
            val generation = explainStructuredGeneration
            // R90：提交时捕获原 SQL/历史条目/实际目标；回调不得再读 current 或当前数据源。
            val history = cn.song.mybatisplus.visualizer.history.SqlHistoryManager.getInstance()
            val planKey = cn.song.mybatisplus.visualizer.history.ExplainPlanKey.create(
                cn.song.mybatisplus.visualizer.history.ExplainPlanKey.projectIdentity(proj.basePath),
                cn.song.mybatisplus.visualizer.execute.DataSourceSettings.getUniqueId(ds),
                dialect.name, outFormat, explainSql,
            )
            val historyRequest = captureExplainPlanHistory(history, planKey, ds)
            cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.executeStructured(
                proj, ds, explainSql,
                timeoutSeconds = settings.executionTimeoutSeconds,
                onPlan = { outcome ->
                    if (com.intellij.openapi.util.Disposer.isDisposed(this) || generation != explainStructuredGeneration) {
                        return@executeStructured
                    }
                    // 索引建议联动（ExplainIndexAdvisor 规则集的面板侧消费——只提示不执行；
                    // 建议器异常不连坐计划渲染，降级为无建议）
                    val hints = runCatching {
                        cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor.advise(
                            dialect.name, outcome.raw.headers, outcome.raw.rows,
                        )
                    }.getOrDefault(emptyList())
                    val historyCompletion = completeExplainPlanHistory(history, historyRequest, outcome.raw)
                    if (historyCompletion.saved != cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult.CLEARED_OR_REMOVED) {
                        captureAiContextPlan(history, historyRequest.key, outcome.raw)
                    }
                    val planDiff = historyCompletion.baselines.firstOrNull()?.let {
                        ExplainPlanDiff.diff(it.raw, outcome.raw, outFormat)
                    }
                    // R61 兼容观察值；比较决策由 R90 的目标隔离会话负责。
                    lastStructuredExplain = explainSql to outcome.raw
                    var dialogRef: ExplainPlanDialog? = null
                    val view = ExplainPlanResultView(
                        dialectName = dialect.name,
                        dataSourceName = outcome.dataSourceName,
                        elapsedMs = outcome.elapsedMs,
                        raw = outcome.raw,
                        format = outFormat,
                        hints = hints,
                        onBackToConsole = {
                            dialogRef?.close(0)
                            submitExplainDirectConsole(proj, explainSql, dialectGateRequest)
                        },
                        diff = planDiff,
                        baselines = historyCompletion.baselines,
                        historyStatus = historyCompletion.saved,
                    )
                    val dialog = ExplainPlanDialog(proj, view)
                    dialogRef = dialog
                    showExplainDialog(dialog)
                    resultPanel.showIdle()
                },
                onFailed = { errorText ->
                    if (com.intellij.openapi.util.Disposer.isDisposed(this) || generation != explainStructuredGeneration) {
                        return@executeStructured
                    }
                    // T-ExplainPwdPrompt（2026-10-24）：失败分型出口——认证失败不再回退直送
                    // （直送同通道必同样失败，徒增一次红字对话框）。
                    // T-ExplainPwdPrompt W6（用户沙盒实测 bug）：AUTH **也不弹红字模态框**——
                    // askPassword=true 后平台凭据链已弹原生「Connecting to ... + Change
                    // Credentials / Abort」对话框（失败与两个出口都在那里），用户点 Abort 后
                    // 异常以 SQLException: Access denied 形态冒泡回来，再弹红字框是二次打断
                    // （用户报障原话：「点终止还是出现执行计划的弹框」）。AUTH 改轻通知收口，
                    // 补存密码指引随通知走。
                    when (cn.song.mybatisplus.visualizer.execute.classifyExplainFailure(errorText)) {
                        cn.song.mybatisplus.visualizer.execute.ExplainFailureKind.AUTH -> {
                            resultPanel.showIdle()
                            notifyExplainAuthFailure(proj)
                        }
                        // T-ExplainFailUx W1：选错源不弹模态、不回退直送（同通道必同样失败）——
                        // 就地横幅 + 「切换数据源」动作（换源后自动重试，W3）
                        cn.song.mybatisplus.visualizer.execute.ExplainFailureKind.WRONG_TARGET -> {
                            resultPanel.showIdle()
                            showExplainWrongTarget(
                                proj, errorText, explainSql, dialectGateRequest,
                                openConsole = { submitExplainDirectConsole(proj, explainSql, dialectGateRequest) },
                            )
                        }
                        cn.song.mybatisplus.visualizer.execute.ExplainFailureKind.CANCELLED -> {
                            resultPanel.showIdle()
                            notifyExplainCancelled(proj)
                        }
                        cn.song.mybatisplus.visualizer.execute.ExplainFailureKind.OTHER -> {
                            notifyExplainStructuredFallback(proj, null, detail = errorText)
                            submitExplainDirect(proj, explainSql, dialectGateRequest)
                        }
                    }
                },
            )
        }
    }
}

/** T-PreviewSideQuery P3a：从执行视图回到预览——移除头部并重渲染预览 SQL。 */
internal fun SqlPreviewPanel.backToPreview() {
    executeViewHeader?.let { consoleHost.remove(it) }
    executeViewHeader = null
    refreshView()
}

/** P3a：执行结果视图头部——明确当前不是预览编辑态，并提供显式「返回预览」。 */
internal fun SqlPreviewPanel.executeViewHeader(): JComponent =
    com.intellij.ui.components.JBPanel<com.intellij.ui.components.JBPanel<*>>(java.awt.BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 8)
        add(
            com.intellij.ui.components.JBLabel(MpSqlVisualizerBundle.message("panel.executeViewHint")).apply {
                foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
            },
            java.awt.BorderLayout.WEST,
        )
        add(
            primaryButton(MpSqlVisualizerBundle.message("panel.backToPreview")) { backToPreview() }
                .apply { font = buttonFont() },
            java.awt.BorderLayout.EAST,
        )
    }

/**
 * R33：结构化通道回退轻提示（fail-closed 姿态的一部分——落回直送通道并告知原因，
 * 不弹窗打断）。[reason] 非 null = 环境性回退（无数据源/无插件）；null = 执行失败，
 * [detail] 携带驱动错误文本。
 */

internal fun SqlPreviewPanel.notifyExplainStructuredFallback(
    proj: Project,
    reason: cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason?,
    detail: String?,
) {
    if (ApplicationManager.getApplication().isUnitTestMode) return
    runCatching {
        val why = when (reason) {
            cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.NO_DATA_SOURCE ->
                MpSqlVisualizerBundle.message("panel.explain.structured.reason.noDataSource")
            cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.NO_DATABASE_PLUGIN ->
                MpSqlVisualizerBundle.message("panel.explain.structured.reason.noDatabasePlugin")
            cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.UNFILLED_PARAMS ->
                MpSqlVisualizerBundle.message("panel.explain.structured.reason.unfilledParams")
            null -> detail ?: ""
        }
        Notification(
            NOTIFICATION_GROUP_ID,
            MpSqlVisualizerBundle.message("panel.explain"),
            MpSqlVisualizerBundle.message("panel.explain.structured.fallback", why),
            NotificationType.INFORMATION,
        ).notify(proj)
    }
}

/**
 * T-UI030：无已绑数据源时**直接复用既有数据源选择/准备链**（与执行 SQL/执行计划同一个
 * runner 选择器），只绑定、不执行任何 SQL；绑定落地后自动重试结构化 EXPLAIN。
 *
 * 为什么不走「通知 + 动作」：真机反馈里用户已经点了「执行计划」，这一刻缺的是数据源上下文
 * 本身——让用户再点一次通知动作只是把同一次决策拆成两步，且通知动作本身还要解释「点了会
 * 发生什么」。直接弹平台原生的 runner 选择器，用户当场选一个（或静默复用 last-used），
 * 选择即完成绑定、随后自动重试，路径最短且完全复用既有链（不新造选择 UI / runner）。
 *
 * 并发纪律：已有准备链在飞时**只等待**，不再走 chooseAndPrepare（预览准备是 PASS_THROUGH，
 * 会建第二条会话/控制台）。用户取消选择会把单飞位复位，轮询下一拍即收口为「不再弹第二次」。
 */
internal fun SqlPreviewPanel.promptDataSourceThenRetryStructured(
    proj: Project,
    dialect: cn.song.mybatisplus.visualizer.sql.Dialect,
    explainSql: String,
    dialectGateRequest: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request,
) {
    val errorShown = java.util.concurrent.atomic.AtomicBoolean(false)
    // 代际快照：等待绑定期间用户可能切预览/再次点执行计划（SqlPreviewPanelSqlView 会递增）——
    // 过期等待不得把旧 SQL 的 EXPLAIN 提交到新预览上。
    val generation = explainStructuredGeneration
    if (cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.shouldPromptForDataSource(
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.isPrepareInFlight(),
        )
    ) {
        // 绑定用的 SQL 取面板当前预览原文（不是包了 EXPLAIN 的文本）：只写进控制台文档，
        // 不执行；真正的 EXPLAIN 由下面的重试提交，用户 SQL 不被改写。
        val previewSql = nativeEditorDoc?.text?.takeIf { it.isNotBlank() }
            ?: editor?.document?.text?.takeIf { it.isNotBlank() }
            ?: explainSql
        // executeAfterPrepare=false = 只准备/绑定，不提交任何语句（DML/DDL 零自动执行）。
        // 不传 dialectGateRequest：绑定与方言无关，门禁在重试的结构化提交里照旧生效。
        cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prepareConsole(
            proj,
            previewSql,
            onError = { message ->
                errorShown.set(true)
                resultPanel.showError(message)
            },
        )
    }
    awaitDataSourceBoundThenRetry(proj, dialect, explainSql, dialectGateRequest, errorShown, generation)
}

/** 绑定等待轮询间隔（EDT；只读判定，不碰 JDBC）。 */
private const val EXPLAIN_DS_BIND_POLL_MS = 250

/** 轮询硬上限：120 拍 × 250ms = 30s（T-PerfSweep26-06 由 480 拍/2 分钟收紧）；只防定时器泄漏，正常收口由准备链终结（已绑定或取消）驱动。 */
private const val EXPLAIN_DS_BIND_MAX_TICKS = 120

/**
 * T-UI030：等待既有/新建准备链把数据源绑定落地，然后重试一次结构化 EXPLAIN。
 *
 * 判定全部委托 [cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.dataSourceBindTick]
 * （纯函数，取消/超时路径可单测）。收到取消/失败（单飞位复位）即停表并只给现有回退说明，
 * 绝不再弹选择器；重试路径传 `allowDataSourcePrompt=false`，即使仍未绑定也不会形成循环。
 */
internal fun SqlPreviewPanel.awaitDataSourceBoundThenRetry(
    proj: Project,
    dialect: cn.song.mybatisplus.visualizer.sql.Dialect,
    explainSql: String,
    dialectGateRequest: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request,
    errorShown: java.util.concurrent.atomic.AtomicBoolean,
    generation: Int,
) {
    awaitDataSourceBoundThen(
        proj,
        generation,
        onBound = {
            submitExplainOrStructured(
                proj, dialect, explainSql, dialectGateRequest, allowDataSourcePrompt = false,
            )
        },
        onGiveUp = {
            // 选择失败（无数据源/驱动错误）已在面板行内显示既有错误文案；只有「用户取消」
            // 这类没有错误文案的收口才补一条说明——两条提示不重复。
            if (!errorShown.get()) notifyExplainDataSourceNotBound(proj)
        },
    )
}

/**
 * 通用数据源绑定等待（T-UI030 同款）：RETRY → [onBound]；GIVE_UP → [onGiveUp]；
 * 预览代际变化/面板销毁 → 静默停表。调用方负责在 [onBound] 里禁止再次弹选择器。
 */
internal fun SqlPreviewPanel.awaitDataSourceBoundThen(
    proj: Project,
    generation: Int,
    onBound: () -> Unit,
    onGiveUp: () -> Unit,
) {
    var ticks = 0
    val timer = javax.swing.Timer(EXPLAIN_DS_BIND_POLL_MS) { event ->
        ticks++
        if (Disposer.isDisposed(this) || proj.isDisposed) {
            (event.source as javax.swing.Timer).stop()
            return@Timer
        }
        // 预览已切换/又点了一次相关动作：这次等待与重试都已过期，静默停表即可。
        if (generation != explainStructuredGeneration) {
            (event.source as javax.swing.Timer).stop()
            return@Timer
        }
        val bound = runCatching {
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSource()
        }.getOrNull() != null
        val inFlight = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.isPrepareInFlight()
        when (cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.dataSourceBindTick(
            bound = bound,
            prepareInFlight = inFlight,
            timeoutReached = ticks >= EXPLAIN_DS_BIND_MAX_TICKS,
        )) {
            cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.DataSourceBindTick.RETRY -> {
                (event.source as javax.swing.Timer).stop()
                onBound()
            }
            cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.DataSourceBindTick.GIVE_UP -> {
                (event.source as javax.swing.Timer).stop()
                onGiveUp()
            }
            cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.DataSourceBindTick.WAIT -> Unit
        }
    }
    timer.isRepeats = true
    timer.start()
}

/** T-UI030：用户取消数据源选择（或等待未绑定）后的说明——如实说明「未执行」，并给出下一步。 */
internal fun SqlPreviewPanel.notifyExplainDataSourceNotBound(proj: Project) {
    if (ApplicationManager.getApplication().isUnitTestMode) return
    runCatching {
        Notification(
            NOTIFICATION_GROUP_ID,
            MpSqlVisualizerBundle.message("panel.explain"),
            MpSqlVisualizerBundle.message("panel.explain.structured.noDataSourceDeclined"),
            NotificationType.INFORMATION,
        ).notify(proj)
    }
}

/**
 * T-UI030：开关关闭通知里的一个可操作项（文案 key + 点击行为）。
 *
 * 用「key + runnable」而非直接构造 [NotificationAction]（照
 * `SqlTemplateErrorAction` 先例）：让「挂了哪些动作、点了做什么」成为不依赖平台事件即可
 * 断言的纯逻辑，[NotificationAction] 的点击回调只能由真机/平台事件驱动。
 */
internal class StructuredExplainNotificationAction(val titleKey: String, val run: () -> Unit)

/** T-UI030：开关关闭通知的动作清单——恒给「启用结构化并重试」一项。 */
internal fun structuredExplainDisabledActions(
    onEnableAndRetry: () -> Unit,
): List<StructuredExplainNotificationAction> =
    listOf(
        StructuredExplainNotificationAction("panel.explain.structured.enableAndRetry", onEnableAndRetry),
    )

/**
 * T-UI030：结构化开关关闭时的可操作通知——带「启用结构化并重试」动作。
 * 动作由纯函数 [cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.shouldOfferStructuredEnable]
 * 决定是否值得出现（TEXT/XML/Community 不提示）；动作回调只写该开关并重试，不改其它设置。
 */
internal fun buildStructuredExplainDisabledNotification(onEnableAndRetry: () -> Unit): Notification {
    val notification = Notification(
        NOTIFICATION_GROUP_ID,
        MpSqlVisualizerBundle.message("panel.explain"),
        MpSqlVisualizerBundle.message("panel.explain.structured.disabled"),
        NotificationType.INFORMATION,
    )
    for (action in structuredExplainDisabledActions(onEnableAndRetry)) {
        notification.addAction(
            NotificationAction.createSimpleExpiring(MpSqlVisualizerBundle.message(action.titleKey)) { action.run() },
        )
    }
    return notification
}

internal fun SqlPreviewPanel.notifyStructuredExplainDisabled(proj: Project, onEnableAndRetry: () -> Unit) {
    if (ApplicationManager.getApplication().isUnitTestMode) return
    runCatching { buildStructuredExplainDisabledNotification(onEnableAndRetry).notify(proj) }
}

/**
 * T-UI030：「启用结构化并重试」动作——写设置（与设置页 apply 同一字段、同一变更主题）后
 * 立即重试一次 EXPLAIN。设置语义不变：默认仍关闭，只有用户显式点这个动作才打开。
 */
internal fun SqlPreviewPanel.enableStructuredExplainAndRetry(
    proj: Project,
    dialect: cn.song.mybatisplus.visualizer.sql.Dialect,
    explainSql: String,
    dialectGateRequest: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request,
) {
    if (Disposer.isDisposed(this) || proj.isDisposed) return
    PluginSettings.getInstance().state.explainStructuredRender = true
    ApplicationManager.getApplication().messageBus
        .syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
    submitExplainOrStructured(proj, dialect, explainSql, dialectGateRequest)
}

/**
 * T-ExplainChainHardening T-ECH-01（O-48）：被包语句不可 EXPLAIN 时的轻通知（同
 * [panel.explain.unsupported] 形态——纯告知无需打断，不新增弹窗）。文案给出**判定依据**
 * （实际首关键字）而不只是结论，用户据此能自己改语句。
 */

internal fun SqlPreviewPanel.notifyExplainNotExplainable(proj: com.intellij.openapi.project.Project, keyword: String?) {
    if (ApplicationManager.getApplication().isUnitTestMode) return
    runCatching {
        Notification(
            NOTIFICATION_GROUP_ID,
            MpSqlVisualizerBundle.message("panel.explain"),
            MpSqlVisualizerBundle.message(
                "panel.explain.notExplainable",
                keyword ?: MpSqlVisualizerBundle.message("panel.explain.notExplainable.unknown"),
            ),
            NotificationType.INFORMATION,
        ).notify(proj)
    }
}
