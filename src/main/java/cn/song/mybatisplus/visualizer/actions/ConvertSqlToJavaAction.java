package cn.song.mybatisplus.visualizer.actions;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator;
import cn.song.mybatisplus.visualizer.reverse.ReverseJoin;
import cn.song.mybatisplus.visualizer.reverse.ReverseWrapperFormat;
import cn.song.mybatisplus.visualizer.reverse.SqlReverseException;
import cn.song.mybatisplus.visualizer.reverse.SqlReverseResult;
import cn.song.mybatisplus.visualizer.reverse.SqlToJavaConverter;
import cn.song.mybatisplus.visualizer.ui.EntitySelectionDialog;
import cn.song.mybatisplus.visualizer.ui.JavaCodePreviewDialog;
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiClass;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SQL → MyBatis-Plus Java 代码 转换 Action（SQL 反向链路，P0 + T-M36.2/3 + T-M36.4 共享编排）。
 *
 * 触发方式（plugin.xml 注册）：编辑器右键菜单 "Convert SQL to MP Java Code" + 快捷键
 * `Alt+Shift+J`（Windows/Linux）/ `Cmd+Shift+J`（macOS）。
 *
 * 行为：取编辑器选择区文本 → SqlToJavaConverter 共享编排——解析 → 按设置形态后台 ReadAction
 * 查找实体 → 分发（无候选回落 QueryWrapper / 唯一候选直接生成 / 多候选 SqlSelectionDialog 选择）
 * → JavaCodePreviewDialog 预览（复制/关闭）。
 *
 * T-BatchSql：多语句（顶层分号拆分）走批量路径（错误隔离 + 单次 ReadAction 批量查找 + 拼接输出）。
 *
 * 与 ConvertToSqlAction（Wrapper→SQL 正向）互补。
 */
public final class ConvertSqlToJavaAction extends AnAction {

    /** 通知分组 id（与预览/转换链路同组）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";


    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) return;
        SelectionModel selectionModel = editor.getSelectionModel();
        if (!selectionModel.hasSelection()) {
            showNoSelection(project);
            return;
        }
        String text = selectionModel.getSelectedText();
        if (text == null) return;
        if (text.isBlank()) {
            showNoSelection(project);
            return;
        }

        // T-BatchSql：多语句走批量路径（顶层分号拆分）
        List<String> statements = SqlToJavaConverter.INSTANCE.splitStatements(text);
        if (statements.size() > 1) {
            convertBatch(project, statements);
            return;
        }

        // 1. SQL 解析（纯函数，EDT 安全）；T-Perf-20：失败改 WARNING 通知（非模态、非红色）——
        // 用户误选非 SQL 文本即触发，模态框过重；失败信息（含行号）仍完整可见
        SqlReverseResult result;
        try {
            result = SqlToJavaConverter.INSTANCE.parse(text);
        } catch (SqlReverseException ex) {
            com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("cn.song.mybatisplus.visualizer.preview")
                    .createNotification(
                            MpSqlVisualizerBundle.message("reverse.error.title"),
                            MpSqlVisualizerBundle.message(ex.getKey(), ex.getDetail()) + "（第 "
                                    + SqlToJavaConverter.INSTANCE.errorLine(text, ex.getOffset()) + " 行）",
                            com.intellij.notification.NotificationType.WARNING
                    )
                    .notify(project);
            return;
        }

        // 2. 实体查找（PSI 索引访问 → 后台 ReadAction）；QUERY 形态无需实体（跳过查找）
        ReverseWrapperFormat format = reverseFormat();
        if (format == ReverseWrapperFormat.QUERY) {
            showResult(project, result, Collections.emptyList(), format, Collections.emptyMap());
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final Pair<List<SqlToJavaConverter.EntityCandidate>, Map<String, List<SqlToJavaConverter.EntityCandidate>>>[] payloadHolder =
                    new Pair[1];
            try {
                payloadHolder[0] = ReadAction.compute(() -> {
                    List<SqlToJavaConverter.EntityCandidate> candidates =
                            SqlToJavaConverter.INSTANCE.findCandidates(project, result.tableName());
                    Map<String, List<SqlToJavaConverter.EntityCandidate>> subCandidates =
                            SqlToJavaConverter.INSTANCE.findSubEntityCandidates(project, result.joins());
                    return Pair.create(candidates, subCandidates);
                });
            } catch (Throwable t) {
                payloadHolder[0] = Pair.create(Collections.emptyList(), Collections.emptyMap());
            }
            ApplicationManager.getApplication().invokeLater(() ->
                    showResult(project, result, payloadHolder[0].getFirst(), format, payloadHolder[0].getSecond())
            );
        });
    }

    /**
     * T-BatchSql：批量转换（编辑器 Action 入口）。
     * 多语句时不弹实体选择弹窗，统一走自动推断 + 字符串列名回落；输出代码块到 JavaCodePreviewDialog。
     */
    private void convertBatch(@NotNull Project project, @NotNull List<String> statements) {
        List<SqlToJavaConverter.BatchItemResult> parseResults = SqlToJavaConverter.INSTANCE.parseBatch(statements);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final kotlin.Pair<Map<Integer, List<SqlToJavaConverter.EntityCandidate>>, Map<Integer, Map<String, List<SqlToJavaConverter.EntityCandidate>>>>[] pairHolder =
                    new kotlin.Pair[1];
            try {
                pairHolder[0] = ReadAction.compute(() -> SqlToJavaConverter.INSTANCE.findCandidatesBatch(project, parseResults));
            } catch (Throwable t) {
                pairHolder[0] = new kotlin.Pair<>(Collections.emptyMap(), Collections.emptyMap());
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                // 3. EDT 生成 + 拼接 + 弹窗（T-Perf-21：主路径补 disposed 守卫——后台查找期间项目可能已关闭）
                if (project.isDisposed()) return;
                ReverseWrapperFormat format = reverseFormat();
                String code = SqlToJavaConverter.INSTANCE.generateBatchCode(
                        parseResults, pairHolder[0].getFirst(), pairHolder[0].getSecond(), format, null,
                        reverseLogicDeleteValue(project));
                new JavaCodePreviewDialog(project, code).show();
            });
        });
    }

    /** 反向转换配置形态（测试环境 settings 未注册时默认 AUTO）。 */
    private ReverseWrapperFormat reverseFormat() {
        try {
            return ReverseWrapperFormat.fromPersisted(PluginSettings.getInstance().getState().getReverseWrapperFormat());
        } catch (Throwable ignored) {
            return ReverseWrapperFormat.AUTO;
        }
    }

    /**
     * T-ReverseGroupLogicDelete：逻辑删除「未删除」值（项目级优先 → 全局 → 兜底 "0"）——
     * 生成器据此识别 MP 依据 `@TableLogic` 自动注入的 `AND <列> = <值>` 并跳过。
     * 与正向注入 `effectiveLogicDeleteValue().ifBlank { "0" }` 同口径。
     */
    private String reverseLogicDeleteValue(@NotNull Project project) {
        try {
            String value = ProjectPluginSettings.getInstance(project).effectiveLogicDeleteValue();
            if (value != null && !value.isBlank()) return value;
        } catch (Throwable ignored) {
        }
        return SqlToJavaConverter.DEFAULT_LOGIC_DELETE_VALUE;
    }

    /** 手动选择实体类开关（测试环境 settings 未注册时默认开）。 */
    private boolean manualEntitySelectionEnabled() {
        try {
            return PluginSettings.getInstance().getState().getReverseManualEntitySelection();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 结果分发：0 候选且开关开 → 手动选择弹窗；
     * JOIN 且开关开 → 手动选择弹窗；否则：无候选回落 QueryWrapper；唯一候选直接生成；多候选选择后生成。
     */
    private void showResult(
            @NotNull Project project,
            @NotNull SqlReverseResult result,
            @NotNull List<SqlToJavaConverter.EntityCandidate> candidates,
            @NotNull ReverseWrapperFormat format,
            @NotNull Map<String, List<SqlToJavaConverter.EntityCandidate>> subCandidates) {
        if (manualEntitySelectionEnabled() && (!result.joins().isEmpty() || candidates.isEmpty())) {
            List<EntitySelectionDialog.TableRow> rows = new ArrayList<>();
            rows.add(new EntitySelectionDialog.TableRow(
                    EntitySelectionDialog.MAIN_KEY,
                    MpSqlVisualizerBundle.message("dialog.selectEntity.mainTable") + " " + result.tableName(),
                    candidates.stream().map(SqlToJavaConverter.EntityCandidate::psiClass).collect(Collectors.toList()),
                    result.tableName()
            ));
            for (ReverseJoin join : result.joins()) {
                String alias = join.alias();
                if (alias != null) {
                    List<SqlToJavaConverter.EntityCandidate> subs = subCandidates.get(alias);
                    List<PsiClass> subClasses = subs != null
                            ? subs.stream().map(SqlToJavaConverter.EntityCandidate::psiClass).collect(Collectors.toList())
                            : Collections.emptyList();
                    rows.add(new EntitySelectionDialog.TableRow(
                            alias,
                            MpSqlVisualizerBundle.message("dialog.selectEntity.joinTable") + " " + alias + " " + join.tableName(),
                            subClasses,
                            join.tableName()
                    ));
                }
            }
            new EntitySelectionDialog(project, rows, (Map<String, PsiClass> selections) -> {
                // T-Perf-08：实体确认回调解析移后台——candidateOf 的 EntityResolver.resolve 遍历
                // 继承链全部字段 + 注解 resolve，大实体耗时随字段数线性，EDT 直解析构成确认停顿；
                // 后台 ReadAction 解析后回 EDT 展示代码预览（对齐上方 convert 的既有异步模式）。
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    final kotlin.Pair<SqlToJavaConverter.EntityCandidate, Map<String, JavaCodeGenerator.EntityTarget>>[] resolvedHolder =
                            new kotlin.Pair[1];
                    resolvedHolder[0] = ReadAction.compute(() -> {
                        PsiClass mainPsi = selections.get(EntitySelectionDialog.MAIN_KEY);
                        SqlToJavaConverter.EntityCandidate main = mainPsi != null
                                ? SqlToJavaConverter.INSTANCE.candidateOf(mainPsi) : null;
                        Map<String, JavaCodeGenerator.EntityTarget> subEntities = new HashMap<>();
                        for (ReverseJoin join : result.joins()) {
                            String alias = join.alias();
                            if (alias == null) continue;
                            PsiClass psi = selections.get(alias);
                            if (psi != null) {
                                JavaCodeGenerator.EntityTarget sub = toSubEntity(
                                        SqlToJavaConverter.INSTANCE.candidateOf(psi), reverseLogicDeleteValue(project));
                                if (sub != null) subEntities.put(alias, sub);
                            }
                        }
                        return new kotlin.Pair<>(main, (Map<String, JavaCodeGenerator.EntityTarget>) subEntities);
                    });
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) return;
                        SqlToJavaConverter.EntityCandidate main = resolvedHolder[0].getFirst();
                        Map<String, JavaCodeGenerator.EntityTarget> subEntities = resolvedHolder[0].getSecond();
                        if (main != null) {
                            showCodeDialog(project, result, main, format, subEntities);
                        } else {
                            // 主表未指定实体 → 回落字符串列名形态
                            new JavaCodePreviewDialog(project,
                                    SqlToJavaConverter.INSTANCE.generateCode(result, null, format, null, Collections.emptyMap())).show();
                        }
                    });
                });
            }).show();
            return;
        }
        if (candidates.isEmpty()) {
            new JavaCodePreviewDialog(project,
                    SqlToJavaConverter.INSTANCE.generateCode(result, null, format, null, Collections.emptyMap())).show();
        } else if (candidates.size() == 1) {
            showCodeDialog(project, result, candidates.get(0), format);
        } else {
            // 摘要/行文本为纯数据（PSI 已在后台解析），EDT 直接构造
            List<SqlSelectionDialog.Row> displayTexts = candidates.stream().map(c ->
                    SqlSelectionDialog.Row.of(
                            c.psiClass().getName() + "（表 " + c.tableInfo().getTableName() + "）",
                            cn.song.mybatisplus.visualizer.ui.SqlSelectionDialogKt.entityCandidateSubtitle(c.psiClass())
                    )
            ).collect(Collectors.toList());
            new SqlSelectionDialog<>(project, candidates, displayTexts, (SqlToJavaConverter.EntityCandidate selected) -> showCodeDialog(project, result, selected, format)).show();
        }
    }

    /** 手动选择实体 → 生成用副实体目标（类名缺失跳过；Kotlin 默认参重载，logicDeleteValue = 默认值）。 */
    private JavaCodeGenerator.EntityTarget toSubEntity(SqlToJavaConverter.EntityCandidate candidate) {
        return toSubEntity(candidate, SqlToJavaConverter.DEFAULT_LOGIC_DELETE_VALUE);
    }

    /** 手动选择实体 → 生成用副实体目标（类名缺失跳过）。 */
    private JavaCodeGenerator.EntityTarget toSubEntity(SqlToJavaConverter.EntityCandidate candidate, String logicDeleteValue) {
        String className = candidate.psiClass().getName();
        if (className == null) return null;
        Map<String, String> columnToField = new HashMap<>();
        for (ColumnInfo col : candidate.tableInfo().getColumns()) {
            columnToField.put(col.getColumnName(), col.getFieldName());
        }
        return new JavaCodeGenerator.EntityTarget(className, columnToField, Collections.emptyMap(),
                candidate.tableInfo().getLogicDeleteColumn(), logicDeleteValue);
    }

    /** 生成并展示代码（形态由配置分发）。 */
    private void showCodeDialog(
            @NotNull Project project,
            @NotNull SqlReverseResult result,
            @NotNull SqlToJavaConverter.EntityCandidate candidate,
            @NotNull ReverseWrapperFormat format) {
        showCodeDialog(project, result, candidate, format, Collections.emptyMap());
    }

    private void showCodeDialog(
            @NotNull Project project,
            @NotNull SqlReverseResult result,
            @NotNull SqlToJavaConverter.EntityCandidate candidate,
            @NotNull ReverseWrapperFormat format,
            @NotNull Map<String, JavaCodeGenerator.EntityTarget> subEntities) {
        String code = SqlToJavaConverter.INSTANCE.generateCode(result, candidate, format, null, subEntities,
                reverseLogicDeleteValue(project));
        new JavaCodePreviewDialog(project, code).show();
    }

    private void showNoSelection(@NotNull Project project) {
        // T-UiNative T-UN-02：未选中文本提示改轻通知（原模态弹窗——纯告知无需打断）；
        // update() 已拦可见性，本路径为竞态兜底。测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）。
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            new com.intellij.notification.Notification(
                    NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("plugin.name"),
                    MpSqlVisualizerBundle.message("reverse.error.noSelection"),
                    com.intellij.notification.NotificationType.INFORMATION
            ).notify(project);
        } catch (Throwable ignored) {
            // runCatching 等价：通知链路失败不影响动作主链
        }
    }

    // 仅访问编辑器选择区（UI 状态，非 PSI）→ EDT 更新线程安全且无慢操作风险
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        SelectionModel selection = editor != null ? editor.getSelectionModel() : null;
        String sel = selection != null ? selection.getSelectedText() : null;
        e.getPresentation().setEnabledAndVisible(
                selection != null && selection.hasSelection() && sel != null && !sel.isBlank());
    }

    /** 插件动作 ID（plugin.xml 中注册）。 */
    public static final String ACTION_ID = "cn.song.mybatisplus.visualizer.ConvertSqlToJava";
}
