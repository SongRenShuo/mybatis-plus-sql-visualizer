// T-SyncK2JMaster482 波D2 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt
// （T-M53-II 2026-10-10：SqlPreviewPanel 的「预览执行链、EXPLAIN 双通道与控制台嵌入」内聚成员，
//   以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal）
// 扩展函数 fun SqlPreviewPanel.foo(...) → public static R foo(SqlPreviewPanel receiver, ...)。
// 自 master 同文件 R36/R42/R91/T-PreviewSideQuery/UI023/UI025/R46/O-145/T-UI030/T-DialectGateUx/
// T-DialectStatusLiveRefresh/T-DmlImpactProminence/R61/R90/P3a 增量移植。
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.execute.ConsoleEmbedMode;
import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate;
import cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo;
import cn.song.mybatisplus.visualizer.execute.ExecutionSubmission;
import cn.song.mybatisplus.visualizer.execute.SqlExecutor;
import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier;
import cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession;
import cn.song.mybatisplus.visualizer.history.ExplainPlanKey;
import cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot;
import cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult;
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager;
import cn.song.mybatisplus.visualizer.log.SqlExportFormat;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.sql.ExplainGate;
import cn.song.mybatisplus.visualizer.sql.ExplainSupport;
import cn.song.mybatisplus.visualizer.sql.MySqlDialect;
import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper;
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor;
import cn.song.mybatisplus.visualizer.util.EditorCommentShortcuts;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@code SqlPreviewPanelExecution.kt}（T-M53-II 拆分件）的 Java 承载类——扩展函数
 * {@code fun SqlPreviewPanel.foo(...)} 落为 {@code public static R foo(SqlPreviewPanel receiver, ...)}。
 *
 * <p>含预览执行链、EXPLAIN 双通道与控制台嵌入相关成员。</p>
 */
public final class SqlPreviewPanelExecutionKt {

    private SqlPreviewPanelExecutionKt() {
    }

    public static void showRollbackSql(@NotNull SqlPreviewPanel p) {
        String raw = previewSqlForReadonly(p);
        if (raw == null) return;
        String sql = SqlCommentStripper.strip(raw);
        if (sql.isBlank()) return;
        String kind = SqlTypeClassifier.getFirstKeyword(sql);
        if (kind == null) kind = "SQL";
        // T-ST-03 核实：本路径是【回滚SQL】按钮的独立动作（工具栏 :687），**不在执行确认流内**——
        // 此处 generate 是该次点击的唯一一次解析（确认流的解析点在 executeSql/executeAdhocSql 的
        // ExecuteConfirmMessages.build 处），故无「确认阶段产物」可显式传入。
        cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.RollbackResult result =
                cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.generate(sql);
        cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.SnapshotEntry entry =
                p.project != null
                        ? p.project.getService(cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.class)
                        .findBySql(sql)
                        : null;
        if (result.rating() == cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.Rating.UNABLE
                && result.snapshotSelectSql() == null) {
            Project proj = p.project;
            if (proj == null) return;
            StringBuilder reasons = new StringBuilder();
            for (int i = 0; i < result.warnings().size(); i++) {
                cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.RollbackWarning warning = result.warnings().get(i);
                if (i > 0) reasons.append("\n");
                if (warning.messageArgs().isEmpty()) {
                    reasons.append(MpSqlVisualizerBundle.message(warning.messageKey()));
                } else {
                    reasons.append(MpSqlVisualizerBundle.message(warning.messageKey(), warning.messageArgs().toArray()));
                }
            }
            Messages.showInfoMessage(
                    proj,
                    reasons.toString(),
                    MpSqlVisualizerBundle.message("rollback.dialog.title"));
            return;
        }
        new RollbackSqlDialog(p.project, p, result, kind, entry).show();
    }

    /**
     * R36（V46 定案）：回放脚本导出——当前预览（或经历史 recall 回落的）SQL（参数已填回）导出为
     * <b>可直接执行的单个 {@code .sql}</b>，方言适配取当前方言（语句本体已是生成链渲染产物，不改写），
     * 事务包裹可选（勾选则 {@code <方言显式事务开始语句>; … COMMIT;}；方言无该语句时选项禁用并说明）。
     *
     * <p>与 O-91 {@link #exportPortableSql} 的关系：入口/写盘/通知三处<b>照抄同款</b>（仓纪律「新增 UI 交互
     * 须照抄既有同类」），产物形态不同——O-91 是 CSV/TSV/TXT 行集，本件是单个 {@code .sql}。
     * 脱敏同 R60 口径（{@code displayMaskEnabled}，投影级）：开启时脚本字面量打码并在注释头标 {@code masked}。</p>
     */
    public static void exportReplayScript(@NotNull SqlPreviewPanel p) {
        String sql = previewSqlForReadonly(p);
        if (sql == null || sql.isBlank()) return;
        cn.song.mybatisplus.visualizer.sql.Dialect dialect =
                p.current != null && p.current.getEffectiveDialect() != null
                        ? p.current.getEffectiveDialect()
                        : MySqlDialect.INSTANCE;
        ReplayExportDialog options = new ReplayExportDialog(p.project, dialect.getName(), dialect.getTransactionBegin());
        if (!options.showAndGet()) return;
        boolean mask = PluginSettings.getInstance().getState().getDisplayMaskEnabled();
        cn.song.mybatisplus.visualizer.log.ReplayScriptExport.Plan plan =
                cn.song.mybatisplus.visualizer.log.ReplayScriptExport.plan(sql, dialect, options.isWrapTransaction());
        if (plan == null) return;
        com.intellij.openapi.fileChooser.FileSaverDescriptor descriptor =
                new com.intellij.openapi.fileChooser.FileSaverDescriptor(
                        MpSqlVisualizerBundle.message("replay.export.title"),
                        "SQL",
                        cn.song.mybatisplus.visualizer.log.ReplayScriptExport.EXT);
        com.intellij.openapi.fileChooser.FileSaverDialog dialog =
                com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
                        .createSaveFileDialog(descriptor, p.project);
        com.intellij.openapi.vfs.VirtualFileWrapper saved =
                dialog.save((com.intellij.openapi.vfs.VirtualFile) null, "replay.sql");
        if (saved == null) return;
        java.io.File target = saved.getFile();
        final cn.song.mybatisplus.visualizer.log.ReplayScriptExport.Plan planF = plan;
        final java.io.File targetF = target;
        final Project projectF = p.project;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                cn.song.mybatisplus.visualizer.log.SqlExportService.exportToFile(
                        targetF,
                        cn.song.mybatisplus.visualizer.log.ReplayScriptExport.render(
                                planF,
                                mask,
                                cn.song.mybatisplus.visualizer.log.SqlExportService.formatTime(System.currentTimeMillis())));
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(SqlPreviewPanel.NOTIFICATION_GROUP_ID)
                        .createNotification(
                                MpSqlVisualizerBundle.message("log.history.export.done",
                                        planF.statements().size(), targetF.getAbsolutePath()),
                                NotificationType.INFORMATION)
                        .notify(projectF);
            } catch (Throwable failure) {
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(SqlPreviewPanel.NOTIFICATION_GROUP_ID)
                        .createNotification(
                                MpSqlVisualizerBundle.message("log.history.export.failed",
                                        failure.getMessage() != null ? failure.getMessage() : ""),
                                NotificationType.ERROR)
                        .notify(projectF);
            }
        });
    }

    /** T-PreviewSideQuery P1 测试缝：headless 无原生控制台绑定；生产为 null 走真链。 */
    public static volatile java.util.function.Supplier<Object> dmlImpactTargetForTest = null;

    /** T-PreviewSideQuery P1 测试缝：数据源选择器绑定调用（只绑定不执行）。 */
    public interface DmlImpactPromptForTest {
        void prompt(Project project, String sql, Consumer<String> onError);
    }

    public static volatile DmlImpactPromptForTest dmlImpactPromptForTest = null;

    /** T-PreviewSideQuery P1 测试缝：绑定等待（生产走 awaitDataSourceBoundThen）。 */
    public interface AwaitBindForTest {
        void await(Project project, int generation, Runnable onBound, Runnable onGiveUp);
    }

    public static volatile AwaitBindForTest dmlImpactAwaitBindForTest = null;

    /** T-PreviewSideQuery P2 测试缝：headless 无原生控制台绑定；生产为 null 走真链。 */
    public static volatile java.util.function.Supplier<Object> explainTargetForTest = null;

    /** T-PreviewSideQuery P2 测试缝：结构化通道的绑定数据源。 */
    public static volatile java.util.function.Supplier<Object> explainBoundDsForTest = null;

    /** T-PreviewSideQuery P2 测试缝：headless 下 Database 插件可用性。 */
    public static volatile java.util.function.Supplier<Boolean> explainDatabaseAvailableForTest = null;

    /**
     * R42：DML 影响面预演。
     *
     * <p>把当前 UPDATE/DELETE 的 WHERE 谓词改写为等价只读 {@code COUNT(*)} 试算语句（改写边界与
     * 成本防护见 {@code cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator}），经<b>只读旁路
     * 查询</b>（{@link PreviewSideQuery}）对当前绑定数据源执行——不进入共享控制台、不替换预览编辑器。
     * 不适用形态（JOIN/多表/无 WHERE/多语句）走轻通知如实说明原因，不执行。</p>
     */
    public static void estimateDmlImpact(@NotNull SqlPreviewPanel p, boolean allowDataSourcePrompt) {
        Project proj = p.project;
        if (proj == null) return;
        String sql = previewSqlForReadonly(p);
        if (sql == null) return;
        cn.song.mybatisplus.visualizer.sql.Dialect dialect =
                p.current != null && p.current.getEffectiveDialect() != null
                        ? p.current.getEffectiveDialect()
                        : MySqlDialect.INSTANCE;
        cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator.Plan plan =
                cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator.plan(sql, dialect);
        if (plan instanceof cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator.NotApplicable notApplicable) {
            notifyDmlImpact(p, proj,
                    MpSqlVisualizerBundle.message("dml.impact.notApplicable.title"),
                    MpSqlVisualizerBundle.message(notApplicable.reasonKey()));
            // 覆盖上一次的内联结果，避免 SQL 已不可试算时仍显示旧行数。
            SqlPreviewPanelParamPanelKt.showDmlImpactResult(p,MpSqlVisualizerBundle.message(notApplicable.reasonKey()), true, null);
            return;
        }
        if (!(plan instanceof cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator.Applicable applicable)) {
            return;
        }
        cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator.Probe probe = applicable.probe();
        ExecutionDialectGate.Request dialectGateRequest = new ExecutionDialectGate.Request(
                p.executionDialectRenderMode,
                dialect.getName(),
                ExecutionDialectGate.Intent.QUERY,
                // COUNT 试算按当前方言生成，目标不一致时 fail-closed；不把另一语法族的 COUNT 送进目标库。
                false,
                true,
                null);
        String capLine = probe.rowCap() != null
                ? MpSqlVisualizerBundle.message("dml.impact.probe.capped", probe.rowCap())
                : MpSqlVisualizerBundle.message("dml.impact.probe.uncapped");
        String probeBody = MpSqlVisualizerBundle.message(
                "dml.impact.probe.body",
                probe.probeSql(),
                probe.targetTable(),
                capLine);
        // T-DmlImpactProminence（用户真机反馈）：不再发「试算语句/目标表」预演通知——
        // 气泡讲的是探针而非结果，与内联/横幅结果并存时既冗余又易被误读。
        // 探针详情保留在结果横幅的 tooltip（probeBody）里，需要时悬停可见。
        Object target;
        if (dmlImpactTargetForTest != null) {
            target = dmlImpactTargetForTest.get();
        } else {
            try {
                target = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSource();
            } catch (Throwable t) {
                target = null;
            }
        }
        if (target == null) {
            if (allowDataSourcePrompt) {
                promptDataSourceThenRetryImpact(p, proj, sql);
            } else {
                SqlPreviewPanelParamPanelKt.showDmlImpactResult(p,MpSqlVisualizerBundle.message("dml.impact.noDataSource"), true, null);
            }
            return;
        }
        SqlPreviewPanelParamPanelKt.showDmlImpactResult(p,MpSqlVisualizerBundle.message("dml.impact.running"), false, null);
        PreviewSideQuery.run(proj, target, probe.probeSql(), dialectGateRequest, true, (result -> {
            if (result instanceof PreviewSideQuery.Scalar scalar) {
                Long cap = probe.rowCap();
                String text = cap != null
                        ? MpSqlVisualizerBundle.message("dml.impact.inline.capped",
                                Math.min(scalar.value(), cap))
                        : MpSqlVisualizerBundle.message("dml.impact.inline", scalar.value());
                SqlPreviewPanelParamPanelKt.showDmlImpactResult(p,text, false, probeBody);
            } else if (result instanceof PreviewSideQuery.Blocked blocked) {
                SqlPreviewPanelParamPanelKt.showDmlImpactResult(p,blocked.message(), true, null);
            } else if (result instanceof PreviewSideQuery.Failed failed) {
                SqlPreviewPanelParamPanelKt.showDmlImpactResult(p,
                        MpSqlVisualizerBundle.message("dml.impact.inline.error", failed.message()), true, null);
            } else if (result instanceof PreviewSideQuery.Table) {
                SqlPreviewPanelParamPanelKt.showDmlImpactResult(p,
                        MpSqlVisualizerBundle.message("dml.impact.inline.error", "unexpected tabular result"),
                        true, null);
            }
        }));
    }

    /**
     * T-PreviewSideQuery P1：无绑定数据源时复用既有数据源选择器（只绑定不执行），
     * 绑定落地后重试一次预估；取消/超时只就地提示，不再二次弹选择器。
     */
    /** Kotlin 默认参数 {@code allowDataSourcePrompt = true} 的等价重载。 */
    public static void estimateDmlImpact(@NotNull SqlPreviewPanel p) {
        estimateDmlImpact(p, true);
    }

    public static void promptDataSourceThenRetryImpact(@NotNull SqlPreviewPanel p,
                                                       @NotNull Project proj, @NotNull String previewSql) {
        SqlPreviewPanelParamPanelKt.showDmlImpactResult(p,MpSqlVisualizerBundle.message("dml.impact.binding"), false, null);
        int generation = p.explainStructuredGeneration;
        AtomicBoolean errorShown = new AtomicBoolean(false);
        if (cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.shouldPromptForDataSource(
                cn.song.mybatisplus.visualizer.execute.DataSourceSelector.isPrepareInFlight())) {
            // executeAfterPrepare=false：只准备/绑定，不提交任何语句（DML/DDL 零自动执行）。
            DmlImpactPromptForTest promptForTest = dmlImpactPromptForTest;
            if (promptForTest != null) {
                promptForTest.prompt(proj, previewSql, message -> {
                    errorShown.set(true);
                    SqlPreviewPanelParamPanelKt.showDmlImpactResult(p,message, true, null);
                });
            } else {
                cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prepareConsole(
                        proj, previewSql, null, null,
                        message -> {
                            errorShown.set(true);
                            SqlPreviewPanelParamPanelKt.showDmlImpactResult(p,message, true, null);
                        },
                        null, null, null, false);
            }
        }
        AwaitBindForTest awaitForTest = dmlImpactAwaitBindForTest;
        if (awaitForTest != null) {
            awaitForTest.await(proj, generation,
                    () -> estimateDmlImpact(p, false),
                    () -> {
                        if (!errorShown.get()) {
                            SqlPreviewPanelParamPanelKt.showDmlImpactResult(p,MpSqlVisualizerBundle.message("dml.impact.noDataSource"), true, null);
                        }
                    });
        } else {
            awaitDataSourceBoundThen(p, proj, generation,
                    () -> estimateDmlImpact(p, false),
                    () -> {
                        if (!errorShown.get()) {
                            SqlPreviewPanelParamPanelKt.showDmlImpactResult(p,MpSqlVisualizerBundle.message("dml.impact.noDataSource"), true, null);
                        }
                    });
        }
    }

    /** R42：影响面预演的轻通知（非模态；单测模式不弹）。 */
    private static void notifyDmlImpact(@NotNull SqlPreviewPanel p, @NotNull Project proj,
                                        @NotNull String title, @NotNull String body) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            new Notification(SqlPreviewPanel.NOTIFICATION_GROUP_ID, title, body,
                    NotificationType.INFORMATION).notify(proj);
        } catch (Throwable ignored) {
        }
    }

    /** UI023：仅观察最终通知对象，不替代校验、渲染或执行分支。 */
    public static volatile Consumer<Notification> schemaNotificationForTest = null;

    /**
     * R46：查询链的未识别对象轻通知（非模态、不阻断执行——与 DML 确认弹窗同源同文案）。
     * 无发现或单测模式不弹（弹窗/通知克制红线；真机才提示）。
     */
    private static void notifySchemaIssues(
            @NotNull SqlPreviewPanel p,
            @NotNull Project proj,
            @NotNull List<cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator.Issue> issues) {
        String body = cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.renderNotification(issues);
        if (body == null) return;
        if (ApplicationManager.getApplication().isUnitTestMode() && schemaNotificationForTest == null) return;
        try {
            Notification notification = new Notification(
                    SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("schema.check.notification.title"),
                    body,
                    NotificationType.WARNING);
            Consumer<Notification> observer = schemaNotificationForTest;
            if (observer != null) observer.accept(notification);
            else notification.notify(proj);
        } catch (Throwable ignored) {
        }
    }

    /**
     * O-91（缩范围首版）：当前预览的 CRUD SQL + 回滚链产物串成可移植脚本导出。
     *
     * <p>语句本体不改写（生成侧方言降级链已保证「标准语法优先」），导出只串联＋标注：
     * 每条语句前 {@code -- dialect: X | kind: CRUD/ROLLBACK | upsert: 形态或 no} 注释头；
     * 格式走 R4 既有三格式通道（{@code SqlExportService.formatPortable}）。
     * 回滚链诚实拒绝（UPDATE/DELETE 无 before-image、UPSERT 三形态等）时导出面不造回滚行
     * （与 {@link #showRollbackSql} 的 UNABLE 弹说明同口径，不降级造半成品）。
     * 写盘在后台线程（对齐 SqlLogPanel R4 导出流程）。</p>
     */
    public static void exportPortableSql(@NotNull SqlPreviewPanel p) {
        String raw = previewSqlForReadonly(p);
        if (raw == null) return;
        String sql = SqlCommentStripper.strip(raw);
        if (sql.isBlank()) return;
        cn.song.mybatisplus.visualizer.sql.Dialect dialect =
                (p.current != null && p.current.getEffectiveDialect() != null)
                        ? p.current.getEffectiveDialect()
                        : MySqlDialect.INSTANCE;
        List<cn.song.mybatisplus.visualizer.log.PortableSqlRow> rows =
                cn.song.mybatisplus.visualizer.log.PortableSqlExport.collectRows(
                        sql, dialect, p.current != null ? p.current.getBatchModel() : null);
        if (rows.isEmpty()) return;
        SqlExportFormat format = choosePortableExportFormat(p);
        if (format == null) return;
        com.intellij.openapi.fileChooser.FileSaverDescriptor descriptor =
                new com.intellij.openapi.fileChooser.FileSaverDescriptor(
                        MpSqlVisualizerBundle.message("panel.portableExport"),
                        "SQL (" + format.getExt().toUpperCase(java.util.Locale.ROOT) + ")",
                        format.getExt());
        com.intellij.openapi.fileChooser.FileSaverDialog dialog =
                com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
                        .createSaveFileDialog(descriptor, p.project);
        com.intellij.openapi.vfs.VirtualFileWrapper saved =
                dialog.save((com.intellij.openapi.vfs.VirtualFile) null, "sql-portable." + format.getExt());
        if (saved == null) return;
        java.io.File target = saved.getFile();
        final List<cn.song.mybatisplus.visualizer.log.PortableSqlRow> rowsFinal = rows;
        final SqlExportFormat formatFinal = format;
        final Project projectFinal = p.project;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                cn.song.mybatisplus.visualizer.log.SqlExportService.exportToFile(
                        target,
                        // R60（T-LaneB D96）：可移植导出同脱敏口径（displayMaskEnabled，投影级）
                        cn.song.mybatisplus.visualizer.log.SqlExportService.formatPortable(
                                rowsFinal,
                                formatFinal,
                                PluginSettings.getInstance().getState().getDisplayMaskEnabled()));
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(SqlPreviewPanel.NOTIFICATION_GROUP_ID)
                        .createNotification(
                                MpSqlVisualizerBundle.message("log.history.export.done", rowsFinal.size(), target.getAbsolutePath()),
                                NotificationType.INFORMATION)
                        .notify(projectFinal);
            } catch (Throwable it) {
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(SqlPreviewPanel.NOTIFICATION_GROUP_ID)
                        .createNotification(
                                MpSqlVisualizerBundle.message("log.history.export.failed", it.getMessage() != null ? it.getMessage() : ""),
                                NotificationType.ERROR)
                        .notify(projectFinal);
            }
        });
    }

    /** O-91：可移植导出格式三选（取消返回 null；形态对齐 SqlLogPanel R4 同款对话框）。 */
    @Nullable
    public static SqlExportFormat choosePortableExportFormat(@NotNull SqlPreviewPanel p) {
        String[] names = {"CSV", "TSV", "TXT"};
        int idx = Messages.showChooseDialog(
                p.project,
                MpSqlVisualizerBundle.message("log.history.export.choose"),
                MpSqlVisualizerBundle.message("panel.portableExport"),
                Messages.getQuestionIcon(),
                names,
                names[0]);
        if (idx < 0) return null;
        if (idx == 0) return SqlExportFormat.CSV;
        if (idx == 1) return SqlExportFormat.TSV;
        return SqlExportFormat.TXT;
    }

    /**
     * R91（master ed5005d4 对齐）：AI 上下文复制——只读当前预览/编辑器与已获取历史，
     * 绝不新取计划或 schema（v2 投影：字面量遮蔽 + 标识符可选 + 缺失段标注）。
     * （全参形态在前：接线守卫按首个声明取函数体。）
     */
    public static void copyAsAiContext(@NotNull SqlPreviewPanel p, boolean includeIdentifiers) {
        String sql = previewSqlForReadonly(p);
        if (sql == null) return;
        PreviewResult preview = p.current;
        cn.song.mybatisplus.visualizer.sql.Dialect dialect =
                preview != null && preview.getEffectiveDialect() != null ? preview.getEffectiveDialect() : null;
        SqlPreviewPanel.AiContextTarget target = p.aiContextTarget;
        if (target != null && !(target.preview() == preview && stillCurrentSafe(target))) {
            target = null;
        }
        ExplainGate.SubmitPlan.Submit wrapped = null;
        if (dialect != null) {
            ExplainGate.SubmitPlan plan = ExplainGate.planExplainSubmit(dialect, sql);
            if (plan instanceof ExplainGate.SubmitPlan.Submit submit) wrapped = submit;
        }
        cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat format =
                dialect != null ? ExplainSupport.formatFor(dialect.getName()) : null;
        cn.song.mybatisplus.visualizer.history.ExplainPlanKey key = null;
        if (target != null && dialect != null && wrapped != null && format != null) {
            cn.song.mybatisplus.visualizer.history.ExplainPlanKey candidate =
                    cn.song.mybatisplus.visualizer.history.ExplainPlanKey.create(
                            cn.song.mybatisplus.visualizer.history.ExplainPlanKey.projectIdentity(
                                    p.project != null ? p.project.getBasePath() : null),
                            target.key().dataSourceUuid(), dialect.getName(), format, wrapped.statement());
            if (candidate.equals(target.key())) key = candidate;
        }
        List<cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot> plans = List.of();
        if (key != null) {
            cn.song.mybatisplus.visualizer.history.SqlHistoryManager history =
                    cn.song.mybatisplus.visualizer.history.SqlHistoryManager.getInstance();
            List<cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot> matched =
                    new ArrayList<>(history.matchingExplainPlans(key));
            kotlin.Pair<Long, cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot> acquired = p.aiContextPlan;
            if (acquired != null && acquired.getFirst() == history.explainPlanGeneration()
                    && acquired.getSecond().key().equals(key)) {
                matched.add(acquired.getSecond());
            }
            plans = matched;
        }
        // A manually edited SQL cannot borrow schema facts from the previous preview.
        cn.song.mybatisplus.visualizer.util.AiContextExport.StaticSchema schema = null;
        if (preview != null && (sql.equals(preview.getSql()) || sql.equals(p.lastRenderedSql))) {
            String table = preview.getTableName();
            if (table != null) {
                schema = new cn.song.mybatisplus.visualizer.util.AiContextExport.StaticSchema(table, preview.getColumns());
            }
        }
        cn.song.mybatisplus.visualizer.util.AiContextExport.Result result =
                cn.song.mybatisplus.visualizer.util.AiContextExport.buildV2(
                        sql, dialect != null ? dialect.getName() : null, key, plans, schema, includeIdentifiers);
        cn.song.mybatisplus.visualizer.util.UiTextClipboard.copy(result.block());
        String toastKey = includeIdentifiers ? "ai.context.v2.copied.identifiers" : "ai.context.v2.copied";
        SqlPreviewPanelSqlViewKt.showDiffToast(p,
                MpSqlVisualizerBundle.message(toastKey, result.maskedCount()));
    }

    private static boolean stillCurrentSafe(@NotNull SqlPreviewPanel.AiContextTarget target) {
        try {
            Boolean v = target.stillCurrent().get();
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Kotlin 默认参数 {@code includeIdentifiers = false} 的等价重载。 */
    public static void copyAsAiContext(@NotNull SqlPreviewPanel p) {
        copyAsAiContext(p, false);
    }

    public static ExecutionDialectGate.Request executionDialectGateRequest(
            @NotNull SqlPreviewPanel p, @NotNull ExecutionDialectGate.Intent intent) {
        PreviewResult result = p.current;
        String currentText = currentEditorSql(p);
        boolean untouched = currentText != null && currentText.equals(p.lastRenderedSql);
        // 只有模型/批量（CRUD）载体能按新方言从结构重新生成；XML/纯文本与分页后处理缺重建载体，
        // 宁可报 RERENDER_UNAVAILABLE，也不做字符串替换冒充无损。
        boolean canRerender = result != null && result.getXmlModel() == null
                && result.getPaginationLimitLine() == null
                && (result.getPreviewModel() != null || result.getBatchModel() != null);
        return new ExecutionDialectGate.Request(
                p.executionDialectRenderMode,
                // T-ExplainSwitchNative W10（2026-10-24 用户实测「为什么 sql 无法执行」——红条：
                // 「当前 SQL 按 **未知** 渲染，所选数据源是 MySQL」）：
                // {@code effectiveDialect} 为 null 只发生在「渲染链没记方言」的载体（如 JPA/日志还原等
                // renderedDialect 未赋值的链）。而渲染器 {@code DialectRegistry.byName(null)} 的既有语义是
                // <b>回落 MySQL</b>（Dialect.kt:908-909）——即实际渲染方言从来不是「未知」。此处按同一口径
                // 补回落，避免闸门把「渲染器自己都没当成未知」的形态判成 RENDER_UNKNOWN_BLOCK 阻断执行。
                result != null && result.getEffectiveDialect() != null
                        ? result.getEffectiveDialect().getName()
                        : MySqlDialect.INSTANCE.getName(),
                intent,
                canRerender,
                untouched,
                p.sourceModuleKey);
    }

    public static void onExecutionDialectGate(
            @NotNull SqlPreviewPanel p, @NotNull ExecutionDialectGate.Verdict verdict) {
        switch (verdict.status()) {
            case MATCH, TARGET_UNKNOWN_QUERY_CONFIRM, RENDER_UNKNOWN_QUERY_CONFIRM -> {
            }
            case AUTO_RERENDER_REQUIRED -> {
                String target = verdict.targetDialectName();
                if (target != null && rerenderForExecutionTarget(p, target)) {
                    p.resultPanel.showStatus(cn.song.mybatisplus.visualizer.execute.DataSourceSelector.dialectGateMessage(verdict));
                } else {
                    showDialectGateError(p, new ExecutionDialectGate.Verdict(
                            ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
                            verdict.renderedDialectName(), verdict.targetDialectName()));
                }
            }
            // T-DialectGatePass（2026-10-26 语义对齐，对齐 commit 8619a3af 用户定案「方言不能影响
            // 最终的执行」）：这两态在 allowDialectGate 侧本就放行（返回 true），预览入口同步去掉
            // 阻断呈现——提示照旧报出双方方言不一致，但不再用「错误+动作」红条阻断，改走
            // showStatus 信息态；执行继续与 MATCH 分支同一路径（本回调只管呈现，继续与否由
            // allowDialectGate 的返回值决定，这里不拦截即放行）。
            // 时序坑（同 showDialectGateError 注释）：闸门在同一次调用栈还会经旧 onError 通道再投
            // 一次纯文案（DataSourceSelector.allowDialectGate 先 onDialectGate 再 onError），同步
            // showStatus 会被紧随其后的 showError 覆盖——把最终呈现放到下一拍 EDT，两条通道最终
            // 只留一条提示（若执行随后真报错，真实错误仍会覆盖这条状态，时序上真实错误晚于本拍）。
            case MANUAL_CONFLICT, RERENDER_UNAVAILABLE -> {
                String message = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.dialectGateMessage(verdict);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (p.previewDisposed) return;
                    p.resultPanel.showStatus(message);
                });
            }
            default -> p.resultPanel.showError(
                    cn.song.mybatisplus.visualizer.execute.DataSourceSelector.dialectGateMessage(verdict));
        }
    }

    /**
     * T-DialectGateUx：阻断态错误 + 两个就地出口。
     *
     * <ul>
     *  <li>「切换数据源」→ {@link #executeSql} 带 forceChoose 重开选择器（跳过已准备会话）；</li>
     *  <li>「打开 SQL 方言设置」→ 平台设置页（EP id = "SQL Dialects"，按 id 取避免整棵设置树实例化）。</li>
     * </ul>
     *
     * <p>文案只解释原因，动作直接通向修复点；用户不必自己翻设置或文档。</p>
     *
     * <p>T-DialectGatePass（2026-10-26）：MANUAL_CONFLICT / RERENDER_UNAVAILABLE 已改为放行
     * （见 {@link #onExecutionDialectGate}），不再走本函数——<b>保留不删</b>（后续可能复用），当前唯一
     * 生产调用方是 AUTO_RERENDER_REQUIRED 分支的「重渲染失败回落 RERENDER_UNAVAILABLE」路径
     * （该态仍是阻断语义，红条+就地出口照旧）。</p>
     */
    public static void showDialectGateError(@NotNull SqlPreviewPanel p, @NotNull ExecutionDialectGate.Verdict verdict) {
        Project proj = p.project;
        String message = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.dialectGateMessage(verdict);
        if (proj == null) {
            p.resultPanel.showError(message);
            return;
        }
        boolean settingsAvailable;
        try {
            settingsAvailable = findSqlDialectConfigurable(proj) != null;
        } catch (Throwable t) {
            settingsAvailable = false;
        }
        // T-DialectGateUx：闸门在**同一次调用栈**里还会经旧 onError 通道再投一次纯文案
        // （DataSourceSelector.allowDialectGate 先 onDialectGate 再 onError），后者会把刚挂上的
        // 动作清掉（沙盒实证：红条只有复制、没有两个入口）。把带动作的最终呈现放到下一拍 EDT，
        // 保证它是这次阻断的最终状态。
        final Project projF = proj;
        final boolean settingsAvailableF = settingsAvailable;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (p.previewDisposed) return;
            p.resultPanel.showErrorWithActions(
                    message,
                    MpSqlVisualizerBundle.message("execute.dialectGate.action.switchDataSource"),
                    MpSqlVisualizerBundle.message("execute.dialectGate.action.switchDataSource.tip"),
                    // T-ExplainSwitchNative W12（2026-10-24 用户实测三图定案）：换源一律走**平台原生
                    // 工具栏的 Data Sources 菜单**（图三：Detach Data Source + 全部源）——插件侧自建
                    // 入口做不到与原生一致（平台 forced 关联无公开清除 API，自建列表第二次起会被短路成
                    // 单源）。此处动作改为把用户送到能换源的地方：打开 Database 工具窗口。
                    () -> cn.song.mybatisplus.visualizer.execute.DataSourceSelector.openDatabaseToolWindow(projF),
                    settingsAvailableF
                            ? MpSqlVisualizerBundle.message("execute.dialectGate.action.openSettings")
                            : null,
                    MpSqlVisualizerBundle.message("execute.dialectGate.action.openSettings.tip"),
                    () -> openSqlDialectSettings(projF));
        });
    }

    /** 方言设置页的 Configurable EP id（Database 插件注册，见 SqlPlugin.xml）。 */
    public static final String SQL_DIALECTS_SETTING_ID = "SQL Dialects";

    /** T-DialectGateUx：打开「设置 → 语言与框架 → SQL 方言」页并选中该页。 */
    public static void openSqlDialectSettings(@NotNull Project proj) {
        // 平台自带的「按 id 打开并选中」入口：它在**同一个** ConfigurableGroup 实例里查找目标
        // configurable 再建对话框。先 findById 再把 wrapper 交给 showSettingsDialog(project, Configurable)
        // 会因两次 group 实例不同而选不中——沙盒实证只打开到设置根页。
        boolean opened;
        try {
            com.intellij.ide.actions.ShowSettingsUtilImpl.showSettingsDialog(proj, SQL_DIALECTS_SETTING_ID, null);
            opened = true;
        } catch (Throwable t) {
            opened = false;
        }
        if (!opened) {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(proj, SQL_DIALECTS_SETTING_ID);
        }
    }

    /**
     * 按 configurable EP <b>id</b> 取方言设置页，不实例化整棵设置树。
     *
     * <p>{@code Settings.find(Class)} / {@code showSettingsDialog(project, Class)} 会
     * {@code ConfigurableVisitor.findByType} 遍历并实例化全部 configurable（Kotlin scripting 页构造器会做
     * 文件名索引 → 真机 EDT 慢操作断言）；{@code findById} 只包一层 wrapper，页面本身在展示时才实例化。</p>
     */
    @Nullable
    private static com.intellij.openapi.options.Configurable findSqlDialectConfigurable(@NotNull Project proj) {
        try {
            com.intellij.openapi.options.ConfigurableGroup group =
                    com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil.getConfigurableGroup(proj, true);
            return com.intellij.openapi.options.ex.ConfigurableVisitor.findById(SQL_DIALECTS_SETTING_ID,
                    java.util.List.of(group));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 模型/批量 CRUD 载体的无损目标方言重渲染；成功只改预览，不提交当前点击。 */
    public static boolean rerenderForExecutionTarget(@NotNull SqlPreviewPanel p, @NotNull String targetDialectName) {
        PreviewResult old = p.current;
        if (old == null) return false;
        cn.song.mybatisplus.visualizer.sql.Dialect target = null;
        for (cn.song.mybatisplus.visualizer.sql.Dialect d : DialectRegistry.INSTANCE.getDialects()) {
            if (d.getName().equalsIgnoreCase(targetDialectName)) {
                target = d;
                break;
            }
        }
        if (target == null) return false;
        PreviewResult updated;
        if (old.getXmlModel() != null || old.getPaginationLimitLine() != null) {
            return false;
        } else if (old.getPreviewModel() != null) {
            updated = new PreviewResult(
                    old.getSql(), old.getCompactSql(), old.getUnresolvedParams(), old.getParamTable(),
                    old.getWrapperType(), old.getTableName(), old.getWarning(), old.getColumns(),
                    old.getColumnComments(), old.getTableComment(),
                    old.getPreviewModel().withDialect(target),
                    old.getParamHints(), old.getCandidateTables(), old.getBatchModel(), old.getXmlModel(),
                    old.getInitialParamValues(), old.getPerfWarnings(), old.getFillClauses(), old.getPreviousSql(),
                    old.getPagesColumns(), old.getNamedParamHints(), old.getStackDigest(),
                    old.isHasDollarRawSegments(), old.getCountSql(), old.getPaginationLimitLine(),
                    old.getPlaceholderParamNames(), old.getDuplicateParamNames(), old.getDiagramStatement(),
                    old.getHistoryFingerprintSeed(), old.getPaginationParamSources(),
                    target, old.getDialectDecision(), old.getHistoryEntryId(), old.getActualSql());
        } else if (old.getBatchModel() != null) {
            updated = new PreviewResult(
                    old.getSql(), old.getCompactSql(), old.getUnresolvedParams(), old.getParamTable(),
                    old.getWrapperType(), old.getTableName(), old.getWarning(), old.getColumns(),
                    old.getColumnComments(), old.getTableComment(), old.getPreviewModel(),
                    old.getParamHints(), old.getCandidateTables(), old.getBatchModel().withDialect(target),
                    old.getXmlModel(), old.getInitialParamValues(), old.getPerfWarnings(), old.getFillClauses(),
                    old.getPreviousSql(), old.getPagesColumns(), old.getNamedParamHints(), old.getStackDigest(),
                    old.isHasDollarRawSegments(), old.getCountSql(), old.getPaginationLimitLine(),
                    old.getPlaceholderParamNames(), old.getDuplicateParamNames(), old.getDiagramStatement(),
                    old.getHistoryFingerprintSeed(), old.getPaginationParamSources(),
                    target, old.getDialectDecision(), old.getHistoryEntryId(), old.getActualSql());
        } else {
            return false;
        }
        // R92: this is the same preview with a new dialect, not a fresh parameter session.
        // Retain ownership of existing controls; storage-generation invalidation still applies.
        if (p.fieldFillPreview == old) p.fieldFillPreview = updated;
        p.current = updated;
        // T-DialectStatusLiveRefresh：目标方言已被方言闸证实，决策同步升级——meta 行否则停留在
        // 旧的「未验证/歧义」态与实际渲染方言自相矛盾（copy() 不会带上新决策）。
        p.currentDialectDecision = new DialectDecisionEngine.Decision(
                DialectDecisionEngine.Status.RESOLVED,
                target.getName(),
                DialectDecisionEngine.Confidence.EXACT,
                DialectDecisionEngine.Source.BOUND_DATASOURCE,
                List.of(), List.of(), null);
        SqlPreviewPanelEditorLifecycleKt.syncPreviewDialectMapping(p, updated);
        SqlPreviewPanelSqlViewKt.renderSql(p);
        DialectStatusRefreshKt.applyDialectStatusSegment(p);
        Project proj = p.project;
        String sql = p.lastRenderedSql;
        if (proj != null && sql != null && !sql.isBlank()) {
            final Project projF = proj;
            final String sqlF = sql;
            final String targetNameF = target.getName();
            ApplicationManager.getApplication().executeOnPooledThread(() ->
                    cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prewarmExecTempFile(
                            projF, sqlF, targetNameF));
        }
        return true;
    }

    /**
     * T-RollbackSql F4：临时 SQL 执行（回滚弹窗专用）——复用 {@link #executeSql} 的既有管线
     * （分类 → DML 二次确认 → isPrepared 快路径/全链），T-RefreshDbSteal F4 所有权卫队
     * 对这条路径天然生效；执行完毕结果反馈走既有 resultPanel。
     *
     * <p>刻意不走 {@code SqlExecutor.execute} 从弹窗直调全链：全链建临时 .sql 并 attachConsole，
     * 可能把正嵌在预览面板里的共享控制台偷走。</p>
     *
     * @param dmlConfirmed    DML 二次确认豁免（回滚弹窗「执行回滚」已过用户显式确认链）
     * @param rollbackOfEntry 本次执行本身是回滚语句时指向被回滚的原快照条目（F6 留痕链）
     */
    /** Kotlin 默认参数等价重载：dmlConfirmed=false、rollbackOfEntry=null、onSubmission=null。 */
    public static void executeAdhocSql(@NotNull SqlPreviewPanel p, @NotNull String sql) {
        executeAdhocSql(p, sql, false, null);
    }

    /** Kotlin 默认参数等价重载：rollbackOfEntry=null、onSubmission=null。 */
    public static void executeAdhocSql(@NotNull SqlPreviewPanel p, @NotNull String sql, boolean dmlConfirmed) {
        executeAdhocSql(p, sql, dmlConfirmed, null);
    }

    /** Kotlin 默认参数等价重载：onSubmission=null。 */
    public static void executeAdhocSql(
            @NotNull SqlPreviewPanel p,
            @NotNull String sql,
            boolean dmlConfirmed,
            @Nullable cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.SnapshotEntry rollbackOfEntry) {
        executeAdhocSql(p, sql, dmlConfirmed, rollbackOfEntry, null);
    }

    /**
     * T-RollbackSql F4（onSubmission 回执形态，master 对齐）：每次请求仅结算一次——
     * 回执经 {@link AtomicBoolean} 单发，上层（RollbackSqlDialog.rollbackSubmissionReceipt）
     * 据此驱动 F6 留痕（SUBMITTED 才 markRollbackExecuted）与横幅更新；早退/取消
     * 分别回 BLOCKED/CANCELLED，与 master 语义一致。
     */
    public static void executeAdhocSql(
            @NotNull SqlPreviewPanel p,
            @NotNull String sql,
            boolean dmlConfirmed,
            @Nullable cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.SnapshotEntry rollbackOfEntry,
            @Nullable Consumer<ExecutionSubmission> onSubmission) {
        final AtomicBoolean completed = new AtomicBoolean(false);
        final Consumer<ExecutionSubmission> receipt = outcome -> {
            if (completed.compareAndSet(false, true) && onSubmission != null) {
                onSubmission.accept(outcome);
            }
        };
        Project proj = p.project;
        if (proj == null || sql.isBlank()) {
            receipt.accept(ExecutionSubmission.BLOCKED);
            return;
        }
        boolean isQuery = SqlTypeClassifier.isQuery(sql);
        ExecutionDialectGate.Request dialectGateRequest = ExecutionDialectGate.rawSqlRequest(sql);
        // O-64/T-RollbackIdentityParse（P19/D2=A）：identity 包装产物整批直跑。识别特征=
        // 含 `SET IDENTITY_INSERT … ON|OFF` 语句（本插件自产回滚批次的封闭形态）；普通多语句
        // 产物保持现态（Statements 选择层默认行为不变），避免行为突变。生产入口仅本函数
        // （回滚弹窗「执行回滚/执行快照查询」），executeSql 的用户编辑器执行链不接旗标。
        boolean wholeScriptExec = cn.song.mybatisplus.visualizer.sql.IdentityInsertStatements.containsStatement(sql);
        if (!isQuery && !dmlConfirmed) {
            String keyword = SqlTypeClassifier.getFirstKeyword(sql);
            if (keyword == null) keyword = "SQL";
            int confirm = Messages.showYesNoDialog(
                    proj,
                    cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages.build(sql, keyword),
                    MpSqlVisualizerBundle.message("execute.confirm.dml"),
                    Messages.getWarningIcon());
            if (confirm != Messages.YES) {
                receipt.accept(ExecutionSubmission.CANCELLED);
                return;
            }
        }
        // P2-F5/F6：经执行按钮执行的 UPDATE/DELETE 执行前登记快照条目（回滚语句登记留痕链）
        registerRollbackSnapshotIfNeeded(p, sql, rollbackOfEntry);

        p.resultPanel.showLoading("");
        if (SqlExecutor.isPrepared()) {
            if (!consoleAttachedToMe(p)) {
                Logger.getInstance(SqlPreviewPanel.class)
                        .warn("[TRollbackSql] executeAdhocSql: prepared console detached → recover before execute");
                recoverConsole(p, sql);
                if (SqlExecutor.isPrepared() && !consoleAttachedToMe(p)) {
                    SqlExecutor.execute(
                            proj, sql, null, isQuery,
                            consoleComponent -> embedConsole(p, consoleComponent, ConsoleEmbedMode.EXECUTE),
                            consoleEditor -> onNativeEditorReady(p, consoleEditor),
                            errorMsg -> p.resultPanel.showError(errorMsg),
                            info -> showErrorRichWithLocate(p, info),
                            wholeScriptExec,
                            dialectGateRequest, null, receipt, false);
                    return;
                }
            }
            // 快路径同步执行 SQL 进控制台文档（executePrepared 执行的就是控制台文档；
            // 回滚执行后下一次 renderSql 会按预览 SQL 同步回来）
            com.intellij.openapi.editor.Document doc = p.nativeEditorDoc;
            if (doc != null) {
                if (!doc.getText().equals(sql)) {
                    final com.intellij.openapi.editor.Document docF = doc;
                    WriteCommandAction.runWriteCommandAction(proj, () -> docF.setText(sql));
                }
            }
            boolean executed = SqlExecutor.executePrepared(
                    proj,
                    fullConsole -> embedConsole(p, fullConsole, ConsoleEmbedMode.EXECUTE),
                    errorMsg -> p.resultPanel.showError(errorMsg),
                    info -> showErrorRichWithLocate(p, info),
                    wholeScriptExec,
                    dialectGateRequest, null, receipt);
            if (executed) return;
        }
        SqlExecutor.execute(
                proj, sql, null, isQuery,
                consoleComponent -> embedConsole(p, consoleComponent, ConsoleEmbedMode.EXECUTE),
                consoleEditor -> onNativeEditorReady(p, consoleEditor),
                errorMsg -> p.resultPanel.showError(errorMsg),
                info -> showErrorRichWithLocate(p, info),
                wholeScriptExec,
                dialectGateRequest, null, receipt, false);
    }

    /**
     * T-RollbackSql P2-F5/F6：UPDATE/DELETE 执行前登记快照条目（内存 store，LogSqlEntry 不动字段）。
     * 回滚语句（rollbackOfEntry 非 null）也登记——parentExecutionId 指向被回滚的原执行，
     * 形成 dbx {@code rollback_of} 语义的留痕链；仅登记可解析到目标表的语句（解析失败登记无意义）。
     *
     * <p>**T-PS5-02 / T-ST-03 解析复用前提（实测核实，勿默认撤销）**：下面 {@code RollbackSqlGenerator.generate}
     * 走 {@code UnboundedWriteDetector.parseStatementsShared} 的 AST 单槽，与确认阶段
     * （executeSql/executeAdhocSql 的 {@code ExecuteConfirmMessages.build} → {@code UnboundedWriteDetector.detect}）
     * 共用同一次解析——命中判据是**同文本内容全等**，故此处**依赖两条前提**：
     * ①确认阶段确实以同一文本走了一遍 {@code detect}（UPDATE/DELETE 必经 DML 二次确认，成立；
     * **豁免参数路径** {@code executeAdhocSql(dmlConfirmed = true)} 不走确认弹窗、退化为本链自解析一次——
     * 但该参数**现行生产无调用点**，仅由测试覆盖，故此退化目前只存在于测试路径）；
     * ②{@link SqlCommentStripper#strip} 幂等——此处传入的是 {@code strip(sql)}，而 {@code detect} 内部键是
     * {@code strip(sql)}、{@code generate} 内部还会再剥一次（键成 {@code strip(strip(sql))}），幂等成立才落回同一槽。
     * 失效后果分档：**前提①失效只是多一次解析**；**前提②失效则不同**——命中路径用单剥文本的 AST、
     * 未命中路径解析双剥文本，两条路径输入文本不同、结果可能随缓存命中状态而变。</p>
     */
    public static void registerRollbackSnapshotIfNeeded(
            @NotNull SqlPreviewPanel p,
            @NotNull String sql,
            @Nullable cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.SnapshotEntry rollbackOfEntry) {
        Project proj = p.project;
        if (proj == null) return;
        String keyword = SqlTypeClassifier.getFirstKeyword(sql);
        if (keyword == null) return;
        if (!"UPDATE".equals(keyword) && !"DELETE".equals(keyword)) return;
        cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore store =
                proj.getService(cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.class);
        // O-59 T-IIR-01：把**当前生效方言**随快照登记进去——取的是 SqlPreviewService.currentDialect
        // （= DialectRegistry.effective(project)，与预览/执行链同源，含 T-DialectAutoInfer 的数据源
        // 推断，不新造解析）。此前两处登记点都不传该参数 → SnapshotEntry.dialect 恒 null →
        // RollbackSqlGenerator.backfillInsert 的 wrapIdentity 恒假 → 回滚侧 identity 包装在
        // **生产链上永不触发**。取不到时保持 null（= 未知不包装，O-52 D1=A 契约不变）。
        cn.song.mybatisplus.visualizer.sql.Dialect dialect =
                proj.getService(SqlPreviewService.class).currentDialect();
        if (rollbackOfEntry != null) {
            store.register(
                    sql, rollbackOfEntry.targetTable(), null,
                    List.of(), rollbackOfEntry.executionId(), dialect, List.of());
            return;
        }
        // T-PS5-02：传入已剥注释文本——键对齐靠 strip 幂等（详见本函数 KDoc 的两条前提）
        cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.RollbackResult analyzed =
                cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.generate(SqlCommentStripper.strip(sql));
        String table = analyzed.targetTable();
        if (table == null) return;
        store.register(sql, table, analyzed.snapshotSelectSql(), analyzed.setColumns(),
                cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.NO_EXECUTION_ID, dialect, List.of());
    }

    /** 当前编辑器 SQL 文本（原生编辑器文档优先，兜底自研编辑器；两处皆空 null）。 */
    @Nullable
    public static String currentEditorSql(@NotNull SqlPreviewPanel p) {
        if (p.nativeEditorDoc != null && !p.nativeEditorDoc.getText().isBlank()) return p.nativeEditorDoc.getText();
        if (p.editor != null && !p.editor.getDocument().getText().isBlank()) return p.editor.getDocument().getText();
        return null;
    }

    /**
     * T-ExplainConsoleFirst W2（2026-10-24）：<b>只读动作</b>的取词——执行视图在场时读预览快照。
     *
     * <p>为什么需要：执行计划走内嵌原生控制台（形态 A）时，EXPLAIN 语句被写进 {@code mpsql-exec.sql} 的
     * document，而 {@code nativeEditorDoc} 指向它——{@link #currentEditorSql} 于是从「预览 SQL」漂移成
     * 「EXPLAIN …」。回滚 SQL / 导出回放脚本 / 复制 AI 上下文 / 导出可移植 SQL / 预估影响行数
     * 全都读那个函数，在执行视图里点它们会拿到 EXPLAIN 语句（{@code 1a1cf724} 正是因此把默认通道换成
     * 旁路查询的）。</p>
     *
     * <p>判据：{@code lastRenderedSql} 由 showSql 在每次预览渲染后记录，而控制台执行链写 exec 文件
     * <b>不经过 showSql</b>，故它恰好保存着「最后一次预览的 SQL」。</p>
     *
     * <p>边界：执行视图不在场（含「返回预览」之后）一律返回真实编辑器文本——用户手改必须被看见。
     * <b>执行链本身不用本函数</b>（executeSql/explainSql 执行的就是编辑器里的真实文本）。</p>
     */
    @Nullable
    public static String previewSqlForReadonly(@NotNull SqlPreviewPanel p) {
        if (p.executeViewHeader != null) {
            String snapshot = p.lastRenderedSql;
            if (snapshot != null && !snapshot.isBlank()) return snapshot;
        }
        return currentEditorSql(p);
    }

    /**
     * T-InjectionGate T-IG-02：{@code ${}} 原文拼接段的执行确认闸门（{@link #executeSql}/{@link #explainSql} 共用）。
     *
     * <p>判定信号取自 {@link PreviewResult#isHasDollarRawSegments()}（解析期 {@code ${}} token 收集的渲染期事实，
     * 不是最终文本 regex——{@code ${}} 成功替换为模拟值后文本里已无痕迹，regex 会漏掉全部成功场景）。
     * 含 raw 段且本面板未确认过 → 弹确认框；用户选 NO → 中止；选 YES → 记会话级记忆，本面板后续执行不再重复弹。</p>
     *
     * @return true = 放行（无 raw 段 / 已确认过 / 本次确认通过）
     */
    public static boolean confirmDollarRawSegments(@NotNull SqlPreviewPanel p, @NotNull Project proj) {
        if (p.current == null || !p.current.isHasDollarRawSegments()) return true;
        if (p.dollarRawConfirmedInSession) return true;
        int confirm = Messages.showYesNoDialog(
                proj,
                MpSqlVisualizerBundle.message("execute.confirm.dollarRaw.message", SqlPreviewPanel.DOLLAR_TOKEN),
                MpSqlVisualizerBundle.message("execute.confirm.dml"),
                Messages.getWarningIcon());
        if (confirm != Messages.YES) return false;
        p.dollarRawConfirmedInSession = true;
        return true;
    }

    /**
     * T-PC2-04（D1=B 定案）：含「签名类型未能识别」参数的语句执行确认闸门
     * （{@link #executeSql}/{@link #explainSql} 共用，形态与 {@link #confirmDollarRawSegments} 同款）。
     *
     * <p>判定信号取自 {@link XmlPreviewModel#unknownParamSources}（{@code paramTypes} 有条目但类别 UNKNOWN：
     * 枚举 / {@code boolean} / {@code byte[]} 等——类型读到了却映射不到类别，绑定层只能以字面 {@code ?} 提交）。
     * **无类型信息不计入**（mapper 接口不可解析时键不在 {@code paramTypes}）——与 T-PT-02「无类型信息
     * 不提示」同向，也避免「接口解析不到 → 每次执行都提示」的噪音面。</p>
     *
     * @return true = 放行（无 UNKNOWN 参数 / 已确认过 / 本次确认通过）
     */
    public static boolean confirmUnknownParams(@NotNull SqlPreviewPanel p, @NotNull Project proj) {
        List<String> unknown = (p.current != null && p.current.getXmlModel() != null)
                ? p.current.getXmlModel().unknownParamSources(p.current.getUnresolvedParams())
                : null;
        if (unknown == null) unknown = List.of();
        if (unknown.isEmpty() || p.unknownParamsConfirmedInSession) return true;
        int confirm = Messages.showYesNoDialog(
                proj,
                MpSqlVisualizerBundle.message("execute.confirm.unknownParams.message", unknown.size()),
                MpSqlVisualizerBundle.message("execute.confirm.dml"),
                Messages.getWarningIcon());
        if (confirm != Messages.YES) return false;
        p.unknownParamsConfirmedInSession = true;
        return true;
    }

    /**
     * T-PSG-04（N-33）：{@code ?} 位点数与参数条数对账的**非阻断**标注。
     *
     * <p>结论由 backfillParamsBySource 在回填时算出（串感知位点数 vs 参数条数），此处排在两道
     * 确认门之后、快照登记/执行之前读走并如实登记日志——不弹窗、不中止（我方既有档位是确认/标注，
     * 不是拒绝）。产物侧的同款对账标注见 {@link CopyExecutableSqlAction} 的头部标记。</p>
     */
    public static void warnOnPlaceholderMismatch(@NotNull SqlPreviewPanel p) {
        String mismatch = p.placeholderReconciliation;
        if (mismatch == null) return;
        Logger.getInstance(SqlPreviewPanel.class).warn("[T-PSG-04] executeSql: " + mismatch);
    }

    /**
     * 执行 SQL（M36）：
     *  1. SQL 类型分类 → 非查询语句二次确认
     *  2. 创建临时上下文文件 → 原生数据源弹窗 → 选中后自动执行
     *  3. 结果在打开的 Database 编辑器下方原生展示
     *
     * <p>Community（无 database 插件）由 {@link SqlExecutor} 提示降级。
     * M36.5.4：入口/中止日志——沙盒排查执行问题时此前无任何面板侧日志（静默 return 难定位）。</p>
     */
    public static void executeSql(@NotNull SqlPreviewPanel p) {
        executeSql(p, false);
    }

    public static void executeSql(@NotNull SqlPreviewPanel p, boolean forceChoose) {
        Project proj = p.project;
        if (proj == null) return;
        // M36.4：优先取原生编辑器文档，兜底自研编辑器
        String sql;
        if (p.nativeEditorDoc != null && !p.nativeEditorDoc.getText().isBlank()) {
            sql = p.nativeEditorDoc.getText();
        } else if (p.editor != null && !p.editor.getDocument().getText().isBlank()) {
            sql = p.editor.getDocument().getText();
        } else {
            Logger.getInstance(SqlPreviewPanel.class)
                    .warn("[M36] executeSql aborted: no SQL text (nativeDoc=" + (p.nativeEditorDoc != null)
                            + ", editor=" + (p.editor != null) + ")");
            return;
        }
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] executeSql: len=" + sql.length() + ", prepared=" + SqlExecutor.isPrepared());

        // 1. SQL 类型分类 → 非查询语句二次确认
        boolean isQuery = SqlTypeClassifier.isQuery(sql);
        if (!isQuery) {
            String keyword = SqlTypeClassifier.getFirstKeyword(sql);
            if (keyword == null) keyword = "SQL";
            int confirm = Messages.showYesNoDialog(
                    proj,
                    cn.song.mybatisplus.visualizer.execute.ExecuteConfirmMessages.build(sql, keyword),
                    MpSqlVisualizerBundle.message("execute.confirm.dml"),
                    Messages.getWarningIcon());
            if (confirm != Messages.YES) return;
        }
        // T-InjectionGate T-IG-02：`${}` raw 拼接段确认门（DML 分类判定之后追加——预览产物里的
        // raw 段带的是模拟值，执行前须用户确认值真实性；NO 直接中止，不落快照不执行）
        if (!confirmDollarRawSegments(p, proj)) return;
        // T-PC2-04（D1=B）：签名类型未识别参数确认门（D1=B 的「执行/EXPLAIN 前提示一次」——
        // 预览链保持放行；排在 `${}` 门之后、快照登记/执行之前，NO 直接中止）
        if (!confirmUnknownParams(p, proj)) return;
        // T-PSG-04（N-33）：`?` 位点数与参数条数对账——与上面两道确认门同址，但**不阻断**
        warnOnPlaceholderMismatch(p);
        // T-RollbackSql P2-F5/F6：UPDATE/DELETE 执行前登记快照条目（回滚弹窗据此提供粘贴回填工作流）
        registerRollbackSnapshotIfNeeded(p, sql, null);
        ExecutionDialectGate.Request dialectGateRequest = executionDialectGateRequest(
                p, isQuery ? ExecutionDialectGate.Intent.QUERY : ExecutionDialectGate.Intent.DML);

        // 2. 控制台已就绪（M36.5 默认展示）→ 同步 SQL 到控制台文档后直接执行；
        //    未就绪 → 走准备+执行合一全链（准备完成后控制台内嵌右侧）
        p.resultPanel.showLoading("");
        // T-DialectGateUx：forceChoose（错误行「切换数据源」）必须跳过已准备控制台，
        // 否则会继续复用与原目标同源的会话，闸门再次拦截。
        if (SqlExecutor.isPrepared() && !forceChoose) {
            // T-RefreshDbSteal F4：isPrepared 只证明插件级共享控制台存在，不证明它还挂在本面板——
            // 失联时 executePrepared 会把 SQL 执行到别的弹窗/Database 工具窗的控制台（症状③）。
            if (!consoleAttachedToMe(p)) {
                Logger.getInstance(SqlPreviewPanel.class)
                        .warn("[M36] executeSql: prepared console detached from this panel → recover before execute");
                recoverConsole(p, sql);
                if (SqlExecutor.isPrepared() && !consoleAttachedToMe(p)) {
                    // 恢复未落地（异步链未回调）：放弃 executePrepared，落全链（全链会重建/复用
                    // 共享控制台并经 onConsole 重嵌本面板），避免打在别人的控制台上
                    SqlExecutor.execute(
                            proj, sql, null, isQuery,
                            consoleComponent -> embedConsole(p, consoleComponent, ConsoleEmbedMode.EXECUTE),
                            consoleEditor -> onNativeEditorReady(p, consoleEditor),
                            errorMsg -> p.resultPanel.showError(errorMsg),
                            info -> showErrorRichWithLocate(p, info),
                            false,
                            dialectGateRequest,
                            verdict -> onExecutionDialectGate(p, verdict),
                            null,
                            false);
                    return;
                }
            }
            // 执行前同步当前 SQL（用户可能改过参数/手动编辑）
            com.intellij.openapi.editor.Document doc = p.nativeEditorDoc;
            if (doc != null) {
                if (!doc.getText().equals(sql)) {
                    final com.intellij.openapi.editor.Document docF = doc;
                    WriteCommandAction.runWriteCommandAction(proj, () -> docF.setText(sql));
                }
            }
            boolean executed = SqlExecutor.executePrepared(
                    proj,
                    // M36.5.2：执行触发成功后换入完整控制台（结果区执行后才出现）
                    fullConsole -> embedConsole(p, fullConsole, ConsoleEmbedMode.EXECUTE),
                    errorMsg -> p.resultPanel.showError(errorMsg),
                    info -> showErrorRichWithLocate(p, info),
                    false,
                    dialectGateRequest,
                    verdict -> onExecutionDialectGate(p, verdict),
                    null);
            if (executed) return;
        }
        SqlExecutor.execute(
                proj, sql, null, isQuery,
                consoleComponent -> embedConsole(p, consoleComponent, ConsoleEmbedMode.EXECUTE),
                consoleEditor -> onNativeEditorReady(p, consoleEditor),
                // M36.3：错误回调——执行链任何环节失败时从「执行中」状态恢复并显示错误
                errorMsg -> p.resultPanel.showError(errorMsg),
                info -> showErrorRichWithLocate(p, info),
                false,
                dialectGateRequest,
                verdict -> onExecutionDialectGate(p, verdict),
                null,
                forceChoose);
    }

    /**
     * T-ErrorLocate T-ELU-01：执行调用点共用的富错误回调——**先解析锚点、再决定按钮显隐**。
     *
     * <p>诚实降级定案：驱动报错位置（Position/at line）指向**被执行 SQL 文本**的字符/行，
     * 与 mapper 原文行不一一对应（动态 SQL 拼接、参数注入后行号必然错位），v1 只承诺
     * 「语句起始行」跳转 + 按钮 tooltip 如实说明，绝不虚标精确跳转。
     * 锚点来自 {@code SqlPreviewService.lastPreviewSourceAnchor}（预览触发时登记）。</p>
     */
    public static void locateErrorSource(@NotNull SqlPreviewPanel p, @NotNull Project proj,
                                         @NotNull SqlPreviewService.SourceAnchor anchor) {
        try {
            new com.intellij.openapi.fileEditor.OpenFileDescriptor(proj, anchor.file(), anchor.line0(), 0).navigate(true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * T-ErrorLocateUsability T-ELU-01（O-49）：**先解析锚点，再决定是否给按钮**。
     *
     * <p>缺陷（KDoc 与实现不符）：{@code SqlResultPanel.showErrorRich} 按 {@code locate != null} 决定按钮显隐，
     * 而唯一调用方此前**恒传非 null lambda**（锚点解析发生在点击时，{@code project ?: return} /
     * {@code anchor ?: return} 静默返回）→ **按钮可见，点了什么都不发生**。现把解析提到显隐之前。</p>
     */
    @Nullable
    public static Runnable errorLocateAction(@NotNull SqlPreviewPanel p) {
        Project proj = p.project;
        if (proj == null) return null;
        SqlPreviewService.SourceAnchor anchor;
        try {
            anchor = proj.getService(SqlPreviewService.class).lastPreviewSourceAnchor;
        } catch (Throwable t) {
            anchor = null;
        }
        if (anchor == null) return null;
        if (!anchor.file().isValid()) return null;
        final Project projF = proj;
        final SqlPreviewService.SourceAnchor anchorF = anchor;
        return () -> locateErrorSource(p, projF, anchorF);
    }

    /** T-ErrorLocate：执行调用点共用的富错误回调（错误呈现 + **锚点可解析时**才附定位动作）。 */
    public static void showErrorRichWithLocate(@NotNull SqlPreviewPanel p, @NotNull ExecutionErrorInfo info) {
        p.resultPanel.showErrorRich(info, errorLocateAction(p));
        maybeProbeDdlEffective(p, info);
    }

    /**
     * T-DdlRecover（O-15）T-DR-02：「失败不等于未生效」——执行 DDL 撞连接类断开时，数据库侧
     * 可能已隐式提交。判定链：连接类异常（SQLState 08xxx / vendor 码 / 签名兜底）∧ 可能改元数据
     * （MetadataInvalidator 既有预判复用）→ 提取 CREATE 目标表名 → 后台退避探测（500ms/1.5s/3s
     * 三次，等 DBN 自动重连窗口）→ 命中呈现警示条、未命中 tooltip 如实注记。
     * 探测/提取任一失败维持原错误呈现（诚实降级）；警示无新弹窗（弹窗克制红线）。
     */
    public static void maybeProbeDdlEffective(@NotNull SqlPreviewPanel p, @NotNull ExecutionErrorInfo info) {
        Project proj = p.project;
        if (proj == null) return;
        if (!cn.song.mybatisplus.visualizer.execute.ConnectionErrorClassifier.isConnectionError(info)) return;
        String sql;
        if (p.nativeEditorDoc != null && !p.nativeEditorDoc.getText().isBlank()) {
            sql = p.nativeEditorDoc.getText();
        } else if (p.editor != null && !p.editor.getDocument().getText().isBlank()) {
            sql = p.editor.getDocument().getText();
        } else {
            return;
        }
        if (!cn.song.mybatisplus.visualizer.execute.MetadataInvalidator.mayChangeMetadata(sql)) return;
        String target = cn.song.mybatisplus.visualizer.execute.DdlTargetExtractor.extractCreateTarget(sql);
        if (target == null) return;
        final Project projF = proj;
        final String targetF = target;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean[] hit = {false};
            for (long delayMs : new long[]{500, 1500, 3000}) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.TableMeta meta;
                try {
                    meta = cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.tableMeta(projF, targetF);
                } catch (Throwable t) {
                    meta = null;
                }
                if (meta == null) break;
                if (meta.comment() != null || !meta.columns().isEmpty()) {
                    hit[0] = true;
                    break;
                }
            }
            String probedAt = java.time.LocalTime.now().withNano(0).toString();
            final boolean hitFinal = hit[0];
            ApplicationManager.getApplication().invokeLater(() -> {
                if (hitFinal) {
                    p.resultPanel.showDdlEffectiveWarning(targetF, probedAt);
                } else {
                    p.resultPanel.noteDdlProbeMissed();
                }
            }, ModalityState.any());
        });
    }

    /**
     * M37.2：真实 EXPLAIN——按当前方言把预览 SQL 包装为 EXPLAIN 语句，走执行链提交原生控制台。
     * 结果展示与普通执行一致（内嵌控制台结果 Tab）；静态性能规则标注保留在顶部 meta 区，同屏对照。
     * EXPLAIN 归免确认类（{@link SqlTypeClassifier}），无 DML 二次确认；方言不支持（null）→ 提示降级。
     *
     * <p>T-ExplainChainHardening T-ECH-01（O-48，D1=A 严格判定）：包装前先判**被包语句**的首关键字
     * 是否落在 {@link ExplainGate} 白名单——此前对编辑器里的任意文本加前缀，现改为明确提示、不执行。
     * 判据只看「未包装」的文本：已以 EXPLAIN 开头的文本是本插件上一次的包装产物（T-ExplainDbl
     * 幂等场景），不适用白名单，否则二次点击会被误拒。</p>
     */
    public static void explainSql(@NotNull SqlPreviewPanel p) {
        Project proj = p.project;
        if (proj == null) return;
        String sql;
        if (p.nativeEditorDoc != null && !p.nativeEditorDoc.getText().isBlank()) {
            sql = p.nativeEditorDoc.getText();
        } else if (p.editor != null && !p.editor.getDocument().getText().isBlank()) {
            sql = p.editor.getDocument().getText();
        } else {
            return;
        }
        cn.song.mybatisplus.visualizer.sql.Dialect dialect =
                (p.current != null && p.current.getPreviewModel() != null)
                        ? p.current.getPreviewModel().getDialect()
                        : MySqlDialect.INSTANCE;
        // T-ECH-01：提交判定（幂等 → 首关键字白名单 → 方言支持面），两类拒绝理由分别提示
        ExplainGate.SubmitPlan plan = ExplainGate.planExplainSubmit(dialect, sql);
        String explainSql;
        if (plan instanceof ExplainGate.SubmitPlan.NotExplainable) {
            notifyExplainNotExplainable(p, proj, ((ExplainGate.SubmitPlan.NotExplainable) plan).firstKeyword());
            return;
        } else if (plan instanceof ExplainGate.SubmitPlan.DialectUnsupported) {
            // T-UiNative T-UN-02：方言不支持提示改轻通知（原模态弹窗——纯告知无需打断）
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                try {
                    new Notification(
                            SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("panel.explain"),
                            MpSqlVisualizerBundle.message("panel.explain.unsupported",
                                    ((ExplainGate.SubmitPlan.DialectUnsupported) plan).dialectName()),
                            NotificationType.INFORMATION).notify(proj);
                } catch (Throwable ignored) {
                }
            }
            return;
        } else if (plan instanceof ExplainGate.SubmitPlan.Submit) {
            ExplainGate.SubmitPlan.Submit submit = (ExplainGate.SubmitPlan.Submit) plan;
            // R23：二段式方言（Oracle）的计划不在本次语句的返回行里——提交前轻通知告知另查路径
            ExplainSupport.Entry entry = ExplainSupport.entryFor(dialect.getName());
            String followUp = entry != null ? entry.postPlanQuery() : null;
            if (followUp != null) {
                if (!ApplicationManager.getApplication().isUnitTestMode()) {
                    try {
                        new Notification(
                                SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                                MpSqlVisualizerBundle.message("panel.explain"),
                                MpSqlVisualizerBundle.message("panel.explain.planRegistered", followUp),
                                NotificationType.INFORMATION).notify(proj);
                    } catch (Throwable ignored) {
                    }
                }
            }
            explainSql = submit.statement();
        } else {
            return;
        }
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M37.2] explainSql: dialect=" + dialect.getName() + ", len=" + explainSql.length());
        // T-InjectionGate T-IG-03：EXPLAIN 本就真实执行——`${}` raw 段同门控（旁路不得绕开执行闸门）；
        // 方言不支持（上方已 return，未执行）不弹
        if (!confirmDollarRawSegments(p, proj)) return;
        // T-PC2-04（D1=B）：EXPLAIN 同门控（旁路不得绕开执行闸门，与 T-IG-03 同款姿态）
        if (!confirmUnknownParams(p, proj)) return;
        // T-PSG-04（N-33）：对账非阻断标注与 executeSql 同址同款（EXPLAIN 也是执行旁路）
        warnOnPlaceholderMismatch(p);
        // R33：面板 EXPLAIN 双通道分派（结构化渲染为增量——判定/执行/渲染任一环不成立都
        // fail-closed 回退直送通道，双通道并存不替换既有行为，见 submitExplainOrStructured）
        // 代际先递增（所有路径）——作废前一次提交的在途结构化回调
        p.explainStructuredGeneration++;
        submitExplainOrStructured(p, proj, dialect, explainSql,
                executionDialectGateRequest(p, ExecutionDialectGate.Intent.EXPLAIN));
    }

    /**
     * T-PreviewSideQuery P2：显式「在控制台打开」路径（保留原有 SqlExecutor.execute 行为）。
     * 仅由用户在结果对话框里明确选择时调用；默认 EXPLAIN/回退不再走这里。
     *
     * <p>T-ExplainConsoleFirst W8（2026-10-24 沙盒验收实测）：<b>进执行链前先预热 exec 临时文件</b>。</p>
     *
     * <p>实测症状：点「执行计划」刷 9 条 {@code SEVERE - SlowOperations ... Plugin to blame: SQL Visualizer}，
     * 栈为 {@code explainSql → submitExplainDirectConsole → SqlExecutor.execute → chooseAndPrepare →
     * createTempPsiFile → PsiManagerImpl.findFile}——即 {@code createTempPsiFile} 的<b>冷路径</b>
     * （VFS refresh + {@code PsiManager.findFile}）在 EDT 上跑。</p>
     *
     * <p>为什么现在才暴露：{@code 1a1cf724} 之后 EXPLAIN 默认走旁路查询（PreviewSideQuery），不经过
     * 控制台链；本轮 W1 把它切回控制台后，这条既有冷路径重新进入热路径。</p>
     *
     * <p>修法：执行前把<b>本条 EXPLAIN 文本</b>交给后台预热（专用入口自带 EDT 拒绝、并发去重），
     * 命中缓存后 {@code createTempPsiFile} 走热路径。预热失败只是回落到既有冷路径，不阻断执行。</p>
     *
     * <p>W8-fix（同日复验修订）：<b>不得在 EDT 上阻塞等待预热</b>——后台线程里直接调 inner
     * （不嵌套 prewarmExecTempFile 的预算等待），完成后回 EDT 续接执行链；预热失败/超时都照常续接。</p>
     */
    public static void submitExplainDirectConsole(
            @NotNull SqlPreviewPanel p,
            @NotNull Project proj,
            @NotNull String explainSql,
            @NotNull ExecutionDialectGate.Request dialectGateRequest) {
        // R46：EXPLAIN 是执行旁路，同样过 Schema 校验（未识别对象走轻通知，不阻断）
        cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.Outcome outcome =
                cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.check(proj, explainSql);
        if (outcome instanceof cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.Checked checked) {
            notifySchemaIssues(p, proj, checked.issues());
        }
        final AtomicBoolean prewarmDone = new AtomicBoolean(false);
        final Runnable continueChain = () -> {
            if (prewarmDone.compareAndSet(false, true)) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (Disposer.isDisposed(p) || proj.isDisposed()) return;
                    p.resultPanel.showLoading("");
                    SqlExecutor.execute(
                            proj, explainSql, null, true,
                            consoleComponent -> embedConsole(p, consoleComponent, ConsoleEmbedMode.EXECUTE),
                            consoleEditor -> onNativeEditorReady(p, consoleEditor),
                            errorMsg -> p.resultPanel.showError(errorMsg),
                            info -> showErrorRichWithLocate(p, info),
                            false,
                            dialectGateRequest,
                            verdict -> onExecutionDialectGate(p, verdict),
                            null, false);
                }, ModalityState.any());
            }
        };
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prewarmExecTempFileOnBackgroundThread(
                        proj,
                        explainSql,
                        dialectGateRequest.renderedDialectName(),
                        com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel.class));
            } catch (Throwable e) {
                if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(e)) throw (Error) e;
                com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel.class)
                        .info("[W8] exec prewarm before explain failed (fallback to cold path): "
                                + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            // 预热完成（含失败）都续接——预热只是优化，不是前置条件。
            ApplicationManager.getApplication().invokeLater(continueChain::run, ModalityState.any());
        });
    }

    /**
     * T-ExplainConsoleFirst W1（2026-10-24）：执行计划默认/回退<b>统一走内嵌原生控制台</b>。
     *
     * <p>形态渊源：这是初版实现（{@code 1a1cf724} 前的 submitExplainDirect），{@code 1a1cf724} 为了「不顶掉
     * 预览编辑器」把默认改成旁路查询（形态 B）。十二轮换源拉锯（T-DialectInferPollution /
     * T-ExplainFailUx / T-ExplainSwitchNative，合计 W1~W12）暴露出形态 B / 结构化通道的系统性代价：
     * 它们是插件自建 JDBC 通道，于是「用哪条源、密码从哪来、方言对不对、失败怎么呈现」全落在插件
     * 身上；而平台「选中即关联」（getForcedDataSourceForExec 命中即 singletonList 短路枚举、
     * 无公开清除 API）让插件侧选源列表第二次起恒为单源——这是那十二轮全部拉锯的根因。</p>
     *
     * <p>形态 A 把这些<b>整类</b>交回平台：换源走内嵌控制台自带的原生 Data Sources 菜单（Detach +
     * 全部源），密码走平台凭据链，方言与报错在控制台原生呈现。{@code 1a1cf724} 当初真正的顾虑
     * （执行视图里只读动作拿到 EXPLAIN 语句）已由 {@link #previewSqlForReadonly} 根治（W2）。</p>
     *
     * <p>前置只判「一条源都没有」→ 引导去 Database 工具窗口；<b>不在插件侧做任何选源</b>
     * （不重演 W1~W12）。结构化形态（explainStructuredRender）不受影响，仍在设置项后。</p>
     */
    public static void submitExplainDirect(
            @NotNull SqlPreviewPanel p,
            @NotNull Project proj,
            @NotNull String explainSql,
            @NotNull ExecutionDialectGate.Request dialectGateRequest) {
        // R46：EXPLAIN 是执行旁路，同样过 Schema 校验（未识别对象走轻通知，不阻断）
        cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.Outcome outcome =
                cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.check(proj, explainSql);
        if (outcome instanceof cn.song.mybatisplus.visualizer.execute.SchemaValidationGate.Checked checked) {
            notifySchemaIssues(p, proj, checked.issues());
        }
        // 零源判定：只要有一条在籍源就交给平台（平台自己决定用哪条、按需弹选择器），
        // 插件不枚举、不筛选、不猜。测试缝非 null 即视为「有可用源」。
        boolean hasAnySource;
        if (explainTargetForTest != null) {
            hasAnySource = explainTargetForTest.get() != null;
        } else {
            boolean registered;
            try {
                registered = cn.song.mybatisplus.visualizer.execute.DataSourceSelector
                        .registeredDataSourceCount(proj) > 0;
            } catch (Throwable t) {
                registered = true; // runCatching { ... }.getOrDefault(true) 等价
            }
            hasAnySource = registered;
        }
        if (!hasAnySource) {
            // 一条源都没有：按钮直达 Database 工具窗口（按提示去配置/连接），不空跑一次必然失败的执行。
            ExplainPlanDialog.showExplainErrorDialog(p,
                    MpSqlVisualizerBundle.message("panel.explain.noDataSource"),
                    () -> {
                    }, true);
            return;
        }
        submitExplainDirectConsole(p, proj, explainSql, dialectGateRequest);
    }

    /**
     * T-ExplainFailUx W1（2026-10-24 用户沙盒实测）：<b>选错数据源</b>失败的就地引导。
     *
     * <p>为什么不用模态：{@code Table 'mp_demo.x' doesn't exist} 是最典型的「该换源」信号，模态框会先遮住
     * 预览、还要求用户先关掉再点「在控制台打开」两步才见到换源入口（用户原话：「这种情况就不应该
     * 弹窗了吧」）。改为结果区就地横幅（SqlResultPanel.showErrorWithActions 双动作槽）。</p>
     *
     * <p>T-ExplainSwitchNative W6（同日修订）：动作语义改为「<b>弹平台原生选择器</b>」且<b>不自动重试</b>
     * ——用户实测「点切换数据源却重试执行了」：旧实现走 prepareConsole(forceChoose=true)，
     * 而该参数只跳过 last-used 静默，runner 唯一时仍会直选执行，用户看不到列表。</p>
     */
    private static void showExplainWrongTarget(
            @NotNull SqlPreviewPanel p,
            @NotNull Project proj,
            @NotNull String message,
            @NotNull String explainSql,
            @NotNull ExecutionDialectGate.Request dialectGateRequest,
            @NotNull Runnable openConsole) {
        p.resultPanel.showIdle();
        String hint = MpSqlVisualizerBundle.message("panel.explain.wrongTargetHint");
        p.resultPanel.showErrorWithActions(
                message + "　" + hint,
                MpSqlVisualizerBundle.message("panel.explain.action.switchDataSource"),
                MpSqlVisualizerBundle.message("panel.explain.action.switchDataSource.tip"),
                // W12：换源走平台原生工具栏 Data Sources 菜单（见 showDialectGateError 同款说明）
                () -> cn.song.mybatisplus.visualizer.execute.DataSourceSelector.openDatabaseToolWindow(proj),
                MpSqlVisualizerBundle.message("panel.explain.openConsole"),
                null,
                openConsole);
    }

    /**
     * T-ExplainPwdPrompt W6（2026-10-24）：认证失败轻通知——平台凭据链（askPassword=true 的
     * 「Change Credentials / Abort」对话框）已完成失败呈现与两个出口，本通知只做 Abort 后的
     * 收口反馈（含补存密码指引），绝不弹红字模态框二次打断。单测模式不弹（notifySchemaIssues
     * 同款守卫）。
     */
    private static void notifyExplainAuthFailure(@NotNull SqlPreviewPanel p, @NotNull Project proj) {
        if (ApplicationManager.getApplication().isUnitTestMode() && schemaNotificationForTest == null) return;
        try {
            new Notification(
                    SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("panel.explain"),
                    MpSqlVisualizerBundle.message("panel.explain.authFailureHint"),
                    NotificationType.WARNING).notify(proj);
        } catch (Throwable ignored) {
        }
    }

    /**
     * T-ExplainPwdPrompt（2026-10-24）：用户取消（原生密码框 Esc 等）——轻通知收口，不弹红字
     * 模态框（取消是用户自己的决定，模态报错等于打断两次），也<b>绝不自动重试</b>（重试会再弹一次
     * 密码框）。单测模式不弹（弹窗/通知克制红线，notifySchemaIssues 同款守卫）。
     */
    private static void notifyExplainCancelled(@NotNull SqlPreviewPanel p, @NotNull Project proj) {
        if (ApplicationManager.getApplication().isUnitTestMode() && schemaNotificationForTest == null) return;
        try {
            new Notification(
                    SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("panel.explain"),
                    MpSqlVisualizerBundle.message("panel.explain.cancelled"),
                    NotificationType.INFORMATION).notify(proj);
        } catch (Throwable ignored) {
        }
    }

    /**
     * R33：面板 EXPLAIN 双通道分派（自建执行链路线，批次 9 核实报告 §三 V41 定案）。
     *
     * <p>判定（{@code ExplainPanelChannel.decide}，纯逻辑）：开关开 ∧ 输出格式 ∈ {TABLE, JSON}
     * ∧ database 插件在场 ∧ 预览会话已绑数据源 → 结构化通道；开关关/其余方言（TEXT/XML，
     * 含 R23 Oracle 二段式与 SQL Server XML——不硬解析红线）→ 设计内直送（静默）；无数据源/
     * 无插件 → fail-closed 回退直送＋一条轻提示（不弹窗）。执行/渲染异常同样回退直送——
     * <b>结构化通道绝不成为 EXPLAIN 的唯一路径</b>。</p>
     */
    public static void submitExplainOrStructured(@NotNull SqlPreviewPanel p, @NotNull Project proj,
                                                 @NotNull cn.song.mybatisplus.visualizer.sql.Dialect dialect,
                                                 @NotNull String explainSql,
                                                 @NotNull ExecutionDialectGate.Request dialectGateRequest) {
        submitExplainOrStructured(p, proj, dialect, explainSql, dialectGateRequest, true, false);
    }

    /** Kotlin 默认参数等价重载：{@code rerendered=false}（T-DialectInferPollution W7 防循环旗标）。 */
    public static void submitExplainOrStructured(@NotNull SqlPreviewPanel p, @NotNull Project proj,
                                                 @NotNull cn.song.mybatisplus.visualizer.sql.Dialect dialect,
                                                 @NotNull String explainSql,
                                                 @NotNull ExecutionDialectGate.Request dialectGateRequest,
                                                 boolean allowDataSourcePrompt) {
        submitExplainOrStructured(p, proj, dialect, explainSql, dialectGateRequest, allowDataSourcePrompt, false);
    }

    /**
     * @param allowDataSourcePrompt T-UI030：重试路径禁止再弹数据源选择器（防「取消 → 再弹 → 再取消」循环）。
     * @param rerendered            T-DialectInferPollution W7：本链已自动重渲染过一次（防闸门重提交循环）。
     */
    public static void submitExplainOrStructured(@NotNull SqlPreviewPanel p, @NotNull Project proj,
                                                 @NotNull cn.song.mybatisplus.visualizer.sql.Dialect dialect,
                                                 @NotNull String explainSql,
                                                 @NotNull ExecutionDialectGate.Request dialectGateRequest,
                                                 boolean allowDataSourcePrompt,
                                                 boolean rerendered) {
        PluginSettings.State settings = PluginSettings.getInstance().getState();
        Object boundDs;
        if (explainBoundDsForTest != null) {
            boundDs = explainBoundDsForTest.get();
        } else {
            try {
                boundDs = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSource();
            } catch (Throwable t) {
                boundDs = null;
            }
        }
        // T-ExplainPwdPrompt W4（2026-10-24）：真绑定缺失且绑定链 runner 枚举被方言路由卡死
        // （数据源无 Dialects 映射时平台返回空，沙盒实测 Database 视图已连接仍 runners=0）时，
        // 回落到面板「数据源（上次使用）」记忆取源——结构化通道（askPassword=true）不依赖
        // 绑定链即可到达；歧义即弃，方言不符由既有 allowDialectGate 闸拦。
        if (boundDs == null) {
            try {
                boundDs = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.explainFallbackDataSource(
                        proj, p.sourceModuleKey, explainSql);
            } catch (Throwable t) {
                boundDs = null;
            }
        }
        // O-145 W7：结构化 EXPLAIN 不经过 SqlExecutor，必须在 JDBC 直连前用同一实际数据源闸门。
        if (boundDs != null && !cn.song.mybatisplus.visualizer.execute.DataSourceSelector.allowDialectGate(
                proj,
                dialectGateRequest,
                cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.fromDataSource(boundDs),
                verdict -> {
                    // T-DialectInferPollution W7（2026-10-24 用户真机报障）：渲染方言与目标数据源
                    // 不一致（如 last-used 在竞品测试后残留 dbx_test/PG，MySQL 预览被按 PG 渲染）时
                    // 闸门 AUTO_RERENDER_REQUIRED 重渲染成功——**自动重提交一次**，不再让用户重按
                    // 「执行计划」；重渲染失败或已重渲染过（防循环）回落既有红条提示。
                    if (verdict.status() == ExecutionDialectGate.Status.AUTO_RERENDER_REQUIRED
                            && !rerendered
                            && verdict.targetDialectName() != null
                            && rerenderForExecutionTarget(p, verdict.targetDialectName())) {
                        submitExplainOrStructured(p, proj, dialect, explainSql, dialectGateRequest,
                                false, true);
                    } else {
                        onExecutionDialectGate(p, verdict);
                    }
                },
                message -> p.resultPanel.showError(message),
                null)) return;
        cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat format =
                ExplainSupport.formatFor(dialect.getName());
        boolean databaseAvailable = explainDatabaseAvailableForTest != null
                ? explainDatabaseAvailableForTest.get()
                : cn.song.mybatisplus.visualizer.mcp.McpDbExecutor.isDatabaseAvailable();
        // R33 真机走查修复：编辑器文本就是回填链的终点产物（参数击键防抖重渲染），此处残留
        // `?`（串感知位点，字符串/注释里的 `?` 不计）即真未填——不下发结构化探测（字面 `?`
        // 送 JDBC = 语法错），按响亮失败回退并明示「存在未回填参数」
        boolean hasUnfilledParams = !PlaceholderPositionsKt.placeholderPositions(explainSql).isEmpty();
        cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.Decision verdict =
                cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.decide(
                        settings.getExplainStructuredRender(),
                        format,
                        databaseAvailable,
                        boundDs != null,
                        hasUnfilledParams);
        if (verdict instanceof cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.Decision.DirectSilent) {
            // T-UI030：开关关闭时不再只是「静默直送」——该方言确有结构化面且 Database 插件
            // 在场时给一条带「启用结构化并重试」动作的通知；TEXT/XML/Community 不提示（开启
            // 也没用，只会变成噪音）。不点动作时行为与改前逐字一致（仍直送原生控制台）。
            //
            // T-ExplainConsoleFirst W6（2026-10-24 用户实测「为什么我点击执行计划还是弹窗了」）：
            // 该推荐**只提示一次**。此前每次点执行计划都弹，用户顺手点过一次「启用结构化」后
            // 开关被永久写成 true，界面上再无任何「现在是实验形态」的提示——下次看到弹窗时
            // 完全无从归因。沙盒实测该配置确为 true、而代码默认是 false，差异只可能来自这里。
            if (cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.shouldOfferStructuredEnable(
                    settings.getExplainStructuredRender(), format, databaseAvailable)
                    && !settings.getExplainStructuredOfferedShown()) {
                settings.setExplainStructuredOfferedShown(true);
                notifyStructuredExplainDisabled(p, proj,
                        () -> enableStructuredExplainAndRetry(p, proj, dialect, explainSql, dialectGateRequest));
            }
            submitExplainDirect(p, proj, explainSql, dialectGateRequest);
        } else if (verdict instanceof cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.Decision.DirectFallback) {
            cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.Decision.DirectFallback fb =
                    (cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.Decision.DirectFallback) verdict;
            if (fb.reason() == cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.NO_DATA_SOURCE) {
                if (allowDataSourcePrompt) {
                    // T-UI030：真机反馈这条「已回退原生控制台」只是告知、没有下一步。改为直接弹
                    // 既有数据源选择器（与执行 SQL 同一个 runner 选择器，只绑定不执行任何 SQL），
                    // 选中后自动重试结构化；用户取消则只给说明，绝不再弹第二次。
                    promptDataSourceThenRetryStructured(p, proj, dialect, explainSql, dialectGateRequest);
                } else {
                    // 重试仍未绑定：只给现有回退说明，不再触发直送链（直送链会再次弹选择器）。
                    notifyExplainStructuredFallback(p, proj, fb.reason(), null);
                }
            } else if (fb.reason() ==
                    cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.UNFILLED_PARAMS) {
                // T-ExplainPwdPrompt W5（2026-10-24 用户沙盒实测 bug）：参数未回填**不回退直送**。
                // 回退直送在无绑定形态下必然失败且再弹一个误导性「未绑定数据源」红字框（用户明明
                // 有 last-used 数据源）；有绑定形态把字面 `?` 送 JDBC 同样是语法错（R33 同款结论）。
                // 通知已含原因与动作（填参数后重试），就地收口即可。
                notifyExplainStructuredFallback(p, proj, fb.reason(), null);
            } else {
                notifyExplainStructuredFallback(p, proj, fb.reason(), null);
                submitExplainDirect(p, proj, explainSql, dialectGateRequest);
            }
        } else if (verdict instanceof cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.Decision.Structured) {
            final Object ds = boundDs;
            final cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat outFormat = format;
            // 防御：判定与取值之间环境可能变化（数据源失效等）——缺一即回退，不硬解包
            if (ds == null || outFormat == null) {
                submitExplainDirect(p, proj, explainSql, dialectGateRequest);
                return;
            }
            p.resultPanel.showLoading("");
            final int generation = p.explainStructuredGeneration;
            // R90：提交时捕获原 SQL/历史条目/实际目标；回调不得再读 current 或当前数据源。
            cn.song.mybatisplus.visualizer.history.SqlHistoryManager history =
                    cn.song.mybatisplus.visualizer.history.SqlHistoryManager.getInstance();
            cn.song.mybatisplus.visualizer.history.ExplainPlanKey planKey =
                    cn.song.mybatisplus.visualizer.history.ExplainPlanKey.create(
                            cn.song.mybatisplus.visualizer.history.ExplainPlanKey.projectIdentity(proj.getBasePath()),
                            cn.song.mybatisplus.visualizer.execute.DataSourceSettings.getUniqueId(ds),
                            dialect.getName(), outFormat, explainSql);
            final cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Request historyRequest =
                    captureExplainPlanHistory(p, history, planKey, ds);
            final cn.song.mybatisplus.visualizer.history.SqlHistoryManager historyF = history;
            final cn.song.mybatisplus.visualizer.history.ExplainPlanKey planKeyF = planKey;
            final Project projF = proj;
            final String explainSqlF = explainSql;
            final ExecutionDialectGate.Request gateRequestF = dialectGateRequest;
            cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.executeStructured(
                    proj, ds, explainSql,
                    settings.getExecutionTimeoutSeconds(),
                    outcome -> {
                        if (Disposer.isDisposed(p) || generation != p.explainStructuredGeneration) {
                            return;
                        }
                        // 索引建议联动（ExplainIndexAdvisor 规则集的面板侧消费——只提示不执行；
                        // 建议器异常不连坐计划渲染，降级为无建议）
                        List<cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor.Hint> hints;
                        try {
                            hints = cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor.advise(
                                    dialect.getName(), outcome.raw().headers(), outcome.raw().rows());
                        } catch (Throwable t) {
                            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) throw (Error) t;
                            hints = List.of();
                        }
                        cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Completion historyCompletion =
                                completeExplainPlanHistory(p, historyF, historyRequest, outcome.raw());
                        if (historyCompletion.saved() != cn.song.mybatisplus.visualizer.history.ExplainPlanSaveResult.CLEARED_OR_REMOVED) {
                            captureAiContextPlan(p, historyF, planKeyF, outcome.raw());
                        }
                        List<cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Baseline> baselines =
                                historyCompletion.baselines();
                        cn.song.mybatisplus.visualizer.ui.ExplainPlanDiff.PlanDiff planDiff = baselines.isEmpty()
                                ? null
                                : ExplainPlanDiff.diff(baselines.get(0).raw(), outcome.raw(), outFormat);
                        // R61 兼容观察值；比较决策由 R90 的目标隔离会话负责。
                        p.lastStructuredExplain = new kotlin.Pair<>(explainSql, outcome.raw());
                        final ExplainPlanDialog[] dialogRef = {null};
                        ExplainPlanResultView view = new ExplainPlanResultView(
                                dialect.getName(),
                                outcome.dataSourceName(),
                                outcome.elapsedMs(),
                                outcome.raw(),
                                outFormat,
                                hints,
                                () -> {
                                    if (dialogRef[0] != null) dialogRef[0].close(0);
                                    submitExplainDirectConsole(p, projF, explainSqlF, gateRequestF);
                                },
                                planDiff,
                                baselines,
                                historyCompletion.saved());
                        ExplainPlanDialog dialog = new ExplainPlanDialog(projF, view);
                        dialogRef[0] = dialog;
                        ExplainPlanDialog.showExplainDialog(p, dialog);
                        p.resultPanel.showIdle();
                    },
                    errorText -> {
                        if (Disposer.isDisposed(p) || generation != p.explainStructuredGeneration) {
                            return;
                        }
                        // T-ExplainPwdPrompt（2026-10-24）：失败分型出口——认证失败不再回退直送
                        // （直送同通道必同样失败，徒增一次红字对话框）。
                        // T-ExplainPwdPrompt W6（用户沙盒实测 bug）：AUTH **也不弹红字模态框**——
                        // askPassword=true 后平台凭据链已弹原生「Connecting to ... + Change
                        // Credentials / Abort」对话框（失败与两个出口都在那里），用户点 Abort 后
                        // 异常以 SQLException: Access denied 形态冒泡回来，再弹红字框是二次打断
                        // （用户报障原话：「点终止还是出现执行计划的弹框」）。AUTH 改轻通知收口，
                        // 补存密码指引随通知走。
                        cn.song.mybatisplus.visualizer.execute.ExplainFailureKind kind =
                                cn.song.mybatisplus.visualizer.execute.ExplainPanelChannelKt
                                        .classifyExplainFailure(errorText);
                        if (kind == cn.song.mybatisplus.visualizer.execute.ExplainFailureKind.AUTH) {
                            p.resultPanel.showIdle();
                            notifyExplainAuthFailure(p, projF);
                        } else if (kind == cn.song.mybatisplus.visualizer.execute.ExplainFailureKind.WRONG_TARGET) {
                            // T-ExplainFailUx W1：选错源不弹模态、不回退直送（同通道必同样失败）——
                            // 就地横幅 + 「切换数据源」动作（换源后自动重试，W3）
                            p.resultPanel.showIdle();
                            showExplainWrongTarget(p, projF, errorText, explainSqlF, gateRequestF,
                                    () -> submitExplainDirectConsole(p, projF, explainSqlF, gateRequestF));
                        } else if (kind == cn.song.mybatisplus.visualizer.execute.ExplainFailureKind.CANCELLED) {
                            p.resultPanel.showIdle();
                            notifyExplainCancelled(p, projF);
                        } else {
                            notifyExplainStructuredFallback(p, projF, null, errorText);
                            submitExplainDirect(p, projF, explainSqlF, gateRequestF);
                        }
                    });
        }
    }

    /** T-PreviewSideQuery P3a：从执行视图回到预览——移除头部并重渲染预览 SQL。 */
    public static void backToPreview(@NotNull SqlPreviewPanel p) {
        if (p.executeViewHeader != null) {
            p.consoleHost.remove(p.executeViewHeader);
        }
        p.executeViewHeader = null;
        SqlPreviewPanelSqlViewKt.refreshView(p);
    }

    /** P3a：执行结果视图头部——明确当前不是预览编辑态，并提供显式「返回预览」。 */
    @NotNull
    private static JComponent buildExecuteViewHeader(@NotNull SqlPreviewPanel p) {
        JBPanel<JBPanel<?>> header = new JBPanel<JBPanel<?>>(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(JBUI.Borders.empty(2, 8));
        JBLabel hint = new JBLabel(MpSqlVisualizerBundle.message("panel.executeViewHint"));
        hint.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());
        header.add(hint, BorderLayout.WEST);
        JButton back = primaryButton(MpSqlVisualizerBundle.message("panel.backToPreview"), () -> backToPreview(p));
        back.setFont(buttonFont(p));
        header.add(back, BorderLayout.EAST);
        return header;
    }

    /**
     * T-UI030：无已绑数据源时<b>直接复用既有数据源选择/准备链</b>（与执行 SQL/执行计划同一个
     * runner 选择器），只绑定、不执行任何 SQL；绑定落地后自动重试结构化 EXPLAIN。
     *
     * <p>并发纪律：已有准备链在飞时<b>只等待</b>，不再走 chooseAndPrepare（预览准备是 PASS_THROUGH，
     * 会建第二条会话/控制台）。用户取消选择会把单飞位复位，轮询下一拍即收口为「不再弹第二次」。</p>
     */
    public static void promptDataSourceThenRetryStructured(
            @NotNull SqlPreviewPanel p,
            @NotNull Project proj,
            @NotNull cn.song.mybatisplus.visualizer.sql.Dialect dialect,
            @NotNull String explainSql,
            @NotNull ExecutionDialectGate.Request dialectGateRequest) {
        AtomicBoolean errorShown = new AtomicBoolean(false);
        // 代际快照：等待绑定期间用户可能切预览/再次点执行计划（SqlPreviewPanelSqlView 会递增）——
        // 过期等待不得把旧 SQL 的 EXPLAIN 提交到新预览上。
        int generation = p.explainStructuredGeneration;
        if (cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.shouldPromptForDataSource(
                cn.song.mybatisplus.visualizer.execute.DataSourceSelector.isPrepareInFlight())) {
            // 绑定用的 SQL 取面板当前预览原文（不是包了 EXPLAIN 的文本）：只写进控制台文档，
            // 不执行；真正的 EXPLAIN 由下面的重试提交，用户 SQL 不被改写。
            String previewSql = currentEditorSql(p) != null ? currentEditorSql(p) : explainSql;
            // executeAfterPrepare=false = 只准备/绑定，不提交任何语句（DML/DDL 零自动执行）。
            // 不传 dialectGateRequest：绑定与方言无关，门禁在重试的结构化提交里照旧生效。
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prepareConsole(
                    proj, previewSql, null, null,
                    message -> {
                        errorShown.set(true);
                        p.resultPanel.showError(message);
                    },
                    null, null, null, false);
        }
        awaitDataSourceBoundThenRetry(p, proj, dialect, explainSql, dialectGateRequest, errorShown, generation);
    }

    /**
     * T-UI030：等待既有/新建准备链把数据源绑定落地，然后重试一次结构化 EXPLAIN。
     * 收到取消/失败（单飞位复位）即停表并只给现有回退说明，绝不再弹选择器；
     * 重试路径传 {@code allowDataSourcePrompt=false}，即使仍未绑定也不会形成循环。
     */
    public static void awaitDataSourceBoundThenRetry(
            @NotNull SqlPreviewPanel p,
            @NotNull Project proj,
            @NotNull cn.song.mybatisplus.visualizer.sql.Dialect dialect,
            @NotNull String explainSql,
            @NotNull ExecutionDialectGate.Request dialectGateRequest,
            @NotNull AtomicBoolean errorShown,
            int generation) {
        awaitDataSourceBoundThen(p, proj, generation,
                () -> submitExplainOrStructured(p, proj, dialect, explainSql, dialectGateRequest, false),
                () -> {
                    // 选择失败（无数据源/驱动错误）已在面板行内显示既有错误文案；只有「用户取消」
                    // 这类没有错误文案的收口才补一条说明——两条提示不重复。
                    if (!errorShown.get()) notifyExplainDataSourceNotBound(p, proj);
                });
    }

    /** T-UI030：用户取消数据源选择（或等待未绑定）后的说明——如实说明「未执行」，并给出下一步。 */
    public static void notifyExplainDataSourceNotBound(@NotNull SqlPreviewPanel p, @NotNull Project proj) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            new Notification(
                    SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("panel.explain"),
                    MpSqlVisualizerBundle.message("panel.explain.structured.noDataSourceDeclined"),
                    NotificationType.INFORMATION).notify(proj);
        } catch (Throwable ignored) {
        }
    }

    /**
     * T-UI030：开关关闭通知里的一个可操作项（文案 key + 点击行为）。
     * 用「key + runnable」而非直接构造 NotificationAction（照 SqlTemplateErrorAction 先例）：
     * 让「挂了哪些动作、点了做什么」成为不依赖平台事件即可断言的纯逻辑。
     */
    public static final class StructuredExplainNotificationAction {
        public final String titleKey;
        public final Runnable run;

        public StructuredExplainNotificationAction(@NotNull String titleKey, @NotNull Runnable run) {
            this.titleKey = titleKey;
            this.run = run;
        }
    }

    /** T-UI030：开关关闭通知的动作清单——恒给「启用结构化并重试」一项。 */
    @NotNull
    public static List<StructuredExplainNotificationAction> structuredExplainDisabledActions(
            @NotNull Runnable onEnableAndRetry) {
        return List.of(new StructuredExplainNotificationAction(
                "panel.explain.structured.enableAndRetry", onEnableAndRetry));
    }

    /**
     * T-UI030：结构化开关关闭时的可操作通知——带「启用结构化并重试」动作。
     * 动作由纯函数 shouldOfferStructuredEnable 决定是否值得出现（TEXT/XML/Community 不提示）；
     * 动作回调只写该开关并重试，不改其它设置。
     */
    @NotNull
    public static Notification buildStructuredExplainDisabledNotification(@NotNull Runnable onEnableAndRetry) {
        Notification notification = new Notification(
                SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("panel.explain"),
                MpSqlVisualizerBundle.message("panel.explain.structured.disabled"),
                NotificationType.INFORMATION);
        for (StructuredExplainNotificationAction action : structuredExplainDisabledActions(onEnableAndRetry)) {
            notification.addAction(NotificationAction.createSimpleExpiring(
                    MpSqlVisualizerBundle.message(action.titleKey), action.run));
        }
        return notification;
    }

    public static void notifyStructuredExplainDisabled(@NotNull SqlPreviewPanel p, @NotNull Project proj,
                                                       @NotNull Runnable onEnableAndRetry) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            buildStructuredExplainDisabledNotification(onEnableAndRetry).notify(proj);
        } catch (Throwable ignored) {
        }
    }

    /**
     * T-UI030：「启用结构化并重试」动作——写设置（与设置页 apply 同一字段、同一变更主题）后
     * 立即重试一次 EXPLAIN。设置语义不变：默认仍关闭，只有用户显式点这个动作才打开。
     */
    public static void enableStructuredExplainAndRetry(
            @NotNull SqlPreviewPanel p,
            @NotNull Project proj,
            @NotNull cn.song.mybatisplus.visualizer.sql.Dialect dialect,
            @NotNull String explainSql,
            @NotNull ExecutionDialectGate.Request dialectGateRequest) {
        if (Disposer.isDisposed(p) || proj.isDisposed()) return;
        PluginSettings.getInstance().getState().setExplainStructuredRender(true);
        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged();
        submitExplainOrStructured(p, proj, dialect, explainSql, dialectGateRequest);
    }

    /**
     * R33：结构化通道回退轻提示（fail-closed 姿态的一部分——落回直送通道并告知原因，
     * 不弹窗打断）。{@code reason} 非 null = 环境性回退（无数据源/无插件）；null = 执行失败，
     * {@code detail} 携带驱动错误文本。
     */
    public static void notifyExplainStructuredFallback(
            @NotNull SqlPreviewPanel p,
            @NotNull Project proj,
            @Nullable cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason reason,
            @Nullable String detail) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            String why;
            if (reason == cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.NO_DATA_SOURCE) {
                why = MpSqlVisualizerBundle.message("panel.explain.structured.reason.noDataSource");
            } else if (reason == cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.NO_DATABASE_PLUGIN) {
                why = MpSqlVisualizerBundle.message("panel.explain.structured.reason.noDatabasePlugin");
            } else if (reason == cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel.FallbackReason.UNFILLED_PARAMS) {
                why = MpSqlVisualizerBundle.message("panel.explain.structured.reason.unfilledParams");
            } else {
                why = detail != null ? detail : "";
            }
            new Notification(
                    SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("panel.explain"),
                    MpSqlVisualizerBundle.message("panel.explain.structured.fallback", why),
                    NotificationType.INFORMATION).notify(proj);
        } catch (Throwable ignored) {
        }
    }

    /**
     * T-ExplainChainHardening T-ECH-01（O-48）：被包语句不可 EXPLAIN 时的轻通知（同
     * panel.explain.unsupported 形态——纯告知无需打断，不新增弹窗）。文案给出**判定依据**
     * （实际首关键字）而不只是结论，用户据此能自己改语句。
     */
    public static void notifyExplainNotExplainable(@NotNull SqlPreviewPanel p, @NotNull Project proj,
                                                   @Nullable String keyword) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            new Notification(
                    SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("panel.explain"),
                    MpSqlVisualizerBundle.message(
                            "panel.explain.notExplainable",
                            keyword != null ? keyword
                                    : MpSqlVisualizerBundle.message("panel.explain.notExplainable.unknown")),
                    NotificationType.INFORMATION).notify(proj);
        } catch (Throwable ignored) {
        }
    }

    /**
     * M36.5：原生控制台编辑器就绪统一处理——注入补全上下文、记录文档引用、
     * 释放自研编辑器（原生控制台占据右侧）。
     */
    public static void onNativeEditorReady(@NotNull SqlPreviewPanel p, @NotNull Editor consoleEditor) {
        SqlCompletionContext ctx = SqlPreviewPanelSqlViewKt.buildCompletionContext(p);
        consoleEditor.putUserData(SqlCompletionContext.KEY, ctx);
        SqlCompletionContributor.registerContext(consoleEditor.getDocument(), ctx);
        p.nativeEditorDoc = consoleEditor.getDocument();
        // T-DbSchemaCompletion：控制台就绪 = 数据源已 attach/建连，此刻的 schema 预热最容易命中
        //（预览打开常早于建连，那时 getModel() 还是 null）
        if (p.current != null) {
            SqlPreviewPanelSqlViewKt.prefetchSchemaForCompletion(p, p.current);
        }
        // T-DbSchemaCompletion：同一窗口下 JOIN 图也一样——首次富化空手（模型未加载）时补刷一次，
        // 否则那张图会永远停在实体注释上。只补一次：target 命中即置空。
        PreviewResult retryTarget = p.current;
        if (retryTarget != null && p.diagramEnrichRetryTarget == retryTarget) {
            p.diagramEnrichRetryTarget = null;
            SqlPreviewPanelResultViewKt.updateDiagramTab(p, retryTarget);
        }
        // T-AutoFold F5：登记控制台编辑器引用（折叠应用目标）；就绪即按当前文档内容应用一次
        // T-PreviewLineCommentDash O-134/D136：exec 文件同样被 DialectPreMapping 映射 MySQL，
        // 控制台编辑器一并打标接管；换装先把旧编辑器减计数（install/uninstall 各自幂等）
        if (p.nativeConsoleEditor != null && p.nativeConsoleEditor != consoleEditor) {
            EditorCommentShortcuts.uninstall(p.nativeConsoleEditor);
        }
        p.nativeConsoleEditor = consoleEditor;
        EditorCommentShortcuts.install(consoleEditor);
        // UI025：exec 文件在 .idea 下，daemon 不分析 → 编辑器级错列标注通道（与预览编辑器同源）。
        // install 内部首拍延迟 300ms/1500ms，覆盖 preparedEditor 与 schema 缓存晚到的窗口。
        cn.song.mybatisplus.visualizer.ui.annotate.PreviewSchemaHighlighter.install(consoleEditor);
        cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(
                consoleEditor,
                consoleEditor.getDocument().getText());
        if (p.editor != null) {
            SqlPreviewPanelEditorLifecycleKt.releaseEditor(p);
            p.editorHolder.removeAll();
        }
        // UI025：控制台编辑器的 ExternalAnnotator 需要「文档 → 已备控制台目标」身份，而
        // preparedEditor 在本回调返回后才由 prepareAfterEditorResolved 赋值。下一拍重启一次 daemon，
        // 让首轮 collect 就能读到目标（否则要等用户下一次输入才出现错列波浪线）。
        // 单元测试直接驱动 doHighlighting，不做全局 daemon 重启（避免拖慢/扰动其他用例）。
        if (!ApplicationManager.getApplication().isUnitTestMode()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                Project projNow = p.project;
                if (projNow != null && !projNow.isDisposed()) {
                    try {
                        // k2j：243 平台 ReadAction.compute 只剩 ThrowableComputable 形态（master Kotlin
                        // 泛型实参 <PsiFile?, Throwable> 同此），Computable 单参重载已不可用。
                        com.intellij.psi.PsiFile psi = com.intellij.openapi.application.ReadAction.compute(
                                (com.intellij.openapi.util.ThrowableComputable<com.intellij.psi.PsiFile, Throwable>) () ->
                                        com.intellij.psi.PsiDocumentManager.getInstance(projNow)
                                                .getPsiFile(consoleEditor.getDocument()));
                        if (psi != null) {
                            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(projNow).restart(psi);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, ModalityState.any());
        }
    }

    /**
     * M36.5：发起控制台默认准备（面板展示时调用，不执行）——
     * Ultimate 下原生控制台即刻可见（▶ 工具栏/Tx Auto/Playground 等 DBN 原生元素常驻预览面板）；
     * Community 下静默回退自研编辑器；无数据源静默引导（不弹错误打断预览）。
     *
     * <p>M36.5.25g 试过回退"预览即 DBN 控制台"——但用户明确要求**预览阶段也显示 DBN 工具栏**
     * （▶ 工具栏需始终可见），故恢复 prepareConsole 调用。</p>
     *
     * <p>T-RefreshDbSteal F4：兼作失联恢复入口 {@link #recoverConsole}——回退编辑器在场时以回退编辑器
     * 的文本发起准备（准备链复用共享控制台会同步该 SQL），面板无任何编辑器时静默跳过。</p>
     */
    public static void prepareDefaultConsole(@NotNull SqlPreviewPanel p) {
        Project proj = p.project;
        if (proj == null) return;
        if (p.editor == null || p.editor.getDocument().getText().isBlank()) return;
        String sql = p.editor.getDocument().getText();
        SqlExecutor.prepareConsole(
                proj, sql,
                consoleComponent -> {
                    // T-FirstExecResultInvisible F3：预览准备链显式 PREVIEW
                    embedConsole(p, consoleComponent, ConsoleEmbedMode.PREVIEW);
                    p.resultPanel.showEmpty();
                    // T-PreviewFast P7：请求→控制台可见（第三段锚点，用户感知的「预览快不快」）
                    try {
                        long req = SqlPreviewService.lastPreviewRequestMs;
                        if (req > 0) {
                            Logger.getInstance(SqlPreviewPanel.class)
                                    .info("[T-PreviewFast] request→console-visible: "
                                            + (System.currentTimeMillis() - req) + "ms");
                        }
                    } catch (Throwable ignored) {
                    }
                    // T-PreviewConsoleFmt / T-WSC-01：嵌入完成即按当前渲染文本回填一次；
                    // 此处回调**会写文档**（renderSql → showSql → document.setText），故必须用
                    // write-safe 模态（nonModal）。
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (Disposer.isDisposed(p)) return;
                        try {
                            SqlPreviewPanelSqlViewKt.renderSql(p);
                        } catch (Throwable e) {
                            Logger.getInstance(SqlPreviewPanel.class)
                                    .warn("[M36] post-attach re-render FAILED: " + e.getClass().getSimpleName()
                                            + ": " + e.getMessage());
                        }
                    }, ModalityState.nonModal());
                },
                consoleEditor -> onNativeEditorReady(p, consoleEditor),
                // 默认准备失败（无数据源等）静默——不打扰预览；执行时再走全链提示
                null,
                null);
    }

    /**
     * M36.4：将拼合的原生控制台 UI 组件内嵌到右侧 SQL 显示栏。
     *
     * <p>Kotlin 默认参数 {@code embedMode = ConsoleEmbedMode.EXECUTE} 的等价重载（面板测试缝
     * {@code embedConsoleForTest(component)} 即此形态）。</p>
     */
    public static void embedConsole(@NotNull SqlPreviewPanel p, @NotNull JComponent consoleComponent) {
        embedConsole(p, consoleComponent, ConsoleEmbedMode.EXECUTE);
    }

    /**
     * M36.4：将拼合的原生控制台 UI 组件内嵌到右侧 SQL 显示栏。
     * M36.5.39-A1：入口身份探针——{@code sameAsPrev=true} 判定方案 A 路线有效（DBN results refreshed
     * 后传入的是同一对象），false 则需升级 B 路线。
     * M36.5.39-A2：嵌入统一走持久 consoleHost（容器只此一份），DBN 拿走组件时只清空 host 子层，
     * editorHolder 显示链不断裂。
     * M36.5.39-A3：组件被 DBN 完全释放（parent==null）后由 hierarchy 监听自动重挂，不等轮询。
     *
     * <p>T-FirstExecResultInvisible F3：入参显式化——旧 {@code previewMode: Boolean} 一个布尔兼「预览」
     * 与「全链执行」两语义，全链执行换入也传 true 导致执行组件被折叠。归口 {@link ConsoleEmbedMode}：
     * EXECUTE 恒展开，PREVIEW 仅结果区无内容时折叠。</p>
     */
    public static void embedConsole(@NotNull SqlPreviewPanel p, @NotNull JComponent consoleComponent,
                                    @NotNull ConsoleEmbedMode embedMode) {
        int id = System.identityHashCode(consoleComponent);
        Integer prev = p.lastConsoleEmbeddedId;
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] embedConsole identity: id=" + id + ", prevEmbedded=" + prev
                        + ", sameAsPrev=" + (prev != null && prev == id)
                        + ", sameInstance=" + (consoleComponent == p.embeddedConsole)
                        + ", embedMode=" + embedMode);
        // O-96fix2：控制台**挂载即刷新**绑定指示行。S3 的刷新通道原本只收口在 renderSql 尾部，
        // 但「执行 SQL」路径挂载后不重渲染——指示行停留在挂载前的记忆态，须手动点刷新才升级
        // 「已绑定：X」。本方法是全挂载路径的单点钩子，在此补一拍刷新即覆盖执行/预览/复用三链。
        ApplicationManager.getApplication().invokeLater(() -> {
            if (Disposer.isDisposed(p)) return;
            try {
                SqlPreviewPanelParamPanelKt.refreshBindStateRow(p);
            } catch (Throwable e) {
                Logger.getInstance(SqlPreviewPanel.class)
                        .warn("[O-96fix2] bind-state refresh on embed FAILED: " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
            }
        }, ModalityState.any());
        if (consoleComponent == p.embeddedConsole) {
            // P3a：执行视图头部随 embedMode 幂等换装（EXECUTE 补挂 / PREVIEW 摘除）。
            if (embedMode == ConsoleEmbedMode.EXECUTE && p.executeViewHeader == null) {
                JComponent header = buildExecuteViewHeader(p);
                p.executeViewHeader = header;
                p.consoleHost.add(header, BorderLayout.NORTH);
            } else if (embedMode != ConsoleEmbedMode.EXECUTE && p.executeViewHeader != null) {
                p.consoleHost.remove(p.executeViewHeader);
                p.executeViewHeader = null;
            }
            // M36.5.47：同实例防抖时**不清空结果、不重挂**（防闪烁），但必须清除"执行中"
            // loading 态。M36.5.49：用 showIdle() 而非 showEmpty()——只清 loading/成功态，**保留错误态**。
            p.resultPanel.showIdle();
            // M36.5.77：execute 路径（embedMode=EXECUTE）即使同实例也要确保 divider 展开
            if (embedMode == ConsoleEmbedMode.EXECUTE && consoleComponent instanceof JSplitPane) {
                final JSplitPane sp = (JSplitPane) consoleComponent;
                // write-safe: UI-only（纯 Swing/只读，无文档写）
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (sp.getHeight() > 0) {
                        sp.setDividerLocation(0.6);
                        sp.revalidate();
                    }
                }, ModalityState.any());
            }
            return;
        }
        // T-RefreshDbSteal F3：旧组件的 rehost 监听必须显式解绑——共享控制台场景旧监听残活
        // 会把控制台抢回已废弃面板（真机 6 连抢实锤），detachConsole 内统一处理
        detachConsole(p);
        p.embeddedConsole = consoleComponent;
        p.lastConsoleEmbeddedId = id;
        // 去边框防白边（内嵌组件/容器可能自带 border）
        consoleComponent.setBorder(JBUI.Borders.empty());
        consoleComponent.setOpaque(false);
        // A2：host 挂入 editorHolder（clear/removeAll 后重挂，容器实例保持唯一）
        p.editorHolder.removeAll();
        p.editorHolder.add(p.consoleHost, BorderLayout.CENTER);
        p.consoleHost.removeAll();
        p.consoleHost.add(consoleComponent, BorderLayout.CENTER);
        if (embedMode == ConsoleEmbedMode.EXECUTE) {
            // P3a：执行结果视图明确标识 + 显式「返回预览」，避免用户只能靠「刷新」逃回编辑态。
            JComponent header = buildExecuteViewHeader(p);
            p.executeViewHeader = header;
            p.consoleHost.add(header, BorderLayout.NORTH);
        } else {
            p.executeViewHeader = null;
        }
        attachRehostListener(p, consoleComponent);
        if (consoleComponent instanceof JSplitPane) {
            final JSplitPane sp = (JSplitPane) consoleComponent;
            // M36.5.51：预览（execute=false）时也强制 divider 60%。invokeLater 下一拍组件已在树中。
            // write-safe: UI-only（纯 Swing/只读，无文档写）
            ApplicationManager.getApplication().invokeLater(() -> {
                if (sp.getHeight() > 0) {
                    // T-FirstExecResultInvisible F3：折叠归口 embedMode——EXECUTE 恒展开；
                    // PREVIEW 仅结果区无内容才折叠（竞态窗口内已有结果不吞）。
                    boolean collapse = embedMode.shouldCollapseDivider(
                            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.currentResultAreaHasContent());
                    sp.setDividerLocation(collapse ? 1.0 : 0.6);
                    sp.revalidate();
                    sp.repaint();
                }
                // M36.5.75：预览路径也安装 Output 控制台填充布局（幂等）
                cn.song.mybatisplus.visualizer.execute.DataSourceSelector
                        .installOutputHistoryFillLayout(sp, Logger.getInstance(SqlPreviewPanel.class));
            }, ModalityState.any());
            sp.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    // M36.6.2：无条件首触强制 60%——旧条件 getDividerLocation()<=0 在首次布局后恒 false。
                    if (sp.getHeight() < 200) return;
                    // T-FirstExecResultInvisible F3：同上归口 embedMode（触发时重探内容）
                    boolean collapse = embedMode.shouldCollapseDivider(
                            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.currentResultAreaHasContent());
                    sp.setDividerLocation(collapse ? 1.0 : 0.6);
                    sp.removeComponentListener(this);
                }
            });
        } else {
            // M36.5.25：顶部块（非 SplitPane，首次预览 executeAfterPrepare=false）——
            // 编辑器组件可能按 preferred=0 塌缩为 0 高，获尺寸后强制重排一次
            consoleComponent.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    consoleComponent.revalidate();
                    consoleComponent.repaint();
                    consoleComponent.removeComponentListener(this);
                }
            });
        }
        // M36.5.49：用 showIdle 而非 showEmpty——换入新控制台组件时若刚显示了 showError，showEmpty 会清掉错误。
        p.resultPanel.showIdle();
        p.revalidate();
        p.repaint();
    }

    /**
     * T-RefreshDbSteal F4：所有权判定——本面板记录的控制台实例是否仍在自己的 consoleHost 树内。
     * false = 控制台已被其他面板收养/或 DBN 释放后未重挂成功，此时任何"写入 nativeEditorDoc /
     * executePrepared"都是打在别人的控制台上（三症状公共前置条件）。
     */
    public static boolean consoleAttachedToMe(@NotNull SqlPreviewPanel p) {
        JComponent console = p.embeddedConsole;
        if (console == null) return false;
        return console.getParent() != null && p.consoleHost.isAncestorOf(console);
    }

    /**
     * T-RefreshDbSteal F4：失联恢复——共享控制台已不在本面板时重新走准备链把它要回来。
     * {@code prepareConsole} 的 onConsole 回调经 {@link #embedConsole} 幂等重嵌，准备链失败静默
     * （与 prepareDefaultConsole 同语义），本面板此时已落在回退编辑器上，显示不悬空。
     */
    public static void recoverConsole(@NotNull SqlPreviewPanel p, @NotNull String sqlForRecovery) {
        if (p.project == null) return;
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] recoverConsole triggered: len=" + sqlForRecovery.length()
                        + ", embedded=" + System.identityHashCode(p.embeddedConsole));
        prepareDefaultConsole(p);
    }

    /**
     * M36.5.39-A3：DBN 释放监听——仅当组件被完全释放（parent==null，DBN 不再持有）且仍是当前
     * 嵌入实例时调度重挂；parent!=null 表示 DBN 临时 reparent（results refreshed 中转），不抢。
     *
     * <p>M36.5.44 去抖修正：DBN 在 results refreshed 窗口内会**高频** reparent 整控台 SplitPane，
     * 若每次 parent==null 都立即重挂，会导致"重挂→DBN 拿走→重挂…"无限循环。加入最小重挂间隔
     * {@link SqlPreviewPanel#REHOST_MIN_INTERVAL_MS}：DBN 正忙于 reparent 时跳过，待它稳定后再挂回。</p>
     *
     * <p>T-RefreshDbSteal 两处修正：① 监听器引用保存到 rehostListener，detachConsole/dispose 显式移除；
     * ② 去抖 skip 不再静默丢弃：现调度单次延迟复查（REHOST_RETRY_DELAY_MS 后仍失联且仍是当前实例 → 幂等重挂）。</p>
     */
    public static void attachRehostListener(@NotNull SqlPreviewPanel p, @NotNull JComponent component) {
        HierarchyListener old = p.rehostListener;
        if (old != null) component.removeHierarchyListener(old);
        HierarchyListener listener = e -> {
            boolean parentChanged = (e.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0L;
            if (!parentChanged || e.getComponent().getParent() != null || e.getComponent() != p.embeddedConsole) return;
            long now = System.currentTimeMillis();
            long since = now - p.lastRehostAt;
            if (since >= 0 && since < SqlPreviewPanel.REHOST_MIN_INTERVAL_MS) {
                // T-RefreshDbSteal：去抖 skip 有日志 + 单次延迟复查兜底
                Logger.getInstance(SqlPreviewPanel.class)
                        .info("[M36] re-host debounced (since=" + since + "ms, id="
                                + System.identityHashCode(e.getComponent()) + ") → retry scheduled");
                if (!(e.getComponent() instanceof JComponent)) return;
                scheduleRehostRetry(p, (JComponent) e.getComponent());
                return;
            }
            p.lastRehostAt = now;
            Logger.getInstance(SqlPreviewPanel.class)
                    .info("[M36] console component released by DBN (id=" + System.identityHashCode(e.getComponent())
                            + ") → re-host (since=" + since + "ms)");
            // ModalityState.any()：模态弹窗 pump 期间重挂照常执行（纯 Swing 操作安全）
            // write-safe: UI-only（纯 Swing/只读，无文档写）
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!(e.getComponent() instanceof JComponent)) return;
                rehostConsoleForTest(p, (JComponent) e.getComponent());
            }, ModalityState.any());
        };
        p.rehostListener = listener;
        component.addHierarchyListener(listener);
    }

    /**
     * T-RefreshDbSteal：去抖窗口内被跳过的释放 → 延迟复查一次（仍失联则幂等重挂）。
     * 单发 Swing Timer（flashCopied 同款范式）：invokeLater 无延迟，复查会永远落在去抖
     * 窗口内被自己的 {@code since} 拦截，等于从未重试。
     */
    public static void scheduleRehostRetry(@NotNull SqlPreviewPanel p, @NotNull JComponent component) {
        if (p.rehostRetryScheduled) return;
        p.rehostRetryScheduled = true;
        javax.swing.Timer timer = new javax.swing.Timer((int) SqlPreviewPanel.REHOST_RETRY_DELAY_MS, e -> {
            p.rehostRetryScheduled = false;
            // 复查条件与监听器同款：仍是当前嵌入实例且仍无 parent（被别的容器收养则不抢）
            if (component != p.embeddedConsole || component.getParent() != null) return;
            long since = System.currentTimeMillis() - p.lastRehostAt;
            if (since >= 0 && since < SqlPreviewPanel.REHOST_MIN_INTERVAL_MS) return;
            p.lastRehostAt = System.currentTimeMillis();
            Logger.getInstance(SqlPreviewPanel.class)
                    .info("[M36] re-host retry fired (id=" + System.identityHashCode(component) + ", since=" + since + "ms)");
            rehostConsoleForTest(p, component);
        });
        timer.setRepeats(false);
        timer.start();
    }

    /**
     * M36.5.39-A3：重挂（同步逻辑抽出可单测）。仅接受当前嵌入实例且尚未挂回 host 的组件；
     * 返回 true = 已重挂。
     */
    public static boolean rehostConsoleForTest(@NotNull SqlPreviewPanel p, @NotNull JComponent component) {
        if (component != p.embeddedConsole || component.getParent() == p.consoleHost) return false;
        p.consoleHost.removeAll();
        p.consoleHost.add(component, BorderLayout.CENTER);
        p.consoleHost.revalidate();
        p.consoleHost.repaint();
        return true;
    }

    /**
     * M36：摘除已内嵌的控制台组件（清空预览时调用；JdbcConsole 生命周期由 IDE 管理）。
     * M36.5.39-A2：只清空 consoleHost 子层，容器保留——再次嵌入复用同一容器。
     * T-RefreshDbSteal：同时移除本面板挂接的 rehost 监听。
     */
    public static void detachConsole(@NotNull SqlPreviewPanel p) {
        JComponent embedded = p.embeddedConsole;
        if (embedded != null) {
            HierarchyListener l = p.rehostListener;
            if (l != null) embedded.removeHierarchyListener(l);
            p.consoleHost.remove(embedded);
        }
        // P3a：执行视图头部（返回预览）也属于控制台宿主层，摘除时一并清空。
        p.executeViewHeader = null;
        p.consoleHost.removeAll();
        // T-AutoFold F5：控制台组件摘除即清编辑器引用（防 EditorFactory 泄漏）
        // T-PreviewLineCommentDash O-134：摘除先减计数（install 的对称端点之一）
        if (p.nativeConsoleEditor != null) EditorCommentShortcuts.uninstall(p.nativeConsoleEditor);
        p.nativeConsoleEditor = null;
        p.embeddedConsole = null;
        p.rehostListener = null;
    }

    /**
     * 面板按钮字体（T-DevAcceptFix2 ⑨）：整数磅值派生的 UI 字体。**勿改回非整数**（旧 {@code 10.5f}
     * 经 Java2D 非整数度量路径渲染，字形发糊——用户对照工具栏按钮实测）；工具栏与小按钮同源取值。
     */
    public static java.awt.Font buttonFont(@NotNull SqlPreviewPanel p) {
        return p.getFont().deriveFont(11f);
    }

    public static JButton smallButton(@NotNull SqlPreviewPanel p, @NotNull String text, @NotNull Runnable action) {
        JButton button = new JButton(text);
        button.setFont(buttonFont(p));
        button.addActionListener(e -> action.run());
        return button;
    }

    /**
     * T-DmlImpactProminence：主行动按钮样式（蓝色实底）——用于「返回预览」「复制 SQL 并关闭」
     * 这类需要一眼看到的显式出口。
     *
     * <p>UI-043：**必须交给平台绘制**，不能手工涂底色。早期实现是 {@code setOpaque(true)} +
     * {@code setBackground} 填主题蓝，而 Darcula 按钮边框是**内缩 4px 的圆角**
     * （{@code DarculaButtonPainter.getOffset() == 4}）：底色按整个矩形铺满，圆角之外就多出
     * 一圈方形蓝边（用户截图所见）。</p>
     *
     * <p>平台的标准入口是 client property {@code JButton.styleDefault}
     * （= {@code DarculaButtonUI.DEFAULT_STYLE_KEY}）：{@code DarculaButtonPainter} 在
     * {@code paintBorder} 里认这个键，走原生 default 样式的圆角填充与配色。
     * 平台自身的 ButtonPanel、OnboardingDialogButtons 都是这么打的标。</p>
     */
    public static void applyPrimaryButtonStyle(@NotNull JButton button) {
        button.putClientProperty(
                com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI.DEFAULT_STYLE_KEY,
                java.lang.Boolean.TRUE);
        // 底色/前景交给 LAF；显式清掉，防止外层（如 resultPanel 主题覆盖）残留手工涂色
        button.setOpaque(false);
        button.setFocusPainted(false);
        button.repaint();
    }

    public static JButton primaryButton(@NotNull String text, @NotNull Runnable action) {
        JButton button = new JButton(text);
        applyPrimaryButtonStyle(button);
        button.addActionListener(e -> action.run());
        return button;
    }

    /** 压缩/多行切换按钮文案：显示"将要切换到的模式"。 */
    @NotNull
    public static String modeButtonText(@NotNull SqlPreviewPanel p) {
        return MpSqlVisualizerBundle.message(
                p.compactMode ? "panel.mode.multiline" : "panel.mode.singleline");
    }

    // R42：影响面预演的内联结果（不触碰编辑器/控制台）——正式承载已迁
    // SqlPreviewPanelParamPanelKt.showDmlImpactResult（T-DmlImpactProminence 起同时挂顶部横幅），
    // 本文件原最小版（逐次扫描 paramPanel 建标签、无横幅）已删除，调用点全部改指正式版。

    /** 绑定等待轮询间隔（EDT；只读判定，不碰 JDBC）。 */
    private static final int EXPLAIN_DS_BIND_POLL_MS = 250;

    /**
     * 轮询硬上限：120 拍 × 250ms = 30s（T-PerfSweep26-06 由 480 拍/2 分钟收紧）；
     * 只防定时器泄漏，正常收口由准备链终结（已绑定或取消）驱动。
     */
    private static final int EXPLAIN_DS_BIND_MAX_TICKS = 120;

    /** 数据源绑定等待（生产路径，T-PreviewSideQuery P1）：250ms 轮询 + 120 拍（30s）硬上限。 */
    public static void awaitDataSourceBoundThen(@NotNull SqlPreviewPanel p, @NotNull Project proj, int generation,
                                                @NotNull Runnable onBound, @NotNull Runnable onGiveUp) {
        int[] ticks = {0};
        javax.swing.Timer timer = new javax.swing.Timer(EXPLAIN_DS_BIND_POLL_MS, event -> {
            ticks[0]++;
            javax.swing.Timer t = (javax.swing.Timer) event.getSource();
            if (com.intellij.openapi.util.Disposer.isDisposed(p) || proj.isDisposed()) {
                t.stop();
                return;
            }
            // 预览已切换/又点了一次相关动作：这次等待与重试都已过期，静默停表即可。
            if (generation != p.explainStructuredGeneration) {
                t.stop();
                return;
            }
            boolean bound;
            try {
                bound = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSource() != null;
            } catch (Throwable ignored) {
                bound = false;
            }
            boolean inFlight = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.isPrepareInFlight();
            switch (cn.song.mybatisplus.visualizer.execute.ExplainFallbackGuidance.dataSourceBindTick(
                    bound, inFlight, ticks[0] >= EXPLAIN_DS_BIND_MAX_TICKS)) {
                case RETRY:
                    t.stop();
                    onBound.run();
                    break;
                case GIVE_UP:
                    t.stop();
                    onGiveUp.run();
                    break;
                case WAIT:
                    break;
            }
        });
        timer.setRepeats(true);
        timer.start();
    }


    /** R90：EXPLAIN 计划历史捕获入口（默认 still-current 判定——纯绑定对象查找，零连接操作）。 */
    public static cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Request captureExplainPlanHistory(
            @NotNull SqlPreviewPanel p,
            @NotNull cn.song.mybatisplus.visualizer.history.SqlHistoryManager history,
            @NotNull cn.song.mybatisplus.visualizer.history.ExplainPlanKey planKey,
            @NotNull Object target) {
        return captureExplainPlanHistory(p, history, planKey, target, () -> {
            // Pure bound-object lookup, not roster/last-used lookup and not a connection operation.
            Object bound;
            try {
                bound = cn.song.mybatisplus.visualizer.execute.DataSourceSelector.boundConsoleDataSource();
            } catch (Throwable t) {
                return false;
            }
            if (bound != target) return false;
            try {
                String id = cn.song.mybatisplus.visualizer.execute.DataSourceSettings.getUniqueId(bound);
                return id != null && id.equalsIgnoreCase(planKey.dataSourceUuid());
            } catch (Throwable t) {
                return false;
            }
        });
    }

    /** O-145/R90：EXPLAIN 计划历史捕获入口（retention 关时只建会话不补建历史条目）。 */
    public static cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Request captureExplainPlanHistory(
            @NotNull SqlPreviewPanel p,
            @NotNull cn.song.mybatisplus.visualizer.history.SqlHistoryManager history,
            @NotNull cn.song.mybatisplus.visualizer.history.ExplainPlanKey planKey,
            @NotNull Object target,
            @NotNull java.util.function.Supplier<Boolean> targetStillCurrent) {
        PreviewResult originalPreview = p.current;
        boolean retentionEnabled = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
                .getState().getExplainPlanRetentionEnabled();
        p.aiContextTarget = new SqlPreviewPanel.AiContextTarget(originalPreview, planKey, targetStillCurrent);
        // XML/日志纯文本预览没有经过 recordHistory；首次 EXPLAIN 复用同一历史域建条目。
        // 已有 ID 即使被用户删除也绝不补建，防删除后在途结果复活。
        if (retentionEnabled && originalPreview != null
                && originalPreview.getHistoryEntryId().isEmpty() && planKey.isPersistable()) {
            // Kotlin 命名参数形态的 Java 等价：无参构造 + 逐字段写入（其余字段取默认）
            cn.song.mybatisplus.visualizer.history.SqlHistoryEntry entry =
                    new cn.song.mybatisplus.visualizer.history.SqlHistoryEntry();
            entry.setSql(originalPreview.getSql());
            entry.setCompactSql(originalPreview.getCompactSql());
            entry.setTimestamp(System.currentTimeMillis());
            entry.setTableName(originalPreview.getTableName() != null ? originalPreview.getTableName() : "");
            entry.setProjectIdentity(planKey.projectIdentity());
            // T-PresetMethodScope：补建条目沿用当前预览的作用域，参数快照回落口径一致。
            entry.setOriginScopeKey(originalPreview.getOriginScopeKey());
            originalPreview.setHistoryEntryId(history.add(entry).getId());
        }
        return p.explainPlanHistorySession.begin(
                history, originalPreview != null ? originalPreview.getSql() : "", planKey, target,
                originalPreview != null ? originalPreview.getHistoryEntryId() : "", retentionEnabled);
    }

    /** 成功回调重新读取当前设置，不能使用提交前的 State 引用（期间可能 apply/loadState）。 */
    public static cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Completion completeExplainPlanHistory(
            @NotNull SqlPreviewPanel p,
            @NotNull cn.song.mybatisplus.visualizer.history.SqlHistoryManager history,
            @NotNull cn.song.mybatisplus.visualizer.history.ExplainPlanHistorySession.Request request,
            @NotNull cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult raw) {
        return p.explainPlanHistorySession.complete(history, request, raw,
                cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
                        .getState().getExplainPlanRetentionEnabled());
    }

    /** R91: retain an already obtained value snapshot in memory, never write history from the copy action. */
    public static void captureAiContextPlan(
            @NotNull SqlPreviewPanel p,
            @NotNull cn.song.mybatisplus.visualizer.history.SqlHistoryManager history,
            @NotNull cn.song.mybatisplus.visualizer.history.ExplainPlanKey key,
            @NotNull cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult raw) {
        p.aiContextPlan = new kotlin.Pair<>(history.explainPlanGeneration(),
                cn.song.mybatisplus.visualizer.history.ExplainPlanSnapshot.create(key, raw));
    }

}
