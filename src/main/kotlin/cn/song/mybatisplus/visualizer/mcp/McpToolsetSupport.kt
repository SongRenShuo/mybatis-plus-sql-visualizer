package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.LogStatementClassifier
import cn.song.mybatisplus.visualizer.log.SqlListTypeColor
import cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.log.sqlLogManagerIfCreated
import cn.song.mybatisplus.visualizer.log.SqlSource
import cn.song.mybatisplus.visualizer.log.StatementType
import cn.song.mybatisplus.visualizer.log.formatDatabaseFragment
import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * MCP 工具集抽象基类（T-McpToolset2 / T-MTS2-00）：一工具一 toolset 类的平台门控基座。
 *
 * **为什么按工具拆类**：平台对工具的启用/禁用门控只有 toolset 粒度——
 * `McpToolset.Companion.getEnabledToolsets`（262 mcpserver.jar 字节码实证）按 `isEnabled()`
 * 过滤整个 toolset；工具级开关即一工具一 toolset 类，各以自己的 [toolName] 过 [McpToolGate]。
 * （O-68 订正：该过滤只覆盖 ReflectionToolsProvider 路径；agent 真正看到的外部会话清单走
 * `getMcpToolsFiltered` 过滤层，由 SqlVisualizerToolGateFilterProvider EP 承担，见 McpToolGate
 * 头注。）
 *
 * **类名前缀 `SqlVisualizer` 是硬约定（T-McpToolNaming / T-MTN-01）**：平台「暴露工具」页按工具描述符的
 * category 分组，category 的 `shortName` 直接取 **toolset 类的 simpleName**
 * （`ToolsetReflection_utilKt.asTools` 字节码实证），`McpToolset` 接口本身没有名字属性——
 * 分组名只能由类名决定。加统一前缀后本插件十组在列表里**组名自带插件名且字典序相邻**，
 * 扫视即知同属一个插件。**新增工具类必须照此命名**（`SqlVisualizer<Xxx>Tool`），
 * 否则该工具在「暴露工具」页会脱离本插件组群。
 *
 * **253 兼容（T-McpDynamicToolset 形态）**：本基类**零平台接口实现**（T-McpDynamicToolset：
 * jar 字节码零 com.intellij.mcpserver.* 类型引用——243/251 的 mcpServer 旧形态缺新 API，静态
 * 引用即 verifier compatibility problems，20260926 审核拒绝主因）。平台接口由运行期 ASM 生成的
 * 壳类实现（壳 extends 本基类链上的工具类 + implements McpToolset，三钩子直接继承本类实现，
 * 见 McpToolsetDynamicRegistrar）；此前「显式字面量覆写防 K2 兼容桥悬空」的三钩子约定随
 * implements 摘除自然消解（K2 桥只为接口默认方法生成）。
 *
 * **输出预算登记表（T-McpOutputBudget / O-20 守卫约定——新增工具或调整预算必须在此登记对齐，
 * MetadataInvalidator 缓存登记表同款防漏）**：
 *  - sqlv_schema_context：maxTables 默认 8 / 硬上限 20、单元格 200 字符、tables 白名单 ≤50
 *    （常量在 McpSchemaFormatter：DEFAULT_MAX_TABLES / HARD_MAX_TABLES / MAX_CELL_CHARS /
 *    MAX_TABLE_NAMES）；
 *  - sqlv_explain：行默认 ≤200 / 上限 1000（maxRows 可配，越界钳制不抛错；通道层另有兜底钳制）、
 *    单元格 2000 字符（常量在 McpExplainFormatter：DEFAULT_MAX_ROWS / HARD_MAX_ROWS /
 *    MAX_CELL_CHARS）；notices 节无条数预算（驱动会话通道天然有界，个案个位数）；
 *  - sqlv_perf_advice：建议 ≤30 条（MAX_FINDINGS，超限 SEVERITY 等级序先裁低级）+ 单条文案
 *    200 字符（MAX_ADVICE_CHARS，对齐 schema 200 口径）（常量在 SqlVisualizerPerfAdviceTool）；
 *  - sqlv_history_search（R14）：返回条 ≤20 默认 / 硬上限 100（maxResults 钳制）、单条 SQL 预览
 *    8 行 / 600 字符、扫描封顶 200_000 条（常量在 HistorySearchService：DEFAULT_MAX_RESULTS /
 *    HARD_MAX_RESULTS / MAX_PREVIEW_LINES / MAX_PREVIEW_CHARS）；范围默认当日（D1=A）防全量倾泻；
 *  - sqlv_check_sql（NEW-23 chain=true）：核查段规模由输入 SQL 决定；清单段 = 固定行数的判定与
 *    交接文案 + 回滚计划（语句 ≤500 条 / 256KB，RollbackSqlGenerator 既有上限）+ 一条 COUNT 试算
 *    语句——无新增独立预算，超限由回滚生成器截断并降级 PARTIAL；
 *  - 其余六工具：输出规模由输入决定天然有界（渲染=源码规模、logs=日志条数、crud/reverse/
 *    rollback=单实体单语句），不设额外预算。
 *
 * 注册：运行期探测式程序化注册（McpToolsetDynamicRegistrar，mcpServer 新形态在场的 253+/262
 * 命中；243/251 旧形态静默跳过）。线程模型/零弹窗/审计约定与一期一致：协程线程调用（PSI 读包
 * ReadAction 于各服务内部）；拒绝经 [SqlvExpectedToolException]＋audit 边界反射转译为平台
 * McpExpectedError（见 [McpExpectedBridge]）；每次调用经 [McpAuditLog] 审计。
 */
abstract class McpToolsetSupport(private val toolName: String) {

    /** 平台门控：总开关 ∧ 工具级未禁用（McpToolGate 读插件设置，测试环境兜底启用）。 */
    fun isEnabled(): Boolean = McpToolGate.isEnabled(toolName)

    /** 稳定工具集（平台 McpToolset.isExperimental 默认 true = 实验态，必须显式 false 才进稳定集）。 */
    fun isExperimental(): Boolean = false

    fun alwaysIncluded(): Boolean = false

    // ------------------------------------------------------------------
    // 共用骨架（一期 SqlVisualizerToolset 逐行迁入）
    // ------------------------------------------------------------------

    /**
     * 审计包裹：成功/预期错误统一记 idea.log。非预期异常不在此吞（平台记 Internal error）。
     *
     *  **Long token＋审计上下文旁路——零跨挂起点引用结构（T-McpDynamicToolset 定案）**：
     *  suspend 函数中跨挂起点存活的**引用**（局部对象或参数，如 catch 分支读 tool/argsSummary、
     *  原形态的 AuditTicket）令 K2 生成 SpillingKt spill 引用（243 平台 stdlib 缺该类，verifier
     *  compatibility problem；20260926 三版结构实测收敛出该规律——ticket 对象版/argsSummary 参数版
     *  均报，primitive 跨挂起点不报）。本形态：tool/argsSummary/started 挂起前存入 [AUDIT_CTX]
     *  旁路 map，audit 链只让 primitive token 跨挂起点，记账（含异常路径）在挂起后按 token 取回；
     *  finally 兜底移除防 Internal error 路径泄漏。此前 suspend inline 方案（体分散进工具方法）
     *  亦因 catch 引用 argsSummary 参数 spill 而废弃。
     *
     *  T-McpAuditTrail T-MAT-04：[argsSummary] 为真实入参摘要（经 [McpAuditLog.summarize] 产出，
     *  整体受 [McpAuditLog] 的 SUMMARY_LIMIT 截断）。默认值 [LEGACY_ARGS_SUMMARY] 是改造前的
     *  字面量：其余九个**纯静态**工具无执行面、参数面未立项，保持审计行逐字节不变（**仅 OK 路径**
     *  ——错误路径的入参段格式十个工具统一变化）；新增执行类工具应显式传摘要，勿再落 "ok"。
     *
     *  错误路径（EXPECTED_ERROR）的组合串走 [errorArgsSummary] 的两段分预算：错误文本优先，
     *  入参摘要让位——长 SQL 场景下错误原因不被摘要挤掉（T-MAT-04 回退修复）。
     */
    protected suspend fun audit(
        tool: String,
        block: suspend () -> String,
    ): String = audit(tool, LEGACY_ARGS_SUMMARY, block)

    /**
     * 审计包裹：成功/预期错误统一记 idea.log。非预期异常不在此吞（平台记 Internal error）。
     *
     *  **intrinsics 透传＋continuation 包装——零 SpillingKt 结构（T-McpDynamicToolset 定案）**：
     *  K2（2.1+）对「try-catch/finally 包裹挂起调用」的 suspend 函数无条件生成
     *  `SpillingKt.nullOutSpilledVariable` 引用（参数复制进 continuation 槽时的防泄漏清槽；
     *  20260926 四版结构+探针矩阵实证，catch 内容无关）——而 243 平台捆绑 stdlib 2.0.21 缺
     *  SpillingKt，verifier compatibility problem（20260926 审核拒绝主因之一）。降/升编译器
     *  与携带该类均不可行（gradle 兼容矩阵/类加载委派），本形态绕开触发条件：audit 体内
     *  **不再有被 try 包裹的挂起调用**——挂起段经 [suspendCoroutineUninterceptedOrReturn]
     *  透传（TAIL 形态），成败记账收进包装 continuation（[AuditGate.resumeWith]）与同步
     *  catch 两处；同步/挂起双路径行为与原 try-catch 逐语义等价（预期错误转译平台异常、
     *  其余异常原样传播、成功记账 elapsed）。
     *
     *  T-McpAuditTrail T-MAT-04：[argsSummary] 为真实入参摘要（经 [McpAuditLog.summarize] 产出，
     *  整体受 [McpAuditLog] 的 SUMMARY_LIMIT 截断）。两参重载默认 [LEGACY_ARGS_SUMMARY]：纯静态
     *  工具无执行面、参数面未立项，保持审计行逐字节不变（**仅 OK 路径**——错误路径的入参段格式
     *  十个工具统一变化）；新增执行类工具应显式传摘要，勿再落 "ok"。
     *
     *  错误路径（EXPECTED_ERROR）的组合串走 [errorArgsSummary] 的两段分预算：错误文本优先，
     *  入参摘要让位——长 SQL 场景下错误原因不被摘要挤掉（T-MAT-04 回退修复）。
     */
    protected suspend fun audit(
        tool: String,
        argsSummary: String,
        block: suspend () -> String,
    ): String {
        val token = AUDIT_SEQ.incrementAndGet()
        AUDIT_CTX[token] = AuditContext(tool, argsSummary, System.currentTimeMillis())
        // 券04（T-PerfSweep27-04）：写入点顺带清扫——取消且 continuation 永不 resume 时
        // 双路径记账（AuditGate.resumeWith/onFailure）都不会触达，token 残留；同步 O(小表)
        // 清扫兜底。**不得改用 try/finally 包裹挂起调用**——K2 对该形态无条件生成 SpillingKt
        // 引用，243 平台 stdlib 缺该类（verifier 红线，见类 KDoc；P-4 证伪结论）。
        sweepAuditContexts()
        return suspendCoroutineUninterceptedOrReturn { cont ->
            val gate = AuditGate(token, cont)
            val outcome = try {
                if (!McpToolGate.isEnabled(tool)) {
                    throwExpected(McpToolErrors.code(McpToolErrors.TOOL_DISABLED, "tool $tool is disabled in SQL Visualizer settings."))
                }
                block.startCoroutineUninterceptedOrReturn(gate)
            } catch (e: Throwable) {
                if (FatalErrors.isFatal(e)) throw e
                // 同步路径：block 体在当前帧直接失败（未挂起），记账后按原语义抛出
                throw gate.onFailure(e)
            }
            if (outcome !== COROUTINE_SUSPENDED) gate.onSuccess(outcome as String)
            outcome
        }
    }

    /**
     * 审计闸门：挂起路径成败记账＋预期错误转译＋结果转发（包装 completion 的被动转发者，
     * 不自建状态机）。[onSuccess]/[onFailure] 同样服务同步路径（见 [audit]）。
     */
    internal class AuditGate(
        private val token: Long,
        private val completion: Continuation<String>,
    ) : Continuation<String> {
        override val context: CoroutineContext = completion.context

        override fun resumeWith(result: Result<String>) {
            val r = result
            if (r.isSuccess) {
                auditOk(token)
                completion.resumeWith(Result.success(r.getOrThrow()))
            } else {
                completion.resumeWith(Result.failure(onFailure(r.exceptionOrNull()!!)))
            }
        }

        /** 失败记账＋预期错误转译；其余异常原样返回（平台记 Internal error）。
         *  O-86（DBX #116 对照）：中断/取消（CancellationException）也落审计（status=INTERRUPTED）——
         *  此前该分支直接透传，被取消的请求在 [MCP-AUDIT] 流里无痕（审计纵深缺口）。 */
        internal fun onFailure(e: Throwable): Throwable {
            val ctx = AUDIT_CTX.remove(token) ?: return e
            if (e is java.util.concurrent.CancellationException) {
                McpAuditLog.call(ctx.tool, ctx.argsSummary, "INTERRUPTED", System.currentTimeMillis() - ctx.started)
                return e
            }
            if (e !is SqlvExpectedToolException) return e
            McpAuditLog.call(
                ctx.tool,
                errorArgsSummary(ctx.argsSummary, e.mcpErrorText),
                "EXPECTED_ERROR",
                System.currentTimeMillis() - ctx.started,
            )
            // 对平台抛出的必须是平台 McpExpectedError（agent 可读错误而非 Internal error）；
            // 转译经反射（零平台类型引用），失败降级原样抛自有异常。
            return McpExpectedBridge.wrapExpected(e)
        }

        /** 同步成功记账（返回值走 audit 的 outcome 透传，不经 completion）。 */
        fun onSuccess(@Suppress("UNUSED_PARAMETER") value: String) {
            auditOk(token)
        }
    }

    /** 审计上下文（tool/argsSummary/started 打包，挂起前入 [AUDIT_CTX]、成败记账时取回移除）。 */
    internal class AuditContext(val tool: String, val argsSummary: String, val started: Long)

    /** 取当前项目（框架注入 coroutineContext；反射解析平台 McpCallInfoKt，见 McpExpectedBridge）。 */
    protected suspend fun requireProject(): Project =
        McpExpectedBridge.currentProjectOrNull(kotlin.coroutines.coroutineContext)
            ?: throwExpected(
                McpToolErrors.code(McpToolErrors.PROJECT_NOT_READY, "no project bound to this MCP call."),
            )

    /** 索引未就绪明确报错不挂死（渲染/schema/实体解析依赖 PSI 与索引）。 */
    protected fun requireSmart(project: Project) {
        if (DumbService.getInstance(project).isDumb) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.PROJECT_NOT_READY,
                    "project indexing is in progress; retry when the IDE finishes indexing.",
                ),
            )
        }
    }

    /** 拒绝通道：agent 可读结构化错误，零弹窗（audit 边界转译为平台 McpExpectedError）。 */
    protected fun throwExpected(message: String): Nothing = throw SqlvExpectedToolException(message)

    protected fun firstLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() } ?: text

    companion object {
        /**
         * T-McpAuditTrail T-MAT-04：改造前的审计入参槽位字面量。保留为默认值——纯静态工具
         * （无执行面、参数面未立项）的审计行因此逐字节不变；执行类工具显式传真实摘要。
         */
        private const val LEGACY_ARGS_SUMMARY = "ok"

        /** 审计上下文旁路（T-McpDynamicToolset 零跨挂起点引用结构，见 [audit] 头注）：key=token。 */
        internal val AUDIT_CTX = java.util.concurrent.ConcurrentHashMap<Long, AuditContext>()

        /** 审计 token 发号器。 */
        private val AUDIT_SEQ = java.util.concurrent.atomic.AtomicLong()

        /**
         * 券04（T-PerfSweep27-04）旁路表容量上限：常态 0~2 条（每在途工具一条），取消残留是
         * 低频异常路径，64 上限只为防「控制通道失效形态下长时间无记账」的病态累积。
         */
        internal const val AUDIT_CTX_MAX = 64

        /**
         * 券04（T-PerfSweep27-04）过期判定窗：远超 McpDbExecutor 的 queryTimeout 量级（秒级），
         * 在途工具不会被判过期；即使误删活条目，记账端 `remove ?: return` 语义只丢一条审计行。
         */
        internal const val AUDIT_CTX_STALE_MS = 10 * 60_000L

        /**
         * 券04（T-PerfSweep27-04）审计旁路表清扫：移除过期条目 + 超限按 started 移除最旧。
         * **纯同步函数**（不得触碰挂起调用/try-finally-挂起形态，SpillingKt 红线见类 KDoc）；
         * audit() 写入后调用，挂起前本帧执行完毕。测试直调（internal 可见）。
         */
        internal fun sweepAuditContexts(now: Long = System.currentTimeMillis()) {
            AUDIT_CTX.values.removeIf { now - it.started > AUDIT_CTX_STALE_MS }
            if (AUDIT_CTX.size > AUDIT_CTX_MAX) {
                AUDIT_CTX.entries
                    .sortedBy { it.value.started }
                    .take(AUDIT_CTX.size - AUDIT_CTX_MAX)
                    .forEach { AUDIT_CTX.remove(it.key) }
            }
        }

        /** 成功记账并移除上下文（AuditGate 同步/挂起两路径共用；静态域供嵌套类触达）。 */
        private fun auditOk(token: Long) {
            val ctx = AUDIT_CTX.remove(token) ?: return
            McpAuditLog.call(ctx.tool, ctx.argsSummary, "OK", System.currentTimeMillis() - ctx.started)
        }
    }
}

// ------------------------------------------------------------------
// T-McpAuditTrail（T-MAT-01/02）：MCP 执行链落历史
//
// 为什么是**顶层函数**而非基类方法：本文件门面类（McpToolsetSupportKt）的顶层函数可被裸 JUnit /
// 平台测试直调，加载时不必解析抽象基类的 mcpserver 接口（可选插件依赖）——McpDbExecutor.drainWarnings
// 同款先例。落库链（[SqlLogManager] 取用 + 条目构造）整体留在这里，基类只多一个回调接线点；
// 未来新增执行类工具照抄 [recordMcpExecution] 一处即可获得历史与审计。
// ------------------------------------------------------------------

/**
 * MCP 执行条目构造（T-McpAuditTrail T-MAT-01/02）。
 *
 * **文本恒为调用方传入的原始 selectSql，绝不落 `EXPLAIN <select>` 包装句**：包装句首 token 为
 * `EXPLAIN` → [LogStatementClassifier] 判 `StatementType.OTHER` → [SqlListTypeColor.colorOf] 对 OTHER
 * 返回 null → 列表无类型染色（计划 §一 硬约束 2，第八轮报告 #38 宣称的「风险类别复用分类器」当场失效）。
 * 「该条来自 EXPLAIN 包装执行」由 [SqlSource.MCP] 承担独立标记（该来源仅 explain 通道产出）。
 *
 * 无参数（params 空、无占位符）：MCP 工具收的是已成型 SELECT，参数已在 agent 侧落地——
 * 故本条目依赖 [SqlLogAutoSaver] 的 `source == MCP` 条件才写出 pd 行（T-MAT-03 配套必改）。
 *
 * @param dataSourceName 数据源显示名 → 复用既有目标库角标 [formatDatabaseFragment]
 * @param elapsedMs 执行耗时（历史列表按慢查询阈值着色）
 * @param errorText 失败时的「异常类名: 首行消息」（成功为 null，列表红字片段展示）
 */
internal fun mcpHistoryEntry(
    selectSql: String,
    dataSourceName: String? = null,
    elapsedMs: Long? = null,
    errorText: String? = null,
    timestamp: Long = System.currentTimeMillis(),
): LogSqlEntry {
    val sql = selectSql.trim()
    return LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = timestamp,
        source = SqlSource.MCP,
        executionTimeMs = elapsedMs,
        errorText = errorText,
        databaseName = dataSourceName,
    )
}

/**
 * 落历史（T-McpAuditTrail T-MAT-01）：走 [SqlLogManager] 既有入库路径——`buffer.addEntry` 之后
 * 面板刷新与 [SqlLogAutoSaver] 写盘全部复用既有链路（不新开写通道、不自建 MCP 专属存储）。
 * 取服务用 `project.getService`（Kotlin 类无平台生成的 `getInstance` 静态——Java 注解处理器只处理
 * Java 类，全仓取用口径一致，见 LogStartupCapture / SectionConfigurable）。
 *
 * 失败只记 idea.log 不冒泡：审计是旁路，绝不影响工具结果（McpDbExecutor 侧的回调投递同样 runCatching，
 * 两道都不许把审计故障升级为工具故障）。
 *
 * **门控（D1 定案，2026-09-22 用户）**：审计条目**不受「SQL 日志捕获」开关约束**——走
 * [cn.song.mybatisplus.visualizer.log.SqlLogEntryBuffer.addAuditEntry]（跳过 `paused` 门），
 * 即开关关着（含用户显式停止后的 `paused=true`）时照样落列表；MCP 工具的可用性只由设置页
 * 「启用 SQL Visualizer MCP 工具」＋工具级禁用清单决定（`McpToolGate`）。
 * 其余门与普通条目一致：去重窗口内同 SQL 折叠、过滤关键字命中的丢弃（两者是列表展示偏好，不是开关）。
 */
internal fun recordMcpExecution(
    project: Project,
    selectSql: String,
    dataSourceName: String?,
    elapsedMs: Long,
    errorText: String?,
) {
    runCatching {
        // T-ST-02b2：读路径经 sqlLogManagerIfCreated（永不发起创建；审计是旁路，取不到即跳过）
        sqlLogManagerIfCreated(project)?.entries?.addAuditEntry(
            mcpHistoryEntry(selectSql, dataSourceName, elapsedMs, errorText),
        )
    }.onFailure { e ->
        com.intellij.openapi.diagnostic.Logger
            .getInstance("cn.song.mybatisplus.visualizer.mcp.McpAuditTrail")
            .warn("T-McpAuditTrail: MCP 执行落历史失败（工具结果不受影响）", e)
    }
}
