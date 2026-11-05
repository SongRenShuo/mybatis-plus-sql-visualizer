package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/** 提交回执，不代表数据库执行成功；与旧准备/选择 Boolean 回调分离。 */
enum class ExecutionSubmission { SUBMITTED, CANCELLED, BLOCKED }

/**
 * SQL 执行引擎（M36，反射隔离）：弹出原生数据源选择 → Database 控制台内嵌执行。
 *
 * M36.5 执行链拆分：
 *  - [prepareConsole]：准备控制台（选数据源 → 建 session → attachConsole → 拼合组件回调），**不执行**——
 *    面板打开时调用，原生控制台即刻可见；
 *  - [executePrepared]：在已准备的控制台上执行；未就绪返回 false（调用方回退全链）；
 *  - [execute]：准备 + 执行合一（原语义，等价原生 Ctrl+Enter）。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object SqlExecutor {

    /** UI023：headless 下截获真实执行入口边界，避免靠 Community 不可执行制造「零提交」假绿。 */
    internal var executionAttemptForTest: ((String?) -> Unit)? = null

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

    /**
     * 准备 + 执行合一（M36 原语义）：选数据源 → 建控制台 → 直接执行。
     *
     * @param onConsole 控制台 UI 组件就绪回调（供内嵌展示；null = 不内嵌）
     * @param onEditor  控制台编辑器就绪回调（注入插件补全上下文；null = 不需要）
     * @param onError   错误回调（执行链失败时调用；null = 不通知）
     */
    fun execute(
        project: Project,
        sql: String,
        dataSource: Any?,
        isQuery: Boolean,
        onConsole: ((javax.swing.JComponent) -> Unit)? = null,
        onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
        wholeScriptExec: Boolean = false,
        dialectGateRequest: ExecutionDialectGate.Request? = null,
        onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)? = null,
        onSubmission: ((ExecutionSubmission) -> Unit)? = null,
        /** T-DialectGateUx：强制弹数据源选择器（方言闸门的「切换数据源」入口）。 */
        forceChoose: Boolean = false,
        /** UI-049：空 Runner「方言不匹配」态结构化提示（与 `onError` 互斥；null = 既有红字文案）。 */
        onDialectMismatchHint: ((DialectMismatchHint) -> Unit)? = null,
        /** T8：中性信息回调（工具语句/更新语句成功完成时面板 showStatus，不走红条错误通道）。 */
        onInfo: ((String) -> Unit)? = null,
    ) {
        executionAttemptForTest?.let { it(sql); return }
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val receipt: (ExecutionSubmission) -> Unit = { outcome ->
            if (completed.compareAndSet(false, true)) onSubmission?.invoke(outcome)
        }
        DataSourceSelector.selectAndExecute(
            project, sql,
            onConsole = onConsole,
            onEditor = onEditor,
            onError = if (onSubmission == null) onError else { message ->
                receipt(ExecutionSubmission.BLOCKED)
                onError?.invoke(message)
                Unit
            },
            // 富错误通道仍按既有优先级投递，未提供时不可用空 lambda 吞掉旧通道。
            onErrorRich = if (onSubmission == null) onErrorRich else onErrorRich?.let { sink -> { info: ExecutionErrorInfo ->
                receipt(ExecutionSubmission.BLOCKED)
                sink(info)
            } },
            callback = { success ->
                if (!success) {
                    // Community 或失败时提示
                    if (!ExecuteSqlSupport.isAvailable()) {
                        showInfo(project, MpSqlVisualizerBundle.message("execute.noDatabase"))
                    }
                }
            },
            wholeScriptExec = wholeScriptExec,
            dialectGateRequest = dialectGateRequest,
            onDialectGate = onDialectGate,
            onSubmission = if (onSubmission == null) null else receipt,
            forceChoose = forceChoose,
            onDialectMismatchHint = onDialectMismatchHint,
            onInfo = onInfo,
        )
    }

    /**
     * M36.5：准备控制台（不执行）——面板打开时调用，原生控制台默认展示。
     * Community 下静默失败（callback(false)，不打扰用户——社区版走自研编辑器回退）。
     */
    fun prepareConsole(
        project: Project,
        sql: String,
        onConsole: ((javax.swing.JComponent) -> Unit)? = null,
        onEditor: ((com.intellij.openapi.editor.Editor) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        callback: ((Boolean) -> Unit)? = null,
        dialectGateRequest: ExecutionDialectGate.Request? = null,
        onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)? = null,
        /** UI-049：空 Runner「方言不匹配」态结构化提示（与 `onError` 互斥；null = 既有红字文案）。 */
        onDialectMismatchHint: ((DialectMismatchHint) -> Unit)? = null,
    ) {
        if (!ExecuteSqlSupport.isAvailable()) {
            // 社区版：静默回退（不弹提示——自研编辑器已是回退态）
            callback?.invoke(false)
            return
        }
        DataSourceSelector.prepareConsole(
            project, sql,
            onConsole = onConsole,
            onEditor = onEditor,
            onError = onError,
            callback = callback,
            dialectGateRequest = dialectGateRequest,
            onDialectGate = onDialectGate,
            onDialectMismatchHint = onDialectMismatchHint,
        )
    }

    /**
     * M36.5：在已准备的控制台上执行。
     * @param onResultsReady 执行触发成功回调（完整控制台 UI，顶部块+结果区）——
     *                       调用方据此把右侧从纯编辑器切换为带结果区的完整控制台
     * @param wholeScriptExec O-64：identity 包装产物整批直跑（ExecOption 覆写为
     *                        EXECUTE_INSIDE_WHOLE_SCRIPT/EXECUTE_OUTSIDE_WHOLE_SCRIPT，
     *                        原生 Statements 选择弹层不再弹出、整批全部执行）；普通执行传默认 false
     * @return false = 控制台未就绪（调用方回退 [execute] 全链）
     */
    fun executePrepared(
        project: Project,
        onResultsReady: ((javax.swing.JComponent) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        onErrorRich: ((ExecutionErrorInfo) -> Unit)? = null,
        wholeScriptExec: Boolean = false,
        dialectGateRequest: ExecutionDialectGate.Request? = null,
        onDialectGate: ((ExecutionDialectGate.Verdict) -> Unit)? = null,
        onSubmission: ((ExecutionSubmission) -> Unit)? = null,
        onInfo: ((String) -> Unit)? = null,
    ): Boolean {
        executionAttemptForTest?.let { it(null); return true }
        if (!ExecuteSqlSupport.isAvailable()) return false
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val receipt: (ExecutionSubmission) -> Unit = { outcome ->
            if (completed.compareAndSet(false, true)) onSubmission?.invoke(outcome)
        }
        return DataSourceSelector.executePrepared(
            project, onResultsReady,
            onError = if (onSubmission == null) onError else { message ->
                receipt(ExecutionSubmission.BLOCKED)
                onError?.invoke(message)
                Unit
            },
            onErrorRich = if (onSubmission == null) onErrorRich else onErrorRich?.let { sink -> { info: ExecutionErrorInfo ->
                receipt(ExecutionSubmission.BLOCKED)
                sink(info)
            } },
            wholeScriptExec = wholeScriptExec,
            dialectGateRequest = dialectGateRequest,
            onDialectGate = onDialectGate,
            onSubmission = if (onSubmission == null) null else receipt,
            onInfo = onInfo,
        )
    }

    /** 控制台是否已就绪（[prepareConsole] 成功后 true）。 */
    fun isPrepared(): Boolean = ExecuteSqlSupport.isAvailable() && DataSourceSelector.isPrepared()

    /** 释放已准备控制台引用（面板清空/关闭时调用）。 */
    fun clearPrepared() {
        DataSourceSelector.clearPrepared()
    }

    /** T-Perf-16：面板 dispose 释放全部单例滞留引用（prepared/shared/pendingOnConsole）。 */
    fun releaseForPanel() {
        DataSourceSelector.releaseForPanel()
    }

    /**
     * T-UiNative T-UN-02：Community 降级提示改轻通知（原模态弹窗——纯告知无需打断）。
     * 测试模式跳过 + runCatching 兜底（headless 无通知系统依赖，原 Messages 亦被 runCatching 吞掉）。
     */
    private fun showInfo(project: Project, message: String) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            Notification(
                NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("execute.title"),
                message,
                NotificationType.INFORMATION,
            ).notify(project)
        }
    }
}
