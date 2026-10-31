package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.util.DisplayMasker
import cn.song.mybatisplus.visualizer.util.SqlStatementSplitter

/**
 * R36（V46 证伪②定案）：回放脚本组装——把预览编辑器里的 SQL 串成**单个可直接执行的
 * `.sql` 脚本**。入参 SQL 的两项前提已由上游保证：
 *  - **参数已填回**：编辑器正文即预览链 `renderSql` → `backfillParamsBySource` 的回填产物
 *    （历史条目经 R51 recall 链回落后同样带参数），本类不再做参数替换；
 *  - **方言适配**：语句本体已由生成链按当前方言渲染（[Dialect]），本类**不改写语句本体**
 *    （同 O-91 原则），只按方言补事务包裹语句。
 *
 * 与 O-91 产物先验的关系（V46 第二条的实测裁量）：
 *  - O-91 产物＝[PortableSqlRow] 行集，走 [SqlExportService.formatPortable] 的 CSV/TSV/TXT
 *    **三格式通道**，形态是「机器可读行集 + dialect/kind/upsert 三列标注」；
 *  - R36 产物＝**单个可执行 .sql**（新产物形态）。通道**复用** [SqlExportService.exportToFile]
 *    写盘与 `displayMaskEnabled` 脱敏形参先例；**不扩** [SqlExportFormat] 枚举——加第 4 个值会
 *    连带 R4 三格式对话框与 format/formatPortable 的分支面，超出本件范围（登记为边界）。
 *
 * 本类只做三件事：①语句切分 + 结尾分号规范化（保证可直接执行）②可选事务包裹
 * ③注释头（方言/条数/事务态/脱敏态，机器与人都可读）。
 *
 * 脱敏（R60 口径对齐）：[render] 的 `mask` 形参开 → 逐语句 [DisplayMasker.maskDisplay]
 * （投影级，调用方入参与数据层条目不动），并在注释头标 `masked`——脱敏后字面量成 `***`、
 * 脚本不再逐字可执行属**预期**（用户在设置里开启了展示脱敏即选择了交付形态）；
 * 默认关闭（[cn.song.mybatisplus.visualizer.config.PluginSettings.State.displayMaskEnabled]
 * 默认 false）时为逐字可执行脚本。
 */
object ReplayScriptExport {

    /** 产物扩展名（`.sql` 可执行脚本）。 */
    const val EXT = "sql"

    /** 事务包裹尾语句。 */
    const val COMMIT_STATEMENT = "COMMIT;"

    /** 注释头首行标识。 */
    const val HEADER_TAG = "SQL Visualizer replay script"

    /**
     * 回放脚本组装结果（纯数据，渲染与 UI 说明共用）。
     *
     * @param dialectName 当前方言名（注释头 `dialect:` 段）
     * @param statements 切分并规范化前的语句原文（渲染时逐条补结尾分号）
     * @param transactionBegin 本方言显式事务开始语句（[Dialect.transactionBegin]），null = 无
     * @param wrapped **生效**的事务包裹（方言无显式开始时强制 false）
     * @param wrapRequested 调用方是否请求了包裹（与 [wrapped] 合判降级）
     */
    data class Plan(
        val dialectName: String,
        val statements: List<String>,
        val transactionBegin: String?,
        val wrapped: Boolean,
        val wrapRequested: Boolean,
    ) {
        /** 请求了但方言不支持（UI 据此说明诚实降级原因）。 */
        val wrapUnsupported: Boolean get() = wrapRequested && !wrapped
    }

    /** 语句切分（[SqlStatementSplitter.split]，含 BEGIN/END 块与引号内分号感知）并剔除空白段。 */
    fun statementsOf(sql: String): List<String> =
        SqlStatementSplitter.split(sql).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 组装计划；无可执行语句（空/全空白）返回 null（调用方不发导出，同 O-91 空行集口径）。
     *
     * [wrapTransaction] 仅在 [Dialect.transactionBegin] 非 null 时生效——无显式事务开始语句的
     * 方言（Oracle/DM）走 [Plan.wrapUnsupported]，脚本诚实省略包裹（见 [Dialect.transactionBegin]）。
     */
    fun plan(sql: String, dialect: Dialect, wrapTransaction: Boolean): Plan? {
        val statements = statementsOf(sql)
        if (statements.isEmpty()) return null
        val begin = dialect.transactionBegin
        return Plan(
            dialectName = dialect.name,
            statements = statements,
            transactionBegin = begin,
            wrapped = wrapTransaction && begin != null,
            wrapRequested = wrapTransaction,
        )
    }

    /**
     * 渲染脚本正文。
     *
     * @param mask 开 → 逐语句投影级脱敏（[DisplayMasker.maskDisplay]）＋头部 `masked:` 标注
     * @param allowList 白名单豁免随调用方设置贯穿（UI-001 修复；为空时与旧签名逐字等价）
     * @param generatedAt 生成时间文本（调用方传 [SqlExportService.formatTime]，null 则省略该行）
     */
    fun render(plan: Plan, mask: Boolean = false, generatedAt: String? = null, allowList: List<String> = emptyList()): String {
        val effective =
            if (mask) plan.statements.map { DisplayMasker.maskDisplay(it, DisplayMasker.MaskMode.MASK, allowList) } else plan.statements
        val sb = StringBuilder()
        sb.append("-- ").append(HEADER_TAG).append('\n')
        if (!generatedAt.isNullOrBlank()) sb.append("-- generated: ").append(generatedAt).append('\n')
        sb.append("-- dialect: ").append(plan.dialectName).append('\n')
        sb.append("-- statements: ").append(plan.statements.size).append('\n')
        sb.append("-- transaction: ").append(transactionLine(plan)).append('\n')
        if (mask) {
            sb.append("-- masked: literal values replaced with *** (display mask enabled)\n")
        }
        if (plan.wrapped) sb.append(plan.transactionBegin).append(";\n")
        effective.forEach { raw ->
            sb.append(raw.trimEnd().trimEnd(';').trim()).append(";\n")
        }
        if (plan.wrapped) sb.append(COMMIT_STATEMENT).append('\n')
        return sb.toString()
    }

    /** 注释头的 `transaction:` 段（三态：包裹 / 未请求 / 请求但方言无显式事务语句）。 */
    private fun transactionLine(plan: Plan): String = when {
        plan.wrapped -> "${plan.transactionBegin} ... COMMIT (use ROLLBACK in place of COMMIT to undo)"
        plan.wrapUnsupported -> "none (${plan.dialectName} has no explicit transaction-start statement)"
        else -> "none"
    }
}
