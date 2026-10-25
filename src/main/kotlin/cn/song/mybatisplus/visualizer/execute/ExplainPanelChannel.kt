package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.mcp.McpDbExecutor
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project

/**
 * R33：面板 EXPLAIN 结构化渲染通道（自建执行链路线，批次 9 核实报告 §三 V41 定案）。
 *
 * 现状（V41 锚点）：`SqlPreviewPanel.explainSql` 把包装后的 EXPLAIN 直送原生 Database 控制台，
 * 插件对结果区仅做组件换入（`embedConsole`），**无行列级程序化读取面**——截获控制台输出不可行
 * （网格由 DBN 托管，硬读=侵入平台内部模型，版本脆弱）。本通道复用 MCP 链既有的自持执行链
 * （[McpDbExecutor] 反射建连执行，243/262 签名核验在案），把行列文本交给面板侧结构化渲染。
 *
 * **双通道并存（保守增量红线）**：本通道不是 EXPLAIN 的唯一路径——判定/执行/渲染任一环节
 * 不成立都 fail-closed 回退既有直送控制台通道（[Decision.DirectSilent] = 设计内直送不提示；
 * [Decision.DirectFallback] = 结构化预期成立但环境不给，落回直送通道并给一条轻提示，不弹窗）。
 *
 * 线程纪律：[decide] 在 EDT 调用（只读布尔判定）；[executeStructured] 的 JDBC 全程在
 * pooled 线程（JDBC 禁 EDT，循 McpDbExecutor 既有线程形态），结果经 EDT 回调交付。
 */
object ExplainPanelChannel {

    /** 结构化渲染认得的输出格式：仅 MySQL 家族表格行与 PG 家族单列 JSON 文本。 */
    private val STRUCTURED_FORMATS = setOf(ExplainOutputFormat.TABLE, ExplainOutputFormat.JSON)

    /**
     * T-UI030：该输出格式是否有结构化渲染面（TEXT/XML/未知一律无——不硬解析红线）。
     * 供「开关关闭时是否值得提示启用」复用同一份格式白名单，避免第二处硬编码。
     */
    fun supportsStructuredFormat(format: ExplainOutputFormat?): Boolean =
        format != null && format in STRUCTURED_FORMATS

    /** 通道判定结果（三类可区分——提示文案不同，合并会让用户拿到错的解释，ExplainGate 同款口径）。 */
    sealed interface Decision {
        /** 走结构化通道（自建执行链 + 面板渲染）。 */
        object Structured : Decision

        /** 设计内直送（开关关 / 方言格式非结构化档 TEXT/XML/未知）——既有主路径，不给提示。 */
        object DirectSilent : Decision

        /** 环境性回退：结构化预期成立但环境不可用——落回直送通道并附轻提示（[reason] 可区分）。 */
        data class DirectFallback(val reason: FallbackReason) : Decision
    }

    /** 回退理由（轻提示按理由给可操作的措辞）。 */
    enum class FallbackReason {
        /** 预览会话没有已绑数据源（用户还没在控制台里选过数据源）。 */
        NO_DATA_SOURCE,

        /** Community / 无 Database 插件（自建链与控制台链同样不可用——此时直送链自身会提示引导）。 */
        NO_DATABASE_PLUGIN,

        /**
         * R33 真机走查修复：SQL 仍含未回填的 `?` 占位符——预览文本是回填链的终点产物
         * （参数击键有防抖重渲染），残留 `?` 即真未填（默认占位档 / 无类型信息）。此时下发
         * 结构化探测 = 把字面 `?` 送进 JDBC → 语法错（真机 `SQLException: You have an error
         * in your SQL syntax`）。响亮失败优于语法错：不发探测，明示「存在未回填参数」后
         * 落回直送通道（控制台如实呈现语法错）。
         */
        UNFILLED_PARAMS,
    }

    /**
     * 通道判定（纯函数，无 IDE 类型——EDT 直调安全）。
     *
     * 判定序：① 开关关 → 直送；② 格式非结构化档（TEXT/XML/未知，含 R23 Oracle 二段式与
     * SQL Server XML——**不硬解析**红线）→ 直送；③ database 插件不在场 → 回退（提示）；
     * ④ 无已绑数据源 → 回退（提示）；⑤ SQL 仍含未回填 `?`（R33 真机走查修复）→ 回退（提示）；
     * ⑥ 全部成立 → 结构化。
     *
     * `hasUnfilledParams` 默认 false 只为既有调用点零改编译——面板接线必须显式传
     * 占位探测结论（WiringGuard 钉住），漏传会让语法错场景重新滑进结构化探测。
     */
    fun decide(
        enabled: Boolean,
        format: ExplainOutputFormat?,
        databaseAvailable: Boolean,
        hasBoundDataSource: Boolean,
        hasUnfilledParams: Boolean = false,
    ): Decision {
        if (!enabled) return Decision.DirectSilent
        if (format == null || format !in STRUCTURED_FORMATS) return Decision.DirectSilent
        if (!databaseAvailable) return Decision.DirectFallback(FallbackReason.NO_DATABASE_PLUGIN)
        if (!hasBoundDataSource) return Decision.DirectFallback(FallbackReason.NO_DATA_SOURCE)
        if (hasUnfilledParams) return Decision.DirectFallback(FallbackReason.UNFILLED_PARAMS)
        return Decision.Structured
    }

    /**
     * 结构化执行结果（纯数据，EDT 回调交付；[dataSourceName]/[elapsedMs] 来自
     * [McpDbExecutor.ExecutedOutcome] 审计钩子的同步回读——成功失败两条路径都会先回调一次）。
     */
    data class StructuredOutcome(
        val raw: McpExplainFormatter.RawResult,
        val elapsedMs: Long,
        val dataSourceName: String?,
    )

    /** 测试缝：headless 不连真库；生产为 null 走池线程真链。 */
    @Volatile
    internal var structuredRunnerForTest: ((
        Project,
        Any,
        String,
        Int,
        (StructuredOutcome) -> Unit,
        (String) -> Unit,
    ) -> Unit)? = null

    /**
     * 结构化执行（JDBC 全程 pooled 线程，禁 EDT）。
     *
     * 复用 [McpDbExecutor.executeQuery] 既有反射链（channelNote 不传——结构化档方言全是
     * 单语句通道，null 走 executeQuery 主通道与既有调用点逐字节同形）；行预算取
     * [McpExplainFormatter.DEFAULT_MAX_ROWS]（EXPLAIN 计划行通常 <100，MCP 链同款默认）；
     * 超时护栏沿用设置项（与 MCP 链同源）。
     *
     * @param onPlan   成功回调（EDT 交付）
     * @param onFailed 失败回调（EDT 交付；参数 = 驱动异常「类名: 首行消息」）——调用方据此
     *                 回退直送通道（fail-closed，绝不吞掉失败假装渲染成功）
     */
    fun executeStructured(
        project: Project,
        dataSource: Any,
        explainSql: String,
        timeoutSeconds: Int,
        onPlan: (StructuredOutcome) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val testRunner = structuredRunnerForTest
        if (testRunner != null) {
            testRunner(project, dataSource, explainSql, timeoutSeconds, onPlan, onFailed)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            var elapsedMs = -1L
            var dataSourceName: String? = null
            try {
                val raw = McpDbExecutor.executeQuery(
                    project, dataSource, explainSql, McpExplainFormatter.DEFAULT_MAX_ROWS,
                    timeoutSeconds = timeoutSeconds,
                    // T-ExplainPwdPrompt：面板 UI 上下文——数据源未存密码时交平台原生密码框
                    // （此前硬编码 false 连坐自 MCP 通道，沙盒实测只出 Access denied 红字）
                    askPassword = true,
                    onExecuted = { outcome ->
                        // 审计钩子同步回读（成功/失败两路径都回调一次；取数失败不影响主链）
                        elapsedMs = outcome.elapsedMs
                        dataSourceName = outcome.dataSourceName
                    },
                )
                val outcome = StructuredOutcome(raw, elapsedMs, dataSourceName)
                invokeLaterEdt { onPlan(outcome) }
            } catch (e: Throwable) {
                // T-FatalPassthrough 同款解包口径（反射链目标异常被包进 InvocationTargetException）
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                if (FatalErrors.isFatal(cause)) throw cause
                val text = "${cause.javaClass.simpleName}: " +
                    (cause.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "(no message)")
                invokeLaterEdt { onFailed(text) }
            }
        }
    }

    private fun invokeLaterEdt(block: () -> Unit) {
        // write-safe：回调只做 Swing 组装/换入（无文档写），ModalityState.any() 同 embedConsole 口径
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
    }
}

/** T-ExplainPwdPrompt：执行计划失败分型（呈现层据此选出口，纯逻辑 headless 直测）。 */
internal enum class ExplainFailureKind {
    /** 认证失败（数据源密码未存/错误）——附「补存密码」指引，不再无谓回退直送（同通道必同样失败）。 */
    AUTH,

    /**
     * T-ExplainFailUx W1（2026-10-24 用户沙盒实测）：**选错数据源**——预览引用的表不在当前源
     * （`Table 'mp_demo.wms_inventory_jar_demo' doesn't exist`）。这是最典型的「该换源」信号：
     * 不弹模态框，就地引导切换数据源（换源后自动重试一次，见 W3）。
     */
    WRONG_TARGET,

    /** 用户取消（原生密码框 Esc 等）——轻通知，绝不自动重试（重试会再弹一次框）。 */
    CANCELLED,

    /** 其余——维持既有回退直送链。 */
    OTHER,
}

/** 认证失败签名（小写 contains；覆盖 MySQL/PostgreSQL/Oracle/SQL Server 常见形态）。 */
private val EXPLAIN_AUTH_SIGNATURES = listOf(
    "access denied",                   // MySQL / 通用
    "password authentication failed",  // PostgreSQL
    "invalid username/password",       // Oracle 文本形态
    "ora-01017",                       // Oracle 数字形态
    "login failed for user",           // SQL Server
)

/**
 * T-ExplainFailUx W1：「表不在当前源」签名（小写 contains）。覆盖四家方言的固定报文：
 * MySQL `[42S02][1146] Table 'x.y' doesn't exist`（仓内实证 DataSourceSelectorResults:1056）、
 * PostgreSQL `relation "x" does not exist`（42P01）、SQL Server `Invalid object name 'x'`（208）、
 * Oracle `ORA-00942: table or view does not exist`。
 *
 * 注意与「列不存在」区分：`Unknown column` 是列级问题（同一张表在，换源解决不了），**不算**本档。
 */
private val EXPLAIN_WRONG_TARGET_SIGNATURES = listOf(
    "doesn't exist",                    // MySQL 1146 / SQL Server 变体
    "does not exist",                   // PostgreSQL 42P01 / Oracle 942 文本
    "unknown table",                    // MySQL 泛化
    "invalid object name",              // SQL Server 208
    "ora-00942",                        // Oracle 数字形态
    "no such table",                    // SQLite / 通用
)

/** 文本是否「表不在当前源」形态（W1）。 */
internal fun isWrongTargetText(text: String): Boolean {
    val lower = text.lowercase()
    return EXPLAIN_WRONG_TARGET_SIGNATURES.any { lower.contains(it) }
}

/** 文本是否认证失败形态（[classifyExplainFailure] 与 [cn.song.mybatisplus.visualizer.ui.PreviewSideQuery] 共用）。 */
internal fun isAuthFailureText(text: String): Boolean {
    val lower = text.lowercase()
    return EXPLAIN_AUTH_SIGNATURES.any { lower.contains(it) }
}

/**
 * T-ExplainPwdPrompt W2（2026-10-24）：执行计划失败分型。
 *
 * 取消判定排除超时——驱动超时消息常含 "cancelled"（MySQL "Statement cancelled due to
 * timeout"），超时是执行错误不是用户取消（[cn.song.mybatisplus.visualizer.execute.ExecutionTimeoutGuard]
 * 已转 `timed out` 标记），误判取消会把真失败吞成轻通知。
 */
internal fun classifyExplainFailure(text: String): ExplainFailureKind {
    if (isAuthFailureText(text)) return ExplainFailureKind.AUTH
    // T-ExplainFailUx W1：认证优先（`doesn't exist` 与认证不会同时出现，但认证误判代价更高），
    // 其次「表不在当前源」——该换源而非报错（列级 `Unknown column` 不算，换源解决不了）。
    if (isWrongTargetText(text)) return ExplainFailureKind.WRONG_TARGET
    val lower = text.lowercase()
    // "cancell" 词根同时覆盖 cancelled / cancellation（CancellationException 是双 L，
    // 只写 "canceled" 漏掉平台取消形态——ExplainFailureClassifierTest 首跑实证）
    val cancelLike = lower.contains("canceled") || lower.contains("cancell")
    val timeoutLike = lower.contains("timeout") || lower.contains("timed out")
    if (cancelLike && !timeoutLike) return ExplainFailureKind.CANCELLED
    return ExplainFailureKind.OTHER
}
