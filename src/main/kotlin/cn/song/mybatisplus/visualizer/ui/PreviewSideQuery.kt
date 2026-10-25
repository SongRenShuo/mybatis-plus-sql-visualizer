package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import cn.song.mybatisplus.visualizer.mcp.McpDbExecutor
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.mcp.McpSelectGuard
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable

/**
 * 预览面板只读旁路查询（T-PreviewSideQuery P1/P2）。
 *
 * 走 [McpDbExecutor.executeQuery] 这条既有只读通道（结构化 EXPLAIN 已在使用），
 * **不调用** `SqlExecutor.execute` / `embedConsole`，因此不会覆盖预览编辑器所在的
 * 共享控制台文档，也不会让 `currentEditorSql()` 漂移到试算/EXPLAIN 语句。
 *
 * 前置检查与生产执行链一致：SELECT 探针先过 [McpSelectGuard]，再按
 * [ExecutionDialectGate] 对绑定数据源做 fail-closed 方言校验；通过后才在池线程执行，
 * EDT 回调结果。
 */
internal object PreviewSideQuery {

    sealed interface Result {
        /** 单值结果（预估影响行数）。 */
        data class Scalar(val value: Long, val raw: McpExplainFormatter.RawResult) : Result

        /** 表格结果（EXPLAIN 直送等）。 */
        data class Table(val raw: McpExplainFormatter.RawResult) : Result

        /**
         * 只读护栏/方言闸阻断；[message] 可直接展示。
         *
         * @param verdict 方言闸判定（只读护栏阻断时为 null）——调用方据此区分「方言冲突」
         *                与其它阻断，并决定是否给「按目标方言重渲染 / 切换数据源」出口。
         */
        data class Blocked(
            val message: String,
            val verdict: ExecutionDialectGate.Verdict? = null,
        ) : Result

        /** 执行失败；[message] 可直接展示。 */
        data class Failed(val message: String) : Result
    }

    /**
     * 测试缝：headless 无真库/原生控制台；生产为 null 走真链。
     * 回调在调用线程执行，测试可直接同步给结果。
     */
    @Volatile
    internal var runnerForTest: ((Project, Any, String, Boolean, (Result) -> Unit) -> Unit)? = null

    /**
     * @param request 渲染来源/意图/是否可重渲染——与生产执行链同一份事实。
     * @param scalar  true=只取首行首列数字（COUNT 试算）；false=返回整表（EXPLAIN）。
     * @param requireSelectGuard true=先过 MCP 只读护栏（COUNT 探针）；EXPLAIN 走自己的
     *        ExplainGate/方言闸，不需要 SELECT 护栏。
     */
    fun run(
        project: Project,
        dataSource: Any,
        sql: String,
        request: ExecutionDialectGate.Request,
        scalar: Boolean,
        requireSelectGuard: Boolean = true,
        channelNote: String? = null,
        onResult: (Result) -> Unit,
    ) {
        if (requireSelectGuard) {
            val guard = McpSelectGuard.checkSingleSelect(sql)
            if (!guard.allowed) {
                onResult(Result.Blocked(guard.reason ?: guard.errorCode ?: "read-only guard blocked"))
                return
            }
        }
        val target = runCatching { ExecutionDialectGate.fromDataSource(dataSource) }.getOrNull()
        val verdict = ExecutionDialectGate.evaluate(request, target?.dialectName)
        if (verdict.status != ExecutionDialectGate.Status.MATCH) {
            onResult(Result.Blocked(DataSourceSelector.dialectGateMessage(verdict), verdict))
            return
        }
        val testRunner = runnerForTest
        if (testRunner != null) {
            testRunner(project, dataSource, sql, scalar, onResult)
            return
        }
        val timeoutSeconds = PluginSettings.getInstance().state.executionTimeoutSeconds
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching {
                // T-PreviewSideQuery：`executeOnPooledThread` 不携带 Job/ProgressIndicator，而
                // DatabaseConnectionManager.Builder.createBlocking 明确要求当前线程有进度上下文
                // （headless 实测：no ProgressIndicator or Job, the current job is not cancellable）。
                // 真机 IDE 的池线程恰好有上下文，测试/纯后台调用没有——给一次空指示器让只读查询
                // 在任意后台上下文可执行；查询本身不读取消状态（只读短查询，超时由驱动护栏负责）。
                // T-PreviewChainNoise W2（2026-10-23 订正）：上文「真机 IDE 的池线程恰好有上下文」
                // 已被用户真机日志推翻——ExplainPanelChannel 同款裸调在真机也命中该 SEVERE。
                // 兜底已下沉到 McpDbExecutor.withDbBlockingContext（缺上下文才补空指示器），
                // 本处包装保留为无害冗余（少改一处少一份回归面）。
                ProgressManager.getInstance().runProcess(
                    Computable {
                        McpDbExecutor.executeQuery(
                            project,
                            dataSource,
                            sql,
                            if (scalar) 1 else McpExplainFormatter.DEFAULT_MAX_ROWS,
                            timeoutSeconds = timeoutSeconds,
                            // T-ExplainPwdPrompt：面板 UI 上下文——数据源未存密码时交平台原生密码框
                            askPassword = true,
                            channelNote = channelNote,
                        )
                    },
                    EmptyProgressIndicator(),
                )
            }
            ApplicationManager.getApplication().invokeLater({
                outcome.onSuccess { raw -> onResult(if (scalar) scalarResult(raw) else tableResult(raw)) }
                    .onFailure { e -> onResult(Result.Failed(failureMessage(e))) }
            }, ModalityState.any())
        }
    }

    /**
     * 标量结果映射：首行首列去空白后必须是整数，否则如实标 Failed（不把空值当 0）。
     * 抽成纯函数是为了让 headless 覆盖真链映射——此前测试缝直接给 [Result]，这段逻辑零覆盖。
     */
    internal fun scalarResult(raw: McpExplainFormatter.RawResult): Result {
        val value = raw.rows.firstOrNull()?.firstOrNull()?.trim()?.toLongOrNull()
        return if (value == null) Result.Failed("query did not return a numeric count") else Result.Scalar(value, raw)
    }

    /** 表格结果映射（EXPLAIN 直送）。 */
    internal fun tableResult(raw: McpExplainFormatter.RawResult): Result = Result.Table(raw)

    /**
     * 失败信息：反射通道会把目标异常包进 InvocationTargetException，必须解包后取 message；
     * 无 message 时退回异常类简单名，保证对话框/内联行不出现空白原因。
     */
    internal fun failureMessage(t: Throwable): String {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        val text = cause.message ?: cause.javaClass.simpleName
        // T-ExplainPwdPrompt（2026-10-24）：认证失败附「补存密码」指引——预估行数内联区与
        // EXPLAIN 错误对话框共用本出口，指引随文案走（其余失败形态零变化）
        return if (cn.song.mybatisplus.visualizer.execute.isAuthFailureText(text)) {
            text + "　" + MpSqlVisualizerBundle.message("panel.explain.authFailureHint")
        } else {
            text
        }
    }
}
