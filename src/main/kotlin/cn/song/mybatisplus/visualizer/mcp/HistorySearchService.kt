package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.SqlLogFileReader
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * 历史落盘检索器（R14 T-HistorySearch / T-01）：在 SQL 历史自动落盘文件
 * （SqlLogAutoSaver，默认 `.idea/sql-visualizer/sql-history/`）之上做过滤检索——
 * 读取面复用 [SqlLogFileReader]（不重造），检索语义四维：关键词 / 正则 / 时间范围 / 来源。
 *
 * **域中立（D3=A）**：本对象零 com.intellij、零 mcpserver 依赖，输入输出全是纯数据——
 * 当前唯一调用方=MCP 工具（SqlVisualizerHistorySearchTool），MCP 侧的文案格式化/入参校验
 * 转译在工具层，不在这里。F94 改注（T-PerfSweep25，文档漂移修正）：原注「与 R16 历史跨文件
 * 全文检索 UI 共用同一实现」与实况不符——R16 UI（HistorySqlLogPanel 的文件名/条目级检索）
 * 走自身的 filterHistoryEntries 实现，不经本服务；域中立形态保留为未来复用留位，非既成事实。
 *
 * **「来源」维度的落盘实况（实施期重锚证伪）**：通道标记（SqlSource）在历史文件里
 * **仅 MCP 存活**——SqlLogParamCodec.encodeEntry 只对 MCP 落 `s` 键，控制台/agent 来源
 * 读回一律 UNKNOWN；头部 `source:` 段语义已被 parseSource 占用为源码位置。故四维过滤的
 * 「来源」按**来源文件（sourceFile）**实现（大小写不敏感子串，对 agent「找 UserService.java
 * 落过的 SQL」才有实用价值）；通道标记仅在命中元信息里透出，不做过滤维度。
 *
 * **范围默认值（D1=A）**：默认只检索「今日」条目（条目时间戳 ≥ 当日本地零点——按条目
 * timestamp 过滤而非按文件名挑当日文件，滚动块/自定义模板/跨天追加文件一律天然正确）；
 * `allDates=true` 扩展到目录全部日期。默认范围内显式 `fromMs` 早于今日起点**不放宽**
 * 默认范围（要跨天必须显式 allDates=true，防 agent 无意全量扫描拉爆上下文）。
 *
 * **返回体（D2=A）**：条目摘要（时间戳 + SQL 预览 [MAX_PREVIEW_LINES]/[MAX_PREVIEW_CHARS] 截断 +
 * 来源位置/耗时/落盘文件）+ 命中计数；**不返回全文**——agent 需要更多上下文时自行缩小关键词
 * 再查。`maxResults` 越界钳制不抛错；命中按时间戳**最新在前**，超限置 `truncated`。
 *
 * **性能预算（F55/F56/F65 三合一后）**：目录扫描走 [SqlLogFileReader.forEachEntry] 条目粒度
 * 流式解析（单条 SQL 级峰值）＋边读边滤只留命中（内存只驻命中集）＋文件名倒序（封顶截断丢的
 * 是最旧侧，最新数据永远先入）＋名日期/mtime 双旧的文件零 IO 预跳过（默认「今日」不再读全目录）；
 * 扫描条目封顶 [MAX_SCAN_ENTRIES]（条目粒度计数，解析条目数恒 ≤ 上限），超限置 `scanTruncated`
 * 即停——目录被保留上限失控（maxFiles=0 全量保留）时检索仍有时界。目录不存在/空目录 = 合法零命中
 * （自动保存开关默认关，历史可能确实不存在——零命中是正常结果不是错误）。
 *
 * **排序兜底（V14b）**：多文件读取顺序与滚动块排序语义不做保证，命中输出前按
 * timestamp 自行排序（Reader 的 per-file 排序只作输入预备）。
 */
object HistorySearchService {

    /** 默认返回条数（D2=A：摘要 + 计数，防 agent 上下文爆炸）。 */
    const val DEFAULT_MAX_RESULTS = 20

    /** 返回条数硬上限（maxResults 越界钳制到此）。 */
    const val HARD_MAX_RESULTS = 100

    /** 单条 SQL 预览的行数上限。 */
    const val MAX_PREVIEW_LINES = 8

    /** 单条 SQL 预览的字符上限（含省略号）。 */
    const val MAX_PREVIEW_CHARS = 600

    /** 扫描条目封顶：超出后停止读后续文件（scanTruncated=true），防失控目录无界扫描。 */
    private const val MAX_SCAN_ENTRIES = 200_000

    // ---------------- T-SAF-24（mcp.history_search.agent-regex-unbounded-backtracking） ----------------

    /** 正则模式长度上限：超长模式多携带病态构造，先于编译拒绝。 */
    const val MAX_REGEX_PATTERN_LENGTH = 256

    /** 单条目正则求值预算默认值（纳秒）：超限条目记 miss（不整查失败）。 */
    const val DEFAULT_ENTRY_REGEX_BUDGET_NS = 50_000_000L

    /** 整次检索正则累计预算默认值（纳秒）：超限抛 [InvalidQueryException]（结构化失败）。 */
    const val DEFAULT_TOTAL_REGEX_BUDGET_NS = 2_000_000_000L

    /** 预算缝（测试注入；生产用 [DEFAULT_ENTRY_REGEX_BUDGET_NS] 默认）。 */
    @Volatile
    internal var entryRegexBudgetNs: Long = DEFAULT_ENTRY_REGEX_BUDGET_NS

    /** 预算缝（测试注入；生产用 [DEFAULT_TOTAL_REGEX_BUDGET_NS] 默认）。 */
    @Volatile
    internal var totalRegexBudgetNs: Long = DEFAULT_TOTAL_REGEX_BUDGET_NS

    /** 入参形态非法（正则编译失败 / 时间范围倒置）——MCP 侧转译为 INVALID_ARGUMENT。 */
    class InvalidQueryException(message: String) : IllegalArgumentException(message)

    /**
     * 检索查询（全部字段可选；空查询 = 默认范围内的全部条目）。
     *
     * @param keyword 关键词（大小写不敏感子串，匹配 rawSql/restoredSql/mapperStatement）
     * @param regex   正则（大小写不敏感，匹配 rawSql/restoredSql；编译失败抛 [InvalidQueryException]）
     * @param fromMs  时间范围下界（含；epoch 毫秒，按条目 timestamp）
     * @param toMs    时间范围上界（含）
     * @param sourceFile 来源文件过滤（大小写不敏感子串，匹配条目 sourceFile；
     *                   通道标记在落盘数据里仅 MCP 存活，故「来源」维度按来源文件实现）
     * @param allDates false（默认）= 只检索今日条目（D1=A）；true = 检索目录全部日期
     * @param maxResults 返回条数上限（越界钳制 1..[HARD_MAX_RESULTS]）
     */
    data class Query(
        val keyword: String? = null,
        val regex: String? = null,
        val fromMs: Long? = null,
        val toMs: Long? = null,
        val sourceFile: String? = null,
        val allDates: Boolean = false,
        val maxResults: Int = DEFAULT_MAX_RESULTS,
    )

    /** 命中条目摘要（D2=A：不含 SQL 全文）。 */
    data class Hit(
        val timestamp: Long,
        val sqlPreview: String,
        val source: String,
        val sourceFile: String?,
        val sourceLine: Int,
        val executionTimeMs: Long?,
        val databaseName: String?,
        val mapperStatement: String?,
        val errorText: String?,
        val historyFile: String,
    )

    /** 检索结果：命中摘要 + 计数（totalHits/returned/truncated 供调用方自行组「截断说明」）。 */
    data class Result(
        val totalHits: Int,
        val returned: Int,
        val truncated: Boolean,
        val scannedFiles: Int,
        val scannedEntries: Int,
        val scanTruncated: Boolean,
        /** 本次生效的范围下界（epoch 毫秒）；allDates=true 且未给 fromMs 时为 null。 */
        val scopeStartMs: Long?,
        val hits: List<Hit>,
    )

    /**
     * 文件目录入口（F55/F56/F65 三合一重写，D31 道 A）：扫目录下全部 .sql（大小写不敏感），
     * **文件名倒序（新→旧）**逐文件**条目粒度**流式解析（[SqlLogFileReader.forEachEntry]），
     * 边读边滤只留命中——一次改动同收三发现：
     *  - F55 截断丢旧不丢新：旧环升序读盘，封顶截掉的恰是最新文件（默认「今日」极端情形
     *    旧文件耗尽预算读不到今天的文件，与「默认当日防倾泻」意图相悖）；
     *  - F56 封顶前不再整文件驻留：旧环 readFromFile 全量物化后 perFile 持有全部已读条目
     *    （最坏峰值＝200k 条+最后文件全量），现内存只驻命中条目（峰值≈命中集）；
     *  - F65 默认今日省 IO：名日期与 mtime 均早于范围起点的文件零 IO 预跳过（[preSkippable]），
     *    旧环 allDates=false 仍要读全目录才能按 timestamp 筛。
     *
     * @param scanEntryCap 扫描条目封顶（默认 [MAX_SCAN_ENTRIES]；测试缝）——**条目粒度**计数，
     *   解析条目数恒 ≤ cap，超限置 scanTruncated 即停（后续文件/条目都不再读）
     */
    fun search(
        dir: String,
        query: Query,
        nowMs: Long = System.currentTimeMillis(),
        scanEntryCap: Int = MAX_SCAN_ENTRIES,
    ): Result {
        val compiled = CompiledFilters.of(query, nowMs)
        val directory = File(dir)
        val files = directory
            .listFiles { f -> f.isFile && f.extension.equals("sql", ignoreCase = true) }
            ?.sortedByDescending { it.name } // F55：新→旧（默认日期命名下即最新文件优先）
            ?: emptyList()
        var scannedEntries = 0
        var scanTruncated = false
        val matched = mutableListOf<Pair<String, LogSqlEntry>>()
        for (f in files) {
            // F65：预跳过先于封顶判定——范围外的文件零 IO 跳过，不占扫描预算也不触发截断标记
            // （「截断」语义＝可能有相关数据未读；预跳过文件按判据不含范围内数据）
            if (preSkippable(f, compiled.scopeFrom)) continue
            if (scannedEntries >= scanEntryCap) {
                scanTruncated = true
                break
            }
            var stop = false
            SqlLogFileReader.forEachEntry(f) { e ->
                if (scannedEntries >= scanEntryCap) { // F56：条目粒度封顶（下一条都不再解析）
                    scanTruncated = true
                    stop = true
                    return@forEachEntry false
                }
                scannedEntries++
                if (compiled.matches(e)) matched.add(f.name to e) // 边读边滤只留命中
                true
            }
            if (stop) break
        }
        return buildResult(
            matched, compiled,
            scannedFiles = files.size,
            scannedEntries = scannedEntries,
            scanTruncated = scanTruncated,
        )
    }

    /**
     * 纯逻辑核（域中立测试缝）：已在内存的「落盘文件名 → 条目」清单上执行四维过滤。
     * F55 三合一后与目录扫描环共用 [CompiledFilters]（一次编译逐条匹配）与 [buildResult]
     * （排序/截断/摘要收口）——两入口的过滤与输出语义单一实现源。
     */
    fun search(
        perFile: List<Pair<String, List<LogSqlEntry>>>,
        query: Query,
        nowMs: Long,
        scannedFiles: Int = perFile.size,
        scannedEntries: Int = perFile.sumOf { (_, entries) -> entries.size },
        scanTruncated: Boolean = false,
    ): Result {
        val compiled = CompiledFilters.of(query, nowMs)
        val matched = perFile.flatMap { (fileName, entries) ->
            entries.asSequence()
                .filter(compiled::matches)
                .map { fileName to it }
        }
        return buildResult(
            matched, compiled,
            scannedFiles = scannedFiles,
            scannedEntries = scannedEntries,
            scanTruncated = scanTruncated,
        )
    }

    /**
     * 编译后的过滤条件（目录扫描环与纯逻辑核共用）——入参校验（非法正则/时间倒置抛
     * [InvalidQueryException]）与条件编译只做一次，扫描环内逐条 O(1) 匹配。
     */
    private class CompiledFilters(
        val maxResults: Int,
        val keyword: String?,
        val regex: Regex?,
        val sourceFileFilter: String?,
        val scopeFrom: Long?,
        val toMs: Long?,
    ) {
        /** 正则求值累计耗时（纳秒；本对象一次 search 一份，单线程逐条累加）。 */
        var regexElapsedNs = 0L
            private set

        /** 四维匹配（范围/关键词/正则/来源文件）——语义与旧纯核的 inScope+textMatches+sourceFilter 逐字一致。 */
        fun matches(e: LogSqlEntry): Boolean {
            if (scopeFrom != null && e.timestamp < scopeFrom) return false
            if (toMs != null && e.timestamp > toMs) return false
            if (keyword != null) {
                val keywordHit = e.rawSql.contains(keyword, ignoreCase = true) ||
                    e.restoredSql.contains(keyword, ignoreCase = true) ||
                    e.mapperStatement?.contains(keyword, ignoreCase = true) == true
                if (!keywordHit) return false
            }
            if (regex != null) {
                // T-SAF-24：正则求值预算——单条目超 [entryRegexBudgetNs] 记 miss（检索继续，
                // 防单个病态条目霸占检索）；整查累计超 [totalRegexBudgetNs] 抛
                // [InvalidQueryException]（MCP 侧转译 INVALID_ARGUMENT，结构化失败）。
                // 局限：预算在求值返回后结算——单条灾难性回溯仍会先耗完自身时长（步数级
                // 预算需共享 BoundedRegex 工具，登记 followup）；编译前高危形态初筛
                // （[regexComplexityIssue]）+ 模式长度上限把这类构造挡在求值之前。
                val startedAt = System.nanoTime()
                val hit = regex.containsMatchIn(e.rawSql) || regex.containsMatchIn(e.restoredSql)
                val elapsed = System.nanoTime() - startedAt
                regexElapsedNs += elapsed
                if (regexElapsedNs > totalRegexBudgetNs) {
                    throw InvalidQueryException(
                        "regex evaluation exceeded the total budget of " +
                            "${totalRegexBudgetNs / 1_000_000} ms; narrow the query " +
                            "(keyword/sourceFile/time range) or simplify the pattern.",
                    )
                }
                if (!hit || elapsed > entryRegexBudgetNs) return false
            }
            if (sourceFileFilter != null && e.sourceFile?.contains(sourceFileFilter, ignoreCase = true) != true) return false
            return true
        }

        companion object {
            fun of(query: Query, nowMs: Long): CompiledFilters {
                val maxResults = query.maxResults.coerceIn(1, HARD_MAX_RESULTS)
                val keyword = query.keyword?.trim()?.takeIf { it.isNotEmpty() }
                val regex = query.regex?.trim()?.takeIf { it.isNotEmpty() }?.let { pattern ->
                    // T-SAF-24：模式长度上限 + 高危复杂度初筛（编译前拒绝），再走既有编译校验
                    if (pattern.length > MAX_REGEX_PATTERN_LENGTH) {
                        throw InvalidQueryException(
                            "regex pattern too long (${pattern.length} > $MAX_REGEX_PATTERN_LENGTH characters).",
                        )
                    }
                    regexComplexityIssue(pattern)?.let { issue ->
                        throw InvalidQueryException(
                            "regex rejected ($issue): pattern carries catastrophic-backtracking risk; " +
                                "use a simpler pattern or the keyword filter.",
                        )
                    }
                    runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrElse { e ->
                        throw InvalidQueryException("invalid regex '$pattern': ${e.message}")
                    }
                }
                val sourceFileFilter = query.sourceFile?.trim()?.takeIf { it.isNotEmpty() }
                if (query.fromMs != null && query.toMs != null && query.fromMs > query.toMs) {
                    throw InvalidQueryException("invalid time range: fromMs (${query.fromMs}) > toMs (${query.toMs})")
                }
                // D1=A：默认范围下界 = 当日本地零点；allDates 或显式更晚的 fromMs 才放宽
                val todayStart = startOfLocalDay(nowMs)
                val scopeFrom = when {
                    query.allDates -> query.fromMs
                    else -> maxOf(todayStart, query.fromMs ?: Long.MIN_VALUE)
                }
                return CompiledFilters(maxResults, keyword, regex, sourceFileFilter, scopeFrom, query.toMs)
            }
        }
    }

    /**
     * 高危复杂度初筛（T-SAF-24，保守启发式）：拒绝「量词化分组再套无界量词」与「双重量词」
     * 两类已证实的灾难性回溯构造（(a+)+b / (a*)* / a** 形态族）。非高危形态交由求值预算
     * 兜底——初筛宁可少拦（保守），不追求完备。
     *
     * 扫描器语义：`\` 转义取双字符；`[…]` 字符类整段跳过（类内量词是字面量）；`(?:…)`
     * 等非捕获组同样入栈；`?`/`+` 紧随量词之后视作懒惰/占有修饰符（`.*?` 合法）而非量词；
     * `{n,m}` 识别为量词（无界 `{n,}` 与可空 `{0,m}` 计入风险面），非量词形态按字面量。
     * 有界内层量词（`(\d{1,3}\.){3}` 等 IP 段形态）不拦——外层量词必须无界才构成风险。
     *
     * @return null=放行；非 null=拒绝原因短语（进 [InvalidQueryException] 消息）
     */
    internal fun regexComplexityIssue(pattern: String): String? {
        /** 一个分组的扫描态：[riskInside] = 组内出现过无界或可空量词。 */
        class Frame {
            var riskInside = false
        }

        val stack = ArrayDeque<Frame>()
        var lastGroupRisk = false
        var lastWasQuantifier = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    i += 2
                    lastGroupRisk = false
                    lastWasQuantifier = false
                }
                c == '[' -> {
                    // 字符类：跳到未转义的 `]`（类内量词是字面量）；类后可跟量词（[ab]+ 合法）
                    i++
                    while (i < pattern.length && pattern[i] != ']') {
                        i += if (pattern[i] == '\\') 2 else 1
                    }
                    i++
                    lastGroupRisk = false
                    lastWasQuantifier = false
                }
                c == '(' -> {
                    stack.addLast(Frame())
                    lastGroupRisk = false
                    lastWasQuantifier = false
                    i++
                }
                c == ')' -> {
                    val closed = stack.removeLastOrNull()
                    lastGroupRisk = closed?.riskInside == true
                    // 风险向父组传播（((a+))+ 形态：内层风险经中间组直抵外层量词）
                    if (lastGroupRisk) stack.lastOrNull()?.riskInside = true
                    lastWasQuantifier = false
                    i++
                }
                c == '*' || c == '+' || c == '?' -> {
                    if (lastWasQuantifier && (c == '?' || c == '+')) {
                        // 懒惰（*?）／占有（*+）修饰符：消费掉，不按量词判定
                        i++
                        continue
                    }
                    val unbounded = c != '?'
                    if (lastWasQuantifier) return "double quantifier"
                    if (lastGroupRisk && unbounded) return "nested quantifier"
                    stack.lastOrNull()?.let { frame ->
                        if (unbounded || c == '?') frame.riskInside = true
                    }
                    lastWasQuantifier = true
                    i++
                }
                c == '{' -> {
                    val end = pattern.indexOf('}', i)
                    val body = if (end > i) pattern.substring(i + 1, end) else ""
                    val isQuantifier = body.isNotEmpty() && body.all { it.isDigit() || it == ',' }
                    if (isQuantifier) {
                        if (lastWasQuantifier) return "double quantifier"
                        val unbounded = !body.contains(',')
                        val emptyable = body.startsWith("0") || body.startsWith(",") || body == ","
                        if (lastGroupRisk && unbounded) return "nested quantifier"
                        stack.lastOrNull()?.let { frame ->
                            if (unbounded || emptyable) frame.riskInside = true
                        }
                        lastWasQuantifier = true
                        i = end + 1
                    } else {
                        lastGroupRisk = false
                        lastWasQuantifier = false
                        i++
                    }
                }
                else -> {
                    lastGroupRisk = false
                    lastWasQuantifier = false
                    i++
                }
            }
        }
        return null
    }

    /**
     * 命中排序（V14b：输出前按时间戳自行排——最新在前，稳定序：同毫秒按落盘文件名 + 文件内序号）
     * + 截断 + 摘要组装（目录扫描环与纯逻辑核共用收口）。
     */
    private fun buildResult(
        matched: List<Pair<String, LogSqlEntry>>,
        compiled: CompiledFilters,
        scannedFiles: Int,
        scannedEntries: Int,
        scanTruncated: Boolean,
    ): Result {
        val ordered = matched.sortedWith(
            compareByDescending<Pair<String, LogSqlEntry>> { (_, e) -> e.timestamp }
                .thenBy { (fileName, _) -> fileName }
                .thenBy { (_, e) -> e.sequenceNumber },
        )

        val hits = ordered.take(compiled.maxResults).map { (fileName, e) ->
            Hit(
                timestamp = e.timestamp,
                sqlPreview = preview(e.restoredSql),
                source = e.source.name,
                sourceFile = e.sourceFile,
                sourceLine = e.sourceLine,
                executionTimeMs = e.executionTimeMs,
                databaseName = e.databaseName,
                mapperStatement = e.mapperStatement,
                errorText = e.errorText,
                historyFile = fileName,
            )
        }
        return Result(
            totalHits = ordered.size,
            returned = hits.size,
            truncated = ordered.size > hits.size,
            scannedFiles = scannedFiles,
            scannedEntries = scannedEntries,
            scanTruncated = scanTruncated,
            scopeStartMs = compiled.scopeFrom,
            hits = hits,
        )
    }

    /** 文件名携带的日期（yyyy-MM-dd 首个匹配；滚动块 `.2.sql` 后缀不影响）——无日期返回 null。 */
    private val FILE_NAME_DAY_REGEX = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    private fun fileNameDayStartMs(name: String): Long? {
        val mr = FILE_NAME_DAY_REGEX.find(name) ?: return null
        return runCatching {
            java.time.LocalDate.of(mr.groupValues[1].toInt(), mr.groupValues[2].toInt(), mr.groupValues[3].toInt())
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /**
     * F65 预跳过判据（internal＝测试直测缝）：文件可整体跳过不读盘，当且仅当——范围下界存在，
     * 且文件**名日期日**与 **mtime** 都早于该起点。
     *
     * 为什么必须 mtime 兜底（对「仅凭名日期」的收敛，防跨天追加误伤）：滚动块按体积滚动、
     * 名字是**开块日**——名为昨日但今日凌晨仍被追加的文件真实存在（D1=A 当年正因此选择按条目
     * 时间戳过滤而非按文件名挑当日文件）。mtime 早于范围起点 ⇒ 起点后从未写入 ⇒ 全部条目
     * 时间戳 < 起点，跳过零误伤；名日期判据覆盖默认模板主场景（旧日期文件且未被追加＝两名双旧
     * 即跳）。名字无日期（自定义模板）不跳——正确性优先于优化面。
     */
    internal fun preSkippable(file: File, scopeFrom: Long?): Boolean {
        if (scopeFrom == null) return false
        if (file.lastModified() >= scopeFrom) return false
        val nameDay = fileNameDayStartMs(file.name) ?: return false
        return nameDay < startOfLocalDay(scopeFrom)
    }

    /** 当日本地零点（epoch 毫秒）。 */
    private fun startOfLocalDay(nowMs: Long): Long =
        Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** SQL 预览（D2=A）：前 [MAX_PREVIEW_LINES] 行且 ≤ [MAX_PREVIEW_CHARS] 字符，截断带省略号。 */
    private fun preview(sql: String): String {
        val lines = sql.lines()
        val taken = lines.take(MAX_PREVIEW_LINES)
        var out = taken.joinToString("\n")
        if (out.length > MAX_PREVIEW_CHARS) {
            out = out.take(MAX_PREVIEW_CHARS - 1) + "…"
        } else if (lines.size > taken.size) {
            out += "\n…"
        }
        return out
    }
}
