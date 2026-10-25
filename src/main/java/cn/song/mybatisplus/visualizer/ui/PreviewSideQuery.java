// 自 master ui/PreviewSideQuery.kt 移植（T-PreviewSideQuery P1/P2：预览面板只读旁路查询）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate;
import cn.song.mybatisplus.visualizer.mcp.McpDbExecutor;
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter;
import cn.song.mybatisplus.visualizer.mcp.McpSelectGuard;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * 预览面板只读旁路查询（T-PreviewSideQuery P1/P2）。
 *
 * <p>走 {@link McpDbExecutor#executeQuery} 这条既有只读通道（结构化 EXPLAIN 已在使用），
 * <b>不调用</b> {@code SqlExecutor.execute} / {@code embedConsole}，因此不会覆盖预览编辑器所在的
 * 共享控制台文档，也不会让 {@code currentEditorSql()} 漂移到试算/EXPLAIN 语句。</p>
 *
 * <p>前置检查与生产执行链一致：SELECT 探针先过 {@link McpSelectGuard}，再按
 * {@link ExecutionDialectGate} 对绑定数据源做 fail-closed 方言校验；通过后才在池线程执行，
 * EDT 回调结果。</p>
 */
public final class PreviewSideQuery {

    private PreviewSideQuery() {
    }

    public sealed interface Result permits Scalar, Table, Blocked, Failed {
    }

    /** 单值结果（预估影响行数）。 */
    public record Scalar(long value, @NotNull McpExplainFormatter.RawResult raw) implements Result {
    }

    /** 表格结果（EXPLAIN 直送等）。 */
    public record Table(@NotNull McpExplainFormatter.RawResult raw) implements Result {
    }

    /**
     * 只读护栏/方言闸阻断；{@code message} 可直接展示。
     *
     * @param verdict 方言闸判定（只读护栏阻断时为 null）——调用方据此区分「方言冲突」
     *                与其它阻断，并决定是否给「按目标方言重渲染 / 切换数据源」出口。
     */
    public record Blocked(@NotNull String message, @Nullable ExecutionDialectGate.Verdict verdict)
            implements Result {
    }

    /** 执行失败；{@code message} 可直接展示。 */
    public record Failed(@NotNull String message) implements Result {
    }

    /** 测试缝回调形态（Kotlin 五参 lambda 的 Java 承载）。 */
    public interface TestRunner {
        void run(@NotNull Project project, @NotNull Object dataSource, @NotNull String sql,
                 boolean scalar, @NotNull Consumer<Result> onResult);
    }

    /**
     * 测试缝：headless 无真库/原生控制台；生产为 null 走真链。
     * 回调在调用线程执行，测试可直接同步给结果。
     */
    public static volatile TestRunner runnerForTest = null;

    /** Kotlin 默认参数等价重载：{@code requireSelectGuard=true}、{@code channelNote=null}。 */
    public static void run(@NotNull Project project, @NotNull Object dataSource, @NotNull String sql,
                           @NotNull ExecutionDialectGate.Request request, boolean scalar,
                           @NotNull Consumer<Result> onResult) {
        run(project, dataSource, sql, request, scalar, true, null, onResult);
    }

    /** Kotlin 默认参数等价重载：{@code channelNote=null}。 */
    public static void run(@NotNull Project project, @NotNull Object dataSource, @NotNull String sql,
                           @NotNull ExecutionDialectGate.Request request, boolean scalar,
                           boolean requireSelectGuard, @NotNull Consumer<Result> onResult) {
        run(project, dataSource, sql, request, scalar, requireSelectGuard, null, onResult);
    }

    /**
     * @param request 渲染来源/意图/是否可重渲染——与生产执行链同一份事实。
     * @param scalar  true=只取首行首列数字（COUNT 试算）；false=返回整表（EXPLAIN）。
     * @param requireSelectGuard true=先过 MCP 只读护栏（COUNT 探针）；EXPLAIN 走自己的
     *        ExplainGate/方言闸，不需要 SELECT 护栏。
     */
    public static void run(@NotNull Project project, @NotNull Object dataSource, @NotNull String sql,
                           @NotNull ExecutionDialectGate.Request request, boolean scalar,
                           boolean requireSelectGuard, @Nullable String channelNote,
                           @NotNull Consumer<Result> onResult) {
        if (requireSelectGuard) {
            McpSelectGuard.Verdict guard = McpSelectGuard.checkSingleSelect(sql);
            if (!guard.allowed()) {
                onResult.accept(new Blocked(
                        guard.reason() != null ? guard.reason()
                                : guard.errorCode() != null ? guard.errorCode()
                                : "read-only guard blocked",
                        null));
                return;
            }
        }
        ExecutionDialectGate.Target target = quietly(() -> ExecutionDialectGate.fromDataSource(dataSource));
        ExecutionDialectGate.Verdict verdict = ExecutionDialectGate.evaluate(request,
                target != null ? target.dialectName() : null);
        if (verdict.status() != ExecutionDialectGate.Status.MATCH) {
            onResult.accept(new Blocked(DataSourceSelector.dialectGateMessage(verdict), verdict));
            return;
        }
        TestRunner testRunner = runnerForTest;
        if (testRunner != null) {
            testRunner.run(project, dataSource, sql, scalar, onResult);
            return;
        }
        int timeoutSeconds = PluginSettings.getInstance().getState().getExecutionTimeoutSeconds();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            McpExplainFormatter.RawResult outcome = null;
            Throwable error = null;
            try {
                // T-PreviewSideQuery：`executeOnPooledThread` 不携带 Job/ProgressIndicator，而
                // DatabaseConnectionManager.Builder.createBlocking 明确要求当前线程有进度上下文
                // （headless 实测：no ProgressIndicator or Job, the current job is not cancellable）。
                // 真机 IDE 的池线程恰好有上下文，测试/纯后台调用没有——给一次空指示器让只读查询
                // 在任意后台上下文可执行；查询本身不读取消状态（只读短查询，超时由驱动护栏负责）。
                // T-PreviewChainNoise W2（2026-10-23 订正）：上文「真机 IDE 的池线程恰好有上下文」
                // 已被用户真机日志推翻——ExplainPanelChannel 同款裸调在真机也命中该 SEVERE。
                // 兜底已下沉到 McpDbExecutor.withDbBlockingContext（缺上下文才补空指示器），
                // 本处包装保留为无害冗余（少改一处少一份回归面）。
                outcome = ProgressManager.getInstance().runProcess(
                        (Computable<McpExplainFormatter.RawResult>) () -> {
                            try {
                                return McpDbExecutor.executeQuery(
                                        project,
                                        dataSource,
                                        sql,
                                        scalar ? 1 : McpExplainFormatter.DEFAULT_MAX_ROWS,
                                        timeoutSeconds,
                                        // T-ExplainPwdPrompt：面板 UI 上下文——数据源未存密码时交平台原生密码框
                                        true,
                                        null,
                                        channelNote);
                            } catch (Throwable t) {
                                if (FatalErrors.isFatal(t)) {
                                    throw (Error) t;
                                }
                                throw new RuntimeException(t);
                            }
                        },
                        new EmptyProgressIndicator());
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) {
                    throw (Error) t;
                }
                error = t;
            }
            McpExplainFormatter.RawResult raw = outcome;
            Throwable failure = error;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (failure != null) {
                    onResult.accept(new Failed(failureMessage(failure)));
                } else {
                    onResult.accept(scalar ? scalarResult(raw) : tableResult(raw));
                }
            }, ModalityState.any());
        });
    }

    /**
     * 标量结果映射：首行首列去空白后必须是整数，否则如实标 Failed（不把空值当 0）。
     * 抽成纯函数是为了让 headless 覆盖真链映射——此前测试缝直接给 {@link Result}，这段逻辑零覆盖。
     */
    public static Result scalarResult(@NotNull McpExplainFormatter.RawResult raw) {
        Long value = null;
        List<List<String>> rows = raw.rows();
        if (rows != null && !rows.isEmpty()) {
            List<String> firstRow = rows.get(0);
            if (firstRow != null && !firstRow.isEmpty()) {
                String cell = firstRow.get(0);
                if (cell != null) {
                    String trimmed = cell.trim();
                    try {
                        value = trimmed.isEmpty() ? null : Long.parseLong(trimmed);
                    } catch (NumberFormatException e) {
                        value = null;
                    }
                }
            }
        }
        return value != null ? new Scalar(value, raw) : new Failed("query did not return a numeric count");
    }

    /** 表格结果映射（EXPLAIN 直送）。 */
    @NotNull
    public static Result tableResult(@NotNull McpExplainFormatter.RawResult raw) {
        return new Table(raw);
    }

    /**
     * 失败信息：反射通道会把目标异常包进 InvocationTargetException，必须解包后取 message；
     * 无 message 时退回异常类简单名，保证对话框/内联行不出现空白原因。
     *
     * <p>T-ExplainPwdPrompt（2026-10-24）：认证失败附「补存密码」指引——预估行数内联区与
     * EXPLAIN 错误对话框共用本出口，指引随文案走（其余失败形态零变化）。</p>
     */
    @NotNull
    public static String failureMessage(@NotNull Throwable t) {
        Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite
                ? ite.getTargetException()
                : t;
        String text = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        if (cn.song.mybatisplus.visualizer.execute.ExplainPanelChannelKt.isAuthFailureText(text)) {
            return text + "　" + MpSqlVisualizerBundle.message("panel.explain.authFailureHint");
        }
        return text;
    }

    /** Kotlin {@code runCatching { ... }.getOrNull()} 等价：fatal 透传，其余异常归 null。 */
    @Nullable
    private static <T> T quietly(@NotNull java.util.concurrent.Callable<T> body) {
        try {
            return body.call();
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            return null;
        }
    }
}
