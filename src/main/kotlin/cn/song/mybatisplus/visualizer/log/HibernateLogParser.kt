package cn.song.mybatisplus.visualizer.log

/**
 * Hibernate/JPA 控制台日志解析器。
 *
 * 支持的日志形态：
 * - SQL 行（Hibernate: 前缀）：`Hibernate: select ...`（同行或独占一行等待下一行）
 * - SQL 行（注释前缀）：块注释 load com.example.User 后跟 select ...（同行或跨行注释块）
 * - SQL 行（纯 SQL，[recognizeBareSql]=true 时）：行首即 SELECT/INSERT/UPDATE/DELETE
 * - 参数行：`binding parameter [1] as [BIGINT] - [1]`
 *
 * 三态状态机：IDLE、COLLECTING、emit。
 * - IDLE：等待 SQL 行（Hibernate: / 注释块 / 纯 SQL），检测到后切换到 COLLECTING。
 * - COLLECTING：累积多行 SQL（注释块跨行 + SQL 续行），遇到新语句前缀或参数行时闭合。
 * - emit：closePending() 从 multilineBuffer 取完整 SQL 构建 LogSqlEntry。
 *
 * 与 [MyBatisLogParser] 并列运行，互不干扰。共享 [LogSqlEntry] 数据模型。
 * 产出条目的 [LogSqlEntry.source] 固定为 [SqlSource.HIBERNATE]。
 */
class HibernateLogParser(
    private val restorer: LogSqlRestorer = LogSqlRestorer(),

    /** SQL 行前缀（默认 `Hibernate:`）。 */
    var sqlPrefix: String = DEFAULT_SQL_PREFIX,

    /** 参数行前缀（默认 `binding parameter`）。 */
    var paramPrefix: String = DEFAULT_PARAM_PREFIX,

    /** 混合前缀（true=关键字宽容匹配；false=行首精确前缀匹配）。 */
    var mixedPrefix: Boolean = true,

    /** 行解析最大长度（超长截断，防控制台大输出卡顿）。 */
    var maxParseLength: Int = DEFAULT_MAX_PARSE_LENGTH,

    /** 是否识别无前缀的纯 SQL 行（行首即 SELECT/INSERT/UPDATE/DELETE）。 */
    var recognizeBareSql: Boolean = true,

    /**
     * T-ConsoleFlushAlign：静默冲刷阈值（毫秒）。桶内最后一行静默超过它，即认定「后续行不会再来了」
     * （绑定参数行 / SQL 续行与语句行同线程相邻输出，正常间隔 1ms 量级），可安全闭合产出。
     */
    private val settleMs: Long = DEFAULT_SETTLE_MS,

    /** settle 判定的时钟（测试注入可控件；与 [LogThreadRouter] 共用同一时钟）。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val entries = mutableListOf<LogSqlEntry>()

    /**
     * 单线程桶：一条语句从 SQL 行起头到闭合之间的全部状态（T-HibConc 按线程隔离）。
     *
     * 为什么必须分桶：多线程日志按到达序混排时，单槽状态机下另一线程的语句行会提前闭合本线程
     * 尚未收全的 SQL 块，本线程的绑定行则被追加进共享的 `pendingParams`——**参数跨线程错挂**
     * （A 的 SQL 配 B 的值）。与 MyBatis/JdbcTemplate 同一根因（T-LogParseConc 已修）。
     */
    private class Bucket {
        var collecting = false
        var inComment = false
        val multilineBuffer = StringBuilder()

        /** SQL 行原文（日志时间取它；stdout 副本沿用 logger 副本的行，故续收集时不一定刷新）。 */
        var pendingLogLine: String? = null
        val pendingParams = mutableListOf<LogSqlParam>()

        /**
         * T-HibDualCapture 隔离槽（T-HibConc 起**按桶**）：本线程产生的零参影子条目，
         * 等本线程 stdout 副本的参数版到达即取代。实例级共享时并发下不同线程的影子会互相顶掉
         * （A 的影子被 B 的影子挤出入列、A 的参数版到达时已无从合并 → 面板多出零参重复条目）。
         * 同线程两副本经 MRU 路由恒同桶，故按桶不影响合并。
         */
        var pendingShadow: LogSqlEntry? = null
    }

    /**
     * T-HibConc：线程分桶路由。`routeNoTokenToRecent=true` 是 Hibernate 专属——多行 SQL 正文与
     * `Hibernate: ` stdout 标志行都不带日志前缀（提取不到 token），须跟随同线程的带 token 行。
     */
    private val router = LogThreadRouter(
        factory = { Bucket() },
        isCollecting = { it.collecting },
        isIdle = { !it.collecting && !it.inComment && it.pendingParams.isEmpty() && it.pendingShadow == null },
        routeNoTokenToRecent = true,
        // 时钟与 [clock] 同源（flushSettled 的静默判定一致性；测试注入可控件，同 ShardingSphere 先例）
        clock = clock,
    )

    /** 喂入一行控制台输出（任意线程；按线程 token 路由到独立桶——解析器实例按 console 隔离）。 */
    fun feed(line: String) {
        feed(line, LogThreadRouter.threadTokenOf(line))
    }

    /**
     * F58（T-PerfSweep18 压轴）：喂入一行＋**已提取**的线程 token（热路径每行一次下传，
     * 语义与 [feed] 自提逐字等价；token=null 走 fail-open 回退）。
     */
    fun feed(line: String, threadToken: String?) {
        val bucket = router.bucketForToken(threadToken)
        if (bucket.collecting) {
            feedCollecting(bucket, line)
        } else {
            feedIdle(bucket, line)
        }
    }

    // ---------- IDLE 状态（等待 SQL 行） ----------

    private fun feedIdle(bucket: Bucket, line: String) {
        // 1. Hibernate: 前缀（含同行 SQL）
        if (handleHibernatePrefix(bucket, line)) return

        // 2. Hibernate: 独占一行（remainder 为空）→ 进入等待态
        if (isPrefixOnly(line, sqlPrefix)) {
            closePending(bucket)
            bucket.collecting = true
            bucket.pendingLogLine = line
            bucket.pendingParams.clear()
            return
        }

        // 3. 块注释开头
        if (line.trimStart().startsWith("/*")) {
            val (unclosed, afterComment) = hasUnclosedComment(line)
            closePending(bucket)
            bucket.collecting = true
            bucket.pendingLogLine = line
            bucket.pendingParams.clear()
            if (unclosed) {
                bucket.inComment = true
            } else {
                if (afterComment.isNotEmpty() && looksLikeSql(afterComment)) {
                    bucket.multilineBuffer.append(afterComment)
                } else {
                    bucket.collecting = false
                }
            }
            return
        }

        // 4. 纯 SQL 行（recognizeBareSql=true 时）
        if (recognizeBareSql) {
            val trimmed = line.trim()
            if (!trimmed.startsWith(paramPrefix, ignoreCase = true) &&
                BARE_SQL_PATTERN.containsMatchIn(trimmed)
            ) {
                closePending(bucket)
                bucket.collecting = true
                bucket.multilineBuffer.append(trimmed)
                bucket.pendingLogLine = line
                bucket.pendingParams.clear()
                return
            }
        }

        // 5. 参数绑定行——IDLE（无待闭合语句）态下无上下文，丢弃并计数（T-HibConc 与另两解析器同口径）
        val param = extractBindingParam(line)
        if (param != null) {
            LogParseDiag.paramWithoutContext.incrementAndGet()
            return
        }

        // 6. 噪声行（忽略）
    }

    // ---------- COLLECTING 状态（累积多行 SQL） ----------

    private fun feedCollecting(bucket: Bucket, line: String) {
        if (bucket.inComment) {
            // 等待注释块结束符
            val endIdx = line.lastIndexOf("*/")
            if (endIdx >= 0) {
                bucket.inComment = false
                val afterComment = line.substring(endIdx + 2).trim()
                if (afterComment.isNotEmpty() && looksLikeSql(afterComment)) {
                    if (bucket.multilineBuffer.length + 1 + afterComment.length <= maxParseLength) {
                        bucket.multilineBuffer.append(" ").append(afterComment)
                    }
                }
            }
            return
        }

        // 1. 新 Hibernate: 前缀（含同行 SQL）→ 闭合当前条目，开启新收集
        if (handleHibernatePrefix(bucket, line)) return

        // 1b. Hibernate: 独占一行 → 闭合当前条目，开启新收集（handleHibernatePrefix 不处理空文本）
        if (isPrefixOnly(line, sqlPrefix)) {
            closePending(bucket)
            bucket.collecting = true
            bucket.inComment = false
            bucket.multilineBuffer.clear()
            bucket.pendingParams.clear()
            return
        }

        // 2. 新块注释开头 → 闭合当前条目，开启新收集
        if (line.trimStart().startsWith("/*")) {
            val (unclosed, afterComment) = hasUnclosedComment(line)
            closePending(bucket)
            bucket.collecting = true
            bucket.pendingParams.clear()
            if (unclosed) {
                bucket.inComment = true
            } else {
                if (afterComment.isNotEmpty() && looksLikeSql(afterComment)) {
                    bucket.multilineBuffer.append(afterComment)
                } else {
                    bucket.collecting = false
                }
            }
            return
        }

        // 3. 参数绑定行 → 追加参数（保持 COLLECTING 以支持多参数）
        val param = extractBindingParam(line)
        if (param != null) {
            bucket.pendingParams += param
            return
        }

        // 4. 已有参数后遇到非 SQL 行 → 闭合当前条目，按 IDLE 处理该行
        if (bucket.pendingParams.isNotEmpty()) {
            closePending(bucket)
            feedIdle(bucket, line)
            return
        }

        // 5. MyBatis 行或其他框架前缀 → 忽略（不属于 Hibernate SQL 续行，保持 COLLECTING 等待参数）
        // 覆盖三种形态：MP 前缀（==>）、原生（Preparing:/Parameters:）、日志框架前缀（时间戳+类名）
        if (isMybatisLogLine(line) || isLogFrameworkLine(line)) {
            return
        }

        // 6. 续行 → 追加到 buffer
        if (bucket.multilineBuffer.length < maxParseLength) {
            bucket.multilineBuffer.append(" ").append(line.trim())
        }
    }

    /** 闭合全部桶的未完成条目（新语句到来 / 面板刷新时调用）。返回首个产出的条目。 */
    fun closePending(): LogSqlEntry? {
        var first: LogSqlEntry? = null
        for (bucket in router.allBuckets()) {
            val produced = closePending(bucket)
            if (first == null) first = produced
        }
        return first
    }

    /** 闭合指定桶的未完成条目。 */
    private fun closePending(bucket: Bucket): LogSqlEntry? {
        val sql = if (bucket.collecting) {
            val s = bucket.multilineBuffer.toString().trim().take(maxParseLength).ifEmpty { null }
            bucket.collecting = false
            bucket.inComment = false
            bucket.multilineBuffer.clear()
            s
        } else {
            null
        } ?: return null
        val logTs = bucket.pendingLogLine?.let { LogTimestampParser.parse(it) }
        bucket.pendingLogLine = null
        val entry = LogSqlEntry(
            rawSql = sql,
            restoredSql = restorer.restore(sql, bucket.pendingParams),
            params = bucket.pendingParams.toList(),
            source = SqlSource.HIBERNATE,
            logTimestamp = logTs,
        )
        bucket.pendingParams.clear()

        // T-HibDualCapture：隔离槽合并——同 SQL 后继条目取代零参影子；不同语句影子先入列保序
        val shadow = bucket.pendingShadow
        bucket.pendingShadow = null
        if (shadow != null) {
            if (shadow.rawSql == entry.rawSql) {
                entries += entry
                return entry
            }
            entries += shadow
        }
        if (entry.params.isEmpty()) {
            bucket.pendingShadow = entry // 可能是被 stdout 副本覆盖的零参影子，先隔离待并
            return null
        }
        entries += entry
        return entry
    }

    /** 取出全部已闭合条目并清空缓冲区（面板刷新用）。 */
    fun takeEntries(): List<LogSqlEntry> {
        // T-HibDualCapture：隔离槽仅在对应桶无语句收集中时冲刷——收集中冲刷会让零参影子在
        // stdout 副本到达前就可见、合并失效；收集中说明该桶当前语句尚未闭合（末条目本就滞后一条）
        for (bucket in router.allBuckets()) {
            if (!bucket.collecting) {
                bucket.pendingShadow?.let { entries += it }
                bucket.pendingShadow = null
            }
        }
        val result = entries.toList()
        entries.clear()
        return result
    }

    /**
     * T-ConsoleFlushAlign：静默冲刷——闭合全部**已静默 ≥ [settleMs]** 的桶的待产出条目，产出进
     * [entries]（由调用方随下一次 [takeEntries] 取走），返回本次产出条数。
     *
     * 为什么必须有它：绑定参数行（TRACE 级，`logging.level` 常只开 `org.hibernate.SQL: debug` 不含
     * type 描述符）到达后要等「非 SQL 行」才闭合；参数行被截断时只等同线程**下一条语句行**闭合——
     * 实时链路从不调 [closePending]，应用安静后最后一条语句在桶里无限期滞留，`takeEntries()` 恒空。
     * 静默 ≥ 阈值即判定「后续行不会再来了」，此时闭合是安全的（同
     * [JdbcTemplateLogParser.flushSettled] 口径：宁交未还原形态，不整段丢弃）。
     *
     * 零参条目沿用 T-HibDualCapture 影子槽语义：[closePending] 对零参条目返回 null（条目暂存
     * `pendingShadow` 等待 stdout 副本合并）——随下一次 [takeEntries] 在非收集态桶上立即交出，不另设冲刷点。
     *
     * 调用点两处：① [processConsoleLine] 逐行入口（同控制台线程，喂行后调用，当前行的桶刚被触达故
     * 不被截断）；② 控制台桥的常规轮询 [SqlLogConsoleBridge.flushSettledEntries]（EDT）——应用之后
     * 一行都不再打时，靠这一拍兜底。
     */
    fun flushSettled(nowMs: Long = clock()): Int {
        var produced = 0
        for (bucket in router.settledBuckets(settleMs, nowMs)) {
            if (closePending(bucket) != null) produced++
        }
        return produced
    }

    // ---------- 私有方法 ----------

    /**
     * 处理 Hibernate: 前缀（含同行 SQL 或同行块注释）。
     *
     * 从 [Hibernate:] 之后的文本中进一步剥离块注释前缀，
     * 如果注释未闭合则进入 inComment 态。
     *
     * @return true 如果行包含 Hibernate: 前缀并已处理完毕。
     */
    private fun handleHibernatePrefix(bucket: Bucket, line: String): Boolean {
        val afterKeyword = extractAfterKeyword(line, sqlPrefix)
        if (afterKeyword == null) return false

        if (afterKeyword.trimStart().startsWith("/*")) {
            // Hibernate: 后紧跟块注释（同行闭合或跨行）
            val (unclosed, afterComment) = hasUnclosedComment(afterKeyword)
            closePending(bucket)
            bucket.collecting = true
            bucket.pendingLogLine = line
            bucket.pendingParams.clear()
            if (unclosed) {
                bucket.inComment = true
            } else {
                if (afterComment.isNotEmpty() && looksLikeSql(afterComment)) {
                    bucket.multilineBuffer.append(afterComment)
                } else {
                    bucket.collecting = false
                }
            }
        } else {
            closePending(bucket)
            bucket.collecting = true
            bucket.multilineBuffer.append(afterKeyword)
            bucket.pendingLogLine = line
            bucket.pendingParams.clear()
        }
        return true
    }

    /**
     * 尝试从行中提取参数绑定信息。
     * 格式：`binding parameter [N] as [TYPE] - [VALUE]`
     * @return LogSqlParam 或 null（非参数行）。
     */
    private fun extractBindingParam(line: String): LogSqlParam? {
        val match = if (mixedPrefix) {
            // 宽容模式：在行中搜索参数模式
            BINDING_PARAM_PATTERN.find(line)
        } else {
            // 严格模式：行首精确匹配
            BINDING_PARAM_PATTERN.matchEntire(line.trim())
        } ?: return null

        val type = match.groupValues[2].ifEmpty { null }
        val rawValue = match.groupValues[3]

        // Hibernate 输出 [null] 表示 null 字面量
        if (rawValue.equals("null", ignoreCase = true)) {
            return LogSqlParam(null, type)
        }

        // [] 表示空字符串（非 null）
        return LogSqlParam(rawValue, type)
    }

    /**
     * 提取关键字后的文本（用于显式前缀匹配）。
     */
    private fun extractAfterKeyword(line: String, keyword: String): String? {
        if (keyword.isEmpty()) return null
        val idx = if (mixedPrefix) {
            line.indexOf(keyword)
        } else {
            // 严格模式：行首精确前缀匹配（对齐 MyBatisLogParser 行为）
            if (line.startsWith(keyword)) 0 else -1
        }
        if (idx < 0) return null
        val text = line.substring(idx + keyword.length).trim()
        return text.ifEmpty { null }
    }

    /**
     * 行仅含前缀关键字（remainder 为空/纯空白）——分行形态信号。
     * 与 [MyBatisLogParser] 同名方法对齐，用于 Hibernate: 独占一行时进入等待态。
     */
    private fun isPrefixOnly(line: String, keyword: String): Boolean {
        if (keyword.isEmpty()) return false
        val idx = if (mixedPrefix) {
            line.indexOf(keyword)
        } else {
            if (line.startsWith(keyword)) 0 else -1
        }
        if (idx < 0) return false
        return line.substring(idx + keyword.length).trim().isEmpty()
    }

    /**
     * 检测行中块注释的状态。
     *
     * 返回值：
     * - Pair(true, "")：行含注释开始符但无同行结束符（未闭合）
     * - Pair(false, afterComment)：注释同行闭合，afterComment 为注释后的文本
     * - Pair(false, "")：行中无注释开始符
     */
    private fun hasUnclosedComment(line: String): Pair<Boolean, String> {
        val start = line.indexOf("/*")
        if (start < 0) return Pair(false, "")
        val end = line.lastIndexOf("*/")
        if (end < start + 2) return Pair(true, "")
        val afterComment = line.substring(end + 2).trim()
        return Pair(false, afterComment)
    }

    /** 文本是否以 SQL 关键字开头。 */
    private fun looksLikeSql(text: String): Boolean {
        val upper = text.trimStart().uppercase()
        return upper.startsWith("SELECT") || upper.startsWith("INSERT") ||
            upper.startsWith("UPDATE") || upper.startsWith("DELETE") ||
            upper.startsWith("MERGE") || upper.startsWith("WITH")
    }

    /**
     * 是否为 MyBatis/MyBatis-Plus 日志行（MP 前缀 / 原生 Preparing: / Parameters:）。
     * 多模块并发时，MyBatis 行可能混入 Hibernate 控制台，必须在 COLLECTING 态忽略。
     */
    private fun isMybatisLogLine(line: String): Boolean {
        if (MYBATIS_LINE_PATTERN.containsMatchIn(line)) return true
        // 原生格式：行中任意位置含 Preparing: 或 Parameters:
        return line.contains("Preparing:") || line.contains("Parameters:")
    }

    /**
     * 是否为日志框架前缀行（时间戳 + 日志级别 + 线程 + 类名），如 Spring Boot 默认格式：
     * `2026-08-22 00:34:11.251 DEBUG 37792 --- [nio-8083-exec-5] org.hibernate.SQL :`
     */
    private fun isLogFrameworkLine(line: String): Boolean = LOG_FRAMEWORK_PATTERN.containsMatchIn(line)

    companion object {
        const val DEFAULT_SQL_PREFIX = "Hibernate:"
        const val DEFAULT_PARAM_PREFIX = "binding parameter"
        const val DEFAULT_MAX_PARSE_LENGTH = 20000

        /**
         * T-ConsoleFlushAlign：静默冲刷默认阈值（毫秒），对齐 [JdbcTemplateLogParser.DEFAULT_SETTLE_MS]。
         *
         * 取 1 秒的由来：绑定参数行（TRACE）与语句行（DEBUG）由同线程相邻打印，间隔 1ms 量级；1 秒的
         * 静默足以判定「没有参数行/续行可等」，而误伤（把参数行留在条目外）需要该线程在两行之间
         * 卡住 1 秒以上。
         */
        const val DEFAULT_SETTLE_MS = 1000L

        /**
         * 纯 SQL 行模式：行首即 SQL 关键字。
         */
        private val BARE_SQL_PATTERN = Regex(
            """^(select|insert|update|delete|merge|with)\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 参数绑定行模式：`binding parameter [N] as [TYPE] - [VALUE]`
         * 组 1 = 位置索引（未使用，保留用于调试），组 2 = JDBC 类型，组 3 = 值。
         *
         * 注意：值部分使用 `(.*)` 贪婪匹配，支持值中包含 `]` 字符
         * （如 `binding parameter [1] as [VARCHAR] - [a]b]`）。
         */
        private val BINDING_PARAM_PATTERN = Regex(
            """binding parameter\s+\[(\d+)\]\s+as\s+\[(\w+)\]\s+-\s+\[(.*)\]""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * MyBatis/MyBatis-Plus 日志行模式：
         * - MP 格式：`==>  Preparing:` / `==> Parameters:`
         * - 日志框架前缀：`2026-08-22 ... org.hibernate.SQL :`（任意日志行含类名）
         */
        private val MYBATIS_LINE_PATTERN = Regex(
            """==>\s+(Preparing|Parameters):""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 日志框架前缀模式：时间戳开头 + 日志级别（DEBUG/TRACE/INFO/WARN/ERROR）。
         * 示例：`2026-08-22 00:34:11.251 DEBUG 37792 --- [nio-8083-exec-5] org.hibernate.SQL :`
         */
        private val LOG_FRAMEWORK_PATTERN = Regex(
            """^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(DEBUG|TRACE|INFO|WARN|ERROR)""",
        )
    }
}
