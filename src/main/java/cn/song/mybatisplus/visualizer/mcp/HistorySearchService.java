// T-SyncK2JMaster482 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/HistorySearchService.kt 移植（R14 T-HistorySearch / T-01）
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.log.LogSqlEntry;
import cn.song.mybatisplus.visualizer.log.SqlLogFileReader;
import kotlin.Pair;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 历史落盘检索器（R14 T-HistorySearch / T-01）：在 SQL 历史自动落盘文件
 * （SqlLogAutoSaver，默认 {@code .idea/sql-visualizer/sql-history/}）之上做过滤检索——
 * 读取面复用 {@link SqlLogFileReader}（不重造），检索语义四维：关键词 / 正则 / 时间范围 / 来源。
 *
 * <p><b>域中立（D3=A）</b>：本对象零 com.intellij、零 mcpserver 依赖，输入输出全是纯数据——
 * 当前唯一调用方=MCP 工具（SqlVisualizerHistorySearchTool），MCP 侧的文案格式化/入参校验
 * 转译在工具层，不在这里。F94 改注（T-PerfSweep25，文档漂移修正）：原注「与 R16 历史跨文件
 * 全文检索 UI 共用同一实现」与实况不符——R16 UI（HistorySqlLogPanel 的文件名/条目级检索）
 * 走自身的 filterHistoryEntries 实现，不经本服务；域中立形态保留为未来复用留位，非既成事实。</p>
 *
 * <p><b>「来源」维度的落盘实况（实施期重锚证伪）</b>：通道标记（SqlSource）在历史文件里
 * <b>仅 MCP 存活</b>——SqlLogParamCodec.encodeEntry 只对 MCP 落 {@code s} 键，控制台/agent 来源
 * 读回一律 UNKNOWN；头部 {@code source:} 段语义已被 parseSource 占用为源码位置。故四维过滤的
 * 「来源」按<b>来源文件（sourceFile）</b>实现（大小写不敏感子串，对 agent「找 UserService.java
 * 落过的 SQL」才有实用价值）；通道标记仅在命中元信息里透出，不做过滤维度。</p>
 *
 * <p><b>范围默认值（D1=A）</b>：默认只检索「今日」条目（条目时间戳 ≥ 当日本地零点——按条目
 * timestamp 过滤而非按文件名挑当日文件，滚动块/自定义模板/跨天追加文件一律天然正确）；
 * {@code allDates=true} 扩展到目录全部日期。默认范围内显式 {@code fromMs} 早于今日起点<b>不放宽</b>
 * 默认范围（要跨天必须显式 allDates=true，防 agent 无意全量扫描拉爆上下文）。</p>
 *
 * <p><b>返回体（D2=A）</b>：条目摘要（时间戳 + SQL 预览 [MAX_PREVIEW_LINES]/[MAX_PREVIEW_CHARS] 截断 +
 * 来源位置/耗时/落盘文件）+ 命中计数；<b>不返回全文</b>——agent 需要更多上下文时自行缩小关键词
 * 再查。{@code maxResults} 越界钳制不抛错；命中按时间戳<b>最新在前</b>，超限置 {@code truncated}。</p>
 *
 * <p><b>性能预算（F55/F56/F65 三合一后）</b>：目录扫描走 [SqlLogFileReader.forEachEntry] 条目粒度
 * 流式解析（单条 SQL 级峰值）＋边读边滤只留命中（内存只驻命中集）＋文件名倒序（封顶截断丢的
 * 是最旧侧，最新数据永远先入）＋名日期/mtime 双旧的文件零 IO 预跳过（默认「今日」不再读全目录）；
 * 扫描条目封顶 [MAX_SCAN_ENTRIES]（条目粒度计数，解析条目数恒 ≤ 上限），超限置 {@code scanTruncated}
 * 即停——目录被保留上限失控（maxFiles=0 全量保留）时检索仍有时界。目录不存在/空目录 = 合法零命中
 * （自动保存开关默认关，历史可能确实不存在——零命中是正常结果不是错误）。</p>
 *
 * <p><b>排序兜底（V14b）</b>：多文件读取顺序与滚动块排序语义不做保证，命中输出前按
 * timestamp 自行排序（Reader 的 per-file 排序只作输入预备）。</p>
 *
 * <p>移植注记：master 的 {@code object} 按本仓惯例落为 final class + {@link #INSTANCE} + 全静态成员；
 * 嵌套 data class（Query/Hit/Result）落为静态 record（带字段注释），Kotlin 默认参数以「全参规范构造 +
 * 无参缺省重载」表达；{@code Regex(pattern, IGNORE_CASE)} 落为
 * {@code Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)}，{@code containsMatchIn} 落为
 * {@code Matcher.find()}；{@code String.contains(other, ignoreCase=true)} 落为 regionMatches 子串扫描
 * （与 Kotlin 实现同口径，规避默认 Locale 差异）。</p>
 */
public final class HistorySearchService {

    private HistorySearchService() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final HistorySearchService INSTANCE = new HistorySearchService();

    /** 默认返回条数（D2=A：摘要 + 计数，防 agent 上下文爆炸）。 */
    public static final int DEFAULT_MAX_RESULTS = 20;

    /** 返回条数硬上限（maxResults 越界钳制到此）。 */
    public static final int HARD_MAX_RESULTS = 100;

    /** 单条 SQL 预览的行数上限。 */
    public static final int MAX_PREVIEW_LINES = 8;

    /** 单条 SQL 预览的字符上限（含省略号）。 */
    public static final int MAX_PREVIEW_CHARS = 600;

    /** 扫描条目封顶：超出后停止读后续文件（scanTruncated=true），防失控目录无界扫描。 */
    private static final int MAX_SCAN_ENTRIES = 200_000;

    /** 入参形态非法（正则编译失败 / 时间范围倒置）——MCP 侧转译为 INVALID_ARGUMENT。 */
    public static class InvalidQueryException extends IllegalArgumentException {
        public InvalidQueryException(String message) {
            super(message);
        }
    }

    /**
     * 检索查询（全部字段可选；空查询 = 默认范围内的全部条目）。
     *
     * @param keyword     关键词（大小写不敏感子串，匹配 rawSql/restoredSql/mapperStatement）
     * @param regex       正则（大小写不敏感，匹配 rawSql/restoredSql；编译失败抛 [InvalidQueryException]）
     * @param fromMs      时间范围下界（含；epoch 毫秒，按条目 timestamp）
     * @param toMs        时间范围上界（含）
     * @param sourceFile  来源文件过滤（大小写不敏感子串，匹配条目 sourceFile；
     *                    通道标记在落盘数据里仅 MCP 存活，故「来源」维度按来源文件实现）
     * @param allDates    false（默认）= 只检索今日条目（D1=A）；true = 检索目录全部日期
     * @param maxResults  返回条数上限（越界钳制 1..[HARD_MAX_RESULTS]）
     */
    public record Query(
        /** 关键词（大小写不敏感子串）。 */
        String keyword,
        /** 正则（大小写不敏感；编译失败抛 InvalidQueryException）。 */
        String regex,
        /** 时间范围下界（含；epoch 毫秒）。 */
        Long fromMs,
        /** 时间范围上界（含）。 */
        Long toMs,
        /** 来源文件过滤（大小写不敏感子串）。 */
        String sourceFile,
        /** false = 只检索今日条目；true = 检索目录全部日期。 */
        boolean allDates,
        /** 返回条数上限（越界钳制）。 */
        int maxResults
    ) {
        /** Kotlin 默认参数等价重载：全字段缺省（空查询 = 默认范围内的全部条目）。 */
        public Query() {
            this(null, null, null, null, null, false, DEFAULT_MAX_RESULTS);
        }
    }

    /** 命中条目摘要（D2=A：不含 SQL 全文）。 */
    public record Hit(
        /** 条目时间戳（epoch 毫秒）。 */
        long timestamp,
        /** SQL 预览（截断）。 */
        String sqlPreview,
        /** 来源（SqlSource 名）。 */
        String source,
        /** 来源文件（可空）。 */
        String sourceFile,
        /** 来源行号（0 表示未知）。 */
        int sourceLine,
        /** 执行耗时（毫秒；可空）。 */
        Long executionTimeMs,
        /** 目标数据库标注（可空）。 */
        String databaseName,
        /** mapper 语句 id（可空）。 */
        String mapperStatement,
        /** 执行报错（可空）。 */
        String errorText,
        /** 命中条目所在的落盘文件名。 */
        String historyFile
    ) {
    }

    /** 检索结果：命中摘要 + 计数（totalHits/returned/truncated 供调用方自行组「截断说明」）。 */
    public record Result(
        /** 总命中数（排序后、截断前）。 */
        int totalHits,
        /** 实际返回条数。 */
        int returned,
        /** 是否因 maxResults 截断。 */
        boolean truncated,
        /** 扫描的文件数。 */
        int scannedFiles,
        /** 扫描的条目数。 */
        int scannedEntries,
        /** 是否因条目封顶中断扫描。 */
        boolean scanTruncated,
        /** 本次生效的范围下界（epoch 毫秒）；allDates=true 且未给 fromMs 时为 null。 */
        Long scopeStartMs,
        /** 命中摘要清单（最新在前）。 */
        List<Hit> hits
    ) {
    }

    /**
     * 文件目录入口（F55/F56/F65 三合一重写，D31 道 A）：扫目录下全部 .sql（大小写不敏感），
     * <b>文件名倒序（新→旧）</b>逐文件<b>条目粒度</b>流式解析（[SqlLogFileReader.forEachEntry]），
     * 边读边滤只留命中——一次改动同收三发现：
     * <ul>
     *   <li>F55 截断丢旧不丢新：旧环升序读盘，封顶截掉的恰是最新文件（默认「今日」极端情形
     *       旧文件耗尽预算读不到今天的文件，与「默认当日防倾泻」意图相悖）；</li>
     *   <li>F56 封顶前不再整文件驻留：旧环 readFromFile 全量物化后 perFile 持有全部已读条目
     *       （最坏峰值＝200k 条+最后文件全量），现内存只驻命中条目（峰值≈命中集）；</li>
     *   <li>F65 默认今日省 IO：名日期与 mtime 均早于范围起点的文件零 IO 预跳过（[preSkippable]），
     *       旧环 allDates=false 仍要读全目录才能按 timestamp 筛。</li>
     * </ul>
     */
    public static Result search(String dir, Query query) {
        return search(dir, query, System.currentTimeMillis());
    }

    /** 全参形态（Kotlin nowMs 默认参数显式入口；scanEntryCap 缺省 [MAX_SCAN_ENTRIES]）。 */
    public static Result search(String dir, Query query, long nowMs) {
        return search(dir, query, nowMs, MAX_SCAN_ENTRIES);
    }

    /**
     * 全参形态（scanEntryCap = 扫描条目封顶测试缝）。
     *
     * @param scanEntryCap 扫描条目封顶（默认 [MAX_SCAN_ENTRIES]；测试缝）——<b>条目粒度</b>计数，
     *                    解析条目数恒 ≤ cap，超限置 scanTruncated 即停（后续文件/条目都不再读）
     */
    public static Result search(String dir, Query query, long nowMs, int scanEntryCap) {
        CompiledFilters compiled = CompiledFilters.of(query, nowMs);
        File directory = new File(dir);
        File[] listed = directory.listFiles(f -> f.isFile() && extensionOf(f).equalsIgnoreCase("sql"));
        List<File> files = new ArrayList<>();
        if (listed != null) {
            for (File f : listed) {
                files.add(f);
            }
        }
        files.sort(Comparator.comparing(File::getName).reversed()); // F55：新→旧（默认日期命名下即最新文件优先）
        // Java 闭包不可捕获可变局部（Kotlin 可），计数/截断标记用单元素数组承载
        int[] scannedEntries = {0};
        boolean[] scanTruncated = {false};
        List<Pair<String, LogSqlEntry>> matched = new ArrayList<>();
        for (File f : files) {
            // F65：预跳过先于封顶判定——范围外的文件零 IO 跳过，不占扫描预算也不触发截断标记
            // （「截断」语义＝可能有相关数据未读；预跳过文件按判据不含范围内数据）
            if (preSkippable(f, compiled.scopeFrom)) {
                continue;
            }
            if (scannedEntries[0] >= scanEntryCap) {
                scanTruncated[0] = true;
                break;
            }
            boolean[] stop = {false};
            SqlLogFileReader.INSTANCE.forEachEntry(f, e -> {
                if (scannedEntries[0] >= scanEntryCap) { // F56：条目粒度封顶（下一条都不再解析）
                    scanTruncated[0] = true;
                    stop[0] = true;
                    return false;
                }
                scannedEntries[0]++;
                if (compiled.matches(e)) {
                    matched.add(new Pair<>(f.getName(), e)); // 边读边滤只留命中
                }
                return true;
            });
            if (stop[0]) {
                break;
            }
        }
        return buildResult(
            matched, compiled,
            files.size(),
            scannedEntries[0],
            scanTruncated[0]
        );
    }

    /** Kotlin 默认参数等价重载：scannedFiles/scannedEntries/scanTruncated 缺省（纯逻辑核直调）。 */
    public static Result search(List<Pair<String, List<LogSqlEntry>>> perFile, Query query, long nowMs) {
        int scannedEntries = 0;
        for (Pair<String, List<LogSqlEntry>> entry : perFile) {
            scannedEntries += entry.getSecond().size();
        }
        return search(perFile, query, nowMs, perFile.size(), scannedEntries, false);
    }

    /**
     * 纯逻辑核（域中立测试缝）：已在内存的「落盘文件名 → 条目」清单上执行四维过滤。
     * F55 三合一后与目录扫描环共用 [CompiledFilters]（一次编译逐条匹配）与 [buildResult]
     * （排序/截断/摘要收口）——两入口的过滤与输出语义单一实现源。
     */
    public static Result search(
        List<Pair<String, List<LogSqlEntry>>> perFile,
        Query query,
        long nowMs,
        int scannedFiles,
        int scannedEntries,
        boolean scanTruncated
    ) {
        CompiledFilters compiled = CompiledFilters.of(query, nowMs);
        List<Pair<String, LogSqlEntry>> matched = new ArrayList<>();
        for (Pair<String, List<LogSqlEntry>> fileEntry : perFile) {
            String fileName = fileEntry.getFirst();
            for (LogSqlEntry e : fileEntry.getSecond()) {
                if (compiled.matches(e)) {
                    matched.add(new Pair<>(fileName, e));
                }
            }
        }
        return buildResult(
            matched, compiled,
            scannedFiles,
            scannedEntries,
            scanTruncated
        );
    }

    /**
     * 编译后的过滤条件（目录扫描环与纯逻辑核共用）——入参校验（非法正则/时间倒置抛
     * [InvalidQueryException]）与条件编译只做一次，扫描环内逐条 O(1) 匹配。
     */
    private static final class CompiledFilters {
        final int maxResults;
        final String keyword;
        final Pattern regex;
        final String sourceFileFilter;
        final Long scopeFrom;
        final Long toMs;

        CompiledFilters(int maxResults, String keyword, Pattern regex, String sourceFileFilter, Long scopeFrom, Long toMs) {
            this.maxResults = maxResults;
            this.keyword = keyword;
            this.regex = regex;
            this.sourceFileFilter = sourceFileFilter;
            this.scopeFrom = scopeFrom;
            this.toMs = toMs;
        }

        /** 四维匹配（范围/关键词/正则/来源文件）——语义与旧纯核的 inScope+textMatches+sourceFilter 逐字一致。 */
        boolean matches(LogSqlEntry e) {
            if (scopeFrom != null && e.timestamp() < scopeFrom) {
                return false;
            }
            if (toMs != null && e.timestamp() > toMs) {
                return false;
            }
            if (keyword != null) {
                boolean keywordHit = containsIgnoreCase(e.rawSql(), keyword)
                    || containsIgnoreCase(e.restoredSql(), keyword)
                    || (e.mapperStatement() != null && containsIgnoreCase(e.mapperStatement(), keyword));
                if (!keywordHit) {
                    return false;
                }
            }
            if (regex != null && !regex.matcher(e.rawSql()).find() && !regex.matcher(e.restoredSql()).find()) {
                return false;
            }
            return sourceFileFilter == null
                || (e.sourceFile() != null && containsIgnoreCase(e.sourceFile(), sourceFileFilter));
        }

        static CompiledFilters of(Query query, long nowMs) {
            int maxResults = Math.max(1, Math.min(HARD_MAX_RESULTS, query.maxResults()));
            String keyword = trimmedOrNull(query.keyword());
            Pattern regex = null;
            String regexPattern = trimmedOrNull(query.regex());
            if (regexPattern != null) {
                try {
                    regex = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException e) {
                    throw new InvalidQueryException("invalid regex '" + regexPattern + "': " + e.getMessage());
                }
            }
            String sourceFileFilter = trimmedOrNull(query.sourceFile());
            if (query.fromMs() != null && query.toMs() != null && query.fromMs() > query.toMs()) {
                throw new InvalidQueryException(
                    "invalid time range: fromMs (" + query.fromMs() + ") > toMs (" + query.toMs() + ")");
            }
            // D1=A：默认范围下界 = 当日本地零点；allDates 或显式更晚的 fromMs 才放宽
            long todayStart = startOfLocalDay(nowMs);
            Long scopeFrom;
            if (query.allDates()) {
                scopeFrom = query.fromMs();
            } else {
                scopeFrom = Math.max(todayStart, query.fromMs() != null ? query.fromMs() : Long.MIN_VALUE);
            }
            return new CompiledFilters(maxResults, keyword, regex, sourceFileFilter, scopeFrom, query.toMs());
        }
    }

    /**
     * 命中排序（V14b：输出前按时间戳自行排——最新在前，稳定序：同毫秒按落盘文件名 + 文件内序号）
     * + 截断 + 摘要组装（目录扫描环与纯逻辑核共用收口）。
     */
    private static Result buildResult(
        List<Pair<String, LogSqlEntry>> matched,
        CompiledFilters compiled,
        int scannedFiles,
        int scannedEntries,
        boolean scanTruncated
    ) {
        List<Pair<String, LogSqlEntry>> ordered = new ArrayList<>(matched);
        ordered.sort(
            Comparator.<Pair<String, LogSqlEntry>>comparingLong(p -> p.getSecond().timestamp()).reversed()
                .thenComparing(p -> p.getFirst())
                .thenComparingLong(p -> p.getSecond().sequenceNumber())
        );

        List<Hit> hits = new ArrayList<>();
        int limit = Math.min(compiled.maxResults, ordered.size());
        for (int i = 0; i < limit; i++) {
            Pair<String, LogSqlEntry> fileEntry = ordered.get(i);
            String fileName = fileEntry.getFirst();
            LogSqlEntry e = fileEntry.getSecond();
            hits.add(new Hit(
                e.timestamp(),
                preview(e.restoredSql()),
                e.source().name(),
                e.sourceFile(),
                e.sourceLine(),
                e.executionTimeMs(),
                e.databaseName(),
                e.mapperStatement(),
                e.errorText(),
                fileName
            ));
        }
        return new Result(
            ordered.size(),
            hits.size(),
            ordered.size() > hits.size(),
            scannedFiles,
            scannedEntries,
            scanTruncated,
            compiled.scopeFrom,
            hits
        );
    }

    /** 文件名携带的日期（yyyy-MM-dd 首个匹配；滚动块 {@code .2.sql} 后缀不影响）——无日期返回 null。 */
    private static final Pattern FILE_NAME_DAY_REGEX = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    private static Long fileNameDayStartMs(String name) {
        java.util.regex.Matcher mr = FILE_NAME_DAY_REGEX.matcher(name);
        if (!mr.find()) {
            return null;
        }
        try {
            return java.time.LocalDate.of(
                    Integer.parseInt(mr.group(1)), Integer.parseInt(mr.group(2)), Integer.parseInt(mr.group(3)))
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * F65 预跳过判据（测试直测缝）：文件可整体跳过不读盘，当且仅当——范围下界存在，
     * 且文件<b>名日期日</b>与 <b>mtime</b> 都早于该起点。
     *
     * <p>为什么必须 mtime 兜底（对「仅凭名日期」的收敛，防跨天追加误伤）：滚动块按体积滚动、
     * 名字是<b>开块日</b>——名为昨日但今日凌晨仍被追加的文件真实存在（D1=A 当年正因此选择按条目
     * 时间戳过滤而非按文件名挑当日文件）。mtime 早于范围起点 ⇒ 起点后从未写入 ⇒ 全部条目
     * 时间戳 < 起点，跳过零误伤；名日期判据覆盖默认模板主场景（旧日期文件且未被追加＝两名双旧
     * 即跳）。名字无日期（自定义模板）不跳——正确性优先于优化面。</p>
     */
    public static boolean preSkippable(File file, Long scopeFrom) {
        if (scopeFrom == null) {
            return false;
        }
        if (file.lastModified() >= scopeFrom) {
            return false;
        }
        Long nameDay = fileNameDayStartMs(file.getName());
        if (nameDay == null) {
            return false;
        }
        return nameDay < startOfLocalDay(scopeFrom);
    }

    /** 当日本地零点（epoch 毫秒）。 */
    private static long startOfLocalDay(long nowMs) {
        return Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** SQL 预览（D2=A）：前 [MAX_PREVIEW_LINES] 行且 ≤ [MAX_PREVIEW_CHARS] 字符，截断带省略号。 */
    private static String preview(String sql) {
        List<String> lines = sql.lines().toList();
        List<String> taken = lines.subList(0, Math.min(MAX_PREVIEW_LINES, lines.size()));
        String out = String.join("\n", taken);
        if (out.length() > MAX_PREVIEW_CHARS) {
            out = out.substring(0, MAX_PREVIEW_CHARS - 1) + "…";
        } else if (lines.size() > taken.size()) {
            out += "\n…";
        }
        return out;
    }

    /** Kotlin {@code String?.trim()?.takeIf { it.isNotEmpty() }} 等价（空白归 null）。 */
    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Kotlin {@code String.contains(other, ignoreCase = true)} 等价（regionMatches 子串扫描）。 */
    private static boolean containsIgnoreCase(String text, String sub) {
        if (sub.isEmpty()) {
            return true;
        }
        int max = text.length() - sub.length();
        for (int i = 0; i <= max; i++) {
            if (text.regionMatches(true, i, sub, 0, sub.length())) {
                return true;
            }
        }
        return false;
    }

    /** Kotlin {@code File.extension} 等价（末个 {@code .} 之后；无点返回空串）。 */
    private static String extensionOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
