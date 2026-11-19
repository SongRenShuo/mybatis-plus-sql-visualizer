// T-SyncK2JMaster482 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerHistorySearchTool.kt 移植（R14 T-HistorySearch / T-02）
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver;
import com.intellij.mcpserver.annotations.McpDescription;
import com.intellij.mcpserver.annotations.McpTool;
import com.intellij.mcpserver.annotations.McpToolHintValue;
import com.intellij.mcpserver.annotations.McpToolHints;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * sqlv_history_search 工具（R14 T-HistorySearch / T-02）：历史落盘 SQL 检索——agent 通道
 * 首次触达 SQL 历史自动落盘资产（F-43 SqlLogAutoSaver；此前 {@code sqlv_render_logs} 只收 agent
 * 自带日志文本，落盘历史对其不可见）。
 *
 * <p>检索语义在 {@link HistorySearchService}（T-01 域中立纯逻辑；F94 改注：R16 UI 走
 * HistorySqlLogPanel 自身的 filterHistoryEntries，不经本服务）：关键词/正则/时间范围/
 * 来源文件四维过滤；范围默认当日（D1=A，{@code allDates=true} 显式扩展）、返回摘要+命中计数（D2=A）。
 * 逻辑分层同 {@link SqlVisualizerRenderLogsTool}：{@link #formatResult}/{@link #searchHistory} 纯函数可测。</p>
 *
 * <p>只读口径：只读历史文件目录，不执行 SQL、不写任何盘（readOnlyHint 与实现一致）；
 * 零 PSI/索引依赖——不 requireSmart（dumb 模式下照样可用，检索的是磁盘文件）。
 * 目录解析与 HistorySqlLogPanel/SqlLogAutoSaver 同源：设置自定义保存目录优先，缺省
 * {@code .idea/sql-visualizer/sql-history/}（项目 basePath 兜底 user.home）。</p>
 *
 * <p>移植注记：Kotlin {@code open class} 保持非 final（动态壳类须可继承）；挂起方法按
 * {@link McpToolsetSupport} 类头移植注记落为普通 Java 方法（同步审计块）；Kotlin 默认参数
 * （keyword/regex/sourceFile 空串、fromMs/toMs/maxResults 可空）保留为全参方法，入参 null 按空串归一
 * （与 {@link SqlVisualizerRenderLogsTool} 同款防御——Java 侧平台可能传 null）。</p>
 */
public class SqlVisualizerHistorySearchTool extends McpToolsetSupport {

    public SqlVisualizerHistorySearchTool() {
        super("sqlv_history_search");
    }

    @McpTool(name = "sqlv_history_search", title = "Search persisted SQL execution history")
    @McpDescription(
        description = "Search the plugin's persisted SQL history files (written by SQL log auto-save under "
            + ".idea/sql-visualizer/sql-history/, or the custom auto-save directory). Filters: 'keyword' "
            + "(case-insensitive substring in SQL text or mapper statement id), 'regex' (case-insensitive "
            + "over SQL text), 'fromMs'/'toMs' (epoch millis bounds on the entry timestamp), 'sourceFile' "
            + "(case-insensitive substring on the originating source path). By default only today's entries "
            + "are searched; pass allDates=true to search the whole history directory. Returns match summaries "
            + "(timestamp, SQL preview, source location, duration, mapper statement id) with hit counts, "
            + "newest first, bounded by maxResults (default 20, hard cap 100) — never full dumps. "
            + "Output tier (D219): 'detail' defaults to true (one summary block per hit including the SQL "
            + "preview, the historical contract); pass detail=false for a compact listing — header fields "
            + "only, no SQL preview body — when scanning many hits with a tight AI context budget. "
            + "Read-only: reads history files only; never executes SQL and never writes anything."
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE
    )
    public McpToolTextResult sqlv_history_search(
        String keyword,
        String regex,
        Long fromMs,
        Long toMs,
        String sourceFile,
        boolean allDates,
        Integer maxResults,
        Boolean detail
    ) {
        String kw = keyword != null ? keyword : "";
        String rx = regex != null ? regex : "";
        String sf = sourceFile != null ? sourceFile : "";
        return audit("sqlv_history_search",
            SqlVisualizerHistorySearchToolKt.historySearchArgsSummary(kw, rx, fromMs, toMs, sf, allDates),
            () -> {
                Project project = requireProject();
                HistorySearchService.Query query = new HistorySearchService.Query(
                    kw,
                    rx,
                    fromMs,
                    toMs,
                    sf,
                    allDates,
                    maxResults != null ? maxResults : HistorySearchService.DEFAULT_MAX_RESULTS
                );
                return formatResult(searchHistory(resolveHistoryDir(project), query), detail);
            });
    }

    /** 检索执行 + 入参异常转译（纯逻辑核之上的薄壳，普通 JUnit 可直测）。 */
    public HistorySearchService.Result searchHistory(String dir, HistorySearchService.Query query) {
        try {
            return HistorySearchService.search(dir, query);
        } catch (HistorySearchService.InvalidQueryException e) {
            throwExpected(McpToolErrors.code(
                McpToolErrors.INVALID_ARGUMENT,
                e.getMessage() != null ? e.getMessage() : "invalid query"));
            return null; // 不可达：throwExpected 必抛
        }
    }

    /**
     * 结果格式化（agent 面向文案，英文常量不进 bundle——McpToolErrors 同口径，纯函数可测）。
     * 0 命中是合法结果：正常返回 + 指引，不走错误通道（sqlv_render_logs 同款）。
     */
    public String formatResult(HistorySearchService.Result result) {
        return formatResult(result, null);
    }

    /**
     * 结果格式化（D219 全参形态）：detail=false 走紧凑清单——每命中一行头字段（时间/来源/位置/耗时/库/mapper/错误），
     * 不输出 SQL 预览正文；null/true 保持历史契约（摘要块 + 预览正文）。
     */
    public String formatResult(HistorySearchService.Result result, Boolean detail) {
        if (result.totalHits() == 0) {
            return "No history entries matched."
                + " SQL history is persisted only when the plugin's log auto-save is enabled;"
                + " history files live under .idea/sql-visualizer/sql-history/ (or the custom auto-save directory)."
                + " Loosen the filters, or pass allDates=true to search beyond today.";
        }
        StringBuilder out = new StringBuilder();
        out.append("Found ").append(result.totalHits()).append(" match(es)");
        out.append("; showing ").append(result.returned()).append(" newest-first");
        if (result.truncated()) {
            out.append(" (truncated — narrow the filters or raise 'maxResults' up to ");
            out.append(HistorySearchService.HARD_MAX_RESULTS);
            out.append(')');
        }
        out.append(". Scanned ").append(result.scannedFiles()).append(" file(s), ");
        out.append(result.scannedEntries()).append(" entries");
        if (result.scanTruncated()) {
            out.append(" (warning: scan stopped at the entry cap — results may be incomplete)");
        }
        if (result.scopeStartMs() != null) {
            out.append(", scope from ").append(TIMESTAMP_FMT.format(instantOf(result.scopeStartMs())));
        }
        out.append(".\n");
        boolean compact = detail == Boolean.FALSE;
        if (compact) {
            out.append("(compact listing — pass detail=true to include SQL previews)\n");
        }
        for (int index = 0; index < result.hits().size(); index++) {
            HistorySearchService.Hit hit = result.hits().get(index);
            out.append("\n-- #").append(index + 1);
            out.append(' ').append(TIMESTAMP_FMT.format(instantOf(hit.timestamp())));
            out.append(" [").append(hit.source().toLowerCase(Locale.ROOT)).append(']');
            if (hit.sourceFile() != null) {
                out.append(' ').append(hit.sourceFile());
                if (hit.sourceLine() > 0) {
                    out.append(':').append(hit.sourceLine());
                }
            }
            if (hit.executionTimeMs() != null) {
                out.append(" (").append(hit.executionTimeMs()).append("ms)");
            }
            if (hit.databaseName() != null) {
                out.append(" db=").append(hit.databaseName());
            }
            if (hit.mapperStatement() != null) {
                out.append(" mapper=").append(hit.mapperStatement());
            }
            if (hit.errorText() != null) {
                out.append(" error=").append(hit.errorText());
            }
            out.append(" in ").append(hit.historyFile());
            if (!compact) {
                out.append('\n').append(hit.sqlPreview());
            }
        }
        return out.toString();
    }

    /** 历史目录解析：设置自定义保存目录优先；缺省 {@code .idea/sql-visualizer/sql-history/}
     *  （basePath 为空兜底 user.home——SqlLogAutoSaver.resolveDir 同构，宁就近不猜他处）。 */
    private String resolveHistoryDir(Project project) {
        String custom = null;
        try {
            custom = PluginSettings.getInstance().getState().getLogAutoSavePath();
        } catch (Throwable ignored) {
            custom = null;
        }
        if (custom != null && !custom.isBlank()) {
            return custom.trim();
        }
        String base = project.getBasePath() != null ? project.getBasePath() : System.getProperty("user.home");
        return base + File.separator + ".idea" + File.separator + SqlLogAutoSaver.DATA_DIR_NAME
            + File.separator + "sql-history";
    }

    private static final DateTimeFormatter TIMESTAMP_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static Instant instantOf(long ms) {
        return Instant.ofEpochMilli(ms);
    }
}
