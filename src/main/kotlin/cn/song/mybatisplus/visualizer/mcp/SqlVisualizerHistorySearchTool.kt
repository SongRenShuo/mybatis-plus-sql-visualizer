package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.openapi.project.Project
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * sqlv_history_search 工具（R14 T-HistorySearch / T-02）：历史落盘 SQL 检索——agent 通道
 * 首次触达 SQL 历史自动落盘资产（F-43 SqlLogAutoSaver；此前 `sqlv_render_logs` 只收 agent
 * 自带日志文本，落盘历史对其不可见）。
 *
 * 检索语义在 [HistorySearchService]（T-01 域中立纯逻辑；F94 改注：R16 UI 走
 * HistorySqlLogPanel 自身的 filterHistoryEntries，不经本服务）：关键词/正则/时间范围/
 * 来源文件四维过滤；范围默认当日（D1=A，`allDates=true` 显式扩展）、返回摘要+命中计数（D2=A）。
 * 逻辑分层同 [SqlVisualizerRenderLogsTool]：[formatResult]/[searchHistory] 纯函数可测。
 *
 * 只读口径：只读历史文件目录，不执行 SQL、不写任何盘（readOnlyHint 与实现一致）；
 * 零 PSI/索引依赖——不 requireSmart（dumb 模式下照样可用，检索的是磁盘文件）。
 * 目录解析与 HistorySqlLogPanel/SqlLogAutoSaver 同源：设置自定义保存目录优先，缺省
 * `.idea/sql-visualizer/sql-history/`（项目 basePath 兜底 user.home）。
 */
open class SqlVisualizerHistorySearchTool : McpToolsetSupport("sqlv_history_search") {

    @McpTool(name = "sqlv_history_search", title = "Search persisted SQL execution history")
    @McpDescription(
        "Search the plugin's persisted SQL history files (written by SQL log auto-save under " +
            ".idea/sql-visualizer/sql-history/, or the custom auto-save directory). Filters: 'keyword' " +
            "(case-insensitive substring in SQL text or mapper statement id), 'regex' (case-insensitive " +
            "over SQL text), 'fromMs'/'toMs' (epoch millis bounds on the entry timestamp), 'sourceFile' " +
            "(case-insensitive substring on the originating source path). By default only today's entries " +
            "are searched; pass allDates=true to search the whole history directory. Returns match summaries " +
            "(timestamp, SQL preview, source location, duration, mapper statement id) with hit counts, " +
            "newest first, bounded by maxResults (default 20, hard cap 100) — never full dumps. " +
            "Read-only: reads history files only; never executes SQL and never writes anything.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun sqlv_history_search(
        keyword: String = "",
        regex: String = "",
        fromMs: Long? = null,
        toMs: Long? = null,
        sourceFile: String = "",
        allDates: Boolean = false,
        maxResults: Int? = null,
    ): String = audit("sqlv_history_search", historySearchArgsSummary(keyword, regex, fromMs, toMs, sourceFile, allDates)) {
        val project = requireProject()
        val query = HistorySearchService.Query(
            keyword = keyword,
            regex = regex,
            fromMs = fromMs,
            toMs = toMs,
            sourceFile = sourceFile,
            allDates = allDates,
            maxResults = maxResults ?: HistorySearchService.DEFAULT_MAX_RESULTS,
        )
        formatResult(searchHistory(resolveHistoryDir(project), query))
    }

    /** 检索执行 + 入参异常转译（纯逻辑核之上的薄壳，普通 JUnit 可直测）。 */
    internal fun searchHistory(dir: String, query: HistorySearchService.Query): HistorySearchService.Result =
        try {
            HistorySearchService.search(dir, query)
        } catch (e: HistorySearchService.InvalidQueryException) {
            throwExpected(McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, e.message ?: "invalid query"))
        }

    /**
     * 结果格式化（agent 面向文案，英文常量不进 bundle——McpToolErrors 同口径，纯函数可测）。
     * 0 命中是合法结果：正常返回 + 指引，不走错误通道（sqlv_render_logs 同款）。
     *
     * T-SAF-07（mcp.history_search.masking-not-applied）：sqlPreview 携带参数已回填为字面量
     * 的还原 SQL，此前该通道从不查阅 mcpMaskEnabled——「MCP 输出脱敏」不变式只在
     * sqlv_render_logs 成立，agent 可用关键字搜索历史提取敏感字面量。现与 renderLogs 同款：
     * [maskLiterals] null 读设置（服务未注册兜底关），true/false 供测试与调用方显式覆盖；
     * 打码经 [cn.song.mybatisplus.visualizer.util.DisplayMasker.maskDisplay]（双条件规则与
     * 采集 agent 同表）。
     */
    internal fun formatResult(
        result: HistorySearchService.Result,
        /** T-SAF-07：null 读设置 mcpMaskEnabled（默认关）；测试注入 true/false 钉行为。 */
        maskLiterals: Boolean? = null,
    ): String {
        if (result.totalHits == 0) {
            return buildString {
                append("No history entries matched.")
                append(" SQL history is persisted only when the plugin's log auto-save is enabled;")
                append(" history files live under .idea/sql-visualizer/sql-history/ (or the custom auto-save directory).")
                append(" Loosen the filters, or pass allDates=true to search beyond today.")
            }
        }
        return buildString {
            // T-SAF-07：脱敏开关（null 读设置，renderLogs 同款；只打码输出文本，不影响命中判定）
            val maskOn = maskLiterals ?: runCatching {
                PluginSettings.getInstance().state.mcpMaskEnabled
            }.getOrDefault(false)
            append("Found ").append(result.totalHits).append(" match(es)")
            append("; showing ").append(result.returned).append(" newest-first")
            if (result.truncated) {
                append(" (truncated — narrow the filters or raise 'maxResults' up to ")
                append(HistorySearchService.HARD_MAX_RESULTS)
                append(')')
            }
            append(". Scanned ").append(result.scannedFiles).append(" file(s), ")
            append(result.scannedEntries).append(" entries")
            if (result.scanTruncated) {
                append(" (warning: scan stopped at the entry cap — results may be incomplete)")
            }
            result.scopeStartMs?.let { append(", scope from ").append(TIMESTAMP_FMT.format(instantOf(it))) }
            append(".\n")
            result.hits.forEachIndexed { index, hit ->
                append("\n-- #").append(index + 1)
                append(' ').append(TIMESTAMP_FMT.format(instantOf(hit.timestamp)))
                append(" [").append(hit.source.lowercase()).append(']')
                hit.sourceFile?.let {
                    append(' ').append(it)
                    if (hit.sourceLine > 0) append(':').append(hit.sourceLine)
                }
                hit.executionTimeMs?.let { append(" (").append(it).append("ms)") }
                hit.databaseName?.let { append(" db=").append(it) }
                hit.mapperStatement?.let { append(" mapper=").append(it) }
                hit.errorText?.let { append(" error=").append(it) }
                append(" in ").append(hit.historyFile)
                // T-SAF-07：sqlPreview 与 renderLogs 的 restoredSql 同一数据类（字面量已回填），
                // 同一 mcpMaskEnabled 设置必须同一脱敏不变式
                append('\n')
                    .append(cn.song.mybatisplus.visualizer.util.DisplayMasker.maskDisplay(hit.sqlPreview, maskOn))
            }
        }
    }

    /** 历史目录解析：设置自定义保存目录优先；缺省 `.idea/sql-visualizer/sql-history/`
     *  （basePath 为空兜底 user.home——SqlLogAutoSaver.resolveDir 同构，宁就近不猜他处）。 */
    private fun resolveHistoryDir(project: Project): String {
        val custom = runCatching { PluginSettings.getInstance().state.logAutoSavePath }.getOrNull()
        if (!custom.isNullOrBlank()) return custom.trim()
        val base = project.basePath ?: System.getProperty("user.home")
        return "$base${File.separator}.idea${File.separator}${SqlLogAutoSaver.DATA_DIR_NAME}${File.separator}sql-history"
    }

    companion object {
        private val TIMESTAMP_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

        private fun instantOf(ms: Long): Instant = Instant.ofEpochMilli(ms)
    }
}

/**
 * 审计入参摘要（T-MAT-04：新增有入参面的工具显式传真实摘要，不落 "ok"）。
 * 顶层函数（executionArgsSummary 同款先例）：裸 JUnit 可直测，加载门面类不必初始化 Logger。
 * 只记过滤条件形态，不记任何 SQL 文本/参数值（脱敏口径对齐 T-RA 系列）。
 */
internal fun historySearchArgsSummary(
    keyword: String,
    regex: String,
    fromMs: Long?,
    toMs: Long?,
    sourceFile: String,
    allDates: Boolean,
): String = McpAuditLog.summarize(
    "keyword" to keyword.trim(),
    "regex" to regex.trim(),
    "fromMs" to fromMs,
    "toMs" to toMs,
    "sourceFile" to sourceFile.trim(),
    "allDates" to allDates,
)
