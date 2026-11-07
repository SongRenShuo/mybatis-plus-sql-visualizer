package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.function.Consumer;

/**
 * SQL 执行引擎（M36，反射隔离）：弹出原生数据源选择 → Database 控制台内嵌执行。
 *
 * M36.5 执行链拆分：
 *  - prepareConsole：准备控制台（选数据源 → 建 session → attachConsole → 拼合组件回调），不执行。
 *  - executePrepared：在已准备的控制台上执行；未就绪返回 false（调用方回退全链）。
 *  - execute：准备 + 执行合一（原语义，等价原生 Ctrl+Enter）。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
public final class SqlExecutor {

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** UI023：headless 下截获真实执行入口边界，避免靠 Community 不可执行制造「零提交」假绿。 */
    public interface ExecutionAttempt {
        void attempt(@Nullable String sql);
    }

    public static volatile ExecutionAttempt executionAttemptForTest = null;

    /** 准备 + 执行合一（M36 原语义）：选数据源 → 建控制台 → 直接执行（Kotlin 默认参 {@code onErrorRich = null} 形态）。 */
    public static void execute(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Object dataSource,
            boolean isQuery,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError) {
        execute(project, sql, dataSource, isQuery, onConsole, onEditor, onError, null);
    }

    /** 准备 + 执行合一（M36 原语义，T-ErrorLocate 富错误通道重载）：选数据源 → 建控制台 → 直接执行（Kotlin 默认参 {@code wholeScriptExec = false} 形态）。 */
    public static void execute(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Object dataSource,
            boolean isQuery,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich) {
        execute(project, sql, dataSource, isQuery, onConsole, onEditor, onError, onErrorRich, false);
    }

    /** 准备 + 执行合一（O-64：wholeScriptExec 重载——identity 包装产物整批直跑）。 */
    public static void execute(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Object dataSource,
            boolean isQuery,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
        execute(project, sql, dataSource, isQuery, onConsole, onEditor, onError, onErrorRich, wholeScriptExec,
                null, null, null, false);
    }

    /** 全参形态：方言闸 + 提交回执 + forceChoose（T-DialectGateUx / UI023）。 */
    public static void execute(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Object dataSource,
            boolean isQuery,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            @Nullable Consumer<ExecutionSubmission> onSubmission,
            boolean forceChoose) {
        execute(project, sql, dataSource, isQuery, onConsole, onEditor, onError, onErrorRich, wholeScriptExec,
                dialectGateRequest, onDialectGate, onSubmission, forceChoose, null, null);
    }

    /**
     * 全参形态 + 结构化提示 / 中性信息通道（UI-049 / T8）。
     *
     * @param onDialectMismatchHint UI-049：空 Runner「方言不匹配」态结构化提示（与 {@code onError} 互斥；null = 既有红字文案）。
     * @param onInfo                T8：中性信息回调（工具语句/更新语句成功完成时面板 showStatus，不走红条错误通道）。
     */
    public static void execute(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Object dataSource,
            boolean isQuery,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            @Nullable Consumer<ExecutionSubmission> onSubmission,
            boolean forceChoose,
            @Nullable Consumer<DialectMismatchHint> onDialectMismatchHint,
            @Nullable Consumer<String> onInfo) {
        ExecutionAttempt attempt = executionAttemptForTest;
        if (attempt != null) {
            attempt.attempt(sql);
            return;
        }
        DataSourceSelector.selectAndExecute(project, sql, onConsole, onEditor, onError,
                success -> {
                    if (!Boolean.TRUE.equals(success)) {
                        // Community 或失败时提示
                        if (!ExecuteSqlSupport.isAvailable()) {
                            showInfo(project, MpSqlVisualizerBundle.message("execute.noDatabase"));
                        }
                    }
                },
                onErrorRich, wholeScriptExec, dialectGateRequest, onDialectGate, onSubmission, forceChoose,
                onDialectMismatchHint, onInfo);
    }

    /** M36.5：准备控制台（不执行）。Community 下静默失败（callback(false)）。 */
    public static void prepareConsole(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback) {
        if (!ExecuteSqlSupport.isAvailable()) {
            // 社区版：静默回退（不弹提示——自研编辑器已是回退态）
            if (callback != null) callback.accept(false);
            return;
        }
        DataSourceSelector.prepareConsole(project, sql, onConsole, onEditor, onError, callback, null, null, false);
    }

    public static void prepareConsole(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            boolean forceChoose) {
        prepareConsole(project, sql, onConsole, onEditor, onError, callback, dialectGateRequest, onDialectGate,
                forceChoose, null);
    }

    /** UI-049：空 Runner「方言不匹配」态结构化提示（与 {@code onError} 互斥；null = 既有行为）。 */
    public static void prepareConsole(
            @NotNull Project project,
            @NotNull String sql,
            @Nullable Consumer<JComponent> onConsole,
            @Nullable Consumer<Editor> onEditor,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<Boolean> callback,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            boolean forceChoose,
            @Nullable Consumer<DialectMismatchHint> onDialectMismatchHint) {
        if (!ExecuteSqlSupport.isAvailable()) {
            if (callback != null) callback.accept(false);
            return;
        }
        DataSourceSelector.prepareConsole(project, sql, onConsole, onEditor, onError, callback,
                dialectGateRequest, onDialectGate, forceChoose, null, false, onDialectMismatchHint);
    }

    /** M36.5：在已准备的控制台上执行（Kotlin 默认参 {@code onErrorRich = null} 形态）。 */
    public static boolean executePrepared(
            @NotNull Project project,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError) {
        return executePrepared(project, onResultsReady, onError, null);
    }

    /** M36.5：在已准备的控制台上执行（T-ErrorLocate 富错误通道重载，Kotlin 默认参 {@code wholeScriptExec = false} 形态）。 */
    public static boolean executePrepared(
            @NotNull Project project,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich) {
        return executePrepared(project, onResultsReady, onError, onErrorRich, false, null, null, null);
    }

    /**
     * M36.5：在已准备的控制台上执行。
     *
     * @param wholeScriptExec O-64：identity 包装产物整批直跑（ExecOption 覆写为
     *                        EXECUTE_INSIDE_WHOLE_SCRIPT/EXECUTE_OUTSIDE_WHOLE_SCRIPT，
     *                        原生 Statements 选择弹层不再弹出、整批全部执行）；普通执行传默认 false
     */
    public static boolean executePrepared(
            @NotNull Project project,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec) {
        return executePrepared(project, onResultsReady, onError, onErrorRich, wholeScriptExec, null, null, null);
    }

    /** 全参形态：方言闸 + 提交回执（UI023 / O-145 W7）。 */
    public static boolean executePrepared(
            @NotNull Project project,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            @Nullable Consumer<ExecutionSubmission> onSubmission) {
        return executePrepared(project, onResultsReady, onError, onErrorRich, wholeScriptExec, dialectGateRequest,
                onDialectGate, onSubmission, null);
    }

    /** 全参 + 中性信息通道（T8）：工具语句成功完成时面板 showStatus，不走红条错误通道。 */
    public static boolean executePrepared(
            @NotNull Project project,
            @Nullable Consumer<JComponent> onResultsReady,
            @Nullable Consumer<String> onError,
            @Nullable Consumer<ExecutionErrorInfo> onErrorRich,
            boolean wholeScriptExec,
            @Nullable ExecutionDialectGate.Request dialectGateRequest,
            @Nullable Consumer<ExecutionDialectGate.Verdict> onDialectGate,
            @Nullable Consumer<ExecutionSubmission> onSubmission,
            @Nullable Consumer<String> onInfo) {
        if (!ExecuteSqlSupport.isAvailable()) return false;
        ExecutionAttempt attempt = executionAttemptForTest;
        if (attempt != null) {
            attempt.attempt(null);
            return true;
        }
        final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        final Consumer<ExecutionSubmission> receipt = outcome -> {
            if (completed.compareAndSet(false, true) && onSubmission != null) {
                onSubmission.accept(outcome);
            }
        };
        return DataSourceSelector.executePrepared(project, onResultsReady,
                onSubmission == null ? onError : message -> {
                    receipt.accept(ExecutionSubmission.BLOCKED);
                    if (onError != null) onError.accept(message);
                },
                onSubmission == null ? onErrorRich : onErrorRich == null ? null : info -> {
                    receipt.accept(ExecutionSubmission.BLOCKED);
                    onErrorRich.accept(info);
                },
                wholeScriptExec, dialectGateRequest, onDialectGate,
                onSubmission == null ? null : receipt, onInfo);
    }

    /** 控制台是否已就绪（prepareConsole 成功后 true）。 */
    public static boolean isPrepared() {
        return ExecuteSqlSupport.isAvailable() && DataSourceSelector.isPrepared();
    }

    /** 释放已准备控制台引用（面板清空/关闭时调用）。 */
    public static void clearPrepared() {
        DataSourceSelector.clearPrepared();
    }

    /** T-Perf-16：面板 dispose 释放全部单例滞留引用（prepared/shared/pendingOnConsole）。 */
    public static void releaseForPanel() {
        DataSourceSelector.releaseForPanel();
    }

    /**
     * T-UiNative T-UN-02：Community 降级提示改轻通知（原模态弹窗——纯告知无需打断）。
     * 测试模式跳过 + runCatching 兜底（headless 无通知系统依赖，原 Messages 亦被 runCatching 吞掉）。
     */
    private static void showInfo(@NotNull Project project, @NotNull String message) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            new Notification(
                    NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("execute.title"),
                    message,
                    NotificationType.INFORMATION
            ).notify(project);
        } catch (Throwable ignored) {
            // headless 无通知系统依赖，与原 runCatching 等价
        }
    }
}
