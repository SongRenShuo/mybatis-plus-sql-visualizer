package cn.song.mybatisplus.visualizer.log

/**
 * Spring JdbcTemplate 控制台日志解析器（M47）。
 *
 * 支持的日志形态（DEBUG=SQL 行，TRACE=参数行；两类来自不同类）：
 * - SQL 行（JdbcTemplate，有 SQL 文本）：`Executing prepared SQL statement [SELECT ...]`
 * - SQL 行（字符串重载）：`Executing SQL query [SELECT ...]` / `Executing SQL update [UPDATE ...]`
 * - SQL 行（存储过程）：`Calling stored procedure [{call ...}]`
 * - 参数行（StatementCreatorUtils）：
 *   `Setting SQL statement parameter value: column index 1, parameter value [0], value class [java.lang.Integer], SQL type unknown`
 * - NamedParameterJdbcTemplate 单行格式：
 *   `sql = SELECT ... WHERE col = :param1 , parameters = {param1=0, param2=abc}`
 *
 * 不匹配的行（MyBatis/Hibernate/纯噪声）自然忽略。
 *
 * 参数按 `column index`（1-based）升序排列后回填 `?`。
 * 类型还原：`value class [java.lang.Integer]` → 去 `java.lang.` 前缀 → `Integer` → 查 [LogSqlRestorer.PLAIN_TYPES] 判定引号。
 * NamedParameter 格式：命名参数 `:paramName` 直接替换为 Map 中对应值（启发式判定引号）。
 *
 * 多行语句（T-JdbcMultiline）：`[...]` 内 SQL 跨行时（开括号行无闭合），首行进入 COLLECTING 态逐行累积，
 * string-aware 净括号计数（单引号字符串内 `[`/`]` 不计数，`''` 转义）归零即闭合；新语句行/参数行/超限
 * 强制闭合残句，`closePending()` 同样冲刷（LogToSqlPanel 粘贴末尾零改动受益）。
 *
 * T-JdbcThreadBucket（并发参数错挂修复，2026-09-15 立项，根因：单槽状态机假设「SQL 行 → 参数行 → 下一条」严格串行，
 * Tomcat 并发请求日志按到达序混排，named 行带 `closePending()` 会把其它线程的 prepared 语句强制闭合 → 该线程随后到达的
 * 参数行因 `pendingSql == null` 被静默丢弃 → 用户实测「有些参数没有正常识别出来」+ 预览提示「未捕获到参数绑定日志」。
 * 修复：从日志行提取线程 token（`[http-nio-…-exec-4]` 等），按 token 分桶维护独立 pendingSql/pendingCollecting 状态机；
 * 线程 token 提取失败回退共享默认桶（与旧单槽行为一致，fail-open）。SkyWalking 注入的 `[TID: …]` 段是追踪标识非线程名，须排除）。
 *
 * [maxParseLength] 超长截断（防控制台大输出卡顿）。
 */
class JdbcTemplateLogParser(
    private val restorer: LogSqlRestorer = LogSqlRestorer(),

    /** 解析器启用开关（M47：设置页控制）。 */
    var enabled: Boolean = true,

    /** 行解析最大长度（超长截断，防控制台大输出卡顿）。 */
    var maxParseLength: Int = DEFAULT_MAX_PARSE_LENGTH,

    /**
     * T-ConsolePendingFlush：静默冲刷阈值（毫秒）。桶内最后一行静默超过它，即认定「后续行不会再来了」
     * （参数行与语句行是同线程相邻输出，正常间隔 1ms 量级），可安全闭合产出。
     */
    private val settleMs: Long = DEFAULT_SETTLE_MS,

    /** settle 判定的时钟（测试注入可控件；与 [LogThreadRouter] 共用同一时钟）。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val entries = mutableListOf<LogSqlEntry>()

    // ----- T-JdbcThreadBucket / T-LogParseConc：按线程 token 分桶的独立状态机（修复并发参数错挂） -----

    /** 单线程桶的完整状态机（对应旧解析器实例级字段，现按线程 token 隔离）。 */
    private class ThreadBucket {
        var pendingSql: String? = null
        var pendingLogLine: String? = null
        val pendingParams = mutableListOf<IndexedParam>()
        var collecting = false
        val multilineBuffer = StringBuilder()
        var multilineNet = 0
        var multilineInString = false

        /** T-LPC-04：多行收集的**首行**（日志时间取它——多行条目的时间此前恒 null 回落捕获时间）。 */
        var collectingFirstLine: String? = null

        /** 多行收集态内存占用（用于超限判定）。 */
        val byteSize: Int get() = multilineBuffer.length
    }

    /** 线程分桶路由（T-LogParseConc 起为三解析器共享实现，见 [LogThreadRouter]）。 */
    private val router = LogThreadRouter(
        factory = { ThreadBucket() },
        isCollecting = { it.collecting },
        isIdle = { it.pendingSql == null && !it.collecting },
        // T-SAF-22：老化淘汰前强制闭合（与 flushSettled 互补——老化兜底容量压力下的漏网挂起态）
        forceClose = { closeBucketPending(it) },
        clock = clock,
    )

    /** 喂入一行控制台输出（任意线程，内部按线程 token 路由到对应桶——解析器实例按 console 隔离）。 */
    fun feed(line: String) {
        feed(line, LogThreadRouter.threadTokenOf(line))
    }

    /**
     * F58（T-PerfSweep18 压轴）：喂入一行＋**已提取**的线程 token（热路径每行一次下传，
     * 语义与 [feed] 自提逐字等价；token=null 走 fail-open 回退）。
     */
    fun feed(line: String, threadToken: String?) {
        if (!enabled) return
        val bucket = router.bucketForToken(threadToken)
        // 多行收集态：语句跨行累积中（T-JdbcMultiline）
        if (bucket.collecting) {
            if (looksLikeStatementBoundary(line)) {
                // 边界行（新语句/NamedParameter/参数行）：强制闭合残句并立即产出，
                // 防止后续行的参数误挂到残句上
                closeBucketPending(bucket)
                feedIdle(bucket, line)
            } else {
                appendCollecting(bucket, line)
            }
            return
        }
        feedIdle(bucket, line)
    }

    private fun feedIdle(bucket: ThreadBucket, line: String) {
        // 分支 1：NamedParameterJdbcTemplate 单行格式（sql = ... , parameters = {...}）
        val namedEntry = extractNamedParamEntry(line)
        if (namedEntry != null) {
            closeBucketPending(bucket)
            entries += namedEntry
            return
        }

        // 分支 2：SQL 行（动作短语 + `[` 文本）
        val action = SQL_ACTION_PATTERN.find(line)
        if (action != null) {
            val openIdx = line.indexOf('[', action.range.last + 1)
            if (openIdx >= 0 && line.substring(action.range.last + 1, openIdx).isBlank()) {
                // string-aware 净括号扫描（字符串内括号不计数）：同行闭合走快路径；
                // 未闭合进入多行收集——含 `[SELECT a[1] FROM t` 这类首个 `]` 并非消息终结符的
                // 形态（旧同行正则会把 "SELECT a[1" 误提取为完整语句）
                val probe = scanBrackets(line, openIdx + 1, net = 1, inString = false)
                closeBucketPending(bucket)
                bucket.pendingParams.clear()
                if (probe.closeIdx >= 0) {
                    bucket.pendingSql = line.substring(openIdx + 1, probe.closeIdx).take(maxParseLength)
                    bucket.pendingLogLine = line
                } else {
                    bucket.collecting = true
                    bucket.multilineBuffer.setLength(0)
                    bucket.multilineBuffer.append(line.substring(openIdx + 1))
                    bucket.multilineNet = probe.net
                    bucket.multilineInString = probe.inString
                    // T-LPC-04：记首行——多行条目的日志时间取它（此前多行路径不记 pendingLogLine，
                    // 条目 logTimestamp 恒 null → 面板回落捕获时间，控制台积压时与日志行拉开）
                    bucket.collectingFirstLine = line
                }
                return
            }
            // 动作短语命中但无 `[`（如 `Executing prepared SQL query` 头行）→ 落到参数/噪声分支
        }

        // 分支 3：参数行
        val param = extractParamFromLine(line)
        if (param != null) {
            if (bucket.pendingSql == null) {
                LogParseDiag.paramWithoutContext.incrementAndGet() // T-LPC-05：静默丢弃留痕
                return
            }
            bucket.pendingParams += param
            return
        }

        // 分支 4：有 pendingSql 后遇到非参数非 SQL 行 → 闭合当前条目
        if (bucket.pendingSql != null && bucket.pendingParams.isNotEmpty()) {
            closeBucketPending(bucket)
        }
    }

    /**
     * 闭合指定线程桶的未完成条目（新语句到来 / 面板刷新 / 粘贴末尾时调用）。
     * 返回产出的条目（可能为 null，即桶内无 pendingSql）。
     */
    private fun closeBucketPending(bucket: ThreadBucket): LogSqlEntry? {
        if (bucket.collecting) {
            // 多行残句强制闭合（T-JdbcMultiline）——宁交未闭合残句，不整段丢弃
            val partial = bucket.multilineBuffer.toString()
            if (partial.isNotBlank()) {
                LogParseDiag.partialMultilineClosed.incrementAndGet() // T-LPC-05
                finishCollecting(bucket, partial)
            } else {
                bucket.collecting = false
                bucket.multilineBuffer.setLength(0)
                bucket.multilineNet = 0
                bucket.multilineInString = false
                bucket.collectingFirstLine = null
            }
        }
val sql = bucket.pendingSql ?: return null
        bucket.pendingSql = null
        val logTs = bucket.pendingLogLine?.let { LogTimestampParser.parse(it) }
        bucket.pendingLogLine = null
        // 按 column index 升序排列（1-based → 0-based 由 restorer 按序处理）
        val sortedParams = bucket.pendingParams.sortedBy { it.index }
            .map { LogSqlParam(it.value, it.mappedType) }
        val entry = LogSqlEntry(
            rawSql = sql,
            restoredSql = restorer.restore(sql, sortedParams),
            params = sortedParams,
            source = SqlSource.JDBC,
            logTimestamp = logTs,
        )
        entries += entry
        bucket.pendingParams.clear()
        return entry
    }

    /** 闭合全部线程桶的未完成条目（面板刷新 / 粘贴末尾时调用）。返回首个产出的条目（保持旧 closePending 返回语义）。 */
    fun closePending(): LogSqlEntry? {
        var first: LogSqlEntry? = null
        for (bucket in router.allBuckets()) {
            val produced = closeBucketPending(bucket)
            if (first == null) first = produced
        }
        return first
    }

    /** 取出全部已闭合条目并清空缓冲区（面板刷新用）。 */
    fun takeEntries(): List<LogSqlEntry> {
        val result = entries.toList()
        entries.clear()
        return result
    }

    /**
     * T-ConsolePendingFlush：静默冲刷——闭合全部**已静默 ≥ [settleMs]** 的桶的待产出条目，产出进
     * [entries]（由调用方随下一次 [takeEntries] 取走），返回本次产出条数。
     *
     * 为什么必须有它：本解析器的条目只在「同一线程桶里再来一行边界行」时经 [closeBucketPending] 产出，
     * 而 JdbcTemplate 的语句行之后经常**没有同线程后继行**——参数行是 TRACE 级（`logging.level` 常只开
     * `JdbcTemplate: debug`），Tomcat 又按请求新建线程（`exec-1`/`exec-2`…），低频访问下每条语句各自独占
     * 一个新线程、此后不再有行。旧行为下这些语句永远停在桶里，`takeEntries()` 恒空——用户看到的是
     * 面板「已扫描 N 行 / 产出 0 条」。
     *
     * 调用点两处：① [processConsoleLine] 逐行入口（同控制台线程，喂行后调用，零并发）；
     * ② 控制台桥的常规轮询（EDT）——应用之后一行都不再打时，靠这一拍兜底。
     *
     * 已知代价：若某线程在语句行与它的参数行之间卡住超过阈值（连接池耗尽等），条目会先以未还原形态
     * 产出，随后到达的参数行按「无上下文」丢弃（[LogParseDiag.paramWithoutContext] 留痕）。阈值取
     * 1 秒即为把这一误伤面压到最小。
     */
    fun flushSettled(nowMs: Long = clock()): Int {
        var produced = 0
        for (bucket in router.settledBuckets(settleMs, nowMs)) {
            if (closeBucketPending(bucket) != null) produced++
        }
        return produced
    }

    // ---------- 私有方法（与旧版同逻辑，按桶操作） ----------

    /**
     * 尝试从行中提取 NamedParameterJdbcTemplate 格式的完整条目（单行）。
     * 格式：`sql = <SQL> , parameters = {param1=val1, param2=val2}`
     * 命名参数 `:paramName` 直接替换为 Map 中对应值。
     */
    private fun extractNamedParamEntry(line: String): LogSqlEntry? {
        val match = NAMED_PARAM_PATTERN.find(line) ?: return null
        val sql = match.groupValues[1].trim().take(maxParseLength)
        val paramsText = match.groupValues[2].trim()
        val paramsMap = parseNamedParamsMap(paramsText)
        val restoredSql = replaceNamedParams(sql, paramsMap)
        // 收集 params 列表（按 :paramN 出现顺序）
        val params = mutableListOf<LogSqlParam>()
        NAMED_PARAM_REF.findAll(sql).forEach { refMatch ->
            val paramName = refMatch.groupValues[1]
            val value = paramsMap[paramName]
            params += LogSqlParam(value, null)
        }
        return LogSqlEntry(
            rawSql = sql,
            restoredSql = restoredSql,
            params = params,
            source = SqlSource.JDBC,
            logTimestamp = LogTimestampParser.parse(line),
        )
    }

    /**
     * 解析 NamedParameter 格式的参数 Map 文本。
     * 格式：`param1=0, param2=abc`
     * 值可能含 `=` 或 `,`（如 JSON 字符串），按 `, paramName=` 边界切分。
     */
    private fun parseNamedParamsMap(paramsText: String): Map<String, String> {
        if (paramsText.isEmpty()) return emptyMap()
        val params = mutableMapOf<String, String>()
        // 按 `, key=` 边界切分（lookahead 保留 key）；T-PerfSweep F2：正则提常量（此前每条 named 参数条目内联新建）
        val entries = paramsText.split(NAMED_PARAMS_MAP_SPLIT)
        for (entry in entries) {
            val eqIdx = entry.indexOf('=')
            if (eqIdx <= 0) continue
            val key = entry.substring(0, eqIdx).trim()
            val value = entry.substring(eqIdx + 1).trim()
            params[key] = value
        }
        return params
    }

    /**
     * 替换 SQL 中的命名参数为渲染后的字面量。
     * 无类型标注 → 启发式：数值/true/false 原样，其余引号包裹。
     *
     * T-ListParamCollect：IN 子句内的集合值（日志形态 `[a]`/`[a, b]`）剥括号按元素渲染，
     * 方括号不再落进 SQL 字面量；非 IN 位置行为与改造前逐字节一致。
     */
    private fun replaceNamedParams(sql: String, params: Map<String, String>): String {
        val inClauseNames = ListParamRenderer.inClauseParamNames(sql)
        var result = sql
        for ((name, value) in params) {
            val rendered = ListParamRenderer.renderParam(value, name in inClauseNames) { renderUntyped(it) }
            result = result.replace(":$name", rendered)
        }
        return result
    }

    /**
     * 无类型标注的字面量渲染（启发式）。
     * 数值（含小数/科学计数）/ true/false → 原样；null → NULL；其余 → 引号包裹。
     */
    private fun renderUntyped(value: String?): String {
        if (value == null || value == "null") return "NULL"
        if (value.matches(NUMERIC_REGEX) || value == "true" || value == "false") return value
        return "'" + value.replace("'", "''") + "'"
    }

    /**
     * 尝试从行中提取参数信息。
     * 格式：`Setting SQL statement parameter value: column index N, parameter value [VALUE], value class [CLASS], SQL type TYPE`
     */
    private fun extractParamFromLine(line: String): IndexedParam? {
        val match = PARAM_LINE_PATTERN.find(line) ?: return null
        val columnIndex = match.groupValues[1].toIntOrNull() ?: return null
        val rawValue = match.groupValues[2]
        val className = match.groupValues[3]

        // null 字面量：parameter value [null]（value class 可能是 null，也可能是具体类型如 java.lang.String）。
        // 字符串字面值 "null" 与 SQL NULL 在该行无法区分，按 NULL 语义处理（M37.0 回归集实证）。
        if (rawValue == "null") {
            return IndexedParam(columnIndex, null, null)
        }

        val mappedType = mapClassToType(className)
        return IndexedParam(columnIndex, rawValue, mappedType)
    }

    /**
     * 将 Spring JdbcTemplate 输出的全限定 Java 类名映射到 PLAIN_TYPES 可识别的简名。
     *
     * - `java.lang.Integer` → `Integer`
     * - `java.math.BigDecimal` → `BigDecimal`
     * - `java.sql.Timestamp` → `Timestamp`（不在 PLAIN_TYPES → 引号包裹）
     * - 其他未知类名 → 原样返回（不在 PLAIN_TYPES → 引号包裹）
     */
    private fun mapClassToType(className: String): String? {
        if (className == "null") return null
        return className
            .removePrefix("java.lang.")
            .removePrefix("java.math.")
            .removePrefix("java.util.")
            .removePrefix("java.sql.")
            .removePrefix("java.time.")
    }

    // ---------- 多行收集态（T-JdbcMultiline） ----------

    /** COLLECTING 态边界判定：新语句行 / NamedParameter 行 / 参数行到达时强制闭合残句。 */
    private fun looksLikeStatementBoundary(line: String): Boolean =
        SQL_ACTION_PATTERN.containsMatchIn(line) ||
            NAMED_PARAM_PATTERN.containsMatchIn(line) ||
            PARAM_LINE_PATTERN.containsMatchIn(line)

    /** COLLECTING 态逐行累积；净括号计数归零即闭合（终结 `]` 前的文本即完整 SQL）。 */
    private fun appendCollecting(bucket: ThreadBucket, line: String) {
        if (bucket.byteSize + line.length + 1 >= maxParseLength) {
            // 累积超限：强制闭合（截断语义与单行快路径一致），不吞后续语句
            closeBucketPending(bucket)
            return
        }
        val lineStart = bucket.multilineBuffer.length
        bucket.multilineBuffer.append('\n').append(line)
        val probe = scanBrackets(line, 0, bucket.multilineNet, bucket.multilineInString)
        bucket.multilineNet = probe.net
        bucket.multilineInString = probe.inString
        if (probe.closeIdx >= 0) {
            finishCollecting(bucket, bucket.multilineBuffer.substring(0, lineStart + 1 + probe.closeIdx))
        }
    }

    /** 闭合多行语句：保留内部换行、削首尾空白，回归与单行一致的 pendingSql 生命周期。 */
    private fun finishCollecting(bucket: ThreadBucket, sql: String) {
        // T-LPC-04：多行条目的日志时间取**收集首行**（该行才是 SQL 所在日志行，含时间戳前缀）——
        // 此前多行路径不记 pendingLogLine，条目 logTimestamp 恒 null → 面板回落捕获时间，
        // 控制台行多/管道滞后时与日志行时间拉开（用户实测 209ms）。
        bucket.pendingLogLine = bucket.collectingFirstLine
        bucket.collectingFirstLine = null
        bucket.collecting = false
        bucket.multilineBuffer.setLength(0)
        bucket.multilineNet = 0
        bucket.multilineInString = false
        bucket.pendingSql = sql.trim().take(maxParseLength)
    }

    /** 括号扫描结果：累计净计数、延续后的字符串态、净计数首次归零处的局部下标（未闭合为 -1）。 */
    private class BracketScan(val net: Int, val inString: Boolean, val closeIdx: Int)

    /**
     * string-aware 括号扫描（T-JdbcMultiline）：`[` +1 / `]` -1，单引号字符串内的括号不计数
     * （`''` 为字符串内转义引号，字符串态跨行延续）。消息终结 `]` 使净计数归零——
     * SQL 正文中的平衡/字符串内括号均不构成闭合。
     */
    private fun scanBrackets(text: String, from: Int, net: Int, inString: Boolean): BracketScan {
        var n = net
        var s = inString
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (s) {
                if (c == '\'') {
                    if (i + 1 < text.length && text[i + 1] == '\'') {
                        i += 2
                        continue
                    }
                    s = false
                }
            } else {
                when (c) {
                    '\'' -> s = true
                    '[' -> n++
                    ']' -> {
                        n--
                        if (n == 0) return BracketScan(n, s, i)
                    }
                }
            }
            i++
        }
        return BracketScan(n, s, -1)
    }

    /** 带索引的参数（column index + 值 + 映射后类型名）。 */
    private data class IndexedParam(
        val index: Int,
        val value: String?,
        val mappedType: String?,
    )

    companion object {
        const val DEFAULT_MAX_PARSE_LENGTH = 20000

        /**
         * T-ConsolePendingFlush：静默冲刷默认阈值（毫秒）。
         *
         * 取 1 秒的由来：参数行（TRACE）与语句行（DEBUG）由同线程相邻打印，`JdbcTemplate.execute` 先打
         * 语句行、随后在 `psc.createPreparedStatement` 里逐参数打 TRACE 行，间隔 1ms 量级；1 秒的静默
         * 足以判定「没有参数行可等」，而误伤（把参数行留在条目外）需要该线程在两步之间卡住 1 秒以上。
         */
        const val DEFAULT_SETTLE_MS = 1000L

        // T-LogParseConc：线程 token 提取 / 分桶 / 桶上限已下沉 [LogThreadRouter]（三解析器共享），
        // 原 THREAD_WINDOW / THREAD_BRACKET / PURE_DIGIT / LEVEL_KEYWORD_PATTERN / DEFAULT_MAX_BUCKETS 随之下沉。

        /**
         * SQL 行动作短语（T-JdbcMultiline 自旧「同行 `[...]` 正则」拆出）。
         * 配合 string-aware 括号扫描判定同行闭合（快路径）或跨行收集（慢路径）——
         * 旧式对 `[SELECT a[1] FROM t`（首个 `]` 是标识符括号、消息终结 `]` 在后续行）
         * 会把 "SELECT a[1" 误提取为完整语句。
         *
         * 匹配形态：
         * - `Executing prepared SQL statement [SQL]` / `update` / `query`
         * - `Executing SQL statement/query/update [SQL]`
         * - `Executing SQL batch update [SQL]`
         * - `Calling stored procedure [SQL]`（无 Executing 前缀）
         *
         * 不匹配：`Executing prepared SQL query`（无 `[SQL]` 的头行）
         */
        private val SQL_ACTION_PATTERN = Regex(
            """(?:Executing\s+(?:prepared\s+SQL\s+(?:statement|update|query)|SQL\s+(?:statement|query|update)|SQL\s+batch\s+update)|Calling\s+stored\s+procedure)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 参数行匹配正则。
         * 组 1 = column index，组 2 = parameter value，组 3 = value class，组 4 = SQL type。
         *
         * 值使用贪婪匹配到 `], value class` 前的最后一个 `]`，
         * 支持值本身含 `]` 字符。
         */
        private val PARAM_LINE_PATTERN = Regex(
            """Setting\s+SQL\s+statement\s+parameter\s+value:\s+column\s+index\s+(\d+),\s+parameter\s+value\s+\[(.*)],\s+value\s+class\s+\[(.*?)],\s+SQL\s+type\s+(.*)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * NamedParameterJdbcTemplate 单行格式正则。
         * 组 1 = SQL 文本，组 2 = 参数 Map 文本。
         *
         * 匹配形态：`sql = SELECT ... , parameters = {param1=0, param2=abc}`
         * SQL 使用非贪婪匹配到 ` , parameters =` 前的最后一个字符。
         */
        private val NAMED_PARAM_PATTERN = Regex(
            """sql\s*=\s*(.*?)\s*,\s*parameters\s*=\s*\{([^}]*)\}""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * SQL 中命名参数引用模式：`:paramName`（用于收集 params 列表）。
         */
        private val NAMED_PARAM_REF = Regex(""":(\w+)""")

        /** 数值字面量（含小数与科学计数）。 */
        private val NUMERIC_REGEX = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")

        /**
         * T-PerfSweep F2：NamedParameter 参数 Map 的 `, key=` 边界切分正则（lookahead 保留 key）。
         * 捕捉热路径上每条 named 参数条目都要切分——原内联新建 Regex 提为常量。
         */
        private val NAMED_PARAMS_MAP_SPLIT = Regex("""\s*,\s*(?=\w+\s*=)""")
    }
}
